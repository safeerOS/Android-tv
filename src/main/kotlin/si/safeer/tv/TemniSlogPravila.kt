package si.safeer.tv

/**
 * Pravila za pretvorbo temnega sloga (DARK_MODE_AMOLED_CSS -> TEMNI_CSS) in oznacevanje korena predvajalnika.
 *
 * Zgodovina (izmerjeno 21. 10. 2026 na TV, lastnik potrdil):
 * Temni slog je vsem elementom z [class*=container] vsilil neprosojno ozadje. Predvajalniki
 * (Bitmovin bmpui-ui-uicontainer) imajo prozoren sloj z gumbi NAD videom, ki je prekril sliko (zvok brez slike).
 *
 * Izboljsave po neodvisnem pregledu (S1 + V1 + S2 + S3 + N3):
 * 1. Specificnost ostane tocno izvirna: vsak selektor dobi :where(:not([data-safeer-medij], [data-safeer-medij] *)).
 *    Izvzet je tudi oznaceni element sam: prekrivni sloj ob videu (S3) bi sicer obdrzal temno ozadje in prekril sliko.
 * 2. Psevdoelementi (::...): filter gre PRED psevdoelement (npr. input:where(...)::placeholder).
 *    Globalni psevdoelementi (::-webkit-scrollbar, ::selection) in bloki @keyframes/@font-face ostanejo nedotaknjeni.
 * 3. @media/@supports: pravila znotraj se pretvorijo rekurzivno.
 * 4. Vejice v :is()/:not() ne razbijejo selektorja (razdelitev po vejicah le zunaj oklepajev).
 * 5. JS koren (MEDIJ_KOREN_JS): ustavi plezanje pri overflow auto|scroll, v celozaslonskem nacinu ne pleza (oznaci
 *    samo celozaslonski element z videom), oznaci prekrivke (position absolute|fixed > 80 % videa; prednik videa
 *    ni prekrivka), pocisti ob emptied ali odstranitvi videa. MutationObserver je omejen na en pregled na 250 ms
 *    (TV ima sibak procesor, strani kot SPA spreminjajo DOM nenehno).
 */
object TemniSlogPravila {

    const val MEDIJ_FILTER = ":where(:not([data-safeer-medij], [data-safeer-medij] *))"

