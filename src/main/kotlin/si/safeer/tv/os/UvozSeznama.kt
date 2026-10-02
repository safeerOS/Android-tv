package si.safeer.tv.os

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Uvoz seznama predvajanja iz YouTuba ali Spotifyja v Safeerjeve sezname predvajanja (lastnik, 2. 10. 2026:
 * "ce ima uporabnik pri YouTube ali Spotify priljubljen seznam, ga mora enostavno prenesti v nas predvajalnik").
 *
 * Uporabnik prilepi ali deli povezavo javnega (ali nenavedenega) seznama; preberemo naslove, izvajalce in slike.
 *  - YouTube: vsak posnetek je enota spletnega vira (stran posnetka) - predvaja jo nas predvajalnik kot vsak
 *    drug zadetek s spletnega vira.
 *  - Spotify: Spotify svojih tokov ne daje drugim predvajalnikom, zato prenesemo SEZNAM SKLADB (naslov,
 *    izvajalec, dolzina); posnetek zanjo poiscemo ob predvajanju ([najdi]) in si ga zapomnimo.
 * Brez prijave: zasebnih seznamov ("Vsecki", Liked Songs) ne moremo brati - uporabnik jih najprej da v javni
 * ali nenavedeni seznam. Nic se ne posilja nikomur razen strani, s katere uporabnik sam uvaza.
 */
object UvozSeznama {
    /** Id skladbe, ki se nima posnetka: "isci:<dolzina v s>|<izvajalec - naslov>". */
    const val PREDPONA_ISKANJA = "isci:"
    const val NAJVEC = 400
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    data class Uvoz(val ime: String, val skladbe: List<Jamendo.Skladba>, val vir: String)

    // ------------------------------------------------------------------ prepoznava povezave

    fun youtubeId(vnos: String): String? {
        val u = vnos.trim()
        if (!Regex("(?i)(^|[/.@])(youtube\\.com|youtu\\.be|youtube-nocookie\\.com)/").containsMatchIn(u)) return null
        return Regex("[?&]list=([A-Za-z0-9_-]{10,})").find(u)?.groupValues?.get(1)
    }

    /** (vrsta, id): seznam predvajanja ali album. */
    fun spotifyId(vnos: String): Pair<String, String>? {
        val u = vnos.trim()
        val m = Regex("(?i)open\\.spotify\\.com/(?:intl-[a-z-]+/)?(?:embed/)?(playlist|album)/([A-Za-z0-9]{10,})").find(u)
            ?: Regex("(?i)spotify:(playlist|album):([A-Za-z0-9]{10,})").find(u) ?: return null
        return m.groupValues[1].lowercase() to m.groupValues[2]
    }

    fun jePovezava(vnos: String) = youtubeId(vnos) != null || spotifyId(vnos) != null

    /** Povezava seznama v deljenem besedilu ("Poslusaj ta seznam: https://...") ali null. */
    fun povezavaIz(besedilo: String): String? =
        Regex("(?:https?://|spotify:)\\S+").findAll(besedilo).map { it.value.trimEnd('.', ',', ')') }.firstOrNull { jePovezava(it) }

    /** Vnos je YouTube ali Spotify, a ni seznam (posamezen posnetek, zasebni "Vsecki" ...): za jasno sporocilo. */
    fun jeZasebenAliPosamezen(vnos: String): Boolean =
        !jePovezava(vnos) && Regex("(?i)(youtube\\.com|youtu\\.be|spotify\\.com|spotify\\.link)").containsMatchIn(vnos)

    // ------------------------------------------------------------------ uvoz (omrezje; klici z delovne niti)

    fun uvozi(vnos: String): Uvoz? {
        youtubeId(vnos)?.let { return uvoziYoutube(it) }
        spotifyId(vnos)?.let { (vrsta, id) -> return uvoziSpotify(vrsta, id) }
        return null
    }

