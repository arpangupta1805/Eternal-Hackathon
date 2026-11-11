#!/usr/bin/env node
// A CLI that acts as a phone, so the backend can be tested without the app.
//
//   node scripts/fake-device.js enroll [--server URL] [--role CUSTOMER|RIDER|STORE] [--name NAME]
//   node scripts/fake-device.js order --store STORE_ID --item ITEM:QTY[:RULE[:SUBSTITUTE]] [--item ...]
//        [--seen ITEM=PAISE] [--tolerance PAISE] [--trace id,id] [--tamper] [--twice] [--expired]
//
// RULE is DROP (default), SUBSTITUTE or CANCEL_ORDER.
// Exit code is 0 when the outcome is the expected one for the flags given, 1 otherwise.

import fs from 'node:fs';
import path from 'node:path';
import { parseArgs } from 'node:util';
import { initCrypto } from '../src/crypto.js';
import { DATA_DIR } from '../src/server.js';
import {
  buildOrderBody,
  enrollDevice,
  fetchCatalog,
  openAck,
  sealOrder,
  tamper,
  uploadPackets,
} from './device-lib.js';

const { values: opts, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    server: { type: 'string' },
    state: { type: 'string', default: path.join(DATA_DIR, 'fake-device.json') },
    role: { type: 'string', default: 'CUSTOMER' },
    name: { type: 'string', default: 'Fake Device' },
    store: { type: 'string', default: 'store_blinkit_01' },
    item: { type: 'string', multiple: true, default: [] },
    seen: { type: 'string', multiple: true, default: [] },
    tolerance: { type: 'string' },
    trace: { type: 'string' },
    tamper: { type: 'boolean', default: false },
    twice: { type: 'boolean', default: false },
    expired: { type: 'boolean', default: false },
  },
});

const rupees = (paise) => `₹${(paise / 100).toFixed(2)}`;

function fail(message) {
  console.error(`FAIL: ${message}`);
  process.exit(1);
}

function printResult(result) {
  console.log(`  status:     ${result.status}${result.reason ? ` (${result.reason})` : ''}`);
  if (result.server_order_ref) console.log(`  order ref:  ${result.server_order_ref}`);
  for (const i of result.final_items) {
    console.log(`  item:       ${i.qty} x ${i.name} @ ${rupees(i.unit_price)}`);
  }
  for (const c of result.changes) {
    const detail =
      c.change === 'SUBSTITUTED' ? ` -> ${c.new_item_id}`
      : c.change === 'PRICE_CHANGED' ? ` ${rupees(c.old_price)} -> ${rupees(c.new_price)}`
      : '';
    console.log(`  change:     ${c.item_id} ${c.change}${detail}`);
  }
  if (result.status !== 'REJECTED') {
    console.log(`  delivery:   ${rupees(result.delivery_fee)}`);
    console.log(`  total:      ${rupees(result.final_total)}`);
    console.log(`  eta:        ${result.eta_minutes} min`);
  }
}

async function cmdEnroll() {
  const server = (opts.server ?? 'http://localhost:8080').replace(/\/$/, '');
  const device = await enrollDevice(server, { name: opts.name, role: opts.role });
  fs.mkdirSync(path.dirname(opts.state), { recursive: true });
  fs.writeFileSync(opts.state, JSON.stringify(device, null, 2), { mode: 0o600 });
  console.log(`Enrolled ${device.device_id} (node ${device.node_id}, ${device.role}) at ${server}`);
  console.log(`State saved to ${opts.state}`);
}

async function cmdOrder() {
  if (!fs.existsSync(opts.state)) fail(`no device state at ${opts.state}. Run "enroll" first.`);
  const device = JSON.parse(fs.readFileSync(opts.state, 'utf8'));
  const server = (opts.server ?? device.server_url).replace(/\/$/, '');
  if (opts.item.length === 0) fail('give at least one --item ITEM:QTY[:RULE[:SUBSTITUTE]]');

  const seen = Object.fromEntries(opts.seen.map((s) => s.split('=')));
  const lines = opts.item.map((spec) => {
    const [itemId, qty = '1', rule = 'DROP', substitute] = spec.split(':');
    return {
      item_id: itemId,
      qty: Number(qty),
      on_unavailable: rule,
      substitute_item_id: substitute ?? null,
      unit_price_seen: seen[itemId] !== undefined ? Number(seen[itemId]) : undefined,
    };
  });

  const catalog = await fetchCatalog(server);
  const body = buildOrderBody(device, catalog, opts.store, lines, {
    tolerance: opts.tolerance !== undefined ? Number(opts.tolerance) : undefined,
    expired: opts.expired,
  });
  const extraHops = opts.trace ? opts.trace.split(',').filter(Boolean) : [];
  const trace = [device.node_id, ...extraHops];
  const gatewayNodeId = trace[trace.length - 1];
  let packet = sealOrder(device, body, { trace });
  if (opts.tamper) packet = tamper(packet);

  console.log(`Order ${body.order_id}`);
  console.log(`  store ${body.store_id}, ${body.items.length} line(s), total seen ${rupees(body.total_seen)}, max ${rupees(body.max_total)}`);
  console.log(`  trace ${trace.join(' -> ')} -> server (${trace.length - 1} hop(s))${opts.tamper ? '  [payload tampered]' : ''}`);

  const first = await uploadPackets(server, gatewayNodeId, [packet]);

  if (opts.tamper) {
    if (first.length !== 0) fail('a tampered packet got an ACK');
    console.log('No ACK returned. The server dropped the tampered packet, as expected.');
    return;
  }

  if (first.length !== 1) fail(`expected 1 ACK, got ${first.length}`);
  const { result, issued_at: issuedAt } = openAck(device, first[0]);
  console.log(`ACK verified (server signature ok, result decrypted), issued_at ${issuedAt}`);
  printResult(result);

  if (opts.expired && !(result.status === 'REJECTED' && result.reason === 'EXPIRED')) {
    fail('an expired order was not rejected as EXPIRED');
  }

  if (opts.twice) {
    const second = await uploadPackets(server, gatewayNodeId, [packet]);
    if (second.length !== 1) fail(`second upload: expected 1 ACK, got ${second.length}`);
    if (JSON.stringify(second[0]) !== JSON.stringify(first[0])) fail('second upload returned a different ACK');
    console.log('Second upload returned the identical ACK (duplicate handled).');
  }
}

await initCrypto();
const command = positionals[0];
try {
  if (command === 'enroll') await cmdEnroll();
  else if (command === 'order') await cmdOrder();
  else fail('usage: fake-device.js <enroll|order> [options]. See the header of this file.');
} catch (err) {
  fail(err.message);
}
