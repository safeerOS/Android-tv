#!/usr/bin/env node
// Neodvisni pregled Groka (5. 11. 2026): 13 najdb v daljincu spletnih strani (assets/tv_spatial.js).
//   node tests/tv_pregled_groka_test.js [pot do tv_spatial.js]
// SAFEER_VSE_NAPAKE=1: izpise vse padle preverbe (dokaz, da brez popravka pade), sicer ustavi ob prvi.
//
// Zakaj: skript tece v svetu strani (Android WebView nima locenih svetov, vbrizg z evaluateJavascript pri 35 % nalaganja).
// Stran je zato lahko prepisala metode, ki jih skript klice ob OK (getBoundingClientRect, elementFromPoint, charCodeAt,
// Math.imul, Date.now), klicala izvoze na window in sama izbirala cilj pravega dotika (tudi v tuj okvir).
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { ustvari } = require('./tv_lazni_dom');

const KOREN = path.join(__dirname, '..');
const VIR = fs.readFileSync(process.argv[2] || path.join(KOREN, 'assets', 'tv_spatial.js'), 'utf8');
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
const gumb = (s, t, o, ex) => s.el('button', Object.assign({ okvir: o, besedilo: t }, ex || {}));
const izbrani = (s) => s.document.querySelectorAll('.safeer-active-card').map((e) => e.innerText.trim() || e.tagName);
const ZETON = '0123456789abcdef'.repeat(3);

// 1. VISOKA: stran prepise getBoundingClientRect / elementFromPoint -> pravi dotik na njeni tocki.
primer('1', () => {
    const s = ustvari({ zeton: ZETON });
    const a = gumb(s, 'A', [100, 100, 300, 60]);
    const tuj = s.el('iframe', { attrs: { src: 'https://tuj.test/dovoli' }, okvir: [1000, 600, 200, 100] });
    s.body.dodaj(a, tuj);
    s.nalozi(VIR);
    s.izberi(a);
    // Stran po nalaganju: lazni okvir gumba nad tujim okvirjem, elementFromPoint vedno vrne gumb.
    a.getBoundingClientRect = () => ({ left: 1000, top: 600, width: 200, height: 100, right: 1200, bottom: 700 });
    s.document.elementFromPoint = () => a;
    s.ok();
    preveri('1: prepisan getBoundingClientRect/elementFromPoint: dotik na pravi sredini gumba', [[250, 130]], s.dotiki);
    const s2 = ustvari({ zeton: ZETON, izvirniki: false });
    const b = gumb(s2, 'B', [100, 100, 300, 60]);
    s2.body.dodaj(b);
    s2.nalozi(VIR);
    s2.izberi(b);
    s2.ok();
    preveri('1: brez izvirnikov z zacetka dokumenta ni pravega dotika (samo klik strani)', [[], 1], [s2.dotiki, s2.kliki.length]);
});

// 2. VISOKA: zeton prek prepisanih String.prototype.charCodeAt / Math.imul / Number.prototype.toString.
primer('2', () => {
    const s = ustvari({ zeton: ZETON });
    s.body.dodaj(gumb(s, 'A', [100, 100, 300, 60]), s.el('input', { attrs: { type: 'file' }, okvir: [100, 300, 300, 60] }));
    s.izvedi(`window.__ujeto = { niz: [], imul: 0, stevila: [] };
        (function () {
            var cc = String.prototype.charCodeAt, im = Math.imul, ts = Number.prototype.toString;
            String.prototype.charCodeAt = function (i) { window.__ujeto.niz.push(String(this)); return cc.call(this, i); };
            Math.imul = function (x, y) { window.__ujeto.imul++; return im(x, y); };
            Number.prototype.toString = function (r) { window.__ujeto.stevila.push(Number(this)); return ts.call(this, r); };
        })();`);
    s.nalozi(VIR);
    s.izberi(s.document.querySelector('button'));
    s.ok('0123456789abcdef0123456789abcdef');
    s.izberi(s.document.querySelector('input'));
    s.ok('0123456789abcdef0123456789abcdef');
    const u = s.window.__ujeto;
    preveri('2: prepisan charCodeAt nikoli ne vidi zetona', false, u.niz.some((v) => v.indexOf(ZETON) !== -1));
    preveri('2: prepisan Math.imul se ne klice (mnozenje z operatorji)', 0, u.imul);
    const ts = Number.prototype.toString;
    // Stran iz ujetih stevil sestavi staticni dokaz izbire in z njim izbere svoj okvir (najdba 9).
    const okvir = s.el('iframe', { attrs: { src: 'https://tuj.test/' }, okvir: [700, 500, 300, 80] });
    s.body.dodaj(okvir);
    s.window._safeer_highlight(okvir, 'x');
    const ponaredki = s.window.__ujeto.stevila.map((n) => 'safeer-' + ts.call(n, 36));
    preveri('9: ujeta stevila ne dajo dokaza za izbiro okvirja strani', false, ponaredki.some((d) => s.window._safeer_highlight(okvir, d) === true));
    preveri('9: brez dokaza ni gole vrednosti "datoteka"', false, /\|\| 'datoteka'/.test(VIR));
});

