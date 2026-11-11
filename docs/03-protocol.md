# 03. Protocol

This file defines everything the Android app and the backend must agree on. If code and this file disagree, fix one of them in the same commit.

## Conventions

- All JSON is UTF-8.
- All times are Unix milliseconds, UTC.
- All money is integer paise (₹1 = 100 paise).
- Binary values in JSON are **standard base64 with padding** (RFC 4648). Android: `Base64.NO_WRAP`. Node: `Buffer.toString('base64')`.
- IDs: `order_id` is a UUID v4 string. `node_id` is 8 random lowercase hex characters, generated once per install. `device_id` is issued by the server at enrollment.
- **Signatures are always over the exact bytes that were transmitted.** The receiver verifies the received bytes and only then parses them. Never re-serialise JSON before verifying.

## Constants

| Name | Value |
|---|---|
| `ORDER_VALIDITY_MS` | 20 minutes |
| `ACK_VALIDITY_MS` | 60 minutes from issue |
| `STOCK_VALIDITY_MS` | 30 minutes from issue |
| `INITIAL_HOPS` | 8 |
| `MAX_PACKET_BYTES` | 16384 (serialised envelope) |
| `MAX_STORED_PACKETS` | 200 per node |
| `MAX_TRACE` | 16 entries |
| `STOCK_REISSUE_MS` | 10 minutes (server re-signs an unchanged snapshot when it is older than this) |
| `STOCK_POLL_MS` | 10 seconds |
| `ONLINE_RETRY_MS` | 10 seconds (backend retry probe after a network error) |
| `DEFAULT_PRICE_TOLERANCE` | 2000 paise (₹20) |
| `CLOCK_SKEW_MS` | 2 minutes (server-side allowance) |
| Nearby `serviceId` | `com.eternallite.mesh` |

## Keys

| Key | Type | Held by | Purpose |
|---|---|---|---|
| `server_sign` | Ed25519 | Server (secret), every app (public) | Sign `ACK` and `STOCK` |
| `server_box` | X25519 | Server (secret), every app (public) | Apps seal orders to it |
| `device_sign` | Ed25519 | Device (secret), server (public) | Device signs its orders |
| `device_box` | X25519 | Device (secret), server (public) | Server seals the ACK result to it |

libsodium functions used:

- `crypto_sign_keypair`, `crypto_sign` (combined mode: output is 64-byte signature followed by the message), `crypto_sign_open`
- `crypto_box_keypair`, `crypto_box_seal`, `crypto_box_seal_open`

Device keys are generated on first launch. Secret keys are stored on the device encrypted with a key held in the Android Keystore. Server keys are generated on first backend start and saved in `backend/data/`.

## Enrollment (online, once)

The app must be online once before Lite mode can be used. It calls `POST /v1/devices/enroll` with the user's name, phone, address, the install's `node_id`, its role label and both device public keys. The server keeps `node_id` and role so the dashboard can label each entry of a `trace`. The server returns `device_id` and both server public keys. The app then downloads the catalog. See `04-backend.md` for the exact request and response.

## Packet envelope

This is what travels between nodes and what a gateway uploads. Relays can read all of it.

```json
{
  "v": 1,
  "type": "ORDER",
  "id": "3f2b6c1e-9d47-4a0b-8a51-0c2e7d9b1a44",
  "expires_at": 1794480000000,
  "hops_left": 8,
  "trace": ["a1b2c3d4"],
  "payload": "<base64>"
}
```

| Field | Meaning |
|---|---|
| `v` | Protocol version. Always `1`. Drop anything else. |
| `type` | `ORDER`, `ACK` or `STOCK` |
| `id` | `ORDER` and `ACK`: the `order_id`. `STOCK`: `"<store_id>:<seq>:<issued_at>"`. |
| `expires_at` | Nodes drop the packet after this time. |
| `hops_left` | Decremented on every receive. See routing rules. |
| `trace` | `node_id`s that have held the packet, in order. For display and metrics only. Not authenticated. |
| `payload` | Depends on `type`, see below. |

A packet's **key** is `"<type>:<id>"`, for example `ORDER:3f2b...`. Keys are used for dedupe and for the inventory exchange.

The envelope is **not** authenticated. Anyone can change `hops_left`, `expires_at` or `trace`. That is acceptable because every security decision uses the signed payload, never the envelope.

## ORDER payload

