package si.safeer.tv.os

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors

/**
 * Zvocniki v omrezju (DLNA) na telefonu, tablici in TV: naprava jih najde in upravlja SAMA - brez
 * racunalnika in brez Safeer Linka (lastnik, 29. 9. 2026). Zvocnik vir (javni http(s) naslov skladbe ali
 * radia) potegne sam, telefon je le daljinec. Pravila in razclenjevanje: [DlnaPravila].
 */
object Zvocniki {
    private const val TAG = "SafeerZvocniki"
    private val delavec = Executors.newSingleThreadExecutor { r -> Thread(r, "safeer-zvocniki").apply { isDaemon = true } }
    private val glavna = Handler(Looper.getMainLooper())

    /** Zvocnik, ki ta trenutek igra nasa skladba (ali null). */
    @Volatile var aktivni: DlnaPravila.Zvocnik? = null
        private set
    @Volatile var aktivnaSkladba: Jamendo.Skladba? = null
        private set
    @Volatile private var zadnjiSeznam: List<DlnaPravila.Zvocnik> = emptyList()
    private var straza: Runnable? = null

    fun zadnji(): List<DlnaPravila.Zvocnik> = zadnjiSeznam

    // ------------------------------------------------------------------ iskanje
    fun najdi(ctx: Context, rezultat: (List<DlnaPravila.Zvocnik>) -> Unit) {
        val app = ctx.applicationContext
        delavec.execute {
            val najdeni = try { isci(app, 3000) } catch (e: Exception) { Log.w(TAG, "Iskanje: ${e.message}"); emptyList() }
            zadnjiSeznam = najdeni
            glavna.post { rezultat(najdeni) }
        }
    }

