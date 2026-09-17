package si.safeer.tv.scit

import java.io.File

/** Preizkus jedra Safeer Scita v navadnem JVM: nabor domen in paketi DNS. */
fun preveri(pogoj: Boolean, sporocilo: String) { if (!pogoj) throw AssertionError(sporocilo) }

fun naborTest() {
    val mapa = File(System.getProperty("java.io.tmpdir"), "safeer-scit-test").apply { mkdirs() }
    val dat = File(mapa, "domene.bin")
    val n = DomenskiNabor.zapisi(dat, sequenceOf(
        sequenceOf("Ads.Example.com.", "tracker.net", "ads.example.com", "bad..domain", "", "#komentar", "x"),
        sequenceOf("phish.si", "sub.evil.org"),
    ))
    preveri(n == 4, "pricakovane 4 razlicne domene, dobil $n")
    val p = DomenskiNabor.Poizvedba.odpri(dat) ?: throw AssertionError("nabora ni mogoce odpreti")
    preveri(p.stevilo == 4, "stevilo")
    preveri(p.jeBlokirana("ads.example.com"), "tocna domena")
    preveri(p.jeBlokirana("ADS.EXAMPLE.COM."), "velike crke + pika")
    preveri(p.jeBlokirana("cdn.ads.example.com"), "poddomena")
    preveri(!p.jeBlokirana("example.com"), "nadrejena ni blokirana")
    preveri(!p.jeBlokirana("notads.example.com"), "podobna ni blokirana")
    preveri(p.jeBlokirana("a.b.sub.evil.org"), "globoka poddomena")
    preveri(!p.jeBlokirana("evil.org"), "evil.org sam ni na seznamu")
    preveri(!p.jeBlokirana("net"), "vrhnja domena")
    preveri(!p.jeBlokirana("localhost"), "brez pike")
    preveri(p.jeBlokirana("phish.si"), "si-cert")
    println("nabor: OK ($n domen, ${dat.length()} B)")
    // velik nabor: 600k domen -> velikost in hitrost
    val velik = File(mapa, "velik.bin")
    val t0 = System.currentTimeMillis()
    val m = DomenskiNabor.zapisi(velik, sequenceOf((0 until 600_000).asSequence().map { "d$it.tracker-example.com" }))
    val t1 = System.currentTimeMillis()
    val v = DomenskiNabor.Poizvedba.odpri(velik)!!
    var zadetkov = 0
    for (i in 0 until 200_000) if (v.jeBlokirana("cdn.d${i * 3}.tracker-example.com")) zadetkov++
    val t2 = System.currentTimeMillis()
    preveri(m == 600_000 && zadetkov == 200_000, "velik nabor: $m / $zadetkov")
    println("velik nabor: ${velik.length() / 1024} KiB, zapis ${t1 - t0} ms, 200k poizvedb ${t2 - t1} ms")
    mapa.deleteRecursively()
}

fun preveriUdp(p: ByteArray, razlicica: Int) {
    // IPv4: kontrolna vsota glave mora biti 0 po preverjanju; UDP vsota s psevdoglavo prav tako.
    val ipDolzina = if (razlicica == 4) 20 else 40
    if (razlicica == 4) preveri(DnsPaket.kontrolnaVsota(p, 0, 20, 0) == 0, "IPv4 glava")
    val izvor = if (razlicica == 4) p.copyOfRange(12, 16) else p.copyOfRange(8, 24)
    val cilj = if (razlicica == 4) p.copyOfRange(16, 20) else p.copyOfRange(24, 40)
    val udpDolzina = DnsPaket.u16(p, ipDolzina + 4)
    var psevdo = 0L
    for (b in listOf(izvor, cilj)) { var i = 0; while (i < b.size) { psevdo += DnsPaket.u16(b, i); i += 2 } }
    psevdo += 17 + udpDolzina
    preveri(DnsPaket.kontrolnaVsota(p, ipDolzina, udpDolzina, psevdo) == 0, "UDP vsota IPv$razlicica")
}

