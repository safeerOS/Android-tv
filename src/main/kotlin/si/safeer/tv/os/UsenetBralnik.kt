package si.safeer.tv.os

import android.os.Looper
import java.io.Closeable
import java.io.IOException
import java.util.PriorityQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bralnik za lasten pogon Usenet, korak 2 (lastnik 26. 10. 2026) - enako kot core/usenet_bralnik.py v Safeer-linux.
 *
 * Prototip 25. 10. 2026 (NzbDAV): za 60 s ogleda je slo z Useneta 3-7x vec, kot se je pogledalo, tudi po zaprtju. Tu je
 * okno vnaprej OMEJENO in raste z zaporednim branjem ([oknoVnaprej]; skupni primeri tok_okno): po odprtju ali skoku samo
 * zacetno okno, nato dvakratnik zaporedno prebranega do meje profila; ob skoku se cakajoca branja vnaprej zavrzejo.
 * Segmenti hkrati prek [UsenetNntp.body] z lastnimi nitmi (zahtevano pred vnaprej), LRU v RAM z mejo po profilu (TV ima
 * malo RAM - Philips: heapgrowthlimit 192 MB). [Bralnik.close] ne caka na zahteve v teku.
 * Nikoli na glavni niti (omrezje). message-id in imena niso v toString, izjemi ali dnevniku.
 */
object UsenetBralnik {
    private const val MIB = 1024L * 1024

    /** [okno] najvecje okno vnaprej, [zacetno] po odprtju ali skoku, [lru] dekodirani segmenti v RAM, [vzporedno] branj. */
    data class Profil(val okno: Long, val zacetno: Long, val lru: Long, val vzporedno: Int)

    /**
     * Lastnik 28. 10. 2026: skladba albuma (zvok, nekaj MB/min) - manjse okno in predpomnilnik kot film; album drzi do
     * AlbumIzvor.NAJVEC_ODPRTIH skladb hkrati (prej 5 x 32 MiB na TV = OutOfMemoryError po preklopih).
     * 29. 10. 2026: za visoko-bitne skladbe (npr. 96 kHz 24-bit FLAC) okno 8 MiB in 4 vzporedna branja preprecujejo stradanje medpomnilnika.
     */
    fun profilAlbuma(p: Profil): Profil =
        p.copy(okno = minOf(p.okno, 8 * MIB), lru = minOf(p.lru, 12 * MIB), vzporedno = minOf(p.vzporedno, 4))

    /** Po profilu naprave (skupni primeri tok_profili; enako na namizju). */
    val PROFILI: Map<String, Profil> = linkedMapOf(
        "tv" to Profil(8 * MIB, 1 * MIB, 32 * MIB, 4),
        "telefon" to Profil(8 * MIB, 1 * MIB, 48 * MIB, 6),
        "namizje" to Profil(16 * MIB, 1 * MIB, 96 * MIB, 8),
    )

    /** Okno vnaprej: dvakratnik zaporedno prebranega od zadnjega skoka, najmanj [zacetno], najvec [najvec]. */
    fun oknoVnaprej(zaporednoPrebrano: Long, zacetno: Long, najvec: Long): Long =
        maxOf(zacetno, minOf(najvec, 2 * maxOf(0L, zaporednoPrebrano)))

    class KosVira(val datoteka: Int, val odmik: Long, val dolzina: Long)

    /** Film kot zaporedje kosov datotek iz NZB (gola datoteka: en kos; RAR store: kos v vsakem zvezku). */
    class Vir(val preslikave: List<UsenetTok.Preslikava>, val kosi: List<KosVira>, val ime: String? = null) {
        private val zacetki = LongArray(kosi.size + 1).also { z -> for (i in kosi.indices) z[i + 1] = z[i] + kosi[i].dolzina }
        val velikost: Long = zacetki[kosi.size]

        init {
            for (k in kosi) {
                val p = preslikave[k.datoteka]
                if (k.dolzina <= 0 || k.odmik < 0 || k.odmik + k.dolzina > p.velikost) throw UsenetTok.NapakaToka(UsenetTok.Izid.NESKLADNO)
            }
        }

        override fun toString() = "Vir($velikost B, ${kosi.size} kosov)"

        private fun kos(odmik: Long): Int {
            var lo = 0
            var hi = kosi.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (zacetki[mid] <= odmik) lo = mid else hi = mid - 1
            }
            return lo
        }

        /** (datoteka, odmik v datoteki, do konca kosa) za odmik v filmu. */
        fun kje(odmik: Long): Triple<Int, Long, Long> {
            if (odmik < 0 || odmik >= velikost) throw UsenetTok.NapakaToka(UsenetTok.Izid.IZVEN)
            val i = kos(odmik)
            return Triple(kosi[i].datoteka, kosi[i].odmik + (odmik - zacetki[i]), zacetki[i + 1] - odmik)
        }

