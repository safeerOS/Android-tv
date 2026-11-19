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
            ZAPISKI_PREBERI -> Bundle().apply { stanje(this, shramba.zapiski()) }
            ZAPISKI_STRAN -> Bundle().apply {
                val vsi = shramba.zapiski()
                val revizija = P.revizija(vsi)
                putString(REVIZIJA, revizija)
                val zahtevana = v.getString(REVIZIJA)
                if (zahtevana != null && zahtevana != revizija) {
                    // Stanje se je spremenilo med branjem strani: indeksi ne veljajo vec, Lina bere od zacetka (brez REVIZIJE).
                    putBoolean(SPREMENJENO, true)
                    putInt(NASLEDNJI, -1)
                } else {
                    val s = P.stran(vsi, v.getInt(OD, 0))
                    putString(ZAPISI, s.json)
                    putInt(NASLEDNJI, s.naslednji)
                    putInt(IZPUSCENIH, s.izpuscenih)
                }
            }
            ZAPISKI_POSLJI -> {
                // Vhod je lahko skupina zapiskov (stran stanja): sprejmi() zdruzi z obstojecimi, zato vrstni red skupin ni pomemben.
                val vhod = (v.getString(ZAPISI) ?: "").let { if (it.length > P.BINDER_NAJVEC_ZNAKOV) null else P.izJson(it) }
                val prej = shramba.zapiski()
                val zdruzeno = if (vhod == null) null else shramba.sprejmi(vhod)
                if (zdruzeno != null && zdruzeno != prej) try { LinaVmesnikShramba.poSpremembi?.invoke() } catch (_: Throwable) { }
                Bundle().apply {
                    putBoolean(SPREJETO, zdruzeno != null)
                    stanje(this, zdruzeno ?: shramba.zapiski())
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

    /** Stanje v odgovor, ce gre v en Binder kos; sicer PREVELIKO=true in Lina bere po straneh (ZAPISKI_STRAN). */
    private fun stanje(b: Bundle, zapiski: List<P.Zapisek>) {
        val json = P.vJson(zapiski)
        if (json.length <= P.BINDER_NAJVEC_ZNAKOV) b.putString(ZAPISI, json) else b.putBoolean(PREVELIKO, true)
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
        /** Stran stanja po Binder meji: vhod OD (indeks, privzeto 0); odgovor ZAPISI + NASLEDNJI (-1 = konec) + IZPUSCENIH (zapiski, ki so za Binder preveliki; po krogu gredo normalno). */
        const val ZAPISKI_STRAN = 7
        const val ODGOVOR = 10
        const val OP = "op"
        const val ZAPISI = "zapisi"
        const val SPREJETO = "sprejeto"
        const val OD = "od"
        const val NASLEDNJI = "naslednji"
        const val PREVELIKO = "preveliko"
        const val IZPUSCENIH = "izpuscenih"
        /** Branje po straneh: odgovor nosi REVIZIJO stanja; Lina jo poslje z naslednjo stranjo, SPREMENJENO=true pomeni »zacni znova«. */
        const val REVIZIJA = "revizija"
        const val SPREMENJENO = "spremenjeno"
        const val IME = "ime"
        const val VREDNOST = "vrednost"
        const val IMENA = "imena"
        private const val TAG = "SafeerLinaVmesnik"
    }
}
