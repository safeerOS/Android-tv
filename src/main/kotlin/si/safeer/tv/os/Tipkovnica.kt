package si.safeer.tv.os

import android.view.inputmethod.InputMethodManager

object Tipkovnica {
    /**
     * Zastavica za showSoftInput: SHOW_IMPLICIT je opusceno sele v Androidu 17 (API 37). Na Androidu 9-16 (tudi
     * televizorji, ki jih Google ne posodablja vec) dolocuje, da uporabnik tipkovnico lahko zapre - zato jo
     * obdrzimo na vseh razlicicah, dokler zamenjava ni preizkusena na napravi z Androidom 17.
     */
    @Suppress("DEPRECATION")
    const val NAMIG = InputMethodManager.SHOW_IMPLICIT
}
