package si.safeer.tv.os

/**
 * Kdaj zacasen torrent (film iz kataloga, pomoc drugi napravi v Linku) izgine sam. Lastnik, 3. 10. 2026: "ce uporabnik
 * torrenta ne uporablja vec, ga naprava samodejno odstrani." Cista pravila brez Androida (tests/ZacasniPravilaTest.kt);
 * ista kot na racunalniku (core/link_datoteke.py: RABA_VELJA_S, RABA_V_TEKU_S).
 */
object ZacasniPravila {
    /**
     * Po toliko casu od zadnjega predvajanja torrent in njegove datoteke izginejo. Lastnik, 25. 10. 2026: kar se prek
     * torrenta prenese neposredno na napravo, gre 12 ur po zadnjem predvajanju (prej 48 ur); izjema je, kar uporabnik
     * izrecno shrani (»Obdrži« ali Prenesi - ta ni zacasen). Ob zagonu se pospravi vse, kar je starejse od 12 ur.
     */
    const val VELJA_MS = 12L * 3_600_000
    /** Ob pomanjkanju prostora gredo tudi mlajsi, a ne tisti, ki jih je kdo predvajal v zadnjih urah (film se tece). */
    const val V_TEKU_MS = 6L * 3_600_000

    /** [cas] = zadnja raba; [primanjkuje] = za nov film ni dovolj prostora. */
    fun odstrani(cas: Long, zdaj: Long, primanjkuje: Boolean, velja: Long = VELJA_MS, vTeku: Long = V_TEKU_MS,
                 /** »Obdrži«: uporabnik hoce, da film ostane - ne potece in ne gre niti ob pomanjkanju prostora. */
                 obdrzan: Boolean = false): Boolean =
        !obdrzan && (zdaj - cas > velja || (primanjkuje && zdaj - cas > vTeku))

    /**
     * Grok 9, Z3 (lastnik 25. 10. 2026 dovolil zivi preizkus P2P z odprtimi filmi CC): zacasen torrent je po ogledu prenasal
     * naprej (IP v roju, dokler ni bil cel) in ob vsakem zagonu aplikacije spet sel v roj. Zdaj tece samo, dokler je odprta
     * kaka povezava toka (predvajalnik te naprave ali naprava v Linku); [PO_OGLEDU_MS] po zaprtju zadnje se ustavi. Nadzor
     * ([MagnetMotor]) ustavi tudi torrent, ki ga po [ZACETEK_MS] od zadnjega branja ni odprl nihce (predvajalnik se ni povezal,
     * obnova ob zagonu). Datoteke ostanejo do [VELJA_MS] (Nadaljuj), nova povezava toka ga spet zazene.
     */
    const val PO_OGLEDU_MS = 5_000L
    const val ZACETEK_MS = 30_000L
    /**
     * Grok 27, Q1: odprta povezava toka, po kateri toliko casa ni sel noben bajt (premor predvajanja s polnim medpomnilnikom,
     * odjemalec ne bere, naprava v Linku brez povezave), ne drzi torrenta v roju. Vec kot najdaljse cakanje na kos
     * (MagnetMotor.CAKAJ_KOS_MS = 90 s), med katerim se branje steje. Ko odjemalec spet bere, ga povezava zazene.
     */
    const val ZASTOJ_MS = 120_000L

    /**
     * Ali naj se zacasen torrent ustavi: [obdrzan] (»Obdrži« - uporabnik hoce film, kot pri Prenesi) in [deliNaprej] tečeta
     * naprej; sicer ustavi, ko od zadnjega branja mine [prag] ms ([msOdBranja]) in ni odprte povezave toka ([odprtihTokov])
     * ali pa odprte povezave ze [ZASTOJ_MS] nihce ne bere (Grok 27, Q1).
     */
    fun ustaviPoOgledu(obdrzan: Boolean, deliNaprej: Boolean, odprtihTokov: Int, msOdBranja: Long, prag: Long = PO_OGLEDU_MS): Boolean =
        !obdrzan && !deliNaprej && msOdBranja >= prag && (odprtihTokov <= 0 || msOdBranja >= ZASTOJ_MS)

    /** Grok 27, Q5: en zapis hasha za vse mape in mnozice toka (male crke, brez presledkov). */
    fun kanon(hash: String): String = hash.trim().lowercase()

