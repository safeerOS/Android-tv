#!/usr/bin/env node
// Cetrti slepi pregled 5. 11. 2026 (groznji model: zlonamerna stran v WebView, assets/tv_spatial.js tece v glavnem svetu PO
// skriptih strani). Vsak primer z vidika napadalca:   node tests/tv_cetrti_pregled_test.js [pot do tv_spatial.js]
// N-A (Google nacin, KLIK_FOKUSA) je v tests/google_smer_test.js in tests/GooglePravilaTest.kt, HMAC v tests/tv_hmac_test.js.
// SAFEER_VSE_NAPAKE=1: izpise vse padle preverbe (dokaz, da brez popravka pade), sicer ustavi ob prvi.
'use strict';
const assert = require('assert');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { ustvari } = require('./tv_lazni_dom');

const KOREN = path.join(__dirname, '..');
const VIR = fs.readFileSync(process.argv[2] || path.join(KOREN, 'assets', 'tv_spatial.js'), 'utf8');
const KT_USM = fs.readFileSync(path.join(KOREN, 'src/main/kotlin/si/safeer/tv/UserScriptManager.kt'), 'utf8');
const KT_POGLED = fs.readFileSync(path.join(KOREN, 'src/main/kotlin/si/safeer/tv/ChromiumEngineView.kt'), 'utf8');
const VSE = process.env.SAFEER_VSE_NAPAKE === '1';
let stevilo = 0, padle = 0;
function preveri(opis, pricakovano, dobljeno) {
    try {
        assert.deepStrictEqual(dobljeno, pricakovano, opis);
        stevilo++;
        console.log('  OK   ' + opis);
    } catch (_) {
        padle++;
        console.log('  PADLO ' + opis + ' (pricakovano ' + JSON.stringify(pricakovano) + ', dobljeno ' + JSON.stringify(dobljeno) + ')');
        if (!VSE) process.exit(1);
    }
}
function primer(ime, fn) {
    try { fn(); } catch (e) {
        padle++;
        console.log('  PADLO ' + ime + ': ' + (e && e.message));
        if (!VSE) process.exit(1);
    }
}
// Neodvisna izvedba (node:crypto) - kot bi preverila aplikacija (TvTipke.zivaKopijaVelja, javax.crypto).
const hmac = (z, niz) => crypto.createHmac('sha256', z).update(niz).digest('hex');
const TA = 'a'.repeat(16) + '0123456789abcdef'.repeat(2), TB = 'b'.repeat(48);
const NA = '0f1e2d3c4b5a6978', NB = '1122334455667788';
const IZZIV = '00112233445566778899aabbccddeeff';

