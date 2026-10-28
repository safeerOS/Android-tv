package si.safeer.tv.os

/**
 * Naloga 133: kakovost glede na hitrost povezave. OK na kartici je na TV izbral 4K REMUX 77 GB (Inception) - na nasi
 * povezavi je tekel, na pocasnejsi bi zatikal. Tok, katerega bitna hitrost (velikost / dolzina) presega [DELEZ] izmerjene
 * hitrosti, gre za tiste, ki ustrezajo. Brez meritve ([HitrostPovezave]) ostane izbira, kot je. Ciste odlocitve
 * (preizkus: tests/KakovostPravilaTest.kt).
 */
object KakovostPravila {
    /** Delez izmerjene hitrosti, ki ga tok sme porabiti (rezerva za nihanje povezave in zvocne sledi). */
    const val DELEZ = 0.7

    /** Dolzine nimamo v podatkih dodatkov: film se steje 130 min, epizoda 50 min (raje vec kot manj - manjsa bitna hitrost). */
    fun minut(jeEpizoda: Boolean): Int = if (jeEpizoda) 50 else 130

    /** Bitna hitrost toka v bitih na sekundo; 0 = velikosti ne poznamo. */
    fun bitnaHitrost(gb: Double, minut: Int): Long =
        if (gb <= 0.0 || minut <= 0) 0L else (gb * 1_000_000_000.0 * 8.0 / (minut * 60.0)).toLong()

    /** Ali tok ustreza povezavi: neznana velikost ali hitrost vedno ustreza. */
    fun ustreza(gb: Double, minut: Int, hitrost: Long): Boolean {
        val b = bitnaHitrost(gb, minut)
        return b == 0L || hitrost <= 0L || b <= hitrost * DELEZ
    }

    /**
     * Vrstni red za povezavo: kar ustreza, ostane v svojem redu (kakovost po TokIzbira) in gre naprej; kar ne, za njim -
     * najprej manjse (vecja moznost, da tece). Brez meritve vrstni red ostane. Vrne indekse v [gb].
     */
    fun urediZaHitrost(gb: List<Double>, minut: Int, hitrost: Long): List<Int> {
        if (hitrost <= 0L) return gb.indices.toList()
        val da = gb.indices.filter { ustreza(gb[it], minut, hitrost) }
        val ne = gb.indices.filter { !ustreza(gb[it], minut, hitrost) }.sortedBy { gb[it] }
        return da + ne
    }

    /** Meritev se zapomni samo iz dovolj velikega vzorca (video, ne posamezen kos zvoka). */
    const val NAJMANJSI_VZOREC = 2L * 1024 * 1024

    fun vzorecVelja(bajtov: Long): Boolean = bajtov >= NAJMANJSI_VZOREC

    /**
     * Hitrost iz vzorca nalaganja (prejeti bajti v casu, ko je predvajalnik nalagal medpomnilnik): predvajalnik nalaga v
     * sunkih s polno hitrostjo, zato je to hitrost povezave do streznika toka. 0 = vzorec premajhen ali prekratek.
     */
    fun hitrostIzVzorca(bajtov: Long, ms: Long): Long =
        if (!vzorecVelja(bajtov) || ms < 1_000) 0L else bajtov * 8_000 / ms

    /** Glajenje: nova meritev steje 30 %, da en hiter ali pocasen trenutek ne obrne izbire. */
    fun zgladi(stara: Long, nova: Long): Long = if (stara <= 0L) nova else (stara * 0.7 + nova * 0.3).toLong()

    /** Meritev velja teden dni (povezava doma se ne spreminja pogosto; po selitvi naprave se popravi z novimi vzorci). */
    const val VELJA_MS = 7L * 24 * 3_600_000

    fun velja(kdajMs: Long, zdajMs: Long): Boolean = zdajMs - kdajMs in 0 until VELJA_MS

    // --- Oznaka kakovosti ob imenu filma (lastnik 28. 10. 2026, razdelek 5) ------------------------------------------
    // Ista pravila na Linuxu in Windows (core/kakovost_oznake.py) - skupni primeri tests/skupni-primeri/kakovost-oznake.json.

