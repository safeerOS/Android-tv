package si.safeer.tv.os

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * Domaci zaslon je en sam. Ko uporabnik v Safeer Browserju izbere "Dodaj na domaci zaslon", mora
 * spletna aplikacija pristati v Safeer OS - v **drugi** aplikaciji. Ta ponudnik je tista vrata:
 * odprt je samo aplikacijam z istim podpisom (dovoljenje si.safeer.tv.permission.LINK) in zna
 * natanko tisto, kar brskalnik potrebuje.
 *
 * Ponudnik obstaja samo v Safeer OS (okusi/os/AndroidManifest.xml). Ce Safeer OS ni namescen,
 * brskalnik seznam vodi sam - takrat je domaci zaslon televizorja njegov.
 */
class SpletnePonudnik : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(metoda: String, arg: String?, dodatki: Bundle?): Bundle? {
        val c = context ?: return null
        val url = arg.orEmpty()
        val odgovor = Bundle()
        try {
            when (metoda) {
                SEZNAM -> odgovor.putString("seznam", SpletneAplikacije.jsonSeznam(c))
                JE_DODANA -> odgovor.putBoolean("je", SpletneAplikacije.jeDodana(c, url))
                NADALJEVANJE -> odgovor.putString("url", SpletneAplikacije.nadaljevanje(c, url))
                ZAPOMNI -> SpletneAplikacije.zapomniMesto(c, url, dodatki?.getString("zadnji").orEmpty())
                // Brskalnik ob vklopu televizorja vprasa, ali naj odpre Safeer OS (os/VklopTelevizorja).
                ZAGON_OB_VKLOPU -> odgovor.putBoolean("je", ZagonOb.jeVklopljen(c))
                DODAJ -> {
                    SpletneAplikacije.dodaj(c, url, dodatki?.getString("ime").orEmpty()) {
                        try { DomacaVrsta.osvezi(c) } catch (e: Throwable) { Log.w(TAG, "Vrste ni bilo mogoce osveziti: ${e.message}") }
                    }
                    odgovor.putBoolean("je", true)
                }
                ODSTRANI -> SpletneAplikacije.odstrani(c, url)
                PREMAKNI -> {
                    val premaknjeno = SpletneAplikacije.premakni(c, url, dodatki?.getInt("zamik") ?: 0)
                    if (premaknjeno) try { DomacaVrsta.osvezi(c) } catch (e: Throwable) { Log.w(TAG, "Vrste ni bilo mogoce osveziti: ${e.message}") }
                    odgovor.putBoolean("je", premaknjeno)
                }
                else -> return null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Zahteve '$metoda' ni bilo mogoce izpolniti: ${e.message}")
            return null
        }
        return odgovor
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, so: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, sa: Array<out String>?): Int = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, sa: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "SafeerSpletnePonudnik"
        const val SEZNAM = "seznam"
        const val JE_DODANA = "je_dodana"
        const val NADALJEVANJE = "nadaljevanje"
        const val ZAPOMNI = "zapomni"
        const val DODAJ = "dodaj"
        const val ODSTRANI = "odstrani"
        const val PREMAKNI = "premakni"
        const val ZAGON_OB_VKLOPU = "zagon_ob_vklopu"

        fun naslov(paket: String): Uri = Uri.parse("content://$paket.spletne")

        /**
         * Poklici Safeer OS, ce je namescen kot svoja aplikacija. Vrne null, kadar ga ni ali kadar
         * klic ne uspe - takrat klicatelj naredi isto pri sebi.
         */
        fun klic(c: Context, metoda: String, arg: String, dodatki: Bundle? = null): Bundle? {
            val paket = Sosed.os(c) ?: return null
            return try {
                c.applicationContext.contentResolver.call(naslov(paket), metoda, arg, dodatki)
            } catch (e: Throwable) {
                Log.w(TAG, "Safeer OS ni odgovoril ($metoda): ${e.message}"); null
            }
        }
    }
}
