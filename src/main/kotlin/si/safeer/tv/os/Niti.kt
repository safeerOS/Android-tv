package si.safeer.tv.os

import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Revizija RAM 8. 11. 2026 (#8): bazeni s stalnim stevilom niti (newFixedThreadPool, ThreadPoolExecutor z keepAlive 0)
 * drzijo jedrne niti za vedno - Medijski center jih je imel ~35 brez dela (vsaka sklad ~1 MB navideznega, 16-64 KB RSS,
 * objekt Thread). Po [MIROVANJE_S] brez dela se nit sprosti; naslednja naloga jo naredi znova (~0,1 ms).
 */
object Niti {
    const val MIROVANJE_S = 30L

    /** Dovoli sprostitev jedrnih niti; keepAlive mora biti > 0 PRED allowCoreThreadTimeOut (sicer IllegalArgumentException). */
    fun <T : ExecutorService> sprostiMirujoce(bazen: T, sekund: Long = MIROVANJE_S): T {
        val t = bazen as? ThreadPoolExecutor ?: return bazen
        if (sekund <= 0) return bazen
        t.setKeepAliveTime(sekund, TimeUnit.SECONDS)
        t.allowCoreThreadTimeOut(true)
        return bazen
    }
}
