// Nadomestek samo za tests/run_dash_jvm_test.sh (DashPrevzem na JVM); ni del aplikacije.
package android.util
object Log {
    val SPOROCILA = java.util.concurrent.CopyOnWriteArrayList<String>()
    @JvmStatic fun i(tag: String, msg: String): Int { SPOROCILA.add("$tag: $msg"); return 0 }
}
