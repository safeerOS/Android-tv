package si.safeer.tv.os

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import si.safeer.tv.os.UsenetPonudnikPravila as P

/**
 * Naloga 42 G (27. 10. 2026): okno »Moji viri › Usenet ponudnik« v Medijskem centru (docs/usenet-ponudnik-okno.md).
 * Vsa besedila iz [P.besedilo] (sl, en, de, es, fr, it) v jeziku vmesnika. Geslo se nikoli ne pokaze (polje je ob
 * odprtju prazno), ne gre v dnevnik ali Toast. »Preveri« samo na klik, na delovni niti, z [UsenetNntp.prijava];
 * nic v ozadju. »Shrani« je edini, ki odklene zapah po zavrnjeni prijavi.
 */
object UsenetPonudnikOkno {
    private val glavna = Handler(Looper.getMainLooper())

    private fun jezik(a: Activity): String = a.resources.configuration.locales[0]?.language ?: "en"

    /** Vrstica v Mojih virih: ime in stanje, brez uporabnika in gesla. */
    fun vrstica(a: Activity): String {
        val j = jezik(a)
        val s = try { UsenetPonudnik.get(a).stanje() } catch (_: Throwable) { null }
        if (s == null || !s.vpisan) return P.besedilo("NASLOV", j) + " · " + P.besedilo("NI_VPISAN", j)
        val ime = s.ime?.takeIf { it.isNotBlank() } ?: s.gostitelj.orEmpty()
        return P.besedilo("NASLOV", j) + " · " + ime + " · " + P.besedilo(s.besedilo, j)
    }

