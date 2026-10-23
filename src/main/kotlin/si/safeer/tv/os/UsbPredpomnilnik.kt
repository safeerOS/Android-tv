package si.safeer.tv.os

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.Looper
import java.io.File

/**
 * Naloga 87: predpomnilnik Media Center na odstranljivem pomnilniku (USB, kartica). Mapa aplikacije na zunanji shrambi
 * (getExternalCacheDirs) - brez dovoljenj; ob odstranitvi aplikacije jo sistem pobrise. Odlocitve so v [PredpomnilnikPravila].
 *
 * 57c: metapodatki so v bazi SQLite ([PredpomnilnikBaza]) - [notranja] na TV (majhna) in [usb] (velika, ce je USB v uporabi).
 * Na USB je poleg baze se mapa »iptv« (seznami IPTV do 20 MB so datoteke, beremo jih vrstico za vrstico).
 *
 * - Samo iz ozadja: na glavni niti [mapa] vrne null (USB je lahko pocasen ali pravkar odklopljen - nikoli zastoj zaslona).
 * - Odklop med delom: vsaka napaka branja ali pisanja poklice [napaka] - USB do naslednje preverbe ni vec v uporabi, vse
 *   gre na notranji pomnilnik; ob ponovnem priklopu (sistemsko obvestilo ali po [PredpomnilnikPravila.PREVERBA_MS]) znova.
 * - Meja velikosti in TTL po vrsti, LRU ciscenje v ozadju ([PredpomnilnikPravila.zaBrisanje]).
 */
object UsbPredpomnilnik {
    private const val PREFS = "safeer_predpomnilnik_usb"
    private const val MAPA = "safeer-media"

    /** Kar o USB zdaj vemo (za kartico v Mojih virih brez branja diska na glavni niti). */
    class Stanje(val usb: Boolean, val zasedenoUsb: Long, val mejaUsb: Long, val zasedenoNotranji: Long, val izracunano: Boolean)

    @Volatile var stanje = Stanje(false, 0, 0, 0, false)
        private set
    @Volatile private var koren: File? = null
    @Volatile private var notranjaBaza: PredpomnilnikBaza? = null
    @Volatile private var usbBaza: PredpomnilnikBaza? = null
    /** Priklopljen odstranljiv pomnilnik ne glede na izbrano mesto (za izbiro mesta: »USB (prostih X)«). */
    @Volatile var usbPriklopljen: PredpomnilnikPravila.Kandidat? = null
        private set
    @Volatile private var meja = 0L
    @Volatile private var preverjenOb = 0L
    @Volatile private var zapisanih = 0L
    @Volatile private var ciscenoOb = 0L
    @Volatile private var prijavljen = false
    /** Ob spremembi stanja (kartica v Mojih virih se osvezi) - klic iz ozadja. */
    @Volatile var obSpremembi: (() -> Unit)? = null

