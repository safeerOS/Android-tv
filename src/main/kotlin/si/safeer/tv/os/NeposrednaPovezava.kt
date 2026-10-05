package si.safeer.tv.os

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * Neposredna povezava TLS s **pripetim** potrdilom do naprave, ki ima lahko vec naslovov (docs/LINK-MESH.md,
 * pravilo 8): naslov iz seznama naprav Safeer Linka in tisti, ki jih je naprava nastela sama.
 *
 * Brez Androidovih razredov (razen dnevnika), da jo preizkus pozene v navadnem JVM s pravimi vticnicami
 * (tests/NeposrednaPovezavaTest.kt).
 */
object NeposrednaPovezava {
    /** Cas za povezavo in rokovanje, kadar je naslov en sam. */
    const val CAS_ENEGA_MS = 8_000

    /** Cas za en naslov, kadar jih je vec: stirje poskusi se izidejo v 30 s, kolikor naprava caka gledalca. */
    const val CAS_KANDIDATA_MS = 4_000

    /** En naslov dobi ves cas; vec naslovov si ga razdeli, da napacen prvi ne porabi cakanja naprave. */
    fun casZa(steviloNaslovov: Int): Int = if (steviloNaslovov <= 1) CAS_ENEGA_MS else CAS_KANDIDATA_MS

    /**
     * TCP in TLS do prvega naslova, na katerem odgovori naprava s potrdilom [odtis]. Naslov, na katerem ni nikogar,
     * je tam druga naprava (rokovanje s pripetim odtisom pade) ali naprava molci, preskocimo. Vrne povezavo po
     * koncanem rokovanju ali null, ce naprave ni na nobenem naslovu.
     *
     * Klicatelj sele po tem poslje svoj enkratni zeton - naprava na napacnem naslovu ga zato nikoli ne vidi.
     * [tece] = false prekine iskanje (uporabnik je zaslon zapustil).
     */
    fun povezi(
        naslovi: List<String>,
        vrata: Int,
        odtis: String,
        casMs: Int = casZa(naslovi.size),
        oznaka: String = "SafeerPovezava",
        tece: () -> Boolean = { true },
    ): SSLSocket? {
        val (tovarna, _) = Pin.tovarna(odtis)
        for (n in naslovi) {
            if (!tece()) return null
            val goli = Socket()
            try {
                goli.connect(InetSocketAddress(n, vrata), casMs)
                goli.tcpNoDelay = true                     // brez Naglejevega zbiranja: vsak paket takoj
                val s = tovarna.createSocket(goli, n, vrata, true) as SSLSocket
                s.soTimeout = casMs                        // nema naprava ne sme zadrzati naslednjega naslova
                s.startHandshake()
                s.soTimeout = 0
                return s
            } catch (e: Throwable) {
                Log.i(oznaka, "$n:$vrata ni dosegljiv: ${e.message}")
                try { goli.close() } catch (_: Throwable) { }
            }
        }
        return null
    }
}
