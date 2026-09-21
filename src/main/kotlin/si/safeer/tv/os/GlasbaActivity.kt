package si.safeer.tv.os

import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import si.safeer.tv.R
import java.util.concurrent.Executors

/**
 * Mediji: glasba in video brez oglasov, urejeno kot pri priljubljenih odjemalcih za televizor -
 * levo razdelki, desno vrste s karticami, ki jih drsis z daljincem.
 *
 * Viri: Jamendo (neodvisni izvajalci, najbolj poslusano najprej), internetni radio (Radio Browser,
 * najprej domace postaje), PeerTube (vgrajeni in uporabnikovi strezniki), uporabnikovi tokovi in
 * datoteke na televizorju in napravah v Safeer Linku (zaslon Datoteke). Predvaja [GlasbaStoritev],
 * zato vse igra tudi v ozadju; [PredvajanjeActivity] pokaze, kaj se predvaja.
 *
 * Seznami se hranijo za cas delovanja aplikacije: ponovna izbira razdelka je takojsnja in ne
 * odvisna od tega, ali Jamendo tisti hip odgovori.
 */
class GlasbaActivity : OsActivity() {

    private data class Vrsta(val naslov: String, val kartice: List<Kartica>, val video: Boolean = false)
    /** Podatki vrste brez zaslona - samo to gre v predpomnilnik (kartice drzijo zaslon). */
    private data class Podatki(val naslov: String, val skladbe: List<Jamendo.Skladba>, val video: Boolean = false, val naprave: Boolean = false)
    private data class Kartica(val naslov: String, val podnaslov: String, val slika: String, val klik: (View) -> Unit,
                               val dolgo: ((View) -> Unit)? = null, val ikona: Int = R.drawable.os_ikona_glasba)

    private val delavec = Executors.newFixedThreadPool(4)
    private val glavna = Handler(Looper.getMainLooper())

    private lateinit var razdelki: List<TextView>
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
    private val poslusalec: () -> Unit = { glavna.post { osveziZdaj() } }
    private val tik = object : Runnable { override fun run() { osveziZdaj(); glavna.postDelayed(this, 1_000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(zgradi())
        izberi(DOMOV)
        razdelki[DOMOV].requestFocus()
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
        GlasbaStoritev.poslusalci.add(poslusalec)
        glavna.post(tik)
    }

    override fun onStop() {
        GlasbaStoritev.poslusalci.remove(poslusalec)
        glavna.removeCallbacks(tik)
        super.onStop()
    }

    override fun onDestroy() { delavec.shutdownNow(); super.onDestroy() }

    // ------------------------------------------------------------------ postavitev

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun besedilo(vel: Float, barva: Int, krepko: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, vel); setTextColor(barva); maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        if (krepko) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    private fun zgradi(): View {
        val beli = getColor(R.color.os_besedilo)
        val koren = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(getColor(R.color.os_ozadje)) }

        // Levo: razdelki
        val meni = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(14), dp(20))
            setBackgroundColor(getColor(R.color.os_meni_ozadje))
        }
        meni.addView(besedilo(22f, beli, true).apply { text = getString(R.string.os_glasba_naslov); setPadding(dp(8), 0, 0, dp(18)) })
        razdelki = listOf(R.string.os_mediji_domov, R.string.os_mediji_priljubljene, R.string.os_mediji_glasba, R.string.os_glasba_radio, R.string.os_glasba_video,
            R.string.os_mediji_naprave, R.string.os_mediji_viri, R.string.os_glasba_iskanje).mapIndexed { i, id ->
            besedilo(16f, beli, true).apply {
                text = getString(id)
                this.id = View.generateViewId()
                setPadding(dp(16), dp(11), dp(16), dp(11))
                isFocusable = true; isClickable = true
                setBackgroundResource(R.drawable.os_meni_postavka)
                setOnClickListener { izberi(i) }
                // Fokus na razdelek ga ze odpre - kot pri odjemalcih za televizor, brez dodatnega OK.
                setOnFocusChangeListener { _, f -> if (f && razdelek != i) izberi(i) }
                meni.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            }
        }
        koren.addView(meni, LinearLayout.LayoutParams(dp(230), -1))

