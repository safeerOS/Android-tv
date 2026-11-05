package si.safeer.tv.scit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import si.safeer.tv.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Storitev Safeer Scita (VpnService) v lastnem procesu `:scit`.
 *
 * Tunel dobi samo pot do navideznega streznika DNS (10.111.222.2 / fd66:5afe:e2::2); sistem vsem
 * aplikacijam za DNS ponudi ta naslov. Zanka bere pakete s tunela, blokirane domene odgovori z
 * NXDOMAIN, ostalo poslje pravemu strezniku DNS omrezja prek zascitene vticnice (mimo tunela) in
 * odgovor zavije nazaj. Nabor domen je mapirana datoteka (DomenskiNabor); ob spremembi jo znova
 * odpre. Ce uporabnik vklopi drug VPN, sistem to storitev preklice (onRevoke) - stanje "prekinjen".
 */
class ScitStoritev : VpnService() {

    private var tunel: ParcelFileDescriptor? = null
    private var nit: Thread? = null
    private var nitOdgovorov: Thread? = null
    @Volatile private var tece = false
    @Volatile private var nabor: DomenskiNabor.Poizvedba? = null
    /** Webtor rezerva: naucene izjeme iz scit/izjeme.txt (domena -> velja do; [ScitIzjeme]); stalne so v kodi. */
    @Volatile private var izjeme: Map<String, Long> = emptyMap()
    /** Pregled V1: samo seznami grozenj (scit/grozne.bin) - izjema jih nikoli ne umakne. */
    @Volatile private var grozne: DomenskiNabor.Poizvedba? = null
    /** Grok 15, N8: gradnja seznamov (in nabora grozenj) je ze zahtevana v tem procesu. */
    @Volatile private var zahtevaniSeznami = false
    @Volatile private var upstream: List<InetAddress> = emptyList()
    /** Pregled Scita 4. 11. 2026: omrezje ne javi DNS - ne izberemo tujega, poizvedbe dobijo SERVFAIL (zaslon: »ni krajevnega DNS«). */
    @Volatile private var brezKrajevnega = false
    @Volatile private var zadnjaOsvezitev = 0L
    private var povratekOmrezja: ConnectivityManager.NetworkCallback? = null
    /** Uporabnikove izjeme z zaslona Scita (scit/izjeme_uporabnika.txt; [ScitPregled]): natanko ime, 30 dni, nikoli groznje. */
    @Volatile private var uporabniske: Map<String, Long> = emptyMap()
    /** Zadnja ustavljena imena (najvec 20) - samo v pomnilniku tega procesa in v zasebni datoteki scit/zadnja.txt. */
    private val zadnjeKljuc = Any()
    private var zadnje: List<ScitPregled.Zadnje> = emptyList()
    @Volatile private var zadnjeSpremenjena = false
    private var vticnica4: DatagramSocket? = null
    private var vticnica6: DatagramSocket? = null
    private val cakajoce = ConcurrentHashMap<Int, Cakajoca>()
    private val blokiranih = AtomicLong(); private val poizvedb = AtomicLong()
    @Volatile private var dan = danes()
    private var urnik: ScheduledExecutorService? = null
    private var zadnjeObvestilo = -1L
    private var zadnjiDnevnik = -1L
    private val izhodKljuc = Any()
    private var izhod: FileOutputStream? = null

    /**
     * Poizvedba, poslana navzgor: cigava je, katere streznike smo ze vprasali in kdaj nazadnje.
     * Ce prvi razreševalec molci (usmerjevalnik po prekinitvi rad neha odgovarjati), gre isto
     * vprasanje naslednjemu; sprejmemo odgovor kateregakoli vprasanega.
     */
    private class Cakajoca(val q: DnsPaket.Poizvedba, val cas: Long, val strezniki: List<InetAddress>) {
        @Volatile var naslednji = 0            // indeks streznika, ki ga bomo vprasali naslednjega
        @Volatile var zadnji = 0L              // kdaj smo nazadnje poslali
        val vprasani = java.util.concurrent.CopyOnWriteArrayList<InetAddress>()
    }

