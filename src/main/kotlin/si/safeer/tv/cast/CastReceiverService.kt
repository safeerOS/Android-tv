package si.safeer.tv.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Safeer Cast Receiver Service za Android TV (tv-browser-2).
 *
 * Deluje kot zanesljiva storitev v ozadju, ki vzdržuje WebSocket povezavo s Safeer Cast Hubom
 * ali sprejema neposredne ukaze za predvajanje URL-jev in medijskih tokov.
 */
class CastReceiverService : Service() {

    companion object {
        private const val TAG = "SafeerCastReceiver"
        /** Domaca stran brskalnika; tipka Domov z daljinca Safeer Controla jo odpre. */
        const val DOMACA_STRAN = "file:///android_asset/brave_home.html"
        private const val CHANNEL_ID = "safeer_cast_channel"
        private const val NOTIFICATION_ID = 4040

        const val ACTION_START = "si.safeer.tv.cast.START"
        const val ACTION_STOP = "si.safeer.tv.cast.STOP"
        const val EXTRA_HUB_URL = "extra_hub_url"
        const val EXTRA_DEVICE_NAME = "extra_device_name"

        /**
         * Privzetega vozlišča ni. Brskalnik je uporaben sam; Hub je nadgradnja, ki jo
         * uporabnik doda, če jo ima. Trdo zapisan naslov bi pomenil, da vsaka nameščena
         * kopija trka na tuje omrežje.
         */
        const val DEFAULT_HUB_URL = ""

        /** Ali je vozlišče sploh nastavljeno na tej napravi. */
        fun isConfigured(context: Context): Boolean =
            !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_HUB_URL, "").isNullOrBlank()
        const val PREFS_NAME = "safeer_cast_prefs"
        const val KEY_HUB_URL = "hub_url"
        const val KEY_CONTROL_TOKEN = "control_token"
        const val KEY_TICKET_PATH = "hub_ticket_path"

        const val ACTION_OPEN_CAST = "si.safeer.tv.cast.OPEN"
        const val EXTRA_CAST_URL = "cast_url"
        const val EXTRA_CAST_TITLE = "cast_title"
        const val EXTRA_CAST_POSITION = "cast_position"
        private const val WAKE_NOTIFICATION_ID = 4041
        private const val WAKE_CHANNEL_ID = "safeer_cast_wake"
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val IDLE_RETRY_MS = 600_000L

        /** MainActivity javi, ali je v ospredju; ce ni, naslov odpremo z namero. */
        @Volatile
        var krmilnikVOspredju: Boolean = false

        // Callback vmesnik za povezavo z MainActivity / ChromiumEngineView
        var mediaController: CastMediaController? = null

        /** Tekoča storitev, da lahko aplikacija pošlje stanje predvajanja nazaj pošiljatelju. */
        @Volatile
        var instance: CastReceiverService? = null
            private set

        /**
         * Zadnji seznam naprav, kot ga je javil Hub (cast.devices). Televizor ni samo zaslon:
         * stran Safeer Linka iz njega posilja odprto stran in besedilo, zato mora vedeti, komu.
         */
        @Volatile
        var zadnjeNaprave: String = "[]"
            private set

        /** Stran Safeer Linka se prijavi, da izve za nov seznam naprav in za stanje povezave. */
        @Volatile
        var naSpremembeNaprav: ((String) -> Unit)? = null

        @Volatile
        var naPovezavo: ((Boolean) -> Unit)? = null

        /** Odgovor Huba na nase posiljanje: (ref_id, status, error_code, error). */
        @Volatile
        var naPotrditev: ((String, String, String, String) -> Unit)? = null

        /** Odgovor na ukaz daljinca (control.result) ali zavrnitev sredisca (control.ack), za stran Linka. */
        @Volatile
        var naUkazOdziv: ((JSONObject) -> Unit)? = null

        @Volatile
        var povezan: Boolean = false
            private set

