package si.safeer.tv.os

import kotlin.system.exitProcess

/**
 * Preizkusi cistih pravil za sinhro v gledalcu na protokolu okvirja 4 (ZaslonSinhro, ZaslonZvok). Brez Androida.
 * Slog: p(pogoj, opis), na koncu exitProcess(1), ce kaj pade. Prevajanje: ZaslonSinhro.kt, ZaslonZvok.kt in ta datoteka.
 */

private var uspelih = 0
private var padlih = 0

private fun p(pogoj: Boolean, opis: String) {
    if (pogoj) { uspelih++; println("  OK     $opis") } else { padlih++; println("  PADLO  $opis") }
}

private fun b(besedilo: String): ByteArray = besedilo.toByteArray(Charsets.UTF_8)

private fun absL(x: Long): Long = if (x < 0L) -x else x

private fun zigJson() {
    println("razcleniZig (okvir 4)")
    // Natanko oblika z racunalnika: json.dumps(..., separators=(",", ":")).
    p(ZaslonSinhro.razcleniZig(b("{\"vrsta\":2,\"zig_ms\":1000,\"bajt\":0}")) == ZaslonSinhro.Zig(2, 1000L, 0L), "veljaven zvok")
    p(ZaslonSinhro.razcleniZig(b("{\"vrsta\":1,\"zig_ms\":500,\"bajt\":0}")) == ZaslonSinhro.Zig(1, 500L, 0L), "veljavna slika")
    p(ZaslonSinhro.razcleniZig(b("{ \"bajt\": 103, \"vrsta\": 2, \"zig_ms\": 1021 }")) == ZaslonSinhro.Zig(2, 1021L, 103L),
        "presledki in drug vrstni red")
    p(ZaslonSinhro.razcleniZig(b("{\"vrsta\":2,\"zig_ms\":7,\"bajt\":4,\"nov\":\"x\"}")) == ZaslonSinhro.Zig(2, 7L, 4L),
        "neznano polje se prezre")
    p(ZaslonSinhro.razcleniZig(b("{\"vrsta\":2,\"zig_ms\":1000}")) == null, "manjka bajt")
    p(ZaslonSinhro.razcleniZig(b("{\"zig_ms\":1000,\"bajt\":0}")) == null, "manjka vrsta")
    p(ZaslonSinhro.razcleniZig(b("{\"vrsta\":2,\"bajt\":0}")) == null, "manjka zig_ms")
    p(ZaslonSinhro.razcleniZig(b("{\"vrsta\":3,\"zig_ms\":1000,\"bajt\":0}")) == null, "napacna vrsta 3")
    p(ZaslonSinhro.razcleniZig(b("{\"vrsta\":0,\"zig_ms\":1000,\"bajt\":0}")) == null, "napacna vrsta 0")
    p(ZaslonSinhro.razcleniZig(b("{\"vrsta\":2,\"zig_ms\":\"1000\",\"bajt\":0}")) == null, "ne-stevilo: niz")
    p(ZaslonSinhro.razcleniZig(b("{\"vrsta\":2,\"zig_ms\":1000.5,\"bajt\":0}")) == null, "ne-stevilo: decimalno")
    p(ZaslonSinhro.razcleniZig(b("{\"vrsta\":2,\"zig_ms\":true,\"bajt\":0}")) == null, "ne-stevilo: true")
    p(ZaslonSinhro.razcleniZig(b("{\"vrsta\":2,\"zig_ms\":99999999999999999999,\"bajt\":0}")) == null, "preliv Long")
    p(ZaslonSinhro.razcleniZig(b("{\"vrsta\":2,\"zig_ms\":-1,\"bajt\":0}")) == null, "negativen zig_ms")
    p(ZaslonSinhro.razcleniZig(b("{\"vrsta\":2,\"zig_ms\":1000,\"bajt\":-4}")) == null, "negativen bajt")
    val rep = "\"vrsta\":2,\"zig_ms\":1000,\"bajt\":0}"          // 33 B
    p(ZaslonSinhro.razcleniZig(b("{" + " ".repeat(166) + rep)) == ZaslonSinhro.Zig(2, 1000L, 0L), "tocno 200 B gre")
    p(ZaslonSinhro.razcleniZig(b("{" + " ".repeat(167) + rep)) == null, "predolg: 201 B")
    p(ZaslonSinhro.razcleniZig(ByteArray(0)) == null, "prazno")
    p(ZaslonSinhro.razcleniZig(b("{\"vrsta\":2,\"vrsta\":1,\"zig_ms\":1,\"bajt\":0}")) == null, "podvojen kljuc")
    p(ZaslonSinhro.razcleniZig(b("{\"vrsta\":2,\"zig_ms\":1,\"bajt\":0}x")) == null, "smeti za objektom")
    p(ZaslonSinhro.razcleniZig(b("{\"vrsta\":{\"a\":1},\"zig_ms\":1,\"bajt\":0}")) == null, "gnezden objekt")
    p(ZaslonSinhro.razcleniZig(b("{\"vrsta\":2 \"zig_ms\":1,\"bajt\":0}")) == null, "manjka vejica")
    p(ZaslonSinhro.razcleniZig(b("{}")) == null, "prazen objekt")
}

