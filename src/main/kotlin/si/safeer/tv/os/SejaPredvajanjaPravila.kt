package si.safeer.tv.os

/**
 * Kdaj sistemska seja predvajanja (MediaSession) dobi novo stanje. Izmerjeno na TV 5. 11. 2026 19:29: ob menjavi skladbe je
 * Safeer OS v 54 ms poslal 6 stanj (state=2 trikrat z istim polozajem 0), Bluetooth AVRCP je vsako javil z »no new data«
 * in vsakic znova poslal metapodatke. Vzrok: osvezi() se klice ob vsakem dogodku predvajalnika (onIsPlayingChanged,
 * onPlayWhenReadyChanged, onPlaybackStateChanged, onMediaItemTransition, ICY ...) in vsakic posilja sejo in obvestilo.
 * Sistem polozaj med predvajanjem izracuna sam (polozaj + (zdaj - posodobljeno) * hitrost), zato polozaj posljemo samo ob
 * skoku, premoru ali zacetku. Cista pravila - tests/SejaPredvajanjaPravilaTest.kt.
 */
object SejaPredvajanjaPravila {
    /** PlaybackState.STATE_PLAYING (brez Androida v preizkusu). */
    const val PREDVAJA = 3
    /** Odstopanje polozaja, ki se steje za skok (manjse je zaokrozitev ure in predvajalnika). */
    const val SKOK_MS = 1_500L

    /** Kar seja pove sistemu; [posodobljenoMs] = elapsedRealtime ob posiljanju (kot PlaybackState.Builder.setState). */
    data class Stanje(val stanje: Int, val hitrost: Float, val akcije: Long, val posebne: List<String>,
                      val polozajMs: Long, val posodobljenoMs: Long)

    /** Polozaj, ki ga sistem v [zdajMs] izracuna iz prej poslanega stanja. */
    fun pricakovanPolozaj(prej: Stanje, zdajMs: Long): Long =
        if (prej.stanje == PREDVAJA && prej.hitrost > 0f) prej.polozajMs + ((zdajMs - prej.posodobljenoMs).coerceAtLeast(0L) * prej.hitrost).toLong()
        else prej.polozajMs

    /** true = stanje [zdaj] je treba poslati: prvo, drugo stanje/hitrost/akcije/posebne akcije ali skok polozaja. */
    fun bistvenoSpremenjeno(prej: Stanje?, zdaj: Stanje): Boolean {
        if (prej == null) return true
        if (prej.stanje != zdaj.stanje || prej.hitrost != zdaj.hitrost || prej.akcije != zdaj.akcije || prej.posebne != zdaj.posebne) return true
        return kotlin.math.abs(zdaj.polozajMs - pricakovanPolozaj(prej, zdaj.posodobljenoMs)) > SKOK_MS
    }
}
