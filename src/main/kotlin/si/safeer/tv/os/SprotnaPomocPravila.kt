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
 *
 * Pregled 6. 11. 2026:
 * - H1: vir na napravi v Linku (racunalnik, NAS) gre pomocniku s `fp` in `token` streznika datotek - token je nosilni
 *   zeton TE naprave pri tistem strezniku (vse datoteke, ki jih ta naprava sme brati), zato velja kot skrivnost.
 * - H2: surov naslov z zetonom (ni bil zamenjan z nadomestkom, npr. tok, ki ga je dodatek vrnil sproti) prepozna
 *   SkrivnostiPravila po zgradbi - tudi tak gre samo ozjemu krogu.
 */
object SprotnaPomocPravila {
    /** Naslov sam nosi skrivnost: nadomestek SkrivniDodatki ali po zgradbi skrivni naslov (dodatek, tok, zeton v poizvedbi). */
    fun skrivnostVNaslovu(zvok: String): Boolean =
        SkrivnostiPravila.jeNadomestek(zvok) || SkrivnostiPravila.jeSkrivenNaslov(zvok) || !SkrivnostiPravila.smeMedVseNaprave(zvok)

    /** Vir nosi skrivnost: naslov ([skrivnostVNaslovu]), glave toka (proxyHeaders) ali zeton streznika datotek ([imaStreznik]). */
    fun nosiSkrivnost(zvok: String, imaGlave: Boolean, imaStreznik: Boolean = false): Boolean =
        imaGlave || imaStreznik || skrivnostVNaslovu(zvok)

    /** Ali sme pomocnik dobiti ta vir: brez skrivnosti vsak, s skrivnostjo samo ozji krog. */
    fun sme(nosiSkrivnost: Boolean, vOzjemKrogu: Boolean): Boolean = !nosiSkrivnost || vOzjemKrogu

    /**
     * Naslov za pomocnika: pravi ([dejanski]) samo napravi v ozjem krogu, ce je [zvok] nadomestek; brez nadomestka
     * nespremenjen. null = te naprave za ta vir ne prosimo.
     */
    fun naslov(zvok: String, vOzjemKrogu: Boolean, dejanski: (String) -> String): String? = when {
        !SkrivnostiPravila.jeNadomestek(zvok) -> if (vOzjemKrogu || !skrivnostVNaslovu(zvok)) zvok else null
        !vOzjemKrogu -> null
        else -> dejanski(zvok).takeIf { !SkrivnostiPravila.jeNadomestek(it) }
    }

    /** Glave toka samo napravi v ozjem krogu (prazno, ce jih ni). */
    fun glave(glave: Map<String, String>, vOzjemKrogu: Boolean): Map<String, String> = if (vOzjemKrogu) glave else emptyMap()
}