        // Desno: vrste in spodaj zdaj se predvaja
        val desno = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(24), dp(28), dp(16)) }
        stanje = besedilo(14f, getColor(R.color.os_umirjeno))
        desno.addView(stanje)
        vsebina = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        drsnik = ScrollView(this).apply { addView(vsebina); isFillViewport = true; isVerticalScrollBarEnabled = false }
        desno.addView(drsnik, LinearLayout.LayoutParams(-1, 0, 1f))

        vrstica = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
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
        desno.addView(vrstica, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        koren.addView(desno, LinearLayout.LayoutParams(0, -1, 1f))
        return koren
    }

    private fun kartica(k: Kartica, video: Boolean, prva: Boolean): View {
        val sirina = dp(if (video) 240 else 150)
        val visina = if (video) sirina * 9 / 16 else sirina
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(8))
            setBackgroundResource(R.drawable.os_ploscica_app)
            isFocusable = true; isClickable = true
            setOnClickListener { k.klik(it) }
            k.dolgo?.let { d -> setOnLongClickListener { d(it); true } }
            // Iz prve kartice levo nazaj na razdelke.
            if (prva) nextFocusLeftId = razdelki[razdelek].id
            val slika = ImageView(this@GlasbaActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(getColor(R.color.os_kartica))
                setImageResource(k.ikona)
            }
            addView(slika, LinearLayout.LayoutParams(sirina, visina))
            addView(besedilo(14f, getColor(R.color.os_besedilo), true).apply { text = k.naslov; setPadding(dp(2), dp(8), 0, 0) },
                LinearLayout.LayoutParams(sirina, -2))
            addView(besedilo(12f, getColor(R.color.os_umirjeno)).apply { text = k.podnaslov; setPadding(dp(2), 0, 0, 0) },
                LinearLayout.LayoutParams(sirina, -2))
            naloziSliko(k.slika, slika)
        }
    }

    private fun narisi(vrste: List<Vrsta>, opis: String, prazno: String = getString(R.string.os_glasba_prazno)) {
        // Fokus iz vsebine, ki jo bomo zamenjali, na razdelek - sicer pade na prvi razdelek in ga odpre.
        if (vsebina.hasFocus()) razdelki[razdelek].requestFocus()
        vsebina.removeAllViews()
        stanje.text = if (vrste.all { it.kartice.isEmpty() }) prazno else opis
        iskalnik?.let { vsebina.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
        for (v in vrste) {
            if (v.kartice.isEmpty()) continue
            vsebina.addView(besedilo(18f, getColor(R.color.os_besedilo), true).apply { text = v.naslov; setPadding(dp(4), dp(14), 0, dp(8)) })
            val niz = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            v.kartice.forEachIndexed { i, k ->
                niz.addView(kartica(k, v.video, i == 0), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14) })
            }
            vsebina.addView(HorizontalScrollView(this).apply {
                addView(niz); isHorizontalScrollBarEnabled = false; clipToPadding = false
            })
        }
        drsnik.scrollTo(0, 0)
        // Po zaprtem oknu (Dodaj vir, Odstrani) fokus ne sme ostati nikjer - daljinec bi sicer skocil na Domov.
        drsnik.post { if (window.decorView.findFocus() == null) razdelki[razdelek].requestFocus() }
    }

    // ------------------------------------------------------------------ slike

    private fun naloziSliko(naslov: String, v: ImageView) {
        if (!naslov.startsWith("https://")) return
        SLIKE.get(naslov)?.let { v.setImageBitmap(it); return }
        v.tag = naslov
        delavec.execute {
            val b = Jamendo.bajti(naslov)?.let { VarnaSlika.izBajtov(it, 360) } ?: return@execute
            glavna.post { SLIKE.put(naslov, b); if (v.tag == naslov) v.setImageBitmap(b) }
        }
    }

    // ------------------------------------------------------------------ razdelki

    private fun izberi(i: Int) {
        razdelek = i
        razdelki.forEachIndexed { j, t -> t.setTextColor(getColor(if (j == i) R.color.os_mint else R.color.os_besedilo)) }
        iskalnik = if (i == ISKANJE) novIskalnik() else null
        // Desno z razdelka Iskanje gre naravnost v polje za iskanje.
        razdelki[ISKANJE].nextFocusRightId = iskalnik?.id ?: View.NO_ID
        val moje = ++nalaganje
        val predpomnjeno = SEZNAMI[i]
        if (predpomnjeno != null) { prikazi(i, predpomnjeno); return }
        if (i == VIRI || i == NAPRAVE) { narisi(posebne(i), opis(i)); return }
        if (i == PRILJUBLJENE) { narisi(priljubljeneVrste(), opis(i), getString(R.string.os_mediji_prilj_prazno)); return }
        vsebina.removeAllViews()
        if (i == ISKANJE) { zadetki?.let { narisi(it, opisZadetkov) } ?: narisi(predIskanjem(), getString(R.string.os_glasba_isci_navodilo)); return }
        stanje.text = getString(R.string.os_glasba_nalagam)
        delavec.execute {
            val podatki = try { podatkiRazdelka(i) } catch (_: Exception) { null }
            glavna.post {
                if (moje != nalaganje || isFinishing) return@post
                if (podatki == null) { stanje.text = getString(R.string.os_glasba_napaka); return@post }
                // Prazen odgovor (Jamendo obcasno) ne ostane v predpomnilniku - ob naslednji izbiri poskusimo znova.
                if (podatki.filterNot { it.naprave }.all { it.skladbe.isNotEmpty() }) SEZNAMI[i] = podatki
                prikazi(i, podatki)
            }
        }
    }

    /** Domov ima na vrhu se priljubljene (vedno sveze, ne iz predpomnilnika). */
    private fun prikazi(i: Int, podatki: List<Podatki>) =
        narisi((if (i == DOMOV) priljubljeneVrste() else emptyList()) + vVrste(podatki), opis(i))

    private fun opis(i: Int) = when (i) {
        PRILJUBLJENE -> getString(R.string.os_mediji_priljubljene_opis)
        GLASBA -> getString(R.string.os_glasba_po_priljubljenosti)
        RADIO -> getString(R.string.os_glasba_radiji)
        VIDEO -> getString(R.string.os_glasba_video_opis)
        NAPRAVE -> getString(R.string.os_mediji_naprave_opis)
        VIRI -> getString(R.string.os_mediji_viri_opis)
        else -> getString(R.string.os_glasba_podnaslov)
    }

    /** Podatki razdelka (klic na delovni niti). */
    private fun podatkiRazdelka(i: Int): List<Podatki> = when (i) {
        DOMOV -> {
            val (domace, svet) = Radio.postajeLocene()
            listOfNotNull(
                Podatki(getString(R.string.os_mediji_vrsta_glasba), Jamendo.priljubljene(24)),
                Podatki(getString(R.string.os_mediji_vrsta_radio), (domace + svet).take(24)),
                Podatki(getString(R.string.os_mediji_vrsta_video), izmenicno(MedijskiViri.streznikiPeerTube(this).map { s ->
                    try { PeerTube.najboljGledani(s, 12) } catch (_: Exception) { emptyList() } }), video = true),
                Podatki(getString(R.string.os_mediji_naprave), emptyList(), naprave = true),
                MedijskiViri.vsi(this).filterNot { it.jePeerTube }.takeIf { it.isNotEmpty() }?.let { viri ->
                    Podatki(getString(R.string.os_mediji_viri), viri.map { MedijskiViri.kotSkladba(it) }) },
            )
        }
        GLASBA -> Jamendo.priljubljene(48).chunked(12).mapIndexed { n, del ->
            Podatki(if (n == 0) getString(R.string.os_glasba_po_priljubljenosti) else getString(R.string.os_mediji_se), del) }
        RADIO -> Radio.postajeLocene().let { (domace, svet) ->
            listOf(Podatki(getString(R.string.os_mediji_domace), domace), Podatki(getString(R.string.os_mediji_svet), svet)) }
        VIDEO -> MedijskiViri.streznikiPeerTube(this).map { s ->
            Podatki(s, try { PeerTube.najboljGledani(s, 24) } catch (_: Exception) { emptyList() }, video = true) }
        else -> emptyList()
    }

    private fun vVrste(p: List<Podatki>) = p.map { d ->
        if (d.naprave) Vrsta(d.naslov, napraveKartice())
        else Vrsta(d.naslov, if (d.video) videi(d.skladbe, d.naslov) else skladbe(d.skladbe, d.naslov), d.video)
    }

    /** Razdelka, ki se ne predpomnita (hitra, krajevna). */
    private fun posebne(i: Int): List<Vrsta> = when (i) {
        NAPRAVE -> listOf(Vrsta(getString(R.string.os_mediji_naprave), napraveKartice()))
        VIRI -> listOf(Vrsta(getString(R.string.os_mediji_viri), listOf(
            Kartica(getString(R.string.os_mediji_dodaj), getString(R.string.os_mediji_dodaj_opis), "", { dodajVir() }, ikona = R.drawable.os_ikona_plus)) +
            MedijskiViri.vsi(this).map { v ->
                Kartica(v.ime, if (v.jePeerTube) "PeerTube · ${v.naslov}" else v.naslov.removePrefix("https://").removePrefix("http://"), "",
                    { when {
                        v.jePeerTube -> razdelki[VIDEO].requestFocus()
                        // Spletna stran: odpre jo brskalnik Safeer, ki predvaja vse (z vgrajenim Scitom).
                        v.jeSplet -> odpriStran(v.naslov, v.ime)
                        else -> predvajaj(listOf(MedijskiViri.kotSkladba(v)), 0)
                    } },
                    { odstraniVir(v) },
                    ikona = when { v.jePeerTube -> R.drawable.os_ikona_video; v.jeSplet -> R.drawable.os_ikona_splet; else -> R.drawable.os_ikona_glasba })
            }))
        else -> emptyList()
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

    // ------------------------------------------------------------------ priljubljene

    /** Priljubljene skladbe in postaje, videi in shranjeni seznami (vsak svoja vrsta). */
    private fun priljubljeneVrste(): List<Vrsta> {
        val p = MedijskiViri.priljubljene(this)
        return listOf(
            Vrsta(getString(R.string.os_mediji_prilj_glasba), skladbe(p.filterNot { it.video })),
            Vrsta(getString(R.string.os_mediji_prilj_video), videi(p.filter { it.video }), video = true),
        ) + MedijskiViri.seznami(this).map { sz ->
            val video = sz.skladbe.all { it.video }
            Vrsta("≡  " + sz.ime, if (video) videi(sz.skladbe, sz.ime, sz) else skladbe(sz.skladbe, sz.ime, sz), video)
        }
    }

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

    /** Priljubljene so na zaslonu v razdelkih Domov in Priljubljene - tam jih narisemo znova. */
    private fun osveziPriljubljene() { if (razdelek == DOMOV || razdelek == PRILJUBLJENE) izberi(razdelek) }

    private fun napraveKartice() = listOf(
        Kartica(getString(R.string.os_meni_datoteke), getString(R.string.os_mediji_naprave_kartica), "",
            { startActivity(Intent(this, DatotekeActivity::class.java)) }, ikona = R.drawable.os_ikona_naprava))

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
        nextFocusLeftId = razdelki[ISKANJE].id
        setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_SEARCH || a == EditorInfo.IME_ACTION_DONE) { isci(text.toString().trim()); true } else false
        }
    }

    /** Pred iskanjem: glasovno iskanje in nedavna iskanja. */
    private fun predIskanjem() = listOf(
        Vrsta(getString(R.string.os_mediji_nedavna), listOf(
            Kartica(getString(R.string.os_mediji_glasovno), getString(R.string.os_mediji_glasovno_opis), "", { glasovno() }, ikona = R.drawable.os_ikona_mikrofon)) +
            MedijskiViri.iskanja(this).map { b -> Kartica(b, getString(R.string.os_glasba_iskanje), "", { odpriIskanje(b) }, ikona = R.drawable.os_ikona_isci) }))

    /** Odpre razdelek Iskanje; z besedo takoj isce, sicer pripravi polje za tipkanje. */
    private fun odpriIskanje(beseda: String) {
        if (razdelek != ISKANJE) { razdelki[ISKANJE].requestFocus(); if (razdelek != ISKANJE) izberi(ISKANJE) }
        val polje = iskalnik ?: return
        if (beseda.isNotBlank()) { polje.setText(beseda); isci(beseda); return }
        polje.requestFocus()
        polje.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(polje, 0) }
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
        iskalnik?.let { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0) }
        MedijskiViri.zapomniIskanje(this, beseda)
        zadnjaBeseda = beseda
        val moje = ++nalaganje
        stanje.text = getString(R.string.os_glasba_nalagam)
        val strezniki = MedijskiViri.streznikiPeerTube(this)
        val mali = beseda.lowercase()
        val viri = MedijskiViri.vsi(this).filter { it.ime.lowercase().contains(mali) || it.naslov.lowercase().contains(mali) }
        Thread {
            val iskanja = listOf<() -> List<Jamendo.Skladba>>({ PeerTube.isci(strezniki, beseda) }, { Radio.isci(beseda) })
            var izvajalci = emptyList<Jamendo.Izvajalec>()
            val izid = arrayOfNulls<List<Jamendo.Skladba>>(iskanja.size)
            val niti = iskanja.mapIndexed { i, f -> Thread { izid[i] = try { f() } catch (_: Exception) { null } } } +
                Thread { izvajalci = try { Jamendo.isciIzvajalce(beseda) } catch (_: Exception) { emptyList() } }
            niti.forEach { it.start() }
            niti.forEach { it.join(25_000) }
            val videi = izid[0].orEmpty(); val postaje = izid[1].orEmpty()
            glavna.post {
                if (moje != nalaganje || isFinishing) return@post
                val vrste = listOf(
                    Vrsta(getString(R.string.os_glasba_video), videi(videi, beseda), video = true),
                    Vrsta(getString(R.string.os_mediji_izvajalci), izvajalci.map { iz ->
                        Kartica(iz.ime, getString(R.string.os_glasba_izvajalec), iz.slika, { odpriIzvajalca(iz) }) }),
                    Vrsta(getString(R.string.os_mediji_postaje), skladbe(postaje, beseda)),
                    Vrsta(getString(R.string.os_mediji_viri), skladbe(viri.map { MedijskiViri.kotSkladba(it) })),
                )
                zadetki = vrste
                opisZadetkov = getString(R.string.os_mediji_zadetki, videi.size, izvajalci.size, postaje.size, viri.size)
                if (razdelek != ISKANJE) return@post
                narisi(vrste, opisZadetkov)
                fokusNaPrvo()
            }
        }.start()
    }

    /** Fokus na prvo kartico prve vrste (za iskalnim poljem). */
    private fun fokusNaPrvo() {
        for (i in 0 until vsebina.childCount) {
            val v = vsebina.getChildAt(i) as? HorizontalScrollView ?: continue
            ((v.getChildAt(0) as? LinearLayout)?.getChildAt(0))?.requestFocus()
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
                        drsnik.post { ((vsebina.getChildAt(1) as? HorizontalScrollView)?.getChildAt(0) as? LinearLayout)?.let { it.getChildAt(it.childCount - 1)?.requestFocus() } }
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
        vrstica.visibility = if (sk == null || p == null) View.GONE else View.VISIBLE
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

        private const val DOMOV = 0; private const val PRILJUBLJENE = 1; private const val GLASBA = 2; private const val RADIO = 3
        private const val VIDEO = 4; private const val NAPRAVE = 5; private const val VIRI = 6; private const val ISKANJE = 7
        private const val GLAS = 41

        /** Seznami razdelkov za cas delovanja aplikacije (ponovna izbira je takojsnja). */
        private val SEZNAMI = HashMap<Int, List<Podatki>>()
        private val SLIKE = LruCache<String, android.graphics.Bitmap>(120)
    }
}
