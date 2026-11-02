#!/usr/bin/env node
// En sam fokus pri daljincu (assets/tv_spatial.js, SAFEER_EN_FOKUS) brez naprave:   node tests/tv_en_fokus_test.js
//
// Zakaj (lastnik 2. 11. 2026 19:05, claude.ai/new na TV, »Brskalnik ima dvojni focus«): po DOL je bil »Recents« izbran
// (nasa oznaka), DOM fokus pa je ostal na »Customize«, ki ga nas slog :focus obrobi s cian okvirjem. Izbrani element ni
// bil fokusljiv (focus() tiho ne naredi nicesar). Po vsakem premiku mora biti fokus na izbranem elementu (ali v njem)
// ali nikjer - nikoli na prejsnjem.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');

const VIR = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
const od = VIR.indexOf('/* SAFEER_EN_FOKUS_ZACETEK');
const doKonca = VIR.indexOf('/* SAFEER_EN_FOKUS_KONEC */');
assert.ok(od > 0 && doKonca > od, 'bloka SAFEER_EN_FOKUS ni v tv_spatial.js (fokus ostane na prejsnjem elementu)');
const { uskladiFokus } = new Function(VIR.slice(od, doKonca) + '\nreturn { uskladiFokus: uskladiFokus };')();

let stevilo = 0;
function preveri(opis, pricakovano, dobljeno) {
    assert.strictEqual(dobljeno, pricakovano, opis);
    stevilo++;
    console.log('  OK   ' + opis);
}

// Ponarejen DOM: fokus dobi samo fokusljiv element (kot v brskalniku: DIV brez tabindex ga ne dobi).
const body = { tagName: 'BODY' };
const doc = { body, documentElement: { tagName: 'HTML' }, activeElement: body };
function el(tag, fokusljiv, otroci) {
    return {
        tagName: tag, otroci: otroci || [], razred: new Set(),
        focus() { if (fokusljiv) doc.activeElement = this; },
        blur() { if (doc.activeElement === this) doc.activeElement = body; },
        contains(o) { return o === this || this.otroci.some((c) => c.contains(o)); },
        querySelector() { return this.otroci.filter((c) => c.povezava)[0] || null; },
    };
}
// Kar naredi highlightElement: obroc samo na novem (clearActive), nato uskladiFokus.
const vsi = [];
function izberi(x) {
    vsi.forEach((y) => y.razred.delete('safeer-active-card'));
    x.razred.add('safeer-active-card');
    uskladiFokus(x, doc);
}
function enObroc() { return vsi.filter((y) => y.razred.has('safeer-active-card')).length; }

const customize = el('BUTTON', true);
const recents = el('DIV', false);                         // ovoj brez tabindex: focus() ne naredi nicesar
const povezava = el('A', true); povezava.povezava = true;
const vrstica = el('DIV', false, [povezava]);             // ovoj s povezavo v sebi
const polje = el('INPUT', true);
const okvir = el('IFRAME', true);                         // gumb GSI v tujem okvirju
vsi.push(customize, recents, vrstica, povezava, polje, okvir);

izberi(customize);
preveri('Customize: fokus na izbranem', customize, doc.activeElement);
izberi(recents);
preveri('po DOL na Recents (ni fokusljiv): en sam obroc', 1, enObroc());
preveri('po DOL na Recents: Customize nima vec fokusa (prej cian okvir na njem)', true, doc.activeElement !== customize);
preveri('po DOL na Recents: fokus nikjer (body)', body, doc.activeElement);
izberi(vrstica);
preveri('ovoj s povezavo: fokus na povezavi v izbranem', povezava, doc.activeElement);
izberi(customize);
izberi(polje);
preveri('polje: prejsnji izgubi fokus, polje ga ne dobi (tipkovnica TV)', body, doc.activeElement);
polje.focus();
izberi(polje);
preveri('polje, ki je imelo fokus: ga izgubi (kot prej el.blur)', body, doc.activeElement);
izberi(customize);
izberi(okvir);
preveri('tuj okvir: prejsnji izgubi fokus, okvir ga ne dobi', body, doc.activeElement);
preveri('tuj okvir: en sam obroc', 1, enObroc());
izberi(customize);
izberi(customize);
preveri('isti element znova: fokus ostane', customize, doc.activeElement);

// highlightElement mora uskladiFokus res klicati, takoj po oznaki (blok ne sme ostati mrtev).
const hl = VIR.slice(VIR.indexOf('function highlightElement(el)')).slice(0, 4000);
preveri('highlightElement: clearActive, oznaka, uskladiFokus', true,
    /clearActive\(\);[\s\S]{0,200}el\.classList\.add\('safeer-active-card'\);[\s\S]{0,400}uskladiFokus\(el, document\);/.test(hl));
preveri('highlightElement: brez starega neposrednega el.focus', true, !/tag !== 'INPUT' && tag !== 'TEXTAREA'/.test(hl));
console.log('tv_en_fokus_test: OK (' + stevilo + ')');
