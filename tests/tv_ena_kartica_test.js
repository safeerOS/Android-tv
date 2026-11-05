#!/usr/bin/env node
// En fokus na kartici clanka (SAFEER_PODREJENA_POVEZAVA):   node tests/tv_ena_kartica_test.js [pot do tv_spatial.js]
//
// Zakaj (lastnik 5. 11. 2026 17:49, TV, rtvslo.si): kartica novice »Mikrodrama / Moja dva fanta: RTV Slovenija napovedala
// ...« je imela dva fokusa - najprej samo oznaka rubrike (svoja povezava), nato naslov clanka. Uporabnik: »Kateri fokus je
// pravi za ogled vsebine?«. Zdaj je cilj naslov clanka; meniji in seznami povezav ostanejo, kot so.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { ustvari } = require('./tv_lazni_dom');

const VIR = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
let stevilo = 0;
function preveri(opis, pricakovano, dobljeno) {
    assert.deepStrictEqual(dobljeno, pricakovano, opis);
    stevilo++;
    console.log('  OK   ' + opis);
}
const izbran = (s) => s.document.querySelectorAll('.safeer-active-card').map((e) => e.innerText.trim().split(' ')[0]);

// 1) rtvslo.si: rubrika in naslov v istem h3, kartica je article. DOL z zgornjega gumba gre na naslov (en korak).
{
    const s = ustvari();
    const zgoraj = s.el('button', { okvir: [100, 20, 200, 50], besedilo: 'Zgoraj' });
    const kartica = s.el('article', { okvir: [100, 200, 640, 200] });
    const h3 = s.el('h3', { okvir: [100, 300, 640, 80] });
    h3.dodaj(s.el('a', { attrs: { href: '/mikrodrama' }, okvir: [100, 300, 120, 30], besedilo: 'Mikrodrama' }),
        s.el('a', { attrs: { href: '/clanek/moja-dva-fanta' }, okvir: [100, 335, 620, 40], besedilo: 'Moja dva fanta: RTV Slovenija napovedala novo serijo' }));
    kartica.dodaj(h3);
    const spodaj = s.el('button', { okvir: [100, 600, 200, 50], besedilo: 'Spodaj' });
    s.body.dodaj(zgoraj, kartica, spodaj);
    s.nalozi(VIR);
    s.izberi(zgoraj);
    s.smer('DOWN');
    preveri('kartica clanka: DOL izbere naslov, ne rubrike', ['Moja'], izbran(s));
    s.smer('DOWN');
    preveri('kartica clanka: naslednji DOL zapusti kartico (en sam fokus na kartici)', ['Spodaj'], izbran(s));
    s.smer('UP');
    preveri('kartica clanka: GOR nazaj na naslov', ['Moja'], izbran(s));
}
// 2) Rubrika nad naslovom (v article), naslov v h2 iste kartice.
{
    const s = ustvari();
    const zgoraj = s.el('button', { okvir: [100, 20, 200, 50], besedilo: 'Zgoraj' });
    const kartica = s.el('article', { okvir: [100, 200, 640, 200] });
    const h2 = s.el('h2', { okvir: [100, 340, 640, 50] });
    h2.dodaj(s.el('a', { attrs: { href: '/clanek/2' }, okvir: [100, 340, 620, 40], besedilo: 'Dolg naslov drugega clanka na strani' }));
    kartica.dodaj(s.el('a', { attrs: { href: '/sport' }, okvir: [100, 300, 80, 30], besedilo: 'Sport' }), h2);
    s.body.dodaj(zgoraj, kartica);
    s.nalozi(VIR);
    s.izberi(zgoraj);
    s.smer('DOWN');
    preveri('rubrika nad naslovom v isti kartici: DOL izbere naslov', ['Dolg'], izbran(s));
}
// 3) Meni (nav): kratke in dolge povezave ostanejo vse dosegljive.
{
    const s = ustvari();
    const nav = s.el('nav', { okvir: [0, 0, 1920, 60] });
    nav.dodaj(s.el('a', { attrs: { href: '/sport' }, okvir: [100, 10, 80, 40], besedilo: 'Sport' }),
        s.el('a', { attrs: { href: '/novice' }, okvir: [300, 10, 400, 40], besedilo: 'Najnovejse novice iz Slovenije in sveta' }));
    s.body.dodaj(nav, s.el('button', { okvir: [100, 300, 200, 50], besedilo: 'Spodaj' }));
    s.nalozi(VIR);
    s.izberi(nav.children[0]);
    s.smer('RIGHT');
    preveri('meni: DESNO s kratke na dolgo povezavo', ['Najnovejse'], izbran(s));
    s.smer('LEFT');
    preveri('meni: LEVO nazaj na kratko povezavo (ostane cilj)', ['Sport'], izbran(s));
}
// 4) Podmeni: povezava v zgornjem li ostane, ceprav so v njegovem podmeniju daljse povezave.
{
    const s = ustvari();
    const ul = s.el('ul', { okvir: [0, 100, 1920, 300] });
    const li = s.el('li', { okvir: [100, 100, 600, 200] });
    const pod = s.el('ul', { okvir: [100, 160, 600, 100] });
    const podLi = s.el('li', { okvir: [100, 160, 600, 50] });
    podLi.dodaj(s.el('a', { attrs: { href: '/sport/nogomet' }, okvir: [100, 170, 500, 40], besedilo: 'Nogomet in vse druge dolge novice' }));
    pod.dodaj(podLi);
    li.dodaj(s.el('a', { attrs: { href: '/sport' }, okvir: [100, 100, 80, 40], besedilo: 'Sport' }), pod);
    ul.dodaj(li);
    const zgoraj = s.el('button', { okvir: [100, 20, 200, 50], besedilo: 'Zgoraj' });
    s.body.dodaj(zgoraj, ul);
    s.nalozi(VIR);
    s.izberi(zgoraj);
    s.smer('DOWN');
    preveri('podmeni: DOL izbere zgornjo povezavo »Sport«', ['Sport'], izbran(s));
}
// 5) Cisto pravilo.
{
    const i = VIR.indexOf('/* SAFEER_PODREJENA_POVEZAVA_ZACETEK'), j = VIR.indexOf('/* SAFEER_PODREJENA_POVEZAVA_KONEC */');
    assert.ok(i > 0 && j > i, 'bloka SAFEER_PODREJENA_POVEZAVA ni');
    const { jePodrejenaPovezava } = new Function(VIR.slice(i, j) + '\nreturn { jePodrejenaPovezava };')();
    const p = (href, besedilo) => ({ href, besedilo });
    preveri('rubrika ob naslovu je podrejena', true, jePodrejenaPovezava(p('/r', 'Mikrodrama'), p('/c', 'Moja dva fanta: RTV Slovenija napovedala')));
    preveri('ista povezava ni podrejena (slika in naslov istega clanka)', false, jePodrejenaPovezava(p('/c', 'Mikrodrama'), p('/c', 'Moja dva fanta: RTV Slovenija napovedala')));
    preveri('podobno dolgi povezavi nista podrejeni', false, jePodrejenaPovezava(p('/a', 'Prijava v racun'), p('/b', 'Registracija racuna')));
    preveri('dolga »mala« povezava ni podrejena', false, jePodrejenaPovezava(p('/a', 'x'.repeat(41)), p('/b', 'y'.repeat(100))));
}
// 6) Stolpec: sirok element, ki vodoravno pokrije ozjega, je v istem stolpcu (SAFEER_STOLPEC).
{
    const i = VIR.indexOf('/* SAFEER_STOLPEC_ZACETEK'), j = VIR.indexOf('/* SAFEER_STOLPEC_KONEC */');
    assert.ok(i > 0 && j > i, 'bloka SAFEER_STOLPEC ni');
    const { prekrivaStolpec } = new Function(VIR.slice(i, j) + '\nreturn { prekrivaStolpec };')();
    const r = (l, w) => ({ left: l, width: w, right: l + w });
    preveri('stolpec: sirok naslov nad ozkim gumbom', true, prekrivaStolpec(r(100, 200), r(100, 620)));
    preveri('stolpec: element v sosednjem stolpcu ne', false, prekrivaStolpec(r(100, 200), r(320, 200)));
    preveri('stolpec: manj kot pol prekrivanja ne', false, prekrivaStolpec(r(100, 200), r(250, 400)));
}
console.log('tv_ena_kartica_test: OK (' + stevilo + ')');
