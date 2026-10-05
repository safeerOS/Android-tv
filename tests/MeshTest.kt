package si.safeer.tv.cast

// Link Mesh (docs/LINK-MESH.md) v navadnem JVM: trije pravi usmerjevalniki, sosednja povezava je
// lazna cev, ki sporocilo takoj preda drugemu Hubu. Isti primeri kot tests/test_link_mesh.py na Linuxu.

private var napakMesh = 0

private fun preveriM(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napakMesh++ }
}

private class Naprava(override val naslov: String = "192.168.0.50") : HubUsmerjevalnik.Odjemalec {
    val prejeto = ArrayList<String>()
    override fun poslji(besedilo: String) { prejeto.add(besedilo) }
    override fun zapri(koda: Int, razlog: String) {}
    fun vrste(tip: String) = prejeto.filter { JsonLahki.objekt(it)?.niz("type") == tip }
    fun zadnje(tip: String) = vrste(tip).lastOrNull()
    fun idji(): List<String> {
        val s = zadnje("cast.devices") ?: return emptyList()
        val naprave = s.substringAfter("\"devices\":")
        return Regex("\"id\":\"([^\"]+)\"").findAll(naprave).map { it.groupValues[1] }.toList().sorted()
    }
}

/** Ena stran sosednje povezave: kar poslje ta stran, obdela Hub na drugi strani. */
private class Cev(val cilj: HubUsmerjevalnik, override val naslov: String = "192.168.0.9") : HubUsmerjevalnik.Odjemalec {
    lateinit var druga: Cev
    val poslano = ArrayList<String>()
    private val vrsta = ArrayList<String>()
    var zadrzi = true
    var zaprta = false
    override fun poslji(besedilo: String) {
        if (zaprta) return
        poslano.add(besedilo)
        if (zadrzi) vrsta.add(besedilo) else cilj.obdelaj(druga, besedilo)
    }
    override fun zapri(koda: Int, razlog: String) { zaprta = true }
    fun spusti() { zadrzi = false; val v = vrsta.toList(); vrsta.clear(); v.forEach { cilj.obdelaj(druga, it) } }
}

