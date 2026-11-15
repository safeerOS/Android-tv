#!/usr/bin/env node
// Slepi pregled TV 15. 11. 2026 (najdbe N1-N4 za tv_spatial.js, predpomnilniki hitrosti). Vsaka najdba ima preizkus, ki brez popravka pade:
//   N1 fiksni element po drsenju okna ni izpadel iz ciljev (zapis okvirja v koordinatah strani ne velja za fiksne/sticky elemente),
//   N2 prepisana getBoundingClientRect ne zastrupi predpomnilnika okvirjev (bere se izvirnik z zacetka dokumenta),
//   N3 sprememba aria-modal, besedila in globoko v drevesu zavrze zapis noge/piskotkov,
//   N4 okno, ki se pokaze samo s CSS, ni skrito dlje kot ~150 ms,
//   + poslusalci okna (scroll vsebnika) so v lazni DOM resnicni: zavrzejo zapis okvirjev.
//   node tests/tv_slepi_pregled_test.js [pot do tv_spatial.js]
'use strict';
const fs = require('fs');
const path = require('path');
const { zgradi } = require('./tv_velika_stran');
const { ustvari } = require('./tv_lazni_dom');

const VIR = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
let stevilo = 0, padlo = 0;   // SAFEER_VSE=1: izpise vse padle preverbe (preizkus brez popravka)
function preveri(opis, pricakovano, dobljeno) {
    if (JSON.stringify(pricakovano) !== JSON.stringify(dobljeno)) { console.log('  PADLO ' + opis + ' (pricakovano ' + JSON.stringify(pricakovano) + ', dobljeno ' + JSON.stringify(dobljeno) + ')'); if (!process.env.SAFEER_VSE) process.exit(1); padlo++; return; }
    stevilo++; console.log('  OK   ' + opis);
}
const izbranBesedilo = (s) => ((s.document.querySelector('.safeer-active-card') || {}).innerText || '').trim().split(/\s+/).slice(0, 2).join(' ');
function dodajOkno(t) {
    // Piskotkarsko pogovorno okno (fiksno, cez cel zaslon) z dvema gumboma na viden polozaj 500-660 px.
    const s = t.s;
    const dovoli = s.el('button', { okvir: [800, 500, 200, 60], besedilo: 'Dovoli' });
    const zavrni = s.el('button', { okvir: [800, 600, 200, 60], besedilo: 'Zavrni' });
    const okno = s.el('div', { razred: 'consent-modal', okvir: [0, 0, 1920, 1080], slog: { position: 'fixed', zIndex: '100' } });
    okno.dodaj(dovoli, zavrni);
    s.body.dodaj(okno);
    s.spremeni([{ type: 'childList', target: s.body, addedNodes: [okno], removedNodes: [] }]);
    return { okno, dovoli, zavrni };
}

// ---- N1: fiksno okno po drsenju okna ostane cilj (izbira ne uide ZA modalno okno) ----
{
    const t = zgradi(VIR); const { s } = t;
    dodajOkno(t);
    s.smer('DOWN');                                  // zbiranje ob scrollY = 0: okvirji se zapomnijo
    preveri('N1 osnova: izbira je v oknu (Dovoli)', 'Dovoli', izbranBesedilo(s));
    s.drsi(8000);                                    // stran sama (ali uporabnik) podrsa okno; okno ostane na zaslonu
    s.smer('DOWN');
    preveri('N1 po drsenju okna na 8000 px: DOL ostane v fiksnem oknu (Zavrni, ne clanek za oknom)', 'Zavrni', izbranBesedilo(s));
}
// ---- N1b: prilepljena (sticky) glava po drsenju ostane na zaslonu in cilj ----
{
    const t = zgradi(VIR); const { s } = t;
    s.body.okvir = [0, 0, 1920, 13200];              // stars sticky glave: cela stran
    const glava = s.body.children[0];                // nav z rubrikami (okvir 0-60 px)
    glava.slog = { position: 'sticky', zIndex: '50' };
    s.spremeni([{ type: 'attributes', attributeName: 'style', target: glava, oldValue: '' }]);
    s.izberi(t.kartice[0].povezava);
    s.smer('DOWN');                                  // zbiranje ob scrollY = 0: okvirji rubrik se zapomnijo (v pogledu)
    s.drsi(8000);                                    // glava je po drsenju se vedno pri vrhu zaslona (sticky)
    s.window.__preizkus.oznaci(t.rubrike[5]);        // izbira na rubriki brez premika ure (zapis okvirjev ostane veljaven); glava je v pogledu, strani ne drsi
    preveri('N1b izhodisce: izbrana je Rubrika 5', 'Rubrika 5', izbranBesedilo(s));
    s.smer('RIGHT');
    preveri('N1b prilepljena glava po drsenju okna: sosednja rubrika je cilj (DESNO -> Rubrika 6)', 'Rubrika 6', izbranBesedilo(s));
}

