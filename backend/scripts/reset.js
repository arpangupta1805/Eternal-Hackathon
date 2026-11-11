// npm run reset: clears orders and the packet log, re-seeds the catalog.
// Keeps devices and server keys, and never lowers stock_seq or catalog_version.

import path from 'node:path';
import { getMeta, listStores, openDb, resetData } from '../src/db.js';
import { DATA_DIR } from '../src/server.js';

const db = openDb(path.join(DATA_DIR, 'eternal.db'));
resetData(db);
const devices = db.prepare('SELECT COUNT(*) AS n FROM devices').get().n;
console.log(`Reset done. catalog_version=${getMeta(db, 'catalog_version')}, devices kept=${devices}`);
for (const s of listStores(db)) console.log(`  ${s.store_id}: stock_seq=${s.stock_seq}`);
db.close();
