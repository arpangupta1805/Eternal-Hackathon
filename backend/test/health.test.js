import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/server.js';

test('GET /health returns ok', async () => {
  const server = createApp().listen(0);
  try {
    const { port } = server.address();
    const res = await fetch(`http://127.0.0.1:${port}/health`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.ok, true);
    assert.equal(typeof body.time, 'number');
  } finally {
    server.close();
  }
});
