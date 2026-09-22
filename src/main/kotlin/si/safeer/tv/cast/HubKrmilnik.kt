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

    /** Spletni odjemalec (naprava brez Safeerja): goli HTTP na svojih vratih, samo domace omrezje. */
    @Volatile
    private var spletniStreznik: HubStreznik? = null

    @Volatile
    var usmerjevalnik: HubUsmerjevalnik? = null
        private set

    /** Stran Safeer Linka (ce je odprta) izve za nove ali potrjene prijave. */
    @Volatile
    var naSpremembePrijav: (() -> Unit)? = null

    /**
     * Zaslon (MainActivity) pokaze kodo za seznanitev tudi takrat, ko stran Linka ni odprta -
     * naprava, ki se povezuje, kodo potrebuje TAKOJ, uporabnik pa je morda sredi filma.
     */
    @Volatile
    var naPrijavoZaZaslon: (() -> Unit)? = null

    /** Storitev v ozadju osvezi obvestilo s kodo (telefon, ko brskalnik ni v ospredju). */
    @Volatile
    var naPrijavoZaObvestilo: (() -> Unit)? = null

    @Volatile
    var tokovi: HubTokovi? = null
        private set

    fun tece(): Boolean = streznik?.teceZdaj() == true

    fun vrata(): Int = streznik?.vrata ?: 0

    /** Vrata spletnega odjemalca (0, ce ne tece). */
    fun vrataSplet(): Int = spletniStreznik?.vrata ?: 0

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
        // Hub je prvi clan kroga zaupanja: njegov kljuc je kljuc potrdila TLS.
        try { u.vpisiLastniKljuc(lastniId(), imeHuba(app), HubTls.javniKljucB64(), "tv") } catch (e: Throwable) {
            Log.w(TAG, "Kljuca huba ni bilo mogoce vpisati v krog: ${e.message}")
        }
        u.naSpremembePrijav = {
            try { naSpremembePrijav?.invoke() } catch (e: Throwable) { SafeerLog.napaka("Krmilnik", "naSpremembePrijav", e) }
            try { naPrijavoZaZaslon?.invoke() } catch (e: Throwable) { SafeerLog.napaka("Krmilnik", "naPrijavoZaZaslon", e) }
            try { naPrijavoZaObvestilo?.invoke() } catch (e: Throwable) { SafeerLog.napaka("Krmilnik", "naPrijavoZaObvestilo", e) }
        }
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

        // Spletni odjemalec: ista logika huba, goli HTTP na svojih vratih (brskalnik na telefonu brez
        // Safeerja ne sprejme nasega samopodpisanega potrdila); samo krajevno omrezje in ozek izbor poti.
        u.beriSredstvo = { ime ->
            try { app.assets.open("link-web/$ime").bufferedReader(Charsets.UTF_8).use { it.readText() } } catch (_: Throwable) { null }
        }
        val w = HubStreznik(
            zeljenaVrata = HubUsmerjevalnik.SPLETNA_VRATA,
            naZahtevo = { zahteva -> u.odgovoriSplet(zahteva) },
            preveriVstopnico = { zahteva -> u.preveriVstopnico(zahteva) },
            naPovezavo = { povezava -> povezi(u, povezava) },
            tlsTovarna = null
        )
        if (w.zazeni()) { spletniStreznik = w; u.spletnaVrata = w.vrata } else Log.w(TAG, "Spletnih vrat ni bilo mogoce odpreti; spletni odjemalec ni na voljo.")

        // Televizor, ki gosti, je hkrati zaslon: sprejemnik se priklopi na lastni Hub, da ga
        // druge naprave vidijo kot "zaslon" in mu lahko posljejo stran. Brez tega je Hub
        // sredisce, na katerega ni mogoce nicesar poslati.
        poveziLastniZaslon(app, u, s.vrata)

        HubObjava.objavi(app, s.vrata, imeHuba(app), prioriteta(app), lastniId()) { uspelo ->
            if (!uspelo) {
                // Brez oglasa Hub se vedno dela; naprava, ki ga je ze videla, pozna naslov.
                Log.i(TAG, "Hub tece, oglas v omrezju pa ni uspel.")
            }
        }
        if (zapomni) zapomniZeljo(app, true)
        pozabiIzvoljeni(app)
        Log.i(TAG, "Safeer Hub tece na ${naslov()} (prioriteta ${prioriteta(app)})")
        // Izvolitev: ce v hisi ze gosti boljsi clan kroga, se mu umaknemo. Poteka v ozadju, hub
        // medtem tece - naprave, ki so pripete nanj, ob umiku najdejo izvoljenega prek mDNS.
        nacrtujIzvolitev(app, PRVA_IZVOLITEV_MS)
        return true
    }

    // ------------------------------------------------------------------ izvolitev huba

    const val KLJUC_PRIORITETA = "hub_prioriteta"
    const val KLJUC_IZVOLJENI_URL = "izvoljeni_hub_url"
    const val KLJUC_IZVOLJENI_ODTIS = "izvoljeni_hub_fp"
    const val KLJUC_IZVOLJENI_ID = "izvoljeni_hub_id"
    private const val PRVA_IZVOLITEV_MS = 1_500L
    private const val PONOVNA_IZVOLITEV_MS = 90_000L

    private val glavna by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var izvolitevNacrtovana: Runnable? = null

    /** Platforma te naprave v krogu zaupanja: tablica ali TV (isti Gradle projekt, razlicna okusa). */
    fun platforma(context: Context): String = when {
        context.packageName.endsWith(".phone") -> "phone"
        context.packageName.endsWith(".tablet") -> "tablet"
        else -> "tv"
    }

    /** Prioriteta pri izvolitvi: uporabnikova (nastavitev hub_prioriteta) ali privzeta po platformi. */
    fun prioriteta(context: Context): Int {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KLJUC_PRIORITETA, 0)
        return if (p > 0) p else IzvolitevHuba.privzetaPrioriteta(platforma(context))
    }

    /** Razlicica te aplikacije (versionName) ali prazno. */
    fun razlicica(context: Context): String =
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() } catch (_: Throwable) { "" }

    /**
     * Protocol v1: model naprave v tovoru cast.register (protocol, platform, kind, version, priority).
     * [vrsta] pove, kaj ta odjemalec je: "screen" (zaslon, ki lahko gosti hub), "os" (Safeer OS),
     * "handheld", "computer". Prioriteto poslje le, kdor lahko gosti hub (drugi 0 = izpusceno).
     */
    fun poljaV1(context: Context, vrsta: String, tovor: org.json.JSONObject, prioriteta: Int = 0): org.json.JSONObject {
        tovor.put("protocol", HubUsmerjevalnik.PROTOKOL_V1)
            .put("platform", platforma(context))
            .put("kind", vrsta)
        razlicica(context).takeIf { it.isNotBlank() }?.let { tovor.put("version", it) }
        if (prioriteta > 0) tovor.put("priority", prioriteta)
        return tovor
    }

    /** Hub, ki smo se mu umaknili (naslov, odtis, id), ali null, ce gostimo sami oz. nismo v Linku. */
    fun izvoljeniHub(context: Context): HubDiscovery.NajdeniHub? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val naslov = p.getString(KLJUC_IZVOLJENI_URL, "") ?: ""
        val id = p.getString(KLJUC_IZVOLJENI_ID, "") ?: ""
        if (naslov.isBlank() || id.isBlank()) return null
        return HubDiscovery.NajdeniHub(naslov, p.getString(KLJUC_IZVOLJENI_ODTIS, "") ?: "", id, 0, "")
    }

    private fun pozabiIzvoljeni(app: Context) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KLJUC_IZVOLJENI_URL).remove(KLJUC_IZVOLJENI_ODTIS).remove(KLJUC_IZVOLJENI_ID).apply()
    }

    private fun nacrtujIzvolitev(app: Context, cez: Long) {
        izvolitevNacrtovana?.let { glavna.removeCallbacks(it) }
        val r = Runnable { izvolitevNacrtovana = null; if (tece()) izvolitev(app) }
        izvolitevNacrtovana = r
        glavna.postDelayed(r, cez)
    }

    /**
     * Pogleda, kdo v hisi gosti, in se umakne boljsemu clanu kroga (IzvolitevHuba). Tuj oglas brez
     * mesta v krogu zaupanja ne steje. Ce ostanemo hub, preverimo znova cez nekaj minut.
     */
    fun izvolitev(app: Context) {
        HubDiscovery.poisciVse(app) { hubi ->
            if (!tece()) return@poisciVse
            val krog = KrogNaprave.krog(app)
            val jaz = IzvolitevHuba.Kandidat(lastniId(), prioriteta(app))
            // Clan kroga: po id-ju ali - pri id-ju iz kljuca - po kljucu (id, ki ga se nismo videli, a kljuc poznamo).
            val kandidati = hubi.filter { it.id.isNotBlank() && it.id != jaz.id && krog.clanZaId(it.id) != null }
                .map { IzvolitevHuba.Kandidat(it.id, it.prioriteta, it.naslov, it.odtis, it.ime) }
            val tuji = hubi.filter { it.id.isBlank() || (it.id != jaz.id && krog.clanZaId(it.id) == null) }
            if (tuji.isNotEmpty()) Log.i(TAG, "Izvolitev: ${tuji.size} hub(ov) zunaj kroga zaupanja ne steje.")
            val umik = IzvolitevHuba.komuSeUmaknem(jaz, kandidati)
            if (umik == null) {
                Log.i(TAG, "Izvolitev: ostajam hub (${jaz.id}, prioriteta ${jaz.prioriteta}; drugih v krogu: ${kandidati.size}).")
                nacrtujIzvolitev(app, PONOVNA_IZVOLITEV_MS)
                return@poisciVse
            }
            Log.i(TAG, "Izvolitev: umikam se hubu ${umik.id} (prioriteta ${umik.prioriteta} > ${jaz.prioriteta}) na ${umik.naslov}.")
            umakniSe(app, umik)
        }
    }

    /** Ugasne lastni hub in televizor priklopi na izvoljenega kot odjemalca (prijava s podpisom, zaupanje po krogu). */
    @Synchronized
    private fun umakniSe(app: Context, hub: IzvolitevHuba.Kandidat) {
        ustavi(app, zapomni = false)
        Seznanitve.zapomniTrenutno(app)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KLJUC_IZVOLJENI_URL, hub.naslov).putString(KLJUC_IZVOLJENI_ODTIS, hub.odtis).putString(KLJUC_IZVOLJENI_ID, hub.id)
            .putString("hub_url", hub.naslov).putString("hub_ticket_path", "/cast/ticket")
            .putString(HubTls.KEY_HUB_FP, hub.odtis)
            // Zeton velja samo za lastni hub; pri izvoljenem se prijavimo s podpisom kljuca (krog zaupanja).
            .putString("control_token", Seznanitve.zeton(app, hub.odtis) ?: "")
            .apply()
        try { CastReceiverService.start(app, hub.naslov, imeHuba(app)) } catch (e: Throwable) {
            Log.w(TAG, "Sprejemnika ni bilo mogoce priklopiti na izvoljeni hub: ${e.message}")
        }
    }

    /**
     * Izvoljenega huba ni vec (sprejemnik ga ne doseze): ce je uporabnik Link prizgal, spet gostimo
     * sami - izvolitev po zagonu pove, ali je medtem prevzel kdo drug.
     */
    @Synchronized
    fun izvoljeniHubIzgubljen(context: Context) {
        val app = context.applicationContext
        if (izvoljeniHub(app) == null || tece() || !jeZazelen(app)) return
        Log.i(TAG, "Izvoljeni hub se ne oglasa; gostim spet sam.")
        pozabiIzvoljeni(app)
        zazeni(app, zapomni = false)
    }

    /**
     * Po ponovnem zagonu (namestitev, vklop naprave), ko smo se ze prej umaknili izvoljenemu hubu:
     * huba ne zaganjamo, zaslon pa priklopimo na izvoljenega. Ce se ta ne oglasi, sprejemnik po
     * treh neuspehih poklice izvoljeniHubIzgubljen in naprava spet gosti sama.
     */
    fun poveziNaIzvoljeni(context: Context) {
        val app = context.applicationContext
        val hub = izvoljeniHub(app) ?: return
        if (tece() || CastReceiverService.povezan) return
        try { CastReceiverService.start(app, hub.naslov, imeHuba(app)) } catch (e: Throwable) {
            Log.w(TAG, "Sprejemnika ni bilo mogoce priklopiti na izvoljeni hub: ${e.message}")
        }
    }

    /** Ugasne Hub. `zapomni` naj bo true samo, kadar je tako odlocil uporabnik. */
    @Synchronized
    fun ustavi(context: Context?, zapomni: Boolean = true) {
        izvolitevNacrtovana?.let { glavna.removeCallbacks(it) }
        izvolitevNacrtovana = null
        HubObjava.umakni()
        streznik?.ustavi()
        streznik = null
        spletniStreznik?.ustavi()
        spletniStreznik = null
        usmerjevalnik = null
        tokovi = null
        if (context != null) odklopiLastniZaslon(context.applicationContext)
        if (zapomni && context != null) zapomniZeljo(context.applicationContext, false)
        Log.i(TAG, "Safeer Hub ustavljen.")
    }

    private const val LASTNI_NASLOV_PREDPONA = "wss://127.0.0.1:"

    /**
     * Id te naprave: iz njenega kljuca (`n-…`, KrogNaprave.lastniId) - isti na vseh hubih in po menjavi
     * huba. Isti id uporabi sprejemnik (CastReceiverService), oglas mDNS in sorodniki (`-os`). Stari id
     * po modelu (`tv-…`) ostane v krogih kot alias; hub ga ob prvi prijavi s podpisom sam poveze z novim.
     */
    fun lastniId(): String = KrogNaprave.lastniId(nadomestni = { stariId() })

    /** Id po modelu naprave, kot je veljal pred prehodom na id iz kljuca (samo se kot nadomestek in alias). */
    fun stariId(): String = "tv-" + android.os.Build.MODEL.replace(Regex("\\s+"), "-").lowercase()

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
            val zeton = u.zagotoviLastniZeton(lastniId(), imeHuba(app))
            val naslov = LASTNI_NASLOV_PREDPONA + vrata + "/cast/ws"
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("hub_url", naslov)
                .putString("control_token", zeton)
                .putString("hub_ticket_path", "/cast/ticket")
                // Lastnemu Hubu zaupamo po istem pravilu kot vsakemu drugemu: po odtisu.
                .putString(HubTls.KEY_HUB_FP, HubTls.lastniOdtis())
                .apply()
            CastReceiverService.start(app, naslov, imeHuba(app))
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
            override val vstopnica: String? = povezava.zahteva.poizvedba["ticket"]
            override fun poslji(besedilo: String) = povezava.poslji(besedilo)
            override fun zapri(koda: Int, razlog: String) = povezava.zapri(koda, razlog)
        }
        povezava.naSporocilo = { sporocilo -> u.obdelaj(odjemalec, sporocilo) }
        povezava.naZaprtje = { u.odklopi(odjemalec) }
    }

    private fun imeHuba(context: Context): String =
        context.getString(si.safeer.tv.R.string.os_ime_vrste) + " (" + android.os.Build.MODEL + ")"

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
            .niz("ime", if (HubObjava.objavljenoIme.isNotBlank()) HubObjava.objavljenoIme else imeHuba(context))
            .logicno("objavljen", HubObjava.jeObjavljen())
            .stevilo("naprav", (u?.steviloNaprav() ?: 0).toDouble())
            .stevilo("cakajocih", (u?.cakajocePrijave()?.size ?: 0).toDouble())
            .stevilo("seznanjenih", (u?.seznanjeneNaprave()?.size ?: 0).toDouble())
            // Umaknili smo se izvoljenemu hubu: Safeer Link je vklopljen, tece pa na tej napravi (id).
            .niz("izvoljeni", if (tece()) "" else izvoljeniHub(context)?.id.orEmpty())
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
