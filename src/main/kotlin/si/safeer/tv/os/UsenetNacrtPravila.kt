package si.safeer.tv.os

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object UsenetNacrtPravila {
    private const val TAG = "SafeerOsMedia"

    /** Izid nacrta: ali je predvajljivo na Usenetu. */
    data class Nacrt(val predvajljiv: Boolean, val razlog: String, val cas: Long = System.currentTimeMillis())

    /** Pomnilnik nacrtov. Kljuc je id naslova (npr. imdbId). TTL je 30 min. */
    private val nacrti = ConcurrentHashMap<String, Nacrt>()
    private const val TTL_MS = 30 * 60_000L

    fun shraniNacrt(id: String, predvajljiv: Boolean, razlog: String) {
        nacrti[id] = Nacrt(predvajljiv, razlog)
        Log.i(TAG, "nacrt: Usenet predvajljiv=${if (predvajljiv) "da" else "ne"} ($razlog)")
    }

    fun preberiNacrt(id: String): Nacrt? {
        val n = nacrti[id] ?: return null
        if (System.currentTimeMillis() - n.cas > TTL_MS) {
            nacrti.remove(id)
            return null
        }
        return n
    }

    /** Preklic nacrtov: pobrise zastarele, da ne polnimo pomnilnika, ali ob novem iskanju. */
    fun pocisti() {
        // Can be used to cancel ongoing operations if needed.
    }
}
