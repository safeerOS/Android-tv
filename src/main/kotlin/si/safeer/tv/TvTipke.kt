package si.safeer.tv

/**
 * Barvni gumbi daljinca v brskalniku. Brez Androida, preizkus v tests/TvTipkeTest.kt.
 *
 * TV 3. 11. 2026 (lastnik): na Cloudflare/captcha strani ni mogel vklopiti kazalca - ZELENI gumb na njegovem
 * Philipsu odpre sistemske Info/Vire (izmerjeno: getevent KEY_GREEN pride, aplikacija ga ne dobi). RDECEGA ne
 * potrebuje, zato kazalec vklopi tudi RDECI; MENU ostane hitri izbor strani.
 */
object TvTipke {
    enum class Dejanje { KAZALEC, HITRE_STRANI, ZAZNAMKI, NIC }

    private const val PROG_RED = 183
    private const val PROG_GREEN = 184
    private const val PROG_YELLOW = 185
    private const val MENU = 82
    private const val INFO = 165
    private const val BOOKMARK = 174

    fun dejanje(keyCode: Int): Dejanje = when (keyCode) {
        PROG_RED, PROG_GREEN, INFO -> Dejanje.KAZALEC
        MENU -> Dejanje.HITRE_STRANI
        PROG_YELLOW, BOOKMARK -> Dejanje.ZAZNAMKI
        else -> Dejanje.NIC
    }

    private const val MEDIA_PLAY_PAUSE = 85
    private const val MEDIA_PLAY = 126
    private const val MEDIA_PAUSE = 127

    /**
     * Pavza s tipke daljinca. Skript predvajanja v ozadju (UserScriptManager.BACKGROUND_PLAYBACK_JS) ignorira vsak pause(),
     * ki ni sledil uporabnikovemu dogodku v strani, nadzornik na 500 ms pa video, ki ga uporabnik ni pavziral, spet zazene.
     * Tipka daljinca JE uporabnikovo dejanje, zato pred pause() sprozimo keydown v oknu (isti dogodek, ki ga skript steje kot
     * uporabnika): pavza je potem trajna. Izmerjeno na TV 16. 11. 2026: brez tega pause() zavrzen; z zastavico _safeer_app_bg
     * pavza uspe, a jo nadzornik v isti sekundi razveljavi; s keydown ostane video 6 s na pavzi.
     */
    const val PAVZA_JS = "(function(){window.dispatchEvent(new Event('keydown'));return x.pause();})()"

    /**
     * Medijske tipke daljinca za video v strani (Google Android TV zahteve za kakovost, TV-PP). Izmerjeno 16. 11. 2026 na
     * televizorju: KEYCODE_MEDIA_PLAY_PAUSE med videom v brskalniku ni naredila nic. Vrne skript za vrhnji dokument ali null, ce
     * tipka ni medijska. Izbere predvajajoci video, sicer najvecjega z naloženimi podatki; brez videa ne stori nic.
     */
    fun jsMedijskaTipka(keyCode: Int): String? {
        val dejanje = when (keyCode) {
            MEDIA_PLAY_PAUSE -> "(x.paused ? x.play() : $PAVZA_JS)"
            MEDIA_PLAY -> "x.play()"
            MEDIA_PAUSE -> PAVZA_JS
            else -> return null
        }
        return "(function(){try{var v=Array.prototype.slice.call(document.querySelectorAll('video')).filter(function(e){return e.readyState>0;});" +
            "if(!v.length)return 'ni';var x=v.filter(function(e){return !e.paused;})[0]||v.sort(function(a,b){return b.clientWidth*b.clientHeight-a.clientWidth*a.clientHeight;})[0];" +
            "var r=$dejanje;if(r&&r.catch)r.catch(function(){});return 'ok';}catch(e){return 'napaka';}})()"
    }