fun paketiTest() {
    val dns = DnsPaket.sestaviPoizvedbo(0x1234, "Ads.Example.COM", 1)
    val v4 = DnsPaket.sestaviPaket(4, byteArrayOf(10, 111, (222).toByte(), 1), byteArrayOf(10, 111, (222).toByte(), 2), 40000, 53, dns)
    preveriUdp(v4, 4)
    val q = DnsPaket.razcleni(v4) ?: throw AssertionError("IPv4 poizvedbe ni")
    preveri(q.razlicica == 4 && q.ime == "ads.example.com" && q.vrsta == 1 && q.id == 0x1234, "polja IPv4: ${q.ime} ${q.vrsta}")
    preveri(q.izvornaVrata == 40000 && q.ciljnaVrata == 53, "vrata")
    val odgovor = DnsPaket.odgovorBlokirano(q)
    preveri(DnsPaket.u16(odgovor, 0) == 0x1234, "ID odgovora")
    preveri((odgovor[2].toInt() and 0x80) != 0 && (odgovor[3].toInt() and 0x0F) == 3, "QR in NXDOMAIN")
    preveri(DnsPaket.u16(odgovor, 4) == 1 && DnsPaket.u16(odgovor, 6) == 0, "QDCOUNT/ANCOUNT")
    val paket = DnsPaket.zavijOdgovor(q, odgovor)
    preveriUdp(paket, 4)
    preveri(paket.copyOfRange(12, 16).contentEquals(q.cilj) && paket.copyOfRange(16, 20).contentEquals(q.izvor), "naslova zamenjana")
    preveri(DnsPaket.u16(paket, 20) == 53 && DnsPaket.u16(paket, 22) == 40000, "vrata zamenjana")
    preveri(DnsPaket.razcleni(paket) == null, "odgovor se ne razcleni kot poizvedba")

    val v6izvor = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }
    val v6cilj = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 2 }
    val v6 = DnsPaket.sestaviPaket(6, v6izvor, v6cilj, 5555, 53, DnsPaket.sestaviPoizvedbo(7, "cdn.phish.si", 28))
    preveriUdp(v6, 6)
    val q6 = DnsPaket.razcleni(v6) ?: throw AssertionError("IPv6 poizvedbe ni")
    preveri(q6.razlicica == 6 && q6.ime == "cdn.phish.si" && q6.vrsta == 28 && q6.izvornaVrata == 5555, "polja IPv6")
    val p6 = DnsPaket.zavijOdgovor(q6, DnsPaket.odgovorBlokirano(q6))
    preveriUdp(p6, 6)
    preveri(p6.copyOfRange(24, 40).contentEquals(v6izvor), "IPv6 cilj odgovora = izvor poizvedbe")

    // smeti: prekratko, TCP, fragment
    preveri(DnsPaket.razcleni(ByteArray(10)) == null, "prekratek")
    val tcp = v4.copyOf(); tcp[9] = 6
    preveri(DnsPaket.razcleni(tcp) == null, "TCP se zavrne")
    val frag = v4.copyOf(); frag[6] = 0x20
    preveri(DnsPaket.razcleni(frag) == null, "fragment se zavrne")
    println("paketi: OK")
}

/**
 * Preverjanje odgovorov: navzgor gre nas ID, nazaj aplikaciji njen; odgovor na drugo vprasanje
 * ali z drugim ID-jem ne sme steti kot odgovor na naso poizvedbo.
 */
fun odgovoriTest() {
    val dns = DnsPaket.sestaviPoizvedbo(0x1234, "example.com", 1)
    val paket = DnsPaket.sestaviPaket(4, byteArrayOf(10, 111, (222).toByte(), 1), byteArrayOf(10, 111, (222).toByte(), 2), 40000, 53, dns)
    val q = DnsPaket.razcleni(paket) ?: throw AssertionError("poizvedbe ni")

    val navzgor = DnsPaket.zId(q.dns, 0xBEEF)
    preveri(DnsPaket.u16(navzgor, 0) == 0xBEEF, "nas ID gre navzgor")
    preveri(DnsPaket.u16(q.dns, 0) == 0x1234, "izvirna poizvedba ostane nedotaknjena")
    preveri(navzgor.size == q.dns.size, "dolzina poizvedbe se ne spremeni")
    preveri(navzgor.copyOfRange(2, navzgor.size).contentEquals(q.dns.copyOfRange(2, q.dns.size)), "spremeni se samo ID")

    // Odgovor streznika: isto vprasanje, nas ID.
    val odgovor = DnsPaket.odgovorBlokirano(DnsPaket.razcleni(
        DnsPaket.sestaviPaket(4, q.izvor, q.cilj, q.izvornaVrata, 53, navzgor))!!)
    val vprasanje = DnsPaket.vprasanjeOdgovora(odgovor) ?: throw AssertionError("vprasanja v odgovoru ni")
    preveri(vprasanje.first == "example.com" && vprasanje.second == 1, "vprasanje iz odgovora: $vprasanje")

    // Odgovor na drugo ime se ne sme ujemati z naso poizvedbo.
    val tuj = DnsPaket.sestaviPoizvedbo(0xBEEF, "napadalec.si", 1)
    val tujeVprasanje = DnsPaket.vprasanjeOdgovora(tuj) ?: throw AssertionError("tujega vprasanja ni")
    preveri(tujeVprasanje.first != vprasanje.first, "tuje ime se razlikuje")

    preveri(DnsPaket.vprasanjeOdgovora(ByteArray(8)) == null, "prekratek odgovor")
    val brezVprasanja = odgovor.copyOf(); brezVprasanja[4] = 0; brezVprasanja[5] = 0
    preveri(DnsPaket.vprasanjeOdgovora(brezVprasanja) == null, "odgovor brez vprasanja")
    println("odgovori: OK")
}

fun main() {
    naborTest()
    paketiTest()
    odgovoriTest()
    println("SCIT TESTI: OK")
}
