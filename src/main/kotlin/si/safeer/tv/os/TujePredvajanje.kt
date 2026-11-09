package si.safeer.tv.os

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Predvajanje DRUGIH aplikacij (SmartTube, YouTube ...) za mali predvajalnik na domacem zaslonu - lastnik 8. 11. 2026
 * 06:30: »ce predvaja glasbo, jo lahko v mini predvajalniku upravljamo«. Seje predvajanja drugih aplikacij sistem da
 * samo poslusalcu obvestil z dovoljenjem »Dostop do obvestil« ([ZnackeObvestil]), ki ga uporabnik vklopi sam; brez
 * njega je [stanje] null in mali predvajalnik kaze samo nase predvajanje. Hranimo samo naslov, izvajalca in ali igra -
 * nic ne zapusti naprave.
 */
object TujePredvajanje {
    private const val TAG = "SafeerTujePredvajanje"

    data class Stanje(val paket: String, val naslov: String, val izvajalec: String, val igra: Boolean)

    /** Izbrana seja druge aplikacije (igra ima prednost pred premorom) ali null. Bere glavna nit. */
    @Volatile var stanje: Stanje? = null
        private set
    val poslusalci = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    private val glavna = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private var komponenta: ComponentName? = null
    private var mojPaket = ""
    private var aplikacija: Context? = null
    private var kontroler: MediaController? = null
    private val sejeSpremenjene = MediaSessionManager.OnActiveSessionsChangedListener { izberi(it.orEmpty()) }
    private val povratni = object : MediaController.Callback() {
        // Slepi pregled: izbrana seja ne igra vec - morda igra druga (seznam sej se ob tem ne spremeni nujno).
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (!MiniPredvajalnikPravila.tujaIgra(state?.state ?: PlaybackState.STATE_NONE)) izberiZnova() else osvezi()
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) { osvezi() }
        override fun onSessionDestroyed() { izberiZnova() }
    }

    /** Poslusalec obvestil je povezan (dovoljenje dano): spremljaj seje predvajanja. */
    fun zacni(c: Context, k: ComponentName) {
        glavna.post {
            try {
                val m = c.applicationContext.getSystemService(MediaSessionManager::class.java) ?: return@post
                ustaviNaGlavni()
                komponenta = k; mojPaket = c.packageName; aplikacija = c.applicationContext
                m.addOnActiveSessionsChangedListener(sejeSpremenjene, k, glavna)
                // CodeRabbit 8. 11.: aktivno (msm) sele po uspesni prijavi - sicer zagotovi() ne bi nikoli poskusil znova.
                msm = m
                izberi(m.getActiveSessions(k))
                Log.i(TAG, "seje drugih aplikacij: spremljam")
            } catch (e: SecurityException) {
                ustaviNaGlavni()
                Log.w(TAG, "seje drugih aplikacij: ni dovoljenja (${e.message})")
            } catch (e: Throwable) {
                ustaviNaGlavni()
                Log.w(TAG, "seje drugih aplikacij: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Ali seje ze spremljamo ([zacni] je uspel). */
    fun aktivno(): Boolean = msm != null

    /**
     * Zaganjalnik ob vsakem prikazu: ce je »Dostop do obvestil« dan, seje spremljamo tudi, ce sistem poslusalca ob
     * posodobitvi aplikacije ni (se) povezal - getActiveSessions potrebuje samo omogoceno komponento (TV 8. 11. 2026).
     */
    fun zagotovi(c: Context) {
        if (aktivno()) return
        val k = ComponentName(c, ZnackeObvestil::class.java)
        // TV 8. 11. 2026 (apk-0711-12): po posodobitvi je sistem javil »already bound« in poslusalca v novem procesu ni
        // nikoli povezal (onListenerConnected ni bil klican) - zaprosimo za ponovno vezavo in seje beremo kar tu:
        // getActiveSessions s komponento uspe, ce je »Dostop do obvestil« dan, sicer SecurityException (ujeta v zacni).
        if (!ZnackeObvestil.povezan) try { android.service.notification.NotificationListenerService.requestRebind(k) } catch (_: Throwable) { }
        Log.i(TAG, "seje drugih aplikacij: zacenjam iz zaganjalnika (poslusalec povezan=${ZnackeObvestil.povezan})")
        zacni(c, k)
    }

    /** Dovoljenje preklicano ali poslusalec odklopljen: nic vec ne spremljamo. */
    fun ustavi() { glavna.post { ustaviNaGlavni() } }

    private fun ustaviNaGlavni() {
        try { msm?.removeOnActiveSessionsChangedListener(sejeSpremenjene) } catch (_: Throwable) { }
        try { kontroler?.unregisterCallback(povratni) } catch (_: Throwable) { }
        msm = null; kontroler = null; igraBrezPrikaza = null
        objavi(null)
    }

    private fun izberiZnova() {
        val m = msm ?: return
        val k = komponenta ?: return
        try { izberi(m.getActiveSessions(k)) } catch (_: Throwable) { izberi(emptyList()) }
    }

    private fun izberi(seje: List<MediaController>) {
        val drugih = seje.filter { it.packageName != mojPaket }
        val i = MiniPredvajalnikPravila.izberiTujo(drugih.map { it.playbackState?.state ?: PlaybackState.STATE_NONE })
        val nov = drugih.getOrNull(i)
        if (nov?.sessionToken != kontroler?.sessionToken) {
            try { kontroler?.unregisterCallback(povratni) } catch (_: Throwable) { }
            kontroler = nov
            try { nov?.registerCallback(povratni, glavna) } catch (_: Throwable) { }
        }
        osvezi()
    }

    private fun osvezi() {
        val k = kontroler
        val md = try { k?.metadata } catch (_: Throwable) { null }
        val st = try { k?.playbackState?.state } catch (_: Throwable) { null } ?: PlaybackState.STATE_NONE
        // Ustavljena z X se spet pokaze, ko zaigra (uporabnik jo je v aplikaciji znova zagnal).
        if (k != null && MiniPredvajalnikPravila.tujaIgra(st) && k.packageName == ustavljenIzDocka) ustavljenIzDocka = null
        val s = k?.let {
            // TV 8. 11. 2026: SmartTube v seji ne objavi naslova (metadata description=null) - brez njega malega
            // predvajalnika ni bilo. Namesto naslova ime aplikacije.
            Stanje(it.packageName,
                MiniPredvajalnikPravila.naslovTuje(md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { t -> t.isNotBlank() }
                    ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty(),
                    imeAplikacije(it.packageName)),
                md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty(),
                MiniPredvajalnikPravila.tujaIgra(st))
        }?.takeIf { MiniPredvajalnikPravila.tujaZaPrikaz(it.naslov, st) }
            ?.takeIf { MiniPredvajalnikPravila.prikaziTujo(it.paket, it.igra, ustavljenIzDocka) }
        // CodeRabbit: paket, ki igra, se steje tudi brez naslova (seja brez metapodatkov) - ciscenje ga ne zapre.
        igraBrezPrikaza = k?.packageName?.takeIf { MiniPredvajalnikPravila.tujaIgra(st) }
        objavi(s)
    }

    private fun objavi(s: Stanje?) {
        if (s == stanje) return
        stanje = s
        // TV 8. 11. 2026: po namestitvi SmartTube ni bil v malem predvajalniku in ciscenje ga je zaprlo - brez sledi v dnevniku.
        Log.i(TAG, "seja druge aplikacije: ${s?.paket ?: "nobena"} (igra=${s?.igra})")
        for (p in poslusalci) try { p() } catch (_: Throwable) { }
    }

    @Volatile private var igraBrezPrikaza: String? = null

    /** Paket in cas premora, ki ga je uporabnik dal v malem predvajalniku (CodeRabbit: zascita velja samo za ta paket). */
    @Volatile private var premorIzDocka: Pair<String, Long>? = null

    /** Paket, ki zdaj igra ali ga je uporabnik v malem predvajalniku dal na premor (za ciscenje ozadja), ali null. */
    fun igraPaket(): String? = stanje?.takeIf {
        MiniPredvajalnikPravila.tujaZascitena(it.igra, premorIzDocka?.takeIf { p -> p.first == it.paket }?.second ?: 0L, System.currentTimeMillis())
    }?.paket ?: igraBrezPrikaza

    /** Paket, ki ZDAJ igra (ne zascita premora) - samo ta sme pojasniti zvok v ozadju. */
    fun igraZdaj(): String? = stanje?.takeIf { it.igra }?.paket ?: igraBrezPrikaza

    private fun imeAplikacije(paket: String): String = try {
        val pm = aplikacija?.packageManager ?: return ""
        pm.getApplicationLabel(pm.getApplicationInfo(paket, 0)).toString()
    } catch (_: Throwable) { "" }

    fun preklopi() {
        val t = kontroler?.transportControls ?: return
        val s = stanje
        if (s?.igra == true) { premorIzDocka = s.paket to System.currentTimeMillis(); t.pause() } else { premorIzDocka = null; t.play() }
    }

    /** Samo premor: seje na premoru ne nadaljuje. */
    fun premor() { if (stanje?.igra == true) preklopi() }

    /** Paket, ki ga je uporabnik ustavil z X v malem predvajalniku - ne prikazemo ga, dokler spet ne igra. */
    @Volatile private var ustavljenIzDocka: String? = null

    /**
     * Lastnik 9. 11. 2026 (posnetek TV: X na SmartTube ni zaprl predvajanja): X pomeni konec predvajanja. Seja dobi stop,
     * izgine iz malega predvajalnika in aplikacija, ki ne predvaja, se zapre (kot ob Domov). Prej samo premor, ki je
     * aplikacijo celo zascitil pred zapiranjem (premorIzDocka).
     */
    fun ustaviIzDocka(c: Context) {
        val k = kontroler ?: return
        premorIzDocka = null
        val paket = k.packageName
        ustavljenIzDocka = paket
        try { k.transportControls.stop() } catch (_: Throwable) { }
        objavi(null)
        // Slepi pregled 9. 11.: ustavljeni paket se ob kliku se steje kot igrajoc (igraBrezPrikaza, isMusicActive) in bi ostal
        // odprt - pocistiOzadje ga izloci in zvok izmeri po zamiku. Ce po zamiku se igra (stop prezrt), ne zapre nicesar.
        Pomnilnik.pocistiOzadje(c.applicationContext, cakajMs = MiniPredvajalnikPravila.ZAPRI_PO_USTAVITVI_MS,
            nedejavnostMs = OsPravila.VRNITEV_DOMOV_NEDEJAVNOST_MS, ponavljaj = true, ustavljen = paket)
    }

    /** Kliknjen naslov v malem predvajalniku: odpre aplikacijo, ki igra. */
    fun odpri(c: Context): Boolean {
        val p = stanje?.paket ?: return false
        val i = c.packageManager.getLeanbackLaunchIntentForPackage(p) ?: c.packageManager.getLaunchIntentForPackage(p) ?: return false
        return try { c.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Throwable) { false }
    }
    /**
     * Cel zaslon: najprej zaslon predvajanja, ki ga seja sama ponudi (sessionActivity), sicer aplikacija. TV 8. 11. 2026:
     * SmartTube iz ozadja z zagonskim namenom odpre seznam posnetkov, ne predvajalnika.
     */
    fun odpriCelZaslon(c: Context): Boolean {
        val pi = try { kontroler?.sessionActivity } catch (_: Throwable) { null }
        if (pi != null) {
            try {
                // Klik v malem predvajalniku je na vidnem zaganjalniku: dovolj je ALLOW_IF_VISIBLE (API 36, skil Android oddaljeno).
                if (android.os.Build.VERSION.SDK_INT >= 36) {
                    pi.send(c, 0, null, null, null, null, android.app.ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE).toBundle())
                } else if (android.os.Build.VERSION.SDK_INT >= 34) {
                    @Suppress("DEPRECATION")
                    pi.send(c, 0, null, null, null, null, android.app.ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle())
                } else pi.send()
                return true
            } catch (_: Throwable) { }
        }
        return odpri(c)
    }

    fun naslednja() { kontroler?.transportControls?.skipToNext() }
    fun prejsnja() { kontroler?.transportControls?.skipToPrevious() }
}
