package si.safeer.tv.os

import android.content.Context
import org.json.JSONObject

/**
 * Naloga 63 (lastnik, 22. 10. 2026: serija »Lucifer« med Filmi): vrsta naslova (film / serija), kot so jo pokazali
 * podatki, ki jih Safeer ze ima - vnos kataloga, metapodatki dodatka (epizode po sezonah) ali odprt plakat. Dodatek lahko
 * serijo navede v katalogu filmov; ko enkrat vemo, da je serija, se ne pokaze vec med Filmi (in film, za katerega vemo,
 * ne med Serijami). Nobenih novih poizvedb: shramba samo zapise, kar je prislo z ze poslanimi vprasanji.
 *
 * Kljuca: "i:<imdb>" (isti naslov iz vec dodatkov) in "s:<osnova dodatka>|<id>" (vnos brez IMDb, npr. katalog m3u).
 * Serija je lepljiva: film je ne povozi (dodatek, ki serijo imenuje film, ne sme skriti prave serije). Najvec [NAJVEC]
 * zapisov, najstarejsi gredo; na disk (SharedPreferences) najvec enkrat na [ZAMIK_MS].
 *
 * Naloga 66: v isti shrambi so drzave izvora (`country` iz vnosa kataloga ali metapodatkov, npr. »South Korea«) po
 * IMDb - za filter jezika, kadar jezika naslova ne vemo ([OsPravila.jezikPoDrzavah]). Tudi te pridejo samo z ze
 * poslanimi vprasanji.
 */
object VrsteNaslovov {
    const val SERIJA = "series"
    const val FILM = "movie"
    private const val NASTAVITVE = "safeer_vrste_naslovov"
    private const val KLJUC = "vrste"
    private const val KLJUC_DRZAV = "drzave"
    private const val NAJVEC = 4000
    /** Pregled 70: drzave se menjajo z vsako stranjo kataloga - manjsa shramba (SharedPreferences je cela v kopici). */
    private const val NAJVEC_DRZAV = 1000
    private const val ZAMIK_MS = 2_000L

