package si.safeer.tv.os

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import si.safeer.tv.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Glasba brez oglasov: Jamendo (skladbe neodvisnih izvajalcev, razvrscene po priljubljenosti,
 * iskanje po izvajalcih), internetni radio ([Radio]) in video s PeerTuba ([PeerTube]). Predvaja [GlasbaStoritev], zato glasba igra naprej,
 * ko zaslon zapustis.
 *
 * Nacin poslusanja: ko glasba igra in se daljinca 30 s nihce ne dotakne, se zaslon zatemni -
 * ostane samo skladba in ura (Android aplikaciji ne dovoli ugasniti zaslona televizorja).
 * Katerakoli tipka ga zbudi.
 */
class GlasbaActivity : OsActivity() {

    private val delavec = Executors.newFixedThreadPool(3)
    private val glavna = Handler(Looper.getMainLooper())
    private val slike = LruCache<String, android.graphics.Bitmap>(80)

    private lateinit var mreza: GridLayout
    private lateinit var stanje: TextView
    private lateinit var iskalnik: EditText
    private lateinit var zavihki: List<TextView>
    private lateinit var zdajSlika: ImageView
    private lateinit var zdajNaslov: TextView
    private lateinit var zdajIzvajalec: TextView
    private lateinit var zdajVir: TextView
    private lateinit var zdajCas: TextView
    private lateinit var zdajPotek: ProgressBar
    private lateinit var vrstica: View
    private lateinit var tema: FrameLayout
    private lateinit var temaNaslov: TextView
    private lateinit var temaIzvajalec: TextView
    private lateinit var temaUra: TextView

    private var zavihek = 0
    private companion object { const val VIDEO = 2; const val ISKANJE = 3 }
    private var nalaganje = 0
    private var seznam: List<Jamendo.Skladba> = emptyList()
    private val poslusalec: () -> Unit = { glavna.post { osveziZdaj() } }

