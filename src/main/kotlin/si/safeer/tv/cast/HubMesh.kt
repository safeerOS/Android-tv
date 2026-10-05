package si.safeer.tv.cast

import android.content.Context
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Link Mesh (docs/LINK-MESH.md): nas Hub se sam poveze s Hubi drugih naprav iz kroga zaupanja.
 *
 * Hub na tej napravi tece vedno (brez izvolitve in umika). Tu poiscemo druge Hube (mDNS z `mesh=mesh1`),
 * preverimo, da njihovo potrdilo nosi kljuc clana kroga, se prijavimo s podpisom nasega kljuca in odpremo
 * sosednjo povezavo. Vse naprej naredi HubUsmerjevalnik (mesh.devices, mesh.route, mesh.trust).
 *
 * Za vsak par nastane ena povezava: prvi klice manjsi id; vecji klice sam sele, ce ga manjsi dolgo ne doseze.
 *
 * Global Link: kadar soseda neposredno ni (naprava je zunaj doma), ga po [RELE_PO_NEUSPEHIH] neuspelih klicih
 * poklicemo prek link.safeer.si (GlobalLink.naslov). Rele prenasa samo sifrirane bajte; pripeti kljuc iz kroga
 * in prijava s podpisom ostaneta enaka kot v LAN.
 */
object HubMesh {
    private const val TAG = "SafeerMesh"
    private const val VECJI_CAKA_MS = 40_000L
    private const val KLJUC_ZNANI = "mesh_znani"
    private const val VRATA = 8990
    private const val NAJVEC_ZNANIH = 32

    private val klicem: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val prvicVideni = ConcurrentHashMap<String, Long>()
    /** Sosed, ki nas je zavrnil: do kdaj ga ne klicemo (premor raste do 10 min). */
    private val zavrnjen = ConcurrentHashMap<String, Pair<Long, Long>>()
    private const val PREMOR_MS = 30_000L
    private const val NAJDALJSI_PREMOR_MS = 600_000L
    /** Global Link: zaporedni neuspehi neposrednih klicev in premor po neuspelem klicu prek releja (kvota). */
    private val neuspehi = ConcurrentHashMap<String, Int>()
    private val premorReleja = ConcurrentHashMap<String, Pair<Long, Long>>()
    private const val RELE_PO_NEUSPEHIH = 2
    private const val PREMOR_RELEJA_MS = 60_000L
    /** Sosed, ki ga nismo dosegli: do kdaj ga ne klicemo (premor raste do 10 min); oglas mDNS premor izbrise. */
    private val nedosegljivDo = ConcurrentHashMap<String, Pair<Long, Long>>()

    /** Ali naslov kaze na to napravo (127.x, ::1, localhost) - tam je lokalni konec releja, ne sosed. */
    internal fun jeZanka(naslov: String): Boolean {
        val gostitelj = try { java.net.URI(if (naslov.contains("://")) naslov else "wss://$naslov").host.orEmpty() } catch (_: Throwable) { naslov }
            .trim('[', ']')
        return gostitelj == "localhost" || gostitelj == "::1" || gostitelj == "0:0:0:0:0:0:0:1" || gostitelj.startsWith("127.")
    }

    /** Ali naj ta klic gre prek releja (vrne naslov lokalnih vrat releja) ali null. */
    private fun naslovReleja(app: Context, h: HubDiscovery.NajdeniHub, zdaj: Long = System.currentTimeMillis()): String? {
        if (!si.safeer.tv.link.GlobalLink.vklopljen(app)) return null
        if ((premorReleja[h.id]?.first ?: 0L) > zdaj) return null
        if (si.safeer.tv.link.GlobalLink.osnovniId(h.id) == null) return null
        val naslov = si.safeer.tv.link.GlobalLink.naslov(app, h.naslov, h.id, prekRele = true)
        return naslov.takeIf { it != h.naslov && jeZanka(it) }
    }

