package si.safeer.tv.os

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.remove_flags_t
import org.libtorrent4j.swig.session_handle
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Magnet povezave (BitTorrent) na Androidu: prejem, pošiljanje in predvajanje že med prenosom.
 *
 * Motor je libtorrent (libtorrent4j, MIT). Predvajalnik (ExoPlayer) dobi lokalni naslov
 * `http://127.0.0.1:<vrata>/t/<skrivnost>`: vsaka zahteva z `Range` postavi roke (deadline) kosom okoli mesta
 * predvajanja, zato se film začne v nekaj sekundah in previjanje dela, prenaša pa se samo, kar se gleda.
 *
 * Varnost in poštenost (enako kot core/os_torrent.py na računalniku):
 * - predvajamo samo glasbo in video; programov in skript (.apk, .exe, .lnk ...) nikoli ne prenesemo;
 * - brez UPnP/NAT-PMP (ne odpiramo vrat na usmerjevalniku);
 * - oddajanje (upload) je med prenosom omejeno, po koncu se ustavi, razen če uporabnik izbere »Deli naprej«;
 * - malo povezav in omejen pomnilnik: TV in telefon ostaneta odzivna.
 */
object MagnetMotor {
    private const val TAG = "SafeerMagnet"
    private const val POVEZAV = 50
    private const val ODDAJA_BPS = 256 * 1024
    private const val OKNO_BAJTOV = 16L * 1024 * 1024       // koliko naprej pripravimo med tokom
    private const val CAKAJ_KOS_MS = 90_000L
    private const val PREFS = "safeer_magnet"
    val SLEDILNIKI = listOf("udp://tracker.opentrackr.org:1337/announce", "udp://open.demonii.com:1337/announce",
        "udp://tracker.torrent.eu.org:451/announce")

