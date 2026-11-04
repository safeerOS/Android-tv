package si.safeer.tv.scit

import si.safeer.tv.cast.JsonLahki
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress

/**
 * Scit proti skupnim primerom (tests/skupni-primeri/scit-pravila.json - ISTA datoteka v Safeer-linux, kjer jo preverja
 * tests/test_scit_skupni_primeri.py). Lastnik 4. 11. 2026: »ni optimalno delati vec Scitov za razlicne OS« - pravilo,
 * spremenjeno samo na eni strani, tu ali tam pade.
 *
 * Odlocitev je ScitOdlocitev.odloci - ISTA funkcija, ki jo klice ScitStoritev (UDP in TCP); tu dobi nabor iz skupnih
 * seznamov in ime iz prave poizvedbe DNS (DnsPaket.vprasanje).
 * Primer z »neujemanje« in »android« v »razlicno_na« je preskocen z izpisom; ce Android postane enak, preizkus pade.
 * Zagon: tests/run_scit_tests.sh (pot: argument ali tests/skupni-primeri/scit-pravila.json).
 */
private const val PLATFORMA = "android"
private const val DAN = 24L * 3600 * 1000
private const val T0 = 1_800_000_000_000L

private var napak = 0
private var preverjenih = 0
private var preskocenih = 0

private fun obj(s: String): JsonLahki.Pogled = JsonLahki.objekt(s) ?: error("ni objekt: ${s.take(80)}")
private fun seznam(s: String): List<String> = JsonLahki.elementi(s) ?: error("ni seznam: ${s.take(80)}")
private fun JsonLahki.Pogled.vsebina(k: String): String = surovo(k) ?: error("ni kljuca $k")

/** Poizvedba DNS, kot jo poslje aplikacija: oznake med pikami (prazne izpuscene), bajti UTF-8. */
private fun poizvedba(ime: String): ByteArray {
    val b = ByteArrayOutputStream()
    b.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
    for (o in ime.split('.').filter { it.isNotEmpty() }.map { it.toByteArray(Charsets.UTF_8) }) { b.write(o.size); b.write(o) }
    b.write(byteArrayOf(0, 0, 1, 0, 1))
    return b.toByteArray()
}

private class Okolje(seznami: JsonLahki.Pogled, mapa: File) {
    val nabor: DomenskiNabor.Poizvedba
    val grozne: DomenskiNabor.Poizvedba

    init {
        val oglasi = seznami.nizi("oglasi")
        val groznje = seznami.nizi("groznje")
        // Kot Scit na TV: domene.bin = vsi seznami, grozne.bin = samo groznje (izjema jih nikoli ne umakne).
        DomenskiNabor.zapisi(File(mapa, "domene.bin"), sequenceOf(oglasi.asSequence(), groznje.asSequence()))
        DomenskiNabor.zapisi(File(mapa, "grozne.bin"), sequenceOf(groznje.asSequence()))
        nabor = DomenskiNabor.Poizvedba.odpri(File(mapa, "domene.bin")) ?: error("nabor")
        grozne = DomenskiNabor.Poizvedba.odpri(File(mapa, "grozne.bin")) ?: error("grozne")
    }

    /** Kot ScitStoritev.odlocitev z nalozenim naborom grozenj; KANAREK je NXDOMAIN, torej ustavljeno. */
    fun ustavljeno(ime: String, uporabniske: Map<String, Long>, zdaj: Long): String {
        val q = DnsPaket.vprasanje(poizvedba(ime))?.ime.orEmpty()
        val izid = ScitOdlocitev.odloci(q, { nabor.jeBlokirana(it) }, true, { grozne.jeBlokirana(it) },
            { ScitIzjeme.dovoljena(it, emptyMap(), zdaj) || ScitPregled.uporabniskaVelja(it, uporabniske, zdaj) })
        return if (izid == ScitOdlocitev.Izid.NAPREJ) "spuscen" else "ustavljeno"
    }
}

