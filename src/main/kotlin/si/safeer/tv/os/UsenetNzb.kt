package si.safeer.tv.os

import java.io.StringReader
import java.nio.charset.Charset
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/**
 * Pogon Usenet, korak 1: varna razclenitev NZB (newzbin NZB 1.1) v datoteke in segmente (message-id clankov), varno
 * ime datoteke in message-id. Cist objekt brez Androida in omrezja (tests/UsenetNzbTest.kt; pravila so skupna z
 * namizjem: tests/skupni-primeri/usenet-pogon.json - nzb_razcleni, nzb_ime, varno_ime, nntp_id).
 *
 * Varnost (XXE, razpihnjen vhod):
 * - »<!ENTITY«, »<!ELEMENT«, »<!ATTLIST«, »<!NOTATION« kjerkoli (tudi v komentarju) = zavrnjeno. Dovoljen je en sam
 *   preprost `<!DOCTYPE nzb PUBLIC "..." "...">` (ali SYSTEM ali brez id), kakrsnega imajo NZB indeksatorjev - pred
 *   razclenitvijo ga odstranimo, zato se zunanji DTD nikoli ne nalozi (Xerces bi ga privzeto prenesel). Vsak drug
 *   DOCTYPE = zavrnjeno.
 * - Razclenjevalnik ima se disallow-doctype-decl, brez zunanjih entitet in DTD, FEATURE_SECURE_PROCESSING in
 *   EntityResolver, ki zavrne vse. Android (Expat) nekaterih zastavic ne pozna - preverba besedila pred razclenitvijo je
 *   zato obvezna in ne zavisi od razclenjevalnika.
 * - SAX namesto DOM: NZB 50 GB izdaje ima ~70 000 segmentov; DOM bi ves NZB drzal v pomnilniku (TV ima malo RAM), SAX
 *   bere sproti in meje ([Meje]) ustavijo branje takoj, ko so presezene.
 * - Ime datoteke ([varnoIme]) je samo ime: brez poti, »..« in znakov, ki niso crka, stevka ali » ._-()[]+,!&'#@~«.
 * - Message-id ([cistId]) samo natisljiv ASCII brez presledkov in < > - nic iz NZB ne more vriniti ukaza v NNTP.
 */
object UsenetNzb {
    /** RFC 3977 3.6: message-id z oklepajema najvec 250 bajtov (skupni primeri: id_znakov). */
    const val NAJVEC_ID = 248

    /** Ime datoteke in naslov NZB v kodnih tockah (ime_znakov, naslov_znakov). */
    const val NAJVEC_IMENA = 200
    const val NAJVEC_NASLOVA = 200
    const val NAJVEC_SKUPINE = 200

    /** Meje stevila (velikost bajtov je [UsenetPravila.NAJVEC_NZB_BAJTOV]). Preizkusi jih lahko zmanjsajo. */
    class Meje(
        val datotek: Int = 5_000,
        val segmentov: Int = 500_000,
        val segmentovDatoteke: Int = 200_000,
        val skupin: Int = 50,
        /** Vseh elementov XML skupaj (tudi neznanih) - meja branja pred koncem dokumenta. */
        val elementov: Int = 1_000_000,
    )

    /** Imena so ista kot v skupnih primerih (izid v malih crkah). */
    enum class Napaka {
        /** Vec bajtov kot [UsenetPravila.NAJVEC_NZB_BAJTOV] ali vec datotek, segmentov ali elementov kot [Meje]. */
        PREVELIK,
        /** Pokvarjen XML, bajt NUL, nepodprto kodiranje, nedefinirana entiteta. */
        NAPAKA_XML,
        /** ENTITY, ELEMENT, ATTLIST, NOTATION ali nedovoljen DOCTYPE. */
        ZAVRNJENO_DTD,
        /** Koren ni `nzb` (stran HTML, napaka indeksatorja). */
        NI_NZB,
        /** Nobene datoteke z veljavnim segmentom. */
        PRAZEN,
    }

    class NapakaNzb(val vrsta: Napaka) : Exception("NZB: " + vrsta.name)

    /** Segment = en clanek Useneta. [bajtov] je velikost clanka po NZB (kodirano; 0 = ni znana). */
    class Segment(val stevilka: Int, val bajtov: Long, val id: String) {
        override fun toString() = "Segment($stevilka, $bajtov B)"   // brez message-id
    }

    /**
     * Datoteka iz NZB. [ime] = [imeIzZadeve] (null = zadeva nima imena, npr. zakrita izdaja - ime da kasneje glava yEnc
     * ali par2). [manjka] = koliko segmentov manjka glede na »(a/b)« v zadevi in najvecjo stevilko. [bajtov] je vsota
     * velikosti clankov iz NZB, NE dekodiranih podatkov - odmike da glava yEnc.
     */
    class Datoteka(val ime: String?, val datum: Long, val skupine: List<String>, val segmenti: List<Segment>, val manjka: Int) {
        val bajtov: Long get() = segmenti.sumOf { it.bajtov }
        override fun toString() = "Datoteka(${segmenti.size} segmentov, $bajtov B)"
    }

