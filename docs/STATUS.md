# Status

Update this file at the end of every work session. Anyone (human or AI tool) resuming work reads this first.

**Last updated:** 2026-11-12, M3 app code written and building. **It has never run on a phone or emulator.** See "M3: what is and is not verified".

**Current focus:** test M3 on a real phone (steps below), fix what breaks, then M4 mesh routing logic.

## Milestones

- [x] M0. Scaffold (one check outstanding: install the APK on a real phone, see Known issues)
- [x] M1. Backend core
- [x] M2. Dashboard (not yet looked at in a real browser, see Known issues)
- [ ] M3. App without mesh (self-relay path): code complete, builds, crypto verified against the backend. Not ticked because its acceptance test needs a phone.
- [ ] M4. Mesh routing logic (JVM tests)
- [ ] M5. Real transport (Nearby)
- [ ] M6. Stock through the mesh
- [ ] M7. Demo tooling
- [ ] M8. Rehearse and measure

## What M0 verified

- `backend`: `npm test` passes (1 test), `GET /health` returns `{"ok":true,...}` from a running server.
- `android`: `./gradlew assembleDebug` produces `app/build/outputs/apk/debug/app-debug.apk` (about 10.9 MB).
- Not verified: installing that APK on a phone. No device was attached.

## What M1 verified

- `npm test`: 31 tests pass (every case listed in `04-backend.md` "Tests", plus forged ACK, wrong signer, id mismatch, clock-skew boundary, order refs).
- Against a real running server, `scripts/fake-device.js`: `enroll` works; `order` prints a verified, decrypted ACK (3-hop trace stored as `hops = 3`); `--tamper` gets no ACK and a `TAMPERED` log row; `--twice` gets the identical ACK and a `DUPLICATE` log row; `--expired` gets `REJECTED / EXPIRED`.
- `npm run reset` on that server: device kept, `stock_seq` and `catalog_version` went up, the same device ordered again and got `EL-000003` (counter not restarted).

## What M2 verified

- `npm test`: 38 tests pass (31 from M1 plus 7 for the admin API and WebSocket).
- Tests cover both acceptance criteria: an uploaded order is pushed over `WS /admin/ws` with its role-labelled trace, and an admin stock toggle changes `GET /v1/mesh/stock` (seq up, item listed) and turns the next order into `CONFIRMED_MODIFIED` with a substitution.
- The same was run by hand against a live server with `fake-device.js`: 4 enrolled devices (Customer, Rider, Store, gateway), a 3-hop order, a stock toggle, a substituted order, a tampered packet and a rejected order. `/admin/api/state` reported the expected metrics.
- **Not verified:** how the page looks and behaves in a browser. Headless Brave would not produce a screenshot in the build sandbox. The page is served (HTTP 200) and `app.js` passes a syntax check, but nobody has looked at it yet.

## M3: what is and is not verified

Verified on the dev machine:

- `./gradlew assembleDebug` builds a 15 MB APK. `lintDebug`: 0 errors. The APK contains `libsodium.so` and `libjnidispatch.so` for arm64, armv7, x86 and x86_64.
- 16 JVM unit tests pass: `OrderBuilderTest` (4), `CryptoTest` (10), `BackendIntegrationTest` (2).
- **Kotlin and Node agree on bytes.** `BackendIntegrationTest` uses the app's real `ApiClient`, `OrderBuilder`, `OrderSealer`, `SignatureVerifier` and `AckOpener` against a running backend: enroll, catalog, a sealed order is confirmed, the ACK signature verifies and the result decrypts, a second upload returns the identical ACK, a tampered copy gets no ACK.

Not verified (no phone attached, no emulator installed):

- Anything that only runs on Android: the screens, Room, key storage in the Android Keystore, libsodium loading on a device, connectivity detection, and the upload trigger when airplane mode is turned off.

### How to run the M3 acceptance test

1. Start the backend on the laptop: `cd backend && npm run dev`. Find the laptop's LAN IP. The phone must be on the same Wi-Fi, and that Wi-Fi must have working internet (Android only counts a network as online once it is validated).
2. Plug the phone in with USB debugging on: `cd android && ./gradlew installDebug`.
3. Open the app. Fill the form, set "Server address" to `http://<laptop ip>:8080`, tap "Set up this phone". Expect the store list.
4. Online order: add items, open the cart, "Place order". Expect "Confirmed" within a second or two, and the order on the dashboard.
5. Self-relay: turn airplane mode on. The strip turns red ("Lite mode"). Place an order: "Place order via relay", then status "Waiting for a relay or signal". Turn airplane mode off. Expect it to confirm by itself within seconds.
6. If something crashes: `adb logcat -d | grep -E "AndroidRuntime|eternallite"` and send me the output.

