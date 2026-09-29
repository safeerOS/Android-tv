package si.safeer.tv.os

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import si.safeer.tv.R
import kotlin.math.abs

/**
 * Stransko vrstico na napravi z dotikom uporabnik skrije s potegom proti levemu robu in jo vrne s
 * potegom z levega roba (ali dotikom tankega rocaja). Izbira velja za vse zaslone Safeer OS
 * (skupna shramba s [StranskaVrstica]). Uporaba: v dispatchTouchEvent klici [dotik] pred super.
 */
class SkrivanjeVrstice(private val a: Activity, private val meni: View) {
    private val shramba = a.getSharedPreferences("safeer_os_vrstica", Context.MODE_PRIVATE)
    private var skrita = shramba.getBoolean("skrita", false)
    private val rocaj = View(a)
    private var x0 = 0f
    private var y0 = 0f
    private var sledim = false
    private var opravljeno = false

    init {
        rocaj.background = LayerDrawable(arrayOf(GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(0x9957D6AD.toInt()) }))
            .apply { setLayerGravity(0, Gravity.CENTER); setLayerSize(0, dp(6), dp(56)) }
        rocaj.contentDescription = a.getString(R.string.os_vrstica_pokazi)
        rocaj.setOnClickListener { nastavi(false) }
        (meni.parent as? ViewGroup)?.let { stars ->
            val lp = if (stars is LinearLayout) LinearLayout.LayoutParams(dp(14), ViewGroup.LayoutParams.MATCH_PARENT)
                else ViewGroup.LayoutParams(dp(14), ViewGroup.LayoutParams.MATCH_PARENT)
            stars.addView(rocaj, stars.indexOfChild(meni), lp)
        }
        uveljavi()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), a.resources.displayMetrics).toInt()

    private fun rocajIliMeni(): Int = meni.width + if (rocaj.visibility == View.VISIBLE) rocaj.width else 0

    private fun uveljavi() {
        meni.visibility = if (skrita) View.GONE else View.VISIBLE
        rocaj.visibility = if (skrita) View.VISIBLE else View.GONE
    }

    fun nastavi(skrij: Boolean) {
        if (skrita == skrij) return
        skrita = skrij
        shramba.edit().putBoolean("skrita", skrij).apply()
        (meni.parent as? ViewGroup)?.let { TransitionManager.beginDelayedTransition(it) }
        uveljavi()
    }

    /** true = dogodek je porabil poteg (Activity naj ga ne poslje naprej). */
    fun dotik(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.rawX; y0 = e.rawY; opravljeno = false
                // Koordinate glede na vsebnik vrstice (levo je lahko sistemska vrstica z gumbi).
                val loc = IntArray(2); ((meni.parent as? View) ?: meni).getLocationOnScreen(loc)
                val x = e.rawX - loc[0]
                sledim = if (skrita) x < dp(32) else x <= rocajIliMeni()
            }
            MotionEvent.ACTION_MOVE -> if (sledim && !opravljeno) {
                val dx = e.rawX - x0; val dy = e.rawY - y0
                if (abs(dx) > dp(48) && abs(dx) > 1.5f * abs(dy) && ((dx < 0) != skrita)) {
                    opravljeno = true
                    val preklic = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                    a.window.superDispatchTouchEvent(preklic); preklic.recycle()
                    nastavi(dx < 0)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (opravljeno) { sledim = false; return true }
        }
        return opravljeno
    }
}
