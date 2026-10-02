package si.safeer.tv.os

import si.safeer.tv.R

/**
 * Zvrsti filmov in serij za mrezo Filmi | Serije: en kljuc, prevedeno ime, ime moznosti v katalogih dodatkov
 * (Stremio `genre`) in vzorec za prepoznavo iz metapodatkov vsebine. Uporabnik izbere zvrst, od kod vsebina
 * pride, ga ne zanima (lastnik, 2. 10. 2026).
 */
object Zvrsti {
    class Zvrst(val kljuc: String, val ime: Int, val katalog: List<String>, private val vzorec: Regex, private val staro: String = "") {
        /** Ali vsebina sodi v zvrst: po strukturiranih zvrsteh, sicer po oceni iz naslova (spletni viri brez metapodatkov). */
        fun ustreza(s: Jamendo.Skladba): Boolean =
            if (s.genres.isNotEmpty()) s.genres.any { vzorec.containsMatchIn(it.lowercase()) }
            else staro.isNotEmpty() && SpletniVir.zvrstVsebine(s) == staro
    }

    val VSE = listOf(
        Zvrst("akcija", R.string.os_zvrst_akcija, listOf("Action", "Action & Adventure"), Regex("action|akcij"), "Akcija"),
        Zvrst("komedija", R.string.os_zvrst_komedija, listOf("Comedy"), Regex("comedy|komed"), "Komedija"),
        Zvrst("drama", R.string.os_zvrst_drama, listOf("Drama"), Regex("drama"), "Drama"),
        Zvrst("triler", R.string.os_zvrst_triler, listOf("Thriller"), Regex("thriller|triler")),
        Zvrst("grozljivke", R.string.os_zvrst_grozljivke, listOf("Horror"), Regex("horror|grozljiv"), "Grozljivke"),
        Zvrst("kriminalke", R.string.os_zvrst_kriminalke, listOf("Crime"), Regex("crime|kriminal"), "Kriminalke"),
        Zvrst("zf", R.string.os_zvrst_zf, listOf("Sci-Fi", "Science Fiction", "Sci-Fi & Fantasy"), Regex("sci-?fi|science fiction|znanstven")),
        Zvrst("fantazija", R.string.os_zvrst_fantazija, listOf("Fantasy"), Regex("fantasy|fantazij|fantast"), "Fantastika"),
        Zvrst("pustolovski", R.string.os_zvrst_pustolovski, listOf("Adventure"), Regex("adventure|pustolov")),
        Zvrst("animacija", R.string.os_zvrst_animacija, listOf("Animation"), Regex("animation|anime|animacij|animiran"), "Animacija"),
        Zvrst("druzinski", R.string.os_zvrst_druzinski, listOf("Family", "Kids"), Regex("family|druzin|družin|kids|children"), "Druzinski"),
        Zvrst("romantika", R.string.os_zvrst_romantika, listOf("Romance"), Regex("romance|romanti"), "Romantika"),
        Zvrst("dokumentarci", R.string.os_zvrst_dokumentarci, listOf("Documentary"), Regex("documentary|dokument"), "Dokumentarci"),
    )

    fun poKljucu(kljuc: String): Zvrst? = VSE.firstOrNull { it.kljuc == kljuc }

    /** Moznost kataloga (npr. "Comedy") za zvrst, ce jo katalog ponuja; brez razlikovanja velikih crk. */
    fun moznost(z: Zvrst, moznosti: List<String>): String? = moznosti.firstOrNull { m -> z.katalog.any { it.equals(m, ignoreCase = true) } }
}
