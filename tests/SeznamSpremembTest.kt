package si.safeer.tv.cast

// Seznam naprav kot posnetek + spremembe (SeznamSprememb) v navadnem JVM. Isti primeri kot tests/test_link_spremembe.py na
// Linuxu; skupna pogodba (tests/fixtures/seznam_sprememb.json) je ista datoteka v obeh repozitorijih.

private var napakSS = 0

private fun preveriS(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napakSS++ }
}

private fun primerS(ime: String, f: () -> Unit) { println(ime); f() }

/** Kanonicni JSON (kljuci po abecedi, brez presledkov): primerjava ne glede na zapis. */
private fun kanon(surovo: String): String {
    val t = surovo.trim()
    return when {
        t.startsWith("{") -> {
            val p = JsonLahki.objekt(t) ?: return "?"
            p.kljuci().sorted().joinToString(",", "{", "}") { "\"" + JsonLahki.ubezi(it) + "\":" + kanon(p.surovo(it)!!) }
        }
        t.startsWith("[") -> (JsonLahki.elementi(t) ?: return "?").joinToString(",", "[", "]") { kanon(it) }
        t.startsWith("\"") -> "\"" + JsonLahki.ubezi(JsonLahki.niz(t) ?: return "?") + "\""
        t == "true" || t == "false" || t == "null" -> t
        else -> t.toDoubleOrNull()?.let { JsonLahki.stevilo(it) } ?: "?"
    }
}

/** Seznam naprav brez casa zadnjega stika, kanonicno (vrstni red naprav ostane). */
private fun brezCasa(seznam: String): String =
    (JsonLahki.elementi(seznam) ?: emptyList()).joinToString(",", "[", "]") { e ->
        kanon(SeznamSprememb.json(SeznamSprememb.zapis(e)!!.filterKeys { it != "last_seen" }))
    }

private class Odjemalec(val nov: Boolean, override val naslov: String = "192.168.0.50") : HubUsmerjevalnik.Odjemalec {
    val prejeto: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())
    val prejemnik = SeznamSprememb.Prejemnik()
    @Volatile var seznam: String? = null
    var zahtev = 0
    var izgubi: ((String) -> Boolean)? = null
    val cakajoce = ArrayList<String>()
    override fun poslji(besedilo: String) {
        if (izgubi?.invoke(besedilo) == true) return
        prejeto.add(besedilo)
        val tip = JsonLahki.objekt(besedilo)?.niz("type")
        if (tip != SeznamSprememb.POSNETEK && tip != SeznamSprememb.SPREMEMBE) return
        if (nov) {
            val (s, zahtevaj) = prejemnik.sprejmi(besedilo)
            if (zahtevaj) { zahtev++; cakajoce.add(SeznamSprememb.zahteva("z$zahtev")) }
            if (s != null) seznam = JsonLahki.objekt(s)?.surovo("devices")
        } else if (tip == SeznamSprememb.POSNETEK) seznam = JsonLahki.objekt(besedilo)?.surovo("devices")
    }
    override fun zapri(koda: Int, razlog: String) {}
    fun vrste(tip: String) = synchronized(prejeto) { prejeto.toList() }.filter { JsonLahki.objekt(it)?.niz("type") == tip }
    fun idji(): List<String> = (JsonLahki.elementi(seznam ?: "[]") ?: emptyList()).mapNotNull { JsonLahki.objekt(it)?.niz("id") }
}

private fun prijavi(u: HubUsmerjevalnik, id: String, nov: Boolean, naslov: String = "192.168.0.50", o: Odjemalec? = null, ime: String = id): Odjemalec {
    val n = o ?: Odjemalec(nov, naslov)
    val zm = if (nov) "\"url\",\"remote\",\"${SeznamSprememb.ZMOZNOST}\"" else "\"url\",\"remote\""
    u.obdelaj(n, """{"id":"r","type":"cast.register","payload":{"device_id":"$id","name":"$ime","role":"receiver","capabilities":[$zm]}}""")
    return n
}

