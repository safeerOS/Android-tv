// Nadomestek samo za tests/run_dash_jvm_test.sh (DashPrevzem na JVM); ni del aplikacije.
package org.json
class JSONObject() {
    private val m = LinkedHashMap<String, Any?>()
    constructor(s: String) : this() {
        Regex("\"([^\"]*)\"\\s*:\\s*\"([^\"]*)\"").findAll(s).forEach { m[it.groupValues[1]] = it.groupValues[2] }
    }
    fun put(k: String, v: Any?): JSONObject { m[k] = v; return this }
    fun keys(): Iterator<String> = m.keys.toList().iterator()
    fun optString(k: String, d: String): String = m[k]?.toString() ?: d
    override fun toString() = m.toString()
}
