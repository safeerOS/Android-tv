#!/usr/bin/env node
// Okno s piskotki ob strani (assets/tv_spatial.js, SAFEER_PLAST_IZBIRE):   node tests/tv_plast_izbire_test.js
//
// Zakaj (lastnik 3. 11. 2026 02:42, claude.ai/login na TV, okvirji iz uiautomator): piskotkov ni mogel sprejeti z daljincem.
// Gumbi prijave levo in na sredini, pritrjeno okno »Cookie settings« desno (povezava »here«, Customize / Reject all /
// Accept all cookies). Gumbi omenjajo piskotke in okno ni modalno, zato jih je jeNogaAliPiskotki izlocil. Preizkus: vsi
// stirje cilji okna so dosegljivi s puscicami, OK klikne izbranega, ob pojavu gre izbira v okno (na prvi gumb) in nic se
// ne sprejme ali zavrne samo.
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
const okvir = (l, t, d, s) => [l, t, d - l, s - t];       // iz [l,t][d,s] kot v uiautomator

// vloga: '' (pritrjen DIV, kot claude.ai) ali 'dialog' (role=dialog brez aria-modal).
function stran(vloga) {
    const s = ustvari();
    const e = {};
    const glava = s.el('header', { okvir: okvir(0, 100, 1920, 210) });
    e.try = s.el('a', { attrs: { href: '/new' }, okvir: okvir(1630, 124, 1820, 188), besedilo: 'Try Claude' });
    glava.dodaj(e.try);
    const prijava = s.el('main', { okvir: okvir(0, 210, 1920, 1080) });
    e.google = s.el('button', { okvir: okvir(568, 606, 1352, 688), besedilo: 'Continue with Google' });
    e.apple = s.el('button', { okvir: okvir(568, 710, 1352, 792), besedilo: 'Continue with Apple' });
    e.posta = s.el('input', { attrs: { type: 'email' }, okvir: okvir(568, 872, 1352, 954) });
    e.naprej = s.el('button', { okvir: okvir(568, 984, 1352, 1066), besedilo: 'Continue with email' });
    prijava.dodaj(e.google, e.apple, e.posta, e.naprej);
    const attrs = vloga ? { role: vloga, 'aria-label': 'Cookie settings' } : {};
    const okno = s.el('div', { attrs, okvir: okvir(1148, 596, 1892, 1052), slog: { position: 'fixed', zIndex: '50' } });
    const naslov = s.el('h2', { okvir: okvir(1168, 616, 1872, 656), besedilo: 'Cookie settings' });
    const besedilo = s.el('p', { okvir: okvir(1168, 676, 1872, 860), besedilo: 'We use cookies ... Learn more' });
    e.here = s.el('a', { attrs: { href: 'https://www.anthropic.com/legal/cookies' }, okvir: okvir(1664, 810, 1726, 846), besedilo: 'here' });
    besedilo.dodaj(e.here);
    e.customize = s.el('button', { okvir: okvir(1168, 880, 1872, 944), besedilo: 'Customize cookie settings' });
    e.reject = s.el('button', { okvir: okvir(1168, 968, 1508, 1032), besedilo: 'Reject all cookies' });
    e.accept = s.el('button', { okvir: okvir(1532, 968, 1872, 1032), besedilo: 'Accept all cookies' });
    okno.dodaj(naslov, besedilo, e.customize, e.reject, e.accept);
    s.body.dodaj(glava, prijava, okno);
    s.nalozi(VIR);
    s.e = e;
    s.izbran = () => s.document.querySelector('.safeer-active-card');
    return s;
}
function ime(s, el) { return Object.keys(s.e).filter((k) => s.e[k] === el)[0] || (el ? el.tagName : 'nic'); }
// Vse, kar je s puscicami dosegljivo od zacetka (iskanje v sirino po smereh).
function dosegljivi(s, zacetek) {
    const videni = [zacetek], vrsta = [zacetek];
    while (vrsta.length) {
        const od = vrsta.shift();
        for (const smer of ['UP', 'DOWN', 'LEFT', 'RIGHT']) {
            s.window._safeer_highlight(od);
            s.window._safeer_navigate_spatial(smer);
            const zdaj = s.izbran();
            if (zdaj && videni.indexOf(zdaj) === -1) { videni.push(zdaj); vrsta.push(zdaj); }
        }
    }
    return videni.map((x) => ime(s, x)).sort();
}

