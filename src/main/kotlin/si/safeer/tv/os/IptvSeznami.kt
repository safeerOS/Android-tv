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

    // Pripona -j2: od 23. 10. 2026 ima kanal brez tvg-language jezik drzave - stare razclenitve (brez jezika) se ne berejo vec,
    // seznam se prenese znova, [obdrzi] stare datoteke pospravi.
    private fun ime(naslov: String) = KanaliPravila.odtisDodatka(naslov) + "-j2"
    private fun mapa(c: Context) = File(c.applicationContext.filesDir, MAPA)
    /** Naloga 87: mapa seznamov na USB, ce je predpomnilnik na USB v uporabi (iz ozadja; z glavne niti null). */
    private fun mapaUsb(c: Context): File? = UsbPredpomnilnik.mapa(c, PredpomnilnikPravila.IPTV)
    /** Mapa, kjer seznam ta hip je: notranja, sicer USB; ce ga ni nikjer, notranja. */
    private fun mapaSeznama(c: Context, naslov: String): File =
        listOfNotNull(mapa(c), mapaUsb(c)).firstOrNull { File(it, ime(naslov) + ".tsv").exists() } ?: mapa(c)
    private fun datoteka(c: Context, naslov: String) = File(mapaSeznama(c, naslov), ime(naslov) + ".tsv")
    private fun opis(c: Context, naslov: String) = File(mapaSeznama(c, naslov), ime(naslov) + ".info")

    /** Podatki seznama z diska (brez omrezja); null = se ni prenesen. */
    fun podatki(c: Context, naslov: String): Podatki? {
        podatki[naslov]?.let { p ->
            // Naloga 87: seznam na USB, ki je bil odklopljen, ni vec na voljo - prenese se znova (na notranji). Na glavni niti
            // (kartica v Mojih virih) diska ne preverjamo.
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper() || datoteka(c, naslov).exists()) return p
            podatki.remove(naslov)
        }
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
                // Naloga 87: z USB v uporabi gre seznam (do 20 MB) na USB - notranji pomnilnik TV ostane prost.
                val m = (mapaUsb(c) ?: mapa(c)).apply { mkdirs() }
                val zacasna = File(m, ime(naslov) + ".tmp")
                val bralnik = IptvPravila.Bralnik(naslov, drzava, jezik)
                val zvrsti = HashSet<String>(); val jeziki = HashSet<String>()
                zacasna.bufferedWriter().use { w ->
                    p.inputStream.bufferedReader().use { r ->
                        // Naloga 86 (M7): vrstica najvec [IptvPravila.NAJVEC_VRSTICA] znakov (daljsa se preskoci, ne bere v pomnilnik),
                        // skupaj najvec [NAJVEC_BAJTOV] - prej je readLine() prebral celo vrstico brez preloma (OOM).
                        IptvPravila.vrstice(r, IptvPravila.NAJVEC_VRSTICA, NAJVEC_BAJTOV) { l ->
                            val v = bralnik.vrstica(l) ?: return@vrstice
                            if (v.zvrst.isNotEmpty()) zvrsti += v.zvrst
                            if (v.jezik.isNotEmpty()) jeziki += v.jezik
                            w.write(IptvPravila.vVrstico(v)); w.newLine()
                        }
                    }
                }
                val podatkiNovi = Podatki(System.currentTimeMillis(), bralnik.kanalov, zvrsti, jeziki)
                val cilj = File(m, ime(naslov) + ".tsv")
                if (!zacasna.renameTo(cilj)) { cilj.delete(); if (!zacasna.renameTo(cilj)) { zacasna.delete(); return podatki(c, naslov) } }
                File(m, ime(naslov) + ".info").writeText("${podatkiNovi.prenesenOb}\n${podatkiNovi.kanalov}\n${zvrsti.joinToString(",")}\n${jeziki.joinToString(",")}")
                // Stara kopija na drugem mestu (notranji <-> USB) ni vec potrebna.
                for (drug in listOfNotNull(mapa(c), mapaUsb(c)).filter { it.path != m.path })
                    for (pr in listOf(".tsv", ".info")) try { File(drug, ime(naslov) + pr).delete() } catch (_: Throwable) { }
                podatki[naslov] = podatkiNovi
                android.util.Log.i("SafeerOsMedia", "seznam IPTV: kanalov=${bralnik.kanalov}, zvrsti=${zvrsti.size}, jeziki=${jeziki.size}, " +
                    "drzava seznama=${drzava.ifEmpty { "-" }}, jezik seznama=${jezik.ifEmpty { "-" }}, po ${android.os.SystemClock.uptimeMillis() - od} ms")
                return podatkiNovi
            } finally { try { p.disconnect() } catch (_: Throwable) { } }
        } catch (_: Exception) {
            // Naloga 87: napaka pisanja na USB (odklop, poln) - do naslednje preverbe notranji.
            if (mapaUsb(c) != null) UsbPredpomnilnik.napaka()
            for (m in listOfNotNull(mapa(c), mapaUsb(c))) try { File(m, ime(naslov) + ".tmp").delete() } catch (_: Throwable) { }
            return podatki(c, naslov)
        } catch (_: OutOfMemoryError) {
            // Naloga 86 (M7): pomnilnika je zmanjkalo (sibak TV) - seznam ostane, kot je bil; proces ne pade.
            for (m in listOfNotNull(mapa(c), mapaUsb(c))) try { File(m, ime(naslov) + ".tmp").delete() } catch (_: Throwable) { }
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
        } catch (_: Exception) {
            // Naloga 87: branje z USB ni uspelo (odklop) - do naslednje preverbe notranji; pripravi() seznam prenese znova.
            if (!f.path.startsWith(mapa(c).path)) UsbPredpomnilnik.napaka()
        }
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
        for (m in listOfNotNull(mapa(c), mapaUsb(c))) for (pr in listOf(".tsv", ".info")) try { File(m, ime(naslov) + pr).delete() } catch (_: Exception) { }
    }

    /** Naloga 87: »Pocisti predpomnilnik« - datoteke izbrise klicatelj, tu pozabimo podatke v pomnilniku. */
    fun pozabiVse() = podatki.clear()

    /**
     * Naloga 86 (M5): obdrzi samo datoteke seznamov [naslovi] (vsi viri SEZNAM uporabnika); ostale (.tsv, .info, .tmp) izbrise.
     * Seznam, ki se prenasa, ostane. Klic iz ozadja.
     */
    fun obdrzi(c: Context, naslovi: Collection<String>) {
        val imena = (naslovi + prenasam).map { ime(it) }.toSet()
        for (f in listOfNotNull(mapa(c), mapaUsb(c)).flatMap { it.listFiles().orEmpty().toList() }) {
            if (f.name.substringBefore('.') in imena) continue
            podatki.keys.filter { ime(it) == f.name.substringBefore('.') }.forEach { podatki.remove(it) }
            try { f.delete() } catch (_: Exception) { }
        }
    }

    /** Vir seznama v mrezi TV v zivo: izbira mreze, domaci ali ostali, koliko kanalov je ze dal. */
    class Vir(val naslov: String, val domaci: Boolean, val zvrst: String, val jezik: String) {
        @Volatile var dal = 0
        @Volatile var konec = false
    }
}
