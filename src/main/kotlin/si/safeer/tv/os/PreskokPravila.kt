package si.safeer.tv.os

/**
 * Preskok uvoda, povzetka, odjavne spice in napovedi (lastnik 13. 11. 2026, ideja iz IPVIO Stream; kode nismo prevzeli).
 * Casi so iz skupnostne baze TheIntroDB (api.theintrodb.org/v4/media, brez kljuca). Pogoji (20. 9. 2026): samo
 * nekomercialna uporaba, klic neposredno z uporabnikove naprave, predpomnjenje samo za sejo - zato se casi hranijo
 * samo v pomnilniku predvajalnika. Casi so lahko netocni (drug posnetek, PAL/NTSC): gumb ponudi preskok, samodejnega ni.
 * Cista logika brez Androida (preizkus tests/PreskokPravilaTest.kt).
 */
object PreskokPravila {
    const val GOSTITELJ = "api.theintrodb.org"
    const val KLJUC_PRESKOK = "preskok_odsekov"
    /** Odgovor je majhen (nekaj odsekov); vse vec je napaka ali zloraba. */
    const val NAJVEC_BAJTOV = 16 * 1024
    const val ROK_MS = 4_000
    /** Krajsih odsekov ne ponudimo (gumb bi se pokazal in izginil). */
    const val NAJKRAJSI_MS = 5_000L
    /** Uvod in povzetek nista daljsa od 5 min in se zacneta v prvih 20 min (ali prvi tretjini): baza je skupnostna -
     *  pokvarjen vnos (»uvod 0 do 24 h«) bi sicer gumb drzal ves film (neodvisni pregled 13. 11. 2026, N1). */
    const val NAJDALJSI_UVOD_MS = 5L * 60_000L
    const val UVOD_NAJPOZNEJE_MS = 20L * 60_000L
    /** Odjavna in napoved se zacneta v drugi polovici posnetka (brez trajanja jih ne ponudimo). */
    const val ODJAVNA_OD_DELEZA = 0.5
    /** Gumb je viden najvec toliko od vstopa v odsek: potem OK spet dela pavzo (dolga odjavna, N2). */
    const val GUMB_VIDEN_MS = 15_000L
    /** Gumb izgine malo pred koncem odseka, da ne skoci za nekaj sekund. */
    const val PRED_KONCEM_MS = 1_500L
    private const val NAJVEC_ODSEKOV = 8
    private const val NAJVEC_CAS_MS = 24L * 3600_000L

    enum class Vrsta(val kljucApi: String) { POVZETEK("recap"), UVOD("intro"), ODJAVNA("credits"), NAPOVED("preview") }

    /** [doMs] null = do konca posnetka. */
    data class Odsek(val vrsta: Vrsta, val odMs: Long, val doMs: Long?)

    private val RX_IMDB = Regex("^tt\\d{7,10}$")

    /** Naslov poizvedbe ali null (ni veljavnega IMDb id, napacna sezona/epizoda). Film: sezona in epizoda 0. */
    fun naslov(imdbId: String?, sezona: Int, epizoda: Int): String? {
        val id = imdbId?.trim().orEmpty()
        if (!RX_IMDB.matches(id)) return null
        if (sezona < 0 || epizoda < 0 || sezona > 999 || epizoda > 9999) return null
        if (sezona != 0 && epizoda == 0) return null      // sezona brez epizode ni film ne epizoda
        val zaEpizodo = if (epizoda > 0) "&season=$sezona&episode=$epizoda" else ""
        return "https://$GOSTITELJ/v4/media?imdb_id=$id$zaEpizodo"
    }

    private fun stevilo(vrednost: String?): Long? {
        val v = vrednost?.trim() ?: return null
        if (v == "null") return null
        val n = v.toLongOrNull() ?: return -1L
        return if (n in 0..NAJVEC_CAS_MS) n else -1L
    }

