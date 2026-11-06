/*
 * Safeer signed threat feed client for Android and Android TV (pure JVM, no Android imports).
 *
 * Normative format: docs/THREAT_FEED_FORMAT.md in https://github.com/memelandfaner/safeer-threat-intel
 * The same file is used unchanged by Safeer Browser for Android and Safeer Browser for Android TV;
 * compare SHA-256 sums before editing a copy.
 *
 * Security properties
 *  - A manifest is accepted only when an Ed25519 signature from a built-in trusted key covers its
 *    exact bytes. A bundle is accepted only when its size, SHA-256 and own signature match.
 *  - Signed documents must be in canonical form; the parser accepts nothing else.
 *  - Versions only move forward (anti-rollback); expired manifests are refused.
 *  - Every failure (network, verification, disk, memory) keeps the last verified bundle.
 *  - Nothing about browsing leaves the device: updates are two anonymous GETs of public files.
 *
 * Ed25519 verification uses Bouncy Castle's RFC 8032 implementation (org.bouncycastle.math.ec.rfc8032),
 * shipped as a reproducibly shrunk jar because java.security Ed25519 exists only on Android 13+.
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
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import org.bouncycastle.math.ec.rfc8032.Ed25519

open class FeedVerificationException(message: String) : Exception(message)
class RollbackException(message: String) : FeedVerificationException(message)
class ExpiredFeedException(message: String) : FeedVerificationException(message)

object SignedFeedFormat {
    const val SCHEMA_VERSION = 1L
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_BUNDLE_BYTES = 48 * 1024 * 1024
    const val MAX_SOURCES = 256
    const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    val BUNDLE_CONTEXT: ByteArray = "safeer-bundle-v1\n".toByteArray(Charsets.US_ASCII)
    val MANIFEST_CONTEXT: ByteArray = "safeer-manifest-v1\n".toByteArray(Charsets.US_ASCII)
    val INDICATOR_TYPES = listOf("domain", "hostname", "url", "ipv4", "ipv6")
    val CATEGORIES = listOf("botnet_c2", "malware", "phishing", "scam", "ads", "tracker")
    val FEED_TYPES = listOf("threats", "adblock")

    /** Categories allowed in each feed type; a threats bundle never carries ads or trackers. */
    val FEED_CATEGORIES = mapOf(
        "threats" to setOf("botnet_c2", "malware", "phishing", "scam"),
        "adblock" to setOf("ads", "tracker"),
    )

    /** Higher is more severe. When several rules match, the most severe one is reported. */
    fun severity(category: String): Int = when (category) {
        "botnet_c2" -> 6
        "malware" -> 5
        "phishing" -> 4
        "scam" -> 3
        "tracker" -> 2
        "ads" -> 1
        else -> 0
    }
}

// ------------------------------------------------------------------------------------------------
// Ed25519
// ------------------------------------------------------------------------------------------------

