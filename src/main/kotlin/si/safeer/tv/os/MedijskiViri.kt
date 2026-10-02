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
        val jeKodi get() = tip == KODI
        val jeDodatek get() = jeStremio || jeKodi
    }

    /** Dodatki, ki jih uporabnik vnese SAM (Stremio: naslov manifesta; Kodi: repozitorij ali .zip). Brez prilozenih. */
    const val STREMIO = "stremio"
    const val KODI = "kodi"

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
        shrani(ctx, vsi(ctx) + v)
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
        val shranjeni = rocni(ctx).filterNot { PeerTube.jeBlokiran(it.naslov) || (it.jeSplet && jeImenikVirov(it.naslov)) }
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

    /** Seznam predvajanja, ki si ga je uporabnik shranil med priljubljene. */
    data class Seznam(val ime: String, val skladbe: List<Jamendo.Skladba>)

    /**
     * Ali skladbo lahko shranimo: splet, radio, Jamendo, PeerTube (datoteko ob predvajanju vprasamo
     * znova). Datoteke z naprav ne - njihov naslov velja samo za trenutno povezavo Safeer Linka.
     */
    fun shranljiva(s: Jamendo.Skladba): Boolean {
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

    fun priljubljene(ctx: Context): List<Jamendo.Skladba> = beriSkladbe(beri(ctx, PRILJUBLJENE))

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
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Seznam(it.optString("ime"), beriSkladbe(it.optJSONArray("skladbe")?.toString() ?: "[]")) }
            .filter { it.ime.isNotBlank() && it.skladbe.isNotEmpty() }
    }

    /** Shrani seznam (isto ime zamenja); vrne shranjeni seznam ali null, ce v njem ni nicesar shranljivega. */
    fun shraniSeznam(ctx: Context, ime: String, skladbe: List<Jamendo.Skladba>): Seznam? {
        val sz = Seznam(ime, skladbe.filter { zaSeznam(it) }.map { zaShranjevanje(it) }.distinctBy { it.id }.take(NAJVEC_V_SEZNAMU))
        if (sz.skladbe.isEmpty()) return null
        pisiSeznami(ctx, listOf(sz) + seznami(ctx).filterNot { it.ime == ime })
        return sz
    }

    const val NAJVEC_V_SEZNAMU = 400

    /** Doda skladbo na konec seznama (ustvari ga, ce ga se ni); vrne false, ce je ze na njem ali je ni mogoce shraniti. */
    fun dodajNaSeznam(ctx: Context, ime: String, s: Jamendo.Skladba): Boolean {
        if (!zaSeznam(s)) return false
        val vsi = seznami(ctx)
        val obstojeci = vsi.firstOrNull { it.ime == ime }
        if (obstojeci != null && obstojeci.skladbe.any { it.id == s.id }) return false
        val nov = Seznam(ime, ((obstojeci?.skladbe ?: emptyList()) + zaShranjevanje(s)).takeLast(NAJVEC_V_SEZNAMU))
        // Obstojeci seznam ostane na svojem mestu, nov gre na zacetek.
        pisiSeznami(ctx, if (obstojeci != null) vsi.map { if (it.ime == ime) nov else it } else listOf(nov) + vsi)
        return true
    }

    /** Odstrani skladbo s seznama; prazen seznam izgine. */
    fun odstraniSSeznama(ctx: Context, ime: String, s: Jamendo.Skladba) =
        pisiSeznami(ctx, seznami(ctx).map { if (it.ime == ime) it.copy(skladbe = it.skladbe.filterNot { x -> x.id == s.id }) else it })

    /**
     * Uvozena skladba je dobila posnetek ([UvozSeznama.najdi]): v seznamih in med priljubljenimi jo zamenjamo, da
     * naslednjic zacne takoj in ima svojo sliko.
     */
    @Synchronized fun zamenjaj(ctx: Context, zamenjave: Map<String, Jamendo.Skladba>) {
        if (zamenjave.isEmpty()) return
        val sz = seznami(ctx)
        if (sz.any { l -> l.skladbe.any { it.id in zamenjave } })
            pisiSeznami(ctx, sz.map { l -> l.copy(skladbe = l.skladbe.map { zamenjave[it.id] ?: it }.distinctBy { it.id }) })
        val p = priljubljene(ctx)
        if (p.any { it.id in zamenjave }) pisi(ctx, PRILJUBLJENE, pisiSkladbe(p.map { zamenjave[it.id] ?: it }.distinctBy { it.id }))
    }

    fun odstraniSeznam(ctx: Context, ime: String) = pisiSeznami(ctx, seznami(ctx).filterNot { it.ime == ime })

    private fun pisiSeznami(ctx: Context, s: List<Seznam>) {
        val a = JSONArray()
        s.take(30).forEach { a.put(JSONObject().put("ime", it.ime).put("skladbe", JSONArray(pisiSkladbe(it.skladbe)))) }
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
    fun nedavno(ctx: Context): List<Jamendo.Skladba> = beriSkladbe(beri(ctx, NEDAVNO)).distinctBy { it.id }.take(MAX_NEDAVNO)

    fun odstraniNedavno(ctx: Context, s: Jamendo.Skladba) =
        pisi(ctx, NEDAVNO, pisiSkladbe(nedavno(ctx).filterNot { it.id == s.id }))

    fun pocistiNedavno(ctx: Context) = pisi(ctx, NEDAVNO, "[]")

    fun zapomniNedavno(ctx: Context, s: Jamendo.Skladba) {
        if (!shranljiva(s)) return
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
     * Matej, 1. 10. 2026: uporabnik vire doda in pozabi - tudi vgrajenih mu ni treba trpeti.
     */
    fun vgrajeniPeerTube(ctx: Context): List<String> {
        val proc = odstranjeni(ctx)
        return PeerTube.VGRAJENI.filterNot { it in proc }
    }

    /** Vgrajeni streznik kot vir (za Moji viri: odstranitev z istim dialogom kot dodani viri). */
    fun vgrajenPeerTubeVir(streznik: String) = Vir(PEERTUBE, streznik, streznik)

    fun odstrani(ctx: Context, vir: Vir) {
        zapomniOdstranjen(ctx, vir.naslov)
        shrani(ctx, rocni(ctx).filterNot { it.naslov == vir.naslov || istaSpletnaStran(it.naslov, vir.naslov) })
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

    private fun shrani(ctx: Context, viri: List<Vir>) {
        val a = JSONArray()
        viri.forEach { a.put(JSONObject().put("tip", it.tip).put("ime", it.ime).put("naslov", it.naslov)) }
        ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit().putString(KLJUC, a.toString()).apply()
    }
}
