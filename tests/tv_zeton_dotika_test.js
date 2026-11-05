#!/usr/bin/env node
// Pravi dotik daljinca samo z zetonom glavnega okvirja (SAFEER_ZETON_DOTIKA):   node tests/tv_zeton_dotika_test.js
//
// Zakaj (pregled Gemini 3. 11. 2026): SafeerBridge (addJavascriptInterface) vidi vsak okvir strani. Tuj okvir, ki je v
// 1,5 s po OK poklical triggerNativeTap pred nasim skriptom, je porabil dovoljenje tipke in sam izbral tocko dotika.
// Zdaj: nas skript poslje zeton iz zaprtja, aplikacija ga preveri (SpletVarnostPravila.zetonVelja, konstanten cas) PRED
// porabo dovoljenja; zeton ni na window, zato ga okvir ne more prebrati.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { ustvari } = require('./tv_lazni_dom');

const KOREN = path.join(__dirname, '..');
const VIR = fs.readFileSync(process.argv[2] || path.join(KOREN, 'assets', 'tv_spatial.js'), 'utf8');
let stevilo = 0;
function preveri(opis, pricakovano, dobljeno) {
    assert.deepStrictEqual(dobljeno, pricakovano, opis);
    stevilo++;
    console.log('  OK   ' + opis);
}

const ZETON = 'a1b2c3d4'.repeat(6);
const s = ustvari({ zeton: ZETON });
const gumb = s.el('button', { okvir: [100, 100, 300, 60], besedilo: 'Prijava' });
s.body.dodaj(gumb);
s.nalozi(VIR);
s.izberi(gumb);
s.ok();
// Neodvisni pregled 5. 11. 2026 (odlocitev B): navaden gumb dobi sinteticni klik - most dotika (in zeton) se ne uporabi.
preveri('OK na gumbu: brez klica mostu (zeton ne gre nikamor)', [[], [], 1], [s.zetoni, s.dotiki, s.kliki.length]);
// Tretji neodvisni pregled 5. 11. 2026: pravi dotik ostane samo na oznaki skritega polja za datoteko (isti dokument).
const vhod = s.el('input', { attrs: { type: 'file' }, okvir: [0, 0, 0, 0], slog: { display: 'none' } });
const oznaka = s.el('label', { attrs: { tabindex: '0', role: 'button' }, okvir: [100, 300, 300, 60], besedilo: 'Nalozi' });
oznaka.control = vhod;
oznaka.dodaj(vhod);
s.body.dodaj(oznaka);
s.izberi(oznaka);
s.ok('0123456789abcdef0123456789abcdef');
preveri('OK na oznaki datoteke: nas skript poslje zeton, ki ga je vstavila aplikacija', [ZETON], s.zetoni);
preveri('OK na oznaki datoteke: dotik sprejet (en, na sredini)', [[250, 330]], s.dotiki);
preveri('ni dodatnega sinteticnega klika', 1, s.kliki.length);
// Aplikacija pred dotikom cilj preveri znova (_safeer_preveri_cilj): isti cilj - odtis 'cilj|dotik'; drugi klic - nic.
const IZ2 = 'fedcba9876543210fedcba9876543210';
preveri('druga preverba cilja: odtis zetona (cilj se ni spremenil)', 'cilj:' + s.odtisZetona('cilj|dotik|' + IZ2), s.preveriCilj(IZ2));
preveri('druga preverba cilja je enkratna', '', s.preveriCilj(IZ2));