object Ed25519Verifier {
    /** Strict RFC 8032 verification (non-canonical S and invalid points are rejected). */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        return try {
            Ed25519.verify(signature, 0, publicKey, 0, message, 0, message.size)
        } catch (e: RuntimeException) {
            false
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Strict JSON
// ------------------------------------------------------------------------------------------------

/**
 * Byte cursor for the canonical JSON subset: printable ASCII strings with only \" and \\ escapes,
 * integers 0..2^53-1 without leading zeros, no whitespace. With [allowWhitespace] (only used for the
 * unsigned manifest envelope) JSON whitespace between tokens is skipped.
 */
internal class JsonCursor(val data: ByteArray, private val allowWhitespace: Boolean = false) {
    var pos = 0

    fun fail(message: String): Nothing = throw FeedVerificationException("invalid JSON at byte $pos: $message")

    fun skipWhitespace() {
        if (!allowWhitespace) return
        while (pos < data.size) {
            val c = data[pos].toInt()
            if (c == 0x20 || c == 0x09 || c == 0x0a || c == 0x0d) pos++ else return
        }
    }

    fun peek(): Int {
        skipWhitespace()
        return if (pos < data.size) data[pos].toInt() and 0xff else -1
    }

    fun expect(ch: Char) {
        skipWhitespace()
        if (pos >= data.size || data[pos].toInt() != ch.code) fail("expected '$ch'")
        pos++
    }

    fun tryConsume(ch: Char): Boolean {
        skipWhitespace()
        if (pos < data.size && data[pos].toInt() == ch.code) {
            pos++
            return true
        }
        return false
    }

    fun readString(): String {
        expect('"')
        val start = pos
        var escaped = false
        while (true) {
            if (pos >= data.size) fail("unterminated string")
            val c = data[pos].toInt() and 0xff
            if (c == '"'.code) break
            if (c < 0x20 || c > 0x7e) fail("character outside printable ASCII")
            if (c == '\\'.code) {
                val next = if (pos + 1 < data.size) data[pos + 1].toInt() else -1
                if (next != '"'.code && next != '\\'.code) fail("unsupported escape")
                escaped = true
                pos += 2
            } else {
                pos++
            }
        }
        val raw = String(data, start, pos - start, Charsets.US_ASCII)
        pos++
        return if (escaped) unescape(raw) else raw
    }

    private fun unescape(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\') {
                out.append(raw[i + 1])
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    fun readInteger(): Long {
        skipWhitespace()
        val start = pos
        while (pos < data.size && data[pos] >= '0'.code.toByte() && data[pos] <= '9'.code.toByte()) pos++
        val length = pos - start
        if (length == 0) fail("expected an unsigned integer")
        if (length > 1 && data[start] == '0'.code.toByte()) fail("leading zero")
        if (length > 16) fail("integer out of range")
        if (pos < data.size && (data[pos] == '.'.code.toByte() || data[pos] == 'e'.code.toByte() || data[pos] == 'E'.code.toByte())) {
            fail("floating point numbers are not allowed")
        }
        val value = String(data, start, length, Charsets.US_ASCII).toLong()
        if (value > SignedFeedFormat.MAX_SAFE_INTEGER) fail("integer out of range")
        return value
    }

    /** Reads `"name":` and fails unless the key is exactly [name]. */
    fun expectKey(name: String) {
        val key = readString()
        if (key != name) fail("expected key '$name', found '$key'")
        expect(':')
    }

    fun expectEnd() {
        skipWhitespace()
        if (pos != data.size) fail("trailing data")
    }

    /** Generic value for the small unsigned envelope: Map (no duplicate keys), List, String or Long. */
    fun readValue(depth: Int = 0): Any {
        if (depth > 8) fail("nesting too deep")
        return when (peek()) {
            '{'.code -> {
                expect('{')
                val map = LinkedHashMap<String, Any>()
                if (!tryConsume('}')) {
                    do {
                        val key = readString()
                        if (map.containsKey(key)) fail("duplicate key '$key'")
                        expect(':')
                        map[key] = readValue(depth + 1)
                    } while (tryConsume(','))
                    expect('}')
                }
                map
            }
            '['.code -> {
                expect('[')
                val list = ArrayList<Any>()
                if (!tryConsume(']')) {
                    do {
                        list.add(readValue(depth + 1))
                    } while (tryConsume(','))
                    expect(']')
                }
                list
            }
            '"'.code -> readString()
            in '0'.code..'9'.code -> readInteger()
            else -> fail("unsupported value")
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Values and verification helpers
// ------------------------------------------------------------------------------------------------

data class SignatureRef(val alg: String, val keyId: String, val sig: String)

data class FeedManifest(
    val feedType: String,
    val version: Long,
    val generatedAt: String,
    val expiresAt: String,
    val expiresAtEpochSeconds: Long,
    val ruleCount: Long,
    val rulesSha256: String,
    val bundlePath: String,
    val bundleSize: Int,
    val bundleSha256: String,
    val bundleSignature: SignatureRef,
)

data class FeedSource(val id: String, val license: String, val name: String, val url: String)

data class FeedMatch(val indicator: String, val indicatorType: String, val category: String, val source: FeedSource)

class VerifiedBundle internal constructor(
    val manifest: FeedManifest,
    val sources: List<FeedSource>,
    val ruleCount: Int,
    val index: ThreatIndex,
)

internal object FeedText {
    private val HEX64 = Pattern.compile("^[0-9a-f]{64}$")
    private val TIME = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})Z$")
    private val HOST = Pattern.compile(
        "^(?=.{4,253}$)(?:[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?\\.)+(?:[a-z]{2,63}|xn--[a-z0-9-]{1,59})$"
    )
    private val URL_RULE = Pattern.compile("^https?://[\\x21\\x23-\\x5b\\x5d-\\x7e]+$")
    private val IPV4 = Pattern.compile("^(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    private val KEY_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$")

    fun isHex64(value: String) = HEX64.matcher(value).matches()
    fun isKeyId(value: String) = KEY_ID.matcher(value).matches()

    fun epochSeconds(value: String): Long {
        val m = TIME.matcher(value)
        if (!m.matches()) throw FeedVerificationException("invalid timestamp")
        // Matcher.group je lahko null (neobvezna skupina); tu so vse skupine obvezne, a ne zaupamo slepo.
        fun del(i: Int): Int = m.group(i)?.toIntOrNull() ?: throw FeedVerificationException("invalid timestamp")
        if (del(1) < 1970) throw FeedVerificationException("invalid timestamp")
        return try {
            LocalDateTime.of(del(1), del(2), del(3), del(4), del(5), del(6)).toEpochSecond(ZoneOffset.UTC)
        } catch (e: DateTimeException) {
            throw FeedVerificationException("invalid timestamp")
        }
    }

    fun validRule(value: String, type: String): Boolean = when (type) {
        "domain", "hostname" -> HOST.matcher(value).matches()
        "url" -> value.length <= 2048 && URL_RULE.matcher(value).matches()
        "ipv4" -> IPV4.matcher(value).matches()
        "ipv6" -> Ipv6.parse(value)?.let { !Ipv6.isIpv4Mapped(it) && Ipv6.format(it) == value } ?: false
        else -> false
    }

    /** Strict standard base64 with padding: decoding and re-encoding must give the same text. */
    fun base64(text: String): ByteArray {
        val bytes = try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            throw FeedVerificationException("invalid base64")
        }
        if (Base64.getEncoder().encodeToString(bytes) != text) throw FeedVerificationException("non-canonical base64")
        return bytes
    }

    fun sha256(data: ByteArray, offset: Int = 0, length: Int = data.size): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data, offset, length)
        return digest.digest()
    }

    fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val alphabet = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            chars[i * 2] = alphabet[v ushr 4]
            chars[i * 2 + 1] = alphabet[v and 0x0f]
        }
        return String(chars)
    }
}

internal object Ipv6 {
    /** Parses hexadecimal IPv6 text (no zone, no embedded IPv4) into eight 16-bit groups. */
    fun parse(text: String): IntArray? {
        if (text.length < 2 || text.length > 39) return null
        val doubleColon = text.indexOf("::")
        if (doubleColon >= 0 && text.indexOf("::", doubleColon + 1) >= 0) return null
        fun groups(part: String): List<Int>? {
            if (part.isEmpty()) return emptyList()
            val result = ArrayList<Int>()
            for (group in part.split(':')) {
                if (group.isEmpty() || group.length > 4) return null
                var value = 0
                for (ch in group) {
                    val digit = Character.digit(ch, 16)
                    if (digit < 0) return null
                    value = value * 16 + digit
                }
                result.add(value)
            }
            return result
        }
        val out = IntArray(8)
        if (doubleColon < 0) {
            val all = groups(text) ?: return null
            if (all.size != 8) return null
            for (i in 0 until 8) out[i] = all[i]
        } else {
            val head = groups(text.substring(0, doubleColon)) ?: return null
            val tail = groups(text.substring(doubleColon + 2)) ?: return null
            if (head.size + tail.size > 7) return null
            for (i in head.indices) out[i] = head[i]
            for (i in tail.indices) out[8 - tail.size + i] = tail[i]
        }
        return out
    }

