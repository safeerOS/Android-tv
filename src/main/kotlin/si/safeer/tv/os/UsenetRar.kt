package si.safeer.tv.os

import java.util.zip.CRC32

/**
 * RAR samo v nacinu »store« za lasten pogon Usenet, korak 2 (lastnik 26. 10. 2026) - enako kot core/usenet_rar.py.
 *
 * Shranjen RAR (RAR4 metoda 0x30, RAR5 metoda 0) ima film v zvezkih nespremenjen - pretakamo z odmikom. Stisnjenega,
 * sifriranega ali s sifriranimi glavami ne moremo predvajati: [preveri] odloci iz prvih bajtov prvega zvezka PRED
 * ponudbo (prototip 25. 10. 2026: 2 od 4 izdaj BBB stisnjeni, dodatek je predvajal nadomestni video).
 *
 * Po objavljeni specifikaciji (RARLAB technote za RAR 2.9-4, »RAR 5.0 archive format«), brez kode UnRAR, junrar ali GPL;
 * preverjeno 26. 10. 2026 proti pravim arhivom WinRAR 7 (skupni primeri rar_glava »pravi«). Meje proti zlonamernim glavam:
 * 10 000 blokov, glava RAR5 do 2 MiB, ime do 4096 B, odmik vsakega bloka raste, CRC glav; ime gre skozi varnoIme.
 * Skupni primeri: usenet-pogon.json v5 (rar_glava, rar_zvezki, rar_sestavi).
 */
object UsenetRar {
    /** Imena so ista kot v skupnih primerih (male crke). */
    enum class Izid { OK, NI_RAR, STISNJEN, SIFRIRAN, SIFRIRANE_GLAVE, POSKODOVAN, NEPOPOLNO, NESKLADNO, NI_DATOTEKE }

    class NapakaRar(val izid: Izid) : java.io.IOException("usenet rar: " + izid.name.lowercase())

    val PODPIS4 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
    val PODPIS5 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
    const val NAJVEC_BLOKOV = 10_000
    const val NAJVEC_GLAVA5 = 2 * 1024 * 1024
    const val NAJVEC_IME = 4096

    class Vnos(
        val ime: String?, val odmik: Long, val dolzina: Long, val velikost: Long,
        val pred: Boolean, val po: Boolean, val mapa: Boolean, val store: Boolean, val sifriran: Boolean,
    ) {
        override fun toString() = "Vnos($odmik+$dolzina/$velikost)"
    }

    class Zvezek(val razlicica: Int, val vnosi: List<Vnos>, val stevilka: Int?, val vecZvezkov: Boolean, val nepopolno: Boolean)

    class Kos(val zvezek: Int, val odmik: Long, val dolzina: Long)

    /** Bralnik zvezka: do [n] bajtov od [odmik] (manj, ce jih ni). */
    fun interface Beri { fun beri(odmik: Long, n: Int): ByteArray }

