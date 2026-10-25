package si.safeer.tv.os

/**
 * Nadomestek SpletniVir za preizkus sonde v navadnem JVM (tests/run_sonda_tests.sh): pravi SpletniVir potrebuje WebView
 * (UA, piskotki) in Media3. Sonda dobi glave toka, kot jih poda klicatelj.
 */
object SpletniVir {
    fun glaveZa(url: String, glaveVsem: Map<String, String> = emptyMap(), piskotki: Boolean = true): Map<String, String> =
        HashMap(glaveVsem)
}
