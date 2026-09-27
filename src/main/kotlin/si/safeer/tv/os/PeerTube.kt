package si.safeer.tv.os

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Video s PeerTuba: odprtokodno, brez oglasov in brez sledenja. Vgrajeni strezniki in strezniki,
 * ki jih uporabnik doda sam ([MedijskiViri]). Samo javni posnetki brez obcutljive vsebine, ki imajo
 * predvajljiv tok. Federirano iskanje dopolni SepiaSearch; za tok vedno vprasamo domaci streznik.
 */
object PeerTube {
    val VGRAJENI = listOf("peertube.tv", "tilvids.com", "framatube.org", "peertube.uno", "video.blender.org")
    private const val ROK_MS = 5_000L
    private const val SEPIA = "https://sepiasearch.org/api/v1/search/videos"

    /** Najbolj gledani posnetki enega streznika. */
    fun najboljGledani(streznik: String, stevilo: Int = 24): List<Jamendo.Skladba> =
        najboljGledani(listOf(streznik), stevilo).firstOrNull()?.second.orEmpty()

    /** Police vec streznikov nalozimo hkrati in ohranimo njihov vrstni red. */
    fun najboljGledani(strezniki: List<String>, stevilo: Int = 24): List<Pair<String, List<Jamendo.Skladba>>> {
        val opravila: List<() -> Pair<String, List<Jamendo.Skladba>>> = strezniki.distinct().map { s ->
            { s to (try { seznam(s, "/api/v1/videos?sort=-views&count=$stevilo&nsfw=false&isLocal=true") } catch (_: Exception) { emptyList() }) }
        }
        val surovi = vzporedno(opravila)
        val razreseni = razresiVzporedno(surovi.flatMap { it.second }).groupBy { it.streznik }
        return surovi.map { (s, _) -> s to razreseni[s].orEmpty() }
    }

    /**
     * Iskanje po streznikih in uradnem federiranem indeksu. PeerTube isce ohlapno, zato obdrzimo
     * samo posnetke, pri katerih so vse besede iskanja v naslovu, kanalu, opisu ali oznakah.
     */
    fun isci(strezniki: List<String>, beseda: String): List<Jamendo.Skladba> {
        val besede = normaliziraj(beseda).split(' ').filter { it.length >= 2 }
        val q = URLEncoder.encode(beseda, "UTF-8")
        fun ustreza(v: JSONObject): Boolean {
            val besedilo = normaliziraj(listOf(v.optString("name"), v.optJSONObject("channel")?.optString("displayName").orEmpty(),
                v.optJSONObject("account")?.optString("displayName").orEmpty(), v.optString("description"), v.optString("truncatedDescription"),
                v.optJSONArray("tags")?.join(" ").orEmpty()).joinToString(" "))
            return besede.all { besedilo.contains(it) }
        }

        // Vsi indeksi imajo skupni rok; napaka ali zamuda enega ne zadrzi uspesnih odgovorov.
        val opravila = strezniki.distinct().map { s ->
            { seznam(s, "/api/v1/search/videos?search=$q&sort=-views&nsfw=false&count=30&searchTarget=local", ::ustreza) }
        } + listOf<() -> List<Jamendo.Skladba>>({
            seznamIzNaslova("$SEPIA?search=$q&count=30", "sepiasearch.org", ::ustreza)
        })
        val kandidati = vzporedno(opravila).flatten().distinctBy { it.id }
        return razresiVzporedno(kandidati)
    }

