/*
 * Safeer threat list agent for Android and Android TV (pure JVM, no Android imports).
 *
 * Keeps public plain-text blocklists (abuse.ch, Phishing Army ...) on the device and refreshes them
 * every time the browser starts, without slowing the start or the first page:
 *  - start() returns at once; all work runs on one low-priority background thread.
 *  - The lists saved by the previous run are loaded first (integrity checked with SHA-256), so the
 *    protection is complete a moment after start instead of after a download.
 *  - The network check waits [startDelayMs] (the first page loads first) and uses conditional GET
 *    (ETag / Last-Modified): an unchanged list costs one small request, not a download.
 *  - Restarts in quick succession do not repeat the check within [minCheckIntervalSeconds].
 *  - Afterwards the lists are checked every [intervalSeconds] while the browser runs.
 *  - Every failure keeps the lists that are already in use. Requests are anonymous GETs of public files.
 *
 * The same file is used unchanged by Safeer Browser for Android and Android TV.
 */
package com.safeer.threatfeed

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One public blocklist: a hosts file or one domain per line. */
data class PlainListSource(
    /** File-name safe identifier: lower case letters, digits and dashes. */
    val id: String,
    val name: String,
    val url: String,
    val category: String,
    /** Text that must appear near the top of the file; rejects captive portals and error pages. */
    val marker: String,
    val minEntries: Int = 20,
    val maxBytes: Int = 32 * 1024 * 1024,
    /** Lists of IPv4 addresses (for example Feodo Tracker) instead of host names. */
    val ipv4: Boolean = false,
    /**
     * Lists without a header (for example the SI-CERT phishing domain list): one domain per line, or
     * `timestamp,domain`. There is no [marker] to check, so instead nearly every non-empty line must be a valid
     * entry and there must be at least [minEntries] of them; error pages and captive portals fail that test.
     */
    val headerless: Boolean = false,
    /**
     * Filter lists in Adblock Plus syntax (EasyList): every non-comment line is kept verbatim for
     * FilterListEngine instead of being read as a host name. The threat engine ignores raw lists.
     */
    val raw: Boolean = false,
    /**
     * Only for Safeer Scit (the TV-wide DNS filter): ad and tracker domains that the browser's own
     * threat engine must not treat as "dangerous" pages. The threat engine ignores these lists.
     */
    val dnsOnly: Boolean = false,
    /**
     * Uporabnikov kljuc za seznam, ki ga brez kljuca ni (abuse.ch URLhaus od 21. 10. 2026, lastnik 9. 11. 2026): [url]
     * vsebuje »{KLJUC}«. Brez kljuca (null) se seznam ne prenasa in ostane zadnja shranjena kopija; to ni napaka.
     * Kljuc sme imeti samo crke in stevke (gre v pot naslova) in se ne zapise v napake ali dnevnik.
     */
    val kljuc: (() -> String?)? = null,
) {
    init {
        require(id.length in 1..40 && id.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) { "invalid list id" }
    }
}

/**
 * One list with all its entries. The agent hands these to [ThreatListAgent.onLists] only for the duration of that call
 * and does not keep them: the entries (hundreds of thousands of strings, ~20 MB on the TV, measured 4. 11. 2026) are
 * needed only to build the engines. [sha256] is the SHA-256 of the saved list file ("" when unknown).
 */
class PlainList(val source: PlainListSource, val entries: List<String>, val fetchedAtEpochSeconds: Long, val sha256: String = "")

/** What the agent keeps in memory about a list in use: no entries, only their number and checksum. */
class PlainListInfo(val source: PlainListSource, val count: Int, val sha256: String, val fetchedAtEpochSeconds: Long)

class ListRejectedException(message: String) : IOException(message)

data class ConditionalResponse(val notModified: Boolean, val body: ByteArray?, val etag: String?, val lastModified: String?)

