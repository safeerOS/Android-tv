#!/usr/bin/env node
// Preizkus cistilca oglasnih prekrivk (scit pred pojavnimi okni v UserScriptManager.kt) brez naprave: skripta tece v
// peskovniku z majhnim nadomestkom za dokument.   node tests/cistilec_prekrivk_test.js
//
// Zakaj: cistilec je s pravilom div[class*="mgp_ad"] odstranil cel predvajalnik, ker si predvajalnik stanje oglasa
// zapise kot razred na svojem glavnem vsebniku. Pravilo: predvajalnika (elementa z videom) in njegovih delov cistilec
// ne odstrani nikoli; oglasne prekrivke zunaj njega odstrani kot doslej.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const VIR = fs.readFileSync(path.join(__dirname, '..', 'src', 'main', 'kotlin', 'si', 'safeer', 'tv', 'UserScriptManager.kt'), 'utf8');
const ZACETEK = 'private const val ANTI_POPUNDER_SHIELD_JS = """';
const od = VIR.indexOf(ZACETEK);
assert.ok(od > 0, 'skripte scita ni v UserScriptManager.kt');
const KODA = VIR.slice(od + ZACETEK.length, VIR.indexOf('"""', od + ZACETEK.length));
assert.ok(KODA.includes('cleanAllAdOverlays') && !KODA.includes('$'), 'skripta scita ni cela ali vsebuje predlogo Kotlina');

// ---------------------------------------------------------------- majhen dokument
function El(tag, razredi, atributi) {
    this.tagName = tag.toUpperCase();
    this.className = razredi || '';
    this.attrs = atributi || {};
    this.id = this.attrs.id || '';
    this.children = [];
    this.parentNode = null;
    this.dataset = {};
    this.slog = { position: 'static', zIndex: 'auto', opacity: '1', visibility: 'visible' };
    this.offsetWidth = 100; this.offsetHeight = 40;
    this.klikov = 0;
    this.textContent = '';
}
El.prototype.dodaj = function (o) { o.parentNode = this; this.children.push(o); return o; };
El.prototype.remove = function () {
    if (!this.parentNode) return;
    this.parentNode.children = this.parentNode.children.filter((x) => x !== this);
    this.parentNode = null;
};
El.prototype.click = function () { this.klikov++; };
El.prototype.getAttribute = function (ime) { return ime === 'class' ? this.className : (ime in this.attrs ? this.attrs[ime] : null); };
El.prototype.getBoundingClientRect = function () { return { width: this.offsetWidth, height: this.offsetHeight, left: 0, top: 0 }; };
El.prototype.vDokumentu = function () { let e = this; while (e.parentNode) e = e.parentNode; return e.tagName === 'BODY'; };

