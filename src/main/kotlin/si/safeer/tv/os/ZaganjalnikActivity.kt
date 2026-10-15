package si.safeer.tv.os

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import si.safeer.tv.R
import si.safeer.tv.os.ZaganjalnikPravila.Vnos

/**
 * Zaganjalnik Safeer OS: en domaci zaslon za tablico, telefon in televizor, ki se prilagodi oknu in nacinu
 * upravljanja (lastnik, 15. 10. 2026). Postavitev izbere [ZaganjalnikPravila.izberi] po sirini okna (ozko /
 * srednje / siroko) in vnosu (dotik / daljinec), ne po vrsti naprave; zasuk, razdeljen zaslon in zlozljivi
 * telefoni se prerisejo sproti (configChanges, brez novega zaslona).
 *
 * Zgradba je ista kot na namizju Safeer OS: stranska vrstica ([StranskaVrstica] - Domov, Medijski center,
 * Safeer Link, Programi, Datoteke, Splet, Zapiski, Nastavitve), na domacem zaslonu pa lupa na vrhu,
 * priljubljene aplikacije, kompakten predvajalnik, nedavne datoteke in programi naprav v Safeer Linku.
 * Vsaka vsebina ima en sam vstop: vse aplikacije so v Programih, naprave v Safeer Linku.
 *
 * Domaci zaslon (HOME) je izbira uporabnika: alias [Zaganjalnik] je privzeto onemogocen, ob prvem zagonu
 * ponudimo sistemsko okno vloge, pot nazaj je v Nastavitvah. Ce zaganjalnik pade dvakrat v minuti, se domaci
 * zaslon vrne prejsnjemu zaganjalniku ([Zaganjalnik.namestiZascito]).
 */
class ZaganjalnikActivity : OsActivity(), LinkOdjemalec.Poslusalec {

    private val link by lazy { LinkUpravitelj.pridobi(this) }
    private val glavna = Handler(Looper.getMainLooper())
    private lateinit var postavitev: ZaganjalnikPravila.Postavitev
    private var vnos = Vnos.DOTIK

    private var drsnik: ScrollView? = null
    private var polje: EditText? = null
    private var stanjeBesedilo: TextView? = null
    private var stanjePika: View? = null
    private var odsekPriljubljene: LinearLayout? = null
    private var odsekPredvajalnik: LinearLayout? = null
    private var odsekDatoteke: LinearLayout? = null
    private var odsekNaprave: LinearLayout? = null
    private var ponudba: View? = null

    /** Priljubljene aplikacije te naprave, nalozene v ozadju (ime, ikona, zagon). */
    private var priljubljene: List<Aplikacije.Vnos> = emptyList()
    private var nalagamPriljubljene = false
    private var zacetek = 0L

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (DomacaLupa.naRezultat(this, requestCode, resultCode, data)) return
        if (requestCode == Zaganjalnik.ZAHTEVA_VLOGE) {
            // Uporabnik je v sistemskem oknu rekel ne: alias spet onemogocimo, da Android Safeer OS ne ponuja sam.
            if (!Zaganjalnik.jeIzbran(this)) Zaganjalnik.opusti(this)
            prefs().edit().putBoolean(KLJUC_PONUJENO, true).apply()
            narisiPonudbo()
            return
        }
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        zacetek = SystemClock.uptimeMillis()
        Zaganjalnik.namestiZascito(this)
        if (intent?.hasCategory(Intent.CATEGORY_HOME) == true) Zaganjalnik.zabeleziZagonDomov(this)
        Predgretje.zazeni(this)
        super.onCreate(savedInstanceState)
        zgradi()
        // Safeer Link (sredisce in sprejemnik ukazov drugih naprav) naj tece, kadar ga je uporabnik vklopil.
        try { si.safeer.tv.cast.HubStoritev.zagotovi(this) } catch (_: Throwable) { }
        zazeniSprejemnik()
        if (!Naprava.jeTelevizor(this)) si.safeer.tv.link.Obvestila.zaprosiEnkrat(this)
        if (Zaganjalnik.vrnjenPoPadcu(this)) pokaziVrnjenPoPadcu()
        window.decorView.post {
            Log.i(TAG, "domaci zaslon narisan v ${SystemClock.uptimeMillis() - zacetek} ms (${postavitev.sirina}, ${postavitev.vnos})")
            reportFullyDrawn()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Tipka Domov na domacem zaslonu: na vrh, brez odprte tipkovnice (kot drugi zaganjalniki).
        if (intent?.hasCategory(Intent.CATEGORY_HOME) == true) {
            Zaganjalnik.zabeleziZagonDomov(this)
            polje?.clearFocus()
            drsnik?.smoothScrollTo(0, 0)
        }
    }

