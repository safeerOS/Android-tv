package si.safeer.tv.os

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import si.safeer.tv.R

/**
 * Meni kanala v TV v zivo. Lastnik 4. 11. 2026 05:15 (fotografija TV): sistemski seznam besedil za »Skrij kanal« in
 * priljubljene je bil grd - zdaj temno okno Safeer OS z imenom kanala in velikimi ploscicami z ikono in napisom.
 * Daljinec: puscice med ploscicami, OK izvede, Nazaj zapre. Najvec [STOLPCEV] ploscic v vrsti, nato nova vrsta (brez
 * vodoravnega drsenja). Katera dejanja so, odloci klicatelj ([PriljubljeniKanaliPravila.meniKanala]).
 */
object MeniKanala {
    const val STOLPCEV = 4

    /** Ploscica: napis, ikona (crtna, ic_m_*), [poudarjeno] = ikona v barvi poudarka (npr. kanal je ze priljubljen). */
    class Dejanje(val napis: String, val ikona: Int, val poudarjeno: Boolean = false, val izvedi: () -> Unit)

    fun pokazi(a: Activity, naslov: String, podnaslov: String, dejanja: List<Dejanje>): AlertDialog? {
        if (dejanja.isEmpty()) return null
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), a.resources.displayMetrics).toInt()
        fun barva(atr: Int): Int = TypedValue().also { a.theme.resolveAttribute(atr, it, true) }.data
        val koren = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(28), dp(36), dp(28))
        }
        koren.addView(TextView(a).apply {
            text = naslov; textSize = 24f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(barva(R.attr.osBesedilo)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            isAccessibilityHeading = true
        })
        if (podnaslov.isNotBlank()) koren.addView(TextView(a).apply {
            text = podnaslov; textSize = 15f; setTextColor(barva(R.attr.osUmirjeno)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        var okno: AlertDialog? = null
        val ploscice = ArrayList<View>()
        var vrsta: LinearLayout? = null
        dejanja.forEachIndexed { i, d ->
            if (i % STOLPCEV == 0) vrsta = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }.also {
                koren.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(if (i == 0) 22 else 12) })
            }
            val p = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                isFocusable = true; isClickable = true
                setBackgroundResource(R.drawable.os_hitri_gumb)
                setPadding(dp(8), dp(14), dp(8), dp(10))
                contentDescription = d.napis
                addView(ImageView(a).apply {
                    setImageResource(d.ikona)
                    imageTintList = ColorStateList.valueOf(barva(if (d.poudarjeno) R.attr.osMint else R.attr.osBesedilo))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(34), dp(34)))
                addView(TextView(a).apply {
                    text = d.napis; textSize = 15f; gravity = Gravity.CENTER; maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END; setTextColor(barva(R.attr.osBesedilo))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
                setOnClickListener { okno?.dismiss(); d.izvedi() }
            }
            vrsta!!.addView(p, LinearLayout.LayoutParams(dp(150), dp(116)).apply { if (i % STOLPCEV != 0) marginStart = dp(12) })
            ploscice += p
        }
        val o = AlertDialog.Builder(a, R.style.Os_Okno).setView(koren).create()
        okno = o
        o.setOnShowListener { ploscice.first().requestFocus() }
        o.show()
        Kontroler.pokazi(o)
        return o
    }
}
