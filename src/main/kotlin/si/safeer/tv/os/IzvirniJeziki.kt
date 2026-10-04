package si.safeer.tv.os

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Izvirni jezik filmov in serij iz Wikidate, s predpomnilnikom na napravi. Pravila (poizvedbi, branje odgovora, izbira
 * glavnega jezika) so v [IzvirniJezik].
 *
 * Kaj gre iz naprave: samo javni id-ji naslovov (IMDb) iz kataloga, ki je na zaslonu, in izbrani jezik - in to sele,
 * ko uporabnik izbere jezik v filtru. O uporabniku, njegovih dodatkih ali tem, kaj gleda, Wikidata ne izve nicesar.
 */
object IzvirniJeziki {
    private const val TAG = "SafeerJeziki"
    private const val NASLOV = "https://query.wikidata.org/sparql"
    private const val DATOTEKA = "izvirni-jeziki.tsv"
    /**
     * Razlicica tabele jezikov ([IzvirniJezik.JEZIKI]). Ko tabela dobi nove predmete (razlicice jezika), zapisi
     * »neznan jezik« iz stare tabele niso vec resnicni (veljali bi se do 7 dni); znani ostanejo.
     */
    private const val TABELA = "2"
    private const val VRSTICA_TABELE = "#tabela\t"
    private const val NAJVEC_ZAPISOV = 20_000
    private const val SEZNAM_VELJA_MS = 6L * 3_600_000

    /** id IMDb -> zapis ([IzvirniJezik.vZapis]). */
    private val zapisi = ConcurrentHashMap<String, String>()
    @Volatile private var nalozeno = false
    /** Stran seznama naslovov v jeziku: naslovi, pri katerih je ta jezik glavni, in ali ima seznam se naslednjo stran. */
    class Stran(val naslovi: List<IzvirniJezik.Naslov>, val seKaj: Boolean)

    /** Strani seznamov po jeziku (kljuc "koda|tip|odmik|jezik vmesnika") za cas, ko je aplikacija odprta. */
    private val seznami = ConcurrentHashMap<String, Pair<Long, Stran>>()
    /** Wikidata dovoli le nekaj socasnih vprasanj z enega naslova: vec kot toliko jih ne posljemo hkrati. */
    private val socasno = Semaphore(4)
    /** Paketi vprasanj tecejo najvec trije hkrati: cetrto mesto ostane seznamu jezika, da ne caka za njimi. */
    private val paketi = java.util.concurrent.Executors.newFixedThreadPool(3) { r -> Thread(r, "safeer-jeziki").apply { isDaemon = true } }
    /** Po odgovoru »prevec vprasanj« (429) do takrat ne sprasujemo. */
    @Volatile private var premorDo = 0L

    private fun datoteka(c: Context) = File(c.filesDir, DATOTEKA)

    @Synchronized
    private fun nalozi(c: Context) {
        if (nalozeno) return
        try {
            val f = datoteka(c)
            var tabela = ""
            if (f.isFile) f.forEachLine { v ->
                if (v.startsWith(VRSTICA_TABELE)) { tabela = v.substring(VRSTICA_TABELE.length).trim(); return@forEachLine }
                val i = v.indexOf('\t')
                if (i > 0 && IzvirniJezik.veljavenImdb(v.substring(0, i))) zapisi[v.substring(0, i)] = v.substring(i + 1)
            }
            if (tabela != TABELA) {
                val zdaj = System.currentTimeMillis()
                zapisi.entries.removeAll { IzvirniJezik.izZapisa(it.value, zdaj).isNullOrEmpty() }
            }
        } catch (e: Throwable) { Log.i(TAG, "Branje: ${e.message}") }
        nalozeno = true
    }

    @Synchronized
    private fun shrani(c: Context) {
        try {
            // Prevec zapisov: najstarejsi gredo (cas je zadnji del zapisa).
            if (zapisi.size > NAJVEC_ZAPISOV) {
                zapisi.entries.sortedBy { it.value.substringAfterLast(';').toLongOrNull() ?: 0L }
                    .take(zapisi.size - NAJVEC_ZAPISOV * 9 / 10).forEach { zapisi.remove(it.key) }
            }
            val f = datoteka(c)
            val zacasna = File(f.parentFile, f.name + ".tmp")
            zacasna.bufferedWriter().use { w ->
                w.write(VRSTICA_TABELE); w.write(TABELA); w.write("\n")
                for ((id, z) in zapisi) { w.write(id); w.write("\t"); w.write(z); w.write("\n") }
            }
            if (!zacasna.renameTo(f)) { f.delete(); if (!zacasna.renameTo(f)) zacasna.delete() }
        } catch (e: Throwable) { Log.i(TAG, "Zapis: ${e.message}") }
    }

    /** Kar o naslovu ze vemo, brez omrezja: kode glavnega jezika (prazno = neznan), null = se nismo vprasali. */
    fun znani(c: Context, imdb: String): List<String>? {
        nalozi(c)
        return IzvirniJezik.izZapisa(zapisi[imdb], System.currentTimeMillis())
    }

