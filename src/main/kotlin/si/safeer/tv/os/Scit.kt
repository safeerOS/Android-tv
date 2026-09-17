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
 * Filter je na televizorju lahko samo eden - Android da tunel eni sami aplikaciji. Zato: ce je
 * Safeer Browser namescen, je Scit njegov in Safeer OS ga samo kaze in preklaplja prek storitve
 * SCIT_SORODNIK (dovoljenje istega podpisa); sistemsko okno z dovoljenjem za VPN odpre brskalnik.
 * Ce brskalnika ni, Safeer OS pozene svoj Scit - koda je v obeh aplikacijah.
 */
object Scit {
    private const val TAG = "SafeerOsScit"

    class Stanje(val naVoljo: Boolean, val vklopljen: Boolean, val pripravljen: Boolean, val tece: Boolean,
                 val blokiranih: Long, val domen: Int, val potrebujeOkno: Boolean)

    fun stanje(context: Context, naprej: (Stanje) -> Unit) {
        val brskalnik = Sosed.brskalnik(context)
        if (brskalnik != null) prekBrskalnika(context, brskalnik, ScitSorodnikStoritev.STANJE, naprej)
        else naprej(preberi(context, false))
    }

    fun vklopi(context: Context, naprej: (Stanje) -> Unit) {
        val brskalnik = Sosed.brskalnik(context)
        if (brskalnik != null) { prekBrskalnika(context, brskalnik, ScitSorodnikStoritev.VKLOPI, naprej); return }
        val ok = si.safeer.tv.scit.Scit.vklopi(context)
        naprej(preberi(context, !ok))
    }

    fun izklopi(context: Context, naprej: (Stanje) -> Unit) {
        val brskalnik = Sosed.brskalnik(context)
        if (brskalnik != null) { prekBrskalnika(context, brskalnik, ScitSorodnikStoritev.IZKLOPI, naprej); return }
        si.safeer.tv.scit.Scit.izklopi(context)
        naprej(preberi(context, false))
    }

    /** Sistemsko okno z dovoljenjem za VPN (prvic); po vrnitvi klicatelj znova prebere stanje. */
    fun odpriVklop(context: Context) {
        val brskalnik = Sosed.brskalnik(context)
        val namera = if (brskalnik != null)
            Intent().setComponent(ComponentName(brskalnik, "si.safeer.tv.scit.ScitActivity"))
        else Intent(context, ScitActivity::class.java)
        try { context.startActivity(namera.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Throwable) { Log.w(TAG, "Okna za vklop ni mogoce odpreti: ${e.message}") }
    }

    /** Ali Scit tece v sosednji aplikaciji (takrat ga ne kaze vklapljati se pri nas). */
    fun jeVklopljen(context: Context): Boolean =
        if (Sosed.brskalnik(context) != null) zadnjeVklopljeno
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
