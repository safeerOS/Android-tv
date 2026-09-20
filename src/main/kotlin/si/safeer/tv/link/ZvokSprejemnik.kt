package si.safeer.tv.link

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * Zvok racunalnika na tej napravi (Safeer Link, ukaz `audio.play`) - kot zvocnik Bluetooth, le
 * da je zvocnik ta televizor ali tablica v domacem omrezju.
 *
 * Racunalnik (Safeer Control, core/link_zvok.py) po hubu poslje samo dogovor: vrata, odtis svojega
 * potrdila, enkratni zeton in svoje naslove. Zvok nato tece **neposredno** z racunalnika po TLS s
 * pripetim potrdilom (kot deljenje zaslona) - nic ne gre skozi hub.
 *
 * Pozdrav: `SAFEER-ZVOK <zeton>\n`, odgovor ena vrstica JSON (`{"zvok":{"hz","kanali"}}`), nato
 * okvirji: 1 bajt vrste (2 = zvok PCM s16le, 3 = obvestilo), 4 bajti dolzine, vsebina.
 *
 * Tece v procesu CastReceiverService (storitev v ospredju vrste mediaPlayback), zato zvok igra
 * tudi, ko je na zaslonu druga aplikacija. Naenkrat ena seja; nova zamenja staro.
 */
object ZvokSprejemnik {
    private const val TAG = "SafeerZvok"
    private const val OKVIR_ZVOK = 2
    private const val OKVIR_OBVESTILO = 3
    private const val NAJVECJI_OKVIR = 1 shl 20

    @Volatile private var seja = 0
    @Volatile private var vticnica: Socket? = null
    @Volatile var racunalnik: String = ""
        private set

    fun tece(): Boolean = vticnica != null

    /** Ukaz `audio.play`: poveze se na racunalnik in predvaja. Takoj vrne izid; zvok tece v ozadju. */
    fun zacni(context: Context, p: JSONObject): Daljinec.Izid {
        val vrata = p.optInt("port", 0)
        val odtis = p.optString("fp", "")
        val zeton = p.optString("token", "")
        val naslovi = mutableListOf<String>()
        p.optJSONArray("hosts")?.let { for (i in 0 until it.length()) it.optString(i, "").takeIf { n -> n.isNotBlank() }?.let(naslovi::add) }
        p.optString("host", "").takeIf { it.isNotBlank() }?.let { if (it !in naslovi) naslovi.add(0, it) }
        if (vrata !in 1..65535 || odtis.length < 32 || zeton.length < 16 || naslovi.isEmpty()) {
            return Daljinec.Izid(false, "Nepopoln ukaz za zvok", koda = "neveljavno")
        }
        val ime = p.optString("name", "").take(60).ifBlank { "računalnik" }
        ustavi()
        val moja = ++seja
        val app = context.applicationContext
        Thread({ teci(app, moja, naslovi, vrata, odtis, zeton, ime) }, "safeer-zvok").apply { isDaemon = true; start() }
        return Daljinec.Izid(true, "Zvok z računalnika $ime")
    }

    /** Ukaz `audio.stop` ali nova seja: zapre povezavo; racunalnik vrne zvok na svoj izhod. */
    fun ustavi(): Daljinec.Izid {
        seja++
        val s = vticnica
        vticnica = null
        try { s?.close() } catch (_: Throwable) { }
        return Daljinec.Izid(true, if (s != null) "Zvok ustavljen" else "Zvok ni tekel")
    }

