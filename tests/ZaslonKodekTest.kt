package si.safeer.tv.os

/** Preizkus [ZaslonKodek]: vrste enot NAL za H.264 in HEVC ter kaj z njimi naredi gledalec zaslona. */
fun main() {
    // H.264: vrsta je spodnjih 5 bitov prvega bajta (SPS 0x67, PPS 0x68, IDR 0x65, P-slika 0x41).
    check(ZaslonKodek.vrstaEnote(false, 0x67) == 7) { "SPS" }
    check(ZaslonKodek.vrstaEnote(false, 0x68) == 8) { "PPS" }
    check(ZaslonKodek.vrstaEnote(false, 0x65) == 5) { "IDR" }
    check(ZaslonKodek.vrstaEnote(false, 0x41) == 1) { "P" }
    check(ZaslonKodek.jeNastavitev(false, 7) && ZaslonKodek.jeNastavitev(false, 8)) { "SPS/PPS sta nastavitev" }
    check(!ZaslonKodek.jeNastavitev(false, 5) && !ZaslonKodek.jeNastavitev(false, 1)) { "slika ni nastavitev" }
    for (v in 0..31) check(!ZaslonKodek.cakaNaSliko(false, v)) { "H.264 ostane, kot je bil: $v" }

    // HEVC: vrsta so biti 1-6 prvega bajta (VPS 0x40, SPS 0x42, PPS 0x44, SEI 0x4E, IDR 0x26, navadna slika 0x02).
    check(ZaslonKodek.vrstaEnote(true, 0x40) == 32) { "VPS" }
    check(ZaslonKodek.vrstaEnote(true, 0x42) == 33) { "SPS" }
    check(ZaslonKodek.vrstaEnote(true, 0x44) == 34) { "PPS" }
    check(ZaslonKodek.vrstaEnote(true, 0x4E) == 39) { "SEI" }
    check(ZaslonKodek.vrstaEnote(true, 0x26) == 19) { "IDR" }
    check(ZaslonKodek.vrstaEnote(true, 0x02) == 1) { "navadna slika" }
    // Nastavitve in spremne enote pocakajo na sliko; slika (vrste 0-31) gre v dekoder takoj, z njimi spredaj.
    for (v in listOf(32, 33, 34, 35, 39, 40)) check(ZaslonKodek.cakaNaSliko(true, v)) { "caka: $v" }
    for (v in listOf(0, 1, 19, 20, 21)) check(!ZaslonKodek.cakaNaSliko(true, v)) { "slika ne caka: $v" }
    // Pri HEVC nobena enota ne gre posebej kot nastavitev (gredo s sliko).
    for (v in 0..40) check(!ZaslonKodek.jeNastavitev(true, v)) { "HEVC brez locenih nastavitev: $v" }

    // Seznam za racunalnik: HEVC spredaj samo s strojnim dekodirnikom; H.264 vedno.
    check(ZaslonKodek.seznam(true) == listOf("hevc", "h264"))
    check(ZaslonKodek.seznam(false) == listOf("h264"))

    // Varovalka: dekoder HEVC, ki po 60 poslanih slikah ne vrne nobene, toka ne zna. H.264 varovalke nima.
    check(!ZaslonKodek.hevcBrezSlike(true, 0, false) && !ZaslonKodek.hevcBrezSlike(true, 59, false)) { "se cakamo" }
    check(ZaslonKodek.hevcBrezSlike(true, 60, false) && ZaslonKodek.hevcBrezSlike(true, 500, false)) { "ne zna" }
    check(!ZaslonKodek.hevcBrezSlike(true, 500, true)) { "slika je prisla" }
    check(!ZaslonKodek.hevcBrezSlike(false, 500, false)) { "H.264 nima varovalke" }
    // Zapomnimo si razlicico aplikacije, v kateri je HEVC odpovedal; po posodobitvi poskusimo znova.
    check(ZaslonKodek.hevcDovoljen(0L, 294L)) { "nikoli ni odpovedal" }
    check(!ZaslonKodek.hevcDovoljen(294L, 294L)) { "odpovedal v tej razlicici" }
    check(ZaslonKodek.hevcDovoljen(294L, 295L)) { "po posodobitvi znova" }

    // Zmoznosti za `caps` v screen.start: vsak okus zna preklop na namizje, dolgo skupino slik in utrip (rtt). Prilagajanja
    // kakovosti med sejo (abr) se ne ponudi nobeden - racunalnik bi sicer smel menjati kodirnik sredi toka.
    for (okus in listOf("os", "telefon", "tablica", "brskalnik", "predvajalnik", "")) {
        val z = ZaslonKodek.zmoznosti(okus)
        check(z.containsAll(listOf("handoff", "gop", "rtt"))) { "zmoznosti $okus: $z" }
        check("abr" !in z) { "abr se ni: $okus" }
        check(z.size == z.toSet().size) { "brez podvojenih: $okus $z" }
    }
    kljucne()
    println("ZaslonKodekTest: OK")
}

/** Kljucna slika: H.264 IDR (5), HEVC IRAP (16-21); v celi sliki H.264 je pred IDR pogosto SEI ali AUD. */
private fun kljucne() {
    check(ZaslonKodek.jeKljucna(false, 5) && !ZaslonKodek.jeKljucna(false, 1) && !ZaslonKodek.jeKljucna(false, 6)) {
        "H.264: samo IDR"
    }
    for (v in 16..21) check(ZaslonKodek.jeKljucna(true, v)) { "HEVC IRAP $v" }
    for (v in listOf(0, 1, 15, 22, 32, 33, 39)) check(!ZaslonKodek.jeKljucna(true, v)) { "HEVC ni kljucna: $v" }

    fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
    // H.264 cela slika: SEI (0x06) s stiribajtno zacetno kodo, nato IDR (0x65) s tribajtno.
    val idr = b(0, 0, 0, 1, 0x06, 5, 4, 3, 0, 0, 1, 0x65, 0x88, 0x84)
    check(ZaslonKodek.imaKljucno(false, idr, 0, idr.size)) { "IDR za SEI" }
    check(!ZaslonKodek.imaKljucno(false, idr, 0, 8)) { "samo SEI ni kljucna" }
    val p = b(0, 0, 0, 1, 0x09, 0x10, 0, 0, 1, 0x41, 0x9a)
    check(!ZaslonKodek.imaKljucno(false, p, 0, p.size)) { "AUD + P ni kljucna" }
    // HEVC: IDR_W_RADL (0x26 0x01) je kljucna, navadna slika (0x02 0x01) ni.
    check(ZaslonKodek.imaKljucno(true, b(0, 0, 0, 1, 0x26, 0x01, 0xaf), 0, 7)) { "HEVC IDR" }
    check(!ZaslonKodek.imaKljucno(true, b(0, 0, 1, 0x02, 0x01, 0xd0), 0, 6)) { "HEVC navadna" }
    // Razpon in rob: zacetna koda brez glave na koncu ali zunaj razpona ne steje.
    check(!ZaslonKodek.imaKljucno(false, b(0, 0, 1), 0, 3)) { "brez glave" }
    check(!ZaslonKodek.imaKljucno(false, idr, 9, idr.size)) { "zacetek za kodo" }
    check(ZaslonKodek.imaKljucno(false, idr, 0, 100)) { "do_ nad velikostjo je omejen" }
}
