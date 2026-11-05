#!/usr/bin/env node
// OK na polju za datoteko odpre izbirnik (assets/tv_spatial.js SAFEER_OK_DATOTEKA):   node tests/tv_ok_datoteka_test.js
//
// Zakaj (TV 4. 11. 2026, w3schools tryhtml5_input_type_file): fokus je bil na »Izberite datoteko«, OK ni naredil nicesar,
// onShowFileChooser se ni poklical. Chromium izbirnik odpre samo ob uporabnikovi aktivaciji, klik iz evaluateJavascript
// je nima. Polje dobi fokus, skript vrne 'datoteka' in aplikacija poslje pravo tipko Enter (TvTipke.okNaDatoteki).
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const VIR = fs.readFileSync(path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
const KT = fs.readFileSync(path.join(__dirname, '..', 'src', 'main', 'kotlin', 'si', 'safeer', 'tv', 'tv', 'SiteProfile.kt'), 'utf8');
function blok(ime) {
    const od = VIR.indexOf('/* ' + ime + '_ZACETEK */'), doK = VIR.indexOf('/* ' + ime + '_KONEC */');
    assert.ok(od > 0 && doK > od, 'bloka ' + ime + ' ni');
    return VIR.slice(od, doK);
}
let n = 0;
function preveri(opis, pric, dob) { assert.strictEqual(dob, pric, opis); n++; console.log('  OK   ' + opis); }

const { vhodDatoteke, okNaDatoteki } = new Function(blok('SAFEER_OK_DATOTEKA') + '\nreturn { vhodDatoteke, okNaDatoteki };')();
function el(tag, atr, dodatno) {
    return Object.assign({ nodeType: 1, tagName: tag, disabled: false, getAttribute: (k) => (k in (atr || {}) ? atr[k] : null) }, dodatno || {});
}
const datoteka = el('INPUT', { type: 'file' });
const DATOTEKA = el('INPUT', { type: 'FILE' });
const besedilo = el('INPUT', { type: 'text' });
const brezTipa = el('INPUT', {});
const gumb = el('BUTTON', {});
const povezava = el('A', { href: '/x' });
const oznaka = el('LABEL', { for: 'f' }, { control: datoteka });
const oznakaBesedila = el('LABEL', { for: 't' }, { control: besedilo });
const prazna = el('LABEL', {}, { control: null });

preveri('input type=file je polje za datoteko', datoteka, vhodDatoteke(datoteka));
preveri('type=FILE (velike crke) tudi', DATOTEKA, vhodDatoteke(DATOTEKA));
preveri('besedilno polje ni', null, vhodDatoteke(besedilo));
preveri('input brez tipa (text) ni', null, vhodDatoteke(brezTipa));
preveri('gumb ni', null, vhodDatoteke(gumb));
preveri('povezava ni', null, vhodDatoteke(povezava));
preveri('oznaka za polje datoteke vrne polje', datoteka, vhodDatoteke(oznaka));
preveri('oznaka za besedilno polje ni', null, vhodDatoteke(oznakaBesedila));
preveri('oznaka brez polja ni', null, vhodDatoteke(prazna));
preveri('nic ni', null, vhodDatoteke(null));

preveri('polje s fokusom: datoteka (Enter iz aplikacije)', 'datoteka', okNaDatoteki(datoteka, datoteka));
preveri('fokusa ni dobilo (skrito v oznaki): navaden klik', '', okNaDatoteki(datoteka, null));
preveri('fokus drugje: navaden klik', '', okNaDatoteki(datoteka, gumb));
const onemogoceno = el('INPUT', { type: 'file' }, { disabled: true });
preveri('onemogoceno polje: nic posebnega', '', okNaDatoteki(onemogoceno, onemogoceno));
preveri('brez polja: navaden klik', '', okNaDatoteki(null, null));

// Vezava: OK v _safeer_click_focused_card vrne 'datoteka' pred dotikom, aplikacija na to poslje Enter.
const klik = VIR.slice(VIR.indexOf('var klikIzbrane = function'));
const iDat = klik.indexOf('return dokazDatoteke(ZETON_DOTIKA, izziv);'), iDotik = klik.indexOf('klikniElement(');
preveri('OK na datoteki vrne pred dotikom in el.click()', true, iDat > 0 && iDat < iDotik);
// Neodvisni pregled 5. 11. 2026: fokus in aktivni element z izvirniki z zacetka dokumenta (fokusIz, aktivenIz).
preveri('fokus pred odlocitvijo (izvirnik)', true, /fokusIz\(vhodD\);(?:\s*\/\/[^\n]*)*\s*if \(okNaDatoteki\(vhodD, aktivenIz\(\)\) === 'datoteka'\)/.test(klik));
// Tretji neodvisni pregled 5. 11. 2026 (N3): Enter sele po drugi preverbi cilja (_safeer_preveri_cilj, TvTipke.ciljVelja 'enter').
preveri('aplikacija na "datoteka" poslje KEYCODE_ENTER sele po drugi preverbi cilja', true,
    /TvTipke\.okNaDatoteki\(o, zeton, izziv\)\)[\s\S]{0,900}_safeer_preveri_cilj[\s\S]{0,300}TvTipke\.ciljVelja\(o2, zeton, izziv2, "enter"\) && wv\.zetonDotika == zeton\) \{\s*nativnaTipka\(wv, event, KeyEvent\.KEYCODE_ENTER\)/.test(KT));
preveri('brez druge preverbe ni Enter (ni neposrednega klica po okNaDatoteki)', false,
    /TvTipke\.okNaDatoteki\(o, zeton, izziv\)\) nativnaTipka/.test(KT));
preveri('Google: polje za datoteko dobi Enter, ne dotik', true, /if\(e\.matches\('input\[type="file"\]'\)\)return 'enter';/.test(KT));
console.log('tv_ok_datoteka_test: OK (' + n + ')');
