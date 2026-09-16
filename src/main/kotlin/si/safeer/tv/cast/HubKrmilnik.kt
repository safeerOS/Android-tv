package si.safeer.tv.cast

import android.content.Context
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Vklop in izklop Safeer Huba na televizorju.
 *
 * Hub ne tece kar sam od sebe. Stece, ko ga uporabnik v Safeer Linku prizge, in tece,
 * dokler ga ne ugasne ali dokler brskalnik ne konca. Zeljo si zapomnimo: kdor je Hub
 * enkrat prizgal, pricakuje, da bo televizor naslednjic spet dosegljiv, ne da bi moral
 * karkoli nastavljati.
 *
 * Zakaj Hub na televizorju ne ugasne, ko uporabnik zapre zaslon Safeer Linka, na telefonu
 * pa bo: televizor je zaslon, ki naj bo dosegljiv, medtem ko uporabnik gleda - ce bi Hub
 * ugasnil, bi telefon izgubil daljinca ravno takrat, ko ga potrebuje. Telefon pa je v roki
 * in ga nima smisla obremenjevati, ko brskalnik ni odprt.
 */
object HubKrmilnik {

    private const val TAG = "SafeerHubKrmilnik"
    const val PREFS = "safeer_cast_prefs"
    const val KLJUC_VKLOPLJEN = "hub_vklopljen"

    @Volatile
    private var streznik: HubStreznik? = null

    @Volatile
    var usmerjevalnik: HubUsmerjevalnik? = null
        private set

    @Volatile
    var tokovi: HubTokovi? = null
        private set

    fun tece(): Boolean = streznik?.teceZdaj() == true

    fun vrata(): Int = streznik?.vrata ?: 0

