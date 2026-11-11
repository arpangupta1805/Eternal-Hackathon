import crypto from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import express from 'express';

import { createAdminRouter } from './admin.js';
import { ROLES } from './constants.js';
import { PUBLIC_KEY_BYTES, b64decode, b64encode, initCrypto, loadOrCreateServerKeys } from './crypto.js';
import { getCatalog, openDb } from './db.js';
import { attachWebSocket, createEvents } from './events.js';
import { createOrderProcessor } from './orders.js';
import { createStockService } from './stock.js';

const PUBLIC_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'public');
export const DATA_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'data');

/**
 * Everything the routes need. With no dataDir, the database and keys live in memory (tests).
 * `now` is injectable so tests can move the clock.
 */
export async function createContext({ dataDir = null, now = () => Date.now() } = {}) {
  await initCrypto();
  const db = openDb(dataDir ? path.join(dataDir, 'eternal.db') : ':memory:');
  const keys = loadOrCreateServerKeys(dataDir ? path.join(dataDir, 'keys.json') : null);
  const events = createEvents();
  const ctx = { db, keys, now, events };
  ctx.stock = createStockService(ctx);
  ctx.orders = createOrderProcessor(ctx);
  return ctx;
}

function badRequest(res, message) {
  return res.status(400).json({ error: 'invalid_request', message });
}

const isStr = (v) => typeof v === 'string';
const isPublicKey = (v) => b64decode(v)?.length === PUBLIC_KEY_BYTES;

function newDeviceId() {
  const alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789';
  let id = 'dev_';
  for (const byte of crypto.randomBytes(6)) id += alphabet[byte % alphabet.length];
  return id;
}

export function createApp(ctx) {
  const { db, keys, now } = ctx;
  const app = express();
  app.use(express.json({ limit: '1mb' }));

  app.get('/health', (req, res) => {
    res.json({ ok: true, service: 'eternal-lite-backend', time: now() });
  });

  app.post('/v1/devices/enroll', (req, res) => {
    const b = req.body ?? {};
    if (!isStr(b.name) || b.name.trim() === '') return badRequest(res, 'name is required');
    if (!isStr(b.phone) || b.phone.trim() === '') return badRequest(res, 'phone is required');
    if (b.address === null || typeof b.address !== 'object' || Array.isArray(b.address)) {
      return badRequest(res, 'address must be an object');
    }
    if (!isStr(b.node_id) || !/^[0-9a-f]{8}$/.test(b.node_id)) {
      return badRequest(res, 'node_id must be 8 lowercase hex characters');
    }
    if (!ROLES.includes(b.role)) return badRequest(res, `role must be one of ${ROLES.join(', ')}`);
    if (!isPublicKey(b.sign_pk)) return badRequest(res, 'sign_pk must be a base64 32-byte key');
    if (!isPublicKey(b.box_pk)) return badRequest(res, 'box_pk must be a base64 32-byte key');

    const deviceId = newDeviceId();
    db.prepare(
      `INSERT INTO devices (device_id, name, phone, address, node_id, role, sign_pk, box_pk, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
    ).run(deviceId, b.name.trim(), b.phone.trim(), JSON.stringify(b.address), b.node_id, b.role, b.sign_pk, b.box_pk, now());

    res.json({
      device_id: deviceId,
      server_sign_pk: b64encode(keys.sign.pk),
      server_box_pk: b64encode(keys.box.pk),
    });
  });

  app.get('/v1/catalog', (req, res) => {
    res.json(getCatalog(db, now()));
  });

  app.post('/v1/mesh/packets', (req, res) => {
    const b = req.body ?? {};
    if (!Array.isArray(b.packets)) return badRequest(res, 'packets must be an array');
    const gatewayNodeId = isStr(b.gateway_node_id) ? b.gateway_node_id.slice(0, 32) : null;
    res.json({ packets: ctx.orders.processPackets(gatewayNodeId, b.packets) });
  });

  app.get('/v1/mesh/stock', (req, res) => {
    res.json({ packets: ctx.stock.latestPackets() });
  });

  app.use('/admin/api', createAdminRouter(ctx));

  // Dashboard (static page at /).
  app.use(express.static(PUBLIC_DIR));

  app.use((req, res) => {
    res.status(404).json({ error: 'not_found', message: `No route for ${req.method} ${req.path}` });
  });

  // eslint-disable-next-line no-unused-vars
  app.use((err, req, res, next) => {
    if (err.type === 'entity.parse.failed') return badRequest(res, 'body is not valid JSON');
    if (err.type === 'entity.too.large') {
      return res.status(413).json({ error: 'too_large', message: 'request body is too large' });
    }
    console.error(err);
    res.status(500).json({ error: 'internal', message: 'internal server error' });
  });

  return app;
}

// Start listening only when run directly (not when imported by tests or scripts).
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT) || 8080;
  const ctx = await createContext({ dataDir: DATA_DIR });
  ctx.stock.latestPackets(); // create the first STOCK packets at startup
  const server = createApp(ctx).listen(port, () => {
    console.log(`Eternal Lite backend listening on http://localhost:${port}`);
    console.log(`Dashboard: http://localhost:${port}/`);
  });
  attachWebSocket(server, ctx);
}
