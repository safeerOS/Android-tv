#!/usr/bin/env node
// Preverjanje »Verify you are human« (Cloudflare Turnstile) z daljincem (assets/tv_spatial.js):   node tests/tv_izziv_test.js
//
// Zakaj (lastnik 3. 11. 2026, prijava na TV): do preverjanja ni prisel, GOR in DOL sta samo drsela, do naslovne vrstice
// (in kazalca) ni mogel - ujet v strani. Izmerjeno 3. 11. 2026 (Chrome, preizkusni kljuc Cloudflare 3x...FF): okvir
// challenges.cloudflare.com je v zaprtem shadow DOM - querySelectorAll('iframe') vrne 0, vidna sta le gostitelj (DIV,
// 880 x 69) in skrito polje cf-turnstile-response; okvir je 300 x 65 ob levem robu, potrditveno polje 22 px od levega
// roba na sredini visine. Preizkus: DOL doseze preverjanje, OK je en pravi dotik na potrditvenem polju, GOR od njega vrne
// tipko naslovni vrstici (-1), tipka se ne izgubi v drsenju, ki se ne premakne.
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

// Stran izziva: naslov, preverjanje (gostitelj zaprtega shadow DOM) in noga s povezavo - nobenega drugega cilja.
// razred: 'cf-turnstile' (stran sama vstavi preverjanje) ali '' (stran izziva Cloudflare: vsebnik brez razreda).
function stranIzziva(nast, razred) {
    const s = ustvari(nast);
    const naslov = s.el('h1', { okvir: [200, 120, 600, 60], besedilo: 'primer.test' });
    const vsebnik = s.el('div', { razred: razred || '', okvir: [200, 300, 880, 69] });
    const ovoj = s.el('div', { okvir: [200, 300, 880, 69] });
    const gostitelj = s.el('div', { okvir: [200, 300, 880, 69] });
    const polje = s.el('input', { attrs: { type: 'hidden', name: 'cf-turnstile-response', id: 'cf-chl-widget-ab12c_response' }, slog: { display: 'none' } });
    ovoj.dodaj(gostitelj, polje);
    vsebnik.dodaj(ovoj);
    const noga = s.el('div', { razred: 'footer', okvir: [0, 980, 1920, 100] });
    noga.dodaj(s.el('a', { attrs: { href: 'https://www.cloudflare.com/' }, okvir: [800, 1010, 260, 30], besedilo: 'Cloudflare' }));
    s.body.dodaj(naslov, vsebnik, noga);
    s.nalozi(VIR);
    s.izbran = () => s.document.querySelector('.safeer-active-card');
    s.vsebnik = vsebnik;
    return s;
}

// 1) Stran izziva (vsebnik brez razreda): DOL doseze preverjanje, OK = en pravi dotik na potrditvenem polju.
let s = stranIzziva();
preveri('DOL na strani izziva premakne izbiro (1)', 1, s.window._safeer_navigate_spatial('DOWN'));
const izbran = s.izbran();
preveri('izbrano je preverjanje (gostitelj okvirja v zaprtem shadow DOM)', true, !!izbran && s.vsebnik.contains(izbran));
s.window._safeer_click_focused_card();
preveri('OK: natanko en pravi dotik, na potrditvenem polju (22 px od levega roba, sredina visine)', [[222, 334.5]], s.dotiki);
preveri('OK ne sprozi sinteticnega klika na gostitelja', 0, s.kliki.length);
preveri('GOR od preverjanja vrne tipko naslovni vrstici (-1)', -1, s.window._safeer_navigate_spatial('UP'));

// 1b) Vsebnik .cf-turnstile je bil cilj ze prej (po nakljucju: »turns-TILE«), OK pa je tapnil sredino gostitelja
// (640 px), kjer okvirja ni (okvir je 300 px ob levem robu): dotik je sel v prazno.
s = stranIzziva({}, 'cf-turnstile');
preveri('.cf-turnstile: DOL doseze preverjanje', 1, s.window._safeer_navigate_spatial('DOWN'));
s.window._safeer_click_focused_card();
preveri('.cf-turnstile: OK tapne potrditveno polje, ne prazne sredine', [[222, 334.5]], s.dotiki);

// 2) Stran je podrsana, drsenje pa se ne premakne (npr. overflow: hidden): GOR ne sme ostati ujet v drsenju.
s = stranIzziva({ scrollY: 300, visinaStrani: 1600, drsenjeZaklenjeno: true });
preveri('DOL doseze preverjanje tudi na podrsani strani', 1, s.window._safeer_navigate_spatial('DOWN'));
preveri('GOR, ko se stran ne premakne: tipka gre naslovni vrstici (-1), ne izgine', -1, s.window._safeer_navigate_spatial('UP'));

// 3) Stran brez enega cilja (noga ni cilj), podrsana in zaklenjena: GOR ne sme vrniti »porabljeno«.
s = ustvari({ scrollY: 300, visinaStrani: 1600, drsenjeZaklenjeno: true });
s.body.dodaj(s.el('h1', { okvir: [200, 120, 600, 60], besedilo: 'Napis' }));
s.nalozi(VIR);
preveri('stran brez ciljev, drsenje se ne premakne: GOR -> naslovna vrstica (-1)', -1, s.window._safeer_navigate_spatial('UP'));
preveri('stran brez ciljev, drsenje se ne premakne: DOL -> stran sama (-1)', -1, s.window._safeer_navigate_spatial('DOWN'));
// Drsenje, ki se premakne, ostane (dolga stran brez povezav se se vedno bere).
s = ustvari({ scrollY: 300, visinaStrani: 1600 });
s.body.dodaj(s.el('h1', { okvir: [200, 120, 600, 60], besedilo: 'Napis' }));
s.nalozi(VIR);
preveri('stran brez ciljev, ki se da drsati: GOR podrsa (1)', 1, s.window._safeer_navigate_spatial('UP'));
preveri('... in se res premakne', 180, s.window.scrollY);

// 4) Tuj okvir v navadnem DOM (edini element strani): DOL ga doseze, OK je en dotik na sredini, GOR vrne -1.
s = ustvari();
s.body.dodaj(s.el('iframe', { attrs: { src: 'https://challenges.cloudflare.com/cdn-cgi/challenge-platform/x/normal', id: 'cf-chl-widget-x' }, okvir: [810, 500, 300, 65] }));
s.nalozi(VIR);
preveri('stran samo s tujim okvirjem: DOL ga doseze (1)', 1, s.window._safeer_navigate_spatial('DOWN'));
s.window._safeer_click_focused_card();
preveri('OK na okvirju: en pravi dotik', 1, s.dotiki.length);
preveri('GOR od okvirja: naslovna vrstica (-1)', -1, s.window._safeer_navigate_spatial('UP'));
console.log('tv_izziv_test: OK (' + stevilo + ')');
