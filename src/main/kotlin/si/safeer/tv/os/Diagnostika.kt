package si.safeer.tv.os

import android.content.Context

/**
 * Branje lastnosti diagnostike ([DiagnostikaPravila]) z `getprop`. Klice se na glavni niti ob izbiri toka (izberiTok -
 * vsi klici prek glavna.post). Pregled Grok 32, G4/G6: proces se zazene samo, ce je na napravi vklopljen razvijalski adb
 * (prej ob vsaki izbiri toka pri vseh uporabnikih), z rokom [ROK_MS] in z destroy().
 */
object Diagnostika {
    /** getprop odgovori v nekaj ms; dlje ne cakamo (glavna nit). */
    private const val ROK_MS = 500L

    fun vklopljena(c: Context, lastnost: String): Boolean = try {
        DiagnostikaPravila.vklopljena(lastnost, System.currentTimeMillis() / 1000, { adbVklopljen(c) }, ::getprop)
    } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false } catch (_: Throwable) { false }

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
