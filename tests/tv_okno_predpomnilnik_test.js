#!/usr/bin/env node
// SAFEER_OKNO_PREDPOMNILNIK (15. 11. 2026): iskanje prekrivnega okna se predpomni (isti kljuc strani, najvec 400 ms). Predpomnilnik
// ne sme prikriti okna, ki ga stran doda ali odstrani: sprememba DOM ga zavrze, cilji so spet samo v oknu / spet vsa stran.
//   node tests/tv_okno_predpomnilnik_test.js [pot do tv_spatial.js]
'use strict';
const fs = require('fs');
const path = require('path');
const { ustvari } = require('./tv_lazni_dom');

const VIR = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
let stevilo = 0;
function preveri(opis, pricakovano, dobljeno) {
    if (JSON.stringify(pricakovano) !== JSON.stringify(dobljeno)) { console.log('  PADLO ' + opis + ' (pricakovano ' + JSON.stringify(pricakovano) + ', dobljeno ' + JSON.stringify(dobljeno) + ')'); process.exit(1); }
    stevilo++; console.log('  OK   ' + opis);
}
const s = ustvari({ zeton: 'e'.repeat(48) });
const izbran = () => ((s.document.querySelector('.safeer-active-card') || {}).innerText || '').trim();
const a1 = s.el('a', { attrs: { href: '/1' }, okvir: [100, 200, 400, 60], besedilo: 'Prvi' });
const a2 = s.el('a', { attrs: { href: '/2' }, okvir: [100, 400, 400, 60], besedilo: 'Drugi' });
const a3 = s.el('a', { attrs: { href: '/3' }, okvir: [100, 600, 400, 60], besedilo: 'Tretji' });
s.body.dodaj(a1, a2, a3);
s.nalozi(VIR);
// Stevec celotnih pregledov DOM za prekrivno okno (najdiPrekrivnoOkno bere ves DOM z ~20 izbirniki).
let pregledov = 0;
const dq = s.document.querySelectorAll.bind(s.document);
s.document.querySelectorAll = (q) => { if (q.indexOf('[class*="Overlay"]') !== -1) pregledov++; return dq(q); };
s.izberi(a1);
s.smer('DOWN');
preveri('brez okna: DOL gre na naslednjo povezavo', 'Drugi', izbran());
// Brez spremembe strani naslednja tipka okna ne isce znova (pred popravkom: vsaj en pregled DOM na tipko).
const p0 = pregledov;
s.smer('DOWN');
preveri('brez spremembe strani: DOL na Tretji', 'Tretji', izbran());
preveri('brez spremembe strani: nov pregled DOM za okno ni potreben', 0, pregledov - p0);
// Sprememba strani (nov element) predpomnilnik zavrze: okno se isce znova.
const p1 = pregledov;
const nov = s.el('a', { attrs: { href: '/4' }, okvir: [100, 800, 400, 60], besedilo: 'Cetrti' });
s.body.dodaj(nov);
s.spremeni([{ type: 'childList', target: s.body, addedNodes: [nov], removedNodes: [] }]);
s.smer('UP');
preveri('po spremembi strani: okno se isce znova', true, pregledov > p1);
s.izberi(a1);

// Stran doda pogovorno okno cez cel zaslon (piskotki): sprememba DOM zavrze predpomnilnik, DOL ostane v oknu.
const gumb = s.el('button', { okvir: [800, 500, 200, 60], besedilo: 'Dovoli' });
const gumb2 = s.el('button', { okvir: [800, 600, 200, 60], besedilo: 'Zavrni' });
const okno = s.el('div', { razred: 'consent-modal', okvir: [0, 0, 1920, 1080], slog: { position: 'fixed', zIndex: '100' } });
okno.dodaj(gumb, gumb2);
s.body.dodaj(okno);
s.spremeni([{ type: 'childList', target: s.body, addedNodes: [okno], removedNodes: [] }]);
s.smer('DOWN');
preveri('okno dodano: izbira je v oknu (Dovoli)', 'Dovoli', izbran());
s.smer('DOWN');
preveri('okno dodano: DOL ostane v oknu (Zavrni)', 'Zavrni', izbran());

// Okno izgine: predpomnilnik ne sme zadrzati zaprte strani.
okno.parentElement.children.splice(okno.parentElement.children.indexOf(okno), 1);
okno.parentElement = null;
s.spremeni([{ type: 'childList', target: s.body, addedNodes: [], removedNodes: [okno] }]);
preveri('okno odstranjeno: povezava strani je spet izbirna', true, s.izberi(a1));
s.smer('DOWN');
preveri('okno odstranjeno: DOL je spet na strani (Drugi)', 'Drugi', izbran());
console.log('tv_okno_predpomnilnik_test: OK (' + stevilo + ')');