    /**
     * Odgovor skripta jsMedijskaTipka (evaluateJavascript vrne JSON, torej "ni" z narekovaji): »ni« = v strani ni videa, zato
     * tipko dobi naslednja medijska seja (glasba v ozadju). »ok« in »napaka« ostaneta pri strani.
     */
    /** Kaj brskalnikova medijska seja stori s tipko (slepi pregled TV 18. 11. 2026, N1 in N5). */
    enum class MedijskaOdlocitev { NE_PORABI, NATIVNO, PORABI, SKRIPT, POSREDUJ }

    /**
     * Odlocitev za tipko, ki jo dobi seja brskalnika: brskalnik ni v ospredju -> NE_PORABI (tipka ni nasa, sistem jo da
     * naprej; prej je bila porabljena in izgubljena, glasba po obisku brskalnika ni slisala tipke); lastni predvajalnik
     * ima tipko sam; tipko, ki smo jo ravnokar posredovali, ignoriramo (zanka); 85/126/127 gredo v stran; druge
     * medijske tipke (naprej, nazaj, stop, previjanje) gredo naprej glasbi (prej: return false, tipka se je izgubila).
     */
    fun medijskaOdlocitev(ospredje: Boolean, nativno: Boolean, posredujem: Boolean, imaSkript: Boolean): MedijskaOdlocitev = when {
        !ospredje -> MedijskaOdlocitev.NE_PORABI
        nativno -> MedijskaOdlocitev.NATIVNO
        posredujem -> MedijskaOdlocitev.PORABI
        imaSkript -> MedijskaOdlocitev.SKRIPT
        else -> MedijskaOdlocitev.POSREDUJ
    }

    fun medijskiOdgovorPosreduj(odgovor: String?): Boolean = odgovor?.trim()?.trim('"') == "ni"

    /** Toliko ponovitev drzanega OK (~1 s) vklopi kazalec v nacinu D-pad. */
    const val PONOVITEV_ZA_KAZALEC = 12

    /** Drzan OK na strani (ne v vrstici zgoraj) v nacinu D-pad vklopi kazalec; v nacinu miske je drzan OK vlecenje. */
    fun drzanOkVkljuciKazalec(keyCode: Int, ponovitev: Int, kazalecViden: Boolean, vVrstici: Boolean): Boolean =
        (keyCode == 23 || keyCode == 66) && ponovitev == PONOVITEV_ZA_KAZALEC && !kazalecViden && !vVrstici

    /**
     * Ali GOR s strani gre v naslovno vrstico. [odgovor]: kar vrne window._safeer_navigate_spatial('UP') (»-1« = v strani
     * ni cilja visje in stran je na vrhu; »null« ali prazno = skripta daljinca v dokumentu se ni). Zunanji pregled fokusa
     * 3. 11. 2026: v nacinu aplikacije se skripta vstavi sele pri 35 % nalaganja (onProgressChanged) - prvi GOR v se
     * praznem dokumentu je zapustil aplikacijo. Med nalaganjem ([napredek] < 100) GOR v nacinu aplikacije zato ne odide;
     * ko je stran nalozena in skripte ni (stran brez nase navigacije), GOR vrstico pokaze kot prej - uporabnik ni ujet.
     */
    /**
     * OK na polju za datoteko: [odgovor] je, kar vrne window._safeer_click_focused_card('[izziv]') (tv_spatial.js
     * SAFEER_OK_DATOTEKA). TV 4. 11. 2026 (w3schools tryhtml5_input_type_file): klik iz skripta nima uporabnikove
     * aktivacije, Chromium izbirnika datotek ne odpre. Pri polju za datoteko ima polje fokus in aplikacija poslje pravo
     * tipko Enter. Drugi krog pregleda 5. 11. 2026: stran je funkcijo lahko zasedla pred nasim skriptom in sama vrnila
     * "datoteka" - velja samo "datoteka:" + [odtisZetona] zetona strani ([zeton], pozna ga samo nas skript) in izziva tega
     * OK (tv_spatial.js SAFEER_DOKAZ). Vse drugo (golo "datoteka", true, false, null) ostane navaden klik skripta.
     */
    fun okNaDatoteki(odgovor: String?, zeton: String, izziv: String): Boolean {
        if (zeton.length < 32 || !IZZIV.matches(izziv)) return false
        val o = (odgovor ?: "").trim().trim('"')
        val pricakovan = "datoteka:" + odtisZetona(zeton, "datoteka|$izziv")
        return java.security.MessageDigest.isEqual(o.toByteArray(Charsets.UTF_8), pricakovan.toByteArray(Charsets.UTF_8))
    }

