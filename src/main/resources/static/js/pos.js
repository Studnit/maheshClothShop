/* Billing screen. The server is the source of truth for prices and totals (POST /pos/api/quote);
   this script only collects the cart, shows what the server says, and submits it once. */
(function () {
  'use strict';
  var root = document.getElementById('posRoot');
  if (!root) { return; }
  var $ = function (id) { return document.getElementById(id); };
  var T = {};
  Object.keys(root.dataset).forEach(function (k) { if (k.indexOf('t') === 0 && k.length > 1 && k[1] === k[1].toUpperCase()) { T[k.charAt(1).toLowerCase() + k.slice(2)] = root.dataset[k]; } });
  var MAX_DISC = parseFloat(root.dataset.maxDiscount || '10');
  var COUNTRY = root.dataset.country || '91';
  var EXCH = root.dataset.exchangeId ? { id: root.dataset.exchangeId, left: Number(root.dataset.exchangeCredit) || 0 } : null;
  var HELD_KEY = 'mcs-held-bills';

  var methods = Array.prototype.map.call(document.querySelectorAll('#methodData li'), function (li) {
    return { code: li.dataset.code, name: li.dataset.name, ref: li.dataset.ref === 'true' };
  });

  var state = {
    cart: [],            // {variantId, label, sku, price, available, qty, discount}
    customer: null,      // {id, name, phone, due}
    invDiscount: 0,
    pays: [],            // {method, amount, reference, touched}
    quote: null,
    attemptId: null, attemptSig: null,
    busy: false
  };

  // ---------------------------------------------------------------- search
  var qEl = $('q'), results = $('results'), searchTimer = null, searchSeq = 0;

  var allHits = [];
  function runSearch(text) {
    var seq = ++searchSeq;
    var cat = $('fCat').value;
    MCS.json('/pos/api/search?q=' + encodeURIComponent(text || '') + (cat ? '&category=' + encodeURIComponent(cat) : '')).then(function (hits) {
      if (seq !== searchSeq) { return; }
      allHits = hits; fillFilter('fSize', 'sizeName'); fillFilter('fColour', 'colourName');
      renderResults();
    }).catch(function (e) { results.innerHTML = '<tr><td colspan="6" class="text-danger p-3">' + MCS.esc(e.message) + '</td></tr>'; });
  }

  // size / colour dropdowns are built from what the search returned, keeping the current choice
  function fillFilter(id, field) {
    var sel = $(id), cur = sel.value, seen = {};
    allHits.forEach(function (h) { if (h[field]) { seen[h[field]] = true; } });
    var first = sel.options[0];
    sel.innerHTML = ''; sel.appendChild(first);
    Object.keys(seen).sort().forEach(function (n) { var o = document.createElement('option'); o.value = n; o.textContent = n; sel.appendChild(o); });
    sel.value = seen[cur] ? cur : '';
  }

  function visibleHits() {
    var size = $('fSize').value, colour = $('fColour').value, avail = $('fAvail').checked, sort = $('fSort').value;
    var list = allHits.filter(function (h) {
      return (!size || h.sizeName === size) && (!colour || h.colourName === colour) && (!avail || h.available > 0);
    });
    if (sort === 'priceAsc') { list.sort(function (x, y) { return Number(x.price) - Number(y.price); }); }
    else if (sort === 'priceDesc') { list.sort(function (x, y) { return Number(y.price) - Number(x.price); }); }
    else if (sort === 'stock') { list.sort(function (x, y) { return y.available - x.available; }); }
    return list;
  }

  function renderResults() {
    var hits = visibleHits();
    $('resCount').textContent = hits.length;
    if (!hits.length) { results.innerHTML = '<tr><td colspan="6" class="text-center text-muted p-4"><i class="bi bi-search me-1"></i>' + MCS.esc(T.none) + '</td></tr>'; return; }
    results.innerHTML = '';
    hits.forEach(function (v) {
      var tr = document.createElement('tr'), out = v.available <= 0;
      var stkCls = out ? 'stk-out' : (v.available <= 5 ? 'stk-low' : 'stk-ok');
      tr.innerHTML =
        '<td><div class="d-flex align-items-center gap-2"><div class="pthumb">' + (v.imageFileId ? '<img loading="lazy" alt="" src="/files/' + v.imageFileId + '">' : '<i class="bi bi-handbag"></i>') + '</div>' +
        '<div><div class="fw-semibold">' + MCS.esc(v.productName) + '</div><div class="small text-muted">' + MCS.esc(v.sku) + '</div></div></div></td>' +
        '<td>' + (v.sizeName ? '<span class="size-chip">' + MCS.esc(v.sizeName) + '</span>' : '') + '</td>' +
        '<td>' + (v.colourName ? '<span class="cdot" style="background:' + MCS.esc(v.colourHex || '#ccc') + '"></span>' + MCS.esc(v.colourName) : '') + '</td>' +
        '<td class="text-end ' + stkCls + '">' + (out ? MCS.esc(T.out) : v.available) + '</td>' +
        '<td class="text-end money fw-semibold">' + MCS.inr(v.price) + '</td>' +
        '<td class="text-end"><button type="button" class="btn btn-sm btn-primary"' + (out ? ' disabled' : '') + '><i class="bi bi-plus-lg"></i> ' + MCS.esc(T.add) + '</button></td>';
      tr.querySelector('button').addEventListener('click', function () { addToCart(v); });
      results.appendChild(tr);
    });
  }
  ['fSize', 'fColour', 'fAvail', 'fSort'].forEach(function (id) { $(id).addEventListener('change', renderResults); });
  $('fCat').addEventListener('change', function () { runSearch(qEl.value.trim()); });

  qEl.addEventListener('input', function () { clearTimeout(searchTimer); searchTimer = setTimeout(function () { runSearch(qEl.value.trim()); }, 180); });
  qEl.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter') { return; }
    e.preventDefault();
    var code = qEl.value.trim();
    if (!code) { return; }
    // scanners "type" the code and press Enter: try an exact barcode / SKU match first
    MCS.json('/pos/api/code?code=' + encodeURIComponent(code)).then(function (v) {
      addToCart(v); qEl.value = ''; runSearch('');
    }).catch(function (err) { if (err.status === 404) { runSearch(code); } else { showError(err.message); } });
  });

  // ---------------------------------------------------------------- cart
  function addToCart(v) {
    if (v.available <= 0) { showError(T.nostock); return; }
    var line = state.cart.filter(function (l) { return l.variantId === v.variantId; })[0];
    if (line) {
      if (line.qty + 1 > v.available) { showError(T.nostock); return; }
      line.qty += 1;
    } else {
      state.cart.push({
        variantId: v.variantId, label: v.productName + ([v.colourName, v.sizeName].filter(Boolean).length ? ' · ' + [v.colourName, v.sizeName].filter(Boolean).join(' / ') : ''),
        sku: v.sku, img: v.imageFileId, price: Number(v.price), available: v.available, qty: 1, discount: 0
      });
    }
    clearError(); changed(); beep();
  }

  function beep() { try { var c = new (window.AudioContext || window.webkitAudioContext)(), o = c.createOscillator(); o.frequency.value = 880; o.connect(c.destination); o.start(); setTimeout(function () { o.stop(); c.close(); }, 60); } catch (e) { /* silent */ } }

  function renderCart() {
    var box = $('lines');
    $('cartCount').textContent = state.cart.reduce(function (s, l) { return s + l.qty; }, 0);
    if (!state.cart.length) { box.innerHTML = '<div class="empty"><i class="bi bi-cart"></i>' + MCS.esc(T.empty) + '</div>'; return; }
    box.innerHTML = '';
    state.cart.forEach(function (l, idx) {
      var row = document.createElement('div');
      row.className = 'bill-line';
      var lineTotal = state.quote && state.quote.lineTotals ? state.quote.lineTotals[idx] : l.price * l.qty - l.discount;
      row.innerHTML =
        '<div class="d-flex align-items-center gap-2"><div class="pthumb">' + (l.img ? '<img alt="" src="/files/' + l.img + '">' : '<i class="bi bi-handbag"></i>') + '</div>' +
        '<div><div class="fw-semibold small">' + MCS.esc(l.label) + '</div><div class="small text-muted">' + MCS.inr(l.price) + '</div></div></div>' +
        '<div class="qty"><button type="button" data-a="dec">−</button><input inputmode="numeric" value="' + l.qty + '" aria-label="qty"><button type="button" data-a="inc">+</button></div>' +
        '<input class="form-control form-control-sm text-end fw-semibold" inputmode="decimal" data-a="disc" value="' + Number(lineTotal).toFixed(2) + '" aria-label="amount">' +
        '<div class="text-end"><button type="button" class="btn btn-sm text-danger p-0" data-a="del"><i class="bi bi-trash"></i></button></div>';
      row.querySelector('[data-a=dec]').addEventListener('click', function () { l.qty = Math.max(1, l.qty - 1); changed(); });
      row.querySelector('[data-a=inc]').addEventListener('click', function () { if (l.qty + 1 > l.available) { showError(T.nostock); return; } l.qty += 1; changed(); });
      row.querySelector('.qty input').addEventListener('change', function (e) {
        var n = parseInt(e.target.value, 10); if (!(n > 0)) { n = 1; } if (n > l.available) { showError(T.nostock); n = l.available; } l.qty = n; changed();
      });
      row.querySelector('[data-a=disc]').addEventListener('change', function (e) { var gross = l.price * l.qty, amt = parseFloat(e.target.value); l.discount = isNaN(amt) ? 0 : Math.min(gross, Math.max(0, Math.round((gross - amt) * 100) / 100)); changed(); });
      row.querySelector('[data-a=del]').addEventListener('click', function () { state.cart.splice(idx, 1); changed(); });
      box.appendChild(row);
    });
  }

  // ---------------------------------------------------------------- quote from the server
  var quoteTimer = null, quoteSeq = 0;
  function changed() {
    state.attemptId = null;
    renderCart();
    clearTimeout(quoteTimer);
    if (!state.cart.length) { state.quote = null; renderTotals(); return; }
    quoteTimer = setTimeout(requestQuote, 120);
  }
  function payloadLines() { return state.cart.map(function (l) { return { variantId: l.variantId, quantity: l.qty, discount: l.discount || 0 }; }); }
  function requestQuote() {
    var seq = ++quoteSeq;
    MCS.json('/pos/api/quote', { method: 'POST', body: { lines: payloadLines(), invoiceDiscount: state.invDiscount || 0 } }).then(function (q) {
      if (seq !== quoteSeq) { return; }
      state.quote = q; clearError(); renderCart(); renderTotals();
    }).catch(function (e) { state.quote = null; renderTotals(); showError(e.message); });
  }

  function renderTotals() {
    var q = state.quote;
    $('tSub').textContent = MCS.inr(q ? q.subtotal : 0);
    $('tDisc').textContent = MCS.inr(q ? Number(q.lineDiscount) + Number(q.invoiceDiscount) : 0);
    if ($('tTax')) { $('tTax').textContent = MCS.inr(q ? q.tax : 0); }
    if ($('tRound')) { $('tRound').textContent = MCS.inr(q ? q.roundOff : 0); }
    $('tTotal').textContent = MCS.inr(q ? q.total : 0);
    if ($('tExch')) { $('tExch').textContent = '− ' + MCS.inr(credit()); }
    autoPay();
    renderPays();
  }

  $('invDisc').addEventListener('change', function (e) { state.invDiscount = Math.max(0, parseFloat(e.target.value) || 0); e.target.value = state.invDiscount; changed(); });
  document.querySelectorAll('[data-pct]').forEach(function (b) {
    b.addEventListener('click', function () {
      if (!state.quote) { return; }
      var pct = parseFloat(b.dataset.pct);
      var gross = Number(state.quote.subtotal) - Number(state.quote.lineDiscount);
      state.invDiscount = Math.round(gross * pct) / 100;
      $('invDisc').value = state.invDiscount; changed();
    });
  });

  // ---------------------------------------------------------------- payments
  function total() { return state.quote ? Number(state.quote.total) : 0; }
  // exchange credit from an earlier return is used first; the customer pays only what is left
  function credit() { return EXCH ? Math.round(Math.min(EXCH.left, total()) * 100) / 100 : 0; }
  function payable() { return Math.max(0, total() - credit()); }
  function paidSum() { return state.pays.reduce(function (s, p) { return s + (Number(p.amount) || 0); }, 0); }
  function ensurePay() { if (!state.pays.length) { state.pays.push({ method: methods.length ? methods[0].code : 'CASH', amount: 0, reference: '', touched: false }); } }
  function autoPay() {
    ensurePay();
    // while the cashier has not typed an amount, a single payment row just follows the total
    if (state.pays.length === 1 && !state.pays[0].touched) { state.pays[0].amount = Math.round(payable() * 100) / 100; }
  }
  function methodOptions(sel) { return methods.map(function (m) { return '<option value="' + m.code + '"' + (m.code === sel ? ' selected' : '') + '>' + MCS.esc(m.name) + '</option>'; }).join(''); }
  function renderPays() {
    var box = $('payRows'); box.innerHTML = '';
    state.pays.forEach(function (p, idx) {
      var def = methods.filter(function (m) { return m.code === p.method; })[0];
      var row = document.createElement('div');
      row.innerHTML = '<div class="pay-row"><select class="form-select form-select-sm">' + methodOptions(p.method) + '</select>' +
        '<input class="form-control form-control-sm text-end" inputmode="decimal" value="' + (p.amount || '') + '">' +
        (state.pays.length > 1 ? '<button type="button" class="btn btn-sm text-danger p-0"><i class="bi bi-x-circle"></i></button>' : '<span></span>') + '</div>' +
        (def && def.ref ? '<input class="form-control form-control-sm mb-2" placeholder="' + MCS.esc(T.ref) + '" value="' + MCS.esc(p.reference || '') + '">' : '');
      var sel = row.querySelector('select'), amt = row.querySelector('.pay-row input'), del = row.querySelector('button'), ref = row.querySelectorAll('input')[1];
      sel.addEventListener('change', function () { p.method = sel.value; renderPays(); });
      amt.addEventListener('change', function () { p.amount = Math.max(0, parseFloat(amt.value) || 0); p.touched = true; renderPays(); });
      if (ref) { ref.addEventListener('input', function () { p.reference = ref.value; }); }
      if (del) { del.addEventListener('click', function () { state.pays.splice(idx, 1); if (state.pays.length === 1) { state.pays[0].touched = false; autoPay(); } renderPays(); }); }
      box.appendChild(row);
    });
    var pm = $('payMethods'); pm.innerHTML = '';
    methods.forEach(function (m) {
      var b = document.createElement('button'); b.type = 'button'; b.textContent = m.name;
      if (state.pays.length && state.pays[0].method === m.code) { b.className = 'active'; }
      b.addEventListener('click', function () { if (state.pays.length) { state.pays[0].method = m.code; renderPays(); } });
      pm.appendChild(b);
    });
    var diff = payable() - paidSum(), st = $('payStatus'), lab = $('payStatusLabel');
    if (Math.abs(diff) < 0.005) { lab.textContent = ''; st.textContent = '✓'; st.className = 'money text-success'; }
    else if (diff > 0) { lab.textContent = state.cart.length ? T.short + ' / ' + T.due : ''; st.textContent = MCS.inr(diff); st.className = 'money text-warning'; }
    else { lab.textContent = T.paidmore; st.textContent = MCS.inr(-diff); st.className = 'money text-danger'; }
    var cashPay = state.pays.filter(function (p) { return p.method === 'CASH'; })[0];
    $('cashHelper').classList.toggle('d-none', !cashPay);
    updateChange();
    $('payBtn').disabled = state.busy || !state.cart.length || !state.quote || diff < -0.005;
  }
  function updateChange() {
    var cashPay = state.pays.filter(function (p) { return p.method === 'CASH'; })[0];
    var got = parseFloat($('cashIn').value) || 0;
    $('changeOut').textContent = MCS.inr(cashPay ? Math.max(0, got - Number(cashPay.amount || 0)) : 0);
  }
  $('cashIn').addEventListener('input', updateChange);
  $('addPay').addEventListener('click', function () {
    ensurePay();
    var rest = Math.max(0, total() - paidSum());
    state.pays.forEach(function (p) { p.touched = true; });
    var m = methods.filter(function (x) { return !state.pays.some(function (p) { return p.method === x.code; }); })[0] || methods[0];
    state.pays.push({ method: m ? m.code : 'CASH', amount: rest, reference: '', touched: true });
    renderPays();
  });

  // ---------------------------------------------------------------- customer
  var custQ = $('custQ'), custList = $('custList'), custTimer = null;
  function setCustomer(c) {
    state.customer = c;
    $('custPicked').classList.toggle('d-none', !c); $('custPicked').classList.toggle('d-flex', !!c);
    $('custSearch').classList.toggle('d-none', !!c);
    if (c) {
      $('custName').textContent = c.name;
      $('custInfo').textContent = (c.phone || '') + (Number(c.due) > 0 ? ' · ' + T.due + ' ' + MCS.inr(c.due) : '');
    }
    custList.classList.add('d-none');
  }
  custQ.addEventListener('input', function () {
    clearTimeout(custTimer);
    var t = custQ.value.trim();
    if (t.length < 2) { custList.classList.add('d-none'); return; }
    custTimer = setTimeout(function () {
      MCS.json('/pos/api/customers?q=' + encodeURIComponent(t)).then(function (list) {
        custList.innerHTML = '';
        list.forEach(function (c) {
          var a = document.createElement('button'); a.type = 'button'; a.className = 'list-group-item list-group-item-action';
          a.innerHTML = '<div class="fw-semibold">' + MCS.esc(c.name) + '</div><div class="small text-muted">' + MCS.esc(c.phone || '') + (Number(c.due) > 0 ? ' · ' + MCS.inr(c.due) : '') + '</div>';
          a.addEventListener('click', function () { setCustomer(c); });
          custList.appendChild(a);
        });
        custList.classList.toggle('d-none', !list.length);
      }).catch(function () { custList.classList.add('d-none'); });
    }, 200);
  });
  $('custRemove').addEventListener('click', function () { setCustomer(null); });
  if ($('custAdd')) {
    $('custAdd').addEventListener('click', function () {
      var name = ($('walkName').value || custQ.value).trim(), phone = $('walkPhone').value.trim();
      if (!name) { $('walkName').focus(); return; }
      MCS.json('/pos/api/customers', { method: 'POST', body: { name: name, phone: phone } }).then(function (c) {
        setCustomer(c); MCS.toast(T.added, 'success');
      }).catch(function (e) { showError(e.message); });
    });
  }

  // ---------------------------------------------------------------- hold / resume
  function heldList() { try { return JSON.parse(localStorage.getItem(HELD_KEY) || '[]'); } catch (e) { return []; } }
  function saveHeld(list) { try { localStorage.setItem(HELD_KEY, JSON.stringify(list)); } catch (e) { MCS.toast('Browser storage is blocked', 'warning'); } renderHeld(); }
  function renderHeld() {
    var list = heldList(), ul = $('heldList');
    $('heldCount').textContent = list.length;
    ul.innerHTML = '';
    if (!list.length) { ul.innerHTML = '<li class="dropdown-item-text text-muted small">—</li>'; return; }
    list.forEach(function (h, i) {
      var li = document.createElement('li');
      var a = document.createElement('button'); a.type = 'button'; a.className = 'dropdown-item d-flex justify-content-between gap-3';
      a.innerHTML = '<span>' + MCS.esc(h.customer ? h.customer.name : T.walkin) + ' · ' + h.cart.length + '</span><small class="text-muted">' + new Date(h.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) + '</small>';
      a.addEventListener('click', function () {
        state.cart = h.cart; state.invDiscount = h.invDiscount || 0; $('invDisc').value = state.invDiscount;
        state.pays = []; setCustomer(h.customer);
        var l = heldList(); l.splice(i, 1); saveHeld(l); changed(); MCS.toast(T.resumed, 'info');
      });
      li.appendChild(a); ul.appendChild(li);
    });
  }
  $('holdBtn').addEventListener('click', function () {
    if (!state.cart.length) { return; }
    var l = heldList(); l.push({ at: Date.now(), cart: state.cart, customer: state.customer, invDiscount: state.invDiscount });
    saveHeld(l); resetAll(); MCS.toast(T.held, 'success');
  });

  // ---------------------------------------------------------------- checkout
  function showError(m) { var e = $('posError'); e.textContent = m; e.classList.remove('d-none'); }
  function clearError() { $('posError').classList.add('d-none'); }

  function resetAll() {
    state.cart = []; state.customer = null; state.invDiscount = 0; state.pays = []; state.quote = null; state.attemptId = null;
    $('invDisc').value = 0; $('notes').value = ''; $('walkName').value = ''; $('walkPhone').value = ''; $('cashIn').value = ''; custQ.value = '';
    setCustomer(null); clearError(); changed(); qEl.focus(); runSearch('');
  }
  function askClear() { if (state.cart.length && window.confirm(T.clear)) { resetAll(); } }
  $('clearBtn').addEventListener('click', askClear);
  $('clearBtn2').addEventListener('click', askClear);

  function waPhone() {
    var raw = state.customer ? state.customer.phone : $('walkPhone').value;
    return (raw || '').replace(/\D/g, '');
  }

  function checkout() {
    if (state.busy || $('payBtn').disabled) { return; }
    var consentEl = $('waConsent');
    var wantWa = !!(consentEl && consentEl.checked && waPhone().length >= 10);
    var body = {
      customerId: state.customer ? state.customer.id : null,
      walkInName: state.customer ? null : $('walkName').value.trim(),
      walkInPhone: state.customer ? null : $('walkPhone').value.trim(),
      lines: payloadLines(), invoiceDiscount: state.invDiscount || 0,
      payments: state.pays.filter(function (p) { return Number(p.amount) > 0; }).map(function (p) { return { method: p.method, amount: Number(p.amount), reference: p.reference || null }; }),
      notes: $('notes').value.trim() || null, sendWhatsApp: wantWa,
      exchangeReturnId: EXCH && credit() > 0 ? EXCH.id : null, exchangeCredit: EXCH && credit() > 0 ? credit() : null
    };
    // the same cart + payments keep the same request id, so a retry after a network error can never bill twice
    var sig = JSON.stringify(body);
    if (!state.attemptId || state.attemptSig !== sig) { state.attemptId = MCS.uuid(); state.attemptSig = sig; }
    body.clientRequestId = state.attemptId;
    state.busy = true; $('payBtn').disabled = true; clearError();
    MCS.json('/pos/api/checkout', { method: 'POST', body: body }).then(function (r) {
      state.busy = false; showDone(r, wantWa);
    }).catch(function (e) { state.busy = false; renderPays(); showError(e.message); });
  }
  $('payBtn').addEventListener('click', checkout);

  var doneModal = null;
  function showDone(r, wantWa) {
    $('doneNo').textContent = r.invoiceNo;
    $('doneTotal').textContent = MCS.inr(r.total);
    var due = $('doneDue'); due.classList.toggle('d-none', !(Number(r.balance) > 0)); due.textContent = Number(r.balance) > 0 ? T.due + ' ' + MCS.inr(r.balance) : '';
    $('donePrint').href = '/sales/' + r.invoiceId + '/print?autoprint=1';
    $('doneThermal').href = '/sales/' + r.invoiceId + '/receipt?autoprint=1';
    $('doneView').href = '/sales/' + r.invoiceId;
    $('doneWaAuto').classList.toggle('d-none', !(wantWa && r.autoSend));
    var wa = $('doneWa');
    // when the Cloud API is not auto-sending, offer the one-tap WhatsApp share (works with the owner's WhatsApp on any phone)
    var manual = r.canShare && !(wantWa && r.autoSend);
    wa.classList.toggle('d-none', !manual);
    wa.href = '/sales/' + r.invoiceId + '/whatsapp';
    if (!doneModal) { doneModal = new bootstrap.Modal($('doneModal')); }
    doneModal.show();
    resetAllSilently();
  }
  function resetAllSilently() {
    state.cart = []; state.customer = null; state.invDiscount = 0; state.pays = []; state.quote = null; state.attemptId = null;
    $('invDisc').value = 0; $('notes').value = ''; $('walkName').value = ''; $('walkPhone').value = ''; $('cashIn').value = ''; custQ.value = '';
    setCustomer(null); renderCart(); renderTotals();
  }
  $('doneNew').addEventListener('click', function () { doneModal.hide(); qEl.focus(); runSearch(''); });

  // ---------------------------------------------------------------- keyboard
  document.addEventListener('keydown', function (e) {
    if (e.key === 'F2') { e.preventDefault(); qEl.focus(); qEl.select(); }
    else if (e.key === 'F9') { e.preventDefault(); checkout(); }
  });

  // ---------------------------------------------------------------- start
  renderCart(); renderTotals(); renderHeld(); runSearch('');
})();
