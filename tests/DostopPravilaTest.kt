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

    // Odloca kljuc: oznaka iz kljuca, pod katero je v krogu DRUG kljuc, ni naprava, za katero se izdaja.
    val jedroTv = idIzKljuca("KLJUC-TV")
    val krogPodobnih = mapOf(jedroTv to "KLJUC-TV", "$jedroTv-x" to "KLJUC-GOST-B1", "$jedroTv-os" to "KLJUC-TV", "pokvarjen" to "!")
    preveri("kljuca v preizkusu data razlicni jedri", idIzKljuca("KLJUC-GOST-B1") != jedroTv && p.jeJedro(jedroTv))
    val idAliNapaka: (String) -> String = { k -> if (k == "!") throw IllegalArgumentException("pokvarjen kljuc") else idIzKljuca(k) }
    preveri("oznaka druge naprave z drugim kljucem v krogu dobi prazno jedro",
        p.jedro("$jedroTv-x", { krogPodobnih[it] }, idAliNapaka) == "" && p.jedro("$jedroTv-os", { krogPodobnih[it] }, idAliNapaka) == jedroTv &&
            p.jedro(jedroTv, { krogPodobnih[it] }, idAliNapaka) == jedroTv)
    preveri("pokvarjen kljuc ne odpre nicesar", p.jedro("pokvarjen", { krogPodobnih[it] }, idAliNapaka) == "")
    preveri("jedro je natanko n- in 16 sestnajstiskih znakov",
        p.jeJedro("n-0123456789abcdef") && !p.jeJedro("n-0123456789abcdef-os") && !p.jeJedro("n-0123456789abcdeg") && !p.jeJedro("") && !p.jeJedro("tv-stari-id"))

    // Kljuc za preverbo podpisa v dogovoru zascite: po jedru, ne po vnosu oznake.
    val vsi = { krogPodobnih.values.toList() }
    preveri("kljuc za zascito je kljuc, ki da jedro oznake (tudi za sorodnika brez vnosa)",
        p.kljucZaZascito(jedroTv, { krogPodobnih[it] }, vsi, idAliNapaka) == "KLJUC-TV" &&
            p.kljucZaZascito("$jedroTv-novprogram", { krogPodobnih[it] }, vsi, idAliNapaka) == "KLJUC-TV")
    preveri("vnos z oznako naprave in tujim kljucem pravega kljuca ne zasenci in tujega ne podtakne",
        p.kljucZaZascito("$jedroTv-x", { krogPodobnih[it] }, vsi, idAliNapaka) == "KLJUC-TV")
    preveri("oznaka, katere jedra ni v krogu, nima kljuca (tudi ce je pod njo vpisan tuj kljuc)",
        p.kljucZaZascito("n-0000000000000000", { "KLJUC-GOST-B1" }, vsi, idAliNapaka) == null &&
            p.kljucZaZascito("$jedroTv-x", { "KLJUC-GOST-B1" }, { listOf("KLJUC-GOST-B1") }, idAliNapaka) == null)
    preveri("stara oznaka ima kljuc svojega vnosa; neznana nima kljuca",
        p.kljucZaZascito("tv-stari-id", { kljuci[it] }, vsi, idAliNapaka) == "KLJUC-TV" && p.kljucZaZascito("tujec", { null }, vsi, idAliNapaka) == null &&
            p.kljucZaZascito("tujec", { "" }, vsi, idAliNapaka) == null)

    // Zascita: odloca jedro iz preverjenega kljuca; od naprave, ki zascito zna, nezascitenega ukaza ne sprejmemo.
    val vse = p.VSE_ZMOZNOSTI
    var vprasanOZasciti = false
    preveri("zasciteno sporocilo: odloca jedro iz kljuca, ne oznaka",
        p.smePosiljatelj(Zahteva.DATOTEKE, "n-aaaaaaaaaaaaaaaa", true, { true }, { j -> if (j == "n-aaaaaaaaaaaaaaaa") vse else nic }, { nic }) &&
            !p.smePosiljatelj(Zahteva.DATOTEKE, "n-bbbbbbbbbbbbbbbb", true, { false }, { j -> if (j == "n-aaaaaaaaaaaaaaaa") vse else nic }, { vse }))
    preveri("nezasciten ukaz v imenu naprave, ki zascito zna, ne velja - ceprav ima oznaka dostop",
        !p.smePosiljatelj(Zahteva.DATOTEKE, "", true, { true }, { nic }, { vse }) &&
            !p.smePosiljatelj(Zahteva.VSE, "", true, { true }, { nic }, { vse }))
    preveri("naprava, ki zascite se ne zna, dela po starem (po oznaki)",
        p.smePosiljatelj(Zahteva.DATOTEKE, "", true, { false }, { nic }, { vse }) &&
            !p.smePosiljatelj(Zahteva.DATOTEKE, "", true, { false }, { nic }, { setOf(Zmoznost.PROGRAMI) }))
    preveri("usklajevanje in zaslon gresta se po starem tudi od naprave z zascito",
        p.smePosiljatelj(Zahteva.VSE, "", false, { vprasanOZasciti = true; true }, { nic }, { vse }) && !vprasanOZasciti)
    preveri("prosto ostane prosto", p.smePosiljatelj(Zahteva.PROSTO, "", true, { true }, { nic }, { nic }))
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

    // Dotik gledalca deljenega zaslona (posiljatelj »gledalec« ni naprava v Linku).
    val zZaslonom = setOf(Zmoznost.ZASLON)
    val znak = p.znakGledalca("abc12345", "0123456789abcdef")
    preveri("znak deljenja je SHA-256 id-ja in kljuca (znan primer)",
        znak == "178f27ae16b93fd0c6b025eaeef6c6c4b8f1f57fb6bb8ac3ee068a6e0b5dc3af")
    preveri("drug id ali drug kljuc da drug znak; kljuca v znaku ni",
        znak != p.znakGledalca("abc12346", "0123456789abcdef") && znak != p.znakGledalca("abc12345", "0123456789abcdee") &&
            !znak.contains("0123456789abcdef"))
    preveri("brez id-ja ali kljuca znaka ni",
        p.znakGledalca("", "k") == "" && p.znakGledalca("id", "") == "" && p.znakGledalca(" ", " ") == "")
    preveri("gledalec sme upravljati, dokler naprava deli zaslon z napravo, ki ima zaslon in upravljanje",
        listOf("input.tap", "input.swipe", "input.key", " Input.Tap ").all { p.smeGledalec(it, true, zZaslonom, znak, znak) } &&
            p.smeGledalec("input.tap", true, p.VSE_ZMOZNOSTI, znak, znak))
    preveri("brez deljenja zaslona gledalec ne sme nicesar", !p.smeGledalec("input.tap", false, p.VSE_ZMOZNOSTI, znak, znak))
    preveri("zaslon, deljen z napravo brez zaslona in upravljanja, se samo gleda",
        !p.smeGledalec("input.tap", true, emptySet(), znak, znak) &&
            !p.smeGledalec("input.tap", true, setOf(Zmoznost.DATOTEKE, Zmoznost.PROGRAMI, Zmoznost.PREDVAJALNIK), znak, znak))
    preveri("gledalec sme samo upravljati zaslon - datotek, programov in drugih ukazov ne",
        listOf("files.list", "apps.launch", "apps.list", "play.state", "screen.start", "key", "status", "open_url", "", "input",
            "input.enable", "input.text", "gamepad.button").none { p.smeGledalec(it, true, p.VSE_ZMOZNOSTI, znak, znak) })
    preveri("dotik brez znaka deljenja ali z napacnim znakom ne velja (naprava, ki se le prijavi kot »gledalec«)",
        !p.smeGledalec("input.tap", true, p.VSE_ZMOZNOSTI, znak, "") &&
            !p.smeGledalec("input.tap", true, p.VSE_ZMOZNOSTI, znak, p.znakGledalca("abc12345", "drug-kljuc")) &&
            !p.smeGledalec("input.tap", true, p.VSE_ZMOZNOSTI, znak, znak.uppercase()) &&
            !p.smeGledalec("input.tap", true, p.VSE_ZMOZNOSTI, znak, znak.dropLast(1)))
    preveri("dokler sredisce deljenja se ni odprlo (znaka ni), dotik ne velja - tudi prazen znak ne",
        !p.smeGledalec("input.tap", true, p.VSE_ZMOZNOSTI, "", "") && !p.smeGledalec("input.tap", true, p.VSE_ZMOZNOSTI, "", znak) &&
            !p.smeGledalec("input.tap", true, p.VSE_ZMOZNOSTI, " ", " "))
    preveri("oznaka gledalca ni oznaka naprave", p.GLEDALEC == "gledalec" && !p.jeIdIzKljuca(p.GLEDALEC))

    // ---- kljuc shrambe (neodvisni pregled 7. 10. 2026) ----
    // Vnos, katerega oznaka je kar GOLO jedro druge naprave, kljuc pa tuj: jedra nima, kljuc shrambe pa ne sme biti
    // enak jedru prave naprave (sicer bi dobil, kar je bilo izrecno poslano pravi, in bil med prejemniki njenih oddaj).
    val goloJedro = mapOf(jedroTv to "KLJUC-GOST-B1")
    val jedroVnosa = p.jedro(jedroTv, { goloJedro[it] }, idAliNapaka)
    val jedroPrograma = p.jedro("$jedroTv-os", { goloJedro[it] }, idAliNapaka)
    preveri("oznaka z golim jedrom druge naprave in tujim kljucem nima jedra", jedroVnosa == "" && jedroPrograma == jedroTv)
    preveri("kljuc shrambe take oznake ni jedro prave naprave",
        p.kljucShrambe(jedroTv, jedroVnosa) == p.BREZ_JEDRA + jedroTv && p.kljucShrambe(jedroTv, jedroVnosa) != jedroTv &&
            p.kljucShrambe("$jedroTv-os", jedroPrograma) == jedroTv)
    preveri("naprava z jedrom in naprava, ki je krog ne pozna, imata kljuc shrambe kot doslej",
        p.kljucShrambe("tv-stari-id", idIzKljuca("KLJUC-TV")) == idIzKljuca("KLJUC-TV") && p.kljucShrambe("tujec", "tujec") == "tujec")
    preveri("kljuc shrambe oznake brez jedra ni oblike oznake iz kljuca", !p.jeIdIzKljuca(p.kljucShrambe(jedroTv, "")))

    // ---- drugi neodvisni pregled (7. 10. 2026) ----
    val j = "n-0123456789abcdef"
    preveri("oznaka iz kljuca ima samo pripono iz varne abecede (crke, stevke, pika, podcrtaj, vezaj)",
        listOf(j, "$j-os", "$j-control", "$j-a.b_c-1", "$j-").all { p.jeIdIzKljuca(it) } &&
            listOf("$j-x\ny", "$j-x y", "$j-č", "$j-" + "a".repeat(200), "$j\n", "${j}x", "n-0123456789ABCDEF", "n-0123456789abcde",
                "x-0123456789abcdef", "").none { p.jeIdIzKljuca(it) })
    preveri("veljavna oznaka naprave: 1-128 vidnih znakov ASCII",
        listOf(j, "$j-os", "stara-naprava_1.2:3", "a".repeat(128)).all { p.veljavnaOznaka(it) } &&
            listOf("", "a b", "a\nb", "a\rb", "a\tb", "a\u0000b", "a\u007fb", "a\u0085b", "a b", "č", "\ud800", "a".repeat(129)).none { p.veljavnaOznaka(it) })
    preveri("kljuc za zascito: oznaka s prelomom vrstice, presledkom ali predolga kljuca ne dobi",
        p.kljucZaZascito("$jedroTv-os", { null }, vsi, idAliNapaka) == "KLJUC-TV" &&
            listOf("$jedroTv-tv\n$j", "$jedroTv-x y", "$jedroTv\n", "stara naprava", "a\nb", "a".repeat(200))
                .none { p.kljucZaZascito(it, { "KLJUC-TV" }, vsi, idAliNapaka) != null })
    // N2: pod oznako programa naprave je v krogu podtaknjen DRUG kljuc (jedro po krogu je prazno). Zahteve po zasciti to
    // ne sme ugasniti: odloca jedro iz OBLIKE oznake.
    val dokazani = setOf(jedroTv)
    val jedroPoKrogu: (String) -> String = { id -> p.jedro(id, { krogPodobnih[it] }, idAliNapaka) }
    preveri("podtaknjen vnos (oznaka naprave, drug kljuc) ne ugasne zahteve po zasciti",
        jedroPoKrogu("$jedroTv-x") == "" && p.zahtevaZascito("$jedroTv-x", { it in dokazani }, jedroPoKrogu) &&
            p.zahtevaZascito("$jedroTv-os", { it in dokazani }, jedroPoKrogu) && p.zahtevaZascito(jedroTv, { it in dokazani }, jedroPoKrogu))
    preveri("brez dokazanega kljuca zascite ne zahtevamo; stara oznaka po kljucu iz kroga",
        !p.zahtevaZascito("$jedroTv-x", { false }, jedroPoKrogu) && !p.zahtevaZascito("", { true }, jedroPoKrogu) &&
            !p.zahtevaZascito("stara-naprava", { it in dokazani }, { it }) && p.zahtevaZascito("tv-stari-id", { it in dokazani }, { jedroTv }))
    // N3: seznam naprav pise sredisce - koliko dogovorov za dokaz kljuca sme sproziti.
    val veliko = List(500) { "$jedroTv-x$it" }
    val prvi = p.izberiZaDokaz(veliko, emptyMap(), 1_000_000L, 60_000L)
    preveri("seznam z veliko oznakami iste naprave sprozi najvec ${p.NAJVEC_DOKAZOV_NA_JEDRO} dogovore",
        prvi == veliko.take(p.NAJVEC_DOKAZOV_NA_JEDRO))
    val zapis = prvi.associateWith { 1_000_000L }
    preveri("isti seznam takoj znova ne sprozi nicesar; po minuti spet najvec toliko",
        p.izberiZaDokaz(veliko, zapis, 1_010_000L, 60_000L).isEmpty() &&
            p.izberiZaDokaz(veliko, zapis, 1_060_000L, 60_000L).size == p.NAJVEC_DOKAZOV_NA_JEDRO)
    val razlicne = List(100) { "n-%016x-os".format(it) }
    preveri("dolg seznam razlicnih naprav sprozi najvec ${p.NAJVEC_DOKAZOV_NA_SEZNAM} dogovorov, po vrsti iz seznama",
        p.izberiZaDokaz(razlicne, emptyMap(), 1_000_000L, 60_000L) == razlicne.take(p.NAJVEC_DOKAZOV_NA_SEZNAM))
    val poln = (0 until p.NAJVEC_ZAPISOV_DOKAZOV).associate { "stara-$it" to 0L }
    preveri("ko je zapis o poskusih poln, novih dogovorov ni (ze zapisana naprava sme znova)",
        p.izberiZaDokaz(listOf("$j-os", "stara-7"), poln, 1_000_000L, 60_000L) == listOf("stara-7"))
    preveri("jedro oznake za stetje: jedro oznake iz kljuca, sicer oznaka sama",
        p.jedroOznake("$j-os") == j && p.jedroOznake("stara-naprava") == "stara-naprava" && p.jedroOznake("$j-x\ny") == "$j-x\ny")
    // Cetrti pregled: oznaka sporocila (id, ref_id) je kratka - odgovor jo ponovi.
    preveri("oznaka sporocila: niz do ${p.NAJVEC_OZNAKE_SPOROCILA} znakov, obicajno stevilo ali nic",
        !p.predolgaOznaka("a".repeat(128), null, 5, 7L, 2.5) && p.predolgaOznaka("kratka", "a".repeat(129)) && !p.predolgaOznaka() &&
            p.predolgaOznaka(listOf("x")) && p.predolgaOznaka(mapOf("a" to 1)) && p.predolgaOznaka(true) &&
            p.predolgaOznaka(Double.POSITIVE_INFINITY) && p.predolgaOznaka(Double.NaN) && p.predolgaOznaka(1e30) &&
            p.NAJVEC_OZNAKE_SPOROCILA == 128 && p.NAJVEC_PONOVLJENEGA_DEJANJA == 64)

    if (napak > 0) { println("\nNAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("DostopPravilaTest: OK")
}
