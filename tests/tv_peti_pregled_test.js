#!/usr/bin/env node
// Peti slepi pregled 5. 11. 2026 (N5): ime izvirnikov (__safeer_izvirniki_<n>) se menja ob vsaki strani glavnega okvirja.
// Groznji model: stran prebere ime z Object.getOwnPropertyNames in ga uporabi v poznejsem dokumentu.
//   node tests/tv_peti_pregled_test.js [pot do tv_spatial.js]   (SAFEER_VSE_NAPAKE=1: vse padle preverbe)
// N1 je v tests/google_smer_test.js in GoogleTipkeTest.kt, N2/N3 v GooglePravilaTest.kt, N4 v YoutubeVrstaPravilaTest.kt.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { ustvari } = require('./tv_lazni_dom');

const KOREN = path.join(__dirname, '..');
const VIR = fs.readFileSync(process.argv[2] || path.join(KOREN, 'assets', 'tv_spatial.js'), 'utf8');
const KT_POGLED = fs.readFileSync(path.join(KOREN, 'src/main/kotlin/si/safeer/tv/ChromiumEngineView.kt'), 'utf8');
const KT_USM = fs.readFileSync(path.join(KOREN, 'src/main/kotlin/si/safeer/tv/UserScriptManager.kt'), 'utf8');
const VSE = process.env.SAFEER_VSE_NAPAKE === '1';
let stevilo = 0, padle = 0;
function preveri(opis, pricakovano, dobljeno) {
    try { assert.deepStrictEqual(dobljeno, pricakovano, opis); stevilo++; console.log('  OK   ' + opis); }
    catch (_) { padle++; console.log('  PADLO ' + opis + ' (pricakovano ' + JSON.stringify(pricakovano) + ', dobljeno ' + JSON.stringify(dobljeno) + ')'); if (!VSE) process.exit(1); }
}
function primer(ime, fn) { try { fn(); } catch (e) { padle++; console.log('  PADLO ' + ime + ': ' + (e && e.message)); if (!VSE) process.exit(1); } }
const ZETON = 'c'.repeat(48);

primer('N5 aplikacija', () => {
    const vsiNonce = KT_POGLED.match(/internal (val|var) nonceIzvirnikov: String/);
    preveri('N5: ime izvirnikov ni vec stalno za pogled (var, ne val)', 'var', vsiNonce ? vsiNonce[1] : null);
    const zac = KT_POGLED.slice(KT_POGLED.indexOf('override fun onPageStarted('), KT_POGLED.indexOf('override fun onPageStarted(') + 1600);
    preveri('N5: onPageStarted: dokument dobi ime zadnje registracije, za naslednjega nova registracija', true,
        /nonceIzvirnikovPrej = nonceIzvirnikov\s*nonceIzvirnikov = nonceIzvirnikovNaslednji\s*post \{ if \(!uniceno\) registrirajIzvirnike\(\) \}/.test(zac));
    const i = KT_POGLED.indexOf('private fun registrirajIzvirnike(');
    const reg = i < 0 ? '' : KT_POGLED.slice(i, i + 700);
    preveri('N5: registracija z novim imenom odstrani staro (stara imena ne veljajo vec)', true,
        /SpletVarnostPravila\.novNonce\(\)/.test(reg) && /izvirnikiRocaj\?\.remove\(\)/.test(reg) && /izvirnikiJs\(n\)/.test(reg));
    preveri('N5: ziva kopija se preveri z izvirniki prejsnjega dokumenta', true,
        /SpletVarnostPravila\.zivaKopijaJs\(izPrej, prej\)/.test(KT_USM) && /webView\.nonceIzvirnikovPrej\.ifEmpty/.test(KT_USM));
});

