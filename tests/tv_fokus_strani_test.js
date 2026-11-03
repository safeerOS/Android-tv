#!/usr/bin/env node
// Obroc sledi fokusu, ki ga premakne stran (assets/tv_spatial.js, SAFEER_FOKUS_STRANI):   node tests/tv_fokus_strani_test.js
//
// Zakaj (lastnik 2. 11. 2026 19:09, claude.ai/new na TV): »Search« v stranski vrstici odpre pogovorno okno, stran sama
// da fokus polju v njem (tipkovnica TV gor). Nas obroc je ostal na »Search« v stranski vrstici in segal cez gumba »All«
// in »Artifacts« v oknu. Ko stran premakne fokus, mora obroc za njim - na polju za vnos pa se skrije.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');

const VIR = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
const od = VIR.indexOf('/* SAFEER_FOKUS_STRANI_ZACETEK');
const doKonca = VIR.indexOf('/* SAFEER_FOKUS_STRANI_KONEC */');
assert.ok(od > 0 && doKonca > od, 'bloka SAFEER_FOKUS_STRANI ni v tv_spatial.js (obroc ne sledi fokusu strani)');
const { jeVnosnoPolje, naFokusStrani } = new Function(VIR.slice(od, doKonca) +
    '\nreturn { jeVnosnoPolje: jeVnosnoPolje, naFokusStrani: naFokusStrani };')();

let stevilo = 0;
function preveri(opis, pricakovano, dobljeno) {
    assert.strictEqual(dobljeno, pricakovano, opis);
    stevilo++;
    console.log('  OK   ' + opis);
}

// Ponarejen DOM: samo to, kar blok uporablja.
const vsi = [];
const body = { tagName: 'BODY', nodeType: 1 };
const doc = {
    body, documentElement: { tagName: 'HTML', nodeType: 1 },
    querySelector: (s) => (s === '.safeer-active-card' ? vsi.find((x) => x.razred.has('safeer-active-card')) || null : null),
};
function el(tag, lastnosti, otroci) {
    const x = Object.assign({
        tagName: tag, nodeType: 1, otroci: otroci || [], razred: new Set(), atributi: {}, isContentEditable: false, cilj: true,
        getAttribute(n) { return n in this.atributi ? this.atributi[n] : null; },
        contains(o) { return o === this || this.otroci.some((c) => c.contains(o)); },
    }, lastnosti || {});
    vsi.push(x);
    return x;
}
let zavrni = false;    // highlightElement zavrne cilj (izven pogleda)
const poti = {
    pocisti() { vsi.forEach((x) => x.razred.delete('safeer-active-card')); },
    oznaci(x) { poti.pocisti(); if (!zavrni) x.razred.add('safeer-active-card'); },
    jeCilj: (x) => x.cilj,
};
const oznaceni = () => vsi.filter((x) => x.razred.has('safeer-active-card'));

// claude.ai/new: stranska vrstica, nato pogovorno okno Search s poljem in gumboma.
const povezavaVIskanju = el('A', { atributi: { href: '/x' } });
const search = el('BUTTON', {}, [povezavaVIskanju]);
const polje = el('INPUT', { atributi: { type: 'text' } });
const all = el('BUTTON');
const artifacts = el('BUTTON');
const okno = el('DIV', { atributi: { role: 'dialog', tabindex: '-1' }, cilj: false }, [polje, all, artifacts]);

// Daljinec je izbral »Search« (nas obroc).
search.razred.add('safeer-active-card');
preveri('fokus na nasem cilju: nic', 'nic', naFokusStrani(search, doc, poti));
preveri('fokus na povezavi v nasem cilju: nic', 'nic', naFokusStrani(povezavaVIskanju, doc, poti));
preveri('obroc ostane na Search', search, oznaceni()[0]);

// OK na Search: stran odpre okno in sama da fokus polju (tipkovnica TV).
preveri('stran da fokus polju v oknu: obroc se skrije', 'skrij', naFokusStrani(polje, doc, poti));
preveri('po odprtju okna ni vec obroca na Search (prej: obroc cez stransko vrstico in okno)', 0, oznaceni().length);