fun interface ConditionalFetcher {
    @Throws(IOException::class)
    fun fetch(url: String, limit: Int, etag: String?, lastModified: String?): ConditionalResponse
}

/** Anonymous conditional GET: no cookies or caches, HTTPS only (also after redirects), hard limits. */
class HttpsListFetcher(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
    private val totalTimeoutMs: Long = 120_000,
    private val allowPlainHttpForTests: Boolean = false,
) : ConditionalFetcher {
    override fun fetch(url: String, limit: Int, etag: String?, lastModified: String?): ConditionalResponse {
        if (!url.startsWith("https://") && !allowPlainHttpForTests) throw IOException("only HTTPS is allowed")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true // HttpURLConnection never follows HTTPS -> HTTP
            connection.useCaches = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("User-Agent", "Safeer")
            connection.setRequestProperty("Accept", "text/plain")
            if (!etag.isNullOrEmpty()) connection.setRequestProperty("If-None-Match", etag)
            if (!lastModified.isNullOrEmpty()) connection.setRequestProperty("If-Modified-Since", lastModified)
            val status = connection.responseCode
            if (!allowPlainHttpForTests && connection.url.protocol != "https") throw IOException("redirected away from HTTPS")
            if (status == 304) return ConditionalResponse(true, null, etag, lastModified)
            if (status != 200) throw IOException("HTTP status $status")
            val declared = connection.getHeaderField("Content-Length")?.toLongOrNull()
            if (declared != null && declared > limit) throw IOException("response too large")
            val deadline = System.currentTimeMillis() + totalTimeoutMs
            val body = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream(64 * 1024)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (out.size() + read > limit) throw IOException("response too large")
                    out.write(buffer, 0, read)
                    if (System.currentTimeMillis() > deadline) throw IOException("download took too long")
                }
                out.toByteArray()
            }
            return ConditionalResponse(false, body, connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"))
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Brez java.util.regex (8. 11. 2026, izmerjeno na TV): vsak Matcher je v ART nativni objekt ICU; pri 420.810 domenah
 * (~4 ujemanja na vrstico) je gradnja nabora v procesu :scit sprozila 6 GC »NativeAlloc« (460k-1,18M objektov vsak) in
 * nativno kopico napihnila z 1,7 MB na 112-141 MB, ki je ART storitvi v ospredju ne vrne (PSS Scita 15 -> 154-208 MB).
 * Rocni preverjalniki so natanko enakovredni prejsnjim izrazom (tests/ThreatListAgentTest.kt, orakelj Regex v testu):
 *  - oznaka gostitelja: `[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?`
 *  - IPv4: `(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])(\\.(...)){3}`
 *  - casovni zig CSV: `[0-9]{4}-[0-9]{2}-[0-9]{2}([T ][0-9]{2}:[0-9]{2}(:[0-9]{2})?(\\.[0-9]+)?(Z|[+-][0-9]{2}:?[0-9]{2})?)?`
 * Seznam se bere po vrsticah iz toka, ne iz enega niza (HaGeZi ~8 MB bajtov je bil se ~16 MB UTF-16 v kopici).
 */
object PlainListParser {
    private val sinkAddresses = setOf("0.0.0.0", "127.0.0.1", "::", "::1")

    private fun digit(c: Char) = c in '0'..'9'
    private fun labelEdge(c: Char) = c in 'a'..'z' || digit(c) || c == '_'
    private fun labelInner(c: Char) = labelEdge(c) || c == '-'

    /** `[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?` over the whole string. */
    fun isHostLabel(s: CharSequence): Boolean {
        val n = s.length
        if (n < 1 || n > 63 || !labelEdge(s[0])) return false
        if (n == 1) return true
        if (!labelEdge(s[n - 1])) return false
        for (i in 1 until n - 1) if (!labelInner(s[i])) return false
        return true
    }

    /** One octet `25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9]`: 1-3 digits, no leading zero unless exactly "0", <= 255. */
    private fun isOctet(s: CharSequence, from: Int, to: Int): Boolean {
        val n = to - from
        if (n < 1 || n > 3) return false
        var v = 0
        for (i in from until to) { val c = s[i]; if (!digit(c)) return false; v = v * 10 + (c - '0') }
        if (n > 1 && s[from] == '0') return false
        return v <= 255
    }

    /** Four octets separated by single dots, nothing else (the former `ipv4.matches`). */
    fun isIpv4(s: CharSequence): Boolean {
        var start = 0
        var octets = 0
        val n = s.length
        for (i in 0..n) {
            if (i == n || s[i] == '.') {
                if (!isOctet(s, start, i)) return false
                octets++
                if (octets > 4) return false
                start = i + 1
            }
        }
        return octets == 4
    }

    private fun digits(s: CharSequence, at: Int, count: Int): Boolean {
        if (at + count > s.length) return false
        for (i in at until at + count) if (!digit(s[i])) return false
        return true
    }

    /**
     * `2026-09-12T08:47:04+01:00`, `2026-09-12 08:47:04.5Z` or `2026-09-12` (the former `csvTimestamp.matches`):
     * date, then optionally `[T ]HH:MM`, `:SS`, `.fraction`, `Z` or `+HH:MM`/`+HHMM`.
     */
    fun isCsvTimestamp(s: CharSequence): Boolean {
        val n = s.length
        if (!digits(s, 0, 4) || n < 10 || s[4] != '-' || !digits(s, 5, 2) || s[7] != '-' || !digits(s, 8, 2)) return false
        var i = 10
        if (i == n) return true
        if ((s[i] != 'T' && s[i] != ' ') || !digits(s, i + 1, 2) || i + 3 >= n || s[i + 3] != ':' || !digits(s, i + 4, 2)) return false
        i += 6
        if (i < n && s[i] == ':') { if (!digits(s, i + 1, 2)) return false; i += 3 }
        if (i < n && s[i] == '.') {
            var j = i + 1
            while (j < n && digit(s[j])) j++
            if (j == i + 1) return false
            i = j
        }
        if (i == n) return true
        if (s[i] == 'Z') return i + 1 == n
        if (s[i] != '+' && s[i] != '-') return false
        if (!digits(s, i + 1, 2)) return false
        i += 3
        if (i < n && s[i] == ':') i++
        return digits(s, i, 2) && i + 2 == n
    }

    /** Lines of [bytes] as UTF-8, one at a time (no copy of the whole list as a String). */
    private fun lines(bytes: ByteArray): Sequence<String> =
        bytes.inputStream().reader(Charsets.UTF_8).buffered(64 * 1024).lineSequence()

    /** Normalized, de-duplicated entries; throws [ListRejectedException] for files that are not this list. */
    fun parse(bytes: ByteArray, source: PlainListSource): List<String> {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8).lowercase()
        if (!source.headerless && !head.contains(source.marker.lowercase())) throw ListRejectedException("marker '${source.marker}' missing")
        if (head.contains("<html") || head.contains("<!doctype")) throw ListRejectedException("HTML instead of a list")
        val seen = LinkedHashSet<String>()
        if (source.raw) {
            lines(bytes).forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) return@forEach
                if (line.length <= 4096) seen.add(line)
            }
            if (seen.size < source.minEntries) throw ListRejectedException("only ${seen.size} rules")
            return ArrayList(seen)
        }
        var dataLines = 0
        var validLines = 0
        // Lastnik 9. 11. 2026 (AdGuard DNS filter): izjeme »@@||ime^« seznama. Vnos istega seznama, ki je ime izjeme ali
        // njegova nadrejena domena, izpustimo (nabor je »wildcard« in izjeme ne zna); drugi seznami ostanejo nedotaknjeni.
        val exceptions = HashSet<String>()
        lines(bytes).forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("!")) return@forEach
            if (line.startsWith("@@||")) {
                val e = line.removePrefix("@@||").substringBefore('^').substringBefore('$').lowercase().trimEnd('.')
                if (isHostName(e)) exceptions.add(e)
                return@forEach
            }
            dataLines++
            val fields = if (source.headerless) line.split(',', ';').map { it.trim() } else emptyList()
            val text = if (fields.size >= 2 && isCsvTimestamp(fields[0])) fields[1] else line
            val parts = text.split(' ', '\t').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return@forEach
            val candidate = (if (parts.size >= 2 && parts[0] in sinkAddresses) parts[1] else parts[0])
                .lowercase().trimEnd('.').removePrefix("||").removeSuffix("^").removePrefix("*.")
            val entry = if (source.ipv4) candidate.takeIf { isIpv4(it) } else candidate.takeIf { isHostName(it) }
            if (entry != null) { validLines++; seen.add(entry) }
        }
        if (source.headerless && validLines * 10 < dataLines * 9) throw ListRejectedException("only $validLines of $dataLines lines are entries")
        for (e in exceptions) {
            var d = e
            while (true) {
                seen.remove(d)
                val pika = d.indexOf('.')
                if (pika < 0) break
                d = d.substring(pika + 1)
            }
        }
        if (seen.size < source.minEntries) throw ListRejectedException("only ${seen.size} entries")
        return ArrayList(seen)
    }

    fun isHostName(name: String): Boolean {
        if (name.length !in 4..253 || !name.contains('.') || isIpv4(name)) return false
        // localhost.localdomain: glava hosts (StevenBlack, privzeti seznam Pi-hole) - kot Safeer-linux razcleni_seznam.
        if (name == "localhost" || name == "localhost.localdomain" || name.endsWith(".localhost") || name.endsWith(".local")) return false
        val labels = name.split('.')
        return labels.all { isHostLabel(it) } && labels.last().any { it.isLetter() }
    }

    /** UTF-8 of the entries joined with '\n' (the saved list), written once - without the joined String in between. */
    fun joinUtf8(entries: List<String>): ByteArray {
        var size = 0
        for (e in entries) size += e.length + 1
        val out = java.io.ByteArrayOutputStream(size.coerceAtLeast(16))
        val w = out.writer(Charsets.UTF_8)
        for ((i, e) in entries.withIndex()) { if (i > 0) w.write("\n"); w.write(e) }
        w.flush()
        return out.toByteArray()
    }

    /** Entries of a saved list (joinUtf8 inverse): one line each, read from a stream, [count] expected. */
    fun splitUtf8(bytes: ByteArray, count: Int): List<String> {
        if (bytes.isEmpty()) return emptyList()
        val out = ArrayList<String>(count.coerceIn(0, 1 shl 20))   // zgornja meja: pokvarjen meta.count ne rezervira GB
        bytes.inputStream().reader(Charsets.UTF_8).buffered(64 * 1024).forEachLine { out.add(it) }
        // A list whose last entry is empty keeps the trailing empty line (String.split did); readers drop it.
        if (bytes[bytes.size - 1] == '\n'.code.toByte()) out.add("")
        return out
    }
}

