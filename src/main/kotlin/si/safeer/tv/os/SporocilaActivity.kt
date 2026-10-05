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
        val id = namera?.getStringExtra(EXTRA_POGOVOR) ?: return
        shramba.pogovori().firstOrNull { it.kanalId == KlepetLinka.KANAL && it.id == id }?.let { pokaziPogovor(it) }
    }

    private val obKlepetu: () -> Unit = { glavna.post { if (!unicena) osveziPrikaz() } }
    private var napraveLinka: List<LinkOdjemalec.Naprava> = emptyList()
    private val linkPoslusalec = object : LinkOdjemalec.Poslusalec {
        override fun naStanje(povezan: Boolean, sporocilo: String) {}
        override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) {
            napraveLinka = naprave
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
        glava.addView(ImageView(this).apply { setImageResource(R.drawable.os_ikona_sporocila) },
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
            setOnKeyListener { _, koda, d ->
                if (koda == KeyEvent.KEYCODE_ENTER && d.isCtrlPressed && d.action == KeyEvent.ACTION_UP) { poslji(); true } else false
            }
        }
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
            try { getSystemService(android.app.NotificationManager::class.java)?.cancel(p.id.hashCode()) } catch (_: Throwable) {}
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

    private fun narisiSporocila() {
        val p = izbran ?: return
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
                // Dotik: dolg pritisk kopira (prejeto besedilo je navadno namenjeno drugi aplikaciji).
                setOnLongClickListener { dejanjaSporocila(s.besedilo.trim()); true }
                setTextColor(osBarva(if (ven) R.color.os_mint_temna else R.color.os_besedilo))
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat(); setColor(osBarva(if (ven) R.color.os_mint else R.color.os_ozadje))
                    if (zadetek) setStroke(dp(2), osBarva(R.color.os_opozorilo))
                }
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

    /** Dolg pritisk na sporocilo: kopiraj; ena sama povezava ponudi se »Odpri povezavo«. */
    private fun dejanjaSporocila(besedilo: String) {
        if (besedilo.isEmpty()) return
        if (!OsPravila.jePovezava(besedilo)) { Odlozisce.kopiraj(this, besedilo); return }
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setItems(arrayOf(getString(R.string.ui_share_open_link), getString(R.string.os_bliznjica_kopiraj))) { _, i ->
                if (i == 1) Odlozisce.kopiraj(this, besedilo)
                else try {
                    startActivity(Brskalnik.namera(this).setAction(android.content.Intent.ACTION_VIEW)
                        .setData(android.net.Uri.parse(besedilo)))
                } catch (_: Exception) { Toast.makeText(this, R.string.os_odpri_ni_aplikacije, Toast.LENGTH_SHORT).show() }
            }
            .create()
        Kontroler.pokazi(okno)
        okno.show()
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
        private const val OSVEZI_MS = 60_000L
    }
}
