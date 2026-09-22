package si.safeer.tv.tablica

import si.safeer.tv.HomeTilesStore
import si.safeer.tv.R
import si.safeer.tv.os.AplikacijeHostaActivity
import si.safeer.tv.os.DatotekeActivity
import si.safeer.tv.os.Host
import si.safeer.tv.os.Identiteta
import si.safeer.tv.os.LinkOdjemalec
import si.safeer.tv.os.LinkUpravitelj
import si.safeer.tv.os.NapraveActivity
import si.safeer.tv.os.Ozadje
import si.safeer.tv.os.Robovi
import si.safeer.tv.os.Scit
import si.safeer.tv.os.Sosed
import si.safeer.tv.os.SpletneAplikacije
import si.safeer.tv.os.ZaslonActivity

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.URLEncoder

/**
 * Domači zaslon Safeer OS Tablet.
 *
 * Enaka zasnova kot na televizorju (Safeer Link, vsebina, datoteke, programi, zaslon, Ščit),
 * a prilagojena za dotik od blizu:
 *  - Stranski meni (ležeče z besedilom, pokončno kot elegantna ozka tirnica z ikonami).
 *  - Iskalna vrstica za hitro iskanje na spletu ali neposredno odpiranje naslovov.
 *  - Tri velike primarne kartice (Spletni brskalnik, Zaslon računalnika, Programi).
 *  - Odsek Aplikacije z vodoravnim drsnikom in možnostjo dodajanja bližnjic.
 *  - Tri spodnje plošče: resnične povezane naprave (Safeer Link), hitri dostop in Ščit (DNS zaščita).
 */
class DomovTabletActivity : Activity(), LinkOdjemalec.Poslusalec {

    /** Domaci zaslon tablice govori jezik, ki ga je uporabnik izbral (prej je ostal v jeziku sistema). */
    override fun attachBaseContext(newBase: android.content.Context) {
        jezikOb = si.safeer.tv.JezikVmesnika.izbrani(newBase)
        super.attachBaseContext(si.safeer.tv.JezikVmesnika.vKontekstu(newBase))
    }

    private var jezikOb: String? = null

    /** Jezik je bil v Nastavitvah zamenjan: domaci zaslon se ob vrnitvi narise v novem. */
    override fun onResume() {
        super.onResume()
        if (jezikOb != null && si.safeer.tv.JezikVmesnika.izbrani(this) != jezikOb) recreate()
    }

    private var koren: View? = null
    private var drsnik: ScrollView? = null
    private var vnosIskanje: EditText? = null
    private var stanjeOkvir: View? = null
    private var stanjePika: View? = null
    private var stanjeBesedilo: TextView? = null
    private var naslovPozdrav: TextView? = null
    private var podnaslovPozdrav: TextView? = null

    private var karticaBrskalnik: View? = null
    private var karticaZaslon: View? = null
    private var karticaProgrami: View? = null

    private var gumbVseAplikacije: View? = null
    private var vrstaAplikacije: LinearLayout? = null

    private var ploscaNaprave: View? = null
    private var seznamPloscaNaprave: LinearLayout? = null

    private var ploscaHitri: View? = null
    private var hitriWeb: View? = null
    private var hitriRacunalnik: View? = null
    private var hitriDatoteke: View? = null
    private var hitriNastavitve: View? = null

    private var ploscaScit: View? = null
    private var scitStatusPika: View? = null
    private var scitStatusBesedilo: TextView? = null
    private var scitStevecBesedilo: TextView? = null

    private var karticaPovezavaHost: View? = null
    private var ikonaPovezavaHost: ImageView? = null
    private var naslovPovezavaHost: TextView? = null
    private var podnaslovPovezavaHost: TextView? = null
    private var gumbOdklopiHost: TextView? = null

    private var scitStanje: Scit.Stanje? = null
    private val glavna = Handler(Looper.getMainLooper())
    private val link by lazy { LinkUpravitelj.pridobi(this) }

    private var sporociloStanja = ""
    private var imamoDatoteke = false
    private var imamoPrograme = false
    private var imamoZaslon = false

    private var hubi: List<IskanjeHubov.Hub> = emptyList()
    private var iscem = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nastaviPostavitev()
        // Brez dovoljenja za obvestila naprava ne more vprasati lastnika, kadar Android za
        // brisanje ali vrtenje fotografije zahteva njegovo privolitev (PotrditevActivity).
        si.safeer.tv.link.Obvestila.zaprosiEnkrat(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        nastaviPostavitev()
    }