    private fun uvoziYoutube(id: String): Uvoz? {
        val html = beri("https://www.youtube.com/playlist?list=$id&hl=en", youtube = true) ?: return null
        val podatki = jsonZa(html, "ytInitialData") ?: return null
        val prva = izYoutube(podatki)
        val skladbe = LinkedHashMap<String, Vnos>()
        prva.vnosi.forEach { skladbe.putIfAbsent(it.id, it) }
        // Nadaljevanja (po 100): isti vmesnik, kot ga klice stran sama, ko uporabnik drsi po seznamu.
        val razlicica = Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: "2.20250101.00.00"
        var zeton = prva.nadaljevanje
        var krogov = 0
        while (zeton != null && skladbe.size < NAJVEC && krogov++ < 6) {
            val telo = JSONObject().put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB").put("clientVersion", razlicica).put("hl", "en"))).put("continuation", zeton)
            val o = beri("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", youtube = true, telo = telo.toString())
                ?.let { try { JSONObject(it) } catch (_: Exception) { null } } ?: break
            val stran = izYoutube(o)
            val prej = skladbe.size
            stran.vnosi.forEach { skladbe.putIfAbsent(it.id, it) }
            if (skladbe.size == prej) break
            zeton = stran.nadaljevanje
        }
        if (skladbe.isEmpty()) return null
        val v = skladbe.values.take(NAJVEC)
        // Glasbeni seznam (vecina posnetkov ima oznako glasbe): poslusanje z ovitkom; sicer video.
        val glasba = v.count { it.glasba } * 2 >= v.size
        return Uvoz(prva.ime.ifBlank { "YouTube" }, v.map { youtubeSkladba(it.id, it.naslov, it.kanal, video = !glasba) }, "YouTube")
    }

    internal fun youtubeSkladba(id: String, naslov: String, kanal: String, video: Boolean): Jamendo.Skladba {
        // "Izvajalec - Naslov (Official Video)" razdelimo; kanal ostane izvajalec, kadar locila ni.
        val cistKanal = kanal.removeSuffix(" - Topic").removeSuffix("VEVO").trim()
        val i = naslov.indexOf(" - ")
        val (n, iz) = if (i > 0 && i < naslov.length - 3) naslov.substring(i + 3).trim() to naslov.substring(0, i).trim() else naslov to cistKanal
        val stran = "https://www.youtube.com/watch?v=$id"
        return SpletniVir.enota(stran, n.ifBlank { naslov }, iz.ifBlank { "YouTube" }, "https://i.ytimg.com/vi/$id/mqdefault.jpg", video)
    }

    private fun uvoziSpotify(vrsta: String, id: String): Uvoz? {
        val html = beri("https://open.spotify.com/embed/$vrsta/$id") ?: return null
        val (ime, vnosi) = izSpotify(html) ?: return null
        if (vnosi.isEmpty()) return null
        return Uvoz(ime.ifBlank { "Spotify" }, vnosi.take(NAJVEC), "Spotify")
    }

    // ------------------------------------------------------------------ razclenjevanje (brez omrezja)

    internal data class Vnos(val id: String, val naslov: String, val kanal: String, val glasba: Boolean)
    internal data class Stran(val ime: String, val vnosi: List<Vnos>, val nadaljevanje: String?)

    /** Objekt JSON, ki ga stran priredi spremenljivki (var ytInitialData = {...};). */
    internal fun jsonZa(html: String, ime: String): JSONObject? {
        val z = Regex("$ime\\s*=\\s*\\{").find(html) ?: return null
        val od = z.range.last
        val konec = html.indexOf(";</script>", od).takeIf { it > 0 } ?: return null
        return try { JSONObject(html.substring(od, konec)) } catch (_: Exception) { null }
    }

