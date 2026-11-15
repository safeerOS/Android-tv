#!/usr/bin/env node
// SAFEER_NOGA_STATICNO (15. 11. 2026): staticni del jeNogaAliPiskotki (noga, piskotek po razredu/besedilu/href, modalno okno) se
// predpomni v odlocitvi elementa. Sprememba razreda ali besedila elementa (sprememba strani) ga mora zavreci: povezava, ki
// postane pasica s piskotki, ni vec cilj; povezava v nogi, ki to neha biti, je spet cilj.
//   node tests/tv_noga_staticno_test.js [pot do tv_spatial.js]
'use strict';
const fs = require('fs');
const path = require('path');
const { ustvari } = require('./tv_lazni_dom');

const VIR = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
let stevilo = 0;
function preveri(opis, pricakovano, dobljeno) {
    if (pricakovano !== dobljeno) { console.log('  PADLO ' + opis + ' (pricakovano ' + JSON.stringify(pricakovano) + ', dobljeno ' + JSON.stringify(dobljeno) + ')'); process.exit(1); }
    stevilo++; console.log('  OK   ' + opis);
}
const s = ustvari({ zeton: 'f'.repeat(48) });
const izbran = () => ((s.document.querySelector('.safeer-active-card') || {}).innerText || '').trim();
const a1 = s.el('a', { attrs: { href: '/1' }, okvir: [100, 100, 400, 60], besedilo: 'Prvi' });
const a2 = s.el('a', { attrs: { href: '/2' }, okvir: [100, 300, 400, 60], besedilo: 'Drugi' });
const a3 = s.el('a', { attrs: { href: '/3' }, okvir: [100, 500, 400, 60], besedilo: 'Tretji' });
s.body.dodaj(a1, a2, a3);
s.nalozi(VIR);
s.izberi(a1);
s.smer('DOWN');
preveri('osnova: DOL na Drugi (predpomni staticni del)', 'Drugi', izbran());
s.smer('UP');
preveri('osnova: GOR na Prvi', 'Prvi', izbran());
// Stran spremeni razred Drugega v pasico s piskotki.
a2.setAttribute('class', 'cookie-banner');
s.spremeni([{ type: 'attributes', attributeName: 'class', target: a2, oldValue: '' }]);
s.smer('DOWN');
preveri('Drugi je postal pasica s piskotki: DOL ga preskoci (Tretji)', 'Tretji', izbran());
// ... in neha biti.
s.smer('UP');
a2.setAttribute('class', 'povezava');
s.spremeni([{ type: 'attributes', attributeName: 'class', target: a2, oldValue: 'cookie-banner' }]);
s.izberi(a1);
s.smer('DOWN');
preveri('Drugi ni vec pasica: DOL spet nanj', 'Drugi', izbran());
console.log('tv_noga_staticno_test: OK (' + stevilo + ')');
