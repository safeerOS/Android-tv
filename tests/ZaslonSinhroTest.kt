package si.safeer.tv.os

/** Preizkus [ZaslonSinhro]: izracun odmika, mrtvo obmocje, omejitve, rocni zamik in glajenje z mediano. */
fun main() {
    // Konstante
    check(ZaslonSinhro.MRTVO_OBMOCJE_MS == 10L) { "mrtvo obmocje mora biti 10 ms" }
    check(ZaslonSinhro.NAJVEC_ZAMIK_MS == 400L) { "najvecji zamik mora biti 400 ms" }
    check(ZaslonSinhro.CILJ_MS == 20L) { "cilj mora biti 20 ms" }
    check(ZaslonSinhro.KORAK_ROCNO_MS == 10L) { "korak rocnega zamika mora biti 10 ms" }

    // Predznak odmika: pozitivno = slika naprej, negativno = zvok naprej.
    check(ZaslonSinhro.odmik(1050L, 1000L) == 50L) { "slika naprej mora biti pozitivna" }
    check(ZaslonSinhro.odmik(1000L, 1050L) == -50L) { "zvok naprej mora biti negativen" }
    check(ZaslonSinhro.odmik(2000L, 2000L) == 0L) { "enaka ziga pomenita nic odmika" }

    // Usklajenost na meji 19/20 ms v obe smeri.
    check(ZaslonSinhro.usklajen(0L)) { "0 ms je usklajen" }
    check(ZaslonSinhro.usklajen(19L)) { "19 ms je se usklajen" }
    check(ZaslonSinhro.usklajen(-19L)) { "-19 ms je se usklajen" }
    check(!ZaslonSinhro.usklajen(20L)) { "20 ms ni vec usklajen" }
    check(!ZaslonSinhro.usklajen(-20L)) { "-20 ms ni vec usklajen" }
    check(!ZaslonSinhro.usklajen(120L)) { "120 ms ni usklajen" }
    check(!ZaslonSinhro.usklajen(Long.MIN_VALUE)) { "skrajna negativna vrednost ni usklajena" }

    // Mrtvo obmocje: |ostanek| <= 10 -> 0, 11 -> 11, -11 -> -11.
    check(ZaslonSinhro.potrebnaZakasnitev(0L, 0L) == 0L) { "nic brez popravka" }
    check(ZaslonSinhro.potrebnaZakasnitev(10L, 0L) == 0L) { "10 ms je v mrtvem obmocju" }
    check(ZaslonSinhro.potrebnaZakasnitev(-10L, 0L) == 0L) { "-10 ms je v mrtvem obmocju" }
    check(ZaslonSinhro.potrebnaZakasnitev(11L, 0L) == 11L) { "11 ms je nad mrtvim obmocjem" }
    check(ZaslonSinhro.potrebnaZakasnitev(-11L, 0L) == -11L) { "-11 ms je pod mrtvim obmocjem" }

    // Omejitev rezultata na +-400 ms.
    check(ZaslonSinhro.potrebnaZakasnitev(400L, 0L) == 400L) { "zgornja meja 400 ms" }
    check(ZaslonSinhro.potrebnaZakasnitev(401L, 0L) == 400L) { "omejitev nad 400 ms" }
    check(ZaslonSinhro.potrebnaZakasnitev(950L, 0L) == 400L) { "velik pozitivni odmik omejen na 400 ms" }
    check(ZaslonSinhro.potrebnaZakasnitev(-400L, 0L) == -400L) { "spodnja meja -400 ms" }
    check(ZaslonSinhro.potrebnaZakasnitev(-401L, 0L) == -400L) { "omejitev pod -400 ms" }
    check(ZaslonSinhro.potrebnaZakasnitev(-950L, 0L) == -400L) { "velik negativni odmik omejen na -400 ms" }

    // Vpliv rocnega zamika zvoka.
    check(ZaslonSinhro.potrebnaZakasnitev(80L, 60L) == 20L) { "odmik 80, rocno 60 -> ostanek 20" }
    check(ZaslonSinhro.potrebnaZakasnitev(80L, 70L) == 0L) { "odmik 80, rocno 70 -> ostanek 10 v mrtvem obmocju" }
    check(ZaslonSinhro.potrebnaZakasnitev(80L, 120L) == -40L) { "odmik 80, rocno 120 -> zakasni zvok za 40 ms" }
    check(ZaslonSinhro.potrebnaZakasnitev(350L, -100L) == 400L) { "ostanek 450 omejen na 400" }
    check(ZaslonSinhro.potrebnaZakasnitev(-350L, 100L) == -400L) { "ostanek -450 omejen na -400" }

    // Omejitev rocnega zamika in korak gor/dol.
    check(ZaslonSinhro.omejiRocniZamik(0L) == 0L) { "rocni zamik 0" }
    check(ZaslonSinhro.omejiRocniZamik(500L) == 400L) { "rocni zamik omejen navzgor" }
    check(ZaslonSinhro.omejiRocniZamik(-500L) == -400L) { "rocni zamik omejen navzdol" }
    check(ZaslonSinhro.rocniKorak(0L, true) == 10L) { "korak gor iz 0" }
    check(ZaslonSinhro.rocniKorak(0L, false) == -10L) { "korak dol iz 0" }
    check(ZaslonSinhro.rocniKorak(390L, true) == 400L) { "korak gor do meje" }
    check(ZaslonSinhro.rocniKorak(400L, true) == 400L) { "korak gor ne preseze 400" }
    check(ZaslonSinhro.rocniKorak(395L, true) == 400L) { "korak gor iz 395 omejen na 400" }
    check(ZaslonSinhro.rocniKorak(-390L, false) == -400L) { "korak dol do meje" }
    check(ZaslonSinhro.rocniKorak(-400L, false) == -400L) { "korak dol ne preseze -400" }
    check(ZaslonSinhro.rocniKorak(-395L, false) == -400L) { "korak dol iz -395 omejen na -400" }

    // Glajenje: manj kot 5 meritev -> null.
    val glajenje = ZaslonSinhro.Glajenje()
    check(glajenje.mediana() == null) { "brez meritev je mediana null" }
    for (i in 1..4) {
        glajenje.dodaj(30L)
        check(glajenje.mediana() == null) { "po $i meritvah je mediana se null" }
    }
    glajenje.dodaj(30L)
    check(glajenje.mediana() == 30L) { "po 5 meritvah vrne mediano" }

    // Osamelec (skok 500 ms) ne sme spremeniti mediane.
    val brezSkoka = glajenje.mediana()
    glajenje.dodaj(500L)
    check(glajenje.mediana() == brezSkoka) { "en skok 500 ms ne sme premakniti mediane" }
    glajenje.dodaj(30L)
    check(glajenje.mediana() == 30L) { "po dodatni redni meritvi mediana ostane 30" }

    // Drsece okno zadnjih velikost meritev (stare vrednosti se izrinejo).
    val okno = ZaslonSinhro.Glajenje(velikost = 7)
    for (i in 1..7) okno.dodaj(10L)
    check(okno.mediana() == 10L) { "polno okno z vrednostjo 10" }
    okno.dodaj(500L)
    check(okno.mediana() == 10L) { "osamelec 500 ms v polnem oknu ne premakne mediane" }
    for (i in 1..7) okno.dodaj(90L)
    check(okno.mediana() == 90L) { "po 7 novih meritvah stare vrednosti izginejo" }

    // Ponastavitev izbrise stare meritve in spet zahteva vsaj 5 novih.
    okno.ponastavi()
    check(okno.mediana() == null) { "po ponastavitvi je mediana null" }
    for (i in 1..4) {
        okno.dodaj(-25L)
        check(okno.mediana() == null) { "po ponastavitvi in $i meritvah se vedno null" }
    }
    okno.dodaj(-25L)
    check(okno.mediana() == -25L) { "po ponastavitvi in 5 meritvah vrne novo mediano" }

    // Pregled Grok L1: okno manjse od NAJMANJ_MERITEV bi vedno vracalo null, zato ga zavrnemo ze ob izdelavi.
    val premajhnoZavrnjeno = try { ZaslonSinhro.Glajenje(3); false } catch (_: IllegalArgumentException) { true }
    check(premajhnoZavrnjeno) { "Glajenje(3) mora biti zavrnjeno" }
    check(ZaslonSinhro.Glajenje(ZaslonSinhro.NAJMANJ_MERITEV).mediana() == null) { "Glajenje(5) je veljavno" }

    println("ZaslonSinhroTest: OK")
}