    /** Ali je uporabnik Hub prizgal (tudi ce trenutno ne tece, npr. pred zagonom brskalnika). */
    fun jeZazelen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KLJUC_VKLOPLJEN, false)

    private fun zapomniZeljo(context: Context, vklopljen: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KLJUC_VKLOPLJEN, vklopljen).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "Nastavitve ni bilo mogoce zapisati: ${e.message}")
        }
    }

    /** Ob zagonu brskalnika: Hub stece samo, ce ga je uporabnik ze prej prizgal. */
    fun samodejniZagon(context: Context) {
        if (jeZazelen(context) && !tece()) zazeni(context, zapomni = false)
    }

    /**
     * Zazene Hub: streznik, usmerjevalnik in objavo v omrezju. Vrne true, ce tece.
     */
    @Synchronized
    fun zazeni(context: Context, zapomni: Boolean = true): Boolean {
        val app = context.applicationContext
        if (tece()) {
            if (zapomni) zapomniZeljo(app, true)
            return true
        }

        val u = HubUsmerjevalnik(NastavitveShramba(app))
        // TLS: kljuc Huba iz Android KeyStore; odtis potrdila je vpleten v seznanjanje.
        val tls = try { HubTls.streznik() } catch (e: Throwable) {
            Log.w(TAG, "TLS Huba ni bilo mogoce pripraviti: ${e.message}")
            return false
        }
        u.lastniOdtis = HubTls.lastniOdtis()
        // Vsebina (zaslon, datoteke) gre mimo usmerjevalnika, po loceni zahtevi HTTP;
        // usmerjevalnik le pove ciljni napravi, kje jo dobi.
        val t = HubTokovi(
            mapaPrenosov = { mapaZaPrejete(app) },
            mapaZacasna = { File(app.cacheDir, "safeer-link").apply { mkdirs() } },
            jeVeljavenZeton = { zeton -> u.jeVeljavenZeton(zeton) },
            lastniId = { lastniId() },
            naPrejetoDatoteko = { ime, pot -> Log.i(TAG, "Prejeta datoteka $ime -> ${pot.parent}") }
        )
        u.tokovi = t
        val s = HubStreznik(
            naZahtevo = { zahteva -> u.odgovori(zahteva) },
            preveriVstopnico = { zahteva -> u.preveriVstopnico(zahteva) },
            naPovezavo = { povezava -> povezi(u, povezava) },
            naTok = { zahteva, vhod, izhod, vticnica -> t.obdelaj(zahteva, vhod, izhod, vticnica) },
            tlsTovarna = tls
        )
        if (!s.zazeni()) {
            Log.w(TAG, "Huba ni bilo mogoce zagnati.")
            return false
        }
        streznik = s
        usmerjevalnik = u
        tokovi = t

        // Televizor, ki gosti, je hkrati zaslon: sprejemnik se priklopi na lastni Hub, da ga
        // druge naprave vidijo kot "zaslon" in mu lahko posljejo stran. Brez tega je Hub
        // sredisce, na katerega ni mogoce nicesar poslati.
        poveziLastniZaslon(app, u, s.vrata)

        HubObjava.objavi(app, s.vrata, imeHuba()) { uspelo ->
            if (!uspelo) {
                // Brez oglasa Hub se vedno dela; naprava, ki ga je ze videla, pozna naslov.
                Log.i(TAG, "Hub tece, oglas v omrezju pa ni uspel.")
            }
        }
        if (zapomni) zapomniZeljo(app, true)
        Log.i(TAG, "Safeer Hub tece na ${naslov()}")
        return true
    }

    /** Ugasne Hub. `zapomni` naj bo true samo, kadar je tako odlocil uporabnik. */
    @Synchronized
    fun ustavi(context: Context?, zapomni: Boolean = true) {
        HubObjava.umakni()
        streznik?.ustavi()
        streznik = null
        usmerjevalnik = null
        tokovi = null
        if (context != null) odklopiLastniZaslon(context.applicationContext)
        if (zapomni && context != null) zapomniZeljo(context.applicationContext, false)
        Log.i(TAG, "Safeer Hub ustavljen.")
    }

    private const val LASTNI_NASLOV_PREDPONA = "wss://127.0.0.1:"

    /** Isti id, s katerim se sprejemnik televizorja prijavi Hubu (CastReceiverService). */
    fun lastniId(): String = "tv-" + android.os.Build.MODEL.replace(Regex("\\s+"), "-").lowercase()

    /**
     * Mapa za datoteke, ki jih televizor prejme prek Safeer Linka: ista, kot jo je uporabnik
     * izbral za prenose. Ce vanjo ni mogoce pisati (novejsi Android brez dovoljenja), gre v
     * mapo aplikacije za prenose, ki je vedno na voljo.
     */
    fun mapaZaPrejete(app: Context): File {
        val izbrana = try { si.safeer.tv.PrenosiMapa.ciljnaMapa(app) } catch (_: Throwable) { null }
        if (izbrana != null && (izbrana.isDirectory || izbrana.mkdirs()) && izbrana.canWrite()) return izbrana
        val nadomestna = app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: File(app.filesDir, "prenosi")
        nadomestna.mkdirs()
        return nadomestna
    }

    /** Sprejemnik televizorja se priklopi na lastni Hub z zetonom, ki ga Hub izda sam sebi. */
    private fun poveziLastniZaslon(app: Context, u: HubUsmerjevalnik, vrata: Int) {
        try {
            val zeton = u.zagotoviLastniZeton(lastniId(), imeHuba())
            val naslov = LASTNI_NASLOV_PREDPONA + vrata + "/cast/ws"
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("hub_url", naslov)
                .putString("control_token", zeton)
                .putString("hub_ticket_path", "/cast/ticket")
                // Lastnemu Hubu zaupamo po istem pravilu kot vsakemu drugemu: po odtisu.
                .putString(HubTls.KEY_HUB_FP, HubTls.lastniOdtis())
                .apply()
            CastReceiverService.start(app, naslov, imeHuba())
            Log.i(TAG, "Televizor je priklopljen na lastni Hub kot zaslon.")
        } catch (e: Throwable) {
            Log.w(TAG, "Lastnega zaslona ni bilo mogoce priklopiti: ${e.message}")
        }
    }

    /** Ob izklopu Huba pospravimo samo to, kar je kazalo nanj; tuje seznanitve pustimo pri miru. */
    private fun odklopiLastniZaslon(app: Context) {
        try {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val naslov = prefs.getString("hub_url", "") ?: ""
            if (!naslov.startsWith(LASTNI_NASLOV_PREDPONA)) return
            try {
                app.stopService(android.content.Intent(app, CastReceiverService::class.java))
            } catch (_: Throwable) { }
            prefs.edit().remove("hub_url").remove("control_token").remove("hub_ticket_path").remove(HubTls.KEY_HUB_FP).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "Lastnega zaslona ni bilo mogoce odklopiti: ${e.message}")
        }
    }

    private fun povezi(u: HubUsmerjevalnik, povezava: HubStreznik.Povezava) {
        val odjemalec = object : HubUsmerjevalnik.Odjemalec {
            override val naslov: String = povezava.naslov
            override fun poslji(besedilo: String) = povezava.poslji(besedilo)
            override fun zapri(koda: Int, razlog: String) = povezava.zapri(koda, razlog)
        }
        povezava.naSporocilo = { sporocilo -> u.obdelaj(odjemalec, sporocilo) }
        povezava.naZaprtje = { u.odklopi(odjemalec) }
    }

    private fun imeHuba(): String = "Safeer TV (" + android.os.Build.MODEL + ")"

    /** Naslov, na katerem je Hub dosegljiv; prazen, ce ne tece ali ce ni omrezja. */
    fun naslov(): String {
        val vrata = vrata()
        if (vrata == 0) return ""
        val ip = krajevniNaslov() ?: return ""
        return "https://$ip:$vrata"
    }

    /**
     * Prvi krajevni naslov IPv4 te naprave. Televizor ima navadno enega samega, a na
     * napravah z vec vmesniki izberemo tistega, ki je res v hisnem omrezju.
     */
    fun krajevniNaslov(): String? = try {
        val vmesniki = NetworkInterface.getNetworkInterfaces()
        var najdeno: String? = null
        while (vmesniki.hasMoreElements() && najdeno == null) {
            val vmesnik = vmesniki.nextElement()
            if (!vmesnik.isUp || vmesnik.isLoopback) continue
            val naslovi = vmesnik.inetAddresses
            while (naslovi.hasMoreElements()) {
                val naslov = naslovi.nextElement()
                if (naslov is Inet4Address && naslov.isSiteLocalAddress) {
                    najdeno = naslov.hostAddress
                    break
                }
            }
        }
        najdeno
    } catch (e: Exception) {
        Log.w(TAG, "Naslova ni bilo mogoce ugotoviti: ${e.message}")
        null
    }

    /** Stanje za stran Safeer Linka. */
    fun stanjeJson(context: Context): String {
        val u = usmerjevalnik
        return JsonLahki.Zapis()
            .logicno("tece", tece())
            .logicno("zazelen", jeZazelen(context))
            .niz("naslov", naslov())
            .niz("ime", if (HubObjava.objavljenoIme.isNotBlank()) HubObjava.objavljenoIme else imeHuba())
            .logicno("objavljen", HubObjava.jeObjavljen())
            .stevilo("naprav", (u?.steviloNaprav() ?: 0).toDouble())
            .stevilo("cakajocih", (u?.cakajocePrijave()?.size ?: 0).toDouble())
            .stevilo("seznanjenih", (u?.seznanjeneNaprave()?.size ?: 0).toDouble())
            .toString()
    }

    /** Zetoni seznanjenih naprav v zasebnih nastavitvah brskalnika. */
    private class NastavitveShramba(private val context: Context) : HubUsmerjevalnik.Shramba {
        override fun beri(kljuc: String): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(kljuc, null)

        override fun pisi(kljuc: String, vrednost: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(kljuc, vrednost).apply()
        }
    }
}
