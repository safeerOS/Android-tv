package si.safeer.tv.link

import android.app.Activity
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import si.safeer.tv.cast.CastReceiverService
import si.safeer.tv.cast.HubDiscovery
import si.safeer.tv.cast.HubPairing
import org.json.JSONObject

/**
 * Most med stranjo Safeer Linka in televizorjem.
 *
 * Televizor je v Safeer Castu predvsem zaslon (prejemnik), a ne samo to: odprto stran
 * in besedilo zna poslati tudi drugim napravam (telefonu, racunalniku). Deljenje datotek
 * in zaslona s televizorja (se) ni - stran to izve po tem, da teh metod tu ni.
 *
 * Stran je del aplikacije (assets/link/) in most je pripet samo njenemu pogledu,
 * nikoli zavihku s spletno stranjo. Zeton naprave ostane v zasebnih nastavitvah.
 */
class LinkMost(
    private val dejavnost: Activity,
    private val pogled: WebView,
    private val trenutnaStran: () -> Pair<String, String?>,
    private val zapriZaslon: () -> Unit,
    private val odpriVBrskalniku: (String) -> Unit
) {

    companion object {
        private const val TAG = "SafeerLink"
        const val PREFS = "safeer_cast_prefs"
    }

    private fun nastavitve() = dejavnost.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun hubUrl(): String = nastavitve().getString("hub_url", "") ?: ""

    private fun ime(): String =
        "tv-" + android.os.Build.MODEL.replace(Regex("\\s+"), "-").lowercase()

    private fun odziv(vrsta: String, podatki: Any) {
        val telo = when (podatki) {
            is JSONObject -> podatki.toString()
            // Seznam mora priti do strani kot seznam; ce ga zavijemo v narekovaje,
            // stran dobi besedilo in odziv tiho odpade.
            is org.json.JSONArray -> podatki.toString()
            is Boolean -> podatki.toString()
            else -> JSONObject.quote(podatki.toString())
        }
        pogled.post {
            try {
                pogled.evaluateJavascript(
                    "window.safeerLinkOdziv && window.safeerLinkOdziv(${JSONObject.quote(vrsta)}, $telo)",
                    null
                )
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "Odziva ni bilo mogoce dostaviti: ${e.message}")
            }
        }
    }

    /**
     * Napaka za stran. [koda] je stabilna oznaka, ki jo stran prevede v jezik naprave;
     * [sporocilo] ostane zraven kot rezerva in za diagnostiko, ce kode ne pozna.
     */
    private fun napaka(koda: String, sporocilo: String) = odziv(
        "napaka",
        JSONObject().put("koda", koda).put("sporocilo", sporocilo)
    )

    // ------------------------------------------------------------------

    @JavascriptInterface
    fun jeTelevizor(): Boolean = true

    /** Stanje sinhronizacije. Na televizorju je zaenkrat izklopljena. */
    @JavascriptInterface
    fun sinhronizacijaStanje(): String =
        "{\"zaznamki\":{\"vklopljena\":false,\"stevilo\":0,\"nadvoljo\":false}}"

    /** Na televizorju sinhronizacije zaznamkov (se) ni; povemo naravnost. */
    @JavascriptInterface
    fun nastaviSinhronizacijo(vklopljena: Boolean) {
        napaka(
            "sync_tv_ni_na_voljo",
            si.safeer.tv.UiText.get(si.safeer.tv.R.string.ui_link_sync_unavailable)
                .ifBlank { "Bookmark sync is not available on the television yet." }
        )
    }


    /**
     * Jezik, ki ga ima uporabnik na napravi -- stran govori v njem.
     * Vrnemo samo dvocrkovno oznako; stran zna slovensko in anglesko.
     */
    @JavascriptInterface
    fun jezik(): String = try {
        val jeziki = dejavnost.resources.configuration.locales
        val prvi = if (jeziki.size() > 0) jeziki.get(0) else java.util.Locale.getDefault()
        (prvi.language ?: "").lowercase().take(2)
    } catch (e: Throwable) {
        ""
    }

    /**
     * Odklopi TO napravo od Safeer Linka: pozabi zeton in naslov.
     *
     * Namenoma ne posegamo v druge naprave -- to je odlocitev za napravo, ki jo ima
     * uporabnik v roki. Ostale se odstrani v Safeer Controlu.
     */
    @JavascriptInterface
    fun pozabiNapravo() {
        try {
            nastavitve().edit()
                .remove("control_token")
                .remove("hub_url")
                .remove("hub_ticket_path")
                .remove("hub_last_seen")
                .remove(si.safeer.tv.cast.HubTls.KEY_HUB_FP)
                .remove("seznanitve")
                .apply()
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "Nastavitev ni bilo mogoce pocistiti: ${e.message}")
        }
        odziv("pozabljeno", true)
    }

    @JavascriptInterface
    fun stanje(): String {
        return try {
            JSONObject().apply {
                put("hub", hubUrl())
                put("znan", hubUrl().isNotBlank())
                put("seznanjen", HubPairing.token(dejavnost) != null)
                put("naprava", "Safeer TV (" + android.os.Build.MODEL + ")")
                put("id", ime())
                put("videnZadnjic", nastavitve().getLong("hub_last_seen", 0L))
            }.toString()
        } catch (e: Throwable) {
            "{\"znan\":false,\"seznanjen\":false}"
        }
    }

    @JavascriptInterface
    fun poisciHub() {
        try {
            HubDiscovery.discover(dejavnost) { naslov ->
                odziv("hub", JSONObject().apply {
                    put("najden", naslov != null)
                    put("naslov", naslov ?: "")
                })
            }
        } catch (e: Throwable) {
            napaka("iskanje_ni_steklo", "Iskanja ni bilo mogoce zagnati: ${e.message}")
        }
    }

    @JavascriptInterface
    fun seznani() {
        val naslov = hubUrl()
        if (naslov.isBlank()) {
            napaka("hub_ni_znan", "Hub ni znan. Najprej ga poisci.")
            return
        }
        try {
            HubPairing.pair(
                dejavnost, naslov, ime(), "Safeer TV (" + android.os.Build.MODEL + ")",
                { nacin, koda ->
                    odziv("nacin", JSONObject().apply {
                        put("nacin", nacin)
                        put("koda", koda)
                    })
                },
                { uspelo -> seznanitevKoncana(uspelo) }
            )
        } catch (e: Throwable) {
            napaka("seznanitev_ni_stekla", "Seznanitve ni bilo mogoce zaceti: ${e.message}")
        }
    }

    /** Uporabnik je vtipkal sestmestno kodo, ki jo pokaze gostitelj. */
    @JavascriptInterface
    fun potrdiKodo(koda: String) {
        try {
            HubPairing.potrdiKodo(dejavnost, koda, ime()) { uspelo, razlog ->
                if (uspelo) {
                    seznanitevKoncana(true)
                } else {
                    odziv("kodaNiSprejeta", JSONObject().apply {
                        put("razlog", razlog ?: "napacna_koda")
                    })
                }
            }
        } catch (e: Throwable) {
            napaka("seznanitev_ni_stekla", "Kode ni bilo mogoce poslati: ${e.message}")
        }
    }

    /** Uporabnik je vnos kode opustil. */
    @JavascriptInterface
    fun prekiniSeznanitev() {
        try {
            HubPairing.prekini()
        } catch (_: Throwable) {}
        odziv("seznanitevPrekinjena", true)
    }

    private fun seznanitevKoncana(uspelo: Boolean) {
        odziv("seznanitev", uspelo)
        if (uspelo) {
            // Sprejemnik lahko zdaj stece: televizor je od tu naprej dosegljiv.
            try {
                CastReceiverService.start(
                    dejavnost, null,
                    "Safeer TV (" + android.os.Build.MODEL + ")"
                )
            } catch (_: Throwable) {}
        }
    }

    /** Televizor ne odpira svoje povezave posiljatelja; pove le, ali sprejemnik tece. */
    @JavascriptInterface
    fun poveziSe() {
        pripniPoslusalce()
        val storitev = CastReceiverService.instance
        if (storitev == null && HubPairing.token(dejavnost) != null && hubUrl().isNotBlank()) {
            try { CastReceiverService.start(dejavnost) } catch (_: Throwable) { }
        }
        odziv("povezava", CastReceiverService.povezan)
        odziv("naprave", napraveZaStran(CastReceiverService.zadnjeNaprave))
    }

    /** Seznam naprav, kot ga Hub javlja vsem povezanim; televizor je v njem tudi sam. */
    @JavascriptInterface
    fun naprave(): String = napraveZaStran(CastReceiverService.zadnjeNaprave).toString()

    private fun napraveZaStran(surovo: String): org.json.JSONArray {
        val polje = org.json.JSONArray()
        try {
            val vhod = org.json.JSONArray(surovo)
            for (i in 0 until vhod.length()) {
                val n = vhod.optJSONObject(i) ?: continue
                polje.put(JSONObject().apply {
                    put("id", n.optString("id", ""))
                    put("ime", n.optString("name", ""))
                    put("vloga", n.optString("role", "receiver"))
                    put("zmoznosti", n.optJSONArray("capabilities") ?: org.json.JSONArray())
                    put("zasedenaOd", n.optString("busy_by", ""))
                    put("zasedenaOdIme", n.optString("busy_by_name", ""))
                })
            }
        } catch (_: Throwable) { }
        return polje
    }

    @JavascriptInterface
    fun trenutnaStranJson(): String {
        return try {
            val (url, naslov) = trenutnaStran()
            JSONObject().apply {
                put("url", url)
                put("naslov", naslov ?: "")
                put("posljiva", url.startsWith("http://") || url.startsWith("https://"))
            }.toString()
        } catch (e: Throwable) {
            "{\"url\":\"\",\"naslov\":\"\",\"posljiva\":false}"
        }
    }

    /** Poslje stran, ki je odprta na televizorju, na izbrano napravo (telefon, racunalnik). */
    @JavascriptInterface
    fun posljiTrenutno(idNaprave: String) {
        val (url, naslov) = trenutnaStran()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            napaka("stran_ni_primerna", "Ta stran ni primerna za posiljanje.")
            return
        }
        poslji(idNaprave, url, naslov ?: "")
    }

    @JavascriptInterface
    fun poslji(idNaprave: String, url: String, naslov: String) {
        val cist = url.trim()
        if (!cist.startsWith("http://") && !cist.startsWith("https://")) {
            napaka("samo_http", "Poslati je mogoce samo naslove http in https.")
            return
        }
        val storitev = CastReceiverService.instance
        if (storitev == null || !CastReceiverService.povezan) {
            napaka("hub_ni_znan", "Hub ni znan.")
            return
        }
        val id = java.util.UUID.randomUUID().toString()
        CastReceiverService.naPotrditev = { refId, stanje, koda, sporocilo ->
            if (refId == id) {
                if (stanje == "accepted") odziv("poslano", JSONObject().put("naprava", idNaprave).put("url", cist))
                else napaka(koda.ifBlank { "posiljanje_ni_uspelo" }, sporocilo.ifBlank { "Posiljanje ni uspelo." })
            }
        }
        if (!storitev.posljiUrl(idNaprave, cist, naslov.ifBlank { null }, id)) {
            napaka("posiljanje_ni_uspelo", "Posiljanje ni uspelo: povezave s Hubom ni.")
        }
    }

    // ------------------------------------------------------------------ deljenje s televizorja

    private fun zeton(): String? = HubPairing.token(dejavnost)

    /** Naslov Huba za navadne zahteve HTTP (wss://x:y/cast/ws -> https://x:y). */
    private fun hubHttp(): String = hubUrl().replace(Regex("^wss"), "https").replace(Regex("^ws"), "http")
        .substringBefore("/cast/ws").substringBefore("/link/ws").substringBefore("/safeer/ws").trimEnd('/')

    private fun httpJson(metoda: String, pot: String, telo: String): Pair<Int, String> {
        val povezava = java.net.URL(hubHttp() + pot).openConnection() as java.net.HttpURLConnection
        try {
            si.safeer.tv.cast.HubTls.zavaruj(povezava, dejavnost)
            povezava.requestMethod = metoda
            povezava.connectTimeout = 5000
            povezava.readTimeout = 10000
            zeton()?.let { povezava.setRequestProperty("x-safeer-token", it) }
            povezava.setRequestProperty("Content-Type", "application/json")
            povezava.doOutput = true
            povezava.outputStream.use { it.write(telo.toByteArray(Charsets.UTF_8)) }
            val koda = povezava.responseCode
            val tok = if (koda >= 400) povezava.errorStream else povezava.inputStream
            return koda to (tok?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally {
            povezava.disconnect()
        }
    }

    private fun deljenje(vrsta: String, stanje: String, cilj: String, sporocilo: String = "", koda: String = "", zasedenaOd: String = "") {
        odziv("deljenje", JSONObject().apply {
            put("vrsta", vrsta)
            put("stanje", stanje)
            put("cilj", cilj)
            put("ime", "")
            put("sporocilo", sporocilo)
            put("koda", koda)
            put("zasedenaOd", zasedenaOd)
        })
    }

    /** Besedilo z daljinca (ali tipkovnice) na izbrano napravo - ista pot kot na telefonu. */
    @JavascriptInterface
    fun posljiBesedilo(idNaprave: String, besedilo: String) {
        val cisto = besedilo.trim()
        if (cisto.isEmpty()) return
        if (hubUrl().isBlank() || zeton() == null) {
            napaka("hub_ni_znan", "Hub ni znan.")
            return
        }
        deljenje("besedilo", "posiljam", idNaprave)
        Thread {
            try {
                val telo = JSONObject().put("device_id", ime()).put("target", idNaprave).put("text", cisto).toString()
                val (koda, odgovor) = httpJson("POST", "/cast/share/text", telo)
                if (koda == 200) deljenje("besedilo", "poslano", idNaprave)
                else {
                    val o = try { JSONObject(odgovor) } catch (_: Throwable) { JSONObject() }
                    deljenje("besedilo", "napaka", idNaprave,
                        sporocilo = o.optString("napaka", "").ifBlank { o.optString("error", "") }.ifBlank { "Hub je odgovoril $koda" },
                        koda = o.optString("koda", "").ifBlank { o.optString("error_code", "") },
                        zasedenaOd = o.optString("busy_by_name", "").ifBlank { o.optString("busy_by", "") })
                }
            } catch (e: Throwable) {
                deljenje("besedilo", "napaka", idNaprave, sporocilo = e.message ?: "posiljanje ni uspelo")
            }
        }.start()
    }

    @JavascriptInterface
    fun nadzor(idNaprave: String, ukaz: String, vrednost: Double) {
        napaka("tv_ne_upravlja", "Televizor je zaslon in ne upravlja drugih zaslonov.")
    }

    @JavascriptInterface
    fun zapri() {
        dejavnost.runOnUiThread { zapriZaslon() }
    }

    @JavascriptInterface
    fun odpri(url: String) {
        val cist = url.trim()
        if (!cist.startsWith("http://") && !cist.startsWith("https://")) return
        dejavnost.runOnUiThread {
            zapriZaslon()
            odpriVBrskalniku(cist)
        }
    }


    // ------------------------------------------------------------------ Hub na televizorju

    /**
     * Stanje Huba, ki ga gosti ta televizor.
     *
     * Ob vsakem pogledu na stran tudi povemo usmerjevalniku, kam naj javi spremembe -
     * tako se cakajoca prijava pokaze sama od sebe in uporabniku ni treba osvezevati.
     */
    @JavascriptInterface
    fun hubStanje(): String {
        pripniPoslusalce()
        return try {
            si.safeer.tv.cast.HubKrmilnik.stanjeJson(dejavnost)
        } catch (e: Throwable) {
            "{\"tece\":false,\"zazelen\":false}"
        }
    }

    @JavascriptInterface
    fun hubVklopi() {
        try {
            val uspelo = si.safeer.tv.cast.HubStoritev.vklopi(dejavnost)
            pripniPoslusalce()
            if (!uspelo) napaka("hub_ni_zagnan", "Huba ni bilo mogoce zagnati.")
            odziv("hub-tu", JSONObject(hubStanje()))
        } catch (e: Throwable) {
            napaka("hub_ni_zagnan", "Huba ni bilo mogoce zagnati: ${e.message}")
        }
    }

    @JavascriptInterface
    fun hubIzklopi() {
        try {
            si.safeer.tv.cast.HubStoritev.izklopi(dejavnost)
            odziv("hub-tu", JSONObject(hubStanje()))
        } catch (e: Throwable) {
            napaka("hub_ni_ustavljen", "Huba ni bilo mogoce ustaviti: ${e.message}")
        }
    }

    /**
     * Naprave, ki se zelijo prikljuciti: ime in sestmestna koda, ki jo uporabnik prepise
     * s tega zaslona na tisto napravo. Dokler je ne vtipka, se ne poveze nic.
     */
    @JavascriptInterface
    fun hubPrijave(): String = try {
        val u = si.safeer.tv.cast.HubKrmilnik.usmerjevalnik
        org.json.JSONArray().apply {
            u?.cakajocePrijave()?.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.pairId)
                    put("ime", p.ime)
                    put("koda", p.pin)
                    put("naslov", p.naslov)
                    put("starost", p.starostSekund)
                    put("potrebujePotrditev", p.potrebujePotrditev)
                })
            }
        }.toString()
    } catch (e: Throwable) {
        "[]"
    }

    /** Uporabnik je na televizorju pritisnil V redu. Nicesar ni treba vtipkati. */
    @JavascriptInterface
    fun hubPotrdi(idPrijave: String) {
        val u = si.safeer.tv.cast.HubKrmilnik.usmerjevalnik
        if (u == null) {
            napaka("hub_ne_tece", "Hub ne tece.")
            return
        }
        if (!u.potrdiPrijavo(idPrijave)) {
            napaka("prijava_potekla", "Prijave ni vec ali pa je poteklo.")
        }
        odziv("hub-prijave", org.json.JSONArray(hubPrijave()))
    }

    @JavascriptInterface
    fun hubZavrni(idPrijave: String) {
        si.safeer.tv.cast.HubKrmilnik.usmerjevalnik?.zavrniPrijavo(idPrijave)
        odziv("hub-prijave", org.json.JSONArray(hubPrijave()))
    }

    /** Naprave, ki jim je uporabnik ze dovolil. */
    @JavascriptInterface
    fun hubSeznanjene(): String = try {
        val u = si.safeer.tv.cast.HubKrmilnik.usmerjevalnik
        org.json.JSONArray().apply {
            u?.seznanjeneNaprave()?.forEach { n ->
                put(JSONObject().apply {
                    put("id", n.deviceId)
                    put("ime", n.ime)
                    put("od", n.seznanjenaOb)
                })
            }
        }.toString()
    } catch (e: Throwable) {
        "[]"
    }

    /** Odvzame dostop napravi in jo, ce je povezana, odklopi. */
    // ------------------------------------------------------------------ krajevna imena naprav

    /** Imena, ki jih je uporabnik te naprave dal drugim napravam (JSON {id: ime}); ostanejo na tej napravi. */
    @JavascriptInterface
    fun vzdevki(): String = nastavitve().getString("link_vzdevki", "{}") ?: "{}"

    /** Prazno ime vzdevek odstrani (naprava se spet kaze s svojim imenom). */
    @JavascriptInterface
    fun shraniVzdevek(idNaprave: String, ime: String) {
        try {
            val vsi = JSONObject(nastavitve().getString("link_vzdevki", "{}") ?: "{}")
            val cisto = ime.trim().take(64)
            if (cisto.isEmpty()) vsi.remove(idNaprave) else vsi.put(idNaprave, cisto)
            nastavitve().edit().putString("link_vzdevki", vsi.toString()).apply()
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "Vzdevka ni bilo mogoce shraniti: ${e.message}")
        }
    }

    @JavascriptInterface
    fun hubPreklici(idNaprave: String) {
        val u = si.safeer.tv.cast.HubKrmilnik.usmerjevalnik
        if (u == null) {
            napaka("hub_ne_tece", "Hub ne tece.")
            return
        }
        u.prekliciNapravo(idNaprave)
        odziv("hub-seznanjene", org.json.JSONArray(hubSeznanjene()))
    }

    /**
     * Usmerjevalnik javi spremembe strani, da se nova prijava pokaze takoj.
     * Poslusalca pripnemo vedno na novo, ker se Hub lahko vmes ugasne in prizge.
     */
    private fun pripniPoslusalce() {
        CastReceiverService.naSpremembeNaprav = { surovo -> odziv("naprave", napraveZaStran(surovo)) }
        CastReceiverService.naPovezavo = { p -> odziv("povezava", p) }
        val u = si.safeer.tv.cast.HubKrmilnik.usmerjevalnik ?: return
        si.safeer.tv.cast.HubKrmilnik.naSpremembePrijav = { odziv("hub-prijave", org.json.JSONArray(hubPrijave())) }
        u.naSpremembeNaprav = { odziv("hub-tu", JSONObject(si.safeer.tv.cast.HubKrmilnik.stanjeJson(dejavnost))) }
    }


    fun pospravi() {
        // Televizor tu nima odprte povezave, ki bi jo bilo treba zapreti.
        // Hub pa namenoma tece naprej: televizor je zaslon, ki naj bo dosegljiv tudi
        // takrat, ko uporabnik zapre ta zaslon in gleda. Ugasne ga uporabnik sam ali
        // konec brskalnika. Odklopimo samo poslusalca, da stran ne ostane v pomnilniku.
        try {
            CastReceiverService.naSpremembeNaprav = null
            CastReceiverService.naPovezavo = null
            CastReceiverService.naPotrditev = null
            val u = si.safeer.tv.cast.HubKrmilnik.usmerjevalnik
            si.safeer.tv.cast.HubKrmilnik.naSpremembePrijav = null
            u?.naSpremembeNaprav = null
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "Poslusalcev ni bilo mogoce odkljuciti: ${e.message}")
        }
    }
}
