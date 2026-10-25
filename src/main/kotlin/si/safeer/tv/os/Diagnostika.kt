package si.safeer.tv.os

/** Branje lastnosti diagnostike ([DiagnostikaPravila]) z `getprop` - samo z delovne ali glavne niti ob izbiri toka. */
object Diagnostika {
    fun vklopljena(lastnost: String): Boolean = DiagnostikaPravila.dovoljenaLastnost(lastnost) && try {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", lastnost))
        val v = p.inputStream.bufferedReader().use { it.readLine() }
        DiagnostikaPravila.velja(v, System.currentTimeMillis() / 1000)
    } catch (_: Throwable) { false }

    /** Z3 v zivo: torrent odprtega filma na tej napravi po samodejni poti ([DiagnostikaPravila.P2P_SAMO]). */
    fun samoP2p(): Boolean = vklopljena(DiagnostikaPravila.P2P_SAMO)
}
