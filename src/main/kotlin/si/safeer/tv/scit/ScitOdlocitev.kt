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

    /**
     * Lastnik 9. 11. 2026 (Scit kot Pi-hole): odgovor za [ime] vsebuje cilje CNAME [cilji]. Vrne ime, ki ga zabelezimo med
     * ustavljenimi, ali null (odgovor gre naprej). Neodvisni pregled 9. 11.: cilj na seznamu GROZENJ ustavi VEDNO, tudi ob
     * izjemi ali zascitenem imenu (zabelezi se cilj - ta s seznama grozenj nima izjeme). Sicer izjema na imenu
     * ([izjemaImena]: uporabnikova, Webtorjeva, stalna), krajevno ime, Googlov koren ali zasciten gostitelj -> naprej;
     * oglasni cilj ustavi vprasano ime (zabelezi se ime, da ga uporabnik na zaslonu Scita lahko spusti).
     */
    fun cnameUstavi(ime: String, cilji: List<String>, naGroznihCilj: (String) -> Boolean, izjemaImena: Boolean,
                    odloci: (String) -> Izid): String? {
        if (cilji.isEmpty()) return null
        cilji.firstOrNull { val c = DomenskiNabor.normaliziraj(it); c.isNotEmpty() && !jeKrajevno(c) && naGroznihCilj(c) }
            ?.let { return DomenskiNabor.normaliziraj(it) }
        val d = DomenskiNabor.normaliziraj(ime)
        if (d.isEmpty() || izjemaImena || jeKrajevno(d) || jeZascitena(d) || ScitGostitelji.vrsta(d) == ScitGostitelji.Vrsta.KOREN) return null
        return if (cilji.any { odloci(it) == Izid.USTAVI }) d else null
    }

    /**
     * Blokada po IP odgovora (blocky »block by response IP«, 26. 11. 2026): odgovor za [ime] vsebuje naslov IPv4
     * [naslovi], ki je na seznamu groznj z naslovi (Feodo: botnet C2). Ustavi se vprasano ime (zabelezi se, da ga
     * uporabnik na zaslonu Scita lahko spusti). Izjema na imenu (uporabnikova, Webtorjeva, stalna), krajevno ime, Googlov
     * koren in zasciten gostitelj gredo naprej: naslov skupnega gostovanja se lahko ujema z naslovom s seznama, uporabnik
     * mora imeti pot, da ime spusti (pri CNAME na seznamu groznj to velja drugace, ker je cilj ime, ne skupen naslov).
     */
    fun ipUstavi(ime: String, naslovi: List<String>, naGroznemIp: (String) -> Boolean, izjemaImena: Boolean): Boolean {
        if (naslovi.isEmpty()) return false
        val d = DomenskiNabor.normaliziraj(ime)
        if (d.isEmpty() || izjemaImena || jeKrajevno(d) || jeZascitena(d) || ScitGostitelji.vrsta(d) == ScitGostitelji.Vrsta.KOREN) return false
        return naslovi.any(naGroznemIp)
    }
}
