# 04. Backend

A mock Eternal order API plus a live dashboard. Node.js 22+, Express, `better-sqlite3`, `libsodium-wrappers`, `ws`. JavaScript ES modules. Packet formats and crypto are defined in `03-protocol.md`.

## Running

- `npm install`, then `npm run dev` (port 8080 by default, `PORT` env var overrides).
- First start creates `data/eternal.db`, generates server keys into `data/keys.json`, and seeds the catalog.
- `npm run reset` clears orders and the packet log and re-seeds the catalog. It **keeps** server keys and the `devices` table, so enrolled phones keep working. It never lowers counters: every store's `stock_seq` and the global `catalog_version` continue from their old values plus 1, and `order_counter` is left alone. (Phones ignore stock snapshots that are not newer than what they hold, so a counter that went back to 1 would silently break stock updates.) It does the same thing as `POST /admin/api/reset`.
- Deleting `data/eternal.db` by hand loses devices and counters. After that every phone must use "Clear all data" and enroll again.
- For development, phones reach the backend at the laptop's LAN IP. **For the demo, the gateway phone is on mobile data, so the backend must be reachable from the internet**: deploy it to a public host or expose it with a tunnel. The app's server URL is editable in its debug screen.

## Database schema

```sql
CREATE TABLE devices (
  device_id   TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  phone       TEXT NOT NULL,
  address     TEXT NOT NULL,          -- JSON
  node_id     TEXT NOT NULL,          -- 8 hex chars, the install's mesh id
  role        TEXT NOT NULL,          -- CUSTOMER | RIDER | STORE (display label)
  sign_pk     TEXT NOT NULL,          -- base64
  box_pk      TEXT NOT NULL,          -- base64
  created_at  INTEGER NOT NULL
);

CREATE TABLE stores (
  store_id     TEXT PRIMARY KEY,
  name         TEXT NOT NULL,
  kind         TEXT NOT NULL,         -- RESTAURANT | DARK_STORE
  open         INTEGER NOT NULL DEFAULT 1,
  delivery_fee INTEGER NOT NULL,      -- paise
  eta_minutes  INTEGER NOT NULL,
  stock_seq    INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE items (
  item_id   TEXT PRIMARY KEY,
  store_id  TEXT NOT NULL REFERENCES stores(store_id),
  name      TEXT NOT NULL,
  category  TEXT NOT NULL,
  price     INTEGER NOT NULL,         -- paise
  in_stock  INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL                 -- holds catalog_version, order_counter
);

CREATE TABLE orders (
  order_id         TEXT PRIMARY KEY,
  device_id        TEXT NOT NULL,
  store_id         TEXT NOT NULL,
  status           TEXT NOT NULL,     -- CONFIRMED | CONFIRMED_MODIFIED | REJECTED
  reason           TEXT,
  server_order_ref TEXT,
  body             TEXT NOT NULL,     -- OrderBody JSON as received
  result           TEXT NOT NULL,     -- AckResult JSON
  ack_packet       TEXT NOT NULL,     -- full ACK envelope JSON, returned for duplicates
  created_at       INTEGER NOT NULL,  -- from body (device clock)
  received_at      INTEGER NOT NULL,  -- server clock
  gateway_node_id  TEXT,
  trace            TEXT NOT NULL,     -- JSON array
  hops             INTEGER NOT NULL
);

CREATE TABLE packet_log (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  received_at     INTEGER NOT NULL,
  packet_key      TEXT NOT NULL,
  gateway_node_id TEXT,
  outcome         TEXT NOT NULL       -- ACCEPTED | DUPLICATE | TAMPERED | MALFORMED
);
```

`catalog_version` increases by 1 on any change to stores or items (stock, price, open). `stock_seq` on a store increases by 1 on any stock or open/closed change for that store.

## Seed data

Two stores, about ten items each, with realistic Indian names and prices:

- `store_zomato_01`: a restaurant (kind `RESTAURANT`), for example biryani, paneer dishes, rotis, drinks
- `store_blinkit_01`: a dark store (kind `DARK_STORE`), for example milk, bread (brown and white, so substitution can be demoed), eggs, snacks

## Public API

All bodies are JSON. Errors return `{ "error": "<code>", "message": "..." }` with a 4xx or 5xx status.

### `POST /v1/devices/enroll`

