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
import si.safeer.tv.os.LinaVmesnikPravila as P

/**
 * Lokalni vmesnik Safeer OS za Lino (lastnik, 19. 11. 2026): zapiski in kljuci asistenta skupaj. Neobvezen - Lina brez
 * Safeer OS deluje sama. Zasciten z dovoljenjem istega podpisa (si.safeer.tv.permission.LINK, manifest) in se enkrat po
 * uid sporocila in podpisu paketa ([P.jeLina]); drug klicatelj ne dobi odgovora. Sproziti ga ne more besedilo klica ali
 * sporocila: vsak klic je Binder sporocilo aplikacije Lina. Vrednosti so samo v Binder sporocilu - nikoli v namerah ali
 * dnevniku. Lina nima clanstva v krogu: kar prispeva, po krogu prenese Safeer OS (obstojeca pot).
 */
class LinaVmesnikStoritev : Service() {
    private val nit = HandlerThread("safeer-lina-vmesnik").apply { start() }

    private val odzivnik = Messenger(Handler(nit.looper) { m ->
        val paket = try { packageManager.getPackagesForUid(m.sendingUid)?.firstOrNull { it == P.PAKET_LINA } } catch (_: Throwable) { null }
        if (!P.jeLina(paket, packageName, paket != null && UskladitevKljucev.podpisEnak(this, paket))) {
            Log.w(TAG, "zavrnjeno: klicatelj ni Lina z istim podpisom")
            return@Handler true
        }
        val odgovor = try { obdelaj(m.what, m.data ?: Bundle()) } catch (e: Throwable) { Log.w(TAG, "napaka: ${e.javaClass.simpleName}"); null }
        if (odgovor != null) try {
            m.replyTo?.send(Message.obtain(null, ODGOVOR).apply { data = odgovor.apply { putInt(OP, m.what) } })
        } catch (e: Throwable) { Log.w(TAG, "odgovor: ${e.javaClass.simpleName}") }
        true
    })

    private fun obdelaj(op: Int, v: Bundle): Bundle? {
        val shramba = LinaVmesnikShramba.get(this)
        return when (op) {
            ZAPISKI_PREBERI -> Bundle().apply { putString(ZAPISI, P.vJson(shramba.zapiski())) }
            ZAPISKI_POSLJI -> {
                val vhod = P.izJson(v.getString(ZAPISI) ?: "")
                val prej = shramba.zapiski()
                val zdruzeno = if (vhod == null) null else shramba.sprejmi(vhod)
                if (zdruzeno != null && zdruzeno != prej) try { LinaVmesnikShramba.poSpremembi?.invoke() } catch (_: Throwable) { }
                Bundle().apply {
                    putBoolean(SPREJETO, zdruzeno != null)
                    putString(ZAPISI, P.vJson(zdruzeno ?: shramba.zapiski()))
                }
            }
            KLJUC_NASTAVI -> Bundle().apply {
                val ok = UskladitevKljucev.podZaklepom { shramba.nastaviKljuc(v.getString(IME) ?: "", v.getString(VREDNOST) ?: "") }
                if (ok) UskladitevKljucev.sprozi(this@LinaVmesnikStoritev)   // brez tega bi sprememba cakala na nakljucno naslednjo uskladitev
                putBoolean(SPREJETO, ok)
            }
            KLJUC_PREBERI -> Bundle().apply { shramba.beriKljuc(v.getString(IME) ?: "")?.let { putString(VREDNOST, it) } }
            KLJUC_IZBRISI -> Bundle().apply {
                val ok = UskladitevKljucev.podZaklepom { shramba.izbrisiKljuc(v.getString(IME) ?: "") }
                if (ok) UskladitevKljucev.sprozi(this@LinaVmesnikStoritev)
                putBoolean(SPREJETO, ok)
            }
            KLJUC_SEZNAM -> Bundle().apply { putStringArray(IMENA, shramba.imenaKljucev().toTypedArray()) }
            else -> null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = if (intent?.action == DEJANJE) odzivnik.binder else null

    override fun onDestroy() { nit.quitSafely(); super.onDestroy() }

    companion object {
        const val DEJANJE = "si.safeer.tv.LINA_VMESNIK"
        const val ZAPISKI_PREBERI = 1
        const val ZAPISKI_POSLJI = 2
        const val KLJUC_NASTAVI = 3
        const val KLJUC_PREBERI = 4
        const val KLJUC_IZBRISI = 5
        const val KLJUC_SEZNAM = 6
        const val ODGOVOR = 10
        const val OP = "op"
        const val ZAPISI = "zapisi"
        const val SPREJETO = "sprejeto"
        const val IME = "ime"
        const val VREDNOST = "vrednost"
        const val IMENA = "imena"
        private const val TAG = "SafeerLinaVmesnik"
    }
}