    fun isIpv4Mapped(groups: IntArray): Boolean =
        groups[0] == 0 && groups[1] == 0 && groups[2] == 0 && groups[3] == 0 && groups[4] == 0 && groups[5] == 0xffff

    /** RFC 5952 text: lowercase, no leading zeros, the first longest run of two or more zero groups as "::". */
    fun format(groups: IntArray): String {
        var bestStart = -1
        var bestLength = 0
        var runStart = -1
        var runLength = 0
        for (i in 0 until 8) {
            if (groups[i] == 0) {
                if (runStart < 0) runStart = i
                runLength++
                if (runLength > bestLength) {
                    bestStart = runStart
                    bestLength = runLength
                }
            } else {
                runStart = -1
                runLength = 0
            }
        }
        if (bestLength < 2) return groups.joinToString(":") { Integer.toHexString(it) }
        val head = (0 until bestStart).joinToString(":") { Integer.toHexString(groups[it]) }
        val tail = (bestStart + bestLength until 8).joinToString(":") { Integer.toHexString(groups[it]) }
        return "$head::$tail"
    }
}

// ------------------------------------------------------------------------------------------------
// Matching
// ------------------------------------------------------------------------------------------------

/**
 * Rule storage behind a [ThreatIndex]: packed value ((category index shl 16) or source index) of an exact key, or -1.
 * Two implementations with identical answers: hash maps (built while a bundle is verified) and a mapped file
 * ([SortedStringTable], written right after verification and opened in milliseconds at the next start).
 */
internal interface RuleLookup {
    val domainCount: Int
    val hostCount: Int
    val urlCount: Int
    fun domain(key: String): Int
    fun host(key: String): Int
    fun url(key: String): Int
}

internal class MapRuleLookup(
    val domains: HashMap<String, Int>,
    val hosts: HashMap<String, Int>,
    val urls: HashMap<String, Int>,
) : RuleLookup {
    override val domainCount get() = domains.size
    override val hostCount get() = hosts.size
    override val urlCount get() = urls.size
    override fun domain(key: String) = domains[key] ?: -1
    override fun host(key: String) = hosts[key] ?: -1
    override fun url(key: String) = urls[key] ?: -1
}

/** Tables 0, 1, 2 = domains, hosts, urls. Values that do not decode (damaged file) count as "no rule". */
internal class TableRuleLookup(val table: SortedStringTable, private val sourceCount: Int) : RuleLookup {
    override val domainCount get() = table.size(0)
    override val hostCount get() = table.size(1)
    override val urlCount get() = table.size(2)
    private fun checked(v: Int) = if (v < 0 || (v ushr 16) >= SignedFeedFormat.CATEGORIES.size || (v and 0xffff) >= sourceCount) -1 else v
    override fun domain(key: String) = checked(table.find(0, key))
    override fun host(key: String) = checked(table.find(1, key))
    override fun url(key: String) = checked(table.find(2, key))
}

/** Immutable lookup structure: exact lookups for hosts, IPs and URLs, suffix walk for domains. */
class ThreatIndex internal constructor(
    internal val rules: RuleLookup,
    private val sources: List<FeedSource>,
) {
    internal constructor(
        domains: HashMap<String, Int>,
        hosts: HashMap<String, Int>,
        urls: HashMap<String, Int>,
        sources: List<FeedSource>,
    ) : this(MapRuleLookup(domains, hosts, urls), sources)

    val size: Int get() = rules.domainCount + rules.hostCount + rules.urlCount

    /** True when the rules are served from the mapped index file (not from the Java heap). */
    val mapped: Boolean get() = rules is TableRuleLookup

    /** Every rule that covers the host is considered; the most severe category wins. */
    fun matchHost(rawHost: String?): FeedMatch? = matchHostWhere(rawHost) { true }

    /**
     * Kot [matchHost], a uposteva samo pravila za gostitelje/domene, za katere [sme] vrne true. Scit tako na
     * zlo.web.app najde pravilo projekta, tudi ce vir navaja (resneje) celotno web.app, ki ga nikoli ne blokiramo.
     */
    fun matchHostWhere(rawHost: String?, sme: (String) -> Boolean): FeedMatch? {
        val host = normalizeHost(rawHost) ?: return null
        var best: FeedMatch? = rules.host(host).takeIf { it >= 0 && sme(host) }?.let {
            match(host, if (host.contains(':')) "ipv6" else if (isIpv4(host)) "ipv4" else "hostname", it)
        }
        if (rules.domainCount == 0) return best
        var index = host.length
        while (index > 0) {
            val dot = host.lastIndexOf('.', index - 1)
            val candidate = host.substring(dot + 1)
            rules.domain(candidate).takeIf { it >= 0 && sme(candidate) }?.let { best = moreSevere(best, match(candidate, "domain", it)) }
            if (dot < 0) break
            index = dot
        }
        return best
    }

    /** Host rules and the exact URL rule are all considered; the most severe category wins. */
    fun matchUrl(url: String?): FeedMatch? {
        if (url.isNullOrEmpty()) return null
        val parts = UrlParts.parse(url) ?: return null
        val hostMatch = matchHost(parts.host)
        if (rules.urlCount == 0) return hostMatch
        val key = parts.normalized() ?: return hostMatch
        return moreSevere(hostMatch, rules.url(key).takeIf { it >= 0 }?.let { match(key, "url", it) })
    }

    /**
     * Samo pravilo za tocen naslov (vrsta "url"), brez pravil gostitelja: na gostiteljih z uporabnisko vsebino
     * (sites.google.com ...) sme Scit blokirati stran, ne pa celotnega skupnega gostitelja.
     */
    fun matchUrlRule(url: String?): FeedMatch? {
        if (url.isNullOrEmpty() || rules.urlCount == 0) return null
        val key = UrlParts.parse(url)?.normalized() ?: return null
        return rules.url(key).takeIf { it >= 0 }?.let { match(key, "url", it) }
    }

    private fun moreSevere(current: FeedMatch?, candidate: FeedMatch?): FeedMatch? = when {
        current == null -> candidate
        candidate == null -> current
        SignedFeedFormat.severity(candidate.category) > SignedFeedFormat.severity(current.category) -> candidate
        else -> current
    }

    private fun match(indicator: String, type: String, packed: Int): FeedMatch =
        FeedMatch(indicator, type, SignedFeedFormat.CATEGORIES[packed ushr 16], sources[packed and 0xffff])

    companion object {
        val EMPTY = ThreatIndex(HashMap(), HashMap(), HashMap(), emptyList())

        /**
         * Writes the rules of a verified bundle to [file] for [openMapped]. [key] binds the file to the exact manifest
         * bytes (SHA-256), so a file from another version or feed is never used.
         */
        internal fun writeMapped(file: java.io.File, key: ByteArray, maps: MapRuleLookup, sources: List<FeedSource>) {
            val words = ArrayList<String?>(sources.size * 4)
            for (s in sources) { words.add(s.id); words.add(s.license); words.add(s.name); words.add(s.url) }
            val tables = listOf(maps.domains, maps.hosts, maps.urls).map { m ->
                val keys = m.keys.toTypedArray()
                java.util.Arrays.sort(keys)
                SortedStringTable.Table(keys.size, { keys[it] }, { m[keys[it]]!! })
            }
            SortedStringTable.write(file, key, words, tables)
        }

        /** Opens an index written by [writeMapped]; null when it is missing, incomplete or built from other bytes. */
        internal fun openMapped(file: java.io.File, key: ByteArray): Pair<ThreatIndex, List<FeedSource>>? {
            val table = SortedStringTable.open(file, key) ?: return null
            val w = table.words
            if (table.tableCount != 3 || w.isEmpty() || w.size % 4 != 0 || w.any { it == null }) return null
            val sources = (0 until w.size / 4).map { FeedSource(w[it * 4]!!, w[it * 4 + 1]!!, w[it * 4 + 2]!!, w[it * 4 + 3]!!) }
            return ThreatIndex(TableRuleLookup(table, sources.size), sources) to sources
        }

        private fun isIpv4(host: String) = host.isNotEmpty() && host.all { it == '.' || it in '0'..'9' }

        internal fun normalizeHost(raw: String?): String? {
            var host = raw?.trim()?.lowercase() ?: return null
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length - 1)
            host = host.trimEnd('.')
            if (host.isEmpty()) return null
            if (host.contains(':')) {
                val groups = Ipv6.parse(host) ?: return host
                if (Ipv6.isIpv4Mapped(groups)) {
                    // ::ffff:a.b.c.d reaches the IPv4 address, so it must match IPv4 rules.
                    return "${groups[6] ushr 8}.${groups[6] and 0xff}.${groups[7] ushr 8}.${groups[7] and 0xff}"
                }
                return Ipv6.format(groups)
            }
            return host
        }
    }
}