Request:
```json
{
  "name": "Arpan",
  "phone": "9XXXXXXXXX",
  "address": { "line1": "...", "line2": "...", "landmark": "...", "lat": 23.2156, "lng": 72.6842 },
  "node_id": "a1b2c3d4",
  "role": "RIDER",
  "sign_pk": "<base64 Ed25519 public key>",
  "box_pk": "<base64 X25519 public key>"
}
```
Response:
```json
{
  "device_id": "dev_7k2m9x",
  "server_sign_pk": "<base64>",
  "server_box_pk": "<base64>"
}
```
No login or OTP. Each call creates a new device.

`node_id` and `role` are used only to label hop paths on the dashboard: a `trace` entry is shown with the role of the most recently enrolled device that has that `node_id`. A `node_id` the server has never seen is shown without a label. The role is fixed at enrollment; changing the role label later in the app's debug screen changes the Nearby endpoint name but not the dashboard label.

### `GET /v1/catalog`

```json
{
  "catalog_version": 12,
  "generated_at": 1794478800000,
  "stores": [
    {
      "store_id": "store_blinkit_01",
      "name": "Blinkit, Kudasan",
      "kind": "DARK_STORE",
      "open": true,
      "delivery_fee": 2500,
      "eta_minutes": 12,
      "stock_seq": 7,
      "items": [
        { "item_id": "itm_milk_500", "name": "Milk 500 ml", "category": "Dairy", "price": 3300, "in_stock": true }
      ]
    }
  ]
}
```

### `POST /v1/mesh/packets`

Called by any online node to upload the orders it holds.

Request:
```json
{ "gateway_node_id": "d4c3b2a1", "packets": [ { ...ORDER envelope... } ] }
```
Response:
```json
{ "packets": [ { ...ACK envelope... } ] }
```
One ACK per order that was processed or already known. Nothing is returned for packets that were dropped (tampered, malformed, unknown device). Non-`ORDER` packets in the request are ignored.

### `GET /v1/mesh/stock`

```json
{ "packets": [ { ...STOCK envelope, latest for each store... } ] }
```
STOCK packets are created (and cached) whenever a store's `stock_seq` changes, and once at startup. On each request, any cached packet whose `issued_at` is older than `STOCK_REISSUE_MS` is first re-issued: same `seq` and contents, fresh `issued_at`, new signature, new envelope `id` and `expires_at`. This keeps a valid packet available however long ago the last change was.

## Order processing

For each `ORDER` envelope, in this exact order:

1. **Shape check.** Envelope valid (fields and types of the packet envelope, `v` is 1, size at most `MAX_PACKET_BYTES`), `type` is `ORDER`. Otherwise log `MALFORMED`, skip. Well-formed `ACK` and `STOCK` packets are skipped without a log entry.
2. **Dedupe.** If `orders` has a row with `order_id = envelope.id`: log `DUPLICATE`, return its stored `ack_packet`. Stop.
3. **Open.** `crypto_box_seal_open(payload, server_box keys)`. On failure: log `TAMPERED`, skip.
4. **Split.** First 64 bytes are the signature, the rest is `order_body_bytes`. Parse the body as JSON to read `device_id`. On parse failure: log `MALFORMED`, skip.
5. **Verify.** Look up the device. `crypto_sign_open(signed, device.sign_pk)`. Unknown device or bad signature: log `TAMPERED`, skip.
6. **ID check.** `body.order_id` must equal `envelope.id`. Otherwise log `TAMPERED`, skip.
   **Body check.** The verified body must have the fields and types of `OrderBody` (non-empty `items`, each `qty` a positive integer, `payment` equal to `COD`, integer money fields). Otherwise log `MALFORMED`, skip (no ACK).
7. **Expiry.** If server time is later than `body.expires_at + CLOCK_SKEW_MS`: result is `REJECTED / EXPIRED`. Go to step 13.
8. **Store.** If the store does not exist or is closed: `REJECTED / STORE_CLOSED`. Go to 13.
9. **Items.** For each item in the body:
   - Item exists in that store and is in stock: keep it, at the **current** price. If the current price differs from `unit_price_seen`, add a `PRICE_CHANGED` change.
   - Otherwise apply `on_unavailable`:
     - `DROP`: remove it, add a `DROPPED` change.
     - `SUBSTITUTE`: if `substitute_item_id` exists in the same store and is in stock, use it with the same `qty` at its current price and add a `SUBSTITUTED` change. Otherwise treat as `DROP`.
     - `CANCEL_ORDER`: `REJECTED / ITEM_UNAVAILABLE`. Go to 13.