## How to run the backend

```
cd backend
npm install
npm run dev                                   # http://localhost:8080
npm test
node scripts/fake-device.js enroll
node scripts/fake-device.js order --item itm_milk_500:2 --item itm_bread_brown:1:SUBSTITUTE:itm_bread_white
```

Dashboard: open `http://localhost:8080/`. To see a labelled multi-hop path without phones, enroll extra fake devices with their own state files and pass their node ids to `--trace`:

```
node scripts/fake-device.js enroll --role RIDER --state data/rider.json
node scripts/fake-device.js enroll --role STORE --state data/store.json
node scripts/fake-device.js order --item itm_milk_500:2 --trace <rider node_id>,<store node_id>
```

## In progress / half-done

- M3 is missing two things from `05-android-app.md` that belong with later milestones: the notification when an ACK arrives (M5, with the foreground service and notification permission) and "N relays nearby" in the status strip (shows 0 until M5).
- The Relay and Debug screens are M5 and M7. Until then the server address can only be set during onboarding; to change it, clear the app's data and set up again.

## Doc changes after review (approved by the user, 2026-11-12)

1. **Roles on the dashboard.** Enrollment now sends `node_id` and `role`; `devices` has both columns; the dashboard labels `trace` entries from them. (`03`, `04`, `05`)
2. **STOCK re-issue.** The server re-signs an unchanged snapshot when it is older than `STOCK_REISSUE_MS` (10 min). To make that flood, the STOCK envelope `id` is now `<store_id>:<seq>:<issued_at>` and "newer" is decided by `(seq, issued_at)`. (`02`, `03`, `04`, `05`)
3. **Reset never lowers counters.** `stock_seq` and `catalog_version` continue from old value + 1. (`03`, `04`)
4. **Reset keeps devices.** `npm run reset` no longer deletes the database; it does the same as `POST /admin/api/reset`. (`04`)
5. **Retry probe.** The stock poll keeps running after a backend network error (`ONLINE_RETRY_MS`, 10 s) so a node can become online again. (`03`, `05`)
6. **Tamper and upload-rejected.** Tamper corrupts only the forwarded copy. A gateway marks an ORDER that got HTTP 200 but no ACK as `upload_rejected` and never re-uploads it. (`03`, `05`, `06`)
7. **Demo step 6** uses the Relay switch, not Bluetooth, to isolate phone A. The relay switch now only stops Nearby; the uploader keeps running. The step 5 order also confirms in step 6, and the script says so. (`01`, `05`, `06`)

## Decisions made during build

Record any choice not covered by the docs, with one line of reasoning.