// ---- N2: zacasno prepisana getBoundingClientRect ne zastrupi predpomnilnika ----
{
    // Primerjava z isto potjo brez laznega branja: po tipki (kjer stran laze) in vrnitvi metode mora DOL iti vrsto za vrsto.
    function pot(laze) {
        const t = zgradi(VIR); const { s } = t;
        s.izberi(t.kartice[0].povezava);
        if (laze) s.izvedi('var P = Object.getPrototypeOf(document.body); var _o = P.getBoundingClientRect; ' +
            'P.getBoundingClientRect = function () { var r = _o.call(this); return { left: r.left, top: r.top + 1e6, width: r.width, height: r.height, right: r.right, bottom: r.bottom + 1e6 }; }; ' +
            'this.__vrni = function () { P.getBoundingClientRect = _o; };');
        s.smer('DOWN');                              // tipka zgradi predpomnilnik, medtem ko stran laze (ali ne)
        if (laze) s.izvedi('this.__vrni()');          // laz se umakne (nevidna, prehodna)
        s.drsi(0);                                   // stran je (zaradi lazi) lahko podrsala: nazaj na vrh, predpomnilnik ostane
        s.window.__preizkus.oznaci(t.kartice[0].povezava);
        const izid = [];
        for (let i = 0; i < 3; i++) { s.smer('DOWN'); izid.push(izbranBesedilo(s)); }
        return izid;
    }
    const kontrola = pot(false);
    preveri('N2 kontrola: DOL gre vrsto za vrsto', ['Clanek 1-0', 'Clanek 2-0', 'Clanek 3-0'], kontrola);
    preveri('N2 po zacasni lazi in vrnitvi metode je pot enaka kontroli (predpomnilnik ni zastrupljen)', kontrola, pot(true));
}
// ---- N2b: brez izvirnika (stran brez zagotovljenih izvirnikov) se okvirji ne zapisujejo ----
{
    const t = zgradi(VIR, { izvirniki: false }); const { s } = t;
    s.izberi(t.kartice[0].povezava);
    s.smer('DOWN'); s.smer('DOWN');
    const vrsta = Number((/Clanek (\d+)-/.exec(izbranBesedilo(s)) || [0, -1])[1]);
    preveri('N2b brez izvirnikov daljinec se vedno dela (vrsta 2)', 2, vrsta);
}

// ---- N3: aria-modal, besedilo in globoki predniki zavrzejo zapis noge/piskotkov ----
{
    const s = ustvari({ zeton: 'a'.repeat(48) });
    const a1 = s.el('a', { attrs: { href: '/1' }, okvir: [100, 100, 400, 60], besedilo: 'Prvi' });
    const noga = s.el('footer');
    const modal = s.el('div', { attrs: { 'aria-modal': 'true' } });
    const a2 = s.el('a', { attrs: { href: '/2' }, okvir: [100, 300, 400, 60], besedilo: 'Drugi' });
    modal.dodaj(a2); noga.dodaj(modal);
    const a3 = s.el('a', { attrs: { href: '/3' }, okvir: [100, 500, 400, 60], besedilo: 'Tretji' });
    s.body.dodaj(a1, noga, a3);
    s.nalozi(VIR);
    const izb = () => ((s.document.querySelector('.safeer-active-card') || {}).innerText || '').trim();
    s.izberi(a1);
    s.smer('DOWN');
    preveri('N3 aria-modal osnova: povezava v modalnem oknu v nogi je cilj (Drugi)', 'Drugi', izb());
    s.smer('UP');
    modal.removeAttribute('aria-modal');
    s.spremeni([{ type: 'attributes', attributeName: 'aria-modal', target: modal, oldValue: 'true' }]);
    s.smer('DOWN');
    preveri('N3 aria-modal odstranjen: povezava v nogi ni vec cilj (Tretji)', 'Tretji', izb());
}
{
    const s = ustvari({ zeton: 'b'.repeat(48) });
    const a1 = s.el('a', { attrs: { href: '/1' }, okvir: [100, 100, 400, 60], besedilo: 'Prvi' });
    const a2 = s.el('a', { attrs: { href: '/2' }, okvir: [100, 300, 400, 60], besedilo: 'Dalje' });
    const a3 = s.el('a', { attrs: { href: '/3' }, okvir: [100, 500, 400, 60], besedilo: 'Tretji' });
    s.body.dodaj(a1, a2, a3);
    s.nalozi(VIR);
    const izb = () => ((s.document.querySelector('.safeer-active-card') || {}).innerText || '').trim();
    s.izberi(a1);
    s.smer('DOWN');
    preveri('N3 besedilo osnova: DOL na Dalje', 'Dalje', izb());
    s.smer('UP');
    a2._besedilo = 'Nastavitve piskotkov';             // besedilno vozlisce: samo characterData, brez childList
    s.spremeni([{ type: 'characterData', target: { nodeType: 3, parentElement: a2, data: 'Nastavitve piskotkov' }, oldValue: 'Dalje' }]);
    s.smer('DOWN');
    preveri('N3 besedilo je postalo piskotek: DOL ga preskoci (Tretji)', 'Tretji', izb());
}
{
    const s = ustvari({ zeton: 'c'.repeat(48) });
    const a1 = s.el('a', { attrs: { href: '/1' }, okvir: [100, 100, 400, 60], besedilo: 'Prvi' });
    const a2 = s.el('a', { attrs: { href: '/2' }, okvir: [100, 300, 400, 60], besedilo: 'Drugi' });
    let vrh = null, gnezdo = null;
    for (let i = 0; i < 12; i++) { const d = s.el('div'); if (gnezdo) gnezdo.dodaj(d); else vrh = d; gnezdo = d; }
    gnezdo.dodaj(a2);
    const a3 = s.el('a', { attrs: { href: '/3' }, okvir: [100, 500, 400, 60], besedilo: 'Tretji' });
    s.body.dodaj(a1, vrh, a3);
    s.nalozi(VIR);
    const izb = () => ((s.document.querySelector('.safeer-active-card') || {}).innerText || '').trim();
    s.izberi(a1);
    s.smer('DOWN');
    preveri('N3 globina osnova: DOL na Drugi', 'Drugi', izb());
    s.smer('UP');
    vrh.setAttribute('class', 'cookie-banner');      // razred 12 prednikov visje (prej so odlocitve gledale samo 9)
    s.spremeni([{ type: 'attributes', attributeName: 'class', target: vrh, oldValue: '' }]);
    s.smer('DOWN');
    preveri('N3 razred 12 prednikov visje je postal piskotek: DOL preskoci Drugi (Tretji)', 'Tretji', izb());
}

