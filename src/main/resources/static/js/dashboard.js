/* Dashboard charts. Data arrives as JSON in #dash[data-json]. */
(function () {
  'use strict';
  var root = document.getElementById('dash');
  if (!root || !window.Chart) { return; }
  var d; try { d = JSON.parse(root.getAttribute('data-json')); } catch (e) { return; }
  var charts = [];
  var PALETTE = ['#4338ca', '#f59e0b', '#10b981', '#ef4444', '#0ea5e9', '#8b5cf6', '#ec4899', '#84cc16'];
  var lang = MCS.lang();
  var L = lang === 'mr'
    ? { sales: 'विक्री', profit: 'नफा', bills: 'बिले' }
    : { sales: 'Sales', profit: 'Profit', bills: 'Bills' };

  function css(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }
  function shortDate(iso) { var p = iso.split('-'); return p[2] + '-' + p[1]; }
  function compact(v) {
    v = Number(v);
    if (v >= 10000000) { return (v / 10000000).toFixed(1) + 'Cr'; }
    if (v >= 100000) { return (v / 100000).toFixed(1) + 'L'; }
    if (v >= 1000) { return (v / 1000).toFixed(1) + 'k'; }
    return String(v);
  }

  function build() {
    charts.forEach(function (c) { c.destroy(); });
    charts = [];
    var ink = css('--muted'), line = css('--line');
    Chart.defaults.font.family = getComputedStyle(document.body).fontFamily;
    Chart.defaults.color = ink;

    var trend = document.getElementById('trendChart');
    if (trend) {
      var g = trend.getContext('2d').createLinearGradient(0, 0, 0, 280);
      g.addColorStop(0, 'rgba(67,56,202,.35)'); g.addColorStop(1, 'rgba(67,56,202,0)');
      charts.push(new Chart(trend, {
        type: 'line',
        data: {
          labels: d.days.map(shortDate),
          datasets: [
            { label: L.sales, data: d.sales, borderColor: '#4338ca', backgroundColor: g, fill: true, tension: .35, pointRadius: 2, borderWidth: 2.5 },
            { label: L.profit, data: d.profit, borderColor: '#10b981', backgroundColor: 'transparent', tension: .35, pointRadius: 2, borderWidth: 2, borderDash: [5, 4] }
          ]
        },
        options: {
          maintainAspectRatio: false, interaction: { mode: 'index', intersect: false },
          plugins: { legend: { position: 'bottom', labels: { usePointStyle: true, boxWidth: 8 } },
            tooltip: { callbacks: { label: function (c) { return ' ' + c.dataset.label + ': ' + MCS.inr(c.parsed.y); } } } },
          scales: { x: { grid: { display: false } }, y: { beginAtZero: true, grid: { color: line }, ticks: { callback: compact } } }
        }
      }));
    }

    function doughnut(id, labels, values) {
      var el = document.getElementById(id); if (!el) { return; }
      if (!values.length) { el.parentNode.innerHTML = '<div class="empty"><i class="bi bi-pie-chart"></i></div>'; return; }
      charts.push(new Chart(el, {
        type: 'doughnut',
        data: { labels: labels, datasets: [{ data: values, backgroundColor: PALETTE, borderWidth: 0, hoverOffset: 6 }] },
        options: { maintainAspectRatio: false, cutout: '64%',
          plugins: { legend: { position: 'bottom', labels: { usePointStyle: true, boxWidth: 8 } },
            tooltip: { callbacks: { label: function (c) { return ' ' + c.label + ': ' + MCS.inr(c.parsed); } } } } }
      }));
    }
    doughnut('catChart', d.catNames, d.catValues);
    doughnut('payChart', d.payNames, d.payValues);

    var hours = document.getElementById('hourChart');
    if (hours) {
      var max = Math.max.apply(null, d.hourValues.concat([1]));
      charts.push(new Chart(hours, {
        type: 'bar',
        data: { labels: d.hourNames, datasets: [{ label: L.bills, data: d.hourValues, borderRadius: 6,
          backgroundColor: d.hourValues.map(function (v) { return 'rgba(67,56,202,' + (0.25 + 0.75 * v / max).toFixed(2) + ')'; }) }] },
        options: { maintainAspectRatio: false, plugins: { legend: { display: false } },
          scales: { x: { grid: { display: false } }, y: { beginAtZero: true, grid: { color: line }, ticks: { precision: 0 } } } }
      }));
    }
  }
  build();
  document.addEventListener('mcs:theme', build);
})();
