#!/usr/bin/env node
// Najdbe pregleda fokusa na spletni strani (assets/tv_spatial.js), 5. 11. 2026:   node tests/tv_pregled_fokusa_test.js [tv_spatial.js]
//
// Vsak razdelek je ena najdba; brez popravka pade (preverjeno z vrnjeno staro razlicico datoteke).
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { ustvari } = require('./tv_lazni_dom');

const VIR = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
let stevilo = 0;
// SAFEER_VSE_NAPAKE=1: izpise vse padle preverbe (za dokaz, da brez popravka pade), sicer ustavi ob prvi.
const VSE = !!process.env.SAFEER_VSE_NAPAKE;
let padle = 0;
function preveri(opis, pricakovano, dobljeno) {
    try { assert.deepStrictEqual(dobljeno, pricakovano, opis); } catch (e) {
        if (!VSE) throw e;
        padle++; console.log('  PADE ' + opis); return;
    }
    stevilo++;
    console.log('  OK   ' + opis);
}
function gumb(s, ime, okvir, o) { return s.el('button', Object.assign({ okvir, besedilo: ime }, o || {})); }
function izbrani(s) { return s.document.querySelectorAll('.safeer-active-card').map((e) => e.innerText.trim() || e.tagName); }
function modalno(s) {
    const okno = s.el('div', { attrs: { role: 'dialog', 'aria-modal': 'true' }, okvir: [480, 270, 960, 540], slog: { position: 'fixed', zIndex: '100' } });
    okno.dodaj(gumb(s, 'V redu', [600, 600, 300, 60]));
    return okno;
}

// 1. Fokus strani brez tipke: en sam fokus (izbrana kartica ga dobi nazaj), obris strani samo brez nase izbire.
{
    const s = ustvari();
    const a = gumb(s, 'A', [100, 100, 300, 60]), b = gumb(s, 'B', [100, 400, 300, 60]);
    s.body.dodaj(a, b);
    s.nalozi(VIR);
    s.izberi(a);
    preveri('1: ob izbiri html.safeer-izbira (obris :focus strani se ne rise)', true, s.document.documentElement.classList.contains('safeer-izbira'));
    b.focus();                                             // stran sama, brez tipke
    preveri('1: fokus strani brez tipke -> fokus nazaj na izbrani kartici', a, s.document.activeElement);
    preveri('1: obroc ostane en, na A (nova kartica brez tipke ni izbrana)', ['A'], izbrani(s));
    const polje = s.el('input', { attrs: { type: 'text' }, okvir: [100, 700, 300, 60] });
    s.body.dodaj(polje);
    polje.focus();
    preveri('1: polje za vnos (CodeRabbit): izbira pociscena, obris strani spet dovoljen', [[], false],
        [izbrani(s), s.document.documentElement.classList.contains('safeer-izbira')]);
}

{
    const s = ustvari();
    const a = gumb(s, 'A', [100, 100, 300, 60]);
    s.body.dodaj(a);
    s.nalozi(VIR);
    s.izberi(a);
    const okno = modalno(s);
    s.body.dodaj(okno);
    const vRedu = okno.children[0];
    vRedu.focus();                                         // stran odpre okno in mu da fokus (brez tipke)
    preveri('1: fokus strani v modalnem oknu: ostane v oknu, kartica za oknom ni vec izbrana', [vRedu, []],
        [s.document.activeElement, izbrani(s)]);
}

