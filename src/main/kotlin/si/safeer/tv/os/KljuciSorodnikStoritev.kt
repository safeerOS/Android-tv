package si.safeer.tv.os

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log

/**
 * Lastnik 4. 11. 2026 (05:10): »Ce ga vnesem v Safeer OS, velja tudi za predvajalnik.« Izmenjava kljucev (debrid, Usenet,
 * indeksator, dodatki s poverilnico, racuni IPTV) med sorodnimi aplikacijami Safeer na ISTI napravi. Storitev je zascitena
 * z dovoljenjem istega podpisa (si.safeer.tv.permission.LINK, manifest), klicatelja preverimo se enkrat po uid sporocila
 * in podpisu paketa ([UskladitevKljucevPravila.jeSorodnik]). Vrednosti so samo v Binder sporocilu med aplikacijama - nikoli
 * v namerah, ki jih vidi sistem, in nikoli v dnevniku.
 */
class KljuciSorodnikStoritev : Service() {
    private val nit = HandlerThread("safeer-kljuci-sorodnik-streznik").apply { start() }

    private val odzivnik = Messenger(Handler(nit.looper) { m ->
        if (m.what != IZMENJAJ) return@Handler true
        val paket = try { packageManager.getPackagesForUid(m.sendingUid)?.firstOrNull { it in UskladitevKljucevPravila.SORODNIKI } } catch (_: Throwable) { null }
        if (!UskladitevKljucevPravila.jeSorodnik(paket, packageName, paket != null && UskladitevKljucev.podpisEnak(this, paket))) {
            Log.w(TAG, "zavrnjeno: klicatelj ni sorodna aplikacija Safeer z istim podpisom")
            return@Handler true
        }
        if (!UskladitevKljucev.vklopljeno(this)) return@Handler true
        val komu = m.replyTo
        val prejeto = m.data?.getString(ZAPISI)
        try {
            if (prejeto != null && paket != null) UskladitevKljucev.prejmiOdSorodnika(this, paket, prejeto)
            val odgovor = Message.obtain(null, ODGOVOR).apply { data = Bundle().apply { putString(ZAPISI, UskladitevKljucev.zaSorodnika(this@KljuciSorodnikStoritev)) } }
            komu?.send(odgovor)
        } catch (e: Throwable) { Log.w(TAG, "izmenjava: ${e.javaClass.simpleName}") }
        true
    })

    override fun onBind(intent: Intent?): IBinder? = if (intent?.action == DEJANJE) odzivnik.binder else null

    override fun onDestroy() { nit.quitSafely(); super.onDestroy() }

    companion object {
        const val DEJANJE = "si.safeer.tv.KLJUCI_SORODNIK"
        const val IZMENJAJ = 1
        const val ODGOVOR = 10
        const val ZAPISI = "zapisi"
        private const val TAG = "SafeerKljuciSorodnik"
    }
}
