package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Naloga 118: mini predvajalnik kot dock ob robu domacega zaslona ([MiniPredvajalnikPravila]). */
fun main() {
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

    if (napak == 0) println("MiniPredvajalnikPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
