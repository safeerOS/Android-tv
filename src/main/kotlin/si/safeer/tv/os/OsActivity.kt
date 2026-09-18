package si.safeer.tv.os

import android.app.Activity
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Skupna osnova zaslonov Safeer OS: poskrbi, da igralni plosek dela povsod enako (glej
 * [Kontroler]). Zaslon, ki hoce kaksen gumb zase, prepise [plosekDejanje] in vrne true.
 */
open class OsActivity : Activity() {

    private val palica by lazy { Kontroler.Palica(this) }

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

    override fun onPause() {
        palica.ustavi()
        super.onPause()
    }
}
