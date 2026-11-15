#!/usr/bin/env node
// Predgrevanje ciljev daljinca po koncu drsenja (assets/tv_spatial.js):   node tests/tv_predgrevanje_test.js
//
// Zakaj (11. 11. 2026, TV, 24ur.com): drsenje z daljincem 43 % zatikajocih slicic, brez tv_spatial.js 1,7 %. Vsaka tipka
// drsa stran, drsenje spremeni kljuc predpomnilnika kandidatov, zato je zbiranje ciljev (100-230 ms na TV) teklo v istem
// okvirju kot zacetek drsenja. Predgrevanje zbere cilje po koncu drsenja; naslednja tipka zadene predpomnilnik.
// Preizkus pade brez popravka (ali ce kdo odstrani katerikoli del veriznega mehanizma). Pot do skripta za mutacijski preizkus:
// TV_SPATIAL_DATOTEKA=/pot/do/kopije node tests/tv_predgrevanje_test.js
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const VIR = fs.readFileSync(process.env.TV_SPATIAL_DATOTEKA || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
let n = 0;
function ok(pogoj, opis) { assert.ok(pogoj, opis); n++; console.log('  OK   ' + opis); }
function telo(ime) {
    const i = VIR.indexOf('function ' + ime + '(');
    assert.ok(i > 0, 'funkcija ' + ime + ' obstaja');
    let g = VIR.indexOf('{', i), d = 0, j = g;
    for (; j < VIR.length; j++) { if (VIR[j] === '{') d++; else if (VIR[j] === '}' && --d === 0) break; }
    return VIR.slice(g, j + 1);
}

// 1. Drsenje (okno ali vsebnik) sproži nacrt predgrevanja v istem poslusevalcu, ki spreminja kljuc predpomnilnika.
ok(/addEventListener\('scroll', function \(\) \{ drsenjeRazlicica\+\+; nacrtujPredgrevanje\(\); \}, \{ capture: true, passive: true \}\)/.test(VIR),
    'drsenje sprozi nacrtujPredgrevanje');
// 2. Tipka daljinca zabelezi cas; brez tipke predgrevanja ni (stran, ki jo drsa uporabnik s prstom, ne dobi dela).
ok(/var navigirajProstorsko = function\(direction, robVrstice, smemVrstico\) \{\s*zadnjaTipkaCas = casZdaj\(\);/.test(VIR), 'tipka belezi zadnjaTipkaCas');
const nac = telo('nacrtujPredgrevanje');
ok(/if \(!zadnjaTipkaCas \|\| predgrevanjeSt >= PREDGREVANJE_NAJVEC\) return;/.test(nac) && /clearTimeout\(predgrevanjeCasovnik\)/.test(nac) && /setTimeout\(izvediPredgrevanje, PREDGREVANJE_ZAMIK_MS\)/.test(nac),
    'nacrt: samo po tipki, odskocni casovnik (zbiranje po koncu drsenja)');
// 2b. Sprememba strani po drsenju (lenobne slike, nasi tabindex) zavrze izid: tudi ta sprozi nov nacrt (omejen na PREDGREVANJE_NAJVEC).
ok(/domRazlicica\+\+; nacrtujPredgrevanje\(\); \} \}/.test(VIR) && /napisanih > 0\)\) \{ domRazlicica\+\+; nacrtujPredgrevanje\(\); \}/.test(VIR) && /predgrevanjeSt = 0;/.test(VIR),
    'sprememba DOM sprozi nacrt, stevec se ponastavi ob tipki');
// 3. Izvedba: omejitev casa po tipki, ne na skriti strani, preskok ce je predpomnilnik svez, zastavica samo okoli zbiranja.
const izv = telo('izvediPredgrevanje');
ok(/PREDGREVANJE_PO_TIPKI_MS/.test(izv) && /document\.hidden/.test(izv) && /kandOpazovalec/.test(izv), 'izvedba: omejitev po tipki, skrita stran, opazovalec');
ok(/k === kandKljuc/.test(izv) && /if \(predgrevanjeSt\+\+ >= PREDGREVANJE_NAJVEC\) return;\s*predgrevanje = true;\s*getCandidates\(\);/.test(izv) && /finally \{ predgrevanje = false; \}/.test(izv),
    'izvedba: preskok ob svezem predpomnilniku, getCandidates z zastavico, zastavica se vedno ugasne');
// 4. Predgrevanje ne sme spreminjati izbire: obroc cisti samo tipka.
ok(!/(^|[^!]\s)pocistiObroc\(/m.test(telo('odstraniNedosegljiveJedro').replace(/if \(!predgrevanje\) pocistiObroc\(/g, '')) &&
    (telo('odstraniNedosegljiveJedro').match(/if \(!predgrevanje\) pocistiObroc\(/g) || []).length === 3, 'odstraniNedosegljive: pocistiObroc samo izven predgrevanja (3 klici)');
// 5. Izid jeNogaAliPiskotki velja znotraj enega zbiranja (klican je dvakrat: za element in za kartico), nikoli med zbiranji.
ok(/function jeNogaVZbiranju\(el\) \{\s*if \(!nogaIzid \|\| !el\) return jeNogaAliPiskotki\(el\);/.test(VIR) && /if \(nogaIzid\.has\(el\)\) return nogaIzid\.get\(el\);/.test(VIR) && /nogaIzid\.set\(el, r\)/.test(VIR), 'jeNogaVZbiranju: izid za element v zbiranju');
ok(/jeNogaVZbiranju\(el\)\) od = null;/.test(VIR) && /if \(jeNogaVZbiranju\(item\)\) \{/.test(VIR), 'zbiranje uporablja izid pri obeh klicih (element in kartica)');
ok(/var nogaZbiranje = new Map\(\);\s*nogaIzid = nogaZbiranje;\s*try \{ found = odstraniNedosegljive\(getCandidatesFresh\(\)\); \} finally \{ nogaIzid = null; \}/.test(VIR), 'izid se zavrze ob koncu zbiranja (tudi ob izjemi)');
// 6. Razumne meje (cas po tipki, zamik) - ne prevec ne premalo.
const zamik = +(VIR.match(/PREDGREVANJE_ZAMIK_MS = (\d+)/) || [])[1], poTipki = +(VIR.match(/PREDGREVANJE_PO_TIPKI_MS = (\d+)/) || [])[1];
ok(zamik >= 100 && zamik <= 300 && poTipki >= 3000 && poTipki <= 15000, 'meje: zamik ' + zamik + ' ms, po tipki ' + poTipki + ' ms');
console.log('tv_predgrevanje_test: OK (' + n + ')');