    private val IZZIV = Regex("[0-9a-f]{16,64}")

    /** Odgovor skripta je natanko [predpona] + ':' + odtis zetona strani in niza (konstanten cas); drugo ne velja. */
    private fun odgovorVelja(odgovor: String?, zeton: String, izziv: String, predpona: String, niz: String): Boolean {
        if (zeton.length < 32 || !IZZIV.matches(izziv)) return false
        val o = (odgovor ?: "").trim().trim('"')
        val pricakovan = predpona + ":" + odtisZetona(zeton, niz)
        return java.security.MessageDigest.isEqual(o.toByteArray(Charsets.UTF_8), pricakovan.toByteArray(Charsets.UTF_8))
    }

    /**
     * Tretji neodvisni pregled 5. 11. 2026 (N3): pravo dejanje (Enter na polju za datoteko ali dotik na oznako) aplikacija
     * izvede sele, ko skript tik pred njim cilj preveri znova (tv_spatial.js SAFEER_PREVERBA_CILJA, [nacin] 'enter' ali
     * 'dotik') in vrne 'cilj:' + odtis zetona strani in nakljucnega [izziv].
     */
    fun ciljVelja(odgovor: String?, zeton: String, izziv: String, nacin: String): Boolean =
        (nacin == "enter" || nacin == "dotik") && odgovorVelja(odgovor, zeton, izziv, "cilj", "cilj|$nacin|$izziv")

    /**
     * Tretji neodvisni pregled 5. 11. 2026 (odlocitev vodje): OK v tuj okvir ne naredi pravega dotika; skript vrne
     * 'kazalec:' + odtis zetona in izziva tega OK, aplikacija pokaze namig za kazalec (enkrat na stran).
     */
    fun namigKazalca(odgovor: String?, zeton: String, izziv: String): Boolean =
        odgovorVelja(odgovor, zeton, izziv, "kazalec", "kazalec|$izziv")

