package si.safeer.tv.os

import android.app.Activity
import android.app.ActivityOptions
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

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
        if (zablodelaPotrditev(dogodek)) return true
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
        viden = SystemClock.uptimeMillis()
        pritisnjene.clear()
        koda.zacni()
    }

    // ------------------------------------------------------------------ zablodel OK

    /** Kdaj je zaslon postal viden (onResume). */
    private var viden = 0L
    /** Potrditvene tipke, katerih pritisk je videl prav ta zaslon. */
    private val pritisnjene = HashSet<Int>()

    /**
     * OK, ki ga zaslon ni videl sam, ne sme nicesar izbrati.
     *
     * 21. 9. 2026: »Vse aplikacije« na domacem zaslonu je odprl seznam, ta pa v trenutku brskalnik
     * (prvo ploscico) - drugi OK (odboj tipke ali ponoven pritisk, ker se zaslon ni odzval takoj)
     * je padel ze na nov zaslon. Zato pritisk v prvih [ZASCITA_MS] po odprtju ne steje, spust
     * brez pritiska na tem zaslonu pa tudi ne (pritisk je bil na prejsnjem zaslonu).
     */
    private fun zablodelaPotrditev(d: KeyEvent): Boolean {
        val k = d.keyCode
        if (k != KeyEvent.KEYCODE_DPAD_CENTER && k != KeyEvent.KEYCODE_ENTER &&
            k != KeyEvent.KEYCODE_NUMPAD_ENTER && k != KeyEvent.KEYCODE_BUTTON_A) return false
        return when (d.action) {
            KeyEvent.ACTION_DOWN -> if (d.repeatCount == 0) {
                if (SystemClock.uptimeMillis() - viden < ZASCITA_MS) true else { pritisnjene.add(k); false }
            } else k !in pritisnjene
            KeyEvent.ACTION_UP -> !pritisnjene.remove(k)
            else -> false
        }
    }

    /**
     * Aplikacija naj zraste iz ploscice, ki jo je odprla - kot pri zaganjalniku televizorja -
     * namesto trdega reza na njen prazen zacetni zaslon, dokler se ne nalozi.
     */
    fun izPloscice(izvor: View?): Bundle? = try {
        if (izvor == null || izvor.width <= 0 || izvor.height <= 0) null
        else ActivityOptions.makeScaleUpAnimation(izvor, 0, 0, izvor.width, izvor.height).toBundle()
    } catch (_: Throwable) { null }

    companion object {
        /** Po odprtju zaslona OK toliko casa ne steje (odboj tipke, dvojni pritisk). */
        const val ZASCITA_MS = 500L
    }

    override fun onPause() {
        koda.ustavi()
        palica.ustavi()
        super.onPause()
    }
}
