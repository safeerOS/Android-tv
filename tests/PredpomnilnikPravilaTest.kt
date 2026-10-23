package si.safeer.tv.os

import si.safeer.tv.os.PredpomnilnikPravila.Datoteka
import si.safeer.tv.os.PredpomnilnikPravila.GB
import si.safeer.tv.os.PredpomnilnikPravila.Kandidat
import si.safeer.tv.os.PredpomnilnikPravila.MB
import si.safeer.tv.os.PredpomnilnikPravila.Nacin

/** Naloga 87: predpomnilnik Media Center na USB - izbira mape, meje, TTL in LRU (PredpomnilnikPravila). */
private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

fun main() {
    println("Preizkus predpomnilnika na USB")
    val usb = Kandidat("/mnt/media_rw/usb", odstranljiv = true, priklopljen = true, prostih = 5 * GB, nasih = 0)
    val vgrajena = Kandidat("/storage/emulated/0", odstranljiv = false, priklopljen = true, prostih = 50 * GB, nasih = 0)

    println("\n== izbira ==")
    preveriEnako("samodejno: USB", usb, PredpomnilnikPravila.izberi(Nacin.SAMODEJNO, listOf(vgrajena, usb)))
    preveriEnako("vedno notranji: nikoli USB", null, PredpomnilnikPravila.izberi(Nacin.NOTRANJI, listOf(usb)))
    preveriEnako("vgrajena zunanja shramba ni USB", null, PredpomnilnikPravila.izberi(Nacin.SAMODEJNO, listOf(vgrajena)))
    preveriEnako("odklopljen USB ne", null, PredpomnilnikPravila.izberi(Nacin.SAMODEJNO, listOf(usb.copy(priklopljen = false))))
    preveriEnako("USB z 1 GB prostega ne (meja je VEC kot 1 GB)", null, PredpomnilnikPravila.izberi(Nacin.SAMODEJNO, listOf(usb.copy(prostih = GB))))
    preveriEnako("poln USB z nasim predpomnilnikom ostane", true,
        PredpomnilnikPravila.izberi(Nacin.SAMODEJNO, listOf(usb.copy(prostih = 300 * MB, nasih = 2 * GB))) != null)
    preveriEnako("brez kandidatov", null, PredpomnilnikPravila.izberi(Nacin.SAMODEJNO, emptyList()))
    preveriEnako("naprava v Linku: nikoli USB", null, PredpomnilnikPravila.izberi(Nacin.LINK, listOf(usb)))
    preveriEnako("mesto iz niza: notranji", Nacin.NOTRANJI, PredpomnilnikPravila.mestoIzNiza("notranji").nacin)
    preveriEnako("mesto: neznano je samodejno", Nacin.SAMODEJNO, PredpomnilnikPravila.mestoIzNiza(null).nacin)
    preveriEnako("mesto: naprava v Linku", PredpomnilnikPravila.Mesto(Nacin.LINK, "n-abc-control"), PredpomnilnikPravila.mestoIzNiza("link:n-abc-control"))
    preveriEnako("mesto: pokvarjen id naprave -> samodejno", Nacin.SAMODEJNO, PredpomnilnikPravila.mestoIzNiza("link:a b/../c").nacin)
    preveriEnako("mesto: prazen id -> samodejno", Nacin.SAMODEJNO, PredpomnilnikPravila.mestoIzNiza("link:").nacin)
    for (m in listOf(PredpomnilnikPravila.Mesto(Nacin.NOTRANJI), PredpomnilnikPravila.Mesto(Nacin.SAMODEJNO), PredpomnilnikPravila.Mesto(Nacin.LINK, "n-1")))
        preveriEnako("mesto tja in nazaj: $m", m, PredpomnilnikPravila.mestoIzNiza(PredpomnilnikPravila.mestoVNiz(m)))

    println("\n== meje ==")
    preveriEnako("privzeto 2 GB", 2 * GB, PredpomnilnikPravila.meja(PredpomnilnikPravila.PRIVZETA_GB, usb))
    preveriEnako("8 GB na USB s 5 GB: prostor - rezerva", 5 * GB - 256 * MB, PredpomnilnikPravila.meja(8, usb))
    preveriEnako("nase datoteke stejejo v prostor", 4 * GB, PredpomnilnikPravila.meja(4, usb.copy(prostih = GB, nasih = 4 * GB)))
    preveriEnako("neznana velikost -> privzeta", 2, PredpomnilnikPravila.velikostGb(3))
    preveriEnako("ponujena velikost ostane", 8, PredpomnilnikPravila.velikostGb(8))
    preveriEnako("nikoli negativna", 0L, PredpomnilnikPravila.meja(2, usb.copy(prostih = 10 * MB)))

    println("\n== TTL in LRU ==")
    val dan = 24 * 3_600_000L
    val zdaj = 100 * dan
    preveriEnako("TTL naslovnice 14 dni", 14 * dan, PredpomnilnikPravila.ttl(PredpomnilnikPravila.NASLOVNICE))
    preveriEnako("TTL katalogi 24 h", dan, PredpomnilnikPravila.ttl(PredpomnilnikPravila.KATALOGI))
    val d = listOf(
        Datoteka("a", PredpomnilnikPravila.KATALOGI, 10, zdaj - 2 * dan),        // zastarel katalog
        Datoteka("b", PredpomnilnikPravila.NASLOVNICE, 10, zdaj - 2 * dan),      // naslovnica velja
        Datoteka("c", PredpomnilnikPravila.NASLOVNICE, 10, zdaj - dan),
        Datoteka("d", PredpomnilnikPravila.POLICE, 10, zdaj - 1000),
        Datoteka("e.tmp", PredpomnilnikPravila.POLICE, 10, zdaj - 2 * 3_600_000L), // stara zacasna
        Datoteka("f", PredpomnilnikPravila.POLICE, 10, zdaj + 3 * dan),           // ura iz prihodnosti
    )
    preveriEnako("pod mejo: samo zastarele, zacasne in iz prihodnosti", listOf("a", "e.tmp", "f"), PredpomnilnikPravila.zaBrisanje(d, 1000, zdaj))
    preveriEnako("nad mejo: se najdlje neuporabljene (LRU)", listOf("a", "e.tmp", "f", "b"), PredpomnilnikPravila.zaBrisanje(d, 20, zdaj))
    preveriEnako("meja 0: vse", 6, PredpomnilnikPravila.zaBrisanje(d, 0, zdaj).size)
    preveriEnako("sveza zacasna ostane", emptyList<String>(),
        PredpomnilnikPravila.zaBrisanje(listOf(Datoteka("g.tmp", PredpomnilnikPravila.POLICE, 1, zdaj - 1000)), 1000, zdaj))

    println("\n== kdaj ciscenje ==")
    preveriEnako("prvi zapis procesa", true, PredpomnilnikPravila.jeCasZaCiscenje(1, 0, zdaj, 2 * GB))
    preveriEnako("malo zapisano, pred kratkim", false, PredpomnilnikPravila.jeCasZaCiscenje(10 * MB, zdaj - 60_000, zdaj, 2 * GB))
    preveriEnako("vec kot dvajsetina meje", true, PredpomnilnikPravila.jeCasZaCiscenje(110 * MB, zdaj - 60_000, zdaj, 2 * GB))
    preveriEnako("10 min od zadnjega", true, PredpomnilnikPravila.jeCasZaCiscenje(1, zdaj - 10 * 60_000, zdaj, 2 * GB))
    preveriEnako("ura nazaj", true, PredpomnilnikPravila.jeCasZaCiscenje(1, zdaj + 60_000, zdaj, 2 * GB))

    println("\n== naprava v Linku (57b) ==")
    val P = PredpomnilnikPravila
    preveriEnako("plakat sme", true, P.smeNaLink(P.NASLOVNICE, 40_000, zasebno = false))
    preveriEnako("zasebno nikoli", false, P.smeNaLink(P.POLICE, 1000, zasebno = true))
    preveriEnako("seznam IPTV ne", false, P.smeNaLink(P.IPTV, 1000, zasebno = false))
    preveriEnako("strani kanalov ne", false, P.smeNaLink(P.KANALI, 1000, zasebno = false))
    preveriEnako("prevelik ne", false, P.smeNaLink(P.KATALOGI, P.LINK_NAJVEC_BAJTOV + 1, zasebno = false))
    preveriEnako("prazen ne", false, P.smeNaLink(P.KATALOGI, 0, zasebno = false))
    val odtis = "0123456789abcdef0123456789abcdef01234567"
    preveriEnako("kljuc: vrsta/odtis", "police/$odtis", P.kljucLink(P.POLICE, odtis))
    preveriEnako("kljuc: ni odtis (naslov) -> null", null, P.kljucLink(P.POLICE, "https://x.tv/a"))
    preveriEnako("kljuc: vrsta, ki ne gre po Linku -> null", null, P.kljucLink(P.IPTV, odtis))
    preveriEnako("odgovor: svez", true, P.veljavenOdgovor(P.KATALOGI, zdaj - 3_600_000L, zdaj))
    preveriEnako("odgovor: zastarel katalog", false, P.veljavenOdgovor(P.KATALOGI, zdaj - 2 * dan, zdaj))
    preveriEnako("odgovor: iz prihodnosti", false, P.veljavenOdgovor(P.KATALOGI, zdaj + 2 * dan, zdaj))
    preveriEnako("odgovor: brez casa", false, P.veljavenOdgovor(P.KATALOGI, 0, zdaj))
    preveriEnako("varovalka: 2 napaki - se sprasujemo", true, P.linkSme(2, zdaj, zdaj))
    preveriEnako("varovalka: 3 napake - 60 s ne", false, P.linkSme(3, zdaj - 59_000, zdaj))
    preveriEnako("varovalka: po 60 s znova", true, P.linkSme(3, zdaj - 60_000, zdaj))
    preveriEnako("izbira naprav: samo z zmoznostjo cache, brez sebe, po imenu", listOf("b" to "Ana PC", "c" to "zadnji"),
        P.napraveZaIzbiro(listOf(Triple("c", "zadnji", listOf("cache")), Triple("a", "TV", listOf("cache")), Triple("b", "Ana PC", listOf("files", "cache")),
            Triple("d", "Telefon", listOf("files"))), jaz = "a"))

    println("\n== SQLite (57c) ==")
    val vnosi = listOf(
        PredpomnilnikPravila.Vnos("k/a", 100, zdaj - 5 * dan, zdaj - 1),        // potekel
        PredpomnilnikPravila.Vnos("n/b", 100, zdaj - 3 * dan, zdaj + dan),
        PredpomnilnikPravila.Vnos("n/c", 100, zdaj - dan, zdaj + dan),
        PredpomnilnikPravila.Vnos("p/d", 100, zdaj, zdaj + dan),
        PredpomnilnikPravila.Vnos("p/e", 100, zdaj + 2 * dan, zdaj + 9 * dan),  // uporaba iz prihodnosti
    )
    preveriEnako("baza pod mejo: potekel in iz prihodnosti", listOf("k/a", "p/e"), P.zaBrisanjeVnosov(vnosi, 10_000, zdaj))
    preveriEnako("baza nad mejo: se LRU", listOf("k/a", "p/e", "n/b", "n/c"), P.zaBrisanjeVnosov(vnosi, 100, zdaj))
    preveriEnako("baza: tocno na meji ostane", listOf("k/a", "p/e"), P.zaBrisanjeVnosov(vnosi, 300, zdaj))
    preveriEnako("baza: prazna", emptyList<String>(), P.zaBrisanjeVnosov(emptyList(), 0, zdaj))
    preveriEnako("poteka: katalog 24 h", zdaj + dan, P.poteka(P.KATALOGI, zdaj))
    preveriEnako("uporaba: pred 10 min se ne pise", false, P.osveziUporabo(zdaj - 600_000, zdaj))
    preveriEnako("uporaba: pred 2 h se pise", true, P.osveziUporabo(zdaj - 2 * 3_600_000L, zdaj))
    preveriEnako("uporaba: ura nazaj se pise", true, P.osveziUporabo(zdaj + 60_000, zdaj))
    preveriEnako("kljuc baze", "police/abc", P.kljucBaze(P.POLICE, "abc"))
    preveriEnako("ime velike datoteke brez locil", "kanali_abc", P.imeVelike("kanali/abc"))
    preveriEnako("ime velike: brez poti ven", "kanalietc", P.imeVelike("kanali../../etc").replace("_", ""))

    println("\n== predvajane skladbe na USB (naloga 89) ==")
    preveriEnako("delitev 2 GB: IPTV cetrtina, zvok polovica, baza ostanek", PredpomnilnikPravila.Delitev(512 * MB, 1024 * MB, 512 * MB),
        PredpomnilnikPravila.delitevUsb(2 * GB))
    preveriEnako("delitev: vsota je meja (liha)", 1001L, PredpomnilnikPravila.delitevUsb(1001).let { it.iptv + it.zvok + it.baza })
    preveriEnako("delitev: negativna meja je 0", PredpomnilnikPravila.Delitev(0, 0, 0), PredpomnilnikPravila.delitevUsb(-5))
    preveriEnako("skladba sme", true, PredpomnilnikPravila.smeVPredpomnilnikZvoka(zvok = true, vZivo = false, http = true, enodelni = true, zasebno = false))
    preveriEnako("video ne", false, PredpomnilnikPravila.smeVPredpomnilnikZvoka(zvok = false, vZivo = false, http = true, enodelni = true, zasebno = false))
    preveriEnako("radio v zivo ne", false, PredpomnilnikPravila.smeVPredpomnilnikZvoka(zvok = true, vZivo = true, http = true, enodelni = true, zasebno = false))
    preveriEnako("datoteka na napravi (ni http) ne", false, PredpomnilnikPravila.smeVPredpomnilnikZvoka(zvok = true, vZivo = false, http = false, enodelni = true, zasebno = false))
    preveriEnako("HLS ne", false, PredpomnilnikPravila.smeVPredpomnilnikZvoka(zvok = true, vZivo = false, http = true, enodelni = false, zasebno = false))
    preveriEnako("zaseben dodatek nikoli", false, PredpomnilnikPravila.smeVPredpomnilnikZvoka(zvok = true, vZivo = false, http = true, enodelni = true, zasebno = true))
    preveriEnako("kljuc: brez pripone toka", "zvok|stremio|music|tt1|https://a.test",
        PredpomnilnikPravila.kljucZvoka("stremio|music|tt1|https://a.test#123456"))
    preveriEnako("kljuc: id brez pripone", "zvok|splet:https://www.youtube.com/watch?v=x", PredpomnilnikPravila.kljucZvoka("splet:https://www.youtube.com/watch?v=x"))
    preveriEnako("cela", true, PredpomnilnikPravila.jeCelota(1000, 1000))
    preveriEnako("delna", false, PredpomnilnikPravila.jeCelota(1000, 999))
    preveriEnako("neznana dolzina ni cela", false, PredpomnilnikPravila.jeCelota(-1, 5000))
    preveriEnako("razlog: sme = prazno", "", PredpomnilnikPravila.razlogBrezPredpomnjenja(zvok = true, vZivo = false, http = true, enodelni = true, zasebno = false))
    preveriEnako("razlog: video", "video", PredpomnilnikPravila.razlogBrezPredpomnjenja(zvok = false, vZivo = false, http = true, enodelni = true, zasebno = false))
    preveriEnako("razlog: HLS", "HLS ali DASH", PredpomnilnikPravila.razlogBrezPredpomnjenja(zvok = true, vZivo = false, http = true, enodelni = false, zasebno = false))
    preveriEnako("razlog: zaseben", "zasebni dodatek", PredpomnilnikPravila.razlogBrezPredpomnjenja(zvok = true, vZivo = false, http = true, enodelni = true, zasebno = true))
    preveriEnako("TTL besedil 30 dni", 30 * dan, PredpomnilnikPravila.ttl(PredpomnilnikPravila.BESEDILA))
    preveriEnako("besedila ne gredo na napravo v Linku", false, PredpomnilnikPravila.smeNaLink(PredpomnilnikPravila.BESEDILA, 100, zasebno = false))

    if (napak == 0) println("PredpomnilnikPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
