package si.safeer.tv.os

import si.safeer.tv.os.RazpolozljivostPravila as P

/**
 * Pravila »preverjevalca doma« brez Androida (preizkus: tests/DomPreverjanjePravilaTest.kt; protokol:
 * docs/HOME-VERIFIER.md).
 *
 * Seznam pokaze film sele, ko dodatek potrdi, da se ga da predvajati. Dodatek steje vprasanja po domacem naslovu, ne
 * po napravi: tri naprave, ki vsaka zase sprasujejo v svoji meji, so skupaj cez mejo (4. 10. 2026 je dodatek omejil
 * ves dom - takrat ne da niti toka za film). Zato v domu sprasuje ENA naprava - preverjevalec: tista, ki je najdlje
 * vklopljena (televizor pred tablico in telefonom). Ostale ji povedo, katere naslove potrebujejo, in dobijo odgovor.
 *
 *  - eno vprasanje na naslov za ves dom (dve napravi z istim seznamom ne vprasata dvakrat);
 *  - en proracun vprasanj za ves dom (vedro zetonov preverjevalca);
 *  - kdor caka (okno seznama), je pred delom na zalogo; delo na zalogo tece pocasi in samo, ko nihce ne caka -
 *    naslovi za oknom so tako preverjeni, se preden uporabnik pride do njih.
 *
 * Preverjevalca ni (naprava je sama, drugi ne odgovorijo, imajo druge dodatke): naprava preverja sama, kot doslej.
 */
object DomPreverjanjePravila {
    const val DEJANJE = "avail.get"
    /** Zmoznost v prijavi v Link: naprava zna biti preverjevalec doma. */
    const val ZMOZNOST = "avail"

    /** Najvec kljucev v enem vprasanju: okno (nanje nekdo caka) in na zalogo. */
    const val NAJVEC_OKNO = 30
    const val NAJVEC_NAPREJ = 90
    /** Najvec naslovov, ki cakajo na preverjanje na zalogo. */
    const val NAJVEC_ZALOGE = 400
    /** Naslov iz okna je zelen, dokler ga odjemalec sprasuje; ko neha (zaprl je seznam), gre med zalogo. */
    const val ZELJA_VELJA_MS = 15_000L
    /** Naslov na zalogo caka najvec toliko; potem ga pozabimo (seznam se je medtem verjetno spremenil). */
    const val ZALOGA_VELJA_MS = 12 * 3_600_000L
    /** Najdaljse cakanje enega vprasanja na prvi nov odgovor (dolgo povprasevanje). */
    const val CAKAJ_NAJVEC_MS = 4_000L
    /** Po neuspelem preverjanju (dodatek ni odgovoril) istega naslova nekaj casa ne vprasamo znova. */
    const val PO_NEUSPEHU_MS = 60_000L
    /** Delo na zalogo: najvec eno vprasanje na toliko casa in samo, ko je vedro zetonov skoraj polno. */
    const val ZALOGA_RAZMIK_MS = 20_000L
    const val ZALOGA_ZETONOV = 3.0
    /** Po uporabnikovem dejanju (zacetek filma, okno seznama) delo na zalogo pocaka. */
    const val ZALOGA_PO_UPORABNIKU_MS = 30_000L
    /** Po omejitvi dodatka (premor) dela na zalogo nekaj casa ni. */
    const val ZALOGA_PO_PREMORU_MS = 2 * 3_600_000L
    /** Najvec vprasanj na zalogo na dan. */
    const val ZALOGA_NA_DAN = 600

    // ------------------------------------------------------------------ kdo preverja

    /** Rang naprave za vlogo preverjevalca: televizor (vedno vklopljen) pred tablico in telefonom; 0 = ne more biti. */
    fun rang(platforma: String): Int = when (platforma) { "tv" -> 3; "tablet" -> 2; "phone" -> 1; else -> 0 }

    /** [id]: naslov naprave v Linku (komu poslati); [naprava]: fizicna naprava (za enak vrstni red na vseh napravah). */
    data class Kandidat(val id: String, val naprava: String, val platforma: String)

    private val VRSTNI_RED = compareByDescending<Kandidat> { rang(it.platforma) }.thenBy { it.naprava }.thenBy { it.id }