    /**
     * JavaScript za zaznavo in oznacevanje korena predvajalnika ter njegovih prekrivnih slojev.
     */
    const val MEDIJ_KOREN_JS = """
(function(){
  try {
    function pocisti() {
      var oz = document.querySelectorAll('[data-safeer-medij]');
      var vs = document.getElementsByTagName('video');
      for (var i = 0; i < oz.length; i++) {
        var el = oz[i];
        if (!document.documentElement.contains(el)) continue;
        var seVelja = false;
        for (var j = 0; j < vs.length; j++) {
          var v = vs[j];
          if (!document.documentElement.contains(v)) continue;
          if (!v.src && !v.currentSrc && v.readyState === 0) continue;
          if (el.contains(v)) { seVelja = true; break; }
          var r = v.getBoundingClientRect();
          if (r.width >= 50 && r.height >= 50) {
            var er = el.getBoundingClientRect();
            var ix = Math.max(0, Math.min(r.right, er.right) - Math.max(r.left, er.left));
            var iy = Math.max(0, Math.min(r.bottom, er.bottom) - Math.max(r.top, er.top));
            if (ix * iy > 0.8 * r.width * r.height) {
              var cs = window.getComputedStyle(el);
              if (cs.position === 'absolute' || cs.position === 'fixed') { seVelja = true; break; }
            }
          }
        }
        if (!seVelja) el.removeAttribute('data-safeer-medij');
      }
    }

    function oznaciPrekrivke(v, r, koren) {
      var povrsina = r.width * r.height;
      if (povrsina <= 0) return;
      var kandidati = [];
      function dodaj(el) {
        if (!el || el === document.body || el === document.documentElement) return;
        if (koren.contains(el) || el === koren) return;
        // Prednik videa lezi pod njim, ni prekrivka; sicer bi celozaslonski #app izvzel vso stran.
        if (el.contains(v)) return;
        if (kandidati.indexOf(el) === -1) kandidati.push(el);
      }
      if (koren.parentElement) {
        var vsi = koren.parentElement.querySelectorAll('*');
        for (var i = 0; i < vsi.length; i++) dodaj(vsi[i]);
      }
      var p = koren.parentElement;
      while (p && p !== document.documentElement) {
        for (var c = 0; c < p.children.length; c++) dodaj(p.children[c]);
        p = p.parentElement;
      }
      if (document.elementsFromPoint) {
        var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
        var tocke = document.elementsFromPoint(cx, cy) || [];
        for (var t = 0; t < tocke.length; t++) dodaj(tocke[t]);
      }
      for (var k = 0; k < kandidati.length; k++) {
        var el = kandidati[k];
        var er = el.getBoundingClientRect();
        var ix = Math.max(0, Math.min(r.right, er.right) - Math.max(r.left, er.left));
        var iy = Math.max(0, Math.min(r.bottom, er.bottom) - Math.max(r.top, er.top));
        if (ix * iy > 0.8 * povrsina) {
          var cs = window.getComputedStyle(el);
          if (cs.position === 'absolute' || cs.position === 'fixed') {
            if (!el.hasAttribute('data-safeer-medij')) el.setAttribute('data-safeer-medij', '1');
          }
        }
      }
    }

    function oznaci() {
      var fs = document.fullscreenElement || document.webkitFullscreenElement;
      if (fs) {
        // V celozaslonskem nacinu ne plezamo (predniki so del strani); izvzamemo samo celozaslonski
        // element z videom, ker so gumbi predvajalnika v njem.
        if (fs.tagName !== 'VIDEO' && fs !== document.body && fs !== document.documentElement &&
            fs.getElementsByTagName('video').length > 0 && !fs.hasAttribute('data-safeer-medij')) {
          fs.setAttribute('data-safeer-medij', '1');
        }
        return;
      }
      var vs = document.getElementsByTagName('video');
      for (var i = 0; i < vs.length; i++) {
        var v = vs[i];
        if (!v.src && !v.currentSrc && v.readyState === 0) continue;
        var r = v.getBoundingClientRect();
        if (r.width < 50 || r.height < 50) continue;
        var koren = v.parentElement, e = v.parentElement;
        while (e && e !== document.body && e !== document.documentElement) {
          var cs = window.getComputedStyle(e);
          var ox = cs.overflowX || cs.overflow, oy = cs.overflowY || cs.overflow;
          if (ox === 'auto' || ox === 'scroll' || oy === 'auto' || oy === 'scroll') break;
          var b = e.getBoundingClientRect();
          if (b.width <= r.width * 1.15 + 4 && b.height <= r.height * 1.15 + 4) koren = e;
          else break;
          e = e.parentElement;
        }
        if (koren && koren !== document.body && koren !== document.documentElement) {
          if (!koren.hasAttribute('data-safeer-medij')) koren.setAttribute('data-safeer-medij', '1');
          oznaciPrekrivke(v, r, koren);
        }
      }
    }

    pocisti();
    oznaci();

    var MO = window.MutationObserver || (typeof MutationObserver !== 'undefined' ? MutationObserver : null);
    if (MO && !window.__safeerMedijObserver) {
      var cakaMo = 0;
      var mo = new MO(function() {
        if (cakaMo) return;
        cakaMo = setTimeout(function() {
          cakaMo = 0;
          try { pocisti(); oznaci(); } catch (e) { console.warn('Safeer medij koren:', e); }
        }, 250);
      });
      mo.observe(document.documentElement || document.body, { childList: true, subtree: true });
      window.__safeerMedijObserver = mo;
    }

    if (!window.__safeerMedijKoren) {
      window.__safeerMedijKoren = true;
      ['loadedmetadata', 'play', 'playing', 'resize', 'emptied', 'fullscreenchange', 'webkitfullscreenchange'].forEach(function(t) {
        document.addEventListener(t, function() {
          if (t === 'emptied') pocisti();
          oznaci();
        }, true);
      });
    }
  } catch(e) {
    console.warn('Safeer medij koren:', e);
  }
})();
"""

