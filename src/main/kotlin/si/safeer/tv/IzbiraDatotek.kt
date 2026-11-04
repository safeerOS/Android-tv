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
     * Vrstni red izbirnikov (TV 4. 11. 2026: Philips nima DocumentsUI, OPEN_DOCUMENT nima prejemnika in OK na gumbu ni
     * naredil nicesar): sistemski SAF, nato upravitelj datotek (GET_CONTENT), nato lasten seznam iz MediaStore, nazadnje
     * okno, da izbirnika ni. SPOROCILO vedno vrne stran null (WebView sicer naslednjega izbora ne odpre).
     */
    enum class Izbirnik { OPEN_DOCUMENT, GET_CONTENT, LASTEN, SPOROCILO }

    /** Naslednji izbirnik, ko [prejsnji] na napravi ni na voljo (null = zacetek). */
    fun naslednji(prejsnji: Izbirnik?): Izbirnik = when (prejsnji) {
        null -> Izbirnik.OPEN_DOCUMENT
        Izbirnik.OPEN_DOCUMENT -> Izbirnik.GET_CONTENT
        Izbirnik.GET_CONTENT -> Izbirnik.LASTEN
        Izbirnik.LASTEN, Izbirnik.SPOROCILO -> Izbirnik.SPOROCILO
    }

    /** Lasten izbirnik se pokaze samo, ce ima kaj ponuditi; prazen seznam = okno »ni izbirnika«. */
    fun poSeznamu(steviloDatotek: Int): Izbirnik = if (steviloDatotek > 0) Izbirnik.LASTEN else Izbirnik.SPOROCILO

    /** Ali datoteka z MIME [mime] ustreza tipom strani ([mimeTipi]): vse datoteke ([VSE]), skupina (image/...) ali tocen tip. */
    fun ustreza(mime: String?, tipi: List<String>): Boolean {
        if (VSE in tipi) return true
        val m = mime?.trim()?.lowercase().orEmpty()
        if (!MIME.matches(m) || m.endsWith("/*")) return false
        return tipi.any { t -> if (t.endsWith("/*")) m.startsWith(t.dropLast(1)) else m == t }
    }

    /** Mape, ki jih lasten izbirnik ne kaze: Android/ (data, obb, media drugih aplikacij) - kot DatotekePravila.smeUrejati. */
    fun skritaMapa(mapa: String): Boolean = mapa.trim().trimStart('/').startsWith("Android/", ignoreCase = true)

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
