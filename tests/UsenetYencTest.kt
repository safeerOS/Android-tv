package si.safeer.tv.os

import java.util.zip.CRC32

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

private fun b(s: String) = s.toByteArray(Charsets.ISO_8859_1)
private fun crc(d: ByteArray) = CRC32().apply { update(d) }.value
private fun hex(d: ByteArray) = d.joinToString("") { "%02x".format(it) }

/** Ime napake yEnc ali null, ce dekodiranje uspe. */
private fun napaka(vhod: ByteArray): String? =
    try { UsenetYenc.dekodiraj(vhod); null } catch (e: UsenetYenc.NapakaYenc) { e.vrsta.name }

/** Kot pride prek NNTP: streznik podvoji pike in doda ».«; odjemalec vzame telo ([UsenetNntp.telo]). */
private fun prekNntp(telo: ByteArray): ByteArray =
    UsenetNntp.telo(YencKodirnik.podvojiPike(telo) + b(".\r\n"))!!.telo

/** Meritev hitrosti: [skupaj] bajtov v clankih po 716 800 B (obicajna velikost na Usenetu), [ponovitev] ponovitev. */
private fun meritev(skupaj: Int, ponovitev: Int) {
    val r = java.util.Random(42)
    val izvor = ByteArray(skupaj).also { r.nextBytes(it) }
    val velikostDela = 716_800
    val telesa = ArrayList<ByteArray>()
    val surova = ArrayList<ByteArray>()
    var od = 0
    var st = 1
    val delov = (skupaj + velikostDela - 1) / velikostDela
    while (od < skupaj) {
        val d = izvor.copyOfRange(od, minOf(skupaj, od + velikostDela))
        val t = YencKodirnik.kodiraj(d, "meritev.bin", st, delov, od.toLong(), skupaj.toLong())
        telesa += t
        surova += YencKodirnik.podvojiPike(t) + b(".\r\n")
        od += d.size
        st++
    }
    val surovih = surova.sumOf { it.size.toLong() }
    println("  meritev: ${telesa.size} clankov, dekodirano $skupaj B, surovo $surovih B, JVM ${System.getProperty("java.version")}, " +
        "jeder ${Runtime.getRuntime().availableProcessors()}")
    for (nacin in listOf("yEnc", "NNTP telo + yEnc")) {
        for (krog in 0..ponovitev) {
            val crcCel = CRC32()
            val t0 = System.nanoTime()
            for (k in telesa.indices) {
                val del = if (nacin == "yEnc") UsenetYenc.dekodiraj(telesa[k]) else UsenetYenc.dekodiraj(UsenetNntp.telo(surova[k])!!.telo)
                crcCel.update(del.podatki)
            }
            val s = (System.nanoTime() - t0) / 1e9
            val ok = crcCel.value == crc(izvor)
            val oznaka = if (krog == 0) "ogrevanje" else "ponovitev $krog"
            println(String.format(java.util.Locale.ROOT, "  %s, %s: %.3f s, %.1f MB/s dekodirano (%.1f MB/s surovo), CRC %s",
                nacin, oznaka, s, skupaj / s / 1e6, surovih / s / 1e6, if (ok) "ok" else "NAPAKA"))
            if (!ok) napak++
        }
    }
}

