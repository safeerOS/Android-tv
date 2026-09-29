// Media3 oznacuje del API-ja kot @UnstableApi (se lahko spremeni med razlicicami). Uporabljamo ga
// namerno (lasten vir podatkov); ob posodobitvi Media3 to datoteko preverimo.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package si.safeer.tv.os

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * DVD brez zaščite iz slike ISO (z računalnika prek Safeer Controla ali krajevno): glavni naslov diska
 * (največji niz VTS_xx_1..n.VOB) predvajamo kot en tok MPEG-PS, brez kopiranja slike na napravo.
 *
 * Slika se bere po delih (Range) prek osnovnega vira ([PripetiVir] ali navaden), zato previjanje deluje.
 * Zaščitenih diskov (CSS) ne predvajamo - Safeer ničesar ne odklepa (zastavica šifriranja v glavi sektorja).
 */
object DvdVir {
    const val SHEMA = "safeer-dvd"
    private const val SEKTOR = 2048

    data class Odsek(val odmik: Long, val dolzina: Long)

    enum class Stanje { V_REDU, ZASCITEN, NI_DVD }

    private val predpomnilnik = ConcurrentHashMap<String, List<Odsek>>()

    fun jeIso(ime: String): Boolean = ime.endsWith(".iso", ignoreCase = true)

    /** Naslov za predvajalnik: izvirni naslov slike ISO, zavit v našo shemo. */
    fun uri(izvirni: String): String = "$SHEMA:" + Uri.encode(izvirni)

    private fun izvirni(u: Uri): Uri = Uri.parse(Uri.decode(u.toString().removePrefix("$SHEMA:")))

    private fun beri(ds: DataSource, u: Uri, odmik: Long, dolzina: Int): ByteArray {
        val izid = ByteArray(dolzina)
        ds.open(DataSpec.Builder().setUri(u).setPosition(odmik).setLength(dolzina.toLong()).build())
        try {
            var n = 0
            while (n < dolzina) {
                val r = ds.read(izid, n, dolzina - n)
                if (r == C.RESULT_END_OF_INPUT) break
                n += r
            }
            return if (n == dolzina) izid else izid.copyOf(n)
        } finally { ds.close() }
    }

