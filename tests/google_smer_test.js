#!/usr/bin/env node
// Smerne tipke na Googlovih zadetkih (GOOGLE_SMER v src/main/kotlin/si/safeer/tv/tv/SiteProfile.kt) brez naprave:
//   node tests/google_smer_test.js [pot/do/SiteProfile.kt]
//
// Zakaj (lastnik 2. 11. 2026, dnevnik SafeerGoogleNav na TV): z daljincem ni bilo mogoce do »Prijava« v glavi strani
// niti do »Prilagodi«/»Razumem« v Googlovem pogovornem oknu »Preveri nastavitve« (role=dialog). Nasa navigacija je
// poznala samo iskalno polje, rob in zadetke: vse nad dnom polja je izlocila, odprtega okna pa ni locila od strani.
// Preizkus pozene celoten skript na majhnem ponarejenem DOM (lega elementov na zaslonu 1920 x 1080).
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');

const KT = process.argv[2] || path.join(__dirname, '..', 'src', 'main', 'kotlin', 'si', 'safeer', 'tv', 'tv', 'SiteProfile.kt');
const vir = fs.readFileSync(KT, 'utf8');
const js = vir.split('private const val GOOGLE_SMER = """', 2)[1].split('"""', 1)[0];
assert.ok(js.indexOf('$') < 0, 'GOOGLE_SMER je surovi niz Kotlina: znak $ bi Kotlin razumel kot predlogo');

let stevilo = 0;
function preveri(opis, pricakovano, dobljeno) {
    assert.deepStrictEqual(dobljeno, pricakovano, opis);
    stevilo++;
    console.log('  OK   ' + opis);
}

// ---------------------------------------------------------------- ponarejen DOM
// Element pozna natanko tiste dele izbirnikov (locene z vejico), ki jih nasteje v »tok«.
const W = 1920, H = 1080;
function stran() {
    const vsi = [];
    const body = { tagName: 'BODY', parentElement: null, otroci: [] };
    const doc = {
        body, documentElement: { appendChild() {} }, activeElement: body,
        querySelectorAll: (s) => vsi.filter((x) => x.matches(s)),
        querySelector: (s) => vsi.find((x) => x.matches(s)) || null,
        getElementById: (id) => doc._ustvarjeni.find((x) => x.id === id) || null,
        createElement: () => { const x = { style: {}, id: '' }; doc._ustvarjeni.push(x); return x; },
        addEventListener() {}, _ustvarjeni: [],
        // Vrhnji element na tocki: kasnejsi v dokumentu je nad prejsnjimi (okno je dodano zadnje).
        elementFromPoint(x, y) {
            for (let i = vsi.length - 1; i >= 0; i--) {
                const r = vsi[i].r;
                if (!vsi[i].skozi && x >= r.left && x < r.left + r.width && y >= r.top && y < r.top + r.height) return vsi[i];
            }
            return body;
        },
    };
    function el(tag, tok, r, starsi, besedilo, lastnosti) {
        const x = Object.assign({
            tagName: tag, tok: new Set(tok), r: { left: r[0], top: r[1], width: r[2], height: r[3], right: r[0] + r[2], bottom: r[1] + r[3] },
            parentElement: starsi || body, innerText: besedilo || '', isConnected: true, poz: 'static',
            matches(s) { return s.split(',').some((t) => this.tok.has(t.trim())); },
            closest(s) { for (let p = this; p && p !== body; p = p.parentElement) if (p.matches(s)) return p; return null; },
            contains(o) { for (let p = o; p; p = p.parentElement) if (p === this) return true; return false; },
            querySelectorAll(s) { return vsi.filter((y) => y !== this && this.contains(y) && y.matches(s)); },
            querySelector(s) { return this.querySelectorAll(s)[0] || null; },
            getBoundingClientRect() { return this.r; },
            getAttribute() { return null; },
            focus() { doc.activeElement = this; },
            blur() { if (doc.activeElement === this) doc.activeElement = body; },
            scrollIntoView() {},
        }, lastnosti || {});
        vsi.push(x);
        return x;
    }
    const okolje = {
        addEventListener() {},
    };
    function pozeni(smer) {
        const f = new Function('window', 'document', 'innerWidth', 'innerHeight', 'getComputedStyle',
            'return ' + js)(okolje, doc, W, H,
            (x) => ({ visibility: 'visible', display: 'block', opacity: '1', overflowX: 'visible', position: x.poz || 'static', zIndex: x.z || 'auto' }));
        return f(smer);
    }
    // OK na Googlu (KLIK_FOKUSA): skript se izvede takoj in vrne odlocitev za Kotlin (GoogleTipke.ok).
    function ok() {
        const k = vir.split('private const val KLIK_FOKUSA = """', 2)[1].split('"""', 1)[0];
        return new Function('window', 'document', 'innerWidth', 'innerHeight', 'return ' + k)(okolje, doc, W, H);
    }
    return { doc, el, pozeni, ok };
}

