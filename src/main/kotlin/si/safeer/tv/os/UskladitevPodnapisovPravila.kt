package si.safeer.tv.os

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Samodejna uskladitev podnapisov s sliko (lastnik 13. 11. 2026: »prehitevajo«, 14. 11.: »so tokrat zelo za sliko«).
 *
 * IZMERJENO 14. 11. 2026 (epizoda serije, Usenet, TV): nas prikaz je tocen (napis na zaslonu ima v datoteki cas, ki je
 * enak polozaju predvajalnika), edini slovenski podnapis pri OpenSubtitles pa je za televizijsko izdajo DSR (pospesena na 25 fps,
 * drugi rezi). Proti izdaji BluRay je odmik po odsekih od +30 s do -16 s - zato se zdi enkrat »prehitevajo«, drugic
 * »zaostajajo«, stalen rocni zamik pa tega ne popravi.
 *
 * Zato: (1) prednost ima podnapis izdaje, ki se ujema z videom ([tocke]); (2) podnapis druge izdaje poravnamo po CASIH napisov
 * ujemajoce se izdaje v katerem koli jeziku ([poravnaj]) - brez besedila in brez zvoka (zvoka iz toka ne moremo prebrati vnaprej,
 * ne da bi prenesli ves film). Po odsekih: razmerje hitrosti (23,976/24/25 fps) in zamik; skok med odsekoma stane [KAZEN_MS]
 * in [LAM_MS_NA_KORAK] na korak, zato neresnicnih skokov ni. Meritev 14. 11. 2026 na 4 parih izdaj (resnica iz enakih vrstic
 * dveh angleskih izdaj): mediana napake 0,07-0,26 s, p90 0,43-0,74 s; DSR proti BluRay prej mediana 13,4 s.
 * Cisto pravilo brez Androida (preizkus tests/UskladitevPodnapisovPravilaTest.kt).
 */
object UskladitevPodnapisovPravila {
    /** Mreza zamikov: korak 0,2 s, do +-120 s (vecji odmik je druga vsebina, ne druga izdaja). */
    const val KORAK_MS = 200
    const val RAZPON_MS = 120_000
    /** Kazen skoka v milisekundah prekrivanja (izmerjeno 14. 11.: 1 s je najboljsa na vseh 4 parih; 2-3 s izgubi reze oglasov DSR). */
    const val KAZEN_MS = 1000f
    /** Dodatna kazen na korak skoka (0,5 s prekrivanja na sekundo skoka): brez nje so bili skoki za 100-260 s. */
    const val LAM_MS_NA_KORAK = 0.5f * KORAK_MS
    /** Sprejmemo, ce je po poravnavi pokritih vsaj 68 % casa napisov (pravi pari 0,71-0,96; napacna epizoda 0,58-0,63). */
    const val NAJMANJ_POKRITOST = 0.68
    const val NAJMANJ_IZBOLJSANJE = 0.05
    const val NAJMANJ_NAPISOV = 20
    /** Zgornja meja dela in pomnilnika (dolg film ima 1500-2000 napisov). */
    const val NAJVEC_NAPISOV = 3000
    /** Trajanje napisa brez konca (zadnji napis v Media3). */
    const val PRIVZETO_TRAJANJE_MS = 2000L
    private const val BLOK = 32

    /** Razmerja hitrosti: enako, 23,976 <-> 25 (PAL), 24 <-> 25. 24 <-> 23,976 (0,1 %) pokrijejo odseki (izmerjeno 14. 11.: enaka napaka, pol casa). */
    val RAZMERJA = doubleArrayOf(1.0, 25.0 / 23.976, 23.976 / 25.0, 25.0 / 24.0, 24.0 / 25.0)

    class Napis(val zacetekMs: Long, val konecMs: Long)

    /** Za vsak napis vira (v vhodnem vrstnem redu): nov zacetek = razmerje * zacetek + zamik. */
    class Izid(val razmerje: DoubleArray, val zamikMs: LongArray, val pokritostPrej: Double, val pokritostPo: Double, val odsekov: Int) {
        fun zacetek(i: Int, ms: Long): Long = (razmerje[i] * ms).roundToLong() + zamikMs[i]
    }

