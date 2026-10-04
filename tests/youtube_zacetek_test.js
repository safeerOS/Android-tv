#!/usr/bin/env node
// Preizkus assets/youtube_zacetek.js brez naprave: skripta tece v peskovniku z nadomestki za okno, dokument,
// zgodovino in shrambo seje.   node tests/youtube_zacetek_test.js
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const KODA = fs.readFileSync(path.join(__dirname, '..', 'assets', 'youtube_zacetek.js'), 'utf8');

// Kar stran ze ima, preden pride skripta: Response, XMLHttpRequest (kot v brskalniku, a brez omrezja).
const PRIPRAVA = `
    var __izvirniParse = JSON.parse;
    function Response(besedilo) { this.besedilo = besedilo; }
    Response.prototype.json = function () { return Promise.resolve(__izvirniParse(this.besedilo)); };
    function XMLHttpRequest() { this.responseType = ''; this._odgovor = null; }
    Object.defineProperty(XMLHttpRequest.prototype, 'response', {
        configurable: true, enumerable: true, get: function () { return this._odgovor; }
    });
`;

function okolje(naslov, moznosti) {
    moznosti = moznosti || {};
    const poslusalci = [];
    const navigacije = [];
    const shramba = new Map();
    const ura = { zdaj: 1000000 };
    const u = new URL(naslov);
    const location = {
        get href() { return u.href; }, get hostname() { return u.hostname; }, get pathname() { return u.pathname; },
        get origin() { return u.origin; }, get search() { return u.search; },
        assign(n) { navigacije.push(['assign', String(n)]); }, replace(n) { navigacije.push(['replace', String(n)]); },
    };
    const zgodovina = {
        klici: [],
        pushState(stanje, ime, url) { this.klici.push(['pushState', url]); return 'izvirna'; },
        replaceState(stanje, ime, url) { this.klici.push(['replaceState', url]); return 'izvirna'; },
    };
    const okno = {
        location, history: zgodovina, URL,
        document: { addEventListener(ime, f, zajem) { poslusalci.push({ ime, f, zajem }); } },
        sessionStorage: moznosti.brezShrambe
            ? { getItem() { throw new Error('ni shrambe'); }, setItem() { throw new Error('ni shrambe'); } }
            : { getItem: (k) => (shramba.has(k) ? shramba.get(k) : null), setItem: (k, v) => { shramba.set(k, String(v)); } },
        Date: { now: () => ura.zdaj },
    };
    okno.window = okno; okno.self = okno; okno.top = moznosti.vOkvirju ? {} : okno;
    vm.createContext(okno);
    vm.runInContext(PRIPRAVA, okno);
    vm.runInContext(KODA, okno);
    const klik = () => poslusalci.find((p) => p.ime === 'click');
    return { okno, poslusalci, navigacije, zgodovina, shramba, ura, klik, izvedi: (koda) => vm.runInContext(koda, okno) };
}

/** Dotik na povezavo; vrne, ali ga je skripta prestregla. */
function dotik(o, href, dodatno) {
    dodatno = dodatno || {};
    const povezava = href == null ? null : { href, contains: (x) => !!dodatno.gumb && x === dodatno.gumb };
    const dogodek = Object.assign({
        defaultPrevented: false, button: 0, ctrlKey: false, metaKey: false, shiftKey: false, altKey: false, ustavljen: 0,
        target: { closest: (izbirnik) => (izbirnik === 'a[href]' ? povezava : (dodatno.gumb || null)) },
        preventDefault() { this.defaultPrevented = true; },
        stopImmediatePropagation() { this.ustavljen++; },
    }, dodatno.dogodek || {});
    o.klik().f(dogodek);
    return dogodek.ustavljen === 1 && dogodek.defaultPrevented;
}

const ODGOVOR = JSON.stringify([
    { responseType: 1, playerResponse: { playerAds: [{ a: 1 }], adSlots: [{}, {}], adBreakHeartbeatParams: 'Q0FB', adPlacements: [{}],
        videoDetails: { videoId: 'aaaaaaaaaaa' }, streamingData: { serverAbrStreamingUrl: 'x' } } },
    { responseType: 2, watchNextResponse: { contents: { adSlots: ['globlje ostane'] } } },
]);

