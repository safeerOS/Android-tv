package si.safeer.tv.os

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Naloga 135: vstop za samodejni preizkus po gradnji (tests/preizkus_po_gradnji.py). Izvozena je SAMO z dovoljenjem
 * `android.permission.DUMP`, ki ga ima le adb (shell) in sistem - druga aplikacija je ne more zagnati. Ne predvaja sama:
 * preveri vnos ([PreizkusPravila]) in ga preda Medijskemu centru, kjer gre po isti poti kot OK na kartici.
 */
class PreizkusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        predaj(intent)
    }

    /**
     * Drugi zagon v isti nalogi pride sem, ne v onCreate (SM-X210 24. 10. 2026: »intent has been delivered to currently
     * running top-most instance« - druga zahteva, epizoda, se prej ni izvedla). Zato tudi lastna prazna naloga v manifestu.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        predaj(intent)
    }

    private fun predaj(n: Intent) {
        val v = PreizkusPravila.vnos(n.getStringExtra(EXTRA_IMDB), n.getStringExtra(EXTRA_NASLOV))
        if (v != null) startActivity(Intent(this, GlasbaActivity::class.java)
            .putExtra(GlasbaActivity.PREIZKUS_IMDB, n.getStringExtra(EXTRA_IMDB))
            .putExtra(GlasbaActivity.PREIZKUS_NASLOV, v.naslov)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }

    companion object {
        const val EXTRA_IMDB = "si.safeer.preizkus.IMDB"
        const val EXTRA_NASLOV = "si.safeer.preizkus.NASLOV"
    }
}
