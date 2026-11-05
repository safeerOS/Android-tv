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
    s.window._safeer_highlight(a);
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
    s.window._safeer_highlight(a);
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
    s.window._safeer_highlight(a);
    preveri('2: _safeer_highlight vrne false za cilj zunaj pogleda', false, s.window._safeer_highlight(b));
    s.window._safeer_highlight(a);
    preveri('2: DOL: zavrnjen B izlocen, izbran naslednji (C), vrne 1', [1, ['C']], [s.window._safeer_navigate_spatial('DOWN'), izbrani(s)]);
}
{
    const s = ustvari({ visinaStrani: 3000, drsenjeZaklenjeno: true });
    const a = gumb(s, 'A', [100, 100, 300, 60]), b = gumb(s, 'B', [100, 1200, 300, 60]);
    s.body.dodaj(a, b);
    s.nalozi(VIR);
    s.window._safeer_highlight(a);
    preveri('2: DOL, edini cilj zavrnjen in stran se ne premakne: -1 (tipka strani), izbira ostane', [-1, ['A']],
        [s.window._safeer_navigate_spatial('DOWN'), izbrani(s)]);
}

// 3. Vstop v novo plast (casovnik, opazovalec DOM - brez tipke).
{
    const s = ustvari();
    s.body.dodaj(gumb(s, 'A', [100, 100, 300, 60]));
    s.nalozi(VIR);
    const o1 = modalno(s); s.body.dodaj(o1);
    preveri('3: brez izbire: vstop v modalno okno', true, s.window._safeer_vstopi_v_plast());
    s.window.location.href = 'https://primer.test/?q=1#x';    // stran spremeni samo poizvedbo in #
    o1.slog.display = 'none';
    s.body.dodaj(modalno(s));                                  // in okno izrise na novo
    s.window._safeer_highlight(null);                         // brez izbire: zapira samo kljuc naslova
    preveri('3: isti izvor in pot (drug ? in #): ni drugega vstopa', false, s.window._safeer_vstopi_v_plast());
}
{
    const s = ustvari();
    const a = gumb(s, 'A', [100, 100, 300, 60]);
    s.body.dodaj(a);
    s.nalozi(VIR);
    s.window._safeer_highlight(a);
    s.body.dodaj(modalno(s));
    preveri('3: izbira obstaja, brez tipke: okno je ne vzame', [false, ['A']], [s.window._safeer_vstopi_v_plast(), izbrani(s)]);
}
{
    const s = ustvari();
    s.body.dodaj(gumb(s, 'A', [100, 100, 300, 60]));
    const plast = s.el('div', { okvir: [0, 900, 1920, 180], slog: { position: 'fixed', zIndex: '10' } });
    plast.dodaj(gumb(s, 'Accept cookies', [1500, 950, 300, 60]));
    s.body.dodaj(plast);
    s.nalozi(VIR);
    preveri('3: brez tipke: pritrjena plast z besedo cookie (ni modalno okno) ne vzame izbire', false, s.window._safeer_vstopi_v_plast());
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
    s.window._safeer_click_focused_card();
    preveri('6: razred safeer-active-card, ki ga je stran dala svojemu okvirju: ni dotika', [], s.dotiki);
    for (const ime of ['_safeer_click_focused_card', '_safeer_highlight', '_safeer_navigate_spatial']) {
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
    s.window._safeer_highlight(okvir);
    s.window._safeer_click_focused_card();
    preveri('6: izbran okvir z opacity 0: ni dotika', [], s.dotiki);
    okvir.slog.opacity = '1';
    s.window._safeer_highlight(okvir);
    s.window._safeer_click_focused_card();
    preveri('6: viden okvir, ki ga je izbral daljinec: en dotik na sredini', [[850, 440]], s.dotiki);
}

// 7. Zastavica namestitve: stran je ne more vnaprej nastaviti ne prepisati; ping aplikacije ostane resnicen.
{
    const s = ustvari();
    s.body.dodaj(gumb(s, 'A', [100, 100, 300, 60]));
    s.window._safeer_tv_remote_installed = true;               // stran pred nasim skriptom
    s.nalozi(VIR);
    preveri('7: vnaprej nastavljena zastavica ne prepreci namestitve', 'function', typeof s.window._safeer_navigate_spatial);
    preveri('7: ping (UserScriptManager) vidi namescen skript', true, !!s.window._safeer_tv_remote_installed);
    try { s.window._safeer_tv_remote_installed = false; } catch (_) {}
    preveri('7: stran zastavice ne prepise', true, !!s.window._safeer_tv_remote_installed);
    preveri('7: zastavica ne vsebuje zetona', -1, String(s.window._safeer_tv_remote_installed).indexOf(s.zeton));
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
    s.window._safeer_highlight(b);
    b.slog.opacity = '0';
    preveri('8: izbran element z opacity 0: DOL zacne pri vidnem (prvi zgoraj), ne pri skritem', [1, ['A']],
        [s.window._safeer_navigate_spatial('DOWN'), izbrani(s)]);
    s.window._safeer_highlight(c);
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
    s.window._safeer_highlight(b);
    ovoj.setAttribute('aria-hidden', 'true');
    s.window._safeer_click_focused_card();
    preveri('8: izbran element pod aria-hidden: OK ne sprozi dotika', [[], []], [s.dotiki, s.kliki]);
}
{
    const s = ustvari();
    const a = gumb(s, 'A', [100, 100, 300, 60]);
    s.body.dodaj(a);
    s.nalozi(VIR);
    s.window._safeer_highlight(a);
    s.body.dodaj(modalno(s));
    s.window._safeer_click_focused_card();
    preveri('8: izbran element za modalnim oknom: OK ne sprozi dotika', [[], []], [s.dotiki, s.kliki]);
}

// 10. LEVO/DESNO brez cilja: drsenje, ki se ni premaknilo, tipke ne porabi.
{
    const s = ustvari({ drsenjeZaklenjeno: true });
    const a = gumb(s, 'A', [100, 100, 300, 60]);
    s.body.dodaj(a);
    s.nalozi(VIR);
    s.window._safeer_highlight(a);
    preveri('10: DESNO brez cilja, stran se ne premakne: -1', -1, s.window._safeer_navigate_spatial('RIGHT'));
    s.window.scrollX = 140;
    preveri('10: LEVO na robu (podrsaj), stran se ne premakne: -1', -1, s.window._safeer_navigate_spatial('LEFT', true));
}
{
    const s = ustvari();
    const a = gumb(s, 'A', [100, 100, 300, 60]);
    s.body.dodaj(a);
    s.nalozi(VIR);
    s.window._safeer_highlight(a);
    preveri('10: DESNO brez cilja, stran se premakne: 0 (kot prej)', 0, s.window._safeer_navigate_spatial('RIGHT'));
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

// Meritev zbiranja kandidatov (brez spremembe vedenja): dnevnik H54 s trajanjem.
preveri('meritev: H54 zapise trajanje zbiranja kandidatov', true, /_safeerDbg\('H54', 'tv_spatial\.js:kandidati', 'trajanje'/.test(VIR));
if (padle) { console.log('tv_pregled_fokusa_test: PADE (' + padle + ')'); process.exit(1); }
console.log('tv_pregled_fokusa_test: OK (' + stevilo + ')');
