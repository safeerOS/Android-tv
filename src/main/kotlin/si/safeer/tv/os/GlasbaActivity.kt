package si.safeer.tv.os

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.Player
import si.safeer.tv.R
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ConcurrentHashMap

/**
 * Safeer Media: glasba, video in radio z vseh virov na enem mestu, brez oglasov.
 *
 * Levo je stranski meni Safeer OS (Safeer Media je en njegov del, kot na domacem zaslonu). Desno
 * je nadzorna plosca: velike kartice Glasba, Video, Radio in Viri, "zdaj se predvaja" s tipkami
 * in hitrimi dejanji, nedavno predvajano, tvoji viri, priljubljene in priporocila. Kartica odpre
 * svoj razdelek (vrste kartic, ki jih drsis z daljincem); Nazaj se vrne na nadzorno plosco.
 *
 * Viri: Jamendo (neodvisni izvajalci, najbolj poslusano najprej), internetni radio (Radio Browser,
 * najprej domace postaje), PeerTube (vgrajeni in uporabnikovi strezniki), uporabnikovi tokovi in
 * strani ter datoteke na televizorju in napravah v Safeer Linku. Predvaja [GlasbaStoritev], zato
 * vse igra tudi v ozadju; [PredvajanjeActivity] je celozaslonski prikaz.
 *
 * Na plosci so samo dejanja, ki res delujejo - brez gumbov za se nenarejene funkcije.
 */
class GlasbaActivity : OsActivity() {

    /** Vrsta kartic; [pogled] je posebna vrsta (npr. tvoji viri), ki se narise tako, kot je. */
    private data class Vrsta(val naslov: String, val kartice: List<Kartica>, val video: Boolean = false, val pogled: View? = null, val mala: Boolean = false,
                             val mreza: Boolean = false)
    /** Podatki vrste brez zaslona - samo to gre v predpomnilnik (kartice drzijo zaslon). */
    private data class Podatki(val naslov: String, val skladbe: List<Jamendo.Skladba>, val video: Boolean = false)
    private data class Kartica(val naslov: String, val podnaslov: String, val slika: String, val klik: (View) -> Unit,
                               val dolgo: ((View) -> Unit)? = null, val ikona: Int = R.drawable.os_ikona_glasba)

    private val delavec = Executors.newFixedThreadPool(4)
    // Omrezno iskanje ima lasten omejen pool. Prejsnje iskanje preklicemo, da pocasni
    // strezniki ne zasedajo CPU/omrezja se dolgo po novem vnosu.
    private val iskanjeDelavec = Executors.newFixedThreadPool(4)
    private val iskanjeNiti = mutableListOf<Future<*>>()
    private val slikeVTeKu = ConcurrentHashMap.newKeySet<String>()
    private val glavna = Handler(Looper.getMainLooper())

