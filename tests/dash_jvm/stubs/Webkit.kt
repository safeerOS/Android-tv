// Nadomestek samo za tests/run_dash_jvm_test.sh (DashPrevzem na JVM); ni del aplikacije.
package android.webkit
class CookieManager private constructor() {
    fun getCookie(url: String): String? {
        val h = try { java.net.URI(url).host } catch (_: Exception) { null } ?: return null
        return POSODA[h.lowercase()]
    }
    companion object {
        val POSODA = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val I = CookieManager()
        @JvmStatic fun getInstance(): CookieManager = I
    }
}
