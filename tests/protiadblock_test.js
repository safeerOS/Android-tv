#!/usr/bin/env node
// Preizkus assets/protiadblock.js brez naprave: skripta tece v peskovniku z nadomestki za DOM.   node tests/protiadblock_test.js
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const KODA = fs.readFileSync(path.join(__dirname, '..', 'assets', 'protiadblock.js'), 'utf8');

function okolje() {
    const poslusalci = [];
    class Element {
        constructor(razred, id) { this.nodeType = 1; this.className = razred || ''; this.id = id || ''; this._h = 0; this._w = 0; }
        get clientHeight() { return this._h; }
        get clientWidth() { return this._w; }
        getBoundingClientRect() { return { x: 0, y: 0, top: 0, left: 0, right: 0, bottom: 0, width: this._w, height: this._h }; }
    }
    class HTMLElement extends Element {
        get offsetHeight() { return this._h; }
        get offsetWidth() { return this._w; }
        get offsetTop() { return 0; }
        get offsetLeft() { return 0; }
    }
    class Response { constructor(telo, opis) { this.status = (opis && opis.status) || 200; this.telo = telo; } }
    const ctx = {
        Element, HTMLElement, Response, Proxy, URL, Object, Promise, setTimeout, String,
        Event: class Event { constructor(t) { this.type = t; } },
        location: { href: 'https://primer.test/', hostname: 'primer.test' },
        console,
    };
    ctx.window = ctx;
    ctx.getComputedStyle = function (el) {
        return {
            display: el._skrit ? 'none' : 'block', visibility: el._skrit ? 'hidden' : 'visible', opacity: '1', height: '0px', width: '0px',
            getPropertyValue(ime) { return this[ime]; },
        };
    };
    ctx.fetchKlici = [];
    ctx.fetch = function (url) {
        ctx.fetchKlici.push(String(url));
        return /ads\.example|googlesyndication/.test(String(url)) ? Promise.reject(new TypeError('blokirano')) : Promise.resolve({ status: 200, pravi: true });
    };
    ctx.addEventListener = function (ime, fn, zajem) { poslusalci.push({ ime, fn, zajem }); };
    ctx.dispatchEvent = function () {};
    ctx.poslusalci = poslusalci;
    class XMLHttpRequest {
        constructor() { this.slusalci = {}; this.status = 0; }
        open() {}
        send() {}
        addEventListener(ime, fn) { (this.slusalci[ime] = this.slusalci[ime] || []).push(fn); }
        dispatchEvent(e) { (this.slusalci[e.type] || []).forEach(f => f(e)); }
    }
    ctx.XMLHttpRequest = XMLHttpRequest;
    vm.createContext(ctx);
    return ctx;
}

(async () => {
    const c = okolje();
    vm.runInContext(KODA, c);
    const ZAZENI = (kodaJs) => vm.runInContext(kodaJs, c);

    // 1. Spremenljivke in knjiznice.
    assert.strictEqual(c.canRunAds, true);
    assert.strictEqual(c.adblockDetected, false);
    assert.strictEqual(c.blockAdBlock.check(), false);
    let klican = false;
    new c.FuckAdBlock({ onNotDetected() { klican = true; } });
    await new Promise(r => setTimeout(r, 30));
    assert.ok(klican, 'FuckAdBlock sporoci »ni blokatorja«');

    // 2. Vabe: sprememba merjenja samo za elemente z oglasnim imenom.
    const vaba = new c.HTMLElement('adsbox ad-banner', '');
    const navadnoPolje = new c.HTMLElement('vsebina glavni', 'okvir');
    const stevilcnik = new c.HTMLElement('header', 'nav');
    assert.strictEqual(vaba.offsetHeight, 1, 'vaba z velikostjo 0 dobi 1');
    assert.strictEqual(vaba.offsetWidth, 1);
    assert.strictEqual(vaba.clientHeight, 1);
    assert.strictEqual(vaba.getBoundingClientRect().height, 1);
    assert.strictEqual(navadnoPolje.offsetHeight, 0, 'navaden element ostane pri svojem merjenju');
    assert.strictEqual(stevilcnik.getBoundingClientRect().height, 0);
    const prava = new c.HTMLElement('ad', ''); prava._h = 250; prava._w = 300;
    assert.strictEqual(prava.offsetHeight, 250, 'prava velikost ostane');
    const sploh = new c.HTMLElement('headline loaded', 'download-bar');   // »ad« znotraj besede ne sme sprozitii
    assert.strictEqual(sploh.offsetHeight, 0);
    vaba._skrit = true;
    const slog = c.getComputedStyle(vaba);
    assert.strictEqual(slog.display, 'block');
    assert.strictEqual(slog.visibility, 'visible');
    assert.strictEqual(slog.getPropertyValue('display'), 'block');
    const slogNavadnega = c.getComputedStyle(Object.assign(new c.HTMLElement('vsebina'), { _skrit: true }));
    assert.strictEqual(slogNavadnega.display, 'none', 'navaden skrit element ostane skrit');

    // 3. Omrezne preverbe.
    const r1 = await c.fetch('https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js');
    assert.strictEqual(r1.status, 200, 'blokiran oglasni fetch uspe');
    const r2 = await c.fetch('https://ads.example/ads.js');
    assert.strictEqual(r2.status, 200);
    let napaka = null;
    try { await c.fetch('https://api.primer.test/podatki', { method: 'GET' }); } catch (e) { napaka = e; }
    assert.strictEqual(napaka, null, 'navaden naslov ostane nespremenjen');
    const r3 = await c.fetch('https://pagead2.googlesyndication.com/x', { method: 'POST' }).catch(e => e);
    assert.ok(r3 instanceof TypeError, 'POST se ne ponareja');
    const xhr = new c.XMLHttpRequest();
    xhr.open('GET', 'https://securepubads.g.doubleclick.net/tag/js/gpt.js');
    let naloZ = false; xhr.addEventListener('load', () => { naloZ = true; });
    xhr.send();
    xhr.dispatchEvent({ type: 'error' });
    assert.ok(naloZ, 'XHR na oglasni naslov konca z load');
    assert.strictEqual(xhr.status, 200);

    // 4. Blokirana oglasna skripta sprozi load namesto error.
    const zajem = c.poslusalci.find(p => p.ime === 'error' && p.zajem === true);
    assert.ok(zajem, 'poslusalec za error v fazi zajema');
    let nalozena = false, ustavljeno = false, preprecen = false;
    const skripta = { tagName: 'SCRIPT', src: 'https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js',
                      dispatchEvent(e) { if (e.type === 'load') nalozena = true; }, onload: null };
    zajem.fn({ target: skripta, stopImmediatePropagation() { ustavljeno = true; }, preventDefault() { preprecen = true; } });
    assert.ok(nalozena && ustavljeno && preprecen);
    const tuja = { tagName: 'SCRIPT', src: 'https://primer.test/app.js', dispatchEvent() { throw new Error('ne smeva'); } };
    let ust2 = false;
    zajem.fn({ target: tuja, stopImmediatePropagation() { ust2 = true; }, preventDefault() {} });
    assert.ok(!ust2, 'napaka lastne skripte ostane');

    // Ponoven zagon ne podvoji ovitkov.
    const pred = c.fetch;
    vm.runInContext(KODA, c);
    assert.strictEqual(c.fetch, pred, 'drugi zagon ne spremeni ovitka');
    console.log('protiadblock_test: OK');
})().catch(e => { console.error(e); process.exit(1); });
