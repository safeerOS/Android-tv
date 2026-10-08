package si.safeer.tv.os

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import si.safeer.tv.R

/** Izbira in upravljanje zvocnika v omrezju iz zaslonov Glasba in Predvajanje (isti pogovorni okni). */
object ZvocnikIzbira {
    private fun pokazi(a: Activity, g: AlertDialog.Builder): AlertDialog {
        val okno = g.create()
        okno.setOnShowListener { okno.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus() }
        okno.show()
        Kontroler.pokazi(okno)
        return okno
    }

    private fun gradnik(a: Activity) = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    fun izberi(a: Activity, sk: Jamendo.Skladba, spremenjeno: () -> Unit) {
        val iscem = pokazi(a, gradnik(a).setTitle(R.string.zvocnik_predvajaj_na).setMessage(R.string.zvocnik_iscem)
            .setNegativeButton(a.getString(R.string.os_preklici), null))
        Zvocniki.najdi(a) { najdeni ->
            if (!iscem.isShowing || a.isFinishing) return@najdi
            iscem.dismiss()
            if (najdeni.isEmpty()) {
                pokazi(a, gradnik(a).setTitle(R.string.zvocnik_predvajaj_na).setMessage(R.string.zvocnik_ni_najdenih)
                    .setNegativeButton(a.getString(R.string.os_preklici), null))
                return@najdi
            }
            pokazi(a, gradnik(a).setTitle(R.string.zvocnik_predvajaj_na)
                .setItems(najdeni.map { z -> if (z.model.isNotBlank() && z.model != z.ime) "${z.ime} · ${z.model}" else z.ime }.toTypedArray()) { _, i ->
                    val z = najdeni[i]
                    Zvocniki.tujVir(z) { vir ->
                        if (a.isFinishing) return@tujVir
                        if (vir == null) poslji(a, z, sk, spremenjeno)
                        else pokazi(a, gradnik(a).setTitle(z.ime).setMessage(a.getString(R.string.zvocnik_zaseden, z.ime, vir))
                            .setPositiveButton(R.string.zvocnik_preklopi) { _, _ -> poslji(a, z, sk, spremenjeno) }
                            .setNegativeButton(a.getString(R.string.os_preklici), null))
                    }
                }
                .setNegativeButton(a.getString(R.string.os_preklici), null))
        }
    }

    private fun poslji(a: Activity, z: DlnaPravila.Zvocnik, sk: Jamendo.Skladba, spremenjeno: () -> Unit) {
        // Skladba nadaljuje na zvocniku na istem mestu; ta naprava postane daljinec (ena glasba naenkrat).
        GlasbaStoritev.naZvocnik(a, z, sk) { napaka ->
            // Zagon, ki ga je prehitela novejsa izbira (ali konec seje), ni ne napaka zvocnika ne »zdaj igra«.
            if (napaka is Zvocniki.Prehiteno) { }
            else if (napaka != null) Toast.makeText(a, a.getString(R.string.zvocnik_napaka, z.ime), Toast.LENGTH_LONG).show()
            else Toast.makeText(a, a.getString(R.string.zvocnik_igra, z.ime), Toast.LENGTH_SHORT).show()
            spremenjeno()
        }
    }

