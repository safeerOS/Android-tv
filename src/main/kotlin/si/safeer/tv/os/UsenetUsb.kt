package si.safeer.tv.os

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Lastnik 28. 10. 2026: lasten pogon Usenet uporablja predpomnilnik na USB (ce je izbran in priklopljen, [UsbPredpomnilnik]).
 * Plast okrog prenosa clanka ([UsenetNntp.body]): najprej datoteka na USB, sicer Usenet in zapis. Previjanje nazaj, ponovni
 * ogled in ponovno odprtje istega filma ne gredo vec na Usenet (manj prometa pri ponudniku, kvota). Bralnik in LRU v RAM
 * ostaneta, kot sta. Imena datotek so SHA-256 message-id ([PredpomnilnikPravila.imeSegmenta]) - id ni v imenu ne v dnevniku.
 * Napaka USB (odklop, poln) gre v [UsbPredpomnilnik.napaka]; clanek pride z Useneta kot doslej. Nikoli na glavni niti
 * ([UsbPredpomnilnik.mapa] tam vrne null).
 */
object UsenetUsb {
    private const val TAG = "SafeerUsenet"
    private val zadetkov = AtomicLong()
    private val zadetkovBajtov = AtomicLong()
    private val zapisanih = AtomicLong()
    private val dnevnikOb = AtomicLong()

    fun ovij(c: Context, telo: (String) -> ByteArray): (String) -> ByteArray {
        val app = c.applicationContext
        return { id ->
            val datoteka = try {
                UsbPredpomnilnik.mapa(app, PredpomnilnikPravila.USENET)?.let { m ->
                    val ime = PredpomnilnikPravila.imeSegmenta(id)
                    File(File(m, ime.take(2)), ime)
                }
            } catch (_: Throwable) { null }
            beri(datoteka) ?: telo(id).also { zapisi(app, datoteka, it) }
        }
    }

    private fun beri(f: File?): ByteArray? {
        if (f == null) return null
        return try {
            val n = f.length()
            if (n <= 0L || n > PredpomnilnikPravila.NAJVEC_SEGMENT) return null
            val b = f.readBytes()
            f.setLastModified(System.currentTimeMillis())   // LRU: zadnja uporaba
            zadetkov.incrementAndGet(); zadetkovBajtov.addAndGet(b.size.toLong())
            dnevnik()
            b
        } catch (_: java.io.FileNotFoundException) { null } catch (_: Throwable) { UsbPredpomnilnik.napaka(); null }
    }

    private fun zapisi(c: Context, f: File?, b: ByteArray) {
        if (f == null || b.isEmpty() || b.size > PredpomnilnikPravila.NAJVEC_SEGMENT) return
        try {
            f.parentFile?.mkdirs()
            val zac = File(f.parentFile, f.name + ".tmp")
            zac.writeBytes(b)
            if (!zac.renameTo(f)) { zac.delete(); return }
            zapisanih.incrementAndGet()
            UsbPredpomnilnik.zapisano(c, b.size.toLong())
        } catch (_: Throwable) { UsbPredpomnilnik.napaka() }
    }

    /** Najvec na minuto: koliko segmentov je prislo z USB namesto z Useneta (brez imen in id-jev). */
    private fun dnevnik() {
        val zdaj = System.currentTimeMillis()
        val prej = dnevnikOb.get()
        if (zdaj - prej < 60_000L || !dnevnikOb.compareAndSet(prej, zdaj)) return
        Log.i(TAG, "USB predpomnilnik: z USB ${zadetkov.get()} segmentov (${zadetkovBajtov.get() / (1024 * 1024)} MiB), zapisanih ${zapisanih.get()}")
    }
}