// ---- N4: okno, ki ga pokaze samo CSS (brez zapisa opazovalca), je skrito najvec ~150 ms ----
{
    const s = ustvari({ zeton: 'd'.repeat(48) });
    const a1 = s.el('a', { attrs: { href: '/1' }, okvir: [100, 200, 400, 60], besedilo: 'Prvi' });
    const a2 = s.el('a', { attrs: { href: '/2' }, okvir: [100, 400, 400, 60], besedilo: 'Drugi' });
    const okno = s.el('div', { razred: 'consent-modal', okvir: [0, 0, 1920, 1080], slog: { position: 'fixed', zIndex: '100', display: 'none' } });
    okno.dodaj(s.el('button', { okvir: [800, 500, 200, 60], besedilo: 'Dovoli' }));
    s.body.dodaj(a1, a2, okno);
    s.nalozi(VIR);
    let pregledov = 0;
    const dq = s.document.querySelectorAll.bind(s.document);
    s.document.querySelectorAll = (q) => { if (q.indexOf('[class*="Overlay"]') !== -1) pregledov++; return dq(q); };
    s.izberi(a1);
    s.smer('DOWN');                                  // okna ni: izid iskanja (brez okna) se zapomni
    okno.slog.display = 'block';                     // samo CSS (:target, nova pravila): zapisa za opazovalca ni
    const p0 = pregledov;
    s.ura.t += 120;                                  // skupaj ~220 ms od iskanja (tipka 100 + 120): dalec nad 150 ms
    s.smer('DOWN');
    preveri('N4 po vec kot 150 ms se okno isce znova (pred popravkom 400 ms: nic)', true, pregledov > p0);
}

// ---- poslusalci okna so resnicni (lazni DOM): drsenje VSEBNIKA zavrze zapis okvirjev ----
{
    const t = zgradi(VIR); const { s } = t;
    preveri('lazni DOM shrani poslusalce scroll okna (skript jih registrira)', true, s.stPoslusalcev('scroll') >= 1);
    s.izberi(t.kartice[0].povezava);
    s.smer('DOWN');                                  // okvirji daljnih elementov se zapomnijo
    s.smer('UP');
    const daljna = t.kartice[30 * 3];
    // Razporeditev se spremeni brez zapisa opazovalca (npr. vsebnik z drsenjem): kartica 30 pride tik pod prvo.
    const dy = -(daljna.kartica.okvir[1] - 300);
    const premakni = (el) => { if (el.okvir) el.okvir = [el.okvir[0], el.okvir[1] + dy, el.okvir[2], el.okvir[3]]; el.children.forEach(premakni); };
    premakni(daljna.kartica.parentElement);
    s.drsi(40, daljna.kartica.parentElement);        // scroll z elementa (ne z dokumenta): rectRazlicica++
    s.smer('DOWN');
    preveri('po drsenju vsebnika je premaknjena kartica (Clanek 30-0) cilj', 'Clanek 30-0', izbranBesedilo(s));
}
if (padlo) { console.log('tv_slepi_pregled_test: PADLO ' + padlo); process.exit(1); }
console.log('tv_slepi_pregled_test: OK (' + stevilo + ')');
