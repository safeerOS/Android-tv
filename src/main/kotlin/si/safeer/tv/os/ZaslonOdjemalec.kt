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
                          val zastojev: Int = 0)

    @Volatile private var tece = false
    /** Kodek toka, kot ga pove racunalnik v glavi (`kodek`): HEVC ali (privzeto, starejsi Safeer) H.264. */
    @Volatile private var hevc = false
    // Stanje dekodiranja; bere in pise ga samo nit toka.
    private val info = MediaCodec.BufferInfo()
    private var slik = 0
    private var zastojev = 0
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
            val sirina = glava.optInt("w", 1920)
            val visina = glava.optInt("h", 1080)
            val fps = glava.optInt("fps", 30)
            hevc = glava.optString("kodek") == "hevc"
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
            crpaj(vhod, kodek, sirina, visina)
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
        val kodek = MediaCodec.createDecoderByType(vrsta)
        kodek.configure(oblika, surface, null, 0)
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
     */
    private fun crpaj(vhod: InputStream, kodek: MediaCodec, sirina: Int, visina: Int) {
        val podatkovni = DataInputStream(vhod)
        val glava = ByteArray(5)
        var ostanek = ByteArray(0)
        var bajtov = 0L
        var zadnjePorocilo = SystemClock.elapsedRealtime()
        var imelZvok = false
        slik = 0; zastojev = 0; zadnjaSlika = 0L; zadnjaZakasnitev = 0L; predSliko = ByteArray(0); poslanihSlik = 0
        while (tece) {
            podatkovni.readFully(glava)
            val vrsta = glava[0].toInt() and 0xff
            val dolzina = ((glava[1].toInt() and 0xff) shl 24) or ((glava[2].toInt() and 0xff) shl 16) or
                ((glava[3].toInt() and 0xff) shl 8) or (glava[4].toInt() and 0xff)
            if (dolzina <= 0 || dolzina > NAJVECJI_OKVIR) break
            val telo = ByteArray(dolzina)
            podatkovni.readFully(telo)
            bajtov += dolzina + glava.size
            if (vrsta == OKVIR_ZVOK) {
                imelZvok = true
                try { zvocnik?.write(telo, 0, dolzina, AudioTrack.WRITE_NON_BLOCKING) } catch (_: Throwable) { }
                continue
            }
            if (vrsta == OKVIR_OBVESTILO) {
                val obvestilo = try { JSONObject(String(telo, Charsets.UTF_8)) } catch (_: Throwable) { null }
                val konec = obvestilo?.optString("konec").orEmpty()
                if (konec.isNotEmpty()) { naStanje(Stanje.PRAZNO, konec); tece = false; break }
                if (obvestilo != null) try { naObvestilo(obvestilo) } catch (_: Throwable) { }
                continue
            }
            if (vrsta != OKVIR_SLIKA) continue
            ostanek = ostanek + telo
            var od = zacetekNal(ostanek, 0)
            if (od < 0) continue
            var naslednji = zacetekNal(ostanek, od + 3)
            while (naslednji > 0) {
                posljiNal(kodek, ostanek, od, naslednji)
                od = naslednji
                naslednji = zacetekNal(ostanek, od + 3)
            }
            ostanek = ostanek.copyOfRange(od, ostanek.size)   // zacetek naslednje enote
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
                    zadnjaZakasnitev, sirina, visina, imelZvok, zastojev))
                slik = 0; bajtov = 0; zadnjePorocilo = zdaj; imelZvok = false; zastojev = 0
            }
        }
    }

    /** Vse, kar je dekoder ze naredil, takoj na zaslon. */
    private fun izprazni(kodek: MediaCodec) {
        while (true) {
            val i = kodek.dequeueOutputBuffer(info, 0)
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
