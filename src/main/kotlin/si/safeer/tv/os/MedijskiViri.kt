package si.safeer.tv.os

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Viri, ki jih doda uporabnik sam: streznik PeerTube (video), neposreden tok (radio, glasba,
 * video po naslovu) ali spletna stran, ki jo odpre brskalnik Safeer - jedro Safeer OS, ki predvaja vse. Kje vire najde, je njegova stvar; Safeer jih samo predvaja cisto - promet
 * gre skozi Scit kot ves ostali. Shranjeni so samo na tej napravi.
 */
object MedijskiViri {
    private const val NASTAVITVE = "safeer_mediji"
    private const val KLJUC = "viri"
    private const val ISKANJA = "iskanja"
    private const val PRILJUBLJENE = "priljubljene"
    private const val SEZNAMI = "seznami"
    private const val NEDAVNO = "nedavno"
    private const val MAX_NEDAVNO = 5

    data class Vir(val tip: String, val ime: String, val naslov: String) {
        val jePeerTube get() = tip == PEERTUBE
        val jeSplet get() = tip == SPLET
        val jeSeznam get() = tip == SEZNAM
        val jePodkast get() = tip == PODKAST
        val jeStremio get() = tip == STREMIO
        val jeDodatek get() = jeStremio
        /** Naloga 69: imenik radijskih postaj (Radio Browser, TuneIn), ki ga je uporabnik dodal sam. */
        val jeImenik get() = tip == IMENIK
        /** Naloga 89: besedila pesmi (LRCLIB), vir, ki ga je uporabnik dodal sam. */
        val jeBesedila get() = tip == BESEDILA
    }

    /** Dodatki, ki jih uporabnik vnese SAM (Stremio: naslov manifesta). Brez prilozenih. */
    const val STREMIO = "stremio"
    /**
     * Dodatkov Kodi Safeer ne more poganjati (programi za aplikacijo Kodi), zato jih ne ponujamo vec (lastnik,
     * 2. 10. 2026: "ce ne moremo necesa poganjati, tudi dodatkov ne ponujamo"). Vrsta ostane le, da prej shranjene
     * vnose prepoznamo in izpustimo.
     */
    private const val KODI_OPUSCENO = "kodi"

    /** Rezultat preverjanja naslova dodatka: (naslov ali null, kljuc napake za besedilo). */
    fun preveriDodatek(tip: String, vnos: String): Pair<String?, String> {
        var v = vnos.trim()
        if (v.isEmpty()) return null to "prazno"
        if (tip == STREMIO && v.lowercase().startsWith("stremio://")) v = "https://" + v.substring("stremio://".length)
        val u = try { android.net.Uri.parse(v) } catch (_: Exception) { null }
        if (u == null || u.scheme !in listOf("http", "https") || u.host.isNullOrBlank()) return null to "naslov"
        if (tip == STREMIO) {
            // Uporabnik z daljincem ne bo tipkal "/manifest.json": sprejmemo tudi stran dodatka
            // (".../configure", ".../" ali brez konca) in manifest dopolnimo sami.
            var pot = (u.path ?: "").trimEnd('/')
            if (!pot.lowercase().endsWith("/manifest.json")) {
                if (pot.lowercase().endsWith("/configure")) pot = pot.substring(0, pot.length - "/configure".length)
                pot = "$pot/manifest.json"
            }
            return u.buildUpon().path(pot).query(null).fragment(null).build().toString() to ""
        }
        return u.buildUpon().fragment(null).build().toString() to ""
    }

    /**
     * Shrani dodatek (podvojeni naslov le vrne obstojecega). Webtor rezerva (25. 10. 2026): naslov s poverilnico (osebni
     * zeton Webtorja, debrid kljuc v nastavitvah) se shrani kot nadomestek brez skrivnosti, skrivnost sifrirana
     * ([SkrivniDodatki]); null = sifriranje ni uspelo - pravega naslova ne shranimo.
     */
    fun dodajDodatek(ctx: Context, tip: String, naslovVnosa: String, ime: String, prejeto: Boolean = false): Vir? {
        // Pregled U5: dodatek s poverilnico samo po https (zeton v naslovu po http potuje necifriran).
        if (tip == STREMIO && SkrivnostiPravila.skrivnaPredponaDodatka(naslovVnosa) != null && !naslovVnosa.trim().startsWith("https://")) return null
        val naslov = if (tip == STREMIO && SkrivnostiPravila.skrivnaPredponaDodatka(naslovVnosa) != null)
            SkrivniDodatki.zavaruj(ctx, naslovVnosa) ?: return null else naslovVnosa
        val obstojeci = vsi(ctx).firstOrNull { it.tip == tip && it.naslov == naslov }
        if (obstojeci != null) return obstojeci
        val v = Vir(tip, ime.ifBlank { android.net.Uri.parse(naslov).host ?: naslov }, naslov)
        // Samo rocno dodani: viri iz spletnih aplikacij te naprave nastanejo sproti (vsi) in se ne shranjujejo.
        // [prejeto]: dodatek z druge naprave v ozjem krogu (UskladitevKljucev) - ni dodan tu ([dodanTu]).
        shrani(ctx, rocni(ctx) + v, prejeto = prejeto)
        // Dodatek s poverilnico gre sam na naprave v ozjem krogu (UskladitevKljucev).
        if (SkrivnostiPravila.jeNadomestek(naslov)) UskladitevKljucev.sprozi(ctx)
        return v
    }

    const val PEERTUBE = "peertube"
    const val TOK = "tok"
    /** Spletna stran z glasbo ali videom: odpre jo brskalnik Safeer (jedro Safeer OS), ki predvaja vse. */
    const val SPLET = "splet"
    /** Uporabnikov API za iskanje (naslov z {q}, po zelji "| Glava: vrednost"). */
    const val API = "api"
    /** Seznam .m3u/.pls z vec skladbami: ob dodajanju ga preberemo, skladbe najde tudi iskanje. */
    const val SEZNAM = "seznam"
    /** RSS podkasta: klik odpre epizode. */
    const val PODKAST = "podkast"
    /**
     * Naloga 69: imenik radijskih postaj kot uporabnikov vir - naslov je [OsPravila.IMENIK_RADIO_BROWSER] ali
     * [OsPravila.IMENIK_TUNEIN]. Z njim delujeta obstojeca Radio (Radio Browser) in TuneIn: domace postaje, zvrsti,
     * iskanje. Brez njega na ta streznika ne gre nobena zahteva. Med napravami v Linku ne potuje (ni v
     * [VRSTE_ZA_USKLADITEV]): na drugi napravi ga uporabnik doda sam.
     */
    const val IMENIK = "imenik"

    /**
     * Naloga 89: besedila pesmi iz LRCLIB (lrclib.net) kot uporabnikov vir - brez njega Safeer besedil ne isce (naslov in
     * izvajalec bi sla tujemu strezniku). Med napravami v Linku ne potuje (kot imenik postaj).
     */
    const val BESEDILA = "besedila"
    const val NASLOV_LRCLIB = "https://lrclib.net"

    /** Ali je uporabnik dodal vir besedil (brez omrezja; branje nastavitev). */
    fun imaBesedila(ctx: Context): Boolean = rocni(ctx).any { it.tip == BESEDILA }

    /** Ali je uporabnik dodal imenik postaj [kateri] (brez omrezja). */
    fun imaImenik(ctx: Context, kateri: String): Boolean = rocni(ctx).any { it.tip == IMENIK && it.naslov == kateri }

    private const val ODSTRANJENI_VIRI = "odstranjeni_viri"

    /**
     * Vgrajeni spletni viri (naloga 57; lastnik, 22. 10. 2026: »Brez privzetih virov, tudi obstojecim izbrisi.« in
     * »Uporabnik si sam doloci vire; kar ima na svojih napravah ze nalozeno, pa prikazemo.«): koda ne doda nobenega
     * spletnega vira sama - ne Jamendo, Radio Browser, TuneIn, vgrajenih streznikov PeerTube, Archive.org (javna last,
     * arhiv v iskanju), imenika podkastov, vgrajenih kanalov TV v zivo ne kataloga Cinemeta.
     *
     * Vgrajeni viri niso bili nikoli shranjeni med uporabnikovimi ([KLJUC]); koda jih je dodajala sproti. Zato jih
     * obstojecim uporabnikom ni treba brisati iz shrambe: od te razlicice se ne uporabljajo vec, uporabnikovi viri
     * (tudi Cinemeta ali PeerTube, ce ju je dodal sam) pa ostanejo, kot so, in se brisejo kot vsak vir ([odstrani]).
     * Vsebina z uporabnikovih naprav (ta naprava, Safeer Link, Torrent) ni spletni vir in ostane.
     */
    const val VGRAJENI_SPLETNI_VIRI = false

    fun odstranjeni(ctx: Context): Set<String> {
        val s = ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).getString(ODSTRANJENI_VIRI, "[]") ?: "[]"
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    private fun zapomniOdstranjen(ctx: Context, naslov: String) {
        val g = gostiteljVira(naslov)
        if (g.isBlank()) return
        val zdaj = odstranjeni(ctx) + g
        ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit()
            .putString(ODSTRANJENI_VIRI, JSONArray(zdaj.toList()).toString()).apply()
    }

    private fun prekliciOdstranjen(ctx: Context, naslov: String) {
        val g = gostiteljVira(naslov)
        if (g.isBlank()) return
        val zdaj = odstranjeni(ctx) - g
        ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit()
            .putString(ODSTRANJENI_VIRI, JSONArray(zdaj.toList()).toString()).apply()
    }

