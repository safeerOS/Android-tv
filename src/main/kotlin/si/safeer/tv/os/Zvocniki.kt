package si.safeer.tv.os

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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
 *
 * Od 7. 10. 2026 zvocnik upravlja glavni predvajalnik: tu je stanje predvajanja na zvocniku ([stanje], [polozaj],
 * [glasnost]) in ukazi ([premor], [nadaljuj], [skoci], [glasnostZa]); kdaj se kateri ukaz sme poslati, dolocajo
 * [ZvocnikPravila] (izmerjeno na pravem zvocniku). Vrsto skladb vodi [GlasbaStoritev].
 *
 * Niti: stanje spreminja samo glavna nit; vsi klici zvocniku tecejo po vrsti na enem delavcu. Zaporedne stevilke
 * ([zagon], [zadnjiPreskok], [zadnjiPrenos], [ukazov]) povedo delavcu in odcitkom, da jih je prehitel novejsi ukaz.
 */
object Zvocniki {
    private const val TAG = "SafeerZvocniki"
    /** Toliko klicatelj [ustavi] najdlje caka na potrditev (ta naprava zacne igrati sele po njej - ne igrata oba). */
    private const val STOP_HITRO_MS = 2_500L
    /** Stop, ki ni prisel do zvocnika, ponavljamo toliko budnega casa (zvocnik brez daljinca bi igral naprej). */
    private const val STOP_VZTRAJA_MS = 60_000L
    private const val STOP_PREMOR_MS = 3_000L
    /** Zaklep budnosti se podaljsa ob vsakem odcitku; ce program obstane, se po tem casu sprosti sam. */
    private const val BUDNOST_MS = 10 * 60_000L
    /** Po toliko casa brez odgovora zvocnika je seja zastarela: pred prvim Stop preverimo, ali zvocnik se igra nase. */
    private const val ZASTAREL_PO_MS = 15_000L
    private val delavec = Executors.newSingleThreadExecutor { r -> Thread(r, "safeer-zvocniki").apply { isDaemon = true } }
    private val glavna = Handler(Looper.getMainLooper())

    /** Zvocnik, ki ta trenutek igra nasa skladba (ali null). */
    @Volatile var aktivni: DlnaPravila.Zvocnik? = null
        private set
    @Volatile var aktivnaSkladba: Jamendo.Skladba? = null
        private set
    @Volatile private var zadnjiSeznam: List<DlnaPravila.Zvocnik> = emptyList()
    /** Naslov, ki smo ga zvocniku poslali (pri datotekah te naprave: naslov nasega streznika). */
    @Volatile private var aktivniUrl = ""
    /** Naslov, ki ga je zvocnik javil, ko je nasa skladba stekla (lahko se razlikuje od poslanega). Samo glavna nit. */
    private var potrjenUrl = ""
    /**
     * Zadnji naslov, ki ga je delavec poslal zvocniku (tik pred SetAVTransportURI). [aktivniUrl] se zapise sele, ko zagon
     * uspe: Stop med menjavo skladbe ali po neuspelem zagonu bi sicer nase predvajanje imel za tuje (tretji pregled, A3).
     */
    @Volatile private var poslanUrl = ""
    /** UDN zvocnika, ki mu je bil poslan [poslanUrl] - drugemu zvocniku ta naslov ne pomeni nicesar (R6-A3). */
    @Volatile private var poslanZa = ""
    @Volatile private var streznik: ZvocnikStreznik? = null
    private var straza: Runnable? = null

    /** Stanje predvajanja na zvocniku, kot ga vidi uporabnik. Spreminja ga samo glavna nit. */
    @Volatile var stanje = ZvocnikPravila.Stanje(zeliIgrati = false)
        private set
    /** Zadnja znana glasnost zvocnika (-1 = se ne vemo) in najvecja, ki jo zvocnik navaja. */
    @Volatile var glasnost = -1
        private set
    @Volatile var najGlasnost = 100
        private set

    /** Sprememba, ki jo morajo videti seja, obvestilo in zasloni (predvaja/premor, nova skladba, preskok, glasnost, konec seje). */
    var obSpremembi: (() -> Unit)? = null
    /**
     * Skladba na zvocniku se je koncala (KONEC_SKLADBE: seja traja, naslednjo poslje [GlasbaStoritev]), zvocnik je prevzel
     * kdo drug ali ni vec dosegljiv (PREVZET) ali pa skladba ni stekla (NI_ZACELO) - v zadnjih dveh primerih je seja ze
     * koncana; enako KONEC_POZEN (skladba se je koncala, a tega nismo videli sproti - naslednje ne posljemo).
     * Zraven: zadnji polozaj v ms, ime zvocnika in ali je uporabnik takrat hotel predvajanje (ne premora).
     */
    var obKoncu: ((ZvocnikPravila.Dejanje, Long, String, Boolean) -> Unit)? = null

