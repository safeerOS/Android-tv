#!/usr/bin/env node
// Lastnik 5. 11. 2026 19:12: pravi dotik tudi na »Nisem robot« (reCAPTCHA, hCaptcha) - samo okvir s potrditvenim poljem,
// z enako strogo preverbo izvora kot GSI. Izziv s slikami (bframe, frame=challenge), lazen izvor ali okvir pod
// gostiteljem shadow DOM: brez pravega dotika.   node tests/tv_nisem_robot_test.js [pot do tv_spatial.js]
// SAFEER_VSE_NAPAKE=1: izpise vse padle preverbe (dokaz, da brez popravka pade), sicer ustavi ob prvi.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { ustvari } = require('./tv_lazni_dom');

const VIR = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
const VSE = process.env.SAFEER_VSE_NAPAKE === '1';
let padle = 0;
function preveri(opis, pricakovano, dobljeno) {
    try {
        assert.deepStrictEqual(dobljeno, pricakovano, opis);
        console.log('  OK   ' + opis);
    } catch (_) {
        padle++;
        console.log('  PADLO ' + opis + ' (pricakovano ' + JSON.stringify(pricakovano) + ', dobljeno ' + JSON.stringify(dobljeno) + ')');
        if (!VSE) process.exit(1);
    }
}
function blok(ime) {
    const i = VIR.indexOf('/* ' + ime + '_ZACETEK'), j = VIR.indexOf('/* ' + ime + '_KONEC */');
    return i >= 0 && j > i ? VIR.slice(i, j) : '';
}
const ZETON = '0123456789abcdef'.repeat(3);
const HC = 'https://newassets.hcaptcha.com/captcha/v1/a8d420e70f47f23124b1fa1b44c812cc4e5d6bc0/static/hcaptcha.html';
const PRAVI = {
    'reCAPTCHA api2': 'https://www.google.com/recaptcha/api2/anchor?ar=1&k=6Lc_x&co=aHR0cHM6Ly9wcmltZXIudGVzdDo0NDM.&hl=sl&v=r1&size=normal&cb=q1',
    'reCAPTCHA enterprise': 'https://www.google.com/recaptcha/enterprise/anchor?ar=1&k=6Lc_x&co=x&hl=sl&v=r1&size=normal&cb=q1',
    'recaptcha.net api2': 'https://www.recaptcha.net/recaptcha/api2/anchor?ar=1&k=6Lc_x&co=x&hl=sl&v=r1&size=normal',
    'recaptcha.net enterprise': 'https://www.recaptcha.net/recaptcha/enterprise/anchor?ar=1&k=6Lc_x&co=x&hl=sl&v=r1',
    'hCaptcha': HC + '#frame=checkbox&id=0abc&host=primer.test&sentry=true',
    'GSI (prej)': 'https://accounts.google.com/gsi/button?theme=outline',
};
const LAZNI = {
    'reCAPTCHA izziv (bframe)': 'https://www.google.com/recaptcha/api2/bframe?hl=sl&v=r1&k=6Lc_x',
    'reCAPTCHA enterprise bframe': 'https://www.google.com/recaptcha/enterprise/bframe?hl=sl&k=6Lc_x',
    'tuj gostitelj google.com.napadalec.si': 'https://www.google.com.napadalec.si/recaptcha/api2/anchor?ar=1&k=x',
    'tuj gostitelj z uporabnikom (@)': 'https://www.google.com@napadalec.si/recaptcha/api2/anchor?ar=1',
    'http:// namesto https://': 'http://www.google.com/recaptcha/api2/anchor?ar=1&k=x',
    'druga pot na google.com': 'https://www.google.com/search?q=recaptcha/api2/anchor',
    'druga pot (anchorx)': 'https://www.google.com/recaptcha/api2/anchorx?ar=1',
    'google.com brez www': 'https://google.com/recaptcha/api2/anchor?ar=1&k=x',
    'recaptcha.net druga pot': 'https://www.recaptcha.net/recaptcha/api2/userverify?k=x',
    'hCaptcha izziv (frame=challenge)': HC + '#frame=challenge&id=0abc&host=primer.test',
    'hCaptcha nevidni (checkbox-invisible)': HC + '#frame=checkbox-invisible',
    'hCaptcha druga datoteka': 'https://newassets.hcaptcha.com/captcha/v1/x/static/drugo.html#frame=checkbox&id=1',
    'hCaptcha poizvedba pred #': HC + '?a=1#frame=checkbox&id=1',
    'hCaptcha brez #': HC,
    'tuj gostitelj newassets.hcaptcha.com.napadalec.si': 'https://newassets.hcaptcha.com.napadalec.si/captcha/v1/x/static/hcaptcha.html#frame=checkbox&id=1',
    'hcaptcha.com druga poddomena': 'https://napadalec.hcaptcha.com/captcha/v1/x/static/hcaptcha.html#frame=checkbox&id=1',
    'http:// hCaptcha': 'http://newassets.hcaptcha.com/captcha/v1/x/static/hcaptcha.html#frame=checkbox&id=1',
};

