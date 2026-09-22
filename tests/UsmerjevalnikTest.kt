package si.safeer.tv.cast

/**
 * Preizkus bralca JSON in usmerjevalnika Safeer Huba brez naprave in brez omrezja.
 *
 * Kar preverjamo, je tisto, kar bi se v zivo pokazalo sele kot "ne dela" ali, huje, kot
 * tiha varnostna luknja: da se sporocilo posreduje samo tistemu, ki mu je namenjeno; da
 * nihce ne pride skozi brez potrditve na televizorju; da se vstopnica porabi enkrat; da
 * seznami ne rastejo v nedogled; in da bralec JSON zavrne tisto, kar ni pravilen JSON.
 */

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) {
        println("  OK   $opis")
    } else {
        println("  NAPAKA $opis")
        napak++
    }
}

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) {
    preveri("$opis (pričakovano=$pricakovano, dobljeno=$dobljeno)", pricakovano == dobljeno)
}

// ------------------------------------------------------------ pomozni odjemalec

private class Lazni(override val naslov: String = "192.168.0.50", override val vstopnica: String? = null) : HubUsmerjevalnik.Odjemalec {
    val prejeto = ArrayList<String>()
    var zaprt = false
    var zapriKodo = 0
    override fun poslji(besedilo: String) {
        prejeto.add(besedilo)
    }
    override fun zapri(koda: Int, razlog: String) {
        zaprt = true
        zapriKodo = koda
    }
    fun zadnje(): String = prejeto.lastOrNull() ?: ""
    fun pocisti() = prejeto.clear()
}

private class LazniPomnilnik : HubUsmerjevalnik.Shramba {
    val vsebina = HashMap<String, String>()
    override fun beri(kljuc: String): String? = vsebina[kljuc]
    override fun pisi(kljuc: String, vrednost: String) {
        vsebina[kljuc] = vrednost
    }
}

private var cas = 1_700_000_000_000L
private var stevec = 0

private fun usmerjevalnik(shramba: HubUsmerjevalnik.Shramba? = null): HubUsmerjevalnik =
    HubUsmerjevalnik(shramba, { cas }, { n -> "t%d-%d".format(++stevec, n) })

private fun tip(sporocilo: String): String = JsonLahki.objekt(sporocilo)?.nizAli("type") ?: "?"
private fun polje(sporocilo: String, kljuc: String): String =
    JsonLahki.objekt(sporocilo)?.nizAli(kljuc) ?: ""

private fun registracija(deviceId: String, vloga: String, zmoznosti: String = "[\"url\",\"control\"]"): String =
    """{"id":"r-$deviceId","type":"cast.register","payload":{"device_id":"$deviceId","name":"Naprava $deviceId","role":"$vloga","capabilities":$zmoznosti}}"""

private fun zahteva(
    metoda: String,
    pot: String,
    telo: String = "",
    odjemalec: String = "192.168.0.50",
    glave: Map<String, String> = emptyMap(),
    poizvedba: Map<String, String> = emptyMap()
) = HubStreznik.Zahteva(metoda, pot, poizvedba, glave, telo, odjemalec)

// ------------------------------------------------------------ JSON

private fun preizkusJson() {
    println("\n== bralec JSON ==")

    val ovojnica = JsonLahki.objekt(
        """{"id":"a1","type":"cast.url","timestamp":1.5,"target":"tv","payload":{"url":"https://safeer.si/"}}"""
    )
    preveri("objekt se prebere", ovojnica != null)
    preveriEnako("tip", "cast.url", ovojnica?.niz("type"))
    preveriEnako("cilj", "tv", ovojnica?.niz("target"))
    preveriEnako("stevilo", 1.5, ovojnica?.stevilo("timestamp"))
    preveriEnako("tovor ostane nedotaknjen", """{"url":"https://safeer.si/"}""", ovojnica?.surovo("payload"))
    preveriEnako("vgnezdeno se prebere sele ob potrebi", "https://safeer.si/",
        ovojnica?.objekt("payload")?.niz("url"))
    preveriEnako("niz na mestu stevila je null", null, ovojnica?.stevilo("type"))

    // Kljuc znotraj niza ne sme zmesti bralca.
    val past = JsonLahki.objekt("""{"opomba":"\"type\":\"cast.control\"","type":"cast.ping"}""")
    preveriEnako("kljuc v nizu ne zmede bralca", "cast.ping", past?.niz("type"))

    preveriEnako("ubegi", "vrstica\nnova \"navednica\" č",
        JsonLahki.objekt("""{"a":"vrstica\nnova \"navednica\" č"}""")?.niz("a"))

    preveriEnako("zmoznosti", listOf("url", "control", "sync"),
        JsonLahki.objekt("""{"c":["url","control","sync"]}""")?.nizi("c"))

    preveri("smeti za objektom se zavrnejo", JsonLahki.objekt("""{"a":1} nekaj""") == null)
    preveri("nezakljucen niz se zavrne", JsonLahki.objekt("""{"a":"b}""") == null)
    preveri("nezakljucen objekt se zavrne", JsonLahki.objekt("""{"a":1""") == null)
    preveri("manjkajoca vejica se zavrne", JsonLahki.objekt("""{"a":1 "b":2}""") == null)
    preveri("nadzorni znak v nizu se zavrne", JsonLahki.objekt("{\"a\":\"b\nc\"}") == null)
    preveri("pokvarjeno stevilo se zavrne", JsonLahki.objekt("""{"a":01.}""") == null)
    preveri("pokvarjen vgnezden objekt se zavrne", JsonLahki.objekt("""{"a":{"b":}}""") == null)
    preveri("seznam ni objekt", JsonLahki.objekt("""[1,2,3]""") == null)
    preveri("prazen objekt je v redu", JsonLahki.objekt("{}") != null)

    val globoko = StringBuilder()
    for (i in 0..20) globoko.append("""{"a":""")
    globoko.append("1")
    for (i in 0..20) globoko.append("}")
    preveri("pregloboko vgnezdenje se zavrne", JsonLahki.objekt(globoko.toString()) == null)

    val zapis = JsonLahki.Zapis()
        .niz("a", "z \"navednico\"")
        .stevilo("b", 3.0)
        .stevilo("c", 1.25)
        .logicno("d", true)
        .nic("e")
        .seznamNizov("f", listOf("x", "y"))
    preveriEnako("zapis", """{"a":"z \"navednico\"","b":3,"c":1.25,"d":true,"e":null,"f":["x","y"]}""",
        zapis.toString())
    preveri("kar zapisemo, znamo tudi prebrati", JsonLahki.objekt(zapis.toString())?.niz("a") == "z \"navednico\"")
}

// ------------------------------------------------------------ register in cast

private fun preizkusRegistra() {
    println("\n== register naprav in cast ==")
    val u = usmerjevalnik()

    val tv = Lazni("192.168.0.20")
    val telefon = Lazni("192.168.0.30")

    preveriEnako("prejemnik se registrira", "cast.ack", tip(u.odgovorNa(tv, registracija("tv1", "receiver"))!!))
    preveriEnako("potrditev je sprejeta", "accepted", polje(u.odgovorNa(tv, registracija("tv1", "receiver"))!!, "status"))

    val odgovorPosiljatelja = u.odgovorNa(telefon, registracija("fon1", "sender"))
    preveriEnako("posiljatelj se registrira", "accepted", polje(odgovorPosiljatelja!!, "status"))
    preveriEnako("posiljatelj takoj dobi seznam naprav", "cast.devices", tip(telefon.zadnje()))
    preveri("v seznamu je prejemnik", telefon.zadnje().contains("\"id\":\"tv1\""))
    // Deljenje je dvosmerno: v seznamu so vse povezane naprave z vlogo zraven; kdo je zaslon,
    // odloci vmesnik po polju role.
    preveri("v seznamu je tudi posiljatelj, z vlogo", telefon.zadnje().contains("\"id\":\"fon1\"") &&
        telefon.zadnje().contains("\"role\":\"sender\""))
    preveriEnako("tudi zaslon dobi nov seznam", "cast.devices", tip(tv.zadnje()))

    // ---- deljenje: besedilo gre izbrani napravi, posiljatelja vpise Hub ----
    val deljenje = u.odgovorNa(telefon, """{"id":"d1","type":"share.text","target":"tv1","payload":{"text":"Zdravo"}}""")
    preveriEnako("deljenje sprejeto", "accepted", polje(deljenje!!, "status"))
    preveriEnako("potrditev je v prostoru share", "share.ack", tip(deljenje))
    preveriEnako("zaslon dobi share.text", "share.text", tip(tv.zadnje()))
    preveri("prejemnik vidi, kdo poslje", tv.zadnje().contains("\"sender\":\"fon1\""))
    preveri("prejemnik vidi besedilo", tv.zadnje().contains("Zdravo"))
    preveriEnako("deljenje neznani napravi je zavrnjeno", "rejected",
        polje(u.odgovorNa(telefon, """{"id":"d2","type":"share.text","target":"nihce","payload":{"text":"x"}}""")!!, "status"))
    preveriEnako("deljenje samemu sebi je zavrnjeno", "rejected",
        polje(u.odgovorNa(telefon, """{"id":"d3","type":"share.text","target":"fon1","payload":{"text":"x"}}""")!!, "status"))

    preveriEnako("brez device_id zavrnjeno", "rejected",
        polje(u.odgovorNa(telefon, """{"id":"x","type":"cast.register","payload":{}}""")!!, "status"))

    // Posredovanje
    tv.pocisti()
    val ukaz = """{"id":"u1","type":"cast.url","target":"tv1","payload":{"url":"https://safeer.si/"}}"""
    val potrditev = u.odgovorNa(telefon, ukaz)!!
    preveriEnako("ukaz je sprejet", "accepted", polje(potrditev, "status"))
    preveriEnako("potrditev se sklicuje na sporocilo", "u1", polje(potrditev, "ref_id"))
    preveriEnako("prejemnik dobi ukaz nespremenjen", ukaz, tv.zadnje())

    preveriEnako("neznan cilj je zavrnjen", "rejected",
        polje(u.odgovorNa(telefon, """{"id":"u2","type":"cast.url","target":"ni-me"}""")!!, "status"))

    preveriEnako("ping vrne pong", "cast.pong", tip(u.odgovorNa(telefon, """{"id":"p1","type":"cast.ping"}""")!!))
    preveriEnako("pong ohrani id", "p1", polje(u.odgovorNa(telefon, """{"id":"p1","type":"cast.ping"}""")!!, "id"))

    val neznano = u.odgovorNa(telefon, """{"id":"n1","type":"racun.izprazni"}""")!!
    preveriEnako("neznan tip ni posredovan", "error", polje(neznano, "status"))
    preveri("neznan tip dobi razlago", polje(neznano, "error").contains("Neznan tip"))

    preveriEnako("pokvarjeno sporocilo ne podre nicesar", "error",
        polje(u.odgovorNa(telefon, "{to ni json")!!, "status"))

    preveriEnako("sporocilo brez tipa", "error",
        polje(u.odgovorNa(telefon, """{"id":"b1"}""")!!, "status"))

    // cast.status se razposlje posiljateljem, odgovora ni
    telefon.pocisti()
    preveri("cast.status nima odgovora",
        u.odgovorNa(tv, """{"id":"s1","type":"cast.status","device_id":"tv1","payload":{"state":"playing"}}""") == null)
    preveriEnako("posiljatelj izve za stanje", "cast.status", tip(telefon.zadnje()))

    // Odklop prejemnika osvezi seznam pri posiljateljih
    telefon.pocisti()
    u.odklopi(tv)
    preveriEnako("po odklopu se seznam osvezi", "cast.devices", tip(telefon.zadnje()))
    preveri("odklopljenega prejemnika ni vec v seznamu", !telefon.zadnje().contains("\"id\":\"tv1\""))
    preveriEnako("ukaz odklopljeni napravi je zavrnjen", "rejected",
        polje(u.odgovorNa(telefon, """{"id":"u3","type":"cast.url","target":"tv1"}""")!!, "status"))
}

// ------------------------------------------------------------ sinhronizacija

private fun preizkusDaljinca() {
    println("\n== daljinec (control.command / control.result) ==")
    val u = usmerjevalnik()
    val tv = Lazni("192.168.0.20")
    val star = Lazni("192.168.0.21")
    val control = Lazni("192.168.0.40")

    u.odgovorNa(tv, registracija("tv1", "receiver", "[\"url\",\"control\",\"remote\"]"))
    u.odgovorNa(star, registracija("tv-star", "receiver"))
    u.odgovorNa(control, registracija("pc1", "sender", "[\"url\",\"text\"]"))
    tv.pocisti(); control.pocisti()

    val ukaz = u.odgovorNa(control, """{"id":"u1","type":"control.command","target":"tv1","payload":{"action":"key","params":{"key":"down"}}}""")
    preveriEnako("ukaz je sprejet (posredovan)", "accepted", polje(ukaz!!, "status"))
    preveriEnako("potrditev je v prostoru control", "control.ack", tip(ukaz))
    preveriEnako("naprava dobi control.command", "control.command", tip(tv.zadnje()))
    preveri("naprava vidi, kdo ukazuje", tv.zadnje().contains("\"sender\":\"pc1\""))
    preveri("id ukaza ostane isti (ref za odgovor)", tv.zadnje().contains("\"id\":\"u1\""))
    preveri("tovor pride cel", tv.zadnje().contains("\"key\":\"down\""))

    // Odgovor gre nazaj posiljatelju ukaza, brez potrditve Huba.
    val odgovor = u.odgovorNa(tv, """{"id":"o1","type":"control.result","target":"pc1","ref_id":"u1","payload":{"ok":true,"message":"Tipka down","action":"key"}}""")
    preveriEnako("Hub odgovora ne potrjuje", null, odgovor)
    preveriEnako("posiljatelj dobi control.result", "control.result", tip(control.zadnje()))
    preveriEnako("ref_id se ohrani", "u1", polje(control.zadnje(), "ref_id"))
    preveri("posiljatelja odgovora vpise Hub", control.zadnje().contains("\"sender\":\"tv1\""))

    preveriEnako("naprava brez zmoznosti remote je zavrnjena", "rejected",
        polje(u.odgovorNa(control, """{"id":"u2","type":"control.command","target":"tv-star","payload":{"action":"key"}}""")!!, "status"))
    preveriEnako("... z oznako brez_daljinca", "brez_daljinca",
        polje(u.odgovorNa(control, """{"id":"u2","type":"control.command","target":"tv-star","payload":{"action":"key"}}""")!!, "error_code"))
    preveriEnako("ukaz neznani napravi je zavrnjen", "rejected",
        polje(u.odgovorNa(control, """{"id":"u3","type":"control.command","target":"nihce","payload":{"action":"key"}}""")!!, "status"))
    preveriEnako("ukaz samemu sebi je zavrnjen", "rejected",
        polje(u.odgovorNa(control, """{"id":"u4","type":"control.command","target":"pc1","payload":{"action":"key"}}""")!!, "status"))
    // Odgovor nepovezani napravi se ne izgubi tiho: posiljatelj dobi zavrnitev.
    preveriEnako("odgovor neznani napravi je zavrnjen", "rejected",
        polje(u.odgovorNa(tv, """{"id":"o2","type":"control.result","target":"nihce","ref_id":"u1","payload":{"ok":true}}""")!!, "status"))
}