// N5 (tretji neodvisni pregled 5. 11. 2026): stran po nalaganju ovije SafeerBridge.triggerNativeTap in caka na zeton.
{
    const s5 = ustvari({ zeton: ZETON });
    const v5 = s5.el('input', { attrs: { type: 'file' }, okvir: [0, 0, 0, 0], slog: { display: 'none' } });
    const o5 = s5.el('label', { attrs: { tabindex: '0', role: 'button' }, okvir: [100, 300, 300, 60], besedilo: 'Nalozi' });
    o5.control = v5;
    o5.dodaj(v5);
    s5.body.dodaj(o5);
    s5.nalozi(VIR);
    s5.izvedi(`window.__ukradeno = []; (function () { var b = window.SafeerBridge, f = b.triggerNativeTap;
        b.triggerNativeTap = function (x, y, z) { window.__ukradeno.push(z); return f.call(b, x, y, z); }; })();`);
    s5.izberi(o5);
    s5.ok('0123456789abcdef0123456789abcdef');
    preveri('N5: ovita metoda mostu zetona ne dobi (izvirnik z zacetka dokumenta)', [], Array.from(s5.window.__ukradeno));
    preveri('N5: pravi dotik gre skozi izvirni most', 1, s5.dotiki.length);
    preveri('N5: skript ne bere window.SafeerBridge za dotik', false, /SafeerBridge(?:\s*\|\||\.triggerNativeTap)/.test(VIR));
}

// Tuj okvir (ali skript strani) klice most brez zetona ali z ugibanim: dotik zavrnjen.
s.dotiki.length = 0;
preveri('klic brez zetona (stari podpis) zavrnjen', false, s.window.SafeerBridge.triggerNativeTap(5, 5));
preveri('klic z mestom namesto zetona zavrnjen', false, s.window.SafeerBridge.triggerNativeTap(5, 5, '__SAFEER_ZETON_DOTIKA__'));
preveri('noben zavrnjen klic ni dotik', 0, s.dotiki.length);

