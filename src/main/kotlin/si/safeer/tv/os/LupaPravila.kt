package si.safeer.tv.os

import si.safeer.tv.cast.JsonLahki
import java.text.Normalizer

/**
 * Naloga 42 E (27. 10. 2026): lupa kot na racunalniku in glasovni ukazi. ENA razclemba ukazov za lupo in glas (prej je
 * »… na TV« razclenjeval PrikaziNa po vrsti naprave): »<kaj> na <napravo>« - ciljna naprava PO IMENU v ozjem krogu
 * (»Sintel na Dnevna soba«), sicer po vrsti (»Sintel na TV«, »weather on my phone«); »vreme« (»vreme v Mariboru«,
 * »vreme na TV«); »isci v spletu …«; spletni naslov; vse drugo je iskanje. Jeziki: sl, en, de, es, fr, it.
 * Ciste funkcije (tests/LupaPravilaTest.kt).
 */
object LupaPravila {
    const val ISCI = "isci"
    const val PRIKAZI_NA = "prikazi_na"
    const val VREME = "vreme"
    const val ISCI_SPLET = "isci_splet"
    const val SPLET = "splet"

    /**
     * [naprava] = id naprave v ozjem krogu, izbrane po imenu; [vrsteNaprav] = platforme, ce je cilj vrsta (»na TV«);
     * oboje prazno = ta naprava.
     */
    data class Ukaz(val vrsta: String, val vsebina: String, val naprava: String = "", val vrsteNaprav: List<String> = emptyList())

    private val PC = listOf("windows", "linux", "mac")

    /** Vrsta naprave (normalizirane besede, brez predloga) -> platforme. Isto kot PrikaziNa/delovna.js ciljMedija. */
    private val VRSTE: Map<String, List<String>> = buildMap {
        for (b in listOf("tv", "televiziji", "televizorju", "televizor", "televizijo", "television", "telly", "fernseher", "fernsehen",
            "tele", "televisao", "televisor", "televisione")) put(b, listOf("tv"))
        for (b in listOf("racunalniku", "racunalnik", "pc", "prenosniku", "computer", "laptop", "rechner", "ordenador", "computador",
            "computadora", "portatil", "lordinateur", "ordinateur")) put(b, PC)
        for (b in listOf("tablici", "tablico", "tablica", "tablet", "tableta", "tablette")) put(b, listOf("tablet"))
        for (b in listOf("telefonu", "telefon", "mobitelu", "mobitel", "phone", "mobile", "handy", "smartphone", "movil", "telefono",
            "celular", "telemovel", "telefone", "telephone", "portable", "cellulare")) put(b, listOf("phone"))
    }

    /** Predlogi pred ciljem (»na«, »on«, »auf« …) in cleni ali svojilni zaimki, ki lahko sledijo. */
    private val PREDLOGI = setOf("na", "v", "on", "to", "auf", "im", "am", "en", "sur", "sulla", "sul", "su", "in", "no", "nella", "nel")
    private val CLENI = setOf("the", "my", "this", "dem", "den", "meinem", "meinen", "la", "el", "mi", "le", "ma", "mon", "il", "lo", "mio", "mia", "o", "a")

    private val GLAGOLI = setOf("prikazi", "pokazi", "predvajaj", "odpri", "zavrti", "show", "play", "open", "put", "zeige",
        "spiele", "offne", "muestra", "reproduce", "pon", "abre", "mostra", "reproduz", "toca", "passa",
        "montre", "joue", "ouvre", "lance", "riproduci", "apri", "metti")

    private val VREME_BESEDE = setOf("vreme", "vremenska", "napoved", "weather", "forecast", "wetter", "tiempo", "clima", "meteo", "tempo")

