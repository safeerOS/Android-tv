package si.safeer.tv.os

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper

/**
 * Koda za novo napravo, pokazana cez karkoli je na zaslonu Safeer OS - tudi cez sliko racunalnika.
 * Dokler je zaslon spredaj, vsaki dve sekundi vprasa sredisce po cakajocih prijavah
 * ([KodaSeznanitve]); ko prijave ni vec (naprava je kodo vtipkala ali je potekla), okno izgine.
 */
class KodaNaZaslonu(private val a: Activity) {

    private val glavna = Handler(Looper.getMainLooper())
    private var tece = false
    private var okno: AlertDialog? = null
    private var id: String? = null

    private val preveri = object : Runnable {
        override fun run() {
            if (!tece) return
            KodaSeznanitve.poglej(a) { seznam ->
                if (!tece) return@poglej
                pokazi(seznam.lastOrNull())
                glavna.postDelayed(this, 2_000)
            }
        }
    }

    fun zacni() {
        tece = true
        glavna.removeCallbacks(preveri)
        glavna.post(preveri)
    }

    fun ustavi() {
        tece = false
        glavna.removeCallbacks(preveri)
        okno?.let { if (it.isShowing) it.dismiss() }
        okno = null; id = null
    }

    private fun pokazi(p: KodaSeznanitve.Prijava?) {
        if (p == null) {
            okno?.let { if (it.isShowing) it.dismiss() }
            okno = null; id = null
            return
        }
        if (id == p.pairId && okno?.isShowing == true) return
        okno?.let { if (it.isShowing) it.dismiss() }
        val vsebina = android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 24, 64, 8)
            addView(android.widget.TextView(a).apply {
                text = a.getString(si.safeer.tv.R.string.os_seznanitev_opis, p.ime)
                textSize = 18f
            })
            addView(android.widget.TextView(a).apply {
                text = p.pin.map { it.toString() }.joinToString(" ")
                textSize = 44f
                setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 28, 0, 12)
            })
        }
        val pairId = p.pairId
        val novo = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(a.getString(si.safeer.tv.R.string.os_seznanitev_naslov))
            .setView(vsebina)
            .setNegativeButton(a.getString(si.safeer.tv.R.string.os_seznanitev_zavrni)) { _, _ -> KodaSeznanitve.zavrni(a, pairId) }
            .setPositiveButton(a.getString(android.R.string.ok), null)
            .create()
        okno = novo; id = pairId
        Kontroler.pokazi(novo)
        novo.show()
    }
}
