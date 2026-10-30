#!/usr/bin/env node
// OK na daljincu = natanko en klik; sirok nizek gumb je cilj (assets/tv_spatial.js):   node tests/tv_en_klik_test.js
//
// Zakaj (lastnik 28. 10. 2026, Facebook na TV): »Pokazi geslo« ni delal pravilno - OK je sprozil tri klike (sinteticni
// click, el.click(), pravi dotik), preklop se je prestavil veckrat. Gumb »Prijava« (<div role=button> cez ves obrazec)
// je izlocilo pravilo za siroke vsebnike.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const VIR = fs.readFileSync(path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
function blok(ime, vrni) {
    const od = VIR.indexOf('/* ' + ime + '_ZACETEK */'), doK = VIR.indexOf('/* ' + ime + '_KONEC */');
    assert.ok(od > 0 && doK > od, 'bloka ' + ime + ' ni');
    return { koda: VIR.slice(od, doK), fn: vrni ? new Function(VIR.slice(od, doK) + '\nreturn ' + vrni + ';')() : null };
}
let n = 0;
function preveri(opis, pric, dob) { assert.strictEqual(dob, pric, opis); n++; console.log('  OK   ' + opis); }

const naElementu = blok('SAFEER_NA_ELEMENTU', 'naElementu').fn;
const el = { contains: (x) => x === 'otrok' };
preveri('zadetek je element', true, naElementu(el, el));
preveri('zadetek je potomec (ikona v gumbu)', true, naElementu(el, 'otrok'));
preveri('zadetek je prekrivni sloj', false, naElementu(el, 'drugo'));
preveri('brez zadetka', false, naElementu(el, null));

const jeSirokGumb = blok('SAFEER_SIROK_GUMB', 'jeSirokGumb').fn;
const e = (atr) => ({ getAttribute: (k) => (k in atr ? atr[k] : null) });
preveri('div role=button 48 px visok = gumb', true, jeSirokGumb(e({ role: 'button' }), { height: 48 }, 540));
preveri('tabindex=0 nizek = gumb', true, jeSirokGumb(e({ tabindex: '0' }), { height: 40 }, 540));
preveri('tabindex=-1 ni gumb', false, jeSirokGumb(e({ tabindex: '-1' }), { height: 40 }, 540));
preveri('role=button visok 300 px je vsebnik', false, jeSirokGumb(e({ role: 'button' }), { height: 300 }, 540));
preveri('sirok div brez vloge ni gumb', false, jeSirokGumb(e({}), { height: 40 }, 540));

const klik = blok('SAFEER_EN_KLIK').koda.split('\n').filter((v) => !/^\s*\/\//.test(v)).join('\n');
preveri('en el.click()', 1, (klik.match(/el\.click\(\)/g) || []).length);
preveri('brez sinteticnega dogodka click', false, /new MouseEvent\('click'/.test(klik));
preveri('pravi dotik samo, ce je zadetek na elementu', true, /if \(naElementu\(el, zadetek\)\)/.test(klik));
preveri('miska samo, ce dotik ni sprejet', true, /if \(!tapnjeno\)/.test(klik));
assert.ok(VIR.indexOf("!jeSirokGumb(el, rect, winH)") > 0, 'isActionable ne uporablja jeSirokGumb');
// claude.ai prijava (30. 10. 2026): Tailwind »duration-200« ne sme izlociti pravega gumba, povezave ali polja.
preveri('pravilo za casovne oznake ne velja za prave gumbe', true,
    /var pravGumb = tag === 'A' \|\| tag === 'BUTTON' \|\| tag === 'INPUT'/.test(VIR) &&
    /if \(!pravGumb && \(cls\.indexOf\('time'\)/.test(VIR));
console.log('tv_en_klik_test: OK (' + n + ')');
