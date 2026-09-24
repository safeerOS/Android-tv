package si.safeer.tv.cast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Po vklopu televizorja in po posodobitvi aplikacije znova zazene Safeer Link, ce ga je
 * uporabnik prizgal.
 *
 * Brez tega bi moral uporabnik po vsakem izklopu televizorja (ali po namestitvi nove razlicice,
 * ki proces ustavi) odpreti brskalnik, preden bi telefon spet nasel zaslon. Ce Link ni prizgan,
 * ta prejemnik ne naredi nicesar.
 *
 * Safeer Browser TV in Safeer OS sta na istem televizorju lahko obe namesceni (glej Sosed.kt):
 * sredisce Safeer Linka je eno samo in je vedno brskalnikovo. Ce ta prejemnik tece znotraj
 * Safeer OS, brskalnik pa je na tej napravi tudi namescen, svojega sredisca zato NE zaganjamo,
 * tudi ce je Safeer OS nekoc prej (npr. pred namestitvijo brskalnika) svoj Link ze prizgal -
 * sicer bi po vsakem vklopu ali posodobitvi na istem televizorju znova tekli dve srediscu, ki bi
 * si morali deliti ista vrata in bi se izmenoma umikali eno drugemu (glej HubKrmilnik.izvolitev).
 */
class ZagonPrejemnik : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val akcija = intent?.action ?: return
        if (akcija != Intent.ACTION_BOOT_COMPLETED &&
            akcija != "android.intent.action.QUICKBOOT_POWERON" &&
            akcija != "com.htc.intent.action.QUICKBOOT_POWERON" &&
            akcija != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val app = context?.applicationContext ?: return
        try {
            if (si.safeer.tv.os.Sosed.brskalnik(app) != null) {
                Log.i("SafeerHubZagon", "Sredisca ne zaganjam ob zagonu: na tej napravi ga vodi brskalnik.")
            } else {
                HubStoritev.zagotovi(app)
            }
        } catch (e: Throwable) {
            Log.w("SafeerHubZagon", "Zagona po vklopu ni bilo mogoce izvesti: ${e.message}")
        }
        // Safeer Scit (filter DNS za ves televizor) se po vklopu / posodobitvi zazene sam, ce je vklopljen.
        try {
            si.safeer.tv.scit.Scit.zagotovi(app)
        } catch (e: Throwable) {
            Log.w("SafeerScit", "Zagona Scita po vklopu ni bilo mogoce izvesti: ${e.message}")
        }
    }
}