```
order_body_bytes = UTF-8 JSON of OrderBody
signed           = crypto_sign(order_body_bytes, device_sign_sk)      // 64-byte sig + body
sealed           = crypto_box_seal(signed, server_box_pk)
payload          = base64(sealed)
```

`OrderBody`:

```json
{
  "v": 1,
  "order_id": "3f2b6c1e-9d47-4a0b-8a51-0c2e7d9b1a44",
  "device_id": "dev_7k2m9x",
  "created_at": 1794478800000,
  "expires_at": 1794480000000,
  "catalog_version": 12,
  "store_id": "store_blinkit_01",
  "items": [
    {
      "item_id": "itm_milk_500",
      "qty": 2,
      "unit_price_seen": 3300,
      "on_unavailable": "DROP",
      "substitute_item_id": null
    },
    {
      "item_id": "itm_bread_brown",
      "qty": 1,
      "unit_price_seen": 5500,
      "on_unavailable": "SUBSTITUTE",
      "substitute_item_id": "itm_bread_white"
    }
  ],
  "delivery_fee_seen": 2500,
  "total_seen": 14600,
  "max_total": 16600,
  "payment": "COD",
  "address": {
    "line1": "Room 214, Hostel H",
    "line2": "IIT Gandhinagar",
    "landmark": "Near mess",
    "lat": 23.2156,
    "lng": 72.6842
  },
  "phone": "9XXXXXXXXX",
  "note": ""
}
```

Rules:

- `on_unavailable` is one of `DROP` (remove this item, continue), `SUBSTITUTE` (use `substitute_item_id` if it is in stock, otherwise treat as `DROP`), `CANCEL_ORDER` (reject the whole order). Default `DROP`.
- `total_seen` = sum of `unit_price_seen × qty` + `delivery_fee_seen`.
- `max_total` = the most the customer agrees to pay. Default `total_seen + DEFAULT_PRICE_TOLERANCE`. The user can change the tolerance in the cart.
- `payment` is always `"COD"`.
- `expires_at` = `created_at + ORDER_VALIDITY_MS`. The envelope's `expires_at` is set to the same value. The server trusts only the one inside the body.
- The envelope's `id` must equal `order_id`.
- `address.lat` and `address.lng` are `null` when the app has no coordinates (the enrollment form does not ask for them). `line2` and `landmark` may be empty strings.

## ACK payload

```
result_bytes  = UTF-8 JSON of AckResult
sealed_result = crypto_box_seal(result_bytes, device_box_pk)
ack_bytes     = UTF-8 JSON of { "order_id": ..., "issued_at": ..., "sealed_result": base64(sealed_result) }
payload       = base64(crypto_sign(ack_bytes, server_sign_sk))
```

Any node can verify the server signature and read `order_id`, so any node can safely delete the matching order. Only the originator can open `sealed_result`.

`AckResult`:

```json
{
  "order_id": "3f2b6c1e-9d47-4a0b-8a51-0c2e7d9b1a44",
  "status": "CONFIRMED_MODIFIED",
  "reason": null,
  "server_order_ref": "EL-000042",
  "final_items": [
    { "item_id": "itm_milk_500", "name": "Milk 500 ml", "qty": 2, "unit_price": 3300 },
    { "item_id": "itm_bread_white", "name": "White bread", "qty": 1, "unit_price": 5000 }
  ],
  "changes": [
    { "item_id": "itm_bread_brown", "change": "SUBSTITUTED", "new_item_id": "itm_bread_white" }
  ],
  "delivery_fee": 2500,
  "final_total": 14100,
  "eta_minutes": 12
}
```

| `status` | Meaning |
|---|---|
| `CONFIRMED` | Accepted exactly as sent, at the same total. |
| `CONFIRMED_MODIFIED` | Accepted with changes allowed by the customer's rules. `changes` lists them. |
| `REJECTED` | Not accepted. `reason` says why. `final_items` and `changes` are empty, `final_total` and `delivery_fee` are 0, `server_order_ref` and `eta_minutes` are `null`. |

`reason` values: `EXPIRED`, `STORE_CLOSED`, `ITEM_UNAVAILABLE`, `PRICE_EXCEEDED`.

`change` values: `DROPPED`, `SUBSTITUTED` (with `new_item_id`), `PRICE_CHANGED` (with `old_price`, `new_price`).

Envelope for an ACK: `type` = `ACK`, `id` = `order_id`, `expires_at` = `issued_at + ACK_VALIDITY_MS`, `hops_left` = `INITIAL_HOPS`, `trace` = `[]`.

