#!/usr/bin/env node
// Izbira se brez uporabnikove tipke ne zamenja (assets/tv_spatial.js SAFEER_SAMODEJNO):   node tests/tv_samodejno_test.js
//
// Zakaj (lastnik 5. 11. 2026 04:52, video f-droid.org): stran se je sama pomikala in izbira je skakala med povezavami,
// daljinec pa je v 80 s poslal samo 2 tipki.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const VIR = fs.readFileSync(path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
const od = VIR.indexOf('/* SAFEER_SAMODEJNO_ZACETEK */'), doK = VIR.indexOf('/* SAFEER_SAMODEJNO_KONEC */');
assert.ok(od > 0 && doK > od, 'bloka SAFEER_SAMODEJNO ni');
const smeSamodejno = new Function(VIR.slice(od, doK) + '\nreturn smeSamodejno;')();
let n = 0;
function preveri(opis, pric, dob) { assert.strictEqual(dob, pric, opis); n++; console.log('  OK   ' + opis); }
preveri('brez izbire: samodejna izbira sme (prvi vstop, okno)', true, smeSamodejno(false, 999999));
preveri('z izbiro, kratko po tipki: sme (stran premakne fokus po OK)', true, smeSamodejno(true, 400));
preveri('z izbiro, brez tipke: ne zamenja', false, smeSamodejno(true, 8000));
preveri('z izbiro, nikoli tipke (zacetek): ne zamenja', false, smeSamodejno(true, Date.now()));
const fokus = VIR.slice(VIR.indexOf("document.addEventListener('focusin'"));
preveri('fokus strani gre skozi vrata (izjema: vsebnik okna)', true, /if \(!samodejnoDovoljeno\('fokus strani'\) && plastIzbire\(ev\.target, window\.getComputedStyle\) !== ev\.target\) \{[\s\S]{0,600}?return;\s*\}/.test(fokus) && fokus.indexOf("samodejnoDovoljeno('fokus strani')") < fokus.indexOf('naFokusStrani('));
preveri('vstop v okno ostane omejen na enkrat na naslov', true, /if \(vstopNaNaslovu === kljucNaslova\(\)\) return false;/.test(VIR));
preveri('tipka (smer) zabelezi cas', true, /var navigirajProstorsko = function\(direction, robVrstice\) \{\s*zadnjaTipkaMs = Date\.now\(\);/.test(VIR));
preveri('tipka (OK) zabelezi cas', true, /var klikIzbrane = function\(\) \{\s*zadnjaTipkaMs = Date\.now\(\);/.test(VIR));
console.log('tv_samodejno_test: OK (' + n + ')');