// 3. VISOKA: fokus, ki ga je dala stran, postane cilj OK - pravi dotik v tuj okvir v vsebniku.
primer('3', () => {
    const s = ustvari({ zeton: ZETON });
    const vsebnik = s.el('div', { attrs: { tabindex: '0' }, okvir: [600, 300, 400, 200] });
    vsebnik.dodaj(s.el('iframe', { attrs: { src: 'https://placilo.test/potrdi' }, okvir: [600, 300, 400, 200] }));
    s.body.dodaj(gumb(s, 'A', [100, 100, 300, 60]), vsebnik);
    s.nalozi(VIR);
    vsebnik.focus();                                          // stran ob nalaganju, brez tipke
    s.ok();
    preveri('3: prvi OK na fokusu strani: ni pravega dotika v tuj okvir', [], s.dotiki);
    s.ok();
    preveri('3: tudi drugi OK: tuj okvir v vsebniku ni cilj pravega dotika', [], s.dotiki);
    const s2 = ustvari({ zeton: ZETON });
    const b = gumb(s2, 'B', [100, 100, 300, 60]);
    s2.body.dodaj(b);
    s2.nalozi(VIR);
    s2.document.activeElement = b;                            // fokus strani brez dogodka, izbire ni
    s2.ok();
    preveri('3: brez izbire prvi OK samo izbere (brez dotika in klika)', [['B'], [], 0], [izbrani(s2), s2.dotiki, s2.kliki.length]);
    s2.ok();
    preveri('3: drugi OK na zdaj potrjeni izbiri: en pravi dotik', [[250, 130]], s2.dotiki);
});

// 4. SREDNJA: lazni .cf-turnstile s tujim okvirjem v sebi.
primer('4', () => {
    const s = ustvari({ zeton: ZETON });
    const zgoraj = gumb(s, 'Zgoraj', [100, 20, 300, 50]);
    const lazni = s.el('div', { razred: 'cf-turnstile', okvir: [100, 100, 300, 65] });
    lazni.dodaj(s.el('iframe', { attrs: { src: 'https://tuj.test/dovoli' }, okvir: [100, 100, 300, 65] }));
    s.body.dodaj(zgoraj, lazni);
    s.nalozi(VIR);
    s.izberi(zgoraj);
    s.smer('DOWN');
    s.ok();
    preveri('4: lazni izziv s tujim okvirjem: ni pravega dotika', [], s.dotiki);
    const s2 = ustvari({ zeton: ZETON });
    const z2 = gumb(s2, 'Zgoraj', [100, 20, 300, 50]);
    const pravi = s2.el('div', { razred: 'cf-turnstile', okvir: [100, 100, 300, 65] });
    pravi.dodaj(s2.el('iframe', { attrs: { src: 'https://challenges.cloudflare.com/cdn-cgi/challenge-platform/x' }, okvir: [100, 100, 300, 65] }));
    s2.body.dodaj(z2, pravi);
    s2.nalozi(VIR);
    s2.izberi(z2);
    s2.smer('DOWN');
    s2.ok();
    preveri('4: okvir challenges.cloudflare.com v izzivu: en dotik na potrditveno polje', [[122, 132.5]], s2.dotiki);
});

// 5. SREDNJA: stran zaklene zastavico 'safeer-x' -> daljinec mrtev.
primer('5', () => {
    const s = ustvari({ zeton: ZETON });
    s.body.dodaj(gumb(s, 'A', [100, 100, 300, 60]));
    Object.defineProperty(s.window, '_safeer_tv_remote_installed', { value: 'safeer-x', writable: false, configurable: false });
    s.nalozi(VIR);
    preveri('5: tuja zastavica s predpono safeer- ne prepreci namestitve', 'function', typeof s.window._safeer_navigate_spatial);
});

