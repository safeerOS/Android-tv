package si.safeer.tv.link

import android.content.Context
import android.net.Network

/** Nadomestek poti telefona: ena preverjena pot Wi-Fi. */
class AndroidInternetPoti(context: Context) : AutoCloseable {
    private val omrezje = Network()
    fun zaznane(): List<InternetPot> = listOf(InternetPot("android-1", VrstaInternetPoti.WIFI, dosegljiva = true, merjena = false))
    fun omrezje(idPoti: String): Network? = if (idPoti == "android-1") omrezje else null
    fun pocakajMobilno(rokMs: Long): Boolean = false
    fun mobilnaVRabi() { }
    fun sprostiNerabljenoMobilno() { }
    fun sprostiMobilno() { }
    override fun close() { }
    companion object { const val MOBILNA_OSTANE_MS = 300_000L }
}

/** Nadomestek vprasanja uporabniku: preizkus prebere, za koga bi se pokazalo. */
object InternetVprasanje {
    val pokazana = java.util.concurrent.CopyOnWriteArrayList<String>()
    fun pokazi(ctx: Context, naprava: String, ime: String) { pokazana.add(naprava) }
}
