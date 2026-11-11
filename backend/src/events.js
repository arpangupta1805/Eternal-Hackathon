// Event fan-out to the dashboard: in-process listeners plus the /admin/ws WebSocket.

import { WebSocketServer } from 'ws';
import { getMetrics, getOrderSummary } from './admin.js';

export function createEvents() {
  const listeners = new Set();
  return {
    /** fn receives { event: "order" | "catalog" | "packet", data }. Returns an unsubscribe function. */
    subscribe(fn) {
      listeners.add(fn);
      return () => listeners.delete(fn);
    },
    broadcast(event, data) {
      for (const fn of listeners) {
        try {
          fn({ event, data });
        } catch (err) {
          console.error('event listener failed:', err);
        }
      }
    },
  };
}

/**
 * Serves WS /admin/ws on an http.Server and pushes every event to all connected dashboards.
 *   order   -> { order: <order summary>, metrics }
 *   packet  -> { ...packet_log row, metrics }
 *   catalog -> the full state (same shape as GET /admin/api/state)
 */
export function attachWebSocket(httpServer, ctx) {
  const wss = new WebSocketServer({ noServer: true });

  httpServer.on('upgrade', (req, socket, head) => {
    const { pathname } = new URL(req.url, 'http://localhost');
    if (pathname !== '/admin/ws') {
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
  });

  wss.on('connection', (ws) => {
    ws.on('error', () => {});
  });

  ctx.events.subscribe(({ event, data }) => {
    if (wss.clients.size === 0) return;
    let payload = data;
    if (event === 'order') {
      payload = { order: getOrderSummary(ctx.db, data.order_id), metrics: getMetrics(ctx.db) };
    } else if (event === 'packet') {
      payload = { ...data, metrics: getMetrics(ctx.db) };
    }
    const message = JSON.stringify({ event, data: payload });
    for (const client of wss.clients) {
      if (client.readyState === client.OPEN) client.send(message);
    }
  });

  return wss;
}
