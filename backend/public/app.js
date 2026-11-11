// Eternal Lite dashboard. Vanilla JS, no build step.
// Loads GET /admin/api/state, then stays live over WS /admin/ws.

const state = { stores: [], orders: [], metrics: null };
const openDetails = new Set(); // order_ids whose detail is expanded
const freshOrders = new Set(); // order_ids that should animate in on the next render
let catalogDirty = false; // a catalog update arrived while a price field was being edited

const $ = (id) => document.getElementById(id);

const ROLE_LABEL = { CUSTOMER: 'Customer', RIDER: 'Rider', STORE: 'Store node' };
const STATUS_LABEL = { CONFIRMED: 'Confirmed', CONFIRMED_MODIFIED: 'Confirmed with changes', REJECTED: 'Rejected' };
const REASON_LABEL = {
  EXPIRED: 'The order arrived after its 20 minutes ran out.',
  STORE_CLOSED: 'The store is closed.',
  ITEM_UNAVAILABLE: 'An item was out of stock and the customer chose not to accept changes, or nothing was left.',
  PRICE_EXCEEDED: 'The current total is above the most the customer agreed to pay.',
};

// ---- formatting ----

function esc(value) {
  return String(value).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function rupees(paise) {
  const r = paise / 100;
  return `₹${Number.isInteger(r) ? r : r.toFixed(2)}`;
}

function duration(ms) {
  if (ms == null) return 'n/a';
  const v = Math.max(0, ms);
  if (v < 1000) return `${v} ms`;
  if (v < 60000) return `${(v / 1000).toFixed(1)} s`;
  return `${Math.floor(v / 60000)} min ${Math.round((v % 60000) / 1000)} s`;
}

function clock(ms) {
  return new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

const hopWord = (n) => (n === 1 ? '1 hop' : `${n} hops`);

// ---- orders ----

function chainHtml(order) {
  const parts = [];
  order.trace.forEach((node, i) => {
    const role = node.role ? ROLE_LABEL[node.role] ?? node.role : 'Unknown phone';
    const mark = i === 0 ? 'A' : String.fromCharCode(65 + Math.min(i, 25));
    parts.push(
      `<span class="hop" style="--i:${i}">` +
        `<span class="hop-mark" aria-hidden="true">${mark}</span>` +
        `<span class="hop-role">${esc(role)}</span>` +
        `<span class="hop-id">${esc(node.node_id)}</span>` +
      `</span>` +
      `<span class="wire" style="--i:${i}" aria-hidden="true"></span>`
    );
  });
  parts.push(
    `<span class="hop is-server" style="--i:${order.trace.length}">` +
      `<span class="hop-mark" aria-hidden="true">S</span>` +
      `<span class="hop-role">Server</span>` +
      `<span class="hop-id">&nbsp;</span>` +
    `</span>`
  );
  return `<span class="chain" role="img" aria-label="Path: ${esc(order.trace.map((n) => n.node_id).join(' to '))} to server">${parts.join('')}</span>`;
}

function detailHtml(order) {
  const names = new Map();
  for (const s of state.stores) for (const i of s.items) names.set(i.item_id, i.name);
  const name = (id) => names.get(id) ?? id;

  const asked = order.requested_items
    .map((i) => `<li><span>${i.qty} × ${esc(name(i.item_id))}</span><span>${rupees(i.unit_price_seen * i.qty)}</span></li>`)
    .join('');

  let outcome;
  if (order.status === 'REJECTED') {
    outcome = `<p class="reject-reason">${esc(REASON_LABEL[order.reason] ?? order.reason)}</p>`;
  } else {
    const finals = order.final_items
      .map((i) => `<li><span>${i.qty} × ${esc(i.name)}</span><span>${rupees(i.unit_price * i.qty)}</span></li>`)
      .join('');
    outcome = `<ol>${finals}<li><span>Delivery</span><span>${rupees(order.delivery_fee)}</span></li></ol>`;
  }

  const changes = order.changes.map((c) => {
    if (c.change === 'DROPPED') return `<li class="change-dropped"><span>${esc(name(c.item_id))} removed (out of stock)</span></li>`;
    if (c.change === 'SUBSTITUTED') return `<li class="change-substituted"><span>${esc(name(c.item_id))} replaced with ${esc(name(c.new_item_id))}</span></li>`;
    return `<li class="change-price"><span>${esc(name(c.item_id))} price ${rupees(c.old_price)} to ${rupees(c.new_price)}</span></li>`;
  }).join('');

  return (
    `<div class="order-detail">` +
      `<div><h3>Customer asked for (${rupees(order.total_seen)} with delivery)</h3><ol>${asked}</ol></div>` +
      `<div><h3>${order.status === 'REJECTED' ? 'Why it was rejected' : 'Server confirmed'}</h3>${outcome}</div>` +
      (changes ? `<div><h3>Changes made by the customer's rules</h3><ol>${changes}</ol></div>` : '') +
    `</div>`
  );
}

function orderHtml(order) {
  const cls = order.status === 'REJECTED' ? 'is-rejected' : order.status === 'CONFIRMED_MODIFIED' ? 'is-modified' : '';
  const fresh = freshOrders.has(order.order_id) ? 'is-new' : '';
  const open = openDetails.has(order.order_id);
  const ref = order.server_order_ref ?? 'No order ref';
  const total = order.status === 'REJECTED' ? 'Not placed' : rupees(order.final_total);
  return (
    `<li class="order ${cls} ${fresh}" style="--n:${order.trace.length + 1}">` +
      `<button class="order-main" type="button" data-order="${esc(order.order_id)}" aria-expanded="${open}">` +
        `<span class="order-id">` +
          `<span class="order-ref">${esc(ref)}</span><br>` +
          `<span class="order-store">${esc(order.store_name)}</span><br>` +
          `<span class="order-time">${clock(order.received_at)}</span>` +
        `</span>` +
        chainHtml(order) +
        `<span class="order-outcome">` +
          `<span class="status">${STATUS_LABEL[order.status]}</span><br>` +
          `<span class="order-total">${total}</span><br>` +
          `<span class="order-speed">${hopWord(order.hops)}, ${duration(order.latency_ms)}</span>` +
        `</span>` +
      `</button>` +
      (open ? detailHtml(order) : '') +
    `</li>`
  );
}

function renderOrders() {
  $('order-list').innerHTML = state.orders.map(orderHtml).join('');
  $('orders-empty').hidden = state.orders.length > 0;
  $('orders-count').textContent = state.orders.length ? `Newest first, showing ${state.orders.length}` : '';
  freshOrders.clear();
}

$('order-list').addEventListener('click', (e) => {
  const btn = e.target.closest('[data-order]');
  if (!btn) return;
  const id = btn.dataset.order;
  if (openDetails.has(id)) openDetails.delete(id);
  else openDetails.add(id);
  renderOrders();
  document.querySelector(`[data-order="${CSS.escape(id)}"]`)?.focus();
});

// ---- metrics ----

function renderMetrics() {
  const m = state.metrics;
  if (!m) return;
  const cells = [
    ['Orders confirmed', m.orders_confirmed, 'is-key'],
    ['Orders rejected', m.orders_rejected, ''],
    ['Duplicates dropped', m.duplicates_dropped, ''],
    ['Tampered packets rejected', m.tampered_rejected, m.tampered_rejected ? 'is-alarm' : ''],
    ['Tampered packets accepted', m.tampered_accepted, 'is-key'],
    ['Average hops', m.avg_hops == null ? 'n/a' : m.avg_hops.toFixed(1), ''],
    ['Average latency', duration(m.avg_latency_ms), ''],
    ['Median latency', duration(m.median_latency_ms), ''],
  ];
  $('metric-grid').innerHTML = cells
    .map(([label, value, cls]) => `<div class="metric ${cls}"><dd>${esc(value)}</dd><dt>${esc(label)}</dt></div>`)
    .join('');
  renderSuccessRate();
}

function renderSuccessRate() {
  const attempted = Number($('attempted').value);
  const out = $('success-rate');
  if (!state.metrics || !Number.isInteger(attempted) || attempted <= 0) {
    out.textContent = 'Enter a count to see the delivery success rate.';
    return;
  }
  const reached = state.metrics.orders_received;
  const pct = Math.min(100, (reached / attempted) * 100);
  out.innerHTML = `<strong>${pct.toFixed(0)}%</strong> delivered: ${reached} of ${attempted} orders reached the server.`;
}

$('attempted').value = localStorage.getItem('attempted') ?? '';
$('attempted').addEventListener('input', () => {
  localStorage.setItem('attempted', $('attempted').value);
  renderSuccessRate();
});

// ---- catalog control ----

function renderCatalog() {
  if (document.activeElement?.matches?.('.price-field input')) {
    catalogDirty = true; // do not pull a field out from under someone typing
    return;
  }
  catalogDirty = false;
  $('catalog').innerHTML = state.stores.map((s) => {
    const items = s.items.map((i) => (
      `<div class="item ${i.in_stock ? '' : 'is-out'}">` +
        `<span class="item-name">${esc(i.name)}</span>` +
        `<label class="price-field"><span aria-hidden="true">₹</span>` +
          `<input type="number" min="1" step="1" value="${i.price / 100}" data-price="${esc(i.item_id)}" aria-label="Price of ${esc(i.name)} in rupees">` +
        `</label>` +
        `<label class="switch">` +
          `<input type="checkbox" ${i.in_stock ? 'checked' : ''} data-stock="${esc(i.item_id)}">` +
          `<span class="switch-track"></span>` +
          `<span class="switch-text">${i.in_stock ? 'In stock' : 'Out of stock'}</span>` +
        `</label>` +
      `</div>`
    )).join('');
    return (
      `<div class="store">` +
        `<div class="store-head">` +
          `<h3 class="store-name">${esc(s.name)} <span class="store-meta">stock update ${s.stock_seq}</span></h3>` +
          `<label class="switch">` +
            `<input type="checkbox" ${s.open ? 'checked' : ''} data-open="${esc(s.store_id)}">` +
            `<span class="switch-track"></span>` +
            `<span class="switch-text">${s.open ? 'Open' : 'Closed'}</span>` +
          `</label>` +
        `</div>` +
        items +
      `</div>`
    );
  }).join('');
}

async function post(path, body) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body ?? {}),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.message ?? `Request failed (${res.status})`);
  }
  applyState(await res.json());
}

