package si.safeer.tv.cast

import si.safeer.tv.cast.DostopPravila.Zahteva
import si.safeer.tv.cast.DostopPravila.Zmoznost

private var napak = 0

private fun preveri(kaj: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $kaj") else { println("FAIL   $kaj"); napak++ }
}

fun main() {
    val p = DostopPravila
    println("== dostop naprav do vsebin (seznanitev ni dovoljenje) ==")

    // Kaj zahteva katero dejanje.
    preveri("datoteke: seznam, iskanje, odpiranje, shramba",
        listOf("files.list", "files.search", "files.open", "storage.put", "storage.status").all { p.zahtevaDejanja(it) == Zahteva.DATOTEKE })
    preveri("programi: seznam, zagon, zapiranje",
        listOf("apps.list", "apps.launch", "apps.close", "apps.running", "apps", "launch_app", "open_in_app").all { p.zahtevaDejanja(it) == Zahteva.PROGRAMI })
    preveri("predvajalnik: kaj se predvaja, nadaljevanje, seznami in viri",
        listOf("play.state", "play.stop", "lists.get").all { p.zahtevaDejanja(it) == Zahteva.PREDVAJALNIK })
    preveri("predvajalnik: odpiranje vsebine brez vprasanja in zvok druge naprave",
        listOf("open_url", "magnet.open", "audio.play", "audio.stop").all { p.zahtevaDejanja(it) == Zahteva.PREDVAJALNIK })
    preveri("predvajalnik: pomoc pri predvajanju (torrent, pretvorba, dodatki, zmogljivost)",
        listOf("magnet.stream", "magnet.list", "magnet.remove", "magnet.keep", "video.transcode", "video.status", "video.stream",
            "video.stream_stop", "video.stream_status", "avail.get", "host.info").all { p.zahtevaDejanja(it) == Zahteva.PREDVAJALNIK })
    preveri("zaslon in upravljanje: zaslon, tipke, vnos, glasnost, stanje",
        listOf("screen.start", "screen.stop", "screen.status", "key", "scroll", "screenshot", "volume", "restart", "clear_cache",
            "status", "input.tap", "input.text", "input.enable", "gamepad.button", "gamepad.axis").all { p.zahtevaDejanja(it) == Zahteva.ZASLON })
    preveri("ponudba »Poslji na napravo« je prosta (uporabnik jo sprejme ali zavrne)", p.zahtevaDejanja("play.offer") == Zahteva.PROSTO)
    preveri("neznano dejanje zahteva vse", p.zahtevaDejanja("racun.izprazni") == Zahteva.VSE && p.zahtevaDejanja("") == Zahteva.VSE)
    preveri("velike crke in presledki ne odprejo nicesar", p.zahtevaDejanja("  Files.List ") == Zahteva.DATOTEKE)

    // Sporocila, ki niso ukazi.
    preveri("stran, nadzor predvajanja in predaja zahtevajo predvajalnik",
        listOf("cast.url", "cast.control", "handoff.request").all { p.zahtevaSporocila(it) == Zahteva.PREDVAJALNIK })
    preveri("usklajevanje stanja samo ozji krog (odprto vse)", p.zahtevaSporocila("sync.data") == Zahteva.VSE)
    preveri("predvajalnik sam za usklajevanje ne zadosca",
        !p.sme(setOf(DostopPravila.Zmoznost.PREDVAJALNIK), p.zahtevaSporocila("sync.data")) && p.sme(p.VSE_ZMOZNOSTI, p.zahtevaSporocila("sync.data")))
    preveri("zaslon druge naprave se odpre le z dovoljenjem; konec deljenja vedno",
        p.zahtevaSporocila("share.screen", "start") == Zahteva.ZASLON && p.zahtevaSporocila("share.screen", "stop") == Zahteva.PROSTO)
    preveri("besedilo, datoteka in klepet so izrecno poslani - prosto",
        listOf("share.text", "share.file", "chat.send").all { p.zahtevaSporocila(it) == Zahteva.PROSTO })

    // Odlocanje.
    val nic = emptySet<Zmoznost>()
    val datoteke = setOf(Zmoznost.DATOTEKE)
    preveri("naprava brez dovoljenj sme samo prosto",
        p.sme(nic, Zahteva.PROSTO) && Zahteva.values().filter { it != Zahteva.PROSTO }.none { p.sme(nic, it) })
    preveri("dovoljenje za datoteke ne odpre programov, predvajalnika ali zaslona",
        p.sme(datoteke, Zahteva.DATOTEKE) && !p.sme(datoteke, Zahteva.PROGRAMI) && !p.sme(datoteke, Zahteva.PREDVAJALNIK) &&
            !p.sme(datoteke, Zahteva.ZASLON) && !p.sme(datoteke, Zahteva.VSE))
    preveri("neznano dejanje sme le naprava z vsemi dovoljenji",
        p.sme(p.VSE_ZMOZNOSTI, Zahteva.VSE) && !p.sme(p.VSE_ZMOZNOSTI - Zmoznost.ZASLON, Zahteva.VSE))

    // Zapis v shrambi.
    preveri("zapis in branje naborov", p.vNiz(p.VSE_ZMOZNOSTI) == "dpvz" && p.vNiz(nic) == "" &&
        p.izNiza("dpvz") == p.VSE_ZMOZNOSTI && p.izNiza("zd") == setOf(Zmoznost.DATOTEKE, Zmoznost.ZASLON) && p.izNiza(null) == nic && p.izNiza("xy?") == nic)

    // Stalna oznaka naprave.
    val kljuci = mapOf("tv-stari-id" to "KLJUC-TV")
    val idIzKljuca: (String) -> String = { "n-" + it.lowercase().filter { z -> z in '0'..'9' || z in 'a'..'f' }.padEnd(16, '0').take(16) }
    preveri("id iz kljuca s pripono sorodnika da isto jedro",
        p.jedro("n-0123456789abcdef-os", { null }, idIzKljuca) == "n-0123456789abcdef" &&
            p.jedro("n-0123456789abcdef-control", { null }, idIzKljuca) == "n-0123456789abcdef" &&
            p.jedro("n-0123456789abcdef", { null }, idIzKljuca) == "n-0123456789abcdef")
    preveri("star id se prevede prek kljuca v krogu", p.jedro("tv-stari-id", { kljuci[it] }, idIzKljuca) == idIzKljuca("KLJUC-TV"))
    preveri("naprava, ki je krog ne pozna, ostane pri svojem id-ju", p.jedro("tujec", { null }, idIzKljuca) == "tujec")
    preveri("id, ki je le podoben id-ju iz kljuca, ni jedro",
        !p.jeIdIzKljuca("n-0123456789abcdefX") && !p.jeIdIzKljuca("n-0123456789ABCDEF") && !p.jeIdIzKljuca("n-012345") && p.jeIdIzKljuca("n-0123456789abcdef-os"))

    // Kdo ob uvedbi dovoljenj obdrzi dostop.
    val meja = p.MEJA_PODEDOVANJA
    val jaz = "aaaa000000000001"; val tablica = "bbbb000000000002"; val televizor = "cccc000000000003"; val gost = "dddd000000000004"
    val krog = listOf(
        DostopPravila.Clan("n-$jaz-os", jaz, meja - 5 * 86400),
        DostopPravila.Clan("n-$jaz", jaz, meja - 4 * 86400),
        DostopPravila.Clan("n-$tablica", tablica, meja - 9 * 86400),
        DostopPravila.Clan("tv-stari", televizor, meja - 16 * 86400),
        DostopPravila.Clan("n-$televizor-os", televizor, meja + 3600),          // nov id iste naprave ne skrajsa njenega staza
        DostopPravila.Clan("n-$gost-os", gost, meja + 17 * 3600 + 180),            // telefon druge osebe, dodan po meji
        DostopPravila.Clan("n-$gost", gost, meja + 17 * 3600 + 181),
    )
    val jedroOd: (String) -> String = { "n-$it" }
    val podedovani = p.podedovani(krog, jaz, jedroOd)
    preveri("dosedanje naprave obdrzijo dostop", podedovani == setOf("n-$tablica", "n-$televizor"))
    preveri("naprava, dodana po meji, dostopa ne podeduje", "n-$gost" !in podedovani)
    preveri("ta naprava sebe ne vpisuje", "n-$jaz" !in podedovani)
    preveri("naprava, ki je sama dodana po meji, ne podeduje nikogar (tudi ona svojih vsebin ne odpre)",
        p.podedovani(krog, gost, jedroOd).isEmpty())
    preveri("brez svojega kljuca ali brez svojega vnosa v krogu ni podedovanih",
        p.podedovani(krog, null, jedroOd).isEmpty() && p.podedovani(krog, "eeee000000000005", jedroOd).isEmpty() && p.podedovani(emptyList(), jaz, jedroOd).isEmpty())
    preveri("vnos tocno na meji ni vec »od prej«",
        p.podedovani(listOf(DostopPravila.Clan("a", jaz, meja - 1), DostopPravila.Clan("b", tablica, meja)), jaz, jedroOd).isEmpty())
    preveri("meja je 6. 10. 2026 00:00:00 UTC", meja == 1_791_244_800.0)

    if (napak > 0) { println("\nNAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("DostopPravilaTest: OK")
}
