package si.safeer.tv.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import org.json.JSONObject
import si.safeer.tv.cast.HubKrmilnik
import si.safeer.tv.cast.HubTls
import si.safeer.tv.cast.KrogNaprave
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/**
 * Deljenje zaslona te naprave (tablica, televizor) prek Safeer Linka - ista storitev kot v brskalniku
 * za telefon (com.safeer.mobile.browser.link), tu z zaupanjem po krogu: pri izvoljenem hubu mora
 * potrdilo poleg odtisa nositi tudi kljuc huba iz kroga zaupanja.
 *
 * Zaslon se zajema z MediaProjection (uporabnik ga dovoli v sistemskem oknu), vsak okvir se
 * pomanjsa in stisne v JPEG ter poslje Hubu po eni sami odprti zahtevi HTTPS; Hub ga prikaze
 * ciljni napravi (npr. Safeer Control na racunalniku). Vse gre po hisnem omrezju, nic v oblak.
 * Storitev tece v ospredju (obvestilo z gumbom za prekinitev), dokler je uporabnik ne ustavi.
 *
 * Zaklep (nacrt »dex-upravljanje« A4): Android 15 QPR1+ ob zaklepu projekcijo ustavi. Tok do Huba takrat ostane odprt:
 * racunalnik namesto zaslona vidi sliko »odkleni za nadaljevanje« (ista pot kot okvirji - starejsi racunalnik in Hub ne
 * potrebujeta nicesar novega), po odklepu pa se z novim soglasjem slika vrne v isto okno. Med cakanjem z naprave ne
 * odide nic drugega kot ta slika, vnos z racunalnika je zavrnjen (Daljinec).
 */
class DeljenjeZaslonaStoritev : Service() {

    companion object {
        private const val TAG = "SafeerDeljenjeZaslona"
        private const val KANAL = "safeer_link_zaslon"
        private const val ID_OBVESTILA = 4242

        const val ACTION_START = "si.safeer.tv.link.ZASLON_START"
        const val ACTION_STOP = "si.safeer.tv.link.ZASLON_STOP"
        /** A3: »Vedno dovoli temu racunalniku« / preklic iz obvestila deljenja (velja za napravo, ki zdaj gleda). */
        const val ACTION_ZAUPAJ = "si.safeer.tv.link.ZASLON_ZAUPAJ"
        const val ACTION_NE_ZAUPAJ = "si.safeer.tv.link.ZASLON_NE_ZAUPAJ"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_CILJ = "cilj"
        const val EXTRA_IME_CILJA = "ime_cilja"
        const val EXTRA_HUB_HTTP = "hub_http"
        const val EXTRA_ZETON = "zeton"
        const val EXTRA_ID_NAPRAVE = "id_naprave"

        /** Najdaljsa stranica poslane slike; dovolj za racunalnik ali televizor, dovolj malo za Wi-Fi. */
        const val NAJVECJA_STRANICA = 1280
        const val KAKOVOST_JPEG = 60
        /** Ciljni razmik med okvirji (ms): ~8 na sekundo. */
        const val RAZMIK_MS = 120L
        /** Ce se slika ne spremeni, vseeno posljemo okvir vsakih toliko, da Hub ve, da smo tu. */
        const val UTRIP_MS = 4000L

        @Volatile var tece: Boolean = false
            private set
        @Volatile var cilj: String = ""
            private set
        /** Znak tega deljenja (DostopPravila.znakGledalca); prazen, dokler sredisce deljenja ne odpre, in po koncu. */
        @Volatile var znakGledalca: String = ""
            private set
        @Volatile var imeCilja: String = ""
            private set
        @Volatile var zadnjaNapaka: String = ""
            private set
        @Volatile var zadnjaKoda: String = ""
            private set
        @Volatile var zadnjaZasedenaOd: String = ""
            private set
        /** A4: projekcijo je ustavil zaklep; tok do Huba je odprt in caka na odklep (racunalnik vidi sporocilo). */
        @Volatile var cakaNaOdklep: Boolean = false
            private set

        /** Stran Safeer Linka se pripne sem, da izve za zacetek, konec in napako. */
        @Volatile var naSpremembo: (() -> Unit)? = null

        fun zazeni(
            context: Context, resultCode: Int, data: Intent, cilj: String, imeCilja: String,
            hubHttp: String, zeton: String, idNaprave: String, odprtoTukajZa: String = ""
        ) {
            // Novo deljenje: zapis, da je prejsnje zacel »Odpri tukaj«, ne velja vec; ce je to deljenje zacel »Odpri tukaj«
            // naprave [odprtoTukajZa], se zapise tu, v istem klicu ([PretokKonec.Zahteve.novoDeljenje]).
            PretokKonec.zahteve.novoDeljenje(odprtoTukajZa)
            val namera = Intent(context, DeljenjeZaslonaStoritev::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_CILJ, cilj)
                putExtra(EXTRA_IME_CILJA, imeCilja)
                putExtra(EXTRA_HUB_HTTP, hubHttp)
                putExtra(EXTRA_ZETON, zeton)
                putExtra(EXTRA_ID_NAPRAVE, idNaprave)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(namera)
            else context.startService(namera)
        }

        /** [pricakovaniCilj] (Grok 23, R2): konec velja samo, ce se deljenje se vedno deli tej napravi; null = brez pogoja. */
        fun ustavi(context: Context, pricakovaniCilj: String? = null) {
            try {
                context.startService(Intent(context, DeljenjeZaslonaStoritev::class.java).apply {
                    action = ACTION_STOP
                    if (pricakovaniCilj != null) putExtra(EXTRA_CILJ, pricakovaniCilj)
                })
            } catch (e: Exception) {
                Log.w(TAG, "Ustavitve ni bilo mogoce zahtevati: ${e.message}")
            }
        }

        /**
         * Pravilo lastnika 25. 10. 2026 (kot core/link_dostop na racunalniku): umik iz ozjega kroga ali odvzem zaslona takoj
         * konca deljenje zaslona tej napravi (tudi med cakanjem na odklep). Klice se ob spremembi dostopa in kroga.
         */
        fun ustaviBrezDostopa(context: Context, spremenjenoJedro: String? = null) {
            val c = cilj
            // Napaka pri branju dostopa = brez dostopa (fail-closed).
            val zmoznosti = try { si.safeer.tv.cast.Dostop.zmoznosti(context, c) } catch (_: Throwable) { emptySet() }
            val sme = si.safeer.tv.cast.DostopPravila.Zmoznost.ZASLON in zmoznosti
            // Grok 15, N1: sprememba kroga (null) ali dostopa DRUGE naprave seje ne konca, razen ce cilj ni vec v ozjem krogu.
            // [spremenjenoJedro] je jedro (Dostop.nastavi: jedro(ctx, id)), zato primerjamo jedro z jedrom.
            val jeCilj = spremenjenoJedro != null && try { si.safeer.tv.cast.Dostop.jedro(context, c) == spremenjenoJedro } catch (_: Throwable) { true }
            // Grok 23, R2: ozji krog po istem pravilu kot povsod (OzjiKrog.vKrogu; napaka = ni v krogu).
            val vKrogu = si.safeer.tv.os.OzjiKrog.vKrogu(context, c)
            if (DeljenjePravila.ustaviZaradiDostopa(tece, cakaNaOdklep, c, sme, vKrogu, jeCilj)) {
                Log.i(TAG, "Deljenje zaslona ustavljeno: naprava nima vec dostopa do zaslona (umik iz ozjega kroga).")
                ustavi(context, pricakovaniCilj = c)
            }
            // Grok 15, N17: odprto vprasanje za napravo, ki ni vec v ozjem krogu, se zapre - zahteva je umaknjena.
            val vprasanZa = PretociActivity.odprtCilj
            if (vprasanZa.isNotBlank() && PretociActivity.vprasanjeOdprto() &&
                (try { si.safeer.tv.cast.Dostop.zmoznosti(context, vprasanZa).isEmpty() } catch (_: Throwable) { true })) {
                Log.i(TAG, "Vprasanje za deljenje zaslona zaprto: naprava ni vec v ozjem krogu.")
                if (PretokKonec.zahteve.caka(PretociActivity.odprtaZahteva)) PretokKonec.zahteve.umakni()
                PretociActivity.zapriCakajoco()
            }
        }

        private fun javi() {
            try { naSpremembo?.invoke() } catch (_: Exception) { }
        }

        /** Besedila obvestila v jeziku naprave (slovenscina ali anglescina). */
        private fun besedilo(context: Context, kljuc: String): String {
            val sl = try { context.resources.configuration.locales[0].language == "sl" } catch (_: Throwable) { false }
            return when (kljuc) {
                "naslov" -> if (sl) "Safeer Link deli zaslon" else "Safeer Link is sharing the screen"
                "besedilo" -> if (sl) "Zaslon vidi {ime}." else "{ime} can see this screen."
                "ustavi" -> if (sl) "Prekini deljenje zaslona" else "Stop sharing the screen"
                "kanal" -> if (sl) "Safeer Link – deljenje zaslona" else "Safeer Link – screen sharing"
                else -> kljuc
            }
        }
    }

