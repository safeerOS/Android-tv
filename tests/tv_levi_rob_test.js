#!/usr/bin/env node
// Levi rob strani v vgrajenem brskalniku Safeer OS (assets/tv_spatial.js) brez naprave:   node tests/tv_levi_rob_test.js
//
// Zakaj: na televizorju je VSAK pritisk LEVO odprl stransko vrstico Safeer OS (za Android je cela stran en pogled in
// meni je levo od nje), fokus v strani pa se je premaknil sele ob naslednjem pritisku. Pravilo: stransko vrstico odpre
// LEVO samo, kadar v isti vrsti na zaslonu levo ni vec nobenega elementa in stran ni podrsana v desno. Brez lupine
// Safeer OS (samostojni Safeer Browser) se ne spremeni nic.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');

const VIR = fs.readFileSync(path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
const od = VIR.indexOf('/* SAFEER_LEVI_ROB_ZACETEK');
const doKonca = VIR.indexOf('/* SAFEER_LEVI_ROB_KONEC */');
assert.ok(od > 0 && doKonca > od, 'bloka SAFEER_LEVI_ROB ni v tv_spatial.js');
const { jeLeviSosed, leviRob } = new Function(VIR.slice(od, doKonca) + '\nreturn { jeLeviSosed: jeLeviSosed, leviRob: leviRob };')();

const pravokotnik = (left, top, width, height) => ({ left, top, width, height, right: left + width, bottom: top + height });
let stevilo = 0;
function preveri(opis, pricakovano, dobljeno) {
    assert.deepStrictEqual(dobljeno, pricakovano, opis);
    stevilo++;
    console.log('  OK   ' + opis);
}

// Vrsta oznak kot na posnetku: [anime] [asian drama] [bollywood] ... v isti vrsti, pod njo sirok gumb.
const anime = pravokotnik(140, 300, 60, 28), drama = pravokotnik(210, 300, 110, 28), bollywood = pravokotnik(330, 300, 90, 28);
const gumb = pravokotnik(140, 380, 1500, 60), naslovStrani = pravokotnik(140, 120, 180, 40);
preveri('element levo v isti vrsti je sosed', true, jeLeviSosed(drama, anime));
preveri('element desno ni levi sosed', false, jeLeviSosed(drama, bollywood));
preveri('prvi v vrsti nima levega soseda v vrsti nad seboj (prej: skok na konec zgornje vrste)', false, jeLeviSosed(anime, naslovStrani));
preveri('sirok gumb cez vso sirino: oznaka nad njim ni levi sosed', false, jeLeviSosed(gumb, anime));
preveri('visoka kartica levo, ki sega cez dve vrsti, je sosed manjse desno', true,
    jeLeviSosed(pravokotnik(600, 520, 200, 80), pravokotnik(140, 400, 400, 300)));
preveri('element, ki se dotika samo po robu (4 px), ni v isti vrsti', false,
    jeLeviSosed(pravokotnik(600, 500, 200, 40), pravokotnik(140, 462, 200, 40)));
preveri('skoraj isto sredisce (manj kot 12 px levo) ni sosed', false, jeLeviSosed(pravokotnik(300, 300, 100, 40), pravokotnik(295, 300, 100, 40)));

preveri('samostojni brskalnik (brez lupine): navadna navigacija', '', leviRob(false, false, 0));
preveri('brez lupine tudi podrsana stran ostane pri starem', '', leviRob(undefined, false, 300));
preveri('lupina, levo je sosed: navadna navigacija', '', leviRob(true, true, 0));
preveri('lupina, levo ni nicesar: stranska vrstica', 'meni', leviRob(true, false, 0));
preveri('lupina, stran podrsana v desno: najprej nazaj na levi rob', 'podrsaj', leviRob(true, false, 140));

// Navigacija ju mora res uporabljati (da blok ne ostane mrtev po prihodnjih popravkih).
assert.ok(/_safeer_navigate_spatial = function\(direction, robVrstice\)/.test(VIR), 'navigacija ne sprejme robVrstice');
assert.ok((VIR.match(/return -2;/g) || []).length >= 4, 'navigacija levega roba ne javi na vseh stirih mestih');
assert.ok(VIR.indexOf('jeLeviSosed(cRect, r)') > doKonca, 'zanka kandidatov ne preveri levega soseda');
console.log('tv_levi_rob_test: OK (' + stevilo + ')');