private class CevS(val cilj: HubUsmerjevalnik, override val naslov: String = "192.168.0.9") : HubUsmerjevalnik.Odjemalec {
    lateinit var druga: CevS
    val poslano = ArrayList<String>()
    private val vrsta = ArrayList<String>()
    var zadrzi = true
    var izgubi: ((String) -> Boolean)? = null
    /** Starejse sredisce na eni strani povezave: mesh.devices gre brez epoch/ver/caps (kot ga poslje oz. prebere starejsi). */
    var staro = false
    var poslanihSprememb = 0
    override fun poslji(besedilo: String) {
        var b = besedilo
        val tip = JsonLahki.objekt(b)?.niz("type")
        if (tip == SeznamSprememb.MESH_SPREMEMBE) poslanihSprememb++
        if (staro && tip == SeznamSprememb.MESH_POSNETEK) {
            val p = JsonLahki.objekt(b)!!.objekt("payload")!!
            b = """{"type":"mesh.devices","payload":{"hub":"${p.niz("hub")}","devices":${p.surovo("devices")},"relay":${p.surovo("relay")}}}"""
        }
        if (izgubi?.invoke(b) == true) return
        poslano.add(b)
        if (zadrzi) vrsta.add(b) else cilj.obdelaj(druga, b)
    }
    override fun zapri(koda: Int, razlog: String) {}
    fun spusti() { zadrzi = false; val v = vrsta.toList(); vrsta.clear(); v.forEach { cilj.obdelaj(druga, it) } }
    /** Kar ima sosed po posnetku in spremembah, ki so sle po tej povezavi. */
    fun zrcalo(): SeznamSprememb.Zrcalo {
        val z = SeznamSprememb.Zrcalo(mapOf("devices" to false, "relay" to false))
        for (s in poslano) {
            val j = JsonLahki.objekt(s) ?: continue
            val t = j.objekt("payload") ?: continue
            when (j.niz("type")) {
                SeznamSprememb.MESH_POSNETEK -> z.posnetek(mapOf("devices" to t.surovo("devices"), "relay" to t.surovo("relay")), t.niz("epoch"), t.stevilo("ver"))
                SeznamSprememb.MESH_SPREMEMBE -> z.uporabi(t)
            }
        }
        return z
    }
}

