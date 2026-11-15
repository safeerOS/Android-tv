package si.safeer.tv.os

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ciste odlocitve za predpomnilnik brskalnika na USB disku ([si.safeer.tv.BrskalnikPredpomnilnik]), brez Androida, da jih
 * preizkus na JVM res izvede (tests/UsbPopravkiTest.kt). Popravki XX3-XX8 (15. 11. 2026).
 */
object BrskalnikDiskPravila {
    /** XX5: neuspel poskus velja 24 h, nato disk poskusimo znova (prej za vedno, do brisanja podatkov). */
    const val NEUSPEH_VELJA_MS = 24L * 3_600_000

    /** XX4: najdlje toliko glavna nit caka na disk; potem WebView zacne z notranjim predpomnilnikom. */
    const val ROK_PRIKLOPA_MS = 300L

    /** Ime mape na disku (komentar BrskalnikPredpomnilnik: <USB>/Android/data/<paket>/cache/brskalnik-http). */
    const val MAPA_NA_DISKU = "brskalnik-http"

    /**
     * XX3: na disk gre samo mapa HTTP predpomnilnika WebViewa, ne cel cacheDir/WebView. Pot je Chromiumova in se lahko razlikuje
     * po verziji WebView (NEPREVERJENO na napravi).
     */
    const val HTTP_PODMAPA = "WebView/Default/HTTP Cache"

    /**
     * XX3: imeni, ki ju na disku zasedemo s prazno DATOTEKO: ce Chromium Code Cache ali GPUCache ustvari v mapi HTTP
     * predpomnilnika, mape tam ne more ustvariti in ta del ne gre na disk (vedenje Chromiuma NEPREVERJENO na napravi).
     */
    val ZAPORNICE = listOf("Code Cache", "GPUCache")

    /** XX8: meritev nakljucnega dostopa velja 7 dni (kot zaporedna meritev v DiskPravila). */
    const val NAKLJUCNO_VELJA_MS = 7L * 24 * 3_600_000

    /** XX8: disk za brskalnik samo, ce mediana malega zapisa+branja ni vec kot 2x pocasnejsa od notranjega pomnilnika. */
    const val NAKLJUCNO_FAKTOR = 2L

    // ------------------------------------------------------------------ XX5: stanje poskusa

    enum class Izid { POSKUS, USPEL, NEUSPEL }

    data class Zapis(val pot: String, val izid: Izid, val cas: Long)

    /** "<pot>|<izid>|<ms>"; stari zapis "<pot>|<izid>" (brez casa) velja kot cas 0 (neuspel je torej potekel). */
    fun preberi(s: String?): Zapis? {
        val n = s ?: return null
        val deli = n.split('|')
        if (deli.size < 2) return null
        val imaCas = deli.size >= 3 && deli.last().isNotEmpty() && deli.last().all { it in '0'..'9' } && deli.last().length <= 18
        val izidNiz = if (imaCas) deli[deli.size - 2] else deli.last()
        val pot = deli.subList(0, deli.size - (if (imaCas) 2 else 1)).joinToString("|")
        val izid = when (izidNiz) { "poskus" -> Izid.POSKUS; "uspel" -> Izid.USPEL; "neuspel" -> Izid.NEUSPEL; else -> return null }
        return Zapis(pot, izid, if (imaCas) deli.last().toLong() else 0L)
    }

    fun zapisi(z: Zapis): String = z.pot + "|" + z.izid.name.lowercase() + "|" + z.cas

    /**
     * Prejsnji izid za ta disk (za PredpomnilnikPravila.brskalnikCilj): true uspel, false neuspel v zadnjih 24 h, null = poskusi.
     * Poskus brez dokaza o sesutju ni neuspeh (XX5: prej je bil neuspeh ze zagon brez nalozene strani).
     */
    fun prejsnji(z: Zapis?, usbPot: String?, zdaj: Long): Boolean? {
        if (z == null || usbPot == null || z.pot != usbPot) return null
        return when (z.izid) {
            Izid.USPEL -> true
            Izid.POSKUS -> null
            Izid.NEUSPEL -> if (zdaj - z.cas in -86_400_000L until NEUSPEH_VELJA_MS) false else null
        }
    }

