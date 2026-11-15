package si.safeer.tv

import android.content.Context
import android.os.Environment
import android.system.Os
import si.safeer.tv.os.BrskalnikDiskPravila as B
import si.safeer.tv.os.PredpomnilnikPravila
import si.safeer.tv.os.UsbPredpomnilnik
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

/**
 * Predpomnilnik spletnih strani (Chromium WebView) na USB disku, ce ga uporabnik ima (lastnik 15. 11. 2026: »ce uporabnik kot jaz
 * uporabi USB trdi disk«); brez diska nic ne spremenimo. Notranji pomnilnik TV je poln (77 %, 1,1 GB prostega), disk pa
 * je ze izmerjen (pisljiv in dovolj hiter, [UsbPredpomnilnik]) in izbran v nastavitvah (Samodejno / Ta TV - ista nastavitev kot
 * pri Medijskem centru; »Ta TV« vrne predpomnilnik v notranji pomnilnik).
 *
 * Kako: samo mapa HTTP predpomnilnika (`cacheDir/WebView/Default/HTTP Cache`, [B.HTTP_PODMAPA]) postane simbolna povezava na
 * `<USB>/Android/data/<paket>/cache/brskalnik-http`; Code Cache in GPUCache tam zasedeta prazni datoteki ([B.ZAPORNICE]).
 * Popravki XX3-XX8 (15. 11. 2026):
 * - vse delo z diskom tece v ozadju; glavna nit caka najvec 300 ms, nato WebView zacne z notranjim predpomnilnikom (XX4);
 * - en priklop na proces - povezave ne menjamo pod zivim WebViewom (XX6);
 * - mapa na disku se ob vsakem zagonu izprazni pred uporabo (predpomnilnik samo za sejo: vnosi, ki jih je kdo podtaknil na
 *   drugem racunalniku, se ne uporabijo - XX3), ob "Ta TV" ali odklopu pa ob naslednjem zagonu z dosegljivim diskom (XX7);
 * - brisanje nikoli ne sledi simbolnim povezavam (XX6);
 * - neuspel poskus velja 24 h in je neuspel samo ob sesutju procesa, ne ob zagonu brez nalozene strani (XX5);
 * - disk samo, ce mali nakljucni dostop ni vec kot 2x pocasnejsi od notranjega pomnilnika (XX8).
 * NEPREVERJENO na napravi: pot podmap Chromiuma in ali WebView sledi povezavi.
 */
object BrskalnikPredpomnilnik {
    private const val PREFS = "safeer_brskalnik_predpomnilnik"
    private const val STANJE = "stanje"            // B.zapisi: "<usbPot>|poskus|<ms>" | "...|uspel|<ms>" | "...|neuspel|<ms>"
    private const val ZA_PRAZNJENJE = "za_praznjenje" // mape na disku (\n), nedosegljive, ko bi jih morali izprazniti
    private val enkrat = B.Enkrat()
    @Volatile private var potrjeno = false
    /** Mapa na disku, ki jo WebView uporablja v tej seji (null = notranji predpomnilnik). */
    @Volatile private var ciljSeje: String? = null
    /** Cilj prejsnje povezave (prebran, preden smo jo odstranili). */
    @Volatile private var prejsnjaTarca: String? = null

