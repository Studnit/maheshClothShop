/* Opens the print dialog when the page was opened with ?autoprint=1 (right after a sale). */
(function () {
  if (/[?&]autoprint=1/.test(location.search)) { window.addEventListener('load', function () { setTimeout(function () { window.print(); }, 250); }); }
  var b = document.getElementById('printNow'); if (b) { b.addEventListener('click', function () { window.print(); }); }
})();