    /**
     * Telefon pokonci (ozek zaslon): iskanje dobi vso sirino, kapsula stanja gre pod njo.
     * Sicer bi kapsula iskalno polje stisnila na nekaj crk.
     */
    private fun glavaZaOzekZaslon() {
        if (resources.configuration.screenWidthDp >= 600) return
        val iskanje = findViewById<View>(R.id.iskalnaVrsticaOkvir) ?: return
        val stanje = stanjeOkvir ?: return
        val glava = iskanje.parent as? android.widget.LinearLayout ?: return
        glava.orientation = android.widget.LinearLayout.VERTICAL
        glava.gravity = android.view.Gravity.START
        iskanje.layoutParams = (iskanje.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
            width = android.widget.LinearLayout.LayoutParams.MATCH_PARENT; weight = 0f; marginEnd = 0
        }
        stanje.layoutParams = (stanje.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
            topMargin = (10 * resources.displayMetrics.density).toInt()
        }
    }

    private fun nastaviPostavitev() {
        setContentView(R.layout.tablet_activity_domov)
        Robovi.uporabi(this)
        val root = findViewById<View>(R.id.koren)
        koren = root
        Ozadje.uporabi(this, root)

        drsnik = findViewById(R.id.drsnik)
        vnosIskanje = findViewById(R.id.vnosIskanje)
        stanjeOkvir = findViewById(R.id.stanjeOkvir)
        glavaZaOzekZaslon()
        stanjePika = findViewById(R.id.stanjePika)
        stanjeBesedilo = findViewById(R.id.stanjeBesedilo)
        naslovPozdrav = findViewById(R.id.naslovPozdrav)
        podnaslovPozdrav = findViewById(R.id.podnaslovPozdrav)

        karticaBrskalnik = findViewById(R.id.karticaBrskalnik)
        karticaZaslon = findViewById(R.id.karticaZaslon)
        karticaProgrami = findViewById(R.id.karticaProgrami)

        gumbVseAplikacije = findViewById(R.id.gumbVseAplikacije)
        vrstaAplikacije = findViewById(R.id.vrstaAplikacije)

        ploscaNaprave = findViewById(R.id.ploscaNaprave)
        seznamPloscaNaprave = findViewById(R.id.seznamPloscaNaprave)
        ploscaHitri = findViewById(R.id.ploscaHitri)
        hitriWeb = findViewById(R.id.hitriWeb)
        hitriRacunalnik = findViewById(R.id.hitriRacunalnik)
        hitriDatoteke = findViewById(R.id.hitriDatoteke)
        hitriNastavitve = findViewById(R.id.hitriNastavitve)

        ploscaScit = findViewById(R.id.ploscaScit)
        scitStatusPika = findViewById(R.id.scitStatusPika)
        scitStatusBesedilo = findViewById(R.id.scitStatusBesedilo)
        scitStevecBesedilo = findViewById(R.id.scitStevecBesedilo)

        karticaPovezavaHost = findViewById(R.id.karticaPovezavaHost)
        ikonaPovezavaHost = findViewById(R.id.ikonaPovezavaHost)
        naslovPovezavaHost = findViewById(R.id.naslovPovezavaHost)
        podnaslovPovezavaHost = findViewById(R.id.podnaslovPovezavaHost)
        gumbOdklopiHost = findViewById(R.id.gumbOdklopiHost)

        pripraviStranskiMeni()
        pripraviIskanje()
        pripraviVelikeKartice()
        pripraviSpodnjePlosce()

        pokaziStanje()
        narisi()
        osveziScit()
    }

    override fun onStart() {
        super.onStart()
        link.dodaj(this)
        pokaziStanje()
        narisi()
        osveziScit()
        isci()
        SpletneAplikacije.osveziIkone(this) {
            glavna.post { narisiAplikacije() }
        }
        si.safeer.tv.os.GlasbaStoritev.poslusalci.add(medijiPoslusalec)
        si.safeer.tv.os.GlasbaStoritev.osveziKartico(this)
    }

    /** Kartica Mediji kaze, kaj se predvaja (tudi ko predvajanje tece v ozadju). */
    private val medijiPoslusalec: () -> Unit = { runOnUiThread { si.safeer.tv.os.GlasbaStoritev.osveziKartico(this) } }

    override fun onStop() {
        si.safeer.tv.os.GlasbaStoritev.poslusalci.remove(medijiPoslusalec)
        link.odstrani(this)
        super.onStop()
    }