private val kljuciS = HashMap<String, String>()
private fun kljucS(id: String): String = kljuciS.getOrPut(id) {
    val par = java.security.KeyPairGenerator.getInstance("EC").apply {
        initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    java.util.Base64.getEncoder().encodeToString(par.public.encoded)
}

private val CLANI_S = listOf("hub-a", "hub-b", "hub-c", "tv", "pc", "tel", "tablica", "ura") + (0 until 30).map { "d$it" }

private fun hubS(id: String, oknoMs: Long = 0L): HubUsmerjevalnik {
    val u = HubUsmerjevalnik(null, oknoSeznamaMs = oknoMs)
    u.lastniId = id
    for (c in CLANI_S) u.krog.dodaj(KrogZaupanja.Clan(c, kljucS(c), c, "tv", 1.0, "hub-a"))
    return u
}

private fun poveziS(a: HubUsmerjevalnik, b: HubUsmerjevalnik, staroA: Boolean = false, staroB: Boolean = false): Pair<CevS, CevS> {
    val ab = CevS(b); val ba = CevS(a)
    ab.druga = ba; ba.druga = ab
    // Starejse sredisce na eni strani: v obe smeri brez epoch/ver/caps - ono jih ne posilja, nase pa ne bere.
    ab.staro = staroA || staroB; ba.staro = staroA || staroB
    a.dodajSoseda(b.lastniId, ab, a.lastniId)
    b.dodajSoseda(a.lastniId, ba, a.lastniId)
    ab.spusti(); ba.spusti()
    return ab to ba
}

fun main(args: Array<String>) {
    println("Preizkus seznama naprav: posnetek + spremembe (SeznamSprememb)")
    val primeri = args.firstOrNull()?.let { java.io.File(it).readText() }

    primerS("skupna pogodba z racunalnikom (tests/fixtures/seznam_sprememb.json)") {
        val p = JsonLahki.objekt(primeri ?: "{}")
        preveriS("primeri prebrani", p != null && p.ima("prejemnik") && p.ima("razlika"))
        for (e in JsonLahki.elementi(p?.surovo("prejemnik") ?: "[]").orEmpty()) {
            val primer = JsonLahki.objekt(e)!!
            val ime = primer.niz("ime")
            val z = SeznamSprememb.Zrcalo(mapOf("devices" to true))
            val pos = primer.objekt("posnetek")!!
            z.posnetek(mapOf("devices" to pos.surovo("devices")), pos.niz("epoch"), pos.stevilo("ver"))
            for (k in JsonLahki.elementi(primer.surovo("spremembe")!!)!!) {
                val korak = JsonLahki.objekt(k)!!
                val izid = z.uporabi(korak.objekt("sporocilo")!!)
                preveriS("$ime: ${korak.niz("izid")}", izid == korak.niz("izid"))
            }
            preveriS("$ime: koncni seznam", kanon(z.seznamJson()) == kanon(primer.surovo("koncni")!!))
        }
        for (e in JsonLahki.elementi(p?.surovo("razlika") ?: "[]").orEmpty()) {
            val primer = JsonLahki.objekt(e)!!
            val (_, d) = SeznamSprememb.razlika(SeznamSprememb.seznam(primer.surovo("staro"))!!, SeznamSprememb.seznam(primer.surovo("novo"))!!, true)
            val pricakovano = primer.surovo("sprememba")!!
            preveriS("razlika ${primer.niz("ime")}", if (pricakovano == "null") d == null else d != null && kanon(d) == kanon(pricakovano))
        }
    }

    primerS("nakljucna zaporedja: zrcalo je vedno enako objavi (tudi vrstni red)") {
        val rng = java.util.Random(7)
        for (zId in listOf(true, false)) {
            val pogled = SeznamSprememb.Pogled(mapOf("devices" to zId))
            val zrcalo = SeznamSprememb.Zrcalo(mapOf("devices" to zId))
            val stanje = LinkedHashMap<String, LinkedHashMap<String, String>>()
            var vredu = true
            for (korak in 0 until 400) {
                val r = rng.nextDouble()
                if (r < 0.4 || stanje.isEmpty()) {
                    val i = "n${rng.nextInt(60)}"
                    stanje[i] = linkedMapOf("name" to "\"N$i\"", "capabilities" to "[\"url\"]", "last_seen" to "$korak")
                } else if (r < 0.6) stanje.remove(stanje.keys.elementAt(rng.nextInt(stanje.size)))
                else if (r < 0.8) {
                    val i = stanje.keys.elementAt(rng.nextInt(stanje.size))
                    val z = LinkedHashMap(stanje[i]!!)
                    z["name"] = "\"ime$korak\""
                    if (rng.nextBoolean()) z["apps"] = "{\"si.safeer.os\":{\"name\":\"OS\"}}" else z.remove("apps")
                    stanje[i] = z
                } else {
                    val i = stanje.keys.elementAt(rng.nextInt(stanje.size))
                    stanje[i] = LinkedHashMap(stanje[i]!!).apply { put("last_seen", "$korak") }
                }
                val novo = LinkedHashMap<String, ZapisSeznama>()
                for ((i, z) in stanje) novo[i] = if (zId) LinkedHashMap<String, String>().apply { put("id", "\"$i\""); putAll(z) } else z
                val prej = pogled.ver
                pogled.posodobi(mapOf("devices" to novo))
                if (pogled.ver == 1L) zrcalo.posnetek(mapOf("devices" to if (zId) pogled.posnetekJson() else pogled.slovarJson("devices")), pogled.epoha, 1.0)
                else if (pogled.ver != prej) {
                    val t = JsonLahki.objekt("{\"epoch\":\"${pogled.epoha}\",\"base\":${pogled.ver - 1},\"ver\":${pogled.ver}," +
                        pogled.zadnja!!.entries.joinToString(",") { "\"${it.key}\":${it.value}" } + "}")!!
                    if (zrcalo.uporabi(t) != "ok") vredu = false
                }
                val a = if (zId) brezCasa(zrcalo.seznamJson()) else kanon(zrcalo.slovarJson("devices").let { s ->
                    JsonLahki.objekt(s)!!.let { o -> o.kljuci().joinToString(",", "{", "}") { k -> "\"$k\":" + SeznamSprememb.json(SeznamSprememb.zapis(o.surovo(k)!!)!!.filterKeys { it != "last_seen" }) } } })
                val b = if (zId) brezCasa(pogled.posnetekJson()) else kanon(pogled.slovarJson("devices").let { s ->
                    JsonLahki.objekt(s)!!.let { o -> o.kljuci().joinToString(",", "{", "}") { k -> "\"$k\":" + SeznamSprememb.json(SeznamSprememb.zapis(o.surovo(k)!!)!!.filterKeys { it != "last_seen" }) } } })
                if (a != b || zrcalo.deli["devices"]!!.keys.toList() != pogled.deli["devices"]!!.keys.toList()) vredu = false
            }
            preveriS("400 korakov (zId=$zId)", vredu)
        }
    }

    primerS("vrzel in dvom") {
        val z = SeznamSprememb.Zrcalo(mapOf("devices" to true))
        z.posnetek(mapOf("devices" to "[{\"id\":\"a\",\"name\":\"A\"}]"), "e1", 3.0)
        preveriS("napacna osnova je vrzel", z.uporabi(JsonLahki.objekt("{\"epoch\":\"e1\",\"base\":2,\"ver\":3}")!!) == "vrzel")
        preveriS("druga oznaka je vrzel", z.uporabi(JsonLahki.objekt("{\"epoch\":\"x\",\"base\":3,\"ver\":4}")!!) == "vrzel")
        preveriS("logicna verzija je vrzel", z.uporabi(JsonLahki.objekt("{\"epoch\":\"e1\",\"base\":3,\"ver\":true}")!!) == "vrzel")
        preveriS("vrzel ne pokvari seznama", z.seznamJson() == "[{\"id\":\"a\",\"name\":\"A\"}]")
        for (slabo in listOf("{\"del\":[\"b\"]}", "{\"chg\":[{\"id\":\"b\",\"set\":{\"name\":\"x\"}}]}", "{\"add\":[{\"id\":\"a\"}]}",
                "{\"chg\":[{\"id\":\"a\",\"set\":{\"id\":\"b\"}}]}", "{\"chg\":[{\"id\":\"a\",\"unset\":[\"id\"]}]}", "{\"add\":\"x\"}", "[]")) {
            z.posnetek(mapOf("devices" to "[{\"id\":\"a\",\"name\":\"A\"}]"), "e1", 3.0)
            val izid = z.uporabi(JsonLahki.objekt("{\"epoch\":\"e1\",\"base\":3,\"ver\":4,\"devices\":$slabo}")!!)
            preveriS("dvom: $slabo", izid == "dvom" && !z.velja && z.seznamJson() == "[]")
        }
        z.posnetek(mapOf("devices" to "[{\"id\":\"a\"}]"), "e1", 3.0)
        val velik = (0 until 2100).joinToString(",", "[", "]") { "{\"id\":\"v$it\",\"x\":\"${"y".repeat(2000)}\"}" }
        preveriS("meja zrcala", z.uporabi(JsonLahki.objekt("{\"epoch\":\"e1\",\"base\":3,\"ver\":4,\"devices\":{\"add\":$velik}}")!!) == "dvom")
        val ura = longArrayOf(0L)
        val p = SeznamSprememb.Prejemnik { ura[0] }
        p.sprejmi("{\"type\":\"cast.devices\",\"devices\":[],\"epoch\":\"e\",\"ver\":1}")
        preveriS("prva zahteva", p.sprejmi("{\"type\":\"cast.devices.delta\",\"epoch\":\"e\",\"base\":5,\"ver\":6}") == (null to true))
        preveriS("druga v 2 s ne", p.sprejmi("{\"type\":\"cast.devices.delta\",\"epoch\":\"e\",\"base\":6,\"ver\":7}") == (null to false))
        ura[0] = 2500
        preveriS("po 2 s spet", p.sprejmi("{\"type\":\"cast.devices.delta\",\"epoch\":\"e\",\"base\":7,\"ver\":8}") == (null to true))
        preveriS("drugo sporocilo gre naprej nespremenjeno", p.sprejmi("{\"type\":\"cast.url\"}") == ("{\"type\":\"cast.url\"}" to false))
    }

    primerS("sredisce: posnetek, nato samo spremembe; star odjemalec cel seznam") {
        val u = hubS("hub-a")
        val a = prijavi(u, "tv", nov = true)
        val star = prijavi(u, "pc", nov = false)
        val b = prijavi(u, "tel", nov = true)
        preveriS("novi dobi posnetek", b.vrste("cast.devices").size == 1)
        val zadnja = a.vrste("cast.devices.delta").lastOrNull().orEmpty()
        preveriS("star clan dobi samo spremembo (dodan tel)", zadnja.contains("\"add\"") && zadnja.contains("\"tel\"") && !zadnja.contains("\"chg\""))
        preveriS("tv ima en sam posnetek", a.vrste("cast.devices").size == 1)
        preveriS("star odjemalec nikoli sprememb", star.vrste("cast.devices.delta").isEmpty())
        preveriS("nov in star imata isti seznam", brezCasa(a.seznam!!) == brezCasa(star.seznam!!) && a.idji() == listOf("tv", "pc", "tel"))
        u.odklopi(b)
        preveriS("odhod je del", a.vrste("cast.devices.delta").last().contains("\"del\":[\"tel\"]"))
        preveriS("po odhodu enaka", brezCasa(a.seznam!!) == brezCasa(star.seznam!!))
    }

    primerS("mesan krog nakljucno: novi odjemalci imajo vedno isti seznam kot stari") {
        val rng = java.util.Random(11)
        val u = hubS("hub-a")
        val povezani = LinkedHashMap<String, Odjemalec>()
        var vredu = true
        for (korak in 0 until 300) {
            val r = rng.nextDouble()
            val id = "d${rng.nextInt(25)}"
            val naslov = if (rng.nextDouble() < 0.3) "127.0.0.1" else "192.168.0.50"
            when {
                r < 0.45 -> povezani[id] = prijavi(u, id, nov = rng.nextDouble() < 0.6, naslov = naslov, ime = "Ime $korak")
                r < 0.65 && id in povezani -> u.odklopi(povezani.remove(id)!!)
                r < 0.85 && id in povezani -> u.obdelaj(povezani[id]!!, """{"id":"k","type":"apps.announce","payload":{"apps":${
                    if (rng.nextDouble() < 0.7) "{\"si.safeer.os\":{\"name\":\"OS $korak\"}}" else "{}"}}}""")
                id in povezani -> prijavi(u, id, povezani[id]!!.nov, o = povezani[id], ime = "Novo $korak")
            }
            // Vsak star odjemalec v skupini ima zadnji cel seznam: novi iz iste skupine (isti naslov) imajo enakega.
            for (skupina in listOf("127.0.0.1", "192.168.0.50")) {
                val v = povezani.values.filter { it.naslov == skupina }
                val stari = v.filter { !it.nov }
                val novi = v.filter { it.nov }
                if (stari.isEmpty()) continue
                val wzor = brezCasa(stari.first().seznam!!)
                if (novi.any { brezCasa(it.seznam!!) != wzor } || stari.any { brezCasa(it.seznam!!) != wzor }) vredu = false
            }
            if (povezani.values.any { it.zahtev > 0 }) vredu = false
            if (povezani.values.any { it.idji().toSet() != povezani.keys }) vredu = false
        }
        preveriS("300 korakov brez razlike", vredu)
    }

    primerS("izgubljena sprememba: odjemalec zahteva posnetek in seznam se ujema") {
        val u = hubS("hub-a")
        var izgubi = false
        val a = prijavi(u, "tv", nov = true).apply { this.izgubi = { izgubi && it.contains("cast.devices.delta") } }
        prijavi(u, "tel", nov = true)
        izgubi = true
        prijavi(u, "tablica", nov = true)
        izgubi = false
        preveriS("tablice se ne vidi", "tablica" !in a.idji())
        prijavi(u, "pc", nov = true)
        preveriS("vrzel: ena zahteva", a.zahtev == 1)
        val posnetkov = a.vrste("cast.devices").size
        for (z in a.cakajoce.toList()) u.obdelaj(a, z)
        a.cakajoce.clear()
        preveriS("posnetek prispe", a.vrste("cast.devices").size == posnetkov + 1)
        preveriS("seznam se ujema", a.idji() == listOf("tv", "tel", "tablica", "pc"))
        prijavi(u, "ura", nov = true)
        preveriS("od posnetka naprej spet spremembe", JsonLahki.objekt(a.prejeto.last())?.niz("type") == "cast.devices.delta" && a.idji().last() == "ura")
        val tujec = Odjemalec(true)
        preveriS("zahteva neprijavljene povezave zavrnjena", u.odgovorNa(tujec, SeznamSprememb.zahteva("x")).orEmpty().contains("naprava_ni_povezana")
            && tujec.vrste("cast.devices").isEmpty())
        val drugi = prijavi(u, "d1", nov = true)
        val pred = drugi.prejeto.size
        u.obdelaj(a, SeznamSprememb.zahteva("y"))
        preveriS("zahteva ne gre nikomur naprej", drugi.prejeto.size == pred)
    }

    primerS("sosedje: posnetek, nato spremembe; tujec nikoli; starejsi sosed cel seznam") {
        val a = hubS("hub-a"); val b = hubS("hub-b")
        prijavi(a, "tv", nov = true)
        val (ab, ba) = poveziS(a, b)
        val tel = prijavi(b, "tel", nov = true)
        prijavi(a, "tablica", nov = true)
        prijavi(a, "tujec", nov = true)
        preveriS("a soseda posilja spremembe", ab.poslanihSprememb > 0 && ba.poslanihSprememb > 0)
        preveriS("tel vidi vse clane", tel.idji().toSet() == setOf("tv", "tel", "tablica"))
        preveriS("tujec ne gre cez mejo ne v posnetku ne v spremembi", ab.poslano.none { it.contains("\"tujec\"") })
        val z = ab.zrcalo()
        preveriS("zrcalo soseda: tv in tablica", z.deli["devices"]!!.keys == setOf("tv", "tablica"))
        val c = hubS("hub-c")
        prijavi(c, "pc", nov = true)
        val (ac, ca) = poveziS(a, c, staroB = true)
        prijavi(a, "ura", nov = true)
        preveriS("starejsemu sosedu nikoli sprememb", ac.poslanihSprememb == 0)
        val pcOdjemalec = prijavi(c, "d2", nov = true)
        preveriS("starejsi sosed vidi vse", pcOdjemalec.idji().toSet().containsAll(setOf("tv", "tablica", "ura", "pc", "d2")))
        preveriS("c (star) a ne posilja sprememb", ca.poslano.none { JsonLahki.objekt(it)?.niz("type") == SeznamSprememb.MESH_SPREMEMBE })
    }

    primerS("sosedje: izgubljena sprememba vrne posnetek") {
        val a = hubS("hub-a"); val b = hubS("hub-b")
        prijavi(a, "tv", nov = true)
        val tel = prijavi(b, "tel", nov = true)
        val (ab, ba) = poveziS(a, b)
        var izgubi = true
        ab.izgubi = { izgubi && it.contains(SeznamSprememb.MESH_SPREMEMBE) }
        prijavi(a, "tablica", nov = true)
        izgubi = false
        preveriS("b o tablici ne ve", "tablica" !in tel.idji())
        prijavi(a, "ura", nov = true)
        preveriS("b zahteva posnetek enkrat", ba.poslano.count { it.contains(SeznamSprememb.MESH_ZAHTEVA) } == 1)
        preveriS("seznam se ujema", tel.idji().toSet() == setOf("tv", "tel", "tablica", "ura"))
    }

    primerS("zdruzevanje: prva takoj, ostale ob koncu okna") {
        val klici = ArrayList<Long>()
        val casovniki = ArrayList<Pair<Long, () -> Unit>>()
        val ura = longArrayOf(1000L)
        val d = SeznamSprememb.Dusilec({ klici.add(ura[0]) }, 150L, { ura[0] }) { zamik, f -> casovniki.add(zamik to f) }
        d.sprozi()
        preveriS("prva takoj (zamik 0)", casovniki.map { it.first } == listOf(0L))
        casovniki.removeAt(0).second()
        ura[0] = ura[0] + 10L
        repeat(50) { d.sprozi() }
        preveriS("50 sprememb v oknu: ena objava", casovniki.size == 1 && casovniki[0].first == 140L)
        casovniki.removeAt(0).second()
        preveriS("dve objavi", klici.size == 2)
        d.ustavi()
        d.sprozi()
        preveriS("ustavljen ne objavlja", casovniki.isEmpty())
        val u = hubS("hub-a", oknoMs = 100L)
        val prvi = prijavi(u, "tv", nov = true)
        Thread.sleep(250)
        val ostali = (0 until 30).map { prijavi(u, "d$it", nov = true) }
        val konec = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < konec && (ostali + prvi).any { it.idji().size != 31 }) Thread.sleep(20)
        u.ustaviObjave()
        preveriS("vsi imajo cel seznam", (ostali + prvi).all { it.idji().size == 31 })
        preveriS("prvi: zdruzeno (najvec 4 spremembe, ne 30)", prvi.vrste("cast.devices.delta").size <= 4)
    }

    primerS("val prijav (meritev, 32 naprav = meja sredisca na Androidu)") {
        fun val32(nov: Boolean): Pair<Long, Int> {
            val u = hubS("hub-a")
            val n = (0 until 30).map { prijavi(u, "d$it", nov) } + listOf(prijavi(u, "tv", nov), prijavi(u, "pc", nov))
            val seznami = n.flatMap { o -> o.vrste("cast.devices") + o.vrste("cast.devices.delta") }
            return seznami.sumOf { it.length.toLong() } to seznami.size
        }
        val (stari, stSporocil) = val32(false)
        val (novi, noSporocil) = val32(true)
        println("  starejsi odjemalci (cel seznam): $stari znakov v $stSporocil sporocilih; novi (spremembe): $novi znakov v $noSporocil")
        preveriS("spremembe vsaj 5-krat manj prometa", novi * 5 < stari)
    }

    println(if (napakSS == 0) "Vse OK." else "NAPAK: $napakSS")
    if (napakSS > 0) kotlin.system.exitProcess(1)
}
