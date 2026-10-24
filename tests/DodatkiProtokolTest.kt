package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Naloga 123: Safeer Add-on Protocol v1 ([DodatkiProtokol]). Argument: pot do docs/DODATKI-PROTOKOL.md. */
fun main(args: Array<String>) {
    println("Preizkus protokola dodatkov v1")

    // Dokument in koda govorita isto verzijo (kopije dokumenta so v Safeer-linux in Safeer-windows).
    val dokument = args.firstOrNull()?.let { java.io.File(it).readText() }.orEmpty()
    val vDokumentu = Regex("PROTOKOL-VERZIJA: (\\d+)").find(dokument)?.groupValues?.get(1)?.toIntOrNull()
    preveriEnako("verzija v dokumentu = VERZIJA", DodatkiProtokol.VERZIJA, vDokumentu)
    // Primer manifesta v dokumentu (prvi blok json) je veljaven in brez opozoril.
    val primer = Regex("```json\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).find(dokument)?.groupValues?.get(1).orEmpty()
    val izPrimera = DodatkiProtokol.preberi(primer)
    preveriEnako("primer v dokumentu: brez opozoril", emptyList<String>(), izPrimera?.opozorila)
    preveriEnako("primer v dokumentu: dejanje, mapa, 3 nastavitve", Triple(1, 1, 3),
        izPrimera?.let { Triple(it.dejanja.size, it.mape.size, it.nastavitve.size) })

    preveriEnako("navaden dodatek Stremio: brez razsiritve", null, DodatkiProtokol.preberi("""{"id":"a","name":"A","resources":["stream"]}"""))
    preveriEnako("pokvarjen JSON: brez razsiritve", null, DodatkiProtokol.preberi("""{"id":"a","safeer":"""))

    val manifest = """{"id":"org.example","version":"1.2.0","name":"Ex","safeer":{"protocol":1,
      "actions":[
        {"id":"add-to-list","on":["movie","series"],"icon":"plus","label":{"en":"Add to my list","sl":"Dodaj na moj seznam"},"confirm":true},
        {"id":"Bad Id","on":["movie"],"label":"x"},
        {"id":"no-type","on":["game"],"label":"x"},
        {"id":"no-label","on":["movie"]},
        {"id":"odd-icon","on":["tv"],"icon":"rocket","label":"  Two\n  lines  "},
        {"id":"add-to-list","on":["tv"],"label":"dup"}],
      "enrich":["movie","SERIES","game"],
      "folders":[{"id":"my-account","name":{"en":"My account","sl":"Moj racun"}},{"id":"x","name":""}],
      "settings":[
        {"key":"api_key","type":"secret","required":true,"label":"API key"},
        {"key":"quality","type":"choice","options":["auto","1080p"],"default":"4k","label":"Quality"},
        {"key":"only_cached","type":"toggle","default":true,"label":"Only instant"},
        {"key":"leak","type":"secret","default":"abc","label":"Leak"},
        {"key":"empty","type":"choice","label":"Empty"},
        {"key":"weird","type":"script","label":"Weird"}],
      "changelog":{"1.2.0":{"en":"Folders","sl":"Mape"}},
      "future":{"anything":1}}}"""
    val r = DodatkiProtokol.preberi(manifest)!!
    preveriEnako("protokol 1", 1, r.protokol)
    preveriEnako("veljavna dejanja (brez dvojnika)", listOf("add-to-list", "odd-icon"), r.dejanja.map { it.id })
    preveriEnako("naslov v jeziku uporabnika", "Dodaj na moj seznam", r.dejanja[0].naslov.za("sl-SI"))
    preveriEnako("neznan jezik: anglescina", "Add to my list", r.dejanja[0].naslov.za("hr"))
    preveriEnako("potrditev", true, r.dejanja[0].potrdi)
    preveriEnako("neznana ikona -> info", "info", r.dejanja[1].ikona)
    preveriEnako("besedilo ocisceno", "Two lines", r.dejanja[1].naslov.za("en"))
    preveriEnako("dejanja za film", listOf("add-to-list"), DodatkiProtokol.dejanjaZa(r, "movie").map { it.id })
    preveriEnako("dejanja za kanal", listOf("odd-icon"), DodatkiProtokol.dejanjaZa(r, "tv").map { it.id })
    preveriEnako("dopolni: znani tipi", setOf("movie", "series"), r.dopolni)
    preveriEnako("mape: veljavne, ikona folder", listOf("my-account" to "folder"), r.mape.map { it.id to it.ikona })
    preveriEnako("nastavitve: veljavne", listOf("api_key", "quality", "only_cached"), r.nastavitve.map { it.kljuc })
    preveriEnako("choice: neveljaven default -> prva moznost", "auto", r.nastavitve[1].privzeto)
    preveriEnako("toggle default", "true", r.nastavitve[2].privzeto)
    preveriEnako("ima skrivnosti", true, r.imaSkrivnosti)
    preveriEnako("opozorila za razvijalca", true, r.opozorila.any { it.contains("secret ne sme imeti default") } &&
        r.opozorila.any { it.contains("neznan type") } && r.opozorila.any { it.contains("neveljaven id") })

    val novejsi = DodatkiProtokol.preberi("""{"safeer":{"protocol":2,"actions":[{"id":"a","on":["movie"],"label":"A"}]}}""")!!
    preveriEnako("novejsi protokol: razsiritve prezrte", true to 0, novejsi.novejsi to novejsi.dejanja.size)
    preveriEnako("brez protokola: prezrto", 0, DodatkiProtokol.preberi("""{"safeer":{"actions":[]}}""")!!.protokol)
    val veliko = (1..12).joinToString(",") { """{"id":"a$it","on":["movie"],"label":"A"}""" }
    preveriEnako("najvec 8 dejanj", 8, DodatkiProtokol.preberi("""{"safeer":{"protocol":1,"actions":[$veliko]}}""")!!.dejanja.size)

    // Naslovi
    val o = "https://ex.test/cfg/"
    preveriEnako("naslov dejanja", "https://ex.test/cfg/safeer/action/add-to-list/movie/tt1%3A2.json", DodatkiProtokol.naslovDejanja(o, "add-to-list", "movie", "tt1:2"))
    preveriEnako("naslov dopolnitve", "https://ex.test/cfg/safeer/enrich/series/tt9.json", DodatkiProtokol.naslovDopolni(o, "series", "tt9"))
    preveriEnako("koren mape", "https://ex.test/cfg/safeer/folder/my-account.json", DodatkiProtokol.naslovMape(o, "my-account"))
    preveriEnako("pot mape brez ../", "https://ex.test/cfg/safeer/folder/my-account/movies/a%20b.json",
        DodatkiProtokol.naslovMape(o, "my-account", "movies/../a b"))

    // Nastavitve gredo v glavo, samo opisane
    val glava = DodatkiProtokol.glavaNastavitev(r.nastavitve, mapOf("api_key" to "k1", "tuje" to "x"))
    val json = String(java.util.Base64.getUrlDecoder().decode(glava), Charsets.UTF_8)
    preveriEnako("glava: opisane vrednosti in privzete", """{"api_key":"k1","quality":"auto","only_cached":true}""", json)
    preveriEnako("glava brez polnila", false, glava.contains("="))

    preveriEnako("124: skrivnost samo po https", listOf("api_key", "quality", "only_cached"),
        DodatkiProtokol.nastavitveZaZahtevo(r.nastavitve, "https://ex.test/stream/movie/tt1.json").map { it.kljuc })
    preveriEnako("124: po http brez skrivnosti", listOf("quality", "only_cached"),
        DodatkiProtokol.nastavitveZaZahtevo(r.nastavitve, "http://192.168.1.5:7000/stream/movie/tt1.json").map { it.kljuc })
    preveriEnako("124: HTTPS z velikimi crkami je https", 3, DodatkiProtokol.nastavitveZaZahtevo(r.nastavitve, "HTTPS://ex.test/x").size)
    preveriEnako("https sme", true, DodatkiProtokol.smePoslati("https://ex.test/m.json", razvojni = false))
    preveriEnako("http na internet ne", false, DodatkiProtokol.smePoslati("http://ex.test/m.json", razvojni = true))
    preveriEnako("http krajevno brez razvojnega ne", false, DodatkiProtokol.smePoslati("http://192.168.1.5:7000/m.json", razvojni = false))
    preveriEnako("http krajevno v razvojnem da", true, DodatkiProtokol.smePoslati("http://192.168.1.5:7000/m.json", razvojni = true))
    preveriEnako("localhost v razvojnem da", true, DodatkiProtokol.smePoslati("http://localhost:7000/m.json", razvojni = true))
    preveriEnako("172.32 ni krajevni", false, DodatkiProtokol.jeKrajevni("172.32.0.1"))
    preveriEnako("ime 10.x.ex.com ni krajevno", false, DodatkiProtokol.jeKrajevni("10.0.0.ex.com"))
    preveriEnako("ftp ne", false, DodatkiProtokol.smePoslati("ftp://ex.test/m.json", razvojni = true))

    // Naloga 125: dnevnik razvojnega nacina pove samo vrsto zahteve - nikoli poti (v njej je lahko kljuc storitve)
    preveriEnako("125: vrsta toka brez kljuca iz poti", "stream", DodatkiProtokol.vrstaZahteve("https://ex.test/realdebrid=SKRITO/stream/movie/tt1.json"))
    preveriEnako("125: manifest", "manifest", DodatkiProtokol.vrstaZahteve("https://ex.test/cfg/manifest.json?x=1"))
    preveriEnako("125: dejanje", "action", DodatkiProtokol.vrstaZahteve("https://ex.test/safeer/action/a/movie/tt1.json"))
    preveriEnako("125: mapa", "folder", DodatkiProtokol.vrstaZahteve("https://ex.test/safeer/folder/m.json"))
    preveriEnako("125: neznano", "drugo", DodatkiProtokol.vrstaZahteve("https://ex.test/x"))
    preveriEnako("125: kljuc ni v nobeni vrsti", false, listOf("stream", "manifest", "action", "folder", "drugo").any { it.contains("SKRITO") })

    // Odgovori
    val d = DodatkiProtokol.preberiDejanje("""{"ok":true,"message":{"en":"Added","sl":"Dodano"},"refresh":true}""")!!
    preveriEnako("odgovor dejanja", Triple(true, "Dodano", true), Triple(d.ok, d.sporocilo?.za("sl"), d.osvezi))
    preveriEnako("odgovor brez ok je uspeh", true, DodatkiProtokol.preberiDejanje("""{}""")!!.ok)
    preveriEnako("prevelik odgovor zavrnjen", null, DodatkiProtokol.preberiDejanje("{\"m\":\"" + "x".repeat(DodatkiProtokol.NAJVEC_ODGOVOR_B) + "\"}"))
    val mapa = DodatkiProtokol.preberiMapo("""{"items":[
      {"kind":"folder","path":"movies","name":"Movies"},
      {"kind":"meta","type":"movie","id":"tt1","name":"Film","poster":"http://ex.test/p.jpg"},
      {"kind":"meta","type":"movie","name":"No id"},
      {"kind":"script","name":"x"}],"next":"movies?page=2"}""")!!
    preveriEnako("mapa: veljavni elementi", listOf("folder", "meta"), mapa.elementi.map { it.vrsta })
    preveriEnako("plakat samo https", "", mapa.elementi[1].plakat)
    preveriEnako("naslednja stran", "movies?page=2", mapa.naprej)

    // Spremembe enkrat po posodobitvi
    preveriEnako("spremembe po posodobitvi", "Mape", DodatkiProtokol.spremembeZaPrikaz(r, "1.1.0", "1.2.0")?.za("sl"))
    preveriEnako("prva namestitev: brez sprememb", null, DodatkiProtokol.spremembeZaPrikaz(r, null, "1.2.0"))
    preveriEnako("ista verzija: brez", null, DodatkiProtokol.spremembeZaPrikaz(r, "1.2.0", "1.2.0"))

    // Pregled 25. 10. 2026 (D1): kljuc nastavitev ni vec 32-bitni hashCode - osnovi z enakim hashCode imata razlicen id.
    val a = "https://dodatek.example/Aa"; val b = "https://dodatek.example/BB"
    preveriEnako("osnovi imata isti stari hashCode (pogoj preizkusa)", a.hashCode(), b.hashCode())
    preveriEnako("trcenje hashCode: razlicen id osnove", false, DodatkiProtokol.idOsnove(a) == DodatkiProtokol.idOsnove(b))
    preveriEnako("id osnove je SHA-256 hex", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", DodatkiProtokol.idOsnove(""))
    preveriEnako("id osnove: 64 znakov", 64, DodatkiProtokol.idOsnove(a).length)
    preveriEnako("stari id = hex hashCode", Integer.toHexString(a.hashCode()), DodatkiProtokol.stariIdOsnove(a))
    preveriEnako("selitev: brez drugih dodatkov dovoljena", true, DodatkiProtokol.smeSeliti(a, listOf(a)))
    preveriEnako("selitev: drug dodatek brez trcenja", true, DodatkiProtokol.smeSeliti(a, listOf(a, "https://drug.example")))
    preveriEnako("selitev: trcenje z drugim namescenim dodatkom - ne", false, DodatkiProtokol.smeSeliti(a, listOf(a, b)))
    preveriEnako("selitev: trcenje velja v obe smeri", false, DodatkiProtokol.smeSeliti(b, listOf(b, a)))

    // D7: omejeno branje odgovora - nikoli vec kot meja + 1 bajt, Content-Length nad mejo takoj zavrnjen.
    class Stevec(val vsebina: ByteArray, val neskoncno: Boolean = false) : java.io.InputStream() {
        var prebrano = 0L
        override fun read(): Int {
            if (!neskoncno && prebrano >= vsebina.size) return -1
            prebrano++; return 'x'.code
        }
    }
    preveriEnako("omejeno: pod mejo prebrano", "xxxxx",
        DodatkiProtokol.preberiOmejenoBesedilo(Stevec(ByteArray(5)), -1, 10))
    preveriEnako("omejeno: tocno meja prebrano", 10, DodatkiProtokol.preberiOmejeno(Stevec(ByteArray(10)), 10, 10)?.size)
    preveriEnako("omejeno: meja + 1 zavrnjeno", null, DodatkiProtokol.preberiOmejeno(Stevec(ByteArray(11)), -1, 10))
    val neskoncen = Stevec(ByteArray(0), neskoncno = true)
    preveriEnako("omejeno: neskoncen tok zavrnjen", null, DodatkiProtokol.preberiOmejeno(neskoncen, -1, 1000))
    preveriEnako("omejeno: neskoncen tok prebran najvec do meje + 1", true, neskoncen.prebrano <= 1001)
    val zGlavo = Stevec(ByteArray(5))
    preveriEnako("omejeno: Content-Length nad mejo zavrnjen", null, DodatkiProtokol.preberiOmejeno(zGlavo, 2_000_000, 1000))
    preveriEnako("omejeno: Content-Length nad mejo - nic prebrano", 0L, zGlavo.prebrano)
    preveriEnako("omejeno: privzeta meja je 1 MB", 1024 * 1024,
        DodatkiProtokol.preberiOmejeno(Stevec(ByteArray(1024 * 1024)), -1)?.size)
    preveriEnako("omejeno: privzeta meja + 1 zavrnjeno", null, DodatkiProtokol.preberiOmejeno(Stevec(ByteArray(1024 * 1024 + 1)), -1))

    if (napak == 0) println("DodatkiProtokolTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
