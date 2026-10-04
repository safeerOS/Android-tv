package si.safeer.tv.cast

import java.net.Socket

/**
 * Obrambni mehanizem sredisca (HubObramba): ista pravila in isti primeri kot tests/test_link_obramba.py na racunalniku.
 *
 * Prvi del je cista logika z lazno uro. Drugi del je pravi HubStreznik na zanki (brez TLS, kot ostali preizkusi na
 * JVM): napadalec z zavrnjenimi prijavami je zaprt in povezava se mu zapre brez odgovora.
 */

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

private fun <T> enako(opis: String, pricakovano: T, dobljeno: T) =
    preveri("$opis (pričakovano=$pricakovano, dobljeno=$dobljeno)", pricakovano == dobljeno)

private const val A = "192.168.0.66"
private const val B = "192.168.0.67"
private const val C = "192.168.0.68"

private class Okolje {
    var t = 1_000_000L
    val zapore = ArrayList<Triple<String, Long, String>>()
    val napadi = ArrayList<List<String>>()
    val opozorila = ArrayList<Triple<String, Int, Map<String, Int>>>()
    var povezani: Set<String> = emptySet()
    val o = HubObramba(
        ura = { t },
        obZapori = { vir, ms, razlog -> zapore.add(Triple(vir, ms, razlog)) },
        obNapadu = { viri -> napadi.add(viri) },
        obOpozorilu = { vir, vsota, sestava -> opozorila.add(Triple(vir, vsota, sestava)) }
    ).also { it.zaupan = { vir -> vir in povezani } }

    fun doZapore(vir: String, vrsta: String = HubObramba.BREZ_ZAUPANJA) {
        var n = 0
        while (!o.zaprt(vir) && n++ < 500) o.dogodek(vir, vrsta)
    }
}

