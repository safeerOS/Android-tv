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
    preveri("video, ni rocno, je serija -> ne", !PripravaPravila.odpriTakoj(true, false, true))

    println("Preizkus pravil ostaniVPripravi in cas")
    preveri("enak zagon, pod 45s -> ostani", PripravaPravila.ostaniVPripravi(5, 5, 10_000L))
    preveri("nov zagon, pod 45s -> ne ostani", !PripravaPravila.ostaniVPripravi(5, 6, 10_000L))
    preveri("enak zagon, nad 45s -> ne ostani", !PripravaPravila.ostaniVPripravi(5, 5, 46_000L))
    preveri("potekel cas -> da", PripravaPravila.jePotekelCas(45_000L))
    preveri("ni potekel cas -> ne", !PripravaPravila.jePotekelCas(44_999L))

    if (napak > 0) kotlin.system.exitProcess(1)
    println("PripravaPravilaTest: OK")
}
