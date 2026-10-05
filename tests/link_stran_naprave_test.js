#!/usr/bin/env node
// Stran Safeer Linka v aplikacijah za Android (assets/link/link.js): ikona in napis TE naprave, ikone drugih naprav.
//
// Izmerjeno 5. 10. 2026 na telefonu (Safeer OS 0.5.52): telefon je sebe kazal z ikono televizorja in napisom
// »Safeer Link teče na tem televizorju« (most.jeTelevizor() vraca true na vsaki napravi z Androidom), racunalnik z
// imenom brez besede »računalnik« pa z ikono telefona (most strani ni podal platforme, stran je ugibala po imenu).
//
// Preizkus stran ZARES pozene (node, brez brskalnika; elementi strani so preproste nadomestne vrednosti) z mostom,
// kakrsnega pripne aplikacija.   node tests/link_stran_naprave_test.js
'use strict';
const vm = require('vm'), fs = require('fs'), path = require('path'), assert = require('assert');

const VIR = fs.readFileSync(path.join(__dirname, '..', 'assets', 'link', 'link.js'), 'utf8').replace(/\r\n/g, '\n');
const LOVILEC = '} catch (e) {\n      // Stran nikoli ne sme pasti zaradi odziva.\n    }';
assert.strictEqual(VIR.split(LOVILEC).length, 2, 'lovilca odzivov ni ali ni en sam');

function karkoli() {
  const f = function () { return karkoli(); };
  return new Proxy(f, {
    get(t, k) {
      if (k === Symbol.toPrimitive) return function () { return ''; };
      if (k === 'toString' || k === 'valueOf') return function () { return ''; };
      if (k === 'length') return 0;
      if (typeof k === 'symbol') return undefined;
      return karkoli();
    },
    set() { return true; }
  });
}

function element(id) {
  const stor = { id: id, textContent: '', hidden: false, value: '', innerHTML: '', className: '', title: '', disabled: false,
                 checked: false, children: [], childNodes: [], options: [], style: {}, dataset: {}, parentNode: null,
                 classList: { add() {}, remove() {}, toggle() { return false; }, contains() { return false; } },
                 addEventListener() {}, removeEventListener() {}, setAttribute() {}, removeAttribute() {},
                 getAttribute() { return null; }, appendChild(o) { stor.children.push(o); return o; },
                 removeChild(o) { return o; }, insertBefore(o) { stor.children.push(o); return o; },
                 querySelector() { return null; }, querySelectorAll() { return []; },
                 focus() {}, blur() {}, click() {}, scrollIntoView() {}, contains() { return false; },
                 getBoundingClientRect() { return { left: 0, top: 0, width: 0, height: 0, right: 0, bottom: 0 }; } };
  return new Proxy(stor, {
    get(t, k) { if (k in t) return t[k]; if (typeof k === 'symbol') return undefined; return karkoli(); },
    set(t, k, v) { t[k] = v; if (k === 'innerHTML' && v === '') t.children = []; return true; }
  });
}

const JAZ = 'n-jaz';

/** Most, kakrsnega pripne aplikacija (LinkMost.kt): stanje in seznam naprav kot besedilo JSON. */
function most(platforma, naprave) {
  const znano = {
    jeTelevizor() { return true; },                   // vsaka aplikacija za Android (tudi telefon in tablica)
    jezik() { return 'sl'; },
    stanje() { return JSON.stringify({ znan: true, seznanjen: true, hub: 'wss://10.0.0.5:8990/cast/ws', naprava: 'Moja naprava', id: JAZ }); },
    naprave() { return JSON.stringify(naprave); },
    poveziSe() {}, lahkoVOspredje() { return true; },
    trenutnaStranJson() { return JSON.stringify({ url: '', naslov: '', posljiva: false }); }
  };
  if (platforma !== undefined) znano.platforma = function () { return platforma; };
  return new Proxy(znano, {
    get(t, k) {
      if (k in t) return t[k];
      if (k === 'platforma' || typeof k === 'symbol') return undefined;       // starejsa aplikacija platforme ne pove
      return function () { return ''; };
    },
    has(t, k) { return k in t; }
  });
}

/** Stran pozene z danim mostom; vrne dostop do elementov in napake, ki bi jih sicer pogoltnil lovilec odzivov. */
function stran(platforma, naprave) {
  const elementi = {};
  const el = function (id) { if (!elementi[id]) elementi[id] = element(id); return elementi[id]; };
  const obZagonu = [];
  const document = {
    documentElement: element('html'), body: element('body'), title: '', hidden: false, visibilityState: 'visible',
    getElementById: el, querySelector() { return null; }, querySelectorAll() { return []; },
    addEventListener(ime, f) { if (ime === 'DOMContentLoaded') obZagonu.push(f); }, removeEventListener() {},
    createElement() { return element(''); }, createTextNode(b) { return { textContent: b, children: [] }; },
    createDocumentFragment() { return element(''); }
  };
  const okno = {
    document: document, navigator: { language: 'sl' }, location: { hash: '', search: '', href: '' },
    localStorage: { getItem() { return null; }, setItem() {}, removeItem() {} },
    setTimeout() { return 0; }, clearTimeout() {}, setInterval() { return 0; }, clearInterval() {},
    requestAnimationFrame() { return 0; }, addEventListener() {}, removeEventListener() {},
    matchMedia() { return { matches: false, addEventListener() {}, addListener() {} }; },
    console: { log() {}, warn() {}, error() {} }, __napake: [],
    SafeerLink: most(platforma, naprave)
  };
  okno.window = okno;
  vm.createContext(okno);
  vm.runInContext(VIR.replace(LOVILEC, '} catch (e) { window.__napake.push(String((e && e.stack) || e)); }'), okno);
  obZagonu.forEach(function (f) { f(); });
  assert.deepStrictEqual(okno.__napake, [], 'stran je ob odzivu padla');
  return { el: el, okno: okno };
}

