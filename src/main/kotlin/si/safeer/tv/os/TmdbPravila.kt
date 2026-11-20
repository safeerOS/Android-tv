package si.safeer.tv.os

/**
 * Safeer katalog (TMDB): vgrajen »dodatek« Stremio brez strežnika (lastnik 20. 11. 2026). Zahteve na psevdo-naslov
 * [BASE] obdela aplikacija sama in jih prevede v klice TMDB; naslov ne obstaja v omrezju (.invalid).
 * Tu so samo cista pravila (brez Androida in omrezja); preizkus: tests/TmdbPravilaTest.kt.
 * Pogoji TMDB: navedba vira, brez rabe z UI/AI (Lina, agent), predpomnjenje najvec 6 mesecev (pri nas ure).
 */
object TmdbPravila {
    const val BASE = "https://tmdb.safeer.invalid"
    const val STRAN = 20
    const val NAJVEC_STRANI = 25

    /** Obvezna navedba po pogojih TMDB (v O aplikaciji / pravno okno). */
    const val NAVEDBA = "This product uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise approved by TMDB."

    fun jeNas(url: String) = url.startsWith("$BASE/") || url == BASE

    class Zahteva(val tip: String, val katalog: String, val skip: Int)

    /** `{BASE}/catalog/{tip}/{id}[/skip=N].json` -> zahteva; vse drugo null. */
    fun zahteva(url: String): Zahteva? {
        if (!jeNas(url)) return null
        val pot = url.removePrefix(BASE).substringBefore('?').removeSuffix(".json")
        val d = pot.split('/').filter { it.isNotEmpty() }
        if (d.size < 3 || d[0] != "catalog") return null
        if (d.size > 4) return null
        val skip = if (d.size == 4) Regex("^skip=(\\d{1,5})$").find(d[3])?.groupValues?.get(1)?.toIntOrNull() ?: return null else 0
        return Zahteva(d[1], d[2], skip)
    }

    fun jeManifest(url: String) = jeNas(url) && url.substringBefore('?') == "$BASE/manifest.json"

    /** Pot TMDB za katalog; null = neznan. */
    fun pot(tip: String, katalog: String): String? = when (tip to katalog) {
        "movie" to "tmdb.popularno" -> "movie/popular"
        "movie" to "tmdb.najbolje" -> "movie/top_rated"
        "movie" to "tmdb.novo" -> "movie/now_playing"
        "series" to "tmdb.popularno" -> "tv/popular"
        "series" to "tmdb.najbolje" -> "tv/top_rated"
        "series" to "tmdb.novo" -> "tv/on_the_air"
        else -> null
    }

    /**
     * Stran TMDB (od 1) iz Stremio `skip`, ko poznamo meje: `meje[i]` = skip, pri katerem se zacne stran i + 2 (stran da manj
     * vnosov, ker vnose brez IMDb id izpustimo, zato skip ne raste za 20). Neznan skip: ocena skip / [STRAN].
     */
    fun stran(skip: Int, meje: List<Int>): Int {
        if (skip <= 0) return 1
        val i = meje.indexOf(skip)
        return (if (i >= 0) i + 2 else stran(skip)).coerceAtMost(NAJVEC_STRANI)
    }

    /** Stran TMDB (od 1) iz Stremio `skip`. */
    fun stran(skip: Int): Int = (skip.coerceAtLeast(0) / STRAN + 1).coerceAtMost(NAJVEC_STRANI)

    private val POT_SLIKE = Regex("^/[A-Za-z0-9_./-]{1,100}$")

    fun slika(pot: String?, velikost: String): String =
        if (pot != null && POT_SLIKE.matches(pot) && ".." !in pot) "https://image.tmdb.org/t/p/$velikost$pot" else ""

    private val IMDB = Regex("^tt\\d{5,10}$")
    fun veljavenImdb(s: String?): Boolean = s != null && IMDB.matches(s)

    /** Leto iz datuma `YYYY-MM-DD`; prazno, ce ni veljaven. */
    fun leto(datum: String?): String = datum?.takeIf { Regex("^\\d{4}(-\\d{2}-\\d{2})?$").matches(it) }?.take(4).orEmpty()

    /** Jezik za TMDB iz oznake naprave (sl -> sl-SI, sicer oznaka; prazno -> en-US). */
    fun jezik(oznaka: String): String = when {
        oznaka.isBlank() -> "en-US"
        oznaka.equals("sl", true) -> "sl-SI"
        Regex("^[a-z]{2}(-[A-Z]{2})?$").matches(oznaka) -> oznaka
        else -> "en-US"
    }
}