    private fun normaliziraj(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Ali na tem naslovu tece PeerTube; vrne ime streznika ali null. */
    fun imeStreznika(streznik: String): String? = try {
        val j = JSONObject(beri("https://$streznik/api/v1/config"))
        j.optJSONObject("instance")?.optString("name")?.ifBlank { streznik }
    } catch (_: Exception) { null }

    /**
     * Datoteka za predvajanje: HLS, sicer najboljsa samostojna datoteka do 1080p. Najprej
     * vprasamo izvorni streznik, nato streznike, kjer smo video nasli - izvor je vcasih nedosegljiv
     * (preverjeno 21. 9. 2026: tinkerbetter.tube), povezani streznik pa pozna iste datoteke.
     */
    fun razresi(v: Jamendo.Skladba, rezervni: List<String> = VGRAJENI): Jamendo.Skladba? {
        if (v.zvok.startsWith("https://")) return v
        for (s in (listOf(v.streznik) + rezervni).filter { it.isNotBlank() }.distinct()) {
            val r = try { razresiPri(s, v) } catch (_: Exception) { null }
            if (r != null) return r
        }
        return null
    }

    private fun razresiPri(streznik: String, v: Jamendo.Skladba): Jamendo.Skladba? {
        val j = JSONObject(beri("https://$streznik/api/v1/videos/${v.id}"))
        val kanal = j.optJSONObject("channel")?.let { "${it.optString("name")}@${it.optString("host")}" }.orEmpty()
        // HLS ima prednost pred datoteko, ker se prilagaja povezavi in ga Media3 predvaja neposredno.
        val seznami = j.optJSONArray("streamingPlaylists")
        val hls = seznami?.let { s -> (0 until s.length()).map { s.getJSONObject(it).optString("playlistUrl") } }
            ?.firstOrNull { it.startsWith("https://") }
        if (hls != null) return v.copy(zvok = hls, mime = MedijskiViri.MIME_HLS, kanal = kanal, streznik = streznik)

        val datoteke = mutableListOf<Pair<Int, String>>()
        val a = j.optJSONArray("files")
        if (a != null) for (i in 0 until a.length()) {
            val f = a.getJSONObject(i)
            val visina = f.optJSONObject("resolution")?.optInt("id") ?: 0
            val url = f.optString("fileUrl")
            if (url.startsWith("https://")) datoteke += visina to url
        }
        val primerne = datoteke.filter { it.first in 1..1080 }
        val url = (primerne.maxByOrNull { it.first } ?: datoteke.minByOrNull { it.first })?.second ?: return null
        return v.copy(zvok = url, mime = "video/mp4", kanal = kanal, streznik = streznik)
    }

    /**
     * Predlogi pod videom: najbolj gledani s tega kanala in posnetki o isti temi (najdaljsa beseda
     * naslova), izmenicno, brez tega videa. Preverjeno 21. 9. 2026 na tilvids.com in framatube.org.
     */
    fun predlogi(v: Jamendo.Skladba): List<Jamendo.Skladba> {
        val s = v.streznik.ifBlank { return emptyList() }
        val kanal = try {
            if (v.kanal.contains('@')) razresiVzporedno(seznam(s, "/api/v1/video-channels/${v.kanal}/videos?sort=-views&count=12&nsfw=false")) else emptyList()
        } catch (_: Exception) { emptyList() }
        val beseda = v.naslov.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 }.maxByOrNull { it.length }
        val tema = beseda?.let { isci(listOf(s), it) }.orEmpty()
        return (0 until maxOf(kanal.size, tema.size)).flatMap { listOfNotNull(kanal.getOrNull(it), tema.getOrNull(it)) }
            .distinctBy { it.id }.filter { it.id != v.id }.take(20)
    }

    private fun seznam(streznik: String, pot: String, ustreza: (JSONObject) -> Boolean = { true }): List<Jamendo.Skladba> =
        seznamIzNaslova("https://$streznik$pot", streznik, ustreza)

    private fun seznamIzNaslova(naslov: String, privzetiStreznik: String,
        ustreza: (JSONObject) -> Boolean = { true }): List<Jamendo.Skladba> {
        val r = JSONObject(beri(naslov)).optJSONArray("data") ?: return emptyList()
        return (0 until r.length()).map { r.getJSONObject(it) }.mapNotNull { v ->
            if (v.optBoolean("nsfw") || !ustreza(v)) return@mapNotNull null
            val stran = v.optString("url")
            val streznik = domaciStreznik(v, stran, privzetiStreznik)
            if (streznik.isBlank()) return@mapNotNull null
            val slika = v.optString("previewPath").ifBlank { v.optString("thumbnailPath") }
            Jamendo.Skladba(v.optString("uuid"), v.optString("name"),
                v.optJSONObject("channel")?.optString("displayName").orEmpty().ifBlank { streznik },
                if (slika.startsWith("/")) "https://$streznik$slika" else slika,
                "", stran, video = true, streznik = streznik)
        }.filter { it.id.isNotBlank() && it.naslov.isNotBlank() }
    }

    private fun domaciStreznik(v: JSONObject, stran: String, privzeti: String): String {
        val surovi = sequenceOf(v.optJSONObject("channel")?.optString("host"),
            v.optJSONObject("account")?.optString("host"), Uri.parse(stran).host, privzeti)
            .filterNotNull().firstOrNull { it.isNotBlank() }.orEmpty()
        return Uri.parse("https://$surovi").host.orEmpty()
    }

    /** Vsak kandidat vprasamo hkrati; kar v petih sekundah nima toka, se ne prikaze. */
    private fun razresiVzporedno(videi: List<Jamendo.Skladba>): List<Jamendo.Skladba> =
        vzporedno(videi.map { v -> { try { razresi(v, emptyList()) } catch (_: Exception) { null } } }).filterNotNull()

    private fun <T : Any> vzporedno(opravila: List<() -> T?>): List<T> {
        if (opravila.isEmpty()) return emptyList()
        val bazen = java.util.concurrent.Executors.newCachedThreadPool()
        val prihodnosti = opravila.map { delo -> bazen.submit<T?> { delo() } }
        bazen.shutdown()
        val rok = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(ROK_MS)
        val rezultati = prihodnosti.mapNotNull { f ->
            try { f.get((rok - System.nanoTime()).coerceAtLeast(1), java.util.concurrent.TimeUnit.NANOSECONDS) }
            catch (_: Exception) { f.cancel(true); null }
        }
        bazen.shutdownNow()
        return rezultati
    }

    private fun beri(naslov: String): String {
        val p = URL(naslov).openConnection() as HttpURLConnection
        p.connectTimeout = ROK_MS.toInt(); p.readTimeout = ROK_MS.toInt()
        p.setRequestProperty("User-Agent", "SafeerOS")
        p.setRequestProperty("Accept", "application/json")
        try {
            if (p.responseCode != 200) throw java.io.IOException("HTTP ${p.responseCode}")
            return p.inputStream.bufferedReader().use { it.readText() }
        } finally { p.disconnect() }
    }
}
