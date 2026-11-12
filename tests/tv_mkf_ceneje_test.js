#!/usr/bin/env node
// Cenejsi krog makeElementsFocusable (H54, TV 11. 11. 2026: ~38 ms na klic na 24ur.com, 4x upocasnitev):   node tests/tv_mkf_ceneje_test.js
// Element, ki ze ima tabindex, ne sme vec sprozati preverb isCarouselArrow/jeSkritZaFokus (closest). Vedenje je isto: skriti in
// puscice vrtiljaka tabindexa ne dobijo, ostali ga dobijo ob prvem krogu, skrit element ga dobi, ko skritost mine.
// Brez popravka preizkus pade (2 x closest na element v vsakem krogu).
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { ustvari } = require('./tv_lazni_dom');
const VIR = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
let n = 0;
function ok(pogoj, opis) { if (!pogoj) { console.log('  PADLO ' + opis); process.exit(1); } n++; console.log('  OK   ' + opis); }

const s = ustvari({});
let krog = null;
s.window.setInterval = (f, ms) => { if (ms === 4500) krog = f; return 0; };
let preverb = 0;
const stevec = (el) => { const iz = el.closest; el.closest = function (sel) { if (sel.indexOf('[inert]') !== -1 || sel.indexOf('.owl-nav') !== -1) preverb++; return iz.call(this, sel); }; return el; };
const navadni = [];
for (let i = 0; i < 40; i++) { const a = stevec(s.el('a', { attrs: { href: '/n' + i }, okvir: [100, 100 + i * 20, 200, 18], besedilo: 'n' + i })); navadni.push(a); s.body.dodaj(a); }
const skrit = stevec(s.el('a', { attrs: { href: '/s' }, okvir: [100, 1000, 200, 18], besedilo: 's' }));
const ovoj = s.el('div', { attrs: { 'aria-hidden': 'true' } }); ovoj.dodaj(skrit); s.body.dodaj(ovoj);
const puscica = stevec(s.el('a', { attrs: { href: '/p' }, razred: 'owl-next', okvir: [100, 1100, 20, 18], besedilo: '>' }));
s.body.dodaj(puscica);
s.nalozi(VIR);
ok(typeof krog === 'function', 'krog makeElementsFocusable je na casovniku 4500 ms');

preverb = 0; krog();
ok(navadni.every((a) => a.getAttribute('tabindex') === '0'), 'prvi krog: navadne povezave dobijo tabindex');
ok(skrit.getAttribute('tabindex') === null, 'skrit element (aria-hidden prednik) tabindexa ne dobi');
ok(puscica.getAttribute('tabindex') === null, 'puscica vrtiljaka tabindexa ne dobi');
ok(preverb > 0, 'prvi krog preveri elemente (' + preverb + ' preverb)');

preverb = 0; krog();
// Drugi krog: 40 elementov ima tabindex; preverjena sta lahko le skrit element in puscica (2 x closest vsak, najvec 4).
ok(preverb <= 4, 'drugi krog ne preverja elementov z tabindexom (preverb: ' + preverb + ', pricakovano <= 4)');

ovoj.attrs = {}; // skritost mine
krog();
ok(skrit.getAttribute('tabindex') === '0', 'ko skritost mine, element dobi tabindex');
ok(puscica.getAttribute('tabindex') === null, 'puscica ostane brez tabindexa');
const stran = s.el('a', { attrs: { href: '/x', tabindex: '-1' }, okvir: [100, 1200, 200, 18], besedilo: 'x' });
s.body.dodaj(stran); krog();
ok(stran.getAttribute('tabindex') === '-1', 'tabindex strani se ne prepise');
console.log('tv_mkf_ceneje_test: OK (' + n + ')');
