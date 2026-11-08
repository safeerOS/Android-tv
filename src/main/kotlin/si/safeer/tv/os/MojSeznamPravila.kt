package si.safeer.tv.os

/**
 * Moj seznam (lastnik 8. 11. 2026, zgled Jellyfin + KefinTweaks »watchlist«): film, serija ali epizoda, shranjena za
 * kasneje, brez takojsnjega predvajanja. Cista pravila brez Androida (preizkus: tests/MojSeznamPravilaTest.kt);
 * shramba je v [MedijskiViri].
 */
object MojSeznamPravila {
    /** Najvec vsebin na seznamu - starejse odpadejo (seznam za kasneje, ne arhiv). */
    const val NAJVEC = 200

    /**
     * Delez ogleda, ko je vsebina odgledana in gre s seznama sama (zgled: »remove after watched«). Enako kot
     * [OsPravila.odgledano] (95 %) - CodeRabbit 8. 11. 2026: pri 90 % je vsebina zapustila seznam, a ostala v Nadaljuj.
     */
    const val ODGLEDANO_ODSTOTKOV = 95

    /** Kljuc vnosa: id, sezona in epizoda - serija (0/0) in njene epizode so razlicni vnosi. Prazen id ostane prazen. */
    fun kljuc(id: String, sezona: Int, epizoda: Int): String = if (id.isEmpty()) "" else "$id|$sezona|$epizoda"

    /** Na seznam gre samo video (film, serija, epizoda), ne glasba, radio ali kanal v zivo. */
    fun ponudi(video: Boolean, radio: Boolean, kanal: Boolean): Boolean = video && !radio && !kanal

    /** Dodaj na zacetek (najnovejse prvo) ali, ce je ze na seznamu, odstrani; prazen id ne spremeni nicesar. */
    fun preklopi(idji: List<String>, id: String): List<String> = when {
        id.isEmpty() -> idji
        id in idji -> idji.filter { it != id }
        else -> (listOf(id) + idji).take(NAJVEC)
    }

    /** Ogled je dovolj dolg, da vsebina zapusti seznam; brez trajanja (tok v zivo) nikoli. */
    fun odgledano(polozajMs: Long, trajanjeMs: Long): Boolean =
        trajanjeMs > 0 && polozajMs * 100 >= trajanjeMs * ODGLEDANO_ODSTOTKOV
}
