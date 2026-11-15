#!/usr/bin/env node
// SAFEER_RECT_PAS (15. 11. 2026): okvirji elementov izven pasu pogleda se predpomnijo v koordinatah strani (zanka pasu ne bere
// getBoundingClientRect za ~1800 elementov na tipko). Predpomnilnik ne sme prikriti elementa, ki se po spremembi strani (DOM)
// ali drsenju vsebnika znajde v pogledu: sprememba ga zavrze in element je spet cilj.
//   node tests/tv_rect_pas_test.js [pot do tv_spatial.js]
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
function premakni(el, dy) {
    if (el.okvir) el.okvir = [el.okvir[0], el.okvir[1] + dy, el.okvir[2], el.okvir[3]];
    el.children.forEach((c) => premakni(c, dy));
}

const t = zgradi(VIR);
const { s } = t;
const k0 = t.kartice[0], daljna = t.kartice[30 * 3];   // vrsta 30, levi stolpec: ~9700 px pod pogledom
s.izberi(k0.povezava);
s.smer('DOWN');                                        // zbiranje: okvirji daljnih elementov se zapomnijo
preveri('osnova: DOL gre na naslednjo vrsto (Clanek 1-0)', 'Clanek 1-0', t.izbran());
s.smer('UP');                                          // nazaj na prvo kartico brez izbire (izbira bi premaknila uro za 5 s)
preveri('osnova: GOR nazaj na Clanek 0-0', 'Clanek 0-0', t.izbran());
// Stran premakne daljno kartico tik pod prvo (npr. oglas se vrine) in to javi kot spremembo strani (slog).
premakni(daljna.kartica.parentElement, -(daljna.kartica.okvir[1] - 300));
s.spremeni([{ type: 'attributes', attributeName: 'style', target: daljna.kartica.parentElement, oldValue: '' }]);
s.smer('DOWN');
preveri('po spremembi strani je premaknjena kartica (Clanek 30-0) cilj', 'Clanek 30-0', t.izbran());
console.log('tv_rect_pas_test: OK (' + stevilo + ')');
