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
 * Regresijski testi za preverjanje odgovorov DNS (DnsPaket.ustrezaOdgovor).
 *
 * To je obramba pred dvema stvarema: da odgovor dobi napacna aplikacija (dve hkratni poizvedbi z
 * istim ID-jem) in da nam kdo v omrezju podtakne svoj odgovor. Zato tu preizkusamo natanko tisto
 * logiko, ki jo uporablja storitev - ne le pomoznih funkcij.
 */
fun odgovoriTest() {
    val nas = byteArrayOf(10, 111, (222).toByte(), 1)
    val navidezni = byteArrayOf(10, 111, (222).toByte(), 2)
    val streznik = byteArrayOf((192).toByte(), (168).toByte(), 0, 1)
    val drugStreznik = byteArrayOf((192).toByte(), (168).toByte(), 0, 135.toByte())

    fun poizvedba(id: Int, ime: String, vrsta: Int = 1, vrata: Int = 40000): DnsPaket.Poizvedba =
        DnsPaket.razcleni(DnsPaket.sestaviPaket(4, nas, navidezni, vrata, 53,
            DnsPaket.sestaviPoizvedbo(id, ime, vrsta))) ?: throw AssertionError("poizvedbe ni")

    /** Odgovor streznika: isto vprasanje, dani ID, postavljena zastavica QR. */
    fun odgovor(nasId: Int, ime: String, vrsta: Int = 1, razred: Int = 1): ByteArray {
        val d = DnsPaket.sestaviPoizvedbo(nasId, ime, vrsta)
        d[2] = (d[2].toInt() or 0x80).toByte()
        DnsPaket.put16(d, d.size - 2, razred)
        return d
    }

    val q = poizvedba(0x1234, "example.com")
    preveri(q.razred == 1, "razred vprasanja: ${q.razred}")

    // nas ID gre navzgor, izvirna poizvedba ostane nedotaknjena
    val navzgor = DnsPaket.zId(q.dns, 0xBEEF)
    preveri(DnsPaket.u16(navzgor, 0) == 0xBEEF && DnsPaket.u16(q.dns, 0) == 0x1234, "zamenjan samo ID")
    preveri(navzgor.copyOfRange(2, navzgor.size).contentEquals(q.dns.copyOfRange(2, q.dns.size)), "ostalo enako")

    val dober = odgovor(0xBEEF, "example.com")
    preveri(DnsPaket.ustrezaOdgovor(q, 0xBEEF, streznik, streznik, 53, dober), "pravi odgovor se sprejme")

    // napacen posiljatelj, vrata, ID
    preveri(!DnsPaket.ustrezaOdgovor(q, 0xBEEF, streznik, drugStreznik, 53, dober), "drug streznik se zavrne")
    preveri(!DnsPaket.ustrezaOdgovor(q, 0xBEEF, streznik, streznik, 5353, dober), "druga vrata se zavrnejo")
    preveri(!DnsPaket.ustrezaOdgovor(q, 0xBEEF, streznik, streznik, 53, odgovor(0x1111, "example.com")), "drug ID se zavrne")

    // podtaknjen odgovor na drugo vprasanje, drugo vrsto, drug razred
    preveri(!DnsPaket.ustrezaOdgovor(q, 0xBEEF, streznik, streznik, 53, odgovor(0xBEEF, "napadalec.si")), "drugo ime se zavrne")
    preveri(!DnsPaket.ustrezaOdgovor(q, 0xBEEF, streznik, streznik, 53, odgovor(0xBEEF, "example.com", 28)), "druga vrsta se zavrne")
    preveri(!DnsPaket.ustrezaOdgovor(q, 0xBEEF, streznik, streznik, 53, odgovor(0xBEEF, "example.com", 1, 3)), "drug razred se zavrne")

    // poizvedba (brez QR) ni odgovor; prekratko sporocilo ni odgovor
    val brezQr = DnsPaket.sestaviPoizvedbo(0xBEEF, "example.com")
    preveri(!DnsPaket.ustrezaOdgovor(q, 0xBEEF, streznik, streznik, 53, brezQr), "poizvedba se ne sprejme kot odgovor")
    preveri(!DnsPaket.ustrezaOdgovor(q, 0xBEEF, streznik, streznik, 53, dober, 8), "prekratek odgovor se zavrne")

    // dve aplikaciji z istim ID-jem: vsaka dobi samo svoj odgovor
    val a = poizvedba(0x0007, "prva.si", vrata = 40001)
    val b = poizvedba(0x0007, "druga.si", vrata = 40002)
    val zaA = odgovor(0x1000, "prva.si")
    val zaB = odgovor(0x2000, "druga.si")
    preveri(DnsPaket.ustrezaOdgovor(a, 0x1000, streznik, streznik, 53, zaA), "A dobi svojega")
    preveri(DnsPaket.ustrezaOdgovor(b, 0x2000, streznik, streznik, 53, zaB), "B dobi svojega")
    preveri(!DnsPaket.ustrezaOdgovor(a, 0x1000, streznik, streznik, 53, zaB), "A ne dobi odgovora za B")
    preveri(!DnsPaket.ustrezaOdgovor(b, 0x2000, streznik, streznik, 53, zaA), "B ne dobi odgovora za A")

    // vprasanje in zastavica QR
    val v = DnsPaket.vprasanje(dober) ?: throw AssertionError("vprasanja ni")
    preveri(v.ime == "example.com" && v.vrsta == 1 && v.razred == 1, "vprasanje iz odgovora")
    preveri(DnsPaket.jeOdgovor(dober) && !DnsPaket.jeOdgovor(brezQr), "zastavica QR")
    preveri(DnsPaket.vprasanje(ByteArray(8)) == null, "prekratko sporocilo")

    println("odgovori: OK")
}

fun main() {
    naborTest()
    paketiTest()
    odgovoriTest()
    tcpDnsTest()
    println("SCIT TESTI: OK")
}