private fun zigVelja() {
    println("zigVelja (stevec bajtov)")
    val z = ZaslonSinhro.Zig(2, 1010L, 100L)
    p(ZaslonSinhro.zigVelja(z, 100L), "100 prejetih = bajt 100")
    p(!ZaslonSinhro.zigVelja(z, 103L), "drug okvir -> ne velja")
}

private fun zveznost() {
    println("zvezenOdsek (bajt -> vrzel > 40 ms)")
    // 48 kHz stereo: 4 B na vzorec; 1920 B = 480 vzorcev = 10 ms.
    p(ZaslonZvok.zvezenOdsek(1000L, 0L, 1010L, 1920L, 48_000, 2, false), "10 ms po 10 ms: zvezno")
    p(ZaslonZvok.zvezenOdsek(1000L, 0L, 1050L, 1920L, 48_000, 2, false), "razlika 40 ms: se zvezno")
    p(!ZaslonZvok.zvezenOdsek(1000L, 0L, 1051L, 1920L, 48_000, 2, false), "razlika 41 ms: nov odsek")
    p(!ZaslonZvok.zvezenOdsek(1000L, 0L, 1300L, 1920L, 48_000, 2, false), "tisina 290 ms ni bila poslana: nov odsek")
    p(!ZaslonZvok.zvezenOdsek(1000L, 1920L, 1010L, 0L, 48_000, 2, false), "stevec nazaj: nov odsek")
    p(ZaslonZvok.zvezenOdsek(1000L, 0L, 969L, 1920L, 48_000, 2, false) == false, "zig 41 ms prezgodaj: nov odsek")
    // Primer z racunalnika: zigi 1000, 1010, 1021 pri bajt 0, 100, 103 (lazni kosi): 100 B = 25 vzorcev = 0 ms.
    p(ZaslonZvok.zvezenOdsek(1000L, 0L, 1010L, 100L, 48_000, 2, false), "1010 proti 1000 + 0: razlika 10 <= 40")
}

