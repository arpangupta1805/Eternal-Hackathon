# 05. Android app

One app, all roles. Kotlin, Jetpack Compose, minSdk 26. Package `com.eternallite.app`. Packet formats, crypto and routing rules are in `03-protocol.md` and must be followed exactly.

## Package structure

| Package | Contents |
|---|---|
| `data` | Room database, entities, DAOs, `CatalogRepository`, `OrderRepository`, settings |
| `crypto` | `KeyStore` (device keys), `OrderSealer`, `AckOpener`, `SignatureVerifier` |
| `mesh` | `MeshTransport` interface, `NearbyTransport`, `MeshRouter`, `PacketStore`, frame and packet models |
| `net` | `ApiClient`, `ConnectivityMonitor`, `GatewayUploader`, `StockPoller` |
| `service` | `MeshService` (foreground service that owns transport, router, uploader, poller) |
| `ui` | Compose screens. No view models: screens collect Room flows from the repositories through the app container. |
| `debug` | Debug settings, tamper tool, metrics |

## Key design rule: router is pure Kotlin

`MeshRouter` contains all routing rules and must not import any Android or Nearby class. It depends only on these interfaces:

```kotlin
interface MeshTransport {
    val events: Flow<TransportEvent>      // PeerConnected(peerId, nodeId, role), PeerDisconnected(peerId), FrameReceived(peerId, bytes)
    suspend fun start()
    suspend fun stop()
    suspend fun send(peerId: String, bytes: ByteArray)
}
```

plus `PacketStore`, a clock, a signature verifier, and an "is online" flow.

This allows a **JVM unit test with an in-memory fake transport** that wires 4 routers in a line and checks that an order floods A to D, the ACK floods back, orders are deleted on ACK, duplicates are dropped, hop limit and expiry work, and newer STOCK replaces older. Build this test before touching real phones. It is the fastest way to get the mesh logic right.

## Local storage (Room)

| Table | Purpose |
|---|---|
| `stores`, `items` | Cached catalog. `items.in_stock` is updated by STOCK packets. |
| `catalog_meta` | `catalog_version`, `synced_at`, per-store `stock_seq` and `stock_updated_at` |
| `cart_items` | Current cart: item, qty, `on_unavailable`, `substitute_item_id` |
| `orders` | The user's own orders: `order_id`, body JSON, **the sealed ORDER envelope**, state, AckResult JSON, timestamps, peers it was handed to |
| `packets` | The packet store: key, type, id, envelope JSON, `expires_at`, `hops_left`, `received_at`, `is_own`, `upload_rejected` |
| `seen_keys` | Keys already processed, with a timestamp. Purge entries older than 2 hours. |
| `settings` | `node_id`, `device_id`, role label, server URL, server public keys, relay enabled, debug options |

The user's own sealed envelope is kept in `orders` as well as in `packets`, so an `UNCONFIRMED` order can still be re-uploaded after its packet has expired and been purged.

## Nearby Connections setup

- `Strategy.P2P_CLUSTER`, `serviceId` `com.eternallite.mesh`, payload type `BYTES`.
- Advertise and discover at the same time, for as long as `MeshService` runs.
- Endpoint name: `EL|<node_id>|<role>`.
- Only the node with the smaller `node_id` calls `requestConnection`. Both sides accept automatically in `onConnectionInitiated`, unless the debug allowed-peers list is non-empty and the peer's `node_id` is not in it, in which case reject.
- On disconnect or failure, discovery keeps running, so reconnection happens by itself.
- Required permissions depend on Android version (Bluetooth scan, advertise and connect, fine location, nearby Wi-Fi devices on newer versions, Wi-Fi state). **Take the exact list from the current official Nearby Connections documentation.** Request them at runtime on the onboarding screen and explain why.
- A `BYTES` payload has a size limit of about 32 KB, which is why `MAX_PACKET_BYTES` is 16384.

`NearbyTransport` is the only class that touches the Nearby API.

## MeshService

A foreground service with a persistent notification ("Eternal Lite relay active, N peers"). It starts when the app is enrolled and keeps running whether relay is on or off. **The relay switch only starts and stops `NearbyTransport`** (advertising, discovery and all peer connections). The uploader and poller keep running with relay off, so self-relay always works. It owns:

- `NearbyTransport` and `MeshRouter`
- `ConnectivityMonitor`: exposes `isOnline` (validated internet, debug force-offline off, and the last backend call did not fail with a network error)
- `GatewayUploader`: whenever `isOnline` is true, uploads all stored ORDER packets that are not marked `upload_rejected`, and the user's own unconfirmed orders; triggers immediately when `isOnline` flips to true and whenever a new ORDER is stored. An ORDER that was uploaded with HTTP 200 but got no ACK is marked `upload_rejected` and not uploaded again (see `03-protocol.md`).
- `StockPoller`: polls `GET /v1/mesh/stock` every 10 seconds whenever the system reports validated internet and force-offline is off, **even if the last backend call failed**. This poll is the retry probe: its first success marks the node online again. It also refreshes the full catalog when the node becomes online.
- An expiry timer every 30 seconds

For the demo, the app stays open on all phones. Aggressive background killing on some phone brands is a known limitation, not something to solve now.

## Screens

**1. Onboarding and enrollment** (needs internet once)
Name, phone, address form (flat or building, area, landmark; no coordinates). Role label picker (Customer, Rider, Store node). Server address field (the same value is editable later in the debug screen). Permission requests (added in M5 with Nearby). On submit: generate keys and `node_id`, enroll (sending `node_id` and the chosen role), download catalog. If offline, say clearly that first-time setup needs internet.

**2. Home**
List of stores. A status bar at the top that is always visible on every screen:
- Online: "Online"
- Offline: "Lite mode: no internet. Orders will be relayed." plus "N relays nearby"
- "Menu as of N min ago" (from `synced_at` or the latest stock snapshot's `issued_at`, whichever is newer; server re-issues keep this fresh while the phone is in reach of the mesh)

**3. Store menu**
Items by category with price and add/remove buttons. Out-of-stock items are greyed out with an "Out of stock" tag and cannot be added. When a STOCK packet arrives, the list updates live. If a cart item goes out of stock, flag it in the cart.

**4. Cart**
- Items, quantities, delivery fee, total
- Per item, "If unavailable": Remove it (default) / Replace with… (pick another in-stock item from the same store) / Cancel whole order
- Price tolerance: "Accept if total rises by up to ₹20" (editable)
- Payment: Cash on delivery (fixed, shown but not selectable)
- A note: "This order is valid for 20 minutes"
- "Place order" button. Offline label: "Place order via relay". Online label: "Place order".

Placing the order always goes through the same path: build body, sign, seal, wrap, store in `orders` and `packets`. If online, the uploader sends it at once.

**5. Order status**
A timeline for one order:
- Sealed and queued
- Handed to N relays (or "Waiting for a relay or signal")
- Confirmed / Confirmed with changes / Rejected / Unconfirmed
Shows the final items, every change (dropped, substituted, price changed), final total, ETA, order ref, or the reject reason in plain words. A countdown shows time left before the order expires. A notification fires when the ACK arrives.

**6. Orders list**
All of the user's orders with state.

**7. Relay**
Node ID, role, relay on/off switch (off = no Nearby advertising, discovery or peers; own orders still upload when the phone has internet), connected peers (node ID and role), number of packets carried, counts relayed and uploaded.

**8. Debug** (reachable from Relay screen)
- Server URL
- **Allowed peers**: list of `node_id`s. Empty means connect to anyone.
- **Force offline**: treat internet as unavailable
- **Tamper next relayed order**: one-shot. For the next ORDER this node forwards, one byte of the decoded payload is flipped (then re-encoded as base64) in the copy sent to peers. The node's own stored copy is left intact, and the tampered copy keeps the same packet key.
- Role label (changes the Nearby endpoint name only; the dashboard label is the role sent at enrollment)
- Packet store viewer (key, hops left, expires in, trace)
- Event log (connected, received, forwarded, uploaded, dropped with reason)
- Clear all data
- Battery reading at relay start and now

## Crypto on the device

- Lazysodium for all operations in `03-protocol.md`. All calls go through one small interface (`crypto/Sodium.kt`), so the same sealing and ACK-opening code runs in JVM unit tests with desktop libsodium.
- `BackendIntegrationTest` runs that code against a live backend: `ETERNAL_BACKEND_URL=http://localhost:8080 ./gradlew testDebugUnitTest`. It is skipped when the variable is not set.
- Generate `device_sign` and `device_box` keypairs on first launch.
- Store secret keys encrypted with an AES key from the Android Keystore.
- Server public keys come from the enrollment response and are stored in settings.
- Every `ACK` and `STOCK` is verified against `server_sign_pk` before it has any effect.

## Network

- HTTP client with short timeouts (5 seconds connect, 10 seconds read). A network failure marks the node as not online until the next successful call or connectivity change. The stock poll keeps running as a retry probe so that "next successful call" can happen.
- Debug builds allow cleartext HTTP so a LAN IP can be used during development.

## UI style

Clean and simple, Material 3. Use Eternal-like red as the accent. The demo is watched on small phone screens by judges, so status text must be large and unambiguous. No real Zomato or Blinkit logos or artwork.
