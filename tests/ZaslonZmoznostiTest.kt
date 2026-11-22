import si.safeer.tv.os.ZaslonZmoznosti
import kotlin.system.exitProcess

// Preizkus brez JUnit (kot ostali ZaslonXxxTest): prvi neuspeh konca z izhodno kodo 1.
private var preverjenih = 0
private fun p(pogoj: Boolean, opis: String) {
    preverjenih++
    if (!pogoj) { println("NAPAKA: $opis"); exitProcess(1) }
}

private class D(
    override val mime: String,
    override val najSirina: Int,
    override val najVisina: Int,
    override val koder: Boolean = false,
    override val strojni: Boolean? = true,
    private val podpiraFn: (Int, Int) -> Boolean = { w, h -> w <= najSirina && h <= najVisina },
) : ZaslonZmoznosti.Dekoder {
    override fun podpira(sirina: Int, visina: Int) = podpiraFn(sirina, visina)
}

private fun viri(vararg d: ZaslonZmoznosti.Dekoder) = ZaslonZmoznosti.ViriDekoderjev { d.toList() }

private val AVC_1080 = D("video/avc", 1920, 1088)
private val AVC_4K = D("video/avc", 4096, 2304)
private val HEVC_1080 = D("video/hevc", 1920, 1088)
private val HEVC_4K = D("video/hevc", 4096, 2304)
private val PRICAKOVAN_4K = ZaslonZmoznosti.Izid(listOf("hevc", "h264"),
    mapOf("hevc" to listOf(3840, 2160), "h264" to listOf(1920, 1080)))

fun main() {
    // 1. Brez HEVC: nic ne dodamo, racunalnik ostane pri H.264 kot doslej (tudi ce H.264 zmore 4K).
    val brez = ZaslonZmoznosti.sestavi(viri(AVC_1080, AVC_4K))
    p(brez.prazen, "brez dekoderja HEVC mora biti izid prazen, je $brez")

    // 2. HEVC samo do 1080p: ne navedemo ga.
    p(ZaslonZmoznosti.sestavi(viri(AVC_1080, HEVC_1080)).prazen, "HEVC samo 1080p ne sme biti naveden")
    // isSizeSupported(3840,2160) zavrne, ceprav sta meji dovolj veliki (npr. samo pokoncno).
    val zavrne = D("video/hevc", 3840, 3840, podpiraFn = { _, _ -> false })
    p(ZaslonZmoznosti.sestavi(viri(zavrne)).prazen, "HEVC, ki zavrne 3840x2160, ne sme biti naveden")
    // isSizeSupported pravi da, a getVideoCapabilities meja sirine je pod 3840: ne navedemo.
    val premajhen = D("video/hevc", 2048, 2160, podpiraFn = { _, _ -> true })
    p(ZaslonZmoznosti.sestavi(viri(premajhen)).prazen, "HEVC z najvecjo sirino 2048 ne sme biti naveden")
    val prenizek = D("video/hevc", 3840, 1080, podpiraFn = { _, _ -> true })
    p(ZaslonZmoznosti.sestavi(viri(prenizek)).prazen, "HEVC z najvecjo visino 1080 ne sme biti naveden")

    // 3. HEVC 4K: natanko oblika, ki jo bere racunalnik.
    val z4k = ZaslonZmoznosti.sestavi(viri(AVC_1080, HEVC_4K))
    p(z4k == PRICAKOVAN_4K, "HEVC 4K: pricakovan $PRICAKOVAN_4K, je $z4k")
    p(z4k.codecs.first() == "hevc", "HEVC mora biti prvi (prednost)")
    p(z4k.maxsize.keys.toList() == listOf("hevc", "h264"), "kljuci maxsize: ${z4k.maxsize.keys}")
    p(z4k.maxsize.values.all { it.size == 2 }, "vsaka velikost je [sirina, visina]")
    p(z4k.codecs.all { it == it.lowercase() }, "imena kodekov z malimi crkami")
    // Ime mime z velikimi crkami je isti kodek.
    p(ZaslonZmoznosti.sestavi(viri(D("VIDEO/HEVC", 3840, 2160))) == PRICAKOVAN_4K, "mime brez obcutljivosti za crke")
    // Neznano, ali je strojni (pred Androidom 10): ne izlocimo.
    p(ZaslonZmoznosti.sestavi(viri(D("video/hevc", 3840, 2160, strojni = null))) == PRICAKOVAN_4K,
        "HEVC 4K z neznano strojnostjo je naveden")

    // 4. Prazen seznam dekoderjev.
    p(ZaslonZmoznosti.sestavi(viri()).prazen, "prazen seznam -> prazen izid")

    // 5. Napaka vmesnika: varen privzetek (brez kljucev), ne izjema.
    val napaka = ZaslonZmoznosti.sestavi(ZaslonZmoznosti.ViriDekoderjev { throw IllegalStateException("MediaCodecList") })
    p(napaka.prazen, "napaka seznama -> prazen izid, je $napaka")
    // Napaka enega dekoderja ne skrije drugega, ki 4K zmore.
    val pokvarjen = D("video/hevc", 4096, 2304, podpiraFn = { _, _ -> throw IllegalArgumentException("caps") })
    p(ZaslonZmoznosti.sestavi(viri(pokvarjen, HEVC_4K)) == PRICAKOVAN_4K, "napaka enega dekoderja ne ustavi ostalih")
    p(ZaslonZmoznosti.sestavi(viri(pokvarjen)).prazen, "edini pokvarjen dekoder -> prazno")

    // 6. Vec dekoderjev: koder 4K, programski 4K in strojni 1080p ne zadoscajo.
    val koder4k = D("video/hevc", 4096, 2304, koder = true)
    val programski4k = D("video/hevc", 4096, 2304, strojni = false)
    p(ZaslonZmoznosti.sestavi(viri(AVC_4K, koder4k, programski4k, HEVC_1080)).prazen,
        "koder/programski 4K in strojni 1080p ne smejo dati HEVC")
    // ... dodan strojni 4K dekoder kjerkoli v seznamu: HEVC.
    p(ZaslonZmoznosti.sestavi(viri(HEVC_4K, AVC_4K, koder4k, programski4k, HEVC_1080)) == PRICAKOVAN_4K,
        "strojni HEVC 4K med vec dekoderji mora biti najden")
    p(ZaslonZmoznosti.sestavi(viri(AVC_4K, koder4k, programski4k, HEVC_1080, HEVC_4K)) == PRICAKOVAN_4K,
        "strojni HEVC 4K na koncu seznama mora biti najden")

    // HEVC je v tej razlicici ze odpovedal (ZaslonKodek.hevcDovoljen): ne ponudimo ga.
    p(ZaslonZmoznosti.sestavi(viri(HEVC_4K), hevcDovoljen = false).prazen, "hevcDovoljen=false -> prazno")

    // Meje racunalnika (8 nizov, 16 znakov), sicer racunalnik "codecs" prezre.
    p(z4k.codecs.size <= ZaslonZmoznosti.KODEKI_NAJVEC, "najvec 8 kodekov")
    p(z4k.codecs.all { it.length <= ZaslonZmoznosti.KODEK_NAJVEC_ZNAKOV }, "vsak kodek najvec 16 znakov")

    println("OK: $preverjenih preverb")
}
