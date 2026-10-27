package si.safeer.tv.os

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import si.safeer.tv.R
import java.lang.ref.WeakReference

/**
 * Naloga 42 C (27. 10. 2026): tiho opozorilo na novo sporocilo cez katerikoli zaslon Safeer OS - tudi med predvajanjem
 * filma. Pogled ni fokusabilen in ne lovi dotikov: fokus daljinca ostane, kjer je, predvajanje tece naprej
 * ([SporocilaHitroPravila.ustaviPredvajanje]). Na zaslonu je ime in ena vrstica sporocila; izgine samo po
 * [SporocilaHitroPravila.OPOZORILO_MS]. Odgovor je na domacem zaslonu (Sporocila na hitro) ali v Sporocilih.
 */
object SporociloPasica {
    private const val OZNAKA = "k:sporocilo_pasica"
    private val glavna = Handler(Looper.getMainLooper())
    private var zadnja: WeakReference<View>? = null
    private val umakni = Runnable { umakniZdaj() }

    fun pokazi(a: Activity, ime: String, besedilo: String) {
        if (a.isFinishing || a.isDestroyed) return
        val koren = a.window?.decorView as? FrameLayout ?: return
        umakniZdaj()
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), a.resources.displayMetrics).toInt() }
        val plosca = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            tag = OZNAKA
            isFocusable = false; isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(a.osBarva(R.color.os_kartica_dvignjena))
                setStroke(dp(1), a.osBarva(R.color.os_kartica_obroba))
            }
            elevation = dp(8).toFloat()
            setPadding(dp(16), dp(10), dp(16), dp(12))
        }
        plosca.addView(TextView(a).apply {
            text = a.getString(R.string.pk_novo_sporocilo, SporocilaHitroPravila.izsek(ime, 40))
            setTextColor(a.osBarva(R.color.os_mint)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        plosca.addView(TextView(a).apply {
            text = SporocilaHitroPravila.izsek(besedilo)
            setTextColor(a.osBarva(R.color.os_besedilo)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        // Bralnik zaslona prebere opozorilo, ne da bi fokus zapustil predvajalnik.
        plosca.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        val lp = FrameLayout.LayoutParams(minOf(dp(420), a.resources.displayMetrics.widthPixels - dp(32)), FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END).apply { setMargins(dp(16), dp(24), dp(24), dp(16)) }
        koren.addView(plosca, lp)
        zadnja = WeakReference(plosca)
        glavna.postDelayed(umakni, SporocilaHitroPravila.OPOZORILO_MS)
    }

    fun umakniZdaj() {
        glavna.removeCallbacks(umakni)
        zadnja?.get()?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        zadnja = null
    }
}
