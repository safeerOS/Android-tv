package si.safeer.tv.cast

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Streznik Safeer Huba v brskalniku: majhen HTTP streznik z nadgradnjo na WebSocket.
 *
 * Zakaj lastna izvedba in ne knjiznica: v APK-ju ne zelimo nove odvisnosti samo zaradi
 * tega, okhttp pa zna biti odjemalec in ne streznik. Protokol je RFC 6455, isti, ki ga
 * govori ze Hub na racunalniku, zato televizor in telefon ostaneta nespremenjena -
 * govorimo isti jezik, le streznik stoji drugje.
 *
 * Razred namenoma ne pozna Androidovih storitev in ne uporabniskega vmesnika, da ga je
 * mogoce preizkusiti sam zase.
 */
class HubStreznik(
    private val zeljenaVrata: Int = PRIVZETA_VRATA,
    /** Odgovori na navadne zahteve HTTP. Vrne null, ce poti ne pozna (404). */
    private val naZahtevo: (Zahteva) -> Odgovor?,
    /** Ali je ta zahteva za nadgradnjo v WebSocket dovoljena; vrne razlog zavrnitve ali null. */
    private val preveriVstopnico: (Zahteva) -> String?,
    /** Nova odprta povezava. */
    private val naPovezavo: (Povezava) -> Unit,
    /**
     * Zahteve, ki potrebujejo tok (prenos datoteke, deljenje zaslona): dobijo vhod in izhod
     * vticnice, same preberejo telo in same odgovorijo. Vrne true, ce je zahtevo prevzel.
     * Telo takih zahtev se NE bere vnaprej - lahko je vec sto MB ali pa tece brez konca.
     */
    private val naTok: ((Zahteva, InputStream, OutputStream, Socket) -> Boolean)? = null,
    /**
     * TLS: tovarna streznih vticnic s kljucem Huba. Brez nje streznik govori goli HTTP -
     * to je dovoljeno samo v preizkusih na JVM; na napravah Hub vedno tece s TLS, ker po
     * omrezju potujejo zetoni, datoteke in zaslon.
     */
    private val tlsTovarna: javax.net.ssl.SSLServerSocketFactory? = null,
    /**
     * Obrambni mehanizem ([HubObramba]): steje sovrazne dogodke po viru in vir zapre. Zaprt vir ne pride niti do
     * rokovanja TLS. Brez njega (preizkusi) streznik dela kot prej.
     */
    private val obramba: HubObramba? = null,
    /** Vrsta jedra za sprejem povezav (listen); zamenljiva samo za meritev v preizkusu (ObremenitevTest). */
    private val cakalnaVrsta: Int = CAKALNA_VRSTA,
    /** Po tolikem casu brez prebrane zahteve je nepreverjena vticnica tiha in jo nova sme izriniti ([ROK_TIHE_MS]). */
    private val rokTiheMs: Long = ROK_TIHE_MS
) {

    data class Zahteva(
        val metoda: String,
        val pot: String,
        val poizvedba: Map<String, String>,
        val glave: Map<String, String>,
        val telo: String,
        val odjemalec: String
    )

    data class Odgovor(
        val koda: Int,
        val telo: String,
        val vrsta: String = "application/json; charset=utf-8"
    )

    private val tece = AtomicBoolean(false)
    private var vticnica: ServerSocket? = null
    private val povezave = CopyOnWriteArrayList<Povezava>()

    /** Koliko bajtov trenutno zadrzujejo vse povezave skupaj (sestavljanje sporocil). */
    private val skupajZadrzano = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Nepreverjene vticnice: od sprejema do prebrane zahteve (pri WebSocket do veljavne vstopnice, pri toku do predaje
     * HubTokovi, ki preveri svoj kljuc), v vrstnem redu sprejema. Samo zanje veljata fiksni meji
     * [NAJVEC_NEPREVERJENIH] in [NAJVEC_NEPREVERJENIH_NA_VIR] (isto kot core/link_neposredno.py na racunalniku).
     *
     * Do 0.5.90 je bila meja za vse: 24 hkrati strezenih vticnic (tudi clanov) in 16 povezav WebSocket - 17. naprava
     * kroga je dobila 503, val prijav cez 24 pa »prevec hkratnih zahtev«. Clane zdaj ustavijo samo viri naprave
     * ([ViriNaprave]; sredisce: nova naprava v HubUsmerjevalnik.registriraj), nepreverjene pa ta meja: pocasen ali tih
     * odjemalec (odpre vticnico in molci) ne more zasesti niti in deskriptorjev, ki so za clane.
     */
    private val nepreverjene = LinkedHashMap<Socket, Nepreverjena>()

    private class Nepreverjena(val vir: String, val ob: Long)

    /** Meritev: najvec hkrati nepreverjenih vticnic od zagona (preizkusi, ObremenitevTest). */
    @Volatile
    var najvecNepreverjenih = 0
        private set

    /** Meritev: koliko tihih nepreverjenih je izrinila nova povezava. */
    @Volatile
    var izrinjenih = 0
        private set

    /** Meritev: najdaljsi cas od sprejema do prebrane zahteve (ali konca) med vticnicami, ki niso bile izrinjene. */
    @Volatile
    var najdljeNepreverjenaMs = 0L
        private set

    fun steviloNepreverjenih(): Int = synchronized(nepreverjene) { nepreverjene.size }

    /** Meritev: koliko cakajocih sporocil je novejse nadomestilo v izhodnih vrstah (Povezava.poslji(nadomesti=)). */
    private val nadomesceno = java.util.concurrent.atomic.AtomicLong(0)

    fun nadomestenih(): Long = nadomesceno.get()

    /**
     * Telo odgovora 503, kadar ta naprava nima virov za novo vticnico (koda ni_virov). Krmilnik ga nastavi z besedilom v
     * jeziku uporabnika in imenom naprave (HubUsmerjevalnik.odgovorBrezVirov): odjemalec tako pove, katera naprava je
     * zasedena, ne ugiba ob tiho zaprti vticnici.
     */
    @Volatile
    var teloBrezVirov: () -> String = { "{\"napaka\":\"" + ViriNaprave.BESEDILO_BREZ_VIROV + "\",\"code\":\"" + ViriNaprave.KODA + "\"}" }

    /** Vrata, na katerih streznik dejansko poslusa (lahko se razlikujejo od zeljenih). */
    @Volatile
    var vrata: Int = 0
        private set

    /** Ali streznik govori TLS (na napravah vedno). */
    val jeTls: Boolean get() = tlsTovarna != null

    fun zazeni(): Boolean {
        if (tece.get()) return true
        val vt = odpriVticnico() ?: return false
        vticnica = vt
        vrata = vt.localPort
        tece.set(true)
        zazeniNit("safeer-hub-accept") { zankaSprejemanja(vt) }
        Log.i(OZNAKA, "Hub posluša na vratih $vrata")
        return true
    }

    fun ustavi() {
        if (!tece.getAndSet(false)) return
        for (p in povezave) {
            try {
                p.zapri(1001, "hub se ustavlja")
            } catch (_: Exception) {
            }
        }
        povezave.clear()
        try {
            vticnica?.close()
        } catch (_: Exception) {
        }
        vticnica = null
        vrata = 0
        Log.i(OZNAKA, "Hub ustavljen")
    }

    fun teceZdaj(): Boolean = tece.get()

    fun steviloPovezav(): Int = povezave.size

    /** Ali ima naprava s tega naslova pri nas odprto povezavo (prijava z vstopnico je uspela)? Za obrambo je zaupana. */
    fun imaPovezavoZ(vir: String): Boolean = vir.isNotEmpty() && povezave.any { it.zahteva.odjemalec == vir }

    /**
     * Najprej poskusimo obicajna vrata, da je naslov predvidljiv. Ce so zasedena (npr. na
     * napravi ze tece kaj drugega), vzamemo prosta - naslov ionako objavimo prek mDNS, zato
     * uporabniku ni treba vedeti niti imena niti stevilke.
     */
    private fun odpriVticnico(): ServerSocket? {
        for (kandidat in listOf(zeljenaVrata, 0)) {
            try {
                val naslov = InetAddress.getByName("0.0.0.0")
                val t = tlsTovarna ?: return ServerSocket(kandidat, cakalnaVrsta, naslov)
                val v = t.createServerSocket(kandidat, cakalnaVrsta, naslov) as javax.net.ssl.SSLServerSocket
                // Samo TLS 1.2/1.3; brez preverjanja odjemalskih potrdil (identiteto naprav dajo zetoni).
                v.useClientMode = false
                v.enabledProtocols = v.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
                v.needClientAuth = false
                return v
            } catch (e: Exception) {
                Log.w(OZNAKA, "Vrat $kandidat ni bilo mogoče odpreti: ${e.message}")
            }
        }
        return null
    }

    /**
     * Niti zaganjamo s privzetim skladom. Poskus, da bi ga zmanjsali, se je v preizkusu
     * koncal s StackOverflowError: sklad je navidezni pomnilnik, ki se dodeljuje sproti,
     * zato neaktivna nit v resnici porabi le nekaj kilobajtov - prihranek bi bil navidezen,
     * tveganje sesutja pa pravo. Pomnilnik zares omejujeta stevilo povezav in velikost
     * sporocil, ne velikost sklada.
     */
    private fun zazeniNit(ime: String, blok: () -> Unit) {
        val nit = Thread(null, { blok() }, ime)
        nit.isDaemon = true
        nit.start()
    }

    private fun zankaSprejemanja(vt: ServerSocket) {
        while (tece.get()) {
            val odjemalec = try {
                vt.accept()
            } catch (e: Exception) {
                if (!tece.get() || vt.isClosed) break
                // Brez prostega deskriptorja (EMFILE) accept pade, poslusalna vticnica pa ostane odprta. Prej se je zanka
                // tu koncala in sredisce do ponovnega zagona ni sprejelo nikogar vec; zdaj kratek premor in naprej.
                Log.w(OZNAKA, "Napaka pri sprejemanju: ${e.message}")
                ViriNaprave.zabelezi(ViriNaprave.DESKRIPTORJI, "sprejem")
                try { Thread.sleep(PREMOR_PO_NAPAKI_MS) } catch (_: InterruptedException) { break }
                continue
            }
            val vir = odjemalec.inetAddress?.hostAddress ?: ""
            // Zaprt vir (HubObramba) ne pride niti do rokovanja TLS: brez potrdila, brez odgovora.
            if (obramba != null && !obramba.dovoli(vir)) {
                zapriTiho(odjemalec)
                continue
            }
            // Viri naprave: brez deskriptorja ali pomnilnika nad rezervo nove vticnice ne strezemo (kdo je clan, se ve
            // sele po zahtevi). Ze povezani delajo naprej; uporabnik izve razlog (ViriNaprave.zabelezi -> obvestilo).
            val razlog = ViriNaprave.preveri(1, 0L)
            if (razlog.isNotEmpty()) {
                ViriNaprave.zabelezi(razlog, "sprejem")
                // Odjemalec dobi jasen odgovor (503 ni_virov z imenom te naprave), ne tiho zaprte vticnice. Strezba je
                // kratka ([ROK_BREZ_VIROV_MS]) in steje med nepreverjene; ce sistem niti ne da, vticnico samo zapremo.
                for (tiha in sprejmiNepreverjeno(odjemalec, vir)) zapriTiho(tiha)
                val zagnana = ViriNaprave.zazeniNit("safeer-hub-brez-virov", "sprejem") {
                    try { odgovoriBrezVirov(odjemalec) } catch (_: Exception) { } finally { preverjena(odjemalec); zapriTiho(odjemalec) }
                }
                if (!zagnana) { preverjena(odjemalec); zapriTiho(odjemalec) }
                continue
            }
            for (tiha in sprejmiNepreverjeno(odjemalec, vir)) zapriTiho(tiha)
            val zagnana = ViriNaprave.zazeniNit("safeer-hub-odjemalec", "sprejem") {
                try {
                    postrezi(odjemalec)
                } catch (e: Exception) {
                    Log.w(OZNAKA, "Povezava končana z napako: ${e.message}")
                } finally {
                    preverjena(odjemalec)
                    try {
                        if (!odjemalec.isClosed) odjemalec.close()
                    } catch (_: Exception) {
                    }
                }
            }
            if (!zagnana) {
                preverjena(odjemalec)
                zapriTiho(odjemalec)
            }
        }
    }

    private fun zapriTiho(vticnica: Socket) {
        try { vticnica.close() } catch (_: Exception) { }
    }

    /**
     * Nova vticnica je nepreverjena. Nikoli ne caka in ni zavrnjena, ker so mesta polna: ce je nepreverjenih z istega vira
     * vec kot [NAJVEC_NEPREVERJENIH_NA_VIR] ali vseh vec kot [NAJVEC_NEPREVERJENIH], izpade najstarejsa TIHA (dlje kot
     * [rokTiheMs] brez prebrane zahteve) - najprej z istega vira, sicer z vira, ki ima najvec tihih. Svezih ne izrinemo:
     * ob valu prijav (200 clanov z enega naslova, ObremenitevTest) so vse sveze in vse pridejo noter, tudi ce rokovanje TLS
     * na pocasnem televizorju caka na procesor. Vrne vticnice, ki jih je treba zapreti (zunaj kljucavnice); izrinjena med
     * rokovanjem steje v obrambi kot neuspelo rokovanje, kot prej tiha povezava po roku.
     */
    private fun sprejmiNepreverjeno(vticnica: Socket, vir: String): List<Socket> = synchronized(nepreverjene) {
        val zdaj = System.currentTimeMillis()
        nepreverjene[vticnica] = Nepreverjena(vir, zdaj)
        if (nepreverjene.size > najvecNepreverjenih) najvecNepreverjenih = nepreverjene.size
        val meja = zdaj - rokTiheMs
        val izrinjene = ArrayList<Socket>()
        fun izrini(odVira: String): Boolean {
            val k = nepreverjene.entries.firstOrNull { it.value.vir == odVira && it.value.ob <= meja }?.key ?: return false
            nepreverjene.remove(k)
            izrinjene.add(k)
            return true
        }
        while (nepreverjene.values.count { it.vir == vir } > NAJVEC_NEPREVERJENIH_NA_VIR) if (!izrini(vir)) break
        while (nepreverjene.size > NAJVEC_NEPREVERJENIH) {
            val vir2 = nepreverjene.values.filter { it.ob <= meja }.groupingBy { it.vir }.eachCount()
                .maxByOrNull { it.value }?.key ?: break
            if (!izrini(vir2)) break
        }
        izrinjenih += izrinjene.size
        izrinjene
    }

    /** Vticnica ni (vec) nepreverjena: clan z veljavno vstopnico, tok, ali pa je koncana. */
    private fun preverjena(vticnica: Socket) {
        synchronized(nepreverjene) {
            val n = nepreverjene.remove(vticnica) ?: return
            val ms = System.currentTimeMillis() - n.ob
            if (ms > najdljeNepreverjenaMs) najdljeNepreverjenaMs = ms
        }
    }

    /** Vticnica, ki je zaradi virov ne strezemo: prebere zahtevo in odgovori 503 s [teloBrezVirov], v roku. */
    private fun odgovoriBrezVirov(vticnica: Socket) {
        vticnica.soTimeout = ROK_BREZ_VIROV_MS
        if (vticnica is javax.net.ssl.SSLSocket) vticnica.startHandshake()
        val vhod = vticnica.getInputStream().buffered()
        preberiZahtevo(vhod, vticnica)
        val telo = try { teloBrezVirov() } catch (_: Throwable) { "{\"code\":\"" + ViriNaprave.KODA + "\"}" }
        posljiOdgovor(vticnica.getOutputStream(), Odgovor(503, telo))
    }

    private fun postrezi(vticnica: Socket) {
        vticnica.soTimeout = BRALNI_TIMEOUT_MS
        vticnica.tcpNoDelay = true
        val vir = vticnica.inetAddress?.hostAddress ?: ""
        if (obramba != null && vticnica is javax.net.ssl.SSLSocket) {
            // Rokovanje izrecno: neuspelo (ni TLS, pregledovalnik vrat, tiha povezava) je sovrazen dogodek vira.
            try {
                vticnica.startHandshake()
            } catch (e: Exception) {
                obramba.dogodek(vir, HubObramba.ROKOVANJE)
                return
            }
        }
        val vhod = vticnica.getInputStream().buffered()
        val izhod = vticnica.getOutputStream()

        val zahteva = preberiZahtevo(vhod, vticnica) ?: return

        val nadgradnja = zahteva.glave["upgrade"]?.lowercase() == "websocket"
        if (nadgradnja) {
            val razlog = preveriVstopnico(zahteva)
            if (razlog != null) {
                // Povezavo zavrnemo se pred rokovanjem: naprava brez veljavne vstopnice
                // ne sme nikoli priti do sporocil.
                obramba?.dogodek(vir, HubObramba.BREZ_ZAUPANJA)
                posljiOdgovor(izhod, Odgovor(403, "{\"napaka\":\"$razlog\"}"))
                return
            }
            // Veljavna vstopnica: vir je clan kroga. Stevilo clanov ni omejeno (prej 16 povezav): novo napravo sprejme ali
            // zavrne HubUsmerjevalnik.registriraj po virih naprave (ViriNaprave), z jasnim razlogom.
            obramba?.zaupaj(vir)
            preverjena(vticnica)
            val kljuc = zahteva.glave["sec-websocket-key"]
            if (kljuc.isNullOrBlank()) {
                posljiOdgovor(izhod, Odgovor(400, "{\"napaka\":\"manjka kljuc\"}"))
                return
            }
            rokovanje(izhod, kljuc)
            val povezava = Povezava(vticnica, vhod, izhod, zahteva)
            povezave.add(povezava)
            try {
                naPovezavo(povezava)
                povezava.zankaBranja()
            } finally {
                povezave.remove(povezava)
            }
            return
        }

        if (jeTokovnaPot(zahteva.pot)) {
            // Tok (datoteka, zaslon, gledalec) preveri svoj kljuc ali zeton takoj (HubTokovi) in brez njega konca; z njim
            // je dolg - ne sme zasedati mest nepreverjenih. Hkratne tokove omejujejo viri naprave, ne stevilka.
            preverjena(vticnica)
            val prevzeto = try {
                naTok?.invoke(zahteva, vhod, izhod, vticnica) ?: false
            } catch (e: Exception) {
                Log.w(OZNAKA, "Tok ${zahteva.pot} se je koncal z napako: ${e.message}")
                true
            }
            if (prevzeto) return
            obramba?.dogodek(vir, HubObramba.TIPANJE)
            posljiOdgovor(izhod, Odgovor(404, "{\"napaka\":\"ni te poti\"}"))
            return
        }

        val odgovor = try {
            naZahtevo(zahteva) ?: Odgovor(404, "{\"napaka\":\"ni te poti\"}")
        } catch (e: Exception) {
            Log.w(OZNAKA, "Napaka pri obdelavi ${zahteva.pot}: ${e.message}")
            Odgovor(500, "{\"napaka\":\"notranja napaka\"}")
        }
        obramba?.let { oceni(it, vir, zahteva, odgovor) }
        posljiOdgovor(izhod, odgovor)
    }

    /** Obramba: napaka odgovora je sovrazen dogodek vira, uspesna prijava ga naredi zaupanega. */
    private fun oceni(o: HubObramba, vir: String, zahteva: Zahteva, odgovor: Odgovor) {
        if (odgovor.koda >= 400) {
            // GET /cast/health brez zetona (401) je prepoznava sredisca - tako ga preveri vsaka naprava, preden mu zaupa.
            if (zahteva.pot == "/cast/health" && zahteva.metoda == "GET") return
            o.dogodek(vir, HubObramba.vrstaNapake(odgovor.koda, odgovor.telo))
            return
        }
        if (zahteva.metoda != "POST") return
        when (zahteva.pot) {
            "/cast/auth/ticket", "/cast/ticket" -> o.zaupaj(vir)
            // Zacetek seznanitve s kodo uporabniku pokaze kodo: cetrti v minuti vir zapre.
            "/cast/pair/start" -> o.dogodek(vir, HubObramba.ZACETEK_SEZNANITVE)
            // Odgovor napravi pove, ali je njena koda prava (potrditev sredisca): vsak krog je poskus kode.
            "/cast/pair/spake" -> o.dogodek(vir, HubObramba.POSKUS_KODE)
            "/cast/pair/qr/start" -> o.dogodek(vir, HubObramba.SEZNANITEV)
        }
    }

    // ---------------------------------------------------------------- HTTP

    private fun preberiZahtevo(vhod: InputStream, vticnica: Socket): Zahteva? {
        val prva = preberiVrstico(vhod) ?: return null
        val deli = prva.split(" ")
        if (deli.size < 2) return null
        val metoda = deli[0].uppercase()
        val celotnaPot = deli[1]

        val glave = HashMap<String, String>()
        var stevec = 0
        while (true) {
            val vrstica = preberiVrstico(vhod) ?: break
            if (vrstica.isEmpty()) break
            if (++stevec > NAJVEC_GLAV) return null
            val dvopicje = vrstica.indexOf(':')
            if (dvopicje <= 0) continue
            glave[vrstica.substring(0, dvopicje).trim().lowercase()] =
                vrstica.substring(dvopicje + 1).trim()
        }

        val vprasajZaPot = celotnaPot.indexOf('?')
        val golaPot = if (vprasajZaPot >= 0) celotnaPot.substring(0, vprasajZaPot) else celotnaPot
        val tokovna = jeTokovnaPot(golaPot)

        val dolzina = if (tokovna) 0 else (glave["content-length"]?.toIntOrNull() ?: 0)
        if (dolzina > NAJVECJE_TELO) return null
        val telo = if (dolzina > 0) {
            val medpomnilnik = ByteArray(dolzina)
            var prebrano = 0
            while (prebrano < dolzina) {
                val n = vhod.read(medpomnilnik, prebrano, dolzina - prebrano)
                if (n < 0) break
                prebrano += n
            }
            String(medpomnilnik, 0, prebrano, Charsets.UTF_8)
        } else ""

        val vprasaj = celotnaPot.indexOf('?')
        val pot = if (vprasaj >= 0) celotnaPot.substring(0, vprasaj) else celotnaPot
        val poizvedba = if (vprasaj >= 0) razcleniPoizvedbo(celotnaPot.substring(vprasaj + 1)) else emptyMap()

        return Zahteva(
            metoda = metoda,
            pot = pot,
            poizvedba = poizvedba,
            glave = glave,
            telo = telo,
            // Naslov jemljemo iz povezave, nikoli iz tega, kar naprava pove o sebi.
            odjemalec = vticnica.inetAddress?.hostAddress ?: ""
        )
    }

    /** Poti, katerih telo je tok in ne kratko sporocilo. */
    private fun jeTokovnaPot(pot: String): Boolean =
        pot.startsWith("/cast/file") || pot.startsWith("/cast/screen") ||
            pot.startsWith("/cast/d/") || pot.startsWith("/cast/thumb/") ||
            pot.startsWith("/cast/live/") || pot.startsWith("/cast/magnet/")

    private fun preberiVrstico(vhod: InputStream): String? {
        val izpis = ByteArrayOutputStream()
        while (true) {
            val b = try {
                vhod.read()
            } catch (e: SocketTimeoutException) {
                return null
            }
            if (b < 0) return if (izpis.size() == 0) null else izpis.toString("UTF-8")
            if (b == '\n'.code) {
                var niz = izpis.toString("UTF-8")
                if (niz.endsWith("\r")) niz = niz.dropLast(1)
                return niz
            }
            if (izpis.size() > NAJVECJA_VRSTICA) return null
            izpis.write(b)
        }
    }

    private fun razcleniPoizvedbo(niz: String): Map<String, String> {
        val izid = HashMap<String, String>()
        for (par in niz.split("&")) {
            if (par.isEmpty()) continue
            val i = par.indexOf('=')
            if (i < 0) izid[odkodiraj(par)] = "" else izid[odkodiraj(par.substring(0, i))] =
                odkodiraj(par.substring(i + 1))
        }
        return izid
    }

    private fun odkodiraj(niz: String): String = try {
        java.net.URLDecoder.decode(niz, "UTF-8")
    } catch (_: Exception) {
        niz
    }

    private fun posljiOdgovor(izhod: OutputStream, odgovor: Odgovor) {
        val telo = odgovor.telo.toByteArray(Charsets.UTF_8)
        val glava = StringBuilder()
        glava.append("HTTP/1.1 ").append(odgovor.koda).append(" ").append(besedaKode(odgovor.koda)).append("\r\n")
        glava.append("Content-Type: ").append(odgovor.vrsta).append("\r\n")
        glava.append("Content-Length: ").append(telo.size).append("\r\n")
        // Hub je krajevna naprava; odgovori naj se nikjer ne shranjujejo.
        glava.append("Cache-Control: no-store\r\n")
        glava.append("Connection: close\r\n\r\n")
        izhod.write(glava.toString().toByteArray(Charsets.UTF_8))
        izhod.write(telo)
        izhod.flush()
    }

    private fun besedaKode(koda: Int): String = when (koda) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        429 -> "Too Many Requests"
        503 -> "Service Unavailable"
        else -> "Internal Server Error"
    }

    // ----------------------------------------------------------- WebSocket

    private fun rokovanje(izhod: OutputStream, kljuc: String) {
        val sprejem = MessageDigest.getInstance("SHA-1")
            .digest((kljuc.trim() + CAROBNI_NIZ).toByteArray(Charsets.US_ASCII))
        val zakodiran = vBase64(sprejem)
        val odgovor = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $zakodiran\r\n\r\n"
        izhod.write(odgovor.toByteArray(Charsets.US_ASCII))
        izhod.flush()
    }

    /**
     * Base64 po RFC 4648. Namenoma ne uporabljamo android.util.Base64: brez te vezi je
     * streznik mogoce pognati in preizkusiti v navadnem JVM, brez naprave.
     */
    private fun vBase64(podatki: ByteArray): String {
        val abeceda = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val izpis = StringBuilder()
        var i = 0
        while (i < podatki.size) {
            val b0 = podatki[i].toInt() and 0xFF
            val b1 = if (i + 1 < podatki.size) podatki[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < podatki.size) podatki[i + 2].toInt() and 0xFF else 0
            izpis.append(abeceda[b0 shr 2])
            izpis.append(abeceda[((b0 and 0x03) shl 4) or (b1 shr 4)])
            izpis.append(if (i + 1 < podatki.size) abeceda[((b1 and 0x0F) shl 2) or (b2 shr 6)] else '=')
            izpis.append(if (i + 2 < podatki.size) abeceda[b2 and 0x3F] else '=')
            i += 3
        }
        return izpis.toString()
    }

    /** En okvir, ki caka na pisalno nit povezave; [nadomesti]: vrsta, katere novejse sporocilo ga zamenja (ali null). */
    private class Izhod(val opkoda: Int, val podatki: ByteArray, val nadomesti: String? = null)

    /** Ena odprta povezava z napravo. */
    inner class Povezava(
        private val vticnica: Socket,
        private val vhod: InputStream,
        private val izhod: OutputStream,
        val zahteva: Zahteva
    ) {
        /** Ime naprave, ki ga vpise usmerjevalnik, ko se naprava predstavi. */
        @Volatile
        var imeNaprave: String = ""

        @Volatile
        var idNaprave: String = ""

        /** Zmoznosti, ki jih je naprava prijavila (npr. "cast", "sync"). */
        val zmoznosti = HashSet<String>()

        private val odprta = AtomicBoolean(true)

        /**
         * Izhodna vrsta povezave. Pisanje na vticnico je blokirajoce: naprava, ki svojih
         * podatkov ne bere (zamrznjena aplikacija, izgubljen WiFi), bi drzala nit, ki je
         * hotela poslati - in z njo vse ostale naprave, ker hub seznam objavi vsem zapored.
         * Zato posiljanje samo doda okvir v vrsto, pise pa ena sama nit te povezave.
         */
        private val kljucnicaVrste = Object()
        private val vrsta = ArrayDeque<Izhod>()
        private var bajtovVVrsti = 0L
        private var pisec: Thread? = null
        private var zaprtjeKoda = 1000
        private var zaprtjeRazlog = ""

        /**
         * Koliko bajtov ta povezava trenutno zadrzuje pri sestavljanju sporocila. Racun je
         * tudi skupen: do [SKUPNI_ZADRZEK_BREZ_MERITVE] brez meritve, nad tem samo, ce ima naprava
         * pomnilnik nad rezervo (ViriNaprave) - prej je bila to meja 1 MiB za vse clane skupaj
         * (pet clanov z velikim sporocilom hkrati in sesti je dobil 1013).
         */
        private var zadrzano = 0L

        private fun zadrziBajte(n: Int): Boolean {
            val skupaj = skupajZadrzano.addAndGet(n.toLong())
            if (skupaj > SKUPNI_ZADRZEK_BREZ_MERITVE) {
                val razlog = ViriNaprave.preveri(0, n.toLong())
                if (razlog.isNotEmpty()) {
                    skupajZadrzano.addAndGet(-n.toLong())
                    ViriNaprave.zabelezi(razlog, "sporocilo")
                    return false
                }
            }
            zadrzano += n
            return true
        }

        private fun sprostiZadrzek() {
            if (zadrzano > 0) {
                skupajZadrzano.addAndGet(-zadrzano)
                zadrzano = 0
            }
        }

        /** Kaj narediti s prejetim sporocilom; nastavi usmerjevalnik. */
        @Volatile
        var naSporocilo: ((String) -> Unit)? = null

        @Volatile
        var naZaprtje: (() -> Unit)? = null

        val naslov: String get() = zahteva.odjemalec

        fun jeOdprta(): Boolean = odprta.get() && !vticnica.isClosed

        /**
         * Postavi sporocilo v vrsto te povezave. Nikoli ne caka na omrezje: ce naprava ne bere
         * in se vrsta napolni, povezavo zapremo (naprava se lahko takoj vrne z novo povezavo).
         *
         * [nadomesti] (isto kot link_ws.Povezava.poslji na racunalniku): sporocilo te vrste, ki se caka v vrsti, novo
         * zamenja na istem mestu. Rabi ga samo SeznamSprememb.razposlji za cel seznam naprav STAREJSEMU prejemniku (brez
         * devdelta1) - novejsi cel seznam starega povsem nadomesti, ob valu prijav se jih sicer nabere do meje vrste in
         * naprava izpade. Prej je vrsta to ugibala po vsebini (vsak "cast.devices"), tudi pri prejemniku, ki zna spremembe:
         * posnetek je tam preskocil spremembo, ki je cakala za njim, in prejemnik je moral zahtevati nov posnetek.
         */
        fun poslji(besedilo: String, nadomesti: String? = null) {
            vVrsto(OPKODA_BESEDILO, besedilo.toByteArray(Charsets.UTF_8), nadomesti)
        }

        private fun vVrsto(opkoda: Int, podatki: ByteArray, nadomesti: String? = null) {
            if (!jeOdprta()) return
            var prepolna = false
            var brezPisca = false
            synchronized(kljucnicaVrste) {
                if (nadomesti != null) {
                    val stari = vrsta.indexOfFirst { it.nadomesti == nadomesti }
                    if (stari >= 0) {
                        bajtovVVrsti += podatki.size - vrsta[stari].podatki.size
                        vrsta[stari] = Izhod(opkoda, podatki, nadomesti)
                        nadomesceno.incrementAndGet()
                        return
                    }
                }
                if (vrsta.size >= NAJVEC_V_VRSTI || bajtovVVrsti + podatki.size > NAJVEC_BAJTOV_V_VRSTI) {
                    prepolna = true
                } else if (!zagotoviPisca()) {
                    brezPisca = true
                } else {
                    vrsta.addLast(Izhod(opkoda, podatki, nadomesti))
                    bajtovVVrsti += podatki.size
                    kljucnicaVrste.notifyAll()
                }
            }
            if (prepolna) {
                Log.w(OZNAKA, "Naprava ne bere (${imeNaprave.ifBlank { naslov }}); povezavo zapiram.")
                zapri(1011, "naprava ne bere")
            } else if (brezPisca) {
                // Sistem ne da niti (ViriNaprave je zabelezil pomanjkanje): 1013 = naj poskusi pozneje.
                zapri(1013, "ni virov")
            }
        }

        /** Nit, ki edina pise na vticnico te povezave; zazene se ob prvem sporocilu. False: sistem niti ne da. */
        private fun zagotoviPisca(): Boolean {
            if (pisec != null) return true
            val nit = Thread(null, { zankaPisanja() }, "safeer-hub-pisec")
            nit.isDaemon = true
            try {
                nit.start()
            } catch (_: Throwable) {
                ViriNaprave.zabelezi(ViriNaprave.NITI, "povezava")
                return false
            }
            pisec = nit
            return true
        }

        private fun zankaPisanja() {
            while (true) {
                var naslednji: Izhod? = null
                synchronized(kljucnicaVrste) {
                    while (vrsta.isEmpty() && odprta.get()) {
                        try {
                            kljucnicaVrste.wait(1_000)
                        } catch (_: InterruptedException) {
                            return
                        }
                    }
                    if (vrsta.isNotEmpty()) {
                        naslednji = vrsta.removeFirst()
                        bajtovVVrsti -= naslednji!!.podatki.size
                    }
                }
                val okvir = naslednji
                if (okvir == null) {
                    // Povezava se zapira in vrsta je prazna: posljemo se zakljucni okvir.
                    posljiZakljucek()
                    return
                }
                try {
                    zapisiOkvir(okvir.opkoda, okvir.podatki)
                } catch (e: Exception) {
                    Log.w(OZNAKA, "Pisanje ni uspelo: ${e.message}")
                    zapri(1011, "napaka pri pisanju")
                    return
                }
            }
        }

        private fun posljiZakljucek() {
            try {
                val telo = ByteArrayOutputStream()
                telo.write((zaprtjeKoda shr 8) and 0xFF)
                telo.write(zaprtjeKoda and 0xFF)
                telo.write(zaprtjeRazlog.toByteArray(Charsets.UTF_8))
                zapisiOkvir(OPKODA_ZAPRI, telo.toByteArray())
            } catch (_: Exception) {
            }
        }

        /**
         * Zapre povezavo. Zakljucni okvir poslje pisalna nit (edina pise na vticnico); ce se
         * je zataknila na napravi, ki ne bere, po kratkem roku vticnico zapremo brez njega -
         * zapiranje huba nikoli ne sme cakati na napravo.
         */
        fun zapri(koda: Int = 1000, razlog: String = "") {
            if (!odprta.getAndSet(false)) return
            sprostiZadrzek()
            var nit: Thread? = null
            synchronized(kljucnicaVrste) {
                zaprtjeKoda = koda
                zaprtjeRazlog = razlog
                nit = pisec
                kljucnicaVrste.notifyAll()
            }
            if (nit == null) {
                posljiZakljucek()   // nic ni bilo poslanega: pisca ni, okvir zapisemo sami
            } else if (nit !== Thread.currentThread()) {
                try {
                    nit!!.join(ZAPIRALNI_ROK_MS)
                } catch (_: InterruptedException) {
                }
            }
            try {
                vticnica.close()
            } catch (_: Exception) {
            }
            try {
                naZaprtje?.invoke()
            } catch (_: Exception) {
            }
        }

        /**
         * Streznik svojih okvirjev ne masklira (RFC 6455), odjemalcevi pa morajo biti
         * maskirani - neustrezen okvir zavrnemo, namesto da bi ga poskusali razumeti.
         */
        private fun zapisiOkvir(opkoda: Int, podatki: ByteArray) {
            val glava = ByteArrayOutputStream()
            glava.write(0x80 or opkoda)
            when {
                podatki.size < 126 -> glava.write(podatki.size)
                podatki.size <= 0xFFFF -> {
                    glava.write(126)
                    glava.write((podatki.size shr 8) and 0xFF)
                    glava.write(podatki.size and 0xFF)
                }
                else -> {
                    glava.write(127)
                    for (i in 7 downTo 0) glava.write(((podatki.size.toLong() shr (8 * i)) and 0xFF).toInt())
                }
            }
            izhod.write(glava.toByteArray())
            izhod.write(podatki)
            izhod.flush()
        }

        internal fun zankaBranja() {
            var zbrano = ByteArrayOutputStream()
            var zbranaOpkoda = -1
            var zadnjiPing = System.currentTimeMillis()
            var tihihKrogov = 0

            while (jeOdprta()) {
                val prvi = try {
                    vhod.read()
                } catch (e: SocketTimeoutException) {
                    // Tisina ni nujno napaka: posljemo ping in pocakamo se en krog.
                    val zdaj = System.currentTimeMillis()
                    if (zdaj - zadnjiPing > PING_VSAKIH_MS) {
                        zadnjiPing = zdaj
                        // Naprava, ki se po vec pingih ne oglasi, je najbrz izginila
                        // (izklopljen wifi, ugasnjen telefon). Taksne povezave ne drzimo
                        // odprte, sicer se nit in medpomnilnik kopicita.
                        if (++tihihKrogov > NAJVEC_TIHIH_KROGOV) {
                            zapri(1001, "naprava se ne oglaša")
                            break
                        }
                        run {
                            try {
                                vVrsto(OPKODA_PING, ByteArray(0))
                            } catch (_: Exception) {
                                zapri(1011, "ping ni uspel")
                            }
                        }
                        continue
                    }
                    continue
                } catch (e: Exception) {
                    if (jeOdprta()) Log.i(OZNAKA, "Branje s povezave ${imeNaprave.ifBlank { naslov }} se je končalo: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(100)}")
                    break
                }
                if (prvi < 0) break
                tihihKrogov = 0

                val zakljucen = (prvi and 0x80) != 0
                val opkoda = prvi and 0x0F

                val drugi = vhod.read()
                if (drugi < 0) break
                val maskiran = (drugi and 0x80) != 0
                if (!maskiran) {
                    // Odjemalec, ki ne maskira, ni skladen; taksne povezave ne beremo naprej.
                    obramba?.dogodek(zahteva.odjemalec, HubObramba.OKVIR)
                    zapri(1002, "okvir ni maskiran")
                    break
                }

                var dolzina = (drugi and 0x7F).toLong()
                if (dolzina == 126L) {
                    dolzina = ((vhod.read() shl 8) or vhod.read()).toLong()
                } else if (dolzina == 127L) {
                    dolzina = 0
                    for (i in 0 until 8) dolzina = (dolzina shl 8) or vhod.read().toLong()
                }
                if (dolzina < 0 || dolzina > NAJVECJE_SPOROCILO) {
                    zapri(1009, "sporočilo je preveliko")
                    break
                }

                val maska = ByteArray(4)
                if (!preberiTocno(maska)) break
                val podatki = ByteArray(dolzina.toInt())
                if (!preberiTocno(podatki)) break
                odmaskiraj(podatki, maska)

                when (opkoda) {
                    OPKODA_ZAPRI -> {
                        zapri(1000, "")
                        return
                    }
                    OPKODA_PING -> run {
                        try {
                            vVrsto(OPKODA_PONG, podatki)
                        } catch (_: Exception) {
                        }
                    }
                    OPKODA_PONG -> { /* ziv je, nic drugega ni treba */ }
                    OPKODA_BESEDILO, OPKODA_DVOJISKO, OPKODA_NADALJEVANJE -> {
                        if (opkoda != OPKODA_NADALJEVANJE) zbranaOpkoda = opkoda
                        if (zbrano.size() + podatki.size > NAJVECJE_SPOROCILO) {
                            zapri(1009, "sporočilo je preveliko")
                            return
                        }
                        if (!zadrziBajte(podatki.size)) {
                            zapri(1013, "naprava naj poskusi znova")
                            return
                        }
                        // Sporocilo v enem okvirju (skoraj vsa) gre naprej brez vmesnih kopij.
                        val celo: ByteArray? = if (zakljucen && zbrano.size() == 0) podatki else {
                            zbrano.write(podatki)
                            if (zakljucen) zbrano.toByteArray() else null
                        }
                        if (celo != null) {
                            if (zbranaOpkoda == OPKODA_BESEDILO) {
                                val besedilo = String(celo, Charsets.UTF_8)
                                try {
                                    naSporocilo?.invoke(besedilo)
                                } catch (e: Exception) {
                                    Log.w(OZNAKA, "Obdelava sporočila ni uspela: ${e.message}")
                                }
                            }
                            if (zbrano.size() > 0) zbrano = ByteArrayOutputStream()
                            zbranaOpkoda = -1
                            sprostiZadrzek()
                        }
                    }
                }
            }
            zapri(1000, "")
        }

        /** XOR z masko odjemalca (RFC 6455) po stiri bajte naenkrat - brez deljenja po modulu za vsak bajt. */
        private fun odmaskiraj(podatki: ByteArray, maska: ByteArray) {
            val m0 = maska[0].toInt()
            val m1 = maska[1].toInt()
            val m2 = maska[2].toInt()
            val m3 = maska[3].toInt()
            val polnih = podatki.size and 3.inv()
            var i = 0
            while (i < polnih) {
                podatki[i] = (podatki[i].toInt() xor m0).toByte()
                podatki[i + 1] = (podatki[i + 1].toInt() xor m1).toByte()
                podatki[i + 2] = (podatki[i + 2].toInt() xor m2).toByte()
                podatki[i + 3] = (podatki[i + 3].toInt() xor m3).toByte()
                i += 4
            }
            while (i < podatki.size) {
                podatki[i] = (podatki[i].toInt() xor maska[i and 3].toInt()).toByte()
                i++
            }
        }

        private fun preberiTocno(cilj: ByteArray): Boolean {
            var prebrano = 0
            while (prebrano < cilj.size) {
                val n = try {
                    vhod.read(cilj, prebrano, cilj.size - prebrano)
                } catch (e: Exception) {
                    return false
                }
                if (n < 0) return false
                prebrano += n
            }
            return true
        }
    }

    companion object {
        private const val OZNAKA = "SafeerHubStreznik"
        const val PRIVZETA_VRATA = 8990

        private const val CAROBNI_NIZ = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val OPKODA_NADALJEVANJE = 0x0
        private const val OPKODA_BESEDILO = 0x1
        private const val OPKODA_DVOJISKO = 0x2
        private const val OPKODA_ZAPRI = 0x8
        private const val OPKODA_PING = 0x9
        private const val OPKODA_PONG = 0xA

        // Clanov kroga (povezav WebSocket z veljavno vstopnico) ne omejuje stevilka - samo viri naprave (ViriNaprave;
        // lastnik, 18. 10. 2026: podjetje z veliko napravami za enim naslovom). Do 0.5.90: 16 povezav, 24 vticnic skupaj.
        // Fiksne meje ostanejo za nepreverjene (spodaj) in tehnicne meje protokola (velikost sporocila, glave, vrsta).

        /**
         * Nepreverjene vticnice (od sprejema do prebrane zahteve): najvec toliko vseh in z enega naslova, isto kot
         * link_neposredno na racunalniku. Nova jih ne caka - izrine najstarejso TIHO ([ROK_TIHE_MS]). Meritev 18. 10. 2026
         * (ObremenitevTest, 200 clanov z istega naslova hkrati, zanka, brez TLS): hkrati nepreverjenih do 162, najdlje
         * 0,64 s - meja 32 na naslov bi z izrinjanjem najstarejse (kot na racunalniku, kjer se preverjanje konca ze pred
         * TLS) izrinila vecino pravih clanov. Zato izrinjamo samo tihe; izrinjenih v vseh meritvah 0.
         */
        const val NAJVEC_NEPREVERJENIH = 128
        const val NAJVEC_NEPREVERJENIH_NA_VIR = 32
        /**
         * Nepreverjena vticnica brez zahteve dlje od tega je tiha in jo nova povezava sme izriniti. Ob valu 200 prijav je
         * bila na zanki najdlje 0,64 s; na televizorju (32-bitni ARM, TLS s kljucem v KeyStore) rokovanje caka na
         * procesor dlje - 10 s je rezerva, da izrinjamo samo tihe, ne clanov v cakanju.
         */
        const val ROK_TIHE_MS = 10_000L
        private const val NAJVECJE_SPOROCILO = 256L * 1024
        /** Do toliko skupaj zadrzanih bajtov (sestavljanje sporocil vseh povezav) brez meritve; nad tem odloca pomnilnik. */
        private const val SKUPNI_ZADRZEK_BREZ_MERITVE = 1L * 1024 * 1024
        /** Premor sprejemanja po napaki accept (npr. EMFILE), da zanka ne vrti procesorja. */
        private const val PREMOR_PO_NAPAKI_MS = 100L
        /** Rok za odgovor ni_virov vticnici, ki je zaradi virov ne strezemo (rokovanje TLS in zahteva). */
        private const val ROK_BREZ_VIROV_MS = 5_000
        private const val NAJVECJE_TELO = 64 * 1024
        private const val NAJVECJA_VRSTICA = 8 * 1024
        private const val NAJVEC_GLAV = 64
        private const val BRALNI_TIMEOUT_MS = 30_000
        private const val PING_VSAKIH_MS = 25_000L
        /** Po toliko zaporednih pingih brez odziva povezavo zapremo, da se ne kopicijo. */
        private const val NAJVEC_TIHIH_KROGOV = 3
        /**
         * Vrsta jedra za sprejem povezav (listen), isto kot sredisce na racunalniku (request_queue_size 512). Do 0.5.90
         * je bila 16: ob valu prijav jedro preostale klice zavrze in naprave cakajo na ponovni SYN (1, 3, 7 s). Meritev
         * 18. 10. 2026 (ObremenitevTest, 200 clanov z enega naslova hkrati, 4 ponovitve): z vrsto 16 so bili vsi prijavljeni
         * v 4,1-15,6 s, z 512 v 0,6-1,9 s. Jedro jo omeji z net.core.somaxconn (televizor, Android 11: 128).
         */
        const val CAKALNA_VRSTA = 512

        /**
         * Izhodna vrsta ene povezave. Sporocila huba so majhna (seznam naprav, ukazi daljinca),
         * zato je vrsta kratka: kdor toliko zaostane, ne bere - povezavo zapremo in naprava se
         * lahko takoj vrne z novo. Datoteke in zaslon ne gredo skozi vrsto (svoje povezave).
         */
        private const val NAJVEC_V_VRSTI = 64
        private const val NAJVEC_BAJTOV_V_VRSTI = 512L * 1024
        /** Koliko cakamo, da pisalna nit poslje zakljucni okvir, preden vticnico zapremo. */
        private const val ZAPIRALNI_ROK_MS = 300L
    }
}
