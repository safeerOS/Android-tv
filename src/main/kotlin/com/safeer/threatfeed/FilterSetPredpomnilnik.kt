/*
 * Predprevedeni nabor pravil EasyList na disku (12. 11. 2026).
 *
 * Zakaj: na TV prevod EasyList (49 299 pravil) traja ~1,7 s od zagona, zahteve podvirov v shouldInterceptRequest pa na ta prevod
 * cakajo (ZascitaZagona; izmerjeno: skupaj 4,5-5,6 s cakanja na ~65 klicev, do 0,8 s na klic; filtriranje samo ~1,5 ms/klic).
 * Nabor, ki je ze prevajen, se zapise v datoteko in ob naslednjem zagonu bere brez razclenjevanja pravil, brez branja seznama in
 * brez SHA-256 nad seznamom. Odlocitve so iste (isti objekti NetworkFilter in isti vrstni red v predalih); zascita se ne oslabi:
 * zahteve se se vedno ne spustijo mimo seznama, cakanje se samo skrajsa.
 *
 * Varnost: datoteka je v zasebni mapi aplikacije; kljuc (SHA-256 seznamov iz metapodatkov agenta + razlicica aplikacije +
 * razlicica zapisa) in CRC32 celotne datoteke; karkoli ne stima (kljuc, CRC, oblika, izjema) = null in prevod iz seznama kot prej.
 * Pure JVM (brez Androida). Datoteka FilterListEngine.kt je souporabljena z mobilno aplikacijo: ta datoteka je neobvezen dodatek.
 */
package com.safeer.threatfeed

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.regex.Pattern
import java.util.zip.CRC32

object FilterSetPredpomnilnik {
    private const val MAGIC = 0x53464331      // "SFC1"
    private const val RAZLICICA_ZAPISA = 1
    /** Najvec zapisanih nizov na datoteko (varovalka pred pokvarjeno datoteko). */
    private const val NAJVEC_NIZOV = 4_000_000

