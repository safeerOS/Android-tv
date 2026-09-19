package si.safeer.tv.os

import android.app.Activity
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Skupna osnova zaslonov Safeer OS: poskrbi, da igralni plosek dela povsod enako (glej
 * [Kontroler]). Zaslon, ki hoce kaksen gumb zase, prepise [plosekDejanje] in vrne true.
 */
open class OsActivity : Activity() {

    /** Vsi zasloni Safeer OS govorijo jezik, ki ga je uporabnik izbral
     *  (enako kot brskalnik); pri "samodejno" ostane jezik televizorja. */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(si.safeer.tv.JezikVmesnika.vKontekstu(newBase))
    }

    private val palica by lazy { Kontroler.Palica(this) }

    /** Po setContentView: vsebina se odmakne od sistemskih vrstic (Android 15+, glej [Robovi]). */
    override fun onContentChanged() {
        super.onContentChanged()
        Robovi.uporabi(this)
    }

    /** Zaslon lahko gumb prevzame; privzeto ga prepusti skupnemu prevodu. */
    open fun plosekDejanje(koda: Int): Boolean = false

    /** Zaslon lahko ob prvi uporabi ploscka osvezi svojo pomoc; privzeto ni kaj osvezevati. */
    open fun plosekZaznan() {}

    override fun dispatchKeyEvent(dogodek: KeyEvent): Boolean {
        if (Kontroler.jeDokazPloska(dogodek) &&
            Kontroler.zabelezi(this, dogodek.device)) plosekZaznan()
        if (Kontroler.tipka(this, dogodek) { koda -> plosekDejanje(koda) }) return true
        return super.dispatchKeyEvent(dogodek)
    }

    override fun onGenericMotionEvent(dogodek: MotionEvent): Boolean {
        if (Kontroler.jeOdklon(dogodek) &&
            Kontroler.zabelezi(this, dogodek.device)) plosekZaznan()
        if (palica.dogodek(dogodek)) return true
        return super.onGenericMotionEvent(dogodek)
    }

    // ------------------------------------------------------------------ koda za novo napravo

    /** Nova naprava se povezuje: kodo pokazemo cez karkoli je na zaslonu (glej KodaNaZaslonu). */
    private val koda by lazy { KodaNaZaslonu(this) }

    override fun onResume() {
        super.onResume()
        koda.zacni()
    }

    override fun onPause() {
        koda.ustavi()
        palica.ustavi()
        super.onPause()
    }
}
