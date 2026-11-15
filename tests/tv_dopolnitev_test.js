#!/usr/bin/env node
// Slepi pregled TV2 15. 11. 2026 (najdbe F2, F3, F4 za tv_spatial.js). Vsaka najdba ima preizkus, ki brez popravka pade:
//   F2 okno, ki se pokaze samo s CSS, ob zbiranju pa je bilo dalec izven pasu: izbira ne ostane ZA oknom do RECT_NAJDLJE_MS (3 s),
//   F3 N >= 51 povezav, ki jih stran naenkrat premakne v piskotke, ne ostane med cilji (prej samo prvih 50 vozlisc zapisa),
//   F4 rok predpomnilnika iskanja prekrivnega okna se steje od KONCA iskanja: pocasno iskanje (>= 150 ms) ni 2 polni iskanji na tipko.
//   node tests/tv_dopolnitev_test.js [pot do tv_spatial.js]
'use strict';
const fs = require('fs');
const path = require('path');
const { zgradi } = require('./tv_velika_stran');
const { ustvari } = require('./tv_lazni_dom');

const VIR = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
let stevilo = 0, padlo = 0;
function preveri(opis, pogoj, podrobnost) {
    if (!pogoj) { console.log('  PADLO ' + opis + (podrobnost !== undefined ? ' (' + JSON.stringify(podrobnost) + ')' : '')); padlo++; return; }
    stevilo++; console.log('  OK   ' + opis);
}
const besedilo = (s) => ((s.document.querySelector('.safeer-active-card') || {}).innerText || '').trim().split(/\s+/).slice(0, 2).join(' ');

// ---- F2: CSS-razkritje okna, ki je bilo ob zbiranju izven pasu ----
{
    const t = zgradi(VIR); const { s } = t;
    const dovoli = s.el('button', { okvir: [800, 6500, 200, 60], besedilo: 'Dovoli' });
    const zavrni = s.el('button', { okvir: [800, 6600, 200, 60], besedilo: 'Zavrni' });
    const okno = s.el('div', { razred: 'consent-modal', okvir: [0, 6000, 1920, 1080], slog: { position: 'fixed', zIndex: '100' } });
    okno.dodaj(dovoli, zavrni);
    s.body.dodaj(okno);
    s.spremeni([{ type: 'childList', target: s.body, addedNodes: [okno], removedNodes: [] }]);
    s.izberi(t.kartice[0].povezava);
    s.smer('DOWN');                                   // zbiranje: okno izven pasu, okvirji se zapomnijo (rectPas)
    // CSS-razkritje brez zapisa za opazovalca (:target, :focus-within, nova pravila CSS).
    okno.okvir = [0, 0, 1920, 1080]; dovoli.okvir = [800, 500, 200, 60]; zavrni.okvir = [800, 600, 200, 60];
    const t0 = s.ura.t;
    let prvic = null;
    for (let i = 0; i < 40 && prvic === null; i++) {
        s.smer('DOWN');
        const b = besedilo(s);
        if (b === 'Dovoli' || b === 'Zavrni') prvic = s.ura.t - t0;
    }
    preveri('F2 okno, razkrito samo s CSS, je cilj najkasneje po 300 ms (prej 3100 ms)', prvic !== null && prvic <= 300, prvic);
    // Dotik na element ZA oknom ne sme nastati, medtem ko je izbira se tam.
    const t2 = zgradi(VIR); const s2 = t2.s;
    const o2 = s2.el('div', { razred: 'consent-modal', okvir: [0, 6000, 1920, 1080], slog: { position: 'fixed', zIndex: '100' } });
    const d2 = s2.el('button', { okvir: [800, 6500, 200, 60], besedilo: 'Dovoli' });
    o2.dodaj(d2); s2.body.dodaj(o2);
    s2.spremeni([{ type: 'childList', target: s2.body, addedNodes: [o2], removedNodes: [] }]);
    s2.izberi(t2.kartice[0].povezava); s2.smer('DOWN');
    o2.okvir = [0, 0, 1920, 1080]; d2.okvir = [800, 500, 200, 60];
    s2.smer('DOWN'); s2.smer('DOWN');
    preveri('F2 po dveh tipkah izbira ni vec ZA oknom', besedilo(s2) === 'Dovoli', besedilo(s2));
}

