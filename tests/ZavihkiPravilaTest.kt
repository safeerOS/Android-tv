package si.safeer.tv

import si.safeer.tv.ZavihkiPravila.Namera

/** Zavihki in RAM na TV (lastnik 2. in 4. 11. 2026): en zavihek po zagonu z zaganjalnika, zavihek aplikacije ne ostane. */
fun main() {
    val p = ZavihkiPravila
    // Lastnik 23:43: proces ziv, 3 zavihki, zagon z zaganjalnika -> en zavihek.
    check(p.zapriOstale(Namera.ZAGANJALNIK, zavihkov = 3, bilVOzadju = true))
    check(!p.zapriOstale(Namera.ZAGANJALNIK, zavihkov = 1, bilVOzadju = true))      // en sam: nic
    check(!p.zapriOstale(Namera.ZAGANJALNIK, zavihkov = 3, bilVOzadju = false))     // ze na zaslonu: uporabnikovi zavihki ostanejo
    check(!p.zapriOstale(Namera.POGLED, zavihkov = 3, bilVOzadju = true))           // zunanji naslov: v aktivnem zavihku
    check(!p.zapriOstale(Namera.APLIKACIJA, zavihkov = 3, bilVOzadju = true))
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
    println("ZavihkiPravilaTest: OK")
}
