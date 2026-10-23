package si.safeer.tv.os

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Naloga 76: kanali zasebnih kategorij dodatkov (XXX/Adult ...), ki ne gredo v noben skupni seznam, ohranjeni cez zagone
 * aplikacije. Prej samo v pomnilniku: po vsakem zagonu ali posodobitvi je bila mreza TV v zivo prazna, dokler pregled
 * vseh zasebnih katalogov ni koncal (do 3 min).
 *
 * Na disku po dodatku ena datoteka (ime = [KanaliPravila.odtisDodatka]): kljuc katalogov ([KanaliPravila.kljucZasebnih]),
 * cas in urejeni 64-bitni odtisi id-jev ([KanaliPravila.odtisi]) - brez berljivih imen ali id-jev. Zapis velja
 * [KanaliPravila.ZASEBNI_VELJA_MS]; potem velja naprej, dokler ga pregled v ozadju ne zamenja.
 */
object ZasebniKanali {
    private const val MAPA = "zasebni_kanali"
    private const val RAZLICICA = 1
    /** Vec ni smiselno (meja pregleda 20 strani po 1000 na katalog); varovalka pred pokvarjeno datoteko. */
    private const val NAJVEC_ODTISOV = 2_000_000

    private class Zapis(val kljuc: String, val cas: Long, val odtisi: LongArray)

    /** Odtis osnove dodatka -> zapis. */
    private val zapisi = ConcurrentHashMap<String, Zapis>()
    @Volatile private var nalozeno = false

    private fun mapa(c: Context) = File(c.applicationContext.filesDir, MAPA)

    /** Prebere zapise z diska (enkrat na proces). Klic iz ozadja. */
    fun nalozi(c: Context) {
        if (nalozeno) return
        synchronized(this) {
            if (nalozeno) return
            for (f in mapa(c).listFiles().orEmpty()) {
                val z = try { beri(f) } catch (_: Exception) { null }
                if (z == null) { f.delete(); continue }
                zapisi.putIfAbsent(f.name, z)
            }
            nalozeno = true
        }
    }

    private fun beri(f: File): Zapis? = DataInputStream(f.inputStream().buffered()).use { d ->
        if (d.readInt() != RAZLICICA) return null
        val kljuc = d.readUTF()
        val cas = d.readLong()
        val n = d.readInt()
        if (n < 0 || n > NAJVEC_ODTISOV) return null
        val o = LongArray(n) { d.readLong() }
        Zapis(kljuc, cas, o)
    }

    /** Stanje zapisa dodatka [osnova] za njegove trenutne zasebne kataloge ([kljuc]). */
    fun stanje(osnova: String, kljuc: String, zdaj: Long = System.currentTimeMillis()): KanaliPravila.ZasebniZapis {
        val z = zapisi[KanaliPravila.odtisDodatka(osnova)]
        return KanaliPravila.stanjeZasebnih(z?.kljuc, z?.cas ?: 0L, kljuc, zdaj)
    }

    /** Nov zapis dodatka po koncanem pregledu (v pomnilniku takoj, na disku atomarno). Klic iz ozadja. */
    fun zapisi(c: Context, osnova: String, kljuc: String, id: Collection<String>) {
        val ime = KanaliPravila.odtisDodatka(osnova)
        val z = Zapis(kljuc, System.currentTimeMillis(), KanaliPravila.odtisi(id))
        zapisi[ime] = z
        try {
            val m = mapa(c).apply { mkdirs() }
            val zacasna = File(m, "$ime.tmp")
            DataOutputStream(zacasna.outputStream().buffered()).use { d ->
                d.writeInt(RAZLICICA); d.writeUTF(z.kljuc); d.writeLong(z.cas); d.writeInt(z.odtisi.size)
                for (x in z.odtisi) d.writeLong(x)
            }
            if (!zacasna.renameTo(File(m, ime))) zacasna.delete()
        } catch (_: Exception) { }
    }

    /** Dodatek je odstranjen: njegov zapis izgine (pomnilnik in disk). */
    fun pozabi(c: Context, osnova: String) {
        val ime = KanaliPravila.odtisDodatka(osnova)
        zapisi.remove(ime)
        try { File(mapa(c), ime).delete() } catch (_: Exception) { }
    }

    /** Obdrzi samo zapise dodatkov [osnove] (dodatek, odstranjen na drugi napravi, izgine ob naslednjem odprtju mreze). */
    fun obdrzi(c: Context, osnove: Collection<String>) {
        val imena = osnove.map { KanaliPravila.odtisDodatka(it) }.toSet()
        for (ime in zapisi.keys.toList()) if (ime !in imena) {
            zapisi.remove(ime)
            try { File(mapa(c), ime).delete() } catch (_: Exception) { }
        }
    }

    /** Ali je kanal [id] v zasebni kategoriji kateregakoli dodatka (tudi po zastarelem zapisu). */
    fun jeZaseben(id: String): Boolean {
        if (zapisi.isEmpty()) return false
        val o = KanaliPravila.odtisId(id)
        return zapisi.values.any { z -> z.odtisi.isNotEmpty() && java.util.Arrays.binarySearch(z.odtisi, o) >= 0 }
    }

    /** Koliko odtisov je znanih (za dnevnik). */
    fun velikost(): Int = zapisi.values.sumOf { it.odtisi.size }
}
