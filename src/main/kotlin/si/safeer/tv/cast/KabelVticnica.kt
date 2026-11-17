package si.safeer.tv.cast

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.Socket

/**
 * Vticnica za naslov na podomrezju Etherneta (samo TV; ni del Link Core).
 *
 * Izmerjeno 17. 11. 2026: TV s Scitom (VPN) vse vticnice aplikacije usmeri v privzeto omrezje, tabela poti tega omrezja
 * pa podomrezja kabla ne pozna - povezava na naslov kabla je obvisela (4 s), med tem je zmagala pot prek Huba
 * (pot=global) in miska je zaostajala. Vezava vticnice na omrezje Ethernet (Network.bindSocket) uporabi tabelo poti
 * eth0, kjer je podomrezje kabla neposredno dosegljivo. Naslovi zunaj kabla ostanejo navadne vticnice (Wi-Fi, Scit).
 */
internal object KabelVticnica {
    @Suppress("DEPRECATION")
    private fun zicnoOmrezje(cm: ConnectivityManager): Network? = try {
        cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true }
    } catch (_: Throwable) { null }

    /**
     * Tovarna vticnic za [NeposrednaPovezava.povezi]. Naslov na kablu brez omrezja Ethernet ali z neuspelo vezavo da
     * izjemo, naslov preskocimo takoj (Wi-Fi je rezerva) in ne cakamo roka.
     */
    fun za(cm: ConnectivityManager?, zicna: List<KabelNaslovi.Podomrezje>): (String) -> Socket = { naslov ->
        val s = Socket()
        if (zicna.any { KabelNaslovi.naPodomrezju(naslov, it) }) {
            try {
                val omrezje = (if (cm == null) null else zicnoOmrezje(cm)) ?: throw IOException("omrezja Ethernet ni")
                omrezje.bindSocket(s)
            } catch (e: Throwable) {
                try { s.close() } catch (_: Throwable) { }
                throw e
            }
        }
        s
    }
}
