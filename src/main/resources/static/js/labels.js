/* Builds printable barcode labels from the variants listed in the page (Code 128, set B). */
(function () {
  'use strict';
  var sheet = document.getElementById('labelSheet'); if (!sheet || !window.Code128) { return; }
  var items = Array.prototype.slice.call(sheet.querySelectorAll('.label-item'));
  var copies = document.getElementById('copies');
  function render() {
    Array.prototype.slice.call(sheet.querySelectorAll('.label-box')).forEach(function (n) { n.remove(); });
    var n = Math.max(1, Math.min(50, parseInt(copies && copies.value, 10) || 1));
    items.forEach(function (it) {
      var code = it.dataset.code, svg;
      try { svg = Code128.svg(code, 40); } catch (e) { return; }   // codes with non-ASCII characters cannot be encoded
      for (var i = 0; i < n; i++) {
        var box = document.createElement('div'); box.className = 'label-box';
        box.innerHTML = '<div class="fw-bold">' + MCS.esc(sheet.dataset.shop) + '</div><div>' + MCS.esc(sheet.dataset.product) + '</div>' +
          '<div>' + MCS.esc([it.dataset.colour, it.dataset.size].filter(Boolean).join(' / ')) + '</div>' + svg + '<div>' + MCS.esc(code) + '</div>';
        sheet.appendChild(box);
      }
    });
  }
  if (copies) { copies.addEventListener('input', render); }
  render();
})();
