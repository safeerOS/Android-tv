#!/usr/bin/env node
// Posrednik predvajalnika v okvirjih (os/SpletniIgralec.kt POSREDNIK_JS), neodvisni pregled 5. 11. 2026:
//   node tests/igralec_posrednik_test.js [pot do SpletniIgralec.kt]
// SAFEER_VSE_NAPAKE=1: izpise vse padle preverbe (dokaz, da brez popravka pade).
// Groznji model: stran ali tuj okvir (oglas) z lastnim JS v istem svetu kot posrednik.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const KT = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'src', 'main', 'kotlin', 'si', 'safeer', 'tv', 'os', 'SpletniIgralec.kt'), 'utf8');
const m = KT.match(/internal const val POSREDNIK_JS = """([\s\S]*?)"""/);
assert.ok(m, 'POSREDNIK_JS ni v SpletniIgralec.kt');
const NONCE = '0f1e2d3c4b5a6978', KLJUC = 'a1b2c3d4'.repeat(6);
// Kot posrednikJs: izvori vgradnih predvajalnikov iz IZVORI_PREDVAJALNIKA (tretji neodvisni pregled 5. 11. 2026, N6).
const mi = KT.match(/internal val IZVORI_PREDVAJALNIKA = listOf\(([\s\S]*?)\)/);
const IZVORI = mi ? (mi[1].match(/"[^"]+"/g) || []).map((x) => x.slice(1, -1)) : [];
const JS = m[1].split('__SAFEER_NONCE_AGENTA__').join(NONCE).split('__SAFEER_KLJUC_AGENTA__').join(KLJUC)
    .split('__SAFEER_IZVORI_AGENTA__').join(IZVORI.map((o) => "O['" + o + "']=1;").join(''));
const VSE = process.env.SAFEER_VSE_NAPAKE === '1';
let stevilo = 0, padle = 0;
function preveri(opis, pric, dob) {
    try { assert.deepStrictEqual(dob, pric, opis); stevilo++; console.log('  OK   ' + opis); }
    catch (_) { padle++; console.log('  PADLO ' + opis + ' (pricakovano ' + JSON.stringify(pric) + ', dobljeno ' + JSON.stringify(dob) + ')'); if (!VSE) process.exit(1); }
}

// Okno (vrhnje ali okvir) z enim medijem; postMessage dostavi sporocilo s pravim posiljateljem (e.source).
function okno(vrh, izvor) {
    const poslusalci = [], intervali = [], dnevnik = [];
    const medij = { paused: false, currentTime: 12, duration: 100, readyState: 4, ended: false, clientWidth: 640, clientHeight: 360,
        pause() { dnevnik.push('pause'); this.paused = true; }, play() { dnevnik.push('play'); this.paused = false; }, setAttribute() {},
        getBoundingClientRect: () => ({ width: 640, height: 360 }) };
    const w = {
        frames: [], dnevnik, medij, intervali,
        addEventListener(t, f) { if (t === 'message') poslusalci.push(f); },
        setInterval(f) { intervali.push(f); return intervali.length; },
        HTMLMediaElement: function () {},
        location: { origin: izvor || 'https://primer.test' },
        document: { querySelectorAll: (s) => (/video|audio/.test(s) ? [medij] : []), querySelector: () => null, getElementById: () => null,
            createElement: () => ({}), head: { appendChild() {} }, documentElement: {} },
        poslusalci,
    };
    w.postMessage = function (data, _o, posiljatelj) {
        const src = posiljatelj ? posiljatelj.__g : w.__g;
        const izv = posiljatelj ? posiljatelj.location.origin : w.location.origin;   // brskalnik: izvor posiljatelja
        const kopija = JSON.parse(JSON.stringify(data));        // strukturirana kopija
        poslusalci.slice().forEach((f) => { try { f({ data: kopija, source: src, origin: izv }); } catch (_) {} });
    };
    vm.createContext(w);
    // Kot v brskalniku: window, top, parent in e.source so isti objekt (globalni objekt sveta).
    w.__g = vm.runInContext('globalThis', w);
    w.window = w.__g; w.self = w.__g;
    w.top = vrh ? vrh.__g : w.__g; w.parent = w.top;
    return w;
}
function posljiOd(cilj, od, data) { cilj.postMessage(data, '*', od); }

// 1. Stran vnaprej nastavi staro zastavico -> posrednik se je prej ustavil.
{
    const v = okno();
    v.__safeerAgent = 1;
    vm.runInContext(JS, v);
    preveri('1: vnaprej nastavljena window.__safeerAgent ne izklopi posrednika', true, v.poslusalci.length > 0);
    const v2 = okno();
    vm.runInContext(JS, v2);
    const n = v2.poslusalci.length;
    vm.runInContext(JS, v2);
    preveri('1: drugi vbrizg (evaluateJavascript za zacetkom dokumenta) se ne namesti znova', n, v2.poslusalci.length);
    preveri('1: zastavica ni prepisljiva', 1, (vm.runInContext("try { window['__safeerAgent_" + NONCE + "'] = 0; } catch (e) {} window['__safeerAgent_" + NONCE + "']", v2)));
    preveri("1: 'use strict' v posredniku", true, /^\(function\(\)\{'use strict';/.test(m[1]));
}

// 2. Ukaz od tujega okvirja (oglas) v vrhnji strani: pavza, ki je ni dal uporabnik.
{
    const v = okno();
    vm.runInContext(JS, v);
    const oglas = okno(v);
    posljiOd(v, oglas, { safeer: 'ukaz', c: 'pause' });
    preveri('2: ukaz tujega okvirja vrhnja stran zavrne', [], v.dnevnik);
    posljiOd(v, v, { safeer: 'ukaz', c: 'pause' });           // Kotlin: window.postMessage v vrhnji strani
    preveri('2: ukaz aplikacije (isto okno) velja', ['pause'], v.dnevnik);
    // Okvir sprejme ukaz od starsa (posrednik visje), ne od drugega okvirja.
    const okvir = okno(v);
    vm.runInContext(JS, okvir);
    posljiOd(okvir, oglas, { safeer: 'ukaz', c: 'pause' });
    preveri('2: okvir zavrne ukaz sosednjega okvirja', [], okvir.dnevnik);
    posljiOd(okvir, v, { safeer: 'ukaz', c: 'pause' });
    preveri('2: okvir sprejme ukaz starsa', ['pause'], okvir.dnevnik);
}

// 3. Lazno stanje ali napaka iz tujega okvirja -> Kotlin uposteva igra/konec/ponovno nalaganje.
{
    const v = okno();
    vm.runInContext(JS, v);
    const oglas = okno(v);
    posljiOd(v, oglas, { safeer: 'stanje', p: true, t: 99, d: 100, r: 4, e: true });
    posljiOd(v, oglas, { safeer: 'napaka' });
    preveri('3: lazno stanje in napaka tujega okvirja brez kljuca: zavrnjeno', [undefined, undefined],
        [vm.runInContext('window.__safeerOkvir', v), vm.runInContext('window.__safeerNapaka', v)]);
    posljiOd(v, oglas, { safeer: 'stanje', k: 'b2'.repeat(24), p: true, t: 99, d: 100, r: 4, e: true });
    preveri('3: z ugibanim kljucem tudi ne', undefined, vm.runInContext('window.__safeerOkvir', v));
    // Pravi posrednik v okvirju (isti kljuc): stanje sprejeto.
    const okvir = okno(v);
    vm.runInContext(JS, okvir);
    okvir.postMessage = v.postMessage;
    vm.runInContext('window.top = { postMessage: function (d, o) { window.__vrh(d, o); } };', okvir);
    okvir.__vrh = (d) => posljiOd(v, okvir, d);
    okvir.intervali.forEach((f) => f());
    const st = vm.runInContext('window.__safeerOkvir', v);
    preveri('3: stanje nasega posrednika v okvirju velja (kot prej)', [true, 12, 100], st ? [st.p, st.t, st.d] : null);
    preveri('3: kljuc ni v stanju, ki ga bere aplikacija', undefined, st ? st.k : 'ni stanja');
}

// 4. Tretji neodvisni pregled 5. 11. 2026 (N6): oglas v okvirju ima nas posrednik in kljuc; stran oglasa prepise getterje
// medija, nas posrednik poslje lazno stanje ali napako (preskok skladbe). Vrhnja stran sprejme stanje samo z izvora same sebe
// ali vgradnega predvajalnika.
function okvirZ(v, izvor, ponaredi) {
    const o = okno(v, izvor);
    vm.runInContext(JS, o);
    if (ponaredi) { o.medij.ended = true; o.medij.currentTime = 99; o.medij.duration = 100; }
    vm.runInContext('window.top = { postMessage: function (d, o) { window.__vrh(d, o); } };', o);
    o.__vrh = (d) => posljiOd(v, o, d);
    o.document.querySelector = (s) => (ponaredi && /ytp-error/.test(s) ? { offsetWidth: 10 } : null);
    o.intervali.forEach((f) => f());
    return o;
}
{
    const v = okno(null, 'https://safeer.si');
    vm.runInContext(JS, v);
    okvirZ(v, 'https://oglas.napadalec.test', true);
    preveri('4: oglas z nasim posrednikom (lazno stanje, konec, napaka): vrhnja stran zavrne', [undefined, undefined],
        [vm.runInContext('window.__safeerOkvir', v), vm.runInContext('window.__safeerNapaka', v)]);
    preveri('4: seznam izvorov predvajalnika je v posredniku (YouTube embed)', true, IZVORI.indexOf('https://www.youtube.com') !== -1 &&
        JS.indexOf("O['https://www.youtube.com']=1;") !== -1);
    okvirZ(v, 'https://www.youtube.com', false);
    const st = vm.runInContext('window.__safeerOkvir', v);
    preveri('4: stanje vgradnega predvajalnika YouTube velja', [true, 12, false], st ? [st.p, st.t, st.e] : null);
    const v2 = okno(null, 'https://primer.test');
    vm.runInContext(JS, v2);
    okvirZ(v2, 'https://primer.test', false);
    const st2 = vm.runInContext('window.__safeerOkvir', v2);
    preveri('4: stanje okvirja istega izvora kot vrhnja stran velja', [true, 12], st2 ? [st2.p, st2.t] : null);
}

// 5. Cetrti slepi pregled 5. 11. 2026 (N-E): spletni vir s predvajalnikom v okvirju drugega izvora (CDN, ni na seznamu) igra.
// Prej (od N6) vrhnja stran ni dobila nicesar - po ROK_ZASTOJA_MS zastoj: skladba NI_TOKA in preskocena, ceprav je igrala.
// Zdaj: samo znak »igra« (cas), ki ga Kotlin uporabi le za preklic zastoja; stanje (polozaj, konec) ostane nepreverjeno.
const ms = KT.match(/private const val STANJE_JS = """([\s\S]*?)"""/);
function stanjeVrha(v) {
    vm.runInContext('if (!window.navigator) window.navigator = {}; window.__stanjeJs = ' + JSON.stringify(ms ? ms[1] : '0'), v);
    return JSON.parse(JSON.stringify(vm.runInContext('eval(window.__stanjeJs)', v)));
}
{
    const v = okno(null, 'https://glasba.primer.test');
    v.document.querySelectorAll = () => [];                     // vrhnja stran nima medija (predvajalnik je v okvirju)
    vm.runInContext(JS, v);
    okvirZ(v, 'https://cdn.predvajalnik.test', false);           // CDN igra (t=12), polozaja vrhnja stran ne preveri
    preveri('5 (N-E): CDN okvir: stanje ni preverjeno (ni polozaja, konca)', undefined, vm.runInContext('window.__safeerOkvir', v));
    const st = stanjeVrha(v);
    // Odlocitev vodje 5. 11. 2026 (N4): poleg znaka se stevila t, trajanje d in cas prejema (dolg posnetek, ki tece, se ne preskoci).
    preveri('5 (N-E/N4): STANJE_JS vrne znak u ter samo stevila t, d in cas prejema', ['u', 'ut', 'ud', 'uc', 1, 12, 100, 'number'],
        [...Object.keys(st), st.u, st.ut, st.ud, typeof st.uc]);
    preveri('5 (N-E): Kotlin znak uporabi SAMO za zastoj', true,
        /if \(o\.optInt\("u"\) == 1\) nepreverjeno\.znak\(o\.optDouble\("ut", -1\.0\), o\.optDouble\("ud", 0\.0\), o\.optDouble\("uc", -1\.0\),/.test(KT) &&
        /YoutubeVrstaPravila\.zastoj\(zdaj - nalozenoOd, polozaj, pripravljeno, zelja, nepreverjeno\.igra\(zdaj\),\s*nepreverjeno\.dolgIgra\(zdaj\)\)/.test(KT) &&
        (KT.match(/nepreverjeno\./g) || []).length === 3);
    // Okvir poslje necislo trajanje ali niz: v stanje gre samo stevilo.
    const v4 = okno(null, 'https://glasba.primer.test');
    v4.document.querySelectorAll = () => [];
    vm.runInContext(JS, v4);
    const o4 = okvirZ(v4, 'https://cdn.predvajalnik.test', false);
    posljiOd(v4, o4, { safeer: 'stanje', k: KLJUC, p: true, t: 5, d: 'x<script>', r: 4, e: false });
    const st4 = stanjeVrha(v4);
    preveri('5 (N4): trajanje, ki ni stevilo, je 0', [5, 0], [st4.ut, st4.ud]);
    // CDN okvir, ki ne igra (pavza ali konec), znaka ne da - pravi zastoj ostane.
    const v2 = okno(null, 'https://glasba.primer.test');
    v2.document.querySelectorAll = () => [];
    vm.runInContext(JS, v2);
    const o2 = okno(v2, 'https://cdn.predvajalnik.test');
    vm.runInContext(JS, o2);
    o2.medij.paused = true; o2.medij.currentTime = 0;
    vm.runInContext('window.top = { postMessage: function (d, o) { window.__vrh(d, o); } };', o2);
    o2.__vrh = (d) => posljiOd(v2, o2, d);
    o2.intervali.forEach((f) => f());
    preveri('5 (N-E): CDN okvir, ki ne igra: brez znaka (pravi zastoj se preskoci)', {}, stanjeVrha(v2));
    // Oglas brez kljuca znaka ne doseze.
    const v3 = okno(null, 'https://glasba.primer.test');
    v3.document.querySelectorAll = () => [];
    vm.runInContext(JS, v3);
    posljiOd(v3, okno(v3, 'https://oglas.test'), { safeer: 'stanje', p: true, t: 50 });
    preveri('5 (N-E): okvir brez kljuca: brez znaka', {}, stanjeVrha(v3));
}

if (padle) { console.log('igralec_posrednik_test: PADLO ' + padle + ' (uspelo ' + stevilo + ')'); process.exit(1); }
console.log('igralec_posrednik_test: OK (' + stevilo + ')');
