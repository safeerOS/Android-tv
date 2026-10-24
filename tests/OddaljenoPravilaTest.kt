package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Naloga 117: upravljanje predvajanja na drugi napravi kot lokalno ([OddaljenoPravila]). */
fun main() {
    println("Preizkus upravljanja predvajanja na drugi napravi")

    // Ukazi z druge naprave: vse preverjeno.
    preveriEnako("premor", OddaljenoPravila.Ukaz("pause"), OddaljenoPravila.preberiUkaz("pause"))
    preveriEnako("velike crke in presledki", OddaljenoPravila.Ukaz("next"), OddaljenoPravila.preberiUkaz(" NEXT "))
    preveriEnako("neznan ukaz", null, OddaljenoPravila.preberiUkaz("reboot"))
    preveriEnako("prazen ukaz", null, OddaljenoPravila.preberiUkaz(""))
    preveriEnako("skok na cas", OddaljenoPravila.Ukaz("seek", ms = 61_000L), OddaljenoPravila.preberiUkaz("seek", ms = 61_000L))
    preveriEnako("skok brez casa", null, OddaljenoPravila.preberiUkaz("seek"))
    preveriEnako("glasnost na vrednost", OddaljenoPravila.Ukaz("volume", glasnost = 40), OddaljenoPravila.preberiUkaz("volume", glasnost = 40))
    preveriEnako("glasnost nad 100 omejena", OddaljenoPravila.Ukaz("volume", glasnost = 100), OddaljenoPravila.preberiUkaz("volume", glasnost = 500))
    preveriEnako("glasnost za korak", OddaljenoPravila.Ukaz("volume", korak = -5), OddaljenoPravila.preberiUkaz("volume", korak = -5))
    preveriEnako("glasnost brez vrednosti in koraka", null, OddaljenoPravila.preberiUkaz("volume"))
    preveriEnako("skladba iz vrste", OddaljenoPravila.Ukaz("queue.play", indeks = 3), OddaljenoPravila.preberiUkaz("queue.play", indeks = 3))
    preveriEnako("skladba iz vrste brez indeksa", null, OddaljenoPravila.preberiUkaz("queue.play"))

    // Glasnost
    preveriEnako("korak navzgor", 45, OddaljenoPravila.novaGlasnost(40, -1, 5))
    preveriEnako("ne pod 0", 0, OddaljenoPravila.novaGlasnost(3, -1, -5))
    preveriEnako("ne nad 100", 100, OddaljenoPravila.novaGlasnost(98, -1, 5))
    preveriEnako("neznana glasnost + korak", 5, OddaljenoPravila.novaGlasnost(-1, -1, 5))
    preveriEnako("vrednost ima prednost", 20, OddaljenoPravila.novaGlasnost(80, 20, 5))
    preveriEnako("100 % je najvisja stopnja", 15, OddaljenoPravila.vStopnjo(100, 15))
    preveriEnako("50 % od 15 stopenj", 8, OddaljenoPravila.vStopnjo(50, 15))
    preveriEnako("stopnja 15/15 je 100 %", 100, OddaljenoPravila.vOdstotek(15, 15))
    preveriEnako("brez lestvice neznano", -1, OddaljenoPravila.vOdstotek(3, 0))

    // Cas med osvezitvami
    val igra = OddaljenoPravila.Stanje(naslov = "Pesem", igra = true, polozajMs = 10_000L, trajanjeMs = 60_000L, glasnost = 40, prejetoMs = 1_000L)
    preveriEnako("med igranjem cas tece", 12_500L, OddaljenoPravila.polozajZdaj(igra, 3_500L))
    preveriEnako("ne cez trajanje", 60_000L, OddaljenoPravila.polozajZdaj(igra, 999_000L))
    preveriEnako("med premorom stoji", 10_000L, OddaljenoPravila.polozajZdaj(igra.copy(igra = false), 9_000L))
    preveriEnako("ura nazaj ne zmanjsa casa", 10_000L, OddaljenoPravila.polozajZdaj(igra, 500L))

    // Takojsen odziv gumba
    val poPremoru = OddaljenoPravila.poUkazu(igra, OddaljenoPravila.Ukaz("pause"), 3_000L)
    preveriEnako("premor: ne igra in cas obstane", false to 12_000L, poPremoru.igra to OddaljenoPravila.polozajZdaj(poPremoru, 20_000L))
    preveriEnako("nadaljuj po premoru", true, OddaljenoPravila.poUkazu(poPremoru, OddaljenoPravila.Ukaz("resume"), 4_000L).igra)
    preveriEnako("preklopi", false, OddaljenoPravila.poUkazu(igra, OddaljenoPravila.Ukaz("toggle"), 2_000L).igra)
    preveriEnako("nadaljuj brez vsebine ne igra", false,
        OddaljenoPravila.poUkazu(OddaljenoPravila.Stanje(), OddaljenoPravila.Ukaz("resume"), 1L).igra)
    preveriEnako("skok omejen na trajanje", 60_000L, OddaljenoPravila.poUkazu(igra, OddaljenoPravila.Ukaz("seek", ms = 90_000L), 1_000L).polozajMs)
    preveriEnako("glasnost takoj", 45, OddaljenoPravila.poUkazu(igra, OddaljenoPravila.Ukaz("volume", korak = 5), 1_000L).glasnost)
    preveriEnako("naprej pocaka na odgovor", igra, OddaljenoPravila.poUkazu(igra, OddaljenoPravila.Ukaz("next"), 5_000L))

    // Okno vrste
    preveriEnako("kratka vrsta cela", 0 until 5, OddaljenoPravila.oknoVrste(5, 2))
    preveriEnako("dolga vrsta: trenutna na sredini", 75 until 125, OddaljenoPravila.oknoVrste(300, 100))
    preveriEnako("dolga vrsta: na zacetku", 0 until 50, OddaljenoPravila.oknoVrste(300, 3))
    preveriEnako("dolga vrsta: na koncu", 250 until 300, OddaljenoPravila.oknoVrste(300, 299))
    preveriEnako("prazna vrsta", true, OddaljenoPravila.oknoVrste(0, 0).isEmpty())

    // Osvezitev in konec seje
    preveriEnako("vidno, igra: 1 s", 1_000L, OddaljenoPravila.razmikOsvezitve(vidno = true, igra = true))
    preveriEnako("vidno, premor: 3 s", 3_000L, OddaljenoPravila.razmikOsvezitve(vidno = true, igra = false))
    preveriEnako("skrito: 10 s", 10_000L, OddaljenoPravila.razmikOsvezitve(vidno = false, igra = true))
    preveriEnako("dve napaki: seja tece", false, OddaljenoPravila.sejaKoncana(2, 0L, 60_000L))
    preveriEnako("tri napake, a nedavni odgovor: seja tece", false, OddaljenoPravila.sejaKoncana(3, 50_000L, 60_000L))
    preveriEnako("tri napake in 15 s brez odgovora: konec", true, OddaljenoPravila.sejaKoncana(3, 45_000L, 60_000L))

    // Kdaj gumbi upravljajo cilj
    preveriEnako("seja, cilj igra, tu nic: cilj", true, OddaljenoPravila.upravljaCilj(true, ciljKajIgra = true, tuIgra = false))
    preveriEnako("tu igra: ta naprava", false, OddaljenoPravila.upravljaCilj(true, ciljKajIgra = true, tuIgra = true))
    preveriEnako("cilj se ni sprejel: ta naprava", false, OddaljenoPravila.upravljaCilj(true, ciljKajIgra = false, tuIgra = false))
    preveriEnako("brez seje: ta naprava", false, OddaljenoPravila.upravljaCilj(false, ciljKajIgra = true, tuIgra = false))

    if (napak == 0) println("OddaljenoPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
