#!/usr/bin/env node
// Odrezan element ni cilj daljinca (assets/tv_spatial.js, SAFEER_ODREZAN) brez naprave:   node tests/tv_odrezan_cilj_test.js
//
// Zakaj (TV 10. 11. 2026, apk-22, rtvslo.si): po GOR do vrha je bil izbran A 1220x22, obroc cez celo modro glavo. To je
// povezava »Pojdite na meni« za bralnike zaslona: position absolute + clip rect(1px,1px,1px,1px) - okvir ima, vidna ni.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');

const VIR = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
const od = VIR.indexOf('/* SAFEER_ODREZAN_ZACETEK */');
const doKonca = VIR.indexOf('/* SAFEER_ODREZAN_KONEC */');
assert.ok(od > 0 && doKonca > od, 'bloka SAFEER_ODREZAN ni v tv_spatial.js (nevidna povezava za bralnike zaslona je cilj daljinca)');
const { jeOdrezanSlog } = new Function(VIR.slice(od, doKonca) + '\nreturn { jeOdrezanSlog: jeOdrezanSlog };')();

let stevilo = 0;
function preveri(opis, pricakovano, dobljeno) {
    assert.strictEqual(dobljeno, pricakovano, opis);
    stevilo++;
    console.log('  OK   ' + opis);
}
preveri('rtvslo.si »Pojdite na meni«: absolute + rect(1px, 1px, 1px, 1px)', true, jeOdrezanSlog('absolute', 'rect(1px, 1px, 1px, 1px)', 'none'));
preveri('sr-only: rect(0px, 0px, 0px, 0px)', true, jeOdrezanSlog('absolute', 'rect(0px, 0px, 0px, 0px)', 'none'));
preveri('zapis brez vejic: rect(0 0 0 0)', true, jeOdrezanSlog('absolute', 'rect(0 0 0 0)', 'none'));
preveri('pritrjen element z rezom 1 px', true, jeOdrezanSlog('fixed', 'rect(0px, 1px, 1px, 0px)', 'none'));
preveri('clip-path inset(50%)', true, jeOdrezanSlog('static', 'auto', 'inset(50%)'));
preveri('clip-path inset(100%)', true, jeOdrezanSlog('relative', 'auto', 'inset(100%)'));
preveri('navadna povezava', false, jeOdrezanSlog('static', 'auto', 'none'));
preveri('absolutna povezava brez reza', false, jeOdrezanSlog('absolute', 'auto', 'none'));
preveri('clip na staticnem elementu ne velja', false, jeOdrezanSlog('static', 'rect(1px, 1px, 1px, 1px)', 'none'));
preveri('rez, ki pusti viden del (rect 0 200 40 0)', false, jeOdrezanSlog('absolute', 'rect(0px, 200px, 40px, 0px)', 'none'));
preveri('zaobljen rez slike: inset(0% round 8px)', false, jeOdrezanSlog('static', 'auto', 'inset(0% round 8px)'));
preveri('majhen rez roba: inset(10%)', false, jeOdrezanSlog('static', 'auto', 'inset(10%)'));
preveri('prazne vrednosti', false, jeOdrezanSlog('', '', ''));

// Pravilo mora biti uporabljeno pri zbiranju ciljev in pri izbranem elementu, ne samo napisano.
assert.ok(/if \(jeOdrezanSlog\(style\.position, style\.clip, style\.clipPath\)\) return false;/.test(VIR), 'zbiranje ciljev ne klice jeOdrezanSlog');
assert.ok(/p === el && jeOdrezanSlog\(st\.position, st\.clip, st\.clipPath\)/.test(VIR), 'jeProsojen (izbrani element) ne klice jeOdrezanSlog');
stevilo += 2;
// H50 zapise okvir obroca in merilo strani (meritev presirokega obroca na TV).
assert.ok(/ob: okvirObrocaZaDnevnik\(\)/.test(VIR) && /visualViewport\.scale/.test(VIR) && /iw: window\.innerWidth/.test(VIR), 'H50 ne zapise okvirja obroca in merila');
stevilo++;
console.log('tv_odrezan_cilj_test: ' + stevilo + ' preverb OK');