internal class UrlParts(val scheme: String, val host: String, val port: Int, val pathAndQuery: String) {
    fun normalized(): String? {
        if (scheme != "http" && scheme != "https") return null
        val hostText = ThreatIndex.normalizeHost(host) ?: return null
        val netloc = if (hostText.contains(':')) "[$hostText]" else hostText
        val defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        val authority = if (port >= 0 && !defaultPort) "$netloc:$port" else netloc
        val queryAt = pathAndQuery.indexOf('?')
        val path = if (queryAt >= 0) pathAndQuery.substring(0, queryAt) else pathAndQuery
        val query = if (queryAt >= 0) pathAndQuery.substring(queryAt + 1) else ""
        return "$scheme://$authority${path.ifEmpty { "/" }}" + (if (query.isNotEmpty()) "?$query" else "")
    }

    companion object {
        fun parse(url: String): UrlParts? {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd <= 0) return null
            val scheme = url.substring(0, schemeEnd).lowercase()
            var rest = url.substring(schemeEnd + 3)
            val hash = rest.indexOf('#')
            if (hash >= 0) rest = rest.substring(0, hash)
            var authorityEnd = rest.length
            for (i in rest.indices) {
                val ch = rest[i]
                if (ch == '/' || ch == '?' || ch == '\\') {
                    authorityEnd = i
                    break
                }
            }
            var authority = rest.substring(0, authorityEnd)
            val pathAndQuery = rest.substring(authorityEnd)
            val at = authority.lastIndexOf('@')
            if (at >= 0) authority = authority.substring(at + 1)
            val host: String
            var portText = ""
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) return null
                host = authority.substring(1, close)
                val after = authority.substring(close + 1)
                if (after.isNotEmpty()) {
                    if (!after.startsWith(":")) return null
                    portText = after.substring(1)
                }
            } else {
                val colon = authority.lastIndexOf(':')
                if (colon >= 0) {
                    host = authority.substring(0, colon)
                    portText = authority.substring(colon + 1)
                } else {
                    host = authority
                }
            }
            var port = -1
            if (portText.isNotEmpty()) {
                if (portText.length > 5 || !portText.all { it in '0'..'9' }) return null
                port = portText.toInt()
                if (port > 65535) return null
            }
            if (host.isEmpty()) return null
            return UrlParts(scheme, host.lowercase(), port, pathAndQuery)
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Verification
// ------------------------------------------------------------------------------------------------

object SignedFeedVerifier {

    private fun signatureValid(ref: SignatureRef, trustedKeys: Map<String, String>, context: ByteArray, data: ByteArray): Boolean {
        if (ref.alg != "ed25519") return false
        val publicText = trustedKeys[ref.keyId] ?: return false
        val publicKey = try { FeedText.base64(publicText) } catch (e: FeedVerificationException) { return false }
        val signature = try { FeedText.base64(ref.sig) } catch (e: FeedVerificationException) { return false }
        val message = ByteArray(context.size + data.size)
        System.arraycopy(context, 0, message, 0, context.size)
        System.arraycopy(data, 0, message, context.size, data.size)
        return Ed25519Verifier.verify(publicKey, message, signature)
    }

