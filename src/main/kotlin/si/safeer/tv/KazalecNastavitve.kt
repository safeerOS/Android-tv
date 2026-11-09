package si.safeer.tv

import android.content.Context

/** Uporabnikova izbira kazalca po gostitelju (SharedPreferences, samo ta naprava). */
object KazalecNastavitve {
    private const val PREFS = "safeer_kazalec_strani"
    private const val NAJVEC = 200

    fun zapomnjeno(context: Context, gostitelj: String?): Boolean? {
        if (gostitelj.isNullOrEmpty()) return null
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.contains(gostitelj)) p.getBoolean(gostitelj, false) else null
    }

    fun zapomni(context: Context, gostitelj: String?, kazalec: Boolean) {
        if (gostitelj.isNullOrEmpty()) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = p.edit()
        // Omejitev velikosti: ob prevec zapisih pozabi vse (redko; brez rasti v nedogled).
        if (!p.contains(gostitelj) && p.all.size >= NAJVEC) e.clear()
        e.putBoolean(gostitelj, kazalec).apply()
    }
}
