package si.safeer.tv.scit

/**
 * Testi DNS prek TCP brez Androida: celoten potek (SYN, vprasanje, odgovor, FIN) in robovi.
 *
 * Tu preizkusamo prav tisto logiko, ki tece na televizorju - [TcpDns] posiljanje in razreševanje
 * dobi od zunaj, zato je v testu lahko zapisnik paketov in ponarejen razreševalec.
 */

private val ODJEMALEC = byteArrayOf(10, 111, (222).toByte(), 1)
private val STREZNIK = byteArrayOf(10, 111, (222).toByte(), 2)

/** Sestavi segment od aplikacije proti navideznemu strezniku DNS. */
private fun odOdjemalca(vrata: Int, seq: Long, ack: Long, zastavice: Int, tovor: ByteArray = ByteArray(0)): ByteArray {
    val vzorec = TcpPaket.Segment(4, STREZNIK, ODJEMALEC, 53, vrata, 0, 0, 0, ByteArray(0))
    // sestavi() obrne smer, zato podamo vzorec z zamenjanima stranema.
    return TcpPaket.sestavi(vzorec, seq, ack, zastavice, tovor)
}

private fun sporociloDns(id: Int, ime: String): ByteArray = DnsPaket.sestaviPoizvedbo(id, ime)

private fun sTcpDolzino(d: ByteArray): ByteArray {
    val o = ByteArray(2 + d.size)
    DnsPaket.put16(o, 0, d.size)
    System.arraycopy(d, 0, o, 2, d.size)
    return o
}