// ---- F2: prva tipka po izteku roka iskanja okna je ze pravilna (okno se isce ze v getActiveElement, preden zbiranje bere zapisane okvirje) ----
{
    const t3 = zgradi(VIR); const s3 = t3.s;
    const o3 = s3.el('div', { razred: 'consent-modal', okvir: [0, 6000, 1920, 1080], slog: { position: 'fixed', zIndex: '100' } });
    const d3 = s3.el('button', { okvir: [800, 6500, 200, 60], besedilo: 'Dovoli' });
    o3.dodaj(d3); s3.body.dodaj(o3);
    s3.spremeni([{ type: 'childList', target: s3.body, addedNodes: [o3], removedNodes: [] }]);
    s3.izberi(t3.kartice[0].povezava); s3.smer('DOWN');
    o3.okvir = [0, 0, 1920, 1080]; d3.okvir = [800, 500, 200, 60];
    s3.ura.t += 300;                                  // rok predpomnilnika okna (150 ms) je potekel
    s3.smer('DOWN');
    preveri('F2 prva tipka po izteku roka je ze v oknu', besedilo(s3) === 'Dovoli', besedilo(s3));
}

// ---- F3: N povezav, ki jih stran naenkrat premakne v piskotke ----
function premik(N) {
    const s = ustvari({ zeton: 'e'.repeat(48) });
    const a1 = s.el('a', { attrs: { href: '/1' }, okvir: [100, 100, 400, 60], besedilo: 'Prvi' });
    const kont = s.el('div', { okvir: [0, 190, 1920, 880] });
    const povezave = [];
    for (let i = 0; i < N; i++) { const p = s.el('a', { attrs: { href: '/p' + i }, okvir: [100 + (i % 10) * 180, 200 + Math.floor(i / 10) * 50, 170, 40], besedilo: 'P' + i }); povezave.push(p); kont.dodaj(p); }
    const a3 = s.el('a', { attrs: { href: '/3' }, okvir: [100, 1030, 400, 40], besedilo: 'Zadnji' });
    s.body.dodaj(a1, kont, a3);
    s.nalozi(VIR);
    s.izberi(a1);
    s.smer('DOWN');                                   // zbiranje: odlocitve za vse povezave (cilji)
    s.smer('UP');
    const banner = s.el('div', { razred: 'cookie-banner', okvir: [0, 190, 1920, 880] });
    s.body.dodaj(banner);
    kont.children = [];
    povezave.forEach((p) => { banner.dodaj(p); });
    s.spremeni([{ type: 'childList', target: kont, addedNodes: [], removedNodes: povezave }, { type: 'childList', target: banner, addedNodes: povezave, removedNodes: [] }]);
    s.smer('DOWN');
    return besedilo(s);
}
for (const N of [30, 50, 51, 60, 120]) preveri('F3 ' + N + ' premaknjenih povezav v piskotke ni vec med cilji', premik(N) === 'Prvi', premik(N));

// ---- F4: rok predpomnilnika okna od KONCA iskanja ----
function iskanja(trajanje) {
    const t = zgradi(VIR); const { s } = t;
    let iskanj = 0;
    const dq = s.document.querySelectorAll;
    s.document.querySelectorAll = function (q) {
        if (q.indexOf('[class*="Overlay"]') !== -1) { iskanj++; s.ura.t += trajanje; }
        return dq.call(this, q);
    };
    s.izberi(t.kartice[0].povezava);
    const po = [];
    for (let i = 0; i < 6; i++) { const p0 = iskanj; s.smer('DOWN'); po.push(iskanj - p0); }
    return po;
}
for (const d of [160, 200, 300]) { const po = iskanja(d); preveri('F4 iskanje okna ' + d + ' ms: najvec 1 polno iskanje na tipko', Math.max.apply(null, po) <= 1, po); }

if (padlo) { console.log('tv_dopolnitev_test: PADLO ' + padlo); process.exit(1); }
console.log('tv_dopolnitev_test: OK (' + stevilo + ')');
