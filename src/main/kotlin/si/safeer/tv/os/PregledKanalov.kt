package si.safeer.tv.os

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import si.safeer.tv.R

/**
 * Naloga 75 (lastnik, 23. 10. 2026: »ob dodajanju dodatka naj Safeer sam odstrani mrtve tokove«): pregled kanalov dodatka
 * v ozadju. Ko uporabnik doda dodatek s kanali in nato najvec enkrat na dan ([OsPravila.pregledJeNaVrsti]), ko je Safeer
 * v ozadju, preverimo njegove kanale ([preveri]: dodatek ima tok IN streznik toka odgovori) in izid zapisemo v
 * [MrtviKanali] - mreza TV v zivo mrtvih potem sploh ne pokaze.
 *
 * Omejitve (TV je sibak, dodatek steje poizvedbe): niti z nizko prednostjo, [HKRATI] preverjanja hkrati, samo proracun
 * dela na zalogo ([Stremio.smeNaZalogo], tretjina urne meje [TempoDodatka]), dodatek v premoru ali brez odgovora
 * ([BREZ_ODGOVORA_NAJVEC] zaporednih, [Stremio.neOdgovarja]) pregled ustavi. Nikoli med predvajanjem; prekine ga zacetek
 * predvajanja ([GlasbaStoritev]) in pomnilniski pritisk ([OsPravila.pritiskPomnilnika]). Dnevnik: SafeerOsMedia.
 */
object PregledKanalov {
    /** Napredek pregleda dodatka (za kartico v Mojih virih); [poln] = cas zadnjega dokoncanega pregleda, [poskus] = zadnji zacetek. */
    data class Napredek(val preverjenih: Int = 0, val vseh: Int = 0, val mrtvih: Int = 0, val tece: Boolean = false,
                        val poln: Long = 0L, val poskus: Long = 0L) {
        fun vNiz() = "$preverjenih;$vseh;$mrtvih;$poln;$poskus"
        companion object {
            fun izNiza(s: String?): Napredek? {
                val d = s.orEmpty().split(";").map { it.toLongOrNull() ?: return null }
                if (d.size != 5) return null
                return Napredek(d[0].toInt(), d[1].toInt(), d[2].toInt(), false, d[3], d[4])
            }
        }
    }

    private const val PREFS = "safeer_pregled_kanalov"
    private const val HKRATI = 2
    private const val NAJVEC_KANALOV = 3000
    private const val NAJVEC_STRANI = 40
    private const val STRAN_MIN = 20
    private const val BREZ_ODGOVORA_NAJVEC = 5
    /** Obcasni pregled zacne sele cez toliko casa (odhod z zaslona je pogosto zacetek predvajanja). */
    private const val ZAMIK_OBCASNEGA_MS = 30_000L
    /** Po pomnilniskem pritisku toliko casa ne zacnemo znova. */
    private const val PO_PRITISKU_MS = 30 * 60_000L
    private const val OBVESTI_NAJVEC_MS = 5_000L

