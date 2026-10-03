package si.safeer.tv.os

import si.safeer.tv.os.SeznamiPravila.Glava
import si.safeer.tv.os.SeznamiPravila.Korak
import si.safeer.tv.os.SeznamiPravila.Zapis

fun main() {
    // Seznama tukaj ni: prevzamemo ga - razen ce smo ga izbrisali po njegovi zadnji spremembi.
    check(SeznamiPravila.korak(null, 0, Glava("A", 0, 5)) == Korak.PREVZEMI)          // star seznam (cas 0) z druge naprave
    check(SeznamiPravila.korak(null, 0, Glava("A", 100, 5)) == Korak.PREVZEMI)
    check(SeznamiPravila.korak(null, 200, Glava("A", 100, 5)) == Korak.NIC)           // izbrisan pozneje: ne vrnemo
    check(SeznamiPravila.korak(null, 200, Glava("A", 0, 5)) == Korak.NIC)
    check(SeznamiPravila.korak(null, 200, Glava("A", 300, 5)) == Korak.PREVZEMI)      // po izbrisu znova uvozen drugje
    check(SeznamiPravila.korak(null, 0, Glava("A", 100, 0)) == Korak.NIC)             // prazen seznam ni seznam
    // Seznam je na obeh: novejsi zmaga, starejsega ne vzamemo; enak cas in razlicno stevilo = zdruzimo.
    check(SeznamiPravila.korak(Glava("A", 100, 5), 0, Glava("A", 200, 3)) == Korak.PREVZEMI)
    check(SeznamiPravila.korak(Glava("A", 200, 5), 0, Glava("A", 100, 9)) == Korak.NIC)
    check(SeznamiPravila.korak(Glava("A", 0, 5), 0, Glava("A", 0, 7)) == Korak.ZDRUZI)
    check(SeznamiPravila.korak(Glava("A", 0, 5), 0, Glava("A", 0, 5)) == Korak.NIC)
    // Izbris drugje velja, ce seznama od takrat nismo spremenili.
    check(SeznamiPravila.izbrisVelja(100, 200) && SeznamiPravila.izbrisVelja(0, 200))
    check(!SeznamiPravila.izbrisVelja(300, 200) && !SeznamiPravila.izbrisVelja(0, 0))
    // Zdruzevanje: moje ostanejo po vrsti, tuje, ki jih nimam, gredo za njimi; ista skladba je ena (tudi z in brez posnetka).
    val a = Zapis("Pesem", "Izvajalec", youtube = "abc12345678")
    val b = Zapis("Druga", "Nekdo")
    val c = Zapis("pesem ", " izvajalec")                                             // ista kot a, brez posnetka
    val d = Zapis("Tretja", "Nekdo", url = "https://primer.si/t.mp3")
    check(SeznamiPravila.zdruzi(listOf(a, b), listOf(c, d), 400) == listOf(a, b, d))
    check(SeznamiPravila.zdruzi(listOf(a, b), listOf(d), 2) == listOf(a, b))
    // Zdruzitev na obeh napravah da isto mnozico (vrstni red je lahko drug) - naslednjic ni vec kaj zdruzevati.
    val tu = SeznamiPravila.zdruzi(listOf(a, b), listOf(d), 400); val tam = SeznamiPravila.zdruzi(listOf(d), listOf(a, b), 400)
    check(tu.map { it.kljuc }.toSet() == tam.map { it.kljuc }.toSet() && tu.size == tam.size)
    // Prevzem: tuje skladbe, a ze najden posnetek za isto skladbo ostane; tuji posnetek ima prednost pred mojim.
    val prevzeto = SeznamiPravila.prevzemi(listOf(a, b), listOf(c, d, Zapis("Pesem", "Izvajalec")), 400)
    check(prevzeto.size == 2 && prevzeto[0].youtube == "abc12345678" && prevzeto[1] == d) { prevzeto }
    check(SeznamiPravila.prevzemi(listOf(a), listOf(a.copy(youtube = "xyz98765432")), 400)[0].youtube == "xyz98765432")
    // Posnetek iz naslova strani.
    check(SeznamiPravila.youtubePosnetek("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1") == "dQw4w9WgXcQ")
    check(SeznamiPravila.youtubePosnetek("https://youtu.be/dQw4w9WgXcQ?t=3") == "dQw4w9WgXcQ")
    check(SeznamiPravila.youtubePosnetek("https://m.youtube.com/shorts/abcdef12345") == "abcdef12345")
    check(SeznamiPravila.youtubePosnetek("https://www.youtube.com/results?search_query=a") == "")
    check(SeznamiPravila.youtubePosnetek("https://primer.si/watch?v=dQw4w9WgXcQ") == "")
    check(SeznamiPravila.youtubePosnetek("https://notyoutube.com.primer.si/watch?v=dQw4w9WgXcQ") == "")
    // Ure naprav niso enake: krajevna sprememba ali izbris ni nikoli starejsi od prejsnjega stanja in znanega izbrisa.
    check(SeznamiPravila.novCas(1_000, 0, 0) == 1_000L)
    check(SeznamiPravila.novCas(1_000, 5_000, 0) == 5_001L)       // seznam je prisel z naprave, ki ji ura prehiteva
    check(SeznamiPravila.novCas(1_000, 0, 7_000) == 7_001L)       // znova ustvarjen po izbrisu »iz prihodnosti«
    // ... zato izbris z naprave z zaostalo uro velja, nov seznam po izbrisu pa ostane.
    check(SeznamiPravila.izbrisVelja(5_000, SeznamiPravila.novCas(1_000, 5_000, 0)))
    check(!SeznamiPravila.izbrisVelja(SeznamiPravila.novCas(1_000, 0, 7_000), 7_000))
    // Moji viri med napravami. Vir iz casa pred usklajevanjem (cas 0) prevzame naprava, ki ga nima ...
    check(SeznamiPravila.virPrevzamemo(false, null, 0))
    check(!SeznamiPravila.virPrevzamemo(true, null, 5_000))
    // ... razen ce ga je uporabnik tu izbrisal: izbrisan vir se ne vrne, dokler ga drugje ne doda znova (pozneje).
    val izbrisVira = SeznamiPravila.novCas(1_000, 0, 0)
    check(!SeznamiPravila.virPrevzamemo(false, izbrisVira, 0))
    check(!SeznamiPravila.virPrevzamemo(false, izbrisVira, izbrisVira))
    check(SeznamiPravila.virPrevzamemo(false, izbrisVira, SeznamiPravila.novCas(900, 0, izbrisVira)))   // dodan znova, ura zaostaja
    // Izbris z druge naprave odstrani star vir (cas 0) in vir, dodan pred izbrisom; pozneje dodanega pusti.
    check(SeznamiPravila.izbrisViraVelja(0, izbrisVira))
    check(SeznamiPravila.izbrisViraVelja(500, SeznamiPravila.novCas(100, 500, 0)))                        // izbris z naprave z zaostalo uro
    check(!SeznamiPravila.izbrisViraVelja(SeznamiPravila.novCas(900, 0, izbrisVira), izbrisVira))
    check(!SeznamiPravila.izbrisViraVelja(0, 0))
    println("SeznamiPravilaTest: OK")
}
