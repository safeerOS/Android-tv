@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package si.safeer.tv.link

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Vir podatkov za Transformer v [Pretok], kadar izvirnik ni na pripeti napravi (splet, domace omrezje). Namesto
 * DefaultHttpDataSource, ki preusmeritvam sledi sam (vodja 5. 11. 2026: 302 na 127.0.0.1 je obsel PretokPravila):
 * vsak skok preveri [PretokHttp.odpri], povezava gre na preverjene naslove, glave toka samo izvoru [prvi].
 * [getUri] vrne koncni naslov verige - relativni kosi seznama HLS se razresijo glede nanj, kot pri DefaultHttpDataSource.
 */
class PretokVir(private val klient: okhttp3.OkHttpClient, private val prvi: String,
                private val glave: Map<String, String>) : BaseDataSource(true) {

    class Tovarna(private val prvi: String, private val glave: Map<String, String>) : DataSource.Factory {
        // En odjemalec na tok: povezave (ze preverjene) se ponovno uporabijo med zahtevami obsegov.
        private val klient = PretokHttp.klient()
        override fun createDataSource(): DataSource = PretokVir(klient, prvi, glave)
    }

    private var odgovor: okhttp3.Response? = null
    private var vhod: InputStream? = null
    private var uri: Uri? = null
    private var ostane = C.LENGTH_UNSET.toLong()
    private var odprt = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val doKonca = dataSpec.length == C.LENGTH_UNSET.toLong()
        val obseg = if (dataSpec.position == 0L && doKonca) null
            else "bytes=${dataSpec.position}-" + (if (doKonca) "" else (dataSpec.position + dataSpec.length - 1).toString())
        val o = PretokHttp.odpri(klient, dataSpec.uri.toString(), prvi, glave, obseg)
        val r = o.odgovor
        if (r.code !in 200..299) { r.close(); throw IOException("Pretok: koda ${r.code}") }
        val telo = r.body ?: run { r.close(); throw IOException("Pretok: brez vsebine") }
        odgovor = r
        uri = Uri.parse(o.url)
        val v = telo.byteStream()
        vhod = v
        // Streznik obsega ni upostevala (200): preskocimo do zeljenega mesta.
        var preskoci = if (r.code == 200 && dataSpec.position > 0) dataSpec.position else 0L
        val buf = ByteArray(64 * 1024)
        while (preskoci > 0) {
            val n = v.read(buf, 0, minOf(buf.size.toLong(), preskoci).toInt())
            if (n < 0) throw EOFException("Pretok: tok krajsi od zacetka")
            preskoci -= n
        }
        val dolzina = telo.contentLength()
        ostane = when {
            !doKonca -> dataSpec.length
            dolzina >= 0 -> if (r.code == 200) (dolzina - dataSpec.position).coerceAtLeast(0L) else dolzina
            else -> C.LENGTH_UNSET.toLong()
        }
        odprt = true
        transferStarted(dataSpec)
        return ostane
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (ostane == 0L) return C.RESULT_END_OF_INPUT
        val v = vhod ?: throw IOException("Pretok: vir ni odprt")
        val zelim = if (ostane < 0) length else minOf(length.toLong(), ostane).toInt()
        val n = v.read(buffer, offset, zelim)
        if (n < 0) return C.RESULT_END_OF_INPUT
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