// Tretji neodvisni pregled 5. 11. 2026 (odlocitev vodje): pravi dotik v tuj okvir je izklopljen (PRAVI_DOTIK_V_OKVIR =
// false). Seznam izvorov ostane za ponovni vklop s potrditvijo na strani prejemnika - preverjamo ga z vklopljeno zastavico.
const VIR_PROIZVODNJA = VIR;
const VIR_OKVIR = VIR.split('var PRAVI_DOTIK_V_OKVIR = false;').join('var PRAVI_DOTIK_V_OKVIR = true;');
{
    const fp = new Function(VIR_PROIZVODNJA.slice(VIR_PROIZVODNJA.indexOf('/* SAFEER_PRAVI_DOTIK_ZACETEK'), VIR_PROIZVODNJA.indexOf('/* SAFEER_PRAVI_DOTIK_KONEC */')) + '\nreturn { smePraviDotik };')();
    const zp = (d) => fp.smePraviDotik(Object.assign({ izvirniki: true, omrezni: true, potrjena: true, namen: 'google', oznakaCilja: 'IFRAME',
        zadetekJeCilj: true, zadetekVCilju: true, oznakaZadetka: 'IFRAME' }, d));
    for (const [ime, v] of Object.entries(PRAVI)) preveri('proizvodnja: ' + ime + ': brez pravega dotika (izklopljeno)', false, zp({ virCilja: v }));
    preveri('proizvodnja: izziv Cloudflare: brez pravega dotika', false, zp({ namen: 'izziv', oznakaZadetka: 'IFRAME',
        virZadetka: 'https://challenges.cloudflare.com/cdn-cgi/x' }));
    preveri('proizvodnja: zastavica je izklopljena', true, VIR_PROIZVODNJA.indexOf('var PRAVI_DOTIK_V_OKVIR = false;') !== -1);
}
// Ciste odlocitve (SAFEER_PRAVI_DOTIK) z vklopljeno zastavico.
const f = new Function(VIR_OKVIR.slice(VIR_OKVIR.indexOf('/* SAFEER_PRAVI_DOTIK_ZACETEK'), VIR_OKVIR.indexOf('/* SAFEER_PRAVI_DOTIK_KONEC */')) + '\nreturn { smePraviDotik };')();
const osnova = { izvirniki: true, omrezni: true, potrjena: true, namen: 'google', oznakaCilja: 'IFRAME', zadetekJeCilj: true,
    zadetekVCilju: true, oznakaZadetka: 'IFRAME' };
const z = (d) => f.smePraviDotik(Object.assign({}, osnova, d));
for (const [ime, v] of Object.entries(PRAVI)) preveri('cisto: ' + ime + ': pravi dotik', true, z({ virCilja: v }));
for (const [ime, v] of Object.entries(LAZNI)) preveri('cisto: ' + ime + ': sinteticen klik', false, z({ virCilja: v }));
preveri('cisto: reCAPTCHA, na tocki ni okvir sam (gostitelj shadow DOM): ne', false, z({ virCilja: PRAVI['reCAPTCHA api2'], zadetekJeCilj: false, oznakaZadetka: 'DIV' }));
preveri('cisto: hCaptcha, cilj ni okvir (gostitelj): ne', false, z({ virCilja: PRAVI.hCaptcha, oznakaCilja: 'DIV' }));
preveri('cisto: reCAPTCHA brez izvirnikov / nepotrjena / ne z omrezja: ne', [false, false, false],
    [z({ virCilja: PRAVI['reCAPTCHA api2'], izvirniki: false }), z({ virCilja: PRAVI['reCAPTCHA api2'], potrjena: false }),
        z({ virCilja: PRAVI['reCAPTCHA api2'], omrezni: false })]);
preveri('cisto: vir ni niz: ne', false, z({ virCilja: { toString: () => PRAVI['reCAPTCHA api2'] } }));

// Celoten OK v laznem DOM (izvirni getter src, elementFromPoint, most triggerNativeTap).
function ok(src, prekrij, vir) {
    const s = ustvari({ zeton: ZETON });
    const o = s.el('iframe', { attrs: { src: src, title: 'reCAPTCHA' }, okvir: [100, 100, 304, 78] });
    s.body.dodaj(o);
    if (prekrij) s.body.dodaj(s.el('div', { okvir: [100, 100, 304, 78] }));   // gostitelj shadow DOM cez okvir
    s.nalozi(vir || VIR_OKVIR);
    s.izberi(o);
    s.ok();
    return s.dotiki;
}
for (const ime of Object.keys(PRAVI)) preveri('OK proizvodnja: ' + ime + ': ni pravega dotika', [], ok(PRAVI[ime], false, VIR_PROIZVODNJA));
for (const ime of ['reCAPTCHA api2', 'reCAPTCHA enterprise', 'recaptcha.net api2']) {
    preveri('OK: ' + ime + ': en pravi dotik na potrditveno polje (31 px od levega roba, sredina visine)', [[131, 139]], ok(PRAVI[ime]));
}
preveri('OK: hCaptcha: en pravi dotik na sredini okvirja', [[252, 139]], ok(PRAVI.hCaptcha));
for (const ime of ['reCAPTCHA izziv (bframe)', 'tuj gostitelj google.com.napadalec.si', 'http:// namesto https://',
    'hCaptcha izziv (frame=challenge)', 'tuj gostitelj newassets.hcaptcha.com.napadalec.si']) {
    preveri('OK: ' + ime + ': ni pravega dotika', [], ok(LAZNI[ime]));
}
preveri('OK: reCAPTCHA pod gostiteljem shadow DOM: ni pravega dotika', [], ok(PRAVI['reCAPTCHA api2'], true));
preveri('OK: hCaptcha pod gostiteljem shadow DOM: ni pravega dotika', [], ok(PRAVI.hCaptcha, true));

if (padle) { console.log('PADLO: ' + padle); process.exit(1); }
console.log('tv_nisem_robot_test: vse OK');
