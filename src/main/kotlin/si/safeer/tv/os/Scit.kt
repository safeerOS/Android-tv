package si.safeer.tv.os

import android.content.Context
import android.content.Intent
import android.util.Log
import si.safeer.tv.scit.ScitActivity

/** Safeer Scit (filter DNS za ves televizor) za domaci zaslon: neposredni klici, brez mostu med aplikacijami. */
object Scit {
    private const val TAG = "SafeerOsScit"

    class Stanje(val naVoljo: Boolean, val vklopljen: Boolean, val pripravljen: Boolean, val tece: Boolean,
                 val blokiranih: Long, val domen: Int, val potrebujeOkno: Boolean)

    fun stanje(context: Context, naprej: (Stanje) -> Unit) = naprej(preberi(context, false))

    fun vklopi(context: Context, naprej: (Stanje) -> Unit) {
        val ok = si.safeer.tv.scit.Scit.vklopi(context)
        naprej(preberi(context, !ok))
    }

    fun izklopi(context: Context, naprej: (Stanje) -> Unit) {
        si.safeer.tv.scit.Scit.izklopi(context)
        naprej(preberi(context, false))
    }

    /** Sistemsko okno z dovoljenjem za VPN (prvic); po vrnitvi klicatelj znova prebere stanje. */
    fun odpriVklop(context: Context) {
        try { context.startActivity(Intent(context, ScitActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Throwable) { Log.w(TAG, "Okna za vklop ni mogoce odpreti: ${e.message}") }
    }

    private fun preberi(context: Context, potrebujeOkno: Boolean): Stanje {
        val s = si.safeer.tv.scit.Scit.statistika(context)
        return Stanje(true, si.safeer.tv.scit.Scit.jeVklopljen(context), si.safeer.tv.scit.Scit.jePripravljen(context),
            s.stanje == "tece", s.blokiranih, s.domen, potrebujeOkno)
    }
}