- Health route is `GET /health`. The docs did not name a path.
- A signed order whose body is invalid (missing fields, `qty` not positive) is logged `MALFORMED` with no ACK. No reject reason exists for it. Written into `04-backend.md`.
- Packets from a gateway response are added to the seen set. Otherwise the same ACK would be processed again when a peer sends it back. Written into `03-protocol.md`.
- A stored ACK returned for a re-upload more than 60 minutes later has an expired envelope. The originator still processes it (response handling skips the expiry check) but does not forward it.
- A delivery fee change gives `CONFIRMED_MODIFIED` with empty `changes`. No admin endpoint changes the fee, so no new change type was added.
- Full catalog refresh happens when a node becomes online, not on a timer.
- Milestones are built strictly in order (M1 and M3 are not parallelised).
- Android build stack: AGP 8.13.2, Gradle 8.14.3 (wrapper), Kotlin 2.2.21, Compose BOM 2025.12.01, compileSdk and targetSdk 36, minSdk 26, Java target 17. Chosen as the latest AGP 8.x line because it works with the SDK platform (36) and Gradle already on the dev machine. AGP 9.x exists but needs a newer Gradle and changes Kotlin setup; not worth the risk for a demo.
- No Java toolchain pin in Gradle: the build uses whatever JDK runs Gradle (JDK 21 here) and targets Java 17 bytecode. A pinned toolchain failed because JDK 17 is not installed.
- Backend: Express 5.3.0. Other dependencies (`better-sqlite3`, `libsodium-wrappers`, `ws`) are added in M1 and M2 when first used.
- App icon is the Android default for now.
- **Node 22 or newer is required**, not 20: the current `better-sqlite3` (13.1.1) needs it. Docs updated. Dev machine has Node 23.7.
- Backend versions: `better-sqlite3` 13.1.1, `libsodium-wrappers` 0.8.4, Express 5.3.0.
- Two files beyond the layout in `02-architecture.md`: `src/constants.js` (protocol constants) and `scripts/device-lib.js` (device-side protocol code shared by `fake-device.js` and the tests). Added to the layout doc.
- STOCK packets are built lazily on `GET /v1/mesh/stock` (when `stock_seq` changed or the packet is older than 10 minutes) and once at startup. Same result as "created whenever `stock_seq` changes", with no invalidation to get wrong, and it also picks up `npm run reset` done from another process.
- A `REJECTED` AckResult has `server_order_ref` and `eta_minutes` null, `changes` empty, `delivery_fee` 0. Rejected orders do not consume an order ref. Written into `03-protocol.md`.
- Packet log outcome `ACCEPTED` means "processed and answered", so rejected orders are logged `ACCEPTED` too. The dashboard metric "orders accepted" must count by order status, not by this log.
- The server does not check that `total_seen` matches the body's own arithmetic or that `expires_at - created_at` is 20 minutes. It computes its own total and trusts the signed `expires_at`, as the docs say.
- Each line of `items` is evaluated on its own; the same `item_id` appearing twice is not merged.
- `GET /v1/catalog` lists stores in `store_id` order and items in seed order.
- One more file beyond the layout: `src/admin.js` (admin routes, dashboard state, metrics). Added to the layout doc. `ws` version 8.22.2.
- Admin endpoints answer with the full dashboard state and push the same as a `catalog` event, so the page never has to merge partial updates. Shapes are written into `04-backend.md`.
- "Orders confirmed" on the dashboard counts orders by status. "Success rate" is orders that reached the server (any answer, including rejected) divided by the attempted count the operator types in; that count is stored in the browser's localStorage.
- "Tampered packets accepted" is a constant 0, true by construction (an order is stored only after its signature verifies). It is not a measured counter.
- Hop path marks are letters by position (A, B, C, D, then S for the server), with the role label and node id under each. A node id the server never enrolled shows as "Unknown phone".
- The dashboard uses system fonts only, so it does not depend on a font CDN during the demo.
- App libraries: Room 2.8.4 with KSP 2.3.4, kotlinx-serialization-json 1.9.0, coroutines 1.10.2, OkHttp 4.12.0, navigation-compose 2.9.6, Lazysodium-android 5.2.0 with JNA 5.17.0. Versions chosen to match Kotlin 2.2.21; newer serialization and coroutines releases target newer Kotlin.
- The server address is asked for on the onboarding form, because enrollment cannot happen without it. Default text is a placeholder LAN address.
- The address form has no coordinates, so `address.lat` and `address.lng` are sent as `null`. Written into `03-protocol.md`.
- No view models. Screens read Room flows straight from the repositories. Less code, and all state already lives in the database.
- A cart holds items from one store. Adding from another store asks first, then empties the cart.
- Placing an order is allowed with out-of-stock items in the cart (they are flagged in red). The demo needs this: the server applies the fallback rule.
- "Replace with" and nothing picked is sent as `DROP`.
- The uploader, expiry timer and retry probe run in an application-wide coroutine scope for now, not a foreground service. `MeshService` arrives in M5 with Nearby. Until then they stop if Android kills the app in the background; with the app open they work.
- The retry probe is a bare `GET /v1/mesh/stock` that ignores the packets. M6 turns it into the real `StockPoller`.
- Besides upload triggers on "became online" and "order placed", the 30-second expiry timer also kicks an upload round as a safety net.
- If the server answers an upload of the user's own order with no ACK, the order is flagged and the status screen says the phone may need to be set up again (this happens if the backend database was deleted).
- Room uses destructive migration: a schema change in a later milestone wipes local app data, and the phone must be set up again.
- API errors use `error` codes `invalid_request` (400), `not_found` (404), `too_large` (413), `internal` (500).

## Deviations from the docs

Record anything built differently from the docs, and update the doc itself too.

(none: the review changes above were written into the docs)

## Known issues

- M0 acceptance says "the empty app installs on a phone". Only the build is verified. To finish the check: enable USB debugging, plug a phone in, run `cd android && ./gradlew installDebug`.
- **The Android app has never been run.** Treat the first install as a test, not a demo. Most likely trouble spots: libsodium or JNA failing to load on the device, and the Wi-Fi network not counting as "validated".
- The dashboard has not been viewed in a browser yet. Run `npm run dev`, open `http://localhost:8080/`, place a few fake-device orders, and check layout, the hop-chain animation on a new order, the stock switches and price fields, and readability from a distance.
- Android Studio is not installed on the dev machine. Builds work from the command line with `./gradlew`.