    /** Stevec nasih ukazov: odcitek, narocen pred zadnjim ukazom, je zastarel. Samo glavna nit. */
    private var ukazov = 0
    /** Zaporedna stevilka klica [predvajaj]: delavec preskoci zagon, ki ga je prehitel novejsi (ali konec seje). */
    @Volatile private var zagon = 0
    /** Zaporedna stevilka preskoka: delavec poslje samo zadnjega. */
    @Volatile private var zadnjiPreskok = 0
    /** Zaporedna stevilka ukaza prenosa (Play, Pause): ponavljanje zavrnjenega ukaza odneha, ko ga prehiti novejsi. */
    @Volatile private var zadnjiPrenos = 0
    /** Cilj preskoka, ki je narocen, a ga zvocnik morda se ni dobil (-1 = ni). Samo glavna nit. */
    private var nacrtovan = -1L
    /** Preskoka ne posljemo pred tem trenutkom (po Play mora miniti [ZvocnikPravila.PREMOR_PRED_PRESKOKOM_MS]). */
    @Volatile private var preskokNePred = 0L
    /** Zvocnik, ki mu je bila poslana prva skladba seje, seja pa se ni potrjena - da jo [ustavi] lahko preklice. */
    @Volatile private var cakaZagon: DlnaPravila.Zvocnik? = null
    /** Zagon skladbe je prehitel novejsi ukaz (druga skladba, konec seje): ne uspeh ne napaka zvocnika. */
    class Prehiteno : Exception("prehiteno")
    private val najGlasnosti = HashMap<String, Int>()      // samo delavec
    private var neodzivnih = 0
    private var neodzivenOd = 0L
    private var app: Context? = null

    private val cakaGlasnost = ZvocnikPravila.CakajocaGlasnost()
    private var glasnostVTeku = false      // samo glavna nit
    /** Zvocnik je povedal svojo lestvico glasnosti; sicer gredo spremembe po najmanjsih korakih ([ZvocnikPravila]). */
    @Volatile var lestvicaZnana = false
        private set
    /** Po nasem ukazu naj bo naslednji odcitek najpozneje takrat (ko mine mir); 0 = po rednem razporedu. Samo glavna nit. */
    private var kmaluOb = 0L

    private var budnost: PowerManager.WakeLock? = null

    /** Ura, ki tece tudi med spanjem naprave (zvocnik igra naprej). */
    private fun ura(): Long = SystemClock.elapsedRealtime()

    fun zadnji(): List<DlnaPravila.Zvocnik> = zadnjiSeznam

    /** Polozaj skladbe na zvocniku za zaslon, sejo in obvestilo (med odcitki tece sam). */
    fun polozaj(): Long = ZvocnikPravila.polozajZdaj(stanje, ura())

    private fun javi() { try { obSpremembi?.invoke() } catch (e: Exception) { Log.w(TAG, "Obvestilo o spremembi: ${e.message}") } }

    /** Napis ob predvajanju na zvocniku: »Igra na: X« ali, kadar se zvocnik ne odziva, »X se ne odziva«. */
    fun napis(ctx: Context): String? = aktivni?.let {
        ctx.getString(if (stanje.nedosegljiv) si.safeer.tv.R.string.zvocnik_nedosegljiv else si.safeer.tv.R.string.zvocnik_igra_na, it.ime)
    }

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