    private fun rocni(ctx: Context): List<Vir> {
        preseliSkrivne(ctx)
        val a = try { JSONArray(ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).getString(KLJUC, "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Vir(it.optString("tip"), it.optString("ime"), it.optString("naslov")) }
            .map { v ->
                // Prehod 0.5.108 -> 0.5.109 (izmerjeno 23. 10. 2026 na telefonu): radio-browser.info / tunein.com, dodana pred
                // nalogo 69, sta shranjena kot spletni strani in Radio ostane prazen. Beremo ju kot imenik postaj.
                val kateri = if (v.tip == SPLET) OsPravila.imenikPostaj(v.naslov) else null
                if (kateri == null) v
                else Vir(IMENIK, if (kateri == OsPravila.IMENIK_TUNEIN) "TuneIn" else "Radio Browser", kateri)
            }.distinctBy { it.tip + "|" + it.naslov }
    }

    /**
     * Vsi viri za Safeer Media: ročno dodani viri + samodejno prebrane spletne aplikacije,
     * ki jih uporabnik doda v Safeer OS. Dvojniki po gostitelju so izločeni.
     */
    fun vsi(ctx: Context): List<Vir> {
        val shranjeni = rocni(ctx).filterNot { it.tip == KODI_OPUSCENO || PeerTube.jeBlokiran(it.naslov) || (it.jeSplet && jeImenikVirov(it.naslov)) }
        val spletne = try { SpletneAplikacije.seznam(ctx) } catch (_: Throwable) { emptyList() }
        val prepovedani = odstranjeni(ctx)
        val samodejni = spletne.mapNotNull { app ->
            val url = app.url.trim()
            if (url.isBlank() || !url.startsWith("http")) null
            else {
                val g = gostiteljVira(url)
                if (g.isBlank() || g in prepovedani || PeerTube.jeBlokiran(g) || jeImenikVirov(url)) null
                else Vir(SPLET, app.ime.ifBlank { g }, url)
            }
        }
        val vsiZbrani = mutableListOf<Vir>()
        val videneDomene = mutableSetOf<String>()
        for (v in shranjeni) {
            val g = gostiteljVira(v.naslov)
            if (g.isNotBlank()) videneDomene += g
            vsiZbrani += v
        }
        for (v in samodejni) {
            val g = gostiteljVira(v.naslov)
            if (g.isNotBlank() && g !in videneDomene) {
                videneDomene += g
                vsiZbrani += v
            }
        }
        return vsiZbrani
    }

    /** Spletna aplikacija je lahko tudi vir Safeer Media, vendar seznama ostajata locena. */
    fun spletniVir(ctx: Context, naslov: String): Vir? =
        vsi(ctx).firstOrNull { it.jeSplet && istaSpletnaStran(it.naslov, naslov) }

    /** Obstojeci vnos za isti vir, tudi ce je naslov zapisan z www/m, / ali parametri. */
    fun obstojeciVir(ctx: Context, naslov: String): Vir? {
        val cisto = naslov.trim().substringBefore('|').trim().let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
        }
        // Lastnik, 26. 10. 2026: vir z racunom je v nastavitvah nadomestek - isti racun prepoznamo brez prenosa (prijave).
        IptvPravila.skrivnostVira(SEZNAM, cisto)?.let { SkrivnostiPravila.nadomestek(it) }?.let { n ->
            vsi(ctx).firstOrNull { IptvPravila.jeVrstaRacuna(it.tip) && it.naslov == n }?.let { return it }
        }
        return vsi(ctx).firstOrNull { v ->
            if (v.jeSplet) istaSpletnaStran(v.naslov, cisto)
            else kanonicniNaslov(v.naslov) == kanonicniNaslov(cisto)
        }
    }

    /** Spletno aplikacijo brez ponovnega omreznega preverjanja vklopi kot medijski vir. */
    fun dodajSpletniVir(ctx: Context, naslov: String, ime: String): Vir? {
        val cisto = naslov.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        if (PeerTube.jeBlokiran(cisto)) return null
        if (!jePredvajljiv(cisto)) return null
        val gostitelj = try { URL(cisto).host } catch (_: Exception) { return null }
        prekliciOdstranjen(ctx, cisto)
        spletniVir(ctx, cisto)?.let { return it }
        val vir = Vir(SPLET, ime.ifBlank { gostitelj.removePrefix("www.") }, cisto)
        shrani(ctx, rocni(ctx).filterNot { istaSpletnaStran(it.naslov, cisto) } + vir)
        return vir
    }

    private fun gostiteljVira(url: String): String = try {
        val u = url.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        URL(u).host.lowercase().removePrefix("www.").removePrefix("m.").trimEnd('.')
    } catch (_: Exception) { "" }

    /**
     * Strani, ki niso vsebina, ampak imeniki virov (seznam dodatkov Stremio, trgovine, repozitoriji):
     * iz njih splosni bralnik "najde" kartice dodatkov in jih zlozi med serije in videe (tablica, 1. 10. 2026).
     */
    private val IMENIKI_VIROV = setOf(
        "stremio-addons.net", "beta.stremio-addons.net", "stremio-addons.com", "addons.stremio.com", "stremio.com",
        "github.com", "gitlab.com", "codeberg.org", "play.google.com", "apps.apple.com", "f-droid.org",
        "addons.mozilla.org", "chromewebstore.google.com", "safeer.si"
    )
    fun jeImenikVirov(url: String): Boolean = gostiteljVira(url).let { g -> g.isNotBlank() && IMENIKI_VIROV.any { g == it || g.endsWith(".$it") } }

    /** Pri spletni aplikaciji je vir domena, ne posamezna podstran ali sledilni parameter. */
    private fun istaSpletnaStran(a: String, b: String): Boolean {
        val x = gostiteljVira(a); val y = gostiteljVira(b)
        return x.isNotBlank() && x == y
    }

    private fun kanonicniNaslov(url: String): String = try {
        val u = URL(url)
        val host = u.host.lowercase().removePrefix("www.").removePrefix("m.").trimEnd('.')
        val vrata = u.port.takeIf { it > 0 && it != u.defaultPort }?.let { ":$it" }.orEmpty()
        "$host$vrata${u.path.trimEnd('/')}" + u.query?.let { "?$it" }.orEmpty()
    } catch (_: Exception) { url.trim().trimEnd('/').lowercase() }

    // ------------------------------------------------------------------ priljubljene

    /**
     * Seznam predvajanja, ki si ga je uporabnik shranil med priljubljene. [cas] = zadnja sprememba (ms; 0 = seznam iz
     * casa pred usklajevanjem med napravami), [vir] = od kod je uvozen (YouTube, Spotify ali prazno). Glej [SeznamiSink].
     */
    data class Seznam(val ime: String, val skladbe: List<Jamendo.Skladba>, val cas: Long = 0L, val vir: String = "")

    /**
     * Ali skladbo lahko shranimo: splet, radio, Jamendo, PeerTube (datoteko ob predvajanju vprasamo
     * znova). Datoteke z naprav ne - njihov naslov velja samo za trenutno povezavo Safeer Linka.
     */
    fun shranljiva(s: Jamendo.Skladba): Boolean {
        // Vgrajeni kanal v zivo ima vrsto vsebine (HLS) kot datoteka z naprave, a je spletni prenos: gre med nazadnje
        // predvajane in priljubljene (izmerjeno 5. 10. 2026: predvajan vgrajeni kanal ni bil »Nazadnje predvajano«).
        if (s.id.startsWith("tv:") && s.zvok.startsWith("https://")) return true
        val naprava = s.mime.isNotBlank() && s.mime != STRAN && s.streznik.isBlank()
        // Glasba s spletnega vira (stran posnetka) in uvozene skladbe: shranimo stran, tok poiscemo ob predvajanju.
        if (UvozSeznama.jeIskana(s) || (SpletniVir.jeEnota(s) && !s.video && s.povezava.startsWith("https://"))) return true
        return !naprava && (s.zvok.startsWith("https://") || (s.video && s.streznik.isNotBlank()))
    }

    /** V seznam predvajanja gre tudi video s spletnega vira (uvozen seznam posnetkov). */
    private fun zaSeznam(s: Jamendo.Skladba) = shranljiva(s) || (SpletniVir.jeEnota(s) && s.povezava.startsWith("https://"))

    private fun zaShranjevanje(s: Jamendo.Skladba) = when {
        // Webtor rezerva: tok dodatka s poverilnico v poti (ce ga ni bilo mogoce zakriti z nadomestkom) se ne shrani -
        // kartico dodatka ob predvajanju razresimo znova (Stremio: tokove vprasamo vedno na novo).
        // Pregled S3: samo kartice dodatka s poverilnico - navadnim dodatkom (CDN z dolgim id-jem) tok ostane.
        Stremio.jeEnota(s) && Stremio.razstavi(s)?.first.orEmpty().let { o ->
            SkrivnostiPravila.odstraniTokObShranjevanju(o, s.zvok) || SkrivnostiPravila.odstraniTokObShranjevanju(o, s.povezava) } ->
            s.copy(zvok = "", mime = "",
                povezava = if (SkrivnostiPravila.odstraniTokObShranjevanju(Stremio.razstavi(s)?.first.orEmpty(), s.povezava)) "" else s.povezava)
        // Naloga 96: kanal seznama IPTV ima v [Jamendo.Skladba.streznik] seznam (ne PeerTube) - tok ostane.
        s.video && s.streznik.isNotBlank() && !IptvSeznami.jeKanal(s) -> s.copy(zvok = "", mime = "")
        // Ujeti tok strani velja le kratek cas: shranimo stran, tok dobimo znova ob predvajanju.
        SpletniVir.jeEnota(s) -> s.copy(zvok = "", mime = "")
        else -> s
    }

    /**
     * Shranjen vgrajeni kanal (»tv:«) velja po trenutnem seznamu vgrajenih kanalov: ime in naslov prenosa se s
     * posodobitvijo aplikacije lahko spremenita, shranjena kopija pa ne. Kanal, ki ga v seznamu ni vec, ostane, kot je.
     */
    private fun osveziVgrajene(s: List<Jamendo.Skladba>): List<Jamendo.Skladba> {
        if (s.none { it.id.startsWith("tv:") }) return s
        val vgrajeni = try { TvVZivo.kanali().associate { it.skladba.id to it.skladba } } catch (_: Exception) { emptyMap() }
        return s.map { if (it.id.startsWith("tv:")) vgrajeni[it.id] ?: it else it }
    }

    fun priljubljene(ctx: Context): List<Jamendo.Skladba> = osveziVgrajene(beriSkladbe(beri(ctx, PRILJUBLJENE)))

    fun jePriljubljena(ctx: Context, s: Jamendo.Skladba) = priljubljene(ctx).any { it.id == s.id }

    /**
     * Doda ali odstrani; vrne, ali je skladba zdaj (shranjena) med priljubljenimi. Pregled 25. 10. (N9, N10): meja
     * [NAJVEC_PRILJUBLJENIH] odreze najprej najstarejse skladbe, ne kanalov ([PriljubljeniKanaliPravila.omeji]); vrnjeno
     * stanje je po shranjenem seznamu; hkrati z [zamenjaj] in [urediPriljubljene] ne tece (isti zaklep).
     */
    @Synchronized
    fun preklopiPriljubljeno(ctx: Context, s: Jamendo.Skladba): Boolean {
        val zdaj = priljubljene(ctx)
        val nova = when {
            zdaj.any { it.id == s.id } -> zdaj.filterNot { it.id == s.id }
            // 25. 10. 2026: kanal v zivo gre za zadnji priljubljeni kanal - red, ki ga je uporabnik uredil, ostane.
            PriljubljeniKanaliPravila.jeKanal(s.id, Stremio.jeVZivo(s)) -> {
                val kanali = zdaj.filter { PriljubljeniKanaliPravila.jeKanal(it.id, Stremio.jeVZivo(it)) }.map { it.id }.toHashSet()
                val poId = (zdaj + zaShranjevanje(s)).associateBy { it.id }
                PriljubljeniKanaliPravila.dodaj(zdaj.map { it.id }, { it in kanali }, s.id).mapNotNull { poId[it] }
            }
            else -> listOf(zaShranjevanje(s)) + zdaj
        }
        val kanaliNovi = nova.filter { PriljubljeniKanaliPravila.jeKanal(it.id, Stremio.jeVZivo(it)) }.map { it.id }.toHashSet()
        val poIdNovi = nova.associateBy { it.id }
        val shrani = PriljubljeniKanaliPravila.omeji(nova.map { it.id }, { it in kanaliNovi }, NAJVEC_PRILJUBLJENIH).mapNotNull { poIdNovi[it] }
        pisi(ctx, PRILJUBLJENE, pisiSkladbe(shrani))
        return shrani.any { it.id == s.id }
    }

    private const val NAJVEC_PRILJUBLJENIH = 300

    /**
     * Priljubljene v novem vrstnem redu [red] (isti id-ji, drugacen red - premik kanala, [PriljubljeniKanaliPravila.premakni]).
     * Vrne false, ce se je seznam medtem spremenil (drugi id-ji) - nic ne zapise.
     */
    @Synchronized
    fun urediPriljubljene(ctx: Context, red: List<String>): Boolean {
        val zdaj = beriSkladbe(beri(ctx, PRILJUBLJENE))
        val poId = zdaj.associateBy { it.id }
        if (red.toSet() != poId.keys) return false
        pisi(ctx, PRILJUBLJENE, pisiSkladbe(red.distinct().mapNotNull { poId[it] }))
        return true
    }

    fun seznami(ctx: Context): List<Seznam> {
        val a = try { JSONArray(beri(ctx, SEZNAMI)) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Seznam(it.optString("ime"), beriSkladbe(it.optJSONArray("skladbe")?.toString() ?: "[]"), it.optLong("cas"), it.optString("vir"))
        }.filter { it.ime.isNotBlank() && it.skladbe.isNotEmpty() }
    }

    private const val SEZNAMI_IZBRISANI = "seznami_izbrisani"

    /** Izbrisani seznami (ime -> cas izbrisa): da se seznam ne vrne z naprave, ki ga se ima ([SeznamiSink]). */
    fun izbrisaniSeznami(ctx: Context): Map<String, Long> = try {
        val o = JSONObject(ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).getString(SEZNAMI_IZBRISANI, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.optLong(it) }.filterValues { it > 0 }
    } catch (_: Exception) { emptyMap() }

    /** Zapomni izbris (kasnejsi cas zmaga); [cas] = 0 izbris pozabi (seznam je spet ustvarjen). Najvec 100 zapisov. */
    @Synchronized fun zapomniIzbrisSeznama(ctx: Context, ime: String, cas: Long) {
        val zdaj = izbrisaniSeznami(ctx).toMutableMap()
        if (cas <= 0) { if (zdaj.remove(ime) == null) return } else { if ((zdaj[ime] ?: 0L) >= cas) return; zdaj[ime] = cas }
        val o = JSONObject()
        zdaj.entries.sortedByDescending { it.value }.take(100).forEach { o.put(it.key, it.value) }
        ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit().putString(SEZNAMI_IZBRISANI, o.toString()).apply()
    }

    /** Cas krajevne spremembe seznama [ime] ([SeznamiPravila.novCas]: nikoli starejsi od prejsnjega stanja in izbrisa). */
    private fun novCasSeznama(ctx: Context, ime: String): Long = SeznamiPravila.novCas(System.currentTimeMillis(),
        seznami(ctx).firstOrNull { it.ime == ime }?.cas ?: 0L, izbrisaniSeznami(ctx)[ime] ?: 0L)

    /** Usklajevanje: seznam z druge naprave zapise, kot je (cas ostane njen); obstojeci ostane na svojem mestu. */
    @Synchronized fun nastaviSeznam(ctx: Context, sz: Seznam) {
        if (sz.ime.isBlank() || sz.skladbe.isEmpty()) return
        val vsi = seznami(ctx)
        pisiSeznami(ctx, if (vsi.any { it.ime == sz.ime }) vsi.map { if (it.ime == sz.ime) sz else it } else listOf(sz) + vsi)
        zapomniIzbrisSeznama(ctx, sz.ime, 0)
    }

    /** Shrani seznam (isto ime zamenja); vrne shranjeni seznam ali null, ce v njem ni nicesar shranljivega. */
    fun shraniSeznam(ctx: Context, ime: String, skladbe: List<Jamendo.Skladba>, vir: String = ""): Seznam? {
        val sz = Seznam(ime, skladbe.filter { zaSeznam(it) }.map { zaShranjevanje(it) }.distinctBy { it.id }.take(NAJVEC_V_SEZNAMU),
            novCasSeznama(ctx, ime), vir)
        if (sz.skladbe.isEmpty()) return null
        pisiSeznami(ctx, listOf(sz) + seznami(ctx).filterNot { it.ime == ime })
        zapomniIzbrisSeznama(ctx, ime, 0)
        return sz
    }

    const val NAJVEC_V_SEZNAMU = 400

    /** Doda skladbo na konec seznama (ustvari ga, ce ga se ni); vrne false, ce je ze na njem ali je ni mogoce shraniti. */
    fun dodajNaSeznam(ctx: Context, ime: String, s: Jamendo.Skladba): Boolean {
        if (!zaSeznam(s)) return false
        val vsi = seznami(ctx)
        val obstojeci = vsi.firstOrNull { it.ime == ime }
        if (obstojeci != null && obstojeci.skladbe.any { it.id == s.id }) return false
        val nov = Seznam(ime, ((obstojeci?.skladbe ?: emptyList()) + zaShranjevanje(s)).takeLast(NAJVEC_V_SEZNAMU),
            novCasSeznama(ctx, ime), obstojeci?.vir.orEmpty())
        // Obstojeci seznam ostane na svojem mestu, nov gre na zacetek.
        pisiSeznami(ctx, if (obstojeci != null) vsi.map { if (it.ime == ime) nov else it } else listOf(nov) + vsi)
        zapomniIzbrisSeznama(ctx, ime, 0)
        return true
    }

    /** Odstrani skladbo s seznama; prazen seznam izgine. */
    fun odstraniSSeznama(ctx: Context, ime: String, s: Jamendo.Skladba) {
        val zdaj = novCasSeznama(ctx, ime)
        val vsi = seznami(ctx).map { if (it.ime == ime) it.copy(skladbe = it.skladbe.filterNot { x -> x.id == s.id }, cas = zdaj) else it }
        if (vsi.any { it.ime == ime && it.skladbe.isEmpty() }) odstraniSeznam(ctx, ime) else pisiSeznami(ctx, vsi)
    }

    /**
     * Uvozena skladba je dobila posnetek ([UvozSeznama.najdi]): v seznamih in med priljubljenimi jo zamenjamo, da
     * naslednjic zacne takoj in ima svojo sliko.
     */
    @Synchronized fun zamenjaj(ctx: Context, zamenjave: Map<String, Jamendo.Skladba>) {
        if (zamenjave.isEmpty()) return
        val sz = seznami(ctx)
        // Najden posnetek je sprememba seznama: druge naprave ga dobijo z usklajevanjem in ne iscejo same.
        val zdaj = System.currentTimeMillis()
        if (sz.any { l -> l.skladbe.any { it.id in zamenjave } })
            pisiSeznami(ctx, sz.map { l ->
                if (l.skladbe.none { it.id in zamenjave }) l
                else l.copy(skladbe = l.skladbe.map { zamenjave[it.id] ?: it }.distinctBy { it.id }, cas = SeznamiPravila.novCas(zdaj, l.cas, 0L))
            })
        val p = priljubljene(ctx)
        if (p.any { it.id in zamenjave }) pisi(ctx, PRILJUBLJENE, pisiSkladbe(p.map { zamenjave[it.id] ?: it }.distinctBy { it.id }))
    }

    /** Izbrise seznam in si izbris zapomni ([cas]; privzeto zdaj), da ga usklajevanje ne vrne z druge naprave. */
    fun odstraniSeznam(ctx: Context, ime: String, cas: Long = novCasSeznama(ctx, ime)) {
        pisiSeznami(ctx, seznami(ctx).filterNot { it.ime == ime })
        zapomniIzbrisSeznama(ctx, ime, cas)
    }

    private fun pisiSeznami(ctx: Context, s: List<Seznam>) {
        val a = JSONArray()
        s.take(30).forEach { a.put(JSONObject().put("ime", it.ime).put("cas", it.cas).put("vir", it.vir).put("skladbe", JSONArray(pisiSkladbe(it.skladbe)))) }
        pisi(ctx, SEZNAMI, a.toString())
    }

    private fun beri(ctx: Context, kljuc: String) = ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).getString(kljuc, "[]") ?: "[]"
    private fun pisi(ctx: Context, kljuc: String, v: String) = ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit().putString(kljuc, v).apply()