/** Pogon Usenet, korak 1: dekodiranje yEnc ([UsenetYenc]). Z argumentom »meritev« se izmeri hitrost (50 MB, 3x). */
fun main(args: Array<String>) {
    println("Preizkus yEnc (pogon Usenet)")

    // Rocno izracunan primer (ne iz nasega kodirnika): =@ =J =M =} so ubezni NUL, LF, CR in »=«.
    // 0xD6+42=0x100 -> 0x00, 0xE0+42 -> 0x0A, 0xE3+42 -> 0x0D, 0x13+42 -> 0x3D; »r« = 'H'+42.
    val rocni = byteArrayOf(0xD6.toByte(), 0xE0.toByte(), 0xE3.toByte(), 0x13, 'H'.code.toByte())
    val vhodRocni = b("=ybegin line=128 size=5 name=rocni.bin\r\n=@=J=M=}r\r\n=yend size=5 crc32=%08x\r\n".format(crc(rocni)))
    val d1 = UsenetYenc.dekodiraj(vhodRocni)
    preveriEnako("rocni primer: bajti", hex(rocni), hex(d1.podatki))
    preveriEnako("rocni primer: CRC preverjen", true, d1.crcPreverjen)
    preveriEnako("rocni primer: ime", "rocni.bin", d1.ime)
    preveriEnako("rocni primer: en del (del in delov null)", listOf(null, null), listOf(d1.del, d1.delov))
    preveriEnako("rocni primer: odmiki", listOf(0L, 5L, 5L), listOf(d1.zacetek, d1.konec, d1.velikostDatoteke))
    // Ubeznik na koncu vrstice velja za prvi znak naslednje (CR/LF med njima se izpusti).
    preveriEnako("ubeznik cez konec vrstice", "13", hex(UsenetYenc.dekodiraj(b("=ybegin line=1 size=1 name=a\r\n=\r\n}\r\n=yend size=1\r\n")).podatki))
    preveriEnako("== je bajt 211", "d3", hex(UsenetYenc.dekodiraj(b("=ybegin line=2 size=1 name=a\r\n==\r\n=yend size=1\r\n")).podatki))

    // CRC32("123456789") = cbf43926 (znana vrednost CRC-32/ISO-HDLC).
    val devet = b("123456789")
    val vhodDevet = YencKodirnik.kodiraj(devet, "d.txt")
    preveriEnako("znan CRC cbf43926 v glavi", true, String(vhodDevet, Charsets.ISO_8859_1).contains("crc32=cbf43926"))
    preveriEnako("znan CRC: dekodirano", "123456789", String(UsenetYenc.dekodiraj(vhodDevet).podatki, Charsets.ISO_8859_1))
    preveriEnako("napacen CRC", "NAPAKA_CRC", napaka(b(String(vhodDevet, Charsets.ISO_8859_1).replace("cbf43926", "cbf43927"))))
    preveriEnako("CRC velike crke", "OK", if (napaka(b(String(vhodDevet, Charsets.ISO_8859_1).replace("cbf43926", "CBF43926"))) == null) "OK" else "NAPAKA")
    preveriEnako("CRC brez vodilnih nicel", 0x1234L, UsenetYenc.crcIz("1234"))
    preveriEnako("CRC z 0x ni veljaven", null, UsenetYenc.crcIz("0xabcdef"))
    preveriEnako("CRC ni heks", null, UsenetYenc.crcIz("xyz"))
    preveriEnako("CRC predolg", null, UsenetYenc.crcIz("123456789"))
    preveriEnako("CRC prazen", null, UsenetYenc.crcIz(""))

    // Pike: telo po NNTP jih nima vec podvojenih; ».« na zacetku vrstice je podatek (bajt 4).
    val pike = byteArrayOf(4, 4, 4, 0x41, 4)
    val telo = YencKodirnik.kodiraj(pike, "p.bin", vrstica = 2)
    preveriEnako("pike v telesu", true, String(telo, Charsets.ISO_8859_1).contains("\r\n..\r\n"))
    preveriEnako("pike: dekodirano iz telesa", hex(pike), hex(UsenetYenc.dekodiraj(telo).podatki))
    preveriEnako("pike: prek NNTP (podvojene, nato odstranjene)", hex(pike), hex(UsenetYenc.dekodiraj(prekNntp(telo)).podatki))
    preveriEnako("ubezna pika (=n) na zacetku", hex(pike), hex(UsenetYenc.dekodiraj(YencKodirnik.kodiraj(pike, "p.bin", vrstica = 2, ubeziPiko = true)).podatki))

    // Vsi bajti, razlicne dolzine vrstic, CRLF in samo LF, neposredno in prek NNTP.
    val vsi = ByteArray(256 * 9) { (it % 256).toByte() }
    for (vrstica in listOf(1, 2, 3, 61, 128, 997)) {
        for (crlf in listOf(true, false)) {
            val v = YencKodirnik.kodiraj(vsi, "vsi.bin", vrstica = vrstica, crlf = crlf)
            preveriEnako("vsi bajti, vrstica $vrstica, crlf $crlf", hex(vsi), hex(UsenetYenc.dekodiraj(v).podatki))
            if (crlf) preveriEnako("vsi bajti prek NNTP, vrstica $vrstica", hex(vsi), hex(UsenetYenc.dekodiraj(prekNntp(v)).podatki))
        }
    }

    // Vecdelni clanek: odmiki iz =ypart (od 1, konec vkljucno) in pcrc32 dela.
    val dat = ByteArray(30) { (it * 7).toByte() }
    val del2 = dat.copyOfRange(10, 20)
    val vDel = YencKodirnik.kodiraj(del2, "film.mkv", del = 2, delov = 3, zacetek = 10, velikost = 30)
    val d2 = UsenetYenc.dekodiraj(vDel)
    preveriEnako("vecdelni: del/delov", listOf(2, 3), listOf(d2.del, d2.delov))
    preveriEnako("vecdelni: zacetek, konec, velikost datoteke", listOf(10L, 20L, 30L), listOf(d2.zacetek, d2.konec, d2.velikostDatoteke))
    preveriEnako("vecdelni: bajti", hex(del2), hex(d2.podatki))
    preveriEnako("vecdelni: pcrc32 preverjen", true, d2.crcPreverjen)
    val sDel = String(vDel, Charsets.ISO_8859_1)
    preveriEnako("vecdelni: crc32 cele datoteke se ne preveri", false,
        UsenetYenc.dekodiraj(b(sDel.replace(Regex("pcrc32=[0-9a-f]+"), "crc32=00000000"))).crcPreverjen)
    preveriEnako("vecdelni brez =ypart", "NAPAKA_OBLIKE", napaka(b(sDel.replace(Regex("=ypart[^\n]*\n"), ""))))
    preveriEnako("=ypart begin > end", "NAPAKA_OBLIKE", napaka(b(sDel.replace("begin=11 end=20", "begin=21 end=20"))))
    preveriEnako("=ypart end > size", "NAPAKA_OBLIKE", napaka(b(sDel.replace("begin=11 end=20", "begin=31 end=40"))))
    preveriEnako("=ypart begin 0", "NAPAKA_OBLIKE", napaka(b(sDel.replace("begin=11", "begin=0"))))
    preveriEnako("=ypart daljsi od podatkov", "NAPAKA_VELIKOSTI", napaka(b(sDel.replace("end=20", "end=21"))))
    preveriEnako("=ypart krajsi od podatkov", "NAPAKA_VELIKOSTI", napaka(b(sDel.replace("end=20", "end=19"))))
    preveriEnako("=yend part drug", "NAPAKA_OBLIKE", napaka(b(sDel.replace(" part=2 pcrc32", " part=3 pcrc32"))))
    preveriEnako("part ni stevilo", "NAPAKA_OBLIKE", napaka(b(sDel.replace("part=2 total=3", "part=x total=3"))))
    preveriEnako("part 0", "NAPAKA_OBLIKE", napaka(b(sDel.replace("part=2 total=3", "part=0 total=3"))))

    // Glava
    val ena = String(YencKodirnik.kodiraj(b("abc"), "moj film 2020 (1).mkv"), Charsets.ISO_8859_1)
    preveriEnako("ime s presledki", "moj film 2020 (1).mkv", UsenetYenc.dekodiraj(b(ena)).ime)
    preveriEnako("ime s potjo je samo ime", "passwd", UsenetYenc.dekodiraj(b(ena.replace("moj film 2020 (1).mkv", "../../etc/passwd"))).ime)
    preveriEnako("ime .. -> null", null, UsenetYenc.dekodiraj(b(ena.replace("moj film 2020 (1).mkv", ".."))).ime)
    preveriEnako("ime UTF-8", "\u010d.mkv", UsenetYenc.dekodiraj(YencKodirnik.kodiraj(b("abc"), "\u010d.mkv")).ime)
    preveriEnako("ime latin-1 (ni veljaven UTF-8)", "\u00e8.mkv", UsenetYenc.dekodiraj(b(ena.replace("moj film 2020 (1).mkv", "\u00e8.mkv"))).ime)
    preveriEnako("dolgo ime skrajsano na 200", 200, UsenetYenc.dekodiraj(b(ena.replace("name=moj", "name=" + "x".repeat(5000)))).ime?.length)
    preveriEnako("prazne vrstice in besedilo pred =ybegin", "abc", String(UsenetYenc.dekodiraj(b("\r\n\r\nnekaj\r\n$ena")).podatki, Charsets.ISO_8859_1))
    preveriEnako("brez =ybegin", "NI_YENC", napaka(b("abc\r\ndef\r\n")))
    preveriEnako("=ybegin ni na zacetku vrstice", "NI_YENC", napaka(b("x=ybegin line=1 size=1 name=a\r\nK\r\n=yend size=1\r\n")))
    preveriEnako("brez size", "NAPAKA_OBLIKE", napaka(b(ena.replace("size=3 name", "name"))))
    preveriEnako("negativen size", "NAPAKA_OBLIKE", napaka(b(ena.replace("line=128 size=3", "line=128 size=-3"))))
    preveriEnako("enodelni nad mejo dela", "PREVELIK", napaka(b(ena.replace("line=128 size=3", "line=128 size=16777217"))))
    preveriEnako("del nad mejo dela", "PREVELIK", napaka(b(sDel.replace("size=30", "size=90000000").replace("end=20", "end=80000000"))))
    preveriEnako("prazna datoteka", 0, UsenetYenc.dekodiraj(YencKodirnik.kodiraj(ByteArray(0), "prazna.txt")).podatki.size)

    // Odrezan clanek, velikost, konec
    val odrezan = ena.substringBefore("=yend")
    preveriEnako("brez =yend (odrezan)", "PRIREZANO", napaka(b(odrezan)))
    preveriEnako("=ypart namesto =yend je podatek -> brez konca", "PRIREZANO", napaka(b(odrezan + "=ypart begin=1 end=3\r\n")))
    preveriEnako("=yend brez presledka je podatek -> brez konca", "PRIREZANO", napaka(b(odrezan + "=yend\r\n")))
    preveriEnako("=yend size drug", "NAPAKA_VELIKOSTI", napaka(b(ena.replace("=yend size=3", "=yend size=4"))))
    preveriEnako("vec podatkov kot size", "NAPAKA_VELIKOSTI", napaka(b(ena.replace("line=128 size=3", "line=128 size=2").replace("=yend size=3", "=yend size=2"))))
    preveriEnako("manj podatkov kot size", "NAPAKA_VELIKOSTI", napaka(b(ena.replace("line=128 size=3", "line=128 size=4").replace("=yend size=3", "=yend size=4"))))
    preveriEnako("brez CRC: uspe, ni preverjen", false, UsenetYenc.dekodiraj(b(ena.replace(Regex(" crc32=[0-9a-f]+"), ""))).crcPreverjen)
    preveriEnako("pokvarjen CRC v glavi", "NAPAKA_OBLIKE", napaka(b(ena.replace(Regex("crc32=[0-9a-f]+"), "crc32=zz"))))
    preveriEnako("enodelni s pcrc32: pcrc32 velja", "NAPAKA_CRC", napaka(b(ena.replace(Regex("crc32=([0-9a-f]+)"), "crc32=$1 pcrc32=00000001"))))
    // Osamljen »=« tik pred =yend se izpusti (vsi podatki so ze tu).
    preveriEnako("= pred =yend", "abc", String(UsenetYenc.dekodiraj(b(ena.replace("\r\n=yend", "=\r\n=yend"))).podatki, Charsets.ISO_8859_1))
    // Velika napovedana velikost brez podatkov ne sme zahtevati velikega pomnilnika (dodeli najvec toliko kot vhod).
    val t0 = System.nanoTime()
    preveriEnako("size 3 MiB, malo podatkov", "NAPAKA_VELIKOSTI", napaka(b("=ybegin line=128 size=3145728 name=a\r\nkkk\r\n=yend size=3145728\r\n")))
    preveriEnako("brez velike dodelitve (< 50 ms)", true, (System.nanoTime() - t0) < 50_000_000L)
    preveriEnako("od in do vhoda", "abc", String(UsenetYenc.dekodiraj(b("XXXX$ena"), 4).podatki, Charsets.ISO_8859_1))

    // polja()
    preveriEnako("polja: ime zadnje", mapOf("part" to "1", "size" to "9", "name" to "a b=c.mkv"), UsenetYenc.polja("part=1 size=9 name=a b=c.mkv"))
    preveriEnako("polja: brez imena", mapOf("begin" to "1", "end" to "5"), UsenetYenc.polja("begin=1  end=5"))

    if ("meritev" in args) meritev(50_000_000, 3)

    if (napak == 0) println("UsenetYencTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
