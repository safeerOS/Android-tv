#!/usr/bin/env node
// Meritev cene nasih skript na TV (assets/tv_spatial.js, H55):   node tests/tv_perf_meritev_test.js
//
// Zakaj (lastnik 11. 11. 2026: »24ur.com je se vedno pocasna«): na TV renderer na 24ur.com porabi 73-90 % jedra, v TV Bro 41-45 %;
// brez DevTools ni mogoce locevati, koliko glavne niti pojedo nasi opazovalci. Skript zato izpise stevce (H55) po 8 s in 20 s.
// Preizkus pade brez meritve in ce stevci spremenijo vedenje (npr. izjema v okolju brez performance).
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const VIR = fs.readFileSync(path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
let n = 0;
function ok(pogoj, opis) { assert.ok(pogoj, opis); n++; console.log('  OK   ' + opis); }

ok(VIR.indexOf("window._safeerDbg('H55', 'tv_spatial.js:perf'") > 0, 'izpis H55 obstaja');
ok(/setTimeout\(function \(\) \{ porociPerf\('8s'\); \}, 8000\)/.test(VIR) && /setTimeout\(function \(\) \{ porociPerf\('20s'\); \}, 20000\)/.test(VIR), 'izpis po 8 s in 20 s');
ok(VIR.indexOf('if (perfN++ >= 2 || !window._safeerDbg) return;') > 0, 'najvec dva izpisa na stran');
ok(/var merCas = function \(\) \{ try \{ return performance\.now\(\); \} catch \(_\) \{ return Date\.now\(\); \} \};/.test(VIR), 'cas brez performance ne vrze izjeme');
// Merjenje ne sme ostati brez finally: izjema v opazovalcu ne sme izgubiti stevca ali preprecevati vedenja.
ok((VIR.match(/finally \{ merStat\./g) || []).length >= 3 && /finally \{ merStat\.moMs/.test(VIR), 'stevci v finally');
console.log('tv_perf_meritev_test: OK (' + n + ')');
