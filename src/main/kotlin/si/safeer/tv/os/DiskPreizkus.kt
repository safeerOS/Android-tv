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

    /** Najdlje, kolikor meritev sme trajati (32 MiB pri 1-2 MB/s je 16-32 s; dlje = zatik na odklopljenem nosilcu). */
    const val OMEJITEV_MS = 30_000L
    private const val IME_DATOTEKE = "preizkus-hitrosti.tmp"
    private val NEUSPELO = Rezultat(false, 0.0)

    fun izmeri(mapa: File, bajtov: Long = DiskPravila.PREIZKUS_BAJTOV): Rezultat {
        val f = File(mapa, IME_DATOTEKE)
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

    /**
     * [izmeri] z casovno omejitvijo (apk-24, N3): meritev tece na loceni dnevni niti, klicatelj ceka najvec [omejitevMs].
     * fsync na izvlecenem FAT/fuse nosilcu se lahko zatakne v jedru (D-stanje); tedaj vrnemo »neuspelo« in poskusimo pobrisati
     * datoteko, zataknjena nit pa ne zadrzuje nicesar (dnevna je). Pozni izid zataknjene niti se zavrze. Klic samo iz ozadja.
     * [meri] je za preizkus zamenljiv.
     */
    fun izmeriZOmejitvijo(mapa: File, omejitevMs: Long = OMEJITEV_MS, meri: (File) -> Rezultat = { izmeri(it) }): Rezultat {
        val izid = java.util.concurrent.atomic.AtomicReference<Rezultat?>(null)
        val konec = java.util.concurrent.CountDownLatch(1)
        val nit = Thread({
            try { izid.set(meri(mapa)) } catch (_: Throwable) { } finally { konec.countDown() }
        }, "safeer-usb-meritev").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        try { nit.start() } catch (_: Throwable) { return NEUSPELO }
        val prisel = try { konec.await(omejitevMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        if (prisel) return izid.get() ?: NEUSPELO
        try { nit.interrupt() } catch (_: Throwable) { }
        try { File(mapa, IME_DATOTEKE).delete() } catch (_: Throwable) { }
        return NEUSPELO
    }
}
