package si.safeer.tv.os

/**
 * Kdaj zacasen torrent (film iz kataloga, pomoc drugi napravi v Linku) izgine sam. Matej, 3. 10. 2026: "ce uporabnik
 * torrenta ne uporablja vec, ga naprava samodejno odstrani." Cista pravila brez Androida (tests/ZacasniPravilaTest.kt);
 * ista kot na racunalniku (core/link_datoteke.py: RABA_VELJA_S, RABA_V_TEKU_S).
 */
object ZacasniPravila {
    /** Po toliko casu brez predvajanja torrent in njegove datoteke izginejo (film, ki ga prekines, naslednji vecer se caka). */
    const val VELJA_MS = 48L * 3_600_000
    /** Ob pomanjkanju prostora gredo tudi mlajsi, a ne tisti, ki jih je kdo predvajal v zadnjih urah (film se tece). */
    const val V_TEKU_MS = 6L * 3_600_000

    /** [cas] = zadnja raba; [primanjkuje] = za nov film ni dovolj prostora. */
    fun odstrani(cas: Long, zdaj: Long, primanjkuje: Boolean, velja: Long = VELJA_MS, vTeku: Long = V_TEKU_MS): Boolean =
        zdaj - cas > velja || (primanjkuje && zdaj - cas > vTeku)

    /** Vrstni red odstranjevanja: najdlje neuporabljeni prvi. Vnos = (cas zadnje rabe, kljuc). */
    fun <T> poVrsti(vnosi: List<Pair<Long, T>>): List<Pair<Long, T>> = vnosi.sortedBy { it.first }

    /** Kaj od zacasnih torrentov bi ob pomanjkanju prostora smeli sprostiti (za izracun prostora, ki je napravi na voljo). */
    fun sprostljiv(cas: Long, zdaj: Long, vTeku: Long = V_TEKU_MS): Boolean = zdaj - cas > vTeku
}
