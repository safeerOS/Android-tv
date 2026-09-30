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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
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
    /** Vrtavka, dokler video nima prve slike ali se polni medpomnilnik - brez nje je zacetek le crn zaslon. */
    private lateinit var nalaganje: ProgressBar
    private lateinit var namig: TextView
    private var prvaSlika = false
    private var prvaSlikaZa = ""
    private lateinit var temaUra: TextView
    private lateinit var temaNaslov: TextView
    private lateinit var predlogi: LinearLayout
    private lateinit var predlogiNaslov: TextView
    private lateinit var predlogiNiz: LinearLayout
    private var pripravaVTeKu = ""
    /** Za kateri posnetek so predlogi nalozeni (ob novem posnetku jih nalozimo znova). */
    private var predlogiZa = ""
    private val delavec = java.util.concurrent.Executors.newFixedThreadPool(3)

    private val podnapisi by lazy { Podnapisi.Prikaz(this) }
    private var gumbPodnapisi: ImageButton? = null
    private var gumbZvok: ImageButton? = null
    private var gumbPip: ImageButton? = null
    private var vlecenje = false
    private var pripet: Player? = null
    private var zadnjaSlika = ""
    private val poslusalec: () -> Unit = { glavna.post { osvezi() } }
    private val tik = object : Runnable { override fun run() { osveziCas(); glavna.postDelayed(this, 1_000) } }
    private val skrij = Runnable {
        // Dokler se video ne zacne, pas z naslovom ostane: uporabnik vidi, kaj se nalaga.
        if (jeVideo() && !predlogiOdprti() && !seNalaga()) prekritje.animate().alpha(0f).setDuration(300).start()
        else if (seNalaga()) glavna.postDelayed(skrijRunnable(), 1_000)
    }
    private fun skrijRunnable(): Runnable = skrij
    private val zatemni = Runnable { if (!jeVideo() && GlasbaStoritev.predvajalnik?.isPlaying == true) tema.visibility = View.VISIBLE; osveziCas() }
    private var nacinRazmerja = 0
    private var zadnjaVelikost: VideoSize? = null
    private val velikost = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) = prilagodi(videoSize)
        override fun onRenderedFirstFrame() { prvaSlika = true; posodobiNalaganje() }
        override fun onPlaybackStateChanged(playbackState: Int) = posodobiNalaganje()
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) = posodobiPodnapise()
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val sk = GlasbaStoritev.trenutna()
            if (sk != null && SpletniVir.jeEnota(sk) && sk.zvok.isNotBlank()) {
                SpletniIgralec.zadnja = java.lang.ref.WeakReference(this@PredvajanjeActivity)
                GlasbaStoritev.predvajajSplet(this@PredvajanjeActivity, sk.copy(zvok = ""), dovoliPrevzem = false)
            } else {
                izvajalec.text = getString(R.string.os_glasba_napaka)
                nalaganje.visibility = View.GONE
                zbudi()
            }
        }
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
        naslov = besedilo(26f, osBarva(R.color.os_besedilo), true)
        izvajalec = besedilo(17f, osBarva(R.color.os_umirjeno))
        vir = besedilo(13f, osBarva(R.color.os_mint))
        potek = if (dotik) SeekBar(this).apply {
            max = 1000
            // Vidno drsenje po posnetku (Matej: brez skritih kretenj) - premakne ob spustu.
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, odUporabnika: Boolean) { if (odUporabnika) zbudi() }
                override fun onStartTrackingTouch(sb: SeekBar) { vlecenje = true }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    vlecenje = false
                    GlasbaStoritev.predvajalnik?.let { p -> if (p.duration > 0 && p.isCurrentMediaItemSeekable) p.seekTo(p.duration * sb.progress / 1000) }
                    osveziCas()
                }
            })
        } else ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        cas = besedilo(15f, osBarva(R.color.os_umirjeno))
        namig = besedilo(12f, osBarva(R.color.os_umirjeno)).apply { text = getString(R.string.os_mediji_namig_predvajanje) }
        prekritje.addView(naslov); prekritje.addView(izvajalec); prekritje.addView(vir)
        prekritje.addView(potek, LinearLayout.LayoutParams(-1, if (dotik) -2 else dp(5)).apply { topMargin = dp(12); bottomMargin = dp(6) })
        prekritje.addView(cas); prekritje.addView(namig)
        predlogi = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        predlogiNaslov = besedilo(17f, osBarva(R.color.os_besedilo), true).apply { setPadding(0, dp(14), 0, dp(8)) }
        predlogiNiz = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        predlogi.addView(predlogiNaslov)
        predlogi.addView(HorizontalScrollView(this).apply { addView(predlogiNiz); isHorizontalScrollBarEnabled = false; clipToPadding = false })
        prekritje.addView(predlogi)
        koren.addView(prekritje, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        // Podnapisi nad pasom z naslovom (ta na telefonu ostane viden), poravnani na spodnji rob slike.
        koren.addView(podnapisi.pogled, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(48); leftMargin = dp(32); rightMargin = dp(32) })

        tema = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        val stolpec = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        temaUra = besedilo(64f, 0x66F0F4F3).apply { gravity = Gravity.CENTER }
        temaNaslov = besedilo(22f, 0x77F0F4F3).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) }
        stolpec.addView(temaUra); stolpec.addView(temaNaslov)
        tema.addView(stolpec, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        koren.addView(tema, FrameLayout.LayoutParams(-1, -1))
        nalaganje = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        koren.addView(nalaganje, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        setContentView(koren)
        if (dotik) pripraviDotik(koren)
    }

    /** Telefon in tablica nimata daljinca: vidni gumbi (nazaj v Safeer OS, prejsnja/predvajaj/naslednja)
     *  in vedno odprta vrsta kartic namesto skritih tipk (Matej, 29. 9. 2026: »tezava iti nazaj«). */
    private val dotik by lazy { si.safeer.tv.ChromiumEngineView.naDotik(this) }
    private var gumbPredvajaj: ImageButton? = null

    private fun okroglGumb(ikona: Int, opis: Int, velikost: Int, klik: () -> Unit) = ImageButton(this).apply {
        setImageResource(ikona); contentDescription = getString(opis)
        imageTintList = android.content.res.ColorStateList.valueOf(osBarva(R.color.os_besedilo))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99101820.toInt()); setStroke(dp(1), 0x33FFFFFF) }
        layoutParams = LinearLayout.LayoutParams(dp(velikost), dp(velikost)).apply { marginEnd = dp(14) }
        setOnClickListener { zbudi(); klik() }
    }

    private fun pripraviDotik(koren: FrameLayout) {
        namig.visibility = View.GONE
        val nazaj = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(16), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0x99101820.toInt()); setStroke(dp(1), 0x33FFFFFF) }
            isClickable = true; isFocusable = true
            contentDescription = getString(R.string.os_mediji_nazaj_v_os)
            setOnClickListener { @Suppress("DEPRECATION") onBackPressed() }
            addView(ImageView(this@PredvajanjeActivity).apply {
                setImageResource(si.safeer.tv.R.drawable.ic_m_back)
                imageTintList = android.content.res.ColorStateList.valueOf(osBarva(R.color.os_besedilo))
            }, LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(besedilo(15f, osBarva(R.color.os_besedilo), true).apply { text = getString(R.string.os_mediji_nazaj_v_os); setPadding(dp(6), 0, 0, 0) })
        }
        // V isti vrstici kot gumbi predvajanja: ne prekrije naslova niti na nizkem zaslonu telefona.
        // Ozek pokončni telefon: gumbi se prelomijo v drugo vrsto, namesto da bi padli čez rob zaslona.
        val gumbi = OvijalnaVrsta(this)
        gumbi.addView(nazaj, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(24) })
        gumbi.addView(okroglGumb(R.drawable.os_ikona_prejsnja, R.string.os_mediji_prejsnja, 52) {
            GlasbaStoritev.predvajalnik?.let { if (it.currentPosition > 5000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem() }
        })
        gumbi.addView(okroglGumb(R.drawable.os_ikona_nazaj10, R.string.os_mediji_nazaj_10, 52) { premakni(-10_000) })
        gumbPredvajaj = okroglGumb(R.drawable.os_ikona_pavza, R.string.os_mediji_predvajaj_pavza, 64) { preklopi() }
        gumbi.addView(gumbPredvajaj)
        gumbi.addView(okroglGumb(R.drawable.os_ikona_naprej10, R.string.os_mediji_naprej_10, 52) { premakni(10_000) })
        gumbi.addView(okroglGumb(R.drawable.os_ikona_naslednja, R.string.os_mediji_naslednja, 52) {
            GlasbaStoritev.predvajalnik?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
        })
        gumbPodnapisi = okroglGumb(R.drawable.os_ikona_podnapisi, R.string.podnapisi_naslov, 52) {
            GlasbaStoritev.predvajalnik?.let { p -> Podnapisi.izberi(this, p) { posodobiPodnapise() } }
        }.also { it.visibility = View.GONE; gumbi.addView(it) }
        gumbZvok = okroglGumb(R.drawable.os_ikona_zvocna_sled, R.string.os_mediji_zvocna_sled, 52) {
            GlasbaStoritev.predvajalnik?.let { p -> ZvocneSledi.izberi(this, p) { posodobiPodnapise() } }
        }.also { it.visibility = View.GONE; gumbi.addView(it) }
        gumbPip = okroglGumb(R.drawable.os_ikona_slika_v_sliki, R.string.os_mediji_slika_v_sliki, 52) { vSlikoVSliki() }
            .also { it.visibility = View.GONE; gumbi.addView(it) }
        prekritje.addView(gumbi, prekritje.indexOfChild(namig), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
    }

    override fun onStart() {
        super.onStart()
        GlasbaStoritev.poslusalci.add(poslusalec)
        osvezi()
        glavna.post(tik)
        zbudi()
        if (dotik) glavna.post { if (!isFinishing && !predlogiOdprti()) odpriPredloge() }
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
        const val PIP_PREKLOPI = "si.safeer.tv.os.PIP_PREKLOPI"
    }

    override fun onDestroy() { odjaviPip(); delavec.shutdownNow(); super.onDestroy() }

    override fun onStop() {
        // Video brez slike nima smisla: ko uporabnik zapusti predvajalnik (Nazaj, Domov, druga aplikacija),
        // ga ustavimo na mestu - "Nadaljuj gledanje" ga pozneje nadaljuje. Glasba in radio igrata naprej.
        if (jeVideo() && !isChangingConfigurations) GlasbaStoritev.predvajalnik?.pause()
        GlasbaStoritev.poslusalci.remove(poslusalec)
        glavna.removeCallbacks(tik); glavna.removeCallbacks(skrij); glavna.removeCallbacks(zatemni)
        // Sliko odpnemo, zvok igra naprej (predvajanje v ozadju).
        pripet?.let { it.clearVideoSurfaceView(povrsina); it.removeListener(velikost); it.removeListener(podnapisi) }
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
            pripet?.let { it.clearVideoSurfaceView(povrsina); it.removeListener(velikost); it.removeListener(podnapisi) }
            p.setVideoSurfaceView(povrsina); p.addListener(velikost); p.addListener(podnapisi); pripet = p
            prvaSlika = p.playbackState == Player.STATE_READY && p.videoSize.width > 0
        }
        if (prvaSlikaZa != sk.id) { prvaSlikaZa = sk.id; prvaSlika = p.playbackState == Player.STATE_READY && p.videoSize.width > 0 }
        posodobiNalaganje()
        povrsina.visibility = if (sk.video) View.VISIBLE else View.INVISIBLE
        namig.setText(if (sk.video) R.string.os_mediji_namig_predvajanje_video else R.string.os_mediji_namig_predvajanje)
        posodobiPodnapise()
        (p as? SpletniIgralec)?.let { if (sk.video) it.pokazi(povrsina) else it.skrij() }
        naslovnica.visibility = if (sk.video || predlogiOdprti()) View.GONE else View.VISIBLE
        if (predlogiOdprti() && predlogiZa != sk.id) zapriPredloge()
        naslov.text = sk.naslov
        val skritiVir = SpletniVir.jeEnota(sk)
        izvajalec.text = if (skritiVir) "" else sk.izvajalec
        temaNaslov.text = if (skritiVir) sk.naslov else listOf(sk.naslov, sk.izvajalec).filter { it.isNotBlank() }.joinToString(" · ")
        val stran = sk.povezava.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
        vir.text = when {
            skritiVir -> ""
            sk.zvok.startsWith("https://prod-1.storage.jamendo.com") || sk.povezava.contains("jamen") -> getString(R.string.os_glasba_vir, stran)
            else -> stran
        }
        if (!sk.video && sk.slika != zadnjaSlika) {
            zadnjaSlika = sk.slika
            naslovnica.setImageResource(R.drawable.os_ikona_glasba)
            if (sk.slika.startsWith("https://")) Thread {
                val b = (SpletniVir.bajtiSlike(this, sk.slika) ?: Jamendo.bajti(sk.slika))?.let { VarnaSlika.izBajtov(it, 600) }
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
        if (!vlecenje) potek.progress = if (trajanje > 0) (polozaj * 1000 / trajanje).toInt() else 0
        gumbPredvajaj?.setImageResource(if (p.isPlaying) R.drawable.os_ikona_pavza else R.drawable.os_ikona_predvajaj)
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

    private fun prilagodi(v: VideoSize? = zadnjaVelikost) {
        val velikost = v ?: zadnjaVelikost ?: return
        if (velikost.width <= 0 || velikost.height <= 0) return
        zadnjaVelikost = velikost
        val stars = povrsina.parent as? View ?: return
        val sirina = stars.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val visina = stars.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val razmerje = (velikost.width * velikost.pixelWidthHeightRatio / velikost.height).takeIf { it > 0f } ?: (16f / 9f)
        val (w, h) = when (nacinRazmerja) {
            1 -> {
                // Fill (zoom): zapolni celoten zaslon brez robov
                var wFill = sirina; var hFill = (sirina / razmerje).toInt()
                if (hFill < visina) { hFill = visina; wFill = (visina * razmerje).toInt() }
                wFill to hFill
            }
            2 -> {
                // Raztegni na celoten zaslon
                sirina to visina
            }
            else -> {
                // Fit (privzeto): ohrani izvirno razmerje
                var wFit = sirina; var hFit = (sirina / razmerje).toInt()
                if (hFit > visina) { hFit = visina; wFit = (visina * razmerje).toInt() }
                wFit to hFit
            }
        }
        povrsina.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        (podnapisi.pogled.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.bottomMargin = maxOf(0, (visina - minOf(h, visina)) / 2) + dp(if (dotik) 10 else 40)
            podnapisi.pogled.layoutParams = lp
        }
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
        // Zvocnik v omrezju (DLNA): zvocnik vir potegne sam, ta naprava je le daljinec.
        val naZvocniku = Zvocniki.aktivni
        if (naZvocniku != null) {
            val i = predlogiNiz.childCount
            predlogiNiz.addView(kartica(getString(R.string.zvocnik_na, naZvocniku.ime), Zvocniki.aktivnaSkladba?.naslov ?: "", "",
                R.drawable.os_ikona_zvocnik, video) { ZvocnikIzbira.upravljaj(this) { napolni(zadnjiPredlogi.first, zadnjiPredlogi.second, i) } })
        } else if (zdaj != null && !video && DlnaPravila.zaZvocnik(zdaj.zvok)) {
            val i = predlogiNiz.childCount
            predlogiNiz.addView(kartica(getString(R.string.zvocnik_predvajaj_na), zdaj.naslov, "",
                R.drawable.os_ikona_zvocnik, video) { ZvocnikIzbira.izberi(this, zdaj) { napolni(zadnjiPredlogi.first, zadnjiPredlogi.second, i) } })
        }
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
        if (video) {
            val i = predlogiNiz.childCount
            val opis = when (nacinRazmerja) {
                1 -> getString(R.string.os_media_razmerje_fill)
                2 -> getString(R.string.os_media_razmerje_stretch)
                else -> getString(R.string.os_media_razmerje_fit)
            }
            predlogiNiz.addView(kartica(getString(R.string.os_media_razmerje), opis, "", R.drawable.os_ikona_video, video) {
                nacinRazmerja = (nacinRazmerja + 1) % 3
                prilagodi()
                napolni(zadnjiPredlogi.first, zadnjiPredlogi.second, i)
            })
        }
        dejanj = predlogiNiz.childCount
        seznam.forEach { sk ->
            predlogiNiz.addView(kartica(sk.naslov, if (SpletniVir.jeEnota(sk)) "" else sk.izvajalec, sk.slika, if (sk.video) R.drawable.os_ikona_video else R.drawable.os_ikona_glasba, video) {
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
                scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(osBarva(R.color.os_kartica)); setImageResource(ikona)
            }
            addView(pogled, LinearLayout.LayoutParams(sirina, if (video) sirina * 9 / 16 else sirina))
            addView(besedilo(13f, osBarva(R.color.os_besedilo), true).apply { text = naslov; maxLines = 1; setPadding(dp(2), dp(6), 0, 0) },
                LinearLayout.LayoutParams(sirina, -2))
            addView(besedilo(11f, osBarva(R.color.os_umirjeno)).apply { text = podnaslov; maxLines = 1; setPadding(dp(2), 0, 0, 0) },
                LinearLayout.LayoutParams(sirina, -2))
            if (slika.startsWith("https://")) delavec.execute {
                val b = (SpletniVir.bajtiSlike(this@PredvajanjeActivity, slika) ?: Jamendo.bajti(slika))?.let { VarnaSlika.izBajtov(it, 320) } ?: return@execute
                glavna.post { pogled.setImageBitmap(b) }
            }
        }.also { it.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) } }
    }

    /** Video iz predlogov: razresimo datoteko in ga predvajamo tu - zaslon ostane odprt. */
    private fun predvajajVideo(sk: Jamendo.Skladba) {
        val kljuc = sk.id.ifBlank { sk.povezava.ifBlank { sk.naslov } }
        if (pripravaVTeKu.isNotEmpty()) return
        pripravaVTeKu = kljuc
        android.widget.Toast.makeText(this, getString(R.string.os_media_pripravljam, sk.naslov), android.widget.Toast.LENGTH_SHORT).show()
        zapriPredloge()
        naslov.text = sk.naslov; izvajalec.text = getString(R.string.os_glasba_nalagam)
        if (SpletniVir.jeEnota(sk)) {
            SpletniVir.razresi(this, sk) { r ->
                if (isFinishing) { pripravaVTeKu = ""; return@razresi }
                pripravaVTeKu = ""
                if (r == null) {
                    SpletniIgralec.zadnja = java.lang.ref.WeakReference(this)
                    GlasbaStoritev.predvajajSplet(this, sk)
                    return@razresi
                }
                GlasbaStoritev.predvajaj(this, listOf(r), 0)
            }
        } else {
            delavec.execute {
                val r = try {
                    when {
                        JavnaLast.jeEnota(sk) -> JavnaLast.razresi(sk)
                        TuneIn.jeEnota(sk) -> TuneIn.razresi(sk)
                        else -> PeerTube.razresi(sk, MedijskiViri.streznikiPeerTube(this))
                    }
                } catch (_: Exception) { null }
                glavna.post {
                    if (isFinishing) { pripravaVTeKu = ""; return@post }
                    pripravaVTeKu = ""
                    if (r == null) {
                        izvajalec.text = getString(R.string.os_glasba_napaka)
                        android.widget.Toast.makeText(this, getString(R.string.os_media_priprava_napaka, sk.naslov), android.widget.Toast.LENGTH_LONG).show()
                        return@post
                    }
                    GlasbaStoritev.predvajaj(this, listOf(r), 0)
                }
            }
        }
    }

    /** Iskanje v Medijih; predvajanje igra naprej v ozadju. */
    private fun odpriIskanje() {
        startActivity(Intent(this, GlasbaActivity::class.java).putExtra(GlasbaActivity.ISKANJE_BESEDA, "")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** Pokaze podatke in odmakne zatemnitev. */
    private fun seNalaga(): Boolean {
        val p = GlasbaStoritev.predvajalnik ?: return false
        if (p.playbackState == Player.STATE_ENDED || p.playerError != null) return false
        if (p.playbackState == Player.STATE_BUFFERING) return true
        // Spletni igralec (WebView) ne javlja prve slike: zanj velja le polnjenje medpomnilnika.
        return jeVideo() && p !is SpletniIgralec && !prvaSlika && p.playWhenReady
    }

    /** Gumb in namig za podnapise samo, kadar jih video ima. */
    private fun posodobiPodnapise() {
        val ima = jeVideo() && Podnapisi.imaPodnapise(GlasbaStoritev.predvajalnik)
        GlasbaStoritev.predvajalnik?.let { p -> android.util.Log.d("SafeerPodnapisi", "Posnetki: " + Podnapisi.posnetki(p).joinToString { (g, i) -> "${g.getTrackFormat(i).sampleMimeType}/${g.getTrackFormat(i).language}/${g.isTrackSelected(i)}" }) }
        gumbPodnapisi?.visibility = if (ima) View.VISIBLE else View.GONE
        gumbZvok?.visibility = if (ZvocneSledi.imaIzbiro(GlasbaStoritev.predvajalnik)) View.VISIBLE else View.GONE
        gumbPip?.visibility = if (jeVideo() && pipMogoc()) View.VISIBLE else View.GONE
        posodobiPip()
        if (!dotik && ::namig.isInitialized && jeVideo()) {
            val osnova = getString(R.string.os_mediji_namig_predvajanje_video)
            namig.text = if (ima) osnova + " · " + getString(R.string.podnapisi_namig) else osnova
        }
        if (!jeVideo()) podnapisi.pogled.visibility = View.GONE
    }

    private fun posodobiNalaganje() {
        if (!::nalaganje.isInitialized) return
        val da = seNalaga()
        nalaganje.visibility = if (da) View.VISIBLE else View.GONE
        if (da) { prekritje.animate().cancel(); prekritje.alpha = 1f }
    }

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
        glavna.postDelayed({ posodobiPip() }, 150)
    }

    private fun premakni(ms: Long) {
        val p = GlasbaStoritev.predvajalnik ?: return
        if (!p.isCurrentMediaItemSeekable) return
        val cilj = (p.currentPosition + ms).coerceAtLeast(0)
        p.seekTo(if (p.duration > 0) minOf(cilj, p.duration - 500) else cilj)
        osveziCas()
    }

    // ------------------------------------------------------------------ slika v sliki (telefon, tablica)
    private fun pipMogoc(): Boolean = dotik && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParametri(): android.app.PictureInPictureParams {
        val v = zadnjaVelikost
        val razmerje = if (v != null && v.width > 0 && v.height > 0) android.util.Rational(v.width, v.height) else android.util.Rational(16, 9)
        // Android dovoli razmerja med 1:2,39 in 2,39:1.
        val r = razmerje.toFloat().coerceIn(0.4185f, 2.39f)
        val b = android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational((r * 1000).toInt(), 1000))
        val igra = GlasbaStoritev.predvajalnik?.isPlaying == true
        val dejanje = android.app.RemoteAction(
            android.graphics.drawable.Icon.createWithResource(this, if (igra) R.drawable.os_ikona_pavza else R.drawable.os_ikona_predvajaj),
            getString(R.string.os_mediji_predvajaj_pavza), getString(R.string.os_mediji_predvajaj_pavza),
            android.app.PendingIntent.getBroadcast(this, 1, Intent(PIP_PREKLOPI).setPackage(packageName),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE))
        b.setActions(listOf(dejanje))
        if (android.os.Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(jeVideo() && igra)
        return b.build()
    }

    /** Posodobi parametre (samodejni vstop ob tipki Domov na Androidu 12+, gumb predvajaj/premor v oknu). */
    private fun posodobiPip() {
        if (!pipMogoc() || !jeVideo()) return
        try { setPictureInPictureParams(pipParametri()) } catch (_: Throwable) { }
    }

    private fun vSlikoVSliki() {
        if (!pipMogoc() || !jeVideo()) return
        try { enterPictureInPictureMode(pipParametri()) } catch (e: Throwable) { android.util.Log.w("SafeerPiP", "vstop ni uspel: $e") }
    }

    /** Android 8-11: tipka Domov med videom -> slika v sliki (na 12+ to naredi setAutoEnterEnabled). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT < 31 && pipMogoc() && jeVideo() && GlasbaStoritev.predvajalnik?.isPlaying == true) vSlikoVSliki()
    }

    private var pipSprejemnikPrijavljen = false
    private fun odjaviPip() { if (pipSprejemnikPrijavljen) { pipSprejemnikPrijavljen = false; try { unregisterReceiver(pipSprejemnik) } catch (_: Throwable) { } } }

    private val pipSprejemnik = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) { if (i?.action == PIP_PREKLOPI) { preklopi(); posodobiPip() } }
    }

    override fun onPictureInPictureModeChanged(vPip: Boolean, novaKonfiguracija: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(vPip, novaKonfiguracija)
        // V majhnem oknu le slika (in podnapisi); pas z naslovom in kartice se skrijejo.
        prekritje.visibility = if (vPip) View.GONE else View.VISIBLE
        if (vPip) { prekritje.animate().cancel(); prekritje.alpha = 1f } else zbudi()
        if (vPip) {
            val filter = android.content.IntentFilter(PIP_PREKLOPI)
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(pipSprejemnik, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(pipSprejemnik, filter)
            pipSprejemnikPrijavljen = true
        } else odjaviPip()
    }

    override fun dispatchTouchEvent(dogodek: MotionEvent): Boolean {
        val budna = tema.visibility != View.VISIBLE && prekritje.alpha > 0.5f
        zbudi()
        if (dotik) {
            // Prvi dotik le zbudi prikaz; nato delujejo vidni gumbi in kartice (brez skritih kretenj).
            if (!budna) return true
            if (dogodek.action == MotionEvent.ACTION_UP && !predlogiOdprti()) odpriPredloge()
            return super.dispatchTouchEvent(dogodek)
        }
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
        if (predlogiOdprti() && !dotik) when (dogodek.keyCode) {
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
            KeyEvent.KEYCODE_DPAD_UP -> { if (jeVideo()) p?.let { Podnapisi.izberi(this, it) { posodobiPodnapise() } }; return true }
            KeyEvent.KEYCODE_CAPTIONS -> { p?.let { Podnapisi.preklopi(this, it); posodobiPodnapise() }; return true }
            KeyEvent.KEYCODE_SEARCH -> { odpriIskanje(); return true }
            KeyEvent.KEYCODE_WINDOW, KeyEvent.KEYCODE_PROG_GREEN -> {
                if (jeVideo()) {
                    nacinRazmerja = (nacinRazmerja + 1) % 3
                    prilagodi()
                    return true
                }
            }
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
        KeyEvent.KEYCODE_BUTTON_Y -> {
            if (jeVideo()) {
                nacinRazmerja = (nacinRazmerja + 1) % 3
                prilagodi()
                true
            } else false
        }
        else -> false
    }
}