private fun preveri(p: JsonLahki.Pogled, opis: String, pricakovano: Any?, dobljeno: Any?) {
    val neujemanje = p.niz("neujemanje")
    val na = p.surovo("razlicno_na")?.let { s -> seznam(s).mapNotNull { JsonLahki.niz(it) } } ?: listOf("android", "linux")
    if (neujemanje != null && PLATFORMA in na) {
        preskocenih++
        println("  PRESKOCENO (neujemanje) $opis: $neujemanje; Android da $dobljeno")
        if (pricakovano == dobljeno) { napak++; println("  NAPAKA  neujemanje odpravljeno - umakni oznako: $opis") }
        return
    }
    preverjenih++
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }
}

fun main(args: Array<String>) {
    println("Scit proti skupnim primerom (scit-pravila.json)")
    val pot = args.firstOrNull() ?: "tests/skupni-primeri/scit-pravila.json"
    val v = obj(File(pot).readText(Charsets.UTF_8))
    if (v.stevilo("verzija")?.toInt() != 2) { napak++; println("  NAPAKA  verzija ${v.stevilo("verzija")}") }
    // Seznami pravila v kodi so ISTI kot v skupnih primerih (in v core/os_scit.py).
    preverjenih++
    for ((opis, pricakovano, dobljeno) in listOf(
        Triple("krajevna", v.nizi("krajevna"), ScitOdlocitev.KRAJEVNA),
        Triple("krajevne_koncnice", v.nizi("krajevne_koncnice"), ScitOdlocitev.KRAJEVNE_KONCNICE),
        Triple("kanarek_doh", listOf(v.niz("kanarek_doh")), listOf(ScitOdlocitev.KANAREK_DOH)),
        Triple("zasciteni", v.nizi("zasciteni"), ScitOdlocitev.ZASCITENI),
        Triple("izjema_dni", listOf(v.stevilo("izjema_dni")?.toLong()?.times(24L * 3600 * 1000)), listOf(ScitPregled.IZJEMA_MS)))) {
        if (pricakovano != dobljeno) { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }
        else println("  ok  seznam $opis")
    }
    val mapa = kotlin.io.path.createTempDirectory("scit-skupni").toFile()
    try {
        val okolje = Okolje(obj(v.vsebina("seznami")), mapa)

        println("\n== ustavljeno ==")
        for (p in seznam(v.vsebina("ustavljeno")).map(::obj))
            preveri(p, p.niz("opis")!!, p.niz("izid"), okolje.ustavljeno(p.niz("ime")!!, emptyMap(), T0))

        println("\n== izjeme uporabnika ==")
        for (p in seznam(v.vsebina("izjeme")).map(::obj)) {
            val izjeme = ScitPregled.dodajIzjemo(emptyMap(), p.niz("izjema")!!, T0, true) { okolje.grozne.jeBlokirana(it) }
            val zdaj = T0 + p.stevilo("dan")!!.toLong() * DAN
            val dobljeno = (izjeme != null) to okolje.ustavljeno(p.niz("poizvedba")!!, izjeme ?: emptyMap(), zdaj)
            preveri(p, p.niz("opis")!!, p.logicno("sprejeta") to p.niz("izid"), dobljeno)
        }

        println("\n== razresevalci naprej ==")
        for (p in seznam(v.vsebina("razresevalci_naprej")).map(::obj)) {
            val krajevni = p.nizi("krajevni").map { InetAddress.getByName(it) }   // samo stevilski naslovi: brez poizvedbe DNS
            val izbrani = ScitPregled.naprej(krajevni)
            val izid = when {
                izbrani.isEmpty() -> if ((DnsPaket.odgovorNapakaZa(poizvedba("a.example"))[3].toInt() and 0x0F) == 2) "servfail" else "napacen odgovor"
                izbrani == krajevni -> "krajevni"
                else -> "tuj"
            }
            preveri(p, p.niz("opis")!!, p.niz("izid"), izid)
        }
    } finally {
        mapa.deleteRecursively()   // samo zacasna mapa tega preizkusa
    }
    println("\npreverjenih $preverjenih, preskocenih (neujemanje) $preskocenih")
    println(if (napak == 0) "VSE OK" else "NAPAK: $napak")
    if (napak > 0) kotlin.system.exitProcess(1)
}