    private val VIDEO = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "m2ts", "mpg", "mpeg", "wmv", "flv", "3gp", "ogv")
    private val ZVOK = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "alac", "ape")
    private val PODNAPISI = setOf("srt", "vtt", "ass", "ssa", "sub")
    private val SLIKE = setOf("jpg", "jpeg", "png", "webp", "gif")
    private val NEVARNE = setOf("exe", "msi", "scr", "com", "bat", "cmd", "ps1", "vbs", "vbe", "js", "jse", "wsf", "hta",
        "lnk", "pif", "cpl", "jar", "apk", "xapk", "dll", "sys", "reg", "sh", "run", "bin", "appimage", "desktop", "deb",
        "rpm", "dmg", "pkg", "iso", "img", "url", "chm", "docm", "xlsm", "pptm")

    data class Datoteka(val i: Int, val ime: String, val velikost: Long, val vrsta: String) {
        val predvajljiva get() = vrsta == "video" || vrsta == "audio"
        val privzetoIzbrana get() = predvajljiva || vrsta == "podnapisi"
    }

    data class Opis(val hash: String, val ime: String, val uri: String, val datoteke: List<Datoteka>) {
        val sumljiv get() = datoteke.any { it.vrsta == "nevarno" }
    }

    // ------------------------------------------------------------------ magnet

    private val BTIH = Regex("urn:btih:([0-9a-fA-F]{40}|[A-Za-z2-7]{32})$")

    /** Hash (40 hex) iz veljavne magnet povezave ali null. */
    fun hash(uri: String): String? {
        val u = uri.trim()
        if (!u.startsWith("magnet:?", ignoreCase = true) || u.length > 8192 || u.any { it == '\n' || it == '\r' || it == ' ' || it == '\t' }) return null
        for (del in u.substring(8).split('&')) {
            val (k, v) = del.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (k != "xt") continue
            val m = BTIH.find(URLDecoder.decode(v, "UTF-8")) ?: continue
            val h = m.groupValues[1]
            return if (h.length == 40) h.lowercase() else base32vHex(h)
        }
        return null
    }

    private fun base32vHex(b32: String): String {
        val abeceda = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var biti = 0; var vrednost = 0
        val izhod = StringBuilder()
        for (c in b32.uppercase()) {
            vrednost = (vrednost shl 5) or abeceda.indexOf(c)
            biti += 5
            if (biti >= 8) { biti -= 8; izhod.append("%02x".format((vrednost shr biti) and 0xff)) }
        }
        return izhod.toString()
    }

    /** Magnet brez sledilnikov dobi nekaj zanesljivih (hitrejše iskanje); ostalih ne spreminjamo. */
    /**
     * Očiščena povezava za libtorrent: xt, dn in samo javni sledilniki (brez x.pe/ws in brez naslovov v
     * domačem omrežju - tuja povezava ne sme usmerjati zahtev na usmerjevalnik). Brez sledilnikov dodamo znane.
     */
    fun zSledilniki(uri: String): String {
        val h = hash(uri) ?: return uri
        val deli = uri.substringAfter('?', "").split('&').mapNotNull {
            val k = it.substringBefore('=', ""); if (k.isEmpty()) null else k to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val ime = deli.firstOrNull { it.first == "dn" }?.second.orEmpty().take(200)
        val sledilniki = deli.filter { it.first == "tr" && javenSledilnik(it.second) }.map { it.second }.take(20)
            .ifEmpty { SLEDILNIKI }
        fun kod(x: String) = java.net.URLEncoder.encode(x, "UTF-8")
        return "magnet:?xt=urn:btih:$h" + (if (ime.isNotEmpty()) "&dn=" + kod(ime) else "") +
            sledilniki.joinToString("") { "&tr=" + kod(it) }
    }

    fun javenSledilnik(t: String): Boolean {
        if (!(t.startsWith("udp://") || t.startsWith("http://") || t.startsWith("https://"))) return false
        val gostitelj = try { android.net.Uri.parse(t).host?.lowercase()?.trimEnd('.') } catch (_: Throwable) { null } ?: return false
        if (gostitelj.isEmpty() || gostitelj == "localhost" || '.' !in gostitelj && ':' !in gostitelj) return false
        if (listOf(".localhost", ".local", ".lan", ".home", ".internal", ".home.arpa").any { gostitelj.endsWith(it) }) return false
        val literal = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(gostitelj) || ':' in gostitelj
        if (!literal) return true
        return try {
            val a = java.net.InetAddress.getByName(gostitelj.trim('[', ']'))
            val ula = a is java.net.Inet6Address && (a.address[0].toInt() and 0xFE) == 0xFC
            !(a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress || ula ||
                (a.address.size == 4 && (a.address[0].toInt() and 0xFF) == 100 && (a.address[1].toInt() and 0xC0) == 64))
        } catch (_: Throwable) { false }
    }

    fun vrsta(ime: String, izvrsljiva: Boolean = false): String {
        if (izvrsljiva) return "nevarno"
        val k = ime.substringAfterLast('.', "").lowercase()
        return when (k) {
            in VIDEO -> "video"; in ZVOK -> "audio"; in PODNAPISI -> "podnapisi"; in SLIKE -> "slika"
            in NEVARNE -> "nevarno"; else -> "drugo"
        }
    }

    // ------------------------------------------------------------------ seja

    @Volatile private var seja: SessionManager? = null
    private val opisi = ConcurrentHashMap<String, TorrentInfo>()
    private lateinit var app: Context

    fun mapa(c: Context): File = File(c.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: c.filesDir, "Safeer").apply { mkdirs() }

    @Synchronized
    fun seja(c: Context): SessionManager {
        seja?.let { return it }
        app = c.applicationContext
        val nastavitve = SettingsPack()
            .connectionsLimit(POVEZAV).activeDownloads(3).activeSeeds(2)
            .uploadRateLimit(ODDAJA_BPS)
        nastavitve.setEnableDht(true)
        nastavitve.setMaxMetadataSize(16 * 1024 * 1024)
        // Brez odpiranja vrat na usmerjevalniku.
        nastavitve.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
        nastavitve.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
        val s = SessionManager(false)
        // Preprosto branje/pisanje namesto preslikave v pomnilnik (mmap): mmap nad Androidovo FUSE shrambo
        // (Android/data) je 29. 9. 2026 na testnem telefonu sesul sistemsko storitev shrambe (MediaProvider).
        s.start(SessionParams(nastavitve).apply { setPosixDiskIO() })
        seja = s
        obnovi(c)
        Thread({ nadzor() }, "safeer-magnet-nadzor").apply { isDaemon = true; start() }
        return s
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Prenosi, ki jih je uporabnik začel (magnet + izbrane datoteke), prežijo ponovni zagon aplikacije. */
    private fun zapomni(c: Context, hash: String, uri: String, izbrane: Set<Int>, lastna: String = "") {
        val p = prefs(c)
        val vsi = JSONObject(p.getString("prenosi", "{}") ?: "{}")
        vsi.put(hash, JSONObject().put("uri", uri).put("izbrane", JSONArray(izbrane.sorted())).put("lastna", lastna))
        p.edit().putString("prenosi", vsi.toString()).apply()
    }

    private fun pozabi(c: Context, hash: String) {
        val p = prefs(c)
        val vsi = JSONObject(p.getString("prenosi", "{}") ?: "{}")
        vsi.remove(hash)
        p.edit().putString("prenosi", vsi.toString()).apply()
    }

    private fun obnovi(c: Context) {
        val vsi = JSONObject(prefs(c).getString("prenosi", "{}") ?: "{}")
        for (h in vsi.keys()) {
            val z = vsi.optJSONObject(h) ?: continue
            Thread({
                try {
                    val lastna = z.optString("lastna")
                    if (lastna.isNotBlank()) deli(c, File(lastna)) else {
                        val izbrane = (0 until (z.optJSONArray("izbrane")?.length() ?: 0)).map { z.getJSONArray("izbrane").getInt(it) }
                        dodaj(c, z.optString("uri"), izbrane, izbrane.toSet())  // shranjene je uporabnik že potrdil
                    }
                } catch (e: Throwable) { Log.i(TAG, "Obnova $h: ${e.message}") }
            }, "safeer-magnet-obnova").apply { isDaemon = true; start() }
        }
    }

    fun deliNaprej(c: Context, hash: String): Boolean = prefs(c).getStringSet("deli_naprej", emptySet())!!.contains(hash)

    fun nastaviDeliNaprej(c: Context, hash: String, vklop: Boolean) {
        val m = prefs(c).getStringSet("deli_naprej", emptySet())!!.toMutableSet()
        if (vklop) m.add(hash) else m.remove(hash)
        prefs(c).edit().putStringSet("deli_naprej", m).apply()
        if (vklop) rocaj(c, hash)?.resume()
    }

    fun rocaj(c: Context, hash: String): TorrentHandle? =
        if (!Regex("^[0-9a-fA-F]{40}$").matches(hash)) null else seja(c).find(Sha1Hash.parseHex(hash.lowercase()))

    /**
     * Ročaji vseh torrentov, vsak svoja kopija. Elementi `get_torrents()` so le kazalci v začasni C++ vektor:
     * ko ga zbiralnik smeti sprosti, klic na tak ročaj sesuje proces (SIGSEGV v have_piece, 29. 9. 2026).
     */
    private fun rocaji(s: SessionManager): List<TorrentHandle> {
        val v = s.swig().get_torrents()
        val hashi = ArrayList<Sha1Hash>()
        try {
            for (k in 0 until v.size) { val h = v[k]; if (h.is_valid()) hashi += Sha1Hash(h.info_hash()) }
        } finally { java.lang.ref.Reference.reachabilityFence(v) }
        return hashi.mapNotNull { s.find(it) }
    }

    // ------------------------------------------------------------------ uporaba

    /** Metapodatki magneta (ime in datoteke), brez prenosa vsebine. Blokira do 60 s: klic iz ozadja. */
    fun preberi(c: Context, uri: String): Opis {
        val h = hash(uri) ?: throw IllegalArgumentException("ni_magnet")
        val ti = opisi[h] ?: run {
            val dir = File(c.cacheDir, "magnet").apply { mkdirs() }
            val bajti = seja(c).fetchMagnet(zSledilniki(uri.trim()), 60, dir) ?: throw IllegalStateException("ni_metapodatkov")
            TorrentInfo.bdecode(bajti).also { opisi[h] = it }
        }
        val fs = ti.files()
        val datoteke = (0 until fs.numFiles()).filter { !fs.padFileAt(it) }.map {
            val ime = fs.filePath(it)
            val izv = try { fs.fileFlags(it).and_(org.libtorrent4j.swig.file_storage.flag_executable).non_zero() } catch (_: Throwable) { false }
            Datoteka(it, ime, fs.fileSize(it), vrsta(ime, izv))
        }
        return Opis(h, ti.name(), uri.trim(), datoteke)
    }

    /**
     * Začne prenos izbranih datotek; če torrent že teče, jih doda. Vrne hash. Datoteko, ki je videti kot
     * program, prenesemo samo, če jo je uporabnik izrecno potrdil ([potrjeneNevarne]) - prepoznava je
     * samodejna in se lahko zmoti, odločitev je njegova. Predvajamo je nikoli.
     */
    fun dodaj(c: Context, uri: String, izbrane: List<Int>, potrjeneNevarne: Set<Int> = emptySet()): String {
        val opis = preberi(c, uri)
        val dovoljene = opis.datoteke.filter { it.vrsta != "nevarno" || it.i in potrjeneNevarne }.map { it.i }.toSet()
        val obstojeci = rocaj(c, opis.hash)
        val zdaj = (obstojeci?.filePriorities()?.withIndex()?.filter { it.value != Priority.IGNORE }?.map { it.index }?.toSet() ?: emptySet())
        val koncne = (izbrane.toSet() intersect dovoljene) + zdaj
        if (koncne.isEmpty()) throw IllegalArgumentException("ni_izbranih")
        val ti = opisi[opis.hash]!!
        val prednosti = Array(ti.numFiles()) { if (it in koncne) Priority.DEFAULT else Priority.IGNORE }
        if (obstojeci != null) {
            obstojeci.prioritizeFiles(prednosti)
            obstojeci.resume()
        } else {
            seja(c).download(ti, mapa(c), null, prednosti, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        }
        zapomni(c, opis.hash, opis.uri, koncne)
        return opis.hash
    }

    fun seznam(c: Context): JSONArray {
        val izid = JSONArray()
        if (seja == null && JSONObject(prefs(c).getString("prenosi", "{}") ?: "{}").length() == 0) return izid
        for (th in rocaji(seja(c))) {
            if (!th.isValid) continue
            val st = th.status()
            val hash = th.infoHash().toHex()
            val ti = th.torrentFile()
            val fs = ti?.files()
            val prednosti = th.filePriorities()
            val datoteke = JSONArray()
            if (fs != null) for (i in 0 until fs.numFiles()) {
                if (fs.padFileAt(i)) continue
                val ime = fs.filePath(i)
                datoteke.put(JSONObject().put("i", i).put("ime", ime).put("vrsta", vrsta(ime))
                    .put("vkljucena", prednosti.getOrNull(i) != Priority.IGNORE))
            }
            val zapis = JSONObject(prefs(c).getString("prenosi", "{}") ?: "{}").optJSONObject(hash)
            val lastna = zapis?.optString("lastna").orEmpty()
            // Takoj po zagonu (obnova) metapodatkov še ni: ime iz shranjene povezave (dn), sicer prazna vrstica.
            val dn = zapis?.optString("uri").orEmpty().substringAfter('?', "").split('&')
                .firstOrNull { it.startsWith("dn=") }?.let { runCatching { java.net.URLDecoder.decode(it.substring(3), "UTF-8") }.getOrNull() }.orEmpty()
            val ime = listOf(ti?.name().orEmpty(), st.name().orEmpty(), dn).firstOrNull { it.isNotBlank() } ?: hash.take(12)
            izid.put(JSONObject().put("hash", hash).put("ime", ime).put("preneseno", st.totalWantedDone())
                .put("skupaj", st.totalWanted()).put("hitrost", st.downloadPayloadRate()).put("oddaja", st.uploadPayloadRate())
                .put("povezave", st.numPeers()).put("koncano", st.isFinished).put("premor", th.getFlags().and_(TorrentFlags.PAUSED).non_zero())
                .put("deli_naprej", deliNaprej(c, hash)).put("lastna", lastna.isNotBlank()).put("datoteke", datoteke))
        }
        return izid
    }

    fun odstrani(c: Context, hash: String, zDatotekami: Boolean) {
        val th = rocaj(c, hash) ?: return
        val lastna = JSONObject(prefs(c).getString("prenosi", "{}") ?: "{}").optJSONObject(hash)?.optString("lastna").orEmpty()
        // Uporabnikovih izvirnikov, ki jih deli, nikoli ne brišemo (samo kopijo v naši mapi).
        seja(c).remove(th, if (zDatotekami && lastna.isBlank()) session_handle.delete_files else remove_flags_t())
        if (lastna.isNotBlank()) File(lastna).delete()
        pozabi(c, hash)
        nastaviDeliNaprej(c, hash, false)
    }

    /** Iz uporabnikove datoteke (kopija v naši mapi) naredi torrent, ga začne oddajati in vrne magnet povezavo. */
    fun deli(c: Context, datoteka: File): String {
        val ti = TorrentInfo.bdecode(TorrentBuilder().path(datoteka).creator("Safeer").generate().entry().bencode())
        seja(c).download(ti, datoteka.parentFile, null, null, null, TorrentFlags.SEED_MODE)
        val hash = ti.infoHash().toHex()
        nastaviDeliNaprej(c, hash, true)
        val uri = zSledilniki("magnet:?xt=urn:btih:$hash&dn=" + java.net.URLEncoder.encode(ti.name(), "UTF-8"))
        zapomni(c, hash, uri, setOf(0), lastna = datoteka.absolutePath)
        return uri
    }

    fun magnet(c: Context, hash: String): String? = rocaj(c, hash)?.makeMagnetUri()?.let { zSledilniki(it) }

    private fun nadzor() {
        while (true) {
            Thread.sleep(10_000)
            try {
                val s = seja ?: continue
                for (th in rocaji(s)) {
                    if (!th.isValid) continue
                    // Po koncu prenosa ne oddajamo drugim, razen če uporabnik to izrecno izbere.
                    if (th.status().isFinished && !deliNaprej(app, th.infoHash().toHex()) &&
                        !th.getFlags().and_(TorrentFlags.PAUSED).non_zero()) th.pause()
                }
            } catch (e: Throwable) { Log.i(TAG, "Nadzor: ${e.message}") }
        }
    }

    // ------------------------------------------------------------------ lokalni tok za predvajalnik

    /** Skrivnosti lokalnega toka po vrsti nastanka: ob preveč jih odstranimo najstarejšo, ne naključne. */
    private val tokovi: MutableMap<String, Pair<String, Int>> = java.util.Collections.synchronizedMap(LinkedHashMap())
    @Volatile private var streznik: ServerSocket? = null
    private val nakljucje = SecureRandom()

    /** Lokalni naslov za predvajalnik: datoteka [i] torrenta [hash], predvaja se že med prenosom. */
    /** Podnapisi iz istega torrenta, ki sodijo k videu [i] (ista mapa ali podmapa Subs). */
    fun podnapisiZa(c: Context, uri: String, i: Int): List<Datoteka> {
        val opis = preberi(c, uri)
        val video = opis.datoteke.firstOrNull { it.i == i } ?: return emptyList()
        val mapa = video.ime.substringBeforeLast('/', "")
        val predpona = if (mapa.isEmpty()) "" else "$mapa/"
        val vMapi = opis.datoteke.filter { it.ime.startsWith(predpona) }
        val relativno = vMapi.associateBy { it.ime.removePrefix(predpona) }
        val videov = vMapi.count { it.vrsta == "video" && !it.ime.removePrefix(predpona).contains('/') }
        // Prevelika "podnapisna" datoteka ni podnapis (8 MB kot na računalniku): ne prenašamo je.
        return Podnapisi.ujemajoci(video.ime, relativno.keys.toList(), videov == 1).mapNotNull { relativno[it] }
            .filter { it.velikost <= 8L * 1024 * 1024 }.take(24)
    }

    fun tok(c: Context, hash: String, i: Int): String {
        val th = rocaj(c, hash) ?: throw IllegalStateException("ni_torrenta")
        val ti = th.torrentFile() ?: throw IllegalStateException("ni_metapodatkov")
        if (i !in 0 until ti.numFiles() || vrsta(ti.files().filePath(i)) !in setOf("video", "audio", "podnapisi")) throw IllegalArgumentException("ni_predvajljivo")
        if (th.filePriority(i) == Priority.IGNORE) th.filePriority(i, Priority.DEFAULT)
        th.resume()
        val s = zazeniStreznik()
        val b = ByteArray(16).also { nakljucje.nextBytes(it) }
        val skrivnost = b.joinToString("") { "%02x".format(it) }
        tokovi[skrivnost] = hash to i
        synchronized(tokovi) { while (tokovi.size > 256) tokovi.keys.firstOrNull()?.let { tokovi.remove(it) } }
        return "http://127.0.0.1:${s.localPort}/t/$skrivnost"
    }

    @Synchronized
    private fun zazeniStreznik(): ServerSocket {
        streznik?.takeIf { !it.isClosed }?.let { return it }
        val s = ServerSocket(0, 16, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        streznik = s
        Thread({
            while (!s.isClosed) {
                val k = try { s.accept() } catch (_: Throwable) { break }
                Thread({ postrezi(k) }, "safeer-magnet-tok").apply { isDaemon = true; start() }
            }
        }, "safeer-magnet-streznik").apply { isDaemon = true; start() }
        return s
    }

    private fun postrezi(s: Socket) {
        try {
            s.soTimeout = 30_000
            val vhod = s.getInputStream(); val izhod = s.getOutputStream()
            val prva = vrstica(vhod) ?: return
            var obseg = ""
            while (true) {
                val v = vrstica(vhod) ?: break
                if (v.isEmpty()) break
                if (v.lowercase().startsWith("range:")) obseg = v.substring(6).trim()
            }
            val deli = prva.split(" ")
            val metoda = deli.getOrNull(0).orEmpty()
            val cilj = tokovi[deli.getOrNull(1).orEmpty().removePrefix("/t/").substringBefore('?')]
            if ((metoda != "GET" && metoda != "HEAD") || cilj == null) { prazen(izhod, 404); return }
            val th = rocaj(app, cilj.first) ?: run { prazen(izhod, 404); return }
            val ti = th.torrentFile() ?: run { prazen(izhod, 404); return }
            val fs = ti.files()
            val velikost = fs.fileSize(cilj.second)
            var zacetek = 0L; var konec = velikost - 1; var delni = false
            if (obseg.startsWith("bytes=")) {
                val ab = obseg.substring(6).substringBefore(',').split("-", limit = 2)
                try {
                    if (ab[0].isEmpty()) zacetek = maxOf(0L, velikost - ab[1].toLong())
                    else { zacetek = ab[0].toLong(); if (ab.getOrElse(1) { "" }.isNotEmpty()) konec = minOf(velikost - 1, ab[1].toLong()) }
                    delni = true
                } catch (_: Throwable) { }
                if (delni && (zacetek > konec || zacetek >= velikost)) {
                    izhod.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$velikost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
                }
            }
            val dolzina = konec - zacetek + 1
            val ime = fs.filePath(cilj.second)
            val mime = when (ime.substringAfterLast('.').lowercase()) {
                "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"
                "m4a" -> "audio/mp4"; "ogg", "opus" -> "audio/ogg"; "wav" -> "audio/wav"; "avi" -> "video/x-msvideo"
                else -> if (vrsta(ime) == "video") "video/mp4" else "application/octet-stream"
            }
            izhod.write(((if (delni) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n") +
                "Content-Type: $mime\r\nAccept-Ranges: bytes\r\nContent-Length: $dolzina\r\n" +
                (if (delni) "Content-Range: bytes $zacetek-$konec/$velikost\r\n" else "") +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            if (metoda == "HEAD") return
            posljiObseg(th, ti, cilj.second, zacetek, dolzina, izhod)
        } catch (e: Throwable) {
            Log.i(TAG, "Tok: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        } finally {
            try { s.close() } catch (_: Throwable) { }
        }
    }

    /** Pošilja bajte datoteke; pred vsakim kosom počaka, da libtorrent prenese potrebne dele (roki kosom). */
    private fun posljiObseg(th: TorrentHandle, ti: TorrentInfo, i: Int, od: Long, dolzina: Long, izhod: OutputStream) {
        val fs = ti.files()
        val odmikDatoteke = fs.fileOffset(i)
        val dolzinaKosa = ti.pieceLength().toLong()
        val pot = File(th.savePath(), fs.filePath(i))
        val medpomnilnik = ByteArray(256 * 1024)
        var poz = od
        val konec = od + dolzina
        var datoteka: RandomAccessFile? = null
        try {
            while (poz < konec) {
                // Roki: najprej kos, ki ga potrebujemo zdaj, nato okno naprej (predvajanje brez zatikanja).
                val prvi = ((odmikDatoteke + poz) / dolzinaKosa).toInt()
                val zadnjiOkna = ((odmikDatoteke + minOf(konec, poz + OKNO_BAJTOV) - 1) / dolzinaKosa).toInt()
                for ((n, kos) in (prvi..minOf(zadnjiOkna, ti.numPieces() - 1)).withIndex()) {
                    if (!th.havePiece(kos)) th.setPieceDeadline(kos, 500 + n * 250)
                }
                val zacetek = System.currentTimeMillis()
                while (!th.havePiece(prvi)) {
                    if (System.currentTimeMillis() - zacetek > CAKAJ_KOS_MS) throw java.io.IOException("kos $prvi ne pride")
                    Thread.sleep(50)
                }
                // Do konca tega kosa smemo brati.
                val doKonca = minOf(konec, (prvi + 1L) * dolzinaKosa - odmikDatoteke)
                if (datoteka == null) datoteka = RandomAccessFile(pot, "r")
                datoteka.seek(poz)
                while (poz < doKonca) {
                    val n = datoteka.read(medpomnilnik, 0, minOf(medpomnilnik.size.toLong(), doKonca - poz).toInt())
                    if (n <= 0) throw java.io.IOException("konec datoteke")
                    izhod.write(medpomnilnik, 0, n)
                    poz += n
                }
            }
            izhod.flush()
        } finally {
            try { datoteka?.close() } catch (_: Throwable) { }
        }
    }

    private fun prazen(izhod: OutputStream, koda: Int) {
        izhod.write("HTTP/1.1 $koda X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }

    private fun vrstica(vhod: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = vhod.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(b.toChar())
        }
    }
}
