        /* 📺 Safeer TV Remote D-Pad Navigation & Cinema OSD Engine */
'use strict';
        (function() {
            'use strict';
            // Neodvisni pregled 5. 11. 2026 (najdba 1): v ohlapnem nacinu je stran prek f.caller.caller (ali
            // Error.prepareStackTrace + CallSite.getFunction) iz prepisane metode (npr. String.prototype.indexOf spodaj)
            // dobila to funkcijo in z izvirnim toString vir z zetonom. Strogi nacin skrije klicatelje in funkcije okvirjev.
            // Tretji neodvisni pregled 5. 11. 2026 (N7): String.prototype.indexOf lahko prepise stran in tako prepreci
            // namestitev daljinca - iskanje po znakih (location.href je nespremenljiv, [LegacyUnforgeable]).
            function vsebujeNiz(v, p) {
                if (typeof v !== 'string') return false;
                for (var i = 0; i + p.length <= v.length; i++) {
                    var j = 0;
                    while (j < p.length && v[i + j] === p[j]) j++;
                    if (j === p.length) return true;
                }
                return false;
            }
            var naslovDokumenta = '';
            try { naslovDokumenta = location.href; } catch (_) {}
            if (vsebujeNiz(naslovDokumenta, 'youtube.com/tv')) return;

            /* SAFEER_ZETON_DOTIKA_ZACETEK */
            // Pregled Gemini 3. 11. 2026: SafeerBridge vidi vsak okvir strani; pravi dotik zato zahteva zeton, ki ga ima samo
            // ta skript glavnega okvirja (aplikacija ga vstavi namesto mesta spodaj). Zeton je samo v zaprtju, nikoli na window.
            // Most vzamemo ob zagonu, da ga stran pozneje ne podtakne; ob zacetku dokumenta ga aplikacija zaklene
            // (ChromiumEngineView.ZAKLEP_MOSTU_JS). Pregled 3. 11. 2026: brez poznega ponovnega branja window.SafeerBridge.
            var ZETON_DOTIKA = '__SAFEER_ZETON_DOTIKA__';
            // Tretji neodvisni pregled 5. 11. 2026 (N5): Object.freeze na mostu Java (ZAKLEP_MOSTU_JS) ni dokazan - prej smo
            // triggerNativeTap prebrali ob vsakem klicu iz window.SafeerBridge in stran ga je lahko ovila (zeton v njen ovoj).
            // Most in metodo ujame ChromiumEngineView.IZVIRNIKI_JS ob zacetku dokumenta (IZ.most, IZ.tap); klic z Reflect.apply.
            // Neodvisni pregled 5. 11. 2026 (najdba 8) je uvedel mejo 5 napacnih dokazov (32-bitni odtis bi stran ugibala).
            // Cetrti slepi pregled 5. 11. 2026 (N-D, odlocitev vodje): dokaz je HMAC-SHA256 (256 bitov), ugibanje ni mogoce -
            // meja je bila le se samo-DoS strani (5 laznih klicev je izklopilo prave dotike in Enter). Meja odstranjena.
            // Prava dejanja (zaupanja vreden dotik ali tipka Enter) samo z izvirniki z zacetka dokumenta, v dokumentu z omrezja
            // in dokler stran ni zasedla nasega izvoza.
            function pravaDejanjaDovoljena() {
                return !!IZ && omrezniDokument && !izvozZaseden;
            }
            function pravDotik(x, y) {
                try {
                    // Pregled Groka 5. 11. 2026: brez izvirnikov (IZ) tocke ne moremo preveriti; ce je stran zasedla nas izvoz, je
                    // sovrazna - pravega dotika ni. Po klicu (dotik tece asinhrono na niti UI) klicatelj ne klice vec kode strani.
                    if (!pravaDejanjaDovoljena()) return false;
                    if (!IZ || typeof IZ.tap !== 'function' || !IZ.most) return false;
                    return IZ.apply(IZ.tap, IZ.most, [x, y, ZETON_DOTIKA]) === true;
                } catch (_) { return false; }
            }
            /* SAFEER_ZETON_DOTIKA_KONEC */

            /* SAFEER_IZVIRNIKI_ZACETEK */
            // Pregled Groka 5. 11. 2026 (najdbi 1 in 10): skript tece v svetu strani (evaluateJavascript; Android WebView nima
            // locenih svetov) in sele pri 35 % nalaganja. Stran je prej lahko prepisala getBoundingClientRect, elementFromPoint,
            // contains ali Date.now in tako izbrala tocko pravega dotika. Izvirnike shrani ChromiumEngineView.IZVIRNIKI_JS ob
            // zacetku dokumenta (pred skriptami strani) v nespremenljivo lastnost; aplikacija vstavi 'da' samo, ce je ta skript
            // registrirala (sicer bi lastnost lahko ponaredila stran). Brez izvirnikov ni pravega dotika, ostane sinteticni klik.
            var IZVIRNIKI_ZAGOTOVLJENI = '__SAFEER_IZVIRNIKI__' === 'da';
            // Neodvisni pregled 5. 11. 2026 (najdba 7): ime lastnosti ima nakljucni del pogleda (ChromiumEngineView), zato ga
            // stran ne more vnaprej zasesti. Izvirniki veljajo samo v dokumentu z omrezja (https/http): v about:blank, data:
            // in blob: skript zacetka dokumenta ni zagotovljen, ime pa bi stran lahko poznala iz prejsnjega dokumenta pogleda.
            // location.protocol je nespremenljiv ([LegacyUnforgeable]), stran ga ne prepise.
            var IME_IZVIRNIKOV = '__safeer_izvirniki_' + '__SAFEER_NONCE_IZVIRNIKOV__';
            var protokolDokumenta = '';
            try { protokolDokumenta = location.protocol; } catch (_) {}
            var omrezniDokument = protokolDokumenta === 'https:' || protokolDokumenta === 'http:';
            var IZ = null;
            try {
                var kIz = window[IME_IZVIRNIKOV];
                if (IZVIRNIKI_ZAGOTOVLJENI && omrezniDokument && kIz && typeof kIz.apply === 'function' && typeof kIz.gbcr === 'function' &&
                    typeof kIz.efp === 'function' && typeof kIz.contains === 'function' && typeof kIz.tag === 'function' &&
                    typeof kIz.rl === 'function' && typeof kIz.rt === 'function' && typeof kIz.rw === 'function' &&
                    typeof kIz.rh === 'function' && typeof kIz.dp === 'function' && typeof kIz.gopd === 'function') IZ = kIz;
            } catch (_) {}
            // Najdba 5: izvoze in zastavico smo definirali z Object.defineProperty strani - stran ga je prepisala in namesto
            // nase funkcije zapisala ovoj, ki je belezil izzive in dokaze. Izvirnika z zacetka dokumenta; opisnik brez
            // prototipa (lastnost get/set na Object.prototype ga ne spremeni).
            function definiraj(o, k, opis) {
                if (IZ) return IZ.apply(IZ.dp, null, [o, k, opis]);
                return Object.defineProperty(o, k, opis);
            }
            function opisLastnosti(o, k) {
                if (IZ) return IZ.apply(IZ.gopd, null, [o, k]);
                return Object.getOwnPropertyDescriptor(o, k);
            }
            var datumZdaj = Date.now;
            function casZdaj() {
                if (IZ && typeof IZ.now === 'function') { try { return IZ.apply(IZ.now, IZ.perf, []); } catch (_) {} }
                try { return datumZdaj(); } catch (_) { return 0; }
            }
            function okvirIz(el) {
                if (!IZ || !el) return null;
                try {
                    var r = IZ.apply(IZ.gbcr, el, []);
                    var l = IZ.apply(IZ.rl, r, []), t = IZ.apply(IZ.rt, r, []), w = IZ.apply(IZ.rw, r, []), h = IZ.apply(IZ.rh, r, []);
                    if (typeof l !== 'number' || typeof t !== 'number' || typeof w !== 'number' || typeof h !== 'number') return null;
                    return { left: l, top: t, width: w, height: h, right: l + w, bottom: t + h };
                } catch (_) { return null; }
            }
            function zadetekIz(x, y) { try { return IZ ? IZ.apply(IZ.efp, document, [x, y]) : null; } catch (_) { return null; } }
            function vsebujeIz(a, b) {
                if (!a || !b) return false;
                if (a === b) return true;
                try { return IZ.apply(IZ.contains, a, [b]) === true; } catch (_) { return false; }
            }
            function oznakaIz(el) { try { var t = IZ.apply(IZ.tag, el, []); return typeof t === 'string' ? t : ''; } catch (_) { return ''; } }
            function virOkvirjaIz(el) {
                try { var v = (IZ && typeof IZ.src === 'function') ? IZ.apply(IZ.src, el, []) : ''; return typeof v === 'string' ? v : ''; }
                catch (_) { return ''; }
            }
            // Polje za datoteko z izvirniki (najdba 5 in odlocitev B): oznaka in vrsta polja iz getterjev z zacetka dokumenta.
            function jeVhodDatotekeIz(el) {
                if (!IZ || !el || typeof IZ.typ !== 'function') return false;
                var oz = oznakaIz(el), v = el;
                if (oz === 'LABEL') {
                    if (typeof IZ.ctl !== 'function') return false;
                    try { v = IZ.apply(IZ.ctl, el, []); } catch (_) { return false; }
                    if (!v || v === el) return false;
                    oz = oznakaIz(v);
                }
                if (oz !== 'INPUT') return false;
                try { return IZ.apply(IZ.typ, v, []) === 'file'; } catch (_) { return false; }
            }
            function fokusIz(el) {
                try { if (IZ && typeof IZ.fokus === 'function') IZ.apply(IZ.fokus, el, [{ __proto__: null, preventScroll: true }]); } catch (_) {}
            }
            function aktivenIz() {
                try { return (IZ && typeof IZ.akt === 'function') ? IZ.apply(IZ.akt, document, []) : null; } catch (_) { return null; }
            }
            /* SAFEER_IZVIRNIKI_KONEC */

            /* SAFEER_ZNAK_NAMESTITVE_ZACETEK */
            // Pregled fokusa 5. 11. 2026: zastavica namestitve je nespremenljiva lastnost z znakom, izpeljanim iz zetona strani
            // (zeton sam ne pride na window). Pregled Groka 5. 11. 2026 (najdba 2): znak je prej racunal z.charCodeAt(i) in
            // Math.imul na samem zetonu - prepis teh funkcij v strani je videl zeton. Zeton je sestnajstiski
            // (SpletVarnostPravila.novZeton): znake beremo z indeksom niza in tabelo, racunamo z operatorji (HMAC-SHA256
            // v SAFEER_HMAC, isti kot TvTipke.odtisHex) - nobena funkcija, ki jo doseze stran, zetona ne vidi.
            // Neodvisni pregled 5. 11. 2026 (najdba 8): tabela brez prototipa - lastnost na Object.prototype ne doda znaka.
            var KODE_ZNAKOV = { __proto__: null, '0': 48, '1': 49, '2': 50, '3': 51, '4': 52, '5': 53, '6': 54, '7': 55, '8': 56, '9': 57, 'a': 97, 'b': 98, 'c': 99, 'd': 100, 'e': 101, 'f': 102, 'g': 103, 'h': 104, 'i': 105, 'j': 106, 'k': 107, 'l': 108, 'm': 109, 'n': 110, 'o': 111, 'p': 112, 'q': 113, 'r': 114, 's': 115, 't': 116, 'u': 117, 'v': 118, 'w': 119, 'x': 120, 'y': 121, 'z': 122, '|': 124 };
            // Neodvisni pregled 5. 11. 2026 (najdba 6): stran je vnaprej zasedla _safeer_tv_remote_installed in
            // _safeer_navigate_spatial - daljinec se ni namestil. Imena izvozov in zastavice imajo nakljucni del dokumenta
            // (ChromiumEngineView.nonceIzvozov, nov ob vsaki strani), ki ga stran pred nasim skriptom ne pozna. Zastavica ne
            // nosi vec odtisa zetona (prej znak namestitve na window).
            var NONCE_IZVOZOV = '__SAFEER_NONCE__';
            function imeIzvoza(ime) { return ime + '_' + NONCE_IZVOZOV; }
            var IME_ZASTAVICE = imeIzvoza('_safeer_tv_remote_installed');
            function jeNamescen(w, ime) {
                var d = null;
                try { d = opisLastnosti(w, ime || IME_ZASTAVICE); } catch (_) {}
                return !!d && d.value === true && d.configurable === false;
            }
            // Tretji neodvisni pregled 5. 11. 2026 (N8): onPageStarted brez novega dokumenta (prenos, odgovor 204) da nov nonce
            // strani - prej se je v isti dokument namestila druga kopija skripta. Aplikacija vstavi nonce prejsnje strani
            // (ChromiumEngineView.nonceIzvozovPrej); ce je v tem dokumentu ze nasa zastavica s tem imenom (izvirni
            // getOwnPropertyDescriptor), skript ostane en, aplikacija pa se vrne na zeton in nonce zive kopije
            // (UserScriptManager.injectOnPageFinished). Neveljaven ali nezamenjan nonce: preverbe ni.
            var NONCE_PREJ = '__SAFEER_PREJ_NONCE__';
            function jeNonceNiz(v) {
                if (typeof v !== 'string' || v.length < 16 || v.length > 64) return false;
                for (var i = 0; i < v.length; i++) {
                    var c = KODE_ZNAKOV[v[i]];
                    if (typeof c !== 'number' || !((c >= 48 && c <= 57) || (c >= 97 && c <= 102))) return false;
                }
                return true;
            }
            // Peti slepi pregled 5. 11. 2026 (N5): ime izvirnikov se menja ob vsaki strani - ziva kopija v ISTEM dokumentu (onPageStarted
            // brez novega dokumenta) ima izvirnike pod imenom prejsnje strani. Samo za to preverbo; nikoli za prava dejanja.
            // Lazna zastavica z laznimi izvirniki prejsnjega imena v novem dokumentu se namestitev le zadrzi, aplikacija pa kopijo
            // preveri z izzivom (_safeer_ziv, HMAC) in skript namesti znova brez prejsnjega imena.
            var NONCE_IZ_PREJ = '__SAFEER_NONCE_IZ_PREJ__';
            var IZ_PREJ = null;
            try {
                var kIzP = jeNonceNiz(NONCE_IZ_PREJ) ? window['__safeer_izvirniki_' + NONCE_IZ_PREJ] : null;
                if (kIzP && typeof kIzP.apply === 'function' && typeof kIzP.gopd === 'function') IZ_PREJ = kIzP;
            } catch (_) {}
            function zivaPrejsnjaKopija(w) {
                if (!jeNonceNiz(NONCE_PREJ) || NONCE_PREJ === NONCE_IZVOZOV) return false;
                var I2 = IZ_PREJ || IZ;
                if (!I2) return false;
                var d = null;
                try { d = I2.apply(I2.gopd, null, [w, '_safeer_tv_remote_installed_' + NONCE_PREJ]); } catch (_) {}
                return !!d && d.value === true && d.configurable === false;
            }
            function oznaciNamescen(w) {
                try {
                    definiraj(w, IME_ZASTAVICE, { __proto__: null, value: true, writable: false, configurable: false, enumerable: false });
                    return true;
                } catch (_) { return false; }
            }
            /* SAFEER_ZNAK_NAMESTITVE_KONEC */
            /* SAFEER_DOKAZ_ZACETEK */
            // Drugi krog pregleda 5. 11. 2026: odgovor 'datoteka' sprozi pravo tipko Enter. Aplikacija ob vsakem OK poslje
            // nakljucen izziv in sprejme samo 'datoteka:' + odtis zetona in izziva (TvTipke.okNaDatoteki). Zeton je na obeh koncih.
            function jeVeljavenIzziv(iz) {
                if (typeof iz !== 'string' || iz.length < 16 || iz.length > 64) return false;
                for (var i = 0; i < iz.length; i++) {
                    var c = KODE_ZNAKOV[iz[i]];
                    if (typeof c !== 'number' || !((c >= 48 && c <= 57) || (c >= 97 && c <= 102))) return false;
                }
                return true;
            }
            /* SAFEER_HMAC_ZACETEK */
            // Cetrti slepi pregled 5. 11. 2026 (N-B, N-D; odlocitev vodje 3): dokaz je HMAC-SHA256 z zetonom strani kot kljucem
            // (TvTipke.odtisHex, javax.crypto) namesto 32-bitnega FNV-1a, ki ni MAC (stran je odtise zbirala kot orakelj).
            // Lastna izvedba SHA-256 (FIPS 180-4) in HMAC (RFC 2104): samo operatorji, nizi z indeksom in tabele brez
            // prototipa ali z vsemi indeksi ze ob nastanku - nobena metoda ali nastavljalec, ki ga lahko prepise stran
            // (push, charCodeAt, Math.imul, toString, Array.prototype[i]). Znaki samo iz KODE_ZNAKOV (zeton, izziv, stevec in
            // nasi nizi so iz 0-9, a-z, '|'); drug znak: ''. Vektorji: tests/tv_hmac_test.js in tests/TvTipkeTest.kt.
            var K_SHA256 = [0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
                0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
                0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
                0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
                0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
                0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
                0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
                0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2];
            var HEKS = '0123456789abcdef';
            // SHA-256 bajtov B[0..n-1] (objekt brez prototipa); vrne 32 bajtov v objektu brez prototipa.
            function sha256Bajti(B, n) {
                'use strict';
                var H = { __proto__: null, 0: 0x6a09e667, 1: 0xbb67ae85, 2: 0x3c6ef372, 3: 0xa54ff53a, 4: 0x510e527f, 5: 0x9b05688c, 6: 0x1f83d9ab, 7: 0x5be0cd19 };
                var W = { __proto__: null };
                var vse = n + 9, bloki = (vse + 63 - (vse + 63) % 64) / 64, skupaj = bloki * 64, bitov = n * 8;
                for (var k = 0; k < bloki; k++) {
                    for (var t = 0; t < 16; t++) {
                        var w = 0;
                        for (var j = 0; j < 4; j++) {
                            var i = k * 64 + t * 4 + j, x = 0;
                            if (i < n) x = B[i];
                            else if (i === n) x = 0x80;
                            else if (i >= skupaj - 4) x = (bitov >>> (8 * (skupaj - 1 - i))) & 255;
                            w = ((w << 8) | x) | 0;
                        }
                        W[t] = w;
                    }
                    for (t = 16; t < 64; t++) {
                        var p15 = W[t - 15], p2 = W[t - 2];
                        var s0 = ((p15 >>> 7) | (p15 << 25)) ^ ((p15 >>> 18) | (p15 << 14)) ^ (p15 >>> 3);
                        var s1 = ((p2 >>> 17) | (p2 << 15)) ^ ((p2 >>> 19) | (p2 << 13)) ^ (p2 >>> 10);
                        W[t] = (W[t - 16] + s0 + W[t - 7] + s1) | 0;
                    }
                    var a = H[0], b = H[1], c = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
                    for (t = 0; t < 64; t++) {
                        var S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
                        var ch = (e & f) ^ (~e & g);
                        var t1 = (h + S1 + ch + K_SHA256[t] + W[t]) | 0;
                        var S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
                        var mj = (a & b) ^ (a & c) ^ (b & c);
                        var t2 = (S0 + mj) | 0;
                        h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
                    }
                    H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + c) | 0; H[3] = (H[3] + d) | 0;
                    H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0;
                }
                var D = { __proto__: null };
                for (var q = 0; q < 8; q++) {
                    D[q * 4] = (H[q] >>> 24) & 255; D[q * 4 + 1] = (H[q] >>> 16) & 255;
                    D[q * 4 + 2] = (H[q] >>> 8) & 255; D[q * 4 + 3] = H[q] & 255;
                }
                return D;
            }
            // HMAC-SHA256 (RFC 2104); kljuc najvec 64 bajtov (nas zeton jih ima 48), sicer null.
            function hmacBajti(K, kn, M, mn) {
                'use strict';
                if (kn > 64) return null;
                var N = { __proto__: null }, Z = { __proto__: null }, i;
                for (i = 0; i < 64; i++) { var kb = i < kn ? K[i] : 0; N[i] = kb ^ 0x36; Z[i] = kb ^ 0x5c; }
                for (i = 0; i < mn; i++) N[64 + i] = M[i];
                var notr = sha256Bajti(N, 64 + mn);
                for (i = 0; i < 32; i++) Z[64 + i] = notr[i];
                return sha256Bajti(Z, 96);
            }
            function bajtiNiza(v, O) {
                'use strict';
                if (typeof v !== 'string') return -1;
                for (var i = 0; i < v.length; i++) {
                    var c = KODE_ZNAKOV[v[i]];
                    if (typeof c !== 'number') return -1;
                    O[i] = c;
                }
                return v.length;
            }
            // 64 sestnajstiskih znakov ali '' (kljuc krajsi od 32 znakov ali znak zunaj KODE_ZNAKOV).
            function odtisHex(zeton, niz) {
                'use strict';
                var K = { __proto__: null }, M = { __proto__: null };
                var kn = bajtiNiza(zeton, K), mn = bajtiNiza(niz, M);
                if (kn < 32 || kn > 64 || mn < 0) return '';
                var D = hmacBajti(K, kn, M, mn);
                if (!D) return '';
                var v = '';
                for (var i = 0; i < 32; i++) v = v + HEKS[D[i] >>> 4] + HEKS[D[i] & 15];
                return v;
            }
            /* SAFEER_HMAC_KONEC */
            function odtisZetona(zeton, niz) {
                var h = odtisHex(zeton, niz);
                return h === '' ? '' : 'safeer-' + h;
            }
            function dokazDatoteke(zeton, izziv) {
                if (!jeVeljavenIzziv(izziv)) return '';
                var o = odtisZetona(zeton, 'datoteka|' + izziv);
                return o ? 'datoteka:' + o : '';
            }
            /* SAFEER_DOKAZ_KONEC */
            if (jeNamescen(window)) return;
            if (zivaPrejsnjaKopija(window)) return;
            // Ime zastavice pozna samo ta skript: ce ga izvirni defineProperty ne more zasesti, je skript ze namescen.
            if (!oznaciNamescen(window) && IZ) return;

            // Pregled fokusa 5. 11. 2026: stran je lahko prepisala window._safeer_click_focused_card ali _safeer_highlight
            // (aplikacija ju klice ob OK) - npr. odgovor 'datoteka' je sprozil pravo tipko Enter. Funkcije za aplikacijo
            // so nespremenljive lastnosti.
            var izvozZaseden = false;
            // javno = ime brez nakljucnega dela (klice ga site_agent.js); tak izvoz ne doseze pravih dejanj.
            function izvoziZaAplikacijo(ime, fn, javno) {
                var polno = javno ? ime : imeIzvoza(ime);
                // Neodvisni pregled 5. 11. 2026 (najdbi 4 in 9): stran izvoz doseze tudi med nasim odzivom na tipko (ponovni
                // vstop iz prepisane metode ali iz poslusalca klika). Izbira, ki jo naredi tak klic, ni potrjena; izvozi s
                // tipko (smer, OK) jo potrdijo sami, sele po dokazu tipke. Brez apply/call, ki ju lahko prepise stran.
                var ovoj = function (a, b, c) {
                    var bilo = vTipki;
                    vTipki = false;
                    try { return fn(a, b, c); } finally { vTipki = bilo; }
                };
                try {
                    definiraj(window, polno, { __proto__: null, value: ovoj, writable: false, configurable: false, enumerable: false });
                    return true;
                } catch (_) {}
                // Drugi krog pregleda 5. 11. 2026: stran je ime zasedla pred nami - aplikacija bo klicala njeno funkcijo.
                // Zapis v dnevnik (brez podatkov strani); odgovoru za pravo tipko aplikacija brez dokaza ne zaupa (SAFEER_DOKAZ).
                try { console.log('H55 tv_spatial.js:izvoz izvoz ni uspel {"ime":"' + ime + '"}'); } catch (_) {}
                if (!javno) izvozZaseden = true;
                try { window[polno] = ovoj; } catch (_) {}
                return false;
            }

            /* SAFEER_SAMODEJNO_ZACETEK */
            // Lastnik 5. 11. 2026 04:52 (video, f-droid.org): stran se je sama pomikala in izbira je skakala med povezavami,
            // daljinec pa je v 80 s poslal 2 tipki. Izbrani element se brez uporabnikove tipke ne zamenja: fokus, ki ga premakne
            // stran, izbiro zamenja samo, ce je se ni, ali kratko po tipki (izjema: fokus na vsebniku odprtega okna - vstop v
            // okno, vedno isti cilj). Vstop v novo okno (pasica piskotkov) ostane - najvec
            // enkrat na naslov strani (vstopNaNaslovu), zato ne more skakati.
            var SAMODEJNO_PO_TIPKI_MS = 3000;
            function smeSamodejno(imaIzbiro, odTipkeMs) {
                return !imaIzbiro || (odTipkeMs >= 0 && odTipkeMs < SAMODEJNO_PO_TIPKI_MS);
            }
            /* SAFEER_SAMODEJNO_KONEC */
            var zadnjaTipkaMs = 0;
            /* SAFEER_TIPKA_ZACETEK */
            // Pregled Groka 5. 11. 2026 (najdbe 3, 7, 10): _safeer_navigate_spatial, _safeer_click_focused_card in _safeer_highlight
            // doseze tudi stran - z njimi je premikala izbiro in nastavila cas »zadnje tipke« (izbira je nato skakala sama).
            // Aplikacija ob vsaki tipki daljinca v istem skriptu poslje nakljucen izziv in odtis zetona (TvTipke.ukazTipke);
            // brez veljavnega dokaza smer, OK in izbira ne naredijo nicesar. En dokaz = en klic. Primerjava stevil (brez
            // toString), zato prepis metod v strani dokaza ne vidi.
            // Neodvisni pregled 5. 11. 2026 (najdba 5): prej je veljal vsak par razen zadnjega - starejsi ujeti par je spet
            // dal tipko. Zdaj dokaz pokrije strogo narascajoc stevec aplikacije (ChromiumEngineView.naslednjaTipka).
            var tipkaNaVoljo = false, zadnjiStevecTipke = 0, vTipki = false, izbiraPotrjena = false;
            function jeStevecTipke(n) { return typeof n === 'number' && n > 0 && n <= 9007199254740991 && n % 1 === 0; }
            // Tretji neodvisni pregled 5. 11. 2026 (N7): ime izvoza vidi stran (Object.getOwnPropertyNames) - 5 klicev brez
            // argumentov je izklopilo prava dejanja. Napaka steje samo pri klicu z veljavnim izzivom, narascajocim stevcem in
            // stevilom za dokaz (samo tak klic je ugibanje dokaza); drugi klici so zavrnjeni brez stetja.
            // Cetrti slepi pregled 5. 11. 2026: dokaz je niz HMAC (64 znakov, TvTipke.odtisHex); primerjava nizov z operatorjem.
            function sprejmiTipko(izziv, stevec, dokaz) {
                if (!jeVeljavenIzziv(izziv) || !jeStevecTipke(stevec) || stevec <= zadnjiStevecTipke || typeof dokaz !== 'string' ||
                    dokaz.length !== 64) {
                    return false;
                }
                // Stevilo v niz z operatorjem (notranji ToString), ne z Number.prototype.toString strani.
                var h = odtisHex(ZETON_DOTIKA, 'tipka|' + stevec + '|' + izziv);
                if (h === '' || dokaz !== h) return false;
                zadnjiStevecTipke = stevec;
                tipkaNaVoljo = true;
                zadnjaTipkaMs = casZdaj();
                return true;
            }
            function porabiTipko() { var t = tipkaNaVoljo; tipkaNaVoljo = false; return t; }
            // Izbira, ki je ni naredil daljinec (ploscica iz klica strani, vrnitev po predvajalniku): nikoli potrjena.
            function oznaciBrezPotrditve(el) {
                var bilo = vTipki;
                vTipki = false;
                try { return highlightElement(el); } finally { vTipki = bilo; }
            }
            /* SAFEER_TIPKA_KONEC */
            izvoziZaAplikacijo('_safeer_tipka', sprejmiTipko);
            // Cetrti slepi pregled 5. 11. 2026 (N-B): aplikacija zivo kopijo s prejsnjim nonce sprejme samo, ce ta na nakljucen
            // izziv vrne 'ziv:' + odtis SVOJEGA zetona (TvTipke.zivaKopijaVelja) - zastavico z znanim imenom lahko postavi nova
            // stran, zetona prejsnje strani pa ne pozna. Odgovor je samo za ta niz ('ziv|' + izziv), ne za druge dokaze.
            izvoziZaAplikacijo('_safeer_ziv', function (izziv) {
                if (!jeVeljavenIzziv(izziv)) return '';
                var o = odtisZetona(ZETON_DOTIKA, 'ziv|' + izziv);
                return o ? 'ziv:' + o : '';
            });
            // Pregled fokusa 5. 11. 2026: stran je razred safeer-active-card lahko dala svojemu (tudi nevidnemu) okvirju in OK je
            // nanj sprozil pravi dotik. Izbrani element je zdaj samo tisti, ki ga je izbral daljinec (zaprtje); razred je le slog.
            var izbraniEl = null;
            // Zadnja ploscica v zivo, ki jo je izbral ta skript (site_agent.js jo vrne po zaprtju predvajalnika).
            var zadnjaPloscica = null;
            function izbrani() {
                var e = izbraniEl;
                try { if (e && e.isConnected && e.classList.contains('safeer-active-card')) return e; } catch (_) {}
                return null;
            }
            function nastaviIzbrani(el) {
                izbraniEl = el || null;
                // Potrjena = izbral jo je daljinec (klic aplikacije z dokazom tipke); samo na potrjeni sme OK sproziti pravi dotik.
                izbiraPotrjena = !!el && vTipki;
                // Obris :focus strani rise samo, ko daljinec nima izbire - sicer bi bila dva okvirja (pregled fokusa 5. 11. 2026).
                try { document.documentElement.classList[el ? 'add' : 'remove']('safeer-izbira'); } catch (_) {}
            }
            function samodejnoDovoljeno(vir) {
                var ima = !!izbrani();
                var od = zadnjaTipkaMs > 0 ? casZdaj() - zadnjaTipkaMs : -1;
                if (smeSamodejno(ima, od)) return true;
                try {
                    window._safeerDbgSamN = (window._safeerDbgSamN || 0) + 1;
                    if (window._safeerDbgSamN <= 20) window._safeerDbg('H53', 'tv_spatial.js:samodejno', 'brez tipke prezrto', { vir: vir, od: od });
                } catch (_) {}
                return false;
            }

            // #region agent log
            window._safeerDbg = function(h, loc, msg, podatki) {
                try {
                    var rep = '';
                    if (podatki !== undefined && podatki !== null) {
                        try { rep = ' ' + JSON.stringify(podatki); } catch (_) { rep = ' ' + String(podatki); }
                    }
                    console.log(String(h || '') + ' ' + String(loc || '') + ' ' + String(msg || '') + rep);
                } catch (_) {}
            };
            window._safeerDbgRun = 'lean';
            // #endregion

            function isCarouselArrow(el) {
                if (!el) return false;
                var cls = ((el.className || '') + '').toLowerCase();
                if (cls.indexOf('owl-prev') !== -1 || cls.indexOf('owl-next') !== -1) return true;
                if (cls.indexOf('swiper-button') !== -1 || cls.indexOf('slick-arrow') !== -1) return true;
                if (cls.indexOf('carousel-control') !== -1 || cls.indexOf('slider-arrow') !== -1) return true;
                try {
                    if (el.closest && el.closest('.owl-nav, .swiper-button-prev, .swiper-button-next, .slick-arrow')) return true;
                } catch (_) {}
                return false;
            }
            window._safeer_medij_release_cdm = function () {
                var had = false;
                try {
                    var vids = document.querySelectorAll('video');
                    var i, m;
                    for (i = 0; i < vids.length; i++) {
                        m = vids[i];
                        try {
                            if (m.mediaKeys) {
                                had = true;
                                m.setMediaKeys(null);
                            }
                        } catch (_) {}
                        try { m.pause(); } catch (_) {}
                    }
                } catch (_) {}
                return had;
            };
            window._safeer_medij_live_program_tiles = function () {
                var nodes = document.querySelectorAll('.item.item--event, .item--event');
                var tiles = [];
                var i, el, cls;
                for (i = 0; i < nodes.length; i++) {
                    el = nodes[i];
                    cls = ((el.className || '') + '').toLowerCase();
                    if (cls.indexOf('channel-container') !== -1 && cls.indexOf('item--event') === -1) continue;
                    if (el.closest && el.closest('.menu, #csh__menu_bar, .menu-items-wrapper')) continue;
                    tiles.push(el);
                }
                tiles.sort(function (a, b) {
                    var ay = a.offsetTop, by = b.offsetTop;
                    try { ay = a.getBoundingClientRect().top + (window.scrollY || 0); } catch (_) {}
                    try { by = b.getBoundingClientRect().top + (window.scrollY || 0); } catch (_) {}
                    if (Math.abs(ay - by) > 48) return ay - by;
                    var ax = a.offsetLeft, bx = b.offsetLeft;
                    try { ax = a.getBoundingClientRect().left + (window.scrollX || 0); } catch (_) {}
                    try { bx = b.getBoundingClientRect().left + (window.scrollX || 0); } catch (_) {}
                    return ax - bx;
                });
                return tiles;
            };
            window._safeer_medij_tile_label = function (tile) {
                var name = '';
                try {
                    var img = tile && tile.querySelector && tile.querySelector('img[alt]');
                    if (img && img.alt) name = (img.alt + '').replace(/\s+/g, ' ').trim();
                } catch (_) {}
                if (!name && tile) name = ((tile.innerText || tile.textContent || '') + '').replace(/\s+/g, ' ').trim();
                return (name || '').slice(0, 36);
            };
            window._safeer_medij_click_live_tile = function (tile, idx) {
                if (!tile) return 0;
                window._safeer_medij_last_tile = tile;
                window._safeer_medij_zap_idx = idx;
                try { sessionStorage.removeItem('safeer_medij_autoplay'); } catch (_) {}
                window._safeer_medij_want_play = true;
                // Drugi krog pregleda 5. 11. 2026: funkcija je na window - ploscico lahko poda stran. Izbira (cilj pravega
                // dotika ob OK) samo na vidni ploscici, kot pri daljincu (jeUporaben).
                try { if (jeUporaben(tile, true)) { zadnjaPloscica = tile; oznaciBrezPotrditve(tile); } } catch (_) {}
                try { tile.click(); } catch (_) {}
                return window._safeer_medij_tile_label(tile) || 1;
            };
            window._safeer_medij_tune_channel = function (num) {
                try {
                    var tiles = window._safeer_medij_live_program_tiles();
                    var idx = (num | 0) - 1;
                    if (idx < 0 || idx >= tiles.length) {
                        try {
                            if (window._safeerDbg) window._safeerDbg('H340', 'tv_spatial.js:tune', 'no program', {
                                want: num,
                                n: tiles.length
                            });
                        } catch (_) {}
                        return '!' + tiles.length;
                    }
                    var name = window._safeer_medij_click_live_tile(tiles[idx], idx);
                    try {
                        if (window._safeerDbg) window._safeerDbg('H340', 'tv_spatial.js:tune', 'tune click', {
                            i: idx,
                            n: tiles.length,
                            t: name,
                            want: num
                        });
                    } catch (_) {}
                    return name || 1;
                } catch (e) {
                    return 0;
                }
            };
            window._safeer_medij_zap_channel = function (delta) {
                try {
                    var step = delta > 0 ? 1 : -1;
                    var tiles = window._safeer_medij_live_program_tiles();
                    if (!tiles.length) return 0;
                    var cur = window._safeer_medij_last_tile;
                    var idx = -1;
                    var i;
                    if (typeof window._safeer_medij_zap_idx === 'number' &&
                        window._safeer_medij_zap_idx >= 0 &&
                        window._safeer_medij_zap_idx < tiles.length) {
                        idx = window._safeer_medij_zap_idx;
                    }
                    if (idx < 0) {
                        for (i = 0; i < tiles.length; i++) {
                            if (tiles[i] === cur || (cur && tiles[i].contains && tiles[i].contains(cur))) {
                                idx = i;
                                break;
                            }
                        }
                    }
                    if (idx < 0) {
                        var hl = document.querySelector('.item.item--event.safeer-active-card, .item--event.safeer-active-card');
                        if (hl) {
                            for (i = 0; i < tiles.length; i++) {
                                if (tiles[i] === hl || tiles[i].contains(hl) || (hl.contains && hl.contains(tiles[i]))) {
                                    idx = i;
                                    break;
                                }
                            }
                        }
                    }
                    if (idx < 0) idx = 0;
                    var nextIdx = idx + step;
                    if (nextIdx < 0) nextIdx = tiles.length - 1;
                    if (nextIdx >= tiles.length) nextIdx = 0;
                    if (tiles[nextIdx] === cur && tiles.length > 1) {
                        nextIdx = (nextIdx + step + tiles.length) % tiles.length;
                    }
                    var tile = tiles[nextIdx];
                    if (!tile) return 0;
                    var name = window._safeer_medij_click_live_tile(tile, nextIdx);
                    try {
                        if (window._safeerDbg) window._safeerDbg('H339', 'tv_spatial.js:zap', 'live zap click', {
                            i: nextIdx,
                            n: tiles.length,
                            t: name,
                            d: step
                        });
                    } catch (_) {}
                    return name || 1;
                } catch (e) {
                    return 0;
                }
            };

            // 1. Injektiraj kinematografske sloge za fokus in OSD HUD
            try {
                var style = document.createElement('style');
                style.id = 'tv-remote-cinema-style';
                style.textContent = `
                    /* En sam okvir: izbrani element oznaci plavajoci obroc (#safeer-focus-target-ring),
                       ki ga vsebnik strani ne more odrezati. Obroba na elementu in se obroba strani
                       ob njem sta dajali dvojni okvir (21. 9. 2026, rtvslo.si). Obroba ostane samo
                       elementu s fokusom, ki ni izbran z daljincem (npr. polje za vnos). */
                    html:not(.safeer-izbira) :focus:not(.safeer-active-card):not(.safeer-active-card *),
                    html:not(.safeer-izbira) :focus-visible:not(.safeer-active-card):not(.safeer-active-card *), .tv-remote-focused {
                        outline: 4px solid #00e5ff !important;
                        outline-offset: 4px !important;
                        border-radius: 10px !important;
                        transition: outline-color 0.1s ease !important;
                    }
                    .safeer-active-card, .safeer-active-card :focus, .safeer-active-card :focus-visible {
                        outline: none !important;
                    }
                    .safeer-active-card {
                        background-color: rgba(0, 229, 255, 0.10) !important;
                        z-index: 9999 !important;
                    }
                    #safeer-focus-target-ring {
                        position: fixed;
                        pointer-events: none;
                        border: 4px solid #00e5ff;
                        border-radius: 12px;
                        box-shadow: 0 0 0 2px rgba(0, 0, 0, 0.8), 0 0 16px rgba(0, 229, 255, 0.75);
                        background: rgba(0, 229, 255, 0.08);
                        z-index: 50000;
                        transition: top 0.1s ease, left 0.1s ease, width 0.1s ease, height 0.1s ease, opacity 0.12s ease;
                        opacity: 0;
                    }
                    #safeer-focus-target-ring.active {
                        opacity: 1;
                    }
                    .safeer-focus-badge {
                        display: none !important;
                        position: absolute;
                        top: -14px;
                        right: -12px;
                        background: #00e5ff;
                        color: #000000;
                        font-size: 13px;
                        font-weight: 900;
                        border-radius: 50%;
                        width: 24px;
                        height: 24px;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        box-shadow: 0 0 12px #00e5ff;
                    }
                    /* 🛑 Skrij odvečne pasice na TV zaslonu */
                    #onetrust-banner-sdk, .cookie-banner, .gdpr-consent, .cookie-notice,
                    .open-in-app, .smartbanner, [class*="CookieConsent"], [id*="cookie-notice"],
                    ytm-open-app-button, ytm-app-promo-renderer {
                        display: none !important;
                    }
                    /* 🎬 Kinematografski OSD HUD */
                    #safeer-tv-hud {
                        position: fixed;
                        bottom: 60px;
                        left: 50%;
                        transform: translateX(-50%);
                        background: rgba(8, 14, 26, 0.92);
                        backdrop-filter: blur(16px);
                        -webkit-backdrop-filter: blur(16px);
                        border: 2px solid #00e5ff;
                        border-radius: 16px;
                        padding: 14px 28px;
                        color: #ffffff;
                        font-family: 'Segoe UI', Roboto, sans-serif;
                        font-size: 22px;
                        font-weight: 700;
                        letter-spacing: 0.5px;
                        box-shadow: 0 10px 40px rgba(0, 229, 255, 0.45), 0 0 15px rgba(0, 0, 0, 0.8);
                        display: flex;
                        align-items: center;
                        gap: 14px;
                        z-index: 2147483647;
                        pointer-events: none;
                        opacity: 0;
                        transition: opacity 0.25s ease, transform 0.25s ease;
                    }
                    #safeer-tv-hud.visible {
                        opacity: 1;
                        transform: translateX(-50%) translateY(0);
                    }
                `;
                // Pregled fokusa 5. 11. 2026: senca :focus strani na izbranem elementu je bila drugi okvir ob nasem obrocu. Samo
                // na spletnih straneh - domaca stran (file:) ima okvir fokusa v svojem slogu (safeer-home.css, brave_home.html).
                if (location.protocol !== 'file:') {
                    style.textContent += '.safeer-active-card:focus,.safeer-active-card:focus-visible,' +
                        '.safeer-active-card :focus,.safeer-active-card :focus-visible{box-shadow:none!important}';
                }
                (document.head || document.documentElement).appendChild(style);

            } catch(e) {}

            // #region agent log
            try {
                window._safeerDbg('H1', 'UserScriptManager.kt:TV_REMOTE_init', 'layout', {
                    host: location.hostname,
                    path: location.pathname,
                    w: window.innerWidth,
                    h: window.innerHeight,
                    sw: (document.documentElement && document.documentElement.scrollWidth) || 0,
                    sh: (document.documentElement && document.documentElement.scrollHeight) || 0,
                    cinema: !!document.getElementById('tv-remote-cinema-style')
                });
            } catch (_) {}
            // #endregion

            // 2. Ustvari OSD HUD element
            var hudTimer = null;
            function getOrCreateHud() {
                var hud = document.getElementById('safeer-tv-hud');
                if (!hud) {
                    hud = document.createElement('div');
                    hud.id = 'safeer-tv-hud';
                    (document.body || document.documentElement).appendChild(hud);
                }
                return hud;
            }

            window._safeer_show_osd = function(htmlContent, durationMs) {
                try {
                    var hud = getOrCreateHud();
                    hud.innerHTML = htmlContent;
                    hud.classList.add('visible');
                    if (hudTimer) clearTimeout(hudTimer);
                    hudTimer = setTimeout(function() {
                        hud.classList.remove('visible');
                    }, durationMs || 1500);
                } catch(_) {}
            };

            function formatTime(seconds) {
                if (!isFinite(seconds) || isNaN(seconds)) return '00:00';
                var m = Math.floor(seconds / 60);
                var s = Math.floor(seconds % 60);
                var mm = m < 10 ? '0' + m : '' + m;
                var ss = s < 10 ? '0' + s : '' + s;
                return mm + ':' + ss;
            }

            // 3. Predvajalniške funkcije za daljinec
            window._safeer_is_video_active = function() {
                var v = document.querySelector('video');
                var isWatch = location.pathname.indexOf('/watch') !== -1 || location.pathname.indexOf('/shorts') !== -1;
                if (isWatch) return true;
                if (!v) return false;
                var r = v.getBoundingClientRect();
                return r.width > 800 && r.height > 400 && (v.videoWidth || 0) > 0;
            };

            window._safeer_toggle_play_pause = function() {
                try {
                    var v = document.querySelector('video');
                    var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    
                    // 1. HTML5 Video
                    if (v) {
                        if (v.paused) {
                            v.play().catch(function() {});
                            window._safeer_show_osd('▶ Predvajaj &nbsp;<span style="color:#00e5ff; font-size:18px;">' + formatTime(v.currentTime) + ' / ' + formatTime(v.duration) + '</span>', 1400);
                        } else {
                            v.pause();
                            window._safeer_show_osd('⏸ Premor &nbsp;<span style="color:#ffd700; font-size:18px;">' + formatTime(v.currentTime) + ' / ' + formatTime(v.duration) + '</span>', 1800);
                        }
                        return true;
                    }

                    // 2. Iframe Multi-Protocol Message Broadcast
                    var iframes = document.querySelectorAll('iframe');
                    if (iframes.length > 0) {
                        var msgs = [
                            '{"event":"command","func":"togglePlay","args":""}',
                            '{"event":"command","func":"pauseVideo","args":""}',
                            '{"event":"command","func":"playVideo","args":""}',
                            JSON.stringify({ method: 'toggle' }),
                            JSON.stringify({ event: 'command', func: 'toggle' }),
                            JSON.stringify({ type: 'player:toggle' }),
                            'toggle', 'playPause', 'pause', 'play'
                        ];
                        for (var i = 0; i < iframes.length; i++) {
                            for (var m = 0; m < msgs.length; m++) {
                                try { iframes[i].contentWindow.postMessage(msgs[m], '*'); } catch(_) {}
                            }
                        }
                        window._safeer_show_osd('⏯ Predvajalnik preklopljen', 1400);
                        return true;
                    }
                } catch(_) {}
                return false;
            };

            window._safeer_seek = function(deltaSeconds) {
                try {
                    var v = document.querySelector('video');
                    if (!v) return false;
                    var newTime = Math.max(0, Math.min(v.duration || 99999, v.currentTime + deltaSeconds));
                    v.currentTime = newTime;
                    var sign = deltaSeconds > 0 ? '+ ' + deltaSeconds + 's ⏩' : deltaSeconds + 's ⏪';
                    var color = deltaSeconds > 0 ? '#00e5ff' : '#38bdf8';
                    window._safeer_show_osd(
                        '<span style="color:' + color + ';">' + sign + '</span> &nbsp; <span style="font-size:18px;">' + formatTime(newTime) + ' / ' + formatTime(v.duration) + '</span>',
                        1400
                    );
                    return true;
                } catch(_) {}
                return false;
            };

            window._safeer_toggle_fullscreen = function() {
                try {
                    var fsBtn = document.querySelector('.ytp-fullscreen-button, button[aria-label*="celozaslon"], button[aria-label*="Fullscreen"], button[aria-label*="Full screen"], .fullscreen-button');
                    if (fsBtn) {
                        fsBtn.click();
                        window._safeer_show_osd('⛶ Celozaslonski način', 1200);
                        return true;
                    }
                    var v = document.querySelector('video');
                    if (v) {
                        if (document.fullscreenElement) {
                            document.exitFullscreen();
                            window._safeer_show_osd('🗗 Običajen pogled', 1200);
                        } else {
                            (v.parentElement || v).requestFullscreen().catch(function() {});
                            window._safeer_show_osd('⛶ Celozaslonski način', 1200);
                        }
                        return true;
                    }
                } catch(_) {}
                return false;
            };

            window._safeer_next_video = function() {
                try {
                    var isShorts = location.pathname.indexOf('/shorts') !== -1;
                    if (isShorts) {
                        window.scrollBy({ top: window.innerHeight * 0.9, behavior: 'smooth' });
                        window._safeer_show_osd('⏭ Naslednji Short', 1000);
                        return true;
                    }
                    var nextBtn = document.querySelector('.ytp-next-button, button[aria-label*="Naslednji"], button[aria-label*="Next"], a[aria-label*="Next"]');
                    if (nextBtn) {
                        nextBtn.click();
                        window._safeer_show_osd('⏭ Naslednji video', 1200);
                        return true;
                    }
                    var nextRec = document.querySelector('ytm-compact-video-renderer a, ytd-compact-video-renderer a, ytm-video-with-context-renderer a, .media-item-thumbnail-container');
                    if (nextRec) {
                        nextRec.click();
                        window._safeer_show_osd('⏭ Predvajam naslednjega', 1200);
                        return true;
                    }
                } catch(_) {}
                return false;
            };

            window._safeer_prev_video = function() {
                try {
                    var isShorts = location.pathname.indexOf('/shorts') !== -1;
                    if (isShorts) {
                        window.scrollBy({ top: -window.innerHeight * 0.9, behavior: 'smooth' });
                        window._safeer_show_osd('⏮ Prejšnji Short', 1000);
                        return true;
                    }
                    var prevBtn = document.querySelector('.ytp-prev-button, button[aria-label*="Prejšnji"], button[aria-label*="Previous"]');
                    if (prevBtn) {
                        prevBtn.click();
                        window._safeer_show_osd('⏮ Prejšnji video', 1200);
                        return true;
                    }
                    if (window.history.length > 1) {
                        window.history.back();
                        window._safeer_show_osd('⏮ Nazaj', 1000);
                        return true;
                    }
                } catch(_) {}
                return false;
            };

            window._safeer_adjust_volume = function(delta) {
                try {
                    var v = document.querySelector('video');
                    if (v) {
                        var newVol = Math.max(0.0, Math.min(1.0, v.volume + delta));
                        v.volume = newVol;
                        v.muted = false;
                        var pct = Math.round(newVol * 100);
                        var icon = pct === 0 ? '🔇' : (pct > 60 ? '🔊' : '🔉');
                        window._safeer_show_osd(icon + ' Spletna glasnost: ' + pct + '%', 1200);
                        return true;
                    }
                } catch(_) {}
                return false;
            };

            // 4. Inteligentna geometrijska prostorska navigacija po spletnih elementih (2D Spatial Navigation)
            function jePotVZivo() {
                return ((location.pathname || '').toLowerCase()).indexOf('/livetv') !== -1;
            }

            /* SAFEER_SIROK_GUMB_ZACETEK */
            // Lastnik 28. 10. 2026 (Facebook na TV): gumb »Prijava« je <div role="button"> cez ves obrazec (vec kot
            // 48 % sirine), zato ga je pravilo za siroke vsebnike izlocilo in daljinec do njega ni prisel. Sirok, a nizek
            // element z vlogo gumba ali povezave (ali tabindex >= 0) je gumb, ne vsebnik.
            function jeSirokGumb(el, rect, winH) {
                if (!el || !el.getAttribute || !rect) return false;
                var vloga = (el.getAttribute('role') || '').toLowerCase();
                var ti = el.getAttribute('tabindex');
                var gumb = vloga === 'button' || vloga === 'link' || vloga === 'tab' ||
                    (ti !== null && ti !== '' && !isNaN(parseInt(ti, 10)) && parseInt(ti, 10) >= 0);
                if (!gumb) return false;
                return rect.height <= Math.max(72, (winH || 1080) * 0.15);
            }
            /* SAFEER_SIROK_GUMB_KONEC */

            /* SAFEER_NA_ELEMENTU_ZACETEK */
            // Ali je element, ki ga brskalnik vidi na tocki (zadetek), ta element ali njegov potomec.
            function naElementu(el, zadetek) {
                if (!el || !zadetek) return false;
                try { return zadetek === el || (el.contains && el.contains(zadetek)); } catch (_) { return false; }
            }
            /* SAFEER_NA_ELEMENTU_KONEC */

            /* SAFEER_MODALNO_ZACETEK */
            // Lastnik 28. 10. 2026 (Facebook na TV, se vedno): gumba »Dovoli vse piskotke« / »Zavrni izbirne piskotke«
            // v besedilu omenjata piskotke, zato ju je jeNogaAliPiskotki izlocil in daljinec do njiju ni mogel. Pasico
            // s piskotki na dnu strani se vedno preskocimo; v modalnem oknu pa je izbira prav tisto, kar mora uporabnik
            // narediti, zato tam noben element ni »noga ali piskotki«.
            function jeVModalnemOknu(el) {
                try {
                    return !!(el && el.closest &&
                        el.closest('[aria-modal="true"], [role="dialog"], [role="alertdialog"], dialog[open]'));
                } catch (_) { return false; }
            }
            /* SAFEER_MODALNO_KONEC */

            /* SAFEER_PLAST_IZBIRE_ZACETEK */
            // Lastnik 3. 11. 2026 02:42 (claude.ai/login na TV, uiautomator): do okna »Cookie settings« desno (Customize /
            // Reject all / Accept all cookies, povezava »here«) daljinec ni prisel. Gumbi omenjajo piskotke, zato jih je
            // jeNogaAliPiskotki izlocil; okno ni bilo modalno (ne role=dialog ne cez cel zaslon), izjema ga ni zajela.
            // Pritrjena plast (fixed ali sticky prednik) ali pogovorno okno je izbira uporabnika: vsi gumbi in povezave
            // v njej so cilji. Nic ne sprejmemo ali zavrnemo samodejno - izbere uporabnik.
            var IZBIRNIK_OKNA = '[aria-modal="true"], [role="dialog"], [role="alertdialog"], dialog[open]';
            function plastIzbire(el, slog) {
                var p = el, g = 0;
                while (p && p.nodeType === 1 && g++ < 15) {
                    var tag = (p.tagName || '').toUpperCase();
                    if (tag === 'BODY' || tag === 'HTML') return null;
                    try { if (p.matches && p.matches(IZBIRNIK_OKNA)) return p; } catch (_) {}
                    var pos = '';
                    try { pos = slog(p).position; } catch (_) {}
                    if (pos === 'fixed' || pos === 'sticky') return p;
                    p = p.parentElement;
                }
                return null;
            }
            // Prvi cilj v plasti: najprej gumb (ne povezava v besedilu), po vrsti branja. Nevtralno: prvi gumb po legi,
            // ne »Sprejmi« ali »Zavrni« po besedilu.
            function prviVPlasti(okno, kandidati) {
                var gumbi = [], drugi = [];
                for (var i = 0; i < kandidati.length; i++) {
                    var k = kandidati[i];
                    if (!okno.contains(k)) continue;
                    var tag = (k.tagName || '').toUpperCase();
                    var ro = ((k.getAttribute && k.getAttribute('role')) || '').toLowerCase();
                    (tag === 'BUTTON' || ro === 'button' || (tag === 'INPUT' && /^(button|submit)$/i.test(k.getAttribute('type') || ''))
                        ? gumbi : drugi).push(k);
                }
                var sez = gumbi.length ? gumbi : drugi;
                sez.sort(function (a, b) {
                    var ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
                    return Math.abs(ra.top - rb.top) > 8 ? ra.top - rb.top : ra.left - rb.left;
                });
                return sez[0] || null;
            }
            /* SAFEER_PLAST_IZBIRE_KONEC */

            function jeNogaAliPiskotki(el) {
                if (!el) return false;
                if (jeVModalnemOknu(el)) return false;
                try {
                    if (el.closest && el.closest('footer, .footer')) return true;
                } catch (_) {}
                var piskotek = false;
                try {
                    piskotek = !!(el.closest && el.closest('[class*="cookie"], [class*="piškot"], [class*="piskot"]'));
                } catch (_) {}
                var t = ((el.innerText || el.textContent || '') + '').replace(/\s+/g, ' ').trim().toLowerCase();
                if (t.indexOf('piškot') !== -1 || t.indexOf('piskot') !== -1 || t.indexOf('cookie') !== -1) piskotek = true;
                var href = ((el.getAttribute && el.getAttribute('href')) || '').toLowerCase();
                if (href.indexOf('cookie') !== -1) piskotek = true;
                // Izbira o piskotkih v pritrjeni plasti ali oknu je uporabnikova (SAFEER_PLAST_IZBIRE, 3. 11. 2026).
                if (piskotek) return !plastIzbire(el, window.getComputedStyle);
                var r;
                try { r = el.getBoundingClientRect(); } catch (_) { r = null; }
                // Nizek element cisto spodaj je skoraj vedno pasica s piskotki - razen v pritrjeni spodnji vrstici
                // za navigacijo (rtvslo.si: MMC / Zame / RTV 365 / Radio / Televizija / mojRTV, 43 px pri dnu) in v
                // pritrjeni plasti z izbiro (pasica, ki caka na odlocitev uporabnika).
                if (r && r.top > ((window.innerHeight || 1080) - 70) && r.height < 48 && !jeSpodnjaNavigacija(el) &&
                    !plastIzbire(el, window.getComputedStyle)) return true;
                return false;
            }

            // Pritrjena spodnja vrstica za navigacijo, ki je trenutno na zaslonu (ali null).
            function najdiSpodnjoVrstico() {
                try {
                    var w = window.innerWidth || 1920, h = window.innerHeight || 1080, xs = [0.2, 0.5, 0.8];
                    for (var i = 0; i < xs.length; i++) {
                        var p = document.elementFromPoint(Math.round(w * xs[i]), h - 6), g = 0;
                        while (p && p.nodeType === 1 && g < 12) {
                            var pos = window.getComputedStyle(p).position;
                            if (pos === 'fixed' || pos === 'sticky') break;
                            p = p.parentElement; g++;
                        }
                        if (!p || p.nodeType !== 1 || g >= 12) continue;
                        var r = p.getBoundingClientRect();
                        if (r.bottom < h - 8 || r.height >= h * 0.3) continue;
                        var gumb = p.querySelector('a[href], button, [role="tab"], [role="link"], [role="button"]');
                        if (gumb && jeSpodnjaNavigacija(gumb)) return { el: p, top: r.top };
                    }
                } catch (_) {}
                return null;
            }

            // Pritrjena spodnja vrstica z vsaj tremi povezavami ali gumbi in brez besedila o piskotkih/soglasju.
            function jeSpodnjaNavigacija(el) {
                try {
                    var p = el, g = 0, vrstica = null;
                    while (p && p.nodeType === 1 && g < 12) {
                        var pos = window.getComputedStyle(p).position;
                        if (pos === 'fixed' || pos === 'sticky') { vrstica = p; break; }
                        p = p.parentElement; g++;
                    }
                    if (!vrstica) return false;
                    var rv = vrstica.getBoundingClientRect();
                    if (rv.height > (window.innerHeight || 1080) * 0.3) return false;
                    var besedilo = ((vrstica.innerText || '') + '').toLowerCase();
                    if (/pi[sš]kot|cookie|soglas|consent|zasebnost|privacy/.test(besedilo)) return false;
                    return vrstica.querySelectorAll('a[href], button, [role="tab"], [role="link"], [role="button"]').length >= 3;
                } catch (_) {
                    return false;
                }
            }

            function jeVsebinskaPloscica(el) {
                if (!el) return false;
                var cls = ((el.className || '') + '').toLowerCase();
                if (cls.indexOf('item--event') !== -1) return true;
                if (cls.indexOf('content-carousel__item') !== -1) return true;
                if (cls.indexOf('editorial') !== -1 && cls.indexOf('item') !== -1) return true;
                if (!jePotVZivo() && cls.indexOf('channel-container') !== -1) return true;
                try {
                    if (el.closest && el.closest('[class*="content-carousel__item"], .item.item--event, .item--event')) return true;
                    if (!jePotVZivo() && el.closest && el.closest('.channel-container, [class*="channel-container"]')) return true;
                } catch (_) {}
                return false;
            }

            function jeGumbDejanja(el) {
                if (!el) return false;
                var cls = ((el.className || '') + '').toLowerCase();
                if (cls.indexOf('action-list') !== -1) return true;
                if (cls.indexOf('promo-carousel__button') !== -1) return true;
                var t = ((el.innerText || el.textContent || '') + '').replace(/\s+/g, ' ').trim().toLowerCase();
                if (!t || t.length > 56) return false;
                return t.indexOf('predvajaj') !== -1 || t.indexOf('glej zdaj') !== -1 ||
                    t.indexOf('glej oddajo') !== -1 || t.indexOf('na začetek') !== -1 ||
                    t.indexOf('na zacetek') !== -1 || t.indexOf('priljubljen') !== -1 ||
                    t.indexOf('posnemi') !== -1 || t.indexOf('dodaj med') !== -1;
            }

            function izberiVsebinskoPloscico() {
                var firstLive = jePotVZivo() ? document.getElementById('csh__first_item') : null;
                if (firstLive) {
                    try {
                        var fr0 = firstLive.getBoundingClientRect();
                        if (fr0.width >= 80 && fr0.height >= 50 && fr0.bottom > 80) return firstLive;
                    } catch (_) {}
                }
                var nodes = document.querySelectorAll(
                    '.item.item--event, .item--event, [class*="content-carousel__item"], [class*="editorial-item"], .content-carousel a, [class*="content-carousel"] a, .channel-container, [class*="channel-container"]'
                );
                var best = null;
                var bestScore = 1e12;
                var i, el, r, score;
                var winH = window.innerHeight || 1080;
                var winW = window.innerWidth || 1920;
                for (i = 0; i < nodes.length; i++) {
                    el = nodes[i];
                    if (jeNogaAliPiskotki(el)) continue;
                    var ncls = ((el.className || '') + '').toLowerCase();
                    if (jePotVZivo() && ncls.indexOf('channel-container') !== -1 && ncls.indexOf('item--event') === -1) continue;
                    r = el.getBoundingClientRect();
                    if (r.width < 120 || r.height < 70) continue;
                    if (r.width > winW * 0.72 || r.height > winH * 0.62) continue;
                    if (r.top < 52 || r.top > winH - 80) continue;
                    if (r.left > winW - 80 || r.right < 40) continue;
                    score = r.top * 8 + r.left;
                    if ((el.id || '') === 'csh__first_item') score -= 8000;
                    if (ncls.indexOf('item--event') !== -1) score -= 60;
                    if (score < bestScore) {
                        bestScore = score;
                        best = el;
                    }
                }
                if (best) {
                    var root = null;
                    try {
                        root = best.closest && best.closest('[class*="content-carousel__item"], .item.item--event, .item--event');
                        if (!root && !jePotVZivo()) {
                            root = best.closest && best.closest('.channel-container, [class*="channel-container"]');
                        }
                    } catch (_) {}
                    return root || best;
                }
                return null;
            }

            /* SAFEER_PRAVI_CILJ_ZACETEK */
            // Zunanji pregled 3. 11. 2026: ovitek kartice (razred card/tile/item/media ...) je zamenjal gumb ali povezavo v
            // sebi - obroc na celi kartici, gumb »Predvajaj« ali »Vec« ni bil dosegljiv. Pravi cilj je element, ki ga
            // brskalnik sam pozna kot dejanje; ovitek po razredu je cilj samo, ce v njem ni drugega pravega cilja.
            // Znane ploscice (item--event, content-carousel__item: RTV 365, Pro Plus) ostanejo cele kot prej.
            function jePravCilj(el) {
                if (!el || el.nodeType !== 1) return false;
                var t = (el.tagName || '').toUpperCase();
                if (t === 'BUTTON' || t === 'INPUT' || t === 'TEXTAREA' || t === 'SELECT') return true;
                if (t === 'A' && el.hasAttribute('href')) return true;
                var ro = (el.getAttribute('role') || '').toLowerCase();
                return ro === 'button' || ro === 'link' || ro === 'tab';
            }
            function jeZnanaPloscica(el) {
                var c = ((el && el.className) || '').toString().toLowerCase();
                return c.indexOf('item--event') !== -1 || c.indexOf('content-carousel__item') !== -1;
            }
            function jeOvitekPoRazredu(el) {
                if (!el || jePravCilj(el) || jeZnanaPloscica(el)) return false;
                if (el.hasAttribute('onclick') || el.hasAttribute('data-ved')) return false;
                var ti = el.getAttribute('tabindex');
                return !(ti !== null && ti.trim() !== '' && ti.trim() !== '-1');
            }
            function pravihCiljevV(el) {
                var n = 0, vsi;
                try { vsi = el.querySelectorAll('a[href], button, [role="button"], [role="link"], [role="tab"], input, select, textarea'); } catch (_) { return 0; }
                for (var i = 0; i < vsi.length && n < 2; i++) {
                    var r = vsi[i].getBoundingClientRect();
                    if (r.width > 0 && r.height > 0) n++;
                }
                return n;
            }
            /* SAFEER_PRAVI_CILJ_KONEC */

            function findCardContainer(el) {
                if (!el || el === document.body || el === document.documentElement) return null;
                var tag = el.tagName.toUpperCase();
                var cls = (el.className || '').toString().toLowerCase();
                var text = (el.textContent || '').trim().toLowerCase();

                // Gumbi za neposredno dejanje (npr. 'Glej Zdaj', 'Predvajaj', 'Prijava', 'Zapri') naj ostanejo samostojni cilji!
                if (tag === 'BUTTON' || tag === 'INPUT' || tag === 'IFRAME' || (tag === 'A' && (cls.indexOf('btn') !== -1 || text.indexOf('glej') !== -1 || text.indexOf('predvajaj') !== -1 || text.indexOf('prijava') !== -1))) {
                    return null;
                }
                if (isCarouselArrow(el)) {
                    return null;
                }

                var curr = el;
                var highestCard = null;
                while (curr && curr !== document.body && curr !== document.documentElement) {
                    var cTag = curr.tagName.toUpperCase();
                    var cCls = (curr.className || '').toString().toLowerCase();
                    
                    // Hero pasica ni kartica programa ampak vsebovalnik z gumbi
                    if (cCls.indexOf('hero') !== -1 || cCls.indexOf('slick-slider') !== -1 || cCls.indexOf('promo-carousel') !== -1) {
                        break;
                    }

                    var isCard = cCls.indexOf('item--event') !== -1 ||
                                 cCls.indexOf('content-carousel__item') !== -1 ||
                                 cCls.indexOf('channel-card') !== -1 ||
                                 cCls.indexOf('program-card') !== -1 ||
                                 cCls.indexOf('video-card') !== -1 || cCls.indexOf('media-card') !== -1 ||
                                 (cCls.indexOf('tile') !== -1 && cTag !== 'BODY');
                    if (cCls.indexOf('slick-slide') !== -1 && cCls.indexOf('item--event') === -1 &&
                        cCls.indexOf('content-carousel__item') === -1) {
                        isCard = false;
                    }
                                 
                    if (isCard) {
                        var rect = curr.getBoundingClientRect();
                        if (rect.width >= 60 && rect.height >= 40 && rect.width < 1600) {
                            highestCard = curr;
                            if (cCls.indexOf('item--event') !== -1 || cCls.indexOf('content-carousel__item') !== -1) {
                                break;
                            }
                        }
                    }
                    curr = curr.parentElement;
                }
                // Pravi cilj v kartici z vec dejanji ostane sam (SAFEER_PRAVI_CILJ): kartica ga ne zamenja.
                if (highestCard && jePravCilj(el) && !jeZnanaPloscica(highestCard) && pravihCiljevV(highestCard) > 1) return null;
                return highestCard;
            }

            // --- Prekrivna okna (prijava, piskotki, obvestila) ---
            // Ko je odprto prekrivno okno, gumbi pod njim ostanejo v DOM in so po CSS
            // se vedno "vidni", klikniti pa jih ni mogoce. Brez tega preverjanja daljinec
            // fokusira prav te gumbe: okvir skace po praznem zaslonu in uporabnik ne more
            // do vsebine okna. Ne ugibamo po imenih razredov (vsaka stran jih imenuje po
            // svoje) - vprasamo brskalnik, kaj je dejansko na tisti tocki.
            function jeSkritZaFokus(el) {
                try {
                    if (el.closest && el.closest('[inert], [aria-hidden="true"]')) return true;
                } catch (_) {}
                return false;
            }

            function jePrekrit(el) {
                try {
                    var r = el.getBoundingClientRect();
                    var winW = window.innerWidth || 1920;
                    var winH = window.innerHeight || 1080;
                    var l = Math.max(r.left, 0), d = Math.min(r.right, winW);
                    var t = Math.max(r.top, 0), b = Math.min(r.bottom, winH);
                    // Element zunaj zaslona: o njem ne sodimo, za to skrbijo drugi filtri.
                    if (d - l < 2 || b - t < 2) return false;
                    var sredina = l + (d - l) * 0.5;
                    if (!jeNaTocki(el, sredina, (t + b) / 2)) {
                        // Videti je prekrit. Preden ga odpisemo, poskusimo se levo in desno ter nad in pod sredino -
                        // lepljiva glava ali drsnik lahko prekriva le del (zunanji pregled 3. 11. 2026: glava cez
                        // zgornjo polovico gumba ga je izlocila, cetudi je bil spodnji del viden in klikljiv).
                        var vrste = [0.5, 0.8, 0.2], stolpci = [0.5, 0.15, 0.85];
                        for (var vi = 0; vi < vrste.length; vi++) {
                            var y = t + (b - t) * vrste[vi];
                            for (var si = 0; si < stolpci.length; si++) {
                                if (vi === 0 && si === 0) continue;
                                if (jeNaTocki(el, l + (d - l) * stolpci[si], y)) return false;
                            }
                        }
                        return true;
                    }
                } catch (_) {}
                return false;
            }

            function jeNasObroc(el) {
                if (!el) return false;
                if (el.id === 'safeer-focus-target-ring') return true;
                var c = ((el.className || '') + '');
                return c.indexOf('safeer-focus-badge') !== -1 || c.indexOf('safeer-ring') !== -1;
            }

            function jeNaTocki(el, x, y) {
                var winW = window.innerWidth || 1920;
                var winH = window.innerHeight || 1080;
                if (x <= 0 || y <= 0 || x >= winW || y >= winH) return true;
                var zadetek = document.elementFromPoint(x, y);
                if (!zadetek) return true;
                // Nas lastni oznacevalni obroc ni ovira. Ce bi stel za oviro, bi prav
                // trenutno oznacen element izpadel s seznama in oznaka bi odskocila drugam.
                if (jeNasObroc(zadetek)) return true;
                return zadetek === el || el.contains(zadetek) || zadetek.contains(el);
            }

            // Poisce plast cez cel zaslon: prijavno okno, obvestilo o piskotkih, opozorilo.
            // Ce taka plast obstaja, je vse zunaj nje za uporabnika nedosegljivo, cetudi je
            // po CSS se vedno vidno. Iscemo po obicajnih imenih, potem pa vsakega kandidata
            // se izmerimo - ime samo po sebi ni dokaz.
            function najdiPrekrivnoOkno() {
                var imena = '[role="dialog"], [role="alertdialog"], dialog[open],' +
                    ' [class*="modal"], [class*="Modal"], [class*="overlay"], [class*="Overlay"],' +
                    ' [class*="backdrop"], [class*="popup"], [class*="Popup"], [class*="dialog"],' +
                    ' [class*="consent"], [class*="cookie"], [id*="modal"], [id*="overlay"],' +
                    ' [id*="popup"], [id*="consent"], [id*="cookie"]';
                var kandidati;
                try { kandidati = document.querySelectorAll(imena); } catch (_) { return null; }
                var winW = window.innerWidth || 1920;
                var winH = window.innerHeight || 1080;
                var najboljsi = null, najvisji = -1;
                for (var i = 0; i < kandidati.length; i++) {
                    var el = kandidati[i];
                    var st;
                    try { st = window.getComputedStyle(el); } catch (_) { continue; }
                    if (st.position !== 'fixed' && st.position !== 'absolute') continue;
                    if (st.display === 'none' || st.visibility === 'hidden') continue;
                    if ((parseFloat(st.opacity) || 1) < 0.15) continue;
                    if (st.pointerEvents === 'none') continue;
                    var r = el.getBoundingClientRect();
                    if (r.width < winW * 0.8 || r.height < winH * 0.8) continue;
                    if (r.top > 8 || r.left > 8) continue;
                    var z = parseInt(st.zIndex, 10);
                    if (!(z > 0)) continue;
                    // Gola zatemnitev brez gumbov ni okno, v katerega bi se dalo premakniti.
                    try {
                        if (!el.querySelector('a, button, input, select, textarea, [role="button"]')) continue;
                    } catch (_) { continue; }
                    if (z >= najvisji) { najvisji = z; najboljsi = el; }
                }
                if (najboljsi) return najboljsi;
                // Lastnik 28. 10. 2026 (Facebook na TV, okno o piskotkih): pogovorno okno, ki ni cez cel zaslon in nima
                // z-indeksa na sebi (zatemnitev je drug element brez imena). Daljinec je skakal na povezave za oknom in
                // do »Dovoli« / »Zavrni« ni prisel. Modalno okno (aria-modal, dialog, role=dialog) velja, ce je vidno,
                // veliko vsaj tretjino zaslona, ima gumbe in je na vrhu: na njegovi sredini je ono samo, ne kaj drugega.
                var dialogi;
                try { dialogi = document.querySelectorAll('[aria-modal="true"], [role="dialog"], [role="alertdialog"], dialog[open]'); }
                catch (_) { return null; }
                for (var d = 0; d < dialogi.length; d++) {
                    var dl = dialogi[d];
                    var sd;
                    try { sd = window.getComputedStyle(dl); } catch (_) { continue; }
                    if (sd.display === 'none' || sd.visibility === 'hidden' || sd.pointerEvents === 'none') continue;
                    if ((parseFloat(sd.opacity) || 1) < 0.15) continue;
                    var rd = dl.getBoundingClientRect();
                    if (rd.width < winW * 0.3 || rd.height < winH * 0.3) continue;
                    try {
                        if (!dl.querySelector('a, button, input, select, textarea, [role="button"]')) continue;
                    } catch (_) { continue; }
                    var cx = Math.max(1, Math.min(winW - 2, rd.left + rd.width / 2));
                    var cy = Math.max(1, Math.min(winH - 2, rd.top + rd.height / 2));
                    var naVrhu = document.elementFromPoint(cx, cy);
                    if (!naVrhu || jeNasObroc(naVrhu)) continue;
                    if (naVrhu === dl || dl.contains(naVrhu)) najboljsi = dl;     // kasnejsi v dokumentu je navadno vrhnji
                }
                return najboljsi;
            }

            function pocistiObroc(dovoljeni) {
                try {
                    var aktiven = izbrani();
                    if (aktiven && dovoljeni.indexOf(aktiven) === -1) {
                        aktiven.classList.remove('safeer-active-card');
                        // Pregled fokusa 5. 11. 2026: obroc je ostal na mestu cilja brez izbire (prazen okvir).
                        nastaviIzbrani(null);
                        updateFocusRing(null);
                    }
                } catch (_) {}
            }

            // Ali se da v dani smeri se drsati? Ne sprasujemo, ali drsnik obstaja, ampak ali
            // ima se kam iti: element levo zunaj zaslona je dosegljiv samo, ce je kaj ze
            // odrsanega v desno. Sicer ga ni mogoce prikazati in ni cilj za daljinec.
            function lahkoDrsi(el, vodoravno, nazaj) {
                try {
                    var st = window.getComputedStyle(el);
                    var pretok = vodoravno ? st.overflowX : st.overflowY;
                    if (pretok !== 'auto' && pretok !== 'scroll') return false;
                    var polozaj = vodoravno ? el.scrollLeft : el.scrollTop;
                    if (nazaj) return polozaj > 4;
                    var obseg = vodoravno ? (el.scrollWidth - el.clientWidth)
                                          : (el.scrollHeight - el.clientHeight);
                    return (obseg - polozaj) > 4;
                } catch (_) {
                    return false;
                }
            }

            function sePridePonj(el, vodoravno, nazaj) {
                var p = el.parentElement;
                var globina = 0;
                while (p && globina < 30) {
                    if (lahkoDrsi(p, vodoravno, nazaj)) return true;
                    p = p.parentElement;
                    globina++;
                }
                try {
                    var d = document.documentElement;
                    var polozaj = vodoravno ? (window.scrollX || d.scrollLeft || 0)
                                            : (window.scrollY || d.scrollTop || 0);
                    if (nazaj) return polozaj > 4;
                    var obseg = vodoravno ? (d.scrollWidth - d.clientWidth)
                                          : (d.scrollHeight - d.clientHeight);
                    return (obseg - polozaj) > 4;
                } catch (_) {
                    return true;
                }
            }

            // Pritrjen element (position: fixed, sam ali po predniku) stoji glede na pogled.
            // Ce je zunaj zaslona, ga drsenje ne bo nikoli prikazalo - tak je skriti stranski
            // predal, ki ga ima veliko strani (npr. chatgpt.com).
            function jePritrjen(el) {
                var p = el;
                var globina = 0;
                while (p && p.nodeType === 1 && globina < 30) {
                    try {
                        var st = window.getComputedStyle(p);
                        if (st.position === 'fixed') return true;
                    } catch (_) {}
                    p = p.parentElement;
                    globina++;
                }
                return false;
            }

            // Ali je element zunaj vidnega polja? Cisto geometrijsko vprasanje, brez ugibanja:
            // ce je tu odgovor pritrdilen tudi po poskusu drsenja, obroca tja ne postavimo.
            function jeZunajPogleda(el) {
                try {
                    var r = el.getBoundingClientRect();
                    var winW = window.innerWidth || 1920;
                    var winH = window.innerHeight || 1080;
                    if (r.width < 1 && r.height < 1) return true;
                    return (r.right <= 4) || (r.left >= winW - 4) ||
                           (r.bottom <= 4) || (r.top >= winH - 4);
                } catch (_) {
                    return false;
                }
            }

            // Element cisto zunaj zaslona, do katerega se ne da pridrsati, ni cilj:
            // obroc bi se narisal zunaj vidnega polja in uporabnik bi ostal brez oznake.
            function jeIzvenDosega(el) {
                try {
                    var r = el.getBoundingClientRect();
                    var winW = window.innerWidth || 1920;
                    var winH = window.innerHeight || 1080;
                    if (r.width < 1 && r.height < 1) return true;
                    var zunaj = (r.right <= 4) || (r.left >= winW - 4) ||
                                (r.bottom <= 4) || (r.top >= winH - 4);
                    if (!zunaj) return false;
                    if (jePritrjen(el)) return true;
                    if (r.right <= 4) return !sePridePonj(el, true, true);
                    if (r.left >= winW - 4) return !sePridePonj(el, true, false);
                    if (r.bottom <= 4) return !sePridePonj(el, false, true);
                    if (r.top >= winH - 4) return !sePridePonj(el, false, false);
                } catch (_) {}
                return false;
            }

            /* SAFEER_ZAPRTA_STRAN_ZACETEK */
            // Lastnik 3. 11. 2026 05:23-05:26 (claude.ai/login na TV): uganka v tujem okvirju (Premakni / Preskoci) je pokrila
            // stran. Okno ima samo okvir, brez gumbov v nasem dokumentu, zato ga najdiPrekrivnoOkno ni stelo; vsi cilji strani so
            // bili prekriti in navigacija je skakala po njih ZA uganko - stran je samo drsela, do vrstice ni prisel. Pritrjena
            // plast s tujim okvirjem na sredini zaslona zdaj pomeni: v strani ni cilja (-1), GOR gre v vrstico brskalnika.
            var stranZaprta = false;
            function najdiZaprtoPlast() {
                var w = window.innerWidth || 1920, h = window.innerHeight || 1080;
                var e = document.elementFromPoint(w / 2, h / 2);
                if (!e || jeNasObroc(e)) return null;
                var p = e, g = 0;
                while (p && p.nodeType === 1 && g++ < 20) {
                    var tag = (p.tagName || '').toUpperCase();
                    if (tag === 'BODY' || tag === 'HTML') return null;
                    var st;
                    try { st = window.getComputedStyle(p); } catch (_) { return null; }
                    if (st.position === 'fixed') {
                        var r = p.getBoundingClientRect();
                        if (r.width * r.height < w * h * 0.3) return null;
                        var okvir = (e.tagName || '').toUpperCase() === 'IFRAME' || !!p.querySelector('iframe');
                        return okvir ? p : null;
                    }
                    p = p.parentElement;
                }
                return null;
            }
            /* SAFEER_ZAPRTA_STRAN_KONEC */

            function odstraniNedosegljive(seznam) {
                stranZaprta = false;
                if (!seznam || !seznam.length) {
                    if (najdiZaprtoPlast()) stranZaprta = true;
                    return seznam;
                }
                var okno = najdiPrekrivnoOkno();
                var vOknu = [];
                for (var i = 0; i < seznam.length; i++) {
                    var el = seznam[i];
                    if (jeSkritZaFokus(el)) continue;
                    if (jeIzvenDosega(el)) continue;
                    if (okno && !okno.contains(el)) continue;
                    vOknu.push(el);
                }
                if (okno) {
                    // Znotraj okna ne ugibamo naprej: kar je v njem, je dosegljivo.
                    // Ce v oknu ni nobenega cilja, raje pustimo prvotni seznam, kot da bi
                    // daljinec ostal brez cilja.
                    if (!vOknu.length) return seznam;
                    pocistiObroc(vOknu);
                    return vOknu;
                }
                var ostane = [];
                for (var j = 0; j < vOknu.length; j++) {
                    if (!jePrekrit(vOknu[j])) ostane.push(vOknu[j]);
                }
                if (!ostane.length) {
                    // Vse prekriva plast s tujim okvirjem (uganka, preverjanje): za njo ni cilja (SAFEER_ZAPRTA_STRAN).
                    if (najdiZaprtoPlast()) { stranZaprta = true; pocistiObroc([]); return []; }
                    return vOknu.length ? vOknu : seznam;
                }
                pocistiObroc(ostane);
                return ostane;
            }

            // Oznaka za razhroscevanje: po njej se vidi, ali tece popravljena skripta.
            try { window._safeerFokusFilter = '2026-09-14e'; } catch (_) {}

            /* SAFEER_OKVIR_GUMB_ZACETEK */
            // Lastnik 2. 11. 2026 (claude.ai prijava na TV): »Nadaljuj z Google« je gumb Google Identity Services v
            // tujem okvirju (accounts.google.com/gsi/button). Okvirjev nismo steli za cilje, zato ga daljinec ni dosegel.
            // Majhen viden okvir je zdaj cilj (gumb, ne vsebina): visok najvec 120 px, sirok najvec 60 % zaslona in
            // najvec 8 % povrsine; oglasni okvirji (google_ads, aswift, doubleclick ...) ne. OK je en pravi dotik na sredini
            // samo na okvir https://accounts.google.com/gsi/ (tockaNamena 'google', neodvisni pregled 5. 11. 2026); drug
            // okvir daljinec izbere, OK vanj ne seze (kazalec - rdeci gumb - ostane uporabnikov pravi dotik).
            function jeGumbOkvir(sirina, visina, winW, winH, ime) {
                if (!(sirina >= 40 && visina >= 20)) return false;
                if (visina > 120 || sirina > winW * 0.6 || sirina * visina > winW * winH * 0.08) return false;
                var i = (ime || '').toLowerCase();
                return !/google_ads|aswift|doubleclick|googlesyndication|adservice|\bads?\b|banner|sponsor/.test(i);
            }
            /* SAFEER_OKVIR_GUMB_KONEC */

            /* SAFEER_IZZIV_ZACETEK */
            // Lastnik 3. 11. 2026 (prijava na TV): do Cloudflare »Verify you are human« (Turnstile) daljinec ni prisel.
            // Izmerjeno 3. 11. 2026 (Chrome, preizkusni kljuc 3x...FF): okvir challenges.cloudflare.com je v ZAPRTEM shadow
            // DOM, querySelectorAll('iframe') vrne 0. Vidna sta le gostitelj (DIV, sirok kot vsebnik, 880 px) in skrito polje
            // cf-turnstile-response. Okvir je 300 x 65 ob levem robu gostitelja, potrditveno polje 22 px od levega roba na
            // sredini visine. Cilj je zato gostitelj, obroc je na okvirju, OK je en pravi dotik na potrditveno polje.
            // Izziva ne resujemo: dotik sprozi samo uporabnikov OK (triggerNativeTap brez tipke daljinca zavrne).
            var IZZIV_IZBIRNIK = 'input[name="cf-turnstile-response"], .cf-turnstile, [id^="cf-chl-widget-"]';
            var izzivi = [];
            function gostiteljIzziva(el) {
                var p = el, g = 0;
                while (p && p.nodeType === 1 && g++ < 6) {
                    var r = p.getBoundingClientRect();
                    if (r.width >= 40 && r.height >= 20) return p;
                    p = p.parentElement;
                }
                return null;
            }
            function najdiIzzive(doc) {
                var out = [], sez;
                try { sez = doc.querySelectorAll(IZZIV_IZBIRNIK); } catch (_) { return out; }
                for (var i = 0; i < sez.length; i++) {
                    var g = gostiteljIzziva(sez[i]);
                    if (!g || g === doc.body || g === doc.documentElement) continue;
                    if (g.getBoundingClientRect().height > 200) continue;
                    // Gnezdeni vsebniki istega preverjanja: ostane najbolj notranji.
                    var dodaj = true;
                    for (var j = out.length - 1; j >= 0; j--) {
                        if (out[j] === g || g.contains(out[j])) { dodaj = false; break; }
                        if (out[j].contains(g)) out.splice(j, 1);
                    }
                    if (dodaj) out.push(g);
                }
                return out;
            }
            // Vidni okvir v gostitelju: normal 300 x 65 (izmerjeno), compact 150 x 140 (po dokumentaciji, ni izmerjeno).
            // Brez Math.min (lahko ga prepise stran in izbere tocko pravega dotika): primerjava z operatorji.
            function okvirIzziva(r) {
                var meja = r.height > 100 ? 150 : 300, w = r.width < meja ? r.width : meja;
                return { left: r.left, top: r.top, width: w, height: r.height, right: r.left + w, bottom: r.top + r.height };
            }
            function tockaIzziva(r) {
                var o = okvirIzziva(r);
                if (o.height > 100) return { x: o.left + o.width / 2, y: o.top + o.height / 2 };
                var pol = o.width / 2;
                return { x: o.left + (22 < pol ? 22 : pol), y: o.top + o.height / 2 };
            }
            // Tretji neodvisni pregled 5. 11. 2026 (N2): brez Array.prototype.indexOf strani (prepis je vsak element naredil za izziv).
            function jeIzziv(el) {
                if (!el) return false;
                for (var i = 0; i < izzivi.length; i++) { if (izzivi[i] === el) return true; }
                return false;
            }
            function okvirCilja(el) {
                var r = el.getBoundingClientRect();
                return jeIzziv(el) ? okvirIzziva(r) : r;
            }
            /* SAFEER_IZZIV_KONEC */

            function isActionable(el) {
                if (!el || el.nodeType !== 1) return false;
                var tag = el.tagName.toUpperCase();
                if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT' || tag === 'HTML' || tag === 'BODY' || tag === 'SVG' || tag === 'PATH') return false;
                var href = (el.getAttribute && el.getAttribute('href')) || '';
                if (href.charAt(0) === '#' || (el.className && ('' + el.className).indexOf('wUrY2b') !== -1)) return false;
                // Povezave za preskok (skip links) za tipkovnico: vidne sele ob fokusu, prekrijejo glavo strani in samo
                // premaknejo fokus drugam (lastnik 1. 11. 2026, rtvslo.si »Pojdite na meni za preklop med portali«).
                var hrefMalo = href.toLowerCase();
                if (hrefMalo.indexOf('javascript:') === 0 && hrefMalo.indexOf('.focus(') !== -1) return false;
                var clsSkok = (el.className || '').toString().toLowerCase();
                if (clsSkok.indexOf('skip-link') !== -1 || clsSkok.indexOf('skip-to') !== -1 || clsSkok.indexOf('skiplink') !== -1 ||
                    clsSkok.indexOf('visually-hidden-focusable') !== -1 || clsSkok.indexOf('sr-only-focusable') !== -1) return false;
                if (isCarouselArrow(el)) return false;

                var p = el;
                var skritPodmeni = false;
                var winW = window.innerWidth || 1920, winH = window.innerHeight || 1080;
                var globina = 0;
                // Najvec 8 prednikov: getComputedStyle za vsak kandidat do <body> je na TV (2 GB) predrago ob vsakem pritisku.
                while (p && p.nodeType === 1 && p !== document.body && p !== document.documentElement && globina++ < 8) {
                    var ps = window.getComputedStyle(p);
                    if (ps.display === 'none' || ps.visibility === 'hidden' || ps.opacity === '0') {
                        skritPodmeni = true; break;
                    }
                    var pr = p.getBoundingClientRect();
                    var pTag = p.tagName.toLowerCase();
                    var pCls = (p.className || '').toLowerCase();
                    var isMenu = pTag === 'ul' || pTag === 'menu' || pTag === 'nav' || pCls.indexOf('menu') !== -1 || pCls.indexOf('subcategories') !== -1 || p.hasAttribute('aria-haspopup');
                    if (isMenu) {
                        // Samo velikost 0: seznam pod robom zaslona ali vrtiljak desno je dosegljiv s premikom (pregled 1. 11. 2026).
                        if (pr.width === 0 || pr.height === 0) { skritPodmeni = true; break; }
                    }
                    p = p.parentElement;
                }
                if (skritPodmeni) return false;
                if (tag === 'IFRAME') {
                    var roOkvir = el.getBoundingClientRect(), soOkvir = window.getComputedStyle(el);
                    if (soOkvir.display === 'none' || soOkvir.visibility === 'hidden' || soOkvir.opacity === '0') return false;
                    var imeOkvirja = (el.id || '') + ' ' + (el.getAttribute('name') || '') + ' ' + (el.getAttribute('src') || '');
                    return jeGumbOkvir(roOkvir.width, roOkvir.height, winW, winH, imeOkvirja);
                }

                // Lastnik 1. 11. 2026 (rtvslo.si, »Televizija ▾«): povezava in poleg nje gumb s puscico za podmeni
                // (aria-controls) sta ena postavka - cilj je samo povezava; podmeni ima ista mesta kot stran povezave.
                if (tag === 'BUTTON' && el.hasAttribute('aria-controls') && el.hasAttribute('aria-expanded')) {
                    var prej = el.previousElementSibling;
                    if (prej && prej.tagName && prej.tagName.toUpperCase() === 'A' && prej.getAttribute('href')) return false;
                }

                var ro = (el.getAttribute('role') || '').toLowerCase();
                var ti = el.getAttribute('tabindex');
                if (ro === 'menuitem' || (ti !== null && ti !== '' && ti !== '-1')) {
                    var inLinks = el.querySelectorAll('a[href]');
                    var visibleLinks = [];
                    for (var li = 0; li < inLinks.length; li++) {
                        var lr = inLinks[li].getBoundingClientRect();
                        var lps = window.getComputedStyle(inLinks[li]);
                        if (lr.width > 0 && lr.height > 0 && lps.display !== 'none' && lps.visibility !== 'hidden' && lps.opacity !== '0') {
                            visibleLinks.push(inLinks[li]);
                        }
                    }
                    if (visibleLinks.length === 1) {
                        var tEl = ((el.innerText || el.textContent || '') + '').replace(/\s+/g, ' ').trim().toLowerCase();
                        var tA = ((visibleLinks[0].innerText || visibleLinks[0].textContent || '') + '').replace(/\s+/g, ' ').trim().toLowerCase();
                        if (tEl === tA || tEl.indexOf(tA) !== -1) {
                            return false;
                        }
                    }
                }
                
                // Izloči zgolj tekstovne elemente in časovne oznake znotraj kartic (čas je del celotnega polja vsebine)
                if (tag === 'SPAN' || tag === 'TIME' || tag === 'P' || tag === 'H1' || tag === 'H2' || tag === 'H3' || tag === 'H4' || tag === 'LABEL' || tag === 'SMALL' || tag === 'EM' || tag === 'STRONG') {
                    if (!el.hasAttribute('onclick') && el.getAttribute('role') !== 'button' && el.tagName !== 'A') {
                        return false;
                    }
                }
                
                var cls = (el.className || '').toString().toLowerCase();
                if (jeNogaAliPiskotki(el)) return false;
                if (jePotVZivo() && cls.indexOf('channel-container') !== -1 && cls.indexOf('item--event') === -1) return false;
                if (cls.indexOf('channellists') !== -1 || cls.indexOf('icon-p24_channellist') !== -1) return false;
                // Casovne oznake, trajanje, znacke v karticah niso cilji - pravi gumbi, povezave in polja pa so vedno.
                // Lastnik 30. 10. 2026 (claude.ai prijava): Tailwind razredi »duration-200«, »transition« so izlocili
                // gumbe »Continue with Google« in polje za e-posto; daljinec je skocil naravnost na Privacy Policy.
                var pravGumb = tag === 'A' || tag === 'BUTTON' || tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT';
                if (!pravGumb && (cls.indexOf('time') !== -1 || cls.indexOf('duration') !== -1 || cls.indexOf('badge') !== -1 || cls.indexOf('progress') !== -1 || cls.indexOf('subtitle') !== -1)) {
                    if (!el.hasAttribute('onclick') && el.getAttribute('role') !== 'button') {
                        return false;
                    }
                }

                var rect = el.getBoundingClientRect();
                if (rect.width < 18 || rect.height < 14) return false;
                var winW = window.innerWidth || 1920;
                var winH = window.innerHeight || 1080;
                var clsCheck = (el.className || '').toString().toLowerCase();
                var playTxtEarly = ((el.innerText || el.textContent || '') + '').replace(/\s+/g, ' ').trim().toLowerCase();
                var isPlayActEarly = clsCheck.indexOf('action-list') !== -1 ||
                    clsCheck.indexOf('promo-carousel__button') !== -1 ||
                    (playTxtEarly.length > 0 && playTxtEarly.length <= 64 && (
                        playTxtEarly.indexOf('predvajaj v živo') !== -1 || playTxtEarly.indexOf('predvajaj v zivo') !== -1 ||
                        playTxtEarly.indexOf('glej zdaj') !== -1 || playTxtEarly.indexOf('glej oddajo') !== -1
                    ));
                if (isPlayActEarly) return true;
                if (clsCheck.indexOf('menu-items-wrapper') !== -1) return false;
                if (clsCheck.indexOf('home-link') !== -1) {
                    var homeTxt = ((el.innerText || el.textContent || '') + '').replace(/\s+/g, ' ').trim().toLowerCase();
                    if (homeTxt.indexOf('za vas') === -1) return false;
                }
                if (clsCheck.indexOf('slick-slider') !== -1 || clsCheck.indexOf('promo-carousel') !== -1) return false;
                if (clsCheck.indexOf('icon-p24_search') !== -1) return true;
                if (tag === 'LI' && (' ' + clsCheck + ' ').indexOf(' search ') !== -1) return true;
                if (clsCheck.indexOf('search-card') !== -1 || clsCheck.indexOf('shield-card') !== -1 ||
                    clsCheck.indexOf('clock-widget') !== -1 || clsCheck.indexOf('engine-chips') !== -1 ||
                    clsCheck.indexOf('favorites-grid') !== -1) {
                    return false;
                }
                if (rect.width > winW * 0.48 || rect.height > winH * 0.42) {
                    var wideTxt = ((el.innerText || el.textContent || '') + '').toLowerCase();
                    var widePlay = clsCheck.indexOf('action-list') !== -1 || wideTxt.indexOf('predvajaj') !== -1 || wideTxt.indexOf('glej zdaj') !== -1;
                    if (!widePlay && clsCheck.indexOf('item--event') === -1 && clsCheck.indexOf('content-carousel__item') === -1 && clsCheck.indexOf('channel-container') === -1) {
                        if (tag !== 'A' && tag !== 'BUTTON' && tag !== 'INPUT' && tag !== 'TEXTAREA' &&
                            !jeSirokGumb(el, rect, winH)) return false;
                    }
                }
                
                var style = window.getComputedStyle(el);
                if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return false;

                var playTxt = ((el.innerText || el.textContent || '') + '').replace(/\s+/g, ' ').trim().toLowerCase();
                var isPlayAct = clsCheck.indexOf('action-list') !== -1 ||
                    (playTxt.length > 0 && playTxt.length <= 64 && (
                    playTxt.indexOf('predvajaj v živo') !== -1 || playTxt.indexOf('predvajaj v zivo') !== -1 ||
                    playTxt.indexOf('glej zdaj') !== -1 || playTxt.indexOf('glej oddajo') !== -1));
                if (isPlayAct) return true;

                if (cls.indexOf('content-carousel__item') !== -1) return true;
                if (tag === 'A' || tag === 'BUTTON' || tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true;
                if (el.hasAttribute('onclick') || el.hasAttribute('data-ved')) return true;
                // tabindex="-1" pomeni "dosegljiv samo iz kode, ne za uporabnika" - tak
                // element (npr. vsebnik prekrivnega okna) ni cilj za daljinec.
                if (el.hasAttribute('tabindex') &&
                    ((el.getAttribute('tabindex') || '').trim() !== '-1')) return true;
                if (el.getAttribute('role') === 'button' || el.getAttribute('role') === 'link' || el.getAttribute('role') === 'tab') return true;
                if (cls.indexOf('item--event') !== -1) return true;
                
                // TV Grid kartice, celotni programski bloki, filmi, epizode, EPG vrstice, drsniki
                if (cls.indexOf('card') !== -1 || cls.indexOf('tile') !== -1 || cls.indexOf('item') !== -1 || 
                    cls.indexOf('channel') !== -1 || cls.indexOf('poster') !== -1 || cls.indexOf('program') !== -1 || 
                    cls.indexOf('epg') !== -1 || cls.indexOf('slider') !== -1 || cls.indexOf('slick') !== -1 || 
                    cls.indexOf('media') !== -1 || cls.indexOf('thumb') !== -1 || cls.indexOf('movie') !== -1 || 
                    cls.indexOf('watch') !== -1 || cls.indexOf('show') !== -1) {
                    return true;
                }
                
                return false;
            }

            // Pregled Groka 5. 11. 2026 (najdba 6): predpomnilnik je bil na window - stran je z njim dolocala cilje daljinca.
            var kandPredpomnilnik = null, kandCas = 0;
            function getCandidates() {
                var nowMs = casZdaj();
                if (kandPredpomnilnik && nowMs - kandCas >= 0 && nowMs - kandCas < 80) {
                    return kandPredpomnilnik;
                }
                var t0 = 0;
                try { t0 = performance.now(); } catch (_) {}
                var found = odstraniNedosegljive(getCandidatesFresh());
                // #region agent log
                // Pregled fokusa 5. 11. 2026: zbiranje ob vsaki tipki je mozno ozko grlo - najprej merimo (brez spremembe vedenja).
                try {
                    window._safeerDbgKandN = (window._safeerDbgKandN || 0) + 1;
                    if (window._safeerDbgKandN <= 20) {
                        window._safeerDbg('H54', 'tv_spatial.js:kandidati', 'trajanje', {
                            n: found.length, ms: Math.round((performance.now() - t0) * 10) / 10 });
                    }
                } catch (_) {}
                // #endregion
                kandPredpomnilnik = found;
                kandCas = nowMs;
                return found;
            }
            /* SAFEER_STOLPEC_ZACETEK */
            function prekrivaStolpec(a, b) {
                var p = Math.min(a.right, b.right) - Math.max(a.left, b.left), ozja = Math.min(a.width, b.width);
                return ozja > 0 && p >= ozja * 0.5;
            }
            /* SAFEER_STOLPEC_KONEC */
            /* SAFEER_PODREJENA_POVEZAVA_ZACETEK */
            // Lastnik 5. 11. 2026 17:49 (TV, rtvslo.si, kartica novice »Mikrodrama / Moja dva fanta ...«): oznaka rubrike je
            // svoja povezava in je bila samostojen korak pred naslovom clanka - dva fokusa na eni kartici, uporabnik ni vedel,
            // kateri odpre vsebino. Kratka povezava ob vecji povezavi na drug naslov v isti kartici (naslov h1-h6, article, li,
            // card, teaser) ni samostojen cilj daljinca; cilj je naslov clanka. Meniji in seznami povezav (nav, menu, tablist,
            // podmeni v drugem li) ostanejo nespremenjeni.
            var KARTICA_POVEZAV = 'h1, h2, h3, h4, h5, h6, article, li, [class*="card"], [class*="teaser"]';
            function jePodrejenaPovezava(mala, velika) {
                var tm = mala.besedilo.length, tv = velika.besedilo.length;
                return !!mala.href && !!velika.href && mala.href !== velika.href && tm > 0 && tm <= 40 && tv >= 15 && tv >= 2 * tm;
            }
            /* SAFEER_PODREJENA_POVEZAVA_KONEC */
            function opisPovezave(el) {
                if (!el || (el.tagName || '').toUpperCase() !== 'A') return null;
                var h = ((el.getAttribute && el.getAttribute('href')) || '') + '';
                if (!h || h.charAt(0) === '#' || /^javascript:/i.test(h)) return null;
                return { href: h, besedilo: ((el.innerText || el.textContent || '') + '').replace(/\s+/g, ' ').trim() };
            }
            function istaKarticaPovezav(velika, kartica) {
                var kv = null;
                try { kv = velika.closest(KARTICA_POVEZAV); } catch (_) {}
                if (kv === kartica) return true;
                // Rubrika nad naslovom (v article ali li), naslov v h1-h6 iste kartice.
                return !!kv && /^H[1-6]$/.test((kv.tagName || '').toUpperCase()) && kartica.contains(kv);
            }
            function brezPodrejenihPovezav(seznam) {
                var out = [];
                for (var i = 0; i < seznam.length; i++) {
                    var el = seznam[i], om = opisPovezave(el), izpusti = false;
                    if (om) {
                        var kartica = null, vMeniju = false;
                        try {
                            kartica = el.closest(KARTICA_POVEZAV);
                            vMeniju = !!el.closest('nav, [role="navigation"], [role="menu"], [role="menubar"], [role="tablist"]');
                        } catch (_) {}
                        if (kartica && !vMeniju) {
                            for (var j = 0; j < seznam.length && !izpusti; j++) {
                                if (j === i || !kartica.contains(seznam[j])) continue;
                                var ov = opisPovezave(seznam[j]);
                                if (ov && jePodrejenaPovezava(om, ov) && istaKarticaPovezav(seznam[j], kartica)) izpusti = true;
                            }
                        }
                    }
                    if (!izpusti) out.push(el);
                }
                return out;
            }

            function getCandidatesFresh() {
                    if ((location.href || '').indexOf('brave_home') !== -1) {
                        var homeEls = document.querySelectorAll('.hero-card, .engine-chip, .favorite-tile, .edit-chip, .search-input, .search-btn');
                        var homeOut = [];
                        for (var hi = 0; hi < homeEls.length; hi++) homeOut.push(homeEls[hi]);
                        if (homeOut.length) return homeOut;
                    }
                var all = document.querySelectorAll('a, button, input, textarea, select, [role="button"], [role="link"], [role="tab"], [tabindex], [data-ved], [onclick], .video-play-button, [class*="card"], [class*="tile"], [class*="item"], [class*="channel"], [class*="poster"], [class*="program"], [class*="epg"], [class*="movie"], [class*="media"], [class*="slick-slide"], article, li.search, .icon-p24_search, [class*="icon-p24_search"], iframe');
                var raw = [];
                for (var i = 0; i < all.length; i++) {
                    var el = all[i];
                    if (isActionable(el)) {
                        var cardRoot = findCardContainer(el);
                        var finalEl = cardRoot || el;
                        if (raw.indexOf(finalEl) === -1) {
                            raw.push(finalEl);
                        }
                    }
                }
                
                // Keep .item--event tiles; drop wrapping .livetv-griditem parents
                var candidates = [];
                for (var j = 0; j < raw.length; j++) {
                    var item = raw[j];
                    var itemCls = ((item.className || '') + '').toLowerCase();
                    var isNested = false;
                    var dropWrapper = false;
                    for (var k = 0; k < raw.length; k++) {
                        if (j === k) continue;
                        if (raw[k].contains(item)) {
                            // Pravi cilj v ovitku po razredu ostane, odpade ovitek (SAFEER_PRAVI_CILJ).
                            if (jePravCilj(item) && jeOvitekPoRazredu(raw[k])) continue;
                            var pcls = ((raw[k].className || '') + '').toLowerCase();
                            if (itemCls.indexOf('item--event') !== -1 && pcls.indexOf('livetv-griditem') !== -1) {
                                continue;
                            }
                            if (itemCls.indexOf('content-carousel__item') !== -1 && pcls.indexOf('slick') !== -1) {
                                continue;
                            }
                            isNested = true;
                            break;
                        }
                        if (item.contains(raw[k])) {
                            if (jeOvitekPoRazredu(item) && jePravCilj(raw[k])) { dropWrapper = true; break; }
                            var cclsK = ((raw[k].className || '') + '').toLowerCase();
                            if (itemCls.indexOf('livetv-griditem') !== -1 && itemCls.indexOf('item--event') === -1 && cclsK.indexOf('item--event') !== -1) {
                                dropWrapper = true;
                                break;
                            }
                            if (itemCls.indexOf('slick') !== -1 && cclsK.indexOf('content-carousel__item') !== -1) {
                                dropWrapper = true;
                                break;
                            }
                        }
                    }
                    if (!isNested && !dropWrapper) {
                        if (jeNogaAliPiskotki(item)) {
                            continue;
                        }
                        if (itemCls.indexOf('slick-slide') !== -1 && itemCls.indexOf('item--event') === -1 &&
                            itemCls.indexOf('content-carousel__item') === -1) {
                            continue;
                        }
                        if (jePotVZivo() && itemCls.indexOf('channel-container') !== -1 && itemCls.indexOf('item--event') === -1) {
                            continue;
                        }
                        if (itemCls.indexOf('channellists') !== -1) {
                            continue;
                        }
                        candidates.push(item);
                    }
                }
                // En fokus na kartici clanka: rubrika ob naslovu ni samostojen cilj (SAFEER_PODREJENA_POVEZAVA).
                candidates = brezPodrejenihPovezav(candidates);
                // Preverjanje v zaprtem shadow DOM (SAFEER_IZZIV): cilj je gostitelj, tudi ce ga isActionable ne pozna.
                izzivi = najdiIzzive(document);
                for (var iz = 0; iz < izzivi.length; iz++) {
                    var vsebuje = -1;
                    for (var c = 0; c < candidates.length; c++) {
                        if (candidates[c] === izzivi[iz] || candidates[c].contains(izzivi[iz]) || izzivi[iz].contains(candidates[c])) { vsebuje = c; break; }
                    }
                    if (vsebuje === -1) candidates.push(izzivi[iz]);
                    else candidates[vsebuje] = izzivi[iz];
                }
                return candidates;
            }

            /* SAFEER_UPORABEN_CILJ_ZACETEK */
            // Pregled fokusa 5. 11. 2026: izbira ali fokus strani na elementu z opacity 0, aria-hidden, inert, zunaj pogleda
            // ali za modalnim oknom je bila izhodisce puscic in cilj OK (neviden element). Tak element ni izhodisce: zacnemo z
            // vidnim. lahko: brez iskanja modalnega okna (drsenje, sprememba velikosti - samo obroc).
            function jeProsojen(el) {
                var p = el, g = 0;
                while (p && p.nodeType === 1 && g++ < 30) {
                    var st;
                    try { st = window.getComputedStyle(p); } catch (_) { return false; }
                    var o = parseFloat(st.opacity);
                    if (!isNaN(o) && o <= 0.01) return true;
                    if (st.display === 'none' || (p === el && st.visibility === 'hidden')) return true;
                    p = p.parentElement;
                }
                return false;
            }
            function jeUporaben(el, lahko) {
                if (!el || el.isConnected === false) return false;
                try {
                    var r = el.getBoundingClientRect();
                    if (r.width < 8 || r.height < 8) return false;
                } catch (_) { return false; }
                if (jeSkritZaFokus(el) || jeProsojen(el) || jeZunajPogleda(el)) return false;
                if (!lahko) {
                    var okno = najdiPrekrivnoOkno();
                    if (okno && !okno.contains(el)) return false;
                }
                return true;
            }
            /* SAFEER_UPORABEN_CILJ_KONEC */

            function getActiveElement(lahko) {
                var current = izbrani();
                if (current && jeUporaben(current, lahko)) return current;
                var act = document.activeElement;
                if (act && act !== document.body && act !== document.documentElement && isActionable(act) && jeUporaben(act, lahko)) return act;
                return null;
            }

            // Pregled Groka 5. 11. 2026 (najdba 7): ni vec na window - nihce je ne klice, stran pa je z njo premikala izbiro.
            var zagotoviFokusMedija = function() {
                try {
                    var cur = izbrani() || getActiveElement();
                    function jeMenijskiElement(el) {
                        if (!el) return false;
                        var cls = ((el.className || '') + '').toLowerCase();
                        if (cls.indexOf('menu-items-wrapper') !== -1) return false;
                        if (cls.indexOf('logo') !== -1 && cls.indexOf('menu-item') === -1) return false;
                        if (cls.indexOf('livetv-link') !== -1 || cls.indexOf('menu-item') !== -1) return true;
                        try {
                            if (el.closest && el.closest('.menu, #csh__menu_bar, .menu-items-wrapper')) return true;
                        } catch (_) {}
                        var tx = ((el.innerText || el.textContent || '') + '').replace(/\s+/g, ' ').trim().toLowerCase();
                        if (tx === 'za vas' || tx === 'tv v živo' || tx === 'tv v zivo' || tx === 'filmi' ||
                            tx.indexOf('knjižnic') !== -1 || tx.indexOf('knjiznic') !== -1 || tx === 'več' || tx === 'vec') return true;
                        return false;
                    }
                    if (cur) {
                        var keepCls = ((cur.className || '') + '').toLowerCase();
                        if (keepCls.indexOf('item--event') !== -1 || keepCls.indexOf('content-carousel__item') !== -1 ||
                            keepCls.indexOf('action-list') !== -1 || jeGumbDejanja(cur) || jeMenijskiElement(cur)) {
                            highlightElement(cur);
                            return getActiveElement();
                        }
                    }
                    if (cur && jeMenijskiElement(cur) && window._safeer_medij_did_focus) {
                        // #region agent log
                        try { window._safeerDbg('H120', 'tv_remote_nav.js:ensure_focus', 'keep menu', { cls: ((cur.className || '') + '').slice(0, 60) }); } catch (_) {}
                        // #endregion
                        highlightElement(cur);
                        return getActiveElement();
                    }
                    if (!window._safeer_medij_did_focus) {
                        var pathEf = (location.pathname || '').toLowerCase();
                        if (pathEf.indexOf('/event') !== -1) {
                            var playFirst = izberiGumbPredvajanja();
                            if (playFirst) {
                                highlightElement(playFirst);
                                var playGot = getActiveElement();
                                // #region agent log
                                try {
                                    window._safeerDbg('H261', 'tv_remote_nav.js:ensure_focus', 'play first', {
                                        got: !!(playGot),
                                        cls: ((playFirst.className || '') + '').slice(0, 70),
                                        act: !!isActionable(playFirst)
                                    });
                                } catch (_) {}
                                // #endregion
                                return playGot;
                            }
                        } else if (pathEf.indexOf('/livetv') === -1) {
                            var homePlay = izberiGumbPredvajanja();
                            if (homePlay) {
                                highlightElement(homePlay);
                                return getActiveElement();
                            }
                        }
                    }
                    if (!window._safeer_medij_did_focus && cur && jeMenijskiElement(cur)) {
                        // #region agent log
                        try { window._safeerDbg('H263', 'tv_remote_nav.js:ensure_focus', 'skip initial menu', { cls: ((cur.className || '') + '').slice(0, 60) }); } catch (_) {}
                        // #endregion
                        cur = null;
                    }
                    if (cur && jeNogaAliPiskotki(cur)) {
                        clearActive();
                        cur = null;
                    }
                    if (cur) {
                        var cclsMap = ((cur.className || '') + '').toLowerCase();
                        if (cclsMap.indexOf('livetv-griditem') !== -1 && cclsMap.indexOf('item--event') === -1) {
                            var childEv = cur.querySelector('.item.item--event, .item--event');
                            if (childEv) {
                                // #region agent log
                                try {
                                    window._safeerDbg('H95', 'tv_remote_nav.js:ensure_focus', 'remap griditem', {
                                        from: cclsMap.slice(0, 60),
                                        to: ((childEv.className || '') + '').slice(0, 70)
                                    });
                                } catch (_) {}
                                // #endregion
                                cur = childEv;
                            }
                        }
                        if (jePotVZivo() && cclsMap.indexOf('channel-container') !== -1 && cclsMap.indexOf('item--event') === -1) {
                            var mappedProg = livetvProgramFromHeader(cur);
                            // #region agent log
                            try {
                                window._safeerDbg('H301', 'tv_remote_nav.js:ensure_focus', 'skip channel logo', {
                                    got: !!mappedProg,
                                    to: mappedProg ? ((mappedProg.innerText || '') + '').replace(/\s+/g, ' ').trim().slice(0, 40) : ''
                                });
                            } catch (_) {}
                            // #endregion
                            if (mappedProg) cur = mappedProg;
                            else {
                                clearActive();
                                cur = null;
                            }
                        }
                    }
                    if (cur) {
                        var cr0 = cur.getBoundingClientRect();
                        var ccls = ((cur.className || '') + '').toLowerCase();
                        var ctx = ((cur.innerText || cur.textContent || '') + '').replace(/\s+/g, ' ').trim().toLowerCase();
                        var isPlayAction = ccls.indexOf('action-list') !== -1 ||
                            ctx.indexOf('predvajaj v živo') !== -1 || ctx.indexOf('predvajaj v zivo') !== -1 ||
                            ctx.indexOf('glej zdaj') !== -1 || ctx.indexOf('glej oddajo') !== -1 ||
                            ctx.indexOf('predvajaj') === 0;
                        var isProg = ccls.indexOf('item--event') !== -1 || jeVsebinskaPloscica(cur);
                        var isLogoOnly = ccls.indexOf('logo') !== -1 && ctx.indexOf('za vas') === -1;
                        var tooBig = cr0.width > 700 && !isProg;
                        var off = !jeMenijskiElement(cur) && (cr0.top < 48 || cr0.bottom < 40 || cr0.top > ((window.innerHeight || 1080) - 40) || cr0.right < 40 || cr0.left > ((window.innerWidth || 1920) - 40));
                        if (isPlayAction && ctx.length <= 64) {
                            // #region agent log
                            try { window._safeerDbg('H221', 'tv_remote_nav.js:ensure_focus', 'keep play action', { cls: ccls.slice(0, 70) }); } catch (_) {}
                            // #endregion
                            highlightElement(cur);
                            return getActiveElement();
                        }
                        if (isLogoOnly || (!isProg && !jeMenijskiElement(cur)) || tooBig || off) {
                            clearActive();
                            cur = null;
                        }
                    }
                    if (cur) {
                        highlightElement(cur);
                        return getActiveElement();
                    }
                    var els = document.querySelectorAll('.item.item--event, .item--event, .livetv-griditem__item, [class*="livetv-griditem"]');
                    var i, el, r, pick = null;
                    for (i = 0; i < els.length; i++) {
                        el = els[i];
                        var pcls = ((el.className || '') + '').toLowerCase();
                        if (pcls.indexOf('livetv-griditem') !== -1 && pcls.indexOf('item--event') === -1) {
                            var childPick = el.querySelector('.item.item--event, .item--event, .livetv-griditem__item');
                            if (childPick) el = childPick;
                        }
                        r = el.getBoundingClientRect();
                        if (r.width < 80 || r.height < 50) continue;
                        if (r.top < 48 || r.bottom > ((window.innerHeight || 1080) - 8)) continue;
                        pick = el;
                        break;
                    }
                    if (!pick) pick = document.getElementById('csh__first_item') || (els[0] || null);
                    if (!pick) pick = izberiVsebinskoPloscico();
                    if (pick) highlightElement(pick);
                    return getActiveElement();
                } catch (_) {}
                return null;
            };

            if (location.protocol === 'file:') {
                var calmFocus = document.createElement('style');
                calmFocus.id = 'safeer-calm-focus';
                var jeSafeerDomov = /brave_home\.html$/i.test(location.pathname || '');
                // Domaca stran ima okvir fokusa v svojem slogu (safeer-home.css: rob + senca); nas obris z odmikom je bil
                // drugi okvir okrog kartice (lastnik 5. 11. 2026, TV: dvojni fokus na »RTV SLO«).
                calmFocus.textContent = jeSafeerDomov
                    ? '.safeer-active-card{outline:none!important}:focus,:focus-visible{outline-color:#5de0b0!important}#safeer-focus-target-ring{display:none!important}.safeer-focus-badge{display:none!important}'
                    : '.safeer-active-card{transform:none!important;transition:none!important;outline:3px solid #b5eb8d!important;outline-offset:3px!important;box-shadow:0 0 0 2px #101814!important;background-color:transparent!important}:focus,:focus-visible{outline-color:#b5eb8d!important;box-shadow:none!important}#safeer-focus-target-ring{display:none!important}.safeer-focus-badge{display:none!important}';
                (document.head || document.documentElement).appendChild(calmFocus);
            }

            function getOrCreateFocusRing() {
                var ring = document.getElementById('safeer-focus-target-ring');
                if (!ring) {
                    ring = document.createElement('div');
                    ring.id = 'safeer-focus-target-ring';
                    ring.innerHTML = '<span class="safeer-focus-badge">⚡</span>';
                    (document.body || document.documentElement).appendChild(ring);
                }
                return ring;
            }

            function updateFocusRing(el) {
                // The outline follows the element without a second animated DOM overlay.
                if (location.protocol === 'file:') return;
                try {
                    var ring = getOrCreateFocusRing();
                    if (document.documentElement.classList.contains('safeer-medij-fs')) {
                        ring.classList.remove('active');
                        ring.style.display = 'none';
                        return;
                    }
                    ring.style.display = '';
                    if (!el) {
                        ring.classList.remove('active');
                        return;
                    }
                    var r = okvirCilja(el);
                    if (r.width === 0 && r.height === 0) {
                        ring.classList.remove('active');
                        return;
                    }
                    var pad = 4;
                    ring.style.top = (r.top - pad) + 'px';
                    ring.style.left = (r.left - pad) + 'px';
                    ring.style.width = (r.width + pad * 2) + 'px';
                    ring.style.height = (r.height + pad * 2) + 'px';
                    ring.classList.add('active');
                } catch(_) {}
            }

            function clearActive() {
                var prevs = document.querySelectorAll('.safeer-active-card');
                for (var i = 0; i < prevs.length; i++) {
                    prevs[i].classList.remove('safeer-active-card');
                }
                nastaviIzbrani(null);
                updateFocusRing(null);
            }

            // Obroc mora slediti svojemu cilju tudi takrat, ko se stran ne premakne, ampak
            // se preuredi: razdelek se razpre, obvestilo izgine, slika se nalozi. Doslej se
            // je popravil samo ob drsenju in spremembi velikosti okna, zato je obvisel na
            // starem mestu - na televizorju cez besedilo, ki ga ni mogoce pritisniti.
            // In ce cilj izgine, obroc umaknemo, namesto da bi kazal v prazno.
            var zadnjiOkvirObroca = null;

            function spremljajCiljObroca() {
                try {
                    var el = izbrani();
                    if (!el) {
                        if (zadnjiOkvirObroca !== null) {
                            zadnjiOkvirObroca = null;
                            updateFocusRing(null);
                        }
                        return;
                    }
                    var r = el.getBoundingClientRect();
                    var st = window.getComputedStyle(el);
                    // Pregled fokusa 5. 11. 2026: tudi prosojen (opacity 0), aria-hidden ali inert cilj ni vec izbran.
                    if (st.display === 'none' || st.visibility === 'hidden' ||
                        (r.width < 2 && r.height < 2) || jeSkritZaFokus(el) || jeProsojen(el)) {
                        el.classList.remove('safeer-active-card');
                        nastaviIzbrani(null);
                        zadnjiOkvirObroca = null;
                        updateFocusRing(null);
                        return;
                    }
                    var okvir = Math.round(r.top) + ':' + Math.round(r.left) + ':' +
                                Math.round(r.width) + ':' + Math.round(r.height);
                    if (okvir !== zadnjiOkvirObroca) {
                        zadnjiOkvirObroca = okvir;
                        updateFocusRing(el);
                    }
                } catch (_) {}
            }

            setInterval(spremljajCiljObroca, 250);

            /* SAFEER_FOKUS_STRANI_ZACETEK */
            // Lastnik 2. 11. 2026 19:09 (claude.ai/new na TV): »Search« v stranski vrstici odpre pogovorno okno in stran
            // sama da fokus njegovemu polju (tipkovnica TV gor). Nas obroc je ostal na starem cilju (stranska vrstica) in
            // segal cez okno - dva fokusa. Ko stran sama premakne fokus (ne mi), mu sledimo: na polje za vnos obroc skrijemo
            // (tipkovnica je vidna oznaka, naslednja puscica gre od polja), na tuj okvir prav tako (svoj okvir ima sam),
            // drugam (gumb v oknu) gre obroc za fokusom. Navigacija ostane v odprtem oknu (najdiPrekrivnoOkno).
            function jeVnosnoPolje(el) {
                if (!el || el.nodeType !== 1) return false;
                var tag = (el.tagName || '').toUpperCase();
                if (tag === 'TEXTAREA' || el.isContentEditable) return true;
                var ro = ((el.getAttribute && el.getAttribute('role')) || '').toLowerCase();
                if (ro === 'textbox' || ro === 'searchbox' || ro === 'combobox') return true;
                if (tag !== 'INPUT') return false;
                var ti = ((el.getAttribute && el.getAttribute('type')) || 'text').toLowerCase();
                return ['button', 'submit', 'reset', 'checkbox', 'radio', 'range', 'color', 'file', 'image', 'hidden'].indexOf(ti) === -1;
            }
            // f: nas (fokus je na nasem cilju ali v njem), vnos (polje za vnos), okvir (tuj okvir), cilj (element je
            // za daljinec cilj - isActionable; vsebnik okna s tabindex=-1 ni, takrat obroc samo umaknemo).
            function odzivNaFokus(f) {
                // Pregled Gemini 3. 11. 2026: polje, ki ga je izbral daljinec in je po OK dobilo fokus (tipkovnica TV), je
                // bilo »nase« in obroc je ostal cez polje - dva kazalca. Polje za vnos ima zato prednost pred »nas«.
                if (f.vnos) return 'skrij';
                if (f.nas) return 'nic';
                if (f.okvir || !f.cilj) return 'skrij';
                return 'sledi';
            }
            // Odziv na fokus, ki ga je premaknila stran (p: pocisti = clearActive, oznaci = highlightElement,
            // jeCilj = isActionable; podani, da je odlocitev preizkusljiva brez brskalnika).
            function naFokusStrani(t, doc, p) {
                if (!t || t.nodeType !== 1 || t === doc.body || t === doc.documentElement) return 'nic';
                var akt = doc.querySelector('.safeer-active-card');
                var odziv = odzivNaFokus({
                    nas: !!akt && (akt === t || akt.contains(t)),
                    vnos: jeVnosnoPolje(t),
                    okvir: (t.tagName || '').toUpperCase() === 'IFRAME',
                    cilj: p.jeCilj(t)
                });
                // Stran je dala fokus vsebniku okna (ni cilj): izbira gre na prvi gumb v njem (SAFEER_PLAST_IZBIRE).
                if (odziv === 'skrij' && !jeVnosnoPolje(t) && p.vstopi && p.vstopi(t)) return 'vstopi';
                if (odziv === 'skrij') p.pocisti();
                else if (odziv === 'sledi') {
                    p.oznaci(t);
                    // highlightElement cilj izven pogleda zavrne: star obroc vseeno ne sme ostati.
                    var zdaj = doc.querySelector('.safeer-active-card');
                    if (!zdaj || !(zdaj === t || zdaj.contains(t))) p.pocisti();
                }
                return odziv;
            }
            /* SAFEER_FOKUS_STRANI_KONEC */

            // Ko se pojavi okno ali pasica z izbiro (piskotki), gre izbira enkrat vanjo - kot pri modalnem oknu, da
            // uporabnik vidi, kje je. Samo oznaka, brez klika. Ne, ce uporabnik tipka v polje.
            var vstopljenePlasti = [];
            function najdiPlastPiskotkov() {
                var sez;
                // Samo gumbi (pasica caka na odlocitev); povezava »Cookie policy« v pritrjeni glavi ali nogi ni pasica.
                try { sez = document.querySelectorAll('button, [role="button"]'); } catch (_) { return null; }
                for (var i = 0; i < sez.length && i < 400; i++) {
                    var t = ((sez[i].textContent || '') + '').toLowerCase();
                    if (t.indexOf('cookie') === -1 && t.indexOf('piskot') === -1 && t.indexOf('piškot') === -1) continue;
                    var pl = plastIzbire(sez[i], window.getComputedStyle);
                    if (!pl) continue;
                    try { if (pl.closest('header, nav, footer, [role="banner"], [role="navigation"]')) continue; } catch (_) {}
                    var r = pl.getBoundingClientRect();
                    if (r.width >= 120 && r.height >= 40 && !jeZunajPogleda(pl)) return pl;
                }
                return null;
            }
            function vstopiVPlast(okno) {
                if (!okno) return false;
                var akt = izbrani();
                if (akt && okno.contains(akt)) return true;
                var prvi = prviVPlasti(okno, getCandidates());
                if (!prvi) return false;
                // Neodvisni pregled 5. 11. 2026 (najdba 9): samodejni vstop v okno ni izbira daljinca - nikoli potrjena.
                oznaciBrezPotrditve(prvi);
                return true;
            }
            // Lastnik 3. 11. 2026 05:26: »okno se vsiljuje« - izbira je znova in znova skakala v plast (stran jo je na novo
            // izrisala, za nas nova plast) in vsakic podrsala stran. Zdaj najvec enkrat na naslov strani, nikoli v plast s
            // tujim okvirjem (uganka) in nikoli na gumb, ki ga kaj prekriva.
            // Pregled fokusa 5. 11. 2026: klic tece iz casovnika (4,5 s) in opazovalca DOM, torej brez tipke. Kljuc je bil cel
            // naslov (sprememba # ali ?q ga je odklenila) in vsaka lepljiva plast z besedo cookie je vzela izbiro. Zdaj: kljuc je
            // izvor + pot; brez nedavne tipke samo pravo modalno okno (najdiPrekrivnoOkno) in nikoli, ce izbira ze obstaja.
            function kljucNaslova() {
                try { return (location.protocol || '') + '//' + (location.host || location.hostname || '') + (location.pathname || ''); }
                catch (_) { return ''; }
            }
            var vstopNaNaslovu = '';
            function vstopiVNovoPlast() {
                try {
                    if (vstopNaNaslovu === kljucNaslova()) return false;
                    var odTipke = casZdaj() - zadnjaTipkaMs;
                    var poTipki = zadnjaTipkaMs > 0 && odTipke >= 0 && odTipke < SAMODEJNO_PO_TIPKI_MS;
                    if (!poTipki && izbrani()) return false;
                    var okno = najdiPrekrivnoOkno() || (poTipki ? najdiPlastPiskotkov() : null);
                    if (!okno || vstopljenePlasti.indexOf(okno) !== -1) return false;
                    if (jeVnosnoPolje(document.activeElement)) return false;
                    if (okno.querySelector && okno.querySelector('iframe')) return false;
                    if (najdiZaprtoPlast()) return false;
                    var prvi = prviVPlasti(okno, getCandidates());
                    if (!prvi || jePrekrit(prvi)) return false;
                    if (!vstopiVPlast(okno)) return false;
                    vstopNaNaslovu = kljucNaslova();
                    vstopljenePlasti.push(okno);
                    if (vstopljenePlasti.length > 10) vstopljenePlasti.shift();
                    return true;
                } catch (_) { return false; }
            }
            // Najdba 9: vstopiVNovoPlast ni vec na window - nihce zunaj skripta je ne klice, stran pa je z njo med nasim
            // odzivom na tipko nastavila potrjeno izbiro.

            var sledimFokusu = false;
            /* SAFEER_PREPIR_FOKUSA_ZACETEK */
            // Pregled Groka 5. 11. 2026 (najdba 12): past fokusa strani (focusout -> focus() v setTimeout) in nase vracanje fokusa
            // izbrani kartici sta se lahko izmenjevala brez konca (utripajoc okvir, obremenjen procesor TV). Najvec 2 vrnitvi v
            // 1000 ms; nato izbiro pocistimo in fokus ostane strani.
            function smemVrnitiFokus(st, zdaj) {
                if (!st.zac || zdaj < st.zac || zdaj - st.zac > 1000) { st.zac = zdaj || 1; st.n = 0; }
                st.n++;
                return st.n <= 2;
            }
            /* SAFEER_PREPIR_FOKUSA_KONEC */
            var prepirFokusa = { zac: 0, n: 0 };
            document.addEventListener('focusin', function (ev) {
                // Pregled Groka 5. 11. 2026: fokus, ki ga premakne stran (tudi med nasim odzivom na tipko), izbire ne potrdi.
                var biloVTipki = vTipki;
                vTipki = false;
                try { odzoviSeNaFokus(ev); } finally { vTipki = biloVTipki; }
            }, true);
            function odzoviSeNaFokus(ev) {
                // Nas lastni fokus (highlightElement -> uskladiFokus) med odzivom ne sme sproziti novega odziva.
                if (sledimFokusu) return;
                if (!samodejnoDovoljeno('fokus strani') && plastIzbire(ev.target, window.getComputedStyle) !== ev.target) {
                    // CodeRabbit 5. 11. 2026: ce stran sama da fokus polju za vnos, stara kartica ne sme ostati izbrana (smerne
                    // tipke bi zacele pri njej) - izbiro samo pocistimo, nove kartice pa samodejno ne izberemo.
                    if (jeVnosnoPolje(ev.target)) { try { clearActive(); } catch (_) {} return; }
                    // Pregled fokusa 5. 11. 2026: fokus strani je ostal drugje (cian obris), nas obroc na izbrani kartici in OK
                    // je sel na kartico - dva okvirja. En sam fokus: izbrani kartici ga vrnemo (nove kartice ne izberemo).
                    var izb = izbrani();
                    if (izb && izb !== ev.target && !(izb.contains && izb.contains(ev.target))) {
                        // Stran je odprla modalno okno in mu dala fokus: kartica za oknom ni dosegljiva - izbiro pocistimo,
                        // fokus ostane v oknu (njegov obris je edini okvir), fokusa mu ne vzamemo.
                        var oknoF = null;
                        try { oknoF = najdiPrekrivnoOkno(); } catch (_) {}
                        if (oknoF && oknoF.contains(ev.target) && !oknoF.contains(izb)) { try { clearActive(); } catch (_) {} return; }
                        if (!smemVrnitiFokus(prepirFokusa, casZdaj())) { try { clearActive(); } catch (_) {} return; }
                        sledimFokusu = true;
                        try { uskladiFokus(izb, document, istaPlast); } catch (_) {} finally { sledimFokusu = false; }
                    }
                    return;
                }
                sledimFokusu = true;
                try {
                    naFokusStrani(ev.target, document, { pocisti: clearActive, oznaci: highlightElement, jeCilj: isActionable,
                        vstopi: function (t) {
                            var tag = (t.tagName || '').toUpperCase();
                            if (tag === 'IFRAME' || plastIzbire(t, window.getComputedStyle) !== t) return false;
                            return vstopiVPlast(t);
                        } });
                } catch (_) {} finally { sledimFokusu = false; }
            }

            // Pritrjene vrstice strani (glava, spodnja vrstica z gumbi) pokrijejo del pogleda; scrollIntoView
            // zanje ne ve, zato bi izbrani element obstal pod njimi (npr. 24ur.com). Izmerimo, kaj pri vrhu
            // in dnu pogleda stoji pritrjeno, in element pomaknemo v prosti del med njima.
            function pritrjeniRobovi() {
                var w = window.innerWidth || 1920, h = window.innerHeight || 1080;
                var zgoraj = 0, spodaj = h;
                function pritrjenPrednik(e) {
                    var g = 0;
                    while (e && e.nodeType === 1 && g < 25) {
                        try {
                            var p = window.getComputedStyle(e).position;
                            if (p === 'fixed' || p === 'sticky') return e;
                        } catch (_) {}
                        e = e.parentElement; g++;
                    }
                    return null;
                }
                var xs = [0.15, 0.5, 0.85];
                for (var i = 0; i < xs.length; i++) {
                    var x = Math.round(w * xs[i]);
                    var pg = pritrjenPrednik(document.elementFromPoint(x, 2));
                    var pd = pritrjenPrednik(document.elementFromPoint(x, h - 3));
                    if (pg) { var r1 = pg.getBoundingClientRect(); if (r1.top <= 2 && r1.height < h * 0.35) zgoraj = Math.max(zgoraj, r1.bottom); }
                    if (pd) { var r2 = pd.getBoundingClientRect(); if (r2.bottom >= h - 3 && r2.height < h * 0.35) spodaj = Math.min(spodaj, r2.top); }
                }
                return { zgoraj: zgoraj, spodaj: spodaj };
            }

            function odmakniOdPritrjenih(el) {
                try {
                    if (jePritrjen(el)) return;
                    var r = el.getBoundingClientRect(), p = pritrjeniRobovi(), rob = 10;
                    if (r.height > p.spodaj - p.zgoraj - 2 * rob) return;
                    var dy = 0;
                    if (r.bottom > p.spodaj - rob) dy = r.bottom - (p.spodaj - rob);
                    else if (r.top < p.zgoraj + rob) dy = r.top - (p.zgoraj + rob);
                    if (dy) window.scrollBy(0, dy);
                } catch (_) {}
            }
            window._safeer_odmakni = odmakniOdPritrjenih;

            /* SAFEER_EN_FOKUS_ZACETEK */
            // Lastnik 2. 11. 2026 19:05 (claude.ai/new na TV, »dvojni focus«): izbran je bil »Recents« (nasa oznaka), DOM
            // fokus pa je ostal na »Customize«, ki ga nas slog :focus obrobi s cian okvirjem - dva fokusa hkrati. Izbrani
            // element pogosto ni fokusljiv (DIV ovoj), focus() tiho ne naredi nicesar. Zdaj: fokus na element, sicer na
            // povezavo ali gumb v njem; ce ga tudi to ne dobi, prejsnjemu fokus vzamemo (blur). Polje (INPUT, TEXTAREA:
            // tipkovnica TV bi ujela daljinec) in tuj okvir (IFRAME: fokus bi sel v okvir) fokusa ne dobita nikoli.
            // istaPlast(a, b): ali sta a in b v isti plasti izbire ali oknu (neobvezno; pregled fokusa 5. 11. 2026).
            function uskladiFokus(el, doc, istaPlast) {
                if (!el) return;
                var tag = (el.tagName || '').toUpperCase();
                var brezFokusa = tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'IFRAME';
                if (!brezFokusa) {
                    try { el.focus({ preventScroll: true }); } catch (_) {}
                    if (doc.activeElement !== el) {
                        var notri = null;
                        try { notri = el.querySelector('a[href], button, [role="button"], [role="link"], [tabindex]:not([tabindex="-1"])'); } catch (_) {}
                        if (notri) { try { notri.focus({ preventScroll: true }); } catch (_) {} }
                    }
                }
                var akt = doc.activeElement;
                var vElementu = false;
                try { vElementu = akt === el || (!!el.contains && el.contains(akt)); } catch (_) {}
                if (akt && akt !== doc.body && akt !== doc.documentElement && (brezFokusa || !vElementu)) {
                    // Pregled fokusa 5. 11. 2026: blur v odprtem oknu je fokus vrgel na body - okno se je zaprlo ali past fokusa
                    // ga je vrnila na zacetek. V isti plasti fokus ostane (obris strani se ob izbiri ne rise, html.safeer-izbira);
                    // polje za vnos ga izgubi vedno (tipkovnica TV bi ujela daljinec).
                    var aktTag = (akt.tagName || '').toUpperCase();
                    var aktPolje = aktTag === 'INPUT' || aktTag === 'TEXTAREA' || !!akt.isContentEditable;
                    var vIstiPlasti = false;
                    if (!aktPolje && istaPlast) { try { vIstiPlasti = !!istaPlast(akt, el); } catch (_) {} }
                    if (!vIstiPlasti) { try { akt.blur(); } catch (_) {} }
                }
            }
            /* SAFEER_EN_FOKUS_KONEC */
            function istaPlast(a, b) {
                var pa = plastIzbire(a, window.getComputedStyle);
                return !!pa && pa === plastIzbire(b, window.getComputedStyle);
            }

            function highlightElement(el) {
                kandPredpomnilnik = null;
                if (el && jeZunajPogleda(el)) {
                    // Najprej ga poskusimo pridrsati v pogled; sele ce tudi potem ni viden,
                    // cilja ne sprejmemo. Merimo, ne ugibamo po zgradbi strani.
                    try { el.scrollIntoView({ behavior: 'auto', block: 'nearest', inline: 'nearest' }); } catch (_) {}
                    if (jeZunajPogleda(el)) {
                        // #region agent log
                        try {
                            var rz = el.getBoundingClientRect();
                            var prvi = el.parentElement, drs = null, gl = 0;
                            while (prvi && gl < 30 && !drs) {
                                var sp = window.getComputedStyle(prvi);
                                if (sp.overflowX === 'auto' || sp.overflowX === 'scroll') drs = prvi;
                                prvi = prvi.parentElement;
                                gl++;
                            }
                            window._safeerDbgIzvenN = (window._safeerDbgIzvenN || 0) + 1;
                            if (window._safeerDbgIzvenN <= 6) {
                                window._safeerDbg('H51', 'tv_spatial.js:hl', 'cilj izven zaslona zavrnjen', {
                                    tag: (el.tagName || ''),
                                    l: Math.round(rz.left),
                                    r: Math.round(rz.right),
                                    sx: Math.round(window.scrollX || 0),
                                    dsl: Math.round((document.documentElement || {}).scrollLeft || 0),
                                    dsw: Math.round((document.documentElement || {}).scrollWidth || 0),
                                    dcw: Math.round((document.documentElement || {}).clientWidth || 0),
                                    ovl: drs ? Math.round(drs.scrollLeft) : -1,
                                    filter: window._safeerFokusFilter || '?'
                                });
                            }
                        } catch (_) {}
                        // #endregion
                        return false;
                    }
                }
                clearActive();
                if (!el) return false;
                window._safeer_medij_did_focus = true;
                el.classList.add('safeer-active-card');
                nastaviIzbrani(el);
                try { if (((el.className || '') + '').indexOf('item--event') !== -1) el.tabIndex = 0; } catch (_) {}
                var tag = (el.tagName || '').toUpperCase();
                // En sam fokus: DOM fokus gre na izbrani element (ali povezavo v njem) ali pa nikamor (SAFEER_EN_FOKUS).
                uskladiFokus(el, document, istaPlast);
                var hr = el.getBoundingClientRect();
                if (hr.width < 800 && hr.height < 500) {
                    try { el.scrollIntoView({ behavior: 'auto', block: 'nearest', inline: 'nearest' }); } catch (_) {}
                    odmakniOdPritrjenih(el);
                }
                if (document.documentElement.classList.contains('safeer-medij-fs')) updateFocusRing(null);
                else updateFocusRing(el);
                // Diagnostika (lastnik 29. 10. 2026, Facebook na TV: gumbi in stranska vrstica, cez katere gre izbira,
                // ostanejo oznaceni, namigi ostanejo odprti). Brez besedil strani: samo vrsta elementa, vloga in stanje.
                if (/(^|\.)facebook\.com$/i.test(location.hostname || '')) {
                    try {
                        clearTimeout(window._safeerDiagFokus);
                        window._safeerDiagFokus = setTimeout(function() {
                            try {
                                var oz = [], kand = document.querySelectorAll('a,button,[role],[tabindex]');
                                for (var i = 0; i < kand.length && oz.length < 8; i++) {
                                    var e = kand[i], cs = window.getComputedStyle(e);
                                    if (cs.outlineStyle === 'none' || parseFloat(cs.outlineWidth) < 1) continue;
                                    var r = e.getBoundingClientRect();
                                    if (r.width < 2 || r.height < 2) continue;
                                    oz.push({ t: e.tagName, r: e.getAttribute('role') || '', ak: e.classList.contains('safeer-active-card'),
                                        f: e.matches(':focus'), fv: e.matches(':focus-visible'), fw: e.matches(':focus-within'),
                                        h: e.matches(':hover'), oc: cs.outlineColor, w: Math.round(r.width), v: Math.round(r.height) });
                                }
                                var ae = document.activeElement;
                                window._safeerDbg('H300', 'tv_spatial.js:fb', 'oznaceni', {
                                    n: oz.length, oz: oz, akt: ae ? (ae.tagName + ':' + (ae.getAttribute('role') || '')) : '',
                                    jeAkt: ae === el, namigi: document.querySelectorAll('[role=tooltip]').length,
                                    obroc: (document.getElementById('safeer-focus-target-ring') || {}).className || '' });
                            } catch (_) {}
                        }, 400);
                    } catch (_) {}
                }
                // #region agent log
                try {
                    window._safeerDbgHlN = (window._safeerDbgHlN || 0) + 1;
                    if (window._safeerDbgHlN <= 30) {
                        window._safeerDbg('H50', 'tv_remote_nav.js:hl', 'highlight', {
                            tag: tag,
                            cls: ((el.className || '') + '').toString().slice(0, 70),
                            w: Math.round(hr.width),
                            h: Math.round(hr.height),
                            t: Math.round(hr.top),
                            l: Math.round(hr.left)
                        });
                    }
                } catch (_) {}
                // #endregion
                return true;
            }

            // Neodvisni pregled 5. 11. 2026 (najdba 4): _safeer_highlight ni vec izvoz - aplikacija ga ne klice (MainActivity in
            // site_agent.js uporabljata _safeer_skrij_obris, _safeer_vrni_obris in _safeer_izberi_zadnjo_ploscico), stran pa je z
            // njim brez dokaza pocistila izbiro. Izbira je samo iz smeri daljinca (navigirajProstorsko) ali brez potrditve.
            // site_agent.js po zaprtju predvajalnika: izbira nazaj na zadnjo ploscico, ki jo je izbral ta skript (zaprtje) -
            // ne na element iz window._safeer_medij_last_tile, ki ga lahko nastavi stran.
            izvoziZaAplikacijo('_safeer_izberi_zadnjo_ploscico', function () {
                var t = zadnjaPloscica;
                if (!t || !t.isConnected || !jeUporaben(t, true)) return false;
                return oznaciBrezPotrditve(t);
            }, true);
            // MainActivity.poveziObrisStrani: fokus gre iz strani v vrstico z naslovom - obris skrijemo, ob vrnitvi ga vrnemo na
            // isti element. Element hrani zaprtje (prej window._safeerObrisZunaj in querySelector('.safeer-active-card'),
            // oboje lahko nastavi stran - drugi krog pregleda 5. 11. 2026).
            var skritaIzbira = null, skritaPotrjena = false;
            izvoziZaAplikacijo('_safeer_skrij_obris', function () {
                var a = izbrani();
                if (!a) return false;
                skritaIzbira = a;
                skritaPotrjena = izbiraPotrjena;
                highlightElement(null);
                return true;
            });
            izvoziZaAplikacijo('_safeer_vrni_obris', function () {
                var a = skritaIzbira;
                skritaIzbira = null;
                if (!a || !a.isConnected || izbrani() || !jeUporaben(a)) return false;
                var vrnjena = oznaciBrezPotrditve(a);
                if (vrnjena) izbiraPotrjena = skritaPotrjena;
                return vrnjena;
            });

            window.addEventListener('scroll', function() {
                if (window._safeerRingRaf) return;
                window._safeerRingRaf = requestAnimationFrame(function () {
                    window._safeerRingRaf = 0;
                    var curr = izbrani() || getActiveElement(true);
                    if (curr) updateFocusRing(curr);
                });
            }, { passive: true });

            window.addEventListener('resize', function() {
                var curr = izbrani() || getActiveElement(true);
                if (curr) updateFocusRing(curr);
            }, { passive: true });

            function jeProgramskaPloscica(el) {
                if (!el) return false;
                var cls = ((el.className || '') + '').toLowerCase();
                if (cls.indexOf('item--event') !== -1) return false;
                if (cls.indexOf('channel-container') !== -1) return true;
                try {
                    if (el.closest && el.closest('.channel-container, [class*="channel-container"]')) return true;
                } catch (_) {}
                return false;
            }

            function livetvProgramFromHeader(el) {
                if (!el) return null;
                try {
                    var root = (el.closest && el.closest('.livetv-griditem, .zw_column, [class*="livetv-griditem"]')) || el.parentElement;
                    var tile = root ? root.querySelector('.item.item--event, .item--event') : null;
                    if (tile && tile !== el) return tile;
                } catch (_) {}
                try {
                    var sib = el.nextElementSibling;
                    while (sib) {
                        var sc = ((sib.className || '') + '').toLowerCase();
                        if (sc.indexOf('item--event') !== -1) return sib;
                        var inner = sib.querySelector && sib.querySelector('.item.item--event, .item--event');
                        if (inner) return inner;
                        sib = sib.nextElementSibling;
                    }
                } catch (_) {}
                try {
                    var hr = el.getBoundingClientRect();
                    var cx = hr.left + hr.width / 2;
                    var tiles = document.querySelectorAll('.item.item--event, .item--event');
                    var best = null;
                    var bestD = 1e9;
                    for (var i = 0; i < tiles.length; i++) {
                        var tr = tiles[i].getBoundingClientRect();
                        if (tr.width < 80 || tr.height < 50) continue;
                        if (tr.top + 8 < hr.bottom) continue;
                        var dx = Math.abs((tr.left + tr.width / 2) - cx);
                        var dy = tr.top - hr.bottom;
                        if (dx > 90 || dy > 120) continue;
                        var d = dx + dy;
                        if (d < bestD) {
                            bestD = d;
                            best = tiles[i];
                        }
                    }
                    return best;
                } catch (_) {}
                return null;
            }

            function izberiGlavoPrograma() {
                var nodes = document.querySelectorAll('.channel-container, [class*="channel-container"]');
                var i, a, r, pick = null, bestLeft = 1e9;
                for (i = 0; i < nodes.length; i++) {
                    a = nodes[i];
                    r = a.getBoundingClientRect();
                    if (r.width < 80 || r.height < 20) continue;
                    if (r.top < 48 || r.top > 420) continue;
                    if (r.left >= -20 && r.left < bestLeft) {
                        bestLeft = r.left;
                        pick = a;
                    }
                }
                return pick;
            }

            /* SAFEER_TV_PONUDNIK_ZACETEK */
            // Pregled Groka 5. 11. 2026 (najdba 11): skok GOR na »Glej zdaj« ali na meni glave je hevristika za Xplore TV in
            // RTV 365; na drugih straneh (novice) je GOR preskocil vrstice vmes. Samo na teh gostiteljih.
            function jeGostiteljTvPonudnika(host) {
                host = (host || '').toLowerCase();
                var gs = ['xploretv.si', 'rtvslo.si'];
                for (var i = 0; i < gs.length; i++) {
                    if (host === gs[i] || (host.length > gs[i].length && host.slice(-(gs[i].length + 1)) === '.' + gs[i])) return true;
                }
                return false;
            }
            /* SAFEER_TV_PONUDNIK_KONEC */

            function jeMenijskaOznaka(t) {
                t = (t || '').replace(/\s+/g, ' ').trim().toLowerCase();
                if (!t || t.length > 28) return false;
                if (t === 'za vas' || t === 'tv v živo' || t === 'tv v zivo' || t === 'filmi' ||
                    t === 'več' || t === 'vec' || t === 'družina' || t === 'druzina' || t === 'tv vodič' || t === 'tv vodic') return true;
                if (t.indexOf('knjižnic') !== -1 || t.indexOf('knjiznic') !== -1) return true;
                if (t.indexOf('vodič') !== -1 || t.indexOf('vodic') !== -1) return true;
                return false;
            }

            function jeMenijskaPovezava(el) {
                if (!el) return false;
                var cls = ((el.className || '') + '').toLowerCase();
                if (cls.indexOf('menu-items-wrapper') !== -1) return false;
                if (cls.indexOf('logo') !== -1 && cls.indexOf('menu-item') === -1) return false;
                try {
                    var rr = el.getBoundingClientRect();
                    if (rr.top > 150 || rr.height < 8) return false;
                } catch (_) { return false; }
                var t = ((el.innerText || el.textContent || '') + '').replace(/\s+/g, ' ').trim().toLowerCase();
                if (t.length > 28) return false;
                if (jeMenijskaOznaka(t)) return true;
                var href = ((el.getAttribute && el.getAttribute('href')) || '') + '';
                if (jeMenijskaOznaka(t) && /\/livetv|\/movies|\/library|\/gridguide|\/home/.test(href)) return true;
                return false;
            }

            function poberiMenijskePovezave() {
                var nodes = document.querySelectorAll('.menu a, #csh__menu_bar a, .menu-items-wrapper a, a.livetv-link, a.home-link, li.menu-item a');
                var out = [];
                var seen = {};
                for (var i = 0; i < nodes.length; i++) {
                    var a = nodes[i];
                    var r = a.getBoundingClientRect();
                    if (r.width < 24 || r.height < 12) continue;
                    if (r.top > 150) continue;
                    var t = ((a.innerText || a.textContent || '') + '').replace(/\s+/g, ' ').trim().toLowerCase();
                    var cls = ((a.className || '') + '').toLowerCase();
                    if (cls.indexOf('logo') !== -1 && t.indexOf('za vas') === -1) continue;
                    if (!jeMenijskaOznaka(t)) continue;
                    var href = ((a.getAttribute('href') || '') + '').toLowerCase();
                    var key = href + '|' + t;
                    if (seen[key]) continue;
                    seen[key] = true;
                    out.push(a);
                }
                out.sort(function (a, b) {
                    return a.getBoundingClientRect().left - b.getBoundingClientRect().left;
                });
                return out;
            }

            function izberiGumbPredvajanja() {
                var needles = ['predvajaj v živo', 'predvajaj v zivo', 'glej zdaj', 'glej oddajo od začetka', 'glej oddajo'];
                var nodes = document.querySelectorAll('button, a, [role="button"], [class*="action-list"]');
                var best = null;
                var bestPri = 99;
                var i, el, t, r, n, pri;
                for (i = 0; i < nodes.length; i++) {
                    el = nodes[i];
                    t = ((el.innerText || el.textContent || '') + '').replace(/\s+/g, ' ').trim().toLowerCase();
                    if (!t || t.length > 64) continue;
                    pri = 99;
                    for (n = 0; n < needles.length; n++) {
                        if (t.indexOf(needles[n]) !== -1 && n < pri) pri = n;
                    }
                    if (pri === 99) continue;
                    r = el.getBoundingClientRect();
                    if (r.width < 24 || r.height < 12) continue;
                    if (r.top < 50 || r.top > 520) continue;
                    if (pri < bestPri) {
                        bestPri = pri;
                        best = el;
                    }
                }
                return best;
            }

            function izberiMenijskoPovezavo(preferLive, fromEl) {
                var links = poberiMenijskePovezave();
                if (!links.length) return null;
                var i, href, t;
                if (preferLive) {
                    for (i = 0; i < links.length; i++) {
                        href = ((links[i].getAttribute('href') || '') + '').toLowerCase();
                        t = ((links[i].innerText || links[i].textContent || '') + '').toLowerCase();
                        if (href.indexOf('/livetv') !== -1 || t.indexOf('živo') !== -1 || t.indexOf('zivo') !== -1) {
                            return links[i];
                        }
                    }
                }
                if (fromEl) {
                    var fr = fromEl.getBoundingClientRect();
                    var cx = fr.left + fr.width / 2;
                    var best = links[0];
                    var bestD = 1e9;
                    for (i = 0; i < links.length; i++) {
                        var rr = links[i].getBoundingClientRect();
                        var d = Math.abs((rr.left + rr.width / 2) - cx);
                        if (d < bestD) {
                            bestD = d;
                            best = links[i];
                        }
                    }
                    return best;
                }
                var path = (location.pathname || '').toLowerCase();
                for (i = 0; i < links.length; i++) {
                    href = ((links[i].getAttribute('href') || '') + '').toLowerCase();
                    if (path && href && href.indexOf(path) !== -1) return links[i];
                }
                return links[0];
            }

            function premakniPoMeniju(direction) {
                var links = poberiMenijskePovezave();
                if (links.length < 2) return false;
                var cur = getActiveElement();
                var idx = -1;
                var i, t, tk;
                for (i = 0; i < links.length; i++) {
                    if (cur && (links[i] === cur || links[i].contains(cur) || (cur.contains && cur.contains(links[i])))) {
                        idx = i;
                        break;
                    }
                }
                if (idx < 0 && cur) {
                    t = ((cur.innerText || cur.textContent || '') + '').replace(/\s+/g, ' ').trim().toLowerCase();
                    for (i = 0; i < links.length; i++) {
                        tk = ((links[i].innerText || links[i].textContent || '') + '').replace(/\s+/g, ' ').trim().toLowerCase();
                        if (tk && t && (tk === t || t.indexOf(tk) !== -1 || tk.indexOf(t) !== -1)) {
                            idx = i;
                            break;
                        }
                    }
                }
                if (idx < 0) return false;
                var next = direction === 'LEFT' ? idx - 1 : idx + 1;
                if (next < 0 || next >= links.length) return 'rob';
                highlightElement(links[next]);
                // #region agent log
                try {
                    window._safeerDbg('H231', 'tv_remote_nav.js:menu', 'move', {
                        dir: direction,
                        from: idx,
                        to: next,
                        t: ((links[next].innerText || '') + '').replace(/\s+/g, ' ').trim().slice(0, 40),
                        href: ((links[next].getAttribute('href') || '') + '').slice(0, 50),
                        n: links.length
                    });
                } catch (_) {}
                // #endregion
                return true;
            }

            /* SAFEER_LEVI_ROB_ZACETEK - ciste funkcije; preizkus: tests/tv_levi_rob_test.js */
            // Safeer OS ima levo od strani stransko vrstico. LEVO jo odpre, kadar v isti vrsti na zaslonu levo ni vec
            // nobenega elementa. Prej je vrstico odprl VSAK pritisk LEVO (Android strani ne vidi), fokus v strani pa
            // se je premaknil sele ob naslednjem.
            function jeLeviSosed(c, r) {
                // Kandidat r je levo od izbranega c (sredisce vsaj 12 px levo) in se z njim prekriva po visini.
                var dx = (r.left + r.width / 2) - (c.left + c.width / 2);
                return dx < -12 && r.top < c.bottom - 4 && r.bottom > c.top + 4;
            }
            function leviRob(robVrstice, imaSoseda, scrollX) {
                // '' = navadna navigacija; 'podrsaj' = stran je podrsana v desno (najprej nazaj na levi rob); 'meni' = stranska vrstica.
                if (!robVrstice || imaSoseda) return '';
                return scrollX > 0 ? 'podrsaj' : 'meni';
            }
            /* SAFEER_LEVI_ROB_KONEC */

            var navigirajProstorsko = function(direction, robVrstice) {
                try {
                    var scrollY = window.scrollY || document.documentElement.scrollTop || 0;
                    var winH = window.innerHeight || 1080;
                    var winW = window.innerWidth || 1920;
                    var scrollEase = 'auto';

                    var current = getActiveElement();
                    if (jePotVZivo() && current && jeProgramskaPloscica(current)) {
                        var fromLogo = livetvProgramFromHeader(current);
                        // #region agent log
                        try {
                            window._safeerDbg('H301', 'tv_remote_nav.js:spatial', 'skip channel logo', {
                                dir: direction,
                                got: !!fromLogo,
                                to: fromLogo ? ((fromLogo.innerText || '') + '').replace(/\s+/g, ' ').trim().slice(0, 40) : ''
                            });
                        } catch (_) {}
                        // #endregion
                        if (fromLogo) {
                            highlightElement(fromLogo);
                            if (direction === 'DOWN') return 1;
                            current = fromLogo;
                        }
                    }
                    var candidates = getCandidates();

                    if (candidates.length === 0 && stranZaprta) {
                        // Stran zaprta s plastjo v tujem okvirju: nic ne drsamo za njo. GOR -> vrstica brskalnika (kazalec),
                        // LEVO na robu -> stranska vrstica, drugo dobi stran sama (Tab v okvir).
                        clearActive();
                        if (direction === 'LEFT' && leviRob(robVrstice, false, window.scrollX || 0) === 'meni') return -2;
                        return -1;
                    }
                    if (candidates.length === 0) {
                        // Nicesar ne vidimo (to se zgodi pri oknih, ki tecejo v svojem okvirju).
                        // Najprej poskusimo podrsati vsebino, sicer povemo, da nimamo kam -
                        // takrat tipko dobi stran sama (GOR: naslovna vrstica). Tipko porabi samo drsenje, ki se je
                        // res premaknilo (lastnik 3. 11. 2026: na strani izziva sta GOR in DOL samo »drsela«, ujet).
                        if (direction === 'DOWN') {
                            if (drsajNotranjiVsebnik(null, 1)) return 1;
                            if (oknoLahkoDrsi(1) && podrsajOkno(0, 120)) return 1;
                            return -1;
                        }
                        if (direction === 'UP') {
                            if (drsajNotranjiVsebnik(null, -1)) return 1;
                            if (scrollY <= 20) return -1;
                            return podrsajOkno(0, -120) ? 1 : -1;
                        }
                        if (direction === 'LEFT') {
                            if (leviRob(robVrstice, false, window.scrollX || 0) === 'meni') return -2;
                            return podrsajOkno(-140, 0) ? 1 : -1;
                        }
                        if (direction === 'RIGHT') return podrsajOkno(140, 0) ? 1 : -1;
                        return -1;
                    }

                    if (!current) {
                        // V strani ni izbranega nicesar: LEVO gre v stransko vrstico (Safeer OS), ne na prvi element strani.
                        if (direction === 'LEFT' && leviRob(robVrstice, false, window.scrollX || 0) === 'meni') return -2;
                        // Najprej vidni po legi (zgoraj levo), nato ostali po vrsti. Pregled fokusa 5. 11. 2026: zavrnjen cilj
                        // (highlightElement -> false) je prej vseeno vrnil 1 in smer je ostala mrtva - zdaj naslednji.
                        var vidni = [], ostali = [];
                        for (var i = 0; i < candidates.length; i++) {
                            var cand = candidates[i];
                            var candTag = (cand.tagName || '').toUpperCase();
                            if (candTag === 'INPUT' || candTag === 'TEXTAREA') continue;
                            var r = cand.getBoundingClientRect();
                            if (r.top >= -20 && r.top <= winH && r.left >= 0 && r.right <= winW) {
                                var dist = r.top * 2 + r.left;
                                var cls = (cand.className || '').toString().toLowerCase();
                                if (cls.indexOf('engine-chip') !== -1 || cls.indexOf('favorite-tile') !== -1 || cls.indexOf('tile') !== -1) {
                                    dist -= 80;
                                }
                                vidni.push({ el: cand, d: dist, i: i });
                            } else {
                                ostali.push(cand);
                            }
                        }
                        vidni.sort(function (a, b) { return a.d - b.d || a.i - b.i; });
                        var vrstaPrvih = vidni.map(function (x) { return x.el; }).concat(ostali);
                        // Drugi krog pregleda 5. 11. 2026: zavrnjen cilj je stran ze podrsal (scrollIntoView) - polozaj
                        // vrnemo, da naslednji poskus in neuspeh ne pustita podrsane strani brez izbire.
                        var polPrvih = polozajOkna();
                        for (var k = 0; k < vrstaPrvih.length && k < 8; k++) {
                            if (highlightElement(vrstaPrvih[k])) return 1;
                            povrniOkno(polPrvih);
                        }
                        return -1;
                    }

                    var cRect = okvirCilja(current);
                    var cCenterX = cRect.left + cRect.width / 2;
                    var cCenterY = cRect.top + cRect.height / 2;

                    if (direction === 'DOWN' && current) {
                        var homeCls = ((current.className || '') + '').toLowerCase();
                        if (homeCls.indexOf('engine-chip') !== -1) {
                            var chips = document.querySelectorAll('.engine-chip');
                            var favs = document.querySelectorAll('.favorite-tile');
                            var ci = -1, fi;
                            for (fi = 0; fi < chips.length; fi++) {
                                if (chips[fi] === current || (chips[fi].contains && chips[fi].contains(current))) { ci = fi; break; }
                            }
                            var hitFav = (ci >= 0 && favs[ci]) ? favs[ci] : (favs[0] || null);
                            if (hitFav) {
                                highlightElement(hitFav);
                                return 1;
                            }
                        }
                    }

                    if ((direction === 'LEFT' || direction === 'RIGHT') && current && jeMenijskaPovezava(current)) {
                        var poMeniju = premakniPoMeniju(direction);
                        // Prva povezava menija strani: levo od nje je stranska vrstica Safeer OS.
                        if (poMeniju === 'rob' && direction === 'LEFT' && leviRob(robVrstice, false, window.scrollX || 0) === 'meni') return -2;
                        if (poMeniju) return 1;
                    }

                    if (direction === 'UP') {
                        var curTxt = ((current.innerText || current.textContent || '') + '').replace(/\s+/g, ' ').trim().toLowerCase();
                        var curCls = ((current.className || '') + '').toLowerCase();
                        var onPlayBtn = curCls.indexOf('promo-carousel__button') !== -1 || curCls.indexOf('action-list') !== -1 ||
                            curTxt === 'glej zdaj' || curTxt.indexOf('glej zdaj') === 0 ||
                            curTxt.indexOf('predvajaj v živo') !== -1 || curTxt.indexOf('predvajaj v zivo') !== -1;
                        var naTvPonudniku = jeGostiteljTvPonudnika(location.hostname);
                        if (onPlayBtn && naTvPonudniku) {
                            var menuFromPlay = izberiMenijskoPovezavo(false, current);
                            if (menuFromPlay) {
                                highlightElement(menuFromPlay);
                                // #region agent log
                                try { window._safeerDbg('H40', 'tv_remote_nav.js:up', 'snap menu from play', { t: ((menuFromPlay.innerText || '') + '').replace(/\s+/g, ' ').trim().slice(0, 40) }); } catch (_) {}
                                // #endregion
                                return 1;
                            }
                        }
                        if (current && jeMenijskaPovezava(current)) {
                            /* stay in menu; spatial LEFT/RIGHT above */
                        } else {
                            var playHit = naTvPonudniku ? izberiGumbPredvajanja() : null;
                            if (playHit && playHit !== current) {
                                var playTop = playHit.getBoundingClientRect().top;
                                if (playTop < cRect.top - 8) {
                                    highlightElement(playHit);
                                    // #region agent log
                                    try { window._safeerDbg('H253', 'tv_remote_nav.js:up', 'snap play', { t: ((playHit.innerText || '') + '').replace(/\s+/g, ' ').trim().slice(0, 40) }); } catch (_) {}
                                    // #endregion
                                    return 1;
                                }
                            }
                            var menuHit = naTvPonudniku ? izberiMenijskoPovezavo(!!jePotVZivo(), current) : null;
                            if (menuHit && menuHit !== current) {
                                var onTopRow = cRect.top < 320;
                                if (onTopRow) {
                                    highlightElement(menuHit);
                                    // #region agent log
                                    try { window._safeerDbg('H40', 'tv_remote_nav.js:up', 'snap menu', { t: ((menuHit.innerText || '') + '').replace(/\s+/g, ' ').trim().slice(0, 40), href: ((menuHit.getAttribute('href') || '') + '').slice(0, 40), n: poberiMenijskePovezave().length }); } catch (_) {}
                                    // #endregion
                                    return 1;
                                }
                            }
                        }
                    }

                    if (direction === 'DOWN' && current && jeMenijskaPovezava(current)) {
                        var mHref = ((current.getAttribute && current.getAttribute('href')) || '').toLowerCase();
                        var mTxt = ((current.innerText || current.textContent || '') + '').replace(/\s+/g, ' ').trim().toLowerCase();
                        var pth = (location.pathname || '').toLowerCase();
                        if ((mHref.indexOf('/livetv') !== -1 || mTxt === 'tv v živo' || mTxt === 'tv v zivo') && pth.indexOf('/livetv') === -1) {
                            // #region agent log
                            try { window._safeerDbg('H252', 'tv_remote_nav.js:down', 'open livetv', { from: pth.slice(0, 40) }); } catch (_) {}
                            // #endregion
                            try { window._safeer_medij_want_play = false; sessionStorage.removeItem('safeer_medij_autoplay'); } catch (_) {}
                            try { current.click(); } catch (_) { if (mHref) location.href = mHref; }
                            return 1;
                        }
                        var tileDown = izberiVsebinskoPloscico();
                        if (tileDown) {
                            highlightElement(tileDown);
                            return 1;
                        }
                        if (pth.indexOf('/event') !== -1) {
                            var playDown = izberiGumbPredvajanja();
                            if (playDown) {
                                highlightElement(playDown);
                                // #region agent log
                                try { window._safeerDbg('H253', 'tv_remote_nav.js:down', 'snap play', { t: ((playDown.innerText || '') + '').replace(/\s+/g, ' ').trim().slice(0, 40) }); } catch (_) {}
                                // #endregion
                                return 1;
                            }
                        }
                    }

                    // Zunanji pregled 3. 11. 2026: tu je bil izstop v naslovno vrstico, ce je bil izbrani element v zgornjih
                    // 80 px - tudi, ce je bil nad njim se cilj strani (v nacinu aplikacije je prvi GOR zapustil aplikacijo).
                    // Izstop (-1) je zdaj samo spodaj: v smeri GOR ni cilja in stran je na vrhu (SAFEER_GOR_NA_VRHU).

                    // Pregled fokusa 5. 11. 2026: highlightElement zavrne cilj, ki ostane zunaj pogleda; prej je navigacija vseeno
                    // vrnila 1 in smer je ostala trajno mrtva. Zavrnjenega izlocimo in poskusimo naslednjega; ce ga ni, gre
                    // naprej kot brez cilja (drsenje ali -1: aplikacija tipko da strani).
                    var zavrnjeni = [];
                    var polPrej = polozajOkna();
                    for (var poskus = 0; poskus < 6; poskus++) {
                        // CodeRabbit 5. 11. 2026: zavrnjen cilj je lahko podrsal vsebnik - izhodisce merimo znova.
                        if (poskus > 0) {
                            cRect = okvirCilja(current);
                            cCenterX = cRect.left + cRect.width / 2;
                            cCenterY = cRect.top + cRect.height / 2;
                        }
                        var bestTarget = null;
                        var bestScore = Infinity;
                        var bestAxis = null;
                        var bestAxisScore = Infinity;
                        var wrapTarget = null;
                        var wrapY = 1e9;
                        var wrapX = 1e9;
                        var leviSosed = false;
                        // Spodnja vrstica strani (rtvslo.si: MMC / Zame / RTV 365 ...) je svoje obmocje: navzdol pridemo
                        // vanjo sele, ko je naslednja vsebina skrita pod njo; iz nje navzdol nadaljujemo z vsebino pod njo.
                        var vrsticaSp = najdiSpodnjoVrstico();
                        var vVrstici = !!(vrsticaSp && vrsticaSp.el.contains(current));
                        var bestVrstica = null, bestVrsticaScore = Infinity, podVrstico = null, podScore = Infinity;

                        for (var j = 0; j < candidates.length; j++) {
                            var el = candidates[j];
                            if (el === current || current.contains(el)) continue;
                            if (zavrnjeni.indexOf(el) !== -1) continue;

                            var r = okvirCilja(el);
                            if (r.width < 12 || r.height < 12) continue;
                            var eCls = ((el.className || '') + '').toLowerCase();
                            if (jeNogaAliPiskotki(el)) continue;
                            if (jePotVZivo() && eCls.indexOf('channel-container') !== -1 && eCls.indexOf('item--event') === -1) continue;

                            var eCenterX = r.left + r.width / 2;
                            var eCenterY = r.top + r.height / 2;
                            if (vrsticaSp) {
                                var elVVrstici = vrsticaSp.el.contains(el);
                                if (vVrstici) {
                                    if ((direction === 'LEFT' || direction === 'RIGHT') && !elVVrstici) continue;
                                    if (direction === 'UP' && elVVrstici) continue;
                                    if (direction === 'DOWN') {
                                        if (!elVVrstici && r.bottom > vrsticaSp.top + 4) {
                                            var ps = r.top + Math.abs(eCenterX - cCenterX) * 0.3;
                                            if (ps < podScore) { podScore = ps; podVrstico = el; }
                                        }
                                        continue;
                                    }
                                } else if (elVVrstici) {
                                    if (direction === 'DOWN') {
                                        var vs = Math.abs(eCenterX - cCenterX);
                                        if (vs < bestVrsticaScore) { bestVrsticaScore = vs; bestVrstica = el; }
                                    }
                                    continue;
                                }
                            }
                            var dx = eCenterX - cCenterX;
                            var dy = eCenterY - cCenterY;
                            var valid = false;
                            var mainDist = 0;
                            var crossDist = 0;

                            if (direction === 'DOWN') {
                                if (dy > 12) { valid = true; mainDist = dy; crossDist = Math.abs(dx); }
                            } else if (direction === 'UP') {
                                if (dy < -12) { valid = true; mainDist = -dy; crossDist = Math.abs(dx); }
                            } else if (direction === 'RIGHT') {
                                if (dx > 12) { valid = true; mainDist = dx; crossDist = Math.abs(dy); }
                                if (r.top > cRect.bottom - 10) {
                                    var wy = r.top;
                                    var wx = r.left;
                                    if (wy < wrapY - 10 || (Math.abs(wy - wrapY) < 10 && wx < wrapX)) {
                                        wrapY = wy;
                                        wrapX = wx;
                                        wrapTarget = el;
                                    }
                                }
                            } else if (direction === 'LEFT') {
                                if (dx < -12) { valid = true; mainDist = -dx; crossDist = Math.abs(dy); }
                                if (r.bottom < cRect.top + 10) {
                                    var wy2 = -r.top;
                                    var wx2 = -r.right;
                                    if (wy2 < wrapY - 10 || (Math.abs(wy2 - wrapY) < 10 && wx2 < wrapX)) {
                                        wrapY = wy2;
                                        wrapX = wx2;
                                        wrapTarget = el;
                                    }
                                }
                            }

                            if (direction === 'LEFT' && !leviSosed && jeLeviSosed(cRect, r)) leviSosed = true;

                            if (valid) {
                                var rowTol = jePotVZivo() ? 88 : 72;
                                var colTol = jePotVZivo() ? 150 : 130;
                                // Lastnik 5. 11. 2026 17:49 (rtvslo.si): sirok naslov clanka (sredisce dalec desno) ni bil »v stolpcu«, DOL ga
                                // je preskocil na ozek gumb nizje. V stolpcu je tudi element, ki vodoravno pokrije vsaj pol ozjega (SAFEER_STOLPEC).
                                var sameAxis = (direction === 'LEFT' || direction === 'RIGHT') ? (crossDist < rowTol) : (crossDist < colTol || prekrivaStolpec(cRect, r));
                                var score = mainDist + crossDist * (sameAxis ? 0.12 : 1.45);
                                if (jeVsebinskaPloscica(el) && jeVsebinskaPloscica(current) && sameAxis) score -= 80;
                                if (jeGumbDejanja(el) && jeGumbDejanja(current) && sameAxis) score -= 60;
                                if (sameAxis && score < bestAxisScore) {
                                    bestAxisScore = score;
                                    bestAxis = el;
                                }
                                if (score < bestScore) {
                                    bestScore = score;
                                    bestTarget = el;
                                }
                            }
                        }
                        // Safeer OS: v isti vrsti levo ni nicesar -> stranska vrstica (ne skok na konec vrste nad nami).
                        if (direction === 'LEFT') {
                            var rob = leviRob(robVrstice, leviSosed, window.scrollX || 0);
                            if (rob === 'meni') return -2;
                            // Pregled fokusa 5. 11. 2026: drsenje, ki se ni premaknilo, tipke ne porabi (-1: tipka gre strani).
                            if (rob === 'podrsaj') return podrsajOkno(-140, 0) ? 0 : -1;
                        }
                        if (bestAxis) bestTarget = bestAxis;
                        else if (!bestTarget && wrapTarget) bestTarget = wrapTarget;
                        if (vrsticaSp && direction === 'DOWN') {
                            if (vVrstici) bestTarget = podVrstico;
                            else if (bestVrstica) {
                                var tr = bestTarget ? bestTarget.getBoundingClientRect() : null;
                                // Od naslednje vsebine se nad vrstico vidi le rob (ali je ni): najprej vrstica.
                                if (!tr || (vrsticaSp.top - tr.top) < Math.min(tr.height * 0.5, 60)) bestTarget = bestVrstica;
                            }
                        }
                        if (!bestTarget || highlightElement(bestTarget)) break;
                        zavrnjeni.push(bestTarget);
                        bestTarget = null;
                        povrniOkno(polPrej);   // naslednji poskus brez drsenja zavrnjenega (drugi krog pregleda 5. 11. 2026)
                    }

                    if (bestTarget) {
                        return 1;
                    } else {
                        if (direction === 'DOWN') {
                            // Vsebnik pod prstom ima lahko svoj drsnik (okno s piskotki,
                            // seznam v pogovornem oknu); tega podrsamo, ne cele strani.
                            if (drsajNotranjiVsebnik(current, 1)) return 1;
                            if (!oknoLahkoDrsi(1) || !podrsajOkno(0, 120)) return -1;
                        }
                        else if (direction === 'UP') {
                            if (drsajNotranjiVsebnik(current, -1)) return 1;
                            // Drsenje, ki se ne premakne (SAFEER_IZZIV, 3. 11. 2026), ne sme ujeti: naslovna vrstica.
                            if (scrollY <= 20 || !podrsajOkno(0, -120)) {
                                clearActive();
                                return -1;
                            }
                        }
                        // Pregled fokusa 5. 11. 2026: LEVO/DESNO brez cilja - ce se stran ni premaknila, tipka gre strani (-1).
                        else if (direction === 'LEFT') { if (!podrsajOkno(-140, 0)) return -1; }
                        else if (direction === 'RIGHT') { if (!podrsajOkno(140, 0)) return -1; }
                        return 0;
                    }
                } catch(_) {}
                return -1;
            };
            // Samo s dokazom tipke (SAFEER_TIPKA); klic strani vrne null in ne premakne nicesar.
            izvoziZaAplikacijo('_safeer_navigate_spatial', function (smer, robVrstice) {
                if (!porabiTipko()) return null;
                vTipki = true;
                try { return navigirajProstorsko(smer, robVrstice); } finally { vTipki = false; }
            });

            /**
             * Podrsa vsebnik, v katerem smo (npr. okno s piskotki), in ne cele strani.
             * smer: 1 navzdol, -1 navzgor. Vrne true, ce se je kaj premaknilo.
             */
            function drsajVsebnik(el, dy) {
                var prej = el.scrollTop;
                try { el.scrollTop += dy; } catch (_) { return false; }
                return Math.abs(el.scrollTop - prej) >= 1;
            }
            function drsajNotranjiVsebnik(zacetek, smer) {
                var nazaj = smer < 0;
                var korak = Math.round((window.innerHeight || 1080) * 0.35);
                var el = zacetek;
                var globina = 0;
                while (el && el.nodeType === 1 && globina < 30) {
                    if (lahkoDrsi(el, false, nazaj) && drsajVsebnik(el, smer * korak)) return true;
                    el = el.parentElement;
                    globina++;
                }
                // Brez izhodisca poskusimo v prekrivnem oknu: tam je vsebina, ki jo
                // uporabnik gleda, in prav ta mora iti navzdol.
                var okno = najdiPrekrivnoOkno();
                if (!okno) return false;
                if (lahkoDrsi(okno, false, nazaj) && drsajVsebnik(okno, smer * korak)) return true;
                var notranji;
                try { notranji = okno.querySelectorAll('div, section, main, ul, form'); } catch (_) { return false; }
                for (var i = 0; i < notranji.length && i < 400; i++) {
                    if (lahkoDrsi(notranji[i], false, nazaj) && drsajVsebnik(notranji[i], smer * korak)) return true;
                }
                return false;
            }

            // Podrsa okno in pove, ali se je res premaknilo (overflow: hidden, zaklenjeno ozadje okna ...).
            // Drugi krog pregleda 5. 11. 2026: 'instant', ne 'auto' - pri scroll-behavior:smooth strani se 'auto' premakne
            // sele kasneje, meritev takoj je rekla »ni premika« (-1) in aplikacija je tipko dala se strani (dvojni premik).
            function podrsajOkno(dx, dy) {
                var d = document.documentElement;
                var x0 = window.scrollX || d.scrollLeft || 0, y0 = window.scrollY || d.scrollTop || 0;
                try { window.scrollBy({ left: dx, top: dy, behavior: 'instant' }); } catch (_) { return false; }
                var x1 = window.scrollX || d.scrollLeft || 0, y1 = window.scrollY || d.scrollTop || 0;
                return Math.abs(x1 - x0) >= 1 || Math.abs(y1 - y0) >= 1;
            }
            function polozajOkna() {
                var d = document.documentElement;
                return [window.scrollX || d.scrollLeft || 0, window.scrollY || d.scrollTop || 0];
            }
            function povrniOkno(p) {
                var z = polozajOkna();
                if (Math.abs(z[0] - p[0]) < 1 && Math.abs(z[1] - p[1]) < 1) return;
                try { window.scrollTo({ left: p[0], top: p[1], behavior: 'instant' }); } catch (_) {}
            }

            /** Ali se da drsati celo stran v dani smeri? */
            function oknoLahkoDrsi(smer) {
                try {
                    var d = document.documentElement;
                    var polozaj = window.scrollY || d.scrollTop || 0;
                    if (smer < 0) return polozaj > 4;
                    return ((d.scrollHeight - d.clientHeight) - polozaj) > 4;
                } catch (_) {
                    return false;
                }
            }

            /* SAFEER_PRAVI_DOTIK_ZACETEK */
            // Odlocitev vodje po neodvisnem pregledu 5. 11. 2026: skript tece v glavnem svetu strani in ne more biti zaupanja
            // vreden. Pravi (nativni) dotik je zato samo za namene, zaradi katerih obstaja: gumb Google (GSI, okvir
            // https://accounts.google.com/gsi/), izziv Cloudflare (challenges.cloudflare.com) in polje za datoteko, ki ne dobi
            // fokusa (skrito v oznaki). Vse drugo je sinteticni klik - z njim stran ne doseze tujega okvirja.
            // Najdba 3: elementFromPoint vrne gostitelja (zaprtega ali deklarativnega) shadow DOM, ne okvirja v njem. Shadow
            // DOM ima lahko samo element s seznama HTML attachShadow (DIV, SPAN, P, SECTION ...) ali element po meri (ime z '-') -
            // zadetek na takem elementu ni dokaz, da pod tocko ni tujega okvirja (zato ZADETEK_BREZ_OKVIRJA spodaj).
            // Tretji neodvisni pregled 5. 11. 2026 (N1-N4): preverba izvora iz glavnega sveta strani ni zanesljiva - atribut src
            // ne pove dejanske vsebine okvirja (srcdoc z notranjim tujim okvirjem, otrok, ki ga stars navigira prek
            // contentWindow.location), gostitelj shadow DOM lahko skriva katerikoli okvir, med preverbo in dotikom stran
            // premakne tuj okvir pod tocko, prekrivalo okvirja preverimo le po velikosti. Odlocitev vodje: pravi dotik v TUJ
            // okvir (GSI, reCAPTCHA, hCaptcha, Cloudflare) je izklopljen; OK tam pokaze namig za kazalec (rdeci gumb), kjer
            // cilja uporabnik sam. Seznam ostane za ponovni vklop s potrditvijo na strani prejemnika (skript zacetka
            // dokumenta v okvirju dovoljenega izvora, porocanje prek addWebMessageListener - SAFEER-ZNANJE.md).
            var PRAVI_DOTIK_V_OKVIR = false;
            // Cetrti slepi pregled 5. 11. 2026 (N-C): tudi FENCEDFRAME in PORTAL (vsebina drugega izvora).
            var OKVIRNE_OZNAKE = { __proto__: null, IFRAME: 1, FRAME: 1, OBJECT: 1, EMBED: 1, FENCEDFRAME: 1, PORTAL: 1 };
            // Brez metod niza (indexOf, startsWith lahko prepise stran): primerjava po znakih.
            function zacneZ(v, p) {
                if (typeof v !== 'string' || v.length < p.length) return false;
                for (var i = 0; i < p.length; i++) { if (v[i] !== p[i]) return false; }
                return true;
            }
            // Cetrti slepi pregled 5. 11. 2026 (N-C, odlocitev vodje 4): prej seznam prepovedanih (vsaka nova vrsta okvirja, npr.
            // FENCEDFRAME, je bila »varna«). Zdaj seznam dovoljenih oznak zadetka pri oznaki s skritim poljem za datoteko:
            // besedilni elementi, ki ne morejo biti gostitelj shadow DOM (HTML attachShadow) in ne nosijo tuje vsebine.
            // SPAN NI na seznamu: lahko je gostitelj (zaprte) sence s tujim okvirjem - zadetek na SPAN v oznaki ostane brez
            // pravega dotika (sinteticni klik), razen ce je zadetek oznaka sama (zadetekJeCilj).
            var ZADETEK_BREZ_OKVIRJA = { __proto__: null, LABEL: 1, B: 1, I: 1, EM: 1, STRONG: 1, SMALL: 1, U: 1, S: 1, MARK: 1,
                ABBR: 1, CODE: 1, KBD: 1, SUB: 1, SUP: 1, BR: 1, IMG: 1, INPUT: 1 };
            function lahkoSkrivaOkvir(oz) {
                if (typeof oz !== 'string' || oz === '') return true;
                return ZADETEK_BREZ_OKVIRJA[oz] !== 1;
            }
            function koncaZ(v, k, p) {
                if (typeof v !== 'string' || k < p.length || k > v.length) return false;
                for (var i = 0; i < p.length; i++) { if (v[k - p.length + i] !== p[i]) return false; }
                return true;
            }
            // Lastnik 5. 11. 2026 19:12: tudi »Nisem robot« (reCAPTCHA, hCaptcha) - samo okvir s potrditvenim poljem, ne izziv
            // s slikami. Izvori preverjeni 5. 11. 2026: reCAPTCHA vrne stran potrditvenega polja na api2/anchor in
            // enterprise/anchor (www.google.com in www.recaptcha.net), izziv je bframe. hCaptcha (js.hcaptcha.com/1/api.js):
            // assetUrl https://newassets.hcaptcha.com/captcha/v1/<razlicica>/static, okvir polja '/hcaptcha.html#frame=checkbox&',
            // izziv '#frame=challenge', nevidni '#frame=checkbox-invisible'. Vir je iz izvirnega getterja src (razresen URL).
            var IZVORI_POLJA = ['https://www.google.com/recaptcha/api2/anchor?', 'https://www.google.com/recaptcha/enterprise/anchor?',
                'https://www.recaptcha.net/recaptcha/api2/anchor?', 'https://www.recaptcha.net/recaptcha/enterprise/anchor?'];
            function jePoljeRecaptcha(v) {
                for (var i = 0; i < IZVORI_POLJA.length; i++) { if (zacneZ(v, IZVORI_POLJA[i])) return true; }
                return false;
            }
            function jeOkvirPotrditve(v) {
                if (typeof v !== 'string') return false;
                if (zacneZ(v, 'https://accounts.google.com/gsi/') || jePoljeRecaptcha(v)) return true;
                if (!zacneZ(v, 'https://newassets.hcaptcha.com/captcha/')) return false;
                var h = -1;
                for (var j = 0; j < v.length; j++) {
                    if (v[j] === '?') return false;
                    if (v[j] === '#') { h = j; break; }
                }
                if (h < 0 || !koncaZ(v, h, '/hcaptcha.html')) return false;
                return koncaZ(v, h + 16, '#frame=checkbox&');
            }
            function smePraviDotik(o) {
                if (!o || !o.izvirniki || !o.omrezni || !o.potrjena) return false;
                if ((o.namen === 'google' || o.namen === 'izziv') && PRAVI_DOTIK_V_OKVIR !== true) return false;
                if (o.namen === 'google') {
                    // Namen 'google': okvir prijave Google (GSI) ali potrditveno polje reCAPTCHA/hCaptcha; zadetek je tocno ta okvir.
                    return o.oznakaCilja === 'IFRAME' && o.zadetekJeCilj === true && jeOkvirPotrditve(o.virCilja);
                }
                if (o.namen === 'izziv') {
                    if (!o.zadetekVCilju) return false;
                    if (o.oznakaZadetka === 'IFRAME') return zacneZ(o.virZadetka, 'https://challenges.cloudflare.com/');
                    // Pravi izziv je v ZAPRTEM shadow DOM (izmerjeno 3. 11. 2026): gostitelja moramo sprejeti (preostalo tveganje).
                    return OKVIRNE_OZNAKE[o.oznakaZadetka] !== 1;
                }
                if (o.namen === 'datoteka') {
                    if (!o.vhodDatoteke || !o.zadetekVCilju) return false;
                    return o.zadetekJeCilj === true || !lahkoSkrivaOkvir(o.oznakaZadetka);
                }
                return false;
            }
            /* SAFEER_PRAVI_DOTIK_KONEC */
            // Tocka pravega dotika za namen ali null. Vse z izvirniki (IZ); po tej preverbi klicatelj takoj poklice pravDotik in
            // konca OK brez kode strani (najdba 2: dotik tece asinhrono, stran je vmes premaknila tuj okvir pod tocko).
            function tockaNamena(el, namen) {
                if (!IZ || !el) return null;
                var r = okvirIz(el);
                if (!r) return null;
                var t = namen === 'izziv' ? tockaIzziva(r) : { x: r.left + r.width / 2, y: r.top + r.height / 2 };
                var oc = oznakaIz(el), vc = oc === 'IFRAME' ? virOkvirjaIz(el) : '';
                // reCAPTCHA (styles__ltr.css 5. 11. 2026): okvir 304 x 78, polje 28 px z robom 1 + 12 + 4 px - sredina polja
                // 31 px od levega roba (sredina okvirja je na besedilu). Stran okvir pogosto pomanjsa (transform), zato v razmerju.
                if (namen === 'google' && jePoljeRecaptcha(vc)) t.x = r.left + r.width * 31 / 304;
                var z = zadetekIz(t.x, t.y);
                var oz = z ? oznakaIz(z) : '';
                return smePraviDotik({
                    izvirniki: true, omrezni: omrezniDokument, potrjena: true, namen: namen,
                    oznakaCilja: oc, virCilja: vc,
                    zadetekJeCilj: z === el, zadetekVCilju: !!z && vsebujeIz(el, z), oznakaZadetka: oz,
                    virZadetka: oz === 'IFRAME' ? virOkvirjaIz(z) : '',
                    vhodDatoteke: namen === 'datoteka' && jeVhodDatotekeIz(el)
                }) ? t : null;
            }

            function klikniElement(el) {
                if (!el) return;
                try {
                    var host = (location.hostname || '').toLowerCase();
                    var clsTap = ((el.className || '') + '').toLowerCase();
                    var jeProgramskaKartica = (
                        clsTap.indexOf('item--event') !== -1 ||
                        !!(el.closest && el.closest('.item--event, .item.item--event'))
                    );
                    if (jeProgramskaKartica) {
                        if (window._safeerSiteAgent && !window._safeerSiteAgent.acceptOk()) return;
                        var tile = (el.closest && el.closest('.item--event, .item.item--event')) || el;
                        var ovTap = document.querySelector('.zw-overlays-layer, [class*="overlays-layer"]');
                        var ocTap = ovTap ? ((ovTap.className || '') + '').toLowerCase() : '';
                        var overlayOnTap = ocTap.indexOf('player-fullwindow') !== -1 || ocTap.indexOf('player-scaled') !== -1;
                        var vTap = document.querySelector('video');
                        var framedTap = !!(vTap && (vTap.videoWidth || 0) >= 320 && vTap.readyState >= 2);
                        if (overlayOnTap && framedTap) return;
                        try { if (window._safeerSiteAgent) window._safeerSiteAgent.markWantPlay(); } catch (_) {}
                        try { window._safeer_medij_last_tile = tile; } catch (_) {}
                        zadnjaPloscica = tile;
                        window._safeer_medij_player_el = null;
                        window._safeer_medij_video_boosted = false;
                        window._safeer_medij_fs_clicked = false;
                        window._safeer_medij_playing = false;
                        try { if (overlayOnTap && !framedTap && window._safeer_medij_unsmash) window._safeer_medij_unsmash(); } catch (_) {}
                        function fireTileClick() {
                            var rectT = tile.getBoundingClientRect();
                            var clickN = 0;
                            var clickTg = '';
                            function onCapClick(ev) {
                                clickN++;
                                var t = ev && ev.target;
                                clickTg = ((t && t.tagName) || '') + ':' + (((t && t.className) || '') + '').toString().slice(0, 40);
                            }
                            try { document.addEventListener('click', onCapClick, true); } catch (_) {}
                            try { tile.click(); } catch (_) {}
                            try { document.removeEventListener('click', onCapClick, true); } catch (_) {}
                            try {
                                window._safeerDbg('H97', 'tv_remote_nav.js:click', 'tile pointer', {
                                    id: (tile.id || '').slice(0, 40),
                                    first: (tile.id || '') === 'csh__first_item',
                                    t: ((tile.innerText || '') + '').replace(/\s+/g, ' ').trim().slice(0, 50),
                                    l: Math.round(rectT.left || 0),
                                    tY: Math.round(rectT.top || 0),
                                    path: (location.pathname || '').slice(0, 50)
                                });
                            } catch (_) {}
                            try {
                                window._safeerDbg('H294', 'tv_remote_nav.js:click', 'tile click count', {
                                    n: clickN,
                                    tg: clickTg,
                                    first: (tile.id || '') === 'csh__first_item',
                                    method: 'tile.click'
                                });
                            } catch (_) {}
                        }
                        if (overlayOnTap && ovTap && !framedTap) {
                            try {
                                ovTap.classList.remove('player-fullwindow', 'player-scaled');
                                ovTap.classList.add('player-closed');
                            } catch (_) {}
                            setTimeout(fireTileClick, 280);
                            return;
                        }
                        fireTileClick();
                        return;
                    }
                    // Pregled Groka 5. 11. 2026 (najdba 1): okvir z izvirniki (IZ), ne z metodami, ki jih prepise stran.
                    var rect = okvirIz(el) || el.getBoundingClientRect();
                    var cx = rect.left + rect.width / 2;
                    var cy = rect.top + rect.height / 2;
                    /* SAFEER_EN_KLIK_ZACETEK */
                    // Lastnik 28. 10. 2026 (Facebook na TV): OK je sprozil TRI klike - preklopna gumba (»Pokazi geslo«) sta se
                    // prestavila dvakrat ali trikrat. Natanko en klik: zaporedje dogodkov miske z enim samim el.click().
                    // Neodvisni pregled 5. 11. 2026 (odlocitev B): tu nikoli pravi dotik - samo za namene v klikIzbrane.
                    var mouseOpts = { bubbles: true, cancelable: true, view: window, clientX: cx, clientY: cy, screenX: cx, screenY: cy };
                    try { el.dispatchEvent(new PointerEvent('pointerdown', mouseOpts)); } catch(_) {}
                    try { el.dispatchEvent(new MouseEvent('mousedown', mouseOpts)); } catch(_) {}
                    try { el.dispatchEvent(new PointerEvent('pointerup', mouseOpts)); } catch(_) {}
                    try { el.dispatchEvent(new MouseEvent('mouseup', mouseOpts)); } catch(_) {}
                    try { el.click(); } catch(_) {}
                    /* SAFEER_EN_KLIK_KONEC */
                } catch(_) {}
            }

            /* SAFEER_OK_DATOTEKA_ZACETEK */
            // TV 4. 11. 2026 (w3schools tryhtml5_input_type_file): OK na »Izberite datoteko« ni naredil nicesar,
            // onShowFileChooser se ni poklical. Chromium izbirnik datotek odpre samo ob uporabnikovi aktivaciji; klik iz
            // evaluateJavascript (el.click(), sinteticna miska) je nima. Zato polje za datoteko (ali oznaka, ki kaze nanj)
            // dobi fokus in aplikacija WebView poslje pravo tipko Enter - kot tipkovnica na racunalniku (TvTipke.okNaDatoteki).
            function vhodDatoteke(el) {
                if (!el || el.nodeType !== 1) return null;
                var tag = (el.tagName || '').toUpperCase();
                if (tag === 'INPUT') {
                    var tip = ((el.getAttribute && el.getAttribute('type')) || '').toLowerCase();
                    return tip === 'file' ? el : null;
                }
                if (tag === 'LABEL') {
                    var c = null;
                    try { c = el.control; } catch (_) {}
                    return (c && c !== el && vhodDatoteke(c) === c) ? c : null;
                }
                return null;
            }
            // 'datoteka' = aplikacija poslje Enter; '' = obicajen klik (npr. skrito polje v oznaki: pravi dotik na oznako).
            function okNaDatoteki(vhod, aktiven) {
                return (!!vhod && !vhod.disabled && aktiven === vhod) ? 'datoteka' : '';
            }
            /* SAFEER_OK_DATOTEKA_KONEC */

            /* SAFEER_PREVERBA_CILJA_ZACETEK */
            // Tretji neodvisni pregled 5. 11. 2026 (N3): med naso preverbo in pravim dejanjem (dotik ali Enter tece prek niti UI
            // aplikacije) tece koda strani. Pravo dejanje caka v zaprtju (en cilj, najvec CILJ_ROK_MS po OK); aplikacija tik
            // pred dotikom ali tipko poklice _safeer_preveri_cilj z dokazom tipke in nakljucnim izzivom, mi cilj preverimo
            // znova z izvirniki in vrnemo 'cilj:' + odtis zetona (TvTipke.ciljVelja). En klic porabi cilj.
            var cakajociCilj = null, CILJ_ROK_MS = 1500;
            function pravDotikNamena(el, namen) {
                var t = tockaNamena(el, namen);
                if (!t) return false;
                cakajociCilj = { __proto__: null, el: el, nacin: 'dotik', namen: namen, x: t.x, y: t.y, cas: casZdaj() };
                if (pravDotik(t.x, t.y)) return true;
                cakajociCilj = null;
                return false;
            }
            function ciljSeVelja(c) {
                if (!c || !pravaDejanjaDovoljena()) return false;
                var od = casZdaj() - c.cas;
                if (!(od >= 0 && od < CILJ_ROK_MS)) return false;
                if (c.nacin === 'enter') {
                    var v = c.el;
                    if (aktivenIz() !== v || !jeVhodDatotekeIz(v)) return false;
                    try { if (typeof IZ.dis === 'function' && IZ.apply(IZ.dis, v, []) === true) return false; } catch (_) { return false; }
                    return true;
                }
                if (c.nacin === 'dotik') {
                    var t = tockaNamena(c.el, c.namen);
                    return !!t && t.x === c.x && t.y === c.y;
                }
                return false;
            }
            function preveriCilj(izziv) {
                var c = cakajociCilj;
                cakajociCilj = null;
                if (!jeVeljavenIzziv(izziv) || !ciljSeVelja(c)) return '';
                var o = odtisZetona(ZETON_DOTIKA, 'cilj|' + c.nacin + '|' + izziv);
                return o ? 'cilj:' + o : '';
            }
            /* SAFEER_PREVERBA_CILJA_KONEC */
            // Namig, da OK v tuj okvir ne seze: aplikacija pokaze kratko besedilo (enkrat na stran) samo z odtisom zetona
            // (TvTipke.namigKazalca) - drug odgovor ne naredi nicesar.
            function namigKazalca(izziv) {
                if (!jeVeljavenIzziv(izziv)) return true;
                var o = odtisZetona(ZETON_DOTIKA, 'kazalec|' + izziv);
                return o ? 'kazalec:' + o : true;
            }

            // Okvir, na katerega gre pravi dotik: izbral ga je daljinec (zaprtje) in je viden (pregled fokusa 5. 11. 2026).
            function jeVidenOkvir(el) {
                if (!el || el !== izbrani() || !jeUporaben(el, true)) return false;
                var r = okvirIz(el);
                return !!r && r.width >= 40 && r.height >= 20;
            }

            var klikIzbrane = function(izziv) {
                try {
                    var path = (location.pathname || '').toLowerCase();
                    var onEvent = path.indexOf('/events/') !== -1 || path.indexOf('/event/') !== -1;
                    // Pregled Groka 5. 11. 2026 (najdba 3): cilj OK je samo izbira daljinca, ne fokus, ki ga je dala stran. Brez
                    // izbire prvi OK samo izbere fokusiran element; kliknemo ob naslednjem OK.
                    var target = izbrani();
                    if (!target || !jeUporaben(target)) {
                        var akt = getActiveElement();
                        // Neodvisni pregled 5. 11. 2026 (najdba 4): fokus je lahko dala stran - izbira brez potrditve.
                        if (akt) { oznaciBrezPotrditve(akt); return true; }
                        return false;
                    }
                    // Najdba 4: OK izbire ne potrdi (prej je drugi OK na fokusu strani sprozil pravi dotik); potrdi jo samo smerna
                    // tipka z dokazom (navigirajProstorsko).
                    var potrjena = izbiraPotrjena;
                    var goingEvent = onEvent;
                    try {
                        if (target && !goingEvent) {
                            var a = (target.closest && target.closest('a[href*="event"]')) || target.querySelector('a[href*="event"]');
                            var ah = a ? ((a.getAttribute('href') || a.href || '') + '') : '';
                            goingEvent = ah.indexOf('event') !== -1;
                        }
                    } catch (_) {}
                    // #region agent log
                    try {
                        window._safeerDbg('H60', 'tv_remote_nav.js:click', 'ok click target', {
                            path: path.slice(0, 80),
                            cls: ((target && target.className) || '').toString().slice(0, 90),
                            goingEvent: !!goingEvent,
                            onEvent: !!onEvent,
                            want: !!window._safeer_medij_want_play,
                            auto: (function(){ try { return sessionStorage.getItem('safeer_medij_autoplay') || ''; } catch(e){ return ''; } })()
                        });
                    } catch (_) {}
                    // #endregion
                    // Preverjanje Cloudflare (SAFEER_IZZIV): en pravi dotik na potrditveno polje. Sinteticni klik na
                    // gostitelja ne doseze okvirja, zato ga ni.
                    if (target && jeIzziv(target)) {
                        // Pregled Groka 5. 11. 2026 (najdba 4): lazni .cf-turnstile (prosojen, pointer-events: none) cez tuj okvir ali s
                        // tujim okvirjem v sebi. Dotik samo na potrjeni izbiri in ce je na tocki gostitelj (zaprt shadow DOM) ali
                        // okvir challenges.cloudflare.com v njem. Po pravDotik do konca OK ni kode strani (najdba 2).
                        if (!potrjena) return true;
                        // Tretji neodvisni pregled 5. 11. 2026: pravi dotik v tuj okvir je izklopljen - namig za kazalec.
                        if (PRAVI_DOTIK_V_OKVIR !== true) return namigKazalca(izziv);
                        pravDotikNamena(target, 'izziv');
                        return true;
                    }
                    // Polje za datoteko: fokus nanj in Enter iz aplikacije (SAFEER_OK_DATOTEKA).
                    var vhodD = vhodDatoteke(target);
                    if (!vhodD && target && target.closest) { try { vhodD = vhodDatoteke(target.closest('label')); } catch (_) {} }
                    if (vhodD && !vhodD.disabled) {
                        // Prava tipka Enter (uporabnikova aktivacija) samo na izbiri, ki jo je potrdil daljinec.
                        if (!potrjena) return true;
                        // Neodvisni pregled 5. 11. 2026: polje, fokus in aktivni element z izvirniki (stran je s prepisanim focus ali
                        // activeElement lahko usmerila pravi Enter v tuj okvir).
                        if (pravaDejanjaDovoljena() && jeVhodDatotekeIz(vhodD)) {
                            fokusIz(vhodD);
                            // 'datoteka:' + odtis: aplikacija preveri, da odgovor res da ta skript (SAFEER_DOKAZ, TvTipke.okNaDatoteki).
                            // Tretji neodvisni pregled 5. 11. 2026 (N3): pred tipko Enter aplikacija se enkrat vprasa, ali ima
                            // fokus se isto polje (_safeer_preveri_cilj, izvirni activeElement) - stran je vmes lahko dala fokus
                            // tujemu okvirju.
                            if (okNaDatoteki(vhodD, aktivenIz()) === 'datoteka') {
                                cakajociCilj = { __proto__: null, el: vhodD, nacin: 'enter', cas: casZdaj() };
                                return dokazDatoteke(ZETON_DOTIKA, izziv);
                            }
                            // Skrito polje v oznaki (fokusa ne dobi): en pravi dotik na oznako (aplikacija tocko pred dotikom
                            // preveri se enkrat, ChromiumEngineView.triggerNativeTap).
                            if (pravDotikNamena(target, 'datoteka')) return true;
                        }
                    }
                    // Tuj okvir: pravi dotik samo na gumb Google (GSI, SAFEER_OKVIR_GUMB) ali potrditveno polje reCAPTCHA/hCaptcha
                    // (jeOkvirPotrditve), ki ga je izbral daljinec in je viden.
                    // Drug tuj okvir (oglas, placilo ...) OK ne doseze - sinteticni klik vanj ne pride (odlocitev B).
                    if (target && (target.tagName || '').toUpperCase() === 'IFRAME') {
                        if (!jeVidenOkvir(target)) return false;
                        if (!potrjena) return true;
                        // Tretji neodvisni pregled 5. 11. 2026: OK v noben tuj okvir ne seze - namig za kazalec (enkrat na stran).
                        if (PRAVI_DOTIK_V_OKVIR !== true) return namigKazalca(izziv);
                        pravDotikNamena(target, 'google');
                        return true;
                    }
                    if (target) {
                        var clickable = target;
                        clickable = target.querySelector('a[href], button, [class*="play"], [class*="watch"], [class*="thumb"], [class*="image"], img, [role="button"]') || target;
                        // Pregled Groka 5. 11. 2026 (najdba 8): en OK = en dotik (prej se drugi na kartico: dvojni preklop, drugi
                        // dotik po spremembi postavitve na drug element). Nevidna notranja povezava: dotik na kartico.
                        if (clickable !== target) {
                            var rc = okvirIz(clickable) || clickable.getBoundingClientRect();
                            if (!(rc.width >= 1 && rc.height >= 1)) clickable = target;
                        }
                        klikniElement(clickable);
                        if (!goingEvent && path.indexOf('/livetv') === -1) {
                            try { if (window._safeerSiteAgent && window._safeerSiteAgent.allowBoost()) window._safeer_medij_boost(); } catch (_) {}
                        }
                        // #region agent log
                        // #endregion

                        setTimeout(function() {
                            try {
                                if (location.pathname.indexOf('/watch') === -1 && location.pathname.indexOf('/shorts') === -1) return;
                                var v = document.querySelector('video');
                                if (v) {
                                    v.muted = false;
                                    v.volume = 1.0;
                                    var playPromise = v.play();
                                    if (playPromise !== undefined) {
                                        playPromise.catch(function() {});
                                    }
                                }
                            } catch(_) {}
                        }, 700);
                        return true;
                    }
                } catch(_) {}
                return false;
            };
            // Samo s dokazom tipke (SAFEER_TIPKA): stran z njim ne sprozi OK.
            izvoziZaAplikacijo('_safeer_click_focused_card', function (izziv) {
                if (!porabiTipko()) return false;
                vTipki = true;
                try { return klikIzbrane(izziv); } finally { vTipki = false; }
            });
            // Druga preverba cilja tik pred pravim dejanjem (SAFEER_PREVERBA_CILJA): samo z dokazom tipke, sicer stran klic
            // porabi in nas vprasa za odtis.
            izvoziZaAplikacijo('_safeer_preveri_cilj', function (izziv) {
                if (!porabiTipko()) { cakajociCilj = null; return ''; }
                return preveriCilj(izziv);
            });

            // 5. Samodejno dodajanje tabindex. Piskotkov ne sprejemamo in ne zavracamo namesto uporabnika
            // (lastnik, 3. 11. 2026): izbira je vedno njegova, daljinec mora le doseci gumbe okna.
            function makeElementsFocusable() {
                try {
                    vstopiVNovoPlast();
                    var focusables = document.querySelectorAll('a, button, input, select, textarea, [onclick], [role="button"], .card, .media-card, .item--event, .item.item--event, .tab, .seasonHeader, .video-play-button');
                    for (var i = 0; i < focusables.length; i++) {
                        var el = focusables[i];
                        if (isCarouselArrow(el)) continue;
                        if (jeSkritZaFokus(el)) continue;
                        if (!el.hasAttribute('tabindex')) {
                            el.setAttribute('tabindex', '0');
                        }
                    }
                } catch(_) {}
            }

            document.addEventListener('DOMContentLoaded', makeElementsFocusable);
            setInterval(makeElementsFocusable, 4500);
                var focusMoTimer = 0;
                try {
                    var focusMo = new MutationObserver(function () {
                        if (focusMoTimer) return;
                        focusMoTimer = setTimeout(function () {
                            focusMoTimer = 0;
                            makeElementsFocusable();
                        }, 480);
                    });
                    focusMo.observe(document.documentElement, { childList: true, subtree: true });
                } catch (_) {}

                        // 7. Real-time scroll listener za samodejno skrivanje/prikaz orodne vrstice
            var lastScrollPos = window.scrollY || 0;
            window.addEventListener('scroll', function() {
                try {
                    var currPos = window.scrollY || document.documentElement.scrollTop || 0;
                    var diff = currPos - lastScrollPos;
                    if (Math.abs(diff) > 24) {
                        if (window.SafeerBridge && window.SafeerBridge.onScrollChanged) {
                            window.SafeerBridge.onScrollChanged(diff > 0 ? 1 : -1, currPos);
                        }
                        lastScrollPos = currPos;
                    }
                } catch(_) {}
            }, { passive: true });

            try {
                setTimeout(function() {
                    var ae = document.activeElement;
                    if (ae && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA')) {
                        ae.blur();
                    }
                    if (location.href.indexOf('brave_home') !== -1 && !getActiveElement()) {
                        var start = document.querySelector('.hero-card') || document.querySelector('.favorite-tile') || document.querySelector('.engine-chip');
                        if (start) highlightElement(start);
                    }
                }, 80);
            } catch(_) {}
        })();
