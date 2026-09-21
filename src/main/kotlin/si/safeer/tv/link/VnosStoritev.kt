package si.safeer.tv.link

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Safeer Vnos: miska in tipkovnica z racunalnika (Safeer Control, Safeer OS na racunalniku) na tej
 * napravi - da je aplikacijo, ki jo naprava deli na racunalnik, tam mogoce upravljati kot namizni
 * program.
 *
 * Android drugi aplikaciji ne dovoli dotikati se zaslona; edina pot brez korenskega dostopa je
 * storitev dostopnosti, ki jo uporabnik enkrat sam vklopi v nastavitvah (Dostopnost -> Safeer Vnos).
 * Storitev ne posilja vsebine zaslona nikamor: samo izvede dotik, poteg, pomik ali tipko, ki jih
 * poslje seznanjena naprava v Safeer Linku (ukazi `input.*`, Daljinec).
 *
 * Prilagoditev je tu, na napravi, ker samo ona ve, kaksna je in kaj ima fokus: puscice v besedilnem
 * polju premikajo kazalec, drugje fokus (na televizorju kot krizec daljinca); Enter v polju potrdi
 * vnos, drugje klikne; kolesce na zaslonu na dotik pomakne vsebino brez zaleta, na televizorju
 * premakne fokus - aplikacije za televizor so narejene za krizec, ne za poteg.
 *
 * Koordinate pridejo kot delez zaslona (0..1), ker gledalec na racunalniku vidi pomanjsano sliko.
 */
class VnosStoritev : AccessibilityService() {

