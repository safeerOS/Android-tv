package si.safeer.tv.os

import si.safeer.tv.R

import android.content.Context
import si.safeer.tv.cast.HubKrmilnik
import si.safeer.tv.cast.HubTls
import si.safeer.tv.cast.KrogNaprave
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Odjemalec Safeer Linka za Safeer OS: povezava s srediscem (TLS s pripetim odtisom, enkratna
 * vstopnica, WebSocket), prijava naprave in seznam naprav. Isti protokol kot telefon in racunalnik.
 */
class LinkOdjemalec(private val context: Context) {

    data class Naprava(val id: String, val ime: String, val vloga: String, val zmoznosti: List<String>, val naslov: String,
                       /** "tv", "tablet", "phone", "linux" ... - kot se naprava predstavi hubu; prazno pri starih. */
                       val platforma: String = "",
                       /** Fizicna naprava (id iz kljuca, polje `device`): sorodniki z istim kljucem imajo isto; prazno brez kljuca. */
                       val naprava: String = "")

    companion object {
        private const val TAG = "SafeerOsLink"

        /**
         * Druge naprave za prikaz uporabniku. Sorodniki z istim kljucem (brskalnik in Safeer Control na istem
         * racunalniku, Safeer OS in zaslon iste tablice) so ena naprava: ostane tisti z vec zmoznostmi, da dejanja
         * (datoteke, zaslon) gredo pravemu. Lastna naprava izpade v celoti, tudi njeni sorodniki.
         */
        fun drugeZaPrikaz(naprave: List<Naprava>, jaz: String): List<Naprava> {
            val moja = naprave.firstOrNull { it.id == jaz }?.naprava.orEmpty()
            val izhod = LinkedHashMap<String, Naprava>()
            for (n in naprave) {
                if (n.id == jaz || (moja.isNotBlank() && n.naprava == moja)) continue
                val kljuc = n.naprava.ifBlank { "id:" + n.id }
                val prej = izhod[kljuc]
                if (prej == null || n.zmoznosti.size > prej.zmoznosti.size) izhod[kljuc] = n
            }
            return izhod.values.toList()
        }
    }

    interface Poslusalec {
        fun naStanje(povezan: Boolean, sporocilo: String)
        fun naNaprave(naprave: List<Naprava>)
        fun naNaslov(url: String, naslov: String, od: String)
        fun naBesedilo(besedilo: String, od: String)
        /** Sredisce te naprave ne pozna vec: zeton je treba dobiti znova. */
        fun naZavrnitev()
        /** Sredisca ni vec (vec zaporednih neuspehov): morda se je lastni hub umaknil izvoljenemu - poverilnice znova. */
        fun naIzgubo() {}
    }

    var poslusalec: Poslusalec? = null
    @Volatile var povezan = false
        private set
    var imeSredisca: String = ""
        private set
    /** Zadnji seznam naprav s sredisca (za zaslone, ki se odprejo, ko je povezava ze vzpostavljena). */
    @Volatile var naprave: List<Naprava> = emptyList()
    /** Sredisce tece na tej napravi (loopback ali nas naslov): njegov brskalnik se hubu javi s 127.0.0.1. */
    @Volatile var srediceJeTu: Boolean = false
        private set

    /** Povabilo sredisca za QR kodo (pair.invite.ok): kar naprava potrebuje, da kodo narise. */
    data class Povabilo(val qrId: String, val skrivnost: String, val odtis: String, val naslov: String, val veljaMs: Long)

    @Volatile private var naPovabilo: ((Povabilo?) -> Unit)? = null

    /**
     * Prosi sredisce za QR kodo, s katero se nova naprava pridruzi Linku. Tako lahko kodo pokaze
     * KATERAKOLI naprava v Linku, ne samo tista, na kateri tece sredisce. [naprej] dobi null, ce ne gre.
     */
    fun zahtevajPovabilo(preklici: String, naprej: (Povabilo?) -> Unit) {
        val w = ws
        if (!povezan || w == null) { glavna.post { naprej(null) }; return }
        naPovabilo = naprej
        val poslano = try {
            w.send(JSONObject().put("id", UUID.randomUUID().toString()).put("type", "pair.invite")
                .put("payload", JSONObject().put("preklici", preklici)).toString())
        } catch (_: Throwable) { false }
        if (!poslano) { naPovabilo = null; glavna.post { naprej(null) }; return }
        glavna.postDelayed({ naPovabilo?.let { naPovabilo = null; it(null) } }, 8_000)
    }

