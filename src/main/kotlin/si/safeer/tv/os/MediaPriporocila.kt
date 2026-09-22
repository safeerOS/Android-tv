package si.safeer.tv.os

import android.content.Context
import java.text.Normalizer
import kotlin.math.min

/**
 * Zasebni, lokalni priporocilni sloj Safeer Media.
 *
 * Ne ustvarja uporabniskega profila na strezniku in ne posilja zgodovine nikamor. Signale vzame
 * samo iz podatkov, ki jih Safeer ze hrani na tej napravi: Nadaljuj gledanje, priljubljene in
 * nedavno predvajano. Kandidati vedno pridejo iz uporabnikovih trenutnih virov.
 */
object MediaPriporocila {
    data class Izid(val zate: List<Jamendo.Skladba>, val razlog: String = "")

    private fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun identiteta(s: Jamendo.Skladba): String = when {
        s.imdbId.isNotBlank() -> "imdb:${s.imdbId.lowercase()}"
        s.tmdbId.isNotBlank() -> "tmdb:${s.tmdbId.lowercase()}:${SpletniVir.vrstaVsebine(s).orEmpty()}"
        else -> "${SpletniVir.vrstaVsebine(s).orEmpty()}:${norm(s.naslov)}:${s.year.takeIf { it > 0 } ?: ""}"
    }

    /** Kandidatom iz uporabnikovih virov doloci vrstni red. Brez zunanjega API-ja. */
    fun uredi(c: Context, kandidati: List<Jamendo.Skladba>, meja: Int = 24): Izid {
        if (kandidati.isEmpty()) return Izid(emptyList())

        val napredek = MediaNapredek.seznam(c)
        val priljubljene = MedijskiViri.priljubljene(c)
        val nedavno = MedijskiViri.nedavno(c)
        val ogledano = (napredek.map { it.skladba } + priljubljene + nedavno).filter { it.video }
        if (ogledano.isEmpty()) return Izid(kandidati.distinctBy(::identiteta).take(meja))

        val zvrsti = ogledano.mapNotNull { SpletniVir.zvrstVsebine(it) }
            .groupingBy { it }.eachCount()
        val tipi = ogledano.mapNotNull { SpletniVir.vrstaVsebine(it) }
            .groupingBy { it }.eachCount()
        val viri = ogledano.map { norm(it.izvajalec) }.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount()
        val ze = ogledano.map(::identiteta).toSet()

        fun ocena(s: Jamendo.Skladba): Int {
            var o = 0
            SpletniVir.zvrstVsebine(s)?.let { o += min(4, zvrsti[it] ?: 0) * 5 }
            SpletniVir.vrstaVsebine(s)?.let { o += min(4, tipi[it] ?: 0) * 2 }
            val vir = norm(s.izvajalec)
            if (vir.isNotBlank()) o += min(3, viri[vir] ?: 0) * 2
            // Nova vsebina ima prednost pred ze gledano; nadaljevanje ima svojo loceno polico.
            if (identiteta(s) in ze) o -= 30
            // Strukturirani podatki so zanesljivejsi od ugibanja in pomagajo pri dobrem katalogu.
            if (s.genres.isNotEmpty()) o += 2
            if (s.imdbId.isNotBlank() || s.tmdbId.isNotBlank()) o += 1
            return o
        }

        val urejeni = kandidati.distinctBy(::identiteta).withIndex()
            .sortedWith(compareByDescending<IndexedValue<Jamendo.Skladba>> { ocena(it.value) }.thenBy { it.index })
            .map { it.value }.take(meja)

        val najZvrst = zvrsti.maxByOrNull { it.value }?.key.orEmpty()
        return Izid(urejeni, najZvrst)
    }
}