    /** Pokritost casa z napisi reference (zdruzeni intervali, kumulativa) - prekrivanje intervala v O(log n). */
    private class Pokritost(ref: List<Napis>) {
        val zac: LongArray
        val kon: LongArray
        val kum: LongArray
        init {
            val urejeni = ref.filter { it.konecMs > it.zacetekMs }.sortedBy { it.zacetekMs }
            val z = ArrayList<Long>(); val k = ArrayList<Long>()
            for (n in urejeni) {
                if (z.isNotEmpty() && n.zacetekMs <= k[k.size - 1]) k[k.size - 1] = maxOf(k[k.size - 1], n.konecMs)
                else { z.add(n.zacetekMs); k.add(n.konecMs) }
            }
            zac = z.toLongArray(); kon = k.toLongArray()
            kum = LongArray(zac.size + 1)
            for (i in zac.indices) kum[i + 1] = kum[i] + kon[i] - zac[i]
        }
        /** Pokriti cas v [0, t]. */
        fun doCasa(t: Long): Long {
            var lo = 0; var hi = zac.size
            while (lo < hi) { val m = (lo + hi) ushr 1; if (zac[m] <= t) lo = m + 1 else hi = m }
            if (lo == 0) return 0L
            return kum[lo - 1] + minOf(t, kon[lo - 1]) - zac[lo - 1]
        }
        fun prekrivanje(a: Long, b: Long): Long = if (b <= a) 0L else doCasa(b) - doCasa(a)

        /** Pokriti cas v [0, t] od kazalca [od] naprej (t narasca): vrne nov kazalec v [kazalec]. */
        fun doCasaOd(t: Long, od: Int, kazalec: IntArray, mesto: Int): Long {
            var lo = od
            while (lo < zac.size && zac[lo] <= t) lo++
            kazalec[mesto] = lo
            if (lo == 0) return 0L
            return kum[lo - 1] + minOf(t, kon[lo - 1]) - zac[lo - 1]
        }
    }

