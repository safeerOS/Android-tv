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
                .setItems(najdeni.map { z -> imeZvocnika(z) }.toTypedArray()) { _, i -> izberiZvocnik(a, najdeni[i], sk, spremenjeno) }
                .setNegativeButton(a.getString(R.string.os_preklici), null))
        }
    }

    private fun imeZvocnika(z: DlnaPravila.Zvocnik): String =
        if (z.model.isNotBlank() && z.model != z.ime) "${z.ime} · ${z.model}" else z.ime

    private fun izberiZvocnik(a: Activity, z: DlnaPravila.Zvocnik, sk: Jamendo.Skladba, spremenjeno: () -> Unit) {
        Zvocniki.tujVir(z) { vir ->
            if (a.isFinishing) return@tujVir
            if (vir == null) poslji(a, z, sk, spremenjeno)
            else pokazi(a, gradnik(a).setTitle(z.ime).setMessage(a.getString(R.string.zvocnik_zaseden, z.ime, vir))
                .setPositiveButton(R.string.zvocnik_preklopi) { _, _ -> poslji(a, z, sk, spremenjeno) }
                .setNegativeButton(a.getString(R.string.os_preklici), null))
        }
    }

    /**
     * Lastnik 27. 10. 2026: en gumb »Predvajaj na napravi« (mini predvajalnik, predvajalnik) - zvocniki v omrezju, naprave
     * Safeer v Linku in Bluetooth/izhodi naprave ([PredvajajNaNapraviPravila]). Ce ze igra na zvocniku: upravljanje zvocnika.
     */
    fun predvajajNaNapravi(a: Activity, sk: Jamendo.Skladba, spremenjeno: () -> Unit) {
        if (Zvocniki.aktivni != null) { upravljaj(a, spremenjeno); return }
        val naslov = a.getString(R.string.zvocnik_na_napravi)
        val iscem = pokazi(a, gradnik(a).setTitle(naslov).setMessage(R.string.zvocnik_iscem)
            .setNegativeButton(a.getString(R.string.os_preklici), null))
        val naprave = try { Predaja.cilji(a) } catch (_: Throwable) { emptyList() }
        // Zvocnik dobi samo, kar zna predvajati (YouTube: ko je tok razresen - GlasbaStoritev.zaZvocnikPonudba).
        val zaZvocnik = GlasbaStoritev.zaZvocnikPonudba(a, sk) {}
        Zvocniki.najdi(a) { najdeni ->
            if (!iscem.isShowing || a.isFinishing) return@najdi
            iscem.dismiss()
            val zvocniki = if (zaZvocnik) najdeni else emptyList()
            val izbire = PredvajajNaNapraviPravila.izbire(zvocniki.size, naprave.size)
            val imena = izbire.map { (vrsta, i) ->
                when (vrsta) {
                    PredvajajNaNapraviPravila.ZVOCNIK -> imeZvocnika(zvocniki[i]) + " · " + a.getString(R.string.zvocnik_oznaka)
                    PredvajajNaNapraviPravila.NAPRAVA -> DatotekeActivity.lepoIme(naprave[i].ime) + " · Safeer"
                    else -> a.getString(R.string.zvocnik_sistemski_izhod)
                }
            }
            pokazi(a, gradnik(a).setTitle(naslov).setItems(imena.toTypedArray()) { _, k ->
                val (vrsta, i) = izbire[k]
                when (vrsta) {
                    PredvajajNaNapraviPravila.ZVOCNIK -> izberiZvocnik(a, zvocniki[i], sk, spremenjeno)
                    PredvajajNaNapraviPravila.NAPRAVA -> posljiNaNapravo(a, naprave[i], spremenjeno)
                    else -> sistemskiIzhod(a)
                }
            }.setNegativeButton(a.getString(R.string.os_preklici), null))
        }
    }

    /** Kar igra tu, drugi napravi Safeer v Linku (tam caka Sprejmi); od tod jo ta naprava upravlja (naloga 117). */
    fun posljiNaNapravo(a: Activity, c: LinkOdjemalec.Naprava, spremenjeno: () -> Unit) {
        val ime = DatotekeActivity.lepoIme(c.ime)
        Predaja.poslji(a, c) { koda ->
            if (a.isFinishing) return@poslji
            if (koda == "ok" || koda == "ok_odpri") OddaljenoPredvajanje.zacni(a, c)
            val b = when (koda) {
                "ok" -> a.getString(R.string.os_predaja_poslano, ime)
                "ok_odpri" -> a.getString(R.string.os_predaja_poslano_odpri, ime)
                "ni_deljeno" -> a.getString(R.string.os_predaja_poslji_ni_deljeno)
                "stara" -> a.getString(R.string.os_predaja_poslji_stara, ime)
                "izklopljeno" -> a.getString(R.string.os_predaja_poslji_izklopljeno, ime)
                else -> a.getString(R.string.os_predaja_poslji_napaka, ime)
            }
            Toast.makeText(a, b, Toast.LENGTH_LONG).show()
            spremenjeno()
        }
    }

    /** Bluetooth in drugi izhodi naprave: sistemski izbirnik izhoda (Android 14+), sicer nastavitve Bluetootha. */
    private fun sistemskiIzhod(a: Activity) {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            try { if (android.media.MediaRouter2.getInstance(a).showSystemOutputSwitcher()) return } catch (_: Throwable) { }
        }
        try { a.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Throwable) { }
    }

    private fun poslji(a: Activity, z: DlnaPravila.Zvocnik, sk: Jamendo.Skladba, spremenjeno: () -> Unit) {
        // Skladba nadaljuje na zvocniku na istem mestu; ta naprava postane daljinec (ena glasba naenkrat).
        GlasbaStoritev.naZvocnik(a, z, sk) { napaka ->
            // Zagon, ki ga je prehitela novejsa izbira (ali konec seje), ni ne napaka zvocnika ne »zdaj igra«.
            if (napaka is Zvocniki.Prehiteno) { }
            else if (napaka != null) Toast.makeText(a, Zvocniki.besediloNapake(a, napaka, z.ime), Toast.LENGTH_LONG).show()
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

    /**
     * Naloga 117: upravljanje druge naprave v Safeer Linku (seja [OddaljenoPredvajanje]) - kot okno zvocnika: glava pove
     * napravo, kaj igra, cas in glasnost (sledi cilju vsakih 400 ms); premor, naprej/nazaj, +-10 s in glasnost okna ne
     * zaprejo, vrsta in »Prekini upravljanje« ga. Ko cilj ne odgovarja vec, se seja konca in okno zapre s sporocilom.
     */
    fun upravljajNapravo(a: Activity, spremenjeno: () -> Unit) {
        val n = OddaljenoPredvajanje.cilj ?: return
        val ime = DatotekeActivity.lepoIme(n.ime)
        val g = gradnik(a)
        val rob = (24 * a.resources.displayMetrics.density).toInt()
        val kaj = TextView(g.context).apply { setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small) }
        val glava = LinearLayout(g.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(rob, rob * 3 / 4, rob, rob / 4)
            addView(TextView(g.context).apply {
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_DialogWindowTitle)
                text = a.getString(R.string.naprava_na, ime)
            })
            addView(kaj)
        }
        fun cas(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
        fun osveziGlavo() {
            val st = OddaljenoPredvajanje.stanje
            val vrstice = mutableListOf<String>()
            if (st.kajIgra) {
                vrstice += listOf(st.naslov, st.izvajalec).filter { it.isNotBlank() }.joinToString(" – ")
                vrstice += if (st.trajanjeMs > 0) cas(OddaljenoPredvajanje.polozajMs()) + " / " + cas(st.trajanjeMs) else cas(OddaljenoPredvajanje.polozajMs())
                if (st.zvocnik.isNotBlank()) vrstice += a.getString(R.string.zvocnik_na, st.zvocnik)
            } else vrstice += a.getString(R.string.naprava_caka, ime)
            if (st.glasnost >= 0) vrstice += a.getString(R.string.naprava_glasnost, st.glasnost)
            kaj.text = vrstice.joinToString("\n")
        }
        osveziGlavo()
        // Pregled C6: »Premor« ali »Predvajaj« po stanju cilja, ±10 s samo pri posnetku s trajanjem (ne v zivo).
        fun dejanja(): List<Pair<Int, String>> {
            val st = OddaljenoPredvajanje.stanje
            val d = mutableListOf(
                0 to a.getString(if (st.igra) R.string.zg_premor else R.string.zg_predvajaj),
                1 to a.getString(R.string.zg_prejsnja), 2 to a.getString(R.string.zg_naslednja))
            if (OddaljenoPravila.previjanjeNaCilju(st)) { d += 3 to a.getString(R.string.naprava_nazaj10); d += 4 to a.getString(R.string.naprava_naprej10) }
            d += 5 to a.getString(R.string.zvocnik_glasneje)
            d += 6 to a.getString(R.string.zvocnik_tisje)
            d += 7 to a.getString(R.string.naprava_vrsta)
            d += 8 to a.getString(R.string.naprava_prekini)
            return d
        }
        var prikazana = dejanja()
        val seznam = android.widget.ArrayAdapter(g.context, android.R.layout.select_dialog_item, prikazana.map { it.second }.toMutableList())
        fun osveziDejanja() {
            val nova = dejanja()
            if (nova == prikazana) return
            prikazana = nova
            seznam.setNotifyOnChange(false)
            seznam.clear(); seznam.addAll(nova.map { it.second })
            seznam.notifyDataSetChanged()
        }
        val okno = pokazi(a, g.setCustomTitle(glava).setAdapter(seznam, null).setNegativeButton(a.getString(R.string.os_preklici), null))
        val glavna = android.os.Handler(android.os.Looper.getMainLooper())
        val sledi = object : Runnable {
            override fun run() {
                if (!okno.isShowing || a.isFinishing || a.isDestroyed) return
                // Seja koncana (sporocilo, ce jo je koncal cilj, pokaze OddaljenoPredvajanje - pregled C7).
                if (OddaljenoPredvajanje.cilj?.id != n.id) { okno.dismiss(); spremenjeno(); return }
                osveziGlavo()
                osveziDejanja()
                glavna.postDelayed(this, 400L)
            }
        }
        // Pregled C3: vsak vidno(true) ima natanko en vidno(false) - ob zaprtju okna, ko zaslon odide v ozadje (Domov) ali
        // se unici; okno se tedaj zapre (sicer bi stevec vidnih obvisel in stanje cilja bi se v ozadju osvezevalo vsako sekundo).
        var vidnoJavljeno = true
        OddaljenoPredvajanje.vidno(true)
        lateinit var zivljenje: android.app.Application.ActivityLifecycleCallbacks
        fun skrito() {
            if (!vidnoJavljeno) return
            vidnoJavljeno = false
            glavna.removeCallbacks(sledi)
            try { a.application.unregisterActivityLifecycleCallbacks(zivljenje) } catch (_: Throwable) { }
            OddaljenoPredvajanje.vidno(false)
        }
        zivljenje = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStopped(x: Activity) { if (x === a) { skrito(); try { okno.dismiss() } catch (_: Throwable) { } } }
            override fun onActivityDestroyed(x: Activity) { if (x === a) skrito() }
            override fun onActivityCreated(x: Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(x: Activity) {}
            override fun onActivityResumed(x: Activity) {}
            override fun onActivityPaused(x: Activity) {}
            override fun onActivitySaveInstanceState(x: Activity, b: android.os.Bundle) {}
        }
        a.application.registerActivityLifecycleCallbacks(zivljenje)
        okno.setOnDismissListener { skrito() }
        glavna.postDelayed(sledi, 400L)
        okno.listView?.setOnItemClickListener { _, _, i, _ ->
            if (OddaljenoPredvajanje.cilj?.id != n.id) { okno.dismiss(); spremenjeno(); return@setOnItemClickListener }
            val korak = OddaljenoPravila.KORAK_GLASNOSTI
            when (prikazana.getOrNull(i)?.first ?: return@setOnItemClickListener) {
                0 -> OddaljenoPredvajanje.preklopi()
                1 -> OddaljenoPredvajanje.prejsnja()
                2 -> OddaljenoPredvajanje.naslednja()
                3 -> OddaljenoPredvajanje.skoci((OddaljenoPredvajanje.polozajMs() - 10_000L).coerceAtLeast(0L))
                4 -> OddaljenoPredvajanje.skoci(OddaljenoPredvajanje.polozajMs() + 10_000L)
                5 -> OddaljenoPredvajanje.glasnostZa(korak)
                6 -> OddaljenoPredvajanje.glasnostZa(-korak)
                7 -> { okno.dismiss(); vrstaNaprave(a, spremenjeno); return@setOnItemClickListener }
                else -> { OddaljenoPredvajanje.koncaj(); okno.dismiss(); spremenjeno(); return@setOnItemClickListener }
            }
            osveziGlavo()
            osveziDejanja()
        }
    }

    /** Vrsta na drugi napravi (okno okoli trenutne skladbe); dotik skladbe jo predvaja tam. */
    private fun vrstaNaprave(a: Activity, spremenjeno: () -> Unit) {
        val st = OddaljenoPredvajanje.stanje
        if (st.vrsta.isEmpty()) { upravljajNapravo(a, spremenjeno); return }
        val imena = st.vrsta.mapIndexed { i, naslov ->
            (if (st.odVrste + i == st.indeks) "▶ " else "") + naslov.ifBlank { "—" }
        }.toTypedArray()
        pokazi(a, gradnik(a).setTitle(R.string.naprava_vrsta).setItems(imena) { _, i ->
            OddaljenoPredvajanje.izVrste(st.odVrste + i)
            upravljajNapravo(a, spremenjeno)
        }.setNegativeButton(a.getString(R.string.os_preklici)) { _, _ -> upravljajNapravo(a, spremenjeno) })
    }
}
