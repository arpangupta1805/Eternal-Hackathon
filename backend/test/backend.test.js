import { test, before } from 'node:test';
import assert from 'node:assert/strict';

import { STOCK_REISSUE_MS, STOCK_VALIDITY_MS, ACK_VALIDITY_MS, CLOCK_SKEW_MS, ORDER_VALIDITY_MS } from '../src/constants.js';
import { b64decode, b64encode, initCrypto } from '../src/crypto.js';
import { getMeta, getStore, resetData, setItemPrice, setItemStock, setStoreOpen } from '../src/db.js';
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
const T0 = 1_800_000_000_000;

before(() => initCrypto());

/** A fresh in-memory backend on a random port, with a movable clock and one enrolled device. */
async function setup(t) {
  const clock = { t: T0 };
  const ctx = await createContext({ now: () => clock.t });
  const server = createApp(ctx).listen(0);
  t.after(() => server.close());
  const url = `http://127.0.0.1:${server.address().port}`;
  const device = await enrollDevice(url, { role: 'CUSTOMER' });
  const catalog = await fetchCatalog(url);

  /** Builds, seals and uploads an order. Returns { body, packet, acks, result }. */
  async function order(lines, { bodyOpts = {}, mutate, trace, from = device } = {}) {
    const body = buildOrderBody(from, catalog, BLINKIT, lines, { now: clock.t, ...bodyOpts });
    if (mutate) mutate(body);
    const packet = sealOrder(from, body, trace ? { trace } : undefined);
    const acks = await uploadPackets(url, 'gateway1', [packet]);
    const result = acks.length === 1 ? openAck(from, acks[0]).result : null;
    return { body, packet, acks, result };
  }

  const outcomes = () => ctx.db.prepare('SELECT outcome FROM packet_log ORDER BY id').all().map((r) => r.outcome);
  const orderCount = () => ctx.db.prepare('SELECT COUNT(*) AS n FROM orders').get().n;
  return { ctx, clock, url, device, catalog, order, outcomes, orderCount };
}

const MILK = { item_id: 'itm_milk_500', qty: 2 }; // 3300 each
const BROWN = { item_id: 'itm_bread_brown', qty: 1 }; // 5500
const FEE = 2500;

test('health route', async (t) => {
  const { url } = await setup(t);
  const res = await fetch(`${url}/health`);
  assert.equal(res.status, 200);
  assert.equal((await res.json()).ok, true);
});

test('enroll validates input and stores node_id and role', async (t) => {
  const { url, ctx, device } = await setup(t);
  const row = ctx.db.prepare('SELECT * FROM devices WHERE device_id = ?').get(device.device_id);
  assert.equal(row.node_id, device.node_id);
  assert.equal(row.role, 'CUSTOMER');
  assert.match(device.device_id, /^dev_[a-z0-9]{6}$/);

  const bad = { name: 'x', phone: '9', address: {}, node_id: 'XYZ', role: 'RIDER', sign_pk: device.sign_pk, box_pk: device.box_pk };
  const res = await fetch(`${url}/v1/devices/enroll`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(bad) });
  assert.equal(res.status, 400);
  assert.equal((await res.json()).error, 'invalid_request');
});

test('catalog has two stores with items, integer prices and a version', async (t) => {
  const { catalog } = await setup(t);
  assert.equal(catalog.catalog_version, 1);
  assert.deepEqual(catalog.stores.map((s) => s.store_id).sort(), ['store_blinkit_01', 'store_zomato_01']);
  for (const s of catalog.stores) {
    assert.ok(s.items.length >= 10);
    assert.equal(s.open, true);
    for (const i of s.items) {
      assert.ok(Number.isInteger(i.price));
      assert.equal(i.in_stock, true);
    }
  }
});