    /** Razclenjen NZB. Geslo (`<meta type="password">`) se ne hrani: [imaGeslo] pove, da je arhiv verjetno sifriran. */
    class Nzb(val datoteke: List<Datoteka>, val naslov: String?, val imaGeslo: Boolean) {
        override fun toString() = "Nzb(${datoteke.size} datotek)"
    }

    private val KODIRANJA = mapOf(
        // us-ascii je podmnozica UTF-8: bajti nad 127 se berejo kot UTF-8 (skupni primeri v2).
        "utf-8" to Charsets.UTF_8, "utf8" to Charsets.UTF_8, "us-ascii" to Charsets.UTF_8, "ascii" to Charsets.UTF_8,
        "iso-8859-1" to Charsets.ISO_8859_1, "iso_8859-1" to Charsets.ISO_8859_1, "latin1" to Charsets.ISO_8859_1,
        "latin-1" to Charsets.ISO_8859_1,
    )
    private val DEKLARACIJA = Regex("^<\\?xml[^>]*?\\sencoding\\s*=\\s*([\"'])([^\"']*)\\1")
    private val DTD_DEL = Regex("<!(ENTITY|ELEMENT|ATTLIST|NOTATION)")
    private const val NIZ = "(\"[^\"\\[\\]<>]*\"|'[^'\\[\\]<>]*')"
    private val PREPROST_DOCTYPE = Regex("<!DOCTYPE\\s+nzb(\\s+PUBLIC\\s+$NIZ\\s+$NIZ|\\s+SYSTEM\\s+$NIZ)?\\s*>")
    private val DOCTYPE = Regex("<!DOCTYPE", RegexOption.IGNORE_CASE)

    /** Razclenitev NZB; ob napaki [NapakaNzb]. */
    fun razcleni(bajti: ByteArray, meje: Meje = Meje()): Nzb {
        if (UsenetPravila.nzbPrevelik(bajti.size.toLong())) throw NapakaNzb(Napaka.PREVELIK)
        if (bajti.any { it == 0.toByte() }) throw NapakaNzb(Napaka.NAPAKA_XML)   // tudi UTF-16/32
        var od = 0
        if (bajti.size >= 3 && bajti[0] == 0xEF.toByte() && bajti[1] == 0xBB.toByte() && bajti[2] == 0xBF.toByte()) od = 3
        val glava = String(bajti, od, minOf(bajti.size - od, 1024), Charsets.ISO_8859_1)
        val kodiranje: Charset = if (glava.startsWith("<?xml")) {
            val ime = DEKLARACIJA.find(glava)?.groupValues?.get(2)
            if (ime == null) Charsets.UTF_8 else KODIRANJA[ime.lowercase()] ?: throw NapakaNzb(Napaka.NAPAKA_XML)
        } else {
            Charsets.UTF_8
        }
        val besedilo = String(bajti, od, bajti.size - od, kodiranje)   // napacni bajti -> U+FFFD
        if (DTD_DEL.containsMatchIn(besedilo)) throw NapakaNzb(Napaka.ZAVRNJENO_DTD)
        val cisto = brezDoctype(besedilo) ?: throw NapakaNzb(Napaka.ZAVRNJENO_DTD)
        val bralec = Bralec(meje)
        try {
            tovarna().newSAXParser().parse(InputSource(StringReader(cisto)), bralec)
        } catch (e: Exception) {
            throw NapakaNzb(bralec.napaka ?: Napaka.NAPAKA_XML)
        }
        bralec.napaka?.let { throw NapakaNzb(it) }
        if (bralec.datoteke.isEmpty()) throw NapakaNzb(Napaka.PRAZEN)
        return Nzb(bralec.datoteke, bralec.naslov, bralec.imaGeslo)
    }

    /** Besedilo brez preprostega DOCTYPE; null = DOCTYPE ni dovoljen (notranji del, male crke, drug koren, vec njih). */
    private fun brezDoctype(besedilo: String): String? {
        val vsi = DOCTYPE.findAll(besedilo).take(2).toList()
        if (vsi.isEmpty()) return besedilo
        if (vsi.size > 1) return null
        val i = vsi[0].range.first
        val m = PREPROST_DOCTYPE.find(besedilo, i)?.takeIf { it.range.first == i } ?: return null
        return besedilo.removeRange(m.range)
    }

