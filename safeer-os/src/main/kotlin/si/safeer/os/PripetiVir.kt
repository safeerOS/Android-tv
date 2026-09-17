package si.safeer.os

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Vir podatkov za ExoPlayer, ki bere z racunalnika prek OkHttp s pripetim potrdilom Safeer
 * Controla in zetonom te naprave. Obsegi (Range) za iskanje po datoteki; brez potrdila z
 * dobljenim odtisom povezava ne steče.
 */
class PripetiVir(private val odjemalec: OkHttpClient, private val zeton: String) : BaseDataSource(true) {

    class Tovarna(streznikOdtis: String, private val zeton: String) : DataSource.Factory {
        private val odjemalec = odjemalecZaStreznik(streznikOdtis)
        override fun createDataSource(): DataSource = PripetiVir(odjemalec, zeton)
    }

    private var odziv: Response? = null
    private var tok: InputStream? = null
    private var uri: Uri? = null
    private var preostane: Long = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val z = Request.Builder().url(dataSpec.uri.toString()).header("X-Safeer-Token", zeton)
        val doKonca = dataSpec.length == C.LENGTH_UNSET.toLong()
        if (dataSpec.position != 0L || !doKonca) {
            val konec = if (doKonca) "" else (dataSpec.position + dataSpec.length - 1).toString()
            z.header("Range", "bytes=${dataSpec.position}-$konec")
        }
        val r = try { odjemalec.newCall(z.build()).execute() } catch (e: IOException) { throw e }
        if (!r.isSuccessful) {
            r.close()
            throw IOException("HTTP ${r.code}")
        }
        val telo = r.body ?: run { r.close(); throw IOException("prazen odgovor") }
        odziv = r
        tok = telo.byteStream()
        if (r.code == 200 && dataSpec.position > 0) {
            // Streznik obsega ni upostevala: preskocimo do zeljenega mesta.
            var ostane = dataSpec.position
            val kos = ByteArray(64 * 1024)
            while (ostane > 0) {
                val n = tok!!.read(kos, 0, minOf(kos.size.toLong(), ostane).toInt())
                if (n < 0) throw IOException("datoteka je krajsa od zahtevanega mesta")
                ostane -= n
            }
        }
        preostane = when {
            !doKonca -> dataSpec.length
            telo.contentLength() >= 0 -> telo.contentLength()
            else -> C.LENGTH_UNSET.toLong()
        }
        transferStarted(dataSpec)
        return preostane
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (preostane == 0L) return C.RESULT_END_OF_INPUT
        val t = tok ?: throw IOException("vir ni odprt")
        val koliko = if (preostane == C.LENGTH_UNSET.toLong()) length else minOf(preostane, length.toLong()).toInt()
        val n = t.read(buffer, offset, koliko)
        if (n == -1) return C.RESULT_END_OF_INPUT
        if (preostane != C.LENGTH_UNSET.toLong()) preostane -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try { tok?.close() } catch (_: Throwable) { }
        try { odziv?.close() } catch (_: Throwable) { }
        val bilo = tok != null
        tok = null
        odziv = null
        if (bilo) transferEnded()
    }

    companion object {
        /** OkHttp s pripetim potrdilom streznika datotek (odtis SHA-256 iz odgovora `files.list`). */
        fun odjemalecZaStreznik(odtis: String): OkHttpClient {
            val (tovarna, zaupnik) = Pin.tovarna(odtis)
            return OkHttpClient.Builder()
                .sslSocketFactory(tovarna, zaupnik)
                .hostnameVerifier(Pin.brezImena)
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
