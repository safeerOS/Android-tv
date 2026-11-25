package si.safeer.tv.os

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import org.json.JSONObject
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket

/**
 * Sprejemnik zaslona racunalnika. Bere gol pretok H.264 (Annex-B) z racunalnika in ga predaja
 * strojnemu dekoderju televizorja (MediaCodec) naravnost na povrsino - brez vsebnika in brez
 * medpomnilnika, ker je cilj cim manjsa zakasnitev.
 *
 * Povezava je TLS s **pripetim** potrdilom: odtis dobimo skupaj z enkratnim zetonom v odgovoru na
 * ukaz `screen.start` po Safeer Linku. Tako ne zaupamo nobeni izdajateljski verigi in ne imenu
 * gostitelja - samo temu potrdilu.
 *
 * Po isti povezavi tece **zvok** (surov PCM, brez dekodiranja in brez zakasnitve) in nazaj proti
 * racunalniku **vnos**: vsak dogodek je ena vrstica JSON (tipka, besedilo, premik, klik, kolesce).
 */
class ZaslonOdjemalec(
    /**
     * Naslovi racunalnika, ki jih poskusimo po vrsti (docs/LINK-MESH.md, pravilo 8): naslov iz seznama naprav in
     * tisti, ki jih je racunalnik nastel sam.
     */
    private val naslovi: List<String>,
    private val vrata: Int,
    private val odtis: String,
    private val zeton: String,
    private val naStanje: (Stanje, String) -> Unit,
    private val naStatistiko: (Statistika) -> Unit,
    /** Obvestila racunalnika med sejo (okvir izbire, kazalec za povecavo, tipkovnica ...). */
    private val naObvestilo: (JSONObject) -> Unit = {},
    /**
     * Krajevna vrata releja do Huba racunalnika (Global Link) ali null, kadar te poti ni. Rele se odpre sele ob
     * klicu: doma neposredna pot zmaga prej in se ga ne dotaknemo.
     */
    private val vrataHuba: (() -> Int?)? = null,
    /** Preizkus »tudi doma prek interneta«: samo pot prek Huba, da se vidi, ali deluje. */
    private val samoHub: Boolean = false,
    /** Vticnica za naslov (Android: naslov na kablu prek omrezja eth0) in rok za naslov; glej [NeposrednaPovezava.povezi]. */
    private val vticnicaZa: (String) -> java.net.Socket = { java.net.Socket() },
    private val rokNaslova: (String) -> Int? = { null },
    private val rezervaZa: (String) -> java.net.Socket? = { null },
) {
    /**
     * PRAZNO: racunalnik javi, da na locenem zaslonu ni vec programa (besedilo = razlog).
     * NEDOSEGLJIV: racunalnika ni na nobenem od njegovih naslovov (seja se sploh ni zacela).
     */
    enum class Stanje {
        POVEZUJEM, TECE, KONCANO, NAPAKA, PRAZNO, NEDOSEGLJIV,
        /** Dekoder HEVC te naprave toka ne zna (ni vrnil nobene slike): gledalec sejo zahteva znova s H.264. */
        KODEK
    }

    /** Kar lahko izmerimo na televizorju: slike, pretok in koliko casa slika stoji v dekoderju. */
    data class Statistika(val slik: Int, val naSekundo: Double, val megabitov: Double,
                          val dekoderMs: Long, val sirina: Int, val visina: Int, val zvok: Boolean,
                          /** Koliko presledkov med zaporednima slikama je bilo v tem obdobju daljsih od [ZASTOJ_MS]. */
                          val zastojev: Int = 0,
                          /** Najvec slik, ki so bile v dekoderju, ko je prisla ven naslednja (1 = zadrzi eno sliko; meritev). */
                          val vDekoderju: Int = 0,
                          /** Povprecni cas slike v dekoderju v tem obdobju (ms); [dekoderMs] je samo zadnja slika. */
                          val dekoderPovpMs: Double = 0.0)

    @Volatile private var tece = false
    /** Kodek toka, kot ga pove racunalnik v glavi (`kodek`): HEVC ali (privzeto, starejsi Safeer) H.264. */
    @Volatile private var hevc = false
    /** Kvantizator toka, kot ga pove racunalnik v glavi (`qp`); 0, ce ga ne pove (starejsi Safeer). */
    @Volatile private var qp = 0

    /** Tok za »Podatke o povezavi«: kodek in kvantizator, npr. »HEVC q20«. */
    fun opisToka(): String = (if (hevc) "HEVC" else "H.264") + (if (qp > 0) " q$qp" else "")
    // Stanje dekodiranja; bere in pise ga samo nit toka.
    private val info = MediaCodec.BufferInfo()
    private var slik = 0
    private var zastojev = 0
    /** Pise izhodna nit (pod zaklepom izprazni), bere nit toka brez zaklepa (preveriZastoj). */
    @Volatile private var zadnjaSlika = 0L
    private var zadnjaZakasnitev = 0L
    /** Meritev dekoderja (22. 11. 2026): slike noter/ven, najvec hkrati v dekoderju, vsota casov za povprecje. */
    @Volatile private var slikNoter = 0L
    @Volatile private var slikVen = 0L
    private var najvecVDekoderju = 0
    private var vsotaZakasnitev = 0L
    /** Enote z nastavitvami HEVC, ki cakajo na sliko, s katero gredo skupaj v dekoder. */
    private var predSliko = ByteArray(0)
    /** Slike HEVC, poslane v dekoder, preden je vrnil prvo: dekoder, ki ne vrne nobene, toka ne zna. */
    private var poslanihSlik = 0
    @Volatile private var imaSliko = false
    /** Seja tece prek Huba racunalnika (Global Link), ne neposredno - za dnevnik in meritve. */
    @Volatile var prekHuba = false
        private set
    private var nit: Thread? = null
    private var vticnica: Socket? = null

    /** Naslov racunalnika, na katerega je tok res povezan (za oznako omrezja: kabel ali Wi-Fi); null pred povezavo. */
    val oddaljeniNaslovToka: String? get() = try { vticnica?.inetAddress?.hostAddress } catch (_: Throwable) { null }
    private var izhod: OutputStream? = null
    private var zvocnik: AudioTrack? = null

    /** Rocni zamik zvoka (ms, + = zvok kasneje); nastavi ga zaslon iz shranjene izbire za ta zaslon. */
    @Volatile var rocniZamikZvokaMs = 0L
        set(v) { field = ZaslonSinhro.omejiRocniZamik(v) }
    // Usklajevanje zvoka in slike; dejavno samo, ce glava ima "zig": {"okvir": 4} (sicer kot prej).
    // Razen oznacenih jih bere in pise samo nit toka.
    @Volatile private var zigi = false
    /** Ali seja meri sinhro (okvir 4); brez tega rocni zamik zvoka nima ucinka. */
    val sinhroDejaven: Boolean get() = zigi
    private var zavrnjenihZigov = 0
    /** Zig iz okvirja 4, ki caka na naslednji okvir svoje vrste. */
    private var cakaZigSlike: ZaslonSinhro.Zig? = null
    private var cakaZigZvoka: ZaslonSinhro.Zig? = null
    /** Prejeti bajti vsebine po vrsti, kot jih steje racunalnik v polju "bajt". */
    private var prejetoSlike = 0L
    private var prejetoZvoka = 0L
    /** Zig zajema slike, ki jo pravkar posiljamo v dekoder (null = brez sinhro za to sliko). */
    private var zigSlike: Long? = null
    /** Kljuc vhoda v dekoder (elapsedRealtime v us) -> zig zajema; bere jo tudi nit prikaza. */
    private val zigiSlik = ZaslonSinhro.ZigTabela()
    /** Brez "au": zig prve rezine slike, ki caka v ostanku (ZaslonSinhro.ZigEnote). */
    private val zigEnote = ZaslonSinhro.ZigEnote()
    /** Seja brez "au": pts enot strogo narasca (ZaslonSinhro.enolicenPts); z "au" kot prej. */
    private var brezAu = false
    private var zadnjiPtsUs = 0L
    /** Zapisan okvir zvoka (zacetek odseka) -> zig zajema. */
    private val odsekiZvoka = ZaslonSinhro.ZigTabela()
    private var zadnjiOdsekOkvir = 0L
    private var prejZigZvoka: Long? = null
    private var prejBajtZvoka = 0L
    private val krmilnik = ZaslonSinhro.Krmilnik()
    /** Zamik, ki je ze uveljavljen v sliki in vrsti zvoka (krmilnik ga spremeni prej). */
    private var uveljavljenZamikMs = 0L
    /** Zamik izrisa slike (ms); bereta ga obe niti, ki praznita dekoder. */
    @Volatile private var zamikSlikeMs = 0L
    private val vrstaZvoka = ZaslonZvok.VrstaZvoka()
    private var zvokHz = 48000
    private var zvokKanali = 2
    private var zapisanihOkvirjev = 0L
    /** Podtoki AudioTrackov, ki smo jih v tej seji ze zamenjali (stevec gre z AudioTrackom v nic). */
    private var podtokovPrej = 0
    private var obnovZvoka = 0
    private var zadnjaObnovaMs = 0L
    private val casZvoka = AudioTimestamp()
    private var nitPrikaza: HandlerThread? = null
    // Zadnja prikazana slika z zigom: pise nit prikaza, bere nit toka.
    private val zaklepPrikaza = Any()
    private var prikazZig = 0L
    private var prikazNs = 0L
    private var prikazNov = false
    // Zahteva za kljucni okvir (en presojevalec na sejo, ponastavljen ob novi seji v crpaj).
    private val kljucni = ZaslonKljucniOkvir.Presojevalec()
    @Volatile private var cakaKljucni = false
    /** Pts kljucne slike po zahtevi; pise nit toka, pocisti izhodna nit (compareAndSet, brez izgube). */
    private val kljucniPtsUs = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var slikaPokvarjena = false
    @Volatile private var ponovnaPresojaMs: Long? = null
    @Volatile private var razlogKljucnega = ZaslonKljucniOkvir.Razlog.ZASTOJ_SLIKE
    private var zacetekSejeMs = 0L
    private val posiljalnik = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "safeer-zaslon-vnos").also { it.isDaemon = true }
    }

    // ---- SAMO MERITEV (zastoj zvoka 4 s): zastavice enkrat na sejo, ponastavljene ob vsaki glavi. ----
    // Atomic* namesto kljucavnic: izris tece na dveh nitih (tok in izhodna nit), branje nikoli ne caka.
    private val casSeja = AtomicInteger(0)
    private val casPrviWrite = AtomicBoolean(false)
    private val casZvokNeprazen = AtomicBoolean(false)
    private val casZvokPo5s = AtomicBoolean(false)
    private val casPrvaVDekoder = AtomicBoolean(false)
    private val casPrvaPrikazana = AtomicBoolean(false)
    /** Skupaj ponujeni in sprejeti bajti zvoka (AudioTrack.write vrne sprejete; razlika je izpuscen zvok). */
    private val casZvokPonujeno = AtomicLong(0)
    private val casZvokSprejeto = AtomicLong(0)
    private val casPrviWriteMs = AtomicLong(0)

    /** Ena vrstica na dogodek; elapsedRealtime, ker ga ze uporablja ta razred (zig v dekoderju, statistika). Ne sme vreci. */
    private fun cas(dogodek: String, dodatno: String = "") {
        try {
            Log.i(CAS_TAG, "$CAS_TAG dogodek=$dogodek t_ms=${SystemClock.elapsedRealtime()} " +
                "wall_ms=${System.currentTimeMillis()} seja=${System.identityHashCode(this)}-${casSeja.get()}" +
                (if (dodatno.isEmpty()) "" else " $dodatno"))
        } catch (_: Throwable) { }
    }

    /** Ponastavi zastavice za novo sejo (klic ob prejeti glavi). */
    private fun casNovaSeja() {
        try {
            casSeja.incrementAndGet()
            casPrviWrite.set(false); casZvokNeprazen.set(false); casZvokPo5s.set(false)
            casPrvaVDekoder.set(false); casPrvaPrikazana.set(false)
            casZvokPonujeno.set(0); casZvokSprejeto.set(0); casPrviWriteMs.set(0)
        } catch (_: Throwable) { }
    }

    /** Samo branje stanja AudioTrack za dnevnik; vsaka napaka (stari API) da "?". */
    private fun casZvocnik(t: AudioTrack?): String {
        if (t == null) return "zvocnik=null"
        fun b(f: () -> Any?): String = try { f().toString() } catch (_: Throwable) { "?" }
        return "buffer_frames=" + b { if (Build.VERSION.SDK_INT >= 23) t.bufferSizeInFrames else "n/a" } +
            " start_prag_frames=" + b { if (Build.VERSION.SDK_INT >= 31) t.startThresholdInFrames else "n/a" } +
            " play_state=" + b { t.playState } +
            " glava_predvajanja=" + b { t.playbackHeadPosition } +
            " podtekov=" + b { if (Build.VERSION.SDK_INT >= 24) t.underrunCount else "n/a" }
    }

    fun zacni(surface: Surface) {
        if (tece) return
        tece = true
        nit = Thread({ teci(surface) }, "safeer-zaslon").also { it.start() }
    }

    fun ustavi() {
        tece = false
        try { posiljalnik.shutdownNow() } catch (_: Throwable) { }
        try { vticnica?.close() } catch (_: Throwable) { }
        nit = null
    }

    /**
     * Dogodek s televizorja na racunalnik (tipka, besedilo, premik, klik, kolesce). Poslje se po
     * isti povezavi; racunalnik ga odigra samo, ce je na njegovem seznamu dovoljenega.
     *
     * Pisanje gre na svojo nit: tipka pride z glavne niti, Android pa na njej omrezja ne dovoli
     * (NetworkOnMainThreadException, ki v dnevniku nima niti sporocila - le "null").
     */
    fun posljiVnos(dogodek: JSONObject) {
        if (izhod == null) return
        val besedilo = dogodek.toString() + "\n"
        try {
            posiljalnik.execute {
                val o = izhod ?: return@execute
                try {
                    synchronized(o) { o.write(besedilo.toByteArray()); o.flush() }
                } catch (e: Throwable) { Log.w(TAG, "Vnosa ni bilo mogoce poslati: ${e.message}") }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    private fun teci(surface: Surface) {
        var kodek: MediaCodec? = null
        try {
            naStanje(Stanje.POVEZUJEM, "")
            // Dve poti do iste seje: neposredno (doma) in prek Huba racunalnika (Global Link, zdoma). Doma je
            // neposredna povezava koncana, preden se druga sploh zacne; zdoma nihce ne caka na naslove domacega
            // omrezja. Zeton gre na pot sele po rokovanju s pripetim potrdilom (NeposrednaPovezava).
            val dobiVrata = vrataHuba
            val neposredno: ((() -> Boolean) -> SSLSocket?)? =
                if (naslovi.isEmpty() || (samoHub && dobiVrata != null)) null
                else fun(t: () -> Boolean): SSLSocket? =
                    NeposrednaPovezava.povezi(naslovi, vrata, odtis, oznaka = TAG, tece = t,
                        vticnica = vticnicaZa, rokNaslova = rokNaslova, rezerva = rezervaZa)
            val poHubu: ((() -> Boolean) -> SSLSocket?)? =
                if (dobiVrata == null) null
                else fun(t: () -> Boolean): SSLSocket? {
                    val v = dobiVrata() ?: return null
                    return NeposrednaPovezava.poveziPrekHuba(v, odtis, zeton, oznaka = TAG, tece = t)
                }
            val izid = NeposrednaPovezava.tekma(neposredno, poHubu, tece = { tece })
            if (izid == null) {
                if (tece) {
                    Log.w(TAG, "Zaslon: racunalnik ni dosegljiv (${naslovi.joinToString()}:$vrata)")
                    naStanje(Stanje.NEDOSEGLJIV, "")
                } else {
                    naStanje(Stanje.KONCANO, "")
                }
                return
            }
            val s = izid.vticnica
            vticnica = s
            // Uporabnik je zaslon zapustil med povezovanjem: racunalniku se ne predstavimo vec.
            if (!tece) { naStanje(Stanje.KONCANO, ""); return }
            prekHuba = izid.pot == NeposrednaPovezava.Pot.HUB
            Log.i(TAG, "Zaslon: povezava " + if (prekHuba) "prek Global Linka" else "neposredno")
            val izhodniTok: OutputStream = s.outputStream
            val vhod: InputStream = s.inputStream
            // Prek Huba je zeton ze v zahtevi za predajo seje; neposredno ga poslje pozdrav.
            if (!prekHuba) {
                izhodniTok.write(("SAFEER-ZASLON $zeton\n").toByteArray())
                izhodniTok.flush()
            }
            izhod = izhodniTok
            // Glava pride takoj, ko racunalnik gledalca sprejme; brez roka bi nema povezava visela v nedogled.
            s.soTimeout = TISINA_MS
            val glava = JSONObject(preberiVrstico(vhod))
            casNovaSeja()
            cas("glava_prejeta", "zvok=" + (try { if (glava.optJSONObject("zvok") != null) 1 else 0 } catch (_: Throwable) { "?" }) +
                " prek_huba=" + (if (prekHuba) 1 else 0))
            val sirina = glava.optInt("w", 1920)
            val visina = glava.optInt("h", 1080)
            val fps = glava.optInt("fps", 30)
            hevc = glava.optString("kodek") == "hevc"
            qp = glava.optInt("qp", 0)
            // Zigi samo, ce racunalnik pove protokol okvirja 4; stari racunalnik polja nima in vse ostane kot prej.
            zigi = glava.optJSONObject("zig")?.optInt("okvir", 0) == OKVIR_ZIG
            imaSliko = false
            kodek = try { pripraviKodek(surface, sirina, visina, fps) } catch (e: Throwable) {
                if (!hevc) throw e
                // Dekoderja HEVC ni mogoce pripraviti: gledalec sejo zahteva znova s H.264.
                Log.w(TAG, "HEVC: dekoderja ni mogoce pripraviti (${e.message})")
                naStanje(Stanje.KODEK, "")
                return
            }
            val dekoder: MediaCodec = kodek ?: return
            glava.optJSONObject("zvok")?.let { zvocnik = pripraviZvok(it.optInt("hz", 48000), it.optInt("kanali", 2)) }
            if (zigi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Cas DEJANSKEGA prikaza slike (pregled S1): sistem ga pove po izrisu, na svoji niti, ne na glavni.
                try {
                    val np = HandlerThread("safeer-zaslon-prikaz")
                    np.start()
                    nitPrikaza = np
                    dekoder.setOnFrameRenderedListener({ _, ptsUs, nanoTime -> obPrikazu(ptsUs, nanoTime) }, Handler(np.looper))
                } catch (e: Throwable) { Log.w(TAG, "Sinhro: brez casa prikaza (${e.message})") }
            }
            // Slika in zvok tecejo ves cas (tudi med pavzo). Ce 10 s ne pride nic, povezave ni vec
            // (izpad Wi-Fi ne zapre vticnice) - branje pade in seja se vrne sama, namesto zamrznjene slike.
            s.soTimeout = TISINA_MS
            naStanje(Stanje.TECE, "")
            // Racunalnik, ki vsak okvir poslje kot celo sliko ("au", Safeer OS za Windows 1.0.48), dovoli, da jo dekoder
            // dobi takoj; brez tega zadnja enota slike caka na zacetek naslednje (en interval slike zamika).
            crpaj(vhod, dekoder, sirina, visina, glava.optBoolean("au", false))
            naStanje(Stanje.KONCANO, "")
        } catch (e: Throwable) {
            if (tece && hevc && !imaSliko && e !is java.io.IOException) {
                // Dekoder HEVC je odpovedal, preden je vrnil prvo sliko (povezava je cela): znova s H.264.
                Log.w(TAG, "HEVC: dekoder je odpovedal pred prvo sliko (${e.message})")
                naStanje(Stanje.KODEK, "")
            } else if (tece) {
                Log.w(TAG, "Zaslon: ${e.message}")
                naStanje(Stanje.NAPAKA, e.message.orEmpty())
            } else {
                naStanje(Stanje.KONCANO, "")
            }
        } finally {
            izhod = null
            // Meritev za TV: koliko podtokov je imel zvok v tej seji (pregled Z1, 60 ms medpomnilnik).
            if (zvocnik != null || podtokovPrej > 0)
                Log.i(TAG, "Zvok: podtokov v seji ${podtokovPrej + podtokov(zvocnik)}, zamik sinhro $uveljavljenZamikMs ms")
            vrstaZvoka.pocisti()
            try { zvocnik?.pause(); zvocnik?.flush(); zvocnik?.release() } catch (_: Throwable) { }
            zvocnik = null
            try { kodek?.stop() } catch (_: Throwable) { }
            try { kodek?.release() } catch (_: Throwable) { }
            // Nit prikaza koncamo sele po sprostitvi dekoderja (prej bi dekoder posiljal na mrtvo nit).
            try { nitPrikaza?.quitSafely() } catch (_: Throwable) { }
            nitPrikaza = null
            try { vticnica?.close() } catch (_: Throwable) { }
            vticnica = null
            tece = false
        }
    }

    private fun pripraviKodek(surface: Surface, sirina: Int, visina: Int, fps: Int): MediaCodec {
        val vrsta = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        // Obliko sestavimo vsakic znova: kopija MediaFormat(MediaFormat) je sele od Androida 10 (API 29),
        // televizorji z Androidom 9 bi ob njej padli (NoSuchMethodError).
        fun osnovna(): MediaFormat {
            val oblika = MediaFormat.createVideoFormat(vrsta, sirina, visina)
            oblika.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // Nizka zakasnitev: dekoder naj ne zbira slik vnaprej (Android 11+ zna to povedati naravnost).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                oblika.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            oblika.setInteger(MediaFormat.KEY_PRIORITY, 0)   // v ospredju, ne v ozadju
            // Kljucni okvir pri visoki kakovosti zlahka preseze pol megabajta. Ce dekoderju tega ne
            // povemo, so vhodni medpomnilniki premajhni in prva taka slika vrze BufferOverflowException
            // (v dnevniku samo "null") - seja pade takoj po prvi sliki.
            oblika.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(1 shl 20, sirina * visina))
            return oblika
        }
        // Dekoderji Qualcomm in Samsung Exynos sliko zadrzijo, dokler ne pride naslednja (cakajo na morebitno
        // preurejanje slik, ki ga nas tok nima): izmerjeno na Tab A9+ (c2.qti.avc.decoder) ~50 ms pri mirnem
        // zaslonu. Njihove lastne nastavitve to izklopijo; drugi dekoderji neznane kljuce prezrejo.
        val nizkaZakasnost = mapOf(
            "vendor.qti-ext-dec-picture-order.enable" to 1,
            "vendor.qti-ext-dec-low-latency.enable" to 1,
            "vendor.rtc-ext-dec-low-latency.enable" to 1,
            // MediaTek (tudi televizorji Philips, mt5895) in Amlogic: enako, njuna kljuca (Moonlight, MediaCodecHelper).
            "vdec-lowlatency" to 1,
            "vendor.low-latency.enable" to 1,
        )
        var kodek = MediaCodec.createDecoderByType(vrsta)
        try {
            val z = osnovna()
            nizkaZakasnost.forEach { (k, v) -> z.setInteger(k, v) }
            kodek.configure(z, surface, null, 0)
        } catch (e: Exception) {
            // Dekoder nastavitev proizvajalca ne sprejme: brez njih, kot prej.
            Log.w(TAG, "Dekoder brez nastavitev nizke zakasnitve: ${e.message}")
            try { kodek.release() } catch (_: Throwable) { }
            kodek = MediaCodec.createDecoderByType(vrsta)
            kodek.configure(osnovna(), surface, null, 0)
        }
        kodek.start()
        Log.i(TAG, "Dekoder: ${kodek.name} za ${sirina}x$visina@$fps")
        // Meritev: ali je dekoder nastavitve nizke zakasnitve res sprejel (neznane kljuce tiho zavrze).
        try {
            val f = kodek.inputFormat
            val sprejeto = listOf("low-latency", "vdec-lowlatency", "vendor.low-latency.enable").joinToString(" ") { k ->
                "$k=" + (if (f.containsKey(k)) try { f.getInteger(k).toString() } catch (_: Throwable) { "?" } else "-")
            }
            Log.i(TAG, "Dekoder sprejel: $sprejeto")
        } catch (_: Throwable) { }
        return kodek
    }

    /**
     * Zvok je surov PCM, zato ga ni treba dekodirati - gre naravnost v AudioTrack. Medpomnilnik je
     * majhen (okrog 60 ms), ker je cilj, da zvok ne zaostaja za sliko.
     */
    private fun pripraviZvok(hz: Int, kanali: Int): AudioTrack? = try {
        // Kanale normaliziramo enkrat (pregled Grok L6, 22. 11. 2026): razpored in velikost medpomnilnika izhajata iz istega stevila,
        // sicer bi kanali <= 0 dali mono razpored s stereo velikostjo (dvakrat daljsi medpomnilnik).
        val kanaliZvoka = minOf(ZaslonZvok.kanaliVeljavni(kanali), 2)
        zvokHz = hz
        zvokKanali = kanaliZvoka
        val razpored = if (kanaliZvoka >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val najmanj = AudioTrack.getMinBufferSize(hz, razpored, AudioFormat.ENCODING_PCM_16BIT)
        // Negativna koda pomeni nepodprto obliko: vzrok gre v dnevnik spodaj (catch), ne skrije ga Builder (pregled Z5).
        if (najmanj <= 0) throw IllegalArgumentException("getMinBufferSize $najmanj za $hz Hz, $kanaliZvoka kanala")
        // Velikost po pravilih ZaslonZvok (ciljno ~60 ms, poravnano na cel vzorec, nikoli pod sistemskim najmanjsim).
        // Veckanalni zvok (5.1) se vkljuci sele, ko ga racunalnik poslje in je preizkusen na TV: zdaj samo mono/stereo.
        val velikost = ZaslonZvok.medpomnilnikBajtov(hz, kanaliZvoka, false,
            ZaslonZvok.CILJNI_MEDPOMNILNIK_MS, maxOf(najmanj, 0))
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(hz)
                .setChannelMask(razpored).build())
            .setBufferSizeInBytes(velikost)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also {
                cas("audiotrack_ustvarjen", "min_buffer_b=$najmanj velikost_b=$velikost hz=$hz kanali=$kanaliZvoka " + casZvocnik(it))
                it.play()
                cas("play", "bajti_zapisano=${casZvokSprejeto.get()} " + casZvocnik(it))
                Log.i(TAG, "Zvok: $hz Hz, $kanali kanala, medpomnilnik $velikost B")
            }
    } catch (e: Throwable) {
        Log.w(TAG, "Zvoka ni bilo mogoce pripraviti: ${e.message}")
        null
    }

    /** Okvir 4: zig za NASLEDNJI okvir vrste iz polja "vrsta"; pokvarjen (ali predolg) okvir preskocimo. */
    private fun obZigu(telo: ByteArray) {
        if (!zigi) return
        val zig = ZaslonSinhro.razcleniZig(telo)
        if (zig == null) {
            zavrnjenihZigov++
            if (zavrnjenihZigov == 1 || zavrnjenihZigov % 1000 == 0) Log.w(TAG, "Sinhro: neveljaven okvir 4 ($zavrnjenihZigov)")
            return
        }
        if (zig.vrsta == OKVIR_SLIKA) cakaZigSlike = zig else cakaZigZvoka = zig
    }

    /**
     * Zig za ta okvir slike ali zvoka (ce je pred njim prisel okvir 4 z ujemajocim stevcem "bajt"); vedno pristeje
     * okvir k stevcu prejetih bajtov te vrste, kot ga steje racunalnik.
     */
    private fun vzemiZig(vrsta: Int, dolzina: Int): ZaslonSinhro.Zig? {
        val slika = vrsta == OKVIR_SLIKA
        val cakajoci = if (slika) cakaZigSlike else cakaZigZvoka
        val prejeto = if (slika) prejetoSlike else prejetoZvoka
        if (slika) {
            cakaZigSlike = null
            prejetoSlike += dolzina
        } else {
            cakaZigZvoka = null
            prejetoZvoka += dolzina
        }
        if (!zigi || cakajoci == null) return null
        return if (ZaslonSinhro.zigVelja(cakajoci, prejeto)) cakajoci else null
    }

    /** Okvir zvoka: v vrsto (z zigom in oznako zveznosti iz "bajt"), nato pisi, izpusti zaostalo in meri. */
    private fun obZvoku(telo: ByteArray, dolzina: Int, zig: ZaslonSinhro.Zig?) {
        var novOdsek = false
        if (zig != null) {
            val prej = prejZigZvoka
            novOdsek = prej == null || !ZaslonZvok.zvezenOdsek(prej, prejBajtZvoka, zig.zigMs, zig.bajt,
                zvokHz, zvokKanali, false)
            prejZigZvoka = zig.zigMs
            prejBajtZvoka = zig.bajt
        }
        casPonudi(telo, dolzina)
        vrstaZvoka.dodaj(telo, 0, dolzina, zig?.zigMs, novOdsek)
        zapisiZvok()
        izpustiZaostalo()
        meriSinhro()
    }

    /**
     * WRITE_NON_BLOCKING vzame, kolikor je prostora: vrnjeno stevilo bajtov odstejemo, ostanek ostane v vrsti
     * za naslednji okvir (pregled Z2). Negativna koda pomeni pokvarjen AudioTrack: zgradimo ga znova.
     */
    private fun zapisiZvok() {
        var poskusov = 0
        while (poskusov < 64) {
            poskusov++
            val track = zvocnik ?: return
            val kos = vrstaZvoka.prvi() ?: return
            val r = try {
                track.write(kos.podatki, kos.od, kos.konec - kos.od, AudioTrack.WRITE_NON_BLOCKING)
            } catch (_: Throwable) {
                AudioTrack.ERROR_INVALID_OPERATION
            }
            if (r < 0) {
                znovaZgradiZvok(r)
                return
            }
            casSprejeto(r, track)
            if (r == 0) return
            val zig = kos.zig
            // Odsek zapisemo ob vrzeli in vsaj vsako sekundo, da preracun iz odseka ostane kratek.
            if (zig != null && (kos.novOdsek || zapisanihOkvirjev - zadnjiOdsekOkvir >= zvokHz.toLong())) {
                odsekiZvoka.dodaj(zapisanihOkvirjev,
                    ZaslonZvok.zigPoOdmiku(zig, kos.od - kos.zacetek, zvokHz, zvokKanali, false))
                zadnjiOdsekOkvir = zapisanihOkvirjev
                kos.novOdsek = false
            }
            zapisanihOkvirjev += (r / ZaslonZvok.velikostVzorca(zvokKanali, false)).toLong()
            vrstaZvoka.porabi(r)
        }
    }

    /** Merilni dnevnik na poti prek vrste (isti dogodki kot na stari poti: prvi_write, prvi_zvok_neprazen, zvok_po_5s). */
    private fun casPonudi(telo: ByteArray, dolzina: Int) {
        try {
            val ponujenoSkupaj = casZvokPonujeno.addAndGet(dolzina.toLong())
            if (!casZvokNeprazen.get() && telo.any { it != 0.toByte() } && casZvokNeprazen.compareAndSet(false, true)) {
                cas("prvi_zvok_neprazen", "ponujeno_skupaj=$ponujenoSkupaj sprejeto_skupaj=${casZvokSprejeto.get()}")
            }
        } catch (_: Throwable) { }
    }

    private fun casSprejeto(r: Int, track: AudioTrack) {
        try {
            val sprejetoSkupaj = casZvokSprejeto.addAndGet(maxOf(r, 0).toLong())
            val ponujenoSkupaj = casZvokPonujeno.get()
            if (casPrviWrite.compareAndSet(false, true)) {
                casPrviWriteMs.set(SystemClock.elapsedRealtime())
                cas("prvi_write", "sprejeto=$r ponujeno_skupaj=$ponujenoSkupaj sprejeto_skupaj=$sprejetoSkupaj " + casZvocnik(track))
            }
            if (!casZvokPo5s.get() && SystemClock.elapsedRealtime() - casPrviWriteMs.get() >= 5000 &&
                casZvokPo5s.compareAndSet(false, true)) {
                cas("zvok_po_5s", "ponujeno_skupaj=$ponujenoSkupaj sprejeto_skupaj=$sprejetoSkupaj " + casZvocnik(track))
            }
        } catch (_: Throwable) { }
    }

    private fun podtokov(track: AudioTrack?): Int =
        if (track != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { track.underrunCount } catch (_: Throwable) { 0 }
        } else {
            0
        }

    private fun znovaZgradiZvok(koda: Int) {
        Log.w(TAG, "Zvok: write je vrnil $koda, AudioTrack gradimo znova")
        val star = zvocnik
        zvocnik = null
        podtokovPrej += podtokov(star)
        try { star?.release() } catch (_: Throwable) { }
        // Nov AudioTrack steje okvirje od 0, namerna tisina v starem je izgubljena: sinhro od zacetka.
        zapisanihOkvirjev = 0L
        zadnjiOdsekOkvir = 0L
        odsekiZvoka.ponastavi()
        krmilnik.ponastavi()
        uveljavljenZamikMs = 0L
        zamikSlikeMs = 0L
        vrstaZvoka.pocisti()
        val zdajObnova = SystemClock.elapsedRealtime()
        if (zdajObnova - zadnjaObnovaMs > 60_000L) obnovZvoka = 0
        zadnjaObnovaMs = zdajObnova
        obnovZvoka++
        if (obnovZvoka > 3) Log.w(TAG, "Zvok: obnov AudioTracka je preveliko, zvok do konca seje ugasnjen")
        // Omejeno, da pokvarjen izhod zvoka ne gradi AudioTracka ob vsakem okvirju; slika tece naprej.
        if (obnovZvoka <= 3) zvocnik = pripraviZvok(zvokHz, zvokKanali)
    }

    /** Zaostanek nad mejo iz cilja (in namernega zamika zvoka) izpustimo iz vrste, da zvok ne zaostaja za sliko. */
    private fun izpustiZaostalo() {
        val track = zvocnik ?: return
        val glavaPredvajanja = try { track.playbackHeadPosition } catch (_: Throwable) { return }
        val zaostanek = ZaslonZvok.zaostanekMs(ZaslonZvok.vTrackuOkvirjev(zapisanihOkvirjev, glavaPredvajanja),
            vrstaZvoka.bajtov, zvokHz, zvokKanali, false)
        val izpust = ZaslonZvok.zaIzpustMs(zaostanek, ZaslonSinhro.zamikZvokaMs(uveljavljenZamikMs))
        if (izpust <= 0L) return
        val izpusceno = vrstaZvoka.izpusti(ZaslonZvok.bajtovZaMs(izpust, zvokHz, zvokKanali, false))
        // Po izpustu so meritve odmika iz casa pred njim napacne.
        if (izpusceno > 0) krmilnik.pozabiMeritve()
    }

    /** Nit prikaza: slika s tem pts je bila izrisana ob [nanoTime] (System.nanoTime). */
    private fun obPrikazu(ptsUs: Long, nanoTime: Long) {
        // Kljuc je bil prebran tik pred oddajo v dekoder: pts je enak ali 1 ms vecji.
        val zig = zigiSlik.natancno(ptsUs) ?: return
        synchronized(zaklepPrikaza) {
            prikazZig = zig
            prikazNs = nanoTime
            prikazNov = true
        }
    }

    /**
     * Odmik iz DEJANSKIH casov: zig prikazane slike proti zigu zvoka, ki se je predvajal ob istem trenutku
     * (AudioTrack.getTimestamp, preslikan prek zapisanih odsekov na zig zajema).
     */
    private fun meriSinhro() {
        if (!zigi) return
        val track = zvocnik ?: return
        val prikaz = synchronized(zaklepPrikaza) {
            if (prikazNov) {
                prikazNov = false
                longArrayOf(prikazZig, prikazNs)
            } else {
                null
            }
        } ?: return
        val ima = try { track.getTimestamp(casZvoka) } catch (_: Throwable) { false }
        if (!ima) return
        val okvir = ZaslonSinhro.okvirOb(prikaz[1], casZvoka.framePosition, casZvoka.nanoTime, zvokHz) ?: return
        val odsek = odsekiZvoka.navzdol(okvir) ?: return
        val zigZvoka = ZaslonSinhro.zigOkvirja(okvir, zvokHz, odsek.first, odsek.second) ?: return
        val nov = krmilnik.meritev(ZaslonSinhro.odmik(prikaz[0], zigZvoka), prikaz[1], rocniZamikZvokaMs) ?: return
        uveljaviZamik(nov)
    }

    /** Pozitiven del zamika zakasni izris slike, negativen doda tisino v vrsto zvoka (ali jo, ko se zmanjsa, izpusti). */
    private fun uveljaviZamik(nov: Long) {
        val zvokPrej = ZaslonSinhro.zamikZvokaMs(uveljavljenZamikMs)
        val zvokZdaj = ZaslonSinhro.zamikZvokaMs(nov)
        uveljavljenZamikMs = nov
        zamikSlikeMs = ZaslonSinhro.zamikSlikeMs(nov)
        if (zvokZdaj > zvokPrej) {
            val tisina = ZaslonZvok.bajtovZaMs(zvokZdaj - zvokPrej, zvokHz, zvokKanali, false)
            if (tisina > 0) {
                vrstaZvoka.dodaj(ByteArray(tisina), 0, tisina, null, true)
                // Za tisino se zig odsekov ne nadaljuje: naslednji zvok zacne nov odsek.
                vrstaZvoka.prekini()
            }
        } else if (zvokZdaj < zvokPrej) {
            val hoteno = ZaslonZvok.bajtovZaMs(zvokPrej - zvokZdaj, zvokHz, zvokKanali, false)
            if (vrstaZvoka.izpusti(hoteno) < hoteno) krmilnik.pozabiMeritve()
        }
        Log.i(TAG, "Sinhro: zamik $nov ms (slika ${ZaslonSinhro.zamikSlikeMs(nov)}, zvok $zvokZdaj)")
    }

    /** Zadnji izhod slike in stevec slik ven; pod isto kljucavnico kot izprazni (dve niti). */
    // Brez zaklepa: izhodna nit ga drzi med dequeueOutputBuffer (do IZHOD_CAKAJ_US).
    private fun stanjeIzhoda(): LongArray = longArrayOf(zadnjaSlika, slikVen)

    /** Ob vsaki prejeti sliki: zastoj -> zahteva za kljucni okvir; sicer morebitna ponovna presoja. */
    private fun preveriZastoj() {
        val zdaj = SystemClock.elapsedRealtime()
        val stanje = stanjeIzhoda()
        val zadnjiIzhod = if (stanje[0] == 0L) zacetekSejeMs else stanje[0]
        if (ZaslonSinhro.jeZastojSlike(zdaj, zadnjiIzhod, slikNoter - stanje[1])) {
            zahtevajKljucni(ZaslonKljucniOkvir.Razlog.ZASTOJ_SLIKE)
            return
        }
        // Zastoj, ki je minil sam, ne potrebuje vec zahteve; pokvarjena slika (izpuscena enota) jo se.
        if (razlogKljucnega == ZaslonKljucniOkvir.Razlog.ZASTOJ_SLIKE) {
            slikaPokvarjena = false
            ponovnaPresojaMs = null
        }
        val ponovno = ponovnaPresojaMs
        if (slikaPokvarjena && ponovno != null && zdaj >= ponovno) zahtevajKljucni(razlogKljucnega)
    }

    /**
     * Presoja po pogodbi ZaslonKljucniOkvir.Presojevalec (monotona ura elapsedRealtime). Ob poslji = true gre po
     * vnosnem kanalu {"vrsta":"kljucni"}; sicer presodimo znova cez cakajMs, ce je slika se pokvarjena.
     */
    private fun zahtevajKljucni(razlog: ZaslonKljucniOkvir.Razlog) {
        val zdaj = SystemClock.elapsedRealtime()
        slikaPokvarjena = true
        razlogKljucnega = razlog
        val ponovno = ponovnaPresojaMs
        if (ponovno != null && zdaj < ponovno) return
        val odlocitev = synchronized(kljucni) { kljucni.presodi(razlog, zdaj) }
        if (odlocitev.poslji) {
            ponovnaPresojaMs = null
            cakaKljucni = true
            posljiVnos(JSONObject().put("vrsta", "kljucni"))
            Log.i(TAG, "Kljucni okvir: zahteva ($razlog, zaporednih ${kljucni.zaporednihNeuspelih})")
        } else {
            ponovnaPresojaMs = zdaj + odlocitev.cakajMs
        }
    }

    /** Ce je ta izhod kljucna slika po zahtevi, je uspeh (pogodba: samo nov kljucni okvir po poslani zahtevi). */
    private fun preveriKljucniIzris(ptsUs: Long) {
        val kljuc = kljucniPtsUs.get()
        if (kljuc == 0L) return
        // Kljuc je natanko pts te slike (ena prebrana ura), zato brez tolerance.
        if (ptsUs == kljuc) {
            if (!kljucniPtsUs.compareAndSet(kljuc, 0L)) return
            synchronized(kljucni) { kljucni.uspeh(SystemClock.elapsedRealtime()) }
            slikaPokvarjena = false
            ponovnaPresojaMs = null
        } else if (ptsUs > kljuc && kljucniPtsUs.compareAndSet(kljuc, 0L)) {
            // Kljucna slika ni prisla ven (dekoder jo je zavrgel): cakamo naslednjo.
            cakaKljucni = true
        }
    }

    /** Ali enote od [od] do [do_] vsebujejo kljucno sliko (pred njo so lahko AUD, SEI). */
    private fun vsebujeKljucno(vir: ByteArray, od: Int, do_: Int): Boolean {
        var i = od
        while (i in 0 until do_) {
            if (ZaslonSinhro.jeKljucnaEnota(hevc, vrstaNal(vir, i))) return true
            val naslednji = zacetekNal(vir, i + 3)
            i = if (naslednji in 0 until do_) naslednji else -1
        }
        return false
    }

    /** Izris z zamikom (sinhro): ista statistika kot spodnja pot v izprazni, izris ob System.nanoTime + zamik. */
    private fun izrisiZZamikom(kodek: MediaCodec, i: Int, zdajSlika: Long, zamikMs: Long) {
        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            najvecVDekoderju = maxOf(najvecVDekoderju, (slikNoter - slikVen).toInt())
            slikVen++
            vsotaZakasnitev += zadnjaZakasnitev
        }
        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
            casPrvaPrikazana.compareAndSet(false, true)) cas("prva_slika_prikazana", "velikost=${info.size} zamik_ms=$zamikMs")
        kodek.releaseOutputBuffer(i, System.nanoTime() + zamikMs * 1_000_000L)
        slik++
        imaSliko = true
        if (zadnjaSlika != 0L && zdajSlika - zadnjaSlika > ZASTOJ_MS) zastojev++
        zadnjaSlika = zdajSlika
    }

    /**
     * Pretok so okvirji: en bajt vrste, stirje bajti dolzine, vsebina. Slika gre v dekoder (prej jo
     * razrezemo na enote NAL), zvok pa naravnost v AudioTrack. Zvok pisemo neblokirajoce: ce bi cakal,
     * bi ustavil sliko - raje izpustimo nekaj zvoka kot da slika obstane.
     */
    private fun crpaj(vhod: InputStream, kodek: MediaCodec, sirina: Int, visina: Int, celeSlike: Boolean = false) {
        val podatkovni = DataInputStream(vhod)
        val glava = ByteArray(5)
        var ostanek = ByteArray(0)
        var bajtov = 0L
        var zadnjePorocilo = SystemClock.elapsedRealtime()
        var imelZvok = false
        slik = 0; zastojev = 0; zadnjaSlika = 0L; zadnjaZakasnitev = 0L; predSliko = ByteArray(0); poslanihSlik = 0
        slikNoter = 0L; slikVen = 0L; najvecVDekoderju = 0; vsotaZakasnitev = 0L
        // Sinhro in kljucni okvir od zacetka seje (isti odjemalec lahko tece veckrat).
        cakaZigSlike = null; cakaZigZvoka = null; prejetoSlike = 0L; prejetoZvoka = 0L; zigSlike = null
        zigiSlik.ponastavi(); odsekiZvoka.ponastavi(); zadnjiOdsekOkvir = 0L; prejZigZvoka = null; prejBajtZvoka = 0L
        zigEnote.pocisti(); brezAu = !celeSlike; zadnjiPtsUs = 0L
        krmilnik.ponastavi(); vrstaZvoka.pocisti(); uveljavljenZamikMs = 0L; zamikSlikeMs = 0L
        zapisanihOkvirjev = 0L; podtokovPrej = 0; obnovZvoka = 0
        synchronized(zaklepPrikaza) { prikazNov = false }
        kljucni.ponastavi(); cakaKljucni = false; kljucniPtsUs.set(0L); slikaPokvarjena = false; ponovnaPresojaMs = null
        zacetekSejeMs = SystemClock.elapsedRealtime()
        // Cele slike (H.264): izhod dekoderja prazni svoja nit, ki na dekoder caka - sicer slika, ki se dekodira
        // dlje od trenutka branja, pride na zaslon sele ob naslednjem okvirju z omrezja (izmerjeno ~50 ms).
        val izhodnaNit = if (celeSlike && !hevc) Thread({
            try {
                while (tece) izprazni(kodek, IZHOD_CAKAJ_US)
            } catch (_: Throwable) { }   // dekoder je ustavljen: seja se konca drugje
        }, "safeer-zaslon-izris").also { it.isDaemon = true; it.start() } else null
        try {
        while (tece) {
            podatkovni.readFully(glava)
            val vrsta = glava[0].toInt() and 0xff
            val dolzina = ((glava[1].toInt() and 0xff) shl 24) or ((glava[2].toInt() and 0xff) shl 16) or
                ((glava[3].toInt() and 0xff) shl 8) or (glava[4].toInt() and 0xff)
            if (dolzina <= 0 || dolzina > NAJVECJI_OKVIR) break
            val telo = ByteArray(dolzina)
            podatkovni.readFully(telo)
            if (vrsta == OKVIR_ZIG) {
                bajtov += dolzina + glava.size
                obZigu(telo)
                continue
            }
            if (vrsta == OKVIR_ZVOK) {
                val zigZvoka = vzemiZig(OKVIR_ZVOK, dolzina)
                // Zvok gre prek vrste (ostanek write ostane, Z2); spodnja obstojeca veja ostane samo brez AudioTracka.
                if (zvocnik != null) {
                    bajtov += dolzina + glava.size
                    imelZvok = true
                    obZvoku(telo, dolzina, zigZvoka)
                    continue
                }
            }
            bajtov += dolzina + glava.size
            if (vrsta == OKVIR_ZVOK) {
                imelZvok = true
                // Sem pride samo brez AudioTracka (zgornja veja ima zvocnik != null): pisati ni kam.
                casPonudi(telo, dolzina)
                continue
            }
            if (vrsta == OKVIR_OBVESTILO) {
                val obvestilo = try { JSONObject(String(telo, Charsets.UTF_8)) } catch (_: Throwable) { null }
                val konec = obvestilo?.optString("konec").orEmpty()
                if (konec.isNotEmpty()) {
                    // »prevzeto«: zaslon je odprla druga naprava (Safeer za Windows od 1.0.49) - kateri, povemo naprej.
                    val zakaj = when (konec) {
                        "prevzeto" -> "prevzeto:" + obvestilo?.optString("naprava").orEmpty()
                        "zajem" -> "zajem:" + obvestilo?.optString("razlog").orEmpty()
                        else -> konec
                    }
                    naStanje(Stanje.PRAZNO, zakaj); tece = false; break
                }
                if (obvestilo != null) try { naObvestilo(obvestilo) } catch (_: Throwable) { }
                continue
            }
            if (vrsta != OKVIR_SLIKA) continue
            // Z "au" zig velja za ves okvir (cela slika). Brez "au" ga ZigEnote da prvi rezini slike z meje tega
            // okvirja, ko gre v dekoder: zadnja enota caka na naslednjo zacetno kodo (N3).
            val zigS = vzemiZig(OKVIR_SLIKA, dolzina)
            zigSlike = if (celeSlike) zigS?.zigMs else null
            preveriZastoj()
            if (celeSlike && !hevc) {
                // Okvir je cela slika: nastavitve (SPS, PPS) posebej, vse ostalo v ENEM medpomnilniku. Enota za
                // enoto dekoder (izmerjeno: Qualcomm na Tab A9+) sliko zadrzi, dokler ne pride naslednja - ne ve,
                // da je slika ze cela (pri mirnem zaslonu ~50 ms, pri gibanju en interval slike).
                var od = zacetekNal(telo, 0)
                while (od >= 0 && (vrstaNal(telo, od) == 7 || vrstaNal(telo, od) == 8)) {
                    val naslednji = zacetekNal(telo, od + 3)
                    posljiNal(kodek, telo, od, if (naslednji > 0) naslednji else telo.size)
                    od = naslednji
                }
                if (od >= 0) posljiNal(kodek, telo, od, telo.size)
                // Izris vodi izhodna nit (spodaj): sliko pokaze takoj, ko jo dekoder naredi.
                val zdajC = SystemClock.elapsedRealtime()
                if (zdajC - zadnjePorocilo >= 1000) {
                    val sekunde = (zdajC - zadnjePorocilo) / 1000.0
                    naStatistiko(Statistika(slik, slik / sekunde, bajtov * 8 / 1e6 / sekunde,
                        zadnjaZakasnitev, sirina, visina, imelZvok, zastojev, najvecVDekoderju,
                        if (slik > 0) vsotaZakasnitev.toDouble() / slik else 0.0))
                    najvecVDekoderju = 0; vsotaZakasnitev = 0L
                    slik = 0; bajtov = 0; zadnjePorocilo = zdajC; imelZvok = false; zastojev = 0
                }
                continue
            }
            val prejOstanek = ostanek.size
            ostanek = ZaslonOstanek.dodaj(ostanek, telo)
            val meja = ostanek.size - telo.size
            if (!celeSlike) zigEnote.okvir(zigS?.zigMs, meja, meja == prejOstanek)
            var od = zacetekNal(ostanek, 0)
            if (od < 0) { zigEnote.pocisti(); continue }
            var naslednji = zacetekNal(ostanek, od + 3)
            while (naslednji > 0) {
                if (!celeSlike) zigSlike = zigEnote.za(od, naslednji, ZaslonKodek.jeRezina(hevc, vrstaNal(ostanek, od)))
                posljiNal(kodek, ostanek, od, naslednji)
                od = naslednji
                naslednji = zacetekNal(ostanek, od + 3)
            }
            if (celeSlike) {
                posljiNal(kodek, ostanek, od, ostanek.size)    // okvir je cela slika: zadnja enota ne caka
                ostanek = ByteArray(0)
            } else {
                // Zig velja samo za enoto, ki ga je dobila v za(); cakajoca enota ga nosi v ZigEnote.
                zigSlike = null
                ostanek = ostanek.copyOfRange(od, ostanek.size)   // zacetek naslednje enote
                zigEnote.odrezi(od)
            }
            izprazni(kodek)
            if (ZaslonKodek.hevcBrezSlike(hevc, poslanihSlik, imaSliko)) {
                Log.w(TAG, "HEVC: dekoder po $poslanihSlik slikah ni vrnil nobene - sejo zahtevamo znova s H.264")
                naStanje(Stanje.KODEK, "")
                tece = false
                break
            }
            val zdaj = SystemClock.elapsedRealtime()
            if (zdaj - zadnjePorocilo >= 1000) {
                val sekunde = (zdaj - zadnjePorocilo) / 1000.0
                naStatistiko(Statistika(slik, slik / sekunde, bajtov * 8 / 1e6 / sekunde,
                    zadnjaZakasnitev, sirina, visina, imelZvok, zastojev, najvecVDekoderju,
                    if (slik > 0) vsotaZakasnitev.toDouble() / slik else 0.0))
                najvecVDekoderju = 0; vsotaZakasnitev = 0L
                slik = 0; bajtov = 0; zadnjePorocilo = zdaj; imelZvok = false; zastojev = 0
            }
        }
        } finally {
            izhodnaNit?.interrupt()
        }
    }

    /** Vse, kar je dekoder ze naredil, takoj na zaslon. */
    @Synchronized
    private fun izprazni(kodek: MediaCodec, prvicCakajUs: Long = 0L) {
        var cakaj = prvicCakajUs
        while (true) {
            val i = kodek.dequeueOutputBuffer(info, cakaj)
            cakaj = 0L
            if (i < 0) break
            val zdajSlika = SystemClock.elapsedRealtime()
            zadnjaZakasnitev = zdajSlika - info.presentationTimeUs / 1000
            // Kljucna slika po zahtevi gre na zaslon: uspeh za Presojevalca.
            preveriKljucniIzris(info.presentationTimeUs)
            // Samodejni zamik slike (sinhro): izris ob casu, ko zvok dohiti; brez zamika obstojeca pot spodaj.
            val zamik = zamikSlikeMs
            if (zamik > 0L) {
                izrisiZZamikom(kodek, i, zdajSlika, zamik)
                continue
            }
            if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                // Koliko slik je bilo v dekoderju, ko je ta prisla ven (vkljucno z njo): 1 = brez zadrzevanja.
                najvecVDekoderju = maxOf(najvecVDekoderju, (slikNoter - slikVen).toInt())
                slikVen++
                vsotaZakasnitev += zadnjaZakasnitev
            }
            // Meritev: prva prava slika (ne nastavitve), poslana na zaslon z render=true.
            val casPrava = info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
            kodek.releaseOutputBuffer(i, true)
            if (casPrava && casPrvaPrikazana.compareAndSet(false, true)) cas("prva_slika_prikazana", "velikost=${info.size}")
            slik++
            imaSliko = true
            if (zadnjaSlika != 0L && zdajSlika - zadnjaSlika > ZASTOJ_MS) zastojev++
            zadnjaSlika = zdajSlika
        }
    }

    private fun posljiNal(kodek: MediaCodec, vir: ByteArray, od: Int, do_: Int) {
        val vrsta = vrstaNal(vir, od)
        if (ZaslonKodek.cakaNaSliko(hevc, vrsta)) {
            // HEVC: nastavitve (VPS, SPS, PPS) in spremne enote pocakajo na sliko in gredo z njo v enem
            // medpomnilniku - locenih nastavitev vsi strojni dekoderji ne sprejmejo.
            predSliko = if (predSliko.size + (do_ - od) > NAJVEC_PRED_SLIKO) ByteArray(0)
                else predSliko + vir.copyOfRange(od, do_)
            return
        }
        val spredaj = predSliko
        predSliko = ByteArray(0)
        val dolzina = spredaj.size + (do_ - od)
        val i = vhodniMedpomnilnik(kodek)
        if (i < 0) return
        val medpomnilnik = kodek.getInputBuffer(i) ?: return
        medpomnilnik.clear()
        if (dolzina > medpomnilnik.remaining()) {
            // Enote NAL ni mogoce razbiti na pol, zato jo raje izpustimo kot da seja pade;
            // naslednji kljucni okvir sliko spet postavi na noge.
            Log.w(TAG, "Prevelika enota NAL ($dolzina B), izpuscena.")
            // Dekoder enote ni mogel sprejeti, slika je pokvarjena do kljucne: zahtevaj jo.
            zahtevajKljucni(ZaslonKljucniOkvir.Razlog.NAPAKA_DEKODIRNIKA)
            kodek.queueInputBuffer(i, 0, 0, 0, 0)
            return
        }
        if (spredaj.isNotEmpty()) medpomnilnik.put(spredaj)
        medpomnilnik.put(vir, od, do_ - od)
        // Kljuc za zig in kljucno sliko: pts v naslednji vrstici je ista ura, prebrana tik za tem (enak ali +1 ms).
        // Ena sama prebrana ura: pts in kljuc za zig / kljucno sliko sta vedno enaka.
        val zdajUs = SystemClock.elapsedRealtime() * 1000
        val ptsUs = if (brezAu) ZaslonSinhro.enolicenPts(zdajUs, zadnjiPtsUs) else zdajUs
        zadnjiPtsUs = ptsUs
        if (!ZaslonKodek.jeNastavitev(hevc, vrsta)) {
            val zig = zigSlike
            if (zig != null) zigiSlik.dodaj(ptsUs, zig)
            if (cakaKljucni && vsebujeKljucno(vir, od, do_)) {
                cakaKljucni = false
                kljucniPtsUs.set(ptsUs)
            }
        }
        val zastavice = if (ZaslonKodek.jeNastavitev(hevc, vrsta)) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
        // Cas oddaje uporabimo kot zig: ob izhodu iz dekoderja iz njega izracunamo zakasnitev.
        kodek.queueInputBuffer(i, 0, dolzina, ptsUs, zastavice)
        // Meritev: prva enota slike (ne nastavitve) v dekoderju.
        if (zastavice == 0 && casPrvaVDekoder.compareAndSet(false, true)) cas("prva_slika_v_dekoder", "bajti=$dolzina nal=$vrsta")
        if (zastavice == 0) slikNoter++
        if (hevc && !imaSliko) poslanihSlik++
    }

    /**
     * Vhodni medpomnilnik dekoderja. Kadar je dekoder poln, najprej oddamo, kar je ze naredil, in pocakamo - enote ne
     * izpustimo: pri dolgi skupini slik (kljucna slika na 10 s) bi izpuscena enota pokvarila sliko do naslednje kljucne.
     */
    private fun vhodniMedpomnilnik(kodek: MediaCodec): Int {
        while (tece) {
            val i = kodek.dequeueInputBuffer(10_000)
            if (i >= 0) return i
            izprazni(kodek)
        }
        return -1
    }

    private fun vrstaNal(b: ByteArray, zacetek: Int): Int {
        var i = zacetek + 3
        if (zacetek + 3 < b.size && b[zacetek + 2] == 0.toByte()) i = zacetek + 4
        return if (i < b.size) ZaslonKodek.vrstaEnote(hevc, b[i].toInt()) else -1
    }

    /** Prvi zacetek NAL enote od [od] naprej ali -1. */
    private fun zacetekNal(b: ByteArray, od: Int): Int {
        var i = maxOf(od, 0)
        while (i + 3 < b.size) {
            if (b[i] == 0.toByte() && b[i + 1] == 0.toByte()) {
                if (b[i + 2] == 1.toByte()) return i
                if (b[i + 2] == 0.toByte() && b[i + 3] == 1.toByte()) return i
            }
            i++
        }
        return -1
    }

    private fun preberiVrstico(vhod: InputStream, najvec: Int = 512): String {
        val sb = StringBuilder()
        while (sb.length < najvec) {
            val z = vhod.read()
            if (z < 0 || z == '\n'.code) break
            sb.append(z.toChar())
        }
        return sb.toString()
    }

    private companion object {
        /** Izhodna nit caka na dekodirano sliko najvec toliko (mikrosekunde), nato preveri, ali seja se tece. */
        const val IZHOD_CAKAJ_US = 50_000L
        /** Najdaljsa tisina povezave, preden jo razglasimo za prekinjeno. */
        const val TISINA_MS = 10_000
        /** Presledek med zaporednima slikama, ki ga oko pri 60 slikah na sekundo ze zazna kot zatik. */
        const val ZASTOJ_MS = 50L
        const val TAG = "SafeerOsZaslon"
        /** Oznaka merilnega dnevnika (zastoj zvoka 4 s): adb logcat -s SafeerZaslonCas. */
        const val CAS_TAG = "SafeerZaslonCas"
        /** Vrsti okvirjev; morata biti enaki kot v core/link_zaslon.py. */
        const val OKVIR_SLIKA = 1
        const val OKVIR_ZVOK = 2
        const val OKVIR_OBVESTILO = 3
        /** Zig zajema za naslednji okvir slike ali zvoka (glava "zig": {"okvir": 4}). */
        const val OKVIR_ZIG = ZaslonSinhro.OKVIR_ZIG
        /** Vec kot toliko v enem okvirju ne posiljamo; vecje stevilo pomeni pokvarjen pretok. */
        const val NAJVECJI_OKVIR = 8 * 1024 * 1024
        /** Nastavitve HEVC pred sliko so nekaj sto bajtov; vec kot toliko pomeni pokvarjen pretok. */
        const val NAJVEC_PRED_SLIKO = 64 * 1024
    }
}