    private val glavna = Handler(Looper.getMainLooper())
    // Glavna nit jih zamenja (zaklep, nadaljevanje), nit toka jih bere.
    @Volatile private var projekcija: MediaProjection? = null
    @Volatile private var navidezniZaslon: VirtualDisplay? = null
    @Volatile private var bralec: ImageReader? = null
    private var nit: Thread? = null
    @Volatile private var ustavljam = false
    @Volatile private var idDeljenja = ""
    /** A4: slika za racunalnik med cakanjem na odklep (JPEG); null = brez nje (racunalnik vidi zadnji okvir). */
    @Volatile private var okvirStanja: ByteArray? = null
    private var cakaOd = 0L
    /** Deljenje, ki caka na odklep, je zacel »Odpri tukaj« naprave [cilj] (nadaljevanje to ohrani). */
    private var odprtoTukajCakanja = false
    private var odklepSprejemnik: BroadcastReceiver? = null
    private val rokCakanja = Runnable {
        if (cakaNaOdklep) { Log.i(TAG, "Naprava ni bila odklenjena v roku: deljenje zaslona koncano"); koncaj("") }
    }
    private var hubHttp = ""
    private var zeton = ""
    private var idNaprave = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            // Grok 23, R2: konec zaradi dostopa naprave A ne ustavi deljenja, ki se je vmes zacelo napravi B.
            val pricakovan = if (intent?.action == ACTION_STOP) intent?.getStringExtra(EXTRA_CILJ) else null
            if (DeljenjePravila.ustaviZaCilj(pricakovan, cilj, tece, cakaNaOdklep)) koncaj("")
            else Log.i(TAG, "Konec zaradi dostopa ne velja: deljenje zdaj tece drugi napravi.")
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_ZAUPAJ || intent.action == ACTION_NE_ZAUPAJ) {
            val zaupaj = intent.action == ACTION_ZAUPAJ
            // Pregled dex-a U5: velja samo za napravo iz obvestila, ne med cakanjem na odklep, ponudba se preveri znova.
            // Grok 15, N11: na Androidu 10-11 dejanje v obvestilu ne zahteva odklepa - preverimo zaklep sami (napaka = zaklenjeno).
            val zaklenjeno = try { (getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceLocked } catch (_: Throwable) { true }
            // Pregled 100, N1 (26. 10. 2026): gledalec obvestilo vidi in ga s Safeer Vnosom tapne - »Vedno dovoli« tik po
            // vnosu z druge naprave ne velja (preklic vedno). Ura je ista kot pri zapisu (Daljinec.zabeleziVnosNaDaljavo).
            if (DeljenjePravila.zaupajIzObvestilaBrezVnosa(zaupaj, tece, cilj, intent.getStringExtra(EXTRA_CILJ).orEmpty(), cakaNaOdklep,
                    ZaupanjeZaslona.ponudi(this, cilj), zaklenjeno, Daljinec.zadnjiVnosNaDaljavoMs, android.os.SystemClock.elapsedRealtime())) {
                ZaupanjeZaslona.nastavi(this, cilj, zaupaj)
                posodobiObvestilo()
            } else {
                if (zaupaj && tece) Log.w(TAG, "»Vedno dovoli« iz obvestila zavrnjen (vnos z druge naprave, zaklep ali staro obvestilo)")
                if (!tece) stopSelf(startId)   // staro obvestilo, deljenja ni vec: storitev ne ostane prizgana
            }
            return START_NOT_STICKY
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        // Meritev M1: brez okna za soglasje (trajno dovoljenje) mora biti deljenje na napravi vidno - obvestilo »Ustavi« ali
        // nas gumb na zaslonu. Ce ne gre nobeno (OnePlus 9: obvestila zavrnjena, prikaza ni), deljenja ne zacnemo (spodaj,
        // po startForeground - storitev, zagnana s startForegroundService, se pred njim ne sme ustaviti).
        val vidno = DeljenjePravila.smeZacetiBrezOkna(ZaupanjeZaslona.projekcijaDovoljena(this),
            Daljinec.obvestilaVklopljena(this, KANAL), Daljinec.prekrivanje(this))
        // A4: novo soglasje po odklepu za isti racunalnik - ista seja na Hubu, isto okno na racunalniku.
        if (vidno && tece && data != null && DeljenjePravila.nadaljujVIstemToku(cakaNaOdklep, cilj, intent.getStringExtra(EXTRA_CILJ) ?: "")) {
            if (nadaljuj(resultCode, data)) return START_NOT_STICKY
        }
        if (tece) koncaj("")
        cilj = intent.getStringExtra(EXTRA_CILJ) ?: ""
        imeCilja = intent.getStringExtra(EXTRA_IME_CILJA) ?: cilj
        hubHttp = intent.getStringExtra(EXTRA_HUB_HTTP) ?: ""
        zeton = intent.getStringExtra(EXTRA_ZETON) ?: ""
        idNaprave = intent.getStringExtra(EXTRA_ID_NAPRAVE) ?: ""
        Log.i(TAG, "Zacenjam deljenje zaslona: cilj=${cilj.take(40)} hub=${hubHttp.isNotBlank()} zeton=${zeton.isNotBlank()}")
        if (data == null || cilj.isBlank() || hubHttp.isBlank()) {
            koncaj("Manjkajo podatki za deljenje.")
            return START_NOT_STICKY
        }

        try {
            pripraviKanal()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID_OBVESTILA, obvestilo(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(ID_OBVESTILA, obvestilo())
            }
        } catch (e: Exception) {
            koncaj("Storitve v ospredju ni bilo mogoce zagnati: ${e.message}")
            return START_NOT_STICKY
        }
        if (!vidno) {
            Log.w(TAG, "Deljenje brez okna za soglasje zavrnjeno: ni obvestil ne prikaza cez druge aplikacije")
            koncaj("Deljenja ne začnem: naprava ne bi pokazala, da se zaslon deli. Vklopi obvestila za Safeer.")
            return START_NOT_STICKY
        }

        try {
            val upravitelj = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = upravitelj.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("dovoljenje za zajem zaslona ni bilo dano")
            // Od Androida 14 mora biti povratni klic registriran pred navideznim zaslonom.
            p.registerCallback(povratniKlic(p), glavna)
            projekcija = p
        } catch (e: Exception) {
            koncaj("Zajema zaslona ni bilo mogoce zaceti: ${e.message}")
            return START_NOT_STICKY
        }

        tece = true
        // Deljenje tece: odslej ga ukaz za konec najde po cilju, oznaka zagona ni vec potrebna.
        PretokKonec.zahteve.zagonKoncan()
        pokaziZnak()
        // Pregled dex-a U3: brez okna za soglasje mora biti znak res viden - obvestilo ali gumb, ki se je pokazal.
        if (!DeljenjePravila.znakZagotovljen(ZaupanjeZaslona.projekcijaDovoljena(this), Daljinec.obvestilaVklopljena(this, KANAL), znak != null)) {
            Log.w(TAG, "Deljenje ustavljeno: znak deljenja se ni pokazal in obvestil ni")
            koncaj("Deljenja ne nadaljujem: naprava ne kaže, da se zaslon deli. Vklopi obvestila za Safeer.")
            return START_NOT_STICKY
        }
        zadnjaNapaka = ""
        zadnjaKoda = ""
        zadnjaZasedenaOd = ""
        ustavljam = false
        ohraniZaslon(true)
        javi()
        nit = Thread({ tokZaslona() }, "safeer-zaslon").also { it.start() }
        return START_NOT_STICKY
    }

    /** Konec projekcije [p]: klic stare (zamenjane ali ze pospravljene) projekcije ne velja vec. */
    private fun povratniKlic(p: MediaProjection) = object : MediaProjection.Callback() {
        override fun onStop() { if (p === projekcija) naKonecProjekcije() }
    }

    /** Android je projekcijo ustavil: ob zaklepu pocakamo na odklep (A4), sicer je deljenja konec. */
    private fun naKonecProjekcije() {
        val zaklenjeno = try { (getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked } catch (_: Throwable) { false }
        val prizgan = try { (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive } catch (_: Throwable) { true }
        val konec = DeljenjePravila.konecProjekcije(zaklenjeno, prizgan, ustavljam, tece && idDeljenja.isNotBlank())
        Log.i(TAG, "Projekcija je ustavljena: zaklenjeno=$zaklenjeno zaslon=$prizgan -> $konec")
        if (konec == DeljenjePravila.Konec.ZAKLEP) zacniCakanje() else koncaj("")
    }

    /** A4: projekcije ni vec, tok do Huba ostane; racunalnik vidi »odkleni za nadaljevanje«. */
    private fun zacniCakanje() {
        val zaupano = ZaupanjeZaslona.zaupano(this, cilj)
        val potrdi = DeljenjePravila.poOdklepuPotrdi(zaupano, ZaupanjeZaslona.projekcijaDovoljena(this),
            Daljinec.smeZagnatiIzOzadja(this))
        okvirStanja = try { okvirZaklepa(potrdi) } catch (e: Throwable) { Log.w(TAG, "Slike zaklepa ni bilo mogoce narisati: ${e.message}"); null }
        cakaOd = System.currentTimeMillis()
        odprtoTukajCakanja = PretokKonec.zahteve.odprtoTukajZa() == cilj
        // Najprej oznaka (nit toka ne bere vec bralnika), potem pospravljanje.
        cakaNaOdklep = true
        try { navidezniZaslon?.release() } catch (_: Exception) { }
        navidezniZaslon = null
        try { bralec?.close() } catch (_: Exception) { }
        bralec = null
        projekcija = null
        ohraniZaslon(false)
        skrijZnak()
        prijaviOdklep()
        glavna.removeCallbacks(rokCakanja)
        glavna.postDelayed(rokCakanja, DeljenjePravila.CAKA_ODKLEP_MS)
        posodobiObvestilo()
        javi()
        Log.i(TAG, "Naprava je zaklenjena: deljenje caka na odklep (zaupano=$zaupano)")
    }

    private fun prijaviOdklep() {
        if (odklepSprejemnik != null) return
        val s = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { if (i?.action == Intent.ACTION_USER_PRESENT) poOdklepu() }
        }
        try {
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(s, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(s, filter)
            odklepSprejemnik = s
        } catch (e: Exception) {
            Log.w(TAG, "Odklepa ni mogoce spremljati: ${e.message}")
        }
    }

    private fun odjaviOdklep() {
        val s = odklepSprejemnik ?: return
        odklepSprejemnik = null
        try { unregisterReceiver(s) } catch (_: Exception) { }
    }

    /** Uporabnik je napravo odklenil: zaupana naprava dobi okno za soglasje takoj, druga obvestilo (A4). */
    private fun poOdklepu() {
        if (!cakaNaOdklep) return
        val zaupano = ZaupanjeZaslona.zaupano(this, cilj)
        val kaj = DeljenjePravila.poOdklepu(zaupano, cakaOd, System.currentTimeMillis())
        Log.i(TAG, "Naprava je odklenjena: $kaj")
        when (kaj) {
            DeljenjePravila.Nadaljevanje.KONEC -> koncaj("")
            DeljenjePravila.Nadaljevanje.SAMODEJNO -> Daljinec.nadaljujDeljenje(this, cilj, odprtoTukajCakanja, samodejno = true)
            DeljenjePravila.Nadaljevanje.OBVESTILO -> Daljinec.nadaljujDeljenje(this, cilj, odprtoTukajCakanja, samodejno = false)
        }
    }

    /** Cakanja je konec (nadaljevanje ali konec deljenja). */
    private fun koncajCakanje() {
        glavna.removeCallbacks(rokCakanja)
        odjaviOdklep()
        okvirStanja = null
        cakaNaOdklep = false
    }

    /**
     * A4: novo soglasje po odklepu - nova projekcija v istem toku (ista seja na Hubu, isto okno na racunalniku). False, ce
     * ne gre; takrat se zacne novo deljenje (racunalnik dobi novo okno).
     */
    private fun nadaljuj(resultCode: Int, data: Intent): Boolean {
        var p: MediaProjection? = null
        try {
            // Projekcija je dovoljena samo v storitvi v ospredju vrste mediaProjection: po novem soglasju jo potrdimo znova.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID_OBVESTILA, obvestilo(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(ID_OBVESTILA, obvestilo())
            }
            val upravitelj = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val nova = upravitelj.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("dovoljenje za zajem zaslona ni bilo dano")
            p = nova
            nova.registerCallback(povratniKlic(nova), glavna)
            projekcija = nova
            postaviZajem(meritveZaslona())
        } catch (e: Exception) {
            Log.w(TAG, "Deljenja po odklepu ni bilo mogoce nadaljevati v istem toku: ${e.message}")
            projekcija = null
            try { navidezniZaslon?.release() } catch (_: Exception) { }
            navidezniZaslon = null
            try { bralec?.close() } catch (_: Exception) { }
            bralec = null
            try { p?.stop() } catch (_: Exception) { }
            return false
        }
        // Pregled 100, N2 (26. 10. 2026): isto kot ob prvem zagonu (U3) - brez okna za soglasje mora biti znak res viden, preden
        // je cakanja konec: nit toka poslje prave okvirje, brz ko cakaNaOdklep ni vec true. Zato znak in preverba pred koncajCakanje.
        pokaziZnak()
        if (!DeljenjePravila.znakZagotovljen(ZaupanjeZaslona.projekcijaDovoljena(this), Daljinec.obvestilaVklopljena(this, KANAL), znak != null)) {
            Log.w(TAG, "Deljenje po odklepu ustavljeno: znak deljenja se ni pokazal in obvestil ni")
            // True: deljenje je koncano tu; klicatelj ne sme z istim soglasjem zaceti novega.
            koncaj("Deljenja ne nadaljujem: naprava ne kaže, da se zaslon deli. Vklopi obvestila za Safeer.")
            return true
        }
        koncajCakanje()
        PretokKonec.zahteve.zagonKoncan()
        ohraniZaslon(true)
        posodobiObvestilo()
        javi()
        Log.i(TAG, "Deljenje zaslona se po odklepu nadaljuje v istem toku")
        return true
    }

    /**
     * Slika za racunalnik med cakanjem na odklep: kljucavnica, »… je zaklenjen(a) – odkleni za nadaljevanje« in, kadar bo
     * na napravi treba se kaj potrditi ([potrdi]), druga vrstica. Velikost kot okvirji (ista najvecja stranica).
     */
    private fun okvirZaklepa(potrdi: Boolean): ByteArray {
        val z = meritveZaslona()
        val merilo = minOf(1f, NAJVECJA_STRANICA.toFloat() / maxOf(z.sirina, z.visina))
        val s = maxOf(320, Math.round(z.sirina * merilo))
        val v = maxOf(320, Math.round(z.visina * merilo))
        val slika = Bitmap.createBitmap(s, v, Bitmap.Config.ARGB_8888)
        try {
            val platno = android.graphics.Canvas(slika)
            platno.drawColor(0xFF0B1220.toInt())
            val krajsa = minOf(s, v).toFloat()
            // Kljucavnica: lok in telo.
            val cx = s / 2f
            val vrh = v * 0.30f
            val sirinaTela = krajsa * 0.20f
            val crta = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF94A3B8.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = krajsa * 0.025f
            }
            platno.drawArc(cx - sirinaTela * 0.32f, vrh, cx + sirinaTela * 0.32f, vrh + sirinaTela * 0.64f, 180f, 180f, false, crta)
            platno.drawLine(cx - sirinaTela * 0.32f, vrh + sirinaTela * 0.32f, cx - sirinaTela * 0.32f, vrh + sirinaTela * 0.55f, crta)
            platno.drawLine(cx + sirinaTela * 0.32f, vrh + sirinaTela * 0.32f, cx + sirinaTela * 0.32f, vrh + sirinaTela * 0.55f, crta)
            val telo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF94A3B8.toInt() }
            val zgorajTela = vrh + sirinaTela * 0.52f
            platno.drawRoundRect(cx - sirinaTela / 2, zgorajTela, cx + sirinaTela / 2, zgorajTela + sirinaTela * 0.72f,
                krajsa * 0.02f, krajsa * 0.02f, telo)
            val sirinaBesedila = (s * 0.84f).toInt()
            var y = zgorajTela + sirinaTela * 0.72f + krajsa * 0.08f
            val naslov = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFE2E8F0.toInt(); textSize = krajsa * 0.06f; isFakeBoldText = true
            }
            y += narisiBesedilo(platno, getString(besediloZaklepa()), naslov, sirinaBesedila, (s - sirinaBesedila) / 2f, y) + krajsa * 0.04f
            if (potrdi) {
                val drugo = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF94A3B8.toInt(); textSize = krajsa * 0.042f
                }
                narisiBesedilo(platno, getString(si.safeer.tv.R.string.deljenje_zaklep_potrdi), drugo, sirinaBesedila, (s - sirinaBesedila) / 2f, y)
            }
            val izhod = ByteArrayOutputStream(64 * 1024)
            slika.compress(Bitmap.CompressFormat.JPEG, 80, izhod)
            return izhod.toByteArray()
        } finally {
            slika.recycle()
        }
    }

    /** Narise besedilo na sredini (s prelomi); vrne visino. */
    private fun narisiBesedilo(platno: android.graphics.Canvas, besedilo: String, barva: android.text.TextPaint,
                               sirina: Int, x: Float, y: Float): Float {
        val postavitev = android.text.StaticLayout.Builder.obtain(besedilo, 0, besedilo.length, barva, sirina)
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).build()
        platno.save()
        platno.translate(x, y)
        postavitev.draw(platno)
        platno.restore()
        return postavitev.height.toFloat()
    }

    /** Telefon, tablica ali (televizor, drugo) naprava - kot jo uporabnik pozna. */
    private fun besediloZaklepa(): Int = when (si.safeer.tv.BuildConfig.FLAVOR) {
        "telefon" -> si.safeer.tv.R.string.deljenje_zaklenjen_telefon
        "tablica" -> si.safeer.tv.R.string.deljenje_zaklenjen_tablica
        else -> si.safeer.tv.R.string.deljenje_zaklenjen_naprava
    }

    /** Nas znak deljenja na zaslonu (»Deljenje zaslona · Ustavi«), kadar Safeer sme risati cez druge aplikacije. */
    private var znak: android.view.View? = null

    /**
     * Viden znak deljenja, ki ni odvisen od obvestil (meritev M1: obvestilo »Ustavi« je skrito, ce obvestila niso dovoljena;
     * OnePlus 9 z Androidom 14 sam ne pokaze nicesar). Tap ustavi deljenje. Brez prikaza cez druge aplikacije ga ni - takrat
     * je znak obvestilo; ce ni ne enega ne drugega, se deljenje brez okna za soglasje sploh ne zacne.
     */
    private fun pokaziZnak() {
        if (znak != null || !Daljinec.prekrivanje(this)) return
        try {
            val gostota = resources.displayMetrics.density
            fun dp(v: Int) = Math.round(v * gostota)
            val gumb = android.widget.TextView(this).apply {
                text = getString(si.safeer.tv.R.string.deljenje_znak)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(0xE6B91C1C.toInt())
                }
                contentDescription = text
                setOnClickListener { ustavi(this@DeljenjeZaslonaStoritev) }
            }
            val mesto = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                y = dp(40)
            }
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(gumb, mesto)
            znak = gumb
        } catch (e: Throwable) {
            Log.w(TAG, "Znaka deljenja ni bilo mogoce pokazati: ${e.message}")
        }
    }

    private fun skrijZnak() {
        val v = znak ?: return
        znak = null
        try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Throwable) { }
    }

    private var budnost: android.os.PowerManager.WakeLock? = null

    /**
     * Med deljenjem zaslon ne sme ugasniti (gledalec na racunalniku bi dobil crn zaslon sredi ogleda), sme pa
     * se zatemniti - kot pri zrcaljenju zaslona. Po koncu deljenja spet velja navadni cas ugasnitve.
     */
    private fun ohraniZaslon(da: Boolean) {
        try {
            if (da) {
                if (budnost?.isHeld == true) return
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                @Suppress("DEPRECATION")
                budnost = pm.newWakeLock(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK or android.os.PowerManager.ON_AFTER_RELEASE, "Safeer:DeljenjeZaslona")
                    .apply { setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L) }
            } else {
                budnost?.takeIf { it.isHeld }?.release()
                budnost = null
            }
        } catch (e: Exception) {
            Log.i(TAG, "Zaslona ni mogoce drzati budnega: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ tok

    private fun tokZaslona() {
        var vticnica: Socket? = null
        var napaka = ""
        try {
            // Global Link: zdoma gre deljenje po istem releju kot preostali Link (TLS ostane pripet na odtis Huba).
            hubHttp = potDoHuba(hubHttp)
            val zacetek = zacniNaHubu()
            idDeljenja = zacetek.optString("id", "")
            val potPotiskanja = zacetek.optString("push_path", "")
            if (idDeljenja.isBlank() || potPotiskanja.isBlank()) throw IllegalStateException("Hub ni vrnil poti za deljenje")
            // Dotik gledalca sprejmemo samo z znakom tega deljenja (pozna ga sredisce, ki deljenje gosti).
            znakGledalca = si.safeer.tv.cast.DostopPravila.znakGledalca(
                idDeljenja, potPotiskanja.substringAfter("?k=", "").substringBefore('&'))

            val url = URL(hubHttp)
            if (url.protocol != "https") throw IllegalStateException("Hub brez TLS - posodobi Safeer na gostitelju")
            val vrata = if (url.port > 0) url.port else 443
            // Tok zaslona gre po isti TLS povezavi z istim pripetim odtisom kot vse drugo; pri izvoljenem
            // hubu mora potrdilo nositi se njegov kljuc iz kroga zaupanja.
            val odtis = HubTls.pripetiOdtis(applicationContext)
                ?: throw IllegalStateException("naprava s tem Hubom ni seznanjena")
            val kljuc = try {
                HubKrmilnik.izvoljeniHub(applicationContext)?.let { KrogNaprave.kljucHuba(applicationContext, it.id) }
            } catch (_: Throwable) { null }
            val (tovarna, _) = HubTls.odjemalec(odtis, kljuc)
            vticnica = (tovarna.createSocket(url.host, vrata) as javax.net.ssl.SSLSocket).apply {
                tcpNoDelay = true
                soTimeout = 0
                HubTls.nastaviOdjemalsko(this)
                startHandshake()
            }
            val izhod = DataOutputStream(vticnica.getOutputStream().buffered(256 * 1024))
            izhod.write(("POST $potPotiskanja HTTP/1.1\r\nHost: ${url.host}\r\n" +
                "x-safeer-token: $zeton\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n")
                .toByteArray(Charsets.US_ASCII))
            izhod.flush()

            zajemaj(izhod)
        } catch (e: Exception) {
            if (!ustavljam) {
                napaka = e.message ?: "napaka pri deljenju"
                Log.w(TAG, "Deljenje zaslona se je prekinilo: $napaka")
            }
        } finally {
            try { vticnica?.close() } catch (_: Exception) { }
            glavna.post { koncaj(napaka) }
        }
    }

    /** LAN ali krajevna vrata releja do izvoljenega Huba ([DeljenjePravila.prekReleja]); klic z delovne niti (omrezje). */
    private fun potDoHuba(lan: String): String {
        val gl = GlobalLink
        if (!DeljenjePravila.prekReleja(gl.vklopljen(this), gl.zdoma, gl.samoRele(this)) { gl.lanDosegljiv(lan) }) return lan
        val prek = gl.naslov(this, lan.replace(Regex("^https"), "wss"), HubKrmilnik.izvoljeniHub(this)?.id, prekRele = true)
        val naslov = DeljenjePravila.httpIzWs(prek)
        Log.i(TAG, "deljenje zaslona: pot=" + if (gl.jeRele(java.net.URI(naslov).host, java.net.URI(naslov).port)) "global" else "lan")
        return naslov
    }

    /** Hub odpre deljenje in sam obvesti ciljno napravo, kje naj gleda. */
    private fun zacniNaHubu(): JSONObject {
        val telo = JSONObject().put("device_id", idNaprave).put("target", cilj).toString()
        val (koda, odgovor) = http("POST", "$hubHttp/cast/share/screen/start", telo)
        if (koda != 200) {
            val o = try { JSONObject(odgovor) } catch (_: Exception) { JSONObject() }
            val sporocilo = o.optString("napaka", "")
            zadnjaKoda = o.optString("koda", "")
            zadnjaZasedenaOd = o.optString("busy_by_name", "").ifBlank { o.optString("busy_by", "") }
            throw IllegalStateException(if (sporocilo.isNotBlank()) sporocilo else "Hub je zavrnil deljenje ($koda)")
        }
        return JSONObject(odgovor)
    }

    private fun http(metoda: String, naslov: String, telo: String): Pair<Int, String> {
        val povezava = URL(naslov).openConnection() as HttpURLConnection
        try {
            HubTls.zavaruj(povezava, applicationContext)
            povezava.requestMethod = metoda
            povezava.connectTimeout = 5000
            povezava.readTimeout = 8000
            povezava.setRequestProperty("x-safeer-token", zeton)
            povezava.setRequestProperty("Content-Type", "application/json")
            povezava.doOutput = true
            povezava.outputStream.use { it.write(telo.toByteArray(Charsets.UTF_8)) }
            val koda = povezava.responseCode
            val tok = if (koda >= 400) povezava.errorStream else povezava.inputStream
            val vsebina = tok?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return koda to vsebina
        } finally {
            povezava.disconnect()
        }
    }

    // ------------------------------------------------------------------ zajem

    private class Zajem(val sirina: Int, val visina: Int, val gostota: Int)

    private fun meritveZaslona(): Zajem {
        val dm = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return Zajem(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }

    private fun postaviZajem(z: Zajem) {
        val p = projekcija ?: throw IllegalStateException("projekcije ni")
        val novi = ImageReader.newInstance(z.sirina, z.visina, PixelFormat.RGBA_8888, 2)
        val stari = bralec
        val vd = navidezniZaslon
        if (vd == null) {
            navidezniZaslon = p.createVirtualDisplay(
                "SafeerLink", z.sirina, z.visina, z.gostota,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, novi.surface, null, null
            )
        } else {
            // Obrat naprave: isti navidezni zaslon, nova velikost in nova povrsina.
            vd.resize(z.sirina, z.visina, z.gostota)
            vd.surface = novi.surface
        }
        bralec = novi
        try { stari?.close() } catch (_: Exception) { }
    }

    private fun zajemaj(izhod: OutputStream) {
        var trenutna = meritveZaslona()
        postaviZajem(trenutna)
        val jpeg = ByteArrayOutputStream(256 * 1024)
        var zadnjiOkvir: ByteArray? = null
        var zadnjiPoslan = 0L
        var platno: Bitmap? = null
        var pomanjsano: Bitmap? = null
        var preverjanje = 0L

        var poslanoStanje: ByteArray? = null
        while (!ustavljam && tece) {
            val zdaj = System.currentTimeMillis()
            if (cakaNaOdklep) {
                // A4: projekcije ni (zaklep) - racunalnik vidi sliko »odkleni za nadaljevanje«, nic drugega.
                val stanje = okvirStanja
                if (stanje != null && (stanje !== poslanoStanje || zdaj - zadnjiPoslan > UTRIP_MS)) {
                    posljiOkvir(izhod, stanje)
                    poslanoStanje = stanje
                    zadnjiPoslan = zdaj
                }
                // Po nadaljevanju ne posljemo vec okvirja izpred zaklepa.
                zadnjiOkvir = null
                Thread.sleep(100)
                continue
            }
            poslanoStanje = null
            if (zdaj - preverjanje > 700) {
                preverjanje = zdaj
                val nova = meritveZaslona()
                if (nova.sirina != trenutna.sirina || nova.visina != trenutna.visina) {
                    trenutna = nova
                    try {
                        postaviZajem(nova)
                    } catch (e: IllegalStateException) {
                        // Med obratom se je naprava zaklenila (projekcije ni vec): nic hudega, caka na odklep.
                        if (!cakaNaOdklep) throw e
                    }
                    platno = null
                    pomanjsano = null
                }
            }
            val slika = try { bralec?.acquireLatestImage() } catch (_: Exception) { null }
            if (slika == null) {
                if (zadnjiOkvir != null && zdaj - zadnjiPoslan > UTRIP_MS) {
                    posljiOkvir(izhod, zadnjiOkvir)
                    zadnjiPoslan = zdaj
                }
                Thread.sleep(25)
                continue
            }
            try {
                val ravnina = slika.planes[0]
                val korakSlikovne = ravnina.pixelStride
                val korakVrstice = ravnina.rowStride
                val sirinaZRobom = korakVrstice / korakSlikovne
                val p: Bitmap = platno?.takeIf { it.width == sirinaZRobom && it.height == slika.height }
                    ?: Bitmap.createBitmap(sirinaZRobom, slika.height, Bitmap.Config.ARGB_8888).also { platno = it }
                p.copyPixelsFromBuffer(ravnina.buffer)
                val sirina = slika.width
                val visina = slika.height
                // Enakomerna pomanjsava po obeh oseh: razmerje ostane natanko tako, kot je na zaslonu.
                val merilo = minOf(1f, NAJVECJA_STRANICA.toFloat() / maxOf(sirina, visina))
                val cs = maxOf(1, Math.round(sirina * merilo))
                val cv = maxOf(1, Math.round(visina * merilo))
                val m: Bitmap = pomanjsano?.takeIf { it.width == cs && it.height == cv }
                    ?: Bitmap.createBitmap(cs, cv, Bitmap.Config.ARGB_8888).also { pomanjsano = it }
                val risalo = android.graphics.Canvas(m)
                val izvor = android.graphics.Rect(0, 0, sirina, visina)
                val ciljni = android.graphics.Rect(0, 0, cs, cv)
                risalo.drawBitmap(p, izvor, ciljni, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                jpeg.reset()
                m.compress(Bitmap.CompressFormat.JPEG, KAKOVOST_JPEG, jpeg)
                val okvir = jpeg.toByteArray()
                zadnjiOkvir = okvir
                posljiOkvir(izhod, okvir)
                zadnjiPoslan = System.currentTimeMillis()
            } finally {
                slika.close()
            }
            val porabljeno = System.currentTimeMillis() - zdaj
            if (porabljeno < RAZMIK_MS) Thread.sleep(RAZMIK_MS - porabljeno)
        }
    }

    private fun posljiOkvir(izhod: OutputStream, okvir: ByteArray) {
        val d = okvir.size
        izhod.write(byteArrayOf((d ushr 24).toByte(), (d ushr 16).toByte(), (d ushr 8).toByte(), d.toByte()))
        izhod.write(okvir)
        izhod.flush()
    }

    // ------------------------------------------------------------------ konec

    private fun koncaj(napaka: String) {
        // Deljenje je koncano (ali se ni moglo zaceti): oznaka »zaganja se« ne velja vec. Pri zamenjavi deljenja (nov zagon,
        // medtem ko staro tece) se novo zacne v istem klicu na glavni niti - ukaz za konec vmes ne more priti.
        PretokKonec.zahteve.zagonKoncan()
        koncajCakanje()
        skrijZnak()
        val jeTeklo = tece
        ustavljam = true
        tece = false
        znakGledalca = ""
        ohraniZaslon(false)
        if (napaka.isNotBlank()) { zadnjaNapaka = napaka; Log.w(TAG, "Deljenje zaslona koncano z napako: $napaka") }
        try { navidezniZaslon?.release() } catch (_: Exception) { }
        navidezniZaslon = null
        try { bralec?.close() } catch (_: Exception) { }
        bralec = null
        try { projekcija?.stop() } catch (_: Exception) { }
        projekcija = null
        if (jeTeklo && idDeljenja.isNotBlank()) {
            // Hub ob zaprti vticnici konca sam; izrecen stop pove cilju takoj, ne sele po premoru.
            val id = idDeljenja
            idDeljenja = ""
            Thread {
                try { http("POST", "$hubHttp/cast/share/screen/stop", JSONObject().put("id", id).toString()) }
                catch (e: Exception) { Log.i(TAG, "Stop Hubu ni bil sporocen: ${e.message}") }
            }.start()
        }
        javi()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        if (tece) koncaj("")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ obvestilo

    private fun pripraviKanal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val upravitelj = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (upravitelj.getNotificationChannel(KANAL) != null) return
        val kanal = NotificationChannel(KANAL, besedilo(this, "kanal"), NotificationManager.IMPORTANCE_LOW)
        kanal.setShowBadge(false)
        upravitelj.createNotificationChannel(kanal)
    }

    private fun obvestilo(): Notification {
        var zastavice = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) zastavice = zastavice or PendingIntent.FLAG_IMMUTABLE
        val ustavi = PendingIntent.getService(
            this, 1, Intent(this, DeljenjeZaslonaStoritev::class.java).apply { action = ACTION_STOP }, zastavice)
        val gradnik = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, KANAL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        val ikona = android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel)
        gradnik
            .setContentTitle(besedilo(this, "naslov"))
            .setContentText(if (cakaNaOdklep) getString(si.safeer.tv.R.string.deljenje_caka_obvestilo)
                else besedilo(this, "besedilo").replace("{ime}", imeCilja))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(ikona, besedilo(this, "ustavi"), ustavi).build())
        // A3: »Vedno dovoli temu racunalniku« za napravo iz ozjega kroga (ali preklic) - en tap, brez dodatnega okna.
        try {
            val vedno = cilj.isNotBlank() && ZaupanjeZaslona.vedno(this, cilj) && ZaupanjeZaslona.vKrogu(this, cilj)
            // Pregled dex-a U5: »Vedno dovoli« ne med cakanjem na odklep; namera nosi napravo; od API 31 samo odklenjeno.
            val ponudi = cilj.isNotBlank() && !vedno && !cakaNaOdklep && ZaupanjeZaslona.ponudi(this, cilj)
            if (vedno || ponudi) {
                val dejanje = if (vedno) ACTION_NE_ZAUPAJ else ACTION_ZAUPAJ
                val cakajoca = PendingIntent.getService(this, if (vedno) 3 else 2,
                    Intent(this, DeljenjeZaslonaStoritev::class.java).apply { action = dejanje; putExtra(EXTRA_CILJ, cilj) }, zastavice)
                val oznaka = if (vedno) getString(si.safeer.tv.R.string.deljenje_ne_vec) else Daljinec.oznakaVednoDovoli(this, cilj)
                val kljucavnica = android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_lock_idle_lock)
                val akcija = Notification.Action.Builder(kljucavnica, oznaka, cakajoca)
                if (!vedno && Build.VERSION.SDK_INT >= 31) akcija.setAuthenticationRequired(true)
                gradnik.addAction(akcija.build())
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Gumba »Vedno dovoli« ni bilo mogoce dodati: ${e.message}")
        }
        return gradnik.build()
    }

    private fun posodobiObvestilo() {
        if (!tece) return
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID_OBVESTILA, obvestilo()) }
        catch (e: Exception) { Log.w(TAG, "Obvestila ni bilo mogoce posodobiti: ${e.message}") }
    }
}
