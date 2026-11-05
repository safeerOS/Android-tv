package si.safeer.tv.os

/**
 * Kaj sme dobiti pomocnik sprotne pretvorbe (SprotnaPomoc.prosi) - cista pravila brez Androida
 * (tests/SprotnaPomocPravilaTest.kt).
 *
 * Vodja 5. 11. 2026: prosi() je pomocniku poslal `sk.zvok` nespremenjen. Pri virih z zetonom (debrid, osebni dodatki,
 * Webtor, IPTV z racunom) je to nadomestek SkrivniDodatki (»https://<gostitelj>/safeer-skrito-<odtis>«), zato je pomocnik
 * dobil lazen naslov in pretvorba ni mogla zaceti. Pravi naslov nosi zeton uporabnikovega racuna: dobi ga SAMO naprava
 * v ozjem krogu te naprave (DostopPravila.OZJI_KROG), enako glave toka (kljuc vira API, piskotki). Napravi zunaj ozjega
 * kroga takega vira ne damo - za ta video je ne izberemo.
 */
object SprotnaPomocPravila {
    /** Vir nosi skrivnost: nadomestek SkrivniDodatki ali glave toka (proxyHeaders). */
    fun nosiSkrivnost(zvok: String, imaGlave: Boolean): Boolean = imaGlave || SkrivnostiPravila.jeNadomestek(zvok)

    /** Ali sme pomocnik dobiti ta vir: brez skrivnosti vsak, s skrivnostjo samo ozji krog. */
    fun sme(nosiSkrivnost: Boolean, vOzjemKrogu: Boolean): Boolean = !nosiSkrivnost || vOzjemKrogu

    /**
     * Naslov za pomocnika: pravi ([dejanski]) samo napravi v ozjem krogu, ce je [zvok] nadomestek; brez nadomestka
     * nespremenjen. null = te naprave za ta vir ne prosimo.
     */
    fun naslov(zvok: String, vOzjemKrogu: Boolean, dejanski: (String) -> String): String? = when {
        !SkrivnostiPravila.jeNadomestek(zvok) -> zvok
        !vOzjemKrogu -> null
        else -> dejanski(zvok).takeIf { !SkrivnostiPravila.jeNadomestek(it) }
    }

    /** Glave toka samo napravi v ozjem krogu (prazno, ce jih ni). */
    fun glave(glave: Map<String, String>, vOzjemKrogu: Boolean): Map<String, String> = if (vOzjemKrogu) glave else emptyMap()
}
