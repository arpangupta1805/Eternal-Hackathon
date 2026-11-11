// Order processing. Steps and their order are fixed by docs/04-backend.md, "Order processing".

import {
  ACK_VALIDITY_MS,
  CLOCK_SKEW_MS,
  INITIAL_HOPS,
  MAX_PACKET_BYTES,
  ON_UNAVAILABLE,
  PROTOCOL_VERSION,
} from './constants.js';
import { SIGN_BYTES, b64decode, b64encode, fromUtf8, seal, sealOpen, sign, signOpen, utf8 } from './crypto.js';
import { getItem, getStore, nextOrderCounter } from './db.js';

const isInt = (v) => Number.isInteger(v);
const isStr = (v) => typeof v === 'string';
const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);

function validEnvelopeShape(p) {
  return (
    isObj(p) &&
    p.v === PROTOCOL_VERSION &&
    isStr(p.type) &&
    isStr(p.id) &&
    p.id.length > 0 &&
    p.id.length <= 64 &&
    isInt(p.expires_at) &&
    isInt(p.hops_left) &&
    Array.isArray(p.trace) &&
    p.trace.every(isStr) &&
    isStr(p.payload) &&
    Buffer.byteLength(JSON.stringify(p), 'utf8') <= MAX_PACKET_BYTES
  );
}

function validOrderBody(b) {
  if (!isObj(b)) return false;
  if (b.v !== PROTOCOL_VERSION) return false;
  if (!isStr(b.order_id) || !isStr(b.device_id) || !isStr(b.store_id)) return false;
  if (!isInt(b.created_at) || !isInt(b.expires_at) || !isInt(b.catalog_version)) return false;
  if (!isInt(b.delivery_fee_seen) || b.delivery_fee_seen < 0) return false;
  if (!isInt(b.total_seen) || b.total_seen < 0) return false;
  if (!isInt(b.max_total) || b.max_total < 0) return false;
  if (b.payment !== 'COD') return false;
  if (!isObj(b.address) || !isStr(b.phone)) return false;
  if (!Array.isArray(b.items) || b.items.length === 0) return false;
  for (const it of b.items) {
    if (!isObj(it) || !isStr(it.item_id)) return false;
    if (!isInt(it.qty) || it.qty <= 0) return false;
    if (!isInt(it.unit_price_seen) || it.unit_price_seen < 0) return false;
    if (it.on_unavailable != null && !ON_UNAVAILABLE.includes(it.on_unavailable)) return false;
    if (it.substitute_item_id != null && !isStr(it.substitute_item_id)) return false;
  }
  return true;
}

function rejected(orderId, reason) {
  return {
    order_id: orderId,
    status: 'REJECTED',
    reason,
    server_order_ref: null,
    final_items: [],
    changes: [],
    delivery_fee: 0,
    final_total: 0,
    eta_minutes: null,
  };
}

/**
 * Steps 7 to 12: decides the result for a verified, well-formed body.
 * Pure read of the catalog. server_order_ref is filled in by the caller.
 */
