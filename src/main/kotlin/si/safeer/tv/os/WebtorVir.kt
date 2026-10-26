@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package si.safeer.tv.os

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Pregled 26 (Grok, T1, 25. 10. 2026): predvajalnik tok Webtor odpre SAMO na koncnem naslovu, ki ga je sonda preverila
 * ([Webtor.zaPredvajalnik]): brez preusmeritev (vsak 3xx je napaka - prej je Media3 sledil preusmeritvam istega protokola
 * tudi s preverjenega naslova) in samo na naslove IP, ki jih je preverila sonda ([WebtorHttp.pripet] - brez novega DNS, zato
 * brez rebindinga v domace omrezje). Piskotkov ni; glave so glave gostitelja koncnega naslova ([SpletniVir.glaveZa]).
 * [getUri] vrne naslov iz aplikacije (nadomestek `/resolve/`), nikoli koncnega z zetonom ponudnika.
 */
class WebtorVir(private val preverjen: Webtor.Preverjen, private val glave: Map<String, String>, private val rokMs: Long) :
    BaseDataSource(true) {
    private var odgovor: okhttp3.Response? = null
    private var vhod: InputStream? = null
    private var uri: Uri? = null
    private var ostane = C.LENGTH_UNSET.toLong()
    private var odprt = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val url = preverjen.koncni.toHttpUrlOrNull() ?: throw IOException("Webtor: neveljaven preverjen naslov")
        if (!url.isHttps) throw IOException("Webtor: preverjen naslov ni https")
        val klient = WebtorHttp.klient(WebtorHttp.pripet(url.host, preverjen.naslovi), rokMs)
        val g = okhttp3.Request.Builder().url(url).header("Accept-Encoding", "identity")
        for ((k, v) in glave) try { g.header(k, v) } catch (_: IllegalArgumentException) { }
        WebtorHttp.obseg(dataSpec.position, dataSpec.length)?.let { g.header("Range", it) }
        val r = try { klient.newCall(g.build()).execute() } catch (e: IOException) {
            throw IOException("Webtor: povezava (${e.javaClass.simpleName})")
        }
        if (r.code in 300..399) { r.close(); throw IOException("Webtor: preusmeritev s preverjenega naslova zavrnjena") }
        if (r.code !in 200..299) { r.close(); throw IOException("Webtor: koda ${r.code}") }
        val telo = r.body ?: run { r.close(); throw IOException("Webtor: brez vsebine") }
        odgovor = r
        val v = telo.byteStream()
        vhod = v
        // Streznik je na zahtevo z obsegom vrnil celo datoteko (200): preskoci do zacetka.
        var preskoci = if (r.code == 200 && dataSpec.position > 0) dataSpec.position else 0L
        val buf = ByteArray(16 * 1024)
        while (preskoci > 0) {
            val n = v.read(buf, 0, minOf(buf.size.toLong(), preskoci).toInt())
            if (n < 0) throw EOFException("Webtor: tok krajsi od zacetka")
            preskoci -= n
        }
        val dolzinaTelesa = telo.contentLength()
        ostane = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            dolzinaTelesa >= 0 -> if (r.code == 200) (dolzinaTelesa - dataSpec.position).coerceAtLeast(0L) else dolzinaTelesa
            else -> C.LENGTH_UNSET.toLong()
        }
        odprt = true
        transferStarted(dataSpec)
        return ostane
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (ostane == 0L) return C.RESULT_END_OF_INPUT
        val v = vhod ?: throw IOException("Webtor: vir ni odprt")
        val zelim = if (ostane < 0) length else minOf(length.toLong(), ostane).toInt()
        val n = v.read(buffer, offset, zelim)
        if (n < 0) {
            if (ostane > 0) throw EOFException("Webtor: tok se je koncal prezgodaj")
            return C.RESULT_END_OF_INPUT
        }
        if (ostane > 0) ostane -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try { vhod?.close() } catch (_: Throwable) { }
        try { odgovor?.close() } catch (_: Throwable) { }
        vhod = null; odgovor = null; uri = null
        if (odprt) { odprt = false; transferEnded() }
    }
}

/**
 * Pregled 26 (T1): vir predvajalnika izbere ob vsakem odprtju - tok Webtor prek [WebtorVir] (preverjen, brez preusmeritev,
 * pripeti naslovi), vse drugo prek [osnovni]. Tok na webtor.io, ki ni preverljiv, ne gre po stari poti (IOException iz
 * [Webtor.zaPredvajalnik], fail-closed). [close] prekine sondo, ki se izvaja (pregled 26, T4).
 */
class IzbirniVir(private val app: Context, private val osnovni: DataSource, private val glave: (String) -> Map<String, String>) :
    DataSource {
    private val poslusalci = ArrayList<TransferListener>()
    private var zdaj: DataSource? = null
    @Volatile private var preklic: Webtor.Preklic? = null

    override fun addTransferListener(transferListener: TransferListener) {
        osnovni.addTransferListener(transferListener)
        poslusalci += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val p = Webtor.Preklic()
        preklic = p
        val preverjen = Webtor.zaPredvajalnik(app, dataSpec.uri.toString(), p)
        val d: DataSource = if (preverjen == null) osnovni else {
            val w = WebtorVir(preverjen, glave(preverjen.koncni), ROK_MS)
            for (l in poslusalci) w.addTransferListener(l)
            w
        }
        zdaj = d
        return d.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (zdaj ?: throw IOException("vir ni odprt")).read(buffer, offset, length)

    override fun getUri(): Uri? = zdaj?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = zdaj?.responseHeaders ?: emptyMap()

    override fun close() {
        preklic?.preklici()
        preklic = null
        try { zdaj?.close() } finally { zdaj = null }
    }

    private companion object {
        /** Rok povezave in branja koncnega naslova (CDN TorBox/Webtor; izmerjeno 25. 10. 2026: prvi bajt do 6,2 s). */
        const val ROK_MS = 30_000L
    }
}