class ThreatListAgent(
    val directory: File,
    val sources: List<PlainListSource>,
    private val fetcher: ConditionalFetcher = HttpsListFetcher(),
    private val startDelayMs: Long = 12_000L,
    private val minCheckIntervalSeconds: Long = 15 * 60L,
    private val intervalSeconds: Long = 6 * 3600L,
    private val retrySeconds: Long = 3600L,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    /**
     * Called once after the first load of the saved lists has finished (after [onLists], also when nothing was saved),
     * so that a caller can hold back the first requests of a cold start until the protection is complete.
     */
    private val onFirstLoad: () -> Unit = {},
    /**
     * Receives the lists read by a partial first load (start(firstLoadOnly)): only those ids, the other saved lists stay
     * on disk. The caller already serves them from its own index (for example a mapped file built from the same SHA-256).
     */
    private val onPartialLists: (List<PlainList>) -> Unit = {},
    /** Thread priority of the first load (it decides how long a cold start runs without the lists); later work runs at MIN. */
    private val firstLoadPriority: Int = Thread.MIN_PRIORITY,
    /**
     * Name of the throttle state file in [directory]. A second agent over a subset of the same lists (Safeer Scit in its own
     * process, 4. 11. 2026) uses its own name, so its check does not hold back the browser's check of the other lists.
     */
    private val stateName: String = "agent.state",
    /** Receives every list in use: once after loading the saved lists and after every change. */
    private val onLists: (List<PlainList>) -> Unit,
) {
    init {
        require(sources.map { it.id }.toSet().size == sources.size) { "duplicate list id" }
    }

    private val lock = Any()
    private var executor: ScheduledExecutorService? = null
    private var scheduled: ScheduledFuture<*>? = null
    private val random = java.security.SecureRandom()

    /** Lists in use (metadata only; the entries were delivered to [onLists] and dropped). */
    @Volatile var lists: List<PlainListInfo> = emptyList()
        private set
    @Volatile var lastError: String = ""
        private set

    private class Meta(val sha256: String, val count: Int, val fetchedAt: Long, val etag: String?, val lastModified: String?)

    private val validators = HashMap<String, Meta>()

    private fun listFile(id: String) = File(directory, "$id.list")
    private fun metaFile(id: String) = File(directory, "$id.meta")
    private val stateFile get() = File(directory, stateName)

    /**
     * Starts the agent on a background thread and returns immediately. With [firstLoadOnly] the first load reads and
     * verifies only those lists (to [onPartialLists]); the others are taken over from their metadata (count, SHA-256,
     * validators) without reading their entries, so updates and rebuilds work as after a full load.
     */
    @Synchronized
    fun start(firstLoadOnly: Set<String>? = null): Boolean {
        if (executor != null) return false
        val service = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "safeer-threat-lists").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
        executor = service
        service.execute {
            val thread = Thread.currentThread()
            try {
                thread.priority = firstLoadPriority
                if (firstLoadOnly == null) {
                    val loaded = loadSaved()
                    if (loaded.isNotEmpty()) notifyLists(loaded)
                } else {
                    val loaded = loadSaved(firstLoadOnly)
                    try { onPartialLists(loaded) } catch (e: Exception) { /* a listener must not stop the agent */ }
                }
            } finally {
                thread.priority = Thread.MIN_PRIORITY
                try { onFirstLoad() } catch (e: Exception) { /* a listener must not stop the agent */ }
            }
        }
        schedule(startDelayMs, periodic = false)
        return true
    }

    @Synchronized
    fun stop() {
        scheduled?.cancel(false)
        executor?.shutdownNow()
        executor = null
        scheduled = null
    }

    /** Immediate check that ignores the restart throttle (for an "update lists" button). */
    @Synchronized
    fun requestUpdate(callback: ((changedLists: Int) -> Unit)? = null): Boolean {
        val service = executor ?: return false
        service.execute {
            val changed = try { refresh(force = true) } catch (e: Exception) { 0 }
            try { callback?.invoke(changed) } catch (e: Exception) { /* ignore */ }
        }
        return true
    }

    /** Reads and delivers every saved list to [onLists] again on the agent thread (for example when a caller's index is damaged). */
    @Synchronized
    fun reloadSaved(): Boolean {
        val service = executor ?: return false
        service.execute {
            val loaded = try { loadSaved() } catch (e: Exception) { emptyList() }
            if (loaded.isNotEmpty()) notifyLists(loaded)
        }
        return true
    }

    /** Metadata of the saved lists (count, SHA-256) in source order, without reading the lists: a few small files. */
    fun savedInfo(): List<PlainListInfo> = sources.mapNotNull { source ->
        readMeta(source.id)?.let { PlainListInfo(source, it.count, it.sha256, it.fetchedAt) }
    }

    /**
     * Loads the lists saved by an earlier run; damaged files are ignored (and downloaded again). The caller gets the
     * entries; the agent keeps only [lists] (metadata). With [only], lists outside it are not read: their metadata is used
     * as saved (refresh() reads and verifies them when it needs their entries).
     */
    fun loadSaved(only: Set<String>? = null): List<PlainList> = synchronized(lock) {
        removeStaleTemp()
        val loaded = ArrayList<PlainList>()
        val infos = ArrayList<PlainListInfo>()
        for (source in sources) {
            val meta = readMeta(source.id) ?: continue
            if (only != null && source.id !in only) {
                validators[source.id] = meta
                infos.add(PlainListInfo(source, meta.count, meta.sha256, meta.fetchedAt))
                continue
            }
            val list = readSaved(source, meta)
            if (list != null) {
                loaded.add(list)
                infos.add(list.info())
                validators[source.id] = meta
            } else {
                validators.remove(source.id)
            }
        }
        lists = infos
        loaded
    }

    /** One saved list with its entries, checked against [meta]; null when the file is missing or damaged. */
    private fun readSaved(source: PlainListSource, meta: Meta): PlainList? = try {
        val bytes = listFile(source.id).readBytes()
        if (sha256(bytes) != meta.sha256) throw IOException("checksum mismatch")
        val entries = PlainListParser.splitUtf8(bytes, meta.count)
        if (entries.size != meta.count) throw IOException("entry count mismatch")
        PlainList(source, entries, meta.fetchedAt, meta.sha256)
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun PlainList.info() = PlainListInfo(source, entries.size, sha256, fetchedAtEpochSeconds)

    /** Checks every list; returns how many changed. Skipped within the restart throttle unless forced. */
    fun refresh(force: Boolean = false): Int = synchronized(lock) {
        val now = clock()
        if (!force && now - lastCheck() in 0 until minCheckIntervalSeconds && lists.size == sources.size) return 0
        val inUse = lists.associateBy { it.source.id }
        val fresh = HashMap<String, PlainList>()
        val errors = ArrayList<String>()
        var changed = 0
        for (source in sources) {
            val known = validators[source.id]?.takeIf { inUse.containsKey(source.id) }
            try {
                val url: String
                val kljucVira = source.kljuc
                if (kljucVira != null) {
                    val k = try { kljucVira() } catch (e: Exception) { null }
                    if (k.isNullOrEmpty() || !k.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) continue
                    url = source.url.replace("{KLJUC}", k)
                } else {
                    url = source.url
                }
                val response = fetcher.fetch(url, source.maxBytes, known?.etag, known?.lastModified)
                if (response.notModified) {
                    if (known == null) throw IOException("304 without a saved list")
                    continue
                }
                val entries = PlainListParser.parse(response.body ?: throw IOException("empty body"), source)
                val bytes = PlainListParser.joinUtf8(entries)
                val meta = Meta(sha256(bytes), entries.size, now, response.etag?.take(200), response.lastModified?.take(100))
                if (known != null && known.sha256 == meta.sha256) {
                    writeMeta(source.id, meta) // same content, new validators
                    validators[source.id] = meta
                    continue
                }
                ensureDirectory()
                atomicWrite(listFile(source.id), bytes)
                writeMeta(source.id, meta)
                validators[source.id] = meta
                fresh[source.id] = PlainList(source, entries, now, meta.sha256)
                changed++
            } catch (e: Exception) {
                errors.add("${source.id}: ${e.javaClass.simpleName}: ${e.message}")
            } catch (e: OutOfMemoryError) {
                errors.add("${source.id}: out of memory")
            }
        }
        lastError = errors.joinToString("; ")
        try {
            ensureDirectory()
            atomicWrite(stateFile, "lastCheck=$now\n".toByteArray(Charsets.US_ASCII))
        } catch (e: IOException) { /* throttle is best effort */ }
        if (changed > 0) {
            // The listener rebuilds the engines from every list, so the unchanged ones are read back from disk (checked
            // with SHA-256) instead of being kept in memory between updates.
            val all = sources.mapNotNull { source ->
                fresh[source.id] ?: inUse[source.id]?.let { info ->
                    validators[source.id]?.takeIf { it.sha256 == info.sha256 }?.let { readSaved(source, it) }
                        ?: run { validators.remove(source.id); errors.add("${source.id}: saved list unreadable"); null }
                }
            }
            lastError = errors.joinToString("; ")
            lists = all.map { it.info() }
            notifyLists(all)
        }
        changed
    }

    fun lastCheck(): Long = try {
        stateFile.readText(Charsets.US_ASCII).trim().removePrefix("lastCheck=").toLong()
    } catch (e: Exception) {
        0L
    }

    private fun notifyLists(all: List<PlainList>) {
        try { onLists(all) } catch (e: Exception) { /* a listener must not stop the agent */ }
    }

    @Synchronized
    private fun schedule(delayMs: Long, periodic: Boolean) {
        val service = executor ?: return
        val jitter = if (periodic) 0.9 + random.nextDouble() * 0.2 else 1.0
        scheduled = service.schedule({
            try { refresh(force = false) } catch (e: Exception) { /* keep lists in use */ }
            schedule((if (lastError.isEmpty()) intervalSeconds else retrySeconds) * 1000, periodic = true)
        }, maxOf(0L, (delayMs * jitter).toLong()), TimeUnit.MILLISECONDS)
    }

    private fun readMeta(id: String): Meta? {
        return try {
            val values = metaFile(id).readLines(Charsets.UTF_8).filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            Meta(
                values["sha256"] ?: return null, values["count"]?.toInt() ?: return null, values["fetchedAt"]?.toLong() ?: 0L,
                values["etag"]?.takeIf { it.isNotEmpty() }, values["lastModified"]?.takeIf { it.isNotEmpty() },
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun writeMeta(id: String, meta: Meta) {
        ensureDirectory()
        val clean = { value: String? -> (value ?: "").replace("\n", "").replace("\r", "") }
        val text = "sha256=${meta.sha256}\ncount=${meta.count}\nfetchedAt=${meta.fetchedAt}\n" +
            "etag=${clean(meta.etag)}\nlastModified=${clean(meta.lastModified)}\n"
        atomicWrite(metaFile(id), text.toByteArray(Charsets.UTF_8))
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create ${directory.name}")
    }

    private fun atomicWrite(target: File, data: ByteArray) = atomicReplace(target, data)

    /** Temporary files of a write that was cut off (process killed); fresh ones may belong to a write in progress. */
    private fun removeStaleTemp() {
        val limit = System.currentTimeMillis() - STALE_TEMP_MS
        try {
            directory.listFiles { f -> f.isFile && f.name.endsWith(".tmp") && f.lastModified() < limit }?.forEach { it.delete() }
        } catch (e: Exception) { /* best effort */ }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private const val STALE_TEMP_MS = 3_600_000L

/**
 * Replaces [target] atomically through a temporary file with a unique name in the same directory. The browser and Safeer
 * Scit (its own process) may write the same list at the same time; with the fixed name "<name>.tmp" one process truncated
 * or moved away the other's half-written file (4. 11. 2026). On failure the temporary file is removed.
 */
internal fun atomicReplace(target: File, data: ByteArray) {
    val temp = File.createTempFile("${target.name}.", ".tmp", target.parentFile)
    try {
        FileOutputStream(temp).use { out ->
            out.write(data)
            out.flush()
            out.fd.sync()
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Throwable) {
        temp.delete()
        throw e
    }
}
