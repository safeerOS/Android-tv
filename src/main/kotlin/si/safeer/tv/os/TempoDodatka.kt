package si.safeer.tv.os

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Koliko poizvedb sme dom v eni uri poslati enemu dodatku za preverjanje v ozadju. Cisti JVM
 * (tests/TempoDodatkaTest.kt); omrezje in shramba sta v [Stremio].
 *
 * Dodatek steje poizvedbe po domacem naslovu in svoje meje ne pove vnaprej. Ko je presezena, nekaj casa ne da niti
 * toka za film, ki ga uporabnik res hoce gledati. 4. 10. 2026 je ena sama naprava z eno poizvedbo na 8 s po daljsem
 * brskanju spet sprozila omejitev: kratek razmik med poizvedbami ni dovolj, steje tudi vsota. Zato:
 *  - stejemo vse poizvedbe po tokovih zadnje ure, tudi tiste za predvajanje ([Ura]);
 *  - preverjanje v ozadju sme do [Stanje.naUro] poizvedb na uro - kar ostane do dodatkove meje, je za predvajanje;
 *  - delo na zalogo (nihce ne caka) sme le do tretjine tega;
 *  - ko dodatek kljub temu omeji, se meja takoj prepolovi glede na to, kar je naprava v zadnji uri res poslala, in
 *    se nato pocasi vraca (cetrtino na dan brez omejitve);
 *  - ce dodatek sam pove, koliko naj pocakamo (Retry-After, RateLimit-Reset), velja njegova beseda.
 */
object TempoDodatka {
    /** Privzeta meja: toliko poizvedb v ozadju na uro pri enem dodatku. */
    const val NA_URO = 150
    const val NA_URO_NAJMANJ = 30
    const val OKREVANJE_MS = 24 * 3_600_000L
    /** Porabljen proracun: preverjanje v ozadju pocaka, da vsota zadnje ure pade na toliko odstotkov meje. */
    const val NADALJUJ_ODSTOTKOV = 75
    const val CAKAJ_NAJMANJ_MS = 60_000L
    const val CAKAJ_NAJVEC_MS = 2 * 3_600_000L
    private const val MINUT = 60
    private const val MINUTA_MS = 60_000L

    /** Stevec poizvedb zadnje ure: 60 minutnih predalov, ki se praznijo sami. */
    class Ura {
        private val predali = IntArray(MINUT)
        private var minuta = 0L

        @Synchronized fun dodaj(zdaj: Long, koliko: Int = 1) { premakni(zdaj); predali[mesto(zdaj / MINUTA_MS)] += koliko }

        @Synchronized fun vsota(zdaj: Long): Int { premakni(zdaj); return predali.sum() }

        /** Cez koliko ms bo vsota zadnje ure najvec [meja] (0 = ze je). Predal izpade ob polni minuti. */
        @Synchronized fun cezKolikoDo(meja: Int, zdaj: Long): Long {
            premakni(zdaj)
            var vsota = predali.sum()
            if (vsota <= meja) return 0L
            val m = zdaj / MINUTA_MS
            // Najstarejsi predal (minuta m - 59) izpade ob zacetku minute m + 1, naslednji minuto pozneje ...
            for (k in 1..MINUT) {
                vsota -= predali[mesto(m - MINUT + k)]
                if (vsota <= meja) return (m + k) * MINUTA_MS - zdaj
            }
            return MINUT * MINUTA_MS
        }

        /** Zapis za shrambo: "minuta;c0,c1,...,c59". */
        @Synchronized fun vNiz(): String = "$minuta;" + predali.joinToString(",")

        private fun mesto(m: Long) = ((m % MINUT + MINUT) % MINUT).toInt()

        private fun premakni(zdaj: Long) {
            val m = zdaj / MINUTA_MS
            // Ura naprave je sla vec kot uro nazaj (zapis iz »prihodnosti«): stari predali ne bi nikoli izpadli.
            if (minuta - m > MINUT) { predali.fill(0); minuta = m; return }
            if (m <= minuta) return
            if (m - minuta >= MINUT) predali.fill(0) else for (i in minuta + 1..m) predali[mesto(i)] = 0
            minuta = m
        }

        companion object {
            /** Iz zapisa [vNiz]; pokvarjen ali prazen zapis da prazen stevec. */
            fun izNiza(niz: String?): Ura {
                val u = Ura()
                val deli = niz.orEmpty().split(";")
                if (deli.size != 2) return u
                val m = deli[0].toLongOrNull() ?: return u
                val st = deli[1].split(",").map { it.toIntOrNull() ?: return u }
                if (m < 0 || st.size != MINUT || st.any { it < 0 || it > 100_000 }) return u
                st.forEachIndexed { i, v -> u.predali[i] = v }
                u.minuta = m
                return u
            }
        }
    }

