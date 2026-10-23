package si.safeer.tv.os

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Naloga 80: seznami IPTV (vir vrste SEZNAM, npr. iptv-org »countries/si.m3u«, »languages/eng.m3u«) kot kanali TV v zivo.
 * Seznam se prenese in razcleni tokovno ([IptvPravila.Bralnik]) enkrat na [IptvPravila.OSVEZI_MS] v datoteko kartic na
 * disku (vrstica na kanal); mreza bere strani iz datoteke ([naslednja]) - cel seznam nikoli ni v pomnilniku. Dnevnik:
 * SafeerOsMedia.
 */
object IptvSeznami {
    /** Id kanala iz seznama: »iptv:« + naslov toka. */
    const val PREDPONA = "iptv:"
    private const val MAPA = "iptv_seznami"
    private const val NAJVEC_BAJTOV = 20L * 1024 * 1024

    fun jeKanal(s: Jamendo.Skladba) = s.id.startsWith(PREDPONA)

    /** Kar vemo o prenesenem seznamu (datoteka .info): kdaj, koliko kanalov, katere zvrsti in jeziki (za izbire). */
    class Podatki(val prenesenOb: Long, val kanalov: Int, val zvrsti: Set<String>, val jeziki: Set<String>)

    private val podatki = ConcurrentHashMap<String, Podatki>()
    private val prenasam: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private fun ime(naslov: String) = KanaliPravila.odtisDodatka(naslov)
    private fun mapa(c: Context) = File(c.applicationContext.filesDir, MAPA)
    private fun datoteka(c: Context, naslov: String) = File(mapa(c), ime(naslov) + ".tsv")
    private fun opis(c: Context, naslov: String) = File(mapa(c), ime(naslov) + ".info")

    /** Podatki seznama z diska (brez omrezja); null = se ni prenesen. */
    fun podatki(c: Context, naslov: String): Podatki? {
        podatki[naslov]?.let { return it }
        val f = opis(c, naslov)
        if (!f.exists() || !datoteka(c, naslov).exists()) return null
        return try {
            val d = f.readText().lines()
            Podatki(d[0].toLong(), d[1].toInt(), d.getOrNull(2).orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
                d.getOrNull(3).orEmpty().split(',').filter { it.isNotBlank() }.toSet()).also { podatki[naslov] = it }
        } catch (_: Exception) { null }
    }

    /**
     * Seznam je na disku in ni zastarel: nic. Zastarel: prenese ga v ozadju (stari velja do konca). Ga ni: prenese ga zdaj
     * (klic iz ozadja). Vrne podatke, ki zdaj veljajo.
     */
    fun pripravi(c: Context, naslov: String): Podatki? {
        val p = podatki(c, naslov)
        if (p != null && !IptvPravila.zastarel(p.prenesenOb, System.currentTimeMillis())) return p
        if (p != null) { Thread({ prenesi(c, naslov) }, "safeer-iptv").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start(); return p }
        return prenesi(c, naslov)
    }

    /** Prenese in razcleni seznam tokovno v zacasno datoteko, nato jo zamenja (atomarno). Klic iz ozadja. */
    private fun prenesi(c: Context, naslov: String): Podatki? {
        if (!prenasam.add(naslov)) return podatki(c, naslov)
        val od = android.os.SystemClock.uptimeMillis()
        try {
            val (drzava, jezik) = IptvPravila.izNaslovaSeznama(naslov)
            val p = (URL(naslov).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000; readTimeout = 15_000; setRequestProperty("User-Agent", "SafeerOS")
            }
            try {
                if (p.responseCode !in 200..299) return podatki(c, naslov)
                val m = mapa(c).apply { mkdirs() }
                val zacasna = File(m, ime(naslov) + ".tmp")
                val bralnik = IptvPravila.Bralnik(naslov, drzava, jezik)
                val zvrsti = HashSet<String>(); val jeziki = HashSet<String>()
                var bajtov = 0L
                zacasna.bufferedWriter().use { w ->
                    p.inputStream.bufferedReader().use { r ->
                        while (true) {
                            val l = r.readLine() ?: break
                            bajtov += l.length + 1
                            if (bajtov > NAJVEC_BAJTOV) break
                            val v = bralnik.vrstica(l) ?: continue
                            if (v.zvrst.isNotEmpty()) zvrsti += v.zvrst
                            if (v.jezik.isNotEmpty()) jeziki += v.jezik
                            w.write(IptvPravila.vVrstico(v)); w.newLine()
                        }
                    }
                }
                val podatkiNovi = Podatki(System.currentTimeMillis(), bralnik.kanalov, zvrsti, jeziki)
                if (!zacasna.renameTo(datoteka(c, naslov))) { zacasna.delete(); return podatki(c, naslov) }
                opis(c, naslov).writeText("${podatkiNovi.prenesenOb}\n${podatkiNovi.kanalov}\n${zvrsti.joinToString(",")}\n${jeziki.joinToString(",")}")
                podatki[naslov] = podatkiNovi
                android.util.Log.i("SafeerOsMedia", "seznam IPTV: kanalov=${bralnik.kanalov}, zvrsti=${zvrsti.size}, jeziki=${jeziki.size}, " +
                    "drzava seznama=${drzava.ifEmpty { "-" }}, jezik seznama=${jezik.ifEmpty { "-" }}, po ${android.os.SystemClock.uptimeMillis() - od} ms")
                return podatkiNovi
            } finally { try { p.disconnect() } catch (_: Throwable) { } }
        } catch (_: Exception) {
            return podatki(c, naslov)
        } finally { prenasam.remove(naslov) }
    }