    /** Koda, ki jo je pokazala ta naprava, ni vec potrebna. */
    fun prekliciPovabilo(qrId: String) {
        if (qrId.isBlank()) return
        try {
            ws?.send(JSONObject().put("id", UUID.randomUUID().toString()).put("type", "pair.invite.cancel")
                .put("payload", JSONObject().put("qr_id", qrId)).toString())
        } catch (_: Throwable) { }
    }

    /** Odgovor na ukaz daljinca: `izid` je payload sporocila control.result (ok, message, data) ali null ob napaki/poteku. */
    fun interface Odgovor { fun na(izid: JSONObject?, napaka: String) }

    private class CakajociUkaz(val odgovor: Odgovor, val potek: Runnable)
    private val cakajoci = ConcurrentHashMap<String, CakajociUkaz>()

    private val glavna = Handler(Looper.getMainLooper())
    private var poverilnice: Sorodnik.Poverilnice? = null
    private var odjemalec: OkHttpClient? = null
    @Volatile private var ws: WebSocket? = null
    private var tece = false
    private var poskusov = 0
    /** Global Link: domaci hub ni v tem omrezju, povezava gre prek link.safeer.si (LAN ostane prvi). */
    @Volatile private var prekReleja = false
    private fun naslovHuba(p: Sorodnik.Poverilnice): String =
        si.safeer.tv.link.GlobalLink.naslov(context, p.hubUrl, p.hubId, prekReleja)
    private val idNaprave: String by lazy { Identiteta.id(context) }
    /** Sejni zeton s prijave s podpisom: z njim gredo zahteve HTTP (preimenovanje), ko zetona seznanitve ni. */
    @Volatile private var sejniZeton = ""

    fun zazeni(p: Sorodnik.Poverilnice) {
        poverilnice = p
        tece = true
        poskusov = 0
        // Nova generacija: ponovni poskusi prejsnje povezave (ze nacrtovani na glavni niti) ne veljajo vec,
        // sicer bi ob vsakem novem zagonu tekla se ena zanka poskusov vzporedno s staro.
        generacija++
        izgubaJavljena = false
        // Stara povezava mora pasti: dve hkratni povezavi iste naprave si pri srediscu izmenjujeta mesto
        // (vsaka nova zamenja prejsnjo), zato je povezava padala na ~20 s.
        ws?.let { stara -> ws = null; try { stara.cancel() } catch (_: Throwable) { } }
        odjemalec = zgradi(p)
        povezi()
    }

    fun ustavi() {
        tece = false
        try { ws?.close(1000, "konec") } catch (_: Throwable) { }
        ws = null
        povezan = false
    }

    private fun zgradi(p: Sorodnik.Poverilnice): OkHttpClient {
        // Izvoljeni hub (drug clan kroga, brez zetona): potrdilo mora poleg odtisa iz oglasa nositi
        // njegov kljuc iz kroga zaupanja - oglas mDNS sam po sebi ne dobi nobenega zaupanja.
        val kljucKroga = if (p.zeton.isBlank() && p.hubId.isNotBlank()) KrogNaprave.kljucHuba(context, p.hubId) else null
        val (tovarna, zaupnik) = if (kljucKroga != null) HubTls.odjemalec(p.odtis, kljucKroga) else Pin.tovarna(p.odtis)
        return OkHttpClient.Builder()
            .sslSocketFactory(tovarna, zaupnik)
            .hostnameVerifier(Pin.brezImena)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    private fun osnovaHttp(wsUrl: String): String =
        wsUrl.replace(Regex("^wss"), "https").substringBefore("/cast/ws").trimEnd('/')

    /** En klic HTTP na sredisce; [naprej] dobi kodo in telo (ali -1 in razlog ob napaki omrezja). */
    private fun klic(pot: String, telo: JSONObject?, zeton: String?, naprej: (Int, String) -> Unit) {
        val p = poverilnice ?: return
        val k = odjemalec ?: return
        val z = Request.Builder().url(osnovaHttp(naslovHuba(p)) + pot)
        if (zeton != null) z.addHeader("X-Safeer-Token", zeton)
        z.post((telo?.toString() ?: "").toRequestBody("application/json".toMediaTypeOrNull()))
        k.newCall(z.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { naprej(-1, e.message.orEmpty()) }
            override fun onResponse(call: Call, response: Response) {
                response.use { naprej(it.code, it.body?.string().orEmpty()) }
            }
        })
    }

