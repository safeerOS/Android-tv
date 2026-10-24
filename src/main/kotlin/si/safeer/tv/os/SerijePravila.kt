package si.safeer.tv.os

/**
 * Naloga 130: serije iz katalogov dodatkov z lastnimi id-ji. Dodatki s torrenti (Torrentio ...) poznajo samo id IMDb
 * (epizoda `tt0903747:1:1`); katalog s svojim id-jem (npr. `tmdb:1396`) jih zato ni nikoli vprasal. Ciste odlocitve brez
 * Androida (preizkus: tests/SerijePravilaTest.kt).
 */
object SerijePravila {
    /** Ali je niz id IMDb (`tt` in samo stevke). */
    fun jeImdb(id: String?): Boolean = id != null && id.length > 2 && id.startsWith("tt") && id.drop(2).all { it.isDigit() }

    private val EPIZODA_ID = Regex(""".+:\d+:\d+""")

    /**
     * Naloga 134: id epizode Stremio je »<id serije>:<sezona>:<epizoda>«. Ne »ima dvopicje« - id serije iz kataloga z
     * lastnimi id-ji (»tmdb:1396«) ga ze ima. Isto pravilo kot namizje (media_servers.stremio_je_epizoda, naloga 131).
     */
    fun jeEpizoda(id: String): Boolean = EPIZODA_ID.matches(id)

    /** Id IMDb vnosa: polje `imdb_id` metapodatkov ali id sam; null = ga ni. */
    fun imdbIzMeta(imdbPolje: String?, id: String): String? = imdbPolje?.trim()?.takeIf { jeImdb(it) } ?: id.takeIf { jeImdb(it) }

    /**
     * Id epizode po IMDb (`tt...:sezona:epizoda`), ki ga vprasamo poleg id-ja dodatka. null = ni potreben (id ze je ta) ali
     * ni mogoc (brez IMDb, posebne epizode sezone 0, brez stevilke epizode).
     */
    fun idEpizodePoImdb(imdb: String?, idEpizode: String, sezona: Int, epizoda: Int): String? {
        if (!jeImdb(imdb) || sezona < 1 || epizoda < 1) return null
        val v = "$imdb:$sezona:$epizoda"
        return v.takeIf { it != idEpizode }
    }

    /**
     * Naloga 132: ali epizodo skrijemo s seznama. Samo, ce je NI pri id-ju dodatka ([poDodatku] = false) IN je ni po IMDb
     * ([poImdb] = false) - kadar id po IMDb obstaja ([imaImdb]). Nepreverjeno po IMDb (null) ne skrije: dodatki s
     * torrenti (Torrentio) poznajo samo IMDb in so prej epizode kataloga z lastnimi id-ji napacno skrili.
     */
    fun skrijEpizodo(poDodatku: Boolean?, poImdb: Boolean?, imaImdb: Boolean): Boolean =
        poDodatku == false && (!imaImdb || poImdb == false)

    /** Naloga 132: ali v ozadju preverimo se id po IMDb - samo, ce ga imamo, pri dodatku ni zanesljivo »je« in ga se ne poznamo. */
    fun preveriPoImdb(poDodatku: Boolean?, poImdb: Boolean?, imaImdb: Boolean): Boolean =
        imaImdb && poDodatku != true && poImdb == null

    private val EPIZODA_V_NASLOVU = Regex("""(?<![\p{L}\p{N}])S(\d{1,3})E(\d{1,4})(?![\p{L}\p{N}])""")

    /** Naloga 140: (sezona, epizoda) iz naslova predvajane epizode (»Friends · S1E1 · …«); null = ni epizoda. */
    fun epizodaIzNaslova(naslov: String): Pair<Int, Int>? =
        EPIZODA_V_NASLOVU.find(naslov)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    /**
     * Naloga 140: »Nadaljuj« pri nazadnje predvajani epizodi. Zapis ima id serije (tip »series«), zato je prej odprl seznam
     * sezon namesto epizode. Sezona in epizoda iz zapisa; stari zapisi (brez njiju) iz naslova. null = ni epizoda serije.
     */
    fun epizodaZaNadaljevanje(tip: String?, sezona: Int, epizoda: Int, naslov: String): Pair<Int, Int>? {
        if (tip != "series") return null
        if (sezona >= 0 && epizoda >= 1) return sezona to epizoda
        return epizodaIzNaslova(naslov)
    }

    /** Niz iz JSON-a: org.json vrne JSON null kot niz »null« - to ni ime. */
    fun niz(v: String?): String = if (v == null || v == "null") "" else v
}
