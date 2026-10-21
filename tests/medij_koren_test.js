#!/usr/bin/env node
// Preizkus delovanja MEDIJ_KOREN_JS (TemniSlogPravila.kt / UserScriptManager.kt) brez naprave v Node.js:
// 1. S2: koren se ne oznaci v celozaslonskem nacinu (document.fullscreenElement).
// 2. S2: plezanje se ustavi pri elementu z overflow: auto|scroll.
// 3. S2: oznaka se odstrani, ko videa ni vec ali ob emptied.
// 4. S3: prekrivni sloj s position absolute|fixed, ki pokriva >80 % videa in ni potomec korena, se oznaci.
// 5. N3: catch v primeru napake izpise console.warn (brez skrivanja napak).
//
// Zagon: node tests/medij_koren_test.js
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const VIR = fs.readFileSync(path.join(__dirname, '..', 'src', 'main', 'kotlin', 'si', 'safeer', 'tv', 'TemniSlogPravila.kt'), 'utf8');
const ZACETEK = 'const val MEDIJ_KOREN_JS = """';
const od = VIR.indexOf(ZACETEK);
assert.ok(od > 0, 'MEDIJ_KOREN_JS ni najden v TemniSlogPravila.kt');
const JS_KODA = VIR.slice(od + ZACETEK.length, VIR.indexOf('"""', od + ZACETEK.length));
assert.ok(JS_KODA.includes('oznaciPrekrivke') && JS_KODA.includes('fullscreenElement'), 'JS koda ni popolna');

function ustvariOkolje() {
    const documentListeners = {};
    let fullscreenEl = null;

    function Node(tagName, id, className) {
        this.tagName = (tagName || 'DIV').toUpperCase();
        this.id = id || '';
        this.className = className || '';
        this.attrs = {};
        this.style = {};
        this.computedStyle = {
            overflowX: 'visible',
            overflowY: 'visible',
            position: 'static'
        };
        this.children = [];
        this.parentElement = null;
        this.rect = { left: 0, top: 0, right: 100, bottom: 100, width: 100, height: 100 };
        this.src = '';
        this.currentSrc = '';
        this.readyState = 4;
    }

    Node.prototype.setAttribute = function(k, v) { this.attrs[k] = String(v); };
    Node.prototype.getAttribute = function(k) { return this.attrs[k] !== undefined ? this.attrs[k] : null; };
    Node.prototype.hasAttribute = function(k) { return this.attrs[k] !== undefined; };
    Node.prototype.removeAttribute = function(k) { delete this.attrs[k]; };
    Node.prototype.getBoundingClientRect = function() { return this.rect; };

    Node.prototype.appendChild = function(child) {
        if (child.parentElement) {
            child.parentElement.removeChild(child);
        }
        child.parentElement = this;
        this.children.push(child);
        obvestiMutacije();
        return child;
    };

    Node.prototype.removeChild = function(child) {
        const idx = this.children.indexOf(child);
        if (idx >= 0) {
            this.children.splice(idx, 1);
            child.parentElement = null;
            obvestiMutacije();
        }
        return child;
    };

    Node.prototype.contains = function(other) {
        let cur = other;
        while (cur) {
            if (cur === this) return true;
            cur = cur.parentElement;
        }
        return false;
    };

    Node.prototype.querySelectorAll = function(sel) {
        const rez = [];
        function najdi(n) {
            for (let i = 0; i < n.children.length; i++) {
                const c = n.children[i];
                if (sel === '*' || sel === 'video' && c.tagName === 'VIDEO' || sel === '[data-safeer-medij]' && c.hasAttribute('data-safeer-medij')) {
                    rez.push(c);
                }
                najdi(c);
            }
        }
        najdi(this);
        return rez;
    };

    Node.prototype.getElementsByTagName = function(tag) {
        const t = tag.toUpperCase();
        const rez = [];
        function najdi(n) {
            for (let i = 0; i < n.children.length; i++) {
                const c = n.children[i];
                if (c.tagName === t) rez.push(c);
                najdi(c);
            }
        }
        najdi(this);
        return rez;
    };

    const observerCallbacks = [];
    function obvestiMutacije() {
        for (let i = 0; i < observerCallbacks.length; i++) {
            observerCallbacks[i]();
        }
    }

    const docEl = new Node('HTML', 'html');
    const body = new Node('BODY', 'body');
    docEl.appendChild(body);

    function MockMutationObserver(cb) {
        this.cb = cb;
    }
    MockMutationObserver.prototype.observe = function() {
        observerCallbacks.push(this.cb);
    };

    const opozorila = [];
    const sandbox = {
        document: {
            documentElement: docEl,
            body: body,
            get fullscreenElement() { return fullscreenEl; },
            set fullscreenElement(v) { fullscreenEl = v; },
            getElementsByTagName: (t) => docEl.getElementsByTagName(t),
            querySelectorAll: (s) => docEl.querySelectorAll(s),
            addEventListener: (tip, poslusavec) => {
                if (!documentListeners[tip]) documentListeners[tip] = [];
                documentListeners[tip].push(poslusavec);
            },
            elementsFromPoint: (x, y) => []
        },
        window: {
            getComputedStyle: (el) => el.computedStyle,
            MutationObserver: MockMutationObserver
        },
        MutationObserver: MockMutationObserver,
        console: {
            warn: (msg, err) => { opozorila.push({ msg, err }); },
            log: () => {},
            error: () => {}
        }
    };
    sandbox.window.document = sandbox.document;

    return {
        sandbox,
        Node,
        docEl,
        body,
        opozorila,
        sproziDogodek: (tip, tarca) => {
            if (documentListeners[tip]) {
                documentListeners[tip].forEach((fn) => fn({ target: tarca, type: tip }));
            }
        }
    };
}