private fun pravila() {
    println("Pravila")
    Okolje().apply {
        repeat(19) { o.dogodek(A, HubObramba.BREZ_ZAUPANJA) }
        preveri("pod pragom je vir sprejet", o.dovoli(A))
        enako("pod pragom ni zapore", 0, zapore.size)
        o.dogodek(A, HubObramba.BREZ_ZAUPANJA)
        enako("prag zapre vir", listOf(Triple(A, HubObramba.ZAPORA_MS, HubObramba.BREZ_ZAUPANJA)), zapore)
        preveri("zaprt vir ni sprejet", !o.dovoli(A))
        preveri("zapora velja za vir, ne za vse", o.dovoli(B))
        val s = o.stanje()
        enako("stanje: zaprti", listOf(A), s.zaprti.map { it.vir })
        enako("stanje: zavrnjenih", 1L, s.zavrnjenih)
        enako("stanje: sestava", mapOf(HubObramba.BREZ_ZAUPANJA to 400, HubObramba.POVEZAVA to 1).toSortedMap(), s.zaprti[0].sestava.toSortedMap())
        t += HubObramba.ZAPORA_MS + 1
        preveri("po izteku zapore je vir spet sprejet", o.dovoli(A))
    }
    Okolje().apply {
        repeat(15) { o.dogodek(A, HubObramba.BREZ_ZAUPANJA) }
        t += HubObramba.OKNO_MS + 1
        repeat(15) { o.dogodek(A, HubObramba.BREZ_ZAUPANJA) }
        preveri("stari dogodki ne štejejo", !o.zaprt(A))
    }
    Okolje().apply {
        // Naprava, ki se prijavlja pod napacnim id-jem: vsakih 15 s povezava, zavrnjen izziv in neuspelo rokovanje - ves dan.
        repeat(4 * 60 * 24) {
            o.dovoli(A); o.dogodek(A, HubObramba.BREZ_ZAUPANJA); o.dovoli(A); o.dogodek(A, HubObramba.ROKOVANJE)
            t += 15_000
        }
        enako("legitimna naprava ni nikoli zaprta", 0, zapore.size)
    }
    Okolje().apply {
        var poskusov = 0
        while (!o.zaprt(A)) { o.dogodek(A, HubObramba.SEZNANITEV); poskusov++ }
        enako("ugibanje kode: zapora ob desetem dogodku", 10, poskusov)
        var zacetkov = 0
        while (!o.zaprt(B)) { o.dogodek(B, HubObramba.ZACETEK_SEZNANITVE); zacetkov++ }
        enako("začetki seznanitve: zapora ob četrtem", 4, zacetkov)
    }
    Okolje().apply {
        val trajanja = ArrayList<Long>()
        repeat(5) { doZapore(A); trajanja.add(zapore.last().second); t += zapore.last().second + 1 }
        enako("zapora se podvoji do ene ure", listOf(600_000L, 1_200_000L, 2_400_000L, 3_600_000L, 3_600_000L), trajanja)
        t += HubObramba.POZABI_PONOVITVE_MS + 1
        doZapore(A)
        enako("po dnevu miru znova 10 minut", 600_000L, zapore.last().second)
    }
    Okolje().apply {
        var sprejetih = 0
        for (i in 0 until 1000) { if (!o.dovoli(A)) break; sprejetih++ }
        enako("poplava povezav: 399 sprejetih", 399, sprejetih)
        enako("poplava: razlog", HubObramba.POVEZAVA, zapore[0].third)
    }
    Okolje().apply {
        repeat(300) { o.dovoli(A) }
        o.zaupaj(A)
        var vsi = true
        repeat(5000) { if (!o.dovoli(A)) vsi = false }
        preveri("zaupan vir: povezave ne štejejo", vsi && zapore.isEmpty())
        repeat(39) { o.dogodek(A, HubObramba.BREZ_ZAUPANJA) }
        preveri("zaupan vir: dogodki štejejo polovico", !o.zaprt(A))
        o.dogodek(A, HubObramba.BREZ_ZAUPANJA)
        preveri("tudi zaupan vir ne sme ugibati brez konca", o.zaprt(A))
        o.zaupaj(B)
        t += HubObramba.ZAUPANJE_MS + 1
        for (i in 0 until 1000) if (!o.dovoli(B)) break
        preveri("zaupanje poteče", o.zaprt(B))
    }
    Okolje().apply {
        // Prijava je bila pred urami, povezava je se odprta: listanje mape s slikami ni poplava.
        povezani = setOf(A)
        t += 6 * 3_600_000L
        var vsi = true
        repeat(5000) { if (!o.dovoli(A)) vsi = false }
        preveri("povezana naprava je zaupana, dokler je povezana", vsi)
        povezani = emptySet()
        for (i in 0 until 1000) if (!o.dovoli(A)) break
        preveri("brez povezave velja kot vsak drug vir", o.zaprt(A))
        val pokvarjen = HubObramba(ura = { t }).also { it.zaupan = { throw IllegalStateException("x") } }
        for (i in 0 until 1000) if (!pokvarjen.dovoli(B)) break
        preveri("pokvarjen povratni klic ne podari zaupanja", pokvarjen.zaprt(B))
    }
    Okolje().apply {
        for (vir in listOf("127.0.0.1", "127.0.0.53", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1", "")) {
            repeat(500) { o.dogodek(vir, HubObramba.SEZNANITEV) }
            preveri("ta naprava ($vir) ni nikoli zaprta", o.dovoli(vir))
        }
        enako("zanka: brez zapor", 0, zapore.size)
        preveri("192.168.0.1 ni ta naprava", !HubObramba.jeTaNaprava("192.168.0.1"))
    }
    Okolje().apply {
        doZapore(A); doZapore(B)
        enako("dva vira še nista napad", 0, napadi.size)
        doZapore(C)
        enako("trije viri so napad", listOf(listOf(A, B, C)), napadi)
        preveri("stanje pove napad", o.stanje().napad)
        doZapore("192.168.0.69")
        enako("isti napad se ne javlja znova", 1, napadi.size)
    }
    Okolje().apply {
        repeat(2) { doZapore(A); t += zapore.last().second + 1 }
        enako("dve zapori istega vira še nista napad", 0, napadi.size)
        doZapore(A)
        enako("tretja zapora istega vira je napad", listOf(listOf(A)), napadi)
    }
    Okolje().apply {
        doZapore(A)
        for (i in 0 until HubObramba.NAJVEC_VIROV + 200) o.dogodek("10.${i shr 16 and 255}.${i shr 8 and 255}.${i and 255}", HubObramba.TIPANJE)
        preveri("zaprt vir ne izpade iz spomina", o.zaprt(A))
        preveri("sprosti", o.sprosti(A) && o.dovoli(A) && !o.sprosti(A) && !o.sprosti("192.168.9.9"))
    }
    Okolje().apply {
        repeat(9) { o.dogodek(A, HubObramba.BREZ_ZAUPANJA) }
        enako("pod polovico praga ni opozorila", 0, opozorila.size)
        o.dogodek(A, HubObramba.BREZ_ZAUPANJA); o.dogodek(A, HubObramba.TIPANJE)
        enako("eno opozorilo na polovici praga", listOf(Triple(A, 200, mapOf(HubObramba.BREZ_ZAUPANJA to 200))), opozorila.toList())
    }
    val brezKlicev = HubObramba(ura = { 0L }, obZapori = { _, _, _ -> throw IllegalStateException("x") })
    repeat(30) { brezKlicev.dogodek(A, HubObramba.BREZ_ZAUPANJA) }
    preveri("povratni klic, ki pade, ne podre središča", brezKlicev.zaprt(A))
}

private fun napake() {
    println("Napake odgovorov")
    fun telo(koda: String) = "{\"detail\":\"x\",\"code\":\"$koda\"}"
    enako("zavrnjen podpis", HubObramba.BREZ_ZAUPANJA, HubObramba.vrstaNapake(401, telo("napacen_podpis")))
    enako("ni v krogu", HubObramba.BREZ_ZAUPANJA, HubObramba.vrstaNapake(401, telo("naprava_ni_v_krogu")))
    enako("napacna koda", HubObramba.SEZNANITEV, HubObramba.vrstaNapake(401, telo("napacna_koda")))
    enako("prevec poskusov", HubObramba.SEZNANITEV, HubObramba.vrstaNapake(429, telo("prevec_poskusov")))
    enako("pot, ki je ni (odgovor streznika brez kode)", HubObramba.TIPANJE, HubObramba.vrstaNapake(404, "{\"napaka\":\"ni te poti\"}"))
    // 405 je del prepoznave sredisca, 409/410/503 so stanje, »naprava_ni_povezana« je odgovor seznanjeni napravi.
    enako("405 ni sovrazno", "", HubObramba.vrstaNapake(405, telo("metoda_ni_dovoljena")))
    enako("409 ni sovrazno", "", HubObramba.vrstaNapake(409, telo("alias_zaseden")))
    enako("cilj ni povezan ni sovrazno", "", HubObramba.vrstaNapake(404, telo("naprava_ni_povezana")))
    enako("410 ni sovrazno", "", HubObramba.vrstaNapake(410, telo("posodobi_aplikacijo")))
    enako("uspeh ni sovrazen", "", HubObramba.vrstaNapake(200, telo("ni_poti")))
    preveri("vse vrste imajo tezo", HubObramba.VRSTA_PO_NAPAKI.values.all { it in HubObramba.TEZE })
}

// ------------------------------------------------------------ pravo sredisce na zanki

private fun zahteva(vrata: Int, metoda: String, pot: String, telo: String = ""): Int {
    return try {
        Socket("127.0.0.1", vrata).use { s ->
            s.soTimeout = 3000
            val t = telo.toByteArray(Charsets.UTF_8)
            s.getOutputStream().write(("$metoda $pot HTTP/1.1\r\nHost: test\r\nContent-Length: ${t.size}\r\n\r\n").toByteArray(Charsets.UTF_8))
            s.getOutputStream().write(t)
            s.getOutputStream().flush()
            val prva = s.getInputStream().bufferedReader(Charsets.UTF_8).readLine() ?: return 0
            prva.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
        }
    } catch (_: Exception) {
        0
    }
}

private fun vZivo() {
    println("Središče na zanki")
    val zapore = ArrayList<Pair<String, String>>()
    // Zanka je sicer izvzeta - tu jo stejemo, da lahko napademo sami sebe.
    val obramba = HubObramba(obZapori = { vir, _, razlog -> synchronized(zapore) { zapore.add(vir to razlog) } }, izvzet = { false })
    val streznik = HubStreznik(
        zeljenaVrata = 0,
        naZahtevo = { z ->
            when {
                z.pot == "/cast/health" && z.metoda == "GET" -> HubStreznik.Odgovor(401, "{\"detail\":\"Naprava ni seznanjena.\",\"code\":\"naprava_ni_seznanjena\"}")
                z.pot == "/cast/ticket" && z.metoda == "POST" && z.glave["x-safeer-token"] == "pravi" -> HubStreznik.Odgovor(200, "{\"ticket\":\"t\"}")
                z.pot == "/cast/ticket" && z.metoda == "POST" -> HubStreznik.Odgovor(401, "{\"detail\":\"Naprava ni seznanjena.\",\"code\":\"naprava_ni_seznanjena\"}")
                z.pot == "/cast/ticket" -> HubStreznik.Odgovor(405, "{\"detail\":\"x\",\"code\":\"metoda_ni_dovoljena\"}")
                z.pot == "/cast/pair/start" && z.metoda == "POST" -> HubStreznik.Odgovor(200, "{\"pair_id\":\"p\"}")
                else -> null
            }
        },
        preveriVstopnico = { _ -> "neveljavna vstopnica" },
        naPovezavo = { _ -> },
        obramba = obramba
    )
    preveri("središče se zažene", streznik.zazeni())
    val vrata = streznik.vrata

    // Prepoznava sredisca (GET /cast/health brez zetona -> 401, GET /cast/ticket -> 405) ni sovrazna.
    repeat(30) { zahteva(vrata, "GET", "/cast/health"); zahteva(vrata, "GET", "/cast/ticket") }
    preveri("prepoznava središča ni sovražna", zapore.isEmpty() && !obramba.zaprt("127.0.0.1"))
    obramba.sprosti("127.0.0.1")

    val sveza = HubObramba(obZapori = { vir, _, razlog -> synchronized(zapore) { zapore.add(vir to razlog) } }, izvzet = { false })
    val drugi = HubStreznik(zeljenaVrata = 0, naZahtevo = { z ->
        if (z.pot == "/cast/ticket" && z.metoda == "POST") HubStreznik.Odgovor(401, "{\"detail\":\"x\",\"code\":\"naprava_ni_seznanjena\"}")
        else if (z.pot == "/cast/pair/start") HubStreznik.Odgovor(200, "{\"pair_id\":\"p\"}") else null
    }, preveriVstopnico = { _ -> "neveljavna vstopnica" }, naPovezavo = { _ -> }, obramba = sveza)
    preveri("drugo središče se zažene", drugi.zazeni())
    val kode = ArrayList<Int>()
    for (i in 0 until 30) { kode.add(zahteva(drugi.vrata, "POST", "/cast/ticket", "{}")); if (sveza.zaprt("127.0.0.1")) break }
    // 20 (zavrnjen zeton) + 1 (povezava): devetnajst odgovorov, dvajseta povezava je ze zaprta.
    enako("napačni žetoni: 19 odgovorov, nato zapora", List(19) { 401 } + listOf(0), kode)
    enako("zapora: vir in razlog", listOf("127.0.0.1" to HubObramba.BREZ_ZAUPANJA), synchronized(zapore) { zapore.toList() })
    enako("zaprt vir ne dobi odgovora", 0, zahteva(drugi.vrata, "GET", "/cast/health"))
    preveri("uporabnik vir sprosti", sveza.sprosti("127.0.0.1"))
    enako("po sprostitvi središče odgovarja", 404, zahteva(drugi.vrata, "GET", "/cast/health"))

    // Zacetek seznanitve uporabniku pokaze kodo: cetrti v minuti vir zapre.
    sveza.sprosti("127.0.0.1")
    synchronized(zapore) { zapore.clear() }
    val tretja = HubObramba(obZapori = { vir, _, razlog -> synchronized(zapore) { zapore.add(vir to razlog) } }, izvzet = { false })
    val tretji = HubStreznik(zeljenaVrata = 0, naZahtevo = { z -> if (z.pot == "/cast/pair/start") HubStreznik.Odgovor(200, "{\"pair_id\":\"p\"}") else null },
        preveriVstopnico = { _ -> null }, naPovezavo = { _ -> }, obramba = tretja)
    preveri("tretje središče se zažene", tretji.zazeni())
    val zacetki = ArrayList<Int>()
    for (i in 0 until 12) { zacetki.add(zahteva(tretji.vrata, "POST", "/cast/pair/start", "{\"device_id\":\"x$i\"}")); if (tretja.zaprt("127.0.0.1")) break }
    enako("štirje začetki seznanitve, nato zapora", listOf(200, 200, 200, 200), zacetki)
    enako("razlog zapore", listOf("127.0.0.1" to HubObramba.ZACETEK_SEZNANITVE), synchronized(zapore) { zapore.toList() })

    // Brez obrambe (stari klici konstruktorja) streznik dela kot prej.
    val brez = HubStreznik(zeljenaVrata = 0, naZahtevo = { _ -> null }, preveriVstopnico = { _ -> "ne" }, naPovezavo = { _ -> })
    preveri("središče brez obrambe se zažene", brez.zazeni())
    var vse404 = true
    repeat(60) { if (zahteva(brez.vrata, "GET", "/ni") != 404) vse404 = false }
    preveri("brez obrambe ni zapore", vse404)

    for (s in listOf(streznik, drugi, tretji, brez)) s.ustavi()
}

fun main() {
    pravila()
    napake()
    vZivo()
    println()
    if (napak == 0) println("VSE V REDU") else { println("NAPAK: $napak"); System.exit(1) }
}