    private fun nitVOzadju(ime: String) = ThreadFactory { r ->
        Thread {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST) } catch (_: Throwable) { }
            r.run()
        }.apply { name = ime; isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val voznik = Executors.newSingleThreadExecutor(nitVOzadju("safeer-pregled"))
    private val preverjalci = Executors.newFixedThreadPool(HKRATI, nitVOzadju("safeer-pregled-tok"))

    private val napredek = ConcurrentHashMap<String, Napredek>()
    /** Dodatki, ki cakajo na pregled (naslov -> rocno). */
    private val vrsta = LinkedHashMap<String, Boolean>()
    @Volatile private var tece = false
    @Volatile private var ustavi = ""
    @Volatile private var zadrzanDo = 0L
    @Volatile private var nalozeno = false
    @Volatile private var prijavljen = false
    @Volatile private var obvescenOb = 0L
    /** Zaslon Mojih virov (kartica dodatka kaze napredek) - klic iz niti v ozadju. */
    @Volatile var obSpremembi: (() -> Unit)? = null

    /**
     * Pregled dodatka [naslov]. [rocno] (meni dodatka »Preveri kanale zdaj«): takoj in tudi kanale z znanim izidom; sicer
     * samo, ce je na vrsti ([OsPravila.pregledJeNaVrsti]). Vrne false, ce zdaj ne gre (predvajanje, pomnilnik).
     */
    fun zacni(c: Context, naslov: String, rocno: Boolean): Boolean {
        val app = c.applicationContext
        nalozi(app); prijavi(app)
        if (GlasbaStoritev.igra()) return false
        val zdaj = System.currentTimeMillis()
        if (!rocno) {
            if (android.os.SystemClock.uptimeMillis() < zadrzanDo) return false
            val n = napredek[naslov]
            if (n != null && (n.tece || !OsPravila.pregledJeNaVrsti(n.poln, n.poskus, zdaj))) return false
        }
        synchronized(vrsta) {
            if (rocno || naslov !in vrsta) vrsta[naslov] = rocno || vrsta[naslov] == true
            if (rocno) ustavi = ""
            if (tece) return true
            tece = true
        }
        try { voznik.execute { voziVrsto(app, zamik = !rocno) } } catch (_: java.util.concurrent.RejectedExecutionException) { tece = false; return false }
        return true
    }

    /** Safeer gre v ozadje: dodatki, katerih pregled je na vrsti, pridejo na vrsto (zacne cez [ZAMIK_OBCASNEGA_MS]). */
    fun obcasno(c: Context, naslovi: List<String>) { for (n in naslovi) zacni(c, n, rocno = false) }

    /** Ustavi pregled (zacetek predvajanja, pomnilnik); cakajoci dodatki pridejo na vrsto ob naslednjem [obcasno]. */
    fun ustavi(zakaj: String) {
        synchronized(vrsta) { if (!tece) return; vrsta.clear(); ustavi = zakaj }
        if (zakaj == ZAKAJ_POMNILNIK) zadrzanDo = android.os.SystemClock.uptimeMillis() + PO_PRITISKU_MS
    }

    /** Opis za kartico dodatka v Mojih virih: »Preverjam kanale 340/1200, mrtvih 410« ali izid zadnjega pregleda; null = ga ni bilo. */
    fun opis(c: Context, naslov: String): String? {
        nalozi(c.applicationContext)
        val n = napredek[naslov]?.takeIf { it.vseh > 0 } ?: return null
        return c.getString(if (n.tece) R.string.os_pregled_kanalov_tece else R.string.os_pregled_kanalov_konec, n.preverjenih, n.vseh, n.mrtvih)
    }

    /**
     * Ena preverba kanala (dodatek ima tok IN streznik toka odgovori) z zapisom v [MrtviKanali]: true ziv, false mrtev
     * (dokaz ali [OsPravila.NEGOTOVIH_ZA_MRTEV] zaporednih negotovih izidov), null ne vemo. Druga vrednost: ali je dodatek
     * odgovoril. Klic iz ozadja; uporablja ga tudi mreza TV v zivo.
     */
    fun preveri(c: Context, sk: Jamendo.Skladba): Pair<Boolean?, Boolean> {
        val app = c.applicationContext
        val agent = SpletniVir.agent(app)
        var dodatekOdgovoril = false
        var negotovo = false
        val izid = try {
            Stremio.imaLastenTok(sk) { t ->
                dodatekOdgovoril = true
                when (SondaToka.preveri(t.url, t.glave, agent)) {
                    OsPravila.IzidSonde.ZIV -> true
                    OsPravila.IzidSonde.MRTEV -> false
                    OsPravila.IzidSonde.NEGOTOVO -> { negotovo = true; null }
                    OsPravila.IzidSonde.NEZNANO -> null
                }
            }
        } catch (_: Exception) { null }
        if (izid != null) dodatekOdgovoril = true
        val koncni = when {
            izid == true -> { MrtviKanali.zapomniZivega(app, sk.id); true }
            izid == false -> { MrtviKanali.zapomni(app, sk.id); false }
            negotovo -> if (MrtviKanali.zapomniNegotovega(app, sk.id)) false else null
            else -> null
        }
        return koncni to dodatekOdgovoril
    }

    private const val ZAKAJ_POMNILNIK = "pomnilnik"

    private fun voziVrsto(app: Context, zamik: Boolean) {
        try {
            if (zamik) for (i in 0 until (ZAMIK_OBCASNEGA_MS / 1000).toInt()) {
                if (ustavi.isNotEmpty() || GlasbaStoritev.igra()) return
                Thread.sleep(1000)
            }
            while (true) {
                val (naslov, rocno) = synchronized(vrsta) {
                    val prvi = vrsta.entries.firstOrNull()
                    if (prvi == null || ustavi.isNotEmpty()) return
                    vrsta.remove(prvi.key)
                    prvi.key to prvi.value
                }
                preglej(app, naslov, rocno)
            }
        } catch (_: InterruptedException) {
        } finally {
            // Dodatek, ki pride na vrsto med koncem te voznje, zacne naslednji [zacni].
            synchronized(vrsta) { tece = false; ustavi = "" }
        }
    }

    /** Zakaj pregled zdaj neha ("" = sme naprej). */
    private fun prekinitev(app: Context, naslov: String): String {
        if (ustavi.isNotEmpty()) return ustavi
        if (GlasbaStoritev.igra()) return "predvajanje"
        if (MedijskiViri.vsi(app).none { it.jeStremio && it.naslov == naslov }) return "dodatek odstranjen"
        val omrezje = app.getSystemService(android.net.ConnectivityManager::class.java)
        if (omrezje == null || omrezje.activeNetwork == null || omrezje.isActiveNetworkMetered) return "omrezje"
        if (Stremio.vPremoru(naslov)) return "dodatek v premoru"
        if (Stremio.neOdgovarja(naslov)) return "dodatek ne odgovarja"
        if (!Stremio.smeNaZalogo(listOf(naslov))) return "urni proracun"
        return ""
    }

    private fun preglej(app: Context, naslov: String, rocno: Boolean) {
        val zdaj = System.currentTimeMillis()
        val prej = napredek[naslov]
        var n = Napredek(tece = true, poln = prej?.poln ?: 0L, poskus = zdaj)
        napredek[naslov] = n; shrani(app, naslov, n); obvesti(true)
        var zakaj = ""
        var brezOdgovora = 0
        val videni = HashSet<String>()
        try {
            try { Stremio.manifest(naslov) } catch (_: Exception) { }
            val katalogi = try { Stremio.katalogiRazreda(listOf(naslov), Stremio.TV) } catch (_: Exception) { emptyList() }
            if (katalogi.isEmpty()) { zakaj = "brez kanalov"; return }
            zanka@ for (k in katalogi) {
                var dal = 0
                var strani = 0
                while (strani++ < NAJVEC_STRANI && videni.size < NAJVEC_KANALOV) {
                    zakaj = prekinitev(app, naslov)
                    if (zakaj.isNotEmpty()) break@zanka
                    val stran = try { Stremio.katalogAliIzpad(k, skip = dal) } catch (_: Exception) { null }
                    if (stran == null) { zakaj = "katalog ni odgovoril"; break@zanka }
                    if (stran.isEmpty()) break
                    dal += stran.size
                    // Zasebne kategorije (Stremio.zasebniVnosi) ne sprasujemo; isti kanal v vec katalogih enkrat.
                    val novi = stran.filter { videni.add(it.id) && Stremio.jeEnota(it) && Stremio.dajeLastneTokove(it) && it.id !in Stremio.zasebniVnosi }
                    n = n.copy(vseh = n.vseh + novi.size); napredek[naslov] = n
                    for (par in novi.chunked(HKRATI)) {
                        zakaj = prekinitev(app, naslov)
                        if (zakaj.isNotEmpty()) break@zanka
                        // Brez rocnega »Preveri« kanala z veljavnim izidom ne sprasujemo znova (iztece sam, [MrtviKanali]).
                        val znani = if (rocno) emptyMap() else par.associate { it.id to MrtviKanali.stanje(app, it.id) }.filterValues { it != null }
                        val zahteve = par.filter { it.id !in znani }.map { sk -> preverjalci.submit<Pair<Boolean?, Boolean>> { preveri(app, sk) } }
                        val izidi = zahteve.map { f -> try { f.get(60, TimeUnit.SECONDS) } catch (_: Exception) { f.cancel(true); null to false } }
                        for ((izid, odgovoril) in izidi) { if (odgovoril) brezOdgovora = 0 else brezOdgovora++ }
                        val mrtvih = znani.values.count { it == false } + izidi.count { it.first == false }
                        n = n.copy(preverjenih = n.preverjenih + par.size, mrtvih = n.mrtvih + mrtvih); napredek[naslov] = n
                        obvesti(false)
                        // Circuit breaker pregleda: dodatek zapored ne odgovarja - nehamo (prvi naslednji pregled poskusi znova).
                        if (brezOdgovora >= BREZ_ODGOVORA_NAJVEC) { zakaj = "dodatek ne odgovarja"; break@zanka }
                    }
                    if (!OsPravila.katalogImaVec(stran.size, k.strani, STRAN_MIN)) break
                }
            }
        } finally {
            val konec = zakaj.isEmpty() || zakaj == "brez kanalov"
            n = n.copy(tece = false, poln = if (konec) System.currentTimeMillis() else n.poln)
            napredek[naslov] = n; shrani(app, naslov, n); obvesti(true)
            android.util.Log.i("SafeerOsMedia", "pregled kanalov: dodatek=${android.net.Uri.parse(Stremio.osnova(naslov)).host}, rocno=$rocno, " +
                "kanalov=${n.vseh}, preverjenih=${n.preverjenih}, mrtvih=${n.mrtvih}, ${if (konec) "koncan" else "prekinjen ($zakaj)"} po ${(System.currentTimeMillis() - zdaj) / 1000} s")
        }
    }

    private fun obvesti(takoj: Boolean) {
        val zdaj = android.os.SystemClock.uptimeMillis()
        if (!takoj && zdaj - obvescenOb < OBVESTI_NAJVEC_MS) return
        obvescenOb = zdaj
        try { obSpremembi?.invoke() } catch (_: Throwable) { }
    }

    private fun nalozi(app: Context) {
        if (nalozeno) return
        synchronized(this) {
            if (nalozeno) return
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            for ((k, v) in p.all) Napredek.izNiza(v as? String)?.let { napredek.putIfAbsent(k, it) }
            nalozeno = true
        }
    }

    private fun shrani(app: Context, naslov: String, n: Napredek) {
        try { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(naslov, n.vNiz()).apply() } catch (_: Exception) { }
    }

    /** Pomnilniski pritisk ([OsPravila.pritiskPomnilnika]) ustavi pregled tudi, ko zaslona Safeer ni. */
    private fun prijavi(app: Context) {
        if (prijavljen) return
        synchronized(this) {
            if (prijavljen) return
            app.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onTrimMemory(raven: Int) { if (OsPravila.pritiskPomnilnika(raven)) ustavi(ZAKAJ_POMNILNIK) }
                override fun onConfigurationChanged(c: Configuration) { }
                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override fun onLowMemory() { ustavi(ZAKAJ_POMNILNIK) }
            })
            prijavljen = true
        }
    }
}
