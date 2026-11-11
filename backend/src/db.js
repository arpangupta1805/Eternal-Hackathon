// SQLite setup, schema, seed, and catalog helpers. Schema is defined in docs/04-backend.md.

import fs from 'node:fs';
import path from 'node:path';
import Database from 'better-sqlite3';

const SCHEMA = `
CREATE TABLE IF NOT EXISTS devices (
  device_id   TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  phone       TEXT NOT NULL,
  address     TEXT NOT NULL,
  node_id     TEXT NOT NULL,
  role        TEXT NOT NULL,
  sign_pk     TEXT NOT NULL,
  box_pk      TEXT NOT NULL,
  created_at  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS stores (
  store_id     TEXT PRIMARY KEY,
  name         TEXT NOT NULL,
  kind         TEXT NOT NULL,
  open         INTEGER NOT NULL DEFAULT 1,
  delivery_fee INTEGER NOT NULL,
  eta_minutes  INTEGER NOT NULL,
  stock_seq    INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS items (
  item_id   TEXT PRIMARY KEY,
  store_id  TEXT NOT NULL REFERENCES stores(store_id),
  name      TEXT NOT NULL,
  category  TEXT NOT NULL,
  price     INTEGER NOT NULL,
  in_stock  INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS orders (
  order_id         TEXT PRIMARY KEY,
  device_id        TEXT NOT NULL,
  store_id         TEXT NOT NULL,
  status           TEXT NOT NULL,
  reason           TEXT,
  server_order_ref TEXT,
  body             TEXT NOT NULL,
  result           TEXT NOT NULL,
  ack_packet       TEXT NOT NULL,
  created_at       INTEGER NOT NULL,
  received_at      INTEGER NOT NULL,
  gateway_node_id  TEXT,
  trace            TEXT NOT NULL,
  hops             INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS packet_log (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  received_at     INTEGER NOT NULL,
  packet_key      TEXT NOT NULL,
  gateway_node_id TEXT,
  outcome         TEXT NOT NULL
);
`;

// Prices in paise.
const SEED_STORES = [
  {
    store_id: 'store_zomato_01',
    name: 'Spice Route Kitchen, Kudasan',
    kind: 'RESTAURANT',
    delivery_fee: 3000,
    eta_minutes: 30,
    items: [
      ['itm_biryani_chicken', 'Chicken Dum Biryani', 'Biryani', 24900],
      ['itm_biryani_veg', 'Veg Biryani', 'Biryani', 18900],
      ['itm_paneer_butter', 'Paneer Butter Masala', 'Main course', 22900],
      ['itm_dal_makhani', 'Dal Makhani', 'Main course', 17900],
      ['itm_chole', 'Chole Masala', 'Main course', 15900],
      ['itm_roti_butter', 'Butter Roti', 'Breads', 2500],
      ['itm_naan_garlic', 'Garlic Naan', 'Breads', 5500],
      ['itm_rice_jeera', 'Jeera Rice', 'Rice', 11900],
      ['itm_lassi_sweet', 'Sweet Lassi', 'Drinks', 6900],
      ['itm_chaas', 'Masala Chaas', 'Drinks', 3900],
      ['itm_gulab_jamun', 'Gulab Jamun (2 pc)', 'Dessert', 5900],
    ],
  },
  {
    store_id: 'store_blinkit_01',
    name: 'Blinkit, Kudasan',
    kind: 'DARK_STORE',
    delivery_fee: 2500,
    eta_minutes: 12,
    items: [
      ['itm_milk_500', 'Milk 500 ml', 'Dairy', 3300],
      ['itm_curd_400', 'Curd 400 g', 'Dairy', 3500],
      ['itm_paneer_200', 'Paneer 200 g', 'Dairy', 9000],
      ['itm_bread_brown', 'Brown bread', 'Bakery', 5500],
      ['itm_bread_white', 'White bread', 'Bakery', 5000],
      ['itm_eggs_6', 'Eggs (6 pc)', 'Eggs', 5400],
      ['itm_maggi_4', 'Instant noodles (pack of 4)', 'Snacks', 5600],
      ['itm_chips_classic', 'Potato chips, classic salted', 'Snacks', 2000],
      ['itm_biscuit_glucose', 'Glucose biscuits', 'Snacks', 1000],
      ['itm_cola_750', 'Cola 750 ml', 'Drinks', 4000],
      ['itm_water_1l', 'Drinking water 1 L', 'Drinks', 2000],
    ],
  },
];

/** Opens the database (":memory:" or a file path), creates the schema and seeds an empty catalog. */
export function openDb(dbPath = ':memory:') {
  if (dbPath !== ':memory:') fs.mkdirSync(path.dirname(dbPath), { recursive: true });
  const db = new Database(dbPath);
  db.pragma('journal_mode = WAL');
  db.exec(SCHEMA);
  const hasStores = db.prepare('SELECT COUNT(*) AS n FROM stores').get().n > 0;
  if (!hasStores) {
    db.transaction(() => {
      setMeta(db, 'catalog_version', 1);
      if (getMeta(db, 'order_counter') === null) setMeta(db, 'order_counter', 0);
      insertSeed(db, new Map());
    })();
  }
  return db;
}

