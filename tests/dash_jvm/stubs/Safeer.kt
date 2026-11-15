// Nadomestek samo za tests/run_dash_jvm_test.sh (DashPrevzem na JVM); ni del aplikacije.
package si.safeer.tv
object SafeerDbg {
    val ZAPISI = java.util.concurrent.CopyOnWriteArrayList<String>()
    fun log(a: String, b: String, c: String, j: org.json.JSONObject) { ZAPISI.add("$a $b $c $j") }
}
object DashPravila {
    fun gostitelj(url: String): String = try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }
    fun prepusti(url: String, manifest: String, oglas: Boolean): Boolean = false
}
object AdBlockEngine { fun jeOglasniGostitelj(g: String): Boolean = false }
class PredajaStrani(private val isti: (String, String) -> Boolean) {
    private val s = ArrayList<String>()
    fun prepusti(m: String) { s.add(m) }
    fun jePrepuscen(m: String): Boolean = s.any { isti(it, m) }
    fun pocisti() { s.clear() }
}
class ChromiumEngineView { companion object { const val DESKTOP_USER_AGENT = "Mozilla/5.0 (preizkus)" } }
