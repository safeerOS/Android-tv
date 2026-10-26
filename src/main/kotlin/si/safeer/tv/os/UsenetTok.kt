package si.safeer.tv.os

/**
 * Preslikava odmikov za lasten pogon Usenet, korak 2 (lastnik 26. 10. 2026) - enako kot core/usenet_tok.py v Safeer-linux.
 *
 * Datoteka iz NZB je zaporedje segmentov (clankov yEnc z =ypart begin/end); NZB pove samo velikost kodiranih clankov.
 * Prvi segment da velikost datoteke in dela. Kodirniki delijo na enake dele (zadnji krajsi): odmik o lezi v segmentu
 * o / del + 1 - to je UGIB; vsak prebran segment postane sidro ([Preslikava.vnesi]), ugib med sidri pa enakomerna
 * razdelitev vrzeli, zato se tudi dve zdruzeni objavi najdeta v nekaj branjih. Segment, ki ga v NZB ni, je luknja.
 *
 * Pravila so skupna: tests/skupni-primeri/usenet-pogon.json v5 (tok_odmik, tok_segmenti). Brez omrezja; message-id ni
 * v toString ali izjemi.
 */
object UsenetTok {
    /** Imena so ista kot v skupnih primerih (male crke). */
    enum class Izid { IZVEN, LUKNJA, NESKLADNO, POKVARJEN, ZAPRTO }

    /** Napaka toka; sporocilo nima message-id ne imena datoteke. */
    class NapakaToka(val izid: Izid) : java.io.IOException("usenet tok: " + izid.name.lowercase())

    /** Najvec branj, s katerimi bralnik isce segment za odmik. */
    const val NAJVEC_ISKANJ = 24

    /** Segment (od 1), odmik v njem, [natancno] = razpon segmenta je znan. */
    data class Ugib(val stevilka: Int, val odmik: Long, val natancno: Boolean)

    /** Znani razponi: stevilka -> (odmik, dolzina). */
    private fun preveriZnane(velikost: Long, celota: Int, znani: Map<Int, Pair<Long, Long>>): List<Pair<Int, Pair<Long, Long>>> {
        val vrsta = znani.entries.sortedBy { it.key }.map { it.key to it.value }
        var prejSt = 0
        var prejKonec = 0L
        for ((st, r) in vrsta) {
            val (z, d) = r
            if (st < 1 || st > celota || d < 1 || z < 0 || z + d > velikost) throw NapakaToka(Izid.NESKLADNO)
            if (z - prejKonec < (st - prejSt - 1).toLong() || (st == prejSt + 1 && z != prejKonec)) throw NapakaToka(Izid.NESKLADNO)
            prejSt = st
            prejKonec = z + d
        }
        if (velikost - prejKonec < (celota - prejSt).toLong() || (prejSt == celota && prejKonec != velikost)) {
            throw NapakaToka(Izid.NESKLADNO)
        }
        return vrsta
    }

    private fun enakomerno(lst: Int, lkon: Long, dst: Int, dzac: Long, del: Long?, celota: Int): Boolean {
        if (del == null || del <= 0 || lkon != lst * del) return false
        if (dst == celota + 1) return (celota - 1) * del < dzac && dzac <= celota * del
        return dzac == (dst - 1) * del
    }

    /** Segment za [odmik] (skupni primeri tok_odmik). Napaka: [NapakaToka] IZVEN ali NESKLADNO. */
    fun poisci(velikost: Long, celota: Int, delBajtov: Long?, znani: Map<Int, Pair<Long, Long>>, odmik: Long): Ugib {
        if (odmik < 0 || odmik >= velikost) throw NapakaToka(Izid.IZVEN)
        if (celota < 1 || velikost < celota) throw NapakaToka(Izid.NESKLADNO)
        val vrsta = preveriZnane(velikost, celota, znani)
        for ((st, r) in vrsta) if (r.first <= odmik && odmik < r.first + r.second) return Ugib(st, odmik - r.first, true)
        var lst = 0
        var lkon = 0L
        var dst = celota + 1
        var dzac = velikost
        for ((st, r) in vrsta) {
            if (r.first + r.second <= odmik) { lst = st; lkon = r.first + r.second } else if (r.first > odmik) { dst = st; dzac = r.first; break }
        }
        if (enakomerno(lst, lkon, dst, dzac, delBajtov, celota)) {
            val st = (odmik / delBajtov!!).toInt() + 1
            return Ugib(st, odmik - (st - 1) * delBajtov, false)
        }
        val na = (dzac - lkon).toDouble() / (dst - lst - 1)
        val st = (lst + 1 + Math.floor((odmik - lkon) / na).toInt()).coerceIn(lst + 1, dst - 1)
        return Ugib(st, odmik - (lkon + ((st - lst - 1) * na).toLong()), false)
    }

