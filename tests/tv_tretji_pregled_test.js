#!/usr/bin/env node
// Tretji neodvisni slepi pregled 5. 11. 2026 (groznji model: zlonamerna stran v WebView, assets/tv_spatial.js tece v
// glavnem svetu PO skriptih strani). Vsak primer z vidika napadalca:   node tests/tv_tretji_pregled_test.js [pot do tv_spatial.js]
// SAFEER_VSE_NAPAKE=1: izpise vse padle preverbe (dokaz, da brez popravka pade), sicer ustavi ob prvi.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { ustvari } = require('./tv_lazni_dom');

const KOREN = path.join(__dirname, '..');
const VIR = fs.readFileSync(process.argv[2] || path.join(KOREN, 'assets', 'tv_spatial.js'), 'utf8');
const KT_PRAVILA = fs.readFileSync(path.join(KOREN, 'src/main/kotlin/si/safeer/tv/SpletVarnostPravila.kt'), 'utf8');
const KT_PROFIL = fs.readFileSync(path.join(KOREN, 'src/main/kotlin/si/safeer/tv/tv/SiteProfile.kt'), 'utf8');
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
const ZETON = '0123456789abcdef'.repeat(3);
const IZZIV = '0123456789abcdef0123456789abcdef';
const GSI = 'https://accounts.google.com/gsi/button?x=1';
const RECAPTCHA = 'https://www.google.com/recaptcha/api2/anchor?ar=1&k=6Lc_x&co=x&hl=sl&v=r1&size=normal';
// Oznaka (LABEL) s skritim poljem za datoteko: edini preostali pravi dotik (isti dokument).
function oznakaDatoteke(s, okvir) {
    const vhod = s.el('input', { attrs: { type: 'file' }, okvir: [0, 0, 0, 0], slog: { display: 'none' } });
    const o = s.el('label', { attrs: { tabindex: '0', role: 'button' }, okvir: okvir || [100, 100, 300, 60], besedilo: 'Nalozi' });
    o.control = vhod;
    o.dodaj(vhod);
    return o;
}

// N1 VISOKA: preverba izvora bere atribut src, ne vsebine okvirja: (A) srcdoc z notranjim tujim okvirjem, src = GSI;
// (B) stars navigira otroka prek contentWindow.location, src ostane. Velja za GSI, reCAPTCHA, hCaptcha, Cloudflare.
primer('N1', () => {
    for (const [ime, src, srcdoc] of [['A srcdoc + src GSI', GSI, '<iframe src="https://placilo.napadalec.test/potrdi"></iframe>'],
        ['A srcdoc + src reCAPTCHA', RECAPTCHA, '<iframe src="https://placilo.napadalec.test/potrdi"></iframe>'],
        ['B src GSI, otrok navigiran drugam', GSI, null], ['B src reCAPTCHA, otrok navigiran drugam', RECAPTCHA, null]]) {
        const s = ustvari({ zeton: ZETON });
        const attrs = { src: src, title: 'okvir' };
        if (srcdoc) attrs.srcdoc = srcdoc;
        const o = s.el('iframe', { attrs, okvir: [100, 100, 304, 78] });
        s.body.dodaj(o);
        s.nalozi(VIR);
        // (B): stran po izbiri navigira otroka (v brskalniku: o.contentWindow.location = tuj izvor); atribut src ostane.
        s.izberi(o);
        const odg = s.ok(IZZIV);
        preveri('N1 ' + ime + ': OK ne naredi pravega dotika in ne klice mostu', [[], []], [s.dotiki, s.zetoni]);
        preveri('N1 ' + ime + ': OK vrne namig za kazalec (odtis zetona)', 'kazalec:' + s.odtisZetona('kazalec|' + IZZIV), odg);
    }
    preveri('N1: zastavica PRAVI_DOTIK_V_OKVIR je izklopljena, seznam ostane', [true, true],
        [VIR.indexOf('var PRAVI_DOTIK_V_OKVIR = false;') !== -1, VIR.indexOf('function jeOkvirPotrditve(v)') !== -1]);
});

