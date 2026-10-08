/*
 * Safeer: splosna zascita pred zaznavanjem blokatorja oglasov. Tece ob zacetku dokumenta, pred skriptami strani, na vseh
 * spletnih straneh enako (brez receptov za posamezne strani). Stran, ki preverja »ali je blokator«, dobi odgovor, kot da
 * oglasi niso blokirani:
 *   1. znane spremenljivke in knjiznice zaznavanja (canRunAds, BlockAdBlock, FuckAdBlock ...),
 *   2. vabe: element z razredom ali imenom oglasa je za meritev (velikost, slog) videti navaden in viden,
 *   3. omrezne preverbe: GET/XHR na znan oglasni naslov, ki ga blokiramo, uspe (prazen odgovor),
 *   4. skripta z oglasnega naslova, ki je blokirana, sprozi load namesto error.
 * Nic od tega ne prikaze oglasa: blokiranje ostane; stran dobi le prazen, a uspesen odgovor.
 */
(function () {
    'use strict';
    try { var gost = String(location.hostname || '').toLowerCase();
          if (gost.indexOf('youtube') !== -1) return; } catch (_) {}
    if (window.__safeerProtiAdblock) return;
    try { Object.defineProperty(window, '__safeerProtiAdblock', { value: true }); } catch (_) { return; }

    var BAIT = /(^|[\s_\-.])(ad|ads|adv|advert|adsbox|adbox|adsbygoogle|ad-banner|adbanner|banner[-_]?ad|textads?|text-ad|sponsor|sponsored|pub[-_]?\d+x\d+|ad[-_]?\d+x\d+|ad[-_]?placement|adslot|ad[-_]slot|doubleclick|google[-_]?ads?)([\s_\-.]|$)/i;
    var OGLASNI_NASLOV = /(^|\.)(googlesyndication\.com|doubleclick\.net|googleadservices\.com|adservice\.google\.[a-z.]+|adnxs\.com|taboola\.com|outbrain\.com|amazon-adsystem\.com|moatads\.com|pubmatic\.com|rubiconproject\.com|criteo\.(com|net)|adsafeprotected\.com|scorecardresearch\.com|imasdk\.googleapis\.com|google-analytics\.com|googletagservices\.com|googletagmanager\.com|2mdn\.net|adform\.net|smartadserver\.com|openx\.net|casalemedia\.com|indexww\.com|advertising\.com|quantserve\.com|chartbeat\.com)$|\/(ads?|adframe|adsbygoogle|pagead|advert|banner)[^\/]*\.js(\?|$)/i;

    function jeVaba(el) {
        try {
            if (!el || el.nodeType !== 1) return false;
            var cls = typeof el.className === 'string' ? el.className : (el.getAttribute && el.getAttribute('class')) || '';
            return BAIT.test(cls) || BAIT.test(el.id || '');
        } catch (_) { return false; }
    }

    // 1. Spremenljivke in knjiznice.
    try {
        window.canRunAds = true;
        window.isAdBlockActive = false;
        window.adblock = false;
        window.adblockDetected = false;
        window._adblocker = false;
        if (!window.adsbygoogle) window.adsbygoogle = [];
        try { window.adsbygoogle.loaded = true; } catch (_) {}
        var Lazni = function (opts) {
            if (opts && typeof opts.onNotDetected === 'function') setTimeout(opts.onNotDetected, 10);
        };
        Lazni.prototype.check = function () { return false; };
        Lazni.prototype.clearEvent = function () {};
        Lazni.prototype.setOption = function () { return this; };
        Lazni.prototype.on = function (zaznan, fn) { if (!zaznan && typeof fn === 'function') setTimeout(fn, 10); return this; };
        Lazni.prototype.onDetected = function () { return this; };
        Lazni.prototype.onNotDetected = function (fn) { if (typeof fn === 'function') setTimeout(fn, 10); return this; };
        window.BlockAdBlock = Lazni; window.blockAdBlock = new Lazni();
        window.FuckAdBlock = Lazni; window.fuckAdBlock = window.blockAdBlock;
    } catch (_) {}

    // 2. Vabe: velikost in slog.
    try {
        var EP = window.Element && window.Element.prototype;
        var HP = window.HTMLElement && window.HTMLElement.prototype;
        function ovij(proto, ime, vrni) {
            if (!proto) return;
            var opis = Object.getOwnPropertyDescriptor(proto, ime);
            if (!opis || typeof opis.get !== 'function') return;
            var izvirni = opis.get;
            Object.defineProperty(proto, ime, {
                configurable: true, enumerable: opis.enumerable,
                get: function () {
                    var v = izvirni.call(this);
                    return (v === 0 && jeVaba(this)) ? vrni : v;
                }
            });
        }
        ovij(HP, 'offsetHeight', 1); ovij(HP, 'offsetWidth', 1);
        ovij(EP, 'clientHeight', 1); ovij(EP, 'clientWidth', 1);
        ovij(HP, 'offsetTop', 1); ovij(HP, 'offsetLeft', 1);

        if (EP && typeof EP.getBoundingClientRect === 'function') {
            var izvirniRect = EP.getBoundingClientRect;
            EP.getBoundingClientRect = function () {
                var r = izvirniRect.apply(this, arguments);
                if (r && r.height === 0 && r.width === 0 && jeVaba(this)) {
                    return { x: r.x, y: r.y, top: r.top, left: r.left, right: r.left + 1, bottom: r.top + 1, width: 1, height: 1,
                             toJSON: function () { return this; } };
                }
                return r;
            };
        }

        var izvirniSlog = window.getComputedStyle;
        if (typeof izvirniSlog === 'function' && typeof Proxy === 'function') {
            window.getComputedStyle = function (el) {
                var s = izvirniSlog.apply(window, arguments);
                if (!jeVaba(el)) return s;
                var popravi = { display: 'block', visibility: 'visible', opacity: '1', height: '1px', width: '1px' };
                return new Proxy(s, {
                    get: function (t, k) {
                        if (typeof k === 'string' && Object.prototype.hasOwnProperty.call(popravi, k)) {
                            var v = t[k];
                            return (v === 'none' || v === 'hidden' || v === '0' || v === '0px' || v === '' ) ? popravi[k] : v;
                        }
                        if (k === 'getPropertyValue') {
                            return function (ime) {
                                var v = t.getPropertyValue(ime);
                                if (Object.prototype.hasOwnProperty.call(popravi, ime) &&
                                    (v === 'none' || v === 'hidden' || v === '0' || v === '0px' || v === '')) return popravi[ime];
                                return v;
                            };
                        }
                        var x = t[k];
                        return typeof x === 'function' ? x.bind(t) : x;
                    }
                });
            };
        }
    } catch (_) {}

    // 2b. offsetParent vabe: element z display:none ima offsetParent null; stran to uporablja kot "skrit = blokiran".
    try {
        var HP2 = window.HTMLElement && window.HTMLElement.prototype;
        var opisOP = HP2 && Object.getOwnPropertyDescriptor(HP2, 'offsetParent');
        if (opisOP && typeof opisOP.get === 'function') {
            var izvirniOP = opisOP.get;
            Object.defineProperty(HP2, 'offsetParent', {
                configurable: true, enumerable: opisOP.enumerable,
                get: function () {
                    var v = izvirniOP.call(this);
                    return (v === null && jeVaba(this)) ? (document.body || document.documentElement) : v;
                }
            });
        }
    } catch (_) {}

    // 2c. Google Publisher Tag: stran preverja googletag.apiReady (ali se je knjiznica nalozila). Prazen, a "delujoc" objekt.
    try {
        if (!window.googletag || !window.googletag.apiReady) {
            var veriga = function () {
                var cel = function () { return proxy; };
                var proxy = new Proxy(cel, {
                    get: function (t, k) {
                        if (k === Symbol.toPrimitive) return function () { return ''; };
                        if (k === 'then') return undefined;
                        if (k === 'getSlots' || k === 'getTargetingKeys' || k === 'getSlotElementId') return function () { return []; };
                        return proxy;
                    },
                    apply: function () { return proxy; }
                });
                return proxy;
            };
            // Stran (npr. liveone.com) lahko googletag prepise z novim objektom ({cmd: []}); pripravljenost mora ostati.
            var cakajoci = [];
            var okrepi = function (gt) {
                if (!gt || (typeof gt !== 'object' && typeof gt !== 'function')) return gt;
                try {
                    var cmd = Array.isArray(gt.cmd) ? gt.cmd : [];
                    if (!cmd.__safeer) {
                        cmd.slice().forEach(function (f) { cakajoci.push(f); });
                        cmd.push = function () {
                            for (var i = 0; i < arguments.length; i++) { try { if (typeof arguments[i] === 'function') arguments[i](); } catch (_) {} }
                            return 0;
                        };
                        try { Object.defineProperty(cmd, '__safeer', { value: true }); } catch (_) {}
                        gt.cmd = cmd;
                    }
                    if (gt.apiReady === undefined) gt.apiReady = true;
                    if (gt.pubadsReady === undefined) gt.pubadsReady = true;
                    ['pubads', 'companionAds', 'content', 'defineSlot', 'defineOutOfPageSlot', 'enableServices', 'display',
                     'destroySlots', 'setConfig', 'sizeMapping', 'getVersion'].forEach(function (ime) {
                        if (typeof gt[ime] !== 'function') gt[ime] = function () { return veriga(); };
                    });
                } catch (_) {}
                return gt;
            };
            var nas = okrepi(window.googletag || {});
            try {
                Object.defineProperty(window, 'googletag', {
                    configurable: true, enumerable: true,
                    get: function () { return nas; },
                    set: function (v) { nas = okrepi(v); }
                });
            } catch (_) { window.googletag = nas; }
            setTimeout(function () { cakajoci.forEach(function (f) { try { if (typeof f === 'function') f(); } catch (_) {} }); }, 0);
        }
    } catch (_) {}

    // 3. Omrezne preverbe.
    function jeOglasniNaslov(url) {
        try {
            var u = new URL(String(url), location.href);
            return OGLASNI_NASLOV.test(u.hostname) || OGLASNI_NASLOV.test(u.pathname);
        } catch (_) { return false; }
    }
    try {
        var izvirniFetch = window.fetch;
        if (typeof izvirniFetch === 'function') {
            window.fetch = function (vhod, moznosti) {
                var url = vhod && vhod.url ? vhod.url : vhod;
                var metoda = ((moznosti && moznosti.method) || (vhod && vhod.method) || 'GET').toUpperCase();
                var p = izvirniFetch.apply(this, arguments);
                if (metoda !== 'GET' && metoda !== 'HEAD') return p;
                if (!jeOglasniNaslov(url)) return p;
                return p.then(function (r) { return r; }, function () {
                    return new Response('', { status: 200, statusText: 'OK' });
                });
            };
        }
    } catch (_) {}
    try {
        var XP = window.XMLHttpRequest && window.XMLHttpRequest.prototype;
        if (XP && typeof XP.open === 'function' && typeof XP.send === 'function') {
            var izvirniOpen = XP.open, izvirniSend = XP.send;
            XP.open = function (metoda, url) {
                try { this.__safeerOglas = jeOglasniNaslov(url) && /^(GET|HEAD)$/i.test(metoda || 'GET'); } catch (_) {}
                return izvirniOpen.apply(this, arguments);
            };
            XP.send = function () {
                var xhr = this;
                if (xhr.__safeerOglas) {
                    xhr.addEventListener('error', function () {
                        try {
                            Object.defineProperty(xhr, 'status', { configurable: true, get: function () { return 200; } });
                            Object.defineProperty(xhr, 'readyState', { configurable: true, get: function () { return 4; } });
                            Object.defineProperty(xhr, 'responseText', { configurable: true, get: function () { return ''; } });
                            if (typeof xhr.onload === 'function') xhr.onload(new Event('load'));
                            xhr.dispatchEvent(new Event('load'));
                        } catch (_) {}
                    }, true);
                }
                return izvirniSend.apply(this, arguments);
            };
        }
    } catch (_) {}

    // 4. Blokirana oglasna skripta: load namesto error.
    try {
        window.addEventListener('error', function (e) {
            var t = e && e.target;
            if (!t || t === window || t.tagName !== 'SCRIPT' || !t.src || !jeOglasniNaslov(t.src)) return;
            e.stopImmediatePropagation();
            e.preventDefault();
            try { t.dispatchEvent(new Event('load')); } catch (_) {}
            if (typeof t.onload === 'function') { try { t.onload(new Event('load')); } catch (_) {} }
        }, true);
    } catch (_) {}
    // 5. Sledilne/oglasne slike (new Image().src = ...): onerror bi pomenil "blokirano". Prava zahteva se ne poslje,
    // stran dobi uspesno naloženo sličico 1x1, src pa ostane, kot ga je nastavila.
    try {
        var IP = window.HTMLImageElement && window.HTMLImageElement.prototype;
        var opisSrc = IP && Object.getOwnPropertyDescriptor(IP, 'src');
        var GIF = 'data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==';
        if (opisSrc && typeof opisSrc.set === 'function' && typeof opisSrc.get === 'function') {
            Object.defineProperty(IP, 'src', {
                configurable: true, enumerable: opisSrc.enumerable,
                get: function () {
                    return this.__safeerSrc !== undefined ? this.__safeerSrc : opisSrc.get.call(this);
                },
                set: function (v) {
                    if (jeOglasniNaslov(v)) {
                        try { Object.defineProperty(this, '__safeerSrc', { configurable: true, writable: true, value: new URL(String(v), location.href).href }); } catch (_) {}
                        opisSrc.set.call(this, GIF);
                    } else {
                        try { this.__safeerSrc = undefined; } catch (_) {}
                        opisSrc.set.call(this, v);
                    }
                }
            });
        }
    } catch (_) {}
})();