    private fun le32(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or ((b[i + 2].toLong() and 0xFF) shl 16) or ((b[i + 3].toLong() and 0xFF) shl 24)

    /** Zapisi mape ISO9660: ime -> (sektor, velikost, je mapa). */
    private fun mapa(ds: DataSource, u: Uri, sektor: Long, velikost: Long): List<Triple<String, Pair<Long, Long>, Boolean>> {
        val podatki = beri(ds, u, sektor * SEKTOR, minOf(velikost, 64L * SEKTOR).toInt())
        val izid = mutableListOf<Triple<String, Pair<Long, Long>, Boolean>>()
        var i = 0
        while (i + 33 < podatki.size) {
            val n = podatki[i].toInt() and 0xFF
            if (n == 0) { i = (i / SEKTOR + 1) * SEKTOR; continue }
            val dolzinaImena = podatki[i + 32].toInt() and 0xFF
            if (i + 33 + dolzinaImena > podatki.size) break
            val ime = String(podatki, i + 33, dolzinaImena, Charsets.ISO_8859_1).substringBefore(';').uppercase()
            izid += Triple(ime, le32(podatki, i + 2) to le32(podatki, i + 10), (podatki[i + 25].toInt() and 2) != 0)
            i += n
        }
        return izid
    }

    /** Glavni naslov diska: VOB-i največjega niza VTS_xx po vrsti. Prazno, če slika ni DVD-Video. */
    fun odseki(tovarna: DataSource.Factory, iso: Uri): List<Odsek> {
        predpomnilnik[iso.toString()]?.let { return it }
        val ds = tovarna.createDataSource()
        val pvd = beri(ds, iso, 16L * SEKTOR, SEKTOR)
        if (pvd.size < 190 || pvd[0].toInt() != 1 || String(pvd, 1, 5, Charsets.US_ASCII) != "CD001") return emptyList()
        val koren = mapa(ds, iso, le32(pvd, 158), le32(pvd, 166))
        val vts = koren.firstOrNull { it.first == "VIDEO_TS" && it.third } ?: return emptyList()
        val vobi = mapa(ds, iso, vts.second.first, vts.second.second).mapNotNull { (ime, polozaj, _) ->
            val m = Regex("^VTS_(\\d\\d)_(\\d)\\.VOB$").find(ime) ?: return@mapNotNull null
            if (m.groupValues[2] == "0") null else Triple(m.groupValues[1], m.groupValues[2].toInt(), polozaj)
        }
        val naslov = vobi.groupBy { it.first }.maxByOrNull { (_, d) -> d.sumOf { it.third.second } }?.value ?: return emptyList()
        val izid = naslov.sortedBy { it.second }.map { Odsek(it.third.first * SEKTOR, it.third.second) }
        predpomnilnik[iso.toString()] = izid
        return izid
    }

    /** Ali je slika DVD in ali je zaščitena (bajt 0x14 glave sektorja MPEG, kot libdvdcss). Klic iz ozadja. */
    fun preveri(tovarna: DataSource.Factory, iso: Uri): Stanje {
        val odseki = try { odseki(tovarna, iso) } catch (_: IOException) { return Stanje.NI_DVD }
        if (odseki.isEmpty()) return Stanje.NI_DVD
        val ds = tovarna.createDataSource()
        return try {
            for (o in odseki.take(2)) for (delez in listOf(0.0, 0.3, 0.6)) {
                val sektorjev = maxOf(1L, o.dolzina / SEKTOR)
                val odmik = o.odmik + (sektorjev * delez).toLong() * SEKTOR
                val blok = beri(ds, iso, odmik, 8 * SEKTOR)
                var s = 0
                while (s + SEKTOR <= blok.size) {
                    val zacetek = s
                    if (blok[zacetek] == 0.toByte() && blok[zacetek + 1] == 0.toByte() && blok[zacetek + 2] == 1.toByte() &&
                        blok[zacetek + 3] == 0xBA.toByte() && blok[zacetek + 14] == 0.toByte() && blok[zacetek + 15] == 0.toByte() &&
                        blok[zacetek + 16] == 1.toByte() && (blok[zacetek + 0x14].toInt() shr 4) and 3 != 0) return Stanje.ZASCITEN
                    s += SEKTOR
                }
            }
            Stanje.V_REDU
        } catch (_: IOException) { Stanje.NI_DVD }
    }

    /** Vir za ExoPlayer: naslove `safeer-dvd:` bere kot en tok iz odsekov slike, ostale posreduje naprej. */
    class Tovarna(private val osnova: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = Vir(osnova)
    }

    private class Vir(private val osnova: DataSource.Factory) : BaseDataSource(true) {
        private var naprej: DataSource? = null
        private var odseki: List<Odsek> = emptyList()
        private var iso: Uri? = null
        private var uri: Uri? = null
        private var polozaj = 0L          // v logičnem toku
        private var preostane = 0L
        private var trenutni: DataSource? = null
        private var vOdseku = 0L          // koliko še v trenutno odprtem delu
        private var odprto = false

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            if (dataSpec.uri.scheme != SHEMA) {
                val ds = osnova.createDataSource().also { naprej = it }
                return ds.open(dataSpec)
            }
            transferInitializing(dataSpec)
            iso = izvirni(dataSpec.uri)
            odseki = odseki(osnova, iso!!)
            if (odseki.isEmpty()) throw IOException("ni_dvd")
            val skupaj = odseki.sumOf { it.dolzina }
            polozaj = dataSpec.position
            if (polozaj > skupaj) throw IOException("izven_toka")
            preostane = if (dataSpec.length == C.LENGTH_UNSET.toLong()) skupaj - polozaj else minOf(dataSpec.length, skupaj - polozaj)
            odpriDel()
            odprto = true
            transferStarted(dataSpec)
            return preostane
        }

        private fun odpriDel() {
            trenutni?.close()
            trenutni = null
            var zacetek = 0L
            for (o in odseki) {
                if (polozaj < zacetek + o.dolzina) {
                    val znotraj = polozaj - zacetek
                    vOdseku = minOf(o.dolzina - znotraj, preostane)
                    val ds = osnova.createDataSource()
                    ds.open(DataSpec.Builder().setUri(iso!!).setPosition(o.odmik + znotraj).setLength(vOdseku).build())
                    trenutni = ds
                    return
                }
                zacetek += o.dolzina
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            naprej?.let { return it.read(buffer, offset, length) }
            if (preostane <= 0) return C.RESULT_END_OF_INPUT
            if (vOdseku <= 0) { odpriDel(); if (trenutni == null) return C.RESULT_END_OF_INPUT }
            val ds = trenutni ?: return C.RESULT_END_OF_INPUT
            val n = ds.read(buffer, offset, minOf(length.toLong(), vOdseku).toInt())
            if (n == C.RESULT_END_OF_INPUT) { vOdseku = 0; return read(buffer, offset, length) }
            polozaj += n; preostane -= n; vOdseku -= n
            bytesTransferred(n)
            return n
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            naprej?.close(); naprej = null
            trenutni?.close(); trenutni = null
            if (odprto) { odprto = false; transferEnded() }
        }
    }
}