private fun korak() {
    println("samodejniKorak, delitev zamika, mirovanje")
    p(ZaslonSinhro.samodejniKorak(0L, 80L) == 10L, "slika naprej -> +10")
    p(ZaslonSinhro.samodejniKorak(0L, -80L) == -10L, "zvok naprej -> -10")
    p(ZaslonSinhro.samodejniKorak(30L, 0L) == 30L, "brez potrebe ostane")
    p(ZaslonSinhro.samodejniKorak(400L, 80L) == 400L, "meja +400")
    p(ZaslonSinhro.samodejniKorak(-400L, -80L) == -400L, "meja -400")
    p(ZaslonSinhro.zamikSlikeMs(30L) == 30L && ZaslonSinhro.zamikZvokaMs(30L) == 0L, "+30 -> slika 30, zvok 0")
    p(ZaslonSinhro.zamikSlikeMs(-20L) == 0L && ZaslonSinhro.zamikZvokaMs(-20L) == 20L, "-20 -> slika 0, zvok 20")
    p(ZaslonSinhro.smeMeriti(123L, null), "brez koraka se sme meriti")
    p(!ZaslonSinhro.smeMeriti(1_199_000_000L, 1_000_000_000L), "199 ms po koraku se ne meri")
    p(ZaslonSinhro.smeMeriti(1_200_000_000L, 1_000_000_000L), "200 ms po koraku se meri")
}

private fun casZvoka() {
    println("okvirOb, zigOkvirja")
    p(ZaslonSinhro.okvirOb(10_500_000_000L, 48_000L, 10_000_000_000L, 48_000) == 72_000L, "+0,5 s = +24000 okvirjev")
    p(ZaslonSinhro.okvirOb(9_990_000_000L, 48_000L, 10_000_000_000L, 48_000) == 47_520L, "-10 ms = -480 okvirjev")
    p(ZaslonSinhro.okvirOb(11_000_000_001L, 48_000L, 10_000_000_000L, 48_000) == null, "zig starejsi od 1 s -> null")
    p(ZaslonSinhro.okvirOb(10_000_000_000L, 1L, 10_000_000_000L, 0) == null, "hz 0 -> null")
    p(ZaslonSinhro.zigOkvirja(72_480L, 48_000, 72_000L, 5_000L) == 5_010L, "+480 okvirjev = +10 ms")
    p(ZaslonSinhro.zigOkvirja(71_999L, 48_000, 72_000L, 5_000L) == null, "pred odsekom -> null")
    p(ZaslonSinhro.zigOkvirja(72_000L + 96_000L, 48_000, 72_000L, 5_000L) == 7_000L, "2000 ms se velja")
    p(ZaslonSinhro.zigOkvirja(72_000L + 96_001L, 48_000, 72_000L, 5_000L) == null, "dlje od 2000 ms -> null")
    val okvir = ZaslonSinhro.okvirOb(10_010_000_000L, 72_000L, 10_000_000_000L, 48_000)
    p(okvir == 72_480L, "okvir ob prikazu")
    val zigZ = if (okvir != null) ZaslonSinhro.zigOkvirja(okvir, 48_000, 72_000L, 5_000L) else null
    p(zigZ != null && zigZ == 5_010L && ZaslonSinhro.odmik(5_030L, zigZ) == 20L, "odmik +20 (slika naprej)")
}

private fun slika() {
    println("ujemaPts, jeZastojSlike, jeKljucnaEnota")
    p(ZaslonSinhro.ujemaPts(5_000_000L, 5_000_000L), "enak pts")
    p(ZaslonSinhro.ujemaPts(5_001_000L, 5_000_000L), "+1 ms (prelom ms med branjema)")
    p(!ZaslonSinhro.ujemaPts(5_001_001L, 5_000_000L), "vec kot 1 ms -> ne")
    p(!ZaslonSinhro.ujemaPts(4_999_999L, 5_000_000L), "pred kljucem -> ne")
    p(ZaslonSinhro.jeZastojSlike(11_000L, 10_000L, 3L), "1000 ms brez izhoda, 3 slike noter -> zastoj")
    p(!ZaslonSinhro.jeZastojSlike(10_999L, 10_000L, 3L), "999 ms -> se ne")
    p(!ZaslonSinhro.jeZastojSlike(20_000L, 10_000L, 2L), "2 sliki v dekoderju (zadrzevanje) -> ne")
    p(ZaslonSinhro.jeKljucnaEnota(false, 5) && !ZaslonSinhro.jeKljucnaEnota(false, 1), "H.264: 5 da, 1 ne")
    p(ZaslonSinhro.jeKljucnaEnota(true, 19) && ZaslonSinhro.jeKljucnaEnota(true, 21) && !ZaslonSinhro.jeKljucnaEnota(true, 1),
        "HEVC: 19 in 21 da, 1 ne")
}

