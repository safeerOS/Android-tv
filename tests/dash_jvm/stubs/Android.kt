// Nadomestki Androida samo za tests/run_dash_jvm_test.sh (DashPrevzem na JVM); niso del aplikacije.
package android.net
class Uri private constructor(private val u: java.net.URI?) {
    val scheme: String? get() = u?.scheme
    val host: String? get() = u?.host
    val path: String? get() = u?.rawPath
    companion object { @JvmStatic fun parse(s: String): Uri = Uri(try { java.net.URI(s) } catch (_: Exception) { null }) }
}
