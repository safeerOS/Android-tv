package si.safeer.tv.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.ConcurrentHashMap

/**
 * Androidni vir internetnih poti za Safeer OS Mobile.
 * Ne vklaplja tetheringa in ne spreminja sistemskega default networka. Safeer lahko uporabi samo
 * vticnice, ki jih odpre sam in jih pred povezavo priveze na izbrano omrezje (`Network.bindSocket`).
 * Zato telefon z racunalnikom se naprej govori po Wi-Fi (Safeer Link), tok, ki ga racunalnik poslje
 * skozi telefon, pa zapusti telefon po mobilnem omrezju.
 *
 * Mobilno omrezje Android ob delujocem Wi-Fi ugasne. Zahtevamo ga, ko ga tok potrebuje
 * ([pocakajMobilno]), in ga [MOBILNA_OSTANE_MS] po zadnjem toku sprostimo - prej je bila zahteva
 * odprta ves cas, kar po nepotrebnem drzi mobilni del radia buden.
 */
class AndroidInternetPoti(context: Context) : AutoCloseable {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val omrezja = ConcurrentHashMap<Network, InternetPot>()
    private val kljucnica = Object()
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var zadnjaRabaMobilne = 0L

    fun zaznane(): List<InternetPot> {
        osveziZnanaOmrezja()
        return omrezja.values.sortedBy { it.id }
    }

    /** Zahteva mobilno pot ob aktivnem Wi-Fi. Klici sele po izrecnem dovoljenju uporabnika v Safeer UI. */
    fun zahtevajMobilno() {
        synchronized(kljucnica) {
            zadnjaRabaMobilne = System.currentTimeMillis()
            if (cellularCallback != null) return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { dodaj(network); prebudi() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { dodaj(network, caps); prebudi() }
                override fun onLost(network: Network) { omrezja.remove(network) }
            }
            cellularCallback = cb
            try { cm.requestNetwork(request, cb) } catch (t: Throwable) {
                cellularCallback = null
                throw t
            }
        }
    }

    private fun prebudi() = synchronized(kljucnica) { kljucnica.notifyAll() }

    /**
     * Zahteva mobilno omrezje in pocaka, da ga Android vzpostavi (ob delujocem Wi-Fi traja to sekundo
     * ali dve). Vrne false, ce ga v [rokMs] ni: ni kartice SIM, mobilni podatki so izklopljeni, ni signala.
     */
    fun pocakajMobilno(rokMs: Long): Boolean {
        try { zahtevajMobilno() } catch (_: Throwable) { return false }
        val konec = System.currentTimeMillis() + rokMs
        synchronized(kljucnica) {
            while (true) {
                if (zaznane().any { it.vrsta == VrstaInternetPoti.CELLULAR && it.dosegljiva }) return true
                val ostane = konec - System.currentTimeMillis()
                if (ostane <= 0) return false
                kljucnica.wait(minOf(ostane, 500L))
            }
        }
    }

    /** Mobilni tok tece ali je pravkar koncal: zahteva ostane odprta. */
    fun mobilnaVRabi() { zadnjaRabaMobilne = System.currentTimeMillis() }

    /** Sprosti mobilno zahtevo, ce je [MOBILNA_OSTANE_MS] nihce ni rabil. Klice prehod, ko nima mobilnih tokov. */
    fun sprostiNerabljenoMobilno() {
        if (cellularCallback != null && System.currentTimeMillis() - zadnjaRabaMobilne > MOBILNA_OSTANE_MS) sprostiMobilno()
    }

    fun sprostiMobilno() {
        val cb = synchronized(kljucnica) { cellularCallback.also { cellularCallback = null } } ?: return
        try { cm.unregisterNetworkCallback(cb) } catch (_: IllegalArgumentException) { }
        osveziZnanaOmrezja()
    }

    fun omrezje(idPoti: String): Network? = omrezja.keys.firstOrNull { id(it) == idPoti }

    private fun osveziZnanaOmrezja() {
        @Suppress("DEPRECATION")
        val trenutna = cm.allNetworks.toSet()
        omrezja.keys.removeIf { it !in trenutna }
        trenutna.forEach(::dodaj)
    }

    private fun dodaj(network: Network, caps: NetworkCapabilities? = cm.getNetworkCapabilities(network)) {
        caps ?: return
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
        val vrsta = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> VrstaInternetPoti.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> VrstaInternetPoti.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> VrstaInternetPoti.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> VrstaInternetPoti.ETHERNET
            else -> VrstaInternetPoti.DRUGO
        }
        omrezja[network] = InternetPot(
            id = id(network), vrsta = vrsta,
            dosegljiva = true,
            merjena = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            roaming = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
            // Wi-Fi brez interneta (izpad doma) ostane povezan, a ni preverjen: po njem ne posiljamo.
            preverjena = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        )
    }

    private fun id(network: Network): String = "android-${network.networkHandle}"
    override fun close() = sprostiMobilno()

    companion object { const val MOBILNA_OSTANE_MS = 300_000L }
}
