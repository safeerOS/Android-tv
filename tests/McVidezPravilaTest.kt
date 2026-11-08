package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Videz Doma MC na TV ([McVidezPravila]): stolpci, fokus, naslovi seznamov, monogram postaje, ploscica dejanja, pomoc. */
fun main() {
    println("Preizkus videza Doma MC (McVidezPravila)")
    val P = McVidezPravila
    val T = McVidezPravila.Tip

    // Vrste Doma: Nadaljuj z ogledom (siroka), glasba in seznami (kvadrati), plakati; Nedavno (mala) in kanali ostanejo.
    preveriEnako("nadaljuj -> NADALJUJ", T.NADALJUJ, P.tipVrste(nadaljuj = true, skladbe = false, video = true, mala = false, kanali = false, slicice = false, cisto = false))
    preveriEnako("skladbe -> KVADRAT", T.KVADRAT, P.tipVrste(nadaljuj = false, skladbe = true, video = false, mala = false, kanali = false, slicice = false, cisto = false))
    preveriEnako("video -> PLAKAT", T.PLAKAT, P.tipVrste(nadaljuj = false, skladbe = false, video = true, mala = false, kanali = false, slicice = false, cisto = false))
    preveriEnako("mala (Nedavno) -> DRUGO", T.DRUGO, P.tipVrste(nadaljuj = false, skladbe = false, video = true, mala = true, kanali = false, slicice = false, cisto = false))
    preveriEnako("kanali -> DRUGO", T.DRUGO, P.tipVrste(nadaljuj = false, skladbe = false, video = false, mala = false, kanali = true, slicice = false, cisto = false))

    // TV 8. 11. 2026: prej 3 / 8 / 6 v vrsto.
    preveriEnako("nadaljuj 4 v vrsto (prej 3)", 4, P.stolpciDoma(T.NADALJUJ, 3))
    preveriEnako("kvadrati 7 v vrsto (prej 8)", 7, P.stolpciDoma(T.KVADRAT, 8))
    preveriEnako("plakati 6 ostane 6", 6, P.stolpciDoma(T.PLAKAT, 6))
    preveriEnako("plakati 7 ostane 7", 7, P.stolpciDoma(T.PLAKAT, 7))
    preveriEnako("plakati 9 -> 7", 7, P.stolpciDoma(T.PLAKAT, 9))
    preveriEnako("plakati 4 -> 6", 6, P.stolpciDoma(T.PLAKAT, 4))
    preveriEnako("drugo: mere ostanejo", null, P.stolpciDoma(T.DRUGO, 5))
    preveriEnako("razmik 16 dp", 16, P.RAZMIK_DOMA_DP)
    // 880 dp vsebine: 4 kartice po 192 + 12 rob + 16 razmik = 880.
    preveriEnako("sirina kartice 880/4", 192, P.sirinaKartice(880, 4))
    preveriEnako("sirina kartice 880/7", 97, P.sirinaKartice(880, 7))
    preveriEnako("4 kartice gredo v vrsto", true, 4 * (P.sirinaKartice(880, 4) + P.NOTRANJI_ROB_DP + P.RAZMIK_DOMA_DP) <= 880)
    preveriEnako("sirina 0 stolpcev ne deli z 0", 852, P.sirinaKartice(880, 0))
    preveriEnako("sirina vsaj 1", 1, P.sirinaKartice(10, 7))

    // Fokus: 1.04 z obrobo; zmanjsano gibanje samo obroba.
    preveriEnako("fokus 1.04", 1.04f, P.povecavaFokusa(fokus = true, zmanjsanoGibanje = false))
    preveriEnako("fokus ob zmanjsanem gibanju 1.0", 1f, P.povecavaFokusa(fokus = true, zmanjsanoGibanje = true))
    preveriEnako("brez fokusa 1.0", 1f, P.povecavaFokusa(fokus = false, zmanjsanoGibanje = false))
    preveriEnako("trajanje 120 ms", 120L, P.TRAJANJE_FOKUSA_MS)

    // Seznami z imenom racuna: dva »Oglej si pozneje« se locita po podnaslovu.
    preveriEnako("razdeli ime z racunom", "Oglej si pozneje" to "Kanal A", P.razdeliNaslovSeznama("Oglej si pozneje · Kanal A"))
    preveriEnako("razdeli ob zadnjem locilu", "Mix · 2024" to "Kanal B", P.razdeliNaslovSeznama("Mix · 2024 · Kanal B"))
    preveriEnako("brez locila nespremenjeno", "Moj seznam" to "", P.razdeliNaslovSeznama("Moj seznam"))
    preveriEnako("prazen racun nespremenjeno", "Seznam · " to "", P.razdeliNaslovSeznama("Seznam · "))
    preveriEnako("prazen naslov nespremenjeno", " · Kanal A" to "", P.razdeliNaslovSeznama(" · Kanal A"))
    preveriEnako("pika brez presledkov ni locilo", "A·B" to "", P.razdeliNaslovSeznama("A·B"))
    preveriEnako("podnaslov z racunom", "Kanal A · 19 posnetkov", P.podnaslovSeznama("Kanal A", "19 posnetkov"))
    preveriEnako("podnaslov brez racuna", "11 posnetkov", P.podnaslovSeznama("", "11 posnetkov"))

    // Monogram postaje brez logotipa.
    preveriEnako("Radio izpuscen", "C", P.inicialkePostaje("Radio Center"))
    preveriEnako("dve zacetnici", "V2", P.inicialkePostaje("Val 202"))
    preveriEnako("najvec dve", "AB", P.inicialkePostaje("Alfa Beta Gama"))
    preveriEnako("samo Radio ostane", "R", P.inicialkePostaje("Radio"))
    preveriEnako("zvezdica priljubljene ni crka", "C", P.inicialkePostaje("★ Radio Center"))
    preveriEnako("male crke v velike", "KA", P.inicialkePostaje("kanal a"))
    preveriEnako("crke s sumniki", "ŠČ", P.inicialkePostaje("šola čas"))
    preveriEnako("brez crk prazno", "", P.inicialkePostaje(" · ★ "))
    preveriEnako("barva Radio Center", 4, P.indeksBarve("Radio Center", 5))
    preveriEnako("barva Val 202", 3, P.indeksBarve("Val 202", 5))
    preveriEnako("barva ista z zvezdico", P.indeksBarve("Radio Center", 5), P.indeksBarve("★ Radio Center", 5))
    preveriEnako("barva ista ob vsakem klicu", P.indeksBarve("Antena", 5), P.indeksBarve("Antena", 5))
    preveriEnako("barva brez barv 0", 0, P.indeksBarve("Antena", 0))
    preveriEnako("barva v obsegu", true, (0 until 5).contains(P.indeksBarve("Zelo dolgo ime postaje z veliko crkami 12345", 5)))

    // Kompaktna ploscica dejanja (»Poglej, kaj igra«, »Pokazi vse«).
    preveriEnako("dejanje brez slike kompaktno", true, P.kompaktnoDejanje(dejanje = true, imaSliko = false))
    preveriEnako("dejanje s sliko ne", false, P.kompaktnoDejanje(dejanje = true, imaSliko = true))
    preveriEnako("vsebina ne", false, P.kompaktnoDejanje(dejanje = false, imaSliko = false))
    preveriEnako("visina pol", 54, P.visinaDejanja(108, 48))
    preveriEnako("visina vsaj najmanj", 48, P.visinaDejanja(80, 48))
    preveriEnako("visina ne vec od kartice", 40, P.visinaDejanja(40, 48))

    // Vrstica pomoci: 10 s brez tipke zbledi.
    preveriEnako("pomoc takoj vidna", true, P.pomocVidna(0))
    preveriEnako("pomoc po 9,9 s vidna", true, P.pomocVidna(9_999))
    preveriEnako("pomoc po 10 s skrita", false, P.pomocVidna(10_000))
    preveriEnako("pomoc po 60 s skrita", false, P.pomocVidna(60_000))

    if (napak == 0) println("McVidezPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
