package si.safeer.tv.scit

/**
 * En Scit za vse OS (lastnik 4. 11. 2026): odlocitev za eno ime DNS, ista kot v Safeer-linux (core/os_scit.py).
 * Skupni primeri: tests/skupni-primeri/scit-pravila.json (tests/ScitSkupniPrimeriTest.kt preveri tudi sezname spodaj).
 * Ciste funkcije brez Androida - klice jih ScitStoritev (UDP in TCP) in preizkus.
 */
object ScitOdlocitev {
    enum class Izid { NAPREJ, USTAVI, KANAREK }

    /** Firefox (in podobni) vprasa to ime, preden sam vklopi DoH; NXDOMAIN = ostane pri sistemskem DNS, torej pri Scitu. */
    const val KANAREK_DOH = "use-application-dns.net"

    /** Krajevna imena (RFC 6761, 6762, 8375; .internal): nikoli ustavljena, niti s seznama grozenj - v javnem DNS jih ni. */
    val KRAJEVNA = listOf("localhost")
    val KRAJEVNE_KONCNICE = listOf(".localhost", ".local", ".lan", ".home", ".internal", ".arpa")

    /**
     * Zasciteni gostitelji (s poddomenami): posodobitve, preverjanje cloveka in Safeer. Seznami OGLASOV jih ne ustavijo
     * (preobsiren ali zastrupljen seznam ne sme zapreti popravkov in prijav); seznam GROZENJ jih ustavi vedno.
     */
    val ZASCITENI = listOf(
        "archive.ubuntu.com", "security.ubuntu.com", "ports.ubuntu.com", "ppa.launchpadcontent.net", "packages.linuxmint.com",
        "deb.debian.org", "security.debian.org", "flathub.org", "github.com", "api.github.com", "objects.githubusercontent.com",
        "release-assets.githubusercontent.com", "cloudflare.com", "hcaptcha.com", "recaptcha.net", "www.google.com",
        "www.gstatic.com", "safeer.si"
    )

    fun jeKrajevno(d: String): Boolean = d in KRAJEVNA || KRAJEVNE_KONCNICE.any { d.endsWith(it) }

    fun jeZascitena(d: String): Boolean = ZASCITENI.any { d == it || d.endsWith(".$it") }

    /**
     * [naSeznamu]: ime ali nadrejena domena je v naboru (vsi seznami); [naGroznih]: v naboru grozenj (samo ce je
     * [grozneNalozene]); [izjema]: Webtorjeva naucena ali uporabnikova izjema, ki ob tej uri se velja. Dokler nabor grozenj
     * ni nalozen, veljajo samo stalne izjeme Webtor - »ne vem« ni »da« (tudi za zascitene gostitelje).
     */
    fun odloci(ime: String, naSeznamu: (String) -> Boolean, grozneNalozene: Boolean, naGroznih: (String) -> Boolean,
               izjema: (String) -> Boolean): Izid {
        val d = DomenskiNabor.normaliziraj(ime)
        if (d.isEmpty()) return Izid.NAPREJ
        if (d == KANAREK_DOH) return Izid.KANAREK
        if (jeKrajevno(d) || !naSeznamu(d)) return Izid.NAPREJ
        val groznja = grozneNalozene && naGroznih(d)
        val dovoljena = grozneNalozene && (izjema(d) || jeZascitena(d))
        return if (ScitIzjeme.blokirana(true, grozneNalozene, groznja, dovoljena, ScitIzjeme.jeStalna(d))) Izid.USTAVI else Izid.NAPREJ
    }
}