export function evaluateOrder(db, body, now) {
  const id = body.order_id;

  // 7. Expiry
  if (now > body.expires_at + CLOCK_SKEW_MS) return rejected(id, 'EXPIRED');

  // 8. Store
  const store = getStore(db, body.store_id);
  if (!store || store.open !== 1) return rejected(id, 'STORE_CLOSED');

  // 9. Items
  const available = (itemId) => {
    const item = itemId ? getItem(db, itemId) : null;
    return item && item.store_id === store.store_id && item.in_stock === 1 ? item : null;
  };
  const finalItems = [];
  const changes = [];
  for (const line of body.items) {
    const item = available(line.item_id);
    if (item) {
      finalItems.push({ item_id: item.item_id, name: item.name, qty: line.qty, unit_price: item.price });
      if (item.price !== line.unit_price_seen) {
        changes.push({
          item_id: item.item_id,
          change: 'PRICE_CHANGED',
          old_price: line.unit_price_seen,
          new_price: item.price,
        });
      }
      continue;
    }
    const rule = line.on_unavailable ?? 'DROP';
    if (rule === 'CANCEL_ORDER') return rejected(id, 'ITEM_UNAVAILABLE');
    const sub = rule === 'SUBSTITUTE' ? available(line.substitute_item_id) : null;
    if (sub) {
      finalItems.push({ item_id: sub.item_id, name: sub.name, qty: line.qty, unit_price: sub.price });
      changes.push({ item_id: line.item_id, change: 'SUBSTITUTED', new_item_id: sub.item_id });
    } else {
      changes.push({ item_id: line.item_id, change: 'DROPPED' });
    }
  }

  // 10. Empty check
  if (finalItems.length === 0) return rejected(id, 'ITEM_UNAVAILABLE');

  // 11. Total
  const finalTotal = finalItems.reduce((sum, i) => sum + i.unit_price * i.qty, 0) + store.delivery_fee;
  if (finalTotal > body.max_total) return rejected(id, 'PRICE_EXCEEDED');

  // 12. Status
  return {
    order_id: id,
    status: changes.length === 0 && finalTotal === body.total_seen ? 'CONFIRMED' : 'CONFIRMED_MODIFIED',
    reason: null,
    server_order_ref: null,
    final_items: finalItems,
    changes,
    delivery_fee: store.delivery_fee,
    final_total: finalTotal,
    eta_minutes: store.eta_minutes,
  };
}

/** Step 13: ACK envelope, result sealed to the device and signed by the server. */
export function buildAckPacket(keys, result, deviceBoxPk, issuedAt) {
  const sealedResult = seal(utf8(JSON.stringify(result)), deviceBoxPk);
  const ack = { order_id: result.order_id, issued_at: issuedAt, sealed_result: b64encode(sealedResult) };
  return {
    v: PROTOCOL_VERSION,
    type: 'ACK',
    id: result.order_id,
    expires_at: issuedAt + ACK_VALIDITY_MS,
    hops_left: INITIAL_HOPS,
    trace: [],
    payload: b64encode(sign(utf8(JSON.stringify(ack)), keys.sign.sk)),
  };
}