// N2 VISOKA: stran prepise Array.prototype.indexOf - jeIzziv je vsak element (kartica z gostiteljem shadow DOM s tujim okvirjem).
primer('N2', () => {
    const s = ustvari({ zeton: ZETON });
    const kartica = s.el('div', { razred: 'tile', attrs: { tabindex: '0' }, okvir: [100, 100, 400, 120] });
    kartica.dodaj(s.el('div', { okvir: [100, 100, 400, 120] }));       // gostitelj zaprtega shadow DOM s tujim okvirjem
    s.body.dodaj(kartica);
    s.nalozi(VIR);
    s.izvedi('Array.prototype.indexOf = function () { return 0; };');
    s.izberi(kartica);
    s.ok(IZZIV);
    preveri('N2: prepisan Array.prototype.indexOf: kartica ni izziv - brez pravega dotika, en sinteticni klik', [[], 1], [s.dotiki, s.kliki.length]);
    // Pravi izziv (gostitelj zaprtega shadow DOM, vsebnik s cf-turnstile-response) tudi z vklopljenim indexOf: brez pravega dotika.
    const s2 = ustvari({ zeton: ZETON });
    const v = s2.el('div', { okvir: [200, 300, 880, 69] });
    v.dodaj(s2.el('div', { okvir: [200, 300, 880, 69] }), s2.el('input', { attrs: { type: 'hidden', name: 'cf-turnstile-response' }, slog: { display: 'none' } }));
    s2.body.dodaj(v);
    s2.nalozi(VIR);
    s2.smer('DOWN');
    s2.ok(IZZIV);
    preveri('N2: izziv Cloudflare (gostitelj shadow DOM): ni pravega dotika', [], s2.dotiki);
});

// N3 VISOKA: razkorak med preverbo in pravim dejanjem - stran po nasem odgovoru premakne fokus ali tuj okvir.
primer('N3', () => {
    // (a) Polje za datoteko: OK vrne dokaz za Enter, stran nato da fokus tujemu okvirju (drugIframe.focus()).
    const s = ustvari({ zeton: ZETON });
    const polje = s.el('input', { attrs: { type: 'file' }, okvir: [100, 100, 300, 40] });
    const tuj = s.el('iframe', { attrs: { src: 'https://placilo.napadalec.test/' }, okvir: [900, 500, 300, 60] });
    s.body.dodaj(polje, tuj);
    s.nalozi(VIR);
    s.izberi(polje);
    const odg = s.ok(IZZIV);
    preveri('N3a: OK na polju za datoteko vrne dokaz za Enter', 'datoteka:' + s.odtisZetona('datoteka|' + IZZIV), odg);
    s.document.activeElement = tuj;                              // stran vmes premakne fokus
    preveri('N3a: druga preverba (fokus v tujem okvirju): brez odtisa - aplikacija ne poslje Enter', '', s.preveriCilj(IZZIV));
    // Brez spremembe: odtis velja, enkrat.
    s.izberi(polje);
    s.ok(IZZIV);
    preveri('N3a: druga preverba (isto polje ima fokus): odtis', 'cilj:' + s.odtisZetona('cilj|enter|' + IZZIV), s.preveriCilj(IZZIV));
    preveri('N3a: druga preverba je enkratna', '', s.preveriCilj(IZZIV));
    // Stran sama poklice preverbo (brez dokaza tipke): cilj porabi, odtisa ne dobi.
    s.izberi(polje);
    s.ok(IZZIV);
    preveri('N3a: klic strani brez dokaza tipke ne dobi odtisa', '', s.izvoz('_safeer_preveri_cilj')(IZZIV));
    preveri('N3a: ... in cilja ni vec (aplikacija Enter ne poslje)', '', s.preveriCilj(IZZIV));
    // Onemogoceno polje med preverbama.
    s.izberi(polje);
    s.ok(IZZIV);
    polje.disabled = true;
    preveri('N3a: polje onemogoceno med preverbama: brez odtisa', '', s.preveriCilj(IZZIV));
    polje.disabled = false;
    // Prepozno (po CILJ_ROK_MS): brez odtisa.
    s.izberi(polje);
    s.ok(IZZIV);
    s.ura.t += 2000;
    const f = s.izvoz('_safeer_preveri_cilj');
    const t = s.izvoz('_safeer_tipka');
    t(...s.dokazTipke(s.stevecTipk() + 1));
    preveri('N3a: druga preverba po roku: brez odtisa', '', f(IZZIV));

    // (b) Oznaka skritega polja: pravi dotik; stran po klicu mostu premakne tuj okvir pod tocko.
    const s2 = ustvari({ zeton: ZETON });
    const o = oznakaDatoteke(s2);
    const tuj2 = s2.el('iframe', { attrs: { src: 'https://placilo.napadalec.test/' }, okvir: [900, 500, 300, 60] });
    s2.body.dodaj(o, tuj2);
    s2.nalozi(VIR);
    s2.izberi(o);
    s2.ok(IZZIV);
    preveri('N3b: OK na oznaki: most dotika poklican (aplikacija dotik se zadrzi)', 1, s2.dotiki.length);
    tuj2.okvir = [100, 100, 300, 60];                            // MutationObserver strani: tuj okvir cez oznako
    tuj2.slog = { position: 'absolute', zIndex: '9' };
    preveri('N3b: druga preverba (tuj okvir na tocki): brez odtisa - aplikacija dotika ne izvede', '', s2.preveriCilj(IZZIV));
    tuj2.okvir = [900, 500, 300, 60];
    s2.izberi(o);
    s2.ok(IZZIV);
    preveri('N3b: druga preverba (nic se ni spremenilo): odtis', 'cilj:' + s2.odtisZetona('cilj|dotik|' + IZZIV), s2.preveriCilj(IZZIV));
    // Aplikacija: dotik sele po drugi preverbi; Enter sele po drugi preverbi.
    const tap = KT_POGLED.slice(KT_POGLED.indexOf('fun triggerNativeTap('), KT_POGLED.indexOf('fun triggerNativeTap(') + 4000);
    preveri('N3: triggerNativeTap izvede dotik sele po TvTipke.ciljVelja(..., "dotik")', true,
        tap.indexOf('_safeer_preveri_cilj') > 0 && tap.indexOf('TvTipke.ciljVelja(o, zetonKlica, izziv, "dotik")') > 0 &&
        tap.indexOf('TvTipke.ciljVelja(o, zetonKlica, izziv, "dotik")') < tap.indexOf('dispatchTouchEvent(down)'));
    preveri('N3: Enter na polju za datoteko sele po TvTipke.ciljVelja(..., "enter")', true,
        /ciljVelja\(o2, zeton, izziv2, "enter"\) && wv\.zetonDotika == zeton\) \{\s*nativnaTipka\(wv, event, KeyEvent\.KEYCODE_ENTER\)/.test(KT_PROFIL));
});

