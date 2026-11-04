#!/usr/bin/env node
// Domaca stran brskalnika (assets/brave_home.html): »Dodaj« samo enkrat.   node tests/domaca_dodaj_test.js
//
// Zakaj (preverba TV 4. 11. 2026): »Dodaj« je bil dvakrat - gumb v glavi »Vec strani« in ploscica »+ Dodaj«
// (s stirimi ali manj stranmi celo trikrat: v prvi vrsti in v »Vec strani«).
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const VIR = fs.readFileSync(path.join(__dirname, '..', 'assets', 'brave_home.html'), 'utf8');
let n = 0;
function preveri(opis, pric, dob) { assert.strictEqual(dob, pric, opis); n++; console.log('  OK   ' + opis); }

preveri('v glavi ni gumba Dodaj (addTile v edit-chip)', false, /class="edit-chip"[^>]*onclick="addTile\(\)"/.test(VIR));
const m = VIR.match(/function kjeDodaj\(steviloStrani\) \{[^}]*\}/);
assert.ok(m, 'funkcije kjeDodaj ni');
const kjeDodaj = new Function(m[0] + '\nreturn kjeDodaj;')();
preveri('prazno: ploscica v prvi vrsti', 'hero', kjeDodaj(0));
preveri('tri strani: v prvi vrsti', 'hero', kjeDodaj(3));
preveri('stiri strani: na koncu Vec strani', 'mini', kjeDodaj(4));
preveri('ploscica v prvi vrsti samo po kjeDodaj', true, /if \(dodaj === 'hero'\) addHero/.test(VIR));
preveri('ploscica v Vec strani samo po kjeDodaj', true, /if \(dodaj === 'mini'\) addMini/.test(VIR));
console.log('domaca_dodaj_test: OK (' + n + ')');