    /** Odseki iz odgovora API; neveljavne vnose izpusti. Brez knjiznice JSON: oblika je ploska in znana. */
    fun razcleni(json: String): List<Odsek> {
        if (json.length > NAJVEC_BAJTOV) return emptyList()
        val izid = mutableListOf<Odsek>()
        for (vrsta in Vrsta.values()) {
            val polje = Regex("\"${vrsta.kljucApi}\"\\s*:\\s*\\[([^\\]]*)\\]").find(json)?.groupValues?.get(1) ?: continue
            for (obj in Regex("\\{([^{}]*)\\}").findAll(polje).take(NAJVEC_ODSEKOV)) {
                val t = obj.groupValues[1]
                val od = stevilo(Regex("\"start_ms\"\\s*:\\s*(null|-?\\d+)").find(t)?.groupValues?.get(1) ?: "null")
                val dO = stevilo(Regex("\"end_ms\"\\s*:\\s*(null|-?\\d+)").find(t)?.groupValues?.get(1) ?: "null")
                if (od == -1L || dO == -1L) continue
                // Uvod in povzetek: zacetek null = od zacetka, konec mora biti znan. Odjavna in napoved: konec null = do konca.
                val zacetek = od ?: if (vrsta == Vrsta.UVOD || vrsta == Vrsta.POVZETEK) 0L else continue
                if ((vrsta == Vrsta.UVOD || vrsta == Vrsta.POVZETEK) && dO == null) continue
                if (dO != null && dO <= zacetek) continue
                izid += Odsek(vrsta, zacetek, dO)
            }
        }
        return izid.sortedBy { it.odMs }
    }

    private fun konec(o: Odsek, trajanjeMs: Long): Long? = o.doMs ?: trajanjeMs.takeIf { it > 0 }

    /** Ali je odsek smiseln za ta posnetek (dolzina in lega po vrsti). */
    fun smiseln(o: Odsek, trajanjeMs: Long): Boolean {
        val k = konec(o, trajanjeMs) ?: return false
        val kOmejen = if (trajanjeMs > 0) minOf(k, trajanjeMs) else k
        if (kOmejen - o.odMs < NAJKRAJSI_MS) return false
        return when (o.vrsta) {
            Vrsta.UVOD, Vrsta.POVZETEK -> kOmejen - o.odMs <= NAJDALJSI_UVOD_MS &&
                o.odMs <= maxOf(UVOD_NAJPOZNEJE_MS, if (trajanjeMs > 0) trajanjeMs / 3 else 0L)
            Vrsta.ODJAVNA, Vrsta.NAPOVED -> trajanjeMs > 0 && o.odMs >= (trajanjeMs * ODJAVNA_OD_DELEZA).toLong()
        }
    }

    /** Odsek, v katerem je predvajanje, ali null. Brez trajanja odsek »do konca« ne velja. */
    fun aktiven(odseki: List<Odsek>, polozajMs: Long, trajanjeMs: Long): Odsek? = odseki.firstOrNull { o ->
        if (!smiseln(o, trajanjeMs)) return@firstOrNull false
        val k = konec(o, trajanjeMs) ?: return@firstOrNull false
        val kOmejen = if (trajanjeMs > 0) minOf(k, trajanjeMs) else k
        polozajMs >= o.odMs && polozajMs < kOmejen - PRED_KONCEM_MS
    }

    /** Gumb za odsek je viden, dokler ni minilo [GUMB_VIDEN_MS] od vstopa vanj (vstop = prvic videno, tudi po previjanju). */
    fun gumbViden(vstopMs: Long, zdajMs: Long): Boolean = zdajMs - vstopMs < GUMB_VIDEN_MS

    /** Kam skoci gumb: konec odseka (pri »do konca« zadnja sekunda posnetka - konec posnetka sprozi naslednjo epizodo). */
    fun cilj(o: Odsek, trajanjeMs: Long): Long? {
        val k = konec(o, trajanjeMs) ?: return null
        return if (trajanjeMs > 0) minOf(k, (trajanjeMs - 1_000L).coerceAtLeast(0L)) else k
    }

    /** Ali poizvedbo sploh posljemo: vklopljeno, video na zahtevo (ne v zivo), veljaven naslov. */
    fun poizvedba(vklopljeno: Boolean, video: Boolean, vZivo: Boolean, imdbId: String?, sezona: Int, epizoda: Int): String? =
        if (!vklopljeno || !video || vZivo) null else naslov(imdbId, sezona, epizoda)
}
