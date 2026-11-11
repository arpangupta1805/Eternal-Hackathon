# 01. Overview

## The problem

Eternal (Zomato, Blinkit, District) loses orders wherever a customer's phone has no usable internet for a short while: office basements, lifts, parking levels, malls, hostels and PGs with weak signal, and packed venues where the towers are jammed. The customer is ready to pay, the app cannot reach the server, and the cart is abandoned.

In almost all of these places, working internet is only 20 to 100 metres away, on someone else's phone, a rider's phone, or a store's Wi-Fi.

## The solution

**Lite mode** inside the Eternal app:

1. **Order offline.** The app keeps a cached, versioned catalog (menus, prices, stock flags) and the user's address. The user builds a cart with zero bars.
2. **Seal.** The order is signed by the device and encrypted so only Eternal's server can read it.
3. **Relay.** The sealed packet is passed to nearby devices running the app. Each one stores it and passes it on (store-and-forward). Relays cannot read or alter it.
4. **Upload.** The first device that has internet (the gateway) uploads it. That can be a relay, or the customer's own phone the moment it regains signal.
5. **Confirm.** The server validates the order against live stock and prices, applies the customer's pre-approved fallback rules, and returns one final signed answer. The answer floods back through the mesh to the customer.

## Positioning (use this framing everywhere)

We pitch infrastructure Eternal already controls first, and the crowd second.

| Priority | Who relays | Why it works |
|---|---|---|
| 1 | **Self-relay** | The customer's own phone queues the order and submits it automatically when any signal returns. Works with zero other users. |
| 2 | **Riders** | The rider app is always open in the foreground, so background limits and opt-in are not a problem. Riders move through every neighbourhood. |
| 3 | **Stores and fixed nodes** | Blinkit stores, restaurants, and cheap relay nodes in malls, parking areas and basements. Fixed, powered, wired internet. |
| 4 | **District venues** | Thousands of phones packed together, jammed towers, and a venue Eternal can equip with nodes. Best first market. |
| 5 | **Other customers** | Opt-in crowd relaying. A bonus on top, not the foundation. |

Internet shutdowns are background context only. Do not lead with them: a shutdown covers a whole district, so no gateway is within Bluetooth range, and restaurants and riders are offline too.

## What "done" means for Round 2

A live demo with **4 Android phones, only 1 online**:

- Phone A (customer) is in airplane mode with Bluetooth and the Wi-Fi radio turned back on (no internet). It places an order.
- The order travels A to B to C to D (3 hops). B is labelled "Rider", C is labelled "Store node", D is the gateway with mobile data.
- D uploads to the backend. The dashboard shows the order and its hop path live.
- The confirmation travels back and appears on phone A.
- An operator marks an item out of stock on the dashboard, and offline phone A greys it out a few seconds later.
- An order containing that out-of-stock item is still handled in one round trip using the customer's fallback rule.
- A tampered packet is rejected by the server.
- Phone A alone (no peers) places an order, then airplane mode is turned off, and the order submits by itself (self-relay).

Measured numbers we report: delivery success rate, end-to-end latency, hops, duplicates dropped, tampered packets accepted (target 0), and a rough battery figure.

## In scope

- One Android app containing all roles (customer, relay, gateway)
- Cached catalog, offline cart, per-item fallback rules, price tolerance
- Order sealing, signing, expiry
- Mesh relay over Nearby Connections with flood routing
- Self-relay queue
- Signed ACK flooded back to the customer
- Signed stock updates flooded into the mesh
- Mock Eternal backend with dedupe, validation, re-pricing
- Live web dashboard: orders, hop path, stock toggles, metrics
- Debug tools: forced topology, force-offline, tamper button

## Non-goals (do not build)

- iOS app
- Real payments. Payment is always `COD`. The wallet is a slide-only proposal (see below).
- Real Zomato or Blinkit APIs. Everything is mocked.
- Raw Wi-Fi Direct or raw BLE GATT transport in the phone app
- ESP32 firmware. We design for it (see `02-architecture.md`) but do not build it. We have no hardware in the development phase.
- SMS fallback
- Order cancellation after sealing
- Rider status updates (pickup, drop) over the mesh
- Relay rewards or incentives
- User accounts, login, OTP. Enrollment is a simple form.
- Order tracking after confirmation

## Proposals we mention but do not build

**Wallet payment.** The order carries `payment: "WALLET"`. Nothing is deducted on the phone. When the order reaches the server, the server debits the customer's in-app wallet balance and confirms, or rejects with `INSUFFICIENT_BALANCE`. The server stays the single authority, so there is no offline double-spend problem.

**Fixed relay nodes.** Low-cost ESP32 nodes in parking areas, malls, basements and Blinkit stores. They run the same relay rules. With Wi-Fi they are also gateways.

**SMS as a transport.** In places where SMS works but data does not, the same sealed packet can travel by SMS.

## Glossary

| Term | Meaning |
|---|---|
| Node | Any device running the app. Has a random `node_id`. |
| Originator | The node that created an order. |
| Relay | A node carrying packets that are not its own. |
| Gateway | Any node that currently has internet. Not a fixed role: a node becomes a gateway whenever it is online. |
| Self-relay | The originator uploading its own order when it comes online. |
| Packet | The outer envelope relays can see. Types: `ORDER`, `ACK`, `STOCK`. |
| Sealed | Encrypted so that only the holder of a private key can open it. |
| ACK | The server's signed final answer for one order. |
| Catalog | Stores, items, prices and stock flags, with a version number. |
| Hop | One transfer of a packet between two nodes. |
