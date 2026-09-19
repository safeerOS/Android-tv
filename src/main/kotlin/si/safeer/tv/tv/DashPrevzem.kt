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
import java.util.concurrent.Executors

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
    var listener: ((DashSeja) -> Unit)? = null
    var onNeedPageLicense: (() -> Unit)? = null

    /** Izvor strani, ki je odprta (shema in gostitelj) - od tam gredo piskotki in glavi. */
    @Volatile
    private var izvor: String = ""

    /** Brskalnik javi vsako novo stran; od tod vemo, cigava seja je v igri. */
    fun naStrani(url: String) {
        izvor = izvorOf(url)
    }

    fun izvorStrani(): String = izvor

    /** Ali smo na tej strani ze ujeli pretok DASH? Po tem se odloci nacin predvajanja. */
    fun imaSejo(): Boolean = synchronized(lock) { mpdUrl.isNotEmpty() && !prepuscenBrezZaklepa(mpdUrl) }

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

    private fun izvorOf(url: String): String = try {
        val u = Uri.parse(url)
        val shema = u.scheme.orEmpty().lowercase(Locale.US)
        val gostitelj = u.host.orEmpty()
        if (shema.startsWith("http") && gostitelj.isNotEmpty()) "$shema://$gostitelj" else ""
    } catch (_: Exception) {
        ""
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var mpdUrl: String = ""
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
    private var okElapsed: Long = 0L
    private var parsedElapsed: Long = 0L

    fun markOk() {
        okElapsed = SystemClock.elapsedRealtime()
    }

    fun reset() {
        synchronized(lock) {
            mpdUrl = ""
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
        val u = url.lowercase(Locale.US)
        if (u.contains(".mpd") || u.contains(".m4s") || u.contains("/dash/") ||
            u.contains("castlabs") || u.contains("drmtoday") || u.contains("widevine") ||
            u.contains("expressplay") || u.contains("license-proxy") ||
            u.contains("/drm/") || u.contains("/license")
        ) {
            return true
        }
        val medijskiGostitelj = synchronized(lock) { if (mpdUrl.isEmpty()) "" else hostOf(mpdUrl) }
        return medijskiGostitelj.isNotEmpty() && hostOf(url).equals(medijskiGostitelj, true)
    }

    fun observe(url: String, method: String, headers: Map<String, String>) {
        if (url.isEmpty()) return
        val lower = url.lowercase(Locale.US)
        val merged = withCookies(url, headers)
        when {
            isDashManifest(lower) -> onMpd(url, merged)
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
        val merged = withCookies(url, parsed)
        when {
            isDashManifest(lower) -> onMpd(url, merged)
            isLicenseUrl(lower, method) -> onLicenseHint(url, merged, wrap)
        }
    }

    private fun onMpd(url: String, headers: Map<String, String>) {
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
        synchronized(lock) {
            val same = mpdUrl.isNotEmpty() && sameDashStream(mpdUrl, url)
            if (!same) {
                mpdUrl = url
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
            parseMpdForLicense(url, headers)
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
            hintLicUrl = url
            hintLicHeaders = mergeHeaders(hintLicHeaders, headers)
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
        if (mpdLaurl.isNotEmpty()) return mpdLaurl
        if (pageLicUrl.isNotEmpty()) return pageLicUrl
        if (hintLicUrl.isNotEmpty()) return hintLicUrl
        return ""
    }

    private fun resolvedLicenseHeaders(licUrl: String): Map<String, String> {
        var h: Map<String, String> = emptyMap()
        h = mergeHeaders(h, pageLicHeaders)
        h = mergeHeaders(h, hintLicHeaders)
        h = withCookies(if (licUrl.isNotEmpty()) licUrl else izvor, h)
        return h
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
                .put("hdrNames", session.licenseHeaders.keys.joinToString(",").take(160))
                .put("licHost", hostOf(session.licenseUrl))
                .put("licPath", pathOf(session.licenseUrl))
                .put("okMs", okMs)
                .put("parseMs", parseMs)
                .put("wrap", session.jsonWrapKey ?: "")
        )
        android.util.Log.i(
            "SafeerExo",
            "start exo ch=${session.dashChannel} enc=${session.encrypted} hasPssh=${session.hasPssh} licHost=${hostOf(session.licenseUrl)} licPath=${pathOf(session.licenseUrl)} hdrNames=${session.licenseHeaders.keys.joinToString(",")} okMs=$okMs parseMs=$parseMs"
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

    private fun parseMpdForLicense(mpd: String, headers: Map<String, String>) {
        val snapshot = withCookies(mpd, headers)
        io.execute {
            try {
                val conn = URL(mpd.replace(":443/", "/")).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                snapshot.forEach { (k, v) ->
                    if (!k.equals("Content-Type", true)) conn.setRequestProperty(k, v)
                }
                conn.inputStream.use { input ->
                    val baos = java.io.ByteArrayOutputStream()
                    val tmp = ByteArray(16 * 1024)
                    while (baos.size() < 256 * 1024) {
                        val n = input.read(tmp)
                        if (n < 0) break
                        baos.write(tmp, 0, n)
                    }
                    val body = baos.toString("UTF-8")
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
                        if (!isMpd) return@post
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
                }
            } catch (t: Throwable) {
                SafeerDbg.log(
                    "H338",
                    "DashPrevzem.kt:mpdxml",
                    "mpd fetch fail",
                    JSONObject().put("err", t.javaClass.simpleName)
                )
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

    private fun pathOf(url: String): String {
        if (url.isEmpty()) return ""
        return try {
            (Uri.parse(url).path ?: "").take(120)
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractLaurl(mpdXml: String): String? {
        if (mpdXml.isEmpty()) return null
        val found = LinkedHashSet<String>()
        listOf(
            Regex("""(?is)<[\w.-]*:?laurl[^>]*>([^<]+)</"""),
            Regex("""(?i)(?:laurl|licenseUrl|license_url)\s*[="'>\s]+(https?://[^"'<\s]+)"""),
            Regex("""(?i)https?://[^\s"'<>]+(?:drmtoday|license-proxy-widevine|/widevine|/license)[^\s"'<>]*"""),
            Regex("""https?://[^\s"'<>]*(?:/lic[./]|lic\.)[^\s"'<>]*""")
        ).forEach { re ->
            re.findAll(mpdXml).forEach { m ->
                val raw = (m.groups.lastOrNull { it != null && it.value.startsWith("http", true) }?.value
                    ?: m.groupValues.getOrNull(1)
                    ?: "").trim()
                if (raw.startsWith("http", ignoreCase = true)) {
                    found.add(raw.trimEnd('"', '\'', '<', '>', ' '))
                }
            }
        }
        return found.firstOrNull { it.contains("drmtoday", true) }
            ?: found.firstOrNull { it.contains("widevine", true) }
            ?: found.firstOrNull()
    }

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

    private fun mergeCookie(a: String, b: String): String {
        val parts = LinkedHashSet<String>()
        a.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { parts.add(it) }
        b.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { parts.add(it) }
        return parts.joinToString("; ")
    }

    private fun jsonWrapKeyFromKind(kind: String): String? {
        if (!kind.startsWith("json:")) return null
        val keys = kind.removePrefix("json:").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        for (candidate in listOf("payload", "challenge", "spc", "licenseRequest", "request")) {
            if (keys.any { it.equals(candidate, true) }) return candidate
        }
        return keys.firstOrNull()
    }

    private fun parseHeaderJson(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val out = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.optString(k, "")
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun withCookies(url: String, headers: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        headers.forEach { (k, v) -> if (k.isNotBlank() && !skipHeader(k)) out[k] = v }
        val cm = CookieManager.getInstance()
        val cookieParts = LinkedHashSet<String>()
        listOf(url, if (izvor.isEmpty()) "" else "$izvor/")
            .filter { it.isNotEmpty() }
            .forEach { u ->
            try {
                val c = cm.getCookie(u)
                if (!c.isNullOrBlank()) cookieParts.add(c)
            } catch (_: Exception) {}
        }
        if (cookieParts.isNotEmpty() && out.keys.none { it.equals("Cookie", true) }) {
            out["Cookie"] = cookieParts.joinToString("; ")
        }
        if (out.keys.none { it.equals("User-Agent", true) }) {
            out["User-Agent"] = ChromiumEngineView.DESKTOP_USER_AGENT
        }
        if (izvor.isNotEmpty()) {
            if (out.keys.none { it.equals("Referer", true) }) out["Referer"] = "$izvor/"
            if (out.keys.none { it.equals("Origin", true) }) out["Origin"] = izvor
        }
        return out
    }

    private fun skipHeader(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n == "content-length" || n == "host" || n == "connection" ||
            n == "accept-encoding" || n == "transfer-encoding"
    }
}