    /**
     * Kdo preverja za dom. Vse naprave pridejo do istega odgovora: najvisji rang, pri enakem manjsa oznaka naprave.
     * Vrne naslov naprave, ki jo vprasamo, ali null, ce preverja ta naprava ([jaz]; null = ta aplikacija ne more biti
     * preverjevalec, npr. Predvajalnik, a druge lahko vprasa). [izloceni]: naprave, ki zdaj ne morejo (ne odgovorijo,
     * imajo druge dodatke, so na omejenem omrezju).
     */
    fun izberi(jaz: Kandidat?, drugi: List<Kandidat>, izloceni: Set<String> = emptySet()): String? {
        val najboljsi = drugi.filter { rang(it.platforma) > 0 && it.id !in izloceni && it.naprava != jaz?.naprava }.minWithOrNull(VRSTNI_RED) ?: return null
        if (jaz == null || rang(jaz.platforma) == 0) return najboljsi.id
        return if (VRSTNI_RED.compare(jaz, najboljsi) <= 0) null else najboljsi.id
    }

    // ------------------------------------------------------------------ vprasanje

    /** Kljuc, ki ga sme vprasati druga naprava: film, serija ali epizoda z javnim id-jem (kot pri delitvi zapisov). */
    fun veljaven(kljuc: String): Boolean = P.zaDelitev(kljuc)

    /** (tip, id) iz kljuca »tip|id«. */
    fun razstavi(kljuc: String): Pair<String, String> = kljuc.substringBefore('|') to kljuc.substringAfter('|', "")

    /** Serija kot celota (»series|tt…« brez epizode): preverimo jo po prvi epizodi. */
    fun jeSerija(kljuc: String): Boolean = razstavi(kljuc).let { (tip, id) -> tip == "series" && ':' !in id }

    /** Meja naprave iz vprasanja: »M« = vsak torrent, stevilo = bajti, 0 = samo neposredni tokovi; neveljavno = 0. */
    fun mejaIzNiza(s: String?): Long = when {
        s == null -> 0L
        s == "M" -> P.VSE
        else -> s.toLongOrNull()?.takeIf { it >= 0L } ?: 0L
    }

    fun mejaVNiz(meja: Long): String = if (meja == P.VSE) "M" else meja.coerceAtLeast(0L).toString()

    // ------------------------------------------------------------------ vrsta preverjevalca

    class Vnos(val kljuc: String, var meja: Long, var zadnjic: Long, val dodan: Long)

    /**
     * Kaj je treba preveriti: okno (nekdo caka) pred zalogo. Ni varna za niti - klicatelj jo zaklene.
     * Isti naslov je v vrsti samo enkrat, ne glede na to, koliko naprav ga zeli (eno vprasanje na dom).
     */
    class Vrsta {
        private val okno = LinkedHashMap<String, Vnos>()
        private val zaloga = LinkedHashMap<String, Vnos>()
        private val vDelu = HashSet<String>()
        private val neuspeh = HashMap<String, Long>()

        val velikostOkna: Int get() = okno.size
        val velikostZaloge: Int get() = zaloga.size

        private fun nedavnoNeuspel(k: String, zdaj: Long): Boolean {
            val t = neuspeh[k] ?: return false
            if (zdaj - t in 0 until PO_NEUSPEHU_MS) return true
            neuspeh.remove(k)
            return false
        }

        /** Naslov, na katerega nekdo caka. [meja]: kaj zmore naprava, ki sprasuje; obvelja najmanjsa med cakajocimi. */
        fun zeli(k: String, meja: Long, zdaj: Long) {
            zaloga.remove(k)
            val v = okno[k]
            if (v == null) okno[k] = Vnos(k, meja, zdaj, zdaj) else { v.zadnjic = zdaj; if (meja < v.meja) v.meja = meja }
        }

        /** Naslov na zalogo (nihce ne caka). Vrne false, ce je vrsta polna. */
        fun naZalogo(k: String, zdaj: Long): Boolean {
            if (k in okno || k in zaloga) return true
            if (zaloga.size >= NAJVEC_ZALOGE) return false
            zaloga[k] = Vnos(k, P.BREZ, zdaj, zdaj)
            return true
        }

        /** Naslovi iz okna, ki jih nihce vec ne sprasuje, gredo med zalogo (uporabnik se bo verjetno vrnil); stara zaloga izgine. */
        fun pospravi(zdaj: Long) {
            val it = okno.entries.iterator()
            while (it.hasNext()) {
                val v = it.next().value
                if (zdaj - v.zadnjic in 0..ZELJA_VELJA_MS || v.kljuc in vDelu) continue
                it.remove()
                if (zaloga.size < NAJVEC_ZALOGE) zaloga[v.kljuc] = Vnos(v.kljuc, P.BREZ, zdaj, zdaj)
            }
            zaloga.values.removeAll { zdaj - it.dodan !in 0..ZALOGA_VELJA_MS && it.kljuc !in vDelu }
        }

