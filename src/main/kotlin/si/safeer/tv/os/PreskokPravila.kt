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

    /**
     * Lastnik 14. 11. 2026 (nadomesti neodvisni pregled N4): gumb za preskok je PRIVZETO VKLOPLJEN. Poizvedba pove TheIntroDB
     * oznako IMDb, sezono, epizodo in naslov IP; uporabnik jo izklopi v Medijski center > zobnik > Predvajanje. Edini vir
     * privzete vrednosti (GlasbaStoritev.preskokOdsekov). Ostala varovala ostanejo: brez IMDb ni klica, [ROK_MS],
     * [NAJVEC_BAJTOV], en klic na posnetek v seji.
     */
    const val PRIVZETO_VKLOPLJEN = true

    /**
     * Kljuc nastavitve. Nov 14. 11. 2026 (prej »preskok_odsekov«): edino zapisano vrednost starega kljuca (izklopljeno) je na
     * napravi lastnika pustil preizkus 14. 11. 2026 (vklop in izklop v nastavitvah), ne lastnik - z novo privzeto
     * vrednostjo zato velja nov kljuc. Zapise ga samo rocni preklop v nastavitvah.
     */
    const val KLJUC_PRESKOK = "preskok_odsekov_2"
    /** Odgovor je majhen (nekaj odsekov); vse vec je napaka ali zloraba. */
    const val NAJVEC_BAJTOV = 16 * 1024
    const val ROK_MS = 4_000
    /** Krajsih odsekov ne ponudimo (gumb bi se pokazal in izginil). */
    const val NAJKRAJSI_MS = 5_000L
    /** Uvod in povzetek nista daljsa od 5 min in se zacneta v prvih 20 min (ali prvi tretjini): baza je skupnostna -
     *  pokvarjen vnos (»uvod 0 do 24 h«) bi sicer gumb drzal ves film (neodvisni pregled 13. 11. 2026, N1). */
    const val NAJDALJSI_UVOD_MS = 5L * 60_000L
    const val UVOD_NAJPOZNEJE_MS = 20L * 60_000L
    /** Odjavna in napoved se zacneta v zadnji cetrtini in segata do konca (brez trajanja jih ne ponudimo): skupnostni
     *  odsek sredi filma bi prevzel OK (slepi pregled 13. 11. 2026). */
    const val ODJAVNA_OD_DELEZA = 0.75
    const val ODJAVNA_DO_DELEZA = 0.9
    /** Gumb je viden najvec toliko od vstopa v odsek: potem OK spet dela pavzo (dolga odjavna, N2). */
    const val GUMB_VIDEN_MS = 15_000L
    /** Gumb izgine malo pred koncem odseka, da ne skoci za nekaj sekund. */
    const val PRED_KONCEM_MS = 1_500L
    private const val NAJVEC_ODSEKOV = 2
    /** Pregledamo najvec toliko vnosov vrste: neveljavni na zacetku ne skrijejo veljavnega (drugi slepi pregled). */
    private const val NAJVEC_PREGLEDANIH = 16
    private const val NAJVEC_CAS_MS = 24L * 3600_000L

    /**
     * Odmik gumba od spodnjega roba (px): privzeto [osnovniPx] (nad pasom z imenom), a vedno nad vidnimi podnapisi
     * ([vrhPodnapisovPx] = razdalja vrha podnapisov od spodnjega roba, null = ni podnapisov) z razmikom [razmikPx]. 14. 11. 2026:
     * dolg podnapis v dveh vrsticah na sliki s pasovi (povzetek ima govor) je segal pod gumb desno spodaj.
     */
    fun odmikGumba(osnovniPx: Int, vrhPodnapisovPx: Int?, razmikPx: Int): Int =
        if (vrhPodnapisovPx == null || vrhPodnapisovPx <= 0) osnovniPx else maxOf(osnovniPx, vrhPodnapisovPx + razmikPx)

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
            for (obj in Regex("\\{([^{}]*)\\}").findAll(polje).take(NAJVEC_PREGLEDANIH)) {
                val t = obj.groupValues[1]
                val od = stevilo(Regex("\"start_ms\"\\s*:\\s*(null|-?\\d+)").find(t)?.groupValues?.get(1) ?: "null")
                val dO = stevilo(Regex("\"end_ms\"\\s*:\\s*(null|-?\\d+)").find(t)?.groupValues?.get(1) ?: "null")
                if (od == -1L || dO == -1L) continue
                // Uvod in povzetek: zacetek null = od zacetka, konec mora biti znan. Odjavna in napoved: konec null = do konca.
                val zacetek = od ?: if (vrsta == Vrsta.UVOD || vrsta == Vrsta.POVZETEK) 0L else continue
                if ((vrsta == Vrsta.UVOD || vrsta == Vrsta.POVZETEK) && dO == null) continue
                if (dO != null && dO <= zacetek) continue
                // Preverbe brez trajanja posnetka ze tu (CodeRabbit 13. 11.: neuporaben vnos ne sme zasesti mesta).
                if (dO != null && dO - zacetek < NAJKRAJSI_MS) continue
                if ((vrsta == Vrsta.UVOD || vrsta == Vrsta.POVZETEK) && dO != null && dO - zacetek > NAJDALJSI_UVOD_MS) continue
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
            Vrsta.ODJAVNA, Vrsta.NAPOVED -> trajanjeMs > 0 && o.odMs >= (trajanjeMs * ODJAVNA_OD_DELEZA).toLong() &&
                kOmejen >= (trajanjeMs * ODJAVNA_DO_DELEZA).toLong()
        }
    }

    /** Uporabni odseki za ta posnetek: smiselni, najvec [NAJVEC_ODSEKOV] na vrsto (meja po preverbi trajanja - CodeRabbit
     *  13. 11.: dva neuporabna vnosa na zacetku ne smeta skriti tretjega, veljavnega). */
    fun uporabni(odseki: List<Odsek>, trajanjeMs: Long): List<Odsek> =
        odseki.filter { smiseln(it, trajanjeMs) }.groupBy { it.vrsta }.values.flatMap { it.take(NAJVEC_ODSEKOV) }.sortedBy { it.odMs }

    /** Odsek, v katerem je predvajanje, ali null. Brez trajanja odsek »do konca« ne velja. [izkljuceni] (ze preskoceni ali
     *  z iztecenim gumbom) ne zakrijejo drugega odseka na istem mestu (CodeRabbit 13. 11.: povzetek 0-90 s in uvod 60-80 s). */
    fun aktiven(odseki: List<Odsek>, polozajMs: Long, trajanjeMs: Long, izkljuceni: Set<Odsek> = emptySet()): Odsek? =
        uporabni(odseki, trajanjeMs).firstOrNull { o ->
        if (o in izkljuceni) return@firstOrNull false
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