    private val nakljucni = SecureRandom()
    /** Kdaj smo nazadnje zapisali, da je [cakajoce] poln (dnevnik najvec vsakih 10 s). */
    @Volatile private var zadnjaPolna = 0L

    /** DNS prek TCP: razreševalec ga uporabi, kadar odgovor po UDP ne gre v en paket. */
    private val tcp = TcpDns({ paket -> zapisi(paket) }, { vprasanje, odgovor -> razresiZaTcp(vprasanje, odgovor) })
    private var tcpIzvajalec: ExecutorService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            DEJANJE_USTAVI -> { ustaviVse("ustavljen"); stopSelf(); return START_NOT_STICKY }
            DEJANJE_OSVEZI -> { if (tunel == null) { stopSelf(); return START_NOT_STICKY }; naloziNabor(); return START_STICKY }
        }
        try { pripraviKanal(); startForeground(OBVESTILO, obvestilo()) } catch (e: Throwable) { Log.w(TAG, "Obvestilo: ${e.message}") }
        if (!Scit.jeVklopljen(this)) { ustaviVse("ustavljen"); stopSelf(); return START_NOT_STICKY }
        if (tunel == null && !vzpostavi()) { ustaviVse("napaka"); stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onRevoke() {
        Log.w(TAG, "Sistem je preklical Safeer Scit (drug VPN?).")
        ustaviVse("prekinjen")
        stopSelf()
    }

    override fun onDestroy() {
        ustaviVse(if (Scit.jeVklopljen(this)) "prekinjen" else "ustavljen")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ tunel

    private fun vzpostavi(): Boolean {
        naloziNabor()
        val s = Scit.statistika(this)
        if (s.dan == dan) { blokiranih.set(s.blokiranih); poizvedb.set(s.poizvedb) }
        zadnje = Scit.zadnja(this)
        osveziUpstream()
        val b = Builder()
            .setSession(getString(R.string.scit_ime))
            .setMtu(1500)
            .addAddress("10.111.222.1", 24)
            .addDnsServer(Scit.DNS_V4)
            .addRoute(Scit.DNS_V4, 32)
            .addAddress("fd66:5afe:e2::1", 120)
            .addDnsServer(Scit.DNS_V6)
            .addRoute(Scit.DNS_V6, 128)
            .setBlocking(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
        try {
            val namera = domovNamera()
            val zastavice = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            b.setConfigureIntent(PendingIntent.getActivity(this, 0, namera, zastavice))
        } catch (_: Throwable) { }
        val fd = try { b.establish() } catch (e: Throwable) { Log.w(TAG, "Tunela ni mogoce vzpostaviti: ${e.message}"); null } ?: return false
        tunel = fd
        tece = true
        try {
            vticnica4 = DatagramSocket().also { protect(it) }
            vticnica6 = try { DatagramSocket(0, InetAddress.getByName("::")).also { protect(it) } } catch (_: Throwable) { null }
        } catch (e: Throwable) { Log.w(TAG, "Vticnice: ${e.message}"); ustaviVse("napaka"); return false }
        izhod = FileOutputStream(fd.fileDescriptor)
        nit = Thread({ zanka(fd) }, "safeer-scit").also { it.isDaemon = true; it.start() }
        nitOdgovorov = Thread({ zankaOdgovorov() }, "safeer-scit-odgovori").also { it.isDaemon = true; it.start() }
        tcpIzvajalec = Executors.newFixedThreadPool(2) { r -> Thread(r, "safeer-scit-tcp").also { it.isDaemon = true } }
        val u = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "safeer-scit-urnik").also { it.isDaemon = true } }
        u.scheduleWithFixedDelay({ vzdrzuj() }, 30, 30, TimeUnit.SECONDS)
        u.scheduleWithFixedDelay({
            try { ponoviNeodgovorjene(); tcp.pocisti(); shraniZadnje() } catch (e: Throwable) { Log.w(TAG, "Ponovitev: ${e.message}") }
        }, 1, 1, TimeUnit.SECONDS)
        urnik = u
        spremljajOmrezje()
        shraniStatistiko("tece")
        Log.i(TAG, "Safeer Scit tece; krajevnih DNS: ${upstream.size}; domen: ${nabor?.stevilo ?: 0}")
        return true
    }

    private fun ustaviVse(stanje: String) {
        tece = false
        povratekOmrezja?.let { cb ->
            try { (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb) } catch (_: Throwable) { }
        }
        povratekOmrezja = null
        try { urnik?.shutdownNow() } catch (_: Throwable) { }
        try { tcpIzvajalec?.shutdownNow() } catch (_: Throwable) { }
        tcpIzvajalec = null
        urnik = null
        try { tunel?.close() } catch (_: Throwable) { }
        tunel = null
        try { vticnica4?.close() } catch (_: Throwable) { }
        try { vticnica6?.close() } catch (_: Throwable) { }
        vticnica4 = null; vticnica6 = null
        izhod = null
        cakajoce.clear()
        // Pregled Scita (tocka 5): zadnja imena so osebni podatki - ob izklopu (uporabnik) jih pocistimo; ob prekinitvi
        // (drug VPN, sistem) ostanejo, ker Scit ni bil izklopljen.
        if (stanje == "ustavljen") { synchronized(zadnjeKljuc) { zadnje = emptyList(); zadnjeSpremenjena = false }; Scit.pocistiZadnja(this) }
        else shraniZadnje()
        shraniStatistiko(stanje)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) { }
    }

    private fun zanka(fd: ParcelFileDescriptor) {
        val vhod = FileInputStream(fd.fileDescriptor)
        val buf = ByteArray(32 * 1024)
        while (tece) {
            val n = try { vhod.read(buf) } catch (e: Throwable) { if (tece) Log.w(TAG, "Branje tunela: ${e.message}"); break }
            if (n <= 0) continue
            val q = DnsPaket.razcleni(buf, n)
            if (q == null) { tcp.naPaket(buf, n); continue }   // ni UDP: morda DNS prek TCP
            poizvedb.incrementAndGet()
            when (odlocitev(q.ime)) {
                ScitOdlocitev.Izid.USTAVI -> {
                    blokiranih.incrementAndGet()
                    zabeleziUstavljeno(q.ime)
                    zapisi(DnsPaket.zavijOdgovor(q, DnsPaket.odgovorBlokirano(q)))
                    continue
                }
                // Kanarcek DoH: NXDOMAIN, a ne steje kot ustavljeno (ni oglas) in ne gre med zadnja imena.
                ScitOdlocitev.Izid.KANAREK -> { zapisi(DnsPaket.zavijOdgovor(q, DnsPaket.odgovorBlokirano(q))); continue }
                ScitOdlocitev.Izid.NAPREJ -> Unit
            }
            posreduj(q)
        }
    }

    private fun posreduj(q: DnsPaket.Poizvedba) {
        var strezniki = upstream.filter { vticnicaZa(it) != null }
        if (strezniki.isEmpty()) {
            // Pregled Scita 4. 11. 2026: brez krajevnega DNS ne izberemo tujega. Morda se je omrezje pravkar javilo
            // (povratni klic se ni prisel): enkrat na sekundo preverimo; sicer takoj SERVFAIL, da aplikacija ne caka.
            osveziUpstreamRedko()
            strezniki = upstream.filter { vticnicaZa(it) != null }
            if (strezniki.isEmpty()) { zapisi(DnsPaket.zavijOdgovor(q, DnsPaket.odgovorNapakaZa(q.dns))); return }
        }
        // Navzgor gre NAS ID, ne ID aplikacije: aplikacije si ID-je izbirajo same in se ponovijo,
        // dva hkratna vprasanja z istim ID-jem pa bi se povozila in odgovor bi dobil napacni.
        val nasId = dodeliId(q, strezniki) ?: return
        if (!posljiNaslednjemu(nasId)) cakajoce.remove(nasId)
    }

    private fun vticnicaZa(a: InetAddress): DatagramSocket? = if (a is Inet4Address) vticnica4 else vticnica6

    /**
     * Vprasanje, ki je prislo po TCP. Blokirano domeno odgovorimo sami (tudi po TCP ni obhoda),
     * sicer vprasamo naprej po TCP - ista pot, ki bi jo aplikacija uporabila brez Scita.
     */
    private fun razresiZaTcp(vprasanje: ByteArray, odgovor: (ByteArray?) -> Unit) {
        poizvedb.incrementAndGet()
        val ime = DnsPaket.vprasanje(vprasanje)?.ime.orEmpty()
        Log.i(TAG, "DNS po TCP")   // redko; dokaz, da ta pot tece. Brez imena: imena ne gredo v logcat (pregled Scita, tocka 5).
        when (odlocitev(ime)) {
            ScitOdlocitev.Izid.USTAVI -> {
                blokiranih.incrementAndGet()
                zabeleziUstavljeno(ime)
                odgovor(DnsPaket.odgovorBlokiranoZa(vprasanje))
                return
            }
            ScitOdlocitev.Izid.KANAREK -> { odgovor(DnsPaket.odgovorBlokiranoZa(vprasanje)); return }
            ScitOdlocitev.Izid.NAPREJ -> Unit
        }
        if (upstream.isEmpty()) osveziUpstreamRedko()
        val strezniki = upstream
        val izvajalec = tcpIzvajalec
        if (izvajalec == null) { odgovor(null); return }
        if (strezniki.isEmpty()) { odgovor(DnsPaket.odgovorNapakaZa(vprasanje)); return }   // ni krajevnega DNS: SERVFAIL, ne tuj
        izvajalec.execute {
            var rezultat: ByteArray? = null
            for (streznik in strezniki) {
                rezultat = try { vprasajPoTcp(streznik, vprasanje) } catch (e: Throwable) {
                    if (tece) Log.w(TAG, "DNS po TCP ($streznik): ${e.message}"); null
                }
                if (rezultat != null) break
            }
            odgovor(rezultat)
        }
    }

    /** Ena poizvedba DNS po TCP: dolzinska predpona, sporocilo, odgovor. */
    private fun vprasajPoTcp(streznik: InetAddress, vprasanje: ByteArray): ByteArray? {
        val v = Socket()
        try {
            protect(v)
            v.connect(InetSocketAddress(streznik, 53), 3_000)
            v.soTimeout = 4_000
            val glava = ByteArray(2)
            DnsPaket.put16(glava, 0, vprasanje.size)
            val izh = v.getOutputStream()
            izh.write(glava); izh.write(vprasanje); izh.flush()
            val vh = java.io.DataInputStream(v.getInputStream())
            val dolzina = vh.readUnsignedShort()
            if (dolzina < 12 || dolzina > TcpDns.NAJVECJE_SPOROCILO) return null
            val o = ByteArray(dolzina)
            vh.readFully(o)
            return o
        } finally {
            try { v.close() } catch (_: Throwable) { }
        }
    }

    /** Prost nas ID za poizvedbo; nakljucen, da ga ni mogoce uganiti. Null, ce jih zmanjka. */
    private fun dodeliId(q: DnsPaket.Poizvedba, strezniki: List<InetAddress>): Int? {
        val zdaj = System.currentTimeMillis()
        // Naloga 65: najvec NAJVEC_CAKAJOCIH hkrati (prej do 65536 - vsaka s kopijo paketa, ~0,5-1 kB). Ko strezniki
        // molcijo ali aplikacija poplavi z vprasanji, bi se jih v POTEK_MS nabralo na desetine MB.
        if (cakajoce.size >= NAJVEC_CAKAJOCIH) {
            cakajoce.entries.removeIf { zdaj - it.value.cas > POTEK_MS }
            if (cakajoce.size >= NAJVEC_CAKAJOCIH) {
                if (zdaj - zadnjaPolna > 10_000L) { zadnjaPolna = zdaj; Log.w(TAG, "Hkratnih poizvedb DNS je ${cakajoce.size}; nove izpuscam") }
                return null
            }
        }
        repeat(12) {
            val id = nakljucni.nextInt(65536)
            if (cakajoce.putIfAbsent(id, Cakajoca(q, zdaj, strezniki)) == null) return id
        }
        Log.w(TAG, "Preveč hkratnih poizvedb DNS; ta je izpuščena")
        return null
    }

    /** Poslji cakajoco poizvedbo naslednjemu se nevprasanemu strezniku. */
    private fun posljiNaslednjemu(id: Int): Boolean {
        val c = cakajoce[id] ?: return false
        while (c.naslednji < c.strezniki.size) {
            val cilj = c.strezniki[c.naslednji++]
            val v = vticnicaZa(cilj) ?: continue
            c.zadnji = System.currentTimeMillis()
            c.vprasani.add(cilj)
            try {
                v.send(DatagramPacket(DnsPaket.zId(c.q.dns, id), c.q.dns.size, cilj, 53))
                return true
            } catch (e: Throwable) {
                if (tece) Log.w(TAG, "Posredovanje DNS na $cilj: ${e.message}")
            }
        }
        return false
    }

    /**
     * Poizvedbe, na katere ni odgovora: po [PONOVITEV_MS] jih ponovimo pri naslednjem strezniku,
     * po [POTEK_MS] pa jih opustimo. Brez tega je dovolj, da prvi razreševalec utihne, in splet
     * na televizorju obstoji, ceprav je drugi strežnik dosegljiv.
     * Pregled 5. 11. 2026: ko so vsi vprasani in molcijo ([ScitStanjePravila.neodgovorjena]), aplikacija dobi SERVFAIL
     * (prej tiho opusceno - aplikacija je cakala 5 s na svoj rok). Odstranitev iz [cakajoce] je atomarna (remove(id, c)):
     * pravi odgovor, ki je prisel prej, je poizvedbo ze vzel in SERVFAIL se ne poslje.
     */
    private fun ponoviNeodgovorjene() {
        if (cakajoce.isEmpty()) return
        val zdaj = System.currentTimeMillis()
        for ((id, c) in cakajoce) {
            when (ScitStanjePravila.neodgovorjena(zdaj, c.cas, c.zadnji, c.naslednji, c.strezniki.size, PONOVITEV_MS, POTEK_MS)) {
                ScitStanjePravila.Neodgovorjena.SERVFAIL ->
                    if (cakajoce.remove(id, c)) zapisi(DnsPaket.zavijOdgovor(c.q, DnsPaket.odgovorNapakaZa(c.q.dns)))
                ScitStanjePravila.Neodgovorjena.PONOVI ->
                    if (posljiNaslednjemu(id)) Log.i(TAG, "DNS brez odgovora, poskus pri naslednjem strezniku")
                ScitStanjePravila.Neodgovorjena.CAKAJ -> Unit
            }
        }
    }

    private fun zankaOdgovorov() {
        val v4 = vticnica4 ?: return
        val buf = ByteArray(4096)
        val p = DatagramPacket(buf, buf.size)
        // Odgovore beremo z obeh vticnic: IPv6 v lastni niti, ce obstaja.
        vticnica6?.let { v6 -> Thread({ beriOdgovore(v6) }, "safeer-scit-odgovori6").also { it.isDaemon = true; it.start() } }
        beriOdgovore(v4, p)
    }

    private fun beriOdgovore(v: DatagramSocket, p: DatagramPacket = DatagramPacket(ByteArray(4096), 4096)) {
        val velikost = p.data.size
        while (tece) {
            // Brez tega bi vsak naslednji odgovor odrezali na dolzino prejsnjega.
            p.length = velikost
            try { v.receive(p) } catch (e: Throwable) { if (tece) Log.w(TAG, "Odgovor DNS: ${e.message}"); break }
            if (p.length < 12) continue
            val id = DnsPaket.u16(p.data, p.offset)
            val c = cakajoce[id] ?: continue
            val odgovor = p.data.copyOfRange(p.offset, p.offset + p.length)
            // Celotno preverjanje je v DnsPaket.ustrezaOdgovor (cista funkcija, pokrita s testi):
            // pravi streznik, vrata 53, nas ID, zastavica QR in isto vprasanje (ime, vrsta, razred).
            val izvor = p.address?.address ?: continue
            if (c.vprasani.none { DnsPaket.ustrezaOdgovor(c.q, id, it.address, izvor, p.port, odgovor) }) continue
            if (!cakajoce.remove(id, c)) continue
            DnsPaket.put16(odgovor, 0, c.q.id)   // aplikaciji vrnemo njen ID
            zapisi(DnsPaket.zavijOdgovor(c.q, odgovor))
        }
    }

    private fun zapisi(paket: ByteArray) {
        val o = izhod ?: return
        synchronized(izhodKljuc) {
            try { o.write(paket) } catch (e: Throwable) { if (tece) Log.w(TAG, "Pisanje v tunel: ${e.message}") }
        }
    }

    // ------------------------------------------------------------------ vzdrzevanje

    private fun vzdrzuj() {
        try {
            val zdaj = System.currentTimeMillis()
            cakajoce.entries.removeIf { zdaj - it.value.cas > POTEK_MS }
            val d = danes()
            if (d != dan) { dan = d; blokiranih.set(0); poizvedb.set(0) }
            val datoteka = Scit.naborDatoteka(this)
            val n = nabor
            val gd = Scit.grozneDatoteka(this)
            val g = grozne
            if (n == null || datoteka.lastModified() != n.spremenjena || (gd.isFile && (g == null || gd.lastModified() != g.spremenjena))) naloziNabor()
            // Nabor Scita gradi in posodablja samo ta proces (4. 11. 2026): agent za nabor (startSamoScit), ne celoten agent
            // brskalnika - filter DNS ne rabi EasyList, drevesa grozenj, kazala ne podpisanega vira. Brskalnik seznamov samo za
            // DNS (HaGeZi PRO) ne bere vec, zato agent tece ob vsakem zagonu Scita (prej samo, ko je bil domene.bin brez
            // grozne.bin; v Safeer OS, kjer brskalnika ni, se nabor sicer nikoli ne bi zgradil ne posodobil). Ce je nabor ze
            // zgrajen iz shranjenih seznamov, agent ob zagonu ne bere nicesar.
            if (!zahtevaniSeznami) {
                zahtevaniSeznami = true
                try { si.safeer.tv.ThreatFeedsUpdater.startSamoScit(applicationContext) } catch (e: Throwable) { Log.w(TAG, "Seznami: ${e.javaClass.simpleName}") }
            }
            osveziUpstream()
            shraniStatistiko("tece")
            val b = blokiranih.get()
            val pz = poizvedb.get()
            if (pz != zadnjiDnevnik) {
                zadnjiDnevnik = pz
                Log.i(TAG, "Scit: poizvedb $pz, blokiranih $b, cakajocih ${cakajoce.size}, TCP ${tcp.stevilo()}")
            }
            if (b != zadnjeObvestilo) {
                zadnjeObvestilo = b
                try { getSystemService(NotificationManager::class.java)?.notify(OBVESTILO, obvestilo()) } catch (_: Throwable) { }
            }
        } catch (e: Throwable) { Log.w(TAG, "Vzdrzevanje: ${e.message}") }
    }

    private fun naloziNabor() {
        val nov = try { DomenskiNabor.Poizvedba.odpri(Scit.naborDatoteka(this)) } catch (e: Throwable) { Log.w(TAG, "Nabor: ${e.message}"); null }
        if (nov != null) { nabor = nov; Log.i(TAG, "Nabor domen nalozen: ${nov.stevilo}") }
        grozne = try { DomenskiNabor.Poizvedba.odpri(Scit.grozneDatoteka(this)) } catch (_: Throwable) { null }
        izjeme = try {
            Scit.izjemeDatoteka(this).takeIf { it.isFile }?.readText()?.let { ScitIzjeme.preberi(it, System.currentTimeMillis()) } ?: emptyMap()
        } catch (_: Throwable) { emptyMap() }
        uporabniske = Scit.izjemeUporabnika(this)
    }

    /**
     * Odlocitev za ime ([ScitOdlocitev.odloci], isto pravilo kot Safeer-linux; skupni primeri scit-pravila.json): seznami
     * grozenj imajo prednost pred izjemami (Webtorjeva naucena, uporabnikova - veljavni OB PREVERBI) in pred zascitenimi
     * gostitelji; krajevna imena gredo vedno naprej; kanarcek DoH dobi NXDOMAIN. Brez nabora (se ni zgrajen) gre ime naprej.
     */
    private fun odlocitev(ime: String): ScitOdlocitev.Izid {
        val nab = nabor
        val g = grozne
        val zdaj = System.currentTimeMillis()
        return ScitOdlocitev.odloci(ime, { nab?.jeBlokirana(it) == true }, g != null, { g?.jeBlokirana(it) == true },
            { ScitIzjeme.dovoljena(it, izjeme, zdaj) || ScitPregled.uporabniskaVelja(it, uporabniske, zdaj) })
    }

    /** Ustavljeno ime na vrh seznama zadnjih (zapis v datoteko najvec enkrat na sekundo, v urniku). Brez logcat. */
    private fun zabeleziUstavljeno(ime: String) {
        val ura = System.currentTimeMillis()
        synchronized(zadnjeKljuc) { zadnje = ScitPregled.dodajZadnje(zadnje, ime, ura); zadnjeSpremenjena = true }
    }

    private fun shraniZadnje() {
        // Izklopljen Scit (zastavico je pobrisal vmesnik) imen ne zapise vec - zakasnel zapis bi jih po izklopu obnovil.
        if (!Scit.jeVklopljen(this)) return
        val kopija = synchronized(zadnjeKljuc) { if (!zadnjeSpremenjena) return; zadnjeSpremenjena = false; zadnje }
        Scit.zapisiZadnja(this, kopija)
    }

    /**
     * Pregled Scita 4. 11. 2026: ko se omrezje (usmerjevalnik) javi ali spremeni DNS, razresevalce osvezimo takoj - prej
     * sele v vzdrzevanju na 30 s. Privzeta zahteva izkljuci VPN (NOT_VPN), zato nas tunel ne pride sem.
     */
    private fun spremljajOmrezje() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = obSpremembi()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = obSpremembi()
            override fun onLost(network: Network) = obSpremembi()
        }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb)
            povratekOmrezja = cb
        } catch (e: Throwable) { Log.w(TAG, "Spremljanje omrezja: ${e.javaClass.simpleName}") }
    }

    private fun obSpremembi() {
        if (!tece) return
        try { val prej = brezKrajevnega; osveziUpstream(); if (prej != brezKrajevnega) shraniStatistiko("tece") } catch (_: Throwable) { }
    }

    /** Osvezitev razresevalcev iz poti poizvedbe: najvec enkrat na sekundo (allNetworks je klic v sistem). */
    private fun osveziUpstreamRedko() {
        val zdaj = System.currentTimeMillis()
        if (zdaj - zadnjaOsvezitev < 1_000L) return
        zadnjaOsvezitev = zdaj
        val prej = brezKrajevnega
        osveziUpstream()
        if (prej != brezKrajevnega) shraniStatistiko("tece")
    }

    /** Pravi strezniki DNS omrezja (ne nasi): iz vseh omrezij, ki niso VPN in imajo internet. */
    private fun osveziUpstream() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val najdeni = ArrayList<InetAddress>()
        try {
            // allNetworks: opusceno v API 31, a edini takojsnji seznam; Android 9-11 drugega nima.
            @Suppress("DEPRECATION") val vsa = cm.allNetworks
            for (net in vsa) {
                val zm = cm.getNetworkCapabilities(net) ?: continue
                if (zm.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !zm.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                for (a in cm.getLinkProperties(net)?.dnsServers.orEmpty()) {
                    if (a.hostAddress == Scit.DNS_V4 || a.hostAddress == Scit.DNS_V6 || a.isLoopbackAddress) continue
                    if (a !in najdeni) najdeni.add(a)
                }
            }
        } catch (e: Throwable) { Log.w(TAG, "Omrezja: ${e.message}") }
        // Pregled Scita 4. 11. 2026: prej rezerva 1.1.1.1 in 9.9.9.9 - vse dovoljene poizvedbe so sle k Cloudflare/Quad9.
        // Zdaj samo krajevni; brez njih SERVFAIL, dokler se usmerjevalnik ne javi ([spremljajOmrezje]).
        val izbrani = ArrayList(ScitPregled.naprej(najdeni))
        brezKrajevnega = izbrani.isEmpty()
        // IPv4 najprej: tudi poizvedbe, ki pridejo po IPv6, gredo naprej po IPv4, ce je na voljo.
        izbrani.sortBy { if (it is Inet4Address) 0 else 1 }
        if (izbrani != upstream) { upstream = izbrani; Log.i(TAG, if (izbrani.isEmpty()) "DNS naprej: ni krajevnega" else "DNS naprej: ${izbrani.size} krajevnih") }
    }

    private fun shraniStatistiko(stanje: String) {
        Scit.zapisiStatistiko(this, Scit.Statistika(blokiranih.get(), poizvedb.get(), dan, stanje, nabor?.stevilo ?: 0, brezKrajevnega))
    }

    private fun danes(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

    // ------------------------------------------------------------------ obvestilo

    private fun pripraviKanal() {
        si.safeer.tv.TihaObvestila.kanal(this)
    }

    private fun obvestilo(): Notification {
        val g = Notification.Builder(this, si.safeer.tv.TihaObvestila.kanal(this))
        g.setContentTitle(getString(R.string.scit_ime))
            .setContentText(getString(R.string.scit_obvestilo_besedilo, blokiranih.get()))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        try {
            val zastavice = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            g.setContentIntent(PendingIntent.getActivity(this, 0, domovNamera(), zastavice))
        } catch (_: Throwable) { }
        return g.build()
    }

    /** Zagonska aplikacija tega paketa (Safeer OS nima brskalnika, Safeer Browser TV je MainActivity). */
    private fun domovNamera(): Intent =
        packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName)

    companion object {
        private const val TAG = "SafeerScit"
        private const val KANAL = "safeer_scit"
        private const val OBVESTILO = 7311
        const val DEJANJE_ZAZENI = "si.safeer.tv.scit.ZAZENI"
        const val DEJANJE_USTAVI = "si.safeer.tv.scit.USTAVI"
        const val DEJANJE_OSVEZI = "si.safeer.tv.scit.OSVEZI"
        /** Koliko cakamo na odgovor, preden vprasamo naslednji streznik, in kdaj odnehamo. */
        private const val PONOVITEV_MS = 1_200L
        private const val POTEK_MS = 8_000L
        /** Naloga 65: najvec cakajocih poizvedb naenkrat (obicajno jih je nekaj deset; dnevnik »cakajocih N« vsakih 30 s). */
        private const val NAJVEC_CAKAJOCIH = 1024

        fun zazeni(context: Context) = poslji(context, DEJANJE_ZAZENI)
        fun ustavi(context: Context) = poslji(context, DEJANJE_USTAVI)
        fun osvezi(context: Context) = poslji(context, DEJANJE_OSVEZI)

        private fun poslji(context: Context, dejanje: String) {
            val app = context.applicationContext
            val namera = Intent(app, ScitStoritev::class.java).setAction(dejanje)
            try {
                if (dejanje == DEJANJE_ZAZENI) app.startForegroundService(namera)
                else app.startService(namera)
            } catch (e: Throwable) { Log.w(TAG, "Storitve ni mogoce naslovit ($dejanje): ${e.message}") }
        }
    }
}
