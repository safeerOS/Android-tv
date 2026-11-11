package si.safeer.tv.os

import java.io.File
import java.io.FileOutputStream
import java.util.Random

/**
 * Kratek preizkus hitrosti zapisa ob priklopu diska ([DiskPravila.PREIZKUS_BAJTOV]): zapis v mapo APLIKACIJE na nosilcu z
 * fsync, nato brisanje. Pise samo v nasi mapi (getExternalCacheDirs), nikoli drugam. Klic samo iz ozadja.
 */
object DiskPreizkus {
    class Rezultat(val pisljiv: Boolean, val mbs: Double)

    fun izmeri(mapa: File, bajtov: Long = DiskPravila.PREIZKUS_BAJTOV): Rezultat {
        val f = File(mapa, "preizkus-hitrosti.tmp")
        return try {
            mapa.mkdirs()
            val kos = ByteArray(1024 * 1024).also { Random(11).nextBytes(it) }   // nakljucno: nosilec ne sme stisniti nicel
            val zac = System.nanoTime()
            FileOutputStream(f).use { o ->
                var ostalo = bajtov
                while (ostalo > 0) { val n = minOf(ostalo, kos.size.toLong()).toInt(); o.write(kos, 0, n); ostalo -= n }
                o.flush(); o.fd.sync()
            }
            Rezultat(true, DiskPravila.mbNaSekundo(bajtov, System.nanoTime() - zac))
        } catch (_: Throwable) { Rezultat(false, 0.0) }
        finally { try { f.delete() } catch (_: Throwable) { } }
    }
}
