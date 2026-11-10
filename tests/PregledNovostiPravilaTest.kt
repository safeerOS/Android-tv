package si.safeer.tv.os

import si.safeer.tv.os.PregledNovostiPravila.Izid
import si.safeer.tv.os.PregledNovostiPravila.Stanje

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Preizkus pravil pregleda novosti v ozadju. */
fun main() {
    println("Preizkus pravil za pregled novosti")
    
    fun nV(zadnjiPoln: Long, zadnjiPoskus: Long, zdaj: Long, predvaja: Boolean, omrezje: Boolean, pritisk: Boolean) =
        PregledNovostiPravila.naVrsti(zadnjiPoln, zadnjiPoskus, zdaj, predvaja, omrezje, pritisk)
    val zdaj = 100000000L
    val sestUr = 6 * 60 * 60 * 1000L
    val polUre = 30 * 60 * 1000L
    
    preveriEnako("med predvajanjem ne", false, nV(zdaj - sestUr, zdaj - polUre, zdaj, predvaja = true, omrezje = true, pritisk = false))
    preveriEnako("brez omrezja ne", false, nV(zdaj - sestUr, zdaj - polUre, zdaj, predvaja = false, omrezje = false, pritisk = false))
    preveriEnako("ob pritisku ne", false, nV(zdaj - sestUr, zdaj - polUre, zdaj, predvaja = false, omrezje = true, pritisk = true))
    preveriEnako("prvic (0, 0) da", true, nV(0L, 0L, zdaj, predvaja = false, omrezje = true, pritisk = false))
    preveriEnako("poln pred 5 h ne", false, nV(zdaj - sestUr + 1000L, zdaj - polUre, zdaj, predvaja = false, omrezje = true, pritisk = false))
    preveriEnako("poln pred 6 h da", true, nV(zdaj - sestUr, zdaj - polUre, zdaj, predvaja = false, omrezje = true, pritisk = false))
    preveriEnako("poskus pred 10 min ne (ceprav je poln star 7 h)", false, nV(zdaj - 7 * 60 * 60 * 1000L, zdaj - 10 * 60 * 1000L, zdaj, predvaja = false, omrezje = true, pritisk = false))
    preveriEnako("poln se nikoli, poskus pred 1 min -> ne (razmik med poskusi velja tudi brez polnega)", false, nV(0L, zdaj - 60_000L, zdaj, false, true, false))
    preveriEnako("poln se nikoli, poskus pred 31 min -> da", true, nV(0L, zdaj - 31 * 60_000L, zdaj, false, true, false))
    preveriEnako("ura nazaj da", true, nV(zdaj + 1000L, zdaj - polUre, zdaj, predvaja = false, omrezje = true, pritisk = false))

    val k = PregledNovostiPravila::kandidati
    val katalog1 = listOf("a", "b", "c", "b", "", "d")
    val znani = setOf("b")
    preveriEnako("znani izpadejo, ponovitve in prazni izpadejo, vrstni red kataloga ostane", listOf("a", "c", "d"), k(katalog1, znani, 40))
    val katalog100 = (1..100).map { "k$it" }
    preveriEnako("meja 40 (katalog 100 novih -> 40)", 40, k(katalog100, emptySet(), 40).size)

    val ns = PregledNovostiPravila::novoStanje
    val prej = setOf("star", "obstaja")
    val izidi = mapOf("nov" to Izid.NA_VOLJO, "star" to Izid.NI, "obstaja" to Izid.NE_VEMO, "neznan" to Izid.NE_VEMO)
    preveriEnako("NE_VEMO ne odstrani obstojecega in ne doda novega; NI odstrani; NA_VOLJO doda", setOf("obstaja", "nov"), ns(prej, izidi))

    val nov = PregledNovostiPravila::novosti
    val potem = setOf("a", "b", "c")
    preveriEnako("prvic -> prazno", emptyList<String>(), nov(setOf("a"), potem, true))
    preveriEnako("drugic -> samo res novi, po abecedi", listOf("b", "c"), nov(setOf("a"), potem, false))

    val izg = PregledNovostiPravila::izginuli
    preveriEnako("samo potrjen NI; NE_VEMO ne", setOf("star"), izg(prej, izidi))

    val obv = PregledNovostiPravila::obvestilo
    preveriEnako("0 novih -> null", null, obv(emptyList(), 3))
    preveriEnako("2 nova -> oba, seVec 0", PregledNovostiPravila.Obvestilo(listOf("a", "b"), 0), obv(listOf("a", "b"), 3))
    preveriEnako("5 novih -> prvi 3, seVec 2", PregledNovostiPravila.Obvestilo(listOf("a", "b", "c"), 2), obv(listOf("a", "b", "c", "d", "e"), 3))

    val vn = PregledNovostiPravila::vNiz
    val izn = PregledNovostiPravila::izNiza
    val s1 = Stanje(100L, 200L, setOf("movie|tt123:1:2", "a;b", "navaden"), false)
    preveriEnako("vNiz -> izNiza vrne enako (tudi s kljuci »movie|tt123:1:2« in »a;b«)", s1, izn(vn(s1)))
    preveriEnako("pokvarjen niz -> privzeto", Stanje(), izn("neveljaven\nxyz"))
    
    val kljuci600 = (1..600).map { String.format("k%03d", it) }.toSet()
    val s2 = Stanje(100L, 200L, kljuci600, false)
    val omejeno = izn(vn(s2))
    preveriEnako("600 kljucev -> 500 (velikost)", 500, omejeno.naVoljo.size)
    preveriEnako("600 kljucev -> 500 (pravi elementi po abecedi)", kljuci600.sorted().takeLast(500).toSet(), omejeno.naVoljo)

    if (napak > 0) { println("NAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("PregledNovostiPravilaTest OK")
}