10. **Empty check.** No items left: `REJECTED / ITEM_UNAVAILABLE`. Go to 13.
11. **Total.** `final_total` = sum of current price × qty + the store's current delivery fee. If `final_total > body.max_total`: `REJECTED / PRICE_EXCEEDED`. Go to 13.
12. **Status.** `CONFIRMED` if `changes` is empty and `final_total == body.total_seen`. Otherwise `CONFIRMED_MODIFIED`. Assign `server_order_ref` (`EL-` plus a zero-padded counter) and `eta_minutes` from the store.
13. **Build the ACK** as defined in `03-protocol.md`, sealed to the device's `box_pk` and signed with the server key.
14. **Persist** the order row and the ACK in one transaction. `order_id` is the primary key, so if two gateways upload at the same moment, the second insert fails and that request returns the stored ACK (first copy wins).
15. Log `ACCEPTED` (this means "processed and answered", so it is also logged for a `REJECTED` result), broadcast an `order` event to the dashboard, return the ACK.

`hops` = `envelope.trace.length - 1`. Latency shown on the dashboard = `received_at - body.created_at` (device clock, so approximate).

Stock is boolean only. Accepting an order does not change stock.

## Admin API (dashboard only, no auth, demo use)

| Endpoint | Purpose |
|---|---|
| `GET /admin/api/state` | Everything the dashboard needs on load: stores, items, recent orders, metrics |
| `POST /admin/api/items/:item_id/stock` | Body `{ "in_stock": false }`. Bumps `stock_seq` and `catalog_version`. |
| `POST /admin/api/items/:item_id/price` | Body `{ "price": 3500 }`. Bumps `catalog_version`. |
| `POST /admin/api/stores/:store_id/open` | Body `{ "open": false }`. Bumps `stock_seq` and `catalog_version`. |
| `POST /admin/api/reset` | Clears orders and logs, re-seeds catalog. Keeps devices and keys. Bumps every `stock_seq` and `catalog_version` instead of resetting them (same as `npm run reset`). |
| `WS /admin/ws` | Server pushes `{ "event": "order" | "catalog" | "packet", "data": ... }` |

## Dashboard

One page at `/`, served from `backend/public/`. Vanilla HTML, CSS, JS. It is projected during the demo, so it must be readable from a distance.

- **Live orders.** Newest first. Each row: order ref, store, status with colour, final total, and the **hop path drawn as a chain** (`a1b2c3d4 → e5f6... (Rider) → ... (Store node) → ... → Server`) with hop count and latency. Role labels come from the `devices` table by `node_id`. New orders animate in.
- **Order detail.** Items, changes made (dropped, substituted, price changed), reject reason.
- **Catalog control.** Every item with an in-stock toggle and editable price. Store open/closed toggle.
- **Metrics.** Orders accepted, duplicates dropped, tampered packets rejected, tampered packets accepted (always 0), average hops, average and median latency, success rate if the operator enters the number of orders attempted.

## Test tool: `scripts/fake-device.js`

A CLI that acts as a phone, so the backend can be built and tested without the app. Its protocol code lives in `scripts/device-lib.js`, which is the byte-level reference for the Android app. State is saved in `data/fake-device.json`.

```
node scripts/fake-device.js enroll [--server URL] [--role CUSTOMER|RIDER|STORE]
node scripts/fake-device.js order --item ITEM:QTY[:RULE[:SUBSTITUTE]] [--store ID] [--seen ITEM=PAISE]
     [--tolerance PAISE] [--trace id,id] [--tamper] [--twice] [--expired]
```

`--trace` adds made-up relay node ids so a multi-hop path shows on the dashboard. `--seen` sets the price the device "saw", to test price changes.


- `enroll`: generates keys and a random `node_id`, enrolls (role from a flag, default `CUSTOMER`), saves state to a local file
- `order`: builds an `OrderBody` from flags, signs, seals, wraps, uploads, then verifies and opens the ACK and prints the result
- `--tamper`: flips one byte in the payload before upload (must produce no ACK and a `TAMPERED` log)
- `--twice`: uploads the same packet twice (second must be `DUPLICATE` with the identical ACK)
- `--expired`: sets `expires_at` in the past

## Tests (Node test runner)

At minimum: happy path, duplicate, tampered payload, unknown device, expired, store closed, each `on_unavailable` rule, substitute also unavailable, all items dropped, price above `max_total`, price changed within tolerance, STOCK packet signature and `seq` increments, STOCK re-issue after `STOCK_REISSUE_MS` (same `seq`, later `issued_at`), reset keeps devices and does not lower `stock_seq` or `catalog_version`, signed but invalid body gets no ACK.
