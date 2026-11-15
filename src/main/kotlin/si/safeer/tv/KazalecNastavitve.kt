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

    /** Hitrost kazalca (K12): stopnja 0..2 ([KazalecGibanje.hitrost]); drugo datoteko kot izbira po gostiteljih, ki jo ob 200 vnosih pobrise. */
    private const val PREFS_HITROST = "safeer_kazalec_hitrost"

    fun hitrostStopnja(context: Context): Int = KazalecGibanje.veljavnaStopnja(
        context.getSharedPreferences(PREFS_HITROST, Context.MODE_PRIVATE).getInt("stopnja", KazalecGibanje.PRIVZETA_STOPNJA))

    fun nastaviHitrost(context: Context, stopnja: Int) {
        context.getSharedPreferences(PREFS_HITROST, Context.MODE_PRIVATE).edit().putInt("stopnja", KazalecGibanje.veljavnaStopnja(stopnja)).apply()
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