const POVEZAVA = ['a[href]', 'a[href]'];
function googlovaStran() {
    const s = stran();
    const meni = s.el('DIV', ['[role="button"]'], [16, 16, 48, 48], null, '');
    const logo = s.el('A', POVEZAVA, [700, 12, 160, 56], null, 'Google');
    const prijava = s.el('A', POVEZAVA, [1760, 16, 120, 48], null, 'Prijava');
    const obrazec = s.el('FORM', ['form', 'form[role="search"]'], [200, 90, 1200, 60], null, '');
    const polje = s.el('TEXTAREA', ['textarea', 'textarea[name="q"]', 'form[role="search"] textarea', 'input,textarea,select'],
        [220, 100, 1100, 40], obrazec, '');
    const vse = s.el('A', POVEZAVA, [200, 170, 60, 30], null, 'Vse');
    const slike = s.el('A', POVEZAVA, [280, 170, 80, 30], null, 'Slike');
    const z1 = s.el('A', POVEZAVA, [200, 300, 600, 40], null, 'Prvi zadetek');
    const z2 = s.el('A', POVEZAVA, [200, 420, 600, 40], null, 'Drugi zadetek');
    return { s, meni, logo, prijava, polje, vse, slike, z1, z2 };
}

// ---------------------------------------------------------------- glava strani
// Lastnik 2. 11. 2026 (TV, mobilna postavitev): fokus v iskalnem polju takoj odpre Googlovo prekrivko s predlogi in
// tipkovnico, zato polje ne sme biti postaja na poti GOR. Iz vrha zadetkov (vrstica zavihkov) gre GOR v glavo; polje je
// v glavi le en element (samo nas okvir, brez fokusa), do njega LEVO/DESNO; sele OK nanj ga odpre.
{
    const g = googlovaStran();
    g.z1.focus();
    g.s.pozeni('UP');
    preveri('GOR s prvega zadetka: vrstica zavihkov', g.vse, g.s.doc.activeElement);
    let o = g.s.pozeni('UP');
    preveri('GOR iz zavihkov ni "polje" (0a760199: fokus v polje odpre Googlovo prekrivko)', true, o !== 'polje');
    preveri('GOR iz zavihkov: element glave (polje samo z okvirjem)', 'ok - glava polje', o);
    preveri('polje NI fokusirano (prekrivka in tipkovnica se ne odpreta)', true, g.s.doc.activeElement !== g.polje);
    g.s.pozeni('LEFT');
    preveri('LEVO s polja v glavi: Prijava', g.prijava, g.s.doc.activeElement);
    g.s.pozeni('LEFT');
    preveri('LEVO po glavi: logotip', g.logo, g.s.doc.activeElement);
    g.s.pozeni('LEFT');
    preveri('LEVO po glavi: meni', g.meni, g.s.doc.activeElement);
    preveri('LEVO na zacetku glave: rob (stranska vrstica)', 'rob', g.s.pozeni('LEFT'));
    preveri('GOR iz glave: rob (naslovna vrstica brskalnika)', 'rob', g.s.pozeni('UP'));
    g.s.pozeni('RIGHT'); g.s.pozeni('RIGHT');
    preveri('DESNO po glavi: Prijava', g.prijava, g.s.doc.activeElement);
    preveri('DESNO s Prijava: polje (brez fokusa)', 'ok - glava polje', g.s.pozeni('RIGHT'));
    preveri('fokusa ni na polju', true, g.s.doc.activeElement !== g.polje);
    preveri('OK na polju v glavi: pravi dotik (sele zdaj ga uporabnik odpre)', 'dotik:770,120', g.s.ok());
    g.s.pozeni('DOWN');
    preveri('DOL s polja v glavi: vrstica zavihkov', g.vse, g.s.doc.activeElement);
    g.s.pozeni('UP'); g.s.pozeni('LEFT');
    g.s.pozeni('DOWN');
    preveri('DOL s Prijava: vrstica zavihkov (ne polje)', g.vse, g.s.doc.activeElement);
    g.s.pozeni('DOWN');
    preveri('DOL z zavihkov: prvi zadetek', g.z1, g.s.doc.activeElement);
    g.s.pozeni('DOWN');
    preveri('DOL med zadetki (kot prej)', g.z2, g.s.doc.activeElement);
    // Uporabnik je polje odprl (pravi fokus): GOR gre na glavo nad njim, DOL na zavihke.
    g.polje.focus();
    g.s.pozeni('UP');
    preveri('GOR iz fokusiranega polja: logotip nad njim', g.logo, g.s.doc.activeElement);
    g.polje.focus();
    g.s.pozeni('DOWN');
    preveri('DOL iz fokusiranega polja: zavihki', g.vse, g.s.doc.activeElement);
    // Brez izbranega elementa (iz naslovne vrstice brskalnika): DOL na zavihke, GOR v glavo.
    g.s.doc.activeElement = g.s.doc.body;
    g.s.pozeni('DOWN');
    preveri('DOL brez izbire: zavihki', g.vse, g.s.doc.activeElement);
}
// Namizna postavitev: Prijava v isti vrsti kot polje.
{
    const s = stran();
    const obrazec = s.el('FORM', ['form', 'form[role="search"]'], [200, 20, 1200, 60], null, '');
    const polje = s.el('TEXTAREA', ['textarea', 'textarea[name="q"]', 'form[role="search"] textarea'], [220, 30, 1100, 40], obrazec, '');
    const prijava = s.el('A', POVEZAVA, [1760, 26, 120, 48], null, 'Prijava');
    const vse = s.el('A', POVEZAVA, [200, 100, 60, 30], null, 'Vse');
    s.el('A', POVEZAVA, [200, 300, 600, 40], null, 'Zadetek');
    vse.focus();
    preveri('namizna postavitev: GOR iz zavihkov na polje brez fokusa', 'ok - glava polje', s.pozeni('UP'));
    s.pozeni('RIGHT');
    preveri('namizna postavitev: DESNO s polja na Prijava', prijava, s.doc.activeElement);
    s.pozeni('LEFT');
    preveri('namizna postavitev: LEVO s Prijava nazaj na polje (brez fokusa)', true, s.doc.activeElement !== polje);
}

