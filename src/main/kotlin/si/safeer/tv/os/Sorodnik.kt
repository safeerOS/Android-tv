package si.safeer.tv.os

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import si.safeer.tv.cast.HubKrmilnik
import si.safeer.tv.cast.HubStoritev
import si.safeer.tv.cast.HubTls
import si.safeer.tv.cast.HubUsmerjevalnik

/**
 * Poverilnice Safeer Linka za domaci zaslon Safeer OS. Safeer OS je del tega brskalnika, zato jih
 * dobi neposredno od sredisca v istem procesu (isto, kar LinkSorodnikStoritev vrne sorodnim
 * aplikacijam): naslov sredisca na tem televizorju, zeton za identiteto `<lastniId>-os`, odtis
 * potrdila. Ce Safeer Link ni prizgan, ga prizge - uporabnik je odprl Safeer OS, ki ga potrebuje.
 */
object Sorodnik {
    private const val TAG = "SafeerOsSorodnik"

    data class Poverilnice(val hubUrl: String, val zeton: String, val odtis: String, val hubId: String)

    /**
     * [dovoliZagon] = uporabnik je izbral Safeer Link: ce sredisce ne tece, ga prizgemo. Sicer
     * poverilnice damo samo, kadar sredisce ze tece - Safeer OS sam po sebi nicesar ne prizge.
     */
    fun zahtevaj(context: Context, dovoliZagon: Boolean = false, naprej: (Poverilnice?) -> Unit) {
        val app = context.applicationContext
        Thread({
            val p = try { pripravi(app, dovoliZagon) } catch (e: Throwable) { Log.w(TAG, "Poverilnic ni bilo mogoce pripraviti: ${e.message}"); null }
            Handler(Looper.getMainLooper()).post { naprej(p) }
        }, "safeer-os-sorodnik").start()
    }

    private fun pripravi(app: Context, dovoliZagon: Boolean): Poverilnice? {
        if (!HubKrmilnik.tece()) {
            if (!dovoliZagon) return null
            HubKrmilnik.zazeni(app, zapomni = true)
            HubStoritev.zagotovi(app)
        }
        val u = HubKrmilnik.usmerjevalnik ?: return null
        val vrata = HubKrmilnik.vrata()
        if (vrata == 0) return null
        val id = HubKrmilnik.lastniId() + "-os"
        val zeton = u.zagotoviLastniZeton(id, "Safeer OS (" + android.os.Build.MODEL + ")")
        return Poverilnice("wss://127.0.0.1:$vrata/cast/ws", zeton, HubTls.lastniOdtis(), HubUsmerjevalnik.IDENTITETA_HUBA)
    }
}
