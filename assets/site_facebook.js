// Safeer OS - Facebook na televizorju (lastnik 29. 10. 2026).
// 1) FB-jevi namigi (»Igranje iger«, »Meni«, »Novo sporocilo«) na TV ostanejo odprti: skrijemo jih.
// 2) Sponzorirane objave v viru in oglasi v desnem stolpcu se skrijejo.
// Stevilo skritih zapisemo v dnevnik (SafeerFB), da je ucinek merljiv.
(function () {
    try {
        if (window.__safeerFb) { window.__safeerFb.preglej(); return; }
        var SPONZ = /^(sponzorirano|sponsored|gesponsert|sponsorisé|patrocinado|sponsorizzato|sponzorováno|sponsorowane)$/i;
        var CTA = /^(več o tem|learn more|shop now|nakupuj zdaj|kupi zdaj|buy now|sign up|install now|namesti zdaj|book now|rezerviraj zdaj|contact us|send message|pošlji sporočilo|apply now|get offer|pridobi ponudbo|order now|naroči zdaj|get quote|play game|download|prenesi|subscribe|naroči se)$/i;
        var stevec = { desno: 0, vir: 0 };
        var zadnjiZapis = '';

        function slog() {
            if (document.getElementById('safeer-fb-slog')) return;
            var st = document.createElement('style');
            st.id = 'safeer-fb-slog';
            st.textContent = '[role="tooltip"]{display:none!important}' +
                '[data-safeer-oglas]{display:none!important}';
            (document.head || document.documentElement).appendChild(st);
        }

        function besedilo(el) {
            return ((el && (el.textContent || '')) + '').replace(/\s+/g, ' ').trim();
        }

        function skrij(el, kje) {
            if (!el || el.hasAttribute('data-safeer-oglas')) return;
            el.setAttribute('data-safeer-oglas', kje);
            stevec[kje]++;
        }

        function jeSponzorirano(enota) {
            var oznake = enota.querySelectorAll('a[aria-label], span[aria-label]');
            for (var i = 0; i < oznake.length; i++) {
                if (SPONZ.test((oznake[i].getAttribute('aria-label') || '').trim())) return true;
            }
            var ref = enota.querySelectorAll('[aria-labelledby]');
            for (var j = 0; j < ref.length && j < 40; j++) {
                var id = ref[j].getAttribute('aria-labelledby');
                var cilj = id && document.getElementById(id);
                if (cilj && SPONZ.test(besedilo(cilj))) return true;
            }
            if (enota.querySelector('a[href*="/ads/about"]')) return true;
            var kratki = enota.querySelectorAll('a span, span > span');
            for (var k = 0; k < kratki.length && k < 200; k++) {
                if (SPONZ.test(besedilo(kratki[k]))) return true;
            }
            if (enota.querySelector('a[href*="l.facebook.com/l.php"]')) {
                var gumbi = enota.querySelectorAll('[role="button"], [role="link"], a');
                for (var g = 0; g < gumbi.length && g < 120; g++) {
                    if (CTA.test(besedilo(gumbi[g]))) return true;
                }
            }
            return false;
        }

        function preglej() {
            try {
                slog();
                // Desni stolpec: kartice oglasov vodijo prek l.facebook.com/l.php, nad njimi je naslov »Sponzorirano«.
                var stolpci = document.querySelectorAll('[role="complementary"]');
                for (var s = 0; s < stolpci.length; s++) {
                    var povezave = stolpci[s].querySelectorAll('a[href*="l.facebook.com/l.php"]');
                    for (var p = 0; p < povezave.length; p++) skrij(povezave[p], 'desno');
                    var naslovi = stolpci[s].querySelectorAll('h2, h3, h4, span');
                    for (var n = 0; n < naslovi.length && n < 400; n++) {
                        if (SPONZ.test(besedilo(naslovi[n]))) skrij(naslovi[n], 'desno');
                    }
                }
                // Vir: enote posameznih objav (nikoli ne skrijemo korenskih vsebnikov celotnega vira).
                var enote = document.querySelectorAll('[aria-posinset], [data-pagelet^="FeedUnit_"], [role="feed"] [role="article"]');
                for (var e = 0; e < enote.length; e++) {
                    var en = enote[e];
                    if (en.hasAttribute('data-safeer-oglas')) continue;
                    // Ce enota znotraj vsebuje druge objave (npr. zunanji div), je ne obravnavamo kot posamezno objavo.
                    if (en.querySelector('[aria-posinset], [role="article"]')) continue;
                    if (jeSponzorirano(en)) skrij(en, 'vir');
                }
                var zapis = stevec.desno + '/' + stevec.vir;
                if (zapis !== zadnjiZapis) {
                    zadnjiZapis = zapis;
                    console.log('SafeerFB site_facebook.js skrito ' + JSON.stringify({ desno: stevec.desno, vir: stevec.vir, enot: enote.length }));
                }
            } catch (_) {}
        }

        var cakam = null;
        function kasneje() {
            if (cakam) return;
            cakam = setTimeout(function () { cakam = null; preglej(); }, 600);
        }

        window.__safeerFb = { preglej: preglej };
        preglej();
        try {
            new MutationObserver(kasneje).observe(document.documentElement, { childList: true, subtree: true });
        } catch (_) {}
    } catch (_) {}
})();
