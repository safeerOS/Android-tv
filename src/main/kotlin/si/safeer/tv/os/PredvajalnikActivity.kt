// Media3 oznacuje del API-ja kot @UnstableApi (se lahko spremeni med razlicicami). Uporabljamo ga
// namerno (DASH, lasten vir podatkov); ob posodobitvi Media3 to datoteko preverimo.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package si.safeer.tv.os

import si.safeer.tv.R

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import android.widget.FrameLayout
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.Locale

/**
 * Predvajalnik videa in glasbe z racunalnika (ExoPlayer, vir PripetiVir). Daljinec: OK ali
 * predvajaj/pavza, levo/desno 10 s, hitro nazaj/naprej 60 s, Nazaj zapre. Prekritje z imenom,
 * potekom in casom se po treh sekundah skrije; pri glasbi ostane.
 */
class PredvajalnikActivity : OsActivity() {

    private lateinit var povrsina: SurfaceView
    private lateinit var prekritje: View
    private lateinit var ime: TextView
    private lateinit var cas: TextView
    private lateinit var potek: ProgressBar
    private lateinit var stanjeIkona: ImageView
    private lateinit var glasbaKartica: View

    private var predvajalnik: ExoPlayer? = null
    private var zvok = false
    private val glavna = Handler(Looper.getMainLooper())
    private val skrij = Runnable { if (!zvok) prekritje.visibility = View.GONE }
    private val tik = object : Runnable {
        override fun run() { osveziCas(); glavna.postDelayed(this, 500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.os_activity_predvajalnik)
        povrsina = findViewById(R.id.povrsina)
        prekritje = findViewById(R.id.prekritje)
        ime = findViewById(R.id.ime)
        cas = findViewById(R.id.cas)
        potek = findViewById(R.id.potek)
        stanjeIkona = findViewById(R.id.stanjeIkona)
        glasbaKartica = findViewById(R.id.glasbaKartica)
        zvok = intent.getBooleanExtra("zvok", false)
        val naslov = intent.getStringExtra("ime").orEmpty()
        ime.text = naslov
        findViewById<TextView>(R.id.glasbaIme).text = naslov
        glasbaKartica.visibility = if (zvok) View.VISIBLE else View.GONE
        povrsina.visibility = if (zvok) View.GONE else View.VISIBLE
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStart() {
        super.onStart()
        zazeni()
    }

    override fun onStop() {
        glavna.removeCallbacks(tik); glavna.removeCallbacks(skrij)
        predvajalnik?.release(); predvajalnik = null
        super.onStop()
    }

    private fun zazeni() {
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        // Krajevna datoteka televizorja (content://) gre naravnost skozi Android; datoteka z
        // racunalnika pa prek pripetega vira (TLS z odtisom in zetonom Safeer Controla).
        val lokalno = intent.getBooleanExtra("lokalno", false)
        val s = DatotekeActivity.Streznik.iz(intent.extras)
        if (!lokalno && s == null) { finish(); return }
        val renderers = DefaultRenderersFactory(this).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        val tovarnaVira = if (lokalno) DefaultMediaSourceFactory(this) else DefaultMediaSourceFactory(PripetiVir.Tovarna(s!!.odtis, s.zeton))
        val p = ExoPlayer.Builder(this)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(tovarnaVira)
            .build()
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(if (zvok) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        p.setWakeMode(C.WAKE_MODE_NETWORK)
        if (!zvok) p.setVideoSurfaceView(povrsina)
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                stanjeIkona.setImageResource(if (isPlaying) R.drawable.os_ikona_pavza else R.drawable.os_ikona_predvajaj)
                if (!isPlaying) pokaziPrekritje(false) else pokaziPrekritje(true)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) finish()
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) = prilagodiPovrsino(videoSize)
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PredvajalnikActivity, getString(R.string.os_predvajalnik_napaka, error.errorCodeName), Toast.LENGTH_LONG).show()
                finish()
            }
        })
        val mime = intent.getStringExtra("mime").orEmpty()
        val element = MediaItem.Builder().setUri(url).apply { if (mime.isNotBlank()) setMimeType(mime) }.build()
        p.setMediaItem(element)
        p.prepare()
        p.playWhenReady = true
        predvajalnik = p
        glavna.post(tik)
        pokaziPrekritje(true)
    }

    /** Povrsina naj ohrani razmerje slike (4:3, navpicni posnetki s telefona): v sredini, brez raztegovanja. */
    private fun prilagodiPovrsino(v: VideoSize) {
        if (v.width <= 0 || v.height <= 0) return
        val stars = povrsina.parent as? View ?: return
        val sirinaZ = stars.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val visinaZ = stars.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val razmerje = v.width * v.pixelWidthHeightRatio / v.height
        var w = sirinaZ; var h = (sirinaZ / razmerje).toInt()
        if (h > visinaZ) { h = visinaZ; w = (visinaZ * razmerje).toInt() }
        val lp = FrameLayout.LayoutParams(w, h, android.view.Gravity.CENTER)
        povrsina.layoutParams = lp
    }

    private fun pokaziPrekritje(samodejnoSkrij: Boolean) {
        prekritje.visibility = View.VISIBLE
        glavna.removeCallbacks(skrij)
        if (samodejnoSkrij) glavna.postDelayed(skrij, 3_000)
    }

    private fun osveziCas() {
        val p = predvajalnik ?: return
        val trajanje = p.duration.takeIf { it > 0 } ?: 0L
        val polozaj = p.currentPosition.coerceAtLeast(0)
        cas.text = if (trajanje > 0) "${oblikuj(polozaj)} / ${oblikuj(trajanje)}" else oblikuj(polozaj)
        potek.progress = if (trajanje > 0) (polozaj * 1000 / trajanje).toInt() else 0
    }

    private fun oblikuj(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    private fun premakni(ms: Long) {
        val p = predvajalnik ?: return
        val cilj = (p.currentPosition + ms).coerceAtLeast(0)
        p.seekTo(if (p.duration > 0) minOf(cilj, p.duration - 500) else cilj)
        osveziCas(); pokaziPrekritje(true)
    }

    private fun preklopi() {
        val p = predvajalnik ?: return
        if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); p.play() }
        pokaziPrekritje(p.isPlaying)
    }

    /**
     * Plosek pri predvajanju: A predvaja in ustavi, ramena skaceta po posnetku, palica in krizec
     * pa delata isto kot smerne tipke daljinca. B (nazaj) pusti skupnemu prevodu.
     */
    override fun plosekDejanje(koda: Int): Boolean = when (koda) {
        KeyEvent.KEYCODE_BUTTON_A -> { preklopi(); true }
        KeyEvent.KEYCODE_BUTTON_L1 -> { premakni(-10_000); true }
        KeyEvent.KEYCODE_BUTTON_R1 -> { premakni(10_000); true }
        KeyEvent.KEYCODE_BUTTON_L2 -> { premakni(-60_000); true }
        KeyEvent.KEYCODE_BUTTON_R2 -> { premakni(60_000); true }
        else -> false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { preklopi(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { predvajalnik?.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { predvajalnik?.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { finish(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { premakni(-10_000); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { premakni(10_000); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { premakni(-60_000); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { premakni(60_000); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { pokaziPrekritje(true); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
