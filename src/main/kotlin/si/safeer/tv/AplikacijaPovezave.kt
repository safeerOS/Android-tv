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

    /** Samo https: aplikaciji nikoli ne predamo naslova, ki ga lahko spremeni kdorkoli na poti (lastnik 2. 11. 2026). */
    fun jeHttps(naslov: String?): Boolean = naslov != null && naslov.startsWith("https://", ignoreCase = true)

    /** Pot naslova je prazna ali »/« (ali naslova ni mogoce razcleniti). */
    fun jeKoren(naslov: String?): Boolean {
        val pot = try { java.net.URI(naslov ?: return true).rawPath } catch (_: Exception) { return true }
        return pot.isNullOrEmpty() || pot == "/"
    }

    /**
     * Naslov za dnevnik: shema, gostitelj in prvi del poti - nikoli poizvedba ali delcek (tam je enkratna koda prijave).
     */
    fun zaDnevnik(naslov: String?): String {
        val u = try { java.net.URI(naslov ?: return "-") } catch (_: Exception) { return "-" }
        val prvi = u.rawPath.orEmpty().trimStart('/').substringBefore('/')
        return "${u.scheme}://${u.host}/" + (if (prvi.isEmpty()) "" else "$prvi/…") + (if (u.rawQuery != null) "?…" else "")
    }

    /**
     * Rocni gumb »Nadaljuj v aplikaciji«: [naslov] odpre aplikacija izvora ([izvorOdpre]: VIEW+BROWSABLE+setPackage
     * izvora najde dejavnost), ki ni brskalnik in ni nasa. Vrne paket ali null.
     */
    fun ponudiAplikacijo(
        izvor: String?,
        izvorOdpre: Boolean,
        izvorJeBrskalnik: Boolean,
        naslov: String?,
        lastniPaket: String
    ): String? {
        if (izvor == null || jeNas(izvor, lastniPaket) || !jeHttps(naslov)) return null
        if (izvorJeBrskalnik || !izvorOdpre) return null
        return izvor
    }

    /**
     * Tipka Nazaj (lastnik 2. 11. 2026): ko je gumb »Nadaljuj v aplikaciji« ponujen ([paketGumba]), Nazaj vrne v
     * aplikacijo - zgodovina zavihka bi peljala le skozi strani prijave. V nacinu spletne aplikacije gumba ni.
     */
    fun nazajVAplikacijo(paketGumba: String?, nacinSpletneAplikacije: Boolean): Boolean =
        !nacinSpletneAplikacije && !paketGumba.isNullOrBlank()

    /**
     * Samodejna predaja (kot Chrome), za vsako navigacijo glavnega okvirja - tudi preusmeritve brez kretnje.
     * TV 2. 11. 2026 19:35: predali smo trenutno stran (claude.ai/), ko je brskalnik povratni naslov prijave
     * (/sso-callback/android?...) ze sam nalozil - aplikacija ni dobila kode in je ostala odjavljena. Zato: naslov, ki ga
     * odpre izvor, se ne nalozi, ampak gre izvoru tak, kot je (s poizvedbo). Koren (»/«) ne: aplikacija ga prijavi, a
     * stran ga obisce ze pred koncem prijave.
     */
    fun predajNavigacijo(
        izvor: String?,
        izvorOdpre: Boolean,
        izvorJeBrskalnik: Boolean,
        glavniOkvir: Boolean,
        naslov: String?,
        lastniPaket: String
    ): String? {
        if (!glavniOkvir || jeKoren(naslov)) return null
        return ponudiAplikacijo(izvor, izvorOdpre, izvorJeBrskalnik, naslov, lastniPaket)
    }
}
