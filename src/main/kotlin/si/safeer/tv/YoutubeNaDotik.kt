package si.safeer.tv

/**
 * Naprave z dotikom (telefon, tablica): youtube.com/tv je vmesnik samo za televizorje. Na tablici
 * YouTube najprej ~10 s kaze »Preusmerjeni boste na spletno mesto youtube.com« in sele nato odpre
 * pravo stran. Uporabnik je YouTube izbral sam, zato ga odpremo takoj v pravi obliki (Matej, 28. 9. 2026).
 */
object YoutubeNaDotik {
    private val VIDEO = Regex("[#&?/]watch\\?(?:[^#]*&)?v=([\\w-]{6,})")
    private val ISKANJE = Regex("[#&?/]search\\?(?:[^#]*&)?q=([^&#]+)")

    fun prevedi(url: String): String {
        val lower = url.lowercase()
        val i = lower.indexOf("youtube.com/tv")
        if (i < 0) return url
        // samo prava pot /tv (ne /tvshows ipd.)
        val za = lower.getOrNull(i + "youtube.com/tv".length)
        if (za != null && za != '/' && za != '#' && za != '?') return url
        VIDEO.find(url)?.let { return "https://m.youtube.com/watch?v=" + it.groupValues[1] }
        ISKANJE.find(url)?.let { return "https://m.youtube.com/results?search_query=" + it.groupValues[1] }
        return "https://m.youtube.com/"
    }
}
