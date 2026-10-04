package si.safeer.tv.link

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Safeer Internet Gateway, protokol 2: meje in cisto racunovodstvo (brez Androida, preizkuseno na JVM).
 *
 * Zakaj nadzor pretoka: sredisce Linka napravo, ki ne bere, odklopi (izhodna vrsta najvec 64 okvirjev
 * in 512 KiB - HubStreznik, core/link_ws.py). Tok, ki s streznika bere hitreje, kot Link odteka, zato
 * ne podre samo sebe, ampak povezavo telefona v Link (izmerjeno 4. 10. 2026: prenos 20 MB je pri
 * protokolu 1 po 18 MB obstal, telefon se je moral v Link prijaviti znova).
 *
 * Pravilo je na obeh straneh enako (racunalnik: core/link_internet.py):
 *  - posiljatelj ima do ene naprave na poti najvec [PRORACUN_NAPRAVE] bajtov in [NAJVEC_OKVIRJEV]
 *    okvirjev (vsota vseh tokov) in najvec [OKNO_TOKA] bajtov na tok;
 *  - prejemnik potrdi (`internet.window`, kumulativno stevilo porabljenih bajtov), ko porabi
 *    [POTRDI_PO] bajtov ali ko nima vec nicesar v vrsti. Potrditev je zato najvec toliko, kolikor je
 *    kosov na poti - tudi potrditve ne morejo napolniti vrste sredisca;
 *  - tih tok zadnjo potrditev ponovi ([PONOVI_POTRDITEV_MS], nato vsakic po dvakrat daljsem premoru):
 *    izgubljena potrditev toka ne ustavi za vedno, in stran, ki toka ne pozna vec, odgovori »gone«.
 */
object InternetProtokol {
    const val RAZLICICA = 2
    const val NAJVEC_KOS = 24 * 1024
    const val OKNO_TOKA = 128 * 1024
    const val PRORACUN_NAPRAVE = 256 * 1024
    const val NAJVEC_OKVIRJEV = 20
    const val POTRDI_PO = 32 * 1024
    const val NAJVEC_TOKOV = 64
    const val NAJVEC_TOKOV_NAPRAVE = 48
    const val NEDEJAVNOST_MS = 300_000L
    /** Toliko sme odjemalec poslati, ne da bi cakal potrditev; kdor poslje vec, krsi okno. */
    const val NAJVEC_NEPORABLJENO = OKNO_TOKA + PRORACUN_NAPRAVE
    /** Tih tok ponovi zadnjo potrditev po toliko ms, potem vsakic po dvakrat daljsem premoru (do [PONOVI_NAJVEC_MS]). */
    const val PONOVI_POTRDITEV_MS = 3_000L
    const val PONOVI_NAJVEC_MS = 60_000L
}

/** Koliko bajtov in okvirjev je hkrati na poti do ene naprave (vsota vseh tokov). */
class InternetProracun(
    private val mejaBajtov: Int = InternetProtokol.PRORACUN_NAPRAVE,
    private val mejaOkvirjev: Int = InternetProtokol.NAJVEC_OKVIRJEV
) {
    private val zaklep = ReentrantLock()
    private val prostor = zaklep.newCondition()
    var bajtov = 0; private set
    var okvirjev = 0; private set

    /** Pocaka na prostor za okvir z [n] bajti. False, ko [preklic] vrne true. */
    fun zakupi(n: Int, preklic: () -> Boolean): Boolean = zaklep.withLock {
        while (okvirjev >= mejaOkvirjev || (bajtov > 0 && bajtov + n > mejaBajtov)) {
            if (preklic()) return false
            prostor.await(250, TimeUnit.MILLISECONDS)
        }
        if (preklic()) return false
        bajtov += n
        okvirjev += 1
        true
    }

    fun sprosti(n: Int, stevilo: Int = 1) = zaklep.withLock {
        bajtov = maxOf(0, bajtov - n)
        okvirjev = maxOf(0, okvirjev - stevilo)
        prostor.signalAll()
    }
}

/** Posiljateljeva stran enega toka: koliko je poslano, koliko potrjeno, kateri okvirji so se na poti. */
class OknoPosiljanja(private val okno: Int = InternetProtokol.OKNO_TOKA) {
    private val zaklep = ReentrantLock()
    private val sprememba = zaklep.newCondition()
    private var poslano = 0L
    private var potrjeno = 0L
    private val vLetu = ArrayDeque<LongArray>()   // (konec v toku, bajtov)

    val nepotrjeno: Long get() = zaklep.withLock { poslano - potrjeno }

    /** Pocaka, da je v oknu toka prostor. False, ko [preklic] vrne true. */
    fun pocakaj(preklic: () -> Boolean): Boolean = zaklep.withLock {
        while (poslano - potrjeno >= okno) {
            if (preklic()) return false
            sprememba.await(500, TimeUnit.MILLISECONDS)
        }
        !preklic()
    }

    fun poslal(n: Int) = zaklep.withLock {
        poslano += n
        vLetu.addLast(longArrayOf(poslano, n.toLong()))
    }