private fun tabela() {
    println("ZigTabela")
    val t = ZaslonSinhro.ZigTabela(3)
    p(t.natancno(1L) == null && t.navzdol(100L) == null, "prazna -> null")
    t.dodaj(10L, 100L); t.dodaj(20L, 200L); t.dodaj(30L, 300L)
    p(t.natancno(20L) == 200L, "natancno najde")
    p(t.natancno(25L) == null, "natancno ne ugiba")
    p(t.navzdol(25L) == (20L to 200L), "navzdol: najvecji <= kljuc")
    p(t.navzdol(9L) == null, "navzdol pred prvim -> null")
    t.dodaj(40L, 400L)
    p(t.natancno(10L) == null && t.natancno(40L) == 400L, "krozno: najstarejsi prepisan")
    t.dodaj(40L, 400L)
    p(t.natancno(40L) == 400L, "isti kljuc, isti zig (rezine iste slike) ostane")
    t.dodaj(40L, 416L)
    p(t.natancno(40L) == null, "isti kljuc, drug zig -> dvoumen")
    p(t.navzdol(1_000L) == (30L to 300L), "navzdol preskoci dvoumnega")
    t.ponastavi()
    p(t.natancno(30L) == null, "ponastavi izbrise")
    var napaka = false
    try { ZaslonSinhro.ZigTabela(0) } catch (_: IllegalArgumentException) { napaka = true }
    p(napaka, "velikost 0 zavrnjena")
}

private fun kljuc() {
    println("kljucZamika")
    p(ZaslonSinhro.kljucZamika("1920x1080") == "zaslon_zvok_zamik_1920x1080", "veljaven zaslon")
    p(ZaslonSinhro.kljucZamika("") == "zaslon_zvok_zamik_privzeto", "prazen -> privzeto")
    p(ZaslonSinhro.kljucZamika("1920/1080") == "zaslon_zvok_zamik_privzeto", "neveljaven znak -> privzeto")
    p(!ZaslonSinhro.kljucZamika("1920x1080").startsWith("zaslon_rob_"), "ne trci s kljucem roba")
}

/** Zaprta zanka: izmerjen odmik = osnova - zamik (zamik slike odmik zmanjsa, zamik zvoka poveca). */
private fun simuliraj(osnova: Long, rocni: Long, korakovNajvec: Int): Pair<Long, Int> {
    val k = ZaslonSinhro.Krmilnik()
    var zdajNs = 0L
    var prejsnji = 0L
    var korakov = 0
    var najvecjiSkok = 0L
    for (n in 0 until 20_000) {
        zdajNs += 16_666_667L
        val sum = if (n % 7 == 0) 45L else (n % 5).toLong() - 2L
        val nov = k.meritev(osnova - k.zamikMs + sum, zdajNs, rocni)
        if (nov != null) {
            korakov++
            najvecjiSkok = maxOf(najvecjiSkok, absL(nov - prejsnji))
            prejsnji = nov
        }
        if (korakov > korakovNajvec) return k.zamikMs to -1
    }
    return k.zamikMs to (if (najvecjiSkok > ZaslonSinhro.KORAK_ROCNO_MS) -2 else korakov)
}

