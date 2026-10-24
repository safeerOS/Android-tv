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

    if (napak == 0) println("MiniPredvajalnikPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
