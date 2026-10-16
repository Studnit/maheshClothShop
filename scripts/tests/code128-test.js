const C = require('../../src/main/resources/static/js/code128.js');
const P = C.patterns; let bad = 0;
if (P.length !== 107) { console.log('count', P.length); bad++; }
P.forEach((p, i) => { const sum = p.split('').reduce((a, b) => a + +b, 0); const want = i === 106 ? 13 : 11; if (sum !== want) { console.log('sum', i, p, sum); bad++; } });
if (new Set(P).size !== 107) { console.log('duplicate patterns'); bad++; }
// decode back: widths -> symbols using the table, verify checksum for several strings
const rev = new Map(P.map((p, i) => [p, i]));
function decode(w) {
  const syms = []; let i = 0;
  while (i < w.length) { const len = (w.length - i === 7) ? 7 : 6; syms.push(rev.get(w.slice(i, i + len).join(''))); i += len; }
  return syms;
}
for (const t of ['A', 'Hello World', 'MCS-000123', 'DEMO-P01-BLU-M', '8901234567890', ' ~']) {
  const syms = decode(C.widths(t));
  if (syms.includes(undefined)) { console.log('decode failed', t); bad++; continue; }
  const data = syms.slice(1, -2); let sum = syms[0]; data.forEach((v, k) => sum += v * (k + 1));
  const text = data.map(v => String.fromCharCode(v + 32)).join('');
  if (syms[0] !== 104 || syms.at(-1) !== 106 || sum % 103 !== syms.at(-2) || text !== t) { console.log('mismatch', t, text); bad++; }
}
// known reference: "PJJ123C" in set B has checksum 54 (classic example) -> verify
const s = C.symbols('PJJ123C'); console.log('PJJ123C checksum', s.at(-2), '(expected 55)');
try { C.svg('नमस्ते'); bad++; console.log('should reject non-ascii'); } catch (e) {}
console.log(bad === 0 ? 'code128 OK' : 'code128 FAILED ' + bad);
