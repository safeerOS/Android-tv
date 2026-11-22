package si.safeer.tv.os

import kotlin.system.exitProcess

/**
 * Zig slike brez "au" (oddajnik Linux): zig okvirja 4 dobi prva rezina slike z meje okvirja, s pts, s katerim gre
 * v dekoder. Brez Androida. Slog: p(pogoj, opis), na koncu exitProcess(1), ce kaj pade.
 * Prevajanje: ZaslonSinhro.kt, ZaslonKodek.kt, ZaslonOstanek.kt in ta datoteka.
 * Zanka [brezAu] je prepis poti brez "au" iz ZaslonOdjemalec.crpaj in posljiNal (razred z Androidom se na JVM ne
 * prevede); zacetekNal in vrstaNal sta prepisana dobesedno.
 */

private var uspelih = 0
private var padlih = 0

private fun p(pogoj: Boolean, opis: String) {
    if (pogoj) { uspelih++; println("  OK     $opis") } else { padlih++; println("  PADLO  $opis") }
}

/** Enota H.264: 00 00 00 01, glava, bajt z oznako (bit 7 = first_mb_in_slice == 0 pri rezini), polnilo brez nicel. */
private fun nal(vrsta: Int, oznaka: Int, prvaRezina: Boolean = false, polnila: Int = 20): ByteArray =
    byteArrayOf(0, 0, 0, 1, (0x60 or vrsta).toByte(), ((if (prvaRezina) 0x80 else 0) or oznaka).toByte()) +
        ByteArray(polnila) { 0x11 }

private fun okvir(vararg deli: ByteArray): ByteArray = deli.fold(ByteArray(0)) { a, d -> a + d }

// Dobesedno iz ZaslonOdjemalec (hevc = false).
private fun zacetekNal(b: ByteArray, od: Int): Int {
    var i = maxOf(od, 0)
    while (i + 3 < b.size) {
        if (b[i] == 0.toByte() && b[i + 1] == 0.toByte()) {
            if (b[i + 2] == 1.toByte()) return i
            if (b[i + 2] == 0.toByte() && b[i + 3] == 1.toByte()) return i
        }
        i++
    }
    return -1
}

private fun vrstaNal(b: ByteArray, zacetek: Int): Int {
    var i = zacetek + 3
    if (zacetek + 3 < b.size && b[zacetek + 2] == 0.toByte()) i = zacetek + 4
    return if (i < b.size) ZaslonKodek.vrstaEnote(false, b[i].toInt()) else -1
}

private class Poslano(val oznaka: Int, val vrsta: Int, val zig: Long?, val ptsUs: Long, val uraMs: Long)

/** Pot brez "au": vsak okvir v svoji ms (+16), enote istega okvirja v isti ms. */
private fun brezAu(okvirji: List<Pair<ByteArray, Long?>>, tabela: ZaslonSinhro.ZigTabela): List<Poslano> {
    val izid = ArrayList<Poslano>()
    val enota = ZaslonSinhro.ZigEnote()
    var ostanek = ByteArray(0)
    var zadnjiPtsUs = 0L
    var uraMs = 1_000L
    for ((telo, zig) in okvirji) {
        uraMs += 16
        val prej = ostanek.size
        ostanek = ZaslonOstanek.dodaj(ostanek, telo)
        val meja = ostanek.size - telo.size
        enota.okvir(zig, meja, meja == prej)
        var od = zacetekNal(ostanek, 0)
        if (od < 0) { enota.pocisti(); continue }
        var naslednji = zacetekNal(ostanek, od + 3)
        while (naslednji > 0) {
            val vrsta = vrstaNal(ostanek, od)
            val z = enota.za(od, naslednji, ZaslonKodek.jeRezina(false, vrsta))
            val pts = ZaslonSinhro.enolicenPts(uraMs * 1000, zadnjiPtsUs)
            zadnjiPtsUs = pts
            if (!ZaslonKodek.jeNastavitev(false, vrsta) && z != null) tabela.dodaj(pts, z)
            izid.add(Poslano(ostanek[od + 5].toInt() and 0x7f, vrsta, z, pts, uraMs))
            od = naslednji
            naslednji = zacetekNal(ostanek, od + 3)
        }
        ostanek = ostanek.copyOfRange(od, ostanek.size)
        enota.odrezi(od)
    }
    return izid
}

