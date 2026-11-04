package si.safeer.tv

/**
 * Zavihki brskalnika na TV in RAM (pravilo lastnika 2. 11. 2026: brskalnik se ne sme odpreti s starimi zavihki). Ciste
 * odlocitve brez Androida, preizkus tests/ZavihkiPravilaTest.kt.
 */
object ZavihkiPravila {
    /** Vrsta namere, s katero pride uporabnik v brskalnik. */
    enum class Namera { ZAGANJALNIK, POGLED, APLIKACIJA, DRUGO }

    /**
     * Lastnik 4. 11. 2026 23:43: brskalnik, odprt z zaganjalnika, je bil na 3. zavihku (proces je ostal ziv, vmes sta
     * zunanji nameri odprli strani). Zagon z zaganjalnika ali iz Safeer OS (Splet, domaca stran), ko je bil brskalnik v
     * ozadju: ostane en zavihek (domaca stran), ostali se unicijo. Zavihek, ki predvaja, ostane.
     */
    fun zapriOstale(namera: Namera, zavihkov: Int, bilVOzadju: Boolean): Boolean =
        namera == Namera.ZAGANJALNIK && bilVOzadju && zavihkov > 1

    /**
     * Vodja 4. 11. 2026 23:43 (TV: RTV SLO je ostal med odprtimi zavihki): ob izhodu iz spletne aplikacije (Nazaj na
     * zacetku, Domov, druga aplikacija) se njen zavihek unici - mesto je zapomnjeno (SpletneAplikacije.zapomniMesto),
     * ponoven vstop nadaljuje tam. Ostane samo, ce igra zvok v ozadju, ce caka izbira datotek (sistemski izbirnik je
     * druga dejavnost) ali ob menjavi nastavitev (zasuk).
     */
    fun unici(zvokVOzadju: Boolean, cakaIzbiroDatotek: Boolean, menjavaNastavitev: Boolean): Boolean =
        !zvokVOzadju && !cakaIzbiroDatotek && !menjavaNastavitev
}