    private fun readSignature(cursor: JsonCursor): SignatureRef {
        cursor.expect('{')
        cursor.expectKey("alg")
        val alg = cursor.readString()
        cursor.expect(',')
        cursor.expectKey("key_id")
        val keyId = cursor.readString()
        cursor.expect(',')
        cursor.expectKey("sig")
        val sig = cursor.readString()
        cursor.expect('}')
        return SignatureRef(alg, keyId, sig)
    }

    /**
     * Verifies a `latest.json` envelope. [installedVersion] is the highest accepted version; an equal
     * version is returned (the caller decides that nothing needs to be done).
     */
    fun verifyManifest(
        envelopeBytes: ByteArray,
        trustedKeys: Map<String, String>,
        feedType: String,
        installedVersion: Long = 0,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
        checkExpiry: Boolean = true,
    ): FeedManifest = readManifest(envelopeBytes, trustedKeys, feedType, installedVersion, nowEpochSeconds, checkExpiry, checkSignature = true)

    /**
     * Same as [verifyManifest]; [checkSignature] false only for a stored manifest whose exact bytes (SHA-256) are the key
     * of a mapped index that was written right after this manifest passed [verifyManifest] (SignedFeedStore.loadFast).
     * The signature is checked again in the background (SignedFeedStore.recheck).
     */
    internal fun readManifest(
        envelopeBytes: ByteArray,
        trustedKeys: Map<String, String>,
        feedType: String,
        installedVersion: Long,
        nowEpochSeconds: Long,
        checkExpiry: Boolean,
        checkSignature: Boolean,
    ): FeedManifest {
        if (envelopeBytes.size > SignedFeedFormat.MAX_MANIFEST_BYTES) throw FeedVerificationException("manifest too large")
        val envelopeCursor = JsonCursor(envelopeBytes, allowWhitespace = true)
        val envelope = envelopeCursor.readValue()
        envelopeCursor.expectEnd()
        if (envelope !is Map<*, *> || envelope.keys != setOf("signed", "signatures")) {
            throw FeedVerificationException("invalid manifest envelope")
        }
        val signedText = envelope["signed"] as? String ?: throw FeedVerificationException("invalid manifest envelope")
        val signed = FeedText.base64(signedText)
        val signatures = envelope["signatures"] as? List<*> ?: throw FeedVerificationException("invalid signature list")
        if (signatures.isEmpty() || signatures.size > 4) throw FeedVerificationException("invalid signature list")
        val accepted = !checkSignature || signatures.any { entry ->
            if (entry !is Map<*, *> || entry.keys != setOf("alg", "key_id", "sig")) return@any false
            val alg = entry["alg"] as? String ?: return@any false
            val keyId = entry["key_id"] as? String ?: return@any false
            val sig = entry["sig"] as? String ?: return@any false
            signatureValid(SignatureRef(alg, keyId, sig), trustedKeys, SignedFeedFormat.MANIFEST_CONTEXT, signed)
        }
        if (!accepted) throw FeedVerificationException("manifest signature is not valid for any trusted key")

        // Only signed bytes are interpreted from here on; the field sequence enforces canonical form.
        val c = JsonCursor(signed)
        c.expect('{')
        c.expectKey("bundle")
        c.expect('{')
        c.expectKey("path"); val path = c.readString(); c.expect(',')
        c.expectKey("sha256"); val bundleSha = c.readString(); c.expect(',')
        c.expectKey("signature"); val bundleSignature = readSignature(c); c.expect(',')
        c.expectKey("size"); val size = c.readInteger()
        c.expect('}'); c.expect(',')
        c.expectKey("expires_at"); val expiresAt = c.readString(); c.expect(',')
        c.expectKey("feed_type"); val manifestFeed = c.readString(); c.expect(',')
        c.expectKey("generated_at"); val generatedAt = c.readString(); c.expect(',')
        c.expectKey("rule_count"); val ruleCount = c.readInteger(); c.expect(',')
        c.expectKey("rules_sha256"); val rulesSha = c.readString(); c.expect(',')
        c.expectKey("schema_version"); val schema = c.readInteger(); c.expect(',')
        c.expectKey("type"); val type = c.readString(); c.expect(',')
        c.expectKey("version"); val version = c.readInteger()
        c.expect('}')
        c.expectEnd()

        if (schema != SignedFeedFormat.SCHEMA_VERSION || type != "safeer-feed-manifest") {
            throw FeedVerificationException("unsupported manifest schema")
        }
        if (manifestFeed != feedType) throw FeedVerificationException("manifest is for a different feed")
        if (version < 1) throw FeedVerificationException("invalid version")
        if (path != "$feedType-$version.json") throw FeedVerificationException("unexpected bundle path")
        if (size < 2 || size > SignedFeedFormat.MAX_BUNDLE_BYTES) throw FeedVerificationException("invalid bundle size")
        if (!FeedText.isHex64(bundleSha)) throw FeedVerificationException("invalid bundle hash")
        if (!FeedText.isHex64(rulesSha)) throw FeedVerificationException("invalid rules hash")
        val generated = FeedText.epochSeconds(generatedAt)
        val expires = FeedText.epochSeconds(expiresAt)
        if (expires <= generated) throw FeedVerificationException("manifest expires before it was generated")
        if (checkExpiry && nowEpochSeconds >= expires) throw ExpiredFeedException("manifest has expired")
        if (version < installedVersion) {
            throw RollbackException("offered version $version is older than installed $installedVersion")
        }
        return FeedManifest(
            manifestFeed, version, generatedAt, expiresAt, expires, ruleCount, rulesSha,
            path, size.toInt(), bundleSha, bundleSignature,
        )
    }

