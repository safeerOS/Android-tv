// Skupna umetna stran za preizkuse hitrosti in poti na vrh (assets/tv_spatial.js): ~1900 elementov, 3 stolpci x 40 vrst kartic
// (24ur.com-like), koordinate strani z drsenjem (getBoundingClientRect odsteje scrollY, kot brskalnik), stevci dragih operacij.
'use strict';
const fs = require('fs');
const path = require('path');
const { ustvari } = require('./tv_lazni_dom');

const STOLPCI = 3, VISINA_KARTICE = 320;

function zgradi(vir, o) {
    o = Object.assign({ vrste: 40, zeton: 'd'.repeat(48), spani: 10 }, o || {});
    // Okvirji so v koordinatah strani, lazni DOM odsteje drsenje (tudi za izvirnik okvirja, ki ga skript ob zacetku dokumenta shrani:
    // kljuka naOkvir steje klice strani IN izvirnika, sicer bi branje prek izvirnika ostalo nezaznano).
    const s = ustvari({ zeton: o.zeton, visinaStrani: o.vrste * VISINA_KARTICE + 400, odstejDrsenje: true, izvirniki: o.izvirniki,
        naOkvir: () => { stevci.gbcr++; kdo('gbcr'); } });
    const stevci = { efp: 0, gcs: 0, besedilo: 0, closest: 0, qsa: 0, gbcr: 0, prekrivnoOkno: 0 };
    let vEfp = 0;
    const klicatelji = {};
    const kdo = (op) => {
        if (!process.env.SAFEER_KLICATELJI) return;
        const okvirji = (new Error().stack || '').split('\n').slice(3, 7).map((l) => (/at (?:\S+\.)?(\w+) /.exec(l) || [])[1] || '?');
        const k = op + ' <- ' + okvirji.join(' < ');
        klicatelji[k] = (klicatelji[k] || 0) + 1;
    };
    // Kot brskalnik (block: 'nearest'): scrollIntoView podrsa stran, da je element viden (lazna vrsta ga sicer ne dela nic).
    s.El.prototype.scrollIntoView = function () {
        const r = this.getBoundingClientRect(), h = s.window.innerHeight;
        const dy = r.top < 0 ? r.top : (r.bottom > h ? r.bottom - h : 0);
        if (dy) s.window.scrollBy(0, dy);
    };
    const efp0 = s.document.elementFromPoint;
    s.document.elementFromPoint = function (x, y) { stevci.efp++; kdo('efp'); vEfp++; try { return efp0.call(this, x, y); } finally { vEfp--; } };
    const gcs0 = s.window.getComputedStyle;
    s.window.getComputedStyle = function (e) { stevci.gcs++; kdo('gcs'); return gcs0(e); };
    // innerText v lazni vrsti rekurzivno bere otroke (getter se klice tudi za njih): stejemo samo klice od zunaj.
    let globinaBesedila = 0;
    for (const ime of ['innerText', 'textContent']) {
        const d = Object.getOwnPropertyDescriptor(s.El.prototype, ime);
        Object.defineProperty(s.El.prototype, ime, { configurable: true, get() {
            if (!globinaBesedila) { stevci.besedilo++; kdo('besedilo'); }
            globinaBesedila++; try { return d.get.call(this); } finally { globinaBesedila--; }
        } });
    }
    const cl0 = s.El.prototype.closest;
    s.El.prototype.closest = function (q) { stevci.closest++; kdo('closest'); return cl0.call(this, q); };
    const qsa0 = s.El.prototype.querySelectorAll;
    s.El.prototype.querySelectorAll = function (q) { stevci.qsa++; kdo('qsa'); return qsa0.call(this, q); };
    const dq0 = s.document.querySelectorAll;
    s.document.querySelectorAll = function (q) {
        stevci.qsa++; kdo('qsa');
        if (q.indexOf('[class*="Overlay"]') !== -1) stevci.prekrivnoOkno++;   // iskanje prekrivnega okna (celoten DOM)
        return dq0.call(this, q);
    };

    const kartice = [];
    const glava = s.el('nav', { okvir: [0, 0, 1920, 60] });
    const rubrike = [];
    for (let i = 0; i < 12; i++) { const a = s.el('a', { attrs: { href: '/rubrika' + i }, okvir: [40 + i * 150, 10, 130, 40], besedilo: 'Rubrika ' + i }); rubrike.push(a); glava.dodaj(a); }
    s.body.dodaj(glava);
    for (let v = 0; v < o.vrste; v++) {
        const brezV = !!(o.bezPovezav && v >= o.bezPovezav[0] && v <= o.bezPovezav[1]);
        const vrsta = s.el('div', { razred: brezV ? 'vrsta' : 'row item-row', okvir: [0, 100 + v * VISINA_KARTICE, 1920, VISINA_KARTICE] });
        for (let c = 0; c < STOLPCI; c++) {
            const x = 60 + c * 620, y = 120 + v * VISINA_KARTICE;
            const stolpec = s.el('div', { razred: brezV ? 'stolpec' : 'col item-col', okvir: [x, y, 580, 300] });
            const kartica = s.el('div', { razred: (o.bezPovezav && v >= o.bezPovezav[0] && v <= o.bezPovezav[1]) ? 'tekst-blok' : 'card-item', okvir: [x, y, 580, 300] });
            // Vrste v o.bezPovezav [od, do] so dolg clanek brez ciljev (samo besedilo): redek del strani.
            const brez = o.bezPovezav && v >= o.bezPovezav[0] && v <= o.bezPovezav[1];
            const povezava = s.el(brez ? 'p' : 'a', { attrs: brez ? {} : { href: '/clanek/' + v + '-' + c }, okvir: [x, y + 230, 580, 60], besedilo: 'Clanek ' + v + '-' + c });
            kartica.dodaj(s.el('div', { razred: brezV ? 'slika' : 'slika-item', okvir: [x, y, 580, 220] }), povezava,
                s.el('span', { razred: 'time', okvir: [x + 10, y + 10, 60, 20], besedilo: '12:30' }));
            for (let m = 0; m < o.spani; m++) kartica.dodaj(s.el('span', { razred: brezV ? 'meta' : 'item-meta', okvir: [x + 10 + m * 50, y + 200, 45, 18], besedilo: 'm' }));
            stolpec.dodaj(kartica);
            vrsta.dodaj(stolpec);
            kartice.push({ kartica, povezava, vrsta: v, stolpec: c });
        }
        s.body.dodaj(vrsta);
    }
    s.nalozi(vir);
    const izbranEl = () => s.document.querySelector('.safeer-active-card');
    const izbran = () => ((izbranEl() || {}).innerText || '').trim().split(/\s+/).slice(0, 2).join(' ');
    // Je izbrani element (okvir izbirnika) viden v pogledu? Vrne null brez izbire.
    const viden = () => {
        const e = izbranEl();
        if (!e) return null;
        const r = e.getBoundingClientRect();
        return r.bottom > 0 && r.top < 1080 && r.right > 0 && r.left < 1920;
    };
    return { s, stevci, klicatelji, kartice, rubrike, izbran, izbranEl, viden };
}

function sestej(a) { const v = {}; for (const p of a) for (const k in p) v[k] = (v[k] || 0) + p[k]; return v; }
module.exports = { zgradi, sestej, VISINA_KARTICE };
