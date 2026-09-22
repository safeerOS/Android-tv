package si.safeer.tv.link

import android.content.Context
import android.net.Network
import android.util.Base64
import org.json.JSONObject
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Application-layer internet gateway. Ne uporablja tetheringa in ne spreminja Android routinga.
 * Vsak oddaljeni TCP tok postane nov socket, ki ga Safeer sam odpre in ga pred connect() priveze
 * na izbrani Android Network. Vsebina do druge Safeer naprave potuje po obstojecem TLS WebSocketu.
 */
class AndroidApplicationGateway(
    context: Context,
    private val poti: AndroidInternetPoti,
    private val poslji: (JSONObject) -> Boolean
) : Closeable {
    data class Stanje(val dovoljeno: Boolean, val mobilniBajti: Long, val aktivniTokovi: Int)

    private val prefs = context.applicationContext.getSharedPreferences("safeer_internet_gateway", Context.MODE_PRIVATE)
    private val tokovi = ConcurrentHashMap<String, Tok>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "safeer-gateway").apply { isDaemon = true } }
    private val mobilni = AtomicLong(prefs.getLong("cellular_bytes", 0L))

    init { novMesec() }

    /** Mesecna omejitev: ob novem mesecu se stevec mobilnih bajtov zacne pri 0. */
    private fun novMesec() {
        val c = Calendar.getInstance()
        val mesec = c.get(Calendar.YEAR) * 100 + c.get(Calendar.MONTH) + 1
        if (prefs.getInt("cellular_month", 0) != mesec) {
            mobilni.set(0L)
            prefs.edit().putInt("cellular_month", mesec).putLong("cellular_bytes", 0L).apply()
        }
    }

    @Volatile var politika = InternetPolitika(
        dovoliMobilne = prefs.getBoolean("allow_cellular", false),
        dovoliRoaming = prefs.getBoolean("allow_roaming", false),
        omejitevBajtov = prefs.getLong("cellular_limit", 0L),
        porabljenoBajtov = mobilni.get()
    ); private set

    @Volatile var dovoljeno: Boolean = prefs.getBoolean("gateway_enabled", false); private set

    /** [pisalec] je en sam nit na tok: kosi gredo v socket v istem vrstnem redu, kot so prisli. */
    private class Tok(
        val id: String, val peer: String, val socket: Socket, val pot: InternetPot,
        val pisalec: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "safeer-gateway-w").apply { isDaemon = true } },
        @Volatile var zaprt: Boolean = false
    )

    fun nastavi(dovoli: Boolean, dovoliMobilne: Boolean, dovoliRoaming: Boolean, omejitevBajtov: Long) {
        dovoljeno = dovoli
        politika = InternetPolitika(dovoliMobilne, dovoliRoaming, omejitevBajtov.coerceAtLeast(0), mobilni.get())
        prefs.edit().putBoolean("gateway_enabled", dovoli)
            .putBoolean("allow_cellular", dovoliMobilne)
            .putBoolean("allow_roaming", dovoliRoaming)
            .putLong("cellular_limit", omejitevBajtov.coerceAtLeast(0)).apply()
        if (dovoliMobilne) poti.zahtevajMobilno() else poti.sprostiMobilno()
        if (!dovoli) zapriVse("gateway_disabled")
    }

    fun osveziIzShrambe() {
        nastavi(
            prefs.getBoolean("gateway_enabled", false),
            prefs.getBoolean("allow_cellular", false),
            prefs.getBoolean("allow_roaming", false),
            prefs.getLong("cellular_limit", 0L)
        )
    }

    fun stanje() = Stanje(dovoljeno, mobilni.get(), tokovi.size)

    /** Vrne true, ce je bilo sporocilo gateway protokola in je obdelano. */
    fun obdelaj(sporocilo: JSONObject): Boolean {
        return when (sporocilo.optString("type")) {
            "internet.open" -> { odpri(sporocilo); true }
            "internet.data" -> { podatki(sporocilo); true }
            "internet.close" -> { zapri(sporocilo.optString("stream_id"), "peer_closed"); true }
            else -> false
        }
    }

    private fun odpri(msg: JSONObject) {
        val peer = msg.optString("sender", "")
        val id = msg.optString("stream_id", "")
        val host = msg.optString("host", "")
        val port = msg.optInt("port", 0)
        val zahtevanaPot = msg.optString("path_id", "")
        if (!dovoljeno || peer.isBlank() || id.length !in 8..96 || host.length !in 1..253 || !dovoljenaVrata(port)) {
            napaka(peer, id, "rejected"); return
        }
        if (tokovi.size >= NAJVEC_TOKOV || tokovi.containsKey(id)) { napaka(peer, id, "busy"); return }

        pool.execute {
            try {
                val kandidati = poti.zaznane().filter { politika.copy(porabljenoBajtov = mobilni.get()).dovoljena(it) }
                val pot = kandidati.firstOrNull { it.id == zahtevanaPot }
                    ?: kandidati.firstOrNull { it.vrsta != VrstaInternetPoti.CELLULAR }
                    ?: kandidati.firstOrNull()
                    ?: throw IllegalStateException("no_path")
                val network = poti.omrezje(pot.id) ?: throw IllegalStateException("path_lost")
                val naslov = razresiCilj(network, host)
                val socket = Socket()
                network.bindSocket(socket)
                socket.tcpNoDelay = true
                socket.soTimeout = 30_000
                socket.connect(InetSocketAddress(naslov, port), 10_000)
                val tok = Tok(id, peer, socket, pot)
                tokovi[id] = tok
                poslji(JSONObject().put("type", "internet.opened").put("target", peer).put("stream_id", id).put("path_id", pot.id))
                beri(tok)
            } catch (t: Throwable) {
                napaka(peer, id, t.message ?: "connect_failed")
                zapri(id, "connect_failed", false)
            }
        }
    }

    private fun podatki(msg: JSONObject) {
        val id = msg.optString("stream_id", "")
        val peer = msg.optString("sender", "")
        val tok = tokovi[id] ?: return
        if (tok.peer != peer) return
        val raw = try { Base64.decode(msg.optString("data", ""), Base64.NO_WRAP) } catch (_: Throwable) { return }
        if (raw.isEmpty() || raw.size > NAJVEC_KOS) { zapri(id, "invalid_chunk"); return }
        if (tok.zaprt) return
        tok.pisalec.execute {
            try {
                tok.socket.getOutputStream().write(raw)
                tok.socket.getOutputStream().flush()
                stej(tok.pot, raw.size.toLong())
            } catch (_: Throwable) { zapri(id, "write_failed") }
        }
    }

    private fun beri(tok: Tok) {
        val buf = ByteArray(NAJVEC_KOS)
        try {
            val input = tok.socket.getInputStream()
            while (!tok.zaprt) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                stej(tok.pot, n.toLong())
                val ok = poslji(JSONObject().put("type", "internet.data").put("target", tok.peer)
                    .put("stream_id", tok.id).put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)))
                if (!ok) break
            }
        } catch (_: Throwable) { }
        finally { zapri(tok.id, "eof") }
    }

    private fun stej(pot: InternetPot, n: Long) {
        if (pot.vrsta != VrstaInternetPoti.CELLULAR) return
        novMesec()
        val skupaj = mobilni.addAndGet(n)
        prefs.edit().putLong("cellular_bytes", skupaj).apply()
        val meja = politika.omejitevBajtov
        if (meja > 0 && skupaj >= meja) {
            tokovi.values.filter { it.pot.vrsta == VrstaInternetPoti.CELLULAR }.forEach { zapri(it.id, "cellular_limit") }
        }
    }

    private fun razresiCilj(network: Network, host: String): InetAddress {
        val vsi = network.getAllByName(host)
        return vsi.firstOrNull { varenInternetniNaslov(it) } ?: throw SecurityException("private_destination")
    }

    private fun napaka(peer: String, id: String, razlog: String) {
        if (peer.isNotBlank()) poslji(JSONObject().put("type", "internet.error").put("target", peer).put("stream_id", id).put("reason", razlog.take(80)))
    }

    private fun zapri(id: String, razlog: String, obvesti: Boolean = true) {
        val tok = tokovi.remove(id) ?: return
        tok.zaprt = true
        try { tok.socket.close() } catch (_: Throwable) { }
        tok.pisalec.shutdownNow()
        if (obvesti) poslji(JSONObject().put("type", "internet.close").put("target", tok.peer).put("stream_id", id).put("reason", razlog))
    }

    private fun zapriVse(razlog: String) = tokovi.keys.toList().forEach { zapri(it, razlog) }
    override fun close() { zapriVse("shutdown"); pool.shutdownNow(); poti.close() }

    companion object {
        const val NAJVEC_TOKOV = 8; const val NAJVEC_KOS = 24 * 1024
    }
}
