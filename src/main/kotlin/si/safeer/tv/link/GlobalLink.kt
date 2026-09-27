package si.safeer.tv.link

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import si.safeer.tv.cast.HubTls
import si.safeer.tv.cast.KrogZaupanja
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import si.safeer.tv.cast.HubKrmilnik
import si.safeer.tv.cast.KrogNaprave

/**
 * Global Link na Androidu: kadar domaci hub (npr. racunalnik) ni v istem omrezju, gre povezava do njega
 * prek link.safeer.si. Naprava odpre lokalna vrata 127.0.0.1; vsaka povezava nanje postane kanal releja
 * do huba. Skozi kanal tece nespremenjen TLS Safeer Linka (pripet odtis huba), zato rele vidi samo
 * sifrirane bajte. Worker spusti samo naprave iz hubovega kroga zaupanja (podpis s kljucem naprave).
 * Pot: LAN vedno prvi; rele sele, ko LAN ne odgovarja (GlobalMesh: LOCAL -> RELAY).
 */
object GlobalLink {
    const val GOSTITELJ = "link.safeer.si"
    private const val TAG = "SafeerGlobalLink"
    private const val PREFS = "safeer_global_link"

    fun vklopljen(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("vklopljen", true)
    /** Preizkus: tudi doma vedno prek releja (nastavitve). */
    fun samoRele(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("samo_rele", false)
    fun nastavi(c: Context, vklopljen: Boolean, samoRele: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("vklopljen", vklopljen)
            .putBoolean("samo_rele", vklopljen && samoRele).apply()
        if (!vklopljen) izklopi()
    }

    /** En rele na hub (sprejemnik in Safeer OS lahko hkrati uporabljata istega; drug drugemu ga ne zapreta). */
    private val releji = HashMap<String, Rele>()

    /** Hubi, ki jih rele ne pozna (404: ne objavlja se vec): minuto jih ne klicemo in ostanemo na LAN. */
    private val odsotni = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val PREMOR_ODSOTNEGA_MS = 60_000L
    fun hubOdsoten(id: String?): Boolean {
        val cilj = osnovniId(id) ?: return false
        return System.currentTimeMillis() - (odsotni[cilj] ?: 0L) < PREMOR_ODSOTNEGA_MS
    }

    /** Osnovni id huba iz kljuca (`n-<16 hex>`, brez pripone sorodnika) ali null. */
    fun osnovniId(id: String?): String? {
        val s = id ?: return null
        return if (KrogZaupanja.jeIdIzKljuca(s)) s.take(KrogZaupanja.DOLZINA_ID_IZ_KLJUCA) else null
    }

    /**
     * Naslov, na katerega naj se odjemalec poveze: [hubUrl] (LAN) ali lokalna vrata releja do [hubId],
     * kadar [prekRele] in je Global Link vklopljen. Pot (/cast/ws ...) ostane ista.
     */
    fun naslov(c: Context, hubUrl: String, hubId: String?, prekRele: Boolean): String {
        if (!(prekRele || samoRele(c)) || !vklopljen(c)) return hubUrl
        // Lastni hub te naprave (127.0.0.1) je vedno tu; rele je samo za hub na drugi napravi.
        val lokalno = try { InetAddress.getByName(URI(hubUrl).host).isLoopbackAddress } catch (_: Throwable) { false }
        if (lokalno) return hubUrl
        val cilj = osnovniId(hubId) ?: return hubUrl
        // Hub se ne objavlja (npr. ugasnjen): nazaj na LAN, kjer volitve najdejo novega; brez klicev releja.
        if (hubOdsoten(cilj) && !samoRele(c)) return hubUrl
        val r = synchronized(this) {
            releji[cilj]?.takeIf { it.tece } ?: Rele(c.applicationContext, cilj).also { releji[cilj] = it }
        }
        val pot = try { URI(hubUrl).rawPath.orEmpty() } catch (_: Throwable) { "" }
        return "wss://127.0.0.1:${r.vrata}$pot"
    }

    /** Ali povezava na [host]:[port] tece skozi enega od nasih relejev (sicer je LAN ali lastni hub). */
    fun jeRele(host: String, port: Int): Boolean =
        host == "127.0.0.1" && synchronized(this) { releji.values.any { it.tece && it.vrata == port } }

    fun izklopi() = synchronized(this) { releji.values.forEach { it.zapri() }; releji.clear() }

    /** Ali je naslov LAN huba dosegljiv (kratek TCP poskus); za vrnitev z releja domov. */
    fun lanDosegljiv(hubUrl: String): Boolean = try {
        val u = URI(hubUrl)
        Socket().use { it.connect(InetSocketAddress(u.host, if (u.port > 0) u.port else 443), 1500) }
        true
    } catch (_: Throwable) { false }

    /** Glave X-Safeer-*: podpis nad METODA\nPOT\nCAS\nhex(SHA-256(telo)) s kljucem naprave (kot worker.mjs). */
    fun podpisaneGlave(metoda: String, pot: String, telo: ByteArray = ByteArray(0)): Map<String, String> {
        val cas = (System.currentTimeMillis() / 1000).toString()
        val izvlecek = MessageDigest.getInstance("SHA-256").digest(telo).joinToString("") { "%02x".format(it) }
        val podpis = HubTls.podpisi("$metoda\n$pot\n$cas\n$izvlecek".toByteArray(Charsets.UTF_8))
        return mapOf("X-Safeer-Key" to HubTls.javniKljucB64(), "X-Safeer-Time" to cas, "X-Safeer-Signature" to podpis)
    }

    /** Lokalna vrata do huba [cilj]; vsaka sprejeta povezava je en kanal releja. */
    class Rele(private val context: Context, val cilj: String) {
        // Izrecno IPv4: na Androidu InetAddress.getLoopbackAddress() vrne ::1, odjemalec pa se poveze na 127.0.0.1.
        private val streznik = ServerSocket(0, 16, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val vrata: Int = streznik.localPort
        @Volatile var tece = true; private set
        private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(25, TimeUnit.SECONDS).build()

        init {
            Thread({
                while (tece) {
                    val s = try { streznik.accept() } catch (_: Throwable) { break }
                    kanal(s)
                }
            }, "safeer-global-link").apply { isDaemon = true }.start()
            Log.i(TAG, "Rele do $cilj na 127.0.0.1:$vrata")
        }

        private fun kanal(tcp: Socket) {
            if (hubOdsoten(cilj)) { try { tcp.close() } catch (_: Throwable) { }; return }
            val kanal = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val pot = "/v1/connect?to=$cilj&kanal=$kanal"
            val z = Request.Builder().url("https://$GOSTITELJ$pot").header("User-Agent", "SafeerLink/1.0")
            try { podpisaneGlave("GET", pot).forEach { (k, v) -> z.header(k, v) } } catch (e: Throwable) {
                Log.w(TAG, "Podpisa ni: ${e.message}"); try { tcp.close() } catch (_: Throwable) { }; return
            }
            http.newWebSocket(z.build(), cev(tcp, takoj = false, cilj = cilj))
        }

        fun zapri() {
            tece = false
            try { streznik.close() } catch (_: Throwable) { }
            http.dispatcher.executorService.shutdown()
        }
    }

    // ------------------------------------------------------------------ hub na tej napravi

    /**
     * Kadar ta naprava (TV, tablica, telefon) gosti hub, je ta dosegljiv napravam iz njenega kroga tudi
     * zunaj doma - enako kot Control na racunalniku (core/link_rele.py AgentHuba). Objavi, kdo sme do
     * njega (clani kroga), poslusa na /v1/listen in vsak kanal (/v1/accept) poveze z lokalnim hubom.
     */
    object AgentHuba {
        @Volatile private var nit: Thread? = null
        private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(30, TimeUnit.SECONDS).build()

        fun zazeni(c: Context) {
            if (nit != null) return
            val app = c.applicationContext
            nit = Thread({ zanka(app) }, "safeer-global-link-hub").apply { isDaemon = true; start() }
        }

        private fun objavi(c: Context): Boolean {
            val nas = KrogZaupanja.idIzKljuca(HubTls.javniKljucB64())
            val dovoljeni = JSONArray()
            KrogNaprave.krog(c).clani().mapNotNull { runCatching { KrogZaupanja.idIzKljuca(it.kljuc) }.getOrNull() }
                .filter { it != nas }.distinct().take(256).forEach { dovoljeni.put(it) }
            val telo = JSONObject().put("allow", dovoljeni).put("ttl", 120).toString().toByteArray()
            val z = Request.Builder().url("https://$GOSTITELJ/v1/presence").header("User-Agent", "SafeerLink/1.0")
                .post(telo.toRequestBody("application/json".toMediaType()))
            podpisaneGlave("POST", "/v1/presence", telo).forEach { (k, v) -> z.header(k, v) }
            return http.newCall(z.build()).execute().use { it.isSuccessful }
        }

        private fun zanka(c: Context) {
            var cakaj = 5_000L
            while (true) {
                if (!(vklopljen(c) && HubKrmilnik.tece() && HubKrmilnik.vrata() > 0)) { Thread.sleep(15_000); continue }
                try {
                    if (!objavi(c)) throw IllegalStateException("prisotnost zavrnjena")
                    val konec = java.util.concurrent.CountDownLatch(1)
                    val z = Request.Builder().url("https://$GOSTITELJ/v1/listen").header("User-Agent", "SafeerLink/1.0")
                    podpisaneGlave("GET", "/v1/listen").forEach { (k, v) -> z.header(k, v) }
                    val ws = http.newWebSocket(z.build(), object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { Log.i(TAG, "Hub te naprave je dosegljiv prek $GOSTITELJ") }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val j = try { JSONObject(text) } catch (_: Throwable) { return }
                            val kanal = j.optString("kanal")
                            if (j.optString("type") == "incoming" && kanal.length == 32) Thread({ sprejmi(kanal) }, "safeer-global-link-kanal").apply { isDaemon = true }.start()
                        }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = konec.countDown()
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            Log.i(TAG, "Poslusanje prekinjeno (${response?.code ?: t.message})"); konec.countDown()
                        }
                    })
                    cakaj = 5_000L
                    // Vsakih 10 s preverimo, ali hub se tece (sicer takoj nehamo sprejemati kanale);
                    // prisotnost obnovimo vsako minuto.
                    var krog = 0
                    while (!konec.await(10, TimeUnit.SECONDS)) {
                        if (!(vklopljen(c) && HubKrmilnik.tece())) { ws.close(1000, "izklop"); break }
                        if (++krog % 6 == 0) try { objavi(c) } catch (_: Throwable) { }
                    }
                } catch (e: Throwable) {
                    Log.i(TAG, "Global Link huba: ${e.message}; znova cez ${cakaj / 1000} s")
                    Thread.sleep(cakaj)
                    cakaj = (cakaj * 2).coerceAtMost(300_000L)
                }
            }
        }

        private fun sprejmi(kanal: String) {
            val tcp = try { Socket("127.0.0.1", HubKrmilnik.vrata()) } catch (_: Throwable) { return }
            val pot = "/v1/accept?kanal=$kanal"
            val z = Request.Builder().url("https://$GOSTITELJ$pot").header("User-Agent", "SafeerLink/1.0")
            try { podpisaneGlave("GET", pot).forEach { (k, v) -> z.header(k, v) } } catch (_: Throwable) { tcp.close(); return }
            http.newWebSocket(z.build(), cev(tcp, takoj = true))
        }
    }

