package si.safeer.tv.os

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.FastresumeRejectedAlert
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.swig.add_torrent_params
import org.libtorrent4j.swig.byte_vector
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.remove_flags_t
import org.libtorrent4j.swig.session_handle
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t
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
 *
 * Ponovni zagon ([ObnovaPravila]): ob vsakem torrentu shranimo njegov opis in podatke za nadaljevanje, zato motor ob
 * zagonu ne potrebuje omrežja in že prenesenih datotek ne bere znova. Torrent brez teh podatkov svoje datoteke
 * preveri postopoma, s premori, da naprava ostane odzivna.
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
    /** Nastavitve seje ostanejo dosegljive, dokler živi motor (domačih predmetov seje ne prepuščamo zbiralniku smeti). */
    private var nastavitveSeje: SettingsPack? = null
    private var parametriSeje: SessionParams? = null
    /** Hash ročaja -> ključ, pod katerim je torrent shranjen (hash iz magnet povezave). */
    private val kljuci = ConcurrentHashMap<String, String>()
    /** Kdaj je kdo nazadnje bral tok torrenta (čas od zagona naprave). */
    private val zadnjiTok = ConcurrentHashMap<String, Long>()

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
        varujZakljucevalnike()
        val s = SessionManager(false)
        s.addListener(poslusalec)
        // Preprosto branje/pisanje namesto preslikave v pomnilnik (mmap): mmap nad Androidovo FUSE shrambo
        // (Android/data) je 29. 9. 2026 na testnem telefonu sesul sistemsko storitev shrambe (MediaProvider).
        val parametri = SessionParams(nastavitve).apply { setPosixDiskIO() }
        nastavitveSeje = nastavitve
        parametriSeje = parametri
        s.start(parametri)
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
        // Podatki za nadaljevanje veljajo samo, dokler je torrent shranjen. Opis ostane do naslednjega zagona
        // (če uporabnik isti torrent doda znova, ga ni treba iskati po omrežju); potem ga pospravi [obnovi].
        try { datotekaStanja(c, hash.lowercase(), "resume")?.delete() } catch (_: Throwable) { }
    }

    private fun jeShranjen(c: Context, hash: String): Boolean =
        try { JSONObject(prefs(c).getString("prenosi", "{}") ?: "{}").has(hash) } catch (_: Throwable) { false }

    // ------------------------------------------------------------------ stanje na disku in obnova ob zagonu

    private const val NAJVEC_OPIS = 20L * 1024 * 1024

    /** Opis torrenta (`<hash>.torrent`) in podatki za nadaljevanje (`<hash>.resume`) v zasebni shrambi aplikacije. */
    private fun mapaStanja(c: Context): File = File(c.filesDir, "magnet").apply { mkdirs() }

    private fun datotekaStanja(c: Context, hash: String, vrsta: String): File? =
        if (ObnovaPravila.veljavenHash(hash)) File(mapaStanja(c), "$hash.$vrsta") else null

    /**
     * Mapa začasnega torrenta, s katerim knjižnica pridobi opis magneta. Ta torrent ima isti hash kot pravi, a ni naš:
     * njegovega stanja ne shranjujemo in ga ob obnovi ne sprejmemo.
     */
    private fun mapaIskanja(c: Context): File = File(c.cacheDir, "magnet").apply { mkdirs() }

    private fun lastnost(ime: String): String = try {
        Runtime.getRuntime().exec(arrayOf("getprop", ime)).inputStream.bufferedReader().use { it.readLine() }?.trim().orEmpty()
    } catch (_: Throwable) { "" }

    /** Zapis v celoti ali nič: najprej začasna datoteka, nato preimenovanje. */
    private fun zapisiCelo(f: File, bajti: ByteArray) {
        val zacasna = File(f.parentFile, f.name + ".tmp")
        zacasna.writeBytes(bajti)
        if (!zacasna.renameTo(f)) { f.delete(); if (!zacasna.renameTo(f)) zacasna.delete() }
    }

    private fun shraniOpis(c: Context, hash: String, bajti: ByteArray) {
        try { datotekaStanja(c, hash, "torrent")?.let { zapisiCelo(it, bajti) } } catch (e: Throwable) { Log.i(TAG, "Opis ${hash.take(8)}: ${e.message}") }
    }

    /** Shranjen opis torrenta, če je cel in res pripada temu hashu; poškodovan zapis odstrani (opis pride znova iz omrežja). */
    private fun shranjenOpis(c: Context, hash: String): TorrentInfo? {
        val f = datotekaStanja(c, hash, "torrent") ?: return null
        if (!f.isFile) return null
        val ti = try {
            if (f.length() in 1..NAJVEC_OPIS) TorrentInfo.bdecode(f.readBytes()).takeIf { it.isValid && ustreza(it, hash) } else null
        } catch (_: Throwable) { null }
        if (ti == null) try { f.delete() } catch (_: Throwable) { }
        return ti
    }

    private fun ustreza(ti: TorrentInfo, hash: String): Boolean = try {
        ti.infoHash().toHex() == hash || ti.infoHashes().let { it.hasV1() && it.v1.toHex() == hash }
    } catch (_: Throwable) { false }

    private fun obnovi(c: Context) {
        try { pocistiZacasne(c) } catch (e: Throwable) { Log.i(TAG, "Ciscenje: ${e.message}") }
        val vsi = JSONObject(prefs(c).getString("prenosi", "{}") ?: "{}")
        val hashi = vsi.keys().asSequence().toList()
        try {
            val m = mapaStanja(c)
            for (ime in ObnovaPravila.ostanki(m.list()?.toList().orEmpty(), hashi.toSet())) File(m, ime).delete()
            // Preizkus na napravi (lastnosti debug.* nastavi le adb): zagon kot prvič po posodobitvi - 1 = brez podatkov
            // za nadaljevanje, 2 = tudi brez opisov. Odstrani samo shranjeno stanje; prenesene datoteke ostanejo.
            val preizkus = lastnost("debug.safeer.torrent_brez_stanja")
            if (preizkus == "1" || preizkus == "2") for (f in m.listFiles().orEmpty())
                if (f.name.endsWith(".resume") || (preizkus == "2" && f.name.endsWith(".torrent"))) f.delete()
        } catch (e: Throwable) { Log.i(TAG, "Stanje: ${e.message}") }
        if (hashi.isNotEmpty()) Log.i(TAG, "Obnova: torrentov ${hashi.size}, z opisom ${hashi.count { datotekaStanja(c, it, "torrent")?.isFile == true }}, " +
            "s podatki za nadaljevanje ${hashi.count { datotekaStanja(c, it, "resume")?.isFile == true }}")
        for (h in hashi) {
            val z = vsi.optJSONObject(h) ?: continue
            Thread({ obnoviEnega(c, h, z) }, "safeer-magnet-obnova").apply { isDaemon = true; start() }
        }
    }

    private fun obnoviEnega(c: Context, h: String, z: JSONObject) {
        for (poskus in 0 until 6) {
            try {
                val lastna = z.optString("lastna")
                if (lastna.isNotBlank()) {
                    val f = File(lastna)
                    val ti = shranjenOpis(c, h)
                    // Z opisom datoteke, ki jo uporabnik deli, ni treba znova prebrati cele (izračun kosov).
                    if (ti != null && f.isFile) { opisi[h] = ti; vSejo(c, ti, f.parentFile ?: mapa(c), null, TorrentFlags.SEED_MODE, h, nezno = false) }
                    else deli(c, f)
                } else {
                    val izbrane = (0 until (z.optJSONArray("izbrane")?.length() ?: 0)).map { z.getJSONArray("izbrane").getInt(it) }
                    dodaj(c, z.optString("uri"), izbrane, izbrane.toSet(), zacasno = null, nezno = true)  // shranjene je uporabnik že potrdil
                }
                return
            } catch (e: Throwable) {
                Log.i(TAG, "Obnova ${h.take(8)}: ${e.message}")
                // Opisa brez omrežja ni mogoče dobiti: poskusimo pozneje. Druge napake se s čakanjem ne popravijo.
                if (e.message != "ni_metapodatkov") return
            }
            try { Thread.sleep(300_000) } catch (_: InterruptedException) { return }
        }
    }

    /** Ali je na disku vsaj ena izbrana datoteka torrenta (podatki za nadaljevanje brez datotek so zastareli). */
    private fun imaPodatke(ti: TorrentInfo, kam: File, prednosti: Array<Priority>?): Boolean = try {
        val fs = ti.files()
        (0 until fs.numFiles()).any { !fs.padFileAt(it) && (prednosti == null || prednosti.getOrNull(it) != Priority.IGNORE) && File(kam, fs.filePath(it)).exists() }
    } catch (_: Throwable) { true }

    /**
     * Doda torrent v sejo - s podatki za nadaljevanje, kadar jih imamo (že prenesenih datotek potem ne bere znova).
     * [nezno] (obnova ob zagonu): torrent brez teh podatkov pride v sejo ustavljen, datoteke na disku pa nato postopoma
     * preveri [preverjaj]. Brez [nezno] (uporabnik ga hoče zdaj) se preveri takoj. [kljuc] = hash, pod katerim je shranjen.
     */
    private fun vSejo(c: Context, ti: TorrentInfo, kam: File, prednosti: Array<Priority>?, zastavice: torrent_flags_t,
                      kljuc: String, nezno: Boolean) {
        val s = seja(c)
        val hashRocaja = ti.infoHash().toHex()
        kljuci[hashRocaja] = kljuc
        var shranjeni: add_torrent_params? = null
        val f = datotekaStanja(c, kljuc, "resume")
        if (f != null && f.isFile && imaPodatke(ti, kam, prednosti)) try {
            val napaka = error_code()
            val p = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(f.readBytes()), napaka)
            val tuje = p.getSave_path() == mapaIskanja(c).absolutePath
            if (napaka.value() == 0 && !tuje && Sha1Hash(p.getInfo_hashes().get_best()).toHex() == hashRocaja) shranjeni = p
            else Log.i(TAG, "Podatki za nadaljevanje ${kljuc.take(8)} niso uporabni")
        } catch (e: Throwable) { Log.i(TAG, "Podatki za nadaljevanje ${kljuc.take(8)}: ${e.message}") }
        // Stanje, shranjeno med preverjanjem, pozna le del kosov: preostanek je treba še preveriti.
        val nedokoncano = shranjeni != null && try { shranjeni.get_have_pieces().size() < ti.numPieces() } catch (_: Throwable) { false }
        var odrezano = 0
        if (nedokoncano && shranjeni != null) try {
            // Rep takega stanja ima lahko luknje (kosi, ki so bili ob shranjevanju še v delu): od prve luknje naprej znova.
            val kosi = shranjeni.get_have_pieces()
            val velja = ObnovaPravila.veljavnihKosov(kosi.size(), ObnovaPravila.repPreverjanja(ti.pieceLength())) { kosi.get_bit(it) }
            if (velja < kosi.size()) { odrezano = kosi.size() - velja; kosi.resize(velja); shranjeni.set_have_pieces(kosi) }
        } catch (e: Throwable) { Log.i(TAG, "Stanje ${kljuc.take(8)}: ${e.message}") }
        val preverba = nezno && (shranjeni == null || nedokoncano)
        val p = shranjeni ?: add_torrent_params()
        p.set_ti(ti.swig())
        p.setSave_path(kam.absolutePath)
        if (prednosti != null) p.set_file_priorities(byte_vector().apply { for (x in prednosti) add(x.swig()) })
        // Načini, ki jih sami nikoli ne izberemo (samo oddajanje po napaki diska, ustavitev ob pripravljenosti), se z
        // obnovo ne smejo prenesti v nov zagon: torrent v njih ne bi več prenašal.
        var z = p.getFlags().or_(zastavice).and_(TorrentFlags.UPLOAD_MODE.inv()).and_(TorrentFlags.STOP_WHEN_READY.inv())
            .and_(TorrentFlags.SHARE_MODE.inv())
        if (preverba) z = z.and_(TorrentFlags.AUTO_MANAGED.inv()).or_(TorrentFlags.PAUSED)
        p.setFlags(z)
        s.swig().async_add_torrent(p)
        Log.i(TAG, "V sejo ${kljuc.take(8)}: " + when {
            preverba && nedokoncano -> "nadaljevanje postopnega preverjanja od kosa ${shranjeni?.get_have_pieces()?.size()}" + (if (odrezano > 0) " (rep z luknjo: $odrezano kosov znova)" else "")
            shranjeni != null -> "s podatki za nadaljevanje"
            preverba -> "postopno preverjanje"
            else -> "brez podatkov za nadaljevanje"
        })
        if (preverba) vPreverjanje(hashRocaja)
    }

    private val cakaPreverjanje = java.util.ArrayDeque<String>()
    private var preverjanjeTece = false

    private fun vPreverjanje(hashRocaja: String) {
        synchronized(cakaPreverjanje) {
            cakaPreverjanje.add(hashRocaja)
            if (preverjanjeTece) return
            preverjanjeTece = true
        }
        Thread({ preverjaj() }, "safeer-magnet-preverjanje").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    /** Torrente brez podatkov za nadaljevanje preveri enega za drugim ([ObnovaPravila]). */
    private fun preverjaj() {
        while (true) {
            val h = synchronized(cakaPreverjanje) { cakaPreverjanje.poll().also { if (it == null) preverjanjeTece = false } } ?: return
            try { preveriEnega(h) } catch (e: Throwable) { Log.i(TAG, "Preverjanje ${h.take(8)}: ${e.message}") }
        }
    }

    private fun sePreverja(th: TorrentHandle): Boolean = th.status().state().let {
        it == TorrentStatus.State.CHECKING_FILES || it == TorrentStatus.State.CHECKING_RESUME_DATA
    }

    private fun maloPomnilnika(): Boolean = try {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.lowMemory
    } catch (_: Throwable) { false }

    private fun ura() = android.os.SystemClock.elapsedRealtime()

    /**
     * Preverjanje datotek enega torrenta v kratkih korakih: torrent teče [ObnovaPravila.TECE_MS], nato počiva. Premor se
     * podaljša, kadar naprava zamuja ali ji zmanjkuje pomnilnika. Vmesno stanje se shranjuje, zato se prekinjeno
     * preverjanje ob naslednjem zagonu nadaljuje. Torrent, ki ga kdo pravkar predvaja, se preverja s krajšimi premori.
     */
    private fun preveriEnega(h: String) {
        val s = seja ?: return
        val sha = Sha1Hash.parseHex(h)
        var najden: TorrentHandle? = null
        for (k in 0 until 50) {   // torrent je bil dodan asinhrono
            najden = s.find(sha)?.takeIf { it.isValid }
            if (najden != null) break
            Thread.sleep(200)
        }
        val th = najden ?: run { Log.i(TAG, "Preverjanje ${h.take(8)}: torrenta ni v seji"); return }
        val zacetek = ura()
        var shranjeno = zacetek
        var premor = ObnovaPravila.PREMOR_MS
        var bral = false
        var napaka = false
        while (th.isValid) {
            val st = th.status()
            if (st.state() != TorrentStatus.State.CHECKING_FILES && st.state() != TorrentStatus.State.CHECKING_RESUME_DATA) break
            if (st.errorCode().isError) { napaka = true; Log.i(TAG, "Preverjanje ${h.take(8)}: ${st.errorCode().message}"); break }
            if (ura() - zacetek > ObnovaPravila.NAJVEC_MS) { napaka = true; break }
            bral = bral || st.state() == TorrentStatus.State.CHECKING_FILES
            th.resume()
            Thread.sleep(ObnovaPravila.TECE_MS)
            th.pause()
            if (ura() - (zadnjiTok[h] ?: -ObnovaPravila.V_RABI_MS) <= ObnovaPravila.V_RABI_MS) {
                Thread.sleep(ObnovaPravila.PREMOR_V_RABI_MS)   // nekdo ga predvaja: krajši premori
            } else {
                val t0 = ura()
                Thread.sleep(premor)
                premor = ObnovaPravila.premor(premor, ura() - t0 - premor, maloPomnilnika())
            }
            if (ura() - shranjeno > ObnovaPravila.SHRANI_MS) {
                shranjeno = ura()
                th.saveResumeData()
                Log.i(TAG, "Preverjanje ${h.take(8)}: ${(st.progress() * 100).toInt()} %, premor $premor ms")
            }
        }
        if (!th.isValid) { Log.i(TAG, "Preverjanje ${h.take(8)}: torrent je odstranjen"); return }
        if (napaka) { th.pause(); return }
        val vRabi = ura() - (zadnjiTok[h] ?: -ObnovaPravila.V_RABI_MS) <= ObnovaPravila.V_RABI_MS
        val konec = th.status()
        val koncan = konec.isFinished
        when {
            // Končan torrent brez »Deli naprej« ostane ustavljen; nedokončan prenos gre naprej kot pred zagonom.
            !ObnovaPravila.tecePoPreverjanju(koncan, deliNaprej(app, h)) -> th.pause()
            vRabi -> th.resume()
            else -> { th.setFlags(TorrentFlags.AUTO_MANAGED); th.resume() }
        }
        th.saveResumeData()
        Log.i(TAG, "Preverjeno ${h.take(8)}: ${(ura() - zacetek) / 1000} s" + (if (bral) "" else ", brez branja") +
            (if (koncan) ", cel" else ", delen (manjka ${konec.totalWanted() - konec.totalWantedDone()} B)"))
        if (!koncan) try {
            // Kateri izbrani kosi manjkajo (prvih nekaj): za iskanje vzroka, kadar bi po preverjanju manjkal kos cele datoteke.
            val ti = th.torrentFile()
            val prednosti = th.piecePriorities()
            val manjkajo = (0 until (ti?.numPieces() ?: 0)).filter { prednosti.getOrNull(it) != Priority.IGNORE && !th.havePiece(it) }
            Log.i(TAG, "Preverjeno ${h.take(8)}: kosov ${ti?.numPieces()}, velikost kosa ${ti?.pieceLength()}, manjka ${manjkajo.size}: ${manjkajo.take(24)}")
        } catch (e: Throwable) { Log.i(TAG, "Preverjeno ${h.take(8)}: ${e.message}") }
    }

    /** Shranjevanje podatkov za nadaljevanje, ko jih libtorrent pripravi ([nadzor] jih zahteva sproti). */
    private val poslusalec = object : AlertListener {
        override fun types(): IntArray = intArrayOf(AlertType.SAVE_RESUME_DATA.swig(), AlertType.FASTRESUME_REJECTED.swig())
        override fun alert(alert: Alert<*>) {
            try {
                when (alert) {
                    is SaveResumeDataAlert -> {
                        val h = alert.handle().infoHash().toHex()
                        // Samo torrenti, ki smo jih v sejo dodali sami ([vSejo]) - ne začasni torrent iskanja opisa.
                        val kljuc = kljuci[h] ?: return
                        val stanje = alert.params()
                        if (stanje.savePath == mapaIskanja(app).absolutePath) return
                        // Torrent, ki ga je uporabnik medtem odstranil, ne pusti stanja za seboj.
                        if (jeShranjen(app, kljuc)) datotekaStanja(app, kljuc, "resume")?.let { zapisiCelo(it, AddTorrentParams.writeResumeDataBuf(stanje)) }
                    }
                    is FastresumeRejectedAlert ->
                        Log.i(TAG, "Podatki za nadaljevanje ${alert.handle().infoHash().toHex().take(8)} zavrnjeni: ${alert.error().message}")
                }
            } catch (e: Throwable) { Log.i(TAG, "Nadaljevanje: ${e.message}") }
        }
    }

    @Volatile private var varovano = false

    /**
     * Zaključevalnik domačega predmeta (libtorrent) lahko ob hudem zastoju naprave zamudi sistemski rok (10 s). Sistem
     * bi zato sesul aplikacijo, čeprav ni nič narobe (testni telefon, 4. 10. 2026). Tako zamudo samo zabeležimo;
     * vse druge napake gredo naprej kot prej.
     */
    private fun varujZakljucevalnike() {
        if (varovano) return
        varovano = true
        val prej = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { nit, napaka ->
            if (nit.name == "FinalizerWatchdogDaemon" && napaka is java.util.concurrent.TimeoutException)
                Log.w(TAG, "Zakljucevalnik je zamudil rok (naprava je zastala): ${napaka.message}")
            else prej?.uncaughtException(nit, napaka)
        }
    }

    fun deliNaprej(c: Context, hash: String): Boolean = prefs(c).getStringSet("deli_naprej", emptySet())!!.contains(hash)

    fun nastaviDeliNaprej(c: Context, hash: String, vklop: Boolean) {
        val m = prefs(c).getStringSet("deli_naprej", emptySet())!!.toMutableSet()
        if (vklop) m.add(hash) else m.remove(hash)
        prefs(c).edit().putStringSet("deli_naprej", m).apply()
        // Oddajanje vodi vrsta libtorrenta (hkrati le nekaj torrentov), zato mu vrnemo samodejno vodenje.
        if (vklop) rocaj(c, hash)?.let { it.setFlags(TorrentFlags.AUTO_MANAGED); it.resume() }
    }

    /**
     * Premor ali nadaljevanje na uporabnikovo željo. Ustavljenemu izklopimo samodejno vodenje vrste, sicer bi ga
     * libtorrent čez čas sam spet zagnal.
     */
    fun nastaviPremor(c: Context, hash: String, premor: Boolean) {
        val th = rocaj(c, hash) ?: return
        if (premor) { th.unsetFlags(TorrentFlags.AUTO_MANAGED); th.pause() } else th.resume()
        th.saveResumeData()
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
        val ti = opisi[h] ?: (shranjenOpis(c, h) ?: run {
            val bajti = seja(c).fetchMagnet(zSledilniki(uri.trim()), 60, mapaIskanja(c)) ?: throw IllegalStateException("ni_metapodatkov")
            TorrentInfo.bdecode(bajti).also { shraniOpis(c, h, bajti) }
        }).also { opisi[h] = it }
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
    fun dodaj(c: Context, uri: String, izbrane: List<Int>, potrjeneNevarne: Set<Int> = emptySet(),
              /** true = predvajanje brez izrecnega prenosa (samodejno ciscenje), false = uporabnik ga je prenesel sam (ostane), null = brez spremembe. */
              zacasno: Boolean? = false,
              /** Obnova ob zagonu: torrent brez podatkov za nadaljevanje datoteke preveri postopoma ([preverjaj]). */
              nezno: Boolean = false): String {
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
            vSejo(c, ti, mapa(c), prednosti, TorrentFlags.SEQUENTIAL_DOWNLOAD, opis.hash, nezno)
        }
        // Izrecen prenos velja naprej: torrent, ki ga je uporabnik prenesel sam, s predvajanjem ne postane zacasen.
        val shranjen = try { JSONObject(prefs(c).getString("prenosi", "{}") ?: "{}").has(opis.hash) } catch (_: Throwable) { false }
        val zeIzrecen = (obstojeci != null || shranjen) && !jeZacasen(c, opis.hash)
        zapomni(c, opis.hash, opis.uri, koncne)
        when {
            zacasno == true && !zeIzrecen -> zabeleziRabo(c, opis.hash, ti.name())
            zacasno == false -> pozabiZacasnega(c, opis.hash)
        }
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
        val bajti = TorrentBuilder().path(datoteka).creator("Safeer").generate().entry().bencode()
        val ti = TorrentInfo.bdecode(bajti)
        val hash = ti.infoHash().toHex()
        opisi[hash] = ti
        shraniOpis(c, hash, bajti)
        vSejo(c, ti, datoteka.parentFile ?: mapa(c), null, TorrentFlags.SEED_MODE, hash, nezno = false)
        nastaviDeliNaprej(c, hash, true)
        val uri = zSledilniki("magnet:?xt=urn:btih:$hash&dn=" + java.net.URLEncoder.encode(ti.name(), "UTF-8"))
        zapomni(c, hash, uri, setOf(0), lastna = datoteka.absolutePath)
        return uri
    }

    fun magnet(c: Context, hash: String): String? = rocaj(c, hash)?.makeMagnetUri()?.let { zSledilniki(it) }

    private fun nadzor() {
        var krog = 0
        while (true) {
            Thread.sleep(10_000)
            // Vsako uro: zacasni torrenti, ki jih 48 ur nihce ni predvajal, gredo (tudi med dolgim delovanjem aplikacije).
            if (++krog % 360 == 0) try { pocistiZacasne(app) } catch (e: Throwable) { Log.i(TAG, "Ciscenje: ${e.message}") }
            try {
                val s = seja ?: continue
                for (th in rocaji(s)) {
                    if (!th.isValid) continue
                    val st = th.status()
                    // Torrent, ki datoteke še preverja, vodi [preverjaj].
                    if (st.state() == TorrentStatus.State.CHECKING_FILES || st.state() == TorrentStatus.State.CHECKING_RESUME_DATA) continue
                    val zastavice = th.getFlags()
                    val ustavljen = zastavice.and_(TorrentFlags.PAUSED).non_zero() && !zastavice.and_(TorrentFlags.AUTO_MANAGED).non_zero()
                    // Po koncu prenosa ne oddajamo drugim, razen če uporabnik to izrecno izbere. Samodejno vodenje vrste
                    // izklopimo, sicer libtorrent ustavljen torrent čez čas sam spet zažene.
                    if (st.isFinished && !deliNaprej(app, th.infoHash().toHex()) && !ustavljen) {
                        th.unsetFlags(TorrentFlags.AUTO_MANAGED); th.pause(); th.saveResumeData()
                    } else if (krog % 3 == 0 && th.needSaveResumeData()) th.saveResumeData()
                }
            } catch (e: Throwable) { Log.i(TAG, "Nadzor: ${e.message}") }
        }
    }

    // ------------------------------------------------------------------ zacasni tokovi in samodejno ciscenje

    /** Motor torrentov je v Safeer OS (TV, tablica, telefon); Predvajalnik in brskalnik ga nimata. */
    val naVoljo: Boolean get() = si.safeer.tv.BuildConfig.FLAVOR in setOf("os", "tablica", "telefon")

    /**
     * Roka iz [ZacasniPravila]. Za preizkus na napravi ju je mogoce skrajsati z `adb shell setprop
     * debug.safeer.torrent_velja_ms <ms>` in `debug.safeer.torrent_tece_ms <ms>` (lastnosti debug.* nastavi le adb).
     */
    private fun rok(lastnost: String, privzeto: Long): Long = try {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", lastnost))
        p.inputStream.bufferedReader().use { it.readLine() }?.trim()?.toLongOrNull()?.takeIf { it in 1_000L..privzeto } ?: privzeto
    } catch (_: Throwable) { privzeto }
    private val zadnjiZapisRabe = ConcurrentHashMap<String, Long>()

    /** hash -> {cas zadnje rabe, ime mape ali datoteke}: torrenti, ki jih je zacel predvajalnik ali druga naprava v Linku. */
    private fun zacasni(c: Context): JSONObject = try { JSONObject(prefs(c).getString("zacasni", "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }

    fun jeZacasen(c: Context, hash: String): Boolean = zacasni(c).has(hash.lowercase())

    @Synchronized
    fun zabeleziRabo(c: Context, hash: String, ime: String = "") {
        val h = hash.lowercase()
        val z = zacasni(c)
        val prej = z.optJSONObject(h)
        // Ostala polja vnosa (opis za knjiznico kroga, narocniki) ostanejo.
        val vnos = prej?.let { JSONObject(it.toString()) } ?: JSONObject()
        z.put(h, vnos.put("cas", System.currentTimeMillis()).put("ime", ime.ifBlank { prej?.optString("ime").orEmpty() }))
        prefs(c).edit().putString("zacasni", z.toString()).apply()
        zadnjiZapisRabe[h] = System.currentTimeMillis()
    }

    private fun opisIz(vnos: JSONObject): KnjiznicaKroga.Opis? =
        if (vnos.optBoolean("zaseben")) KnjiznicaKroga.Opis("", "", "", "", true)
        else vnos.optString("naslov").takeIf { it.isNotBlank() }?.let { KnjiznicaKroga.Opis(it, vnos.optString("plakat"), vnos.optString("vrsta"), vnos.optString("ref"), false) }

    private fun narocniki(vnos: JSONObject): List<String> =
        vnos.optJSONArray("narocniki")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()

    /**
     * Knjiznica kroga: kar je naprava (ta ali druga v Linku) povedala o zacasnem torrentu - naslov in plakat ali da je
     * zaseben - in kdo ga je prosil ([KnjiznicaKroga]). Torrenta, ki ni zacasen (uporabnik ga je prenesel sam), ne belezimo.
     */
    @Synchronized
    fun zabeleziOpis(c: Context, hash: String, opis: KnjiznicaKroga.Opis?, narocnik: String) {
        if (opis == null) return
        val h = hash.lowercase()
        val z = zacasni(c)
        val vnos = z.optJSONObject(h) ?: return
        val zdruzen = KnjiznicaKroga.zdruzi(opisIz(vnos), opis)
        vnos.put("zaseben", zdruzen.zaseben).put("naslov", zdruzen.naslov).put("plakat", zdruzen.plakat).put("vrsta", zdruzen.vrsta).put("ref", zdruzen.ref)
        vnos.put("narocniki", JSONArray((narocniki(vnos).filter { it != narocnik } + narocnik).filter { it.isNotBlank() }.takeLast(16)))
        z.put(h, vnos)
        prefs(c).edit().putString("zacasni", z.toString()).apply()
    }

    /** Tok se bere (predvajanje tece): rok do odstranitve se podaljsa - zapis najvec na 10 minut. */
    private fun rabaTece(c: Context, hash: String) {
        val h = hash.lowercase()
        if (System.currentTimeMillis() - (zadnjiZapisRabe[h] ?: 0L) < 600_000L) return
        zadnjiZapisRabe[h] = System.currentTimeMillis()
        if (jeZacasen(c, h)) zabeleziRabo(c, h)
    }

    @Synchronized
    private fun pozabiZacasnega(c: Context, hash: String) {
        val z = zacasni(c)
        if (!z.has(hash.lowercase())) return
        z.remove(hash.lowercase())
        prefs(c).edit().putString("zacasni", z.toString()).apply()
    }

    /** Koliko prostora mora na napravi vedno ostati (5 % shrambe, najmanj 300 MB, najvec 2 GB). */
    fun rezerva(c: Context): Long = try {
        (mapa(c).totalSpace / 20).coerceIn(300L * 1024 * 1024, 2048L * 1024 * 1024)
    } catch (_: Throwable) { 1024L * 1024 * 1024 }

    private fun velikostNaDisku(f: File): Long = try {
        if (f.isDirectory) f.listFiles()?.sumOf { velikostNaDisku(it) } ?: 0L else f.length()
    } catch (_: Throwable) { 0L }

    /** Mapa ali datoteka zacasnega torrenta v nasi mapi prenosov (nikoli zunaj nje). */
    private fun potZacasnega(c: Context, ime: String): File? {
        if (ime.isBlank() || ime == "." || ime == ".." || ime.contains('/') || ime.contains('\\')) return null
        val koren = mapa(c)
        val f = File(koren, ime)
        return try { if (f.canonicalPath.startsWith(koren.canonicalPath + File.separator)) f else null } catch (_: Throwable) { null }
    }

    /** Datoteke zacasnega torrenta in njegova datoteka delnih kosov (.<hash>.parts) - tudi kadar seja ne tece. */
    private fun izbrisiDatoteke(c: Context, hash: String, ime: String) {
        potZacasnega(c, ime)?.let { try { it.deleteRecursively() } catch (_: Throwable) { } }
        if (Regex("^[0-9a-f]{40}$").matches(hash)) try { File(mapa(c), ".$hash.parts").delete() } catch (_: Throwable) { }
    }

    @Volatile private var prostorOb = 0L
    @Volatile private var prostorZadnji = 0L

    /**
     * Najvecja datoteka, ki jo ta naprava lahko predvaja iz torrenta sama: prosti prostor brez rezerve, skupaj s tem,
     * kar bi sprostili zacasni torrenti, ki jih ze nekaj ur nihce ne gleda. Vrednost velja nekaj sekund (klic iz risanja).
     */
    fun prostorZaTok(c: Context): Long {
        if (!naVoljo) return 0L
        val zdaj = android.os.SystemClock.elapsedRealtime()
        if (prostorOb != 0L && zdaj - prostorOb < 5_000) return prostorZadnji
        var sprostljivo = 0L
        try {
            val z = zacasni(c)
            for (h in z.keys()) {
                val o = z.optJSONObject(h) ?: continue
                if (ZacasniPravila.sprostljiv(o.optLong("cas"), System.currentTimeMillis(), obdrzan = o.optBoolean("obdrzi"))) potZacasnega(c, o.optString("ime"))?.let { sprostljivo += velikostNaDisku(it) }
            }
        } catch (_: Throwable) { }
        prostorZadnji = (mapa(c).usableSpace - rezerva(c) + sprostljivo).coerceAtLeast(0L)
        prostorOb = zdaj
        return prostorZadnji
    }

    /**
     * Odstrani zacasne torrente z datotekami: tiste, ki jih 48 ur nihce ni predvajal, in - kadar za nov film manjka
     * prostora ([potrebujem] bajtov prostega) - tudi mlajse, najdlje neuporabljene prve (ne predvajanih v zadnjih urah in
     * ne [obdrzi]). Dela tudi brez zagnane seje (ob zagonu aplikacije): zapis in datoteke odstrani naravnost.
     * Kar je uporabnik prenesel sam (Prenesi), ni zacasno in ostane. Vrne stevilo odstranjenih.
     */
    @Synchronized
    fun pocistiZacasne(c: Context, potrebujem: Long = 0L, obdrzi: String = ""): Int {
        val z = zacasni(c)
        val zdaj = System.currentTimeMillis()
        if (z.length() == 0) return 0
        val velja = rok("debug.safeer.torrent_velja_ms", ZacasniPravila.VELJA_MS)
        val vTeku = rok("debug.safeer.torrent_tece_ms", ZacasniPravila.V_TEKU_MS)
        val vsi = ZacasniPravila.poVrsti(z.keys().asSequence().toList().mapNotNull { h -> z.optJSONObject(h)?.let { it.optLong("cas") to (h to it.optString("ime")) } })
        var odstranjenih = 0
        for ((cas, vnos) in vsi) {
            val (h, ime) = vnos
            if (h == obdrzi.lowercase()) continue
            // »Obdrži«: kar je uporabnik oznacil, ne potece in ne gre niti ob pomanjkanju prostora.
            val drzimo = z.optJSONObject(h)?.optBoolean("obdrzi") == true
            val pretekel = ZacasniPravila.odstrani(cas, zdaj, false, velja, vTeku, drzimo)
            val primanjkuje = potrebujem > 0 && mapa(c).usableSpace < potrebujem
            if (!ZacasniPravila.odstrani(cas, zdaj, primanjkuje, velja, vTeku, drzimo)) continue
            try {
                seja?.let { s -> s.find(Sha1Hash.parseHex(h))?.let { s.remove(it, session_handle.delete_files) } }
            } catch (e: Throwable) { Log.i(TAG, "Odstranitev $h: ${e.message}") }
            pozabi(c, h)
            prefs(c).getStringSet("deli_naprej", emptySet())!!.let { m -> if (h in m) prefs(c).edit().putStringSet("deli_naprej", m - h).apply() }
            izbrisiDatoteke(c, h, ime)
            z.remove(h)
            odstranjenih++
            Log.i(TAG, "Zacasni torrent $h odstranjen (${if (pretekel) "48 h brez predvajanja" else "prostor za nov film"})")
        }
        if (odstranjenih > 0) { prefs(c).edit().putString("zacasni", z.toString()).apply(); prostorOb = 0L }
        return odstranjenih
    }

    /** Pripravljen tok: lokalni naslov za predvajalnik te naprave in skrivnost za tok drugi napravi ([postreziNapravi]). */
    class Pripravljen(val hash: String, val datoteka: Datoteka, val url: String, val skrivnost: String, val podnapisi: List<Pair<Datoteka, String>>)

    /**
     * Predvajanje naravnost iz torrenta, brez okna Magnet (film iz kataloga; pomoc drugi napravi v Linku): prebere
     * metapodatke, izbere datoteko ([datoteka] ali najvecji video), naredi prostor in zacne prenos kot zacasen torrent.
     * Blokira do minute - klic iz ozadja. Napake kot sporocilo izjeme: ni_magnet, ni_metapodatkov, ni_predvajljivo,
     * ni_prostora.
     */
    fun pripraviTok(c: Context, uri: String, datoteka: Int = -1): Pripravljen {
        if (!naVoljo) throw IllegalStateException("ni_podprto")
        val opis = try { preberi(c, uri) } catch (e: IllegalArgumentException) { throw IllegalStateException("ni_magnet") }
        val d = (if (datoteka >= 0) opis.datoteke.firstOrNull { it.i == datoteka && it.predvajljiva }
            else opis.datoteke.filter { it.vrsta == "video" }.maxByOrNull { it.velikost } ?: opis.datoteke.filter { it.vrsta == "audio" }.maxByOrNull { it.velikost })
            ?: throw IllegalStateException("ni_predvajljivo")
        // Kar je od te datoteke ze na disku (film, ki ga nadaljujemo), ne potrebuje novega prostora. Dokler torrent svoje
        // datoteke se preverja (prvi zagon po posodobitvi), napredka ne pozna: takrat velja velikost datoteke na disku.
        val zePreneseno = try {
            val th = rocaj(c, opis.hash)
            val znano = th?.fileProgress()?.getOrNull(d.i) ?: 0L
            if (th != null && sePreverja(th)) maxOf(znano, File(th.savePath(), d.ime).length().coerceAtMost(d.velikost)) else znano
        } catch (_: Throwable) { 0L }
        val potrebno = (d.velikost - zePreneseno).coerceAtLeast(0L) + rezerva(c)
        pocistiZacasne(c, potrebno, opis.hash)
        if (mapa(c).usableSpace < potrebno) throw IllegalStateException("ni_prostora")
        val podnapisi = if (d.vrsta == "video") try { podnapisiZa(c, uri, d.i) } catch (_: Throwable) { emptyList() } else emptyList()
        val hash = dodaj(c, uri, listOf(d.i) + podnapisi.map { it.i }, zacasno = true)
        val url = tok(c, hash, d.i)
        return Pripravljen(hash, d, url, url.substringAfterLast('/'), podnapisi.map { it to tok(c, hash, it.i) })
    }

    /** Zacasni torrenti te naprave za `magnet.list`: [(hash, ime, velikost izbranih, preneseno, koncano, magnet, datoteka)]. */
    fun seznamZacasnih(c: Context, /** Naprava, ki sprasuje (zasebnega dobi samo narocnik); null = ta naprava sama. */ vprasa: String? = null): JSONArray {
        val izid = JSONArray()
        if (!naVoljo) return izid
        val z = zacasni(c)
        if (z.length() == 0) return izid
        val vsi = seznam(c)
        for (k in 0 until vsi.length()) {
            val t = vsi.optJSONObject(k) ?: continue
            val hash = t.optString("hash").lowercase()
            if (!z.has(hash)) continue
            val d = t.optJSONArray("datoteke")
            val video = (0 until (d?.length() ?: 0)).mapNotNull { d?.optJSONObject(it) }.firstOrNull { it.optBoolean("vkljucena") && it.optString("vrsta") == "video" }
            val zapis = z.optJSONObject(hash) ?: JSONObject()
            val opis = opisIz(zapis)
            if (vprasa != null && !KnjiznicaKroga.pove(opis, narocniki(zapis), vprasa)) continue
            val vnos = JSONObject().put("id", idZacasnega(hash)).put("name", t.optString("ime")).put("size", t.optLong("skupaj"))
                .put("done", t.optLong("preneseno")).put("finished", t.optBoolean("koncano"))
                .put("magnet", "magnet:?xt=urn:btih:$hash").put("file", video?.optInt("i", -1) ?: -1)
                // »Obdrži«: polje je vedno tu - naprava po njem ve, da ta naprava zna `magnet.keep`.
                .put("keep", zapis.optBoolean("obdrzi"))
            if (opis?.zaseben == true) vnos.put("private", true)
            else if (opis != null) {
                vnos.put("title", opis.naslov)
                if (opis.plakat.isNotBlank()) vnos.put("poster", opis.plakat)
                if (opis.vrsta.isNotBlank()) vnos.put("kind", opis.vrsta)
                if (opis.ref.isNotBlank()) vnos.put("ref", opis.ref)
            }
            izid.put(vnos)
        }
        return izid
    }

    /** Stevilka zacasnega torrenta za `magnet.remove` (protokol racunalnika pozna celo stevilo, ne hasha). */
    fun idZacasnega(hash: String): Int = hash.take(7).toInt(16)

    fun seznamZacasnihIdji(c: Context): List<Int> = try { zacasni(c).keys().asSequence().map { idZacasnega(it) }.toList() } catch (_: Throwable) { emptyList() }

    /**
     * »Obdrži« (`magnet.keep` ali polica na tej napravi): zacasni torrent ne potece po 48 urah - odstrani ga samo
     * uporabnik. [vprasa] = naprava, ki to zeli (null = ta naprava sama); zasebnega sme oznaciti le narocnik.
     */
    @Synchronized
    fun nastaviObdrzi(c: Context, id: Int, obdrzi: Boolean, vprasa: String? = null): Boolean {
        val z = zacasni(c)
        val hash = z.keys().asSequence().firstOrNull { idZacasnega(it) == id } ?: return false
        val vnos = z.optJSONObject(hash) ?: return false
        if (vprasa != null && !KnjiznicaKroga.pove(opisIz(vnos), narocniki(vnos), vprasa)) return false
        if (obdrzi) vnos.put("obdrzi", true) else vnos.remove("obdrzi")
        z.put(hash, vnos)
        prefs(c).edit().putString("zacasni", z.toString()).apply()
        prostorOb = 0L
        return true
    }

    /** `magnet.remove`: samo zacasni torrenti (kar je uporabnik te naprave prenesel sam, druga naprava ne more odstraniti). */
    fun odstraniZacasnega(c: Context, id: Int): Boolean {
        val z = zacasni(c)
        val hash = z.keys().asSequence().firstOrNull { idZacasnega(it) == id } ?: return false
        odstrani(c, hash, true)
        izbrisiDatoteke(c, hash, z.optJSONObject(hash)?.optString("ime").orEmpty())
        pozabi(c, hash)
        pozabiZacasnega(c, hash)
        prostorOb = 0L
        return true
    }

    /** Tok za drugo napravo v Linku ([si.safeer.tv.link.DatotekeStreznik] pot `/magnet/<skrivnost>`, zeton je ze preverjen). */
    fun postreziNapravi(skrivnost: String, metoda: String, obseg: String, izhod: OutputStream) {
        val cilj = tokovi[skrivnost]
        if ((metoda != "GET" && metoda != "HEAD") || cilj == null) { prazen(izhod, 404); return }
        postreziCilj(cilj, metoda, obseg, izhod)
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
        // Predvajanje mora teči ne glede na vrsto prenosov: libtorrent hkrati pusti le nekaj prenosov, ostale ustavi.
        th.unsetFlags(TorrentFlags.AUTO_MANAGED)
        th.resume()
        zadnjiTok[th.infoHash().toHex()] = ura()
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
            postreziCilj(cilj, metoda, obseg, izhod)
        } catch (e: Throwable) {
            Log.i(TAG, "Tok: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        } finally {
            try { s.close() } catch (_: Throwable) { }
        }
    }

    /** Odgovor s tokom datoteke torrenta (z `Range`): isti za predvajalnik te naprave in za drugo napravo v Linku. */
    private fun postreziCilj(cilj: Pair<String, Int>, metoda: String, obseg: String, izhod: OutputStream) {
        try {
            rabaTece(app, cilj.first)
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
        val hashToka = try { th.infoHash().toHex() } catch (_: Throwable) { "" }
        try {
            while (poz < konec) {
                if (hashToka.isNotEmpty()) zadnjiTok[hashToka] = ura()
                // Roki: najprej kos, ki ga potrebujemo zdaj, nato okno naprej (predvajanje brez zatikanja).
                val prvi = ((odmikDatoteke + poz) / dolzinaKosa).toInt()
                val zadnjiOkna = ((odmikDatoteke + minOf(konec, poz + OKNO_BAJTOV) - 1) / dolzinaKosa).toInt()
                for ((n, kos) in (prvi..minOf(zadnjiOkna, ti.numPieces() - 1)).withIndex()) {
                    if (!th.havePiece(kos)) th.setPieceDeadline(kos, 500 + n * 250)
                }
                val zacetek = System.currentTimeMillis()
                while (!th.havePiece(prvi)) {
                    if (System.currentTimeMillis() - zacetek > CAKAJ_KOS_MS) throw java.io.IOException("kos $prvi ne pride")
                    if (hashToka.isNotEmpty()) zadnjiTok[hashToka] = ura()
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