    /**
     * Glavni jezik naslovov [idji] (id IMDb -> kode). Kar je v predpomnilniku, pride takoj; ostalo vprasamo Wikidato v
     * paketih. Klic iz ozadja (omrezje). Naslov, za katerega odgovora ni (izpad), v izidu manjka in ostane nevprasan.
     * [takoj] = vprasanje gre s klicoce niti (seznam jezika), ne v vrsto za paketi katalogov.
     */
    fun jeziki(c: Context, idji: Collection<String>, takoj: Boolean = false): Map<String, List<String>> {
        nalozi(c)
        val zdaj = System.currentTimeMillis()
        val izid = HashMap<String, List<String>>()
        val manjkajo = ArrayList<String>()
        for (id in idji.filter(IzvirniJezik::veljavenImdb).distinct()) {
            val kode = IzvirniJezik.izZapisa(zapisi[id], zdaj)
            if (kode != null) izid[id] = kode else manjkajo += id
        }
        var novih = 0
        fun paket(idji: List<String>): Map<String, List<String>>? {
            val tsv = vprasaj(IzvirniJezik.poizvedbaPaket(idji)) ?: return null
            val odgovor = IzvirniJezik.izPaketa(tsv)
            // Naslov, ki ga Wikidata ne pozna, zapisemo kot neznan: cez nekaj dni vprasamo znova.
            return idji.associateWith { odgovor[it].orEmpty() }
        }
        val deli = manjkajo.chunked(IzvirniJezik.NAJVEC_V_PAKETU)
        val odgovori = if (takoj) deli.map { paket(it) }
            else deli.map { d -> paketi.submit<Map<String, List<String>>?> { paket(d) } }.map { n -> try { n.get(45, TimeUnit.SECONDS) } catch (_: Exception) { null } }
        for (del in odgovori) {
            if (del == null) continue
            for ((id, kode) in del) {
                zapisi[id] = IzvirniJezik.vZapis(kode, zdaj)
                izid[id] = kode
                novih++
            }
        }
        if (novih > 0) shrani(c)
        return izid
    }

    /**
     * Naslovi v jeziku [koda] po prepoznavnosti ([tip] = "movie" ali "series", [odmik] = koliko jih preskociti).
     * Klic iz ozadja. null = brez odgovora (izpad).
     */
    fun seznam(c: Context, koda: String, tip: String, odmik: Int = 0): Stran? {
        val jezik = IzvirniJezik.poKodi(koda) ?: return Stran(emptyList(), false)
        val vmesnik = try { c.resources.configuration.locales[0].language } catch (_: Throwable) { "en" }
        val kljuc = "$koda|$tip|$odmik|$vmesnik"
        val zdaj = System.currentTimeMillis()
        seznami[kljuc]?.let { (cas, s) -> if (zdaj - cas in 0..SEZNAM_VELJA_MS) return s }
        val tsv = vprasaj(IzvirniJezik.poizvedbaSeznam(jezik, tip, odmik, vmesnik = vmesnik)) ?: return null
        val vsi = IzvirniJezik.izSeznama(tsv)
        // Seznam ima tudi naslove, v katerih je ta jezik le eden od vec (ameriski film z nemskim soproducentom in nekaj
        // nemscine): ostanejo tisti, pri katerih je glavni. Naslov, za katerega odgovora ni (izpad), ostane.
        val glavni = jeziki(c, vsi.map { it.imdb }, takoj = true)
        val stran = Stran(vsi.filter { n -> glavni[n.imdb]?.let { koda in it } ?: true }, vsi.size >= IzvirniJezik.STRAN_SEZNAMA)
        if (seznami.size > 200) seznami.clear()
        seznami[kljuc] = zdaj to stran
        return stran
    }

    private fun vprasaj(poizvedba: String): String? {
        if (System.currentTimeMillis() < premorDo) return null
        val t0 = android.os.SystemClock.elapsedRealtime()
        if (!socasno.tryAcquire(20, TimeUnit.SECONDS)) return null
        val t1 = android.os.SystemClock.elapsedRealtime()
        try {
            val c = URL(NASLOV + "?query=" + URLEncoder.encode(poizvedba, "UTF-8")).openConnection() as HttpURLConnection
            c.connectTimeout = 8_000; c.readTimeout = 25_000
            // Wikidata prosi za prepoznaven opis odjemalca z naslovom za stik.
            c.setRequestProperty("User-Agent", "SafeerOS/${si.safeer.tv.BuildConfig.VERSION_NAME} (https://safeer.si)")
            c.setRequestProperty("Accept", "text/tab-separated-values")
            try {
                val koda = c.responseCode
                if (koda == 429) {
                    val cakaj = c.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(30, 3_600) ?: 120
                    premorDo = System.currentTimeMillis() + cakaj * 1_000
                    Log.i(TAG, "Wikidata prosi za premor $cakaj s")
                    return null
                }
                if (koda !in 200..299) { Log.i(TAG, "Wikidata: HTTP $koda"); return null }
                val odgovor = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                // Trajanje (cakanje na prosto mesto + omrezje) in velikost: brez vsebine vprasanja.
                Log.i(TAG, "Wikidata: ${if (poizvedba.contains("GROUP BY")) "seznam" else "paket"} ${odgovor.length} B v ${android.os.SystemClock.elapsedRealtime() - t1} ms (cakanje ${t1 - t0} ms)")
                return odgovor
            } finally { c.disconnect() }
        } catch (e: Throwable) {
            Log.i(TAG, "Wikidata: ${e.javaClass.simpleName}")
            return null
        } finally { socasno.release() }
    }
}
