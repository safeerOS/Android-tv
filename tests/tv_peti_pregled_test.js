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

// Odlocitev vodje 5. 11. 2026: GOR na glavo strani splosno, brez imen gostiteljev, razredov in besedil dolocenih strani.
primer('glava GOR', () => {
    preveri('brez seznama gostiteljev in posebnosti strani v bloku GOR', [-1, -1, -1],
        ['jeGostiteljTvPonudnika', 'naTvPonudniku', 'snap menu from play'].map((x) => VIR.indexOf(x)));
    const blokGlave = VIR.slice(VIR.indexOf('/* SAFEER_GLAVA_GOR_ZACETEK'), VIR.indexOf('/* SAFEER_GLAVA_GOR_KONEC */'));
    preveri('pravilo glave: samo header, nav, role=banner/navigation', true,
        /GLAVA_IZBIRNIK = 'header a\[href\], header button, nav a\[href\], nav button, \[role="banner"\]/.test(blokGlave));
    preveri('skok na glavo samo brez cilja GOR in na vrhu strani', true,
        /else if \(direction === 'UP'\) \{\s*if \(drsajNotranjiVsebnik\(current, -1\)\) return 1;[\s\S]{0,200}if \(\(window\.scrollY \|\| 0\) <= 20\) \{\s*var vGlavo = ciljVGlavi\(current\);/.test(VIR));
    // Hero z gumbom, nad njim samo glava: GOR na meni.
    const h = ustvari({ zeton: ZETON });
    const glava = h.el('header', { okvir: [0, 0, 1920, 60] });
    glava.dodaj(h.el('a', { attrs: { href: '/meni' }, okvir: [1600, 10, 120, 40], besedilo: 'Meni' }));
    const gumb = h.el('button', { okvir: [100, 500, 300, 60], besedilo: 'Predvajaj' });
    h.body.dodaj(glava, gumb);
    h.nalozi(VIR);
    h.izberi(gumb);
    h.smer('UP');
    preveri('hero: GOR z gumba gre na meni glave', 'Meni', (h.document.querySelector('.safeer-active-card') || {}).innerText);
    // Novice: vrstice med trenutnim elementom in glavo - GOR na naslednjo vrstico, ne na meni (najdba 11).
    const n = ustvari({ zeton: ZETON });
    const g2 = n.el('nav', { okvir: [0, 0, 1920, 60] });
    g2.dodaj(n.el('a', { attrs: { href: '/m' }, okvir: [100, 10, 120, 40], besedilo: 'Meni' }));
    const v1 = n.el('a', { attrs: { href: '/n1' }, okvir: [100, 200, 600, 60], besedilo: 'Novica 1' });
    const v2 = n.el('a', { attrs: { href: '/n2' }, okvir: [100, 400, 600, 60], besedilo: 'Novica 2' });
    const v3 = n.el('a', { attrs: { href: '/n3' }, okvir: [100, 600, 600, 60], besedilo: 'Novica 3' });
    n.body.dodaj(g2, v1, v2, v3);
    n.nalozi(VIR);
    n.izberi(v3);
    n.smer('UP');
    const po1 = (n.document.querySelector('.safeer-active-card') || {}).innerText;
    n.smer('UP');
    preveri('novice: GOR gre vrstico za vrstico (Novica 2, Novica 1), ne na meni', ['Novica 2', 'Novica 1'],
        [po1, (n.document.querySelector('.safeer-active-card') || {}).innerText]);
});

// Odlocitev vodje 5. 11. 2026: brez seznama nedelujocih tokov po naslovu (sprotna zaznava MrtviKanali 24 h ga pokrije).
primer('brez seznama tokov', () => {
    const viri = ['os/KanaliPravila.kt', 'os/MrtviKanali.kt', 'os/GlasbaActivity.kt', 'os/MedijskiViri.kt']
        .map((f) => fs.readFileSync(path.join(KOREN, 'src/main/kotlin/si/safeer/tv', f), 'utf8'));
    preveri('NEDELUJOCI_TOKOVI, znanNedelujocTok in skrijNedelujoc odstranjeni s klici', [false, false, false],
        ['NEDELUJOCI_TOKOVI', 'znanNedelujocTok', 'skrijNedelujoc'].map((x) => viri.some((v) => v.indexOf(x) !== -1)));
    preveri('skrivanje kanala je sprotna oznaka (mrtev 24 h ali rocno skrit)', true,
        /fun skrij\(c: Context, s: Jamendo\.Skladba\): Boolean = jeMrtev\(c, s\.id\)/.test(viri[1]));
});

if (padle) { console.log('tv_peti_pregled_test: PADLO ' + padle + ' (uspelo ' + stevilo + ')'); process.exit(1); }
console.log('tv_peti_pregled_test: OK (' + stevilo + ')');
