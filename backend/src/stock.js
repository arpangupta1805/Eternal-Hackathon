// STOCK packet creation. Format: docs/03-protocol.md, "STOCK payload".

import { INITIAL_HOPS, PROTOCOL_VERSION, STOCK_REISSUE_MS, STOCK_VALIDITY_MS } from './constants.js';
import { b64encode, sign, utf8 } from './crypto.js';
import { listItems, listStores } from './db.js';

/** Builds a signed STOCK envelope for one store row, issued at `issuedAt`. */
export function buildStockPacket(db, keys, store, issuedAt) {
  const snapshot = {
    store_id: store.store_id,
    seq: store.stock_seq,
    issued_at: issuedAt,
    open: store.open === 1,
    unavailable_item_ids: listItems(db, store.store_id)
      .filter((i) => i.in_stock !== 1)
      .map((i) => i.item_id),
  };
  return {
    v: PROTOCOL_VERSION,
    type: 'STOCK',
    id: `${store.store_id}:${store.stock_seq}:${issuedAt}`,
    expires_at: issuedAt + STOCK_VALIDITY_MS,
    hops_left: INITIAL_HOPS,
    trace: [],
    payload: b64encode(sign(utf8(JSON.stringify(snapshot)), keys.sign.sk)),
  };
}

/**
 * Caches the latest STOCK packet per store.
 * A packet is (re)created when the store's stock_seq has changed since it was built,
 * or when it is older than STOCK_REISSUE_MS (same seq and contents, fresh issued_at).
 */
export function createStockService({ db, keys, now }) {
  const cache = new Map(); // store_id -> { seq, issuedAt, packet }

  function latestPackets() {
    const t = now();
    const packets = [];
    const live = new Set();
    for (const store of listStores(db)) {
      live.add(store.store_id);
      let entry = cache.get(store.store_id);
      if (!entry || entry.seq !== store.stock_seq || t - entry.issuedAt >= STOCK_REISSUE_MS) {
        // issued_at must strictly increase per store so a newer packet always wins on (seq, issued_at).
        const issuedAt = entry ? Math.max(t, entry.issuedAt + 1) : t;
        entry = { seq: store.stock_seq, issuedAt, packet: buildStockPacket(db, keys, store, issuedAt) };
        cache.set(store.store_id, entry);
      }
      packets.push(entry.packet);
    }
    for (const id of cache.keys()) if (!live.has(id)) cache.delete(id);
    return packets;
  }

  return { latestPackets };
}