test('happy path: CONFIRMED, verified and decrypted ACK, order row stored', async (t) => {
  const { order, ctx, clock, outcomes } = await setup(t);
  const { body, acks, result } = await order([MILK, BROWN], { trace: ['aaaaaaaa', 'bbbbbbbb', 'cccccccc', 'dddddddd'] });

  assert.equal(result.status, 'CONFIRMED');
  assert.equal(result.reason, null);
  assert.equal(result.server_order_ref, 'EL-000001');
  assert.equal(result.final_total, 2 * 3300 + 5500 + FEE);
  assert.equal(result.final_total, body.total_seen);
  assert.equal(result.delivery_fee, FEE);
  assert.equal(result.eta_minutes, 12);
  assert.deepEqual(result.changes, []);
  assert.deepEqual(result.final_items.map((i) => [i.item_id, i.qty, i.unit_price]), [['itm_milk_500', 2, 3300], ['itm_bread_brown', 1, 5500]]);

  const ack = acks[0];
  assert.deepEqual({ v: ack.v, type: ack.type, id: ack.id, hops_left: ack.hops_left, trace: ack.trace }, { v: 1, type: 'ACK', id: body.order_id, hops_left: 8, trace: [] });
  assert.equal(ack.expires_at, clock.t + ACK_VALIDITY_MS);

  const row = ctx.db.prepare('SELECT * FROM orders WHERE order_id = ?').get(body.order_id);
  assert.equal(row.hops, 3);
  assert.deepEqual(JSON.parse(row.trace), ['aaaaaaaa', 'bbbbbbbb', 'cccccccc', 'dddddddd']);
  assert.equal(row.gateway_node_id, 'gateway1');
  assert.deepEqual(JSON.parse(row.body), body);
  assert.deepEqual(outcomes(), ['ACCEPTED']);
});

test('ACK result can only be opened by the originating device', async (t) => {
  const { order, url } = await setup(t);
  const { acks } = await order([MILK]);
  const other = await enrollDevice(url);
  assert.throws(() => openAck(other, acks[0]), /cannot be opened/);
});

test('ACK with a forged signature is rejected by the device', async (t) => {
  const { order, device } = await setup(t);
  const { acks } = await order([MILK]);
  const bytes = b64decode(acks[0].payload);
  bytes[10] ^= 1;
  assert.throws(() => openAck(device, { ...acks[0], payload: b64encode(bytes) }), /signature is invalid/);
});

test('duplicate upload returns the identical ACK and is logged DUPLICATE', async (t) => {
  const { order, url, outcomes, orderCount, clock } = await setup(t);
  const { packet, acks } = await order([MILK]);
  clock.t += 5000;
  const again = await uploadPackets(url, 'gateway2', [packet]);
  assert.deepEqual(again, acks);
  assert.equal(orderCount(), 1);
  assert.deepEqual(outcomes(), ['ACCEPTED', 'DUPLICATE']);
});

test('same order twice in one upload gets one ACK', async (t) => {
  const { url, device, catalog, clock } = await setup(t);
  const packet = sealOrder(device, buildOrderBody(device, catalog, BLINKIT, [MILK], { now: clock.t }));
  const acks = await uploadPackets(url, 'g', [packet, packet]);
  assert.equal(acks.length, 1);
});

test('tampered payload: no ACK, logged TAMPERED, nothing stored', async (t) => {
  const { url, device, catalog, clock, outcomes, orderCount } = await setup(t);
  const packet = sealOrder(device, buildOrderBody(device, catalog, BLINKIT, [MILK], { now: clock.t }));
  const acks = await uploadPackets(url, 'g', [tamper(packet)]);
  assert.deepEqual(acks, []);
  assert.equal(orderCount(), 0);
  assert.deepEqual(outcomes(), ['TAMPERED']);

  // The intact copy of the same order is still accepted afterwards.
  const ok = await uploadPackets(url, 'g', [packet]);
  assert.equal(openAck(device, ok[0]).result.status, 'CONFIRMED');
});

test('unknown device: no ACK, logged TAMPERED', async (t) => {
  const { order, ctx, device, outcomes } = await setup(t);
  ctx.db.prepare('DELETE FROM devices WHERE device_id = ?').run(device.device_id);
  const { acks } = await order([MILK]);
  assert.deepEqual(acks, []);
  assert.deepEqual(outcomes(), ['TAMPERED']);
});

test('order signed by a different device key: no ACK, logged TAMPERED', async (t) => {
  const { order, url, device, outcomes } = await setup(t);
  const other = await enrollDevice(url);
  // Signed with `other`'s key but claiming to be `device`.
  const { acks } = await order([MILK], { from: other, mutate: (b) => { b.device_id = device.device_id; } });
  assert.deepEqual(acks, []);
  assert.deepEqual(outcomes(), ['TAMPERED']);
});

test('envelope id different from body order_id: no ACK, logged TAMPERED', async (t) => {
  const { url, device, catalog, clock, outcomes } = await setup(t);
  const packet = sealOrder(device, buildOrderBody(device, catalog, BLINKIT, [MILK], { now: clock.t }));
  const acks = await uploadPackets(url, 'g', [{ ...packet, id: '00000000-0000-4000-8000-000000000000' }]);
  assert.deepEqual(acks, []);
  assert.deepEqual(outcomes(), ['TAMPERED']);
});