$('catalog').addEventListener('change', async (e) => {
  const el = e.target;
  try {
    if (el.dataset.stock) {
      await post(`/admin/api/items/${encodeURIComponent(el.dataset.stock)}/stock`, { in_stock: el.checked });
    } else if (el.dataset.open) {
      await post(`/admin/api/stores/${encodeURIComponent(el.dataset.open)}/open`, { open: el.checked });
    } else if (el.dataset.price) {
      const paise = Math.round(Number(el.value) * 100);
      if (!Number.isInteger(paise) || paise <= 0) throw new Error('Enter a price above zero.');
      el.blur();
      await post(`/admin/api/items/${encodeURIComponent(el.dataset.price)}/price`, { price: paise });
    }
  } catch (err) {
    alert(`Could not save the change. ${err.message}`);
    loadState();
  }
});

$('catalog').addEventListener('focusout', () => {
  if (catalogDirty) setTimeout(renderCatalog, 0);
});

$('reset-btn').addEventListener('click', async () => {
  if (!confirm('Reset demo data? This clears all orders and puts every item back in stock at its original price. Enrolled phones keep working.')) return;
  try {
    await post('/admin/api/reset');
  } catch (err) {
    alert(`Could not reset. ${err.message}`);
  }
});

// ---- state and live updates ----

