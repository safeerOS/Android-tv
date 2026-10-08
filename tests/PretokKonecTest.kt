package si.safeer.tv.link

/**
 * Konec »Odpri tukaj« (link/PretokKonec). Izmerjeno 7. 10. 2026 na tablici: po zaprtju okna na racunalniku je tablica
 * zaslon delila naprej in igra je igrala naprej - racunalnik ji ni sporocil nicesar, tablica ukaza za konec ni poznala.
 * Drugi pregled pred izdajo (C1-C3): ukaz mora biti vezan na napravo, ki ga poslje, in na deljenje, ki ga je zacel
 * »Odpri tukaj«; zahteva, ki se caka na soglasje, mora biti umakljiva.
 */
fun main() {
    fun o(stream: Boolean = true, od: String = "A", deli: Boolean = true, cilj: String = "A", tukaj: String = "A", vnos: Boolean = true, caka: String = "",
          zacenja: String = "") = PretokKonec.odloci(stream, od, deli, cilj, tukaj, vnos, caka, zacenja)
    // »Odpri tukaj« naprave A, Safeer Vnos vklopljen, okno na A se zapre: nehamo deliti in aplikacijo umaknemo z zaslona.
    check(o() == PretokKonec.Odlocitev(true, true, false))
    // Brez Safeer Vnosa domov ne moremo: samo konec deljenja.
    check(o(vnos = false) == PretokKonec.Odlocitev(true, false, false))
    // Zaslon je uporabnik delil sam (naprava si »Odpri tukaj« ni zapisala): naprave mu ne premaknemo izpod prstov.
    check(o(tukaj = "") == PretokKonec.Odlocitev(true, false, false))
    // Zapis »Odpri tukaj« je za drugo napravo kot tista, ki zapira: brez Domov.
    check(o(tukaj = "B") == PretokKonec.Odlocitev(true, false, false))
    // Deljenje gre napravi A, ukaz poslje naprava B: nic - tretja naprava tujega deljenja ne ustavi in naprave ne vrze domov.
    check(o(od = "B") == PretokKonec.Odlocitev(false, false, false))
    check(o(od = "B", tukaj = "B") == PretokKonec.Odlocitev(false, false, false))
    // Zaslona ne delimo: nic - zapoznel ali ponovljen ukaz naprave ne sme vreci na domaci zaslon.
    for (vnos in listOf(true, false)) for (tukaj in listOf("", "A"))
        check(o(deli = false, vnos = vnos, tukaj = tukaj) == PretokKonec.Odlocitev(false, false, false))
    // Brez »stream« ukaz pretoka ne zapira; brez posiljatelja ne vemo, cigav je.
    check(o(stream = false) == PretokKonec.Odlocitev(false, false, false))
    check(o(od = "", cilj = "", tukaj = "") == PretokKonec.Odlocitev(false, false, false))
    // Zahteva naprave A se caka na soglasje, A zapre: zahteva je umaknjena. Zahteve naprave B ukaz naprave A ne umakne.
    check(o(deli = false, caka = "A") == PretokKonec.Odlocitev(false, false, true))
    check(o(deli = false, caka = "B") == PretokKonec.Odlocitev(false, false, false))

    // Zahteve: potrditev po umiku ne zacne deljenja.
    var cas = 1_000L
    val z = PretokKonec.Zahteve { cas }
    check(z.cakaOd() == "" && !z.caka(0) && !z.potrdi(0))
    val prva = z.nova("A")
    check(z.caka(prva) && z.cakaOd() == "A")
    z.umakni()
    check(!z.caka(prva) && z.cakaOd() == "" && !z.potrdi(prva))
    // Potrditev velja enkrat.
    val druga = z.nova("A")
    check(z.potrdi(druga))
    check(!z.potrdi(druga) && z.cakaOd() == "")
    // Nova zahteva zamenja staro: potrditev stare ne zacne nicesar, nova ostane.
    val stara = z.nova("A")
    val nova = z.nova("B")
    check(!z.potrdi(stara))
    check(z.cakaOd() == "B" && z.potrdi(nova))
    // Zahteva pretece: pozna potrditev (tap na staro obvestilo, pozabljeno okno) ne zacne deljenja.
    val pozna = z.nova("A")
    cas += PretokKonec.CAKA_MS - 1
    check(z.caka(pozna))
    cas += 1
    check(!z.caka(pozna) && z.cakaOd() == "" && !z.potrdi(pozna))
    // Zapis »Odpri tukaj« velja za deljenje, ki ga je zacel; nastavi ga zacetek deljenja sam (en klic - tretji pregled, K1),
    // vsako drugo novo deljenje ga pobrise.
    check(z.odprtoTukajZa() == "")
    z.novoDeljenje("A")
    check(z.odprtoTukajZa() == "A")
    z.novoDeljenje()
    check(z.odprtoTukajZa() == "")

    // K3: soglasje je dano, storitev deljenja se se zaganja. Ukaz za konec, ki pride v tem hipu, velja kot za tekoce
    // deljenje (prej se je izgubil in deljenje je steklo za okno, ki je ze zaprto).
    check(o(deli = false, cilj = "", zacenja = "A") == PretokKonec.Odlocitev(true, true, false))
    check(o(deli = false, cilj = "", vnos = false, zacenja = "A") == PretokKonec.Odlocitev(true, false, false))
    check(o(deli = false, cilj = "", tukaj = "", zacenja = "A") == PretokKonec.Odlocitev(true, false, false))
    // Zaganja se za drugo napravo: ukaz naprave A ga ne ustavi.
    check(o(deli = false, cilj = "", tukaj = "B", zacenja = "B") == PretokKonec.Odlocitev(false, false, false))
    // R6-K3: deljenje napravi A se tece, »Odpri tukaj« naprave B je pravkar potrjen (novo deljenje bo zamenjalo starega).
    // Ukaz A za konec ne sme ustaviti B-jevega deljenja, ki se zaganja; ukaz B ga ustavi.
    check(o(deli = true, cilj = "A", tukaj = "B", zacenja = "B") == PretokKonec.Odlocitev(false, false, false))
    check(o(od = "B", deli = true, cilj = "A", tukaj = "B", zacenja = "B") == PretokKonec.Odlocitev(true, true, false))
    // Potrditev pusti oznako zagona; velja kratek cas ali dokler storitev ne javi, da deljenje tece.
    cas = 500_000L
    val k = PretokKonec.Zahteve { cas }
    check(k.zacenjaZa() == "")
    check(k.potrdi(k.nova("A")) && k.zacenjaZa() == "A" && k.cakaOd() == "")
    cas += PretokKonec.ZAGON_MS - 1
    check(k.zacenjaZa() == "A")
    cas += 1
    check(k.zacenjaZa() == "")
    check(k.potrdi(k.nova("B")) && k.zacenjaZa() == "B")
    k.zagonKoncan()
    check(k.zacenjaZa() == "")
    // Potrditev, ki ne velja (pretecena, umaknjena, zamenjana), oznake ne pusti.
    val pretecena = k.nova("A")
    cas += PretokKonec.CAKA_MS
    check(!k.potrdi(pretecena) && k.zacenjaZa() == "")
    val umaknjena = k.nova("A"); k.umakni()
    check(!k.potrdi(umaknjena) && k.zacenjaZa() == "")
    println("PretokKonecTest: OK")
}