test('signed but invalid body (qty 0): no ACK, logged MALFORMED', async (t) => {
  const { order, outcomes, orderCount } = await setup(t);
  const { acks } = await order([MILK], { mutate: (b) => { b.items[0].qty = 0; } });
  assert.deepEqual(acks, []);
  assert.equal(orderCount(), 0);
  assert.deepEqual(outcomes(), ['MALFORMED']);
});

test('malformed envelopes are logged, ACK and STOCK packets are ignored', async (t) => {
  const { url, order, outcomes } = await setup(t);
  const { acks } = await order([MILK]);
  const stock = (await fetchStock(url)).packets;
  const res = await uploadPackets(url, 'g', [acks[0], stock[0], { v: 2, type: 'ORDER' }, 'nonsense', null]);
  assert.deepEqual(res, []);
  assert.deepEqual(outcomes(), ['ACCEPTED', 'MALFORMED', 'MALFORMED', 'MALFORMED']);

  const bad = await fetch(`${url}/v1/mesh/packets`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{"packets": 5}' });
  assert.equal(bad.status, 400);
});

test('expired order is REJECTED / EXPIRED, and the answer is stored', async (t) => {
  const { order, url } = await setup(t);
  const { result, packet, acks } = await order([MILK], { bodyOpts: { expired: true } });
  assert.equal(result.status, 'REJECTED');
  assert.equal(result.reason, 'EXPIRED');
  assert.deepEqual(result.final_items, []);
  assert.equal(result.final_total, 0);
  assert.equal(result.server_order_ref, null);
  assert.deepEqual(await uploadPackets(url, 'g', [packet]), acks);
});

test('order just past expires_at but inside the clock skew allowance is accepted', async (t) => {
  const { url, device, catalog, clock } = await setup(t);
  const body = buildOrderBody(device, catalog, BLINKIT, [MILK], { now: clock.t });
  const packet = sealOrder(device, body);
  clock.t += ORDER_VALIDITY_MS + CLOCK_SKEW_MS; // exactly at the limit: not later than
  const acks = await uploadPackets(url, 'g', [packet]);
  assert.equal(openAck(device, acks[0]).result.status, 'CONFIRMED');

  const late = sealOrder(device, buildOrderBody(device, catalog, BLINKIT, [MILK], { now: clock.t }));
  clock.t += ORDER_VALIDITY_MS + CLOCK_SKEW_MS + 1;
  const lateAcks = await uploadPackets(url, 'g', [late]);
  assert.equal(openAck(device, lateAcks[0]).result.reason, 'EXPIRED');
});

test('store closed: REJECTED / STORE_CLOSED', async (t) => {
  const { order, ctx } = await setup(t);
  setStoreOpen(ctx.db, BLINKIT, false);
  const { result } = await order([MILK]);
  assert.equal(result.status, 'REJECTED');
  assert.equal(result.reason, 'STORE_CLOSED');
});

test('unknown store: REJECTED / STORE_CLOSED', async (t) => {
  const { order } = await setup(t);
  const { result } = await order([MILK], { mutate: (b) => { b.store_id = 'store_nope'; } });
  assert.equal(result.reason, 'STORE_CLOSED');
});

test('on_unavailable DROP: item removed, CONFIRMED_MODIFIED', async (t) => {
  const { order, ctx } = await setup(t);
  setItemStock(ctx.db, 'itm_bread_brown', false);
  const { result } = await order([MILK, { ...BROWN, on_unavailable: 'DROP' }]);
  assert.equal(result.status, 'CONFIRMED_MODIFIED');
  assert.deepEqual(result.changes, [{ item_id: 'itm_bread_brown', change: 'DROPPED' }]);
  assert.deepEqual(result.final_items.map((i) => i.item_id), ['itm_milk_500']);
  assert.equal(result.final_total, 2 * 3300 + FEE);
});

test('on_unavailable SUBSTITUTE: replaced at the substitute current price, same qty', async (t) => {
  const { order, ctx } = await setup(t);
  setItemStock(ctx.db, 'itm_bread_brown', false);
  const { result } = await order([MILK, { ...BROWN, qty: 2, on_unavailable: 'SUBSTITUTE', substitute_item_id: 'itm_bread_white' }]);
  assert.equal(result.status, 'CONFIRMED_MODIFIED');
  assert.deepEqual(result.changes, [{ item_id: 'itm_bread_brown', change: 'SUBSTITUTED', new_item_id: 'itm_bread_white' }]);
  assert.deepEqual(result.final_items[1], { item_id: 'itm_bread_white', name: 'White bread', qty: 2, unit_price: 5000 });
  assert.equal(result.final_total, 2 * 3300 + 2 * 5000 + FEE);
});

test('on_unavailable CANCEL_ORDER: REJECTED / ITEM_UNAVAILABLE', async (t) => {
  const { order, ctx } = await setup(t);
  setItemStock(ctx.db, 'itm_bread_brown', false);
  const { result } = await order([MILK, { ...BROWN, on_unavailable: 'CANCEL_ORDER' }]);
  assert.equal(result.status, 'REJECTED');
  assert.equal(result.reason, 'ITEM_UNAVAILABLE');
});

test('substitute also unavailable: treated as DROP', async (t) => {
  const { order, ctx } = await setup(t);
  setItemStock(ctx.db, 'itm_bread_brown', false);
  setItemStock(ctx.db, 'itm_bread_white', false);
  const { result } = await order([MILK, { ...BROWN, on_unavailable: 'SUBSTITUTE', substitute_item_id: 'itm_bread_white' }]);
  assert.equal(result.status, 'CONFIRMED_MODIFIED');
  assert.deepEqual(result.changes, [{ item_id: 'itm_bread_brown', change: 'DROPPED' }]);
});

test('substitute from another store is not used', async (t) => {
  const { order, ctx } = await setup(t);
  setItemStock(ctx.db, 'itm_bread_brown', false);
  const { result } = await order([MILK, { ...BROWN, on_unavailable: 'SUBSTITUTE', substitute_item_id: 'itm_naan_garlic' }]);
  assert.deepEqual(result.changes, [{ item_id: 'itm_bread_brown', change: 'DROPPED' }]);
});

test('all items dropped: REJECTED / ITEM_UNAVAILABLE', async (t) => {
  const { order, ctx } = await setup(t);
  setItemStock(ctx.db, 'itm_bread_brown', false);
  setItemStock(ctx.db, 'itm_milk_500', false);
  const { result } = await order([MILK, BROWN]);
  assert.equal(result.status, 'REJECTED');
  assert.equal(result.reason, 'ITEM_UNAVAILABLE');
});

test('price above max_total: REJECTED / PRICE_EXCEEDED', async (t) => {
  const { order, ctx } = await setup(t);
  setItemPrice(ctx.db, 'itm_milk_500', 3300 + 1001); // 2 x 1001 = 2002 over, tolerance is 2000
  const { result } = await order([MILK]);
  assert.equal(result.status, 'REJECTED');
  assert.equal(result.reason, 'PRICE_EXCEEDED');
});

test('price changed within tolerance: CONFIRMED_MODIFIED at the current price', async (t) => {
  const { order, ctx } = await setup(t);
  setItemPrice(ctx.db, 'itm_milk_500', 3300 + 1000); // exactly max_total
  const { result, body } = await order([MILK]);
  assert.equal(result.status, 'CONFIRMED_MODIFIED');
  assert.deepEqual(result.changes, [{ item_id: 'itm_milk_500', change: 'PRICE_CHANGED', old_price: 3300, new_price: 4300 }]);
  assert.equal(result.final_total, body.max_total);
});

test('price drop: CONFIRMED_MODIFIED with a lower total', async (t) => {
  const { order, ctx } = await setup(t);
  setItemPrice(ctx.db, 'itm_milk_500', 3000);
  const { result } = await order([MILK]);
  assert.equal(result.status, 'CONFIRMED_MODIFIED');
  assert.equal(result.final_total, 2 * 3000 + FEE);
});

test('order refs count up and rejected orders do not use one', async (t) => {
  const { order, ctx } = await setup(t);
  assert.equal((await order([MILK])).result.server_order_ref, 'EL-000001');
  setItemStock(ctx.db, 'itm_bread_brown', false);
  assert.equal((await order([{ ...BROWN, on_unavailable: 'CANCEL_ORDER' }])).result.server_order_ref, null);
  assert.equal((await order([MILK])).result.server_order_ref, 'EL-000002');
});

test('STOCK packets: signed, full snapshot, seq increments on change', async (t) => {
  const { url, ctx, device, clock } = await setup(t);
  const first = (await fetchStock(url)).packets;
  assert.equal(first.length, 2);
  const p1 = first.find((p) => p.id.startsWith(BLINKIT));
  const s1 = openStock(device.server_sign_pk, p1);
  assert.deepEqual(s1, { store_id: BLINKIT, seq: 1, issued_at: clock.t, open: true, unavailable_item_ids: [] });
  assert.deepEqual({ v: p1.v, type: p1.type, id: p1.id, hops_left: p1.hops_left, trace: p1.trace }, { v: 1, type: 'STOCK', id: `${BLINKIT}:1:${clock.t}`, hops_left: 8, trace: [] });
  assert.equal(p1.expires_at, clock.t + STOCK_VALIDITY_MS);

  // Unchanged and young: the cached packet is returned as is.
  clock.t += 1000;
  assert.deepEqual((await fetchStock(url)).packets, first);

  setItemStock(ctx.db, 'itm_bread_brown', false);
  setItemStock(ctx.db, 'itm_eggs_6', false);
  setStoreOpen(ctx.db, BLINKIT, false);
  const p2 = (await fetchStock(url)).packets.find((p) => p.id.startsWith(BLINKIT));
  const s2 = openStock(device.server_sign_pk, p2);
  assert.equal(s2.seq, 4);
  assert.equal(s2.open, false);
  assert.deepEqual(s2.unavailable_item_ids, ['itm_bread_brown', 'itm_eggs_6']);

  // Price changes do not touch stock_seq, only catalog_version.
  const before = getMeta(ctx.db, 'catalog_version');
  setItemPrice(ctx.db, 'itm_milk_500', 3400);
  assert.equal(getStore(ctx.db, BLINKIT).stock_seq, 4);
  assert.equal(getMeta(ctx.db, 'catalog_version'), before + 1);

  // A forged packet does not verify.
  const bytes = b64decode(p2.payload);
  bytes[bytes.length - 3] ^= 1;
  assert.throws(() => openStock(device.server_sign_pk, { ...p2, payload: b64encode(bytes) }), /signature is invalid/);
});

test('STOCK re-issue: same seq and contents, later issued_at, new id', async (t) => {
  const { url, device, clock } = await setup(t);
  const pick = async () => (await fetchStock(url)).packets.find((p) => p.id.startsWith(BLINKIT));
  const p1 = await pick();
  clock.t += STOCK_REISSUE_MS - 1;
  assert.deepEqual(await pick(), p1);
  clock.t += 1;
  const p2 = await pick();
  const s1 = openStock(device.server_sign_pk, p1);
  const s2 = openStock(device.server_sign_pk, p2);
  assert.notEqual(p2.id, p1.id);
  assert.equal(s2.seq, s1.seq);
  assert.ok(s2.issued_at > s1.issued_at);
  assert.equal(p2.expires_at, s2.issued_at + STOCK_VALIDITY_MS);
  assert.deepEqual(s2.unavailable_item_ids, s1.unavailable_item_ids);
});

test('reset keeps devices, clears orders, re-seeds, and never lowers counters', async (t) => {
  const { ctx, order, url, device, orderCount } = await setup(t);
  await order([MILK]);
  setItemStock(ctx.db, 'itm_bread_brown', false);
  setItemPrice(ctx.db, 'itm_milk_500', 9999);
  const seqBefore = getStore(ctx.db, BLINKIT).stock_seq;
  const versionBefore = getMeta(ctx.db, 'catalog_version');

  resetData(ctx.db);

  assert.equal(orderCount(), 0);
  assert.equal(ctx.db.prepare('SELECT COUNT(*) AS n FROM packet_log').get().n, 0);
  assert.equal(ctx.db.prepare('SELECT COUNT(*) AS n FROM devices').get().n, 1);
  assert.equal(getStore(ctx.db, BLINKIT).stock_seq, seqBefore + 1);
  assert.equal(getStore(ctx.db, 'store_zomato_01').stock_seq, 2);
  assert.equal(getMeta(ctx.db, 'catalog_version'), versionBefore + 1);

  const catalog = await fetchCatalog(url);
  const blinkit = catalog.stores.find((s) => s.store_id === BLINKIT);
  assert.equal(blinkit.items.find((i) => i.item_id === 'itm_bread_brown').in_stock, true);
  assert.equal(blinkit.items.find((i) => i.item_id === 'itm_milk_500').price, 3300);

  // The STOCK packet after reset is newer than anything a phone holds.
  const p = (await fetchStock(url)).packets.find((x) => x.id.startsWith(BLINKIT));
  assert.equal(openStock(device.server_sign_pk, p).seq, seqBefore + 1);

  // The enrolled device still works, and the order counter did not restart.
  assert.equal((await order([MILK])).result.server_order_ref, 'EL-000002');
});
