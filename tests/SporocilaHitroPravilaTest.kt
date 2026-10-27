package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Naloga 42 C: Sporocila na hitro na domacem zaslonu ([SporocilaHitroPravila]). Vsa sporocila so lazna. */
fun main() {
    println("Preizkus sporocil na hitro")
    val p = { id: String, cas: String, zadnje: String -> SporocilaHitroPravila.Pogovor(id, "safeer-link", "Naprava $id", zadnje, 0, cas) }
    val vsi = listOf(
        p("a", "2026-10-27T03:00:00+00:00", "Lazno sporocilo A"),
        p("b", "2026-10-27T05:00:00+00:00", "Lazno sporocilo B"),
        p("c", "2026-10-27T04:00:00+00:00", ""),
        p("d", "2026-10-27T01:00:00+00:00", "Lazno sporocilo D"),
        p("e", "2026-10-27T04:30:00+00:00", "Lazno sporocilo E"),
    )
    preveriEnako("najnovejsi trije z besedilom", listOf("b", "e", "a"), SporocilaHitroPravila.zadnja(vsi).map { it.id })
    preveriEnako("brez pogovorov prazno (ni nadomestka)", emptyList<SporocilaHitroPravila.Pogovor>(), SporocilaHitroPravila.zadnja(emptyList()))
    preveriEnako("prazen id odpade", listOf("b"), SporocilaHitroPravila.zadnja(listOf(p("", "9", "x"), p("b", "1", "y"))).map { it.id })

    println("\n== izsek ==")
    preveriEnako("vrstice in presledki strnjeni", "Prva vrstica druga", SporocilaHitroPravila.izsek("  Prva\nvrstica\t\tdruga "))
    preveriEnako("smer besedila (RLO) odstranjena", "abc def", SporocilaHitroPravila.izsek("abc‮def"))
    preveriEnako("skrajsano s tropicjem", "abcdefghi…", SporocilaHitroPravila.izsek("abcdefghijklmnop", 10))
    preveriEnako("kratko ostane", "kratko", SporocilaHitroPravila.izsek("kratko", 10))

    println("\n== dejanja ob fokusu ==")
    preveriEnako("Link, kopirano drugo: vsa tri", listOf("kopiraj", "odgovori", "poslji_kopirano"),
        SporocilaHitroPravila.dejanja("Lazno sporocilo", true, "Kopiran lazni naslov"))
    preveriEnako("odlozisce prazno: brez poslji kopirano", listOf("kopiraj", "odgovori"), SporocilaHitroPravila.dejanja("Lazno", true, "  "))
    preveriEnako("odlozisce je to isto sporocilo: brez poslji kopirano", listOf("kopiraj", "odgovori"),
        SporocilaHitroPravila.dejanja("Lazno sporocilo", true, " Lazno sporocilo "))
    preveriEnako("kanal brez odgovora s tega zaslona: samo kopiraj", listOf("kopiraj"), SporocilaHitroPravila.dejanja("Lazno", false, "drugo"))
    preveriEnako("prazno sporocilo: nic", emptyList<String>(), SporocilaHitroPravila.dejanja(" ", true, "x"))
    preveriEnako("za posiljanje obrezano", "zivjo", SporocilaHitroPravila.zaPosiljanje("  zivjo \n"))
    preveriEnako("prazno se ne poslje", null, SporocilaHitroPravila.zaPosiljanje("   "))
    preveriEnako("null se ne poslje", null, SporocilaHitroPravila.zaPosiljanje(null))
    preveriEnako("predolgo se ne poslje", null, SporocilaHitroPravila.zaPosiljanje("x".repeat(16 * 1024 + 1)))

    println("\n== opozorilo ne prekine predvajanja ==")
    preveriEnako("v ospredju, drug pogovor: opozorilo", true, SporocilaHitroPravila.opozorilo(true, false))
    preveriEnako("odprt ta pogovor: brez opozorila", false, SporocilaHitroPravila.opozorilo(true, true))
    preveriEnako("Safeer OS v ozadju: brez opozorila (sistemsko obvestilo)", false, SporocilaHitroPravila.opozorilo(false, false))
    preveriEnako("predvajanje se nikoli ne ustavi", false, SporocilaHitroPravila.ustaviPredvajanje())
    preveriEnako("opozorilo izgine samo", 6_000L, SporocilaHitroPravila.OPOZORILO_MS)

    // Lastnik 27. 10. 2026: tipkovnica odprta (vidni del okna pod 80 %)
    preveriEnako("tipkovnica cez pol zaslona: odprta", true, SporocilaHitroPravila.tipkovnicaOdprta(600, 1080))
    preveriEnako("brez tipkovnice: zaprta", false, SporocilaHitroPravila.tipkovnicaOdprta(1080, 1080))
    preveriEnako("sistemska vrstica (malo manj): zaprta", false, SporocilaHitroPravila.tipkovnicaOdprta(1000, 1080))
    preveriEnako("neznana visina: zaprta", false, SporocilaHitroPravila.tipkovnicaOdprta(0, 0))

    if (napak == 0) println("SporocilaHitroPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
