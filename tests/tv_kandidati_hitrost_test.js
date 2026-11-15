#!/usr/bin/env node
// SAFEER_SPATIAL_HITROST (TV 15. 11. 2026, 24ur.com: iskanje ciljev ob tipki 102-306 ms, H54). Umetna stran z ~1900 elementi
// (tests/tv_velika_stran.js) in 24 pritiskov DOL z drsenjem + 4 DESNO + 4 GOR. Merimo STEVILO DRAGIH OPERACIJ na pritisk
// (getComputedStyle, besedilo, closest, querySelectorAll, getBoundingClientRect, elementFromPoint, iskanje prekrivnega okna);
// cas v lazni vrsti dom je le primerjalen, ne cas na TV. Preizkus pade, ce je operacij vec kot prag ali ce se zaporedje
// izbranih ciljev razlikuje od referencnega (vedenje isto kot pred optimizacijo).
//   node tests/tv_kandidati_hitrost_test.js [pot do tv_spatial.js]   (SAFEER_IZPIS=1 izpise stevce, SAFEER_KLICATELJI=1 kdo jih klice)
'use strict';
const fs = require('fs');
const path = require('path');
const { zgradi } = require('./tv_velika_stran');

const VIR = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
// Zaporedje izbranih ciljev osnove (pred optimizacijo, izmerjeno 15. 11. 2026): optimizacija ga ne sme spremeniti.
const REFERENCA = 'Clanek 0-0|Clanek 1-0|Clanek 2-0|Clanek 3-0|Clanek 4-0|Clanek 5-0|Clanek 6-0|Clanek 7-0|Clanek 8-0|Clanek 9-0|Clanek 10-0|Clanek 11-0|Clanek 12-0|Clanek 13-0|Clanek 14-0|Clanek 15-0|Clanek 16-0|Clanek 17-0|Clanek 18-0|Clanek 19-0|Clanek 20-0|Clanek 21-0|Clanek 22-0|Clanek 23-0|Clanek 24-0|Clanek 24-1|Clanek 24-2|Clanek 25-0|Clanek 25-1|Clanek 24-1|Clanek 23-1|Clanek 22-1|Clanek 21-1';

function izmeri() {
    const t = zgradi(VIR);
    const { s, stevci } = t;
    const zaporedje = [];
    s.izberi(t.kartice[0].povezava);
    zaporedje.push(t.izbran());
    const poPritisku = [];
    function pritisk(smer) {
        const pred = Object.assign({}, stevci);
        const t0 = process.hrtime.bigint();
        s.smer(smer);
        const razl = { ms: Math.round(Number(process.hrtime.bigint() - t0) / 1e5) / 10 };
        for (const k in stevci) razl[k] = stevci[k] - pred[k];
        poPritisku.push(razl);
        zaporedje.push(t.izbran());
    }
    for (let i = 0; i < 24; i++) pritisk('DOWN');
    for (let i = 0; i < 4; i++) pritisk('RIGHT');
    for (let i = 0; i < 4; i++) pritisk('UP');
    const povp = {};
    for (const p of poPritisku) for (const k in p) povp[k] = (povp[k] || 0) + p[k] / poPritisku.length;
    for (const k in povp) povp[k] = Math.round(povp[k] * 10) / 10;
    return { t, zaporedje, povp, n: poPritisku.length, elementov: s.document.documentElement.vsi().length };
}

const r = izmeri();
if (process.env.SAFEER_KLICATELJI) {
    console.log(Object.entries(r.t.klicatelji).sort((a, b) => b[1] - a[1]).slice(0, 30).map(([k, v]) => (v / r.n).toFixed(1) + '/pritisk ' + k).join('\n'));
}
if (process.env.SAFEER_IZPIS) console.log(JSON.stringify({ elementov: r.elementov, pritiskov: r.n, povprecje_na_pritisk: r.povp, zaporedje: r.zaporedje.join(' | ') }, null, 1));
let stevilo = 0;
function preveri(opis, pogoj, podrobnost) {
    if (!pogoj) { console.log('  PADLO ' + opis + ' ' + (podrobnost === undefined ? '' : JSON.stringify(podrobnost))); process.exit(1); }
    stevilo++; console.log('  OK   ' + opis);
}
preveri('stran ima vsaj 1800 elementov', r.elementov >= 1800, r.elementov);
preveri('daljinec se je res premikal (vsaj 10 razlicnih ciljev)', new Set(r.zaporedje).size >= 10, new Set(r.zaporedje).size);
preveri('zaporedje izbranih ciljev je enako osnovi', r.zaporedje.join('|') === REFERENCA, r.zaporedje.join('|'));
// Pragovi (povprecje na pritisk, izmerjeno 15. 11. 2026): osnova 403 gcs / 301 besedilo / 708 closest / 1608 gbcr / 1,8 prekrivno okno;
// po popravku 273 / 244 / 551 / 1140 / 0,8. Prag je med obema, osnova pade.
const PRAGOVI = { gcs: 330, besedilo: 270, closest: 620, gbcr: 1350, prekrivnoOkno: 1.2 };
for (const k in PRAGOVI) preveri('povprecno ' + k + ' na pritisk <= ' + PRAGOVI[k], r.povp[k] <= PRAGOVI[k], r.povp[k]);
console.log('tv_kandidati_hitrost_test: OK (' + stevilo + ')');