private fun preizkusSinhronizacije() {
    println("\n== sinhronizacija ==")
    val u = usmerjevalnik()
    val a = Lazni("192.168.0.31")
    val b = Lazni("192.168.0.32")
    u.odgovorNa(a, registracija("fonA", "sync-client", """["sync"]"""))
    u.odgovorNa(b, registracija("fonB", "sync-client", """["sync"]"""))

    b.pocisti()
    val podatki = """{"id":"d1","type":"sync.data","payload":{"category":"bookmarks","version":3,"timestamp":100,"data":[{"u":"https://safeer.si/"}]}}"""
    preveriEnako("sync.data sprejet", "accepted", polje(u.odgovorNa(a, podatki)!!, "status"))
    preveriEnako("potrditev je v prostoru sync", "sync.ack", tip(u.odgovorNa(a, podatki)!!))
    preveriEnako("druga naprava dobi podatke", podatki, b.zadnje())
    preveri("posiljatelj sam sebi ne posilja", a.prejeto.none { it == podatki })
    preveriEnako("kategorija je shranjena", listOf("bookmarks"), u.kategorijeSinhronizacije())

    // Naprava, ki je bila ugasnjena, dohiti
    val c = Lazni("192.168.0.33")
    u.odgovorNa(c, registracija("fonC", "sync-client", """["sync"]"""))
    c.pocisti()
    val zahtevek = """{"id":"z1","type":"sync.request","payload":{"category":"bookmarks"}}"""
    preveriEnako("zahtevek sprejet", "accepted", polje(u.odgovorNa(c, zahtevek)!!, "status"))
    preveriEnako("dobi shranjeno stanje", "sync.data", tip(c.zadnje()))
    preveri("stanje vsebuje podatke", c.zadnje().contains("https://safeer.si/"))
    preveriEnako("stanje je naslovljeno nanj", "fonC", polje(c.zadnje(), "target"))

    c.pocisti()
    preveriEnako("ce ze ima novejso razlicico, ne posiljamo", "accepted",
        polje(u.odgovorNa(c, """{"id":"z2","type":"sync.request","payload":{"category":"bookmarks","since_version":9}}""")!!, "status"))
    preveri("in res ne dobi nicesar", c.prejeto.isEmpty())

    // Naslovljeno sporocilo gre samo naslovniku
    b.pocisti()
    c.pocisti()
    val naslovljeno = """{"id":"n2","type":"sync.status","target":"fonB","payload":{"state":"ok"}}"""
    preveriEnako("naslovljeno sprejeto", "accepted", polje(u.odgovorNa(a, naslovljeno)!!, "status"))
    preveriEnako("dobi ga samo naslovnik", naslovljeno, b.zadnje())
    preveri("drugi ga ne dobijo", c.prejeto.isEmpty())

    preveriEnako("neznan naslovnik zavrnjen", "rejected",
        polje(u.odgovorNa(a, """{"id":"n3","type":"sync.status","target":"ni-ga"}""")!!, "status"))

    // sync.ack gre naprej brez odgovora
    a.pocisti()
    val potrdilo = """{"id":"a1","type":"sync.ack","target":"fonA","ref_id":"d1","status":"accepted"}"""
    preveri("sync.ack nima odgovora", u.odgovorNa(b, potrdilo) == null)
    preveriEnako("sync.ack pride do naslovnika", potrdilo, a.zadnje())

    // Prevelika kategorija se ne shrani
    val velika = "x".repeat(HubUsmerjevalnik.NAJVECJA_KATEGORIJA + 10)
    u.odgovorNa(a, """{"id":"v1","type":"sync.data","payload":{"category":"history","version":1,"timestamp":200,"data":"$velika"}}""")
    preveri("prevelika kategorija se ne shrani", !u.kategorijeSinhronizacije().contains("history"))

    // Starejsi zapis ne povozi novejsega
    u.odgovorNa(a, """{"id":"s1","type":"sync.data","payload":{"category":"bookmarks","version":1,"timestamp":50,"data":["staro"]}}""")
    val d = Lazni("192.168.0.34")
    u.odgovorNa(d, registracija("fonD", "sync-client", """["sync"]"""))
    d.pocisti()
    u.odgovorNa(d, """{"id":"z3","type":"sync.request","payload":{"category":"bookmarks"}}""")
    preveri("starejsi zapis ne povozi novejsega", d.zadnje().contains("https://safeer.si/"))

    // Stevilo kategorij je omejeno
    for (i in 0 until HubUsmerjevalnik.NAJVEC_KATEGORIJ + 5) {
        u.odgovorNa(a, """{"id":"k$i","type":"sync.data","payload":{"category":"kat$i","version":1,"timestamp":${300 + i},"data":["$i"]}}""")
    }
    preveri("kategorij ni vec kot dovoljeno",
        u.kategorijeSinhronizacije().size <= HubUsmerjevalnik.NAJVEC_KATEGORIJ)

    // Brez druge naprave, ki sinhronizira
    val u2 = usmerjevalnik()
    val sam = Lazni("192.168.0.35")
    u2.odgovorNa(sam, registracija("sam", "sync-client", """["sync"]"""))
    preveriEnako("sam s sabo ne sinhronizira", "rejected",
        polje(u2.odgovorNa(sam, """{"id":"x1","type":"sync.status","payload":{"a":1}}""")!!, "status"))
}

// ------------------------------------------------------------ seznanjanje

private fun preizkusSeznanjanja() {
    println("\n== seznanjanje ==")
    val pomnilnik = LazniPomnilnik()
    val u = usmerjevalnik(pomnilnik)

    val (pairId, pin) = u.zacniSeznanitev("fon1", "Matejev telefon", "192.168.0.30")!!
    preveri("koda je sestmestna", pin.length == 6 && pin.all { it.isDigit() })
    preveriEnako("prijava caka", 1, u.cakajocePrijave().size)
    preveriEnako("vmesnik vidi ime naprave", "Matejev telefon", u.cakajocePrijave().first().ime)
    preveriEnako("vmesnik vidi isto kodo", pin, u.cakajocePrijave().first().pin)

    preveri("pred potrditvijo ni zetona", u.prevzemiZeton(pairId) == null)

    preveri("potrditev na televizorju uspe", u.potrdiPrijavo(pairId))
    val zeton = u.prevzemiZeton(pairId)
    preveri("naprava prevzame zeton", !zeton.isNullOrBlank())
    preveri("zeton je veljaven", u.jeVeljavenZeton(zeton))
    preveri("drugic prevzem ne uspe", u.prevzemiZeton(pairId) == null)
    preveriEnako("prijava ni vec na seznamu", 0, u.cakajocePrijave().size)
    preveriEnako("naprava je na seznamu seznanjenih", 1, u.seznanjeneNaprave().size)
    preveri("zetoni so shranjeni", pomnilnik.vsebina.isNotEmpty())
    preveri("zetona ne pokazemo v seznamu", u.seznanjeneNaprave().none { it.ime.contains("saf_tv_") })

    // Zetoni prezivijo ponovni zagon
    val u2 = HubUsmerjevalnik(pomnilnik, { cas })
    preveri("po ponovnem zagonu zeton se velja", u2.jeVeljavenZeton(zeton))
    preveri("neveljaven zeton ne velja", !u2.jeVeljavenZeton("saf_tv_neki"))
    preveri("prazen zeton ne velja", !u2.jeVeljavenZeton(""))

    // Odvzem dostopa odklopi napravo
    val naprava = Lazni("192.168.0.30")
    u2.odgovorNa(naprava, registracija("fon1", "sender"))
    preveriEnako("dostop odvzet", 1, u2.prekliciNapravo("fon1"))
    preveri("naprava je odklopljena", naprava.zaprt)
    preveri("zeton po odvzemu ne velja vec", !u2.jeVeljavenZeton(zeton))

    // Ista naprava ne kopici prijav
    val u3 = usmerjevalnik()
    u3.zacniSeznanitev("fon2", "A", "192.168.0.31")
    u3.zacniSeznanitev("fon2", "A", "192.168.0.31")
    preveriEnako("ista naprava ne kopici prijav", 1, u3.cakajocePrijave().size)

    // Vec kot toliko cakajocih ne sprejmemo
    for (i in 0 until HubUsmerjevalnik.NAJVEC_CAKAJOCIH + 3) u3.zacniSeznanitev("n$i", "N$i", "192.168.0.4$i")
    preveriEnako("cakajocih ni vec kot dovoljeno", HubUsmerjevalnik.NAJVEC_CAKAJOCIH, u3.cakajocePrijave().size)
    preveri("nova prijava cez mejo je zavrnjena", u3.zacniSeznanitev("cez", "C", "192.168.0.99") == null)

    // Koda potece
    val u4 = usmerjevalnik()
    val (star, _) = u4.zacniSeznanitev("fon3", "B", "192.168.0.32")!!
    cas += HubUsmerjevalnik.PIN_VELJA_MS + 1000
    preveriEnako("potekla prijava izgine", 0, u4.cakajocePrijave().size)
    preveri("potekle prijave ni mogoce potrditi", !u4.potrdiPrijavo(star))
    cas -= HubUsmerjevalnik.PIN_VELJA_MS + 1000

    // Zavrnitev
    val u5 = usmerjevalnik()
    val (zaZavreci, _) = u5.zacniSeznanitev("fon4", "C", "192.168.0.33")!!
    preveri("zavrnitev uspe", u5.zavrniPrijavo(zaZavreci))
    preveriEnako("po zavrnitvi ni prijave", 0, u5.cakajocePrijave().size)
    preveri("zavrnjene ni mogoce prevzeti", u5.prevzemiZeton(zaZavreci) == null)
}

// ------------------------------------------------------------ preklic prijave (Safeer OS 0.3.1)

private fun preizkusPreklica() {
    println("\n== preklic prijave ==")
    val u = usmerjevalnik()
    val (pairId, _) = u.zacniSeznanitev("tab1", "Tablica", "192.168.0.87")!!
    preveri("tuja naprava ne more preklicati", !u.prekliciPrijavo(pairId, "tuja"))
    preveriEnako("prijava po tujem preklicu ostane", 1, u.cakajocePrijave().size)
    preveri("izmisljen pair_id ne preklice nicesar", !u.prekliciPrijavo("izmisljen", "tab1"))
    preveri("naprava, ki je prijavo zacela, jo preklice", u.prekliciPrijavo(pairId, "tab1"))
    preveriEnako("po preklicu ni prijave (koda izgine z zaslona)", 0, u.cakajocePrijave().size)
    preveri("drugic preklic ne uspe", !u.prekliciPrijavo(pairId, "tab1"))

    // Ze potrjena prijava: preklic je ne sme vec vzeti (zeton je ze izdan).
    val (potrjena, _) = u.zacniSeznanitev("tab2", "Tablica 2", "192.168.0.88")!!
    preveri("potrditev uspe", u.potrdiPrijavo(potrjena))
    preveri("potrjene ni mogoce preklicati", !u.prekliciPrijavo(potrjena, "tab2"))
    preveri("potrjena se vedno prevzame zeton", u.prevzemiZeton(potrjena) != null)

    // Po HTTP: samo v krajevnem omrezju, z obema poljema.
    val (p3, _) = u.zacniSeznanitev("tab3", "Tablica 3", "192.168.0.89")!!
    preveriEnako("z interneta preklic ni mogoc", 403,
        u.odgovori(zahteva("POST", "/cast/pair/cancel", """{"pair_id":"$p3","device_id":"tab3"}""", "203.0.113.5"))?.koda)
    preveriEnako("brez device_id je napaka", 400,
        u.odgovori(zahteva("POST", "/cast/pair/cancel", """{"pair_id":"$p3"}"""))?.koda)
    val tuj = u.odgovori(zahteva("POST", "/cast/pair/cancel", """{"pair_id":"$p3","device_id":"tuja"}"""))
    preveriEnako("tuj preklic po HTTP: 200, a nic preklicano", "false", tuj?.telo?.let { JsonLahki.objekt(it)?.let { o -> o.logicno("cancelled")?.toString() } })
    preveri("prijava po tujem HTTP preklicu ostane", u.cakajocePrijave().any { it.pairId == p3 })
    val pravi = u.odgovori(zahteva("POST", "/cast/pair/cancel", """{"pair_id":"$p3","device_id":"tab3"}"""))
    preveriEnako("pravi preklic po HTTP", "true", pravi?.telo?.let { JsonLahki.objekt(it)?.let { o -> o.logicno("cancelled")?.toString() } })
    preveri("po pravem preklicu prijave ni", u.cakajocePrijave().none { it.pairId == p3 })
}

// ------------------------------------------------------------ vstopnice

private fun preizkusVstopnic() {
    println("\n== vstopnice ==")
    val u = usmerjevalnik()
    val vstopnica = u.izdajVstopnico()
    preveri("vstopnica velja enkrat", u.porabiVstopnico(vstopnica))
    preveri("drugic ne velja", !u.porabiVstopnico(vstopnica))
    preveri("izmisljena ne velja", !u.porabiVstopnico("kar-tako"))
    preveri("prazna ne velja", !u.porabiVstopnico(""))

    val potekla = u.izdajVstopnico()
    cas += HubUsmerjevalnik.VSTOPNICA_VELJA_MS + 1000
    preveri("potekla ne velja", !u.porabiVstopnico(potekla))
    cas -= HubUsmerjevalnik.VSTOPNICA_VELJA_MS + 1000

    val prva = u.izdajVstopnico()
    for (i in 0 until HubUsmerjevalnik.NAJVEC_VSTOPNIC + 2) u.izdajVstopnico()
    preveri("najstarejsa pade ven, ko jih je prevec", !u.porabiVstopnico(prva))

    // Nadgradnja v WebSocket
    val nova = u.izdajVstopnico()
    preveriEnako("brez vstopnice ni nadgradnje", "neveljavna ali potekla vstopnica",
        u.preveriVstopnico(zahteva("GET", "/cast/ws")))
    preveriEnako("z interneta ni nadgradnje", "samo v krajevnem omrežju",
        u.preveriVstopnico(zahteva("GET", "/cast/ws", odjemalec = "8.8.8.8", poizvedba = mapOf("ticket" to nova))))
    preveriEnako("napacna pot", "neznana pot", u.preveriVstopnico(zahteva("GET", "/link/ws")))
    preveri("z vstopnico iz omrezja gre",
        u.preveriVstopnico(zahteva("GET", "/cast/ws", poizvedba = mapOf("ticket" to nova))) == null)
}