    private lateinit var koren: View
    private lateinit var meniMediji: View
    private lateinit var naslov: TextView
    private lateinit var geslo: TextView
    /** Stranski meni in njegova besedila: na ozkem zaslonu (telefon pokonci) ostanejo samo ikone. */
    private lateinit var meni: LinearLayout
    private val besedilaMenija = ArrayList<View>()
    private lateinit var desnoOkvir: LinearLayout
    private lateinit var iskanjeGumb: View
    private lateinit var vsebina: LinearLayout
    private lateinit var drsnik: ScrollView
    private lateinit var stanje: TextView
    private lateinit var vrstica: LinearLayout
    private lateinit var zdajNaslov: TextView
    private lateinit var zdajIzvajalec: TextView
    private lateinit var zdajCas: TextView
    private var razdelek = DOMOV
    private var nalaganje = 0
    private var iskalnik: EditText? = null
    /** Po izbiri kartice razdelka gre fokus na prvo kartico vsebine, ko se narise. */
    private var fokusVVsebino = false
    private var videnPrej = false
    private val poslusalec: () -> Unit = { glavna.post { osveziZdaj() } }
    private val tik = object : Runnable { override fun run() { osveziZdaj(); glavna.postDelayed(this, 1_000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        odprta = true
        setContentView(zgradi())
        prilagodiMeni()
        izberi(DOMOV)
        drsnik.post { if (window.decorView.findFocus() == null || razdelek == DOMOV) vsebina.findViewWithTag<View>(KLJUC_GLASBA)?.requestFocus() }
        iskanjeIzNamena()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        iskanjeIzNamena()
    }

    /** Iskanje, odprto od drugod (zaslon predvajanja, tipka Isci): razdelek Iskanje, polje pripravljeno. */
    private fun iskanjeIzNamena() {
        val beseda = intent.getStringExtra(ISKANJE_BESEDA) ?: return
        intent.removeExtra(ISKANJE_BESEDA)
        odpriIskanje(beseda)
    }

    override fun onStart() {
        super.onStart()
        Ozadje.uporabi(this, koren)
        GlasbaStoritev.poslusalci.add(poslusalec)
        glavna.post(tik)
        // Ob vrnitvi (npr. iz predvajanja) sta se nedavno in stanje predvajanja lahko spremenila.
        if (videnPrej && razdelek == DOMOV) izberi(DOMOV)
        videnPrej = true
    }

    override fun onStop() {
        GlasbaStoritev.poslusalci.remove(poslusalec)
        glavna.removeCallbacks(tik)
        super.onStop()
    }

    override fun onDestroy() {
        odprta = false
        synchronized(iskanjeNiti) { iskanjeNiti.forEach { it.cancel(true) }; iskanjeNiti.clear() }
        iskanjeDelavec.shutdownNow()
        delavec.shutdownNow()
        super.onDestroy()
    }

    /** Nazaj iz razdelka vrne na nadzorno plosco; s plosce zapusti Safeer Media. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (razdelek != DOMOV) {
            val kljuc = when (razdelek) { GLASBA -> KLJUC_GLASBA; VIDEO -> KLJUC_VIDEO; RADIO -> KLJUC_RADIO; VIRI -> KLJUC_VIRI; else -> KLJUC_GLASBA }
            izberi(DOMOV)
            drsnik.post { vsebina.findViewWithTag<View>(kljuc)?.requestFocus() }
            return
        }
        super.onBackPressed()
    }

    // ------------------------------------------------------------------ postavitev

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** TV profil: na 16:9 televizorju uporabimo prostor bolj vodoravno in vecji Now Playing. */
    private fun jeTv() = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    private fun jeSirokTv() = jeTv() && resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE


    private fun besedilo(vel: Float, barva: Int, krepko: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, vel); setTextColor(barva); maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        if (krepko) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    private fun ikona(res: Int, vel: Int, barva: Int? = null) = ImageView(this).apply {
        setImageResource(res); scaleType = ImageView.ScaleType.FIT_CENTER
        if (barva != null) imageTintList = ColorStateList.valueOf(barva)
        layoutParams = LinearLayout.LayoutParams(dp(vel), dp(vel))
    }

    /** Okrogel gumb: pod fokusom mint obroba, sicer prozoren. */
    private fun ozadjeGumba(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(getColor(R.color.os_kartica_dvignjena)); setStroke(dp(2), getColor(R.color.os_mint)) })
        addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0) })
    }

    private fun gumb(res: Int, vel: Int, kljuc: String, klik: () -> Unit) = FrameLayout(this).apply {
        tag = kljuc
        isFocusable = true; isClickable = true
        background = ozadjeGumba()
        setOnClickListener { klik() }
        addView(ImageView(this@GlasbaActivity).apply { setImageResource(res); imageTintList = ColorStateList.valueOf(getColor(R.color.os_besedilo)) },
            FrameLayout.LayoutParams(dp(vel * 5 / 9), dp(vel * 5 / 9), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(vel), dp(vel)).apply { marginEnd = dp(6) }
    }

    /** Telefon pokonci: prostor gre vsebini, meni ostane kot stolpec ikon (prej je vzel dve tretjini). */
    private fun ozekZaslon() = resources.configuration.screenWidthDp < 600

    private fun prilagodiMeni() {
        val ozek = ozekZaslon()
        meni.layoutParams = (meni.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (ozek) dp(68) else resources.getDimensionPixelSize(R.dimen.os_meni_sirina)
        }
        meni.setPadding(dp(if (ozek) 10 else 16), dp(24), dp(if (ozek) 10 else 12), dp(16))
        for (v in besedilaMenija) v.visibility = if (ozek) View.GONE else View.VISIBLE
        desnoOkvir.setPadding(dp(if (ozek) 14 else 28), dp(10), dp(if (ozek) 14 else 28), dp(8))
    }

    /** Zasuk brez novega zaslona (configChanges): meni in trenutni razdelek narisemo za novo sirino. */
    override fun onConfigurationChanged(novo: android.content.res.Configuration) {
        super.onConfigurationChanged(novo)
        prilagodiMeni()
        if (razdelek != ISKANJE) izberi(razdelek)
    }

    private fun zgradi(): View {
        val beli = getColor(R.color.os_besedilo)
        val k = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(getColor(R.color.os_ozadje)) }
        koren = k
        k.addView(stranskiMeni(), LinearLayout.LayoutParams(resources.getDimensionPixelSize(R.dimen.os_meni_sirina), -1))

        val desno = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(if (jeSirokTv()) 30 else 28), dp(10), dp(if (jeSirokTv()) 30 else 28), dp(8)) }
        desnoOkvir = desno

        // Glava: naslov in opis razdelka, desno geslo in iskanje
        val glava = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val levo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        naslov = besedilo(28f, beli, true).apply { typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) }
        stanje = besedilo(14f, getColor(R.color.os_umirjeno))
        levo.addView(naslov); levo.addView(stanje)
        glava.addView(levo, LinearLayout.LayoutParams(0, -2, 1f))
        geslo = TextView(this).apply {
            text = getString(R.string.os_media_geslo); setTextColor(getColor(R.color.os_umirjeno))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); gravity = Gravity.END; setPadding(0, 0, dp(16), 0)
        }
        glava.addView(geslo)
        iskanjeGumb = gumb(R.drawable.os_ikona_isci, 48, "k:iskanje") { odpriIskanje("") }
        glava.addView(iskanjeGumb)
        desno.addView(glava)

        vsebina = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(8)) }
        drsnik = ScrollView(this).apply { addView(vsebina); isFillViewport = true; isVerticalScrollBarEnabled = false }
        desno.addView(drsnik, LinearLayout.LayoutParams(-1, 0, 1f))

        // Mala vrstica "zdaj se predvaja" v razdelkih (na plosci je velika)
        vrstica = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(getColor(R.color.os_kartica_dvignjena)); setStroke(dp(1), getColor(R.color.os_crta)) }
            isFocusable = true; isClickable = true
            setOnClickListener { startActivity(Intent(this@GlasbaActivity, PredvajanjeActivity::class.java)) }
            setOnFocusChangeListener { v, f -> (v.background as GradientDrawable).setStroke(dp(if (f) 2 else 1), getColor(if (f) R.color.os_mint else R.color.os_crta)) }
            visibility = View.GONE
        }
        val besedila = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        zdajNaslov = besedilo(15f, beli, true); zdajIzvajalec = besedilo(13f, getColor(R.color.os_umirjeno))
        besedila.addView(zdajNaslov); besedila.addView(zdajIzvajalec)
        vrstica.addView(besedila, LinearLayout.LayoutParams(0, -2, 1f))
        zdajCas = besedilo(14f, getColor(R.color.os_mint))
        vrstica.addView(zdajCas)
        desno.addView(vrstica, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        // Tipke daljinca samo na televizorju; tablica se upravlja z dotikom.
        if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK))
            desno.addView(pomoc(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        k.addView(desno, LinearLayout.LayoutParams(0, -1, 1f))
        return k
    }

    /** Stranski meni Safeer OS; Safeer Media je izbran, ostalo odpre isti zaslon kot na domacem zaslonu. */
    private fun stranskiMeni(): View {
        val beli = getColor(R.color.os_besedilo)
        val m = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(12), dp(16))
            setBackgroundColor(getColor(R.color.os_meni_ozadje))
        }
        meni = m
        val znak = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, 0, dp(22)) }
        znak.addView(ikona(R.drawable.os_znak, 32))
        val imeOs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        imeOs.addView(besedilo(18f, beli, true).apply { text = getString(R.string.os_app_name) })
        imeOs.addView(besedilo(11f, getColor(R.color.os_umirjeno)).apply { text = getString(R.string.os_podnaslov_app); maxLines = 2 })
        znak.addView(imeOs)
        besedilaMenija.add(imeOs)
        m.addView(znak)

        fun postavka(res: Int, ime: String, opis: String? = null, klik: () -> Unit): View = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            id = View.generateViewId()
            isFocusable = true; isClickable = true
            setBackgroundResource(R.drawable.os_meni_postavka)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { klik() }
            addView(ikona(res, 22, beli))
            val t = LinearLayout(this@GlasbaActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            t.addView(besedilo(14f, beli, true).apply { text = ime; maxLines = 2 })
            if (opis != null) t.addView(besedilo(11f, getColor(R.color.os_umirjeno)).apply { text = opis; maxLines = 2 })
            addView(t)
            besedilaMenija.add(t)
            m.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        fun odpri(i: Intent) { try { startActivity(i) } catch (_: Exception) { Toast.makeText(this, R.string.os_odpri_ni_aplikacije, Toast.LENGTH_SHORT).show() } }
        postavka(R.drawable.os_ikona_domov, getString(R.string.os_meni_domov)) { finish() }
        postavka(R.drawable.os_ikona_splet, getString(R.string.os_brskalnik_naslov)) { odpri(Brskalnik.izMedijev(Brskalnik.namera(this))) }
        meniMediji = postavka(R.drawable.os_ikona_glasba, getString(R.string.os_media_naslov), getString(R.string.os_media_meni_opis)) {
            if (razdelek != DOMOV) onBackPressed() else vsebina.findViewWithTag<View>(KLJUC_GLASBA)?.requestFocus()
        }.apply { isActivated = true; isSelected = true }
        postavka(R.drawable.os_ikona_aplikacije, getString(R.string.os_meni_aplikacije)) {
            odpri(Intent(this, AplikacijeHostaActivity::class.java).putExtra(AplikacijeHostaActivity.EXTRA_VIR, "vse")) }
        postavka(R.drawable.os_ikona_datoteke, getString(R.string.os_meni_datoteke)) { odpri(Intent(this, DatotekeActivity::class.java)) }
        postavka(R.drawable.os_ikona_link, getString(R.string.os_meni_naprave)) { odpri(Intent(this, NapraveActivity::class.java)) }
        postavka(R.drawable.os_ikona_nastavitve, getString(R.string.os_meni_nastavitve)) { odpri(Intent(this, NastavitveActivity::class.java)) }
        m.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        m.addView(besedilo(11f, getColor(R.color.os_umirjeno)).apply { text = getString(R.string.os_poganja); setPadding(dp(4), 0, 0, 0)
            besedilaMenija.add(this) })
        val link = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), 0, 0) }
        link.addView(ikona(R.drawable.os_ikona_link, 16, getColor(R.color.os_mint)))
        link.addView(besedilo(13f, getColor(R.color.os_mint), true).apply { text = getString(R.string.os_link); setPadding(dp(6), 0, 0, 0)
            besedilaMenija.add(this) })
        m.addView(link)
        return m
    }

    /** Vrstica pomoci spodaj: tipke daljinca. */
    private fun pomoc(): View {
        val v = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        fun tipka(t: String, opis: Int) {
            v.addView(besedilo(11f, getColor(R.color.os_besedilo), true).apply {
                text = t; setBackgroundResource(R.drawable.os_tipka); setPadding(dp(8), dp(2), dp(8), dp(2)) })
            v.addView(besedilo(12f, getColor(R.color.os_umirjeno)).apply { text = getString(opis); setPadding(dp(6), 0, dp(18), 0) })
        }
        tipka("OK", R.string.os_media_pomoc_izberi)
        tipka("↩", R.string.os_media_pomoc_nazaj)
        tipka("OK ●", R.string.os_media_pomoc_meni)
        tipka("🔍", R.string.os_media_pomoc_isci)
        return v
    }

    /** Kartica; [mala] je za nedavno na plosci (manjsa, samo naslov), da vrsta ostane na zaslonu. */
    private fun kartica(k: Kartica, video: Boolean, prva: Boolean, kljuc: String, mala: Boolean = false, velikostDp: Int = 0): View {
        val sirina = dp(if (velikostDp > 0) velikostDp else if (mala) 96 else if (video) 224 else 150)
        val visina = if (video) sirina * 9 / 16 else sirina
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = kljuc
            if (mala) setPadding(dp(3), dp(3), dp(3), dp(3)) else setPadding(dp(6), dp(6), dp(6), dp(8))
            setBackgroundResource(R.drawable.os_ploscica_app)
            isFocusable = true; isClickable = true
            setOnClickListener { k.klik(it) }
            k.dolgo?.let { d -> setOnLongClickListener { d(it); true } }
            // Iz prve kartice levo nazaj v stranski meni.
            if (prva) nextFocusLeftId = meniMediji.id
            val slika = ImageView(this@GlasbaActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(getColor(R.color.os_kartica))
                setImageResource(k.ikona)
                // Kartica brez slike: ikona zmerne velikosti na sredini, ne cez vso kartico.
                if (k.slika.isBlank()) { scaleType = ImageView.ScaleType.FIT_CENTER; val r = minOf(sirina, visina) / 4; setPadding(r, r, r, r) }
            }
            addView(slika, LinearLayout.LayoutParams(sirina, visina))
            addView(besedilo(if (mala) 11f else 14f, getColor(R.color.os_besedilo), true).apply { text = k.naslov; setPadding(dp(2), dp(if (mala) 2 else 8), 0, 0) },
                LinearLayout.LayoutParams(sirina, -2))
            if (!mala) addView(besedilo(12f, getColor(R.color.os_umirjeno)).apply { text = k.podnaslov; setPadding(dp(2), 0, 0, 0) },
                LinearLayout.LayoutParams(sirina, -2))
            naloziSliko(k.slika, slika)
        }
    }

    /** Kljuc pogleda s fokusom (kartice in gumbi imajo tag "k:..."), da ga po ponovnem risanju najdemo. */
    private fun kljucFokusa(): String? {
        var v: View? = window.decorView.findFocus()
        while (v != null && v !== vsebina) {
            (v.tag as? String)?.takeIf { it.startsWith("k:") }?.let { return it }
            v = v.parent as? View
        }
        return null
    }

    private fun narisi(vrste: List<Vrsta>, opis: String, prazno: String = getString(R.string.os_glasba_prazno), glava: List<View> = emptyList()) {
        val kljuc = if (vsebina.hasFocus()) kljucFokusa() else null
        // Fokus iz vsebine, ki jo bomo zamenjali, v meni - sicer skoci na prvi element zaslona.
        if (vsebina.hasFocus()) meniMediji.requestFocus()
        vsebina.removeAllViews()
        stanje.text = if (glava.isEmpty() && vrste.all { it.kartice.isEmpty() && it.pogled == null }) prazno else opis
        iskalnik?.let { vsebina.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
        glava.forEach { vsebina.addView(it) }
        for (v in vrste) {
            if (v.kartice.isEmpty() && v.pogled == null) continue
            val tesno = v.mala || razdelek == DOMOV
            if (v.naslov.isNotBlank())
                vsebina.addView(besedilo(if (tesno) 16f else 18f, getColor(R.color.os_besedilo), true).apply {
                    text = v.naslov; tag = NASLOV_VRSTE; setPadding(dp(4), dp(if (tesno) 5 else 14), 0, dp(if (tesno) 3 else 8)) })
            if (v.pogled != null) { vsebina.addView(v.pogled); continue }
            if (v.mreza) {
                // Mreza (Moji viri): toliko kartic v vrsto, kolikor jih gre celih, ostale v naslednjo vrsto.
                val korak = dp(MREZA_DP + 12 + 14)
                val n = ((vsebina.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels * 3 / 4)) / korak).coerceAtLeast(1)
                v.kartice.chunked(n).forEachIndexed { r, del ->
                    vsebina.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; tag = MREZA_VRSTA
                        del.forEachIndexed { i, k ->
                            addView(kartica(k, false, i == 0, "k:${v.naslov}#${r * n + i}", velikostDp = MREZA_DP), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14); bottomMargin = dp(14) })
                        }
                    })
                }
                continue
            }
            val niz = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            v.kartice.forEachIndexed { i, k ->
                niz.addView(kartica(k, v.video, i == 0, "k:${v.naslov}#$i", v.mala), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(if (v.mala) 10 else 14) })
            }
            vsebina.addView(HorizontalScrollView(this).apply {
                addView(niz); isHorizontalScrollBarEnabled = false; clipToPadding = false
            })
        }
        if (kljuc == null) drsnik.scrollTo(0, 0)
        brezOdrezanihVrst()
        drsnik.post {
            // "Nadaljuj" se po zacetku predvajanja zamenja s tipkami - izbira gre na predvajaj/pavza.
            val nazaj = kljuc?.let { vsebina.findViewWithTag<View>(it) ?: if (it == "k:nadaljuj") vsebina.findViewWithTag<View>("k:predvajaj") else null }
            when {
                nazaj != null -> nazaj.requestFocus()
                fokusVVsebino -> { fokusVVsebino = false; fokusNaPrvo() }
                // Po zaprtem oknu (Dodaj vir, Odstrani) fokus ne sme ostati nikjer.
                window.decorView.findFocus() == null -> meniMediji.requestFocus()
            }
        }
    }

    /**
     * Na prvem zaslonu ni odrezane vrste: prva vrsta, ki ne gre cela nad spodnji rob, se skupaj
     * s svojim naslovom odmakne pod rob (pokaze se ob drsenju). Merimo po postavitvi, ne ugibamo.
     */
    private fun brezOdrezanihVrst() {
        vsebina.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                vsebina.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val vidno = drsnik.height
                if (vidno <= 0) return
                for (i in 0 until vsebina.childCount) {
                    val v = vsebina.getChildAt(i)
                    if (v.bottom <= vidno) continue
                    // Samo vrste kartic (in njihove naslove); plosce na vrhu ostanejo, kot so.
                    if (v !is HorizontalScrollView && v.tag != NASLOV_VRSTE && v.tag != MREZA_VRSTA) return
                    val zacetek = if (i > 0 && vsebina.getChildAt(i - 1).tag == NASLOV_VRSTE) i - 1 else i
                    val vrh = vsebina.getChildAt(zacetek).top
                    if (zacetek > 0 && vrh < vidno) vsebina.addView(View(this@GlasbaActivity), zacetek, LinearLayout.LayoutParams(-1, vidno - vrh))
                    return
                }
            }
        })
    }

    // ------------------------------------------------------------------ slike

    private fun naloziSliko(naslov: String, v: ImageView) {
        if (!naslov.startsWith("https://")) return
        SLIKE.get(naslov)?.let { v.setImageBitmap(it); return }
        v.tag = naslov
        // Vec kartic lahko uporablja isto naslovnico. Prenesi/dekodiraj jo samo enkrat.
        if (!slikeVTeKu.add(naslov)) return
        delavec.execute {
            try {
                // 280 px je dovolj za TV kartice, hkrati pa precej zmanjsa heap in GC sunke na 2 GB TV.
                val b = Jamendo.bajti(naslov)?.let { VarnaSlika.izBajtov(it, 280) } ?: return@execute
                SLIKE.put(naslov, b)
                glavna.post { if (!isFinishing && v.tag == naslov) v.setImageBitmap(b) }
            } finally { slikeVTeKu.remove(naslov) }
        }
    }

    // ------------------------------------------------------------------ razdelki

    private fun izberi(i: Int) {
        razdelek = i
        naslov.text = getString(when (i) {
            GLASBA -> R.string.os_mediji_glasba; RADIO -> R.string.os_glasba_radio; VIDEO -> R.string.os_glasba_video
            VIRI -> R.string.os_mediji_viri; ISKANJE -> R.string.os_glasba_iskanje; else -> R.string.os_media_naslov
        })
        geslo.visibility = if (i == DOMOV && !ozekZaslon()) View.VISIBLE else View.GONE
        iskalnik = if (i == ISKANJE) novIskalnik() else null
        // Na plosci je velik "zdaj se predvaja"; mala vrstica spodaj je samo v razdelkih.
        vrstica.visibility = if (i == DOMOV || GlasbaStoritev.trenutna() == null) View.GONE else View.VISIBLE
        val moje = ++nalaganje
        val predpomnjeno = SEZNAMI[i]
        if (predpomnjeno != null) { prikazi(i, predpomnjeno); return }
        if (i == VIRI) { narisi(viriVrste(), opis(i)); return }
        if (i == ISKANJE) { zadetki?.let { narisi(it, opisZadetkov) } ?: narisi(predIskanjem(), getString(R.string.os_glasba_isci_navodilo)); return }
        // Kar je na napravi, pokazemo takoj; vrste s spleta pridejo, ko se nalozijo.
        narisi(zgoraj(i), getString(R.string.os_glasba_nalagam), getString(R.string.os_glasba_nalagam), glavaRazdelka(i))
        delavec.execute {
            val podatki = try { podatkiRazdelka(i) } catch (_: Exception) { null }
            glavna.post {
                if (moje != nalaganje || isFinishing) return@post
                if (podatki == null) { stanje.text = getString(R.string.os_glasba_napaka); return@post }
                // Prazen odgovor (Jamendo obcasno) ne ostane v predpomnilniku - ob naslednji izbiri poskusimo znova.
                if (podatki.all { it.skladbe.isNotEmpty() }) SEZNAMI[i] = podatki
                prikazi(i, podatki)
            }
        }
    }

    /** Razdelek: najprej krajevno (nadzorna plosca, nedavno, priljubljene), nato vrste s spleta. */
    private fun prikazi(i: Int, podatki: List<Podatki>) =
        narisi(zgoraj(i) + vVrste(podatki), opis(i), glava = glavaRazdelka(i))

    private fun opis(i: Int) = when (i) {
        GLASBA -> getString(R.string.os_media_gl_isci_opis)
        RADIO -> getString(R.string.os_glasba_radiji)
        VIDEO -> getString(R.string.os_glasba_video_opis)
        VIRI -> getString(R.string.os_mediji_viri_opis)
        else -> getString(R.string.os_media_podnaslov)
    }

    /** Podatki razdelka s spleta (klic na delovni niti). */
    private fun podatkiRazdelka(i: Int): List<Podatki> = when (i) {
        DOMOV -> {
            val (domace, svet) = Radio.postajeLocene()
            listOf(
                Podatki(getString(R.string.os_mediji_vrsta_glasba), Jamendo.priljubljene(24)),
                Podatki(getString(R.string.os_mediji_vrsta_radio), (domace + svet).take(24)),
                Podatki(getString(R.string.os_mediji_vrsta_video), izmenicno(MedijskiViri.streznikiPeerTube(this).map { s ->
                    try { PeerTube.najboljGledani(s, 12) } catch (_: Exception) { emptyList() } }), video = true),
            )
        }
        GLASBA -> {
            // Discovery namesto podvajanja ene same vrste "Most played": najprej en unikaten
            // popularen izbor, nato zvrsti. Posamezna skladba se med vrstami prikaze samo enkrat.
            val uporabljeni = mutableSetOf<String>()
            fun unikatne(s: List<Jamendo.Skladba>, meja: Int = 18) = s.filter { uporabljeni.add(it.id) }.take(meja)
            val vrste = mutableListOf<Podatki>()
            unikatne(Jamendo.priljubljene(24), 18).takeIf { it.isNotEmpty() }?.let {
                vrste += Podatki(getString(R.string.os_media_popularno), it)
            }
            listOf(
                "rock" to R.string.os_media_zvrst_rock,
                "pop" to R.string.os_media_zvrst_pop,
                "electronic" to R.string.os_media_zvrst_electronic,
                "jazz" to R.string.os_media_zvrst_jazz,
                "classical" to R.string.os_media_zvrst_classical,
                "hiphop" to R.string.os_media_zvrst_hiphop
            ).forEach { (tag, naziv) ->
                unikatne(Jamendo.poZvrsti(tag, 18)).takeIf { it.isNotEmpty() }?.let { vrste += Podatki(getString(naziv), it) }
            }
            vrste
        }
        RADIO -> Radio.postajeLocene().let { (domace, svet) ->
            listOf(Podatki(getString(R.string.os_mediji_domace), domace), Podatki(getString(R.string.os_mediji_svet), svet)) }
        VIDEO -> MedijskiViri.streznikiPeerTube(this).map { s ->
            Podatki(s, try { PeerTube.najboljGledani(s, 24) } catch (_: Exception) { emptyList() }, video = true) }
        else -> emptyList()
    }

    private fun vVrste(p: List<Podatki>) = p.map { d ->
        Vrsta(d.naslov, if (d.video) videi(d.skladbe, d.naslov) else skladbe(d.skladbe, d.naslov), d.video)
    }

    /** Krajevne vrste na vrhu razdelka: nedavno, tvoji viri, priljubljene in seznami, ki sodijo vanj. */
    private fun zgoraj(i: Int): List<Vrsta> {
        val p = MedijskiViri.priljubljene(this)
        val seznami = MedijskiViri.seznami(this)
        fun seznamVrsta(sz: MedijskiViri.Seznam) = sz.skladbe.all { it.video }.let { video ->
            Vrsta("≡  " + sz.ime, if (video) videi(sz.skladbe, sz.ime, sz) else skladbe(sz.skladbe, sz.ime, sz), video) }
        return when (i) {
            DOMOV -> listOf(
                Vrsta(getString(R.string.os_media_nedavno), MedijskiViri.nedavno(this).let { n ->
                    val kartice = n.map { sk ->
                        Kartica(sk.naslov, sk.izvajalec, sk.slika, { predvajaj(listOf(sk), 0) }, { meniNedavno(sk) },
                            ikona = if (sk.video) R.drawable.os_ikona_video else if (sk.radio) R.drawable.os_ikona_radio else R.drawable.os_ikona_glasba)
                    }
                    if (n.isEmpty()) kartice else kartice + Kartica(getString(R.string.os_media_pocisti_nedavno), getString(R.string.os_media_pocisti_nedavno_opis), "", { potrdiPocistiNedavno() }, ikona = R.drawable.os_ikona_ustavi)
                }, video = true, mala = true),
                Vrsta(getString(R.string.os_media_tvoji_viri), emptyList(), pogled = tvojiViri()),
                Vrsta(getString(R.string.os_mediji_prilj_glasba), skladbe(p.filterNot { it.video })),
                Vrsta(getString(R.string.os_mediji_prilj_video), videi(p.filter { it.video }), video = true),
            ) + seznami.map { seznamVrsta(it) }
            GLASBA -> listOf(Vrsta(getString(R.string.os_mediji_prilj_glasba), skladbe(p.filterNot { it.video || it.radio }))) +
                seznami.filterNot { sz -> sz.skladbe.all { it.video } }.map { seznamVrsta(it) }
            RADIO -> listOf(Vrsta(getString(R.string.os_media_prilj_radio), skladbe(p.filter { it.radio })))
            VIDEO -> listOf(Vrsta(getString(R.string.os_mediji_prilj_video), videi(p.filter { it.video }), video = true)) +
                seznami.filter { sz -> sz.skladbe.all { it.video } }.map { seznamVrsta(it) }
            else -> emptyList()
        }
    }

    // ------------------------------------------------------------------ nadzorna plosca

    /** Pogledi nad vrstami: na plosci kartice razdelkov ter "zdaj se predvaja" s hitrimi dejanji. */
    private fun glavaRazdelka(i: Int): List<View> = if (i != DOMOV) emptyList() else listOfNotNull(kategorije(), zdajPlosca())

    private fun kategorije(): View {
        // Televizor (16:9) ima kategorije vedno v eni vrsti; na ozkem zaslonu (tablica pokonci) dve vrsti
        // po dve kartici, na telefonu pokonci ena kartica v vrsti - da opisi niso odrezani.
        val sirina = resources.configuration.screenWidthDp
        val naVrsto = if (jeSirokTv()) 4 else if (sirina < 600) 1 else if (sirina < 900) 2 else 4
        val okvir = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(2)) }
        val vrsti = List(4 / naVrsto) { LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also {
            okvir.addView(it, LinearLayout.LayoutParams(-1, -2).apply { if (okvir.childCount > 0) topMargin = dp(10) }) } }
        var stevec = 0
        fun kat(kljuc: String, res: Int, barva: Int, ime: Int, opis: Int, klik: () -> Unit) {
            val v = vrsti[stevec / naVrsto]
            stevec++
            v.addView(LinearLayout(this).apply {
                tag = kljuc
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                isFocusable = true; isClickable = true
                setBackgroundResource(R.drawable.os_kartica_steklo)
                setPadding(dp(14), dp(if (jeSirokTv()) 8 else 6), dp(12), dp(if (jeSirokTv()) 8 else 6))
                setOnClickListener { klik() }
                if (v.childCount == 0) nextFocusLeftId = meniMediji.id
                addView(ikona(res, 28, barva))
                val t = LinearLayout(this@GlasbaActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
                t.addView(besedilo(16f, getColor(R.color.os_besedilo), true).apply { text = getString(ime) })
                t.addView(besedilo(11f, getColor(R.color.os_umirjeno)).apply { text = getString(opis) })
                addView(t)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        }
        fun odpri(i: Int) { fokusVVsebino = true; izberi(i) }
        kat(KLJUC_GLASBA, R.drawable.os_ikona_glasba, 0xFF8FA8FF.toInt(), R.string.os_mediji_glasba, R.string.os_media_glasba_opis) { odpri(GLASBA) }
        kat(KLJUC_VIDEO, R.drawable.os_ikona_video, 0xFFFF9580.toInt(), R.string.os_glasba_video, R.string.os_media_video_opis) { odpri(VIDEO) }
        kat(KLJUC_RADIO, R.drawable.os_ikona_radio, getColor(R.color.os_mint), R.string.os_glasba_radio, R.string.os_media_radio_opis) { odpri(RADIO) }
        kat(KLJUC_VIRI, R.drawable.os_ikona_mapa, 0xFF7FB2FF.toInt(), R.string.os_mediji_viri, R.string.os_media_viri_opis) { odpri(VIRI) }
        return okvir
    }

    // Pogledi plosce "zdaj se predvaja", ki jih tik vsako sekundo osvezi (null, ko plosce ni).
    private var pSlika: ImageView? = null
    private var pSlikaNaslov = ""
    private var pNaslov: TextView? = null
    private var pIzvajalec: TextView? = null
    private var pVir: TextView? = null
    private var pPotek: ProgressBar? = null
    private var pCas: TextView? = null
    private var pPredvajaj: ImageView? = null
    private var pNakljucno: ImageView? = null
    private var pPonavljaj: ImageView? = null
    private var pSrce: ImageView? = null
    private var hHitrost: TextView? = null
    private var hCasovnik: TextView? = null
    /** Ali je plosca narisana za predvajanje (true) ali za zadnje predvajano (false). */
    private var pZaPredvajanje: Boolean? = null

    /** "Zdaj se predvaja" (ali zadnje predvajano z gumbom Nadaljuj) in hitra dejanja; null, ce ni nicesar. */
    private fun zdajPlosca(): View? {
        pSlika = null; pNaslov = null; pIzvajalec = null; pVir = null; pPotek = null; pCas = null
        pPredvajaj = null; pNakljucno = null; pPonavljaj = null; pSrce = null; hHitrost = null; hCasovnik = null
        val sk = GlasbaStoritev.trenutna()
        val p = GlasbaStoritev.predvajalnik
        val zadnja = if (sk == null) MedijskiViri.nedavno(this).firstOrNull() else null
        pZaPredvajanje = if (sk != null && p != null) true else if (zadnja != null) false else null
        val prikaz = sk ?: zadnja ?: return null
        val beli = getColor(R.color.os_besedilo)
        // Na ozkem zaslonu (tablica pokonci) so hitra dejanja pod plosco, ne ob njej.
        val ozko = !jeSirokTv() && resources.configuration.screenWidthDp < 900
        val vrsta = LinearLayout(this).apply { orientation = if (ozko) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }

        val plosca = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(getColor(R.color.os_kartica_steklo)); setStroke(dp(1), getColor(R.color.os_kartica_obroba)) }
            setPadding(dp(if (jeSirokTv()) 18 else 14), dp(if (jeSirokTv()) 12 else 10), dp(if (jeSirokTv()) 18 else 16), dp(if (jeSirokTv()) 12 else 10))
        }
        // Oznaka "zdaj se predvaja" je v vrstici z izvajalcem - loceni naslov bi vzel prostor vrsti spodaj.
        val oznaka = getString(if (pZaPredvajanje == true) R.string.os_media_zdaj else R.string.os_media_nazadnje)
        val telo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val slika = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(getColor(R.color.os_kartica))
            setImageResource(if (prikaz.video) R.drawable.os_ikona_video else if (prikaz.radio) R.drawable.os_ikona_radio else R.drawable.os_ikona_glasba)
        }
        pSlika = slika; pSlikaNaslov = ""
        // Naslovnica odpre celozaslonski prikaz (tam je s tipko dol tudi vrsta).
        val okvir = FrameLayout(this).apply {
            tag = "k:naslovnica"
            isFocusable = true; isClickable = true
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setStroke(dp(2), getColor(R.color.os_mint)) })
                addState(intArrayOf(), GradientDrawable().apply { setColor(0) })
            }
            nextFocusLeftId = meniMediji.id
            setOnClickListener {
                if (GlasbaStoritev.trenutna() != null) startActivity(Intent(this@GlasbaActivity, PredvajanjeActivity::class.java))
                else predvajaj(listOf(prikaz), 0)
            }
            addView(slika, FrameLayout.LayoutParams(-1, -1))
        }
        // Naslovnica je visoka kot stolpec z besedilom in tipkami ob njej (kvadrat): cim vecja,
        // plosca pa zaradi nje ne zraste - prostor za vrste spodaj ostane (izmerjeno 21. 9. 2026).
        okvir.addOnLayoutChangeListener { v, _, t, _, b, _, _, _, _ ->
            val h = b - t
            if (h > 0 && v.layoutParams.width != h) v.post { v.layoutParams = LinearLayout.LayoutParams(h, -1); v.requestLayout() }
        }
        telo.addView(okvir, LinearLayout.LayoutParams(dp(104), -1))
        val desno = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        desno.addView(besedilo(11f, getColor(R.color.os_mint), true).apply { text = oznaka.uppercase(Locale.getDefault()); letterSpacing = 0.08f })
        pIzvajalec = besedilo(if (jeSirokTv()) 15f else 14f, getColor(R.color.os_umirjeno)).also { desno.addView(it) }
        pNaslov = besedilo(if (jeSirokTv()) 24f else 21f, beli, true).also { desno.addView(it) }
        pVir = besedilo(if (jeSirokTv()) 14f else 13f, getColor(R.color.os_mint)).also { desno.addView(it) }
        if (pZaPredvajanje == true) {
            val potek = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(4)) }
            pPotek = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; progressTintList = ColorStateList.valueOf(getColor(R.color.os_mint)) }.also { potek.addView(it, LinearLayout.LayoutParams(0, dp(5), 1f)) }
            pCas = besedilo(12f, getColor(R.color.os_umirjeno)).apply { setPadding(dp(10), 0, 0, 0) }.also { potek.addView(it) }
            desno.addView(potek)
            val tipke = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val pl = GlasbaStoritev.predvajalnik
            if (!prikaz.radio) tipke.addView(gumb(R.drawable.os_ikona_nakljucno, 36, "k:nakljucno") {
                pl?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }; osveziZdaj() }.also { pNakljucno = it.getChildAt(0) as ImageView })
            tipke.addView(gumb(R.drawable.os_ikona_prejsnja, 36, "k:prejsnja") { pl?.seekToPreviousMediaItem() })
            tipke.addView(gumb(R.drawable.os_ikona_predvajaj, 48, "k:predvajaj") { pl?.let { if (it.isPlaying) it.pause() else it.play() }; osveziZdaj() }
                .also { pPredvajaj = it.getChildAt(0) as ImageView })
            tipke.addView(gumb(R.drawable.os_ikona_naslednja, 36, "k:naslednja") { pl?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } })
            if (!prikaz.radio) tipke.addView(gumb(R.drawable.os_ikona_ponavljaj, 36, "k:ponavljaj") {
                pl?.let { it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } }
                osveziZdaj() }.also { pPonavljaj = it.getChildAt(0) as ImageView })
            if (MedijskiViri.shranljiva(prikaz)) tipke.addView(gumb(R.drawable.os_ikona_srce, 36, "k:srce") {
                GlasbaStoritev.trenutna()?.let { t ->
                    val da = MedijskiViri.preklopiPriljubljeno(this, t)
                    Toast.makeText(this, if (da) R.string.os_mediji_dodano_prilj else R.string.os_mediji_odstranjeno_prilj, Toast.LENGTH_SHORT).show()
                    izberi(DOMOV)
                } }.also { pSrce = it.getChildAt(0) as ImageView })
            (tipke.getChildAt(0))?.nextFocusLeftId = meniMediji.id
            desno.addView(tipke)
        } else {
            desno.addView(LinearLayout(this).apply {
                tag = "k:nadaljuj"
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                isFocusable = true; isClickable = true; nextFocusLeftId = meniMediji.id
                setBackgroundResource(R.drawable.os_kartica_steklo)
                setPadding(dp(14), dp(8), dp(18), dp(8))
                setOnClickListener { predvajaj(listOf(prikaz), 0) }
                addView(ikona(R.drawable.os_ikona_predvajaj, 26, getColor(R.color.os_mint)))
                addView(besedilo(15f, beli, true).apply { text = getString(R.string.os_media_nadaljuj); setPadding(dp(10), 0, 0, 0) })
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        }
        telo.addView(desno, LinearLayout.LayoutParams(0, -2, 1f))
        plosca.addView(telo)
        if (ozko) {
            vrsta.addView(plosca, LinearLayout.LayoutParams(-1, -2))
            if (pZaPredvajanje == true) vrsta.addView(hitraDejanja(prikaz), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        } else {
            // Plosca doloci visino vrste, hitra dejanja se ji prilagodijo.
            vrsta.addView(plosca, LinearLayout.LayoutParams(0, -2, if (jeSirokTv()) 2.35f else 2f))
            if (pZaPredvajanje == true) vrsta.addView(hitraDejanja(prikaz), LinearLayout.LayoutParams(0, -1, if (jeSirokTv()) 0.9f else 1f).apply { marginStart = dp(12) })
        }
        osveziPlosco(prikaz)
        return vrsta
    }

    /** Samo dejanja, ki delujejo: zatemnitev, hitrost, casovnik izklopa, ustavi (celozaslonsko je klik na naslovnico). */
    private fun hitraDejanja(sk: Jamendo.Skladba): View {
        val beli = getColor(R.color.os_besedilo)
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(getColor(R.color.os_kartica_steklo)); setStroke(dp(1), getColor(R.color.os_kartica_obroba)) }
            setPadding(dp(12), dp(if (jeSirokTv()) 8 else 10), dp(12), dp(8))
        }
        v.addView(besedilo(15f, beli, true).apply { text = getString(R.string.os_media_hitra); setPadding(dp(6), 0, 0, dp(4)) })
        fun dejanje(kljuc: String, res: Int, ime: String, klik: () -> Unit): TextView {
            val t = besedilo(13f, beli).apply { text = ime; setPadding(dp(10), 0, 0, 0) }
            v.addView(LinearLayout(this).apply {
                tag = kljuc
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                isFocusable = true; isClickable = true
                setBackgroundResource(R.drawable.os_meni_postavka)
                setPadding(dp(8), dp(if (jeSirokTv()) 4 else 5), dp(8), dp(if (jeSirokTv()) 4 else 5))
                setOnClickListener { klik() }
                addView(ikona(res, 18, beli)); addView(t)
            })
            return t
        }
        fun predvajanje(extra: String?) = startActivity(Intent(this, PredvajanjeActivity::class.java).apply { if (extra != null) putExtra(extra, true) })
        if (!sk.video) dejanje("k:zatemni", R.drawable.os_ikona_luna, getString(R.string.os_media_zatemni)) { predvajanje(PredvajanjeActivity.ZATEMNI) }
        if (!sk.radio) hHitrost = dejanje("k:hitrost", R.drawable.os_ikona_hitrost, "") {
            GlasbaStoritev.predvajalnik?.let { p ->
                val zdaj = p.playbackParameters.speed
                p.setPlaybackSpeed(HITROSTI.firstOrNull { it > zdaj + 0.01f } ?: HITROSTI.first())
            }
            osveziZdaj()
        }
        hCasovnik = dejanje("k:casovnik", R.drawable.os_ikona_casovnik, "") {
            val ostalo = GlasbaStoritev.casovnikMinut()
            GlasbaStoritev.nastaviCasovnik(CASOVNIK.firstOrNull { it > ostalo } ?: 0)
            osveziZdaj()
        }
        dejanje("k:ustavi", R.drawable.os_ikona_ustavi, getString(R.string.os_media_ustavi)) { GlasbaStoritev.ustavi(this); glavna.postDelayed({ if (razdelek == DOMOV) izberi(DOMOV) }, 300) }
        return v
    }

    /** Osvezi besedila, potek in stanja tipk na plosci (vsako sekundo in ob spremembi). */
    private fun osveziPlosco(zadnja: Jamendo.Skladba? = null) {
        val sk = GlasbaStoritev.trenutna() ?: zadnja ?: MedijskiViri.nedavno(this).firstOrNull() ?: return
        val p = GlasbaStoritev.predvajalnik
        pNaslov?.text = sk.naslov
        pIzvajalec?.text = sk.izvajalec
        pVir?.text = when {
            sk.radio -> getString(R.string.os_media_v_zivo)
            sk.video && sk.streznik.isNotBlank() -> "PeerTube · ${sk.streznik}"
            sk.povezava.contains("jamen") -> getString(R.string.os_glasba_vir, sk.povezava.removePrefix("https://").removePrefix("http://"))
            else -> ""
        }
        val slika = pSlika
        if (slika != null && sk.slika != pSlikaNaslov) { pSlikaNaslov = sk.slika; naloziSliko(sk.slika, slika) }
        if (p == null || pZaPredvajanje != true) return
        val trajanje = p.duration.takeIf { it > 0 } ?: 0L
        val polozaj = p.currentPosition.coerceAtLeast(0)
        pPotek?.progress = if (trajanje > 0) (polozaj * 1000 / trajanje).toInt() else 0
        pCas?.text = if (trajanje > 0) "${cas(polozaj)} / ${cas(trajanje)}" else cas(polozaj)
        pPredvajaj?.setImageResource(if (p.isPlaying) R.drawable.os_ikona_pavza else R.drawable.os_ikona_predvajaj)
        val mint = getColor(R.color.os_mint); val beli = getColor(R.color.os_besedilo)
        pNakljucno?.imageTintList = ColorStateList.valueOf(if (p.shuffleModeEnabled) mint else beli)
        pPonavljaj?.imageTintList = ColorStateList.valueOf(if (p.repeatMode != Player.REPEAT_MODE_OFF) mint else beli)
        pSrce?.imageTintList = ColorStateList.valueOf(if (MedijskiViri.jePriljubljena(this, sk)) 0xFFFF6B7A.toInt() else beli)
        hHitrost?.text = getString(R.string.os_media_hitrost, String.format(Locale.ROOT, "%s×", p.playbackParameters.speed.toString().removeSuffix(".0")))
        hCasovnik?.text = getString(R.string.os_media_casovnik,
            GlasbaStoritev.casovnikMinut().let { if (it > 0) getString(R.string.os_media_min, it) else getString(R.string.os_media_izklopljen) })
    }

    private fun cas(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /** Vir medijev: vgrajen (ta naprava, Safeer Link, postaje, PeerTube) ali dodan ([dodan]). */
    private class Vir(val kljuc: String, val ikona: Int, val barva: Int, val ime: String, val opis: String,
                      val odpri: () -> Unit, val dodan: MedijskiViri.Vir? = null)

    /** Vsi viri na enem mestu (Moji viri); na plosci so tisti, ki jih uporabnik pripne. */
    private fun vsiViri(): List<Vir> {
        val datoteke = { startActivity(Intent(this, DatotekeActivity::class.java)) }
        val televizor = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        fun razdelek(i: Int): () -> Unit = { fokusVVsebino = true; izberi(i) }
        return listOf(
            Vir("tv", if (televizor) R.drawable.os_ikona_zaslon else R.drawable.os_ikona_naprava, getColor(R.color.os_mint),
                getString(if (televizor) R.string.os_media_ta_tv else R.string.os_media_ta_naprava), getString(R.string.os_media_ta_tv_opis), datoteke),
            Vir("link", R.drawable.os_ikona_racunalnik, 0xFF8FA8FF.toInt(), getString(R.string.os_media_link), getString(R.string.os_media_link_opis), datoteke),
            Vir("radio", R.drawable.os_ikona_radio, 0xFFFF9580.toInt(), getString(R.string.os_mediji_postaje),
                getString(R.string.os_media_stevilo_prilj, MedijskiViri.priljubljene(this).count { it.radio }), razdelek(RADIO)),
            Vir("peertube", R.drawable.os_ikona_video, 0xFFFF9580.toInt(), "PeerTube",
                getString(R.string.os_media_stevilo_streznikov, MedijskiViri.streznikiPeerTube(this).size), razdelek(VIDEO)),
        ) + MedijskiViri.vsi(this).map { v ->
            Vir(MedijskiViri.kljucPripetega(v), when { v.jePeerTube -> R.drawable.os_ikona_video; v.jeSplet -> R.drawable.os_ikona_splet; else -> R.drawable.os_ikona_glasba },
                0xFF7FB2FF.toInt(), v.ime, if (v.jePeerTube) "PeerTube · ${v.naslov}" else v.naslov.removePrefix("https://").removePrefix("http://"), {
                    when {
                        v.jePeerTube -> { fokusVVsebino = true; izberi(VIDEO) }
                        // Spletna stran: odpre jo brskalnik Safeer, ki predvaja vse (z vgrajenim Scitom).
                        v.jeSplet -> odpriStran(v.naslov, v.ime)
                        else -> predvajaj(listOf(MedijskiViri.kotSkladba(v)), 0)
                    }
                }, v)
        }
    }

    private fun cip(kljuc: String, res: Int, barva: Int, ime: String, opis: String, klik: () -> Unit) = LinearLayout(this).apply {
        tag = kljuc
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        isFocusable = true; isClickable = true
        setBackgroundResource(R.drawable.os_kartica_steklo)
        setPadding(dp(12), dp(7), dp(14), dp(7))
        setOnClickListener { klik() }
        addView(ikona(res, 24, barva))
        val t = LinearLayout(this@GlasbaActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        t.addView(besedilo(13f, getColor(R.color.os_besedilo), true).apply { text = ime })
        t.addView(besedilo(11f, getColor(R.color.os_umirjeno)).apply { text = opis })
        addView(t)
    }

    /**
     * Tvoji viri na plosci - kot priljubljene aplikacije na domacem zaslonu: samo pripeti, v uporabnikovem
     * redu. Kar ne gre celo na zaslon, je pod zadnjo kartico "Vsi viri" - nobena kartica ni prerezana.
     */
    private fun tvojiViri(): View {
        val vsi = vsiViri().associateBy { it.kljuc }
        val pripeti = MedijskiViri.pripeti(this).mapNotNull { vsi[it] }
        val vrsta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun lp() = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) }
        for (v in pripeti) vrsta.addView(cip("k:v:" + v.kljuc, v.ikona, v.barva, v.ime, v.opis, v.odpri).apply {
            setOnLongClickListener { moznostiPripetega(v); true } }, lp())
        val vec = cip(KLJUC_VSI_VIRI, R.drawable.os_ikona_mreza, getColor(R.color.os_besedilo),
            getString(if (pripeti.isEmpty()) R.string.os_media_izberi_vire else R.string.os_media_vsi_viri),
            getString(R.string.os_mediji_viri)) { fokusVVsebino = true; izberi(VIRI) }
        vec.visibility = if (pripeti.isEmpty()) View.VISIBLE else View.GONE
        vrsta.addView(vec, lp())
        vrsta.getChildAt(0)?.nextFocusLeftId = meniMediji.id
        val okvir = HorizontalScrollView(this).apply {
            addView(vrsta); isHorizontalScrollBarEnabled = false
            setPadding(dp(4), dp(4), dp(4), dp(4)); clipToPadding = false
        }
        // Po postavitvi izmerimo: zadnje kartice, ki ne gredo cele na zaslon, se umaknejo pod "Vsi viri".
        okvir.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                okvir.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val sirina = okvir.width - okvir.paddingLeft - okvir.paddingRight
                if (sirina <= 0 || pripeti.isEmpty()) return
                val prosto = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                fun w(v: View) = v.run { measure(prosto, prosto); measuredWidth + dp(10) }
                val kartice = (0 until vrsta.childCount).map { vrsta.getChildAt(it) }.filter { it !== vec }
                var skupaj = kartice.sumOf { w(it) }
                if (skupaj <= sirina) return
                vec.visibility = View.VISIBLE
                var skritih = 0
                for (k in kartice.asReversed()) {
                    if (skupaj + w(vec) <= sirina) break
                    skupaj -= w(k); k.visibility = View.GONE; skritih++
                }
                ((vec as LinearLayout).getChildAt(1) as LinearLayout).getChildAt(1).let { (it as TextView).text = "+$skritih" }
            }
        })
        return okvir
    }

    /** Vidni viri na plosci, v redu z zaslona (brez "Vsi viri"). */
    private fun vidniPripeti(): List<String> = vsebina.findViewWithTag<View>(KLJUC_VSI_VIRI)?.parent.let { it as? LinearLayout }?.let { v ->
        (0 until v.childCount).map { v.getChildAt(it) }.filter { it.visibility == View.VISIBLE && it.tag != KLJUC_VSI_VIRI }
            .mapNotNull { (it.tag as? String)?.removePrefix("k:v:") }
    } ?: emptyList()

    /**
     * Zadrzan OK na viru na plosci: Premakni (puscici levo/desno ga neseta po vrsti, OK konca) ali umakni
     * s plosce - enako kot priljubljena aplikacija na domacem zaslonu. Na dotik (tablica) premikamo po mestu.
     */
    private fun moznostiPripetega(v: Vir) {
        val vidni = vidniPripeti()
        val mesto = vidni.indexOf(v.kljuc)
        val daljinec = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        val dejanja = ArrayList<Pair<String, () -> Unit>>()
        if (daljinec && vidni.size > 1) dejanja.add(getString(R.string.os_premakni) to { zacniPremik(v.kljuc) })
        if (!daljinec && mesto > 0) dejanja.add(getString(R.string.os_spletne_levo) to { premakniPripetega(v.kljuc, -1) })
        if (!daljinec && mesto in 0 until vidni.size - 1) dejanja.add(getString(R.string.os_spletne_desno) to { premakniPripetega(v.kljuc, 1) })
        dejanja.add(getString(R.string.os_media_s_plosce) to {
            val sosed = vidni.getOrNull(mesto + 1) ?: vidni.getOrNull(mesto - 1)
            MedijskiViri.preklopiPripet(this, v.kljuc)
            izberi(DOMOV)
            drsnik.post { vsebina.findViewWithTag<View>(if (sosed != null) "k:v:$sosed" else KLJUC_VSI_VIRI)?.requestFocus() }
            Unit
        })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(v.ime)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    /** Vir, ki ga uporabnik ta trenutek premika po plosci (null = ne premika). */
    private var premikam: String? = null
    /** Tipka, ki je premikanje koncala: njen dvig ne sme se odpreti vira. */
    private var pogoltniGor = -1

    private fun zacniPremik(kljuc: String) {
        premikam = kljuc
        drsnik.post { oznaciPremik() }
    }

    private fun oznaciPremik() {
        val v = vsebina.findViewWithTag<View>("k:v:" + (premikam ?: return)) ?: return
        v.requestFocus()
        v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(120).start()
        // Kot pri priljubljenih aplikacijah: puscici ob imenu povesta, da se kartica premika.
        (((v as? LinearLayout)?.getChildAt(1) as? LinearLayout)?.getChildAt(0) as? TextView)?.let { it.text = "◀  " + it.text + "  ▶" }
        stanje.text = getString(R.string.os_premakni_namig)
    }

    /** Premik samo med vidnimi viri: vir ne sme izginiti pod "Vsi viri". */
    private fun premakniPripetega(kljuc: String, zamik: Int) {
        val vidni = vidniPripeti()
        if (vidni.indexOf(kljuc) + zamik !in vidni.indices) return
        if (!MedijskiViri.premakniPripet(this, kljuc, zamik)) return
        izberi(DOMOV)
        drsnik.post { if (premikam != null) oznaciPremik() else vsebina.findViewWithTag<View>("k:v:$kljuc")?.requestFocus() }
    }

    private fun koncajPremik() {
        premikam = null
        izberi(DOMOV)
    }

    /** Med premikanjem gredo tipke samo premikanju: levo/desno premakne, vse ostalo konca. */
    override fun dispatchKeyEvent(dogodek: KeyEvent): Boolean {
        val k = premikam
        if (k != null) {
            if (dogodek.action == KeyEvent.ACTION_DOWN) when (dogodek.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> premakniPripetega(k, -1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> premakniPripetega(k, 1)
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { }
                else -> { pogoltniGor = dogodek.keyCode; koncajPremik() }
            }
            return true
        }
        if (dogodek.action == KeyEvent.ACTION_UP && dogodek.keyCode == pogoltniGor) { pogoltniGor = -1; return true }
        return super.dispatchKeyEvent(dogodek)
    }

    /**
     * Moji viri: dodaj vir in vsi viri. Zadrzan OK da vir na plosco ali ga umakne - takoj, brez okna
     * (zvezdica in utrip povesta dovolj), kot pri aplikacijah. Dodan vir ima se "Izbrisi", zato tam izbira.
     */
    private fun viriVrste(): List<Vrsta> {
        val pripeti = MedijskiViri.pripeti(this)
        return listOf(Vrsta("", listOf(
            Kartica(getString(R.string.os_mediji_dodaj), getString(R.string.os_mediji_dodaj_opis), "", { dodajVir() }, ikona = R.drawable.os_ikona_plus)) +
            vsiViri().map { v -> Kartica((if (v.kljuc in pripeti) "★ " else "") + v.ime, v.opis, "", { v.odpri() }, { dolgoNaViru(v) }, ikona = v.ikona) },
            mreza = true))
    }

    private fun dolgoNaViru(v: Vir) {
        val preklopi = {
            MedijskiViri.preklopiPripet(this, v.kljuc)
            izberi(VIRI)
            drsnik.post {
                window.decorView.findFocus()?.let { f ->
                    f.animate().scaleX(1.12f).scaleY(1.12f).setDuration(110).withEndAction { f.animate().scaleX(1f).scaleY(1f).setDuration(140).start() }.start()
                }
            }
            Unit
        }
        val dodan = v.dodan ?: return preklopi()
        val na = v.kljuc in MedijskiViri.pripeti(this)
        val dejanja = listOf(getString(if (na) R.string.os_media_s_plosce else R.string.os_media_na_plosco) to preklopi,
            getString(R.string.os_media_izbrisi_vir) to { odstraniVir(dodan) })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(v.ime)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    private fun izmenicno(seznami: List<List<Jamendo.Skladba>>) =
        (0 until (seznami.maxOfOrNull { it.size } ?: 0)).flatMap { i -> seznami.mapNotNull { it.getOrNull(i) } }

    /** Kartice skladb vrste; zadrzan OK odpre meni (priljubljeno, shrani vrsto kot seznam). */
    private fun skladbe(s: List<Jamendo.Skladba>, vrsta: String = "", seznam: MedijskiViri.Seznam? = null) = s.map { sk ->
        if (sk.mime == MedijskiViri.STRAN) Kartica(sk.naslov, sk.izvajalec, "", { odpriStran(sk.zvok, sk.naslov) }, { meni(sk, s, vrsta, seznam) }, ikona = R.drawable.os_ikona_splet)
        else Kartica(sk.naslov, sk.izvajalec, sk.slika, { s.filterNot { it.mime == MedijskiViri.STRAN }.let { p -> predvajaj(p, p.indexOf(sk)) } },
            { meni(sk, s, vrsta, seznam) })
    }

    /** Spletno stran odpre brskalnik Safeer (predvaja vse); nasa glasba se ustavi, zvok strani ob Domov igra naprej. */
    private fun odpriStran(url: String, ime: String) {
        GlasbaStoritev.predvajalnik?.pause()
        startActivity(Brskalnik.medijskaStran(this, url, ime))
    }

    private fun videi(v: List<Jamendo.Skladba>, vrsta: String = "", seznam: MedijskiViri.Seznam? = null) = v.map { sk ->
        Kartica(sk.naslov, sk.izvajalec, sk.slika, { predvajaj(listOf(sk), 0) }, { meni(sk, v, vrsta, seznam) }) }

    private fun meniNedavno(sk: Jamendo.Skladba) {
        AlertDialog.Builder(this).setTitle(sk.naslov)
            .setItems(arrayOf(getString(R.string.os_media_odstrani_nedavno))) { _, _ ->
                MedijskiViri.odstraniNedavno(this, sk)
                SEZNAMI.remove(DOMOV)
                izberi(DOMOV)
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun potrdiPocistiNedavno() {
        AlertDialog.Builder(this).setTitle(R.string.os_media_pocisti_nedavno)
            .setMessage(R.string.os_media_pocisti_nedavno_potrdi)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                MedijskiViri.pocistiNedavno(this)
                SEZNAMI.remove(DOMOV)
                izberi(DOMOV)
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    // ------------------------------------------------------------------ priljubljene

    private fun meni(sk: Jamendo.Skladba, vrsta: List<Jamendo.Skladba>, ime: String, seznam: MedijskiViri.Seznam?) {
        val dejanja = mutableListOf<Pair<String, () -> Unit>>()
        if (MedijskiViri.shranljiva(sk)) {
            val je = MedijskiViri.jePriljubljena(this, sk)
            dejanja += getString(if (je) R.string.os_mediji_odstrani_prilj else R.string.os_mediji_dodaj_prilj) to {
                val zdaj = MedijskiViri.preklopiPriljubljeno(this, sk)
                Toast.makeText(this, if (zdaj) R.string.os_mediji_dodano_prilj else R.string.os_mediji_odstranjeno_prilj, Toast.LENGTH_SHORT).show()
                osveziPriljubljene()
            }
        }
        if (seznam != null) dejanja += getString(R.string.os_mediji_odstrani_seznam) to {
            MedijskiViri.odstraniSeznam(this, seznam.ime); osveziPriljubljene()
        } else if (vrsta.count { MedijskiViri.shranljiva(it) } > 1) dejanja += getString(R.string.os_mediji_shrani_seznam) to {
            val sz = MedijskiViri.shraniSeznam(this, ime.ifBlank { sk.izvajalec.ifBlank { getString(R.string.os_mediji_moja_vrsta) } }, vrsta)
            if (sz != null) Toast.makeText(this, getString(R.string.os_mediji_seznam_shranjen, sz.ime), Toast.LENGTH_SHORT).show()
            osveziPriljubljene()
        }
        if (dejanja.isEmpty()) return
        AlertDialog.Builder(this).setTitle(sk.naslov)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, k -> dejanja[k].second() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    /** Priljubljene so na vrhu plosce in razdelkov Glasba, Radio in Video - tam jih narisemo znova. */
    private fun osveziPriljubljene() { if (razdelek != VIRI && razdelek != ISKANJE) izberi(razdelek) }

    // ------------------------------------------------------------------ iskanje

    /** Zadnji zadetki in beseda: ob vrnitvi na Iskanje so se tam. */
    private var zadetki: List<Vrsta>? = null
    private var zadnjaBeseda = ""
    private var opisZadetkov = ""

    private fun novIskalnik() = EditText(this).apply {
        id = View.generateViewId()
        hint = getString(R.string.os_glasba_isci_namig)
        setText(zadnjaBeseda)
        setTextColor(getColor(R.color.os_besedilo)); setHintTextColor(getColor(R.color.os_umirjeno))
        setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH; inputType = InputType.TYPE_CLASS_TEXT
        setBackgroundResource(R.drawable.os_iskanje)
        setPadding(dp(20), dp(10), dp(20), dp(10))
        tag = "k:iskalno_polje"
        isFocusable = true
        isFocusableInTouchMode = true
        nextFocusLeftId = meniMediji.id
        // Na TV-ju fokus in odpiranje tipkovnice nista ista stvar: D-pad lahko mirno
        // prečka polje, OK/Enter pa odpre tipkovnico. DOWN jo zapre in gre na zadetke.
        showSoftInputOnFocus = false
        setSelection(text.length)
        setOnClickListener { prikaziTipkovnico(this) }
        setOnEditorActionListener { _, a, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (a == EditorInfo.IME_ACTION_SEARCH || a == EditorInfo.IME_ACTION_DONE || enter) {
                val q = text.toString().trim()
                if (q.length >= 2) isci(q)
                true
            } else false
        }
        setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    prikaziTipkovnico(this); true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    skrijTipkovnico(this)
                    fokusNaPrvo()
                    true
                }
                else -> false
            }
        }
    }

    /** Pred iskanjem: glasovno iskanje in nedavna iskanja. */
    private fun predIskanjem() = listOf(
        Vrsta(getString(R.string.os_mediji_nedavna), listOf(
            Kartica(getString(R.string.os_mediji_glasovno), getString(R.string.os_mediji_glasovno_opis), "", { glasovno() }, ikona = R.drawable.os_ikona_mikrofon)) +
            MedijskiViri.iskanja(this).map { b -> Kartica(b, getString(R.string.os_glasba_iskanje), "", { odpriIskanje(b) }, ikona = R.drawable.os_ikona_isci) }))

    /** Odpre razdelek Iskanje; z besedo takoj isce, sicer pripravi polje za tipkanje. */
    private fun odpriIskanje(beseda: String) {
        if (razdelek != ISKANJE) izberi(ISKANJE)
        val polje = iskalnik ?: return
        if (beseda.isNotBlank()) { polje.setText(beseda); isci(beseda); return }
        polje.requestFocus()
        polje.post { prikaziTipkovnico(polje) }
    }

    private fun prikaziTipkovnico(polje: EditText) {
        polje.showSoftInputOnFocus = true
        polje.requestFocus()
        polje.setSelection(polje.text.length)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(polje, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun skrijTipkovnico(polje: EditText) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(polje.windowToken, 0)
        polje.showSoftInputOnFocus = false
    }

    private var glasZacetek = 0L

    private fun glasovno() {
        val namen = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.os_mediji_glasovno_opis))
        glasZacetek = System.currentTimeMillis()
        try { @Suppress("DEPRECATION") startActivityForResult(namen, GLAS) }
        catch (_: Exception) { Toast.makeText(this, R.string.os_mediji_ni_glasovnega, Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != GLAS) return
        // Takojsnja zavrnitev pomeni, da glasovni vnos na tej napravi ne dela (npr. daljinec brez mikrofona).
        if (resultCode != RESULT_OK) {
            if (System.currentTimeMillis() - glasZacetek < 1_500) Toast.makeText(this, R.string.os_mediji_ni_glasovnega, Toast.LENGTH_LONG).show()
            return
        }
        data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { odpriIskanje(it) }
    }

    /**
     * Isce hkrati po vseh virih - videi (PeerTube), izvajalci (Jamendo), radijske postaje in moji
     * viri - in pokaze vse naenkrat; fokus gre na prvi zadetek, da lahko takoj izberes.
     */
    private fun isci(beseda: String) {
        if (beseda.length < 2) return
        iskalnik?.let { skrijTipkovnico(it) }
        MedijskiViri.zapomniIskanje(this, beseda)
        zadnjaBeseda = beseda
        val moje = ++nalaganje
        stanje.text = getString(R.string.os_glasba_nalagam)
        val strezniki = MedijskiViri.streznikiPeerTube(this)
        val mali = beseda.lowercase()
        val viri = MedijskiViri.vsi(this).filter { it.ime.lowercase().contains(mali) || it.naslov.lowercase().contains(mali) }
        // Preklic starih poizvedb: hitro zaporedno iskanje ne sme pustiti kupa zivih niti.
        synchronized(iskanjeNiti) { iskanjeNiti.forEach { it.cancel(true) }; iskanjeNiti.clear() }
        val rezultati = arrayOfNulls<Any>(4)
        val opravila = listOf<() -> Any>(
            { try { Jamendo.isciSkladbe(beseda) } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
            { try { PeerTube.isci(strezniki, beseda) } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
            { try { Radio.isci(beseda) } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
            { try { Jamendo.isciIzvajalce(beseda) } catch (_: Exception) { emptyList<Jamendo.Izvajalec>() } }
        )
        val futures = opravila.mapIndexed { i, f -> iskanjeDelavec.submit { rezultati[i] = f() } }
        synchronized(iskanjeNiti) { iskanjeNiti.addAll(futures) }
        Thread {
            // TV ne sme 25 s delovati zamrznjeno zaradi enega pocasnega vira.
            val rok = System.currentTimeMillis() + 12_000
            futures.forEach { f ->
                val ostanek = rok - System.currentTimeMillis()
                if (ostanek > 0) try { f.get(ostanek, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) { f.cancel(true) }
                else f.cancel(true)
            }
            @Suppress("UNCHECKED_CAST") val glasba = rezultati[0] as? List<Jamendo.Skladba> ?: emptyList()
            @Suppress("UNCHECKED_CAST") val videi = rezultati[1] as? List<Jamendo.Skladba> ?: emptyList()
            @Suppress("UNCHECKED_CAST") val postaje = rezultati[2] as? List<Jamendo.Skladba> ?: emptyList()
            @Suppress("UNCHECKED_CAST") val izvajalci = rezultati[3] as? List<Jamendo.Izvajalec> ?: emptyList()
            glavna.post {
                if (moje != nalaganje || isFinishing) return@post
                val splet = Vrsta(getString(R.string.os_media_na_spletu), listOf(Kartica(getString(R.string.os_media_isci_splet, beseda),
                    getString(R.string.os_media_isci_splet_opis), "", { odpriSplet(beseda) }, ikona = R.drawable.os_ikona_splet)))
                val nicNasli = glasba.isEmpty() && videi.isEmpty() && izvajalci.isEmpty() && postaje.isEmpty() && viri.isEmpty()
                val vrste = listOfNotNull(
                    splet.takeIf { nicNasli },
                    Vrsta(getString(R.string.os_mediji_glasba), skladbe(glasba, beseda)),
                    Vrsta(getString(R.string.os_glasba_video), videi(videi, beseda), video = true),
                    Vrsta(getString(R.string.os_mediji_izvajalci), izvajalci.map { iz ->
                        Kartica(iz.ime, getString(R.string.os_glasba_izvajalec), iz.slika, { odpriIzvajalca(iz) }) }),
                    Vrsta(getString(R.string.os_mediji_postaje), skladbe(postaje, beseda)),
                    Vrsta(getString(R.string.os_mediji_viri), skladbe(viri.map { MedijskiViri.kotSkladba(it) }) +
                        MedijskiViri.vsi(this).filter { it.jeSplet }.map { vir ->
                            Kartica(vir.ime, getString(R.string.os_media_isci_v_viru, beseda), "", {
                                odpriIskanjeVViru(vir, beseda)
                            }, ikona = R.drawable.os_ikona_splet)
                        }),
                    splet.takeUnless { nicNasli },
                )
                zadetki = vrste
                opisZadetkov = getString(R.string.os_mediji_zadetki, videi.size, izvajalci.size, postaje.size, viri.size)
                if (razdelek != ISKANJE) return@post
                narisi(vrste, opisZadetkov)
                fokusNaPrvo()
            }
        }.start()
    }

    /** Uporabnikov spletni vir ostane "dodaj in pozabi": pri globalnem iskanju ponudimo
     * omejeno iskanje znotraj njegove domene. S tem ne ugibamo zasebnih API-jev strani. */
    private fun odpriIskanjeVViru(vir: MedijskiViri.Vir, beseda: String) {
        val host = try { java.net.URL(vir.naslov).host } catch (_: Exception) { return }
        val q = "site:$host $beseda"
        GlasbaStoritev.predvajalnik?.pause()
        startActivity(Brskalnik.medijskaStran(this,
            "https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8"), vir.ime))
    }

    /** Iskanje na spletu: Google, zavihek Videoposnetki, v brskalniku Safeer. */
    private fun odpriSplet(beseda: String) {
        GlasbaStoritev.predvajalnik?.pause()
        try {
            startActivity(Brskalnik.izMedijev(Brskalnik.namera(this)).setAction(Intent.ACTION_VIEW)
                .setData(android.net.Uri.parse("https://www.google.com/search?tbm=vid&q=" + java.net.URLEncoder.encode(beseda, "UTF-8"))))
        } catch (_: Exception) { Toast.makeText(this, R.string.os_odpri_ni_aplikacije, Toast.LENGTH_SHORT).show() }
    }

    /** Fokus na prvo kartico prve vrste (za iskalnim poljem). */
    private fun fokusNaPrvo() {
        for (i in 0 until vsebina.childCount) {
            val v = vsebina.getChildAt(i)
            val vrsta = (if (v.tag == MREZA_VRSTA) v else (v as? HorizontalScrollView)?.getChildAt(0)) as? LinearLayout ?: continue
            vrsta.getChildAt(0)?.requestFocus()
            return
        }
    }

    private fun odpriIzvajalca(iz: Jamendo.Izvajalec) {
        stanje.text = getString(R.string.os_glasba_nalagam)
        delavec.execute {
            val s = try { Jamendo.odIzvajalca(iz.id) } catch (_: Exception) { null }
            glavna.post {
                if (isFinishing) return@post
                if (s == null) { stanje.text = getString(R.string.os_glasba_napaka); return@post }
                narisi(listOf(Vrsta(iz.ime, skladbe(s, iz.ime))), getString(R.string.os_glasba_od_izvajalca, iz.ime))
                fokusNaPrvo()
            }
        }
    }

    // ------------------------------------------------------------------ viri

    private fun dodajVir() {
        val polje = EditText(this).apply {
            hint = getString(R.string.os_mediji_dodaj_namig); setSingleLine(); inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.os_mediji_dodaj)
            .setMessage(R.string.os_mediji_dodaj_razlaga)
            .setView(FrameLayout(this).apply { setPadding(dp(20), 0, dp(20), 0); addView(polje) })
            .setPositiveButton(R.string.os_mediji_dodaj_gumb) { _, _ ->
                val vnos = polje.text.toString()
                if (vnos.isBlank()) return@setPositiveButton
                stanje.text = getString(R.string.os_mediji_preverjam)
                delavec.execute {
                    val vir = MedijskiViri.dodaj(this, vnos)
                    glavna.post {
                        if (isFinishing) return@post
                        if (vir == null) { stanje.text = getString(R.string.os_mediji_ni_vira); return@post }
                        Toast.makeText(this, getString(R.string.os_mediji_dodano, vir.ime), Toast.LENGTH_SHORT).show()
                        SEZNAMI.remove(DOMOV); SEZNAMI.remove(VIDEO)
                        izberi(VIRI)
                        // Fokus na pravkar dodani vir: takoj ga lahko odpres.
                        drsnik.post { vsebina.findViewWithTag<View>("k:#${vsiViri().size}")?.requestFocus() }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun odstraniVir(v: MedijskiViri.Vir) {
        AlertDialog.Builder(this)
            .setTitle(v.ime)
            .setMessage(R.string.os_mediji_odstrani_vprasanje)
            .setPositiveButton(R.string.os_mediji_odstrani) { _, _ ->
                MedijskiViri.odstrani(this, v)
                SEZNAMI.remove(DOMOV); SEZNAMI.remove(VIDEO)
                izberi(VIRI)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ predvajanje

    /** Glasba in radio zacneta takoj (ves seznam v vrsto, naprej/nazaj preklaplja); video najprej razresimo. */
    private fun predvajaj(seznam: List<Jamendo.Skladba>, i: Int) {
        val sk = seznam.getOrNull(i) ?: return
        if (!sk.video || sk.zvok.isNotBlank()) {
            GlasbaStoritev.predvajaj(this, seznam, i)
            if (sk.video) startActivity(Intent(this, PredvajanjeActivity::class.java))
            return
        }
        stanje.text = getString(R.string.os_glasba_nalagam)
        delavec.execute {
            val r = try { PeerTube.razresi(sk, MedijskiViri.streznikiPeerTube(this)) } catch (_: Exception) { null }
            glavna.post {
                if (isFinishing) return@post
                if (r == null) { stanje.text = getString(R.string.os_glasba_napaka); return@post }
                stanje.text = if (razdelek == ISKANJE && zadetki != null) opisZadetkov else opis(razdelek)
                GlasbaStoritev.predvajaj(this, listOf(r), 0)
                startActivity(Intent(this, PredvajanjeActivity::class.java))
            }
        }
    }

    private fun osveziZdaj() {
        val sk = GlasbaStoritev.trenutna()
        val p = GlasbaStoritev.predvajalnik
        if (razdelek == DOMOV) {
            // Plosca se zamenja, ko se predvajanje zacne ali konca; sicer samo osvezimo njene podatke.
            val zeli = if (sk != null && p != null) true else if (MedijskiViri.nedavno(this).isNotEmpty()) false else null
            if (zeli != pZaPredvajanje) izberi(DOMOV) else osveziPlosco()
        }
        vrstica.visibility = if (sk == null || p == null || razdelek == DOMOV) View.GONE else View.VISIBLE
        if (sk == null || p == null) return
        zdajNaslov.text = sk.naslov
        zdajIzvajalec.text = sk.izvajalec
        zdajCas.text = if (p.isPlaying) "▶" else "❚❚"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val p = GlasbaStoritev.predvajalnik
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { p?.let { if (it.isPlaying) it.pause() else it.play() }; return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p?.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p?.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (p?.hasNextMediaItem() == true) p.seekToNextMediaItem(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { p?.seekToPreviousMediaItem(); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { GlasbaStoritev.ustavi(this); return true }
            KeyEvent.KEYCODE_SEARCH -> { odpriIskanje(""); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        /** Beseda za iskanje ob odprtju (prazna: samo odpri iskanje), npr. z zaslona predvajanja. */
        const val ISKANJE_BESEDA = "iskanje"

        /** Safeer Media je odprt (pod predvajalnikom); sicer ga Nazaj v predvajalniku odpre. */
        @Volatile var odprta = false
            private set

        private const val DOMOV = 0; private const val GLASBA = 2; private const val RADIO = 3
        private const val VIDEO = 4; private const val VIRI = 6; private const val ISKANJE = 7
        private const val GLAS = 41
        private const val NASLOV_VRSTE = "naslov-vrste"
        private const val MREZA_VRSTA = "mreza-vrsta"
        /** Kartica v mrezi Mojih virov: dve vrsti gresta na prvi zaslon televizorja. */
        private const val MREZA_DP = 116
        private const val KLJUC_GLASBA = "k:kat:glasba"; private const val KLJUC_VIDEO = "k:kat:video"
        private const val KLJUC_RADIO = "k:kat:radio"; private const val KLJUC_VIRI = "k:kat:viri"
        private const val KLJUC_VSI_VIRI = "k:v:vsi"
        private val HITROSTI = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        private val CASOVNIK = intArrayOf(15, 30, 60, 90)

        /** Seznami razdelkov za cas delovanja aplikacije (ponovna izbira je takojsnja). */
        private val SEZNAMI = HashMap<Int, List<Podatki>>()
        private val SLIKE = LruCache<String, android.graphics.Bitmap>(48)
    }
}
