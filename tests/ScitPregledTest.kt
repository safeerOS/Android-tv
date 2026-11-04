package si.safeer.tv.scit

/**
 * Lastnikov pregled Scita (3. 11. 2026) in dodatek (4. 11. 2026): ciste odlocitve ([ScitPregled]) in paketi ([DnsPaket]).
 * Vsak preizkus pade brez svojega popravka (preverjeno z zacasnim umikom popravka, 4. 11. 2026).
 */
fun main() {
    val P = ScitPregled
    val nas = byteArrayOf(10, 111, (222).toByte(), 1)
    val navidezni = byteArrayOf(10, 111, (222).toByte(), 2)

    // Tocka 1: samo vrata 53 so DNS. QUIC/drug UDP v tunelu ni poizvedba (prej: vsak UDP se je razclenil kot DNS).
    val dns = DnsPaket.sestaviPoizvedbo(0x4242, "example.com")
    preveri(DnsPaket.razcleni(DnsPaket.sestaviPaket(4, nas, navidezni, 40000, 53, dns)) != null, "UDP 53 je poizvedba")
    preveri(DnsPaket.razcleni(DnsPaket.sestaviPaket(4, nas, navidezni, 40000, 443, dns)) == null, "UDP 443 ni poizvedba DNS")
    preveri(DnsPaket.razcleni(DnsPaket.sestaviPaket(4, nas, navidezni, 40000, 853, dns)) == null, "UDP 853 ni poizvedba DNS")

    // Tocka 3: brez krajevnega DNS ni tujega razresevalca; odgovor SERVFAIL (RCODE 2), ID in vprasanje ostaneta.
    preveri(P.naprej(emptyList<String>()).isEmpty(), "brez krajevnega DNS ni rezerve (prej 1.1.1.1 in 9.9.9.9)")
    preveri(P.naprej(listOf("192.168.1.1")) == listOf("192.168.1.1"), "krajevni ostane")
    val sf = DnsPaket.odgovorNapakaZa(dns)
    preveri(DnsPaket.u16(sf, 0) == 0x4242 && (sf[3].toInt() and 0x0F) == 2 && DnsPaket.jeOdgovor(sf), "SERVFAIL: ID, QR, RCODE 2")
    preveri(DnsPaket.vprasanje(sf)?.ime == "example.com", "SERVFAIL nosi vprasanje")
    preveri((DnsPaket.odgovorBlokiranoZa(dns)[3].toInt() and 0x0F) == 3, "NXDOMAIN ostane RCODE 3")

    // Tocka 2: zasebni DNS z imenom streznika Scit obide; samodejni (privzeti) ne.
    preveri(P.zasebniDnsObide("hostname", null), "hostname obide")
    preveri(P.zasebniDnsObide(null, "dns.example"), "ime streznika v LinkProperties obide")
    preveri(!P.zasebniDnsObide("opportunistic", null), "samodejni ne obide")
    preveri(!P.zasebniDnsObide(null, null) && !P.zasebniDnsObide("off", ""), "izklopljen ne obide")

    // Tocki 4 in 5: zadnja imena - najvec 20, najnovejse na vrhu, brez podvajanja, samo znaki imena DNS.
    var z = emptyList<ScitPregled.Zadnje>()
    for (i in 1..25) z = P.dodajZadnje(z, "oglas$i.example.com", i.toLong())
    preveri(z.size == 20 && z.first().ime == "oglas25.example.com" && z.last().ime == "oglas6.example.com", "najvec 20, najnovejse prvo")
    z = P.dodajZadnje(z, "OGLAS10.example.com.", 99)
    preveri(z.size == 20 && z.first().ime == "oglas10.example.com" && z.count { it.ime == "oglas10.example.com" } == 1, "isto ime na vrh, brez podvajanja")
    preveri(P.dodajZadnje(z, "a\tb\nc", 100) === z, "ime z nadzornimi znaki se ne zapise")
    val nazaj = P.preberiZadnje(P.zapisZadnjih(z) + "\n123\tslabo ime\nx\n")
    preveri(nazaj.size == 20 && nazaj.first().ime == "oglas10.example.com" && nazaj.first().ura == 99L, "zapis in branje")
    preveri(P.preberiZadnje((1..40).joinToString("\n") { "$it\td$it.example.com" }).size == 20, "branje najvec 20")

    // Tocka 4: izjeme - 30 dni, natanko ime, nikoli groznja, brez nalozenega seznama grozenj ne.
    val ura = 1_000_000L
    val groznje = setOf("phish.example.com")
    val m = P.dodajIzjemo(emptyMap(), "ads.example.com", ura, true) { it in groznje } ?: error("izjema ni dodana")
    preveri(m["ads.example.com"] == ura + P.IZJEMA_MS, "30 dni")
    preveri(P.uporabniskaVelja("ads.example.com", m, ura + 1) && !P.uporabniskaVelja("x.ads.example.com", m, ura + 1), "natanko ime")
    preveri(!P.uporabniskaVelja("ads.example.com", m, ura + P.IZJEMA_MS + 1), "po 30 dneh ne velja")
    preveri(P.dodajIzjemo(emptyMap(), "phish.example.com", ura, true) { it in groznje } == null, "groznja nima izjeme")
    preveri(P.dodajIzjemo(emptyMap(), "ads.example.com", ura, false) { false } == null, "brez seznama grozenj ni izjeme")
    preveri(P.dodajIzjemo(emptyMap(), "ads.example.com", ura, true) { throw IllegalStateException() } == null, "napaka pri preverbi = ni izjeme")
    preveri(!P.lahkoSpusti(true, true) && !P.lahkoSpusti(false, false) && P.lahkoSpusti(true, false), "gumb samo za ne-groznjo")
    preveri(P.prekliciIzjemo(m, "ADS.example.com").isEmpty(), "preklic")
    val prebrane = P.preberiIzjeme(P.zapisIzjem(m) + "\nstaro.example.com\t5\nslabo\t${ura * 9}", ura)
    preveri(prebrane.keys == setOf("ads.example.com"), "branje: pretecene in neveljavne ne")
    // Izjema z zaslona Scita in blokiranje (ScitStoritev.blokirana): groznja ostane blokirana tudi z izjemo.
    preveri(!ScitIzjeme.blokirana(true, true, false, P.uporabniskaVelja("ads.example.com", m, ura + 1)), "izjema spusti oglas")
    preveri(ScitIzjeme.blokirana(true, true, true, true), "izjema ne spusti grozenj")

    // Dodatek C: privzeto vklopljen - ponudba enkrat, ne cez uporabnikovo izbiro.
    preveri(P.ponudiObZagonu(vodimo = true, ponujeno = false, vklopljen = false, prejUporabljen = false), "prvi zagon: ponudi")
    preveri(!P.ponudiObZagonu(true, true, false, false), "ze ponujeno: ne sprasuj vsakic")
    preveri(!P.ponudiObZagonu(true, false, false, true), "uporabnik ga je ze imel in izklopil: ostane izklopljen")
    preveri(!P.ponudiObZagonu(false, false, false, false), "Scit vodi drug paket (Safeer OS): ta ne ponudi")
    preveri(!P.ponudiObZagonu(true, false, true, false), "ze vklopljen")

    // Dodatek A in B: meni brskalnika kaze in krmili Scit, ki ga vodi Safeer OS.
    preveri(P.kljukica(vodimo = false, lokalnoVklopljen = false, sosedVklopljen = true), "Scit tece v Safeer OS: kljukica je vklopljena")
    preveri(!P.kljukica(false, true, null), "brez odgovora Safeer OS ne trdimo vklopljen")
    preveri(P.kljukica(true, true, false) && !P.kljukica(true, false, true), "Scit vodi brskalnik: lastno stanje")
    preveri(P.klik(false, true) == ScitPregled.Klik.PREK_SOSEDA_IZKLOPI && P.klik(false, false) == ScitPregled.Klik.PREK_SOSEDA_VKLOPI,
        "klik krmili Scit v Safeer OS (ne drugega VPN v brskalniku)")
    preveri(P.klik(false, null) == ScitPregled.Klik.ZASLON_SOSEDA && P.klik(true, null) == ScitPregled.Klik.LOKALNO, "brez stanja: zaslon Scita v Safeer OS")

    println("SCIT PREGLED: OK")
}
