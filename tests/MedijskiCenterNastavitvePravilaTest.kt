package si.safeer.tv.os

private var napak = 0
private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

private fun vlc0() = MedijskiCenterNastavitvePravila.vidneSkupine("si.safeer.player")

fun main(args: Array<String>) {
    println("Preizkus MedijskiCenterNastavitvePravila")
    
    val p = MedijskiCenterNastavitvePravila
    
    val os = p.vidneSkupine("si.safeer.tv")
    preveriEnako("TV OS ima 6 skupin", 6, os.size)
    preveriEnako("TV OS ima Domov", MedijskiCenterNastavitvePravila.Skupina.DOM, os[1].id)
    // Lastnik 13. 11. 2026: preskok uvoda je stikalo v Predvajanju, takoj za samodejno naslednjo epizodo.
    val predv = os.first { it.id == MedijskiCenterNastavitvePravila.Skupina.PREDVAJANJE }.postavke
    preveriEnako("preskok za samodejno epizodo", 1, predv.indexOf(MedijskiCenterNastavitvePravila.Postavka.PRESKOK_ODSEKOV))
    preveriEnako("preskok tudi v predvajalniku", true, vlc0().first { it.id == MedijskiCenterNastavitvePravila.Skupina.PREDVAJANJE }.postavke.contains(MedijskiCenterNastavitvePravila.Postavka.PRESKOK_ODSEKOV))
    
    val vlc = p.vidneSkupine("si.safeer.player")
    preveriEnako("Player ima 6 skupin", 6, vlc.size)
    preveriEnako("Player ima Domov", true, vlc.any { it.id == MedijskiCenterNastavitvePravila.Skupina.DOM })
    
    // Lastnik 19. 11. 2026: »Moji viri« je spremenil stran za oknom in se je zdelo, da se nic ne odzove.
    val Po = MedijskiCenterNastavitvePravila.Postavka
    for (x in Po.values()) {
        val pricakovano = x == Po.MOJI_VIRI || x == Po.SPLOSNE_NASTAVITVE
        preveriEnako("zapre okno po kliku: $x", pricakovano, p.zapreOknoPoKliku(x))
    }

    if (napak > 0) { println("NAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("vse ok")
}