private fun krmilnik() {
    println("Krmilnik (zaprta zanka)")
    val (z1, k1) = simuliraj(80L, 0L, 20)
    p(k1 > 0, "slika 80 ms naprej: konca, koraki po 10 ms (koraki $k1)")
    p(absL(80L - z1) <= ZaslonSinhro.MRTVO_OBMOCJE_MS && ZaslonSinhro.usklajen(80L - z1), "ostanek <= 10 ms (zamik $z1)")
    val (z2, k2) = simuliraj(-60L, 0L, 20)
    p(k2 > 0 && z2 < 0L && absL(-60L - z2) <= ZaslonSinhro.MRTVO_OBMOCJE_MS, "zvok 60 ms naprej -> zamik zvoka (zamik $z2)")
    val (z3, k3) = simuliraj(80L, 60L, 20)
    p(k3 > 0 && absL(80L - z3 - 60L) <= ZaslonSinhro.MRTVO_OBMOCJE_MS, "rocni +60: ostanek proti 0 (zamik $z3)")
    val (z4, k4) = simuliraj(900L, 0L, 60)
    p(k4 == 40 && z4 == ZaslonSinhro.NAJVEC_ZAMIK_MS, "odmik nad mejo: 40 korakov do +400")
    val (z5, k5) = simuliraj(5L, 0L, 5)
    p(k5 == 0 && z5 == 0L, "v mrtvem obmocju brez koraka")
    val k = ZaslonSinhro.Krmilnik()
    var koraki = 0
    for (n in 1..60) if (k.meritev(80L - k.zamikMs, n * 16_666_667L, 0L) != null) koraki++
    p(koraki == 4 && k.zamikMs == 40L, "v 1 s natanko 4 koraki po 10 ms, bilo $koraki, zamik ${k.zamikMs}")
}

private fun meje() {
    println("ZaslonZvok: izpust, bajti, zaostanek")
    p(ZaslonZvok.mejaIzpustaMs(0L) == 120L, "brez namernega zamika meja 120 (2 x 60)")
    p(ZaslonZvok.mejaIzpustaMs(200L) == 320L, "namerni 200 -> meja 320")
    p(ZaslonZvok.zaIzpustMs(250L, 200L) == 0L, "namerno zakasnjen zvok (250 <= 320) se ne izpusti")
    p(ZaslonZvok.zaIzpustMs(250L, 0L) == 190L, "brez namernega: 250 -> izpust 190 (na 60)")
    p(ZaslonZvok.zaIzpustMs(120L, 0L) == 0L, "tocno na meji ne izpusti")
    p(ZaslonZvok.zaIzpustMs(400L, -50L) == 340L, "negativen namerni steje kot 0")
    p(ZaslonZvok.bajtovZaMs(10L, 48_000, 2, false) == 1_920, "10 ms stereo 48 kHz = 480 x 4 B")
    p(ZaslonZvok.bajtovZaMs(10L, 44_100, 1, false) == 882, "10 ms mono 44,1 kHz = 441 x 2 B")
    p(ZaslonZvok.bajtovZaMs(0L, 48_000, 2, false) == 0 && ZaslonZvok.bajtovZaMs(-5L, 48_000, 2, false) == 0, "0 in negativno -> 0")
    p(ZaslonZvok.bajtovZaMs(Long.MAX_VALUE / 100_000L, 48_000, 2, false) == 2_147_483_644, "omejeno na Int, poravnano na 4")
    p(ZaslonZvok.vTrackuOkvirjev(10_000L, 7_120) == 2_880L, "2880 okvirjev v AudioTracku")
    p(ZaslonZvok.vTrackuOkvirjev(0x1_0000_0100L, 0xFFFF_FF00L.toInt()) == 0x200L, "preliv 32-bitne glave")
    p(ZaslonZvok.vTrackuOkvirjev(100L, 200) == 0L, "glava pred zapisanim (nemogoce) -> 0")
    p(ZaslonZvok.zaostanekMs(2_880L, 1_920, 48_000, 2, false) == 70L, "60 ms v AudioTracku + 10 ms v vrsti")
    p(ZaslonZvok.zaostanekMs(0L, 0, 0, 2, false) == 0L, "prazno, hz 0 -> 0 brez deljenja z nic")
    p(ZaslonZvok.zigPoOdmiku(5_000L, 1_920, 48_000, 2, false) == 5_010L, "1920 B v okvir = +10 ms")
    p(ZaslonZvok.zigPoOdmiku(5_000L, 0, 48_000, 2, false) == 5_000L, "brez odmika isti zig")
}

