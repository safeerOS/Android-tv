package si.safeer.tv.os

import si.safeer.tv.R

import android.content.Context
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

    data class Naprava(val id: String, val ime: String, val vloga: String, val zmoznosti: List<String>, val naslov: String)

    interface Poslusalec {
        fun naStanje(povezan: Boolean, sporocilo: String)
        fun naNaprave(naprave: List<Naprava>)
        fun naNaslov(url: String, naslov: String, od: String)
        fun naBesedilo(besedilo: String, od: String)
        /** Sredisce te naprave ne pozna vec: zeton je treba dobiti znova. */
        fun naZavrnitev()
    }

    var poslusalec: Poslusalec? = null
    @Volatile var povezan = false
        private set
    var imeSredisca: String = ""
        private set
    /** Zadnji seznam naprav s sredisca (za zaslone, ki se odprejo, ko je povezava ze vzpostavljena). */
    @Volatile var naprave: List<Naprava> = emptyList()
        private set

    /** Odgovor na ukaz daljinca: `izid` je payload sporocila control.result (ok, message, data) ali null ob napaki/poteku. */
    fun interface Odgovor { fun na(izid: JSONObject?, napaka: String) }

    private class CakajociUkaz(val odgovor: Odgovor, val potek: Runnable)
    private val cakajoci = ConcurrentHashMap<String, CakajociUkaz>()

    private val glavna = Handler(Looper.getMainLooper())
    private var poverilnice: Sorodnik.Poverilnice? = null
    private var odjemalec: OkHttpClient? = null
    private var ws: WebSocket? = null
    private var tece = false
    private var poskusov = 0
    private val idNaprave: String by lazy { Identiteta.id(context) }

    fun zazeni(p: Sorodnik.Poverilnice) {
        poverilnice = p
        tece = true
        poskusov = 0
        odjemalec = zgradi(p.odtis)
        povezi()
    }

    fun ustavi() {
        tece = false
        try { ws?.close(1000, "konec") } catch (_: Throwable) { }
        ws = null
        povezan = false
    }

    private fun zgradi(odtis: String): OkHttpClient {
        val (tovarna, zaupnik) = Pin.tovarna(odtis)
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
        val z = Request.Builder().url(osnovaHttp(p.hubUrl) + pot)
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
        if (KrogNaprave.jeVpisana(context, idNaprave)) poveziSPodpisom(p) else poveziZZetonom(p)
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
            klic("/cast/auth/ticket", zahteva, null) { koda2, telo2 ->
                val j = try { JSONObject(telo2) } catch (_: Throwable) { JSONObject() }
                val vstopnica = j.optString("ticket")
                if (koda2 != 200 || vstopnica.isBlank()) { poveziZZetonom(p); return@klic }
                j.optJSONObject("ring")?.let { KrogNaprave.sprejmi(context, it.toString()) }
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
        val telo = JSONObject().put("pubkey", kljuc).put("name", "Safeer OS").put("platform", "tv")
        klic("/cast/trust/enroll", telo, p.zeton) { koda, odgovor ->
            if (koda != 200) { Log.i(TAG, "Sredisce kroga zaupanja ne pozna ($koda)."); return@klic }
            val ring = try { JSONObject(odgovor).optJSONObject("ring") } catch (_: Throwable) { null }
            if (KrogNaprave.sprejmi(context, ring?.toString())) Log.i(TAG, "Kljuc naprave vpisan v krog zaupanja.")
        }
    }

    private fun odpriZVstopnico(p: Sorodnik.Poverilnice, vstopnica: String) {
        val locilo = if (p.hubUrl.contains("?")) "&" else "?"
        odpri("${p.hubUrl}${locilo}ticket=$vstopnica")
    }

    private fun odpri(naslov: String) {
        val k = odjemalec ?: return
        ws = k.newWebSocket(Request.Builder().url(naslov).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                poskusov = 0
                povezan = true
                val prijava = JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("type", "cast.register")
                    .put("payload", JSONObject()
                        .put("device_id", idNaprave)
                        .put("name", "Safeer OS")
                        .put("role", "sender")
                        .put("capabilities", JSONArray(listOf("url", "text"))))
                webSocket.send(prijava.toString())
                javiStanje(true, "")
            }

            override fun onMessage(webSocket: WebSocket, text: String) = obdelaj(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Povezava padla: ${t.message}")
                povezan = false
                javiStanje(false, "")
                ponovno()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
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

    private fun koncajUkaz(refId: String, izid: JSONObject?, napaka: String) {
        val c = cakajoci.remove(refId) ?: return
        glavna.removeCallbacks(c.potek)
        glavna.post { c.odgovor.na(izid, napaka) }
    }

    private fun ponovno() {
        if (!tece) return
        val zamik = minOf(30_000L, 2_000L * (1 shl minOf(poskusov, 4)))
        poskusov++
        glavna.postDelayed({ if (tece && !povezan) povezi() }, zamik)
    }

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
                    seznam.add(Naprava(d.optString("id"), d.optString("name"), d.optString("role", "receiver"), zmoznosti, d.optString("ip")))
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

    companion object { private const val TAG = "SafeerOsLink" }
}
