package si.safeer.tv.os

/**
 * Preslikava odmikov za lasten pogon Usenet, korak 2 (lastnik 26. 10. 2026) - enako kot core/usenet_tok.py v Safeer-linux.
 *
 * Datoteka iz NZB je zaporedje segmentov (clankov yEnc z =ypart begin/end); NZB pove samo velikost kodiranih clankov.
 * Prvi segment da velikost datoteke in dela. Kodirniki delijo na enake dele (zadnji krajsi): odmik o lezi v segmentu
 * o / del + 1 - to je UGIB; vsak prebran segment postane sidro ([Preslikava.vnesi]), ugib med sidri pa enakomerna
 * razdelitev vrzeli, zato se tudi dve zdruzeni objavi najdeta v nekaj branjih. Segment, ki ga v NZB ni, je luknja.
 *
 * Pregleda Grok 58/61: T1 - yEnc total iz clanka najvec 200 000 in velikost <= celota x 4 MiB, manjkajoci samo kot
 * stevilo (prej seznam vseh, na TV OutOfMemoryError iz enega clanka); T6 - urejena sidra z dvojiskim iskanjem, O(log n)
 * na branje namesto urejanja in kopije vseh znanih.
 *
 * Pravila so skupna: tests/skupni-primeri/usenet-pogon.json (tok_odmik, tok_segmenti, tok_preslikava). Brez omrezja;
 * message-id ni v toString ali izjemi.
 */
object UsenetTok {
    /** Imena so ista kot v skupnih primerih (male crke). */
    enum class Izid { IZVEN, LUKNJA, NESKLADNO, POKVARJEN, ZAPRTO, PREVEC, PREKINJENO }

    /** Napaka toka; sporocilo nima message-id ne imena datoteke. */
    class NapakaToka(val izid: Izid) : java.io.IOException("usenet tok: " + izid.name.lowercase())

    /** Najvec branj, s katerimi bralnik isce segment za odmik. */
    const val NAJVEC_ISKANJ = 24
    /** Enako kot UsenetNzb (meja segmentov datoteke) in UsenetYenc.NAJVEC_DELA. */
    const val NAJVEC_SEGMENTOV_DATOTEKE = 200_000
    const val NAJVEC_DELA: Long = UsenetYenc.NAJVEC_DELA.toLong()

    /** Segment (od 1), odmik v njem, [natancno] = razpon segmenta je znan. */
    data class Ugib(val stevilka: Int, val odmik: Long, val natancno: Boolean)

    /**
     * Znani razponi, urejeni po stevilki (in zato po odmiku): vnos preveri samo soseda, iskanje dvojisko - O(log n).
     * Pravila so ista kot prej: vsak vmesni segment ima vsaj en bajt, sosednja segmenta se stikata, zadnji znan pusti
     * prostor za ostale, segment [celota] konca na velikosti datoteke. Ni varna za vec niti (Preslikava zaklene).
     */
    class Sidra(val velikost: Long, val celota: Int) {
        private var st = IntArray(16)
        private var z = LongArray(16)
        private var d = LongArray(16)
        var n = 0; private set

        private fun par(stA: Int, konecA: Long, stB: Int, zacB: Long) =
            zacB - konecA >= (stB - stA - 1).toLong() && (stB != stA + 1 || zacB == konecA)

        /** Prvi indeks z st[i] >= x. */
        private fun poSt(x: Int): Int {
            var lo = 0
            var hi = n
            while (lo < hi) { val m = (lo + hi) ushr 1; if (st[m] < x) lo = m + 1 else hi = m }
            return lo
        }

        /** Zadnji indeks z z[i] <= x ali -1. */
        private fun poZ(x: Long): Int {
            var lo = 0
            var hi = n
            while (lo < hi) { val m = (lo + hi) ushr 1; if (z[m] <= x) lo = m + 1 else hi = m }
            return lo - 1
        }