// ---------------------------------------------------------------- pogovorno okno (»Preveri nastavitve«)
{
    const g = googlovaStran();
    // Okno ima svoj obrazec cez sirino v zgornji polovici: stara koda je po njem merila dno iskalnega polja in
    // izlocila vse gumbe okna nad tem dnom.
    const okno = g.s.el('DIV', ['[role="dialog"]', '[aria-modal="true"]'], [560, 150, 800, 480], null, 'Preveri nastavitve');
    const obrazecOkna = g.s.el('FORM', ['form'], [560, 150, 800, 480], okno, '');
    const vec = g.s.el('A', POVEZAVA, [600, 250, 140, 30], okno, 'Vec o tem');
    const prilagodi = g.s.el('BUTTON', ['button', 'a[href],button'], [700, 560, 200, 50], okno, 'Prilagodi');
    const razumem = g.s.el('BUTTON', ['button', 'a[href],button'], [1000, 560, 200, 50], okno, 'Razumem');
    g.z1.focus();
    let o = g.s.pozeni('DOWN');
    preveri('odprto okno: prva tipka v okno (prvi element okna)', vec, g.s.doc.activeElement);
    preveri('odgovor pove, da je v oknu', true, / okno /.test(o));
    g.s.pozeni('DOWN');
    preveri('DOL v oknu: Prilagodi', prilagodi, g.s.doc.activeElement);
    g.s.pozeni('RIGHT');
    preveri('DESNO v oknu: Razumem', razumem, g.s.doc.activeElement);
    preveri('DESNO na robu okna: ostane v oknu (ne uide na stran ali v meni)', 'okno', g.s.pozeni('RIGHT'));
    preveri('fokus ostane na Razumem', razumem, g.s.doc.activeElement);
    g.s.pozeni('LEFT');
    preveri('LEVO v oknu: Prilagodi', prilagodi, g.s.doc.activeElement);
    preveri('LEVO na robu okna: ostane v oknu (ne odpre stranske vrstice)', 'okno', g.s.pozeni('LEFT'));
    preveri('DOL na dnu okna: ostane v oknu (ne drsi strani)', 'okno', g.s.pozeni('DOWN'));
    g.polje.focus();
    g.s.pozeni('UP');
    preveri('tudi iz iskalnega polja gre fokus v okno, ne v glavo', vec, g.s.doc.activeElement);
    // Zaprto okno (display:none prek velikosti 0): navigacija spet po strani.
    okno.r = { left: 0, top: 0, width: 0, height: 0, right: 0, bottom: 0 };
    [obrazecOkna, vec, prilagodi, razumem].forEach((x) => { x.r = okno.r; });
    g.z1.focus();
    g.s.pozeni('DOWN');
    preveri('zaprto okno: DOL spet med zadetki', g.z2, g.s.doc.activeElement);
}
// Okno, ki ga prekriva drug element (ni na vrhu), ni modalno.
{
    const g = googlovaStran();
    const okno = g.s.el('DIV', ['[role="dialog"]'], [560, 600, 400, 200], null, '');
    g.s.el('BUTTON', ['button', 'a[href],button'], [600, 650, 100, 40], okno, 'Skrit');
    g.s.el('DIV', [], [0, 500, 1920, 580], null, '');   // prekrivka nad oknom
    g.z1.focus();
    g.s.pozeni('DOWN');
    preveri('okno pod drugim elementom ne zajame tipk', g.z2, g.s.doc.activeElement);
}
// Okno v tujem okvirju (iframe cez sredino zaslona): tipko dobi stran sama.
{
    const g = googlovaStran();
    const ovoj = g.s.el('DIV', [], [0, 0, 1920, 1080], null, '', { poz: 'fixed' });
    g.s.el('IFRAME', ['iframe'], [460, 240, 1000, 600], ovoj, '');
    g.z1.focus();
    preveri('okno v tujem okvirju: fokus v okvir in prvi Tab naprej (tuje+)', 'tuje+', g.s.pozeni('DOWN'));
    preveri('nato tipke v okvir (tuje)', 'tuje', g.s.pozeni('UP'));
}
// TV 2. 11. 2026 (48df9e6b): OK na avatarju odpre Googlovo plosco racuna - tuj okvir (iframe) desno pod glavo, ne cez
// cel zaslon in ne role=dialog. Nobena tipka ni prisla vanj. Zdaj je to pas »tuje«: smeri so Tab/Shift+Tab v okvir,
// OK je Enter, Nazaj Escape; ko okvir izgine, spet nasi pasovi.
{
    const g = googlovaStran();
    const avatar = g.s.el('A', POVEZAVA, [1840, 16, 48, 48], null, '');
    const ovoj = g.s.el('DIV', [], [1080, 160, 800, 780], null, '', { poz: 'absolute', z: '991' });
    const okvir = g.s.el('IFRAME', ['iframe'], [1080, 160, 800, 780], ovoj, '');
    avatar.focus();
    preveri('plosca racuna (iframe, ne cez cel zaslon): DOL -> tuje+ (prej nasi pasovi, nic v okvir)', 'tuje+', g.s.pozeni('DOWN'));
    preveri('fokus je v okvirju', okvir, g.s.doc.activeElement);
    preveri('DOL v okvirju: tuje (Tab)', 'tuje', g.s.pozeni('DOWN'));
    preveri('LEVO v okvirju: tuje (Shift+Tab, ne stranska vrstica)', 'tuje', g.s.pozeni('LEFT'));
    preveri('UP v okvirju: tuje (ne naslovna vrstica)', 'tuje', g.s.pozeni('UP'));
    preveri('OK s fokusom v okvirju: Enter (ne dotik na sredini plosce)', 'enter', g.s.ok());
    preveri('Nazaj: TUJE pove, da je okno odprto (Kotlin poslje Escape)', 'tuje', g.s.pozeni('TUJE'));
    okvir.r = ovoj.r = { left: 0, top: 0, width: 0, height: 0, right: 0, bottom: 0 };
    preveri('zaprta plosca: TUJE -> ne (Nazaj po zgodovini)', 'ne', g.s.pozeni('TUJE'));
    g.z1.focus();
    g.s.pozeni('DOWN');
    preveri('zaprta plosca: spet nasi pasovi', g.z2, g.s.doc.activeElement);
}
// Okvirji, ki niso okno nad stranjo, ostanejo del strani.
{
    const g = googlovaStran();
    const v = g.s.el('DIV', [], [900, 500, 600, 300], null, '');
    g.s.el('IFRAME', ['iframe'], [900, 500, 600, 300], v, '');     // vsebina v toku strani (zemljevid)
    const w = g.s.el('DIV', [], [1500, 300, 100, 100], null, '', { poz: 'absolute', z: '5' });
    g.s.el('IFRAME', ['iframe'], [1500, 300, 100, 100], w, '');    // majhen pripomocek
    g.z1.focus();
    g.s.pozeni('DOWN');
    preveri('okvir v toku strani in majhen okvir nista tuje okno', g.z2, g.s.doc.activeElement);
    preveri('TUJE brez okna: ne', 'ne', g.s.pozeni('TUJE'));
}

