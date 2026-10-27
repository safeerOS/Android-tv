package si.safeer.tv.os

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import si.safeer.tv.R
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kraj za vreme na domacem zaslonu Pokrajina (naloga 42 A). Lastnik 27. 10. 2026: izbira je v Nastavitvah novega
 * zaganjalnika, ne s klikom na uro (ura je samo prikaz). Prazen vnos vreme izklopi. Vreme pride z Open-Meteo samo za ta kraj.
 */
object VremeKraj {
    const val KLJUC_IME = "vreme_kraj_ime"
    const val KLJUC_SIRINA = "vreme_kraj_sirina"
    const val KLJUC_DOLZINA = "vreme_kraj_dolzina"
    const val KLJUC_CAS = "vreme_cas"
    private const val TAG = "SafeerVremeKraj"

    private fun shramba(c: Context) = c.getSharedPreferences("safeer_os", Context.MODE_PRIVATE)

    /** Ime izbranega kraja ali null (vreme izklopljeno). */
    fun ime(c: Context): String? = shramba(c).getString(KLJUC_IME, null)?.takeIf { it.isNotBlank() }

    /** Okno za vnos kraja; [potem] po shranitvi ali izklopu (na glavni niti). */
    fun izberi(a: Activity, potem: () -> Unit) {
        val polje = EditText(a).apply {
            setSingleLine(); setText(ime(a).orEmpty()); hint = a.getString(R.string.pk_vreme_kraj_namig)
        }
        val okno = android.app.AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.pk_vreme_kraj)
            .setMessage(R.string.pk_vreme_kraj_opis)
            .setView(polje)
            .setPositiveButton(android.R.string.ok) { _, _ -> shrani(a, polje.text.toString(), potem) }
            .setNegativeButton(a.getString(R.string.os_preklici), null)
            .create()
        Kontroler.pokazi(okno)
        okno.show()
    }

    private fun shrani(a: Activity, vnos: String, potem: () -> Unit) {
        val jezik = a.resources.configuration.locales[0]?.language ?: "en"
        val naslov = VremePravila.naslovIskanja(vnos, jezik)
        if (naslov == null) {
            shramba(a).edit().remove(KLJUC_IME).remove(KLJUC_SIRINA).remove(KLJUC_DOLZINA).remove(KLJUC_CAS).apply()
            potem(); return
        }
        val glavna = Handler(Looper.getMainLooper())
        Thread({
            val k = beri(naslov)?.let { VremePravila.razcleniKraj(it) }
            glavna.post {
                if (a.isFinishing || a.isDestroyed) return@post
                if (k == null) { Toast.makeText(a, R.string.pk_vreme_ni_najden, Toast.LENGTH_LONG).show(); return@post }
                // Zadnje vreme se izbrise: domaci zaslon ga ob prikazu prebere na novo za nov kraj.
                shramba(a).edit().putString(KLJUC_IME, k.ime).putString(KLJUC_SIRINA, k.sirina.toString())
                    .putString(KLJUC_DOLZINA, k.dolzina.toString()).remove(KLJUC_CAS).apply()
                potem()
            }
        }, "safeer-vreme-kraj").start()
    }

    /** GET z rokom, brez preusmeritev drugam, najvec 64 KiB; null ob napaki ali odgovoru, ki ni 200. Nikoli na glavni niti. */
    fun beri(naslov: String): String? = try {
        val p = URL(naslov).openConnection() as HttpURLConnection
        p.connectTimeout = 6_000; p.readTimeout = 6_000
        p.instanceFollowRedirects = false
        p.setRequestProperty("User-Agent", "Safeer OS (https://safeer.si)")
        p.setRequestProperty("Accept", "application/json")
        try {
            if (p.responseCode != 200) null
            else p.inputStream.use { vhod ->
                val bajti = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                var n = vhod.read(buf)
                while (n >= 0 && bajti.size() <= 64 * 1024) {
                    bajti.write(buf, 0, n)
                    n = vhod.read(buf)
                }
                if (bajti.size() > 64 * 1024) null else bajti.toString("UTF-8")
            }
        } finally { p.disconnect() }
    } catch (e: Throwable) { Log.i(TAG, "Vreme: ${e.javaClass.simpleName}"); null }
}
