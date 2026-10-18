package si.safeer.tv.cast

import java.io.File

/**
 * Viri naprave za clane kroga Safeer Linka (isto kot core/link_viri.py na racunalniku): koliko clanov sredisce sprejme,
 * odlocajo samo pravi viri naprave, merjeni sproti - ne fiksne stevilke.
 *
 * Lastnik, 18. 10. 2026: krog naj zmore tudi veliko podjetje (veliko naprav za enim naslovom). Clan kroga (dokazan z
 * vstopnico, ki jo dobi samo s podpisom kljuca ali zetonom) zato nima vec fiksne meje (prej 16 povezav WebSocket, 24
 * vticnic skupaj, 32 naprav, 16 sosednjih Hubov, 8 vstopnic, 64 sej, 3 prenosi, 4 zasloni, 4 gledalci). Novo povezavo ali
 * delo za clana zavrnemo samo, ko napravi res zmanjkuje deskriptorjev, pomnilnika ali niti - in takrat z jasnim razlogom (koda
 * [KODA], obvestilo na tej napravi v jeziku uporabnika), nikoli tiho. Fiksne meje ostanejo samo za NEPREVERJENE
 * (HubStreznik: dokler zahteva ni prebrana) in kot tehnicne meje protokola (velikost sporocila, dolzina imena).
 *
 * Rezerve (zakaj prav toliko):
 *  - deskriptorji: sredisce tece v procesu Safeer OS in temu procesu vedno pusti vsaj [REZERVA_DESKRIPTORJEV] prostih
 *    (pri nizki meji cetrtino meje), merjeno iz /proc/self/fd proti mehki meji RLIMIT_NOFILE (/proc/self/limits);
 *  - pomnilnik: na Androidu odloca sistem sam - ActivityManager.MemoryInfo (lowMemory, prag threshold), ki ga nastavi
 *    HubKrmilnik; v preizkusih na JVM /proc/meminfo z rezervo 5 % (najmanj 256 MiB) kot na racunalniku. Poleg tega
 *    kopica Jave: proces ima svojo mejo (na televizorju nekaj sto MiB), cez katero ga OutOfMemoryError sesuje ne glede na
 *    prosti pomnilnik sistema - zato vedno ostane [DELEZ_KOPICE] kopice (najmanj [REZERVA_KOPICE_B]);
 *  - niti: ce sistem nove niti ne da (OutOfMemoryError ob zagonu niti), je to pomanjkanje niti.
 *
 * Razred ne pozna Androida, da ga preizkusi poganjajo v navadnem JVM (tests/run_hub_tests.sh, run_obremenitev.sh).
 */
object ViriNaprave {
    /** Koda napake v odgovorih Linka, kadar clan ni sprejet zaradi virov (enaka kot na racunalniku). */
    const val KODA = "ni_virov"
    const val DESKRIPTORJI = "deskriptorji"
    const val POMNILNIK = "pomnilnik"
    const val NITI = "niti"
    /**
     * Besedilo v odgovoru [KODA], ce krmilnik ne nastavi prevoda (preizkusi na JVM); na napravi gre v jeziku uporabnika
     * (R.string.link_viri_zaseden). Pise ga odjemalec, ki ga pokaze: on poskusi znova sam.
     */
    const val BESEDILO_BREZ_VIROV = "Naprava ima zdaj premalo virov (povezav ali pomnilnika) za novo povezavo. Poskusim znova sam."

    /** Prostih deskriptorjev, ki jih Link vedno pusti ostalemu procesu; pri meji pod 2048 cetrtina meje. */
    const val REZERVA_DESKRIPTORJEV = 512
    /** Rezerva pomnilnika, kadar odloca /proc/meminfo (JVM): delez vsega in najmanj toliko bajtov. */
    const val DELEZ_POMNILNIKA = 0.05
    const val REZERVA_POMNILNIKA_B = 256L * 1024 * 1024
    /** Kopica Jave, ki ostane procesu (Safeer OS, predvajalnik): osmina meje kopice, najmanj 32 MiB. */
    const val DELEZ_KOPICE = 8
    const val REZERVA_KOPICE_B = 32L * 1024 * 1024
    /** Za eno povezavo clana: deskriptor in izhodna vrsta (do 512 KiB) z medpomnilniki - 1 MiB, kot na racunalniku. */
    const val POMNILNIK_POVEZAVE = 1024L * 1024
    /** Pomanjkanje se v stanju in obvestilu kaze toliko casa po zadnji zavrnitvi; obvestilo najvec tako pogosto. */
    const val VELJA_MS = 600_000L
    const val OBVESTILO_VSAKIH_MS = 600_000L
    /** Stetje /proc/self/fd stane: dalec od meje (vec kot toliko prostih) velja meritev najvec [SVEZE_MS]. */
    const val PROSTIH_ZA_PREDPOMNILNIK = 2048
    const val SVEZE_MS = 1_000L

