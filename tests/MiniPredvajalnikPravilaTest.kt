package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Naloga 118: mini predvajalnik kot dock ob robu domacega zaslona ([MiniPredvajalnikPravila]). */
fun main() {
    // Lastnik 27. 10. 2026: stikali samodejnega nadaljevanja v predvajalniku.
    run {
        val P = MiniPredvajalnikPravila
        val enako = { opis: String, pricakovano: Any?, dobljeno: Any? ->
            if (pricakovano != dobljeno) { println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno"); kotlin.system.exitProcess(1) }
            else println("  ok  $opis")
        }
        enako("privzeto: naslednja skladba", P.PO_KONCU_NASLEDNJA, P.poKoncuSkladbe(naslednja = true, album = true, imaNaslednjo = true))
        enako("privzeto: po zadnji skladbi naprej (mix)", P.PO_KONCU_MIX, P.poKoncuSkladbe(naslednja = true, album = true, imaNaslednjo = false))
        enako("naslednja izklopljena: konec po trenutni", P.PO_KONCU_KONEC, P.poKoncuSkladbe(naslednja = false, album = true, imaNaslednjo = true))
        enako("album izklopljen: po zadnji skladbi konec", P.PO_KONCU_KONEC, P.poKoncuSkladbe(naslednja = true, album = false, imaNaslednjo = false))
        enako("album izklopljen: skladbe albuma se vedno naprej", P.PO_KONCU_NASLEDNJA, P.poKoncuSkladbe(naslednja = true, album = false, imaNaslednjo = true))
    }

    // Lastnik in vodja 27. 10. 2026 (TV beta 14): Nadaljuj pri filmu, dock nad spodnjo vrstico.
    run {
        val P = MiniPredvajalnikPravila
        val enako = { opis: String, pricakovano: Any?, dobljeno: Any? ->
            if (pricakovano != dobljeno) { println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno"); kotlin.system.exitProcess(1) }
            else println("  ok  $opis")
        }
        enako("film na pavzi: Predvajaj odpre celozaslonski predvajalnik", P.ODPRI_PREDVAJALNIK, P.dejanjePredvajaj(video = true, igra = false))
        enako("film igra: Pavza ostane pavza", P.PREKLOPI_ZVOK, P.dejanjePredvajaj(video = true, igra = true))
        enako("glasba na pavzi: preklopi kot doslej", P.PREKLOPI_ZVOK, P.dejanjePredvajaj(video = false, igra = false))
        enako("klik na naslov filma: predvajalnik", P.ODPRI_PREDVAJALNIK, P.klikNaslova(true))
        enako("klik na naslov glasbe: Medijski center", P.ODPRI_MEDIJE, P.klikNaslova(false))
        enako("dock spodaj: nad spodnjo vrstico", 120, P.dodatniRob(P.PRIVZETO, spodaj = 120))
        enako("dock zgoraj: brez odmika", 0, P.dodatniRob(P.ZGORAJ, spodaj = 120))
        enako("dock levo: samo levi odmik", 0, P.dodatniRob(P.LEVO, spodaj = 120))
        enako("negativen odmik ni mogoc", 0, P.dodatniRob(P.PRIVZETO, spodaj = -5))
    }

    println("Preizkus mini predvajalnika (dock)")

    preveriEnako("privzeto spodaj", "spodaj", MiniPredvajalnikPravila.mesto(null))
    preveriEnako("shranjeno levo", "levo", MiniPredvajalnikPravila.mesto("levo"))
    preveriEnako("velike crke in presledki", "desno", MiniPredvajalnikPravila.mesto(" DESNO "))
    preveriEnako("neznana vrednost: privzeto", "spodaj", MiniPredvajalnikPravila.mesto("sredina"))
    preveriEnako("v seznamu ni dock", false, MiniPredvajalnikPravila.jeDock("seznam"))
    preveriEnako("zgoraj je dock", true, MiniPredvajalnikPravila.jeDock("zgoraj"))
    preveriEnako("levo pokoncen", true, MiniPredvajalnikPravila.pokoncen("levo"))
    preveriEnako("desno pokoncen", true, MiniPredvajalnikPravila.pokoncen("desno"))
    preveriEnako("spodaj vodoraven", false, MiniPredvajalnikPravila.pokoncen("spodaj"))

    preveriEnako("skladba tu: viden", true, MiniPredvajalnikPravila.viden(imaSkladbo = true, oddaljenoKajIgra = false))
    preveriEnako("igra na drugi napravi: viden", true, MiniPredvajalnikPravila.viden(imaSkladbo = false, oddaljenoKajIgra = true))
    preveriEnako("nic ne igra: ni ga", false, MiniPredvajalnikPravila.viden(imaSkladbo = false, oddaljenoKajIgra = false))

    val prag = 24f
    preveriEnako("spodaj: poteg gor odpre", MiniPredvajalnikPravila.ODPRI, MiniPredvajalnikPravila.poPotegu("spodaj", 3f, -80f, prag))
    preveriEnako("spodaj: poteg dol zapre", MiniPredvajalnikPravila.ZAPRI, MiniPredvajalnikPravila.poPotegu("spodaj", 0f, 60f, prag))
    preveriEnako("zgoraj: poteg dol odpre", MiniPredvajalnikPravila.ODPRI, MiniPredvajalnikPravila.poPotegu("zgoraj", 0f, 60f, prag))
    preveriEnako("zgoraj: poteg gor zapre", MiniPredvajalnikPravila.ZAPRI, MiniPredvajalnikPravila.poPotegu("zgoraj", 0f, -60f, prag))
    preveriEnako("levo: poteg desno odpre", MiniPredvajalnikPravila.ODPRI, MiniPredvajalnikPravila.poPotegu("levo", 70f, 5f, prag))
    preveriEnako("levo: poteg levo zapre", MiniPredvajalnikPravila.ZAPRI, MiniPredvajalnikPravila.poPotegu("levo", -70f, 5f, prag))
    preveriEnako("desno: poteg levo odpre", MiniPredvajalnikPravila.ODPRI, MiniPredvajalnikPravila.poPotegu("desno", -70f, 0f, prag))
    preveriEnako("desno: poteg desno zapre", MiniPredvajalnikPravila.ZAPRI, MiniPredvajalnikPravila.poPotegu("desno", 70f, 0f, prag))
    preveriEnako("kratek poteg: nic", MiniPredvajalnikPravila.NIC, MiniPredvajalnikPravila.poPotegu("spodaj", 0f, -10f, prag))
    preveriEnako("poteg vzdolz roba: nic", MiniPredvajalnikPravila.NIC, MiniPredvajalnikPravila.poPotegu("spodaj", 120f, -40f, prag))
    preveriEnako("v seznamu: potegi nic", MiniPredvajalnikPravila.NIC, MiniPredvajalnikPravila.poPotegu("seznam", 0f, -200f, prag))

    preveriEnako("odprt brez dotika 8 s: skrij", true, MiniPredvajalnikPravila.samodejnoSkrij(true, false, 1_000L, 9_000L))
    preveriEnako("odprt, nedaven dotik: ostane", false, MiniPredvajalnikPravila.samodejnoSkrij(true, false, 5_000L, 9_000L))
    preveriEnako("fokus daljinca: ostane", false, MiniPredvajalnikPravila.samodejnoSkrij(true, true, 0L, 60_000L))
    preveriEnako("ze skrit: nic", false, MiniPredvajalnikPravila.samodejnoSkrij(false, false, 0L, 60_000L))

    // Pregled C4: dock se ne gradi ob vsaki osvezitvi
    preveriEnako("se ni zgrajen: zgradi", true, MiniPredvajalnikPravila.zgradiZnova(null, "spodaj", false))
    preveriEnako("isto mesto, na zaslonu: samo osvezi", false, MiniPredvajalnikPravila.zgradiZnova("spodaj", "spodaj", true))
    preveriEnako("novo mesto: zgradi", true, MiniPredvajalnikPravila.zgradiZnova("spodaj", "levo", true))
    preveriEnako("ni vec na zaslonu: zgradi", true, MiniPredvajalnikPravila.zgradiZnova("spodaj", "spodaj", false))
    preveriEnako("neznano shranjeno = privzeto: samo osvezi", false, MiniPredvajalnikPravila.zgradiZnova("spodaj", "sredina", true))

    // Lastnik 27. 10.: drsnik casa in zvocnik v docku
    preveriEnako("skladba s trajanjem: drsnik", true, MiniPredvajalnikPravila.drsnik(180_000L, radio = false, oddaljeno = false))
    preveriEnako("radio: brez drsnika", false, MiniPredvajalnikPravila.drsnik(180_000L, radio = true, oddaljeno = false))
    preveriEnako("brez trajanja (nalaga): brez drsnika", false, MiniPredvajalnikPravila.drsnik(0L, radio = false, oddaljeno = false))
    preveriEnako("druga naprava: brez drsnika", false, MiniPredvajalnikPravila.drsnik(180_000L, radio = false, oddaljeno = true))
    preveriEnako("skladba tu: gumb zvocnik", true, MiniPredvajalnikPravila.gumbZvocnik(imaSkladbo = true, oddaljeno = false))
    preveriEnako("druga naprava: brez gumba zvocnik", false, MiniPredvajalnikPravila.gumbZvocnik(imaSkladbo = true, oddaljeno = true))
    preveriEnako("polovica skladbe = 500", 500, MiniPredvajalnikPravila.korakDrsnika(90_000L, 180_000L))
    preveriEnako("cez konec = 1000", 1000, MiniPredvajalnikPravila.korakDrsnika(999_000L, 180_000L))
    preveriEnako("brez trajanja = 0", 0, MiniPredvajalnikPravila.korakDrsnika(5_000L, 0L))
    preveriEnako("korak 250 = cetrtina", 45_000L, MiniPredvajalnikPravila.casIzDrsnika(250, 180_000L))
    preveriEnako("korak cez meje", 180_000L, MiniPredvajalnikPravila.casIzDrsnika(5000, 180_000L))

    // Obvestilo kot medijski predvajalnik (zaklenjen zaslon, hitre nastavitve)
    val O = ObvestiloPredvajanjaPravila
    preveriEnako("skladba z naslednjo", listOf(O.NAZAJ, O.PREKLOPI, O.NAPREJ, O.NA_NAPRAVI, O.USTAVI), O.dejanja(imaNaslednjo = true, radio = false))
    preveriEnako("zadnja skladba: Nazaj ostane", listOf(O.NAZAJ, O.PREKLOPI, O.NA_NAPRAVI, O.USTAVI), O.dejanja(imaNaslednjo = false, radio = false))
    preveriEnako("radio: tudi na napravi", listOf(O.PREKLOPI, O.NA_NAPRAVI, O.USTAVI), O.dejanja(imaNaslednjo = true, radio = true))
    preveriEnako("strnjeno: nazaj, preklopi, naprej", listOf(0, 1, 2), O.strnjeno(O.dejanja(true, false)).toList())
    preveriEnako("strnjeno brez Ustavi", listOf(0, 1), O.strnjeno(O.dejanja(false, false)).toList())
    preveriEnako("strnjeno radio", listOf(0), O.strnjeno(O.dejanja(true, true)).toList())

    // Lastnik 27. 10.: »Predvajaj na napravi« - zvocniki v omrezju, naprave Linka, nazadnje Bluetooth (sistem)
    val N = PredvajajNaNapraviPravila
    preveriEnako("zvocnika in naprava, sistem zadnji",
        listOf(N.ZVOCNIK to 0, N.ZVOCNIK to 1, N.NAPRAVA to 0, N.SISTEM to 0), N.izbire(2, 1))
    preveriEnako("v omrezju nic: isci znova, nato Bluetooth/sistem", listOf(N.ISCI_ZNOVA to 0, N.SISTEM to 0), N.izbire(0, 0))
    preveriEnako("samo naprave Linka: isci znova pred njimi", listOf(N.ISCI_ZNOVA to 0, N.NAPRAVA to 0, N.SISTEM to 0), N.izbire(0, 1))

    println("\n== naloga 42 B: glasnost v docku ==")
    preveriEnako("gumbi: tisje in glasneje med zvocnikom in skrij", listOf("prejsnja", "predvajaj", "naslednja", "zvocnik", "tisje", "glasneje", "skrij"),
        MiniPredvajalnikPravila.GUMBI)
    preveriEnako("igra tu: glasnost te naprave", MiniPredvajalnikPravila.GLASNOST_TU, MiniPredvajalnikPravila.ciljGlasnosti(false, false))
    preveriEnako("igra na zvocniku: zvocnik", MiniPredvajalnikPravila.GLASNOST_ZVOCNIK, MiniPredvajalnikPravila.ciljGlasnosti(false, true))
    preveriEnako("upravlja drugo napravo: tista naprava", MiniPredvajalnikPravila.GLASNOST_DRUGA, MiniPredvajalnikPravila.ciljGlasnosti(true, false))
    preveriEnako("druga naprava ima prednost pred zvocnikom", MiniPredvajalnikPravila.GLASNOST_DRUGA, MiniPredvajalnikPravila.ciljGlasnosti(true, true))
    preveriEnako("korak druge naprave", 5, MiniPredvajalnikPravila.KORAK_DRUGA)

    // Zunanji pregled 3. 11. 2026: tipka Predvajaj na domu pri videu odpre cel zaslon; dock se po vrnitvi spet skrije.
    run {
        val P = MiniPredvajalnikPravila
        check(P.tipkaNaDomu(P.TIPKA_PLAY, video = true, igra = false, naZvocniku = false, zaslonOdprt = false) == P.ODPRI_PREDVAJALNIK) { "play pri filmu na pavzi" }
        check(P.tipkaNaDomu(P.TIPKA_PREKLOPI, video = true, igra = false, naZvocniku = false, zaslonOdprt = false) == P.ODPRI_PREDVAJALNIK) { "play/pavza pri filmu na pavzi" }
        check(P.tipkaNaDomu(P.TIPKA_PREKLOPI, video = true, igra = true, naZvocniku = false, zaslonOdprt = false) == P.PREKLOPI_ZVOK) { "film igra: pavza" }
        check(P.tipkaNaDomu(P.TIPKA_PLAY, video = false, igra = false, naZvocniku = false, zaslonOdprt = false) == P.PREKLOPI_ZVOK) { "glasba kot doslej" }
        check(P.tipkaNaDomu(P.TIPKA_PLAY, video = true, igra = false, naZvocniku = false, zaslonOdprt = true) == P.PREKLOPI_ZVOK) { "zaslon ze odprt" }
        check(P.znovaSkrivanje(odprt = true, viden = true, casovnikTece = false)) { "po vrnitvi na dom" }
        check(!P.znovaSkrivanje(odprt = true, viden = true, casovnikTece = true)) { "casovnik ze tece" }
        check(!P.znovaSkrivanje(odprt = false, viden = true, casovnikTece = false)) { "skrit dock" }
        println("  ok  tipka Predvajaj na domu in samodejno skrivanje po vrnitvi")
    }

    // Lastnik 28. 10. 2026: gumb »Zatemni zaslon« samo za zvok na tej napravi
    check(MiniPredvajalnikPravila.gumbZatemni(true, false, false))
    check(!MiniPredvajalnikPravila.gumbZatemni(true, true, false)) { "video" }
    check(!MiniPredvajalnikPravila.gumbZatemni(true, false, true)) { "druga naprava" }
    check(!MiniPredvajalnikPravila.gumbZatemni(false, false, false)) { "nic ne igra" }

    println("\n== Del A: sodobna kartica na Domu ==")
    val P = MiniPredvajalnikPravila
    preveriEnako("prikazMc: Dom, ni podstrani", P.KARTICA, P.prikazMc(naDomu = true, imaSkladbo = true, podstranOdprta = false))
    preveriEnako("prikazMc: Dom, podstran", P.ZNAK, P.prikazMc(naDomu = true, imaSkladbo = true, podstranOdprta = true))
    preveriEnako("prikazMc: drug zavihek", P.ZNAK, P.prikazMc(naDomu = false, imaSkladbo = true, podstranOdprta = false))
    preveriEnako("prikazMc: nic nalozeno", P.MC_NIC, P.prikazMc(naDomu = true, imaSkladbo = false, podstranOdprta = false))

    preveriEnako("casNapredka: z urami", "1:16:05 / 2:10:00", P.casNapredka(4565000L, 7800000L))
    preveriEnako("casNapredka: brez ur", "3:05 / 4:12", P.casNapredka(185000L, 252000L))
    preveriEnako("casNapredka: radio/tok v zivo", "", P.casNapredka(10000L, 0L))

    preveriEnako("gumbiKartice: glasba", listOf("prejsnja", "predvajaj", "naslednja"), P.gumbiKartice(video = false, radio = false))
    preveriEnako("gumbiKartice: video", listOf("predvajaj"), P.gumbiKartice(video = true, radio = false))
    preveriEnako("gumbiKartice: radio", listOf("predvajaj"), P.gumbiKartice(video = false, radio = true))

    // Tocka 28 (7. 11. 2026): DOL z dna vsebine na dock.
    preveriEnako("dolNaDock: dno vsebine, dock viden", true, P.dolNaDock(false, true, false))
    preveriEnako("dolNaDock: vsebina ima se spodaj", false, P.dolNaDock(true, true, false))
    preveriEnako("dolNaDock: dock skrit ali nefokusabilen", false, P.dolNaDock(false, false, false))
    preveriEnako("dolNaDock: dock ze ima fokus", false, P.dolNaDock(false, true, true))

    // Naloga #38 (lastnik 6. 11. 04:25, 8. 11. 02:58): kartica na Domu ves cas - igra, Nadaljuj ali nic.
    preveriEnako("stanjeKartice: igra na Domu -> KARTICA", P.KARTICA, P.stanjeKartice(naDomu = true, podstranOdprta = false, imaSkladbo = true, imaNadaljevanje = true))
    preveriEnako("stanjeKartice: nic ne igra, nedokoncano obstaja -> NADALJUJ", P.NADALJUJ, P.stanjeKartice(true, false, false, true))
    preveriEnako("stanjeKartice: nic ne igra, nic nedokoncanega -> nic", P.MC_NIC, P.stanjeKartice(true, false, false, false))
    preveriEnako("stanjeKartice: podstran odprta -> nic (znak)", P.MC_NIC, P.stanjeKartice(true, true, true, true))
    preveriEnako("stanjeKartice: drug razdelek -> nic (znak)", P.MC_NIC, P.stanjeKartice(false, false, true, true))
    preveriEnako("jeNedokoncano: 40 % filma", true, P.jeNedokoncano(true, 40_000L, 100_000L))
    preveriEnako("jeNedokoncano: komaj zacet (1 %) ne", false, P.jeNedokoncano(true, 1_000L, 100_000L))
    preveriEnako("jeNedokoncano: odgledan (96 %) ne", false, P.jeNedokoncano(true, 96_000L, 100_000L))
    preveriEnako("jeNedokoncano: glasba ne", false, P.jeNedokoncano(false, 40_000L, 100_000L))
    preveriEnako("jeNedokoncano: brez trajanja ne", false, P.jeNedokoncano(true, 40_000L, 0L))
    preveriEnako("videoVOkencu: Dom, kartica vidna, video -> da", true, P.videoVOkencu(true, false, true, true))
    preveriEnako("videoVOkencu: podstran -> ne", false, P.videoVOkencu(true, true, true, true))
    preveriEnako("videoVOkencu: kartica skrita -> ne", false, P.videoVOkencu(true, false, false, true))
    preveriEnako("videoVOkencu: glasba -> ne", false, P.videoVOkencu(true, false, true, false))
    preveriEnako("pokaziKajIgra: samo nazadnje -> ne", false, P.pokaziKajIgra(listOf(false, false)))
    preveriEnako("pokaziKajIgra: ena igra -> da", true, P.pokaziKajIgra(listOf(false, true)))
    preveriEnako("pokaziKajIgra: brez naprav -> ne", false, P.pokaziKajIgra(emptyList()))

    // CodeRabbit 8. 11. 2026: okence Doma samo, ko je Dom MC zagnan (cilj vrnitve); z Datotekami vmes je ustavljen.
    preveriEnako("okenceNaDomu: Dom zagnan s kartico -> da", true, MiniPredvajalnikPravila.okenceNaDomu(true, false, true, false, true))
    preveriEnako("okenceNaDomu: Dom ustavljen (Datoteke vmes) -> ne", false, MiniPredvajalnikPravila.okenceNaDomu(false, false, true, false, true))
    preveriEnako("okenceNaDomu: Dom koncuje -> ne", false, MiniPredvajalnikPravila.okenceNaDomu(true, true, true, false, true))
    preveriEnako("okenceNaDomu: drug razdelek -> ne", false, MiniPredvajalnikPravila.okenceNaDomu(true, false, false, false, true))
    preveriEnako("okenceNaDomu: podstran odprta -> ne", false, MiniPredvajalnikPravila.okenceNaDomu(true, false, true, true, true))
    preveriEnako("okenceNaDomu: brez kartice -> ne", false, MiniPredvajalnikPravila.okenceNaDomu(true, false, true, false, false))

    if (napak == 0) println("MiniPredvajalnikPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
