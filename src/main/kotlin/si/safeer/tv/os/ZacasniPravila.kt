package si.safeer.tv.os

/**
 * Kdaj zacasen torrent (film iz kataloga, pomoc drugi napravi v Linku) izgine sam. Lastnik, 3. 10. 2026: "ce uporabnik
 * torrenta ne uporablja vec, ga naprava samodejno odstrani." Cista pravila brez Androida (tests/ZacasniPravilaTest.kt);
 * ista kot na racunalniku (core/link_datoteke.py: RABA_VELJA_S, RABA_V_TEKU_S).
 */
object ZacasniPravila {
    /**
     * Po toliko casu od zadnjega predvajanja torrent in njegove datoteke izginejo. Lastnik, 25. 10. 2026: kar se prek
     * torrenta prenese neposredno na napravo, gre 12 ur po zadnjem predvajanju (prej 48 ur); izjema je, kar uporabnik
     * izrecno shrani (»Obdrži« ali Prenesi - ta ni zacasen). Ob zagonu se pospravi vse, kar je starejse od 12 ur.
     */
    const val VELJA_MS = 12L * 3_600_000
    /** Ob pomanjkanju prostora gredo tudi mlajsi, a ne tisti, ki jih je kdo predvajal v zadnjih urah (film se tece). */
    const val V_TEKU_MS = 6L * 3_600_000

    /** [cas] = zadnja raba; [primanjkuje] = za nov film ni dovolj prostora. */
    fun odstrani(cas: Long, zdaj: Long, primanjkuje: Boolean, velja: Long = VELJA_MS, vTeku: Long = V_TEKU_MS,
                 /** »Obdrži«: uporabnik hoce, da film ostane - ne potece in ne gre niti ob pomanjkanju prostora. */
                 obdrzan: Boolean = false): Boolean =
        !obdrzan && (zdaj - cas > velja || (primanjkuje && zdaj - cas > vTeku))

    /** Vrstni red odstranjevanja: najdlje neuporabljeni prvi. Vnos = (cas zadnje rabe, kljuc). */
    fun <T> poVrsti(vnosi: List<Pair<Long, T>>): List<Pair<Long, T>> = vnosi.sortedBy { it.first }

    /** Kaj od zacasnih torrentov bi ob pomanjkanju prostora smeli sprostiti (za izracun prostora, ki je napravi na voljo). */
    fun sprostljiv(cas: Long, zdaj: Long, vTeku: Long = V_TEKU_MS, obdrzan: Boolean = false): Boolean = !obdrzan && zdaj - cas > vTeku
}
