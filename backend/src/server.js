import express from 'express';
import { fileURLToPath } from 'node:url';

export function createApp() {
  const app = express();
  app.use(express.json({ limit: '1mb' }));

  app.get('/health', (req, res) => {
    res.json({ ok: true, service: 'eternal-lite-backend', time: Date.now() });
  });

  return app;
}

// Start listening only when run directly (not when imported by tests).
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT) || 8080;
  createApp().listen(port, () => {
    console.log(`Eternal Lite backend listening on http://localhost:${port}`);
  });
}