    /** Nakljucen izziv za en OK (32 sestnajstiskih znakov). */
    fun novIzziv(): String {
        val b = ByteArray(16)
        java.security.SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /**
     * Cetrti slepi pregled 5. 11. 2026 (N-B, N-D, odlocitev vodje 3): dokaze, ki jih stran lahko zbira, preverjamo s
     * HMAC-SHA256 (kljuc = zeton strani, 192 bitov iz SpletVarnostPravila.novZeton; sporocilo = niz z izzivom). Prej FNV-1a
     * 32 bitov z zetonom na obeh koncih: notranje stanje je le 32 bitov, zato odtis ni MAC - stran, ki je zbirala odtise
     * (orakelj), bi lahko ugibala ali sestavila veljaven odgovor; dva FNV po 32 bitov bi bila se vedno linearna in
     * kratka. tv_spatial.js racuna isti HMAC z lastno izvedbo SHA-256 (samo operatorji in lastne tabele, brez metod
     * strani; SAFEER_HMAC). Skupni testni vektorji: tests/TvTipkeTest.kt in tests/tv_hmac_test.js.
     * Rezultat: 64 sestnajstiskih znakov (256 bitov); "" za prazen zeton.
     */
    fun odtisHex(zeton: String, niz: String): String {
        if (zeton.isEmpty()) return ""
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(zeton.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(niz.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** Odgovor skripta za dokaz: "safeer-" + [odtisHex] (kot tv_spatial.js odtisZetona). */
    fun odtisZetona(zeton: String, niz: String): String = "safeer-" + odtisHex(zeton, niz)

    /**
     * N-B (cetrti slepi pregled 5. 11. 2026): ziva kopija skripta s prejsnjim nonce velja samo, ce na nakljucni [izziv] odgovori
     * 'ziv:' + odtis zetona PREJSNJE strani - zastavico z znanim imenom lahko postavi tudi nova stran, zetona pa ne pozna.
     */
    fun zivaKopijaVelja(odgovor: String?, zetonPrej: String, izziv: String): Boolean =
        odgovorVelja(odgovor, zetonPrej, izziv, "ziv", "ziv|$izziv")

    /**
     * Pregled Groka 5. 11. 2026 (najdbe 3, 7, 10): _safeer_navigate_spatial, _safeer_click_focused_card in _safeer_highlight
     * doseze tudi stran - z njimi je premikala izbiro. Pred vsakim klicem ob tipki daljinca aplikacija v istem skriptu poslje
     * nakljucen izziv in odtis zetona strani (en dokaz = en klic, tv_spatial.js SAFEER_TIPKA). Brez zetona prazno.
     * Neodvisni pregled 5. 11. 2026 (najdbi 5 in 6): dokaz pokrije strogo narascajoc [stevec] pogleda (starejsi ujeti par ne
     * velja vec), ime izvoza ima nakljucni del strani [nonce] (stran ga ne more vnaprej zasesti).
     */
    fun ukazTipke(zeton: String?, nonce: String?, stevec: Long): String {
        if (zeton == null || zeton.length < 32 || !jeNonce(nonce) || stevec <= 0L) return ""
        val izziv = novIzziv()
        val f = izvoz(nonce, "_safeer_tipka")
        return "$f&&$f('$izziv',$stevec,'${odtisHex(zeton, "tipka|$stevec|$izziv")}');"
    }

    private val NONCE = Regex("[0-9a-f]{16,64}")
    private fun jeNonce(n: String?): Boolean = n != null && NONCE.matches(n)

    /** Ime izvoza tv_spatial.js na window z nakljucnim delom strani; brez veljavnega dela staro ime (ga ni - klic ne naredi nic). */
    fun izvoz(nonce: String?, ime: String): String = if (jeNonce(nonce)) "window.${ime}_$nonce" else "window.$ime"

    /** Premor po zadnji tipki GOR, v katerem nova tipka ne sme v naslovno vrstico (stran se je pravkar premikala navzgor). */
    const val PREMOR_NASLOVNA_MS = 400L
    private var zadnjiGorMs = Long.MIN_VALUE / 2

    /**
     * Lastnik 11. 11. 2026 (brskalnik za zgled): na poti z dna strani navzgor ne smeta kazalec ne fokus sama skociti v naslovno
     * vrstico. V vrstico sme samo NAMERNA tipka GOR: svez pritisk (ne ponovitev drzane tipke) in ne prej kot [PREMOR_NASLOVNA_MS]
     * po prejsnji tipki GOR (sicer hitro tipkanje/drzanje na vrhu stran prehiti in skoci v vrstico). Klici belezijo cas.
     */
    @Synchronized fun gorPritisk(ponovitev: Int, zdajMs: Long): Boolean {
        val smem = smeVNaslovnoVrstico(ponovitev, zdajMs - zadnjiGorMs)
        zadnjiGorMs = zdajMs
        return smem
    }

    fun smeVNaslovnoVrstico(ponovitev: Int, msOdPrejsnjegaGor: Long): Boolean =
        ponovitev == 0 && msOdPrejsnjegaGor >= PREMOR_NASLOVNA_MS

    fun gorVNaslovnoVrstico(odgovor: String?, nacinAplikacije: Boolean, napredek: Int): Boolean {
        val o = (odgovor ?: "").trim().trim('"')
        if (o == "-1") return true
        if (o.isEmpty() || o == "null") return !(nacinAplikacije && napredek < 100)
        return false
    }
}
