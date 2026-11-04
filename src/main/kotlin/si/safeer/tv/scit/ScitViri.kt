package si.safeer.tv.scit

import com.safeer.threatfeed.PlainListSource

/**
 * Kateri seznami brskalnika gredo v nabor Scita (domene.bin, grozne.bin). Cista funkcija brez Androida (preizkus v JVM).
 *
 * Scit je filter DNS: rabi samo imena domen. EasyList (raw, pravila za brskalnik) in seznami IPv4 (Feodo) v nabor ne gredo,
 * zato jih proces :scit ne prenasa in ne bere (4. 11. 2026, prej je :scit zagnal celega agenta brskalnika).
 */
object ScitViri {
    /** Seznam gre v domene.bin. */
    fun zaNabor(s: PlainListSource): Boolean = !s.raw && !s.ipv4

    /** Seznam gre tudi v grozne.bin (ne oglasi: izjema Scita ga nikoli ne umakne). */
    fun zaGrozne(s: PlainListSource): Boolean = zaNabor(s) && !s.dnsOnly

    /**
     * Seznam bere glavni proces brskalnika. Seznami samo za DNS (dnsOnly, HaGeZi PRO) ne: brskalnik jih ne uporablja, branje
     * 247 757 domen ob vsaki osvezitvi pa je na TV dvignilo kopico na 98/122 MB (4. 11. 2026). Bere jih samo proces :scit.
     */
    fun zaBrskalnik(s: PlainListSource): Boolean = !s.dnsOnly

    /** Viri za agenta seznamov v procesu :scit, v istem vrstnem redu kot v brskalniku (enak kljuc vira nabora). */
    fun viri(vsi: List<PlainListSource>): List<PlainListSource> = vsi.filter(::zaNabor)
}
