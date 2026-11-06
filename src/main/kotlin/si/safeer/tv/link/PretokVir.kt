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
class PretokVir(private val klient: PretokHttp.Klient, private val vir: PretokHttp.Vir) : BaseDataSource(true) {

    class Tovarna(prvi: String, glave: Map<String, String>) : DataSource.Factory {
        // En odjemalec in eno stanje zaupanja na tok: povezave (ze preverjene) se ponovno uporabijo med zahtevami
        // obsegov; tuj seznam, ki navede izvor prvega vira, odvzame zaupanje vsem kosom tega toka (PretokHttp.Vir).
        private val klient = PretokHttp.klient()
        private val vir = PretokHttp.Vir(prvi, glave)
        override fun createDataSource(): DataSource = PretokVir(klient, vir)
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
        val o = PretokHttp.odpri(klient, vir, dataSpec.uri.toString(), obseg)
        val r = o.odgovor
        if (r.code !in 200..299) { r.close(); throw IOException("Pretok: koda ${r.code}") }
        val telo = r.body ?: run { r.close(); throw IOException("Pretok: brez vsebine") }
        odgovor = r
        uri = Uri.parse(o.url)
        // Seznam z drugega izvora je PretokHttp ze prebral (preverba navedb); beremo ga iz pomnilnika.
        val prebrano = o.telo
        val v = if (prebrano != null) java.io.ByteArrayInputStream(prebrano) else telo.byteStream()
        vhod = v
        // Streznik obsega ni upostevala (200): preskocimo do zeljenega mesta.
        var preskoci = if (r.code == 200 && dataSpec.position > 0) dataSpec.position else 0L
        val buf = ByteArray(64 * 1024)
        while (preskoci > 0) {
            val n = v.read(buf, 0, minOf(buf.size.toLong(), preskoci).toInt())
            if (n < 0) throw EOFException("Pretok: tok krajsi od zacetka")
            preskoci -= n
        }
        val dolzina = prebrano?.size?.toLong() ?: telo.contentLength()
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
