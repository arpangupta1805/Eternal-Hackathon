import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { WebSocket } from 'ws';

import { initCrypto } from '../src/crypto.js';
import { attachWebSocket } from '../src/events.js';
import { createApp, createContext } from '../src/server.js';
import {
  buildOrderBody,
  enrollDevice,
  fetchCatalog,
  fetchStock,
  openAck,
  openStock,
  sealOrder,
  tamper,
  uploadPackets,
} from '../scripts/device-lib.js';

const BLINKIT = 'store_blinkit_01';

before(() => initCrypto());

async function setup(t) {
  const clock = { t: 1_800_000_000_000 };
  const ctx = await createContext({ now: () => clock.t });
  const server = createApp(ctx).listen(0);
  const wss = attachWebSocket(server, ctx);
  t.after(() => {
    for (const c of wss.clients) c.terminate();
    server.close();
  });
  const port = server.address().port;
  const url = `http://127.0.0.1:${port}`;
  const admin = async (method, path, body) => {
    const res = await fetch(`${url}/admin/api${path}`, {
      method,
      headers: body ? { 'content-type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined,
    });
    return { status: res.status, json: await res.json() };
  };
  return { ctx, clock, url, port, admin };
}

/** Opens a dashboard socket and collects the messages it receives. */
async function dashboard(t, port) {
  const ws = new WebSocket(`ws://127.0.0.1:${port}/admin/ws`);
  const messages = [];
  const waiters = [];
  ws.on('message', (raw) => {
    messages.push(JSON.parse(raw.toString()));
    waiters.splice(0).forEach((w) => w());
  });
  await new Promise((resolve, reject) => {
    ws.once('open', resolve);
    ws.once('error', reject);
  });
  t.after(() => ws.terminate());
  const waitFor = async (pred) => {
    const deadline = Date.now() + 2000;
    for (;;) {
      const found = messages.find(pred);
      if (found) return found;
      if (Date.now() > deadline) throw new Error('timed out waiting for a dashboard message');
      await new Promise((resolve) => {
        waiters.push(resolve);
        setTimeout(resolve, 100);
      });
    }
  };
  return { messages, waitFor };
}

test('dashboard page and assets are served', async (t) => {
  const { url } = await setup(t);
  const page = await fetch(`${url}/`);
  assert.equal(page.status, 200);
  assert.match(await page.text(), /Eternal Lite/);
  assert.equal((await fetch(`${url}/app.js`)).status, 200);
  assert.equal((await fetch(`${url}/style.css`)).status, 200);
});

test('state has stores, orders with labelled trace, and metrics', async (t) => {
  const { url, admin, clock } = await setup(t);
  const customer = await enrollDevice(url, { role: 'CUSTOMER' });
  const rider = await enrollDevice(url, { role: 'RIDER' });
  const store = await enrollDevice(url, { role: 'STORE' });
  const catalog = await fetchCatalog(url);

  const body = buildOrderBody(customer, catalog, BLINKIT, [{ item_id: 'itm_milk_500', qty: 2 }], { now: clock.t });
  const trace = [customer.node_id, rider.node_id, store.node_id, 'ffffffff'];
  const packet = sealOrder(customer, body, { trace });
  clock.t += 4200;
  await uploadPackets(url, 'ffffffff', [packet]);
  await uploadPackets(url, 'ffffffff', [packet]); // duplicate
  const other = sealOrder(customer, buildOrderBody(customer, catalog, BLINKIT, [{ item_id: 'itm_eggs_6', qty: 1 }], { now: clock.t }));
  await uploadPackets(url, customer.node_id, [tamper(other)]); // tampered

  const { status, json } = await admin('GET', '/state');
  assert.equal(status, 200);
  assert.equal(json.stores.length, 2);
  assert.equal(json.orders.length, 1);
  const o = json.orders[0];
  assert.equal(o.server_order_ref, 'EL-000001');
  assert.equal(o.status, 'CONFIRMED');
  assert.equal(o.store_name, 'Blinkit, Kudasan');
  assert.equal(o.hops, 3);
  assert.equal(o.latency_ms, 4200);
  assert.deepEqual(o.trace, [
    { node_id: customer.node_id, role: 'CUSTOMER' },
    { node_id: rider.node_id, role: 'RIDER' },
    { node_id: store.node_id, role: 'STORE' },
    { node_id: 'ffffffff', role: null },
  ]);
  assert.equal(o.final_total, 2 * 3300 + 2500);
  assert.equal(o.requested_items.length, 1);

  assert.deepEqual(json.metrics, {
    orders_received: 1,
    orders_confirmed: 1,
    orders_modified: 0,
    orders_rejected: 0,
    duplicates_dropped: 1,
    tampered_rejected: 1,
    tampered_accepted: 0,
    malformed: 0,
    avg_hops: 3,
    avg_latency_ms: 4200,
    median_latency_ms: 4200,
  });
});

test('toggling stock changes GET /v1/mesh/stock and the next order result', async (t) => {
  const { url, admin, clock } = await setup(t);
  const device = await enrollDevice(url);
  const catalog = await fetchCatalog(url); // the phone's cached catalog: brown bread in stock
  const lines = [{ item_id: 'itm_bread_brown', qty: 1, on_unavailable: 'SUBSTITUTE', substitute_item_id: 'itm_bread_white' }];
  const place = async () => {
    const packet = sealOrder(device, buildOrderBody(device, catalog, BLINKIT, lines, { now: clock.t }));
    return openAck(device, (await uploadPackets(url, 'g', [packet]))[0]).result;
  };
  const stockOf = async () => {
    const p = (await fetchStock(url)).packets.find((x) => x.id.startsWith(BLINKIT));
    return openStock(device.server_sign_pk, p);
  };

  assert.equal((await place()).status, 'CONFIRMED');
  assert.deepEqual((await stockOf()).unavailable_item_ids, []);

  const res = await admin('POST', '/items/itm_bread_brown/stock', { in_stock: false });
  assert.equal(res.status, 200);
  assert.equal(res.json.stores.find((s) => s.store_id === BLINKIT).items.find((i) => i.item_id === 'itm_bread_brown').in_stock, false);
  assert.equal(res.json.catalog_version, 2);

  const snap = await stockOf();
  assert.equal(snap.seq, 2);
  assert.deepEqual(snap.unavailable_item_ids, ['itm_bread_brown']);

  const result = await place();
  assert.equal(result.status, 'CONFIRMED_MODIFIED');
  assert.deepEqual(result.changes, [{ item_id: 'itm_bread_brown', change: 'SUBSTITUTED', new_item_id: 'itm_bread_white' }]);
});

test('price and store-open endpoints, with validation', async (t) => {
  const { admin, ctx } = await setup(t);
  assert.equal((await admin('POST', '/items/itm_milk_500/price', { price: 3500 })).status, 200);
  assert.equal(ctx.db.prepare("SELECT price FROM items WHERE item_id = 'itm_milk_500'").get().price, 3500);
  assert.equal(ctx.db.prepare('SELECT stock_seq FROM stores WHERE store_id = ?').get(BLINKIT).stock_seq, 1);

  const closed = await admin('POST', `/stores/${BLINKIT}/open`, { open: false });
  assert.equal(closed.json.stores.find((s) => s.store_id === BLINKIT).open, false);
  assert.equal(closed.json.stores.find((s) => s.store_id === BLINKIT).stock_seq, 2);

  assert.equal((await admin('POST', '/items/itm_milk_500/price', { price: 35.5 })).status, 400);
  assert.equal((await admin('POST', '/items/itm_milk_500/price', { price: 0 })).status, 400);
  assert.equal((await admin('POST', '/items/itm_milk_500/stock', { in_stock: 'no' })).status, 400);
  assert.equal((await admin('POST', '/items/itm_nope/stock', { in_stock: false })).status, 404);
  assert.equal((await admin('POST', '/items/itm_nope/price', { price: 100 })).status, 404);
  assert.equal((await admin('POST', '/stores/store_nope/open', { open: true })).status, 404);
});

test('WebSocket pushes order, packet and catalog events', async (t) => {
  const { url, port, admin, clock } = await setup(t);
  const device = await enrollDevice(url, { role: 'RIDER' });
  const catalog = await fetchCatalog(url);
  const dash = await dashboard(t, port);

  const packet = sealOrder(device, buildOrderBody(device, catalog, BLINKIT, [{ item_id: 'itm_milk_500', qty: 1 }], { now: clock.t }));
  await uploadPackets(url, device.node_id, [packet]);

  const order = await dash.waitFor((m) => m.event === 'order');
  assert.equal(order.data.order.server_order_ref, 'EL-000001');
  assert.deepEqual(order.data.order.trace, [{ node_id: device.node_id, role: 'RIDER' }]);
  assert.equal(order.data.metrics.orders_confirmed, 1);

  const pkt = await dash.waitFor((m) => m.event === 'packet');
  assert.equal(pkt.data.outcome, 'ACCEPTED');
  assert.equal(pkt.data.packet_key, `ORDER:${packet.id}`);

  await uploadPackets(url, device.node_id, [tamper({ ...packet, id: '11111111-1111-4111-8111-111111111111' })]);
  const bad = await dash.waitFor((m) => m.event === 'packet' && m.data.outcome === 'TAMPERED');
  assert.equal(bad.data.metrics.tampered_rejected, 1);
  assert.equal(bad.data.metrics.tampered_accepted, 0);

  await admin('POST', '/items/itm_eggs_6/stock', { in_stock: false });
  const cat = await dash.waitFor((m) => m.event === 'catalog');
  assert.equal(cat.data.stores.find((s) => s.store_id === BLINKIT).items.find((i) => i.item_id === 'itm_eggs_6').in_stock, false);
  assert.equal(cat.data.orders.length, 1);
});

test('admin reset clears orders, keeps devices, and tells the dashboard', async (t) => {
  const { url, port, admin, ctx, clock } = await setup(t);
  const device = await enrollDevice(url);
  const catalog = await fetchCatalog(url);
  await uploadPackets(url, 'g', [sealOrder(device, buildOrderBody(device, catalog, BLINKIT, [{ item_id: 'itm_milk_500', qty: 1 }], { now: clock.t }))]);
  await admin('POST', '/items/itm_milk_500/stock', { in_stock: false });
  const dash = await dashboard(t, port);

  const res = await admin('POST', '/reset');
  assert.equal(res.status, 200);
  assert.equal(res.json.orders.length, 0);
  assert.equal(res.json.metrics.orders_received, 0);
  assert.equal(res.json.stores.find((s) => s.store_id === BLINKIT).stock_seq, 3);
  assert.equal(ctx.db.prepare('SELECT COUNT(*) AS n FROM devices').get().n, 1);
  const cat = await dash.waitFor((m) => m.event === 'catalog');
  assert.equal(cat.data.orders.length, 0);
});

test('WebSocket upgrade on another path is refused', async (t) => {
  const { port } = await setup(t);
  const ws = new WebSocket(`ws://127.0.0.1:${port}/other`);
  await assert.rejects(new Promise((resolve, reject) => {
    ws.once('open', resolve);
    ws.once('error', reject);
  }));
});