        fun dodaj(s: Int, zz: Long, dd: Long) {
            if (s < 1 || s > celota || dd < 1 || zz < 0 || zz > velikost - dd) throw NapakaToka(Izid.NESKLADNO)
            val i = poSt(s)
            val enak = i < n && st[i] == s
            if (enak && z[i] == zz && d[i] == dd) return
            val lst = if (i > 0) st[i - 1] else 0
            val lkon = if (i > 0) z[i - 1] + d[i - 1] else 0L
            val j = if (enak) i + 1 else i
            if (!par(lst, lkon, s, zz)) throw NapakaToka(Izid.NESKLADNO)
            if (j < n) {
                if (!par(s, zz + dd, st[j], z[j])) throw NapakaToka(Izid.NESKLADNO)
            } else if (velikost - (zz + dd) < (celota - s).toLong() || (s == celota && zz + dd != velikost)) {
                throw NapakaToka(Izid.NESKLADNO)
            }
            if (enak) { z[i] = zz; d[i] = dd; return }
            if (n == st.size) { st = st.copyOf(n * 2); z = z.copyOf(n * 2); d = d.copyOf(n * 2) }
            System.arraycopy(st, i, st, i + 1, n - i)
            System.arraycopy(z, i, z, i + 1, n - i)
            System.arraycopy(d, i, d, i + 1, n - i)
            st[i] = s; z[i] = zz; d[i] = dd
            n++
        }

        fun znan(s: Int): Pair<Long, Long>? {
            val i = poSt(s)
            return if (i < n && st[i] == s) z[i] to d[i] else null
        }

        /** Znan segment za odmik (stevilka, odmik v njem) ali null; [vrzel] dobi (levo_st, levo_konec, desno_st, desno_zac). */
        fun okoliOdmika(odmik: Long, vrzel: LongArray): Pair<Int, Long>? {
            val i = poZ(odmik)
            if (i >= 0 && odmik < z[i] + d[i]) return st[i] to odmik - z[i]
            vrzel[0] = if (i >= 0) st[i].toLong() else 0L
            vrzel[1] = if (i >= 0) z[i] + d[i] else 0L
            vrzel[2] = if (i + 1 < n) st[i + 1].toLong() else (celota + 1).toLong()
            vrzel[3] = if (i + 1 < n) z[i + 1] else velikost
            return null
        }

        fun okoliStevilke(s: Int, vrzel: LongArray) {
            val i = poSt(s)
            vrzel[0] = if (i > 0) st[i - 1].toLong() else 0L
            vrzel[1] = if (i > 0) z[i - 1] + d[i - 1] else 0L
            vrzel[2] = if (i < n) st[i].toLong() else (celota + 1).toLong()
            vrzel[3] = if (i < n) z[i] else velikost
        }

        fun kotSlovar(): Map<Int, Pair<Long, Long>> = (0 until n).associate { st[it] to (z[it] to d[it]) }

        companion object {
            fun izSlovarja(velikost: Long, celota: Int, znani: Map<Int, Pair<Long, Long>>): Sidra {
                val s = Sidra(velikost, celota)
                for ((k, v) in znani.entries.sortedBy { it.key }) s.dodaj(k, v.first, v.second)
                return s
            }
        }
    }

    private fun enakomerno(lst: Int, lkon: Long, dst: Int, dzac: Long, del: Long?, celota: Int): Boolean {
        if (del == null || del <= 0 || lkon != lst * del) return false
        if (dst == celota + 1) return (celota - 1) * del < dzac && dzac <= celota * del
        return dzac == (dst - 1) * del
    }