    /** Kljuc predpomnilnika: SHA-256 (hex) nad deli (npr. id in SHA-256 seznamov, razlicica aplikacije). */
    fun kljuc(deli: List<String>): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update("safeer-easylist-predpomnilnik-$RAZLICICA_ZAPISA".toByteArray(Charsets.UTF_8))
        for (d in deli) { md.update(0); md.update(d.toByteArray(Charsets.UTF_8)) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private class Pisalnik {
        val tabela = LinkedHashMap<String, Int>()
        val telo = ByteArrayOutputStream()
        val o = DataOutputStream(telo)
        fun niz(s: String): Int = tabela.getOrPut(s) { tabela.size }
        fun zapisiNiz(s: String) = o.writeInt(niz(s))
        fun zapisiNizN(s: String?) { if (s == null) o.writeInt(-1) else o.writeInt(niz(s)) }
        fun zapisiSeznam(l: List<String>) { o.writeInt(l.size); for (s in l) zapisiNiz(s) }
        fun filter(f: NetworkFilter) {
            var z = 0
            if (f.exception) z = z or 1
            if (f.important) z = z or 2
            if (f.thirdParty != null) z = z or 4
            if (f.thirdParty == true) z = z or 8
            o.writeByte(z)
            o.writeInt(f.typeMask)
            zapisiNiz(f.text)
            zapisiSeznam(f.domains); zapisiSeznam(f.excludedDomains)
            zapisiNizN(f.hostAnchor)
            val p = f.pattern
            if (p == null) o.writeInt(-1) else { zapisiNiz(p.pattern()); o.writeInt(p.flags()) }
            zapisiNizN(f.token)
            zapisiNizN(f.abpBody)
            o.writeInt(f.abpFlags)
        }
        fun skupina(m: Map<String, List<NetworkFilter>>) {
            var n = 0; for (l in m.values) n += l.size
            o.writeInt(n); for (l in m.values) for (f in l) filter(f)
        }
        fun skupina(l: List<NetworkFilter>) { o.writeInt(l.size); for (f in l) filter(f) }
        fun kozm(m: Map<String, List<String>>) {
            o.writeInt(m.size)
            for ((k, l) in m) { zapisiNiz(k); zapisiSeznam(l) }
        }
    }

    /** Zapise nabor (atomarno: zacasna datoteka, nato preimenovanje). Vrne false, ce zapisa ni (nobena napaka ne uide ven). */
    fun zapisi(set: FilterSet, kljuc: String, datoteka: File): Boolean {
        val tmp = File(datoteka.parentFile, datoteka.name + ".tmp")
        return try {
            val w = Pisalnik()
            w.o.writeInt(set.size); w.o.writeInt(set.skipped)
            w.skupina(set.hostBlocks); w.skupina(set.hostExceptions)
            w.skupina(set.tokenBlocks); w.skupina(set.tokenExceptions)
            w.skupina(set.untokenizedBlocks); w.skupina(set.untokenizedExceptions)
            w.o.writeInt(set.cosmetic.size)
            w.kozm(set.cosmetic.byDomain); w.kozm(set.cosmetic.exceptionsByDomain)
            w.o.flush()
            if (w.tabela.size > NAJVEC_NIZOV) return false
            val izhod = ByteArrayOutputStream(w.telo.size() + w.tabela.size * 24 + 256)
            val d = DataOutputStream(izhod)
            d.writeInt(MAGIC); d.writeInt(RAZLICICA_ZAPISA); d.writeUTF(kljuc)
            d.writeInt(w.tabela.size)
            for (s in w.tabela.keys) d.writeUTF(s)
            d.write(w.telo.toByteArray())
            d.flush()
            val bajti = izhod.toByteArray()
            val crc = CRC32().also { it.update(bajti) }.value.toInt()
            datoteka.parentFile?.mkdirs()
            tmp.outputStream().use { it.write(bajti); val c = ByteArray(4); for (i in 0..3) c[i] = (crc ushr (24 - 8 * i)).toByte(); it.write(c) }
            if (!tmp.renameTo(datoteka)) { tmp.delete(); return false }
            true
        } catch (e: Throwable) {
            try { tmp.delete() } catch (_: Throwable) { }
            false
        }
    }

    /** Samo glava (magic, razlicica zapisa, kljuc): nekaj bajtov, brez branja nabora. */
    fun glavaVelja(datoteka: File, kljuc: String): Boolean = try {
        DataInputStream(FileInputStream(datoteka).buffered(256)).use { i ->
            i.readInt() == MAGIC && i.readInt() == RAZLICICA_ZAPISA && i.readUTF() == kljuc
        }
    } catch (_: Throwable) { false }

    /** Prebere nabor; null, ce datoteke ni, kljuc ali CRC ne stima ali je oblika napacna. */
    fun preberi(datoteka: File, kljuc: String): FilterSet? = try {
        val vse = datoteka.readBytes()
        if (vse.size < 16) null else {
            val telo = vse.size - 4
            val crc = CRC32().also { it.update(vse, 0, telo) }.value.toInt()
            var zapisanCrc = 0
            for (i in 0..3) zapisanCrc = (zapisanCrc shl 8) or (vse[telo + i].toInt() and 0xff)
            if (crc != zapisanCrc) null else beri(DataInputStream(ByteArrayInputStream(vse, 0, telo)), kljuc)
        }
    } catch (_: Throwable) { null }

    private fun beri(i: DataInputStream, kljuc: String): FilterSet? {
        if (i.readInt() != MAGIC || i.readInt() != RAZLICICA_ZAPISA || i.readUTF() != kljuc) return null
        val nNizov = i.readInt()
        if (nNizov < 0 || nNizov > NAJVEC_NIZOV) return null
        val t = arrayOfNulls<String>(nNizov)
        for (k in 0 until nNizov) t[k] = i.readUTF()
        fun niz(): String = t[i.readInt()]!!
        fun nizN(): String? { val x = i.readInt(); return if (x < 0) null else t[x]!! }
        fun seznam(): List<String> {
            val n = i.readInt()
            if (n < 0) throw IllegalStateException()
            if (n == 0) return emptyList()
            val l = ArrayList<String>(n); repeat(n) { l.add(niz()) }; return l
        }
        fun filter(): NetworkFilter {
            val z = i.readByte().toInt()
            val tip = i.readInt()
            val text = niz()
            val dom = seznam(); val izkl = seznam()
            val host = nizN()
            val ps = i.readInt()
            val pattern = if (ps < 0) null else Pattern.compile(t[ps]!!, i.readInt())
            val token = nizN(); val body = nizN(); val bflags = i.readInt()
            return NetworkFilter(text, z and 1 != 0, z and 2 != 0, tip, if (z and 4 != 0) (z and 8 != 0) else null,
                dom, izkl, host, pattern, token, body, bflags)
        }
        val size = i.readInt(); val skipped = i.readInt()
        fun predali(bucket: (NetworkFilter) -> String?): Pair<HashMap<String, MutableList<NetworkFilter>>, ArrayList<NetworkFilter>> {
            val n = i.readInt()
            val m = HashMap<String, MutableList<NetworkFilter>>()
            val rest = ArrayList<NetworkFilter>()
            repeat(n) {
                val f = filter(); val k = bucket(f)
                if (k == null) rest.add(f) else m.getOrPut(k) { ArrayList(1) }.add(f)
            }
            return m to rest
        }
        val hb = predali { it.hostAnchor }.first
        val he = predali { it.hostAnchor }.first
        val tb = predali { it.token }.first
        val te = predali { it.token }.first
        val ub = predali { null }.second
        val ue = predali { null }.second
        val cosmSize = i.readInt()
        fun kozm(): HashMap<String, MutableList<String>> {
            val n = i.readInt(); val m = HashMap<String, MutableList<String>>(maxOf(16, n * 2))
            repeat(n) { val k = niz(); val l = seznam(); m[k] = if (l is ArrayList) l else ArrayList(l) }
            return m
        }
        val cb = kozm(); val ce = kozm()
        if (i.available() != 0) return null
        val cosmetic = if (cosmSize == 0) CosmeticSet.EMPTY else CosmeticSet(cb, ce, cosmSize)
        return FilterSet(hb, he, tb, te, ub, ue, size, skipped, cosmetic)
    }
}
