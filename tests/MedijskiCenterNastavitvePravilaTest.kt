package si.safeer.tv.os

private var napak = 0
private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

fun main(args: Array<String>) {
    println("Preizkus MedijskiCenterNastavitvePravila")
    
    val p = MedijskiCenterNastavitvePravila
    
    val os = p.vidneSkupine("si.safeer.tv")
    preveriEnako("TV OS ima 6 skupin", 6, os.size)
    preveriEnako("TV OS ima Domov", MedijskiCenterNastavitvePravila.Skupina.DOM, os[1].id)
    
    val vlc = p.vidneSkupine("si.safeer.player")
    preveriEnako("Player ima 5 skupin", 5, vlc.size)
    preveriEnako("Player nima Domov", false, vlc.any { it.id == MedijskiCenterNastavitvePravila.Skupina.DOM })
    
    if (napak > 0) { println("NAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("vse ok")
}