// 6. SREDNJA: predpomnilnik kandidatov na window.
primer('6', () => {
    const s = ustvari({ zeton: ZETON });
    const a = gumb(s, 'A', [100, 100, 300, 60]);
    const skrit = gumb(s, 'Skrit', [100, 200, 300, 60], { slog: { opacity: '0' } });
    const b = gumb(s, 'B', [100, 400, 300, 60]);
    s.body.dodaj(a, skrit, b);
    s.nalozi(VIR);
    s.izberi(a);
    s.window._safeerCandCache = [a, skrit];
    s.window._safeerCandAt = 1e15;
    s.smer('DOWN');
    preveri('6: seznam kandidatov strani na window ne velja', ['B'], izbrani(s));
});

// 7. SREDNJA: izvozi na window premaknejo izbiro brez tipke.
primer('7', () => {
    const s = ustvari({ zeton: ZETON });
    const a = gumb(s, 'A', [100, 100, 300, 60]);
    const b = gumb(s, 'B', [100, 300, 300, 60]);
    s.body.dodaj(a, b);
    s.nalozi(VIR);
    s.izberi(a);
    s.window._safeer_navigate_spatial('DOWN');                // stran, brez dokaza tipke
    preveri('7: _safeer_navigate_spatial brez tipke ne premakne izbire', ['A'], izbrani(s));
    s.window._safeer_click_focused_card();
    preveri('7: _safeer_click_focused_card brez tipke ne klikne', [[], 0], [s.dotiki, s.kliki.length]);
    preveri('7: _safeer_medij_ensure_focus ni vec na window', 'undefined', typeof s.window._safeer_medij_ensure_focus);
    preveri('7: rocno izmisljen dokaz tipke ne velja', false, s.window._safeer_tipka('0123456789abcdef0123456789abcdef', 12345));
    s.window._safeer_medij_click_live_tile(b, 0);             // stran poda svoj element
    s.kliki.length = 0;
    s.ok();
    preveri('7: OK na ploscici, ki jo je izbrala stran: ni pravega dotika', [], s.dotiki);
});

// 8. SREDNJA: en OK, dva prava dotika.
primer('8', () => {
    const s = ustvari({ zeton: ZETON });
    const kartica = s.el('div', { razred: 'tile', attrs: { tabindex: '0' }, okvir: [100, 100, 400, 300] });
    kartica.dodaj(s.el('button', { okvir: [420, 110, 70, 40], besedilo: 'Priljubljeno' }));
    s.body.dodaj(kartica);
    s.nalozi(VIR);
    s.izberi(kartica);
    s.ok();
    preveri('8: en OK = en pravi dotik', 1, s.dotiki.length);
});

// 10. NIZKA: Date.now iz strani obide pravilo »brez tipke se izbira ne premakne«.
primer('10', () => {
    const s = ustvari({ zeton: ZETON });
    s.izvedi('Date.now = function () { return 5000; };');
    const z = gumb(s, 'Z', [100, 20, 300, 50]);
    const a = gumb(s, 'A', [100, 100, 300, 60]);
    const b = gumb(s, 'B', [100, 300, 300, 60]);
    s.body.dodaj(z, a, b);
    s.nalozi(VIR);
    s.izberi(z);
    s.smer('DOWN');
    s.ura.t += 10000;                                         // 10 s brez tipke
    b.focus();                                                // stran
    preveri('10: prepisan Date.now: fokus strani 10 s po tipki ne premakne izbire', ['A'], izbrani(s));
});

// 11. NIZKA: GOR skoci na »Glej zdaj« / meni na vsaki strani.
primer('11', () => {
    for (const [host, pric] of [['novice.test', 'Vmes'], ['www.xploretv.si', 'Glej zdaj']]) {
        const s = ustvari({ zeton: ZETON });
        s.window.location.hostname = host;
        const glej = gumb(s, 'Glej zdaj', [100, 100, 300, 60]);
        const vmes = gumb(s, 'Vmes', [100, 300, 300, 60]);
        const spodaj = gumb(s, 'Spodaj', [100, 500, 300, 60]);
        s.body.dodaj(glej, vmes, spodaj);
        s.nalozi(VIR);
        s.izberi(spodaj);
        s.smer('UP');
        preveri('11: GOR na ' + host, [pric], izbrani(s));
    }
});