    /**
     * Razdeli niz selektorjev po vejicah ZUNAJ oklepajev, oglatih oklepajev in narekovajev.
     */
    fun razdeliSelektorje(izbirniki: String): List<String> {
        val rez = mutableListOf<String>()
        var zacetek = 0
        var oklepaj = 0
        var oglati = 0
        var narekovaj: Char? = null
        var ubezen = false

        for (i in izbirniki.indices) {
            val c = izbirniki[i]
            if (ubezen) {
                ubezen = false
                continue
            }
            if (c == '\\') {
                ubezen = true
                continue
            }
            if (narekovaj != null) {
                if (c == narekovaj) narekovaj = null
                continue
            }
            when (c) {
                '\'', '"' -> narekovaj = c
                '(' -> oklepaj++
                ')' -> if (oklepaj > 0) oklepaj--
                '[' -> oglati++
                ']' -> if (oglati > 0) oglati--
                ',' -> {
                    if (oklepaj == 0 && oglati == 0) {
                        val sel = izbirniki.substring(zacetek, i).trim()
                        if (sel.isNotEmpty()) rez.add(sel)
                        zacetek = i + 1
                    }
                }
            }
        }
        val zadnji = izbirniki.substring(zacetek).trim()
        if (zadnji.isNotEmpty()) rez.add(zadnji)
        return rez
    }

    /**
     * Poisce zacetek prvega psevdoelementa (::...) v selektorju zunaj oklepajev, oglatih oklepajev in narekovajev.
     * Vraca indeks prvega dvopicja ali -1, ce psevdoelementa ni.
     */
    fun najdiPsevdoelement(selektor: String): Int {
        var oklepaj = 0
        var oglati = 0
        var narekovaj: Char? = null
        var ubezen = false

        for (i in 0 until selektor.length - 1) {
            val c = selektor[i]
            if (ubezen) {
                ubezen = false
                continue
            }
            if (c == '\\') {
                ubezen = true
                continue
            }
            if (narekovaj != null) {
                if (c == narekovaj) narekovaj = null
                continue
            }
            when (c) {
                '\'', '"' -> narekovaj = c
                '(' -> oklepaj++
                ')' -> if (oklepaj > 0) oklepaj--
                '[' -> oglati++
                ']' -> if (oglati > 0) oglati--
                ':' -> {
                    if (oklepaj == 0 && oglati == 0 && selektor[i + 1] == ':') {
                        return i
                    }
                }
            }
        }
        return -1
    }

    /**
     * Pretvori posamezen selektor:
     * - Ce vsebuje psevdoelement (::...): filter postavi PRED psevdoelement (npr. input:where(...)::placeholder).
     * - Ce je globalni psevdoelement brez predpone (::-webkit-scrollbar, ::selection): pusti nespremenjenega.
     * - Sicer doda filter na konec: <selektor>[MEDIJ_FILTER].
     */
    fun pretvoriEnSelektor(selektor: String): String {
        val sel = selektor.trim()
        if (sel.isEmpty()) return ""

        val psevdoIdx = najdiPsevdoelement(sel)
        if (psevdoIdx >= 0) {
            val pred = sel.substring(0, psevdoIdx).trim()
            val za = sel.substring(psevdoIdx)
            // Globalni psevdoelement brez predhodnega elementa (npr. ::-webkit-scrollbar, ::selection)
            if (pred.isEmpty()) {
                return sel
            }
            return "$pred$MEDIJ_FILTER$za"
        }

        return "$sel$MEDIJ_FILTER"
    }