    /** Potrditev prejemnika. Vrne (bajti, okvirji), ki jih je treba vrniti proracunu naprave. */
    fun potrdi(bajti: Long): Pair<Int, Int> = zaklep.withLock {
        if (bajti <= potrjeno) return 0 to 0
        potrjeno = minOf(bajti, poslano)
        var b = 0
        var o = 0
        while (vLetu.isNotEmpty() && vLetu.first()[0] <= potrjeno) {
            b += vLetu.removeFirst()[1].toInt()
            o += 1
        }
        sprememba.signalAll()
        b to o
    }

    /** Konec toka: vse, kar je se na poti, gre nazaj v proracun. */
    fun sprostiVse(): Pair<Int, Int> = zaklep.withLock {
        var b = 0
        val o = vLetu.size
        for (v in vLetu) b += v[1].toInt()
        vLetu.clear()
        sprememba.signalAll()
        b to o
    }

    fun prebudi() = zaklep.withLock { sprememba.signalAll() }
}

/** Prejemnikova stran enega toka: kdaj poslati `internet.window`. */
class PotrjevanjePrejema(
    private val potrdiPo: Int = InternetProtokol.POTRDI_PO,
    private val ura: () -> Long = { System.currentTimeMillis() }
) {
    private var porabljeno = 0L
    private var javljeno = 0L
    private var zadnjiGlas = ura()
    private var ponoviPo = InternetProtokol.PONOVI_POTRDITEV_MS

    /** Po vsakem porabljenem kosu. Vrne kumulativno stevilo za `internet.window` ali -1 (se ni treba). */
    @Synchronized
    fun porabil(n: Int, vrstaPrazna: Boolean): Long {
        porabljeno += n
        zadnjiGlas = ura()
        ponoviPo = InternetProtokol.PONOVI_POTRDITEV_MS      // promet tece: ponavljanje spet od zacetka
        if (porabljeno - javljeno >= potrdiPo || vrstaPrazna) {
            javljeno = porabljeno
            return porabljeno
        }
        return -1
    }

    /**
     * Tok je tih: ali je cas, da posiljatelju ponovimo zadnjo potrditev? Vrne kumulativno stevilo ali -1.
     * Premor se po vsaki ponovitvi podvoji, da tiha povezava ne postane stalen promet.
     */
    @Synchronized
    fun ponovitev(): Long {
        val zdaj = ura()
        if (zdaj - zadnjiGlas < ponoviPo) return -1
        zadnjiGlas = zdaj
        ponoviPo = minOf(ponoviPo * 2, InternetProtokol.PONOVI_NAJVEC_MS)
        javljeno = porabljeno
        return porabljeno
    }
}

/** Trajna shramba majhnih vrednosti (na telefonu SharedPreferences, v preizkusih slovar). */
interface ShrambaVrednosti {
    fun niz(kljuc: String): String?
    fun nastavi(kljuc: String, vrednost: String?)
    fun kljuci(predpona: String): List<String>
}

/** Fizicna naprava za identiteto Linka: `n-<kljuc>` brez pripone (-control, -os, -player ...). */
fun fizicnaNapravaInterneta(id: String): String {
    if (!id.startsWith("n-")) return id
    val deli = id.split("-")
    return if (deli.size >= 2) deli[0] + "-" + deli[1] else id
}

/**
 * Katera naprava sme uporabljati internet tega telefona. Zmoznost `internet.gateway` pove samo, da
 * telefon to zna; dovoljenje da uporabnik vsaki napravi posebej, na telefonu.
 */
class InternetDovoljenja(private val shramba: ShrambaVrednosti, private val ura: () -> Long) {
    enum class Stanje(val oznaka: String) { DOVOLJENO("allowed"), ZAVRNJENO("denied"), CAKA("pending"), NEZNANO("unknown") }
    data class Vnos(val naprava: String, val stanje: Stanje, val ime: String, val cas: Long)

    private fun kljuc(naprava: String) = PREDPONA + naprava

    private fun preberi(naprava: String): Vnos? {
        val surovo = shramba.niz(kljuc(naprava)) ?: return null
        val deli = surovo.split('\t', limit = 3)
        if (deli.size < 3) return null
        val stanje = Stanje.values().firstOrNull { it.oznaka == deli[0] } ?: return null
        return Vnos(naprava, stanje, deli[2], deli[1].toLongOrNull() ?: 0L)
    }

    private fun zapisi(v: Vnos) {
        val ime = v.ime.replace('\t', ' ').replace('\n', ' ').trim().take(NAJVEC_IME)
        shramba.nastavi(kljuc(v.naprava), v.stanje.oznaka + "\t" + v.cas + "\t" + ime)
    }

    fun stanje(naprava: String): Stanje = preberi(naprava)?.stanje ?: Stanje.NEZNANO

