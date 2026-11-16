package si.safeer.tv.cast

import java.io.InputStream
import java.io.OutputStream

/**
 * Vticniki skupne kode Link Core (Hub, krog naprav) proti delom, ki obstajajo samo na eni napravi (televizor: Global Link, strezniki
 * datotek, deljenje zaslona, Safeer Vnos). Skupne datoteke se kopirajo na telefon (tools/link-core-sync.sh) in ne smejo imenovati
 * razredov, ki jih tam ni. Privzeto je VARNO: rele se ne priznava, poti datotek niso obdelane. Ob zagonu procesa jih izpolni naprava
 * (na televizorju si.safeer.tv.SafeerAplikacija).
 */
object LinkVticniki {
    /** Ali je naslov krajevna vrata odprtega releja Global Link. */
    @Volatile var jeRele: (String, Int) -> Boolean = { _, _ -> false }

    /** Poti streznika datotek prek Huba; vrne true, ce je zahtevo obdelal. */
    @Volatile var prekHuba: ((String, String, Map<String, String>, InputStream, OutputStream, Map<String, String>) -> Boolean)? = null

    /** Ob umiku naprave iz kroga, PRED poslusalci: konec deljenja zaslona, brisanje zaupanja (vsak korak zase). Argument je Context aplikacije (tip Any, da se datoteka prevede tudi v preizkusih brez Androida). */
    @Volatile var obUmikuPred: List<(Any) -> Unit> = emptyList()

    /** Ob umiku naprave iz kroga, PO poslusalcih: Safeer Vnos sledi krogu. */
    @Volatile var obUmikuPo: List<(Any) -> Unit> = emptyList()
}
