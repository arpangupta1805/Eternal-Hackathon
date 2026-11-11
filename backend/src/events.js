// Event fan-out to the dashboard. M1 only has in-process listeners;
// the WebSocket transport (/admin/ws) subscribes here in M2.

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
