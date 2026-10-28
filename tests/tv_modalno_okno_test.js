#!/usr/bin/env node
// Gumbi o piskotkih v modalnem oknu so cilj daljinca (assets/tv_spatial.js):   node tests/tv_modalno_okno_test.js
//
// Zakaj: Facebook na TV (lastnik 28. 10. 2026) - »Dovoli vse piskotke« je bil izlocen kot »noga ali piskotki«, zato
// daljinec do gumbov ni prisel. Pasica s piskotki zunaj modalnega okna ostane izlocena.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');

const VIR = fs.readFileSync(path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
const od = VIR.indexOf('/* SAFEER_MODALNO_ZACETEK */');
const doKonca = VIR.indexOf('/* SAFEER_MODALNO_KONEC */');
assert.ok(od > 0 && doKonca > od, 'bloka SAFEER_MODALNO ni v tv_spatial.js');
const jeVModalnemOknu = new Function(VIR.slice(od, doKonca) + '\nreturn jeVModalnemOknu;')();

// Element z closest() kot v brskalniku: predniki so seznam izbirnikov, ki jim ustrezajo.
function el(ustreza) {
    return { closest: (izb) => izb.split(',').map(x => x.trim()).some(x => ustreza.indexOf(x) !== -1) ? {} : null };
}
let n = 0;
function preveri(opis, pricakovano, dobljeno) { assert.strictEqual(dobljeno, pricakovano, opis); n++; console.log('  OK   ' + opis); }
preveri('gumb v role=dialog (Facebook)', true, jeVModalnemOknu(el(['[role="dialog"]'])));
preveri('gumb v aria-modal', true, jeVModalnemOknu(el(['[aria-modal="true"]'])));
preveri('gumb v <dialog open>', true, jeVModalnemOknu(el(['dialog[open]'])));
preveri('pasica s piskotki na dnu strani (brez okna)', false, jeVModalnemOknu(el([])));
preveri('null', false, jeVModalnemOknu(null));
preveri('closest vrze izjemo', false, jeVModalnemOknu({ closest: () => { throw new Error('x'); } }));

// Izlocitev piskotkov mora najprej vprasati po modalnem oknu.
const f = VIR.indexOf('function jeNogaAliPiskotki(el) {');
assert.ok(f > doKonca, 'jeNogaAliPiskotki manjka');
assert.ok(/function jeNogaAliPiskotki\(el\) \{\s*if \(!el\) return false;\s*if \(jeVModalnemOknu\(el\)\) return false;/.test(VIR.slice(f, f + 200)),
    'jeNogaAliPiskotki ne izpusti elementov v modalnem oknu');
console.log('tv_modalno_okno_test: OK (' + n + ')');