console.log('medij_koren_test: zacetek preizkusov...');

// Preizkus 1: Osnovno oznacevanje korena predvajalnika
{
    const { sandbox, Node, body } = ustvariOkolje();
    const ovitek = new Node('DIV', 'player-wrapper');
    ovitek.rect = { left: 0, top: 0, right: 640, bottom: 360, width: 640, height: 360 };

    const video = new Node('VIDEO', 'vid');
    video.rect = { left: 0, top: 0, right: 640, bottom: 360, width: 640, height: 360 };
    video.src = 'https://primer.si/video.mp4';
    video.readyState = 4;

    ovitek.appendChild(video);
    body.appendChild(ovitek);

    vm.runInNewContext(JS_KODA, sandbox);

    assert.strictEqual(ovitek.getAttribute('data-safeer-medij'), '1', 'ovitek predvajalnika mora dobiti oznako data-safeer-medij');
    assert.strictEqual(body.hasAttribute('data-safeer-medij'), false, 'body NE SME dobiti oznake');
}

// Preizkus 2 (S2): V celozaslonskem nacinu se koren ne oznaci
{
    const { sandbox, Node, body } = ustvariOkolje();
    const ovitek = new Node('DIV', 'fs-player');
    ovitek.rect = { left: 0, top: 0, right: 1920, bottom: 1080, width: 1920, height: 1080 };

    const video = new Node('VIDEO', 'vid');
    video.rect = { left: 0, top: 0, right: 1920, bottom: 1080, width: 1920, height: 1080 };
    video.src = 'blob:test';
    video.readyState = 4;
    ovitek.appendChild(video);
    body.appendChild(ovitek);

    // Nastavi celozaslonski nacin
    sandbox.document.fullscreenElement = ovitek;

    vm.runInNewContext(JS_KODA, sandbox);

    assert.strictEqual(ovitek.hasAttribute('data-safeer-medij'), false, 'v celozaslonskem nacinu se koren ne oznaci');
}

// Preizkus 3 (S2): Plezanje se ustavi pri elementu z overflow: auto|scroll
{
    const { sandbox, Node, body } = ustvariOkolje();
    const app = new Node('DIV', 'app');
    app.rect = { left: 0, top: 0, right: 640, bottom: 360, width: 640, height: 360 };
    app.computedStyle.overflowY = 'auto'; // SPA vsebnik z drsenjem

    const ovitek = new Node('DIV', 'player-inner');
    ovitek.rect = { left: 0, top: 0, right: 640, bottom: 360, width: 640, height: 360 };

    const video = new Node('VIDEO', 'vid');
    video.rect = { left: 0, top: 0, right: 640, bottom: 360, width: 640, height: 360 };
    video.src = 'https://primer.si/v.mp4';
    video.readyState = 4;

    ovitek.appendChild(video);
    app.appendChild(ovitek);
    body.appendChild(app);

    vm.runInNewContext(JS_KODA, sandbox);

    assert.strictEqual(ovitek.getAttribute('data-safeer-medij'), '1', 'ovitek predvajalnika dobi oznako');
    assert.strictEqual(app.hasAttribute('data-safeer-medij'), false, 'element z overflow:auto NE SME dobiti oznake');
}