        /** [(datoteka, odmik v datoteki, dolzina)] za [odmik, odmik + dolzina). */
        fun razponi(odmik: Long, dolzina: Long): List<Triple<Int, Long, Long>> {
            val izid = ArrayList<Triple<Int, Long, Long>>()
            val konec = minOf(velikost, odmik + dolzina)
            var o = odmik
            while (o < konec) {
                val (d, v, doKonca) = kje(o)
                val n = minOf(konec - o, doKonca)
                izid += Triple(d, v, n)
                o += n
            }
            return izid
        }
    }

    private data class Kljuc(val datoteka: Int, val stevilka: Int)

    private class Naloga(val prednost: Int, val zap: Long, val kljuc: Kljuc)

    /** Branje filma z omejenim oknom vnaprej. Varno za vec niti. [telo] = [UsenetNntp.body]. */
    class Bralnik(private val telo: (String) -> ByteArray, val vir: Vir, val profil: Profil = PROFILI.getValue("namizje")) : Closeable {
        private val zaklep = ReentrantLock()
        private val pogoj = zaklep.newCondition()
        /** LRU mora drzati vsaj okno in segment ob njem, sicer bi branje vnaprej izrinilo samo sebe. */
        val lruMeja: Long = maxOf(profil.lru, 2 * profil.okno + 8 * MIB)
        private val lru = LinkedHashMap<Kljuc, ByteArray>(64, 0.75f, true)
        @Volatile var lruBajtov = 0L; private set
        private val vTeku = HashMap<Kljuc, CompletableFuture<ByteArray>>()
        private val cakajo = HashMap<Kljuc, CompletableFuture<ByteArray>>()
        /** T3: segment, ki je padel, se do konca seje ne prenese znova; napaka omrezja dobi se en poskus. */
        private val napake = HashMap<Kljuc, Throwable>()
        private val poskusi = HashMap<Kljuc, Int>()
        /** W3: koliko bralcev caka na kljuc - okno drugega bralca ga ne preklice. */
        private val zahtevani = HashMap<Kljuc, Int>()
        private val vrsta = PriorityQueue<Naloga>(compareBy<Naloga>({ it.prednost }, { it.zap }))
        private val zap = AtomicLong()
        @Volatile var zaprt = false; private set
        private var konecZadnjega = 0L
        private var zaporedno = 0L
        /** Bajti teles clankov z Useneta (kodirani), stevilo segmentov, postrezeni bajti. */
        val zOmrezja = AtomicLong()
        val segmentov = AtomicLong()
        val postrezeno = AtomicLong()
        private val niti = Executors.newFixedThreadPool(maxOf(1, profil.vzporedno)) { r ->
            Thread(r, "safeer-usenet-bralnik").apply { isDaemon = true }
        }

        init {
            repeat(maxOf(1, profil.vzporedno)) { niti.execute { delavec() } }
        }

        override fun toString() = "Bralnik($vir, z omrezja ${zOmrezja.get()} B)"

        /** Ali kakega branja ni v teku (preizkusi, meritev). */
        fun miruje(): Boolean = zaklep.withLock { vTeku.isEmpty() && cakajo.isEmpty() }

        private fun delavec() {
            while (true) {
                var kljuc: Kljuc? = null
                var f: CompletableFuture<ByteArray>? = null
                zaklep.withLock {
                    while (vrsta.isEmpty() && !zaprt) pogoj.await()
                    if (zaprt) return
                    val n = vrsta.poll()
                    kljuc = n.kljuc
                    f = cakajo.remove(n.kljuc)
                }
                val k = kljuc ?: return
                val ff = f ?: continue  // zavrzeno ob skoku
                val podatki = try { prenesi(k) } catch (e: Throwable) {
                    zaklep.withLock {
                        vTeku.remove(k)
                        zapomni(k, e)
                    }
                    ff.completeExceptionally(e)  // ce je close ze nastavil ZAPRTO, nima ucinka
                    null
                }
                if (podatki != null) {
                    zaklep.withLock {
                        vTeku.remove(k)
                        if (!zaprt) vLru(k, podatki)
                    }
                    ff.complete(podatki)
                }
            }
        }

        /** Pod zaklepom: trajna napaka segmenta takoj, napaka omrezja po [NAJVEC_POSKUSOV]. */
        private fun zapomni(k: Kljuc, e: Throwable) {
            if (e is UsenetTok.NapakaToka || e is UsenetRar.NapakaRar) { napake[k] = e; return }
            val n = (poskusi[k] ?: 0) + 1
            poskusi[k] = n
            if (n >= NAJVEC_POSKUSOV) napake[k] = e
        }

        private fun prenesi(k: Kljuc): ByteArray {
            val p = vir.preslikave[k.datoteka]
            val seg = p.segment(k.stevilka)
            val surovo = try { telo(seg.id) } catch (e: UsenetNntp.NapakaNntp) {
                if (e.vrsta == UsenetNntp.Vrsta.NI_CLANKA) throw UsenetTok.NapakaToka(UsenetTok.Izid.LUKNJA)
                throw e
            }
            zOmrezja.addAndGet(surovo.size.toLong())
            segmentov.incrementAndGet()
            val d = try { UsenetYenc.dekodiraj(surovo) } catch (_: UsenetYenc.NapakaYenc) {
                throw UsenetTok.NapakaToka(UsenetTok.Izid.POKVARJEN)
            }
            p.vnesi(k.stevilka, d)
            return d.podatki
        }

        /** Pod zaklepom. */
        private fun vLru(k: Kljuc, podatki: ByteArray) {
            if (lru.containsKey(k)) { lru[k]; return }
            lru[k] = podatki
            lruBajtov += podatki.size
            val it = lru.entries.iterator()
            while (lruBajtov > lruMeja && lru.size > 1 && it.hasNext()) {
                val e = it.next()
                lruBajtov -= e.value.size
                it.remove()
            }
        }

        /** Pod zaklepom: future segmenta, null = ze v LRU ali (pri branju vnaprej) segment ze padel. */
        private fun zahtevaj(k: Kljuc, prednost: Int): CompletableFuture<ByteArray>? {
            if (lru.containsKey(k)) return null
            napake[k]?.let { e ->
                if (prednost > 0) return null  // ne znova vnaprej (T3)
                return CompletableFuture<ByteArray>().apply { completeExceptionally(e) }
            }
            vTeku[k]?.let { f ->
                if (prednost == 0 && cakajo.containsKey(k)) {
                    vrsta += Naloga(0, zap.incrementAndGet(), k)  // vnaprej -> zahtevano: naprej v vrsti
                    pogoj.signal()
                }
                return f
            }
            val f = CompletableFuture<ByteArray>()
            vTeku[k] = f
            cakajo[k] = f
            vrsta += Naloga(prednost, zap.incrementAndGet(), k)
            pogoj.signal()
            return f
        }

        private fun segment(k: Kljuc): ByteArray {
            repeat(NAJVEC_PREKLICEV) {
                val f = zaklep.withLock {
                    if (zaprt) throw UsenetTok.NapakaToka(UsenetTok.Izid.ZAPRTO)
                    lru[k]?.let { return it }
                    val ff = zahtevaj(k, 0)
                    if (ff != null) zahtevani[k] = (zahtevani[k] ?: 0) + 1
                    ff
                } ?: return segment(k)
                try {
                    return f.get()
                } catch (_: CancellationException) {
                    // branje vnaprej, na katero smo cakali, je skok iz druge povezave zavrgel: znova zahtevano
                } catch (e: ExecutionException) {
                    val v = e.cause
                    if (v is IOException) throw v
                    throw IOException("usenet bralnik: " + (v?.javaClass?.simpleName ?: "napaka"))
                } catch (e: InterruptedException) {
                    // W4: Media3 ob preklicu nalaganja prekine nit nalagalnika - DataSource mora vrzi InterruptedIOException
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("usenet bralnik: prekinjeno")
                } finally {
                    zaklep.withLock {
                        val n = (zahtevani[k] ?: 1) - 1
                        if (n > 0) zahtevani[k] = n else zahtevani.remove(k)
                    }
                }
            }
            throw UsenetTok.NapakaToka(UsenetTok.Izid.PREKINJENO)  // W3: prej while (true) brez meje
        }

        private fun beriDatoteko(d: Int, odmik: Long, n: Long): ByteArray {
            val p = vir.preslikave[d]
            repeat(UsenetTok.NAJVEC_ISKANJ) {
                val u = p.poisci(odmik)
                val podatki = segment(Kljuc(d, u.stevilka))
                val u2 = p.poisci(odmik)  // segment je zdaj sidro
                if (u2.natancno && u2.stevilka == u.stevilka) {
                    val od = u2.odmik.toInt()
                    return podatki.copyOfRange(od, minOf(podatki.size.toLong(), od + n).toInt())
                }
            }
            throw UsenetTok.NapakaToka(UsenetTok.Izid.NESKLADNO)
        }

        private fun vnaprej(od: Long) {
            val okno = oknoVnaprej(zaporedno, profil.zacetno, profil.okno)
            val kljuci = ArrayList<Kljuc>()
            for ((d, o, n) in vir.razponi(od, okno)) for (st in vir.preslikave[d].segmentiOkna(o, n)) kljuci += Kljuc(d, st)
            zaklep.withLock {
                val zelja = kljuci.toHashSet()
                // skok: cakajoca branja vnaprej zunaj okna proc - ne tista, na katera caka kak bralec (W3)
                val odvec = cakajo.keys.filter { it !in zelja && (zahtevani[it] ?: 0) == 0 }
                for (k in odvec) {
                    cakajo.remove(k)?.cancel(false)
                    vTeku.remove(k)
                }
                for ((i, k) in kljuci.withIndex()) zahtevaj(k, 1 + i)
            }
        }

        /** Do [n] bajtov filma od [odmik]; prazno na koncu. Napaka: [UsenetTok.NapakaToka] ali [UsenetNntp.NapakaNntp]. */
        fun beri(odmik: Long, n: Int): ByteArray {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Usenet ne sme teci na glavni niti" }
            if (odmik >= vir.velikost || n <= 0) return ByteArray(0)
            if (odmik < 0) throw UsenetTok.NapakaToka(UsenetTok.Izid.IZVEN)
            zaklep.withLock {
                if (zaprt) throw UsenetTok.NapakaToka(UsenetTok.Izid.ZAPRTO)
                if (Math.abs(odmik - konecZadnjega) <= profil.zacetno) {
                    zaporedno += maxOf(0L, odmik + n - maxOf(odmik, konecZadnjega))
                } else {
                    zaporedno = 0  // skok
                }
            }
            val (d, o, doKonca) = vir.kje(odmik)
            val podatki = beriDatoteko(d, o, minOf(n.toLong(), doKonca))
            val konec = odmik + podatki.size
            zaklep.withLock { konecZadnjega = konec }
            postrezeno.addAndGet(podatki.size.toLong())
            vnaprej(konec)
            return podatki
        }

        /**
         * Konec predvajanja: vrsta proc, LRU sproscen, niti koncajo; bralci, ki cakajo na prenos v teku, se takoj zbudijo
         * z ZAPRTO (T9). Prenos clanka, ki ze tece, se konca v UsenetNntp, njegov izid se zavrze.
         */
        override fun close() {
            zaklep.withLock {
                zaprt = true
                for (f in vTeku.values) f.completeExceptionally(UsenetTok.NapakaToka(UsenetTok.Izid.ZAPRTO))
                for (f in cakajo.values) f.cancel(false)
                cakajo.clear()
                vrsta.clear()
                lru.clear()
                lruBajtov = 0
                pogoj.signalAll()
            }
            niti.shutdown()
        }
    }

    /**
     * Branje kot pri Media3 DataSource (open -> read ... -> close) nad [Bralnik] - cisti del, preizkusen na JVM; ovoj za
     * Media3 je [UsenetMedia3Vir]. [open] vrne stevilo bajtov do konca obsega.
     */
    class Branje(private val bralnik: Bralnik) {
        private var polozaj = 0L
        private var preostane = 0L

        fun open(pozicija: Long, dolzina: Long): Long {
            if (pozicija < 0 || pozicija > bralnik.vir.velikost) throw UsenetTok.NapakaToka(UsenetTok.Izid.IZVEN)
            polozaj = pozicija
            preostane = if (dolzina < 0) bralnik.vir.velikost - pozicija else minOf(dolzina, bralnik.vir.velikost - pozicija)
            return preostane
        }

        /** Prebranih bajtov ali -1 na koncu. */
        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (preostane <= 0) return -1
            val kos = bralnik.beri(polozaj, minOf(length.toLong(), preostane).toInt())
            if (kos.isEmpty()) return -1
            System.arraycopy(kos, 0, buffer, offset, kos.size)
            polozaj += kos.size
            preostane -= kos.size
            return kos.size
        }

        fun close() { preostane = 0 }
    }

    // ------------------------------------------------------------------ priprava iz NZB

    private val VIDEO = listOf(".mkv", ".mp4", ".m4v", ".avi", ".ts", ".m2ts", ".webm", ".mov", ".wmv", ".mpg", ".mpeg")
    private val NI_VSEBINA = listOf(".par2", ".nfo", ".sfv", ".nzb", ".jpg", ".jpeg", ".png", ".txt", ".srr", ".url", ".md5")

    /** T3: napaka omrezja pri istem segmentu - po toliko poskusih se ne prenasa vec. */
    const val NAJVEC_POSKUSOV = 2
    /** W3: zahtevano branje, ki ga okno drugega bralca preklice, se ponovi najvec toliko krat. */
    const val NAJVEC_PREKLICEV = 3
    /** T4: priprava bere hkrati najvec toliko zvezkov in zacetek vsakega sprosti takoj po razclenitvi glav. */
    const val PRIPRAVA_HKRATI = 4
    /** T4: meja prometa priprave; vec = PREVEC (600 zvezkov po 700 KiB = 410 MiB). */
    const val NAJVEC_PRIPRAVE_BAJTOV = 512 * MIB
    /** T5: glave, ki niso v prvem segmentu zvezka: najvec toliko segmentov in bajtov na zvezek, sicer PREVEC. */
    const val NAJVEC_GLAV_SEGMENTOV = 8L
    const val NAJVEC_GLAV_BAJTOV = 16 * MIB
    /** Album v RAR: zvezek ima glavo vsake skladbe med podatki - vec segmentov za glave na zvezek (skupni promet ostane omejen). */
    const val NAJVEC_GLAV_SEGMENTOV_ALBUM = 48L
    const val NAJVEC_GLAV_BAJTOV_ALBUM = 64 * MIB

    /** 7z, zip, vec arhivov ... (deobfuskacija je F2) in izdaja brez filma. */
    class NapakaIzdaje(val izid: String) : IOException("usenet izdaja: $izid")

    /** T13: vzorec je samostojna beseda v imenu (»Film.Sample.mkv«), ne podniz (»Samplers.2026.mkv«) - tok_vzorec. */
    private val VZOREC = Regex("(^|[._ \\-\\[(])(sample|vzorec)([._ \\-\\])]|$)", RegexOption.IGNORE_CASE)

    fun jeVzorec(ime: String?): Boolean = VZOREC.containsMatchIn(ime ?: "")

    private fun jeZvezek(ime: String): Boolean {
        val m = ime.lowercase()
        val k = m.substringAfterLast('.', "")
        return UsenetRar.vrstniRedZvezkov(listOf(ime)) != null || m.endsWith(".rar") ||
            (k.length == 3 && k[0] == 'r' && k.substring(1).all { it.isDigit() })
    }

    /** Najvecja datoteka; pri enaki velikosti prva (enako kot Python max po (bajtov, -i)). */
    private fun najvecja(nzb: UsenetNzb.Nzb, kandidati: List<Int>): Int {
        var naj = kandidati[0]
        for (i in kandidati) if (nzb.datoteke[i].bajtov > nzb.datoteke[naj].bajtov) naj = i
        return naj
    }

    /** ("rar" ali "video", indeksi datotek v vrstnem redu) - tok_izbira. Napaka: [NapakaIzdaje] ni_podprto ali ni_filma. */
    fun izberiDatoteke(nzb: UsenetNzb.Nzb): Pair<String, List<Int>> {
        val imena = nzb.datoteke.map { it.ime ?: "" }
        val rar = imena.indices.filter { jeZvezek(imena[it]) && !jeVzorec(imena[it]) }
        if (rar.isNotEmpty()) {
            val red = UsenetRar.vrstniRedZvezkov(rar.map { imena[it] }) ?: throw NapakaIzdaje("ni_podprto")
            return "rar" to red.map { rar[it] }
        }
        val video = imena.indices.filter { i -> VIDEO.any { imena[i].lowercase().endsWith(it) } && !jeVzorec(imena[i]) }
        if (video.isNotEmpty()) return "video" to listOf(najvecja(nzb, video))
        if (imena.any { i -> listOf(".7z", ".zip", ".001").any { i.lowercase().endsWith(it) } }) throw NapakaIzdaje("ni_podprto")
        val kandidati = imena.indices.filter { i -> NI_VSEBINA.none { imena[i].lowercase().endsWith(it) } }
        if (kandidati.isEmpty()) throw NapakaIzdaje("ni_filma")
        return "video" to listOf(najvecja(nzb, kandidati))
    }

    private class Prvi(val preslikava: UsenetTok.Preslikava, val zacetek: ByteArray, val bajtov: Int)

    private fun prviSegment(telo: (String) -> ByteArray, d: UsenetNzb.Datoteka): Prvi {
        val s = d.segmenti[0]
        val surovo = try { telo(s.id) } catch (e: UsenetNntp.NapakaNntp) {
            if (e.vrsta == UsenetNntp.Vrsta.NI_CLANKA) throw UsenetTok.NapakaToka(UsenetTok.Izid.LUKNJA)
            throw e
        }
        val del = try { UsenetYenc.dekodiraj(surovo) } catch (_: UsenetYenc.NapakaYenc) {
            throw UsenetTok.NapakaToka(UsenetTok.Izid.POKVARJEN)
        }
        return Prvi(UsenetTok.Preslikava(d, del, s.stevilka), if (del.zacetek == 0L) del.podatki else ByteArray(0), surovo.size)
    }

    private fun jeRar(z: ByteArray): Boolean = zacne(z, UsenetRar.PODPIS4) || zacne(z, UsenetRar.PODPIS5)

    private fun zacne(z: ByteArray, p: ByteArray) = z.size >= p.size && p.indices.all { z[it] == p[it] }

    /**
     * Glave zvezka iz zacetka (prvi segment); ce segajo cez in jih rabimo ([celo] ali ni nobene datoteke), branje naprej
     * prek bralnika brez okna vnaprej z mejo NAJVEC_GLAV_* (T5; prej brez meje). (zvezek, bajti z omrezja).
     */
    private fun glave(telo: (String) -> ByteArray, p: UsenetTok.Preslikava, zacetek: ByteArray, celo: Boolean,
                      najvecSegmentov: Long = NAJVEC_GLAV_SEGMENTOV, najvecBajtov: Long = NAJVEC_GLAV_BAJTOV): Pair<UsenetRar.Zvezek, Long> {
        val z = if (zacetek.isNotEmpty()) UsenetRar.razcleniZvezek(zacetek, p.velikost) else null
        if (z != null && !(z.nepopolno && (celo || z.vnosi.isEmpty()))) return z to 0L
        val b = Bralnik(telo, Vir(listOf(p), listOf(KosVira(0, 0, p.velikost))), Profil(0, 0, 8 * MIB, 1))
        fun meja() {
            if (b.segmentov.get() > najvecSegmentov || b.zOmrezja.get() > najvecBajtov) {
                throw UsenetTok.NapakaToka(UsenetTok.Izid.PREVEC)
            }
        }
        try {
            val zz = UsenetRar.razcleniZvezek({ o, k ->
                if (o + k <= zacetek.size) zacetek.copyOfRange(o.toInt(), (o + k).toInt())  // prvi segment je ze tu
                else {
                    meja()
                    beriVse(b, o, k).also { meja() }
                }
            }, p.velikost)
            return zz to b.zOmrezja.get()
        } finally {
            b.close()
        }
    }

    /** Prvi zvezek: odlocitev store/stisnjen/sifriran na celem seznamu glav, ce prvi segment ne zadosca (T2). */
    private fun odlociPrvega(telo: (String) -> ByteArray, p: UsenetTok.Preslikava, zacetek: ByteArray): Pair<UsenetRar.Zvezek, Long> {
        var izid = UsenetRar.preveri(zacetek, p.velikost)
        if (izid == UsenetRar.Izid.NEPOPOLNO) {
            val (z, bajtov) = glave(telo, p, zacetek, true)
            izid = UsenetRar.odloci(z, p.velikost)
            if (izid != UsenetRar.Izid.OK) throw UsenetRar.NapakaRar(izid)
            return z to bajtov
        }
        if (izid != UsenetRar.Izid.OK) throw UsenetRar.NapakaRar(izid)
        return UsenetRar.razcleniZvezek(zacetek, p.velikost) to 0L
    }

    private class Odlocitev(val vrsta: String, val izbrane: List<Int>, val preslikava: UsenetTok.Preslikava, val zvezek: UsenetRar.Zvezek?, val bajtov: Long)

    /**
     * Skupna odlocitev za [preveriIzdajo] in [pripravi] (T13). Zakrito ime s podpisom RAR je RAR z enim zvezkom (vec
     * zvezkov z zakritimi imeni ne znamo urediti: ni_podprto).
     */
    private fun prvi(nzb: UsenetNzb.Nzb, telo: (String) -> ByteArray): Odlocitev {
        val (vrsta, izbrane) = izberiDatoteke(nzb)
        val p = prviSegment(telo, nzb.datoteke[izbrane[0]])
        if (vrsta == "video" && !jeRar(p.zacetek)) return Odlocitev(vrsta, izbrane, p.preslikava, null, p.bajtov.toLong())
        val (z, b2) = odlociPrvega(telo, p.preslikava, p.zacetek)
        if (vrsta == "video" && z.vecZvezkov) throw NapakaIzdaje("ni_podprto")
        return Odlocitev("rar", izbrane, p.preslikava, z, p.bajtov + b2)
    }

    /**
     * NZB -> (Vir filma, bajti z omrezja za pripravo). Prvi zvezek odloci (T2), ostali zvezki se berejo najvec
     * [vzporedno] hkrati, zacetek se sprosti takoj po razclenitvi (T4; prej vsi prvi segmenti hkrati v RAM - na TV
     * OutOfMemoryError), skupni promet najvec [najvecBajtov] (PREVEC). Stisnjen ali sifriran RAR = [UsenetRar.NapakaRar]
     * PRED ponudbo. Nikoli na glavni niti.
     */
    fun pripravi(
        nzb: UsenetNzb.Nzb, telo: (String) -> ByteArray, vzporedno: Int = PRIPRAVA_HKRATI, najvecBajtov: Long = NAJVEC_PRIPRAVE_BAJTOV,
    ): Pair<Vir, Long> {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Usenet ne sme teci na glavni niti" }
        val o = prvi(nzb, telo)
        var bajtov = o.bajtov
        if (o.vrsta == "video") {
            return Vir(listOf(o.preslikava), listOf(KosVira(0, 0, o.preslikava.velikost)), nzb.datoteke[o.izbrane[0]].ime) to bajtov
        }
        val n = o.izbrane.size
        val preslikave = arrayOfNulls<UsenetTok.Preslikava>(n)
        val zvezki = arrayOfNulls<UsenetRar.Zvezek>(n)
        preslikave[0] = o.preslikava
        zvezki[0] = o.zvezek
        val hkrati = maxOf(1, vzporedno)
        val bazen = Executors.newFixedThreadPool(hkrati) { r -> Thread(r, "safeer-usenet-priprava").apply { isDaemon = true } }
        val koncani = java.util.concurrent.ExecutorCompletionService<Triple<Int, UsenetTok.Preslikava, Pair<UsenetRar.Zvezek, Long>>>(bazen)
        try {
            var naslednji = 1
            var tece = 0
            while (naslednji < n || tece > 0) {
                while (naslednji < n && tece < hkrati) {
                    val i = naslednji
                    koncani.submit {
                        val p = prviSegment(telo, nzb.datoteke[o.izbrane[i]])
                        val (z, b2) = glave(telo, p.preslikava, p.zacetek, false)
                        Triple(i, p.preslikava, z to p.bajtov + b2)  // zacetek gre stran tu
                    }
                    naslednji++
                    tece++
                }
                val izid = try {
                    koncani.take().get()
                } catch (e: ExecutionException) {
                    throw (e.cause as? IOException) ?: IOException("usenet priprava: " + (e.cause?.javaClass?.simpleName ?: "napaka"))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("usenet priprava: prekinjeno")
                }
                tece--
                preslikave[izid.first] = izid.second
                zvezki[izid.first] = izid.third.first
                bajtov += izid.third.second
                if (bajtov > najvecBajtov) throw UsenetTok.NapakaToka(UsenetTok.Izid.PREVEC)
            }
        } finally {
            bazen.shutdownNow()
        }
        val (ime, _, kosi) = UsenetRar.sestavi(zvezki.map { it!! })
        return Vir(preslikave.map { it!! }, kosi.map { KosVira(it.zvezek, it.odmik, it.dolzina) }, ime) to bajtov
    }

    /**
     * Lastnik 27. 10. 2026 (glasba): ena gola datoteka iz NZB (skladba albuma) kot [Vir] - prvi segment da preslikavo.
     * Album v RAR gre skozi [pripraviAlbum]. Nikoli na glavni niti.
     */
    fun pripraviDatoteko(d: UsenetNzb.Datoteka, telo: (String) -> ByteArray): Vir {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Usenet ne sme teci na glavni niti" }
        if (d.segmenti.isEmpty() || d.segmenti[0].stevilka != 1 || d.manjka > 0) throw UsenetTok.NapakaToka(UsenetTok.Izid.LUKNJA)
        val p = prviSegment(telo, d)
        if (p.preslikava.imaLuknje) throw UsenetTok.NapakaToka(UsenetTok.Izid.LUKNJA)
        return Vir(listOf(p.preslikava), listOf(KosVira(0, 0, p.preslikava.velikost)), d.ime)
    }

    /**
     * Lastnik in vodja 27. 10. 2026: album v RAR (store, en ali vec zvezkov) - na TV je glasba z Useneta prej vedno koncala
     * z »ni_skladb«. Zvezki po vrsti, iz vsakega CEL seznam glav; vsaka zvocna datoteka arhiva (po stevilki skladbe) svoj
     * [Vir] nad istimi zvezki. Prazno = arhiva ni ali v njem ni zvocnih datotek. Stisnjen ali sifriran arhiv =
     * [UsenetRar.NapakaRar] (sproti ga ne moremo brati). Promet najvec [najvecBajtov]. Nikoli na glavni niti.
     */
    fun pripraviAlbum(nzb: UsenetNzb.Nzb, telo: (String) -> ByteArray, najvecBajtov: Long = NAJVEC_PRIPRAVE_BAJTOV): List<Pair<String?, Vir>> {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Usenet ne sme teci na glavni niti" }
        val imena = nzb.datoteke.map { it.ime ?: "" }
        val kandidati = imena.indices.filter { jeZvezek(imena[it]) && !jeVzorec(imena[it]) }
        if (kandidati.isEmpty()) return emptyList()
        val red = UsenetRar.vrstniRedZvezkov(kandidati.map { imena[it] }) ?: throw NapakaIzdaje("ni_podprto")
        val preslikave = ArrayList<UsenetTok.Preslikava>()
        val zvezki = ArrayList<UsenetRar.Zvezek>()
        var bajtov = 0L
        for ((n, k) in red.withIndex()) {
            val d = nzb.datoteke[kandidati[k]]
            if (d.segmenti.isEmpty() || d.segmenti[0].stevilka != 1 || d.manjka > 0) throw UsenetTok.NapakaToka(UsenetTok.Izid.LUKNJA)
            val p = prviSegment(telo, d)
            if (n == 0 && !jeRar(p.zacetek)) throw UsenetRar.NapakaRar(UsenetRar.Izid.NI_RAR)
            val (z, b2) = glave(telo, p.preslikava, p.zacetek, true, NAJVEC_GLAV_SEGMENTOV_ALBUM, NAJVEC_GLAV_BAJTOV_ALBUM)
            bajtov += p.bajtov + b2
            if (bajtov > najvecBajtov) throw UsenetTok.NapakaToka(UsenetTok.Izid.PREVEC)
            preslikave += p.preslikava
            zvezki += z
        }
        val vnosi = zvezki.withIndex().flatMap { (n, z) -> UsenetRar.zacetniVnosi(z).map { n to it } }
        var prva: IOException? = null
        val izid = ArrayList<Pair<String?, Vir>>()
        for (k in UsenetGlasbaPravila.skladbe(vnosi.map { it.second.ime })) {
            val (n, v) = vnosi[k]
            try {
                val (_, kosi) = UsenetRar.sestaviVnos(zvezki, n, v)
                // Skladba z luknjo izpade, album gre naprej (kot pri golih datotekah).
                if (kosi.any { preslikave[it.zvezek].imaLuknje }) throw UsenetTok.NapakaToka(UsenetTok.Izid.LUKNJA)
                izid += v.ime to Vir(preslikave, kosi.map { KosVira(it.zvezek, it.odmik, it.dolzina) }, v.ime)
            } catch (e: UsenetRar.NapakaRar) {
                if (prva == null) prva = e
            } catch (e: UsenetTok.NapakaToka) {
                if (prva == null) prva = e
            }
        }
        if (izid.isEmpty()) prva?.let { throw it }
        return izid
    }

    fun beriVse(b: Bralnik, odmik: Long, n: Int): ByteArray {
        val izid = java.io.ByteArrayOutputStream(maxOf(0, minOf(n, 1 shl 20)))
        var o = odmik
        var ostane = n
        while (ostane > 0) {
            val kos = b.beri(o, ostane)
            if (kos.isEmpty()) break
            izid.write(kos)
            o += kos.size
            ostane -= kos.size
        }
        return izid.toByteArray()
    }

    /**
     * Pred ponudbo: ali izdajo lahko predvajamo. Prvi segment prve datoteke ali zvezka (pri RAR, ce glave segajo cez,
     * se nekaj segmentov z mejo - T2/T5). Izid v malih crkah kot v skupnih primerih ("ok", "stisnjen", "sifriran",
     * "ni_podprto", "luknja", "prevec" ...). Izdaja brez segmenta 1 ali z luknjami v NZB ni ponujena (W6).
     */
    fun preveriIzdajo(nzb: UsenetNzb.Nzb, telo: (String) -> ByteArray): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Usenet ne sme teci na glavni niti" }
        return try {
            val (_, izbrane) = izberiDatoteke(nzb)
            val d = nzb.datoteke[izbrane[0]]
            if (d.segmenti[0].stevilka != 1 || d.manjka > 0) return "luknja"
            val o = prvi(nzb, telo)
            if (o.preslikava.imaLuknje) "luknja" else "ok"
        } catch (e: NapakaIzdaje) {
            e.izid
        } catch (e: UsenetTok.NapakaToka) {
            e.izid.name.lowercase()
        } catch (e: UsenetRar.NapakaRar) {
            e.izid.name.lowercase()
        }
    }
}