    /**
     * Poravnava casov [vir] na [ref] po odsekih. null = premalo ali prevec napisov. O sprejemu odloci [sprejmi].
     * Dinamicno programiranje po napisih (stanje = razmerje x zamik, 5 x 1201), vrstice hranimo le vsakih [BLOK] napisov
     * (5 x 1201 stanj po 4 B = 24 kB na vrstico; pri 3000 napisih 94 tock + 32 vrstic bloka = 3 MB), pri sledenju nazaj jih preracunamo.
     */
    fun poravnaj(vir: List<Napis>, ref: List<Napis>): Izid? {
        val n = vir.size
        if (n < NAJMANJ_NAPISOV || ref.size < NAJMANJ_NAPISOV || n > NAJVEC_NAPISOV) return null
        val red = vir.indices.sortedBy { vir[it].zacetekMs }
        val s = LongArray(n) { vir[red[it]].zacetekMs }
        val e = LongArray(n) { maxOf(vir[red[it]].konecMs, vir[red[it]].zacetekMs + 1) }
        val pok = Pokritost(ref)
        if (pok.zac.isEmpty()) return null
        val stZamikov = 2 * RAZPON_MS / KORAK_MS + 1
        val nR = RAZMERJA.size
        val stanj = nR * stZamikov
        val tmp = FloatArray(stZamikov)
        val alt = FloatArray(stanj)
        val kazalca = IntArray(2)

        fun zamik(k: Int): Long = -RAZPON_MS.toLong() + k.toLong() * KORAK_MS
        fun premik(a: Int, b: Int, cas: Long): Int = ((RAZMERJA[a] - RAZMERJA[b]) * cas / KORAK_MS).roundToInt()
        fun kazen(a: Int, b: Int): Float = if (a == b) KAZEN_MS else 2 * KAZEN_MS

        // Ena vrstica naprej: nova[b,k] = max(ostane, skok) + prekrivanje napisa i pri (razmerje b, zamik k).
        // Skok iz razmerja a v b ohrani cas na zaslonu (premik), nato L1 razdaljna preslikava v dveh prehodih.
        fun naprej(prej: FloatArray, i: Int, nova: FloatArray) {
            java.util.Arrays.fill(alt, Float.NEGATIVE_INFINITY)
            for (a in 0 until nR) for (b in 0 until nR) {
                val d = premik(a, b, s[i])
                if (abs(d) >= stZamikov) continue
                val pen = kazen(a, b)
                var run = Float.NEGATIVE_INFINITY
                for (kb in 0 until stZamikov) {
                    val src = kb - d
                    val q = if (src in 0 until stZamikov) prej[a * stZamikov + src] else Float.NEGATIVE_INFINITY
                    run = maxOf(run - LAM_MS_NA_KORAK, q); tmp[kb] = run
                }
                run = Float.NEGATIVE_INFINITY
                for (kb in stZamikov - 1 downTo 0) {
                    val src = kb - d
                    val q = if (src in 0 until stZamikov) prej[a * stZamikov + src] else Float.NEGATIVE_INFINITY
                    run = maxOf(run - LAM_MS_NA_KORAK, q)
                    val v = maxOf(tmp[kb], run) - pen
                    if (v > alt[b * stZamikov + kb]) alt[b * stZamikov + kb] = v
                }
            }
            for (b in 0 until nR) {
                val r = RAZMERJA[b]
                val rs = (r * s[i]).roundToLong(); val re = (r * e[i]).roundToLong()
                // Zamik narasca s k: kazalca v pokritosti le napredujeta (namesto dveh binarnih iskanj na stanje).
                kazalca[0] = 0; kazalca[1] = 0
                for (k in 0 until stZamikov) {
                    val o = zamik(k)
                    val x = b * stZamikov + k
                    val pa = pok.doCasaOd(rs + o, kazalca[0], kazalca, 0)
                    val pb = pok.doCasaOd(re + o, kazalca[1], kazalca, 1)
                    nova[x] = maxOf(prej[x], alt[x]) + (pb - pa).toFloat()
                }
            }
        }

        val tocke = ArrayList<FloatArray>()
        var vrstica = FloatArray(stanj)
        var naslednja = FloatArray(stanj)
        for (i in 0 until n) {
            if (i % BLOK == 0) tocke.add(vrstica.copyOf())
            naprej(vrstica, i, naslednja)
            val t = vrstica; vrstica = naslednja; naslednja = t
        }
        var najx = 0
        for (x in 1 until stanj) if (vrstica[x] > vrstica[najx]) najx = x
        var b = najx / stZamikov; var k = najx % stZamikov

        // Sledenje nazaj po blokih (vrstice bloka preracunamo iz shranjene tocke).
        val izbranoR = IntArray(n); val izbranoK = IntArray(n)
        // Vrstice bloka v vnaprej rezerviranih poljih (14. 11. 2026: nova polja na vsako vrstico = 11 MB smeti na poravnavo).
        val blokVrstic = Array(BLOK) { FloatArray(stanj) }
        var i = n - 1
        while (i >= 0) {
            val od = (i / BLOK) * BLOK
            System.arraycopy(tocke[i / BLOK], 0, blokVrstic[0], 0, stanj)
            for (j in od until i) naprej(blokVrstic[j - od], j, blokVrstic[j - od + 1])
            while (i >= od) {
                izbranoR[i] = b; izbranoK[i] = k
                if (i == 0) { i--; break }
                val p = blokVrstic[i - od]
                var naj = p[b * stZamikov + k]; var nb = b; var nk = k
                for (a in 0 until nR) {
                    val d = premik(a, b, s[i])
                    if (abs(d) >= stZamikov) continue
                    val pen = kazen(a, b)
                    for (j in 0 until stZamikov) {
                        val w = p[a * stZamikov + j] - pen - LAM_MS_NA_KORAK * abs(k - (j + d))
                        if (w > naj) { naj = w; nb = a; nk = j }
                    }
                }
                b = nb; k = nk
                i--
            }
        }

        val razmerje = DoubleArray(n); val zamikMs = LongArray(n)
        var skupaj = 0L; var prej = 0L; var po = 0L; var odsekov = 1
        for (j in 0 until n) {
            val r = RAZMERJA[izbranoR[j]]; val o = zamik(izbranoK[j])
            razmerje[red[j]] = r; zamikMs[red[j]] = o
            skupaj += e[j] - s[j]
            prej += pok.prekrivanje(s[j], e[j])
            po += pok.prekrivanje((r * s[j]).roundToLong() + o, (r * e[j]).roundToLong() + o)
            if (j > 0 && (izbranoR[j] != izbranoR[j - 1] || izbranoK[j] != izbranoK[j - 1])) odsekov++
        }
        val d = skupaj.coerceAtLeast(1L).toDouble()
        return Izid(razmerje, zamikMs, prej / d, po / d, odsekov)
    }

    /** Poravnavo uporabimo samo, ce se res ujema z referenco in je bistveno boljsa od izvirnika. */
    fun sprejmi(iz: Izid?, napisov: Int): Boolean =
        iz != null && iz.pokritostPo >= NAJMANJ_POKRITOST && iz.pokritostPo >= iz.pokritostPrej + NAJMANJ_IZBOLJSANJE &&
            iz.odsekov <= 1 + napisov / 20

    // ------------------------------------------------------------------ izbira izdaje

    class Kandidat(val url: String, val jezik: String, val izdaja: String = "", val ujemanje: Boolean = false)