// ------------------------------------------------------------ HTTP

private fun preizkusHttp() {
    println("\n== koncne tocke ==")
    val u = usmerjevalnik()

    val zunaj = u.odgovori(zahteva("POST", "/cast/pair/start", """{"device_id":"x","name":"X"}""", "203.0.113.5"))
    preveriEnako("z interneta se ni mogoce prijaviti", 403, zunaj?.koda)

    preveriEnako("brez device_id je napaka", 400,
        u.odgovori(zahteva("POST", "/cast/pair/start", """{"name":"X"}"""))?.koda)

    val prijava = u.odgovori(zahteva("POST", "/cast/pair/start", """{"device_id":"fon1","name":"Telefon"}"""))
    preveriEnako("prijava uspe", 200, prijava?.koda)
    val pairId = polje(prijava!!.telo, "pair_id")
    // Kodo pokaze gostitelj; naprava, ki se prikljucuje, je ne sme dobiti.
    preveriEnako("odgovor NE vsebuje kode", "", polje(prijava.telo, "pin"))
    preveriEnako("odgovor pove nacin", HubUsmerjevalnik.NACIN_SPAKE2, polje(prijava.telo, "nacin"))
    preveriEnako("odgovor pove identiteto huba", HubUsmerjevalnik.IDENTITETA_HUBA, polje(prijava.telo, "hub_id"))
    val pin = u.cakajocePrijave().first().pin
    preveri("gostitelj ima sestmestno kodo", pin.length == 6)
    preveri("nova naprava ne potrebuje potrditve na gostitelju", !u.cakajocePrijave().first().potrebujePotrditev)

    // Stari poti sta zaprti: koda ne sme potovati po omrezju, potrditev brez vezave na potrdilo ne velja.
    preveriEnako("stari verify je 410", 410,
        u.odgovori(zahteva("POST", "/cast/pair/verify", """{"pair_id":"$pairId","pin":"$pin"}"""))?.koda)
    preveriEnako("stari claim je 410", 410,
        u.odgovori(zahteva("POST", "/cast/pair/claim", """{"pair_id":"$pairId"}"""))?.koda)
    preveri("po starih poteh prijava ostane nedotaknjena", u.cakajocePrijave().any { it.pairId == pairId })

    // ---- SPAKE2: koda ostane na obeh zaslonih, po omrezju gredo tocke ----
    fun seznaniSpake(pair: String, naprava: String, koda: String, odtis: String = u.lastniOdtis): Pair<Int, String> {
        val o = Spake2.odjemalec(koda, naprava, HubUsmerjevalnik.IDENTITETA_HUBA, odtis.toByteArray(), pair.toByteArray())
        val k1 = u.odgovori(zahteva("POST", "/cast/pair/spake",
            """{"pair_id":"$pair","device_id":"$naprava","pb":"${HubUsmerjevalnik.bajteVHex(o.sporocilo())}"}"""))
        if (k1?.koda != 200) return (k1?.koda ?: 0) to k1?.telo.orEmpty()
        val pa = HubUsmerjevalnik.hexVBajte(polje(k1.telo, "pa"))!!
        val ca = HubUsmerjevalnik.hexVBajte(polje(k1.telo, "ca"))!!
        val cb = o.zakljuci(pa)
        // Posten odjemalec bi ob neujemanju cA odnehal; tu igramo tudi ugibalca, ki poslje cb
        // vseeno - Hub mora tak poskus steti in ga zavrniti.
        preveri("hubova potrditev se ujema natanko takrat, ko je koda prava in odtis isti",
            o.preveri(ca) == (koda == u.cakajocePrijave().firstOrNull { it.pairId == pair }?.pin && odtis == u.lastniOdtis))
        val k2 = u.odgovori(zahteva("POST", "/cast/pair/finish",
            """{"pair_id":"$pair","device_id":"$naprava","cb":"${HubUsmerjevalnik.bajteVHex(cb)}"}"""))
        return (k2?.koda ?: 0) to k2?.telo.orEmpty()
    }

    preveriEnako("spake z interneta je 403", 403,
        u.odgovori(zahteva("POST", "/cast/pair/spake", """{"pair_id":"$pairId","device_id":"fon1","pb":"04"}""", "203.0.113.5"))?.koda)
    preveriEnako("spake brez pb je 400", 400,
        u.odgovori(zahteva("POST", "/cast/pair/spake", """{"pair_id":"$pairId","device_id":"fon1"}"""))?.koda)
    preveriEnako("spake z neveljavno tocko je 400", 400,
        u.odgovori(zahteva("POST", "/cast/pair/spake", """{"pair_id":"$pairId","device_id":"fon1","pb":"${"04" + "00".repeat(64)}"}"""))?.koda)
    preveriEnako("finish pred spake je 409", 409,
        u.odgovori(zahteva("POST", "/cast/pair/finish", """{"pair_id":"$pairId","device_id":"fon1","cb":"00"}"""))?.koda)
    preveriEnako("tuja naprava z istim pair_id je 404", 404,
        u.odgovori(zahteva("POST", "/cast/pair/spake", """{"pair_id":"$pairId","device_id":"vsiljivec","pb":"04"}"""))?.koda)

    val (napacnaKoda, _) = seznaniSpake(pairId, "fon1", "000000")
    preveriEnako("napacna koda je 401", 401, napacnaKoda)
    preveri("po napacni kodi prijava se zivi", u.cakajocePrijave().any { it.pairId == pairId })

    // Napadalec v sredini: naprava je videla drugo potrdilo TLS -> potrditvi se ne ujemata, ceprav je koda prava.
    u.lastniOdtis = "aa".repeat(32)
    val (mitm, _) = seznaniSpake(pairId, "fon1", pin, odtis = "bb".repeat(32))
    preveriEnako("prava koda z napacnim odtisom potrdila je 401 (clovek v sredini)", 401, mitm)

    val (uspeh, teloUspeha) = seznaniSpake(pairId, "fon1", pin)
    preveriEnako("pravilna koda in pravi odtis izdata zeton", 200, uspeh)
    val zeton = polje(teloUspeha, "token")
    preveri("zeton ima prepoznavno predpono", zeton.startsWith("saf_tv_"))
    preveri("zeton velja", u.jeVeljavenZeton(zeton))
    preveri("uspesne prijave ni vec med cakajocimi", u.cakajocePrijave().none { it.pairId == pairId })
    preveriEnako("prijava po uspehu izgine (404)", 404, seznaniSpake(pairId, "fon1", pin).first)

    // Dolzino zetona merimo na pravi nakljucnosti, ne na laznem generatorju iz preizkusa.
    val pravi = HubUsmerjevalnik(null, { cas })
    val (praviPair, praviPin) = pravi.zacniSeznanitev("fon9", "Pravi", "192.168.0.60")!!
    val o9 = Spake2.odjemalec(praviPin, "fon9", HubUsmerjevalnik.IDENTITETA_HUBA, ByteArray(0), praviPair.toByteArray())
    val i9 = pravi.spakeKorak1(praviPair, "fon9", o9.sporocilo())
    val praviZeton = pravi.spakeKorak2(praviPair, "fon9", o9.zakljuci(i9.pa!!)).zeton.orEmpty()
    preveri("pravi zeton je dovolj dolg", praviZeton.length >= 48)

    // ---- meja poskusov: ugibanje nima smisla ----
    val p3 = u.odgovori(zahteva("POST", "/cast/pair/start", """{"device_id":"fon3","name":"Ugibalec"}"""))
    val pair3 = polje(p3!!.telo, "pair_id")
    for (i in 1 until HubUsmerjevalnik.NAJVEC_POSKUSOV) {
        preveriEnako("zgresen poskus $i je 401", 401, seznaniSpake(pair3, "fon3", "11111$i").first)
    }
    preveriEnako("zadnji dovoljeni zgreseni poskus konca prijavo (429)", 429, seznaniSpake(pair3, "fon3", "999999").first)
    preveri("prijava ugibalca je odstranjena", u.cakajocePrijave().none { it.pairId == pair3 })
    preveriEnako("po tem je vsak poskus 404", 404, seznaniSpake(pair3, "fon3", "999999").first)

    // Tudi samo zacenjanje krogov brez zakljucka je omejeno (sondiranje).
    val p4 = u.odgovori(zahteva("POST", "/cast/pair/start", """{"device_id":"fon4","name":"Sonda"}"""))
    val pair4 = polje(p4!!.telo, "pair_id")
    val sonda = Spake2.odjemalec("123456", "fon4", HubUsmerjevalnik.IDENTITETA_HUBA, ByteArray(0), pair4.toByteArray())
    var zadnja = 0
    for (i in 1..HubUsmerjevalnik.NAJVEC_POSKUSOV + 1) {
        zadnja = u.odgovori(zahteva("POST", "/cast/pair/spake",
            """{"pair_id":"$pair4","device_id":"fon4","pb":"${HubUsmerjevalnik.bajteVHex(sonda.sporocilo())}"}"""))?.koda ?: 0
    }
    preveriEnako("preveč zacetih krogov konca prijavo (429)", 429, zadnja)

    preveriEnako("brez zetona ni vstopnice", 401, u.odgovori(zahteva("POST", "/cast/ticket"))?.koda)
    val vstopnica = u.odgovori(zahteva("POST", "/cast/ticket", glave = mapOf("x-safeer-token" to zeton)))
    preveriEnako("z zetonom je vstopnica", 200, vstopnica?.koda)
    preveri("vstopnica takoj deluje", u.porabiVstopnico(polje(vstopnica!!.telo, "ticket")))

    preveriEnako("seznam naprav zahteva zeton", 401, u.odgovori(zahteva("GET", "/cast/devices"))?.koda)
    preveriEnako("s zetonom je seznam", 200,
        u.odgovori(zahteva("GET", "/cast/devices", glave = mapOf("x-safeer-token" to zeton)))?.koda)

    val stanje = u.odgovori(zahteva("GET", "/cast/health", glave = mapOf("x-safeer-token" to zeton)))
    preveriEnako("stanje je na voljo", 200, stanje?.koda)
    preveriEnako("razlicica protokola je ista kot na racunalniku", "0.2", polje(stanje!!.telo, "protocol"))

    preveri("neznana pot ni odgovorjena", u.odgovori(zahteva("GET", "/cast/skrivnost")) == null)
    // Naprava, ki isce Hub, prav po tem loci Safeer Hub od tujega streznika na istih vratih.
    preveriEnako("znana pot z napacnim glagolom vrne 405", 405,
        u.odgovori(zahteva("GET", "/cast/ticket"))?.koda)
    preveriEnako("tudi prijava z GET vrne 405", 405,
        u.odgovori(zahteva("GET", "/cast/pair/start"))?.koda)
    preveri("potrjevanje ni dosegljivo po omrezju",
        u.odgovori(zahteva("POST", "/cast/pair/approve", """{"pair_id":"$pairId"}""")) == null)
    preveri("cakajocih prijav ni mogoce prebrati po omrezju",
        u.odgovori(zahteva("GET", "/cast/pair/pending")) == null)
    preveri("odvzem dostopa ni dosegljiv po omrezju",
        u.odgovori(zahteva("POST", "/cast/devices/revoke", """{"device_id":"fon1"}""")) == null)

    // Prevec prijav -> 429
    for (i in 0 until HubUsmerjevalnik.NAJVEC_CAKAJOCIH + 2) {
        u.odgovori(zahteva("POST", "/cast/pair/start", """{"device_id":"m$i","name":"M$i"}"""))
    }
    preveriEnako("prevec prijav dobi 429", 429,
        u.odgovori(zahteva("POST", "/cast/pair/start", """{"device_id":"zadnji","name":"Z"}"""))?.koda)
}

// ------------------------------------------------------------ meje

private fun preizkusMeja() {
    println("\n== meje pomnilnika ==")
    val u = usmerjevalnik()
    val odjemalci = ArrayList<Lazni>()
    for (i in 0 until HubUsmerjevalnik.NAJVEC_NAPRAV + 4) {
        val o = Lazni("192.168.0.${100 + i}")
        odjemalci.add(o)
        u.odgovorNa(o, registracija("n$i", "receiver"))
    }
    preveri("povezanih naprav ni vec kot dovoljeno", u.steviloNaprav() <= HubUsmerjevalnik.NAJVEC_NAPRAV)

    val dolgoIme = "I".repeat(200)
    val u2 = usmerjevalnik()
    val o = Lazni()
    u2.odgovorNa(o, """{"id":"d1","type":"cast.register","payload":{"device_id":"dolg","name":"$dolgoIme"}}""")
    val ime = JsonLahki.objekt("{\"n\":${u2.povezaniPrejemniki().substringAfter("\"name\":").substringBefore(",")}}")
        ?.niz("n").orEmpty()
    preveri("ime je porezano", ime.length <= HubUsmerjevalnik.NAJVEC_IMENA)

    preveri("krajevni naslov je prepoznan", HubUsmerjevalnik.jeKrajevni("192.168.0.5"))
    preveri("10.x je krajevni", HubUsmerjevalnik.jeKrajevni("10.0.0.7"))
    preveri("172.16.x je krajevni", HubUsmerjevalnik.jeKrajevni("172.16.4.4"))
    preveri("localhost je krajevni", HubUsmerjevalnik.jeKrajevni("127.0.0.1"))
    preveri("javni naslov ni krajevni", !HubUsmerjevalnik.jeKrajevni("8.8.8.8"))
    preveri("prazen naslov ni krajevni", !HubUsmerjevalnik.jeKrajevni(""))
    preveri("ime gostitelja ni naslov", !HubUsmerjevalnik.jeKrajevni("zlonamerno.example.com"))
}