// 2. Cilj, ki ga highlightElement zavrne (ostane zunaj pogleda), smeri ne sme ubiti.
{
    const s = ustvari({ visinaStrani: 3000 });
    const a = gumb(s, 'A', [100, 100, 300, 60]);
    const b = gumb(s, 'B', [100, 1200, 300, 60]);          // naravnost spodaj, a zunaj pogleda (scrollIntoView ga ne prinese)
    const c = gumb(s, 'C', [900, 500, 300, 60]);           // vidni, posevno spodaj
    s.body.dodaj(a, b, c);
    s.nalozi(VIR);
    s.izberi(a);
    preveri('2: _safeer_highlight vrne false za cilj zunaj pogleda', false, s.izberi(b));
    s.izberi(a);
    preveri('2: DOL: zavrnjen B izlocen, izbran naslednji (C), vrne 1', [1, ['C']], [s.smer('DOWN'), izbrani(s)]);
}
{
    const s = ustvari({ visinaStrani: 3000, drsenjeZaklenjeno: true });
    const a = gumb(s, 'A', [100, 100, 300, 60]), b = gumb(s, 'B', [100, 1200, 300, 60]);
    s.body.dodaj(a, b);
    s.nalozi(VIR);
    s.izberi(a);
    preveri('2: DOL, edini cilj zavrnjen in stran se ne premakne: -1 (tipka strani), izbira ostane', [-1, ['A']],
        [s.smer('DOWN'), izbrani(s)]);
}

// 3. Vstop v novo plast (casovnik, opazovalec DOM - brez tipke).
{
    const s = ustvari();
    s.body.dodaj(gumb(s, 'A', [100, 100, 300, 60]));
    s.nalozi(VIR);
    const o1 = modalno(s); s.body.dodaj(o1);
    preveri('3: brez izbire: vstop v modalno okno', true, s.vstopi());
    s.window.location.href = 'https://primer.test/?q=1#x';    // stran spremeni samo poizvedbo in #
    o1.slog.display = 'none';
    s.body.dodaj(modalno(s));                                  // in okno izrise na novo
    s.pocisti();                                              // brez izbire: zapira samo kljuc naslova
    preveri('3: isti izvor in pot (drug ? in #): ni drugega vstopa', false, s.vstopi());
}
{
    const s = ustvari();
    const a = gumb(s, 'A', [100, 100, 300, 60]);
    s.body.dodaj(a);
    s.nalozi(VIR);
    s.izberi(a);
    s.body.dodaj(modalno(s));
    preveri('3: izbira obstaja, brez tipke: okno je ne vzame', [false, ['A']], [s.vstopi(), izbrani(s)]);
}
{
    const s = ustvari();
    s.body.dodaj(gumb(s, 'A', [100, 100, 300, 60]));
    const plast = s.el('div', { okvir: [0, 900, 1920, 180], slog: { position: 'fixed', zIndex: '10' } });
    plast.dodaj(gumb(s, 'Accept cookies', [1500, 950, 300, 60]));
    s.body.dodaj(plast);
    s.nalozi(VIR);
    preveri('3: brez tipke: pritrjena plast z besedo cookie (ni modalno okno) ne vzame izbire', false, s.vstopi());
}

// 4. uskladiFokus: v isti plasti/oknu brez blur (fokus ne pade na body); polje za vnos ga izgubi vedno.
{
    const od = VIR.indexOf('/* SAFEER_EN_FOKUS_ZACETEK'), doK = VIR.indexOf('/* SAFEER_EN_FOKUS_KONEC */');
    const { uskladiFokus } = new Function(VIR.slice(od, doK) + '\nreturn { uskladiFokus: uskladiFokus };')();
    const body = { tagName: 'BODY' };
    const doc = { body, documentElement: { tagName: 'HTML' }, activeElement: body };
    const el = (tag, fokusljiv) => ({ tagName: tag, focus() { if (fokusljiv) doc.activeElement = this; },
        blur() { if (doc.activeElement === this) doc.activeElement = body; }, contains(o) { return o === this; }, querySelector() { return null; } });
    const vOknu = el('BUTTON', true), ovoj = el('DIV', false);
    doc.activeElement = vOknu;
    uskladiFokus(ovoj, doc, () => true);
    preveri('4: ista plast: gumb v oknu obdrzi fokus (brez blur)', vOknu, doc.activeElement);
    const polje = el('INPUT', true);
    doc.activeElement = polje;
    uskladiFokus(ovoj, doc, () => true);
    preveri('4: ista plast, a polje za vnos: fokus izgubi (tipkovnica TV)', body, doc.activeElement);
    doc.activeElement = vOknu;
    uskladiFokus(ovoj, doc, () => false);
    preveri('4: druga plast: blur kot prej', body, doc.activeElement);
}