    private fun teci(context: Context, moja: Int, naslovi: List<String>, vrata: Int, odtis: String, zeton: String, ime: String) {
        var zvocnik: AudioTrack? = null
        var s: SSLSocket? = null
        try {
            s = povezi(naslovi, vrata, odtis) ?: run {
                Log.w(TAG, "Racunalnika ni bilo mogoce doseci (${naslovi.joinToString()}:$vrata)")
                return
            }
            if (moja != seja) return
            vticnica = s
            val izhod = s.outputStream
            izhod.write("SAFEER-ZVOK $zeton\n".toByteArray())
            izhod.flush()
            val vhod = s.inputStream
            val glava = JSONObject(preberiVrstico(vhod))
            val zv = glava.optJSONObject("zvok") ?: JSONObject()
            // Ko zvok tece, tisina ni napaka: racunalnik povezavo zapre sam, ko sejo konca.
            s.soTimeout = 0
            zvocnik = pripravi(zv.optInt("hz", 48000), zv.optInt("kanali", 2)) ?: return
            racunalnik = ime
            obvesti(context, "🔊 $ime")
            Log.i(TAG, "Zvok z racunalnika $ime tece")
            crpaj(DataInputStream(vhod), zvocnik, moja)
        } catch (e: java.io.EOFException) {
            Log.i(TAG, "Racunalnik je zvok koncal")
        } catch (e: java.net.SocketException) {
            Log.i(TAG, "Povezava z racunalnikom zaprta")
        } catch (e: Throwable) {
            if (moja == seja) Log.w(TAG, "Zvok: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        } finally {
            try { zvocnik?.pause(); zvocnik?.flush(); zvocnik?.release() } catch (_: Throwable) { }
            try { s?.close() } catch (_: Throwable) { }
            if (vticnica === s) vticnica = null
            if (moja == seja) racunalnik = ""
        }
    }

    private fun povezi(naslovi: List<String>, vrata: Int, odtis: String): SSLSocket? {
        val (tovarna, _) = si.safeer.tv.os.Pin.tovarna(odtis)
        for (n in naslovi) {
            try {
                val goli = Socket()
                goli.connect(InetSocketAddress(n, vrata), 4_000)
                goli.tcpNoDelay = true
                val s = tovarna.createSocket(goli, n, vrata, true) as SSLSocket
                s.soTimeout = 15_000
                s.startHandshake()
                return s
            } catch (e: Throwable) {
                Log.i(TAG, "$n:$vrata ni dosegljiv: ${e.message}")
            }
        }
        return null
    }

    /**
     * Medpomnilnik ~200 ms: brez slike ni cesa dohitevati, zato raje malo vec rezerve za tresenje
     * Wi-Fi kot pa prekinitve. Pisemo blokirajoce - zvok je edini tok in ne sme izgubljati vzorcev.
     */
    private fun pripravi(hz: Int, kanali: Int): AudioTrack? = try {
        val razpored = if (kanali >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val najmanj = AudioTrack.getMinBufferSize(hz, razpored, AudioFormat.ENCODING_PCM_16BIT)
        val velikost = maxOf(najmanj * 2, hz * maxOf(1, kanali) * 2 / 5)
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(hz)
                .setChannelMask(razpored).build())
            .setBufferSizeInBytes(velikost)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }
    } catch (e: Throwable) {
        Log.w(TAG, "AudioTrack: ${e.message}")
        null
    }

    private fun crpaj(vhod: DataInputStream, zvocnik: AudioTrack, moja: Int) {
        val glava = ByteArray(5)
        var telo = ByteArray(4096)
        while (moja == seja) {
            vhod.readFully(glava)
            val vrsta = glava[0].toInt() and 0xff
            val dolzina = ((glava[1].toInt() and 0xff) shl 24) or ((glava[2].toInt() and 0xff) shl 16) or
                ((glava[3].toInt() and 0xff) shl 8) or (glava[4].toInt() and 0xff)
            if (dolzina <= 0 || dolzina > NAJVECJI_OKVIR) break
            if (telo.size < dolzina) telo = ByteArray(dolzina)
            vhod.readFully(telo, 0, dolzina)
            when (vrsta) {
                OKVIR_ZVOK -> zvocnik.write(telo, 0, dolzina)
                OKVIR_OBVESTILO -> {
                    val o = try { JSONObject(String(telo, 0, dolzina, Charsets.UTF_8)) } catch (_: Throwable) { null }
                    if (!o?.optString("konec").isNullOrEmpty()) break
                }
            }
        }
    }

    private fun preberiVrstico(vhod: InputStream, najvec: Int = 1024): String {
        val sb = StringBuilder()
        while (sb.length < najvec) {
            val z = vhod.read()
            if (z < 0 || z == '\n'.code) break
            sb.append(z.toChar())
        }
        return sb.toString()
    }

    private fun obvesti(context: Context, besedilo: String) {
        Handler(Looper.getMainLooper()).post {
            try { Toast.makeText(context, besedilo, Toast.LENGTH_SHORT).show() } catch (_: Throwable) { }
        }
    }
}