    private fun poisci(s: Sidra, delBajtov: Long?, odmik: Long): Ugib {
        if (odmik < 0 || odmik >= s.velikost) throw NapakaToka(Izid.IZVEN)
        if (s.celota < 1 || s.velikost < s.celota) throw NapakaToka(Izid.NESKLADNO)
        val v = LongArray(4)
        s.okoliOdmika(odmik, v)?.let { return Ugib(it.first, it.second, true) }
        val lst = v[0].toInt()
        val lkon = v[1]
        val dst = v[2].toInt()
        val dzac = v[3]
        if (enakomerno(lst, lkon, dst, dzac, delBajtov, s.celota)) {
            val st = (odmik / delBajtov!!).toInt() + 1
            return Ugib(st, odmik - (st - 1) * delBajtov, false)
        }
        val na = (dzac - lkon).toDouble() / (dst - lst - 1)
        val st = (lst + 1 + Math.floor((odmik - lkon) / na).toInt()).coerceIn(lst + 1, dst - 1)
        return Ugib(st, odmik - (lkon + ((st - lst - 1) * na).toLong()), false)
    }

    private fun razpon(s: Sidra, delBajtov: Long?, stevilka: Int): Triple<Long, Long, Boolean> {
        if (stevilka < 1 || stevilka > s.celota) throw NapakaToka(Izid.IZVEN)
        s.znan(stevilka)?.let { return Triple(it.first, it.second, true) }
        val v = LongArray(4)
        s.okoliStevilke(stevilka, v)
        val lst = v[0].toInt()
        val lkon = v[1]
        val dst = v[2].toInt()
        val dzac = v[3]
        if (enakomerno(lst, lkon, dst, dzac, delBajtov, s.celota)) {
            val z = (stevilka - 1) * delBajtov!!
            return Triple(z, minOf(delBajtov, dzac - z), false)
        }
        val na = (dzac - lkon).toDouble() / (dst - lst - 1)
        val z = lkon + ((stevilka - lst - 1) * na).toLong()
        val k = if (stevilka + 1 < dst) lkon + ((stevilka - lst) * na).toLong() else dzac
        return Triple(z, maxOf(1L, k - z), false)
    }

    private fun segmentiOkna(s: Sidra, delBajtov: Long?, odmik: Long, dolzina: Long): List<Int> {
        if (dolzina <= 0 || odmik < 0 || odmik >= s.velikost) return emptyList()
        val konec = minOf(s.velikost, odmik + dolzina)
        var st = poisci(s, delBajtov, odmik).stevilka
        val izid = ArrayList<Int>()
        while (st <= s.celota) {
            if (razpon(s, delBajtov, st).first >= konec) break
            izid += st
            st++
        }
        return izid
    }

    /** Segment za [odmik] (skupni primeri tok_odmik). Napaka: [NapakaToka] IZVEN ali NESKLADNO. */
    fun poisci(velikost: Long, celota: Int, delBajtov: Long?, znani: Map<Int, Pair<Long, Long>>, odmik: Long): Ugib {
        if (odmik < 0 || odmik >= velikost) throw NapakaToka(Izid.IZVEN)
        if (celota < 1 || velikost < celota) throw NapakaToka(Izid.NESKLADNO)
        return poisci(Sidra.izSlovarja(velikost, celota, znani), delBajtov, odmik)
    }

    /** (odmik, dolzina, natancno) segmenta - znan ali ocenjen enako kot v [poisci]. */
    fun razpon(velikost: Long, celota: Int, delBajtov: Long?, znani: Map<Int, Pair<Long, Long>>, stevilka: Int): Triple<Long, Long, Boolean> {
        if (stevilka < 1 || stevilka > celota) throw NapakaToka(Izid.IZVEN)
        return razpon(Sidra.izSlovarja(velikost, celota, znani), delBajtov, stevilka)
    }

    /** Segmenti, ki pokrivajo [odmik, odmik + dolzina) (skupni primeri tok_segmenti). */
    fun segmentiOkna(velikost: Long, celota: Int, delBajtov: Long?, znani: Map<Int, Pair<Long, Long>>, odmik: Long, dolzina: Long): List<Int> {
        if (dolzina <= 0 || odmik < 0 || odmik >= velikost) return emptyList()
        return segmentiOkna(Sidra.izSlovarja(velikost, celota, znani), delBajtov, odmik, dolzina)
    }

