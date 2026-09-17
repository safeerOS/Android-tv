package si.safeer.tv.os

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import org.json.JSONObject
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
 */
class ZaslonOdjemalec(
    private val naslov: String,
    private val vrata: Int,
    private val odtis: String,
    private val zeton: String,
    private val naStanje: (Stanje, String) -> Unit,
    private val naStatistiko: (Statistika) -> Unit,
) {
    enum class Stanje { POVEZUJEM, TECE, KONCANO, NAPAKA }

    /** Kar lahko izmerimo na televizorju: slike, pretok in koliko casa slika stoji v dekoderju. */
    data class Statistika(val slik: Int, val naSekundo: Double, val megabitov: Double,
                          val dekoderMs: Long, val sirina: Int, val visina: Int)

    @Volatile private var tece = false
    private var nit: Thread? = null
    private var vticnica: Socket? = null

    fun zacni(surface: Surface) {
        if (tece) return
        tece = true
        nit = Thread({ teci(surface) }, "safeer-zaslon").also { it.start() }
    }

    fun ustavi() {
        tece = false
        try { vticnica?.close() } catch (_: Throwable) { }
        nit = null
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
            val izhod: OutputStream = s.outputStream
            val vhod: InputStream = s.inputStream
            izhod.write(("SAFEER-ZASLON $zeton\n").toByteArray())
            izhod.flush()
            val glava = JSONObject(preberiVrstico(vhod))
            val sirina = glava.optInt("w", 1920)
            val visina = glava.optInt("h", 1080)
            val fps = glava.optInt("fps", 30)
            kodek = pripraviKodek(surface, sirina, visina, fps)
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
     * Pretok razrezemo na enote NAL (zacetna koda 00 00 01 ali 00 00 00 01) in vsako predamo
     * dekoderju. SPS in PPS gresta z zastavico CODEC_CONFIG, sicer jih dekoder ne vzame za nastavitev.
     */
    private fun crpaj(vhod: InputStream, kodek: MediaCodec, sirina: Int, visina: Int) {
        val info = MediaCodec.BufferInfo()
        var zbrano = ByteArray(0)
        val kos = ByteArray(64 * 1024)
        var slik = 0
        var bajtov = 0L
        var zadnjePorocilo = SystemClock.elapsedRealtime()
        var zadnjaZakasnitev = 0L
        while (tece) {
            val prebrano = vhod.read(kos)
            if (prebrano <= 0) break
            bajtov += prebrano
            zbrano = zbrano + kos.copyOfRange(0, prebrano)
            var od = zacetekNal(zbrano, 0)
            if (od < 0) continue
            var naslednji = zacetekNal(zbrano, od + 3)
            while (naslednji > 0) {
                posljiNal(kodek, zbrano, od, naslednji)
                od = naslednji
                naslednji = zacetekNal(zbrano, od + 3)
            }
            zbrano = zbrano.copyOfRange(od, zbrano.size)   // ostanek je zacetek naslednje enote

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
                    zadnjaZakasnitev, sirina, visina))
                slik = 0; bajtov = 0; zadnjePorocilo = zdaj
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

    private companion object { const val TAG = "SafeerOsZaslon" }
}