    /**
     * Stran kanalov seznama za mrezo: [v] pove izbiro (zvrst, jezik), ali so to domaci ([Vir.domaci]) ali ostali, in koliko
     * jih je ze dal. Datoteko bere vrstico za vrstico; vrne najvec [najvec] kanalov. Klic iz ozadja.
     */
    fun naslednja(c: Context, v: Vir, drzavaNaprave: String, jezikNaprave: String, najvec: Int = 100): List<Jamendo.Skladba> {
        if (v.konec) return emptyList()
        val f = datoteka(c, v.naslov)
        if (!f.exists()) { v.konec = true; return emptyList() }
        val izhod = ArrayList<Jamendo.Skladba>(najvec)
        var preskoci = v.dal
        var konec = true
        try {
            f.bufferedReader().useLines { vrstice ->
                for (l in vrstice) {
                    val x = IptvPravila.izVrstice(l) ?: continue
                    if (!IptvPravila.ustreza(x, v.zvrst, v.jezik) || IptvPravila.jeDomac(x, drzavaNaprave, jezikNaprave) != v.domaci) continue
                    if (preskoci > 0) { preskoci--; continue }
                    if (izhod.size >= najvec) { konec = false; break }
                    izhod += skladba(x, v.naslov)
                }
            }
        } catch (_: Exception) { }
        v.dal += izhod.size
        v.konec = konec
        return izhod
    }

    /** Vsi kanali seznama po vrsti (za pregled mrtvih, [PregledKanalov]); [obVnosu] vrne false = ustavi. Klic iz ozadja. */
    fun zaVsakega(c: Context, naslov: String, obVnosu: (Jamendo.Skladba) -> Boolean) {
        val f = datoteka(c, naslov)
        if (!f.exists()) return
        try { f.bufferedReader().useLines { vrstice -> for (l in vrstice) { val x = IptvPravila.izVrstice(l) ?: continue; if (!obVnosu(skladba(x, naslov))) break } } }
        catch (_: Exception) { }
    }

    /** Kartica kanala: logo, ime brez tehnicnih pripon, tok (HLS), zvrst in jezik; [Jamendo.Skladba.streznik] = seznam, iz katerega je. */
    fun skladba(v: IptvPravila.Vnos, seznam: String) = Jamendo.Skladba(PREDPONA + v.url, KanaliPravila.imeKanala(v.ime), "", v.logo, v.url, v.url,
        video = true, mime = if (v.url.substringBefore('?').lowercase().endsWith(".m3u8")) MedijskiViri.MIME_HLS else "",
        streznik = seznam, genres = listOfNotNull(v.zvrst.takeIf { it.isNotEmpty() }), language = v.jezik)

    /** Seznam je odstranjen: datoteki izgineta. */
    fun pozabi(c: Context, naslov: String) {
        podatki.remove(naslov)
        try { datoteka(c, naslov).delete(); opis(c, naslov).delete() } catch (_: Exception) { }
    }

    /** Vir seznama v mrezi TV v zivo: izbira mreze, domaci ali ostali, koliko kanalov je ze dal. */
    class Vir(val naslov: String, val domaci: Boolean, val zvrst: String, val jezik: String) {
        @Volatile var dal = 0
        @Volatile var konec = false
    }
}
