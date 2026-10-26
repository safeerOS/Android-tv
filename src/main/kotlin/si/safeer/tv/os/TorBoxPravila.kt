package si.safeer.tv.os

/**
 * Naloga 119: TorBox kot vir torrentov (https://api-docs.torbox.app, prebrano 24. 10. 2026). Zakaj (lastnik): za majhno ceno
 * TorBox uporabniku da zasebnost, ki je pri torrentih sami ne moremo zagotoviti (naprava ni v roju, njen IP ni viden), in
 * hitrejsi, stabilnejsi tok s streznika. Ciste odlocitve brez Androida in omrezja (preizkus: tests/TorBoxPravilaTest.kt).
 *
 * Iz dokumentacije:
 * - osnova `https://api.torbox.app/v1/api`, odgovor {success, error, detail, data};
 * - kljuc: glava `Authorization: Bearer <kljuc>` (checkcached, createtorrent, mylist); SAMO requestdl zahteva kljuc kot
 *   parameter `token` - tak naslov se nikoli ne zapise v dnevnik in ne gre predvajalniku (ta dobi vrnjeno povezavo CDN);
 * - createtorrent: 60 NEPREDPOMNJENIH na uro na kljuc (in 300/min za vse) - steje [MejaNovih];
 * - requestdl odpre povezavo za 3 ure.
 */
object TorBoxPravila {
    const val OSNOVA = "https://api.torbox.app/v1/api/torrents"

    /** Najvec novih (nepredpomnjenih) torrentov v eni uri na kljuc (TorBox, createtorrent). */
    const val NAJVEC_NOVIH_NA_URO = 60
    const val URA_MS = 3_600_000L