    /** (odmik, dolzina, natancno) segmenta - znan ali ocenjen enako kot v [poisci]. */
    fun razpon(velikost: Long, celota: Int, delBajtov: Long?, znani: Map<Int, Pair<Long, Long>>, stevilka: Int): Triple<Long, Long, Boolean> {
        if (stevilka < 1 || stevilka > celota) throw NapakaToka(Izid.IZVEN)
        val vrsta = preveriZnane(velikost, celota, znani)
        znani[stevilka]?.let { return Triple(it.first, it.second, true) }
        var lst = 0
        var lkon = 0L
        var dst = celota + 1
        var dzac = velikost
        for ((st, r) in vrsta) {
            if (st < stevilka) { lst = st; lkon = r.first + r.second } else { dst = st; dzac = r.first; break }
        }
        if (enakomerno(lst, lkon, dst, dzac, delBajtov, celota)) {
            val z = (stevilka - 1) * delBajtov!!
            return Triple(z, minOf(delBajtov, dzac - z), false)
        }
        val na = (dzac - lkon).toDouble() / (dst - lst - 1)
        val z = lkon + ((stevilka - lst - 1) * na).toLong()
        val k = if (stevilka + 1 < dst) lkon + ((stevilka - lst) * na).toLong() else dzac
        return Triple(z, maxOf(1L, k - z), false)
    }

    /** Segmenti, ki pokrivajo [odmik, odmik + dolzina) (skupni primeri tok_segmenti). */
    fun segmentiOkna(velikost: Long, celota: Int, delBajtov: Long?, znani: Map<Int, Pair<Long, Long>>, odmik: Long, dolzina: Long): List<Int> {
        if (dolzina <= 0 || odmik < 0 || odmik >= velikost) return emptyList()
        val konec = minOf(velikost, odmik + dolzina)
        var st = poisci(velikost, celota, delBajtov, znani, odmik).stevilka
        val izid = ArrayList<Int>()
        while (st <= celota) {
            if (razpon(velikost, celota, delBajtov, znani, st).first >= konec) break
            izid += st
            st++
        }
        return izid
    }

    /** Odmiki ene datoteke iz NZB. Varna za vec niti (bralnik vnasa segmente vzporedno). */
    class Preslikava(datoteka: UsenetNzb.Datoteka, prvi: UsenetYenc.Del, stevilkaPrvega: Int = datoteka.segmenti[0].stevilka) {
        private val segmenti = datoteka.segmenti.associateBy { it.stevilka }
        val ime: String? = datoteka.ime
        val velikost: Long = prvi.velikostDatoteke
        val celota: Int
        val manjkajo: List<Int>
        /** Del iz segmenta, ki ni zadnji (zadnji je krajsi); enodelna datoteka: del = velikost. */
        val delBajtov: Long?
        @Volatile private var znani: Map<Int, Pair<Long, Long>> = emptyMap()
        private val zaklep = Any()

        init {
            if (!segmenti.containsKey(stevilkaPrvega)) throw NapakaToka(Izid.NESKLADNO)
            var c = datoteka.segmenti.size + datoteka.manjka
            prvi.delov?.let { c = maxOf(c, it) }
            celota = c
            if (velikost < celota) throw NapakaToka(Izid.NESKLADNO)
            manjkajo = (1..celota).filter { !segmenti.containsKey(it) }
            delBajtov = if (stevilkaPrvega < celota || celota == 1) prvi.podatki.size.toLong() else null
            vnesi(stevilkaPrvega, prvi)
        }

        override fun toString() = "Preslikava($velikost B, $celota segmentov, ${manjkajo.size} manjka)"

        val imaLuknje: Boolean get() = manjkajo.isNotEmpty()

        fun segment(stevilka: Int): UsenetNzb.Segment = segmenti[stevilka] ?: throw NapakaToka(Izid.LUKNJA)

        fun znani(): Map<Int, Pair<Long, Long>> = znani

        /** Prebran segment postane sidro; druga velikost datoteke ali nasprotje z znanimi = NESKLADNO. */
        fun vnesi(stevilka: Int, d: UsenetYenc.Del) {
            if (d.velikostDatoteke != velikost || d.podatki.isEmpty()) throw NapakaToka(Izid.NESKLADNO)
            synchronized(zaklep) {
                val novi = HashMap(znani)
                novi[stevilka] = d.zacetek to d.podatki.size.toLong()
                preveriZnane(velikost, celota, novi)
                znani = novi
            }
        }

        /** Segment za odmik; manjkajoc segment = LUKNJA. */
        fun poisci(odmik: Long): Ugib {
            val u = poisci(velikost, celota, delBajtov, znani, odmik)
            if (!segmenti.containsKey(u.stevilka)) throw NapakaToka(Izid.LUKNJA)
            return u
        }

        fun segmentiOkna(odmik: Long, dolzina: Long): List<Int> =
            segmentiOkna(velikost, celota, delBajtov, znani, odmik, dolzina).filter { segmenti.containsKey(it) }
    }
}
