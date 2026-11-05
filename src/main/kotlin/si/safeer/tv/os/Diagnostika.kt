package si.safeer.tv.os

import android.content.Context

/**
 * Branje lastnosti diagnostike ([DiagnostikaPravila]) z `getprop`. Klice se na glavni niti ob izbiri toka (izberiTok -
 * vsi klici prek glavna.post). Pregled Grok 32, G4/G6: proces se zazene samo, ce je na napravi vklopljen razvijalski adb
 * (prej ob vsaki izbiri toka pri vseh uporabnikih), z rokom [ROK_MS] in z destroy().
 * Pregled 5. 11. 2026: getprop tece v ozadju, glavna nit dobi zadnjo prebrano vrednost (najvec [PREDPOMNILNIK_MS] staro);
 * prva izbira toka po vklopu lastnosti jo zato morda se ne vidi, naslednja (v nekaj ms) jo.
 */
object Diagnostika {
    /** getprop odgovori v nekaj ms; dlje ne cakamo. */
    private const val ROK_MS = 500L
    private const val PREDPOMNILNIK_MS = 60_000L
    private val predpomnilnik = NastavitvePravila.PredpomnjenaLastnost(PREDPOMNILNIK_MS)

    fun vklopljena(c: Context, lastnost: String): Boolean = try {
        DiagnostikaPravila.vklopljena(lastnost, System.currentTimeMillis() / 1000, { adbVklopljen(c) }, ::izPredpomnilnika)
    } catch (_: Throwable) { false }

    /** Zadnja prebrana vrednost; ce je starejsa od roka, novo branje v ozadju (ena nit na lastnost). */
    private fun izPredpomnilnika(lastnost: String): String? {
        val (vrednost, beri) = predpomnilnik.preberi(lastnost, android.os.SystemClock.elapsedRealtime())
        if (beri) try {
            Thread({
                val nova = try { getprop(lastnost) } catch (_: Throwable) { null }
                predpomnilnik.shrani(lastnost, nova, android.os.SystemClock.elapsedRealtime())
            }, "safeer-diagnostika").apply { isDaemon = true }.start()
        } catch (_: Throwable) { predpomnilnik.shrani(lastnost, vrednost, android.os.SystemClock.elapsedRealtime()) }
        return vrednost
    }

    /**
     * Razvijalski adb: razhroscevanje USB (Settings.Global.ADB_ENABLED == 1) ali brezzicno razhroscevanje (adb_wifi_enabled,
     * Android 11+; ce ga sistem ne da prebrati, ne velja). Brez tega lastnosti `debug.*` ne more nastaviti nihce razen sistema.
     */
    private fun adbVklopljen(c: Context): Boolean {
        val cr = c.applicationContext.contentResolver
        if (android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.ADB_ENABLED, 0) == 1) return true
        return try { android.provider.Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1 } catch (_: Exception) { false }
    }

    /** getprop brez lupine (tabela argumentov), vhod zaprt; po koncu ali roku se proces konca (destroy). null = ni odgovora. */
    private fun getprop(lastnost: String): String? {
        val p = ProcessBuilder("getprop", lastnost).redirectErrorStream(true).start()
        return try {
            p.outputStream.close()
            if (!p.waitFor(ROK_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) null
            else p.inputStream.bufferedReader().use { it.readLine() }
        } finally { p.destroy() }
    }

    /** Z3 v zivo: torrent odprtega filma na tej napravi po samodejni poti ([DiagnostikaPravila.P2P_SAMO]). */
    fun samoP2p(c: Context): Boolean = vklopljena(c, DiagnostikaPravila.P2P_SAMO)
}