    /**
     * Prijava na sredisce. Naprava, ki je ze v krogu zaupanja, se prijavi s podpisom (izziv ->
     * podpis -> vstopnica); ce sredisce tega ne zna ali naprave ne pozna, gre po starem z zetonom.
     * Naprava, ki pride z zetonom in se ni v krogu, v isti seji vpise svoj kljuc (prehod brez kode).
     */
    private fun povezi() {
        val p = poverilnice ?: return
        if (!tece) return
        // S podpisom tudi, ce je nas kljuc v krogu pod starim id-jem: hub nov id sam vpise kot alias.
        if (KrogNaprave.lahkoSPodpisom(context, idNaprave)) poveziSPodpisom(p) else poveziZZetonom(p)
    }

    private fun poveziSPodpisom(p: Sorodnik.Poverilnice) {
        klic("/cast/auth/challenge", JSONObject().put("device_id", idNaprave), null) { koda, telo ->
            val nonce = try { JSONObject(telo).optString("nonce") } catch (_: Throwable) { "" }
            if (koda != 200 || nonce.isBlank()) {
                // Staro sredisce (404/405) ali sredisce, ki nas nima v krogu (401): zeton se vedno velja.
                Log.i(TAG, "Prijava s podpisom ni mogoca ($koda); z zetonom.")
                poveziZZetonom(p); return@klic
            }
            val podpis = try { KrogNaprave.podpisPrijave(idNaprave, p.odtis, nonce) } catch (e: Throwable) {
                Log.w(TAG, "Podpisa ni bilo mogoce narediti: ${e.message}"); poveziZZetonom(p); return@klic
            }
            val zahteva = JSONObject().put("device_id", idNaprave).put("nonce", nonce).put("signature", podpis)
                .put("name", imeVKrogu()).put("platform", HubKrmilnik.platforma(context))
            klic("/cast/auth/ticket", zahteva, null) { koda2, telo2 ->
                val j = try { JSONObject(telo2) } catch (_: Throwable) { JSONObject() }
                val vstopnica = j.optString("ticket")
                if (koda2 != 200 || vstopnica.isBlank()) { poveziZZetonom(p); return@klic }
                j.optJSONObject("ring")?.let { KrogNaprave.sprejmi(context, it.toString()) }
                sejniZeton = j.optString("session_token", "")
                Log.i(TAG, "Prijava s podpisom kljuca naprave.")
                odpriZVstopnico(p, vstopnica)
            }
        }
    }

    private fun poveziZZetonom(p: Sorodnik.Poverilnice) {
        klic("/cast/ticket", null, p.zeton) { koda, telo ->
            if (koda == 401 || koda == 403) {
                Log.w(TAG, "Sredisce zetona ne sprejme ($koda).")
                tece = false
                glavna.post { poslusalec?.naZavrnitev() }
                return@klic
            }
            val vstopnica = try { JSONObject(telo).optString("ticket") } catch (_: Throwable) { "" }
            if (koda != 200 || vstopnica.isBlank()) {
                if (koda < 0) Log.w(TAG, "Vstopnice ni: $telo")
                javiStanje(false, "")
                ponovno()
                return@klic
            }
            // Prehod: zeton velja, kljuca pa v krogu se ni - vpisemo ga, da naslednjic pridemo s podpisom.
            if (!KrogNaprave.jeVpisana(context, idNaprave)) vpisiVKrog(p)
            odpriZVstopnico(p, vstopnica)
        }
    }

    private fun vpisiVKrog(p: Sorodnik.Poverilnice) {
        val kljuc = try { HubTls.javniKljucB64() } catch (e: Throwable) {
            Log.w(TAG, "Kljuca naprave ni: ${e.message}"); return
        }
        val telo = JSONObject().put("pubkey", kljuc).put("name", imeVKrogu()).put("platform", HubKrmilnik.platforma(context))
        klic("/cast/trust/enroll", telo, p.zeton) { koda, odgovor ->
            if (koda != 200) { Log.i(TAG, "Sredisce kroga zaupanja ne pozna ($koda)."); return@klic }
            val ring = try { JSONObject(odgovor).optJSONObject("ring") } catch (_: Throwable) { null }
            if (KrogNaprave.sprejmi(context, ring?.toString())) Log.i(TAG, "Kljuc naprave vpisan v krog zaupanja.")
        }
    }

    /** Ime, kot ga vidijo druge naprave v krogu: tablica je tablica, ne TV. */
    private fun imeVKrogu(): String =
        (try { context.getString(si.safeer.tv.R.string.os_ime_vrste) } catch (_: Throwable) { "Safeer OS" }) +
            " (" + android.os.Build.MODEL + ")"

