package si.safeer.tv.os

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Namera za splet: ce je Safeer Browser namescen kot svoja aplikacija, odpremo njega (en pogon, ena
 * zascita, en seznam zavihkov); sicer vgrajenega, ki je v Safeer OS za ta primer.
 */
object Brskalnik {

    fun namera(c: Context): Intent {
        val paket = Sosed.brskalnik(c)
        val namera = if (paket != null)
            Intent().setComponent(ComponentName(paket, "si.safeer.tv.MainActivity"))
                // Brskalnik naj ve, od kod je prisel: ob izhodu se vrne v Safeer OS, ne na Android.
                .putExtra("iz_safeer_os", c.packageName)
        else Intent(c, si.safeer.tv.MainActivity::class.java)
        return namera.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Spletna aplikacija v svojem oknu brskalnika (kot s kartice na domacem zaslonu). */
    fun spletnaAplikacija(c: Context, url: String, ime: String): Intent =
        namera(c).putExtra("spletna_aplikacija", url).putExtra("aplikacija_ime", ime)

    /** Uporabnikov vir glasbe ali videa: kot spletna aplikacija, zvok pa ob tipki Domov igra naprej. */
    fun medijskaStran(c: Context, url: String, ime: String): Intent =
        spletnaAplikacija(c, url, ime).putExtra("zvok_v_ozadju", true)
}