    private val vrste = LinkedHashMap<String, String>()
    /** Kljuc "i:<imdb>" -> drzave izvora (kot jih da dodatek); pod zaklepom [vrste]. */
    private val drzave = LinkedHashMap<String, String>()
    @Volatile private var app: Context? = null
    @Volatile private var nalozeno = false
    private val pisalec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "safeer-vrste").apply { isDaemon = true } }
    private val narocen = java.util.concurrent.atomic.AtomicBoolean()

    /** Ob odprtju Medijskega centra: zapisi z diska pridejo v ozadju (glavna nit ne bere diska). */
    fun pripravi(ctx: Context) {
        if (app == null) app = ctx.applicationContext
        if (!nalozeno) try { pisalec.execute { nalozi() } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    private fun nalozi() {
        if (nalozeno) return
        val c = app ?: return
        val nast = c.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE)
        val o = try { JSONObject(nast.getString(KLJUC, null) ?: "{}") } catch (_: Exception) { JSONObject() }
        val od = try { JSONObject(nast.getString(KLJUC_DRZAV, null) ?: "{}") } catch (_: Exception) { JSONObject() }
        synchronized(vrste) {
            // Kar je ta zagon ze spoznal, je novejse od diska.
            val zdaj = LinkedHashMap(vrste)
            vrste.clear()
            for (k in o.keys()) o.optString(k).takeIf { it == SERIJA || it == FILM }?.let { vrste[k] = it }
            for ((k, v) in zdaj) vpisi(k, v)
            val zdajDrzave = LinkedHashMap(drzave)
            drzave.clear()
            for (k in od.keys()) od.optString(k).takeIf { it.isNotBlank() }?.let { vpisiDrzave(k, it) }
            for ((k, v) in zdajDrzave) vpisiDrzave(k, v)
            nalozeno = true
        }
    }

    /** Kljuci kartice: IMDb in vnos dodatka (ce je kartica iz dodatka). */
    fun kljuci(s: Jamendo.Skladba): List<String> = listOfNotNull(
        s.imdbId.takeIf { it.isNotBlank() }?.let { "i:" + it.lowercase() },
        Stremio.razstavi(s)?.let { (osnova, _, id) -> kljucVnosa(osnova, id) })

    fun kljucVnosa(osnova: String, id: String): String =
        if (id.startsWith("tt") && id.length > 2 && id.drop(2).all { it.isDigit() }) "i:" + id.lowercase() else "s:$osnova|$id"

    /** SERIJA, FILM ali null (ne vemo). Serija ima prednost, ce se kljuca ne ujemata. Brez diska in omrezja. */
    fun vrsta(s: Jamendo.Skladba): String? {
        val k = kljuci(s)
        if (k.isEmpty()) return null
        val v = synchronized(vrste) { k.mapNotNull { vrste[it] } }
        return if (SERIJA in v) SERIJA else v.firstOrNull()
    }

    /** Zapomni vrsto za kljuce; vrne true, ce se je kaj spremenilo. */
    fun zapomni(kljuci: Collection<String>, vrsta: String): Boolean {
        if ((vrsta != SERIJA && vrsta != FILM) || kljuci.isEmpty()) return false
        val spremenjeno = synchronized(vrste) { kljuci.fold(false) { s, k -> vpisi(k, vrsta) || s } }
        if (spremenjeno) shraniKasneje()
        return spremenjeno
    }

    fun zapomni(s: Jamendo.Skladba, vrsta: String): Boolean = zapomni(kljuci(s), vrsta)

    /** Naloga 66: drzave izvora naslova (po IMDb) ali null. Brez diska in omrezja. */
    fun drzave(s: Jamendo.Skladba): String? {
        val k = s.imdbId.takeIf { it.isNotBlank() }?.let { "i:" + it.lowercase() } ?: return null
        return synchronized(vrste) { drzave[k] }
    }

    /** Naloga 66: zapomni drzave izvora za IMDb [imdb] (»tt...«); vrne true, ce se je kaj spremenilo. */
    fun zapomniDrzave(imdb: String, niz: String): Boolean {
        val d = niz.trim().take(200)
        if (!imdb.startsWith("tt") || d.isEmpty()) return false
        val spremenjeno = synchronized(vrste) { vpisiDrzave("i:" + imdb.lowercase(), d) }
        if (spremenjeno) shraniKasneje()
        return spremenjeno
    }

    /** Klic pod zaklepom [vrste]. */
    private fun vpisiDrzave(k: String, d: String): Boolean {
        if (drzave[k] == d) return false
        drzave.remove(k)
        drzave[k] = d
        while (drzave.size > NAJVEC_DRZAV) drzave.remove(drzave.keys.first())
        return true
    }

    /** Klic pod zaklepom [vrste]. */
    private fun vpisi(k: String, vrsta: String): Boolean {
        val prej = vrste[k]
        if (prej == vrsta || (prej == SERIJA && vrsta == FILM)) return false
        vrste.remove(k)
        vrste[k] = vrsta
        while (vrste.size > NAJVEC) vrste.remove(vrste.keys.first())
        return true
    }

    private fun shraniKasneje() {
        if (app == null || !narocen.compareAndSet(false, true)) return
        try {
            pisalec.schedule(Runnable {
                narocen.set(false)
                val c = app ?: return@Runnable
                if (!nalozeno) nalozi()
                val (o, od) = synchronized(vrste) { JSONObject(vrste as Map<*, *>) to JSONObject(drzave as Map<*, *>) }
                c.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit().putString(KLJUC, o.toString())
                    .putString(KLJUC_DRZAV, od.toString()).apply()
            }, ZAMIK_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) { narocen.set(false) }
    }
}