    fun pokazi(a: Activity, potem: () -> Unit) {
        val j = jezik(a)
        val t = { k: String -> P.besedilo(k, j) }
        val shramba = UsenetPonudnik.get(a)
        val prej = shramba.preberi()
        val stanje = shramba.stanje()
        val rob = (20 * a.resources.displayMetrics.density).toInt()
        fun polje(oznaka: String, vrsta: Int, vrednost: String, namig: String = "") = EditText(a).apply {
            hint = if (namig.isNotEmpty()) "$oznaka ($namig)" else oznaka
            contentDescription = oznaka
            inputType = vrsta; isSingleLine = true
            setText(vrednost)
        }
        val ime = polje(t("POLJE_IME"), InputType.TYPE_CLASS_TEXT, prej?.ime.orEmpty())
        val streznik = polje(t("POLJE_STREZNIK"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, prej?.gostitelj.orEmpty(), "news.example")
        val vrata = polje(t("POLJE_VRATA"), InputType.TYPE_CLASS_NUMBER, (prej?.vrata ?: P.VRATA).toString())
        val tls = CheckBox(a).apply { text = t("POLJE_TLS"); isChecked = prej?.tls ?: true }
        val opozorilo = TextView(a).apply { text = t("OPOZORILO_BREZ_TLS"); visibility = if (tls.isChecked) View.GONE else View.VISIBLE }
        val potrdiBrezTls = CheckBox(a).apply {
            text = t("usenet ponudnik: brez TLS samo z nevarno_brez_tls=True")
            isChecked = prej?.nevarnoBrezTls ?: false
            visibility = opozorilo.visibility
        }
        tls.setOnCheckedChangeListener { _, da ->
            opozorilo.visibility = if (da) View.GONE else View.VISIBLE
            potrdiBrezTls.visibility = opozorilo.visibility
        }
        val uporabnik = polje(t("POLJE_UPORABNIK"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, prej?.uporabnik.orEmpty())
        // Geslo je vedno prazno ob odprtju: shranjeno se nikoli ne pokaze (upravitelj gesel ga sme izpolniti).
        val geslo = polje(t("POLJE_GESLO"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, "")
        val povezav = polje(t("POLJE_POVEZAV"), InputType.TYPE_CLASS_NUMBER, (prej?.najvecPovezav ?: P.PRIVZETO_POVEZAV).toString())
        val namig = TextView(a).apply { text = t("NAMIG_POVEZAV") }
        val izid = TextView(a).apply { text = if (stanje.vpisan) t(stanje.besedilo ?: P.NEPREVERJEN) else t("NI_VPISAN") }
        val vsebina = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(rob, rob / 2, rob, 0)
            for (v in listOf(izid, ime, streznik, vrata, tls, opozorilo, potrdiBrezTls, uporabnik, geslo, povezav, namig)) addView(v)
        }
        // Po zavrnitvi ali zaklepu: fokus na geslo - Preveri ne gre, dokler ga uporabnik ne vpise in shrani.
        if (stanje.stanje == P.NE_VELJA || stanje.stanje == P.ZAKLENJEN) geslo.post { geslo.requestFocus() }

        /** Ponudnik in geslo iz polj ali null (napaka se pokaze kot besedilo kljuca - nikoli z vnosom). */
        fun izPolj(): Pair<P.Ponudnik, P.Geslo>? = try {
            val p = P.Ponudnik.ustvari(streznik.text.toString().trim(), uporabnik.text.toString(),
                vrata.text.toString().toIntOrNull() ?: -1, tls.isChecked, povezav.text.toString().toIntOrNull() ?: -1,
                ime.text.toString().trim(), potrdiBrezTls.isChecked)
            p to P.Geslo.iz(geslo.text.toString())
        } catch (e: P.NeveljavenVnos) {
            izid.text = t(e.message ?: "NI_VPISAN"); null
        }

        val okno = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t("NASLOV"))
            .setView(ScrollView(a).apply { addView(vsebina) })
            .setPositiveButton(t("GUMB_SHRANI"), null)
            .setNeutralButton(t("GUMB_PREVERI"), null)
            .setNegativeButton(t("GUMB_POZABI"), null)
            .create()
        okno.setOnShowListener {
            val shrani = okno.getButton(AlertDialog.BUTTON_POSITIVE)
            val preveri = okno.getButton(AlertDialog.BUTTON_NEUTRAL)
            val pozabi = okno.getButton(AlertDialog.BUTTON_NEGATIVE)
            pozabi.isEnabled = stanje.vpisan
            preveri.isEnabled = stanje.vpisan && stanje.gesloShranjeno && stanje.stanje != P.NE_VELJA && stanje.stanje != P.ZAKLENJEN
            shrani.setOnClickListener {
                val (p, g) = izPolj() ?: return@setOnClickListener
                val trajno = shramba.shrani(p, g)
                geslo.setText("")
                izid.text = t(P.NEPREVERJEN)
                // Brez Keystore geslo velja samo do zaprtja aplikacije (UsenetPonudnik.shrani vrne false).
                if (!trajno) Toast.makeText(a, t("ni_gesla"), Toast.LENGTH_LONG).show()
                pozabi.isEnabled = true; preveri.isEnabled = true
                UskladitevKljucev.sprozi(a.applicationContext)
                potem()
            }
            preveri.setOnClickListener {
                val p = shramba.preberi() ?: return@setOnClickListener
                val (g, ovira) = shramba.zaBazen(p)
                if (g == null) { izid.text = t(ovira ?: P.NI_GESLA); return@setOnClickListener }
                preveri.isEnabled = false
                Thread({
                    val kljuc = try {
                        shramba.preveri(p, g) { pp, gg, _ -> UsenetLastni.prijava(a, pp, gg, shramba.meje) }
                    } catch (e: P.NapakaPonudnika) { e.razlog } catch (_: Throwable) { P.NI_ODGOVORA }
                    glavna.post {
                        izid.text = t(kljuc)
                        preveri.isEnabled = kljuc != P.NE_VELJA && kljuc != P.ZAKLENJEN
                        if (kljuc == P.NE_VELJA || kljuc == P.ZAKLENJEN) geslo.requestFocus()
                        potem()
                    }
                }, "safeer-usenet-ponudnik-preveri").start()
            }
            pozabi.setOnClickListener {
                val potrdi = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(t("GUMB_POZABI"))
                    .setPositiveButton(t("GUMB_POZABI")) { _, _ ->
                        UsenetLastni.ustavi(); shramba.pozabi()
                        UskladitevKljucev.sprozi(a.applicationContext)
                        okno.dismiss(); potem()
                    }
                    .setNegativeButton(android.R.string.cancel, null).create()
                Kontroler.pokazi(potrdi); potrdi.show()
            }
        }
        Kontroler.pokazi(okno)
        okno.show()
    }
}