// 12. NIZKA: prepir s pastjo fokusa strani.
primer('12', () => {
    const s = ustvari({ zeton: ZETON });
    const a = gumb(s, 'A', [100, 100, 300, 60]);
    const b = gumb(s, 'B', [100, 300, 300, 60]);
    s.body.dodaj(a, b);
    s.nalozi(VIR);
    s.izberi(a);
    for (let i = 0; i < 6; i++) b.focus();                    // past fokusa strani vraca fokus na B
    preveri('12: po vec vrnitvah fokusa izbiro pocistimo, fokus ostane strani', [[], 'B'], [izbrani(s), s.document.activeElement.innerText.trim()]);
});

// Ciste odlocitve.
primer('ciste', () => {
    const f = new Function(blok('SAFEER_PRAVI_DOTIK') + blok('SAFEER_PREPIR_FOKUSA') + blok('SAFEER_TV_PONUDNIK') +
        '\nreturn { smePraviDotik, smemVrnitiFokus, jeGostiteljTvPonudnika };')();
    const o = { izvirniki: true, potrjena: true, vCilju: true, zadetekJeCilj: false, zadetekJeOkvir: false, okvirDovoljen: false };
    preveri('cisto: vse izpolnjeno', true, f.smePraviDotik(o));
    preveri('cisto: brez izvirnikov ne', false, f.smePraviDotik(Object.assign({}, o, { izvirniki: false })));
    preveri('cisto: nepotrjena izbira ne', false, f.smePraviDotik(Object.assign({}, o, { potrjena: false })));
    preveri('cisto: zadetek izven cilja ne', false, f.smePraviDotik(Object.assign({}, o, { vCilju: false })));
    preveri('cisto: tuj okvir v cilju ne', false, f.smePraviDotik(Object.assign({}, o, { zadetekJeOkvir: true })));
    preveri('cisto: izbran okvir sam da', true, f.smePraviDotik(Object.assign({}, o, { zadetekJeOkvir: true, zadetekJeCilj: true })));
    const st = { zac: 0, n: 0 };
    preveri('cisto: vrnitev fokusa 1, 2 da, 3 ne, po 1 s spet da', [true, true, false, true],
        [f.smemVrnitiFokus(st, 100), f.smemVrnitiFokus(st, 200), f.smemVrnitiFokus(st, 300), f.smemVrnitiFokus(st, 1500)]);
    preveri('cisto: gostitelji TV ponudnikov', [true, true, true, false, false],
        ['www.xploretv.si', 'xploretv.si', '365.rtvslo.si', 'lazniexploretv.si', 'rtvslo.si.tuj.test'].map(f.jeGostiteljTvPonudnika));
    // Isti odtis kot Kotlin (TvTipkeTest: odtisStevilo, znakNamestitve) in neodvisna izvedba v tv_lazni_dom.js.
    const d = new Function(blok('SAFEER_ZNAK_NAMESTITVE') + blok('SAFEER_DOKAZ') + '\nreturn { odtisStevilo, znakNamestitve, dokazDatoteke };')();
    const IZ = '0123456789abcdef0123456789abcdef';
    const s = ustvari();
    preveri('cisto: odtis tipke kot neodvisna izvedba', s.odtisStevilo('z'.repeat(48), 'tipka|' + IZ), d.odtisStevilo('z'.repeat(48), 'tipka|' + IZ));
    preveri('cisto: odtis tipke kot Kotlin (TvTipkeTest)', 2855865788, d.odtisStevilo('z'.repeat(48), 'tipka|' + IZ));
    preveri('cisto: znak namestitve kot Kotlin (TvTipkeTest)', 'safeer-pjd3yd', d.znakNamestitve('z'.repeat(48)));
    preveri('cisto: dokaz datoteke kot Kotlin (TvTipkeTest)', 'datoteka:safeer-cg41mu', d.dokazDatoteke('z'.repeat(48), IZ));
});

// 13. NIZKA: klicatelj prviFokusMenija ob -1 da fokus »Preklici« (Kotlin, PokrajinaActivity.pokaziMeni).
{
    const pa = fs.readFileSync(path.join(KOREN, 'src/main/kotlin/si/safeer/tv/os/PokrajinaActivity.kt'), 'utf8');
    preveri('13: -1 (vse nevarne ali prazno) -> Preklici', true,
        /if \(prvi >= 0 && seznam != null\) \{[^}]*\}\s*else okno\.getButton\(android\.content\.DialogInterface\.BUTTON_NEGATIVE\)\?\.requestFocus\(\)/.test(pa));
}

