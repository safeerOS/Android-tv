package si.safeer.tv.cast

import android.content.Context
import android.os.Bundle
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * QR koda, s katero se telefon ali tablica pridruzi srediscu na tem televizorju (prijavno okno
 * Safeer OS). Tece v procesu sredisca (brskalnik ali Safeer OS brez brskalnika); Safeer OS jo dobi
 * prek LinkSorodnikStoritev (dovoljenje istega podpisa), nikoli po omrezju.
 *
 * Povezava v kodi: http://<naslov>:<spletna vrata>/#j=<id>&s=<skrivnost>&f=<odtis potrdila>&a=<naslov:vrata>
 * - stran spletnega odjemalca na tem srediscu (telefon brez Safeerja dela v brskalniku, telefon s Safeerjem
 * jo odpre v aplikaciji). Skrivnost je za #, zato je streznik nikoli ne vidi. Brez spletnih vrat (zasedena)
 * ostane stara oblika https://safeer.si/p#..., ki jo razume samo aplikacija.
 */
object PridruzitevSredisca {
    private const val TAG = "SafeerPridruzitev"

    /** Sredisce smo prizgali samo zaradi kode; ce uporabnik izbere »brez povezave«, ga ugasnemo. */
    @Volatile var zagnanoZaKodo = false

    /** Zadnja pridruzitev: (cas v ms, ime naprave). Zaslon jo pokaze kot »povezano«. */
    @Volatile var zadnja: Pair<Long, String>? = null

    /** Nova koda (in preklic prejsnje). Bundle: povezava, napaka. */
    fun nova(context: Context, preklici: String): Bundle {
        val app = context.applicationContext
        val b = Bundle()
        try {
            if (!HubKrmilnik.tece()) {
                if (HubKrmilnik.izvoljeniHub(app) != null) {
                    // Sredisce je na drugi napravi (clan kroga): pridruzitev gre tam.
                    b.putString("napaka", "drugo_sredisce"); return b
                }
                HubKrmilnik.zazeni(app, zapomni = true)
                HubStoritev.zagotovi(app)
                zagnanoZaKodo = true
            }
            val u = HubKrmilnik.usmerjevalnik
            val vrata = HubKrmilnik.vrata()
            val ip = krajevniNaslov()
            if (u == null || vrata == 0 || ip == null) { b.putString("napaka", "ni_sredisca"); return b }
            if (preklici.isNotBlank()) u.prekliciPridruzitev(preklici)
            u.naPridruzitev = { id, ime ->
                zadnja = System.currentTimeMillis() to ime
                Log.i(TAG, "Naprava $id se je pridruzila s QR kodo.")
            }
            val (id, skrivnost) = u.ustvariPridruzitev()
            b.putString("qr_id", id)
            b.putString("povezava", povezavaZaKodo(ip, vrata, HubKrmilnik.vrataSplet(), id, skrivnost))
            b.putLong("velja_ms", HubUsmerjevalnik.PIN_VELJA_MS)
        } catch (e: Throwable) {
            Log.w(TAG, "Kode ni bilo mogoce pripraviti: ${e.message}")
            b.putString("napaka", "ni_sredisca")
        }
        return b
    }

    fun povezavaZaKodo(ip: String, vrata: Int, spletnaVrata: Int, id: String, skrivnost: String): String {
        val rep = "#j=$id&s=$skrivnost&f=${HubTls.lastniOdtis()}&a=$ip:$vrata"
        return if (spletnaVrata > 0) "http://$ip:$spletnaVrata/$rep" else "https://safeer.si/p$rep"
    }

    fun preklici(id: String) {
        try { if (id.isNotBlank()) HubKrmilnik.usmerjevalnik?.prekliciPridruzitev(id) } catch (_: Throwable) { }
    }

    /** Uporabnik je izbral »brez povezave«: sredisce, ki smo ga prizgali samo za kodo, ugasnemo. */
    fun izklopiCeSamoZaKodo(context: Context) {
        if (!zagnanoZaKodo) return
        zagnanoZaKodo = false
        try { HubStoritev.izklopi(context.applicationContext) } catch (_: Throwable) { }
    }

    /** IPv4 naslov televizorja v domacem omrezju (telefon ga uporabi kot namig; odtis potrdi sredisce). */
    fun krajevniNaslov(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    } catch (_: Throwable) { null }
}