// ---------------------------------------------------------------------------------------------- kje skripta ne tece
for (const naslov of ['https://example.com/watch?v=aaaaaaaaaaa', 'https://notyoutube.com/', 'https://youtube.com.example.org/',
    'https://www.youtube.com/tv#/watch?v=aaaaaaaaaaa', 'https://www.youtube.com/tv']) {
    const o = okolje(naslov);
    assert.strictEqual(o.okno._safeer_yt_zacetek, undefined, naslov);
    assert.strictEqual(o.poslusalci.length, 0, naslov);
    assert.strictEqual(o.izvedi('JSON.parse === __izvirniParse'), true, naslov);
    assert.strictEqual(o.izvedi('history.pushState(null, "", "/watch?v=bbbbbbbbbbb")'), 'izvirna', naslov);
    assert.deepStrictEqual(o.navigacije, [], naslov);
}

// ---------------------------------------------------------------------------------------------- odgovor brez oglasov
for (const naslov of ['https://m.youtube.com/results?search_query=x', 'https://www.youtube.com/watch?v=aaaaaaaaaaa', 'https://youtube.com/',
    'https://music.youtube.com/', 'https://www.youtube-nocookie.com/embed/aaaaaaaaaaa']) {
    const o = okolje(naslov, { vOkvirju: naslov.includes('/embed/') });
    assert.strictEqual(o.okno._safeer_yt_zacetek, true, naslov);
    o.okno.besedilo = ODGOVOR;
    // get_watch: seznam z odgovorom predvajalnika in odgovorom strani.
    const r = o.izvedi('JSON.parse(besedilo)');
    assert.deepStrictEqual(Object.keys(r[0].playerResponse).sort(), ['streamingData', 'videoDetails'], naslov);
    assert.strictEqual(r[0].playerResponse.videoDetails.videoId, 'aaaaaaaaaaa');
    assert.strictEqual(r[0].responseType, 1);
    // Globlje v odgovoru strani se ne dotikamo nicesar.
    assert.strictEqual(JSON.stringify(r[1]), '{"responseType":2,"watchNextResponse":{"contents":{"adSlots":["globlje ostane"]}}}');
    // Odgovor predvajalnika sam in v polju playerResponse.
    // Predmeti iz peskovnika imajo svoje prototipe: primerjamo zapis.
    const zapis = (koda) => JSON.stringify(o.izvedi(koda));
    assert.strictEqual(zapis('JSON.parse(\'{"adPlacements":[1],"playerAds":[2],"adSlots":[3],"videoDetails":{"videoId":"b"}}\')'), '{"videoDetails":{"videoId":"b"}}');
    assert.strictEqual(zapis('JSON.parse(\'{"playerResponse":{"adSlots":[3],"x":1},"y":2}\')'), '{"playerResponse":{"x":1},"y":2}');
    // Vse ostalo je, kot je bilo.
    assert.strictEqual(o.izvedi('JSON.parse("5")'), 5);
    assert.strictEqual(o.izvedi('JSON.parse("null")'), null);
    assert.strictEqual(o.izvedi('JSON.parse(\'"adSlots"\')'), 'adSlots');
    assert.strictEqual(zapis('JSON.parse("[1,[2,3],\\"a\\",null]")'), '[1,[2,3],"a",null]');
    assert.strictEqual(zapis('JSON.parse(\'{"a":{"b":{"adSlots":[1]}}}\')'), '{"a":{"b":{"adSlots":[1]}}}');
    assert.strictEqual(zapis('JSON.parse(\'{"a":2,"b":3}\', function (k, v) { return typeof v === "number" ? v * 10 : v; })'), '{"a":20,"b":30}');
    assert.throws(() => o.izvedi('JSON.parse("{pokvarjeno")'), /JSON|Unexpected|Expected/);
    // Stran ne sme videti, da je razclenjevalnik zamenjan.
    assert.ok(o.izvedi('Function.prototype.toString.call(JSON.parse)').includes('[native code]'));
    assert.strictEqual(o.izvedi('JSON.parse.name'), 'parse');
    // XMLHttpRequest: samo odgovor vrste json.
    assert.deepStrictEqual(o.izvedi('(function () { var x = new XMLHttpRequest(); x.responseType = "json"; x._odgovor = __izvirniParse(besedilo); return x.response; })()')[0].playerResponse.adSlots, undefined);
    assert.strictEqual(o.izvedi('(function () { var x = new XMLHttpRequest(); x.responseType = ""; x._odgovor = besedilo; return x.response; })()'), ODGOVOR);
    assert.strictEqual(o.izvedi('(function () { var x = new XMLHttpRequest(); x.responseType = "json"; x._odgovor = null; return x.response; })()'), null);
    // Zacetni odgovor strani, nalozene naravnost.
    o.izvedi('window.ytInitialPlayerResponse = {playerAds: [1], adSlots: [2], videoDetails: {videoId: "c"}}');
    assert.strictEqual(o.izvedi('JSON.stringify(window.ytInitialPlayerResponse)'), '{"videoDetails":{"videoId":"c"}}');
    o.izvedi('window.ytInitialPlayerResponse = null');
    assert.strictEqual(o.izvedi('window.ytInitialPlayerResponse'), null);
}