private val kljuci = HashMap<String, String>()
private fun kljuc(id: String): String = kljuci.getOrPut(id) {
    val par = java.security.KeyPairGenerator.getInstance("EC").apply {
        initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    java.util.Base64.getEncoder().encodeToString(par.public.encoded)
}

private val CLANI = listOf("hub-a", "hub-b", "hub-c", "hub-d", "tv", "pc", "tel", "tablica")

private fun hub(id: String): HubUsmerjevalnik {
    val u = HubUsmerjevalnik(null)
    u.lastniId = id
    for (c in CLANI) u.krog.dodaj(KrogZaupanja.Clan(c, kljuc(c), c, "tv", 1.0, "hub-a"))
    return u
}

/** [aVidiB] je naslov, ki si ga a zapomni za b: IP dohodne ali wss://... odhodne sosednje povezave. */
private fun povezi(a: HubUsmerjevalnik, b: HubUsmerjevalnik, zacel: String = a.lastniId,
                   aVidiB: String = "192.168.0.9", bVidiA: String = "192.168.0.9"): Pair<Cev, Cev> {
    val ab = Cev(b, aVidiB)
    val ba = Cev(a, bVidiA)
    ab.druga = ba
    ba.druga = ab
    a.dodajSoseda(b.lastniId, ab, zacel)
    b.dodajSoseda(a.lastniId, ba, zacel)
    ab.spusti(); ba.spusti()
    return ab to ba
}

/** Naslov naprave [id], kot ga je program nazadnje dobil v cast.devices (null = naprave ni v seznamu). */
private fun Naprava.naslovOd(id: String): String? {
    val s = zadnje("cast.devices") ?: return null
    return Regex("\\{\"id\":\"" + Regex.escape(id) + "\".*?\"ip\":\"([^\"]*)\"").find(s)?.groupValues?.get(1)
}

/** Naslov naprave [id] v zadnjem seznamu mesh.devices, ki je sel po tej sosednji povezavi. */
private fun Cev.izvozenNaslov(id: String): String? {
    val s = poslano.lastOrNull { JsonLahki.objekt(it)?.niz("type") == "mesh.devices" } ?: return null
    return JsonLahki.objekt(s)?.objekt("payload")?.objekt("devices")?.objekt(id)?.niz("ip")
}

private fun prijava(u: HubUsmerjevalnik, id: String, naslov: String = "192.168.0.50"): Naprava {
    val n = Naprava(naslov)
    u.obdelaj(n, """{"id":"r","type":"cast.register","payload":{"device_id":"$id","name":"$id","role":"receiver","capabilities":["url","remote","text","chat"]}}""")
    return n
}

private fun primer(ime: String, f: () -> Unit) { println(ime); f() }

fun main() {
    println("Preizkus Link Mesh (mesh1)")
    primer("vsaka naprava vidi vsako, sosed ni naprava") {
        val a = hub("hub-a"); val b = hub("hub-b")
        val tv = prijava(a, "tv"); val pc = prijava(b, "pc")
        povezi(a, b)
        preveriM("tv vidi pc", tv.idji() == listOf("pc", "tv"))
        preveriM("pc vidi tv", pc.idji() == listOf("pc", "tv"))
        preveriM("sosed ni v seznamu", "hub-b" !in tv.idji())
    }
    primer("usmerjanje s ciljem in odgovor nazaj") {
        val a = hub("hub-a"); val b = hub("hub-b")
        val tv = prijava(a, "tv"); val pc = prijava(b, "pc")
        povezi(a, b)
        b.obdelaj(pc, """{"id":"u1","type":"control.command","target":"tv","payload":{"action":"pause"}}""")
        val ukaz = tv.zadnje("control.command")
        preveriM("ukaz prispe", ukaz != null)
        preveriM("posiljatelj je pc", JsonLahki.objekt(ukaz ?: "{}")?.niz("sender") == "pc")
        preveriM("pc dobi accepted", pc.zadnje("control.ack")?.contains("accepted") == true)
        a.obdelaj(tv, """{"id":"u2","type":"control.result","target":"pc","ref_id":"u1"}""")
        preveriM("odgovor prispe nazaj", pc.zadnje("control.result") != null)
    }
    primer("oddaja natanko enkrat v polni mrezi") {
        val a = hub("hub-a"); val b = hub("hub-b"); val c = hub("hub-c")
        val tv = prijava(a, "tv"); val pc = prijava(b, "pc"); val tel = prijava(c, "tel")
        povezi(a, b); povezi(b, c); povezi(a, c)
        preveriM("tv vidi vse tri", tv.idji() == listOf("pc", "tel", "tv"))
        a.obdelaj(tv, """{"id":"o1","type":"cast.status","device_id":"tv","payload":{}}""")
        a.obdelaj(tv, """{"id":"k1","type":"share.text","target":"tel","payload":{"text":"zivjo"}}""")
        preveriM("tel dobi deljenje enkrat", tel.vrste("share.text").size == 1)
        preveriM("pc ga ne dobi", pc.vrste("share.text").isEmpty())
    }
    primer("izpad soseda podre samo njegove naprave") {
        val a = hub("hub-a"); val b = hub("hub-b"); val c = hub("hub-c")
        val tv = prijava(a, "tv"); prijava(b, "pc"); val tel = prijava(c, "tel")
        val (ab, _) = povezi(a, b); povezi(a, c); povezi(b, c)
        a.odklopi(ab)
        preveriM("tv vidi pc prek c (en vmesni skok)", tv.idji() == listOf("pc", "tel", "tv"))
        preveriM("tel se vedno vidi vse", tel.idji() == listOf("pc", "tel", "tv"))
        preveriM("a ima samo soseda c", a.sosedjeIdji() == listOf("hub-c"))
    }
    primer("lokalna prijava ima prednost") {
        val a = hub("hub-a"); val b = hub("hub-b")
        prijava(b, "tel")
        povezi(a, b)
        val telLokalno = prijava(a, "tel")
        val tv = prijava(a, "tv")
        a.obdelaj(tv, """{"id":"x","type":"share.text","target":"tel","payload":{"text":"a"}}""")
        preveriM("deljenje gre lokalni povezavi", telLokalno.vrste("share.text").size == 1)
    }
    primer("mesh.* od navadne naprave in route naprej zavrnjena") {
        val a = hub("hub-a"); val b = hub("hub-b"); val c = hub("hub-c")
        val tv = prijava(a, "tv"); prijava(c, "tel")
        val odg = a.odgovorNa(tv, """{"id":"m","type":"mesh.route","payload":{"to":"tv","msg":"{}"}}""").orEmpty()
        preveriM("naprava ne sme poslati mesh.*", odg.contains("ni_sosed"))
        val (ab, _) = povezi(a, b); povezi(a, c)
        val pred = (a.javi("hub-c") as? Cev)?.poslano?.size ?: 0
        a.obdelaj(ab, """{"type":"mesh.route","payload":{"to":"tel","msg":"{\"type\":\"share.text\",\"sender\":\"hub-b\"}"}}""")
        preveriM("route na nelokalno napravo se ne posreduje naprej", ((a.javi("hub-c") as? Cev)?.poslano?.size ?: 0) == pred)
    }
    primer("sosed ne more ponarediti posiljatelja") {
        val a = hub("hub-a"); val b = hub("hub-b")
        val tv = prijava(a, "tv"); prijava(b, "pc")
        val (ab, _) = povezi(a, b)
        a.obdelaj(ab, """{"type":"mesh.route","payload":{"to":"tv","msg":"{\"type\":\"control.command\",\"sender\":\"tv-drugje\"}"}}""")
        preveriM("tuj posiljatelj zavrzen", tv.zadnje("control.command") == null)
        a.obdelaj(ab, """{"type":"mesh.route","payload":{"to":"tv","msg":"{\"type\":\"control.command\"}"}}""")
        preveriM("brez posiljatelja zavrzen", tv.zadnje("control.command") == null)
        a.obdelaj(ab, """{"type":"mesh.route","payload":{"to":"tv","msg":"{\"type\":\"control.command\",\"sender\":\"pc\"}"}}""")
        preveriM("njegova naprava sprejeta", tv.zadnje("control.command") != null)
    }
    primer("sosed samo s podpisano vstopnico clana") {
        val a = hub("hub-a")
        val v1 = a.izdajVstopnico("hub-x")
        a.porabiVstopnico(v1)
        val n1 = Naprava()
        var zaprta = false
        val brezPodpisa = object : HubUsmerjevalnik.Odjemalec by n1 {
            override val vstopnica = v1
            override fun zapri(koda: Int, razlog: String) { zaprta = true }
        }
        val o1 = a.odgovorNa(brezPodpisa, """{"id":"h","type":"cast.register","payload":{"device_id":"hub-b","role":"hub","capabilities":["mesh1"]}}""")
        preveriM("brez podpisa zavrnjen", o1 == null && n1.zadnje("cast.ack")?.contains("rejected") == true)
        preveriM("zavrnjena povezava se zapre", zaprta)
        val v2 = a.izdajVstopnico("hub-b", podpis = true)
        a.porabiVstopnico(v2)
        val sPodpisom = Naprava().let { object : HubUsmerjevalnik.Odjemalec by it { override val vstopnica = v2 } }
        val o2 = a.odgovorNa(sPodpisom, """{"id":"h","type":"cast.register","payload":{"device_id":"hub-b","role":"hub","capabilities":["mesh1"]}}""").orEmpty()
        preveriM("s podpisom clana sprejet", o2.contains("accepted") && a.sosedjeIdji() == listOf("hub-b"))
    }
    primer("podvojena povezava ostane ena") {
        val a = hub("hub-a"); val b = hub("hub-b")
        val tv = prijava(a, "tv"); prijava(b, "pc")
        povezi(a, b, zacel = "hub-a")
        val prva = a.javi("hub-b")
        povezi(b, a, zacel = "hub-b")
        preveriM("ostane povezava, ki jo je odprl manjsi id", a.javi("hub-b") === prva)
        preveriM("seznam ostane pravilen", tv.idji() == listOf("pc", "tv"))
    }
    primer("vecji id poklice prvi (po zagonu), nato se manjsi: ostane povezava manjsega") {
        val a = hub("hub-a"); val b = hub("hub-b")
        val tv = prijava(a, "tv"); val pc = prijava(b, "pc")
        povezi(b, a, zacel = "hub-b")
        preveriM("povezava vecjega velja, dokler ni druge", tv.idji() == listOf("pc", "tv"))
        val prvaA = a.javi("hub-b"); val prvaB = b.javi("hub-a")
        val (ab, ba) = povezi(a, b, zacel = "hub-a")
        preveriM("na obeh straneh obvelja povezava manjsega id", a.javi("hub-b") === ab && b.javi("hub-a") === ba
            && a.javi("hub-b") !== prvaA && b.javi("hub-a") !== prvaB)
        preveriM("naprave ostanejo v obeh seznamih", tv.idji() == listOf("pc", "tv") && pc.idji() == listOf("pc", "tv"))
    }
    primer("klepet pocaka in pride prek soseda") {
        val a = hub("hub-a"); val b = hub("hub-b"); val c = hub("hub-c")
        val tv = prijava(a, "tv"); prijava(b, "tel")
        val (ab, _) = povezi(a, b)
        a.odklopi(ab)
        a.obdelaj(tv, """{"id":"k","type":"chat.send","target":"tel","payload":{"text":"pridi"}}""")
        preveriM("klepet caka", tv.zadnje("chat.ack")?.contains("queued") == true)
        val tel2 = prijava(c, "tel")
        povezi(a, c)
        preveriM("klepet prispe prek c", tel2.vrste("chat.send").size == 1)
    }
    primer("seznam sosedu samo ob spremembi") {
        val a = hub("hub-a"); val b = hub("hub-b")
        prijava(a, "tv")
        val (ab, _) = povezi(a, b)
        fun st() = ab.poslano.count { JsonLahki.objekt(it)?.niz("type") == "mesh.devices" }
        prijava(b, "pc")                        // sprememba pri sosedu spremeni nas relay zemljevid
        val pred = st()
        a.obdelaj(Naprava(), """{"id":"p","type":"cast.ping"}""")
        preveriM("brez spremembe ni novega seznama", st() == pred)
        prijava(a, "tablica")
        preveriM("nova lokalna naprava poslje seznam", st() == pred + 1)
    }
    primer("relay: a in c se ne dosezeta, poveze ju b") {
        val a = hub("hub-a"); val b = hub("hub-b"); val c = hub("hub-c")
        val tv = prijava(a, "tv"); prijava(b, "pc"); val tel = prijava(c, "tel")
        povezi(a, b); povezi(b, c)
        preveriM("tv vidi tel", tv.idji() == listOf("pc", "tel", "tv"))
        preveriM("tel vidi tv", tel.idji() == listOf("pc", "tel", "tv"))
        a.obdelaj(tv, """{"id":"r1","type":"control.command","target":"tel","payload":{}}""")
        val ukaz = tel.zadnje("control.command")
        preveriM("ukaz prispe prek b", ukaz != null && JsonLahki.objekt(ukaz)?.niz("sender") == "tv")
        c.obdelaj(tel, """{"id":"r2","type":"control.result","target":"tv","ref_id":"r1"}""")
        preveriM("odgovor nazaj prek b", tv.zadnje("control.result") != null)
    }
    primer("relay: neposredna pot ima prednost, izpad preklopi na vmesno") {
        val a = hub("hub-a"); val b = hub("hub-b"); val c = hub("hub-c")
        val tv = prijava(a, "tv"); prijava(b, "pc"); val tel = prijava(c, "tel")
        val (ab, _) = povezi(a, b); povezi(b, c); val (ac, _) = povezi(a, c)
        fun prekB() = ab.poslano.count { JsonLahki.objekt(it)?.niz("type") == "mesh.route" }
        val pred = prekB()
        a.obdelaj(tv, """{"id":"d","type":"share.text","target":"tel","payload":{"text":"x"}}""")
        preveriM("neposredno, ne prek b", tel.vrste("share.text").size == 1 && prekB() == pred)
        a.odklopi(ac)
        a.obdelaj(tv, """{"id":"e","type":"share.text","target":"tel","payload":{"text":"y"}}""")
        preveriM("po izpadu prek b", tel.vrste("share.text").size == 2 && prekB() == pred + 1)
    }
    primer("relay: najvec en skok in brez ponarejanja") {
        val a = hub("hub-a"); val b = hub("hub-b"); val c = hub("hub-c"); val d = hub("hub-d")
        prijava(a, "tv"); prijava(b, "pc"); val tel = prijava(c, "tel"); val tab = prijava(d, "tablica")
        povezi(a, b); povezi(b, c); povezi(c, d)
        val izA = b.javi("hub-a")!!
        b.obdelaj(izA, """{"type":"mesh.route","payload":{"to":"tel","relay":true,"msg":"{\"type\":\"share.text\",\"sender\":\"pc\"}"}}""")
        preveriM("tuj posiljatelj pri relay zavrzen", tel.vrste("share.text").isEmpty())
        b.obdelaj(izA, """{"type":"mesh.route","payload":{"to":"tablica","relay":true,"msg":"{\"type\":\"share.text\",\"sender\":\"tv\"}"}}""")
        preveriM("drugi skok zavrnjen", tab.vrste("share.text").isEmpty())
    }
    // ---- naslov naprave cez mejo Huba (5. 10. 2026: telefon se je za zaslon racunalnika povezal sam nase)
    val PC = "192.168.0.135"; val TV = "192.168.0.77"
    fun hubN(id: String, nas: (String) -> String = { "" }) = hub(id).also { it.nasNaslovProti = nas }
    primer("naslov: sosedov program dobi naslov sosedove naprave, svoj ostane na zanki") {
        val a = hubN("hub-a"); val b = hubN("hub-b")
        val pc = prijava(a, "pc", "127.0.0.1"); val tv = prijava(b, "tv", "127.0.0.1")
        povezi(a, b, aVidiB = "wss://$TV:8765/cast/ws", bVidiA = PC)
        preveriM("tv vidi pc na naslovu racunalnika", tv.naslovOd("pc") == PC)
        preveriM("pc vidi tv na naslovu televizorja", pc.naslovOd("tv") == TV)
        preveriM("svoj program ostane 127.0.0.1", pc.naslovOd("pc") == "127.0.0.1" && tv.naslovOd("tv") == "127.0.0.1")
    }
    primer("naslov: starejsi sosed poslje zanko, prevedemo jo pri sebi") {
        val a = hubN("hub-a"); val b = hubN("hub-b")
        val pc = prijava(a, "pc", "127.0.0.1")
        val (ab, _) = povezi(a, b, aVidiB = TV, bVidiA = PC)
        a.obdelaj(ab, """{"id":"m","type":"mesh.devices","payload":{"hub":"hub-b","relay":{},"devices":{"tv":{"name":"TV","role":"receiver","capabilities":[],"ip":"127.0.0.1"},"tel":{"name":"T","role":"receiver","capabilities":[],"ip":"::1"},"tablica":{"name":"X","role":"receiver","capabilities":[]}}}}""")
        preveriM("127.0.0.1 -> naslov soseda", pc.naslovOd("tv") == TV)
        preveriM("::1 -> naslov soseda", pc.naslovOd("tel") == TV)
        preveriM("brez naslova -> naslov soseda", pc.naslovOd("tablica") == TV)
    }
    primer("naslov: pravi naslov ostane (naprava ni na sosedovi napravi)") {
        val a = hubN("hub-a"); val b = hubN("hub-b")
        val pc = prijava(a, "pc", "127.0.0.1"); prijava(b, "tablica", "192.168.0.87")
        val (_, ba) = povezi(a, b, aVidiB = TV, bVidiA = PC)
        preveriM("uvoz ga ne spremeni", pc.naslovOd("tablica") == "192.168.0.87")
        preveriM("izvoz ga ne spremeni", ba.izvozenNaslov("tablica") == "192.168.0.87")
    }
    primer("naslov: prek releja naslova ni") {
        val a = hubN("hub-a") { PC }; val b = hubN("hub-b") { TV }
        val pc = prijava(a, "pc", "127.0.0.1"); val tv = prijava(b, "tv", "127.0.0.1")
        val (ab, ba) = povezi(a, b, aVidiB = "wss://127.0.0.1:45555/cast/ws", bVidiA = "127.0.0.1")
        preveriM("uvoz: prazen, ne 127.0.0.1", pc.naslovOd("tv") == "" && tv.naslovOd("pc") == "")
        preveriM("izvoz: prazen, ne 127.0.0.1", ab.izvozenNaslov("pc") == "" && ba.izvozenNaslov("tv") == "")
    }
    primer("naslov: izvoz nosi nas naslov na poti do soseda (dovolj je ena posodobljena naprava)") {
        val a = hubN("hub-a") { sosed -> if (sosed == TV) PC else "10.0.0.2" }
        val b = hubN("hub-b"); val c = hubN("hub-c")
        prijava(a, "pc", "127.0.0.1")
        val (ab, _) = povezi(a, b, aVidiB = "wss://$TV:8765/cast/ws", bVidiA = PC)
        val (ac, _) = povezi(a, c, aVidiB = "10.0.0.7", bVidiA = "10.0.0.2")
        preveriM("sosed v domacem omrezju dobi domaci naslov", ab.izvozenNaslov("pc") == PC)
        preveriM("sosed v drugem omrezju dobi naslov tiste poti", ac.izvozenNaslov("pc") == "10.0.0.2")
        val prej = ab.poslano.count { JsonLahki.objekt(it)?.niz("type") == "mesh.devices" }
        prijava(a, "pc", "127.0.0.1")                    // ista naprava se prijavi znova: seznam je enak
        preveriM("seznam se vedno samo ob spremembi", ab.poslano.count { JsonLahki.objekt(it)?.niz("type") == "mesh.devices" } == prej)
    }
    primer("naslov: neznan nas naslov ne poslje zanke") {
        val a = hubN("hub-a"); val b = hubN("hub-b")
        prijava(a, "pc", "127.0.0.1")
        val (ab, _) = povezi(a, b, aVidiB = TV, bVidiA = PC)
        preveriM("prazen, ne 127.0.0.1", ab.izvozenNaslov("pc") == "")
    }
    primer("naslov: dva skoka - prevede vmesni Hub; zanka starejsega vmesnega Huba ni naslov") {
        val a = hubN("hub-a"); val b = hubN("hub-b"); val c = hubN("hub-c")
        val pc = prijava(a, "pc", "127.0.0.1"); prijava(c, "tel", "127.0.0.1")
        povezi(b, c, aVidiB = "192.168.0.30", bVidiA = TV)
        val (ab, _) = povezi(a, b, aVidiB = TV, bVidiA = PC)
        preveriM("tel pride z naslovom svoje naprave", pc.naslovOd("tel") == "192.168.0.30")
        a.obdelaj(ab, """{"id":"m","type":"mesh.devices","payload":{"hub":"hub-b","devices":{},"relay":{"tablica":{"name":"X","role":"receiver","capabilities":[],"ip":"127.0.0.1","hub":"hub-c"}}}}""")
        preveriM("zanka prek dveh skokov -> prazen naslov", pc.naslovOd("tablica") == "")
    }
    primer("naslov: odjemalec od drugod dobi naslov naprave sredisca, odjemalcu s te naprave zanka ostane") {
        val a = hubN("hub-a") { odjemalec -> if (odjemalec == "192.168.0.143") PC else "10.0.0.2" }
        val pc = prijava(a, "pc", "127.0.0.1")
        val tel = prijava(a, "tel", "192.168.0.143")
        val tab = prijava(a, "tablica", "10.0.0.7")
        fun Naprava.tukaj(id: String) = Regex("\\{\"id\":\"" + id + "\"[^{}]*\\}").find(zadnje("cast.devices").orEmpty())?.value?.contains("\"here\":true") == true
        preveriM("telefon dobi naslov racunalnika", tel.naslovOd("pc") == PC && tel.tukaj("pc"))
        preveriM("vsak odjemalec naslov svoje poti", tab.naslovOd("pc") == "10.0.0.2")
        preveriM("svoj naslov vidi nespremenjen in brez oznake", tel.naslovOd("tel") == "192.168.0.143" && !tel.tukaj("tel"))
        preveriM("odjemalcu s te naprave zanka ostane, z oznako", pc.naslovOd("pc") == "127.0.0.1" && pc.tukaj("pc"))
        val b = hubN("hub-b"); prijava(b, "tv", "127.0.0.1")
        povezi(a, b, aVidiB = TV, bVidiA = PC)
        preveriM("sosedova naprava ni tukaj", tel.naslovOd("tv") == TV && !tel.tukaj("tv"))
    }
    primer("naslov: neznan nas naslov proti odjemalcu ne poslje zanke") {
        val a = hubN("hub-a")
        prijava(a, "pc", "127.0.0.1")
        val tel = prijava(a, "tel", "192.168.0.143")
        preveriM("prazen, ne 127.0.0.1", tel.naslovOd("pc") == "")
    }
    primer("naslov: odjemalceva stran (dela tudi s starejsim srediscem)") {
        val hub = "wss://192.168.0.87:8990/cast/ws"
        preveriM("zanka od sredisca drugje = naslov tistega sredisca", HubNaslovi.zaOdjemalca("127.0.0.1", hub, false, false) == "192.168.0.87")
        preveriM("zanka od svojega sredisca ostane", HubNaslovi.zaOdjemalca("127.0.0.1", "wss://127.0.0.1:45735/cast/ws", true, false) == "127.0.0.1")
        preveriM("pravi naslov ostane", HubNaslovi.zaOdjemalca("192.168.0.220", hub, false, false) == "192.168.0.220")
        preveriM("prazen ostane prazen", HubNaslovi.zaOdjemalca("", hub, false, false) == "")
        preveriM("prek Global Linka neposredne poti ni", HubNaslovi.zaOdjemalca("192.168.0.220", hub, false, true) == "" && HubNaslovi.zaOdjemalca("127.0.0.1", hub, false, true) == "")
        preveriM("zanka brez znanega naslova sredisca ni naslov", HubNaslovi.zaOdjemalca("127.0.0.1", "", false, false) == "")
    }
    primer("naslov: pomozne funkcije") {
        preveriM("gostitelj iz wss", HubNaslovi.gostitelj("wss://192.168.0.77:8765/cast/ws") == "192.168.0.77")
        preveriM("IPv4 v zapisu IPv6", HubNaslovi.gostitelj("::ffff:192.168.0.77") == "192.168.0.77")
        preveriM("obmocje vmesnika odrezano", HubNaslovi.gostitelj("fe80::1%wlan0") == "fe80::1")
        preveriM("samo tukaj", listOf("", "127.0.0.1", "127.0.1.1", "::1", "localhost", "wss://127.0.0.1:4/cast/ws", "fe80::1%wlan0", "::ffff:127.0.0.1").all { HubNaslovi.samoTukaj(it) })
        preveriM("velja povsod", listOf("192.168.0.77", "10.0.0.2", "fd00::7", "wss://192.168.0.77:8765/cast/ws").none { HubNaslovi.samoTukaj(it) })
        preveriM("ime namesto IP: brez poizvedbe DNS", HubNaslovi.nasProti("safeer-tv.local") == "")
        preveriM("pot do zanke ni nas naslov", HubNaslovi.nasProti("127.0.0.1") == "")
    }
    primer("naslov: kandidati za neposredno povezavo (pravilo 8)") {
        val k = HubNaslovi::kandidati
        preveriM("najprej iz seznama, nato nasteti", k("192.168.0.220", listOf("192.168.0.220", "10.0.0.7")) == listOf("192.168.0.220", "10.0.0.7"))
        preveriM("brez nastetih samo naslov iz seznama", k("192.168.0.220", null) == listOf("192.168.0.220") && k("192.168.0.220", emptyList()) == listOf("192.168.0.220"))
        preveriM("brez naslova iz seznama veljajo nasteti", k("", listOf("192.168.0.135")) == listOf("192.168.0.135") && k("  ", listOf("  ")).isEmpty() && k("", null).isEmpty())
        val slabi = listOf("127.0.0.1", "127.8.9.1", "0.0.0.0", "0.1.2.3", "224.0.0.251", "240.0.0.1", "255.255.255.255",
            "::", "::1", "ff02::1", "fe80::1", "2001:db8::5", "::ffff:192.168.0.6", "racunalnik.local", "192.168.0.300",
            "192.168.01.5", "1.2.3", "1.2.3.4.5", "192.168.0.-5", "\u0661\u0669\u0662.168.0.5", "192.168.0.5:8080",
            "http://192.168.0.5", "", "192.168.0.5 ")
        preveriM("nasteti morajo biti naslovi IPv4, dosegljivi od drugod", k("192.168.0.220", slabi) == listOf("192.168.0.220", "192.168.0.5"))
        preveriM("naslov brez DHCP in naslov ponudnika veljata", k("", listOf("169.254.10.20", "100.64.0.7", "223.255.255.254")) == listOf("169.254.10.20", "100.64.0.7", "223.255.255.254"))
        val veliko = (1..19).map { "10.0.0.$it" }
        preveriM("najvec stirje", k("192.168.0.220", veliko) == listOf("192.168.0.220", "10.0.0.1", "10.0.0.2", "10.0.0.3") && k("", veliko).size == HubNaslovi.NAJVEC_KANDIDATOV)
    }
    println()
    if (napakMesh == 0) println("Vse v redu.") else { println("Napak: $napakMesh"); kotlin.system.exitProcess(1) }
}
