package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Naloga 42 F: beta novega TV zaganjalnika ([ZaganjalnikBetaPravila]). */
fun main() {
    println("Preizkus bete novega zaganjalnika")
    preveriEnako("brez bete: brez pripone", "", ZaganjalnikBetaPravila.pripona(null))
    preveriEnako("prazno: brez pripone", "", ZaganjalnikBetaPravila.pripona(" "))
    preveriEnako("beta 1", "-beta.1", ZaganjalnikBetaPravila.pripona("1"))
    preveriEnako("beta 12", "-beta.12", ZaganjalnikBetaPravila.pripona(" 12 "))
    preveriEnako("0 ni veljavna", null, ZaganjalnikBetaPravila.pripona("0"))
    preveriEnako("besedilo ni veljavno", null, ZaganjalnikBetaPravila.pripona("rc1"))
    preveriEnako("predolgo ni veljavno", null, ZaganjalnikBetaPravila.pripona("10000"))
    preveriEnako("stevilka iz imena", 1, ZaganjalnikBetaPravila.stevilkaBete("0.5.111-beta.1"))
    preveriEnako("stevilka s pripono tv nacina", 3, ZaganjalnikBetaPravila.stevilkaBete("0.5.111-tvnacin-beta.3"))
    preveriEnako("izdaja ni beta", 0, ZaganjalnikBetaPravila.stevilkaBete("0.5.110"))
    preveriEnako("beta.0 ni beta", 0, ZaganjalnikBetaPravila.stevilkaBete("0.5.110-beta.0"))

    println("\n== stikalo v Nastavitvah ==")
    preveriEnako("TV, beta gradnja", true, ZaganjalnikBetaPravila.stikaloVidno(true, true, false))
    preveriEnako("TV, izdaja, izklopljen: ni stikala", false, ZaganjalnikBetaPravila.stikaloVidno(true, false, false))
    preveriEnako("TV, izdaja, a vklopljen iz bete: stikalo za vrnitev", true, ZaganjalnikBetaPravila.stikaloVidno(true, false, true))
    preveriEnako("telefon: nikoli", false, ZaganjalnikBetaPravila.stikaloVidno(false, true, true))

    println("\n== vrnitev brez izgube nastavitev ==")
    val prej = mapOf("os_tema" to "nord", "ozadje" to "gore_foto", "vreme_kraj_ime" to "Ljubljana", "lupa_odprta" to true, "zaganjalnik_beta" to true)
    val potem = ZaganjalnikBetaPravila.poPreklopu(prej, false)
    preveriEnako("beta izklopljena", false, potem["zaganjalnik_beta"])
    preveriEnako("vse ostale nastavitve enake", prej - "zaganjalnik_beta", potem - "zaganjalnik_beta")
    preveriEnako("brez izbrane teme: stari zaganjalnik dobi prejsnjo privzeto", "namizje", PokrajinaPravila.tema(null, false))
    preveriEnako("izbrana tema ostane tudi po vrnitvi", "nord", PokrajinaPravila.tema(prej["os_tema"] as String, false))

    // Lastnik 27. 10. 2026: z beto na TV brez stranske vrstice
    preveriEnako("beta na TV: brez stranske vrstice", false, ZaganjalnikBetaPravila.stranskaVrstica(true, true))
    preveriEnako("TV brez bete: vrstica ostane", true, ZaganjalnikBetaPravila.stranskaVrstica(true, false))
    preveriEnako("dotik z beto: vrstica ostane", true, ZaganjalnikBetaPravila.stranskaVrstica(false, true))

    if (napak == 0) println("ZaganjalnikBetaPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