    private val ozadje = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "safeer-brskalnik-disk").apply { isDaemon = true }
    }

    /**
     * Pred prvim WebView. Klic v onCreate ostane, kot je: delo gre v ozadje, ta klic caka najvec [B.ROK_PRIKLOPA_MS].
     * Drugi klic v istem procesu (ponovno ustvarjena dejavnost) ne naredi nicesar. Ne sme vreci izjeme.
     */
    fun priklopi(c: Context) {
        if (!enkrat.prvic()) return
        try {
            val app = c.applicationContext
            val cache = app.cacheDir
            val priprava = B.Priprava { odstraniStarePovezave(cache) }
            try {
                ozadje.execute { pripravi(app, priprava) }
            } catch (_: Throwable) {
                priprava.koncajBrez()
            }
            if (!priprava.pocakaj()) android.util.Log.i("SafeerBrskalnikCache", "disk ni odgovoril v ${B.ROK_PRIKLOPA_MS} ms: notranji predpomnilnik")
        } catch (t: Throwable) {
            android.util.Log.w("SafeerBrskalnikCache", "priklop predpomnilnika ni uspel: " + t.javaClass.simpleName)
        }
    }

    /**
     * Samo notranji pomnilnik: stara povezava cele mape WebView (pred XX3) in povezava HTTP predpomnilnika se odstranita kot
     * povezavi (lstat, brez sledenja); cilj si zapomnimo za praznjenje. WebView najprej, ker pot pod njim gre sicer skozi disk.
     */
    private fun odstraniStarePovezave(cache: File) {
        for (p in listOf(File(cache, "WebView").toPath(), File(cache, B.HTTP_PODMAPA).toPath())) {
            if (Files.isSymbolicLink(p)) {
                prejsnjaTarca = try { Files.readSymbolicLink(p).toString() } catch (_: Throwable) { prejsnjaTarca }
                B.odstraniPovezavo(p)
            }
        }
    }

    /** V ozadju: vse branje in pisanje diska. */
    private fun pripravi(app: Context, priprava: B.Priprava) {
        try {
            priprava.pocisti()
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val zdaj = System.currentTimeMillis()
            val usbPot = UsbPredpomnilnik.potZaBrskalnik(app)
            val d = usbPot?.let { File(it) }
            val priklopljen = d != null && Environment.getExternalStorageState(d) == Environment.MEDIA_MOUNTED
            val odstranljiv = d != null && try { Environment.isExternalStorageRemovable(d) } catch (_: Throwable) { false }
            val prostih = if (priklopljen) try { d!!.usableSpace } catch (_: Throwable) { 0L } else 0L
            val zapis = B.obZagonu(B.preberi(p.getString(STANJE, null)), usbPot, sesutjeOb(app), zdaj)
            // XX8: brez veljavne meritve malega nakljucnega dostopa (ali s prepocasnim diskom) ostane notranji.
            val hiter = priklopljen && B.nakljucnoDovolj(meritev(p, usbPot!!, zdaj), meritev(p, "notranji", zdaj))
            val cilj = if (!hiter) null
                else PredpomnilnikPravila.brskalnikCilj(UsbPredpomnilnik.mesto(app).nacin, usbPot, priklopljen, odstranljiv, prostih, B.prejsnji(zapis, usbPot, zdaj))
            // XX3/XX7: mape na disku izpraznimo pred uporabo; nedosegljivo si zapomnimo za naslednji zagon.
            val zapomnjene = ArrayList<String>()
            val prej = p.getString(ZA_PRAZNJENJE, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
            for (m in B.zaPraznjenje(prejsnjaTarca, prej, cilj)) {
                val mp = Paths.get(m)
                val dosegljiva = try { Environment.getExternalStorageState(File(m)) == Environment.MEDIA_MOUNTED } catch (_: Throwable) { false }
                when {
                    !dosegljiva -> zapomnjene.add(m)
                    Files.isDirectory(mp, LinkOption.NOFOLLOW_LINKS) && !B.pocistiVsebino(mp) -> zapomnjene.add(m)
                }
            }
            val e = p.edit()
            if (zapomnjene.isNotEmpty()) e.putString(ZA_PRAZNJENJE, zapomnjene.joinToString("\n")) else e.remove(ZA_PRAZNJENJE)
            var nov = zapis
            var namescen = false
            if (cilj != null && usbPot != null) {
                val mapa = File(cilj)
                // XX3: nobena sestavina od mape aplikacije na disku do cilja ni povezava (mkdirs bi ji sledil).
                val varna = B.brezPovezav(Paths.get(usbPot), mapa.toPath()) && (mapa.isDirectory || mapa.mkdirs()) &&
                    B.brezPovezav(Paths.get(usbPot), mapa.toPath()) && mapa.canWrite() && B.nastaviZapornice(mapa.toPath())
                if (varna) {
                    val povezava = File(app.cacheDir, B.HTTP_PODMAPA)
                    namescen = priprava.namesti {
                        povezava.parentFile?.mkdirs()
                        // Notranji HTTP predpomnilnik (prava mapa) se umakne z enim preimenovanjem; zbrise se spodaj, v ozadju.
                        if (Files.exists(povezava.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                            Files.move(povezava.toPath(), povezava.toPath().resolveSibling("HTTP Cache.staro-$zdaj"))
                        }
                        Os.symlink(cilj, povezava.path)
                    }
                    if (namescen) {
                        ciljSeje = cilj
                        if (nov?.pot != usbPot || nov.izid != B.Izid.USPEL) nov = B.Zapis(usbPot, B.Izid.POSKUS, zdaj)
                        android.util.Log.i("SafeerBrskalnikCache", "predpomnilnik brskalnika: USB ($cilj), samo HTTP, izpraznjen")
                    }
                }
            }
            if (!namescen) {
                priprava.koncajBrez()
                android.util.Log.i("SafeerBrskalnikCache", "predpomnilnik brskalnika: notranji")
            }
            if (nov != null) e.putString(STANJE, B.zapisi(nov))
            e.commit()
            // Umaknjen notranji predpomnilnik (prava mapa, brez sledenja povezavam).
            File(app.cacheDir, B.HTTP_PODMAPA).parentFile?.listFiles()?.filter { it.name.startsWith("HTTP Cache.staro-") }?.forEach {
                if (B.pocistiVsebino(it.toPath())) Files.deleteIfExists(it.toPath())
            }
            // XX8: meritev za naslednji zagon (samo ko manjka ali je zastarela; nikoli pred odlocitvijo zgoraj).
            if (priklopljen && usbPot != null) izmeri(app, p, usbPot, zdaj)
        } catch (t: Throwable) {
            priprava.koncajBrez()
            android.util.Log.w("SafeerBrskalnikCache", "priprava predpomnilnika ni uspela: " + t.javaClass.simpleName)
        }
    }

    /** Mediana malega dostopa (ns) iz prefs, ce je meritev veljavna. */
    private fun meritev(p: android.content.SharedPreferences, kljuc: String, zdaj: Long): Long? =
        if (B.nakljucnoVelja(p.getLong("nakl_ob:$kljuc", 0L), zdaj)) p.getLong("nakl:$kljuc", 0L).takeIf { it > 0 } else null

    private fun izmeri(app: Context, p: android.content.SharedPreferences, usbPot: String, zdaj: Long) {
        val e = p.edit()
        for ((kljuc, mapa) in listOf("notranji" to app.cacheDir, usbPot to File(usbPot))) {
            if (meritev(p, kljuc, zdaj) != null) continue
            val ns = B.meriNakljucno(mapa)
            // Neuspela meritev (rok, napaka) se zapise kot zelo pocasna: disk ne gre v uporabo do naslednje meritve.
            e.putLong("nakl:$kljuc", ns ?: Long.MAX_VALUE / 4).putLong("nakl_ob:$kljuc", zdaj)
            android.util.Log.i("SafeerBrskalnikCache", "nakljucni dostop $kljuc: " + (ns?.let { "${it / 1000} us" } ?: "ni uspel"))
        }
        e.commit()
    }

    /** XX5: cas zadnjega sesutja procesa (ApplicationExitInfo, API 30) ali null. */
    private fun sesutjeOb(app: Context): Long? = try {
        if (android.os.Build.VERSION.SDK_INT < 30) null
        else app.getSystemService(android.app.ActivityManager::class.java)
            ?.getHistoricalProcessExitReasons(app.packageName, 0, 1)?.firstOrNull()
            ?.takeIf {
                it.reason == android.app.ApplicationExitInfo.REASON_CRASH || it.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    it.reason == android.app.ApplicationExitInfo.REASON_ANR
            }?.timestamp
    } catch (_: Throwable) {
        null
    }

    /**
     * Brskalnik je nalozil stran. Poskus z diskom je uspel sele, ko je HTTP predpomnilnik na disku res kaj zapisal (XX5). Branje
     * diska v ozadju (klic je z glavne niti); [potrjeno] sele po uspesnem zapisu.
     */
    fun potrdi(c: Context) {
        if (potrjeno) return
        val cilj = ciljSeje ?: return
        val app = c.applicationContext
        try {
            ozadje.execute {
                if (potrjeno) return@execute
                try {
                    val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val z = B.preberi(p.getString(STANJE, null))
                    when {
                        z?.izid == B.Izid.USPEL -> potrjeno = true
                        B.smemPotrditi(z, cilj, B.imaVsebino(Paths.get(cilj))) ->
                            if (p.edit().putString(STANJE, B.zapisi(z!!.copy(izid = B.Izid.USPEL, cas = System.currentTimeMillis()))).commit()) potrjeno = true
                    }
                } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
    }
}