    /** Posnetki seznama iz podatkov strani; razume staro (playlistVideoRenderer) in novo obliko (lockupViewModel). */
    internal fun izYoutube(d: JSONObject): Stran {
        val vnosi = ArrayList<Vnos>()
        var ime = ""
        var zeton: String? = null
        fun besedilo(o: JSONObject?): String = o?.optString("simpleText")?.takeIf { it.isNotBlank() }
            ?: o?.optString("content")?.takeIf { it.isNotBlank() }
            ?: o?.optJSONArray("runs")?.let { r -> (0 until r.length()).joinToString("") { r.optJSONObject(it)?.optString("text").orEmpty() } }.orEmpty()
        fun hodi(x: Any?, globina: Int) {
            if (globina > 60) return
            when (x) {
                is JSONArray -> for (i in 0 until x.length()) hodi(x.opt(i), globina + 1)
                is JSONObject -> {
                    x.optJSONObject("playlistMetadataRenderer")?.let { if (ime.isBlank()) ime = it.optString("title") }
                    x.optJSONObject("continuationCommand")?.let { c ->
                        if (c.optString("request").contains("BROWSE") && c.optString("token").isNotBlank()) zeton = c.optString("token")
                    }
                    val stari = x.optJSONObject("playlistVideoRenderer")
                    val novi = x.optJSONObject("lockupViewModel")
                    when {
                        stari != null && stari.optString("videoId").isNotBlank() -> {
                            vnosi += Vnos(stari.optString("videoId"), besedilo(stari.optJSONObject("title")),
                                besedilo(stari.optJSONObject("shortBylineText")), false)
                            return
                        }
                        novi != null && novi.optString("contentId").isNotBlank() && novi.optString("contentType").contains("VIDEO") -> {
                            val m = novi.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                            val kanal = m?.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")
                                ?.optJSONObject(0)?.optJSONArray("metadataParts")?.optJSONObject(0)?.optJSONObject("text")
                            vnosi += Vnos(novi.optString("contentId"), besedilo(m?.optJSONObject("title")), besedilo(kanal),
                                novi.optJSONObject("contentImage")?.toString()?.contains("\"imageName\":\"MUSIC\"") == true)
                            return
                        }
                    }
                    val k = x.keys()
                    while (k.hasNext()) hodi(x.opt(k.next()), globina + 1)
                }
            }
        }
        hodi(d, 0)
        return Stran(ime, vnosi.filter { it.naslov.isNotBlank() }, zeton)
    }

    /** Ime in skladbe iz vgradne strani Spotifyja (__NEXT_DATA__); skladbe se nimajo posnetka (id [PREDPONA_ISKANJA]). */
    internal fun izSpotify(html: String): Pair<String, List<Jamendo.Skladba>>? {
        val od = html.indexOf("__NEXT_DATA__").takeIf { it >= 0 }?.let { html.indexOf('>', it) }?.takeIf { it > 0 } ?: return null
        val konec = html.indexOf("</script>", od).takeIf { it > 0 } ?: return null
        val e = try {
            JSONObject(html.substring(od + 1, konec)).optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
                ?.optJSONObject("data")?.optJSONObject("entity")
        } catch (_: Exception) { null } ?: return null
        val ovitek = e.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url").orEmpty()
        val album = e.optString("type") == "album"
        val a = e.optJSONArray("trackList") ?: return null
        val skladbe = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { t ->
            val naslov = t.optString("title").trim()
            // Pri albumu je podnaslov skladbe izvajalec; ce manjka, velja izvajalec albuma.
            val izvajalec = t.optString("subtitle").replace(' ', ' ').trim().ifBlank { if (album) e.optString("subtitle") else "" }
            if (naslov.isBlank()) null else iskana(naslov, izvajalec, t.optInt("duration") / 1000, ovitek)
        }
        return e.optString("name").ifBlank { e.optString("title") } to skladbe
    }

    /** Skladba brez posnetka: predvajalnik ga poisce ob predvajanju ([najdi]). */
    internal fun iskana(naslov: String, izvajalec: String, sekund: Int, slika: String): Jamendo.Skladba =
        Jamendo.Skladba(PREDPONA_ISKANJA + sekund + "|" + izvajalec + " - " + naslov, naslov, izvajalec, slika, "",
            "https://www.youtube.com/results?search_query=" + URLEncoder.encode("$izvajalec $naslov".trim(), "UTF-8"))

    fun jeIskana(s: Jamendo.Skladba) = s.id.startsWith(PREDPONA_ISKANJA)

    // ------------------------------------------------------------------ posnetek za skladbo (omrezje)