    /** Ob zagonu: poskus postane neuspel samo, ce se je proces po zacetku poskusa res sesul ([sesutjeOb] = cas sesutja ali null). */
    fun obZagonu(z: Zapis?, usbPot: String?, sesutjeOb: Long?, zdaj: Long): Zapis? {
        if (z == null || z.izid != Izid.POSKUS || usbPot == null || z.pot != usbPot) return z
        return if (sesutjeOb != null && sesutjeOb >= z.cas) Zapis(z.pot, Izid.NEUSPEL, zdaj) else z
    }

    /** Uspeh samo, ce je poskus v teku IN je predpomnilnik na disku res kaj zapisal (XX5: prej ze vsaka nalozena stran). */
    fun smemPotrditi(z: Zapis?, ciljSeje: String?, imaVsebino: Boolean): Boolean =
        z != null && z.izid == Izid.POSKUS && ciljSeje != null && imaVsebino

    // ------------------------------------------------------------------ XX3: kaj gre na disk

    /** Ali sme ta del cacheDir (relativna pot) na disk: samo mapa HTTP predpomnilnika, nikoli cel WebView, Code Cache ali GPUCache. */
    fun smeNaDisk(rel: String): Boolean {
        val r = rel.trim('/').replace('\\', '/')
        if (r != HTTP_PODMAPA) return false
        return ZAPORNICE.none { r.split('/').contains(it) }
    }

