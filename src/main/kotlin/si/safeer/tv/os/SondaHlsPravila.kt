package si.safeer.tv.os


/**
 * Sonda kanala HLS do konca verige (lastnik, 25. 10. 2026: »kanali, ki ne delujejo, niso skriti - na primer vsi ABC
 * kanali«). Prej je sonda ([SondaToka]) prebrala samo odgovor na prvi naslov: glavni seznam HLS je odgovoril 200, zato
 * je bil kanal »ziv«, predvajanje pa je padlo pri razlicici ali kosu. Izmerjeno 25. 10. 2026 (iptv-org, ABC News Live
 * 1-10, abcnews-streams.akamaized.net): glavni seznam 200, vseh 10 razlicic 404. Geografsko omejen tok da 200 za seznam
 * in 403 za kos.
 *
 * Kanal je ziv, ko odgovorijo glavni seznam, ena od razlicic (najvec [NAJVEC_RAZLICIC]), kljuc (ce ga seznam ima) in
 * zadnji kos seznama - to, kar predvajalnik res potrebuje. Ciste odlocitve, brez Androida in omrezja (preizkus:
 * tests/SondaHlsPravilaTest.kt); zahteve dela [SondaToka].
 */
object SondaHlsPravila {
    /** Toliko bajtov seznama najvec preberemo (seznam v zivo z uro kosov ima ~100 KB). */
    const val NAJVEC_BAJTOV = 256 * 1024
    /** Toliko razlicic glavnega seznama najvec preverimo (vse mrtve = kanal mrtev; predvajalnik mrtvo razlicico preskoci). */
    const val NAJVEC_RAZLICIC = 4
    /** Ena sonda kanala (vse zahteve skupaj) najvec toliko casa; potem ne vemo (kanal ostane). */
    const val ROK_SONDE_MS = 15_000L
    /** Toliko bajtov kosa ali kljuca zahtevamo (Range) - dovolj za dokaz, da kos obstaja. */
    const val BAJTOV_KOSA = 2048

    /** Ali je besedilo seznam HLS (#EXTM3U na zacetku; oznaka BOM in presledki ne motijo). */
    fun jeSeznam(besedilo: String): Boolean = besedilo.trimStart('\uFEFF', ' ', '\r', '\n', '\t').startsWith("#EXTM3U")

    /** Glavni seznam (nasteva razlicice) - ne seznam kosov. */
    fun jeGlavni(besedilo: String): Boolean = vrstice(besedilo, false).any { it.startsWith("#EXT-X-STREAM-INF") }

