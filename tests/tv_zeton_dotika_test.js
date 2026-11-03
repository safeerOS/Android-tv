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
s.window._safeer_highlight(gumb);
s.window._safeer_click_focused_card();
preveri('OK: nas skript poslje zeton, ki ga je vstavila aplikacija', [ZETON], s.zetoni);
preveri('OK: dotik sprejet (en, na sredini gumba)', [[250, 130]], s.dotiki);
preveri('ni dodatnega sinteticnega klika', 0, s.kliki.length);

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
preveri('skript nikjer drugje ne klice mostu dotika neposredno', 1, (VIR.match(/\.triggerNativeTap\(/g) || []).length);

// Aplikacija: zeton preveri pred porabo dovoljenja in v konstantnem casu; vstavi ga samo v tv_spatial.js.
const most = fs.readFileSync(path.join(KOREN, 'src/main/kotlin/si/safeer/tv/ChromiumEngineView.kt'), 'utf8');
const f = most.slice(most.indexOf('fun triggerNativeTap('), most.indexOf('fun triggerNativeTap(') + 900);
preveri('triggerNativeTap zahteva zeton', true, /fun triggerNativeTap\(x: Float, y: Float, zeton: String\?\)/.test(f));
preveri('zeton se preveri pred porabiDotik (klic brez zetona dovoljenja ne porabi)', true,
    f.indexOf('zetonVelja(zeton, ZETON_DOTIKA)') > 0 && f.indexOf('zetonVelja(zeton, ZETON_DOTIKA)') < f.indexOf('porabiDotik()'));
const pravila = fs.readFileSync(path.join(KOREN, 'src/main/kotlin/si/safeer/tv/SpletVarnostPravila.kt'), 'utf8');
preveri('primerjava v konstantnem casu (MessageDigest.isEqual)', true, /fun zetonVelja[\s\S]{0,300}MessageDigest\.isEqual/.test(pravila));
const usm = fs.readFileSync(path.join(KOREN, 'src/main/kotlin/si/safeer/tv/UserScriptManager.kt'), 'utf8');
preveri('UserScriptManager vstavi zeton v tv_spatial.js', true,
    /vstaviZeton\(\s*webView\.context\.assets\.open\("tv_spatial\.js"\)[\s\S]{0,120}ChromiumEngineView\.ZETON_DOTIKA\)/.test(usm));
console.log('tv_zeton_dotika_test: OK (' + stevilo + ')');
