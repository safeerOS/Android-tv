#!/usr/bin/env node
// Neodvisni slepi varnostni pregled 5. 11. 2026 (groznji model: zlonamerna stran z lastnim JS v istem svetu kot
// assets/tv_spatial.js). Vsak primer napada z vidika strani:   node tests/tv_neodvisni_pregled_test.js [pot do tv_spatial.js]
// SAFEER_VSE_NAPAKE=1: izpise vse padle preverbe (dokaz, da brez popravka pade), sicer ustavi ob prvi.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { ustvari } = require('./tv_lazni_dom');

const VIR = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
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
function blok(ime) {
    const i = VIR.indexOf('/* ' + ime + '_ZACETEK'), j = VIR.indexOf('/* ' + ime + '_KONEC */');
    return i >= 0 && j > i ? VIR.slice(i, j) : '';
}
const ZETON = '0123456789abcdef'.repeat(3);
const GSI = 'https://accounts.google.com/gsi/button?x=1';
const gumb = (s, t, o) => s.el('button', { okvir: o, besedilo: t });
const gsi = (s, o) => s.el('iframe', { attrs: { src: GSI }, okvir: o });

// 1. VISOKA: zeton prek Function.prototype.caller in Error.prepareStackTrace + CallSite.getFunction.
primer('1', () => {
    const s = ustvari({ zeton: ZETON });
    s.body.dodaj(gumb(s, 'A', [100, 100, 300, 60]));
    s.izvedi(`window.__viri = [];
        (function () {
            var io = String.prototype.indexOf, ts = Function.prototype.toString;
            String.prototype.indexOf = function ovoj(a, b) {
                try { var c = ovoj.caller, n = 0; while (c && n++ < 8) { window.__viri.push(ts.call(c)); c = c.caller; } } catch (e) {}
                try {
                    var pst = Error.prepareStackTrace;
                    Error.prepareStackTrace = function (e, cs) { return cs.map(function (k) { var f = k.getFunction(); return f ? ts.call(f) : ''; }); };
                    var sklad = new Error().stack;
                    Error.prepareStackTrace = pst;
                    if (Array.isArray(sklad)) for (var i = 0; i < sklad.length; i++) window.__viri.push(sklad[i]);
                } catch (e) {}
                return io.call(this, a, b);
            };
        })();`);
    s.nalozi(VIR);
    s.izberi(s.document.querySelector('button'));
    s.ok();
    const viri = s.window.__viri;
    preveri('1: stran je prepisano metodo res poklicala (preizkus je veljaven)', true, s.izvedi('String.prototype.indexOf.name') === 'ovoj');
    preveri('1: noben vir funkcije, ki ga dobi stran (caller, CallSite.getFunction), ne vsebuje zetona', false,
        viri.some((v) => typeof v === 'string' && v.indexOf(ZETON) !== -1));
    preveri("1: 'use strict' je prvi stavek funkcije skripta", true, /^\(function\(\) \{\s*'use strict';/m.test(VIR.slice(VIR.indexOf('(function() {'))));
});

// 2. VISOKA: TOCTOU - po pravem dotiku nas skript do konca OK ne klice kode strani.
primer('2', () => {
    for (const vrsta of ['gumb', 'gsi']) {
        const s = ustvari({ zeton: ZETON });
        const cilj = vrsta === 'gumb' ? gumb(s, 'A', [100, 100, 300, 60]) : gsi(s, [100, 100, 300, 60]);
        s.body.dodaj(cilj);
        s.nalozi(VIR);
        s.izberi(cilj);
        const dnevnik = [];
        const most = s.window.SafeerBridge, prej = most.triggerNativeTap;
        most.triggerNativeTap = function (x, y, z) { dnevnik.push('dotik'); return prej.call(this, x, y, z); };
        // Stran: vse, kar lahko nas skript poklice po dotiku (agent strani, casovnik, metode niza in elementov).
        s.window.__dn = dnevnik;
        s.izvedi(`window._safeerSiteAgent = { allowBoost: function () { window.__dn.push('stran'); return false; },
                acceptOk: function () { window.__dn.push('stran'); return true; }, markWantPlay: function () { window.__dn.push('stran'); } };
            (function () { var st = window.setTimeout; window.setTimeout = function (f, ms) { window.__dn.push('stran'); return st(f, ms); }; })();
            (function () { var io = String.prototype.indexOf; String.prototype.indexOf = function (a, b) { window.__dn.push('stran'); return io.call(this, a, b); }; })();`);
        s.ok();
        const i = dnevnik.indexOf('dotik');
        preveri('2 (' + vrsta + '): po pravem dotiku ni kode strani', [], i < 0 ? [] : dnevnik.slice(i + 1).filter((x) => x === 'stran'));
    }
    const s = ustvari({ zeton: ZETON });
    const g = gsi(s, [100, 100, 300, 60]);
    s.body.dodaj(g);
    s.nalozi(VIR);
    s.izberi(g);
    s.ok();
    preveri('2: OK na gumbu Google ostane en pravi dotik na sredini', [[250, 130]], s.dotiki);
});

// 3. VISOKA: tuj okvir v (zaprtem) shadow DOM - elementFromPoint vrne gostitelja (DIV), DOM okvirja ne vidi.
primer('3', () => {
    const s = ustvari({ zeton: ZETON });
    const kartica = s.el('div', { razred: 'tile', attrs: { tabindex: '0' }, okvir: [100, 100, 400, 200] });
    kartica.dodaj(s.el('div', { okvir: [100, 100, 400, 200] }));      // gostitelj zaprtega shadow DOM z okvirjem placila
    s.body.dodaj(kartica);
    s.nalozi(VIR);
    s.izberi(kartica);
    s.ok();
    preveri('3: kartica z gostiteljem shadow DOM: ni pravega dotika', [], s.dotiki);
    // Oznaka za skrito polje za datoteko z gostiteljem shadow DOM na sredini.
    const s2 = ustvari({ zeton: ZETON });
    const vhod = s2.el('input', { attrs: { type: 'file' }, okvir: [0, 0, 0, 0], slog: { display: 'none' } });
    const oznaka = s2.el('label', { attrs: { tabindex: '0', role: 'button' }, okvir: [100, 100, 300, 60], besedilo: 'Nalozi' });
    oznaka.control = vhod;
    oznaka.dodaj(vhod, s2.el('span', { okvir: [100, 100, 300, 60] }));
    s2.body.dodaj(oznaka);
    s2.nalozi(VIR);
    s2.izberi(oznaka);
    s2.ok('0123456789abcdef0123456789abcdef');
    preveri('3: oznaka datoteke z gostiteljem shadow DOM (SPAN) na tocki: ni pravega dotika', [], s2.dotiki);
    // Brez gostitelja: oznaka (skrito polje) dobi en pravi dotik - izbirnik datotek se odpre.
    const s3 = ustvari({ zeton: ZETON });
    const vhod3 = s3.el('input', { attrs: { type: 'file' }, okvir: [0, 0, 0, 0], slog: { display: 'none' } });
    const oznaka3 = s3.el('label', { attrs: { tabindex: '0', role: 'button' }, okvir: [100, 100, 300, 60], besedilo: 'Nalozi' });
    oznaka3.control = vhod3;
    oznaka3.dodaj(vhod3);
    s3.body.dodaj(oznaka3);
    s3.nalozi(VIR);
    s3.izberi(oznaka3);
    s3.ok('0123456789abcdef0123456789abcdef');
    preveri('3: oznaka skritega polja za datoteko: en pravi dotik na sredini', [[250, 130]], s3.dotiki);
});

// 4. VISOKA: OK potrdi izbiro, ki jo je dala stran (fokus) - dvojni OK je bil pravi dotik v tuj okvir.
primer('4', () => {
    const s = ustvari({ zeton: ZETON });
    const g = gsi(s, [700, 400, 300, 60]);
    g.setAttribute('tabindex', '0');
    s.body.dodaj(gumb(s, 'A', [100, 100, 300, 60]), g);
    s.nalozi(VIR);
    s.document.activeElement = g;                              // stran ob nalaganju, brez dogodka in brez tipke
    s.ok();
    s.ok();
    preveri('4: dvojni OK na okvirju, ki mu je fokus dala stran: ni pravega dotika', [], s.dotiki);
    s.smer('UP');
    s.smer('DOWN');                                            // uporabnik pride nanj s smerno tipko
    s.ok();
    preveri('4: smerna tipka potrdi izbiro: OK je en pravi dotik', 1, s.dotiki.length);
    preveri('4: _safeer_highlight ni na window (stran z njim brez dokaza cisti izbiro)', 'undefined', typeof s.window._safeer_highlight);
});

// 5. VISOKA/SREDNJA: stran prepise Object.defineProperty pred skriptom in ujame izvoz; starejsi par spet velja.
primer('5', () => {
    const s = ustvari({ zeton: ZETON });
    s.body.dodaj(gumb(s, 'A', [100, 100, 300, 60]));
    s.izvedi(`window.__ujeti = [];
        (function () {
            var dp = Object.defineProperty;
            Object.defineProperty = function (o, k, d) { if (o === window && /^_safeer/.test(String(k))) window.__ujeti.push(String(k)); return dp(o, k, d); };
            ['_safeer_tipka', '_safeer_navigate_spatial', '_safeer_click_focused_card'].forEach(function (ime) {
                dp(window, ime, { configurable: false, get: function () { return undefined; }, set: function (v) { window.__ujeti.push('set:' + ime); } });
            });
        })();`);
    s.nalozi(VIR);
    preveri('5: prepisan Object.defineProperty strani ne vidi nasih izvozov in zastavice', [], Array.from(s.window.__ujeti));
    preveri('5: navigacija dela (vnaprej zasedena imena)', 'function', typeof s.izvoz('_safeer_navigate_spatial'));
    const t = s.izvoz('_safeer_tipka');
    const p1 = s.dokazTipke(1), p2 = s.dokazTipke(2);
    preveri('5: dokaz 1, nato 2', [true, true], [t(...p1), t(...p2)]);
    preveri('5: starejsi par (1) po novejsem ne velja', false, t(...p1));
    preveri('5: isti par (2) ne velja dvakrat', false, t(...p2));
});

// 6. SREDNJA: stran vnaprej zasede imena -> daljinec mrtev.
primer('6', () => {
    const s = ustvari({ zeton: ZETON });
    const a = gumb(s, 'A', [100, 100, 300, 60]), b = gumb(s, 'B', [100, 300, 300, 60]);
    s.body.dodaj(a, b);
    s.izvedi(`['_safeer_tv_remote_installed', '_safeer_navigate_spatial', '_safeer_click_focused_card', '_safeer_tipka'].forEach(function (k) {
        Object.defineProperty(window, k, { value: function () { return 'stran'; }, writable: false, configurable: false }); });`);
    s.nalozi(VIR);
    s.izberi(a);
    s.smer('DOWN');
    const izbrani = s.document.querySelectorAll('.safeer-active-card').map((e) => e.innerText.trim());
    preveri('6: vnaprej zasedena imena: DOL premakne izbiro (daljinec dela)', ['B'], izbrani);
});

// 7. SREDNJA: izvirnikom se zaupa po zastavici pogleda, ne po dokumentu (about:blank, data: - skript zacetka dokumenta ni).
primer('7', () => {
    for (const prot of ['about:', 'data:']) {
        const s = ustvari({ zeton: ZETON, izvirniki: false });
        s.window.location.protocol = prot;
        s.window.location.href = prot === 'about:' ? 'about:blank' : 'data:text/html,x';
        const g = gsi(s, [100, 100, 300, 60]);
        const tuj = s.el('iframe', { attrs: { src: 'https://tuj.test/placaj' }, okvir: [1000, 600, 300, 60] });
        s.body.dodaj(g, tuj);
        // Stran pozna ime iz prejsnjega dokumenta istega pogleda in ponaredi izvirnike: tocka na tujem okvirju.
        s.window.__tuj = tuj;
        s.izvedi(`(function () {
            var lazni = Object.freeze({ apply: Reflect.apply, gbcr: function () { return { l: 1000, t: 600, w: 300, h: 60 }; },
                efp: function () { return this.querySelector('iframe'); }, contains: function () { return true; },
                tag: function () { return 'IFRAME'; }, src: function () { return '${GSI}'; },
                rl: function () { return this.l; }, rt: function () { return this.t; }, rw: function () { return this.w; }, rh: function () { return this.h; },
                dp: Object.defineProperty, gopd: Object.getOwnPropertyDescriptor });
            ['__safeer_izvirniki_${'8796a5b4c3d2e1f0'}', '__safeer_izvirniki'].forEach(function (k) {
                Object.defineProperty(window, k, { value: lazni, writable: false, configurable: false }); });
        })();`);
        s.nalozi(VIR.split("'__SAFEER_IZVIRNIKI__' === 'da'").join("'da' === 'da'"));   // aplikacija: pogled ima registrirane izvirnike
        s.izberi(g);
        s.ok();
        preveri('7 (' + prot + '): ponarejeni izvirniki v dokumentu brez skripta zacetka: ni pravega dotika', [], s.dotiki);
    }
});

// 8. NIZKA: ugibanje dokaza brez meje; tabela znakov z Object.prototype.
primer('8', () => {
    const s = ustvari({ zeton: ZETON });
    const g = gsi(s, [100, 100, 300, 60]);
    s.body.dodaj(gumb(s, 'A', [100, 300, 300, 60]), g);
    s.nalozi(VIR);
    const t = s.izvoz('_safeer_tipka');
    for (let i = 0; i < 6; i++) t('0123456789abcdef0123456789abcdef', 1000 + i, i);     // stran ugiba
    s.izberi(g);
    s.ok();
    preveri('8: po vec napacnih dokazih ni vec pravega dotika v tem dokumentu', [], s.dotiki);
    s.smer('DOWN');
    preveri('8: smer po ugibanju strani se dela (uporabnik ni ujet)', true, s.document.querySelectorAll('.safeer-active-card').length === 1);
    const f = new Function(blok('SAFEER_ZNAK_NAMESTITVE') + blok('SAFEER_DOKAZ') + '\nreturn { jeVeljavenIzziv };')();
    Object.prototype.G = 48;                                   // onesnazenje prototipa (v istem svetu kot tabela)
    let r;
    try { r = f.jeVeljavenIzziv('G'.repeat(32)); } finally { delete Object.prototype.G; }
    preveri('8: Object.prototype ne doda znaka v tabelo', false, r);
});

// 9. NIZKA: ponovni vstop med nasim odzivom na tipko - stran iz poslusalca klika poklice izvoz, ki izbere okno.
primer('9', () => {
    const s = ustvari({ zeton: ZETON });
    const a = gumb(s, 'Odpri', [100, 100, 300, 60]);
    const okno = s.el('div', { attrs: { role: 'dialog', 'aria-modal': 'true' }, okvir: [400, 200, 1100, 700], slog: { position: 'fixed', zIndex: '100' } });
    const g = gsi(s, [700, 400, 300, 60]);
    okno.dodaj(g);
    s.body.dodaj(a);
    s.nalozi(VIR);
    s.izberi(a);
    a.click = function () {                                    // poslusalec klika strani med nasim OK
        s.body.dodaj(okno);
        for (const k of Object.getOwnPropertyNames(s.window)) {
            if (!/safeer/i.test(k) || typeof s.window[k] !== 'function' || /tipka|navigate|click_focused|medij|toggle|seek|next|prev|adjust|show_osd|Dbg|release/.test(k)) continue;
            try { s.window[k](); } catch (_) {}
        }
    };
    s.ok();
    s.ok();
    preveri('9: izbira, ki jo je med OK naredil klic strani, ni potrjena: drugi OK ni pravi dotik', [], s.dotiki);
    preveri('9: _safeer_vstopi_v_plast ni na window', 'undefined', typeof s.window._safeer_vstopi_v_plast);
    // Obramba v globino (bela skatla prek kljuke preizkusa): izvoz, ki ga stran poklice med nasim odzivom na tipko, in
    // samodejni vstop v okno med tipko izbire ne potrdita.
    const s2 = ustvari({ zeton: ZETON });
    const b = gumb(s2, 'B', [100, 300, 300, 60]);
    s2.body.dodaj(gumb(s2, 'A', [100, 100, 300, 60]), b);
    s2.nalozi(VIR);
    const k = s2.window.__preizkus;
    if (k.izvozi) k.izvozi('_preizkus_izberi', (el) => k.oznaci(el));
    const fn = s2.izvoz('_preizkus_izberi');
    k.medTipko(() => fn && fn(b));
    preveri('9: izvoz, poklican med tipko (ponovni vstop strani), izbere brez potrditve', [true, false],
        [s2.document.querySelectorAll('.safeer-active-card').length === 1, s2.potrjena()]);
    const s3 = ustvari({ zeton: ZETON });
    s3.body.dodaj(gumb(s3, 'A', [100, 100, 300, 60]));
    s3.nalozi(VIR);
    const okno3 = s3.el('div', { attrs: { role: 'dialog', 'aria-modal': 'true' }, okvir: [400, 200, 1100, 700], slog: { position: 'fixed', zIndex: '100' } });
    okno3.dodaj(gumb(s3, 'V oknu', [700, 400, 300, 60]));
    s3.body.dodaj(okno3);
    const vstop = s3.window.__preizkus.medTipko(() => s3.vstopi());
    preveri('9: samodejni vstop v okno med tipko: izbira v oknu brez potrditve', [true, false], [vstop, s3.potrjena()]);
});

if (padle) { console.log('tv_neodvisni_pregled_test: PADLO ' + padle + ' (uspelo ' + stevilo + ')'); process.exit(1); }
console.log('tv_neodvisni_pregled_test: OK (' + stevilo + ')');
