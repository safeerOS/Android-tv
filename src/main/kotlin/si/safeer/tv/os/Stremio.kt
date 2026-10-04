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

    // Kam sodi vsebina dodatka (lastnik, 2. 10. 2026): uporabnik doda dodatek, Safeer ga sam razvrsti v pravi razdelek.
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
     * Predpomnilnik odgovorov dodatkov (v pomnilniku): uporabnik ne caka dvakrat na isto (lastnik, 2. 10. 2026).
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

    /** Glave zadnjega odgovora z omejitvijo (429, 403) v tej niti, imena z malimi crkami: dodatek z njimi pove, koliko naj pocakamo. */
    private val zadnjeGlave = ThreadLocal<Map<String, String>>()

    /** Dodatek je odgovoril, da vsebine nima (ne: napaka streznika, prijave ali omejitve - to je izpad). */
    internal fun kodaPomeniNima(koda: Int) = koda in 400..499 && koda !in setOf(401, 403, 407, 408, 425, 429)

    private fun prenesiJson(url: String): JSONObject? {
        zadnjaKoda.set(0)
        zadnjeGlave.set(emptyMap())
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        // Vsaka poizvedba po tokovih steje v urni proracun dodatka - tudi tista za predvajanje ([TempoDodatka]).
        if ("/stream/" in url) stej(url.substringBefore("/stream/"))
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = CAS; c.readTimeout = CAS
            c.setRequestProperty("User-Agent", "Safeer-Predvajalnik/1.0 (+https://safeer.si)")
            c.setRequestProperty("Accept", "application/json")
            try {
                val koda = c.responseCode
                zadnjaKoda.set(koda)
                if (koda == 429 || koda == 403) zadnjeGlave.set(try {
                    c.headerFields.mapNotNull { (k, v) -> if (k == null) null else v?.firstOrNull()?.let { k.lowercase() to it } }.toMap()
                } catch (_: Exception) { emptyMap() })
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
        zapomniIme(o, ime)
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
                p.all.forEach { (k, v) -> if (v is Boolean) znaniZasebni.putIfAbsent(k, v) else if (v is String && k.startsWith(KLJUC_IMENA)) znanaImena.putIfAbsent(k.removePrefix(KLJUC_IMENA), v) }
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
            // Poizvedbe zadnje ure in kar smo se o meji dodatkov naucili ([TempoDodatka]): ponovni zagon jih ne pozabi.
            try {
                val zdaj = System.currentTimeMillis()
                val p = a.getSharedPreferences(PREFS_TEMPO, Context.MODE_PRIVATE)
                val e = p.edit()
                p.all.forEach { (k, v) ->
                    if (k == KLJUC_OMEJITVE) {
                        // Zadnja omejitev (tu ali drugje v domu): po ponovnem zagonu naprava se vedno ve, da mora z zalogo pocakati.
                        val ob = (v as? String)?.toLongOrNull() ?: 0L
                        if (ob in zdaj - 24 * 3_600_000L..zdaj) { if (ob > premorOb) premorOb = ob } else e.remove(k)
                        return@forEach
                    }
                    val stanje = TempoDodatka.Stanje.izNiza((v as? String)?.substringBefore('|'))
                    val ura = TempoDodatka.Ura.izNiza((v as? String)?.substringAfter('|', ""))
                    // Dodatek, o katerem ni vec kaj vedeti (odstranjen ali dolgo nerabljen), iz shrambe izgine.
                    if (stanje == TempoDodatka.Stanje() && ura.vsota(zdaj) == 0) e.remove(k)
                    else { tempo.putIfAbsent(k, stanje); ure.putIfAbsent(k, ura) }
                }
                e.apply()
                shrambaTempa = p
            } catch (_: Exception) { }
            shrambaZasebnih = try { a.getSharedPreferences(PREFS_ZASEBNI, Context.MODE_PRIVATE) } catch (_: Exception) { null }
        }
    }

    private fun zapomniZasebnost(osnova: String, da: Boolean) {
        if (znaniZasebni.put(osnova, da) == da) return
        try { shrambaZasebnih?.edit()?.putBoolean(osnova, da)?.apply() } catch (_: Exception) { }
    }

    /** osnova -> ime dodatka iz manifesta. Zapis prezivi ponovni zagon: kartica dodatka ne kaze naslova streznika, dokler se manifest ne nalozi znova. */
    private val znanaImena = ConcurrentHashMap<String, String>()
    private const val KLJUC_IMENA = "ime:"

    private fun zapomniIme(osnova: String, ime: String) {
        if (ime.isBlank() || ime.length > 120 || znanaImena.put(osnova, ime) == ime) return
        try { shrambaZasebnih?.edit()?.putString(KLJUC_IMENA + osnova, ime)?.apply() } catch (_: Exception) { }
    }

    /** Ali je zaseben dodatek, kolikor ze vemo (brez omrezja); null = njegovega manifesta se nismo videli. */
    fun zasebenZnano(naslov: String): Boolean? = znaniZasebni[osnova(naslov)]

    /** Kot [zasebenZnano], a neznan manifest prenese (klic z delovne niti); null = dodatek ni dosegljiv. */
    fun zaseben(naslov: String): Boolean? = zasebenZnano(naslov) ?: manifest(naslov)?.zaseben

    /** Kartica (film, epizoda, video) iz zasebnega dodatka - brez omrezja. */
    fun jeZasebna(s: Jamendo.Skladba): Boolean =
        (jeEnota(s) && razstavi(s)?.first?.let { znaniZasebni[it] } == true) || KnjiznicaKroga.jeZasebenPrenos(s.id)

    /** Manifeste dodatkov, ki jih se ne poznamo, prenese v ozadju (odlocitev pri usklajevanju virov med napravami). */
    fun spoznaj(naslovi: List<String>) {
        naslovi.filter { zasebenZnano(it) == null }.take(8).forEach { n ->
            try { bazen.execute { try { manifest(n) } catch (_: Exception) { } } } catch (_: Exception) { }
        }
    }

    /** Vsi katalogi enega dodatka, ki brez filtra vrnejo vsebino - za izrecno odprt dodatek (Moji viri). */
    fun katalogiDodatka(naslov: String): List<Katalog> = manifest(naslov)?.katalogi.orEmpty().filter { it.prikazen }

    /** Ime dodatka, ce je manifest ze nalozen (brez omrezja - za glavno nit). */
    fun imeIzPredpomnilnika(naslov: String): String? = osnova(naslov).let { o -> manifesti[o]?.ime ?: znanaImena[o] }

    fun jeEnota(s: Jamendo.Skladba) = s.id.startsWith(PREDPONA)
    /** (osnova kataloskega dodatka, tip, id vnosa) iz id-ja kartice. */
    fun razstavi(s: Jamendo.Skladba): Triple<String, String, String>? {
        val d = s.id.removePrefix(PREDPONA).split('|', limit = 3)
        return if (d.size == 3) Triple(d[2].substringBefore('#'), d[0], d[1]) else null
    }
    /**
     * Enota dodatka, ki ga uporabnik nima vec (izbrisal ga je tu ali na drugi napravi v Linku): ne da se predvajati,
     * zato je ne kazemo v »Nadaljuj gledanje« in »Nazadnje predvajano« (lastnik: kar se ne da predvajati, ne kazemo).
     * Javni katalog Cinemeta je vedno namescen. Zapis ostane - ce dodatek vrne, se naslov vrne z njim.
     */
    fun brezDodatka(s: Jamendo.Skladba, nasloviDodatkov: Collection<String>): Boolean {
        if (!jeEnota(s)) return false
        val dodatek = razstavi(s)?.first ?: return false
        return dodatek != osnova(CINEMETA) && nasloviDodatkov.none { osnova(it) == dodatek }
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
        // (lastnik, 2. 10. 2026). Dodatki ga podajo razlicno, zato po vrsti: artist/author, igralci, reziser, opis.
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
     * dodatke Stremio, so filmi in serije na voljo, uporabnik ne raziskuje, kateri dodatek je katalog (lastnik,
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

    /** Katalogi TV kanalov v zivo iz uporabnikovih dodatkov - gredo v razdelek TV v zivo (lastnik, 1. 10. 2026). */
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
        return epizodeZa(osnova, tip, id)
    }

    /**
     * Prva epizoda serije z javnim id-jem (IMDb) po javnem katalogu: po njej preverjevalec doma preveri serijo kot
     * celoto, kadar ga vprasa druga naprava (ta poslje samo kljuc, ne svoje kartice). null = katalog serije ne pozna.
     */
    fun prvaEpizoda(id: String): String? =
        (try { epizodeZa(osnova(CINEMETA), "series", id) } catch (_: Exception) { emptyList() }).firstOrNull { it.sezona >= 1 }?.id

    private fun epizodeZa(osnova: String, tip: String, id: String): List<Epizoda> {
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
            // trgovina ... Tega uporabniku nikoli ne kazemo (lastnik, 2. 10. 2026) - vnos izpustimo.
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
     * Se nizja od 3. 10. 2026 zvecer: ze en sam telefon je s 6 + 0,5/s (okoli 28 poizvedb v 45 s) sprozil omejitev v
     * manj kot minuti po odprtju Medijskega centra - potem dodatek ni dal tokov niti za film, ki ga je uporabnik izbral.
     * Zdaj 4 takoj in nato ena na 8 s: polica se preveri pocasneje (izid se hrani ure), predvajanje pa dela.
     * 4. 10. 2026: tudi to ni dovolj - ena sama naprava je po daljsem brskanju spet sprozila omejitev. Dodatek steje
     * tudi vsoto v daljsem casu, zato ima vsak dodatek se urni proracun, ki se uci ([TempoDodatka], [proracun]).
     */
    private const val ZETONI_NAJVEC = 4.0
    private const val ZETONI_NA_S = 0.125
    private const val PREMOR_MS = 15 * 60_000L
    private const val PREMOR_NAJVEC_MS = 2 * 3_600_000L
    private class Vedro { var zetoni = ZETONI_NAJVEC; var cas = System.currentTimeMillis() }
    private val vedra = ConcurrentHashMap<String, Vedro>()
    private val premorDo = ConcurrentHashMap<String, Long>()
    private val premorKorak = ConcurrentHashMap<String, Int>()
    private const val PREFS_PREMOR = "safeer_stremio_premor"

    // Urni proracun dodatka ([TempoDodatka]): poizvedbe zadnje ure in meja za preverjanje v ozadju, oboje po dodatku.
    private const val PREFS_TEMPO = "safeer_stremio_tempo"
    private val ure = ConcurrentHashMap<String, TempoDodatka.Ura>()
    private val tempo = ConcurrentHashMap<String, TempoDodatka.Stanje>()
    private val tempoShranjenOb = ConcurrentHashMap<String, Long>()
    /** Dodatki, katerih premor je nas lasten (porabljen urni proracun) in ne omejitev dodatka. */
    private val premorProracuna: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val zaklepPremora = Any()
    @Volatile private var shrambaTempa: android.content.SharedPreferences? = null

    /** Kljuc v shrambi proracuna, ki ni naslov dodatka: kdaj je dodatek dom nazadnje omejil ([premorOb]). */
    private const val KLJUC_OMEJITVE = "#omejitev"

    private fun zapomniOmejitev(zdaj: Long) {
        premorOb = zdaj
        try { shrambaTempa?.edit()?.putString(KLJUC_OMEJITVE, zdaj.toString())?.apply() } catch (_: Exception) { }
    }

    private fun gostitelj(osnova: String) = try { URL(osnova).host } catch (_: Exception) { "?" }

    private fun ura(osnova: String) = ure.getOrPut(osnova) { TempoDodatka.Ura() }

    /** Meja dodatka; vsak dan brez omejitve se nekoliko vrne proti privzeti. */
    private fun stanjeTempa(osnova: String): TempoDodatka.Stanje {
        val s = tempo[osnova] ?: TempoDodatka.Stanje()
        val o = TempoDodatka.okrevaj(s, System.currentTimeMillis())
        if (o != s) { tempo[osnova] = o; shraniTempo(osnova, takoj = true) }
        return o
    }

    private fun stej(osnova: String) {
        ura(osnova).dodaj(System.currentTimeMillis())
        shraniTempo(osnova, takoj = false)
    }

    /** Stevec se spreminja z vsako poizvedbo: v shrambo gre najvec na pol minute, sprememba meje pa takoj. */
    private fun shraniTempo(osnova: String, takoj: Boolean) {
        val p = shrambaTempa ?: return
        val zdaj = System.currentTimeMillis()
        if (!takoj && zdaj - (tempoShranjenOb[osnova] ?: 0L) in 0 until 30_000L) return
        tempoShranjenOb[osnova] = zdaj
        try { p.edit().putString(osnova, (tempo[osnova] ?: TempoDodatka.Stanje()).vNiz() + "|" + ura(osnova).vNiz()).apply() } catch (_: Exception) { }
    }

    /**
     * Ali urni proracun dodatka se dovoli poizvedbo v ozadju. Ce ne, zacne premor, dokler ga ni spet nekaj: seznami
     * kazejo ze preverjene naslove, ostale naprave v domu pocakajo ([premori]), predvajanje pa dela naprej.
     */
    private fun proracun(osnova: String): Boolean {
        val zdaj = System.currentTimeMillis()
        val s = stanjeTempa(osnova)
        val u = ura(osnova)
        val vUri = u.vsota(zdaj)
        if (TempoDodatka.smeVOzadju(s, vUri)) return true
        synchronized(zaklepPremora) {
            if ((premorDo[osnova] ?: 0L) > zdaj) return false
            val trajanje = TempoDodatka.cakajNaProracun(s, u, zdaj)
            premorProracuna.add(osnova)
            premorDo[osnova] = zdaj + trajanje
            try { shrambaPremorov?.edit()?.putLong(osnova, zdaj + trajanje)?.apply() } catch (_: Exception) { }
            try { android.util.Log.i("SafeerStremio", "dodatek ${gostitelj(osnova)}: urni proracun je porabljen ($vUri od ${s.naUro}), preverjanje v ozadju pocaka ${trajanje / 60_000} min") } catch (_: Throwable) { }
        }
        return false
    }

    /** Ali urni proracun dodatkov, ki dajejo tokove, se dovoli delo na zalogo (zadnje na vrsti). Nic ne porabi. */
    fun smeNaZalogo(naslovi: List<String>): Boolean {
        val zdaj = System.currentTimeMillis()
        return naslovi.all { n ->
            val o = osnova(n)
            manifesti[o]?.let { "stream" in it.viri } == false || TempoDodatka.smeNaZalogo(stanjeTempa(o), ura(o).vsota(zdaj))
        }
    }

    /** Najbolj obremenjen dodatek: »poizvedb zadnje ure/meja na uro« - za dnevnik in meritve (brez imen). */
    fun opisProracuna(): String {
        val zdaj = System.currentTimeMillis()
        return ure.entries.maxByOrNull { it.value.vsota(zdaj) }?.let { "${it.value.vsota(zdaj)}/${stanjeTempa(it.key).naUro}" } ?: "0"
    }

    /** Dodatek je odgovoril, da omejuje poizvedbe: v ozadju ga do konca premora ne sprasujemo. */
    fun vPremoru(naslov: String): Boolean = (premorDo[osnova(naslov)] ?: 0L) > System.currentTimeMillis()

    /** Koliko poizvedb v ozadju (preverjanje, kaj se da predvajati) je ta naprava poslala dodatkom od zagona - za dnevnik in meritve. */
    val poizvedbVOzadju = java.util.concurrent.atomic.AtomicInteger(0)

    /** Kdaj (System.currentTimeMillis) je kateri dodatek nazadnje omejil poizvedbe - tu ali na drugi napravi v Linku. */
    @Volatile var premorOb = 0L
        private set

    /**
     * Kdaj je uporabnik nazadnje zahteval tokove za predvajanje ([tokovi]). Delo na zalogo (preverjevalec doma) po
     * tem nekaj casa pocaka: poizvedbe doma so najprej za tistega, ki zdaj nekaj gleda.
     */
    @Volatile var uporabnikOb = 0L
        private set

    /**
     * Koliko poizvedb v ozadju dodatki ta hip se dovolijo (najmanjse vedro med dodatki, ki dajejo tokove); 0, ce je
     * kateri v premoru. Nic ne porabi - preverjevalec doma po tem presodi, ali sme delati na zalogo.
     */
    fun zetonov(naslovi: List<String>): Double {
        val zdaj = System.currentTimeMillis()
        var najmanj = ZETONI_NAJVEC
        for (n in naslovi) {
            val o = osnova(n)
            if (manifesti[o]?.let { "stream" in it.viri } == false) continue
            if ((premorDo[o] ?: 0L) > zdaj) return 0.0
            val v = vedra[o] ?: continue
            val z = synchronized(v) { (v.zetoni + (zdaj - v.cas).coerceAtLeast(0) / 1000.0 * ZETONI_NA_S).coerceAtMost(ZETONI_NAJVEC) }
            if (z < najmanj) najmanj = z
        }
        return najmanj
    }

    /**
     * Premori, ki se tecejo (osnova dodatka -> koliko ms se). Dodatek omejuje po domacem naslovu, ne po napravi, zato
     * si jih naprave v Linku povedo ([SeznamiSink]): ko dodatek omeji eno, pocakajo vse.
     */
    fun premori(): Map<String, Long> {
        val zdaj = System.currentTimeMillis()
        return premorDo.filterValues { it > zdaj }.mapValues { it.value - zdaj }
    }

    /** Dodatek je omejil drugo napravo v istem domu: tudi ta ga v ozadju ne sprasuje, dokler premor ne mine. */
    fun prevzemiPremor(naslov: String, seMs: Long) {
        if (seMs <= 0L) return
        val o = osnova(naslov)
        val zdaj = System.currentTimeMillis()
        val konec = zdaj + seMs.coerceAtMost(PREMOR_NAJVEC_MS)
        // Minuta razlike ni nov premor (naprave si ga povedo veckrat).
        if ((premorDo[o] ?: 0L) >= konec - 60_000L) return
        premorDo[o] = konec
        zapomniOmejitev(zdaj)
        try { shrambaPremorov?.edit()?.putLong(o, konec)?.apply() } catch (_: Exception) { }
        try { android.util.Log.i("SafeerStremio", "dodatek ${try { URL(o).host } catch (_: Exception) { "?" }} omejuje poizvedbe (sporocila naprava v Linku): premor ${(konec - zdaj) / 60_000} min") } catch (_: Throwable) { }
    }

    /** Dodatek je odgovoril, da omejuje poizvedbe (429, 403). Klic iz niti, ki je odgovor prejela (koda in glave). */
    private fun zacniPremor(osnova: String) {
        val koda = zadnjaKoda.get() ?: 0
        val glave = zadnjeGlave.get().orEmpty()
        synchronized(zaklepPremora) {
            val zdaj = System.currentTimeMillis()
            // Vec hkratnih odgovorov je en premor; nas lasten premor (porabljen proracun) pa omejitve dodatka ne skrije.
            val lasten = premorProracuna.remove(osnova)
            if ((premorDo[osnova] ?: 0L) > zdaj && !lasten) return
            val korak = (premorKorak[osnova] ?: 0).coerceAtMost(3)
            premorKorak[osnova] = korak + 1
            // Ce dodatek pove, koliko naj pocakamo, velja njegova beseda; sicer 15 min in ob ponovitvi dvakrat dlje.
            val trajanje = TempoDodatka.cakajPoGlavah(glave, zdaj) ?: (PREMOR_MS shl korak).coerceAtMost(PREMOR_NAJVEC_MS)
            premorDo[osnova] = zdaj + trajanje
            zapomniOmejitev(zdaj)
            // Meja je nizja, kot smo mislili: nova je polovica tega, kar je naprava v zadnji uri res poslala.
            val poslanih = ura(osnova).vsota(zdaj)
            val novo = TempoDodatka.poOmejitvi(stanjeTempa(osnova), poslanih, zdaj)
            tempo[osnova] = novo
            shraniTempo(osnova, takoj = true)
            try { shrambaPremorov?.edit()?.putLong(osnova, zdaj + trajanje)?.apply() } catch (_: Exception) { }
            try {
                val opis = TempoDodatka.opisGlav(glave).let { if (it.isEmpty()) "" else "; $it" }
                android.util.Log.i("SafeerStremio", "dodatek ${gostitelj(osnova)} omejuje poizvedbe: premor ${trajanje / 60_000} min (koda $koda, v zadnji uri $poslanih poizvedb, nova meja ${novo.naUro} na uro$opis)")
            } catch (_: Throwable) { }
        }
    }

    /**
     * Pocaka na zeton za poizvedbo v ozadju pri dodatku; false = dodatek je v premoru, nit je prekinjena ali vprasanje
     * ne velja vec ([seVelja]: uporabnik je odsel z zaslona) - takrat zetona ne vzame.
     */
    private fun zeton(osnova: String, seVelja: () -> Boolean = { true }): Boolean {
        val v = vedra.getOrPut(osnova) { Vedro() }
        while (true) {
            if ((premorDo[osnova] ?: 0L) > System.currentTimeMillis() || !seVelja()) return false
            if (!proracun(osnova)) return false
            val cakaj = synchronized(v) {
                val zdaj = System.currentTimeMillis()
                v.zetoni = (v.zetoni + (zdaj - v.cas).coerceAtLeast(0) / 1000.0 * ZETONI_NA_S).coerceAtMost(ZETONI_NAJVEC)
                v.cas = zdaj
                if (v.zetoni >= 1.0) { v.zetoni -= 1.0; 0L } else ((1.0 - v.zetoni) / ZETONI_NA_S * 1000).toLong() + 5
            }
            if (cakaj == 0L) return true
            // Po kosih: cakajoce vprasanje, ki ne velja vec, odneha v sekundi (in ne sele, ko bi prislo na vrsto).
            try { Thread.sleep(minOf(cakaj, 1_000L)) } catch (_: InterruptedException) { return false }
        }
    }

    /** Poizvedba ni bila poslana (uporabnik je medtem odsel): zeton gre nazaj v vedro. */
    private fun vrniZeton(osnova: String) {
        val v = vedra[osnova] ?: return
        synchronized(v) { v.zetoni = (v.zetoni + 1.0).coerceAtMost(ZETONI_NAJVEC) }
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

    /** Ali dodatek daje tokove (vir "stream"); null, dokler njegovega manifesta ne poznamo. Brez omrezja. */
    fun dajeTokove(naslov: String): Boolean? = manifesti[osnova(naslov)]?.let { "stream" in it.viri }

    /** Kaj tok zahteva od naprave ([RazpolozljivostPravila.potreba]): 0 = neposreden, velikost torrenta ali »vsak torrent«. */
    private fun potreba(t: Tok): Long? = RazpolozljivostPravila.potreba(t.vrsta == "url", t.vrsta == "torrent",
        if (t.vrsta == "torrent") TokIzbira.opisi(t.ime + " " + t.opis).gb else 0.0)

    /**
     * Kaj dodatki ponujajo za ta naslov, kot meji glede na to, kaj naprava zmore ([RazpolozljivostPravila.Izid]) - isti
     * odgovor zato velja za vsako napravo in vsak nacin. null = ne vemo (kateri dodatek ni odgovoril in nobeden nima
     * nicesar) - takrat nicesar ne sklepamo. [torrent]: kateri torrent steje na tej napravi ([torrentGre]); prvi
     * dodatek s tokom, ki ga ta naprava zmore, zadosca. [seVelja]: preverjanje v ozadju velja le, dokler uporabnik
     * gleda seznam - ko ne velja vec, dodatka ne vprasamo in vrnemo null (klicatelj iz tega ne sklepa nicesar).
     */
    fun razpolozljivo(naslovi: List<String>, tip: String, id: String, torrent: Long, seVelja: () -> Boolean = { true }): RazpolozljivostPravila.Izid? {
        if (naslovi.isEmpty() || !seVelja()) return null
        val manifestiDodatkov = naslovi.map { manifest(it) }
        // Dodatek, ki ga trenutno ne dosezemo (brez omrezja), bi vsebino morda imel: ne sklepamo "ni na voljo".
        var neznano = manifestiDodatkov.any { it == null }
        val vsiDodatki = manifestiDodatkov.filterNotNull()
            .filter { m -> "stream" in m.viri && (m.tipi.isEmpty() || tip in m.tipi) && (m.predpone.isEmpty() || m.predpone.any { id.startsWith(it) }) }
        // Vljudnost do dodatkov: preverjanje tece v ozadju, zato vsak dodatek dobi najvec nekaj poizvedb na sekundo
        // (zeton), dodatka, ki je odgovoril, da poizvedbe omejuje, pa nekaj casa sploh ne sprasujemo (premor).
        val dodatki = vsiDodatki.filter { zeton(it.osnova, seVelja) }
        // Med cakanjem na zeton je uporabnik odsel (zaprl seznam, zacel film, Domov): ne vprasamo nicesar - poizvedbe
        // doma so za tistega, ki zdaj nekaj gleda ali isce. Neporabljeni zetoni gredo nazaj.
        if (!seVelja()) { dodatki.forEach { vrniZeton(it.osnova) }; return null }
        if (dodatki.size != vsiDodatki.size) neznano = true
        // Vsak dodatek vrne potrebe svojih tokov (prazno = odgovoril je, a nima nicesar); null = ni odgovoril.
        val niti = dodatki.map { m -> bazen.submit<List<Long>?> {
            poizvedbVOzadju.incrementAndGet()
            // Brez odgovora ne vemo nicesar; odgovor "tega nimam" (404) pa je odgovor - dodatek vsebine nima.
            val d = json("${m.osnova}/stream/${enc(tip)}/${enc(id)}.json")
                ?: return@submit run {
                    val koda = zadnjaKoda.get() ?: 0
                    if (koda == 429 || koda == 403) zacniPremor(m.osnova)
                    if (kodaPomeniNima(koda)) emptyList() else null
                }
            premorKorak.remove(m.osnova)
            val a = d.optJSONArray("streams") ?: JSONArray()
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { tok(it, m.ime) } }.mapNotNull { potreba(it) }
        } }
        fun odgovori() = niti.map { f -> if (f.isDone) (try { f.get() } catch (_: Exception) { null }) else null }
        // Prvi dodatek s tokom, ki ga ta naprava zmore, zadosca (na pocasne ne cakamo); "ni" velja sele, ko so odgovorili vsi.
        val rok = System.currentTimeMillis() + 15_000
        while (true) {
            val o = odgovori()
            val potrebe = o.filterNotNull().flatten()
            if (potrebe.any { it <= torrent }) return RazpolozljivostPravila.izTokov(potrebe, vsiOdgovorili = !neznano && o.none { it == null })
            if (niti.all { it.isDone } || System.currentTimeMillis() > rok) break
            try { Thread.sleep(40) } catch (_: InterruptedException) { return null }
        }
        val o = odgovori()
        return RazpolozljivostPravila.izTokov(o.filterNotNull().flatten(), vsiOdgovorili = !neznano && o.none { it == null })
    }

    /** Tokovi za film ali epizodo iz vseh dodatkov, ki ponujajo vir "stream" za ta tip in predpono id-ja. */
    fun tokovi(naslovi: List<String>, tip: String, id: String): List<Tok> = naslovi.also { uporabnikOb = System.currentTimeMillis() }
        // Dodatek, ki ga ne dosezemo (manifesta ni), bi vsebino morda imel: to je izpad, ne "ni na voljo".
        .mapNotNull { manifest(it) ?: run { zadnjiIzpad.set(System.currentTimeMillis()); null } }
        .filter { m -> "stream" in m.viri && (m.tipi.isEmpty() || tip in m.tipi) &&
            (m.predpone.isEmpty() || m.predpone.any { id.startsWith(it) }) }
        .flatMap { m ->
            val naslov = "${m.osnova}/stream/${enc(tip)}/${enc(id)}.json"
            var d = json(naslov)
            if (d == null && !kodaPomeniNima(zadnjaKoda.get() ?: 0)) {
                // Dodatek ni odgovoril (omejuje poizvedbe, napaka streznika, omrezje). Uporabnik caka na film: preverjanje
                // v ozadju ustavimo (premor) in poskusimo se enkrat; ce spet nic, to zabelezimo kot izpad.
                val koda = zadnjaKoda.get() ?: 0
                if (koda == 429 || koda == 403) zacniPremor(m.osnova)
                try { Thread.sleep(1_200) } catch (_: InterruptedException) { return@flatMap emptyList<Tok>() }
                d = json(naslov)
                if (d == null && !kodaPomeniNima(zadnjaKoda.get() ?: 0)) {
                    zadnjiIzpad.set(System.currentTimeMillis())
                    try { android.util.Log.i("SafeerStremio", "dodatek ${try { URL(m.osnova).host } catch (_: Exception) { "?" }} ni dal tokov: koda ${zadnjaKoda.get() ?: 0}") } catch (_: Throwable) { }
                }
            }
            val a = d?.optJSONArray("streams") ?: JSONArray()
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { tok(it, m.ime) } }
        }

    /**
     * Kdaj (System.currentTimeMillis) je poizvedba po tokovih za PREDVAJANJE nazadnje ostala brez odgovora dodatka
     * (omejitev poizvedb, napaka streznika, omrezje). Prazen seznam tokov takrat ne pomeni "vsebine ni": uporabniku
     * povemo, da dodatek ne odgovarja, naslov pa ostane na zaslonu.
     */
    val zadnjiIzpad = java.util.concurrent.atomic.AtomicLong(0L)

    /** Ali je zadnja poizvedba po tokovih (v zadnje pol minute) ostala brez odgovora dodatka. */
    fun dodatekNiOdgovoril(): Boolean = System.currentTimeMillis() - zadnjiIzpad.get() < 30_000
}