The server sends no ACK when it cannot identify or trust the sender (seal cannot be opened, unknown device, bad signature). Those packets are dropped and logged.

## STOCK payload

```
stock_bytes = UTF-8 JSON of StockSnapshot
payload     = base64(crypto_sign(stock_bytes, server_sign_sk))
```

`StockSnapshot`:

```json
{
  "store_id": "store_blinkit_01",
  "seq": 7,
  "issued_at": 1794478900000,
  "open": true,
  "unavailable_item_ids": ["itm_bread_brown", "itm_eggs_6"]
}
```

- It is a **full snapshot**, not a delta: every item of that store not in the list is in stock.
- `seq` increases by 1 on every stock or open/closed change for that store. It never decreases, not even when the backend is reset.
- **Re-issue.** A snapshot is only valid for `STOCK_VALIDITY_MS`, so the server keeps it alive: when the latest snapshot for a store is older than `STOCK_REISSUE_MS`, the server signs it again with the same `seq`, the same contents and a fresh `issued_at`. Because `issued_at` is part of the envelope `id`, the re-issued packet has a new key and floods normally.
- **Which snapshot wins.** Compare `(seq, issued_at)`. A snapshot is **newer** than the held one if its `seq` is higher, or its `seq` is equal and its `issued_at` is later. A node ignores any snapshot that is not newer than the one it holds for that store.
- Price changes are not sent in `STOCK`. Prices are handled by the server at submit time using `max_total`.

Envelope: `type` = `STOCK`, `id` = `"<store_id>:<seq>:<issued_at>"`, `expires_at` = `issued_at + STOCK_VALIDITY_MS`, `hops_left` = `INITIAL_HOPS`, `trace` = `[]`.

## Mesh link protocol

Two connected nodes exchange JSON frames as Nearby `BYTES` payloads.

**Connection setup**

- Every node advertises and discovers at the same time, with `Strategy.P2P_CLUSTER` and the `serviceId` above.
- Endpoint name is `EL|<node_id>|<role>`, where role is `CUSTOMER`, `RIDER` or `STORE` (a display label only).
- To avoid both sides dialling each other, **only the node with the lexicographically smaller `node_id` calls `requestConnection`**.
- Connections are accepted in code automatically, unless the debug allowed-peers list is non-empty and the peer is not in it (then reject).

**Frames**

```json
{ "t": "HELLO", "node_id": "a1b2c3d4", "role": "RIDER", "have": ["ORDER:3f2b...", "STOCK:store_blinkit_01:7:1794478900000"] }
```
```json
{ "t": "PACKET", "packet": { ...envelope... } }
```

1. On connect, each side sends `HELLO` with the keys of every non-expired packet it holds.
2. After receiving the peer's `HELLO`, send a `PACKET` frame for every held packet whose key is not in the peer's `have` list, subject to the forwarding rules below.
3. While connected, whenever a node stores a new packet, it sends it to every connected peer that is not known to have it. Track a per-peer set of known keys (from `HELLO`, from packets received from that peer, and from packets sent to it).

## Routing rules

**On receiving a packet from a peer**

1. Drop if: not valid JSON, `v` is not 1, unknown `type`, serialised size over `MAX_PACKET_BYTES`, `expires_at` is in the past, or the key is already in the seen set.
2. Add the key to the seen set.
3. Set `hops_left = hops_left - 1`. If the result is below 0, drop.
4. Append own `node_id` to `trace` (if `trace` has fewer than `MAX_TRACE` entries).
5. Handle by type:

   **ORDER**
   - If this node holds an `ACK` with the same `id`: drop the order. (The ACK will reach the sender through the normal exchange.)
   - Otherwise store it.
   - If this node is online, upload it now.

   **ACK**
   - Verify with `crypto_sign_open(payload, server_sign_pk)`. Drop if it fails.
   - Check that `order_id` inside equals the envelope `id`. Drop if not.
   - Delete any stored `ORDER` with that `id`.
   - Store the ACK. It also acts as a tombstone so the order is not accepted again.
   - If the order is this node's own: open `sealed_result` with the device box keys, update the order, notify the user.

   **STOCK**
   - Verify with `crypto_sign_open(payload, server_sign_pk)`. Drop if it fails.
   - Check that the envelope `id` equals `"<store_id>:<seq>:<issued_at>"` from the signed snapshot. Drop if not.
   - If it is not newer than the held snapshot for that store (compare `(seq, issued_at)`, see STOCK payload): drop.
   - Otherwise delete the older `STOCK` packet for that store, store this one, apply it to the local catalog, and record `issued_at` as the store's stock freshness time. (A re-issue with the same `seq` changes nothing in the catalog, only the freshness time and the packet that is forwarded.)

