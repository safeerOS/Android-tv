package si.safeer.tv.os

import java.net.URLEncoder

/**
 * Glasba z Useneta v Medijskem centru (lastnik 27. 10. 2026; naloga Grok »naloga-glasba-usenet-android.md«, dopolnjena):
 * iskanje pri uporabnikovem indeksatorju (Newznab kategorija 3000), album iz NZB kot vrsta skladb prek lastnega pogona
 * ([UsenetLastni.predvajajAlbum]) v obstojeci predvajalnik glasbe. Ciste odlocitve (tests/UsenetGlasbaPravilaTest.kt).
 * Aplikacija ne vsebuje nobenih virov: indeksator in ponudnika vpise uporabnik sam.
 */
object UsenetGlasbaPravila {
    /** Newznab: 3000 zvok (3010 MP3, 3030 zvocne knjige, 3040 lossless/FLAC, 3050 ostalo) - vse podkategorije hkrati. */
    const val KATEGORIJA = "3000"
    /** Najvec skladb iz ene izdaje (diskografija z 2000 datotekami ne odpre 2000 tokov). */
    // Lastnik 27. 10. 2026: skladbe se pripravijo sproti (najvec 5 odprtih) - meja je samo varovalo velikih izdaj.
    const val NAJVEC_SKLADB = 500
    /** Zvocne datoteke, ki jih predvajalnik (Media3) zna brez dodatnih kodekov. */
    val ZVOK = listOf(".flac", ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Iskanje glasbe pri indeksatorju; null = prekratka beseda ali indeksator ni vpisan (brez zahteve). */
    fun naslovIskanja(osnova: String, kljuc: String, beseda: String): String? {
        val q = beseda.trim()
        if (q.length < 2 || osnova.isBlank() || kljuc.isBlank()) return null
        return "${osnova.trimEnd('/')}/api?apikey=${enc(kljuc)}&t=search&cat=$KATEGORIJA&q=${enc(q)}&limit=50&extended=1"
    }

    /**
     * Lastnik 27. 10. 2026 (»brskanje kot pri YouTubu, po izvajalcu ali skladbi«): iskanje skladbe (Newznab t=music&track=,
     * vecina indeksatorjev ga podpira) tece hkrati z iskanjem izdaj (izvajalec, album); zadetka se zdruzita.
     */
    fun naslovIskanjaSkladbe(osnova: String, kljuc: String, beseda: String): String? {
        val q = beseda.trim()
        if (q.length < 2 || osnova.isBlank() || kljuc.isBlank()) return null
        return "${osnova.trimEnd('/')}/api?apikey=${enc(kljuc)}&t=music&cat=$KATEGORIJA&track=${enc(q)}&limit=50&extended=1"
    }

    /** Zdruzeni zadetki obeh iskanj: vsaka izdaja (povezava NZB) enkrat, najprej zadetki izdaj. */
    fun <T> zdruzi(izdaje: List<T>, skladbe: List<T>, kljuc: (T) -> String): List<T> = (izdaje + skladbe).distinctBy(kljuc)

    /**
     * Kje zacne predvajanje albuma: pri skladbi, ki vsebuje vse besede iskanja (iskal je skladbo); sicer pri prvi.
     * Primerjava brez velikih crk in locil.
     */
    fun zacetnaSkladba(imenaSkladb: List<String>, beseda: String): Int {
        fun cisto(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val besede = cisto(beseda).split(' ').filter { it.isNotBlank() }
        if (besede.isEmpty()) return 0
        val i = imenaSkladb.indexOfFirst { ime -> val c = " " + cisto(ime) + " "; besede.all { c.contains(it) } }
        return if (i >= 0) i else 0
    }

    fun jeZvok(ime: String?): Boolean {
        val m = ime?.lowercase()?.trim() ?: return false
        return ZVOK.any { m.endsWith(it) }
    }

    /** Vzorec je samostojna beseda v imenu (»Album.Sample.flac«), kot pri filmih (UsenetBralnik.jeVzorec). */
    private val VZOREC = Regex("(^|[._ \\-\\[(])(sample|vzorec)([._ \\-\\])]|$)", RegexOption.IGNORE_CASE)
    private val ZACETNA_STEVILKA = Regex("^\\s*(\\d{1,3})(?=\\D)")

    /** Stevilka skladbe na zacetku imena (»03 - Ime.flac« -> 3); brez stevilke na konec. */
    fun stevilka(ime: String?): Int = ime?.let { ZACETNA_STEVILKA.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: Int.MAX_VALUE

    /**
     * Skladbe izdaje: indeksi zvocnih datotek v NZB (brez vzorcev), po stevilki skladbe in nato po imenu, najvec
     * [NAJVEC_SKLADB]. Prazno = izdaja nima golih zvocnih datotek (npr. album v RAR - druga faza).
     */
    fun skladbe(imena: List<String?>): List<Int> =
        imena.withIndex().filter { (_, ime) -> jeZvok(ime) && !VZOREC.containsMatchIn(ime ?: "") }
            .sortedWith(compareBy({ stevilka(it.value) }, { it.value?.lowercase() ?: "" }))
            .map { it.index }.take(NAJVEC_SKLADB)

    /** Ime skladbe za predvajalnik: brez koncnice, zacetne stevilke in locil, podcrtaji kot presledki. */
    fun imeSkladbe(ime: String?): String {
        var s = (ime ?: "").substringAfterLast('/').substringAfterLast('\\')
        val pika = s.lastIndexOf('.')
        if (pika > 0) s = s.substring(0, pika)
        s = s.replace('_', ' ').replace(Regex("^\\s*\\d{1,3}\\s*[-.)_ ]+\\s*"), "").trim()
        return s.ifBlank { "Skladba" }
    }

    private val MALE = setOf("a", "an", "the", "and", "or", "of", "for", "in", "on", "at", "to", "by", "with", "from", "vs")

    /** Velike zacetnice samo za ime, ki je v celoti z malimi crkami (pravilnih zapisov, npr. »AC/DC«, »iPhone«, ne spreminjamo). */
    fun zacetnice(t: String): String {
        if (t.any { it.isUpperCase() }) return t
        val besede = t.split(' ')
        return besede.mapIndexed { i, b ->
            if (b.isEmpty() || (i in 1 until besede.size - 1 && b in MALE)) b else b.replaceFirstChar { it.titlecase() }
        }.joinToString(" ")
    }

    private fun kljuc(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /**
     * Lastnik 27. 10. 2026: (naslov, izvajalec) za vsako skladbo albuma z Useneta. Imena datotek so pogosto »izvajalec-naslov
     * album« z malimi crkami in podcrtaji. Predpona pred »-«, ki jo ima vec kot pol skladb, je izvajalec (svoje polje, z
     * velikimi zacetnicami); skupen zakljucek za dvojnim presledkom ali v oklepaju (ime albuma) se umakne; naslov z malimi
     * crkami dobi velike zacetnice. Odloca cel album - naslov z vezajem (»Re-Arrange«) ene skladbe se ne razbije.
     * [izvajalecIzdaje] (iz naslova izdaje) ima prednost, ce ga poznamo.
     */
    fun lepeSkladbe(imena: List<String?>, izvajalecIzdaje: String = ""): List<Pair<String, String>> {
        val osnove = imena.map { imeSkladbe(it).replace(Regex("[\\t ]{3,}"), "  ").trim() }
        val pol = osnove.size / 2
        // Izvajalec: predpona do prvega »-« (z ali brez presledkov), enaka pri vec kot pol skladb.
        val predpone = osnove.map { o -> o.indexOf('-').takeIf { it > 0 }?.let { kljuc(o.substring(0, it)) }.orEmpty() }
        val izvKljuc = predpone.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().maxByOrNull { it.value }
            ?.takeIf { it.value > pol || (osnove.size == 1 && it.key == kljuc(izvajalecIzdaje)) }?.key
        val brezIzvajalca = osnove.mapIndexed { i, o ->
            if (izvKljuc != null && predpone[i] == izvKljuc) o.substring(o.indexOf('-') + 1).trim() else o
        }
        // Album: zakljucek za dvojnim presledkom ali v zadnjem oklepaju, enak pri vec kot pol skladb.
        fun zakljucek(t: String): String {
            val m = Regex("\\(([^()]+)\\)\\s*$").find(t)
            if (m != null) return m.groupValues[1]
            val d = t.lastIndexOf("  ")
            return if (d > 0) t.substring(d) else ""
        }
        val zakljucki = brezIzvajalca.map { kljuc(zakljucek(it)) }
        val albKljuc = zakljucki.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().maxByOrNull { it.value }
            ?.takeIf { it.value > pol && osnove.size > 1 }?.key
        val izvajalec = izvajalecIzdaje.trim().ifBlank {
            izvKljuc?.let { k -> osnove.first { o -> o.indexOf('-') > 0 && kljuc(o.substring(0, o.indexOf('-'))) == k }
                .let { zacetnice(it.substring(0, it.indexOf('-')).trim()) } }.orEmpty()
        }
        return brezIzvajalca.mapIndexed { i, t ->
            var n = t
            if (albKljuc != null && zakljucki[i] == albKljuc) {
                val z = zakljucek(n)
                n = n.substring(0, n.length - z.length).trimEnd().removeSuffix("(").trimEnd()
            }
            n = n.replace(Regex("\\s+"), " ").trim().trim('-', ' ')
            (zacetnice(n).ifBlank { imeSkladbe(imena[i]) }) to izvajalec
        }
    }

    /** Priljubljeni album: ista izdaja ne glede na velike crke, pike, podcrtaje in presledke. */
    fun istiAlbum(a: String, b: String): Boolean = kljuc(lepNaslov(a)).let { it.isNotEmpty() && it == kljuc(lepNaslov(b)) }

    /**
     * Shranjen album med zadetki novega iskanja: najprej ista izdaja z isto velikostjo (± 1 %), nato ista izdaja; -1 = ni
     * je vec pri indeksatorju.
     */
    fun izberiShranjenega(zadetki: List<Pair<String, Long>>, naslov: String, velikost: Long): Int {
        val isti = zadetki.indices.filter { istiAlbum(zadetki[it].first, naslov) }
        if (isti.isEmpty()) return -1
        if (velikost > 0) isti.firstOrNull { kotiVelikost(zadetki[it].second, velikost) }?.let { return it }
        return isti.first()
    }

    /**
     * Lastnik 28. 10. 2026: druge izdaje istega albuma kot [izbran] (za ponoven poskus pri »luknja«), brez neuspelih;
     * najvec [najvec] (vsaka je en prenos NZB).
     */
    fun drugeIzdaje(naslovi: List<String>, izbran: String, najvec: Int, neuspel: (Int) -> Boolean): List<Int> =
        jedroAlbuma(izbran).let { j ->
            if (j.isEmpty()) emptyList()
            else naslovi.indices.filter { naslovi[it] != izbran && jedroAlbuma(naslovi[it]) == j && !neuspel(it) }.take(najvec)
        }

    /** Izvajalec in album brez letnice, formata in skupine (»Linkin Park - Meteora (2003) [FLAC]« -> »linkin park meteora«). */
    fun jedroAlbuma(naslovIzdaje: String): String {
        val besede = kljuc(lepNaslov(naslovIzdaje)).split(' ').filter { it.isNotEmpty() }
        val konec = besede.indexOfFirst { Regex("(19|20)\\d\\d").matches(it) || it in OZNAKE_FORMATA }
        val jedro = if (konec >= 0) besede.take(konec) else besede
        return if (jedro.size >= 2) jedro.joinToString(" ") else ""
    }

    private val OZNAKE_FORMATA = setOf("flac", "mp3", "aac", "alac", "ogg", "opus", "wav", "320", "256", "v0", "web", "cd", "cdrip",
        "vinyl", "24bit", "16bit", "lossless", "hires", "repost")

    private fun kotiVelikost(a: Long, b: Long) = a > 0 && kotlin.math.abs(a - b) <= maxOf(a, b) / 100

    /** Izvajalec iz naslova izdaje »Izvajalec - Album (Leto) [FLAC]«; brez » - « prazno. */
    fun izvajalec(naslovIzdaje: String): String {
        val n = lepNaslov(naslovIzdaje)
        val i = n.indexOf(" - ")
        return if (i > 0) n.substring(0, i).trim() else ""
    }

    /** Naslov izdaje za kartico: imena brez presledkov (»Izvajalec.Album.2020.FLAC«) dobijo presledke. */
    fun lepNaslov(naslovIzdaje: String): String {
        val t = naslovIzdaje.trim()
        return if (' ' in t) t else t.replace('.', ' ').replace('_', ' ').trim()
    }

    /** Lastnik 28. 10. 2026: kartica izdaje - izvajalec, album, leto in oblika iz imena izdaje. */
    data class OpisIzdaje(val izvajalec: String, val album: String, val leto: String, val oblika: String)

    private val LETO = Regex("(?<![0-9])(19|20)[0-9]{2}(?![0-9])")
    private val OBLIKA = Regex("(?i)(?<![a-z0-9])(flac|mp3|aac|alac|ogg|opus|wav|dsd|24bit|hi-?res)(?![a-z0-9])")
    private val OZNAKE = setOf("web", "cd", "cdrip", "vinyl", "lp", "320", "256", "192", "v0", "v2", "16bit", "lossless", "repost", "proper")

    fun opisIzdaje(naslovIzdaje: String): OpisIzdaje {
        val n = lepNaslov(naslovIzdaje)
        val leto = LETO.find(n)?.value.orEmpty()
        val oblika = OBLIKA.find(n)?.value?.uppercase()?.replace("HI-RES", "HIRES").orEmpty()
        val deli = (if (" - " in n) n.split(" - ") else n.split("-")).map { it.trim() }.filter { it.isNotEmpty() }
        val izvajalec = if (deli.size > 1) deli.first() else ""
        val ostanek = (if (deli.size > 1) deli.drop(1) else deli).joinToString(" ")
            .replace(Regex("[(\\[][^)\\]]*[)\\]]"), " ")
        val besede = ostanek.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val konec = besede.indexOfFirst { LETO.matches(it) || OBLIKA.matches(it) || it.lowercase() in OZNAKE }
        val album = (if (konec >= 0) besede.take(konec) else besede).joinToString(" ").trim(' ', '-', '_', '.')
        return OpisIzdaje(izvajalec, album.ifEmpty { n }, leto, oblika)
    }

    /** (naslov, podnaslov) kartice: izvajalec vrstico nad albumom (lastnik 28. 10. 2026), spodaj leto · oblika · velikost. */
    fun kartica(naslovIzdaje: String, velikost: Long): Pair<String, String> {
        val o = opisIzdaje(naslovIzdaje)
        val naslov = if (o.izvajalec.isNotEmpty()) o.izvajalec + "\n" + o.album else o.album
        return naslov to listOf(o.leto, o.oblika, velikost(velikost)).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** Velikost izdaje za podnaslov kartice. */
    fun velikost(bajtov: Long): String = when {
        bajtov <= 0 -> ""
        bajtov >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", bajtov / (1L shl 30).toDouble())
        else -> "${bajtov / (1L shl 20)} MB"
    }
}
