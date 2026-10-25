package si.safeer.tv.os

/**
 * Namizje Safeer na zunanjem zaslonu (lastnik, 25. 10. 2026): Android ga odpre kot SECONDARY_HOME na zaslonu, ki ga
 * telefon ali tablica razsiri v namizni nacin (Android 15+/16, Samsung DeX), kadar je Safeer izbran domaci zaslon.
 * Ista koda kot [ZaganjalnikActivity], vedno v namizni razporeditvi. Svoj razred ima, ker Android domacega zaslona
 * drugega zaslona ne odpre iz dejavnosti singleTask (za vsak zaslon potrebuje svojo instanco - singleTop v manifestu).
 */
class NamizjeActivity : ZaganjalnikActivity() {
    override val vsiljenoNamizje: Boolean get() = true
}