private fun vrsta() {
    println("VrstaZvoka (WRITE_NON_BLOCKING: ostanek ostane; odseki)")
    val v = ZaslonZvok.VrstaZvoka()
    val a = ByteArray(100) { it.toByte() }
    v.dodaj(a, 0, 100, 1_000L, false)
    v.dodaj(ByteArray(40), 0, 40, 1_025L, false)
    p(v.bajtov == 140, "dva kosa: 100 + 40")
    val prvi = v.prvi()
    p(prvi != null && prvi.od == 0 && prvi.konec == 100 && prvi.zig == 1_000L && prvi.novOdsek, "prvi kos vedno zacne odsek")
    v.porabi(60)
    val isti = v.prvi()
    p(isti != null && isti.od == 60 && isti.zacetek == 0 && v.bajtov == 80, "delni zapis: ostanek 40 B ostane")
    v.porabi(40)
    val drugi = v.prvi()
    p(drugi != null && drugi.zig == 1_025L && !drugi.novOdsek && v.bajtov == 40, "drugi kos zvezen")
    v.porabi(0)
    p(v.bajtov == 40, "r == 0 ne porabi nic")
    v.dodaj(ByteArray(50), 0, 50, 2_000L, false)
    p(v.izpusti(60) == 60 && v.bajtov == 30, "izpust cez dva kosa")
    val tretji = v.prvi()
    p(tretji != null && tretji.zig == 2_000L && tretji.od == 20 && tretji.novOdsek, "po izpustu nov odsek")
    p(v.izpusti(1_000) == 30 && v.bajtov == 0 && v.prvi() == null, "izpust vec, kot je -> vse")
    v.dodaj(a, 0, 10, 3_000L, false)
    val poPraznem = v.prvi()
    p(poPraznem != null && poPraznem.novOdsek, "izpust do praznega: naslednji kos nov odsek")
    v.dodaj(a, 90, 20, 1L, false)
    p(v.bajtov == 10, "kos cez konec tabele zavrnjen")
    v.porabi(10)
    v.dodaj(ByteArray(8), 0, 8, null, true)
    v.prekini()
    v.dodaj(a, 0, 4, 4_000L, false)
    v.porabi(8)
    val poTisini = v.prvi()
    p(poTisini != null && poTisini.zig == 4_000L && poTisini.novOdsek, "po tisini (prekini) nov odsek")
    v.pocisti()
    v.dodaj(a, 0, 4, 5_000L, false)
    val poCiscenju = v.prvi()
    p(poCiscenju != null && poCiscenju.novOdsek && v.bajtov == 4, "po pocisti nov odsek")
    val w = ZaslonZvok.VrstaZvoka()
    w.dodaj(ByteArray(200), 0, 200, 0L, false)
    var zapisano = 0
    var klicev = 0
    while (w.bajtov > 0 && klicev < 100) {
        klicev++
        val kos = w.prvi()
        val r = if (kos == null) 0 else minOf(48, kos.konec - kos.od)
        zapisano += r
        w.porabi(r)
    }
    p(zapisano == 200 && klicev == 5, "200 B v 5 delnih zapisih brez izgube")
}

fun main() {
    zigJson()
    zigVelja()
    zveznost()
    korak()
    casZvoka()
    slika()
    tabela()
    kljuc()
    krmilnik()
    meje()
    vrsta()
    println("Uspelih $uspelih, padlih $padlih")
    if (padlih > 0) exitProcess(1)
}
