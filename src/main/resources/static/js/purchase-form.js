/* Purchase entry: collects lines, asks the server to price them (landed cost included), saves a draft or receives stock. */
(function () {
  'use strict';
  var root = document.getElementById('pf'); if (!root) { return; }
  var $ = function (id) { return document.getElementById(id); };
  var taxOn = root.dataset.tax === 'true';
  var lines = [];
  try { lines = JSON.parse(root.dataset.lines || '[]'); } catch (e) { lines = []; }
  var quote = null, timer = null, seq = 0;

  function body(receiveNow) {
    return {
      id: root.dataset.id || null, supplierId: $('supplier').value || null, supplierInvoiceNo: $('invNo').value.trim(),
      purchaseDate: $('pdate').value, additionalCosts: parseFloat($('extra').value) || 0, notes: $('pnotes').value.trim() || null,
      receiveNow: !!receiveNow,
      lines: lines.map(function (l) { return { variantId: l.variantId, quantity: l.quantity, unitPrice: l.unitPrice, discount: l.discount || 0, taxRate: taxOn ? (l.taxRate || 0) : 0 }; })
    };
  }
  function err(m) { var e = $('pErr'); e.textContent = m || ''; e.classList.toggle('d-none', !m); }

  function render() {
    var tb = $('pLines'); tb.innerHTML = '';
    lines.forEach(function (l, i) {
      var tr = document.createElement('tr');
      var tot = quote ? quote.lineTotals[i] : '', landed = quote ? quote.landed[i] : '';
      tr.innerHTML = '<td>' + MCS.esc(l.label) + '<small class="d-block text-muted">' + MCS.esc(l.sku || '') + '</small></td>' +
        '<td><input class="form-control form-control-sm text-end" data-k="quantity" type="number" min="1" value="' + l.quantity + '"></td>' +
        '<td><input class="form-control form-control-sm text-end" data-k="unitPrice" inputmode="decimal" value="' + (l.unitPrice == null ? '' : l.unitPrice) + '"></td>' +
        '<td><input class="form-control form-control-sm text-end" data-k="discount" inputmode="decimal" value="' + (l.discount || '') + '"></td>' +
        (taxOn ? '<td><input class="form-control form-control-sm text-end" data-k="taxRate" inputmode="decimal" value="' + (l.taxRate == null ? '' : l.taxRate) + '"></td>' : '') +
        '<td class="text-end money">' + (tot === '' ? '' : MCS.inr(tot)) + '</td><td class="text-end money text-muted">' + (landed === '' ? '' : MCS.inr(landed)) + '</td>' +
        '<td class="text-end"><button type="button" class="btn btn-sm text-danger p-0"><i class="bi bi-x-circle"></i></button></td>';
      tr.querySelectorAll('input').forEach(function (inp) {
        inp.addEventListener('change', function () {
          var k = inp.dataset.k, v = k === 'quantity' ? parseInt(inp.value, 10) : parseFloat(inp.value);
          l[k] = isNaN(v) ? (k === 'quantity' ? 1 : 0) : v; requote();
        });
      });
      tr.querySelector('button').addEventListener('click', function () { lines.splice(i, 1); requote(); });
      tb.appendChild(tr);
    });
    $('qSub').textContent = MCS.inr(quote ? quote.subtotal : 0);
    $('qDisc').textContent = MCS.inr(quote ? quote.discount : 0);
    if ($('qTax')) { $('qTax').textContent = MCS.inr(quote ? quote.tax : 0); }
    $('qTotal').textContent = MCS.inr(quote ? quote.total : 0);
    var ok = lines.length > 0 && !!quote;
    $('saveReceive').disabled = !ok; $('saveDraft').disabled = !ok;
  }
  function requote() {
    clearTimeout(timer);
    if (!lines.length || lines.some(function (l) { return l.unitPrice == null || isNaN(l.unitPrice); })) { quote = null; render(); return; }
    timer = setTimeout(function () {
      var s = ++seq;
      MCS.json('/purchases/api/quote', { method: 'POST', body: body(false) }).then(function (q) { if (s === seq) { quote = q; err(''); render(); } })
        .catch(function (e) { quote = null; render(); err(e.message); });
    }, 200);
    render();
  }
  $('extra').addEventListener('change', requote);

  // item search
  var q = $('itemQ'), list = $('itemList'), t = null;
  q.addEventListener('input', function () {
    clearTimeout(t); var v = q.value.trim(); if (v.length < 2) { list.classList.add('d-none'); return; }
    t = setTimeout(function () {
      MCS.json('/inventory/api/variants?q=' + encodeURIComponent(v)).then(function (hits) {
        list.innerHTML = '';
        hits.forEach(function (h) {
          var b = document.createElement('button'); b.type = 'button'; b.className = 'list-group-item list-group-item-action d-flex justify-content-between';
          var label = h.productName + ' · ' + [h.colourName, h.sizeName].filter(Boolean).join(' / ');
          b.innerHTML = '<span>' + MCS.esc(label) + '<small class="d-block text-muted">' + MCS.esc(h.sku) + '</small></span><span class="badge text-bg-secondary align-self-center">' + h.available + '</span>';
          b.addEventListener('click', function () {
            if (lines.some(function (l) { return l.variantId === h.variantId; })) { MCS.toast(root.dataset.tAdded, 'warning'); return; }
            lines.push({ variantId: h.variantId, label: label, sku: h.sku, quantity: 1, unitPrice: h.purchaseCost ? Number(h.purchaseCost) : null, discount: 0, taxRate: Number(h.taxRate || 0) });
            q.value = ''; list.classList.add('d-none'); requote();
          });
          list.appendChild(b);
        });
        list.classList.toggle('d-none', !hits.length);
      });
    }, 200);
  });

  function save(receiveNow) {
    err('');
    ['saveReceive', 'saveDraft'].forEach(function (id) { $(id).disabled = true; });
    MCS.json('/purchases/api/save', { method: 'POST', body: body(receiveNow) }).then(function (r) {
      window.location.href = '/purchases/' + r.id;
    }).catch(function (e) { err(e.message); render(); });
  }
  $('saveReceive').addEventListener('click', function () { save(true); });
  $('saveDraft').addEventListener('click', function () { save(false); });
  requote();
})();
