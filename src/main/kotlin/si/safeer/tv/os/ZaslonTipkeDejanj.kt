package si.safeer.tv.os

import android.content.Context
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import si.safeer.tv.R

/**
 * Pas tipk dejanj na tablici in telefonu (zamisel Stream Decka): pogosta dejanja racunalnika z enim dotikom, brez
 * ciljanja s kazalcem in brez menija. Tipka poslje samo ime dejanja (racunalnik ga izvede sam, Windows in Linux poznata
 * ista imena), odziv pa pokaze takoj naprava sama (blisk in tresljaj) - ne caka na sliko.
 */
class ZaslonTipkeDejanj(
    private val ctx: Context,
    private val koren: FrameLayout,
    private val tipka: (String) -> Unit,
    private val tipkovnica: () -> Unit,
) {
    private val d = ctx.resources.displayMetrics.density
    private val nast = ctx.getSharedPreferences("safeer_os", Context.MODE_PRIVATE)
    private val pas = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(0xE6090D15.toInt())
        visibility = if (nast.getBoolean(KLJUC, false)) View.VISIBLE else View.GONE
    }

    /** (znak, besedilo, dejanje): dejanje je ime tipke za racunalnik ali null za tipkovnico na napravi. */
    private val tipke = listOf(
        Triple("⇆", R.string.os_tipke_okna, "preklopi_okno"),
        Triple("⏯", R.string.os_tipke_predvajaj, "predvajaj"),
        Triple("−", R.string.os_tipke_tise, "tiseje"),
        Triple("+", R.string.os_tipke_glasneje, "glasneje"),
        Triple("∅", R.string.os_tipke_utisaj, "utisaj"),
        Triple("⎋", R.string.os_tipke_esc, "ubezna"),
        Triple("⎘", R.string.os_bliznjica_kopiraj, "kopiraj"),
        Triple("⎙", R.string.os_bliznjica_prilepi, "prilepi"),
        Triple("↶", R.string.os_bliznjica_razveljavi, "razveljavi"),
        Triple("⌨", R.string.os_zaslon_meni_tipkovnica, null),
    )

    init {
        val vrsta = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
        }
        for ((znak, besedilo, dejanje) in tipke) {
            val t = TextView(ctx).apply {
                text = "$znak\n${ctx.getString(besedilo)}"
                textSize = 12f
                setLineSpacing(0f, 1.1f)
                gravity = Gravity.CENTER
                setTextColor(ctx.getColor(R.color.os_besedilo))
                setBackgroundResource(R.drawable.os_znacka)
                minWidth = (64 * d).toInt()
                setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
                contentDescription = ctx.getString(besedilo)
                setOnClickListener { v ->
                    // Odziv takoj na napravi: tresljaj in kratek blisk, preden racunalnik sploh odgovori.
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    v.alpha = 0.4f
                    v.animate().alpha(1f).setDuration(180).start()
                    if (dejanje == null) tipkovnica() else tipka(dejanje)
                }
            }
            vrsta.addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (56 * d).toInt()).apply {
                marginEnd = (6 * d).toInt()
            })
        }
        pas.addView(vrsta)
        koren.addView(pas, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            marginEnd = (136 * d).toInt()       // desno ostaneta gumba ▦ in ☰, pas ju ne prekrije
        })
    }

    val viden: Boolean get() = pas.visibility == View.VISIBLE

    /** Pokaze ali skrije pas; izbira ostane za naslednjic. */
    fun preklopi() {
        val pokazi = !viden
        pas.visibility = if (pokazi) View.VISIBLE else View.GONE
        nast.edit().putBoolean(KLJUC, pokazi).apply()
    }

    companion object { private const val KLJUC = "zaslon_tipke_dejanj" }
}
