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
private class Cev(val cilj: HubUsmerjevalnik) : HubUsmerjevalnik.Odjemalec {
    lateinit var druga: Cev
    override val naslov = "192.168.0.9"
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

private fun povezi(a: HubUsmerjevalnik, b: HubUsmerjevalnik, zacel: String = a.lastniId): Pair<Cev, Cev> {
    val ab = Cev(b)
    val ba = Cev(a)
    ab.druga = ba
    ba.druga = ab
    a.dodajSoseda(b.lastniId, ab, zacel)
    b.dodajSoseda(a.lastniId, ba, zacel)
    ab.spusti(); ba.spusti()
    return ab to ba
}

private fun prijava(u: HubUsmerjevalnik, id: String): Naprava {
    val n = Naprava()
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
        val brezPodpisa = Naprava().let { object : HubUsmerjevalnik.Odjemalec by it { override val vstopnica = v1 } }
        val o1 = a.odgovorNa(brezPodpisa, """{"id":"h","type":"cast.register","payload":{"device_id":"hub-b","role":"hub","capabilities":["mesh1"]}}""").orEmpty()
        preveriM("brez podpisa zavrnjen", o1.contains("rejected"))
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
    println()
    if (napakMesh == 0) println("Vse v redu.") else { println("Napak: $napakMesh"); kotlin.system.exitProcess(1) }
}
