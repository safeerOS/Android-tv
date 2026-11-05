package si.safeer.tv.scit

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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
 * Ob njej `<ime>.vir`: kljuc vhodnih seznamov ([kljucVira]), iz katerih je zgrajena. Gradnja se je prej izvedla ob vsakem
 * zagonu brskalnika (~15-20 MB prehodno in ~8 MB zapisa na flash, raziskava 4. 11. 2026), zdaj samo ob spremembi seznamov.
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

    /**
     * Zapise nabor iz vec seznamov (vsak seznam je zaporedje domen). Vrne stevilo razlicnih domen. [priblizno]: pricakovano
     * stevilo domen (vsota seznamov), da polje ne raste s kopiranjem. Zapis je pretocen (brez medpomnilnika n * 8 bajtov)
     * v lastno zacasno datoteko in nato atomarna zamenjava - brskalnik in proces :scit lahko gradita hkrati.
     */
    fun zapisi(cilj: File, seznami: Sequence<Sequence<String>>, priblizno: Int = 4096): Int {
        val zgoscenke = LongArrayRast(priblizno)
        for (seznam in seznami) for (vrstica in seznam) {
            val d = normaliziraj(vrstica)
            if (jeVeljavnaDomena(d)) zgoscenke.dodaj(zgoscenka(d))
        }
        val polje = zgoscenke.surovo()
        val m = zgoscenke.stevilo
        java.util.Arrays.sort(polje, 0, m)
        var n = 0
        for (i in 0 until m) {
            if (i == 0 || polje[i] != polje[i - 1]) polje[n++] = polje[i]
        }
        val mapa = cilj.absoluteFile.parentFile ?: throw java.io.IOException("nabor brez mape")
        mapa.mkdirs()
        val zacasna = File.createTempFile(cilj.name + ".", ".tmp", mapa)
        try {
            FileOutputStream(zacasna).use { fos ->
                val izhod = DataOutputStream(BufferedOutputStream(fos, 64 * 1024))
                izhod.write(MAGIC)
                izhod.writeInt(n)   // DataOutputStream pise big-endian, kot bere Poizvedba
                for (i in 0 until n) izhod.writeLong(polje[i])
                izhod.flush()
                fos.fd.sync()
            }
            Files.move(zacasna.toPath(), cilj.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            if (zacasna.exists()) zacasna.delete()
        }
        return n
    }

    private fun virDatoteka(cilj: File) = File(cilj.absoluteFile.parentFile, cilj.name + ".vir")

    /**
     * Kljuc vhoda: SHA-256 nad oznako oblike nabora in pari (id seznama, SHA-256 seznama) v danem vrstnem redu.
     * null, ce kateri seznam nima vsote - takrat se gradi vedno (raje odvecna gradnja kot zastarel nabor).
     */
    fun kljucVira(seznami: List<Pair<String, String>>): String? {
        if (seznami.isEmpty() || seznami.any { it.second.isEmpty() }) return null
        val md = MessageDigest.getInstance("SHA-256")
        md.update(MAGIC)
        for ((id, sha) in seznami) md.update("\n$id=$sha".toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Glava je nasa in dolzina datoteke se ujema s stevilom zgoscenk (prekinjen zapis ne velja). */
    fun steviloVGlavi(datoteka: File): Int? = try {
        if (!datoteka.isFile || datoteka.length() < GLAVA) null
        else RandomAccessFile(datoteka, "r").use { d ->
            val g = ByteArray(GLAVA)
            d.readFully(g)
            val b = ByteBuffer.wrap(g).order(ByteOrder.BIG_ENDIAN)
            val m = ByteArray(MAGIC.size); b.get(m)
            val n = b.getInt()
            if (!m.contentEquals(MAGIC) || n < 0 || GLAVA + n.toLong() * 8 != datoteka.length()) null else n
        }
    } catch (_: Exception) { null }

    /** [cilj] je veljaven nabor, zgrajen iz vhoda s kljucem [kljuc]. */
    fun jeZgrajenIz(cilj: File, kljuc: String?): Boolean {
        if (kljuc == null) return false
        val zapisan = try { virDatoteka(cilj).takeIf { it.isFile && it.length() < 256 }?.readText(Charsets.US_ASCII)?.trim() } catch (_: Exception) { null }
        return zapisan == kljuc && steviloVGlavi(cilj) != null
    }

    /**
     * Zgradi nabor samo, ce se je vhod spremenil ([kljuc] se razlikuje od zapisanega ob zadnji gradnji) ali datoteka ni
     * veljavna. Vrne stevilo domen ali -1, ce gradnje ni bilo (seznami se takrat sploh ne preberejo).
     */
    fun zapisiCeSpremenjeno(cilj: File, kljuc: String?, seznami: Sequence<Sequence<String>>, priblizno: Int = 4096): Int {
        if (jeZgrajenIz(cilj, kljuc)) return -1
        // Najprej stran star kljuc: ce se zapis prekine, naslednji zagon gradi znova.
        try { virDatoteka(cilj).delete() } catch (_: Exception) { }
        val n = zapisi(cilj, seznami, priblizno)
        if (kljuc != null) {
            val vir = virDatoteka(cilj)
            val zacasna = File.createTempFile(vir.name + ".", ".tmp", vir.parentFile)
            try {
                zacasna.writeText(kljuc + "\n", Charsets.US_ASCII)
                Files.move(zacasna.toPath(), vir.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                if (zacasna.exists()) zacasna.delete()
            }
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

            /**
             * [prej], ce je datoteka ista (ista lastModified), sicer znova odprta (null, ce je ni). Zaslon Scita je prej vsaki
             * 2 s na novo mapiral grozne.bin (pregled 5. 11. 2026).
             */
            fun odpriCeSpremenjeno(datoteka: File, prej: Poizvedba?): Poizvedba? {
                if (prej != null && prej.datoteka == datoteka && datoteka.isFile && datoteka.lastModified() == prej.spremenjena) return prej
                return odpri(datoteka)
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
    private class LongArrayRast(zacetna: Int) {
        private var a = LongArray(zacetna.coerceIn(16, 4_000_000))
        var stevilo = 0
            private set
        fun dodaj(v: Long) {
            if (stevilo == a.size) a = a.copyOf(a.size * 2)
            a[stevilo++] = v
        }
        /** Notranje polje (veljavnih je prvih [stevilo]), brez kopije. */
        fun surovo(): LongArray = a
    }
}