    /**
     * Pomnilnik nad sistemskim pragom v bajtih (lahko negativno); na Androidu ActivityManager (HubKrmilnik), sicer null
     * in velja /proc/meminfo. Vrne null, ce sistem ne pove.
     */
    @Volatile var pomnilnikNadPragom: (() -> Long?)? = null
    /** Samo za preizkuse: namesto meritve vrne razlog ("" = viri so) za (deskriptorjev, bajtov). */
    @Volatile var preizkus: ((Int, Long) -> String)? = null
    /** Klic ob pomanjkanju (razlog, kje, zavrnjenih v zadnjih [VELJA_MS]) - najvec enkrat na [OBVESTILO_VSAKIH_MS]. */
    @Volatile var naPomanjkanje: ((String, String, Int) -> Unit)? = null
    /** Ura (ms, monotona); zamenljiva v preizkusih. */
    @Volatile var ura: () -> Long = { System.nanoTime() / 1_000_000L }

    private val zaklep = Any()
    private var zadnjaMeritev = Long.MIN_VALUE / 2
    private var zadnjiProstih = 0
    private var zadnjiPomnilnik = Long.MIN_VALUE / 2
    private var zadnjiProstega: Long? = null
    private var razlog = ""
    private var kje = ""
    private var ob = Long.MIN_VALUE / 2
    private var zavrnjenih = 0
    private var zadnjeObvestilo = Long.MIN_VALUE / 2

    // ------------------------------------------------------------------ meritve

    /** Mehka meja odprtih deskriptorjev procesa iz /proc/self/limits (»Max open files«) ali -1 (ne vemo, brez meje). */
    fun mejaDeskriptorjev(): Int = try {
        val vrstica = File("/proc/self/limits").readLines().firstOrNull { it.startsWith("Max open files") }
        val mehka = vrstica?.substring("Max open files".length)?.trim()?.split(Regex("\\s+"))?.firstOrNull()
        mehka?.toIntOrNull()?.takeIf { it > 0 } ?: -1
    } catch (_: Throwable) { -1 }

    fun rezervaDeskriptorjev(meja: Int): Int = minOf(REZERVA_DESKRIPTORJEV, maxOf(0, meja) / 4)

    /** Koliko deskriptorjev ima Link se na voljo nad rezervo (lahko negativno); null = ne vemo. */
    fun prostihDeskriptorjev(): Int? {
        val zdaj = ura()
        synchronized(zaklep) {
            if (zdaj - zadnjaMeritev < SVEZE_MS && zadnjiProstih > PROSTIH_ZA_PREDPOMNILNIK) return zadnjiProstih
        }
        val meja = mejaDeskriptorjev()
        if (meja < 0) return null
        val odprtih = try { File("/proc/self/fd").list()?.size } catch (_: Throwable) { null } ?: return null
        val prostih = meja - rezervaDeskriptorjev(meja) - odprtih
        synchronized(zaklep) { zadnjaMeritev = zdaj; zadnjiProstih = prostih }
        return prostih
    }

    /**
     * Pomnilnik nad rezervo v bajtih (lahko negativno): manjse od sistemskega (nad pragom) in kopice Jave nad njeno
     * rezervo; null = ne vemo. Sistemska meritev velja [SVEZE_MS], kopica se meri vsakic (en klic, brez branja datotek).
     */
    fun prostegaPomnilnika(): Long? {
        val zdaj = ura()
        val sistem = synchronized(zaklep) { if (zdaj - zadnjiPomnilnik < SVEZE_MS) zadnjiProstega else null }
            ?: (try { pomnilnikNadPragom?.invoke() ?: izMeminfo() } catch (_: Throwable) { null })
                .also { synchronized(zaklep) { zadnjiPomnilnik = zdaj; zadnjiProstega = it } }
        val kopica = prosteKopice()
        return when {
            sistem == null -> kopica
            kopica == null -> sistem
            else -> minOf(sistem, kopica)
        }
    }