    private fun isci(ctx: Context, casMs: Int): List<DlnaPravila.Zvocnik> {
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val zaklep = wifi?.createMulticastLock("safeer-zvocniki")?.apply { setReferenceCounted(false); acquire() }
        val lokacije = LinkedHashMap<String, String>()
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 250
                val cilj = InetAddress.getByName("239.255.255.250")
                val p = DlnaPravila.poizvedba()
                repeat(2) { s.send(DatagramPacket(p, p.size, cilj, 1900)) }   // UDP se lahko izgubi
                val konec = System.currentTimeMillis() + casMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < konec) {
                    val paket = DatagramPacket(buf, buf.size)
                    try { s.receive(paket) } catch (_: SocketTimeoutException) { continue }
                    val ip = paket.address?.hostAddress ?: continue
                    val loc = DlnaPravila.lokacija(String(paket.data, 0, paket.length, Charsets.ISO_8859_1), ip) ?: continue
                    lokacije[loc] = ip
                }
            }
        } finally {
            try { zaklep?.release() } catch (_: Exception) {}
        }
        val poUdn = LinkedHashMap<String, DlnaPravila.Zvocnik>()
        for ((loc, _) in lokacije) {
            try {
                val z = DlnaPravila.razcleniOpis(prenesi(loc, DlnaPravila.NAJVEC_OPISA), loc) ?: continue
                poUdn.putIfAbsent(z.udn, z)
            } catch (e: Exception) { Log.d(TAG, "Opis $loc: ${e.message}") }
        }
        return poUdn.values.sortedBy { it.ime.lowercase() }
    }

    private fun prenesi(url: String, meja: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 4000; c.readTimeout = 5000
        try {
            val out = ByteArrayOutputStream()
            c.inputStream.use { vhod ->
                val kos = ByteArray(8192)
                while (true) {
                    val n = vhod.read(kos); if (n < 0) break
                    out.write(kos, 0, n); if (out.size() > meja) throw IllegalStateException("prevelik opis")
                }
            }
            return out.toByteArray()
        } finally { c.disconnect() }
    }

    private fun soap(url: String, storitev: String, dejanje: String, arg: List<Pair<String, String>>, vrni: String = ""): String {
        val telo = DlnaPravila.soap(storitev, dejanje, listOf("InstanceID" to "0") + arg).toByteArray(Charsets.UTF_8)
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 4000; c.readTimeout = 6000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        c.setRequestProperty("SOAPACTION", "\"$storitev#$dejanje\"")
        try {
            c.outputStream.use { it.write(telo) }
            val tok = if (c.responseCode in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            val odgovor = tok.use { it.readBytes() }
            return DlnaPravila.vrednost(odgovor, vrni)
        } finally { c.disconnect() }
    }

    private fun naDelavcu(konec: (Exception?) -> Unit, delo: () -> Unit) {
        delavec.execute {
            val napaka = try { delo(); null } catch (e: Exception) { Log.w(TAG, "${e.message}"); e }
            glavna.post { konec(napaka) }
        }
    }

    // ------------------------------------------------------------------ upravljanje
    /** Vir, ki ga zvocnik ta trenutek igra, ce ni nas (npr. "TV") - da uporabnika vprasamo pred preklopom. */
    fun tujVir(z: DlnaPravila.Zvocnik, rezultat: (String?) -> Unit) {
        delavec.execute {
            val vir = try {
                val st = soap(z.avUrl, DlnaPravila.AV, "GetTransportInfo", emptyList(), "CurrentTransportState")
                val uri = soap(z.avUrl, DlnaPravila.AV, "GetPositionInfo", emptyList(), "TrackURI")
                if (st == "PLAYING" && uri.isNotBlank() && !DlnaPravila.primernVir(uri)) uri else null
            } catch (_: Exception) { null }
            glavna.post { rezultat(vir) }
        }
    }

    fun predvajaj(ctx: Context, z: DlnaPravila.Zvocnik, sk: Jamendo.Skladba, konec: (Exception?) -> Unit) {
        val app = ctx.applicationContext
        naDelavcu({ e -> if (e == null) { aktivni = z; aktivnaSkladba = sk; zazeniStrazo(app) }; konec(e) }) {
            val mime = DlnaPravila.mime(sk.zvok, sk.mime)
            val meta = DlnaPravila.didl(sk.zvok, sk.naslov, sk.izvajalec, mime)
            soap(z.avUrl, DlnaPravila.AV, "SetAVTransportURI", listOf("CurrentURI" to sk.zvok, "CurrentURIMetaData" to meta))
            soap(z.avUrl, DlnaPravila.AV, "Play", listOf("Speed" to "1"))
        }
    }

    fun premorAliNadaljuj(konec: (Exception?) -> Unit) {
        val z = aktivni ?: return konec(null)
        naDelavcu(konec) {
            val st = soap(z.avUrl, DlnaPravila.AV, "GetTransportInfo", emptyList(), "CurrentTransportState")
            if (st == "PLAYING") soap(z.avUrl, DlnaPravila.AV, "Pause", emptyList())
            else soap(z.avUrl, DlnaPravila.AV, "Play", listOf("Speed" to "1"))
        }
    }

    fun glasnost(sprememba: Int, konec: (Exception?) -> Unit) {
        val z = aktivni ?: return konec(null)
        naDelavcu(konec) {
            val zdaj = soap(z.rcUrl, DlnaPravila.RC, "GetVolume", listOf("Channel" to "Master"), "CurrentVolume").toIntOrNull() ?: 20
            soap(z.rcUrl, DlnaPravila.RC, "SetVolume",
                listOf("Channel" to "Master", "DesiredVolume" to (zdaj + sprememba).coerceIn(0, 100).toString()))
        }
    }

    /** Konec na zvocniku (glasba se lahko vrne na to napravo). */
    fun ustavi(konec: (Exception?) -> Unit) {
        val z = aktivni ?: return konec(null)
        pocisti()
        naDelavcu(konec) { soap(z.avUrl, DlnaPravila.AV, "Stop", emptyList()) }
    }

    private fun pocisti() {
        aktivni = null; aktivnaSkladba = null
        straza?.let { glavna.removeCallbacks(it) }; straza = null
    }

    /** Ko skladba na zvocniku konca, poslje naslednjo iz vrste; ce uporabnik na zvocniku izbere drug vir, odnehamo. */
    private fun zazeniStrazo(ctx: Context) {
        straza?.let { glavna.removeCallbacks(it) }
        var napake = 0
        var igral = false
        val r = object : Runnable {
            override fun run() {
                val z = aktivni ?: return
                val sk = aktivnaSkladba ?: return
                delavec.execute {
                    val (st, uri) = try {
                        soap(z.avUrl, DlnaPravila.AV, "GetTransportInfo", emptyList(), "CurrentTransportState") to
                            soap(z.avUrl, DlnaPravila.AV, "GetPositionInfo", emptyList(), "TrackURI")
                    } catch (_: Exception) { "" to "" }
                    glavna.post {
                        if (aktivni !== z || straza !== this) return@post
                        when {
                            st == "PLAYING" || st == "TRANSITIONING" || st == "PAUSED_PLAYBACK" -> {
                                if (uri.isNotBlank() && uri != sk.zvok && !DlnaPravila.primernVir(uri)) { pocisti(); return@post }
                                igral = true; napake = 0
                            }
                            st == "STOPPED" && igral -> {
                                val vrsta = GlasbaStoritev.vrsta()
                                val i = vrsta.indexOfFirst { it.id == sk.id }
                                val naslednja = if (i >= 0) vrsta.drop(i + 1).firstOrNull { DlnaPravila.primernVir(it.zvok) && !it.video } else null
                                if (naslednja != null) predvajaj(ctx, z, naslednja) {} else pocisti()
                                return@post
                            }
                            else -> if (++napake >= 6) { pocisti(); return@post }
                        }
                        glavna.postDelayed(this, 4000)
                    }
                }
            }
        }
        straza = r
        glavna.postDelayed(r, 6000)
    }
}
