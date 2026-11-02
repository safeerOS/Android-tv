package si.safeer.tv

/**
 * Povratek v aplikacijo, ki je odprla zavihek (Android App Links, kot Chrome).
 *
 * TV 2. 11. 2026 18:27: aplikacija Claude je za prijavo odprla Safeer TV Browser (VIEW https://claude.ai/...). Po prijavi
 * poti nazaj ni bilo: stran je ostala na claude.ai/new v brskalniku, ceprav aplikacija za te poti prijavi App Link. Chrome
 * tako navigacijo preda aplikaciji. Tu je odlocitev brez Androida (preizkus tests/AplikacijaPovezaveTest.kt);
 * PackageManager vprasa [AplikacijaPovezaveSistem].
 *
 * Varnost: naslov vrnemo SAMO paketu, ki je zavihek odprl (nikoli drugi aplikaciji, ki bi si prisvojila domeno), samo
 * glavnemu okvirju in samo http(s). Drugih shem (intent:, mailto:) to ne spremeni - te ostanejo pri obstojecih pravilih.
 */
object AplikacijaPovezave {
    const val AKCIJA_VIEW = "android.intent.action.VIEW"
    /** Dodatek, s katerim aplikacije povedo svoj paket brskalniku (Chrome in Firefox ga berata enako). */
    const val ID_APLIKACIJE = "com.android.browser.application_id"

    private val PAKET = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun jeSplet(naslov: String?): Boolean =
        naslov != null && (naslov.startsWith("http://", ignoreCase = true) || naslov.startsWith("https://", ignoreCase = true))

    /** Nase aplikacije (Safeer OS, telefon ...) imajo svoja pravila vracanja - teh ne prevzemamo. */
    private fun jeNas(paket: String, lastniPaket: String) = paket == lastniPaket || paket.startsWith("si.safeer.")

    /**
     * Paket aplikacije, ki je odprla zavihek, ali null. Samo namera VIEW s http(s) naslovom. Najprej Activity.referrer
     * (android-app://paket; sistem ga nastavi po klicatelju), sicer dodatek [ID_APLIKACIJE].
     */
    fun izvor(
        akcija: String?,
        naslov: String?,
        referrerShema: String?,
        referrerGostitelj: String?,
        idAplikacije: String?,
        lastniPaket: String
    ): String? {
        if (akcija != AKCIJA_VIEW || !jeSplet(naslov)) return null
        val kandidat = if (referrerShema.equals("android-app", ignoreCase = true) && !referrerGostitelj.isNullOrBlank())
            referrerGostitelj else idAplikacije
        val paket = kandidat?.trim() ?: return null
        if (paket.length > 255 || !PAKET.matches(paket) || jeNas(paket, lastniPaket)) return null
        return paket
    }

    /**
     * Rocni gumb »Nadaljuj v aplikaciji«: [naslov] odpre natanko aplikacija izvora, ki ni brskalnik in ni nasa.
     * Vrne paket ali null.
     */
    fun ponudiAplikacijo(
        izvor: String?,
        razreseniPaketi: Collection<String>,
        brskalniki: Collection<String>,
        naslov: String?,
        lastniPaket: String
    ): String? {
        if (izvor == null || jeNas(izvor, lastniPaket) || !jeSplet(naslov)) return null
        if (izvor in brskalniki) return null
        return izvor.takeIf { it in razreseniPaketi }
    }

    /**
     * Samodejna predaja navigacije: samo glavni okvir in ne zacetno nalaganje naslova, ki ga je poslala aplikacija (ter
     * njegove preusmeritve) - sicer bi stran, ki jo aplikacija odpre zato, da se prijavimo, takoj vrnili nazaj (zanka).
     */
    fun predajNavigacijo(
        izvor: String?,
        razreseniPaketi: Collection<String>,
        brskalniki: Collection<String>,
        glavniOkvir: Boolean,
        zacetnoNalaganje: Boolean,
        naslov: String?,
        lastniPaket: String
    ): String? {
        if (!glavniOkvir || zacetnoNalaganje) return null
        return ponudiAplikacijo(izvor, razreseniPaketi, brskalniki, naslov, lastniPaket)
    }
}