    /** Kar vemo o dodatku: meja poizvedb v ozadju na uro, kdaj je nazadnje omejil in kdaj se je meja nazadnje vracala. */
    data class Stanje(val naUro: Int = NA_URO, val omejenOb: Long = 0L, val okrevalOb: Long = 0L) {
        fun vNiz() = "$naUro;$omejenOb;$okrevalOb"

        companion object {
            fun izNiza(niz: String?): Stanje {
                val d = niz.orEmpty().split(";")
                if (d.size != 3) return Stanje()
                val n = d[0].toIntOrNull() ?: return Stanje()
                val o = d[1].toLongOrNull() ?: return Stanje()
                val k = d[2].toLongOrNull() ?: return Stanje()
                return Stanje(n.coerceIn(NA_URO_NAJMANJ, NA_URO), o.coerceAtLeast(0L), k.coerceAtLeast(0L))
            }
        }
    }

    /**
     * Dodatek je omejil poizvedbe. [poslanih]: koliko jih je ta naprava poslala v zadnji uri - ocitno prevec, zato je
     * nova meja polovica tega (ali polovica stare meje, ce je ta nizja), a ne pod [NA_URO_NAJMANJ].
     */
    fun poOmejitvi(s: Stanje, poslanih: Int, zdaj: Long): Stanje =
        Stanje((minOf(s.naUro, poslanih.coerceAtLeast(0)) / 2).coerceIn(NA_URO_NAJMANJ, s.naUro), zdaj, zdaj)

    /** Vsak dan brez omejitve se meja vrne za cetrtino (najmanj za 5), do privzete. */
    fun okrevaj(s: Stanje, zdaj: Long): Stanje {
        if (s.naUro >= NA_URO || s.okrevalOb <= 0L || zdaj < s.okrevalOb) return s
        val dni = ((zdaj - s.okrevalOb) / OKREVANJE_MS).coerceAtMost(30L).toInt()
        if (dni <= 0) return s
        var n = s.naUro
        repeat(dni) { n = (n + maxOf(n / 4, 5)).coerceAtMost(NA_URO) }
        return s.copy(naUro = n, okrevalOb = s.okrevalOb + dni * OKREVANJE_MS)
    }

    /** Ali sme naprava dodatku poslati se eno poizvedbo v ozadju; [vUri]: vse poizvedbe po tokovih zadnje ure. */
    fun smeVOzadju(s: Stanje, vUri: Int) = vUri < s.naUro

    /** Delo na zalogo je zadnje na vrsti: le dokler je porabljena manj kot tretjina meje. */
    fun smeNaZalogo(s: Stanje, vUri: Int) = vUri < s.naUro / 3

    /** Proracun ure je porabljen: koliko casa preverjanje v ozadju pocaka. */
    fun cakajNaProracun(s: Stanje, ura: Ura, zdaj: Long): Long =
        ura.cezKolikoDo(s.naUro * NADALJUJ_ODSTOTKOV / 100, zdaj).coerceIn(CAKAJ_NAJMANJ_MS, CAKAJ_NAJVEC_MS)

    /**
     * Koliko casa dodatek prosi, da pocakamo: Retry-After (sekunde ali datum HTTP), sicer RateLimit-Reset oziroma
     * X-RateLimit-Reset (sekunde do ponastavitve ali cas Unix). null = dodatek tega ne pove. Imena glav z malimi crkami.
     */
    fun cakajPoGlavah(glave: Map<String, String>, zdaj: Long): Long? {
        val poGlavi = glave["retry-after"]?.trim()?.let { v -> v.toLongOrNull()?.let { it * 1000L } ?: datum(v)?.let { it - zdaj } }
        val ms = poGlavi ?: listOf("ratelimit-reset", "x-ratelimit-reset")
            .firstNotNullOfOrNull { glave[it]?.trim()?.substringBefore('.')?.toLongOrNull() }
            ?.let { s ->
                when {
                    s > 100_000_000_000L -> s - zdaj            // cas Unix v milisekundah
                    s > 1_000_000_000L -> s * 1000L - zdaj      // cas Unix v sekundah
                    else -> s * 1000L                           // sekunde do ponastavitve
                }
            } ?: return null
        return if (ms <= 0L) null else ms.coerceIn(CAKAJ_NAJMANJ_MS, CAKAJ_NAJVEC_MS)
    }

    private fun datum(v: String): Long? = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(v)?.time
    } catch (_: Exception) { null }

    /** Glave, ki povedo kaj o omejitvi, za dnevnik: samo znana imena, vrednosti skrajsane in brez posebnih znakov. */
    fun opisGlav(glave: Map<String, String>): String =
        glave.entries.filter { (k, _) -> k == "retry-after" || k == "cf-mitigated" || k.startsWith("ratelimit") || k.startsWith("x-ratelimit") }
            .sortedBy { it.key }
            .joinToString(", ") { (k, v) -> k + "=" + v.take(40).replace(Regex("[^0-9A-Za-z;=,. :-]"), "?") }
}
