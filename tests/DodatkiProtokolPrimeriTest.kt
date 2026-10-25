package si.safeer.tv.sdk

import si.safeer.tv.cast.JsonLahki
import si.safeer.tv.os.DodatkiProtokol
import java.io.File

/**
 * Skupni primeri protokola dodatkov v1 (sdk/skupni-primeri/protokol-v1.json): ISTA koda kot aplikacija
 * (DodatkiProtokol) mora dati enake povzetke kot Python SDK (sdk/python/safeer_addon_test.py, enaka oblika povzetkov).
 * Tako orodje za preverjanje v Pythonu in naprava ne moreta soditi razlicno. Ista datoteka primerov je v Safeer-linux in
 * Safeer-windows (sdk/SKUPNO.sha256).
 *
 *   tests/run_dodatki_sdk_tests.sh
 */
private var napak = 0
private var primerov = 0

private fun enako(opis: String, pricakovano: Any?, dobljeno: Any?) {
    primerov++
    if (pricakovano != dobljeno) { napak++; println("  NAPAKA  $opis:\n    pricakovano $pricakovano\n    dobljeno    $dobljeno") }
}

private fun seznam(p: JsonLahki.Pogled, kljuc: String): List<JsonLahki.Pogled> =
    p.surovo(kljuc)?.let { JsonLahki.elementi(it) }.orEmpty().mapNotNull { JsonLahki.objekt(it) }

private fun povzetek(r: DodatkiProtokol.Razsiritev?): String {
    if (r == null) return "none"
    val a = r.dejanja.joinToString("|") {
        "${it.id}[${it.na.sorted().joinToString(",")}]${it.ikona}/${if (it.potrdi) 1 else 0}/${it.naslov.za("sl")}"
    }
    val f = r.mape.joinToString("|") { "${it.id}/${it.ikona}/${it.ime.za("de")}" }
    val s = r.nastavitve.joinToString("|") {
        "${it.kljuc}/${it.vrsta}/${if (it.obvezna) 1 else 0}/${it.privzeto}/${it.moznosti.joinToString(",")}"
    }
    return "p=${r.protokol};new=${if (r.novejsi) 1 else 0};a=$a;e=${r.dopolni.sorted().joinToString(",")};f=$f;s=$s;" +
        "c=${r.spremembe.keys.sorted().joinToString(",")};w=${r.opozorila.size}"
}

fun main(args: Array<String>) {
    val pot = args.firstOrNull() ?: "sdk/skupni-primeri/protokol-v1.json"
    val v = JsonLahki.objekt(File(pot).readText(Charsets.UTF_8)) ?: error("ni mogoce prebrati $pot")
    println("Skupni primeri protokola dodatkov v1 ($pot)")
    enako("verzija protokola", v.stevilo("verzija_protokola")?.toInt(), DodatkiProtokol.VERZIJA)

    for (c in seznam(v, "razsiritev"))
        enako("razsiritev: ${c.nizAli("ime")}", c.nizAli("povzetek"), povzetek(DodatkiProtokol.preberi(c.surovo("manifest")!!)))

    for (c in seznam(v, "glava")) {
        val r = DodatkiProtokol.preberi(c.surovo("manifest")!!)!!
        val vr = c.objekt("vrednosti")!!.let { o -> o.kljuci().associateWith { o.nizAli(it) } }
        val glava = DodatkiProtokol.glavaNastavitev(DodatkiProtokol.nastavitveZaZahtevo(r.nastavitve, c.nizAli("url")), vr)
        enako("glava: ${c.nizAli("ime")}", c.nizAli("json"), String(java.util.Base64.getUrlDecoder().decode(glava), Charsets.UTF_8))
        enako("glava brez polnila: ${c.nizAli("ime")}", false, glava.contains('='))
    }

    for (c in seznam(v, "sme_poslati"))
        enako("smePoslati ${c.nizAli("url")} razvojni=${c.logicno("razvojni")}", c.logicno("pricakovano"),
            DodatkiProtokol.smePoslati(c.nizAli("url"), c.logicno("razvojni") == true))
    for (c in seznam(v, "krajevni"))
        enako("jeKrajevni ${c.nizAli("gostitelj")}", c.logicno("pricakovano"), DodatkiProtokol.jeKrajevni(c.nizAli("gostitelj")))

    for (c in seznam(v, "naslovi")) {
        val dobljeno = when (c.nizAli("vrsta")) {
            "dejanje" -> DodatkiProtokol.naslovDejanja(c.nizAli("osnova"), c.nizAli("dejanje"), c.nizAli("tip"), c.nizAli("id"))
            "dopolni" -> DodatkiProtokol.naslovDopolni(c.nizAli("osnova"), c.nizAli("tip"), c.nizAli("id"))
            else -> DodatkiProtokol.naslovMape(c.nizAli("osnova"), c.nizAli("mapa"), c.nizAli("pot"))
        }
        enako("naslov ${c.nizAli("vrsta")}", c.nizAli("pricakovano"), dobljeno)
    }

    for (c in seznam(v, "mapa")) {
        val m = DodatkiProtokol.preberiMapo(c.surovo("odgovor")!!)
        val p = m?.let { r -> r.elementi.joinToString("|") { "${it.vrsta}/${it.pot}/${it.tip}/${it.id}/${it.ime}/${it.plakat}" } + ";next=" + r.naprej } ?: "none"
        enako("mapa", c.nizAli("povzetek"), p)
    }
    for (c in seznam(v, "dejanje")) {
        val d = DodatkiProtokol.preberiDejanje(c.surovo("odgovor")!!)
        val p = d?.let { "ok=${if (it.ok) 1 else 0};msg=${it.sporocilo?.za("sl").orEmpty()};refresh=${if (it.osvezi) 1 else 0}" } ?: "none"
        enako("dejanje", c.nizAli("povzetek"), p)
    }

    val sp = v.objekt("spremembe")!!
    val r = DodatkiProtokol.preberi(sp.surovo("manifest")!!)!!
    for (c in seznam(sp, "primeri"))
        enako("spremembe ${c.niz("prejsnja")} -> ${c.nizAli("zdaj")}", c.niz("pricakovano"),
            DodatkiProtokol.spremembeZaPrikaz(r, c.niz("prejsnja"), c.nizAli("zdaj"))?.za("en"))

    if (primerov < 40) { napak++; println("  NAPAKA  premalo primerov: $primerov (datoteka primerov ni prebrana v celoti?)") }
    if (napak == 0) println("DodatkiProtokolPrimeriTest: OK ($primerov primerov)") else { println("Napak: $napak od $primerov"); System.exit(1) }
}