fun tcpDnsTest() {
    val poslano = ArrayList<TcpPaket.Segment>()
    var zadnjeVprasanje: ByteArray? = null
    var odgovorNaj: ByteArray? = null
    val dns = TcpDns({ p -> TcpPaket.razcleni(p)?.let { poslano.add(it) } ?: throw AssertionError("nas paket ni TCP") },
        { vprasanje, odgovor -> zadnjeVprasanje = vprasanje; odgovor(odgovorNaj) })

    // ---------------------------------------------------------------- navadna poizvedba
    val vrata = 40000
    val cIsn = 1000L
    preveri(dns.naPaket(odOdjemalca(vrata, cIsn, 0, TcpPaket.SYN)), "SYN je TCP")
    preveri(poslano.size == 1, "en odgovor na SYN")
    val synAck = poslano[0]
    preveri(synAck.ima(TcpPaket.SYN) && synAck.ima(TcpPaket.ACK), "SYN+ACK")
    preveri(synAck.ack == cIsn + 1, "ACK kaze na cIsn+1: ${synAck.ack}")
    preveri(synAck.ciljnaVrata == vrata && synAck.izvornaVrata == 53, "zamenjana vrata")
    val sIsn = synAck.seq
    poslano.clear()

    dns.naPaket(odOdjemalca(vrata, cIsn + 1, sIsn + 1, TcpPaket.ACK))
    preveri(poslano.isEmpty(), "na ACK ne odgovarjamo")

    val vprasanje = sporociloDns(0x2222, "example.com")
    val odgovorStreznika = DnsPaket.odgovorBlokiranoZa(vprasanje)   // poljubno veljavno sporocilo DNS
    odgovorNaj = odgovorStreznika
    dns.naPaket(odOdjemalca(vrata, cIsn + 1, sIsn + 1, TcpPaket.PSH or TcpPaket.ACK, sTcpDolzino(vprasanje)))
    preveri(zadnjeVprasanje != null && zadnjeVprasanje!!.contentEquals(vprasanje), "razreševalec je dobil pravo vprasanje")
    preveri(poslano.size >= 3, "ACK, podatki in FIN: ${poslano.size}")
    val ack = poslano[0]
    preveri(ack.zastavice == TcpPaket.ACK, "prvi je ACK")
    preveri(ack.ack == cIsn + 1 + 2 + vprasanje.size, "ACK potrdi vse prejeto: ${ack.ack}")
    val podatki = poslano[1]
    preveri(podatki.ima(TcpPaket.PSH) && podatki.seq == sIsn + 1, "podatki se zacnejo pri sIsn+1")
    val prejeto = podatki.tovor
    preveri(DnsPaket.u16(prejeto, 0) == odgovorStreznika.size, "dolzinska predpona")
    preveri(prejeto.copyOfRange(2, prejeto.size).contentEquals(odgovorStreznika), "odgovor je nespremenjen")
    val fin = poslano.last()
    preveri(fin.ima(TcpPaket.FIN), "na koncu FIN")
    preveri(fin.seq == sIsn + 1 + prejeto.size, "FIN za podatki: ${fin.seq}")
    poslano.clear()

    // odjemalcev FIN: potrdimo ga in povezavo pozabimo
    dns.naPaket(odOdjemalca(vrata, cIsn + 1 + 2 + vprasanje.size, fin.seq + 1, TcpPaket.FIN or TcpPaket.ACK))
    preveri(poslano.any { it.ima(TcpPaket.ACK) }, "FIN potrdimo")
    preveri(dns.stevilo() == 0, "povezava je zaprta: ${dns.stevilo()}")
    poslano.clear()

    // ---------------------------------------------------------------- vprasanje v dveh kosih
    val vrata2 = 40001
    dns.naPaket(odOdjemalca(vrata2, 5000, 0, TcpPaket.SYN))
    val sIsn2 = poslano[0].seq
    poslano.clear()
    val celo = sTcpDolzino(sporociloDns(0x3333, "razdeljeno.si"))
    zadnjeVprasanje = null
    dns.naPaket(odOdjemalca(vrata2, 5001, sIsn2 + 1, TcpPaket.ACK, celo.copyOfRange(0, 5)))
    preveri(zadnjeVprasanje == null, "polovicno vprasanje se ne razresuje")
    dns.naPaket(odOdjemalca(vrata2, 5001 + 5, sIsn2 + 1, TcpPaket.PSH or TcpPaket.ACK, celo.copyOfRange(5, celo.size)))
    preveri(zadnjeVprasanje != null, "sestavljeno vprasanje se razresi")
    poslano.clear()

    // ---------------------------------------------------------------- velik odgovor gre v vec kosih
    val vrata3 = 40002
    dns.naPaket(odOdjemalca(vrata3, 7000, 0, TcpPaket.SYN))
    val sIsn3 = poslano[0].seq
    poslano.clear()
    val velik = ByteArray(3000).also { System.arraycopy(sporociloDns(0x4444, "velik.si"), 0, it, 0, 20) }
    odgovorNaj = velik
    dns.naPaket(odOdjemalca(vrata3, 7001, sIsn3 + 1, TcpPaket.PSH or TcpPaket.ACK,
        sTcpDolzino(sporociloDns(0x4444, "velik.si"))))
    val kosi = poslano.filter { it.tovor.isNotEmpty() }
    preveri(kosi.size >= 3, "velik odgovor gre v vec kosih: ${kosi.size}")
    preveri(kosi.all { it.tovor.size <= TcpDns.KOS }, "noben kos ni vecji od ${TcpDns.KOS}")
    var skupaj = ByteArray(0)
    for (k in kosi) skupaj += k.tovor
    preveri(skupaj.size == 2 + velik.size, "vsi kosi skupaj so cel odgovor: ${skupaj.size}")
    preveri(skupaj.copyOfRange(2, skupaj.size).contentEquals(velik), "vsebina po sestavljanju")
    var pricakovanSeq = sIsn3 + 1
    for (k in kosi) { preveri(k.seq == pricakovanSeq, "zaporedne stevilke tecejo: ${k.seq}"); pricakovanSeq += k.tovor.size }
    poslano.clear()

    // ---------------------------------------------------------------- robovi
    // segment brez povezave dobi RST
    dns.naPaket(odOdjemalca(40003, 900, 900, TcpPaket.PSH or TcpPaket.ACK, byteArrayOf(1, 2, 3)))
    preveri(poslano.any { it.ima(TcpPaket.RST) }, "brez povezave je RST")
    poslano.clear()

    // napacna vrata (ne 53) ne odprejo povezave
    val nekaDruga = TcpPaket.sestavi(TcpPaket.Segment(4, STREZNIK, ODJEMALEC, 8080, 40004, 0, 0, 0, ByteArray(0)),
        10, 0, TcpPaket.SYN)
    dns.naPaket(nekaDruga)
    preveri(poslano.any { it.ima(TcpPaket.RST) }, "druga vrata dobijo RST")
    preveri(dns.stevilo() == 2, "odprti ostaneta samo prejsnji povezavi: ${dns.stevilo()}")
    poslano.clear()

    // napaka razreševalca poruši povezavo
    val vrata5 = 40005
    dns.naPaket(odOdjemalca(vrata5, 8000, 0, TcpPaket.SYN))
    val sIsn5 = poslano[0].seq
    poslano.clear()
    odgovorNaj = null
    dns.naPaket(odOdjemalca(vrata5, 8001, sIsn5 + 1, TcpPaket.PSH or TcpPaket.ACK,
        sTcpDolzino(sporociloDns(0x5555, "brez-odgovora.si"))))
    preveri(poslano.any { it.ima(TcpPaket.RST) }, "brez odgovora posljemo RST")
    poslano.clear()

    // pokvarjena dolzinska predpona
    val vrata6 = 40006
    dns.naPaket(odOdjemalca(vrata6, 9000, 0, TcpPaket.SYN))
    val sIsn6 = poslano[0].seq
    poslano.clear()
    dns.naPaket(odOdjemalca(vrata6, 9001, sIsn6 + 1, TcpPaket.PSH or TcpPaket.ACK, byteArrayOf(0, 3, 1, 2, 3)))
    preveri(poslano.any { it.ima(TcpPaket.RST) }, "prekratko sporocilo DNS dobi RST")
    poslano.clear()

    // ni TCP: paket ni nas
    preveri(!dns.naPaket(DnsPaket.sestaviPaket(4, ODJEMALEC, STREZNIK, 40007, 53,
        DnsPaket.sestaviPoizvedbo(1, "udp.si"))), "UDP ni nas")

    // IPv6 pot
    val v6odjemalec = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }
    val v6streznik = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 2 }
    val syn6 = TcpPaket.sestavi(TcpPaket.Segment(6, v6streznik, v6odjemalec, 53, 40008, 0, 0, 0, ByteArray(0)),
        11000, 0, TcpPaket.SYN)
    poslano.clear()
    preveri(dns.naPaket(syn6), "IPv6 SYN je TCP")
    preveri(poslano.size == 1 && poslano[0].razlicica == 6 && poslano[0].ima(TcpPaket.SYN), "IPv6 SYN+ACK")

    // cistilec pobrise mirujoce
    dns.pocisti()
    println("tcp: OK")
}
