package si.safeer.tv

import si.safeer.tv.ZavihkiPravila.Domov
import si.safeer.tv.ZavihkiPravila.Namera
import si.safeer.tv.ZavihkiPravila.Okvir
import si.safeer.tv.ZavihkiPravila.VstopAplikacije

/** Zavihki in RAM na TV (lastnik 2. in 4. 11. 2026): en zavihek po zagonu z zaganjalnika, zavihek aplikacije ne ostane. */
fun main(args: Array<String>) {
    val p = ZavihkiPravila
    // Lastnik 23:43: proces ziv, 3 zavihki, zagon z zaganjalnika -> en zavihek.
    check(p.zapriOstale(Namera.ZAGANJALNIK, zavihkov = 3, bilVOzadju = true))
    check(!p.zapriOstale(Namera.ZAGANJALNIK, zavihkov = 1, bilVOzadju = true))      // en sam: nic
    check(!p.zapriOstale(Namera.ZAGANJALNIK, zavihkov = 3, bilVOzadju = false))     // ze na zaslonu: uporabnikovi zavihki ostanejo
    // Pregled lupine 5. 11. 2026: vsak vstop od zunaj in obnova pustita en zavihek (pravilo lastnika: zaradi RAM vedno 1).
    check(p.zapriOstale(Namera.POGLED, zavihkov = 3, bilVOzadju = true))
    check(p.zapriOstale(Namera.POGLED, zavihkov = 2, bilVOzadju = false))
    check(p.zapriOstale(Namera.APLIKACIJA, zavihkov = 3, bilVOzadju = true))
    check(p.zapriOstale(Namera.OBNOVA, zavihkov = 3, bilVOzadju = false))
    check(!p.zapriOstale(Namera.OBNOVA, zavihkov = 1, bilVOzadju = false))
    check(!p.zapriOstale(Namera.DRUGO, zavihkov = 3, bilVOzadju = true))
    // Vodja 23:43: zavihek spletne aplikacije se ob izhodu unici, razen zvoka v ozadju, izbire datotek, zasuka.
    check(p.unici(zvokVOzadju = false, cakaIzbiroDatotek = false, menjavaNastavitev = false))
    check(!p.unici(zvokVOzadju = true, cakaIzbiroDatotek = false, menjavaNastavitev = false))
    check(!p.unici(zvokVOzadju = false, cakaIzbiroDatotek = true, menjavaNastavitev = false))
    check(!p.unici(zvokVOzadju = false, cakaIzbiroDatotek = false, menjavaNastavitev = true))
    // Lastnik 23:47: fokus ob odprtju seznama na aktivni kartici (ne na GridView ali gumbu).
    check(p.zacetnaKartica(aktivni = 1, stevilo = 3) == 1)
    check(p.zacetnaKartica(aktivni = -1, stevilo = 3) == 0)
    check(p.zacetnaKartica(aktivni = 5, stevilo = 3) == 0)
    check(p.zacetnaKartica(aktivni = 0, stevilo = 0) == -1)

    // Pregled lupine 5. 11. 2026: LEVO/DESNO po vrstnem redu na zaslonu. Seznam je v vrstnem redu kode (Nazaj, Osvezi,
    // Domov, polje), postavitev TV pa ima Nazaj (x=0), Domov (x=50), Osvezi (x=100), polje (x=150).
    val tv = listOf(Okvir(0, 0, 44, 44), Okvir(100, 0, 44, 44), Okvir(50, 0, 44, 44), Okvir(150, 0, 400, 44))
    check(p.sosedVVrstici(tv, trenutni = 0, desno = true) == 2)      // Nazaj -> Domov (prej Osvezi)
    check(p.sosedVVrstici(tv, trenutni = 2, desno = true) == 1)      // Domov -> Osvezi
    check(p.sosedVVrstici(tv, trenutni = 1, desno = true) == 3)      // Osvezi -> polje
    check(p.sosedVVrstici(tv, trenutni = 3, desno = false) == 1)     // polje -> Osvezi
    check(p.sosedVVrstici(tv, trenutni = 2, desno = false) == 0)
    check(p.sosedVVrstici(tv, trenutni = 0, desno = false) == -1)    // rob vrstice
    check(p.sosedVVrstici(tv, trenutni = 3, desno = true) == -1)
    // Druga vrstica (Safeer OS pokonci: navigacija spodaj) in skrit gumb (0 x 0) nista soseda.
    val dve = listOf(Okvir(0, 0, 44, 44), Okvir(60, 900, 44, 44), Okvir(120, 0, 0, 0), Okvir(200, 0, 44, 44))
    check(p.sosedVVrstici(dve, trenutni = 0, desno = true) == 3)
    check(p.sosedVVrstici(dve, trenutni = 1, desno = false) == -1)
    check(p.sosedVVrstici(dve, trenutni = 9, desno = true) == -1)
    // Pregled fokusa Gemini 5. 11. 2026 (9): visji sosed (polje 120 px ob gumbu 44 px, poravnano zgoraj) je sosed v obe smeri.
    val visji = listOf(Okvir(0, 0, 44, 44), Okvir(60, 0, 400, 120))
    check(p.sosedVVrstici(visji, trenutni = 0, desno = true) == 1) { "visji sosed preskocen" }
    check(p.sosedVVrstici(visji, trenutni = 1, desno = false) == 0)
    // Vrstici, ki se prekrivata samo za rob (4 px), nista ena vrstica.
    val tesni = listOf(Okvir(0, 0, 44, 44), Okvir(60, 40, 44, 44))
    check(p.sosedVVrstici(tesni, trenutni = 0, desno = true) == -1)
    check(p.sosedVVrstici(tesni, trenutni = 1, desno = false) == -1)

    // Pregled lupine 5. 11. 2026: »home« z daljinca ne javi uspeha, ce ni naredil nicesar.
    // Drugi krog pregleda 5. 11. 2026: OBNOVA ne ob vstopu v lupino (prejsnji zavihek ostane za izhod iz aplikacije).
    check(p.obnovaPustiEnega(obnovljeno = true, vstopVLupino = false))
    check(!p.obnovaPustiEnega(obnovljeno = true, vstopVLupino = true))
    check(!p.obnovaPustiEnega(obnovljeno = false, vstopVLupino = false))
    // Zavihek aplikacije v lupini, ki predvaja, se ob izhodu ne unici (kot pustiEnZavihek).
    check(!p.unici(zvokVOzadju = false, cakaIzbiroDatotek = false, menjavaNastavitev = false, predvaja = true))
    check(p.unici(zvokVOzadju = false, cakaIzbiroDatotek = false, menjavaNastavitev = false, predvaja = false))
    args.getOrNull(0)?.let { src ->
        val ma = java.io.File(src, "si/safeer/tv/MainActivity.kt").readText()
        check("ZavihkiPravila.obnovaPustiEnega(restored, vstopVLupino)" in ma) { "MainActivity: OBNOVA tudi ob vstopu v lupino" }
        check(ma.indexOf("vstopVLupino = jeAplikacijaVLupini(") in 1 until ma.indexOf("ZavihkiPravila.obnovaPustiEnega(")) { "MainActivity: vrstni red vstopa" }
        check("predvaja = tab.predvaja)) return" in ma) { "MainActivity: zapriAplikacijoVLupini unici zavihek, ki predvaja" }
    } ?: error("pot do src/main/kotlin")
    check(p.domovDaljinca(sistemskiUspel = true, nacinAplikacije = true) == Domov.SISTEM)
    check(p.domovDaljinca(sistemskiUspel = true, nacinAplikacije = false) == Domov.SISTEM)
    check(p.domovDaljinca(sistemskiUspel = false, nacinAplikacije = false) == Domov.DOMACA_STRAN)
    check(p.domovDaljinca(sistemskiUspel = false, nacinAplikacije = true) == Domov.NI_MOGOCE)

    // Pregled lupine 5. 11. 2026: branje stanja Cast se ustavi brez predvajanja.
    check(!p.castBranjeNaprej("idle", 0))
    check(!p.castBranjeNaprej("stopped", 0))
    check(p.castBranjeNaprej("playing", 0))
    check(p.castBranjeNaprej("paused", 0))
    // Drugi krog pregleda 5. 11. 2026: prvo prazno branje po »play« branja ne ustavi; drugo zapored ga.
    check(p.castBranjeNaprej("playing", 1))
    check(!p.castBranjeNaprej("playing", 2))                      // medij je izginil s strani
    check(!p.castBranjeNaprej("paused", 1))
    check(p.castBranjeNaprej("buffering", 5))                     // stran se se nalaga
    check(!p.castBranjeNaprej("buffering", 6))
    // Vodja 5. 11. 2026: spletna aplikacija iz Safeer OS - lupina ostane (lastnik 28. 9., 3a65e877), zivljenjski cikel
    // aplikacije pa velja (svoj zavihek, unicen ob izhodu). Prej: navaden VIEW (prepisal aktivni zavihek) oziroma
    // 18460c9c: cez cel zaslon brez stranske vrstice.
    val n = p.spletnaAplikacijaIzOs("https://rtv.si/", "RTV SLO")
    check(n.pogled && n.naslov == "https://rtv.si/")                  // VIEW: hladen zagon = en zavihek (njen)
    val celZaslon = n.dodatki.containsKey(p.DODATEK_CEZ_ZASLON)
    val vLupini = n.dodatki[p.DODATEK_V_LUPINI] == true
    check(p.stranskaVrstica(izOs = true, celZaslon = celZaslon, odpriLink = false))      // lupina Safeer OS ostane
    check(p.vstopAplikacije(celZaslon, vLupini, dovoljeno = true) == VstopAplikacije.V_LUPINI)
    check(n.dodatki[p.DODATEK_IME] == "RTV SLO")
    check(p.vstopAplikacije(celZaslon = false, vLupini = true, dovoljeno = false) == VstopAplikacije.NAVADEN)   // tuja namera
    check(p.vstopAplikacije(celZaslon = true, vLupini = false, dovoljeno = true) == VstopAplikacije.CEZ_ZASLON) // Medij
    check(p.vstopAplikacije(celZaslon = false, vLupini = false, dovoljeno = true) == VstopAplikacije.NAVADEN)
    check(!p.stranskaVrstica(izOs = true, celZaslon = true, odpriLink = false))
    check(!p.stranskaVrstica(izOs = true, celZaslon = false, odpriLink = true))
    check(!p.stranskaVrstica(izOs = false, celZaslon = false, odpriLink = false))
    // TV 8. 11. 2026: izrisovalnik zavihka v ozadju se odpove prednosti, ko brskalnik ni viden ali zmanjkuje pomnilnika.
    check(p.prednostIzrisovalnika(aktiven = true, viden = true, stopnja = 0, predvaja = false) == ZavihkiPravila.Prednost.POMEMBNA)
    check(p.prednostIzrisovalnika(aktiven = true, viden = true, stopnja = p.TRIM_RUNNING_LOW, predvaja = false) == ZavihkiPravila.Prednost.POMEMBNA)
    check(p.prednostIzrisovalnika(aktiven = false, viden = false, stopnja = 0, predvaja = false) == ZavihkiPravila.Prednost.ODPOVEDANA)
    check(p.prednostIzrisovalnika(aktiven = false, viden = true, stopnja = p.TRIM_RUNNING_LOW, predvaja = false) == ZavihkiPravila.Prednost.ODPOVEDANA)
    check(p.prednostIzrisovalnika(aktiven = false, viden = true, stopnja = 15, predvaja = false) == ZavihkiPravila.Prednost.ODPOVEDANA)
    check(p.prednostIzrisovalnika(aktiven = false, viden = true, stopnja = 5, predvaja = false) == ZavihkiPravila.Prednost.BREZ_SPREMEMBE)   // RUNNING_MODERATE
    check(p.prednostIzrisovalnika(aktiven = false, viden = false, stopnja = 20, predvaja = true) == ZavihkiPravila.Prednost.BREZ_SPREMEMBE)  // zvok v ozadju
    // Aktivni na skritem zaslonu: sistem ga odpove sam (waivedWhenNotVisible = true v ChromiumEngineView).
    check(p.prednostIzrisovalnika(aktiven = true, viden = false, stopnja = 20, predvaja = false) == ZavihkiPravila.Prednost.BREZ_SPREMEMBE)
    check(p.TRIM_RUNNING_LOW == 10)
    println("ZavihkiPravilaTest: OK")
}
