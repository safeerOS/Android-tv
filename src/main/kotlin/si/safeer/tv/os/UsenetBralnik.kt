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
        fun miruje(): Boolean = zaklep.withLock { vTeku.isEmpty() }

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
                    zaklep.withLock { vTeku.remove(k) }
                    ff.completeExceptionally(e)
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

        /** Pod zaklepom: future segmenta, null = ze v LRU. */
        private fun zahtevaj(k: Kljuc, prednost: Int): CompletableFuture<ByteArray>? {
            if (lru.containsKey(k)) return null
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
            while (true) {
                val f = zaklep.withLock {
                    if (zaprt) throw UsenetTok.NapakaToka(UsenetTok.Izid.ZAPRTO)
                    lru[k]?.let { return it }
                    zahtevaj(k, 0)
                } ?: continue
                try {
                    return f.get()
                } catch (_: CancellationException) {
                    // branje vnaprej, na katero smo cakali, je skok iz druge povezave zavrgel: znova zahtevano
                } catch (e: ExecutionException) {
                    val v = e.cause
                    if (v is IOException) throw v
                    throw IOException("usenet bralnik: " + (v?.javaClass?.simpleName ?: "napaka"))
                }
            }
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
                val odvec = cakajo.keys.filter { it !in zelja }
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

        /** Konec predvajanja: vrsta proc, LRU sproscen, niti koncajo; zahteve v teku se ne caka. */
        override fun close() {
            zaklep.withLock {
                zaprt = true
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

    /** 7z, zip, vec arhivov ... (deobfuskacija je F2) in izdaja brez filma. */
    class NapakaIzdaje(val izid: String) : IOException("usenet izdaja: $izid")

    private fun jeVzorec(ime: String) = ime.lowercase().let { "sample" in it || "vzorec" in it }

    private fun jeZvezek(ime: String): Boolean {
        val m = ime.lowercase()
        val k = m.substringAfterLast('.', "")
        return UsenetRar.vrstniRedZvezkov(listOf(ime)) != null || m.endsWith(".rar") ||
            (k.length == 3 && k[0] == 'r' && k.substring(1).all { it.isDigit() })
    }

    /** ("rar" ali "video", indeksi datotek v vrstnem redu). Napaka: [NapakaIzdaje] ni_podprto ali ni_filma. */
    fun izberiDatoteke(nzb: UsenetNzb.Nzb): Pair<String, List<Int>> {
        val imena = nzb.datoteke.map { it.ime ?: "" }
        val rar = imena.indices.filter { jeZvezek(imena[it]) && !jeVzorec(imena[it]) }
        if (rar.isNotEmpty()) {
            val red = UsenetRar.vrstniRedZvezkov(rar.map { imena[it] }) ?: throw NapakaIzdaje("ni_podprto")
            return "rar" to red.map { rar[it] }
        }
        val video = imena.indices.filter { i -> VIDEO.any { imena[i].lowercase().endsWith(it) } && !jeVzorec(imena[i]) }
        if (video.isNotEmpty()) return "video" to listOf(video.maxByOrNull { nzb.datoteke[it].bajtov }!!)
        if (imena.any { i -> listOf(".7z", ".zip", ".001").any { i.lowercase().endsWith(it) } }) throw NapakaIzdaje("ni_podprto")
        val kandidati = imena.indices.filter { i -> NI_VSEBINA.none { imena[i].lowercase().endsWith(it) } }
        if (kandidati.isEmpty()) throw NapakaIzdaje("ni_filma")
        return "video" to listOf(kandidati.maxByOrNull { nzb.datoteke[it].bajtov }!!)
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

    /**
     * NZB -> (Vir filma, bajti z omrezja za pripravo). Prvi segment vsake izbrane datoteke (vzporedno) da preslikavo in
     * glave RAR; stisnjen ali sifriran RAR = [UsenetRar.NapakaRar] PRED ponudbo. Nikoli na glavni niti.
     */
    fun pripravi(nzb: UsenetNzb.Nzb, telo: (String) -> ByteArray, vzporedno: Int = 8): Pair<Vir, Long> {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Usenet ne sme teci na glavni niti" }
        val (vrstaIzdaje, izbrane) = izberiDatoteke(nzb)
        val bazen = Executors.newFixedThreadPool(maxOf(1, minOf(vzporedno, izbrane.size))) { r ->
            Thread(r, "safeer-usenet-priprava").apply { isDaemon = true }
        }
        val prvi = try {
            izbrane.map { i -> bazen.submit<Prvi> { prviSegment(telo, nzb.datoteke[i]) } }.map {
                try { it.get() } catch (e: ExecutionException) { throw (e.cause as? IOException) ?: IOException("usenet priprava") }
            }
        } finally {
            bazen.shutdownNow()
        }
        var bajtov = prvi.sumOf { it.bajtov.toLong() }
        val preslikave = prvi.map { it.preslikava }
        if (vrstaIzdaje == "video") {
            return Vir(preslikave, listOf(KosVira(0, 0, preslikave[0].velikost)), nzb.datoteke[izbrane[0]].ime) to bajtov
        }
        val zvezki = ArrayList<UsenetRar.Zvezek>()
        for ((n, p) in prvi.withIndex()) {
            if (n == 0) {
                val izid = UsenetRar.preveri(p.zacetek, p.preslikava.velikost)
                if (izid != UsenetRar.Izid.OK && izid != UsenetRar.Izid.NEPOPOLNO) throw UsenetRar.NapakaRar(izid)
            }
            var z = if (p.zacetek.isNotEmpty()) UsenetRar.razcleniZvezek(p.zacetek, p.preslikava.velikost) else null
            if (z == null || (z.nepopolno && z.vnosi.isEmpty())) {
                // glave cez prvi segment: beri zvezek prek bralnika z majhnim oknom
                val b = Bralnik(telo, Vir(listOf(p.preslikava), listOf(KosVira(0, 0, p.preslikava.velikost))), Profil(MIB, MIB, 8 * MIB, 2))
                try {
                    z = UsenetRar.razcleniZvezek({ o, k -> beriVse(b, o, k) }, p.preslikava.velikost)
                } finally {
                    bajtov += b.zOmrezja.get()
                    b.close()
                }
            }
            zvezki += z
        }
        val (ime, _, kosi) = UsenetRar.sestavi(zvezki)
        return Vir(preslikave, kosi.map { KosVira(it.zvezek, it.odmik, it.dolzina) }, ime) to bajtov
    }

    fun beriVse(b: Bralnik, odmik: Long, n: Int): ByteArray {
        val izid = java.io.ByteArrayOutputStream(maxOf(0, n))
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
     * Pred ponudbo: ali izdajo lahko predvajamo - EN clanek (prvi segment prvega zvezka ali datoteke). Izid v malih
     * crkah kot v skupnih primerih ("ok", "stisnjen", "sifriran", "sifrirane_glave", "ni_podprto", "luknja" ...).
     */
    fun preveriIzdajo(nzb: UsenetNzb.Nzb, telo: (String) -> ByteArray): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Usenet ne sme teci na glavni niti" }
        val (vrstaIzdaje, izbrane) = try { izberiDatoteke(nzb) } catch (e: NapakaIzdaje) { return e.izid }
        val p = try { prviSegment(telo, nzb.datoteke[izbrane[0]]) } catch (e: UsenetTok.NapakaToka) { return e.izid.name.lowercase() }
        val z = p.zacetek
        if (vrstaIzdaje == "video") {
            val rar = z.size >= 7 && (0 until 6).all { z[it] == UsenetRar.PODPIS4[it] } && (z[6].toInt() == 0 || z[6].toInt() == 1)
            return if (rar) UsenetRar.preveri(z, p.preslikava.velikost).name.lowercase() else "ok"
        }
        val izid = UsenetRar.preveri(z, p.preslikava.velikost)
        return if (izid == UsenetRar.Izid.NEPOPOLNO) "ok" else izid.name.lowercase()
    }
}