        fun start(context: Context, hubUrl: String? = null, deviceName: String? = null) {
            // Brez nastavljenega vozlišča storitve sploh ne zaženemo: nobenega obvestila,
            // nobenega omrežnega prometa, nič, kar bi uporabnik brez Huba sploh opazil.
            if (hubUrl.isNullOrBlank() && !isConfigured(context)) {
                Log.i(TAG, "Safeer Hub ni nastavljen - sprejemnika ne zaganjam.")
                return
            }
            val intent = Intent(context, CastReceiverService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HUB_URL, hubUrl)
                putExtra(EXTRA_DEVICE_NAME, deviceName)
            }
            context.startForegroundService(intent)
        }
    }

    interface CastMediaController {
        fun onCastUrlReceived(url: String, title: String?, startPosition: Double)
        fun onCastControl(action: String, position: Double?, volume: Double?)
        fun getCurrentPlaybackState(): Map<String, Any?>

        // Deljenje prek Safeer Linka (besedilo, zaslon, datoteka). Privzeto se ne zgodi nic,
        // da starejsi krmilniki ostanejo veljavni; brskalnik na televizorju jih prepise.
        fun onShareText(od: String, besedilo: String) {}
        fun onShareScreenStarted(url: String, od: String) {}
        fun onShareScreenStopped(id: String) {}
        fun onShareFileReceived(ime: String, pot: java.io.File, od: String) {}
    }

    /**
     * Odjemalec za Hub: TLS z odtisom potrdila, ki si ga je naprava zapomnila ob seznanitvi.
     * Zgradi se ob vsaki povezavi, da po (ponovni) seznanitvi vzame nov odtis.
     */
    @Volatile
    private var client: OkHttpClient = zgradiOdjemalca()

    private fun zgradiOdjemalca(): OkHttpClient {
        val g = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
        val odtis = try { HubTls.pripetiOdtis(this) } catch (_: Throwable) { null }
        // Izvoljeni hub (drug clan kroga): poleg odtisa iz oglasa mora potrdilo nositi njegov kljuc iz kroga.
        val kljuc = try { HubKrmilnik.izvoljeniHub(this)?.let { KrogNaprave.kljucHuba(this, it.id) } } catch (_: Throwable) { null }
        return HubTls.okhttp(g, odtis, kljuc).first.build()
    }

    private var webSocket: WebSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var hubUrl: String = DEFAULT_HUB_URL
    /** Id iz kljuca naprave (HubKrmilnik.lastniId); isti, kot ga hub te naprave vpise v krog in oglasa po mDNS. */
    private val deviceId: String by lazy { HubKrmilnik.lastniId() }
    private var deviceName: String = "Android TV"
    private var isRunning = false
    private var reconnectAttempts = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Vklop televizorja iz pripravljenosti: Safeer OS naj bo prvo, kar se vidi (os/VklopTelevizorja).
        si.safeer.tv.os.VklopTelevizorja.namesti(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Naslov vozlišča: 1. iz namere, 2. iz shranjenih nastavitev, 3. privzeti.
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prejsnjiNaslov = hubUrl
        val fromIntent = intent?.getStringExtra(EXTRA_HUB_URL)
        hubUrl = when {
            !fromIntent.isNullOrBlank() -> fromIntent.also { prefs.edit().putString(KEY_HUB_URL, it).apply() }
            else -> prefs.getString(KEY_HUB_URL, DEFAULT_HUB_URL) ?: DEFAULT_HUB_URL
        }
        intent?.getStringExtra(EXTRA_DEVICE_NAME)?.let { if (it.isNotBlank()) deviceName = it }
        instance = this

        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        si.safeer.tv.link.DatotekeStreznik.pripravi(this)
        // Ze odprte povezave na isti naslov ne odpiramo znova: druga povezava iste naprave bi na hubu
        // zamenjala prvo, prva pa bi obvisela in cez pol minute sprozila nov krog zamenjav.
        val zePovezan = isRunning && povezan && webSocket != null && prejsnjiNaslov == hubUrl
        isRunning = true
        if (!zePovezan) connectToHub()

        return START_STICKY
    }

    private fun connectToHub() {
        if (!isRunning) return
        // Nov rod povezave: prejsnjo zapremo, njeni pozni klici (padec, zaprtje) nic vec ne spremenijo.
        val moj = ++rod
        webSocket?.cancel()
        webSocket = null

        if (!hubUrl.startsWith("wss://")) {
            // Brez TLS bi zeton in vse, kar delimo, potovalo v cistem besedilu. Tak Hub naj se posodobi.
            Log.w(TAG, "Hub brez TLS ($hubUrl) - povezava zavrnjena; posodobi Safeer na gostitelju.")
            return
        }
        client = zgradiOdjemalca()
        Log.i(TAG, "Povezujem se na Safeer Cast Hub: $hubUrl (naprava: $deviceId)")
        // S podpisom tudi, ce je nas kljuc v krogu pod starim id-jem: hub nov id sam vpise kot alias.
        val vpisan = try { KrogNaprave.lahkoSPodpisom(this, deviceId) } catch (_: Throwable) { false }
        if (vpisan) zVstopnicoSPodpisom(hubUrl) { naslov -> odpriPovezavo(naslov, moj) }
        else {
            // Nas kljuc je v krogu pod drugim id (npr. Safeer OS iste naprave): ta id vpisemo kot alias
            // s podpisom, nato pridemo s podpisom tudi sami. Sicer po starem, z zetonom.
            val znani = try { KrogNaprave.znaniIdZaNasKljuc(this) } catch (_: Throwable) { null }
            if (znani != null && HubKrmilnik.izvoljeniHub(this) != null) vpisiAlias(hubUrl, znani) { uspelo ->
                if (uspelo) zVstopnicoSPodpisom(hubUrl) { naslov -> odpriPovezavo(naslov, moj) }
                else zVstopnico(hubUrl, controlToken()) { naslov -> odpriPovezavo(naslov, moj) }
            } else zVstopnico(hubUrl, controlToken()) { naslov -> odpriPovezavo(naslov, moj) }
        }
    }

    /** V krog izvoljenega huba vpise ta id kot alias id-ja [znani] (isti kljuc, dokazan s podpisom). */
    private fun vpisiAlias(wsUrl: String, znani: String, naprej: (Boolean) -> Unit) {
        klic("/cast/auth/challenge", JSONObject().put("device_id", znani)) { koda, telo ->
            val j = try { JSONObject(telo) } catch (_: Throwable) { JSONObject() }
            val nonce = j.optString("nonce")
            if (koda != 200 || nonce.isBlank()) { naprej(false); return@klic }
            val odtisHuba = j.optString("fp").ifBlank { HubTls.pripetiOdtis(this).orEmpty() }
            val podpis = try { KrogNaprave.podpisPrijave(znani, odtisHuba, nonce) } catch (_: Throwable) { naprej(false); return@klic }
            val telo2 = JSONObject().put("device_id", znani).put("nonce", nonce).put("signature", podpis)
                .put("alias", deviceId).put("name", deviceName).put("platform", HubKrmilnik.platforma(this))
            klic("/cast/trust/alias", telo2) { koda2, odgovor ->
                if (koda2 != 200) { Log.i(TAG, "Aliasa v krogu ni bilo mogoce vpisati ($koda2)."); naprej(false); return@klic }
                try { JSONObject(odgovor).optJSONObject("ring")?.let { KrogNaprave.sprejmi(this, it.toString()) } } catch (_: Throwable) { }
                Log.i(TAG, "Id $deviceId vpisan v krog kot alias id-ja $znani.")
                naprej(true)
            }
        }
    }

    private fun osnova(wsUrl: String): String =
        wsUrl.replace(Regex("^wss"), "https").replace(Regex("^ws"), "http")
            .substringBefore("/cast/ws").substringBefore("/link/ws").substringBefore("/safeer/ws").trimEnd('/')

    /** POST JSON na hub; naprej(koda, telo), napaka omrezja = koda 0. */
    private fun klic(pot: String, telo: JSONObject?, naprej: (Int, String) -> Unit) {
        val z = Request.Builder().url("${osnova(hubUrl)}$pot")
            .post((telo?.toString() ?: "").toRequestBody("application/json".toMediaTypeOrNull()))
        client.newCall(z.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { naprej(0, "") }
            override fun onResponse(call: Call, response: Response) { response.use { naprej(it.code, it.body?.string().orEmpty()) } }
        })
    }

    /**
     * Vstopnica s podpisom kljuca naprave (krog zaupanja): izziv -> podpis -> vstopnica. Tako se
     * televizor prijavi tudi izvoljenemu hubu, s katerim ni bil nikoli seznanjen s kodo. Ce hub
     * kroga ne pozna ali nas v njem nima, gre po starem z zetonom.
     */
    private fun zVstopnicoSPodpisom(wsUrl: String, naprej: (String) -> Unit) {
        klic("/cast/auth/challenge", JSONObject().put("device_id", deviceId)) { koda, telo ->
            val j = try { JSONObject(telo) } catch (_: Throwable) { JSONObject() }
            val nonce = j.optString("nonce")
            if (koda != 200 || nonce.isBlank()) {
                // Izvoljeni hub nas pod tem id ne pozna (401), nas kljuc pa ima pod drugim id (npr. Safeer OS
                // te naprave): vpisemo alias in poskusimo znova. Lastni krog tu ne steje - odloca hubov.
                val znani = if (koda == 401 && HubKrmilnik.izvoljeniHub(this) != null) try { KrogNaprave.znaniIdZaNasKljuc(this, razen = deviceId) } catch (_: Throwable) { null } else null
                if (znani != null) {
                    vpisiAlias(wsUrl, znani) { uspelo -> if (uspelo) zVstopnicoSPodpisom(wsUrl, naprej) else zVstopnico(wsUrl, controlToken(), naprej) }
                    return@klic
                }
                Log.i(TAG, "Prijava s podpisom ni mogoca ($koda); z zetonom.")
                zVstopnico(wsUrl, controlToken(), naprej); return@klic
            }
            val odtisHuba = j.optString("fp").ifBlank { HubTls.pripetiOdtis(this).orEmpty() }
            val podpis = try { KrogNaprave.podpisPrijave(deviceId, odtisHuba, nonce) } catch (e: Throwable) {
                Log.w(TAG, "Podpisa ni bilo mogoce narediti: ${e.message}"); zVstopnico(wsUrl, controlToken(), naprej); return@klic
            }
            klic("/cast/auth/ticket", JSONObject().put("device_id", deviceId).put("nonce", nonce).put("signature", podpis)) { koda2, telo2 ->
                val j2 = try { JSONObject(telo2) } catch (_: Throwable) { JSONObject() }
                val vstopnica = j2.optString("ticket")
                if (koda2 != 200 || vstopnica.isBlank()) {
                    Log.w(TAG, "Hub podpisa ni sprejel ($koda2); z zetonom.")
                    zVstopnico(wsUrl, controlToken(), naprej); return@klic
                }
                j2.optJSONObject("ring")?.let { KrogNaprave.sprejmi(this, it.toString()) }
                shraniSejo(j2.optString("session_token"))
                Log.i(TAG, "Prijava s podpisom kljuca naprave.")
                val locilo = if (wsUrl.contains("?")) "&" else "?"
                naprej("$wsUrl${locilo}ticket=$vstopnica")
            }
        }
    }

    /**
     * Sejni zeton izvoljenega huba (prijava s podpisom) shranimo kot zeton za trenutni hub: z njim
     * naprava po HTTP deli zaslon, datoteke in besedilo, kot bi bila s hubom seznanjena s kodo.
     * Pravega zetona seznanitve (lasten hub, seznanitev s kodo) nikoli ne prepisemo.
     */
    private fun shraniSejo(seja: String) {
        if (seja.isBlank() || HubKrmilnik.izvoljeniHub(this) == null) return
        val trenutni = controlToken().orEmpty()
        if (trenutni.isNotBlank() && !trenutni.startsWith("saf_seja_")) return
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_CONTROL_TOKEN, seja).apply()
    }

    /** Zeton za Safeer Control; nastavi se ob seznanitvi televizorja. */
    fun controlToken(): String? =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_CONTROL_TOKEN, null)

    /** Rod trenutne povezave; povecan ob vsakem connectToHub. */
    @Volatile
    private var rod = 0

    private fun odpriPovezavo(naslov: String, moj: Int) {
        if (!isRunning || moj != rod) return
        val request = Request.Builder().url(naslov).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (moj != rod) { webSocket.cancel(); return }
                Log.i(TAG, "Uspešno povezan s Cast Hubom!")
                reconnectAttempts = 0
                povezan = true
                try { naPovezavo?.invoke(true) } catch (_: Throwable) { }

                // 1. Registracija naprave kot Receiver
                val registerMsg = JSONObject().apply {
                    put("id", UUID.randomUUID().toString())
                    put("type", "cast.register")
                    put("payload", JSONObject().apply {
                        put("device_id", deviceId)
                        put("name", deviceName)
                        put("role", "receiver")
                        put("capabilities", org.json.JSONArray(listOf("url", "media", "control", "volume", "seek", "text", "file", "screen", si.safeer.tv.link.Daljinec.ZMOZNOST,
                            si.safeer.tv.link.Daljinec.ZMOZNOST_ZVOK, si.safeer.tv.link.DatotekeStreznik.ZMOZNOST)))
                        // Protocol v1: model naprave in katalog aplikacij, ki jih zna ta zaslon zagnati.
                        HubKrmilnik.poljaV1(this@CastReceiverService, "screen", this, HubKrmilnik.prioriteta(this@CastReceiverService))
                        val katalog = try { si.safeer.tv.link.Daljinec.katalog(this@CastReceiverService) } catch (_: Throwable) { null }
                        if (katalog != null && katalog.length() > 0) put("apps", katalog)
                    })
                }
                webSocket.send(registerMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (moj != rod) return
                handleIncomingMessage(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Hub zapira (npr. ker je ista naprava prisla z novo povezavo): odgovorimo, sicer
                // OkHttp povezave ne zapre in cez pol minute javi manjkajoci pong.
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (moj != rod) return
                Log.w(TAG, "Povezava s hubom padla: ${t.message}. Poskus ponovne povezave...")
                odklopljen()
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (moj != rod) return
                Log.i(TAG, "Povezava zaprta ($code): $reason")
                odklopljen()
                scheduleReconnect()
            }
        })
    }

    /**
     * Naslov odpre v brskalniku tudi takrat, ko ta ni v ospredju. Android 10+ zagon dejavnosti
     * iz ozadja pogosto zavrne, zato poleg neposrednega poskusa objavimo se obvestilo s
     * celozaslonsko namero -- tega sistem odpre sam.
     */
    private fun odpriVBrskalniku(url: String, title: String?, startPos: Double) {
        // Tablica: poslana stran gre v mobilni Safeer. Deljen zaslon (stran s Huba) ostane v vgrajenem,
        // ki zaupa potrdilu Huba.
        val hub = hubHttpOsnova()
        val mobilna = if (hub.isNotEmpty() && url.startsWith(hub)) null
            else si.safeer.tv.os.Brskalnik.mobilniNaslov(this, url)
        val namera = mobilna ?: Intent().apply {
            setClassName(packageName, "si.safeer.tv.MainActivity")
            action = ACTION_OPEN_CAST
            putExtra(EXTRA_CAST_URL, url)
            putExtra(EXTRA_CAST_TITLE, title ?: "")
            putExtra(EXTRA_CAST_POSITION, startPos)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        // Android 10+ zagon dejavnosti iz ozadja zavrne tiho, brez izjeme, zato tega poskusa
        // ne zapisujemo kot uspeh; pravi mehanizem je obvestilo s celozaslonsko namero spodaj.
        try {
            startActivity(namera)
        } catch (e: Exception) {
            Log.w(TAG, "Neposredni zagon brskalnika ni uspel: ${e.message}")
        }
        pripraviKanalZaPrebujanje()
        try {
            val zastavice = android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            val cakajoca = android.app.PendingIntent.getActivity(
                this, WAKE_NOTIFICATION_ID, namera, zastavice)
            val gradnik = android.app.Notification.Builder(this, WAKE_CHANNEL_ID)
            val obvestilo = gradnik
                .setContentTitle("Safeer Cast")
                .setContentText(if (title.isNullOrBlank()) url else title)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(cakajoca)
                .setFullScreenIntent(cakajoca, true)
                .setAutoCancel(true)
                .build()
            val upravitelj = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            upravitelj.notify(WAKE_NOTIFICATION_ID, obvestilo)
            Log.i(TAG, "Objavljeno obvestilo s celozaslonsko namero za $url")
        } catch (e: Exception) {
            Log.w(TAG, "Obvestila s celozaslonsko namero ni bilo mogoce objaviti: ${e.message}")
        }
    }

    /**
     * Kanal za prebujanje mora biti IMPORTANCE_HIGH, sicer sistem celozaslonske namere ne odpre.
     * Kanal storitve v ospredju ostane tih, da med gledanjem ne moti.
     */
    private fun pripraviKanalZaPrebujanje() {
        try {
            val upravitelj = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (upravitelj.getNotificationChannel(WAKE_CHANNEL_ID) != null) return
            val kanal = android.app.NotificationChannel(
                WAKE_CHANNEL_ID,
                "Safeer Cast - prihajajoca vsebina",
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
            kanal.description = "Odpre brskalnik, ko telefon poslje povezavo na televizor."
            kanal.setShowBadge(false)
            upravitelj.createNotificationChannel(kanal)
        } catch (e: Exception) {
            Log.w(TAG, "Kanala za prebujanje ni bilo mogoce ustvariti: ${e.message}")
        }
    }


    /**
     * Vzame enokratno vstopnico pri Safeer Controlu in sele nato odpre WebSocket.
     *
     * Vstopnica velja 30 sekund in se porabi ob prvi uporabi, zato jo vzamemo pri vsaki
     * povezavi posebej. Ce zetona ni, se povezemo brez nje (staro vozlisce) in to povemo
     * v dnevniku -- nezasciteno pot pustimo vidno, ne tiho.
     */
    private fun zVstopnico(wsUrl: String, token: String?, naprej: (String) -> Unit) {
        if (token.isNullOrBlank()) {
            Log.w(TAG, "Zeton za Safeer Control ni nastavljen - povezujem se BREZ avtentikacije.")
            naprej(wsUrl)
            return
        }
        val osnova = wsUrl.replace(Regex("^wss"), "https").replace(Regex("^ws"), "http")
            .substringBefore("/cast/ws").substringBefore("/link/ws").substringBefore("/safeer/ws")
            .trimEnd('/')
        val zahteva = Request.Builder()
            .url("$osnova${ticketPath()}")
            .addHeader("X-Safeer-Token", token)
            .post("".toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        client.newCall(zahteva).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                Log.w(TAG, "Vstopnice ni bilo mogoce dobiti: ${e.message}")
                // Brez tega bi sprejemnik po padlem hubu obtical brez ponovnega poskusa (in brez izvolitve).
                mainHandler.post { odklopljen(); scheduleReconnect() }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val telo = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        Log.w(TAG, "Control je zavrnil zahtevo za vstopnico (${it.code}).")
                        mainHandler.post { odklopljen(); scheduleReconnect() }
                        return
                    }
                    val vstopnica = try {
                        JSONObject(telo).optString("ticket")
                    } catch (e: Exception) {
                        ""
                    }
                    if (vstopnica.isBlank()) {
                        Log.w(TAG, "Odgovor Controla ne vsebuje vstopnice.")
                        return
                    }
                    val locilo = if (wsUrl.contains("?")) "&" else "?"
                    naprej("$wsUrl${locilo}ticket=$vstopnica")
                }
            }
        })
    }


    /** Pot do vstopnice, kot jo je objavil Hub (privzeto /cast/ticket). */
    private fun ticketPath(): String =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TICKET_PATH, "/cast/ticket") ?: "/cast/ticket"

    private fun scheduleReconnect() {
        if (!isRunning) return
        reconnectAttempts++
        // Vozlišče je lahko preprosto ugasnjeno. Deset poskusov z naraščajočim premorom,
        // nato mirujemo deset minut -- televizor ne sme vso noč trkati na vrata, ki jih ni.
        val delayMs = if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            if (reconnectAttempts == MAX_RECONNECT_ATTEMPTS + 1) {
                Log.i(TAG, "Vozlišča ni; poskušam znova vsakih 10 minut.")
            }
            IDLE_RETRY_MS
        } else {
            (reconnectAttempts * 2000L).coerceAtMost(30000L)
        }
        // Po treh neuspehih pogledamo, ali se Safeer Link javlja kje drugje: sredisce je
        // morda prevzel telefon ali racunalnik (ali je dobilo nov naslov). Ce je bil televizor
        // z njim ze seznanjen, HubDiscovery preklopi naslov in zeton brez nove kode.
        if (reconnectAttempts == 3 || (reconnectAttempts > MAX_RECONNECT_ATTEMPTS && reconnectAttempts % 3 == 0)) {
            // Izvoljeni hub je izginil: ce je uporabnik Link prizgal, televizor spet gosti sam.
            if (HubKrmilnik.izvoljeniHub(this) != null && HubKrmilnik.jeZazelen(this)) {
                mainHandler.postDelayed({ HubKrmilnik.izvoljeniHubIzgubljen(this) }, delayMs / 2)
            } else {
                mainHandler.postDelayed({ poisciDrugoSredisce() }, delayMs / 2)
            }
        }
        mainHandler.postDelayed({ connectToHub() }, delayMs)
    }

    private fun poisciDrugoSredisce() {
        if (!isRunning) return
        val prej = hubUrl
        try {
            HubDiscovery.discover(this) { naslov ->
                if (!isRunning || naslov.isNullOrBlank()) return@discover
                val nov = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_HUB_URL, naslov) ?: naslov
                if (nov != prej) {
                    // Naslov zamenjamo; ze nacrtovani ponovni poskus (scheduleReconnect) ga vzame.
                    Log.i(TAG, "Sredisce se je preselilo: $prej -> $nov")
                    hubUrl = nov
                    reconnectAttempts = 0
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Iskanja drugega sredisca ni bilo mogoce zagnati: ${e.message}")
        }
    }

    private fun odklopljen() {
        povezan = false
        zadnjeNaprave = "[]"
        try { naPovezavo?.invoke(false) } catch (_: Throwable) { }
        try { naSpremembeNaprav?.invoke("[]") } catch (_: Throwable) { }
    }

    /**
     * Poslje odprto stran drugi napravi (telefonu, racunalniku ali drugemu zaslonu) prek Huba.
     * Vrne false, ce povezave ni; odgovor Huba (accepted/rejected) pride kot cast.ack.
     */
    fun posljiUrl(cilj: String, url: String, naslov: String?, id: String = UUID.randomUUID().toString()): Boolean {
        val ws = webSocket ?: return false
        if (!povezan) return false
        val sporocilo = JSONObject().apply {
            put("id", id)
            put("type", "cast.url")
            put("target", cilj)
            put("payload", JSONObject().apply {
                put("url", url)
                put("title", naslov ?: "")
                put("start_position", 0.0)
            })
        }
        return try { ws.send(sporocilo.toString()) } catch (_: Throwable) { false }
    }

    private fun handleIncomingMessage(ws: WebSocket, text: String) {
        try {
            val json = JSONObject(text)
            val msgId = json.optString("id", UUID.randomUUID().toString())
            val type = json.optString("type")

            when (type) {
                "cast.ack" -> {
                    if (json.optString("ref_id", "").isNotBlank()) {
                        try {
                            naPotrditev?.invoke(
                                json.optString("ref_id", ""), json.optString("status", ""),
                                json.optString("error_code", ""), json.optString("error", "")
                            )
                        } catch (_: Throwable) { }
                    }
                }

                "cast.devices" -> {
                    val naprave = json.optJSONArray("devices")?.toString() ?: "[]"
                    zadnjeNaprave = naprave
                    try { naSpremembeNaprav?.invoke(naprave) } catch (_: Throwable) { }
                }

                "cast.url" -> {
                    val payload = json.getJSONObject("payload")
                    val url = payload.getString("url")
                    val title = payload.optString("title", "")
                    val startPos = payload.optDouble("start_position", 0.0)

                    Log.i(TAG, "Prejet cast.url: $url ($title)")
                    mainHandler.post {
                        val krmilnik = mediaController
                        if (krmilnik != null && krmilnikVOspredju) {
                            krmilnik.onCastUrlReceived(url, title, startPos)
                        } else {
                            odpriVBrskalniku(url, title, startPos)
                        }
                    }
                    sendAck(ws, msgId, "accepted")
                }

                "cast.control" -> {
                    val payload = json.getJSONObject("payload")
                    val action = payload.getString("action")
                    val position = if (payload.has("position")) payload.getDouble("position") else null
                    val volume = if (payload.has("volume")) payload.getDouble("volume") else null

                    Log.i(TAG, "Prejet cast.control: $action (pos: $position, vol: $volume)")
                    mainHandler.post {
                        mediaController?.onCastControl(action, position, volume)
                    }
                    sendAck(ws, msgId, "accepted")
                }

                "control.result", "control.ack" -> {
                    // Odgovor naprave na nas ukaz (ali zavrnitev sredisca): naprej strani daljinca.
                    if (type == "control.ack" && json.optString("status", "") == "accepted") return
                    try { naUkazOdziv?.invoke(json) } catch (_: Throwable) { }
                }

                "control.command" -> {
                    // Ukaz Safeer Controla (ali druge seznanjene naprave) prek sredisca.
                    // Izvede se na glavni niti; odgovor gre nazaj posiljatelju kot control.result.
                    val payload = json.optJSONObject("payload") ?: JSONObject()
                    val posiljatelj = json.optString("sender", "")
                    val dejanje = payload.optString("action", "")
                    val parametri = JSONObject((payload.optJSONObject("params") ?: payload).toString())
                    // Komu gre odgovor ali pretakanje, pove hub (sender), nikoli parametri ukaza.
                    parametri.remove(si.safeer.tv.link.Daljinec.PARAM_POSILJATELJ)
                    if (posiljatelj.isNotBlank()) parametri.put(si.safeer.tv.link.Daljinec.PARAM_POSILJATELJ, posiljatelj)
                    Log.i(TAG, "Prejet control.command od $posiljatelj: $dejanje")
                    mainHandler.post {
                        val krmilnik = mediaController
                        val ospredje = if (krmilnik != null && krmilnikVOspredju) krmilnik as? si.safeer.tv.link.Daljinec.VOspredju else null
                        val izid = si.safeer.tv.link.Daljinec.izvedi(this@CastReceiverService, dejanje, parametri, ospredje, DOMACA_STRAN) { url, naslov ->
                            if (krmilnik != null && krmilnikVOspredju) krmilnik.onCastUrlReceived(url, naslov, 0.0)
                            else odpriVBrskalniku(url, naslov, 0.0)
                        }
                        if (posiljatelj.isNotBlank()) {
                            try {
                                ws.send(si.safeer.tv.link.Daljinec.sporociloIzida(posiljatelj, msgId, dejanje, izid).toString())
                            } catch (e: Throwable) {
                                Log.w(TAG, "Odgovora na ukaz ni bilo mogoce poslati: ${e.message}")
                            }
                        }
                    }
                }

                "cast.ping" -> {
                    val pong = JSONObject().apply {
                        put("id", msgId)
                        put("type", "cast.pong")
                    }
                    ws.send(pong.toString())
                }

                "share.text" -> {
                    val payload = json.optJSONObject("payload") ?: JSONObject()
                    val besedilo = payload.optString("text", "")
                    val od = imePosiljatelja(json)
                    Log.i(TAG, "Prejeto besedilo od $od (${besedilo.length} znakov)")
                    mainHandler.post {
                        val krmilnik = mediaController
                        if (krmilnik != null && krmilnikVOspredju) krmilnik.onShareText(od, besedilo)
                        else pokaziSporocilo("💬 $od", besedilo)
                    }
                    sendAck(ws, msgId, "accepted")
                }

                "share.screen" -> {
                    val payload = json.optJSONObject("payload") ?: JSONObject()
                    val dejanje = payload.optString("action", "")
                    val od = imePosiljatelja(json)
                    val idDeljenja = payload.optString("id", "")
                    if (dejanje == "start") {
                        val pot = payload.optString("path", "")
                        val url = if (pot.startsWith("/")) hubHttpOsnova() + pot else payload.optString("url", "")
                        if (url.isNotBlank()) {
                            Log.i(TAG, "Deljenje zaslona od $od: $url")
                            mainHandler.post {
                                val krmilnik = mediaController
                                if (krmilnik != null && krmilnikVOspredju) krmilnik.onShareScreenStarted(url, od)
                                else odpriVBrskalniku(url, "Zaslon: $od", 0.0)
                            }
                        }
                    } else if (dejanje == "stop") {
                        Log.i(TAG, "Deljenje zaslona $idDeljenja je koncano")
                        mainHandler.post { mediaController?.onShareScreenStopped(idDeljenja) }
                    }
                    sendAck(ws, msgId, "accepted")
                }

                "share.file" -> {
                    val payload = json.optJSONObject("payload") ?: JSONObject()
                    val ime = payload.optString("name", "datoteka")
                    val pot = payload.optString("path", "")
                    val odtis = payload.optString("sha256", "")
                    val zaGostitelja = payload.optBoolean("for_host", false)
                    val od = imePosiljatelja(json)
                    sendAck(ws, msgId, "accepted")
                    if (zaGostitelja || pot.isBlank()) {
                        // Hub tece na tej napravi: datoteka je ze v mapi prenosov.
                        val mapa = HubKrmilnik.mapaZaPrejete(applicationContext)
                        mainHandler.post { javiDatoteko(ime, java.io.File(mapa, ime), od) }
                    } else {
                        prevzemiDatoteko(hubHttpOsnova() + pot, ime, od, odtis)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Napaka pri obdelavi vhodnega sporočila: ${e.message}", e)
        }
    }

    private fun imePosiljatelja(json: JSONObject): String {
        val ime = json.optString("sender_name", "")
        val privzeto = if (ime.isNotBlank()) ime else json.optString("sender", "naprava")
        return si.safeer.tv.link.LinkVzdevki.ime(this, json.optString("sender", ""), privzeto)
    }

    /** Naslov Huba za navadne zahteve HTTP (ws://x:y/cast/ws -> http://x:y). */
    private fun hubHttpOsnova(): String =
        hubUrl.replace(Regex("^wss"), "https").replace(Regex("^ws"), "http")
            .substringBefore("/cast/ws").substringBefore("/link/ws").substringBefore("/safeer/ws")
            .trimEnd('/')

    /** Kratko sporocilo uporabniku, kadar brskalnik ni v ospredju. */
    private fun pokaziSporocilo(naslov: String, besedilo: String) {
        try {
            android.widget.Toast.makeText(this, "$naslov: ${besedilo.take(200)}", android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w(TAG, "Sporocila ni bilo mogoce pokazati: ${e.message}")
        }
    }

    private fun javiDatoteko(ime: String, pot: java.io.File, od: String) {
        val krmilnik = mediaController
        if (krmilnik != null && krmilnikVOspredju) krmilnik.onShareFileReceived(ime, pot, od)
        else pokaziSporocilo("📁 $od", "Prejeta datoteka: $ime")
    }

    /**
     * Datoteko, ki caka na Hubu, prenesemo v mapo prenosov. V koscih, na lastni niti; ime
     * ostane tako, kot ga je dal posiljatelj, ob trku dobi stevilko kot na namizju.
     */
    private fun prevzemiDatoteko(url: String, ime: String, od: String, pricakovanOdtis: String) {
        Thread {
            var zaBrisanje: java.io.File? = null
            try {
                val mapa = HubKrmilnik.mapaZaPrejete(applicationContext)
                val cilj = HubTokovi.enolicnaPot(mapa, HubTokovi.varnoIme(ime))
                zaBrisanje = cilj
                val zahteva = Request.Builder().url(url).get().build()
                client.newCall(zahteva).execute().use { odgovor ->
                    if (!odgovor.isSuccessful) throw java.io.IOException("Hub je odgovoril ${odgovor.code}")
                    val telo = odgovor.body ?: throw java.io.IOException("prazen odgovor")
                    val prstni = java.security.MessageDigest.getInstance("SHA-256")
                    telo.byteStream().use { vhod ->
                        java.io.FileOutputStream(cilj).use { izhod ->
                            val kos = ByteArray(64 * 1024)
                            while (true) {
                                val n = vhod.read(kos)
                                if (n < 0) break
                                izhod.write(kos, 0, n)
                                prstni.update(kos, 0, n)
                            }
                        }
                    }
                    // Datoteka mora biti natanko taka, kot jo je Hub sprejel; sicer je ne obdrzimo.
                    val dobljen = prstni.digest().joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
                    val pricakovan = pricakovanOdtis.ifBlank { odgovor.header("x-safeer-sha256") ?: "" }
                    if (pricakovan.isNotBlank() && pricakovan != dobljen) throw java.io.IOException("prstni odtis se ne ujema")
                }
                Log.i(TAG, "Datoteka $ime je prenesena v ${cilj.parent}")
                mainHandler.post { javiDatoteko(cilj.name, cilj, od) }
            } catch (e: Exception) {
                Log.w(TAG, "Datoteke $ime ni bilo mogoce prevzeti: ${e.message}")
                try { zaBrisanje?.delete() } catch (_: Exception) { }
                mainHandler.post { pokaziSporocilo("📁 $od", "Datoteke $ime ni bilo mogoče prevzeti.") }
            }
        }.start()
    }

    /** Poslje poljubno sporocilo sredi scu (npr. control.command s strani daljinca). */
    fun posljiSporocilo(sporocilo: JSONObject): Boolean {
        val ws = webSocket ?: return false
        if (!povezan) return false
        return try { ws.send(sporocilo.toString()) } catch (_: Throwable) { false }
    }

    private fun sendAck(ws: WebSocket, refId: String, status: String, error: String? = null) {
        val ack = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("type", "cast.ack")
            put("ref_id", refId)
            put("status", status)
            if (error != null) put("error", error)
        }
        ws.send(ack.toString())
    }

    fun broadcastStatus(state: String, currentUrl: String?, title: String?, position: Double, duration: Double) {
        val statusMsg = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("type", "cast.status")
            put("device_id", deviceId)
            put("payload", JSONObject().apply {
                put("state", state)
                put("current_url", currentUrl)
                put("title", title)
                put("position", position)
                put("duration", duration)
            })
        }
        webSocket?.send(statusMsg.toString())
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        webSocket?.close(1000, "Service stopped")
        webSocket = null
        mainHandler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        try { si.safeer.tv.link.ZvokSprejemnik.ustavi() } catch (_: Throwable) { }
        mediaController = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        run {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Safeer Cast Receiver",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Sprejemanje lokalnih cast ukazov za TV predvajalnik"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
        return builder
            .setContentTitle("Safeer Cast Receiver")
            .setContentText(getString(si.safeer.tv.R.string.ui_cast_ready))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
