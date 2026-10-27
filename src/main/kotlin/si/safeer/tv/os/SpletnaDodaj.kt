package si.safeer.tv.os

import android.app.Activity
import android.widget.LinearLayout
import android.widget.Toast
import si.safeer.tv.R

/**
 * Dodajanje spletne aplikacije na domaci zaslon - skupno staremu domacemu zaslonu (DomovActivity) in novemu zaganjalniku
 * (Pokrajina, »+ Dodaj«). Dodajanje brez tipkanja na daljincu: ponudimo strani, ki jih ima uporabnik ze na domaci strani
 * brskalnika (»Moje strani«); ime in ikono nato prinese manifest spletne aplikacije.
 */
object SpletnaDodaj {
    /** Izbira strani; [poDodaji] po uspesno dodani aplikaciji (osvezi vrstico). */
    fun izberi(a: Activity, poDodaji: () -> Unit) {
        val ploscice = try { si.safeer.tv.HomeTilesStore.load(a) } catch (_: Throwable) { mutableListOf() }
        val proste = ploscice.filterNot { SpletneAplikacije.jeDodana(a, it.url) }
        // Prva izbira je vedno vnos naslova: uporabnik lahko doda katerokoli stran, ne le tistih,
        // ki jih ima ze na domaci strani brskalnika.
        val imena = (listOf(a.getString(R.string.os_spletne_vnesi)) +
            proste.map { it.title.ifBlank { SpletneAplikacije.gostitelj(it.url) } }).toTypedArray()
        android.app.AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(a.getString(R.string.os_spletne_dodaj_naslov))
            .setItems(imena) { _, i ->
                if (i == 0) vnesiNaslov(a, poDodaji)
                else proste.getOrNull(i - 1)?.let { dodaj(a, it.url, it.title, poDodaji) }
            }
            .setNegativeButton(a.getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    /**
     * Vnos naslova z daljincem. Tipkanje na televizorju ni prijetno, zato naslov dopolnimo sami
     * (brez "https://" gre tudi) in ga zavrnemo, ce ni videti kot spletni naslov.
     */
    private fun vnesiNaslov(a: Activity, poDodaji: () -> Unit) {
        val polje = android.widget.EditText(a).apply {
            hint = a.getString(R.string.os_spletne_vnesi_namig)
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(a.osBarva(R.color.os_besedilo))
            setHintTextColor(a.osBarva(R.color.os_umirjeno))
        }
        val okvir = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val r = (a.resources.displayMetrics.density * 24).toInt()
            setPadding(r, r / 2, r, 0)
            addView(polje)
        }
        android.app.AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(a.getString(R.string.os_spletne_vnesi))
            .setView(okvir)
            .setPositiveButton(a.getString(R.string.os_spletne_dodaj_gumb)) { _, _ ->
                val naslov = celoten(polje.text.toString())
                if (naslov == null) {
                    Toast.makeText(a, a.getString(R.string.os_spletne_neveljaven), Toast.LENGTH_LONG).show()
                } else if (SpletneAplikacije.jeDodana(a, naslov)) {
                    Toast.makeText(a, a.getString(R.string.os_spletne_ze_dodana), Toast.LENGTH_LONG).show()
                } else {
                    dodaj(a, naslov, SpletneAplikacije.gostitelj(naslov), poDodaji)
                }
            }
            .setNegativeButton(a.getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
        polje.requestFocus()
    }

    /** Naslov, kot ga je vnesel uporabnik, dopolnjen v celoten https naslov; null, ce to ni naslov. */
    fun celoten(vnos: String): String? {
        val t = vnos.trim().replace(" ", "")
        if (t.isEmpty()) return null
        val z = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        return try {
            val u = java.net.URL(z)
            if (u.host.contains(".") && !u.host.startsWith(".") && !u.host.endsWith(".")) z else null
        } catch (_: Throwable) { null }
    }

    private fun dodaj(a: Activity, url: String, ime: String, poDodaji: () -> Unit) {
        Toast.makeText(a, a.getString(R.string.os_spletne_dodajam), Toast.LENGTH_SHORT).show()
        SpletneAplikacije.dodaj(a, url, ime) {
            if (a.isFinishing) return@dodaj
            poDodaji()
        }
    }
}
