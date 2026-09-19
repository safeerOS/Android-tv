package si.safeer.tv.scit

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Nabor blokiranih domen za Safeer Scit (filter DNS za ves televizor).
 *
 * Seznami (hagezi PRO in TIF, Fake, URLhaus, Phishing Army, SI-CERT) imajo skupaj okoli pol milijona
 * domen. Da jih proces filtra ne nosi v kopici (2 GB televizor), jih zapisemo kot urejeno polje
 * 64-bitnih zgoscenk (FNV-1a) v datoteko; storitev jo mapira v pomnilnik in isce binarno. Domena je
 * blokirana, ce je v naboru sama ali katera od njenih nadrejenih domen (seznami so "wildcard":
 * `ads.example.com` pokrije tudi `x.ads.example.com`).
 *
 * Datoteka: "SCIT1" (5 bajtov) + stevilo (int32 BE) + n * int64 BE.
 */
object DomenskiNabor {
    private val MAGIC = "SCIT1".toByteArray(Charsets.US_ASCII)
    const val GLAVA = 5 + 4

    /** FNV-1a 64 nad malimi crkami ASCII; koncna pika odpade. */
    fun zgoscenka(domena: String): Long {
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        val s = normaliziraj(domena)
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return h
    }

    fun normaliziraj(domena: String): String {
        var s = domena.trim().lowercase()
        while (s.endsWith(".")) s = s.dropLast(1)
        while (s.startsWith(".")) s = s.drop(1)
        return s
    }

    fun jeVeljavnaDomena(s: String): Boolean =
        s.length in 1..253 && '.' in s && s.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' || it == '_' } &&
            !s.contains("..")

    /** Zapise nabor iz vec seznamov (vsak seznam je zaporedje domen). Vrne stevilo razlicnih domen. */
    fun zapisi(cilj: File, seznami: Sequence<Sequence<String>>): Int {
        val zgoscenke = LongArrayRast()
        for (seznam in seznami) for (vrstica in seznam) {
            val d = normaliziraj(vrstica)
            if (jeVeljavnaDomena(d)) zgoscenke.dodaj(zgoscenka(d))
        }
        val polje = zgoscenke.polje()
        polje.sort()
        var n = 0
        for (i in polje.indices) {
            if (i == 0 || polje[i] != polje[i - 1]) polje[n++] = polje[i]
        }
        val zacasna = File(cilj.parentFile, cilj.name + ".tmp")
        cilj.parentFile?.mkdirs()
        RandomAccessFile(zacasna, "rw").use { d ->
            d.setLength(0)
            val b = ByteBuffer.allocate(GLAVA + n * 8).order(ByteOrder.BIG_ENDIAN)
            b.put(MAGIC).putInt(n)
            for (i in 0 until n) b.putLong(polje[i])
            d.write(b.array(), 0, b.position())
        }
        if (!zacasna.renameTo(cilj)) {
            cilj.delete()
            if (!zacasna.renameTo(cilj)) throw java.io.IOException("nabora ni bilo mogoce zapisati")
        }
        return n
    }

    /** Odprt nabor: datoteka je mapirana, iskanje binarno; ne zaseda kopice. */
    class Poizvedba private constructor(private val buf: ByteBuffer, val stevilo: Int, val datoteka: File, val spremenjena: Long) {
        companion object {
            fun odpri(datoteka: File): Poizvedba? {
                if (!datoteka.isFile || datoteka.length() < GLAVA) return null
                RandomAccessFile(datoteka, "r").use { d ->
                    val map = d.channel.map(FileChannel.MapMode.READ_ONLY, 0, datoteka.length()).order(ByteOrder.BIG_ENDIAN)
                    val m = ByteArray(MAGIC.size); map.get(m)
                    if (!m.contentEquals(MAGIC)) return null
                    val n = map.getInt()
                    if (n < 0 || GLAVA + n.toLong() * 8 > datoteka.length()) return null
                    return Poizvedba(map, n, datoteka, datoteka.lastModified())
                }
            }
        }

        fun vsebujeZgoscenko(h: Long): Boolean {
            var lo = 0
            var hi = stevilo - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val v = buf.getLong(GLAVA + mid * 8)
                if (v < h) lo = mid + 1 else if (v > h) hi = mid - 1 else return true
            }
            return false
        }

        /** Domena ali katera od nadrejenih (razen same vrhnje domene) je v naboru. */
        fun jeBlokirana(ime: String): Boolean {
            val d = normaliziraj(ime)
            if (d.isEmpty() || '.' !in d) return false
            var zacetek = 0
            while (true) {
                val del = if (zacetek == 0) d else d.substring(zacetek)
                if ('.' !in del) return false  // vrhnja domena sama se ne preverja
                if (vsebujeZgoscenko(zgoscenka(del))) return true
                val naslednja = d.indexOf('.', zacetek)
                if (naslednja < 0) return false
                zacetek = naslednja + 1
            }
        }
    }

    /** Rastoce polje long brez skatlanja. */
    private class LongArrayRast {
        private var a = LongArray(4096)
        private var n = 0
        fun dodaj(v: Long) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun polje(): LongArray = a.copyOf(n)
    }
}