// N4 SREDNJA/VISOKA: clickjacking dovoljenih okvirjev (prosojno prekrivalo, lazen obris). Okvirji: zaprto z N1.
// Polje za datoteko: prosojen tuj okvir cez oznako - brez pravega dotika.
primer('N4', () => {
    const s = ustvari({ zeton: ZETON });
    const g = s.el('iframe', { attrs: { src: GSI }, okvir: [100, 100, 300, 60], slog: { opacity: '0.01' } });
    s.body.dodaj(s.el('button', { okvir: [100, 100, 300, 60], besedilo: 'Igraj' }), g);
    s.nalozi(VIR);
    s.izberi(g);
    s.ok(IZZIV);
    preveri('N4: skoraj prosojen okvir GSI cez gumb: ni pravega dotika', [], s.dotiki);
    const s2 = ustvari({ zeton: ZETON });
    const o = oznakaDatoteke(s2);
    const prek = s2.el('iframe', { attrs: { src: 'https://placilo.napadalec.test/' }, okvir: [100, 100, 300, 60], slog: { opacity: '0', position: 'absolute', zIndex: '5' } });
    s2.body.dodaj(o, prek);
    s2.nalozi(VIR);
    s2.izberi(o);
    s2.ok(IZZIV);
    preveri('N4: prosojen tuj okvir cez oznako za datoteko: ni pravega dotika', [], s2.dotiki);
});

// N7 NIZKA: 5 namerno napacnih klicev _safeer_tipka (ime vidno) izklopi prava dejanja; prepisan String.prototype.indexOf.
primer('N7', () => {
    const s = ustvari({ zeton: ZETON });
    const o = oznakaDatoteke(s);
    s.body.dodaj(o);
    s.nalozi(VIR);
    const ime = s.izvedi("Object.getOwnPropertyNames(window).filter(function (k) { return /^_safeer_tipka_/.test(k); })[0]");
    preveri('N7: stran najde ime izvoza (preizkus je veljaven)', true, typeof ime === 'string');
    s.izvedi('for (var i = 0; i < 8; i++) { try { window["' + ime + '"](); } catch (e) {} try { window["' + ime + '"]("x", -1, "y"); } catch (e) {} }');
    s.izberi(o);
    s.ok(IZZIV);
    preveri('N7: 16 neveljavnih klicev (brez izziva, stevca) ne izklopi pravih dejanj', 1, s.dotiki.length);
    const s2 = ustvari({ zeton: ZETON });
    s2.body.dodaj(s2.el('button', { okvir: [100, 100, 300, 60], besedilo: 'A' }));
    s2.izvedi("String.prototype.indexOf = function () { return 0; };");
    s2.nalozi(VIR);
    preveri('N7: prepisan String.prototype.indexOf ne prepreci namestitve (izvoz smeri obstaja)', 'function', typeof s2.izvoz('_safeer_navigate_spatial'));
});