    private fun odpriZVstopnico(p: Sorodnik.Poverilnice, vstopnica: String) {
        val locilo = if (p.hubUrl.contains("?")) "&" else "?"
        srediceJeTu = try {
            val gostitelj = java.net.URI(p.hubUrl).host.orEmpty().trim('[', ']')
            val naslov = java.net.InetAddress.getByName(gostitelj)
            naslov.isLoopbackAddress || java.net.NetworkInterface.getByInetAddress(naslov) != null
        } catch (_: Throwable) { false }
        odpri("${naslovHuba(p)}${locilo}ticket=$vstopnica")
    }

    private fun odpri(naslov: String) {
        val k = odjemalec ?: return
        // Ena povezava hkrati: prejsnja (npr. iz vzporednega poskusa) se zapre, preden odpremo novo.
        ws?.let { stara -> ws = null; try { stara.cancel() } catch (_: Throwable) { } }
        ws = k.newWebSocket(Request.Builder().url(naslov).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== ws) { webSocket.cancel(); return }
                poskusov = 0
                povezan = true
                val u = webSocket.request().url
                if (prekReleja && !si.safeer.tv.link.GlobalLink.jeRele(u.host, u.port)) prekReleja = false
                if (prekReleja) glavna.postDelayed(nazajVLan, 300_000L)
                val prijava = JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("type", "cast.register")
                    .put("payload", si.safeer.tv.cast.HubKrmilnik.poljaV1(context, "os", JSONObject()
                        .put("device_id", idNaprave)
                        .put("name", "Safeer OS")
                        .put("role", "sender")
                        .put("capabilities", JSONArray(listOf("url", "text")))))
                webSocket.send(prijava.toString())
                javiStanje(true, "")
            }