// Aplikacija: izvirniki ob zacetku dokumenta pred drugimi skripti, dokaz tipke pred vsakim klicem ob tipki, tocen znak v pingu.
primer('aplikacija', () => {
    const vm = require('vm');
    const beri = (f) => fs.readFileSync(path.join(KOREN, f), 'utf8');
    const cev = beri('src/main/kotlin/si/safeer/tv/ChromiumEngineView.kt');
    const iIzv = cev.indexOf('addDocumentStartJavaScript(this, IZVIRNIKI_JS, setOf("*"))'), iGpc = cev.indexOf('UserScriptManager.GPC_AND_DNT_JS,');
    preveri('aplikacija: IZVIRNIKI_JS registriran pred drugimi skripti zacetka dokumenta', true, iIzv > 0 && iIzv < iGpc);
    const m = cev.match(/IZVIRNIKI_JS = """([\s\S]*?)"""/);
    preveri('aplikacija: IZVIRNIKI_JS obstaja', true, !!m);
    const ctx = vm.createContext({});
    vm.runInContext("var window = this; window.top = window;" +
        "function Element() {} Element.prototype.getBoundingClientRect = function () { return 'izvirnik'; };" +
        "Object.defineProperty(Element.prototype, 'tagName', { get: function () { return 'T'; } });" +
        "function Document() {} Document.prototype.elementFromPoint = function () {}; function Node() {} Node.prototype.contains = function () {};" +
        "function HTMLIFrameElement() {} Object.defineProperty(HTMLIFrameElement.prototype, 'src', { get: function () { return ''; } });" +
        "function DOMRectReadOnly() {} ['left', 'top', 'width', 'height'].forEach(function (k) { Object.defineProperty(DOMRectReadOnly.prototype, k, { get: function () { return 1; } }); });" +
        "function Performance() {} Performance.prototype.now = function () { return 7; }; var performance = new Performance();", ctx);
    vm.runInContext(m[1], ctx);
    vm.runInContext("Element.prototype.getBoundingClientRect = function () { return 'stran'; };" +
        "try { window.__safeer_izvirniki = null; } catch (e) {} try { Object.defineProperty(window, '__safeer_izvirniki', { value: {} }); } catch (e) {}" +
        "try { window.__safeer_izvirniki.gbcr = function () { return 'stran'; }; } catch (e) {}", ctx);
    preveri('IZVIRNIKI_JS: stran po zacetku dokumenta izvirnikov ne zamenja', 'izvirnik', vm.runInContext('Reflect.apply(window.__safeer_izvirniki.gbcr, {}, [])', ctx));
    preveri('IZVIRNIKI_JS: vsi izvirniki, ki jih rabi tv_spatial.js', 'function,function,function,function,function,function,function,function,function',
        vm.runInContext("['apply','gbcr','efp','contains','tag','rl','rt','rw','rh'].map(function (k) { return typeof window.__safeer_izvirniki[k]; }).join()", ctx));
    const sp = beri('src/main/kotlin/si/safeer/tv/tv/SiteProfile.kt');
    preveri('SiteProfile: vsak klic smeri in OK z dokazom tipke', 4, (sp.match(/ukazTipke\(wv\) \+ "window\._safeer_(navigate_spatial|click_focused_card)\(/g) || []).length);
    preveri('SiteProfile: noben klic brez dokaza', 0, (sp.match(/evaluateJavascript\("window\._safeer_(navigate_spatial|click_focused_card)\(/g) || []).length);
    const usm = beri('src/main/kotlin/si/safeer/tv/UserScriptManager.kt');
    preveri('UserScriptManager: ping primerja tocen znak, izvirniki vstavljeni', true,
        /window\._safeer_tv_remote_installed === '\$znak'/.test(usm) && /vstaviIzvirnike\(SpletVarnostPravila\.vstaviZeton\(predloga, zeton\), izvirniki\)/.test(usm));
});

if (padle) { console.log('tv_pregled_groka_test: PADLO ' + padle + ' (uspelo ' + stevilo + ')'); process.exit(1); }
console.log('tv_pregled_groka_test: OK (' + stevilo + ')');
