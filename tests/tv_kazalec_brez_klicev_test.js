#!/usr/bin/env node
// Ai Lina tv browser (lastnik 15. 11. 2026): v nacinu kazalca smerne tipke upravlja nativni VirtualPointerView in NE kliceta skripta
// (TvKeyRouter: veja kazalca vrne true pred SiteProfile), zato skript ne dobi tipke (_safeer_tipka). Merilo: brez tipke skript ob
// drsenju strani (kolesce pod kazalcem), spremembah strani in spremembi velikosti ne bere okvirjev (getBoundingClientRect) - ne
// zbira ciljev, ne predgreva. Merjeno na umetni strani z ~1900 elementi (kljuka naOkvir steje klice strani IN izvirnika).
// Preostanek v kazalcu (casovniki setInterval 250 ms in 4,5 s, opazovalci) lazni DOM ne izvaja: NEPREVERJENO, glej POROCILO.
//   node tests/tv_kazalec_brez_klicev_test.js [pot do tv_spatial.js]
'use strict';
const fs = require('fs');
const path = require('path');
const { zgradi } = require('./tv_velika_stran');

const VIR = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
let stevilo = 0;
function preveri(opis, pricakovano, dobljeno) {
    if (pricakovano !== dobljeno) { console.log('  PADLO ' + opis + ' (pricakovano ' + JSON.stringify(pricakovano) + ', dobljeno ' + JSON.stringify(dobljeno) + ')'); process.exit(1); }
    stevilo++; console.log('  OK   ' + opis);
}
const t = zgradi(VIR); const { s, stevci } = t;
const pred = stevci.gbcr;
// Kazalec drsi stran s kolescem (20 zarez po ~300 px), stran javi spremembe (lenobne slike) in sprememba velikosti.
for (let i = 0; i < 20; i++) {
    s.drsi(i * 300);
    s.spremeni([{ type: 'attributes', attributeName: 'class', target: t.kartice[(i * 3) % t.kartice.length].kartica, oldValue: '' }]);
    s.sprozi('resize', {});
    s.ura.t += 100;
}
preveri('v nacinu kazalca (brez tipk skripta) skript ob drsenju in spremembah ne bere okvirjev (gbcr iz skripta)', 0, stevci.gbcr - pred);
preveri('niti iskanja prekrivnega okna (celoten DOM)', 0, stevci.prekrivnoOkno);
// Nasprotni primer: ena tipka v skript (nacin puscic) zbere cilje - merilo je torej resnicno obcutljivo.
const p1 = stevci.gbcr;
s.izberi(t.kartice[0].povezava); s.smer('DOWN');
preveri('v nacinu puscic ista stran ob tipki bere okvirje (merilo je obcutljivo)', true, stevci.gbcr - p1 > 100);
console.log('tv_kazalec_brez_klicev_test: OK (' + stevilo + ')');