    /** Zacetki »isci v spletu« (normalizirane besede); ostanek je poizvedba. */
    private val SPLET_ZACETKI = listOf(
        listOf("isci", "v", "spletu"), listOf("isci", "na", "spletu"), listOf("poisci", "na", "spletu"), listOf("isci", "splet"),
        listOf("search", "the", "web", "for"), listOf("search", "the", "web"), listOf("search", "web", "for"), listOf("web", "search"),
        listOf("im", "web", "suchen"), listOf("websuche"), listOf("suche", "im", "web", "nach"), listOf("suche", "im", "web"),
        listOf("buscar", "en", "la", "web"), listOf("busca", "en", "la", "web"), listOf("busca", "en", "internet"),
        listOf("chercher", "sur", "le", "web"), listOf("cherche", "sur", "le", "web"), listOf("recherche", "web"),
        listOf("cerca", "sul", "web"), listOf("cerca", "in", "rete"),
    )

    fun kljuc(beseda: String): String =
        Normalizer.normalize(beseda.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .replace("đ", "d").replace("ł", "l").replace("ø", "o").replace("ß", "ss")
            .replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun besede(niz: String): List<String> = niz.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

    /** Konec [k] (normalizirane besede) je ime naprave [ime]? Vrne stevilo besed cilja s predlogom in clenom, sicer 0. */
    private fun konecZImenom(k: List<String>, ime: List<String>): Int {
        if (ime.isEmpty() || k.size < ime.size + 2) return 0
        if (k.subList(k.size - ime.size, k.size) != ime) return 0
        var i = k.size - ime.size - 1
        if (k[i] in CLENI && i > 0 && k[i - 1] in PREDLOGI) i--
        return if (k[i] in PREDLOGI && i >= 1) k.size - i else 0
    }

    private fun brezGlagola(b: List<String>): List<String> = if (b.isNotEmpty() && kljuc(b[0]) in GLAGOLI) b.drop(1) else b

    /**
     * Razclemba vnosa lupe ali glasu. [naprave] = (id, ime) naprav v OZJEM KROGU (brez te naprave); ime ima prednost pred
     * vrsto (»na TV« je naprava z imenom »TV«, ce taka obstaja). Daljse ime ima prednost (»Dnevna soba TV« pred »TV«).
     */
    fun razcleni(niz: String, naprave: List<Pair<String, String>> = emptyList()): Ukaz {
        // Besede brez crk in stevk (»–«, »…«) odpadejo, da se b in k (normalizirane) ujemata po mestih.
        val b = besede(niz).filter { kljuc(it).isNotEmpty() }
        val k = b.map { kljuc(it) }
        if (b.isEmpty()) return Ukaz(ISCI, "")
        // »isci v spletu …«
        for (z in SPLET_ZACETKI) if (k.size > z.size && k.subList(0, z.size) == z) return Ukaz(ISCI_SPLET, b.drop(z.size).joinToString(" "))
        // Cilj po imenu naprave v krogu, nato po vrsti.
        var naprava = ""
        var vrste: List<String> = emptyList()
        var dolzinaCilja = 0
        for ((id, ime) in naprave.sortedByDescending { besede(it.second).size }) {
            val n = konecZImenom(k, besede(ime).map { kljuc(it) }.filter { it.isNotEmpty() })
            if (n > 0) { naprava = id; dolzinaCilja = n; break }
        }
        if (naprava.isEmpty() && k.size >= 3) {
            val zadnja = k.last()
            VRSTE[zadnja]?.let { v ->
                val n = konecZImenom(k, listOf(zadnja))
                if (n > 0) { vrste = v; dolzinaCilja = n }
            }
        }
        val ostanek = brezGlagola(b.take(b.size - dolzinaCilja))
        val ok = ostanek.map { kljuc(it) }
        // Vreme: »vreme«, »vreme v Mariboru«, »weather in Paris«, »vreme na TV«.
        if (ok.isNotEmpty() && ok[0] in VREME_BESEDE) {
            var kraj = ostanek.drop(1)
            if (kraj.isNotEmpty() && kljuc(kraj[0]) in PREDLOGI + setOf("za", "for", "fur", "para", "pour", "per", "a", "de", "di")) kraj = kraj.drop(1)
            return Ukaz(VREME, kraj.joinToString(" "), naprava, vrste)
        }
        if (naprava.isNotEmpty() || vrste.isNotEmpty()) return Ukaz(PRIKAZI_NA, ostanek.joinToString(" "), naprava, vrste)
        val t = b.joinToString(" ")
        if (jeNaslov(t)) return Ukaz(SPLET, if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t")
        return Ukaz(ISCI, t)
    }

    /** Spletni naslov (domena s piko, brez presledkov) - gre v brskalnik, ne v iskanje medijev (isto kot DomacaLupa). */
    fun jeNaslov(q: String): Boolean =
        !q.contains(' ') && (q.startsWith("http://") || q.startsWith("https://") ||
            Regex("^[\\p{L}\\p{N}-]+(\\.[\\p{L}\\p{N}-]+)*\\.[a-z]{2,}(/.*)?$", RegexOption.IGNORE_CASE).matches(q))

    // ------------------------------------------------------------------ zadetki lupe

    /**
     * Ujemanje imena (program, nastavitev, datoteka) s poizvedbo: 3 = ime se zacne s poizvedbo, 2 = beseda v imenu se
     * zacne z njo, 1 = vsebuje jo, 0 = ne ujema. Brez naglasov in velikih crk (»nas« najde »Nastavitve«, »cas« »Čas«).
     */
    fun ujemanje(ime: String, poizvedba: String): Int {
        val q = besede(poizvedba).joinToString(" ") { kljuc(it) }.trim()
        if (q.isEmpty()) return 0
        val i = besede(ime).joinToString(" ") { kljuc(it) }
        return when {
            i.startsWith(q) -> 3
            i.split(' ').any { it.startsWith(q) } -> 2
            i.replace(" ", "").contains(q.replace(" ", "")) -> 1
            else -> 0
        }
    }

    /** Najboljsi zadetki: po ujemanju, nato po imenu; brez neujemajocih; najvec [najvec]. */
    fun <T> najboljsi(seznam: List<T>, poizvedba: String, najvec: Int, ime: (T) -> String): List<T> =
        seznam.map { it to ujemanje(ime(it), poizvedba) }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<T, Int>> { it.second }.thenBy { ime(it.first).lowercase() })
            .take(najvec).map { it.first }

    /** Sprotni zadetki iz spleta niso za kratko poizvedbo (vsaka crka bi sla iskalniku) - sele od toliko znakov. */
    const val SPLET_OD_ZNAKOV = 3
    const val NAJVEC_PREDLOGOV = 5

    fun predlogeIskati(poizvedba: String, vklopljeno: Boolean): Boolean =
        vklopljeno && poizvedba.trim().length >= SPLET_OD_ZNAKOV && !jeNaslov(poizvedba.trim())

    /**
     * Predlogi iskalnika (Google client=chrome, DuckDuckGo type=list): `["poizvedba", ["predlog", ...], ...]`. Brez
     * ponovitve poizvedbe, brez praznih in kontrolnih znakov, najvec [NAJVEC_PREDLOGOV]. Pokvarjen odgovor: prazno.
     */
    fun predlogi(json: String, poizvedba: String): List<String> {
        val vrh = JsonLahki.elementi(json.trim()) ?: return emptyList()
        val drugi = vrh.getOrNull(1) ?: return emptyList()
        val q = poizvedba.trim().lowercase()
        return (JsonLahki.elementi(drugi) ?: return emptyList())
            .mapNotNull { JsonLahki.niz(it) }
            .map { it.replace(Regex("\\p{Cntrl}"), " ").trim().take(120) }
            .filter { it.isNotEmpty() && it.lowercase() != q }
            .distinct().take(NAJVEC_PREDLOGOV)
    }
}
