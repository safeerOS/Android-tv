package si.safeer.tv.os

/**
 * Naloga 113: posnetek YouTube iz glasbenega seznama (npr. seznam z zalozniskimi posnetki, ki jih vgradni predvajalnik
 * zavrne) - ciste odlocitve brez Androida (preizkus: tests/YoutubeVrstaPravilaTest.kt).
 *
 * Prva pot je zvok prek nasega predvajalnika Media3 ([YoutubeZvok], odjemalec VISIONOS - ista pot kot »Predvajaj na
 * zvocniku«): ne potrebuje vgradnje (Error 150/152/153 ga ne zadeva), ne potrebuje strani m.youtube.com v WebView (ta na
 * Android TV obvisi brez sporocila) in ne odklepa DRM (posnetek z DRM ostane na spletni strani ali se preskoci).
 * Spletna stran ([SpletniIgralec]) je rezerva; ce v [ROK_ZASTOJA_MS] ne zacne igrati, gre vrsta naprej z obvestilom.
 */
object YoutubeVrstaPravila {
    enum class Pot { MEDIA3_ZVOK, SPLETNA_STRAN }

    /** Toliko casa ima stran (vgradni predvajalnik ali polna stran) od nalaganja, da zacne igrati. */
    const val ROK_ZASTOJA_MS = 15_000L

    /** Po toliko zaporednih zastojih vrsta obstane (ne preletimo celega seznama brez zvoka). */
    const val NAJVEC_ZAPOREDNIH_ZASTOJEV = 3

    /**
     * Kako zaceti posnetek iz vrste: posnetek YouTube gre najprej v Media3. Na zvocniku v omrezju odloca obstojeca pot
     * ([GlasbaStoritev.naZvocnikVVrsti]); posnetek, za katerega YouTube nedavno ni dal toka (DRM, ni na voljo), gre
     * naravnost na spletno stran - ne cakamo se enkrat na isti »ne«.
     */
    fun pot(jeYoutube: Boolean, video: Boolean, naZvocniku: Boolean, znanoNe: Boolean): Pot =
        // Zunanji pregled 3. 11. 2026: film iz vrste je sel v Media3 kot itag 140/251 (zvok brez slike) - video gre
        // naravnost na stran (kot kotVrsta).
        if (jeYoutube && !video && !naZvocniku && !znanoNe) Pot.MEDIA3_ZVOK else Pot.SPLETNA_STRAN

    /**
     * Stran obvisi: od nalaganja je minil rok, predvajanje ni nikoli steklo (polozaj 0, medij ni pripravljen), uporabnik
     * pa zeli, da igra (pavza ni zastoj). Prej je tak posnetek ostal v BUFFERING za vedno, brez sporocila.
     */
    fun zastoj(odNalaganjaMs: Long, polozajMs: Long, pripravljeno: Boolean, zelja: Boolean, nepreverjenoIgra: Boolean = false,
               nepreverjenoDolgo: Boolean = false): Boolean =
        zelja && !pripravljeno && polozajMs <= 0L && odNalaganjaMs >= ROK_ZASTOJA_MS &&
            !(nepreverjenoIgra && (nepreverjenoDolgo || odNalaganjaMs < ROK_ZASTOJA_MS + NEPREVERJENO_PODALJSEK_MS))

    /** Odlocitev vodje 5. 11. 2026 (N4): oglasi so obicajno krajsi od 60 s; posnetek z vsaj tem trajanjem je verjetno skladba. */
    const val NEPREVERJENO_DOLGO_S = 90.0
    /** Toliko zaporednih novih porocil okvirja z enakomerno narascajocim casom (porocilo vsako sekundo). */
    const val NEPREVERJENO_ENAKOMERNIH = 3

