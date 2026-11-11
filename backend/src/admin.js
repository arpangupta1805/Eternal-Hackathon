// Admin API for the dashboard. No auth: demo use only. See docs/04-backend.md, "Admin API".

import express from 'express';
import { getCatalog, getItem, resetData, setItemPrice, setItemStock, setStoreOpen } from './db.js';

const RECENT_ORDERS = 50;

/** Role label for each node_id: the most recently enrolled device with that node_id wins. */
function roleMap(db) {
  const map = new Map();
  for (const r of db.prepare('SELECT node_id, role FROM devices ORDER BY created_at, rowid').all()) {
    map.set(r.node_id, r.role);
  }
  return map;
}

function orderSummary(row, roles, storeNames) {
  const body = JSON.parse(row.body);
  const result = JSON.parse(row.result);
  return {
    order_id: row.order_id,
    server_order_ref: row.server_order_ref,
    store_id: row.store_id,
    store_name: storeNames.get(row.store_id) ?? row.store_id,
    status: row.status,
    reason: row.reason,
    final_total: result.final_total,
    total_seen: body.total_seen,
    delivery_fee: result.delivery_fee,
    eta_minutes: result.eta_minutes,
    requested_items: body.items,
    final_items: result.final_items,
    changes: result.changes,
    created_at: row.created_at,
    received_at: row.received_at,
    latency_ms: row.received_at - row.created_at,
    hops: row.hops,
    trace: JSON.parse(row.trace).map((nodeId) => ({ node_id: nodeId, role: roles.get(nodeId) ?? null })),
    gateway_node_id: row.gateway_node_id,
  };
}

function storeNameMap(db) {
  return new Map(db.prepare('SELECT store_id, name FROM stores').all().map((s) => [s.store_id, s.name]));
}

export function getOrderSummary(db, orderId) {
  const row = db.prepare('SELECT * FROM orders WHERE order_id = ?').get(orderId);
  return row ? orderSummary(row, roleMap(db), storeNameMap(db)) : null;
}

function median(sorted) {
  if (sorted.length === 0) return null;
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[mid] : Math.round((sorted[mid - 1] + sorted[mid]) / 2);
}

export function getMetrics(db) {
  const byStatus = Object.fromEntries(
    db.prepare('SELECT status, COUNT(*) AS n FROM orders GROUP BY status').all().map((r) => [r.status, r.n])
  );
  const byOutcome = Object.fromEntries(
    db.prepare('SELECT outcome, COUNT(*) AS n FROM packet_log GROUP BY outcome').all().map((r) => [r.outcome, r.n])
  );
  const rows = db.prepare('SELECT hops, received_at - created_at AS latency FROM orders').all();
  const latencies = rows.map((r) => r.latency).sort((a, b) => a - b);
  const confirmed = (byStatus.CONFIRMED ?? 0) + (byStatus.CONFIRMED_MODIFIED ?? 0);
  const rejected = byStatus.REJECTED ?? 0;
  return {
    orders_received: confirmed + rejected, // distinct orders that reached the server and got an answer
    orders_confirmed: confirmed,
    orders_modified: byStatus.CONFIRMED_MODIFIED ?? 0,
    orders_rejected: rejected,
    duplicates_dropped: byOutcome.DUPLICATE ?? 0,
    tampered_rejected: byOutcome.TAMPERED ?? 0,
    // Every stored order passed the device signature check, so this is 0 by construction.
    tampered_accepted: 0,
    malformed: byOutcome.MALFORMED ?? 0,
    avg_hops: rows.length ? rows.reduce((s, r) => s + r.hops, 0) / rows.length : null,
    avg_latency_ms: rows.length ? Math.round(latencies.reduce((s, v) => s + v, 0) / rows.length) : null,
    median_latency_ms: median(latencies),
  };
}

/** Everything the dashboard needs on load. */
export function getState(db, now) {
  const roles = roleMap(db);
  const names = storeNameMap(db);
  const catalog = getCatalog(db, now);
  return {
    server_time: now,
    catalog_version: catalog.catalog_version,
    stores: catalog.stores,
    orders: db
      .prepare('SELECT * FROM orders ORDER BY received_at DESC, rowid DESC LIMIT ?')
      .all(RECENT_ORDERS)
      .map((row) => orderSummary(row, roles, names)),
    metrics: getMetrics(db),
  };
}

export function createAdminRouter(ctx) {
  const { db, now, events } = ctx;
  const router = express.Router();

  const bad = (res, message) => res.status(400).json({ error: 'invalid_request', message });
  const missing = (res, what) => res.status(404).json({ error: 'not_found', message: `${what} does not exist` });

  /** After any catalog change: tell the dashboard, and answer with the fresh state. */
  function changed(res) {
    const state = getState(db, now());
    events.broadcast('catalog', state);
    res.json(state);
  }

  router.get('/state', (req, res) => {
    res.json(getState(db, now()));
  });

  router.post('/items/:item_id/stock', (req, res) => {
    if (typeof req.body?.in_stock !== 'boolean') return bad(res, 'in_stock must be true or false');
    if (!setItemStock(db, req.params.item_id, req.body.in_stock)) return missing(res, 'item');
    changed(res);
  });

  router.post('/items/:item_id/price', (req, res) => {
    const price = req.body?.price;
    if (!Number.isInteger(price) || price <= 0) return bad(res, 'price must be a positive integer (paise)');
    if (!getItem(db, req.params.item_id)) return missing(res, 'item');
    setItemPrice(db, req.params.item_id, price);
    changed(res);
  });

  router.post('/stores/:store_id/open', (req, res) => {
    if (typeof req.body?.open !== 'boolean') return bad(res, 'open must be true or false');
    if (!setStoreOpen(db, req.params.store_id, req.body.open)) return missing(res, 'store');
    changed(res);
  });

  router.post('/reset', (req, res) => {
    resetData(db);
    changed(res);
  });

  return router;
}
