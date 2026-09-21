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

    data class Vir(val tip: String, val ime: String, val naslov: String) {
        val jePeerTube get() = tip == PEERTUBE
        val jeSplet get() = tip == SPLET
    }

    const val PEERTUBE = "peertube"
    const val TOK = "tok"
    /** Spletna stran z glasbo ali videom: odpre jo brskalnik Safeer (jedro Safeer OS), ki predvaja vse. */
    const val SPLET = "splet"

    fun vsi(ctx: Context): List<Vir> {
        val a = try { JSONArray(ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).getString(KLJUC, "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Vir(it.optString("tip"), it.optString("ime"), it.optString("naslov")) }
    }

    // ------------------------------------------------------------------ priljubljene

    /** Seznam predvajanja, ki si ga je uporabnik shranil med priljubljene. */
    data class Seznam(val ime: String, val skladbe: List<Jamendo.Skladba>)

    /**
     * Ali skladbo lahko shranimo: splet, radio, Jamendo, PeerTube (datoteko ob predvajanju vprasamo
     * znova). Datoteke z naprav ne - njihov naslov velja samo za trenutno povezavo Safeer Linka.
     */
    fun shranljiva(s: Jamendo.Skladba): Boolean {
        val naprava = s.mime.isNotBlank() && s.mime != STRAN && s.streznik.isBlank()
        return !naprava && (s.zvok.startsWith("https://") || (s.video && s.streznik.isNotBlank()))
    }

    private fun zaShranjevanje(s: Jamendo.Skladba) = if (s.video && s.streznik.isNotBlank()) s.copy(zvok = "", mime = "") else s

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
        val sz = Seznam(ime, skladbe.filter { shranljiva(it) }.map { zaShranjevanje(it) }.distinctBy { it.id }.take(200))
        if (sz.skladbe.isEmpty()) return null
        pisiSeznami(ctx, listOf(sz) + seznami(ctx).filterNot { it.ime == ime })
        return sz
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
            .put("streznik", it.streznik).put("kanal", it.kanal)) }
        return a.toString()
    }

    private fun beriSkladbe(json: String): List<Jamendo.Skladba> {
        val a = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Jamendo.Skladba(it.optString("id"), it.optString("naslov"), it.optString("izvajalec"), it.optString("slika"), it.optString("zvok"),
                it.optString("povezava"), it.optBoolean("radio"), it.optBoolean("video"), it.optString("mime"), it.optString("streznik"), it.optString("kanal"))
        }.filter { it.id.isNotBlank() }
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
        (PeerTube.VGRAJENI + vsi(ctx).filter { it.jePeerTube }.map { it.naslov }).distinct()

    fun odstrani(ctx: Context, vir: Vir) = shrani(ctx, vsi(ctx).filterNot { it == vir })

    /**
     * Preveri naslov in ga doda. Najprej PeerTube (vpisano ime streznika ali naslov strani), nato
     * neposreden tok: odgovor mora biti zvok ali video (ali seznam .m3u/.pls, iz katerega vzamemo
     * prvi naslov). Vrne dodani vir ali null, ce na naslovu ni nicesar, kar bi znali predvajati.
     */
    fun dodaj(ctx: Context, vnos: String): Vir? {
        val cisto = vnos.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        val gostitelj = try { URL(cisto).host } catch (_: Exception) { return null }
        val jePot = try { URL(cisto).path.trim('/').isNotEmpty() } catch (_: Exception) { false }
        val vir = PeerTube.imeStreznika(gostitelj)?.takeIf { !jePot || cisto.contains("/videos") || cisto.contains("/c/") || cisto.contains("/a/") }
            ?.let { Vir(PEERTUBE, it, gostitelj) }
            ?: tok(cisto)?.let { (url, video) -> Vir(if (video) "$TOK-video" else TOK, gostitelj, url) }
            ?: if (jeStran(cisto)) Vir(SPLET, gostitelj.removePrefix("www."), cisto) else return null
        shrani(ctx, vsi(ctx).filterNot { it.naslov == vir.naslov } + vir)
        return vir
    }

    fun kotSkladba(v: Vir) = Jamendo.Skladba("vir:" + v.naslov, v.ime, v.naslov.removePrefix("https://").removePrefix("http://"),
        "", v.naslov, v.naslov, radio = !v.tip.endsWith("video"), video = v.tip.endsWith("video"),
        mime = if (v.jeSplet) STRAN else "")

    /** Oznaka enote, ki je spletna stran (odpre jo brskalnik, ne nas predvajalnik). */
    const val STRAN = "text/html"

    /** Naslov toka in ali je video; seznam .m3u/.pls razpakira v prvi naslov. */
    private fun tok(naslov: String, globina: Int = 0): Pair<String, Boolean>? {
        if (globina > 1) return null
        val p = try { URL(naslov).openConnection() as HttpURLConnection } catch (_: Exception) { return null }
        p.connectTimeout = 8_000; p.readTimeout = 8_000
        p.setRequestProperty("User-Agent", "SafeerOS")
        p.setRequestProperty("Icy-MetaData", "0")
        return try {
            if (p.responseCode !in 200..299) return null
            val vrsta = (p.contentType ?: "").lowercase()
            when {
                vrsta.contains("mpegurl") || vrsta.contains("scpls") || naslov.endsWith(".m3u") || naslov.endsWith(".pls") -> {
                    val telo = p.inputStream.bufferedReader().use { it.readText().take(20_000) }
                    val prvi = Regex("""(https?://\S+)""").find(telo)?.value?.trim() ?: return null
                    if (prvi.contains(".m3u8")) null else tok(prvi, globina + 1)
                }
                vrsta.startsWith("audio/") || vrsta == "application/ogg" -> naslov to false
                vrsta.startsWith("video/") -> naslov to true
                else -> null
            }
        } catch (_: Exception) { null } finally { p.disconnect() }
    }

    private fun jeStran(naslov: String): Boolean = try {
        val p = URL(naslov).openConnection() as HttpURLConnection
        p.connectTimeout = 8_000; p.readTimeout = 8_000; p.setRequestProperty("User-Agent", "SafeerOS")
        try { p.responseCode in 200..399 && (p.contentType ?: "").lowercase().contains("text/html") } finally { p.disconnect() }
    } catch (_: Exception) { false }

    private fun shrani(ctx: Context, viri: List<Vir>) {
        val a = JSONArray()
        viri.forEach { a.put(JSONObject().put("tip", it.tip).put("ime", it.ime).put("naslov", it.naslov)) }
        ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit().putString(KLJUC, a.toString()).apply()
    }
}
