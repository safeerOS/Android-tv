package si.safeer.tv.tablica

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.NetworkInterface

/**
 * Safeer Link sredisca v tej hisi (televizor, telefon, racunalnik), ki jim se tablica lahko
 * pridruzi.
 *
 * Tablica ima svoje sredisce, a v njem ni nikogar: racunalnik je seznanjen s televizorjem. Zato
 * tablica poisce druga sredisca v omrezju (mDNS, `_safeercast._tcp`) in uporabniku ponudi, da se
 * pridruzi tistemu, kjer ze so njegove naprave. Oglas sam ne dobi nobenega zaupanja: tega da sele
 * seznanitev s kodo, ki jo pokaze sredisce na svojem zaslonu.
 */
object IskanjeHubov {

    private const val TAG = "SafeerTabletHubi"
    private const val VRSTA = "_safeercast._tcp."

    /** Najdeno sredisce: ime (npr. "Dnevna soba"), naslov WebSocketa in informativni odtis. */
    data class Hub(val ime: String, val naslov: String, val odtis: String)

    /** Naslovi te naprave: lastnega sredisca uporabniku ne ponujamo. */
    private fun lastniNaslovi(): Set<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .toSet()
    } catch (_: Throwable) { emptySet() }

    /**
     * Isce [casMs] milisekund in nato enkrat poklice [izid] na glavni niti s seznamom (lahko
     * praznim). mDNS se v vsakem primeru ustavi - iskanje, ki tece ves cas, je davek na baterijo.
     */
    fun najdi(context: Context, casMs: Long = 4_000, izid: (List<Hub>) -> Unit) {
        val app = context.applicationContext
        val glavna = Handler(Looper.getMainLooper())
        val nsd = app.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) { glavna.post { izid(emptyList()) }; return }
        val lastni = lastniNaslovi()
        val najdeni = LinkedHashMap<String, Hub>()
        var koncano = false
        val zaklep = try {
            (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("safeer-tablica-hubi")?.apply { setReferenceCounted(false); acquire() }
        } catch (_: Throwable) { null }

        var poslusalec: NsdManager.DiscoveryListener? = null
        fun konec() {
            if (koncano) return
            koncano = true
            try { poslusalec?.let { nsd.stopServiceDiscovery(it) } } catch (_: Throwable) { }
            try { zaklep?.release() } catch (_: Throwable) { }
            val seznam = synchronized(najdeni) { najdeni.values.toList() }
            izid(seznam)
        }

        fun razresevalec() = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, napaka: Int) { }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val gostitelj = info.host?.hostAddress?.substringBefore('%') ?: return
                if (gostitelj in lastni) return
                val a = info.attributes ?: emptyMap<String, ByteArray>()
                if (a["tls"]?.toString(Charsets.UTF_8) != "1") return      // brez TLS ne
                val pot = a["ws"]?.toString(Charsets.UTF_8) ?: "/cast/ws"
                val ime = a["name"]?.toString(Charsets.UTF_8)?.ifBlank { null } ?: info.serviceName.orEmpty()
                val odtis = a["fp"]?.toString(Charsets.UTF_8).orEmpty()
                val hub = Hub(ime, "wss://$gostitelj:${info.port}$pot", odtis)
                // Isto sredisce se oglasi na vec vmesnikih: kljuc je odtis (ali naslov).
                synchronized(najdeni) { najdeni[odtis.ifBlank { hub.naslov }] = hub }
                Log.i(TAG, "Sredisce: $ime na $gostitelj")
            }
        }

        poslusalec = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(vrsta: String) { }
            override fun onServiceFound(info: NsdServiceInfo) {
                try {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, razresevalec())
                } catch (_: Throwable) { }
            }
            override fun onServiceLost(info: NsdServiceInfo) { }
            override fun onDiscoveryStopped(vrsta: String) { }
            override fun onStartDiscoveryFailed(vrsta: String, napaka: Int) { glavna.post { konec() } }
            override fun onStopDiscoveryFailed(vrsta: String, napaka: Int) { }
        }
        try {
            nsd.discoverServices(VRSTA, NsdManager.PROTOCOL_DNS_SD, poslusalec)
        } catch (_: Throwable) {
            glavna.post { konec() }
            return
        }
        glavna.postDelayed({ konec() }, casMs)
    }
}
