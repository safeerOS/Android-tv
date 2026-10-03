package si.safeer.tv.os

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Odjemalec javnega protokola dodatkov Stremio (enako kot Safeer za Windows, stremio_dodatki.py):
 * manifest.json -> katalogi (/catalog/{tip}/{id}.json, iskanje /catalog/{tip}/{id}/search=....json)
 * -> meta (/meta/{tip}/{id}.json, epizode serij) -> tokovi (/stream/{tip}/{id}.json).
 *
 * Safeer ne prilaga nobenega dodatka; naslove vnese uporabnik. Kot v Stremiu tokove za izbran film ali
 * epizodo vprasamo VSE uporabnikove dodatke, ki ponujajo vir "stream" za ta tip (katalog ima lahko en
 * dodatek, tokove drug). Predvajamo `url` (http/https); `externalUrl`/`ytId` odpre brskalnik; `infoHash`
 * odpre Magnet povezave. Brez obvodov DRM. Vsi klici so sinhroni - klicatelj jih pozene v niti.
 */
object Stremio {
    private const val PREDPONA = "stremio|"
    private const val CAS = 15_000

    data class Katalog(val dodatek: String, val imeDodatka: String, val tip: String, val id: String, val ime: String,
                       val iskanje: Boolean, val obvezni: List<String>,
                       /** Obvezni dodatni parametri s prvo ponujeno moznostjo (genre=Action), kot jih Stremio pokaze v Discover. */
                       val privzeti: List<Pair<String, String>> = emptyList(),
                       /** Moznosti parametra `genre` (zvrsti ali leta), kot jih katalog oglasi - za filter zvrsti v mrezi Filmi | Serije. */
                       val zvrsti: List<String> = emptyList()) {
        /** Ali katalog brez uporabnikovega filtra sploh vrne vsebino (vsi obvezni parametri imajo privzeto moznost). */
        val prikazen: Boolean get() = obvezni.all { o -> privzeti.any { it.first == o } }
    }

    data class Manifest(val osnova: String, val ime: String, val viri: Set<String>, val tipi: Set<String>,
                        val predpone: List<String>, val katalogi: List<Katalog>,
                        /** Zaseben dodatek ([ZasebniDodatki]): ne gre med police, mreze, iskanje in na druge naprave. */
                        val zaseben: Boolean = false)

    data class Epizoda(val id: String, val ime: String, val sezona: Int, val epizoda: Int,
                       /** Tokovi, ki jih dodatek poda ze v metapodatkih posnetka (`videos[].streams`); prazno = vprasamo /stream. */
                       val tokovi: List<Tok> = emptyList())

    data class Tok(val vrsta: String, val url: String, val ime: String, val opis: String, val dodatek: String,
                   /** Torrent: katera datoteka (fileIdx), -1 = najvecji video. */
                   val datoteka: Int = -1,
                   /** `behaviorHints.proxyHeaders.request`: glave, ki jih tok zahteva (User-Agent, Referer, Origin, Cookie ...). */
                   val glave: Map<String, String> = emptyMap())

    private val manifesti = ConcurrentHashMap<String, Manifest>()

    // Kam sodi vsebina dodatka (Matej, 2. 10. 2026): uporabnik doda dodatek, Safeer ga sam razvrsti v pravi razdelek.
    const val FILM = "film"
    const val SERIJA = "serija"
    /** TV v zivo: kanali, prenosi dogodkov in sporta. */
    const val TV = "tv"
    /** Glasba in drug zvok (albumi, skladbe, seznami, podkasti, zvocne knjige). */
    const val GLASBA = "glasba"
    const val RADIO = "radio"
    /** Drug video (kanali z videi, anime, "other"): razdelek Video. */
    const val VIDEO = "video"

    /** Razred vsebine iz tipa dodatka (`type` kataloga ali vnosa). Neznan tip je video - nic se ne izgubi. */
    fun razred(tip: String): String = when (tip.trim().lowercase()) {
        "movie" -> FILM
        "series" -> SERIJA
        "tv", "live", "livetv", "iptv", "events", "event", "sports", "sport" -> TV
        "radio", "radios" -> RADIO
        "music", "audio", "album", "albums", "song", "songs", "track", "tracks", "playlist", "playlists",
        "podcast", "podcasts", "audiobook", "audiobooks" -> GLASBA
        else -> VIDEO
    }

