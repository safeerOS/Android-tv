package si.safeer.tv.os

import android.content.Context
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * Naloga 133: izmerjena hitrost povezave te naprave za izbiro kakovosti ([KakovostPravila]). Meri jo predvajalnik sam
 * (Media3 onBandwidthEstimate med nalaganjem toka) - brez dodatnih prenosov. Shrani se zglajeno, z casom meritve.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object HitrostPovezave {
    private const val PREFS = "safeer_hitrost"
    private const val KLJUC = "bps"
    private const val KLJUC_KDAJ = "kdaj"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Zglajena hitrost v bitih na sekundo; 0 = ni veljavne meritve (izbira ostane, kot je). */
    fun hitrost(c: Context): Long {
        val p = prefs(c)
        return if (KakovostPravila.velja(p.getLong(KLJUC_KDAJ, 0L), System.currentTimeMillis())) p.getLong(KLJUC, 0L) else 0L
    }

    fun zapomni(c: Context, bps: Long) {
        if (bps <= 0L) return
        val p = prefs(c)
        val stara = if (KakovostPravila.velja(p.getLong(KLJUC_KDAJ, 0L), System.currentTimeMillis())) p.getLong(KLJUC, 0L) else 0L
        p.edit().putLong(KLJUC, KakovostPravila.zgladi(stara, bps)).putLong(KLJUC_KDAJ, System.currentTimeMillis()).apply()
    }

    /**
     * Poslusalec predvajalnika. Media3 javi hitrost (onBandwidthEstimate) sele ob koncu prenosa - pri navadnem toku HTTP
     * (film prek debrida) je prenos odprt ves cas in meritve ni (preizkus na TV 24. 10. 2026). Zato merimo sami: v casu,
     * ko predvajalnik nalaga (onIsLoadingChanged), stejemo prejete bajte aplikacije (TrafficStats).
     */
    fun poslusalec(c: Context): AnalyticsListener = object : AnalyticsListener {
        private var od = 0L
        private var bajtovOd = 0L

        private fun prejeto(): Long = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())

        private fun konecVzorca() {
            if (od <= 0L) return
            val bps = KakovostPravila.hitrostIzVzorca(prejeto() - bajtovOd, android.os.SystemClock.elapsedRealtime() - od)
            od = 0L
            if (bps <= 0L) return
            zapomni(c, bps)
            android.util.Log.i("SafeerOsMedia", "hitrost povezave: ${bps / 1_000_000} Mb/s (zglajeno ${hitrost(c) / 1_000_000} Mb/s)")
        }

        override fun onIsLoadingChanged(eventTime: AnalyticsListener.EventTime, isLoading: Boolean) {
            if (isLoading) { od = android.os.SystemClock.elapsedRealtime(); bajtovOd = prejeto() } else konecVzorca()
        }

        override fun onMediaItemTransition(eventTime: AnalyticsListener.EventTime, mediaItem: androidx.media3.common.MediaItem?, reason: Int) = konecVzorca()

        override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) = konecVzorca()
    }
}
