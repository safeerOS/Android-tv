package si.safeer.tv.os

import si.safeer.tv.R

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale

/**
 * Aplikacije: vse, kar lahko uporabnik odpre - aplikacije televizorja, spletne aplikacije in
 * programe z racunalnika - na enem zaslonu, z istimi skupinami in istim iskanjem.
 *
 * Isti zaslon je tudi "Programi" ([EXTRA_VIR] = racunalnik): programi in aplikacije z VSEH
 * povezanih naprav (racunalniki, tablica, telefon, drug televizor), zgoraj izbira naprave. Seznam
 * pride po Safeer Linku (`apps.list`, Protocol v1 - isti ukaz na racunalniku in na Androidu), zagon
 * gre nazaj po isti poti (`apps.launch`) na napravo, ki ima program.
 *
 * Skupine (igre, pisarna, splet, predstavnost, programiranje, ucenje, orodja) so iste, kot jih
 * uporabnik pozna iz menija svojega namizja; aplikacijam televizorja jih pove sistem, spletnim jih
 * dolocimo po naslovu. Iskanje po imenu seznam ozi ze med tipkanjem.
 *
 * Program se odpre na zaslonu racunalnika. Kadar racunalnik svoj zaslon deli, Safeer OS takoj
 * preklopi nanj, da uporabnik to, kar je odprl, tudi vidi in upravlja; kadar ga ne deli, to
 * posteno pove in nicesar ne obljublja.
 */
class AplikacijeHostaActivity : OsActivity(), LinkOdjemalec.Poslusalec {

    private lateinit var mreza: GridView
    private lateinit var naslov: TextView
    private lateinit var nadnaslov: TextView
    private lateinit var znacka: TextView
    private lateinit var sporocilo: TextView
    private lateinit var iskanje: EditText
    private lateinit var skupineVrsta: LinearLayout
    private lateinit var skupineDrsnik: HorizontalScrollView
    private var viriVrsta: LinearLayout? = null

    private val link by lazy { LinkUpravitelj.pridobi(this) }
    private val prilagojevalnik = Prilagojevalnik()

    /** Kaj ta zaslon kaze: "vse", "tv", "splet" ali "racunalnik" (privzeto, kot doslej). */
    private var nacin = AppVir.RACUNALNIK.kljuc
    /** Izbrani vir v nacinu "vse"; null = vsi. */
    private var izbraniVir: AppVir? = null

    private var krajevni: List<SafeerApp> = emptyList()
    private var oddaljeni: List<SafeerApp> = emptyList()
    private var vidni: List<SafeerApp> = emptyList()
    /** Ikone programov racunalnika (PNG, base64) - za kartico na domacem zaslonu. */
    private val ikonePng = HashMap<String, String>()
    private var izbranaSkupina = ""          // prazno = vse skupine
    private var racunalnik: LinkOdjemalec.Naprava? = null
    /** Naprave, s katerih seznam se nalaga ali je nalozen (id), in njihova imena ter vrsta. */
    private val nalagam = HashSet<String>()
    private val nalozene = HashSet<String>()
    private val imenaNaprav = LinkedHashMap<String, String>()
    private val androidNaprave = HashSet<String>()
    /** Izbrana naprava v vrsti naprav; null = vse. */
    private var izbranaNaprava: String? = null

    /** Koliko programov (z ikonami) prosimo naenkrat; en kos mora ostati krepko pod 256 kB. */
    private val STRAN = 18

    private fun zVirom(v: AppVir) = nacin == "vse" || nacin == v.kljuc