        /** Ali kdo caka na naslov, ki se ni v delu (potem delo na zalogo odstopi). */
        fun oknoCaka(zdaj: Long): Boolean = okno.values.any { it.kljuc !in vDelu && !nedavnoNeuspel(it.kljuc, zdaj) && zdaj - it.zadnjic in 0..ZELJA_VELJA_MS }

        /**
         * Naslednji naslov za preverjanje ali null; drugi del para pove, ali nanj kdo caka (okno). [tudiZaloga]: sme
         * vzeti tudi delo na zalogo. Vzeti naslov je »v delu«, dokler ga [koncano] ne sprosti.
         */
        fun naslednji(zdaj: Long, tudiZaloga: Boolean): Pair<Vnos, Boolean>? {
            pospravi(zdaj)
            okno.values.firstOrNull { it.kljuc !in vDelu && !nedavnoNeuspel(it.kljuc, zdaj) }?.let { vDelu.add(it.kljuc); return it to true }
            if (!tudiZaloga) return null
            zaloga.values.firstOrNull { it.kljuc !in vDelu && !nedavnoNeuspel(it.kljuc, zdaj) }?.let { vDelu.add(it.kljuc); return it to false }
            return null
        }

        /** Ali naslov se kdo zeli (med cakanjem na zeton): okno - sprasuje ga se kdo; zaloga - se je v vrsti. */
        fun seZelen(k: String, zdaj: Long): Boolean = okno[k]?.let { zdaj - it.zadnjic in 0..ZELJA_VELJA_MS } ?: (k in zaloga)

        /**
         * Preverjanje naslova je koncano. [odgovor]: dodatki so odgovorili (zapis je shranjen); sicer naslova nekaj casa
         * ne vprasamo znova. [preklicano]: vprasanja nismo poslali (nihce ga ni vec zelel) - naslov ostane, kjer je.
         */
        fun koncano(k: String, odgovor: Boolean, zdaj: Long, preklicano: Boolean = false) {
            vDelu.remove(k)
            if (preklicano) return
            okno.remove(k); zaloga.remove(k)
            if (odgovor) neuspeh.remove(k) else neuspeh[k] = zdaj
            if (neuspeh.size > 2_000) neuspeh.entries.removeAll { zdaj - it.value !in 0 until PO_NEUSPEHU_MS }
        }

        /** Koliko od teh naslovov se caka (v vrsti ali v delu). */
        fun caka(kljuci: Collection<String>): Int = kljuci.count { it in okno || it in vDelu }

        /** Naslovi, ki jih pravkar nismo mogli preveriti (odjemalec naj nanje ne caka). */
        fun neuspeli(kljuci: Collection<String>, zdaj: Long): List<String> = kljuci.filter { it !in okno && it !in vDelu && nedavnoNeuspel(it, zdaj) }
    }

    // ------------------------------------------------------------------ delo na zalogo

    /** Stanje dela na zalogo (stevec na dan, zadnje vprasanje); cisto, da ga lahko preizkusimo. */
    class Zaloga {
        var zadnjic = 0L
        var dan = ""
        var danes = 0

        /**
         * Ali sme preverjevalec zdaj vprasati na zalogo. [zetonov]: koliko vprasanj dodatek ta hip se dovoli;
         * [uporabnikOb]: zadnje uporabnikovo dejanje (film, okno seznama); [premorOb]: zadnja omejitev dodatka;
         * [sme]: naprava ima moc (napajanje ali dovolj baterije, ne predvaja, neomejeno omrezje).
         */
        fun smem(zdaj: Long, danasnji: String, zetonov: Double, uporabnikOb: Long, premorOb: Long, sme: Boolean): Boolean {
            if (!sme) return false
            if (dan != danasnji) { dan = danasnji; danes = 0 }
            if (danes >= ZALOGA_NA_DAN) return false
            if (zetonov < ZALOGA_ZETONOV) return false
            if (zdaj - zadnjic in 0 until ZALOGA_RAZMIK_MS) return false
            if (zdaj - uporabnikOb in 0 until ZALOGA_PO_UPORABNIKU_MS) return false
            if (premorOb > 0L && zdaj - premorOb in 0 until ZALOGA_PO_PREMORU_MS) return false
            return true
        }

        fun vprasano(zdaj: Long) { zadnjic = zdaj; danes++ }
    }
}
