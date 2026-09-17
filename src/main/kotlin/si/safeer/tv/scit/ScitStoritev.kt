package si.safeer.tv.scit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import si.safeer.tv.MainActivity
import si.safeer.tv.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
    @Volatile private var upstream: List<InetAddress> = emptyList()
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
            val namera = Intent(this, MainActivity::class.java)
            val zastavice = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
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
        val u = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "safeer-scit-urnik").also { it.isDaemon = true } }
        u.scheduleWithFixedDelay({ vzdrzuj() }, 30, 30, TimeUnit.SECONDS)
        u.scheduleWithFixedDelay({ try { ponoviNeodgovorjene() } catch (e: Throwable) { Log.w(TAG, "Ponovitev: ${e.message}") } },
            1, 1, TimeUnit.SECONDS)
        urnik = u
        shraniStatistiko("tece")
        Log.i(TAG, "Safeer Scit tece; DNS naprej: $upstream; domen: ${nabor?.stevilo ?: 0}")
        return true
    }

    private fun ustaviVse(stanje: String) {
        tece = false
        try { urnik?.shutdownNow() } catch (_: Throwable) { }
        urnik = null
        try { tunel?.close() } catch (_: Throwable) { }
        tunel = null
        try { vticnica4?.close() } catch (_: Throwable) { }
        try { vticnica6?.close() } catch (_: Throwable) { }
        vticnica4 = null; vticnica6 = null
        izhod = null
        cakajoce.clear()
        shraniStatistiko(stanje)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Throwable) { }
    }

    private fun zanka(fd: ParcelFileDescriptor) {
        val vhod = FileInputStream(fd.fileDescriptor)
        val buf = ByteArray(32 * 1024)
        while (tece) {
            val n = try { vhod.read(buf) } catch (e: Throwable) { if (tece) Log.w(TAG, "Branje tunela: ${e.message}"); break }
            if (n <= 0) continue
            val q = DnsPaket.razcleni(buf, n) ?: continue
            poizvedb.incrementAndGet()
            val nab = nabor
            if (q.ime.isNotEmpty() && nab != null && nab.jeBlokirana(q.ime)) {
                blokiranih.incrementAndGet()
                zapisi(DnsPaket.zavijOdgovor(q, DnsPaket.odgovorBlokirano(q)))
                continue
            }
            posreduj(q)
        }
    }

    private fun posreduj(q: DnsPaket.Poizvedba) {
        val strezniki = upstream.filter { vticnicaZa(it) != null }
        if (strezniki.isEmpty()) return
        // Navzgor gre NAS ID, ne ID aplikacije: aplikacije si ID-je izbirajo same in se ponovijo,
        // dva hkratna vprasanja z istim ID-jem pa bi se povozila in odgovor bi dobil napacni.
        val nasId = dodeliId(q, strezniki) ?: return
        if (!posljiNaslednjemu(nasId)) cakajoce.remove(nasId)
    }

    private fun vticnicaZa(a: InetAddress): DatagramSocket? = if (a is Inet4Address) vticnica4 else vticnica6

    /** Prost nas ID za poizvedbo; nakljucen, da ga ni mogoce uganiti. Null, ce jih zmanjka. */
    private fun dodeliId(q: DnsPaket.Poizvedba, strezniki: List<InetAddress>): Int? {
        val zdaj = System.currentTimeMillis()
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
     */
    private fun ponoviNeodgovorjene() {
        if (cakajoce.isEmpty()) return
        val zdaj = System.currentTimeMillis()
        for ((id, c) in cakajoce) {
            if (zdaj - c.cas > POTEK_MS) { cakajoce.remove(id, c); continue }
            if (zdaj - c.zadnji >= PONOVITEV_MS && c.naslednji < c.strezniki.size) {
                if (posljiNaslednjemu(id)) Log.i(TAG, "DNS brez odgovora, poskus pri naslednjem strezniku")
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
            if (n == null || datoteka.lastModified() != n.spremenjena) naloziNabor()
            osveziUpstream()
            shraniStatistiko("tece")
            val b = blokiranih.get()
            val pz = poizvedb.get()
            if (pz != zadnjiDnevnik) { zadnjiDnevnik = pz; Log.i(TAG, "Scit: poizvedb $pz, blokiranih $b, cakajocih ${cakajoce.size}") }
            if (b != zadnjeObvestilo) {
                zadnjeObvestilo = b
                try { getSystemService(NotificationManager::class.java)?.notify(OBVESTILO, obvestilo()) } catch (_: Throwable) { }
            }
        } catch (e: Throwable) { Log.w(TAG, "Vzdrzevanje: ${e.message}") }
    }

    private fun naloziNabor() {
        val nov = try { DomenskiNabor.Poizvedba.odpri(Scit.naborDatoteka(this)) } catch (e: Throwable) { Log.w(TAG, "Nabor: ${e.message}"); null }
        if (nov != null) { nabor = nov; Log.i(TAG, "Nabor domen nalozen: ${nov.stevilo}") }
    }

    /** Pravi strezniki DNS omrezja (ne nasi): iz vseh omrezij, ki niso VPN in imajo internet. */
    private fun osveziUpstream() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val najdeni = ArrayList<InetAddress>()
        try {
            for (net in cm.allNetworks) {
                val zm = cm.getNetworkCapabilities(net) ?: continue
                if (zm.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !zm.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                for (a in cm.getLinkProperties(net)?.dnsServers.orEmpty()) {
                    if (a.hostAddress == Scit.DNS_V4 || a.hostAddress == Scit.DNS_V6 || a.isLoopbackAddress) continue
                    if (a !in najdeni) najdeni.add(a)
                }
            }
        } catch (e: Throwable) { Log.w(TAG, "Omrezja: ${e.message}") }
        if (najdeni.isEmpty()) {
            for (n in REZERVNI) try { najdeni.add(InetAddress.getByName(n)) } catch (_: Throwable) { }
        }
        // IPv4 najprej: tudi poizvedbe, ki pridejo po IPv6, gredo naprej po IPv4, ce je na voljo.
        najdeni.sortBy { if (it is Inet4Address) 0 else 1 }
        if (najdeni != upstream) { upstream = najdeni; Log.i(TAG, "DNS naprej: $najdeni") }
    }

    private fun shraniStatistiko(stanje: String) {
        Scit.zapisiStatistiko(this, Scit.Statistika(blokiranih.get(), poizvedb.get(), dan, stanje, nabor?.stevilo ?: 0))
    }

    private fun danes(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

    // ------------------------------------------------------------------ obvestilo

    private fun pripraviKanal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val u = getSystemService(NotificationManager::class.java) ?: return
        if (u.getNotificationChannel(KANAL) != null) return
        val k = NotificationChannel(KANAL, getString(R.string.scit_ime), NotificationManager.IMPORTANCE_LOW)
        k.description = getString(R.string.scit_obvestilo_opis)
        k.setShowBadge(false)
        u.createNotificationChannel(k)
    }

    private fun obvestilo(): Notification {
        val g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, KANAL) else @Suppress("DEPRECATION") Notification.Builder(this)
        g.setContentTitle(getString(R.string.scit_ime))
            .setContentText(getString(R.string.scit_obvestilo_besedilo, blokiranih.get()))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        try {
            val zastavice = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            g.setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), zastavice))
        } catch (_: Throwable) { }
        return g.build()
    }

    companion object {
        private const val TAG = "SafeerScit"
        private const val KANAL = "safeer_scit"
        private const val OBVESTILO = 7311
        const val DEJANJE_ZAZENI = "si.safeer.tv.scit.ZAZENI"
        const val DEJANJE_USTAVI = "si.safeer.tv.scit.USTAVI"
        const val DEJANJE_OSVEZI = "si.safeer.tv.scit.OSVEZI"
        private val REZERVNI = listOf("1.1.1.1", "9.9.9.9")
        /** Koliko cakamo na odgovor, preden vprasamo naslednji streznik, in kdaj odnehamo. */
        private const val PONOVITEV_MS = 1_200L
        private const val POTEK_MS = 8_000L

        fun zazeni(context: Context) = poslji(context, DEJANJE_ZAZENI)
        fun ustavi(context: Context) = poslji(context, DEJANJE_USTAVI)
        fun osvezi(context: Context) = poslji(context, DEJANJE_OSVEZI)

        private fun poslji(context: Context, dejanje: String) {
            val app = context.applicationContext
            val namera = Intent(app, ScitStoritev::class.java).setAction(dejanje)
            try {
                if (dejanje == DEJANJE_ZAZENI && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(namera)
                else app.startService(namera)
            } catch (e: Throwable) { Log.w(TAG, "Storitve ni mogoce naslovit ($dejanje): ${e.message}") }
        }
    }
}
