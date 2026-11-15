package si.safeer.tv

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class DashSeja(
    val mpdUrl: String,
    val licenseUrl: String,
    val licenseHeaders: Map<String, String>,
    val mediaHeaders: Map<String, String>,
    val dashChannel: String,
    val jsonWrapKey: String?,
    val encrypted: Boolean,
    val hasPssh: Boolean
)

/**
 * Opazuje stran, na kateri je uporabnik, in ujame njen pretok DASH z licenco Widevine, da ga
 * lahko predvaja ExoPlayer namesto WebViewa (na televizorju je to razlika med zatikanjem in
 * gladko sliko).
 *
 * Nic od tega ni vezano na doloceno stran: manifest prepoznamo po standardu DASH, licencni
 * naslov po standardnih oznakah (Widevine, DRMtoday, ExpressPlay), piskotke in glavi Referer
 * in Origin pa vzamemo z izvora strani, ki je odprta. Zascita vsebine ostane nedotaknjena -
 * licenco izda ponudnikov streznik, desifrira Widevine; mi prenasamo samo uporabnikovo lastno
 * sejo naprej.
 */
object DashPrevzem {
    /** P4: skupni rok prenosa manifesta (vsi skoki in branje). */
    private const val ROK_PRENOSA_NS = 20_000_000_000L

    var listener: ((DashSeja) -> Unit)? = null
    var onNeedPageLicense: (() -> Unit)? = null

    /** Izvor strani, ki je odprta (shema in gostitelj) - od tam gredo piskotki in glavi. */
    @Volatile
    private var izvor: String = ""

    /** Brskalnik javi vsako novo stran; od tod vemo, cigava seja je v igri. */
    fun naStrani(url: String) {
        val nov = izvorOf(url)
        // P5.1: razclenitev, ki je se v teku za prejsnjo stran, po odhodu ne sme vec nastaviti licence.
        if (nov != izvor) synchronized(lock) { generacija++ }
        izvor = nov
    }

    fun izvorStrani(): String = izvor

    /** Ali smo na tej strani ze ujeli pretok DASH? Po tem se odloci nacin predvajanja. */
    fun imaSejo(): Boolean = synchronized(lock) {
        // P3: sele potrjen MPD; prej ze vsak zahtevek z ".mpd" (tudi slika strani) skril vrstico do reset().
        DashVarnostPravila.imaSejo(mpdUrl, mpdPotrjen, mpdUrl.isNotEmpty() && prepuscenBrezZaklepa(mpdUrl))
    }

    /** Tokovi, ki jih predvaja stran sama (glej [PredajaStrani]); velja do odhoda na drug izvor. */
    private val samoStran = PredajaStrani { a, b -> sameDashStream(a, b) }

    fun prepustiStrani(mpd: String) {
        synchronized(lock) { samoStran.prepusti(mpd) }
    }

    fun jePrepuscen(mpd: String): Boolean = synchronized(lock) { prepuscenBrezZaklepa(mpd) }

    private fun prepuscenBrezZaklepa(mpd: String): Boolean = samoStran.jePrepuscen(mpd)

    /** Ali ta naslov kaze na manifest, ki ga predvajamo? */
    fun jeManifest(url: String): Boolean {
        if (url.isEmpty()) return false
        val trenutni = synchronized(lock) { mpdUrl }
        if (trenutni.isNotEmpty() && sameDashStream(trenutni, url)) return true
        return isDashManifest(url.lowercase(Locale.US))
    }

    // Isti razclenjevalnik kot pravila za piskotke, da se izvor in cilj primerjata enako.
    private fun izvorOf(url: String): String = DashVarnostPravila.izvor(url)