    private fun izBajtov(b: ByteArray) = Beri { o, n ->
        if (o >= b.size) ByteArray(0) else b.copyOfRange(o.toInt(), minOf(b.size.toLong(), o + n).toInt())
    }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, i: Int) = u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16)
    private fun crc(b: ByteArray, od: Int) = CRC32().apply { update(b, od, b.size - od) }.value

    /** RAR5 vint: 7 bitov na bajt, najvec 10 bajtov. (vrednost, nov indeks); konec bajtov = NEPOPOLNO. */
    private fun vint(b: ByteArray, od: Int): Pair<Long, Int> {
        var v = 0L
        var premik = 0
        for (k in 0 until 10) {
            if (od + k >= b.size) throw NapakaRar(Izid.NEPOPOLNO)
            val c = b[od + k].toInt() and 0xFF
            if (premik < 64) v = v or ((c and 0x7F).toLong() shl premik)
            if (c and 0x80 == 0) return v to od + k + 1
            premik += 7
        }
        throw NapakaRar(Izid.POSKODOVAN)
    }

    private fun ime(b: ByteArray, unicode4: Boolean = false): String? {
        val del = if (unicode4 && b.contains(0.toByte())) b.copyOfRange(0, b.indexOf(0.toByte())) else b
        val dekoder = Charsets.UTF_8.newDecoder()
        val s = try { dekoder.decode(java.nio.ByteBuffer.wrap(del)).toString() } catch (_: java.nio.charset.CharacterCodingException) {
            String(del, Charsets.ISO_8859_1)
        }
        return UsenetNzb.varnoIme(s)
    }

    private fun zacetek(b: ByteArray, podpis: ByteArray) = b.size >= podpis.size && podpis.indices.all { b[it] == podpis[it] }

    private fun rar4(beri: Beri, velikost: Long): Zvezek {
        var i = PODPIS4.size.toLong()
        val vnosi = ArrayList<Vnos>()
        var vec = false
        var prvi = true
        repeat(NAJVEC_BLOKOV) {
            if (i >= velikost) return Zvezek(4, vnosi, null, vec, false)
            val g = beri.beri(i, 7)
            if (g.size < 7) return Zvezek(4, vnosi, null, vec, true)
            val crc = u16(g, 0)
            val vrsta = g[2].toInt() and 0xFF
            val zast = u16(g, 3)
            val vel = u16(g, 5)
            if (vel < 7) throw NapakaRar(Izid.POSKODOVAN)
            val glava = beri.beri(i, vel)
            if (glava.size < vel) return Zvezek(4, vnosi, null, vec, true)
            if (crc(glava, 2) and 0xFFFF != crc.toLong()) throw NapakaRar(Izid.POSKODOVAN)
            var dodatno = 0L
            if (zast and 0x8000 != 0 || vrsta == 0x74) {
                if (vel < 11) throw NapakaRar(Izid.POSKODOVAN)
                dodatno = u32(glava, 7)
            }
            when {
                vrsta == 0x73 -> {
                    if (zast and 0x0080 != 0) throw NapakaRar(Izid.SIFRIRANE_GLAVE)
                    vec = zast and 0x0001 != 0
                }
                prvi -> throw NapakaRar(Izid.POSKODOVAN)
                vrsta == 0x74 -> {
                    if (vel < 32) throw NapakaRar(Izid.POSKODOVAN)
                    var paket = u32(glava, 7)
                    var nest = u32(glava, 11)
                    val metoda = glava[25].toInt() and 0xFF
                    val nIme = u16(glava, 26)
                    var o = 32
                    if (zast and 0x0100 != 0) {
                        if (vel < 40) throw NapakaRar(Izid.POSKODOVAN)
                        paket = paket or (u32(glava, 32) shl 32)
                        nest = nest or (u32(glava, 36) shl 32)
                        o = 40
                        dodatno = paket
                    }
                    if (nIme > NAJVEC_IME || o + nIme > vel) throw NapakaRar(Izid.POSKODOVAN)
                    vnosi += Vnos(ime(glava.copyOfRange(o, o + nIme), zast and 0x0200 != 0), i + vel, paket, nest,
                        zast and 0x01 != 0, zast and 0x02 != 0, zast and 0xE0 == 0xE0, metoda == 0x30, zast and 0x04 != 0)
                }
                vrsta == 0x7B -> return Zvezek(4, vnosi, null, vec, false)
            }
            prvi = false
            val nasl = i + vel + dodatno
            if (nasl <= i) throw NapakaRar(Izid.POSKODOVAN)
            i = nasl
        }
        throw NapakaRar(Izid.POSKODOVAN)
    }

    private fun imaZapis(dodatno: ByteArray, vrsta: Long): Boolean {
        var i = 0
        while (i < dodatno.size) {
            val (vel, j) = vint(dodatno, i)
            if (vel < 1 || j + vel > dodatno.size) throw NapakaRar(Izid.POSKODOVAN)
            if (vint(dodatno, j).first == vrsta) return true
            i = (j + vel).toInt()
        }
        return false
    }

    private fun rar5(beri: Beri, velikost: Long): Zvezek {
        var i = PODPIS5.size.toLong()
        val vnosi = ArrayList<Vnos>()
        var vec = false
        var stevilka: Int? = null
        var prvi = true
        repeat(NAJVEC_BLOKOV) {
            if (i >= velikost) return Zvezek(5, vnosi, stevilka, vec, false)
            val g = beri.beri(i, 7)
            if (g.size < 5) return Zvezek(5, vnosi, stevilka, vec, true)
            // Glava do 2 MiB ima velikost v najvec 3 bajtih vint; daljsa je poskodovana (ne »nepopolna«).
            if (g.size == 7 && (4..6).all { g[it].toInt() and 0x80 != 0 }) throw NapakaRar(Izid.POSKODOVAN)
            val (vel, z) = try { vint(g, 4) } catch (e: NapakaRar) {
                if (e.izid == Izid.NEPOPOLNO) return Zvezek(5, vnosi, stevilka, vec, true) else throw e
            }
            if (vel < 2 || vel > NAJVEC_GLAVA5) throw NapakaRar(Izid.POSKODOVAN)
            val cela = beri.beri(i, (z + vel).toInt())
            if (cela.size < z + vel) return Zvezek(5, vnosi, stevilka, vec, true)
            if (crc(cela, 4) != u32(cela, 0)) throw NapakaRar(Izid.POSKODOVAN)
            val h = cela.copyOfRange(z, cela.size)
            var vrsta: Long
            var zast: Long
            var dod = 0L
            var pod = 0L
            var k: Int
            try {
                vint(h, 0).also { vrsta = it.first; k = it.second }
                vint(h, k).also { zast = it.first; k = it.second }
                if (zast and 0x1L != 0L) vint(h, k).also { dod = it.first; k = it.second }
                if (zast and 0x2L != 0L) vint(h, k).also { pod = it.first; k = it.second }
            } catch (_: NapakaRar) {
                throw NapakaRar(Izid.POSKODOVAN)
            }
            if (dod > h.size - k) throw NapakaRar(Izid.POSKODOVAN)
            if (vrsta == 4L) throw NapakaRar(Izid.SIFRIRANE_GLAVE)
            if (prvi && vrsta != 1L) throw NapakaRar(Izid.POSKODOVAN)
            try {
                when (vrsta) {
                    1L -> {
                        val (af, k2) = vint(h, k)
                        vec = af and 0x1L != 0L
                        if (af and 0x2L != 0L) stevilka = vint(h, k2).first.toInt() else if (vec) stevilka = 0
                    }
                    2L -> {
                        val (ff, k1) = vint(h, k)
                        var kk = k1
                        val (nest, k3) = vint(h, kk); kk = k3
                        kk = vint(h, kk).second
                        kk += (if (ff and 0x2L != 0L) 4 else 0) + (if (ff and 0x4L != 0L) 4 else 0)
                        val (ci, k4) = vint(h, kk); kk = k4
                        kk = vint(h, kk).second
                        val (nIme, k5) = vint(h, kk); kk = k5
                        if (nIme > NAJVEC_IME || kk + nIme > h.size - dod) throw NapakaRar(Izid.POSKODOVAN)
                        val imeD = ime(h.copyOfRange(kk, (kk + nIme).toInt()))
                        val sifriran = if (dod > 0) imaZapis(h.copyOfRange((h.size - dod).toInt(), h.size), 0x01) else false
                        vnosi += Vnos(imeD, i + z + vel, pod, if (ff and 0x8L != 0L) -1 else nest, zast and 0x8L != 0L,
                            zast and 0x10L != 0L, ff and 0x1L != 0L, (ci shr 7) and 0x7L == 0L, sifriran)
                    }
                    5L -> return Zvezek(5, vnosi, stevilka, vec, false)
                }
            } catch (e: NapakaRar) {
                throw NapakaRar(if (e.izid == Izid.NEPOPOLNO) Izid.POSKODOVAN else e.izid)
            }
            prvi = false
            val nasl = i + z + vel + pod
            if (nasl <= i) throw NapakaRar(Izid.POSKODOVAN)
            i = nasl
        }
        throw NapakaRar(Izid.POSKODOVAN)
    }

    /** Glave enega zvezka iz bajtov zacetka. Napaka: [NapakaRar] NI_RAR, SIFRIRANE_GLAVE, POSKODOVAN. */
    fun razcleniZvezek(b: ByteArray, velikost: Long = b.size.toLong()): Zvezek = razcleniZvezek(izBajtov(b), velikost)

    fun razcleniZvezek(beri: Beri, velikost: Long): Zvezek {
        val z = beri.beri(0, 8)
        if (zacetek(z, PODPIS5)) return rar5(beri, velikost)
        if (zacetek(z, PODPIS4)) return rar4(beri, velikost)
        throw NapakaRar(Izid.NI_RAR)
    }

    /** Najvecja datoteka (ne mapa) - film; vzorci in .nfo so manjsi. Pri enaki velikosti prva. */
    fun glavniVnos(z: Zvezek): Vnos? = z.vnosi.filter { !it.mapa }.maxWithOrNull(compareBy<Vnos>({ it.velikost }, { it.dolzina }))

    /** Odlocitev PRED ponudbo iz prvih bajtov prvega zvezka (skupni primeri rar_glava). */
    fun preveri(prviBajti: ByteArray, velikost: Long = Long.MAX_VALUE / 2): Izid {
        val z = try { razcleniZvezek(prviBajti, velikost) } catch (e: NapakaRar) { return e.izid }
        val v = glavniVnos(z) ?: return if (z.nepopolno) Izid.NEPOPOLNO else Izid.NI_DATOTEKE
        return when {
            v.sifriran -> Izid.SIFRIRAN
            !v.store -> Izid.STISNJEN
            else -> Izid.OK
        }
    }

    private val DEL = Regex("^(.*)\\.part0*(\\d{1,4})\\.rar$", RegexOption.IGNORE_CASE)
    private val RAR = Regex("^(.*)\\.rar$", RegexOption.IGNORE_CASE)
    private val RNN = Regex("^(.*)\\.([r-zR-Z])(\\d{2})$")

    /** Indeksi imen zvezkov v pravem vrstnem redu (skupni primeri rar_zvezki); sicer null. */
    fun vrstniRedZvezkov(imena: List<String?>): List<Int>? {
        val deli = ArrayList<Triple<String, Int, Int>>()
        val star = ArrayList<Triple<String, Int, Int>>()
        for ((i, ime) in imena.withIndex()) {
            val s = ime ?: ""
            val d = DEL.matchEntire(s)
            if (d != null) { deli += Triple(d.groupValues[1].lowercase(), d.groupValues[2].toInt(), i); continue }
            val r = RNN.matchEntire(s)
            if (r != null) {
                star += Triple(r.groupValues[1].lowercase(), 1 + (r.groupValues[2].lowercase()[0] - 'r') * 100 + r.groupValues[3].toInt(), i)
                continue
            }
            val a = RAR.matchEntire(s) ?: return null
            star += Triple(a.groupValues[1].lowercase(), 0, i)
        }
        if ((deli.isNotEmpty() && star.isNotEmpty()) || (deli.isEmpty() && star.isEmpty())) return null
        val vrsta = (deli.ifEmpty { star }).sortedBy { it.second }
        if (vrsta.map { it.first }.toSet().size != 1) return null
        val od = if (deli.isNotEmpty()) 1 else 0
        if (vrsta.map { it.second } != (od until od + vrsta.size).toList()) return null
        return vrsta.map { it.third }
    }

    /** (ime, velikost, kosi) glavne datoteke iz zvezkov v vrstnem redu (skupni primeri rar_sestavi). */
    fun sestavi(zvezki: List<Zvezek>): Triple<String?, Long, List<Kos>> {
        if (zvezki.isEmpty()) throw NapakaRar(Izid.NI_DATOTEKE)
        val v = glavniVnos(zvezki[0]) ?: throw NapakaRar(Izid.NI_DATOTEKE)
        if (v.sifriran) throw NapakaRar(Izid.SIFRIRAN)
        if (!v.store) throw NapakaRar(Izid.STISNJEN)
        if (v.pred) throw NapakaRar(Izid.NESKLADNO)
        val kosi = arrayListOf(Kos(0, v.odmik, v.dolzina))
        var skupaj = v.dolzina
        var trenutni = v
        for (n in 1 until zvezki.size) {
            if (!trenutni.po) break
            val z = zvezki[n]
            if (z.razlicica != zvezki[0].razlicica) throw NapakaRar(Izid.NESKLADNO)
            val s0 = zvezki[0].stevilka
            if (z.stevilka != null && s0 != null && z.stevilka != s0 + n) throw NapakaRar(Izid.NESKLADNO)
            val nasl = z.vnosi.firstOrNull { it.pred && !it.mapa }
            if (nasl == null || nasl.ime != v.ime || (v.velikost >= 0 && nasl.velikost != v.velikost)) throw NapakaRar(Izid.NESKLADNO)
            if (nasl.sifriran) throw NapakaRar(Izid.SIFRIRAN)
            if (!nasl.store) throw NapakaRar(Izid.STISNJEN)
            kosi += Kos(n, nasl.odmik, nasl.dolzina)
            skupaj += nasl.dolzina
            trenutni = nasl
        }
        if (trenutni.po || (v.velikost >= 0 && skupaj != v.velikost)) throw NapakaRar(Izid.NESKLADNO)
        return Triple(v.ime, skupaj, kosi)
    }
}
