package si.safeer.tv.os

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import si.safeer.tv.R

/**
 * Sporocila na domacem zaslonu - kot na namizju Safeer OS (lastnik, 12. 10. 2026): kuverta z znacko neprebranih
 * v glavi domacega zaslona (zgoraj desno), ne postavka stranske vrstice. Na sirokem zaslonu (tablica lezece,
 * televizor) odpre stransko ploscico z zadnjimi pogovori (posiljatelj, zadeva, cas, pika neprebranega) ter
 * »Novo sporocilo« in »Odpri v celoti«; na telefonu in tablici pokonci odpre Sporocila cez ves zaslon.
 * Ena vsebina - en vstop: Sporocila so v Safeer OS dosegljiva samo tu.
 */
object SporocilaZnak {

    private const val OZNAKA_PLOSCICE = "k:sporocila_ploscica"
    private val glavna = Handler(Looper.getMainLooper())

    private fun dp(a: Activity, v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), a.resources.displayMetrics).toInt()

    /** Gumb s kuverto in znacko. [siroko] pove, ali dotik odpre ploscico ali cel zaslon Sporocil. */
    fun gumb(a: Activity, velikostDp: Int, siroko: () -> Boolean): FrameLayout {
        val okvir = FrameLayout(a).apply {
            isFocusable = true; isClickable = true
            setBackgroundResource(R.drawable.os_meni_postavka)
            contentDescription = a.getString(R.string.os_meni_sporocila)
            setOnClickListener { v -> if (siroko()) { odpirac = java.lang.ref.WeakReference(v); pokaziPloscico(a) } else odpriVse(a) }
        }
        okvir.addView(ImageView(a).apply {
            setImageResource(R.drawable.os_ikona_sporocila)
            imageTintList = ColorStateList.valueOf(a.osBarva(R.color.os_besedilo))
        }, FrameLayout.LayoutParams(dp(a, 24), dp(a, 24), Gravity.CENTER))
        val znacka = TextView(a).apply {
            gravity = Gravity.CENTER; visibility = View.GONE
            setTextColor(a.osBarva(R.color.os_ozadje)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            minWidth = dp(a, 17); setPadding(dp(a, 4), 0, dp(a, 4), 0)
            background = GradientDrawable().apply { cornerRadius = dp(a, 9).toFloat(); setColor(a.osBarva(R.color.os_mint)) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            tag = "znacka"
        }
        okvir.addView(znacka, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(a, 17), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(a, 2); marginEnd = dp(a, 2) })
        okvir.layoutParams = ViewGroup.MarginLayoutParams(dp(a, velikostDp), dp(a, velikostDp))
        return okvir
    }

    /** Stevilo neprebranih prebere v ozadju (baza) in ga vpise v znacko gumba. */
    fun osvezi(a: Activity, gumb: View?) {
        gumb ?: return
        Thread({
            val koliko = try { SporocilaShramba(a).use { s -> s.pogovori().sumOf { it.neprebrano } } } catch (_: Throwable) { 0 }
            glavna.post {
                if (a.isFinishing) return@post
                val z = gumb.findViewWithTag<TextView>("znacka") ?: return@post
                val b = ZaganjalnikPravila.besediloZnacke(koliko)
                z.text = b; z.visibility = if (b.isEmpty()) View.GONE else View.VISIBLE
                gumb.contentDescription = if (koliko > 0) a.getString(R.string.zg_znacka_opis, a.getString(R.string.os_meni_sporocila), koliko)
                    else a.getString(R.string.os_meni_sporocila)
            }
        }, "SafeerSporocilaZnak").start()
    }

    fun odpriVse(a: Activity, namera: Intent.() -> Unit = {}) {
        zapri(a)
        try { a.startActivity(Intent(a, SporocilaActivity::class.java).apply(namera)) } catch (_: Throwable) { }
    }

    /** Ploscica je odprta: Nazaj jo zapre (vrne true). */
    fun zapri(a: Activity): Boolean {
        val koren = a.findViewById<ViewGroup>(android.R.id.content) ?: return false
        val p = koren.findViewWithTag<View>(OZNAKA_PLOSCICE) ?: return false
        koren.removeView(p)
        // Fokus nazaj na kuverto (daljinec): sicer pristane na zacetku stranske vrstice.
        odpirac?.get()?.takeIf { it.isAttachedToWindow }?.requestFocus()
        return true
    }

    private var odpirac: java.lang.ref.WeakReference<View>? = null

    private fun pokaziPloscico(a: Activity) {
        if (zapri(a)) return
        val koren = a.findViewById<ViewGroup>(android.R.id.content) ?: return
        // Zavesa cez domaci zaslon: dotik izven ploscice jo zapre; tipka Nazaj (daljinec) tudi.
        val zavesa = object : FrameLayout(a) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.keyCode == KeyEvent.KEYCODE_BACK) { if (e.action == KeyEvent.ACTION_UP) zapri(a); return true }
                return super.dispatchKeyEvent(e)
            }
        }.apply {
            tag = OZNAKA_PLOSCICE
            setBackgroundColor(0x66000000)
            isClickable = true
            setOnClickListener { zapri(a) }
        }
        val ploscica = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setBackgroundColor(a.osBarva(R.color.os_meni_ozadje))
            setPadding(dp(a, 16), dp(a, 18), dp(a, 16), dp(a, 14))
        }
        ploscica.addView(TextView(a).apply {
            text = a.getString(R.string.os_meni_sporocila)
            setTextColor(a.osBarva(R.color.os_besedilo)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(a, 4), 0, 0, dp(a, 10))
        })
        val seznam = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        ploscica.addView(ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(seznam) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val gumbi = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(a, 10), 0, 0) }
        fun gumb(niz: Int, ob: () -> Unit) = TextView(a).apply {
            text = a.getString(niz); isFocusable = true; isClickable = true
            setTextColor(a.osBarva(R.color.os_mint)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10))
            setBackgroundResource(R.drawable.os_meni_postavka)
            setOnClickListener { ob() }
        }
        gumbi.addView(gumb(R.string.zg_sporocila_novo) { odpriVse(a) { putExtra(SporocilaActivity.EXTRA_NOVO, true) } })
        gumbi.addView(gumb(R.string.zg_sporocila_vse) { odpriVse(a) })
        ploscica.addView(gumbi)
        zavesa.addView(ploscica, FrameLayout.LayoutParams(dp(a, 380).coerceAtMost(koren.width.takeIf { it > 0 } ?: Int.MAX_VALUE),
            ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        koren.addView(zavesa, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        (gumbi.getChildAt(0)).requestFocus()

        Thread({
            val pogovori = try { SporocilaShramba(a).use { it.pogovori().take(12) } } catch (_: Throwable) { emptyList() }
            glavna.post {
                if (a.isFinishing || zavesa.parent == null) return@post
                if (pogovori.isEmpty()) seznam.addView(TextView(a).apply {
                    text = a.getString(R.string.os_spor_ni_pogovorov)
                    setTextColor(a.osBarva(R.color.os_umirjeno)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(a, 4), dp(a, 8), 0, 0)
                })
                for (p in pogovori) seznam.addView(vrstica(a, p))
                seznam.getChildAt(0)?.takeIf { it.isFocusable }?.requestFocus()
            }
        }, "SafeerSporocilaPloscica").start()
    }

    private fun vrstica(a: Activity, p: SporocilaShramba.Pogovor): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        isFocusable = true; isClickable = true
        setBackgroundResource(R.drawable.os_meni_postavka)
        setPadding(dp(a, 8), dp(a, 9), dp(a, 8), dp(a, 9))
        setOnClickListener {
            odpriVse(a) { putExtra(SporocilaActivity.EXTRA_POGOVOR, p.id); putExtra(SporocilaActivity.EXTRA_KANAL, p.kanalId) }
        }
        addView(View(a).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(a.osBarva(R.color.os_mint)) }
            visibility = if (p.neprebrano > 0) View.VISIBLE else View.INVISIBLE
        }, LinearLayout.LayoutParams(dp(a, 8), dp(a, 8)).apply { marginEnd = dp(a, 10) })
        val b = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val vrh = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        vrh.addView(TextView(a).apply {
            text = p.ime.ifBlank { p.oseba }; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setTextColor(a.osBarva(R.color.os_besedilo)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            if (p.neprebrano > 0) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        vrh.addView(TextView(a).apply {
            text = kratekCas(p.cas); setTextColor(a.osBarva(R.color.os_umirjeno)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(a, 8), 0, 0, 0)
        })
        b.addView(vrh)
        b.addView(TextView(a).apply {
            text = p.zadeva.ifBlank { p.zadnje }; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setTextColor(a.osBarva(R.color.os_umirjeno)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** Cas pogovora (ISO, UTC) kot v Sporocilih: danes »14:05«, letos »15. 10.«, sicer z letnico. */
    private fun kratekCas(iso: String): String {
        val d = try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(iso.take(19))
        } catch (_: Throwable) { null } ?: return ""
        val zdaj = java.util.Calendar.getInstance(); val c = java.util.Calendar.getInstance().apply { time = d }
        val leto = zdaj.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR)
        val danes = leto && zdaj.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR)
        return java.text.SimpleDateFormat(if (danes) "HH:mm" else if (leto) "d. M." else "d. M. yyyy", java.util.Locale.getDefault()).format(d)
    }
}