private fun preizkusDeljenjaPoHttp() {
    println("\n== deljenje po HTTP: besedilo, zaslon, datoteka ==")
    val shramba = LazniPomnilnik()
    val u = usmerjevalnik(shramba)
    val mapaPrenosov = java.nio.file.Files.createTempDirectory("safeer-prenosi").toFile()
    val mapaZacasna = java.nio.file.Files.createTempDirectory("safeer-zacasno").toFile()
    val tokovi = HubTokovi(
        mapaPrenosov = { mapaPrenosov },
        mapaZacasna = { mapaZacasna },
        jeVeljavenZeton = { u.jeVeljavenZeton(it) },
        lastniId = { "tv-gostitelj" }
    )
    u.tokovi = tokovi
    val zetonTv = u.zagotoviLastniZeton("tv-gostitelj", "Safeer TV")
    val zetonTablice = u.zagotoviLastniZeton("tablica", "Tablica")
    val zetonPc = u.zagotoviLastniZeton("pc", "Racunalnik")
    val glaveTablice = mapOf("x-safeer-token" to zetonTablice)
    val glavePc = mapOf("x-safeer-token" to zetonPc)

    val tv = Lazni("192.168.0.20")
    val tablica = Lazni("192.168.0.31")
    val pc = Lazni("192.168.0.40")
    val fon = Lazni("192.168.0.41")
    u.odgovorNa(tv, registracija("tv-gostitelj", "receiver"))
    u.odgovorNa(tablica, registracija("tablica", "sender"))
    u.odgovorNa(pc, registracija("pc", "sender"))
    u.odgovorNa(fon, registracija("fon2", "sender"))
    tv.pocisti(); tablica.pocisti(); pc.pocisti(); fon.pocisti()

    // ---- identiteta: posiljatelj je lastnik zetona, ne tisto, kar pise v telesu ----
    preveriEnako("zeton pripada napravi", "tablica", u.napravaZeZetona(zetonTablice))
    preveri("neznan zeton nima naprave", u.napravaZeZetona("saf_tv_x") == null && u.napravaZeZetona(null) == null)

    // ---- besedilo ----
    preveriEnako("besedilo brez zetona je 401", 401,
        u.odgovori(zahteva("POST", "/cast/share/text", """{"target":"tv-gostitelj","text":"Zdravo"}"""))?.koda)
    preveriEnako("besedilo z zetonom gre skozi", 200,
        u.odgovori(zahteva("POST", "/cast/share/text", """{"device_id":"pc","target":"tv-gostitelj","text":"Zdravo TV"}""", glave = glaveTablice))?.koda)
    preveriEnako("cilj dobi share.text", "share.text", tip(tv.zadnje()))
    preveri("posiljatelj je lastnik zetona, ne device_id iz telesa", tv.zadnje().contains("\"sender\":\"tablica\"") && tv.zadnje().contains("Zdravo TV"))
    preveriEnako("prazno besedilo je 400", 400,
        u.odgovori(zahteva("POST", "/cast/share/text", """{"target":"tv-gostitelj","text":"  "}""", glave = glaveTablice))?.koda)
    preveriEnako("besedilo nepovezani napravi je 404", 404,
        u.odgovori(zahteva("POST", "/cast/share/text", """{"target":"nihce","text":"x"}""", glave = glaveTablice))?.koda)
    preveriEnako("besedilo samemu sebi je 400", 400,
        u.odgovori(zahteva("POST", "/cast/share/text", """{"target":"tablica","text":"x"}""", glave = glaveTablice))?.koda)

    // ---- zaslon: Hub sam pove cilju, kje gleda, in kdaj je konec ----
    tv.pocisti()
    preveriEnako("zaslon brez cilja je 400", 400,
        u.odgovori(zahteva("POST", "/cast/share/screen/start", """{}""", glave = glaveTablice))?.koda)
    preveriEnako("zaslon nepovezanemu cilju je 404", 404,
        u.odgovori(zahteva("POST", "/cast/share/screen/start", """{"target":"nihce"}""", glave = glaveTablice))?.koda)
    val zacetek = u.odgovori(zahteva("POST", "/cast/share/screen/start", """{"target":"tv-gostitelj"}""", glave = glaveTablice))
    preveriEnako("zacetek deljenja je 200", 200, zacetek?.koda)
    val idZaslona = polje(zacetek!!.telo, "id")
    val potGledanja = polje(zacetek.telo, "view_path")
    preveri("odgovor ima push_path in view_path", polje(zacetek.telo, "push_path").startsWith("/cast/screen/") && potGledanja.contains("/view?k="))
    preveriEnako("cilj dobi share.screen", "share.screen", tip(tv.zadnje()))
    preveri("cilj dobi action start in pot gledalca", tv.zadnje().contains("\"action\":\"start\"") && tv.zadnje().contains(potGledanja))
    preveri("deljenje tece", tokovi.zaslonTece(idZaslona))

    // ---- ena naprava naenkrat: dokler tablica deli s televizorjem, racunalnik caka ----
    println("\n== ena naprava deli naenkrat ==")
    preveriEnako("televizor je zaseden za tablico", "tablica", u.zasedenOd("tv-gostitelj"))
    val zaseden = u.odgovori(zahteva("POST", "/cast/share/screen/start", """{"target":"tv-gostitelj"}""", glave = glavePc))
    preveriEnako("racunalnik ne more deliti zaslona s televizorjem (409)", 409, zaseden?.koda)
    preveri("odgovor pove, kdo deli", zaseden!!.telo.contains("\"busy_by\":\"tablica\"") && zaseden.telo.contains("naprava_zasedena"))
    preveriEnako("racunalnik ne more poslati besedila televizorju (409)", 409,
        u.odgovori(zahteva("POST", "/cast/share/text", """{"target":"tv-gostitelj","text":"x"}""", glave = glavePc))?.koda)
    preveriEnako("tudi po WebSocketu je zavrnjeno", "naprava_zasedena",
        polje(u.odgovorNa(pc, """{"id":"w1","type":"share.text","target":"tv-gostitelj","payload":{"text":"x"}}""")!!, "error_code"))
    preveriEnako("tablica sama lahko televizorju se vedno poslje besedilo", 200,
        u.odgovori(zahteva("POST", "/cast/share/text", """{"target":"tv-gostitelj","text":"se jaz"}""", glave = glaveTablice))?.koda)
    val naTelefon = u.odgovori(zahteva("POST", "/cast/share/screen/start", """{"target":"fon2"}""", glave = glavePc))
    preveriEnako("racunalnik pa lahko medtem deli zaslon s telefonom", 200, naTelefon?.koda)
    preveri("seznam naprav pove, da je televizor zaseden", u.povezaniPrejemniki().contains("\"busy_by\":\"tablica\"") &&
        u.povezaniPrejemniki().contains("\"busy_by_name\":\"Naprava tablica\""))
    tv.pocisti()
    preveriEnako("konec deljenja je 200", 200,
        u.odgovori(zahteva("POST", "/cast/share/screen/stop", """{"id":"$idZaslona"}""", glave = glaveTablice))?.koda)
    preveri("cilj dobi share.screen stop", tv.prejeto.any { tip(it) == "share.screen" && it.contains("\"action\":\"stop\"") })
    preveri("po sprostitvi dobijo vsi nov seznam naprav", tip(tv.zadnje()) == "cast.devices" && !tv.zadnje().contains("busy_by\":\"tablica"))
    preveri("deljenje ne tece vec", !tokovi.zaslonTece(idZaslona))
    preveri("televizor je spet prost", u.zasedenOd("tv-gostitelj") == null)
    val zdajPc = u.odgovori(zahteva("POST", "/cast/share/screen/start", """{"target":"tv-gostitelj"}""", glave = glavePc))
    preveriEnako("zdaj lahko racunalnik deli s televizorjem", 200, zdajPc?.koda)
    u.odgovori(zahteva("POST", "/cast/share/screen/stop", """{"id":"${polje(zdajPc!!.telo, "id")}"}""", glave = glavePc))
    u.odgovori(zahteva("POST", "/cast/share/screen/stop", """{"id":"${polje(naTelefon!!.telo, "id")}"}""", glave = glavePc))
    tv.pocisti()
    u.odgovori(zahteva("POST", "/cast/share/screen/stop", """{"id":"$idZaslona"}""", glave = glaveTablice))
    preveri("ponovni stop ne poslje nicesar", tv.zadnje().isEmpty())

    // ---- datoteka: ko je na Hubu cela, Hub pove cilju; med prenosom je cilj zaseden ----
    println("\n== datoteka prek Huba ==")
    preveri("prenos zasede cilj", tokovi.zasediCilj?.invoke("fon2", "tablica") == null && u.zasedenOd("fon2") == "tablica")
    preveri("drugi posiljatelj med prenosom ne more", tokovi.zasediCilj?.invoke("fon2", "pc") == "tablica")
    tokovi.sprostiCilj?.invoke("fon2", "tablica")
    preveri("po prenosu je cilj prost", u.zasedenOd("fon2") == null)
    fon.pocisti()
    val datoteka = HubTokovi.Datoteka("abc", "slika.jpg", 1234L, java.io.File(mapaZacasna, "x-slika.jpg"), "kljuc1",
        "fon2", "tablica", false, cas, "deadbeef")
    tokovi.naDatoteko?.invoke(datoteka)
    preveriEnako("cilj dobi share.file", "share.file", tip(fon.zadnje()))
    preveri("share.file nosi ime, pot prevzema, odtis in posiljatelja",
        fon.zadnje().contains("\"name\":\"slika.jpg\"") && fon.zadnje().contains("/cast/file/abc?k=kljuc1") &&
            fon.zadnje().contains("\"sender\":\"tablica\"") && fon.zadnje().contains("\"for_host\":false") &&
            fon.zadnje().contains("\"sha256\":\"deadbeef\""))
    preveri("tokovi vedo, kdo je povezan", tokovi.jeCiljPovezan?.invoke("tablica") == true && tokovi.jeCiljPovezan?.invoke("nihce") == false)
    preveri("tokovi poznajo lastnika zetona", tokovi.napravaZeZetona?.invoke(zetonPc) == "pc")

    // ---- imena naprav: uporabnik jih poimenuje, Hub si jih zapomni za vse ----
    println("\n== poimenovanje naprav ==")
    preveriEnako("preimenovanje brez zetona je 401", 401,
        u.odgovori(zahteva("POST", "/cast/devices/rename", """{"device_id":"tv-gostitelj","name":"Dnevna soba"}"""))?.koda)
    preveriEnako("preimenovanje z zetonom je 200", 200,
        u.odgovori(zahteva("POST", "/cast/devices/rename", """{"device_id":"tv-gostitelj","name":"  Dnevna <soba>  "}""", glave = glaveTablice))?.koda)
    preveriEnako("ime je ocisceno in shranjeno", "Dnevna soba", u.imeNaprave("tv-gostitelj"))
    preveri("seznam naprav kaze novo ime, staro ostane kot own_name",
        u.povezaniPrejemniki().contains("\"name\":\"Dnevna soba\"") && u.povezaniPrejemniki().contains("\"own_name\":\"Naprava tv-gostitelj\""))
    preveriEnako("vsi povezani dobijo nov seznam", "cast.devices", tip(pc.zadnje()))
    preveri("tudi seznam seznanjenih kaze vzdevek", u.seznanjeneNaprave().any { it.deviceId == "tv-gostitelj" && it.ime == "Dnevna soba" })
    tv.pocisti()
    u.odgovori(zahteva("POST", "/cast/share/text", """{"target":"tv-gostitelj","text":"hej"}""", glave = glavePc))
    u.odgovori(zahteva("POST", "/cast/devices/rename", """{"device_id":"pc","name":"Matejev racunalnik"}""", glave = glavePc))
    tv.pocisti()
    u.odgovori(zahteva("POST", "/cast/share/text", """{"target":"tv-gostitelj","text":"hej"}""", glave = glavePc))
    preveri("prejemnik vidi vzdevek posiljatelja", tv.zadnje().contains("\"sender_name\":\"Matejev racunalnik\""))
    val u2 = usmerjevalnik(shramba)
    preveriEnako("vzdevki prezivijo ponovni zagon Huba", "Matejev racunalnik", u2.imeNaprave("pc"))
    u.odgovori(zahteva("POST", "/cast/devices/rename", """{"device_id":"pc","name":""}""", glave = glavePc))
    preveriEnako("prazno ime vzdevek odstrani", "Naprava pc", u.imeNaprave("pc"))

    // Ko cilj odide, deljenje zaslona naj se konca brez napake.
    val zacetek2 = u.odgovori(zahteva("POST", "/cast/share/screen/start", """{"target":"tv-gostitelj"}""", glave = glaveTablice))
    val id2 = polje(zacetek2!!.telo, "id")
    u.odklopi(tv)
    tokovi.koncajZaslon(id2)
    preveri("konec po odhodu cilja ne vrze napake", !tokovi.zaslonTece(id2) && u.zasedenOd("tv-gostitelj") == null)

    mapaPrenosov.deleteRecursively(); mapaZacasna.deleteRecursively()
}


// ------------------------------------------------------------ sorodna naprava (Safeer Control ob seznanjenem brskalniku)

private fun preizkusSorodnika() {
    println("- sorodna naprava")
    val u = usmerjevalnik()
    val zeton = u.zagotoviLastniZeton("pc-primer", "Safeer (primer)")
    fun sorodnik(telo: String, glave: Map<String, String> = mapOf("x-safeer-token" to zeton), od: String = "192.168.0.50") =
        u.odgovori(zahteva("POST", "/cast/pair/sibling", telo, od, glave))
    preveriEnako("brez zetona je 401", 401, sorodnik("""{"device_id":"pc-primer-control","name":"Control"}""", emptyMap())?.koda)
    preveriEnako("z interneta je 403", 403, sorodnik("""{"device_id":"pc-primer-control"}""", od = "203.0.113.5")?.koda)
    preveriEnako("tuja naprava ni sorodnik", 403, sorodnik("""{"device_id":"fon-tuja"}""")?.koda)
    preveriEnako("ista naprava ni sorodnik", 403, sorodnik("""{"device_id":"pc-primer"}""")?.koda)
    preveriEnako("brez device_id je 400", 400, sorodnik("""{"name":"Control"}""")?.koda)
    val ok = sorodnik("""{"device_id":"pc-primer-control","name":"Safeer Control (primer)"}""")
    preveriEnako("sorodnik dobi zeton", 200, ok?.koda)
    val nov = polje(ok?.telo.orEmpty(), "token")
    preveri("nov zeton je drug in veljaven", nov.isNotEmpty() && nov != zeton && u.jeVeljavenZeton(nov))
    preveriEnako("nov zeton pripada sorodniku", "pc-primer-control", u.napravaZeZetona(nov))
    preveriEnako("odtis Huba je zraven", u.lastniOdtis, polje(ok?.telo.orEmpty(), "fp"))
    preveri("sorodnik je med seznanjenimi", u.seznanjeneNaprave().any { it.deviceId == "pc-primer-control" })
    val ponovno = sorodnik("""{"device_id":"pc-primer-control","name":"Safeer Control (primer)"}""")
    preveri("ponovna zahteva zamenja zeton, naprava ostane ena",
        ponovno?.koda == 200 && u.seznanjeneNaprave().count { it.deviceId == "pc-primer-control" } == 1 && !u.jeVeljavenZeton(nov))
    preveriEnako("GET na to pot je 405", 405, u.odgovori(zahteva("GET", "/cast/pair/sibling"))?.koda)
}

