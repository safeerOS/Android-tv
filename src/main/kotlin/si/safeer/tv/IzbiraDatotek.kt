package si.safeer.tv

/**
 * Nalaganje datotek na strani (<input type=file>) prek sistemskega izbirnika (ACTION_OPEN_DOCUMENT, SAF - brez
 * dovoljenja za shrambo). Prenos s telefona (bf12168, 4. 11. 2026). Brez Androida, preizkus v tests/IzbiraDatotekTest.kt.
 *
 * Do 4. 11. 2026 glavni brskalnik TV ni imel onShowFileChooser: WebView je izbiro tiho preklical in nalaganje ni delalo.
 */
object IzbiraDatotek {
    private val MIME = Regex("^[a-z0-9][a-z0-9!#$&^_.+-]*/(\\*|[a-z0-9][a-z0-9!#$&^_.+-]*)$")
    private val CONTENT = Regex("^content://([^/?#]+)", RegexOption.IGNORE_CASE)
    const val VSE = "*/*"
    /** Vsi nasi paketi in ponudniki (si.safeer.tv, si.safeer.os, ...fileprovider): stran nikoli ne dobi nasih datotek. */
    const val LASTNA_DRUZINA = "si.safeer"

    /**
     * Atribut accept strani (".pdf", "image/png,.jpg" ali vse slike) v MIME tipe za izbirnik. Ce kakega vnosa ne
     * razumemo (neznana koncnica), ponudimo vse datoteke: uporabnik bi sicer prave datoteke sploh ne videl.
     */
    fun mimeTipi(accept: Array<String>?, poKoncnici: (String) -> String?): List<String> {
        val tipi = LinkedHashSet<String>()
        var neznano = false
        accept.orEmpty().flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.forEach { a ->
            when {
                a == VSE -> neznano = true
                a.startsWith(".") && a.length > 1 -> poKoncnici(a.substring(1))?.lowercase()?.takeIf { MIME.matches(it) }
                    ?.let { tipi += it } ?: run { neznano = true }
                MIME.matches(a) -> tipi += a
                else -> neznano = true
            }
        }
        return if (tipi.isEmpty() || neznano) listOf(VSE) else tipi.toList()
    }

    /**
     * Kar gre strani: samo content:// URI-ji, ki jih je vrnil sistemski izbirnik - nikoli file:// in nikoli nas
     * lastni ponudnik (lastnaOznaka in vse pod njo), ki bi stran lahko spustil do zasebnih datotek brskalnika.
     * %-kodirana avtoriteta se steje za naso (kot SpletVarnostPravila.lastnaAvtoriteta).
     */
    fun sprejmi(uriji: List<String?>, lastnaOznaka: String, vec: Boolean): List<String> {
        val lastna = lastnaOznaka.lowercase()
        val ok = uriji.filterNotNull().distinct().filter { u ->
            val m = CONTENT.find(u) ?: return@filter false
            val oznaka = m.groupValues[1].lowercase().substringAfterLast('@').substringBefore(':')
            oznaka.isNotEmpty() && '%' !in oznaka && oznaka != lastna && !oznaka.startsWith("$lastna.")
        }
        return if (vec) ok else ok.take(1)
    }
}