    /** Na disku namesto Code Cache in GPUCache prazni datoteki (glej [ZAPORNICE]). Ne sledi povezavam. */
    fun nastaviZapornice(mapa: Path): Boolean = try {
        for (ime in ZAPORNICE) {
            val p = mapa.resolve(ime)
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) Files.createFile(p)
        }
        true
    } catch (_: Exception) {
        false
    }

    /** Vsebina, ki dokazuje, da HTTP predpomnilnik pise na disk (zapornice ne stejejo). */
    fun imaVsebino(mapa: Path): Boolean = try {
        if (Files.isSymbolicLink(mapa) || !Files.isDirectory(mapa, LinkOption.NOFOLLOW_LINKS)) false
        else Files.newDirectoryStream(mapa).use { s -> s.any { it.fileName.toString() !in ZAPORNICE } }
    } catch (_: Exception) {
        false
    }

    /**
     * XX3: nobena sestavina poti od [koren] (vkljucno) do [cilj] ni simbolna povezava. Na ext4 disku bi povezava preusmerila
     * pisanje predpomnilnika (ali brisanje) drugam; mkdirs/isDirectory ji sledita.
     */
    fun brezPovezav(koren: Path, cilj: Path): Boolean {
        val k = koren.toAbsolutePath().normalize()
        val c = cilj.toAbsolutePath().normalize()
        if (!c.startsWith(k)) return false
        var p: Path? = c
        while (p != null) {
            if (Files.isSymbolicLink(p)) return false
            if (p == k) return true
            p = p.parent
        }
        return false
    }

    // ------------------------------------------------------------------ XX6/XX7: brisanje brez sledenja povezavam

    /**
     * Izbrise VSEBINO [mapa], ne mape. Povezava se izbrise kot povezava (nikoli njen cilj); ce je [mapa] sama povezava, nic.
     * Prej File.deleteRecursively, ki sledi povezavi na mapo in brise njeno vsebino.
     */
    fun pocistiVsebino(mapa: Path): Boolean {
        if (Files.isSymbolicLink(mapa) || !Files.isDirectory(mapa, LinkOption.NOFOLLOW_LINKS)) return false
        var ok = true
        try {
            Files.newDirectoryStream(mapa).use { s -> for (e in s.toList()) ok = izbrisiDrevo(e, 0) && ok }
        } catch (_: Exception) {
            return false
        }
        return ok
    }

    private fun izbrisiDrevo(p: Path, globina: Int): Boolean {
        if (globina > 64) return false
        return try {
            if (!Files.isSymbolicLink(p) && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                Files.newDirectoryStream(p).use { s -> for (e in s.toList()) izbrisiDrevo(e, globina + 1) }
            }
            Files.deleteIfExists(p)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Odstrani [p], ce je povezava (samo povezavo); prava mapa ostane. true = na poti ni vec povezave. */
    fun odstraniPovezavo(p: Path): Boolean = try {
        if (Files.isSymbolicLink(p)) Files.delete(p)
        !Files.isSymbolicLink(p)
    } catch (_: Exception) {
        false
    }

    /** Brisemo samo mapo predpomnilnika brskalnika v mapi aplikacije na disku (zascita pred pokvarjenim zapisom v prefs). */
    fun smemBrisati(pot: String?): Boolean {
        val n = (pot ?: "").replace('\\', '/')
        if (n.isEmpty() || !n.startsWith("/") || n.split('/').any { it == ".." || it == "." }) return false
        return n.trimEnd('/').substringAfterLast('/') == MAPA_NA_DISKU && "/Android/data/" in n
    }

    /**
     * XX7/XX3: mape na disku, ki jih ta zagon izpraznimo PRED uporabo: mapa prejsnje povezave, zapomnjena mapa (disk je bil
     * ob prehodu na "Ta TV" izkljucen) in nov cilj (predpomnilnik velja samo za sejo - zastrupitev z drugega racunalnika ne ostane).
     */
    fun zaPraznjenje(prejsnjaTarca: String?, zapomnjene: List<String>, cilj: String?): List<String> =
        (listOfNotNull(prejsnjaTarca, cilj) + zapomnjene).map { it.trimEnd('/') }.distinct().filter { smemBrisati(it) }

    // ------------------------------------------------------------------ XX6: en priklop na proces

    class Enkrat {
        private val bilo = AtomicBoolean(false)
        /** true samo ob prvem klicu v procesu (tudi ob hkratnih klicih iz vec niti). */
        fun prvic(): Boolean = bilo.compareAndSet(false, true)
    }

    // ------------------------------------------------------------------ XX4: rok 300 ms, delo v ozadju

    /**
     * Usklajevanje glavne niti in niti v ozadju. Ozadje opravi vse delo z diskom; povezavo namesti samo, ce glavna nit se caka
     * ([namesti]). Glavna nit caka najvec [rokMs]; ko odneha, povezave ni (notranji predpomnilnik) in je ozadje ne sme vec
     * namestiti, ker WebView ze lahko nastaja. [pocistiPovezavo] dela samo na notranjem pomnilniku (lstat/unlink, ne disk).
     */
    class Priprava(private val pocistiPovezavo: () -> Unit) {
        private val zaklep = Any()
        private val konec = CountDownLatch(1)
        @Volatile private var faza = TECE
        private var pocisceno = false

        /** Ozadje, prvi korak: stara povezava proc (notranji pomnilnik). */
        fun pocisti() = synchronized(zaklep) { pocistiNotranje() }

        private fun pocistiNotranje() {
            if (!pocisceno) {
                try { pocistiPovezavo() } catch (_: Throwable) { }
                pocisceno = true
            }
        }

        /** Ozadje: namesti povezavo ([delo]), ce glavna nit se caka; sicer nic. */
        fun namesti(delo: () -> Unit): Boolean {
            try {
                synchronized(zaklep) {
                    if (faza != TECE) return false
                    pocistiNotranje()
                    delo()
                    faza = KONCANO
                    return true
                }
            } finally {
                konec.countDown()
            }
        }

        /** Ozadje: odlocitev je notranji predpomnilnik (ali napaka). */
        fun koncajBrez() {
            synchronized(zaklep) { if (faza == TECE) faza = KONCANO }
            konec.countDown()
        }

        /** Glavna nit: true = ozadje je odlocilo pravocasno; false = rok je potekel, velja notranji predpomnilnik. */
        fun pocakaj(rokMs: Long = ROK_PRIKLOPA_MS): Boolean {
            if (konec.await(rokMs, TimeUnit.MILLISECONDS)) return faza == KONCANO
            synchronized(zaklep) {
                if (faza == TECE) {
                    faza = ODPOVEDANO
                    pocistiNotranje()
                    return false
                }
                return faza == KONCANO
            }
        }

        val odpovedano: Boolean get() = faza == ODPOVEDANO

        private companion object {
            const val TECE = 0
            const val KONCANO = 1
            const val ODPOVEDANO = 2
        }
    }

    // ------------------------------------------------------------------ XX8: nakljucni dostop

    /** Meritev [izmerjenoOb] velja (0 = nikoli; ura iz prihodnosti ne). */
    fun nakljucnoVelja(izmerjenoOb: Long, zdaj: Long): Boolean =
        izmerjenoOb > 0L && zdaj - izmerjenoOb in -86_400_000L..NAKLJUCNO_VELJA_MS

    /** Disk za brskalnik samo z veljavno meritvijo obeh in ce ni vec kot [NAKLJUCNO_FAKTOR]-krat pocasnejsi od notranjega. */
    fun nakljucnoDovolj(usbNs: Long?, notranjiNs: Long?): Boolean =
        usbNs != null && notranjiNs != null && usbNs > 0 && notranjiNs > 0 && usbNs <= notranjiNs * NAKLJUCNO_FAKTOR

    /**
     * Mediana (ns) malega nakljucnega dostopa: [n] datotek po 4 KiB zapisemo s fsync in preberemo v premesanem vrstnem redu.
     * Predpomnilnik brskalnika so tisoci malih datotek, zaporedna meritev 32 MiB tega ne pove (XX8). null = rok ali napaka.
     */
    fun meriNakljucno(mapa: File, n: Int = 32, rokNs: Long = 3_000_000_000L, ura: () -> Long = System::nanoTime): Long? {
        val m = File(mapa, ".safeer-nakljucno")
        val zac = ura()
        try {
            // Tuja povezava na tem imenu bi preusmerila pisanje in brisanje.
            if (Files.isSymbolicLink(m.toPath()) || !(m.isDirectory || m.mkdirs())) return null
            val blok = ByteArray(4096) { (it * 31).toByte() }
            val casi = LongArray(n)
            val datoteke = (0 until n).map { File(m, "b$it") }
            for (i in 0 until n) {
                if (ura() - zac > rokNs) return null
                val t = ura()
                // CodeRabbit (CWE-61): obstojeci vnos (tudi povezava) najprej odstranimo kot vnos, nato CREATE_NEW ne sledi povezavam.
                Files.deleteIfExists(datoteke[i].toPath())
                java.nio.channels.FileChannel.open(
                    datoteke[i].toPath(),
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE
                ).use { it.write(java.nio.ByteBuffer.wrap(blok)); it.force(true) }
                casi[i] = ura() - t
            }
            for (i in (0 until n).shuffled(java.util.Random(n.toLong()))) {
                if (ura() - zac > rokNs) return null
                val t = ura()
                RandomAccessFile(datoteke[i], "r").use { it.readFully(ByteArray(4096)) }
                casi[i] += ura() - t
            }
            return casi.sorted()[n / 2]
        } catch (_: Exception) {
            return null
        } finally {
            try { if (pocistiVsebino(m.toPath())) Files.deleteIfExists(m.toPath()) } catch (_: Exception) { }
        }
    }
}
