/*
 * Predprevedeni nabor pravil EasyList na disku (12. 11. 2026).
 *
 * Zakaj: na TV prevod EasyList (49 299 pravil) traja ~1,7 s od zagona, zahteve podvirov v shouldInterceptRequest pa na ta prevod
 * cakajo (ZascitaZagona; izmerjeno: skupaj 4,5-5,6 s cakanja na ~65 klicev, do 0,8 s na klic; filtriranje samo ~1,5 ms/klic).
 * Nabor, ki je ze prevajen, se zapise v datoteko in ob naslednjem zagonu bere brez razclenjevanja pravil, brez branja seznama in
 * brez SHA-256 nad seznamom. Odlocitve so iste (isti objekti NetworkFilter in isti vrstni red v predalih); zascita se ne oslabi:
 * zahteve se se vedno ne spustijo mimo seznama, cakanje se samo skrajsa.
 *
 * Varnost: datoteka je v zasebni mapi aplikacije. CRC32 in kljuc varujeta SAMO pred poskodbo medija in zastarelostjo, NE sta
 * overitev (napadalcu s pisanjem v mapo zadostuje izracunati CRC32 in SHA-256, ki nista skrivnost; ta pa ima ze vec). Kljuc:
 * SHA-256 DEJANSKO prevedenih seznamov (id + sha256), razlicica aplikacije, razlicica zapisa in odtis razclenjevalnika pravil
 * (samodejno: odtis prevoda vzorcnih pravil, da sprememba parse/compile brez dviga versionCode ne pusti zastarelega nabora).
 * Prazen nabor se NIKOLI ne zapise (poskodovan seznam bi sicer izklopil EasyList do spremembe na strezniku). Karkoli ne stima
 * (kljuc, dolzina, CRC, oblika, izjema) = null in prevod iz seznama kot prej. Pure JVM (brez Androida). Datoteka FilterListEngine.kt
 * je souporabljena z mobilno aplikacijo: ta datoteka je neobvezen dodatek.
 *
 * Zapis (v2): magic, razlicica, kljuc (dolzina + UTF-8), skupna dolzina datoteke (long), tabela nizov (dolzina + UTF-8, vsak niz
 * enkrat), telo, CRC32 vsega prej. Bere se z ByteBuffer (brez monitorjev), pise se v tok brez vmesnih kopij celotne datoteke.
 */
package com.safeer.threatfeed

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.regex.Pattern
import java.util.zip.CRC32

object FilterSetPredpomnilnik {
    private const val MAGIC = 0x53464332      // "SFC2"
    private const val RAZLICICA_ZAPISA = 2
    /** Najvec zapisanih nizov na datoteko (varovalka pred pokvarjeno datoteko). */
    private const val NAJVEC_NIZOV = 4_000_000
    /** Najvecja datoteka, ki jo beremo (EasyList ~4,4 MiB). */
    private const val NAJVEC_BAJTOV = 64L * 1024 * 1024

    /** Vzorcna pravila za odtis razclenjevalnika: pokrivajo sidra, moznosti, domene, regex, kozmetiko, izjeme. */
    private val VZORCNA_PRAVILA = listOf(
        "||oglas.example^", "||sled.example^\$third-party,script", "@@||oglas.example^\$script", "/banner.", "|https://tocno.example/pot*.js^|",
        "||x.example/pot^\$image,domain=a.example|~b.example,important", "/regex[0-9]+/", "*ozadje*", "a*b*c|", "^foo^bar^|",
        "dom.si##.reklama", "dom.si,drugi.si##.skupno", "dom.si#@#.skupno", "~dom.si##.negirano", "##.splosno", "dom.si#?#.x:has(a)",
        "||oglas2.example^\$redirect=nic", "||čš.example^", "||sub.domena.example/pot^", ".swf|",
    )

