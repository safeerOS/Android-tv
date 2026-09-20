package si.safeer.tv.link

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Safeer Vnos: dotik, poteg in tipke z racunalnika (Safeer Control, Safeer OS na racunalniku) na tej
 * tablici - da je aplikacijo, ki jo tablica deli na racunalnik, tam mogoce tudi upravljati z misko
 * in tipkovnico.
 *
 * Android drugi aplikaciji ne dovoli dotikati se zaslona; edina pot brez korenskega dostopa je
 * storitev dostopnosti, ki jo uporabnik enkrat sam vklopi v nastavitvah (Dostopnost -> Safeer Vnos).
 * Storitev ne bere vsebine zaslona in je ne posilja nikamor: samo izvede dotik, poteg ali sistemsko
 * tipko, ki jih poslje seznanjena naprava v Safeer Linku (ukazi `input.*`, Daljinec).
 *
 * Koordinate pridejo kot delez zaslona (0..1), ker gledalec na racunalniku vidi pomanjsano sliko.
 */
class VnosStoritev : AccessibilityService() {

    companion object {
        private const val TAG = "SafeerVnos"

        @Volatile private var instanca: VnosStoritev? = null

        /** Ali je uporabnik Safeer Vnos vklopil (in ga sistem tece). */
        fun aktivna(): Boolean = instanca != null

        /** Dotik na delezu zaslona (0..1). */
        fun dotik(x: Double, y: Double, trajanjeMs: Long = 60): Boolean =
            instanca?.poteza(x, y, x, y, trajanjeMs) ?: false

        /** Poteg od (x1, y1) do (x2, y2), deleza zaslona. */
        fun poteg(x1: Double, y1: Double, x2: Double, y2: Double, trajanjeMs: Long = 300): Boolean =
            instanca?.poteza(x1, y1, x2, y2, trajanjeMs) ?: false

        /** Sistemska tipka: back, home, recents, notifications. */
        fun tipka(ime: String): Boolean {
            val s = instanca ?: return false
            val dejanje = when (ime.lowercase()) {
                "back", "nazaj" -> GLOBAL_ACTION_BACK
                "home", "domov" -> GLOBAL_ACTION_HOME
                "recents", "nedavne" -> GLOBAL_ACTION_RECENTS
                "notifications", "obvestila" -> GLOBAL_ACTION_NOTIFICATIONS
                else -> return false
            }
            return s.performGlobalAction(dejanje)
        }

        /** Besedilo v polje, ki ima fokus (npr. iskanje v aplikaciji); doda k obstojecemu. */
        fun besedilo(niz: String): Boolean {
            val s = instanca ?: return false
            val polje = try { s.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Throwable) { null }
                ?: return false
            val staro = polje.text?.toString().orEmpty()
            val argumenti = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, staro + niz)
            }
            return polje.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, argumenti)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanca = this
        Log.i(TAG, "Safeer Vnos je vklopljen.")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instanca = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instanca = null
        super.onDestroy()
    }

    // Dogodkov ne beremo: storitev samo izvaja, kar poslje seznanjena naprava.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun poteza(x1: Double, y1: Double, x2: Double, y2: Double, trajanjeMs: Long): Boolean {
        val dm = resources.displayMetrics
        val (sirina, visina) = try {
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            if (Build.VERSION.SDK_INT >= 30) {
                val b = wm.currentWindowMetrics.bounds
                b.width() to b.height()
            } else {
                val r = android.util.DisplayMetrics()
                @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(r)
                r.widthPixels to r.heightPixels
            }
        } catch (_: Throwable) { dm.widthPixels to dm.heightPixels }
        fun px(d: Double, n: Int) = (d.coerceIn(0.0, 1.0) * (n - 1)).toFloat()
        val pot = Path().apply {
            moveTo(px(x1, sirina), px(y1, visina))
            lineTo(px(x2, sirina), px(y2, visina))
        }
        val gib = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(pot, 0, trajanjeMs.coerceIn(1, 3000)))
            .build()
        return try { dispatchGesture(gib, null, null) } catch (e: Throwable) {
            Log.w(TAG, "Poteze ni bilo mogoce izvesti: ${e.message}")
            false
        }
    }
}
