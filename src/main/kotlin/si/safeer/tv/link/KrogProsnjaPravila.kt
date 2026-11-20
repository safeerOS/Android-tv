package si.safeer.tv.link

/**
 * »Zahtevaj poln dostop« (lastnik 20. 11. 2026): en klik na napravi v Safeer Linku, potrditev na DRUGI napravi, nato
 * obojestranski ozji krog (vse zmoznosti) in usklajevanje kljucev - brez drugega vprasanja za kljuce.
 *
 * Prosnjo (`circle.request`) posilja naprava, ki dostop zeli; odloci samo tista, ki dostop odpira (Dovoli / Zavrni na njej).
 * Odgovor `circle.grant` je veljaven samo, ce je ta naprava prosnjo res poslala tej napravi in ni potekla.
 * Tu so samo pravila (brez Androida), preizkus: tests/KrogProsnjaPravilaTest.kt.
 */
object KrogProsnjaPravila {
    const val DEJANJE_PROSNJA = "circle.request"
    const val DEJANJE_ODGOVOR = "circle.grant"
    /** Zmoznost, s katero se naprava javi, da zna prosnjo prejeti in potrditi. */
    const val ZMOZNOST = "circle"
    /** Prosnja caka na potrditev toliko casa; poslana prosnja je veljavna toliko casa za odgovor. */
    const val VELJA_MS = 10 * 60_000L
    /** Ista naprava sme isto napravo prositi najvec enkrat na ta cas (ne zasipa z vprasanji). */
    const val RAZMAK_MS = 30_000L
    /** Najvec cakajocih prosenj hkrati (spomin ne raste z napadom). */
    const val NAJVEC_CAKAJOCIH = 8

    fun jeDejanje(d: String): Boolean = d.trim().lowercase().let { it == DEJANJE_PROSNJA || it == DEJANJE_ODGOVOR }

    enum class Odziv { VPRASAJ, ZE_ODPRTO, ZAVRNI_NI_V_KROGU, ZAVRNI_PREHITRO, ZAVRNI_PREVEC }

    /**
     * Kaj naredi prejemnik prosnje. [clanKroga]: posiljatelj je naprava, ki je dokazala kljuc in je v krogu zaupanja te
     * naprave (sicer ga ne vprasamo). [zeOdprto]: vse zmoznosti so ze odprte IN usklajevanje kljucev je ze potrjeno -
     * takrat ni novega vprasanja. [zadnjaMs]: kdaj je ista naprava nazadnje prosila (0 = nikoli). [cakajocih]: koliko
     * prosenj od DRUGIH naprav caka.
     */
    fun odziv(clanKroga: Boolean, zeOdprto: Boolean, zadnjaMs: Long, cakajocih: Int, zdaj: Long): Odziv = when {
        !clanKroga -> Odziv.ZAVRNI_NI_V_KROGU
        zeOdprto -> Odziv.ZE_ODPRTO
        zadnjaMs > 0 && zdaj - zadnjaMs in 0 until RAZMAK_MS -> Odziv.ZAVRNI_PREHITRO
        cakajocih >= NAJVEC_CAKAJOCIH -> Odziv.ZAVRNI_PREVEC
        else -> Odziv.VPRASAJ
    }

    /** Prosnja ali poslana prosnja je se veljavna (ura nazaj ali predaleč naprej ne velja). */
    fun velja(nastala: Long, zdaj: Long): Boolean = zdaj - nastala in 0 until VELJA_MS

    /** Odgovor `circle.grant` sprejmemo samo od naprave, ki smo ji prosnjo res poslali, in samo enkrat, dokler velja. */
    fun smeOdgovor(poslanaMs: Long?, zdaj: Long): Boolean = poslanaMs != null && velja(poslanaMs, zdaj)

    /** Meni naprave ponudi »Zahtevaj poln dostop«, ce naprava zna prosnjo in ni ta naprava. */
    fun ponudi(napravaZna: Boolean, jeTaNaprava: Boolean): Boolean = napravaZna && !jeTaNaprava
}
