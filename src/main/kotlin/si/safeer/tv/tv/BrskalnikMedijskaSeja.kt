package si.safeer.tv.tv

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import si.safeer.tv.MainActivity
import si.safeer.tv.TvTipke

/**
 * Medijske tipke daljinca za video v strani (TV-PP). Izmerjeno 16. 11. 2026 (dumpsys media_session): Android medijske tipke ne
 * dostavi oknu aplikacije, ampak seji, ki je zadnja postala aktivna - v brskalniku je to bila seja glasbe Safeer OS, zato
 * predvajaj/pavza na video v strani ni naredila nic. Brskalnik zato v ospredju aktivira lastno sejo in tipke posreduje strani
 * (TvTipke.jsMedijskaTipka); med lastnim predvajalnikom (Media3) tipko dobi on. Ob odhodu v ozadje sejo izklopi, da glasba spet
 * dobi tipke.
 */
class BrskalnikMedijskaSeja(private val host: MainActivity) {
    private companion object { const val TAG = "SafeerMedija" }

    private var seja: MediaSession? = null

    private val glavna = Handler(Looper.getMainLooper())

    /** Brskalnik je v ospredju (med onResume in onPause); po posredovanju tipke seje ne smemo vklopiti v ozadju. */
    private var ospredje = false

    /** Tipko smo ravnokar posredovali naprej: dokler seje ne vklopimo znova, jo ignoriramo (zaščita pred zanko). */
    private var posredujem = false

    private val ponovniVklop = Runnable {
        posredujem = false
        if (ospredje) aktiviraj()
    }

    /** Skript za video v strani; odgovor (ok/ni/napaka) gre v dnevnik. Brez videa (ni) tipko dobi glasba v ozadju. */
    private fun zazeni(js: String, keyCode: Int) {
        val wv = host.activeWebView()
        if (wv == null) { Log.w(TAG, "brez aktivne strani"); return }
        wv.evaluateJavascript(js) { odgovor ->
            Log.i(TAG, "odgovor strani: $odgovor")
            if (TvTipke.medijskiOdgovorPosreduj(odgovor)) posreduj(keyCode)
        }
    }

    /**
     * V strani ni videa: sejo za trenutek deaktiviramo, da medijski sistem tipko dostavi naslednji seji (glasba Safeer OS), in
     * jo čez kratek čas znova vklopimo. Uporabnik z daljincem tako krmili glasbo, ne da bi moral zapustiti brskalnik.
     */
    private fun posreduj(keyCode: Int) {
        // Odgovor strani lahko pride, ko brskalnik ze ni vec v ospredju: tipke tedaj ne posiljamo in seje ne vklapljamo.
        if (!ospredje) return
        try {
            posredujem = true
            deaktiviraj()
            val am = host.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            Log.i(TAG, "tipka $keyCode posredovana glasbi")
        } catch (e: Throwable) {
            Log.w(TAG, "posredovanje ni uspelo: ${e.javaClass.simpleName}")
        }
        glavna.removeCallbacks(ponovniVklop)
        glavna.postDelayed(ponovniVklop, 600L)
    }

    /** V onResume: brskalnik je v ospredju, seja postane medijska seja za tipke daljinca. */
    fun vklopi() {
        ospredje = true
        aktiviraj()
    }

    private fun aktiviraj() {
        try {
            val s = seja ?: MediaSession(host, "SafeerBrskalnik").also { nova ->
                nova.setCallback(object : MediaSession.Callback() {
                    @Suppress("DEPRECATION")
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        val e = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                        Log.i(TAG, "tipka=${e?.keyCode} akcija=${e?.action} native=${host.playback.isNativeActive()}")
                        if (e == null) return false
                        if (host.playback.isNativeActive()) return host.playback.handleNativeKey(e)
                        if (posredujem) return true
                        val js = TvTipke.jsMedijskaTipka(e.keyCode) ?: return false
                        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) zazeni(js, e.keyCode)
                        return true
                    }
                    override fun onPlay() { Log.i(TAG, "onPlay"); TvTipke.jsMedijskaTipka(126)?.let { zazeni(it, 126) } }
                    override fun onPause() { Log.i(TAG, "onPause"); TvTipke.jsMedijskaTipka(127)?.let { zazeni(it, 127) } }
                })
                seja = nova
            }
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build()
            )
            s.isActive = true
        } catch (_: Throwable) { }
    }

    /** V onPause: seja se izklopi, glasba spet dobi tipke; čakajočega ponovnega vklopa ni več. */
    fun izklopi() {
        ospredje = false
        posredujem = false
        glavna.removeCallbacks(ponovniVklop)
        deaktiviraj()
    }

    private fun deaktiviraj() {
        try { seja?.isActive = false } catch (_: Throwable) { }
    }

    fun sprosti() {
        ospredje = false
        posredujem = false
        glavna.removeCallbacks(ponovniVklop)
        try { seja?.release() } catch (_: Throwable) { }
        seja = null
    }
}