    /**
     * Sledenje nepreverjenemu znaku okvirja drugega izvora (SpletniIgralec): [igra] = znak v zadnjih [NEPREVERJENO_ROK_MS];
     * [dolgIgra] = poleg tega trajanje (d) vsaj [NEPREVERJENO_DOLGO_S] in cas t v zadnjih [NEPREVERJENO_ENAKOMERNIH]
     * porocilih narasca enakomerno (prirastek t med 0,5- in 1,5-kratnikom casa med porociloma po uri strani, [cas] = cas
     * prejema v ms). Samo za odlog zastoja: dolgIgra ga odlozi brez meje (skladba, ki igra, se ne preskoci), igra najvec za
     * [NEPREVERJENO_PODALJSEK_MS]. Stoji t (zamrznjen oglas, prazen predvajalnik) - stevec na 0, velja meja.
     */
    class NepreverjenoSledenje {
        private var zadnjiZnak = 0L
        private var prejT = -1.0
        private var prejCas = -1.0
        private var enakomernih = 0
        private var trajanje = 0.0

        fun znak(t: Double, d: Double, cas: Double, zdajMs: Long) {
            zadnjiZnak = zdajMs
            trajanje = if (d.isFinite() && d > 0.0) d else 0.0
            if (!t.isFinite() || t <= 0.0 || !cas.isFinite() || cas < 0.0) { enakomernih = 0; prejT = -1.0; prejCas = -1.0; return }
            if (cas == prejCas) return  // isto porocilo okvirja kot ob prejsnjem tiku
            if (prejT >= 0.0 && cas > prejCas) {
                val dt = t - prejT
                val du = (cas - prejCas) / 1000.0
                enakomernih = if (dt > 0.0 && dt >= du * 0.5 && dt <= du * 1.5) enakomernih + 1 else 0
            } else enakomernih = 0
            prejT = t
            prejCas = cas
        }

        fun igra(zdajMs: Long): Boolean = nepreverjenoIgra(zadnjiZnak, zdajMs)

        fun dolgIgra(zdajMs: Long): Boolean =
            igra(zdajMs) && trajanje >= NEPREVERJENO_DOLGO_S && enakomernih >= NEPREVERJENO_ENAKOMERNIH
    }

    /**
     * Peti slepi pregled 5. 11. 2026 (N4): okvir oglasa ima tudi nas posrednik s kljucem in vsako sekundo javi »igra« - zastoj
     * je bil preklican brez meje. Nepreverjen znak rok zastoja podaljsa najvec za toliko (na nalaganje strani), potem velja
     * pravi zastoj (preskok, NI_TOKA) - razen ce okvir javi dolg posnetek, ki enakomerno tece ([NepreverjenoSledenje.dolgIgra]).
     */
    const val NEPREVERJENO_PODALJSEK_MS = 30_000L

    /** Rok svezine znaka »okvir drugega izvora igra« (POSREDNIK_JS posilja vsako sekundo; STANJE_JS ga vrne do 3 s). */
    const val NEPREVERJENO_ROK_MS = 3_000L

    /**
     * Cetrti slepi pregled 5. 11. 2026 (N-E): okvir drugega izvora (predvajalnik na CDN, ni na IZVORI_PREDVAJALNIKA) je v
     * zadnjih [NEPREVERJENO_ROK_MS] javil, da igra. Uporablja se SAMO za preklic zastoja (skladba, ki igra v okvirju, ni vec
     * oznacena NI_TOKA in preskocena); nikoli za polozaj, konec ali preskok. [od] 0 = nikoli.
     */
    fun nepreverjenoIgra(od: Long, zdaj: Long): Boolean = od > 0L && zdaj >= od && zdaj - od < NEPREVERJENO_ROK_MS

    /** Po zastoju: naslednja skladba, ce obstaja in nismo ze [NAJVEC_ZAPOREDNIH_ZASTOJEV]-krat zapored obstali. */
    fun naprejPoZastoju(zaporedniZastoji: Int, imaNaslednjo: Boolean): Boolean =
        imaNaslednjo && zaporedniZastoji < NAJVEC_ZAPOREDNIH_ZASTOJEV

    /**
     * Lastnik 27. 10. 2026 13:48 (testni telefon): YouTube je zvocni tok zavrnil (HTTP 403) pri VSAKI skladbi albuma,
     * predvajalnik pa je vsake ~4 s skocil na naslednjo, brez zvoka in brez konca (»ko zelim prekiniti, tudi ne gre«).
     * Zavrnjen tok (403/410) glasbe z YouTuba: skladba gre na spletno stran (rezerva, kot prej), ne na naslednjo.
     */
    // Zunanji pregled 3. 11. 2026: 403 od googlevideo pri zvoku odpre stran vedno - tudi pri posnetku, ki je video
    // (prej je sel tak na naslednjo skladbo ali obstal).
    fun tokZavrnjen(http: Int, jeYoutube: Boolean): Boolean = jeYoutube && (http == 403 || http == 410)

