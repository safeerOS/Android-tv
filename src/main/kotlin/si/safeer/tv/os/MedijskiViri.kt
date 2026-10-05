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

    /** Shrani dodatek (podvojeni naslov le vrne obstojecega). */
    fun dodajDodatek(ctx: Context, tip: String, naslov: String, ime: String): Vir {
        val obstojeci = vsi(ctx).firstOrNull { it.tip == tip && it.naslov == naslov }
        if (obstojeci != null) return obstojeci
        val v = Vir(tip, ime.ifBlank { android.net.Uri.parse(naslov).host ?: naslov }, naslov)
        // Samo rocno dodani: viri iz spletnih aplikacij te naprave nastanejo sproti (vsi) in se ne shranjujejo.
        shrani(ctx, rocni(ctx) + v)
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

    private const val ODSTRANJENI_VIRI = "odstranjeni_viri"

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
        val a = try { JSONArray(ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).getString(KLJUC, "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Vir(it.optString("tip"), it.optString("ime"), it.optString("naslov")) }
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
        s.video && s.streznik.isNotBlank() -> s.copy(zvok = "", mime = "")
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

    /** Doda ali odstrani; vrne, ali je skladba zdaj med priljubljenimi. */
    fun preklopiPriljubljeno(ctx: Context, s: Jamendo.Skladba): Boolean {
        val zdaj = priljubljene(ctx)
        val nova = if (zdaj.any { it.id == s.id }) zdaj.filterNot { it.id == s.id } else listOf(zaShranjevanje(s)) + zdaj
        pisi(ctx, PRILJUBLJENE, pisiSkladbe(nova.take(300)))
        return nova.any { it.id == s.id }
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
            .put("streznik", it.streznik).put("kanal", it.kanal).put("year", it.year).put("language", it.language)) }
        return a.toString()
    }

    private fun beriSkladbe(json: String): List<Jamendo.Skladba> {
        val a = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Jamendo.Skladba(it.optString("id"), it.optString("naslov"), it.optString("izvajalec"), it.optString("slika"), it.optString("zvok"),
                it.optString("povezava"), it.optBoolean("radio"), it.optBoolean("video"), it.optString("mime"), it.optString("streznik"),
                it.optString("kanal"), year = it.optInt("year"), language = it.optString("language"))
        }.filter { it.id.isNotBlank() && PeerTube.jeDovoljen(it) }
    }

    /** Nedavno predvajano (najnovejse prvo), samo na tej napravi. */
    fun nedavno(ctx: Context): List<Jamendo.Skladba> {
        Stremio.pripravi(ctx)
        // Vsebina zasebnih dodatkov ni v zgodovini (ZasebniDodatki) - tudi tista, zapisana pred tem pravilom.
        // Naslovov dodatka, ki ga uporabnik nima vec, ne kazemo (ne dajo se predvajati).
        val dodatki = vsi(ctx).filter { it.jeStremio }.map { it.naslov }
        // Kanala ali postaje, ki ta cas pri viru ne dela ([MrtviKanali]), ne ponujamo za nadaljevanje.
        return osveziVgrajene(beriSkladbe(beri(ctx, NEDAVNO)).distinctBy { it.id }
            .filterNot { Stremio.jeZasebna(it) || Stremio.brezDodatka(it, dodatki) || MrtviKanali.jeMrtev(ctx, it.id) }.take(MAX_NEDAVNO))
    }

    fun odstraniNedavno(ctx: Context, s: Jamendo.Skladba) =
        pisi(ctx, NEDAVNO, pisiSkladbe(nedavno(ctx).filterNot { it.id == s.id }))

    fun pocistiNedavno(ctx: Context) = pisi(ctx, NEDAVNO, "[]")

    fun zapomniNedavno(ctx: Context, s: Jamendo.Skladba) {
        Stremio.pripravi(ctx)
        if (!shranljiva(s) || Stremio.jeZasebna(s)) return
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
    }

    // ------------------------------------------------------------------ viri na plosci

    /**
     * Viri na plosci Safeer Media delujejo kot priljubljene aplikacije na domacem zaslonu: uporabnik
     * jih izbere sam (zadrzan OK v Mojih virih) in uredi njihov red. Kljuci: vgrajeni ("tv", "link",
     * "radio", "peertube") ali dodani ("u:<naslov>").
     */
    private const val PRIPETI = "pripeti_viri"
    /** Dokler uporabnik ne izbere sam: ta naprava, naprave v Safeer Linku in radijske postaje. */
    private val PRIVZETO_PRIPETI = listOf("tv", "link", "radio", "magnet")

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
        val cisto = vnos.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        if (PeerTube.jeBlokiran(cisto.substringBefore('|').trim())) return null
        obstojeciVir(ctx, vnos)?.let { return it }
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
            ?: razvrsti(ctx, cisto, gostitelj)?.let { v -> if (ime.isNullOrBlank()) v else v.copy(ime = ime) }
            ?: return null
        val obstojeci = vsi(ctx).firstOrNull { v ->
            (v.jeSplet && vir.jeSplet && istaSpletnaStran(v.naslov, vir.naslov)) ||
                (!v.jeSplet && !vir.jeSplet && kanonicniNaslov(v.naslov) == kanonicniNaslov(vir.naslov))
        }
        if (obstojeci != null) return obstojeci
        shrani(ctx, rocni(ctx) + vir)
        return vir
    }

    /** Enota za vir: seznam in podkast se odpreta kot seznam (klik), ostalo predvaja predvajalnik ali brskalnik. */
    fun kotSkladba(v: Vir): Jamendo.Skladba = when {
        v.jePodkast -> Podkasti.oddaja(v.naslov, v.ime, v.naslov.removePrefix("https://").substringBefore('/'), "")
        else -> Jamendo.Skladba((if (v.jeSeznam) PREDPONA_SEZNAMA else "vir:") + v.naslov, v.ime,
            v.naslov.removePrefix("https://").removePrefix("http://"), "", v.naslov, v.naslov,
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
        val p = try { URL(naslov).openConnection() as HttpURLConnection } catch (_: Exception) { return skladbeSeznama(ctx, naslov) }
        p.connectTimeout = 8_000; p.readTimeout = 10_000
        p.setRequestProperty("User-Agent", "SafeerOS")
        val nove = try {
            if (p.responseCode in 200..299) beriSeznam(p.inputStream.bufferedReader().use { it.readText().take(2_000_000) }, naslov) else emptyList()
        } catch (_: Exception) { emptyList() } finally { p.disconnect() }
        if (nove.isNotEmpty()) pisi(ctx, kljucSeznama(naslov), pisiSkladbe(nove.take(1000)))
        return nove.ifEmpty { skladbeSeznama(ctx, naslov) }
    }

    /** Skladbe iz dodanih seznamov, ki ustrezajo iskanju (z virom, ki pove izvor). */
    fun iskanjeVSeznamih(ctx: Context, beseda: String): List<Pair<Vir, Jamendo.Skladba>> =
        vsi(ctx).filter { it.jeSeznam }.flatMap { v ->
            skladbeSeznama(ctx, v.naslov).filter { Relevantnost.ocena(beseda, it.naslov, it.izvajalec) >= Relevantnost.SPODNJA }.map { v to it }
        }

    /** Vnosi .m3u (#EXTINF: ime, nato naslov) ali .pls (FileN / TitleN); relativni naslovi glede na seznam. */
    fun beriSeznam(telo: String, osnova: String): List<Jamendo.Skladba> {
        fun polni(n: String) = try { URL(URL(osnova), n.trim()).toString() } catch (_: Exception) { "" }
        val pari = ArrayList<Pair<String, String>>()
        if (telo.trimStart().startsWith("[playlist]", ignoreCase = true)) {
            val datoteke = HashMap<String, String>(); val imena = HashMap<String, String>()
            telo.lines().forEach { l ->
                Regex("""(?i)^File(\d+)=(.+)$""").find(l.trim())?.let { datoteke[it.groupValues[1]] = it.groupValues[2] }
                Regex("""(?i)^Title(\d+)=(.+)$""").find(l.trim())?.let { imena[it.groupValues[1]] = it.groupValues[2] }
            }
            datoteke.keys.sortedBy { it.toIntOrNull() ?: 0 }.forEach { k -> pari += imena[k].orEmpty() to polni(datoteke[k]!!) }
        } else {
            var ime = ""
            telo.lines().map { it.trim() }.forEach { l ->
                when {
                    l.startsWith("#EXTINF", ignoreCase = true) -> ime = l.substringAfter(',', "").trim()
                    l.isEmpty() || l.startsWith("#") -> {}
                    else -> { pari += ime to polni(l); ime = "" }
                }
            }
        }
        return pari.filter { (it.second.startsWith("http://") || it.second.startsWith("https://")) && jePredvajljiv(it.second) }.map { (ime, url) ->
            val (naslov, izvajalec) = Relevantnost.razdeli(ime.ifBlank { url.substringAfterLast('/').substringBefore('?') }, "")
            Jamendo.Skladba("seznam:$url", naslov, izvajalec, "", url, osnova,
                mime = if (url.substringBefore('?').lowercase().endsWith(".m3u8")) MIME_HLS else "")
        }.distinctBy { it.zvok }
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
                    val vnosi = beriSeznam(telo, naslov)
                    // Radijski .pls ima pogosto vec zrcal istega toka (brez koncnice): to je ena postaja.
                    // Seznam je, kadar so vnosi vsaj v polovici datoteke (.mp3, .m3u8 kanali ...).
                    val datotek = vnosi.count { jeDatoteka(it.zvok) }
                    when {
                        vnosi.size > 1 && datotek * 2 >= vnosi.size -> {
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
    @Synchronized private fun shrani(ctx: Context, viri: List<Vir>, cas: Long = 0L, brezSledi: Boolean = false) {
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
        ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit().putString(KLJUC, a.toString())
            .putString(VIRI_CASI, JSONObject(casi as Map<*, *>).toString()).putString(VIRI_IZBRISANI, JSONObject(izbrisani as Map<*, *>).toString()).apply()
    }

    // ------------------------------------------------------------------ viri so enaki na vseh napravah v Linku

    private const val VIRI_CASI = "viri_casi"
    private const val VIRI_IZBRISANI = "viri_izbrisani"
    private val VRSTE_ZA_USKLADITEV = Regex("^(stremio|peertube|splet|api|seznam|podkast|tok(-hls)?(-video)?)$")

    fun kljucUskladitve(v: Vir) = v.tip + "|" + v.naslov

    private fun beriCase(ctx: Context, kljuc: String): MutableMap<String, Long> = try {
        val o = JSONObject(ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).getString(kljuc, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.optLong(it) }.toMutableMap()
    } catch (_: Exception) { mutableMapOf() }

    /** Vir, ki ga smemo poslati drugi napravi ali prevzeti z nje: znana vrsta, javen naslov, ni blokiran. */
    private fun uskladljiv(v: Vir): Boolean =
        VRSTE_ZA_USKLADITEV.matches(v.tip) && v.naslov.length in 4..2048 && v.ime.length <= 200 &&
            v.naslov.none { it < ' ' } && v.ime.none { it < ' ' } && !PeerTube.jeBlokiran(v.naslov) &&
            (v.jePeerTube || v.naslov.startsWith("http://") || v.naslov.startsWith("https://")) &&
            !(v.jeSplet && jeImenikVirov(v.naslov))

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

    /** Vir z druge naprave: dodamo ga, ce ga tu se ni in ga tu nismo izbrisali pozneje, kot je bil tam dodan. */
    @Synchronized fun prevzemiVir(ctx: Context, v: Vir, cas: Long): Boolean {
        if (!uskladljiv(v)) return false
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
    fun prevzemiNaslov(ctx: Context, naslov: String, ime: String, cas: Long): Boolean {
        if (naslov.length !in 4..2048 || naslov.any { it < ' ' } || !(naslov.startsWith("http://") || naslov.startsWith("https://"))) return false
        synchronized(this) {
            val moji = rocni(ctx)
            if (moji.size >= 400 || moji.any { it.naslov == naslov || (!it.jePeerTube && kanonicniNaslov(it.naslov) == kanonicniNaslov(naslov)) }) return false
            // Tu izbrisan pozneje, kot je bil na racunalniku dodan: se ne vrne (ne glede na vrsto, ki jo je imel tukaj).
            val izbris = beriCase(ctx, VIRI_IZBRISANI).filterKeys { it.substringAfter('|') == naslov }.values.maxOrNull()
            if (!SeznamiPravila.virPrevzamemo(false, izbris, cas)) return false
        }
        if (PeerTube.jeBlokiran(naslov) || !jePredvajljiv(naslov)) return false
        val gostitelj = try { URL(naslov).host } catch (_: Exception) { return false }
        val v = razvrsti(ctx, naslov, gostitelj) ?: return false
        return prevzemiVir(ctx, if (ime.isBlank() || ime.length > 200 || ime.any { it < ' ' }) v else v.copy(ime = ime), cas)
    }

    /** Vrsta vira v usklajevanju, ki jo poslje racunalnik za naslov, dodan tam (core/viri_sink.py). */
    const val VRSTA_URL = "url"

    /** Vir je bil izbrisan na drugi napravi ob [cas]: izgine tudi tu, ce ga tu nismo dodali pozneje. Vrne true ob izbrisu. */
    @Synchronized fun prevzemiIzbrisVira(ctx: Context, kljuc: String, cas: Long): Boolean {
        if (cas <= 0L || kljuc.length > 2100) return false
        val odRacunalnika = kljuc.startsWith("$VRSTA_URL|")
        // Racunalnik ne pozna vrste, ki jo je naslovu dala ta naprava: izbris "url|<naslov>" velja za vir s tem naslovom.
        val moj = rocni(ctx).firstOrNull { kljucUskladitve(it) == kljuc }
            ?: if (odRacunalnika) rocni(ctx).firstOrNull { !it.jeStremio && !it.jePeerTube && it.naslov == kljuc.substringAfter('|') } else null
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
