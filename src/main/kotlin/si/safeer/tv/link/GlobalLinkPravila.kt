package si.safeer.tv.link

/**
 * Ciste odlocitve zank Global Linka (brez Androida, da jih preizkus izvaja).
 * Pregled 9. 11. 2026: zanka posiljanja je ob polni vrsti cakala brez meje (nit in vticnica sta ostali za vedno),
 * zanka poslusanja pa je zamik ponastavila ze ob vzpostavitvi, zato je ob takojsnjih prekinitvah poskusala na 5 s.
 */
object GlobalLinkPravila {
    /** Neodvisni pregled 12. 11. 2026 (S3): najvec toliko hkrati odprtih kanalov releja; vsak ima nit, vticnico in WebSocket (TV 2 GB). */
    const val NAJVEC_KANALOV = 32

    fun smeSprejeti(odprtih: Int, najvec: Int = NAJVEC_KANALOV): Boolean = odprtih < najvec

    private val KANAL = Regex("[0-9a-f]{32}")

    /** Ime kanala iz releja gre v URL (/v1/accept?kanal=...): samo 32 malih heks znakov (pregled N1, brez &, # in presledkov). */
    fun veljavenKanal(kanal: String): Boolean = KANAL.matches(kanal)

    /**
     * Pregled 12. 11. 2026 (N3): zahteve releja nosijo podpisane glave (X-Safeer-Key/Signature); OkHttp privzeto sledi
     * preusmeritvam in jih poslje na kateri koli naslov. Rele ne preusmerja, zato 3xx pomeni napako, ne skok.
     */
    fun odjemalec(graditelj: okhttp3.OkHttpClient.Builder): okhttp3.OkHttpClient.Builder =
        graditelj.followRedirects(false).followSslRedirects(false)

    /** Najvec toliko naprav hub objavi kot dovoljene na releju. */
    const val NAJVEC_DOVOLJENIH = 256

    /**
     * Pregled 12. 11. 2026 (N2): seznam naprav, ki smejo prek releja do tega huba, iz TRENUTNEGA kroga (id-ji clanov; brez
     * nas, brez podvojenih). Umaknjena naprava ni vec v krogu, zato je tudi ni v seznamu - ob vsaki spremembi kroga
     * se seznam takoj objavi, ne sele ob obnovitvi (do 1-3 min, ko bi umaknjena naprava se klicala hub).
     */
    fun dovoljeniIdji(clani: List<String?>, nas: String): List<String> =
        clani.filterNotNull().filter { it != nas }.distinct().take(NAJVEC_DOVOLJENIH)

    enum class Akcija { CAKAJ, NADALJUJ, PREKINI }

    /** Vrsta posiljanja nad to mejo: pocakamo, da se sprazni (pocasna povezava), namesto da kopicimo v pomnilnik TV. */
    const val VRSTA_NAJVEC = 1_000_000L
    /** Dlje ne cakamo: druga stran ne bere, kanal zapremo in sprostimo nit. */
    const val VRSTA_CAKAJ_MS = 10_000L
    const val ZAMIK_PRVI_MS = 5_000L
    const val ZAMIK_NAJVEC_MS = 300_000L
    /** Povezava, ki je zdrzala toliko, je bila zdrava: naslednji poskus spet takoj po prvem zamiku. */
    const val ZDRAVA_POVEZAVA_MS = 60_000L

    fun odlocitevVrste(velikostVrste: Long, pretecenCasMs: Long, zaprta: Boolean): Akcija {
        if (zaprta) return Akcija.PREKINI
        if (velikostVrste <= VRSTA_NAJVEC) return Akcija.NADALJUJ
        if (pretecenCasMs > VRSTA_CAKAJ_MS) return Akcija.PREKINI
        return Akcija.CAKAJ
    }

    /** Koliko pocakamo ZDAJ: po zdravi povezavi prvi zamik, sicer zamik, ki je na vrsti. */
    fun zamikZdaj(naVrsti: Long, trajanjeZadnjePovezaveMs: Long): Long =
        if (trajanjeZadnjePovezaveMs >= ZDRAVA_POVEZAVA_MS) ZAMIK_PRVI_MS else naVrsti.coerceIn(ZAMIK_PRVI_MS, ZAMIK_NAJVEC_MS)

    /** Zamik za poskus po tem: dvakrat daljsi, najvec 5 min (streznika ob izpadu ne zasipamo). */
    fun naslednjiZamik(pravkarCakali: Long): Long = (pravkarCakali * 2L).coerceAtMost(ZAMIK_NAJVEC_MS)

    /**
     * Zamik, nakljucno v zgornji polovici: [zamikMs/2, zamikMs]. Izpad Microsoft 365 (13. 11. 2026): po ponovnem
     * zagonu sredisca ali releja so se vse naprave z enakimi fiksnimi zamiki vracale v isti sekundi in obnovljeni
     * streznik spet zasule. Velja za vse ponovne povezave Linka (Global Link, LinkOdjemalec, LinkUpravitelj).
     */
    fun razprsen(zamikMs: Long, nakljucno: Double): Long {
        val pol = zamikMs.coerceAtLeast(0L) / 2L
        return pol + (nakljucno.coerceIn(0.0, 1.0) * pol).toLong()
    }

    fun razprsen(zamikMs: Long): Long = razprsen(zamikMs, java.util.concurrent.ThreadLocalRandom.current().nextDouble())
}
