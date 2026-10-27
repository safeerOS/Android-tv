package si.safeer.tv.os

/**
 * Naloga 42 D (27. 10. 2026): Safeer Link na novem zaganjalniku - naprave ozjega kroga. Klik na napravo odpre gledanje
 * njenega zaslona in upravljanje z daljincem po ISTI poti kot kartica Zaslon na starem domacem zaslonu: racunalnik
 * (zmoznost »desktop«) v ZaslonActivity, naprava Android, ki deli zaslon, prek PovezaniZasloni (deljenje zaslona + vnos).
 *
 * Dovoljenja ozjega kroga se sinhronizirajo: »Vedno dovoli« za deljenje zaslona (ZaupanjeZaslona), ki ga uporabnik da na
 * eni svoji napravi, velja na vseh njegovih napravah v ozjem krogu brez ponovne potrditve - prek sifrirane poti
 * usklajevanja kljucev (secrets.sync, [UskladitevKljucevPravila.PREDPONA_ZAUPANJA]). Zunaj kroga nic: zaupanje se ne
 * poslje napravi zunaj kroga, ne sprejme od nje in ne velja za napravo, ki ni v krogu TE naprave. Besedilo dohodnega
 * sporocila (Safeer Chat, e-posta ...) nikoli ne podeli dostopa ([VIR_SPOROCILO]).
 * Ciste odlocitve (tests/NapraveKrogaPravilaTest.kt).
 */
object NapraveKrogaPravila {
    const val ZMOZNOST_RACUNALNIK = "desktop"

    const val POT_RACUNALNIK = "racunalnik"
    const val POT_DELI_ZASLON = "deli_zaslon"
    /** Naprava v krogu, ki zaslona ne deli: odpre se seznam naprav (Safeer Link), kjer je vse ostalo. */
    const val POT_NAPRAVE = "naprave"

    data class Naprava(val id: String, val ime: String, val zmoznosti: Set<String>, val taNaprava: Boolean, val vKrogu: Boolean,
                       val povezana: Boolean)

    /** Naprave za seznam: samo druge naprave v ozjem krogu; povezane prve, nato po imenu. */
    fun zaPrikaz(naprave: List<Naprava>): List<Naprava> =
        naprave.filter { it.vKrogu && !it.taNaprava }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<Naprava> { it.povezana }.thenBy { it.ime.lowercase() })

    /** Pot ob kliku - ista kot kartica Zaslon (DomovActivity.odpriZaslon). [deliZaslon] = Daljinec.ZMOZNOST_DELI_ZASLON. */
    fun pot(zmoznosti: Set<String>, deliZaslon: String): String = when {
        ZMOZNOST_RACUNALNIK in zmoznosti -> POT_RACUNALNIK
        deliZaslon in zmoznosti -> POT_DELI_ZASLON
        else -> POT_NAPRAVE
    }

    // ------------------------------------------------------------------ sinhronizacija zaupanja v ozjem krogu

    /** Kanal, po katerem je zaupanje prislo: samo usklajevanje kljucev med potrjenimi napravami kroga. */
    const val VIR_USKLADITEV = "secrets.sync"
    /** Dohodno sporocilo (besedilo) - nikoli ne podeli dostopa. */
    const val VIR_SPOROCILO = "sporocilo"

    /** Jedro iz kljuca naprave (»n-« + 16 sestnajstiskih znakov, KrogZaupanja.idIzKljuca); stari id-ji brez kljuca ne. */
    private val RX_JEDRO = Regex("^n-[0-9a-f]{16}$")

    fun veljavnoJedro(jedro: String): Boolean = RX_JEDRO.matches(jedro)

    /**
     * Prejeto zaupanje za napravo [jedro] se uporabi samo: po usklajevanju kljucev, od posiljatelja v ozjem krogu te
     * naprave, za napravo, ki je TUDI v ozjem krogu te naprave, in nikoli za to napravo samo (sama sebi ne deli).
     * Odvzem (grob) velja pod istimi pogoji - naprava zunaj kroga zaupanja tu tako ali tako nima.
     */
    fun sprejmiZaupanje(vir: String, posiljateljVKrogu: Boolean, jedro: String, jedroVKrogu: Boolean, jedroJeTaNaprava: Boolean): Boolean =
        vir == VIR_USKLADITEV && posiljateljVKrogu && veljavnoJedro(jedro) && jedroVKrogu && !jedroJeTaNaprava

    /** Lastna zaupanja, ki gredo drugim napravam kroga: samo veljavna jedra naprav, ki so v ozjem krogu te naprave. */
    fun zaPoslati(vedno: Set<String>, vKrogu: (String) -> Boolean): Set<String> =
        vedno.filter { veljavnoJedro(it) && try { vKrogu(it) } catch (_: Throwable) { false } }.toSortedSet()
}
