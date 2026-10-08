#!/usr/bin/env node
// Preizkus assets/protiadblock.js proti detektorju, ki ponovi preverbe, ki jih v praksi delajo velike strani:
// vaba (offsetParent/velikost/slog), HEAD fetch na oglasni naslov, sledilna slika (onerror), googletag.apiReady.
//   node tests/protiadblock_detektor_test.js
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const KODA = fs.readFileSync(path.join(__dirname, '..', 'assets', 'protiadblock.js'), 'utf8');

function okolje() {
    class Element {
        constructor(razred) { this.nodeType = 1; this.className = razred || ''; this.id = ''; }
        get clientHeight() { return 0; }
        get clientWidth() { return 0; }
        getBoundingClientRect() { return { x: 0, y: 0, top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0 }; }
    }
    // Blokator s kozmetičnim pravilom: vaba je display:none -> vse mere 0, offsetParent null.
    class HTMLElement extends Element {
        get offsetHeight() { return 0; }
        get offsetWidth() { return 0; }
        get offsetTop() { return 0; }
        get offsetLeft() { return 0; }
        get offsetParent() { return null; }
    }
    class HTMLImageElement extends HTMLElement {
        constructor() { super(''); this._src = ''; this.onload = null; this.onerror = null; }
        get src() { return this._src; }
        set src(v) {
            // Blokator: zahteva na sledilni naslov propade (error); sicer uspe (load).
            this._src = v;
            const blokiran = /google-analytics|doubleclick/.test(String(v));
            setTimeout(() => { if (blokiran ? this.onerror : this.onload) (blokiran ? this.onerror : this.onload)({ type: blokiran ? 'error' : 'load' }); }, 1);
        }
    }
    const ctx = {
        Element, HTMLElement, HTMLImageElement, Response: class { constructor(t, o) { this.status = (o && o.status) || 200; } },
        Proxy, URL, Object, Promise, setTimeout, String, Array, Symbol,
        Event: class Event { constructor(t) { this.type = t; } },
        location: { href: 'https://primer.test/', hostname: 'primer.test' },
        document: { body: { nodeType: 1, tag: 'BODY' }, documentElement: { nodeType: 1 } },
    };
    ctx.window = ctx;
    ctx.getComputedStyle = () => ({ display: 'none', visibility: 'hidden', opacity: '0', height: '0px', width: '0px', getPropertyValue(i) { return this[i]; } });
    ctx.fetch = (url) => Promise.reject(new TypeError('blokirano'));
    ctx.addEventListener = () => {};
    ctx.XMLHttpRequest = class { open() {} send() {} addEventListener() {} };
    vm.createContext(ctx);
    return ctx;
}

(async () => {
    const c = okolje();
    vm.runInContext(KODA, c);

    // 1. vaba z razredom oglasa: stran meri offsetParent, višino, slog
    const vaba = new c.HTMLElement(); vaba.className = 'ad-banner adsbox ad-placement carbon-ad';
    assert.ok(vaba.offsetParent !== null, 'offsetParent vabe ne sme biti null');
    assert.ok(vaba.offsetHeight > 0 && vaba.offsetWidth > 0, 'mere vabe');
    assert.strictEqual(c.getComputedStyle(vaba).display, 'block');
    assert.strictEqual(c.getComputedStyle(vaba).visibility, 'visible');

    // navaden element ostane nespremenjen (offsetParent null je zanj pravilen)
    const navaden = new c.HTMLElement(); navaden.className = 'vsebina';
    assert.strictEqual(navaden.offsetParent, null);
    assert.strictEqual(navaden.offsetHeight, 0);

    // 2. HEAD fetch na oglasni naslov (no-cors) mora uspeti
    const r = await c.fetch('https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js', { method: 'HEAD', mode: 'no-cors' });
    assert.strictEqual(r.status, 200);
    // drugi naslovi ostanejo nespremenjeni (zavrnitev)
    let zavrnjen = false;
    try { await c.fetch('https://api.primer.test/podatki'); } catch (_) { zavrnjen = true; }
    assert.ok(zavrnjen, 'neoglasna zahteva se ne sme popravljati');

    // 3. sledilna slika: onerror se ne sme sprožiti, onload mora
    const rezultat = await new Promise((resolve) => {
        const slika = new c.HTMLImageElement();
        slika.onload = () => resolve('load');
        slika.onerror = () => resolve('error');
        slika.src = 'https://www.google-analytics.com/__utm.gif?utmwv=1';
        assert.ok(String(slika.src).indexOf('google-analytics.com/__utm.gif') !== -1, 'src ostane, kot ga je nastavila stran');
    });
    assert.strictEqual(rezultat, 'load');
    // navadna slika gre mimo
    const nav = new c.HTMLImageElement(); nav.src = 'https://primer.test/a.png';
    assert.strictEqual(nav.src, 'https://primer.test/a.png');

    // 4. googletag
    const gt = c.googletag;
    assert.ok(gt && gt.apiReady, 'googletag.apiReady');
    let izvedeno = 0;
    gt.cmd.push(() => { izvedeno++; gt.pubads().enableSingleRequest().collapseEmptyDivs(); gt.defineSlot('/1/x', [300, 250], 'd').addService(gt.pubads()); gt.enableServices(); });
    assert.strictEqual(izvedeno, 1);
    assert.strictEqual(typeof gt.pubads().addEventListener, 'function');
    assert.deepStrictEqual(Array.from(gt.pubads().getSlots()), []);

    console.log('protiadblock_detektor_test: OK');
})().catch((e) => { console.error(e); process.exit(1); });