    /** Vse aplikacije tega zaslona; v nacinu "vse" po abecedi, sicer v vrstnem redu vira. */
    private val programi: List<SafeerApp>
        get() {
            val vsi = krajevni + oddaljeni
            return if (nacin == "vse") vsi.sortedBy { it.ime.lowercase(Locale.getDefault()) } else vsi
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.os_activity_programi)
        nacin = intent.getStringExtra(EXTRA_VIR) ?: AppVir.RACUNALNIK.kljuc
        mreza = findViewById(R.id.mreza)
        naslov = findViewById(R.id.naslov)
        nadnaslov = findViewById(R.id.nadnaslov)
        znacka = findViewById(R.id.racunalnik)
        sporocilo = findViewById(R.id.sporocilo)
        iskanje = findViewById(R.id.iskanje)
        skupineVrsta = findViewById(R.id.skupine)
        skupineDrsnik = findViewById(R.id.skupineDrsnik)
        when (nacin) {
            AppVir.RACUNALNIK.kljuc -> {
                nadnaslov.text = getString(R.string.os_programi)
                naslov.text = getString(R.string.os_programi_naslov)
            }
            else -> {
                nadnaslov.text = getString(R.string.os_vse_nadnaslov)
                naslov.text = getString(R.string.os_vse_naslov)
                findViewById<ImageView>(R.id.glavaIkona)?.setImageResource(R.drawable.os_ikona_mreza)
                znacka.visibility = View.GONE
            }
        }
        mreza.adapter = prilagojevalnik
        // Izbrani program je bil komaj viden (bled izbor) in zgornja vrsta je pod glavo bledela, kot
        // da je odrezana. Zdaj: svetel okvir cez kartico, kartica se rahlo poveca, brez bledenja.
        val d = resources.displayMetrics.density
        mreza.selector = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 14 * d
            setColor(android.graphics.Color.parseColor("#222DD4BF"))
            setStroke((3 * d).toInt(), android.graphics.Color.parseColor("#2DD4BF"))
        }
        mreza.setDrawSelectorOnTop(true)
        mreza.isVerticalFadingEdgeEnabled = false
        mreza.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            private var prejsnji: View? = null
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, i: Int, id: Long) {
                prejsnji?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(100)?.start()
                v?.animate()?.scaleX(1.05f)?.scaleY(1.05f)?.setDuration(100)?.start()
                prejsnji = v
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {
                prejsnji?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(100)?.start()
                prejsnji = null
            }
        }
        mreza.setOnFocusChangeListener { _, ima ->
            if (!ima) mreza.selectedView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(100)?.start()
            else mreza.selectedView?.animate()?.scaleX(1.05f)?.scaleY(1.05f)?.setDuration(100)?.start()
        }
        // Vrsta skupin in daljinec: skupina sledi fokusu samo, ko se uporabnik premika LEVO in
        // DESNO po vrsti. Ce pride v vrsto od spodaj (iz programov) ali od iskanja, fokus pristane
        // na izbrani skupini in seznam ostane, kakrsen je - sicer bi ze en pritisk Gor sredi
        // brskanja zamenjal skupino pod prstom.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { stari, novi ->
            if (novi == null || novi.parent !== skupineVrsta) return@addOnGlobalFocusChangeListener
            val poVrsti = stari != null && stari.parent === skupineVrsta
            if (poVrsti) {
                (novi.tag as? String)?.let { izberiSkupino(it, novi) }
            } else {
                val izbrana = (0 until skupineVrsta.childCount).map { skupineVrsta.getChildAt(it) }
                    .firstOrNull { it.isActivated }
                if (izbrana != null && izbrana !== novi) izbrana.post { izbrana.requestFocus() }
            }
        }
        mreza.setOnItemClickListener { _, _, i, _ -> vidni.getOrNull(i)?.let { zazeni(it) } }
        // Dolg pritisk OK: na domaci zaslon ali z njega, program z racunalnika pa se da tudi zapreti.
        mreza.setOnItemLongClickListener { _, _, i, _ ->
            vidni.getOrNull(i)?.let { moznosti(it) }
            true
        }
        pripraviIskanje()
        // Ob vstopu mora biti viden seznam, ne tipkovnica: ta se odpre sele, ko uporabnik izbere
        // iskalno polje in pritisne OK. (Mreza je ob vstopu se prazna, zato fokus pristane na
        // iskalnem polju - brez tega bi televizor takoj odprl tipkovnico cez pol zaslona.)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }

    override fun onStart() {
        super.onStart()
        Ozadje.uporabi(this, findViewById(R.id.koren))
        link.dodaj(this)
        naloziKrajevne()
        if (zVirom(AppVir.RACUNALNIK)) nalozi()
    }

    override fun onStop() {
        link.odstrani(this)
        super.onStop()
    }

    /** Aplikacije televizorja in spletne aplikacije: na tej napravi, zato takoj. */
    private fun naloziKrajevne() {
        val novi = ArrayList<SafeerApp>()
        if (zVirom(AppVir.TV)) novi.addAll(SafeerAppi.naTelevizorju(this))
        if (zVirom(AppVir.SPLET)) novi.addAll(SafeerAppi.spletne(this))
        val prvi = programi.isEmpty() && novi.isNotEmpty()
        krajevni = novi
        narisiSkupine()
        osveziSeznam()
        if (prvi) { mreza.requestFocus(); mreza.setSelection(0) }
    }

    /**
     * Naprave, katerih programe ali aplikacije lahko pokazemo: racunalnik, ki programe deli (zmoznost
     * "apps"), in naprave z daljincem ("remote" - Android, isti ukaz `apps.list`). Brez te naprave
     * same in brez drugih procesov na njej (isti naslov IP: brskalnik na tem televizorju) - te
     * aplikacije so ze med aplikacijami televizorja.
     */
    private fun napraveSProgrami(): List<LinkOdjemalec.Naprava> {
        val jaz = Identiteta.id(this)
        val mojNaslov = try { si.safeer.tv.cast.PridruzitevSredisca.krajevniNaslov() } catch (_: Throwable) { null }
        // Sredisce vsakemu odjemalcu na svoji napravi pripise 127.0.0.1: kadar sredisce tece tu, so to
        // nasi procesi (brskalnik na tem televizorju) - njegove aplikacije so ze med aplikacijami televizorja.
        val loopback = setOf("127.0.0.1", "::1", "localhost")
        return link.naprave.filter {
            it.id != jaz && (it.zmoznosti.contains("apps") || it.zmoznosti.contains("remote")) &&
                (mojNaslov == null || it.naslov.isBlank() || it.naslov != mojNaslov) &&
                !(link.odjemalec.srediceJeTu && it.naslov in loopback)
        }
    }

    private fun nalozi() {
        val naprave = napraveSProgrami()
        if (naprave.isEmpty()) {
            // Na zaslonu vseh aplikacij naprava ni nujna: brez nje so tu ostale.
            if (nacin != AppVir.RACUNALNIK.kljuc || oddaljeni.isNotEmpty()) return
            znacka.visibility = View.GONE
            pokaziOrodja(false)
            pokaziSporocilo(getString(
                if (!link.povezan) R.string.os_programi_ni_povezave else R.string.os_programi_ni_racunalnika))
            return
        }
        racunalnik = naprave.firstOrNull { it.zmoznosti.contains("apps") } ?: naprave.first()
        for (r in naprave) {
            if (r.id in nalagam || r.id in nalozene) continue
            imenaNaprav[r.id] = r.ime.ifBlank { r.id }
            if (!r.zmoznosti.contains("apps")) androidNaprave.add(r.id)
            if (programi.isEmpty()) pokaziSporocilo(getString(R.string.os_programi_nalagam))
            naloziStran(r, 0)
        }
        if (nacin == AppVir.RACUNALNIK.kljuc) {
            znacka.text = if (imenaNaprav.size == 1) imenaNaprav.values.first()
                else getString(R.string.os_programi_naprav, imenaNaprav.size)
            znacka.visibility = View.VISIBLE
        }
    }

    /**
     * Seznam pride po kosih. Ikone so slike in racunalnik jih ima lahko sto: celoten odgovor bi
     * presegel omejitev sporocila v Linku (256 kB) in bi tiho padel skozi - uporabnik bi cakal v
     * prazno. Zato prosimo za [STRAN] programov naenkrat in jih sproti dodajamo v mrezo. Android
     * vrne cel seznam naenkrat (ikone so majhne, 48 px WebP).
     */
    private fun naloziStran(r: LinkOdjemalec.Naprava, od: Int) {
        nalagam.add(r.id)
        val android = r.id in androidNaprave
        val zahteva = JSONObject().put("offset", od).put("limit", STRAN).put("icons", true)
        link.ukaz(r.id, "apps.list", zahteva, 25_000, LinkOdjemalec.Odgovor { izid, napaka ->
            nalagam.remove(r.id)
            if (isFinishing) return@Odgovor
            if (izid == null || !izid.optBoolean("ok")) {
                nalozene.add(r.id)
                // Napaka ene naprave ne sme prekriti ostalih.
                if (oddaljeni.none { it.racunalnik == r.id }) {
                    imenaNaprav.remove(r.id)
                    koncajSSporocilom(getString(R.string.os_programi_napaka,
                        izid?.optString("message").orEmpty().ifBlank { napaka.orEmpty() }))
                }
                return@Odgovor
            }
            val podatki = izid.optJSONObject("data") ?: JSONObject()
            if (!podatki.optBoolean("enabled", false)) {
                nalozene.add(r.id)
                imenaNaprav.remove(r.id)
                koncajSSporocilom(getString(R.string.os_programi_izklopljeno, r.ime.ifBlank { r.id })); return@Odgovor
            }
            val polje = podatki.optJSONArray("items")
            val novi = ArrayList<SafeerApp>(oddaljeni)
            if (polje != null) for (i in 0 until polje.length()) {
                val o = polje.optJSONObject(i) ?: continue
                val id = o.optString("id"); if (id.isBlank()) continue
                // Racunalnik: icon_png (base64); Android: icon (data URL, WebP).
                val png = o.optString("icon_png").ifBlank { o.optString("icon").substringAfter("base64,", "") }
                val kljuc = "racunalnik:" + r.id + ":" + id
                if (novi.any { it.kljuc == kljuc }) continue
                if (png.isNotBlank()) ikonePng[kljuc] = png
                val skupina = o.optString("group").ifBlank { if (android) skupinaPaketa(id) else DRUGO }
                novi.add(SafeerApp(kljuc, o.optString("name").ifBlank { id }, o.optString("comment"),
                    skupina, ikona(png), AppVir.RACUNALNIK, id, r.id))
            }
            val prvi = programi.isEmpty() && novi.isNotEmpty()
            oddaljeni = novi
            narisiSkupine()
            osveziSeznam()
            if (prvi) { mreza.requestFocus(); mreza.setSelection(0) }
            val skupaj = podatki.optInt("total", novi.size)
            val prejeto = polje?.length() ?: 0
            val tega = novi.count { it.racunalnik == r.id }
            when {
                !android && tega < skupaj && prejeto > 0 -> naloziStran(r, od + prejeto)
                else -> {
                    nalozene.add(r.id)
                    if (programi.isEmpty() && nalagam.isEmpty()) pokaziSporocilo(getString(R.string.os_programi_prazno))
                }
            }
        })
    }

    /** Napaka sredi nalaganja: ce smo kaj ze pokazali, pustimo to in samo povemo, kaj je slo narobe. */
    private fun koncajSSporocilom(b: String) {
        // Na zaslonu vseh aplikacij so ostale se tu; napaka racunalnika jih ne sme prekriti.
        if (nacin == AppVir.RACUNALNIK.kljuc || programi.isEmpty()) pokaziSporocilo(b)
    }

    // ------------------------------------------------------------------ skupine, viri in iskanje

    /** Skupine v vrstnem redu, kot jih uporabnik pricakuje; kar racunalnik doda novega, pade v "drugo". */
    private val vrstniRedSkupin = SafeerAppi.SKUPINE

    private fun imeSkupine(kljuc: String): String = when (kljuc) {
        "igre" -> getString(R.string.os_skupina_igre)
        "pisarna" -> getString(R.string.os_skupina_pisarna)
        "splet" -> getString(R.string.os_skupina_splet)
        "predstavnost" -> getString(R.string.os_skupina_predstavnost)
        "programiranje" -> getString(R.string.os_skupina_programiranje)
        "ucenje" -> getString(R.string.os_skupina_ucenje)
        "orodja" -> getString(R.string.os_skupina_orodja)
        else -> getString(R.string.os_skupina_drugo)
    }

    private fun imeVira(v: AppVir?): String = when (v) {
        null -> getString(R.string.os_vir_vse)
        AppVir.TV -> getString(R.string.os_vir_tv)
        AppVir.SPLET -> getString(R.string.os_vir_splet)
        AppVir.RACUNALNIK -> getString(R.string.os_vir_racunalnik)
    }

    /** Kljuci priljubljenih z racunalnika (zvezdica); preberemo jih enkrat na izris, ne za vsako kartico. */
    private var priljubljeni: Set<String> = emptySet()

    private fun jePriljubljena(p: SafeerApp): Boolean =
        if (p.vir == AppVir.TV) Priljubljene.je(this, p.cilj) else p.kljuc in priljubljeni

    private fun poViru(): List<SafeerApp> {
        val poVir = izbraniVir?.let { v -> programi.filter { it.vir == v } } ?: programi
        return izbranaNaprava?.let { n -> poVir.filter { it.racunalnik == n } } ?: poVir
    }

    /**
     * Gumbi skupin. Pokazemo samo tiste, ki res kaj vsebujejo - prazna skupina bi obljubljala
     * programe, ki jih ni. Ob vsaki je stevilo, da se takoj vidi, kje je kaj.
     */
    private fun narisiSkupine() {
        narisiVire()
        val seznam = poViru()
        val stevila = LinkedHashMap<String, Int>()
        for (kljuc in vrstniRedSkupin) {
            val n = seznam.count { it.skupina == kljuc }
            if (n > 0) stevila[kljuc] = n
        }
        // Ena sama skupina ni razvrstitev: takrat gumbov ne kazemo, ker ne povedo nicesar.
        val kaziSkupine = stevila.size > 1
        pokaziOrodja(programi.isNotEmpty())
        skupineDrsnik.visibility = if (kaziSkupine) View.VISIBLE else View.GONE
        if (!kaziSkupine) { izbranaSkupina = ""; return }
        if (izbranaSkupina.isNotEmpty() && !stevila.containsKey(izbranaSkupina)) izbranaSkupina = ""
        skupineVrsta.removeAllViews()
        skupineVrsta.addView(gumbSkupine("", getString(R.string.os_skupina_vse), seznam.size))
        for ((kljuc, n) in stevila) skupineVrsta.addView(gumbSkupine(kljuc, imeSkupine(kljuc), n))
    }

    /**
     * Viri na zaslonu vseh aplikacij (Vse, Televizor, Splet, Racunalnik) - v glavi, desno, samo
     * kadar je virov vec. Vir se zamenja z OK (ne ze s premikom), ker zamenja celo mrezo.
     */
    private fun narisiVire() {
        if (nacin == AppVir.RACUNALNIK.kljuc) { narisiNaprave(); return }
        if (nacin != "vse") return
        val prisotni = AppVir.values().filter { v -> programi.any { it.vir == v } }
        val vrsta = findViewById<LinearLayout>(R.id.naprave)
        val drsnik = findViewById<View>(R.id.napraveDrsnik)
        viriVrsta = vrsta
        vrsta.removeAllViews()
        if (prisotni.size < 2) { drsnik.visibility = View.GONE; izbraniVir = null; return }
        drsnik.visibility = View.VISIBLE
        if (izbraniVir != null && izbraniVir !in prisotni) izbraniVir = null
        for (v in listOf<AppVir?>(null) + prisotni) {
            val n = if (v == null) programi.size else programi.count { it.vir == v }
            val g = gumb(getString(R.string.os_skupina_s_stevilom, imeVira(v), n), v == izbraniVir)
            g.setOnClickListener {
                if (izbraniVir == v) return@setOnClickListener
                izbraniVir = v
                izbranaSkupina = ""
                narisiSkupine()
                osveziSeznam()
                viriVrsta?.let { r -> (0 until r.childCount).map { r.getChildAt(it) }.firstOrNull { it.isActivated } }
                    ?.requestFocus()
            }
            vrsta.addView(g)
        }
    }

    /**
     * Naprave na zaslonu Programi (Vse, racunalnik, tablica ...) - v glavi, desno, kadar je naprav vec.
     * Naprava se zamenja z OK, ker zamenja celo mrezo.
     */
    private fun narisiNaprave() {
        val prisotne = imenaNaprav.keys.filter { id -> oddaljeni.any { it.racunalnik == id } }
        val vrsta = findViewById<LinearLayout>(R.id.naprave)
        val drsnik = findViewById<View>(R.id.napraveDrsnik)
        viriVrsta = vrsta
        vrsta.removeAllViews()
        if (prisotne.size < 2) { drsnik.visibility = View.GONE; izbranaNaprava = null; return }
        znacka.visibility = View.GONE
        drsnik.visibility = View.VISIBLE
        if (izbranaNaprava != null && izbranaNaprava !in prisotne) izbranaNaprava = null
        for (id in listOf<String?>(null) + prisotne) {
            val n = if (id == null) oddaljeni.size else oddaljeni.count { it.racunalnik == id }
            val ime = if (id == null) getString(R.string.os_vir_vse) else imenaNaprav[id].orEmpty()
            val g = gumb(getString(R.string.os_skupina_s_stevilom, ime, n), id == izbranaNaprava)
            g.setOnClickListener {
                if (izbranaNaprava == id) return@setOnClickListener
                izbranaNaprava = id
                izbranaSkupina = ""
                narisiSkupine()
                osveziSeznam()
                viriVrsta?.let { r -> (0 until r.childCount).map { r.getChildAt(it) }.firstOrNull { it.isActivated } }
                    ?.requestFocus()
            }
            g.setOnFocusChangeListener { _, ima -> if (ima) (drsnik as HorizontalScrollView).requestChildRectangleOnScreen(g, android.graphics.Rect(0, 0, g.width, g.height), false) }
            vrsta.addView(g)
        }
    }

    private fun gumb(besedilo: String, aktiven: Boolean): TextView {
        val t = TextView(this)
        t.text = besedilo
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        t.setTextColor(getColorStateList(R.color.os_skupina_besedilo))
        t.setBackgroundResource(R.drawable.os_skupina)
        t.gravity = Gravity.CENTER
        val vodoravno = (16 * resources.displayMetrics.density).toInt()
        val navpicno = (9 * resources.displayMetrics.density).toInt()
        t.setPadding(vodoravno, navpicno, vodoravno, navpicno)
        t.isFocusable = true
        t.isActivated = aktiven
        val mere = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        mere.marginEnd = (8 * resources.displayMetrics.density).toInt()
        t.layoutParams = mere
        return t
    }

    private fun gumbSkupine(kljuc: String, ime: String, koliko: Int): View {
        val t = gumb(getString(R.string.os_skupina_s_stevilom, ime, koliko), kljuc == izbranaSkupina)
        t.setOnClickListener { izberiSkupino(kljuc, t) }
        t.tag = kljuc
        // Skupina, ki ima fokus, mora ostati vidna, tudi ko jih je vec, kot gre na zaslon.
        t.setOnFocusChangeListener { _, ima ->
            if (ima) skupineDrsnik.post { skupineDrsnik.requestChildRectangleOnScreen(t,
                android.graphics.Rect(0, 0, t.width, t.height), false) }
        }
        return t
    }

    /** Skupina, ki jo uporabnik gleda: oznaci gumb in prerise seznam programov. */
    private fun izberiSkupino(kljuc: String, gumb: View) {
        if (izbranaSkupina == kljuc) return
        izbranaSkupina = kljuc
        for (i in 0 until skupineVrsta.childCount) {
            skupineVrsta.getChildAt(i).isActivated = skupineVrsta.getChildAt(i) === gumb
        }
        osveziSeznam()
    }

    private fun pripraviIskanje() {
        iskanje.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun afterTextChanged(s: Editable?) { osveziSeznam() }
        })
        // Daljinec: OK na iskalnem polju odpre tipkovnico televizorja, Dol gre na seznam.
        iskanje.setOnKeyListener { _, koda, dogodek ->
            if (dogodek.action == KeyEvent.ACTION_DOWN &&
                (koda == KeyEvent.KEYCODE_DPAD_CENTER || koda == KeyEvent.KEYCODE_ENTER)) {
                odpriTipkovnico(); true
            } else false
        }
        iskanje.setOnClickListener { odpriTipkovnico() }
        // Fokus sam po sebi tipkovnice ne odpre; odpre jo sele OK (ali klik).
        iskanje.showSoftInputOnFocus = false
    }

    private fun odpriTipkovnico() {
        iskanje.requestFocus()
        val upravitelj = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        upravitelj.showSoftInput(iskanje, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun pokaziOrodja(vidno: Boolean) {
        findViewById<View>(R.id.orodja).visibility = if (vidno) View.VISIBLE else View.GONE
    }

    /**
     * Iskanje po imenu. Sumnike poenostavimo, da "citalnik" najde "Čitalnik" - na daljincu je
     * sumnik delo, ki ga uporabniku ni treba opraviti.
     */
    private fun poenostavi(besedilo: String): String {
        val sb = StringBuilder(besedilo.length)
        for (z in besedilo.lowercase(Locale.getDefault())) {
            sb.append(when (z) {
                'č', 'ć' -> 'c'
                'š' -> 's'
                'ž' -> 'z'
                'đ' -> 'd'
                else -> z
            })
        }
        return sb.toString()
    }

    private fun osveziSeznam() {
        priljubljeni = SafeerAppi.priljubljeni(this).map { it.kljuc }.toSet()
        val iskano = poenostavi(iskanje.text?.toString().orEmpty().trim())
        vidni = poViru().filter { p ->
            (izbranaSkupina.isEmpty() || p.skupina == izbranaSkupina) &&
                (iskano.isEmpty() || poenostavi(p.ime).contains(iskano) || poenostavi(p.opis).contains(iskano))
        }
        prilagojevalnik.notifyDataSetChanged()
        when {
            vidni.isNotEmpty() -> skrijSporocilo()
            programi.isEmpty() -> { }               // sporocilo o nalaganju ali napaki ostane
            iskano.isNotEmpty() -> pokaziSporocilo(getString(R.string.os_programi_ni_zadetka, iskanje.text.toString().trim()))
            else -> pokaziSporocilo(getString(R.string.os_programi_prazno))
        }
    }

    /** Ikona programa: PNG v base64 iz odgovora, oblikovan enako kot ikone spletnih aplikacij. */
    private fun ikona(base64: String): android.graphics.drawable.Drawable? {
        if (base64.isBlank()) return null
        return try {
            val bajti = Base64.decode(base64, Base64.DEFAULT)
            val slika = VarnaSlika.izBajtov(bajti) ?: return null
            SpletneAplikacije.ikonaIzSlike(this, slika)
        } catch (_: Throwable) { null }
    }

    // ------------------------------------------------------------------ zagon in moznosti

    /** Dolg pritisk: na domaci zaslon ali z njega; program z racunalnika se da tudi zapreti. */
    private fun moznosti(p: SafeerApp) {
        val dejanja = ArrayList<Pair<String, () -> Unit>>()
        val na = SafeerAppi.jePriljubljena(this, p)
        if (p.vir != AppVir.SPLET) dejanja.add(getString(if (na) R.string.os_priljubljen_odstrani else R.string.os_priljubljen_dodaj) to {
            val png = ikonePng[p.kljuc]?.let { try { Base64.decode(it, Base64.DEFAULT) } catch (_: Throwable) { null } }
            val zdaj = SafeerAppi.preklopi(this, p, png)
            priljubljeni = SafeerAppi.priljubljeni(this).map { it.kljuc }.toSet()
            prilagojevalnik.notifyDataSetChanged()
            Toast.makeText(this, getString(
                if (zdaj) R.string.os_aplikacije_dodana else R.string.os_aplikacije_odstranjena, p.ime),
                Toast.LENGTH_SHORT).show()
        })
        if (p.vir == AppVir.RACUNALNIK && p.racunalnik !in androidNaprave) dejanja.add(getString(R.string.os_program_zapri) to { zapri(p) })
        if (dejanja.isEmpty()) return
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(p.ime)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    private fun zapri(p: SafeerApp) {
        link.ukaz(p.racunalnik, "apps.close", JSONObject().put("app", p.cilj), 10_000,
            LinkOdjemalec.Odgovor { izid, napaka ->
                if (isFinishing) return@Odgovor
                val ok = izid?.optBoolean("ok") == true
                val sporocilo = when {
                    ok -> getString(R.string.os_program_zaprt, p.ime)
                    izid?.optString("code") == "ne_tece" -> getString(R.string.os_program_ne_tece, p.ime)
                    else -> izid?.optString("message").orEmpty().ifBlank { napaka.orEmpty() }
                }
                if (sporocilo.isNotBlank()) Toast.makeText(this, sporocilo, Toast.LENGTH_LONG).show()
            })
    }

    private fun zazeni(p: SafeerApp) {
        when (p.vir) {
            AppVir.TV -> p.namera?.let { odpriVarno(Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), p.ime) }
            AppVir.SPLET -> {
                Nadaljuj.zapisi(this, Nadaljuj.Vnos(vrsta = Nadaljuj.SPLETNA, ime = p.ime, url = p.cilj))
                odpriVarno(Brskalnik.spletnaAplikacija(this, p.cilj, p.ime), p.ime)
            }
            AppVir.RACUNALNIK -> zazeniProgram(p)
        }
    }

    /** Odpiranje, ki ne utihne: ce ne gre, povemo zakaj in ponudimo ponovni poskus. */
    private fun odpriVarno(namera: Intent, ime: String) {
        try {
            startActivity(namera)
        } catch (e: Throwable) {
            val razlog = when (e) {
                is android.content.ActivityNotFoundException -> getString(R.string.os_odpri_ni_aplikacije)
                is SecurityException -> getString(R.string.os_odpri_ni_dovoljenja)
                else -> e.message.orEmpty().ifBlank { e.javaClass.simpleName }
            }
            android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(ime)
                .setMessage(getString(R.string.os_odpri_napaka, razlog))
                .setPositiveButton(getString(R.string.os_poskusi_znova)) { _, _ -> odpriVarno(namera, ime) }
                .setNegativeButton(getString(R.string.os_preklici), null)
                .let { Kontroler.pokazi(it.show()) }
        }
    }

    /**
     * Zagon programa. Program se odpre na racunalniku; kadar racunalnik deli svoj zaslon, takoj
     * preklopimo nanj, da uporabnik to, kar je odprl, vidi in upravlja tu.
     */
    private fun zazeniProgram(p: SafeerApp) {
        val r = link.naprave.firstOrNull { it.id == p.racunalnik } ?: racunalnik ?: return
        if (r.id in androidNaprave) { zazeniNaNapravi(p, r); return }
        val zaslon = link.naprave.any { it.id == r.id && it.zmoznosti.contains("desktop") }
        link.ukaz(r.id, "apps.launch", JSONObject().put("app", p.cilj), 10_000, LinkOdjemalec.Odgovor { izid, napaka ->
            if (isFinishing) return@Odgovor
            if (izid?.optBoolean("ok") != true) {
                Toast.makeText(this, getString(R.string.os_programi_napaka,
                    izid?.optString("message").orEmpty().ifBlank { napaka }), Toast.LENGTH_LONG).show()
                return@Odgovor
            }
            val vnos = Nadaljuj.Vnos(vrsta = Nadaljuj.PROGRAM, ime = p.ime,
                racunalnik = r.id, program = p.cilj, igra = p.skupina == "igre")
            Nadaljuj.zapisi(this, vnos)
            ikonePng[p.kljuc]?.let { png ->
                try { Nadaljuj.shraniIkono(this, vnos, Base64.decode(png, Base64.DEFAULT)) } catch (_: Throwable) { }
            }
            if (zaslon) {
                Toast.makeText(this, getString(R.string.os_programi_odpiram, p.ime), Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, ZaslonActivity::class.java)
                    .putExtra(DatotekeActivity.EXTRA_RACUNALNIK, r.id)
                    .putExtra(ZaslonActivity.EXTRA_ZASLON, "apps")
                    .putExtra(ZaslonActivity.EXTRA_PROGRAM, p.cilj)
                    .putExtra(ZaslonActivity.EXTRA_IGRA, p.skupina == "igre"))
            } else {
                Toast.makeText(this, getString(R.string.os_programi_zagnan, p.ime,
                    r.ime.ifBlank { r.id }), Toast.LENGTH_LONG).show()
            }
        })
    }

    /** Aplikacija na tablici, telefonu ali drugem televizorju: odpre se na tisti napravi. */
    private fun zazeniNaNapravi(p: SafeerApp, r: LinkOdjemalec.Naprava) {
        link.ukaz(r.id, "apps.launch", JSONObject().put("app", p.cilj), 10_000, LinkOdjemalec.Odgovor { izid, napaka ->
            if (isFinishing) return@Odgovor
            if (izid?.optBoolean("ok") != true) {
                Toast.makeText(this, getString(R.string.os_programi_napaka,
                    izid?.optString("message").orEmpty().ifBlank { napaka }), Toast.LENGTH_LONG).show()
                return@Odgovor
            }
            Nadaljuj.zapisi(this, Nadaljuj.Vnos(vrsta = Nadaljuj.PROGRAM, ime = p.ime, racunalnik = r.id, program = p.cilj))
            Toast.makeText(this, getString(R.string.os_programi_zagnan_naprava, p.ime, r.ime.ifBlank { r.id }), Toast.LENGTH_LONG).show()
        })
    }

    /**
     * Nazaj najprej pocisti iskanje, skupino in vir: uporabnik, ki je iskal, si najbrz zeli nazaj
     * cel seznam, ne ven z zaslona. Drugi Nazaj zapre zaslon kot obicajno.
     */
    override fun onBackPressed() {
        if (iskanje.text?.isNotEmpty() == true || izbranaSkupina.isNotEmpty() || izbraniVir != null || izbranaNaprava != null) {
            iskanje.setText("")
            izbranaSkupina = ""
            izbraniVir = null
            izbranaNaprava = null
            narisiSkupine()
            osveziSeznam()
            if (vidni.isNotEmpty()) { mreza.requestFocus(); mreza.setSelection(0) }
            return
        }
        super.onBackPressed()
    }

    /** Plosek: X odpre iskanje, da uporabniku ni treba loviti polja s palico. */
    override fun plosekDejanje(koda: Int): Boolean {
        if (koda == KeyEvent.KEYCODE_BUTTON_X && iskanje.visibility == View.VISIBLE) {
            odpriTipkovnico(); return true
        }
        return false
    }

    private fun pokaziSporocilo(b: String) { sporocilo.text = b; sporocilo.visibility = View.VISIBLE }
    private fun skrijSporocilo() { sporocilo.visibility = View.GONE }

    // ------------------------------------------------------------------ Link

    override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) {
        // Nova naprava v Linku (npr. tablica se je prizgala): dodamo njene aplikacije.
        if (zVirom(AppVir.RACUNALNIK)) nalozi()
    }

    override fun naStanje(povezan: Boolean, sporocilo: String) { }
    override fun naNaslov(url: String, naslov: String, od: String) { }
    override fun naBesedilo(besedilo: String, od: String) { }
    override fun naZavrnitev() { }

    private inner class Prilagojevalnik : BaseAdapter() {
        override fun getCount(): Int = vidni.size
        override fun getItem(i: Int): Any = vidni[i]
        override fun getItemId(i: Int): Long = i.toLong()
        override fun getView(i: Int, star: View?, roditelj: ViewGroup): View {
            val v = star ?: LayoutInflater.from(this@AplikacijeHostaActivity)
                .inflate(R.layout.os_kartica_program, roditelj, false)
            val p = vidni[i]
            val ikona = v.findViewById<ImageView>(R.id.ikona)
            if (p.ikona != null) ikona.setImageDrawable(p.ikona)
            else ikona.setImageResource(if (p.vir == AppVir.RACUNALNIK) R.drawable.os_ikona_racunalnik else R.drawable.os_ikona_mreza)
            // Zvezdica pove, da je na domacem zaslonu; z dveh metrov je vidna takoj.
            v.findViewById<TextView>(R.id.ime).text =
                if (p.vir != AppVir.SPLET && jePriljubljena(p)) "★ " + p.ime else p.ime
            return v
        }
    }

    companion object {
        /** Kaj zaslon kaze: "vse", "tv", "splet" ali "racunalnik" (privzeto). */
        const val EXTRA_VIR = "vir"
        /** Oznaka skupine za program, ki svoje kategorije nima (ista beseda kot v core/link_programi.py). */
        private const val DRUGO = "drugo"

        /** Groba skupina aplikacije z Androida po imenu paketa (tuja naprava kategorije ne poslje). */
        fun skupinaPaketa(paket: String): String {
            val p = paket.lowercase(Locale.ROOT)
            return when {
                listOf("game", "igra", "games").any { it in p } -> "igre"
                listOf("youtube", "spotify", "music", "video", "player", "photo", "gallery",
                    "camera", "rtvslo", "hbo", "disney", "twitch", "radio", "netflix").any { it in p } -> "predstavnost"
                listOf("browser", "chrome", "firefox", "safeer", "mail", "gmail", "whatsapp", "viber", "messenger",
                    "telegram", "facebook", "instagram", "maps").any { it in p } -> "splet"
                listOf("docs", "sheets", "slides", "office", "word", "excel", "pdf", "calendar", "notes", "keep")
                    .any { it in p } -> "pisarna"
                listOf("settings", "files", "calculator", "clock", "manager", "launcher").any { it in p } -> "orodja"
                else -> DRUGO
            }
        }
    }
}
