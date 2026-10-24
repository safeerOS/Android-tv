package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Naloga 135: vstop samodejnega preizkusa po gradnji ([PreizkusPravila]). */
fun main() {
    println("Preizkus pravil preizkusa po gradnji")
    preveriEnako("film", PreizkusPravila.Vnos("movie", "tt1375666", 0, 0, "Preizkus film"), PreizkusPravila.vnos("tt1375666", "Preizkus film"))
    preveriEnako("epizoda", PreizkusPravila.Vnos("series", "tt0108778", 1, 1, "Preizkus serija"), PreizkusPravila.vnos(" tt0108778:1:1 ", "Preizkus serija"))
    preveriEnako("brez naslova", "Preizkus", PreizkusPravila.vnos("tt1375666", null)?.naslov)
    preveriEnako("naslov brez nadzornih znakov in obrezan", "ab" + "x".repeat(78), PreizkusPravila.vnos("tt1375666", "a\nb" + "x".repeat(200))?.naslov)
    preveriEnako("tmdb ni dovoljen", null, PreizkusPravila.vnos("tmdb:1396", "x"))
    preveriEnako("povezava ni dovoljena", null, PreizkusPravila.vnos("https://primer/x", "x"))
    preveriEnako("prazno", null, PreizkusPravila.vnos(null, "x"))
    preveriEnako("napacna epizoda", null, PreizkusPravila.vnos("tt0108778:x:1", "x"))
    preveriEnako("oznaka v id-ju", true, PreizkusPravila.jePreizkus("stremio:series|tt0108778|osnova#preizkus#123"))
    preveriEnako("navaden id ni preizkus", false, PreizkusPravila.jePreizkus("stremio:series|tt0108778|osnova#123"))
    if (napak == 0) println("PreizkusPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
