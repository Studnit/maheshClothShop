// Runs in <head> before first paint so the saved theme never flashes.
(function () {
  try {
    var t = localStorage.getItem('mcs-theme');
    if (!t && window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) { t = 'dark'; }
    if (t === 'dark') { document.documentElement.setAttribute('data-theme', 'dark'); }
  } catch (e) { /* storage blocked: stay on light */ }
})();
