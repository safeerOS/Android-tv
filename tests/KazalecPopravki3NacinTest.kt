// Popravki slepega pregleda TV3 (15. 11. 2026), N5, N6, I4: cista pravila kazalca (OK spust, skrivanje ob miski, zacetna lega).
// Integracija (WebView keyup, prava miska) je NEPREVERJENA na napravi; tests/preveri_tv_popravki3.py preveri samo, da jih koda uporablja.
//   tests/run_splet_tests.sh
package si.safeer.tv

import si.safeer.tv.KazalecPravila as K
import kotlin.system.exitProcess

private var padlo = 0
private var preverb = 0

private fun p(pogoj: Boolean, opis: String) {
    preverb++
    if (pogoj) println("OK   $opis") else { padlo++; println("PADE $opis") }
}

fun main() {
    // N5: prvi OK, ki kazalec samo pokaze, mora pogoltniti tudi spust (stran ne sme dobiti keyup brez keydown).
    p(K.okSpust(cakaKlik = false, pogoltniSpust = true) == K.OkSpust.POGOLTNI, "N5: samo pokazi -> spust pogoltnjen")
    p(K.okSpust(cakaKlik = true, pogoltniSpust = false) == K.OkSpust.KLIK, "N5: navaden kratek OK -> klik")
    p(K.okSpust(cakaKlik = true, pogoltniSpust = true) == K.OkSpust.KLIK, "N5: klik ima prednost pred pogoltnjenim spustom")
    p(K.okSpust(cakaKlik = false, pogoltniSpust = false) == K.OkSpust.PREPUSTI, "N5: drugi spusti (drzanje, vlecenje) ostanejo kot prej")
    p(K.tipkaSamoPokaze(bilSkrit = true, jeOk = true) && !K.tipkaSamoPokaze(true, false) && !K.tipkaSamoPokaze(false, true), "N5: samo skrit kazalec + OK")

    // N6: miska med pritiskom OK (cakaKlik) kazalca ne skrije - spust ne sme klikniti na skritem mestu.
    p(!K.smeSkritiZaMisko(vidno = true, vlecem = false, cakaKlik = true, skrit = false), "N6: med cakaKlik se kazalec za misko ne skrije")
    p(K.smeSkritiZaMisko(vidno = true, vlecem = false, cakaKlik = false, skrit = false), "N6: sicer se skrije")
    p(!K.smeSkritiZaMisko(vidno = true, vlecem = true, cakaKlik = false, skrit = false), "N6: med vlecenjem ne")
    p(!K.smeSkritiZaMisko(vidno = false, vlecem = false, cakaKlik = false, skrit = false), "N6: nevidnega ne skriva")
    p(!K.smeSkritiZaMisko(vidno = true, vlecem = false, cakaKlik = false, skrit = true), "N6: ze skritega ne skriva znova")

    // I4: sredina samo ob prvi postavitvi; kazalec v (0, 0), ki ga je postavil uporabnik, ne skoci v sredino.
    p(K.zacetnaLega(zePostavljena = false, x = 0f, y = 0f, sirina = 1920, visina = 1080) == Pair(960f, 540f), "I4: prva postavitev (0,0) -> sredina")
    p(K.zacetnaLega(zePostavljena = true, x = 0f, y = 0f, sirina = 1920, visina = 1080) == null, "I4: kazalec v (0,0) po prvi postavitvi ne skoci")

    // I4: sredina samo ob prvi postavitvi.
    p(K.zacetnaLega(zePostavljena = true, x = 0f, y = 0f, sirina = 1920, visina = 1080) == null, "I4: kazalec po prvi postavitvi ne skoci")
    p(K.zacetnaLega(zePostavljena = false, x = 0f, y = 0f, sirina = 1920, visina = 1080) == Pair(960f, 540f), "I4: prva postavitev v kotu gre v sredino")
    p(K.zacetnaLega(zePostavljena = false, x = 100f, y = 50f, sirina = 1920, visina = 1080) == null, "I4: ze postavljen kazalec ostane")
    p(K.zacetnaLega(zePostavljena = false, x = 0f, y = 0f, sirina = 0, visina = 0) == null, "I4: velikost nic: nic")

    println("KazalecPopravki3NacinTest: $preverb preverb, $padlo padlo")
    if (padlo > 0) exitProcess(1)
}