    companion object {
        private const val TAG = "SafeerVnos"
        /** Kolikšen del visine zaslona premakne en korak kolesca na zaslonu na dotik. */
        private const val KORAK_KOLESCA = 0.09
        private const val NAJVEC_KORAKOV = 8

        @Volatile private var instanca: VnosStoritev? = null

        /** Ali je uporabnik Safeer Vnos vklopil (in ga sistem tece). */
        fun aktivna(): Boolean = instanca != null

        /** Dotik na delezu zaslona (0..1). */
        fun dotik(x: Double, y: Double, trajanjeMs: Long = 60): Boolean =
            instanca?.poteza(x, y, x, y, trajanjeMs) ?: false

        /** Poteg od (x1, y1) do (x2, y2), deleza zaslona. */
        fun poteg(x1: Double, y1: Double, x2: Double, y2: Double, trajanjeMs: Long = 300): Boolean =
            instanca?.poteza(x1, y1, x2, y2, trajanjeMs) ?: false

        /**
         * Tipka: sistemske (back, home, recents, notifications) in tipke tipkovnice (up, down, left,
         * right, enter, backspace, delete), prilagojene temu, kar ima na napravi fokus.
         */
        fun tipka(ime: String): Boolean {
            val s = instanca ?: return false
            val k = ime.lowercase()
            val globalna = when (k) {
                "back", "nazaj" -> GLOBAL_ACTION_BACK
                "home", "domov" -> GLOBAL_ACTION_HOME
                "recents", "nedavne" -> GLOBAL_ACTION_RECENTS
                "notifications", "obvestila" -> GLOBAL_ACTION_NOTIFICATIONS
                else -> null
            }
            if (globalna != null) return s.performGlobalAction(globalna)
            return try {
                when (k) {
                    "up", "down", "left", "right" -> s.smer(k)
                    "enter", "ok", "center" -> s.potrdi()
                    "backspace" -> s.brisi(naprej = false)
                    "delete" -> s.brisi(naprej = true)
                    else -> false
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Tipke $k ni bilo mogoce izvesti: ${e.message}"); false
            }
        }

        /** Kolesce miske na delezu zaslona: [korakov] > 0 navzdol (vsebina gre gor), < 0 navzgor. */
        fun kolesce(x: Double, y: Double, korakov: Int): Boolean {
            val s = instanca ?: return false
            val n = korakov.coerceIn(-NAJVEC_KORAKOV, NAJVEC_KORAKOV)
            if (n == 0) return true
            return try { s.pomakni(x, y, n) } catch (e: Throwable) {
                Log.w(TAG, "Pomika ni bilo mogoce izvesti: ${e.message}"); false
            }
        }

        /** Besedilo v polje, ki ima fokus, na mesto kazalca (izbrano besedilo zamenja). */
        fun besedilo(niz: String): Boolean {
            val s = instanca ?: return false
            val polje = s.vnosnoPolje()?.takeIf { it.isEditable } ?: return false
            val (t, z, k) = s.stanjePolja(polje)
            return s.nastavi(polje, t.substring(0, z) + niz + t.substring(k), z + niz.length)
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

    // ------------------------------------------------------------------ fokus in besedilo

    private fun jeTelevizor(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    private fun vnosnoPolje(): AccessibilityNodeInfo? =
        try { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Throwable) { null }

    /** Besedilo polja (brez namiga v praznem polju) in izbira; brez izbire je kazalec na koncu. */
    private fun stanjePolja(polje: AccessibilityNodeInfo): Triple<String, Int, Int> {
        val namig = Build.VERSION.SDK_INT >= 26 && polje.isShowingHintText
        val t = if (namig) "" else polje.text?.toString().orEmpty()
        var z = polje.textSelectionStart
        var k = polje.textSelectionEnd
        if (z < 0 || k < 0 || z > t.length || k > t.length) { z = t.length; k = t.length }
        return Triple(t, minOf(z, k), maxOf(z, k))
    }

    private fun nastavi(polje: AccessibilityNodeInfo, besedilo: String, kazalec: Int): Boolean {
        val ok = polje.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, besedilo)
        })
        if (ok) polje.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, kazalec)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, kazalec)
        })
        return ok
    }

    /** Vracalka (znak pred kazalcem) ali Delete (znak za njim); izbrano besedilo izbrise v celoti. */
    private fun brisi(naprej: Boolean): Boolean {
        val polje = vnosnoPolje()?.takeIf { it.isEditable } ?: return false
        val (t, z0, k0) = stanjePolja(polje)
        var z = z0
        var k = k0
        if (z == k) {
            if (naprej) { if (k >= t.length) return true; k += Character.charCount(t.codePointAt(k)) }
            else { if (z == 0) return true; z -= Character.charCount(t.codePointBefore(z)) }
        }
        return nastavi(polje, t.substring(0, z) + t.substring(k), z)
    }

    /**
     * Puscica: v besedilnem polju premakne kazalec (levo/desno za znak, gor/dol za vrstico); drugje
     * premakne fokus kot krizec daljinca. Android 13+ to zna sam, starejsi (Android TV 11) ne - tam
     * poiscemo naslednji element v smeri od tistega, ki ima fokus.
     */
    private fun smer(k: String): Boolean {
        val polje = vnosnoPolje()
        if (polje != null && polje.isEditable) {
            val (akcija, enota) = when (k) {
                "left" -> AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY to AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER
                "right" -> AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY to AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER
                "up" -> AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY to AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
                else -> AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY to AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
            }
            val ok = polje.performAction(akcija, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, enota)
                putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false)
            })
            // Enovrsticno polje gor/dol ne premakne: takrat gre fokus naprej, kot na televizorju.
            if (ok) return true
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return performGlobalAction(when (k) {
                "up" -> GLOBAL_ACTION_DPAD_UP; "down" -> GLOBAL_ACTION_DPAD_DOWN
                "left" -> GLOBAL_ACTION_DPAD_LEFT; else -> GLOBAL_ACTION_DPAD_RIGHT
            })
        }
        val od = polje ?: return false
        val cilj = od.focusSearch(when (k) {
            "up" -> View.FOCUS_UP; "down" -> View.FOCUS_DOWN; "left" -> View.FOCUS_LEFT; else -> View.FOCUS_RIGHT
        }) ?: return false
        return cilj.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    /** Enter: v besedilnem polju potrdi vnos (iskanje, poslji); drugje klikne, kar ima fokus. */
    private fun potrdi(): Boolean {
        val polje = vnosnoPolje()
        if (polje != null && polje.isEditable && Build.VERSION.SDK_INT >= 30 &&
            polje.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true
        if (Build.VERSION.SDK_INT >= 33) return performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)
        var n = polje
        while (n != null && !n.isClickable) n = n.parent
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    // ------------------------------------------------------------------ kolesce

    /**
     * Na televizorju kolesce premika fokus gor in dol (aplikacije za televizor so narejene za krizec),
     * drugod pomakne vsebino pod miško za korak - s potegom, ki se na koncu ustavi, da se seznam ne
     * zalete dalec naprej, kot bi se ob hitrem potegu s prstom.
     */
    private fun pomakni(x: Double, y: Double, korakov: Int): Boolean {
        if (jeTelevizor()) {
            var ok = false
            repeat(kotlin.math.abs(korakov)) { ok = smer(if (korakov > 0) "down" else "up") || ok }
            return ok
        }
        val pot = (KORAK_KOLESCA * korakov).coerceIn(-0.45, 0.45)
        val y1 = y.coerceIn(0.05, 0.95)
        val y2 = (y1 - pot).coerceIn(0.02, 0.98)
        return poteza(x, y1, x, y2, 220L + 40L * kotlin.math.abs(korakov), zadrzi = true)
    }

    // ------------------------------------------------------------------ poteze

    private fun velikost(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return try {
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            if (Build.VERSION.SDK_INT >= 30) {
                val b: Rect = wm.currentWindowMetrics.bounds
                b.width() to b.height()
            } else {
                val r = android.util.DisplayMetrics()
                @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(r)
                r.widthPixels to r.heightPixels
            }
        } catch (_: Throwable) { dm.widthPixels to dm.heightPixels }
    }

    /**
     * Poteza od (x1, y1) do (x2, y2). [zadrzi]: prst na koncu se malo obdrzi na mestu, da je hitrost ob
     * dvigu nic - seznam se premakne natanko za pot, brez zaleta.
     */
    private fun poteza(x1: Double, y1: Double, x2: Double, y2: Double, trajanjeMs: Long, zadrzi: Boolean = false): Boolean {
        val (sirina, visina) = velikost()
        fun px(d: Double, n: Int) = (d.coerceIn(0.0, 1.0) * (n - 1)).toFloat()
        val pot = Path().apply {
            moveTo(px(x1, sirina), px(y1, visina))
            lineTo(px(x2, sirina), px(y2, visina))
        }
        val cas = trajanjeMs.coerceIn(1, 3000)
        return try {
            if (!zadrzi) {
                dispatchGesture(GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(pot, 0, cas)).build(), null, null)
            } else {
                val prvi = GestureDescription.StrokeDescription(pot, 0, cas, true)
                val mirno = Path().apply { moveTo(px(x2, sirina), px(y2, visina)); lineTo(px(x2, sirina), px(y2, visina) + 0.5f) }
                val drugi = prvi.continueStroke(mirno, 0, 90, false)
                dispatchGesture(GestureDescription.Builder().addStroke(prvi).build(), object : GestureResultCallback() {
                    override fun onCompleted(opis: GestureDescription?) {
                        try { dispatchGesture(GestureDescription.Builder().addStroke(drugi).build(), null, null) } catch (_: Throwable) { }
                    }
                }, null)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Poteze ni bilo mogoce izvesti: ${e.message}")
            false
        }
    }
}
