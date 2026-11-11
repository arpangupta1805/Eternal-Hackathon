// The phone's side of the protocol (docs/03-protocol.md), in JavaScript.
// Used by fake-device.js and by the tests. This is the byte-level reference for the Android app.

import crypto from 'node:crypto';
import {
  DEFAULT_PRICE_TOLERANCE,
  INITIAL_HOPS,
  ORDER_VALIDITY_MS,
  PROTOCOL_VERSION,
} from '../src/constants.js';
import {
  b64decode,
  b64encode,
  fromUtf8,
  generateBoxKeypair,
  generateSignKeypair,
  seal,
  sealOpen,
  sign,
  signOpen,
  utf8,
} from '../src/crypto.js';

export const DEFAULT_ADDRESS = {
  line1: 'Room 214, Hostel H',
  line2: 'IIT Gandhinagar',
  landmark: 'Near mess',
  lat: 23.2156,
  lng: 72.6842,
};

async function http(method, url, body) {
  const res = await fetch(url, {
    method,
    headers: body ? { 'content-type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  const json = await res.json().catch(() => null);
  if (!res.ok) throw new Error(`${method} ${url} failed: ${res.status} ${JSON.stringify(json)}`);
  return json;
}

/** Generates keys and a node_id, enrolls, and returns the device state (JSON-serialisable). */
export async function enrollDevice(serverUrl, { name = 'Fake Device', phone = '9000000000', role = 'CUSTOMER', address = DEFAULT_ADDRESS } = {}) {
  const signKeys = generateSignKeypair();
  const boxKeys = generateBoxKeypair();
  const nodeId = crypto.randomBytes(4).toString('hex');
  const res = await http('POST', `${serverUrl}/v1/devices/enroll`, {
    name,
    phone,
    address,
    node_id: nodeId,
    role,
    sign_pk: b64encode(signKeys.pk),
    box_pk: b64encode(boxKeys.pk),
  });
  return {
    server_url: serverUrl,
    device_id: res.device_id,
    node_id: nodeId,
    role,
    phone,
    address,
    sign_pk: b64encode(signKeys.pk),
    sign_sk: b64encode(signKeys.sk),
    box_pk: b64encode(boxKeys.pk),
    box_sk: b64encode(boxKeys.sk),
    server_sign_pk: res.server_sign_pk,
    server_box_pk: res.server_box_pk,
  };
}

export function fetchCatalog(serverUrl) {
  return http('GET', `${serverUrl}/v1/catalog`);
}

export function fetchStock(serverUrl) {
  return http('GET', `${serverUrl}/v1/mesh/stock`);
}

/**
 * Builds an OrderBody from the catalog the device has "seen".
 * lines: [{ item_id, qty, on_unavailable?, substitute_item_id?, unit_price_seen? }]
 */
export function buildOrderBody(device, catalog, storeId, lines, { now = Date.now(), tolerance = DEFAULT_PRICE_TOLERANCE, expired = false, note = '' } = {}) {
  const store = catalog.stores.find((s) => s.store_id === storeId);
  if (!store) throw new Error(`store ${storeId} is not in the catalog`);
  const items = lines.map((l) => {
    const seen = l.unit_price_seen ?? store.items.find((i) => i.item_id === l.item_id)?.price;
    if (seen === undefined) throw new Error(`item ${l.item_id} is not in store ${storeId}`);
    return {
      item_id: l.item_id,
      qty: l.qty,
      unit_price_seen: seen,
      on_unavailable: l.on_unavailable ?? 'DROP',
      substitute_item_id: l.substitute_item_id ?? null,
    };
  });
  const totalSeen = items.reduce((sum, i) => sum + i.unit_price_seen * i.qty, 0) + store.delivery_fee;
  // --expired: created far enough back that expires_at is beyond the server's clock skew allowance.
  const createdAt = expired ? now - ORDER_VALIDITY_MS - 10 * 60 * 1000 : now;
  return {
    v: PROTOCOL_VERSION,
    order_id: crypto.randomUUID(),
    device_id: device.device_id,
    created_at: createdAt,
    expires_at: createdAt + ORDER_VALIDITY_MS,
    catalog_version: catalog.catalog_version,
    store_id: storeId,
    items,
    delivery_fee_seen: store.delivery_fee,
    total_seen: totalSeen,
    max_total: totalSeen + tolerance,
    payment: 'COD',
    address: device.address,
    phone: device.phone,
    note,
  };
}

/** Signs, seals and wraps an OrderBody into an ORDER envelope. */
export function sealOrder(device, body, { trace = [device.node_id] } = {}) {
  const signed = sign(utf8(JSON.stringify(body)), b64decode(device.sign_sk));
  const sealed = seal(signed, b64decode(device.server_box_pk));
  return {
    v: PROTOCOL_VERSION,
    type: 'ORDER',
    id: body.order_id,
    expires_at: body.expires_at,
    hops_left: INITIAL_HOPS,
    trace,
    payload: b64encode(sealed),
  };
}

/** Returns a copy of the envelope with one byte of the decoded payload flipped. */
export function tamper(envelope) {
  const bytes = b64decode(envelope.payload);
  bytes[Math.floor(bytes.length / 2)] ^= 0x01;
  return { ...envelope, payload: b64encode(bytes) };
}

export async function uploadPackets(serverUrl, gatewayNodeId, packets) {
  const res = await http('POST', `${serverUrl}/v1/mesh/packets`, { gateway_node_id: gatewayNodeId, packets });
  return res.packets;
}

/**
 * Verifies the server signature on an ACK envelope and opens the sealed result.
 * Returns { order_id, issued_at, result }. Throws if anything does not check out.
 */
export function openAck(device, envelope) {
  if (envelope.type !== 'ACK') throw new Error('not an ACK packet');
  const ackBytes = signOpen(b64decode(envelope.payload), b64decode(device.server_sign_pk));
  if (!ackBytes) throw new Error('ACK server signature is invalid');
  const ack = JSON.parse(fromUtf8(ackBytes));
  if (ack.order_id !== envelope.id) throw new Error('ACK order_id does not match envelope id');
  const resultBytes = sealOpen(b64decode(ack.sealed_result), b64decode(device.box_pk), b64decode(device.box_sk));
  if (!resultBytes) throw new Error('sealed_result cannot be opened with the device box keys');
  return { order_id: ack.order_id, issued_at: ack.issued_at, result: JSON.parse(fromUtf8(resultBytes)) };
}

/** Verifies the server signature on a STOCK envelope. Returns the StockSnapshot. Throws on failure. */
export function openStock(serverSignPkB64, envelope) {
  if (envelope.type !== 'STOCK') throw new Error('not a STOCK packet');
  const bytes = signOpen(b64decode(envelope.payload), b64decode(serverSignPkB64));
  if (!bytes) throw new Error('STOCK server signature is invalid');
  const snapshot = JSON.parse(fromUtf8(bytes));
  if (envelope.id !== `${snapshot.store_id}:${snapshot.seq}:${snapshot.issued_at}`) {
    throw new Error('STOCK envelope id does not match the signed snapshot');
  }
  return snapshot;
}
