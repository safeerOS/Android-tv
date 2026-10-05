package si.safeer.tv.cast

/**
 * Katero deljenje zaslona stoji za obvestilom o prihajajoci vsebini (CastReceiverService, id 4041).
 *
 * Obvestilo objavimo, kadar Safeer ni v ospredju in Android zagona dejavnosti ne dovoli. Ko se deljenje konca,
 * obvestilo nima vec cesa odpreti - do 0.5.52 je ostalo (izmerjeno 5. 10. 2026: se dve uri po koncu deljenja).
 * Isto obvestilo sluzi tudi poslani strani in videu; teh konec deljenja zaslona ne sme pospraviti.
 * Brez Androida, zato preizkusljivo v navadnem JVM (tests/ObvestiloZaslonaTest.kt).
 */
class ObvestiloZaslona {
    private var deljenje: String? = null      // null = obvestila za zaslon ni

    /** Obvestilo je objavljeno za deljen zaslon [idDeljenja] (prazen id: sredisce ga ni povedalo). */
    @Synchronized
    fun zaZaslon(idDeljenja: String) { deljenje = idDeljenja }

    /** Obvestilo je objavljeno za drugo vsebino (poslana stran, video). */
    @Synchronized
    fun zaDrugo() { deljenje = null }

    /** Obvestilo je pospravljeno (vsebina se je odprla ali smo ga umaknili). */
    @Synchronized
    fun pospravljeno() { deljenje = null }

    /** Konec deljenja [idDeljenja]: ali je treba obvestilo pospraviti. Vrne true najvec enkrat za isto obvestilo. */
    @Synchronized
    fun obKoncu(idDeljenja: String): Boolean {
        val nase = deljenje ?: return false
        if (nase.isNotEmpty() && idDeljenja.isNotEmpty() && nase != idDeljenja) return false
        deljenje = null
        return true
    }
}