    /**
     * Naprava je prosila za internet in odlocitve se ni. Vrne true, ce je treba uporabnika vprasati:
     * prvic in potem najvec enkrat na [PONOVI_VPRASANJE_MS] (vprasanje je morda odmaknil).
     */
    @Synchronized
    fun zabeleziProsnjo(naprava: String, ime: String): Boolean {
        if (naprava.isBlank()) return false
        val prej = preberi(naprava)
        if (prej != null && prej.stanje != Stanje.CAKA) return false
        val zdaj = ura()
        // Ura telefona gre lahko tudi nazaj (nastavitev casa): zapis »iz prihodnosti« ne sme utisati vprasanja za ure.
        if (prej != null && (zdaj - prej.cas) in -PONOVI_VPRASANJE_MS until PONOVI_VPRASANJE_MS) return false
        if (prej == null) pospravi()
        zapisi(Vnos(naprava, Stanje.CAKA, ime.ifBlank { prej?.ime ?: naprava }, zdaj))
        return true
    }

    @Synchronized
    fun odloci(naprava: String, dovoli: Boolean, ime: String = "") {
        if (naprava.isBlank()) return
        val prej = preberi(naprava)
        zapisi(Vnos(naprava, if (dovoli) Stanje.DOVOLJENO else Stanje.ZAVRNJENO, ime.ifBlank { prej?.ime ?: naprava }, ura()))
    }

    @Synchronized
    fun pozabi(naprava: String) = shramba.nastavi(kljuc(naprava), null)

    fun vsi(): List<Vnos> = shramba.kljuci(PREDPONA).mapNotNull { preberi(it.removePrefix(PREDPONA)) }
        .sortedWith(compareBy({ it.stanje != Stanje.CAKA }, { it.ime.lowercase() }))

    /** Zapis ene naprave (cas zadnje prosnje ali odlocitve); null, ce je ne poznamo. */
    fun vnos(naprava: String): Vnos? = preberi(naprava)

    /**
     * Prosnje brez odlocitve, stare najvec [SVEZA_PROSNJA_MS]: te pokazemo, ko uporabnik odpre Safeer OS.
     * Starejse ostanejo v nastavitvah; z oknom ob vsakem odprtju aplikacije bi samo nadlegovale.
     */
    fun svezeCakajoce(): List<Vnos> {
        val zdaj = ura()
        return vsi().filter { it.stanje == Stanje.CAKA && kotlin.math.abs(zdaj - it.cas) <= SVEZA_PROSNJA_MS }
    }

    /** Najstarejse neodlocene prosnje gredo, da seznam ne raste (odlocitve ostanejo). */
    private fun pospravi() {
        val vsi = vsi()
        if (vsi.size < NAJVEC_VNOSOV) return
        vsi.filter { it.stanje == Stanje.CAKA }.sortedBy { it.cas }.take(vsi.size - NAJVEC_VNOSOV + 1).forEach { pozabi(it.naprava) }
    }

    companion object {
        const val PREDPONA = "perm."
        const val NAJVEC_IME = 64
        const val NAJVEC_VNOSOV = 32
        const val PONOVI_VPRASANJE_MS = 60_000L
        const val SVEZA_PROSNJA_MS = 10 * 60_000L
    }
}

/**
 * Poraba mobilnih podatkov skozi prehod: danes in ta mesec. Steje v pomnilniku, na disk zapise
 * najvec enkrat na [SHRANI_NA_MS] (prej: zapis ob vsakem kosu, do tisockrat na sekundo).
 * Kljuca `cellular_month` / `cellular_bytes` sta ista kot v protokolu 1, da stevec ob nadgradnji ostane.
 */
class InternetPoraba(private val shramba: ShrambaVrednosti, private val ura: () -> Long) {
    private var mesec = shramba.niz("cellular_month")?.toIntOrNull() ?: 0
    private var mesecBajti = shramba.niz("cellular_bytes")?.toLongOrNull() ?: 0L
    private var dan = shramba.niz("cellular_day")?.toIntOrNull() ?: 0
    private var danBajti = shramba.niz("cellular_day_bytes")?.toLongOrNull() ?: 0L
    private var shranjeno = 0L
    private var umazano = false

    private fun naDan(danes: Int) {
        if (danes / 100 != mesec) { mesec = danes / 100; mesecBajti = 0L; umazano = true }
        if (danes != dan) { dan = danes; danBajti = 0L; umazano = true }
    }

    /** Pristeje [n] bajtov na dan [danes] (llllmmdd). Vrne mesecno vsoto. */
    @Synchronized
    fun dodaj(n: Long, danes: Int): Long {
        naDan(danes)
        mesecBajti += n
        danBajti += n
        umazano = true
        if (ura() - shranjeno >= SHRANI_NA_MS) shrani()
        return mesecBajti
    }

    @Synchronized fun mesecno(danes: Int): Long { naDan(danes); return mesecBajti }
    @Synchronized fun dnevno(danes: Int): Long { naDan(danes); return danBajti }

    @Synchronized
    fun shrani() {
        if (!umazano) return
        shramba.nastavi("cellular_month", mesec.toString())
        shramba.nastavi("cellular_bytes", mesecBajti.toString())
        shramba.nastavi("cellular_day", dan.toString())
        shramba.nastavi("cellular_day_bytes", danBajti.toString())
        umazano = false
        shranjeno = ura()
    }

    companion object { const val SHRANI_NA_MS = 2_000L }
}
