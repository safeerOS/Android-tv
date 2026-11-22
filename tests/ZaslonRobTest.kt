package si.safeer.tv.os

/** Preizkus [ZaslonRob]: omejevanje promilov, koraki, izracun sodega okvira, mere slike in kljuci. */
fun main() {
    // Konstanti po navodilih.
    check(ZaslonRob.NAJVEC_PROMILOV == 80) { "NAJVEC_PROMILOV mora biti 80" }
    check(ZaslonRob.KORAK == 5) { "KORAK mora biti 5" }

    // Omejitev na [0, 80].
    check(ZaslonRob.omeji(-100) == 0) { "negativno -> 0" }
    check(ZaslonRob.omeji(-1) == 0) { "-1 -> 0" }
    check(ZaslonRob.omeji(0) == 0) { "0 -> 0" }
    check(ZaslonRob.omeji(5) == 5) { "5 -> 5" }
    check(ZaslonRob.omeji(40) == 40) { "40 -> 40" }
    check(ZaslonRob.omeji(80) == 80) { "80 -> 80" }
    check(ZaslonRob.omeji(81) == 80) { "81 -> 80" }
    check(ZaslonRob.omeji(1000) == 80) { "1000 -> 80" }

    // Korak gor in dol znotraj in na mejah.
    check(ZaslonRob.zvecaj(0) == 5) { "zvecaj od 0" }
    check(ZaslonRob.zvecaj(75) == 80) { "zvecaj do zgornje meje" }
    check(ZaslonRob.zvecaj(78) == 80) { "zvecaj cez mejo se omeji na 80" }
    check(ZaslonRob.zvecaj(80) == 80) { "zvecaj na zgornji meji ostane 80" }
    check(ZaslonRob.zvecaj(120) == 80) { "zvecaj nad mejo ostane 80" }

    check(ZaslonRob.zmanjsaj(0) == 0) { "zmanjsaj na spodnji meji ostane 0" }
    check(ZaslonRob.zmanjsaj(3) == 0) { "zmanjsaj pod 0 se omeji na 0" }
    check(ZaslonRob.zmanjsaj(5) == 0) { "zmanjsaj do spodnje meje" }
    check(ZaslonRob.zmanjsaj(80) == 75) { "zmanjsaj od 80" }
    check(ZaslonRob.zmanjsaj(-20) == 0) { "zmanjsaj pod mejo ostane 0" }

    // Okvir za 3840x2160 pri 0, 5 in 80 promilov (rocni izracun):
    // - 0 promilov: [0, 0, 0, 0]
    // - 5 promilov: 3840*5/1000 = 19 -> sodo 18; 2160*5/1000 = 10 -> sodo 10 -> [18, 10, 18, 10]
    // - 80 promilov: 3840*80/1000 = 307 -> sodo 306; 2160*80/1000 = 172 -> sodo 172 -> [306, 172, 306, 172]
    check(ZaslonRob.okvir(3840, 2160, 0).contentEquals(intArrayOf(0, 0, 0, 0))) { "3840x2160 pri 0 promilov" }
    check(ZaslonRob.okvir(3840, 2160, 5).contentEquals(intArrayOf(18, 10, 18, 10))) { "3840x2160 pri 5 promilov" }
    check(ZaslonRob.okvir(3840, 2160, 80).contentEquals(intArrayOf(306, 172, 306, 172))) { "3840x2160 pri 80 promilov" }
    // Prevelik ali negativen promil se omeji enako kot 80 oziroma 0.
    check(ZaslonRob.okvir(3840, 2160, -10).contentEquals(intArrayOf(0, 0, 0, 0))) { "negativen promil -> nicelni okvir" }
    check(ZaslonRob.okvir(3840, 2160, 150).contentEquals(intArrayOf(306, 172, 306, 172))) { "promil nad 80 se omeji na 80" }

    // Lihe mere (1921x1081): vse stiri vrednosti morajo biti vedno sode in simetricne.
    // 5 promilov: 1921*5/1000 = 9 -> 8; 1081*5/1000 = 5 -> 4 -> [8, 4, 8, 4]
    // 80 promilov: 1921*80/1000 = 153 -> 152; 1081*80/1000 = 86 -> 86 -> [152, 86, 152, 86]
    check(ZaslonRob.okvir(1921, 1081, 5).contentEquals(intArrayOf(8, 4, 8, 4))) { "1921x1081 pri 5 promilov" }
    check(ZaslonRob.okvir(1921, 1081, 80).contentEquals(intArrayOf(152, 86, 152, 86))) { "1921x1081 pri 80 promilov" }
    for (p in 0..80) {
        val o = ZaslonRob.okvir(1921, 1081, p)
        check(o.size == 4) { "okvir ima 4 vrednosti pri p=$p" }
        check(o[0] == o[2] && o[1] == o[3]) { "simetricen okvir pri p=$p" }
        for (v in o) {
            check(v >= 0 && v % 2 == 0) { "vedno nenegativno sodo stevilo pikslov pri p=$p, dobljeno=$v" }
        }
    }

    // Nesmiselne in nepozitivne mere -> [0, 0, 0, 0].
    val nic = intArrayOf(0, 0, 0, 0)
    check(ZaslonRob.okvir(0, 0, 40).contentEquals(nic)) { "0x0" }
    check(ZaslonRob.okvir(0, 2160, 40).contentEquals(nic)) { "sirina 0" }
    check(ZaslonRob.okvir(3840, 0, 40).contentEquals(nic)) { "visina 0" }
    check(ZaslonRob.okvir(-3840, 2160, 40).contentEquals(nic)) { "negativna sirina" }
    check(ZaslonRob.okvir(3840, -2160, 40).contentEquals(nic)) { "negativna visina" }
    check(ZaslonRob.okvir(-1, -1, 80).contentEquals(nic)) { "obe negativni" }

    // Velikost slike znotraj roba: nikoli manj od 2x2.
    check(ZaslonRob.velikostSlike(3840, 2160, 0) == (3840 to 2160)) { "3840x2160 brez roba" }
    check(ZaslonRob.velikostSlike(3840, 2160, 5) == (3804 to 2140)) { "3840x2160 pri 5 promilov" }
    check(ZaslonRob.velikostSlike(3840, 2160, 80) == (3228 to 1816)) { "3840x2160 pri 80 promilov" }
    check(ZaslonRob.velikostSlike(1921, 1081, 5) == (1905 to 1073)) { "1921x1081 pri 5 promilov" }
    check(ZaslonRob.velikostSlike(2, 2, 80) == (2 to 2)) { "2x2 ostane 2x2" }
    check(ZaslonRob.velikostSlike(1, 1, 80) == (2 to 2)) { "1x1 se dvigne na 2x2" }
    check(ZaslonRob.velikostSlike(0, 0, 40) == (2 to 2)) { "0x0 nikoli pod 2x2" }
    check(ZaslonRob.velikostSlike(-100, -50, 80) == (2 to 2)) { "negativne mere nikoli pod 2x2" }
    check(ZaslonRob.velikostSlike(1, 100, 0) == (2 to 100)) { "ozka sirina nikoli pod 2" }
    check(ZaslonRob.velikostSlike(100, 1, 0) == (100 to 2)) { "nizka visina nikoli pod 2" }

    // Kljuc za shranjevanje: samo alfanumericni ASCII znaki, sicer privzeto.
    check(ZaslonRob.kljuc("3840x2160") == "zaslon_rob_3840x2160") { "kljuc za 3840x2160" }
    check(ZaslonRob.kljuc("1920x1080") == "zaslon_rob_1920x1080") { "kljuc za 1920x1080" }
    check(ZaslonRob.kljuc("PhilipsTV50") == "zaslon_rob_PhilipsTV50") { "kljuc z alfanumericnim imenom" }
    check(ZaslonRob.kljuc("") == "zaslon_rob_privzeto") { "prazen niz -> privzeto" }
    check(ZaslonRob.kljuc(" ") == "zaslon_rob_privzeto") { "samo presledek -> privzeto" }
    check(ZaslonRob.kljuc("3840 x 2160") == "zaslon_rob_privzeto") { "presledek v nizu -> privzeto" }
    check(ZaslonRob.kljuc("3840/2160") == "zaslon_rob_privzeto") { "posevnica / -> privzeto" }
    check(ZaslonRob.kljuc("../etc/passwd") == "zaslon_rob_privzeto") { "pot z / in . -> privzeto" }
    check(ZaslonRob.kljuc("3840_2160") == "zaslon_rob_privzeto") { "podcrtaj -> privzeto" }
    check(ZaslonRob.kljuc("3840-2160") == "zaslon_rob_privzeto") { "vezaj -> privzeto" }
    check(ZaslonRob.kljuc("tv\u010d1") == "zaslon_rob_privzeto") { "ne-ASCII crka -> privzeto" }

    println("ZaslonRobTest: OK")
}
