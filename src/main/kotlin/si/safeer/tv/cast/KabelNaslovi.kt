package si.safeer.tv.cast

/**
 * Prednost neposredne kabelske povezave za sejo zaslona (samo TV; ni del Link Core, telefon je ne rabi).
 *
 * Prej je TV vedno poskusil naslov iz seznama naprav (Wi-Fi) in uspel, zato kabel ni bil nikoli uporabljen. Zdaj gredo
 * pred ostale naslovi, ki so na podomrezju zicnega (Ethernet) vmesnika TV: brez Wi-Fi in usmerjevalnika je zamik manjsi
 * in bolj stalen. Wi-Fi je rezerva.
 */
internal object KabelNaslovi {
    /** Podomrezje IPv4 vmesnika te naprave: naslov in dolzina predpone (npr. 198.51.100.2 in 30). */
    class Podomrezje(val naslov: String, val predpona: Int)

    private fun stevilo(naslov: String): Long? {
        if (!HubNaslovi.naslovNaprave(naslov)) return null
        return naslov.split('.').fold(0L) { a, d -> (a shl 8) or d.toLong() }
    }

    /** Ali je [naslov] na istem podomrezju kot [p] (dosegljiv brez usmerjevalnika). Neveljavno podomrezje: ne. */
    fun naPodomrezju(naslov: String, p: Podomrezje): Boolean {
        if (p.predpona !in 8..32) return false
        val a = stevilo(naslov) ?: return false
        val b = stevilo(p.naslov) ?: return false
        val maska = (0xFFFFFFFFL shl (32 - p.predpona)) and 0xFFFFFFFFL
        return (a and maska) == (b and maska)
    }

    /**
     * Kot [HubNaslovi.kandidati], vendar najprej tisti naslovi, ki so na podomrezju zicnega vmesnika. Vrstni red znotraj
     * skupin ostane; ce kabla ni ([zicna] prazen) ali nobeden naslov ni na njem, je izid enak [HubNaslovi.kandidati]
     * (Wi-Fi je rezerva). Rez na [HubNaslovi.NAJVEC_KANDIDATOV] je PO razvrstitvi, da kabelski naslov ne odpade.
     * Naslov iz seznama naprav Linka ne sme odpasti: zlonamerna naprava bi z odgovorom (hosts) napolnila vsa mesta s
     * kabelskimi naslovi in ga izrinila. Ce ga rez odnese, dobi zadnje mesto.
     */
    /** Zgornja meja pregledanih naslovov iz seznama naprav (kabelski naslov mora ostati dosegljiv, a ne neomejeno). */
    const val NAJVEC_PREGLEDANIH = 64

    fun kandidatiSKablom(naslov: String, hosts: List<String>?, zicna: List<Podomrezje>): List<String> {
        val najvec = HubNaslovi.NAJVEC_KANDIDATOV
        // Slepi pregled 18. 11. 2026, N7: odgovor racunalnika (hosts) ni omejen; prej O(n^2) na glavni niti (ANR).
        // Podvojitve odpadejo prek mnozice (vrstni red ostane), pregled se ustavi pri NAJVEC_PREGLEDANIH razlicnih.
        val vsi = LinkedHashSet<String>()
        val prvi = naslov.trim()
        if (prvi.isNotEmpty()) vsi.add(prvi)
        for (h0 in hosts.orEmpty()) {
            if (vsi.size >= NAJVEC_PREGLEDANIH) break
            val h = h0.trim()
            if (h.isNotEmpty() && HubNaslovi.naslovNaprave(h)) vsi.add(h)
        }
        if (zicna.isEmpty()) return vsi.take(najvec)
        val (zicni, ostali) = vsi.partition { n -> zicna.any { naPodomrezju(n, it) } }
        val izid = (zicni + ostali).take(najvec)
        return if (prvi.isEmpty() || prvi in izid) izid else izid.take(najvec - 1) + prvi
    }

    /**
     * Slepi pregled 18. 11. 2026, N6: omrezje VPN (Scit) lahko prevzame prenosni tip osnovnega omrezja, zato samo
     * TRANSPORT_ETHERNET ne zadosca: vezava vticnice na VPN je tocno napaka, ki jo ta razred odpravlja. Zicno je samo
     * omrezje, ki ni VPN (ni prenosa VPN in ima NET_CAPABILITY_NOT_VPN).
     */
    fun jeZicnoOmrezje(imaEthernet: Boolean, imaVpn: Boolean, imaNotVpn: Boolean): Boolean = imaEthernet && !imaVpn && imaNotVpn

    /** Rok za naslov na kablu: omrezje LAN odgovori v milisekundah (rokovanje TLS na TV nekaj 100 ms). */
    const val ROK_KABLA_MS = 1_500

    /** Rok za posamezen naslov: kratek za naslove na kablu, sicer null (privzeti rok seznama). */
    fun rokNaslova(zicna: List<Podomrezje>): (String) -> Int? =
        { n -> if (zicna.any { naPodomrezju(n, it) }) ROK_KABLA_MS else null }

    /**
     * Podomrezja IPv4 delujocih zicnih vmesnikov (eth*) te naprave; prazno brez kabla ali ob napaki. Samo branje
     * stanja vmesnikov, nobene nastavitve sistema.
     */
    fun zicnaPodomrezja(): List<Podomrezje> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.name.startsWith("eth") && it.isUp && !it.isLoopback }
            .flatMap { v -> v.interfaceAddresses.mapNotNull { a ->
                val ip = a.address as? java.net.Inet4Address ?: return@mapNotNull null
                Podomrezje(ip.hostAddress.orEmpty(), a.networkPrefixLength.toInt())
            } }
    } catch (_: Throwable) { emptyList() }
}
