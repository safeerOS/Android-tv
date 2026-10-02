package si.safeer.tv.os

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Kaj od prikazanega se res da predvajati (Matej, 2. 10. 2026: "ce videa ali serije ne moremo predvajati, ga ne
 * prikazemo"). Katalog (npr. Cinemeta) pozna vse naslove, tokove pa imajo le uporabnikovi dodatki - zato si za vsak
 * naslov zapomnimo, ali ga kateri dodatek ponuja. "Ni na voljo" velja nekaj ur (dodatki dobivajo nove vsebine),
 * "je na voljo" en dan. Ob spremembi dodatkov se vse pozabi. Samo na tej napravi.
 */
object Razpolozljivost {
    private const val DATOTEKA = "safeer_razpolozljivost"
    private const val NI_VELJA = 6 * 3_600_000L
    private const val JE_VELJA = 24 * 3_600_000L
    private const val NAJVEC = 4000
    private const val ZACASNO_VELJA = 10 * 60_000L

    /**
     * Skrito le za kratek cas in samo v pomnilniku (kljuc -> cas): dotik ni dal toka, a se ne vemo, ali so odgovorili
     * vsi dodatki. Izpad dodatka ali omrezja ne sme naslova skriti za ure - to stori sele potrjen "ni" ([zapomni]).
     */
    private val zacasno = ConcurrentHashMap<String, Long>()

    fun zacasnoNi(kljuc: String) { zacasno[kljuc] = System.currentTimeMillis() }

    /** kljuc -> cas zapisa; negativen cas pomeni "ni na voljo". */
    private val stanja = ConcurrentHashMap<String, Long>()
    @Volatile private var nalozeno = false
    @Volatile private var umazano = false
    @Volatile private var shranjujem = false

    fun kljuc(tip: String, id: String) = "$tip|$id"

    private fun nalozi(c: Context) {
        if (nalozeno) return
        synchronized(this) {
            if (nalozeno) return
            try {
                val o = JSONObject(c.getSharedPreferences(DATOTEKA, Context.MODE_PRIVATE).getString("stanja", "{}") ?: "{}")
                for (k in o.keys()) stanja[k] = o.optLong(k)
            } catch (_: Exception) { }
            nalozeno = true
        }
    }

    /** true = se da predvajati, false = ne, null = ne vemo (se ni preverjeno ali je zapis zastarel). */
    fun stanje(c: Context, kljuc: String): Boolean? {
        nalozi(c)
        zacasno[kljuc]?.let { if (System.currentTimeMillis() - it in 0..ZACASNO_VELJA) return false else zacasno.remove(kljuc) }
        val cas = stanja[kljuc] ?: return null
        val starost = System.currentTimeMillis() - kotlin.math.abs(cas)
        return when {
            cas < 0 -> if (starost in 0..NI_VELJA) false else null
            else -> if (starost in 0..JE_VELJA) true else null
        }
    }

    fun zapomni(c: Context, kljuc: String, je: Boolean) {
        nalozi(c)
        if (stanja.size > NAJVEC) {
            // Najstarejso polovico pozabimo (zapisi se sproti obnavljajo).
            stanja.entries.sortedBy { kotlin.math.abs(it.value) }.take(NAJVEC / 2).forEach { stanja.remove(it.key) }
        }
        stanja[kljuc] = System.currentTimeMillis() * (if (je) 1 else -1)
        zacasno.remove(kljuc)
        umazano = true
        shrani(c.applicationContext)
    }

    /** Drugi dodatki = druga razpolozljivost: ob spremembi seznama dodatkov vse pozabimo. */
    fun pripravi(c: Context, dodatki: List<String>) {
        nalozi(c)
        val odtis = dodatki.map { it.trim() }.sorted().joinToString("\n").hashCode().toString()
        val p = c.getSharedPreferences(DATOTEKA, Context.MODE_PRIVATE)
        if (p.getString("dodatki", "") == odtis) return
        stanja.clear(); zacasno.clear()
        p.edit().putString("dodatki", odtis).putString("stanja", "{}").apply()
    }

    /** Zapis na disk zdruzimo: med preverjanjem mreze pride veliko sprememb zapored. */
    private fun shrani(c: Context) {
        if (shranjujem) return
        shranjujem = true
        Thread {
            try {
                Thread.sleep(1_500)
                while (umazano) {
                    umazano = false
                    val o = JSONObject()
                    for ((k, v) in stanja) o.put(k, v)
                    c.getSharedPreferences(DATOTEKA, Context.MODE_PRIVATE).edit().putString("stanja", o.toString()).apply()
                }
            } catch (_: Exception) { } finally { shranjujem = false }
        }.apply { name = "Safeer-razpolozljivost"; isDaemon = true; start() }
    }
}
