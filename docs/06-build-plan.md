# 06. Build plan

Build in this order. Each milestone ends in something that can be shown working. Do not start a milestone until the previous one meets its acceptance criteria. Update `STATUS.md` as you go.

With three people, a natural split is: (1) backend and dashboard, (2) app data, crypto and UI, (3) mesh. M1 and M3 can run in parallel once `03-protocol.md` is read by everyone.

## M0. Scaffold

- `/backend` Node project, `/android` Android Studio project, `/firmware/README.md` (one paragraph: future ESP32 node, not built)
- `.gitignore` for `backend/data/`, `node_modules`, Android build outputs

**Done when:** backend starts and serves a health route; the empty app installs on a phone.

## M1. Backend core

- Schema, seed, server keys
- `enroll`, `catalog`, `mesh/packets`, `mesh/stock`
- Full order processing from `04-backend.md`
- `scripts/fake-device.js`
- Tests

**Done when:** `fake-device.js` can enroll, place an order and print a verified, decrypted ACK; `--tamper` gets no ACK; `--twice` gets the identical ACK; all tests pass.

## M2. Dashboard

- Admin API, WebSocket, the page

**Done when:** an order from `fake-device.js` appears live with its trace; toggling stock changes `GET /v1/mesh/stock` and the next order's result.

## M3. App without mesh (self-relay path)

- Enrollment, key generation, catalog cache
- Home, menu, cart with fallback rules, order status
- Seal and sign
- `ConnectivityMonitor`, `GatewayUploader`

**Done when:** on one phone, an order placed online is confirmed; an order placed in airplane mode sits as "Queued", and is confirmed by itself within seconds of turning airplane mode off. This is already a complete end-to-end product slice.

## M4. Mesh routing logic (no phones)

- `MeshRouter`, `PacketStore`, frames, all routing rules
- In-memory fake transport and the 4-node JVM test

**Done when:** unit tests pass for: flood A to D, upload at D, ACK back to A, orders deleted on ACK, duplicate dropped, hop limit, expiry, forged ACK ignored, STOCK newer replaces older and older is ignored.

## M5. Real transport

- `NearbyTransport`, `MeshService`, permissions, Relay screen
- Two phones first, then three

**Done when:** phone A in airplane mode (Bluetooth on) places an order, phone B (online) uploads it, and the confirmation shows on A.

Test early on the actual demo phones: Nearby may use Wi-Fi radios for its own links, which can interrupt a gateway's Wi-Fi internet. Keep the gateway phone on **mobile data**.

## M6. Stock through the mesh

- `StockPoller`, STOCK handling, live menu update

**Done when:** toggling an item on the dashboard greys it out on an offline phone connected through at least one relay.

## M7. Demo tooling

- Debug screen: allowed peers, force offline, tamper, packet viewer, event log
- Role labels shown on Relay screen and dashboard
- Metrics on dashboard

**Done when:** four phones forced into A-B-C-D deliver an order with trace length 4 (3 hops) shown on the dashboard.

## M8. Rehearse and measure

- Run 20 orders across 3 hops. Record success rate, latency per order, hops.
- Run the tamper test several times. Record tampered accepted (must be 0).
- Battery: note battery percentage on a relay phone before and after a 30-minute relaying session with a fixed number of packets. Report it as a rough figure, not a precise per-packet cost.
- **Record a backup video of the full demo working.**
- Polish UI text and dashboard readability.

## Demo script (about 4 minutes)

Setup before going on stage: all four phones enrolled and catalog synced. Allowed peers set: A allows B. B allows A and C. C allows B and D. D allows C. Roles: A Customer, B Rider, C Store node, D Customer. Phones A, B and C in airplane mode with Bluetooth (and Wi-Fi radio) turned back on. D on mobile data. Dashboard on the projector.

1. **Show the problem.** Phone A has no internet. A normal app would spin. Ours shows "Lite mode" and "Menu as of 3 min ago".
2. **Order across 3 hops.** Place a Blinkit order on A. The dashboard shows it arrive with the path A → B (Rider) → C (Store node) → D → Server. The confirmation appears on A.
3. **Live stock through the mesh.** On A, add brown bread to the cart and set "If unavailable: replace with white bread". Then mark brown bread out of stock on the dashboard. On offline phone A it greys out in the menu and is flagged in the cart.
4. **One round trip, not two.** Place the order anyway. It comes back "Confirmed with changes: brown bread replaced with white bread", with no second approval step.
5. **Security.** Turn on "Tamper next relayed order" on B. Place an order on A. The dashboard's tampered-rejected counter goes up by exactly 1 (the gateway does not re-upload a rejected packet), tampered-accepted stays 0. The order stays unconfirmed on A for now.
6. **Self-relay.** Turn the **Relay switch off** on A (no peers; more reliable than turning Bluetooth off, because Nearby may be using Wi-Fi for the link). Place an order: "Waiting for a relay or signal". Turn airplane mode off. It confirms by itself. **Expect two confirmations here:** A still holds the intact copy of the step 5 order, so that one is uploaded and confirmed too. Say so: "the tampered copy was thrown away, and the customer's own phone delivered the real one."
7. **Numbers.** Show the measured success rate, latency and hops from rehearsal.

## Judge questions to be ready for

| Question | Answer |
|---|---|
| Who relays if few users have this on? | Riders (app always open), stores and fixed nodes first. Crowd relaying is a bonus. And the customer's own phone always self-relays. |
| Why not just wait for signal? | We do that too (self-relay). The mesh makes it minutes faster where signal is a floor away, and makes it possible at venues where the towers stay jammed for hours. |
| Why not SMS? | No SMS in a basement. Where SMS works, it can be one more transport for the same sealed packet. |
| What if the item went out of stock? | Three layers: cached menu with age shown, signed stock updates through the mesh, and the server applies the customer's pre-chosen fallback in one round trip. |
| Can a relay read or change my order? | No. Sealed to the server's key and signed by the device. Tampering breaks the signature. |
| Duplicates? | One order ID. First copy wins, others get the same answer. |
| Order arrives two hours late? | Orders expire after 20 minutes. The server rejects late ones. |
| Payment? | Cash on delivery now. Proposed: server debits the in-app wallet when the order arrives. |
| Battery? | Bluetooth low energy for discovery, packets of a few KB. Rough measured figure from rehearsal. |
| Internet shutdowns? | Not our target. A shutdown is district-wide, so no gateway is in range. We target short dead zones and venues. |

## Risks

| Risk | Mitigation |
|---|---|
| Nearby connections flaky in a room full of phones and Wi-Fi | Rehearse in a crowded place. Keep phones close. Backup video. |
| Gateway loses internet when Nearby uses Wi-Fi | Gateway on mobile data. Test in M5. |
| Backend not reachable from mobile data | Public deployment or tunnel, tested the day before. |
| App and backend disagree on bytes | `fake-device.js` and tests are the reference. Protocol doc is the contract. |
| Phone clocks differ | Server allows 2 minutes of skew. Sync phone clocks before the demo. |
| Scope creep | Non-goals list in `01-overview.md`. |