    private val tik = object : Runnable {
        override fun run() { osveziCas(); glavna.postDelayed(this, 1_000) }
    }
    private val zatemni = Runnable { if (GlasbaStoritev.predvajalnik?.isPlaying == true) pokaziTemo(true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(zgradi())
        izberiZavihek(0)
    }

    override fun onStart() {
        super.onStart()
        GlasbaStoritev.poslusalci.add(poslusalec)
        osveziZdaj()
        glavna.post(tik)
        budilka()
    }

    override fun onStop() {
        GlasbaStoritev.poslusalci.remove(poslusalec)
        glavna.removeCallbacks(tik); glavna.removeCallbacks(zatemni)
        super.onStop()
    }

    override fun onDestroy() {
        delavec.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ postavitev

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun besedilo(ctx: Context, vel: Float, barva: Int, krepko: Boolean = false) = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, vel)
        setTextColor(barva)
        if (krepko) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun zgradi(): View {
        val beli = getColor(R.color.os_besedilo)
        val umirjeno = getColor(R.color.os_umirjeno)
        val koren = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.os_ozadje)) }
        val stolpec = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(20))
        }
        koren.addView(stolpec, FrameLayout.LayoutParams(-1, -1))

        stolpec.addView(besedilo(this, 30f, beli, true).apply { text = getString(R.string.os_glasba_naslov) })
        stolpec.addView(besedilo(this, 15f, umirjeno).apply {
            text = getString(R.string.os_glasba_podnaslov)
            setPadding(0, dp(4), 0, dp(18))
        })

        // Zavihki: Priljubljeno, Radio, Iskanje
        val vrstaZ = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        zavihki = listOf(R.string.os_glasba_priljubljeno, R.string.os_glasba_radio, R.string.os_glasba_video, R.string.os_glasba_iskanje).mapIndexed { i, id ->
            besedilo(this, 16f, beli, true).apply {
                text = getString(id)
                this.id = View.generateViewId()
                setPadding(dp(22), dp(10), dp(22), dp(10))
                isFocusable = true; isClickable = true
                setBackgroundResource(R.drawable.os_meni_postavka)
                setOnClickListener { izberiZavihek(i) }
                vrstaZ.addView(this, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
            }
        }
        iskalnik = EditText(this).apply {
            hint = getString(R.string.os_glasba_isci_namig)
            setTextColor(beli); setHintTextColor(umirjeno)
            setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(getColor(R.color.os_kartica)); setStroke(dp(1), getColor(R.color.os_crta)) }
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
            setOnEditorActionListener { _, akcija, _ ->
                if (akcija == EditorInfo.IME_ACTION_SEARCH || akcija == EditorInfo.IME_ACTION_DONE) { isci(); true } else false
            }
        }
        vrstaZ.addView(iskalnik, LinearLayout.LayoutParams(dp(420), -2).apply { marginStart = dp(8) })
        stolpec.addView(vrstaZ)

        stanje = besedilo(this, 15f, umirjeno).apply { setPadding(0, dp(14), 0, dp(4)) }
        stolpec.addView(stanje)

        mreza = GridLayout(this).apply { columnCount = 6; useDefaultMargins = false }
        val drsnik = ScrollView(this).apply { isFillViewport = true; addView(mreza); clipToPadding = false; setPadding(0, dp(6), 0, dp(6)) }
        stolpec.addView(drsnik, LinearLayout.LayoutParams(-1, 0, 1f))

        // Zdaj se predvaja
        vrstica = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(getColor(R.color.os_kartica_dvignjena)); setStroke(dp(1), getColor(R.color.os_crta)) }
            isFocusable = true; isClickable = true
            setOnClickListener { preklopi() }
            // Daljinci televizorjev pogosto nimajo tipke Stop: zadrzan OK na vrstici glasbo ustavi.
            setOnLongClickListener { GlasbaStoritev.ustavi(this@GlasbaActivity); true }
            setOnFocusChangeListener { v, f -> v.alpha = if (f) 1f else 0.92f }
        }
        zdajSlika = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        (vrstica as LinearLayout).addView(zdajSlika, LinearLayout.LayoutParams(dp(64), dp(64)))
        val besedila = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        zdajNaslov = besedilo(this, 17f, beli, true)
        zdajIzvajalec = besedilo(this, 14f, umirjeno)
        zdajVir = besedilo(this, 12f, getColor(R.color.os_mint))
        zdajPotek = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        besedila.addView(zdajNaslov); besedila.addView(zdajIzvajalec); besedila.addView(zdajVir)
        besedila.addView(zdajPotek, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(6) })
        (vrstica as LinearLayout).addView(besedila, LinearLayout.LayoutParams(0, -2, 1f))
        zdajCas = besedilo(this, 15f, umirjeno)
        (vrstica as LinearLayout).addView(zdajCas)
        stolpec.addView(vrstica, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        stolpec.addView(besedilo(this, 12f, umirjeno).apply {
            text = getString(R.string.os_glasba_namig); setPadding(0, dp(8), 0, 0)
        })

        // Nacin poslusanja (zatemnjen zaslon)
        tema = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE; isClickable = true }
        val temaStolpec = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        temaUra = besedilo(this, 64f, 0x66F0F4F3).apply { gravity = Gravity.CENTER }
        temaNaslov = besedilo(this, 26f, 0x88F0F4F3.toInt(), true).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) }
        temaIzvajalec = besedilo(this, 18f, 0x55F0F4F3).apply { gravity = Gravity.CENTER }
        temaStolpec.addView(temaUra); temaStolpec.addView(temaNaslov); temaStolpec.addView(temaIzvajalec)
        tema.addView(temaStolpec, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        koren.addView(tema, FrameLayout.LayoutParams(-1, -1))
        return koren
    }

    private fun ploscica(naslov: String, podnaslov: String, slika: String, klik: () -> Unit): View {
        val prvaVrsta = mreza.childCount < mreza.columnCount
        val sirina = (resources.displayMetrics.widthPixels - dp(96)) / 6 - dp(14)
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(10))
            setBackgroundResource(R.drawable.os_ploscica_app)
            isFocusable = true; isClickable = true
            setOnClickListener { klik() }
            // Iz prve vrste gor vedno na zavihke (drsnik bi tipko sicer porabil za drsenje).
            if (prvaVrsta) nextFocusUpId = zavihki[zavihek].id
        }
        val slikaV = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(getColor(R.color.os_kartica))
            setImageResource(R.drawable.os_ikona_glasba)
        }
        p.addView(slikaV, LinearLayout.LayoutParams(-1, sirina - dp(16)))
        p.addView(besedilo(this, 14f, getColor(R.color.os_besedilo), true).apply { text = naslov; setPadding(0, dp(8), 0, 0) })
        p.addView(besedilo(this, 12f, getColor(R.color.os_umirjeno)).apply { text = podnaslov })
        naloziSliko(slika, slikaV)
        p.layoutParams = GridLayout.LayoutParams().apply { width = sirina; setMargins(dp(7), dp(7), dp(7), dp(7)) }
        return p
    }

    private fun naloziSliko(naslov: String, v: ImageView) {
        if (!naslov.startsWith("https://")) return
        slike.get(naslov)?.let { v.setImageBitmap(it); return }
        v.tag = naslov
        delavec.execute {
            val b = Jamendo.bajti(naslov)?.let { VarnaSlika.izBajtov(it, 300) } ?: return@execute
            glavna.post {
                slike.put(naslov, b)
                if (v.tag == naslov) v.setImageBitmap(b)
            }
        }
    }

    // ------------------------------------------------------------------ vsebina

    private fun izberiZavihek(i: Int) {
        zavihek = i
        zavihki.forEachIndexed { j, t -> t.isSelected = j == i; t.setTextColor(getColor(if (j == i) R.color.os_mint else R.color.os_besedilo)) }
        iskalnik.visibility = if (i == ISKANJE) View.VISIBLE else View.GONE
        when (i) {
            0 -> nalozi({ Jamendo.priljubljene() }) { pokaziSkladbe(it) }
            1 -> nalozi({ Radio.postaje() }) { pokaziSkladbe(it) }
            VIDEO -> nalozi({ PeerTube.priljubljeni() }) { pokaziVideo(it, getString(R.string.os_glasba_video_opis)) }
            ISKANJE -> { mreza.removeAllViews(); stanje.text = getString(R.string.os_glasba_isci_navodilo); iskalnik.requestFocus() }
        }
    }

    private fun isci() {
        val beseda = iskalnik.text.toString().trim()
        if (beseda.length < 2) return
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(iskalnik.windowToken, 0)
        // Eno iskanje za vse vire: izvajalci z Jamenda in video s PeerTuba.
        nalozi({ Jamendo.isciIzvajalce(beseda) to PeerTube.isci(beseda) }) { (izvajalci, videi) ->
            mreza.removeAllViews()
            stanje.text = if (izvajalci.isEmpty() && videi.isEmpty()) getString(R.string.os_glasba_prazno)
                else getString(R.string.os_glasba_zadetki, izvajalci.size, videi.size)
            dodajVideo(videi)
            izvajalci.forEach { iz ->
                mreza.addView(ploscica(iz.ime, getString(R.string.os_glasba_izvajalec), iz.slika) {
                    nalozi({ Jamendo.odIzvajalca(iz.id) }) { pokaziSkladbe(it, iz.ime) }
                })
            }
            mreza.getChildAt(0)?.requestFocus()
        }
    }

    private fun <T> nalozi(delo: () -> T, potem: (T) -> Unit) {
        val moje = ++nalaganje
        stanje.text = getString(R.string.os_glasba_nalagam)
        delavec.execute {
            val izid = try { Result.success(delo()) } catch (e: Exception) { Result.failure(e) }
            glavna.post {
                if (moje != nalaganje || isFinishing) return@post
                izid.onSuccess(potem).onFailure { stanje.text = getString(R.string.os_glasba_napaka) }
            }
        }
    }

    private fun pokaziSkladbe(s: List<Jamendo.Skladba>, izvajalec: String? = null) {
        seznam = s
        mreza.removeAllViews()
        stanje.text = when {
            s.isEmpty() -> getString(R.string.os_glasba_prazno)
            izvajalec != null -> getString(R.string.os_glasba_od_izvajalca, izvajalec)
            zavihek == 1 -> getString(R.string.os_glasba_radiji)
            else -> getString(R.string.os_glasba_po_priljubljenosti)
        }
        s.forEachIndexed { i, sk -> mreza.addView(ploscica(sk.naslov, sk.izvajalec, sk.slika) { predvajaj(i) }) }
        if (!iskalnik.hasFocus() || izvajalec != null) mreza.getChildAt(0)?.requestFocus()
    }

    private fun pokaziVideo(v: List<PeerTube.Video>, opis: String) {
        mreza.removeAllViews()
        stanje.text = if (v.isEmpty()) getString(R.string.os_glasba_prazno) else opis
        dodajVideo(v)
        mreza.getChildAt(0)?.requestFocus()
    }

    private fun dodajVideo(v: List<PeerTube.Video>) {
        v.forEach { video -> mreza.addView(ploscica(video.naslov, video.kanal.ifBlank { video.streznik }, video.slika) { odpriVideo(video) }) }
    }

    /** Video predvaja obstojeci predvajalnik Safeer OS; glasba se medtem ustavi (pavza). */
    private fun odpriVideo(v: PeerTube.Video) {
        nalozi({ PeerTube.datoteka(v) }) { url ->
            if (url == null) { stanje.text = getString(R.string.os_glasba_napaka); return@nalozi }
            GlasbaStoritev.predvajalnik?.pause()
            startActivity(android.content.Intent(this, PredvajalnikActivity::class.java)
                .putExtra("url", url).putExtra("ime", v.naslov).putExtra("lokalno", true).putExtra("mime", "video/mp4"))
        }
    }

    private fun predvajaj(i: Int) {
        if (seznam.getOrNull(i) == null) return
        // Ves seznam gre v vrsto: naprej/nazaj na daljincu preklopi skladbo ali postajo.
        GlasbaStoritev.predvajaj(this, seznam, i)
    }

    // ------------------------------------------------------------------ zdaj se predvaja

    private fun osveziZdaj() {
        val sk = GlasbaStoritev.trenutna()
        vrstica.visibility = if (sk == null) View.GONE else View.VISIBLE
        if (sk == null) { pokaziTemo(false); return }
        zdajNaslov.text = sk.naslov
        zdajIzvajalec.text = sk.izvajalec
        val stran = sk.povezava.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
        zdajVir.text = if (sk.radio) stran else getString(R.string.os_glasba_vir, stran)
        temaNaslov.text = sk.naslov
        temaIzvajalec.text = sk.izvajalec
        zdajSlika.setImageResource(R.drawable.os_ikona_glasba)
        naloziSliko(sk.slika, zdajSlika)
        osveziCas()
    }

    private fun osveziCas() {
        val p = GlasbaStoritev.predvajalnik
        if (p == null) { zdajCas.text = ""; return }
        val trajanje = p.duration.takeIf { it > 0 } ?: 0L
        val polozaj = p.currentPosition.coerceAtLeast(0)
        zdajCas.text = (if (p.isPlaying) "▶  " else "❚❚  ") + if (trajanje > 0) "${oblikuj(polozaj)} / ${oblikuj(trajanje)}" else oblikuj(polozaj)
        zdajPotek.progress = if (trajanje > 0) (polozaj * 1000 / trajanje).toInt() else 0
        if (tema.visibility == View.VISIBLE) {
            temaUra.text = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date())
            // Vsako minuto malo drugje, da se na zaslonu nic ne vzge.
            val minuta = (System.currentTimeMillis() / 60_000).toInt()
            tema.getChildAt(0).translationX = ((minuta * 37) % 121 - 60).toFloat() * resources.displayMetrics.density
            tema.getChildAt(0).translationY = ((minuta * 53) % 81 - 40).toFloat() * resources.displayMetrics.density
        }
    }

    private fun oblikuj(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    private fun preklopi() {
        val p = GlasbaStoritev.predvajalnik ?: return
        if (p.isPlaying) p.pause() else p.play()
        osveziCas()
    }

    private fun pokaziTemo(da: Boolean) {
        tema.visibility = if (da) View.VISIBLE else View.GONE
        if (da) osveziCas()
    }

    /** Vsaka tipka odmakne zatemnitev za 30 s. */
    private fun budilka() {
        glavna.removeCallbacks(zatemni)
        glavna.postDelayed(zatemni, 30_000)
    }

    override fun dispatchKeyEvent(dogodek: KeyEvent): Boolean {
        budilka()
        if (tema.visibility == View.VISIBLE) {
            // Tipka samo zbudi zaslon; tipke za predvajanje delajo tudi v temi.
            val medij = dogodek.keyCode in setOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            if (!medij) {
                if (dogodek.action == KeyEvent.ACTION_UP) pokaziTemo(false)
                return true
            }
        }
        return super.dispatchKeyEvent(dogodek)
    }

    override fun dispatchTouchEvent(dogodek: android.view.MotionEvent): Boolean {
        budilka()
        if (tema.visibility == View.VISIBLE) {
            if (dogodek.action == android.view.MotionEvent.ACTION_UP) pokaziTemo(false)
            return true
        }
        return super.dispatchTouchEvent(dogodek)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val p = GlasbaStoritev.predvajalnik
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { preklopi(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p?.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p?.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (p?.hasNextMediaItem() == true) p.seekToNextMediaItem(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { p?.seekToPreviousMediaItem(); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { GlasbaStoritev.ustavi(this); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun plosekDejanje(koda: Int): Boolean = when (koda) {
        KeyEvent.KEYCODE_BUTTON_X -> { preklopi(); true }
        KeyEvent.KEYCODE_BUTTON_R1 -> { GlasbaStoritev.predvajalnik?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }; true }
        KeyEvent.KEYCODE_BUTTON_L1 -> { GlasbaStoritev.predvajalnik?.seekToPreviousMediaItem(); true }
        else -> false
    }

}