    private fun pisiSkladbe(s: List<Jamendo.Skladba>): String {
        val a = JSONArray()
        s.forEach { a.put(JSONObject().put("id", it.id).put("naslov", it.naslov).put("izvajalec", it.izvajalec).put("slika", it.slika)
            .put("zvok", it.zvok).put("povezava", it.povezava).put("radio", it.radio).put("video", it.video).put("mime", it.mime)
            .put("streznik", it.streznik).put("kanal", it.kanal).put("year", it.year).put("language", it.language)
            // Naloga 140: epizoda serije - »Nadaljuj« jo nadaljuje (prej je odprl seznam sezon).
            .put("season", it.season).put("episode", it.episode).put("imdbId", it.imdbId)) }
        return a.toString()
    }

    private fun beriSkladbe(json: String): List<Jamendo.Skladba> {
        val a = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Jamendo.Skladba(it.optString("id"), it.optString("naslov"), it.optString("izvajalec"), it.optString("slika"), it.optString("zvok"),
                it.optString("povezava"), it.optBoolean("radio"), it.optBoolean("video"), it.optString("mime"), it.optString("streznik"),
                it.optString("kanal"), year = it.optInt("year"), language = it.optString("language"),
                season = it.optInt("season"), episode = it.optInt("episode"), imdbId = it.optString("imdbId"))
        }.filter { it.id.isNotBlank() && PeerTube.jeDovoljen(it) }
    }

    /** Nedavno predvajano (najnovejse prvo), samo na tej napravi. */
    fun nedavno(ctx: Context): List<Jamendo.Skladba> {
        Stremio.pripravi(ctx)
        // Vsebina zasebnih dodatkov ni v zgodovini (ZasebniDodatki) - tudi tista, zapisana pred tem pravilom.
        // Naslovov dodatka, ki ga uporabnik nima vec, ne kazemo (ne dajo se predvajati).
        val dodatki = vsi(ctx).filter { it.jeStremio }.map { it.naslov }
        // Kanala ali postaje, ki ta cas pri viru ne dela ([MrtviKanali]), ne ponujamo za nadaljevanje. Naloga 91c: tudi ne
        // skritega ali kanala z znanim nedelujocim tokom ([KanaliPravila.znanNedelujocTok]) - in ne njegove razlicice z istim imenom.
        val vnosi = beriSkladbe(beri(ctx, NEDAVNO)).distinctBy { it.id }.filterNot { Stremio.jeZasebna(it) || Stremio.brezDodatka(it, dodatki) }
        return osveziVgrajene(KanaliPravila.brezNedelujocihKanalov(vnosi,
            { it.id.startsWith("tv:") || Stremio.jeVZivo(it) || IptvSeznami.jeKanal(it) }, { it.naslov },
            { MrtviKanali.skrij(ctx, it) })
            .take(MAX_NEDAVNO))
    }

    fun odstraniNedavno(ctx: Context, s: Jamendo.Skladba) =
        pisi(ctx, NEDAVNO, pisiSkladbe(nedavno(ctx).filterNot { it.id == s.id }))

    fun pocistiNedavno(ctx: Context) = pisi(ctx, NEDAVNO, "[]")

    fun zapomniNedavno(ctx: Context, s: Jamendo.Skladba) {
        Stremio.pripravi(ctx)
        // Naloga 135: samodejni preizkus po gradnji ne gre med nazadnje predvajano.
        if (!shranljiva(s) || Stremio.jeZasebna(s) || PreizkusPravila.jePreizkus(s.id)) return
        val z = zaShranjevanje(s)
        pisi(ctx, NEDAVNO, pisiSkladbe((listOf(z) + nedavno(ctx).filterNot { it.id == z.id }).distinctBy { it.id }.take(MAX_NEDAVNO)))
    }

    /** Nedavna iskanja (najnovejse prvo), samo na tej napravi. */
    fun iskanja(ctx: Context): List<String> {
        val a = try { JSONArray(ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).getString(ISKANJA, "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }

    fun zapomniIskanje(ctx: Context, beseda: String) {
        val nova = (listOf(beseda) + iskanja(ctx).filterNot { it.equals(beseda, ignoreCase = true) }).take(8)
        ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit().putString(ISKANJA, JSONArray(nova).toString()).apply()
    }

    fun streznikiPeerTube(ctx: Context): List<String> =
        (vgrajeniPeerTube(ctx) + vsi(ctx).filter { it.jePeerTube }.map { it.naslov }).filterNot(PeerTube::jeBlokiran).distinct()

    /**
     * Vgrajeni strezniki PeerTube brez tistih, ki jih je uporabnik odstranil (Moji viri -> Izbrisi vir).
     * Lastnik, 1. 10. 2026: uporabnik vire doda in pozabi - tudi vgrajenih mu ni treba trpeti.
     */
    fun vgrajeniPeerTube(ctx: Context): List<String> {
        if (!VGRAJENI_SPLETNI_VIRI) return emptyList()
        val proc = odstranjeni(ctx)
        return PeerTube.VGRAJENI.filterNot { it in proc }
    }

    /** Vgrajeni streznik kot vir (za Moji viri: odstranitev z istim dialogom kot dodani viri). */
    fun vgrajenPeerTubeVir(streznik: String) = Vir(PEERTUBE, streznik, streznik)

    fun odstrani(ctx: Context, vir: Vir, cas: Long = 0L) {
        zapomniOdstranjen(ctx, vir.naslov)
        // Spletne strani so vir po domeni (ista stran pod drugim naslovom gre zraven); dodatki, tokovi in strezniki pa po
        // tocnem naslovu - sicer bi izbris enega dodatka odstranil vse dodatke z iste domene.
        shrani(ctx, rocni(ctx).filterNot { it.naslov == vir.naslov || (vir.jeSplet && it.jeSplet && istaSpletnaStran(it.naslov, vir.naslov)) }, cas)
        if (vir.jeSeznam) ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit().remove(kljucSeznama(vir.naslov)).apply()
        pripeti(ctx).let { p -> if (kljucPripetega(vir) in p) pisi(ctx, PRIPETI, JSONArray(p - kljucPripetega(vir)).toString()) }
        // Webtor rezerva: odstranjen dodatek s poverilnico - skrivnost izgine iz sifrirane shrambe (ce je ne rabi drug vir).
        SkrivnostiPravila.nadomestnaOsnova(vir.naslov)?.let { n ->
            if (rocni(ctx).none { SkrivnostiPravila.nadomestnaOsnova(it.naslov) == n }) SkrivniDodatki.pozabi(ctx, vir.naslov)
            // Izbris gre sam na naprave v ozjem krogu (grob s casom - UskladitevKljucev).
            UskladitevKljucev.sprozi(ctx)
        }
    }

    /**
     * Webtor rezerva: pravi naslovi dodatkov s poverilnico te naprave (za [Debrid.posljiKljuce] - samo ozji krog, sifrirano
     * od naprave do naprave). Nadomestki brez znane skrivnosti izpadejo.
     */
    fun skrivniDodatki(ctx: Context): List<String> {
        SkrivniDodatki.nalozi(ctx)
        return rocni(ctx).filter { it.jeStremio && SkrivnostiPravila.jeNadomestek(it.naslov) }
            .map { SkrivniDodatki.dejanski(it.naslov) }.filter { SkrivnostiPravila.veljavenSkrivniDodatek(it) }.distinct()
    }

    /** Usklajevanje: dodatek s poverilnico z odtisom [odtis] je bil izbrisan na drugi napravi v ozjem krogu. */
    fun odstraniSkrivniDodatek(ctx: Context, odtis: String) {
        val v = rocni(ctx).firstOrNull { it.jeStremio && SkrivnostiPravila.nadomestnaOsnova(it.naslov)?.endsWith(SkrivnostiPravila.OZNAKA + odtis) == true }
            ?: return
        odstrani(ctx, v)
    }

    private val zaklepPrehoda = Any()
    @Volatile private var preseljeno = false

    /** Nastavitve, ki se ne preseljujejo: sifrirane skrivnosti (vrednosti so sifrirane, ne vsebujejo naslova). */
    private val IZVZETE_NASTAVITVE = setOf("safeer_sporocila_skrivnosti", "safeer_skrivni_dodatki")

    /**
     * Od 26. 10. 2026 enako za vire z racunom IPTV ([IptvPravila.zamenjaveRacunov]; datoteke kanalov [IptvSeznami.preseli]).
     *
     * Webtor rezerva - prehod z razlicice pred 25. 10. 2026 (pregled S1/N2): dodatki s poverilnico so bili shranjeni s pravim
     * naslovom (in usklajeni kot navaden vir). Skrivnost gre v sifrirano shrambo, pravi del naslova se v VSEH datotekah
     * nastavitev aplikacije (kljuci in vrednosti: Moji viri, zgodovina, priljubljene, seznami, napredek, zasebni dodatki,
     * premori, kanali ...) zamenja z nadomestkom, predpomnilnik manifestov z njim se izbrise - dodatek in kartice ostanejo,
     * brez sledi izbrisa za druge naprave. Ce sifriranje ne uspe, vir ostane, kot je (ne izgubi se), a ne gre vec med naprave
     * ([uskladljiv]). Zastavica se postavi sele po koncanem delu (pod zaklepom); v ozadju se nato enkrat na proces pregledajo
     * se vse nastavitve za znane prave naslove (kar je morda zapisala nit, ki je brala pred prehodom) in pospravijo
     * zapuscene skrivnosti. Klice se iz [rocni] in pred branjem nastavitev v Stremio.pripravi.
     */
    fun preseliSkrivne(ctx: Context) {
        if (preseljeno) return
        val a = ctx.applicationContext
        synchronized(zaklepPrehoda) {
            if (preseljeno) return
            try { preseli(a) } catch (e: Exception) { android.util.Log.w("SafeerOsMedia", "skrivni dodatki, prehod: ${e.javaClass.simpleName}") }
            preseljeno = true
        }
        Thread({ try { pocisti(a) } catch (e: Exception) { android.util.Log.w("SafeerOsMedia", "skrivni dodatki, pospravljanje: ${e.javaClass.simpleName}") } },
            "safeer-skrivni-prehod").apply { isDaemon = true }.start()
    }

    private fun preseli(a: Context) {
        val p = a.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE)
        val viri = try { JSONArray(p.getString(KLJUC, "[]")) } catch (_: Exception) { return }
        val zamenjave = LinkedHashMap<String, String>()
        val drugi = ArrayList<IptvPravila.ZapisVira>()
        for (i in 0 until viri.length()) {
            val o = viri.optJSONObject(i) ?: continue
            if (o.optString("tip") != STREMIO) { drugi += IptvPravila.ZapisVira(o.optString("tip"), o.optString("ime"), o.optString("naslov")); continue }
            val n = o.optString("naslov")
            val predpona = SkrivnostiPravila.skrivnaPredponaDodatka(n) ?: continue
            if (SkrivniDodatki.zavaruj(a, n) == null) continue
            zamenjave[predpona] = SkrivnostiPravila.nadomestek(predpona) ?: continue
        }
        // Lastnik, 26. 10. 2026: viri z racunom IPTV so bili v nastavitvah s pravim naslovom (in z usklajevanjem virov pri vseh
        // napravah v Linku). Skrivnost gre v sifrirano shrambo, pravi naslov se v vseh nastavitvah zamenja z nadomestkom.
        val racuni = IptvPravila.zamenjaveRacunov(drugi) { SkrivniDodatki.zavarujCel(a, it) }
        // Grok 46, Ö3 + Grok 52, Ñ3: kaj je bilo tu ze pred prehodom (starejsa razlicica je to dala vsem napravam v Linku) - za
        // preklic na napravah zunaj ozjega kroga in za enkratno opozorilo lastniku. VSI viri z racunom, tudi nesifrirani (sifriranje
        // ni uspelo) in neznane oblike (brez hevristike jeVirSPoverilnico). Samo odtisi.
        val predOdtisi = IptvPravila.racuniPredPrehodom(drugi)
        if (predOdtisi.isNotEmpty()) {
            val pred = (p.getStringSet(VIRI_PRED_PREHODOM, null) ?: emptySet()) + predOdtisi
            p.edit().putStringSet(VIRI_PRED_PREHODOM, HashSet(pred)).commit()
            obvestiRacune()
        }
        // Grobovi izbrisanih virov z geslom v kljucu (sli so z usklajevanjem virov vsem napravam) izginejo.
        val izbrisani = beriCase(a, VIRI_IZBRISANI)
        val brez = IptvPravila.brezGrobovZRacunom(izbrisani)
        if (brez.size != izbrisani.size) {
            p.edit().putString(VIRI_IZBRISANI, JSONObject(brez as Map<*, *>).toString()).commit()
            android.util.Log.i("SafeerOsMedia", "viri z racunom: grobov s pravim naslovom odstranjenih ${izbrisani.size - brez.size}")
        }
        if (zamenjave.isEmpty() && racuni.isEmpty()) return
        // Datoteke kanalov in premor prenosa gredo pod nadomestek - brez novega prenosa (vsak prenos je prijava v racun).
        for ((s, n) in racuni) try { IptvSeznami.preseli(a, s, n) } catch (e: Exception) { android.util.Log.w("SafeerOsMedia", "viri z racunom, datoteke: ${e.javaClass.simpleName}") }
        // Moji viri: vir z racunom dobi nadomestek in varno ime (IptvPravila.preseliZapise); ostale nastavitve spodaj.
        if (racuni.isNotEmpty()) preseliMojeVire(a, viri, racuni)
        zamenjave.putAll(racuni)
        val n = zamenjajVNastavitvah(a, zamenjave)
        android.util.Log.i("SafeerOsMedia", "skrivni dodatki in viri z racunom: ${zamenjave.size} preseljenih na nadomestek " +
            "(viri z racunom ${racuni.size}, datotek nastavitev $n)")
    }

    /**
     * Prehod: Moji viri ([viri] = prebran zapis) - vir z racunom dobi nadomestek in varno ime ([IptvPravila.preseliZapise];
     * samodejno ime je bil lahko zadnji odsek poti = geslo). Dodatki ostanejo za [zamenjajVNastavitvah].
     */
    private fun preseliMojeVire(a: Context, viri: JSONArray, racuni: Map<String, String>) {
        var spremenjeno = false
        for (i in 0 until viri.length()) {
            val o = viri.optJSONObject(i) ?: continue
            val z = IptvPravila.ZapisVira(o.optString("tip"), o.optString("ime"), o.optString("naslov"))
            val nov = IptvPravila.preseliZapise(listOf(z), racuni).first()
            if (nov == z) continue
            o.put("ime", nov.ime).put("naslov", nov.naslov)
            spremenjeno = true
        }
        if (spremenjeno) a.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit().putString(KLJUC, viri.toString()).commit()
    }

    /** Vse datoteke nastavitev (razen [IZVZETE_NASTAVITVE]) in predpomnilnik dodatkov: pravi del -> nadomestek. */
    private fun zamenjajVNastavitvah(a: Context, zamenjave: Map<String, String>): Int {
        var spremenjenih = 0
        val imena = java.io.File(a.applicationInfo.dataDir, "shared_prefs").listFiles()
            ?.mapNotNull { f -> f.name.takeIf { it.endsWith(".xml") }?.removeSuffix(".xml") }.orEmpty()
        for (ime in imena) {
            if (ime in IZVZETE_NASTAVITVE) continue
            val sp = a.getSharedPreferences(ime, Context.MODE_PRIVATE)
            val staro = sp.all
            val novo = SkrivnostiPravila.preseliVnose(staro, zamenjave) ?: continue
            // Pregled U12: brez clear() - zapisemo samo spremenjene kljuce (zapis druge niti med branjem ostane).
            val e = sp.edit()
            for (k in staro.keys) if (k !in novo) e.remove(k)
            for ((k, v) in SkrivnostiPravila.spremenjeniVnosi(staro, novo)) when (v) {
                is String -> e.putString(k, v)
                is Long -> e.putLong(k, v)
                is Int -> e.putInt(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Float -> e.putFloat(k, v)
                is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
            }
            e.commit()
            spremenjenih++
        }
        // Predpomnjeni manifesti in odgovori dodatkov s pravim naslovom: izbrisemo jih (predpomnilnik se napolni znova).
        java.io.File(a.cacheDir, "stremio").listFiles()?.forEach { f ->
            try { if (f.isFile && f.length() < 16L * 1024 * 1024 && SkrivnostiPravila.vsebujeKatero(f.readText(), zamenjave.keys)) f.delete() } catch (_: Exception) { }
        }
        return spremenjenih
    }

    /** Enkrat na proces v ozadju: ostanki pravih naslovov v nastavitvah (N2) in zapuscene skrivnosti (S4). */
    private fun pocisti(a: Context) {
        synchronized(zaklepPrehoda) {
            val pravi = SkrivniDodatki.pravePredpone(a)
            if (pravi.isEmpty()) return
            val n = zamenjajVNastavitvah(a, pravi)
            if (n > 0) android.util.Log.i("SafeerOsMedia", "skrivni dodatki: pravi naslov odstranjen se iz $n datotek nastavitev")
            SkrivniDodatki.pocisti(a, rocni(a).mapNotNull { SkrivnostiPravila.nadomestnaOsnova(it.naslov) }.toSet())
        }
    }

    // ------------------------------------------------------------------ viri na plosci

    /**
     * Viri na plosci Safeer Media delujejo kot priljubljene aplikacije na domacem zaslonu: uporabnik
     * jih izbere sam (zadrzan OK v Mojih virih) in uredi njihov red. Kljuci: vgrajeni ("tv", "link",
     * "radio", "peertube") ali dodani ("u:<naslov>").
     */
    private const val PRIPETI = "pripeti_viri"
    /**
     * Dokler uporabnik ne izbere sam: ta naprava, naprave v Safeer Linku in Torrent. Radijske postaje (vgrajen vir
     * Radio Browser) do naloge 57 tudi.
     */
    private val PRIVZETO_PRIPETI = SeznamiPravila.privzetoPripeti(VGRAJENI_SPLETNI_VIRI)

    fun kljucPripetega(v: Vir) = "u:" + v.naslov

    fun pripeti(ctx: Context): List<String> {
        val s = ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).getString(PRIPETI, null) ?: return PRIVZETO_PRIPETI
        return try { JSONArray(s).let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } } catch (_: Exception) { PRIVZETO_PRIPETI }
    }

    /** Na plosco ali z nje; vrne, ali je vir zdaj na plosci. */
    fun preklopiPripet(ctx: Context, kljuc: String): Boolean {
        val zdaj = pripeti(ctx)
        val nova = if (kljuc in zdaj) zdaj - kljuc else zdaj + kljuc
        pisi(ctx, PRIPETI, JSONArray(nova).toString())
        return kljuc in nova
    }

    /** Premik za [zamik] mest (-1 levo, +1 desno); vrne true, ce se je red res spremenil. */
    fun premakniPripet(ctx: Context, kljuc: String, zamik: Int): Boolean {
        val s = pripeti(ctx).toMutableList()
        val i = s.indexOf(kljuc)
        val j = i + zamik
        if (i < 0 || j !in s.indices) return false
        s.add(j, s.removeAt(i))
        pisi(ctx, PRIPETI, JSONArray(s).toString())
        return true
    }

    /**
     * Preveri naslov in ga doda. Najprej PeerTube (vpisano ime streznika ali naslov strani), nato
     * vsebina na naslovu: zvok ali video, tok HLS (.m3u8), seznam .m3u/.pls (cel seznam - skladbe
     * najde tudi iskanje), RSS podkasta ali spletna stran. Vrne dodani vir ali null, ce na naslovu ni
     * nicesar, kar bi znali predvajati.
     */
    fun dodaj(ctx: Context, vnos: String, ime: String? = null): Vir? {
        // Naloga 69: radio-browser.info / tunein.com je imenik postaj, ne stran ali tok - brez preverjanja naslova (zahteve).
        // Naloga 89: lrclib.net je vir besedil pesmi, ne stran ali tok - brez preverjanja naslova (zahteve).
        if (BesedilaPravila.jeVirLrclib(vnos)) {
            rocni(ctx).firstOrNull { it.tip == BESEDILA }?.let { return it }
            val v = Vir(BESEDILA, ime?.takeIf { it.isNotBlank() } ?: ctx.getString(si.safeer.tv.R.string.os_besedila_vir), NASLOV_LRCLIB)
            shrani(ctx, rocni(ctx) + v)
            android.util.Log.i("SafeerOsMedia", "dodan vir: besedila pesmi (LRCLIB)")
            return v
        }
        OsPravila.imenikPostaj(vnos)?.let { kateri ->
            rocni(ctx).firstOrNull { it.tip == IMENIK && it.naslov == kateri }?.let { return it }
            val imeImenika = if (kateri == OsPravila.IMENIK_TUNEIN) "TuneIn" else "Radio Browser"
            val v = Vir(IMENIK, ime?.takeIf { it.isNotBlank() } ?: ctx.getString(si.safeer.tv.R.string.os_mediji_imenik_postaj, imeImenika), kateri)
            shrani(ctx, rocni(ctx) + v)
            android.util.Log.i("SafeerOsMedia", "dodan vir: imenik postaj $kateri")
            return v
        }
        val cisto = vnos.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        if (PeerTube.jeBlokiran(cisto.substringBefore('|').trim())) return null
        obstojeciVir(ctx, vnos)?.let { obstojeci ->
            // Grok 60, Ů2: uporabnik je na TEJ napravi sam vpisal vir z racunom, ki ga naprava ze ima (podedovan ali prejet) - to je dokaz.
            if (IptvPravila.virZRacunom(obstojeci.tip, obstojeci.naslov)) potrdiDodanTu(ctx, obstojeci.naslov)
            return obstojeci
        }
        prekliciOdstranjen(ctx, cisto)
        if (vnos.contains("{q}") || vnos.contains("{searchTerms}")) {
            val g = try { URL(vnos.substringBefore('|').trim()).host } catch (_: Exception) { return null }
            val v = Vir(API, ime?.takeIf { it.isNotBlank() } ?: g.removePrefix("www.").removePrefix("api."), vnos.trim())
            shrani(ctx, rocni(ctx).filterNot { it.naslov == v.naslov } + v)
            return v
        }
        if (!jePredvajljiv(cisto)) return null
        val gostitelj = try { URL(cisto).host } catch (_: Exception) { return null }
        val jePot = try { URL(cisto).path.trim('/').isNotEmpty() } catch (_: Exception) { false }
        val vir = PeerTube.imeStreznika(gostitelj)?.takeIf { !jePot || cisto.contains("/videos") || cisto.contains("/c/") || cisto.contains("/a/") }
            ?.let { Vir(PEERTUBE, it, gostitelj) }
            ?: razvrsti(ctx, cisto, gostitelj)?.let { v -> if (ime.isNullOrBlank()) v else v.copy(ime = ime) }?.let { zavarujNov(ctx, it) ?: return null }
            ?: return null
        val obstojeci = vsi(ctx).firstOrNull { v ->
            (v.jeSplet && vir.jeSplet && istaSpletnaStran(v.naslov, vir.naslov)) ||
                (!v.jeSplet && !vir.jeSplet && kanonicniNaslov(v.naslov) == kanonicniNaslov(vir.naslov))
        }
        if (obstojeci != null) return obstojeci
        shrani(ctx, rocni(ctx) + vir)
        // Vir z racunom gre sam na naprave v ozjem krogu (UskladitevKljucev) - nikoli z navadnim usklajevanjem virov.
        if (IptvPravila.virZRacunom(vir.tip, vir.naslov)) UskladitevKljucev.sprozi(ctx)
        return vir
    }

    /**
     * Lastnik, 26. 10. 2026: vir z racunom IPTV (Xtream, tok z geslom - [IptvPravila.skrivnostVira]) dobi nadomestek in varno
     * ime ze pred shranjevanjem: klicatelj (zaslon, pripenjanje, pregled) pravega naslova sploh ne dobi. Seznam, ki ga je
     * [razvrsti] prebral pod pravim naslovom, gre pod nadomestek. null = sifriranje ni uspelo (vira ne dodamo).
     */
    private fun zavarujNov(ctx: Context, v: Vir): Vir? {
        val s = IptvPravila.skrivnostVira(v.tip, v.naslov) ?: return v
        val z = IptvPravila.zaShrambo(IptvPravila.ZapisVira(v.tip, v.ime, v.naslov), false) { SkrivniDodatki.zavarujCel(ctx, it) }
            ?: return null.also { android.util.Log.w("SafeerOsMedia", "vira z racunom ni bilo mogoce zavarovati - ni dodan") }
        val p = ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE)
        p.getString(kljucSeznama(v.naslov), null)?.let { json ->
            p.edit().remove(kljucSeznama(v.naslov))
                .putString(kljucSeznama(z.naslov), SkrivnostiPravila.zamenjaj(json, mapOf(v.naslov to z.naslov, s to z.naslov))).apply()
        }
        return Vir(z.tip, z.ime, z.naslov)
    }

    /** Enota za vir: seznam in podkast se odpreta kot seznam (klik), ostalo predvaja predvajalnik ali brskalnik. */
    fun kotSkladba(v: Vir): Jamendo.Skladba = when {
        v.jePodkast -> Podkasti.oddaja(v.naslov, v.ime, v.naslov.removePrefix("https://").substringBefore('/'), "")
        // Vir z racunom: na kartici samo gostitelj (26. 10. 2026; prej cel naslov z geslom Xtream).
        else -> Jamendo.Skladba((if (v.jeSeznam) PREDPONA_SEZNAMA else "vir:") + v.naslov, v.ime,
            if (IptvPravila.virZRacunom(v.tip, v.naslov)) IptvPravila.zaPrikaz(v.naslov) else v.naslov.removePrefix("https://").removePrefix("http://"),
            "", v.naslov, v.naslov,
            radio = !v.tip.endsWith("video") && !v.jeSeznam, video = v.tip.endsWith("video"),
            mime = when { v.jeSplet -> STRAN; v.tip.contains("hls") -> MIME_HLS; else -> "" })
    }

    /** Oznaka enote, ki je spletna stran (odpre jo brskalnik, ne nas predvajalnik). */
    const val STRAN = "text/html"
    /** Tok HLS (.m3u8): predvajalnik ga predvaja z modulom Media3 HLS. */
    const val MIME_HLS = "application/x-mpegURL"
    /** Id enote, ki odpre dodani seznam .m3u/.pls. */
    const val PREDPONA_SEZNAMA = "vir-seznam:"

    // ------------------------------------------------------------------ dodani seznami .m3u/.pls

    private fun kljucSeznama(naslov: String) = "seznam:$naslov"

    /** Skladbe dodanega seznama, kot so bile ob zadnjem branju (brez omrezja - za iskanje). */
    fun skladbeSeznama(ctx: Context, naslov: String): List<Jamendo.Skladba> = beriSkladbe(beri(ctx, kljucSeznama(naslov)))

    /** Seznam prebere znova (ob odprtju); ce naslov ne odgovori, ostane zadnji znani. */
    fun osveziSeznam(ctx: Context, naslov: String): List<Jamendo.Skladba> {
        // Vir z racunom: v aplikaciji nadomestek, pravi naslov samo za zahtevo; neznana skrivnost (Keystore) = nobene zahteve.
        val pravi = SkrivniDodatki.dejanski(ctx, naslov)
        if (SkrivnostiPravila.jeNadomestek(pravi)) return skladbeSeznama(ctx, naslov)
        // Grok 52, Ñ2: seznam z racunom samo ob premoru I5 (zavrnjen racun: 10 min, 1 h, 6 h, 24 h) - klic je vedno uporabnikov klik
        // (prevzem z druge naprave seznama ne prebere vec); izid gre v isto shrambo napak kot prenos za mrezo TV v zivo.
        val racun = IptvPravila.virZRacunom(SEZNAM, naslov)
        if (racun && !IptvSeznami.smePrenesti(ctx, naslov)) return skladbeSeznama(ctx, naslov)
        val p = try { URL(pravi).openConnection() as HttpURLConnection } catch (_: Exception) { return skladbeSeznama(ctx, naslov) }
        p.connectTimeout = 8_000; p.readTimeout = 10_000
        p.setRequestProperty("User-Agent", "SafeerOS")
        var koda = 0
        val nove = try {
            koda = p.responseCode
            if (koda in 200..299) beriSeznam(p.inputStream.bufferedReader().use { it.readText().take(2_000_000) }, pravi, naslov) else emptyList()
        } catch (_: Exception) { emptyList() } finally { p.disconnect() }
        if (racun) IptvSeznami.poIzidu(ctx, naslov, koda, nove.size)
        if (nove.isNotEmpty()) pisi(ctx, kljucSeznama(naslov), pisiSkladbe(nove.take(1000)))
        return nove.ifEmpty { skladbeSeznama(ctx, naslov) }
    }

    /** Skladbe iz dodanih seznamov, ki ustrezajo iskanju (z virom, ki pove izvor). */
    fun iskanjeVSeznamih(ctx: Context, beseda: String): List<Pair<Vir, Jamendo.Skladba>> =
        vsi(ctx).filter { it.jeSeznam }.flatMap { v ->
            skladbeSeznama(ctx, v.naslov).filter { Relevantnost.ocena(beseda, it.naslov, it.izvajalec) >= Relevantnost.SPODNJA }.map { v to it }
        }

    /** Vnosi .m3u (#EXTINF: ime, nato naslov) ali .pls (FileN / TitleN); relativni naslovi glede na seznam. */
    fun beriSeznam(telo: String, osnova: String, seznam: String = osnova): List<Jamendo.Skladba> = vnosiSeznama(telo, osnova, seznam).map { it.second }

    /**
     * Naloga 68: kot [beriSeznam], z imenom vnosa, kot ga da seznam (#EXTINF / TitleN; "" = brez imena - naslov kartice
     * je takrat del naslova toka). Vnos brez koncnice in brez oznak IPTV je radijski tok ([OsPravila.jeRadijskiVnos]).
     * Relativni naslovi glede na [osnova] (pravi naslov); kartica dobi [seznam] (pri viru z racunom nadomestek).
     */
    private fun vnosiSeznama(telo: String, osnova: String, seznam: String = osnova): List<Pair<String, Jamendo.Skladba>> {
        fun polni(n: String) = try { URL(URL(osnova), n.trim()).toString() } catch (_: Exception) { "" }
        val pari = ArrayList<Triple<String, String, String>>()      // ime, naslov, vrstica #EXTINF
        if (telo.trimStart().startsWith("[playlist]", ignoreCase = true)) {
            val datoteke = HashMap<String, String>(); val imena = HashMap<String, String>()
            telo.lines().forEach { l ->
                Regex("""(?i)^File(\d+)=(.+)$""").find(l.trim())?.let { datoteke[it.groupValues[1]] = it.groupValues[2] }
                Regex("""(?i)^Title(\d+)=(.+)$""").find(l.trim())?.let { imena[it.groupValues[1]] = it.groupValues[2] }
            }
            datoteke.keys.sortedBy { it.toIntOrNull() ?: 0 }.forEach { k -> pari += Triple(imena[k].orEmpty().trim(), polni(datoteke[k]!!), "") }
        } else {
            var ime = ""
            var extinf = ""
            telo.lines().map { it.trim() }.forEach { l ->
                when {
                    // Ime za vejico ZUNAJ narekovajev (group-title="News,Sport", http-user-agent z vejico) - kot seznami IPTV.
                    l.startsWith("#EXTINF", ignoreCase = true) -> { ime = IptvPravila.imeIzExtinf(l); extinf = l }
                    l.isEmpty() || l.startsWith("#") -> {}
                    else -> { pari += Triple(ime, polni(l), extinf); ime = ""; extinf = "" }
                }
            }
        }
        return pari.filter { (it.second.startsWith("http://") || it.second.startsWith("https://")) && jePredvajljiv(it.second) }.map { (ime, url, extinf) ->
            val (naslov, izvajalec) = Relevantnost.razdeli(ime.ifBlank { url.substringAfterLast('/').substringBefore('?') }, "")
            ime to Jamendo.Skladba("seznam:$url", naslov, izvajalec, "", url, seznam,
                radio = OsPravila.jeRadijskiVnos(jeDatoteka(url), extinf),
                mime = if (url.substringBefore('?').lowercase().endsWith(".m3u8")) MIME_HLS else "")
        }.distinctBy { it.second.zvok }
    }

    private val KONCNICE = listOf(".mp3", ".ogg", ".oga", ".opus", ".m4a", ".aac", ".flac", ".wav", ".mp4", ".mkv", ".webm", ".m3u8")
    /** Torrentov, arhivov in namestitvenih paketov predvajalnik ne predvaja - takih vnosov ne pokazemo. */
    private val NEPREDVAJLJIVE = listOf(".torrent", ".nzb", ".iso", ".rar", ".zip", ".7z", ".exe", ".msi", ".apk", ".dmg", ".img", ".bin")
    fun jePredvajljiv(url: String) = !url.startsWith("magnet:", ignoreCase = true) &&
        url.substringBefore('?').substringBefore('#').lowercase().let { u -> NEPREDVAJLJIVE.none { u.endsWith(it) } }

    private fun jeDatoteka(url: String) = url.substringBefore('?').lowercase().let { u -> KONCNICE.any { u.endsWith(it) } }

    /** Kaj je na naslovu: zvok, video, HLS, seznam, podkast ali stran (eno branje). */
    private fun razvrsti(ctx: Context, naslov: String, gostitelj: String, globina: Int = 0): Vir? {
        if (globina > 1) return null
        val p = try { URL(naslov).openConnection() as HttpURLConnection } catch (_: Exception) { return null }
        p.connectTimeout = 8_000; p.readTimeout = 8_000
        p.setRequestProperty("User-Agent", "SafeerOS")
        p.setRequestProperty("Icy-MetaData", "0")
        return try {
            if (p.responseCode !in 200..299) return null
            val vrsta = (p.contentType ?: "").lowercase()
            val pot = naslov.lowercase().substringBefore('?')
            val seznam = vrsta.contains("mpegurl") || vrsta.contains("scpls") || pot.endsWith(".m3u") || pot.endsWith(".m3u8") || pot.endsWith(".pls")
            when {
                seznam -> {
                    val telo = p.inputStream.bufferedReader().use { it.readText().take(2_000_000) }
                    if (telo.contains("#EXT-X-")) return Vir(if (telo.contains("RESOLUTION=")) "$TOK-hls-video" else "$TOK-hls", gostitelj, naslov)
                    val zImeni = vnosiSeznama(telo, naslov)
                    val vnosi = zImeni.map { it.second }
                    // Radijski .pls ima pogosto vec zrcal istega toka (brez koncnice): to je ena postaja.
                    // Seznam je, kadar so vnosi vsaj v polovici datoteke (.mp3, .m3u8 kanali ...) ali imajo razlicna
                    // imena (naloga 68: seznam postaj Radio Browser, tokovi brez koncnice).
                    val datotek = vnosi.count { jeDatoteka(it.zvok) }
                    when {
                        OsPravila.jeSeznamPostaj(zImeni.map { it.first }, datotek) -> {
                            pisi(ctx, kljucSeznama(naslov), pisiSkladbe(vnosi.take(1000)))
                            val ime = pot.substringAfterLast('/').substringBeforeLast('.').ifBlank { gostitelj }
                            Vir(SEZNAM, "$ime (${vnosi.size})", naslov)
                        }
                        vnosi.isNotEmpty() -> razvrsti(ctx, vnosi[0].zvok, gostitelj, globina + 1)?.let { it.copy(ime = gostitelj) }
                        else -> null
                    }
                }
                vrsta.startsWith("audio/") || vrsta == "application/ogg" -> Vir(TOK, gostitelj, naslov)
                vrsta.startsWith("video/") -> Vir("$TOK-video", gostitelj, naslov)
                vrsta.contains("xml") || vrsta.contains("rss") -> Podkasti.imeOddaje(naslov)?.let { Vir(PODKAST, it, naslov) }
                vrsta.contains("text/html") -> Vir(SPLET, gostitelj.removePrefix("www."), naslov)
                else -> null
            }
        } catch (_: Exception) { null } finally { p.disconnect() }
    }

    /**
     * Shrani rocno dodane vire. Za usklajevanje med napravami v Linku ([SeznamiSink]) si ob tem zapomnimo, kdaj je bil
     * vir dodan in kdaj izbrisan: [cas] != 0 je cas z druge naprave (prevzem), sicer ura te naprave - a nikoli starejsa
     * od znanega izbrisa oziroma dodajanja (ure naprav niso enake).
     */
    @Synchronized private fun shrani(ctx: Context, viriVnosa: List<Vir>, cas: Long = 0L, brezSledi: Boolean = false, prejeto: Boolean = false) {
        // Webtor rezerva: dodatek s poverilnico nikoli kot pravi naslov v nastavitvah (varovalka za vse poti dodajanja).
        // Pregled U6: ce sifriranje ne uspe, novega pravega naslova ne zapisemo (prej je slo v nastavitve necifrirano);
        // ze shranjen (iz casa pred prehodom) ostane, kot je - ne izgubi se. U5: novega po http ne sprejmemo.
        val prejsnji = rocni(ctx).map { it.naslov }.toSet()
        // Grok 60, Ů2: naslovi (po zavarovanju) virov, ki jih uporabnik dodaja zdaj - po VHODNEM naslovu (zavarovanje ze shranjenega
        // vira pravega naslova ne naredi novega vira: prehod ne sme podedovanemu viru dati dokaza).
        val noviTu = HashSet<String>()
        val viri = viriVnosa.mapNotNull { v ->
            // Lastnik, 26. 10. 2026: vir z racunom IPTV nikoli s pravim naslovom v nastavitvah (varovalka za vse poti dodajanja,
            // tudi prevzem); ce sifriranje ne uspe, ze shranjen ostane (ne izgubi se), nov se ne shrani.
            val r: Vir? = if (IptvPravila.virZRacunom(v.tip, v.naslov) && !SkrivnostiPravila.jeNadomestek(v.naslov))
                IptvPravila.zaShrambo(IptvPravila.ZapisVira(v.tip, v.ime, v.naslov), v.naslov in prejsnji) { SkrivniDodatki.zavarujCel(ctx, it) }
                    ?.let { Vir(it.tip, it.ime, it.naslov) }
                    .also { if (it == null) android.util.Log.w("SafeerOsMedia", "vira z racunom ni bilo mogoce zavarovati - ni shranjen") }
            else if (!(v.jeStremio && SkrivnostiPravila.skrivnaPredponaDodatka(v.naslov) != null)) v
            else if (v.naslov in prejsnji) SkrivniDodatki.zavaruj(ctx, v.naslov)?.let { v.copy(naslov = it) } ?: v
            else if (!v.naslov.trim().startsWith("https://")) null.also { android.util.Log.w("SafeerOsMedia", "dodatek s poverilnico po http ni shranjen") }
            else SkrivniDodatki.zavaruj(ctx, v.naslov)?.let { v.copy(naslov = it) }
                .also { if (it == null) android.util.Log.w("SafeerOsMedia", "dodatka s poverilnico ni bilo mogoce zavarovati - ni shranjen") }
            r?.also { if (v.naslov !in prejsnji) noviTu += it.naslov }
        }
        val prej = rocni(ctx).map { kljucUskladitve(it) }.toSet()
        val zdaj = viri.map { kljucUskladitve(it) }.toSet()
        val a = JSONArray()
        viri.forEach { a.put(JSONObject().put("tip", it.tip).put("ime", it.ime).put("naslov", it.naslov)) }
        val casi = beriCase(ctx, VIRI_CASI); val izbrisani = beriCase(ctx, VIRI_IZBRISANI)
        val ura = System.currentTimeMillis()
        for (k in zdaj - prej) { casi[k] = if (cas != 0L) cas else SeznamiPravila.novCas(ura, 0L, izbrisani[k] ?: 0L); izbrisani.remove(k) }
        // [brezSledi]: vir izgine samo tukaj (ni izbris, ki bi se prenesel na druge naprave).
        for (k in prej - zdaj) { if (!brezSledi) izbrisani[k] = if (cas != 0L) cas else SeznamiPravila.novCas(ura, casi[k] ?: 0L, 0L); casi.remove(k) }
        casi.keys.retainAll(zdaj)
        while (izbrisani.size > 300) izbrisani.remove(izbrisani.minByOrNull { it.value }!!.key)
        // Lastnik, 26. 10. 2026: kateri viri so dodani na tej napravi - samo ta jih v ozadju preverja ([dodanTu]); prevzem z druge
        // naprave ([cas] != 0) ali [prejeto] ni dodajanje tu.
        val tukaj = IptvPravila.viriTukaj(prejsnji, viri.map { it.naslov }.toSet(),
            ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).getStringSet(VIRI_TUKAJ, null)?.toSet(),
            prejeto = prejeto || cas != 0L, jeTv = Sosed.jeTelevizor(ctx))
        // Grok 60, Ů2: dokaz, da je vir dodan na tej napravi - samo uporabnikovo dodajanje tu; prevzem, podedovan vir (zapisa ni) in
        // vir, ki ga je prejela druga naprava, ga nimajo, dokler uporabnik vira ne potrdi ([potrdiDodanTu]).
        val dokaz = IptvPravila.dokazDodanTu(ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).getStringSet(VIRI_DOKAZ, null)?.toSet(),
            noviTu, viri.map { it.naslov }.toSet(), prejeto = prejeto || cas != 0L)
        val urejanje = ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit().putString(KLJUC, a.toString())
            .putString(VIRI_CASI, JSONObject(casi as Map<*, *>).toString()).putString(VIRI_IZBRISANI, JSONObject(izbrisani as Map<*, *>).toString())
            .putStringSet(VIRI_TUKAJ, HashSet(tukaj)).putStringSet(VIRI_DOKAZ, HashSet(dokaz))
        // Naloga 62: vir dodan ali odstranjen - police in mreze z diska iz casa pred tem ne veljajo vec.
        if (prej != zdaj) urejanje.putLong(VIRI_SPREMENJENI, maxOf(ura, spremenjeni(ctx) + 1))
        urejanje.apply()
    }

    /**
     * Naloga 62: kdaj (System.currentTimeMillis te naprave) je bil vir nazadnje dodan ali odstranjen - tu ali z
     * usklajevanjem z druge naprave. Police in mreze na disku (MedijskiPredpomnilnik), shranjene pred tem, so iz starih
     * virov: Medijski center jih ne pokaze in ne dopolnjuje z njimi; nalaganje, ki se je zacelo prej, jih ne zapise.
     */
    fun spremenjeni(ctx: Context): Long = ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).getLong(VIRI_SPREMENJENI, 0L)

    private const val VIRI_TUKAJ = "viri_dodani_tu"

    /** Grok 60, Ů2: dokaz »dodan tu« (nov kljuc: zapis iz casa pred popravkom, kjer je TV vzela vire izpred pravila za svoje, ne velja). */
    private const val VIRI_DOKAZ = "viri_dodani_tu_dokaz"

    /**
     * Grok 60, Ů2 (fail-closed): ali je vir z racunom [naslov] dodan na tej napravi Z DOKAZOM (zapis ob dodajanju tu ali potrditev
     * uporabnika, [IptvPravila.dokazDodanTu]). Samo tak vir se na tej napravi samodejno prenasa ([IptvSeznami.pripravi]) in gre po
     * secrets.sync z vrednostjo v ozji krog te naprave ([UskladitevKljucev]); podedovan (zapisa ni, tudi na TV) ali prejet vir
     * ne. Brez @Synchronized: bere ga tudi zaslon (kartica vira); zapis je atomaren.
     */
    fun dodanTuZDokazom(ctx: Context, naslov: String): Boolean =
        ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).getStringSet(VIRI_DOKAZ, null)?.contains(naslov) == true

    /**
     * Grok 60, Ů2: uporabnik je na tej napravi izrecno potrdil (meni vira) ali sam znova vpisal vir z racunom, ki ga ta naprava ze
     * ima: od zdaj velja za dodanega tu. Pot za podedovane vire in vire, prejete od druge naprave. Vrne true, ce vir ima dokaz.
     */
    @Synchronized fun potrdiDodanTu(ctx: Context, naslov: String): Boolean {
        val p = ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE)
        val prej = p.getStringSet(VIRI_DOKAZ, null)?.toSet()
        val novo = IptvPravila.potrditevDodanTu(prej, rocni(ctx).map { it.naslov }.toSet(), naslov)
        if (novo != (prej ?: emptySet<String>())) {
            p.edit().putStringSet(VIRI_DOKAZ, HashSet(novo)).apply()
            // Vir z racunom gre odslej v ozji krog te naprave (kot ob dodajanju).
            if (naslov in novo) UskladitevKljucev.sprozi(ctx)
        }
        return naslov in novo
    }

    /**
     * Lastnik, 26. 10. 2026: ali je bil vir [naslov] dodan na tej napravi ([IptvPravila.viriTukaj]) - v krogu naprav ga v
     * ozadju preverja samo ta naprava; prejet z druge naprave se tu v ozadju ne preverja. SAMO za delitev dela pri pregledu
     * javnih seznamov in dodatkov brez poverilnic (TV vzame vire izpred pravila za svoje, ker tam ni racuna za zaklep); vir z
     * racunom odloca [dodanTuZDokazom] (Grok 60, Ů2).
     */
    @Synchronized fun dodanTu(ctx: Context, naslov: String): Boolean {
        val p = ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE)
        p.getStringSet(VIRI_TUKAJ, null)?.let { return naslov in it }
        // Prehod (zapisa se ni): viri iz casa pred pravilom - TV jih vzame za svoje, telefon in tablica ne.
        val zdaj = rocni(ctx).map { it.naslov }.toSet()
        val t = IptvPravila.viriTukaj(zdaj, zdaj, null, prejeto = false, jeTv = Sosed.jeTelevizor(ctx))
        p.edit().putStringSet(VIRI_TUKAJ, HashSet(t)).apply()
        return naslov in t
    }

    // ------------------------------------------------------------------ viri so enaki na vseh napravah v Linku

    private const val VIRI_CASI = "viri_casi"
    private const val VIRI_IZBRISANI = "viri_izbrisani"
    private const val VIRI_SPREMENJENI = "viri_spremenjeni"
    private val VRSTE_ZA_USKLADITEV = Regex("^(stremio|peertube|splet|api|seznam|podkast|tok(-hls)?(-video)?)$")

    fun kljucUskladitve(v: Vir) = v.tip + "|" + v.naslov

    private fun beriCase(ctx: Context, kljuc: String): MutableMap<String, Long> = try {
        val o = JSONObject(ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).getString(kljuc, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.optLong(it) }.toMutableMap()
    } catch (_: Exception) { mutableMapOf() }

    /**
     * Vir, ki ga smemo poslati drugi napravi ali prevzeti z nje: znana vrsta, javen naslov, ni blokiran. [odOzjegaKroga]: prevzem
     * od naprave v ozjem krogu te naprave - sme tudi vir, ki ni pozitivno znan javen ([IptvPravila.prevzemZNavadnoUskladitvijo]).
     */
    private fun uskladljiv(v: Vir, odOzjegaKroga: Boolean = false): Boolean =
        VRSTE_ZA_USKLADITEV.matches(v.tip) && v.naslov.length in 4..2048 && v.ime.length <= 200 &&
            v.naslov.none { it < ' ' } && v.ime.none { it < ' ' } && !PeerTube.jeBlokiran(v.naslov) &&
            (v.jePeerTube || v.naslov.startsWith("http://") || v.naslov.startsWith("https://")) &&
            !(v.jeSplet && jeImenikVirov(v.naslov)) &&
            // Webtor rezerva: dodatek s poverilnico (ali njen nadomestek) gre samo v ozji krog - z debrid kljuci
            // (Debrid.posljiKljuce, potrditev na prejemni napravi), nikoli z navadnim usklajevanjem virov.
            !(v.jeStremio && !SkrivnostiPravila.smeMedVseNaprave(v.naslov)) &&
            // Lastnik, 26. 10. 2026: vir z racunom IPTV (pravi naslov ali nadomestek) enako - samo ozji krog (UskladitevKljucev,
            // kljuc »iptv:<odtis>«); prej je sel vsem napravam v Linku. Grok 46, Ö1: fail-closed - z navadnim usklajevanjem samo
            // pozitivno znan javni seznam; prevzem drugega samo od naprave v ozjem krogu.
            IptvPravila.prevzemZNavadnoUskladitvijo(v.tip, v.naslov, odOzjegaKroga)

    /** Ali ta vir potuje med napravami v Linku (za besedilo ob brisanju): zaseben ali se neznan dodatek ne. */
    fun greMedNaprave(v: Vir): Boolean =
        uskladljiv(v) && ZasebniDodatki.smeMedNaprave(v.jeStremio, if (v.jeStremio) Stremio.zasebenZnano(v.naslov) else null)

    /** Rocno dodani viri te naprave s casom dodajanja (0 = dodan pred usklajevanjem) - za druge naprave v Linku. */
    fun zaUskladitev(ctx: Context): List<Pair<Vir, Long>> {
        Stremio.pripravi(ctx)
        val casi = beriCase(ctx, VIRI_CASI)
        val moji = rocni(ctx).filter { uskladljiv(it) }
        // Dodatek gre drugim napravam sele, ko vemo, da ni zaseben (ZasebniDodatki); neznane spoznamo v ozadju.
        Stremio.spoznaj(moji.filter { it.jeStremio }.map { it.naslov })
        return moji.filter { ZasebniDodatki.smeMedNaprave(it.jeStremio, if (it.jeStremio) Stremio.zasebenZnano(it.naslov) else null) }
            .take(200).map { it to (casi[kljucUskladitve(it)] ?: 0L) }
    }

    /** Ali vir s tem naslovom tu ze imamo, ne glede na vrsto (hitro, brez omrezja). */
    fun imamNaslov(ctx: Context, naslov: String): Boolean = rocni(ctx).any { it.naslov == naslov }

    /** Ali vir s tem kljucem tu ze imamo (hitro, brez omrezja). */
    fun imamVir(ctx: Context, v: Vir): Boolean = kljucUskladitve(v).let { k -> rocni(ctx).any { kljucUskladitve(it) == k } }

    /**
     * Zasebni dodatki, ki so sem prisli z usklajevanjem, preden je veljalo pravilo [ZasebniDodatki]: tu izginejo
     * brez sledi izbrisa (na napravi, kjer jih je uporabnik dodal, ostanejo). Vrne true, ce je kaj odstranil.
     */
    @Synchronized fun odstraniPrevzeteZasebne(ctx: Context): Boolean {
        Stremio.pripravi(ctx)
        val casi = beriCase(ctx, VIRI_CASI)
        val moji = rocni(ctx)
        val stran = moji.filter { it.jeStremio && ZasebniDodatki.prevzetOdstranimo(Stremio.zasebenZnano(it.naslov), casi[kljucUskladitve(it)] ?: 0L) }
            .map { kljucUskladitve(it) }.toSet()
        if (stran.isEmpty()) return false
        shrani(ctx, moji.filterNot { kljucUskladitve(it) in stran }, brezSledi = true)
        pripeti(ctx).let { p -> val brez = p.filterNot { k -> moji.any { kljucUskladitve(it) in stran && kljucPripetega(it) == k } }
            if (brez.size != p.size) pisi(ctx, PRIPETI, JSONArray(brez).toString()) }
        return true
    }

    /** Viri, izbrisani na tej napravi (kljuc -> cas izbrisa): druge naprave jih izbrisejo tudi pri sebi. */
    fun izbrisaniViri(ctx: Context): Map<String, Long> = beriCase(ctx, VIRI_IZBRISANI)

    /**
     * Grobovi za navadno usklajevanje (lists.get »sources_deleted«): brez virov z racunom in dodatkov s poverilnico - njihov
     * izbris gre samo v ozji krog (UskladitevKljucev), kljuc z nadomestkom pa ne vsem napravam v Linku (26. 10. 2026).
     */
    fun izbrisaniViriZaUskladitev(ctx: Context): Map<String, Long> =
        izbrisaniViri(ctx).filterKeys { IptvPravila.smeZNavadnoUskladitvijo(it.substringBefore('|'), it.substringAfter('|', "")) }

    /**
     * Vir z racunom z naprave v ozjem krogu (UskladitevKljucev, kljuc »iptv:<odtis>«, preverjen z [IptvPravila.prejetRacun]):
     * shrani se kot nadomestek s sifrirano skrivnostjo, BREZ prenosa seznama (prenese se, ko ga uporabnik odpre - vsak prenos
     * je prijava v racun). [prejeto]: ni dodan tu - ta naprava ga v ozadju nikoli ne preverja. Obstojeci vir: skrivnost se
     * obnovi (pregled S5: neberljiva v Keystore). null = ni uspelo.
     */
    fun dodajRacun(ctx: Context, tip: String, naslov: String, ime: String, prejeto: Boolean): Vir? {
        val s = IptvPravila.skrivnostVira(tip, naslov) ?: return null
        val n = SkrivniDodatki.zavarujCel(ctx, s) ?: return null
        rocni(ctx).firstOrNull { it.tip == tip && it.naslov == n }?.let { return it }
        val v = Vir(tip, IptvPravila.varnoIme(ime, s), n)
        shrani(ctx, rocni(ctx) + v, prejeto = prejeto)
        return v
    }

    // ------------------------------------------------------------------ Grok 46, Ö3: ze razposlane kopije

    private const val VIRI_PRED_PREHODOM = "viri_racun_pred_prehodom"
    private const val RACUNI_OPOZORJENI = "racuni_geslo_opozorjeni"
    private const val PREKLIC_OBDRZANI = "racuni_preklic_obdrzani"

    /** Zaslon, ki pokaze opozorilo o deljenem geslu ali vprasanje o preklicanem viru (GlasbaActivity); klic na glavni niti. */
    val poslusalciRacunov = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private fun obvestiRacune() = android.os.Handler(android.os.Looper.getMainLooper()).post {
        for (p in poslusalciRacunov) try { p() } catch (_: Throwable) { }
    }

    private fun nastavitve(ctx: Context) = ctx.applicationContext.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE)
    private fun mnozica(ctx: Context, k: String): Set<String> = nastavitve(ctx).getStringSet(k, null)?.toSet() ?: emptySet()

    /** Viri z racunom te naprave: odtis nadomestka, pravi naslov (ce je skrivnost znana), cas dodajanja, naslov v aplikaciji. */
    private class Racun(val odtis: String, val pravi: String, val cas: Long, val naslov: String)
    private fun racuni(ctx: Context): List<Racun> {
        SkrivniDodatki.nalozi(ctx)
        val casi = beriCase(ctx, VIRI_CASI)
        return rocni(ctx).filter { IptvPravila.jeVrstaRacuna(it.tip) }.mapNotNull { v ->
            val pravi = SkrivniDodatki.dejanski(v.naslov)
            if (SkrivnostiPravila.jeNadomestek(pravi)) return@mapNotNull null
            val odtis = SkrivnostiPravila.nadomestnaOsnova(v.naslov)?.substringAfterLast(SkrivnostiPravila.OZNAKA) ?: IptvPravila.odtisRacuna(pravi)
            Racun(odtis, pravi, casi[kljucUskladitve(v)] ?: 0L, v.naslov)
        }
    }

    /**
     * Grok 46, Ö3 - lastnik: za vire je vprasala naprava ZUNAJ ozjega kroga (SeznamiSink). Vrne preklic za tiste vire z racunom,
     * ki jih je starejsa razlicica dala vsem napravam (null = ni kaj preklicati), tudi za nesifrirane ob prehodu (Ñ3). Opozorilo
     * lastniku ne caka na to vprasanje ([opozoriloDeljenja]).
     */
    fun preklicZunajKroga(ctx: Context): JSONObject? {
        val pred = mnozica(ctx, VIRI_PRED_PREHODOM)
        if (pred.isEmpty()) return null
        val r = racuni(ctx)
        val b = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val sol = b.joinToString("") { "%02x".format(it) }
        val vnosi = IptvPravila.preklicZa(r.map { Triple(it.odtis, it.pravi, it.cas) }, pred, sol)
        if (vnosi.isEmpty()) return null
        val a = JSONArray()
        vnosi.forEach { (h, c) -> a.put(JSONObject().put("h", h).put("c", c)) }
        return JSONObject().put("s", sol).put("o", a)
    }

    /** Odtis vira z racunom v Mojih virih: nadomestek (sifriran) ali pravi naslov (sifriranje ob prehodu ni uspelo); brez Keystore. */
    private fun odtisVira(v: Vir): String? = SkrivnostiPravila.nadomestnaOsnova(v.naslov)?.substringAfterLast(SkrivnostiPravila.OZNAKA)
        ?: IptvPravila.skrivnostVira(v.tip, v.naslov)?.let { IptvPravila.odtisRacuna(it) }

    private fun zaOpozorilo(ctx: Context): Pair<Set<String>, List<Vir>> {
        val pred = mnozica(ctx, VIRI_PRED_PREHODOM)
        if (pred.isEmpty()) return emptySet<String>() to emptyList()
        val viri = rocni(ctx).filter { IptvPravila.jeVrstaRacuna(it.tip) }
        return IptvPravila.zaOpozorilo(pred, viri.mapNotNull { odtisVira(it) }.toSet(), mnozica(ctx, RACUNI_OPOZORJENI)) to viri
    }

    /**
     * Gostitelji virov, za katere lastnik se ni videl opozorila »geslo je bilo deljeno« (prazno = ni opozorila). Grok 52, Ñ3: vsak
     * vir z racunom izpred prehoda, enkrat po posodobitvi (prej samo za vire hevristicne oblike in sele po vprasanju naprave
     * zunaj kroga).
     */
    fun opozoriloDeljenja(ctx: Context): List<String> {
        val (za, viri) = zaOpozorilo(ctx)
        if (za.isEmpty()) return emptyList()
        return viri.filter { odtisVira(it) in za }.map { IptvPravila.zaPrikaz(it.naslov) }.distinct()
    }

    /** Lastnik je opozorilo videl: ne pokaze se vec (za vir, ki je nastal po prehodu, se ne pokaze nikoli). */
    fun opozoriloVideno(ctx: Context) {
        val (za, _) = zaOpozorilo(ctx)
        if (za.isNotEmpty()) nastavitve(ctx).edit().putStringSet(RACUNI_OPOZORJENI, HashSet(mnozica(ctx, RACUNI_OPOZORJENI) + za)).apply()
    }

    /** Preklic z naprave [naprava] (ime [ime]): viri te naprave (naslovi v aplikaciji), o katerih odloci uporabnik. */
    class Preklic(val naprava: String, val ime: String, val naslovi: List<String>)
    private val preklici = java.util.concurrent.ConcurrentHashMap<String, Preklic>()

    /** Grok 46, Ö3 - prejemna naprava: preklic iz kazala druge naprave (SeznamiSink). Klic iz ozadja. */
    fun preklicPrejet(ctx: Context, naprava: String, ime: String, o: JSONObject) {
        val a = o.optJSONArray("o") ?: return
        val prejeti = (0 until minOf(a.length(), IptvPravila.NAJVEC_PREKLICA)).mapNotNull { a.optJSONObject(it) }
            .map { it.optString("h") to it.optLong("c") }
        val zaVprasati = IptvPravila.preklicani(racuni(ctx).map { Triple(it.naslov, it.pravi, it.cas) }, o.optString("s"), prejeti,
            mnozica(ctx, PREKLIC_OBDRZANI))
        if (zaVprasati.isEmpty() || preklici[naprava]?.naslovi == zaVprasati) return
        preklici[naprava] = Preklic(naprava, ime, zaVprasati)
        android.util.Log.i("SafeerOsMedia", "viri z racunom: preklic z druge naprave za ${zaVprasati.size} virov - odloci uporabnik")
        obvestiRacune()
    }

    /** Najstarejsi preklic, o katerem uporabnik se ni odlocil, ali null. */
    fun preklic(): Preklic? = preklici.values.firstOrNull()

    /** Gostitelji virov preklica (za vprasanje - brez uporabnika in gesla). */
    fun opisPreklica(p: Preklic): String = p.naslovi.map { IptvPravila.zaPrikaz(it) }.distinct().joinToString(", ")

    /** Uporabnik je odlocil: [odstrani] = viri izginejo s te naprave; sicer ostanejo in vprasanja zanje ni vec. */
    fun odlociPreklic(ctx: Context, p: Preklic, odstrani: Boolean) {
        preklici.remove(p.naprava)
        if (!odstrani) { nastavitve(ctx).edit().putStringSet(PREKLIC_OBDRZANI, HashSet(mnozica(ctx, PREKLIC_OBDRZANI) + p.naslovi)).apply(); return }
        val a = ctx.applicationContext
        for (n in p.naslovi) {
            val v = rocni(a).firstOrNull { it.naslov == n } ?: continue
            odstrani(a, v)
            if (v.jeSeznam) Thread({ try { IptvSeznami.pozabi(a, n) } catch (_: Exception) { } }, "safeer-iptv-preklic").apply { isDaemon = true }.start()
        }
    }

    /** Usklajevanje: vir z racunom z odtisom [odtis] je bil izbrisan na drugi napravi v ozjem krogu. Klic iz ozadja. */
    fun odstraniRacun(ctx: Context, odtis: String) {
        val v = rocni(ctx).firstOrNull { IptvPravila.jeVrstaRacuna(it.tip) &&
            SkrivnostiPravila.nadomestnaOsnova(it.naslov)?.endsWith(SkrivnostiPravila.OZNAKA + odtis) == true } ?: return
        odstrani(ctx, v)
        if (v.jeSeznam) try { IptvSeznami.pozabi(ctx, v.naslov) } catch (_: Exception) { }
    }

    /** Vir z druge naprave: dodamo ga, ce ga tu se ni in ga tu nismo izbrisali pozneje, kot je bil tam dodan. */
    @Synchronized fun prevzemiVir(ctx: Context, v: Vir, cas: Long, odOzjegaKroga: Boolean = false): Boolean {
        if (!uskladljiv(v, odOzjegaKroga)) return false
        val k = kljucUskladitve(v)
        val moji = rocni(ctx)
        if (moji.any { kljucUskladitve(it) == k } || moji.size >= 400) return false
        // Ista spletna stran pod drugim naslovom je isti vir.
        if (v.jeSplet && moji.any { it.jeSplet && istaSpletnaStran(it.naslov, v.naslov) }) return false
        if (!SeznamiPravila.virPrevzamemo(false, beriCase(ctx, VIRI_IZBRISANI)[k], cas)) return false
        prekliciOdstranjen(ctx, v.naslov)
        shrani(ctx, moji + v, if (cas > 0L) cas else 1L)
        return true
    }

    /**
     * Naslov, ki ga je uporabnik dodal na racunalniku (vrsta [VRSTA_URL]): racunalnik vrste ne pozna, doloci jo ta
     * naprava sama (prebere naslov - klic iz ozadja). Vrne true, ce je vir dodan.
     */
    fun prevzemiNaslov(ctx: Context, naslov: String, ime: String, cas: Long, odOzjegaKroga: Boolean = false): Boolean {
        if (naslov.length !in 4..2048 || naslov.any { it < ' ' } || !(naslov.startsWith("http://") || naslov.startsWith("https://"))) return false
        // Lastnik, 26. 10. 2026: naslov, ki ni znan javni seznam, samo od naprave v ozjem krogu - drugace ga niti ne razvrscamo
        // (zahteva = prijava v racun, Grok 46 Ö1).
        if (!IptvPravila.prevzemZNavadnoUskladitvijo(VRSTA_URL, naslov, odOzjegaKroga)) return false
        synchronized(this) {
            val moji = rocni(ctx)
            if (moji.size >= 400 || moji.any { it.naslov == naslov || (!it.jePeerTube && kanonicniNaslov(it.naslov) == kanonicniNaslov(naslov)) }) return false
            // Tu izbrisan pozneje, kot je bil na racunalniku dodan: se ne vrne (ne glede na vrsto, ki jo je imel tukaj).
            val izbris = beriCase(ctx, VIRI_IZBRISANI).filterKeys { it.substringAfter('|') == naslov }.values.maxOrNull()
            if (!SeznamiPravila.virPrevzamemo(false, izbris, cas)) return false
        }
        if (PeerTube.jeBlokiran(naslov) || !jePredvajljiv(naslov)) return false
        val gostitelj = try { URL(naslov).host } catch (_: Exception) { return false }
        // Grok 52, Ñ2: vir z racunom se ne bere (razvrstitev = prijava v racun na vsaki napravi kroga hkrati): vrsta po obliki naslova.
        val v = IptvPravila.prejetNaslov(naslov) { razvrsti(ctx, naslov, gostitelj)?.let { IptvPravila.ZapisVira(it.tip, it.ime, it.naslov) } }
            ?.let { Vir(it.tip, it.ime, it.naslov) } ?: return false
        return prevzemiVir(ctx, if (ime.isBlank() || ime.length > 200 || ime.any { it < ' ' }) v else v.copy(ime = ime), cas, odOzjegaKroga)
    }

    /** Vrsta vira v usklajevanju, ki jo poslje racunalnik za naslov, dodan tam (core/viri_sink.py). */
    const val VRSTA_URL = "url"

    /** Vir je bil izbrisan na drugi napravi ob [cas]: izgine tudi tu, ce ga tu nismo dodali pozneje. Vrne true ob izbrisu. */
    @Synchronized fun prevzemiIzbrisVira(ctx: Context, kljucPrejet: String, cas: Long, odOzjegaKroga: Boolean = false): Boolean {
        if (cas <= 0L || kljucPrejet.length > 2100) return false
        // Lastnik, 26. 10. 2026: izbris vira z racunom (ali dodatka s poverilnico) pride samo iz ozjega kroga (UskladitevKljucev ali
        // lists.get naprave v ozjem krogu - Linux); vir z racunom je tu nadomestek, zato izbris s pravim naslovom velja zanj.
        val tipK = kljucPrejet.substringBefore('|')
        val naslovK = kljucPrejet.substringAfter('|', "")
        if (!IptvPravila.prevzemZNavadnoUskladitvijo(tipK, naslovK, odOzjegaKroga)) return false
        val kljuc = IptvPravila.skrivnostVira(if (tipK == VRSTA_URL) SEZNAM else tipK, naslovK)
            ?.let { SkrivnostiPravila.nadomestek(it) }?.let { "$tipK|$it" } ?: kljucPrejet
        val odRacunalnika = kljuc.startsWith("$VRSTA_URL|")
        // Racunalnik ne pozna vrste, ki jo je naslovu dala ta naprava: izbris "url|<naslov>" velja za vir s tem naslovom.
        val moj = rocni(ctx).firstOrNull { kljucUskladitve(it) == kljuc }
            ?: if (odRacunalnika) rocni(ctx).firstOrNull { !it.jeStremio && !it.jePeerTube && it.naslov == kljuc.substringAfter('|') } else null
        // Pregled 70: imenik postaj med napravami ne potuje (dodajanje ne) - tudi izbris na drugi napravi ga ne vzame.
        if (moj?.jeImenik == true || moj?.jeBesedila == true) return false   // naloga 89: tudi vir besedil je samo na tej napravi
        if (moj == null) {
            val izbrisani = beriCase(ctx, VIRI_IZBRISANI)
            if ((izbrisani[kljuc] ?: 0L) < cas && (odRacunalnika || VRSTE_ZA_USKLADITEV.matches(kljuc.substringBefore('|')))) {
                izbrisani[kljuc] = cas
                while (izbrisani.size > 300) izbrisani.remove(izbrisani.minByOrNull { it.value }!!.key)
                ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit().putString(VIRI_IZBRISANI, JSONObject(izbrisani as Map<*, *>).toString()).apply()
            }
            return false
        }
        if (!SeznamiPravila.izbrisViraVelja(beriCase(ctx, VIRI_CASI)[kljucUskladitve(moj)] ?: 0L, cas)) return false
        odstrani(ctx, moj, cas)
        return true
    }
}
