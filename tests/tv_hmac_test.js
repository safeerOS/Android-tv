#!/usr/bin/env node
// Cetrti slepi pregled 5. 11. 2026 (N-B, N-D, odlocitev vodje 3): dokazi tv_spatial.js so HMAC-SHA256 z zetonom strani.
// Preverimo lastno izvedbo (SAFEER_HMAC) proti node:crypto in skupnim vektorjem (iste vrednosti v tests/TvTipkeTest.kt) -
// v svetu, kjer je stran prej prepisala vgrajene metode in nastavljalce (napadalec bere zeton ali vmesne vrednosti).
//   node tests/tv_hmac_test.js [pot do tv_spatial.js]
'use strict';
const assert = require('assert');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const VIR = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
let stevilo = 0;
function preveri(opis, pricakovano, dobljeno) {
    assert.deepStrictEqual(dobljeno, pricakovano, opis);
    stevilo++;
    console.log('  OK   ' + opis);
}
function blok(ime) {
    const i = VIR.indexOf('/* ' + ime + '_ZACETEK'), j = VIR.indexOf('/* ' + ime + '_KONEC */');
    assert.ok(i >= 0 && j > i, 'blok ' + ime + ' manjka (brez popravka: ni HMAC)');
    return VIR.slice(i, j);
}
const tabela = blok('SAFEER_ZNAK_NAMESTITVE').match(/var KODE_ZNAKOV = \{[^\n]*\};/);
assert.ok(tabela, 'KODE_ZNAKOV');
const koda = tabela[0] + '\n' + blok('SAFEER_HMAC') + '\nthis.__h = { odtisHex: odtisHex, hmacBajti: hmacBajti };';

// Stran pred nasim skriptom: nastavljalci na indeksih Array.prototype in Object.prototype, prepisani charCodeAt, Math.imul,
// toString, String.prototype.concat - vse zapise v dnevnik (to bi napadalec videl).
const ctx = vm.createContext({});
vm.runInContext(`
    var dnevnik = [];
    for (var i = 0; i < 200; i++) (function (k) {
        Object.defineProperty(Array.prototype, k, { set: function (v) { dnevnik.push('A' + k + '=' + v); }, get: function () { dnevnik.push('Ag' + k); return 0; }, configurable: true });
        Object.defineProperty(Object.prototype, k, { set: function (v) { dnevnik.push('O' + k + '=' + v); }, get: function () { dnevnik.push('Og' + k); return 0; }, configurable: true });
    })(String(i));
    String.prototype.charCodeAt = function () { dnevnik.push('charCodeAt'); return 0; };
    String.prototype.concat = function () { dnevnik.push('concat'); return ''; };
    Math.imul = function () { dnevnik.push('imul'); return 0; };
    Number.prototype.toString = function () { dnevnik.push('toString'); return ''; };
    Array.prototype.push = function () { dnevnik.push('push'); return 0; };
`, ctx);
vm.runInContext(koda, ctx);
const H = ctx.__h;
const IZ = '0123456789abcdef0123456789abcdef';
// Skupni vektorji (izracunani s Python hmac; iste stevilke v tests/TvTipkeTest.kt).
const VEKTORJI = [
    ['z'.repeat(48), 'datoteka|' + IZ, 'e47329582e82b715def9698cf9bf11cdaac8957ca113435c6f5a6aa2e4a53c99'],
    ['z'.repeat(48), 'tipka|7|' + IZ, '5324ab093cac221be66308b2c117da1ec0c37209df048be2fd293a122f0e1188'],
    ['a1'.repeat(24), 'datoteka|ffffffffffffffff', '3c19c611192160fa341491dbca22e1939406c7cf50540bf75368508986c7b55d'],
    ['z'.repeat(48), 'cilj|enter|' + IZ, '60e2ebac5f45798f86b1c72fcd5b91e493d7a7c768f86ea0f3f8d460df7476bd'],
    ['z'.repeat(48), 'ziv|' + IZ, '457cf84fb6ca2cb9dc87aaa95c972b808782c3f791dfb07aa31ed2ef59934fb8'],
    ['0123456789abcdef'.repeat(3), 'x'.repeat(119), 'e791a98eb0ecbe7168f461cd5f22123b38a92da02740e847c8aaacb02ac0325e'],
    ['z'.repeat(32), 'a'.repeat(55), 'bd6815b419feca963e3d1265609542dba7b87370e198466ca8a7c454cdc43dea'],
    ['z'.repeat(64), 'b'.repeat(56), 'd97602b29d0c0cdb13c57b6b8b2ab8ea4ff162802ad99eca67b5ae7eef7c0121'],
];
for (const [k, m, h] of VEKTORJI) {
    preveri('vektor ' + k.slice(0, 4) + '.. ' + m.slice(0, 12) + ' (' + m.length + ' B)', h, H.odtisHex(k, m));
    preveri('  node:crypto isto', h, crypto.createHmac('sha256', k).update(m).digest('hex'));
}
// RFC 4231, primer 2 (kljuc "Jefe") - neposredno nad bajti (znaki zunaj KODE_ZNAKOV).
function bajti(v) { const o = Object.create(null); for (let i = 0; i < v.length; i++) o[i] = v.charCodeAt(i); return o; }
const D = H.hmacBajti(bajti('Jefe'), 4, bajti('what do ya want for nothing?'), 28);
let hex = ''; for (let i = 0; i < 32; i++) hex += (D[i] < 16 ? '0' : '') + D[i].toString(16);
preveri('RFC 4231 primer 2', '5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843', hex);
// Nakljucni zetoni in nizi (vse dolzine do dveh blokov) proti node:crypto.
const ZNAKI = '0123456789abcdefghijklmnopqrstuvwxyz|';
let vsi = true;
for (let n = 0; n < 300; n++) {
    const z = Array.from({ length: 32 + (n % 33) }, () => ZNAKI[crypto.randomInt(36)]).join('');
    const m = Array.from({ length: n % 150 }, () => ZNAKI[crypto.randomInt(37)]).join('');
    if (H.odtisHex(z, m) !== crypto.createHmac('sha256', z).update(m).digest('hex')) { vsi = false; break; }
}
preveri('300 nakljucnih parov (kljuc 32-64, sporocilo 0-149 znakov) = node:crypto', true, vsi);
preveri('prekratek zeton, tuj znak, ne-niz: brez odtisa', ['', '', '', ''],
    [H.odtisHex('z'.repeat(31), 'a'), H.odtisHex('z'.repeat(48), 'A'), H.odtisHex('z'.repeat(48), 7), H.odtisHex(null, 'a')]);
preveri('stran (prepisane metode in nastavljalci) med racunanjem ni videla nicesar', [], Array.from(vm.runInContext('dnevnik', ctx)));
// Hitrost (TV ima ~10x pocasnejsi procesor od prenosnika; prejsnja ocena 0,5-1 ms na tipko).
const t0 = process.hrtime.bigint();
for (let i = 0; i < 1000; i++) H.odtisHex('z'.repeat(48), 'tipka|' + i + '|' + IZ);
const us = Number(process.hrtime.bigint() - t0) / 1000 / 1000;
console.log('  cas: ' + us.toFixed(1) + ' us na odtis (1000 ponovitev, ta racunalnik)');
console.log('tv_hmac_test: OK (' + stevilo + ')');