// N8 NIZKA: onPageStarted brez novega dokumenta (204, prenos) - nov nonce, v dokumentu bi tekla druga kopija skripta.
primer('N8', () => {
    const s = ustvari({ zeton: ZETON });
    s.body.dodaj(s.el('button', { okvir: [100, 100, 300, 60], besedilo: 'A' }));
    s.nalozi(VIR);
    const NOV = 'aaaabbbbccccdddd', NOV_Z = 'f'.repeat(48);
    s.naloziZNonce(VIR, NOV, NOV_Z, s.nonce);
    preveri('N8: v istem dokumentu se druga kopija ne namesti (ni izvozov z novim nonce)', ['undefined', 'undefined'],
        [typeof s.window['_safeer_navigate_spatial_' + NOV], typeof s.window['_safeer_tv_remote_installed_' + NOV]]);
    preveri('N8: ziva kopija ostane (izvoz s prejsnjim nonce)', 'function', typeof s.window['_safeer_navigate_spatial_' + s.nonce]);
    // Nov dokument (brez zastavice prejsnje strani): skript se namesti normalno, tudi z nonce prejsnje strani.
    const s2 = ustvari({ zeton: ZETON, prejNonce: '1234123412341234' });
    s2.body.dodaj(s2.el('button', { okvir: [100, 100, 300, 60], besedilo: 'A' }));
    s2.nalozi(VIR);
    preveri('N8: nov dokument z nonce prejsnje strani: skript namescen', 'function', typeof s2.izvoz('_safeer_navigate_spatial'));
    // Aplikacija: izraz zive kopije z izvirnim getOwnPropertyDescriptor (SpletVarnostPravila.zivaKopijaJs).
    const m = KT_PRAVILA.match(/fun zivaKopijaJs\(nonceIzvirnikov: String, prej: String\): String \{[\s\S]*?return ("[\s\S]*?)\n    \}/);
    preveri('N8: zivaKopijaJs je v SpletVarnostPravila', true, !!m);
    const izraz = (ni, pr) => m[1].replace(/" \+\s*"/g, '').replace(/^"|"$/g, '').split('$nonceIzvirnikov').join(ni).split('$prej').join(pr);
    preveri('N8: izraz: ista stran z zivo kopijo -> true', true, s.izvedi(izraz(s.nonceIzvirnikov, s.nonce)));
    const s3 = ustvari({ zeton: ZETON });
    s3.izvedi("Object.getOwnPropertyDescriptor = function () { return { value: true, configurable: false }; };");
    preveri('N8: izraz: stran z laznim getOwnPropertyDescriptor (nov dokument) -> false', false, s3.izvedi(izraz(s3.nonceIzvirnikov, s.nonce)));
    preveri('N8: aplikacija vstavi nonce prejsnje strani in se ob zivi kopiji vrne nanj', true,
        /vstaviPrejsnjiNonce\(/.test(fs.readFileSync(path.join(KOREN, 'src/main/kotlin/si/safeer/tv/UserScriptManager.kt'), 'utf8')) &&
        /zetonDotikaPrej = zetonDotika\s*nonceIzvozovPrej = nonceIzvozov\s*zetonDotika = SpletVarnostPravila\.novZeton\(\)/.test(KT_POGLED));
});

// Namig za kazalec: samo z odtisom, enkrat na stran (aplikacija).
primer('namig', () => {
    preveri('namig: Toast samo z TvTipke.namigKazalca, enkrat na stran (namigKazalcaZa)', true,
        /TvTipke\.namigKazalca\(o, zeton, izziv\) && wv\.zetonDotika == zeton && wv\.namigKazalcaZa != zeton\)/.test(KT_PROFIL) &&
        /wv\.namigKazalcaZa = zeton/.test(KT_PROFIL));
    const jeziki = ['values', 'values-en', 'values-sl', 'values-de', 'values-es', 'values-fr', 'values-it'];
    preveri('namig: besedilo v vseh jezikih vira', jeziki.map(() => true),
        jeziki.map((j) => /name="splet_namig_kazalec">[^<]{20,}</.test(fs.readFileSync(path.join(KOREN, 'res', j, 'splet_strings.xml'), 'utf8'))));
});

if (padle) { console.log('tv_tretji_pregled_test: PADLO ' + padle + ' (uspelo ' + stevilo + ')'); process.exit(1); }
console.log('tv_tretji_pregled_test: OK (' + stevilo + ')');