    private fun pripraviStranskiMeni() {
        val mDomov = findViewById<View>(R.id.meniDomov)
        val mAplikacije = findViewById<View>(R.id.meniAplikacije)
        val mZaslon = findViewById<View>(R.id.meniZaslon)
        val mDatoteke = findViewById<View>(R.id.meniDatoteke)
        val mNaprave = findViewById<View>(R.id.meniNaprave)
        val mSplet = findViewById<View>(R.id.meniSplet)
        val mNastavitve = findViewById<View>(R.id.meniNastavitve)

        mDomov?.isActivated = true
        mDomov?.isSelected = true
        mDomov?.setOnClickListener {
            drsnik?.smoothScrollTo(0, 0)
        }
        // En vstop do vseh aplikacij (tablice in povezanih naprav); deluje tudi brez racunalnika.
        mAplikacije?.setOnClickListener {
            odpriVarno(Intent(this, AplikacijeHostaActivity::class.java), getString(R.string.tablet_programi))
        }
        // Zaslon in Splet sta kartici na domacem zaslonu; v stranski vrstici bi ju podvojila.
        mZaslon?.visibility = View.GONE
        mSplet?.visibility = View.GONE
        mZaslon?.setOnClickListener {
            val r = racunalnik("desktop")
            if (r != null) {
                val namera = Intent(this, ZaslonActivity::class.java).putExtra(DatotekeActivity.EXTRA_RACUNALNIK, r.id)
                odpriVarno(namera, getString(R.string.tablet_zaslon))
            } else {
                pokaziOknoNiPovezano(getString(R.string.tablet_zaslon))
            }
        }
        mDatoteke?.setOnClickListener {
            if (imamoDatoteke) {
                odpriVarno(Intent(this, DatotekeActivity::class.java), getString(R.string.tablet_datoteke))
            } else {
                pokaziOknoNiPovezano(getString(R.string.tablet_datoteke))
            }
        }
        findViewById<View>(R.id.karticaMediji)?.setOnClickListener {
            odpriVarno(si.safeer.tv.os.GlasbaStoritev.namenKartice(this), getString(R.string.os_mediji_kartica))
        }
        mNaprave?.setOnClickListener {
            odpriVarno(Intent(this, NapraveActivity::class.java), getString(R.string.tablet_naprave))
        }
        mSplet?.setOnClickListener {
            odpriVBrskalniku(null)
        }
        mNastavitve?.setOnClickListener {
            odpriVarno(Intent(this, si.safeer.tv.os.NastavitveActivity::class.java), getString(R.string.os_meni_nastavitve))
        }
    }