    /** Poslusalec, ki bajte kanala prenasa v lokalno TCP povezavo in nazaj ([takoj]: brez cakanja na "ready"). */
    internal fun cev(tcp: Socket, takoj: Boolean, cilj: String? = null): WebSocketListener = object : WebSocketListener() {
        private fun posiljaj(webSocket: WebSocket) {
            Thread({
                val buf = ByteArray(16 * 1024)
                try {
                    val vhod = tcp.getInputStream()
                    while (true) {
                        val n = vhod.read(buf)
                        if (n < 0) break
                        while (webSocket.queueSize() > 1_000_000) Thread.sleep(10)
                        if (!webSocket.send(buf.toByteString(0, n))) break
                    }
                } catch (_: Throwable) { }
                webSocket.close(1000, "konec")
            }, "safeer-global-link-tx").apply { isDaemon = true }.start()
        }
        @Volatile private var pripravljen = takoj
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (takoj) { posiljaj(webSocket); return }
            // Hub mora kanal sprejeti v 20 s; sicer ga ni (npr. ravno nehal gostiti) - ne cakamo v nedogled.
            Thread({
                Thread.sleep(20_000)
                if (!pripravljen) {
                    Log.i(TAG, "Hub kanala ni sprejel v 20 s")
                    if (cilj != null) odsotni[cilj] = System.currentTimeMillis()
                    webSocket.cancel(); zapri()
                }
            }, "safeer-global-link-rok").apply { isDaemon = true }.start()
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!takoj && text == "ready" && !pripravljen) { pripravljen = true; posiljaj(webSocket) }
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try { tcp.getOutputStream().write(bytes.toByteArray()) } catch (_: Throwable) { webSocket.close(1000, "konec") }
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { zapri(); webSocket.close(1000, null) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = zapri()
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.i(TAG, "Kanal releja ni uspel (${response?.code ?: t.message})")
            if (response?.code == 404 && cilj != null) odsotni[cilj] = System.currentTimeMillis()
            zapri()
        }
        private fun zapri() { try { tcp.close() } catch (_: Throwable) { } }
    }
}