    /**
     * Rekurzivna pretvorba pravil v bloku CSS.
     */
    fun pretvoriPravila(cssVsebina: String): String {
        val sb = StringBuilder()
        var start = 0
        var i = 0
        var oklepaj = 0
        var oglati = 0
        var narekovaj: Char? = null
        var ubezen = false

        while (i < cssVsebina.length) {
            val c = cssVsebina[i]
            if (ubezen) {
                ubezen = false
                i++
                continue
            }
            if (c == '\\') {
                ubezen = true
                i++
                continue
            }
            if (narekovaj != null) {
                if (c == narekovaj) narekovaj = null
                i++
                continue
            }
            when (c) {
                '\'', '"' -> narekovaj = c
                '(' -> oklepaj++
                ')' -> if (oklepaj > 0) oklepaj--
                '[' -> oglati++
                ']' -> if (oglati > 0) oglati--
                ';' -> {
                    if (oklepaj == 0 && oglati == 0) {
                        // Enovita izjava brez bloka ({}), npr. @import ali @charset
                        sb.append(cssVsebina.substring(start, i + 1))
                        start = i + 1
                    }
                }
                '{' -> {
                    if (oklepaj == 0 && oglati == 0) {
                        val predpona = cssVsebina.substring(start, i)
                        val glava = predpona.trim()
                        val vodilniPresledki = predpona.takeWhile { it.isWhitespace() }

                        // Poisci ujemajoci zaklepaj '}'
                        var globina = 1
                        var j = i + 1
                        var vNarekovaju: Char? = null
                        var vUbezen = false
                        while (j < cssVsebina.length && globina > 0) {
                            val z = cssVsebina[j]
                            if (vUbezen) {
                                vUbezen = false
                                j++
                                continue
                            }
                            if (z == '\\') {
                                vUbezen = true
                                j++
                                continue
                            }
                            if (vNarekovaju != null) {
                                if (z == vNarekovaju) vNarekovaju = null
                                j++
                                continue
                            }
                            when (z) {
                                '\'', '"' -> vNarekovaju = z
                                '{' -> globina++
                                '}' -> globina--
                            }
                            j++
                        }

                        val telo = cssVsebina.substring(i + 1, j - 1)
                        val glavaMala = glava.lowercase()

                        sb.append(vodilniPresledki)
                        if (glavaMala.contains("keyframes") || glavaMala.startsWith("@font-face")) {
                            // @keyframes/@font-face notranjosti ne pokvari (pusti)
                            sb.append(glava).append(" {").append(telo).append("}")
                        } else if (glavaMala.startsWith("@media") || glavaMala.startsWith("@supports") || glavaMala.startsWith("@container")) {
                            // @media/@supports/@container: pretvori pravila znotraj
                            sb.append(glava).append(" {").append(pretvoriPravila(telo)).append("}")
                        } else if (glava.startsWith("@")) {
                            // drugi @ ukazi
                            sb.append(glava).append(" {").append(telo).append("}")
                        } else if (glava.isEmpty()) {
                            sb.append("{").append(telo).append("}")
                        } else {
                            // Redno pravilo z enim ali vec selektorji
                            val pretvorjeniSelektorji = razdeliSelektorje(glava).joinToString(", ") { sel ->
                                pretvoriEnSelektor(sel)
                            }
                            sb.append(pretvorjeniSelektorji).append(" {").append(telo).append("}")
                        }

                        start = j
                        i = j - 1
                    }
                }
            }
            i++
        }

        if (start < cssVsebina.length) {
            sb.append(cssVsebina.substring(start))
        }

        return sb.toString()
    }

    /**
     * Glavna funkcija za pretvorbo celotnega CSS niza.
     */
    fun pretvoriTemniCss(css: String): String {
        val brezKomentarjev = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).replace(css, "")
        return pretvoriPravila(brezKomentarjev)
    }
}
