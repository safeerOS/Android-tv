package si.safeer.tv.os

import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import si.safeer.tv.R

/**
 * "Dodaj na seznam predvajanja": skladbo dodas na izbran seznam ali na novega (ime vpises). Isto okno iz
 * knjiznice (dolg pritisk na skladbo) in z zaslona predvajanja - svoj seznam nastaja med poslusanjem.
 */
object SeznamOkno {
    fun dodaj(a: Activity, sk: Jamendo.Skladba, poDodajanju: () -> Unit = {}) {
        val seznami = MedijskiViri.seznami(a)
        fun shrani(ime: String) {
            val je = MedijskiViri.dodajNaSeznam(a, ime, sk)
            Toast.makeText(a, a.getString(if (je) R.string.os_seznam_dodano else R.string.os_seznam_ze, ime), Toast.LENGTH_SHORT).show()
            if (je) poDodajanju()
        }
        fun nov() {
            val polje = EditText(a).apply { hint = a.getString(R.string.os_seznam_ime); setSingleLine() }
            val rob = (20 * a.resources.displayMetrics.density).toInt()
            AlertDialog.Builder(a).setTitle(R.string.os_seznam_nov)
                .setView(FrameLayout(a).apply { setPadding(rob, 0, rob, 0); addView(polje) })
                .setPositiveButton(android.R.string.ok) { _, _ -> polje.text.toString().trim().take(60).takeIf { it.isNotBlank() }?.let { shrani(it) } }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        if (seznami.isEmpty()) { nov(); return }
        AlertDialog.Builder(a).setTitle(R.string.os_seznam_dodaj)
            .setItems((seznami.map { it.ime } + a.getString(R.string.os_seznam_nov)).toTypedArray()) { _, k ->
                seznami.getOrNull(k)?.let { shrani(it.ime) } ?: nov()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    /** Ali skladbo lahko damo na seznam predvajanja (tudi video s spletnega vira). */
    fun mozno(sk: Jamendo.Skladba) = MedijskiViri.shranljiva(sk) || SpletniVir.jeEnota(sk)
}
