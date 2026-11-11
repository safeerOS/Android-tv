package si.safeer.tv.link

/**
 * Manjse varnostne odlocitve Safeer Linka iz pregleda 10. 11. 2026 - ciste, brez Androida
 * (tests/LinkVarnostPravilaTest.kt).
 */
object LinkVarnostPravila {
    // ------------------------------------------------------------------ dnevnik (4.1)

    /**
     * Oznaka naprave za dnevnik: zacetek in zadnji 4 znaki jedra, brez sredine (dnevniki se kopirajo v porocila). Polna
     * oznaka je `n-<16 znakov kljuca>-<program>`; iz nje ostane `n-…cdef-control`. Samo vidni ASCII, omejena dolzina -
     * oznako pise sredisce. Telo je v cast/OznakaDnevnik.kt (skupno z Link Core, ki ne sme poznati link/).
     */
    fun oznakaZaDnevnik(id: String): String = si.safeer.tv.cast.OznakaDnevnik.oznaka(id)

    // ------------------------------------------------------------------ napaka toka (pregled apk-24, S3)

    /**
     * Napaka toka `video.stream` kot koda, ne besedilo izjeme (kot Shramba in Pretvorba, [PrenosPravila.kodaNapake]):
     * »Connection refused« / »timeout« razkrivata, kateri naslovi in vrata domacega omrezja odgovarjajo. Vedno
     * neprazna (prazno polje `napaka` pomeni, da tok se tece).
     */
    fun napakaToka(e: Throwable): String = PrenosPravila.kodaNapake(e) { it is PretokHttp.NedovoljenCilj }

    // ------------------------------------------------------------------ sprejem zvoka (2.4)

    /** Racunalnik poslje svoje naslove v domacem omrezju; vec kot toliko jih ne poskusamo (vsak poskus caka do 4 s). */
    const val NAJVEC_NASLOVOV_ZVOKA = 6
    /** Glava toka zvoka mora priti v tem casu po povezavi; potem je tisina dovoljena (zvok tece, dokler ga racunalnik ne konca). */
    const val ROK_GLAVE_ZVOKA_MS = 8_000L

    /**
     * Naslovi iz ukaza `audio.play` (`host` najprej, nato `hosts`), na katere se sprejemnik res poveze. Prej je bil
     * seznam brez meje (vsak naslov 4 s cakanja, nit v ozadju) in je sprejel tudi to napravo samo. Zdaj: brez
     * ponovitev, samo veljavna imena in naslovi, brez zanke in povezavno-lokalnih ([PretokPravila.gostiteljPrepovedan]),
     * najvec [NAJVEC_NASLOVOV_ZVOKA]. Pregled apk-24 (N6): [lastni] so naslovi IP te naprave (iz njenih omreznih
     * vmesnikov, kot bajti); zapis, ki je eden izmed njih (tudi kot preslikani IPv4 v IPv6), odpade - commit 2d7bea54 je
     * to trdil, koda pa je izlocila samo zanko in povezavno-lokalne.
     */
    fun nasloviZvoka(host: String, hosts: List<String>, lastni: List<ByteArray> = emptyList()): List<String> {
        val izid = ArrayList<String>()
        for (surov in listOf(host) + hosts) {
            if (izid.size >= NAJVEC_NASLOVOV_ZVOKA) break
            val n = surov.trim()
            if (n.isEmpty() || n.length > 253 || n in izid) continue
            if (!n.all { it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '-' || it == ':' || it == '_' }) continue
            if (PretokPravila.gostiteljPrepovedan(n)) continue
            val b = PretokPravila.bajti(n)
            if (b != null && lastni.any { PretokPravila.enakNaslov(b, it) }) continue
            izid.add(n)
        }
        return izid
    }

    /** Ali je rok ([rokMs] od [zacetekMs]) mimo; ura nazaj steje kot mimo. */
    fun rokPotekel(zacetekMs: Long, zdajMs: Long, rokMs: Long): Boolean = zdajMs < zacetekMs || zdajMs - zacetekMs >= rokMs

    // ------------------------------------------------------------------ klepet (3.1)

    private val CAS_UTC = Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d.*""")

    /**
     * Cas sporocila klepeta v nasi obliki (`llll-mm-ddTuu:mm:ss+00:00`, UTC): cas posiljatelja ([iso]), a NAJVEC [zdaj]
     * (cas prejema v isti obliki). Prej je sporocilo s casom iz prihodnosti ostalo za vedno na vrhu pogovora in pred
     * vsemi poznejsimi sporocili. Nerazumljiv cas = cas prejema.
     */
    fun casSporocila(iso: String, zdaj: String): String {
        val t = iso.trim()
        if (!CAS_UTC.matches(t) || !(t.endsWith("Z") || t.contains("+00:00"))) return zdaj
        val cas = t.take(19) + "+00:00"
        return if (cas > zdaj) zdaj else cas
    }
}