// ------------------------------------------------------------ krog zaupanja

private fun parKljucev(): java.security.KeyPair =
    java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()

private fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)

private fun podpisi(par: java.security.KeyPair, podatki: ByteArray): String {
    val s = java.security.Signature.getInstance("SHA256withECDSA")
    s.initSign(par.private); s.update(podatki)
    return b64(s.sign())
}

private fun preizkusKroga() {
    println("\nKrog zaupanja")
    val shramba = LazniPomnilnik()
    val u = usmerjevalnik(shramba)
    u.lastniOdtis = "ABCDEF0123"
    val hub = parKljucev()
    u.vpisiLastniKljuc("tv-hub", "Dnevna soba", b64(hub.public.encoded), "tv")
    preveriEnako("hub je prvi clan kroga", 1, u.krog.stevilo())
    preveri("krog je v trajni shrambi", shramba.vsebina.containsKey(KrogZaupanja.KLJUC_SHRAMBE))

    // Prehod: naprava s starim zetonom vpise svoj kljuc - brez nove kode.
    val zeton = u.zagotoviLastniZeton("tel-1", "Telefon")
    val tel = parKljucev()
    val brez = u.odgovori(zahteva("POST", "/cast/trust/enroll", """{"pubkey":"${b64(tel.public.encoded)}"}"""))
    preveriEnako("vpis brez zetona je 401", 401, brez?.koda)
    val vpis = u.odgovori(zahteva("POST", "/cast/trust/enroll", """{"pubkey":"${b64(tel.public.encoded)}","name":"Moj telefon","platform":"phone"}""",
        glave = mapOf("x-safeer-token" to zeton)))
    preveriEnako("vpis z zetonom uspe", 200, vpis?.koda)
    preveriEnako("vpisana je naprava zetona, ne tista iz telesa", "tel-1", polje(vpis?.telo.orEmpty(), "device_id"))
    preveriEnako("krog ima dva clana", 2, u.krog.stevilo())
    preveriEnako("ime iz vpisa", "Moj telefon", u.krog.clan("tel-1")?.ime)
    val slab = u.odgovori(zahteva("POST", "/cast/trust/enroll", """{"pubkey":"bm9uc2Vuc2U="}""", glave = mapOf("x-safeer-token" to zeton)))
    preveriEnako("neveljaven kljuc je 400", 400, slab?.koda)

    // Prijava s podpisom: izziv -> podpis -> vstopnica.
    val neznan = u.odgovori(zahteva("POST", "/cast/auth/challenge", """{"device_id":"tuja"}"""))
    preveriEnako("izziv za napravo zunaj kroga je 401", 401, neznan?.koda)
    val izziv = u.odgovori(zahteva("POST", "/cast/auth/challenge", """{"device_id":"tel-1"}"""))
    preveriEnako("izziv za clana uspe", 200, izziv?.koda)
    val nonce = polje(izziv?.telo.orEmpty(), "nonce")
    preveri("izziv ima nonce", nonce.isNotBlank())
    preveriEnako("izziv nosi odtis huba", "ABCDEF0123", polje(izziv?.telo.orEmpty(), "fp"))
    val napacen = u.odgovori(zahteva("POST", "/cast/auth/ticket",
        """{"device_id":"tel-1","nonce":"$nonce","signature":"${podpisi(parKljucev(), u.podatkiZaPodpis("tel-1", nonce))}"}"""))
    preveriEnako("podpis z drugim kljucem je 401", 401, napacen?.koda)
    val izziv2 = u.odgovori(zahteva("POST", "/cast/auth/challenge", """{"device_id":"tel-1"}"""))
    val nonce2 = polje(izziv2?.telo.orEmpty(), "nonce")
    preveri("porabljen izziv ne velja vec", nonce2 != nonce)
    val pravi = u.odgovori(zahteva("POST", "/cast/auth/ticket",
        """{"device_id":"tel-1","nonce":"$nonce2","signature":"${podpisi(tel, u.podatkiZaPodpis("tel-1", nonce2))}"}"""))
    preveriEnako("pravi podpis da vstopnico", 200, pravi?.koda)
    val vstopnica = polje(pravi?.telo.orEmpty(), "ticket")
    preveri("vstopnica je uporabna za WebSocket", u.porabiVstopnico(vstopnica))
    // Vstopnica je vezana na napravo, ki ji je bila izdana: prijava pod tujim id ne velja.
    preveriEnako("vstopnica s podpisom je vezana na napravo", "tel-1", u.napravaVstopnice(vstopnica))
    val tujec = Lazni(vstopnica = vstopnica)
    u.obdelaj(tujec, registracija("tuja-naprava", "sender"))
    preveri("prijava pod tujim id z vezano vstopnico je zavrnjena", polje(tujec.zadnje(), "error_code") == "napacen_device_id")
    val pravi2 = Lazni(vstopnica = vstopnica)
    u.obdelaj(pravi2, registracija("tel-1", "sender"))
    preveriEnako("prijava pod svojim id uspe", "accepted", polje(pravi2.zadnje(), "status"))
    preveriEnako("po prijavi vezava odpade", null, u.napravaVstopnice(vstopnica))
    // Vstopnica z zetonom je vezana na lastnika zetona.
    val zZetonom = u.odgovori(zahteva("POST", "/cast/ticket", "", glave = mapOf("x-safeer-token" to zeton)))
    val vstopnicaZ = polje(zZetonom?.telo.orEmpty(), "ticket")
    preveri("vstopnica z zetonom je porabljiva", u.porabiVstopnico(vstopnicaZ))
    preveriEnako("vstopnica z zetonom je vezana na lastnika zetona", "tel-1", u.napravaVstopnice(vstopnicaZ))
    // Povezava brez vstopnice (preizkusi, stari tok) se prijavi po starem.
    val brezVstopnice = Lazni()
    u.obdelaj(brezVstopnice, registracija("tel-9", "sender"))
    preveriEnako("prijava brez vezane vstopnice gre po starem", "accepted", polje(brezVstopnice.zadnje(), "status"))
    preveri("odgovor prinese krog", JsonLahki.objekt(pravi?.telo.orEmpty())?.objekt("ring")?.objekt("clani")?.ima("tel-1") == true)
    // Alias: ista naprava (isti kljuc) vpise se svoj drugi id - dokaz je podpis za znani id.
    val izzivA = u.odgovori(zahteva("POST", "/cast/auth/challenge", """{"device_id":"tel-1"}"""))
    val nonceA = polje(izzivA?.telo.orEmpty(), "nonce")
    val aliasTuj = u.odgovori(zahteva("POST", "/cast/trust/alias",
        """{"device_id":"tel-1","nonce":"$nonceA","signature":"${podpisi(parKljucev(), u.podatkiZaPodpis("tel-1", nonceA))}","alias":"tel-1-os"}"""))
    preveriEnako("alias s tujim podpisom je 401", 401, aliasTuj?.koda)
    val izzivB = u.odgovori(zahteva("POST", "/cast/auth/challenge", """{"device_id":"tel-1"}"""))
    val nonceB = polje(izzivB?.telo.orEmpty(), "nonce")
    val aliasOk = u.odgovori(zahteva("POST", "/cast/trust/alias",
        """{"device_id":"tel-1","nonce":"$nonceB","signature":"${podpisi(tel, u.podatkiZaPodpis("tel-1", nonceB))}","alias":"tel-1-os","name":"Telefon OS"}"""))
    preveriEnako("alias s pravim podpisom uspe", 200, aliasOk?.koda)
    preveriEnako("alias ima isti kljuc", b64(tel.public.encoded), u.krog.clan("tel-1-os")?.kljuc)
    // Ime pripada napravi (kljucu), ne id-ju: alias podeduje ime, da je naprava na vseh hubih ista.
    preveriEnako("alias podeduje ime naprave", "Moj telefon", u.krog.clan("tel-1-os")?.ime)
    val izzivC = u.odgovori(zahteva("POST", "/cast/auth/challenge", """{"device_id":"tel-1-os"}"""))
    preveriEnako("alias dobi izziv", 200, izzivC?.koda)
    val izzivD = u.odgovori(zahteva("POST", "/cast/auth/challenge", """{"device_id":"tel-1"}"""))
    val nonceD = polje(izzivD?.telo.orEmpty(), "nonce")
    val aliasZaseden = u.odgovori(zahteva("POST", "/cast/trust/alias",
        """{"device_id":"tel-1","nonce":"$nonceD","signature":"${podpisi(tel, u.podatkiZaPodpis("tel-1", nonceD))}","alias":"tv-hub"}"""))
    preveriEnako("alias na id z drugim kljucem je 409", 409, aliasZaseden?.koda)
    val znova = u.odgovori(zahteva("POST", "/cast/auth/ticket",
        """{"device_id":"tel-1","nonce":"$nonce2","signature":"${podpisi(tel, u.podatkiZaPodpis("tel-1", nonce2))}"}"""))
    preveriEnako("isti izziv drugic ne velja", 401, znova?.koda)
    val tujOdtis = "safeer-link-auth\nffff\n$nonce2\ntel-1".toByteArray()
    preveri("podpis je vezan na odtis huba", !u.krog.preveriPodpis("tel-1", u.podatkiZaPodpis("tel-1", nonce2), podpisi(tel, tujOdtis)))

    // Ob prijavi po WebSocketu dobi naprava krog; ob spremembi kroga ga dobijo vsi.
    val o = Lazni()
    u.obdelaj(o, registracija("tel-1", "sender"))
    preveri("naprava ob prijavi dobi trust.update", o.prejeto.any { tip(it) == "trust.update" })
    o.pocisti()
    u.krog.umakni("tuja-naprava", "tv-hub")   // umik neznane naprave nicesar ne spremeni
    preveri("umik neznane naprave ne razposilja", o.prejeto.none { tip(it) == "trust.update" })
    u.krog.umakni("tel-1", "tv-hub")
    preveri("umik clana gre vsem", o.prejeto.any { tip(it) == "trust.update" })
    preveriEnako("umaknjeni ni vec clan", false, u.krog.jeClan("tel-1"))
    preveriEnako("umaknjeni ne dobi izziva", 401, u.odgovori(zahteva("POST", "/cast/auth/challenge", """{"device_id":"tel-1"}"""))?.koda)

    // Zdruzevanje je deterministicno in umik prezivi zdruzitev s starim krogom.
    val star = KrogZaupanja()
    star.zdruzi(u.krog.json())
    val drug = KrogZaupanja()
    drug.dodaj(KrogZaupanja.Clan("tel-1", b64(tel.public.encoded), "Telefon", "phone", 1.0, "tv-hub"))
    drug.zdruzi(star.json())
    preveriEnako("star vnos ne ozivi umaknjene naprave", false, drug.jeClan("tel-1"))
    val vrnjen = KrogZaupanja.Clan("tel-1", b64(tel.public.encoded), "Telefon", "phone", KrogZaupanja.zdaj() + 10, "tv-hub")
    drug.dodaj(vrnjen)
    preveriEnako("ponovna seznanitev (novejsi vnos) napravo vrne", true, drug.jeClan("tel-1"))
    star.zdruzi(drug.json())
    preveriEnako("vrnitev preide tudi v drugi krog", true, star.jeClan("tel-1"))
    preveriEnako("oba kroga sta enaka", star.json(), drug.json())
    preveri("pokvarjen zapis kroga ne spremeni nicesar", !star.zdruzi("{\"clani\":{\"x\":{\"kljuc\":\"???\"}}}"))
    preveriEnako("id iz kljuca je stabilen", KrogZaupanja.idIzKljuca(b64(tel.public.encoded)), KrogZaupanja.idIzKljuca(b64(tel.public.encoded)))
    preveri("id iz kljuca ima predpono n- in 16 znakov", KrogZaupanja.idIzKljuca(b64(tel.public.encoded)).matches(Regex("n-[0-9a-f]{16}")))
}

// ------------------------------------------------------------ podpisani vnosi v krogu (P2P)

private fun preizkusPodpisanegaKroga() {
    println()
    println("Podpisani vnosi v krogu")
    val hub = parKljucev()
    val telefon = parKljucev()
    val vsiljivec = parKljucev()
    val hubId = KrogZaupanja.idIzKljuca(b64(hub.public.encoded))

    // Hub doda telefon in vnos podpise (kot naprava, ki v krog doda drugo napravo).
    val krogHuba = KrogZaupanja()
    krogHuba.lastniKljuc = b64(hub.public.encoded)
    krogHuba.podpisnik = { podatki -> podpisi(hub, podatki) }
    krogHuba.dodaj(KrogZaupanja.Clan(hubId, b64(hub.public.encoded), "TV", "tv", 100.0, hubId))
    krogHuba.dodaj(KrogZaupanja.Clan("tel-1", b64(telefon.public.encoded), "Telefon", "phone", 200.0, hubId))
    preveri("vnos, ki ga dodamo mi, je podpisan", krogHuba.json().contains("podpis"))

    // Druga naprava, ki huba ze pozna, podpisan vnos sprejme tudi brez huba (npr. prek releja).
    val krogTablice = KrogZaupanja()
    krogTablice.zdruzi(JsonLahki.Zapis().stevilo("v", 1.0).surovo("clani", JsonLahki.Zapis().surovo(hubId,
        JsonLahki.Zapis().niz("kljuc", b64(hub.public.encoded)).niz("ime", "TV").niz("platforma", "tv")
            .stevilo("dodano", 100.0).niz("dodal", hubId).toString()).toString()).toString())
    preveriEnako("podpisan vnos sprejmemo", true, krogTablice.zdruzi(krogHuba.json(), preveriPodpise = true))
    preveriEnako("telefon je v krogu", true, krogTablice.jeClan("tel-1"))

    // Nepodpisan ali ponarejen vnos tuje naprave ne pride noter.
    val ponaredek = JsonLahki.Zapis().stevilo("v", 1.0).surovo("clani", JsonLahki.Zapis().surovo("vsiljivec",
        JsonLahki.Zapis().niz("kljuc", b64(vsiljivec.public.encoded)).niz("ime", "Vsiljivec").niz("platforma", "linux")
            .stevilo("dodano", 300.0).niz("dodal", hubId).toString()).toString()).toString()
    preveriEnako("nepodpisan vnos ne pride v krog", false, krogTablice.zdruzi(ponaredek, preveriPodpise = true))
    preveriEnako("vsiljivca ni v krogu", false, krogTablice.jeClan("vsiljivec"))
    val tujPodpis = podpisi(vsiljivec, KrogZaupanja.podatkiClana("vsiljivec", b64(vsiljivec.public.encoded), "linux", 300.0, hubId))
    val ponaredek2 = JsonLahki.Zapis().stevilo("v", 1.0).surovo("clani", JsonLahki.Zapis().surovo("vsiljivec",
        JsonLahki.Zapis().niz("kljuc", b64(vsiljivec.public.encoded)).niz("ime", "Vsiljivec").niz("platforma", "linux")
            .stevilo("dodano", 300.0).niz("dodal", hubId).niz("podpis", tujPodpis).toString()).toString()).toString()
    preveriEnako("podpis z drugim kljucem ne velja", false, krogTablice.zdruzi(ponaredek2, preveriPodpise = true))

    // Brez preverjanja (krog od nasega huba) velja kot doslej - stare naprave se naprej delajo.
    preveriEnako("krog od huba sprejmemo tudi brez podpisov", true, krogTablice.zdruzi(ponaredek))

    // Umik mora biti podpisan.
    val krogDrugi = KrogZaupanja()
    krogDrugi.zdruzi(krogHuba.json())
    val laznjivUmik = JsonLahki.Zapis().stevilo("v", 1.0).surovo("umiki", JsonLahki.Zapis().surovo("tel-1",
        JsonLahki.Zapis().stevilo("umaknjeno", 400.0).niz("umaknil", "vsiljivec").toString()).toString()).toString()
    preveriEnako("nepodpisan umik ne umakne naprave", false, krogDrugi.zdruzi(laznjivUmik, preveriPodpise = true))
    preveriEnako("telefon ostane v krogu", true, krogDrugi.jeClan("tel-1"))
    krogHuba.umakni("tel-1", hubId, 500.0)
    preveri("nas umik je podpisan", krogHuba.json().contains("umiki") && krogHuba.json().contains("podpis"))
    preveriEnako("podpisan umik sprejmemo", true, krogDrugi.zdruzi(krogHuba.json(), preveriPodpise = true))
    preveriEnako("telefon ni vec v krogu", false, krogDrugi.jeClan("tel-1"))
}