function sestavljen(el, izbirnik) {
    // tag? nato poljubno .razred, #id, [atribut], [atribut="v"], [atribut*="v"], [atribut^="v"]
    const m = /^([a-zA-Z][a-zA-Z0-9-]*|\*)?((?:\.[\w-]+|#[\w-]+|\[[^\]]+\])*)$/.exec(izbirnik);
    if (!m) throw new Error('preizkus ne pozna izbirnika: ' + izbirnik);
    if (m[1] && m[1] !== '*' && el.tagName !== m[1].toUpperCase()) return false;
    const deli = m[2].match(/\.[\w-]+|#[\w-]+|\[[^\]]+\]/g) || [];
    return deli.every((d) => {
        if (d[0] === '.') return el.className.split(/\s+/).includes(d.slice(1));
        if (d[0] === '#') return el.id === d.slice(1);
        const a = /^\[([\w-]+)(?:([*^$]?=)["']?([^"'\]]*)["']?)?\]$/.exec(d);
        if (!a) throw new Error('preizkus ne pozna atributa: ' + d);
        const v = a[1] === 'class' ? el.className : (a[1] === 'id' ? (el.id || null) : el.getAttribute(a[1]));
        if (v == null) return false;
        if (!a[2]) return true;
        if (a[2] === '=') return v === a[3];
        if (a[2] === '*=') return v.includes(a[3]);
        if (a[2] === '^=') return v.startsWith(a[3]);
        return v.endsWith(a[3]);
    });
}
function ustreza(el, izbirnik) {
    return izbirnik.split(',').map((s) => s.trim()).filter(Boolean).some((en) => {
        const veriga = en.split(/\s+/);
        if (!sestavljen(el, veriga[veriga.length - 1])) return false;
        let prednik = el.parentNode;
        for (let i = veriga.length - 2; i >= 0; i--) {
            while (prednik && !(prednik.tagName !== 'BODY' && sestavljen(prednik, veriga[i]))) prednik = prednik.parentNode;
            if (!prednik) return false;
            prednik = prednik.parentNode;
        }
        return true;
    });
}
function potomci(el, izhod) { el.children.forEach((o) => { izhod.push(o); potomci(o, izhod); }); return izhod; }
El.prototype.querySelectorAll = function (s) { return potomci(this, []).filter((o) => ustreza(o, s)); };
El.prototype.querySelector = function (s) { return this.querySelectorAll(s)[0] || null; };
El.prototype.matches = function (s) { return ustreza(this, s); };
El.prototype.closest = function (s) { let e = this; while (e && e.tagName !== 'BODY') { if (ustreza(e, s)) return e; e = e.parentNode; } return null; };

function pozeni(sestavi, gostitelj) {
    const body = new El('body');
    const deli = sestavi(body);
    const intervali = [];
    const document = {
        body,
        querySelectorAll: (s) => body.querySelectorAll(s),
        querySelector: (s) => body.querySelector(s),
        addEventListener() {},
    };
    const okno = {
        document,
        location: { href: 'https://' + gostitelj + '/video', hostname: gostitelj },
        innerWidth: 400, innerHeight: 800,
        getComputedStyle: (el) => el.slog,
        setInterval: (f) => { intervali.push(f); return intervali.length; },
        MutationObserver: function () { this.observe = function () {}; },
        Date, parseInt, parseFloat, isFinite, String, Object, console,
    };
    okno.window = okno; okno.self = okno; okno.top = okno;
    vm.createContext(okno);
    vm.runInContext(KODA, okno);
    intervali.forEach((f) => f());   // se en obhod, kot po 200 ms
    return deli;
}

// ---------------------------------------------------------------- 1) predvajalnik z oglasom ostane cel
{
    const d = pozeni((body) => {
        const predvajalnik = body.dodaj(new El('div', 'playerFlvContainer mgp_container mgp_readyState mgp_adRollReady', { id: 'playerDiv_1' }));
        const video = predvajalnik.dodaj(new El('video'));
        const vsebnikOglasa = predvajalnik.dodaj(new El('div', 'mgp_adRollContainer'));
        const preskok = predvajalnik.dodaj(new El('div', 'mgp_adRollSkipButton'));
        const informacija = predvajalnik.dodaj(new El('div', 'adInformation'));
        const oglas = body.dodaj(new El('div', 'ad-zone'));
        const igralnica = body.dodaj(new El('a', '', { href: 'https://example.com/casino/x' }));
        const ovojZVideom = body.dodaj(new El('div', 'topAd'));
        ovojZVideom.dodaj(new El('video'));
        return { predvajalnik, video, vsebnikOglasa, preskok, informacija, oglas, igralnica, ovojZVideom };
    }, 'videi.example');
    assert.ok(d.predvajalnik.vDokumentu(), 'predvajalnik z razredom stanja oglasa mora ostati na strani');
    assert.ok(d.video.vDokumentu() && d.vsebnikOglasa.vDokumentu(), 'deli predvajalnika morajo ostati');
    assert.ok(d.informacija.vDokumentu(), 'splosno pravilo ne sme odstraniti dela znotraj predvajalnika');
    assert.ok(d.preskok.vDokumentu() && d.preskok.klikov >= 1, 'gumb za preskok oglasa ostane in ga samodejni preskok pritisne');
    assert.ok(!d.oglas.vDokumentu(), 'oglasna prekrivka zunaj predvajalnika se odstrani kot doslej');
    assert.ok(!d.igralnica.vDokumentu(), 'oglasna povezava se odstrani kot doslej');
    assert.ok(d.ovojZVideom.vDokumentu(), 'element, ki vsebuje video, se ne odstrani (je predvajalnik ali ga ovija)');
}

// ---------------------------------------------------------------- 2) v pravilih cistilca ni notranjosti predvajalnika
{
    const seznam = /var adSelectors = \[([\s\S]*?)\]\.join/.exec(KODA);
    assert.ok(seznam, 'seznama pravil cistilca ni');
    assert.ok(!/mgp_/.test(seznam[1]), 'pravila cistilca ne smejo ciljati notranjosti predvajalnika (mgp_)');
}

// ---------------------------------------------------------------- 3) nevidna plast cez pol zaslona se po 3 s odstrani, predvajalnik ne
{
    const d = pozeni((body) => {
        const plast = body.dodaj(new El('div', 'x'));
        plast.slog = { position: 'fixed', zIndex: '999', opacity: '0', visibility: 'visible' };
        plast.offsetWidth = 400; plast.offsetHeight = 800;
        plast.dataset.safeerNevidno = String(Date.now() - 5000);
        const zVideom = body.dodaj(new El('div', 'y'));
        zVideom.slog = { position: 'fixed', zIndex: '999', opacity: '0', visibility: 'visible' };
        zVideom.offsetWidth = 400; zVideom.offsetHeight = 800;
        zVideom.dataset.safeerNevidno = String(Date.now() - 5000);
        zVideom.dodaj(new El('video'));
        return { plast, zVideom };
    }, 'videi.example');
    assert.ok(!d.plast.vDokumentu(), 'nevidna plast za krajo klikov se odstrani');
    assert.ok(d.zVideom.vDokumentu(), 'nevidna plast z videom ni prevara');
}

console.log('cistilec_prekrivk_test: OK');