    /**
     * Posnetek za skladbo brez toka: prvi zadetki iskanja, med njimi tisti z najblizjo dolzino (album ali
     * uradni posnetek, ne desetminutni koncert). Vrne enoto spletnega vira ali null.
     */
    fun najdi(s: Jamendo.Skladba): Jamendo.Skladba? {
        val sekund = s.id.removePrefix(PREDPONA_ISKANJA).substringBefore('|').toIntOrNull() ?: 0
        val poizvedba = "${s.izvajalec} ${s.naslov}".trim()
        // Najprej vmesnik strani (odgovor JSON, ~50 kB stisnjeno), sicer stran z zadetki (vecja) - isti podatki.
        val telo = JSONObject().put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "WEB").put("clientVersion", "2.20261001.01.00").put("hl", "en"))).put("query", poizvedba)
        val podatki = beri("https://www.youtube.com/youtubei/v1/search?prettyPrint=false", youtube = true, telo = telo.toString())
            ?.let { try { JSONObject(it) } catch (_: Exception) { null } }
            ?: beri("https://www.youtube.com/results?search_query=" + URLEncoder.encode(poizvedba, "UTF-8") + "&hl=en", youtube = true)
                ?.let { jsonZa(it, "ytInitialData") }
            ?: return null
        val id = izberiZadetek(zadetkiIskanja(podatki), sekund) ?: return null
        return SpletniVir.enota("https://www.youtube.com/watch?v=$id", s.naslov, s.izvajalec, "https://i.ytimg.com/vi/$id/mqdefault.jpg", false)
    }

    /** (id, dolzina v sekundah) zadetkov iskanja po vrsti; razume videoRenderer in lockupViewModel. */
    internal fun zadetkiIskanja(d: JSONObject): List<Pair<String, Int>> {
        val izhod = ArrayList<Pair<String, Int>>()
        fun sekunde(t: String): Int = t.trim().split(':').mapNotNull { it.toIntOrNull() }.takeIf { it.size in 2..3 }
            ?.fold(0) { a, b -> a * 60 + b } ?: 0
        fun hodi(x: Any?, globina: Int) {
            if (globina > 60 || izhod.size >= 12) return
            when (x) {
                is JSONArray -> for (i in 0 until x.length()) hodi(x.opt(i), globina + 1)
                is JSONObject -> {
                    val v = x.optJSONObject("videoRenderer")
                    val l = x.optJSONObject("lockupViewModel")
                    when {
                        v != null && v.optString("videoId").isNotBlank() -> {
                            izhod += v.optString("videoId") to sekunde(v.optJSONObject("lengthText")?.optString("simpleText").orEmpty()); return
                        }
                        l != null && l.optString("contentId").isNotBlank() && l.optString("contentType").contains("VIDEO") -> {
                            val cas = Regex("\"text\":\"(\\d{1,2}(?::\\d{2}){1,2})\"").find(l.optJSONObject("contentImage")?.toString().orEmpty())?.groupValues?.get(1).orEmpty()
                            izhod += l.optString("contentId") to sekunde(cas); return
                        }
                    }
                    val k = x.keys()
                    while (k.hasNext()) hodi(x.opt(k.next()), globina + 1)
                }
            }
        }
        hodi(d, 0)
        return izhod
    }

    internal fun izberiZadetek(z: List<Pair<String, Int>>, sekund: Int): String? {
        if (z.isEmpty()) return null
        if (sekund <= 0) return z.first().first
        // Med prvimi petimi tisti, ki je po dolzini najblizje (do 12 s razlike); sicer prvi zadetek.
        return z.take(5).filter { it.second > 0 && kotlin.math.abs(it.second - sekund) <= 12 }
            .minByOrNull { kotlin.math.abs(it.second - sekund) }?.first ?: z.first().first
    }

    private fun beri(naslov: String, youtube: Boolean = false, telo: String? = null): String? {
        val p = try { URL(naslov).openConnection() as HttpURLConnection } catch (_: Exception) { return null }
        return try {
            p.connectTimeout = 8_000; p.readTimeout = 12_000
            p.setRequestProperty("User-Agent", UA)
            p.setRequestProperty("Accept-Language", "en")
            // Brez tega piskotka YouTube v EU namesto seznama vrne stran za soglasje (nic osebnega: "samo nujni").
            if (youtube) p.setRequestProperty("Cookie", "SOCS=CAI")
            if (telo != null) {
                p.requestMethod = "POST"; p.doOutput = true
                p.setRequestProperty("Content-Type", "application/json")
                p.outputStream.use { it.write(telo.toByteArray()) }
            }
            if (p.responseCode in 200..299) p.inputStream.bufferedReader().use { it.readText().take(6_000_000) } else null
        } catch (_: Exception) { null } finally { p.disconnect() }
    }
}
