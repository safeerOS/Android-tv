// Majhen ponarejen DOM za preizkuse daljinca (assets/tv_spatial.js) brez brskalnika in brez odvisnosti.
// Elementi imajo podane okvirje (koordinate pogleda), slog (position, zIndex ...) in besedilo; elementFromPoint
// vrne vrhnji element (visji z-index pritrjenih prednikov, sicer kasnejsi v dokumentu). Skript tece v vm, kot v strani.
'use strict';
const vm = require('vm');

function razbij(niz, znak) {
    const out = []; let glob = 0, nar = false, zac = 0;
    for (let i = 0; i < niz.length; i++) {
        const c = niz[i];
        if (c === '"' || c === "'") nar = !nar;
        else if (!nar && (c === '[' || c === '(')) glob++;
        else if (!nar && (c === ']' || c === ')')) glob--;
        else if (!nar && glob === 0 && c === znak) { out.push(niz.slice(zac, i)); zac = i + 1; }
    }
    out.push(niz.slice(zac));
    return out.map((s) => s.trim()).filter(Boolean);
}
function ujemaSestavljen(el, s) {
    let i = 0;
    const tag = /^([a-zA-Z][\w-]*|\*)/.exec(s);
    if (tag) { if (tag[1] !== '*' && el.tagName !== tag[1].toUpperCase()) return false; i = tag[0].length; }
    while (i < s.length) {
        const r = s.slice(i);
        let m;
        if ((m = /^#([\w-]+)/.exec(r))) { if (el.id !== m[1]) return false; }
        else if ((m = /^\.([\w-]+)/.exec(r))) { if (!el.classList.contains(m[1])) return false; }
        else if ((m = /^\[\s*([\w-]+)\s*(?:([*^$~|]?=)\s*["']?([^"'\]]*)["']?)?\s*\]/.exec(r))) {
            const v = el.getAttribute(m[1]);
            if (v === null) return false;
            const p = m[3];
            if (m[2] === '=' && v !== p) return false;
            if (m[2] === '*=' && v.indexOf(p) === -1) return false;
            if (m[2] === '^=' && v.indexOf(p) !== 0) return false;
            if (m[2] === '$=' && !v.endsWith(p)) return false;
        } else if ((m = /^:not\((.*?)\)(?![^(]*\))/.exec(r))) { if (ujemaSestavljen(el, m[1])) return false; }
        else return false;                       // drugi psevdo razredi (:focus ...) v preizkusu ne veljajo
        i += m[0].length;
    }
    return true;
}
function ujema(el, izbirnik) {
    return razbij(izbirnik, ',').some((sel) => {
        const deli = sel.replace(/\s*>\s*/g, ' ').split(/\s+(?![^\[]*\])/).filter(Boolean);
        if (!ujemaSestavljen(el, deli[deli.length - 1])) return false;
        let p = el.parentElement;
        for (let k = deli.length - 2; k >= 0; k--) {
            while (p && !ujemaSestavljen(p, deli[k])) p = p.parentElement;
            if (!p) return false;
            p = p.parentElement;
        }
        return true;
    });
}

function ustvari(nastavitve) {
    const n = Object.assign({ sirina: 1920, visina: 1080, scrollY: 0, drsenjeZaklenjeno: false, visinaStrani: 1080 }, nastavitve || {});
    const poslusalci = {};
    const dotiki = [], kliki = [], drsenja = [], zetoni = [];
    const zeton = n.zeton || 'z'.repeat(48);
    // Kot ChromiumEngineView: nakljucni del imen izvozov (nov ob vsaki strani) in imena izvirnikov (na pogled).
    const nonce = n.nonce || '0f1e2d3c4b5a6978';
    const nonceIzvirnikov = n.nonceIzvirnikov || '8796a5b4c3d2e1f0';
    let stevec = 0;
    class Seznam {
        constructor(el) { this.el = el; }
        _s() { return (this.el._razred || '').split(/\s+/).filter(Boolean); }
        contains(c) { return this._s().indexOf(c) !== -1; }
        add(...c) { const s = this._s(); c.forEach((x) => { if (s.indexOf(x) === -1) s.push(x); }); this.el._razred = s.join(' '); }
        remove(...c) { this.el._razred = this._s().filter((x) => c.indexOf(x) === -1).join(' '); }
    }
    class El {
        constructor(tag, o) {
            o = o || {};
            this.tagName = tag.toUpperCase(); this.nodeType = 1; this.children = []; this.parentElement = null;
            this.attrs = Object.assign({}, o.attrs || {}); this._razred = o.razred || '';
            this.okvir = o.okvir || null; this.slog = Object.assign({}, o.slog || {}); this._besedilo = o.besedilo || '';
            this.style = {}; this.classList = new Seznam(this); this.isConnected = true; this._st = stevec++;
            this.scrollTop = 0; this.scrollLeft = 0; this.scrollHeight = 0; this.clientHeight = 0; this.scrollWidth = 0; this.clientWidth = 0;
        }
        get className() { return this._razred; } set className(v) { this._razred = v; }
        get id() { return this.attrs.id || ''; } set id(v) { this.attrs.id = v; }
        get innerText() { return this._besedilo + this.children.map((c) => ' ' + c.innerText).join(''); }
        get textContent() { return this.innerText; }
        set innerHTML(_) {}
        get isContentEditable() { return false; }
        get tabIndex() { return Number(this.attrs.tabindex || -1); } set tabIndex(v) { this.attrs.tabindex = String(v); }
        getAttribute(a) { if (a === 'class') return this._razred; return Object.prototype.hasOwnProperty.call(this.attrs, a) ? this.attrs[a] : null; }
        hasAttribute(a) { return this.getAttribute(a) !== null; }
        setAttribute(a, v) { if (a === 'class') this._razred = String(v); else this.attrs[a] = String(v); }
        removeAttribute(a) { delete this.attrs[a]; }
        appendChild(c) { c.parentElement = this; this.children.push(c); return c; }
        dodaj(...c) { c.forEach((x) => this.appendChild(x)); return this; }
        vsi() { const out = []; (function hodi(e) { e.children.forEach((c) => { out.push(c); hodi(c); }); })(this); return out; }
        querySelectorAll(s) { return this.vsi().filter((e) => ujema(e, s)); }
        querySelector(s) { return this.querySelectorAll(s)[0] || null; }
        matches(s) { return ujema(this, s); }
        closest(s) { let p = this; while (p && p.nodeType === 1) { if (ujema(p, s)) return p; p = p.parentElement; } return null; }
        contains(o) { while (o) { if (o === this) return true; o = o.parentElement; } return false; }
        getBoundingClientRect() {
            const r = this.okvir || [0, 0, 0, 0];
            return { left: r[0], top: r[1], width: r[2], height: r[3], right: r[0] + r[2], bottom: r[1] + r[3], x: r[0], y: r[1] };
        }
        fokusljiv() {
            if (this.attrs.tabindex !== undefined) return true;
            return ['BUTTON', 'INPUT', 'TEXTAREA', 'SELECT', 'IFRAME'].indexOf(this.tagName) !== -1 || (this.tagName === 'A' && this.hasAttribute('href'));
        }
        focus() {
            // Kot brskalnik: element z display:none (tudi prednik) fokusa ne dobi.
            for (let p = this; p && p.nodeType === 1; p = p.parentElement) { if ((p.slog || {}).display === 'none') return; }
            if (!this.fokusljiv() || doc.activeElement === this) return;
            doc.activeElement = this;
            (poslusalci.focusin || []).forEach((f) => { try { f({ target: this }); } catch (_) {} });
        }
        blur() { if (doc.activeElement === this) doc.activeElement = doc.body; }
        click() { kliki.push(this); }
        dispatchEvent(e) { if (e && e.type === 'click') kliki.push(this); return true; }
        scrollIntoView() {}
        addEventListener() {} removeEventListener() {}
    }
    const html = new El('html', { okvir: [0, 0, n.sirina, n.visina] });
    const body = new El('body', { okvir: [0, 0, n.sirina, n.visina] });
    const head = new El('head');
    html.dodaj(head, body);
    const doc = {
        documentElement: html, body, head, activeElement: body, readyState: 'complete', fullscreenElement: null,
        get scrollingElement() { return html; },
        createElement: (t) => new El(t),
        getElementById: (id) => html.vsi().filter((e) => e.id === id)[0] || null,
        querySelectorAll: (s) => html.vsi().filter((e) => ujema(e, s)),
        querySelector: (s) => doc.querySelectorAll(s)[0] || null,
        addEventListener: (t, f) => { (poslusalci[t] = poslusalci[t] || []).push(f); },
        removeEventListener: () => {},
        exitFullscreen: () => {},
        elementFromPoint(x, y) {
            let naj = null, najZ = -Infinity, najSt = -1;
            body.vsi().forEach((e) => {
                if (!e.okvir) return;
                const s = slog(e);
                if (s.display === 'none' || s.visibility === 'hidden' || s.pointerEvents === 'none') return;
                // Brskalnik zadetka ne racuna z metodami strani: izvirni okvir (stran lahko prepise getBoundingClientRect).
                const r = El.prototype.getBoundingClientRect.call(e);
                if (x < r.left || x >= r.right || y < r.top || y >= r.bottom) return;
                let z = 0, p = e;
                while (p && p.nodeType === 1) { const sp = slog(p); if (sp.position === 'fixed' || sp.position === 'absolute') { const zz = parseInt(sp.zIndex, 10); if (zz > z) z = zz; } p = p.parentElement; }
                if (z > najZ || (z === najZ && e._st > najSt)) { naj = e; najZ = z; najSt = e._st; }
            });
            return naj || body;
        },
    };
    html.scrollHeight = n.visinaStrani; html.clientHeight = n.visina; html.scrollWidth = n.sirina; html.clientWidth = n.sirina;
    function slog(e) {
        return Object.assign({ display: 'block', visibility: 'visible', opacity: '1', position: 'static', zIndex: 'auto',
            pointerEvents: 'auto', overflowX: 'visible', overflowY: 'visible', outlineStyle: 'none', outlineWidth: '0px' }, e.slog || {});
    }
    const win = {
        innerWidth: n.sirina, innerHeight: n.visina, scrollX: 0, scrollY: n.scrollY, devicePixelRatio: 1,
        location: { href: 'https://primer.test/', hostname: 'primer.test', pathname: '/', protocol: 'https:' },
        getComputedStyle: slog,
        scrollBy(a, b) {
            const dx = typeof a === 'object' ? (a.left || 0) : (a || 0), dy = typeof a === 'object' ? (a.top || 0) : (b || 0);
            drsenja.push([dx, dy]);
            if (n.drsenjeZaklenjeno) return;
            win.scrollX = Math.max(0, win.scrollX + dx);
            win.scrollY = Math.max(0, Math.min(html.scrollHeight - html.clientHeight, win.scrollY + dy));
            html.scrollTop = win.scrollY;
        },
        scrollTo() {}, addEventListener() {}, removeEventListener() {},
        setTimeout: () => 0, clearTimeout() {}, setInterval: () => 0, clearInterval() {},
        requestAnimationFrame: () => 0, cancelAnimationFrame() {},
        MutationObserver: class { observe() {} disconnect() {} },
        PointerEvent: class { constructor(t) { this.type = t; } }, MouseEvent: class { constructor(t) { this.type = t; } },
        KeyboardEvent: class { constructor(t) { this.type = t; } }, Event: class { constructor(t) { this.type = t; } },
        sessionStorage: { getItem: () => null, setItem() {}, removeItem() {} },
        localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
        console: { log() {}, warn() {}, error() {} },
        // Kot ChromiumEngineView: dotik velja samo z zetonom skripta (n.zeton = vstavljen zeton, kot UserScriptManager).
        SafeerBridge: { triggerNativeTap(x, y, z) { zetoni.push(z); if (z !== zeton) return false; dotiki.push([x, y]); return true; }, onScrollChanged() {} },
        document: doc,
    };
    html.scrollTop = n.scrollY;
    win.window = win; win.self = win; win.top = win;
    const ctx = vm.createContext(win);
    let odtis = null;
    // Ura izvirnikov (performance.now ob zacetku dokumenta, ChromiumEngineView.IZVIRNIKI_JS): vsaka tipka jo premakne za 100 ms.
    const ura = { t: 1000 };
    // Kot ChromiumEngineView.IZVIRNIKI_JS: izvirniki metod, shranjeni pred skriptami strani (nespremenljiva lastnost).
    // Izvirniki so iz zunanjega sveta preizkusa: stran (koda v vm) jih ne more prepisati, kot v brskalniku.
    const fokusIzvirnik = El.prototype.focus;
    const mostZacetka = win.SafeerBridge;
    if (n.izvirniki !== false) {
        const imena = ['__safeer_izvirniki_' + nonceIzvirnikov, '__safeer_izvirniki'];
        for (const ime of imena) Object.defineProperty(win, ime, { writable: false, configurable: false, enumerable: false, value: Object.freeze({
            apply: Reflect.apply, gbcr: El.prototype.getBoundingClientRect, efp: doc.elementFromPoint, contains: El.prototype.contains,
            tag: function () { return this.tagName; },
            src: function () { if (this.tagName !== 'IFRAME') throw new TypeError('ni okvir'); return this.getAttribute('src') || ''; },
            rl: function () { return this.left; }, rt: function () { return this.top; },
            rw: function () { return this.width; }, rh: function () { return this.height; },
            now: function () { return ura.t; }, perf: null,
            dp: Object.defineProperty, gopd: Object.getOwnPropertyDescriptor,
            ctl: function () { if (this.tagName !== 'LABEL') throw new TypeError('ni oznaka'); return this.control || null; },
            typ: function () { if (this.tagName !== 'INPUT') throw new TypeError('ni polje'); return (El.prototype.getAttribute.call(this, 'type') || 'text').toLowerCase(); },
            fokus: function (o) { return fokusIzvirnik.call(this, o); },
            akt: function () { return doc.activeElement; },
            dis: function () { if (this.tagName !== 'INPUT') throw new TypeError('ni polje'); return this.disabled === true; },
            // Kot IZVIRNIKI_JS (tretji neodvisni pregled 5. 11. 2026, N5): most in metoda ob zacetku dokumenta.
            most: mostZacetka, tap: mostZacetka ? mostZacetka.triggerNativeTap : null }) });
    }
    // Stari dokaz (FNV-1a, 32 bitov) - samo za preizkus starega skripta brez popravka (SAFEER_VSE_NAPAKE=1).
    function odtisStevilo(z, niz) {
        let h = 2166136261;
        const v = z + '|' + niz + '|' + z;
        for (let i = 0; i < v.length; i++) { h = (h ^ v.charCodeAt(i)) >>> 0; h = Math.imul(h, 16777619) >>> 0; }
        return h;
    }
    // Cetrti slepi pregled 5. 11. 2026: dokaz je HMAC-SHA256 (TvTipke.odtisHex) - neodvisna izvedba node:crypto, ne koda skripta.
    let hmacSkript = false;
    const hmacHex = (z, niz) => require('crypto').createHmac('sha256', z).update(niz).digest('hex');
    const odtisTipke = (z, niz) => (hmacSkript ? hmacHex(z, niz) : odtisStevilo(z, niz));
    let stIzziva = 0;
    // Ime z nakljucnim delom; brez njega staro ime (preizkus starega skripta brez popravka, SAFEER_VSE_NAPAKE=1).
    const izvoz = (ime) => (typeof win[ime + '_' + nonce] === 'function' ? win[ime + '_' + nonce] : win[ime]);
    const noviDokaz = () => typeof win['_safeer_tipka_' + nonce] === 'function';
    // Argumenti klica _safeer_tipka za stevec st (kot TvTipke.ukazTipke); stari skript: (izziv, dokaz).
    function dokazTipke(st) {
        const iz = st.toString(16).padStart(32, '0');
        return noviDokaz() ? [iz, st, odtisTipke(zeton, 'tipka|' + st + '|' + iz)] : [iz, odtisTipke(zeton, 'tipka|' + iz)];
    }
    // Kot TvTipke.ukazTipke: aplikacija pred klicem ob tipki daljinca poslje izziv, strogo narascajoc stevec in dokaz.
    function tipka() {
        ura.t += 100;
        if (typeof izvoz('_safeer_tipka') !== 'function') return false;
        return izvoz('_safeer_tipka')(...dokazTipke(++stIzziva));
    }
    return {
        window: win, document: doc, body, El, dotiki, kliki, drsenja,
        el: (tag, o) => new El(tag, o),
        zetoni, zeton, nonce, nonceIzvirnikov, izvoz,
        // Kot UserScriptManager.tvSpatialJs: zeton namesto mesta v besedilu skripta.
        nalozi(vir) {
            // Odtis zetona iz istega skripta (SAFEER_DOKAZ), kot ga bi izracunala aplikacija.
            const blok = (ime) => { const i = vir.indexOf('/* ' + ime + '_ZACETEK'), j = vir.indexOf('/* ' + ime + '_KONEC */'); return i >= 0 && j > i ? vir.slice(i, j) : ''; };
            const b = blok('SAFEER_ZNAK_NAMESTITVE') + blok('SAFEER_DOKAZ');
            if (b.indexOf('function odtisZetona') !== -1) odtis = new Function(b + '\nreturn odtisZetona;')();
            hmacSkript = b.indexOf('SAFEER_HMAC_ZACETEK') !== -1;
            if (hmacSkript) odtis = (z, niz) => 'safeer-' + hmacHex(z, niz);
            const izv = n.izvirniki === false ? 'ne' : 'da';
            // Samo preizkus: kljuka v zaprtju skripta (izbira kot iz smeri daljinca, samodejni vstop v okno). V aplikaciji je ni;
            // stran teh funkcij ne doseze (neodvisni pregled 5. 11. 2026: _safeer_highlight in _safeer_vstopi_v_plast nista vec izvoza).
            const kljuka = ';try { Object.defineProperty(window, "__preizkus", { value: { ' +
                'izberi: function (el) { if (!porabiTipko()) return false; if (!isActionable(el) || !jeUporaben(el, true)) return false; ' +
                'vTipki = true; try { return highlightElement(el); } finally { vTipki = false; } }, ' +
                'vstopi: function () { return vstopiVNovoPlast(); }, pocisti: function () { return highlightElement(null); }, ' +
                'potrjena: function () { return izbiraPotrjena; }, ' +
                'medTipko: function (f) { vTipki = true; try { return f(); } finally { vTipki = false; } }, ' +
                'izvozi: function (ime, fn) { return izvoziZaAplikacijo(ime, fn); }, oznaci: function (el) { return highlightElement(el); } } }); } catch (_) {}\n';
            const konec = vir.lastIndexOf('})();');
            const s2 = konec > 0 && vir.indexOf('function highlightElement') !== -1 ? vir.slice(0, konec) + kljuka + vir.slice(konec) : vir;
            vm.runInContext(s2.split('__SAFEER_ZETON_DOTIKA__').join(zeton).split('__SAFEER_IZVIRNIKI__').join(izv)
                .split('__SAFEER_NONCE_IZVIRNIKOV__').join(nonceIzvirnikov).split('__SAFEER_NONCE__').join(nonce)
                .split('__SAFEER_PREJ_NONCE__').join(n.prejNonce || ''), ctx);
        },
        ura, tipka, odtisStevilo: (z, niz) => odtisTipke(z, niz), dokazTipke, stevecTipk: () => stIzziva,
        // Koda strani v istem svetu (npr. prepis vgrajenih metod pred nasim skriptom).
        izvedi(koda) { return vm.runInContext(koda, ctx); },
        // Klici aplikacije ob tipki daljinca (SiteProfile): dokaz tipke, nato smer ali OK.
        smer(...a) { tipka(); return izvoz('_safeer_navigate_spatial')(...a); },
        ok(...a) { tipka(); return izvoz('_safeer_click_focused_card')(...a); },
        // Kot aplikacija tik pred pravim dejanjem (SiteProfile Enter, ChromiumEngineView.triggerNativeTap): dokaz tipke, nato
        // druga preverba cilja z izzivom; vrne odgovor skripta.
        preveriCilj(izziv) { tipka(); const f = izvoz('_safeer_preveri_cilj'); return typeof f === 'function' ? f(izziv) : undefined; },
        odtisZetona: (niz) => (odtis ? odtis(zeton, niz) : ''),
        // Novo nalaganje istega skripta v isti dokument (onPageStarted brez novega dokumenta): nov nonce, nov zeton.
        // Peti slepi pregled (N5): novIz = ime izvirnikov, ki ga aplikacija zdaj pricakuje (novo ob vsaki strani), izPrej = ime
        // prejsnjega dokumenta (ziva kopija v istem dokumentu).
        naloziZNonce(vir, novNonce, novZeton, prejNonce, novIz, izPrej) {
            hmacSkript = vir.indexOf('SAFEER_HMAC_ZACETEK') !== -1;
            vm.runInContext(vir.split('__SAFEER_ZETON_DOTIKA__').join(novZeton).split('__SAFEER_IZVIRNIKI__').join(n.izvirniki === false ? 'ne' : 'da')
                .split('__SAFEER_NONCE_IZ_PREJ__').join(izPrej || '')
                .split('__SAFEER_NONCE_IZVIRNIKOV__').join(novIz || nonceIzvirnikov).split('__SAFEER_NONCE__').join(novNonce)
                .split('__SAFEER_PREJ_NONCE__').join(prejNonce || ''), ctx);
        },
        vstopi() { return win.__preizkus.vstopi ? win.__preizkus.vstopi() : win._safeer_vstopi_v_plast(); },
        pocisti() { return win.__preizkus.pocisti(); },
        potrjena() { return win.__preizkus.potrjena(); },
        // Izbira, kot jo naredi daljinec: dokaz tipke in _safeer_highlight(el) (stara razlicica: staticni dokaz). Brez SAFEER_DOKAZ (stara
        // razlicica skripta) brez dokaza.
        izberi(el) {
            // Izbira je nastala s tipko pred casom (5 s): preizkusi brez tipke po izbiri ostanejo brez tipke.
            if (win.__preizkus) { tipka(); const r = win.__preizkus.izberi(el); ura.t += 5000; return r; }
            if (typeof win._safeer_tipka === 'function') { tipka(); const r = win._safeer_highlight(el); ura.t += 5000; return r; }
            return odtis ? win._safeer_highlight(el, odtis(zeton, 'izbira')) : win._safeer_highlight(el);
        },
        dokazIzbire() { return odtis ? odtis(zeton, 'izbira') : undefined; },
    };
}
module.exports = { ustvari };
