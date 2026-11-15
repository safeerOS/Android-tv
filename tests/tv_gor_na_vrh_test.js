#!/usr/bin/env node
// SAFEER_GOR_NA_VRH (lastnik 15. 11. 2026): z dna strani nazaj na vrh z daljincem je tezko in na koncu ni vidnega kazalca.
// Umetna stran ~1900 elementov (40 vrst kartic, tests/tv_velika_stran.js), fokus na zadnji kartici, N pritiskov GOR: izbrani cilj
// nikoli ni izgubljen, je ves cas viden v pogledu, vrh dosezemo v razumnem stevilu pritiskov (ena vrsta na pritisk).
// Na vrhu aplikacija GOR v naslovno vrstico ne pusti ob drzani tipki in takoj po drsenju gor (TvTipke.gorPritisk): skript dobi
// to kot tretji argument (smemVrstico === false) in izbire NE sme pociscati (sicer ostane stran brez kazalca); ko v vrstico sme,
// vrne -1 in izbiro pocisti (kot prej).
//   node tests/tv_gor_na_vrh_test.js [pot do tv_spatial.js]   (SAFEER_IZPIS=1 izpise potek)
'use strict';
const fs = require('fs');
const path = require('path');
const { zgradi } = require('./tv_velika_stran');

const VIR = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');
let stevilo = 0;
function preveri(opis, pogoj, podrobnost) {
    if (!pogoj) { console.log('  PADLO ' + opis + ' ' + (podrobnost === undefined ? '' : JSON.stringify(podrobnost))); process.exit(1); }
    stevilo++; console.log('  OK   ' + opis);
}

// Pot v dani smeri; ustavi se, ko je izbrana povezava v glavi strani (vrh) ali po najvec pritiskih.
function pot(smer, zacetek, najvec, o) {
    const t = zgradi(VIR, o);
    const { s } = t;
    const maxY = s.document.documentElement.scrollHeight - s.document.documentElement.clientHeight;
    if (zacetek === 'dno') { s.window.scrollY = maxY; s.document.documentElement.scrollTop = maxY; }
    const prva = zacetek === 'dno' ? t.kartice[t.kartice.length - 3] : t.kartice[0];   // dno: spodnja vrsta, levi stolpec
    s.izberi(prva.povezava);
    const potek = [];
    let izgub = 0, nevidnih = 0, prvaIzgubaPri = -1, vrh = -1;
    for (let i = 1; i <= najvec; i++) {
        s.smer(smer);
        const ime = t.izbran(), vid = t.viden();
        potek.push(i + ':' + (ime || 'BREZ') + '@' + Math.round(s.window.scrollY));
        if (!ime) { izgub++; if (prvaIzgubaPri < 0) prvaIzgubaPri = i; }
        else if (!vid) nevidnih++;
        if (smer === 'UP' && ime.indexOf('Rubrika') === 0) { vrh = i; break; }
    }
    return { t, potek, izgub, nevidnih, prvaIzgubaPri, vrh, scrollY: s.window.scrollY };
}

const gor = pot('UP', 'dno', 80);
if (process.env.SAFEER_IZPIS) console.log(JSON.stringify({ gor: Object.assign({}, gor, { t: undefined, potek: gor.potek.join(' ') }) }, null, 1));
preveri('GOR z dna: izbira nikoli ni izgubljena (nobenega pritiska brez kazalca)', gor.izgub === 0, { izgub: gor.izgub, prvaIzgubaPri: gor.prvaIzgubaPri });
preveri('GOR z dna: kazalec je po vsakem pritisku v pogledu', gor.nevidnih === 0, { nevidnih: gor.nevidnih });
preveri('GOR z dna: glava strani (vrh) dosezena v najvec 60 pritiskih (40 vrst)', gor.vrh > 0 && gor.vrh <= 60, { vrh: gor.vrh });
preveri('GOR z dna: stran je na vrhu', gor.scrollY <= 20, gor.scrollY);

// Na vrhu: GOR, ki ga aplikacija v vrstico ne spusti (drzana tipka, takoj po drsenju), kazalca ne sme vzeti.
{
    const t = gor.t, s = t.s;
    const pred = t.izbran();
    for (let i = 0; i < 3; i++) {
        const r = s.smer('UP', false, false);
        preveri('na vrhu, GOR brez dovoljenja za vrstico (' + (i + 1) + '): izbira ostane (' + pred + ')', t.izbran() === pred && t.viden() === true && r !== -1, { izbran: t.izbran(), r });
    }
    const r2 = s.smer('UP', false, true);
    preveri('na vrhu, GOR z dovoljenjem: skript preda tipko vrstici (-1) in izbiro pocisti', r2 === -1 && t.izbran() === '', { r: r2, izbran: t.izbran() });
}

const dol = pot('DOWN', 'vrh', 160);
if (process.env.SAFEER_IZPIS) console.log(JSON.stringify({ dol: Object.assign({}, dol, { t: undefined, potek: dol.potek.slice(0, 12).join(' ') + ' ...' }) }, null, 1));
preveri('DOL z vrha: izbira nikoli ni izgubljena', dol.izgub === 0, { izgub: dol.izgub, prvaIzgubaPri: dol.prvaIzgubaPri });
preveri('DOL z vrha: kazalec je vedno v pogledu', dol.nevidnih === 0, { nevidnih: dol.nevidnih });
console.log('tv_gor_na_vrh_test: OK (' + stevilo + ')');
