package si.safeer.tv.link

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Surface

/**
 * Lezece / pokoncno med gledanjem z racunalnika (Povezani zasloni, gumb »Ležeče«). Aplikacija sme spremeniti obracanje
 * zaslona le z dovoljenjem »Spreminjanje sistemskih nastavitev« (uporabnik ga da enkrat). Prejsnji polozaj si
 * zapomnimo in ga vrnemo, ko se deljenje konca. Lastnik, 11. 10. 2026: najbolj udobno upravljanje telefona z racunalnika.
 */
object Obracanje {
    private var prejSamodejno: Int? = null
    private var prejPolozaj: Int? = null

    /** Vrne (uspelo, sporocilo, koda). */
    fun obrni(c: Context, lezece: Boolean): Triple<Boolean, String, String> {
        val sl = try { c.resources.configuration.locales[0].language == "sl" } catch (_: Throwable) { false }
        if (!Settings.System.canWrite(c)) {
            try {
                c.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) { }
            return Triple(false, if (sl) "Na napravi enkrat dovoli »Spreminjanje sistemskih nastavitev« za Safeer, nato znova pritisni Ležeče."
                else "On the device, allow “Modify system settings” for Safeer once, then press Landscape again.", "potrebno_dovoljenje_obracanje")
        }
        val r = c.contentResolver
        return try {
            if (prejSamodejno == null) {
                prejSamodejno = Settings.System.getInt(r, Settings.System.ACCELEROMETER_ROTATION, 1)
                prejPolozaj = Settings.System.getInt(r, Settings.System.USER_ROTATION, Surface.ROTATION_0)
            }
            Settings.System.putInt(r, Settings.System.ACCELEROMETER_ROTATION, 0)
            Settings.System.putInt(r, Settings.System.USER_ROTATION, if (lezece) Surface.ROTATION_90 else Surface.ROTATION_0)
            Triple(true, if (lezece) "lezece" else "pokoncno", "")
        } catch (e: Throwable) {
            Triple(false, e.message ?: "napaka", "napaka")
        }
    }

    /** Konec deljenja: polozaj, kot je bil pred prvim »Ležeče«. */
    fun povrni(c: Context) {
        val samodejno = prejSamodejno ?: return
        try {
            if (Settings.System.canWrite(c)) {
                Settings.System.putInt(c.contentResolver, Settings.System.USER_ROTATION, prejPolozaj ?: Surface.ROTATION_0)
                Settings.System.putInt(c.contentResolver, Settings.System.ACCELEROMETER_ROTATION, samodejno)
            }
        } catch (_: Throwable) { }
        prejSamodejno = null
        prejPolozaj = null
    }
}