function insertSeed(db, previousSeq) {
  const insStore = db.prepare(
    'INSERT INTO stores (store_id, name, kind, open, delivery_fee, eta_minutes, stock_seq) VALUES (?, ?, ?, 1, ?, ?, ?)'
  );
  const insItem = db.prepare(
    'INSERT INTO items (item_id, store_id, name, category, price, in_stock) VALUES (?, ?, ?, ?, ?, 1)'
  );
  for (const s of SEED_STORES) {
    const seq = (previousSeq.get(s.store_id) ?? 0) + 1;
    insStore.run(s.store_id, s.name, s.kind, s.delivery_fee, s.eta_minutes, seq);
    for (const [itemId, name, category, price] of s.items) {
      insItem.run(itemId, s.store_id, name, category, price);
    }
  }
}

/**
 * Clears orders and the packet log and re-seeds the catalog.
 * Keeps devices. Never lowers counters: stock_seq and catalog_version continue from
 * their old values plus 1, order_counter is untouched (see docs/04-backend.md, "Running").
 */
export function resetData(db) {
  db.transaction(() => {
    const previousSeq = new Map(
      db.prepare('SELECT store_id, stock_seq FROM stores').all().map((r) => [r.store_id, r.stock_seq])
    );
    db.exec('DELETE FROM orders; DELETE FROM packet_log; DELETE FROM items; DELETE FROM stores;');
    insertSeed(db, previousSeq);
    bumpCatalogVersion(db);
  })();
}

// ---- meta ----

export function getMeta(db, key) {
  const row = db.prepare('SELECT value FROM meta WHERE key = ?').get(key);
  return row ? Number(row.value) : null;
}

export function setMeta(db, key, value) {
  db.prepare(
    'INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value'
  ).run(key, String(value));
}

function bumpCatalogVersion(db) {
  setMeta(db, 'catalog_version', (getMeta(db, 'catalog_version') ?? 0) + 1);
}

/** Increments and returns the order counter. Call inside a transaction. */
export function nextOrderCounter(db) {
  const n = (getMeta(db, 'order_counter') ?? 0) + 1;
  setMeta(db, 'order_counter', n);
  return n;
}

// ---- catalog reads ----

export function getStore(db, storeId) {
  return db.prepare('SELECT * FROM stores WHERE store_id = ?').get(storeId) ?? null;
}

export function listStores(db) {
  return db.prepare('SELECT * FROM stores ORDER BY store_id').all();
}

export function getItem(db, itemId) {
  return db.prepare('SELECT * FROM items WHERE item_id = ?').get(itemId) ?? null;
}

export function listItems(db, storeId) {
  return db.prepare('SELECT * FROM items WHERE store_id = ? ORDER BY rowid').all(storeId);
}

/** The GET /v1/catalog response body. */
export function getCatalog(db, now) {
  return {
    catalog_version: getMeta(db, 'catalog_version'),
    generated_at: now,
    stores: listStores(db).map((s) => ({
      store_id: s.store_id,
      name: s.name,
      kind: s.kind,
      open: s.open === 1,
      delivery_fee: s.delivery_fee,
      eta_minutes: s.eta_minutes,
      stock_seq: s.stock_seq,
      items: listItems(db, s.store_id).map((i) => ({
        item_id: i.item_id,
        name: i.name,
        category: i.category,
        price: i.price,
        in_stock: i.in_stock === 1,
      })),
    })),
  };
}

// ---- catalog writes (each returns false if the target does not exist) ----

/** Bumps the store's stock_seq and catalog_version. */
export function setItemStock(db, itemId, inStock) {
  return db.transaction(() => {
    const item = getItem(db, itemId);
    if (!item) return false;
    db.prepare('UPDATE items SET in_stock = ? WHERE item_id = ?').run(inStock ? 1 : 0, itemId);
    db.prepare('UPDATE stores SET stock_seq = stock_seq + 1 WHERE store_id = ?').run(item.store_id);
    bumpCatalogVersion(db);
    return true;
  })();
}

/** Bumps catalog_version only. Prices are not part of STOCK packets. */
export function setItemPrice(db, itemId, price) {
  return db.transaction(() => {
    if (!getItem(db, itemId)) return false;
    db.prepare('UPDATE items SET price = ? WHERE item_id = ?').run(price, itemId);
    bumpCatalogVersion(db);
    return true;
  })();
}

/** Bumps the store's stock_seq and catalog_version. */
export function setStoreOpen(db, storeId, open) {
  return db.transaction(() => {
    if (!getStore(db, storeId)) return false;
    db.prepare('UPDATE stores SET open = ?, stock_seq = stock_seq + 1 WHERE store_id = ?').run(
      open ? 1 : 0,
      storeId
    );
    bumpCatalogVersion(db);
    return true;
  })();
}
