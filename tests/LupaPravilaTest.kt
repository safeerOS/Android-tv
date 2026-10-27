package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Naloga 42 E: ena razclemba ukazov lupe in glasu ([LupaPravila]) in zadetki lupe. Lazne naprave. */
fun main() {
    println("Preizkus lupe in glasovnih ukazov")
    val krog = listOf("n-1" to "Dnevna soba", "n-2" to "Pisarna", "n-3" to "TV", "n-4" to "Dnevna soba TV")
    val u = { niz: String -> LupaPravila.razcleni(niz, krog) }
    val brez = { niz: String -> LupaPravila.razcleni(niz) }

    println("\n== <kaj> na <napravo>: po imenu v krogu ==")
    preveriEnako("Sintel na Dnevna soba", LupaPravila.Ukaz("prikazi_na", "Sintel", "n-1"), u("Sintel na Dnevna soba"))
    preveriEnako("daljse ime ima prednost", LupaPravila.Ukaz("prikazi_na", "Sintel", "n-4"), u("Sintel na dnevna soba TV"))
    preveriEnako("ime TV pred vrsto TV", LupaPravila.Ukaz("prikazi_na", "Sintel", "n-3"), u("Sintel na TV"))
    preveriEnako("glagol odpade, sumniki v imenu", LupaPravila.Ukaz("prikazi_na", "Jungle Book", "n-2"), u("Predvajaj Jungle Book na pisarni".replace("pisarni", "Pisarna")))
    preveriEnako("en: on the", LupaPravila.Ukaz("prikazi_na", "Sintel", "n-2"), u("play Sintel on the Pisarna"))
    preveriEnako("de: auf dem", LupaPravila.Ukaz("prikazi_na", "Sintel", "n-1"), u("Sintel auf dem Dnevna Soba"))
    preveriEnako("ime brez predloga ni cilj", LupaPravila.Ukaz("isci", "Pisarna"), u("Pisarna"))

    println("\n== po vrsti naprave (brez imena) ==")
    preveriEnako("sl na TV", LupaPravila.Ukaz("prikazi_na", "Sintel", "", listOf("tv")), brez("Sintel na TV"))
    preveriEnako("sl na racunalniku", LupaPravila.Ukaz("prikazi_na", "Sintel", "", listOf("windows", "linux", "mac")), brez("Sintel na računalniku"))
    preveriEnako("en on my phone", LupaPravila.Ukaz("prikazi_na", "Big Buck Bunny", "", listOf("phone")), brez("show Big Buck Bunny on my phone"))
    preveriEnako("es en la tele", LupaPravila.Ukaz("prikazi_na", "Sintel", "", listOf("tv")), brez("pon Sintel en la tele"))
    preveriEnako("fr sur la télé", LupaPravila.Ukaz("prikazi_na", "Sintel", "", listOf("tv")), brez("Sintel sur la télé"))
    preveriEnako("it sulla TV", LupaPravila.Ukaz("prikazi_na", "Sintel", "", listOf("tv")), brez("Sintel sulla TV"))
    preveriEnako("de auf dem Fernseher", LupaPravila.Ukaz("prikazi_na", "Sintel", "", listOf("tv")), brez("Sintel auf dem Fernseher"))

    println("\n== vreme ==")
    preveriEnako("vreme", LupaPravila.Ukaz("vreme", ""), brez("vreme"))
    preveriEnako("vreme na TV (lastnik)", LupaPravila.Ukaz("vreme", "", "", listOf("tv")), brez("vreme na TV"))
    preveriEnako("vreme na napravi po imenu", LupaPravila.Ukaz("vreme", "", "n-1"), u("vreme na Dnevna soba"))
    preveriEnako("vreme v kraju", LupaPravila.Ukaz("vreme", "Mariboru"), brez("Vreme v Mariboru"))
    preveriEnako("weather in Paris", LupaPravila.Ukaz("vreme", "Paris"), brez("weather in Paris"))
    preveriEnako("Wetter auf dem Fernseher", LupaPravila.Ukaz("vreme", "", "", listOf("tv")), brez("Wetter auf dem Fernseher"))
    preveriEnako("météo", LupaPravila.Ukaz("vreme", ""), brez("Météo"))

    println("\n== splet in iskanje ==")
    preveriEnako("isci v spletu", LupaPravila.Ukaz("isci_splet", "recept za potico"), brez("Išči v spletu recept za potico"))
    preveriEnako("search the web for", LupaPravila.Ukaz("isci_splet", "Sintel"), brez("search the web for Sintel"))
    preveriEnako("cerca sul web", LupaPravila.Ukaz("isci_splet", "meteo Roma"), brez("cerca sul web meteo Roma"))
    preveriEnako("naslov", LupaPravila.Ukaz("splet", "https://safeer.si"), brez("safeer.si"))
    preveriEnako("naslov s shemo", LupaPravila.Ukaz("splet", "https://example.org/a"), brez("https://example.org/a"))
    preveriEnako("navadno iskanje", LupaPravila.Ukaz("isci", "Big Buck Bunny"), brez("Big Buck Bunny"))
    preveriEnako("pomisljaj ne zamakne besed", LupaPravila.Ukaz("prikazi_na", "Sintel", "", listOf("tv")), brez("Sintel – na TV"))
    preveriEnako("prazno", LupaPravila.Ukaz("isci", ""), brez("   "))

    println("\n== zadetki lupe ==")
    preveriEnako("zacetek imena", 3, LupaPravila.ujemanje("Nastavitve", "nas"))
    preveriEnako("zacetek besede", 2, LupaPravila.ujemanje("Safeer Link", "lin"))
    preveriEnako("vsebuje", 1, LupaPravila.ujemanje("YouTube", "tub"))
    preveriEnako("brez sumnikov", 3, LupaPravila.ujemanje("Čas in datum", "cas"))
    preveriEnako("ne ujema", 0, LupaPravila.ujemanje("Netflix", "xyz"))
    preveriEnako("prazna poizvedba", 0, LupaPravila.ujemanje("Netflix", " "))
    preveriEnako("najboljsi po ujemanju in imenu", listOf("Nastavitve", "Safeer Nastavitve", "Ponastavi"),
        LupaPravila.najboljsi(listOf("Ponastavi", "Safeer Nastavitve", "Nastavitve", "Netflix"), "nastav", 5) { it })
    preveriEnako("najvec", 1, LupaPravila.najboljsi(listOf("a1", "a2"), "a", 1) { it }.size)

    println("\n== sprotni predlogi iz spleta ==")
    preveriEnako("Google/DDG oblika", listOf("sintel film", "sintel blender"),
        LupaPravila.predlogi("""["sintel",["sintel","sintel film","sintel blender",""],[],{"x":1}]""", "Sintel"))
    preveriEnako("najvec 5", 5, LupaPravila.predlogi("""["a",["a1","a2","a3","a4","a5","a6"]]""", "a").size)
    preveriEnako("pokvarjen odgovor", emptyList<String>(), LupaPravila.predlogi("<html>", "a"))
    preveriEnako("brez seznama", emptyList<String>(), LupaPravila.predlogi("""["a"]""", "a"))
    preveriEnako("kratka poizvedba ne gre iskalniku", false, LupaPravila.predlogeIskati("si", true))
    preveriEnako("naslov ne gre iskalniku", false, LupaPravila.predlogeIskati("safeer.si", true))
    preveriEnako("izklopljeno ne gre", false, LupaPravila.predlogeIskati("sintel", false))
    preveriEnako("sicer da", true, LupaPravila.predlogeIskati("sintel", true))

    if (napak == 0) println("LupaPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
