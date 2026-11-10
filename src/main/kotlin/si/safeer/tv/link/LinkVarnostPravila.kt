package si.safeer.tv.link

/**
 * Manjse varnostne odlocitve Safeer Linka iz pregleda 10. 11. 2026 - ciste, brez Androida
 * (tests/LinkVarnostPravilaTest.kt).
 */
object LinkVarnostPravila {
    // ------------------------------------------------------------------ sprejem zvoka (2.4)

    /** Racunalnik poslje svoje naslove v domacem omrezju; vec kot toliko jih ne poskusamo (vsak poskus caka do 4 s). */
    const val NAJVEC_NASLOVOV_ZVOKA = 6
    /** Glava toka zvoka mora priti v tem casu po povezavi; potem je tisina dovoljena (zvok tece, dokler ga racunalnik ne konca). */
    const val ROK_GLAVE_ZVOKA_MS = 8_000L

    /**
     * Naslovi iz ukaza `audio.play` (`host` najprej, nato `hosts`), na katere se sprejemnik res poveze. Prej je bil
     * seznam brez meje (vsak naslov 4 s cakanja, nit v ozadju) in je sprejel tudi to napravo samo. Zdaj: brez
     * ponovitev, samo veljavna imena in naslovi, brez zanke in povezavno-lokalnih ([PretokPravila.gostiteljPrepovedan]),
     * najvec [NAJVEC_NASLOVOV_ZVOKA].
     */
    fun nasloviZvoka(host: String, hosts: List<String>): List<String> {
        val izid = ArrayList<String>()
        for (surov in listOf(host) + hosts) {
            if (izid.size >= NAJVEC_NASLOVOV_ZVOKA) break
            val n = surov.trim()
            if (n.isEmpty() || n.length > 253 || n in izid) continue
            if (!n.all { it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '-' || it == ':' || it == '_' }) continue
            if (PretokPravila.gostiteljPrepovedan(n)) continue
            izid.add(n)
        }
        return izid
    }

    /** Ali je rok ([rokMs] od [zacetekMs]) mimo; ura nazaj steje kot mimo. */
    fun rokPotekel(zacetekMs: Long, zdajMs: Long, rokMs: Long): Boolean = zdajMs < zacetekMs || zdajMs - zacetekMs >= rokMs
}
