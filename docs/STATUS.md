# Status

Update this file at the end of every work session. Anyone (human or AI tool) resuming work reads this first.

**Last updated:** 2026-11-12, M0 scaffold built. Docs revised for seven gaps found in review (see "Doc changes after review").

**Current focus:** M1 backend core.

## Milestones

- [x] M0. Scaffold (one check outstanding: install the APK on a real phone, see Known issues)
- [ ] M1. Backend core
- [ ] M2. Dashboard
- [ ] M3. App without mesh (self-relay path)
- [ ] M4. Mesh routing logic (JVM tests)
- [ ] M5. Real transport (Nearby)
- [ ] M6. Stock through the mesh
- [ ] M7. Demo tooling
- [ ] M8. Rehearse and measure

## What M0 verified

- `backend`: `npm test` passes (1 test), `GET /health` returns `{"ok":true,...}` from a running server.
- `android`: `./gradlew assembleDebug` produces `app/build/outputs/apk/debug/app-debug.apk` (about 10.9 MB).
- Not verified: installing that APK on a phone. No device was attached.

## In progress / half-done

(nothing)

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

## Deviations from the docs

Record anything built differently from the docs, and update the doc itself too.

(none: the review changes above were written into the docs)

## Known issues

- M0 acceptance says "the empty app installs on a phone". Only the build is verified. To finish the check: enable USB debugging, plug a phone in, run `cd android && ./gradlew installDebug`.
- Android Studio is not installed on the dev machine. Builds work from the command line with `./gradlew`.