    override fun onConfigurationChanged(nova: Configuration) {
        super.onConfigurationChanged(nova)
        val t = SystemClock.uptimeMillis()
        val besedilo = polje?.text?.toString().orEmpty()
        zgradi()
        if (besedilo.isNotEmpty()) polje?.setText(besedilo)
        osveziVse()
        window.decorView.post {
            Log.i(TAG, "preris ob spremembi okna ${nova.screenWidthDp}x${nova.screenHeightDp} dp: ${SystemClock.uptimeMillis() - t} ms (${postavitev.sirina})")
        }
    }

    override fun onStart() {
        super.onStart()
        link.dodaj(this)
        GlasbaStoritev.poslusalci.add(medijiPoslusalec)
        ZnackeObvestil.poslusalci.add(znackePoslusalec)
        osveziVse()
        AplikacijeHostaActivity.predhodno(this)
    }

    override fun onStop() {
        GlasbaStoritev.poslusalci.remove(medijiPoslusalec)
        ZnackeObvestil.poslusalci.remove(znackePoslusalec)
        link.odstrani(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Iz Programov se je lahko spremenil seznam priljubljenih: preberemo znova (hitro, iz shrambe).
        naloziPriljubljene()
        NamestiApk.nadaljujCeCaka(this)
    }

    /** Domaci zaslon ne odide z Nazaj (kot drugi zaganjalniki); kot navadna aplikacija gre v ozadje. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if ((drsnik?.scrollY ?: 0) > 0) { drsnik?.smoothScrollTo(0, 0); return }
        if (Zaganjalnik.jeIzbran(this)) return
        moveTaskToBack(true)
    }

    private fun osveziVse() {
        pokaziStanje()
        narisiPriljubljene()
        narisiPredvajalnik()
        narisiDatoteke()
        narisiNaprave()
        narisiPonudbo()
    }

    // ------------------------------------------------------------------ postavitev

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
    private fun dp(v: Int) = dp(v.toFloat())
    private fun sp(v: Float) = v * postavitev.merilo

    private fun zaznajVnos(): Vnos {
        val c = resources.configuration
        return if (Naprava.jeTelevizor(this) || c.touchscreen == Configuration.TOUCHSCREEN_NOTOUCH) Vnos.DALJINEC else Vnos.DOTIK
    }

    private fun zgradi() {
        vnos = zaznajVnos()
        val c = resources.configuration
        postavitev = ZaganjalnikPravila.izberi(c.screenWidthDp, c.screenHeightDp, vnos)
        val rob = dp(if (postavitev.sirina == ZaganjalnikPravila.Sirina.OZKA) 16 else 28)

        val seznam = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(rob, dp(20), rob, dp(32))
        }
        seznam.addView(glava())
        ponudba = null
        odsekPriljubljene = odsek(getString(R.string.os_odsek_priljubljene))
        odsekPredvajalnik = odsek(getString(R.string.zg_predvaja))
        odsekDatoteke = odsek(getString(R.string.zg_nedavne_datoteke))
        odsekNaprave = odsek(getString(R.string.zg_na_napravah))
        seznam.addView(LinearLayout(this).apply { tag = "ponudba"; orientation = LinearLayout.VERTICAL })
        seznam.addView(odsekPriljubljene)
        // Mreza odsekov: telefon en stolpec, tablica pokonci dva, lezece trije (kot namizje); televizor vrste.
        val spodnji = listOf(odsekPredvajalnik!!, odsekDatoteke!!, odsekNaprave!!)
        val stolpci = postavitev.stolpci
        if (stolpci <= 1) spodnji.forEach { seznam.addView(it) }
        else spodnji.chunked(stolpci).forEach { vrsta ->
            val v = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; baselineAligned = false }
            vrsta.forEachIndexed { i, o ->
                v.addView(o, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(20)
                })
            }
            // Prazen prostor, da zadnja vrsta ohrani sirino stolpcev.
            repeat(stolpci - vrsta.size) { v.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(20) }) }
            seznam.addView(v)
        }

        drsnik = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(seznam, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val okvir = FrameLayout(this).apply {
            id = R.id.koren
            addView(drsnik, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(StranskaVrstica.ovij(this, okvir, StranskaVrstica.Razdelek.DOMOV))
        Ozadje.uporabi(this, okvir)
    }

    private fun glava(): View {
        val vrsta = LinearLayout(this).apply {
            orientation = if (postavitev.sirina == ZaganjalnikPravila.Sirina.OZKA) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Lupa (DomacaLupa): napises ali poves - »Sintel na TV«, spletni naslov ali iskanje v Medijskem centru.
        val iskanje = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.os_iskanje)
            setPadding(dp(14), 0, dp(10), 0)
            minimumHeight = dp(if (vnos == Vnos.DALJINEC) 56 else 48)
        }
        iskanje.addView(ImageView(this).apply {
            setImageResource(R.drawable.os_ikona_isci)
            imageTintList = ColorStateList.valueOf(osBarva(R.color.os_umirjeno))
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        val vnosPolje = EditText(this).apply {
            setSingleLine()
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setTextColor(osBarva(R.color.os_besedilo))
            setHintTextColor(osBarva(R.color.os_umirjeno))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(16f))
            setPadding(dp(10), dp(10), dp(4), dp(10))
            // Televizor: fokus polja tipkovnice ne odpre; OK jo odpre (kot drugje v Safeer OS).
            if (vnos == Vnos.DALJINEC) {
                showSoftInputOnFocus = false
                setOnKeyListener { v, koda, d ->
                    if (d.action == KeyEvent.ACTION_DOWN && (koda == KeyEvent.KEYCODE_DPAD_CENTER || koda == KeyEvent.KEYCODE_ENTER)) {
                        (v as EditText).showSoftInputOnFocus = true
                        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(v, 0)
                        true
                    } else false
                }
            }
        }
        iskanje.addView(vnosPolje, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        DomacaLupa.prevzemi(this, vnosPolje, iskanje) { odpriSplet() }
        polje = vnosPolje

        // Stanje Safeer Linka: samo prikaz (vstop v Safeer Link je v stranski vrstici - en vstop na vsebino).
        val stanje = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(osBarva(R.color.os_kartica)) }
        }
        val pika = View(this).apply { setBackgroundResource(R.drawable.os_pika) }
        stanje.addView(pika, LinearLayout.LayoutParams(dp(8), dp(8)))
        val besedilo = TextView(this).apply {
            setTextColor(osBarva(R.color.os_umirjeno)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(12f))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(8), 0, 0, 0)
        }
        stanje.addView(besedilo)
        stanjePika = pika; stanjeBesedilo = besedilo

        if (vrsta.orientation == LinearLayout.VERTICAL) {
            vrsta.addView(iskanje, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            vrsta.addView(stanje, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10); gravity = Gravity.START })
        } else {
            vrsta.addView(iskanje, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            vrsta.addView(stanje, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(16) })
        }
        return vrsta
    }

    /** Odsek: naslov + vsebina (otrok 1). Prazen odsek je skrit (nimamo praznih ikon in praznih kartic). */
    private fun odsek(naslov: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(24), 0, 0)
        addView(TextView(this@ZaganjalnikActivity).apply {
            text = naslov.uppercase(java.util.Locale.getDefault())
            setTextColor(osBarva(R.color.os_mint))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(12f))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.08f
            setPadding(dp(2), 0, 0, dp(10))
        })
        addView(LinearLayout(this@ZaganjalnikActivity).apply { orientation = LinearLayout.VERTICAL })
        visibility = View.GONE
    }

    private fun vsebinaOdseka(o: LinearLayout?): LinearLayout? = o?.getChildAt(1) as? LinearLayout

    /** Sirina vsebine v dp (okno brez stranske vrstice in robov) - za stevilo ploscic v vrsti. */
    private fun sirinaVsebineDp(): Int {
        val c = resources.configuration
        val vrstica = if (postavitev.vrstica == ZaganjalnikPravila.Vrstica.Z_IMENI && vnos == Vnos.DOTIK) 180 else 68
        val rob = if (postavitev.sirina == ZaganjalnikPravila.Sirina.OZKA) 32 else 56
        return (c.screenWidthDp - vrstica - rob).coerceAtLeast(200)
    }

    // ------------------------------------------------------------------ ploscice

    private fun ploscica(ime: String, ikona: Drawable?, znacka: Int, opis: String? = null, ob: (View) -> Unit, dolgo: (() -> Unit)?): View {
        val sirina = dp(postavitev.ploscicaDp)
        val okvir = FrameLayout(this).apply {
            isFocusable = true; isClickable = true
            setBackgroundResource(R.drawable.os_ploscica_app)
            contentDescription = if (znacka > 0) getString(R.string.zg_znacka_opis, ime, znacka) else ime
            setOnClickListener { ob(it) }
            if (dolgo != null) setOnLongClickListener { dolgo(); true }
        }
        val stolpec = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(10), dp(6), dp(8))
        }
        val velikost = dp(if (vnos == Vnos.DALJINEC) 52 else 44)
        stolpec.addView(ImageView(this).apply {
            setImageDrawable(ikona ?: getDrawable(R.drawable.os_ikona_aplikacije))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(velikost, velikost))
        stolpec.addView(TextView(this).apply {
            text = ime; gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setTextColor(osBarva(R.color.os_besedilo)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(11f))
            setPadding(0, dp(6), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (opis != null) stolpec.addView(TextView(this).apply {
            text = opis; gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setTextColor(osBarva(R.color.os_umirjeno)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(9.5f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        okvir.addView(stolpec, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val besediloZnacke = ZaganjalnikPravila.besediloZnacke(znacka)
        if (besediloZnacke.isNotEmpty()) okvir.addView(TextView(this).apply {
            text = besediloZnacke; gravity = Gravity.CENTER
            setTextColor(osBarva(R.color.os_ozadje)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            minWidth = dp(18); setPadding(dp(4), 0, dp(4), 0)
            background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(osBarva(R.color.os_mint)) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(18), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(4); marginEnd = dp(4) })
        okvir.layoutParams = ViewGroup.MarginLayoutParams(sirina, ViewGroup.LayoutParams.WRAP_CONTENT)
        return okvir
    }

    /**
     * Ploscice v vrste: na dotik mreza (toliko v vrsti, kolikor jih gre v sirino), na televizorju ena vodoravna
     * vrsta, po kateri se premikas levo/desno.
     */
    private fun razporedi(cilj: LinearLayout, ploscice: List<View>, sirinaDp: Int) {
        cilj.removeAllViews()
        if (postavitev.vrste) {
            val vrsta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipToPadding = false; setPadding(dp(4), dp(4), dp(4), dp(4)) }
            ploscice.forEachIndexed { i, p -> vrsta.addView(p, LinearLayout.LayoutParams(dp(postavitev.ploscicaDp), ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(14) }) }
            cilj.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false; clipToPadding = false
                addView(vrsta)
            })
            return
        }
        val naVrsto = ZaganjalnikPravila.ploscicVVrsti(sirinaDp, postavitev.ploscicaDp)
        for (kos in ploscice.chunked(naVrsto)) {
            val vrsta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (p in kos) {
                val celica = FrameLayout(this)
                celica.addView(p, FrameLayout.LayoutParams(dp(postavitev.ploscicaDp), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
                vrsta.addView(celica, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            repeat(naVrsto - kos.size) { vrsta.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f)) }
            cilj.addView(vrsta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
    }

    // ------------------------------------------------------------------ priljubljene aplikacije

    private fun naloziPriljubljene() {
        if (nalagamPriljubljene) return
        nalagamPriljubljene = true
        val app = applicationContext
        Thread({
            val paketi = Priljubljene.seznam(app)
            val najdene = paketi.distinct().mapNotNull { p -> try { Aplikacije.ena(app, p) } catch (_: Throwable) { null } }
            // Ikone oblikujemo tu (zaobljena ploscica), ne na glavni niti.
            najdene.forEach { try { Aplikacije.ikona(app, it) } catch (_: Throwable) { } }
            glavna.post {
                nalagamPriljubljene = false
                if (isFinishing || isDestroyed) return@post
                priljubljene = najdene
                narisiPriljubljene()
            }
        }, "SafeerPriljubljene").start()
    }

    private fun narisiPriljubljene() {
        val o = odsekPriljubljene ?: return
        val cilj = vsebinaOdseka(o) ?: return
        val ploscice = ArrayList<View>()
        for (a in priljubljene) {
            ploscice.add(ploscica(a.ime, Aplikacije.ikona(this, a), ZnackeObvestil.koliko(a.paket),
                ob = { v -> odpri(a.namera, a.ime, v) }, dolgo = { moznostiPriljubljene(a) }))
        }
        for (s in SpletneAplikacije.seznam(this)) {
            val ime = s.ime.ifBlank { SpletneAplikacije.gostitelj(s.url) }
            ploscice.add(ploscica(ime, SpletneAplikacije.ikona(this, s), 0, ob = { v ->
                Nadaljuj.zapisi(this, Nadaljuj.Vnos(vrsta = Nadaljuj.SPLETNA, ime = ime, url = s.url))
                odpri(Brskalnik.spletnaAplikacija(this, s.url, ime), ime, v)
            }, dolgo = { moznostiSpletne(s) }))
        }
        // Dodajanje: Programi (vse aplikacije) - tam dolg pritisk aplikacijo pripne sem.
        ploscice.add(ploscica(getString(R.string.zg_dodaj), getDrawable(R.drawable.os_ikona_plus)?.mutate()?.apply {
            setTint(osBarva(R.color.os_mint)) }, 0, ob = {
            Toast.makeText(this, R.string.zg_priljubljene_namig, Toast.LENGTH_LONG).show()
            odpri(Intent(this, AplikacijeHostaActivity::class.java).putExtra(AplikacijeHostaActivity.EXTRA_VIR, "vse"), getString(R.string.os_meni_aplikacije), null)
        }, dolgo = null))
        razporedi(cilj, ploscice, sirinaVsebineDp())
        o.visibility = View.VISIBLE
    }

    private fun moznostiPriljubljene(a: Aplikacije.Vnos) {
        val dejanja = listOf(
            getString(R.string.os_spletne_levo) to { if (Priljubljene.premakni(this, a.paket, -1)) naloziPriljubljene() },
            getString(R.string.os_spletne_desno) to { if (Priljubljene.premakni(this, a.paket, 1)) naloziPriljubljene() },
            getString(R.string.os_priljubljen_odstrani) to { Priljubljene.odstrani(this, a.paket); naloziPriljubljene() },
            getString(R.string.zg_podatki_aplikacije) to {
                odpri(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.paket)), a.ime, null)
            },
        )
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(a.ime)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    private fun moznostiSpletne(s: SpletneAplikacije.Aplikacija) {
        val dejanja = listOf(
            getString(R.string.os_spletne_levo) to { SpletneAplikacije.premakni(this, s.url, -1); narisiPriljubljene() },
            getString(R.string.os_spletne_desno) to { SpletneAplikacije.premakni(this, s.url, 1); narisiPriljubljene() },
            getString(R.string.os_spletne_odstrani) to { SpletneAplikacije.odstrani(this, s.url); narisiPriljubljene() },
        )
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(s.ime.ifBlank { SpletneAplikacije.gostitelj(s.url) })
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    private val znackePoslusalec: () -> Unit = { narisiPriljubljene() }

    // ------------------------------------------------------------------ kompakten predvajalnik

    private val medijiPoslusalec: () -> Unit = { runOnUiThread { narisiPredvajalnik() } }

    private fun narisiPredvajalnik() {
        val o = odsekPredvajalnik ?: return
        val cilj = vsebinaOdseka(o) ?: return
        val skladba = try { GlasbaStoritev.trenutna() } catch (_: Throwable) { null }
        cilj.removeAllViews()
        if (skladba == null) { o.visibility = View.GONE; return }
        val igra = try { GlasbaStoritev.igra() } catch (_: Throwable) { false }
        (o.getChildAt(0) as? TextView)?.text = getString(if (igra) R.string.zg_predvaja else R.string.zg_v_premoru).uppercase(java.util.Locale.getDefault())
        val kartica = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.os_ploscica_app)
            setPadding(dp(14), dp(10), dp(8), dp(10))
            isFocusable = true; isClickable = true
            setOnClickListener { odpri(GlasbaStoritev.namenKartice(this@ZaganjalnikActivity), getString(R.string.os_mediji_kartica), it) }
        }
        kartica.addView(ImageView(this).apply {
            setImageResource(if (skladba.radio) R.drawable.os_ikona_radio else R.drawable.os_ikona_glasba)
            imageTintList = ColorStateList.valueOf(osBarva(R.color.os_mint))
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        val besedila = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        besedila.addView(TextView(this).apply {
            text = skladba.naslov; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setTextColor(osBarva(R.color.os_besedilo)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(14f))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        if (skladba.izvajalec.isNotBlank()) besedila.addView(TextView(this).apply {
            text = skladba.izvajalec; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setTextColor(osBarva(R.color.os_umirjeno)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(12f))
        })
        kartica.addView(besedila, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun gumb(ikona: Int, opis: Int, dejanje: () -> Unit) = ImageButton(this).apply {
            setImageResource(ikona); contentDescription = getString(opis)
            imageTintList = ColorStateList.valueOf(osBarva(R.color.os_besedilo))
            setBackgroundResource(R.drawable.os_meni_postavka)
            isFocusable = true
            setOnClickListener { try { dejanje() } catch (_: Throwable) { }; glavna.postDelayed({ narisiPredvajalnik() }, 300) }
        }
        val g = dp(if (vnos == Vnos.DALJINEC) 48 else 44)
        kartica.addView(gumb(R.drawable.os_ikona_prejsnja, R.string.zg_prejsnja) { GlasbaStoritev.prejsnja() }, LinearLayout.LayoutParams(g, g))
        kartica.addView(gumb(if (igra) R.drawable.os_ikona_pavza else R.drawable.os_ikona_predvajaj,
            if (igra) R.string.zg_premor else R.string.zg_predvajaj) { GlasbaStoritev.preklopi() }, LinearLayout.LayoutParams(g, g))
        kartica.addView(gumb(R.drawable.os_ikona_naslednja, R.string.zg_naslednja) { GlasbaStoritev.naslednja() }, LinearLayout.LayoutParams(g, g))
        cilj.addView(kartica, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        o.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ nedavne datoteke

    private fun narisiDatoteke() {
        val o = odsekDatoteke ?: return
        val cilj = vsebinaOdseka(o) ?: return
        cilj.removeAllViews()
        val datoteke = Nadaljuj.seznam(this).filter { it.vrsta in ZaganjalnikPravila.VRSTE_DATOTEK }.take(5)
        if (datoteke.isEmpty()) { o.visibility = View.GONE; return }
        for (n in datoteke) {
            val kje = if (n.krajevno) getString(R.string.os_naprave_ta)
                else link.naprave.firstOrNull { it.id == n.racunalnik }?.ime?.ifBlank { null } ?: getString(R.string.zg_ni_povezana)
            cilj.addView(vrstica(ikonaVrste(n.vrsta), n.ime, kje, ob = {
                NadaljujOdpri.datoteka(this, link, n) { racunalnikaNi ->
                    Toast.makeText(this, if (racunalnikaNi) getString(R.string.os_nadaljuj_ni_racunalnika)
                        else getString(R.string.os_nadaljuj_ni_vec, n.ime), Toast.LENGTH_LONG).show()
                }
            }, dolgo = {
                android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(n.ime)
                    .setItems(arrayOf(getString(R.string.os_nadaljuj_odstrani))) { _, _ -> Nadaljuj.odstrani(this, n); narisiDatoteke() }
                    .setNegativeButton(getString(R.string.os_preklici), null)
                    .let { Kontroler.pokazi(it.show()) }
            }))
        }
        o.visibility = View.VISIBLE
    }

    private fun ikonaVrste(vrsta: String): Int = when (vrsta) {
        Nadaljuj.VIDEO -> R.drawable.os_ikona_video
        Nadaljuj.GLASBA -> R.drawable.os_ikona_glasba
        Nadaljuj.SLIKA -> R.drawable.os_ikona_slika
        else -> R.drawable.os_ikona_datoteka
    }

    private fun vrstica(ikona: Int, ime: String, opis: String, ob: () -> Unit, dolgo: (() -> Unit)? = null): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.os_ploscica_app)
            setPadding(dp(12), dp(if (vnos == Vnos.DALJINEC) 12 else 9), dp(12), dp(if (vnos == Vnos.DALJINEC) 12 else 9))
            isFocusable = true; isClickable = true
            setOnClickListener { ob() }
            if (dolgo != null) setOnLongClickListener { dolgo(); true }
            addView(ImageView(this@ZaganjalnikActivity).apply {
                setImageResource(ikona); imageTintList = ColorStateList.valueOf(osBarva(R.color.os_mint))
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            val b = LinearLayout(this@ZaganjalnikActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            b.addView(TextView(this@ZaganjalnikActivity).apply {
                text = ime; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                setTextColor(osBarva(R.color.os_besedilo)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(13f))
            })
            b.addView(TextView(this@ZaganjalnikActivity).apply {
                text = opis; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setTextColor(osBarva(R.color.os_umirjeno)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(11f))
            })
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }

    // ------------------------------------------------------------------ programi naprav v Safeer Linku

    private fun narisiNaprave() {
        val o = odsekNaprave ?: return
        val cilj = vsebinaOdseka(o) ?: return
        val pripeti = SafeerAppi.priljubljeni(this).filter { it.vir == AppVir.RACUNALNIK }
        val nedavni = Nadaljuj.seznam(this).filter { it.vrsta == Nadaljuj.PROGRAM }
        val pari = ZaganjalnikPravila.programiNaprav(pripeti.map { it.racunalnik to it.cilj }, nedavni.map { it.racunalnik to it.program })
        if (pari.isEmpty()) {
            cilj.removeAllViews()
            // Brez pripetih programov: kratka pot, kako jih dobis - samo, ce naprave s programi res so.
            if (AplikacijeHostaActivity.napraveSProgrami(this).isEmpty()) { o.visibility = View.GONE; return }
            cilj.addView(TextView(this).apply {
                text = getString(R.string.zg_na_napravah_namig)
                setTextColor(osBarva(R.color.os_umirjeno)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(12f))
            })
            o.visibility = View.VISIBLE
            return
        }
        val ploscice = pari.map { (naprava, program) ->
            val p = pripeti.firstOrNull { it.racunalnik == naprava && it.cilj == program }
            val n = nedavni.firstOrNull { it.racunalnik == naprava && it.program == program }
            val ime = p?.ime ?: n?.ime ?: program
            val ikona = p?.let { SafeerAppi.ikona(this, it) } ?: n?.let { Nadaljuj.ikona(this, it) }
            val r = link.naprave.firstOrNull { it.id == naprava }
            val kje = r?.ime?.ifBlank { r.id } ?: getString(R.string.zg_ni_povezana)
            ploscica(ime, ikona, 0, opis = kje, ob = {
                ZagonPrograma.zazeni(this, link, naprava, program, ime, p?.skupina == "igre" || n?.igra == true)
            }, dolgo = {
                android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(ime)
                    .setItems(arrayOf(getString(R.string.os_priljubljen_odstrani))) { _, _ ->
                        if (p != null) SafeerAppi.odstrani(this, p.kljuc)
                        if (n != null) Nadaljuj.odstrani(this, n)
                        narisiNaprave()
                    }
                    .setNegativeButton(getString(R.string.os_preklici), null)
                    .let { Kontroler.pokazi(it.show()) }
            }).apply { alpha = if (r == null) 0.55f else 1f }
        }
        val stolpci = postavitev.stolpci.coerceAtLeast(1)
        razporedi(cilj, ploscice, sirinaVsebineDp() / stolpci)
        o.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ ponudba domacega zaslona

    private fun prefs() = getSharedPreferences("safeer_os", MODE_PRIVATE)

    /**
     * Ob prvem zagonu na dotik: jasna izbira, ali naj bo Safeer OS domaci zaslon. Ena kartica, dva gumba; po
     * odlocitvi je ni vec (izbira ostane v Nastavitvah). Televizor ima svojo pot (Nastavitve, [Zaganjalnik]).
     */
    private fun narisiPonudbo() {
        val mesto = drsnik?.findViewWithTag<LinearLayout>("ponudba") ?: return
        mesto.removeAllViews()
        if (vnos == Vnos.DALJINEC || prefs().getBoolean(KLJUC_PONUJENO, false) || Zaganjalnik.jeIzbran(this)) return
        val kartica = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.os_kartica)
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        kartica.addView(TextView(this).apply {
            text = getString(R.string.zg_ponudba_naslov)
            setTextColor(osBarva(R.color.os_besedilo)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        kartica.addView(TextView(this).apply {
            text = getString(R.string.zg_ponudba_opis)
            setTextColor(osBarva(R.color.os_umirjeno)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(6))
        })
        val gumbi = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun gumb(niz: Int, poudarjen: Boolean, ob: () -> Unit) = TextView(this).apply {
            text = getString(niz); isFocusable = true; isClickable = true
            setTextColor(osBarva(if (poudarjen) R.color.os_mint else R.color.os_umirjeno))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundResource(R.drawable.os_meni_postavka)
            setOnClickListener { ob() }
        }
        gumbi.addView(gumb(R.string.zg_ponudba_ne, false) {
            prefs().edit().putBoolean(KLJUC_PONUJENO, true).apply(); narisiPonudbo()
        })
        gumbi.addView(gumb(R.string.zg_ponudba_da, true) {
            if (!Zaganjalnik.zahtevajVlogo(this)) Toast.makeText(this, R.string.os_zaganjalnik_ni_nastavitev, Toast.LENGTH_LONG).show()
        })
        kartica.addView(gumbi)
        mesto.addView(kartica, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
    }

    private fun pokaziVrnjenPoPadcu() {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.zg_padec_naslov)
            .setMessage(R.string.zg_padec_opis)
            .setPositiveButton(R.string.zg_padec_znova) { _, _ -> Zaganjalnik.zahtevajVlogo(this) }
            .setNegativeButton(android.R.string.ok, null)
            .let { Kontroler.pokazi(it.show()) }
    }

    // ------------------------------------------------------------------ Safeer Link

    private fun zazeniSprejemnik() {
        try {
            if (si.safeer.tv.cast.CastReceiverService.instance != null || !si.safeer.tv.cast.CastReceiverService.isConfigured(this)) return
            si.safeer.tv.cast.CastReceiverService.start(this, null, getString(R.string.os_ime_vrste) + " (" + android.os.Build.MODEL + ")")
        } catch (e: Throwable) {
            Log.w(TAG, "Sprejemnika Linka ni bilo mogoce zagnati: ${e.message}")
        }
    }

    private fun pokaziStanje() {
        val sami = link.povezan && LinkOdjemalec.drugeZaPrikaz(link.naprave, Identiteta.id(this)).isEmpty()
        val druge = LinkOdjemalec.drugeZaPrikaz(link.naprave, Identiteta.id(this)).size
        stanjeBesedilo?.text = when {
            link.povezan && !sami -> getString(R.string.zg_stanje_naprave, druge)
            link.povezan -> getString(R.string.os_stanje_krajevni)
            else -> getString(R.string.os_stanje_ni)
        }
        stanjePika?.apply {
            // Zelena pika: povezan z drugimi napravami; sicer opozorilna barva (rumena pika je le v okusih na dotik).
            backgroundTintList = if (link.povezan && !sami) null else ColorStateList.valueOf(osBarva(R.color.os_opozorilo))
            alpha = if (link.povezan && !sami) 1f else 0.8f
        }
    }

    override fun naStanje(povezan: Boolean, sporocilo: String) {
        pokaziStanje()
        if (!povezan && sporocilo == "ni") slediSredisculu()
    }

    private var iscemSredisce = false

    /**
     * Naprava je seznanjena z oddaljenim srediscem (televizor, racunalnik), ki je dobil nov naslov v omrezju:
     * poiscemo ga po odtisu (mDNS) in se povezemo na novi naslov - brez ponovne seznanitve (prej DomovTabletActivity).
     */
    private fun slediSredisculu() {
        if (iscemSredisce || link.povezan || !Host.jeOddaljen(this)) return
        val p = Host.poverilnice(this) ?: return
        iscemSredisce = true
        si.safeer.tv.tablica.IskanjeHubov.najdi(this) { najdeni ->
            iscemSredisce = false
            if (isFinishing || link.povezan) return@najdi
            val isti = najdeni.firstOrNull { it.odtis.isNotBlank() && it.odtis.equals(p.odtis, ignoreCase = true) } ?: return@najdi
            if (isti.naslov == p.hubUrl) return@najdi
            Host.shrani(this, isti.naslov, p.zeton, p.odtis, p.hubId)
            link.ponovnoPoveziSe()
        }
    }

    override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) {
        pokaziStanje()
        narisiNaprave()
        narisiDatoteke()
        AplikacijeHostaActivity.predhodno(this)
    }

    override fun naNaslov(url: String, naslov: String, od: String) { }
    override fun naBesedilo(besedilo: String, od: String) { }
    override fun naZavrnitev() { }

    // ------------------------------------------------------------------ odpiranje

    private fun odpriSplet() = odpri(Brskalnik.namera(this), getString(R.string.os_splet), null)

    private fun odpri(namera: Intent, ime: String, izvor: View?) {
        try {
            startActivity(namera, izPloscice(izvor))
        } catch (e: Throwable) {
            Toast.makeText(this, getString(R.string.os_odpri_napaka, e.message ?: e.javaClass.simpleName) + " (" + ime + ")", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "SafeerZaganjalnik"
        private const val KLJUC_PONUJENO = "zaganjalnik_ponudba_odgovorjena"
    }
}