// ------------------------------------------------------------ id iz kljuca: prehod brez nove seznanitve

private fun preizkusIdaIzKljuca() {
    println()
    println("Id iz kljuca")
    preveri("n- + 16 hex je id iz kljuca", KrogZaupanja.jeIdIzKljuca("n-0123456789abcdef"))
    preveri("s pripono sorodnika tudi", KrogZaupanja.jeIdIzKljuca("n-0123456789abcdef-os"))
    preveri("stari id ni id iz kljuca", !KrogZaupanja.jeIdIzKljuca("tv-philips-os") && !KrogZaupanja.jeIdIzKljuca("n-0123456789abcdeg"))
    preveri("brez locila pred pripono ni", !KrogZaupanja.jeIdIzKljuca("n-0123456789abcdefos"))
    val u = HubUsmerjevalnik()
    u.lastniOdtis = "ABCDEF0123"
    // Krog s starim id-jem in kljucem naprave (kot po prehodu na krog zaupanja, pred id-ji iz kljuca).
    val tel = parKljucev()
    val kljuc = b64(tel.public.encoded)
    u.krog.dodaj(KrogZaupanja.Clan("tv-stari", kljuc, "Stari TV", "tv", 1.0, "hub"))
    val novi = KrogZaupanja.idIzKljuca(kljuc)
    preveriEnako("clanZaId najde stari vnos po kljucu", "tv-stari", u.krog.clanZaId(novi)?.id)
    preveriEnako("tudi za sorodnika z istim kljucem", "tv-stari", u.krog.clanZaId("$novi-os")?.id)
    preveriEnako("neznan kljuc ne najde nikogar", null, u.krog.clanZaId(KrogZaupanja.idIzKljuca(b64(parKljucev().public.encoded))))
    // Prijava s podpisom pod novim id-jem: hub sprejme podpis s starim kljucem in nov id vpise kot alias.
    val izziv = u.odgovori(zahteva("POST", "/cast/auth/challenge", """{"device_id":"$novi"}"""))
    preveriEnako("izziv za id iz kljuca, ki je v krogu pod starim id-jem, uspe", 200, izziv?.koda)
    val nonce = polje(izziv?.telo.orEmpty(), "nonce")
    val tuj = u.odgovori(zahteva("POST", "/cast/auth/ticket",
        """{"device_id":"$novi","nonce":"$nonce","signature":"${podpisi(parKljucev(), u.podatkiZaPodpis(novi, nonce))}"}"""))
    preveriEnako("tuj podpis pod novim id je 401", 401, tuj?.koda)
    preveriEnako("neuspeh ne vpise aliasa", null, u.krog.clan(novi))
    val izziv2 = u.odgovori(zahteva("POST", "/cast/auth/challenge", """{"device_id":"$novi"}"""))
    val nonce2 = polje(izziv2?.telo.orEmpty(), "nonce")
    val pravi = u.odgovori(zahteva("POST", "/cast/auth/ticket",
        """{"device_id":"$novi","nonce":"$nonce2","signature":"${podpisi(tel, u.podatkiZaPodpis(novi, nonce2))}","name":"Novi TV","platform":"tv"}"""))
    preveriEnako("pravi podpis pod novim id da vstopnico", 200, pravi?.koda)
    preveriEnako("nov id je v krogu z istim kljucem", kljuc, u.krog.clan(novi)?.kljuc)
    preveriEnako("nov id podeduje ime naprave (ne povozi ga ime iz prijave)", "Stari TV", u.krog.clan(novi)?.ime)
    preveriEnako("nov id je dodal stari id", "tv-stari", u.krog.clan(novi)?.dodal)
    preveriEnako("stari id ostane (seznanitev prezivi)", kljuc, u.krog.clan("tv-stari")?.kljuc)
    preveri("odgovor prinese krog z obema", JsonLahki.objekt(pravi?.telo.orEmpty())?.objekt("ring")?.objekt("clani")?.ima("tv-stari") == true)
    // Vstopnica, izdana staremu id-ju, velja za prijavo pod novim (isti kljuc) - in obratno.
    val izziv3 = u.odgovori(zahteva("POST", "/cast/auth/challenge", """{"device_id":"tv-stari"}"""))
    val nonce3 = polje(izziv3?.telo.orEmpty(), "nonce")
    val stara = u.odgovori(zahteva("POST", "/cast/auth/ticket",
        """{"device_id":"tv-stari","nonce":"$nonce3","signature":"${podpisi(tel, u.podatkiZaPodpis("tv-stari", nonce3))}"}"""))
    val vstopnica = polje(stara?.telo.orEmpty(), "ticket")
    preveri("vstopnica staremu id-ju", u.porabiVstopnico(vstopnica))
    val prijava = Lazni(vstopnica = vstopnica)
    u.obdelaj(prijava, registracija(novi, "receiver"))
    preveriEnako("prijava pod novim id z vstopnico starega uspe", "accepted", polje(prijava.zadnje(), "status"))
    val drugKljuc = b64(parKljucev().public.encoded)
    u.krog.dodaj(KrogZaupanja.Clan("tel-drugi", drugKljuc, "Drugi", "phone", 1.0, "hub"))
    val vstopnica2 = polje(u.odgovori(zahteva("POST", "/cast/auth/ticket", run {
        val i = u.odgovori(zahteva("POST", "/cast/auth/challenge", """{"device_id":"tv-stari"}"""))
        val n = polje(i?.telo.orEmpty(), "nonce")
        """{"device_id":"tv-stari","nonce":"$n","signature":"${podpisi(tel, u.podatkiZaPodpis("tv-stari", n))}"}"""
    }))?.telo.orEmpty(), "ticket")
    preveri("se ena vstopnica", u.porabiVstopnico(vstopnica2))
    val tujec = Lazni(vstopnica = vstopnica2)
    u.obdelaj(tujec, registracija("tel-drugi", "sender"))
    preveri("z vstopnico enega kljuca se ni mogoce prijaviti kot drug kljuc", polje(tujec.zadnje(), "error_code") == "napacen_device_id")

    // Sejni zeton: prijava s podpisom da tudi zeton za HTTP (datoteke, zaslon), vezan na napravo.
    val seja = polje(pravi?.telo.orEmpty(), "session_token")
    preveri("prijava s podpisom vrne sejni zeton", seja.startsWith("saf_seja_"))
    preveri("sejni zeton velja kot zeton", u.jeVeljavenZeton(seja))
    preveriEnako("sejni zeton pove napravo", novi, u.napravaZeZetona(seja))
    preveri("izmisljen sejni zeton ne velja", !u.jeVeljavenZeton("saf_seja_izmisljen"))
    val vstopnicaSeje = u.odgovori(zahteva("POST", "/cast/ticket", "", glave = mapOf("x-safeer-token" to seja)))
    preveriEnako("s sejnim zetonom se dobi vstopnica", 200, vstopnicaSeje?.koda)
    u.krog.umakni(novi, "hub")
    u.krog.umakni("tv-stari", "hub")
    preveri("umik iz kroga ubije sejo", !u.jeVeljavenZeton(seja))
}

// ------------------------------------------------------------ Protocol v1: model naprave in katalog aplikacij

private fun preizkusProtokolaV1() {
    println()
    println("Protocol v1")
    val u = HubUsmerjevalnik()
    val pc = Lazni("192.168.0.60")
    val katalog = """{"firefox":{"name":"Firefox","kind":"app"},"vlc":{"name":"VLC","kind":"app","icon":"data:x"}}"""
    val odgovor = u.odgovorNa(pc, """{"id":"r1","type":"cast.register","payload":{"device_id":"pc-1","name":"Racunalnik","role":"sender",
        "capabilities":["url","apps"],"protocol":"1.0","platform":"linux","kind":"computer","version":"1.0.14","priority":80,"apps":$katalog}}""")
    preveriEnako("prijava v1 sprejeta", "accepted", polje(odgovor!!, "status"))
    val tv = Lazni("192.168.0.61")
    u.obdelaj(tv, registracija("tv-1", "receiver"))
    val seznam = tv.prejeto.map { JsonLahki.objekt(it) }.lastOrNull { it?.niz("type") == "cast.devices" }
    preveriEnako("tv dobi cast.devices", "cast.devices", seznam?.niz("type"))
    val naprave = seznam?.surovo("devices").orEmpty()
    preveri("v seznamu je model naprave v1", naprave.contains("\"platform\":\"linux\"") && naprave.contains("\"kind\":\"computer\"")
        && naprave.contains("\"version\":\"1.0.14\"") && naprave.contains("\"priority\":80") && naprave.contains("\"protocol\":\"1.0\""))
    preveri("v seznamu je katalog aplikacij", naprave.contains("\"apps\":{") && naprave.contains("\"firefox\":{\"name\":\"Firefox\"") && naprave.contains("\"icon\":\"data:x\""))
    preveri("naprava 0.2 nima polj v1", !naprave.substringAfter("\"id\":\"tv-1\"").contains("\"platform\""))
    // Naknadna objava kataloga.
    tv.pocisti()
    val objava = u.odgovorNa(pc, """{"id":"a1","type":"apps.announce","payload":{"apps":{"gimp":{"name":"GIMP","kind":"app"}}}}""")
    preveriEnako("apps.announce sprejet", "accepted", polje(objava!!, "status"))
    val novi = tv.prejeto.lastOrNull { it.contains("cast.devices") }.orEmpty()
    preveri("po objavi dobijo vsi nov seznam", novi.contains("\"gimp\":{\"name\":\"GIMP\"") && !novi.contains("firefox"))
    preveriEnako("ista objava drugic ne razposilja", "accepted", polje(u.odgovorNa(pc, """{"id":"a2","type":"apps.announce","payload":{"apps":{"gimp":{"name":"GIMP","kind":"app"}}}}""")!!, "status"))
    val neprijavljen = Lazni("192.168.0.62")
    preveriEnako("apps.announce brez prijave je zavrnjen", "rejected", polje(u.odgovorNa(neprijavljen, """{"id":"a3","type":"apps.announce","payload":{"apps":{"x":{"name":"X"}}}}""")!!, "status"))
    // Meje kataloga.
    preveriEnako("pokvarjen katalog odpade", "", u.preveriKatalog("[1,2]"))
    preveriEnako("prazen katalog odpade", "", u.preveriKatalog("{}"))
    val velik = (1..300).joinToString(",", "{", "}") { "\"a$it\":{\"name\":\"App $it\"}" }
    val ociscen = JsonLahki.objekt(u.preveriKatalog(velik))
    preveriEnako("katalog je omejen na NAJVEC_APLIKACIJ", HubUsmerjevalnik.NAJVEC_APLIKACIJ, ociscen?.kljuci()?.size)
    preveriEnako("predolg katalog odpade", "", u.preveriKatalog("x".repeat(HubUsmerjevalnik.NAJVEC_KATALOG_BAJTOV + 1)))
    preveri("vnos brez imena dobi id kot ime", u.preveriKatalog("""{"kodi":{}}""").contains("\"name\":\"kodi\""))
}

// ------------------------------------------------------------ prijava s QR kodo