function ikona(li) {
  const svg = String(li.children[0].innerHTML);
  if (svg.indexOf('M2 4h20v13H2z') >= 0) return 'tv';
  if (svg.indexOf('M1 3h22v14H1z') >= 0) return 'racunalnik';
  if (svg.indexOf('M6.5 1.5h11a2.5 2.') >= 0) return 'telefon';
  if (svg.indexOf('M3 11 12 3l9 8') >= 0) return 'hisa';
  return '?' + svg.slice(0, 60);
}

/** Vrstice seznama »Povezane naprave«: {ime: ikona}. */
function vrstice(s) {
  const izid = {};
  s.el('seznamNaprav').children.forEach(function (li) { izid[String(li.children[1].children[0].textContent)] = ikona(li); });
  return izid;
}

const NAPRAVE = [
  { id: JAZ, ime: 'Moja naprava', vloga: 'receiver', zmoznosti: [], platforma: '', vrsta: '' },
  { id: 'n-linux-control', ime: 'Safeer Control (racunalnik-Extensa-215-54)', vloga: 'controller', zmoznosti: ['files'], platforma: 'linux', vrsta: 'control' },
  { id: 'n-win-control', ime: 'Safeer Control (DESKTOP-S34LVH4)', vloga: 'controller', zmoznosti: ['files'], platforma: 'windows', vrsta: 'control' },
  { id: 'n-tel', ime: 'Safeer OS (LE2113)', vloga: 'receiver', zmoznosti: ['screen'], platforma: 'phone', vrsta: 'screen' },
  { id: 'n-tab', ime: 'Tablica lastnik', vloga: 'receiver', zmoznosti: ['screen'], platforma: 'tablet', vrsta: 'screen' },
  { id: 'n-tv', ime: 'Dnevna soba', vloga: 'receiver', zmoznosti: ['screen'], platforma: 'tv', vrsta: 'screen' },
  { id: 'n-star-zaslon', ime: 'Stara naprava', vloga: 'receiver', zmoznosti: [], platforma: '', vrsta: '' },
  { id: 'n-star-krmilnik', ime: 'Neznana naprava', vloga: 'controller', zmoznosti: [], platforma: '', vrsta: '' }
];

let preizkusov = 0;
function preizkus(ime, f) { f(); preizkusov += 1; console.log('  OK   ' + ime); }

preizkus('telefon: svoja ikona je telefon, napis ne omenja televizorja', function () {
  const s = stran('phone', NAPRAVE);
  assert.strictEqual(vrstice(s)['Moja naprava'], 'telefon');
  assert.strictEqual(String(s.el('naslovHubTu').textContent), 'Safeer Link teče na tej napravi');
});

preizkus('tablica: enako kot telefon', function () {
  const s = stran('tablet', NAPRAVE);
  assert.strictEqual(vrstice(s)['Moja naprava'], 'telefon');
  assert.strictEqual(String(s.el('naslovHubTu').textContent), 'Safeer Link teče na tej napravi');
});

preizkus('televizor: ikona televizorja in napis o televizorju', function () {
  const s = stran('tv', NAPRAVE);
  assert.strictEqual(vrstice(s)['Moja naprava'], 'tv');
  assert.strictEqual(String(s.el('naslovHubTu').textContent), 'Safeer Link teče na tem televizorju');
});

preizkus('starejsa aplikacija brez platforma(): kot doslej', function () {
  const s = stran(undefined, NAPRAVE);
  assert.strictEqual(vrstice(s)['Moja naprava'], 'tv');
  assert.strictEqual(String(s.el('naslovHubTu').textContent), 'Safeer Link teče na tem televizorju');
});

preizkus('druge naprave: ikona po platformi, ne po imenu ali vlogi', function () {
  const v = vrstice(stran('phone', NAPRAVE));
  assert.strictEqual(v['Safeer Control (racunalnik-Extensa-215-54)'], 'racunalnik', 'ime brez besede »računalnik« (prej ikona telefona)');
  assert.strictEqual(v['Safeer Control (DESKTOP-S34LVH4)'], 'racunalnik');
  assert.strictEqual(v['Safeer OS (LE2113)'], 'telefon', 'telefon z vlogo zaslona (prej ikona televizorja)');
  assert.strictEqual(v['Tablica lastnik'], 'telefon');
  assert.strictEqual(v['Dnevna soba'], 'tv');
});

preizkus('naprava brez platforme: po vlogi in imenu kot doslej', function () {
  const v = vrstice(stran('phone', NAPRAVE));
  assert.strictEqual(v['Stara naprava'], 'tv', 'zaslon brez platforme');
  assert.strictEqual(v['Neznana naprava'], 'telefon');
});

console.log('link_stran_naprave_test: OK (' + preizkusov + ')');
