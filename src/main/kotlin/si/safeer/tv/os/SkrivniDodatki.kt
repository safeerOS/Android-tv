package si.safeer.tv.os

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * Webtor rezerva (25. 10. 2026): skrivni deli naslovov dodatkov ([SkrivnostiPravila.skrivnaPredpona] - osebni zeton
 * Webtorja, debrid kljuc v nastavitvah Torrentia ...). Vsaka skrivnost je shranjena sifrirana z Android Keystore
 * ([SporocilaSkrivnosti], kot debrid kljuci); v nastavitvah je samo seznam odtisov. Aplikacija pozna le nadomestek
 * (»https://<gostitelj>/safeer-skrito-<odtis>«), pravi naslov vstavimo tik pred omreznim klicem ([dejanski]: Stremio,
 * predvajalnik prek [SpletniVir.virPodatkov], sonda Webtor). Nikoli v dnevnik ali na zaslon.
 */
object SkrivniDodatki {
    private const val TAG = "SafeerSkrivni"
    private const val PREFS = "safeer_skrivni_dodatki"
    private const val KLJUC_ODTISI = "odtisi"
    /** Najvec shranjenih skrivnosti (dodatki in njihovi tokovi imajo navadno eno skupno). */
    private const val NAJVEC = 50

    private fun ime(odtis: String) = "dodatek_$odtis"

    /** nadomestna osnova -> prava skrivna predpona */
    private val predpone = ConcurrentHashMap<String, String>()
    @Volatile private var nalozeno = false

    private fun odtisi(c: Context): List<String> = try {
        val a = JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KLJUC_ODTISI, "[]") ?: "[]")
        (0 until a.length()).map { a.optString(it) }.filter { Regex("^[0-9a-f]{16}$").matches(it) }
    } catch (_: Exception) { emptyList() }

    /** Prebere shranjene skrivnosti v pomnilnik (enkrat na proces; Keystore). */
    fun nalozi(c: Context) {
        if (nalozeno) return
        synchronized(this) {
            if (nalozeno) return
            val a = c.applicationContext
            for (o in odtisi(a)) {
                val p = SporocilaSkrivnosti.preberi(a, ime(o)) ?: continue
                SkrivnostiPravila.nadomestek(p)?.let { predpone[it] = p }
            }
            nalozeno = true
            if (predpone.isNotEmpty()) Log.i(TAG, "skrivnih dodatkov: ${predpone.size}")
        }
    }

    /**
     * Naslov s skrivnostjo -> nadomestek; skrivnost se shrani sifrirana. null = naslov nima skrivnosti ali je shramba
     * ni mogla sifrirati (klicatelj pravega naslova NE shrani).
     */
    fun zavaruj(c: Context, naslov: String): String? {
        val p = SkrivnostiPravila.skrivnaPredpona(naslov) ?: return null
        val n = SkrivnostiPravila.nadomestek(p) ?: return null
        nalozi(c)
        if (predpone[n] != p) synchronized(this) {
            val a = c.applicationContext
            val o = SkrivnostiPravila.odtis(p)
            val zdaj = odtisi(a)
            if (o !in zdaj && zdaj.size >= NAJVEC) { Log.w(TAG, "prevec skrivnih naslovov - ne shranim novega"); return null }
            if (!SporocilaSkrivnosti.shrani(a, ime(o), p)) { Log.w(TAG, "sifriranje ni uspelo"); return null }
            if (o !in zdaj) a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KLJUC_ODTISI, JSONArray(zdaj + o).toString()).commit()
            predpone[n] = p
        }
        return n + naslov.trim().substring(p.length)
    }

    /** Nadomestek -> pravi naslov za omrezni klic (ce skrivnost poznamo); vse drugo nespremenjeno. */
    fun dejanski(naslov: String): String {
        val n = SkrivnostiPravila.nadomestnaOsnova(naslov) ?: return naslov
        val p = predpone[n] ?: return naslov
        return p + naslov.trim().substring(n.length)
    }

    fun dejanski(c: Context?, naslov: String): String {
        if (c != null && SkrivnostiPravila.jeNadomestek(naslov)) nalozi(c)
        return dejanski(naslov)
    }

    /** Ali za ta nadomestek poznamo skrivnost (dodatek se da uporabljati na tej napravi). */
    fun pozna(naslov: String): Boolean = SkrivnostiPravila.nadomestnaOsnova(naslov)?.let { predpone.containsKey(it) } == true

    /** Naslov se zacne s katero od znanih pravih skrivnih predpon (npr. slika iz kataloga dodatka z zetonom v poti). */
    fun vsebujeSkrivnost(naslov: String): Boolean = naslov.isNotBlank() && predpone.values.any { naslov.startsWith(it) }

    /** Pozabi skrivnost nadomestka [naslov] (dodatek odstranjen). */
    fun pozabi(c: Context, naslov: String) {
        val n = SkrivnostiPravila.nadomestnaOsnova(naslov) ?: return
        nalozi(c)
        synchronized(this) {
            val p = predpone.remove(n) ?: return
            val o = SkrivnostiPravila.odtis(p)
            val a = c.applicationContext
            SporocilaSkrivnosti.pozabi(a, ime(o))
            a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KLJUC_ODTISI, JSONArray(odtisi(a) - o).toString()).apply()
        }
    }
}
