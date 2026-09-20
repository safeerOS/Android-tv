package si.safeer.tv.cast

private var napak = 0
private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

fun main() {
    println("Izvolitev huba")
    val tv = IzvolitevHuba.Kandidat("tv-dnevna", IzvolitevHuba.PRIORITETA_TV)
    val tablica = IzvolitevHuba.Kandidat("tv-sm-x210", IzvolitevHuba.PRIORITETA_TABLICA)
    val telefon = IzvolitevHuba.Kandidat("phone-a", IzvolitevHuba.PRIORITETA_TELEFON)
    val linux = IzvolitevHuba.Kandidat("pc-janez", IzvolitevHuba.PRIORITETA_LINUX)

    preveri("privzete prioritete", IzvolitevHuba.privzetaPrioriteta("tv") == 60 && IzvolitevHuba.privzetaPrioriteta("tablet") == 40
        && IzvolitevHuba.privzetaPrioriteta("phone") == 20 && IzvolitevHuba.privzetaPrioriteta("linux") == 80 && IzvolitevHuba.privzetaPrioriteta("server") == 100)
    preveri("neznana platforma je najnizja", IzvolitevHuba.privzetaPrioriteta("x") == 20)
    preveri("TV je pred tablico", IzvolitevHuba.jePred(tv, tablica) && !IzvolitevHuba.jePred(tablica, tv))
    preveri("Linux je pred TV", IzvolitevHuba.jePred(linux, tv))
    preveri("najboljsi med vsemi je Linux", IzvolitevHuba.najboljsi(listOf(telefon, tv, tablica, linux)) == linux)
    preveri("brez kandidatov ni najboljsega", IzvolitevHuba.najboljsi(emptyList()) == null)
    // Enaka prioriteta: manjsi id.
    val tv2 = IzvolitevHuba.Kandidat("tv-kuhinja", IzvolitevHuba.PRIORITETA_TV)
    preveri("pri enaki prioriteti odloci manjsi id", IzvolitevHuba.najboljsi(listOf(tv, tv2)) == tv && IzvolitevHuba.najboljsi(listOf(tv2, tv)) == tv)
    preveri("isti id ni pred samim sabo", !IzvolitevHuba.jePred(tv, tv.copy(prioriteta = 99)))
    // Odlocitev.
    preveri("TV ob tablici in telefonu gosti sam", IzvolitevHuba.komuSeUmaknem(tv, listOf(tablica, telefon)) == null)
    preveri("tablica se umakne TV-ju", IzvolitevHuba.komuSeUmaknem(tablica, listOf(tv, telefon)) == tv)
    preveri("TV se umakne Linuxu", IzvolitevHuba.komuSeUmaknem(tv, listOf(linux, tablica)) == linux)
    preveri("sam sebe ne stejem", IzvolitevHuba.komuSeUmaknem(tv, listOf(tv.copy(prioriteta = 100))) == null)
    preveri("brez videnih gostim sam", IzvolitevHuba.komuSeUmaknem(tablica, emptyList()) == null)
    // Vrstni red vhoda ne spremeni izida.
    val a = IzvolitevHuba.najboljsi(listOf(tv, tablica, linux, telefon)); val b = IzvolitevHuba.najboljsi(listOf(telefon, linux, tablica, tv))
    preveri("izid ni odvisen od vrstnega reda", a == b && a == linux)
    // Oglas.
    preveri("prio iz oglasa", IzvolitevHuba.prioritetaIzOglasa("60") == 60 && IzvolitevHuba.prioritetaIzOglasa(null) == 0
        && IzvolitevHuba.prioritetaIzOglasa("x") == 0 && IzvolitevHuba.prioritetaIzOglasa("5000") == 1000)
    println(if (napak == 0) "\nVse v redu." else "\nNapak: $napak")
    if (napak > 0) System.exit(1)
}
