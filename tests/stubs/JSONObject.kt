package org.json

/**
 * Test stub: Androidov org.json ni na poti razredov v navadnem JVM. SponsorBlock rabi samo
 * JSONObject.quote(); tu je enako obnasanje (JSON-ov niz v narekovajih z ubeznimi znaki).
 */
object JSONObject {
    @JvmStatic
    fun quote(s: String?): String {
        if (s == null) return "\"\""
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '/' -> sb.append("\\/")
                '\b' -> sb.append("\\b")
                '' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
