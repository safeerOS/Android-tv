// Lastnik 3. 11. 2026: brskalnik ne sme sam sprejeti ali zavrniti piskotkov - izbira je uporabnikova.
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const vir = fs.readFileSync(path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
assert.ok(!/autoDismissGdpr/.test(vir), 'samodejno potrjevanje piskotkov (autoDismissGdpr) ne sme obstajati');
assert.ok(!/didomi-notice-agree-button/.test(vir), 'gumb za soglasje Didomi se ne klikne sam');
const besede = /(accept all|allow all|sprejmi vse|sprejmi piškotke|reject all|zavrni vse)/i;
for (const vrstica of vir.split('\n')) {
    if (besede.test(vrstica) && /\.click\(\)/.test(vrstica)) {
        assert.fail('vrstica klikne gumb soglasja: ' + vrstica.trim());
    }
}
console.log('tv_brez_samodejnih_piskotkov_test: OK');
