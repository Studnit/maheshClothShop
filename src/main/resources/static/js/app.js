/* Global behaviour: sidebar, theme, CSRF-aware fetch, confirm dialogs, auto-submit filters, sw registration. */
(function () {
  'use strict';
  var doc = document;

  // ---- helpers shared by every page script ----
  window.MCS = window.MCS || {};
  MCS.csrf = function () {
    var t = doc.querySelector('meta[name="_csrf"]'), h = doc.querySelector('meta[name="_csrf_header"]');
    return t && h ? { header: h.content, token: t.content } : null;
  };
  MCS.lang = function () { var m = doc.querySelector('meta[name="app-lang"]'); return m ? m.content : 'en'; };
  MCS.json = function (url, options) {
    options = options || {};
    var headers = Object.assign({ 'Accept': 'application/json' }, options.headers || {});
    var c = MCS.csrf();
    if (c && options.method && options.method !== 'GET') { headers[c.header] = c.token; }
    if (options.body && typeof options.body !== 'string') {
      headers['Content-Type'] = 'application/json';
      options.body = JSON.stringify(options.body);
    }
    options.headers = headers;
    options.credentials = 'same-origin';
    return fetch(url, options).then(function (r) {
      return r.text().then(function (txt) {
        var data = null; try { data = txt ? JSON.parse(txt) : null; } catch (e) { /* not json */ }
        if (!r.ok) { var err = new Error((data && (data.detail || data.message || data.error)) || ('HTTP ' + r.status)); err.status = r.status; err.data = data; throw err; }
        return data;
      });
    });
  };
  // Indian digit grouping, matching the server: 1234567.5 -> 12,34,567.50
  MCS.inr = function (n) {
    n = Number(n || 0); var neg = n < 0; n = Math.abs(n);
    var s = n.toFixed(2), parts = s.split('.'), i = parts[0], last3 = i.slice(-3), rest = i.slice(0, -3);
    if (rest) { last3 = ',' + last3; rest = rest.replace(/\B(?=(\d{2})+(?!\d))/g, ','); }
    return (neg ? '-' : '') + '₹' + rest + last3 + '.' + parts[1];
  };
  MCS.esc = function (s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); };
  MCS.toast = function (message, kind) {
    var wrap = doc.querySelector('.toast-container');
    if (!wrap) { wrap = doc.createElement('div'); wrap.className = 'toast-container position-fixed bottom-0 end-0 p-3'; doc.body.appendChild(wrap); }
    var el = doc.createElement('div');
    el.className = 'toast align-items-center text-bg-' + (kind || 'dark') + ' border-0';
    el.setAttribute('role', 'status');
    var inner = doc.createElement('div'); inner.className = 'd-flex';
    var body = doc.createElement('div'); body.className = 'toast-body'; body.textContent = message;
    var btn = doc.createElement('button'); btn.type = 'button'; btn.className = 'btn-close btn-close-white me-2 m-auto'; btn.setAttribute('data-bs-dismiss', 'toast');
    inner.appendChild(body); inner.appendChild(btn); el.appendChild(inner); wrap.appendChild(el);
    var t = new bootstrap.Toast(el, { delay: 4500 }); t.show();
    el.addEventListener('hidden.bs.toast', function () { el.remove(); });
  };
  MCS.uuid = function () {
    if (window.crypto && crypto.randomUUID) { return crypto.randomUUID(); }
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
      var r = Math.random() * 16 | 0; return (c === 'x' ? r : (r & 3 | 8)).toString(16);
    });
  };

  // ---- sidebar (mobile) ----
  var sb = doc.getElementById('sidebar'), tg = doc.getElementById('sidebarToggle');
  if (sb && tg) {
    tg.addEventListener('click', function () { sb.classList.toggle('open'); });
    doc.addEventListener('click', function (e) {
      if (sb.classList.contains('open') && !sb.contains(e.target) && !tg.contains(e.target)) { sb.classList.remove('open'); }
    });
  }

  // ---- theme ----
  var th = doc.getElementById('themeToggle');
  function syncThemeIcon() {
    if (!th) { return; }
    var dark = doc.documentElement.getAttribute('data-theme') === 'dark';
    th.innerHTML = '<i class="bi ' + (dark ? 'bi-sun' : 'bi-moon-stars') + '"></i>';
  }
  if (th) {
    syncThemeIcon();
    th.addEventListener('click', function () {
      var dark = doc.documentElement.getAttribute('data-theme') === 'dark';
      if (dark) { doc.documentElement.removeAttribute('data-theme'); } else { doc.documentElement.setAttribute('data-theme', 'dark'); }
      try { localStorage.setItem('mcs-theme', dark ? 'light' : 'dark'); } catch (e) { /* ignore */ }
      syncThemeIcon();
      doc.dispatchEvent(new CustomEvent('mcs:theme'));
    });
  }

  // ---- data-confirm="message": ask before a destructive form submit / link ----
  doc.addEventListener('click', function (e) {
    var el = e.target.closest('[data-confirm]');
    if (el && !window.confirm(el.getAttribute('data-confirm'))) { e.preventDefault(); e.stopPropagation(); }
  }, true);

  // ---- data-autosubmit: filter selects submit their form on change ----
  doc.querySelectorAll('select[data-autosubmit], input[data-autosubmit]').forEach(function (el) {
    el.addEventListener('change', function () { if (el.form) { el.form.submit(); } });
  });

  // ---- prevent double submit on forms flagged data-once ----
  doc.querySelectorAll('form[data-once]').forEach(function (f) {
    f.addEventListener('submit', function () {
      f.querySelectorAll('button[type=submit]').forEach(function (b) { b.disabled = true; });
    });
  });

  // ---- copy to clipboard: data-copy="text" ----
  doc.addEventListener('click', function (e) {
    var el = e.target.closest('[data-copy]'); if (!el) { return; }
    var text = el.getAttribute('data-copy');
    (navigator.clipboard ? navigator.clipboard.writeText(text) : Promise.reject()).then(function () {
      MCS.toast(el.getAttribute('data-copied') || 'Copied', 'success');
    }, function () { window.prompt('Copy:', text); });
  });

  // ---- print button: data-print ----
  doc.addEventListener('click', function (e) { if (e.target.closest('[data-print]')) { window.print(); } });

  // ---- service worker: caches static files only, never pages or API calls ----
  if ('serviceWorker' in navigator && location.protocol === 'https:') {
    navigator.serviceWorker.register('/sw.js').catch(function () { /* optional */ });
  }
})();