    /** Kopica Jave nad rezervo (lahko negativno); null, ce meje ni. Zasedeno steje tudi se nepobrane smeti - previdno. */
    fun prosteKopice(): Long? {
        val rt = Runtime.getRuntime()
        val meja = rt.maxMemory()
        if (meja <= 0 || meja == Long.MAX_VALUE) return null
        return meja - (rt.totalMemory() - rt.freeMemory()) - maxOf(REZERVA_KOPICE_B, meja / DELEZ_KOPICE)
    }

    private fun izMeminfo(): Long? {
        var naVoljo = -1L
        var skupaj = -1L
        try {
            File("/proc/meminfo").forEachLine { v ->
                val kb = v.substringAfter(':').trim().substringBefore(' ').toLongOrNull()
                if (v.startsWith("MemAvailable:") && kb != null) naVoljo = kb * 1024
                if (v.startsWith("MemTotal:") && kb != null) skupaj = kb * 1024
            }
        } catch (_: Throwable) { return null }
        if (naVoljo < 0 || skupaj <= 0) return null
        return naVoljo - maxOf(REZERVA_POMNILNIKA_B, (skupaj * DELEZ_POMNILNIKA).toLong())
    }

    /**
     * "" = naprava zmore se toliko deskriptorjev in bajtov pomnilnika nad rezervama; sicer razlog ([DESKRIPTORJI],
     * [POMNILNIK]). Brez podatka (sistem ne pove) sprejmemo: takrat ne vemo vec od sistema, ki sam zavrne, kar ne gre.
     */
    fun preveri(deskriptorjev: Int = 1, pomnilnika: Long = 0L): String {
        preizkus?.let { return it(deskriptorjev, pomnilnika) }
        val prosti = prostihDeskriptorjev()
        if (prosti != null && prosti < deskriptorjev) return DESKRIPTORJI
        val prostega = prostegaPomnilnika()
        if (prostega != null && prostega < maxOf(0L, pomnilnika)) return POMNILNIK
        return ""
    }

    /** Nit za clana. Ce je sistem ne da (meja niti ali pomnilnika), to zabelezi kot pomanjkanje in vrne false. */
    fun zazeniNit(ime: String, kje: String, blok: () -> Unit): Boolean = try {
        Thread(null, { blok() }, ime).apply { isDaemon = true }.start()
        true
    } catch (_: Throwable) {
        zabelezi(NITI, kje)
        false
    }

    // ------------------------------------------------------------------ pomanjkanje: dnevnik, stanje, obvestilo

    /** Clan ni bil sprejet zaradi virov: v dnevnik, v stanje in (redko) obvestilo uporabniku na tej napravi. */
    fun zabelezi(razlog: String, kje: String = "") {
        val zdaj = ura()
        var obvesti = false
        var n = 0
        synchronized(zaklep) {
            if (zdaj - ob > VELJA_MS) zavrnjenih = 0
            this.razlog = razlog
            this.kje = kje
            ob = zdaj
            zavrnjenih++
            n = zavrnjenih
            if (zdaj - zadnjeObvestilo >= OBVESTILO_VSAKIH_MS) { zadnjeObvestilo = zdaj; obvesti = true }
        }
        if (obvesti) {
            android.util.Log.w("SafeerLinkViri", "clan ni sprejet ($razlog, ${kje.ifBlank { "-" }}) - zavrnjenih v zadnjih " +
                "${VELJA_MS / 60_000} min: $n; nad rezervo: deskriptorjev ${prostihDeskriptorjev() ?: "?"}, pomnilnika " +
                "${prostegaPomnilnika()?.let { it / (1024 * 1024) } ?: "?"} MiB")
            try { naPomanjkanje?.invoke(razlog, kje, n) } catch (_: Throwable) { }
        }
    }

    /** Zadnje pomanjkanje v zadnjih [VELJA_MS]: (razlog, kje, zavrnjenih) ali null. */
    fun stanje(): Triple<String, String, Int>? = synchronized(zaklep) {
        if (razlog.isEmpty() || ura() - ob > VELJA_MS) null else Triple(razlog, kje, zavrnjenih)
    }

    /** Za preizkuse: brez zapisanega pomanjkanja, obvestilo spet takoj, sveza meritev. */
    fun ponastavi() = synchronized(zaklep) {
        razlog = ""; kje = ""; ob = Long.MIN_VALUE / 2; zavrnjenih = 0; zadnjeObvestilo = Long.MIN_VALUE / 2
        zadnjaMeritev = Long.MIN_VALUE / 2; zadnjiPomnilnik = Long.MIN_VALUE / 2; zadnjiProstega = null
    }
}