// 5. Senca :focus strani na izbranem elementu izklopljena (samo spletne strani, ne domaca stran file:).
{
    preveri('5: box-shadow:none za .safeer-active-card:focus in :focus v njem, samo zunaj file:', true,
        /if \(location\.protocol !== 'file:'\) \{\s*style\.textContent \+= '\.safeer-active-card:focus,\.safeer-active-card:focus-visible,' \+\s*'\.safeer-active-card :focus,\.safeer-active-card :focus-visible\{box-shadow:none!important\}';/.test(VIR));
    preveri('5: obris :focus strani samo brez nase izbire', true,
        /html:not\(\.safeer-izbira\) :focus:not\(\.safeer-active-card\)/.test(VIR) && /html:not\(\.safeer-izbira\) :focus-visible:not/.test(VIR));
}

// 6. Stran ne more izbrati cilja za pravi dotik (razred, prepis funkcij, neviden okvir).
{
    const s = ustvari();
    const a = gumb(s, 'A', [100, 100, 300, 60]);
    const okvir = s.el('iframe', { razred: 'safeer-active-card', attrs: { src: 'https://tuj.test/' }, okvir: [700, 400, 300, 80] });
    s.body.dodaj(a, okvir);
    s.nalozi(VIR);
    s.ok();
    preveri('6: razred safeer-active-card, ki ga je stran dala svojemu okvirju: ni dotika', [], s.dotiki);
    preveri('6: _safeer_highlight ni vec izvoz (neodvisni pregled 5. 11. 2026)', 'undefined', typeof s.window._safeer_highlight);
    for (const ime of ['_safeer_click_focused_card', '_safeer_navigate_spatial', '_safeer_tipka'].map((x) => x + '_' + s.nonce)) {
        const prej = s.window[ime];
        try { s.window[ime] = function () { return 'datoteka'; }; } catch (_) {}
        try { Object.defineProperty(s.window, ime, { value: function () { return 'datoteka'; } }); } catch (_) {}
        preveri('6: stran ne prepise ' + ime, prej, s.window[ime]);
    }
}
{
    const s = ustvari();
    const okvir = s.el('iframe', { attrs: { src: 'https://tuj.test/' }, okvir: [700, 400, 300, 80], slog: { opacity: '0' } });
    s.body.dodaj(gumb(s, 'A', [100, 100, 300, 60]), okvir);
    s.nalozi(VIR);
    s.izberi(okvir);
    s.ok();
    preveri('6: izbran okvir z opacity 0: ni dotika', [], s.dotiki);
    okvir.slog.opacity = '1';
    s.izberi(okvir);
    s.ok();
    // Neodvisni pregled 5. 11. 2026 (odlocitev B): tuj okvir, ki ni gumb Google, ne dobi pravega dotika.
    preveri('6: viden tuj okvir (ni GSI), ki ga je izbral daljinec: ni dotika', [], s.dotiki);
    okvir.setAttribute('src', 'https://accounts.google.com/gsi/button?x=1');
    s.izberi(okvir);
    s.ok();
    // Tretji neodvisni pregled 5. 11. 2026: pravi dotik v tuj okvir je izklopljen (tudi GSI); preverba vidnosti ostane za
    // ponovni vklop (spodaj z vklopljeno zastavico).
    preveri('6: viden okvir GSI, ki ga je izbral daljinec: proizvodnja brez pravega dotika', [], s.dotiki);
    const s2 = ustvari();
    const o2 = s2.el('iframe', { attrs: { src: 'https://accounts.google.com/gsi/button?x=1' }, okvir: [700, 400, 300, 80] });
    s2.body.dodaj(gumb(s2, 'A', [100, 100, 300, 60]), o2);
    s2.nalozi(VIR.split('var PRAVI_DOTIK_V_OKVIR = false;').join('var PRAVI_DOTIK_V_OKVIR = true;'));
    s2.izberi(o2);
    s2.ok();
    preveri('6: viden okvir GSI (vklopljena zastavica): en dotik na sredini', [[850, 440]], s2.dotiki);
}

// 7. Zastavica namestitve: stran je ne more vnaprej nastaviti ne prepisati; ping aplikacije ostane resnicen.
{
    const s = ustvari();
    s.body.dodaj(gumb(s, 'A', [100, 100, 300, 60]));
    s.window._safeer_tv_remote_installed = true;               // stran pred nasim skriptom
    s.nalozi(VIR);
    const zast = '_safeer_tv_remote_installed_' + s.nonce;
    preveri('7: vnaprej nastavljena zastavica ne prepreci namestitve', 'function', typeof s.izvoz('_safeer_navigate_spatial'));
    preveri('7: ping (UserScriptManager) vidi namescen skript', true, s.window[zast] === true);
    try { s.window[zast] = false; } catch (_) {}
    preveri('7: stran zastavice ne prepise', true, s.window[zast] === true);
    preveri('7: zastavica ne vsebuje zetona', -1, String(s.window[zast]).indexOf(s.zeton));
    let poslusalci = 0;
    const dodaj = s.document.addEventListener;
    s.document.addEventListener = function () { poslusalci++; return dodaj.apply(this, arguments); };
    s.nalozi(VIR);
    preveri('7: drugi vbrizg na isti strani se ne namesti znova', 0, poslusalci);
}

// 8. Izbira na skritem elementu ni izhodisce ne cilj OK.
{
    const s = ustvari();
    const intervali = [];
    s.window.setInterval = (f, ms) => { intervali.push([f, ms]); return 0; };
    const a = gumb(s, 'A', [100, 100, 300, 60]), b = gumb(s, 'B', [100, 300, 300, 60]), c = gumb(s, 'C', [100, 500, 300, 60]);
    s.body.dodaj(a, b, c);
    s.nalozi(VIR);
    s.izberi(b);
    b.slog.opacity = '0';
    preveri('8: izbran element z opacity 0: DOL zacne pri vidnem (prvi zgoraj), ne pri skritem', [1, ['A']],
        [s.smer('DOWN'), izbrani(s)]);
    s.izberi(c);
    c.slog.opacity = '0';
    const sledi = intervali.filter((x) => x[1] === 250).map((x) => x[0]);
    sledi.forEach((f) => f());
    preveri('8: spremljanje obroca odstrani izbiro s prosojnega cilja', [], izbrani(s));
}
{
    const s = ustvari();
    const ovoj = s.el('div', { okvir: [0, 0, 1920, 300] });
    const b = gumb(s, 'B', [100, 100, 300, 60]);
    ovoj.dodaj(b);
    s.body.dodaj(ovoj, gumb(s, 'C', [100, 500, 300, 60]));
    s.nalozi(VIR);
    s.izberi(b);
    ovoj.setAttribute('aria-hidden', 'true');
    s.ok();
    preveri('8: izbran element pod aria-hidden: OK ne sprozi dotika', [[], []], [s.dotiki, s.kliki]);
}
{
    const s = ustvari();
    const a = gumb(s, 'A', [100, 100, 300, 60]);
    s.body.dodaj(a);
    s.nalozi(VIR);
    s.izberi(a);
    s.body.dodaj(modalno(s));
    s.ok();
    preveri('8: izbran element za modalnim oknom: OK ne sprozi dotika', [[], []], [s.dotiki, s.kliki]);
}

// 10. LEVO/DESNO brez cilja: drsenje, ki se ni premaknilo, tipke ne porabi.
{
    const s = ustvari({ drsenjeZaklenjeno: true });
    const a = gumb(s, 'A', [100, 100, 300, 60]);
    s.body.dodaj(a);
    s.nalozi(VIR);
    s.izberi(a);
    preveri('10: DESNO brez cilja, stran se ne premakne: -1', -1, s.smer('RIGHT'));
    s.window.scrollX = 140;
    preveri('10: LEVO na robu (podrsaj), stran se ne premakne: -1', -1, s.smer('LEFT', true));
}
{
    const s = ustvari();
    const a = gumb(s, 'A', [100, 100, 300, 60]);
    s.body.dodaj(a);
    s.nalozi(VIR);
    s.izberi(a);
    preveri('10: DESNO brez cilja, stran se premakne: 0 (kot prej)', 0, s.smer('RIGHT'));
}

// 11. pocistiObroc umakne tudi obroc (ne samo razreda).
{
    const i = VIR.indexOf('function pocistiObroc(dovoljeni) {');
    const j = VIR.indexOf('\n            }\n', i);
    assert.ok(i > 0 && j > i, 'pocistiObroc ni v tv_spatial.js');
    const klici = [];
    const el = { razred: true, classList: { remove() { el.razred = false; }, contains: () => el.razred } };
    const fn = new Function('document', 'izbrani', 'nastaviIzbrani', 'updateFocusRing', VIR.slice(i, j + 14) + '\nreturn pocistiObroc;')(
        { querySelector: () => el }, () => el, () => {}, (x) => klici.push(x));
    fn([]);
    preveri('11: pocistiObroc: razred odstranjen in obroc umaknjen (updateFocusRing(null))', [false, [null]], [el.razred, klici]);
}

// 12. Drugi krog pregleda 5. 11. 2026: izvozi, ki jih doseze tudi stran.
// 12a. Stran sama poklice _safeer_highlight(svoj element) -> pravi OK ne naredi dotika.
{
    const s = ustvari();
    const a = gumb(s, 'A', [100, 100, 300, 60]);
    const okvir = s.el('iframe', { attrs: { src: 'https://tuj.test/' }, okvir: [700, 400, 300, 80] });
    s.body.dodaj(a, okvir);
    s.nalozi(VIR);
    s.izvoz('_safeer_navigate_spatial')('DOWN');              // stran (ime najde v window) - brez dokaza tipke nic
    s.document.activeElement = s.body;
    preveri('12a: stran nima vec _safeer_highlight za izbiro svojega okvirja', 'undefined', typeof s.window._safeer_highlight);
    s.ok();
    preveri('12a: pravi OK po izbiri strani: ni dotika na okvir strani', [], s.dotiki);
    const tile = s.el('div', { razred: 'item--event', okvir: [700, 700, 300, 80], slog: { opacity: '0' } });
    s.body.dodaj(tile);
    s.window._safeer_medij_click_live_tile(tile, 0);
    preveri('12a: _safeer_medij_click_live_tile s skrito ploscico strani: ni izbire', [], izbrani(s));
    preveri('12a: _safeer_izberi_zadnjo_ploscico ne vzame window._safeer_medij_last_tile strani', false,
        (s.window._safeer_medij_last_tile = okvir, s.window._safeer_izberi_zadnjo_ploscico()));
    preveri('12a: izbira, kot jo naredi daljinec (aplikacija z dokazom), deluje', true, s.izberi(a));
    preveri('12a: pocisti (notranje) deluje', [false, []], [s.pocisti(), izbrani(s)]);
}
// 12b. Drugi vbrizg v isti dokument z drugim zetonom -> ni dvojnih poslusalcev, izvozi ostanejo prvi.
{
    const s = ustvari();
    s.body.dodaj(gumb(s, 'A', [100, 100, 300, 60]));
    s.nalozi(VIR);
    const prej = s.izvoz('_safeer_click_focused_card');
    let poslusalci = 0;
    const dodaj = s.document.addEventListener;
    s.document.addEventListener = function () { poslusalci++; return dodaj.apply(this, arguments); };
    s.nalozi(VIR.split('__SAFEER_ZETON_DOTIKA__').join('y'.repeat(48)));
    preveri('12b: drugi vbrizg z drugim zetonom se ne namesti znova (poslusalci)', 0, poslusalci);
    preveri('12b: izvozi ostanejo od prvega vbrizga', prej, s.izvoz('_safeer_click_focused_card'));
}
{
    const s = ustvari();
    s.body.dodaj(gumb(s, 'A', [100, 100, 300, 60]));
    Object.defineProperty(s.window, '_safeer_tv_remote_installed', { value: true, writable: false, configurable: false });
    s.nalozi(VIR);
    preveri('12b: tuja nespremenljiva zastavica brez znaka (true) ne prepreci namestitve', 'function', typeof s.izvoz('_safeer_navigate_spatial'));
}
// 12c. Vnaprej definiran izvoz: aplikacija ne dobi zaupanja vrednega odgovora; nas izvoz da dokaz.
{
    const od = VIR.indexOf('/* SAFEER_ZNAK_NAMESTITVE_ZACETEK'), doK = VIR.indexOf('/* SAFEER_ZNAK_NAMESTITVE_KONEC */');
    const od2 = VIR.indexOf('/* SAFEER_DOKAZ_ZACETEK'), doK2 = VIR.indexOf('/* SAFEER_DOKAZ_KONEC */');
    assert.ok(od2 > 0 && doK2 > od2, 'bloka SAFEER_DOKAZ ni');
    const { dokazDatoteke } = new Function(VIR.slice(od, doK) + VIR.slice(od2, doK2) + '\nreturn { dokazDatoteke };')();
    const IZZIV = '0123456789abcdef0123456789abcdef';
    // Isti primer kot tests/TvTipkeTest.kt (TvTipke.odtisZetona v Kotlinu).
    preveri('12c: odtis kot v Kotlinu (TvTipkeTest)', 'datoteka:safeer-e47329582e82b715def9698cf9bf11cdaac8957ca113435c6f5a6aa2e4a53c99', dokazDatoteke('z'.repeat(48), IZZIV));
    preveri('12c: brez veljavnega izziva ni dokaza', ['', ''], [dokazDatoteke('z'.repeat(48), undefined), dokazDatoteke('z'.repeat(48), 'x')]);
    // Stran je _safeer_click_focused_card zasedla pred nami.
    const s = ustvari();
    const vhod = s.el('input', { attrs: { type: 'file' }, okvir: [100, 100, 300, 60] });
    s.body.dodaj(vhod);
    const zapisi = [];
    s.window.console = { log: (x) => zapisi.push(String(x)), warn() {}, error() {} };
    Object.defineProperty(s.window, '_safeer_click_focused_card', { value: () => 'datoteka', writable: false, configurable: false });
    s.nalozi(VIR);
    // Neodvisni pregled 5. 11. 2026 (najdba 6): nas izvoz ima nakljucni del imena - zasedeno staro ime ga ne doseze.
    s.izberi(vhod);
    preveri('12c: ime strani brez nakljucnega dela ne zasede nasega izvoza: OK da dokaz', dokazDatoteke(s.zeton, IZZIV), s.ok(IZZIV));
    preveri('12c: noben izvoz ni bil zaseden (brez H55)', false, zapisi.some((x) => x.indexOf('H55') === 0));
    // Nas izvoz: polje za datoteko dobi fokus, odgovor je dokaz za ta izziv.
    const s2 = ustvari();
    const vhod2 = s2.el('input', { attrs: { type: 'file' }, okvir: [100, 100, 300, 60] });
    s2.body.dodaj(vhod2);
    s2.nalozi(VIR);
    s2.izberi(vhod2);
    preveri('12c: nas izvoz: OK na polju za datoteko vrne dokaz za izziv', dokazDatoteke(s2.zeton, IZZIV), s2.ok(IZZIV));
}
// 12d. Skrij/vrni obris (MainActivity): element iz zaprtja, ne iz window._safeerObrisZunaj ali razreda strani.
{
    const s = ustvari();
    const a = gumb(s, 'A', [100, 100, 300, 60]), b = gumb(s, 'B', [100, 400, 300, 60]);
    s.body.dodaj(a, b);
    s.nalozi(VIR);
    s.izberi(a);
    const skrij = s.izvoz('_safeer_skrij_obris'), vrni = s.izvoz('_safeer_vrni_obris');
    preveri('12d: skrij obris: izbire ni', [true, []], [skrij(), izbrani(s)]);
    s.window._safeerObrisZunaj = b;                           // stran podtakne svoj element
    preveri('12d: vrni obris: nazaj na A (ne na element strani)', [true, ['A']], [vrni(), izbrani(s)]);
    b.classList.add('safeer-active-card');                    // stran da razred svojemu elementu
    skrij();
    preveri('12d: razred strani ni izbira (skrij vzame A)', [true, ['A']], [vrni(), izbrani(s).filter((x) => x === 'A')]);
    const kt = fs.readFileSync(path.join(__dirname, '..', 'src', 'main', 'kotlin', 'si', 'safeer', 'tv', 'MainActivity.kt'), 'utf8');
    preveri('12d: MainActivity klice izvoza skripta (ne _safeer_highlight)', true,
        kt.indexOf('jsObrisa(od, "_safeer_skrij_obris")') !== -1 && kt.indexOf('jsObrisa(v, "_safeer_vrni_obris")') !== -1 && kt.indexOf('_safeerObrisZunaj=') === -1);
}
// 12e. podrsajOkno: stran s scroll-behavior:smooth se ob 'auto' premakne sele kasneje - meritev je rekla -1 (dvojni premik).
{
    const s = ustvari();
    const a = gumb(s, 'A', [100, 100, 300, 60]);
    s.body.dodaj(a);
    s.nalozi(VIR);
    s.izberi(a);
    const prvotni = s.window.scrollBy;
    s.window.scrollBy = function (o) { if (o && o.behavior === 'instant') return prvotni.call(this, o); s.drsenja.push(['gladko']); };
    preveri('12e: DESNO brez cilja na gladki strani: stran se premakne takoj (0, ne -1)', [0, 140], [s.smer('RIGHT'), s.window.scrollX]);
}
// 12f. Zavrnjen cilj je podrsal stran: polozaj se vrne (brez izbire ne ostane podrsana stran).
{
    const s = ustvari({ visinaStrani: 3000, drsenjeZaklenjeno: true });
    const a = gumb(s, 'A', [100, 100, 300, 60]), b = gumb(s, 'B', [100, 1200, 300, 60]);
    s.body.dodaj(a, b);
    s.window.scrollTo = (o) => { s.window.scrollX = o.left; s.window.scrollY = o.top; };
    b.scrollIntoView = () => { s.window.scrollY += 300; };    // podrsa, B pa ostane zunaj pogleda
    s.nalozi(VIR);
    s.izberi(a);
    preveri('12f: DOL, edini cilj zavrnjen: -1 in stran nazaj na 0', [-1, 0, ['A']], [s.smer('DOWN'), s.window.scrollY, izbrani(s)]);
}
{
    const s = ustvari({ visinaStrani: 3000, drsenjeZaklenjeno: true });
    const b = gumb(s, 'B', [100, 1200, 300, 60]);
    s.body.dodaj(b);
    s.window.scrollTo = (o) => { s.window.scrollX = o.left; s.window.scrollY = o.top; };
    b.scrollIntoView = () => { s.window.scrollY += 300; };
    s.nalozi(VIR);
    preveri('12f: brez izbire (prvi cilji), zavrnjen: -1 in stran nazaj na 0', [-1, 0], [s.smer('DOWN'), s.window.scrollY]);
}
// 12g (CodeRabbit). Zavrnjen cilj je podrsal vsebnik z izbranim elementom: naslednji poskus meri od nove lege.
{
    const s = ustvari({ visinaStrani: 3000 });
    const a = gumb(s, 'A', [100, 500, 300, 60]);
    const b = gumb(s, 'B', [100, 1078, 300, 60]);             // tik pod robom: najblizji, a zunaj pogleda
    const c = gumb(s, 'C', [100, 300, 300, 60]);
    s.body.dodaj(a, b, c);
    b.scrollIntoView = () => { a.okvir = [100, 100, 300, 60]; };   // vsebnik se je premaknil, A je zdaj nad C
    s.nalozi(VIR);
    s.izberi(a);
    preveri('12g: DOL po zavrnjenem B: C (pod novo lego A)', [1, ['C']], [s.smer('DOWN'), izbrani(s)]);
}

// Meritev zbiranja kandidatov (brez spremembe vedenja): dnevnik H54 s trajanjem.
preveri('meritev: H54 zapise trajanje zbiranja kandidatov', true, /_safeerDbg\('H54', 'tv_spatial\.js:kandidati', 'trajanje'/.test(VIR));
if (padle) { console.log('tv_pregled_fokusa_test: PADE (' + padle + ')'); process.exit(1); }
console.log('tv_pregled_fokusa_test: OK (' + stevilo + ')');
