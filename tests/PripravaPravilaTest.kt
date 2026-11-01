package si.safeer.tv.os

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

fun main() {
    println("Preizkus pravil za pripravo tokov (odpri takoj)")
    preveri("video, ni rocno -> da", PripravaPravila.odpriTakoj(true, false))
    preveri("zvok, ni rocno -> ne", !PripravaPravila.odpriTakoj(false, false))
    preveri("video, rocno -> ne", !PripravaPravila.odpriTakoj(true, true))
    preveri("zvok, rocno -> ne", !PripravaPravila.odpriTakoj(false, true))

    if (napak > 0) kotlin.system.exitProcess(1)
    println("PripravaPravilaTest: OK")
}