    private val RX_NAGLASI = Regex("\\p{M}+")
    private fun poenostavi(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(RX_NAGLASI, "")
    /** Ali ime vsebuje vse iskane besede (brez razlikovanja velikih crk in naglasov: "pop tv" najde "POP TV HD"). */
    fun ujema(ime: String, beseda: String): Boolean {
        val i = poenostavi(ime)
        val besede = poenostavi(beseda).split(Regex("\\s+")).filter { it.isNotBlank() }
        return besede.isNotEmpty() && besede.all { it in i }
    }

    private val bazen = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "safeer-stremio").apply { isDaemon = true } }

    fun osnova(naslov: String): String {
        var n = naslov.trim()
        if (n.startsWith("stremio://")) n = "https://" + n.removePrefix("stremio://")
        if (n.endsWith("/manifest.json")) n = n.removeSuffix("/manifest.json")
        return n.trimEnd('/')
    }

    /**
     * Predpomnilnik odgovorov dodatkov (v pomnilniku): uporabnik ne caka dvakrat na isto (Matej, 2. 10. 2026).
     * Katalog 15 min (police razdelka Video in mreza Filmi | Serije berejo iste strani), metapodatki 1 uro,
     * tokovi 3 min (ponoven dotik istega filma, vnaprej nalozeni tokovi ob izbiri kartice z daljincem).
     */
    private val odgovori = ConcurrentHashMap<String, Pair<Long, JSONObject>>()
    private fun veljavnost(url: String): Long = when {
        "/stream/" in url -> 3 * 60_000L
        "/catalog/" in url -> 15 * 60_000L
        "/meta/" in url -> 60 * 60_000L
        else -> 0L
    }

    private fun json(url: String): JSONObject? {
        val velja = veljavnost(url)
        if (velja > 0) odgovori[url]?.let { (cas, o) -> if (System.currentTimeMillis() - cas < velja) return o }
        val o = prenesiJson(url) ?: return null
        // Praznega seznama tokov ne hranimo: dodatek je morda le zacasno brez odgovora.
        val prazniTokovi = "/stream/" in url && (o.optJSONArray("streams")?.length() ?: 0) == 0
        if (velja > 0 && !prazniTokovi) {
            if (odgovori.size > 300) odgovori.clear()
            odgovori[url] = System.currentTimeMillis() to o
        }
        return o
    }

    /** Koda HTTP zadnjega prenosa v tej niti (0 = dodatek ni odgovoril): locimo "tega nimam" (404) od izpada. */
    private val zadnjaKoda = ThreadLocal<Int>()

    /** Dodatek je odgovoril, da vsebine nima (ne: napaka streznika, prijave ali omejitve - to je izpad). */
    internal fun kodaPomeniNima(koda: Int) = koda in 400..499 && koda !in setOf(401, 403, 407, 408, 425, 429)

    private fun prenesiJson(url: String): JSONObject? {
        zadnjaKoda.set(0)
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = CAS; c.readTimeout = CAS
            c.setRequestProperty("User-Agent", "Safeer-Predvajalnik/1.0 (+https://safeer.si)")
            c.setRequestProperty("Accept", "application/json")
            try {
                val koda = c.responseCode
                zadnjaKoda.set(koda)
                if (koda !in 200..299) return null
                JSONObject(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
            } finally { c.disconnect() }
        } catch (_: Exception) { null }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Mapa za predpomnilnik manifestov na disku (nastavi jo aplikacija ob zagonu); brez nje le pomnilnik. */
    @Volatile var mapaPredpomnilnika: java.io.File? = null

    /**
     * Manifest dodatka: z diska (do 24 ur), sicer s spleta. Po hladnem zagonu so katalogi in tokovi tako na voljo
     * brez cakanja na manifest vsakega dodatka posebej; nedosegljiv dodatek uporabi zadnji znani manifest.
     */
    private fun manifestJson(osnova: String): JSONObject? {
        val datoteka = mapaPredpomnilnika?.let { java.io.File(it, "manifest-" + Integer.toHexString(osnova.hashCode()) + ".json") }
        val zDiska = try { datoteka?.takeIf { it.isFile }?.let { JSONObject(it.readText()) } } catch (_: Exception) { null }
        if (zDiska != null && System.currentTimeMillis() - (datoteka?.lastModified() ?: 0L) < 24 * 3600_000L) return zDiska
        val svez = prenesiJson("$osnova/manifest.json")
        if (svez != null) try { datoteka?.parentFile?.mkdirs(); datoteka?.writeText(svez.toString()) } catch (_: Exception) { }
        return svez ?: zDiska
    }

    fun manifest(naslov: String): Manifest? {
        val o = osnova(naslov)
        manifesti[o]?.let { return it }
        val m = manifestJson(o) ?: return null
        val ime = m.optString("name").ifBlank { o }
        val viri = mutableSetOf<String>()
        m.optJSONArray("resources")?.let { a -> for (i in 0 until a.length()) {
            val r = a.opt(i); viri += if (r is JSONObject) r.optString("name") else r.toString() } }
        val tipi = mutableSetOf<String>()
        m.optJSONArray("types")?.let { a -> for (i in 0 until a.length()) tipi += a.optString(i) }
        val predpone = mutableListOf<String>()
        m.optJSONArray("idPrefixes")?.let { a -> for (i in 0 until a.length()) predpone += a.optString(i) }
        val katalogi = mutableListOf<Katalog>()
        m.optJSONArray("catalogs")?.let { a -> for (i in 0 until a.length()) {
            val k = a.optJSONObject(i) ?: continue
            val tip = k.optString("type"); val id = k.optString("id")
            if (tip.isBlank() || id.isBlank()) continue
            val podprti = mutableSetOf<String>(); val obvezni = mutableListOf<String>()
            val privzeti = mutableListOf<Pair<String, String>>()
            val zvrsti = mutableListOf<String>()
            k.optJSONArray("extra")?.let { e -> for (j in 0 until e.length()) {
                val x = e.optJSONObject(j) ?: continue
                val imeX = x.optString("name")
                podprti += imeX
                if (imeX == "genre") x.optJSONArray("options")?.let { o -> for (q in 0 until o.length()) o.optString(q).takeIf { it.isNotBlank() }?.let { zvrsti += it } }
                if (x.optBoolean("isRequired")) {
                    obvezni += imeX
                    // Stremio v Discover obvezni parameter nastavi na prvo moznost (npr. zvrst): enako tu.
                    x.optJSONArray("options")?.optString(0)?.takeIf { it.isNotBlank() }?.let { privzeti += imeX to it }
                } } }
            k.optJSONArray("extraSupported")?.let { e -> for (j in 0 until e.length()) podprti += e.optString(j) }
            k.optJSONArray("extraRequired")?.let { e -> for (j in 0 until e.length()) obvezni += e.optString(j) }
            katalogi += Katalog(o, ime, tip, id, k.optString("name").ifBlank { id }, "search" in podprti, obvezni.distinct(), privzeti, zvrsti)
        } }
        // Zaseben dodatek ([ZasebniDodatki]): tako se oznaci sam, v manifestu (uradno polje protokola).
        val zaseben = m.optJSONObject("behaviorHints")?.optBoolean("adult") == true
        zapomniZasebnost(o, zaseben)
        return Manifest(o, ime, viri, tipi, predpone, katalogi, zaseben).also { manifesti[o] = it }
    }

    // ------------------------------------------------------------------ zasebni dodatki (ZasebniDodatki)

    private const val PREFS_ZASEBNI = "safeer_stremio_zasebni"
    /** Shrambi oznak in premorov (ne Context: predmet zivi ves cas procesa). null = [pripravi] se ni bil klican. */
    @Volatile private var shrambaZasebnih: android.content.SharedPreferences? = null
    @Volatile private var shrambaPremorov: android.content.SharedPreferences? = null
    /** osnova -> ali je zaseben dodatek. Zapis prezivi ponovni zagon, da odlocitev ne caka na omrezje. */
    private val znaniZasebni = ConcurrentHashMap<String, Boolean>()

    /** Vstopne tocke s Contextom (zaslon, storitev, Link) pripravijo predpomnilnik manifestov in znane oznake. */
    fun pripravi(ctx: Context) {
        if (shrambaZasebnih != null) return
        synchronized(znaniZasebni) {
            if (shrambaZasebnih != null) return
            val a = ctx.applicationContext
            if (mapaPredpomnilnika == null) mapaPredpomnilnika = java.io.File(a.cacheDir, "stremio")
            try {
                val p = a.getSharedPreferences(PREFS_ZASEBNI, Context.MODE_PRIVATE)
                p.all.forEach { (k, v) -> if (v is Boolean) znaniZasebni.putIfAbsent(k, v) }
                // Kar smo spoznali pred pripravo (manifest brez Contexta), zapisemo zdaj.
                val e = p.edit(); znaniZasebni.forEach { (k, v) -> e.putBoolean(k, v) }; e.apply()
            } catch (_: Exception) { }
            // Premori dodatkov iz prejsnjega zagona (ponovni zagon ne sme znova sproziti omejitve).
            try {
                val zdaj = System.currentTimeMillis()
                val p = a.getSharedPreferences(PREFS_PREMOR, Context.MODE_PRIVATE)
                val e = p.edit()
                p.all.forEach { (k, v) -> if (v is Long && v > zdaj && v < zdaj + PREMOR_NAJVEC_MS) premorDo.putIfAbsent(k, v) else e.remove(k) }
                e.apply()
                shrambaPremorov = p
            } catch (_: Exception) { }
            shrambaZasebnih = try { a.getSharedPreferences(PREFS_ZASEBNI, Context.MODE_PRIVATE) } catch (_: Exception) { null }
        }
    }

    private fun zapomniZasebnost(osnova: String, da: Boolean) {
        if (znaniZasebni.put(osnova, da) == da) return
        try { shrambaZasebnih?.edit()?.putBoolean(osnova, da)?.apply() } catch (_: Exception) { }
    }

    /** Ali je zaseben dodatek, kolikor ze vemo (brez omrezja); null = njegovega manifesta se nismo videli. */
    fun zasebenZnano(naslov: String): Boolean? = znaniZasebni[osnova(naslov)]

    /** Kot [zasebenZnano], a neznan manifest prenese (klic z delovne niti); null = dodatek ni dosegljiv. */
    fun zaseben(naslov: String): Boolean? = zasebenZnano(naslov) ?: manifest(naslov)?.zaseben

    /** Kartica (film, epizoda, video) iz zasebnega dodatka - brez omrezja. */
    fun jeZasebna(s: Jamendo.Skladba): Boolean = jeEnota(s) && razstavi(s)?.first?.let { znaniZasebni[it] } == true

    /** Manifeste dodatkov, ki jih se ne poznamo, prenese v ozadju (odlocitev pri usklajevanju virov med napravami). */
    fun spoznaj(naslovi: List<String>) {
        naslovi.filter { zasebenZnano(it) == null }.take(8).forEach { n ->
            try { bazen.execute { try { manifest(n) } catch (_: Exception) { } } } catch (_: Exception) { }
        }
    }

    /** Vsi katalogi enega dodatka, ki brez filtra vrnejo vsebino - za izrecno odprt dodatek (Moji viri). */
    fun katalogiDodatka(naslov: String): List<Katalog> = manifest(naslov)?.katalogi.orEmpty().filter { it.prikazen }

    /** Ime dodatka, ce je manifest ze nalozen (brez omrezja - za glavno nit). */
    fun imeIzPredpomnilnika(naslov: String): String? = manifesti[osnova(naslov)]?.ime

    fun jeEnota(s: Jamendo.Skladba) = s.id.startsWith(PREDPONA)
    /** (osnova kataloskega dodatka, tip, id vnosa) iz id-ja kartice. */
    fun razstavi(s: Jamendo.Skladba): Triple<String, String, String>? {
        val d = s.id.removePrefix(PREDPONA).split('|', limit = 3)
        return if (d.size == 3) Triple(d[2].substringBefore('#'), d[0], d[1]) else null
    }
    fun jeSerija(s: Jamendo.Skladba) = razstavi(s)?.second == "series"
    /** Razred enote dodatka (FILM, SERIJA, TV, GLASBA, RADIO, VIDEO). */
    fun razredEnote(s: Jamendo.Skladba): String = razred(razstavi(s)?.second.orEmpty())
    fun jeVZivo(s: Jamendo.Skladba) = jeEnota(s) && razredEnote(s) == TV

    private fun vnos(m: JSONObject, osnova: String, tipKataloga: String = ""): Jamendo.Skladba? {
        val id = m.optString("id").ifBlank { return null }
        val tip = m.optString("type").ifBlank { tipKataloga.ifBlank { "movie" } }
        val leto = m.optString("releaseInfo").ifBlank { m.optString("year") }
        val r = razred(tip)
        // Glasba in radio: pod naslovom je izvajalec (kdo igra), ne letnica - uporabnik mora vedeti, koga pricakovati
        // (Matej, 2. 10. 2026). Dodatki ga podajo razlicno, zato po vrsti: artist/author, igralci, reziser, opis.
        val podnaslov = if (r == GLASBA || r == RADIO) izvajalec(m).ifBlank { leto } else leto
        return Jamendo.Skladba(PREDPONA + tip + "|" + id + "|" + osnova, m.optString("name").ifBlank { id }, podnaslov,
            slikaVnosa(m), "", "$osnova/meta/${enc(tip)}/${enc(id)}.json",
            // Glasba in radio sta zvok (kartica in predvajalnik za zvok), vse ostalo video.
            radio = r == RADIO, video = r != GLASBA && r != RADIO,
            // FILM / SERIJA za oznako na kartici in za filter Filmi | Serije (SpletniVir.vrstaVsebine).
            mediaType = when (tip) { "movie" -> "movie"; "series" -> "tvseries"; else -> "" },
            year = leto.take(4).toIntOrNull() ?: 0,
            // Id IMDb (tt...), kot ga rabi vecina dodatkov: isti film iz vec katalogov/dodatkov je ena kartica.
            imdbId = if (id.startsWith("tt") && id.drop(2).all { it.isDigit() }) id else "",
            genres = (m.optJSONArray("genres") ?: m.optJSONArray("genre"))?.let { g -> (0 until g.length()).mapNotNull { g.optString(it).takeIf { s -> s.isNotBlank() } } } ?: emptyList(),
            // Ocena (IMDb), kadar jo katalog poda: na kartici kot zvezdica, kot pri drugih virih.
            rating = m.optString("imdbRating").toDoubleOrNull() ?: 0.0)
    }

    /** Slika vnosa: plakat, sicer logotip, ozadje ali slicica - katerokoli sliko dodatek poda, raje kot prazno kartico. */
    internal fun slikaVnosa(m: JSONObject): String =
        listOf("poster", "logo", "background", "thumbnail", "image", "icon").firstNotNullOfOrNull { k ->
            m.optString(k).trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }.orEmpty()

    /** Izvajalec glasbenega vnosa, kot ga dodatki podajo: artist/author, prvi igralci, reziser, sicer kratek opis. */
    internal fun izvajalec(m: JSONObject): String {
        fun niz(k: String): String = when (val v = m.opt(k)) {
            is String -> v.trim()
            is org.json.JSONArray -> (0 until minOf(v.length(), 2)).mapNotNull { v.optString(it).trim().takeIf { s -> s.isNotBlank() } }.joinToString(", ")
            else -> ""
        }
        listOf("artist", "artists", "author", "cast", "director").forEach { k -> niz(k).takeIf { it.isNotBlank() }?.let { return it.take(60) } }
        // Opis: samo kratka prva vrstica (npr. "Siddharta" ali "Pop · Slovenija"), dolgega besedila ne kazemo pod naslovom.
        return m.optString("description").lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotBlank() && it.length <= 48 }.orEmpty()
    }

    /** Stran kataloga (`skip` = koliko vnosov preskociti, kot v Stremiu); obvezni parametri s privzeto moznostjo, iskanje kot `search=`. */
    fun katalog(k: Katalog, iskanje: String = "", skip: Int = 0, zvrst: String = ""): List<Jamendo.Skladba> {
        var pot = "${k.dodatek}/catalog/${enc(k.tip)}/${enc(k.id)}"
        val dodatno = (if (iskanje.isNotBlank()) listOf("search" to iskanje) else emptyList()) +
            // Izbrana zvrst (moznost kataloga, npr. "Comedy") nadomesti privzeto moznost parametra genre.
            (if (zvrst.isNotBlank()) listOf("genre" to zvrst) else emptyList()) +
            k.privzeti.filter { it.first != "search" && (zvrst.isBlank() || it.first != "genre") && (iskanje.isBlank() || it.first in k.obvezni) } +
            (if (skip > 0) listOf("skip" to skip.toString()) else emptyList())
        if (dodatno.isNotEmpty()) pot += "/" + dodatno.joinToString("&") { enc(it.first) + "=" + enc(it.second) }
        val d = json("$pot.json") ?: return emptyList()
        val a = d.optJSONArray("metas") ?: d.optJSONArray("metasDetailed") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { m -> vnos(m, k.dodatek, k.tip) } }
    }

    /** Javni katalog metapodatkov Stremia (Cinemeta: filmi in serije po priljubljenosti, epizode) - samo
     *  metapodatki, tokove dajo uporabnikovi dodatki. Isti vir, kot ga Stremio sam kaze v Discover. */
    const val CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"

    /**
     * Uporabnikovi dodatki + javni katalog Cinemeta (kot v Stremiu, kjer je Cinemeta vedno namescena): ko dodas
     * dodatke Stremio, so filmi in serije na voljo, uporabnik ne raziskuje, kateri dodatek je katalog (Matej,
     * 1. 10. 2026). Uporabnikovi katalogi so pred Cinemeto.
     */
    fun zKatalogom(naslovi: List<String>): List<String> {
        if (naslovi.isEmpty()) return naslovi
        val cinemeta = osnova(CINEMETA)
        return if (naslovi.any { osnova(it) == cinemeta }) naslovi else naslovi + CINEMETA
    }

    /** Katalogi razdelka Video (filmi, nato serije, nato drug video: kanali, anime ...); brez tistih, ki brez filtra ne vrnejo nicesar. */
    fun prikazniKatalogi(naslovi: List<String>): List<Katalog> = naslovi.mapNotNull { manifest(it) }.filter { !it.zaseben }
        .flatMap { m -> m.katalogi.filter { it.prikazen && razred(it.tip) in setOf(FILM, SERIJA, VIDEO) } }
        .sortedBy { when (it.tip) { "movie" -> 0; "series" -> 1; else -> 2 } }

    /** Katalogi dodatkov izbranega razreda: TV (razdelek TV v zivo), GLASBA (Glasba), RADIO (Radio). */
    fun katalogiRazreda(naslovi: List<String>, razred: String): List<Katalog> = naslovi.mapNotNull { manifest(it) }.filter { !it.zaseben }
        .flatMap { m -> m.katalogi.filter { it.prikazen && razred(it.tip) == razred } }

    /** Katalogi TV kanalov v zivo iz uporabnikovih dodatkov - gredo v razdelek TV v zivo (Matej, 1. 10. 2026). */
    fun katalogiTv(naslovi: List<String>): List<Katalog> = katalogiRazreda(naslovi, TV)

    /** V kateri razdelek sodi dodatek po svojih katalogih: video (filmi, serije), sicer TV, glasba ali radio. */
    fun glavniRazred(naslov: String): String {
        val razredi = manifest(naslov)?.katalogi.orEmpty().map { razred(it.tip) }.toSet()
        return when {
            razredi.isEmpty() || FILM in razredi || SERIJA in razredi || VIDEO in razredi -> VIDEO
            TV in razredi -> TV
            GLASBA in razredi -> GLASBA
            else -> RADIO
        }
    }

    /**
     * Iskanje po imenu v dodatkih VSEH vrst (filmi, serije, TV v zivo, glasba, radio), vsi katalogi hkrati.
     * Katalog z lastnim iskanjem vprasamo z `search=`; kataloge kanalov, postaj in glasbe, ki iskanja ne
     * podpirajo (vecina dodatkov TV v zivo), preiscemo sami po imenu (stran kataloga je v predpomnilniku).
     */
    fun isci(naslovi: List<String>, beseda: String, tudiZasebni: Boolean = false): List<Jamendo.Skladba> {
        // Zasebni dodatki niso v skupnem iskanju (ZasebniDodatki): isce se v njih samo izrecno ([tudiZasebni]).
        val katalogi = naslovi.mapNotNull { manifest(it) }.filter { tudiZasebni || !it.zaseben }.flatMap { it.katalogi }
        val zIskanjem = katalogi.filter { it.iskanje }.distinctBy { it.dodatek + "|" + it.tip }
        val pokriti = zIskanjem.map { it.dodatek + "|" + it.tip }.toSet()
        val brezIskanja = katalogi.filter { !it.iskanje && it.prikazen && (it.dodatek + "|" + it.tip) !in pokriti &&
            razred(it.tip) in setOf(TV, RADIO, GLASBA) }.take(16)
        val opravila = zIskanjem.map { k -> java.util.concurrent.Callable { katalog(k, beseda).take(20) } } +
            brezIskanja.map { k -> java.util.concurrent.Callable { katalog(k).filter { ujema(it.naslov, beseda) }.take(20) } }
        if (opravila.isEmpty()) return emptyList()
        return bazen.invokeAll(opravila, 10, java.util.concurrent.TimeUnit.SECONDS)
            .flatMap { f -> try { if (f.isCancelled) emptyList() else f.get() } catch (_: Exception) { emptyList() } }
            .distinctBy { it.id }
    }

    /**
     * Posnetki enote (`videos` v metapodatkih): epizode serije, skladbe albuma, videi kanala. Serija: ostevilcene
     * epizode po sezonah (kot do zdaj); ostalo v vrstnem redu dodatka, tudi brez sezone in stevilke.
     */
    fun epizode(s: Jamendo.Skladba): List<Epizoda> {
        val (osnova, tip, id) = razstavi(s) ?: return emptyList()
        val m = json("$osnova/meta/${enc(tip)}/${enc(id)}.json")?.optJSONObject("meta") ?: return emptyList()
        val v = m.optJSONArray("videos") ?: return emptyList()
        val imeDodatka = manifesti[osnova]?.ime.orEmpty()
        val vsi = (0 until v.length()).mapNotNull { i ->
            val x = v.optJSONObject(i) ?: return@mapNotNull null
            val vid = x.optString("id").ifBlank { return@mapNotNull null }
            val tokovi = x.optJSONArray("streams")?.let { a -> (0 until a.length()).mapNotNull { j -> a.optJSONObject(j)?.let { tok(it, imeDodatka) } } }.orEmpty()
            Epizoda(vid, x.optString("title").ifBlank { x.optString("name") }, x.optInt("season"), x.optInt("episode"), tokovi)
        }
        val ostevilcene = vsi.filter { it.sezona > 0 || it.epizoda > 0 }
        return if (tip == "series" && ostevilcene.isNotEmpty()) ostevilcene.sortedWith(compareBy({ it.sezona }, { it.epizoda })) else vsi
    }

    private fun tok(s: JSONObject, imeDodatka: String): Tok? {
        val ime = s.optString("name").ifBlank { imeDodatka }
        val opis = s.optString("title").ifBlank { s.optString("description") }
        // proxyHeaders.request (SDK: behaviorHints): glave, brez katerih streznik toka ne odgovori.
        val glave = LinkedHashMap<String, String>()
        s.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.let { h ->
            for (k in h.keys()) { val v = h.optString(k); if (k.isNotBlank() && v.isNotBlank()) glave[k] = v }
        }
        return when {
            s.optString("url").startsWith("http") ->
                if (jeObvestilo(s, ime, opis)) null else Tok("url", s.optString("url"), ime, opis, imeDodatka, glave = glave)
            // `externalUrl` ni tok, ampak povezava na stran: prosnja za donacijo, vabilo v Discord, "No streams found",
            // trgovina ... Tega uporabniku nikoli ne kazemo (Matej, 2. 10. 2026) - vnos izpustimo.
            s.optString("externalUrl").isNotBlank() -> null
            // Napovednik (YouTube) ni vsebina sama: ne steje kot predvajanje, ponudimo ga le pri rocni izbiri vira.
            s.optString("ytId").isNotBlank() -> Tok("napovednik", "https://www.youtube.com/watch?v=" + s.optString("ytId"), ime, opis, imeDodatka)
            s.optString("infoHash").isNotBlank() -> {
                // Sledilniki iz `sources` ("tracker:udp://..."), da racunalnik hitreje najde vire.
                val viri = s.optJSONArray("sources")
                val tr = (0 until (viri?.length() ?: 0)).mapNotNull { viri?.optString(it) }
                    .filter { it.startsWith("tracker:") }.joinToString("") { "&tr=" + enc(it.removePrefix("tracker:")) }
                val ime2 = s.optJSONObject("behaviorHints")?.optString("filename").orEmpty()
                Tok("torrent", "magnet:?xt=urn:btih:" + s.optString("infoHash") + (if (ime2.isNotBlank()) "&dn=" + enc(ime2) else "") + tr,
                    ime, opis, imeDodatka, s.optInt("fileIdx", -1))
            }
            else -> null
        }
    }

    /** Vnos med tokovi, ki je obvestilo (prosnja za donacijo, vabilo v Discord, "No streams found"): glej [ObvestilaTokov]. */
    internal fun jeObvestilo(s: JSONObject, ime: String, opis: String): Boolean {
        val namigi = s.optJSONObject("behaviorHints")
        val znakiToka = namigi != null &&
            (namigi.has("filename") || namigi.has("videoSize") || namigi.has("videoHash") || namigi.has("proxyHeaders"))
        return ObvestilaTokov.je(s.optString("url"), "$ime $opis", znakiToka)
    }

    /**
     * Ali torrent na tej napravi steje kot predvajanje. [meja]: Long.MAX_VALUE = vsak (pomocnik v Linku ali veliko
     * prostora), 0 = nobeden, sicer najvecja datoteka (bajti), ki gre na prosti prostor - velikost pove opis toka;
     * torrent brez znane velikosti takrat ne steje (ne obljubimo filma, ki ne gre na napravo).
     */
    fun torrentGre(t: Tok, meja: Long): Boolean = when (meja) {
        0L -> false
        Long.MAX_VALUE -> true
        else -> TokIzbira.opisi(t.ime + " " + t.opis).gb.let { it > 0.0 && it * 1024.0 * 1024.0 * 1024.0 <= meja }
    }

    /** Tok, ki ga ta naprava lahko predvaja kot vsebino (ne napovednik). */
    fun jePredvajljiv(t: Tok) = t.vrsta == "url" || t.vrsta == "torrent"

    // ------------------------------------------------------------------ vljudnost do dodatkov (preverjanje v ozadju)

    /**
     * Preverjanje, kaj se da predvajati, poslje dodatku veliko poizvedb, ki jih uporabnik ni izrecno sprozil. Dodatek
     * jih sme imeti za zlorabo (3. 10. 2026: "403 Rate limit exceeded (Scraping/Abuse)" - potem ne dela niti
     * predvajanje). Zato: najvec [ZETONI_NAJVEC] poizvedb takoj, nato [ZETONI_NA_S] na sekundo na dodatek, in premor
     * (15 min, ob ponovitvi dvakrat dlje, do 2 h), ko dodatek odgovori 429 ali 403. Predvajanje ([tokovi]) ne caka.
     * Meja je nizka namenoma: 3. 10. 2026 je dodatek zavrnil napravo ze po ~37 poizvedbah v 15 s, v enem domu pa
     * isti dodatek sprasuje vec naprav z istega naslova. Premor prezivi ponovni zagon ([pripravi]).
     */
    private const val ZETONI_NAJVEC = 6.0
    private const val ZETONI_NA_S = 0.5
    private const val PREMOR_MS = 15 * 60_000L
    private const val PREMOR_NAJVEC_MS = 2 * 3_600_000L
    private class Vedro { var zetoni = ZETONI_NAJVEC; var cas = System.currentTimeMillis() }
    private val vedra = ConcurrentHashMap<String, Vedro>()
    private val premorDo = ConcurrentHashMap<String, Long>()
    private val premorKorak = ConcurrentHashMap<String, Int>()
    private const val PREFS_PREMOR = "safeer_stremio_premor"

    /** Dodatek je odgovoril, da omejuje poizvedbe: v ozadju ga do konca premora ne sprasujemo. */
    fun vPremoru(naslov: String): Boolean = (premorDo[osnova(naslov)] ?: 0L) > System.currentTimeMillis()

    private fun zacniPremor(osnova: String) {
        if ((premorDo[osnova] ?: 0L) > System.currentTimeMillis()) return      // vec hkratnih odgovorov je en premor
        val korak = (premorKorak[osnova] ?: 0).coerceAtMost(3)
        premorKorak[osnova] = korak + 1
        val trajanje = (PREMOR_MS shl korak).coerceAtMost(PREMOR_NAJVEC_MS)
        premorDo[osnova] = System.currentTimeMillis() + trajanje
        try { shrambaPremorov?.edit()?.putLong(osnova, System.currentTimeMillis() + trajanje)?.apply() } catch (_: Exception) { }
        try { android.util.Log.i("SafeerStremio", "dodatek ${try { URL(osnova).host } catch (_: Exception) { "?" }} omejuje poizvedbe: premor ${trajanje / 60_000} min") } catch (_: Throwable) { }
    }

    /** Pocaka na zeton za poizvedbo v ozadju pri dodatku; false = dodatek je v premoru ali je nit prekinjena. */
    private fun zeton(osnova: String): Boolean {
        val v = vedra.getOrPut(osnova) { Vedro() }
        while (true) {
            if ((premorDo[osnova] ?: 0L) > System.currentTimeMillis()) return false
            val cakaj = synchronized(v) {
                val zdaj = System.currentTimeMillis()
                v.zetoni = (v.zetoni + (zdaj - v.cas).coerceAtLeast(0) / 1000.0 * ZETONI_NA_S).coerceAtMost(ZETONI_NAJVEC)
                v.cas = zdaj
                if (v.zetoni >= 1.0) { v.zetoni -= 1.0; 0L } else ((1.0 - v.zetoni) / ZETONI_NA_S * 1000).toLong() + 5
            }
            if (cakaj == 0L) return true
            try { Thread.sleep(cakaj) } catch (_: InterruptedException) { return false }
        }
    }

    /**
     * Ali so vsi dodatki, ki bi jih za ta naslov vprasali po tokovih (tip in predpona id-ja), v premoru - naslova zdaj
     * ni mogoce preveriti. Brez omrezja: uposteva manifeste, ki jih ze poznamo.
     */
    fun vprasaniVPremoru(naslovi: List<String>, tip: String, id: String): Boolean {
        val d = naslovi.mapNotNull { manifesti[osnova(it)] }
            .filter { m -> "stream" in m.viri && (m.tipi.isEmpty() || tip in m.tipi) && (m.predpone.isEmpty() || m.predpone.any { id.startsWith(it) }) }
        return d.isNotEmpty() && d.all { vPremoru(it.osnova) }
    }

    /**
     * Ali vsaj eden od dodatkov za ta naslov ponuja predvajanje: true = da, false = vsi so odgovorili in nobeden nima
     * nicesar, null = ne vemo (kateri ni odgovoril) - takrat nicesar ne sklepamo. [torrent]: kateri torrent tu steje
     * ([torrentGre]).
     */
    fun razpolozljivo(naslovi: List<String>, tip: String, id: String, torrent: Long): Boolean? {
        if (naslovi.isEmpty()) return null
        val manifestiDodatkov = naslovi.map { manifest(it) }
        // Dodatek, ki ga trenutno ne dosezemo (brez omrezja), bi vsebino morda imel: ne sklepamo "ni na voljo".
        var neznano = manifestiDodatkov.any { it == null }
        val vsiDodatki = manifestiDodatkov.filterNotNull()
            .filter { m -> "stream" in m.viri && (m.tipi.isEmpty() || tip in m.tipi) && (m.predpone.isEmpty() || m.predpone.any { id.startsWith(it) }) }
        // Vljudnost do dodatkov: preverjanje tece v ozadju, zato vsak dodatek dobi najvec nekaj poizvedb na sekundo
        // (zeton), dodatka, ki je odgovoril, da poizvedbe omejuje, pa nekaj casa sploh ne sprasujemo (premor).
        val dodatki = vsiDodatki.filter { zeton(it.osnova) }
        if (dodatki.size != vsiDodatki.size) neznano = true
        val niti = dodatki.map { m -> bazen.submit<Boolean?> {
            // Brez odgovora ne vemo nicesar; odgovor "tega nimam" (404) pa je odgovor - dodatek vsebine nima.
            val d = json("${m.osnova}/stream/${enc(tip)}/${enc(id)}.json")
                ?: return@submit run {
                    val koda = zadnjaKoda.get() ?: 0
                    if (koda == 429 || koda == 403) zacniPremor(m.osnova)
                    if (kodaPomeniNima(koda)) false else null
                }
            premorKorak.remove(m.osnova)
            val a = d.optJSONArray("streams") ?: JSONArray()
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { tok(it, m.ime) } }.any { it.vrsta == "url" || (it.vrsta == "torrent" && torrentGre(it, torrent)) }
        } }
        // Prvi dodatek, ki ima tok, zadosca (na pocasne ne cakamo); "ni" velja sele, ko so odgovorili vsi.
        val rok = System.currentTimeMillis() + 15_000
        while (true) {
            if (niti.any { f -> f.isDone && (try { f.get() } catch (_: Exception) { null }) == true }) return true
            if (niti.all { it.isDone } || System.currentTimeMillis() > rok) break
            try { Thread.sleep(40) } catch (_: InterruptedException) { return null }
        }
        if (niti.any { f -> !f.isDone || (try { f.get() } catch (_: Exception) { null }) == null }) neznano = true
        return if (neznano) null else false
    }

    /** Tokovi za film ali epizodo iz vseh dodatkov, ki ponujajo vir "stream" za ta tip in predpono id-ja. */
    fun tokovi(naslovi: List<String>, tip: String, id: String): List<Tok> = naslovi.mapNotNull { manifest(it) }
        .filter { m -> "stream" in m.viri && (m.tipi.isEmpty() || tip in m.tipi) &&
            (m.predpone.isEmpty() || m.predpone.any { id.startsWith(it) }) }
        .flatMap { m ->
            val d = json("${m.osnova}/stream/${enc(tip)}/${enc(id)}.json") ?: return@flatMap emptyList<Tok>()
            val a = d.optJSONArray("streams") ?: JSONArray()
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { tok(it, m.ime) } }
        }
}
