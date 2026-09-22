package si.safeer.tv

import android.app.ActivityManager
import android.content.Context

/**
 * Pravila pomnilnika brskalnika glede na napravo - ena koda za TV in tablico (95 % skupne kode):
 *
 *  - Televizor (Safeer OS TV in TV Browser, 2 GB RAM-a): najvec 5 zavihkov, v ozadju buden le
 *    zadnji zapusceni (ob pritisku nic), brez pripravljanja vsebine izven zaslona.
 *  - Tablica (naprava na dotik): mehka meja 10 zavihkov, ob obilici pomnilnika do 20; v ozadju
 *    ostanejo budni trije nazadnje uporabljeni, starejse uspavamo glede na pomnilniski pritisk
 *    (onTrimMemory/onLowMemory v MainActivity) - brez nepotrebnega unicevanja.
 */
object BrowserMemoryPolicy {

    data class Politika(
        /** Nad tem stevilom zavihkov najstarejsi zavihek zapremo. */
        val najvecZavihkov: Int,
        /** Koliko nazadnje uporabljenih zavihkov v ozadju ostane budnih, ko je pomnilnika dovolj. */
        val budnihVOzadju: Int,
        /** Pripravljanje vsebine izven vidnega obmocja (WebSettings.offscreenPreRaster). */
        val predRaster: Boolean,
        /** Pod tem delezem prostega RAM-a (v %) v ozadju ne ostane buden noben zavihek. */
        val prostegaVsaj: Int,
    )

    fun za(c: Context): Politika {
        if (!ChromiumEngineView.naDotik(c)) return Politika(najvecZavihkov = 5, budnihVOzadju = 1, predRaster = false, prostegaVsaj = 20)
        val mi = pomnilnik(c)
        val obilje = mi != null && !mi.lowMemory && mi.availMem > mi.totalMem * 2 / 5
        return Politika(najvecZavihkov = if (obilje) 20 else 10, budnihVOzadju = 3, predRaster = true, prostegaVsaj = 15)
    }

    /** Koliko zavihkov v ozadju sme ta trenutek ostati budnih (0 ob pomnilniskem pritisku). */
    fun budnihZdaj(c: Context): Int {
        val p = za(c)
        val mi = pomnilnik(c) ?: return p.budnihVOzadju
        return if (mi.lowMemory || mi.availMem * 100 < mi.totalMem * p.prostegaVsaj) 0 else p.budnihVOzadju
    }

    private fun pomnilnik(c: Context): ActivityManager.MemoryInfo? = try {
        ActivityManager.MemoryInfo().also { c.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
    } catch (_: Exception) { null }
}
