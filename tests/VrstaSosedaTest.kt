package si.safeer.tv.cast

/**
 * Izhodna vrsta odhodne sosednje povezave (VrstaSoseda, HubMesh nad OkHttp): cel seznam starejsemu sosedu se v vrsti
 * nadomesti po kljucu, vse drugo gre v vrstnem redu, sosed, ki ne bere, izpade. OkHttp nadomesti lazen posiljatelj.
 */
object VrstaSosedaTest {
    /** Kljuc nadomescanja, kot ga da SeznamSprememb.razposlji (cel seznam naprav). */
    private const val KLJUC = "cast.devices"

    private fun trdi(pogoj: Boolean, sporocilo: String) {
        if (!pogoj) throw AssertionError(sporocilo)
    }

    /** Lazni OkHttp: poslano po vrsti, velikost njegove vrste nastavi preizkus. */
    private class LazniOkhttp {
        val poslano = ArrayList<String>()
        var vVrsti = 0L
        var odprt = true
        fun poslji(b: String): Boolean { if (!odprt) return false; poslano.add(b); vVrsti += b.length; return true }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // 1. OkHttp pise sproti: sporocila gredo naravnost, nic ne caka.
        run {
            val ok = LazniOkhttp()
            val opravila = ArrayList<() -> Unit>()
            val v = VrstaSoseda(ok::poslji, { ok.vVrsti }, { }, { _, f -> opravila.add(f) })
            for (i in 1..5) trdi(v.dodaj("s$i", if (i % 2 == 0) KLJUC else null), "dodaj $i")
            trdi(ok.poslano == listOf("s1", "s2", "s3", "s4", "s5"), "naravnost: ${ok.poslano}")
            trdi(v.cakajocih() == 0 && opravila.isEmpty(), "nic ne caka")
            println("1. OkHttp pise sproti: 5 od 5 naravnost, nic ne caka: OK")
        }

        // 2. OkHttp zaostaja (sosed bere pocasi): 30 celih seznamov starejsemu sosedu -> caka en, zadnji; sporocila brez
        //    kljuca ostanejo vsa in v vrstnem redu, cel seznam ostane na mestu prvega cakajocega.
        run {
            val ok = LazniOkhttp()
            val opravila = ArrayList<() -> Unit>()
            val v = VrstaSoseda(ok::poslji, { ok.vVrsti }, { }, { _, f -> opravila.add(f) })
            ok.vVrsti = VrstaSoseda.V_OKHTTP
            v.dodaj("ukaz-a")
            for (i in 1..30) v.dodaj("seznam-$i", KLJUC)
            v.dodaj("ukaz-b")
            trdi(v.cakajocih() == 3, "caka ${v.cakajocih()} (pricakovano 3)")
            trdi(v.nadomestenih == 29L, "nadomestenih ${v.nadomestenih}")
            trdi(opravila.size == 1, "praznjenje je nacrtovano enkrat (${opravila.size})")
            ok.vVrsti = 0
            opravila.removeAt(0)()
            trdi(ok.poslano == listOf("ukaz-a", "seznam-30", "ukaz-b"), "vrstni red po praznjenju: ${ok.poslano}")
            trdi(v.cakajocih() == 0 && opravila.isEmpty(), "po praznjenju nic ne caka")
            println("2. pocasen sosed: 30 celih seznamov -> 1 (zadnji), ukaza v vrstnem redu: OK")
        }

        // 3. Brez kljuca se nic ne nadomesca (sosed, ki zna spremembe): vsa sporocila v vrstnem redu, tudi ko OkHttp
        //    vzame po nekaj naenkrat (100 KiB sporocila, v OkHttp najvec V_OKHTTP).
        run {
            val ok = LazniOkhttp()
            val opravila = ArrayList<() -> Unit>()
            val v = VrstaSoseda(ok::poslji, { ok.vVrsti }, { }, { _, f -> opravila.add(f) })
            ok.vVrsti = VrstaSoseda.V_OKHTTP
            val velik = "y".repeat(100 * 1024)
            for (i in 1..30) v.dodaj("sprememba-$i:$velik")
            trdi(v.cakajocih() == 30 && v.nadomestenih == 0L, "brez kljuca caka ${v.cakajocih()}")
            var krogov = 0
            while (opravila.isNotEmpty()) {
                ok.vVrsti = 0          // OkHttp je poslal, kar je imel
                opravila.removeAt(0)()
                krogov++
            }
            trdi(ok.poslano.map { it.substringBefore(':') } == (1..30).map { "sprememba-$it" },
                "brez kljuca: ${ok.poslano.size} od 30 ali premesano")
            trdi(krogov > 1, "praznjenje po delih ($krogov krogov)")
            println("3. brez kljuca: 30 od 30 v vrstnem redu, po delih ($krogov krogov praznjenja): OK")
        }

        // 4. Sosed ne bere: vrsta prepolna -> povezava se zapre, nadaljnja sporocila vrnejo false.
        run {
            val ok = LazniOkhttp()
            var zaprta = 0
            val v = VrstaSoseda(ok::poslji, { ok.vVrsti }, { zaprta++ }, { _, _ -> })
            ok.vVrsti = VrstaSoseda.V_OKHTTP
            var sprejetih = 0
            for (i in 1..VrstaSoseda.NAJVEC_SPOROCIL + 5) if (v.dodaj("x$i")) sprejetih++
            trdi(sprejetih == VrstaSoseda.NAJVEC_SPOROCIL, "sprejetih $sprejetih")
            trdi(zaprta == 1, "naPrepolno klican $zaprta-krat")
            trdi(!v.dodaj("po-zaprtju", KLJUC), "po zaprtju ne sprejme")
            println("4. sosed ne bere: po ${VrstaSoseda.NAJVEC_SPOROCIL} sporocilih se povezava zapre: OK")
        }

        // 5. OkHttp zavrne (povezava zaprta): dodaj vrne false, nic ne ostane v vrsti.
        run {
            val ok = LazniOkhttp()
            val v = VrstaSoseda(ok::poslji, { ok.vVrsti }, { }, { _, _ -> })
            ok.odprt = false
            trdi(!v.dodaj("a"), "zaprta povezava ne sprejme")
            trdi(!v.dodaj("b", KLJUC) && v.cakajocih() == 0, "po zavrnitvi nic ne caka")
            println("5. zaprta povezava: OK")
        }
        println("OK VrstaSosedaTest")
    }
}