private fun sha256(s: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun preizkusQrPrijave() {
    println("- prijava s QR kodo")
    val shramba = LazniPomnilnik()
    val u = usmerjevalnik(shramba)
    val telefon = u.zagotoviLastniZeton("fon-matej", "Telefon")
    val skrivnost = "qr-skrivnost-0123456789abcdef"
    val prevzem = "prevzem-samo-racunalnik-42"
    fun qr(pot: String, telo: String, glave: Map<String, String> = emptyMap(), od: String = "192.168.0.50") =
        u.odgovori(zahteva("POST", "/cast/pair/qr/$pot", telo, od, glave))
    val zTel = mapOf("x-safeer-token" to telefon)

    preveriEnako("z interneta je 403", 403,
        qr("start", """{"device_id":"n-pc","secret_sha256":"${sha256(skrivnost)}","poll_secret":"$prevzem"}""", od = "203.0.113.5")?.koda)
    preveriEnako("brez odtisa skrivnosti je 400", 400, qr("start", """{"device_id":"n-pc","poll_secret":"$prevzem"}""")?.koda)
    preveriEnako("prekratka skrivnost za prevzem je 400", 400,
        qr("start", """{"device_id":"n-pc","secret_sha256":"${sha256(skrivnost)}","poll_secret":"kratka"}""")?.koda)
    val start = qr("start", """{"device_id":"n-pc","name":"Računalnik","platform":"linux","secret_sha256":"${sha256(skrivnost)}","poll_secret":"$prevzem"}""")
    preveriEnako("prijava se odpre", 200, start?.koda)
    val qrId = polje(start?.telo.orEmpty(), "qr_id")
    preveri("qr_id in odtis huba sta v odgovoru", qrId.isNotEmpty() && polje(start?.telo.orEmpty(), "fp") == u.lastniOdtis)
    preveri("skrivnosti hub ne vrne", !start?.telo.orEmpty().contains(skrivnost) && !start?.telo.orEmpty().contains(prevzem))

    fun stanje(pr: String = prevzem, id: String = "n-pc") =
        qr("status", """{"qr_id":"$qrId","device_id":"$id","poll_secret":"$pr"}""")
    preveri("pred potrditvijo caka", stanje()?.koda == 200 && stanje()?.telo.orEmpty().contains("\"approved\":false"))
    preveriEnako("napacna skrivnost za prevzem = ni prijave", 404, stanje(pr = "ugibam-ugibam-ugibam")?.koda)
    preveriEnako("tuja naprava ne vidi prijave", 404, stanje(id = "n-tuj")?.koda)

    preveriEnako("podatke vidi samo seznanjena naprava", 401, qr("info", """{"qr_id":"$qrId","secret":"$skrivnost"}""")?.koda)
    preveriEnako("potrdi samo seznanjena naprava", 401, qr("approve", """{"qr_id":"$qrId","secret":"$skrivnost"}""")?.koda)
    preveriEnako("s tujim zetonom ne gre", 401,
        qr("approve", """{"qr_id":"$qrId","secret":"$skrivnost"}""", mapOf("x-safeer-token" to "saf_tv_ponarejen"))?.koda)
    val info = qr("info", """{"qr_id":"$qrId","secret":"$skrivnost"}""", zTel)
    preveri("telefon vidi ime in platformo", info?.koda == 200 && polje(info.telo, "name") == "Računalnik" && polje(info.telo, "platform") == "linux")
    preveriEnako("napacna skrivnost iz QR je 404", 404, qr("approve", """{"qr_id":"$qrId","secret":"napacna"}""", zTel)?.koda)
    preveri("racunalnik po zgresku se ni seznanjen", u.seznanjeneNaprave().none { it.deviceId == "n-pc" })

    val ok = qr("approve", """{"qr_id":"$qrId","secret":"$skrivnost"}""", zTel)
    preveri("telefon dovoli", ok?.koda == 200 && ok.telo.contains("\"approved\":true"))
    preveri("racunalnik je med seznanjenimi", u.seznanjeneNaprave().any { it.deviceId == "n-pc" })
    preveri("zeton je shranjen", shramba.vsebina.values.any { it.contains("n-pc") })
    preveriEnako("ponovna potrditev ne naredi drugega zetona", 1,
        run { qr("approve", """{"qr_id":"$qrId","secret":"$skrivnost"}""", zTel); u.seznanjeneNaprave().count { it.deviceId == "n-pc" } })
    val prevzeto = stanje()
    val zeton = polje(prevzeto?.telo.orEmpty(), "token")
    preveri("racunalnik prevzame zeton", prevzeto?.koda == 200 && zeton.startsWith("saf_tv_") && u.napravaZeZetona(zeton) == "n-pc")
    preveriEnako("zeton se prevzame samo enkrat", 404, stanje()?.koda)

    // Ugibanje skrivnosti iz QR: po NAJVEC_POSKUSOV prijava pade.
    val s2 = qr("start", """{"device_id":"n-pc2","secret_sha256":"${sha256(skrivnost)}","poll_secret":"$prevzem"}""")
    val qr2 = polje(s2?.telo.orEmpty(), "qr_id")
    var zadnja = 0
    repeat(HubUsmerjevalnik.NAJVEC_POSKUSOV) { zadnja = qr("info", """{"qr_id":"$qr2","secret":"ugib-$it"}""", zTel)?.koda ?: 0 }
    preveriEnako("po preveč poskusih je 429", 429, zadnja)
    preveriEnako("prava skrivnost po padcu ne gre vec", 404, qr("approve", """{"qr_id":"$qr2","secret":"$skrivnost"}""", zTel)?.koda)

    // Potek in preklic.
    val s3 = qr("start", """{"device_id":"n-pc3","secret_sha256":"${sha256(skrivnost)}","poll_secret":"$prevzem"}""")
    val qr3 = polje(s3?.telo.orEmpty(), "qr_id")
    cas += HubUsmerjevalnik.PIN_VELJA_MS + 1000
    preveriEnako("po poteku koda ne velja", 404, qr("approve", """{"qr_id":"$qr3","secret":"$skrivnost"}""", zTel)?.koda)
    val s4 = qr("start", """{"device_id":"n-pc4","secret_sha256":"${sha256(skrivnost)}","poll_secret":"$prevzem"}""")
    val qr4 = polje(s4?.telo.orEmpty(), "qr_id")
    preveri("tuj preklic ne velja", qr("cancel", """{"qr_id":"$qr4","device_id":"n-pc4","poll_secret":"ugibam-ugibam-ugibam"}""")?.telo.orEmpty().contains("\"cancelled\":false"))
    preveri("preklic naprave velja", qr("cancel", """{"qr_id":"$qr4","device_id":"n-pc4","poll_secret":"$prevzem"}""")?.telo.orEmpty().contains("\"cancelled\":true"))
    preveriEnako("po preklicu ni prijave", 404, qr("info", """{"qr_id":"$qr4","secret":"$skrivnost"}""", zTel)?.koda)
    val s5 = qr("start", """{"device_id":"fon-matej","secret_sha256":"${sha256(skrivnost)}","poll_secret":"$prevzem"}""")
    preveriEnako("naprava ne dovoli sama sebi", 409,
        qr("approve", """{"qr_id":"${polje(s5?.telo.orEmpty(), "qr_id")}","secret":"$skrivnost"}""", zTel)?.koda)
    preveriEnako("GET na pot QR je 405", 405, u.odgovori(zahteva("GET", "/cast/pair/qr/start"))?.koda)
    repeat(HubUsmerjevalnik.NAJVEC_CAKAJOCIH) {
        qr("start", """{"device_id":"n-poplava-$it","secret_sha256":"${sha256(skrivnost)}","poll_secret":"$prevzem"}""")
    }
    preveriEnako("čakajočih prijav je omejeno", 429,
        qr("start", """{"device_id":"n-poplava-x","secret_sha256":"${sha256(skrivnost)}","poll_secret":"$prevzem"}""")?.koda)
}

private fun preizkusPridruzitve() {
    println("- pridruzitev s QR kodo sredisca")
    val u = usmerjevalnik(LazniPomnilnik())
    var pridruzen = ""
    u.naPridruzitev = { id, _ -> pridruzen = id }
    val (id, skrivnost) = u.ustvariPridruzitev()
    fun pridruzi(telo: String, od: String = "192.168.0.50") = u.odgovori(zahteva("POST", "/cast/pair/qr/join", telo, od))
    preveriEnako("z interneta je 403", 403,
        pridruzi("""{"qr_id":"$id","secret":"$skrivnost","device_id":"fon-nov","name":"Telefon"}""", "203.0.113.5")?.koda)
    preveriEnako("brez device_id je 400", 400, pridruzi("""{"qr_id":"$id","secret":"$skrivnost"}""")?.koda)
    preveriEnako("napacna skrivnost je 404", 404, pridruzi("""{"qr_id":"$id","secret":"ugibam","device_id":"fon-nov"}""")?.koda)
    preveri("po zgresku se ni seznanjen", u.seznanjeneNaprave().none { it.deviceId == "fon-nov" })
    val ok = pridruzi("""{"qr_id":"$id","secret":"$skrivnost","device_id":"fon-nov","name":"Telefon"}""")
    val zeton = polje(ok?.telo.orEmpty(), "token")
    preveri("telefon dobi zeton", ok?.koda == 200 && zeton.startsWith("saf_tv_") && u.napravaZeZetona(zeton) == "fon-nov")
    preveriEnako("odtis sredisca je v odgovoru", u.lastniOdtis, polje(ok?.telo.orEmpty(), "fp"))
    preveriEnako("sredisce izve, kdo se je pridruzil", "fon-nov", pridruzen)
    preveriEnako("koda velja samo enkrat", 404,
        pridruzi("""{"qr_id":"$id","secret":"$skrivnost","device_id":"fon-drug","name":"Drug"}""")?.koda)
    val (id2, s2) = u.ustvariPridruzitev()
    var zadnja = 0
    repeat(HubUsmerjevalnik.NAJVEC_POSKUSOV) { zadnja = pridruzi("""{"qr_id":"$id2","secret":"ugib-$it","device_id":"fon-x"}""")?.koda ?: 0 }
    preveriEnako("ugibanje je omejeno", 429, zadnja)
    preveriEnako("po padcu prava skrivnost ne gre", 404, pridruzi("""{"qr_id":"$id2","secret":"$s2","device_id":"fon-x"}""")?.koda)
    val (id3, s3) = u.ustvariPridruzitev()
    cas += HubUsmerjevalnik.PIN_VELJA_MS + 1000
    preveriEnako("po poteku koda ne velja", 404, pridruzi("""{"qr_id":"$id3","secret":"$s3","device_id":"fon-y"}""")?.koda)
    val (id4, s4) = u.ustvariPridruzitev()
    u.prekliciPridruzitev(id4)
    preveriEnako("preklicana koda ne velja", 404, pridruzi("""{"qr_id":"$id4","secret":"$s4","device_id":"fon-z"}""")?.koda)
}

private fun preizkusVabilaInOdhoda() {
    println("- vabilo nove naprave in odhod naprave")
    val u = usmerjevalnik(LazniPomnilnik())
    u.lastniOdtis = "ABCDEF0123"
    val hub = parKljucev()
    u.vpisiLastniKljuc("tv-hub", "Dnevna soba", b64(hub.public.encoded), "tv")
    val pc = u.zagotoviLastniZeton("n-pc-control", "Racunalnik")
    fun post(pot: String, telo: String, zeton: String? = pc, od: String = "192.168.0.50") =
        u.odgovori(zahteva("POST", pot, telo, od, glave = if (zeton != null) mapOf("x-safeer-token" to zeton) else emptyMap()))

    preveriEnako("vabilo brez zetona je 401", 401, post("/cast/pair/qr/invite", "{}", null)?.koda)
    preveriEnako("vabilo z interneta je 403", 403, post("/cast/pair/qr/invite", "{}", od = "203.0.113.5")?.koda)
    val v = post("/cast/pair/qr/invite", "{}")
    preveriEnako("seznanjena naprava dobi vabilo", 200, v?.koda)
    val qr = polje(v?.telo.orEmpty(), "qr_id"); val sk = polje(v?.telo.orEmpty(), "secret")
    preveriEnako("vabilo nosi odtis sredisca", "ABCDEF0123", polje(v?.telo.orEmpty(), "fp"))
    preveri("vabilo caka", post("/cast/pair/qr/invite/status", """{"qr_id":"$qr"}""")?.telo.orEmpty().contains("\"pending\":true"))
    val j = post("/cast/pair/qr/join", """{"qr_id":"$qr","secret":"$sk","device_id":"fon-novi","name":"Novi telefon"}""", null)
    preveriEnako("telefon se pridruzi z vabilom", 200, j?.koda)
    val st = post("/cast/pair/qr/invite/status", """{"qr_id":"$qr"}""")?.telo.orEmpty()
    preveri("racunalnik izve, da se je pridruzil", st.contains("\"joined\":true") && polje(st, "name") == "Novi telefon")
    preveriEnako("vabilo velja enkrat", 404, post("/cast/pair/qr/join", """{"qr_id":"$qr","secret":"$sk","device_id":"fon-x"}""", null)?.koda)
    val v2 = post("/cast/pair/qr/invite", "{}")
    val qr2 = polje(v2?.telo.orEmpty(), "qr_id")
    post("/cast/pair/qr/invite/cancel", """{"qr_id":"$qr2"}""")
    preveriEnako("preklicano vabilo ne velja", 404,
        post("/cast/pair/qr/join", """{"qr_id":"$qr2","secret":"${polje(v2?.telo.orEmpty(), "secret")}","device_id":"fon-y"}""", null)?.koda)

    // Odhod: racunalnik z dvema id-jema (Control in brskalnik, isti kljuc) zapusti Link; telefon ostane.
    val kljucPc = parKljucev()
    preveriEnako("vpis racunalnika v krog", 200, post("/cast/trust/enroll", """{"pubkey":"${b64(kljucPc.public.encoded)}"}""")?.koda)
    val brsk = u.zagotoviLastniZeton("n-pc", "Brskalnik")
    post("/cast/trust/enroll", """{"pubkey":"${b64(kljucPc.public.encoded)}"}""", brsk)
    val zTel = polje(j?.telo.orEmpty(), "token")
    preveriEnako("odhod brez zetona je 401", 401, post("/cast/devices/leave", "{}", null)?.koda)
    val o = post("/cast/devices/leave", "{}")
    preveriEnako("odhod uspe", 200, o?.koda)
    preveri("odsla sta oba id-ja racunalnika (${o?.telo})", Regex("\"count\":2(\\.0)?[,}]").containsMatchIn(o?.telo.orEmpty()))
    preveri("zeton racunalnika ne velja vec", u.napravaZeZetona(pc) == null && u.napravaZeZetona(brsk) == null)
    preveri("racunalnik ni vec v krogu", u.krog.clan("n-pc-control") == null && u.krog.clan("n-pc") == null)
    preveri("telefon in sredisce ostaneta", u.napravaZeZetona(zTel) == "fon-novi" && u.krog.clan("tv-hub") != null)
    preveriEnako("izziv za odslo napravo je 401", 401,
        u.odgovori(zahteva("POST", "/cast/auth/challenge", """{"device_id":"n-pc-control"}"""))?.koda)
}

private fun preizkusDvojnePovezave() {
    println("- nova povezava iste naprave zamenja staro")
    val u = usmerjevalnik(LazniPomnilnik())
    val stara = Lazni("192.168.0.70")
    u.obdelaj(stara, registracija("pc-1", "sender"))
    val nova = Lazni("192.168.0.70")
    u.obdelaj(nova, registracija("pc-1", "sender"))
    preveri("stara povezava je zaprta", stara.zaprt)
    preveri("nova povezava je prijavljena", u.povezaniPrejemniki().contains("\"id\":\"pc-1\""))
    // Zaprtje stare (njen bralec to javi kasneje) ne sme izbrisati nove.
    u.odklopi(stara)
    preveri("po zaprtju stare naprava ostane prijavljena", u.povezaniPrejemniki().contains("\"id\":\"pc-1\""))
    // Sonda registracije: ukaz sami sebi - prijavljena naprava dobi isti_naprava, osirotela naprava_ni_povezana.
    nova.pocisti()
    u.obdelaj(nova, """{"id":"sonda-1","type":"control.command","target":"pc-1","payload":{"action":"status","params":{}}}""")
    preveriEnako("prijavljena naprava dobi isti_naprava", "isti_naprava", polje(nova.zadnje(), "error_code"))
    u.odklopi(nova)
    stara.pocisti()
    u.obdelaj(stara, """{"id":"sonda-2","type":"control.command","target":"pc-1","payload":{"action":"status","params":{}}}""")
    preveriEnako("osirotela povezava dobi naprava_ni_povezana", "naprava_ni_povezana", polje(stara.zadnje(), "error_code"))
}

// ------------------------------------------------------------ identiteta naprave = kljuc

private fun preizkusIdentitete() {
    println()
    println("Identiteta naprave (kljuc)")
    val u = usmerjevalnik()
    val pc = parKljucev()
    val k = b64(pc.public.encoded)
    val jedro = KrogZaupanja.idIzKljuca(k)
    u.krog.dodaj(KrogZaupanja.Clan("pc-x", k, "Safeer (x)", "linux", 1.0, "hub"))
    u.krog.dodaj(KrogZaupanja.Clan("pc-x-control", k, "Safeer Control (x)", "linux", 1.0, "pc-x"))
    preveriEnako("brskalnik ima napravo iz kljuca", jedro, u.napravaIzKljuca("pc-x"))
    preveriEnako("Control na istem racunalniku ima isto napravo", jedro, u.napravaIzKljuca("pc-x-control"))
    preveriEnako("nov id iz kljuca s pripono tudi", jedro, u.napravaIzKljuca("$jedro-control"))
    preveriEnako("naprava brez kljuca v krogu je nima", null, u.napravaIzKljuca("fon-stari"))
    val tel = parKljucev()
    u.krog.dodaj(KrogZaupanja.Clan("fon-1", b64(tel.public.encoded), "Telefon", "phone", 1.0, "hub"))
    preveri("drug kljuc je druga naprava", u.napravaIzKljuca("fon-1") != jedro)

    val a = Lazni(); u.obdelaj(a, registracija("pc-x", "sender"))
    val b = Lazni(); u.obdelaj(b, registracija("pc-x-control", "sender"))
    val c = Lazni(); u.obdelaj(c, registracija("fon-stari", "sender"))
    val seznam = u.povezaniPrejemniki()
    preveriEnako("oba sorodnika v seznamu nosita isto napravo", 2, seznam.split("\"device\":\"$jedro\"").size - 1)
    val stari = JsonLahki.objekt("{\"s\":$seznam}")
    preveri("naprava brez kljuca je v seznamu brez polja device", seznam.contains("\"id\":\"fon-stari\"") &&
        !Regex("\"id\":\"fon-stari\"[^}]*\"device\"").containsMatchIn(seznam) && stari != null)

    u.preimenuj("pc-x-control", "Matejev racunalnik")
    preveriEnako("vzdevek velja za celo napravo (brskalnik)", "Matejev racunalnik", u.imeNaprave("pc-x"))
    preveriEnako("vzdevek velja za nov id iz kljuca", "Matejev racunalnik", u.imeNaprave("$jedro-control"))
    preveri("seznam kaze vzdevek pri obeh", u.povezaniPrejemniki().split("\"name\":\"Matejev racunalnik\"").size - 1 == 2)
    preveriEnako("ime je v krogu (za vse hube)", "Matejev racunalnik", u.krog.clan("pc-x")?.ime)
    u.preimenuj("pc-x", "Delovni")
    preveriEnako("novo ime zamenja staro za vse id-je", "Delovni", u.imeNaprave("pc-x-control"))
    val drugHub = usmerjevalnik()
    drugHub.krog.zdruzi(u.krog.json())
    preveriEnako("drug hub (po menjavi huba) vidi isto ime", "Delovni", drugHub.imeNaprave("pc-x-control"))
    u.vpisiLastniKljuc("pc-x", "Safeer (x)", k, "linux")
    preveriEnako("ponovni vpis lastnega kljuca ne povozi imena", "Delovni", u.imeNaprave("pc-x"))
    u.preimenuj("pc-x", "")
    preveriEnako("prazno ime odstrani vzdevek naprave", "Naprava pc-x-control", u.imeNaprave("pc-x-control"))
    preveriEnako("telefon ni dobil vzdevka racunalnika", "Telefon", u.imeNaprave("fon-1").let { if (it == "fon-1") "Telefon" else it })

    // Vzdevek, dan staremu id-ju pred krogom, velja tudi za nov id iz kljuca (prej se je izgubil).
    val shramba = LazniPomnilnik()
    val u2 = usmerjevalnik(shramba)
    u2.preimenuj("tv-stari", "Dnevna soba")
    val tv = parKljucev()
    val k2 = b64(tv.public.encoded)
    u2.krog.dodaj(KrogZaupanja.Clan("tv-stari", k2, "TV", "tv", 1.0, "hub"))
    preveriEnako("stari vzdevek velja za nov id", "Dnevna soba", u2.imeNaprave(KrogZaupanja.idIzKljuca(k2)))
    val u3 = usmerjevalnik(shramba)
    preveriEnako("in prezivi ponovni zagon huba", "Dnevna soba", u3.imeNaprave(KrogZaupanja.idIzKljuca(k2)))
}

// ------------------------------------------------------------ dnevnik brez skrivnosti

private fun preizkusDnevnika() {
    println()
    println("SafeerLog")
    val zapisano = ArrayList<String>()
    val prej = SafeerLog.izhod
    SafeerLog.izhod = { zapisano.add(it) }
    SafeerLog.napaka("Preizkus", "zeton saf_seja_abcdef123456 in {\"token\":\"skrivno123\",\"ticket\":\"t-9\"}",
        IllegalStateException("http://192.168.0.5:8080/#j=q1&s=zelo-skrivno&f=AB"))
    SafeerLog.napaka("Preizkus", "podpis MEUCIQDk3x9yZb0Qf1a2b3c4d5e6f7g8h9i0jKLMNOPQRSTUVWXYZ==")
    SafeerLog.izhod = prej
    val vse = zapisano.joinToString("\n")
    preveriEnako("zapisa sta dva", 2, zapisano.size)
    preveri("sejni zeton ni v dnevniku", !vse.contains("abcdef123456"))
    preveri("token in ticket nista v dnevniku", !vse.contains("skrivno123") && !vse.contains("t-9"))
    preveri("skrivnost iz QR ni v dnevniku", !vse.contains("zelo-skrivno"))
    preveri("dolg podpis ni v dnevniku", !vse.contains("MEUCIQDk3x9"))
    preveri("vrsta napake in oznaka ostaneta", vse.contains("IllegalStateException") && vse.contains("SafeerLink/Preizkus"))
    SafeerLog.izhod = { throw RuntimeException("izhod odpove") }
    SafeerLog.napaka("Preizkus", "ne sme vreci")
    SafeerLog.izhod = prej
    preveri("dnevnik ne vrze, tudi ko izhod odpove", true)
}

private fun preizkusImenVKrogu() {
    println()
    println("Imena v krogu in trust.names")
    val u = usmerjevalnik()
    val tv = parKljucev()
    val k = b64(tv.public.encoded)
    u.krog.dodaj(KrogZaupanja.Clan("tv-1", k, "Safeer TV", "tv", 100.0, "hub"))
    u.preimenuj("tv-1", "Dnevna soba")
    preveriEnako("preimenovanje ne premakne casa vpisa", 100.0, u.krog.clan("tv-1")?.dodano)
    val star = KrogZaupanja()
    star.dodaj(KrogZaupanja.Clan("a-1", k, "Isto ime", "tv", 1.0, "hub"))
    preveri("enako ime brez casa imena se potrdi", star.preimenuj("a-1", "Isto ime") && (star.clan("a-1")?.imenovano ?: 0.0) > 0.0)
    preveri("enako ime s casom imena se ne ponovi", !star.preimenuj("a-1", "Isto ime"))
    preveri("preimenovanje ima svoj cas", (u.krog.clan("tv-1")?.imenovano ?: 0.0) > 0.0)

    // Ponovni vpis iste naprave (novejsi dodano) ohrani ime, ki ga je dal uporabnik.
    val tuj = KrogZaupanja()
    tuj.dodaj(KrogZaupanja.Clan("tv-1", k, "Safeer TV", "tv", 200.0, "hub"))
    u.krog.zdruzi(tuj.json())
    preveriEnako("novejsi vpis ohrani uporabnikovo ime", "Dnevna soba", u.krog.clan("tv-1")?.ime)

    // trust.names z naprave: hub vzame ime znanega clana z istim kljucem.
    val odjemalec = KrogZaupanja()
    odjemalec.zdruzi(u.krog.json())
    odjemalec.preimenuj("tv-1", "Spalnica", KrogZaupanja.zdaj() + 10)
    val n = Lazni(); u.obdelaj(n, registracija("fon-9", "sender"))
    n.pocisti()
    u.obdelaj(n, """{"id":"i1","type":"trust.names","payload":${odjemalec.json()}}""")
    preveriEnako("trust.names je sprejet", "accepted", polje(n.prejeto.firstOrNull { tip(it) == "trust.ack" } ?: "", "status"))
    preveriEnako("hub prevzame ime z naprave", "Spalnica", u.imeNaprave("tv-1"))
    preveri("hub razposlje nov krog", n.prejeto.any { tip(it) == "trust.update" })

    // Varnost: po tej poti ne pride nov clan, drug kljuc ali obujena naprava.
    val vsiljivec = parKljucev()
    val ponarejen = """{"v":1,"clani":{"tuj-1":{"kljuc":"${b64(vsiljivec.public.encoded)}","ime":"Tujec","platforma":"x","dodano":1.0,"dodal":"x","imenovano":${KrogZaupanja.zdaj() + 20}},""" +
        """"tv-1":{"kljuc":"${b64(vsiljivec.public.encoded)}","ime":"Ugrabljen","platforma":"tv","dodano":100.0,"dodal":"hub","imenovano":${KrogZaupanja.zdaj() + 30}}},"umiki":{}}"""
    u.obdelaj(n, """{"id":"i2","type":"trust.names","payload":$ponarejen}""")
    preveri("nov clan po trust.names ne vstopi", u.krog.clan("tuj-1") == null)
    preveriEnako("ime z drugim kljucem se ne prime", "Spalnica", u.imeNaprave("tv-1"))
    preveriEnako("kljuc clana ostane isti", k, u.krog.clan("tv-1")?.kljuc)
    u.krog.umakni("tv-1", "hub", KrogZaupanja.zdaj() + 40)
    odjemalec.preimenuj("tv-1", "Obujen", KrogZaupanja.zdaj() + 50)
    u.obdelaj(n, """{"id":"i3","type":"trust.names","payload":${odjemalec.json()}}""")
    preveri("umaknjena naprava se s preimenovanjem ne vrne", u.krog.clan("tv-1") == null)
    val neprijavljen = Lazni()
    u.obdelaj(neprijavljen, """{"id":"i4","type":"trust.names","payload":${odjemalec.json()}}""")
    preveriEnako("brez prijave trust.names ni sprejet", "rejected", polje(neprijavljen.zadnje(), "status"))
    val prihodnost = KrogZaupanja()
    prihodnost.dodaj(KrogZaupanja.Clan("fon-9", b64(parKljucev().public.encoded), "F", "phone", 1.0, "hub"))
    preveri("ime iz daljne prihodnosti se ne prime", !prihodnost.zdruziImena(
        """{"clani":{"fon-9":{"kljuc":"${prihodnost.clan("fon-9")!!.kljuc}","ime":"Z","imenovano":${KrogZaupanja.zdaj() + 10 * 86400}}}}"""))
}

private fun preizkusPredajeInGatewaya() {
    println("\n== handoff.request in internet gateway (RC1) ==")
    val u = usmerjevalnik()
    val tv = Lazni("192.168.0.20")
    val telefon = Lazni("192.168.0.30")
    u.odgovorNa(tv, registracija("tv1", "receiver"))
    u.odgovorNa(telefon, registracija("fon1", "sender", "[\"url\",\"internet.gateway\"]"))

    tv.pocisti()
    val predaja = u.odgovorNa(telefon, """{"id":"h1","type":"handoff.request","target":"tv1","sender":"ponarejen","payload":{"surface":"media","url":"https://safeer.si/v.mp4","title":"Film","position":42.5}}""")!!
    preveriEnako("predaja sprejeta", "accepted", polje(predaja, "status"))
    preveriEnako("zaslon dobi handoff.request", "handoff.request", tip(tv.zadnje()))
    preveri("hub vpise pravega posiljatelja", tv.zadnje().contains("\"sender\":\"fon1\"") && !tv.zadnje().contains("ponarejen"))
    preveri("tovor ostane (url, polozaj)", tv.zadnje().contains("https://safeer.si/v.mp4") && tv.zadnje().contains("42.5"))
    preveriEnako("predaja neznani napravi zavrnjena", "rejected",
        polje(u.odgovorNa(telefon, """{"id":"h2","type":"handoff.request","target":"nihce","payload":{"url":"https://x.si"}}""")!!, "status"))
    preveriEnako("predaja sebi zavrnjena", "rejected",
        polje(u.odgovorNa(telefon, """{"id":"h3","type":"handoff.request","target":"fon1","payload":{"url":"https://x.si"}}""")!!, "status"))
    preveriEnako("predaja brez tovora zavrnjena", "rejected",
        polje(u.odgovorNa(telefon, """{"id":"h4","type":"handoff.request","target":"tv1"}""")!!, "status"))

    telefon.pocisti()
    u.odgovorNa(tv, """{"id":"g1","type":"internet.open","target":"fon1","sender":"ponarejen","stream_id":"tok-12345678","host":"safeer.si","port":443}""")
    preveriEnako("gateway dobi internet.open", "internet.open", tip(telefon.zadnje()))
    preveri("internet.open: posiljatelja vpise hub", telefon.zadnje().contains("\"sender\":\"tv1\"") && !telefon.zadnje().contains("ponarejen"))
    preveri("internet.open: vrata ostanejo", telefon.zadnje().contains("\"port\":443"))
}

fun main() {
    println("Preizkus bralca JSON in usmerjevalnika Safeer Huba")
    preizkusJson()
    preizkusRegistra()
    preizkusDaljinca()
    preizkusSinhronizacije()
    preizkusSeznanjanja()
    preizkusPreklica()
    preizkusSorodnika()
    preizkusVstopnic()
    preizkusHttp()
    preizkusDeljenjaPoHttp()
    preizkusMeja()
    preizkusKroga()
    preizkusProtokolaV1()
    preizkusIdaIzKljuca()
    preizkusPodpisanegaKroga()
    preizkusQrPrijave()
    preizkusPridruzitve()
    preizkusVabilaInOdhoda()
    preizkusDvojnePovezave()
    preizkusIdentitete()
    preizkusDnevnika()
    preizkusImenVKrogu()
    preizkusPredajeInGatewaya()
    println()
    if (napak == 0) {
        println("Vse v redu.")
    } else {
        println("Napak: $napak")
        System.exit(1)
    }
}