// Stran da fokus gumbu v oknu: obroc gre za njim, en sam.
preveri('stran da fokus gumbu All v oknu: obroc sledi', 'sledi', naFokusStrani(all, doc, poti));
preveri('en sam obroc, na All', true, oznaceni().length === 1 && oznaceni()[0] === all);

// Okno da fokus samemu sebi (vsebnik s tabindex=-1 ni cilj): obroc umaknemo, ne cez celo okno.
search.razred.add('safeer-active-card'); all.razred.delete('safeer-active-card');
preveri('fokus na vsebniku okna: skrij', 'skrij', naFokusStrani(okno, doc, poti));
preveri('po fokusu vsebnika ni obroca', 0, oznaceni().length);

// Tuj okvir (npr. obrazec za placilo) ima svoj okvir fokusa.
search.razred.add('safeer-active-card');
preveri('fokus v tujem okvirju: skrij', 'skrij', naFokusStrani(el('IFRAME'), doc, poti));

// highlightElement zavrne cilj (izven pogleda): star obroc ne sme ostati.
search.razred.add('safeer-active-card'); zavrni = true;
preveri('zavrnjen cilj: sledi', 'sledi', naFokusStrani(artifacts, doc, poti));
preveri('zavrnjen cilj: star obroc umaknjen', 0, oznaceni().length);
zavrni = false;
preveri('body ali nic: nic', 'nic', naFokusStrani(body, doc, poti));

// Pregled Gemini 3. 11. 2026: daljinec je izbral polje (nas obroc), OK mu da fokus (tipkovnica TV) - obroc se skrije,
// tudi ce je polje nas cilj (prej: 'nic', obroc je ostal cez polje poleg tipkovnice).
const posta = el('INPUT', { atributi: { type: 'email' } });
vsi.forEach((x) => x.razred.delete('safeer-active-card'));
posta.razred.add('safeer-active-card');
preveri('fokus na polju, ki je nas cilj: skrij', 'skrij', naFokusStrani(posta, doc, poti));
preveri('po fokusu nasega polja ni obroca', 0, oznaceni().length);
const ovojPolja = el('DIV', {}, [el('INPUT', { atributi: { type: 'search' } })]);
ovojPolja.razred.add('safeer-active-card');
preveri('fokus na polju v nasem cilju (ovoj): skrij', 'skrij', naFokusStrani(ovojPolja.otroci[0], doc, poti));

// Polje za vnos.
const t = (tag, a, ce) => el(tag, { atributi: a || {}, isContentEditable: !!ce });
preveri('input brez vrste je polje', true, jeVnosnoPolje(t('INPUT')));
preveri('input search je polje', true, jeVnosnoPolje(t('INPUT', { type: 'search' })));
preveri('textarea je polje', true, jeVnosnoPolje(t('TEXTAREA')));
preveri('contenteditable je polje', true, jeVnosnoPolje(t('DIV', {}, true)));
preveri('role=combobox je polje', true, jeVnosnoPolje(t('DIV', { role: 'combobox' })));
preveri('potrditveno polje ni polje za vnos', false, jeVnosnoPolje(t('INPUT', { type: 'checkbox' })));
preveri('gumb input ni polje za vnos', false, jeVnosnoPolje(t('INPUT', { type: 'submit' })));
preveri('gumb ni polje za vnos', false, jeVnosnoPolje(t('BUTTON')));

// Stran mora blok res uporabljati: poslusalec focusin (zajem) z nasimi potmi in zascito pred lastnim fokusom.
// Od 3. 11. 2026 (SAFEER_PLAST_IZBIRE): fokus na vsebniku okna izbiro premakne na prvi gumb v oknu (vstopi).
preveri('poslusalec focusin klice naFokusStrani s clearActive/highlightElement/isActionable/vstopi', true,
    /addEventListener\('focusin', function \(ev\) \{[\s\S]{0,300}if \(sledimFokusu\) return;[\s\S]{0,200}naFokusStrani\(ev\.target, document, \{ pocisti: clearActive, oznaci: highlightElement, jeCilj: isActionable,\s*vstopi: function \(t\) \{[\s\S]{0,300}return vstopiVPlast\(t\);\s*\} \}\);[\s\S]{0,120}\}, true\);/.test(VIR));
console.log('tv_fokus_strani_test: OK (' + stevilo + ')');
