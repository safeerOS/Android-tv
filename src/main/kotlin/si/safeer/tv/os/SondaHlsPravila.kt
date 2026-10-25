package si.safeer.tv.os

import java.net.URI

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
     * je bil 25. 10. 2026 HTTP 414/404). Absolutna pot ostane; null = ni http(s) ali ni veljaven naslov.
     */
    fun razresi(osnova: String, pot: String): String? = try {
        val r = URI(osnova).resolve(URI(pot.replace(" ", "%20"))).toString()
        if (r.startsWith("http://") || r.startsWith("https://")) r else null
    } catch (_: Exception) { null }

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
        val poSirini = vse.sortedByDescending { it.first }
        val red = (listOf(poSirini.first(), poSirini.last()) + poSirini).map { it.second }.distinct()
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
     * Izid preverjenih razlicic: ena ziva - ziv; vse dokazano mrtve - mrtev; sicer negotovo, ce je bila katera negotova,
     * drugace ne vemo. Prazno = ne vemo.
     */
    fun izidRazlicic(izidi: List<OsPravila.IzidSonde>): OsPravila.IzidSonde = when {
        izidi.isEmpty() -> OsPravila.IzidSonde.NEZNANO
        OsPravila.IzidSonde.ZIV in izidi -> OsPravila.IzidSonde.ZIV
        izidi.all { it == OsPravila.IzidSonde.MRTEV } -> OsPravila.IzidSonde.MRTEV
        OsPravila.IzidSonde.NEGOTOVO in izidi -> OsPravila.IzidSonde.NEGOTOVO
        else -> OsPravila.IzidSonde.NEZNANO
    }

    /**
     * Izid sonde po koraku verige: odgovor HTTP [http] na seznam, razlicico, kljuc ali kos. Kos in kljuc sta zadnja: 2xx
     * (tudi 206 za Range) ziv, 4xx kot pri predvajanju mrtev, 416 (Range ni sprejet, kos pa obstaja) ziv.
     */
    fun izidKosa(http: Int): OsPravila.IzidSonde = if (http == 416) OsPravila.IzidSonde.ZIV else OsPravila.izidPoOdgovoru(http)
}