    /**
     * Cele vrstice seznama. [prirezano] = branje se je ustavilo pri [NAJVEC_BAJTOV]: zadnja vrstica je lahko odrezana
     * (napacen naslov bi dal lazen 404), zato jo izpustimo.
     */
    fun vrstice(besedilo: String, prirezano: Boolean): List<String> {
        val v = besedilo.split('\n')
        val cele = if (prirezano && !besedilo.endsWith("\n")) v.dropLast(1) else v
        return cele.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Relativna pot [pot] glede na naslov seznama [osnova] - naslov PO preusmeritvah (kot predvajalnik; s prvotnim naslovom
     * je bil 25. 10. 2026 HTTP 414/404). Absolutna pot ostane; null = ni http(s).
     *
     * Pregled 25. 10. (N2): razresimo tocno kot predvajalnik (Media3 UriUtil.resolve, RFC 3986), ne z java.net.URI (RFC
     * 2396): ta je »?t=1« razresil v mapo namesto v isti seznam, »../« nad korenom pustil v naslovu in pri »|{}« vrgel izjemo
     * - razlicica je dobila lazen 404 ali bila preskocena.
     */
    fun razresi(osnova: String, pot: String): String? {
        val r = razresiUri(osnova, pot.trim())
        return if (r.startsWith("http://") || r.startsWith("https://")) r else null
    }

    private const val SHEMA = 0
    private const val POT = 1
    private const val POIZVEDBA = 2
    private const val DELCEK = 3

    /** RFC 3986 razresitev [ref] glede na [osnova], prenesena iz Media3 UriUtil.resolve (brez Androida). */
    fun razresiUri(osnova: String, ref: String): String {
        val uri = StringBuilder()
        val r = indeksi(ref)
        // Absoluten naslov: to je cilj (pike v poti se pocistijo).
        if (r[SHEMA] != -1) { uri.append(ref); return odstraniPike(uri, r[POT], r[POIZVEDBA]) }
        val b = indeksi(osnova)
        // Prazno ali samo delcek: osnova brez delcka + ref.
        if (r[DELCEK] == 0) return uri.append(osnova, 0, b[DELCEK]).append(ref).toString()
        // Samo poizvedba: osnova do poizvedbe + ref (isti seznam, nova poizvedba).
        if (r[POIZVEDBA] == 0) return uri.append(osnova, 0, b[POIZVEDBA]).append(ref).toString()
        // Ima avtoriteto (//gostitelj): shema osnove + ref.
        if (r[POT] != 0) {
            val meja = b[SHEMA] + 1
            uri.append(osnova, 0, meja).append(ref)
            return odstraniPike(uri, meja + r[POT], meja + r[POIZVEDBA])
        }
        // Pot od korena: shema in avtoriteta osnove + ref.
        if (ref[r[POT]] == '/') {
            uri.append(osnova, 0, b[POT]).append(ref)
            return odstraniPike(uri, b[POT], b[POT] + r[POIZVEDBA])
        }
        // Osnova je samo avtoriteta brez poti: dodamo '/'.
        if (b[SHEMA] + 2 < b[POT] && b[POT] == b[POIZVEDBA]) {
            uri.append(osnova, 0, b[POT]).append('/').append(ref)
            return odstraniPike(uri, b[POT], b[POT] + r[POIZVEDBA] + 1)
        }
        // Osnova do zadnjega '/' + ref.
        val zadnja = osnova.lastIndexOf('/', b[POIZVEDBA] - 1)
        val meja = if (zadnja == -1) b[POT] else zadnja + 1
        uri.append(osnova, 0, meja).append(ref)
        return odstraniPike(uri, b[POT], meja + r[POIZVEDBA])
    }

    /** Odstrani segmenta ».« in »..« v poti med [od] in [do_] (nad koren ne gre). */
    private fun odstraniPike(uri: StringBuilder, od: Int, do_: Int): String {
        var odmik = od
        var meja = do_
        if (odmik >= meja) return uri.toString()
        if (uri[odmik] == '/') odmik++
        var zacetek = odmik
        var i = odmik
        while (i <= meja) {
            val naslednji: Int
            if (i == meja) naslednji = i
            else if (uri[i] == '/') naslednji = i + 1
            else { i++; continue }
            if (i == zacetek + 1 && uri[zacetek] == '.') {
                uri.delete(zacetek, naslednji)
                meja -= naslednji - zacetek
                i = zacetek
            } else if (i == zacetek + 2 && uri[zacetek] == '.' && uri[zacetek + 1] == '.') {
                val prejsnji = uri.lastIndexOf("/", zacetek - 2) + 1
                val odkod = if (prejsnji > odmik) prejsnji else odmik
                uri.delete(odkod, naslednji)
                meja -= naslednji - odkod
                zacetek = prejsnji
                i = prejsnji
            } else {
                i++
                zacetek = i
            }
        }
        return uri.toString()
    }

    /** Indeksi delov naslova: dvopicje sheme (-1 = je ni), zacetek poti, poizvedbe in delcka. */
    private fun indeksi(s: String): IntArray {
        val ind = IntArray(4)
        if (s.isEmpty()) { ind[SHEMA] = -1; return ind }
        val dolzina = s.length
        var delcek = s.indexOf('#')
        if (delcek == -1) delcek = dolzina
        var poizvedba = s.indexOf('?')
        if (poizvedba == -1 || poizvedba > delcek) poizvedba = delcek
        var mejaSheme = s.indexOf('/')
        if (mejaSheme == -1 || mejaSheme > poizvedba) mejaSheme = poizvedba
        var shema = s.indexOf(':')
        if (shema > mejaSheme) shema = -1
        val avtoriteta = shema + 2 < poizvedba && s[shema + 1] == '/' && s[shema + 2] == '/'
        val pot: Int
        if (avtoriteta) {
            var p = s.indexOf('/', shema + 3)
            if (p == -1 || p > poizvedba) p = poizvedba
            pot = p
        } else pot = shema + 1
        ind[SHEMA] = shema
        ind[POT] = pot
        ind[POIZVEDBA] = poizvedba
        ind[DELCEK] = delcek
        return ind
    }

    /** Pasovna sirina iz #EXT-X-STREAM-INF (0, ce je ni). */
    private fun pasovna(vrstica: String): Long =
        Regex("""[:,]BANDWIDTH=(\d+)""").find(vrstica)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    /**
     * Razlicice glavnega seznama za sondo: najprej najvisja pasovna sirina (predvajalnik na hitrem omrezju zacne tam),
     * nato najnizja, nato ostale; vsaka enkrat, najvec [NAJVEC_RAZLICIC].
     */
    fun razlicice(besedilo: String, osnova: String, prirezano: Boolean = false): List<String> {
        val v = vrstice(besedilo, prirezano)
        val vse = ArrayList<Pair<Long, String>>()
        for (i in v.indices) {
            if (!v[i].startsWith("#EXT-X-STREAM-INF")) continue
            val pot = v.getOrNull(i + 1)?.takeIf { !it.startsWith("#") } ?: continue
            val url = razresi(osnova, pot) ?: continue
            vse += pasovna(v[i]) to url
        }
        if (vse.isEmpty()) return emptyList()
        // Pregled 25. 10. (N3): Media3 zacne s PRVO nasteto razlicico - ta je prva; nato najvisja in najnizja, nato ostale.
        val poSirini = vse.sortedByDescending { it.first }
        val red = (listOf(vse.first(), poSirini.first(), poSirini.last()) + vse).map { it.second }.distinct()
        return red.take(NAJVEC_RAZLICIC)
    }

    /** Zadnji kos seznama kosov (v zivo je najnovejsi in zagotovo se obstaja); null = seznam nima kosov. */
    fun zadnjiKos(besedilo: String, osnova: String, prirezano: Boolean = false): String? =
        vrstice(besedilo, prirezano).lastOrNull { !it.startsWith("#") }?.let { razresi(osnova, it) }

    private val RX_URI = Regex("""URI="([^"]*)"""")
    private val RX_METODA = Regex("""METHOD=([A-Za-z0-9-]+)""")
    private val RX_OBLIKA = Regex("""KEYFORMAT="([^"]*)"""")

    /** Zadnji kljuc AES seznama (http/https), ki ga predvajalnik prenese sam; null = ga ni (ali ni prenosljiv). */
    fun kljuc(besedilo: String, osnova: String, prirezano: Boolean = false): String? {
        val k = vrstice(besedilo, prirezano).lastOrNull { it.startsWith("#EXT-X-KEY") || it.startsWith("#EXT-X-SESSION-KEY") } ?: return null
        if (RX_METODA.find(k)?.groupValues?.get(1)?.uppercase() == "NONE" || drm(k)) return null
        val uri = RX_URI.find(k)?.groupValues?.get(1) ?: return null
        return razresi(osnova, uri)
    }

    /**
     * Seznam zahteva DRM (Widevine, PlayReady, FairPlay): nas predvajalnik za kanale v zivo nima licencnega streznika, zato
     * ga ne predvaja. Navaden AES-128 (KEYFORMAT »identity« ali brez) ni DRM.
     */
    fun drm(besedilo: String): Boolean = vrstice(besedilo, false).any { v ->
        (v.startsWith("#EXT-X-KEY") || v.startsWith("#EXT-X-SESSION-KEY")) && RX_METODA.find(v)?.groupValues?.get(1)?.uppercase() != "NONE" &&
            (RX_OBLIKA.find(v)?.groupValues?.get(1)?.lowercase()?.let { it != "identity" } == true || v.contains("skd://"))
    }

    /**
     * Izid preverjenih razlicic: ena ziva - ziv; VSE razlicice ([vseh]) preverjene in dokazano mrtve - mrtev; sicer
     * negotovo, ce je bila katera negotova, drugace ne vemo. Pregled 25. 10. (S1): ce rok sonde ali napaka prekine
     * preverjanje pred zadnjo razlicico, mrtvih ne razglasimo - nepreverjena je lahko ziva. Prazno = ne vemo.
     */
    fun izidRazlicic(izidi: List<OsPravila.IzidSonde>, vseh: Int): OsPravila.IzidSonde = when {
        izidi.isEmpty() -> OsPravila.IzidSonde.NEZNANO
        OsPravila.IzidSonde.ZIV in izidi -> OsPravila.IzidSonde.ZIV
        izidi.size >= vseh && izidi.all { it == OsPravila.IzidSonde.MRTEV } -> OsPravila.IzidSonde.MRTEV
        OsPravila.IzidSonde.NEGOTOVO in izidi -> OsPravila.IzidSonde.NEGOTOVO
        else -> OsPravila.IzidSonde.NEZNANO
    }

    /**
     * Izid sonde po koraku verige: odgovor HTTP [http] na seznam, razlicico, kljuc ali kos. Kos in kljuc sta zadnja: 2xx
     * (tudi 206 za Range) ziv, 4xx kot pri predvajanju mrtev, 416 (Range ni sprejet, kos pa obstaja) ziv.
     */
    fun izidKosa(http: Int): OsPravila.IzidSonde = if (http == 416) OsPravila.IzidSonde.ZIV else OsPravila.izidPoOdgovoru(http)

    /**
     * Pregled 25. 10. (N3): kos razlicice je 404/410 - predvajalnik (Media3, DefaultLoadErrorHandlingPolicy) tako razlicico
     * izloci in vzame naslednjo, zato sonda preveri se naslednjo. 403 (geografsko zaprto) velja za cel kanal.
     */
    fun naslednjaRazlicicaPoKosu(http: Int): Boolean = http == 404 || http == 410

    /**
     * Pregled 25. 10. (N1): predvajalnik kos zahteva brez Range. Streznik, ki Range zavrne (4xx, ki ni 404/410/416), dobi
     * se zahtevo brez Range - sicer bi bil kanal lazno mrtev.
     */
    fun ponoviBrezRange(http: Int): Boolean = http in 400..499 && http != 404 && http != 410 && http != 416
}
