# Firmware (not built)

Placeholder for a future ESP32 relay node. The idea: a low-cost BLE node placed in parking areas, malls, basements and Blinkit stores that runs the same relay rules as the phone app (`docs/03-protocol.md`: dedupe, hop limit, expiry, server-signature checks on `ACK` and `STOCK`) and, when it has Wi-Fi, also acts as a gateway. Nothing here is implemented and there is no hardware in this phase; the design notes live in `docs/02-architecture.md` under "Future: ESP32 relay nodes".
