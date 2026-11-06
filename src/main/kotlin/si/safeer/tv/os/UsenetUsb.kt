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
    private val zavrzenaPisanja = AtomicLong()
    private val dnevnikOb = AtomicLong()

    fun prostoraZaNovFilm(c: Context, trenutna: String, potrebno: Long, mejaUsenet: Long) {
        val m = UsbPredpomnilnik.mapa(c, PredpomnilnikPravila.USENET) ?: return
        val izdaje = mutableListOf<PredpomnilnikPravila.Izdaja>()
        var zasedeno = 0L
        m.listFiles()?.filter { it.isDirectory }?.forEach { mapaIzdaje ->
            val odtis = mapaIzdaje.name
            var bajtov = 0L
            var zadnja = 0L
            mapaIzdaje.walkTopDown().filter { it.isFile }.forEach {
                bajtov += it.length()
                zadnja = maxOf(zadnja, it.lastModified())
            }
            if (bajtov > 0L) {
                izdaje.add(PredpomnilnikPravila.Izdaja(odtis, zadnja, bajtov))
                zasedeno += bajtov
            }
        }
        val prosto = maxOf(0L, mejaUsenet - zasedeno)
        val zaBrisanje = PredpomnilnikPravila.kajIzbrisati(izdaje, trenutna, potrebno, prosto)
        var izbrisanoBajtov = 0L
        for (odtis in zaBrisanje) {
            val d = File(m, odtis)
            val vel = izdaje.firstOrNull { it.odtis == odtis }?.bajtov ?: 0L
            if (d.deleteRecursively()) izbrisanoBajtov += vel
        }
        Log.d(TAG, "prostor za nov film: potrebno=${potrebno / (1024 * 1024)} MiB, prosto=${prosto / (1024 * 1024)} MiB, izbrisano izdaj=${zaBrisanje.size} MiB=${izbrisanoBajtov / (1024 * 1024)}")
    }

    /**
     * Omejena vrsta za zapis na USB v ozadju: ce je USB kljuc pocasen ali obremenjen,
     * se zapis zavrze (DiscardPolicy) in ne ustavlja omreznega prenosa ali predvajanja.
     */
    private val pisalec = java.util.concurrent.ThreadPoolExecutor(
        1, 1,
        0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue(16),
        { r -> Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "safeer-usb-zapis").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()
    )

    private val pisalecVnaprej = java.util.concurrent.ThreadPoolExecutor(
        1, 1,
        0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue(160), // okno ~96-120 MiB = ~130-160 segmentov
        { r -> Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "safeer-usb-zapis-v").apply { isDaemon = true } },
        { r, executor ->
            if (!executor.isShutdown) {
                try {
                    executor.queue.put(r)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            } else {
                zavrzenaPisanja.incrementAndGet()
                Log.d(TAG, "zavrzen zapis vnaprejsnjega segmenta")
            }
        }
    )

    fun ovij(c: Context, odtisIzdaje: String, telo: (String, Long) -> ByteArray): (String, Long) -> ByteArray {
        val app = c.applicationContext
        return { id, bajtov ->
            val datoteka = try {
                UsbPredpomnilnik.mapa(app, PredpomnilnikPravila.USENET)?.let { m ->
                    val ime = PredpomnilnikPravila.imeSegmenta(id)
                    File(File(m, odtisIzdaje), ime)
                }
            } catch (_: Throwable) { null }
            beri(datoteka)?.also { UsenetBralnik.zadnjiVir.set("usb") } ?: telo(id, bajtov).also { bajti ->
                UsenetBralnik.zadnjiVir.set("omrezje")
                if (datoteka != null && bajti.isNotEmpty() && bajti.size <= PredpomnilnikPravila.NAJVEC_SEGMENT) {
                    try {
                        if (UsenetBralnik.vnaprejPrenos.get() == true) {
                            pisalecVnaprej.execute { zapisi(app, datoteka, bajti) }
                        } else {
                            pisalec.execute { zapisi(app, datoteka, bajti) }
                        }
                    } catch (_: Throwable) {
                        zavrzenaPisanja.incrementAndGet()
                    }
                }
            }
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

    private fun zapisi(c: Context, f: File, b: ByteArray) {
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