    /**
     * Upravljanje zvocnika, na katerem igra glasba: isti ukazi kot glavni gumbi predvajalnika (tam so tudi drsnik, +-10 s
     * in naprej/nazaj). Glava pove ime zvocnika in glasnost; »Glasneje« in »Tisje« okna ne zapreta - glasnost gre po
     * korakih (prej se je okno po vsakem koraku zaprlo in nove glasnosti ni pokazalo). Druga dejanja okno zaprejo.
     * Okno sledi seji: ko glasbe na tem zvocniku ni vec (prevzet, ustavljen, konec vrste), se zapre - prej je ostalo odprto
     * in »Premor / nadaljuj« je zagnal predvajalnik te naprave. Stevilka glasnosti se po korakih umiri na vrednosti, ki jo
     * je zvocnik potrdil (hitri dotiki se zdruzijo v en korak; glasnost se lahko spremeni tudi na zvocniku samem).
     */
    fun upravljaj(a: Activity, spremenjeno: () -> Unit) {
        val z = Zvocniki.aktivni ?: return
        val g = gradnik(a)
        val rob = (24 * a.resources.displayMetrics.density).toInt()
        val glasnost = TextView(g.context).apply { setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small) }
        val glava = LinearLayout(g.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(rob, rob * 3 / 4, rob, rob / 4)
            addView(TextView(g.context).apply {
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_DialogWindowTitle)
                text = a.getString(R.string.zvocnik_na, z.ime)
            })
            addView(glasnost)
        }
        // Prikazana glasnost sledi korakom takoj (zvocnik jo potrdi z zamikom); neznana glasnost se ne pise.
        var prikazana = Zvocniki.glasnost
        var zadnjiKorak = 0L
        fun pokaziGlasnost() {
            glasnost.text = if (prikazana >= 0) a.getString(R.string.zvocnik_glasnost, prikazana) else ""
            glasnost.visibility = if (prikazana >= 0) View.VISIBLE else View.GONE
        }
        fun spremeni(navzgor: Boolean) {
            val korak = ZvocnikPravila.najvecDvig(Zvocniki.najGlasnost, Zvocniki.lestvicaZnana).let { if (navzgor) it else -it }
            Zvocniki.glasnostZa(korak)
            zadnjiKorak = android.os.SystemClock.uptimeMillis()
            if (prikazana >= 0) prikazana = ZvocnikPravila.novaGlasnost(prikazana, korak, Zvocniki.najGlasnost, Zvocniki.lestvicaZnana)
            pokaziGlasnost()
        }
        pokaziGlasnost()
        val imena = arrayOf(a.getString(R.string.zvocnik_premor), a.getString(R.string.zvocnik_glasneje),
            a.getString(R.string.zvocnik_tisje), a.getString(R.string.zvocnik_vrni))
        // Brez poslusalca seznama: okno se ob dotiku vrstice ne zapre samo - o tem odloca poslusalec spodaj.
        val okno = pokazi(a, g.setCustomTitle(glava).setItems(imena, null).setNegativeButton(a.getString(R.string.os_preklici), null))
        val glavna = android.os.Handler(android.os.Looper.getMainLooper())
        fun sejaTraja() = Zvocniki.aktivni?.udn == z.udn
        val sledi = object : Runnable {
            override fun run() {
                // Unicen zaslon okna ne zapre (ostane »prikazano«): sledenje se mora ustaviti samo.
                if (!okno.isShowing || a.isFinishing || a.isDestroyed) return
                // Zaslon v ozadju (okno ni vidno): sledenje miruje, preveri spet cez cas (R6-A4b).
                if (okno.window?.decorView?.windowVisibility != View.VISIBLE) { glavna.postDelayed(this, 2_000L); return }
                if (!sejaTraja()) { okno.dismiss(); spremenjeno(); return }
                // Kar je zvocnik potrdil, ko koraki mirujejo (med koraki velja napoved, da stevilka ne skace nazaj).
                val potrjena = Zvocniki.glasnost
                if (potrjena >= 0 && potrjena != prikazana && android.os.SystemClock.uptimeMillis() - zadnjiKorak > 1_200L) { prikazana = potrjena; pokaziGlasnost() }
                glavna.postDelayed(this, 400L)
            }
        }
        glavna.postDelayed(sledi, 400L)
        okno.setOnDismissListener { glavna.removeCallbacks(sledi) }
        okno.listView?.setOnItemClickListener { _, _, i, _ ->
            // Seje ni vec: okno ne upravlja nicesar (vrstica »Premor / nadaljuj« ne sme zagnati predvajalnika te naprave).
            if (!sejaTraja()) { okno.dismiss(); spremenjeno(); return@setOnItemClickListener }
            when (i) {
                0 -> { GlasbaStoritev.preklopi(); okno.dismiss() }
                1 -> spremeni(true)
                2 -> spremeni(false)
                else -> { GlasbaStoritev.vrniNaNapravo(); okno.dismiss(); spremenjeno() }
            }
        }
    }
}
