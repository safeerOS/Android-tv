#!/usr/bin/env node
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const VIR = fs.readFileSync(path.join(__dirname, '..', 'assets', 'tv_spatial.js'), 'utf8');

function extractFuncs() {
    let code = '';
    code += 'window = { innerWidth: 1920, innerHeight: 1080 };\n';
    code += 'document = { body: { nodeType: 1, getBoundingClientRect: ()=>({width:1920,height:1080,top:0,left:0,right:1920,bottom:1080}) }, documentElement: { nodeType: 1 } };\n';
    code += 'function isCarouselArrow() { return false; }\n';
    code += 'function jeNogaAliPiskotki() { return false; }\n';
    code += 'function jePotVZivo() { return false; }\n';
    code += 'function jeSirokGumb() { return false; }\n';
    let m = VIR.match(/function isActionable[\s\S]*?return false;\n            \}/);
    if (m) code += m[0];
    code += '\nreturn isActionable;';
    return new Function(code)();
}
const isActionable = extractFuncs();

let n = 0;
function preveri(opis, pric, dob) { assert.strictEqual(dob, pric, opis); n++; console.log('  OK   ' + opis); }

function createMocks() {
    const docBody = { nodeType: 1, tagName: 'BODY', hasAttribute: ()=>false, getBoundingClientRect: () => ({width: 1920, height: 1080, right: 1920, left: 0, bottom: 1080, top: 0}) };
    
    // a element (the inner link we want to focus)
    const a = {
        nodeType: 1, tagName: 'A', className: '',
        getAttribute: (k) => k === 'href' ? '/slovenija' : null,
        hasAttribute: () => false,
        getBoundingClientRect: () => ({width: 100, height: 20, right: 100, left: 0, bottom: 20, top: 0}),
        innerText: 'Slovenija', textContent: 'Slovenija'
    };
    
    // a2 element (hidden inside submenu)
    const a2 = {
        nodeType: 1, tagName: 'A', className: '',
        getAttribute: (k) => k === 'href' ? '/slovenija' : null,
        hasAttribute: () => false,
        getBoundingClientRect: () => ({width: 100, height: 20, right: 100, left: 0, bottom: 20, top: 0}),
        innerText: 'Slovenija', textContent: 'Slovenija'
    };

    // submenu UL (d-lg-none hides it on TV)
    const ul = {
        nodeType: 1, tagName: 'UL', className: 'd-lg-none',
        getAttribute: () => null, hasAttribute: () => false,
        getBoundingClientRect: () => ({width: 0, height: 0, right: 0, left: 0, bottom: 0, top: 0}),
    };
    a2.parentElement = ul;
    
    // LI (the menuitem wrapper)
    const li = {
        nodeType: 1, tagName: 'LI', className: 'has-subcategories',
        getAttribute: (k) => k==='role'?'menuitem':'0', // tabindex 0
        hasAttribute: (k) => k==='tabindex',
        getBoundingClientRect: () => ({width: 120, height: 40, right: 120, left: 0, bottom: 40, top: 0}),
        innerText: 'Slovenija Slovenija',
        textContent: 'Slovenija Slovenija',
        querySelectorAll: (sel) => {
            if (sel === 'a[href]') return [a, a2];
            return [];
        }
    };
    
    a.parentElement = li;
    ul.parentElement = li;
    li.parentElement = docBody;
    
    window.getComputedStyle = (node) => {
        if (node === ul) return { display: 'none', visibility: 'hidden', opacity: '0' };
        if (node === a2) return { display: 'none', visibility: 'hidden', opacity: '0' }; // a2 is inside hidden ul
        return { display: 'block', visibility: 'visible', opacity: '1' };
    };
    
    return { li, a, a2, ul };
}

const mocks = createMocks();
preveri('skriti element v podmeniju NI cilj', false, isActionable(mocks.a2));
preveri('povezava a je veljaven cilj', true, isActionable(mocks.a));
preveri('li z role=menuitem je IZLOCEN, ker ima tocno eno vidno povezavo z istim imenom', false, isActionable(mocks.li));

// Pregled 1. 11. 2026: seznam (<ul>) POD robom zaslona ni skrit - do njega pride daljinec s premikom navzdol.
(function () {
    const body = { nodeType: 1, tagName: 'BODY', hasAttribute: () => false, getBoundingClientRect: () => ({width: 1920, height: 1080, right: 1920, left: 0, bottom: 1080, top: 0}) };
    const ulSpodaj = { nodeType: 1, tagName: 'UL', className: 'clanki', getAttribute: () => null, hasAttribute: () => false,
        getBoundingClientRect: () => ({width: 1200, height: 600, right: 1300, left: 100, bottom: 2000, top: 1400}) };
    const clanek = { nodeType: 1, tagName: 'A', className: '', getAttribute: (k) => k === 'href' ? '/clanek' : null, hasAttribute: () => false,
        getBoundingClientRect: () => ({width: 400, height: 200, right: 500, left: 100, bottom: 1600, top: 1400}), innerText: 'Clanek', textContent: 'Clanek' };
    clanek.parentElement = ulSpodaj; ulSpodaj.parentElement = body;
    window.getComputedStyle = () => ({ display: 'block', visibility: 'visible', opacity: '1' });
    preveri('povezava v seznamu pod robom zaslona JE cilj (premik navzdol)', true, isActionable(clanek));
})();

// Lastnik 1. 11. 2026: »Televizija« (povezava) + gumb s puscico (aria-controls) sta en cilj.
(function () {
    const body = { nodeType: 1, tagName: 'BODY', hasAttribute: () => false, getBoundingClientRect: () => ({width: 1920, height: 1080, right: 1920, left: 0, bottom: 1080, top: 0}) };
    const li = { nodeType: 1, tagName: 'LI', className: '', getAttribute: () => null, hasAttribute: () => false,
        getBoundingClientRect: () => ({width: 160, height: 40, right: 360, left: 200, bottom: 40, top: 0}) };
    const povezava = { nodeType: 1, tagName: 'A', className: '', getAttribute: (k) => k === 'href' ? '/tv' : null, hasAttribute: () => false,
        getBoundingClientRect: () => ({width: 110, height: 30, right: 310, left: 200, bottom: 35, top: 5}), innerText: 'Televizija', textContent: 'Televizija' };
    const gumb = { nodeType: 1, tagName: 'BUTTON', className: '', previousElementSibling: povezava,
        getAttribute: (k) => ({ 'aria-controls': 'tbdropdown-televizija', 'aria-expanded': 'false', type: 'button' })[k] || null,
        hasAttribute: (k) => k === 'aria-controls' || k === 'aria-expanded' || k === 'type',
        getBoundingClientRect: () => ({width: 24, height: 24, right: 340, left: 316, bottom: 32, top: 8}), innerText: '', textContent: '' };
    povezava.parentElement = li; gumb.parentElement = li; li.parentElement = body;
    window.getComputedStyle = () => ({ display: 'block', visibility: 'visible', opacity: '1' });
    preveri('povezava »Televizija« je cilj', true, isActionable(povezava));
    preveri('gumb s puscico za podmeni poleg povezave NI locen cilj', false, isActionable(gumb));
})();

console.log('tv_meni_test: OK (' + n + ')');