    /**
     * Kljuc, kot ga uporabnik prilepi: brez presledkov; dolzina 20-128, samo crke, stevke, '-' in '_' (TorBox kljuc je
     * UUID). Kar ni tako, ni kljuc (npr. prilepljena cela vrstica ali naslov) - null.
     */
    fun ocistiKljuc(vnos: String): String? {
        val k = vnos.trim()
        return k.takeIf { it.length in 20..128 && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_' } }
    }

    fun glavaAvtorizacije(kljuc: String): String = "Bearer $kljuc"

    private val HEKS = Regex("^[0-9a-fA-F]{40}$")
    private val BASE32 = Regex("^[A-Za-z2-7]{32}$")

    /** Hash (btih) iz magnetne povezave kot 40 malih heksadecimalnih znakov; base32 (32 znakov) se pretvori. Sicer null. */
    fun hashIzMagneta(magnet: String): String? {
        if (!magnet.trim().startsWith("magnet:?", ignoreCase = true)) return null
        val m = Regex("(?i)[?&]xt=urn:btih:([^&]+)").find(magnet) ?: return null
        val v = m.groupValues[1].trim()
        return when {
            HEKS.matches(v) -> v.lowercase()
            BASE32.matches(v) -> base32VHeks(v.uppercase())
            else -> null
        }
    }

    private fun base32VHeks(s: String): String {
        val abeceda = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder()
        var zbirka = 0L
        var bitov = 0
        for (c in s) {
            zbirka = (zbirka shl 5) or abeceda.indexOf(c).toLong()
            bitov += 5
            while (bitov >= 8) {
                bitov -= 8
                sb.append("%02x".format(((zbirka shr bitov) and 0xFF).toInt()))
            }
        }
        return sb.toString()
    }

    fun naslovPredpomnjeno(hash: String): String = "$OSNOVA/checkcached?hash=$hash&format=object&list_files=true"
    /** Naloga 130: vec hashev naenkrat (vejica, najvec 100), brez seznama datotek - samo kateri so predpomnjeni. */
    fun naslovPredpomnjenihVec(hashi: List<String>): String =
        "$OSNOVA/checkcached?hash=${hashi.joinToString(",")}&format=object&list_files=false"
    fun naslovSeznam(id: Long): String = "$OSNOVA/mylist?id=$id&bypass_cache=true"
    fun naslovUstvari(): String = "$OSNOVA/createtorrent"
    fun naslovVsi(): String = "$OSNOVA/mylist?bypass_cache=true"

    /** POZOR: vsebuje kljuc (token) - samo za klic, nikoli v dnevnik, predvajalnik ali zgodovino. */
    fun naslovPrenosa(kljuc: String, torrentId: Long, datotekaId: Long): String =
        "$OSNOVA/requestdl?token=${java.net.URLEncoder.encode(kljuc, "UTF-8")}&torrent_id=$torrentId&file_id=$datotekaId"

    /** Datoteka torrenta pri TorBoxu (checkcached z list_files ali mylist). */
    data class Datoteka(val id: Long, val ime: String, val velikost: Long, val mime: String)

    private val VIDEO_KONCNICE = setOf("mkv", "mp4", "m4v", "avi", "webm", "mov", "ts", "m2ts", "wmv", "mpg", "mpeg")
    private val ZVOK_KONCNICE = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav")

    fun jePredvajljiva(d: Datoteka): Boolean {
        val k = d.ime.substringAfterLast('.', "").lowercase()
        return d.mime.startsWith("video/") || d.mime.startsWith("audio/") || k in VIDEO_KONCNICE || k in ZVOK_KONCNICE
    }

    /**
     * Katero datoteko predvajati: dodatek pove [indeks] (Stremio fileIdx, mesto v seznamu datotek torrenta) - ce je
     * to predvajljiva datoteka, ta; sicer najvecja predvajljiva (film, ne vzorec). Brez predvajljive: null.
     */
    fun izberiDatoteko(datoteke: List<Datoteka>, indeks: Int): Datoteka? {
        datoteke.getOrNull(indeks)?.takeIf { indeks >= 0 && jePredvajljiva(it) }?.let { return it }
        return datoteke.filter { jePredvajljiva(it) }.maxByOrNull { it.velikost }
    }

    /** Kaj pomeni napaka TorBoxa za uporabnika (sporocilo in ali je smiselno poskusiti brez TorBoxa). */
    const val NAPAKA_KLJUC = "kljuc"
    const val NAPAKA_MEJA = "meja"
    const val NAPAKA_PAKET = "paket"
    const val NAPAKA_NI_DATOTEKE = "ni_datoteke"
    const val NAPAKA_PRENASA = "prenasa"
    const val NAPAKA_STREZNIK = "streznik"
    /**
     * Varovalo racunov (lastnik 26. 10. 2026): racun je zaklenjen ali ne dovoli tega naslova (Real-Debrid 9, 14, 22;
     * AllDebrid AUTH_BLOCKED, AUTH_USER_BANNED) - Safeer storitve ne klice vec, dokler uporabnik ne tapne »Preveri«.
     */
    const val NAPAKA_ZAKLENJEN = "zaklenjen"

    fun vrstaNapake(httpKoda: Int, koda: String?): String = when {
        // Pregled Grok 34, E3 / skupni primeri v2: 403 BREZ kode TorBoxa (stran CDN, blokada IP) o kljucu ne pove nicesar -
        // streznik (prej »kljuc« - veljaven kljuc bi dobil »ne velja«). 401 brez telesa ostane kljuc.
        koda in setOf("NO_AUTH", "BAD_TOKEN", "AUTH_ERROR") || httpKoda == 401 -> NAPAKA_KLJUC
        koda in setOf("ACTIVE_LIMIT", "COOLDOWN_LIMIT", "MONTHLY_LIMIT") || httpKoda == 429 -> NAPAKA_MEJA
        koda in setOf("PLAN_RESTRICTED_FEATURE", "DOWNLOAD_TOO_LARGE") -> NAPAKA_PAKET
        koda in setOf("BOZO_TORRENT", "ITEM_NOT_FOUND", "LINK_OFFLINE") -> NAPAKA_NI_DATOTEKE
        else -> NAPAKA_STREZNIK
    }

    /**
     * Meja novih torrentov: [casi] so casi (ms) prejsnjih NEPREDPOMNJENIH createtorrent. Predpomnjen torrent se ne steje
     * (TorBox: zanj velja samo 300/min).
     */
    object MejaNovih {
        /** Casi, ki se stejejo (zadnja ura), za shrambo - starejsi odpadejo. */
        fun veljavni(casi: List<Long>, zdajMs: Long): List<Long> = casi.filter { zdajMs - it in 0 until URA_MS }

        fun dovoli(casi: List<Long>, zdajMs: Long): Boolean = veljavni(casi, zdajMs).size < NAJVEC_NOVIH_NA_URO

        /** Cez koliko ms bo spet prostor za nov torrent (0 = zdaj). */
        fun cakajMs(casi: List<Long>, zdajMs: Long): Long {
            val v = veljavni(casi, zdajMs).sorted()
            if (v.size < NAJVEC_NOVIH_NA_URO) return 0L
            return (v[v.size - NAJVEC_NOVIH_NA_URO] + URA_MS - zdajMs).coerceAtLeast(0L)
        }
    }

    /**
     * Ali je nov (nepredpomnjen) torrent ze na voljo za predvajanje: TorBox ga mora najprej prenesti. Stanje iz mylist:
     * [koncano] (download_finished), [prisoten] (download_present) ali [stanje] »cached«/»completed«/»uploading«.
     */
    fun pripravljen(stanje: String, koncano: Boolean, prisoten: Boolean): Boolean =
        koncano || prisoten || stanje.lowercase() in setOf("cached", "completed", "uploading")

    /** Toliko casa (in na toliko) cakamo, da TorBox prenese nov torrent, preden uporabniku povemo, naj poskusi kasneje. */
    const val CAKAJ_NOVEGA_MS = 180_000L
    const val CAKAJ_KORAK_MS = 5_000L
}