    /** Verifies bundle bytes against a verified manifest and builds the lookup index. */
    fun verifyBundle(bundleBytes: ByteArray, manifest: FeedManifest, trustedKeys: Map<String, String>): VerifiedBundle {
        if (bundleBytes.size != manifest.bundleSize) throw FeedVerificationException("bundle size does not match the manifest")
        if (FeedText.hex(FeedText.sha256(bundleBytes)) != manifest.bundleSha256) {
            throw FeedVerificationException("bundle SHA-256 does not match the manifest")
        }
        if (!signatureValid(manifest.bundleSignature, trustedKeys, SignedFeedFormat.BUNDLE_CONTEXT, bundleBytes)) {
            throw FeedVerificationException("bundle signature is not valid")
        }

        val c = JsonCursor(bundleBytes)
        c.expect('{')
        c.expectKey("expires_at"); val expiresAt = c.readString(); c.expect(',')
        c.expectKey("feed_type"); val feedType = c.readString(); c.expect(',')
        c.expectKey("generated_at"); val generatedAt = c.readString(); c.expect(',')
        c.expectKey("rule_count"); val ruleCount = c.readInteger(); c.expect(',')
        if (feedType != manifest.feedType || expiresAt != manifest.expiresAt || generatedAt != manifest.generatedAt) {
            throw FeedVerificationException("bundle metadata does not match the manifest")
        }

        c.expectKey("rules")
        val allowedCategories = SignedFeedFormat.FEED_CATEGORIES[manifest.feedType] ?: emptySet()
        val rulesStart = c.pos
        val domains = HashMap<String, Int>()
        val hosts = HashMap<String, Int>()
        val urls = HashMap<String, Int>()
        var count = 0L
        var maxSource = -1L
        c.expect('[')
        if (!c.tryConsume(']')) {
            do {
                c.expect('[')
                val indicator = c.readString(); c.expect(',')
                val type = c.readString(); c.expect(',')
                val category = c.readString(); c.expect(',')
                val sourceIndex = c.readInteger()
                c.expect(']')
                val typeIndex = SignedFeedFormat.INDICATOR_TYPES.indexOf(type)
                val categoryIndex = SignedFeedFormat.CATEGORIES.indexOf(category)
                if (typeIndex < 0 || categoryIndex < 0 || category !in allowedCategories || sourceIndex >= SignedFeedFormat.MAX_SOURCES ||
                    !FeedText.validRule(indicator, type)
                ) {
                    throw FeedVerificationException("invalid rule ${indicator.take(120)}")
                }
                if (sourceIndex > maxSource) maxSource = sourceIndex
                val packed = (categoryIndex shl 16) or sourceIndex.toInt()
                val target = when (type) {
                    "domain" -> domains
                    "url" -> urls
                    else -> hosts
                }
                target.merge(indicator, packed) { old, new ->
                    val oldCategory = SignedFeedFormat.CATEGORIES[old ushr 16]
                    if (SignedFeedFormat.severity(category) > SignedFeedFormat.severity(oldCategory)) new else old
                }
                count++
            } while (c.tryConsume(','))
            c.expect(']')
        }
        val rulesEnd = c.pos
        c.expect(',')

        c.expectKey("schema_version"); val schema = c.readInteger(); c.expect(',')
        c.expectKey("sha256"); val rulesSha = c.readString(); c.expect(',')
        c.expectKey("sources")
        val sources = ArrayList<FeedSource>()
        c.expect('[')
        if (!c.tryConsume(']')) {
            do {
                c.expect('{')
                c.expectKey("id"); val id = c.readString(); c.expect(',')
                c.expectKey("license"); val license = c.readString(); c.expect(',')
                c.expectKey("name"); val name = c.readString(); c.expect(',')
                c.expectKey("url"); val url = c.readString()
                c.expect('}')
                sources.add(FeedSource(id, license, name, url))
                if (sources.size > SignedFeedFormat.MAX_SOURCES) throw FeedVerificationException("too many sources")
            } while (c.tryConsume(','))
            c.expect(']')
        }
        c.expect(',')
        c.expectKey("version"); val version = c.readInteger()
        c.expect('}')
        c.expectEnd()

        if (schema != SignedFeedFormat.SCHEMA_VERSION) throw FeedVerificationException("unexpected bundle schema")
        if (version != manifest.version) throw FeedVerificationException("bundle metadata does not match the manifest")
        if (sources.isEmpty() || maxSource >= sources.size) throw FeedVerificationException("invalid rules or sources")
        if (ruleCount != count || manifest.ruleCount != count) throw FeedVerificationException("rule count mismatch")
        val computed = FeedText.hex(FeedText.sha256(bundleBytes, rulesStart, rulesEnd - rulesStart))
        if (rulesSha != manifest.rulesSha256 || computed != rulesSha) throw FeedVerificationException("rules SHA-256 mismatch")
        return VerifiedBundle(manifest, sources, count.toInt(), ThreatIndex(domains, hosts, urls, sources))
    }
}

// ------------------------------------------------------------------------------------------------
// Storage and updates
// ------------------------------------------------------------------------------------------------

fun interface FeedFetcher {
    /** Returns the body of a 200 response, failing when it would exceed [limit] bytes. */
    @Throws(IOException::class)
    fun fetch(url: String, limit: Int): ByteArray
}

/** Anonymous HTTPS GET: no cookies, no caches, no redirects, constant User-Agent, hard limits. */
class HttpsFeedFetcher(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val totalTimeoutMs: Long = 180_000,
) : FeedFetcher {
    override fun fetch(url: String, limit: Int): ByteArray {
        if (!url.startsWith("https://")) throw IOException("only HTTPS is allowed")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("User-Agent", "Safeer")
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status != 200) throw IOException("HTTP status $status")
            val declared = connection.getHeaderField("Content-Length")?.toLongOrNull()
            if (declared != null && declared > limit) throw IOException("response too large")
            val deadline = System.currentTimeMillis() + totalTimeoutMs
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream(minOf(limit, 1 shl 20))
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (out.size() + read > limit) throw IOException("response too large")
                    out.write(buffer, 0, read)
                    if (System.currentTimeMillis() > deadline) throw IOException("download took too long")
                }
                return out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** Keeps the newest verified bundle of one feed type on disk and in memory. Thread-safe. */
