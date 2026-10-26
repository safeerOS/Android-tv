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
    /** Lastnik, 26. 10. 2026: odtisi dodatkov, ki so dali tok z racunom (Xtream ...) - njihovih kanalov v ozadju ne odpremo. */
    private const val PREFS_VAROVALO = "safeer_pregled_kanalov_varovalo"
    private const val K_DODATKI_S_POVERILNICO = "dodatki_s_poverilnico"
    /** Stevci vrat preverb po gostitelju ([IptvPravila.VrataGostiteljev]) - dnevna meja velja tudi po ponovnem zagonu. */
    private const val K_VRATA = "odprtja_gostiteljev"
    /** Najdlje toliko preverba caka na prost gostitelj (druga preverba, premor); nato kanal ostane neznan. Krajse od 30 s. */
    private const val CAKAJ_GOSTITELJA_MS = 10_000L
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
    /** Naloga 80: seznam IPTV nima dodatkovega proracuna - najvec toliko sond na en pregled (naslednji pregled cez uro nadaljuje). */
    private const val SEZNAM_NA_POGLED = 300

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
    /** Odtisi dodatkov s tokovi z racunom ([PREFS_VAROVALO]); null = se ni prebrano. */
    @Volatile private var sPoverilnico: Set<String>? = null

    /**
     * Lastnik, 26. 10. 2026 (»Nevarnost zaklepa racuna je prioriteta«): ali se kanali vira smejo pregledati - seznam samo
     * javni brezplacni, dodatek samo brez poverilnice ([IptvPravila.zakajNePregleda]) in brez tokov z racunom.
     */
    fun smeVir(c: Context, naslov: String, jeSeznam: Boolean): Boolean = zakajNe(c, naslov, jeSeznam, rocno = true).isEmpty()

    /**
     * [IptvPravila.zakajNeVOzadju] (poverilnica, znani javni seznam, vir z druge naprave) in dodatek, ki je dal tok z racunom.
     * V krogu naprav v ozadju preverja samo naprava, na kateri je bil vir dodan ([MedijskiViri.dodanTu]).
     */
    private fun zakajNe(c: Context, naslov: String, jeSeznam: Boolean, rocno: Boolean): String =
        IptvPravila.zakajNeVOzadju(jeSeznam, naslov, rocno || MedijskiViri.dodanTu(c, naslov), rocno)
            .ifEmpty { if (!jeSeznam && jeDodatekSPoverilnico(c, naslov)) "dodatek s tokovi z racunom" else "" }

    /**
     * Ali sme preverba odpreti tok kanala [sk] (pregled in mreza TV v zivo): kanal seznama samo iz javnega brezplacnega
     * seznama in brez racuna v naslovu toka ([IptvPravila.preverbaDovoljena]); kanal dodatka samo iz dodatka brez
     * poverilnice. Ostali ostanejo vidni - o njih odloci pravo predvajanje.
     */
    fun smeSonda(c: Context, sk: Jamendo.Skladba): Boolean {
        if (IptvSeznami.jeKanal(sk)) return IptvPravila.preverbaDovoljena(sk.streznik, sk.zvok)
        val dodatek = Stremio.razstavi(sk)?.first ?: return false
        return !IptvPravila.dodatekSPoverilnico(dodatek) && !jeDodatekSPoverilnico(c, dodatek)
    }

    private fun dodatkiSPoverilnico(c: Context): Set<String> = sPoverilnico ?: try {
        c.applicationContext.getSharedPreferences(PREFS_VAROVALO, Context.MODE_PRIVATE).getStringSet(K_DODATKI_S_POVERILNICO, null).orEmpty().toSet()
    } catch (_: Exception) { emptySet() }.also { sPoverilnico = it }

    /** Odtis dodatka po osnovi ([Stremio.osnova]): id kanala nosi osnovo, vir v Mojih virih naslov manifesta. */
    private fun odtisDodatka(dodatek: String) = SkrivnostiPravila.odtis(Stremio.osnova(dodatek))

    private fun jeDodatekSPoverilnico(c: Context, dodatek: String) = odtisDodatka(dodatek) in dodatkiSPoverilnico(c)

    /** Dodatek je dal tok z racunom: odslej njegovih kanalov v ozadju ne odpremo (zapis preklice samo odstranitev dodatka). */
    @Synchronized private fun zapomniDodatekSPoverilnico(c: Context, dodatek: String) {
        val novo = dodatkiSPoverilnico(c) + odtisDodatka(dodatek)
        sPoverilnico = novo
        try { c.applicationContext.getSharedPreferences(PREFS_VAROVALO, Context.MODE_PRIVATE).edit().putStringSet(K_DODATKI_S_POVERILNICO, novo).apply() } catch (_: Exception) { }
        android.util.Log.i("SafeerOsMedia", "pregled kanalov: dodatek je dal tok z racunom - njegovih kanalov v ozadju ne odpiramo vec")
    }

    /**
     * Pregled dodatka [naslov]. [rocno] (meni dodatka »Preveri kanale zdaj«): takoj in tudi kanale z znanim izidom; sicer
     * samo, ce je na vrsti ([OsPravila.pregledJeNaVrsti]). Vrne false, ce zdaj ne gre (predvajanje, pomnilnik).
     */
    fun zacni(c: Context, naslov: String, rocno: Boolean, jeSeznam: Boolean): Boolean {
        val app = c.applicationContext
        nalozi(app); prijavi(app)
        // Lastnik, 26. 10. 2026: vir s poverilnico (Xtream, uporabnik:geslo@, dodatek z zetonom) nikoli - tudi rocno ne.
        // V krogu naprav v ozadju preverja samo naprava, na kateri je bil vir dodan: odhod v ozadje ali prejem vira z druge
        // naprave na ostalih napravah ne odpre nobenega toka. Rocno »Preveri kanale zdaj« sme (vir brez poverilnice).
        zakajNe(app, naslov, jeSeznam, rocno).let { if (it.isNotEmpty()) { android.util.Log.i("SafeerOsMedia", "pregled kanalov: preskocen ($it)"); return false } }
        if (GlasbaStoritev.igra()) return false
        val zdaj = System.currentTimeMillis()
        if (!rocno) {
            if (android.os.SystemClock.uptimeMillis() < zadrzanDo) return false
            val n = napredek[naslov]
            if (n != null && (n.tece || !OsPravila.pregledJeNaVrsti(n.poln, n.poskus, zdaj))) return false
        }
        // Naloga 79: rocni pregled preveri dodatek znova (po ZAKLENJEN_PO kanalih samo z obvestili se spet ustavi).
        if (rocno) Stremio.pozabiZaklep(naslov)
        synchronized(vrsta) {
            if (rocno || naslov !in vrsta) vrsta[naslov] = rocno || vrsta[naslov] == true
            if (rocno) ustavi = ""
            if (tece) return true
            tece = true
        }
        try { voznik.execute { voziVrsto(app, zamik = !rocno) } } catch (_: java.util.concurrent.RejectedExecutionException) { tece = false; return false }
        return true
    }

    /** Safeer gre v ozadje: dodatki in seznami, katerih pregled je na vrsti, pridejo na vrsto (zacne cez [ZAMIK_OBCASNEGA_MS]). */
    fun obcasno(c: Context, dodatki: List<String>, seznami: List<String>) {
        for (n in dodatki) zacni(c, n, rocno = false, jeSeznam = false)
        for (n in seznami) zacni(c, n, rocno = false, jeSeznam = true)
    }

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
        // Lastnik, 26. 10. 2026: tok vira z racunom se v ozadju ne odpre nikoli ([smeSonda]); kanal ostane viden.
        if (!smeSonda(app, sk)) return null to true
        // Lastnik, 26. 10. 2026: med predvajanjem preverba stoji (racun z 1-2 povezavama, omrezje je za predvajanje).
        if (GlasbaStoritev.igra()) return null to true
        // Naloga 80: kanal seznama IPTV nima dodatka - vprasamo samo streznik toka (skozi vrata gostitelja).
        if (IptvSeznami.jeKanal(sk)) {
            val (izidS, razlog) = skoziVrata(app, sk.zvok) { SondaToka.preveriZRazlogom(sk.zvok, emptyMap(), SpletniVir.agent(app)) }
                ?: return null to true
            // Naloga 93 + 96: kanal seznama, ki ga sonda skrije, v dnevnik z razlogom (ime, shema; brez naslova - ima lahko geslo).
            fun mrtev(zakaj: String) = android.util.Log.i("SafeerOsMedia",
                "sonda IPTV: mrtev \"${sk.naslov.take(40)}\", shema=${sk.zvok.substringBefore(':')}, razlog: $zakaj")
            val k = when (izidS) {
                OsPravila.IzidSonde.ZIV -> { MrtviKanali.zapomniZivega(app, sk.id); true }
                // Naloga 108: razlog sonde in ime kanala za izbiro »Ne delujejo«.
                OsPravila.IzidSonde.MRTEV -> { mrtev(razlog); MrtviKanali.zapomni(app, sk.id, NedelujocePravila.izSonde(razlog), sk.naslov); false }
                OsPravila.IzidSonde.NEGOTOVO -> if (MrtviKanali.zapomniNegotovega(app, sk.id, NedelujocePravila.izSonde(razlog), sk.naslov)) { mrtev("$razlog, ${OsPravila.NEGOTOVIH_ZA_MRTEV}x zapored negotovo"); false } else null
                OsPravila.IzidSonde.NEZNANO -> null
            }
            return k to true
        }
        val agent = SpletniVir.agent(app)
        var dodatekOdgovoril = false
        var negotovo = false
        var razlogSonde = ""
        var tokZRacunom = false
        val izid = try {
            Stremio.imaLastenTok(sk) { t ->
                dodatekOdgovoril = true
                // Lastnik, 26. 10. 2026: dodatek brez vidne poverilnice da tok z racunom (Xtream /live/<u>/<g>/ ...) - toka ne
                // odpremo, dodatka v ozadju ne sprasujemo vec.
                if (tokZRacunom || IptvPravila.jeVirSPoverilnico(t.url, seznam = false)) { tokZRacunom = true; return@imaLastenTok null }
                val (izidToka, opisSonde) = skoziVrata(app, t.url) { SondaToka.preveriZRazlogom(t.url, t.glave, agent) } ?: return@imaLastenTok null
                razlogSonde = opisSonde
                when (izidToka) {
                    OsPravila.IzidSonde.ZIV -> true
                    OsPravila.IzidSonde.MRTEV -> false
                    OsPravila.IzidSonde.NEGOTOVO -> { negotovo = true; null }
                    OsPravila.IzidSonde.NEZNANO -> null
                }
            }
        } catch (_: Exception) { null }
        if (tokZRacunom) { Stremio.razstavi(sk)?.first?.let { zapomniDodatekSPoverilnico(app, it) }; return null to true }
        if (izid != null) dodatekOdgovoril = true
        val koncni = when {
            izid == true -> { MrtviKanali.zapomniZivega(app, sk.id); true }
            // Naloga 108: razlog sonde toka, sicer dodatek toka nima.
            izid == false -> { MrtviKanali.zapomni(app, sk.id, NedelujocePravila.izSonde(razlogSonde).ifEmpty { NedelujocePravila.NI_TOKA }, sk.naslov); false }
            negotovo -> if (MrtviKanali.zapomniNegotovega(app, sk.id, NedelujocePravila.izSonde(razlogSonde), sk.naslov)) false else null
            else -> null
        }
        return koncni to dodatekOdgovoril
    }

    private const val ZAKAJ_POMNILNIK = "pomnilnik"

    /** Lastnik, 26. 10. 2026: vrata preverb vseh poti te naprave (pregled v ozadju in mreza TV v zivo) - kot Linux J3. */
    private val vrata = IptvPravila.VrataGostiteljev()
    @Volatile private var vrataNalozena = false

    /**
     * Sonda toka [naslov] skozi vrata gostitelja ([IptvPravila.VrataGostiteljev]): ena hkrati, [IptvPravila.PREMOR_GOSTITELJA_MS]
     * premora, [IptvPravila.NAJVEC_NA_GOSTITELJA_DAN] odprtij na dan; med predvajanjem nic. null = ta hip ne (predvajanje,
     * dnevna meja, gostitelj dlje od [CAKAJ_GOSTITELJA_MS] zaseden, prekinjena nit) - kanal ostane neznan in viden.
     */
    private fun skoziVrata(app: Context, naslov: String, sonda: () -> Pair<OsPravila.IzidSonde, String>): Pair<OsPravila.IzidSonde, String>? {
        naloziVrata(app)
        val g = IptvPravila.gostitelj(naslov).ifEmpty { return null }
        val odtis = SkrivnostiPravila.odtis(g).take(16)
        val doKdaj = android.os.SystemClock.uptimeMillis() + CAKAJ_GOSTITELJA_MS
        while (true) {
            if (GlasbaStoritev.igra()) return null
            val zdaj = android.os.SystemClock.uptimeMillis()
            when (vrata.zasedi(odtis, System.currentTimeMillis(), zdaj)) {
                IptvPravila.VrataGostiteljev.Izid.DA -> break
                IptvPravila.VrataGostiteljev.Izid.MEJA -> return null
                IptvPravila.VrataGostiteljev.Izid.CAKAJ -> {
                    if (zdaj >= doKdaj) return null
                    try { Thread.sleep(200) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return null }
                }
            }
        }
        try { app.getSharedPreferences(PREFS_VAROVALO, Context.MODE_PRIVATE).edit().putString(K_VRATA, vrata.vNiz()).apply() } catch (_: Exception) { }
        try { return sonda() } finally { vrata.sprosti(odtis, android.os.SystemClock.uptimeMillis()) }
    }

    private fun naloziVrata(app: Context) {
        if (vrataNalozena) return
        synchronized(vrata) {
            if (vrataNalozena) return
            try { vrata.izNiza(app.getSharedPreferences(PREFS_VAROVALO, Context.MODE_PRIVATE).getString(K_VRATA, null), System.currentTimeMillis()) } catch (_: Exception) { }
            vrataNalozena = true
        }
    }

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
        val vir = MedijskiViri.vsi(app).firstOrNull { (it.jeStremio || it.jeSeznam) && it.naslov == naslov } ?: return "dodatek odstranjen"
        // Lastnik, 26. 10. 2026: varovalka tudi tu (vrsta vira iz shrambe, ne od klicatelja) - vir s poverilnico se ne pregleda.
        zakajNe(app, naslov, vir.jeSeznam, rocno = true).let { if (it.isNotEmpty()) return it }
        val omrezje = app.getSystemService(android.net.ConnectivityManager::class.java)
        if (omrezje == null || omrezje.activeNetwork == null || omrezje.isActiveNetworkMetered) return "omrezje"
        // Naloga 80: seznam IPTV - brez dodatka; proracun je lasten ([SEZNAM_NA_POGLED] preverb na pregled).
        if (vir.jeSeznam) return ""
        // Naloga 79: dodatek daje samo obvestila o narocnini - proracuna ne trosimo na vse njegove kanale.
        if (Stremio.brezProstihTokov(naslov)) return "tokovi samo z narocnino"
        if (Stremio.vPremoru(naslov)) return "dodatek v premoru"
        if (Stremio.neOdgovarja(naslov)) return "dodatek ne odgovarja"
        if (!Stremio.smeNaZalogo(listOf(naslov))) return "urni proracun"
        return ""
    }

    private fun preglej(app: Context, naslov: String, rocno: Boolean) {
        // Lastnik, 26. 10. 2026: vir s poverilnico - brez napredka, brez prenosa in brez enega samega odprtja toka.
        val vir = MedijskiViri.vsi(app).firstOrNull { (it.jeStremio || it.jeSeznam) && it.naslov == naslov } ?: return
        zakajNe(app, naslov, vir.jeSeznam, rocno).let { if (it.isNotEmpty()) { android.util.Log.i("SafeerOsMedia", "pregled kanalov: preskocen ($it)"); return } }
        val zdaj = System.currentTimeMillis()
        val prej = napredek[naslov]
        var n = Napredek(tece = true, poln = prej?.poln ?: 0L, poskus = zdaj)
        napredek[naslov] = n; shrani(app, naslov, n); obvesti(true)
        var zakaj = ""
        var brezOdgovora = 0
        val videni = HashSet<String>()
        try {
            // Naloga 80: seznam IPTV (vir SEZNAM) - kanali iz datoteke na disku, samo sonda toka.
            if (MedijskiViri.vsi(app).any { it.jeSeznam && it.naslov == naslov }) {
                IptvSeznami.pripravi(app, naslov)
                var poslanih = 0
                IptvSeznami.zaVsakega(app, naslov) { sk ->
                    zakaj = prekinitev(app, naslov)
                    if (zakaj.isNotEmpty()) return@zaVsakega false
                    n = n.copy(vseh = n.vseh + 1)
                    val znan = if (rocno) null else MrtviKanali.stanje(app, sk.id)
                    val izid = if (znan != null) znan else {
                        if (poslanih++ >= SEZNAM_NA_POGLED) { zakaj = "urni proracun"; return@zaVsakega false }
                        val f = preverjalci.submit<Pair<Boolean?, Boolean>> { preveri(app, sk) }
                        try { f.get(30, TimeUnit.SECONDS).first } catch (_: Exception) { f.cancel(true); null }
                    }
                    n = n.copy(preverjenih = n.preverjenih + 1, mrtvih = n.mrtvih + if (izid == false) 1 else 0)
                    napredek[naslov] = n
                    obvesti(false)
                    true
                }
                return
            }
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
                    // Zasebne kategorije ([ZasebniKanali]) ne sprasujemo; isti kanal v vec katalogih enkrat.
                    val novi = stran.filter { videni.add(it.id) && Stremio.jeEnota(it) && Stremio.dajeLastneTokove(it) && !ZasebniKanali.jeZaseben(it.id) }
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

    /** Naloga 86 (M5): odstranjen vir - napredek pregleda (pomnilnik in shramba) gre z njim. */
    fun pozabi(c: Context, naslov: String) {
        napredek.remove(naslov)
        // Odstranjen dodatek s tokovi z racunom: ce ga uporabnik doda znova, se odloci na novo.
        val odtis = odtisDodatka(naslov)
        if (odtis in dodatkiSPoverilnico(c)) synchronized(this) {
            val novo = dodatkiSPoverilnico(c) - odtis
            sPoverilnico = novo
            try { c.applicationContext.getSharedPreferences(PREFS_VAROVALO, Context.MODE_PRIVATE).edit().putStringSet(K_DODATKI_S_POVERILNICO, novo).apply() } catch (_: Exception) { }
        }
        try { c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(naslov).apply() } catch (_: Exception) { }
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
