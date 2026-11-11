# Eternal Lite: instructions for AI coding tools

You are working on **Eternal Lite**, a hackathon project by team SwiggyOnTop (IIT Gandhinagar) for the Eternal Hackathon, Round 2. Eternal is the parent company of Zomato, Blinkit and District.

**One-line goal:** a customer with no internet builds a cart from a cached menu, and the encrypted order hops phone to phone over Google Nearby Connections until a phone with internet uploads it to a mock Eternal backend. A signed confirmation floods back to the customer.

## Read these before writing any code

Read in this order. They are the source of truth. Do not fill gaps with assumptions.

1. `docs/01-overview.md`: what we are building, for whom, what is in and out of scope
2. `docs/02-architecture.md`: components, tech stack, repo layout, locked decisions
3. `docs/03-protocol.md`: packet formats, crypto, mesh routing rules (most important file)
4. `docs/04-backend.md`: API, database, order validation, dashboard
5. `docs/05-android-app.md`: app structure, screens, Nearby setup, debug tools
6. `docs/06-build-plan.md`: milestones, acceptance criteria, demo script
7. `docs/STATUS.md`: what is done, what is in progress. Check this first when resuming.

## Rules

1. **Do not assume.** If the docs do not answer a question and the answer changes behaviour, ask the user. If it is a small implementation detail, choose the simplest option and record it in `docs/STATUS.md` under "Decisions made during build".
2. **Locked decisions stay locked.** The table in `docs/02-architecture.md` is final unless the user says otherwise. Do not swap libraries, transports or frameworks on your own.
3. **Protocol changes need a doc change.** The Android app and the backend must agree byte for byte. If you change anything in `docs/03-protocol.md`, update the doc in the same commit and update both sides.
4. **Stay in scope.** `docs/01-overview.md` lists non-goals. Do not build them, even partially.
5. **Verify library versions and APIs.** Do not trust version numbers or API signatures from memory. Check the current official documentation for Nearby Connections, Lazysodium, libsodium-wrappers and Android permissions before using them.
6. **Update `docs/STATUS.md`** at the end of every work session: tick finished items, note what is half-done, note any deviation.
7. **Existing code wins over layout in the docs.** If the repo already has a different structure from `docs/02-architecture.md`, ask the user before restructuring.
8. **Demo first.** The deliverable is a live demo on 4 Android phones. Prefer simple, reliable code over clever code. No feature matters if the demo breaks.

## Quick facts

| Thing | Value |
|---|---|
| App | Native Android, Kotlin, Jetpack Compose |
| Mesh transport | Google Nearby Connections, `Strategy.P2P_CLUSTER` |
| Routing | Flood with dedupe, hop limit, expiry |
| Backend | Node.js, Express, SQLite |
| Crypto | libsodium (sealed boxes + Ed25519) |
| Payment | Cash on delivery only |
| Money | Integer paise everywhere, never floats |
| Time | Unix milliseconds (UTC) everywhere |
