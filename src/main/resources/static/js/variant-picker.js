/* Typeahead that fills a hidden variantId from /inventory/api/variants. Also toggles the cost field by adjustment type. */
(function () {
  'use strict';
  var q = document.getElementById('variantQ'), id = document.getElementById('variantId'), list = document.getElementById('variantList'), info = document.getElementById('variantStock');
  if (!q) { return; }
  var timer = null;
  q.addEventListener('input', function () {
    id.value = ''; clearTimeout(timer);
    var t = q.value.trim(); if (t.length < 2) { list.classList.add('d-none'); return; }
    timer = setTimeout(function () {
      MCS.json('/inventory/api/variants?q=' + encodeURIComponent(t)).then(function (hits) {
        list.innerHTML = '';
        hits.forEach(function (h) {
          var b = document.createElement('button'); b.type = 'button'; b.className = 'list-group-item list-group-item-action d-flex justify-content-between';
          var label = h.productName + ' · ' + [h.colourName, h.sizeName].filter(Boolean).join(' / ');
          b.innerHTML = '<span>' + MCS.esc(label) + '<small class="d-block text-muted">' + MCS.esc(h.sku) + '</small></span><span class="badge text-bg-secondary align-self-center">' + h.available + '</span>';
          b.addEventListener('click', function () { id.value = h.variantId; q.value = label; info.textContent = h.available; list.classList.add('d-none'); });
          list.appendChild(b);
        });
        list.classList.toggle('d-none', !hits.length);
      });
    }, 200);
  });
  var type = document.getElementById('adjType'), cost = document.getElementById('costBox');
  function sync() { if (type && cost) { var needs = type.value === 'OPENING' || type.value === 'ADJUST_IN'; cost.classList.toggle('d-none', !needs); } }
  if (type) { type.addEventListener('change', sync); sync(); }
})();
