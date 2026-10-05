package si.safeer.tv.os

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Kanali in postaje, ki pri viru ta cas ne delujejo: dotik je dal napako vira ([OsPravila.mrtevKanal]) ali pa dodatek
 * zanje nima prenosa. Do izteka ([OsPravila.MRTEV_KANAL_MS]) jih ne kazemo - kar se ne da predvajati, ne kazemo
 * (lastnik). Potem se kanal vrne in se preveri znova. Zraven: kanali, za katere vemo, da prenos imajo
 * ([OsPravila.ZIV_KANAL_MS]) - mreza kanalov jih pokaze brez novega vprasanja. Hranimo samo id kartice in cas izteka,
 * na tej napravi.
 */
object MrtviKanali {
    private const val PREFS = "safeer_mrtvi_kanali"
    private const val PREFS_ZIVI = "safeer_zivi_kanali"
    private val doKdaj = ConcurrentHashMap<String, Long>()
    private val zivDo = ConcurrentHashMap<String, Long>()
    @Volatile private var nalozeno = false

    /** Zaslon se prijavi, da kartico umakne takoj, ko kanal odpove (kljuc kartice). */
    val poslusalci = CopyOnWriteArraySet<(String) -> Unit>()

    private fun nalozi(c: Context) {
        if (nalozeno) return
        synchronized(this) {
            if (nalozeno) return
            val zdaj = System.currentTimeMillis()
            for ((ime, cilj) in listOf(PREFS to doKdaj, PREFS_ZIVI to zivDo)) {
                val p = c.applicationContext.getSharedPreferences(ime, Context.MODE_PRIVATE)
                val potekli = ArrayList<String>()
                for ((k, v) in p.all) {
                    val t = (v as? Long) ?: continue
                    if (t > zdaj) cilj[k] = t else potekli += k
                }
                if (potekli.isNotEmpty()) p.edit().apply { potekli.forEach { remove(it) } }.apply()
            }
            nalozeno = true
        }
    }

    fun zapomni(c: Context, id: String) {
        nalozi(c)
        val k = OsPravila.kljucKanala(id)
        val t = System.currentTimeMillis() + OsPravila.MRTEV_KANAL_MS
        doKdaj[k] = t
        zivDo.remove(k)
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(k, t).apply()
        c.applicationContext.getSharedPreferences(PREFS_ZIVI, Context.MODE_PRIVATE).edit().remove(k).apply()
        for (p in poslusalci) try { p(k) } catch (_: Throwable) { }
    }

    fun jeMrtev(c: Context, id: String): Boolean {
        nalozi(c)
        if (doKdaj.isEmpty()) return false
        val k = OsPravila.kljucKanala(id)
        val t = doKdaj[k] ?: return false
        if (t > System.currentTimeMillis()) return true
        doKdaj.remove(k)
        return false
    }

    /** Dodatek ima za kanal prenos (preverjeno v ozadju). */
    fun zapomniZivega(c: Context, id: String) {
        nalozi(c)
        val k = OsPravila.kljucKanala(id)
        val t = System.currentTimeMillis() + OsPravila.ZIV_KANAL_MS
        zivDo[k] = t
        c.applicationContext.getSharedPreferences(PREFS_ZIVI, Context.MODE_PRIVATE).edit().putLong(k, t).apply()
    }

    /** true = dodatek ima prenos, false = kanal ta cas ne dela, null = se ne vemo (ali je odgovor potekel). */
    fun stanje(c: Context, id: String): Boolean? {
        if (jeMrtev(c, id)) return false
        val k = OsPravila.kljucKanala(id)
        val t = zivDo[k] ?: return null
        if (t > System.currentTimeMillis()) return true
        zivDo.remove(k)
        return null
    }
}
