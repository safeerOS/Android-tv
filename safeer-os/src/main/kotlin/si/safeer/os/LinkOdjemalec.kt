package si.safeer.os

import android.content.Context
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

    private fun povezi() {
        val p = poverilnice ?: return
        val k = odjemalec ?: return
        if (!tece) return
        val zahteva = Request.Builder()
            .url(osnovaHttp(p.hubUrl) + "/cast/ticket")
            .addHeader("X-Safeer-Token", p.zeton)
            .post("".toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        k.newCall(zahteva).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                Log.w(TAG, "Vstopnice ni: ${e.message}")
                javiStanje(false, "")
                ponovno()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.code == 401 || it.code == 403) {
                        Log.w(TAG, "Sredisce zetona ne sprejme (${it.code}).")
                        tece = false
                        glavna.post { poslusalec?.naZavrnitev() }
                        return
                    }
                    val vstopnica = try { JSONObject(it.body?.string().orEmpty()).optString("ticket") } catch (_: Throwable) { "" }
                    if (!it.isSuccessful || vstopnica.isBlank()) {
                        javiStanje(false, "")
                        ponovno()
                        return
                    }
                    val locilo = if (p.hubUrl.contains("?")) "&" else "?"
                    odpri("${p.hubUrl}${locilo}ticket=$vstopnica")
                }
            }
        })
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
                glavna.post { poslusalec?.naNaprave(seznam) }
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
