#!/usr/bin/env node
// SAFEER_KANDIDATI_RAZLICICA (TV 9. 11. 2026, apk-21: zbiranje ciljev 150-250 ms ob vsaki tipki): izid zbiranja velja, dokler
// se stran ne spremeni. Pritisk brez spremembe strani ne pregleda strani znova; sprememba strani (ne nasa oznaka) ga osvezi.
//   node tests/tv_kandidati_predpomnilnik_test.js [pot do tv_spatial.js]
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { ustvari } = require('./tv_lazni_dom');

const VIR = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
let stevilo = 0;
function preveri(opis, pricakovano, dobljeno) {
    try { assert.deepStrictEqual(dobljeno, pricakovano, opis); stevilo++; console.log('  OK   ' + opis); }
    catch (_) { console.log('  PADLO ' + opis + ' (pricakovano ' + JSON.stringify(pricakovano) + ', dobljeno ' + JSON.stringify(dobljeno) + ')'); process.exit(1); }
}
const ZETON = 'c'.repeat(48);
const izbran = (s) => ((s.document.querySelector('.safeer-active-card') || {}).innerText || '').trim();

const s = ustvari({ zeton: ZETON });
const a1 = s.el('a', { attrs: { href: '/1' }, okvir: [100, 300, 400, 60], besedilo: 'Prvi' });
const a2 = s.el('a', { attrs: { href: '/2' }, okvir: [100, 500, 400, 60], besedilo: 'Drugi' });
s.body.dodaj(a1, a2);
s.nalozi(VIR);
// Stevec velikih pregledov strani (zbiranje ciljev bere ves DOM z enim querySelectorAll s seznamom vrst ciljev).
let pregledov = 0;
const qsa = s.document.querySelectorAll.bind(s.document);
s.document.querySelectorAll = (sel) => { if (sel.indexOf('[class*="poster"]') !== -1) pregledov++; return qsa(sel); };
s.izberi(a1);
s.smer('DOWN');
preveri('DOL: Drugi', 'Drugi', izbran(s));
const po1 = pregledov;
s.smer('UP');
preveri('GOR: Prvi', 'Prvi', izbran(s));
preveri('pritisk brez spremembe strani ne pregleda strani znova', po1, pregledov);

// Nasa oznaka (razred safeer-*) ni sprememba strani.
s.spremeni([{ type: 'attributes', attributeName: 'class', target: a2, oldValue: '' }]);
a2.setAttribute && a2.setAttribute('class', 'safeer-active-card');
s.spremeni([{ type: 'attributes', attributeName: 'class', target: a2, oldValue: '' }]);
s.smer('DOWN');
preveri('nasa oznaka safeer-* ne osvezi zbiranja', po1, pregledov);

// Stran doda povezavo: zapis spremembe osvezi zbiranje in nova povezava je cilj.
const a0 = s.el('a', { attrs: { href: '/0' }, okvir: [100, 700, 400, 60], besedilo: 'Nova' });
s.body.dodaj(a0);
s.spremeni([{ type: 'childList', target: s.body, addedNodes: [a0], removedNodes: [] }]);
s.smer('DOWN');
preveri('po spremembi strani je nova povezava cilj', 'Nova', izbran(s));
preveri('sprememba strani osvezi zbiranje', true, pregledov > po1);

// Razred ali slog prednika skrije povezavo: zapis spremembe na predniku razveljavi odlocitev za vse pod njim (ne le zanj).
{
    const t = ustvari({ zeton: ZETON, scrollY: 1500, visinaStrani: 9000 });
    // Velika stran (vec kot 600 elementov): zbiranje uporablja odlocitve po elementih (SAFEER_KANDIDATI_RAZLICICA).
    const polnilo = [];
    for (let i = 0; i < 650; i++) polnilo.push(t.el('a', { attrs: { href: '/p' + i }, okvir: [2000, 5000 + i * 10, 10, 10], besedilo: 'p' }));
    const ovoj = t.el('div', { okvir: [100, 450, 600, 100] });
    const b1 = t.el('a', { attrs: { href: '/b1' }, okvir: [100, 300, 400, 60], besedilo: 'Zgoraj' });
    const b2 = t.el('a', { attrs: { href: '/b2' }, okvir: [100, 460, 400, 60], besedilo: 'V ovoju' });
    const b3 = t.el('a', { attrs: { href: '/b3' }, okvir: [100, 620, 400, 60], besedilo: 'Spodaj' });
    ovoj.dodaj(b2);
    t.body.dodaj(b1, ovoj, b3, ...polnilo);
    t.nalozi(VIR);
    t.izberi(b1);
    t.smer('DOWN');
    preveri('velika stran: DOL na povezavo v ovoju', 'V ovoju', izbran(t));
    t.smer('UP');
    ovoj.slog.display = 'none';
    t.spremeni([{ type: 'attributes', attributeName: 'style', target: ovoj, oldValue: '' }]);
    t.smer('DOWN');
    preveri('skrit ovoj (sprememba prednika): DOL preskoci povezavo v njem', 'Spodaj', izbran(t));
}
console.log('tv_kandidati_predpomnilnik_test: OK (' + stevilo + ')');
