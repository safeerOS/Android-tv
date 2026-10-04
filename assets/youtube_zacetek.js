/*
 * YouTube v vgrajenem brskalniku: posnetek se zacne brez cakanja na oglas.
 *
 * Tece ob zacetku dokumenta, pred skriptami strani (WebViewCompat.addDocumentStartJavaScript), na youtube.com in
 * youtube-nocookie.com. Vmesnika za televizor (youtube.com/tv) se ne dotika - ta ima svojega pomocnika.
 *
 * Zakaj (izmerjeno 4. 10. 2026 na m.youtube.com, docs/YOUTUBE-START.md):
 *  - Ob prehodu na posnetek znotraj strani (dotik na zadetek, naslednji posnetek) odgovor strani vsebuje oglas
 *    (playerAds, adSlots). Oglasni tok blokiramo, predvajalnik pa nanj caka: zaslon je bil crn 10-11 s, nato se
 *    je posnetek zacel pri sesti sekundi.
 *  - Brez oglasnih polj predvajalnik oglasa ne nacrtuje, a streznik za tak prehod vseeno zahteva premor v dolzini
 *    oglasa (izmerjeno 5 in 16 s), preden da prvi kos posnetka.
 *  - Stran posnetka, nalozena kot nova stran, oglasa nima in prvi kos dobi takoj: od dotika do slike 1-2 s.
 * Zato: (1) oglasna polja odstranimo iz odgovorov predvajalnika; (2) prehod na DRUG posnetek je nalaganje nove
 * strani. Vse ostalo (iskanje, kanali, kratki posnetki, isti posnetek z drugim casom) ostane, kot je.
 *
 * Preizkus brez naprave: node tests/youtube_zacetek_test.js
 */
