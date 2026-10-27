package si.safeer.tv.os

import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import si.safeer.tv.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Safeer OS Sporocila: e-posta in Chatwoot v enem seznamu pogovorov (en pogovor = ena oseba).
 * Enako kot na racunalniku: gesla so v Android Keystore, sinhronizacija ne oznaci pisem kot prebrana.
 */
class SporocilaActivity : OsActivity() {
    private lateinit var koren: View
    private lateinit var shramba: SporocilaShramba
    private lateinit var kanaliVrsta: LinearLayout
    private lateinit var dodajGumb: View
    private lateinit var seznamPlosca: View
    private lateinit var seznam: LinearLayout
    private lateinit var prazno: TextView
    private lateinit var pogovorPlosca: LinearLayout
    private lateinit var pogovorIme: TextView
    private lateinit var pogovorZadeva: TextView
    private lateinit var nazajGumb: View
    private lateinit var sporocilaDrsnik: ScrollView
    private lateinit var sporocilaSeznam: LinearLayout
    private lateinit var odgovor: EditText
    /** Nacin pisanja odgovora: glava in seznam izgineta, vidni so pogovor, zadnje sporocilo in polje (zgradi). */
    private var uskladiPisanje: () -> Unit = {}
    private lateinit var posljiGumb: View

    private val delavec = Executors.newSingleThreadExecutor()
    private val glavna = Handler(Looper.getMainLooper())
    private var siroko = false
    private var izbran: SporocilaShramba.Pogovor? = null
    private lateinit var filtriVrsta: LinearLayout
    private lateinit var iskanje: EditText
    private lateinit var dejanjaPogovora: LinearLayout
    private var filter = "vse"            // vse | neprebrano | email | klepet | oznaka:<ime>
    private var oznaciSporocilo: String? = null   // zadetek iskanja, ki ga v pogovoru obrobimo
    private var unicena = false
    private val osvezi = object : Runnable {
        override fun run() { sinhroniziraj(); glavna.postDelayed(this, OSVEZI_MS) }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shramba = SporocilaShramba(this)
        siroko = resources.configuration.screenWidthDp >= 720
        setContentView(StranskaVrstica.ovij(this, zgradi(), StranskaVrstica.Razdelek.SPOROCILA))
        narisiKanale(); narisiFiltre(); narisiSeznam(); pokaziPogovor(null)
        odpriIzNamere(intent)
        // Sporocila z drugih naprav v Linku pridejo kot obvestilo: od Androida 13 za to rabimo dovoljenje.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 71) } catch (_: Throwable) { }
        }
        // Z daljincem zacnemo v vsebini: prvi pogovor ali, ce jih se ni, Dodaj kanal.
        koren.post { (if (seznam.childCount > 0) seznam.getChildAt(0) else dodajGumb).requestFocus() }
    }

    override fun onStart() {
        super.onStart()
        Ozadje.uporabi(this, koren)
        glavna.post(osvezi)
        // Povzetek skupine obvestil brez sporocil (uporabnik jih je odmaknil) ne sme ostati sam.
        KlepetLinka.umakniObvestilo(this)
        // Dokler so Sporocila odprta, drzimo povezavo v Link: klepet z drugih naprav pride takoj.
        if (!LinkUpravitelj.pridobi(this).jeKrajevni()) LinkUpravitelj.pridobi(this).dodaj(linkPoslusalec)
        KlepetLinka.poslusalci.add(obKlepetu)
        KlepetLinka.odprtPogovor = izbran?.takeIf { it.kanalId == KlepetLinka.KANAL }?.id
    }

    override fun onStop() {
        glavna.removeCallbacks(osvezi)
        LinkUpravitelj.pridobi(this).odstrani(linkPoslusalec)
        KlepetLinka.poslusalci.remove(obKlepetu)
        KlepetLinka.odprtPogovor = null
        super.onStop()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        odpriIzNamere(intent)
    }

    /** Obvestilo o sporocilu odpre pravi pogovor. */
    private fun odpriIzNamere(namera: android.content.Intent?) {
        // »Novo sporocilo« s ploscice domacega zaslona: izbira naprave, takoj ko Link pove, katere so.
        if (namera?.getBooleanExtra(EXTRA_NOVO, false) == true) {
            namera.removeExtra(EXTRA_NOVO)
            if (napraveZaKlepet().isNotEmpty()) pisiNapravi() else cakaNovo = true
        }
        val id = namera?.getStringExtra(EXTRA_POGOVOR) ?: return
        val kanal = namera.getStringExtra(EXTRA_KANAL) ?: KlepetLinka.KANAL
        shramba.pogovori().firstOrNull { it.kanalId == kanal && it.id == id }?.let { pokaziPogovor(it) }
    }

    /** »Novo sporocilo« caka na seznam naprav iz Linka. */
    private var cakaNovo = false

    private val obKlepetu: () -> Unit = { glavna.post { if (!unicena) osveziPrikaz() } }
    private var napraveLinka: List<LinkOdjemalec.Naprava> = emptyList()
    private val linkPoslusalec = object : LinkOdjemalec.Poslusalec {
        override fun naStanje(povezan: Boolean, sporocilo: String) {}
        override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) {
            napraveLinka = naprave
            if (cakaNovo && napraveZaKlepet().isNotEmpty()) glavna.post { if (cakaNovo && !unicena) { cakaNovo = false; pisiNapravi() } }
            glavna.post {
                if (unicena) return@post
                // Imena in id-ji pogovorov Linka sledijo seznamu naprav (preimenovanje, stari id-ji).
                if (KlepetLinka.uskladi(shramba, naprave)) {
                    izbran = izbran?.let { i -> if (i.kanalId == KlepetLinka.KANAL) shramba.pogovori().firstOrNull { it.kanalId == i.kanalId && (it.id == i.id || it.id == KlepetLinka.odprtPogovor) } else i }
                    osveziPrikaz()
                } else narisiKanale()
            }
        }
        override fun naNaslov(url: String, naslov: String, od: String) {}
        override fun naBesedilo(besedilo: String, od: String) {}
        override fun naZavrnitev() {}
    }

    /** Naprave v Linku, ki znajo Safeer Chat (brez te naprave in njenih sorodnikov). */
    private fun napraveZaKlepet(): List<LinkOdjemalec.Naprava> =
        LinkOdjemalec.drugeZaPrikaz(napraveLinka.filter { it.zmoznosti.contains(KlepetLinka.ZMOZNOST) }, Identiteta.id(this))

    private fun pisiNapravi() {
        val naprave = napraveZaKlepet()
        if (naprave.isEmpty()) { Toast.makeText(this, R.string.os_spor_ni_naprav, Toast.LENGTH_LONG).show(); return }
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.os_spor_pisi_napravi)
            .setItems(naprave.map { it.ime }.toTypedArray()) { _, i ->
                val n = naprave[i]
                pokaziPogovor(KlepetLinka.zacniPogovor(shramba, n.naprava.ifBlank { n.id }, n.ime))
            }
            .setNegativeButton(R.string.os_preklici, null)
            .create()
        Kontroler.pokazi(okno)
        okno.show()
    }

    override fun onDestroy() {
        unicena = true
        delavec.shutdownNow()
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (pisanjeTece) { izstopiIzPisanja(); return }
        if (!siroko && izbran != null) { pokaziPogovor(null); return }
        super.onBackPressed()
    }

    // ------------------------------------------------------------------ zgradba
    private fun zgradi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val rob = if (siroko) 24 else 14
            setPadding(dp(rob), dp(if (siroko) 20 else 14), dp(rob), dp(14))
            setBackgroundColor(osBarva(R.color.os_ozadje))
        }
        koren = root

        val glava = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // Okrasna ikona glave: na ozkem zaslonu jo nadomesti gumb menija stranske vrstice.
        glava.addView(ImageView(this).apply { setImageResource(R.drawable.os_ikona_sporocila); tag = StranskaVrstica.OZNAKA_SIDRO },
            LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) })
        val naslovi = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        naslovi.addView(TextView(this).apply {
            text = getString(R.string.os_meni_sporocila)
            setTextColor(osBarva(R.color.os_besedilo)); textSize = if (siroko) 27f else 22f; maxLines = 1
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        })
        naslovi.addView(TextView(this).apply {
            text = getString(R.string.os_spor_opis)
            setTextColor(osBarva(R.color.os_umirjeno)); textSize = 13f
        })
        glava.addView(naslovi, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dodajGumb = gumb((if (siroko) "" else "+ ") + getString(R.string.os_spor_dodaj)).apply {
            if (!siroko) { textSize = 13f; setPadding(dp(12), dp(9), dp(12), dp(9)) }
            id = View.generateViewId(); setOnClickListener { dodajKanal() }
        }
        glava.addView(dodajGumb)
        root.addView(glava)

        kanaliVrsta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(kanaliVrsta)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14); bottomMargin = dp(12)
        })

        // Iskanje (ime, zadeva, besedilo, oznake) in filtri - vidni gumbi, brez skritih kretenj.
        iskanje = EditText(this).apply {
            hint = getString(R.string.os_spor_isci_namig); isSingleLine = true
            setTextColor(osBarva(R.color.os_besedilo)); setHintTextColor(osBarva(R.color.os_umirjeno)); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(osBarva(R.color.os_kartica_dvignjena)); setStroke(dp(1), osBarva(R.color.os_crta)) }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                override fun afterTextChanged(p0: android.text.Editable?) { narisiSeznam() }
            })
        }
        tipkovnicaSeleNaOk(iskanje)
        iskanje.setOnKeyListener { v, koda, d -> tipkovnicaNaOk(v as EditText, koda, d) }
        root.addView(iskanje, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        filtriVrsta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(filtriVrsta) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

        // Seznam pogovorov
        prazno = TextView(this).apply {
            gravity = Gravity.CENTER; setTextColor(osBarva(R.color.os_umirjeno)); textSize = 16f
            setPadding(dp(12), dp(40), dp(12), dp(40))
        }
        seznam = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        seznamPlosca = ScrollView(this).apply {
            isFillViewport = true
            // Prazen seznam ne sme ujeti fokusa daljinca (nevidna izbira med gumbom in menijem).
            isFocusable = false
            addView(LinearLayout(this@SporocilaActivity).apply { orientation = LinearLayout.VERTICAL; addView(prazno); addView(seznam) })
        }

        // Pogovor
        pogovorPlosca = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(osBarva(R.color.os_kartica_dvignjena)) }
        }
        val pGlava = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nazajGumb = gumb("‹ " + getString(R.string.os_spor_nazaj)).apply {
            id = View.generateViewId(); setOnClickListener { pokaziPogovor(null) }
        }
        pGlava.addView(nazajGumb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) })
        val pNaslovi = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pogovorIme = TextView(this).apply {
            setTextColor(osBarva(R.color.os_besedilo)); textSize = 19f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        pogovorZadeva = TextView(this).apply { setTextColor(osBarva(R.color.os_umirjeno)); textSize = 12f; maxLines = 1 }
        pNaslovi.addView(pogovorIme); pNaslovi.addView(pogovorZadeva)
        pGlava.addView(pNaslovi, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pogovorPlosca.addView(pGlava)
        // Dejanja nad osebo: preimenuj/zdruzi, oznake, iskanje po sporocilih te osebe.
        dejanjaPogovora = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((ime, dejanje) in listOf(R.string.os_spor_oseba to { izbran?.let { urediOsebo(it) } }, R.string.os_spor_oznake to { izbran?.let { urediOznake(it) } },
                                     R.string.os_spor_isci_osebo to { izbran?.let { isciPriOsebi(it) } })) {
            dejanjaPogovora.addView(gumb(getString(ime)).apply { textSize = 12f; setPadding(dp(12), dp(6), dp(12), dp(6)); setOnClickListener { dejanje() } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        pogovorPlosca.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(dejanjaPogovora) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        sporocilaSeznam = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        sporocilaDrsnik = ScrollView(this).apply { isFillViewport = true; addView(sporocilaSeznam) }
        pogovorPlosca.addView(sporocilaDrsnik, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val vnos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        vnosVrstica = vnos
        odgovor = EditText(this).apply {
            id = View.generateViewId()
            hint = getString(R.string.os_spor_odgovor)
            setTextColor(osBarva(R.color.os_besedilo)); setHintTextColor(osBarva(R.color.os_umirjeno)); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 1; maxLines = 5; imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(osBarva(R.color.os_ozadje)); setStroke(dp(1), osBarva(R.color.os_crta))
            }
            setOnKeyListener { v, koda, d ->
                if (koda == KeyEvent.KEYCODE_ENTER && d.isCtrlPressed && d.action == KeyEvent.ACTION_UP) { poslji(); true }
                else if (koda == KeyEvent.KEYCODE_BACK && pisanjeTece) {
                    if (d.action == KeyEvent.ACTION_UP) izstopiIzPisanja()
                    true
                }
                else tipkovnicaNaOk(v as EditText, koda, d)
            }
        }
        tipkovnicaSeleNaOk(odgovor)
        vnos.addView(odgovor, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
        posljiGumb = gumb(getString(R.string.os_spor_poslji)).apply { id = View.generateViewId(); setOnClickListener { poslji() } }
        vnos.addView(posljiGumb)
        pogovorPlosca.addView(vnos, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        odgovor.nextFocusRightId = posljiGumb.id
        posljiGumb.nextFocusLeftId = odgovor.id

        val telo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (siroko) {
            telo.addView(seznamPlosca, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f).apply { marginEnd = dp(14) })
            telo.addView(pogovorPlosca, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f))
            nazajGumb.visibility = View.GONE
        } else {
            telo.addView(seznamPlosca, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            telo.addView(pogovorPlosca, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        root.addView(telo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        pisanjePlosca = zgradiPisanje()
        root.addView(pisanjePlosca, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            val rob = (resources.displayMetrics.widthPixels * 0.08f).toInt()
            marginStart = rob; marginEnd = rob; topMargin = dp(24)
        })
        // Lastnik 27. 10. 2026 (TV, odgovor na sporocilo): z odprto tipkovnico je ostalo ~600 tock, od tega je glava
        // (naslov, kanali, iskanje, filtri) vzela ~430 - pogovor in polje Odgovor nista bila vidna. Dokler je tipkovnica
        // odprta med pisanjem odgovora, glava in seznam pogovorov izgineta in pogovor dobi ves prostor nad tipkovnico.
        val glavaZaslona = (0 until root.childCount).map { root.getChildAt(it) }.filter { it !== telo } +
            (if (siroko) listOf(seznamPlosca) else emptyList()) +
            listOfNotNull(dejanjaPogovora.parent as? View)   // vrstica dejanj pogovora (osebe, oznake, iskanje)
        var skrito: Map<View, Int>? = null   // vidnost pred skritjem: kar je bilo skrito ze prej, ostane skrito
        val tv = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        uskladiPisanje = {
            val vidno = android.graphics.Rect().also { root.getWindowVisibleDisplayFrame(it) }
            // TV: tipkovnica je odprta, ko jo je OK odprl na polju Odgovor (tipkovnica TV vidnega okna ne zmanjsa vedno);
            // telefon in tablica: vidni del okna (adjustResize).
            // Na TV ima pisanje svoj pas (vstopiVPisanje); tu samo telefon in tablica (vidni del okna, adjustResize).
            val pisem = !tv && odgovor.hasFocus() && SporocilaHitroPravila.tipkovnicaOdprta(vidno.height(), root.rootView.height)
            if (pisem && skrito == null) {
                skrito = glavaZaslona.associateWith { it.visibility }
                glavaZaslona.forEach { it.visibility = View.GONE }
                // Zadnje sporocilo, ki mu odgovarjas, ostane vidno tik nad poljem (brez premika fokusa).
                sporocilaDrsnik.post { sporocilaDrsnik.scrollTo(0, (sporocilaSeznam.height - sporocilaDrsnik.height).coerceAtLeast(0)) }
            } else if (!pisem) skrito?.let { prej -> skrito = null; prej.forEach { (v, s) -> v.visibility = s } }
        }
        root.viewTreeObserver.addOnGlobalLayoutListener { uskladiPisanje() }
        return root
    }

    private fun gumb(ime: String) = TextView(this).apply {
        text = ime; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
        setTextColor(osBarva(R.color.os_besedilo)); textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(18), dp(11), dp(18), dp(11)); setBackgroundResource(R.drawable.os_meni_postavka)
    }

    // ------------------------------------------------------------------ risanje
    private fun besediloStanja(stanje: String): String = when (stanje) {
        "povezan" -> ""
        "napaka:geslo" -> getString(R.string.os_spor_napaka_geslo)
        "napaka:prijava" -> getString(R.string.os_spor_napaka_prijava)
        "napaka:omrezje" -> getString(R.string.os_spor_napaka_omrezje)
        "" -> getString(R.string.os_spor_povezujem)
        else -> if (stanje.startsWith("napaka")) getString(R.string.os_spor_napaka) else ""
    }

    private fun narisiKanale() {
        kanaliVrsta.removeAllViews()
        for (k in shramba.kanali()) {
            val napaka = k.stanje.startsWith("napaka")
            val cip = TextView(this).apply {
                text = (if (k.vrsta == "email") "✉ " else "💬 ") + k.ime + (if (napaka) "  ⚠" else "")
                setTextColor(osBarva(if (napaka) R.color.os_opozorilo else R.color.os_besedilo)); textSize = 13f
                isFocusable = true; isClickable = true; maxLines = 1; tag = "kanal|" + k.id
                setPadding(dp(14), dp(8), dp(14), dp(8)); setBackgroundResource(R.drawable.os_meni_postavka)
                contentDescription = k.ime + " " + besediloStanja(k.stanje)
                setOnClickListener { if (k.vrsta == KlepetLinka.VRSTA) pisiNapravi() else urediKanal(k) }
            }
            kanaliVrsta.addView(cip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        // Safeer Link: pisanje drugim napravam, se preden je prislo prvo sporocilo.
        if (shramba.kanali().none { it.id == KlepetLinka.KANAL } && napraveZaKlepet().isNotEmpty()) {
            kanaliVrsta.addView(TextView(this).apply {
                text = "💬 " + getString(R.string.os_spor_pisi_napravi)
                setTextColor(osBarva(R.color.os_mint)); textSize = 13f
                isFocusable = true; isClickable = true; maxLines = 1; tag = "kanal|" + KlepetLinka.KANAL
                setPadding(dp(14), dp(8), dp(14), dp(8)); setBackgroundResource(R.drawable.os_meni_postavka)
                setOnClickListener { pisiNapravi() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
    }

    /** Prikazno ime osebe: uporabnikovo lastno ime ima prednost pred imenom iz kanala. */
    private fun imeOsebe(p: SporocilaShramba.Pogovor, vrste: Map<String, String>): String {
        val lastno = shramba.lastnoIme(shramba.osebaZa(shramba.kljucIdentitete(vrste[p.kanalId].orEmpty(), p)))
        if (lastno.isNotBlank()) return lastno
        return (if (p.kanalId == KlepetLinka.KANAL) KlepetLinka.prikaznoIme(p.ime) else p.ime).ifBlank { p.oseba }
    }

    private fun jeKlepet(vrsta: String) = vrsta.isNotEmpty() && vrsta != "email"

    private fun narisiFiltre() {
        filtriVrsta.removeAllViews()
        val vsi = listOf("vse" to getString(R.string.os_spor_filter_vse), "neprebrano" to getString(R.string.os_spor_filter_neprebrano),
            "email" to getString(R.string.os_spor_eposta), "klepet" to getString(R.string.os_spor_filter_klepeti)) +
            shramba.vseOznake().map { "oznaka:$it" to "# $it" }
        if (vsi.none { it.first == filter }) filter = "vse"
        for ((kljuc, ime) in vsi) {
            filtriVrsta.addView(TextView(this).apply {
                text = ime; textSize = 12f; isFocusable = true; isClickable = true; maxLines = 1
                setTextColor(osBarva(if (filter == kljuc) R.color.os_mint_temna else R.color.os_besedilo))
                setPadding(dp(12), dp(6), dp(12), dp(6)); tag = "filter|$kljuc"
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(osBarva(if (filter == kljuc) R.color.os_mint else R.color.os_kartica_dvignjena)) }
                setOnClickListener { filter = kljuc; narisiFiltre(); narisiSeznam() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
    }

    private fun narisiSeznam() {
        val kanali = shramba.kanali()
        val vrste = kanali.associate { it.id to it.vrsta }
        val niz = iskanje.text?.toString().orEmpty().trim().lowercase()
        val pogovori = shramba.pogovori().filter { p ->
            val oznake = shramba.oznake(p.kanalId, p.id)
            val ustrezaFilter = when {
                filter == "vse" -> true
                filter == "neprebrano" -> p.neprebrano > 0
                filter == "email" -> vrste[p.kanalId] == "email"
                filter == "klepet" -> jeKlepet(vrste[p.kanalId].orEmpty())
                filter.startsWith("oznaka:") -> oznake.contains(filter.removePrefix("oznaka:"))
                else -> true
            }
            ustrezaFilter && (niz.isEmpty() || (imeOsebe(p, vrste) + " " + p.ime + " " + p.oseba + " " + p.zadeva + " " + p.zadnje + " " + oznake.joinToString(" ")).lowercase().contains(niz))
        }
        seznam.removeAllViews()
        prazno.text = getString(if (kanali.isEmpty()) R.string.os_spor_prazno else if (niz.isNotEmpty() || filter != "vse") R.string.os_spor_ni_zadetkov else R.string.os_spor_ni_pogovorov)
        prazno.visibility = if (pogovori.isEmpty()) View.VISIBLE else View.GONE
        val imena = kanali.associate { it.id to it.ime }
        var prejsnji: View? = null
        for (p in pogovori) {
            val oznacen = izbran?.id == p.id && izbran?.kanalId == p.kanalId
            val oznake = shramba.oznake(p.kanalId, p.id)
            val kartica = LinearLayout(this).apply {
                id = View.generateViewId(); orientation = LinearLayout.VERTICAL
                tag = p.kanalId + "|" + p.id
                isFocusable = true; isClickable = true; setBackgroundResource(R.drawable.os_ploscica_app)
                isSelected = oznacen
                setPadding(dp(16), dp(12), dp(16), dp(12)); setOnClickListener { pokaziPogovor(p) }
                // Naloga 106: dolg pritisk (TV: zadrzan OK) - »Izbrisi pogovor« s potrditvijo.
                setOnLongClickListener { potrdiIzbrisPogovora(p, vrste); true }
                val vrh = LinearLayout(this@SporocilaActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                vrh.addView(TextView(this@SporocilaActivity).apply {
                    text = imeOsebe(p, vrste)
                    setTextColor(osBarva(R.color.os_besedilo)); textSize = 16f; maxLines = 1
                    isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
                    typeface = Typeface.create("sans-serif-medium", if (p.neprebrano > 0) Typeface.BOLD else Typeface.NORMAL)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                vrh.addView(TextView(this@SporocilaActivity).apply {
                    text = kratekCas(p.cas); setTextColor(osBarva(R.color.os_umirjeno)); textSize = 11f
                })
                if (p.neprebrano > 0) vrh.addView(TextView(this@SporocilaActivity).apply {
                    text = p.neprebrano.toString(); setTextColor(osBarva(R.color.os_mint_temna)); textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                    setPadding(dp(7), dp(1), dp(7), dp(1))
                    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(osBarva(R.color.os_mint)) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
                addView(vrh)
                addView(TextView(this@SporocilaActivity).apply {
                    text = p.zadnje.replace('\n', ' ').trim()
                    setTextColor(osBarva(R.color.os_umirjeno)); textSize = 13f; maxLines = 1
                })
                addView(TextView(this@SporocilaActivity).apply {
                    text = listOf((if (vrste[p.kanalId] == "email") "✉ " else "💬 ") + imena[p.kanalId].orEmpty()).plus(oznake.map { "# $it" }).joinToString("  ")
                    setTextColor(osBarva(R.color.os_mint)); textSize = 10f; maxLines = 1
                })
            }
            prejsnji?.let { it.nextFocusDownId = kartica.id; kartica.nextFocusUpId = it.id }
            if (siroko) kartica.nextFocusRightId = odgovor.id
            seznam.addView(kartica, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            prejsnji = kartica
        }
        prejsnji?.let { it.nextFocusDownId = it.id }
    }

    // ------------------------------------------------------------------ osebe, oznake, iskanje pri osebi
    private fun pogovoriOsebe(p: SporocilaShramba.Pogovor): List<SporocilaShramba.Pogovor> {
        val vrste = shramba.kanali().associate { it.id to it.vrsta }
        val oseba = shramba.osebaZa(shramba.kljucIdentitete(vrste[p.kanalId].orEmpty(), p))
        return shramba.pogovori().filter { shramba.osebaZa(shramba.kljucIdentitete(vrste[it.kanalId].orEmpty(), it)) == oseba }
    }

    private fun urediOsebo(p: SporocilaShramba.Pogovor) {
        val vrste = shramba.kanali().associate { it.id to it.vrsta }
        val kljuc = shramba.kljucIdentitete(vrste[p.kanalId].orEmpty(), p)
        val oseba = shramba.osebaZa(kljuc)
        val ime = polje(R.string.os_spor_oseba_ime).apply { setText(shramba.lastnoIme(oseba)) }
        val privzeto = (if (p.kanalId == KlepetLinka.KANAL) KlepetLinka.prikaznoIme(p.ime) else p.ime).ifBlank { p.oseba }
        val polja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(this@SporocilaActivity).apply { text = getString(R.string.os_spor_oseba_privzeto, privzeto); setTextColor(osBarva(R.color.os_umirjeno)); textSize = 13f })
            addView(ime)
            val identitete = pogovoriOsebe(p).map { it.oseba }.distinct()
            addView(TextView(this@SporocilaActivity).apply { text = getString(R.string.os_spor_oseba_identitete) + " " + identitete.joinToString(", "); setTextColor(osBarva(R.color.os_umirjeno)); textSize = 12f; setPadding(0, dp(8), 0, 0) })
        }
        val zdruzena = oseba != kljuc
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.os_spor_oseba)
            .setView(ScrollView(this).apply { addView(polja) })
            .setPositiveButton(R.string.os_spor_shrani) { _, _ -> shramba.preimenujOsebo(oseba, ime.text?.toString().orEmpty()); osveziPrikaz() }
            .setNeutralButton(if (zdruzena) R.string.os_spor_razdruzi else R.string.os_spor_zdruzi) { _, _ ->
                if (zdruzena) { shramba.razdruzi(kljuc); osveziPrikaz() } else zdruziZ(p)
            }
            .setNegativeButton(R.string.os_preklici, null).create()
        Kontroler.pokazi(okno); okno.show()
    }

    /** Zdruzi to osebo z drugo iz seznama (ista oseba na drugem kanalu, npr. e-posta + Matrix). */
    private fun zdruziZ(p: SporocilaShramba.Pogovor) {
        val vrste = shramba.kanali().associate { it.id to it.vrsta }
        val moj = shramba.osebaZa(shramba.kljucIdentitete(vrste[p.kanalId].orEmpty(), p))
        val druge = shramba.pogovori().map { it to shramba.osebaZa(shramba.kljucIdentitete(vrste[it.kanalId].orEmpty(), it)) }
            .filter { it.second != moj }.distinctBy { it.second }
        if (druge.isEmpty()) { Toast.makeText(this, R.string.os_spor_ni_drugih_oseb, Toast.LENGTH_SHORT).show(); return }
        val imena = druge.map { imeOsebe(it.first, vrste) + "  ·  " + it.first.oseba }.toTypedArray()
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.os_spor_zdruzi_z)
            .setItems(imena) { _, i ->
                val (drugi, drugaOseba) = druge[i]
                val kljuci = pogovoriOsebe(drugi).map { shramba.kljucIdentitete(vrste[it.kanalId].orEmpty(), it) }.distinct()
                shramba.zdruziOsebi(moj, drugaOseba, kljuci); osveziPrikaz()
            }
            .setNegativeButton(R.string.os_preklici, null).create()
        Kontroler.pokazi(okno); okno.show()
    }

    private fun urediOznake(p: SporocilaShramba.Pogovor) {
        val trenutne = shramba.oznake(p.kanalId, p.id).toMutableList()
        val vse = (shramba.vseOznake() + trenutne).distinct()
        val nova = polje(R.string.os_spor_nova_oznaka)
        val izbire = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (o in vse) izbire.addView(android.widget.CheckBox(this).apply {
            text = o; isChecked = trenutne.contains(o); setTextColor(osBarva(R.color.os_besedilo))
            setOnCheckedChangeListener { _, b -> if (b) { if (!trenutne.contains(o)) trenutne.add(o) } else trenutne.remove(o) }
        })
        val polja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(izbire); addView(nova) }
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.os_spor_oznake)
            .setView(ScrollView(this).apply { addView(polja) })
            .setPositiveButton(R.string.os_spor_shrani) { _, _ ->
                nova.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { trenutne.add(it) }
                shramba.nastaviOznake(p.kanalId, p.id, trenutne); narisiFiltre(); osveziPrikaz()
            }
            .setNegativeButton(R.string.os_preklici, null).create()
        Kontroler.pokazi(okno); okno.show()
    }

    /** Iskanje po besedilu sporocil te osebe (vsi njeni kanali); zadetek odpre pogovor in obrobi sporocilo. */
    private fun isciPriOsebi(p: SporocilaShramba.Pogovor) {
        val vnos = polje(R.string.os_spor_isci_osebo_namig)
        val zadetki = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val polja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(vnos); addView(zadetki) }
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.os_spor_isci_osebo)
            .setView(ScrollView(this).apply { addView(polja) })
            .setNegativeButton(R.string.os_spor_zapri, null).create()
        val pogovori = pogovoriOsebe(p)
        val imenaKanalov = shramba.kanali().associate { it.id to it.ime }
        vnos.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                zadetki.removeAllViews()
                val niz = e?.toString().orEmpty().trim(); if (niz.length < 2) return
                val rez = shramba.isciSporocila(niz, pogovori)
                if (rez.isEmpty()) { zadetki.addView(TextView(this@SporocilaActivity).apply { text = getString(R.string.os_spor_ni_zadetkov); setTextColor(osBarva(R.color.os_umirjeno)); textSize = 13f; setPadding(0, dp(8), 0, 0) }); return }
                for (z in rez) zadetki.addView(LinearLayout(this@SporocilaActivity).apply {
                    orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true; setBackgroundResource(R.drawable.os_ploscica_app)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    addView(TextView(this@SporocilaActivity).apply { text = imenaKanalov[z.kanalId].orEmpty() + " · " + kratekCas(z.sporocilo.cas); setTextColor(osBarva(R.color.os_umirjeno)); textSize = 11f })
                    addView(TextView(this@SporocilaActivity).apply { text = z.izsek; setTextColor(osBarva(R.color.os_besedilo)); textSize = 13f; maxLines = 3 })
                    setOnClickListener {
                        okno.dismiss(); oznaciSporocilo = z.sporocilo.id
                        pogovori.firstOrNull { it.kanalId == z.kanalId && it.id == z.sporocilo.pogovorId }?.let { pokaziPogovor(it) }
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            }
        })
        Kontroler.pokazi(okno); okno.show(); vnos.requestFocus()
    }

    private fun pokaziPogovor(p: SporocilaShramba.Pogovor?) {
        izbran = p
        KlepetLinka.odprtPogovor = p?.takeIf { it.kanalId == KlepetLinka.KANAL }?.id
        if (p != null && p.kanalId == KlepetLinka.KANAL)
            KlepetLinka.umakniObvestilo(this, p.id)
        if (!siroko) {
            seznamPlosca.visibility = if (p == null) View.VISIBLE else View.GONE
            pogovorPlosca.visibility = if (p == null) View.GONE else View.VISIBLE
        }
        if (p == null) {
            pogovorIme.text = getString(R.string.os_spor_izberi); pogovorZadeva.text = ""
            sporocilaSeznam.removeAllViews(); sporocilaDrsnik.isFocusable = false
            odgovor.isEnabled = false; posljiGumb.isEnabled = false; posljiGumb.alpha = 0.5f
            narisiSeznam()
            return
        }
        pogovorIme.text = imeOsebe(p, shramba.kanali().associate { it.id to it.vrsta })
        pogovorZadeva.text = when {
            p.kanalId == KlepetLinka.KANAL -> "Safeer Link"
            p.zadeva.isNotBlank() -> p.zadeva
            else -> p.oseba
        }
        odgovor.isEnabled = true; posljiGumb.isEnabled = true; posljiGumb.alpha = 1f
        sporocilaDrsnik.isFocusable = true  // z daljincem se da pomikati po pogovoru
        narisiSporocila()
        if (p.neprebrano > 0) delavec.execute {
            try { SporocilaKanali.oznaciPrebrano(this, shramba, p.kanalId, p.id) } catch (_: Throwable) {}
            glavna.post { if (!unicena) narisiSeznam() }
        } else narisiSeznam()
        odgovor.requestFocus()
    }

    /**
     * Lastnik 27. 10. 2026 (TV): ob odprtju pogovora je fokus na polju Odgovor odprl tipkovnico cez pol zaslona. Z daljincem
     * fokus polja tipkovnice ne odpre; odpre jo OK (Enter) in ob odhodu fokusa se spet skrije (kot lupa v Pokrajini).
     * Na telefonu in tablici ostane, kot je bilo.
     */
    private fun tipkovnicaSeleNaOk(e: EditText) {
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) return
        e.showSoftInputOnFocus = false
        val prej = e.onFocusChangeListener
        e.setOnFocusChangeListener { v, ima ->
            prej?.onFocusChange(v, ima)
            if (!ima) (v as EditText).showSoftInputOnFocus = false
            v.post { uskladiPisanje() }
        }
    }

    private fun tipkovnicaNaOk(e: EditText, koda: Int, d: KeyEvent): Boolean {
        if (d.action != KeyEvent.ACTION_DOWN || e.showSoftInputOnFocus) return false
        if (koda != KeyEvent.KEYCODE_DPAD_CENTER && koda != KeyEvent.KEYCODE_ENTER) return false
        e.showSoftInputOnFocus = true
        if (e === odgovor && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) {
            // Pas pisanja najprej (vrstica z odgovorom se prestavi vanj), sele nato tipkovnica - prestavljen pogled izgubi fokus.
            vstopiVPisanje()
            e.post {
                e.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(e, 0)
            }
            return true
        }
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(e, 0)
        e.post { uskladiPisanje() }
        return true
    }

    // ------------------------------------------------------------------ pisanje odgovora na TV (lastnik 27. 10. 2026)

    private lateinit var vnosVrstica: LinearLayout
    private lateinit var pisanjePlosca: LinearLayout
    private lateinit var pisanjeKanali: LinearLayout
    private lateinit var pisanjeOznaka: TextView
    private lateinit var pisanjeOblacki: LinearLayout
    private lateinit var pisanjeVnos: LinearLayout
    private var pisanjeTece = false
    private var vnosStars: ViewGroup? = null
    private var vnosIndeks = -1
    private var vnosParametri: ViewGroup.LayoutParams? = null
    private var skritoPisanje: Map<View, Int>? = null

    /** Steklena plosca nad pokrajino; s fokusom obroba v barvi poudarka (na TV mora biti fokus viden z dveh metrov). */
    private fun steklo(polmerDp: Int, fokusMogoc: Boolean, poudarjeno: Boolean = false) = android.graphics.drawable.StateListDrawable().apply {
        fun plosca(obroba: Int, debelina: Int, ozadje: Int) = GradientDrawable().apply {
            setColor(osBarva(ozadje)); cornerRadius = dp(polmerDp).toFloat(); setStroke(dp(debelina), osBarva(obroba))
        }
        if (fokusMogoc) addState(intArrayOf(android.R.attr.state_focused), plosca(R.color.os_mint, 3, R.color.os_kartica_steklo_fokus))
        addState(intArrayOf(), if (poudarjeno) plosca(R.color.os_mint, 2, R.color.os_kartica_steklo_fokus) else plosca(R.color.os_kartica_obroba, 1, R.color.os_kartica_steklo))
    }

    /**
     * Pas pisanja po skici lastnika: levo kanali osebe (s katerega odgovarjas), desno oznaka kanala, vrsta oblackov zadnjih
     * sporocil (zadnje, ki mu odgovarjas, poudarjeno) in vrstica Odgovor + Poslji (prestavi se sem iz pogovora). Ozadje je
     * pokrajina zaslona - pas je steklen in prosojen.
     */
    private fun zgradiPisanje(): LinearLayout {
        pisanjeKanali = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pisanjeOznaka = TextView(this).apply {
            textSize = 13f; setTextColor(osBarva(R.color.os_besedilo)); background = steklo(14, false)
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        pisanjeOblacki = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pisanjeVnos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val desno = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pisanjeOznaka, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(android.widget.HorizontalScrollView(this@SporocilaActivity).apply { isHorizontalScrollBarEnabled = false; addView(pisanjeOblacki) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            addView(pisanjeVnos, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; visibility = View.GONE
            background = steklo(24, false)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(ScrollView(this@SporocilaActivity).apply { isVerticalScrollBarEnabled = false; addView(pisanjeKanali) },
                LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(16) })
            addView(desno, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** OK na polju Odgovor (TV): zaslon se umakne, ostane pas pisanja nad pokrajino. */
    private fun vstopiVPisanje() {
        if (izbran == null) return
        if (!pisanjeTece) {
            pisanjeTece = true
            val root = koren as ViewGroup
            skritoPisanje = (0 until root.childCount).map { root.getChildAt(it) }.filter { it !== pisanjePlosca }.associateWith { it.visibility }
            skritoPisanje?.keys?.forEach { it.visibility = View.GONE }
            val stars = vnosVrstica.parent as? ViewGroup
            vnosStars = stars; vnosIndeks = stars?.indexOfChild(vnosVrstica) ?: -1; vnosParametri = vnosVrstica.layoutParams
            stars?.removeView(vnosVrstica)
            pisanjeVnos.addView(vnosVrstica, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            pisanjePlosca.visibility = View.VISIBLE
        }
        osveziKanalePisanja()
        osveziOblacke()
    }

    /** Nazaj: tipkovnica se zapre, vrstica z odgovorom gre nazaj v pogovor, zaslon je spet cel. */
    private fun izstopiIzPisanja() {
        if (!pisanjeTece) return
        pisanjeTece = false
        pisanjeVnos.removeView(vnosVrstica)
        val stars = vnosStars
        if (stars != null) stars.addView(vnosVrstica, if (vnosIndeks in 0..stars.childCount) vnosIndeks else stars.childCount, vnosParametri)
        pisanjePlosca.visibility = View.GONE
        skritoPisanje?.forEach { (v, s) -> v.visibility = s }
        skritoPisanje = null
        odgovor.showSoftInputOnFocus = false
        try { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(odgovor.windowToken, 0) } catch (_: Throwable) { }
        narisiSporocila()
        odgovor.post { odgovor.requestFocus() }
    }

    private fun imeKanala(q: SporocilaShramba.Pogovor, imena: Map<String, String>): String =
        if (q.kanalId == KlepetLinka.KANAL) "Safeer Link" else imena[q.kanalId].orEmpty().ifBlank { q.kanalId }

    /** Kanali osebe ob strani: izbira kanala je pogovor, po katerem gre odgovor (Poslji uporabi izbran pogovor). */
    private fun osveziKanalePisanja() {
        val p = izbran ?: return
        val imena = shramba.kanali().associate { it.id to it.ime }
        pisanjeOznaka.text = "\u25CF  " + imeKanala(p, imena)
        pisanjeKanali.removeAllViews()
        for (q in (listOf(p) + pogovoriOsebe(p)).distinctBy { it.kanalId to it.id }) {
            val je = q.kanalId == p.kanalId && q.id == p.id
            pisanjeKanali.addView(TextView(this).apply {
                text = imeKanala(q, imena); textSize = 15f; isFocusable = true; isClickable = true
                setTextColor(osBarva(if (je) R.color.os_mint else R.color.os_besedilo))
                background = steklo(14, true, poudarjeno = je)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener {
                    izbran = q
                    osveziKanalePisanja(); osveziOblacke()
                    odgovor.requestFocus()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
    }

    /** Oblacki zadnjih sporocil izbranega pogovora; zadnje (ki mu odgovarjas) je poudarjeno in vecje. */
    private fun osveziOblacke() {
        val p = izbran ?: return
        val ime = imeOsebe(p, shramba.kanali().associate { it.id to it.vrsta })
        val zadnja = shramba.sporocila(p.kanalId, p.id).takeLast(4)
        pisanjeOblacki.removeAllViews()
        zadnja.forEachIndexed { i, sp ->
            val jeZadnje = i == zadnja.lastIndex
            pisanjeOblacki.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = steklo(16, false, poudarjeno = jeZadnje)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                addView(TextView(this@SporocilaActivity).apply {
                    text = if (sp.smer == "ven") getString(R.string.pk_spor_ti) else ime
                    textSize = 12f; setTextColor(osBarva(R.color.os_umirjeno)); maxLines = 1
                })
                addView(TextView(this@SporocilaActivity).apply {
                    text = sp.besedilo; textSize = if (jeZadnje) 17f else 14f; setTextColor(osBarva(R.color.os_besedilo))
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(if (jeZadnje) 460 else 300)
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
        }
        // Zadnje sporocilo vidno desno (brez premika fokusa).
        pisanjeOblacki.post { (pisanjeOblacki.parent as? View)?.scrollTo(pisanjeOblacki.width, 0) }
    }

    private fun narisiSporocila() {
        val p = izbran ?: return
        if (pisanjeTece) osveziOblacke()   // novo ali poslano sporocilo med pisanjem
        sporocilaSeznam.removeAllViews()
        var obrobljena: View? = null
        for (s in shramba.sporocila(p.kanalId, p.id)) {
            val ven = s.smer == "ven"
            val zadetek = s.id == oznaciSporocilo
            val vrstica = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = if (ven) Gravity.END else Gravity.START
            }
            if (zadetek) obrobljena = vrstica
            vrstica.addView(TextView(this).apply {
                text = s.besedilo.trim(); textSize = 15f; setTextIsSelectable(false)
                // Dolg pritisk (TV: zadrzan OK na sporocilu): kopiraj, odpri povezavo, »Izbrisi pri meni« (naloga 106).
                setOnLongClickListener { dejanjaSporocila(p, s); true }
                // Naloga 106: z daljincem se da priti do sporocila (obroba pokaze fokus).
                isFocusable = true; isLongClickable = true
                setTextColor(osBarva(if (ven) R.color.os_mint_temna else R.color.os_besedilo))
                setPadding(dp(14), dp(9), dp(14), dp(9))
                val ozadje = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat(); setColor(osBarva(if (ven) R.color.os_mint else R.color.os_ozadje))
                    if (zadetek) setStroke(dp(2), osBarva(R.color.os_opozorilo))
                }
                background = ozadje
                setOnFocusChangeListener { _, f -> ozadje.setStroke(if (f || zadetek) dp(2) else 0, osBarva(if (f) R.color.os_mint else R.color.os_opozorilo)) }
                maxWidth = (resources.displayMetrics.widthPixels * (if (siroko) 0.4f else 0.75f)).toInt()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            vrstica.addView(TextView(this).apply {
                text = kratekCas(s.cas); textSize = 10f; setTextColor(osBarva(R.color.os_umirjeno))
                setPadding(dp(6), dp(2), dp(6), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            sporocilaSeznam.addView(vrstica, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        val cilj = obrobljena
        if (cilj != null) { oznaciSporocilo = null; sporocilaDrsnik.post { sporocilaDrsnik.smoothScrollTo(0, maxOf(0, cilj.top - dp(40))) } }
        else sporocilaDrsnik.post { sporocilaDrsnik.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * Dolg pritisk na sporocilo: kopiraj, ena sama povezava se »Odpri povezavo«, in »Izbrisi pri meni« (naloga 106) -
     * meni po [SporocilaPravila.dejanjaSporocila].
     */
    private fun dejanjaSporocila(p: SporocilaShramba.Pogovor, s: SporocilaShramba.Sporocilo) {
        val besedilo = s.besedilo.trim()
        val naVseh = p.kanalId == KlepetLinka.KANAL && SporocilaPravila.skupniId(s.id, s.smer) != null
        // Pregled D5: »Izbrisi na vseh« samo, ce ga druga naprava zna (zmoznost chat.delete); ni je v seznamu - izbris pocaka.
        // Pregled 4: brez izrecnega signala »izbrisa ne zna« je »ne javi« = »morda zna« - izbris pocaka v vrsti.
        val podprto = !naVseh || SporocilaPravila.izbrisNaVsehPonudi(KlepetLinka.izbrisNaDrugi(napraveLinka, p.id))
        val dejanja = SporocilaPravila.dejanjaSporocila(besedilo, OsPravila.jePovezava(besedilo), naVseh, podprto)
        val imena = dejanja.map {
            when (it) {
                SporocilaPravila.ODPRI -> getString(R.string.ui_share_open_link)
                SporocilaPravila.KOPIRAJ -> getString(R.string.os_bliznjica_kopiraj)
                SporocilaPravila.IZBRISI_VSE -> getString(R.string.os_spor_izbrisi_na_vseh)
                SporocilaPravila.IZBRISI_VSE_NI -> getString(R.string.os_spor_na_vseh_ni_podprto)
                else -> getString(R.string.os_spor_izbrisi_pri_meni)
            }
        }
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setItems(imena.toTypedArray()) { _, i ->
                when (dejanja[i]) {
                    SporocilaPravila.KOPIRAJ -> Odlozisce.kopiraj(this, besedilo)
                    SporocilaPravila.ODPRI -> try {
                        startActivity(Brskalnik.namera(this).setAction(android.content.Intent.ACTION_VIEW)
                            .setData(android.net.Uri.parse(besedilo)))
                    } catch (_: Exception) { Toast.makeText(this, R.string.os_odpri_ni_aplikacije, Toast.LENGTH_SHORT).show() }
                    SporocilaPravila.IZBRISI_VSE -> potrdiIzbrisNaVseh(p, s)
                    SporocilaPravila.IZBRISI_VSE_NI ->
                        Toast.makeText(this, R.string.os_spor_na_vseh_ni_podprto_opis, Toast.LENGTH_LONG).show()
                    else -> potrdiIzbrisSporocila(p, s)
                }
            }
            .create()
        Kontroler.pokazi(okno)
        okno.show()
    }

    /**
     * Naloga 116: »Izbrisi na vseh mojih napravah« - po potrditvi sporocilo izgine tu in na napravi, ki ga je prejela.
     * Ce sta ta ali ona naprava brez povezave, izbris pocaka do naslednje uskladitve (sporocilo o tem).
     */
    private fun potrdiIzbrisNaVseh(p: SporocilaShramba.Pogovor, s: SporocilaShramba.Sporocilo) {
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.os_spor_izbrisi_na_vseh)
            .setMessage(R.string.os_spor_izbrisi_na_vseh_vprasanje)
            .setPositiveButton(R.string.os_spor_izbrisi) { _, _ ->
                delavec.execute {
                    val sprejeto = KlepetLinka.izbrisiNaVseh(applicationContext, p.id, s)
                    glavna.post {
                        if (unicena) return@post
                        Toast.makeText(this, if (sprejeto) R.string.os_spor_izbrisano_na_vseh else R.string.os_spor_izbris_caka,
                            Toast.LENGTH_LONG).show()
                        narisiSporocila(); narisiSeznam()
                    }
                }
            }
            .setNegativeButton(R.string.os_preklici, null).create()
        // Privzeti gumb je Preklici, kot pri »Izbrisi pri meni«.
        okno.setOnShowListener { okno.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus() }
        Kontroler.pokazi(okno); okno.show()
    }

    /** Naloga 106: »Izbrisi pri meni« - po potrditvi sporocilo izgine iz pogovora na tej napravi. */
    private fun potrdiIzbrisSporocila(p: SporocilaShramba.Pogovor, s: SporocilaShramba.Sporocilo) {
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.os_spor_izbrisi_pri_meni)
            .setMessage(R.string.os_spor_izbrisi_sporocilo_vprasanje)
            .setPositiveButton(R.string.os_spor_izbrisi) { _, _ ->
                shramba.izbrisiSporocilo(p.kanalId, p.id, s.id)
                Toast.makeText(this, R.string.os_spor_izbrisano, Toast.LENGTH_SHORT).show()
                narisiSporocila(); narisiSeznam()
            }
            .setNegativeButton(R.string.os_preklici, null).create()
        // Privzeti gumb je Preklici: zadrzan OK, ki je odprl meni, ne sme takoj izbrisati.
        okno.setOnShowListener { okno.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus() }
        Kontroler.pokazi(okno); okno.show()
    }

    /** Naloga 106: »Izbrisi pogovor« - po potrditvi vsa sporocila pogovora na tej napravi; oznake ostanejo. */
    private fun potrdiIzbrisPogovora(p: SporocilaShramba.Pogovor, vrste: Map<String, String>) {
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.os_spor_izbrisi_pogovor)
            .setMessage(getString(R.string.os_spor_izbrisi_pogovor_vprasanje, imeOsebe(p, vrste)))
            .setPositiveButton(R.string.os_spor_izbrisi) { _, _ ->
                shramba.izbrisiPogovor(p.kanalId, p.id)
                Toast.makeText(this, R.string.os_spor_izbrisano, Toast.LENGTH_SHORT).show()
                if (izbran?.id == p.id && izbran?.kanalId == p.kanalId) pokaziPogovor(null) else narisiSeznam()
            }
            .setNegativeButton(R.string.os_preklici, null).create()
        okno.setOnShowListener { okno.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus() }
        Kontroler.pokazi(okno); okno.show()
    }

    private fun kratekCas(iso: String): String {
        val d = try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso.take(19))
        } catch (_: Throwable) { null } ?: return ""
        val zdaj = Calendar.getInstance(); val c = Calendar.getInstance().apply { time = d }
        val danes = zdaj.get(Calendar.YEAR) == c.get(Calendar.YEAR) && zdaj.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
        return SimpleDateFormat(if (danes) "HH:mm" else if (zdaj.get(Calendar.YEAR) == c.get(Calendar.YEAR)) "d. M." else "d. M. yyyy",
            Locale.getDefault()).format(d)
    }

    // ------------------------------------------------------------------ delo v ozadju
    private fun sinhroniziraj() {
        if (shramba.kanali().isEmpty()) return
        delavec.execute {
            try { SporocilaKanali.sinhroniziraj(this, shramba) } catch (_: Throwable) {}
            glavna.post { if (!unicena) osveziPrikaz() }
        }
    }

    private fun osveziPrikaz() {
        val fokus = currentFocus
        // Seznam se na novo izrise: z daljincem mora fokus ostati na istem pogovoru, ne skociti drugam.
        val fokusKljuc = fokus?.tag as? String
        val prej = izbran
        izbran = prej?.let { i -> shramba.pogovori().firstOrNull { it.id == i.id && it.kanalId == i.kanalId } }
        if (prej != null && izbran == null) { narisiKanale(); pokaziPogovor(null); return }   // kanal odstranjen
        narisiKanale()
        narisiFiltre()
        narisiSeznam()
        izbran?.let { pogovorIme.text = imeOsebe(it, shramba.kanali().associate { k -> k.id to k.vrsta }); narisiSporocila() }
        if (fokus == odgovor) odgovor.requestFocus()
        else if (fokusKljuc != null)
            (seznam.findViewWithTag<View>(fokusKljuc) ?: kanaliVrsta.findViewWithTag(fokusKljuc))?.requestFocus()
    }

    private fun poslji() {
        val p = izbran ?: return
        val besedilo = odgovor.text?.toString().orEmpty().trim()
        if (besedilo.isEmpty()) return
        posljiGumb.isEnabled = false; posljiGumb.alpha = 0.5f
        delavec.execute {
            var izid = ""
            val napaka = try { izid = SporocilaKanali.poslji(this, shramba, p.kanalId, p.id, besedilo); null } catch (e: Throwable) { e }
            glavna.post {
                if (unicena) return@post
                posljiGumb.isEnabled = true; posljiGumb.alpha = 1f
                if (napaka == null) {
                    odgovor.setText(""); osveziPrikaz()
                    if (izid == "queued") Toast.makeText(this, R.string.os_spor_caka, Toast.LENGTH_LONG).show()
                }
                else Toast.makeText(this, getString(R.string.os_spor_ni_poslano) + " " + besediloStanja(SporocilaKanali.razlog(napaka)), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------------ kanali
    private fun polje(namig: Int, vrsta: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        hint = getString(namig); isSingleLine = true; inputType = vrsta
        typeface = Typeface.DEFAULT
        // Gesla hrani Android Keystore; brez ponudbe upravitelja gesel sredi urejanja.
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    private fun dodajKanal() {
        var vrsta = "email"
        var ponudnik: PonudnikiEposte.Ponudnik? = null
        val eposta = gumb(getString(R.string.os_spor_eposta))
        val chatwoot = gumb(getString(R.string.os_spor_chatwoot))
        val aplikacije = gumb(getString(R.string.os_spor_aplikacije))
        val lastniApi = gumb(getString(R.string.os_spor_lastni_api))
        // Stiri zavihki: na sirokem zaslonu v eni vrsti, na telefonu 2 x 2 - vsi vidni, brez lomljenja besed.
        val zavihki = listOf(eposta, chatwoot, aplikacije, lastniApi)
        zavihki.forEach { it.maxLines = 1; it.isSingleLine = true; it.gravity = Gravity.CENTER }
        fun vrstaZavihkov(g: List<TextView>) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            g.forEachIndexed { i, z -> addView(z, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i < g.size - 1) marginEnd = dp(6); bottomMargin = dp(6) }) }
        }
        val izbira = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (siroko) addView(vrstaZavihkov(zavihki)) else { addView(vrstaZavihkov(zavihki.take(2))); addView(vrstaZavihkov(zavihki.drop(2))) }
        }
        // Lastni API: Matrix (streznik + zeton) ali Telegram Bot (zeton od @BotFather) - navodila povedo, kje ju dobis.
        var protokol = "matrix"
        val apiOpis = TextView(this).apply { text = getString(R.string.os_spor_lastni_api_opis); setTextColor(osBarva(R.color.os_umirjeno)); textSize = 13f; setPadding(0, dp(10), 0, dp(6)) }
        val gMatrix = gumb("Matrix"); val gTelegram = gumb("Telegram Bot")
        val apiIzbira = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(gMatrix, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            addView(gTelegram, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val apiStreznik = polje(R.string.os_spor_api_streznik, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val apiZeton = polje(R.string.os_spor_zeton, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val apiIme = polje(R.string.os_spor_api_ime)
        val apiNavodilaNaslov = TextView(this).apply { text = getString(R.string.os_spor_kaj_narediti); setTextColor(osBarva(R.color.os_besedilo)); textSize = 14f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(0, dp(12), 0, dp(4)) }
        val apiNavodila = TextView(this).apply { setTextColor(osBarva(R.color.os_umirjeno)); textSize = 13f; setLineSpacing(0f, 1.15f) }
        val aFields = listOf(apiOpis, apiIzbira, apiStreznik, apiZeton, apiIme, apiNavodilaNaslov, apiNavodila)
        fun nastaviProtokol(pr: String) {
            protokol = pr
            gMatrix.isSelected = pr == "matrix"; gMatrix.isActivated = pr == "matrix"; gTelegram.isSelected = pr != "matrix"; gTelegram.isActivated = pr != "matrix"
            apiStreznik.visibility = if (pr == "matrix") View.VISIBLE else View.GONE
            apiZeton.hint = if (pr == "matrix") getString(R.string.os_spor_zeton) else "123456:ABC…"
            apiNavodila.text = getString(if (pr == "matrix") R.string.os_spor_nav_matrix else R.string.os_spor_nav_telegram_bot)
        }
        gMatrix.setOnClickListener { nastaviProtokol("matrix") }; gTelegram.setOnClickListener { nastaviProtokol("telegram_bot") }
        // E-posta, korak 1: ponudnik s seznama (streznike poznamo mi). Korak 2: naslov + geslo + navodila.
        val ponudnikiNaslov = TextView(this).apply { text = getString(R.string.os_spor_izberi_ponudnika); setTextColor(osBarva(R.color.os_besedilo)); textSize = 15f; setPadding(0, dp(10), 0, dp(6)) }
        val ponudniki = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val zamenjaj = gumb(getString(R.string.os_spor_zamenjaj_ponudnika))
        val naslov = polje(R.string.os_spor_naslov, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val geslo = polje(R.string.os_spor_geslo, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val napredno = gumb(getString(R.string.os_spor_napredno))
        val imap = polje(R.string.os_spor_imap, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val smtp = polje(R.string.os_spor_smtp, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val navodilaNaslov = TextView(this).apply { text = getString(R.string.os_spor_kaj_narediti); setTextColor(osBarva(R.color.os_besedilo)); textSize = 14f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(0, dp(12), 0, dp(4)) }
        val navodila = TextView(this).apply { setTextColor(osBarva(R.color.os_umirjeno)); textSize = 13f; setLineSpacing(0f, 1.15f) }
        val url = polje(R.string.os_spor_url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val racun = polje(R.string.os_spor_racun, InputType.TYPE_CLASS_NUMBER)
        val zeton = polje(R.string.os_spor_zeton, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val napaka = TextView(this).apply { setTextColor(osBarva(R.color.os_opozorilo)); textSize = 13f; visibility = View.GONE }
        val seznamAplikacij = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val korak1 = listOf(ponudnikiNaslov, ponudniki)
        val korak2 = listOf(zamenjaj, naslov, geslo, napredno, imap, smtp, navodilaNaslov, navodila)
        val cFields = listOf(url, racun, zeton)
        lateinit var okno: AlertDialog

        fun pokaziKorak(drugi: Boolean) {
            korak1.forEach { it.visibility = if (vrsta == "email" && !drugi) View.VISIBLE else View.GONE }
            korak2.forEach { it.visibility = if (vrsta == "email" && drugi) View.VISIBLE else View.GONE }
            val p = ponudnik
            if (drugi && p != null && vrsta == "email") {
                val drug = p.id == PonudnikiEposte.DRUG
                imap.visibility = if (drug) View.VISIBLE else View.GONE
                smtp.visibility = if (drug) View.VISIBLE else View.GONE
                napredno.visibility = if (drug) View.GONE else View.VISIBLE
                naslov.hint = if (drug) getString(R.string.os_spor_naslov) else getString(R.string.os_spor_naslov_ponudnika, p.ime)
                geslo.hint = getString(if (p.geslo == PonudnikiEposte.APLIKACIJE) R.string.os_spor_geslo_aplikacije_polje else R.string.os_spor_geslo)
                navodila.text = getString(p.navodila)
                if (!drug) { imap.setText(if (p.imapVrata == 993) p.imap else "${p.imap}:${p.imapVrata}"); smtp.setText(if (p.smtpVrata == 465) p.smtp else "${p.smtp}:${p.smtpVrata}") }
                else if (imap.tag != "rocno") { val (i, sm) = PonudnikiEposte.predlog(naslov.text?.toString().orEmpty()); imap.setText(if (naslov.text.isNullOrBlank()) "" else i); smtp.setText(if (naslov.text.isNullOrBlank()) "" else sm) }
            }
            okno.getButton(AlertDialog.BUTTON_POSITIVE)?.visibility = if (vrsta == "aplikacije" || (vrsta == "email" && !drugi)) View.GONE else View.VISIBLE
        }

        fun izberiPonudnika(p: PonudnikiEposte.Ponudnik) {
            ponudnik = p; napaka.visibility = View.GONE
            pokaziKorak(true)
            naslov.requestFocus()
        }

        // Seznam ponudnikov: ime + kratek opis, kaj bo treba (navadno geslo / geslo za aplikacije / ni na voljo).
        for (p in PonudnikiEposte.SEZNAM) {
            val vrstica = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true
                setPadding(dp(14), dp(9), dp(14), dp(9)); setBackgroundResource(R.drawable.os_meni_postavka)
                val ime = TextView(this@SporocilaActivity).apply {
                    text = if (p.id == PonudnikiEposte.DRUG) getString(R.string.os_spor_ponudnik_drug) else p.ime + (if (p.geslo == PonudnikiEposte.OAUTH) "  · " + getString(R.string.os_spor_ni_na_voljo) else "")
                    setTextColor(osBarva(if (p.geslo == PonudnikiEposte.OAUTH) R.color.os_umirjeno else R.color.os_besedilo)); textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
                val opis = TextView(this@SporocilaActivity).apply {
                    text = when {
                        p.id == PonudnikiEposte.DRUG -> getString(R.string.os_spor_ponudnik_drug_opis)
                        p.geslo == PonudnikiEposte.APLIKACIJE -> getString(R.string.os_spor_geslo_aplikacije)
                        p.geslo == PonudnikiEposte.OAUTH -> ""
                        else -> p.domene.take(2).joinToString(", ") { "@" + it }
                    }
                    setTextColor(osBarva(R.color.os_umirjeno)); textSize = 12f; visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
                }
                addView(ime); addView(opis)
                setOnClickListener { izberiPonudnika(p) }
            }
            ponudniki.addView(vrstica, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        zamenjaj.setOnClickListener { ponudnik = null; pokaziKorak(false) }
        napredno.setOnClickListener { imap.visibility = View.VISIBLE; smtp.visibility = View.VISIBLE; napredno.visibility = View.GONE }
        imap.setOnFocusChangeListener { _, f -> if (f) imap.tag = "rocno" }
        smtp.setOnFocusChangeListener { _, f -> if (f) smtp.tag = "rocno" }
        naslov.setOnFocusChangeListener { _, f ->
            if (f) return@setOnFocusChangeListener
            val p = ponudnik ?: return@setOnFocusChangeListener
            val t = naslov.text?.toString().orEmpty()
            if (p.id == PonudnikiEposte.DRUG && t.contains("@")) {
                // Ce je domena znanega ponudnika, ga izberemo namesto ugibanja.
                PonudnikiEposte.izNaslova(t)?.let { izberiPonudnika(it); return@setOnFocusChangeListener }
                if (imap.tag != "rocno") { val (i, sm) = PonudnikiEposte.predlog(t); imap.setText(i); smtp.setText(sm) }
            }
        }

        // Aplikacije: ponudniki brez IMAP/SMTP (Outlook, Proton ...) in klepeti - uradna aplikacija iz trgovine.
        seznamAplikacij.addView(TextView(this).apply { text = getString(R.string.os_spor_aplikacije_opis); setTextColor(osBarva(R.color.os_umirjeno)); textSize = 12f; setPadding(0, dp(10), 0, dp(8)) })
        for (skupina in listOf("posta" to R.string.os_spor_app_posta, "klepet" to R.string.os_spor_app_klepet)) {
            seznamAplikacij.addView(TextView(this).apply { text = getString(skupina.second); setTextColor(osBarva(R.color.os_besedilo)); textSize = 14f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(0, dp(8), 0, dp(4)) })
            for (a in AplikacijeSporocil.SEZNAM.filter { it.vrsta == skupina.first }) {
                val vrstica = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(8), dp(10), dp(8)); setBackgroundResource(R.drawable.os_meni_postavka)
                    val besedilo = LinearLayout(this@SporocilaActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(this@SporocilaActivity).apply { text = a.ime; setTextColor(osBarva(R.color.os_besedilo)); textSize = 15f })
                        addView(TextView(this@SporocilaActivity).apply { text = getString(a.opis); setTextColor(osBarva(R.color.os_umirjeno)); textSize = 12f })
                    }
                    val namescena = AplikacijeSporocil.nameščena(this@SporocilaActivity, a.paket)
                    val g = gumb(getString(if (namescena) R.string.os_spor_app_odpri else R.string.os_spor_app_namesti))
                    g.setOnClickListener { AplikacijeSporocil.odpriAliNamesti(this@SporocilaActivity, a.paket) }
                    addView(besedilo, LinearLayout.LayoutParams(0, -2, 1f)); addView(g)
                }
                seznamAplikacij.addView(vrstica, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
        }

        fun nastaviVrsto(v: String) {
            vrsta = v
            for ((g, ime) in listOf(eposta to "email", chatwoot to "chatwoot", aplikacije to "aplikacije", lastniApi to "api")) { g.isSelected = v == ime; g.isActivated = v == ime }
            cFields.forEach { it.visibility = if (v == "chatwoot") View.VISIBLE else View.GONE }
            aFields.forEach { it.visibility = if (v == "api") View.VISIBLE else View.GONE }
            if (v == "api") nastaviProtokol(protokol)
            seznamAplikacij.visibility = if (v == "aplikacije") View.VISIBLE else View.GONE
            napaka.visibility = View.GONE
            pokaziKorak(ponudnik != null)
        }
        eposta.setOnClickListener { nastaviVrsto("email") }
        chatwoot.setOnClickListener { nastaviVrsto("chatwoot") }
        aplikacije.setOnClickListener { nastaviVrsto("aplikacije") }
        lastniApi.setOnClickListener { nastaviVrsto("api") }

        val polja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(izbira); (korak1 + korak2 + cFields + aFields).forEach { addView(it) }; addView(seznamAplikacij); addView(napaka)
        }
        okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.os_spor_dodaj)
            .setView(ScrollView(this).apply { addView(polja) })
            .setPositiveButton(R.string.os_spor_dodaj, null)
            .setNegativeButton(R.string.os_preklici, null)
            .create()
        okno.setCanceledOnTouchOutside(false)
        okno.setOnShowListener {
            nastaviVrsto("email")
            okno.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { gumbDodaj ->
                val id = UUID.randomUUID().toString().take(8)
                var kanal: SporocilaShramba.Kanal
                val skrivnost: String
                if (vrsta == "email") {
                    val p = ponudnik ?: return@setOnClickListener
                    val t = naslov.text?.toString().orEmpty().trim()
                    skrivnost = geslo.text?.toString().orEmpty()
                    if (!t.contains("@") || skrivnost.isEmpty()) { pokaziNapako(napaka, getString(R.string.os_spor_napaka_prijava)); return@setOnClickListener }
                    if (p.geslo == PonudnikiEposte.OAUTH) { pokaziNapako(napaka, getString(R.string.os_spor_nav_oauth)); return@setOnClickListener }
                    val (iH, iP) = gostitelj(imap.text?.toString().orEmpty(), 993)
                    val (sH, sP) = gostitelj(smtp.text?.toString().orEmpty(), 465)
                    if (iH.isBlank() || sH.isBlank()) { pokaziNapako(napaka, getString(R.string.os_spor_napaka_prijava)); return@setOnClickListener }
                    kanal = SporocilaShramba.Kanal(id, "email", t, "", JSONObject().put("naslov", t).put("uporabnik", t).put("ponudnik", p.id)
                        .put("imap", iH).put("imap_vrata", iP).put("smtp", sH).put("smtp_vrata", sP))
                } else if (vrsta == "chatwoot") {
                    val u = url.text?.toString().orEmpty().trim().trimEnd('/')
                    skrivnost = zeton.text?.toString().orEmpty().trim()
                    val r = racun.text?.toString()?.trim()?.toIntOrNull()
                    if (!u.startsWith("https://")) { pokaziNapako(napaka, getString(R.string.os_spor_https)); return@setOnClickListener }
                    if (r == null || skrivnost.isEmpty()) { pokaziNapako(napaka, getString(R.string.os_spor_napaka_prijava)); return@setOnClickListener }
                    kanal = SporocilaShramba.Kanal(id, "chatwoot", u.removePrefix("https://"), "", JSONObject().put("url", u).put("account_id", r))
                } else if (vrsta == "api" && protokol == "matrix") {
                    var st = apiStreznik.text?.toString().orEmpty().trim().trimEnd('/')
                    if (st.isNotEmpty() && !st.contains("://")) st = "https://$st"
                    skrivnost = apiZeton.text?.toString().orEmpty().trim()
                    val lokalni = st.startsWith("http://localhost") || st.startsWith("http://127.")
                    if (!(st.startsWith("https://") || lokalni) || skrivnost.isEmpty()) { pokaziNapako(napaka, getString(R.string.os_spor_api_manjka_matrix)); return@setOnClickListener }
                    kanal = SporocilaShramba.Kanal(id, "matrix", apiIme.text?.toString().orEmpty().trim().ifBlank { st.removePrefix("https://") }, "", JSONObject().put("streznik", st))
                } else if (vrsta == "api") {
                    skrivnost = apiZeton.text?.toString().orEmpty().trim()
                    if (!skrivnost.contains(":") || skrivnost.length < 20) { pokaziNapako(napaka, getString(R.string.os_spor_api_manjka_telegram)); return@setOnClickListener }
                    kanal = SporocilaShramba.Kanal(id, "telegram_bot", apiIme.text?.toString().orEmpty().trim().ifBlank { "Telegram bot" }, "", JSONObject())
                } else return@setOnClickListener
                gumbDodaj.isEnabled = false
                pokaziNapako(napaka, getString(R.string.os_spor_povezujem), false)
                delavec.execute {
                    val ime = kanal.vrsta + ":" + kanal.id
                    val e = try {
                        if (!SporocilaSkrivnosti.shrani(this, ime, skrivnost)) throw SporocilaKanali.ManjkaSkrivnost()
                        if (kanal.vrsta == "email") SporocilaKanali.preveriEpostoPrilagodljivo(kanal.nastavitve, skrivnost) {
                            glavna.post { if (!unicena) pokaziNapako(napaka, getString(R.string.os_spor_poskus_brez_domene), false) }
                        }
                        else if (kanal.vrsta == "chatwoot") SporocilaKanali.preveriChatwoot(this, kanal)
                        else if (kanal.vrsta == "matrix") {
                            val uid = SporocilaLastniApi.preveriMatrix(this, kanal, skrivnost)
                            kanal.nastavitve.put("uporabnik", uid)
                            if (apiIme.text.isNullOrBlank()) kanal = kanal.copy(ime = uid)
                        } else {
                            val bot = SporocilaLastniApi.preveriTelegram(this, kanal, skrivnost)
                            kanal.nastavitve.put("bot", bot)
                            if (apiIme.text.isNullOrBlank() && bot.isNotBlank()) kanal = kanal.copy(ime = "Telegram @$bot")
                        }
                        shramba.dodajKanal(kanal.copy(stanje = "povezan"))
                        null
                    } catch (t: Throwable) { SporocilaSkrivnosti.pozabi(this, ime); t }
                    glavna.post {
                        if (unicena) return@post
                        if (e == null) {
                            okno.dismiss(); narisiKanale(); narisiSeznam(); sinhroniziraj()
                        } else {
                            gumbDodaj.isEnabled = true
                            pokaziNapako(napaka, besediloStanja(SporocilaKanali.razlog(e)))
                        }
                    }
                }
            }
        }
        Kontroler.pokazi(okno)
        okno.show()
    }

    private fun pokaziNapako(v: TextView, besedilo: String, napaka: Boolean = true) {
        v.text = besedilo; v.visibility = View.VISIBLE
        v.setTextColor(osBarva(if (napaka) R.color.os_opozorilo else R.color.os_umirjeno))
    }

    private fun gostitelj(vnos: String, privzeto: Int): Pair<String, Int> {
        val t = vnos.trim()
        val i = t.lastIndexOf(':')
        if (i > 0) t.substring(i + 1).toIntOrNull()?.let { return t.substring(0, i) to it }
        return t to privzeto
    }

    private fun urediKanal(k: SporocilaShramba.Kanal) {
        val stanje = besediloStanja(k.stanje)
        val geslo = polje(if (k.vrsta == "email") R.string.os_spor_geslo else R.string.os_spor_zeton,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val polja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            if (stanje.isNotEmpty()) addView(TextView(this@SporocilaActivity).apply {
                text = stanje; setTextColor(osBarva(R.color.os_opozorilo)); textSize = 13f
            })
            addView(geslo)
        }
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(k.ime)
            .setView(polja)
            .setPositiveButton(R.string.os_spor_novo_geslo) { _, _ ->
                val g = geslo.text?.toString().orEmpty()
                if (g.isNotEmpty()) {
                    SporocilaSkrivnosti.shrani(this, k.vrsta + ":" + k.id, g)
                    shramba.stanje(k.id, ""); narisiKanale(); sinhroniziraj()
                }
            }
            .setNeutralButton(R.string.os_spor_odstrani) { _, _ ->
                SporocilaSkrivnosti.pozabi(this, k.vrsta + ":" + k.id)
                shramba.odstraniKanal(k.id)
                if (izbran?.kanalId == k.id) izbran = null
                narisiKanale(); pokaziPogovor(null)
            }
            .setNegativeButton(R.string.os_preklici, null)
            .create()
        Kontroler.pokazi(okno)
        okno.show()
    }

    companion object {
        const val EXTRA_POGOVOR = "si.safeer.tv.os.sporocila.POGOVOR"
        /** Kanal pogovora iz EXTRA_POGOVOR (privzeto klepet Safeer Linka). */
        const val EXTRA_KANAL = "si.safeer.tv.os.sporocila.KANAL"
        /** Odpri izbiro naprave za novo sporocilo (ploscica Sporocil na domacem zaslonu). */
        const val EXTRA_NOVO = "si.safeer.tv.os.sporocila.NOVO"
        private const val OSVEZI_MS = 60_000L
    }
}