// Zeton ni dosegljiv drugim: ni na window (okvir istega izvora bi ga prebral), ni v dnevniku.
const vidno = Object.keys(s.window).filter((k) => {
    try { return JSON.stringify(s.window[k] === s.window ? null : s.window[k] || null).indexOf(ZETON) !== -1; } catch (_) { return false; }
});
preveri('zeton ni v nobeni lastnosti window', [], vidno);
preveri('skript klice most dotika samo prek izvirnika (IZ.tap)', [0, 1],
    [(VIR.match(/\.triggerNativeTap\(/g) || []).length, (VIR.match(/IZ\.apply\(IZ\.tap, IZ\.most, \[x, y, ZETON_DOTIKA\]\)/g) || []).length]);

// Aplikacija: zeton preveri pred porabo dovoljenja in v konstantnem casu; vstavi ga samo v tv_spatial.js.
const most = fs.readFileSync(path.join(KOREN, 'src/main/kotlin/si/safeer/tv/ChromiumEngineView.kt'), 'utf8');
const f = most.slice(most.indexOf('fun triggerNativeTap('), most.indexOf('fun triggerNativeTap(') + 900);
preveri('triggerNativeTap zahteva zeton', true, /fun triggerNativeTap\(x: Float, y: Float, zeton: String\?\)/.test(f));
preveri('zeton se preveri pred porabiDotik (klic brez zetona dovoljenja ne porabi)', true,
    f.indexOf('zetonVelja(zeton, pricakovan)') > 0 && f.indexOf('zetonVelja(zeton, pricakovan)') < f.indexOf('porabiDotik()'));
const pravila = fs.readFileSync(path.join(KOREN, 'src/main/kotlin/si/safeer/tv/SpletVarnostPravila.kt'), 'utf8');
preveri('primerjava v konstantnem casu (MessageDigest.isEqual)', true, /fun zetonVelja[\s\S]{0,300}MessageDigest\.isEqual/.test(pravila));
const usm = fs.readFileSync(path.join(KOREN, 'src/main/kotlin/si/safeer/tv/UserScriptManager.kt'), 'utf8');
preveri('UserScriptManager vstavi zeton pogleda v tv_spatial.js ob vsakem vbrizgu', true,
    /vstaviZeton\(predloga, zeton\)/.test(usm) && /zetonDotika/.test(usm));
// Zunanji pregled 3. 11. 2026: stran je pred nasim skriptom zamenjala SafeerBridge; zeton je bil en na proces.
preveri('skript ne bere window.SafeerBridge znova pozneje', false, /if \(!mostDotika\) mostDotika/.test(VIR));
preveri('most zaklenjen ob zacetku dokumenta', true,
    /ZAKLEP_MOSTU_JS[\s\S]{0,400}configurable:false/.test(most) && /(addDocumentStartJavaScript\(this, |dodajSkriptZacetka\()ZAKLEP_MOSTU_JS/.test(most));   // peti slepi pregled (N5): prek dodajSkriptZacetka
preveri('nov zeton ob vsaki strani', true, /onPageStarted[\s\S]{0,300}zetonDotika = SpletVarnostPravila\.novZeton\(\)/.test(most));
preveri('ni vec enega zetona na proces', false, /val ZETON_DOTIKA: String/.test(most));
// Zaklep v pravem pomenu: stran po zaklepu mostu ne more zamenjati.
{
    const ctx = { window: {} };
    ctx.window.top = ctx.window;
    const pravi = { triggerNativeTap: function () { return 'pravi'; } };
    ctx.window.SafeerBridge = pravi;
    const zaklep = most.match(/ZAKLEP_MOSTU_JS = """([\s\S]*?)"""/)[1];
    new Function('window', zaklep)(ctx.window);
    try { ctx.window.SafeerBridge = { triggerNativeTap: function () { return 'ponaredek'; } }; } catch (_) {}
    try { ctx.window.SafeerBridge.triggerNativeTap = function () { return 'ponaredek'; }; } catch (_) {}
    preveri('po zaklepu stran mostu ne zamenja', 'pravi', ctx.window.SafeerBridge.triggerNativeTap());
}
// N5: pravi IZVIRNIKI_JS (ChromiumEngineView) ujame most in metodo pred stranjo; poznejsi prepis strani ju ne spremeni.
{
    const m = most.match(/internal const val IZVIRNIKI_JS = """([\s\S]*?)"""/);
    preveri('IZVIRNIKI_JS je v ChromiumEngineView', true, !!m);
    const g = function () { return 0; };
    const proto = (o) => { const p = {}; Object.keys(o).forEach((k) => Object.defineProperty(p, k, { get: o[k], configurable: true })); return p; };
    const pravi = { triggerNativeTap: function () { return 'pravi'; } };
    const okno = {};
    okno.window = okno; okno.top = okno; okno.SafeerBridge = pravi;
    const imena = ['window', 'Reflect', 'Object', 'DOMRectReadOnly', 'Element', 'Document', 'Node', 'HTMLIFrameElement', 'HTMLLabelElement',
        'HTMLInputElement', 'HTMLElement', 'Performance', 'performance'];
    const vrednosti = [okno, Reflect, Object, { prototype: proto({ left: g, top: g, width: g, height: g }) },
        { prototype: Object.assign(proto({ tagName: g }), { getBoundingClientRect: g }) },
        { prototype: Object.assign(proto({ activeElement: g }), { elementFromPoint: g }) }, { prototype: { contains: g } },
        { prototype: proto({ src: g }) }, { prototype: proto({ control: g }) }, { prototype: proto({ type: g, disabled: g }) },
        { prototype: { focus: g } }, { prototype: { now: g } }, {}];
    const N = '8796a5b4c3d2e1f0';
    new Function(...imena, m[1].split('__SAFEER_NONCE_IZVIRNIKOV__').join(N))(...vrednosti);
    const I = okno['__safeer_izvirniki_' + N];
    pravi.triggerNativeTap = function () { return 'ponaredek'; };               // stran po zacetku dokumenta
    preveri('N5: IZVIRNIKI_JS hrani most in izvirno metodo (prepis strani je ne zamenja)', ['pravi', true, true],
        I ? [typeof I.tap === 'function' ? Reflect.apply(I.tap, I.most, []) : null, I.most === pravi, Object.isFrozen(I)] : null);
}
console.log('tv_zeton_dotika_test: OK (' + stevilo + ')');
