package si.safeer.tv.os

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Naloga 109: skladbe (posnetki z YouTuba, uvozene skladbe), ki ne delujejo, z razlogom ([NedelujocePravila]) - samo na tej
 * napravi. Trajen razlog (zaseben, odstranjen, ni v tej drzavi, ...) pomeni: v vrsti se skladba tiho preskoci in se ne
 * poskusa znova; uporabnik jo preveri ali zamenja sam (»Poisci drug posnetek«, »Preveri znova«). Kljuc je id skladbe.
 */
object NedelujoceSkladbe {
    private const val PREFS = "safeer_nedelujoce_skladbe"
    /** Najvec zapisov (najstarejsi gredo) - nastavitve so cele v pomnilniku procesa. */
    private const val NAJVEC = 500
    private val zapisi = ConcurrentHashMap<String, String>()
    @Volatile private var nalozeno = false

    private fun nalozi(c: Context) {
        if (nalozeno) return
        synchronized(this) {
            if (nalozeno) return
            for ((k, v) in c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all) (v as? String)?.let { zapisi[k] = it }
            nalozeno = true
        }
    }

    /** Razlog za skladbo [id] ali null (ne vemo, da ne deluje). */
    fun razlog(c: Context, id: String): String? { nalozi(c); return zapisi[id]?.let { NedelujocePravila.izZapisa(it).first } }

    fun zapomni(c: Context, id: String, razlog: String, ime: String) {
        nalozi(c)
        if (id.isBlank()) return
        val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = p.edit()
        if (zapisi.size >= NAJVEC && !zapisi.containsKey(id)) zapisi.keys.firstOrNull()?.let { star -> zapisi.remove(star); e.remove(star) }
        val z = NedelujocePravila.vZapis(razlog, ime)
        zapisi[id] = z
        e.putString(id, z).apply()
    }

    /** Razlog za uporabnika (Live TV in glasba; brez naslova toka). */
    fun opis(c: Context, razlog: String): String {
        val http = NedelujocePravila.kodaHttp(razlog)
        return when {
            http > 0 -> c.getString(si.safeer.tv.R.string.os_nedel_http, http)
            razlog == NedelujocePravila.NI_TOKA -> c.getString(si.safeer.tv.R.string.os_nedel_ni_toka)
            razlog == NedelujocePravila.STREZNIK -> c.getString(si.safeer.tv.R.string.os_nedel_streznik)
            razlog == NedelujocePravila.NEODZIVEN -> c.getString(si.safeer.tv.R.string.os_nedel_neodziven)
            razlog == NedelujocePravila.DRM -> c.getString(si.safeer.tv.R.string.os_nedel_drm)
            razlog == NedelujocePravila.NI_NA_VOLJO -> c.getString(si.safeer.tv.R.string.os_nedel_ni_na_voljo)
            razlog == NedelujocePravila.ZASEBEN -> c.getString(si.safeer.tv.R.string.os_nedel_zaseben)
            razlog == NedelujocePravila.DRZAVA -> c.getString(si.safeer.tv.R.string.os_nedel_drzava)
            razlog == NedelujocePravila.PRIJAVA -> c.getString(si.safeer.tv.R.string.os_nedel_prijava)
            razlog == NedelujocePravila.CLANI -> c.getString(si.safeer.tv.R.string.os_nedel_clani)
            else -> c.getString(si.safeer.tv.R.string.os_nedel_ni_znano)
        }
    }

    /** »Preveri znova«, nov posnetek ali odstranitev s seznama: zapis izgine. */
    fun pozabi(c: Context, id: String) {
        nalozi(c)
        if (zapisi.remove(id) != null) c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id).apply()
    }
}
