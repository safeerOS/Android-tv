// Nadomestek samo za tests/run_dash_jvm_test.sh (DashPrevzem na JVM); ni del aplikacije.
package android.os
import java.util.concurrent.Executors
class Looper private constructor() {
    companion object {
        val GLAVNI = Looper()
        @Volatile var glavnaNit: Thread? = null
        val izvajalec = Executors.newSingleThreadExecutor { r -> Thread(r, "main").also { it.isDaemon = true; glavnaNit = it } }
        @JvmStatic fun getMainLooper(): Looper = GLAVNI
        @JvmStatic fun myLooper(): Looper? = if (Thread.currentThread() === glavnaNit) GLAVNI else null
    }
}
class Handler(@Suppress("UNUSED_PARAMETER") l: Looper) {
    fun post(r: Runnable): Boolean { Looper.izvajalec.execute(r); return true }
}
object SystemClock {
    @JvmStatic fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000
    @JvmStatic fun uptimeMillis(): Long = System.nanoTime() / 1_000_000
}