    /** Oznaka kakovosti izdaje; [rang] za »najboljso znano«, [opozorilo] = posnetek iz kina (CAM, TS) v opozorilni barvi. */
    enum class Oznaka(val napis: String, val rang: Int, val opozorilo: Boolean) {
        CAM("CAM", 1, true), TS("TS", 2, true), SD("SD", 3, false), HD("HD", 4, false), FULL_HD("Full HD", 5, false), UHD("4K", 6, false);

        companion object {
            fun izNapisa(napis: String?): Oznaka? = values().firstOrNull { it.napis == napis }
        }
    }

    private val CAM_BESEDE = setOf("cam", "camrip", "hdcam", "hqcam", "newcam", "camhd")
    private val TS_BESEDE = setOf("ts", "hdts", "telesync", "tc", "hdtc", "telecine", "pdvd", "predvd", "tsrip", "hdtsrip")
    private val UHD_BESEDE = setOf("2160p", "2160", "4k", "uhd", "4kuhd", "uhd4k")
    private val FHD_BESEDE = setOf("1080p", "1080i", "1080", "fhd", "fullhd")
    private val HD_BESEDE = setOf("720p", "720", "hd")
    private val SD_BESEDE = setOf("480p", "480", "576p", "576", "360p", "sd", "dvdrip", "dvdscr", "xvid")
    /** Koncnica datoteke ».ts« (MPEG-TS) ni telesync: tocka, »ts« in konec besedila ali presledek. */
    private val KONCNICA_TS = Regex("""\.ts(?=$|[\s)\]])""")
    private val LOCILA = Regex("""[^a-z0-9]+""")

    /**
     * Oznaka iz imena in opisa toka (»Torrentio 4k«, »Movie.2025.HDCAM.x264«). CAM in TS imata prednost pred locljivostjo
     * (»HDCAM 1080p« je CAM). »Full HD« in »full-hd« sta ena beseda. Brez znane oznake vrne null (nic ne ugibamo).
     */
    fun oznaka(ime: String): Oznaka? {
        val besedilo = KONCNICA_TS.replace(ime.lowercase(java.util.Locale.ROOT), " ")
            .replace(Regex("""full[\s._-]*hd"""), " fullhd ").replace(Regex("""4k[\s._-]*uhd"""), " 4k ")
        val besede = besedilo.split(LOCILA).filter { it.isNotEmpty() }.toSet()
        return when {
            besede.any { it in CAM_BESEDE } -> Oznaka.CAM
            besede.any { it in TS_BESEDE } -> Oznaka.TS
            besede.any { it in UHD_BESEDE } -> Oznaka.UHD
            besede.any { it in FHD_BESEDE } -> Oznaka.FULL_HD
            besede.any { it in HD_BESEDE } -> Oznaka.HD
            besede.any { it in SD_BESEDE } -> Oznaka.SD
            else -> null
        }
    }

    /** Najboljsa oznaka med tokovi (najvisji rang); CAM samo, ce nic boljsega ni znano. */
    fun najboljsa(imena: Iterable<String>): Oznaka? = imena.mapNotNull { oznaka(it) }.maxByOrNull { it.rang }

    /** Znana oznaka se samo izboljsa: nova izdaja 4K zamenja CAM, slabsi tok (zadnje predvajanje CAM) je ne poslabsa. */
    fun zdruzi(znana: Oznaka?, nova: Oznaka?): Oznaka? = listOfNotNull(znana, nova).maxByOrNull { it.rang }

    /** Napis na kartici: »★ 4K« za oznako iz tokov, opozorilo »⚠ CAM«. */
    fun napisKartice(o: Oznaka): String = (if (o.opozorilo) "⚠ " else "★ ") + o.napis

    /** Kljuc za shrambo: samo id filma (epizode imajo svojo kakovost - »tt1:1:2« ne shranimo). */
    fun kljuc(id: String): String? = id.takeIf { it.isNotBlank() && ':' !in it && it.length <= 64 }

    /** Najvec znanih oznak v shrambi (najstarejse izpadejo). */
    const val NAJVEC_ZNANIH = 500
}
