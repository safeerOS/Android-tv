package si.safeer.tv.os

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import si.safeer.tv.R

/**
 * Namizni nacin Androida - aplikacije v oknih (lastnik, 25. 10. 2026): ali ga naprava zmore, ali je vklopljen in kako ga
 * uporabnik vklopi sam. Safeer razvijalskih nastavitev NIKOLI ne spreminja: stanje samo prebere, za vklop odpre
 * sistemsko stran Moznosti za razvijalce.
 *
 * Kdaj se aplikacija z namizja odpre v oknu: samo z javnim [ActivityOptions.setLaunchBounds], brez skritih sistemskih
 * klicev. Izmerjeno 25. 10. 2026 na testni tablici (Samsung, Android 16, okna vgrajena): Moje datoteke so se odprle v
 * oknu (mode=freeform, meje 744 x 1382 px = 62 % x 72 %). Skriti setLaunchWindowingMode je za aplikacije zaprt
 * (NoSuchMethodException) in ni potreben. Kjer naprava oken ne zmore, Android meje prezre - cel zaslon kot doslej.
 */
object NamizniNacin {
    private const val TAG = "SafeerNamizniNacin"
    private const val PREFS = "safeer_os"
    private const val KLJUC_OKNA = "namizje_okna"

    data class Stanje(
        /** Sistem podpira okna (sistemska funkcija freeform_window_management - npr. Samsung tablice). */
        val funkcija: Boolean,
        /** Razvijalske moznosti: okna proste oblike so vklopljena (velja po ponovnem zagonu). */
        val oknaVklopljena: Boolean,
        /** Razvijalske moznosti: namizni nacin na zunanjih zaslonih. */
        val zunanjiNamizni: Boolean,
        /** Moznosti za razvijalce so vklopljene. */
        val razvijalske: Boolean,
    ) {
        val okna: Boolean get() = funkcija || oknaVklopljena
    }

    /** Branje sistemske nastavitve; ce je Android aplikacijam ne pokaze, je to »ne vemo« = false (nikoli napaka). */
    private fun globalna(c: Context, kljuc: String): Boolean = try {
        Settings.Global.getInt(c.contentResolver, kljuc, 0) == 1
    } catch (_: Throwable) { false }

    fun stanje(c: Context) = Stanje(
        funkcija = try { c.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) } catch (_: Throwable) { false },
        oknaVklopljena = globalna(c, "enable_freeform_support"),
        zunanjiNamizni = globalna(c, "force_desktop_mode_on_external_displays"),
        razvijalske = globalna(c, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
    )

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Uporabnik zeli aplikacije v oknih (privzeto da, kjer jih naprava zmore). */
    fun oknaIzbrana(c: Context): Boolean = prefs(c).getBoolean(KLJUC_OKNA, true)

    private var zaporedje = 0

    /**
     * Moznosti zagona aplikacije z namizja: na napravi z okni (in ce jih uporabnik ni izklopil) okno, kaskadno kot na
     * racunalniku ([ZaganjalnikPravila.oknoAplikacije]); sicer [osnova] (animacija iz ploscice).
     */
    fun moznosti(a: Activity, osnova: Bundle?): Bundle? {
        if (!oknaIzbrana(a) || !stanje(a).okna) return osnova
        return try {
            val meje = if (android.os.Build.VERSION.SDK_INT >= 30) a.windowManager.currentWindowMetrics.bounds
                else Rect(0, 0, a.resources.displayMetrics.widthPixels, a.resources.displayMetrics.heightPixels)
            val r = ZaganjalnikPravila.oknoAplikacije(meje.width(), meje.height(), zaporedje++)
            ActivityOptions.makeBasic().setLaunchBounds(Rect(meje.left + r[0], meje.top + r[1], meje.left + r[2], meje.top + r[3])).toBundle()
        } catch (e: Throwable) {
            Log.w(TAG, "Okna ni bilo mogoce pripraviti: ${e.message}"); osnova
        }
    }

    /** Kratko stanje za vrstico v Nastavitvah. */
    fun kratko(c: Context): String {
        val s = stanje(c)
        return c.getString(when {
            !s.okna -> R.string.nz_okna_ni
            oknaIzbrana(c) -> R.string.nz_okna_vklopljena
            else -> R.string.nz_okna_izklopljena
        })
    }

    /**
     * Pomocnik »Namizni nacin«: kaj naprava zmore, stikalo za okna in - ce oken ni - natancen opis, kako jih uporabnik
     * vklopi sam, z gumbom, ki odpre Moznosti za razvijalce. Imena moznosti se med proizvajalci razlikujejo in jih na
     * vseh napravah nismo preverili, zato jih opisemo splosno (lastnik: brez nepreverjenih trditev).
     */
    fun pokazi(a: Activity, posodobi: () -> Unit) {
        val s = stanje(a)
        val besedilo = StringBuilder()
        besedilo.append(a.getString(if (s.okna) R.string.nz_okna_da else R.string.nz_okna_ne))
        besedilo.append("\n").append(a.getString(if (s.zunanjiNamizni) R.string.nz_zunanji_da else R.string.nz_zunanji_ne))
        if (!s.okna || !s.zunanjiNamizni) {
            besedilo.append("\n\n").append(a.getString(R.string.nz_vodic))
            // Imena na cistem Androidu (slovenska preverjena na Androidu 14, testni telefon 25. 10. 2026); drugje prazno.
            a.getString(R.string.nz_vodic_aosp).takeIf { it.isNotBlank() }?.let { besedilo.append("\n").append(it) }
            if (!s.razvijalske) besedilo.append("\n\n").append(a.getString(R.string.nz_vodic_razvijalske))
        }
        val g = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.nz_nacin)
            .setMessage(besedilo.toString())
            .setNegativeButton(a.getString(R.string.os_preklici), null)
        if (s.okna) {
            val zdaj = oknaIzbrana(a)
            g.setPositiveButton(a.getString(if (zdaj) R.string.nz_okna_izklopi else R.string.nz_okna_vklopi)) { _, _ ->
                prefs(a).edit().putBoolean(KLJUC_OKNA, !zdaj).apply()
                posodobi()
            }
        }
        if (!s.okna || !s.zunanjiNamizni) g.setNeutralButton(a.getString(R.string.nz_odpri_razvijalske)) { _, _ -> odpriRazvijalske(a) }
        Kontroler.pokazi(g.show())
    }

    /** Moznosti za razvijalce; ce jih se ni (niso vklopljene), stran O napravi, kjer se vklopijo. */
    fun odpriRazvijalske(a: Activity) {
        for (dejanje in listOf(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_DEVICE_INFO_SETTINGS, Settings.ACTION_SETTINGS)) {
            try { a.startActivity(Intent(dejanje)); return } catch (_: Throwable) { }
        }
        Toast.makeText(a, R.string.nz_ni_sistemske, Toast.LENGTH_LONG).show()
    }
}