    /**
     * Zunanji pregled 3. 11. 2026: vgradni predvajalnik (okvir youtube.com/embed) v roku ni dal polozaja - nalozimo polno
     * stran watch?v= v isti pogled (en dokument, en predvajalnik), ne istega okvirja znova in ne naslednje skladbe.
     * Zastoj polne strani gre naprej kot doslej ([zastoj], [naprejPoZastoju]).
     */
    fun poZastojuPolnaStran(vgradno: Boolean): Boolean = vgradno

    /** Toliko casa po odhodu z zaslona predvajanja mora polozaj strani narasti, sicer stran v ozadju stoji. */
    const val ROK_OZADJA_MS = 5_000L

    /**
     * Zunanji pregled 3. 11. 2026 (Philips, Android 11): stran, odpeta z zaslona predvajanja (pogled 1x1,
     * RENDERER_PRIORITY_WAIVED), WebView na nekaterih napravah ustavi, stanje pa je kazalo, da igra. Ce je ob odhodu igrala,
     * uporabnik zeli igrati, polozaj pa v [ROK_OZADJA_MS] ni narasel: posten premor (gumb Predvajaj v docku odpre cel zaslon
     * in sele tam play). Kjer stran v ozadju igra (zvok), igra naprej - lastnik 28. 10. 2026: YouTube igra v ozadju.
     */
    fun ozadjeStoji(zelja: Boolean, igralObOdhodu: Boolean, polozajObOdhodu: Long, polozajZdaj: Long, odOdhodaMs: Long): Boolean =
        zelja && igralObOdhodu && odOdhodaMs >= ROK_OZADJA_MS && polozajZdaj <= polozajObOdhodu

    /** Ko YouTube tok zavrne, gredo tudi naslednje skladbe toliko casa naravnost na stran (vsaka bi sicer izgubila ~4 s). */
    const val ZAVRNJEN_VELJA_MS = 30 * 60_000L

    /** Najvec napak predvajalnika zapored, nato predvajanje obstane z razlogom (ne preleti cele vrste brez zvoka). */
    const val NAJVEC_ZAPOREDNIH_NAPAK = 3

    /**
     * Po napaki predvajalnika naprej na naslednjo samo, ce naslednja obstaja, uporabnik ni ustavil ali dal na premor
     * ([zeliIgrati]) in ni bilo ze [NAJVEC_ZAPOREDNIH_NAPAK] napak zapored (stejejo se, dokler kaj ne zaigra).
     */
    fun naprejPoNapaki(zaporedneNapake: Int, imaNaslednjo: Boolean, zeliIgrati: Boolean): Boolean =
        imaNaslednjo && zeliIgrati && zaporedneNapake < NAJVEC_ZAPOREDNIH_NAPAK

    /**
     * Lastnik 27. 10. 2026 (testni telefon): posnetek YouTube, izbran na polici ali v zadetkih (ne iz seznama), je sel
     * mimo vrste naravnost na spletno stran (POLNI_POGON youtube.com, »stopnja=3«): sam v vrsti, zato Naprej ni delal in
     * ob koncu se je predvajalnik ugasnil. Glasba z YouTuba (ne video) zato igra kot vrsta posnetkov z zaslona - prek
     * Media3 kot seznami (naloga 113), z naslednjo skladbo ob koncu. Video ostane na strani (slika).
     */
    fun kotVrsta(jeYoutube: Boolean, video: Boolean): Boolean = jeYoutube && !video

    /**
     * Posamezen posnetek (nadaljuj, deljena povezava, predlogi): glasba z YouTuba gre najprej v Media3 (vrsta z enim
     * posnetkom), stran ostane rezerva. [dovoliPrevzem] false je ze rezerva po napaki Media3 - takrat stran, brez zanke.
     */
    fun posameznoPrekMedia3(jeYoutube: Boolean, video: Boolean, dovoliPrevzem: Boolean): Boolean =
        dovoliPrevzem && kotVrsta(jeYoutube, video)
}
