package si.safeer.tv.os

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import si.safeer.tv.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Zdaj se predvaja: celozaslonski prikaz za glasbo, radio in video iz [GlasbaStoritev].
 * Zaslon predvajalniku le pripne sliko - ko ga zapustis (Nazaj, Domov), zvok igra naprej v ozadju.
 *
 * Daljinec: OK pavza/predvajaj, levo/desno 10 s, gor/dol pokaze podatke, zadrzan OK ali Stop
 * ustavi predvajanje, Nazaj pusti predvajanje v ozadju. Pri glasbi se po 30 s brez daljinca
 * zaslon zatemni (nacin poslusanja); pri videu ne.
 */
class PredvajanjeActivity : OsActivity() {

    private val glavna = Handler(Looper.getMainLooper())
    private lateinit var povrsina: SurfaceView
    private lateinit var naslovnica: ImageView
    private lateinit var prekritje: LinearLayout
    private lateinit var naslov: TextView
    private lateinit var izvajalec: TextView
    private lateinit var vir: TextView
    private lateinit var cas: TextView
    private lateinit var potek: ProgressBar
    private lateinit var tema: FrameLayout
    private lateinit var temaUra: TextView
    private lateinit var temaNaslov: TextView

    private var pripet: Player? = null
    private var zadnjaSlika = ""
    private val poslusalec: () -> Unit = { glavna.post { osvezi() } }
    private val tik = object : Runnable { override fun run() { osveziCas(); glavna.postDelayed(this, 1_000) } }
    private val skrij = Runnable { if (jeVideo()) prekritje.animate().alpha(0f).setDuration(300).start() }
    private val zatemni = Runnable { if (!jeVideo() && GlasbaStoritev.predvajalnik?.isPlaying == true) tema.visibility = View.VISIBLE; osveziCas() }
    private val velikost = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) = prilagodi(videoSize)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun besedilo(vel: Float, barva: Int, krepko: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, vel); setTextColor(barva); maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        if (krepko) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val koren = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        povrsina = SurfaceView(this)
        koren.addView(povrsina, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        naslovnica = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageResource(R.drawable.os_ikona_glasba) }
        koren.addView(naslovnica, FrameLayout.LayoutParams(dp(300), dp(300), Gravity.CENTER).apply { bottomMargin = dp(90) })

        prekritje = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(28), dp(56), dp(36))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x00000000))
        }
        naslov = besedilo(26f, getColor(R.color.os_besedilo), true)
        izvajalec = besedilo(17f, getColor(R.color.os_umirjeno))
        vir = besedilo(13f, getColor(R.color.os_mint))
        potek = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        cas = besedilo(15f, getColor(R.color.os_umirjeno))
        val namig = besedilo(12f, getColor(R.color.os_umirjeno)).apply { text = getString(R.string.os_mediji_namig_predvajanje) }
        prekritje.addView(naslov); prekritje.addView(izvajalec); prekritje.addView(vir)
        prekritje.addView(potek, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(12); bottomMargin = dp(6) })
        prekritje.addView(cas); prekritje.addView(namig)
        koren.addView(prekritje, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        tema = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        val stolpec = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        temaUra = besedilo(64f, 0x66F0F4F3).apply { gravity = Gravity.CENTER }
        temaNaslov = besedilo(22f, 0x77F0F4F3).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) }
        stolpec.addView(temaUra); stolpec.addView(temaNaslov)
        tema.addView(stolpec, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        koren.addView(tema, FrameLayout.LayoutParams(-1, -1))
        setContentView(koren)
    }

    override fun onStart() {
        super.onStart()
        GlasbaStoritev.poslusalci.add(poslusalec)
        osvezi()
        glavna.post(tik)
        zbudi()
    }

    override fun onStop() {
        GlasbaStoritev.poslusalci.remove(poslusalec)
        glavna.removeCallbacks(tik); glavna.removeCallbacks(skrij); glavna.removeCallbacks(zatemni)
        // Sliko odpnemo, zvok igra naprej (predvajanje v ozadju).
        pripet?.let { it.clearVideoSurfaceView(povrsina); it.removeListener(velikost) }
        pripet = null
        super.onStop()
    }

    private fun jeVideo() = GlasbaStoritev.trenutna()?.video == true

    private fun osvezi() {
        val p = GlasbaStoritev.predvajalnik
        val sk = GlasbaStoritev.trenutna()
        if (p == null || sk == null) { finish(); return }
        if (pripet !== p) {
            pripet?.let { it.clearVideoSurfaceView(povrsina); it.removeListener(velikost) }
            p.setVideoSurfaceView(povrsina); p.addListener(velikost); pripet = p
        }
        povrsina.visibility = if (sk.video) View.VISIBLE else View.INVISIBLE
        naslovnica.visibility = if (sk.video) View.GONE else View.VISIBLE
        naslov.text = sk.naslov
        izvajalec.text = sk.izvajalec
        temaNaslov.text = listOf(sk.naslov, sk.izvajalec).filter { it.isNotBlank() }.joinToString(" · ")
        val stran = sk.povezava.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
        vir.text = when {
            sk.zvok.startsWith("https://prod-1.storage.jamendo.com") || sk.povezava.contains("jamen") -> getString(R.string.os_glasba_vir, stran)
            else -> stran
        }
        if (!sk.video && sk.slika != zadnjaSlika) {
            zadnjaSlika = sk.slika
            naslovnica.setImageResource(R.drawable.os_ikona_glasba)
            if (sk.slika.startsWith("https://")) Thread {
                val b = Jamendo.bajti(sk.slika)?.let { VarnaSlika.izBajtov(it, 600) }
                if (b != null) glavna.post { if (zadnjaSlika == sk.slika) naslovnica.setImageBitmap(b) }
            }.start()
        }
        osveziCas()
    }

    private fun osveziCas() {
        val p = GlasbaStoritev.predvajalnik ?: return
        val trajanje = p.duration.takeIf { it > 0 } ?: 0L
        val polozaj = p.currentPosition.coerceAtLeast(0)
        cas.text = (if (p.isPlaying) "▶  " else "❚❚  ") + if (trajanje > 0) "${oblikuj(polozaj)} / ${oblikuj(trajanje)}" else oblikuj(polozaj)
        potek.progress = if (trajanje > 0) (polozaj * 1000 / trajanje).toInt() else 0
        if (tema.visibility == View.VISIBLE) {
            temaUra.text = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date())
            val minuta = (System.currentTimeMillis() / 60_000).toInt()
            tema.getChildAt(0).translationX = ((minuta * 37) % 121 - 60) * resources.displayMetrics.density
            tema.getChildAt(0).translationY = ((minuta * 53) % 81 - 40) * resources.displayMetrics.density
        }
    }

    private fun oblikuj(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    private fun prilagodi(v: VideoSize) {
        if (v.width <= 0 || v.height <= 0) return
        val sirina = (povrsina.parent as View).width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val visina = (povrsina.parent as View).height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val razmerje = v.width * v.pixelWidthHeightRatio / v.height
        var w = sirina; var h = (sirina / razmerje).toInt()
        if (h > visina) { h = visina; w = (visina * razmerje).toInt() }
        povrsina.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
    }

    /** Pokaze podatke in odmakne zatemnitev. */
    private fun zbudi() {
        tema.visibility = View.GONE
        prekritje.animate().cancel(); prekritje.alpha = 1f
        glavna.removeCallbacks(skrij); glavna.postDelayed(skrij, 4_000)
        glavna.removeCallbacks(zatemni); glavna.postDelayed(zatemni, 30_000)
    }

    private fun preklopi() {
        val p = GlasbaStoritev.predvajalnik ?: return
        if (p.isPlaying) p.pause() else p.play()
        osveziCas()
    }

    private fun premakni(ms: Long) {
        val p = GlasbaStoritev.predvajalnik ?: return
        if (!p.isCurrentMediaItemSeekable) return
        val cilj = (p.currentPosition + ms).coerceAtLeast(0)
        p.seekTo(if (p.duration > 0) minOf(cilj, p.duration - 500) else cilj)
        osveziCas()
    }

    override fun dispatchTouchEvent(dogodek: MotionEvent): Boolean {
        val budna = tema.visibility != View.VISIBLE && prekritje.alpha > 0.5f
        zbudi()
        if (!budna) return true
        if (dogodek.action == MotionEvent.ACTION_UP) preklopi()
        return true
    }

    override fun dispatchKeyEvent(dogodek: KeyEvent): Boolean {
        val budna = tema.visibility != View.VISIBLE
        zbudi()
        if (!budna && dogodek.keyCode != KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(dogodek)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val p = GlasbaStoritev.predvajalnik
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event?.repeatCount == 0) event.startTracking()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { preklopi(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p?.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p?.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { GlasbaStoritev.ustavi(this); finish(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (p?.hasNextMediaItem() == true) p.seekToNextMediaItem(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { p?.seekToPreviousMediaItem(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { premakni(-10_000); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { premakni(10_000); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Zadrzan OK ustavi predvajanje (daljinci televizorjev pogosto nimajo tipke Stop). */
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            GlasbaStoritev.ustavi(this); finish(); return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && event?.isCanceled == false && event.isTracking) {
            preklopi(); return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun plosekDejanje(koda: Int): Boolean = when (koda) {
        KeyEvent.KEYCODE_BUTTON_A -> { preklopi(); true }
        KeyEvent.KEYCODE_BUTTON_L1 -> { premakni(-10_000); true }
        KeyEvent.KEYCODE_BUTTON_R1 -> { premakni(10_000); true }
        else -> false
    }
}