    /**
     * Grok 27, Q2: stevci odprtih povezav toka po torrentu. Povezava zmanjsa PRAV stevec, ki ga je povecala (ne tistega, ki je
     * pod hashem zdaj): po odstranitvi in ponovnem dodajanju med odprtim tokom stara povezava ne zmanjsa novega stevca
     * (ustavitev med ogledom) in stevec ne pade pod nic.
     */
    class StevciTokov {
        private val stevci = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

        /** Nova povezava toka; vrnjeni stevec preda [zapri]. */
        fun odpri(hash: String): java.util.concurrent.atomic.AtomicInteger =
            stevci.compute(kanon(hash)) { _, s -> (s ?: java.util.concurrent.atomic.AtomicInteger()).also { it.incrementAndGet() } }!!

        /** Povezava je zaprta; vrne, koliko povezav istega stevca je se odprtih (nikoli pod nic). */
        fun zapri(stevec: java.util.concurrent.atomic.AtomicInteger): Int =
            stevec.updateAndGet { if (it > 0) it - 1 else 0 }

        fun odprtih(hash: String): Int = stevci[kanon(hash)]?.get() ?: 0

        /** Torrent je odstranjen: odprte povezave starega stevca ne stejejo vec za nov torrent z istim hashem. */
        fun pozabi(hash: String) { stevci.remove(kanon(hash)) }
    }

    /**
     * Grok 27, Q1: ali je odjemalec lokalnega toka se povezan, ne da bi mu kaj pisali (med cakanjem na kos). Odjemalec po
     * zahtevi ne posilja nic vec: branje z rokom 1 ms vrne -1 (zaprl) ali poteče (se tam). Prejeti bajt (nepricakovan) se
     * zavrze, povezava velja za zivo.
     */
    fun odjemalecZiv(s: java.net.Socket): Boolean {
        if (s.isClosed || s.isInputShutdown) return false
        val rok = try { s.soTimeout } catch (_: Exception) { return false }
        return try {
            s.soTimeout = 1
            s.getInputStream().read() >= 0
        } catch (_: java.net.SocketTimeoutException) {
            true
        } catch (_: Exception) {
            false
        } finally {
            try { s.soTimeout = rok } catch (_: Exception) { }
        }
    }

    /** Grok 9, Z12: cas zadnje rabe v prihodnosti (napacna ura ob zagonu) se steje od zdaj, da zapis vseeno potece. */
    fun popraviCas(cas: Long, zdaj: Long): Long = if (cas > zdaj) zdaj else cas

    /**
     * Grok 9, Z9: izbrise datoteko ali mapo zacasnega torrenta BREZ sledenja simbolnim povezavam. File.deleteRecursively jim
     * sledi: povezava v torrentu na drugo mapo bi izbrisala tudi njeno vsebino. Povezava se izbrise kot povezava.
     */
    fun izbrisiBrezPovezav(f: java.io.File): Boolean {
        val p = f.toPath()
        val brez = java.nio.file.LinkOption.NOFOLLOW_LINKS
        if (!java.nio.file.Files.exists(p, brez)) return true
        var ok = true
        if (java.nio.file.Files.isDirectory(p, brez)) {
            try {
                java.nio.file.Files.newDirectoryStream(p).use { s -> for (o in s) ok = izbrisiBrezPovezav(o.toFile()) && ok }
            } catch (_: Exception) { ok = false }
        }
        return try { java.nio.file.Files.deleteIfExists(p); ok } catch (_: Exception) { false }
    }

    /** Vrstni red odstranjevanja: najdlje neuporabljeni prvi. Vnos = (cas zadnje rabe, kljuc). */
    fun <T> poVrsti(vnosi: List<Pair<Long, T>>): List<Pair<Long, T>> = vnosi.sortedBy { it.first }

    /** Kaj od zacasnih torrentov bi ob pomanjkanju prostora smeli sprostiti (za izracun prostora, ki je napravi na voljo). */
    fun sprostljiv(cas: Long, zdaj: Long, vTeku: Long = V_TEKU_MS, obdrzan: Boolean = false): Boolean = !obdrzan && zdaj - cas > vTeku
}