// N-B SREDNJA: stran A prebere nonce svoje kopije (Object.getOwnPropertyNames), navigira na B; B postavi zastavico s tem
// imenom in lazen izvoz - prej je aplikacija B vrnila zeton A (orakelj odtisov) in daljinca v B ni namestila.
primer('N-B', () => {
    const a = ustvari({ zeton: TA, nonce: NA });
    a.body.dodaj(a.el('button', { okvir: [100, 100, 300, 60], besedilo: 'A' }));
    a.nalozi(VIR);
    const imena = Object.getOwnPropertyNames(a.window).filter((k) => k.indexOf('_safeer_tv_remote_installed_') === 0);
    preveri('N-B: stran A vidi ime zastavice (nonce ni skrivnost)', ['_safeer_tv_remote_installed_' + NA], imena);
    const ziv = a.window['_safeer_ziv_' + NA];
    preveri('N-B: prava kopija ima izvoz za izziv zive kopije', 'function', typeof ziv);
    preveri('N-B: prava kopija odgovori z odtisom SVOJEGA zetona (aplikacija ga sprejme)', 'ziv:safeer-' + hmac(TA, 'ziv|' + IZZIV),
        typeof ziv === 'function' ? ziv(IZZIV) : undefined);
    preveri('N-B: neveljaven izziv: brez odtisa', ['', ''], typeof ziv === 'function' ? [ziv('x'), ziv(undefined)] : []);
    preveri('N-B: odgovor je samo za ziv| (ne odtis tipke, datoteke ali cilja)', true,
        typeof ziv === 'function' && ziv(IZZIV) !== 'ziv:safeer-' + hmac(TA, 'tipka|1|' + IZZIV) && ziv(IZZIV).indexOf(hmac(TA, 'datoteka|' + IZZIV)) < 0);

    // Dokument B: stran pozna NA, ne pozna TA.
    const b = ustvari({ zeton: TB, nonce: NB, prejNonce: NA });
    b.izvedi(`Object.defineProperty(window, '_safeer_tv_remote_installed_${NA}', { value: true, writable: false, configurable: false });
        window['_safeer_ziv_${NA}'] = function (iz) { window.__ujetIzziv = iz; return 'ziv:safeer-' + '0'.repeat(64); };`);
    b.body.dodaj(b.el('button', { okvir: [100, 100, 300, 60], besedilo: 'B' }));
    b.nalozi(VIR);
    preveri('N-B: z laznim znakom zive kopije se skript (s prejsnjim nonce) ne namesti - zato preverba v aplikaciji',
        'undefined', typeof b.window['_safeer_navigate_spatial_' + NB]);
    const odgovorB = b.window['_safeer_ziv_' + NA](IZZIV);
    preveri('N-B: odgovor strani B ni odtis zetona A (aplikacija ga zavrne)', false, odgovorB === 'ziv:safeer-' + hmac(TA, 'ziv|' + IZZIV));
    // Aplikacija po zavrnitvi prejsnji par pozabi in namesti skript z novim parom brez prejsnjega nonce.
    b.naloziZNonce(VIR, NB, TB, '');
    preveri('N-B: po zavrnitvi: skript namescen z novim nonce', 'function', typeof b.window['_safeer_navigate_spatial_' + NB]);
    preveri('N-B: tipka z zetonom B dela (daljinec ni mrtev)', true, b.tipka());

    // Aplikacija (Kotlin, preverjeno nad izvorom; vedenje v tests/TvTipkeTest.kt zivaKopijaVelja).
    const ping = KT_USM.slice(KT_USM.indexOf('val prejPogoj ='), KT_USM.indexOf('if (odg == "ok")') + 20);
    preveri('N-B: ping poslje nakljucen izziv in poklice _safeer_ziv prejsnjega nonce', true,
        /val izzivZiv = TvTipke\.novIzziv\(\)/.test(ping) && /window\._safeer_ziv_\$prej/.test(ping) && /f\('\$izzivZiv'\)/.test(ping));
    preveri('N-B: vrnitev samo s preverjenim odgovorom; sicer nov skript', true,
        /cev\.vrniPrejsnjoStran\(prej, odg\.removePrefix\("prej:"\), izzivZiv\)/.test(ping) &&
        /lazna ziva kopija[\s\S]*injectSiteScripts\(webView, pageUrl, isDarkMode, finished = true\)/.test(ping));
    preveri('N-B: odgovor pinga po natancni vrednosti (stran ne podtakne "prej:ok")', true,
        !/result\.contains\("ok"\)/.test(ping) && /odg == "ok"/.test(ping));
    const vrni = KT_POGLED.slice(KT_POGLED.indexOf('internal fun vrniPrejsnjoStran('), KT_POGLED.indexOf('internal fun vrniPrejsnjoStran(') + 700);
    preveri('N-B: vrniPrejsnjoStran preveri odtis zetona prejsnje strani in ga ob napaki pozabi', true,
        /TvTipke\.zivaKopijaVelja\(odgovor, zetonDotikaPrej, izziv\)/.test(vrni) && /nonceIzvozovPrej = ""/.test(vrni) && /zetonDotikaPrej = ""/.test(vrni));
});

// N-C SREDNJA: oznaka s skritim poljem za datoteko - pravi dotik velja samo, ce je zadetek na tocki element s seznama
// dovoljenih (besedilo, oznaka); prej seznam prepovedanih: FENCEDFRAME ali drug nov okvir v oznaki je dobil pravi dotik.
function oznakaDatoteke(s) {
    const vhod = s.el('input', { attrs: { type: 'file' }, okvir: [0, 0, 0, 0], slog: { display: 'none' } });
    const o = s.el('label', { attrs: { tabindex: '0', role: 'button' }, okvir: [100, 100, 300, 60], besedilo: 'Nalozi' });
    o.control = vhod;
    o.dodaj(vhod);
    return o;
}
primer('N-C', () => {
    for (const [oz, pricakovano] of [['fencedframe', 0], ['portal', 0], ['span', 0], ['video', 0], ['canvas', 0], ['safeer-x', 0], ['b', 1], ['img', 1]]) {
        const s = ustvari({ zeton: TA });
        const o = oznakaDatoteke(s);
        o.dodaj(s.el(oz, { okvir: [150, 110, 200, 40] }));      // zadetek na sredini oznake
        s.body.dodaj(o);
        s.nalozi(VIR);
        s.izberi(o);
        s.ok(IZZIV);
        preveri('N-C: oznaka datoteke, zadetek ' + oz.toUpperCase() + ': ' + (pricakovano ? 'pravi dotik (besedilo)' : 'brez pravega dotika'),
            pricakovano, s.dotiki.length);
    }
    const s = ustvari({ zeton: TA });
    const o = oznakaDatoteke(s);
    s.body.dodaj(o);
    s.nalozi(VIR);
    s.izberi(o);
    s.ok(IZZIV);
    preveri('N-C: zadetek je oznaka sama: pravi dotik ostane (izbirnik datotek se odpre)', 1, s.dotiki.length);
});

if (padle) { console.log('tv_cetrti_pregled_test: PADLO ' + padle + ' (uspelo ' + stevilo + ')'); process.exit(1); }
console.log('tv_cetrti_pregled_test: OK (' + stevilo + ')');
