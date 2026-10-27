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
    const val NAJVEC_SKLADB = 60
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

    /** Velikost izdaje za podnaslov kartice. */
    fun velikost(bajtov: Long): String = when {
        bajtov <= 0 -> ""
        bajtov >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", bajtov / (1L shl 30).toDouble())
        else -> "${bajtov / (1L shl 20)} MB"
    }
}
