package si.safeer.tv.link

/**
 * Ciste odlocitve zank Global Linka (brez Androida, da jih preizkus izvaja).
 * Pregled 9. 11. 2026: zanka posiljanja je ob polni vrsti cakala brez meje (nit in vticnica sta ostali za vedno),
 * zanka poslusanja pa je zamik ponastavila ze ob vzpostavitvi, zato je ob takojsnjih prekinitvah poskusala na 5 s.
 */
object GlobalLinkPravila {
    enum class Akcija { CAKAJ, NADALJUJ, PREKINI }

    /** Vrsta posiljanja nad to mejo: pocakamo, da se sprazni (pocasna povezava), namesto da kopicimo v pomnilnik TV. */
    const val VRSTA_NAJVEC = 1_000_000L
    /** Dlje ne cakamo: druga stran ne bere, kanal zapremo in sprostimo nit. */
    const val VRSTA_CAKAJ_MS = 10_000L
    const val ZAMIK_PRVI_MS = 5_000L
    const val ZAMIK_NAJVEC_MS = 300_000L
    /** Povezava, ki je zdrzala toliko, je bila zdrava: naslednji poskus spet takoj po prvem zamiku. */
    const val ZDRAVA_POVEZAVA_MS = 60_000L

    fun odlocitevVrste(velikostVrste: Long, pretecenCasMs: Long, zaprta: Boolean): Akcija {
        if (zaprta) return Akcija.PREKINI
        if (velikostVrste <= VRSTA_NAJVEC) return Akcija.NADALJUJ
        if (pretecenCasMs > VRSTA_CAKAJ_MS) return Akcija.PREKINI
        return Akcija.CAKAJ
    }

    /** Koliko pocakamo ZDAJ: po zdravi povezavi prvi zamik, sicer zamik, ki je na vrsti. */
    fun zamikZdaj(naVrsti: Long, trajanjeZadnjePovezaveMs: Long): Long =
        if (trajanjeZadnjePovezaveMs >= ZDRAVA_POVEZAVA_MS) ZAMIK_PRVI_MS else naVrsti.coerceIn(ZAMIK_PRVI_MS, ZAMIK_NAJVEC_MS)

    /** Zamik za poskus po tem: dvakrat daljsi, najvec 5 min (streznika ob izpadu ne zasipamo). */
    fun naslednjiZamik(pravkarCakali: Long): Long = (pravkarCakali * 2L).coerceAtMost(ZAMIK_NAJVEC_MS)
}