private fun List<Poslano>.enota(oznaka: Int): Poslano? = firstOrNull { it.oznaka == oznaka }

private fun ostanekStare() {
    println("vec rezin: zig gre na prvo rezino nove slike, ne na konec stare")
    val t = ZaslonSinhro.ZigTabela()
    val s = brezAu(listOf(
        okvir(nal(1, 1, true), nal(1, 2)) to 100L,
        okvir(nal(1, 3, true), nal(1, 4)) to 200L,
        okvir(nal(1, 5, true)) to 300L), t)
    val a1 = s.enota(1); val a2 = s.enota(2); val b1 = s.enota(3); val b2 = s.enota(4)
    p(a1?.zig == 100L, "a1 (prva rezina slike A) dobi 100")
    p(a2 != null && a2.zig == null, "a2 (konec slike A, poslan z okvirjem B) brez ziga")
    p(b1?.zig == 200L, "b1 dobi 200")
    p(b2 != null && b2.zig == null, "b2 brez ziga")
    p(a2 != null && b1 != null && a2.uraMs == b1.uraMs && a2.ptsUs != b1.ptsUs, "a2 in b1 v isti ms, pts razlicen")
    p(a2 != null && t.natancno(a2.ptsUs) == null, "izris slike A s pts a2 ne najde ziga slike B")
    p(b1 != null && t.natancno(b1.ptsUs) == 200L, "izris s pts b1 najde 200")
    p(b1 != null && b1.ptsUs - b1.uraMs * 1000 in 0L..999L, "pts b1 od ure oddaje manj kot 1 ms")
}

private fun cakajocaEnota() {
    println("ena rezina na sliko: enota gre v dekoder z naslednjim okvirjem")
    val t = ZaslonSinhro.ZigTabela()
    val s = brezAu(listOf(
        okvir(nal(1, 10, true)) to 400L,
        okvir(nal(1, 11, true)) to 500L,
        okvir(nal(1, 12, true)) to 600L), t)
    val d = s.enota(10); val e = s.enota(11)
    p(d != null && d.zig == 400L && t.natancno(d.ptsUs) == 400L, "d dobi 400 s pts oddaje")
    p(d != null && d.uraMs == 1_032L, "d gre v dekoder ob drugem okvirju (ne ob prihodu svojega)")
    p(e?.zig == 500L, "e dobi 500, ne 400")
    p(s.enota(12) == null, "zadnja enota se caka")
}

private fun kljucnaPrekoOkvirjev() {
    println("kljucna slika cez tri okvirje: SPS, PPS brez ziga, zig na IDR; pozni zig ne gre na naslednjo sliko")
    val t = ZaslonSinhro.ZigTabela()
    val idr = okvir(nal(7, 20), nal(8, 21), nal(5, 22, true, polnila = 3000))
    val s = brezAu(listOf(
        idr.copyOfRange(0, 1000) to 700L,
        idr.copyOfRange(1000, 2000) to null,
        idr.copyOfRange(2000, idr.size) to null,
        okvir(nal(1, 23, true)) to 800L,
        okvir(nal(1, 24, true)) to 900L), t)
    val sps = s.enota(20); val pps = s.enota(21); val i = s.enota(22); val p1 = s.enota(23)
    p(sps != null && sps.zig == null && pps != null && pps.zig == null, "SPS in PPS brez ziga")
    p(i != null && i.zig == 700L && t.natancno(i.ptsUs) == 700L, "IDR dobi 700")
    p(i != null && i.uraMs == 1_064L, "IDR gre v dekoder sele s cetrtim okvirjem")
    p(p1?.zig == 800L, "naslednja slika dobi svoj 800, ne 700")
}

