package si.safeer.tv.os

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import si.safeer.tv.R

/**
 * Varnostna vrata zaganjalnika (lastnik, 25. 10. 2026): z enim dotikom nazaj na tovarniski zaganjalnik in z enim nazaj
 * na Safeer, vedno dosegljivo - tudi ce se vmesnik Safeer ne narise:
 *  - Nastavitve › Domaci zaslon in dolg pritisk (desni klik) na prazno namizje;
 *  - bliznjica na ikoni Safeer (dolg pritisk na ikono v katerem koli zaganjalniku) - odpre [VarnostnaVrataActivity],
 *    ki nicesar ne risa;
 *  - ploscica v hitrih nastavitvah ([VarnostnaVrataPloscica]) - vrstica stanja je sistemska in dela, tudi ko Safeer ne;
 *  - samodejno po prevec padcih ([Zaganjalnik.preveriObZagonu], [Zaganjalnik.namestiZascito]).
 */
object VarnostnaVrata {
    private const val TAG = "SafeerVarnostnaVrata"
    const val DEJANJE_VRNI = "si.safeer.tv.os.VRNI_ZAGANJALNIK"
    const val DEJANJE_SAFEER = "si.safeer.tv.os.SAFEER_ZAGANJALNIK"
    private const val KLJUC_PLOSCICA = "ploscica_ponujena"

    /** En dotik nazaj na tovarniski zaganjalnik (Safeer ni vec domaci zaslon, prejsnji zaganjalnik se takoj odpre). */
    fun vrni(c: Context) {
        val odprt = Zaganjalnik.vrniTovarniskega(c)
        Toast.makeText(c.applicationContext, if (odprt) R.string.zg_vrnjen else R.string.nz_vrata_vrnjeno, Toast.LENGTH_LONG).show()
    }

    /**
     * Ko uporabnik Safeer izbere za domaci zaslon, enkrat ponudimo ploscico hitrih nastavitev (Android 13+ sistemsko
     * vprasanje). Brez nje vrata se vedno so: Nastavitve, dolg pritisk na namizje, bliznjica ikone, sistemske nastavitve.
     */
    fun ponudiPloscico(a: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        val p = a.getSharedPreferences("safeer_varnostna_vrata", Context.MODE_PRIVATE)
        if (p.getBoolean(KLJUC_PLOSCICA, false)) return
        p.edit().putBoolean(KLJUC_PLOSCICA, true).apply()
        try {
            val vrstica = a.getSystemService(android.app.StatusBarManager::class.java) ?: return
            vrstica.requestAddTileService(ComponentName(a, VarnostnaVrataPloscica::class.java), a.getString(R.string.nz_ploscica),
                Icon.createWithResource(a, R.drawable.os_ikona_domov), a.mainExecutor) { izid -> Log.i(TAG, "Ploscica: $izid") }
        } catch (e: Throwable) { Log.w(TAG, "Ploscice ni bilo mogoce ponuditi: ${e.message}") }
    }
}

/**
 * Nevidna vrata (bliznjica ikone, ploscica): nicesar ne risa, zato dela tudi, ko se Safeer ne more narisati.
 * VRNI: Safeer ni vec domaci zaslon, odpre se prejsnji zaganjalnik. SAFEER: sistemsko okno vloge domacega zaslona.
 */
class VarnostnaVrataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == VarnostnaVrata.DEJANJE_SAFEER) {
            if (Zaganjalnik.jeIzbran(this)) { finish(); return }
            val okno = Zaganjalnik.zahtevajVlogo(this)
            // Android 9: ni okna vloge, odprle so se sistemske nastavitve (rezultata ni) - koncamo takoj.
            if (!okno || Build.VERSION.SDK_INT < 29) finish()
            return
        }
        VarnostnaVrata.vrni(this)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == Zaganjalnik.ZAHTEVA_VLOGE) {
            if (!Zaganjalnik.jeIzbran(this)) Zaganjalnik.opusti(this) else VarnostnaVrata.ponudiPloscico(this)
        }
        finish()
    }
}

/** Ploscica hitrih nastavitev »Safeer domaci zaslon«: vklopljena = Safeer je domaci zaslon; dotik preklopi. */
class VarnostnaVrataPloscica : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val t = qsTile ?: return
        val domaci = Zaganjalnik.jeIzbran(this)
        t.state = if (domaci) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = getString(if (domaci) R.string.os_zaganjalnik_izbran else R.string.nz_ploscica_tovarniski)
        t.updateTile()
    }

    // Android 13 in starejsi imajo samo razlicico z Intent (PendingIntent je od API 34, uporabimo jo tam).
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val dejanje = if (Zaganjalnik.jeIzbran(this)) VarnostnaVrata.DEJANJE_VRNI else VarnostnaVrata.DEJANJE_SAFEER
        val namera = Intent(this, VarnostnaVrataActivity::class.java).setAction(dejanje).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val zazeni = {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(PendingIntent.getActivity(this, 7412, namera, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                } else @Suppress("DEPRECATION") startActivityAndCollapse(namera)
            } catch (e: Throwable) { Log.w("SafeerVarnostnaVrata", "Ploscica: ${e.message}") }
        }
        // Grok 8, Z4: na zaklenjeni napravi sele po odklepu - izbire domacega zaslona ne spremeni nekdo brez kode.
        if (isLocked) unlockAndRun { zazeni() } else zazeni()
    }
}
