package si.safeer.tv.os

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    preveri("$opis (pričakovano=$pricakovano, dobljeno=$dobljeno)", pricakovano == dobljeno)

fun main() {
    println("Preizkus skritih aplikacij")
    val moj = "si.safeer.os"
    preveriEnako("prazno besedilo = prazen niz", emptySet<String>(), SkriteAplikacijePravila.razcleni(null))
    preveriEnako("krog zapisa", setOf("a.b", "c.d"), SkriteAplikacijePravila.razcleni(SkriteAplikacijePravila.kodiraj(listOf("a.b", "c.d", "a.b"))))
    preveriEnako("prazne vrstice in presledki", setOf("a.b"), SkriteAplikacijePravila.razcleni(" a.b \n\n  "))
    preveriEnako("dodaj", setOf("a.b"), SkriteAplikacijePravila.dodaj(emptySet(), "a.b", moj))
    preveriEnako("zaganjalnika ni mogoce skriti", emptySet<String>(), SkriteAplikacijePravila.dodaj(emptySet(), moj, moj))
    preveriEnako("praznega paketa ni mogoce skriti", emptySet<String>(), SkriteAplikacijePravila.dodaj(emptySet(), " ", moj))
    preveriEnako("odstrani (pokazi znova)", setOf("c.d"), SkriteAplikacijePravila.odstrani(setOf("a.b", "c.d"), "a.b"))
    preveriEnako("odstrani neobstojeci", setOf("a.b"), SkriteAplikacijePravila.odstrani(setOf("a.b"), "x.y"))
    val vnosi = listOf("org.droidtv.aurora", "com.google.android.youtube.tv", "io.mimi.tvapp")
    preveriEnako("filter skrije izbrane", listOf("com.google.android.youtube.tv"),
        SkriteAplikacijePravila.filtriraj(vnosi, { it }, setOf("org.droidtv.aurora", "io.mimi.tvapp")))
    preveriEnako("filter brez skritih ne spremeni", vnosi, SkriteAplikacijePravila.filtriraj(vnosi, { it }, emptySet()))
    if (napak > 0) { println("NAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("VSE V REDU")
}