    private fun pripraviIskanje() {
        vnosIskanje?.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                izvediIskanje(vnosIskanje?.text?.toString().orEmpty().trim())
                true
            } else false
        }
    }

    private fun izvediIskanje(niz: String) {
        if (niz.isBlank()) {
            odpriVBrskalniku(null)
            return
        }
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(vnosIskanje?.windowToken, 0)
        vnosIskanje?.clearFocus()
        // Isto pravilo kot vrstica brskalnika: naslov ali iskanje; neobstojeca domena gre v iskanje.
        val odl = si.safeer.tv.SmartOmnibox.razresi(this, niz) ?: return
        val gostitelj = odl.preveri
        if (gostitelj == null) { odpriVBrskalniku(odl.url); return }
        Thread {
            val url = if (si.safeer.tv.SmartOmnibox.obstaja(gostitelj)) odl.url else si.safeer.tv.SmartOmnibox.iskanje(this, niz)
            runOnUiThread { if (!isFinishing) odpriVBrskalniku(url) }
        }.start()
    }

    private fun pripraviVelikeKartice() {
        karticaBrskalnik?.setOnClickListener {
            odpriVBrskalniku(null)
        }
        karticaZaslon?.setOnClickListener { odpriZaslon() }
        // Hitri dostop je ponavljal kartice in stransko vrstico (Splet, Zaslon, Datoteke, Nastavitve).
        ploscaHitri?.visibility = View.GONE
        // Isto vsebino odpre Aplikacije v stranski vrstici - druga kartica ali "Prikazi vse" bi jo podvojila.
        karticaProgrami?.visibility = View.GONE
        gumbVseAplikacije?.visibility = View.GONE
        karticaProgrami?.setOnClickListener {
            if (imamoPrograme) {
                odpriVarno(Intent(this, AplikacijeHostaActivity::class.java), getString(R.string.tablet_programi))
            } else {
                pokaziOknoNiPovezano(getString(R.string.tablet_programi))
            }
        }
        gumbVseAplikacije?.setOnClickListener {
            if (imamoPrograme) {
                odpriVarno(Intent(this, AplikacijeHostaActivity::class.java), getString(R.string.tablet_programi))
            } else {
                dodajSpletno()
            }
        }
    }

    private fun pripraviSpodnjePlosce() {
        ploscaNaprave?.setOnClickListener {
            odpriVarno(Intent(this, NapraveActivity::class.java), getString(R.string.tablet_naprave))
        }
        stanjeOkvir?.setOnClickListener {
            odpriVarno(Intent(this, NapraveActivity::class.java), getString(R.string.tablet_naprave))
        }
        hitriWeb?.setOnClickListener {
            odpriVBrskalniku(null)
        }
        hitriRacunalnik?.setOnClickListener {
            val r = racunalnik("desktop")
            if (r != null) {
                val namera = Intent(this, ZaslonActivity::class.java).putExtra(DatotekeActivity.EXTRA_RACUNALNIK, r.id)
                odpriVarno(namera, getString(R.string.tablet_zaslon))
            } else {
                pokaziOknoNiPovezano(getString(R.string.tablet_zaslon))
            }
        }
        hitriDatoteke?.setOnClickListener {
            if (imamoDatoteke) {
                odpriVarno(Intent(this, DatotekeActivity::class.java), getString(R.string.tablet_datoteke))
            } else {
                pokaziOknoNiPovezano(getString(R.string.tablet_datoteke))
            }
        }
        hitriNastavitve?.setOnClickListener {
            odpriVarno(Intent(this, si.safeer.tv.os.NastavitveActivity::class.java), getString(R.string.os_meni_nastavitve))
        }
        ploscaScit?.setOnClickListener {
            podrobnostiScita()
        }
    }

    /** Splet na tablici: mobilni Safeer, ce je namescen (os/Brskalnik), sicer vgrajeni. */
    private fun brskalnikNamera(): Intent = si.safeer.tv.os.Brskalnik.namera(this)

    private fun odpriVBrskalniku(url: String?) {
        val namera = url?.let { si.safeer.tv.os.Brskalnik.mobilniNaslov(this, it) } ?: brskalnikNamera().also {
            if (url != null) { it.action = Intent.ACTION_VIEW; it.data = Uri.parse(url) }
        }
        odpriVarno(namera, getString(R.string.os_splet))
    }

    private fun odpriVarno(namera: Intent, naslov: String) {
        try {
            startActivity(namera)
        } catch (e: Throwable) {
            Toast.makeText(this, "$naslov: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pokaziOknoNiPovezano(naslov: String) {
        if (isFinishing) return
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(naslov)
            .setMessage(getString(R.string.os_kartica_ni_povezano_opis))
            .setPositiveButton(getString(R.string.os_meni_naprave)) { _, _ ->
                startActivity(Intent(this, NapraveActivity::class.java))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ Iskanje hubov in seznanitev

    private fun isci() {
        if (iscem || imamoDatoteke || imamoPrograme || imamoZaslon) return
        iscem = true
        IskanjeHubov.najdi(this) { najdeni ->
            iscem = false
            if (isFinishing) return@najdi
            hubi = najdeni.filter { !(link.povezan && it.ime == link.imeSredisca) }
            slediSredisculu(najdeni)
            narisi()
        }
    }

    private fun slediSredisculu(najdeni: List<IskanjeHubov.Hub>) {
        val p = Host.poverilnice(this) ?: return
        if (!Host.jeOddaljen(this) || link.povezan) return
        val isti = najdeni.firstOrNull { it.odtis.isNotBlank() && it.odtis.equals(p.odtis, ignoreCase = true) } ?: return
        if (isti.naslov == p.hubUrl) return
        Host.shrani(this, isti.naslov, p.zeton, p.odtis, p.hubId)
        link.ponovnoPoveziSe()
    }

    private fun seznani(hub: IskanjeHubov.Hub) {
        si.safeer.tv.cast.HubPairing.prekini()
        Host.zapomniNaslov(this, hub.naslov)
        Toast.makeText(this, getString(R.string.tablet_povezujem, hub.ime), Toast.LENGTH_SHORT).show()
        si.safeer.tv.cast.HubPairing.pair(this, hub.naslov, Identiteta.id(this),
            getString(R.string.os_ime_vrste) + " (" + android.os.Build.MODEL + ")",
            { _, _ -> if (!isFinishing) vnesiKodo(hub) },
            { uspelo -> if (!uspelo && !isFinishing)
                Toast.makeText(this, getString(R.string.tablet_ni_odgovora, hub.ime), Toast.LENGTH_LONG).show() })
    }

    private fun vnesiKodo(hub: IskanjeHubov.Hub) {
        val vnos = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 28f
            gravity = android.view.Gravity.CENTER
            setPadding(40, 30, 40, 30)
        }
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.tablet_koda_naslov, hub.ime))
            .setMessage(getString(R.string.tablet_koda_opis, hub.ime))
            .setView(vnos)
            .setPositiveButton(getString(R.string.tablet_poveziSe)) { _, _ -> potrdi(hub, vnos.text?.toString().orEmpty()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> si.safeer.tv.cast.HubPairing.prekini() }
            .setNeutralButton(getString(R.string.tablet_nova_koda)) { _, _ -> seznani(hub) }
            .show()
        vnos.requestFocus()
        glavna.postDelayed({
            if (okno.isShowing) {
                okno.dismiss()
                si.safeer.tv.cast.HubPairing.prekini()
                Toast.makeText(this, getString(R.string.tablet_koda_potekla), Toast.LENGTH_LONG).show()
            }
        }, 290_000)
    }

    private fun potrdi(hub: IskanjeHubov.Hub, koda: String) {
        si.safeer.tv.cast.HubPairing.potrdiKodo(this, koda, Identiteta.id(this)) { uspelo, napaka ->
            if (isFinishing) return@potrdiKodo
            val izid = si.safeer.tv.cast.HubPairing.zadnjaSeznanitev
            if (uspelo && izid != null) {
                Host.shrani(this, hub.naslov, izid.zeton, izid.odtis, izid.hubId)
                link.ponovnoPoveziSe()
                hubi = emptyList()
                Toast.makeText(this, getString(R.string.tablet_povezana, hub.ime), Toast.LENGTH_LONG).show()
                pokaziStanje()
                narisi()
                return@potrdiKodo
            }
            val sporocilo = when (napaka) {
                "napacna_koda" -> getString(R.string.tablet_napacna_koda)
                "prevec_poskusov" -> getString(R.string.tablet_prevec_poskusov)
                "prijava_ne_obstaja", "seznanitev_ne_tece" -> getString(R.string.tablet_koda_potekla)
                else -> getString(R.string.tablet_ni_odgovora, hub.ime)
            }
            Toast.makeText(this, sporocilo, Toast.LENGTH_LONG).show()
            if (napaka == "napacna_koda") vnesiKodo(hub)
        }
    }

    // ------------------------------------------------------------------ Prikaz vsebine in stanja

    /**
     * Povezani zasloni: ena naprava, ki deli zaslon, se odpre takoj; kadar jih je vec, uporabnik
     * izbere, katero gleda na tablici.
     */
    private fun odpriZaslon() {
        val naprave = link.naprave.filter { it.zmoznosti.contains("desktop") && it.id != Identiteta.id(this) }
        fun odpri(r: LinkOdjemalec.Naprava) {
            odpriVarno(Intent(this, ZaslonActivity::class.java).putExtra(DatotekeActivity.EXTRA_RACUNALNIK, r.id),
                getString(R.string.tablet_zaslon))
        }
        when (naprave.size) {
            0 -> pokaziOknoNiPovezano(getString(R.string.tablet_zaslon))
            1 -> odpri(naprave[0])
            else -> AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.tablet_zaslon))
                .setItems(naprave.map { it.ime.ifBlank { it.id } }.toTypedArray()) { _, i -> odpri(naprave[i]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun racunalnik(zmoznost: String): LinkOdjemalec.Naprava? =
        link.naprave.firstOrNull { it.zmoznosti.contains(zmoznost) && it.id != Identiteta.id(this) }

    private fun narisi() {
        imamoDatoteke = racunalnik("files") != null
        imamoPrograme = racunalnik("apps") != null
        imamoZaslon = racunalnik("desktop") != null

        narisiAplikacije()
        narisiNaprave()
        pokaziStanje()
    }

    private fun narisiAplikacije() {
        val vrsta = vrstaAplikacije ?: return
        vrsta.removeAllViews()
        val seznam = SpletneAplikacije.seznam(this)
        val infl = LayoutInflater.from(this)

        for (a in seznam) {
            val v = infl.inflate(R.layout.os_kartica_ikona, vrsta, false)
            val ikona = v.findViewById<ImageView>(R.id.ikona)
            val ime = v.findViewById<TextView>(R.id.ime)
            ikona?.setImageDrawable(SpletneAplikacije.ikona(this, a))
            ime?.text = a.ime.ifBlank { SpletneAplikacije.gostitelj(a.url) }
            v.setOnClickListener { zazeniSpletno(a) }
            v.setOnLongClickListener { moznostiSpletne(a); true }
            vrsta.addView(v)
        }

        // Ploščica za dodajanje aplikacije
        val dodajView = infl.inflate(R.layout.os_kartica_dodaj, vrsta, false)
        dodajView.setOnClickListener { dodajSpletno() }
        vrsta.addView(dodajView)
    }

    private fun zazeniSpletno(a: SpletneAplikacije.Aplikacija) {
        si.safeer.tv.os.Brskalnik.mobilniNaslov(this, a.url)?.let {
            odpriVarno(it, a.ime.ifBlank { SpletneAplikacije.gostitelj(a.url) }); return
        }
        val namera = brskalnikNamera()
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(a.url))
            .putExtra("aplikacija_ime", a.ime.ifBlank { SpletneAplikacije.gostitelj(a.url) })
        odpriVarno(namera, a.ime.ifBlank { SpletneAplikacije.gostitelj(a.url) })
    }

    private fun moznostiSpletne(a: SpletneAplikacije.Aplikacija) {
        val moznosti = arrayOf(
            getString(R.string.os_spletne_levo),
            getString(R.string.os_spletne_desno),
            getString(R.string.os_spletne_odstrani)
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(a.ime.ifBlank { SpletneAplikacije.gostitelj(a.url) })
            .setItems(moznosti) { _, kateri ->
                when (kateri) {
                    0 -> { SpletneAplikacije.premakni(this, a.url, -1); narisiAplikacije() }
                    1 -> { SpletneAplikacije.premakni(this, a.url, 1); narisiAplikacije() }
                    2 -> { SpletneAplikacije.odstrani(this, a.url); narisiAplikacije() }
                }
            }
            .show()
    }

    private fun dodajSpletno() {
        val ploscice = HomeTilesStore.load(this)
        val proste = ploscice.filterNot { SpletneAplikacije.jeDodana(this, it.url) }
        val imena = (listOf(getString(R.string.os_spletne_vnesi)) +
            proste.map { it.title.ifBlank { SpletneAplikacije.gostitelj(it.url) } }).toTypedArray()

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_spletne_dodaj_naslov))
            .setItems(imena) { _, kateri ->
                if (kateri == 0) {
                    vnesiSpletnoRocno()
                } else {
                    val izbrana = proste.getOrNull(kateri - 1)
                    if (izbrana != null) {
                        SpletneAplikacije.dodaj(this, izbrana.url, izbrana.title) {
                            glavna.post { narisiAplikacije() }
                        }
                    }
                }
            }
            .show()
    }

    private fun vnesiSpletnoRocno() {
        val vnos = EditText(this).apply {
            hint = "https://"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setPadding(30, 24, 30, 24)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_spletne_vnesi))
            .setView(vnos)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                var url = vnos.text?.toString()?.trim().orEmpty()
                if (url.isNotBlank()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                    SpletneAplikacije.dodaj(this, url, SpletneAplikacije.gostitelj(url)) {
                        glavna.post { narisiAplikacije() }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun narisiNaprave() {
        val seznam = seznamPloscaNaprave ?: return
        seznam.removeAllViews()
        val infl = LayoutInflater.from(this)
        val druge = LinkOdjemalec.drugeZaPrikaz(link.naprave, Identiteta.id(this))

        // Brez Safeer Linka tablica ne vidi nobene naprave, vklopa pa drugje nima: zato je na
        // domacem zaslonu. Krajevnega nacina tablici ne ponujamo - brez naprav ta zaslon nima cesa
        // pokazati. Kadar Link tece, povezave pa ni, je edino smiselno dejanje poskus znova.
        val vklop = !link.povezan && (link.vprasamoZaNacin() || link.jeKrajevni() || sporociloStanja == "ni_linka")
        val znova = !link.povezan && !vklop && sporociloStanja == "ni"
        if (vklop || znova) {
            val v = infl.inflate(R.layout.os_vrstica_naprava, seznam, false)
            v.findViewById<ImageView>(R.id.ikonaNaprave)?.setImageResource(R.drawable.os_ikona_link)
            v.findViewById<TextView>(R.id.imeNaprave)?.text =
                getString(if (vklop) R.string.tablet_vklopi else R.string.tablet_znova)
            v.findViewById<View>(R.id.pikaNaprave)?.setBackgroundResource(R.drawable.os_pika_rumena)
            v.findViewById<TextView>(R.id.stanjeNaprave)?.text =
                getString(if (vklop) R.string.tablet_vklopi_opis else R.string.tablet_znova_opis)
            v.setOnClickListener {
                if (vklop) {
                    link.vklopiLink()
                    Toast.makeText(this, getString(R.string.tablet_vklopljen), Toast.LENGTH_LONG).show()
                } else {
                    link.ponovnoPoveziSe()
                }
                pokaziStanje()
                narisi()
            }
            seznam.addView(v)
        }

        if (druge.isNotEmpty()) {
            for (n in druge.take(3)) {
                val v = infl.inflate(R.layout.os_vrstica_naprava, seznam, false)
                val ikona = v.findViewById<ImageView>(R.id.ikonaNaprave)
                val ime = v.findViewById<TextView>(R.id.imeNaprave)
                val pika = v.findViewById<View>(R.id.pikaNaprave)
                val stanje = v.findViewById<TextView>(R.id.stanjeNaprave)

                val jeTelefon = n.vloga == "phone" || n.vloga == "telefon"
                ikona?.setImageResource(if (jeTelefon) R.drawable.os_ikona_telefon else R.drawable.os_ikona_zaslon)
                ime?.text = n.ime.ifBlank { n.id }
                pika?.setBackgroundResource(R.drawable.os_pika)
                pika?.alpha = if (link.povezan) 1f else 0.35f
                stanje?.text = getString(if (link.povezan) R.string.os_plosca_naprava_povezana else R.string.os_plosca_naprava_pripravljenost)

                v.setOnClickListener { startActivity(Intent(this, NapraveActivity::class.java)) }
                seznam.addView(v)
            }
        } else if (hubi.isNotEmpty()) {
            for (h in hubi.take(3)) {
                val v = infl.inflate(R.layout.os_vrstica_naprava, seznam, false)
                val ikona = v.findViewById<ImageView>(R.id.ikonaNaprave)
                val ime = v.findViewById<TextView>(R.id.imeNaprave)
                val pika = v.findViewById<View>(R.id.pikaNaprave)
                val stanje = v.findViewById<TextView>(R.id.stanjeNaprave)

                ikona?.setImageResource(R.drawable.os_ikona_link)
                ime?.text = h.ime
                pika?.setBackgroundResource(R.drawable.os_pika_rumena)
                stanje?.text = getString(R.string.tablet_pridruzi, h.ime)

                v.setOnClickListener { seznani(h) }
                seznam.addView(v)
            }
        } else if (!vklop && !znova) {
            val prazno = TextView(this).apply {
                text = getString(if (!link.povezan) R.string.tablet_ni_linka else R.string.tablet_ni_racunalnika)
                setTextColor(getColor(R.color.os_umirjeno))
                textSize = 12f
                setPadding(0, 6, 0, 6)
            }
            seznam.addView(prazno)
        }
    }

    private fun pokaziStanje() {
        val kje = link.imeSredisca.ifBlank { getString(R.string.os_naprava_tv) }
        val besedilo = when {
            link.povezan && !Host.jeOddaljen(this) && !imamoDatoteke && !imamoPrograme && !imamoZaslon ->
                getString(R.string.tablet_stanje_ni_tv)
            link.povezan -> getString(R.string.os_stanje_povezan, kje)
            sporociloStanja == "krajevni" -> getString(R.string.os_stanje_krajevni)
            sporociloStanja == "ni_linka" -> getString(R.string.os_stanje_ni_linka)
            sporociloStanja == "ni" -> getString(R.string.os_stanje_ni)
            else -> getString(R.string.os_stanje_povezujem)
        }
        stanjeBesedilo?.text = besedilo
        stanjePika?.let { pika ->
            if (link.povezan) {
                pika.setBackgroundResource(R.drawable.os_pika)
                pika.alpha = 1f
            } else {
                pika.setBackgroundResource(R.drawable.os_pika_rumena)
                pika.alpha = 0.7f
            }
        }

        // Povezava v stranskem meniju
        if (Host.jeOddaljen(this) && link.povezan) {
            val ime = link.imeSredisca.ifBlank { Host.gostitelj(this) ?: getString(R.string.os_naprava_tv) }
            naslovPovezavaHost?.text = getString(R.string.tablet_povezano_z, ime)
            podnaslovPovezavaHost?.text = Host.gostitelj(this) ?: ""
            gumbOdklopiHost?.visibility = View.VISIBLE
            gumbOdklopiHost?.setOnClickListener {
                Host.domov(this)
                link.ponovnoPoveziSe()
                Toast.makeText(this, getString(R.string.os_stanje_ni), Toast.LENGTH_SHORT).show()
                pokaziStanje()
                narisi()
            }
        } else if (link.povezan) {
            val ime = link.imeSredisca.ifBlank { getString(R.string.os_naprava_tv) }
            naslovPovezavaHost?.text = getString(R.string.os_link)
            podnaslovPovezavaHost?.text = ime
            gumbOdklopiHost?.visibility = View.GONE
        } else {
            naslovPovezavaHost?.text = getString(R.string.os_link)
            podnaslovPovezavaHost?.text = getString(R.string.os_stanje_ni)
            gumbOdklopiHost?.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------ Ščit (DNS zaščita)

    private fun osveziScit() {
        Scit.stanje(this) { pokaziScit(it) }
    }

    private fun pokaziScit(s: Scit.Stanje) {
        scitStanje = s
        val besedilo = scitStatusBesedilo ?: return
        val stevec = scitStevecBesedilo ?: return
        val pika = scitStatusPika ?: return

        when {
            !s.naVoljo -> {
                besedilo.text = getString(R.string.os_plosca_scit_izklopljen)
                pika.alpha = 0.35f
                stevec.text = getString(R.string.os_scit_ni_brskalnika)
            }
            s.vklopljen && s.tece -> {
                besedilo.text = getString(R.string.os_plosca_scit_vklopljen)
                pika.alpha = 1f
                stevec.text = if (s.blokiranih > 0)
                    getString(R.string.os_plosca_scit_stanje_blokiranih, s.blokiranih)
                else getString(R.string.os_plosca_scit_aktivna)
            }
            s.vklopljen -> {
                besedilo.text = getString(R.string.os_plosca_scit_prekinjen)
                pika.alpha = 0.5f
                stevec.text = getString(R.string.os_scit_kratko_prekinjen)
            }
            else -> {
                besedilo.text = getString(R.string.os_plosca_scit_izklopljen)
                pika.alpha = 0.35f
                stevec.text = getString(R.string.os_scit_kratko_izklopljen)
            }
        }
        // Zelena pika pomeni, da Scit dela; izklopljen ali prekinjen ima sivo, ne zbledelo zeleno.
        pika.backgroundTintList = if (s.naVoljo && s.vklopljen && s.tece) null else android.content.res.ColorStateList.valueOf(getColor(R.color.os_siva_pika))
    }

    private fun podrobnostiScita() {
        val s = scitStanje
        val besedilo = when {
            s == null || !s.naVoljo -> getString(R.string.os_scit_ni_brskalnika)
            s.vklopljen && s.tece -> getString(R.string.os_scit_vklopljen_opis, s.blokiranih)
            s.vklopljen -> getString(R.string.os_scit_prekinjen)
            else -> getString(R.string.os_scit_izklopljen_opis)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_scit))
            .setMessage(besedilo + "\n\n" + getString(R.string.os_scit_nastavitev_opis))
            .setPositiveButton(getString(
                if (s?.vklopljen == true) R.string.os_zaganjalnik_izklopi_kratko else R.string.os_vklopi)) { _, _ -> preklopiScit() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .show()
    }

    private fun preklopiScit() {
        val s = scitStanje
        if (s == null || !s.naVoljo) {
            Toast.makeText(this, getString(R.string.os_scit_ni_brskalnika), Toast.LENGTH_LONG).show()
            return
        }
        if (s.vklopljen) {
            Scit.izklopi(this) { pokaziScit(it); Toast.makeText(this, getString(R.string.os_scit_izklopljen_kratko), Toast.LENGTH_SHORT).show() }
        } else {
            Scit.vklopi(this) { nov ->
                if (nov.potrebujeOkno || (!nov.vklopljen && nov.naVoljo)) Scit.odpriVklop(this) else pokaziScit(nov)
            }
        }
        glavna.postDelayed({ osveziScit() }, 2_500)
    }

    // ------------------------------------------------------------------ Link poslušalec

    override fun naStanje(povezan: Boolean, sporocilo: String) {
        sporociloStanja = sporocilo
        pokaziStanje()
        narisi()
        if (!povezan && sporocilo == "ni") isci()
    }

    override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) {
        pokaziStanje()
        val datoteke = racunalnik("files") != null
        val programi = racunalnik("apps") != null
        val zaslon = racunalnik("desktop") != null
        if (datoteke != imamoDatoteke || programi != imamoPrograme || zaslon != imamoZaslon) narisi()
        else narisiNaprave()
        if (!datoteke && !programi && !zaslon) isci()
        si.safeer.tv.os.AplikacijeHostaActivity.predhodno(this)
    }

    override fun naNaslov(url: String, naslov: String, od: String) { }
    override fun naBesedilo(besedilo: String, od: String) { }
    override fun naZavrnitev() { }
}
