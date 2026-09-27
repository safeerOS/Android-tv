package si.safeer.tv.os

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import si.safeer.tv.scit.ScitActivity
import si.safeer.tv.scit.ScitSorodnikStoritev

/**
 * Safeer Scit (filter DNS za ves televizor) za domaci zaslon Safeer OS.
 *
 * Filter je na napravi lahko samo eden - Android da tunel eni sami aplikaciji. Scit vodi tista
 * aplikacija, ki vodi Safeer Link (na televizorju Safeer OS; glej [Sosed.vodimLink]). Kadar ga vodi
 * soseda (starejsa postavitev, kjer je Safeer Browser TV se brez Safeer OS), ga Safeer OS samo kaze
 * in preklaplja prek storitve SCIT_SORODNIK. Ko Scit prevzamemo, sosedovega najprej ugasnemo: dva
 * tunela hkrati nista mogoca.
 */
object Scit {
    private const val TAG = "SafeerOsScit"

    class Stanje(val naVoljo: Boolean, val vklopljen: Boolean, val pripravljen: Boolean, val tece: Boolean,
                 val blokiranih: Long, val domen: Int, val potrebujeOkno: Boolean)

    /** Brskalnik, ki vodi Scit - samo, kadar Safeer Linka (in s tem Scita) ne vodimo mi. */
    private fun sosed(context: Context): String? = if (Sosed.vodimLink(context)) null else Sosed.brskalnik(context)

    fun stanje(context: Context, naprej: (Stanje) -> Unit) {
        val brskalnik = sosed(context)
        if (brskalnik != null) prekBrskalnika(context, brskalnik, ScitSorodnikStoritev.STANJE, naprej)
        else naprej(preberi(context, false))
    }

    fun vklopi(context: Context, naprej: (Stanje) -> Unit) {
        val brskalnik = sosed(context)
        if (brskalnik != null) { prekBrskalnika(context, brskalnik, ScitSorodnikStoritev.VKLOPI, naprej); return }
        // Scit vodimo mi: ce tece v sosedi (stara postavitev), ga tam najprej ugasnemo - en tunel naenkrat.
        Sosed.brskalnik(context)?.let { paket ->
            Sosed.poslji(context, paket, ScitSorodnikStoritev.DEJANJE, ScitSorodnikStoritev.IZKLOPI,
                ScitSorodnikStoritev.ODGOVOR, potekMs = 4000) { }
        }
        val ok = si.safeer.tv.scit.Scit.vklopi(context)
        naprej(preberi(context, !ok))
    }

    fun izklopi(context: Context, naprej: (Stanje) -> Unit) {
        val brskalnik = sosed(context)
        if (brskalnik != null) { prekBrskalnika(context, brskalnik, ScitSorodnikStoritev.IZKLOPI, naprej); return }
        si.safeer.tv.scit.Scit.izklopi(context)
        naprej(preberi(context, false))
    }

    /** Sistemsko okno z dovoljenjem za VPN (prvic); po vrnitvi klicatelj znova prebere stanje. */
    fun odpriVklop(context: Context) {
        val brskalnik = sosed(context)
        val namera = if (brskalnik != null)
            Intent().setComponent(ComponentName(brskalnik, "si.safeer.tv.scit.ScitActivity"))
        else Intent(context, ScitActivity::class.java)
        try { context.startActivity(namera.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Throwable) { Log.w(TAG, "Okna za vklop ni mogoce odpreti: ${e.message}") }
    }

    /** Ali Scit tece v sosednji aplikaciji (takrat ga ne kaze vklapljati se pri nas). */
    fun jeVklopljen(context: Context): Boolean =
        if (sosed(context) != null) zadnjeVklopljeno
        else si.safeer.tv.scit.Scit.jeVklopljen(context)

    @Volatile private var zadnjeVklopljeno = false

    private fun prekBrskalnika(context: Context, paket: String, kaj: Int, naprej: (Stanje) -> Unit) {
        Sosed.poslji(context, paket, ScitSorodnikStoritev.DEJANJE, kaj, ScitSorodnikStoritev.ODGOVOR,
            potekMs = 6000) { b ->
            if (b == null) { naprej(Stanje(false, false, false, false, 0, 0, false)); return@poslji }
            zadnjeVklopljeno = b.getBoolean("vklopljen")
            naprej(Stanje(true, b.getBoolean("vklopljen"), b.getBoolean("pripravljen"), b.getString("stanje") == "tece",
                b.getLong("blokiranih"), b.getInt("domen"), b.getBoolean("potrebuje_okno")))
        }
    }

    private fun preberi(context: Context, potrebujeOkno: Boolean): Stanje {
        val s = si.safeer.tv.scit.Scit.statistika(context)
        zadnjeVklopljeno = si.safeer.tv.scit.Scit.jeVklopljen(context)
        return Stanje(true, zadnjeVklopljeno, si.safeer.tv.scit.Scit.jePripravljen(context),
            s.stanje == "tece", s.blokiranih, s.domen, potrebujeOkno)
    }
}