    private fun klic(url: String, storitev: String, dejanje: String, arg: List<Pair<String, String>>, rokMs: Int = 6000): ByteArray {
        val telo = DlnaPravila.soap(storitev, dejanje, listOf("InstanceID" to "0") + arg).toByteArray(Charsets.UTF_8)
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = minOf(4000, rokMs); c.readTimeout = rokMs
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        c.setRequestProperty("SOAPACTION", "\"$storitev#$dejanje\"")
        try {
            c.outputStream.use { it.write(telo) }
            val tok = if (c.responseCode in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            return tok.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    private fun soap(url: String, storitev: String, dejanje: String, arg: List<Pair<String, String>>, vrni: String = "", rokMs: Int = 6000): String =
        DlnaPravila.vrednost(klic(url, storitev, dejanje, arg, rokMs), vrni)

    private fun naDelavcu(konec: (Exception?) -> Unit, delo: () -> Unit) {
        delavec.execute {
            val napaka = try { delo(); null } catch (e: Exception) { Log.w(TAG, "${e.message}"); e }
            glavna.post { konec(napaka) }
        }
    }

    /**
     * Ukaz prenosa (Play, Pause). Zvocnik ga med nalaganjem skladbe zavrne (UPnP 701, izmerjeno): ponovimo ga nekajkrat,
     * dokler ga uporabnik se hoce - torej dokler ga ni prehitel novejsi ukaz ([zadnjiPrenos]) ali konec seje. Enako,
     * kadar ukaz do zvocnika ni prisel (napaka omrezja: Wi-Fi telefona se prebuja, izgubljen paket) - ukazi prenosa se
     * smejo ponoviti (isti ukaz dvakrat da isto stanje).
     */
    private fun ukazPrenosa(z: DlnaPravila.Zvocnik, dejanje: String, arg: List<Pair<String, String>>, moj: Int) {
        if (moj != zadnjiPrenos || aktivni !== z) return          // prehitel novejsi ukaz prenosa (hitro preklapljanje)
        var zavrnitev = 0
        var napakOmrezja = 0
        while (true) {
            try { soap(z.avUrl, DlnaPravila.AV, dejanje, arg); return }
            catch (e: IllegalStateException) {
                if (++zavrnitev >= 4 || moj != zadnjiPrenos || aktivni !== z) throw e
                Thread.sleep(600)
            }
            catch (e: java.io.IOException) {
                if (++napakOmrezja >= 3 || moj != zadnjiPrenos || aktivni !== z) throw e
                Thread.sleep(400)
            }
            if (moj != zadnjiPrenos || aktivni !== z) return
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

    /**
     * Skladbo poslje zvocniku. [odMs] > 0: nadaljuje na tem mestu (prenos s te naprave) - zvocnik preskok dobi, ko
     * predvajanje stece. [igraj] = false: skladbo samo nalozi (naprej/nazaj med premorom); Play dobi ob [nadaljuj].
     * Med sejo (isti zvocnik) je to menjava skladbe: zasloni jo pokazejo takoj.
     */
    fun predvajaj(ctx: Context, z: DlnaPravila.Zvocnik, sk: Jamendo.Skladba, odMs: Long = 0L, trajanjeMs: Long = 0L, igraj: Boolean = true,
                  konec: (Exception?) -> Unit) {
        val app = ctx.applicationContext
        // Seja na drugem zvocniku se s tem konca (ena glasba naenkrat): stari zvocnik dobi Stop.
        aktivni?.takeIf { it.udn != z.udn }?.let { stari ->
            val nasi = nasiNaslovi(); val zastarelo = zastarelaSeja(); pocisti(); ustaviZanesljivo(stari, nasi, zastarelo) {}
        }
        // Prvi zagon na drugem zvocniku je se v teku: tudi ta dobi Stop (za zagonom, z enakim delavcem) - prej je zacel
        // igrati brez seje in brez daljinca. Pred Stop preverimo, da igra nase.
        cakaZagon?.takeIf { aktivni == null && it.udn != z.udn }?.let { prejsnji -> ustaviZanesljivo(prejsnji, emptySet(), true) {} }
        val moj = ++zagon
        ukazov++; zadnjiPreskok++; zadnjiPrenos++; nacrtovan = -1L
        val istaSeja = aktivni?.udn == z.udn
        if (istaSeja) {
            aktivnaSkladba = sk
            stanje = ZvocnikPravila.zacetek(ura(), odMs, trajanjeMs, igraj)
            potrjenUrl = ""
            straza?.let { glavna.removeCallbacks(it) }; straza = null
            javi()
        } else cakaZagon = z
        var url = ""
        var nalozeno = false                 // zvocnik je naslov sprejel (SetAVTransportURI je uspel)
        var prebranaGlasnost = -1
        var najvec: Int? = null
        this.app = app
        naDelavcu({ e ->
            // Medtem je bila izbrana ze druga skladba ali je seje konec: to ni ne uspeh ne napaka zvocnika.
            if (moj != zagon) { konec(Prehiteno()); return@naDelavcu }
            cakaZagon = null
            if (e != null) {
                if (aktivni == null) {
                    // Seja se ni zacela: streznik, ki je zvocniku ponudil datoteko te naprave, ne ostane odprt.
                    zapriStreznik()
                    // Zvocnik je naslov ze sprejel, odgovor na Play pa se je izgubil ali zamudil: skladba bi lahko stekla
                    // pozneje sama - brez seje in brez daljinca, hkrati s to napravo. Ustavimo jo; pred Stop preverimo, da
                    // zvocnik res drzi NAS naslov (tretji pregled, A2).
                    if (nalozeno) ustaviZanesljivo(z, setOf(url), true) {}
                }
                konec(e); return@naDelavcu
            }
            val zdaj = ura()
            // Menjava skladbe med sejo: kar je uporabnik izbral, medtem ko je ukaz potoval (premor, novo mesto), ostane.
            stanje = if (istaSeja && aktivni?.udn == z.udn) stanje.copy(veljaOd = zdaj, mirDoMs = zdaj + ZvocnikPravila.MIR_PO_UKAZU_MS, zacetekMs = zdaj,
                    slisanMs = zdaj, playOdMs = if (igraj) zdaj else stanje.playOdMs)
                else ZvocnikPravila.zacetek(zdaj, odMs, trajanjeMs, igraj)
            aktivni = z; aktivnaSkladba = sk; aktivniUrl = url; potrjenUrl = ""
            if (prebranaGlasnost >= 0) glasnost = prebranaGlasnost
            najGlasnost = najvec ?: 100
            lestvicaZnana = najvec != null
            neodzivnih = 0
            zazeniStrazo()
            uskladiBudnost()
            javi()
            if (trajanjeMs <= 0L && !sk.radio) poizvediTrajanje(app, sk, moj)
            konec(null)
        }) {
            if (moj != zagon) return@naDelavcu
            url = sk.zvok
            var mime = DlnaPravila.mime(sk.zvok, sk.mime)
            if (DlnaPravila.lokalniVir(sk.zvok)) {
                // Datoteka te naprave: ponudi jo nas majhen streznik (samo ta datoteka, pod zetonom).
                val s = streznik ?: ZvocnikStreznik(app).also { streznik = it }
                url = s.ponudi(sk.zvok, sk.mime, z.naslov)
                mime = s.mime()
            } else streznik?.pozabi()          // ta skladba ni datoteka te naprave: prejsnje datoteke streznik ne ponuja vec
            val meta = DlnaPravila.didl(url, sk.naslov, sk.izvajalec, mime)
            poslanUrl = url; poslanZa = z.udn
            soap(z.avUrl, DlnaPravila.AV, "SetAVTransportURI", listOf("CurrentURI" to url, "CurrentURIMetaData" to meta))
            nalozeno = true
            if (igraj) {
                soap(z.avUrl, DlnaPravila.AV, "Play", listOf("Speed" to "1"))
                preskokNePred = ura() + ZvocnikPravila.PREMOR_PRED_PRESKOKOM_MS
            }
            // Glasnost in njena lestvica (za tipke glasnosti in drsnik sistema). Napaka tu predvajanja ne ustavi.
            try { prebranaGlasnost = soap(z.rcUrl, DlnaPravila.RC, "GetVolume", listOf("Channel" to "Master"), "CurrentVolume").toIntOrNull() ?: -1 } catch (_: Exception) {}
            // Lestvico si zapomnimo samo, ce jo zvocnik pove: neuspelo branje poskusimo ob naslednji skladbi znova (prej je en
            // sam neuspeh za ves cas zapisal »100«), do takrat gredo spremembe po najmanjsih korakih.
            najvec = najGlasnosti[z.udn] ?: (try { if (z.rcOpis.isBlank()) null else DlnaPravila.najGlasnostAliNic(prenesi(z.rcOpis, DlnaPravila.NAJVEC_OPISA)) }
                catch (_: Exception) { null })?.also { najGlasnosti[z.udn] = it }
        }
    }

    /**
     * Pravo trajanje skladbe iz glave datoteke. Skladbi, ki se zacne neposredno na zvocniku (naslednja v vrsti, nova izbira
     * med sejo), ga ta naprava ne pozna, zvocnik pa ga ugiba in ugib spreminja (izmerjeno 8. 10. 2026: 3:48 -> 3:38 -> 3:31
     * pri skladbi, dolgi 3:24) - drsnik in cas na zaslonu sta bila zato napacna. Bere se v svoji niti (omrezje); ce medtem
     * igra ze kaj drugega, se izid zavrze. Neuspeh ni napaka: naprej velja, kar javi zvocnik.
     */
    private fun poizvediTrajanje(ctx: Context, sk: Jamendo.Skladba, moj: Int) {
        Thread({
            var bralnik: android.media.MediaMetadataRetriever? = null
            val ms = try {
                val b = android.media.MediaMetadataRetriever()       // v try: napaka sistemske knjiznice ne sme podreti aplikacije
                bralnik = b
                val vir = sk.zvok
                when {
                    vir.startsWith("content://", true) || vir.startsWith("file://", true) -> b.setDataSource(ctx, android.net.Uri.parse(vir))
                    vir.startsWith("/") -> b.setDataSource(vir)
                    else -> b.setDataSource(vir, HashMap<String, String>())
                }
                b.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (e: Exception) { Log.d(TAG, "Trajanje: ${e.message}"); 0L }
            finally { try { bralnik?.release() } catch (_: Exception) {} }
            if (ms > 0L) glavna.post {
                if (moj != zagon || aktivnaSkladba?.id != sk.id) return@post
                val novo = ZvocnikPravila.sTrajanjem(stanje, ms, sk.radio)
                if (novo != stanje) { stanje = novo; javi() }
            }
        }, "safeer-zvocnik-trajanje").apply { isDaemon = true }.start()
    }

    /** Po nasem ukazu naj resnico o zvocniku izvemo takoj, ko mir po ukazu mine (ne sele ob rednem odcitku). */
    private fun kmaluOdcitaj() {
        if (!stanje.igral) return                                   // ob zagonu skladbe odcitki ze tecejo pogosto
        // Odcitek, ki je prav zdaj v teku, bo naslednjega razporedil sam ([razporediOdcitek]): cas si zapomnimo, da odcitek
        // po miru ne izpade in da ne nastaneta dve verigi odcitkov (prej: dvojni promet do konca skladbe).
        kmaluOb = ura() + ZvocnikPravila.MIR_PO_UKAZU_MS + 100L
        straza?.let { glavna.removeCallbacks(it); glavna.postDelayed(it, ZvocnikPravila.MIR_PO_UKAZU_MS + 100L) }
    }

    /** Naslednji odcitek: caka en sam naenkrat; po nasem ukazu najpozneje takrat, ko mine mir ([kmaluOb]). */
    private fun razporediOdcitek(r: Runnable, cezMs: Long) {
        val zdaj = ura()
        val cez = if (kmaluOb > zdaj) minOf(cezMs, kmaluOb - zdaj) else cezMs
        if (kmaluOb <= zdaj) kmaluOb = 0L
        glavna.removeCallbacks(r)
        glavna.postDelayed(r, cez.coerceAtLeast(0L))
    }

    /** Premor: zaslon ga pokaze takoj, zvocnik dobi ukaz v ozadju. */
    fun premor() {
        val z = aktivni ?: return
        if (!stanje.zeliIgrati) return
        ukazov++
        val moj = ++zadnjiPrenos
        var s = ZvocnikPravila.premor(stanje, ura())
        // Preskok, ki je bil narocen tik pred premorom in ga zvocnik se ni dobil, pocaka na nadaljevanje.
        if (nacrtovan >= 0L) { s = s.copy(cakajociPreskokMs = nacrtovan); nacrtovan = -1L; zadnjiPreskok++ }
        stanje = s
        uskladiBudnost()
        javi()
        delavec.execute { try { ukazPrenosa(z, "Pause", emptyList(), moj) } catch (e: Exception) { Log.w(TAG, "Premor: ${e.message}") } }
        kmaluOdcitaj()
    }

    /** Nadaljevanje; preskok, izbran med premorom, gre zvocniku sele zdaj (Play, kratek premor, preskok). */
    fun nadaljuj() {
        val z = aktivni ?: return
        if (stanje.zeliIgrati) return
        ukazov++
        val moj = ++zadnjiPrenos
        val o = ZvocnikPravila.nadaljuj(stanje, ura())
        stanje = o.stanje
        delavec.execute {
            try {
                ukazPrenosa(z, "Play", listOf("Speed" to "1"), moj)
                preskokNePred = ura() + ZvocnikPravila.PREMOR_PRED_PRESKOKOM_MS
            } catch (e: Exception) { Log.w(TAG, "Nadaljevanje: ${e.message}") }
        }
        if (o.dejanje == ZvocnikPravila.Dejanje.PRESKOK) posljiPreskok(o.ciljMs)
        uskladiBudnost()
        javi()
        kmaluOdcitaj()
    }

    fun preklopi() { if (stanje.zeliIgrati) premor() else nadaljuj() }

    /** Novo mesto v skladbi (drsnik, +-10 s). Med premorom pocaka na nadaljevanje - zaslon cilj pokaze takoj. */
    fun skoci(ciljMs: Long) {
        if (aktivni == null) return
        val o = ZvocnikPravila.preskok(stanje, ciljMs, ura())
        if (o.stanje == stanje) return                          // v zivo ali neznano trajanje: ni premika
        ukazov++
        stanje = o.stanje
        if (o.dejanje == ZvocnikPravila.Dejanje.PRESKOK) posljiPreskok(o.ciljMs)
        javi()
    }

    /** Delavec poslje samo zadnji naroceni preskok, in ne prej kot [preskokNePred]. */
    private fun posljiPreskok(ciljMs: Long) {
        val z = aktivni ?: return
        val moj = ++zadnjiPreskok
        nacrtovan = ciljMs
        delavec.execute {
            val caka = preskokNePred - ura()
            if (caka > 0L) try { Thread.sleep(caka.coerceAtMost(1_000L)) } catch (_: InterruptedException) {}
            if (moj != zadnjiPreskok) return@execute            // prehitel ga je novejsi preskok, premor ali nova skladba
            try { soap(z.avUrl, DlnaPravila.AV, "Seek", listOf("Unit" to "REL_TIME", "Target" to DlnaPravila.casZaPreskok(ciljMs))) }
            catch (e: Exception) { Log.w(TAG, "Preskok: ${e.message}") }
            glavna.post { if (moj == zadnjiPreskok) nacrtovan = -1L }
        }
        // Ko zvok pristane, cas spet tece: seja sistema (zaklenjeni zaslon) naj to izve.
        glavna.postDelayed({ if (aktivni === z && moj == zadnjiPreskok) javi() },
            ZvocnikPravila.PREMOR_PRED_PRESKOKOM_MS + ZvocnikPravila.PRISTANEK_MS + 300L)
    }

    // ------------------------------------------------------------------ glasnost
    // Zvocnik je lahko veliko glasnejsi od telefona. Zato: sprememba vedno izhaja iz glasnosti, ki jo zvocnik javi TA HIP
    // (ne iz zadnje znane - medtem jo je lahko kdo spremenil na zvocniku samem), navzgor gre po korakih
    // ([ZvocnikPravila.najvecDvig]) z odmorom med njimi, vsak korak je svoj ukaz delavcu (premor in ustavitev ne cakata
    // za celim dvigom), in dvig se ustavi takoj, ko seje ni vec.

    /** Glasnost zvocnika za korak (tipke, pogovorno okno). Klicati z glavne niti. */
    fun glasnostZa(sprememba: Int) {
        if (aktivni == null || sprememba == 0) return
        cakaGlasnost.tipka(sprememba)          // tipka preklice cilj drsnika ali kretnje, ki se ni dosezen
        sproziGlasnost()
    }

    /** Glasnost zvocnika proti izbrani vrednosti (drsnik sistema, kretnja). Klicati z glavne niti. */
    fun glasnostNa(vrednost: Int) {
        if (aktivni == null) return
        cakaGlasnost.naCilj(vrednost.coerceIn(0, najGlasnost))
        sproziGlasnost()
    }

    /** Glasnost, ki jo zvocnik javi ta hip (kretnja jo vzame za izhodisce). [rezultat] na glavni niti, samo ob uspehu. */
    fun preberiGlasnost(rezultat: (Int) -> Unit) {
        val z = aktivni ?: return
        delavec.execute {
            val v = try { soap(z.rcUrl, DlnaPravila.RC, "GetVolume", listOf("Channel" to "Master"), "CurrentVolume", 3000).toIntOrNull() ?: -1 } catch (_: Exception) { -1 }
            glavna.post {
                if (aktivni !== z || v < 0) return@post
                if (v != glasnost && !glasnostVTeku) { glasnost = v; javi() }
                rezultat(v)
            }
        }
    }

    private fun sproziGlasnost() {
        if (aktivni == null || glasnostVTeku) return
        glasnostVTeku = true
        korakGlasnosti(-1)
    }

    private fun korakGlasnosti(zadnjaPoslana: Int) {
        val z = aktivni
        if (z == null) { glasnostVTeku = false; return }
        delavec.execute {
            var nova = -1
            var dvig = false
            try {
                val (sprememba, cilj) = cakaGlasnost.vzemi()
                if ((sprememba != 0 || cilj >= 0) && aktivni === z) {
                    val zdaj = soap(z.rcUrl, DlnaPravila.RC, "GetVolume", listOf("Channel" to "Master"), "CurrentVolume", 3000).toIntOrNull()
                    if (zdaj != null) {
                        val najvec = najGlasnost
                        val znana = lestvicaZnana
                        val zelena = if (cilj >= 0) ZvocnikPravila.korakProtiCilju(zdaj, cilj, najvec, znana) else ZvocnikPravila.novaGlasnost(zdaj, sprememba, najvec, znana)
                        if (zelena != zdaj) soap(z.rcUrl, DlnaPravila.RC, "SetVolume", listOf("Channel" to "Master", "DesiredVolume" to zelena.toString()), "", 3000)
                        nova = zelena
                        dvig = zelena > zdaj
                        // Izbrana glasnost je se visje: naprej po korakih, dokler uporabnik ne izbere druge. Ce zvocnik koraka
                        // ni izvedel (svoja omejitev glasnosti), odnehamo.
                        if (cilj >= 0 && zelena < cilj.coerceIn(0, najvec) && zelena != zadnjaPoslana) cakaGlasnost.nadaljuj(cilj)
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "Glasnost: ${e.message}") }
            glavna.post {
                if (aktivni !== z) {
                    // Seje ni vec (ali je druga): nedokoncan dvig se ne nadaljuje.
                    cakaGlasnost.pocisti()
                    glasnostVTeku = false
                    return@post
                }
                if (nova >= 0 && nova != glasnost) { glasnost = nova; javi() }
                if (!cakaGlasnost.caka()) { glasnostVTeku = false; return@post }
                if (dvig) glavna.postDelayed({ korakGlasnosti(nova) }, 150L) else korakGlasnosti(nova)
            }
        }
    }

    // ------------------------------------------------------------------ konec seje
    /**
     * Konec na zvocniku (glasba se lahko vrne na to napravo). Preklice tudi sejo, ki se sele zacenja. [konec] pride, ko
     * zvocnik Stop potrdi (null), ali z napako najpozneje po [STOP_HITRO_MS]: klicatelj, ki nato igra na tej napravi, naj
     * zacne sele v [konec] - sicer hip igrata oba. Stop, ki ni uspel, povemo uporabniku in ga ponavljamo v ozadju
     * ([ustaviZanesljivo]); prej je neuspeh ostal le v dnevniku, zvocnik pa je igral naprej brez daljinca.
     */
    fun ustavi(konec: (Exception?) -> Unit) {
        val z = aktivni ?: cakaZagon ?: return konec(null)
        val nasi = nasiNaslovi()
        val zastarelo = zastarelaSeja()
        pocisti()
        ustaviZanesljivo(z, nasi, zastarelo, konec)
    }

    /**
     * Zvocnika ze nekaj casa nismo slisali (ne odziva se ali je naprava spala): medtem ga je lahko prevzel kdo drug, zato
     * pred prvim Stop preverimo, kaj igra (tretji pregled, A1). Samo glavna nit.
     */
    private fun zastarelaSeja(): Boolean = aktivni != null && (stanje.nedosegljiv || ura() - stanje.slisanMs > ZASTAREL_PO_MS)

    /** Naslova, pod katerima zvocnik igra naso skladbo - da pozen Stop ne ustavi cesa tujega. Samo glavna nit. */
    private fun nasiNaslovi(): Set<String> = setOf(aktivniUrl, potrjenUrl).filterTo(HashSet()) { it.isNotBlank() }

    /**
     * Stop z vztrajanjem. [preveriPrvi]: ze pred prvim Stop preveri, ali zvocnik igra nase (zastarela seja, neuspel zagon);
     * sicer to velja sele za ponovitve - pri zivi seji prvi Stop ne caka na dodatno poizvedbo.
     */
    private fun ustaviZanesljivo(z: DlnaPravila.Zvocnik, nasi: Set<String>, preveriPrvi: Boolean = false, konec: (Exception?) -> Unit) {
        var javljeno = false
        fun povej(e: Exception?) {
            if (javljeno) return
            javljeno = true
            try { konec(e) } catch (x: Exception) { Log.w(TAG, "Po ustavitvi: ${x.message}") }
        }
        // Naprava med ustavljanjem ne sme zaspati (seja je ze pospravljena, njen zaklep budnosti sproscen).
        val budna = try {
            app?.let { (it.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "safeer:zvocnik-stop")
                .apply { setReferenceCounted(false); acquire(STOP_VZTRAJA_MS + 15_000L) } }
        } catch (_: Exception) { null }
        fun sprosti() { try { budna?.let { if (it.isHeld) it.release() } } catch (_: Exception) {} }
        // Klicatelj ne caka dlje kot hitri del (delavec je lahko zaseden z odcitkom zvocnika, ki se ne odziva). Uporabniku
        // povemo sele, ko poskus res ne uspe - pocasen, a uspesen Stop ni razlog za opozorilo.
        var opozorjeno = false
        val rok = Runnable { povej(java.util.concurrent.TimeoutException("Stop")) }
        glavna.postDelayed(rok, STOP_HITRO_MS)
        val zacetek = SystemClock.uptimeMillis()
        fun poskus(prvi: Boolean) {
            delavec.execute {
                var napaka: Exception? = null
                var odnehaj = false
                // Nova seja na tem zvocniku: njej ta Stop ni namenjen.
                if ((aktivni ?: cakaZagon)?.udn == z.udn) odnehaj = true
                else try {
                    // Ponovitev pride pozneje: ce zvocnik medtem igra nekaj tujega (drug vhod, druga naprava), ga pustimo.
                    // Nasi naslovi: tisti ob koncu seje in zadnji, ki ga je delavec res poslal (menjava skladbe, ki je bila na poti).
                    val znani = poslanUrl.let { if (it.isBlank() || poslanZa != z.udn) nasi else nasi + it }
                    val tuje = (!prvi || preveriPrvi) && znani.isNotEmpty() && (try { soap(z.avUrl, DlnaPravila.AV, "GetPositionInfo", emptyList(), "TrackURI", 2_000) }
                        catch (_: Exception) { "" }).let { it.isNotBlank() && it !in znani }
                    if (tuje) odnehaj = true else soap(z.avUrl, DlnaPravila.AV, "Stop", emptyList(), "", 2_000)
                } catch (e: IllegalStateException) {
                    // Zvocnik je odgovoril, a ukaza ni izvedel (ze stoji, se nalaga): velja, ce res ne igra.
                    val st = try { soap(z.avUrl, DlnaPravila.AV, "GetTransportInfo", emptyList(), "CurrentTransportState", 2_000) } catch (_: Exception) { "" }
                    if (st != "STOPPED" && st != "NO_MEDIA_PRESENT") napaka = e
                } catch (e: Exception) { napaka = e }
                val n = napaka
                glavna.post {
                    if (n == null) { glavna.removeCallbacks(rok); povej(null); sprosti(); return@post }
                    Log.w(TAG, "Stop: ${n.message}")
                    glavna.removeCallbacks(rok)
                    povej(n)
                    if (!opozorjeno) { opozorjeno = true; povejNeustavljen(z) }
                    if (!odnehaj && SystemClock.uptimeMillis() - zacetek < STOP_VZTRAJA_MS) glavna.postDelayed({ poskus(false) }, STOP_PREMOR_MS) else sprosti()
                }
            }
        }
        poskus(true)
    }

    /** Zvocnik Stopa ni potrdil: uporabnik mora vedeti, da morda se igra (prej je bilo to le v dnevniku). */
    private fun povejNeustavljen(z: DlnaPravila.Zvocnik) {
        val ctx = app ?: return
        try { android.widget.Toast.makeText(ctx, ctx.getString(si.safeer.tv.R.string.zvocnik_ne_ustavi, z.ime), android.widget.Toast.LENGTH_LONG).show() }
        catch (e: Exception) { Log.w(TAG, "Obvestilo: ${e.message}") }
    }

    private fun zapriStreznik() {
        val s = streznik; streznik = null
        if (s != null) delavec.execute { s.zapri() }
    }

    /** Konec seje brez ukaza zvocniku (vrsta je odigrana; zvocnik ze stoji). */
    fun koncajSejo() { if (aktivni != null) pocisti() }

    private fun pocisti() {
        aktivni = null; aktivnaSkladba = null; aktivniUrl = ""; potrjenUrl = ""; cakaZagon = null
        stanje = ZvocnikPravila.Stanje(zeliIgrati = false); glasnost = -1
        zagon++; ukazov++; zadnjiPreskok++; zadnjiPrenos++; nacrtovan = -1L
        cakaGlasnost.pocisti()
        lestvicaZnana = false; kmaluOb = 0L; neodzivnih = 0
        zapriStreznik()
        straza?.let { glavna.removeCallbacks(it) }; straza = null
        sprostiBudnost()
        javi()
    }

    /** Seja se je koncala sama (zvocnik prevzet, skladba ni stekla, premora ni izvedel ali je konec skladbe prepozen). */
    private fun konecSeje(dejanje: ZvocnikPravila.Dejanje) {
        val z = aktivni
        val polozaj = polozaj()
        val zelel = stanje.zeliIgrati
        val k = obKoncu
        val nasi = nasiNaslovi()
        pocisti()
        val povej = { try { k?.invoke(dejanje, polozaj, z?.ime.orEmpty(), zelel) } catch (e: Exception) { Log.w(TAG, "Konec seje: ${e.message}") }; Unit }
        // Skladba, ki ni stekla, naj ne stece pozneje sama (ta naprava jo morda ze igra); zvocnik, ki premora ne izvede,
        // ustavimo. Ta naprava izve sele, ko je Stop potrjen (ali po hitrem roku). Prevzetemu zvocniku ne posljemo nicesar.
        if ((dejanje == ZvocnikPravila.Dejanje.NI_ZACELO || dejanje == ZvocnikPravila.Dejanje.USTAVI) && z != null) ustaviZanesljivo(z, nasi) { povej() }
        else povej()
    }

    // ------------------------------------------------------------------ odcitki
    /**
     * Redni odcitki stanja zvocnika: polozaj in trajanje za zaslon, predvaja/premor (tudi kadar kdo pritisne na zvocniku
     * samem), glasnost, konec skladbe, prevzem zvocnika. Kaj odcitek pomeni, odlocijo [ZvocnikPravila.poOdcitku].
     */
    private fun zazeniStrazo() {
        straza?.let { glavna.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                val z = aktivni ?: return
                val sk = aktivnaSkladba ?: return
                val naroceno = ukazov
                val jaz = this
                delavec.execute {
                    var ok = true
                    var st = ""
                    var v: Map<String, String> = emptyMap()
                    var gl = -1
                    var kdaj = 0L
                    try {
                        st = soap(z.avUrl, DlnaPravila.AV, "GetTransportInfo", emptyList(), "CurrentTransportState", 3000)
                        v = DlnaPravila.vrednosti(klic(z.avUrl, DlnaPravila.AV, "GetPositionInfo", emptyList(), 3000), listOf("RelTime", "TrackDuration", "TrackURI"))
                        kdaj = ura()                                // polozaj velja za ta trenutek, ne za cas obdelave
                    } catch (_: Exception) { ok = false }
                    if (ok) try {
                        gl = soap(z.rcUrl, DlnaPravila.RC, "GetVolume", listOf("Channel" to "Master"), "CurrentVolume", 3000).toIntOrNull() ?: -1
                    } catch (_: Exception) {}
                    glavna.post { obdelajOdcitek(jaz, z, sk, naroceno, ok, st, v, gl, kdaj) }
                }
            }
        }
        straza = r
        glavna.postDelayed(r, ZvocnikPravila.INTERVAL_ZAGON_MS)
    }

    private fun obdelajOdcitek(r: Runnable, z: DlnaPravila.Zvocnik, sk: Jamendo.Skladba, naroceno: Int, ok: Boolean, st: String,
                               v: Map<String, String>, gl: Int, kdaj: Long) {
        if (aktivni !== z || straza !== r) return
        if (!ok) {
            // Zvocnik se ne odziva (omrezje, doseg): seja OSTANE - zvocnik igra naprej in ko se spet oglasi, ga upravljamo
            // naprej ([ZvocnikPravila.poNeuspehu]). Zaslon, obvestilo in seja povedo, da ni dosegljiv.
            val budno = SystemClock.uptimeMillis()
            if (neodzivnih++ == 0) neodzivenOd = budno
            val novo = ZvocnikPravila.poNeuspehu(stanje, neodzivnih, budno - neodzivenOd)
            val sprememba = novo != stanje
            stanje = novo
            // Vsakic, ne le ob spremembi: budnost nedosegljivega zvocnika in nepotrjenega premora ima rok, ki tece s casom
            // (prej je zaklep po izteku roka ostal do desetminutnega samodejnega izteka).
            uskladiBudnost()
            if (sprememba) javi()
            razporediOdcitek(r, ZvocnikPravila.intervalMs(stanje, ura()).coerceAtLeast(ZvocnikPravila.INTERVAL_IGRA_MS))
            return
        }
        neodzivnih = 0
        val bilNedosegljiv = stanje.nedosegljiv
        if (bilNedosegljiv) stanje = ZvocnikPravila.poUspehu(stanje)
        val svez = naroceno == ukazov
        val uri = v["TrackURI"].orEmpty()
        // Zvocnik predvaja nekaj drugega (drug vhod, druga naprava ali program): odnehamo, ne da bi mu kaj poslali. Nas
        // naslov je tisti, ki smo ga poslali, ali tisti, ki ga je zvocnik javil, ko je nasa skladba stekla po nasem Play
        // ([ZvocnikPravila.cigavNaslov]).
        if (svez && uri.isNotBlank() && (st == "PLAYING" || st == "TRANSITIONING" || st == "PAUSED_PLAYBACK")) {
            when (ZvocnikPravila.cigavNaslov(uri, aktivniUrl, potrjenUrl, st, stanje, kdaj)) {
                ZvocnikPravila.Naslov.TUJ -> { konecSeje(ZvocnikPravila.Dejanje.PREVZET); return }
                ZvocnikPravila.Naslov.POTRDI -> potrjenUrl = uri
                ZvocnikPravila.Naslov.NAS -> {}
            }
        }
        val prej = stanje
        val o = ZvocnikPravila.poOdcitku(prej, st, DlnaPravila.casVMs(v["RelTime"].orEmpty()), DlnaPravila.casVMs(v["TrackDuration"].orEmpty()),
            kdaj, svez, sk.radio)
        stanje = o.stanje
        val zdaj = ura()
        when (o.dejanje) {
            ZvocnikPravila.Dejanje.PRESKOK -> {
                preskokNePred = maxOf(preskokNePred, zdaj + ZvocnikPravila.PREMOR_PRED_PRESKOKOM_MS)
                posljiPreskok(o.ciljMs)
            }
            ZvocnikPravila.Dejanje.PREMOR -> {
                val moj = zadnjiPrenos
                delavec.execute { try { ukazPrenosa(z, "Pause", emptyList(), moj) } catch (e: Exception) { Log.w(TAG, "Premor ob zagonu: ${e.message}") } }
            }
            ZvocnikPravila.Dejanje.KONEC_SKLADBE -> {
                // Seja traja: naslednjo skladbo vrste poslje GlasbaStoritev (ali sejo konca, ce je vrsta odigrana).
                straza = null
                val k = obKoncu
                if (k == null) pocisti() else try { k(o.dejanje, o.stanje.trajanjeMs, z.ime, true) } catch (e: Exception) { Log.w(TAG, "Konec skladbe: ${e.message}"); pocisti() }
                return
            }
            ZvocnikPravila.Dejanje.PREVZET, ZvocnikPravila.Dejanje.KONEC_POZEN, ZvocnikPravila.Dejanje.NI_ZACELO,
            ZvocnikPravila.Dejanje.USTAVI -> { konecSeje(o.dejanje); return }
            ZvocnikPravila.Dejanje.NIC -> {}
        }
        var sprememba = bilNedosegljiv || ZvocnikPravila.bistvena(prej, o.stanje, zdaj)
        if (gl >= 0 && gl != glasnost && !glasnostVTeku) { glasnost = gl; sprememba = true }
        uskladiBudnost()
        if (sprememba) javi()
        razporediOdcitek(r, ZvocnikPravila.intervalMs(stanje, zdaj))
    }

    // ------------------------------------------------------------------ budnost naprave
    /** Casovnik izklopa je bil nastavljen ali izklopljen: naprava mora do njegovega izteka ostati budna. */
    fun casovnikSpremenjen() { uskladiBudnost() }

    /**
     * Naprava med predvajanjem na zvocniku ne spi samo, kadar ima se delo ([ZvocnikPravila.potrebujeBudnost]): sicer
     * po koncu skladbe ne bi poslala naslednje in ob casovniku izklopa ne bi ustavila predvajanja (casovniki med spanjem
     * stojijo), zvocnik pa ne bi dobil datoteke te naprave.
     */
    private fun uskladiBudnost() {
        val ctx = app ?: return
        val sk = aktivnaSkladba
        val treba = aktivni != null && sk != null && ZvocnikPravila.potrebujeBudnost(stanje, DlnaPravila.lokalniVir(sk.zvok),
            GlasbaStoritev.zvocnikImaNadaljevanje(), sk.radio, GlasbaStoritev.casovnikMinut() > 0, ura())
        try {
            if (treba) {
                val z = budnost ?: (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "safeer:zvocnik").apply { setReferenceCounted(false) }.also { budnost = it }
                z.acquire(BUDNOST_MS)
            } else sprostiBudnost()
        } catch (e: Exception) { Log.w(TAG, "Budnost: ${e.message}") }
    }

    private fun sprostiBudnost() { try { budnost?.let { if (it.isHeld) it.release() } } catch (_: Exception) {} }
}