    private fun relejNiUspel(id: String) {
        val prej = premorReleja[id]?.second ?: 0L
        val premor = (if (prej == 0L) PREMOR_RELEJA_MS else prej * 2).coerceAtMost(NAJDALJSI_PREMOR_MS)
        premorReleja[id] = (System.currentTimeMillis() + premor) to premor
        Log.i(TAG, "${id}: prek releja ni dosegljiv; znova cez ${premor / 1000} s")
    }

    /** Link Mesh je vklopljen (izklop samo za primerjavo: nastavitev link_mesh = false). */
    fun vklopljen(context: Context): Boolean =
        context.getSharedPreferences("safeer_cast_prefs", Context.MODE_PRIVATE).getBoolean("link_mesh", true)

    /**
     * Zapomnjeni naslovi sosedov (id -> wss naslov): za naprave, ki jih mDNS ne vidi (pozarni zid,
     * izolacija) in za hiter ponovni priklop po ponovnem zagonu. Dohodna povezava da samo IP.
     */
    fun zapomni(context: Context, id: String, naslov: String) {
        if (id.isBlank() || naslov.isBlank() || jeZanka(naslov)) return   // 127.0.0.1 je rele, ne sosed
        val url = if (naslov.startsWith("wss://")) naslov else "wss://$naslov:$VRATA/cast/ws"
        val p = context.getSharedPreferences("safeer_cast_prefs", Context.MODE_PRIVATE)
        val znani = try { JSONObject(p.getString(KLJUC_ZNANI, "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
        val star = znani.optString(id)
        // Naslov iz odhodne povezave (z vrati) ima prednost pred samim IP iz dohodne.
        if (star == url || (!naslov.startsWith("wss://") && star.contains("//$naslov:"))) return
        znani.put(id, url)
        while (znani.length() > NAJVEC_ZNANIH) znani.remove(znani.keys().next())
        p.edit().putString(KLJUC_ZNANI, znani.toString()).apply()
    }

    /** Oglasi iz mDNS, dopolnjeni z zapomnjenimi naslovi sosedov, ki jih mDNS ta hip ne vidi. */
    /** Sosed se je povezal (v katero koli smer): premor po neuspelih klicih zanj izbrisemo. */
    fun sosedTu(id: String) { nedosegljivDo.remove(id); neuspehi.remove(id) }

    /** Pozabi zapomnjeni naslov soseda (naslov zdaj pripada drugi napravi ali je naprava umaknjena). */
    fun pozabi(context: Context, id: String) {
        val p = context.getSharedPreferences("safeer_cast_prefs", Context.MODE_PRIVATE)
        val znani = try { JSONObject(p.getString(KLJUC_ZNANI, "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
        if (!znani.has(id)) return
        znani.remove(id)
        p.edit().putString(KLJUC_ZNANI, znani.toString()).apply()
    }

    fun zDopolnitvijo(context: Context, hubi: List<HubDiscovery.NajdeniHub>): List<HubDiscovery.NajdeniHub> {
        // Sosed, ki se spet oglasa po mDNS, je ocitno tu: premor po neuspelih klicih zanj ne velja vec.
        for (h in hubi) if (h.mesh == HubUsmerjevalnik.MESH) { zapomni(context, h.id, h.naslov); nedosegljivDo.remove(h.id) }
        val videni = hubi.map { it.id }.toSet()
        val znani = try {
            JSONObject(context.getSharedPreferences("safeer_cast_prefs", Context.MODE_PRIVATE).getString(KLJUC_ZNANI, "{}") ?: "{}")
        } catch (_: Throwable) { JSONObject() }
        val dodatni = znani.keys().asSequence().filter { it !in videni }
            .map { HubDiscovery.NajdeniHub(znani.optString(it), "", it, 0, "", HubUsmerjevalnik.MESH) }.toList()
        return hubi + dodatni
    }

    /** Sosedje iz prejsnjega teka so ze prebrani (enkrat na zagon procesa). */
    @Volatile private var poZagonuVzeto = false

    /**
     * Id-ji sosedov, ki smo jih poznali v prejsnjem teku - samo ob prvem klicu po zagonu procesa, potem prazno.
     * Te HubKrmilnik takoj po zagonu poklice sam (IzvolitevHuba.pocakamNaSoseda), se pred iskanjem oglasov mDNS.
     */
    fun vzemiPoZagonu(context: Context): Set<String> {
        if (poZagonuVzeto) return emptySet()
        synchronized(this) {
            if (poZagonuVzeto) return emptySet()
            poZagonuVzeto = true
            return try {
                JSONObject(context.getSharedPreferences("safeer_cast_prefs", Context.MODE_PRIVATE).getString(KLJUC_ZNANI, "{}") ?: "{}")
                    .keys().asSequence().toSet()
            } catch (_: Throwable) { emptySet() }
        }
    }

    /** Iz oglasov izbere Hube, ki jih moramo zdaj poklicati. */
    fun kandidati(context: Context, u: HubUsmerjevalnik, hubi: List<HubDiscovery.NajdeniHub>, zdaj: Long = System.currentTimeMillis(),
                  poZagonu: Set<String> = emptySet()): List<HubDiscovery.NajdeniHub> {
        val jaz = u.lastniId
        val povezani = u.sosedjeIdji().toSet()
        val krog = KrogNaprave.krog(context)
        return hubi.filter { h ->
            if (h.id.isBlank() || h.id == jaz || h.mesh != HubUsmerjevalnik.MESH || h.id in povezani || h.id in klicem) return@filter false
            if (krog.clanZaId(h.id) == null) return@filter false
            if ((zavrnjen[h.id]?.first ?: 0L) > zdaj) return@filter false
            if ((nedosegljivDo[h.id]?.first ?: 0L) > zdaj) return@filter false
            val prvic = prvicVideni.getOrPut(h.id) { zdaj }
            // Manjsi id klice prvi; pocakamo nanj - razen v prvem krogu po nasem zagonu.
            !IzvolitevHuba.pocakamNaSoseda(h.id, jaz, zdaj - prvic, VECJI_CAKA_MS, poZagonu)
        }
    }

    fun poklici(context: Context, u: HubUsmerjevalnik, h0: HubDiscovery.NajdeniHub, prekReleja: Boolean = false) {
        val app = context.applicationContext
        // Preizkus (nastavitev "tudi doma prek interneta"): vedno prek releja.
        if (!prekReleja && si.safeer.tv.link.GlobalLink.samoRele(app) && naslovReleja(app, h0) != null) {
            poklici(app, u, h0, prekReleja = true); return
        }
        val h = if (prekReleja) (naslovReleja(app, h0)?.let { h0.copy(naslov = it) } ?: return) else h0
        if (!klicem.add(h.id)) return
        val kljuc = KrogNaprave.kljucHuba(app, h.id)
        if (kljuc == null) { klicem.remove(h.id); return }
        /** Klic ni prisel do soseda (brez odgovora ali napacen kljuc): stejemo in po potrebi poskusimo prek releja. */
        fun nedosegljiv(videniKljuc: String?) {
            klicem.remove(h.id)
            if (prekReleja) { relejNiUspel(h.id); return }
            if (videniKljuc != null && videniKljuc != kljuc) {
                // Na tem naslovu se oglasa DRUGA naprava (npr. telefon po ponovni namestitvi z novim kljucem):
                // stara identiteta tu ne zivi vec - naslov pozabimo in je ne klicemo, dokler je mDNS spet ne oglasi.
                // Brez tega bi vsaka naprava v krogu vsakih 20 s trkala na tujo napravo, ta pa bi vsakic zavrnila potrdilo.
                pozabi(app, h.id)
                nedosegljivDo[h.id] = (System.currentTimeMillis() + NAJDALJSI_PREMOR_MS) to NAJDALJSI_PREMOR_MS
                Log.i(TAG, "${h.id}: na ${h.naslov} je druga naprava (${try { KrogZaupanja.idIzKljuca(videniKljuc) } catch (_: Throwable) { "?" }}); naslov pozabljen")
                return
            }
            val n = (neuspehi[h.id] ?: 0) + 1
            neuspehi[h.id] = n
            // Vsak nadaljnji klic pocaka dlje (30 s ... 10 min); oglas mDNS premor takoj izbrise (zDopolnitvijo).
            val prej = nedosegljivDo[h.id]?.second ?: 0L
            val premor = (if (prej == 0L) PREMOR_MS else prej * 2).coerceAtMost(NAJDALJSI_PREMOR_MS)
            nedosegljivDo[h.id] = (System.currentTimeMillis() + premor) to premor
            if (n >= RELE_PO_NEUSPEHIH && naslovReleja(app, h0) != null) poklici(app, u, h0, prekReleja = true)
        }
        // Zaupanje: kljuc v potrdilu = kljuc tega clana v krogu (oglas mDNS ne velja nic).
        val (graditelj, zaupnik) = HubTls.okhttp(OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(10, TimeUnit.SECONDS), null, kljuc)
        val client = graditelj.build()
        val osnova = h.naslov.replace(Regex("^wss"), "https").substringBefore("/cast/ws")
        fun klic(pot: String, telo: JSONObject, naprej: (Int, String) -> Unit) {
            client.newCall(Request.Builder().url(osnova + pot)
                .post(telo.toString().toRequestBody("application/json".toMediaTypeOrNull())).build())
                .enqueue(object : Callback {
                    override fun onFailure(call: Call, e: java.io.IOException) { naprej(0, "") }
                    override fun onResponse(call: Call, response: Response) { response.use { naprej(it.code, it.body?.string().orEmpty()) } }
                })
        }
        val jaz = u.lastniId
        klic("/cast/auth/challenge", JSONObject().put("device_id", jaz)) { koda, telo ->
            val j = try { JSONObject(telo) } catch (_: Throwable) { JSONObject() }
            val nonce = j.optString("nonce")
            val odtis = j.optString("fp").ifBlank { zaupnik.videni.orEmpty() }
            if (koda == 0) { Log.i(TAG, "${h.id}: ni dosegljiv${if (prekReleja) " prek releja" else ""}"); nedosegljiv(zaupnik.videniKljuc); return@klic }
            if (koda != 200 || nonce.isBlank() || odtis.isBlank()) {
                // 401 = sosed nas (se) nima v svojem krogu (npr. telefon po ponovni seznanitvi): ne trkamo vsakih 20 s,
                // ampak s premorom, ki raste; oglas mDNS ali njegov klic k nam ga izbrise.
                Log.i(TAG, "${h.id}: izziva ni ($koda)"); klicem.remove(h.id)
                val prej = nedosegljivDo[h.id]?.second ?: 0L
                val premor = (if (prej == 0L) PREMOR_MS else prej * 2).coerceAtMost(NAJDALJSI_PREMOR_MS)
                nedosegljivDo[h.id] = (System.currentTimeMillis() + premor) to premor
                return@klic
            }
            // Sosed je na tem naslovu dosegljiv (pravi kljuc v potrdilu): neposredno ali prek releja.
            if (prekReleja) premorReleja.remove(h.id) else { neuspehi.remove(h.id); nedosegljivDo.remove(h.id) }
            val podpis = try { KrogNaprave.podpisPrijave(jaz, odtis, nonce) } catch (_: Throwable) { klicem.remove(h.id); return@klic }
            klic("/cast/auth/ticket", JSONObject().put("device_id", jaz).put("nonce", nonce).put("signature", podpis)
                .put("platform", HubKrmilnik.platforma(app))) { koda2, telo2 ->
                val j2 = try { JSONObject(telo2) } catch (_: Throwable) { JSONObject() }
                val vstopnica = j2.optString("ticket")
                if (koda2 != 200 || vstopnica.isBlank()) { Log.i(TAG, "${h.id}: vstopnice ni ($koda2)"); klicem.remove(h.id); return@klic }
                odpri(app, u, h, client, "${h.naslov}?ticket=$vstopnica")
            }
        }
    }

    private fun odpri(app: Context, u: HubUsmerjevalnik, h: HubDiscovery.NajdeniHub, client: OkHttpClient, url: String) {
        val povezava = Sosednja(h.naslov)
        val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                povezava.ws = webSocket
                // Prijava gre prva: sosed mora vedeti, da smo Hub, preden dobi karkoli drugega.
                webSocket.send(JSONObject().put("id", System.currentTimeMillis().toString()).put("type", "cast.register")
                    .put("payload", JSONObject().put("device_id", u.lastniId).put("name", HubKrmilnik.imeHuba(app))
                        .put("role", "hub").put("capabilities", org.json.JSONArray().put(HubUsmerjevalnik.MESH))
                        .put("protocol", "1")).toString())
                if (!u.dodajSoseda(h.id, povezava, u.lastniId)) {
                    webSocket.cancel()
                    klicem.remove(h.id)
                    return
                }
                klicem.remove(h.id)
                // Premor po zavrnitvi izbrisemo sele ob sprejemu (prvo sporocilo, ki ni zavrnitev):
                // zavrnitev pride po odprtju, zato bi ga brisanje tu vedno vrnilo na 30 s.
                Log.i(TAG, "Sosed ${h.id} (${h.naslov}${if (jeZanka(h.naslov)) ", Global Link" else ""})")
            }
            @Volatile private var sprejet = false
            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = JsonLahki.objekt(text)
                if (j?.niz("type") == "cast.ack" && j.niz("status") == "rejected") {
                    // Sosed nas ne sprejme (npr. ze ima povezavo, ki jo je odprl sam): vticnico zapremo takoj.
                    Log.i(TAG, "${h.id} nas ni sprejel (${j.niz("error_code").orEmpty()})")
                    val prej = zavrnjen[h.id]?.second ?: 0L
                    val premor = (if (prej == 0L) PREMOR_MS else prej * 2).coerceAtMost(NAJDALJSI_PREMOR_MS)
                    zavrnjen[h.id] = (System.currentTimeMillis() + premor) to premor
                    webSocket.cancel(); return
                }
                if (!sprejet) { sprejet = true; zavrnjen.remove(h.id) }
                try { u.obdelaj(povezava, text) } catch (e: Throwable) { SafeerLog.napaka("Mesh", "obdelaj", e) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!povezava.zaprta && code != 1000) Log.i(TAG, "Sosed ${h.id}: povezavo je zaprl ($code ${reason.take(60)})")
                konec()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Vzrok v dnevnik: brez njega se padca sosednje povezave med prenosom ne da pojasniti.
                if (!povezava.zaprta) Log.i(TAG, "Sosed ${h.id}: povezava prekinjena (${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)})")
                konec()
            }
            private fun konec() {
                klicem.remove(h.id)
                povezava.zaprta = true
                u.odklopi(povezava)
            }
        })
        povezava.ws = ws
    }

    /** Povezava, ki smo jo odprli mi; OkHttp posilja v svoji niti, zato pocasen sosed ne ustavi Huba. */
    private class Sosednja(override val naslov: String) : HubUsmerjevalnik.Odjemalec {
        @Volatile var ws: WebSocket? = null
        @Volatile var zaprta = false
        override fun poslji(besedilo: String) {
            val w = ws ?: throw IllegalStateException("ni odprta")
            if (zaprta || !w.send(besedilo)) throw IllegalStateException("sosednja povezava je zaprta")
        }
        override fun zapri(koda: Int, razlog: String) {
            zaprta = true
            try { ws?.close(koda.coerceIn(1000, 4999).let { if (it == 1004 || it == 1005 || it == 1006) 1000 else it }, razlog.take(100)) } catch (_: Throwable) { }
        }
    }
}
