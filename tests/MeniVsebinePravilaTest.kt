package si.safeer.tv.os

import java.io.File

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Besedila vrstic (res/values* /os_glasba_strings.xml in zavihki_strings.xml): ime -> besedilo v jeziku [mapa]. */
private fun besedila(mapa: File): Map<String, String> = (mapa.listFiles() ?: emptyArray()).filter { it.name.endsWith(".xml") }
    .flatMap { f -> Regex("<string name=\"([^\"]+)\">([^<]*)</string>").findAll(f.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList() }
    .toMap()

/**
 * Meni vsebine in vrstice Medijskega centra ([MeniVsebinePravila], [DomovVrsticePravila]) - lastnik 6. 11. 2026 04:34-04:56
 * (posnetki TV): seznam predvajanja brez »Odstrani«, »Shrani kot seznam« na elementu seznama, dve vrstici »Seznami
 * predvajanja«, Nedavno brez »Pocisti«, na Domov vrstice, ki podvajajo oddelke, Nazaj iz oddelka zapre Medijski center.
 * Argument: koren projekta (za besedila); brez njega se preverba besedil preskoci.
 */
fun main(args: Array<String>) {
    println("Preizkus menija vsebine in vrstic Medijskega centra")
    val M = MeniVsebinePravila

    // --- gumbi menija glede na pogled
    preveriEnako("v seznamu: odstrani, priljubljene, drug seznam - brez Shrani kot seznam",
        listOf(MeniVsebinePravila.Gumb.ODSTRANI_S_SEZNAMA, MeniVsebinePravila.Gumb.DODAJ_PRILJ, MeniVsebinePravila.Gumb.DODAJ_NA_DRUG_SEZNAM),
        M.gumbi(vSeznamu = true, nedavno = false, ponudiPrilj = true, jePrilj = false, ponudiSeznam = true, shranljivihVVrsti = 12))
    preveriEnako("v seznamu, ze priljubljena: Odstrani iz priljubljenih",
        listOf(MeniVsebinePravila.Gumb.ODSTRANI_S_SEZNAMA, MeniVsebinePravila.Gumb.ODSTRANI_PRILJ, MeniVsebinePravila.Gumb.DODAJ_NA_DRUG_SEZNAM),
        M.gumbi(vSeznamu = true, nedavno = false, ponudiPrilj = true, jePrilj = true, ponudiSeznam = true, shranljivihVVrsti = 12))
    preveriEnako("katalog: dodaj med priljubljene, na seznam, shrani vrsto",
        listOf(MeniVsebinePravila.Gumb.DODAJ_PRILJ, MeniVsebinePravila.Gumb.DODAJ_NA_SEZNAM, MeniVsebinePravila.Gumb.SHRANI_VRSTO),
        M.gumbi(vSeznamu = false, nedavno = false, ponudiPrilj = true, jePrilj = false, ponudiSeznam = true, shranljivihVVrsti = 5))
    preveriEnako("en sam element vrste: brez Shrani kot seznam",
        false, MeniVsebinePravila.Gumb.SHRANI_VRSTO in M.gumbi(vSeznamu = false, nedavno = false, ponudiPrilj = true, jePrilj = false, ponudiSeznam = true, shranljivihVVrsti = 1))
    preveriEnako("kanal (brez seznama): samo priljubljene", listOf(MeniVsebinePravila.Gumb.ODSTRANI_PRILJ),
        M.gumbi(vSeznamu = false, nedavno = false, ponudiPrilj = true, jePrilj = true, ponudiSeznam = false, shranljivihVVrsti = 30))
    preveriEnako("nedavno: odstrani iz nedavnega, brez Shrani kot seznam",
        listOf(MeniVsebinePravila.Gumb.DODAJ_PRILJ, MeniVsebinePravila.Gumb.DODAJ_NA_SEZNAM, MeniVsebinePravila.Gumb.ODSTRANI_NEDAVNO),
        M.gumbi(vSeznamu = false, nedavno = true, ponudiPrilj = true, jePrilj = false, ponudiSeznam = true, shranljivihVVrsti = 5))

    // --- izbira seznama za »Dodaj na seznam«
    preveriEnako("seznama, v katerem element ze je, ni v izbiri", listOf("Rock", "Vecer"),
        M.ponujeniSeznami(listOf("Rock", "Ptujskih 5", "Vecer"), setOf("Ptujskih 5")))
    preveriEnako("element je v vseh seznamih: samo Nov seznam (prazna izbira)", emptyList<String>(),
        M.ponujeniSeznami(listOf("Ptujskih 5"), setOf("Ptujskih 5")))

    // --- odstrani vnos na mestu
    val s = listOf("a", "b", "a", "c")
    preveriEnako("odstrani natanko mesto uporabnika (drugi »a«)", listOf("a", "b", "c"), M.odstraniNaMestu(s, 2, "a") { it })
    preveriEnako("odstrani prvi »a«, ce je uporabnik na njem", listOf("b", "a", "c"), M.odstraniNaMestu(s, 0, "a") { it })
    preveriEnako("seznam se je medtem spremenil: prvi z istim id", listOf("a", "b", "a"), M.odstraniNaMestu(s, 1, "c") { it })
    preveriEnako("elementa ni vec: seznam ostane", s, M.odstraniNaMestu(s, 3, "x") { it })
    preveriEnako("fokus po odstranitvi: naslednja (isto mesto)", 1, KanaliPravila.fokusPoSkritju(1, 4))
    preveriEnako("fokus po odstranitvi zadnje: prejsnja", 2, KanaliPravila.fokusPoSkritju(3, 4))

    // --- prazna Glasba: ena vrstica »Seznami predvajanja«, brez posnetkov iz seznamov, stalen red
    val police = M.policePrazne(radio = false, videospoti = false, seznamov = 6, glasbeniVir = true, tvVir = true)
    preveriEnako("prazna Glasba: stalen red vrstic",
        listOf(MeniVsebinePravila.Polica.SEZNAMI, MeniVsebinePravila.Polica.KONCERTI, MeniVsebinePravila.Polica.NEDAVNO), police)
    preveriEnako("prazna Glasba: brez podvojenih imen vrstic", police.size, police.map { it.kljuc }.distinct().size)
    preveriEnako("prazna Glasba: red je enak ob vsakem klicu", police, M.policePrazne(false, false, 6, true, true))
    preveriEnako("prazen Radio: samo glasbeni kanali", listOf(MeniVsebinePravila.Polica.KANALI), M.policePrazne(true, false, 6, true, true))

    // --- nedavno
    val shramba = mapOf("nedavno" to "[{\"id\":\"x\"}]", "priljubljene" to "[{\"id\":\"p\"}]", "seznami" to "[{\"ime\":\"Rock\"}]")
    val pociscena = M.pocistiNedavno(shramba)
    preveriEnako("pocisti: nedavno prazno", "[]", pociscena["nedavno"])
    preveriEnako("pocisti: priljubljene in seznami ostanejo", shramba - "nedavno", pociscena - "nedavno")
    preveriEnako("pocisti: kljuc je kljuc nedavnega, ne napredka Nadaljuj (MediaNapredek ima svojo shrambo)", "nedavno", M.KLJUC_NEDAVNO)
    val nedavno = listOf("1|Hej muzikanti", "2|Tisoc let", "3|Hej muzikanti")
    preveriEnako("odstrani iz nedavnega z razlicicami istega naslova", listOf("2|Tisoc let"),
        M.odstraniNedavno(nedavno, "1|Hej muzikanti", { it.substringBefore('|') }, { it.substringAfter('|') }))
    preveriEnako("vrstica Nedavno: na koncu Pocisti", listOf("a", "b", "pocisti"), M.karticeNedavno(listOf("a", "b"), "pocisti"))
    preveriEnako("prazno Nedavno: brez vrstice (tudi brez Pocisti)", emptyList<String>(), M.karticeNedavno(emptyList(), "pocisti"))

    // --- Domov: katere vrstice (lastnik 04:48-04:56)
    val D = DomovVrsticePravila
    preveriEnako("privzeto Domov: brez vrstic, ki podvajajo oddelke (Radio, TV v zivo, Moji viri)",
        DomovVrsticePravila.PRIVZETO,
        D.izbrane(null))
    preveriEnako("prazne izbrane vrstice ni", listOf(DomovVrsticePravila.Vrstica.NEDAVNO_DODANO),
        D.vrstice(D.izbrane(null), setOf(DomovVrsticePravila.Vrstica.NEDAVNO_DODANO, DomovVrsticePravila.Vrstica.PRILJ_VIDEO, DomovVrsticePravila.Vrstica.NEDAVNO)))
    preveriEnako("izbira uporabnika in njegov vrstni red", listOf(DomovVrsticePravila.Vrstica.NEDAVNO_DODANO, DomovVrsticePravila.Vrstica.NEDAVNO),
        D.vrstice(D.izbrane("nedavno_dodano,nedavno"), DomovVrsticePravila.Vrstica.values().toSet()))
    preveriEnako("zapis tja in nazaj", "prilj_video,seznami", D.vZapis(D.izbrane("prilj_video,seznami")))
    preveriEnako("nova vrstica za Nadaljuj", listOf(DomovVrsticePravila.Vrstica.NADALJUJ_Z_OGLEDOM, DomovVrsticePravila.Vrstica.MOJ_SEZNAM, DomovVrsticePravila.Vrstica.SEZNAMI),
        D.dodajNovo(listOf(DomovVrsticePravila.Vrstica.NADALJUJ_Z_OGLEDOM, DomovVrsticePravila.Vrstica.SEZNAMI), DomovVrsticePravila.Vrstica.MOJ_SEZNAM, DomovVrsticePravila.Vrstica.NADALJUJ_Z_OGLEDOM))
    preveriEnako("nova vrstica brez Nadaljuj na zacetek", listOf(DomovVrsticePravila.Vrstica.MOJ_SEZNAM, DomovVrsticePravila.Vrstica.SEZNAMI),
        D.dodajNovo(listOf(DomovVrsticePravila.Vrstica.SEZNAMI), DomovVrsticePravila.Vrstica.MOJ_SEZNAM, DomovVrsticePravila.Vrstica.NADALJUJ_Z_OGLEDOM))
    preveriEnako("ze izbrana ostane", listOf(DomovVrsticePravila.Vrstica.MOJ_SEZNAM),
        D.dodajNovo(listOf(DomovVrsticePravila.Vrstica.MOJ_SEZNAM), DomovVrsticePravila.Vrstica.MOJ_SEZNAM, null))
    preveriEnako("vrstica Nadaljuj ob pasu: brez vsebine v pasu", listOf("b", "c"), D.brezVsebineVPasu(listOf("a", "b", "c"), { it }, "a"))
    preveriEnako("vrstica Nadaljuj ob pasu: pas prazen -> vse", listOf("a", "b"), D.brezVsebineVPasu(listOf("a", "b"), { it }, null))
    preveriEnako("vrstica Nadaljuj ob pasu: prazen id ne odstrani nicesar", listOf("", "b"), D.brezVsebineVPasu(listOf("", "b"), { it }, ""))
    // TV 8. 11. 2026: film, ki igra v pasu, ni v vrstici Nadaljuj (tok z drugim id, isti IMDb); glasba -> vrstica cela.
    val prestige = DomPasPravila.Vsebina("kartica-prestige", "tt0482571", 0, 0)
    val dune = DomPasPravila.Vsebina("dune", "tt1160419", 0, 0)
    val tokPrestige = DomPasPravila.Vsebina("tok-x", "tt0482571", 0, 0)
    preveriEnako("igra film: kartica istega filma izpade", listOf(dune), D.brezVsebineVPasu(listOf(prestige, dune), { it }, tokPrestige, true, null))
    preveriEnako("igra glasba: vrstica cela", listOf(prestige, dune),
        D.brezVsebineVPasu(listOf(prestige, dune), { it }, DomPasPravila.Vsebina("pesem", "", 0, 0), false, "dune"))
    preveriEnako("nic ne igra: brez zadnje nedokoncane", listOf(prestige), D.brezVsebineVPasu(listOf(prestige, dune), { it }, null, false, "dune"))
    preveriEnako("igra epizoda: druga epizoda ostane", 1, D.brezVsebineVPasu(listOf(DomPasPravila.Vsebina("e1", "tt0903747", 1, 3)), { it },
        DomPasPravila.Vsebina("t", "tt0903747", 1, 2), true, null).size)
    preveriEnako("Dom: plosca nazadnje", listOf("pas", "a", "b", "plosca"), D.razporedDoma("pas", "plosca", listOf("a", "b")))
    // GOR/DOL ohrani stolpec: siroka kartica z levim robom 48 -> spodaj kartica z levim robom 48 (ne druga, blizje sredini).
    preveriEnako("stolpec po levem robu", 0, D.stolpecPoLevemRobu(48, listOf(48, 250, 452)))
    preveriEnako("glava: zavihek r:", true, D.jeOznakaGlave("r:filmi"))
    preveriEnako("glava: izbira k:jezik", true, D.jeOznakaGlave("k:jezik"))
    preveriEnako("glava: k:nadaljuj", true, D.jeOznakaGlave("k:nadaljuj"))
    preveriEnako("kartica mreze ni glava", false, D.jeOznakaGlave("k:Nedavno dodano#3"))
    preveriEnako("kartica mreze z # v naslovu ni glava", false, D.jeOznakaGlave("k:Top #1 hits#12"))
    preveriEnako("brez oznake ni glava", false, D.jeOznakaGlave(null))
    preveriEnako("stolpec: najblizji levi rob", 2, D.stolpecPoLevemRobu(460, listOf(48, 250, 452)))
    preveriEnako("stolpec: enako dalec -> bolj levo", 0, D.stolpecPoLevemRobu(100, listOf(50, 150)))
    preveriEnako("stolpec: enako dalec, levi je drugi", 1, D.stolpecPoLevemRobu(100, listOf(150, 50)))
    preveriEnako("stolpec: brez kandidatov -1", -1, D.stolpecPoLevemRobu(10, emptyList()))
    preveriEnako("neznan kljuc in dvojnik izpadeta", listOf(DomovVrsticePravila.Vrstica.SEZNAMI), D.izbrane("seznami,neznano,seznami"))
    preveriEnako("vse izklopljeno ostane izklopljeno (ne privzeto)", emptyList<DomovVrsticePravila.Vrstica>(), D.izbrane(""))
    preveriEnako("premik gor v oknu", listOf(DomovVrsticePravila.Vrstica.SEZNAMI, DomovVrsticePravila.Vrstica.NEDAVNO),
        D.premakniVOkna(listOf(DomovVrsticePravila.Vrstica.NEDAVNO, DomovVrsticePravila.Vrstica.SEZNAMI), DomovVrsticePravila.Vrstica.SEZNAMI, -1))
    preveriEnako("premik prve gor v oknu: nic", listOf(DomovVrsticePravila.Vrstica.NEDAVNO, DomovVrsticePravila.Vrstica.SEZNAMI),
        D.premakniVOkna(listOf(DomovVrsticePravila.Vrstica.NEDAVNO, DomovVrsticePravila.Vrstica.SEZNAMI), DomovVrsticePravila.Vrstica.NEDAVNO, -1))
    preveriEnako("vklop dodane vrstice na konec", listOf(DomovVrsticePravila.Vrstica.NEDAVNO, DomovVrsticePravila.Vrstica.PRILJ_VIDEO),
        D.preklopiVOkna(listOf(DomovVrsticePravila.Vrstica.NEDAVNO), DomovVrsticePravila.Vrstica.PRILJ_VIDEO, listOf(DomovVrsticePravila.Vrstica.NEDAVNO, DomovVrsticePravila.Vrstica.PRILJ_VIDEO)))
    preveriEnako("izklop vrstice", listOf(DomovVrsticePravila.Vrstica.NEDAVNO),
        D.preklopiVOkna(listOf(DomovVrsticePravila.Vrstica.NEDAVNO, DomovVrsticePravila.Vrstica.PRILJ_VIDEO), DomovVrsticePravila.Vrstica.PRILJ_VIDEO, listOf(DomovVrsticePravila.Vrstica.NEDAVNO, DomovVrsticePravila.Vrstica.PRILJ_VIDEO)))
    preveriEnako("priljubljena glasba brez radia in kanalov", true,
        D.vPriljubljeniGlasbi(video = false, radio = false, kanal = false) && !D.vPriljubljeniGlasbi(video = false, radio = true, kanal = false) &&
            !D.vPriljubljeniGlasbi(video = false, radio = false, kanal = true))

    // --- Nazaj iz oddelka: na Domov Medijskega centra, z Domov ven
    preveriEnako("Nazaj iz Video: Domov", DomovVrsticePravila.Nazaj.NA_DOMOV, D.nazaj(naDomovu = false, jeDomov = true))
    preveriEnako("Nazaj na Domov: zapre Medijski center", DomovVrsticePravila.Nazaj.ZAPRI, D.nazaj(naDomovu = true, jeDomov = true))
    preveriEnako("brez Domov (samostojni Predvajalnik): kot doslej", DomovVrsticePravila.Nazaj.ZAPRI, D.nazaj(naDomovu = false, jeDomov = false))

    // --- vrstica oddelkov: ista na Domu in v oddelkih, Dom prvi (lastnik 05:11)
    preveriEnako("Dom je prvi gumb", DomovVrsticePravila.Oddelek.DOM, D.gumbiOddelkov().first())
    preveriEnako("vsi oddelki in Predvajalnik", listOf(DomovVrsticePravila.Oddelek.DOM, DomovVrsticePravila.Oddelek.GLASBA, DomovVrsticePravila.Oddelek.VIDEO,
        DomovVrsticePravila.Oddelek.RADIO, DomovVrsticePravila.Oddelek.TV_V_ZIVO, DomovVrsticePravila.Oddelek.PREDVAJALNIK), D.gumbiOddelkov())
    preveriEnako("gumbiOddelkov nima VIRI", false, DomovVrsticePravila.Oddelek.VIRI in D.gumbiOddelkov())
    preveriEnako("glava Doma na TV: samo vrstica oddelkov (brez ploscic, Razvrsti, Nazadnje predvajano)",
        listOf(DomovVrsticePravila.Glava.ODDELKI), D.glavaDoma(vlc = false))

    // --- besedila vrstic v vseh jezikih: imena vrstic prazne Glasbe se ne podvajajo
    val koren = args.firstOrNull()?.let { File(it) }
    if (koren != null) for (jezik in listOf("", "-sl", "-en", "-de", "-es", "-fr", "-it")) {
        val b = besedila(File(koren, "res/values$jezik"))
        val imena = police.map { b[it.kljuc] }
        preveriEnako("besedila vrstic values$jezik obstajajo", false, imena.any { it == null })
        preveriEnako("besedila vrstic values$jezik brez dvojnikov", imena.size, imena.distinct().size)
        preveriEnako("besedilo »Dodaj na drug seznam« values$jezik", true, b["os_seznam_dodaj_drug"]?.isNotBlank() == true)
        preveriEnako("besedilo »Odstrani iz Nadaljuj z ogledom« values$jezik", true, b["os_media_odstrani_nadaljuj"]?.isNotBlank() == true)
    }

    if (napak > 0) { println("NAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("vse ok")
}
