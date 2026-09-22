package si.safeer.tv.os

import android.content.Intent
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
import android.widget.HorizontalScrollView
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
 * Daljinec: OK pavza/predvajaj, levo/desno 10 s, dol odpre vrsto "se za ogled" (video s tega kanala
 * in o isti temi) oziroma "v vrsti" (glasba, radio) s kartico Isci na zacetku, tipka Isci odpre
 * iskanje, zadrzan OK ali Stop ustavi predvajanje, Nazaj pusti predvajanje v ozadju. Pri glasbi se po 30 s brez daljinca
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
    private lateinit var predlogi: LinearLayout
    private lateinit var predlogiNaslov: TextView
    private lateinit var predlogiNiz: LinearLayout
    /** Za kateri posnetek so predlogi nalozeni (ob novem posnetku jih nalozimo znova). */
    private var predlogiZa = ""
    private val delavec = java.util.concurrent.Executors.newFixedThreadPool(3)

    private var pripet: Player? = null
    private var zadnjaSlika = ""
    private val poslusalec: () -> Unit = { glavna.post { osvezi() } }
    private val tik = object : Runnable { override fun run() { osveziCas(); glavna.postDelayed(this, 1_000) } }
    private val skrij = Runnable { if (jeVideo() && !predlogiOdprti()) prekritje.animate().alpha(0f).setDuration(300).start() }
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
        predlogi = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        predlogiNaslov = besedilo(17f, getColor(R.color.os_besedilo), true).apply { setPadding(0, dp(14), 0, dp(8)) }
        predlogiNiz = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        predlogi.addView(predlogiNaslov)
        predlogi.addView(HorizontalScrollView(this).apply { addView(predlogiNiz); isHorizontalScrollBarEnabled = false; clipToPadding = false })
        prekritje.addView(predlogi)
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
        // S plosce Safeer Media: takoj zatemni (samo zvok).
        if (intent.getBooleanExtra(ZATEMNI, false)) {
            intent.removeExtra(ZATEMNI)
            if (!jeVideo()) { glavna.removeCallbacks(zatemni); tema.visibility = View.VISIBLE; osveziCas() }
        }
    }

    /**
     * Nazaj vedno vodi v Safeer Media - tudi ce je bilo predvajanje odprto s kartice na zacetnem
     * zaslonu ali iz obvestila (prej je vrglo na zacetni zaslon Safeer OS). Zvok igra naprej.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!GlasbaActivity.odprta) startActivity(android.content.Intent(this, GlasbaActivity::class.java))
        super.onBackPressed()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    companion object {
        const val ZATEMNI = "zatemni"
    }

    override fun onDestroy() { delavec.shutdownNow(); super.onDestroy() }

    override fun onStop() {
        GlasbaStoritev.poslusalci.remove(poslusalec)
        glavna.removeCallbacks(tik); glavna.removeCallbacks(skrij); glavna.removeCallbacks(zatemni)
        // Sliko odpnemo, zvok igra naprej (predvajanje v ozadju).
        pripet?.let { it.clearVideoSurfaceView(povrsina); it.removeListener(velikost) }
        (pripet as? SpletniIgralec)?.skrij()
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
        (p as? SpletniIgralec)?.let { if (sk.video) it.pokazi(povrsina) else it.skrij() }
        naslovnica.visibility = if (sk.video || predlogiOdprti()) View.GONE else View.VISIBLE
        if (predlogiOdprti() && predlogiZa != sk.id) zapriPredloge()
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

    // ------------------------------------------------------------------ predlogi in iskanje

    private fun predlogiOdprti() = predlogi.visibility == View.VISIBLE

    /** Dol: vrsta s karticami pod posnetkom. Karta Isci je vedno prva, zato je iskanje en klik stran. */
    private fun odpriPredloge() {
        val sk = GlasbaStoritev.trenutna() ?: return
        predlogi.visibility = View.VISIBLE
        // Naslovnica bi prekrila besedilo nad vrsto; ko se vrsta zapre, jo osvezi() vrne.
        naslovnica.visibility = View.GONE
        zbudi()
        if (predlogiZa != sk.id) {
            predlogiZa = sk.id
            if (sk.video) {
                predlogiNaslov.text = getString(R.string.os_mediji_se_za_ogled)
                napolni(emptyList(), true)
                delavec.execute {
                    val seznam = try { PeerTube.predlogi(sk) } catch (_: Exception) { emptyList() }
                    glavna.post { if (!isFinishing && predlogiZa == sk.id) napolni(seznam, true) }
                }
            } else {
                predlogiNaslov.text = getString(R.string.os_mediji_v_vrsti)
                napolni(GlasbaStoritev.vrsta(), false)
            }
        }
        prvaKartica()
    }

    private fun zapriPredloge() {
        predlogi.visibility = View.GONE
        osvezi()
        zbudi()
    }

    private var zadnjiPredlogi: Pair<List<Jamendo.Skladba>, Boolean> = emptyList<Jamendo.Skladba>() to false

    /** Kartice: Isci, Priljubljeno (♡), Shrani vrsto (glasba, radio), nato predlogi. */
    private fun napolni(seznam: List<Jamendo.Skladba>, video: Boolean, fokusNa: Int = -1) {
        zadnjiPredlogi = seznam to video
        val fokus = predlogiNiz.findFocus() != null
        predlogiNiz.removeAllViews()
        predlogiNiz.addView(kartica(getString(R.string.os_mediji_isci_kartica), getString(R.string.os_mediji_isci_kartica_opis), "",
            R.drawable.os_ikona_isci, video) { odpriIskanje() })
        val vrsta = GlasbaStoritev.vrsta()
        val zdaj = GlasbaStoritev.trenutna()
        if (zdaj != null && MedijskiViri.shranljiva(zdaj)) {
            val je = MedijskiViri.jePriljubljena(this, zdaj)
            predlogiNiz.addView(kartica(getString(if (je) R.string.os_mediji_odstrani_prilj else R.string.os_mediji_dodaj_prilj), zdaj.naslov, "",
                R.drawable.os_ikona_srce, video) {
                val da = MedijskiViri.preklopiPriljubljeno(this, zdaj)
                android.widget.Toast.makeText(this, if (da) R.string.os_mediji_dodano_prilj else R.string.os_mediji_odstranjeno_prilj, android.widget.Toast.LENGTH_SHORT).show()
                napolni(zadnjiPredlogi.first, zadnjiPredlogi.second, 1)
            })
        }
        if (!video && vrsta.count { MedijskiViri.shranljiva(it) } > 1) {
            predlogiNiz.addView(kartica(getString(R.string.os_mediji_shrani_vrsto), getString(R.string.os_mediji_shrani_vrsto_opis), "",
                R.drawable.os_ikona_plus, video) {
                val ime = vrsta.map { it.izvajalec }.distinct().singleOrNull()?.takeIf { it.isNotBlank() } ?: getString(R.string.os_mediji_moja_vrsta)
                MedijskiViri.shraniSeznam(this, ime, vrsta)?.let {
                    android.widget.Toast.makeText(this, getString(R.string.os_mediji_seznam_shranjen, it.ime), android.widget.Toast.LENGTH_SHORT).show()
                }
            })
        }
        val p = GlasbaStoritev.predvajalnik
        if (!video && p != null && vrsta.size > 1) {
            val i = predlogiNiz.childCount
            predlogiNiz.addView(kartica(getString(R.string.os_mediji_nakljucno),
                getString(if (p.shuffleModeEnabled) R.string.os_mediji_vklopljeno else R.string.os_mediji_izklopljeno), "",
                R.drawable.os_ikona_nakljucno, video) {
                p.shuffleModeEnabled = !p.shuffleModeEnabled
                napolni(zadnjiPredlogi.first, zadnjiPredlogi.second, i)
            })
        }
        if (!video && p != null && zdaj?.radio != true) {
            val i = predlogiNiz.childCount
            predlogiNiz.addView(kartica(getString(R.string.os_mediji_ponavljanje), getString(when (p.repeatMode) {
                    androidx.media3.common.Player.REPEAT_MODE_ALL -> R.string.os_mediji_ponavljaj_vse
                    androidx.media3.common.Player.REPEAT_MODE_ONE -> R.string.os_mediji_ponavljaj_eno
                    else -> R.string.os_mediji_izklopljeno }), "",
                R.drawable.os_ikona_ponavljaj, video) {
                // Izklopljeno -> vse -> ena skladba -> izklopljeno
                p.repeatMode = when (p.repeatMode) {
                    androidx.media3.common.Player.REPEAT_MODE_OFF -> androidx.media3.common.Player.REPEAT_MODE_ALL
                    androidx.media3.common.Player.REPEAT_MODE_ALL -> androidx.media3.common.Player.REPEAT_MODE_ONE
                    else -> androidx.media3.common.Player.REPEAT_MODE_OFF
                }
                napolni(zadnjiPredlogi.first, zadnjiPredlogi.second, i)
            })
        }
        dejanj = predlogiNiz.childCount
        seznam.forEach { sk ->
            predlogiNiz.addView(kartica(sk.naslov, sk.izvajalec, sk.slika, if (sk.video) R.drawable.os_ikona_video else R.drawable.os_ikona_glasba, video) {
                if (video) predvajajVideo(sk) else GlasbaStoritev.predvajalnik?.let { p ->
                    vrsta.indexOfFirst { it.id == sk.id }.takeIf { it >= 0 }?.let { p.seekTo(it, 0L); p.play() }
                }
            })
        }
        if (fokusNa >= 0) predlogiNiz.getChildAt(fokusNa)?.requestFocus()
        else if (fokus) prvaKartica()
    }

    /** Stevilo kartic z dejanji pred predlogi. */
    private var dejanj = 1

    /** Fokus na prvi predlog, ce ga ni, na prvo dejanje. */
    private fun prvaKartica() = (predlogiNiz.getChildAt(dejanj) ?: predlogiNiz.getChildAt(0))?.requestFocus()

    private fun kartica(naslov: String, podnaslov: String, slika: String, ikona: Int, video: Boolean, klik: () -> Unit): View {
        val sirina = dp(if (video) 200 else 120)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(6))
            setBackgroundResource(R.drawable.os_ploscica_app)
            isFocusable = true; isClickable = true
            setOnClickListener { klik() }
            val pogled = ImageView(this@PredvajanjeActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(getColor(R.color.os_kartica)); setImageResource(ikona)
            }
            addView(pogled, LinearLayout.LayoutParams(sirina, if (video) sirina * 9 / 16 else sirina))
            addView(besedilo(13f, getColor(R.color.os_besedilo), true).apply { text = naslov; maxLines = 1; setPadding(dp(2), dp(6), 0, 0) },
                LinearLayout.LayoutParams(sirina, -2))
            addView(besedilo(11f, getColor(R.color.os_umirjeno)).apply { text = podnaslov; maxLines = 1; setPadding(dp(2), 0, 0, 0) },
                LinearLayout.LayoutParams(sirina, -2))
            if (slika.startsWith("https://")) delavec.execute {
                val b = Jamendo.bajti(slika)?.let { VarnaSlika.izBajtov(it, 320) } ?: return@execute
                glavna.post { pogled.setImageBitmap(b) }
            }
        }.also { it.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) } }
    }

    /** Video iz predlogov: razresimo datoteko in ga predvajamo tu - zaslon ostane odprt. */
    private fun predvajajVideo(sk: Jamendo.Skladba) {
        zapriPredloge()
        naslov.text = sk.naslov; izvajalec.text = getString(R.string.os_glasba_nalagam)
        delavec.execute {
            val r = try { PeerTube.razresi(sk, MedijskiViri.streznikiPeerTube(this)) } catch (_: Exception) { null }
            glavna.post {
                if (isFinishing) return@post
                if (r == null) { izvajalec.text = getString(R.string.os_glasba_napaka); return@post }
                GlasbaStoritev.predvajaj(this, listOf(r), 0)
            }
        }
    }

    /** Iskanje v Medijih; predvajanje igra naprej v ozadju. */
    private fun odpriIskanje() {
        startActivity(Intent(this, GlasbaActivity::class.java).putExtra(GlasbaActivity.ISKANJE_BESEDA, "")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
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
        if (predlogiOdprti()) return super.dispatchTouchEvent(dogodek)
        if (!budna) return true
        // Dotik spodnje cetrtine odpre predloge (tablica nima tipke dol).
        if (dogodek.action == MotionEvent.ACTION_UP && dogodek.y > resources.displayMetrics.heightPixels * 0.75f) { odpriPredloge(); return true }
        if (dogodek.action == MotionEvent.ACTION_UP) preklopi()
        return true
    }

    override fun dispatchKeyEvent(dogodek: KeyEvent): Boolean {
        val budna = tema.visibility != View.VISIBLE
        zbudi()
        if (!budna && dogodek.keyCode != KeyEvent.KEYCODE_BACK) return true
        if (predlogiOdprti()) when (dogodek.keyCode) {
            KeyEvent.KEYCODE_BACK -> { if (dogodek.action == KeyEvent.ACTION_UP) zapriPredloge(); return true }
            // V vrsti predlogov gredo tipke naravnost zaslonu (fokus, OK izbere kartico), mimo pavze in previjanja.
            KeyEvent.KEYCODE_DPAD_UP -> { if (dogodek.action == KeyEvent.ACTION_DOWN) zapriPredloge(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> return window.superDispatchKeyEvent(dogodek)
        }
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
            KeyEvent.KEYCODE_DPAD_DOWN -> { odpriPredloge(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> return true
            KeyEvent.KEYCODE_SEARCH -> { odpriIskanje(); return true }
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
