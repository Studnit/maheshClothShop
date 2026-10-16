/* Code 128 (set B) barcode encoder -> array of module widths, plus an SVG renderer. No dependencies. */
(function (root) {
  'use strict';
  var P = ['212222','222122','222221','121223','121322','131222','122213','122312','132212','221213','221312','231212','112232','122132','122231','113222','123122','123221','223211','221132','221231','213212','223112','312131','311222','321122','321221','312212','322112','322211','212123','212321','232121','111323','131123','131321','112313','132113','132311','211313','231113','231311','112133','112331','132131','113123','113321','133121','313121','211331','231131','213113','213311','213131','311123','311321','331121','312113','312311','332111','314111','221411','431111','111224','111422','121124','121421','141122','141221','112214','112412','122114','122411','142112','142211','241211','221114','413111','241112','134111','111242','121142','121241','114212','124112','124211','411212','421112','421211','212141','214121','412121','111143','111341','131141','114113','114311','411113','411311','113141','114131','311141','411131','211412','211214','211232','2331112'];
  var START_B = 104, STOP = 106;

  /** Returns the symbol values (start, data..., checksum, stop) for printable ASCII text. */
  function symbols(text) {
    var out = [START_B], sum = START_B;
    for (var i = 0; i < text.length; i++) {
      var v = text.charCodeAt(i) - 32;
      if (v < 0 || v > 94) { throw new Error('Character not supported in Code 128 B: ' + text.charAt(i)); }
      out.push(v); sum += v * (i + 1);
    }
    out.push(sum % 103); out.push(STOP);
    return out;
  }

  /** Alternating bar/space widths (in modules), starting with a bar. */
  function widths(text) {
    var w = [];
    symbols(text).forEach(function (s) { P[s].split('').forEach(function (d) { w.push(parseInt(d, 10)); }); });
    return w;
  }

  /** SVG markup; quiet zones of 10 modules each side. */
  function svg(text, height) {
    var w = widths(text), x = 10, bars = '', total = 0;
    w.forEach(function (n, i) { if (i % 2 === 0) { bars += '<rect x="' + x + '" y="0" width="' + n + '" height="100"/>'; } x += n; });
    total = x + 10;
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + total + ' 100" preserveAspectRatio="none" height="' + (height || 40) +
      '" role="img" aria-label="' + text.replace(/[^\w -]/g, '') + '" fill="#000">' + bars + '</svg>';
  }

  var api = { symbols: symbols, widths: widths, svg: svg, patterns: P };
  if (typeof module !== 'undefined' && module.exports) { module.exports = api; } else { root.Code128 = api; }
})(typeof window !== 'undefined' ? window : globalThis);
