#!/usr/bin/env node
// Gumb v tujem okvirju (assets/tv_spatial.js, SAFEER_OKVIR_GUMB) brez naprave:   node tests/tv_okvir_gumb_test.js
//
// Zakaj (lastnik 2. 11. 2026, claude.ai prijava na TV): »Nadaljuj z Google« je gumb Google Identity Services v tujem
// okvirju (accounts.google.com/gsi/button). Okvirji niso bili kandidati daljinca, zato ga ni bilo mogoce izbrati.
// Majhen viden okvir je zdaj cilj z obrocem; OK nanj je en pravi dotik na sredini.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');

const VIR = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
const od = VIR.indexOf('/* SAFEER_OKVIR_GUMB_ZACETEK');
const doKonca = VIR.indexOf('/* SAFEER_OKVIR_GUMB_KONEC */');
assert.ok(od > 0 && doKonca > od, 'bloka SAFEER_OKVIR_GUMB ni v tv_spatial.js (okvirji niso cilji daljinca)');
const { jeGumbOkvir } = new Function(VIR.slice(od, doKonca) + '\nreturn { jeGumbOkvir: jeGumbOkvir };')();

let stevilo = 0;
function preveri(opis, pricakovano, dobljeno) {
    assert.deepStrictEqual(dobljeno, pricakovano, opis);
    stevilo++;
    console.log('  OK   ' + opis);
}
const W = 1920, H = 1080;
const GSI = 'gsi_123_456  https://accounts.google.com/gsi/button?theme=outline&size=large&client_id=x';
preveri('gumb GSI (400 x 44) je cilj', true, jeGumbOkvir(400, 44, W, H, GSI));
preveri('gumb GSI na ozkem zaslonu (TV 960 x 540, 300 x 40) je cilj', true, jeGumbOkvir(300, 40, 960, 540, GSI));
preveri('okvir cez pol zaslona (vsebina, zemljevid) ni cilj', false, jeGumbOkvir(1200, 600, W, H, 'map'));
preveri('visok okvir (video 640 x 360) ni cilj', false, jeGumbOkvir(640, 360, W, H, 'https://www.youtube.com/embed/x'));
preveri('prazen ali skrit okvir (0 x 0, 1 x 1) ni cilj', false, jeGumbOkvir(0, 0, W, H, '') || jeGumbOkvir(1, 1, W, H, ''));
preveri('oglasna pasica 728 x 90 (google_ads_iframe) ni cilj', false, jeGumbOkvir(728, 90, W, H, 'google_ads_iframe_/123/x_0'));
preveri('oglas aswift ni cilj', false, jeGumbOkvir(320, 100, W, H, 'aswift_1'));
preveri('oglas doubleclick ni cilj', false, jeGumbOkvir(320, 50, W, H, 'https://ad.doubleclick.net/x'));

// Navigacija ga mora res uporabljati (da blok ne ostane mrtev).
const kandidati = VIR.slice(VIR.indexOf('function getCandidatesFresh()'), VIR.indexOf('function getActiveElement()'));
preveri('okvirji so med kandidati (iskanje elementov)', true, /\[class\*="icon-p24_search"\], iframe'\)/.test(kandidati));
const akt = VIR.slice(VIR.indexOf('function isActionable(el)'), VIR.indexOf('function getCandidates()'));
preveri('isActionable sprejme okvir samo prek jeGumbOkvir', true, /if \(tag === 'IFRAME'\) \{[\s\S]{0,400}return jeGumbOkvir\(/.test(akt));
preveri('okvir ostane samostojen cilj (ne kartica okoli njega)', true, VIR.indexOf("tag === 'BUTTON' || tag === 'INPUT' || tag === 'IFRAME'") > 0);
preveri('obroc ne da fokusa okvirju', true, VIR.indexOf("tag !== 'INPUT' && tag !== 'TEXTAREA' && tag !== 'IFRAME'") > 0);
preveri('OK na okvirju: en pravi dotik (nativeTapElement) in konec', true,
    /toUpperCase\(\) === 'IFRAME'\) \{ nativeTapElement\(target\); return; \}/.test(VIR));
console.log('tv_okvir_gumb_test: OK (' + stevilo + ')');
