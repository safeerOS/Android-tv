#!/usr/bin/env node
// Vedenjski preizkus (apk-24, neodvisni pregled N1): predgrevanje ciljev ne sme pustiti obroca na elementu, ki ni vec cilj.
//   node tests/tv_predgrevanje_obroc_test.js [pot do tv_spatial.js]
// Predgrevanje zbere cilje brez ciscenja obroca; naslednja tipka zadene predpomnilnik. Brez popravka (kandPredgret) obroc
// ostane na elementu zunaj dosega. Preizkus tece v lazni DOM (tests/tv_lazni_dom.js), ne v pravem WebView; getCandidates
// je v zaprtju skripta, zato mu preizkus doda kljuko (kot tv_lazni_dom.nalozi za izberi) - samo v preizkusu, ne v aplikaciji.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { ustvari } = require('./tv_lazni_dom');

const VIR0 = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
const k = VIR0.lastIndexOf('})();');
assert.ok(k > 0, 'skript se konca z })();');
const VIR = VIR0.slice(0, k) + ';try { Object.defineProperty(window, "__kand", { value: function () { return getCandidates(); } }); } catch (_) {}\n' + VIR0.slice(k);
let stevilo = 0;
function preveri(opis, pricakovano, dobljeno) {
    try { assert.deepStrictEqual(dobljeno, pricakovano, opis); stevilo++; console.log('  OK   ' + opis); }
    catch (_) { console.log('  PADLO ' + opis + ' (pricakovano ' + JSON.stringify(pricakovano) + ', dobljeno ' + JSON.stringify(dobljeno) + ')'); process.exit(1); }
}
const ZETON = 'd'.repeat(48);
const imeIzbranega = (s) => s.document.querySelectorAll('.safeer-active-card').map((e) => (e.innerText || '').trim());

// Izbran je B; potem B ni vec cilj (pade zunaj dosega), A ostane. predgrevanje: ali se zbiranje zgodi v predgrevanju (brez tipke).
function scenarij(predgrevanje, bOstaneCilj) {
    const s = ustvari({ zeton: ZETON });
    let zamaknjeno = null;
    s.window.setTimeout = (f, ms) => { if (ms === 140) zamaknjeno = f; return 1; };   // samo casovnik predgrevanja (PREDGREVANJE_ZAMIK_MS)
    const a = s.el('a', { attrs: { href: '/a' }, okvir: [100, 300, 400, 60], besedilo: 'A' });
    const b = s.el('a', { attrs: { href: '/b' }, okvir: [100, 500, 400, 60], besedilo: 'B' });
    s.body.dodaj(a, b);
    s.nalozi(VIR);
    s.izberi(a);
    s.smer('DOWN');                                            // tipka: zabelezi cas, izbira B
    preveri('izhodisce: izbran B', ['B'], imeIzbranega(s));
    if (!bOstaneCilj) b.okvir = [100, 4000, 400, 60];          // izven dosega (stran ni drsljiva)
    else a.okvir = [100, 4000, 400, 60];                       // kontrola: izven dosega je A, B ostane cilj
    s.spremeni([{ type: 'attributes', attributeName: 'style', target: b, oldValue: '' }]);
    s.ura.t += 300;                                            // cas v TV tece (pri < 80 ms bi zbiranje zadelo se stari predpomnilnik)
    if (predgrevanje) { preveri('predgrevanje je nacrtano', true, typeof zamaknjeno === 'function'); zamaknjeno(); }
    return s;
}

// 1. Po predgrevanju in zadetku predpomnilnika obroc ne ostane na elementu, ki ni vec cilj.
{
    const s = scenarij(true, false);
    preveri('predgrevanje samo zbere cilje: obroc je se na B', ['B'], imeIzbranega(s));
    const cilji = Array.from(s.window.__kand(), (e) => e.innerText.trim());
    preveri('tipka dobi samo A kot cilj', ['A'], cilji);
    preveri('po zadetku predpomnilnika B nima obroca', [], imeIzbranega(s));
}
// 2. Kontrola: brez predgrevanja (sveze zbiranje) je vedenje isto.
{
    const s = scenarij(false, false);
    s.window.__kand();
    preveri('brez predgrevanja B nima obroca', [], imeIzbranega(s));
}
// 3. Izbrani element, ki je se cilj, obroca po predgrevanju NE izgubi (popravek ne cisti po nepotrebnem).
{
    const s = scenarij(true, true);
    const cilji = Array.from(s.window.__kand(), (e) => e.innerText.trim());
    preveri('tipka dobi samo B kot cilj', ['B'], cilji);
    preveri('B je se cilj: obroc ostane', ['B'], imeIzbranega(s));
}
console.log('tv_predgrevanje_obroc_test: OK (' + stevilo + ')');
