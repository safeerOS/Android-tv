package si.safeer.tv.scit

import com.safeer.threatfeed.PlainListSource
import java.io.File

/**
 * Blokada po IP odgovora (26. 11. 2026, po zgledu blocky »block by response IP«): branje zapisov A iz odgovora DNS,
 * seznam naslovov IPv4 (Feodo), odlocitev in izbira virov. JVM, brez Androida.
 */
private fun imeDns(s: String): ByteArray {
    val o = java.io.ByteArrayOutputStream()
    for (l in s.split('.')) { o.write(l.length); o.write(l.toByteArray()) }
    o.write(0); return o.toByteArray()
}

/** Odgovor za [ime] z zapisi: vsak par (vrsta, rdata); ime zapisa je kazalec na vprasanje (0xC00C), razred IN. */
private fun odgovorZ(ime: String, vararg zapisi: Pair<Int, ByteArray>): ByteArray {
    val o = java.io.ByteArrayOutputStream()
    o.write(byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, zapisi.size.toByte(), 0, 0, 0, 0))
    o.write(imeDns(ime)); o.write(byteArrayOf(0, 1, 0, 1))
    for ((vrsta, rd) in zapisi) {
        o.write(byteArrayOf(0xC0.toByte(), 0x0C, 0, vrsta.toByte(), 0, 1, 0, 0, 0, 60, 0, rd.size.toByte()))
        o.write(rd)
    }
    return o.toByteArray()
}

private fun a(vararg b: Int) = 1 to ByteArray(b.size) { b[it].toByte() }

fun ipOdgovorTest() {
    val odg = odgovorZ("c2.stran.si", a(185, 12, 7, 9), a(1, 2, 3, 4))
    preveri(DnsPaket.naslovi4(odg) == listOf("185.12.7.9", "1.2.3.4"), "zapisa A: ${DnsPaket.naslovi4(odg)}")
    // AAAA (vrsta 28, 16 bajtov) in CNAME se ne berejo kot IPv4
    val aaaa = odgovorZ("x.si", 28 to ByteArray(16) { 1 }, a(8, 8, 8, 8))
    preveri(DnsPaket.naslovi4(aaaa) == listOf("8.8.8.8"), "AAAA preskoceno: ${DnsPaket.naslovi4(aaaa)}")
    // Zapis A z dolzino != 4 ni naslov
    preveri(DnsPaket.naslovi4(odgovorZ("x.si", 1 to ByteArray(5))).isEmpty(), "A z 5 bajti ni naslov")
    // Brez odgovorov
    val brez = odg.copyOf(); brez[7] = 0
    preveri(DnsPaket.naslovi4(brez).isEmpty(), "brez odgovorov")
    // Odrezano sporocilo ne vrze izjeme (vsaka dolzina), vrne le, kar je cel zapis
    for (n in 0 until odg.size) DnsPaket.naslovi4(odg, n)
    preveri(DnsPaket.naslovi4(odg, odg.size - 1) == listOf("185.12.7.9"), "odrezan zadnji zapis ne vrne polovicnega naslova")
    // Lazno veliko stevilo odgovorov
    val lazno = odg.copyOf(); lazno[6] = 0xFF.toByte(); lazno[7] = 0xFF.toByte()
    preveri(DnsPaket.naslovi4(lazno) == listOf("185.12.7.9", "1.2.3.4"), "lazno stevilo odgovorov")
    // Razred, ki ni IN (CH), ni naslov
    val ch = odg.copyOf()
    val i = 12 + imeDns("c2.stran.si").size + 4   // prvi zapis: ime (2), vrsta (2), razred (2 bajta)
    ch[i + 5] = 3   // nizji bajt razreda: 3 = CH
    preveri(DnsPaket.naslovi4(ch) == listOf("1.2.3.4"), "razred CH preskocen: ${DnsPaket.naslovi4(ch)}")
    // Meja 64 zapisov
    val veliko = odgovorZ("m.si", *Array(70) { a(10 + it, 0, 0, 1) })
    veliko[7] = 70
    preveri(DnsPaket.naslovi4(veliko).size == 64, "najvec 64 zapisov: ${DnsPaket.naslovi4(veliko).size}")
    println("ip odgovor: OK")
}

