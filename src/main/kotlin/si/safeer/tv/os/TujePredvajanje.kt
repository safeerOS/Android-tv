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
                msm = m; komponenta = k; mojPaket = c.packageName
                m.addOnActiveSessionsChangedListener(sejeSpremenjene, k, glavna)
                izberi(m.getActiveSessions(k))
                Log.i(TAG, "seje drugih aplikacij: spremljam")
            } catch (e: SecurityException) {
                Log.w(TAG, "seje drugih aplikacij: ni dovoljenja (${e.message})")
            } catch (e: Throwable) {
                Log.w(TAG, "seje drugih aplikacij: ${e.javaClass.simpleName}")
            }
        }
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
        val s = k?.let {
            Stanje(it.packageName,
                md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty(),
                md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty(),
                MiniPredvajalnikPravila.tujaIgra(st))
        }?.takeIf { MiniPredvajalnikPravila.tujaZaPrikaz(it.naslov, st) }
        // CodeRabbit: paket, ki igra, se steje tudi brez naslova (seja brez metapodatkov) - ciscenje ga ne zapre.
        igraBrezPrikaza = k?.packageName?.takeIf { MiniPredvajalnikPravila.tujaIgra(st) }
        objavi(s)
    }

    private fun objavi(s: Stanje?) {
        if (s == stanje) return
        stanje = s
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

    fun preklopi() {
        val t = kontroler?.transportControls ?: return
        val s = stanje
        if (s?.igra == true) { premorIzDocka = s.paket to System.currentTimeMillis(); t.pause() } else { premorIzDocka = null; t.play() }
    }

    /** Samo premor (gumb X v malem predvajalniku): seje na premoru ne nadaljuje. */
    fun premor() { if (stanje?.igra == true) preklopi() }

    /** Kliknjen naslov v malem predvajalniku: odpre aplikacijo, ki igra. */
    fun odpri(c: Context): Boolean {
        val p = stanje?.paket ?: return false
        val i = c.packageManager.getLeanbackLaunchIntentForPackage(p) ?: c.packageManager.getLaunchIntentForPackage(p) ?: return false
        return try { c.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Throwable) { false }
    }
    fun naslednja() { kontroler?.transportControls?.skipToNext() }
    fun prejsnja() { kontroler?.transportControls?.skipToPrevious() }
}
