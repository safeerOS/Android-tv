// Media3 oznacuje del API-ja kot @UnstableApi (se lahko spremeni med razlicicami). Uporabljamo ga
// namerno (DASH, lasten vir podatkov); ob posodobitvi Media3 to datoteko preverimo.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package si.safeer.tv.os

import si.safeer.tv.R

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
class PripetiVir(private val odjemalec: OkHttpClient, private val zeton: String,
                 private val context: android.content.Context? = null, private val naprava: String = "") : BaseDataSource(true) {

    /** [naprava] (id v Linku) omogoci tok prek Global Linka, kadar naprava ni v istem omrezju. */
    class Tovarna(streznikOdtis: String, private val zeton: String,
                  context: android.content.Context? = null, private val naprava: String = "") : DataSource.Factory {
        private val odjemalec = odjemalecZaStreznik(streznikOdtis)
        private val app = context?.applicationContext
        override fun createDataSource(): DataSource = PripetiVir(odjemalec, zeton, app, naprava)
    }

    private var odziv: Response? = null
    private var tok: InputStream? = null
    private var uri: Uri? = null
    private var preostane: Long = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val doKonca = dataSpec.length == C.LENGTH_UNSET.toLong()
        fun zahteva(url: String): Response {
            val z = Request.Builder().url(url).header("X-Safeer-Token", zeton)
            if (dataSpec.position != 0L || !doKonca) {
                val konec = if (doKonca) "" else (dataSpec.position + dataSpec.length - 1).toString()
                z.header("Range", "bytes=${dataSpec.position}-$konec")
            }
            return odjemalec.newCall(z.build()).execute()
        }
        val izvirni = dataSpec.uri.toString()
        val kljucPoti = kljucPoti(izvirni)
        // Naprava je bila ze nedosegljiva v LAN: minuto gremo naravnost prek releja (previjanje brez cakanja).
        val znanRele = (releji[kljucPoti] ?: 0L).takeIf { android.os.SystemClock.elapsedRealtime() < it }?.let { prekReleja(izvirni) }
        val r = if (znanRele != null) {
            try { zahteva(znanRele).also { releji[kljucPoti] = android.os.SystemClock.elapsedRealtime() + 60_000L } } catch (e: IOException) {
                // Rele ne gre vec (npr. smo spet doma in rele je padel): enkrat poskusimo neposredno.
                releji.remove(kljucPoti)
                zahteva(izvirni)
            }
        } else try { zahteva(izvirni) } catch (e: IOException) {
            // Neposredno ni sla (npr. zunaj doma): isti tok prek Global Linka do Huba te naprave.
            val rele = prekReleja(izvirni) ?: throw e
            zahteva(rele).also { releji[kljucPoti] = android.os.SystemClock.elapsedRealtime() + 60_000L }
        }
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

    /**
     * Isti naslov prek Global Linka: lokalna vrata releja do Huba naprave + `/cast` (Hub streze deljene
     * datoteke na `/cast/d/<id>`). Potrdilo Huba je isto kot potrdilo streznika datotek (isti kljuc), zato
     * velja isti pripeti odtis. Null, ce rele ni mogoc (izklopljen Global Link, neznana naprava).
     */
    private fun prekReleja(url: String): String? {
        val c = context ?: return null
        if (naprava.isBlank() || !si.safeer.tv.link.GlobalLink.vklopljen(c)) return null
        val u = Uri.parse(url)
        val pot = u.encodedPath ?: return null
        if (!pot.startsWith("/d/") && !pot.startsWith("/thumb/")) return null
        val hub = "wss://${u.host}:${if (u.port > 0) u.port else 443}/cast/ws"
        val rele = si.safeer.tv.link.GlobalLink.naslov(c, hub, naprava, prekRele = true)
        if (!rele.startsWith("wss://127.0.0.1:")) return null
        val vrata = Uri.parse(rele).port.takeIf { it > 0 } ?: return null
        return "https://127.0.0.1:$vrata/cast$pot" + (u.encodedQuery?.let { "?$it" } ?: "")
    }

    /** Odlocitev o releju velja samo za isto napravo na istem naslovu (nikoli za drugo na istem IP). */
    private fun kljucPoti(url: String): String = Uri.parse(url).let { "$naprava|${it.host}:${it.port}" }

    companion object {
        /** Naprave, ki so bile nedosegljive v LAN: do kdaj (elapsedRealtime) gremo naravnost prek releja. */
        private val releji = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
