package si.safeer.tv.os

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import si.safeer.tv.R

/**
 * Naloga 118: mini predvajalnik na domacem zaslonu ([ZaganjalnikActivity]) kot »dock« ob robu, ki ga izbere uporabnik
 * ([MiniPredvajalnikPravila]). Skrit je le rocica ob robu; poteg proti sredini ali dotik (OK na daljincu) ga odpre, poteg
 * proti robu ali gumb »Skrij« (Nazaj) ga skrije, sam se skrije po 8 s brez dotika. Dolg pritisk odpre izbiro mesta.
 * Gumbi gredo prek [GlasbaStoritev]: igrajo na tej napravi, na zvocniku ali na drugi napravi (naloga 117) - kot drugje.
 *
 * [okvir] je FrameLayout domacega zaslona (nad seznamom odsekov); ob vsaki prerisbi zaslona se ustvari znova.
 */
class MiniPredvajalnik(
    private val a: Activity,
    private val okvir: FrameLayout,
    private val nastavitve: SharedPreferences,
    private val daljinec: Boolean,
    private val barva: (Int) -> Int,
    private val odpriMedije: () -> Unit,
    /** Mesto se je spremenilo (domaci zaslon pokaze ali skrije kompakten predvajalnik v seznamu). */
    private val obSpremembiMesta: () -> Unit,
    /** Naloga 127: zaslon brez kompaktnega predvajalnika v seznamu (DomovActivity, DomovTabletActivity) - »v seznamu« ni izbira. */
    private val dovoliSeznam: Boolean = true,
) {
    companion object {
        const val KLJUC_MESTO = "mini_predvajalnik_mesto"

        /**
         * Naloga 127: dock na korenu okna (`android.R.id.content`, vedno FrameLayout) - za domaca zaslona brez lastnega
         * okvira (DomovActivity na TV, DomovTabletActivity na telefonu in tablici). Prezivi tudi nov setContentView.
         */
        fun naOknu(a: Activity, odpriMedije: () -> Unit): MiniPredvajalnik? {
            val okvir = a.findViewById<FrameLayout>(android.R.id.content) ?: return null
            var dock: MiniPredvajalnik? = null
            dock = MiniPredvajalnik(a, okvir, a.getSharedPreferences("safeer_os", android.content.Context.MODE_PRIVATE),
                Naprava.jeTelevizor(a), { a.osBarva(it) }, odpriMedije, { dock?.osvezi() }, dovoliSeznam = false)
            return dock
        }
        /** Odprt ali skrit ostane cez prerisbo zaslona (zasuk, razdeljen zaslon). */
        private var odprt = false
    }

    private val glavna = Handler(Looper.getMainLooper())
    private var koren: FrameLayout? = null
    private var rocica: View? = null
    private var kartica: LinearLayout? = null
    private var zadnjiDotik = 0L

    val mesto: String get() = MiniPredvajalnikPravila.mesto(nastavitve.getString(KLJUC_MESTO, null))
        .let { if (!dovoliSeznam && it == MiniPredvajalnikPravila.V_SEZNAMU) MiniPredvajalnikPravila.PRIVZETO else it }
    val jeOdprt: Boolean get() = odprt && koren?.visibility == View.VISIBLE

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), a.resources.displayMetrics).toInt()

    /** Prerise dock po trenutnem predvajanju; ce nic ne igra ali je izbran »v seznamu«, ga ni. */
    fun osvezi() {
        val m = mesto
        val oddaljeno = try { GlasbaStoritev.naDrugiNapravi() } catch (_: Throwable) { false }
        val tu = try { GlasbaStoritev.trenutna() } catch (_: Throwable) { null }
        val daljinsko = OddaljenoPredvajanje.stanje
        val viden = MiniPredvajalnikPravila.jeDock(m) &&
            MiniPredvajalnikPravila.viden(tu != null, OddaljenoPredvajanje.cilj != null && daljinsko.kajIgra)
        if (!viden) { koren?.visibility = View.GONE; return }
        val naslov = if (oddaljeno || tu == null) daljinsko.naslov else tu.naslov
        val izvajalec = if (oddaljeno || tu == null) daljinsko.izvajalec else tu.izvajalec
        val igra = if (oddaljeno || tu == null) daljinsko.igra else try { GlasbaStoritev.igra() } catch (_: Throwable) { false }
        val radio = !oddaljeno && tu?.radio == true
        zgradi(m, naslov, izvajalec, igra, radio)
    }

    /** Nazaj (domaci zaslon): odprt dock se skrije. Vrne true, ce je bil odprt. */
    fun skrijCeOdprt(): Boolean {
        if (!jeOdprt) return false
        nastaviOdprt(false); return true
    }

    fun ustavi() { glavna.removeCallbacks(samodejno) }

    private val samodejno = object : Runnable {
        override fun run() {
            val k = kartica ?: return
            val fokus = k.findFocus() != null
            if (MiniPredvajalnikPravila.samodejnoSkrij(odprt, fokus, zadnjiDotik, SystemClock.uptimeMillis())) nastaviOdprt(false)
            else if (odprt) glavna.postDelayed(this, 1_000L)
        }
    }

    private fun nastaviOdprt(da: Boolean, fokus: Boolean = false) {
        odprt = da
        zadnjiDotik = SystemClock.uptimeMillis()
        rocica?.visibility = if (da) View.GONE else View.VISIBLE
        kartica?.visibility = if (da) View.VISIBLE else View.GONE
        glavna.removeCallbacks(samodejno)
        if (da) {
            glavna.postDelayed(samodejno, 1_000L)
            if (fokus) kartica?.findViewWithTag<View>("dock:predvajaj")?.requestFocus()
        } else if (fokus) rocica?.requestFocus()
    }

    private fun gravitacija(m: String): Int = when (m) {
        MiniPredvajalnikPravila.ZGORAJ -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        MiniPredvajalnikPravila.LEVO -> Gravity.START or Gravity.CENTER_VERTICAL
        MiniPredvajalnikPravila.DESNO -> Gravity.END or Gravity.CENTER_VERTICAL
        else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    }

    /**
     * Poteg na rocici ali kartici: odpre ali zapre po [MiniPredvajalnikPravila.poPotegu]. Poslusalec dotika ne porabi
     * (dotik, dolg pritisk in gumbi delajo kot obicajno) - porabi samo konec potega, ki je dock odprl ali zaprl.
     */
    private fun potegi(v: View, m: String) {
        var x0 = 0f; var y0 = 0f
        val prag = dp(24).toFloat()
        v.setOnTouchListener { pogled, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; zadnjiDotik = SystemClock.uptimeMillis(); false }
                MotionEvent.ACTION_UP -> when (MiniPredvajalnikPravila.poPotegu(m, e.rawX - x0, e.rawY - y0, prag)) {
                    MiniPredvajalnikPravila.ODPRI -> { pogled.isPressed = false; pogled.cancelLongPress(); nastaviOdprt(true); true }
                    MiniPredvajalnikPravila.ZAPRI -> { pogled.isPressed = false; pogled.cancelLongPress(); nastaviOdprt(false); true }
                    else -> false
                }
                else -> false
            }
        }
    }

    /**
     * Neprosojna zaobljena plosca v barvah teme (dvignjena kartica, obroba kartice); s fokusom daljinca debela obroba v
     * barvi mint - na TV se sicer ne vidi, da je fokus na rocici (24. 10.).
     */
    private fun neprosojno(): android.graphics.drawable.Drawable {
        fun plosca(obroba: Int, debelina: Int) = android.graphics.drawable.GradientDrawable().apply {
            setColor(barva(R.color.os_kartica_dvignjena))
            cornerRadius = dp(16).toFloat()
            setStroke(dp(debelina), barva(obroba))
        }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), plosca(R.color.os_mint, 3))
            addState(intArrayOf(), plosca(R.color.os_kartica_obroba, 1))
        }
    }

    private fun zgradi(m: String, naslov: String, izvajalec: String, igra: Boolean, radio: Boolean) {
        val fokusVDocku = koren?.findFocus() != null
        koren?.let { okvir.removeView(it) }
        val pokoncen = MiniPredvajalnikPravila.pokoncen(m)
        val k = FrameLayout(a)
        val rob = dp(8)

        // Rocica: kratka plosca z ikono glasbe ob robu.
        val r = ImageButton(a).apply {
            setImageResource(if (radio) R.drawable.os_ikona_radio else R.drawable.os_ikona_glasba)
            imageTintList = ColorStateList.valueOf(barva(R.color.os_mint))
            background = neprosojno()
            contentDescription = a.getString(R.string.zg_dock_pokazi) + ": " + naslov
            isFocusable = true
            setOnClickListener { nastaviOdprt(true, fokus = daljinec) }
            setOnLongClickListener { izberiMesto(); true }
        }
        potegi(r, m)
        k.addView(r, if (pokoncen) FrameLayout.LayoutParams(dp(30), dp(96), gravitacija(m)) else FrameLayout.LayoutParams(dp(96), dp(30), gravitacija(m)))

        // Kartica: kaj igra in gumbi.
        val c = LinearLayout(a).apply {
            orientation = if (pokoncen) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // Neprosojno ozadje: ploscica aplikacije je prosojna in skozi dock se je videla vsebina pod njim (telefon, 24. 10.).
            background = neprosojno()
            setPadding(dp(12), dp(8), dp(8), dp(8))
            elevation = dp(6).toFloat()
            isClickable = true
            setOnLongClickListener { izberiMesto(); true }
        }
        potegi(c, m)
        val besedila = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            isFocusable = true
            setOnClickListener { odpriMedije() }
        }
        besedila.addView(TextView(a).apply {
            text = naslov; maxLines = if (pokoncen) 2 else 1; ellipsize = TextUtils.TruncateAt.END
            setTextColor(barva(R.color.os_besedilo)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        if (izvajalec.isNotBlank()) besedila.addView(TextView(a).apply {
            text = izvajalec; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setTextColor(barva(R.color.os_umirjeno)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        if (pokoncen) c.addView(besedila, LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT))
        else c.addView(besedila, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val g = dp(if (daljinec) 48 else 44)
        fun gumb(ikona: Int, opis: String, oznaka: String, dejanje: () -> Unit) = ImageButton(a).apply {
            setImageResource(ikona); contentDescription = opis; tag = oznaka
            imageTintList = ColorStateList.valueOf(barva(R.color.os_besedilo))
            setBackgroundResource(R.drawable.os_meni_postavka)
            isFocusable = true
            setOnClickListener {
                zadnjiDotik = SystemClock.uptimeMillis()
                try { dejanje() } catch (_: Throwable) { }
                glavna.postDelayed({ osvezi() }, 300)
            }
        }
        val gumbi = LinearLayout(a).apply { orientation = if (pokoncen) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        gumbi.addView(gumb(R.drawable.os_ikona_prejsnja, a.getString(R.string.zg_prejsnja), "dock:prejsnja") { GlasbaStoritev.prejsnja() }, LinearLayout.LayoutParams(g, g))
        gumbi.addView(gumb(if (igra) R.drawable.os_ikona_pavza else R.drawable.os_ikona_predvajaj,
            a.getString(if (igra) R.string.zg_premor else R.string.zg_predvajaj), "dock:predvajaj") { GlasbaStoritev.preklopi() }, LinearLayout.LayoutParams(g, g))
        gumbi.addView(gumb(R.drawable.os_ikona_naslednja, a.getString(R.string.zg_naslednja), "dock:naslednja") { GlasbaStoritev.naslednja() }, LinearLayout.LayoutParams(g, g))
        gumbi.addView(gumb(R.drawable.os_ikona_zapri, a.getString(R.string.zg_dock_skrij), "dock:skrij") { nastaviOdprt(false, fokus = daljinec) }, LinearLayout.LayoutParams(g, g))
        c.addView(gumbi)
        val lp = if (pokoncen) FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravitacija(m))
            else FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravitacija(m)).apply {
                // Na sirokem zaslonu (tablica, TV) dock ni cez ves rob.
                width = minOf(a.resources.displayMetrics.widthPixels - 2 * rob, dp(560))
            }
        lp.setMargins(rob, rob, rob, rob)
        k.addView(c, lp)

        okvir.addView(k, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Prazen del docka ne lovi dotikov - pod njim je domaci zaslon.
        k.isClickable = false
        koren = k; rocica = r; kartica = c
        nastaviOdprt(odprt, fokus = fokusVDocku)
    }

    /** Izbira mesta (dolg pritisk na rocico ali kartico): zgoraj, spodaj, levo, desno ali v seznamu. */
    private fun izberiMesto() {
        val mesta = MiniPredvajalnikPravila.MESTA.filter { dovoliSeznam || it != MiniPredvajalnikPravila.V_SEZNAMU }
        val imena = mesta.map {
            a.getString(when (it) {
                MiniPredvajalnikPravila.ZGORAJ -> R.string.zg_dock_zgoraj
                MiniPredvajalnikPravila.LEVO -> R.string.zg_dock_levo
                MiniPredvajalnikPravila.DESNO -> R.string.zg_dock_desno
                MiniPredvajalnikPravila.V_SEZNAMU -> R.string.zg_dock_seznam
                else -> R.string.zg_dock_spodaj
            })
        }.toTypedArray()
        val okno = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.zg_dock_mesto)
            .setSingleChoiceItems(imena, mesta.indexOf(mesto)) { d, i ->
                nastavitve.edit().putString(KLJUC_MESTO, mesta[i]).apply()
                d.dismiss()
                obSpremembiMesta()
            }
            .setNegativeButton(a.getString(R.string.os_preklici), null).create()
        Kontroler.pokazi(okno)
        okno.show()
    }
}