for (const vloga of ['', 'dialog']) {
    const opis = vloga ? 'role=dialog' : 'pritrjen DIV (claude.ai)';
    let s = stran(vloga);
    const dos = dosegljivi(s, s.e.try);
    for (const k of ['here', 'customize', 'reject', 'accept']) preveri(opis + ': »' + k + '« je dosegljiv s puscicami', true, dos.indexOf(k) !== -1);
    // Pritrjen DIV ni modalen: stran za njim ostane dosegljiva. role=dialog cez vec kot tretjino zaslona je
    // modalno okno (najdiPrekrivnoOkno, Facebook 28. 10. 2026): izbira ostane v njem, dokler je odprto.
    preveri(opis + (vloga ? ': izbira ostane v oknu' : ': gumbi prijave ostanejo dosegljivi'), !vloga, dos.indexOf('google') !== -1);
    preveri(opis + ': puscice nicesar ne kliknejo', [0, 0], [s.kliki.length, s.dotiki.length]);

    // OK na izbranem gumbu okna: en pravi dotik na njegovi sredini (na vrhu je on, okno je pritrjeno nad stranjo).
    s.window._safeer_highlight(s.e.reject);
    s.window._safeer_click_focused_card();
    preveri(opis + ': OK na »Reject all cookies« je en dotik na njegovi sredini', [[1338, 1000]], s.dotiki);

    // Pojav okna: izbira gre vanj, na prvi gumb po legi (Customize), brez klika.
    s = stran(vloga);
    s.window._safeer_highlight(s.e.try);
    preveri(opis + ': ob pojavu okna izbira skoci vanj', true, s.window._safeer_vstopi_v_plast());
    preveri(opis + ': izbran je prvi gumb okna (Customize), ne Sprejmi/Zavrni', 'customize', ime(s, s.izbran()));
    preveri(opis + ': nic ni sprejeto ali zavrnjeno samo', [0, 0], [s.kliki.length, s.dotiki.length]);
    preveri(opis + ': v isto okno ne skoci znova (uporabnik lahko odide)', false,
        (s.window._safeer_highlight(s.e.google), s.window._safeer_vstopi_v_plast()));

    // Stran da fokus vsebniku okna (tabindex=-1, ni cilj): obroc ne izgine, izbira gre na prvi gumb v oknu.
    s = stran(vloga);
    s.window._safeer_highlight(s.e.try);
    const okno = s.e.customize.parentElement;
    okno.setAttribute('tabindex', '-1');
    okno.focus();
    preveri(opis + ': fokus strani na vsebniku okna -> izbran prvi gumb okna', 'customize', ime(s, s.izbran()));
}

// Pasica s piskotki v toku strani (ni pritrjena) ostane izlocena kot prej (Facebook 28. 10. 2026, tv_modalno_okno_test).
{
    const s = ustvari();
    const gumb = s.el('button', { okvir: [100, 100, 300, 60], besedilo: 'Prijava' });
    const pasica = s.el('div', { razred: 'cookie-bar', okvir: [0, 400, 1920, 80] });
    const p = s.el('button', { okvir: [100, 410, 300, 60], besedilo: 'Accept cookies' });
    pasica.dodaj(p);
    s.body.dodaj(gumb, pasica);
    s.nalozi(VIR);
    s.window._safeer_highlight(gumb);
    s.window._safeer_navigate_spatial('DOWN');
    preveri('pasica v toku strani (ni pritrjena): ni cilj', 'BUTTON:Prijava',
        s.document.querySelector('.safeer-active-card').tagName + ':' + s.document.querySelector('.safeer-active-card').innerText.trim());
}
// Pritrjena glava z gumbom »Cookie settings« ni pasica, ki caka na izbiro: izbira ne skoci vanjo sama.
{
    const s = ustvari();
    const glava = s.el('header', { okvir: [0, 0, 1920, 80], slog: { position: 'sticky' } });
    glava.dodaj(s.el('button', { okvir: [10, 10, 200, 50], besedilo: 'Meni' }), s.el('button', { okvir: [300, 10, 300, 50], besedilo: 'Cookie settings' }));
    s.body.dodaj(glava, s.el('button', { okvir: [100, 300, 300, 60], besedilo: 'Vsebina' }));
    s.nalozi(VIR);
    preveri('pritrjena glava z »Cookie settings«: brez samodejnega vstopa', false, s.window._safeer_vstopi_v_plast());
}
console.log('tv_plast_izbire_test: OK (' + stevilo + ')');
