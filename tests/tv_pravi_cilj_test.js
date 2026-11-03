#!/usr/bin/env node
// Cilji daljinca na spletnih straneh (assets/tv_spatial.js):   node tests/tv_pravi_cilj_test.js
//
// Zakaj (zunanji pregled fokusa z daljincem, 3. 11. 2026, Android 11 TV):
//  - SAFEER_PRAVI_CILJ: ovitek kartice po razredu (card/tile/item/media) je zamenjal gumbe in povezave v sebi; obroc je
//    bil na celi kartici, gumb »Predvajaj« in »Vec« nista bila dosegljiva.
//  - lepljiva glava cez zgornjo polovico gumba ga je izlocila (jePrekrit je preveril samo vodoravno sredino).
//  - SAFEER_GOR_NA_VRHU: element v zgornjih 80 px je ob GOR takoj zapustil stran (v nacinu aplikacije aplikacijo),
//    cetudi je bil nad njim se cilj.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { ustvari } = require('./tv_lazni_dom');

const VIR = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
let stevilo = 0;
function preveri(opis, pricakovano, dobljeno) {
    assert.deepStrictEqual(dobljeno, pricakovano, opis);
    stevilo++;
    console.log('  OK   ' + opis);
}
const izbran = (s) => s.document.querySelector('.safeer-active-card');
const ime = (el) => (el ? el.tagName + ':' + el.innerText.trim().split(' ')[0] : 'nic');

// 1) Kartica z naslovom (povezava) in dvema gumboma.
{
    const s = ustvari();
    const zgoraj = s.el('button', { okvir: [100, 20, 200, 50], besedilo: 'Zgoraj' });
    const kartica = s.el('div', { razred: 'video-card', okvir: [100, 100, 400, 320] });
    const naslov = s.el('a', { attrs: { href: '/film' }, okvir: [110, 110, 380, 40], besedilo: 'Naslov' });
    const predvajaj = s.el('button', { okvir: [110, 350, 180, 50], besedilo: 'Predvajaj' });
    const vec = s.el('button', { okvir: [310, 350, 180, 50], besedilo: 'Vec' });
    kartica.dodaj(naslov, predvajaj, vec);
    s.body.dodaj(zgoraj, kartica);
    s.nalozi(VIR);
    s.window._safeer_highlight(zgoraj);
    s.window._safeer_navigate_spatial('DOWN');
    preveri('kartica z vec dejanji: DOL izbere naslov (povezavo), ne cele kartice', 'A:Naslov', ime(izbran(s)));
    s.window._safeer_navigate_spatial('DOWN');
    preveri('kartica z vec dejanji: DOL nato »Predvajaj«', 'BUTTON:Predvajaj', ime(izbran(s)));
    s.window._safeer_navigate_spatial('RIGHT');
    preveri('kartica z vec dejanji: DESNO »Vec«', 'BUTTON:Vec', ime(izbran(s)));
}
// 2) Kartica z eno samo povezavo ostane en cilj (cela kartica, kot prej).
{
    const s = ustvari();
    const zgoraj = s.el('button', { okvir: [100, 20, 200, 50], besedilo: 'Zgoraj' });
    const kartica = s.el('div', { razred: 'tile', okvir: [100, 100, 300, 200] });
    kartica.dodaj(s.el('a', { attrs: { href: '/x' }, okvir: [110, 250, 280, 40], besedilo: 'Edina' }));
    s.body.dodaj(zgoraj, kartica);
    s.nalozi(VIR);
    s.window._safeer_highlight(zgoraj);
    s.window._safeer_navigate_spatial('DOWN');
    preveri('kartica z eno povezavo: cilj je kartica', 'DIV:Edina', ime(izbran(s)));
}
// 3) Lepljiva glava prekrije zgornjo polovico gumba: spodnji del je viden, gumb ostane cilj.
{
    const s = ustvari();
    const glava = s.el('header', { okvir: [0, 0, 1920, 100], slog: { position: 'fixed', zIndex: '10' } });
    glava.dodaj(s.el('button', { okvir: [1500, 20, 200, 50], besedilo: 'Glava' }));
    const delno = s.el('button', { okvir: [100, 60, 300, 60], besedilo: 'Delno' });
    const spodaj = s.el('button', { okvir: [100, 400, 300, 60], besedilo: 'Spodaj' });
    s.body.dodaj(glava, delno, spodaj);
    s.nalozi(VIR);
    s.window._safeer_highlight(spodaj);
    s.window._safeer_navigate_spatial('UP');
    preveri('glava cez zgornjo polovico: GOR izbere delno prekriti gumb', 'BUTTON:Delno', ime(izbran(s)));
}
// 4) GOR z elementa v zgornjih 80 px: najprej cilj nad njim, sele nato izstop (-1, naslovna vrstica).
{
    const s = ustvari();
    const vrh = s.el('button', { okvir: [400, 0, 200, 40], besedilo: 'Vrh' });
    const tik = s.el('button', { okvir: [100, 60, 200, 40], besedilo: 'Tik' });
    s.body.dodaj(vrh, tik, s.el('button', { okvir: [100, 400, 200, 40], besedilo: 'Dol' }));
    s.nalozi(VIR);
    s.window._safeer_highlight(tik);
    preveri('GOR iz zgornjih 80 px s ciljem visje: ostane na strani', 1, s.window._safeer_navigate_spatial('UP'));
    preveri('GOR iz zgornjih 80 px: izbran cilj nad njim', 'BUTTON:Vrh', ime(izbran(s)));
    preveri('drugi GOR na vrhu strani brez cilja visje: izstop', -1, s.window._safeer_navigate_spatial('UP'));
}
console.log('tv_pravi_cilj_test: OK (' + stevilo + ')');