    @Volatile internal var odtisPrepis: String? = null
    private val odtisIzracunan: String by lazy {
        val w = Pisalnik()
        pisiNabor(w, FilterListEngine.compile(VZORCNA_PRAVILA))
        w.o.flush()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        for (s in w.tabela.keys) md.update(s.toByteArray(Charsets.UTF_8)).also { md.update(0) }
        md.update(w.telo.toByteArray())
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Odtis razclenjevalnika pravil (spremeni se, ce se spremeni rezultat prevoda vzorcnih pravil). */
    fun odtisRazclenjevalnika(): String = odtisPrepis ?: odtisIzracunan

    /** Kljuc predpomnilnika: SHA-256 (hex) nad deli (npr. razlicica aplikacije, id in SHA-256 seznamov). */
    fun kljuc(deli: List<String>): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update("safeer-easylist-predpomnilnik-$RAZLICICA_ZAPISA".toByteArray(Charsets.UTF_8))
        md.update(0); md.update(odtisRazclenjevalnika().toByteArray(Charsets.UTF_8))
        for (d in deli) { md.update(0); md.update(d.toByteArray(Charsets.UTF_8)) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Kljuc za ZAPIS iz seznamov, ki so bili res prevedeni ([prevedeni]); null, ce zapisa ne smemo narediti: ni surovih seznamov,
     * manjka SHA-256 ali se prevedeni surovi seznami ne ujemajo z metapodatki agenta ([shranjeni]) - poskodovan ali nebran seznam
     * ne sme dobiti veljavnega kljuca (pregled 12. 11. 2026, N2).
     */
    fun kljucZaZapis(razlicica: String, prevedeni: List<PlainList>, shranjeni: List<PlainListInfo>): String? {
        val raw = prevedeni.filter { it.source.raw }
        if (raw.isEmpty() || raw.any { it.sha256.isEmpty() }) return null
        val a = raw.associate { it.source.id to it.sha256 }
        val b = shranjeni.filter { it.source.raw }.associate { it.source.id to it.sha256 }
        if (a != b) return null
        return kljuc(listOf(razlicica) + raw.flatMap { listOf(it.source.id, it.sha256) })
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

    private fun pisiNabor(w: Pisalnik, set: FilterSet) {
        w.o.writeInt(set.size); w.o.writeInt(set.skipped)
        w.skupina(set.hostBlocks); w.skupina(set.hostExceptions)
        w.skupina(set.tokenBlocks); w.skupina(set.tokenExceptions)
        w.skupina(set.untokenizedBlocks); w.skupina(set.untokenizedExceptions)
        w.o.writeInt(set.cosmetic.size)
        w.kozm(set.cosmetic.byDomain); w.kozm(set.cosmetic.exceptionsByDomain)
    }

    /**
     * Zapise nabor: atomarno (zacasna datoteka, fsync, preimenovanje), tok brez kopij celotne datoteke. PRAZEN nabor se ne zapise
     * (vrne false, datoteke ne ustvari). Vrne false tudi ob vsaki napaki (nobena ne uide ven).
     */
    fun zapisi(set: FilterSet, kljuc: String, datoteka: File): Boolean {
        if (set.size <= 0) return false
        val tmp = File(datoteka.parentFile, datoteka.name + ".tmp")
        return try {
            val w = Pisalnik()
            pisiNabor(w, set)
            w.o.flush()
            if (w.tabela.size > NAJVEC_NIZOV) return false
            val kb = kljuc.toByteArray(Charsets.UTF_8)
            val nizi = ArrayList<ByteArray>(w.tabela.size)
            var vsotaNizov = 0L
            for (s in w.tabela.keys) {
                val b = s.toByteArray(Charsets.UTF_8)
                if (String(b, Charsets.UTF_8) != s) return false     // npr. nepar nadomestnik: UTF-8 ga ne ohrani, ne zapisemo
                nizi.add(b); vsotaNizov += 4 + b.size
            }
            val telo = w.telo.toByteArray()
            val skupaj = 4L + 4 + 4 + kb.size + 8 + 4 + vsotaNizov + telo.size + 4
            datoteka.parentFile?.mkdirs()
            FileOutputStream(tmp).use { fos ->
                val crc = CRC32()
                val bo = BufferedOutputStream(fos, 64 * 1024)
                fun pisi(b: ByteArray) { crc.update(b); bo.write(b) }
                val h = ByteBuffer.allocate(4 + 4 + 4 + kb.size + 8 + 4)
                h.putInt(MAGIC).putInt(RAZLICICA_ZAPISA).putInt(kb.size).put(kb).putLong(skupaj).putInt(nizi.size)
                pisi(h.array())
                val dolzina = ByteArray(4)
                for (b in nizi) {
                    val n = b.size
                    dolzina[0] = (n ushr 24).toByte(); dolzina[1] = (n ushr 16).toByte(); dolzina[2] = (n ushr 8).toByte(); dolzina[3] = n.toByte()
                    pisi(dolzina); pisi(b)
                }
                pisi(telo)
                val c = crc.value.toInt()
                bo.write(byteArrayOf((c ushr 24).toByte(), (c ushr 16).toByte(), (c ushr 8).toByte(), c.toByte()))
                bo.flush()
                fos.fd.sync()      // TV se izklaplja z vtikacem: brez sync lahko po preimenovanju ostane okrnjeno telo (N1)
            }
            if (tmp.length() != skupaj) { tmp.delete(); return false }
            if (!tmp.renameTo(datoteka)) { tmp.delete(); return false }
            true
        } catch (e: Throwable) {
            try { tmp.delete() } catch (_: Throwable) { }
            false
        }
    }

    /**
     * Glava (magic, razlicica zapisa, kljuc) in dolzina datoteke proti zapisani: nekaj bajtov, brez branja nabora. Okrnjena datoteka
     * z veljavno glavo ne velja (N1); sprememba bajtov v telesu se ujame sele pri [preberi] (CRC).
     */
    fun glavaVelja(datoteka: File, kljuc: String): Boolean = try {
        val kb = kljuc.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(4 + 4 + 4 + kb.size + 8)
        FileInputStream(datoteka).use { i ->
            var n = 0
            while (n < buf.size) { val r = i.read(buf, n, buf.size - n); if (r < 0) break; n += r }
            if (n < buf.size) false else {
                val b = ByteBuffer.wrap(buf)
                b.getInt() == MAGIC && b.getInt() == RAZLICICA_ZAPISA && b.getInt() == kb.size &&
                    String(buf, 12, kb.size, Charsets.UTF_8) == kljuc && ByteBuffer.wrap(buf, 12 + kb.size, 8).getLong() == datoteka.length()
            }
        }
    } catch (_: Throwable) { false }

    /** Prebere nabor; null, ce datoteke ni, kljuc, dolzina ali CRC ne stima ali je oblika napacna. */
    fun preberi(datoteka: File, kljuc: String): FilterSet? = try {
        if (datoteka.length() > NAJVEC_BAJTOV) null else {
            val vse = datoteka.readBytes()
            if (vse.size < 24) null else {
                val telo = vse.size - 4
                val crc = CRC32().also { it.update(vse, 0, telo) }.value.toInt()
                val zapisan = ByteBuffer.wrap(vse, telo, 4).getInt()
                if (crc != zapisan) null else beri(ByteBuffer.wrap(vse, 0, telo), kljuc, vse.size.toLong())
            }
        }
    } catch (_: Throwable) { null }

    /**
     * Osvezi datoteko po prevodu: ce je zadnje branje spodletelo ([branjeNeuspelo]), jo najprej zbrisemo (okrnjeno/pokvarjeno
     * telo z veljavno glavo bi sicer ostalo in vsak zagon bi placal polni prevod, N1); nato jo zapisemo, ce ne velja ze glava.
     * Vrne true, ce datoteka po klicu velja (nova ali ze veljavna).
     */
    fun osvezi(set: FilterSet, kljuc: String, datoteka: File, branjeNeuspelo: Boolean): Boolean {
        if (branjeNeuspelo) try { datoteka.delete() } catch (_: Throwable) { }
        if (glavaVelja(datoteka, kljuc)) return true
        return zapisi(set, kljuc, datoteka)
    }

    private fun beri(b: ByteBuffer, kljuc: String, dolzinaDatoteke: Long): FilterSet? {
        if (b.getInt() != MAGIC || b.getInt() != RAZLICICA_ZAPISA) return null
        fun str(): String {
            val n = b.getInt()
            if (n < 0 || n > b.remaining()) throw IllegalStateException()
            val s = String(b.array(), b.arrayOffset() + b.position(), n, Charsets.UTF_8)
            b.position(b.position() + n)
            return s
        }
        if (str() != kljuc) return null
        if (b.getLong() != dolzinaDatoteke) return null
        val nNizov = b.getInt()
        if (nNizov < 0 || nNizov > NAJVEC_NIZOV) return null
        val t = arrayOfNulls<String>(nNizov)
        for (k in 0 until nNizov) t[k] = str()
        fun niz(): String = t[b.getInt()]!!
        fun nizN(): String? { val x = b.getInt(); return if (x < 0) null else t[x]!! }
        fun seznam(): List<String> {
            val n = b.getInt()
            if (n < 0) throw IllegalStateException()
            if (n == 0) return emptyList()
            val l = ArrayList<String>(n); repeat(n) { l.add(niz()) }; return l
        }
        fun filter(): NetworkFilter {
            val z = b.get().toInt()
            val tip = b.getInt()
            val text = niz()
            val dom = seznam(); val izkl = seznam()
            val host = nizN()
            val ps = b.getInt()
            val pattern = if (ps < 0) null else Pattern.compile(t[ps]!!, b.getInt())
            val token = nizN(); val body = nizN(); val bflags = b.getInt()
            return NetworkFilter(text, z and 1 != 0, z and 2 != 0, tip, if (z and 4 != 0) (z and 8 != 0) else null,
                dom, izkl, host, pattern, token, body, bflags)
        }
        val size = b.getInt(); val skipped = b.getInt()
        fun predali(bucket: (NetworkFilter) -> String?): Pair<HashMap<String, MutableList<NetworkFilter>>, ArrayList<NetworkFilter>> {
            val n = b.getInt()
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
        val cosmSize = b.getInt()
        fun kozm(): HashMap<String, MutableList<String>> {
            val n = b.getInt(); val m = HashMap<String, MutableList<String>>(maxOf(16, n * 2))
            repeat(n) { val k = niz(); val l = seznam(); m[k] = if (l is ArrayList) l else ArrayList(l) }
            return m
        }
        val cb = kozm(); val ce = kozm()
        if (b.remaining() != 0 || size <= 0) return null
        val cosmetic = if (cosmSize == 0) CosmeticSet.EMPTY else CosmeticSet(cb, ce, cosmSize)
        return FilterSet(hb, he, tb, te, ub, ue, size, skipped, cosmetic)
    }
}
