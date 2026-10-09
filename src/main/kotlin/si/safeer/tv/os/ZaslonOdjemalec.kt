package si.safeer.tv.os

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import org.json.JSONObject
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
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
                          /** Koliko prevelikih enot NAL smo v tem obdobju izpustili (niso sle v medpomnilnik dekoderja). */
                          val izpusceno: Int = 0)

    @Volatile private var tece = false
    /** Kodek toka, kot ga pove racunalnik v glavi (`kodek`): HEVC ali (privzeto, starejsi Safeer) H.264. */
    @Volatile private var hevc = false
    /** Kvantizator toka, kot ga pove racunalnik v glavi (`qp`); 0, ce ga ne pove (starejsi Safeer). */
    @Volatile private var qp = 0
    /** Zadnja zakasnitev tja in nazaj (ms), kot jo pove utrip racunalnika (`z`); 0, dokler je ne pove. */
    @Volatile private var zadnjiRttMs = 0

    /** Tok za »Podatke o povezavi«: kodek, kvantizator in zakasnitev do racunalnika, npr. »HEVC q20 · 104 ms«. */
    fun opisToka(): String {
        val rtt = zadnjiRttMs
        return (if (hevc) "HEVC" else "H.264") + (if (qp > 0) " q$qp" else "") +
            (if (rtt > 0) " \u00b7 " + ZaslonUtrip.opisRtt(rtt) else "")
    }
    // Stanje dekodiranja; bere in pise ga samo nit toka.
    private val info = MediaCodec.BufferInfo()
    private var slik = 0
    private var zastojev = 0
    private var izpusceno = 0
    /** Zadnja sekunda, kot smo jo sporocili ([naStatistiko]); gre z odgovorom na utrip racunalniku. */
    private var zadnjaStatistika: Statistika? = null
    private var zadnjaSlika = 0L
    private var zadnjaZakasnitev = 0L
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
    private var izhod: OutputStream? = null
    private var zvocnik: AudioTrack? = null
    private val posiljalnik = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "safeer-zaslon-vnos").also { it.isDaemon = true }
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
                    NeposrednaPovezava.povezi(naslovi, vrata, odtis, oznaka = TAG, tece = t)
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
            Log.i(TAG, "Zaslon: povezava " + (if (prekHuba) "prek Global Linka" else "neposredno") +
                ", rokovanje ${izid.casMs} ms")
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
            val sirina = glava.optInt("w", 1920)
            val visina = glava.optInt("h", 1080)
            val fps = glava.optInt("fps", 30)
            hevc = glava.optString("kodek") == "hevc"
            qp = glava.optInt("qp", 0)
            // Utrip (meritev zakasnitve, ZaslonUtrip): odgovarjamo samo, ce ga racunalnik v glavi potrdi. Starejsi
            // racunalnik kljuca nima in od nas ne dobi nicesar novega.
            val utrip = glava.optBoolean("rtt", false)
            imaSliko = false
            kodek = try { pripraviKodek(surface, sirina, visina, fps) } catch (e: Throwable) {
                if (!hevc) throw e
                // Dekoderja HEVC ni mogoce pripraviti: gledalec sejo zahteva znova s H.264.
                Log.w(TAG, "HEVC: dekoderja ni mogoce pripraviti (${e.message})")
                naStanje(Stanje.KODEK, "")
                return
            }
            glava.optJSONObject("zvok")?.let { zvocnik = pripraviZvok(it.optInt("hz", 48000), it.optInt("kanali", 2)) }
            // Slika in zvok tecejo ves cas (tudi med pavzo). Ce 10 s ne pride nic, povezave ni vec
            // (izpad Wi-Fi ne zapre vticnice) - branje pade in seja se vrne sama, namesto zamrznjene slike.
            s.soTimeout = TISINA_MS
            naStanje(Stanje.TECE, "")
            // Racunalnik, ki vsak okvir poslje kot celo sliko ("au", Safeer OS za Windows 1.0.48), dovoli, da jo dekoder
            // dobi takoj; brez tega zadnja enota slike caka na zacetek naslednje (en interval slike zamika).
            crpaj(vhod, kodek, sirina, visina, glava.optBoolean("au", false), utrip, izid.casMs)
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
            try { zvocnik?.pause(); zvocnik?.flush(); zvocnik?.release() } catch (_: Throwable) { }
            zvocnik = null
            try { kodek?.stop() } catch (_: Throwable) { }
            try { kodek?.release() } catch (_: Throwable) { }
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
        return kodek
    }

    /**
     * Zvok je surov PCM, zato ga ni treba dekodirati - gre naravnost v AudioTrack. Medpomnilnik je
     * majhen (okrog 100 ms), ker je cilj, da zvok ne zaostaja za sliko.
     */
    private fun pripraviZvok(hz: Int, kanali: Int): AudioTrack? = try {
        val razpored = if (kanali >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val najmanj = AudioTrack.getMinBufferSize(hz, razpored, AudioFormat.ENCODING_PCM_16BIT)
        val velikost = maxOf(najmanj, hz * kanali * 2 / 10)          // ~100 ms
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
            .build().also { it.play(); Log.i(TAG, "Zvok: $hz Hz, $kanali kanala, medpomnilnik $velikost B") }
    } catch (e: Throwable) {
        Log.w(TAG, "Zvoka ni bilo mogoce pripraviti: ${e.message}")
        null
    }

    /**
     * Pretok so okvirji: en bajt vrste, stirje bajti dolzine, vsebina. Slika gre v dekoder (prej jo
     * razrezemo na enote NAL), zvok pa naravnost v AudioTrack. Zvok pisemo neblokirajoce: ce bi cakal,
     * bi ustavil sliko - raje izpustimo nekaj zvoka kot da slika obstane.
     *
     * [utrip]: racunalnik je v glavi potrdil utrip (`rtt`) - na vsakega odgovorimo po kanalu za vnos. [rokMs] je cas
     * povezovanja (NeposrednaPovezava.Izid.casMs); racunalnik ga dobi enkrat, s prvim odgovorom.
     */
    private fun crpaj(vhod: InputStream, kodek: MediaCodec, sirina: Int, visina: Int, celeSlike: Boolean = false,
                      utrip: Boolean = false, rokMs: Long = 0L) {
        val podatkovni = DataInputStream(vhod)
        val glava = ByteArray(5)
        var ostanek = ByteArray(0)
        var bajtov = 0L
        // Vsi prejeti bajti seje z glavami okvirjev (slika, zvok, obvestila): racunalnik iz razlik med odgovori na
        // utrip izracuna, koliko pride do nas, neodvisno od zakasnitve odgovorov.
        var bajtovSkupaj = 0L
        var prviOdmev = true
        var zadnjePorocilo = SystemClock.elapsedRealtime()
        var imelZvok = false
        slik = 0; zastojev = 0; zadnjaSlika = 0L; zadnjaZakasnitev = 0L; predSliko = ByteArray(0); poslanihSlik = 0
        izpusceno = 0; zadnjaStatistika = null; zadnjiRttMs = 0
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
            bajtov += dolzina + glava.size
            bajtovSkupaj += dolzina + glava.size
            if (vrsta == OKVIR_ZVOK) {
                imelZvok = true
                try { zvocnik?.write(telo, 0, dolzina, AudioTrack.WRITE_NON_BLOCKING) } catch (_: Throwable) { }
                continue
            }
            if (vrsta == OKVIR_OBVESTILO) {
                val obvestilo = try { JSONObject(String(telo, Charsets.UTF_8)) } catch (_: Throwable) { null }
                val konec = obvestilo?.optString("konec").orEmpty()
                if (konec.isNotEmpty()) {
                    // »prevzeto«: zaslon je odprla druga naprava (Safeer za Windows od 1.0.49) - kateri, povemo naprej.
                    val zakaj = if (konec == "prevzeto") "prevzeto:" + obvestilo?.optString("naprava").orEmpty() else konec
                    naStanje(Stanje.PRAZNO, zakaj); tece = false; break
                }
                // Utrip racunalnika ni za zaslon: ujamemo ga pred naObvestilo. Odgovor (samo ce ga je glava potrdila)
                // gre kot vnos - ena vrstica na niti posiljalnika, za istim zaklepom kot tipke.
                val utripRacunalnika = obvestilo?.optJSONObject("rtt")
                if (utripRacunalnika != null) {
                    if (utrip) {
                        val st = zadnjaStatistika
                        try {
                            posljiVnos(JSONObject(ZaslonUtrip.odgovorRtt(
                                utripRacunalnika.optLong("n"), utripRacunalnika.optLong("t"),
                                SystemClock.elapsedRealtime(), bajtovSkupaj,
                                st?.naSekundo ?: 0.0, st?.megabitov ?: 0.0, st?.dekoderMs ?: 0L,
                                st?.zastojev ?: 0, st?.izpusceno ?: 0,
                                pot = if (prviOdmev) (if (prekHuba) "hub" else "neposredno") else null,
                                rokMs = if (prviOdmev && rokMs > 0) rokMs else null)))
                            prviOdmev = false
                        } catch (_: Throwable) { }   // meritev ne sme ustaviti slike
                    }
                    zadnjiRttMs = utripRacunalnika.optInt("z", zadnjiRttMs)
                    continue
                }
                if (obvestilo != null) try { naObvestilo(obvestilo) } catch (_: Throwable) { }
                continue
            }
            if (vrsta != OKVIR_SLIKA) continue
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
                    val st = Statistika(slik, slik / sekunde, bajtov * 8 / 1e6 / sekunde,
                        zadnjaZakasnitev, sirina, visina, imelZvok, zastojev, izpusceno)
                    zadnjaStatistika = st
                    naStatistiko(st)
                    slik = 0; bajtov = 0; zadnjePorocilo = zdajC; imelZvok = false; zastojev = 0; izpusceno = 0
                }
                continue
            }
            ostanek = ostanek + telo
            var od = zacetekNal(ostanek, 0)
            if (od < 0) continue
            var naslednji = zacetekNal(ostanek, od + 3)
            while (naslednji > 0) {
                posljiNal(kodek, ostanek, od, naslednji)
                od = naslednji
                naslednji = zacetekNal(ostanek, od + 3)
            }
            if (celeSlike) {
                posljiNal(kodek, ostanek, od, ostanek.size)    // okvir je cela slika: zadnja enota ne caka
                ostanek = ByteArray(0)
            } else {
                ostanek = ostanek.copyOfRange(od, ostanek.size)   // zacetek naslednje enote
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
                val st = Statistika(slik, slik / sekunde, bajtov * 8 / 1e6 / sekunde,
                    zadnjaZakasnitev, sirina, visina, imelZvok, zastojev, izpusceno)
                zadnjaStatistika = st
                naStatistiko(st)
                slik = 0; bajtov = 0; zadnjePorocilo = zdaj; imelZvok = false; zastojev = 0; izpusceno = 0
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
            kodek.releaseOutputBuffer(i, true)
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
            izpusceno++
            kodek.queueInputBuffer(i, 0, 0, 0, 0)
            return
        }
        if (spredaj.isNotEmpty()) medpomnilnik.put(spredaj)
        medpomnilnik.put(vir, od, do_ - od)
        val zastavice = if (ZaslonKodek.jeNastavitev(hevc, vrsta)) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
        // Cas oddaje uporabimo kot zig: ob izhodu iz dekoderja iz njega izracunamo zakasnitev.
        kodek.queueInputBuffer(i, 0, dolzina, SystemClock.elapsedRealtime() * 1000, zastavice)
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
        /** Vrsti okvirjev; morata biti enaki kot v core/link_zaslon.py. */
        const val OKVIR_SLIKA = 1
        const val OKVIR_ZVOK = 2
        const val OKVIR_OBVESTILO = 3
        /** Vec kot toliko v enem okvirju ne posiljamo; vecje stevilo pomeni pokvarjen pretok. */
        const val NAJVECJI_OKVIR = 8 * 1024 * 1024
        /** Nastavitve HEVC pred sliko so nekaj sto bajtov; vec kot toliko pomeni pokvarjen pretok. */
        const val NAJVEC_PRED_SLIKO = 64 * 1024
    }
}