    private val main = Handler(Looper.getMainLooper())
    // P4: ena nit kot prej, a omejena vrsta - stari zahtevki (stara stran) odpadejo, namesto da cakajo v nedogled.
    private val io = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4), ThreadPoolExecutor.DiscardOldestPolicy())
    private val lock = Any()
    private var mpdUrl: String = ""
    /** P3: razclenitev je potrdila, da je [mpdUrl] res manifest MPD. */
    private var mpdPotrjen: Boolean = false
    /** P5.1: poveca se ob novem toku, reset() in novi strani; rezultat razclenitve druge generacije se zavrze. */
    private var generacija: Long = 0L
    /** Gostitelj manifesta, ki ga je WebView zahteval po omrezju; "" = noben. */
    private var omrezniGostitelj: String = ""
    private var mpdHeaders: Map<String, String> = emptyMap()
    private var mpdLaurl: String = ""
    private var pageLicUrl: String = ""
    private var pageLicHeaders: Map<String, String> = emptyMap()
    private var hintLicUrl: String = ""
    private var hintLicHeaders: Map<String, String> = emptyMap()
    private var jsonWrapKey: String? = null
    private var lastFiredKey: String = ""
    private var lastFiredChannel: String = ""
    private var prevFiredChannel: String = ""
    private var lastFireAt: Long = 0L
    private var mpdParseTries: Int = 0
    private var allowClear: Boolean = false
    private var needsDrm: Boolean = false
    private var hasPssh: Boolean = false
    // P11: pise glavna nit, bere tudi nit WebViewa (isStaleChannelMpd).
    @Volatile
    private var okElapsed: Long = 0L
    @Volatile
    private var parsedElapsed: Long = 0L

    fun markOk() {
        okElapsed = SystemClock.elapsedRealtime()
    }

    fun reset() {
        synchronized(lock) {
            generacija++
            mpdUrl = ""
            mpdPotrjen = false
            omrezniGostitelj = ""
            mpdHeaders = emptyMap()
            mpdLaurl = ""
            hintLicUrl = ""
            hintLicHeaders = emptyMap()
            jsonWrapKey = null
            lastFiredKey = ""
            lastFiredChannel = ""
            prevFiredChannel = ""
            lastFireAt = 0L
            mpdParseTries = 0
            allowClear = false
            needsDrm = false
            hasPssh = false
        }
    }

    fun resetAll() {
        synchronized(lock) {
            samoStran.pocisti()
            pageLicUrl = ""
            pageLicHeaders = emptyMap()
        }
        reset()
    }

    fun markStopped() {
        synchronized(lock) { lastFiredKey = "" }
    }

    /**
     * Ali zahteva pripada predvajanju (manifest, segment, licenca)? Take pustimo skozi brez
     * filtra oglasov, sicer bi filter lahko ustavil sliko. Odlocamo po standardnih oznakah
     * DASH in Widevine ter po gostitelju, s katerega je prisel manifest - ne po imenu strani.
     */
    fun shouldPassthrough(url: String): Boolean {
        val gostitelj = DashPravila.gostitelj(url)
        val manifest = synchronized(lock) { omrezniGostitelj }
        return DashPravila.prepusti(url, manifest, AdBlockEngine.jeOglasniGostitelj(gostitelj))
    }

    fun observe(url: String, method: String, headers: Map<String, String>) {
        if (url.isEmpty()) return
        val lower = url.lowercase(Locale.US)
        // P1, nova najdba 1: piskotkov tu ne dodajamo vec - vsak cilj jih dobi sele ob uporabi in samo svoje.
        val merged = DashVarnostPravila.brezPiskotkov(headers)
        when {
            isDashManifest(lower) -> onMpd(url, merged, izOmrezja = true)
            isLicenseUrl(lower, method) -> onLicenseHint(url, merged, null)
        }
    }

    fun observeJs(url: String, method: String, headersJson: String, kind: String) {
        if (url.isEmpty() && kind != "cfg") return
        val parsed = parseHeaderJson(headersJson)
        mergeAuthHeaders(parsed)
        if (kind == "cfg") {
            onPageConfig(url, parsed)
            return
        }
        val wrap = jsonWrapKeyFromKind(kind)
        val lower = url.lowercase(Locale.US)
        val merged = DashVarnostPravila.brezPiskotkov(parsed)
        when {
            isDashManifest(lower) -> onMpd(url, merged)
            isLicenseUrl(lower, method) -> onLicenseHint(url, merged, wrap)
        }
    }

    /** [izOmrezja]: manifest je WebView res zahteval (shouldInterceptRequest), ne javila ga je stran po mostu. */
    private fun onMpd(url: String, headers: Map<String, String>, izOmrezja: Boolean = false) {
        val ch = dashChannelOf(url)
        if (isStaleChannelMpd(ch)) {
            SafeerDbg.log(
                "H330",
                "DashPrevzem.kt:mpd",
                "stale mpd ignored",
                JSONObject().put("ch", ch).put("cur", lastFiredChannel).put("prev", prevFiredChannel)
            )
            return
        }
        val parsed: Boolean
        var newStream = false
        val gen: Long
        synchronized(lock) {
            val same = mpdUrl.isNotEmpty() && sameDashStream(mpdUrl, url)
            // Gostitelj manifesta gre mimo filtra samo, ce je manifest prisel z njega po omrezju (DashPravila.prepusti).
            if (izOmrezja) omrezniGostitelj = DashPravila.gostitelj(url)
            else if (!same) omrezniGostitelj = ""
            if (!same) {
                generacija++
                mpdUrl = url
                mpdPotrjen = false
                mpdHeaders = headers
                mpdLaurl = ""
                lastFiredKey = ""
                mpdParseTries = 0
                allowClear = false
                needsDrm = false
                hasPssh = false
                parsed = true
                newStream = true
            } else {
                mpdUrl = preferMpdUrl(mpdUrl, url)
                mpdHeaders = mergeHeaders(mpdHeaders, headers)
                parsed = mpdParseTries < 2
            }
            gen = generacija
        }
        if (parsed) {
            SafeerDbg.log(
                "H330",
                "DashPrevzem.kt:mpd",
                "dash mpd",
                JSONObject()
                    .put("ch", dashChannelOf(url))
                    .put("host", hostOf(url))
            )
            parseMpdForLicense(url, headers, gen)
            synchronized(lock) { mpdParseTries++ }
        }
        if (newStream) maybeFire()
    }

    private fun onPageConfig(url: String, headers: Map<String, String>) {
        synchronized(lock) {
            if (url.startsWith("http", true) && looksLikeLicenseUrl(url.lowercase(Locale.US))) {
                pageLicUrl = url
            }
            pageLicHeaders = mergeHeaders(pageLicHeaders, headers)
        }
        if (url.startsWith("http", true) && looksLikeLicenseUrl(url.lowercase(Locale.US))) {
            SafeerDbg.log(
                "H331",
                "DashPrevzem.kt:cfg",
                "page drm cfg",
                JSONObject()
                    .put("host", hostOf(url))
                    .put("hdr", headers.keys.joinToString(",").take(160))
            )
        }
    }

    private fun onLicenseHint(url: String, headers: Map<String, String>, wrap: String?) {
        synchronized(lock) {
            // Glave namiga drugega gostitelja ne mesamo (bile so namenjene njemu).
            hintLicHeaders = if (DashVarnostPravila.istiGostitelj(hintLicUrl, url)) mergeHeaders(hintLicHeaders, headers) else headers
            hintLicUrl = url
            if (!wrap.isNullOrEmpty()) jsonWrapKey = wrap
        }
        SafeerDbg.log(
            "H331",
            "DashPrevzem.kt:lic",
            "license hint",
            JSONObject()
                .put("host", hostOf(url))
                .put("hdr", headers.keys.joinToString(",").take(160))
                .put("wrap", wrap ?: "")
        )
    }

    private fun maybeFire() {
        val ready = synchronized(lock) {
            if (mpdUrl.isEmpty()) false
            else allowClear || needsDrm
        }
        if (!ready) return
        if (Looper.myLooper() == Looper.getMainLooper()) fireNow()
        else main.post { fireNow() }
    }

    private fun resolvedLicenseUrl(): String {
        // Licenco s piskotki samo prek https (ali http na strani http istega gostitelja).
        val iz = izvor
        if (mpdLaurl.isNotEmpty() && DashVarnostPravila.smeOmrezje(mpdLaurl, iz)) return mpdLaurl
        if (pageLicUrl.isNotEmpty() && DashVarnostPravila.smeOmrezje(pageLicUrl, iz)) return pageLicUrl
        if (hintLicUrl.isNotEmpty() && DashVarnostPravila.smeOmrezje(hintLicUrl, iz)) return hintLicUrl
        return ""
    }

    private fun resolvedLicenseHeaders(licUrl: String): Map<String, String> {
        var h: Map<String, String> = emptyMap()
        h = mergeHeaders(h, pageLicHeaders)
        if (DashVarnostPravila.istiGostitelj(hintLicUrl, licUrl)) h = mergeHeaders(h, hintLicHeaders)
        // Nova najdba 1: licencni naslov dobi samo SVOJE piskotke (prej tudi piskotke izvora strani, ki ga je podala);
        // brez naslova licence ni piskotkov (prej piskotki izvora).
        return if (licUrl.isNotEmpty()) withCookies(licUrl, h) else DashVarnostPravila.brezPiskotkov(h)
    }

    private fun fireNow() {
        val session = synchronized(lock) {
            if (mpdUrl.isEmpty()) return
            if (prepuscenBrezZaklepa(mpdUrl)) return
            if (!allowClear && !needsDrm) return
            val licUrl = if (needsDrm) resolvedLicenseUrl() else ""
            val lic = if (needsDrm) resolvedLicenseHeaders(licUrl) else emptyMap()
            val media = headersForMedia(withCookies(mpdUrl, mpdHeaders))
            val key = mpdUrl + "|" + licUrl + "|" + needsDrm + "|" + (jsonWrapKey ?: "")
            if (key == lastFiredKey) return
            lastFiredKey = key
            prevFiredChannel = lastFiredChannel
            lastFiredChannel = dashChannelOf(mpdUrl)
            lastFireAt = SystemClock.elapsedRealtime()
            DashSeja(
                mpdUrl = mpdUrl,
                licenseUrl = licUrl,
                licenseHeaders = lic,
                mediaHeaders = media,
                dashChannel = dashChannelOf(mpdUrl),
                jsonWrapKey = if (needsDrm) jsonWrapKey else null,
                encrypted = needsDrm,
                hasPssh = hasPssh
            )
        }
        val now = SystemClock.elapsedRealtime()
        val okMs = if (okElapsed > 0L) now - okElapsed else -1L
        val parseMs = if (parsedElapsed > 0L) now - parsedElapsed else -1L
        SafeerDbg.log(
            "H332",
            "DashPrevzem.kt:fire",
            "start exo",
            JSONObject()
                .put("ch", session.dashChannel)
                .put("hasLic", session.licenseUrl.isNotEmpty())
                .put("enc", session.encrypted)
                .put("pssh", session.hasPssh)
                .put("hdrN", session.licenseHeaders.size)
                .put("licHost", hostOf(session.licenseUrl))
                .put("okMs", okMs)
                .put("parseMs", parseMs)
                .put("wrap", session.jsonWrapKey ?: "")
        )
        android.util.Log.i(
            "SafeerExo",
            // P11: pot licence (lahko nosi zeton) in imena glav ne sodijo v sistemski dnevnik; dovolj sta gostitelj in stevilo.
            "start exo ch=${session.dashChannel} enc=${session.encrypted} hasPssh=${session.hasPssh} licHost=${hostOf(session.licenseUrl)} hdrN=${session.licenseHeaders.size} okMs=$okMs parseMs=$parseMs"
        )
        listener?.invoke(session)
    }

    /**
     * Kljuc pretoka: po cem locimo en pretok od drugega (recimo dva programa). Vzamemo mapo
     * manifesta, in ce datoteka nima splosnega imena, se njeno ime - tako delujeta oba
     * obicajna nacina, mapa na program in ena mapa z vec manifesti.
     */
    fun dashChannelOf(url: String): String {
        val pot = try { Uri.parse(url).path.orEmpty() } catch (_: Exception) { "" }
        if (pot.isEmpty()) return ""
        val deli = pot.split('/').filter { it.isNotEmpty() }
        if (deli.isEmpty()) return ""
        val datoteka = deli.last().lowercase(Locale.US)
        val mapa = deli.dropLast(1).joinToString("/").lowercase(Locale.US)
        val splosnoIme = datoteka.startsWith("manifest") || datoteka.startsWith("index") ||
            datoteka.startsWith("stream") || datoteka.startsWith("playlist")
        return if (splosnoIme || !datoteka.contains('.')) mapa else "$mapa/$datoteka"
    }

    private fun isStaleChannelMpd(ch: String): Boolean {
        if (ch.isEmpty()) return false
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (lastFireAt <= 0L) return false
            if (!ch.equals(prevFiredChannel, true)) return false
            if (ch.equals(lastFiredChannel, true)) return false
            return if (okElapsed > lastFireAt) {
                now - okElapsed < 4_000L
            } else {
                now - lastFireAt < 12_000L
            }
        }
    }

    private fun isDashManifest(lower: String): Boolean {
        if (lower.contains(".m4s") || (lower.contains(".mp4") && !lower.contains(".mpd"))) return false
        if (lower.contains(".mpd") || lower.contains("manifest.mpd")) return true
        return lower.contains("manifest") && (lower.contains("dash") || lower.contains("mpd"))
    }

    private fun sameDashStream(a: String, b: String): Boolean {
        val ca = dashChannelOf(a)
        val cb = dashChannelOf(b)
        if (ca.isNotEmpty() && ca.equals(cb, ignoreCase = true)) return true
        return normalizeMpd(a) == normalizeMpd(b)
    }

    private fun normalizeMpd(url: String): String {
        return url.replace(":443/", "/").substringBefore('?').lowercase(Locale.US)
    }

    private fun preferMpdUrl(current: String, incoming: String): String {
        fun score(u: String): Int {
            val l = u.lowercase(Locale.US)
            var s = 0
            if (l.contains(".mpd")) s += 4
            if (l.contains("manifest")) s += 3
            if (l.contains(":443")) s -= 1
            return s
        }
        return if (score(incoming) >= score(current)) incoming else current
    }

    private fun hostOf(url: String): String {
        return try {
            Uri.parse(url).host ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseMpdForLicense(mpd: String, headers: Map<String, String>, gen: Long) {
        // Izvor in glave zajamemo zdaj: ko zahtevek pride na vrsto, je stran lahko ze druga.
        val izvorZahteve = izvor
        val glave = DashVarnostPravila.brezPiskotkov(headers)
        io.execute {
            var conn: HttpURLConnection? = null
            try {
                // P4: en skupni rok za vse skoke in branje (prej samo rok posameznega branja).
                val konecNs = System.nanoTime() + ROK_PRENOSA_NS
                var cilj = DashVarnostPravila.brezPrivzetihVrat(mpd)
                var telo: ByteArray? = null
                for (skok in 0..DashVarnostPravila.MAKS_PREUSMERITEV) {
                    // P1/P2: sami prenasamo samo https; piskotki samo za ta skok (rocno nastavljen Cookie ne sme slediti preusmeritvi na tuj gostitelj).
                    if (!DashVarnostPravila.smeOmrezje(cilj, izvorZahteve)) return@execute
                    val c = URL(cilj).openConnection() as HttpURLConnection
                    conn = c
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    c.requestMethod = "GET"
                    withCookies(cilj, glave, izvorZahteve).forEach { (k, v) ->
                        if (!k.equals("Content-Type", true)) c.setRequestProperty(k, v)
                    }
                    val koda = c.responseCode
                    if (koda in 300..399) {
                        cilj = DashVarnostPravila.preusmeritev(cilj, c.getHeaderField("Location")) ?: return@execute
                        c.disconnect()
                        conn = null
                        continue
                    }
                    telo = c.inputStream.use { DashVarnostPravila.beriZRokom(it, 256 * 1024, konecNs) }
                    break
                }
                val body = String(telo ?: return@execute, Charsets.UTF_8)
                val isMpd = body.contains("<MPD", ignoreCase = true)
                val pssh = body.contains("pssh", ignoreCase = true)
                val prot = body.contains("ContentProtection", ignoreCase = true)
                val encrypted = isMpd && mpdLooksEncrypted(body)
                val laurl = extractLaurl(body)
                SafeerDbg.log(
                    "H338",
                    "DashPrevzem.kt:mpdxml",
                    "mpd parsed",
                    JSONObject()
                        .put("hasLa", !laurl.isNullOrEmpty())
                        .put("licHost", if (laurl.isNullOrEmpty()) "" else hostOf(laurl))
                        .put("n", body.length)
                        .put("mpd", isMpd)
                        .put("pssh", pssh)
                        .put("prot", prot)
                        .put("enc", encrypted)
                )
                main.post {
                    parsedElapsed = SystemClock.elapsedRealtime()
                    val naprej = synchronized(lock) {
                        // P5.1: rezultat za prejsnji tok ali stran (pocasen napadalcev streznik) ne sme nastaviti licence.
                        if (!DashVarnostPravila.rezultatVelja(gen, generacija)) {
                            false
                        } else if (!isMpd) {
                            // P3: ni manifest - ne drzi seje (skrite vrstice, ExoPlayer) do naslednjega reset().
                            if (!mpdPotrjen) {
                                mpdUrl = ""
                                omrezniGostitelj = ""
                            }
                            false
                        } else {
                            mpdPotrjen = true
                            true
                        }
                    }
                    if (!naprej) return@post
                    if (!encrypted) {
                        synchronized(lock) {
                            allowClear = true
                            needsDrm = false
                            hasPssh = false
                            mpdLaurl = ""
                        }
                        SafeerDbg.log(
                            "H338",
                            "DashPrevzem.kt:mpdxml",
                            "clear dash",
                            JSONObject().put("ch", dashChannelOf(mpd))
                        )
                        maybeFire()
                        return@post
                    }
                    synchronized(lock) {
                        allowClear = false
                        needsDrm = true
                        hasPssh = pssh || prot
                        if (!laurl.isNullOrEmpty()) mpdLaurl = laurl
                    }
                    maybeFire()
                }
            } catch (t: Throwable) {
                SafeerDbg.log(
                    "H338",
                    "DashPrevzem.kt:mpdxml",
                    "mpd fetch fail",
                    JSONObject().put("err", t.javaClass.simpleName)
                )
            } finally {
                // P4: povezava se zapre tudi ob napaki (4xx, rok, izjema pri branju).
                conn?.disconnect()
            }
        }
    }

    private fun mpdLooksEncrypted(body: String): Boolean {
        val l = body.lowercase(Locale.US)
        return l.contains("contentprotection") ||
            l.contains("pssh") ||
            l.contains("edef8ba9") ||
            l.contains("cenc:pssh") ||
            l.contains("cenc:default_kid") ||
            l.contains("urn:mpeg:cenc") ||
            l.contains("mp4protection")
    }

    private fun looksLikeLicenseUrl(lower: String): Boolean {
        return lower.contains("drmtoday") || lower.contains("license-proxy") ||
            lower.contains("widevine") || lower.contains("/license") ||
            lower.contains("expressplay") || lower.contains("/drm/")
    }

    private fun mergeAuthHeaders(headers: Map<String, String>) {
        if (headers.isEmpty()) return
        val extra = LinkedHashMap<String, String>()
        headers.forEach { (k, v) ->
            if (v.isBlank()) return@forEach
            val n = k.lowercase(Locale.US)
            if (n.contains("dt-") || n.contains("custom-data") || n == "authorization") {
                extra[k] = v
            }
        }
        if (extra.isEmpty()) return
        synchronized(lock) {
            pageLicHeaders = mergeHeaders(pageLicHeaders, extra)
        }
    }

    // P4: linearno iskanje namesto stirih regexov s prekrivanjem; licenca s piskotki samo prek https.
    private fun extractLaurl(mpdXml: String): String? = DashVarnostPravila.najdiLaurl(mpdXml, samoHttps = true)

    private fun isLicenseUrl(lower: String, method: String): Boolean {
        if (isDashManifest(lower) || lower.contains(".m4s") || lower.contains(".mp4") ||
            lower.contains(".ts") || lower.contains(".m3u8") || lower.contains(".js")
        ) {
            return false
        }
        val post = method.equals("POST", ignoreCase = true)
        val drmHost = lower.contains("drmtoday") || lower.contains("license-proxy") ||
            lower.contains("expressplay") || lower.contains("widevine")
        if (drmHost) {
            return post || lower.contains("license") || lower.contains("/drm/") ||
                lower.contains("/widevine") || lower.contains("/authenticate")
        }
        return post && (
            lower.contains("/license") || lower.contains("license") ||
                lower.contains("/drm/") || lower.contains("cenc")
            )
    }

    private fun headersForMedia(headers: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        headers.forEach { (k, v) ->
            val n = k.lowercase(Locale.US)
            if (n != "content-type" && n != "content-length") out[k] = v
        }
        return out
    }

    private fun mergeHeaders(a: Map<String, String>, b: Map<String, String>): Map<String, String> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = LinkedHashMap(a)
        b.forEach { (k, v) ->
            if (v.isBlank()) return@forEach
            val existingKey = out.keys.firstOrNull { it.equals(k, true) }
            if (k.equals("Cookie", true) && existingKey != null) {
                out[existingKey] = mergeCookie(out[existingKey] ?: "", v)
            } else if (existingKey != null) {
                out[existingKey] = v
            } else {
                out[k] = v
            }
        }
        return out
    }

    private fun mergeCookie(a: String, b: String): String = DashVarnostPravila.zdruziPiskotke(a, b)

    private fun jsonWrapKeyFromKind(kind: String): String? {
        if (!kind.startsWith("json:")) return null
        val keys = kind.removePrefix("json:").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        for (candidate in listOf("payload", "challenge", "spc", "licenseRequest", "request")) {
            if (keys.any { it.equals(candidate, true) }) return candidate
        }
        return keys.firstOrNull()
    }

    private fun parseHeaderJson(raw: String): Map<String, String> {
        // P11: stran po mostu ne more poslati poljubno velikega JSON ali poljubno mnogo glav.
        if (!DashVarnostPravila.jsonGlavDovoljen(raw)) return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val out = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext() && out.size < DashVarnostPravila.MAKS_GLAV) {
                val k = keys.next()
                out[k] = obj.optString(k, "")
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun withCookies(url: String, headers: Map<String, String>): Map<String, String> = withCookies(url, headers, izvor)

    /**
     * P1/SL-S2: piskotki samo za [url] sam (CookieManager.getCookie(url), kot bi jih poslal brskalnik), nikoli piskotki
     * izvora strani na tuj naslov. [izvorStrani] se prebere enkrat (P11), Referer/Origin nosita samo izvor.
     */
    private fun withCookies(url: String, headers: Map<String, String>, izvorStrani: String): Map<String, String> {
        val cm = try { CookieManager.getInstance() } catch (_: Exception) { null }
        return DashVarnostPravila.sestaviGlave(url, headers, izvorStrani, ChromiumEngineView.DESKTOP_USER_AGENT) { u ->
            try { cm?.getCookie(u) } catch (_: Exception) { null }
        }
    }
}
