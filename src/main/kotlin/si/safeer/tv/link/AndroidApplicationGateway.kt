package si.safeer.tv.link

import android.content.Context
import android.content.SharedPreferences
import android.net.Network
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Safeer Internet Gateway na telefonu (ponudnik), protokol 2 - docs/INTERNET-GATEWAY.md.
 *
 * Ne uporablja tetheringa in ne spreminja Android routinga. Vsak oddaljeni TCP tok postane nova
 * vticnica, ki jo Safeer sam odpre in jo pred connect() priveze na izbrano Android Network (mobilno
 * ali Wi-Fi). Do druge Safeer naprave vsebina potuje po obstojecem TLS WebSocketu Linka.
 *
 * Kdo sme: naprava mora biti v krogu zaupanja IN uporabnik ji mora na tem telefonu izrecno dovoliti
 * ([InternetDovoljenja]). Zmoznost `internet.gateway` pomeni samo, da telefon to zna.
 *
 * Nadzor pretoka je opisan v [InternetProtokol]: brez njega velik prenos napolni izhodno vrsto
 * sredisca in ta telefon odklopi iz Linka.
 */
class AndroidApplicationGateway(
    context: Context,
    private val poti: AndroidInternetPoti,
    /** Ze sestavljeno sporocilo (JSON z `id`) v Link; za kose tokov, kjer je sestavljanje z org.json predrago. */
    private val posljiBesedilo: ((String) -> Boolean)? = null,
    private val poslji: (JSONObject) -> Boolean
) : Closeable {
    data class Stanje(val dovoljeno: Boolean, val mobilniBajti: Long, val aktivniTokovi: Int)

    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val shramba = PrefsShramba(prefs)
    val dovoljenja = InternetDovoljenja(shramba) { System.currentTimeMillis() }
    private val poraba = InternetPoraba(shramba) { System.currentTimeMillis() }
    private val tokovi = ConcurrentHashMap<String, Tok>()
    private val proracuni = ConcurrentHashMap<String, InternetProracun>()
    /** Naprave, ki so v tem zagonu vprasale ali odprle tok: njim sporocimo spremembo nastavitev in dovoljenja. */
    private val odjemalci = ConcurrentHashMap<String, String>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "safeer-gateway").apply { isDaemon = true } }
    private val urnik = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "safeer-gateway-urnik").apply { isDaemon = true } }
    private val mobilnihTokov = AtomicInteger(0)
    private val stevecSporocil = java.util.concurrent.atomic.AtomicLong(0)
    /** Zadnja doba vsakega odjemalca (`epoch`): zamenja jo ob ponovnem zagonu ali izgubi Linka. */
    private val dobe = ConcurrentHashMap<String, Long>()
    /** Tokovi, ki smo jih nedavno zaprli ali zavrnili: zanje drugi strani ne odgovarjamo znova. */
    private val nedavni = object : LinkedHashMap<String, Long>(64, 0.75f, false) {
        override fun removeEldestEntry(najstarejsi: MutableMap.MutableEntry<String, Long>?): Boolean = size > NEDAVNIH_TOKOV
    }

    @Volatile var politika = preberiPolitiko(); private set
    @Volatile var dovoljeno: Boolean = prefs.getBoolean("gateway_enabled", false); private set

    private class Tok(val id: String, val peer: String, val socket: Socket, val pot: InternetPot, val doba: Long) {
        val okno = OknoPosiljanja()
        val potrjevanje = PotrjevanjePrejema()
        val vhod = ArrayDeque<ByteArray>()
        var vhodBajtov = 0
        /** Koliko bajtov toka je slo v vsako smer: vsak kos nosi svoj odmik (`off`), da se luknja v toku opazi. */
        var poslanoSkupaj = 0L
        var prejetoSkupaj = 0L
        @Volatile var zaprt = false
        @Volatile var konecOdjemalca = false     // odjemalec je poslal internet.eof
        @Volatile var konecStreznika = false     // streznik je zaprl svojo smer, internet.eof je poslan
        @Volatile var dejavnost = System.currentTimeMillis()
        val kljucnica = Object()
        fun nedejaven() = System.currentTimeMillis() - dejavnost > InternetProtokol.NEDEJAVNOST_MS
    }

    private fun preberiPolitiko() = InternetPolitika(
        dovoliMobilne = prefs.getBoolean("allow_cellular", false),
        dovoliRoaming = prefs.getBoolean("allow_roaming", false),
        omejitevBajtov = prefs.getLong("cellular_limit", 0L).coerceAtLeast(0L),
        porabljenoBajtov = poraba.mesecno(danes())
    )

    private fun trenutnaPolitika() = politika.copy(porabljenoBajtov = poraba.mesecno(danes()))

    /** Nastavitve ali dovoljenja so se spremenila (zaslon nastavitev, vprasanje): preberi in povej odjemalcem. */
    fun osveziIzShrambe() {
        dovoljeno = prefs.getBoolean("gateway_enabled", false)
        politika = preberiPolitiko()
        if (!dovoljeno) zapriVse("disabled")
        else {
            if (!politika.dovoliMobilne) {
                zapriKjer("mobile_off") { it.pot.vrsta == VrstaInternetPoti.CELLULAR }
                poti.sprostiMobilno()
            }
            // Naprava, ki ji je uporabnik dovoljenje vzel, takoj izgubi odprte tokove.
            zapriKjer("denied") { dovoljenja.stanje(fizicnaNapravaInterneta(it.peer)) != InternetDovoljenja.Stanje.DOVOLJENO }
        }
        objaviStanje()
    }

    fun stanje() = Stanje(dovoljeno, poraba.mesecno(danes()), tokovi.size)

    /** Vrne true, ce je bilo sporocilo gateway protokola in je obdelano. */
    fun obdelaj(sporocilo: JSONObject): Boolean {
        val peer = sporocilo.optString("sender", "")
        when (sporocilo.optString("type")) {
            "internet.query" -> {
                if (peer.isNotBlank()) {
                    novaDoba(peer, sporocilo.optLong("epoch", 0L))
                    val razlog = preveriOdjemalca(peer, sporocilo.optString("sender_name", ""))
                    posljiStanje(peer, razlog)
                }
            }
            "internet.open" -> {
                novaDoba(peer, sporocilo.optLong("epoch", 0L))
                odpri(sporocilo, peer)
            }
            "internet.data" -> podatki(sporocilo, peer)
            "internet.window" -> {
                val tok = tokOd(sporocilo, peer)
                if (tok == null) neznanTok(sporocilo, peer)
                else {
                    val (b, o) = tok.okno.potrdi(sporocilo.optLong("bytes", 0L))
                    if (o > 0) proracun(tok.peer).sprosti(b, o)
                }
            }
            "internet.eof" -> {
                val tok = tokOd(sporocilo, peer)
                if (tok == null) neznanTok(sporocilo, peer)
                else synchronized(tok.kljucnica) { tok.konecOdjemalca = true; tok.kljucnica.notifyAll() }
            }
            "internet.close" -> tokOd(sporocilo, peer)?.let { zapri(it, "peer_closed", obvesti = false) }
            else -> return false
        }
        return true
    }

    /**
     * Odjemalec se je vrnil z drugo dobo: znova se je zagnal ali je izgubil Link. Njegovi prejsnji tokovi
     * nimajo vec lastnika; do izteka nedejavnosti bi drzali proracun naprave in novi tokovi ne bi mogli posiljati.
     */
    private fun novaDoba(peer: String, doba: Long) {
        if (peer.isBlank() || doba == 0L) return
        val prej = dobe.put(peer, doba)
        if (prej == null || prej == doba) return
        val stari = tokovi.values.filter { it.peer == peer && it.doba != doba }
        if (stari.isEmpty()) return
        Log.i(TAG, "odjemalec se je vrnil z novo dobo: zapiram ${stari.size} starih tokov")
        stari.forEach { zapri(it, "restart", obvesti = false) }
    }

    /** Sporocilo za tok, ki ga ne poznamo (npr. po nasem ponovnem zagonu): drugi strani povemo, naj ga zapre. Enkrat na tok. */
    private fun neznanTok(msg: JSONObject, peer: String) {
        val id = msg.optString("stream_id", "")
        if (peer.isBlank() || id.length !in 8..96) return
        synchronized(nedavni) { if (nedavni.put(id, System.currentTimeMillis()) != null) return }
        poslji(JSONObject().put("type", "internet.close").put("target", peer).put("stream_id", id).put("reason", "gone"))
    }

    private fun tokOd(msg: JSONObject, peer: String): Tok? = tokovi[msg.optString("stream_id", "")]?.takeIf { it.peer == peer }

    private fun proracun(peer: String) = proracuni.getOrPut(peer) { InternetProracun() }

    // ------------------------------------------------------------------ kdo sme

    /** Null, ce naprava sme uporabljati internet; sicer razlog. Neodloceni napravi pokaze vprasanje. */
    private fun preveriOdjemalca(peer: String, ime: String): String? {
        if (!dovoljeno) return "disabled"
        val clan = try { si.safeer.tv.cast.KrogNaprave.krog(ctx).clanZaId(peer) } catch (_: Throwable) { null }
        if (peer.isBlank() || clan == null) return "not_trusted"
        val naprava = fizicnaNapravaInterneta(peer)
        odjemalci[peer] = naprava
        return when (dovoljenja.stanje(naprava)) {
            InternetDovoljenja.Stanje.DOVOLJENO -> null
            InternetDovoljenja.Stanje.ZAVRNJENO -> "denied"
            else -> {
                // Ime za vprasanje: kot ga pove sredisce, sicer ime iz kroga zaupanja (sporocilo prek sosednjega
                // sredisca imena ne nosi), nikoli goli id, ce se da.
                val prikaz = ime.ifBlank { clan.ime }.ifBlank { naprava }
                if (dovoljenja.zabeleziProsnjo(naprava, prikaz)) InternetVprasanje.pokazi(ctx, naprava, prikaz)
                "permission_required"
            }
        }
    }

    private fun opisStanja(razlog: String?): JSONObject {
        val o = JSONObject().put("protocol", InternetProtokol.RAZLICICA).put("enabled", dovoljeno)
            .put("permission", when (razlog) {
                null -> "allowed"; "permission_required" -> "pending"; "denied" -> "denied"; "not_trusted" -> "not_trusted"; else -> "unknown"
            })
        if (razlog != null) return o       // kdor nima dovoljenja, o omrezjih telefona ne izve nicesar
        val p = trenutnaPolitika()
        val zaznane = try { poti.zaznane() } catch (_: Throwable) { emptyList() }
        val seznam = JSONArray()
        for (pot in zaznane) seznam.put(JSONObject().put("id", pot.id).put("kind", vrstaPoti(pot.vrsta))
            .put("validated", pot.preverjena).put("metered", pot.merjena).put("roaming", pot.roaming).put("allowed", p.dovoljena(pot)))
        val dan = danes()
        return o.put("paths", seznam)
            .put("cellular", JSONObject().put("allowed", p.dovoliMobilne).put("roaming_allowed", p.dovoliRoaming)
                .put("limit_bytes", p.omejitevBajtov).put("used_month", poraba.mesecno(dan)).put("used_today", poraba.dnevno(dan)))
            .put("streams", JSONObject().put("active", tokovi.size).put("max", InternetProtokol.NAJVEC_TOKOV))
            .put("window", InternetProtokol.OKNO_TOKA).put("chunk", InternetProtokol.NAJVEC_KOS)
    }

    private fun posljiStanje(peer: String, razlog: String?) {
        poslji(JSONObject().put("type", "internet.status").put("target", peer).put("payload", opisStanja(razlog)))
    }

    /** Vsem, ki so v tem zagonu vprasali: nastavitve ali dovoljenje so se spremenili. */
    fun objaviStanje() {
        for ((peer, naprava) in odjemalci) {
            val razlog = if (!dovoljeno) "disabled" else when (dovoljenja.stanje(naprava)) {
                InternetDovoljenja.Stanje.DOVOLJENO -> null
                InternetDovoljenja.Stanje.ZAVRNJENO -> "denied"
                else -> "permission_required"
            }
            posljiStanje(peer, razlog)
        }
    }

    // ------------------------------------------------------------------ odpiranje

    private fun odpri(msg: JSONObject, peer: String) {
        val id = msg.optString("stream_id", "")
        val host = msg.optString("host", "")
        val port = msg.optInt("port", 0)
        val zahtevanaPot = msg.optString("path_id", "")
        val doba = msg.optLong("epoch", 0L)
        if (peer.isBlank() || id.length !in 8..96) return
        if (msg.optInt("v", 1) < InternetProtokol.RAZLICICA) { napaka(peer, id, "old_requester"); return }
        preveriOdjemalca(peer, msg.optString("sender_name", ""))?.let { napaka(peer, id, it); return }
        if (host.length !in 1..253) { napaka(peer, id, "dns_failed"); return }
        if (!dovoljenaVrata(port)) { napaka(peer, id, "port_blocked"); return }
        if (tokovi.size >= InternetProtokol.NAJVEC_TOKOV || tokovi.containsKey(id)
            || tokovi.values.count { it.peer == peer } >= InternetProtokol.NAJVEC_TOKOV_NAPRAVE) { napaka(peer, id, "busy"); return }

        pool.execute {
            var socket: Socket? = null
            try {
                var izbira = izberiPot(poti.zaznane(), trenutnaPolitika(), zahtevanaPot)
                if (izbira.pot == null && izbira.razlog in setOf("no_mobile", "no_path") && politika.dovoliMobilne) {
                    // Mobilno omrezje je ob delujocem Wi-Fi ugasnjeno: zahtevamo ga in pocakamo, da se vzpostavi.
                    poti.pocakajMobilno(ROK_MOBILNE_MS)
                    izbira = izberiPot(poti.zaznane(), trenutnaPolitika(), zahtevanaPot)
                }
                val pot = izbira.pot ?: run { napaka(peer, id, izbira.razlog); return@execute }
                val network = poti.omrezje(pot.id) ?: run { napaka(peer, id, "no_path"); return@execute }
                if (pot.vrsta == VrstaInternetPoti.CELLULAR) poti.mobilnaVRabi()
                val naslov = try { razresiCilj(network, host) }
                    catch (_: UnknownHostException) { null.also { napaka(peer, id, "dns_failed") } }
                    catch (_: SecurityException) { null.also { napaka(peer, id, "private_destination") } }
                if (naslov == null) return@execute
                val s = Socket()
                socket = s
                network.bindSocket(s)
                s.tcpNoDelay = true
                try { s.connect(InetSocketAddress(naslov, port), ROK_POVEZAVE_MS) }
                catch (_: SocketTimeoutException) { zapriTiho(s); napaka(peer, id, "timeout"); return@execute }
                catch (_: IOException) { zapriTiho(s); napaka(peer, id, "connect_failed"); return@execute }
                s.soTimeout = BUDILKA_MS
                val tok = Tok(id, peer, s, pot, doba)
                if (!dovoljeno || tokovi.putIfAbsent(id, tok) != null) { zapriTiho(s); napaka(peer, id, "busy"); return@execute }
                if (pot.vrsta == VrstaInternetPoti.CELLULAR) mobilnihTokov.incrementAndGet()
                Log.i(TAG, "tok ${id.takeLast(6)} odprt (${vrstaPoti(pot.vrsta)}, vrata $port), tokov ${tokovi.size}")
                poslji(JSONObject().put("type", "internet.opened").put("target", peer).put("stream_id", id)
                    .put("path_id", pot.id).put("kind", vrstaPoti(pot.vrsta)))
                Thread({ pisi(tok) }, "safeer-gateway-w").apply { isDaemon = true }.start()
                beri(tok)
            } catch (t: Throwable) {
                Log.w(TAG, "odpiranje toka: ${t.javaClass.simpleName}")
                socket?.let { zapriTiho(it) }
                tokovi[id]?.let { zapri(it, "error") } ?: napaka(peer, id, "connect_failed")
            }
        }
    }

    private fun razresiCilj(network: Network, host: String): InetAddress {
        val vsi = network.getAllByName(host)
        return vsi.firstOrNull { varenInternetniNaslov(it) } ?: throw SecurityException("private_destination")
    }

    // ------------------------------------------------------------------ prenos

    /** Streznik -> odjemalec. Bere samo, kolikor dovolita okno toka in proracun naprave. */
    private fun beri(tok: Tok) {
        val buf = ByteArray(InternetProtokol.NAJVEC_KOS)
        try {
            val input = tok.socket.getInputStream()
            while (!tok.zaprt) {
                if (!tok.okno.pocakaj { tok.zaprt || tok.nedejaven() }) {
                    if (!tok.zaprt) zapri(tok, "idle")
                    return
                }
                val n = try { input.read(buf) } catch (_: SocketTimeoutException) {
                    if (tok.nedejaven()) { zapri(tok, "idle"); return }
                    continue
                }
                if (n < 0) break
                if (n == 0) continue
                if (!proracun(tok.peer).zakupi(n) { tok.zaprt || tok.nedejaven() }) {
                    if (!tok.zaprt) zapri(tok, "idle")
                    return
                }
                tok.okno.poslal(n)
                if (tok.zaprt) {
                    // Tok se je zaprl med zakupom: zapri() tega okvirja ni vec videl, zato ga proracunu vrnemo tu.
                    val (b, o) = tok.okno.sprostiVse()
                    if (o > 0) proracun(tok.peer).sprosti(b, o)
                    return
                }
                tok.dejavnost = System.currentTimeMillis()
                stej(tok.pot, n.toLong())
                val ok = posljiKos(tok, Base64.encodeToString(buf, 0, n, Base64.NO_WRAP))
                tok.poslanoSkupaj += n
                if (!ok) { zapri(tok, "link", obvesti = false); return }
            }
            if (tok.zaprt) return
            // Streznik je koncal svojo smer. Odjemalec lahko se posilja (pol-zaprtje).
            tok.konecStreznika = true
            poslji(JSONObject().put("type", "internet.eof").put("target", tok.peer).put("stream_id", tok.id))
            val oboje = synchronized(tok.kljucnica) { tok.konecOdjemalca && tok.vhod.isEmpty() }
            if (oboje) zapri(tok, "done")
        } catch (_: IOException) {
            zapri(tok, "reset")
        } catch (t: Throwable) {
            Log.w(TAG, "branje toka: ${t.javaClass.simpleName}")
            zapri(tok, "error")
        }
    }

    /** Kos toka kot rocno sestavljen JSON: org.json bi 32 KiB niza ubezal znak za znakom (in `/` zapisal kot `\\/`). */
    private fun posljiKos(tok: Tok, base64: String): Boolean {
        val besedilo = posljiBesedilo ?: return poslji(JSONObject().put("type", "internet.data").put("target", tok.peer)
            .put("stream_id", tok.id).put("off", tok.poslanoSkupaj).put("data", base64))
        val ubezi = si.safeer.tv.cast.JsonLahki::ubezi
        val sb = StringBuilder(base64.length + 160)
        sb.append("{\"id\":\"ig").append(stevecSporocil.incrementAndGet()).append("\",\"type\":\"internet.data\",\"target\":\"")
            .append(ubezi(tok.peer)).append("\",\"stream_id\":\"").append(ubezi(tok.id)).append("\",\"off\":").append(tok.poslanoSkupaj)
            .append(",\"data\":\"").append(base64).append("\"}")
        return besedilo(sb.toString())
    }

    private fun podatki(msg: JSONObject, peer: String) {
        val tok = tokOd(msg, peer) ?: run { neznanTok(msg, peer); return }
        val raw = try { Base64.decode(msg.optString("data", ""), Base64.NO_WRAP) } catch (_: Throwable) { zapri(tok, "invalid_chunk"); return }
        if (raw.isEmpty() || raw.size > InternetProtokol.NAJVEC_KOS) { zapri(tok, "invalid_chunk"); return }
        val odmik = msg.optLong("off", -1L)
        var luknja = false
        val prevec = synchronized(tok.kljucnica) {
            if (tok.zaprt || tok.konecOdjemalca) return
            if (odmik >= 0 && odmik != tok.prejetoSkupaj) { luknja = true; return@synchronized false }
            tok.prejetoSkupaj += raw.size
            tok.vhod.addLast(raw)
            tok.vhodBajtov += raw.size
            tok.kljucnica.notifyAll()
            tok.vhodBajtov > InternetProtokol.NAJVEC_NEPORABLJENO
        }
        // Kos se je na poti izgubil (povezava med srediscema se je vmes obnovila): tok bi bil pokvarjen.
        if (luknja) { zapri(tok, "gap"); return }
        // Odjemalec posilja mimo okna in streznik ne sprejema: pomnilnik ne sme rasti.
        if (prevec) zapri(tok, "window")
    }

    /** Odjemalec -> streznik: po vrsti, s potrditvami. */
    private fun pisi(tok: Tok) {
        try {
            val izhod = tok.socket.getOutputStream()
            while (true) {
                val kos: ByteArray?
                val prazna: Boolean
                var ponovi = -1L
                synchronized(tok.kljucnica) {
                    while (tok.vhod.isEmpty() && !tok.konecOdjemalca && !tok.zaprt) {
                        tok.kljucnica.wait(1000)
                        if (tok.vhod.isEmpty() && !tok.konecOdjemalca && !tok.zaprt) {
                            ponovi = tok.potrjevanje.ponovitev()
                            if (ponovi >= 0) break
                        }
                    }
                    if (tok.zaprt) return
                    kos = tok.vhod.removeFirstOrNull()
                    if (kos != null) tok.vhodBajtov -= kos.size
                    prazna = tok.vhod.isEmpty()
                }
                if (kos == null && ponovi >= 0) {
                    // Tih tok: zadnjo potrditev ponovimo. Ce se je prejsnja izgubila, odjemalec spet posilja;
                    // ce toka ne pozna vec (ponovni zagon), odgovori z internet.close.
                    poslji(JSONObject().put("type", "internet.window").put("target", tok.peer)
                        .put("stream_id", tok.id).put("bytes", ponovi))
                    continue
                }
                if (kos == null) break            // odjemalec je koncal in vse je zapisano
                izhod.write(kos)
                izhod.flush()
                tok.dejavnost = System.currentTimeMillis()
                stej(tok.pot, kos.size.toLong())
                val javi = tok.potrjevanje.porabil(kos.size, prazna)
                if (javi >= 0) poslji(JSONObject().put("type", "internet.window").put("target", tok.peer)
                    .put("stream_id", tok.id).put("bytes", javi))
            }
            try { tok.socket.shutdownOutput() } catch (_: IOException) { }
            if (tok.konecStreznika) zapri(tok, "done")
        } catch (_: IOException) {
            zapri(tok, "write_failed")
        } catch (_: InterruptedException) {
            zapri(tok, "error")
        }
    }

    // ------------------------------------------------------------------ poraba in konec

    private fun danes(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    private fun stej(pot: InternetPot, n: Long) {
        if (pot.vrsta != VrstaInternetPoti.CELLULAR) return
        poti.mobilnaVRabi()
        val skupaj = poraba.dodaj(n, danes())
        val meja = politika.omejitevBajtov
        if (meja > 0 && skupaj >= meja) zapriKjer("limit") { it.pot.vrsta == VrstaInternetPoti.CELLULAR }
    }

    private fun napaka(peer: String, id: String, razlog: String) {
        if (peer.isNotBlank()) poslji(JSONObject().put("type", "internet.error").put("target", peer).put("stream_id", id).put("reason", razlog.take(80)))
    }

    private fun zapriTiho(s: Socket) { try { s.close() } catch (_: Throwable) { } }

    private fun konecMobilnega(pot: InternetPot) {
        if (pot.vrsta != VrstaInternetPoti.CELLULAR) return
        poti.mobilnaVRabi()
        if (mobilnihTokov.decrementAndGet() <= 0) {
            mobilnihTokov.set(0)
            poraba.shrani()
            // Zahteva za mobilno omrezje ostane se nekaj minut (naslednja povezava je takoj), potem jo sprostimo.
            try {
                urnik.schedule({ if (mobilnihTokov.get() == 0) poti.sprostiNerabljenoMobilno() },
                    AndroidInternetPoti.MOBILNA_OSTANE_MS + 5_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Throwable) { }
        }
    }

    private fun zapri(tok: Tok, razlog: String, obvesti: Boolean = true) {
        if (!tokovi.remove(tok.id, tok)) return
        synchronized(nedavni) { nedavni[tok.id] = System.currentTimeMillis() }
        synchronized(tok.kljucnica) { tok.zaprt = true; tok.vhod.clear(); tok.vhodBajtov = 0; tok.kljucnica.notifyAll() }
        val nepotrjeno = tok.okno.nepotrjeno
        val (b, o) = tok.okno.sprostiVse()
        if (o > 0) proracun(tok.peer).sprosti(b, o)
        if (razlog != "done" && razlog != "peer_closed") {
            val p = proracun(tok.peer)
            Log.i(TAG, "tok ${tok.id.takeLast(6)} konec: $razlog (dol ${tok.poslanoSkupaj} B, gor ${tok.prejetoSkupaj} B, nepotrjeno $nepotrjeno B; na poti ${p.bajtov} B / ${p.okvirjev} okvirjev)")
        }
        zapriTiho(tok.socket)
        konecMobilnega(tok.pot)
        if (obvesti) poslji(JSONObject().put("type", "internet.close").put("target", tok.peer).put("stream_id", tok.id).put("reason", razlog))
    }

    private fun zapriKjer(razlog: String, pogoj: (Tok) -> Boolean) = tokovi.values.filter(pogoj).forEach { zapri(it, razlog) }
    private fun zapriVse(razlog: String) = zapriKjer(razlog) { true }

    /** Povezava v Link se je prekinila: kosi na poti so izgubljeni, zato tokovi ne morejo naprej. */
    fun povezavaIzgubljena() = tokovi.values.toList().forEach { zapri(it, "link", obvesti = false) }

    override fun close() {
        zapriVse("shutdown")
        poraba.shrani()
        pool.shutdownNow()
        urnik.shutdownNow()
        poti.close()
    }

    /** SharedPreferences kot [ShrambaVrednosti]; stare vrednosti (Int, Long iz protokola 1) bere kot niz. */
    private class PrefsShramba(private val p: SharedPreferences) : ShrambaVrednosti {
        override fun niz(kljuc: String): String? = try { p.all[kljuc]?.toString() } catch (_: Throwable) { null }
        override fun nastavi(kljuc: String, vrednost: String?) {
            // Kljuc je lahko iz protokola 1 se stevilo: putString ga zamenja, ker edit najprej odstrani starega.
            p.edit().apply { remove(kljuc); if (vrednost != null) putString(kljuc, vrednost) }.apply()
        }
        override fun kljuci(predpona: String): List<String> = try { p.all.keys.filter { it.startsWith(predpona) } } catch (_: Throwable) { emptyList() }
    }

    companion object {
        private const val TAG = "SafeerGateway"
        const val PREFS = "safeer_internet_gateway"
        const val ROK_POVEZAVE_MS = 10_000
        const val ROK_MOBILNE_MS = 8_000L
        const val BUDILKA_MS = 15_000
        const val NEDAVNIH_TOKOV = 256

        fun vrstaPoti(v: VrstaInternetPoti): String = when (v) {
            VrstaInternetPoti.WIFI -> "wifi"
            VrstaInternetPoti.CELLULAR -> "cellular"
            VrstaInternetPoti.ETHERNET -> "ethernet"
            VrstaInternetPoti.VPN -> "vpn"
            VrstaInternetPoti.DRUGO -> "other"
        }

        /** Dovoljenja brez tekocega prehoda (zaslon nastavitev, vprasanje): ista shramba. */
        fun dovoljenja(context: Context): InternetDovoljenja =
            InternetDovoljenja(PrefsShramba(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))) { System.currentTimeMillis() }

        /** Poraba mobilnih podatkov za zaslon nastavitev: (danes, ta mesec). */
        fun poraba(context: Context): Pair<Long, Long> {
            val p = InternetPoraba(PrefsShramba(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))) { System.currentTimeMillis() }
            val c = Calendar.getInstance()
            val dan = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
            return p.dnevno(dan) to p.mesecno(dan)
        }
    }
}
