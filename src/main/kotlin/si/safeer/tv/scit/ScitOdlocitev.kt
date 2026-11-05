package si.safeer.tv.scit

import si.safeer.tv.ScitGostitelji

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
     * [naSeznamu]: najblizja domena imena (ime ali nadrejena) v naboru vseh seznamov med domenami, za katere vrne drugi
     * argument true (null, ce je ni); [naGroznih]: enako v naboru grozenj (samo ce je [grozneNalozene]); [izjema]:
     * Webtorjeva naucena ali uporabnikova izjema, ki ob tej uri se velja. Dokler nabor grozenj ni nalozen, veljajo samo
     * stalne izjeme Webtor - »ne vem« ni »da« (tudi za zascitene gostitelje).
     *
     * Googlovi gostitelji (5. 11. 2026, kot core/os_scit.py): DNS pozna samo ime, ne poti. Koren (iskanje, prijava) se ne
     * ustavi nikoli; na skupnem gostitelju z uporabnisko vsebino (sites.google.com, storage.googleapis.com) ne velja noben
     * vnos GROZNJE; vnos celotne domene (google.com, web.app) ne velja za gostitelje pod njo. Izrecen gostitelj OGLASOV
     * pod skupno infrastrukturo (afs.googleusercontent.com) ostane ustavljen.
     */
    fun odloci(ime: String, naSeznamu: (String, (String) -> Boolean) -> String?, grozneNalozene: Boolean,
               naGroznih: (String, (String) -> Boolean) -> String?, izjema: (String) -> Boolean): Izid {
        val d = DomenskiNabor.normaliziraj(ime)
        if (d.isEmpty()) return Izid.NAPREJ
        if (d == KANAREK_DOH) return Izid.KANAREK
        if (jeKrajevno(d)) return Izid.NAPREJ
        val vrsta = ScitGostitelji.vrsta(d)
        if (vrsta == ScitGostitelji.Vrsta.KOREN) return Izid.NAPREJ
        val sme: (String) -> Boolean = ScitGostitelji::smeGostitelj
        var zadetek = naSeznamu(d, sme)
        if (zadetek == null && vrsta == ScitGostitelji.Vrsta.VSEBINA && grozneNalozene) {
            // Najblizji vnos pod skupno infrastrukturo velja samo, ce ni na seznamu grozenj (torej je oglas).
            val z = naSeznamu(d, ScitGostitelji::smeOglas)
            if (z != null && naGroznih(d) { it == z } == null) zadetek = z
        }
        if (zadetek == null) return Izid.NAPREJ
        val groznja = grozneNalozene && naGroznih(d, sme) != null
        val dovoljena = grozneNalozene && (izjema(d) || jeZascitena(d))
        return if (ScitIzjeme.blokirana(true, grozneNalozene, groznja, dovoljena, ScitIzjeme.jeStalna(d))) Izid.USTAVI else Izid.NAPREJ
    }
}