(function () {
    'use strict';
    if (window._safeer_yt_zacetek) return;
    var gost = String(location.hostname || '').toLowerCase();
    function konec(niz, rep) { return niz.length >= rep.length && niz.slice(niz.length - rep.length) === rep; }
    var jeYt = gost === 'youtube.com' || konec(gost, '.youtube.com') ||
               gost === 'youtube-nocookie.com' || konec(gost, '.youtube-nocookie.com');
    if (!jeYt) return;
    if (String(location.href || '').indexOf('youtube.com/tv') !== -1) return;
    window._safeer_yt_zacetek = true;

    // ------------------------------------------------------------------ 1) odgovor predvajalnika brez oglasov

    var POLJA = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];

    function ocisti(p) {
        if (!p || typeof p !== 'object') return;
        for (var i = 0; i < POLJA.length; i++) {
            if (POLJA[i] in p) { try { delete p[POLJA[i]]; } catch (e) {} }
        }
    }

    /** Odgovor predvajalnika (sam, v polju playerResponse ali v seznamu get_watch) brez oglasnih polj; ostalo nedotaknjeno. */
    function brezOglasov(o) {
        try {
            if (!o || typeof o !== 'object') return o;
            if (Array.isArray(o)) {
                // get_watch: [{playerResponse: {...}}, {watchNextResponse: {...}}]
                for (var i = 0; i < o.length && i < 8; i++) {
                    var e = o[i];
                    if (e && typeof e === 'object' && !Array.isArray(e)) { ocisti(e); if (e.playerResponse) ocisti(e.playerResponse); }
                }
                return o;
            }
            ocisti(o);
            if (o.playerResponse) ocisti(o.playerResponse);
        } catch (e2) {}
        return o;
    }

    // Stran odgovor prebere na vec nacinov; pokrijemo vse, ne da bi zamenjali fetch (stran ga sme brati sproti).
    try {
        JSON.parse = new Proxy(JSON.parse, {
            apply: function (cilj, ta, argumenti) { return brezOglasov(Reflect.apply(cilj, ta, argumenti)); }
        });
    } catch (e) {}
    try {
        Response.prototype.json = new Proxy(Response.prototype.json, {
            apply: function (cilj, ta, argumenti) { return Reflect.apply(cilj, ta, argumenti).then(brezOglasov); }
        });
    } catch (e) {}
    try {
        var opis = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, 'response');
        if (opis && opis.get) {
            Object.defineProperty(XMLHttpRequest.prototype, 'response', {
                configurable: true, enumerable: opis.enumerable,
                get: function () {
                    var r = opis.get.call(this);
                    return (r && typeof r === 'object' && this.responseType === 'json') ? brezOglasov(r) : r;
                }
            });
        }
    } catch (e) {}
    try {
        var zacetni;
        Object.defineProperty(window, 'ytInitialPlayerResponse', {
            configurable: true, enumerable: true,
            get: function () { return zacetni; },
            set: function (v) { zacetni = brezOglasov(v); }
        });
    } catch (e) {}

    // ------------------------------------------------------------------ 2) drug posnetek = nova stran

    // Samo glavna stran (vgrajen predvajalnik na tuji strani ne navigira) in ne YouTube Music (svoja vrsta skladb).
    var glavna = false;
    try { glavna = window.top === window.self; } catch (e) {}
    if (!glavna || gost === 'music.youtube.com') return;

    /** Naslov strani posnetka na tem mestu (URL) ali null. */
    function posnetek(naslov) {
        try {
            var u = new URL(String(naslov), location.href);
            if (u.origin !== location.origin || u.pathname !== '/watch' || !u.searchParams.get('v')) return null;
            return u;
        } catch (e) { return null; }
    }

    /** Id posnetka, ki je zdaj odprt ('' = stran ni stran posnetka). */
    function odprt() {
        try { return location.pathname === '/watch' ? (new URL(location.href).searchParams.get('v') || '') : ''; } catch (e) { return ''; }
    }

    // Varovalo: ce bi stran po nalaganju takoj spet zahtevala drug posnetek (krog), po stirih poskusih v 10 s odnehamo.
    function smem() {
        try {
            var zdaj = Date.now();
            var prej = String(sessionStorage.getItem('_safeer_yt_nova_stran') || '').split(',')
                .map(Number).filter(function (t) { return t > 0 && zdaj - t >= 0 && zdaj - t < 10000; });
            if (prej.length >= 4) return false;
            prej.push(zdaj);
            sessionStorage.setItem('_safeer_yt_nova_stran', prej.join(','));
        } catch (e) {}
        return true;
    }

    // Dotik na povezavo do drugega posnetka: preden stran sploh kaj vprasa.
    document.addEventListener('click', function (e) {
        try {
            if (e.defaultPrevented || e.button > 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;
            var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
            if (!a) return;
            // Gumb ali meni znotraj povezave (tri pike) ostane gumb.
            var gumb = e.target.closest('button, [role="button"], ytm-menu, ytm-menu-renderer');
            if (gumb && a.contains(gumb)) return;
            var u = posnetek(a.href);
            if (!u || u.searchParams.get('v') === odprt() || !smem()) return;
            e.preventDefault();
            e.stopImmediatePropagation();
            location.assign(u.href);
        } catch (err) {}
    }, true);

    // Prehod brez dotika na povezavo (naslednji posnetek, seznam predvajanja, gumbi predvajalnika).
    ['pushState', 'replaceState'].forEach(function (ime) {
        try {
            var izvirna = history[ime];
            history[ime] = function (stanje, naslov, url) {
                try {
                    var u = url != null ? posnetek(url) : null;
                    var zdaj = odprt();
                    // replaceState je prehod le s strani posnetka na drug posnetek; drugje stran z njim samo popravi naslov.
                    if (u && u.searchParams.get('v') !== zdaj && (ime === 'pushState' || zdaj) && smem()) {
                        if (ime === 'pushState') location.assign(u.href); else location.replace(u.href);
                        return;
                    }
                } catch (e) {}
                return izvirna.apply(this, arguments);
            };
        } catch (e) {}
    });
})();
