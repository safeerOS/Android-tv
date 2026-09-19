package si.safeer.tv.os

import android.app.Activity
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Skupna osnova zaslonov Safeer OS: poskrbi, da igralni plosek dela povsod enako (glej
 * [Kontroler]). Zaslon, ki hoce kaksen gumb zase, prepise [plosekDejanje] in vrne true.
 */
open class OsActivity : Activity() {

    /** Vsi zasloni Safeer OS govorijo jezik, ki ga je uporabnik izbral
     *  (enako kot brskalnik); pri "samodejno" ostane jezik televizorja. */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(si.safeer.tv.JezikVmesnika.vKontekstu(newBase))
    }

    private val palica by lazy { Kontroler.Palica(this) }

    /** Zaslon lahko gumb prevzame; privzeto ga prepusti skupnemu prevodu. */
    open fun plosekDejanje(koda: Int): Boolean = false

    /** Zaslon lahko ob prvi uporabi ploscka osvezi svojo pomoc; privzeto ni kaj osvezevati. */
    open fun plosekZaznan() {}

    override fun dispatchKeyEvent(dogodek: KeyEvent): Boolean {
        if (Kontroler.jeDokazPloska(dogodek) &&
            Kontroler.zabelezi(this, dogodek.device)) plosekZaznan()
        if (Kontroler.tipka(this, dogodek) { koda -> plosekDejanje(koda) }) return true
        return super.dispatchKeyEvent(dogodek)
    }

    override fun onGenericMotionEvent(dogodek: MotionEvent): Boolean {
        if (Kontroler.jeOdklon(dogodek) &&
            Kontroler.zabelezi(this, dogodek.device)) plosekZaznan()
        if (palica.dogodek(dogodek)) return true
        return super.onGenericMotionEvent(dogodek)
    }

    // ------------------------------------------------------------------ koda za novo napravo

    private val glavnaNit = android.os.Handler(android.os.Looper.getMainLooper())
    private var naZaslonu = false
    private var kodaOkno: android.app.AlertDialog? = null
    private var kodaId: String? = null

    private val preveriKodo = object : Runnable {
        override fun run() {
            if (!naZaslonu) return
            KodaSeznanitve.poglej(this@OsActivity) { seznam ->
                if (!naZaslonu) return@poglej
                pokaziKodo(seznam.lastOrNull())
                glavnaNit.postDelayed(this, 2_000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        naZaslonu = true
        glavnaNit.removeCallbacks(preveriKodo)
        glavnaNit.post(preveriKodo)
    }

    /** Nova naprava se povezuje: kodo pokazemo velike stevke, cez karkoli je na zaslonu. */
    private fun pokaziKodo(p: KodaSeznanitve.Prijava?) {
        if (p == null) {
            kodaOkno?.let { if (it.isShowing) it.dismiss() }
            kodaOkno = null; kodaId = null
            return
        }
        if (kodaId == p.pairId && kodaOkno?.isShowing == true) return
        kodaOkno?.let { if (it.isShowing) it.dismiss() }
        val vsebina = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 24, 64, 8)
            addView(android.widget.TextView(this@OsActivity).apply {
                text = getString(si.safeer.tv.R.string.os_seznanitev_opis, p.ime)
                textSize = 18f
            })
            addView(android.widget.TextView(this@OsActivity).apply {
                text = p.pin.map { it.toString() }.joinToString(" ")
                textSize = 44f
                setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 28, 0, 12)
            })
        }
        val id = p.pairId
        val okno = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(si.safeer.tv.R.string.os_seznanitev_naslov))
            .setView(vsebina)
            .setNegativeButton(getString(si.safeer.tv.R.string.os_seznanitev_zavrni)) { _, _ -> KodaSeznanitve.zavrni(this, id) }
            .setPositiveButton(getString(android.R.string.ok), null)
            .create()
        kodaOkno = okno; kodaId = id
        Kontroler.pokazi(okno)
        okno.show()
    }

    override fun onPause() {
        naZaslonu = false
        glavnaNit.removeCallbacks(preveriKodo)
        kodaOkno?.let { if (it.isShowing) it.dismiss() }
        kodaOkno = null; kodaId = null
        palica.ustavi()
        super.onPause()
    }
}
