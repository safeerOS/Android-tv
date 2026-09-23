package si.safeer.tv.os

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import si.safeer.tv.cast.HubKrmilnik
import si.safeer.tv.cast.HubStoritev
import si.safeer.tv.cast.HubTls
import si.safeer.tv.cast.HubUsmerjevalnik
import si.safeer.tv.cast.LinkSorodnikStoritev

/**
 * Poverilnice Safeer Linka za domaci zaslon Safeer OS.
 *
 *  - **Brskalnik je namescen**: zeton damo pobrati pri njem (storitev LINK_SORODNIK, dovoljenje
 *    istega podpisa). Sredisce Safeer Linka na televizorju je eno samo in nove povezave ni -
 *    Safeer OS vstopi vanj kot sorodnik `<id sredisca>-os`, tako kot Safeer Control na racunalniku.
 *  - **Brskalnika ni**: Safeer OS ima isto kodo in sredisce zaene sam.
 */
object Sorodnik {
    private const val TAG = "SafeerOsSorodnik"

    data class Poverilnice(val hubUrl: String, val zeton: String, val odtis: String, val hubId: String)

    /**
     * [dovoliZagon] = uporabnik je izbral Safeer Link: ce sredisce ne tece, ga prizgemo (svojega ali
     * brskalnikovega). Sicer poverilnice damo samo, kadar sredisce ze tece - Safeer OS sam po sebi
     * nicesar ne prizge.
     */
    fun zahtevaj(context: Context, dovoliZagon: Boolean = false, naprej: (Poverilnice?) -> Unit) {
        val app = context.applicationContext
        // Uporabnik je host postavil ven iz hise (svoj strezniku v oblaku): sredisce je tam,
        // poverilnice pa smo dobili ob seznanitvi s kodo. Doma zato nicesar ne zaganjamo.
        Host.poverilnice(app)?.let { if (Host.jeOddaljen(app)) { naprej(it); return } }
        val brskalnik = Sosed.brskalnik(app)
        if (brskalnik != null) {
            prekBrskalnika(app, brskalnik, dovoliZagon, naprej)
            return
        }
        Thread({
            val p = try { vProcesu(app, dovoliZagon) } catch (e: Throwable) { Log.w(TAG, "Poverilnic ni bilo mogoce pripraviti: ${e.message}"); null }
            Handler(Looper.getMainLooper()).post { naprej(p) }
        }, "safeer-os-sorodnik").start()
    }

    /**
     * Ce je brskalnik namescen, je sredisce njegovo. Drugega ne zaganjamo niti takrat, kadar ne
     * odgovori: dve srediscu na istem televizorju bi pomenili dve napravi v Safeer Linku.
     */
    private fun prekBrskalnika(app: Context, paket: String, dovoliZagon: Boolean, naprej: (Poverilnice?) -> Unit) {
        val podatki = Bundle().apply {
            putString("device_name", "Safeer OS")
            putString("app", app.packageName)
            // Nas id iz nasega kljuca (drug proces, drug kljuc kot brskalnik): zeton naj bo izdan njemu.
            putString("device_id", Identiteta.id(app))
            putBoolean("ne_zaganjaj", !dovoliZagon)
        }
        Sosed.poslji(app, paket, LinkSorodnikStoritev.DEJANJE, LinkSorodnikStoritev.ZAHTEVA,
            LinkSorodnikStoritev.ODGOVOR, podatki) { b ->
            val p = izBundla(b)
            if (p == null) Log.w(TAG, "Brskalnik ni dal zetona (ni odgovora ali je nepopoln).")
            naprej(p)
        }
    }

    private fun izBundla(b: Bundle?): Poverilnice? {
        if (b == null) return null
        val hub = b.getString("hub_url").orEmpty()
        val zeton = b.getString("token").orEmpty()
        val odtis = b.getString("fp").orEmpty()
        // Zeton je lahko prazen: pri izvoljenem hubu (drug clan kroga) se prijavimo s podpisom kljuca.
        if (!hub.startsWith("wss://") || odtis.isBlank() || (zeton.isBlank() && b.getString("hub_id").isNullOrBlank())) return null
        return Poverilnice(hub, zeton, odtis, b.getString("hub_id").orEmpty())
    }

    private fun vProcesu(app: Context, dovoliZagon: Boolean): Poverilnice? {
        if (!HubKrmilnik.tece()) {
            // Umaknili smo se izvoljenemu hubu: tja, s podpisom kljuca (zeton ni potreben) - a samo,
            // ce se ta izvoljeni hub se sploh oglasa; ce ne, kazalca nase ne obdrzimo za vedno.
            val izvoljeni = HubKrmilnik.izvoljeniHub(app)
            if (izvoljeni != null) {
                if (HubKrmilnik.izvoljeniDosegljiv(app)) return Poverilnice(izvoljeni.naslov, "", izvoljeni.odtis, izvoljeni.id)
                Log.w(TAG, "Izvoljeni hub ${izvoljeni.id} (${izvoljeni.naslov}) ni dosegljiv; pozabljam in gostim sam.")
                HubKrmilnik.pozabiIzvoljenegaHuba(app)
            }
            if (!dovoliZagon) return null
            HubKrmilnik.zazeni(app, zapomni = true)
            HubStoritev.zagotovi(app)
        }
        val u = HubKrmilnik.usmerjevalnik ?: return null
        val vrata = HubKrmilnik.vrata()
        if (vrata == 0) return null
        val id = Identiteta.id(app)
        val zeton = u.zagotoviLastniZeton(id, "Safeer OS (" + android.os.Build.MODEL + ")")
        return Poverilnice("wss://127.0.0.1:$vrata/cast/ws", zeton, HubTls.lastniOdtis(), HubUsmerjevalnik.IDENTITETA_HUBA)
    }
}