    /**
     * Celota segmentov iz NZB in glave prvega clanka (skupni primeri tok_preslikava): yEnc total nad
     * [NAJVEC_SEGMENTOV_DATOTEKE] = NESKLADNO, sicer celota = max(NZB, total); velikost < celota ali > celota x 4 MiB =
     * NESKLADNO.
     */
    fun mejePreslikave(stevilVNzb: Int, manjkaVNzb: Int, velikost: Long, skupaj: Int?): Int {
        var celota = stevilVNzb + manjkaVNzb
        if (skupaj != null) {
            if (skupaj > NAJVEC_SEGMENTOV_DATOTEKE) throw NapakaToka(Izid.NESKLADNO)
            celota = maxOf(celota, skupaj)
        }
        if (velikost < celota || velikost > celota * NAJVEC_DELA) throw NapakaToka(Izid.NESKLADNO)
        return celota
    }

    /** Odmiki ene datoteke iz NZB. Varna za vec niti (bralnik vnasa segmente vzporedno). */
    class Preslikava(datoteka: UsenetNzb.Datoteka, prvi: UsenetYenc.Del, stevilkaPrvega: Int = datoteka.segmenti[0].stevilka) {
        private val segmenti = datoteka.segmenti.associateBy { it.stevilka }
        val ime: String? = datoteka.ime
        val velikost: Long = prvi.velikostDatoteke
        val celota: Int
        /** Stevilo manjkajocih segmentov (nikoli seznam - T1). */
        val manjka: Int
        /** Del iz segmenta, ki ni zadnji (zadnji je krajsi); enodelna datoteka: del = velikost. */
        val delBajtov: Long?
        private val sidra: Sidra
        private val zaklep = Any()

        init {
            if (!segmenti.containsKey(stevilkaPrvega)) throw NapakaToka(Izid.NESKLADNO)
            celota = mejePreslikave(datoteka.segmenti.size, datoteka.manjka, velikost, prvi.delov)
            manjka = celota - segmenti.size
            delBajtov = if (stevilkaPrvega < celota || celota == 1) prvi.podatki.size.toLong() else null
            sidra = Sidra(velikost, celota)
            vnesi(stevilkaPrvega, prvi)
        }

        override fun toString() = "Preslikava($velikost B, $celota segmentov, $manjka manjka)"

        val imaLuknje: Boolean get() = manjka > 0

        /** Prvih [najvec] manjkajocih stevilk (diagnostika). */
        fun manjkajoci(najvec: Int = 100): List<Int> {
            val izid = ArrayList<Int>()
            var s = 1
            while (s <= celota && izid.size < najvec && izid.size < manjka) {
                if (!segmenti.containsKey(s)) izid += s
                s++
            }
            return izid
        }

        fun segment(stevilka: Int): UsenetNzb.Segment = segmenti[stevilka] ?: throw NapakaToka(Izid.LUKNJA)

        fun znani(): Map<Int, Pair<Long, Long>> = synchronized(zaklep) { sidra.kotSlovar() }

        /** Prebran segment postane sidro; druga velikost datoteke ali nasprotje z znanimi = NESKLADNO. */
        fun vnesi(stevilka: Int, d: UsenetYenc.Del) {
            if (d.velikostDatoteke != velikost || d.podatki.isEmpty()) throw NapakaToka(Izid.NESKLADNO)
            vnesiRazpon(stevilka, d.zacetek, d.podatki.size.toLong())
        }

        internal fun vnesiRazpon(stevilka: Int, z: Long, n: Long) = synchronized(zaklep) { sidra.dodaj(stevilka, z, n) }

        /** Segment za odmik; manjkajoc segment = LUKNJA. */
        fun poisci(odmik: Long): Ugib {
            val u = synchronized(zaklep) { poisci(sidra, delBajtov, odmik) }
            if (!segmenti.containsKey(u.stevilka)) throw NapakaToka(Izid.LUKNJA)
            return u
        }

        fun segmentiOkna(odmik: Long, dolzina: Long): List<Int> =
            synchronized(zaklep) { segmentiOkna(sidra, delBajtov, odmik, dolzina) }.filter { segmenti.containsKey(it) }
    }
}
