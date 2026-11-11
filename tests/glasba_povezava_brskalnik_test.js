#!/usr/bin/env node
// Povezava prenosa (RTV SLO) v Glasbi se odpre v uporabnikovem izbranem brskalniku (lastnik 11. 11. 2026):   node tests/glasba_povezava_brskalnik_test.js
'use strict';
const assert = require('assert'), fs = require('fs'), path = require('path');
const vir = fs.readFileSync(path.join(__dirname, '..', 'src/main/kotlin/si/safeer/tv/os/GlasbaActivity.kt'), 'utf8');
assert.ok(!/Intent\(Intent\.ACTION_VIEW, android\.net\.Uri\.parse\(sk\.povezava\)\)/.test(vir), 'sk.povezava ne sme na sistemski privzeti brskalnik');
assert.ok(vir.includes('Brskalnik.iskanje(this, sk.povezava)'), 'sk.povezava gre prek Brskalnik.iskanje');
console.log('glasba_povezava_brskalnik_test: OK');
