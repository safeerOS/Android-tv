package si.safeer.tv.tablica

import si.safeer.tv.os.osBarva
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

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 *  - Ista stranska vrstica kot na vseh zaslonih Safeer OS (StranskaVrstica): telefon pokonci jo skrije (gumb
 *    menija, poteg z roba), tablica in telefon ležeče imajo tirnico z ikonami.
 *  - Kompaktna iskalna lupa v glavi (DomacaLupa) s kuverto Sporočil ob njej.
 *  - Tri velike primarne kartice (Spletni brskalnik, Zaslon računalnika, Programi).
 *  - Odsek Aplikacije z vodoravnim drsnikom in možnostjo dodajanja bližnjic.
 *  - Tri spodnje plošče: resnične povezane naprave (Safeer Link), hitri dostop in Ščit (DNS zaščita).
 */
class DomovTabletActivity : Activity(), LinkOdjemalec.Poslusalec {
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (si.safeer.tv.os.DomacaLupa.naRezultat(this, requestCode, resultCode, data)) return
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }


    /** Domaci zaslon tablice govori jezik, ki ga je uporabnik izbral (prej je ostal v jeziku sistema). */
    override fun attachBaseContext(newBase: android.content.Context) {
        jezikOb = si.safeer.tv.JezikVmesnika.izbrani(newBase)
        super.attachBaseContext(si.safeer.tv.JezikVmesnika.vKontekstu(newBase))
    }

    private var jezikOb: String? = null

    /** Jezik je bil v Nastavitvah zamenjan: domaci zaslon se ob vrnitvi narise v novem. */
    override fun onResume() {
        super.onResume()
        if (jezikOb != null && si.safeer.tv.JezikVmesnika.izbrani(this) != jezikOb) { recreate(); return }
        // "Poslji na napravo": ponudba druge naprave se tudi na domacem zaslonu pokaze kot tiha pasica (kot v OsActivity),
        // ne kot obvestilo - to brez dovoljenja za obvestila sploh ne bi bilo vidno.
        si.safeer.tv.os.Predaja.prikaz = { po -> if (!isFinishing) si.safeer.tv.os.PredajaObvestilo.pasica(this, po) }
        si.safeer.tv.os.Predaja.cakajoca?.takeIf { it.velja() }?.let { si.safeer.tv.os.PredajaObvestilo.umakni(this); si.safeer.tv.os.PredajaObvestilo.pasica(this, it) }
        si.safeer.tv.os.Posodobitve.ponudiCeJeCas(this)   // nova razlicica s safeer.si: tiha pasica, najvec enkrat na dan
        // Enkrat, ce manjka pomembno dovoljenje (prikaz cez druge aplikacije, obvestila) - stran Dovoljenja.
        si.safeer.tv.os.DovoljenjaActivity.ponudiObZagonu(this)
    }

    override fun onPause() {
        if (si.safeer.tv.os.Predaja.prikaz != null) { si.safeer.tv.os.Predaja.prikaz = null; si.safeer.tv.os.PredajaObvestilo.umakniPasico() }
        super.onPause()
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
    /** Naloga 105: kartica »Medijski center« (postavitev jo ima od nekdaj, klika pa ni nihce povezal - »ne vodi nikamor«). */
    private var karticaMediji: View? = null
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

    private var scitStanje: Scit.Stanje? = null
    private val glavna = Handler(Looper.getMainLooper())
    private val link by lazy { LinkUpravitelj.pridobi(this) }

    private var sporociloStanja = ""
    private var imamoDatoteke = false
    private var imamoPrograme = false
    private var imamoZaslon = false

    private var hubi: List<IskanjeHubov.Hub> = emptyList()
    private var iscem = false

    private var vrstica: si.safeer.tv.os.StranskaVrstica? = null
    /** Kuverta Sporocil v glavi (znacka neprebranih). */
    private var kuverta: View? = null

    /** Dotik zunaj prazne razprte lupe jo zapre (kot pri drugih iskalnih vrsticah). */
    override fun dispatchTouchEvent(dogodek: android.view.MotionEvent): Boolean {
        si.safeer.tv.os.DomacaLupa.dotik(this, dogodek)
        return super.dispatchTouchEvent(dogodek)
    }

    /** Nazaj zapre odprt predal stranske vrstice; crka s tipkovnice zacne iskanje v lupi. */
    override fun dispatchKeyEvent(dogodek: android.view.KeyEvent): Boolean {
        if (vrstica?.prestrezi(dogodek) == true) return true
        if (si.safeer.tv.os.DomacaLupa.tipka(this, dogodek)) return true
        return super.dispatchKeyEvent(dogodek)
    }

    /** Nazaj: najprej zapre ploscico Sporocil in razprto lupo, sele nato zapusti domaci zaslon. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (si.safeer.tv.os.SporocilaZnak.zapri(this)) return
        if (si.safeer.tv.os.DomacaLupa.nazaj(this)) return
        if (dock?.skrijCeOdprt() == true) return
        super.onBackPressed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        si.safeer.tv.os.Predgretje.zazeni(this)
        si.safeer.tv.os.Tema.uporabi(this, predvajalnik = false)
        super.onCreate(savedInstanceState)
        nastaviPostavitev()
        // Safeer Link (sredisce) naj tece, kadar ga je uporabnik vklopil - tudi ce ga je Android ali
        // uporabnik (prisilna ustavitev) medtem ustavil; do zdaj se je vrnil sele ob ponovnem zagonu naprave.
        try { si.safeer.tv.cast.HubStoritev.zagotovi(this) } catch (_: Throwable) { }
        // Brez dovoljenja za obvestila naprava ne more vprasati lastnika, kadar Android za
        // brisanje ali vrtenje fotografije zahteva njegovo privolitev (PotrditevActivity).
        si.safeer.tv.link.Obvestila.zaprosiEnkrat(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        si.safeer.tv.os.SporocilaZnak.zapri(this)
        nastaviPostavitev()
        si.safeer.tv.os.SporocilaZnak.osvezi(this, kuverta)
        // Nov setContentView je dock odstranil iz okna: narise se znova (isto stanje, odprt/skrit).
        dock?.osvezi()
    }

    private fun nastaviPostavitev() {
        // Ena stranska vrstica za vse zaslone Safeer OS (lastnik, 19. 10. 2026): skrita na ozkem zaslonu, tirnica na sirokem.
        val v = si.safeer.tv.os.StranskaVrstica.ovij(this, R.layout.tablet_activity_domov, si.safeer.tv.os.StranskaVrstica.Razdelek.DOMOV)
        v.naAktivno = { drsnik?.smoothScrollTo(0, 0) }
        vrstica = v
        setContentView(v)
        Robovi.uporabi(this)
        val root = findViewById<View>(R.id.koren)
        koren = root
        Ozadje.uporabi(this, root)

        drsnik = findViewById(R.id.drsnik)
        vnosIskanje = findViewById(R.id.vnosIskanje)
        stanjeOkvir = findViewById(R.id.stanjeOkvir)
        stanjePika = findViewById(R.id.stanjePika)
        stanjeBesedilo = findViewById(R.id.stanjeBesedilo)
        naslovPozdrav = findViewById(R.id.naslovPozdrav)
        podnaslovPozdrav = findViewById(R.id.podnaslovPozdrav)

        karticaBrskalnik = findViewById(R.id.karticaBrskalnik)
        karticaZaslon = findViewById(R.id.karticaZaslon)
        karticaMediji = findViewById(R.id.karticaMediji)
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

        pripraviGlavo()
        pripraviIskanje()
        pripraviVelikeKartice()
        pripraviSpodnjePlosce()

        pokaziStanje()
        narisi()
        osveziScit()
    }

    override fun onStart() {
        super.onStart()
        si.safeer.tv.os.SporocilaZnak.osvezi(this, kuverta)
        link.dodaj(this)
        zazeniSprejemnik(null)
        pokaziStanje()
        narisi()
        osveziScit()
        isci()
        SpletneAplikacije.osveziIkone(this) {
            glavna.post { narisiAplikacije() }
        }
        si.safeer.tv.os.GlasbaStoritev.poslusalci.add(medijiPoslusalec)
        si.safeer.tv.os.GlasbaStoritev.osveziKartico(this)
        // Naloga 127 (popravek 118): mini predvajalnik je na pravem domacem zaslonu telefona in tablice.
        if (dock == null) dock = si.safeer.tv.os.MiniPredvajalnik.naOknu(this) { startActivity(si.safeer.tv.os.GlasbaStoritev.namenKartice(this)) }
        si.safeer.tv.os.OddaljenoPredvajanje.poslusalci.add(medijiPoslusalec)
        dock?.osvezi()
    }

    private var dock: si.safeer.tv.os.MiniPredvajalnik? = null

    /**
     * Sprejemnik Safeer Linka (ukazi drugih naprav: zagon aplikacij, datoteke, deljenje zaslona). Brez
     * njega je telefon v Linku le, dokler je Safeer OS odprt, in ga racunalnik ne vidi. Tece kot storitev
     * v ospredju, dokler ga uporabnik ne ugasne; zazene se samo, ce je naprava seznanjena s srediscem.
     */
    private fun zazeniSprejemnik(hubUrl: String?) {
        try {
            if (hubUrl == null && (si.safeer.tv.cast.CastReceiverService.instance != null ||
                    !si.safeer.tv.cast.CastReceiverService.isConfigured(this))) return
            si.safeer.tv.cast.CastReceiverService.start(this, hubUrl,
                getString(R.string.os_ime_vrste) + " (" + android.os.Build.MODEL + ")")
        } catch (e: Throwable) {
            android.util.Log.w("SafeerTablica", "Sprejemnika Linka ni bilo mogoce zagnati: ${e.message}")
        }
    }

    /** Kartica Mediji kaze, kaj se predvaja (tudi ko predvajanje tece v ozadju). */
    private val medijiPoslusalec: () -> Unit = { runOnUiThread { si.safeer.tv.os.GlasbaStoritev.osveziKartico(this); dock?.osvezi() } }

    override fun onStop() {
        si.safeer.tv.os.GlasbaStoritev.poslusalci.remove(medijiPoslusalec)
        si.safeer.tv.os.OddaljenoPredvajanje.poslusalci.remove(medijiPoslusalec)
        dock?.ustavi()
        link.odstrani(this)
        super.onStop()
    }

    /**
     * Ena iskalna lupa (lastnik, 11. 10. 2026): vrstica na vrhu napises ali poves - »Sintel na TV« prikaze na drugi
     * napravi, spletni naslov odpre brskalnik, vse drugo isce Medijski center (z vrsto »Na spletu«). DomacaLupa.
     */
    private fun pripraviIskanje() {
        val polje = vnosIskanje ?: return
        val okvir = findViewById<android.view.ViewGroup>(R.id.iskalnaVrsticaOkvir) ?: return
        // Kompaktna lupa: na ozkem zaslonu se ob razprtju umakne stanje Linka, lupa dobi celo glavo.
        si.safeer.tv.os.DomacaLupa.prevzemi(this, polje, okvir, kompaktno = true,
            umakni = listOfNotNull(findViewById<View>(R.id.stanjeVsebnik))) { odpriVBrskalniku(null) }
    }

    /**
     * Glava: Sporocila kot na namizju in televizorju - kuverta z znacko zgoraj desno (ne postavka stranske vrstice).
     * Siroko okno odpre stransko ploscico, telefon in tablica pokonci Sporocila cez ves zaslon (SporocilaZnak).
     */
    private fun pripraviGlavo() {
        val glava = findViewById<LinearLayout>(R.id.glava) ?: return
        val d = resources.displayMetrics.density
        val k = si.safeer.tv.os.SporocilaZnak.gumb(this, 44) { resources.configuration.screenWidthDp >= 840 }
        glava.addView(k, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()).apply { marginStart = (8 * d).toInt() })
        kuverta = k
    }

    private fun pripraviVelikeKartice() {
        karticaBrskalnik?.setOnClickListener {
            odpriVBrskalniku(null)
        }
        karticaZaslon?.setOnClickListener { odpriZaslon() }
        // Naloga 105: ista namera kot kartica v Safeer OS na TV in v stranski vrstici ([si.safeer.tv.os.GlasbaStoritev.namenKartice]).
        karticaMediji?.setOnClickListener {
            val namera = si.safeer.tv.os.GlasbaStoritev.namenKartice(this)
            android.util.Log.i("SafeerOsMedia", "kartica Medijski center: ${namera.component?.className ?: namera.action}")
            odpriVarno(namera, getString(R.string.os_mediji_kartica))
        }
        if (karticaMediji == null) android.util.Log.w("SafeerOsMedia", "kartica Medijski center: ni je v postavitvi")
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
            // Ista pot kot kartica Povezani zasloni (izbira naprave, cakanje na Link).
            odpriZaslon()
        }
        hitriDatoteke?.setOnClickListener {
            odpriVarno(Intent(this, DatotekeActivity::class.java), getString(R.string.tablet_datoteke))
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
            android.util.Log.w("SafeerOsMedia", "odpiranje ni uspelo ($naslov): ${e.javaClass.simpleName}")
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
                Toast.makeText(this, if (si.safeer.tv.cast.HubPairing.kodaZaprtaPriSrediscu()) getString(R.string.link_seznanitev_zaprta)
                    else getString(R.string.tablet_ni_odgovora, hub.ime), Toast.LENGTH_LONG).show() })
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
                zazeniSprejemnik(hub.naslov)
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
     * Povezani zasloni: seznam vseh naprav z zaslonom, ki jih ta naprava sme gledati, s prosnjami in napravami brez
     * povezave (os/PovezaniZasloni, pravila v os/SeznamZaslonov). Kartica je vedno na domacem zaslonu; okno se osvezuje
     * samo, zato ne cakamo vec, da se Link poveze (prej: »Povezujem« in okno »ni povezano« po 4 s).
     */
    private fun odpriZaslon() {
        si.safeer.tv.os.PovezaniZasloni.pokaziSeznam(this, link)
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

                // Ikona pove, kaj naprava je: telefon, televizor, racunalnik ali zaslon (tablica).
                val jeTelefon = n.vloga == "phone" || n.vloga == "telefon" || n.platforma == "phone"
                ikona?.setImageResource(when {
                    jeTelefon -> R.drawable.os_ikona_telefon
                    n.platforma == "tv" -> R.drawable.os_ikona_naprava
                    n.platforma == "linux" || n.platforma == "windows" -> R.drawable.os_ikona_racunalnik
                    n.platforma == "tablet" -> R.drawable.os_ikona_tablica
                    else -> R.drawable.os_ikona_zaslon
                })
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
                setTextColor(osBarva(R.color.os_umirjeno))
                textSize = 12f
                setPadding(0, 6, 0, 6)
            }
            seznam.addView(prazno)
        }
    }

    private fun pokaziStanje() {
        val kje = link.imeSredisca.ifBlank { getString(R.string.os_naprava_tv) }
        // Povezan samo sam s sabo (lastno sredisce, nobene druge naprave): ne "Povezano", ker to zavaja.
        val sami = link.povezan && LinkOdjemalec.drugeZaPrikaz(link.naprave, Identiteta.id(this)).isEmpty()
        val besedilo = when {
            sami -> getString(R.string.os_stanje_krajevni)
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
            if (link.povezan && !sami) {
                pika.setBackgroundResource(R.drawable.os_pika)
                pika.alpha = 1f
            } else {
                pika.setBackgroundResource(R.drawable.os_pika_rumena)
                pika.alpha = 0.7f
            }
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
        pika.backgroundTintList = if (s.naVoljo && s.vklopljen && s.tece) null else android.content.res.ColorStateList.valueOf(osBarva(R.color.os_siva_pika))
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
