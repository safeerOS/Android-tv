package si.safeer.tv

import si.safeer.tv.ZavihkiPravila.Domov
import si.safeer.tv.ZavihkiPravila.Namera
import si.safeer.tv.ZavihkiPravila.Okvir

/** Zavihki in RAM na TV (lastnik 2. in 4. 11. 2026): en zavihek po zagonu z zaganjalnika, zavihek aplikacije ne ostane. */
fun main() {
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

    // Pregled lupine 5. 11. 2026: »home« z daljinca ne javi uspeha, ce ni naredil nicesar.
    check(p.domovDaljinca(sistemskiUspel = true, nacinAplikacije = true) == Domov.SISTEM)
    check(p.domovDaljinca(sistemskiUspel = true, nacinAplikacije = false) == Domov.SISTEM)
    check(p.domovDaljinca(sistemskiUspel = false, nacinAplikacije = false) == Domov.DOMACA_STRAN)
    check(p.domovDaljinca(sistemskiUspel = false, nacinAplikacije = true) == Domov.NI_MOGOCE)

    // Pregled lupine 5. 11. 2026: branje stanja Cast se ustavi brez predvajanja.
    check(!p.castBranjeNaprej("idle", 0))
    check(!p.castBranjeNaprej("stopped", 0))
    check(p.castBranjeNaprej("playing", 0))
    check(p.castBranjeNaprej("paused", 0))
    check(!p.castBranjeNaprej("playing", 1))                      // medij je izginil s strani
    check(p.castBranjeNaprej("buffering", 5))                     // stran se se nalaga
    check(!p.castBranjeNaprej("buffering", 6))
    println("ZavihkiPravilaTest: OK")
}
