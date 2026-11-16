package si.safeer.tv.tv

import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
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

    /** Skript za video v strani; odgovor (ok/ni/napaka) gre v dnevnik za meritve na napravi. */
    private fun zazeni(js: String) {
        val wv = host.activeWebView()
        if (wv == null) { Log.w(TAG, "brez aktivne strani"); return }
        wv.evaluateJavascript(js) { odgovor -> Log.i(TAG, "odgovor strani: $odgovor") }
    }

    fun vklopi() {
        try {
            val s = seja ?: MediaSession(host, "SafeerBrskalnik").also { nova ->
                nova.setCallback(object : MediaSession.Callback() {
                    @Suppress("DEPRECATION")
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        val e = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                        Log.i(TAG, "tipka=${e?.keyCode} akcija=${e?.action} native=${host.playback.isNativeActive()}")
                        if (e == null) return false
                        if (host.playback.isNativeActive()) return host.playback.handleNativeKey(e)
                        val js = TvTipke.jsMedijskaTipka(e.keyCode) ?: return false
                        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) zazeni(js)
                        return true
                    }
                    override fun onPlay() { Log.i(TAG, "onPlay"); TvTipke.jsMedijskaTipka(126)?.let { zazeni(it) } }
                    override fun onPause() { Log.i(TAG, "onPause"); TvTipke.jsMedijskaTipka(127)?.let { zazeni(it) } }
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

    fun izklopi() {
        try { seja?.isActive = false } catch (_: Throwable) { }
    }

    fun sprosti() {
        try { seja?.release() } catch (_: Throwable) { }
        seja = null
    }
}