class SignedFeedStore(
    val directory: File,
    val feedType: String,
    trustedKeys: Map<String, String>,
    baseUrls: List<String>,
    private val fetcher: FeedFetcher = HttpsFeedFetcher(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val trustedKeys: Map<String, String> = trustedKeys.filterKeys { FeedText.isKeyId(it) }
    private val baseUrls = baseUrls.map { it.trimEnd('/') }
    private val updateLock = Any()

    @Volatile var bundle: VerifiedBundle? = null
        private set
    @Volatile var lastError: String = ""
        private set

    init {
        require(feedType in SignedFeedFormat.FEED_TYPES) { "unknown feed type" }
    }

    val enabled: Boolean get() = trustedKeys.isNotEmpty()

    private fun manifestFile(version: Long) = File(directory, "$feedType-$version-manifest.json")
    private fun bundleFile(version: Long) = File(directory, "$feedType-$version-bundle.json")
    /** Rules of the verified bundle as a mapped sorted table (SortedStringTable): opened at start without the heap. */
    private fun indexFile(version: Long) = File(directory, "$feedType-$version-index.bin")
    private val stateFile get() = File(directory, "$feedType-state.json")

    fun installedVersion(): Long {
        return try {
            val bytes = stateFile.readBytes()
            if (bytes.size > 128) return 0
            val cursor = JsonCursor(bytes, allowWhitespace = true)
            val state = cursor.readValue()
            cursor.expectEnd()
            ((state as? Map<*, *>)?.get("version") as? Long) ?: 0
        } catch (e: IOException) {
            0
        } catch (e: FeedVerificationException) {
            0
        }
    }

    /** Loads and re-verifies the stored bundle. An expired bundle is still used (better than none). */
    fun load(): Boolean = synchronized(updateLock) {
        if (!enabled) return false
        val version = installedVersion()
        if (version < 1) {
            lastError = "no stored feed"
            return false
        }
        try {
            val manifest = SignedFeedVerifier.verifyManifest(
                manifestFile(version).readBytes(), trustedKeys, feedType, checkExpiry = false,
            )
            if (manifest.version != version) throw FeedVerificationException("stored manifest does not match the recorded version")
            if (bundleFile(version).length() != manifest.bundleSize.toLong()) throw FeedVerificationException("stored bundle size mismatch")
            val verified = SignedFeedVerifier.verifyBundle(bundleFile(version).readBytes(), manifest, trustedKeys)
            bundle = mapped(verified, manifestFile(version).readBytes(), version) ?: verified
            true
        } catch (e: Exception) {
            lastError = "stored feed unavailable: ${e.javaClass.simpleName}: ${e.message}"
            false
        } catch (e: OutOfMemoryError) {
            lastError = "stored feed unavailable: out of memory"
            false
        }
    }

    /** Fetches and installs a newer bundle. Returns true when a new version was installed. */
    fun update(): Boolean = synchronized(updateLock) {
        if (!enabled) return false
        val installed = installedVersion()
        val errors = ArrayList<String>()
        for (base in baseUrls) {
            try {
                val manifestBytes = fetcher.fetch("$base/v1/$feedType/latest.json", SignedFeedFormat.MAX_MANIFEST_BYTES)
                val manifest = SignedFeedVerifier.verifyManifest(
                    manifestBytes, trustedKeys, feedType, installedVersion = installed, nowEpochSeconds = clock(),
                )
                if (manifest.version == installed && bundle != null) {
                    lastError = ""
                    return false
                }
                val bundleBytes = fetcher.fetch("$base/v1/$feedType/${manifest.bundlePath}", manifest.bundleSize)
                val verified = SignedFeedVerifier.verifyBundle(bundleBytes, manifest, trustedKeys)
                install(manifest, manifestBytes, bundleBytes)
                bundle = mapped(verified, manifestBytes, manifest.version) ?: verified
                lastError = ""
                return true
            } catch (e: Exception) {
                errors.add("$base: ${e.javaClass.simpleName}: ${e.message}")
            } catch (e: OutOfMemoryError) {
                errors.add("$base: out of memory")
            }
        }
        lastError = errors.joinToString("; ")
        false
    }

    private fun install(manifest: FeedManifest, manifestBytes: ByteArray, bundleBytes: ByteArray) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create ${directory.name}")
        atomicWrite(bundleFile(manifest.version), bundleBytes)
        atomicWrite(manifestFile(manifest.version), manifestBytes)
        // The state file is the commit point: until it is replaced, load() keeps using the old files.
        atomicWrite(stateFile, "{\"version\":${manifest.version}}".toByteArray(Charsets.US_ASCII))
        directory.listFiles()?.forEach { file ->
            val parts = file.name.split('-')
            if (file.name.startsWith("$feedType-") && parts.size == 3 &&
                (parts[2] == "manifest.json" || parts[2] == "bundle.json" || parts[2] == "index.bin" ||
                    parts[2].startsWith("manifest.json.") || parts[2].startsWith("bundle.json.") || parts[2].startsWith("index.bin."))
            ) {
                val version = parts[1].toLongOrNull()
                if (version != null && version < manifest.version) file.delete()
            }
        }
    }

    private fun atomicWrite(target: File, data: ByteArray) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temp).use { out ->
            out.write(data)
            out.flush()
            out.fd.sync()
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Writes the rules of a just verified bundle to the index file and returns the same bundle served from the mapped file
     * (the hash maps become garbage), or null when the index cannot be written (the caller keeps the heap index).
     */
    private fun mapped(verified: VerifiedBundle, manifestBytes: ByteArray, version: Long): VerifiedBundle? {
        val maps = verified.index.rules as? MapRuleLookup ?: return null
        return try {
            val key = FeedText.sha256(manifestBytes)
            val file = indexFile(version)
            ThreatIndex.writeMapped(file, key, maps, verified.sources)
            val (index, sources) = ThreatIndex.openMapped(file, key) ?: return null
            VerifiedBundle(verified.manifest, sources, verified.ruleCount, index)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /**
     * Fast start (main thread, milliseconds): opens the mapped index of the installed version when it was written for the
     * exact stored manifest bytes. Reads the state file, the manifest (at most 64 KiB) and the index header; no bundle,
     * no hash maps. The Ed25519 signature and the bundle SHA-256 are checked again by [recheck] in the background.
     */
    fun loadFast(): Boolean = synchronized(updateLock) {
        if (!enabled) return false
        if (bundle != null) return true
        try {
            val version = installedVersion()
            if (version < 1) return false
            val file = manifestFile(version)
            if (!file.isFile || file.length() > SignedFeedFormat.MAX_MANIFEST_BYTES) return false
            val manifestBytes = file.readBytes()
            val (index, sources) = ThreatIndex.openMapped(indexFile(version), FeedText.sha256(manifestBytes)) ?: return false
            val manifest = SignedFeedVerifier.readManifest(manifestBytes, trustedKeys, feedType, 0, 0, checkExpiry = false, checkSignature = false)
            if (manifest.version != version || manifest.ruleCount.toInt().toLong() != manifest.ruleCount ||
                index.size.toLong() > manifest.ruleCount) return false
            bundle = VerifiedBundle(manifest, sources, manifest.ruleCount.toInt(), index)
            true
        } catch (e: Exception) {
            false
        } catch (e: OutOfMemoryError) {
            false
        }
    }

    /**
     * Background check of a bundle opened by [loadFast]: manifest signature (trusted key), stored bundle size and SHA-256
     * (streamed, not read into memory) and the CRC32 of the index file. These bind the index to a verified bundle exactly
     * as the full load did. When anything fails, the fast bundle is dropped and [load] verifies and rebuilds from disk.
     */
    fun recheck(): Boolean = synchronized(updateLock) {
        val current = bundle ?: return load()
        val table = (current.index.rules as? TableRuleLookup)?.table ?: return true
        val version = current.manifest.version
        val ok = try {
            val manifestBytes = manifestFile(version).readBytes()
            val manifest = SignedFeedVerifier.verifyManifest(manifestBytes, trustedKeys, feedType, checkExpiry = false)
            val bundlePath = bundleFile(version)
            manifest == current.manifest && bundlePath.length() == manifest.bundleSize.toLong() &&
                streamSha256(bundlePath) == manifest.bundleSha256 && table.bodyIntact()
        } catch (e: Exception) {
            false
        }
        if (ok) return true
        lastError = "stored index did not pass the check; rebuilding"
        bundle = null
        try { indexFile(version).delete() } catch (e: Exception) { /* load() writes it again */ }
        load()
    }

    private fun streamSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        java.io.FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val r = input.read(buffer)
                if (r < 0) break
                digest.update(buffer, 0, r)
            }
        }
        return FeedText.hex(digest.digest())
    }

    fun matchUrl(url: String?): FeedMatch? = bundle?.index?.matchUrl(url)
    fun matchHost(host: String?): FeedMatch? = bundle?.index?.matchHost(host)
    fun matchUrlRule(url: String?): FeedMatch? = bundle?.index?.matchUrlRule(url)
    fun matchHostWhere(host: String?, sme: (String) -> Boolean): FeedMatch? = bundle?.index?.matchHostWhere(host, sme)
}