            override fun onMessage(webSocket: WebSocket, text: String) { if (webSocket === ws) obdelaj(text) }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Zamenjana ali ustavljena povezava: njen konec ne sme sprozit novega poskusa (sicer tecejo dve).
                if (webSocket !== ws) return
                Log.w(TAG, "Povezava padla: ${t.message}")
                povezan = false
                javiStanje(false, "")
                ponovno()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== ws) return
                povezan = false
                javiStanje(false, "")
                ponovno()
            }
        })
    }

    /**
     * Ukaz drugi napravi po Linku (control.command): sredisce ga posreduje, naprava odgovori s
     * control.result (ref_id = id ukaza). Odgovor pride na glavni niti; brez njega v `potekMs` javimo potek.
     */
    fun ukaz(cilj: String, dejanje: String, parametri: JSONObject, potekMs: Long = 10_000, odgovor: Odgovor) {
        val w = ws
        if (!povezan || w == null) { glavna.post { odgovor.na(null, "ni_povezave") }; return }
        val id = UUID.randomUUID().toString()
        val potek = Runnable { cakajoci.remove(id)?.let { it.odgovor.na(null, "potek") } }
        cakajoci[id] = CakajociUkaz(odgovor, potek)
        val sporocilo = JSONObject()
            .put("id", id)
            .put("type", "control.command")
            .put("target", cilj)
            .put("payload", JSONObject().put("action", dejanje).put("params", parametri))
        val poslano = try { w.send(sporocilo.toString()) } catch (_: Throwable) { false }
        if (!poslano) { cakajoci.remove(id); glavna.post { odgovor.na(null, "ni_povezave") }; return }
        glavna.postDelayed(potek, potekMs)
    }

    /**
     * Poimenuje napravo (tudi to) za vse naprave v Linku; ime hrani sredisce, prazno ime vrne prvotnega.
     * Sredisce na tej napravi preimenuje brez omrezja. [naprej] (uspeh, novo ime) pride na glavni niti.
     */
    fun preimenuj(id: String, ime: String, naprej: (Boolean, String) -> Unit) {
        val u = HubKrmilnik.usmerjevalnik
        if (u != null && srediceJeTu) {
            u.preimenuj(id, ime)
            val novo = u.imeNaprave(id)
            glavna.post { naprej(true, novo) }
            return
        }
        val p = poverilnice
        val zeton = sejniZeton.ifBlank { p?.zeton.orEmpty() }
        if (p == null || zeton.isBlank()) {
            val ok = preimenujPrekoKroga(id, ime)
            glavna.post { naprej(ok, if (ok) ime.trim() else "") }
            return
        }
        klic("/cast/devices/rename", JSONObject().put("device_id", id).put("name", ime.trim()), zeton) { koda, telo ->
            Log.i(TAG, "Preimenovanje na srediscu: HTTP $koda")
            if (koda == 200) {
                val novo = try { JSONObject(telo).optString("name", "") } catch (_: Throwable) { "" }
                glavna.post { naprej(true, novo) }
            } else {
                // Hub brez HTTP preimenovanja (racunalnik): ime gre prek kroga zaupanja.
                val ok = preimenujPrekoKroga(id, ime)
                glavna.post { naprej(ok, if (ok) ime.trim() else "") }
            }
        }
    }

    /**
     * Ime zapisemo v svoj krog (vsem id-jem istega kljuca) in ga ponudimo hubu kot trust.names; hub vzame
     * samo imena clanov, ki jih ze pozna z istim kljucem, in jih razposlje vsem. Prazno ime po tej poti ne gre.
     */
    private fun preimenujPrekoKroga(id: String, ime: String): Boolean {
        val w = ws ?: run { Log.w(TAG, "Preimenovanje prek kroga: ni povezave s srediscem."); return false }
        val cisto = ime.trim()
        if (cisto.isEmpty()) return false
        val krog = KrogNaprave.krog(context)
        val clan = krog.clanZaId(id) ?: run { Log.w(TAG, "Preimenovanje prek kroga: $id ni v krogu (${krog.stevilo()} clanov)."); return false }
        Log.i(TAG, "Preimenovanje prek kroga: ${clan.id} in sorodniki.")
        val zdaj = si.safeer.tv.cast.KrogZaupanja.zdaj()
        krog.clani().filter { it.kljuc == clan.kljuc }.forEach { krog.preimenuj(it.id, cisto, maxOf(zdaj, it.imenovano + 0.001)) }
        return try {
            w.send(JSONObject().put("id", UUID.randomUUID().toString()).put("type", "trust.names")
                .put("payload", JSONObject(krog.json())).toString())
        } catch (_: Throwable) { false }
    }

    private fun koncajUkaz(refId: String, izid: JSONObject?, napaka: String) {
        val c = cakajoci.remove(refId) ?: return
        glavna.removeCallbacks(c.potek)
        glavna.post { c.odgovor.na(izid, napaka) }
    }

    private fun ponovno() {
        if (!tece) return
        val zamik = minOf(30_000L, 2_000L * (1 shl minOf(poskusov, 4)))
        poskusov++
        // Izgubo sredisca javimo enkrat na povezavo (po treh neuspehih); upravitelj takrat vzame
        // poverilnice znova - ce vodijo k istemu srediscu, tu mirno poskusamo naprej.
        // Global Link: po dveh neuspehih v LAN poskusimo domaci hub prek link.safeer.si, preden javimo izgubo.
        val p = poverilnice
        if (poskusov == 2 && !prekReleja && p != null && si.safeer.tv.link.GlobalLink.vklopljen(context) &&
            si.safeer.tv.link.GlobalLink.osnovniId(p.hubId) != null) {
            // Samo ce v tem omrezju ni nobenega huba; sicer ostanemo v LAN (naIzgubo prinese novega).
            try {
                si.safeer.tv.cast.HubDiscovery.discover(context, 4000L) { naslov ->
                    if (tece && !prekReleja && naslov.isNullOrBlank()) {
                        Log.i(TAG, "Domaci hub ni v tem omrezju; poskusim prek Global Linka.")
                        prekReleja = true
                    }
                }
            } catch (e: Throwable) { Log.w(TAG, "Iskanje huba v LAN: ${e.message}") }
        }
        if (poskusov == 3 && !izgubaJavljena) { izgubaJavljena = true; glavna.post { poslusalec?.naIzgubo() } }
        val gen = generacija
        glavna.postDelayed({ if (tece && !povezan && gen == generacija) povezi() }, zamik)
    }

    private var generacija = 0

    /** Na releju: vsakih 5 min preverimo, ali je domaci hub spet v LAN; ce je, gremo domov. */
    private val nazajVLan: Runnable = Runnable {
        val p = poverilnice
        if (!tece || !prekReleja || p == null) return@Runnable
        Thread({
            val doma = si.safeer.tv.link.GlobalLink.lanDosegljiv(p.hubUrl)
            glavna.post {
                if (!prekReleja) return@post
                if (doma) {
                    Log.i(TAG, "Domaci hub je spet v omrezju; zapuscam Global Link.")
                    prekReleja = false
                    si.safeer.tv.link.GlobalLink.izklopi()
                    try { ws?.cancel() } catch (_: Throwable) { }
                } else glavna.postDelayed(nazajVLan, 300_000L)
            }
        }, "safeer-global-link-lan").apply { isDaemon = true }.start()
    }
    private var izgubaJavljena = false

    private fun javiStanje(povezan: Boolean, sporocilo: String) {
        glavna.post { poslusalec?.naStanje(povezan, sporocilo) }
    }

    private fun obdelaj(besedilo: String) {
        val json = try { JSONObject(besedilo) } catch (_: Throwable) { return }
        when (json.optString("type")) {
            "cast.devices" -> {
                val seznam = ArrayList<Naprava>()
                val polje = json.optJSONArray("devices") ?: JSONArray()
                for (i in 0 until polje.length()) {
                    val d = polje.optJSONObject(i) ?: continue
                    val z = d.optJSONArray("capabilities") ?: JSONArray()
                    val zmoznosti = (0 until z.length()).map { z.optString(it) }
                    seznam.add(Naprava(d.optString("id"), d.optString("name"), d.optString("role", "receiver"), zmoznosti, d.optString("ip"),
                        d.optString("platform"), d.optString("device")))
                }
                // Sredisce je naprava z loopback naslovom (tako ga prepozna tudi stran Linka).
                imeSredisca = seznam.firstOrNull { it.naslov == "127.0.0.1" || it.naslov == "::1" }?.ime
                    ?: seznam.firstOrNull { it.id.startsWith("tv-") }?.ime ?: imeSredisca
                naprave = seznam
                glavna.post { poslusalec?.naNaprave(seznam) }
            }
            "control.result" -> {
                val ref = json.optString("ref_id"); if (ref.isBlank()) return
                koncajUkaz(ref, json.optJSONObject("payload") ?: JSONObject(), "")
            }
            "control.ack" -> {
                // Sredisce potrdi ali zavrne posredovanje; zavrnitev (naprava ni na zvezi) je konec ukaza.
                val ref = json.optString("ref_id"); if (ref.isBlank()) return
                if (json.optString("status") != "accepted") {
                    koncajUkaz(ref, null, json.optString("error_code").ifBlank { json.optString("error").ifBlank { "zavrnjeno" } })
                }
            }
            "cast.url" -> {
                val telo = json.optJSONObject("payload") ?: return
                val url = telo.optString("url"); val naslov = telo.optString("title")
                val od = json.optString("sender_name").ifBlank { json.optString("sender") }
                potrdi(json)
                if (url.isNotBlank()) glavna.post { poslusalec?.naNaslov(url, naslov, od) }
            }
            "share.text" -> {
                val telo = json.optJSONObject("payload") ?: return
                val od = json.optString("sender_name").ifBlank { json.optString("sender") }
                potrdi(json)
                glavna.post { poslusalec?.naBesedilo(telo.optString("text"), od) }
            }
            "pair.invite.ok" -> {
                val t = json.optJSONObject("payload") ?: JSONObject()
                val p = Povabilo(t.optString("qr_id"), t.optString("secret"), t.optString("fp"), t.optString("address"),
                    (t.optDouble("expires_in_seconds", 300.0) * 1000).toLong())
                val naprej = naPovabilo
                naPovabilo = null
                if (naprej != null) glavna.post { naprej(if (p.qrId.isBlank() || p.skrivnost.isBlank()) null else p) }
            }
            "pair.code", "pair.done" -> si.safeer.tv.cast.HubKrmilnik.sporociloPrijave(json.optString("type"), json.optJSONObject("payload")) { id ->
                try { ws?.send(JSONObject().put("id", UUID.randomUUID().toString()).put("type", "pair.reject")
                    .put("payload", JSONObject().put("pair_id", id)).toString()) } catch (_: Throwable) { }
            }
            "trust.update" -> {
                // Krog zaupanja s sredisca: hranimo ga sami, da prezivimo menjavo sredisca.
                json.optJSONObject("payload")?.let { KrogNaprave.sprejmi(context, it.toString()) }
            }
        }
    }

    private fun potrdi(json: JSONObject) {
        val id = json.optString("id"); if (id.isBlank()) return
        try {
            ws?.send(JSONObject().put("id", UUID.randomUUID().toString()).put("type", "cast.ack")
                .put("ref_id", id).put("status", "accepted").toString())
        } catch (_: Throwable) { }
    }


}