// Response.json(): fetch(...).then(r => r.json()).
(async () => {
    const o = okolje('https://m.youtube.com/');
    o.okno.besedilo = ODGOVOR;
    const r = await o.izvedi('new Response(besedilo).json()');
    assert.strictEqual(r[0].playerResponse.playerAds, undefined);
    assert.strictEqual(r[0].playerResponse.adSlots, undefined);
    assert.strictEqual(r[0].playerResponse.videoDetails.videoId, 'aaaaaaaaaaa');
    assert.strictEqual(await o.izvedi('new Response("7").json()'), 7);

    // ------------------------------------------------------------------------------------------ drug posnetek = nova stran
    const A = 'https://m.youtube.com/watch?v=aaaaaaaaaaa';
    const B = 'https://m.youtube.com/watch?v=bbbbbbbbbbb';
    {
        // Okvir na tuji strani in YouTube Music: odgovor se cisti, navigacije se ne dotikamo.
        for (const [naslov, moznosti] of [['https://www.youtube.com/embed/aaaaaaaaaaa', { vOkvirju: true }], ['https://music.youtube.com/watch?v=aaaaaaaaaaa', {}]]) {
            const p = okolje(naslov, moznosti);
            assert.strictEqual(p.poslusalci.length, 0, naslov);
            assert.strictEqual(p.izvedi('history.pushState(null, "", "/watch?v=bbbbbbbbbbb")'), 'izvirna', naslov);
            assert.deepStrictEqual(p.navigacije, [], naslov);
        }
    }
    {
        // Zadetki iskanja: dotik na posnetek je nalaganje nove strani, se preden stran kaj vprasa.
        const p = okolje('https://m.youtube.com/results?search_query=x');
        assert.strictEqual(p.klik().zajem, true);
        assert.strictEqual(dotik(p, B + '&pp=abc'), true);
        assert.deepStrictEqual(p.navigacije, [['assign', B + '&pp=abc']]);
        // Relativna povezava in seznam predvajanja.
        assert.strictEqual(dotik(p, '/watch?v=ccccccccccc&list=RDccccccccccc&start_radio=1'), true);
        assert.deepStrictEqual(p.navigacije[1], ['assign', 'https://m.youtube.com/watch?v=ccccccccccc&list=RDccccccccccc&start_radio=1']);
        // Kar ni stran posnetka na tem mestu, ostane strani.
        for (const href of ['/shorts/abc', '/results?search_query=y', '/@kanal', 'https://example.com/watch?v=bbbbbbbbbbb',
            'https://www.youtube.com/watch?v=bbbbbbbbbbb', '/watch', '/watch?list=PL1', 'javascript:void(0)', '']) {
            assert.strictEqual(dotik(p, href), false, href);
        }
        assert.strictEqual(dotik(p, null), false);
        // Dotik s tipko, srednji gumb, ze obdelan dogodek, gumb znotraj povezave (meni).
        assert.strictEqual(dotik(p, B, { dogodek: { ctrlKey: true } }), false);
        assert.strictEqual(dotik(p, B, { dogodek: { shiftKey: true } }), false);
        assert.strictEqual(dotik(p, B, { dogodek: { button: 1 } }), false);
        assert.strictEqual(dotik(p, B, { dogodek: { defaultPrevented: true } }), false);
        assert.strictEqual(dotik(p, B, { gumb: { ime: 'meni' } }), false);
        assert.strictEqual(p.navigacije.length, 2);
    }
    {
        // Stran posnetka: isti posnetek (drug cas, seznam) ostane strani, drug posnetek je nova stran.
        const p = okolje(A + '&t=10s');
        assert.strictEqual(dotik(p, A + '&t=120s'), false);
        assert.strictEqual(dotik(p, '/watch?v=aaaaaaaaaaa&list=PL1'), false);
        assert.strictEqual(dotik(p, B), true);
        assert.deepStrictEqual(p.navigacije, [['assign', B]]);
    }
    {
        // Prehod brez dotika na povezavo: naslednji posnetek, gumbi predvajalnika.
        const p = okolje(A);
        assert.strictEqual(p.izvedi('history.pushState({}, "", "/watch?v=bbbbbbbbbbb&list=RDx")'), undefined);
        assert.deepStrictEqual(p.navigacije, [['assign', B + '&list=RDx']]);
        assert.deepStrictEqual(p.zgodovina.klici, []);
        assert.strictEqual(p.izvedi('history.replaceState({}, "", "' + B + '")'), undefined);
        assert.deepStrictEqual(p.navigacije[1], ['replace', B]);
        // Isti posnetek, druga stran ali brez naslova: izvirna funkcija.
        assert.strictEqual(p.izvedi('history.pushState({}, "", "/watch?v=aaaaaaaaaaa&t=5s")'), 'izvirna');
        assert.strictEqual(p.izvedi('history.pushState({}, "", "/results?search_query=z")'), 'izvirna');
        assert.strictEqual(p.izvedi('history.pushState({}, "")'), 'izvirna');
        assert.strictEqual(p.izvedi('history.replaceState({a: 1}, "", null)'), 'izvirna');
        assert.strictEqual(p.izvedi('history.replaceState({}, "", "/watch?v=aaaaaaaaaaa&pp=1")'), 'izvirna');
        assert.strictEqual(p.izvedi('history.pushState({}, "", "https://example.com/watch?v=bbbbbbbbbbb")'), 'izvirna');
        assert.strictEqual(p.zgodovina.klici.length, 6);
        assert.strictEqual(p.navigacije.length, 2);
    }
    {
        // S strani, ki ni stran posnetka: pushState na posnetek je nova stran, replaceState samo popravi naslov.
        const p = okolje('https://m.youtube.com/');
        assert.strictEqual(p.izvedi('history.replaceState({}, "", "/watch?v=bbbbbbbbbbb")'), 'izvirna');
        assert.strictEqual(p.izvedi('history.pushState({}, "", "/watch?v=bbbbbbbbbbb")'), undefined);
        assert.deepStrictEqual(p.navigacije, [['assign', B]]);
    }
    {
        // Varovalo pred krogom: najvec stiri nalaganja v 10 s, nato stran dela po svoje; cez 10 s spet.
        const p = okolje(A);
        for (let i = 0; i < 4; i++) assert.strictEqual(p.izvedi('history.pushState({}, "", "/watch?v=bbbbbbbbbb' + i + '")'), undefined, 'poskus ' + i);
        assert.strictEqual(p.izvedi('history.pushState({}, "", "/watch?v=bbbbbbbbbb9")'), 'izvirna');
        assert.strictEqual(dotik(p, B), false);
        assert.strictEqual(p.navigacije.length, 4);
        p.ura.zdaj += 9999;
        assert.strictEqual(dotik(p, B), false);
        p.ura.zdaj += 2;
        assert.strictEqual(dotik(p, B), true);
        assert.strictEqual(p.navigacije.length, 5);
        // Pokvarjen zapis v shrambi ne ustavi nicesar.
        p.shramba.set('_safeer_yt_nova_stran', 'abc,,-5,NaN');
        p.ura.zdaj += 20000;
        assert.strictEqual(dotik(p, B), true);
    }
    {
        // Brez shrambe seje (zasebni nacin): prehod vseeno dela.
        const p = okolje(A, { brezShrambe: true });
        assert.strictEqual(dotik(p, B), true);
        assert.strictEqual(p.izvedi('history.pushState({}, "", "/watch?v=ccccccccccc")'), undefined);
        assert.strictEqual(p.navigacije.length, 2);
    }
    {
        // Skripta, pognana dvakrat (ob zacetku dokumenta in se enkrat ob nalaganju), se namesti enkrat.
        const p = okolje(A);
        vm.runInContext(KODA, p.okno);
        assert.strictEqual(p.poslusalci.filter((x) => x.ime === 'click').length, 1);
        p.izvedi('history.pushState({}, "", "/watch?v=bbbbbbbbbbb")');
        assert.strictEqual(p.navigacije.length, 1);
    }
    console.log('youtube_zacetek_test: OK');
})().catch((napaka) => { console.error(napaka); process.exit(1); });