fun ipSeznamTest() {
    val s = "# Feodo\n185.12.7.9\n  1.2.3.4  \n127.0.0.1\n192.168.1.1\n10.0.0.5\n999.1.1.1\n01.2.3.4\nne-naslov\n\n1.2.3.4\n8.8.4.4\n"
    val v = ScitIpSeznam.razclani(s).toList()
    preveri(v.size == 3, "javni, veljavni, brez dvojnikov: $v")
    val mapa = File(System.getProperty("java.io.tmpdir"), "safeer-scit-ip-test").apply { mkdirs() }
    try {
        preveri(ScitIpSeznam.odpri(mapa) == null, "brez datoteke ni nabora")
        preveri(ScitIpSeznam.spremenjena(mapa) == 0L, "brez datoteke cas 0")
        File(mapa, "feodo.list").writeText(s)
        val n = ScitIpSeznam.odpri(mapa) ?: throw AssertionError("nabor")
        preveri(n.stevilo == 3, "stevilo ${n.stevilo}")
        preveri(n.vsebuje("185.12.7.9") && n.vsebuje("1.2.3.4") && n.vsebuje("8.8.4.4"), "javni naslovi so v naboru")
        preveri(!n.vsebuje("127.0.0.1") && !n.vsebuje("192.168.1.1") && !n.vsebuje("10.0.0.5"), "zasebni naslovi nikoli v naboru")
        preveri(!n.vsebuje("1.2.3.5") && !n.vsebuje("ne-naslov") && !n.vsebuje("") && !n.vsebuje("01.2.3.4"), "tuji in neveljavni niso")
        preveri(n.spremenjena == File(mapa, "feodo.list").lastModified() && n.spremenjena > 0, "cas spremembe")
        // Prevelika datoteka se preskoci (ne bere se v pomnilnik)
        val velika = File(mapa, "feodo.list")
        java.io.RandomAccessFile(velika, "rw").use { it.setLength((ScitIpSeznam.NAJVEC_BAJTOV + 1).toLong()) }
        preveri(ScitIpSeznam.odpri(mapa)?.stevilo == 0, "prevelika datoteka: prazen nabor")
        // Prazna datoteka
        velika.writeText("")
        preveri(ScitIpSeznam.odpri(mapa)?.stevilo == 0, "prazna datoteka: prazen nabor")
    } finally { mapa.deleteRecursively() }
    println("ip seznam: OK")
}

fun ipOdlocitevTest() {
    val naSeznamu: (String) -> Boolean = { it == "185.12.7.9" }
    val ip = listOf("1.2.3.4", "185.12.7.9")
    preveri(ScitOdlocitev.ipUstavi("c2.stran.si", ip, naSeznamu, false), "naslov na seznamu ustavi ime")
    preveri(!ScitOdlocitev.ipUstavi("c2.stran.si", listOf("1.2.3.4"), naSeznamu, false), "naslov ni na seznamu: naprej")
    preveri(!ScitOdlocitev.ipUstavi("c2.stran.si", emptyList(), naSeznamu, false), "brez naslovov: naprej")
    preveri(!ScitOdlocitev.ipUstavi("c2.stran.si", ip, naSeznamu, true), "izjema na imenu: naprej (uporabnik ima pot)")
    preveri(!ScitOdlocitev.ipUstavi("nas-ruter.lan", ip, naSeznamu, false), "krajevno ime: naprej")
    preveri(!ScitOdlocitev.ipUstavi("", ip, naSeznamu, false), "prazno ime: naprej")
    preveri(!ScitOdlocitev.ipUstavi("objects.github.com", ip, naSeznamu, false), "zasciten gostitelj: naprej")
    println("ip odlocitev: OK")
}

fun ipViriTest() {
    val feodo = PlainListSource("feodo", "Feodo", "", "", "feodo", minEntries = 0, ipv4 = true)
    val urlhaus = PlainListSource("urlhaus", "URLhaus", "", "", "")
    val easylist = PlainListSource("easylist", "EasyList", "", "", "", raw = true)
    val vsi = listOf(feodo, urlhaus, easylist)
    preveri(ScitViri.viriIp(vsi).map { it.id } == listOf("feodo"), "viri IP: samo Feodo")
    preveri(ScitViri.viri(vsi).none { it.ipv4 }, "IPv4 seznam ostane izven nabora domen")
    val agr = PlainListSource("feodo-aggressive", "Feodo agr", "", "", "feodo", minEntries = 0, ipv4 = true, dnsOnly = true)
    preveri(ScitViri.viriIp(listOf(feodo, agr, urlhaus)).map { it.id } == listOf("feodo", "feodo-aggressive"), "viri IP: oba Feodo")
    preveri(!ScitViri.zaBrskalnik(agr) && ScitViri.zaBrskalnik(feodo), "aggressive je samo za Scit")
    preveri(ScitIpSeznam.DATOTEKE.contains(agr.id + ".list"), "datoteka aggressive v seznamu")
    println("ip viri: OK")
}

fun main() {
    ipOdgovorTest()
    ipSeznamTest()
    ipOdlocitevTest()
    ipViriTest()
    println("SCIT IP TESTI: OK")
}