export function createOrderProcessor({ db, keys, now, events }) {
  const insertLog = db.prepare(
    'INSERT INTO packet_log (received_at, packet_key, gateway_node_id, outcome) VALUES (?, ?, ?, ?)'
  );
  const findOrder = db.prepare('SELECT ack_packet FROM orders WHERE order_id = ?');
  const findDevice = db.prepare('SELECT * FROM devices WHERE device_id = ?');
  const insertOrder = db.prepare(`
    INSERT INTO orders (order_id, device_id, store_id, status, reason, server_order_ref, body, result,
                        ack_packet, created_at, received_at, gateway_node_id, trace, hops)
    VALUES (@order_id, @device_id, @store_id, @status, @reason, @server_order_ref, @body, @result,
            @ack_packet, @created_at, @received_at, @gateway_node_id, @trace, @hops)
  `);

  function log(packetKey, gatewayNodeId, outcome) {
    const receivedAt = now();
    insertLog.run(receivedAt, packetKey, gatewayNodeId, outcome);
    events.broadcast('packet', {
      received_at: receivedAt,
      packet_key: packetKey,
      gateway_node_id: gatewayNodeId,
      outcome,
    });
  }

  /** Returns the ACK envelope for one uploaded packet, or null if the packet gets no answer. */
  function processOne(envelope, gatewayNodeId) {
    // 1. Shape check
    if (!validEnvelopeShape(envelope)) {
      const key = isObj(envelope) && isStr(envelope.id) ? `?:${envelope.id.slice(0, 64)}` : '?';
      log(key, gatewayNodeId, 'MALFORMED');
      return null;
    }
    if (envelope.type === 'ACK' || envelope.type === 'STOCK') return null; // not ours to process
    if (envelope.type !== 'ORDER') {
      log(`?:${envelope.id}`, gatewayNodeId, 'MALFORMED');
      return null;
    }
    const key = `ORDER:${envelope.id}`;

    // 2. Dedupe
    const existing = findOrder.get(envelope.id);
    if (existing) {
      log(key, gatewayNodeId, 'DUPLICATE');
      return JSON.parse(existing.ack_packet);
    }

    // 3. Open
    const sealed = b64decode(envelope.payload);
    if (!sealed) {
      log(key, gatewayNodeId, 'MALFORMED');
      return null;
    }
    const signed = sealOpen(sealed, keys.box.pk, keys.box.sk);
    if (!signed) {
      log(key, gatewayNodeId, 'TAMPERED');
      return null;
    }

    // 4. Split. Parsed here only to find the device; nothing is trusted until step 5 passes.
    if (signed.length <= SIGN_BYTES) {
      log(key, gatewayNodeId, 'MALFORMED');
      return null;
    }
    const bodyText = fromUtf8(signed.subarray(SIGN_BYTES));
    let body;
    try {
      body = JSON.parse(bodyText);
    } catch {
      log(key, gatewayNodeId, 'MALFORMED');
      return null;
    }
    if (!isObj(body)) {
      log(key, gatewayNodeId, 'MALFORMED');
      return null;
    }

    // 5. Verify
    const device = isStr(body.device_id) ? findDevice.get(body.device_id) : null;
    const devicePk = device ? b64decode(device.sign_pk) : null;
    if (!devicePk || !signOpen(signed, devicePk)) {
      log(key, gatewayNodeId, 'TAMPERED');
      return null;
    }

    // 6. ID check, then body check
    if (body.order_id !== envelope.id) {
      log(key, gatewayNodeId, 'TAMPERED');
      return null;
    }
    if (!validOrderBody(body)) {
      log(key, gatewayNodeId, 'MALFORMED');
      return null;
    }

    // 7 to 12
    const receivedAt = now();
    const result = evaluateOrder(db, body, receivedAt);

    // 13 and 14. Counter, ACK and order row in one transaction.
    let ackPacket;
    try {
      db.transaction(() => {
        if (result.status !== 'REJECTED') {
          result.server_order_ref = `EL-${String(nextOrderCounter(db)).padStart(6, '0')}`;
        }
        ackPacket = buildAckPacket(keys, result, b64decode(device.box_pk), receivedAt);
        insertOrder.run({
          order_id: body.order_id,
          device_id: device.device_id,
          store_id: body.store_id,
          status: result.status,
          reason: result.reason,
          server_order_ref: result.server_order_ref,
          body: bodyText,
          result: JSON.stringify(result),
          ack_packet: JSON.stringify(ackPacket),
          created_at: body.created_at,
          received_at: receivedAt,
          gateway_node_id: gatewayNodeId,
          trace: JSON.stringify(envelope.trace),
          hops: Math.max(0, envelope.trace.length - 1),
        });
      })();
    } catch (err) {
      // Another upload of the same order won the insert. First copy wins.
      const winner = findOrder.get(envelope.id);
      if (!winner) throw err;
      log(key, gatewayNodeId, 'DUPLICATE');
      return JSON.parse(winner.ack_packet);
    }

    // 15
    log(key, gatewayNodeId, 'ACCEPTED');
    events.broadcast('order', { order_id: body.order_id });
    return ackPacket;
  }

  /** Processes an upload. Returns one ACK per order that was processed or already known. */
  function processPackets(gatewayNodeId, packets) {
    const acks = [];
    const answered = new Set();
    for (const envelope of packets) {
      const ack = processOne(envelope, gatewayNodeId);
      if (ack && !answered.has(ack.id)) {
        answered.add(ack.id);
        acks.push(ack);
      }
    }
    return acks;
  }

  return { processPackets };
}
