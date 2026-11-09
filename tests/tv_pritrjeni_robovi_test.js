#!/usr/bin/env node
// Pritrjene vrstice strani (assets/tv_spatial.js, SAFEER_PRITRJENI_ROBOVI) brez naprave:   node tests/tv_pritrjeni_robovi_test.js
//
// Zakaj (lastnik 9. 11. 2026, apk-22, rtvslo.si): po GOR je izbrani naslov obstal pod vrstico rubrik, obroc pa cez njo.
// Stran ima dve pritrjeni vrstici, eno pod drugo (#main-bar 0-60 px, #sub-menu 50-90 px; izmerjeno v Chromu 1280x720).
// Sonda samo pri vrhu je videla prvo. Prosti del pogleda se mora zaceti pod ZADNJO pritrjeno vrstico.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');

const VIR = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
const od = VIR.indexOf('/* SAFEER_PRITRJENI_ROBOVI_ZACETEK */');
const doKonca = VIR.indexOf('/* SAFEER_PRITRJENI_ROBOVI_KONEC */');
assert.ok(od > 0 && doKonca > od, 'bloka SAFEER_PRITRJENI_ROBOVI ni v tv_spatial.js (izbrani element obstane pod drugo pritrjeno vrstico)');
const { robPritrjenih } = new Function(VIR.slice(od, doKonca) + '\nreturn { robPritrjenih: robPritrjenih };')();

let stevilo = 0;
function preveri(opis, pricakovano, dobljeno) {
    assert.deepStrictEqual(dobljeno, pricakovano, opis);
    stevilo++;
    console.log('  OK   ' + opis);
}
// Stran = seznam pritrjenih vrstic; sonda vrne tiste, ki pokrivajo visino y.
function stran(vrstice) {
    return (y) => vrstice.filter((v) => y >= v.top && y < v.bottom).map((v) => ({ top: v.top, bottom: v.bottom, height: v.bottom - v.top }));
}
const H = 720;
preveri('brez pritrjenih vrstic: cel pogled', { zgoraj: 0, spodaj: H }, robPritrjenih(H, stran([])));
preveri('ena glava: prosto pod njo', { zgoraj: 60, spodaj: H }, robPritrjenih(H, stran([{ top: 0, bottom: 60 }])));
preveri('rtvslo.si: glava 0-60 in vrstica rubrik 50-90 - prosto sele pod 90', { zgoraj: 90, spodaj: H },
    robPritrjenih(H, stran([{ top: 0, bottom: 60 }, { top: 50, bottom: 90 }])));
preveri('tri vrstice ena pod drugo', { zgoraj: 150, spodaj: H },
    robPritrjenih(H, stran([{ top: 0, bottom: 50 }, { top: 50, bottom: 100 }, { top: 100, bottom: 150 }])));
preveri('pritrjen element sredi strani (ni ob glavi) ne steje', { zgoraj: 60, spodaj: H },
    robPritrjenih(H, stran([{ top: 0, bottom: 60 }, { top: 300, bottom: 360 }])));
preveri('prekrivno okno cez ves zaslon ni vrstica', { zgoraj: 0, spodaj: H }, robPritrjenih(H, stran([{ top: 0, bottom: H }])));
preveri('vrstice skupaj nad 35 % visine: druga ne steje vec', { zgoraj: 200, spodaj: H },
    robPritrjenih(H, stran([{ top: 0, bottom: 200 }, { top: 200, bottom: 300 }])));
preveri('spodnja vrstica z gumbi in nad njo se ena', { zgoraj: 0, spodaj: 600 },
    robPritrjenih(H, stran([{ top: 660, bottom: 720 }, { top: 600, bottom: 660 }])));
preveri('glava in noga hkrati', { zgoraj: 90, spodaj: 660 },
    robPritrjenih(H, stran([{ top: 0, bottom: 60 }, { top: 50, bottom: 90 }, { top: 660, bottom: 720 }])));
console.log('tv_pritrjeni_robovi_test: ' + stevilo + ' preverb OK');