    private fun tovarna(): SAXParserFactory {
        val f = SAXParserFactory.newInstance()
        f.isNamespaceAware = false
        f.isValidating = false
        val zastavice = listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
            XMLConstants.FEATURE_SECURE_PROCESSING to true,
        )
        for ((ime, vrednost) in zastavice) {
            try { f.setFeature(ime, vrednost) } catch (_: Exception) { }   // Android (Expat) jih nekaj ne pozna
        }
        try { f.isXIncludeAware = false } catch (_: Exception) { }
        return f
    }

    /**
     * Message-id brez oklepajev (skupni primeri: nntp_id): obrezani presledki, en par < > na robu odstranjen; veljaven je
     * 1-[NAJVEC_ID] znakov ASCII 0x21-0x7E brez < >; null = neveljaven. CR, LF ali presledek bi v NNTP zacel nov ukaz.
     */
    fun cistId(surovo: String): String? {
        var s = surovo.trim()
        if (s.length >= 2 && s.startsWith("<") && s.endsWith(">")) s = s.substring(1, s.length - 1)
        if (s.isEmpty() || s.length > NAJVEC_ID) return null
        return s.takeIf { it.all { c -> c.code in 0x21..0x7E && c != '<' && c != '>' } }
    }

    private const val DOVOLJENI = " ._-()[]+,!&'#@~"
    private val REZERVIRANA = Regex("(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])", RegexOption.IGNORE_CASE)

    /**
     * Samo ime datoteke (skupni primeri: varno_ime). Del za zadnjo / ali \; po kodnih tockah ostane crka (L*), stevka
     * (Nd) ali eden od » ._-()[]+,!&'#@~«, vse drugo postane »_«. Nad [NAJVEC_IMENA] kodnih tock: koncnica do 9 znakov
     * ostane, osnova se skrajsa (sicer prvih 200). Nato brez presledkov in pik na koncu ter presledkov na zacetku;
     * rezervirana imena Windows (CON, NUL, COM1 ...) dobijo »_« spredaj. null = ne ostane nic.
     */
    fun varnoIme(ime: String): String? {
        val golo = ime.replace('\\', '/').substringAfterLast('/')
        val tocke = ArrayList<Int>(golo.length)
        var i = 0
        while (i < golo.length) {
            val cp = golo.codePointAt(i)
            val dovoljen = Character.isLetter(cp) || Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt() ||
                (cp < 128 && DOVOLJENI.indexOf(cp.toChar()) >= 0)
            tocke += if (dovoljen) cp else '_'.code
            i += Character.charCount(cp)
        }
        var izbrane: List<Int> = tocke
        if (tocke.size > NAJVEC_IMENA) {
            val pika = tocke.lastIndexOf('.'.code)
            val koncnica = tocke.size - pika
            izbrane = if (pika >= 0 && koncnica <= 10) tocke.subList(0, NAJVEC_IMENA - koncnica) + tocke.subList(pika, tocke.size)
            else tocke.subList(0, NAJVEC_IMENA)
        }
        val sb = StringBuilder()
        for (cp in izbrane) sb.appendCodePoint(cp)
        val t = sb.toString().trimEnd(' ', '.').trimStart(' ')
        if (t.isEmpty()) return null
        return if (REZERVIRANA.matches(t.substringBefore('.'))) "_$t" else t
    }

    private val V_NAREKOVAJIH = Regex("\"([^\"]*)\"")
    private val STEVEC = Regex("\\((\\d+)/(\\d+)\\)")
    private val BESEDE = Regex("\\s+")

    /**
     * Ime iz `subject` (skupni primeri: nzb_ime): prvi neprazen niz v dvojnih narekovajih, sicer zadnja beseda s piko
     * po odstranitvi »(a/b)« in besede »yEnc«; vedno skozi [varnoIme]. null = ni imena.
     */
    fun imeIzZadeve(zadeva: String): String? {
        for (m in V_NAREKOVAJIH.findAll(zadeva)) {
            val v = m.groupValues[1]
            if (v.isNotEmpty()) return varnoIme(v)
        }
        val besede = zadeva.replace(STEVEC, " ").trim().split(BESEDE).filter { it.isNotEmpty() && it != "yEnc" }
        val zadnja = besede.lastOrNull { '.' in it } ?: return null
        return varnoIme(zadnja)
    }

    /** Stevilo delov iz zadnjega »(a/b)« v zadevi; null = ga ni ali ni v 1..[najvec]. */
    private fun delovIzZadeve(zadeva: String, najvec: Int): Int? =
        STEVEC.findAll(zadeva).lastOrNull()?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in 1..najvec }

    private fun imeElementa(qName: String?, localName: String?): String =
        (qName?.takeIf { it.isNotEmpty() } ?: localName.orEmpty()).substringAfterLast(':')

    private fun samoStevke(s: String?, najvec: Int): Boolean =
        s != null && s.length in 1..najvec && s.all { it in '0'..'9' }

    /** Bralec SAX; meje ustavijo branje takoj (napaka v [napaka], nato SAXException). */
    private class Bralec(private val meje: Meje) : DefaultHandler() {
        val datoteke = ArrayList<Datoteka>()
        var naslov: String? = null
        var imaGeslo = false
        var napaka: Napaka? = null
        private var globina = 0
        private var datotek = 0
        private var segmentov = 0
        private var elementov = 0

        private var vDatoteki = false
        private var zadeva = ""
        private var datum = 0L
        private var skupine = ArrayList<String>()
        private var segmenti = ArrayList<Segment>()
        private var videne = HashSet<Int>()
        private var stevilka = 0
        private var bajtov = 0L
        private var vGlavi = false
        private var meta: String? = null

        private val sb = StringBuilder()
        private var zbiram = false
        private var predolgo = false

        private fun ustavi(n: Napaka): Nothing {
            napaka = n
            throw SAXException(n.name)
        }

        private fun zacni() { sb.setLength(0); zbiram = true; predolgo = false }

        /** Besedilo elementa: dovolj za id, skupino in naslov; daljse je pokvarjeno. */
        private fun besedilo(): String? { zbiram = false; return if (predolgo) null else sb.toString() }

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
            throw SAXException("zunanje entitete niso dovoljene")

        override fun startElement(uri: String?, localName: String?, qName: String?, atr: Attributes) {
            val ime = imeElementa(qName, localName)
            globina++
            if (++elementov > meje.elementov) ustavi(Napaka.PREVELIK)
            if (globina == 1) {
                if (ime != "nzb") ustavi(Napaka.NI_NZB)
                return
            }
            if (globina == 2 && ime == "head") vGlavi = true
            if (globina == 2 && ime == "file") {
                if (++datotek > meje.datotek) ustavi(Napaka.PREVELIK)
                vDatoteki = true
                zadeva = atr.getValue("subject").orEmpty()
                datum = atr.getValue("date")?.takeIf { samoStevke(it, 18) }?.toLong() ?: 0L
                skupine = ArrayList()
                segmenti = ArrayList()
                videne = HashSet()
            } else if (vDatoteki && ime == "segment") {
                if (++segmentov > meje.segmentov) ustavi(Napaka.PREVELIK)
                stevilka = atr.getValue("number")?.takeIf { samoStevke(it, 6) }?.toInt() ?: 0
                bajtov = atr.getValue("bytes")?.takeIf { samoStevke(it, 9) }?.toLong() ?: 0L
                zacni()
            } else if (vDatoteki && ime == "group") {
                zacni()
            } else if (vGlavi && globina == 3 && ime == "meta") {
                meta = atr.getValue("type")
                zacni()
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (!zbiram) return
            if (sb.length + length > 4096) predolgo = true else sb.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val ime = imeElementa(qName, localName)
            if (vDatoteki && ime == "segment" && zbiram) {
                val id = besedilo()?.let { cistId(it) }
                if (id != null && stevilka in 1..meje.segmentovDatoteke && videne.add(stevilka)) {
                    segmenti += Segment(stevilka, bajtov, id)
                }
            } else if (vDatoteki && ime == "group" && zbiram) {
                val g = besedilo()?.trim()
                if (g != null && g.length in 1..NAJVEC_SKUPINE && skupine.size < meje.skupin) skupine += g
            } else if (vGlavi && globina == 3 && ime == "meta" && zbiram) {
                val v = besedilo()
                if (v != null && meta == "title" && naslov == null) {
                    naslov = v.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
                        .let { varnoDolzino(it) }.takeIf { it.isNotEmpty() }
                }
                if (v != null && meta == "password" && v.trim().isNotEmpty()) imaGeslo = true
            } else if (globina == 2 && ime == "file" && vDatoteki) {
                vDatoteki = false
                if (segmenti.isNotEmpty()) {
                    segmenti.sortBy { it.stevilka }
                    val najvecja = maxOf(segmenti.last().stevilka, delovIzZadeve(zadeva, meje.segmentovDatoteke) ?: 0)
                    datoteke += Datoteka(imeIzZadeve(zadeva), datum, skupine, segmenti, najvecja - segmenti.size)
                }
            } else if (globina == 2 && ime == "head") {
                vGlavi = false
            }
            globina--
        }

        /** Najvec [NAJVEC_NASLOVA] kodnih tock. */
        private fun varnoDolzino(s: String): String =
            if (s.codePointCount(0, s.length) <= NAJVEC_NASLOVA) s else s.substring(0, s.offsetByCodePoints(0, NAJVEC_NASLOVA))
    }
}