    private val VIR_HD = setOf("bluray", "blu", "bdrip", "brrip", "bdremux", "remux", "web", "webrip", "webdl", "amzn", "nf", "dsnp",
        "hmax", "atvp")
    private val HD = VIR_HD + setOf("720p", "1080p", "2160p", "uhd")
    private val SD = setOf("dsr", "dsrip", "satrip", "pdtv", "tvrip", "dvdrip", "dvdscr", "xvid", "divx", "vhsrip", "dvb", "sdtv",
        "480p", "576p")
    private val TV = setOf("dsr", "dsrip", "satrip", "pdtv", "tvrip", "hdtv", "dvb", "sdtv")
    private val DVD = setOf("dvdrip", "dvd", "dvd5", "dvd9", "dvdscr")
    private val LOCLJIVOST = setOf("480p", "576p", "720p", "1080p", "2160p")
    private val PRIPONE = setOf("srt", "sub", "ass", "ssa", "vtt", "mkv", "mp4", "avi", "m4v", "ts")

    private fun deli(ime: String): List<String> =
        ime.lowercase(java.util.Locale.ROOT).replace("web-dl", "webdl").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    private fun brezPripone(ime: String): String {
        val t = ime.substringAfterLast('/').trim()
        return if (t.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in PRIPONE) t.substringBeforeLast('.') else t
    }

    private fun skupina(ime: String): String {
        val b = brezPripone(ime)
        if ('-' !in b) return ""
        return deli(b.substringAfterLast('-')).firstOrNull().orEmpty()
    }

    private fun druzina(t: List<String>): String = when {
        t.any { it in VIR_HD } -> "hd"
        t.any { it in TV } -> "tv"
        t.any { it in DVD } -> "dvd"
        else -> ""
    }

    /**
     * Kako dobro se izdaja podnapisa ujema z videom: odtis (OpenSubtitles moviehash) 100, enako ime datoteke 90, ista
     * skupina +40, ista vrsta vira (BluRay/WEB, TV, DVD) +20, ista locljivost +10. Iz visine videa: video HD (>= 700 vrstic)
     * in izdaja HD +20, izdaja SD (DSR, XviD, DVDRip) -20 (14. 11. 2026: podnapis DSR na videu HD).
     */
    fun tocke(k: Kandidat, imeDatoteke: String?, visinaVidea: Int): Int {
        if (k.ujemanje) return 100
        val izd = k.izdaja.trim()
        if (izd.isEmpty()) return 0
        val ti = deli(brezPripone(izd))
        var t = 0
        val ime = imeDatoteke?.trim().orEmpty()
        if (ime.isNotEmpty()) {
            val td = deli(brezPripone(ime))
            if (td.isNotEmpty() && td == ti) return 90
            val si = skupina(izd); val sd = skupina(ime)
            if (si.isNotEmpty() && si == sd) t += 40
            val di = druzina(ti); val dd = druzina(td)
            if (di.isNotEmpty() && di == dd) t += 20
            val li = ti.firstOrNull { it in LOCLJIVOST }; val ld = td.firstOrNull { it in LOCLJIVOST }
            if (li != null && li == ld) t += 10
        }
        if (visinaVidea >= 700) {
            val hd = ti.any { it in HD }; val sdz = ti.any { it in SD }
            if (hd && !sdz) t += 20 else if (sdz && !hd) t -= 20
        }
        return t
    }

    /** Najboljsi podnapis v [jezik]; ob enakih tockah ostane vrstni red dodatka (prvi). */
    fun najboljsi(kandidati: List<Kandidat>, jezik: String, imeDatoteke: String?, visinaVidea: Int): Kandidat? {
        var naj: Kandidat? = null; var najT = Int.MIN_VALUE
        for (k in kandidati) {
            if (k.jezik != jezik) continue
            val t = tocke(k, imeDatoteke, visinaVidea)
            if (t > najT) { naj = k; najT = t }
        }
        return naj
    }

    /**
     * Referenca za poravnavo [izbran]: podnapis (katerega koli jezika) izdaje, ki se z videom ujema bistveno bolje
     * (vsaj 20 tock in vsaj 20 vec od izbranega). Izbran z odtisom ali enakim imenom (>= 90) poravnave ne potrebuje.
     */
    fun referenca(kandidati: List<Kandidat>, izbran: Kandidat, imeDatoteke: String?, visinaVidea: Int): Kandidat? {
        val ti = tocke(izbran, imeDatoteke, visinaVidea)
        if (ti >= 90) return null
        var naj: Kandidat? = null; var najT = Int.MIN_VALUE
        for (k in kandidati) {
            if (k.url == izbran.url) continue
            val t = tocke(k, imeDatoteke, visinaVidea)
            if (t >= 20 && t >= ti + 20 && t > najT) { naj = k; najT = t }
        }
        return naj
    }
}
