package si.safeer.tv.link

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import si.safeer.tv.os.OsPravila

/**
 * Daljinec kot miska v aplikacijah, ki niso narejene za televizor (lastnik 30. 10. 2026: »tako naj bo za vse
 * aplikacije, ki niso posebej prirejene za TV«, npr. telefonski TikTok). Kot nacin miske za Facebook v brskalniku:
 * smerne tipke premikajo kazalec (drzana tipka pospesuje), OK je dotik (drzan OK preklopi nacin), ob robu zaslona
 * se vsebina pomakne. Nazaj in ostale tipke gredo nespremenjene v aplikacijo. Kazalec je prekrivno okno
 * storitve dostopnosti (ne prestreza dotikov); dotik je potezo sistema (dispatchGesture).
 */
class SistemskaMiska(private val storitev: AccessibilityService) {

    private val glavna = Handler(Looper.getMainLooper())
    private val wm = storitev.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var kazalec: Kazalec? = null
    private var x = 0f
    private var y = 0f

    /** Paket v ospredju, za katerega je miska vklopljena; null = izklopljena. */
    var paket: String? = null
        private set

    private class Kazalec(c: Context) : View(c) {
        private val zunanji = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7EDCAB"); style = Paint.Style.FILL
            setShadowLayer(10f, 0f, 0f, Color.parseColor("#AA000000"))
        }
        private val obroc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f
        }
        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
        override fun onDraw(canvas: Canvas) {
            canvas.drawCircle(width / 2f, height / 2f, 12f, zunanji)
            canvas.drawCircle(width / 2f, height / 2f, 15f, obroc)
        }
    }

    private fun zaslon(): Pair<Int, Int> {
        val m = storitev.resources.displayMetrics
        return Pair(m.widthPixels, m.heightPixels)
    }

    fun vklopi(zaPaket: String) {
        if (paket == zaPaket && kazalec != null) return
        paket = zaPaket
        if (kazalec == null) {
            val (s, v) = zaslon()
            x = s / 2f; y = v / 2f
            val pogled = Kazalec(storitev)
            val lp = WindowManager.LayoutParams(
                VELIKOST, VELIKOST,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            try {
                wm.addView(pogled, lp)
                kazalec = pogled
                postavi()
            } catch (_: Throwable) {
                paket = null
            }
        }
    }

    fun izklopi() {
        paket = null
        kazalec?.let { try { wm.removeView(it) } catch (_: Throwable) { } }
        kazalec = null
    }

    private fun postavi() {
        val k = kazalec ?: return
        val lp = k.layoutParams as? WindowManager.LayoutParams ?: return
        lp.x = (x - VELIKOST / 2f).toInt()
        lp.y = (y - VELIKOST / 2f).toInt()
        try { wm.updateViewLayout(k, lp) } catch (_: Throwable) { }
    }

    /** Tipka daljinca, ko je miska vklopljena. Vrne true, ce jo je miska porabila. */
    fun tipka(e: KeyEvent): Boolean {
        if (paket == null) return false
        val dol = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (dol) glavna.post { premakni(e.keyCode, e.repeatCount) }
                return true
            }
        }
        return false
    }

    /** Kratek dotik na mestu kazalca (OK na daljincu). */
    fun klik() {
        if (paket != null) glavna.post { dotik(false) }
    }

    private fun premakni(koda: Int, ponovitve: Int) {
        val (s, v) = zaslon()
        val korak = OsPravila.korakKazalca(ponovitve) * storitev.resources.displayMetrics.density
        val rob = 8f
        when (koda) {
            KeyEvent.KEYCODE_DPAD_UP -> if (y <= rob + 1f) drsi(gor = true) else y = (y - korak).coerceAtLeast(rob)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (y >= v - rob - 1f) drsi(gor = false) else y = (y + korak).coerceAtMost(v - rob)
            KeyEvent.KEYCODE_DPAD_LEFT -> x = (x - korak).coerceAtLeast(rob)
            KeyEvent.KEYCODE_DPAD_RIGHT -> x = (x + korak).coerceAtMost(s - rob)
        }
        postavi()
    }

    private fun dotik(dolg: Boolean) {
        val pot = Path().apply { moveTo(x, y) }
        val trajanje = if (dolg) 800L else 60L
        try {
            storitev.dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(pot, 0, trajanje)).build(),
                null, null
            )
        } catch (_: Throwable) { }
    }

    /** Kazalec na robu: vsebino pomaknemo za tretjino zaslona (poteza prsta). */
    private fun drsi(gor: Boolean) {
        val (s, v) = zaslon()
        val sx = s / 2f
        val od = if (gor) v * 0.35f else v * 0.65f
        val doo = if (gor) v * 0.68f else v * 0.32f
        val pot = Path().apply { moveTo(sx, od); lineTo(sx, doo) }
        try {
            storitev.dispatchGesture(
                GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(pot, 0, 250)).build(),
                null, null
            )
        } catch (_: Throwable) { }
    }

    private companion object {
        const val VELIKOST = 48
    }
}