    private val delo = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "safeer-usb").apply { isDaemon = true }
    }

    /** Izbrano mesto drugega nivoja (USB samodejno, ta TV, naprava v Linku - [PredpomnilnikPravila.Mesto]). */
    fun mesto(c: Context): PredpomnilnikPravila.Mesto =
        PredpomnilnikPravila.mestoIzNiza(c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mesto", null))

    fun velikostGb(c: Context): Int =
        PredpomnilnikPravila.velikostGb(c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("velikost_gb", PredpomnilnikPravila.PRIVZETA_GB))

    /** Nova nastavitev: velja takoj (naslednja [mapa] preveri znova), stanje se izracuna v ozadju. */
    fun nastavi(c: Context, mesto: PredpomnilnikPravila.Mesto? = null, velikostGb: Int? = null) {
        val e = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        mesto?.let { e.putString("mesto", PredpomnilnikPravila.mestoVNiz(it)) }
        velikostGb?.let { e.putInt("velikost_gb", PredpomnilnikPravila.velikostGb(it)) }
        e.apply()
        preverjenOb = 0L
        osveziStanje(c, ocisti = true)
    }

    /**
     * Podmapa [vrsta] na USB, ce je predpomnilnik na USB zdaj v uporabi; sicer null (notranji). Nikoli z glavne niti.
     */
    fun mapa(c: Context, vrsta: String): File? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val app = c.applicationContext
        prijavi(app)
        val zdaj = android.os.SystemClock.uptimeMillis()
        if (preverjenOb == 0L || zdaj - preverjenOb !in 0 until PredpomnilnikPravila.PREVERBA_MS) preveri(app)
        val k = koren ?: return null
        return try { File(k, vrsta).apply { mkdirs() }.takeIf { it.isDirectory } } catch (_: Throwable) { napaka(); null }
    }

    /** Notranja baza (prvi nivo na disku, ta TV, [PredpomnilnikPravila.NOTRANJA_MEJA]). Klic iz ozadja. */
    fun notranja(c: Context): PredpomnilnikBaza? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        notranjaBaza?.let { return it }
        synchronized(this) {
            return notranjaBaza ?: PredpomnilnikBaza(File(c.applicationContext.cacheDir, "mediji-predpomnilnik.db")).also { notranjaBaza = it }
        }
    }

    /** Baza na USB, ce je predpomnilnik na USB zdaj v uporabi; sicer null. Klic iz ozadja. */
    fun usb(c: Context): PredpomnilnikBaza? {
        val k = mapa(c, "")?.let { koren } ?: return null
        val pot = File(k, "predpomnilnik.db").path
        usbBaza?.takeIf { it.pot == pot }?.let { return it }
        synchronized(this) {
            usbBaza?.takeIf { it.pot == pot }?.let { return it }
            usbBaza?.zapri()
            return PredpomnilnikBaza(File(pot), ::napaka).also { usbBaza = it }
        }
    }

    /** Naloga 89: meja predpomnilnika na USB (bajti), 0 = USB ni v uporabi. */
    fun mejaUsb(): Long = if (koren != null) meja else 0L

    /** Branje ali pisanje na USB ni uspelo (odklop, poln, napaka datotecnega sistema): do naslednje preverbe samo notranji. */
    fun napaka() {
        if (koren != null) android.util.Log.i("SafeerOsMedia", "predpomnilnik USB: napaka, do naslednje preverbe notranji")
        koren = null
        preverjenOb = android.os.SystemClock.uptimeMillis()
        usbBaza?.zapri()
        // Naloga 89: predvajalnik bere skladbe naprej z omrezja (CacheDataSource prezre napake predpomnilnika).
        PredpomnilnikZvoka.sprosti()
    }

    /** Zapisanih [bajtov] (notranja baza ali USB): ob dvajsetini meje ali na 10 min ciscenje v ozadju (TTL, nato LRU do meje). */
    fun zapisano(c: Context, bajtov: Long) {
        zapisanih += bajtov
        if (PredpomnilnikPravila.jeCasZaCiscenje(zapisanih, ciscenoOb, System.currentTimeMillis(), maxOf(meja, PredpomnilnikPravila.NOTRANJA_MEJA))) {
            zapisanih = 0
            ciscenoOb = System.currentTimeMillis()
            osveziStanje(c, ocisti = true)
        }
    }

    /** Zasedenost in (po zelji) ciscenje v ozadju; [obSpremembi] na koncu. */
    fun osveziStanje(c: Context, ocisti: Boolean = false) {
        val app = c.applicationContext
        try { delo.execute {
            try {
                preveri(app)
                // Naloga 89: predpomnilnik zvoka sledi mestu (USB priklopljen, odklopljen, druga meja).
                PredpomnilnikZvoka.uskladi(app)
                val k = koren
                if (ocisti) {
                    if (k != null) pocistiStare(app, k)
                    notranja(app)?.pocisti(PredpomnilnikPravila.NOTRANJA_MEJA)
                    pocistiStareDatoteke(app)
                }
                val zasedenoUsb = k?.let { velikost(it) } ?: 0L
                stanje = Stanje(k != null, zasedenoUsb, if (k != null) meja else 0L, notranjeMape(app).sumOf { velikost(it) }, true)
            } catch (_: Throwable) { napaka() }
            try { obSpremembi?.invoke() } catch (_: Throwable) { }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    /** »Pocisti predpomnilnik«: USB in notranje mape Media Center (police, katalogi, naslovnice, seznami IPTV). V ozadju. */
    fun pocisti(c: Context, koncano: () -> Unit) {
        val app = c.applicationContext
        try { delo.execute {
            // Bazi izpraznimo (VACUUM skrci datoteko), stare datoteke in seznami IPTV gredo.
            try { usb(app)?.izprazni() } catch (_: Throwable) { napaka() }
            PredpomnilnikZvoka.izprazni(app)
            try { notranja(app)?.izprazni() } catch (_: Throwable) { }
            try { koren?.let { k -> File(k, PredpomnilnikPravila.IPTV).deleteRecursively() } } catch (_: Throwable) { napaka() }
            for (m in stareNotranjeMape(app) + File(app.filesDir, "iptv_seznami")) try { m.listFiles().orEmpty().forEach { it.deleteRecursively() } } catch (_: Throwable) { }
            IptvSeznami.pozabiVse()
            android.util.Log.i("SafeerOsMedia", "predpomnilnik: pociscen (USB in notranji)")
            stanje = Stanje(koren != null, 0, if (koren != null) meja else 0L, 0, true)
            try { obSpremembi?.invoke() } catch (_: Throwable) { }
            koncano()
        } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    // ---------------------------------------------------------------- notranje

    /** Mape datotek do naloge 87 (beremo jih se, pisemo v bazo; pocisti jih TTL): mapa -> vrsta. */
    private fun stareNotranjeMape(c: Context): List<File> = listOf(
        File(c.cacheDir, "mediji-police-2"), File(c.cacheDir, "mediji-katalogi"), File(c.cacheDir, "mediji-naslovnice"))
    private val VRSTA_STARE = mapOf("mediji-police-2" to PredpomnilnikPravila.POLICE, "mediji-katalogi" to PredpomnilnikPravila.KATALOGI,
        "mediji-naslovnice" to PredpomnilnikPravila.NASLOVNICE)

    /** Notranje datoteke in baza predpomnilnika Media Center (za zasedenost). */
    private fun notranjeMape(c: Context): List<File> = stareNotranjeMape(c) + File(c.filesDir, "iptv_seznami") +
        listOf("", "-journal", "-velike").map { File(c.cacheDir, "mediji-predpomnilnik.db$it") }

    /** 57c: stare datoteke (pred bazo) po TTL svoje vrste; prazne mape izginejo. */
    private fun pocistiStareDatoteke(c: Context) {
        val zdaj = System.currentTimeMillis()
        for (m in stareNotranjeMape(c)) {
            val vrsta = VRSTA_STARE[m.name] ?: continue
            val d = m.listFiles().orEmpty().filter { it.isFile }.map { PredpomnilnikPravila.Datoteka(it.path, vrsta, it.length(), it.lastModified()) }
            for (p in PredpomnilnikPravila.zaBrisanje(d, Long.MAX_VALUE, zdaj)) try { File(p).delete() } catch (_: Throwable) { }
            if (m.listFiles().isNullOrEmpty()) try { m.delete() } catch (_: Throwable) { }
        }
    }

    @Synchronized private fun preveri(app: Context) {
        preverjenOb = android.os.SystemClock.uptimeMillis()
        val prej = koren
        val kandidati = try {
            app.externalCacheDirs.orEmpty().filterNotNull().map { d ->
                val odstranljiv = try { Environment.isExternalStorageRemovable(d) } catch (_: Throwable) { false }
                val priklopljen = try { Environment.getExternalStorageState(d) == Environment.MEDIA_MOUNTED } catch (_: Throwable) { false }
                val nas = File(d, MAPA)
                PredpomnilnikPravila.Kandidat(d.path, odstranljiv, priklopljen,
                    if (priklopljen) (try { d.usableSpace } catch (_: Throwable) { 0L }) else 0L,
                    if (priklopljen && odstranljiv && prej?.path == nas.path) stanje.zasedenoUsb else 0L)
            }
        } catch (_: Throwable) { emptyList() }
        val izbran = PredpomnilnikPravila.izberi(mesto(app).nacin, kandidati)
        usbPriklopljen = kandidati.firstOrNull { it.odstranljiv && it.priklopljen }
        val nov = izbran?.let { File(it.pot, MAPA) }?.takeIf { try { it.mkdirs() || it.isDirectory } catch (_: Throwable) { false } }
        meja = izbran?.let { PredpomnilnikPravila.meja(velikostGb(app), it) } ?: 0L
        koren = nov
        // Naloga 89: USB priklopljen ali odklopljen - predpomnilnik zvoka sledi (na svoji niti).
        if (prej?.path != nov?.path) PredpomnilnikZvoka.uskladi(app)
        if (prej?.path != nov?.path) android.util.Log.i("SafeerOsMedia", "predpomnilnik: " +
            (if (nov != null) "USB (meja ${meja / PredpomnilnikPravila.MB} MB, prostih ${(izbran?.prostih ?: 0L) / PredpomnilnikPravila.MB} MB)" else "notranji") +
            ", mesto=${mesto(app).nacin}, odstranljivih priklopljenih=${kandidati.count { it.odstranljiv && it.priklopljen }}")
    }

    /** Sistemska obvestila o priklopu in odklopu: naslednja [mapa] preveri takoj. */
    private fun prijavi(app: Context) {
        if (prijavljen) return
        synchronized(this) {
            if (prijavljen) return
            prijavljen = true
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED); addAction(Intent.ACTION_MEDIA_EJECT)
                addAction(Intent.ACTION_MEDIA_REMOVED); addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addDataScheme("file")
            }
            val sprejemnik = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    // Odklop: takoj na notranji (pisanje na izginjajoco mapo bi samo cakalo na napake).
                    if (i.action != Intent.ACTION_MEDIA_MOUNTED) napaka()
                    preverjenOb = 0L
                    osveziStanje(app)
                }
            }
            try {
                if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(sprejemnik, filter, Context.RECEIVER_NOT_EXPORTED)
                else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(sprejemnik, filter)
            } catch (_: Throwable) { prijavljen = false }
        }
    }

    /**
     * USB: seznami IPTV (datoteke) po TTL in LRU do polovice meje, baza do preostanka meje (TTL, LRU). Klic iz ozadja.
     */
    private fun pocistiStare(app: Context, k: File) {
        val iptv = File(k, PredpomnilnikPravila.IPTV)
        val d = iptv.listFiles().orEmpty().filter { it.isFile }.map { PredpomnilnikPravila.Datoteka(it.path, PredpomnilnikPravila.IPTV, it.length(), it.lastModified()) }
        // Naloga 89: meja USB je razdeljena (seznami IPTV, zvok, baza); zvok cisti SimpleCache sam (LRU), baza dobi ostanek.
        val delitev = PredpomnilnikPravila.delitevUsb(meja)
        val ven = PredpomnilnikPravila.zaBrisanje(d, delitev.iptv, System.currentTimeMillis())
        for (p in ven) try { File(p).delete() } catch (_: Throwable) { }
        if (ven.isNotEmpty()) android.util.Log.i("SafeerOsMedia", "predpomnilnik USB: pocisceno ${ven.size} datotek seznamov od ${d.size}")
        val zvok = velikost(File(k, PredpomnilnikPravila.ZVOK))
        usb(app)?.pocisti((meja - velikost(iptv) - zvok).coerceAtLeast(delitev.baza))
    }

    private fun velikost(m: File): Long = try { m.walkTopDown().filter { it.isFile }.sumOf { it.length() } } catch (_: Throwable) { 0L }
}