**Forwarding rules**

- Never forward an expired packet. Delete expired packets on a timer (every 30 seconds).
- Never forward a packet whose `hops_left` is 0. It may still be uploaded by this node if it is online.
- Never send a packet back to a peer known to have it.

**Originating**

- A new `ORDER` starts with `hops_left = INITIAL_HOPS` and `trace = [own node_id]`. It goes into the node's own packet store and seen set and follows the same rules as any other packet.

**Gateway behaviour (any node, whenever it is online)**

- Upload every stored `ORDER` with `POST /v1/mesh/packets`, except those marked upload-rejected (see below). Add the key of every packet in the response to the seen set, put it through the same receive handling as rule 5 (without decrementing `hops_left`), then append own `node_id` to its `trace`.
- **Upload-rejected.** If the upload returns HTTP 200 and the response has no `ACK` for an uploaded `ORDER`, the server has dropped it (tampered, malformed or unknown device). Mark that packet upload-rejected and never upload it again from this node. It stays in the store and is still forwarded under the normal rules, because another copy of the same order may be intact elsewhere. A network error or a non-200 response does not mark anything: those uploads are retried.
- Poll `GET /v1/mesh/stock` every `STOCK_POLL_MS` and handle the returned packets the same way.
- On becoming online, upload immediately. Do not wait for a timer.
- "Online" means: the system reports a validated internet connection, debug force-offline is off, and the last backend call did not fail with a network error.
- **Retry probe.** After a network error the node is not online, so it makes no uploads. To recover, while the system reports a validated connection and force-offline is off, the stock poll keeps running every `ONLINE_RETRY_MS` even when the node is not online. The first successful poll makes the node online again, which triggers the immediate upload.

**Storage limit**

- At most `MAX_STORED_PACKETS`. When full, evict the packet that expires soonest. Never evict the node's own orders or ACKs for its own orders.

## Hops and trace

`trace` for an order uploaded in the demo is `[A, B, C, D]`. Hop count = `trace.length - 1` = 3. A self-relay upload has `trace = [A]`, 0 hops.

## Order states on the originator

| State | Meaning |
|---|---|
| `QUEUED` | Sealed, stored, not yet given to any peer or uploaded |
| `RELAYED` | Given to at least one peer |
| `CONFIRMED` | ACK received, accepted as sent |
| `CONFIRMED_MODIFIED` | ACK received, accepted with changes |
| `REJECTED` | ACK received, rejected |
| `UNCONFIRMED` | `expires_at` passed with no ACK. The order may or may not have been accepted. |

`UNCONFIRMED` is shown honestly: "We could not confirm this order. It will update when a confirmation arrives or you are back online." When the node next comes online it re-uploads its own unconfirmed orders (even though expired). The server answers with the stored ACK if it accepted the order earlier, or with a `REJECTED / EXPIRED` ACK.

## Threats and what handles them

| Threat | Handling |
|---|---|
| Relay reads the order | Sealed to `server_box_pk`. |
| Relay changes the order | Device signature fails on the server. Packet dropped, counted as tampered. |
| Same order uploaded twice | `order_id` is unique on the server. First copy wins, later copies get the stored ACK. |
| Old order replayed | Same `order_id` returns the stored ACK. New IDs need a valid device signature. |
| Fake ACK to make relays delete orders | Relays verify the server signature before deleting. |
| Fake stock update | Nodes verify the server signature. |
| Old stock update replayed | Anything not newer by `(seq, issued_at)` is ignored. |
| Relay corrupts an order in transit | The server drops it with no ACK. The gateway marks it upload-rejected and stops re-uploading it. The originator still holds the intact copy and self-relays it when it gets signal. |
| Packet loops forever | Seen set, hop limit, expiry. |
| Order arrives hours late | Server rejects after `expires_at` (plus `CLOCK_SKEW_MS`). |
| Relay sees who ordered | Envelope has only a random order ID. ACK result is sealed to the device. |
| Spam flooding | Size cap, storage cap, expiry. Relays cannot verify order signatures, so per-peer rate limiting is future work. |