// Preizkus 4 (S2): Oznaka se odstrani, ko videa ni vec ali ob emptied
{
    const { sandbox, Node, body, sproziDogodek } = ustvariOkolje();
    const ovitek = new Node('DIV', 'player-wrap');
    ovitek.rect = { left: 0, top: 0, right: 640, bottom: 360, width: 640, height: 360 };

    const video = new Node('VIDEO', 'vid');
    video.rect = { left: 0, top: 0, right: 640, bottom: 360, width: 640, height: 360 };
    video.src = 'https://primer.si/v.mp4';
    video.readyState = 4;

    ovitek.appendChild(video);
    body.appendChild(ovitek);

    vm.runInNewContext(JS_KODA, sandbox);
    assert.strictEqual(ovitek.getAttribute('data-safeer-medij'), '1', 'ovitek predvajalnika mora biti oznacen');

    // Video izgine (odstranitev iz DOM)
    ovitek.removeChild(video);

    assert.strictEqual(ovitek.hasAttribute('data-safeer-medij'), false, 'oznaka se mora odstraniti po odstranitvi videa');

    // Ponovni preizkus z emptied
    ovitek.appendChild(video);
    assert.strictEqual(ovitek.getAttribute('data-safeer-medij'), '1', 'ovitek se ponovno oznaci ob vrnitvi videa');

    video.src = '';
    video.currentSrc = '';
    video.readyState = 0;
    sproziDogodek('emptied', video);

    assert.strictEqual(ovitek.hasAttribute('data-safeer-medij'), false, 'oznaka se mora odstraniti ob dogodku emptied');
}

// Preizkus 5 (S3): Prekrivni sloj s position: absolute|fixed, ki pokriva >80% videa
{
    const { sandbox, Node, body } = ustvariOkolje();
    const skupniOvitek = new Node('DIV', 'main-wrap');
    skupniOvitek.rect = { left: 0, top: 0, right: 1000, bottom: 800, width: 1000, height: 800 };

    const videoKoren = new Node('DIV', 'video-koren');
    videoKoren.rect = { left: 0, top: 0, right: 640, bottom: 360, width: 640, height: 360 };

    const video = new Node('VIDEO', 'vid');
    video.rect = { left: 0, top: 0, right: 640, bottom: 360, width: 640, height: 360 };
    video.src = 'https://primer.si/v.mp4';
    video.readyState = 4;
    videoKoren.appendChild(video);

    // Sosednji prekrivni sloj z gumbi nad videom (ni potomec videoKoren)
    const overlay = new Node('DIV', 'player-controls-overlay');
    overlay.rect = { left: 0, top: 0, right: 640, bottom: 360, width: 640, height: 360 };
    overlay.computedStyle.position = 'absolute';

    skupniOvitek.appendChild(videoKoren);
    skupniOvitek.appendChild(overlay);
    body.appendChild(skupniOvitek);

    vm.runInNewContext(JS_KODA, sandbox);

    assert.strictEqual(videoKoren.getAttribute('data-safeer-medij'), '1', 'video koren mora biti oznacen');
    assert.strictEqual(overlay.getAttribute('data-safeer-medij'), '1', 'prekrivni sloj sosed mora biti oznacen (S3)');
    assert.strictEqual(skupniOvitek.hasAttribute('data-safeer-medij'), false, 'skupni vecji ovitek ne sme biti oznacen');
}

// Preizkus 6 (N3): Brez skrivanja napak (console.warn)
{
    const { sandbox, opozorila } = ustvariOkolje();
    // Vsilimo napako z neveljavnim getElementsByTagName
    sandbox.document.getElementsByTagName = () => { throw new Error('vsiljena napaka za preizkus catch'); };

    vm.runInNewContext(JS_KODA, sandbox);

    assert.ok(opozorila.length > 0, 'catch mora sproziti console.warn ob napaki (N3)');
    assert.strictEqual(opozorila[0].msg, 'Safeer medij koren:', 'pravilno sporocilo opozorila');
}

console.log('medij_koren_test: VSI PREIZKUSI USPELI (OK)!');