private fun brezZigov() {
    println("KMS / vaapi: brez okvirja 4 za sliko ni ucinka")
    val t = ZaslonSinhro.ZigTabela()
    val s = brezAu(listOf(
        okvir(nal(1, 1, true), nal(1, 2)) to null,
        okvir(nal(1, 3, true), nal(1, 4)) to null,
        okvir(nal(1, 5, true)) to null), t)
    p(s.size == 4 && s.all { it.zig == null }, "nobena enota nima ziga")
    p(s.all { t.natancno(it.ptsUs) == null }, "tabela ostane prazna")
}

private fun zigBrezMeje() {
    println("okvir ne zacne z zacetno kodo: zig ne gre na enoto sredi okvirja")
    val t = ZaslonSinhro.ZigTabela()
    val a = nal(1, 30, true, polnila = 60)
    val s = brezAu(listOf(
        a.copyOfRange(0, 30) to 100L,
        okvir(a.copyOfRange(30, a.size), nal(1, 31, true)) to 200L,
        okvir(nal(1, 32, true)) to 300L), t)
    p(s.enota(30)?.zig == 100L, "a dobi 100")
    p(s.enota(31).let { it != null && it.zig == null }, "b (ne na meji okvirja) brez ziga")
}

private fun neposredno() {
    println("ZigEnote neposredno")
    val e = ZaslonSinhro.ZigEnote()
    e.okvir(5L, 0, true); e.odrezi(0)
    e.okvir(null, 0, false)
    p(e.za(0, 10, true) == null, "odpadel ostanek (ZaslonOstanek) odnese zig")
    val f = ZaslonSinhro.ZigEnote()
    f.okvir(900L, 0, true); f.odrezi(0)
    f.okvir(950L, 10, true)
    p(f.za(0, 10, false) == null, "nastavitev na zacetku ostanka brez ziga")
    p(f.za(10, 20, true) == 950L, "naslednja enota z lastnim zigom dobi svojega, ne prenesenega")
    p(f.za(20, 30, true) == null, "zig porabljen")
    val g = ZaslonSinhro.ZigEnote()
    g.okvir(1L, 7, true)
    p(g.za(-1, 7, true) == null, "odmik -1 nikoli ne dobi ziga")
}

private fun pomozne() {
    println("enolicenPts, jeRezina")
    p(ZaslonSinhro.enolicenPts(5_000_000L, 4_999_000L) == 5_000_000L, "nova ms: ura")
    p(ZaslonSinhro.enolicenPts(5_000_000L, 5_000_000L) == 5_000_001L, "ista ms: +1 us")
    p(ZaslonSinhro.enolicenPts(5_000_000L, 5_000_001L) == 5_000_002L, "ze premaknjen: se +1 us")
    p(ZaslonKodek.jeRezina(false, 1) && ZaslonKodek.jeRezina(false, 5), "H.264 1 in 5 sta rezini")
    p(listOf(2, 6, 7, 8, 9).none { ZaslonKodek.jeRezina(false, it) }, "H.264 2, 6, 7, 8, 9 niso (kot ZigiSlike)")
    p(ZaslonKodek.jeRezina(true, 1) && ZaslonKodek.jeRezina(true, 19), "HEVC 1 in 19 sta rezini")
    p(listOf(32, 33, 34, 35, 39).none { ZaslonKodek.jeRezina(true, it) }, "HEVC 32-35, 39 niso")
}

fun main() {
    ostanekStare()
    cakajocaEnota()
    kljucnaPrekoOkvirjev()
    brezZigov()
    zigBrezMeje()
    neposredno()
    pomozne()
    println("Uspelih $uspelih, padlih $padlih")
    if (padlih > 0) exitProcess(1)
}
