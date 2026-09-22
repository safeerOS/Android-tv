package si.safeer.tv.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Androidni vir internetnih poti za Safeer OS Mobile.
 * Ne vklaplja tetheringa in ne spreminja sistemskega default networka. Safeer lahko uporabi samo
 * povezave/socket-e, ki jih odpre sam. Cellular je eksplicitno zahtevan tudi ob aktivnem Wi-Fi.
 */
class AndroidInternetPoti(context: Context) : AutoCloseable {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val omrezja = ConcurrentHashMap<Network, InternetPot>()
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null

    fun zaznane(): List<InternetPot> {
        osveziZnanaOmrezja()
        return omrezja.values.sortedBy { it.id }
    }

    /** Zahteva ohranitev mobilne poti. Klici sele po izrecnem dovoljenju uporabnika v Safeer UI. */
    fun zahtevajMobilno() {
        if (cellularCallback != null) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { dodaj(network) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { dodaj(network, caps) }
            override fun onLost(network: Network) { omrezja.remove(network) }
        }
        cellularCallback = cb
        try { cm.requestNetwork(request, cb) } catch (t: Throwable) {
            cellularCallback = null
            throw t
        }
    }

    fun sprostiMobilno() {
        val cb = cellularCallback ?: return
        cellularCallback = null
        try { cm.unregisterNetworkCallback(cb) } catch (_: IllegalArgumentException) { }
        osveziZnanaOmrezja()
    }

    /** Socket mora biti se nepovezan. S tem promet tega Safeer toka gre po izbrani Android Network poti. */
    fun privezi(socket: Socket, idPoti: String) {
        val network = omrezja.entries.firstOrNull { id(it.key) == idPoti }?.key
            ?: throw IllegalArgumentException("Internetna pot ni vec dosegljiva: $idPoti")
        network.bindSocket(socket)
    }

    fun omrezje(idPoti: String): Network? = omrezja.keys.firstOrNull { id(it) == idPoti }

    private fun osveziZnanaOmrezja() {
        val trenutna = cm.allNetworks.toSet()
        omrezja.keys.removeIf { it !in trenutna }
        trenutna.forEach(::dodaj)
    }

    private fun dodaj(network: Network, caps: NetworkCapabilities? = cm.getNetworkCapabilities(network)) {
        caps ?: return
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
        val vrsta = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> VrstaInternetPoti.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> VrstaInternetPoti.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> VrstaInternetPoti.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> VrstaInternetPoti.VPN
            else -> VrstaInternetPoti.DRUGO
        }
        omrezja[network] = InternetPot(
            id = id(network), vrsta = vrsta,
            dosegljiva = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            merjena = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            roaming = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        )
    }

    private fun id(network: Network): String = "android-${network.networkHandle}"
    override fun close() = sprostiMobilno()
}