// ---------------------------------------------------------------- OK na prijavi (accounts.google.com)
// Lastnik 2. 11. 2026: OK na »Prikazi geslo« ni naredil nicesar (Enter potrditvenega polja ne preklopi), »Ste pozabili
// geslo?« (gumb v obrazcu) se ni sprozil. Kontrole dobijo pravi dotik, povezave ostanejo pri Enter.
{
    const s = stran();
    const geslo = s.el('INPUT', ['input'], [700, 300, 500, 56], null, '');
    const prikazi = s.el('INPUT', ['input', 'input[type="checkbox"]'], [700, 380, 24, 24], null, '');
    const pozabil = s.el('BUTTON', ['button'], [700, 440, 220, 40], null, 'Ste pozabili geslo?');
    const pov = s.el('A', POVEZAVA, [700, 520, 200, 30], null, 'Pomoc');
    const ovoj = s.el('DIV', ['[tabindex]:not([tabindex="-1"])'], [700, 600, 600, 80], null, '');
    const vOvoju = s.el('A', POVEZAVA, [710, 610, 300, 30], ovoj, 'Zadetek');
    geslo.focus();
    preveri('OK na polju za geslo: pravi dotik (odpre tipkovnico)', 'dotik:950,328', s.ok());
    prikazi.focus();
    preveri('OK na »Prikazi geslo«: pravi dotik (prej Enter, ki ga ne preklopi)', 'dotik:712,392', s.ok());
    pozabil.focus();
    preveri('OK na »Ste pozabili geslo?« (gumb): pravi dotik', 'dotik:810,460', s.ok());
    pov.focus();
    preveri('OK na povezavi: Enter (kot prej, google.com/goto)', 'enter', s.ok());
    ovoj.focus();
    preveri('OK na ovoju zadetka: fokus na povezavo v njem in Enter (kot prej)', 'enter', s.ok());
    preveri('fokus je na povezavi v ovoju', vOvoju, s.doc.activeElement);
    // Narisan okvir potrditvenega polja nad skritim vhodom: dotik ga ne bi dosegel, Presledek ga preklopi.
    s.el('DIV', [], [700, 380, 24, 24], null, '');
    prikazi.focus();
    preveri('potrditveno polje pod drugim elementom: Presledek', 'space', s.ok());
}

// Kotlin mora 'tuje' predati strani in na 'okno' ne narediti nicesar (ne naslovne vrstice, ne stranske vrstice).
const smerNaGooglu = vir.split('internal fun smerNaGooglu(', 2)[1].split('\n}\n', 1)[0];
preveri('Kotlin: tuje -> prava tipka strani', true, /"tuje"\) nativnaTipka\(wv, event\)/.test(smerNaGooglu));
preveri('Kotlin: tuje+ -> Tab naprej', true, /"tuje\+"\) nativnaTipka\(wv, KeyEvent\(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN\)\)/.test(smerNaGooglu));
preveri('Kotlin: Nazaj z odprtim tujim oknom -> Escape', true,
    /GoogleTipke\.jeTuje\(o\)\) nativnaTipka\(wv, KeyEvent\(KeyEvent\.ACTION_DOWN, KeyEvent\.KEYCODE_BACK\), KeyEvent\.KEYCODE_ESCAPE\)/.test(vir));
preveri('Kotlin: rob samo pri odgovoru rob', true, /if \(o == "rob"\)/.test(smerNaGooglu));
console.log('google_smer_test: OK (' + stevilo + ')');