function applyState(next) {
  state.stores = next.stores;
  state.orders = next.orders;
  state.metrics = next.metrics;
  renderOrders();
  renderMetrics();
  renderCatalog();
}

async function loadState() {
  const res = await fetch('/admin/api/state');
  applyState(await res.json());
}

function setLink(live) {
  $('link').classList.toggle('is-live', live);
  $('link-text').textContent = live ? 'Live' : 'Reconnecting';
}

function connect() {
  const ws = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/admin/ws`);
  ws.onopen = () => {
    setLink(true);
    loadState().catch(() => {}); // catch up on anything missed while disconnected
  };
  ws.onmessage = (msg) => {
    const { event, data } = JSON.parse(msg.data);
    if (event === 'order' && data.order) {
      state.orders = [data.order, ...state.orders.filter((o) => o.order_id !== data.order.order_id)].slice(0, 50);
      state.metrics = data.metrics;
      freshOrders.add(data.order.order_id);
      renderOrders();
      renderMetrics();
    } else if (event === 'packet') {
      state.metrics = data.metrics;
      renderMetrics();
    } else if (event === 'catalog') {
      applyState(data);
    }
  };
  ws.onclose = () => {
    setLink(false);
    setTimeout(connect, 1500);
  };
  ws.onerror = () => ws.close();
}

loadState().catch(() => {});
connect();
