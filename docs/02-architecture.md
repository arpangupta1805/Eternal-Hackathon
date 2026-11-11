# 02. Architecture

## Components

```
+-----------------+     Nearby      +-----------------+     Nearby      +-----------------+
|  Phone A        | <-------------> |  Phone B / C    | <-------------> |  Phone D        |
|  originator     |   Connections   |  relays         |   Connections   |  gateway        |
|  (offline)      |                 |  (offline)      |                 |  (online)       |
+-----------------+                 +-----------------+                 +--------+--------+
                                                                                 | HTTPS
                                                                                 v
                                                                    +------------------------+
                                                                    |  Backend (Node)        |
                                                                    |  mock Eternal order API|
                                                                    |  SQLite                |
                                                                    +-----------+------------+
                                                                                | WebSocket
                                                                                v
                                                                    +------------------------+
                                                                    |  Dashboard (web page)  |
                                                                    +------------------------+
```

There is **one Android app**. Every install can originate orders, relay packets, and act as a gateway. "Gateway" is not a mode the user picks: any node that is online uploads every `ORDER` packet it holds.

## Locked decisions

| Area | Decision | Reason |
|---|---|---|
| Platform | Android only, native Kotlin, Jetpack Compose | Radio APIs are native. Cross-platform plugins are unreliable. |
| Transport | Google Nearby Connections, `Strategy.P2P_CLUSTER`, `BYTES` payloads | Handles Bluetooth and Wi-Fi itself, works fully offline, connections are accepted in code with no system dialog. Raw Wi-Fi Direct shows a system dialog and allows one group per phone. |
| Transport abstraction | All mesh code talks to a `MeshTransport` interface | Lets a BLE GATT transport (for ESP32 nodes) be added later without touching routing. |
| Routing | Epidemic flooding with dedupe, hop limit and expiry | No route to maintain, survives people moving. |
| Return path | ACK floods back, tagged with the order ID. Nodes delete the order when they see its ACK. | The original chain may no longer exist. |
| Backend | Node.js, Express, SQLite (`better-sqlite3`), JavaScript ES modules | Team choice. Minimal tooling. |
| Dashboard | One static HTML page served by the backend, vanilla JS, WebSocket for live updates | No build step. |
| Crypto | libsodium. Android: Lazysodium. Node: `libsodium-wrappers`. | Same primitives on both sides. |
| Wire format | JSON, UTF-8. Binary fields are standard base64 with padding. | Easy to debug. Small enough (few KB). |
| Payment | `COD` only | Wallet is a proposal, not built. |
| Stock model | Boolean `in_stock` per item. No quantities. | Enough for the demo. |
| Out-of-stock handling | Three layers: cached catalog with freshness stamp, signed `STOCK` packets through the mesh, server decides at submit time using the customer's fallback rules | One round trip, never two. |
| Demo topology | Debug "allowed peers" list forces A-B-C-D | In one room all phones are in range, so 3 hops must be forced. |

## Repo layout

```
/CLAUDE.md
/docs/                    these documents
/backend/                 Node.js server + dashboard
  package.json
  src/
    server.js             Express app, routes
    constants.js          protocol constants from 03-protocol.md
    db.js                 SQLite setup, schema, seed
    crypto.js             libsodium helpers, server keys
    orders.js             order processing (validation, ACK creation)
    stock.js              STOCK packet creation
    events.js             WebSocket broadcast to dashboard
    admin.js              admin API routes, dashboard state and metrics
  public/                 dashboard (index.html, app.js, style.css)
  scripts/
    fake-device.js        CLI that enrolls, seals and uploads an order (for testing without a phone)
    device-lib.js         the phone's side of the protocol in JS; used by fake-device.js and the tests
    reset.js              npm run reset
  test/
  data/                   SQLite file and server keys (git-ignored)
/android/                 Android Studio project
  app/src/main/java/com/eternallite/app/
    data/                 Room entities, DAOs, repositories
    crypto/               key storage, seal, sign, verify
    mesh/                 MeshTransport, NearbyTransport, MeshRouter, PacketStore
    net/                  ApiClient, ConnectivityMonitor, GatewayUploader, StockPoller
    ui/                   Compose screens
    debug/                debug settings, tamper tool
    service/              foreground MeshService
/firmware/                README only. Future ESP32 node. Not built.
```

## Data flow: placing an order

1. App is online at some earlier time: enrolls, downloads the catalog, stores server public keys.
2. User goes offline. App shows "Lite mode" and "Menu as of N min ago".
3. User builds a cart, picks a fallback rule per item, confirms. App creates the order body, signs it, seals it, wraps it in an `ORDER` packet, stores it in its own packet store.
4. The mesh router offers the packet to every connected peer. Each peer stores it and offers it onward.
5. Any node that is online uploads it with `POST /v1/mesh/packets`. The response contains the `ACK` packet.
6. The gateway puts the `ACK` in its packet store. It floods back. Every node that sees the `ACK` deletes its copy of the order.
7. The originator opens the `ACK`, updates the order status, and notifies the user.

If the originator itself gets internet at any point, step 5 happens directly from it. That is the self-relay queue. It is the same code path, not a special case.

## Data flow: stock updates

1. An operator toggles an item on the dashboard.
2. The backend bumps the store's `stock_seq`, creates a signed `STOCK` packet (full list of unavailable items for that store).
3. Online nodes poll `GET /v1/mesh/stock` every 10 seconds and put newer `STOCK` packets in their packet store.
4. `STOCK` packets flood through the mesh. Each node verifies the server signature, keeps only the newest snapshot per store (highest `seq`, then latest `issued_at`), and applies it to its local catalog.
5. The backend re-issues an unchanged snapshot every 10 minutes (same `seq`, fresh `issued_at`) so a valid `STOCK` packet always exists.

## Tech stack details

**Android**
- Kotlin, Jetpack Compose, coroutines and Flow
- Room for local storage
- `kotlinx.serialization` for JSON
- OkHttp or Ktor client for HTTP
- `com.google.android.gms:play-services-nearby` for the mesh
- Lazysodium for Android (`com.goterl:lazysodium-android`) for crypto
- minSdk 26. Manual dependency wiring (no DI framework needed).

**Backend**
- Node.js 22 or newer (required by `better-sqlite3` 13), ES modules
- Express, `better-sqlite3`, `libsodium-wrappers`, `ws`
- Node's built-in test runner

Check current versions and APIs in the official docs before adding dependencies.

## Future: ESP32 relay nodes (design only, not built)

Kept here so the current code does not block it.

- A node is an ESP32 with BLE. It exposes one GATT service with a write characteristic (phone to node) and a notify characteristic (node to phone). Packets are split into chunks because one BLE write carries only a few hundred bytes.
- The node runs the same routing rules as `docs/03-protocol.md`: dedupe, hop limit, expiry, verify server signatures on `ACK` and `STOCK`.
- With Wi-Fi configured, the node is also a gateway and uploads to the backend itself.
- On the phone, this becomes a second implementation of `MeshTransport` (`BleGattTransport`) running next to `NearbyTransport`. `MeshRouter` does not change.
- This is why the wire format is plain JSON and why routing never depends on Nearby-specific features.