/**
 * Background updates for one store: loads the stored bundle, checks for a newer one after a short
 * delay and then every [intervalSeconds] (retry after [retrySeconds] on failure), with jitter.
 */
class SignedFeedService(
    val store: SignedFeedStore,
    private val intervalSeconds: Long = 6 * 3600L,
    private val retrySeconds: Long = 3600L,
    private val firstDelaySeconds: Long = 12L,
    /** Called once after the first load of the stored bundle has finished (also when there is none). */
    private val onFirstLoad: (() -> Unit)? = null,
    private val onChange: ((SignedFeedStore) -> Unit)? = null,
) {
    private var executor: ScheduledExecutorService? = null
    private var scheduled: ScheduledFuture<*>? = null
    private val random = java.security.SecureRandom()

    @Volatile var lastCheckEpochSeconds: Long = 0
        private set

    @Synchronized
    fun start(): Boolean {
        if (!store.enabled || executor != null) return false
        val service = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "safeer-signed-feed").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
        executor = service
        service.execute {
            try {
                // After SignedFeedStore.loadFast() the rules are already in use; here only the background check.
                if (store.bundle != null) store.recheck() else if (store.load()) notifyChange()
            } catch (e: Exception) {
                /* the stored bundle is unusable; the update below replaces it */
            } finally {
                try { onFirstLoad?.invoke() } catch (e: Exception) { /* listeners must not stop updates */ }
            }
        }
        schedule(firstDelaySeconds)
        return true
    }

    @Synchronized
    fun stop() {
        scheduled?.cancel(false)
        executor?.shutdownNow()
        executor = null
        scheduled = null
    }

    /** Requests an immediate check (for example from a "update lists" button). */
    @Synchronized
    fun requestUpdate(callback: ((installed: Boolean) -> Unit)? = null): Boolean {
        val service = executor ?: return false
        service.execute {
            val installed = runUpdate()
            callback?.invoke(installed)
        }
        return true
    }

    private fun runUpdate(): Boolean {
        lastCheckEpochSeconds = System.currentTimeMillis() / 1000
        val installed = try { store.update() } catch (e: Exception) { false }
        if (installed) notifyChange()
        return installed
    }

    private fun notifyChange() {
        try { onChange?.invoke(store) } catch (e: Exception) { /* listeners must not stop updates */ }
    }

    @Synchronized
    private fun schedule(delaySeconds: Long) {
        val service = executor ?: return
        val jitter = 0.9 + random.nextDouble() * 0.2
        scheduled = service.schedule({
            runUpdate()
            schedule(if (store.lastError.isEmpty()) intervalSeconds else retrySeconds)
        }, maxOf(0L, (delaySeconds * 1000 * jitter).toLong()), TimeUnit.MILLISECONDS)
    }
}
