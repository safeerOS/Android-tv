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
import java.net.InetSocketAddress
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
    private val naslov: String,
    private val vrata: Int,
    private val odtis: String,
    private val zeton: String,
    private val naStanje: (Stanje, String) -> Unit,
    private val naStatistiko: (Statistika) -> Unit,
) {
    /** PRAZNO: racunalnik javi, da na locenem zaslonu ni vec programa (besedilo = razlog). */
    enum class Stanje { POVEZUJEM, TECE, KONCANO, NAPAKA, PRAZNO }

    /** Kar lahko izmerimo na televizorju: slike, pretok in koliko casa slika stoji v dekoderju. */
    data class Statistika(val slik: Int, val naSekundo: Double, val megabitov: Double,
                          val dekoderMs: Long, val sirina: Int, val visina: Int, val zvok: Boolean)

    @Volatile private var tece = false
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
            val (tovarna, _) = Pin.tovarna(odtis)
            val goli = Socket()
            goli.connect(InetSocketAddress(naslov, vrata), 8_000)
            goli.tcpNoDelay = true                     // brez Naglejevega zbiranja: vsak paket takoj
            val s = tovarna.createSocket(goli, naslov, vrata, true) as SSLSocket
            s.startHandshake()
            vticnica = s
            val izhodniTok: OutputStream = s.outputStream
            val vhod: InputStream = s.inputStream
            izhodniTok.write(("SAFEER-ZASLON $zeton\n").toByteArray())
            izhodniTok.flush()
            izhod = izhodniTok
            val glava = JSONObject(preberiVrstico(vhod))
            val sirina = glava.optInt("w", 1920)
            val visina = glava.optInt("h", 1080)
            val fps = glava.optInt("fps", 30)
            kodek = pripraviKodek(surface, sirina, visina, fps)
            glava.optJSONObject("zvok")?.let { zvocnik = pripraviZvok(it.optInt("hz", 48000), it.optInt("kanali", 2)) }
            naStanje(Stanje.TECE, "")
            crpaj(vhod, kodek, sirina, visina)
            naStanje(Stanje.KONCANO, "")
        } catch (e: Throwable) {
            if (tece) {
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
        val oblika = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, sirina, visina)
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
        val kodek = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
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
     * Pretok so okvirji: ena bajt vrste, styri bajti dolzine, vsebina. Slika gre v dekoder (prej jo
     * razrezemo na enote NAL, ker MediaCodec hoce eno na medpomnilnik), zvok pa naravnost v
     * AudioTrack. Zvok pisemo neblokirajoce: ce bi cakal, bi ustavil sliko - raje izpustimo nekaj
     * zvoka kot da slika obstane.
     */
    private fun crpaj(vhod: InputStream, kodek: MediaCodec, sirina: Int, visina: Int) {
        val podatkovni = DataInputStream(vhod)
        val info = MediaCodec.BufferInfo()
        val glava = ByteArray(5)
        var ostanek = ByteArray(0)
        var slik = 0
        var bajtov = 0L
        var zadnjePorocilo = SystemClock.elapsedRealtime()
        var zadnjaZakasnitev = 0L
        var imelZvok = false
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
                val konec = try { JSONObject(String(telo, Charsets.UTF_8)).optString("konec") } catch (_: Throwable) { "" }
                if (konec.isNotEmpty()) { naStanje(Stanje.PRAZNO, konec); tece = false; break }
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

            // Vse, kar je dekoder ze naredil, takoj na zaslon.
            while (true) {
                val i = kodek.dequeueOutputBuffer(info, 0)
                if (i < 0) break
                zadnjaZakasnitev = SystemClock.elapsedRealtime() - info.presentationTimeUs / 1000
                kodek.releaseOutputBuffer(i, true)
                slik++
            }
            val zdaj = SystemClock.elapsedRealtime()
            if (zdaj - zadnjePorocilo >= 1000) {
                val sekunde = (zdaj - zadnjePorocilo) / 1000.0
                naStatistiko(Statistika(slik, slik / sekunde, bajtov * 8 / 1e6 / sekunde,
                    zadnjaZakasnitev, sirina, visina, imelZvok))
                slik = 0; bajtov = 0; zadnjePorocilo = zdaj; imelZvok = false
            }
        }
    }

    private fun posljiNal(kodek: MediaCodec, vir: ByteArray, od: Int, do_: Int) {
        val i = kodek.dequeueInputBuffer(20_000)
        if (i < 0) return
        val medpomnilnik = kodek.getInputBuffer(i) ?: return
        medpomnilnik.clear()
        if (do_ - od > medpomnilnik.remaining()) {
            // Enote NAL ni mogoce razbiti na pol, zato jo raje izpustimo kot da seja pade;
            // naslednji kljucni okvir (vsako sekundo) sliko spet postavi na noge.
            Log.w(TAG, "Prevelika enota NAL (${do_ - od} B), izpuscena.")
            kodek.queueInputBuffer(i, 0, 0, 0, 0)
            return
        }
        medpomnilnik.put(vir, od, do_ - od)
        val vrsta = vrstaNal(vir, od)
        val zastavice = if (vrsta == 7 || vrsta == 8) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
        // Cas oddaje uporabimo kot zig: ob izhodu iz dekoderja iz njega izracunamo zakasnitev.
        kodek.queueInputBuffer(i, 0, do_ - od, SystemClock.elapsedRealtime() * 1000, zastavice)
    }

    private fun vrstaNal(b: ByteArray, zacetek: Int): Int {
        var i = zacetek + 3
        if (zacetek + 3 < b.size && b[zacetek + 2] == 0.toByte()) i = zacetek + 4
        return if (i < b.size) (b[i].toInt() and 0x1f) else -1
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
        const val TAG = "SafeerOsZaslon"
        /** Vrsti okvirjev; morata biti enaki kot v core/link_zaslon.py. */
        const val OKVIR_SLIKA = 1
        const val OKVIR_ZVOK = 2
        const val OKVIR_OBVESTILO = 3
        /** Vec kot toliko v enem okvirju ne posiljamo; vecje stevilo pomeni pokvarjen pretok. */
        const val NAJVECJI_OKVIR = 8 * 1024 * 1024
    }
}