// Ista stran (onPageStarted brez novega dokumenta, N8): dokument ima izvirnike pod STARIM imenom, aplikacija pricakuje novega.
primer('N5 ista stran', () => {
    const s = ustvari({ zeton: ZETON });
    s.body.dodaj(s.el('button', { okvir: [100, 100, 300, 60], besedilo: 'A' }));
    s.nalozi(VIR);
    const NOV = 'aaaabbbbccccdddd', NOV_IZ = '0123012301230123';
    s.naloziZNonce(VIR, NOV, 'd'.repeat(48), s.nonce, NOV_IZ, s.nonceIzvirnikov);
    preveri('N5: druga kopija se v istem dokumentu ne namesti (ziva kopija prek izvirnikov prejsnjega imena)', 'undefined',
        typeof s.window['_safeer_navigate_spatial_' + NOV]);
});

// Nov dokument: stran pozna prejsnje ime izvirnikov in nonce, postavi lazne izvirnike s tem imenom in lazno zastavico.
primer('N5 nov dokument', () => {
    const NA = '0f1e2d3c4b5a6978', STARO_IZ = '5555666677778888';
    const b = ustvari({ zeton: ZETON, nonce: '1122334455667788', prejNonce: NA });
    b.izvedi(`Object.defineProperty(window, '__safeer_izvirniki_${STARO_IZ}', { value: { apply: Reflect.apply,
        gopd: function () { return { value: true, configurable: false }; } } });`);
    b.body.dodaj(b.el('button', { okvir: [100, 100, 300, 60], besedilo: 'B' }));
    b.naloziZNonce(VIR, b.nonce, ZETON, NA, b.nonceIzvirnikov, STARO_IZ);
    preveri('N5: lazni izvirniki prejsnjega imena namestitev le zadrzijo (aplikacija nato preveri izziv)', 'undefined',
        typeof b.window['_safeer_navigate_spatial_' + b.nonce]);
    b.naloziZNonce(VIR, b.nonce, ZETON, '', b.nonceIzvirnikov, '');
    preveri('N5: po zavrnitvi (N-B) skript namescen brez prejsnjega imena', 'function', typeof b.window['_safeer_navigate_spatial_' + b.nonce]);
    preveri('N5: tipka dela', true, b.tipka());
});

// N5: izvirniki morajo teci PRED drugimi skripti zacetka dokumenta - protiadblock.js ovije getBoundingClientRect (ovoj klice
// apply, ki ga stran lahko prepise). Ob menjavi imena aplikacija zato znova registrira vse v istem vrstnem redu.
primer('N5 vrstni red', () => {
    const pab = fs.readFileSync(path.join(KOREN, 'assets/protiadblock.js'), 'utf8');
    preveri('N5: protiadblock.js ovija getBoundingClientRect (zato vrstni red steje)', true, /EP\.getBoundingClientRect = function/.test(pab));
    const i = KT_POGLED.indexOf('private fun registrirajIzvirnike(');
    const reg = i < 0 ? '' : KT_POGLED.slice(i, i + 1400);
    const iIz = reg.indexOf('izvirnikiJs(n)'), iOst = reg.indexOf('for (sk in skriptiZacetka) {\n');
    preveri('N5: ob menjavi najprej izvirniki, nato ostali skripti v prejsnjem vrstnem redu', true,
        /sk\.rocaj\?\.remove\(\)/.test(reg) && iIz > 0 && iOst > iIz && /addDocumentStartJavaScript\(this, sk\.js, sk\.izvori\)/.test(reg));
    preveri('N5: vsi drugi skripti zacetka dokumenta gredo prek dodajSkriptZacetka', 3,
        (KT_POGLED.match(/WebViewCompat\.addDocumentStartJavaScript\(/g) || []).length);
    preveri('N5: GPC, zaklep mostu, protiadblock, YouTube in namizna sirina so na seznamu', 6,
        (KT_POGLED.match(/dodajSkriptZacetka\(/g) || []).length - 1);
});

if (padle) { console.log('tv_peti_pregled_test: PADLO ' + padle + ' (uspelo ' + stevilo + ')'); process.exit(1); }
console.log('tv_peti_pregled_test: OK (' + stevilo + ')');
