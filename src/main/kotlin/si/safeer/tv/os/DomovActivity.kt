package si.safeer.tv.os

import si.safeer.tv.R

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.KeyEvent
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Domaci zaslon Safeer OS: Zacni (Splet, Datoteke, Safeer Link), naprave v Linku, aplikacije na
 * televizorju. Vse z D-padom; fokus je mint obroba (docs/TV-UI.md).
 *
 * Sredisce Linka gosti Safeer Browser na tem televizorju; Safeer OS vanj vstopi brez kode
 * (Sorodnik). Brez brskalnika lupina pove, kaj namestiti - ne vrze napake.
 */
class DomovActivity : OsActivity(), LinkOdjemalec.Poslusalec {

    private lateinit var koren: View
    private lateinit var stanjeBesedilo: TextView
    private lateinit var stanjePika: View
    private lateinit var ura: TextView
    private lateinit var datum: TextView
    private lateinit var meniDomov: View
    private lateinit var meniAplikacije: View
    private lateinit var meniZaslon: View
    private lateinit var meniDatoteke: View
    private lateinit var meniNaprave: View
    private lateinit var meniNastavitve: View
    private lateinit var karticaBrskalnik: View
    private lateinit var karticaZaslon: View
    private lateinit var karticaProgrami: View
    private lateinit var ploscaNaprave: View
    private lateinit var seznamPloscaNaprave: LinearLayout
    private lateinit var hitriWeb: View
    private lateinit var hitriRacunalnik: View
    private lateinit var hitriDatoteke: View
    private lateinit var hitriNastavitve: View
    private lateinit var ploscaScit: View
    private lateinit var scitStatusPika: View
    private lateinit var scitStatusBesedilo: TextView
    private lateinit var scitStevecBesedilo: TextView
    private lateinit var vrstaZacni: LinearLayout
    private lateinit var vrstaNadaljuj: LinearLayout
    private lateinit var naslovNadaljuj: TextView
    private lateinit var drsnikNadaljuj: View
    /** Naslov vrste Nadaljuj skupaj z gumbom Zapri vse (ta je v naslovu, da ga rob zaslona ne odreze). */
    private lateinit var glavaNadaljuj: View
    private lateinit var gumbZapriVse: TextView
    private lateinit var vrstaAplikacije: LinearLayout
    private lateinit var vrstaSpletne: LinearLayout
    private lateinit var opombaSpodaj: TextView
    private lateinit var pomocPlosek: TextView
    private lateinit var drsnik: View

    private val link by lazy { LinkUpravitelj.pridobi(this) }
    private val glavna = Handler(Looper.getMainLooper())
    private val tikUre = object : Runnable {
        override fun run() {
            val zdaj = Date()
            ura.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(zdaj)
            val dFormat = SimpleDateFormat("EEE, d. MMM", Locale.getDefault())
            datum.text = dFormat.format(zdaj).replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            glavna.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.os_activity_domov)
        koren = findViewById(R.id.koren)
        stanjeBesedilo = findViewById(R.id.stanjeBesedilo)
        stanjePika = findViewById(R.id.stanjePika)
        ura = findViewById(R.id.ura)
        datum = findViewById(R.id.datum)
        meniDomov = findViewById(R.id.meniDomov)
        meniAplikacije = findViewById(R.id.meniAplikacije)
        meniZaslon = findViewById(R.id.meniZaslon)
        meniDatoteke = findViewById(R.id.meniDatoteke)
        meniNaprave = findViewById(R.id.meniNaprave)
        meniNastavitve = findViewById(R.id.meniNastavitve)
        meniDomov.isActivated = true
        meniDomov.isSelected = true
        pripraviStranskiMeni()
        karticaBrskalnik = findViewById(R.id.karticaBrskalnik)
        karticaZaslon = findViewById(R.id.karticaZaslon)
        karticaProgrami = findViewById(R.id.karticaProgrami)
        pripraviVelikeKartice()
        ploscaNaprave = findViewById(R.id.ploscaNaprave)
        seznamPloscaNaprave = findViewById(R.id.seznamPloscaNaprave)
        hitriWeb = findViewById(R.id.hitriWeb)
        hitriRacunalnik = findViewById(R.id.hitriRacunalnik)
        hitriDatoteke = findViewById(R.id.hitriDatoteke)
        hitriNastavitve = findViewById(R.id.hitriNastavitve)
        ploscaScit = findViewById(R.id.ploscaScit)
        scitStatusPika = findViewById(R.id.scitStatusPika)
        scitStatusBesedilo = findViewById(R.id.scitStatusBesedilo)
        scitStevecBesedilo = findViewById(R.id.scitStevecBesedilo)
        pripraviSpodnjePlosce()
        vrstaZacni = findViewById(R.id.vrstaZacni)
        vrstaNadaljuj = findViewById(R.id.vrstaNadaljuj)
        naslovNadaljuj = findViewById(R.id.naslovNadaljuj)
        drsnikNadaljuj = findViewById(R.id.drsnikNadaljuj)
        pripraviGlavoNadaljuj()
        vrstaAplikacije = findViewById(R.id.vrstaAplikacije)
        vrstaSpletne = findViewById(R.id.vrstaSpletne)
        opombaSpodaj = findViewById(R.id.opombaSpodaj)
        pomocPlosek = findViewById(R.id.pomocPlosek)
        pomocPlosek.text = getString(R.string.os_pomoc_plosek)
        drsnik = findViewById(R.id.drsnik)
        narisiZacni()
        // Ce nas je odprla tipka Domov, smo res domaci zaslon tega televizorja.
        if (intent?.categories?.contains(Intent.CATEGORY_HOME) == true) Zaganjalnik.zabeleziZagonDomov(this)
    }

    private fun pripraviVelikeKartice() {
        karticaBrskalnik.onFocusChangeListener = fokus
        karticaBrskalnik.setOnClickListener {
            odpriVBrskalniku(null)
        }
        karticaBrskalnik.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    meniDomov.requestFocus()
                    true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    prvaSpodaj()?.requestFocus()
                    true
                } else false
            } else false
        }

        // Mediji (glasba in video) ob spletnem brskalniku: ena pot, ne podvojena v stranskem meniju.
        findViewById<View>(R.id.karticaMediji)?.let { m ->
            m.onFocusChangeListener = fokus
            m.setOnClickListener { odpriVarno(GlasbaStoritev.namenKartice(this), getString(R.string.os_mediji_kartica)) }
            m.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { prvaSpodaj()?.requestFocus(); true } else false
            }
        }

        karticaZaslon.onFocusChangeListener = fokus
        karticaZaslon.setOnClickListener {
            if (imamoZaslon) {
                odpriZaslon()
            } else {
                pokaziOknoNiPovezano(getString(R.string.os_zaslon))
            }
        }
        karticaZaslon.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                prvaSpodaj()?.requestFocus()
                true
            } else false
        }

        // Aplikacije vseh naprav so v stranski vrstici (Aplikacije): druga kartica z isto vsebino bi
        // uporabnika samo spraševala, katero naj izbere.
        karticaProgrami.visibility = View.GONE
        karticaProgrami.onFocusChangeListener = fokus
        karticaProgrami.setOnClickListener {
            if (imamoPrograme) {
                odpriVarno(Intent(this, AplikacijeHostaActivity::class.java), getString(R.string.os_programi))
            } else {
                pokaziOknoNiPovezano(getString(R.string.os_programi))
            }
        }
        karticaProgrami.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                prvaSpodaj()?.requestFocus()
                true
            } else false
        }
    }

    private fun pripraviSpodnjePlosce() {
        ploscaNaprave.onFocusChangeListener = fokus
        ploscaNaprave.setOnClickListener {
            if (link.povezan || !link.vprasamoZaNacin()) odpriVarno(Intent(this, NapraveActivity::class.java), getString(R.string.os_meni_naprave))
            else vprasajZaNacin()
        }
        ploscaNaprave.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    meniNaprave.requestFocus()
                    true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    hitriWeb.requestFocus()
                    true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    prvaSpodaj()?.requestFocus()
                    true
                } else false
            } else false
        }

        hitriWeb.onFocusChangeListener = fokus
        hitriWeb.setOnClickListener { odpriVBrskalniku(null) }
        hitriWeb.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    ploscaNaprave.requestFocus()
                    true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    vrstaNadaljuj.getChildAt((vrstaNadaljuj.childCount / 3).coerceAtLeast(0))?.requestFocus()
                    true
                } else false
            } else false
        }

        hitriRacunalnik.onFocusChangeListener = fokus
        hitriRacunalnik.setOnClickListener {
            if (imamoZaslon) {
                odpriZaslon()
            } else {
                pokaziOknoNiPovezano(getString(R.string.os_zaslon))
            }
        }
        hitriRacunalnik.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    vrstaNadaljuj.getChildAt((vrstaNadaljuj.childCount / 2).coerceAtLeast(0))?.requestFocus()
                    true
                } else false
            } else false
        }

        hitriDatoteke.onFocusChangeListener = fokus
        hitriDatoteke.setOnClickListener {
            odpriVarno(Intent(this, DatotekeActivity::class.java), getString(R.string.os_meni_datoteke))
        }
        hitriDatoteke.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    vrstaNadaljuj.getChildAt((vrstaNadaljuj.childCount * 2 / 3).coerceAtLeast(0))?.requestFocus()
                    true
                } else false
            } else false
        }

        hitriNastavitve.onFocusChangeListener = fokus
        hitriNastavitve.setOnClickListener {
            odpriVarno(Intent(this, NastavitveActivity::class.java), getString(R.string.os_meni_nastavitve))
        }
        hitriNastavitve.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    ploscaScit.requestFocus()
                    true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    vrstaNadaljuj.getChildAt((vrstaNadaljuj.childCount - 1).coerceAtLeast(0))?.requestFocus()
                    true
                } else false
            } else false
        }

        ploscaScit.onFocusChangeListener = fokus
        ploscaScit.setOnClickListener { podrobnostiScita() }
        ploscaScit.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    hitriNastavitve.requestFocus()
                    true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    vrstaNadaljuj.getChildAt((vrstaNadaljuj.childCount - 1).coerceAtLeast(0))?.requestFocus()
                    true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    true
                } else false
            } else false
        }
    }

    private fun osveziPloscoNaprave(naprave: List<LinkOdjemalec.Naprava>) {
        if (!::seznamPloscaNaprave.isInitialized) return
        seznamPloscaNaprave.removeAllViews()
        // Na domacem zaslonu dve, da glavni del ostane cel na zaslonu; vse so pod "›" (Naprave).
        val druge = naprave.filter { it.id != Identiteta.id(this) }.take(2)
        if (druge.isEmpty()) {
            val prazno = TextView(this)
            prazno.text = getString(R.string.os_plosca_naprave_prazno)
            prazno.setTextColor(resources.getColor(R.color.os_umirjeno, null))
            prazno.textSize = 12f
            prazno.setPadding(0, 12, 0, 12)
            seznamPloscaNaprave.addView(prazno)
            return
        }
        val infl = LayoutInflater.from(this)
        for (n in druge) {
            val v = infl.inflate(R.layout.os_vrstica_naprava, seznamPloscaNaprave, false)
            val ikona = v.findViewById<ImageView>(R.id.ikonaNaprave)
            val ime = v.findViewById<TextView>(R.id.imeNaprave)
            val pika = v.findViewById<View>(R.id.pikaNaprave)
            val stanje = v.findViewById<TextView>(R.id.stanjeNaprave)

            val jeTelefon = n.vloga == "phone" || n.vloga == "telefon"
            ikona.setImageResource(if (jeTelefon) R.drawable.os_ikona_telefon else R.drawable.os_ikona_zaslon)
            ime.text = n.ime.ifBlank { n.id }
            pika.alpha = if (link.povezan) 1f else 0.35f
            stanje.text = getString(if (link.povezan) R.string.os_plosca_naprava_povezana else R.string.os_plosca_naprava_pripravljenost)
            seznamPloscaNaprave.addView(v)
        }
    }

    private fun pokaziOknoNiPovezano(naslov: String) {
        if (isFinishing) return
        val okno = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(naslov)
            .setMessage(getString(R.string.os_kartica_ni_povezano_opis))
            .setPositiveButton(getString(android.R.string.ok), null)
            .setNeutralButton(getString(R.string.os_meni_naprave)) { _, _ ->
                odpriVarno(Intent(this, NapraveActivity::class.java), getString(R.string.os_meni_naprave))
            }
        Kontroler.pokazi(okno.show())
    }

    /**
     * Povezani zasloni: naprave v Safeer Linku, ki delijo svoj zaslon. Ena sama se odpre takoj
     * (brez odvecnega koraka); kadar jih je vec, uporabnik izbere, katero gleda na televizorju.
     */
    /** Kartica Povezani zasloni ze na domacem zaslonu pove, katere naprave lahko gledas. */
    private fun osveziOpisZaslona(naprave: List<LinkOdjemalec.Naprava>) {
        val t = findViewById<TextView>(R.id.karticaZaslonOpis) ?: return
        val jaz = Identiteta.id(this)
        val imena = naprave.filter { it.zmoznosti.contains("desktop") && it.id != jaz }.map { it.ime.ifBlank { it.id } }
        t.maxLines = 1
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        t.text = if (imena.isEmpty()) getString(R.string.os_zaslon_opis) else imena.joinToString(" · ")
    }

    private fun odpriZaslon() {
        val jaz = Identiteta.id(this)
        val naprave = link.naprave.filter { it.zmoznosti.contains("desktop") && it.id != jaz }
        fun odpri(r: LinkOdjemalec.Naprava?) {
            val namera = Intent(this, ZaslonActivity::class.java)
            r?.let { namera.putExtra(DatotekeActivity.EXTRA_RACUNALNIK, it.id) }
            odpriVarno(namera, getString(R.string.os_zaslon))
        }
        if (naprave.size < 2) { odpri(naprave.firstOrNull()); return }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_zaslon))
            .setItems(naprave.map { it.ime.ifBlank { it.id } }.toTypedArray()) { _, i -> odpri(naprave[i]) }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    private fun pripraviStranskiMeni() {
        meniDomov.setOnClickListener {
            (drsnik as? android.widget.ScrollView)?.smoothScrollTo(0, 0)
            fokusVsebine()
        }
        meniAplikacije.setOnClickListener {
            // En zaslon za vse aplikacije (izbira naprave je v vrsti zgoraj).
            odpriVarno(Intent(this, AplikacijeHostaActivity::class.java)
                .putExtra(AplikacijeHostaActivity.EXTRA_VIR, "vse"), getString(R.string.os_meni_aplikacije))
        }
        // Zasloni so na domacem zaslonu (kartica Povezani zasloni ob spletnem brskalniku); isti
        // gumb v stranski vrstici bi bil druga pot do istega.
        meniZaslon.visibility = View.GONE
        meniZaslon.setOnClickListener {
            odpriZaslon()
        }
        meniDatoteke.setOnClickListener {
            odpriVarno(Intent(this, DatotekeActivity::class.java), getString(R.string.os_meni_datoteke))
        }
        meniNaprave.setOnClickListener {
            if (link.povezan || !link.vprasamoZaNacin()) odpriVarno(Intent(this, NapraveActivity::class.java), getString(R.string.os_meni_naprave))
            else vprasajZaNacin()
        }
        meniNastavitve.setOnClickListener {
            odpriVarno(Intent(this, NastavitveActivity::class.java), getString(R.string.os_meni_nastavitve))
        }

        val menijskePostavke = listOf(meniDomov, meniAplikacije, meniZaslon, meniDatoteke, meniNaprave, meniNastavitve)
        for (postavka in menijskePostavke) {
            postavka.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    fokusVsebine()
                    true
                } else false
            }
        }
    }

    private fun fokusVsebine() {
        if (::karticaBrskalnik.isInitialized && karticaBrskalnik.visibility == View.VISIBLE) {
            karticaBrskalnik.requestFocus()
            return
        }
        for (vrsta in listOf(vrstaNadaljuj, vrstaZacni, vrstaSpletne, vrstaAplikacije)) {
            if (vrsta.visibility == View.VISIBLE && vrsta.childCount > 0) {
                val prvi = vrsta.getChildAt(0)
                if (prvi != null && prvi.visibility == View.VISIBLE) {
                    prvi.requestFocus()
                    return
                }
            }
        }
    }

    /** Vrstica z gumbi ploscka se pokaze takoj, ko uporabnik plosek prvic uporabi. */
    override fun plosekZaznan() {
        pomocPlosek.visibility =
            if (Kontroler.jePriklopljen(this)) View.VISIBLE else View.GONE
    }

    override fun onStart() {
        super.onStart()
        Ozadje.uporabi(this, koren)      // ozadje po izbiri uporabnika na celotnem zaslonu
        // Gumbi plosecka v vrstici pomoci, kadar je plosek v rabi; sicer je ne kazemo.
        plosekZaznan()
        glavna.post(tikUre)
        ZagonOb.pospravi(this)      // ce nas je ob vklopu odprlo obvestilo, naj ga uporabnik ne vidi
        narisiNadaljuj()
        narisiAplikacije()
        narisiSpletne()
        prevzemiSpletne()
        // Ikone, shranjene s prejsnjo razlicico, so bile premajhne in zato zamegljene; enkrat jih
        // poiscemo v vecji locljivosti.
        SpletneAplikacije.osveziIkone(this) { zZapomnjenimFokusom { narisiSpletne() } }
        link.dodaj(this)
        osveziScit()
        osveziPloscoNaprave(link.naprave)
        AplikacijeHostaActivity.predhodno(this)
        // Vrsta s spletnimi aplikacijami na domacem zaslonu televizorja ostane usklajena; ko ima
        // uporabnik prvo spletno aplikacijo, ga sistem enkrat vprasa, ali jo doda na domaci zaslon.
        DomacaVrsta.osvezi(this)
        if (SpletneAplikacije.seznam(this).isNotEmpty()) DomacaVrsta.ponudiEnkrat(this)
        if (link.vprasamoZaNacin()) glavna.postDelayed({ if (!isFinishing && link.vprasamoZaNacin()) vprasajZaNacin() }, 600)
        GlasbaStoritev.poslusalci.add(medijiPoslusalec)
        GlasbaStoritev.osveziKartico(this)
    }

    /** Kartica Mediji kaze, kaj se predvaja (tudi ko predvajanje tece v ozadju). */
    private val medijiPoslusalec: () -> Unit = { runOnUiThread { GlasbaStoritev.osveziKartico(this) } }

    /**
     * Kadar je Safeer OS domaci zaslon televizorja, tipka Nazaj nima kam: zapustili bi ga in
     * uporabnik bi ostal pred praznim zaslonom. Takrat je Nazaj brez ucinka, kot pri zaganjalniku.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (Zaganjalnik.jeIzbran(this)) return
        super.onBackPressed()
    }

    override fun onStop() {
        GlasbaStoritev.poslusalci.remove(medijiPoslusalec)
        glavna.removeCallbacks(tikUre)
        link.odstrani(this)
        super.onStop()
    }

    // ------------------------------------------------------------------ Link

    override fun naStanje(povezan: Boolean, sporocilo: String) {
        val kje = link.imeSredisca.ifBlank { getString(R.string.os_naprava_tv) }
        opombaSpodaj.visibility = View.GONE
        when {
            povezan -> pokaziStanje(true, getString(R.string.os_stanje_povezan, kje))
            sporocilo == "krajevni" -> pokaziStanje(false, getString(R.string.os_stanje_krajevni))
            sporocilo == "ni_linka" -> pokaziStanje(false, getString(R.string.os_stanje_ni_linka))
            sporocilo == "ni" -> pokaziStanje(false, getString(R.string.os_stanje_ni))
            else -> pokaziStanje(false, getString(R.string.os_stanje_povezujem))
        }
        osveziKartice()
        osveziPloscoNaprave(link.naprave)
    }

    override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) {
        val kje = link.imeSredisca.ifBlank { getString(R.string.os_naprava_tv) }
        if (link.povezan) pokaziStanje(true, getString(R.string.os_stanje_povezan, kje))
        osveziPloscoNaprave(naprave)
        // Racunalnik se je javil (ali odsel): vrsta Zacni dobi ali izgubi kartico s programi.
        val programi = naprave.any { it.zmoznosti.contains("apps") && it.id != Identiteta.id(this) }
        val zaslon = naprave.any { it.zmoznosti.contains("desktop") && it.id != Identiteta.id(this) }
        if (programi != imamoPrograme || zaslon != imamoZaslon) {
            imamoPrograme = programi; imamoZaslon = zaslon
            zZapomnjenimFokusom { narisiZacni() }
        }
        // Ob vstopu Link navadno se ni povezan in vprasanje, kaj tece, ostane brez odgovora:
        // vprasamo znova, ko se racunalnik javi.
        if (programi) osveziTecejo(Nadaljuj.seznam(this).filter { jeProgram(it) })
        osveziOpisZaslona(naprave)
        AplikacijeHostaActivity.predhodno(this)
    }

    override fun naNaslov(url: String, naslov: String, od: String) {
        odpriVBrskalniku(url)
        Toast.makeText(this, getString(R.string.os_poslano_tv), Toast.LENGTH_SHORT).show()
    }

    override fun naBesedilo(besedilo: String, od: String) {
        Toast.makeText(this, getString(R.string.os_prejeto_besedilo, od) + "\n" + besedilo.take(200), Toast.LENGTH_LONG).show()
    }

    override fun naZavrnitev() { }

    private fun pokaziStanje(povezan: Boolean, besedilo: String) {
        stanjeBesedilo.text = besedilo
        stanjePika.alpha = if (povezan) 1f else 0.35f
    }

    // ------------------------------------------------------------------ Zacni

    private var karticaDatoteke: View? = null
    private var karticaLink: View? = null
    /** Ali kateri racunalnik v Linku deli svoje programe (zmoznost "apps"). */
    private var imamoPrograme = false
    /** Ali kateri racunalnik v Linku deli svoj zaslon (zmoznost "desktop"). */
    private var imamoZaslon = false

    /**
     * Fokus mora preziveti osvezitev. Vrste se ponovno izrisejo same od sebe (racunalnik se javi,
     * spletna aplikacija dobi ikono, Scit odgovori), pri tem pa se poglede zavrze skupaj s fokusom
     * in ta skoci na zacetek - sredi tipkanja z daljincem je to zoprno. Zato vsaka kartica nosi
     * svojo oznako, pred izrisom si jo zapomnimo in jo po izrisu poiscemo nazaj.
     */
    private fun zZapomnjenimFokusom(kaj: () -> Unit) {
        val oznaka = currentFocus?.tag as? String
        kaj()
        if (oznaka.isNullOrEmpty()) return
        drsnik.post { najdiPoOznaki(oznaka)?.takeIf { !it.hasFocus() }?.requestFocus() }
    }

    private fun najdiPoOznaki(oznaka: String): View? {
        for (vrsta in listOf(vrstaNadaljuj, vrstaZacni, vrstaSpletne, vrstaAplikacije)) {
            for (i in 0 until vrsta.childCount) {
                val v = vrsta.getChildAt(i) ?: continue
                if (v.tag == oznaka) return v
            }
        }
        return null
    }

    /** Ena kartica v vrsti Zacni; [kljuc] se shrani v vrstni red, zato se nikoli ne spremeni. */
    private class Zacni(val kljuc: String, val ikona: Int, val naslov: String, val ob: () -> Unit)

    /**
     * Vrsta Zacni. Kaj je v njej, dolocimo mi (in kaj je ta trenutek na voljo), v katerem vrstnem
     * redu, pa uporabnik - dolg pritisk na kartico jo premakne levo ali desno.
     *
     * [fokusKljuc]: po premiku naj fokus ostane na isti kartici.
     */
    private fun narisiZacni(fokusKljuc: String = "") {
        vrstaZacni.removeAllViews()
        karticaDatoteke = null; karticaLink = null; karticaScit = null
        val vse = ArrayList<Zacni>()
        vse.add(Zacni("splet", R.drawable.os_ikona_splet, getString(R.string.os_splet)) {
            odpriVBrskalniku(null)
        })
        vse.add(Zacni("datoteke", R.drawable.os_ikona_datoteke, getString(R.string.os_datoteke)) {
            odpriVarno(Intent(this, DatotekeActivity::class.java), getString(R.string.os_datoteke))
        })
        // Naprave so dobile svoj zaslon: na domacem je bila to se ena vrsta kartic in je jemala
        // prostor spletnim aplikacijam, ki jih uporabnik odpira vsak dan.
        vse.add(Zacni("naprave", R.drawable.os_ikona_link, getString(R.string.os_naprave_naslov)) {
            if (link.povezan || !link.vprasamoZaNacin()) odpriVarno(Intent(this, NapraveActivity::class.java), getString(R.string.os_naprave_naslov))
            else vprasajZaNacin()
        })
        vse.add(Zacni("scit", R.drawable.os_ikona_scit, getString(R.string.os_scit)) { preklopiScit() })
        // Zaslon racunalnika: kartica se pokaze samo, kadar ga racunalnik res deli.
        if (imamoZaslon) {
            vse.add(Zacni("zaslon", R.drawable.os_ikona_zaslon, getString(R.string.os_zaslon)) {
                odpriZaslon()
            })
        }
        // Programi racunalnika: kartico pokazemo samo, kadar jih kaksen racunalnik res deli -
        // sicer bi obljubljala nekaj, cesar ni.
        vse.add(Zacni("nastavitve", R.drawable.os_ikona_nastavitve, getString(R.string.os_nastavitve)) {
            odpriVarno(Intent(this, NastavitveActivity::class.java), getString(R.string.os_nastavitve))
        })

        val red = Vrstni.red(this, KLJUC_ZACNI, vse.map { it.kljuc })
        var zeljeni: View? = null
        for (kljuc in red) {
            val z = vse.firstOrNull { it.kljuc == kljuc } ?: continue
            val v = dodajMalo(z.ikona, z.naslov, z.ob)
            v.tag = "zacni:" + z.kljuc
            v.setOnLongClickListener { moznostiZacni(z, red); true }
            when (z.kljuc) {
                "datoteke" -> karticaDatoteke = v
                "naprave" -> karticaLink = v
                "scit" -> karticaScit = v
            }
            if (z.kljuc == fokusKljuc) zeljeni = v
        }
        uravnajVrsto(vrstaZacni, NAJMANJSA_ZACNI_DP, NAJVECJA_ZACNI_DP)
        // Fokus prevzamemo samo, kadar ga nihce nima (prvi izris). Ce uporabnik ravno izbira
        // spodaj, ga racunalnik, ki se je pravkar javil, ne sme vreci nazaj gor.
        if (zeljeni != null) zeljeni.post { zeljeni.requestFocus() }
        else if (currentFocus == null) vrstaZacni.getChildAt(0)?.requestFocus()
        osveziKartice()
        osveziScit()
    }

    /** Dolg pritisk na kartico v vrsti Zacni: uporabnik si vrsto uredi po svoje. */
    private fun moznostiZacni(z: Zacni, red: List<String>) {
        val mesto = red.indexOf(z.kljuc)
        val dejanja = ArrayList<Pair<String, () -> Unit>>()
        if (z.kljuc == "scit") dejanja.add(getString(R.string.os_scit_podrobnosti) to { podrobnostiScita() })
        if (mesto > 0) dejanja.add(getString(R.string.os_spletne_levo) to { premakniZacni(z, red, -1) })
        if (mesto in 0 until red.size - 1) dejanja.add(getString(R.string.os_spletne_desno) to { premakniZacni(z, red, 1) })
        if (dejanja.isEmpty()) return
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(z.naslov)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    private fun premakniZacni(z: Zacni, red: List<String>, zamik: Int) {
        if (!Vrstni.premakni(this, KLJUC_ZACNI, red, z.kljuc, zamik)) return
        narisiZacni(z.kljuc)
    }

    /** Na majhnih karticah opisov ni; stanje pove samo Scit (in vrstica na vrhu zaslona). */
    private fun osveziKartice() { }

    /**
     * Ce Safeer Link ne tece, uporabnik enkrat izbere: vklopi Safeer Link (naprave, datoteke z
     * racunalnika, daljinec) ali delaj krajevno (samo viri tega televizorja). Izbira se zapomni.
     */
    /** Odprto okno izbire nacina; onStart (npr. po ugasnjenem zaslonu) ga ne sme odpreti se enkrat. */
    private var nacinOkno: android.app.AlertDialog? = null

    private fun vprasajZaNacin() {
        if (link.povezan) { odpriLinkVBrskalniku(); return }
        if (nacinOkno?.isShowing == true) return
        // Prijavno okno Safeer OS (enako kot na racunalniku): QR koda, 6-mestna koda ali nadaljuj brez
        // povezave. Staro vprasanje »Link / krajevno« ostane le, ce okna ni mogoce odpreti.
        try {
            startActivity(android.content.Intent(this, PrijavaActivity::class.java).putExtra(PrijavaActivity.EXTRA_PRVI_ZAGON, true))
            return
        } catch (_: Throwable) { }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_nacin_naslov))
            .setMessage(getString(R.string.os_nacin_opis))
            .setPositiveButton(getString(R.string.os_nacin_link)) { _, _ ->
                link.vklopiLink()
                Toast.makeText(this, getString(R.string.os_nacin_link_vklopljen), Toast.LENGTH_SHORT).show()
                osveziKartice()
            }
            .setNegativeButton(getString(R.string.os_nacin_krajevni)) { _, _ ->
                link.krajevniNacin()
                Toast.makeText(this, getString(R.string.os_nacin_krajevni_izbran), Toast.LENGTH_LONG).show()
                osveziKartice()
            }
            .setCancelable(true)
            .let { val o = it.show(); nacinOkno = o; Kontroler.pokazi(o) }
    }

    // ------------------------------------------------------------------ Safeer Scit (filter DNS za ves televizor)

    private var karticaScit: View? = null
    private var scitStanje: Scit.Stanje? = null

    private fun osveziScit() {
        Scit.stanje(this) { pokaziScit(it) }
    }

    /**
     * Na kartici je samo stanje in stevilka - dolgo besedilo se je na televizorju odrezalo na robu.
     * Vse ostalo (kaj Scit sploh blokira, zakaj je prekinjen, kaj potrebuje) je pod "Podrobnosti",
     * ki jih uporabnik odpre z zadrzanim OK.
     */
    private fun pokaziScit(s: Scit.Stanje) {
        scitStanje = s
        val opis = karticaScit?.findViewById<TextView>(R.id.stanje)
        opis?.visibility = View.VISIBLE
        opis?.text = when {
            !s.naVoljo -> getString(R.string.os_scit_kratko_ni)
            // Na majhni kartici je prostora za dve besedi: dolg napis se je odrezal sredi besede.
            s.vklopljen && s.tece -> getString(R.string.os_scit_ploscica_vklopljen, s.blokiranih)
            s.vklopljen -> getString(R.string.os_scit_kratko_prekinjen)
            else -> getString(R.string.os_scit_kratko_izklopljen)
        }
        if (::scitStatusBesedilo.isInitialized && ::scitStevecBesedilo.isInitialized && ::scitStatusPika.isInitialized) {
            when {
                !s.naVoljo -> {
                    scitStatusBesedilo.text = getString(R.string.os_plosca_scit_izklopljen)
                    scitStatusPika.alpha = 0.35f
                    scitStevecBesedilo.text = getString(R.string.os_scit_ni_brskalnika)
                }
                s.vklopljen && s.tece -> {
                    scitStatusBesedilo.text = getString(R.string.os_plosca_scit_vklopljen)
                    scitStatusPika.alpha = 1f
                    scitStevecBesedilo.text = if (s.blokiranih > 0)
                        getString(R.string.os_plosca_scit_stanje_blokiranih, s.blokiranih)
                    else getString(R.string.os_plosca_scit_aktivna)
                }
                s.vklopljen -> {
                    scitStatusBesedilo.text = getString(R.string.os_plosca_scit_prekinjen)
                    scitStatusPika.alpha = 0.5f
                    scitStevecBesedilo.text = getString(R.string.os_scit_prekinjen)
                }
                else -> {
                    scitStatusBesedilo.text = getString(R.string.os_plosca_scit_izklopljen)
                    scitStatusPika.alpha = 0.35f
                    scitStevecBesedilo.text = getString(R.string.os_scit_izklopljen_kratko)
                }
            }
            // Zelena pika pomeni, da Scit dela; izklopljen ali prekinjen ima sivo, ne zbledelo zeleno.
            scitStatusPika.backgroundTintList = if (s.naVoljo && s.vklopljen && s.tece) null else android.content.res.ColorStateList.valueOf(getColor(R.color.os_siva_pika))
        }
    }

    /** Celotna razlaga Scita; na kartici je ni, ker je predolga. */
    private fun podrobnostiScita() {
        val s = scitStanje
        val besedilo = when {
            s == null -> getString(R.string.os_scit_preverjam)
            !s.naVoljo -> getString(R.string.os_scit_ni_brskalnika)
            s.vklopljen && s.tece -> getString(R.string.os_scit_vklopljen_opis, s.blokiranih)
            s.vklopljen -> getString(R.string.os_scit_prekinjen)
            else -> getString(R.string.os_scit_izklopljen_opis)
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_scit))
            .setMessage(besedilo + "\n\n" + getString(R.string.os_scit_nastavitev_opis))
            .setPositiveButton(getString(
                if (s?.vklopljen == true) R.string.os_zaganjalnik_izklopi_kratko else R.string.os_vklopi)) { _, _ -> preklopiScit() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    private fun preklopiScit() {
        val s = scitStanje
        if (s == null || !s.naVoljo) { Toast.makeText(this, getString(R.string.os_scit_ni_brskalnika), Toast.LENGTH_LONG).show(); return }
        if (s.vklopljen) {
            Scit.izklopi(this) { pokaziScit(it); Toast.makeText(this, getString(R.string.os_scit_izklopljen_kratko), Toast.LENGTH_SHORT).show() }
        } else {
            Scit.vklopi(this) { nov ->
                if (nov.potrebujeOkno || (!nov.vklopljen && nov.naVoljo)) Scit.odpriVklop(this) else pokaziScit(nov)
            }
        }
        // Storitev se zazene ali ustavi sele trenutek kasneje: stanje preberemo se enkrat, ko je res novo.
        glavna.postDelayed({ osveziScit() }, 2_500)
    }

    /**
     * Televizor ni telefon: kar pade cez rob zaslona, ni "malo zunaj", ampak odrezano besedilo -
     * in televizorji vrh tega odrezejo se nekaj slikovnih tock (overscan). Zato sirine kartic ne
     * ugibamo vnaprej: izracunamo jo iz sirine zaslona tako, da med robovoma stoji **cel** kos
     * kartic. Kar je vec, se pokaze ob pomiku, nikoli pa ni na zaslonu polovica besedila.
     */
    /**
     * Sirine kartic v vrsti: izracunamo jih tako, da se celo stevilo kartic natanko izide do roba
     * zaslona. Brez tega je zadnja vidna kartica odrezana na robu in vrsta je videti pokvarjena.
     * [najvecDp] prepreci, da bi se ob dveh ali treh karticah raztegnile cez pol zaslona.
     */
    private fun uravnajVrsto(vrsta: LinearLayout, najmanjDp: Int, najvecDp: Int = 0) {
        val m = resources.displayMetrics
        val rob = resources.getDimensionPixelSize(R.dimen.os_rob)
        val vrzel = (16 * m.density).toInt()
        val naVoljo = m.widthPixels - 2 * rob
        val n = vrsta.childCount
        if (n == 0 || naVoljo <= 0) return
        val najmanj = (najmanjDp * m.density).toInt()
        val kolikoGre = ((naVoljo + vrzel) / (najmanj + vrzel)).coerceAtLeast(1)
        val k = if (kolikoGre > n) n else kolikoGre
        var sirina = (naVoljo - (k - 1) * vrzel) / k
        if (najvecDp > 0) sirina = sirina.coerceAtMost((najvecDp * m.density).toInt())
        for (i in 0 until n) {
            val v = vrsta.getChildAt(i) ?: continue
            val lp = v.layoutParams as? LinearLayout.LayoutParams ?: continue
            lp.width = sirina
            lp.marginEnd = if (i == n - 1) 0 else vrzel
            v.layoutParams = lp
        }
    }

    /**
     * Kartica v vrsti Zacni: ikona in ime, brez opisa. Na zaslonu 960x540 dp so prav opisi pojedli
     * toliko visine, da tri vrste niso sle skupaj in se je zadnja rezala. Kar je treba povedati o
     * stanju, gre v eno kratko mint vrstico (Scit), vse ostalo pa pod zadrzan OK.
     */
    private fun dodajMalo(ikona: Int, naslov: String, ob: () -> Unit): View {
        val v = LayoutInflater.from(this).inflate(R.layout.os_kartica_mala, vrstaZacni, false)
        v.findViewById<ImageView>(R.id.ikona).setImageResource(ikona)
        v.findViewById<TextView>(R.id.naslov).text = naslov
        v.setOnClickListener { ob() }
        v.onFocusChangeListener = fokus
        vrstaZacni.addView(v)
        return v
    }

    // ------------------------------------------------------------------ Nadaljuj
    //
    // Pot do filma je na televizorju dolga: Datoteke, racunalnik, mapa, mapa, datoteka. Kdor je
    // vceraj gledal film, ga hoce danes odpreti takoj - zato je prva vrsta domacega zaslona to,
    // kar je nazadnje odprl. Vrsta se pokaze samo, kadar kaj je; prazne vrste ne kazemo.

    private fun narisiNadaljuj() {
        val vnosi = Nadaljuj.seznam(this)
        // Na domacem zaslonu je ta vrsta samo za programe, ki ta trenutek tecejo na racunalniku:
        // z njo jih zapres s televizorja (krizec, Zapri vse). Ko nic ne tece - ali tega se ne vemo - je ni.
        val odprti = vnosi.filter { jeProgram(it) && tecejo?.contains(it.kljuc()) == true }
        vrstaNadaljuj.removeAllViews()
        val vidno = if (odprti.isEmpty()) View.GONE else View.VISIBLE
        glavaNadaljuj.visibility = vidno
        drsnikNadaljuj.visibility = vidno
        naslovNadaljuj.text = getString(R.string.os_odprto_na_racunalniku)
        for ((indeks, n) in odprti.withIndex()) {
            val v = LayoutInflater.from(this).inflate(R.layout.os_kartica_ikona, vrstaNadaljuj, false)
            val ikona = v.findViewById<ImageView>(R.id.ikona)
            val risba = ikonaPrograma(n)
            if (risba != null) ikona.setImageDrawable(risba) else ikona.setImageResource(ikonaZa(n.vrsta))
            v.findViewById<TextView>(R.id.ime).text = n.ime
            v.onFocusChangeListener = fokus
            v.setOnClickListener { zadnjaOznaka = "nadaljuj:" + n.kljuc(); odpriNadaljuj(n) }
            v.setOnLongClickListener { moznostiNadaljuj(n); true }
            dodajKrizec(ikona)
            val jePrvi = indeks == 0
            v.setOnKeyListener { _, koda, dogodek ->
                if (koda == KeyEvent.KEYCODE_BUTTON_X || koda == KeyEvent.KEYCODE_DEL) {
                    if (dogodek.action == KeyEvent.ACTION_UP) zapriProgram(n)
                    true
                } else if (dogodek.action == KeyEvent.ACTION_DOWN && jePrvi && koda == KeyEvent.KEYCODE_DPAD_LEFT) {
                    meniDomov.requestFocus()
                    true
                } else false
            }
            v.tag = "nadaljuj:" + n.kljuc()
            vrstaNadaljuj.addView(v)
        }

        // Zapri vse: ena tipka za vse programe, ki jih je televizor zagnal na racunalniku. Je v
        // naslovu vrste (desno), ne na koncu vrste - tam jo je rob zaslona odrezal na pol.
        val vsiProgrami = vnosi.filter { jeProgram(it) }
        val programi = vsiProgrami.filter { tece(it) }
        osveziTecejo(vsiProgrami)
        gumbZapriVse.visibility = if (programi.isEmpty()) View.GONE else View.VISIBLE
        gumbZapriVse.text = getString(R.string.fmt_ikona_besedilo_2, "\u2715", getString(R.string.os_zapri_vse, programi.size))
        gumbZapriVse.setOnClickListener { zapriVse(programi) }
        // Vrsto smo narisali na novo in izbrana kartica je izginila z njo: uporabnik, ki se vraca
        // iz seje, bi sicer ostal brez izbire (prvi pritisk nekam, kamor ni hotel).
        val f = currentFocus
        val zadnja = zadnjaOznaka
        if (zadnja != null && (f == null || !f.isAttachedToWindow || f.parent !== vrstaNadaljuj)) {
            // Enkratno: le ob vrnitvi s kartice, ki jo je uporabnik odprl; kasneje fokusa ne jemljemo.
            zadnjaOznaka = null
            drsnik.postDelayed({
                if (!isFinishing) (najdiPoOznaki(zadnja) ?: vrstaNadaljuj.getChildAt(0))?.requestFocus()
            }, 60)
        }
        // Desno od zadnje kartice ni nicesar vec: fokus ostane v vrsti in ne skoci v vrsto spodaj.
        if (vrstaNadaljuj.childCount > 0) {
            val zadnja = vrstaNadaljuj.getChildAt(vrstaNadaljuj.childCount - 1)
            if (zadnja.id == View.NO_ID) zadnja.id = View.generateViewId()
            zadnja.nextFocusRightId = zadnja.id
        }
    }

    /** Naslov vrste Nadaljuj postane vrstica: naslov levo, gumb Zapri vse desno. */
    private fun pripraviGlavoNadaljuj() {
        val d = resources.displayMetrics.density
        val stars = naslovNadaljuj.parent as ViewGroup
        val mesto = stars.indexOfChild(naslovNadaljuj)
        val mere = naslovNadaljuj.layoutParams
        stars.removeView(naslovNadaljuj)
        val vrstica = LinearLayout(this)
        vrstica.orientation = LinearLayout.HORIZONTAL
        vrstica.gravity = android.view.Gravity.CENTER_VERTICAL
        // Vidnost zdaj vodi vrstica; naslov sam je v postavitvi lahko skrit (prej ga je prizgala vrsta).
        naslovNadaljuj.visibility = View.VISIBLE
        vrstica.addView(naslovNadaljuj, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val gumb = TextView(this)
        gumb.setTextColor(android.graphics.Color.WHITE)
        gumb.textSize = 14f
        gumb.isFocusable = true
        gumb.isClickable = true
        gumb.setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (6 * d).toInt())
        val ozadje = android.graphics.drawable.GradientDrawable()
        ozadje.cornerRadius = 18 * d
        ozadje.setColor(android.graphics.Color.parseColor("#33CC2B3A"))
        ozadje.setStroke((2 * d).toInt(), android.graphics.Color.TRANSPARENT)
        gumb.background = ozadje
        gumb.onFocusChangeListener = View.OnFocusChangeListener { v, ima ->
            ozadje.setStroke((2 * d).toInt(), if (ima) android.graphics.Color.WHITE else android.graphics.Color.TRANSPARENT)
            ozadje.setColor(android.graphics.Color.parseColor(if (ima) "#CC2B3A" else "#33CC2B3A"))
            v.animate().scaleX(if (ima) 1.06f else 1f).scaleY(if (ima) 1.06f else 1f).setDuration(120).start()
            if (ima) (drsnik as? android.widget.ScrollView)?.smoothScrollTo(0, 0)
        }
        gumb.visibility = View.GONE
        vrstica.addView(gumb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = (24 * d).toInt() })
        // Vrstica mora biti siroka kot zaslon, sicer naslov (utez 1) dobi sirino 0 in izgine.
        mere.width = ViewGroup.LayoutParams.MATCH_PARENT
        stars.addView(vrstica, mesto, mere)
        glavaNadaljuj = vrstica
        gumbZapriVse = gumb
    }

    private fun jeProgram(n: Nadaljuj.Vnos) =
        n.vrsta == Nadaljuj.PROGRAM && n.program.isNotBlank() && n.racunalnik.isNotBlank()

    /**
     * Kateri programi res tecejo (kljuc vnosa). null = ne vemo (starejsi Control, racunalnik ni
     * dosegljiv): takrat krizec kazemo pri vseh, kot prej. Program, ki ne tece vec, ostane v vrsti
     * (z enim klikom ga znova odpres), le krizca in stetja v Zapri vse nima.
     */
    private var tecejo: Set<String>? = null
    /** Kartica, ki jo je uporabnik nazadnje odprl: ob vrnitvi (konec seje) je izbira spet na njej. */
    private var zadnjaOznaka: String? = null
    private var sprasujem = false

    private fun tece(n: Nadaljuj.Vnos) = tecejo?.contains(n.kljuc()) ?: true

    private fun osveziTecejo(programi: List<Nadaljuj.Vnos>) {
        if (sprasujem || programi.isEmpty()) return
        val poRacunalnikih = programi.groupBy { it.racunalnik }
        val zbrano = HashSet<String>()
        var cakam = poRacunalnikih.size
        var znano = true
        sprasujem = true
        for ((racunalnik, vnosi) in poRacunalnikih) {
            val polje = org.json.JSONArray()
            vnosi.forEach { polje.put(it.program) }
            link.ukaz(racunalnik, "apps.running", org.json.JSONObject().put("apps", polje), 8_000,
                LinkOdjemalec.Odgovor { izid, _ ->
                    runOnUiThread {
                        val podatki = izid?.optJSONObject("data")
                        if (izid?.optBoolean("ok") == true && podatki != null) {
                            val t = podatki.optJSONArray("running")
                            val imena = HashSet<String>()
                            if (t != null) for (i in 0 until t.length()) imena.add(t.optString(i))
                            vnosi.filter { it.program in imena }.forEach { zbrano.add(it.kljuc()) }
                        } else znano = false
                        cakam--
                        if (cakam == 0) {
                            sprasujem = false
                            val novo: Set<String>? = if (znano) zbrano else null
                            if (novo != tecejo && !isFinishing) {
                                tecejo = novo
                                zZapomnjenimFokusom { narisiNadaljuj() }
                            }
                        }
                    }
                })
        }
    }

    /** Majhen krizec v kotu ikone: ta program tece na racunalniku in ga lahko zapres. */
    private fun dodajKrizec(ikona: ImageView) {
        val stars = ikona.parent as? android.view.ViewGroup ?: return
        val mesto = stars.indexOfChild(ikona)
        val mere = ikona.layoutParams
        stars.removeView(ikona)
        val okvir = android.widget.FrameLayout(this)
        okvir.layoutParams = mere
        okvir.addView(ikona, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        val d = resources.displayMetrics.density
        val krizec = TextView(this)
        krizec.text = "\u2715"
        krizec.setTextColor(android.graphics.Color.WHITE)
        krizec.textSize = 11f
        krizec.gravity = android.view.Gravity.CENTER
        val ozadje = android.graphics.drawable.GradientDrawable()
        ozadje.shape = android.graphics.drawable.GradientDrawable.OVAL
        ozadje.setColor(android.graphics.Color.parseColor("#CC2B3A"))
        krizec.background = ozadje
        val vel = (20 * d).toInt()
        okvir.addView(krizec, android.widget.FrameLayout.LayoutParams(vel, vel,
            android.view.Gravity.TOP or android.view.Gravity.END))
        stars.addView(okvir, mesto)
    }

    /** Zapre vse programe, ki jih je zagnal televizor, in jih pospravi iz vrste Nadaljuj. */
    private fun zapriVse(programi: List<Nadaljuj.Vnos>) {
        var odgovorov = 0
        var zaprtih = 0
        for (n in programi) {
            link.ukaz(n.racunalnik, "apps.close", org.json.JSONObject().put("app", n.program), 10_000,
                LinkOdjemalec.Odgovor { izid, _ ->
                    odgovorov++
                    if (izid?.optBoolean("ok") == true) zaprtih++
                    // Tudi program, ki ze ne tece, v vrsti ne sodi vec med odprte.
                    if (izid?.optBoolean("ok") == true || izid?.optString("code") == "ne_tece") Nadaljuj.odstrani(this, n)
                    if (odgovorov == programi.size && !isFinishing) {
                        Toast.makeText(this, getString(R.string.os_zaprtih, zaprtih), Toast.LENGTH_LONG).show()
                        zZapomnjenimFokusom { narisiNadaljuj() }
                    }
                })
        }
    }

    private fun ikonaZa(vrsta: String): Int = when (vrsta) {
        Nadaljuj.VIDEO -> R.drawable.os_ikona_video
        Nadaljuj.GLASBA -> R.drawable.os_ikona_glasba
        Nadaljuj.SLIKA -> R.drawable.os_ikona_slika
        Nadaljuj.BESEDILO -> R.drawable.os_ikona_datoteka
        Nadaljuj.SPLETNA -> R.drawable.os_ikona_splet
        Nadaljuj.ZASLON -> R.drawable.os_ikona_zaslon
        else -> R.drawable.os_ikona_racunalnik
    }

    private fun moznostiNadaljuj(n: Nadaljuj.Vnos) {
        val okno = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(n.ime)
            .setPositiveButton(getString(R.string.os_nadaljuj_odstrani)) { _, _ ->
                Nadaljuj.odstrani(this, n)
                zZapomnjenimFokusom { narisiNadaljuj() }
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
        // Program, zagnan s televizorja, je doslej ostal odprt na racunalniku in jemal pomnilnik;
        // zapreti ga je bilo mogoce samo tam. Zdaj to gre z istega mesta, kjer si ga zagnal.
        if (n.vrsta == Nadaljuj.PROGRAM && n.program.isNotBlank() && n.racunalnik.isNotBlank()) {
            okno.setNeutralButton(getString(R.string.os_program_zapri)) { _, _ -> zapriProgram(n) }
        }
        Kontroler.pokazi(okno.show())
    }

    /** Vljudno zapre program na racunalniku (kot bi kliknil X); ce ne tece, to posteno pove. */
    private fun zapriProgram(n: Nadaljuj.Vnos) {
        link.ukaz(n.racunalnik, "apps.close", org.json.JSONObject().put("app", n.program), 10_000,
            LinkOdjemalec.Odgovor { izid, napaka ->
                if (isFinishing) return@Odgovor
                val ok = izid?.optBoolean("ok") == true
                // Zaprt program ne sodi vec v vrsto odprtih.
                if (ok || izid?.optString("code") == "ne_tece") {
                    Nadaljuj.odstrani(this, n)
                    zZapomnjenimFokusom { narisiNadaljuj() }
                }
                val sporocilo = when {
                    ok -> getString(R.string.os_program_zaprt, n.ime)
                    izid?.optString("code") == "ne_tece" -> getString(R.string.os_program_ne_tece, n.ime)
                    else -> izid?.optString("message").orEmpty().ifBlank { napaka.orEmpty() }
                }
                if (sporocilo.isNotBlank()) Toast.makeText(this, sporocilo, Toast.LENGTH_LONG).show()
            })
    }

    /**
     * Odpre, kar je uporabnik nazadnje gledal. Zetona in naslova streznika ne hranimo (velja samo,
     * dokler seja tece), zato ga za datoteke z racunalnika znova vprasamo - in ce racunalnika ni,
     * to posteno povemo, namesto da bi kartica tiho ne naredila nicesar.
     */
    private fun odpriNadaljuj(n: Nadaljuj.Vnos) {
        when (n.vrsta) {
            Nadaljuj.SPLETNA -> {
                val a = SpletneAplikacije.seznam(this).firstOrNull { it.url == n.url }
                if (a != null) zazeniSpletno(a) else odpriVBrskalniku(n.url)
            }
            Nadaljuj.ZASLON -> odpriVarno(Intent(this, ZaslonActivity::class.java)
                .putExtra(DatotekeActivity.EXTRA_RACUNALNIK, n.racunalnik), getString(R.string.os_zaslon))
            Nadaljuj.PROGRAM -> zazeniProgram(n)
            else -> if (n.krajevno) odpriDatoteko(n, null) else odpriZRacunalnika(n)
        }
    }

    private fun racunalnikZa(n: Nadaljuj.Vnos): LinkOdjemalec.Naprava? =
        link.naprave.firstOrNull { it.id == n.racunalnik }

    private fun zazeniProgram(n: Nadaljuj.Vnos) {
        val r = racunalnikZa(n) ?: run { niVec(n, racunalnikaNi = true); return }
        Toast.makeText(this, getString(R.string.os_nadaljuj_odpiram, n.ime), Toast.LENGTH_SHORT).show()
        val zaslon = r.zmoznosti.contains("desktop")
        link.ukaz(r.id, "apps.launch", org.json.JSONObject().put("app", n.program), 10_000,
            LinkOdjemalec.Odgovor { izid, _ ->
                if (isFinishing) return@Odgovor
                if (izid?.optBoolean("ok") != true) { niVec(n); return@Odgovor }
                // Zagon s pripete kartice (ali znova iz Nadaljuj) naj velja enako kot zagon s seznama
                // programov: program gre na vrh vrste Nadaljuj in ga od tam lahko zapres (✕).
                Nadaljuj.zapisi(this, n.copy(kdaj = System.currentTimeMillis()))
                if (!zaslon) zZapomnjenimFokusom { narisiNadaljuj() }
                if (zaslon) odpriVarno(Intent(this, ZaslonActivity::class.java)
                    .putExtra(DatotekeActivity.EXTRA_RACUNALNIK, r.id)
                    .putExtra(ZaslonActivity.EXTRA_ZASLON, "apps")
                    .putExtra(ZaslonActivity.EXTRA_PROGRAM, n.program)
                    .putExtra(ZaslonActivity.EXTRA_IGRA, n.igra), getString(R.string.os_zaslon))
            })
    }

    /** Datoteka z racunalnika: najprej si od njega izprosimo svezo sejo, sele nato odpremo. */
    private fun odpriZRacunalnika(n: Nadaljuj.Vnos) {
        val r = racunalnikZa(n) ?: run { niVec(n, racunalnikaNi = true); return }
        Toast.makeText(this, getString(R.string.os_nadaljuj_odpiram, n.ime), Toast.LENGTH_SHORT).show()
        link.ukaz(r.id, "files.list", org.json.JSONObject().put("folder", ""), 12_000,
            LinkOdjemalec.Odgovor { izid, _ ->
                if (isFinishing) return@Odgovor
                val streznik = izid?.optJSONObject("data")?.optJSONObject("server")
                if (izid?.optBoolean("ok") != true || streznik == null) { niVec(n); return@Odgovor }
                odpriDatoteko(n, DatotekeActivity.Streznik(
                    streznik.optString("base_url").trimEnd('/'),
                    streznik.optString("fp"), streznik.optString("token")))
            })
    }

    private fun odpriDatoteko(n: Nadaljuj.Vnos, s: DatotekeActivity.Streznik?) {
        val naslovDatoteke = if (s == null) n.id else s.url(n.id)
        val namera = when (n.vrsta) {
            Nadaljuj.SLIKA -> Intent(this, SlikaActivity::class.java)
                .putStringArrayListExtra("urli", arrayListOf(naslovDatoteke))
                .putStringArrayListExtra("imena", arrayListOf(n.ime))
                .putExtra("zacetek", 0)
            Nadaljuj.BESEDILO -> Intent(this, BesediloActivity::class.java)
                .putExtra("url", naslovDatoteke).putExtra("ime", n.ime)
            else -> Intent(this, PredvajalnikActivity::class.java)
                .putExtra("url", naslovDatoteke).putExtra("ime", n.ime)
                .putExtra("mime", n.mime).putExtra("zvok", n.vrsta == Nadaljuj.GLASBA)
        }
        namera.putExtra("lokalno", s == null)
        if (s != null) { val b = Bundle(); s.vBundle(b); namera.putExtras(b) }
        odpriVarno(namera, n.ime)
    }

    /** Prava ikona programa: shranjena ob zagonu, sicer tista s pripete kartice, sicer nic. */
    private fun ikonaPrograma(n: Nadaljuj.Vnos): android.graphics.drawable.Drawable? =
        Nadaljuj.ikona(this, n) ?: SafeerAppi.priljubljeni(this).firstOrNull {
            it.vir == AppVir.RACUNALNIK && it.cilj == n.program && it.racunalnik == n.racunalnik
        }?.let { SafeerAppi.ikona(this, it) }

    /**
     * Kar je bilo, ni vec dosegljivo: povejmo z oknom (obvestilo na dnu televizorja hitro spregledas)
     * in kartico ponudimo v odstranitev. Privzeto ostane - racunalnik je morda le ugasnjen.
     */
    private fun niVec(n: Nadaljuj.Vnos, racunalnikaNi: Boolean = false) {
        if (isFinishing) return
        // Racunalnika ni v Linku (ugasnjen, spi, Control ne tece): povejmo, kaj lahko uporabnik
        // naredi - "ni vec na voljo" bi zvenelo, kot da je program izginil.
        val sporocilo = if (racunalnikaNi) getString(R.string.os_nadaljuj_ni_racunalnika)
            else getString(R.string.os_nadaljuj_ni_vec, n.ime)
        val okno = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(n.ime)
            .setMessage(sporocilo)
            .setPositiveButton(getString(android.R.string.ok), null)
        if (OsPravila.ponudiOdstranitev(n.kljuc(), Nadaljuj.seznam(this).map { it.kljuc() })) {
            okno.setNegativeButton(getString(R.string.os_nadaljuj_odstrani)) { _, _ ->
                Nadaljuj.odstrani(this, n)
                zZapomnjenimFokusom { narisiNadaljuj() }
            }
        }
        Kontroler.pokazi(okno.show())
    }

    // ------------------------------------------------------------------ Naprave

    // ------------------------------------------------------------------ Spletne aplikacije
    //
    // Spletna stran, ki se obnasa kot aplikacija: svoja ikona in ime, zagon cez ves zaslon brez
    // vrstice z naslovom. Ideja Firefox OS/Capyloon, le da tu spletna aplikacija podeduje vso
    // zascito brskalnika (blokiranje oglasov in sledilcev, nevarne strani, Scit) in Safeer Link.

    /** Ob prvem zagonu Safeer OS prevzame spletne aplikacije, ki jih je uporabnik dodal v brskalniku. */
    private fun prevzemiSpletne() {
        Thread({
            try { SpletneAplikacije.prevzemiOdBrskalnika(this) { glavna.post { zZapomnjenimFokusom { narisiSpletne() } } } }
            catch (e: Throwable) { Log.w(TAG, "Prevzem spletnih aplikacij: ${e.message}") }
            glavna.post { zZapomnjenimFokusom { narisiSpletne() } }
        }, "safeer-os-prevzem").start()
    }

    /** [fokusUrl]: po premiku ali odstranitvi naj fokus ostane pri isti aplikaciji. */
    private fun narisiSpletne(fokusUrl: String = "") {
        vrstaSpletne.removeAllViews()
        var zeljeni: View? = null
        for (a in SpletneAplikacije.seznam(this)) {
            val v = LayoutInflater.from(this).inflate(R.layout.os_kartica_ikona, vrstaSpletne, false)
            v.findViewById<ImageView>(R.id.ikona).setImageDrawable(SpletneAplikacije.ikona(this, a))
            v.findViewById<TextView>(R.id.ime).text = a.ime.ifBlank { SpletneAplikacije.gostitelj(a.url) }
            v.onFocusChangeListener = fokus
            v.setOnClickListener { zazeniSpletno(a) }
            v.setOnLongClickListener { moznostiSpletne(a); true }
            v.tag = "splet:" + a.url
            vrstaSpletne.addView(v)
            if (a.url == fokusUrl) zeljeni = v
        }
        zeljeni?.let { it.post { it.requestFocus() } }
        val dodaj = LayoutInflater.from(this).inflate(R.layout.os_kartica_ikona, vrstaSpletne, false)
        dodaj.findViewById<ImageView>(R.id.ikona).setImageResource(R.drawable.os_ikona_splet)
        dodaj.findViewById<TextView>(R.id.ime).text = getString(R.string.os_spletne_dodaj)
        dodaj.onFocusChangeListener = fokus
        dodaj.setOnClickListener { dodajSpletno() }
        dodaj.tag = "splet:+"
        vrstaSpletne.addView(dodaj)
    }

    private fun zazeniSpletno(a: SpletneAplikacije.Aplikacija) {
        Nadaljuj.zapisi(this, Nadaljuj.Vnos(vrsta = Nadaljuj.SPLETNA,
            ime = a.ime.ifBlank { SpletneAplikacije.gostitelj(a.url) }, url = a.url))
        val namera = brskalnikNamera()
            .putExtra("spletna_aplikacija", a.url)
            .putExtra("aplikacija_ime", a.ime.ifBlank { SpletneAplikacije.gostitelj(a.url) })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        odpriVarno(namera, a.ime.ifBlank { SpletneAplikacije.gostitelj(a.url) })
    }

    /**
     * Odpiranje, ki ne utihne. Doslej smo napako pri startActivity tiho pozrli in uporabnik je
     * pritisnil OK, pa se ni zgodilo nic - brez pojasnila in brez poti naprej. Zdaj pove, kaj je
     * slo narobe, in ponudi ponovni poskus.
     */
    private fun odpriVarno(namera: Intent, ime: String, moznosti: android.os.Bundle? = null) {
        try {
            startActivity(namera, moznosti)
        } catch (e: Throwable) {
            Log.w(TAG, "Odpiranje ni uspelo ($ime): ${e.message}")
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
     * Dolg pritisk na spletno aplikacijo: uporabnik si vrsto uredi sam. Na daljincu ni vlecenja,
     * zato premikamo po enem mestu; ponudimo samo tisto, kar je na tem mestu res mogoce.
     */
    private fun moznostiSpletne(a: SpletneAplikacije.Aplikacija) {
        val seznam = SpletneAplikacije.seznam(this)
        val mesto = seznam.indexOfFirst { it.url == a.url }
        val dejanja = ArrayList<Pair<String, () -> Unit>>()
        if (mesto > 0) dejanja.add(getString(R.string.os_spletne_levo) to { premakniSpletno(a, -1) })
        if (mesto >= 0 && mesto < seznam.size - 1) dejanja.add(getString(R.string.os_spletne_desno) to { premakniSpletno(a, 1) })
        dejanja.add(getString(R.string.os_spletne_odstrani) to { odstraniSpletno(a) })
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(a.ime.ifBlank { SpletneAplikacije.gostitelj(a.url) })
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    private fun premakniSpletno(a: SpletneAplikacije.Aplikacija, zamik: Int) {
        if (!SpletneAplikacije.premakni(this, a.url, zamik)) return
        narisiSpletne(a.url)
        DomacaVrsta.osvezi(this)
    }

    private fun odstraniSpletno(a: SpletneAplikacije.Aplikacija) {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(a.ime.ifBlank { SpletneAplikacije.gostitelj(a.url) })
            .setMessage(getString(R.string.os_spletne_odstrani_vprasanje))
            .setPositiveButton(getString(R.string.os_spletne_odstrani)) { _, _ ->
                SpletneAplikacije.odstrani(this, a.url); narisiSpletne(); DomacaVrsta.osvezi(this)
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    /**
     * Dodajanje brez tipkanja na daljincu: ponudimo strani, ki jih ima uporabnik ze na domaci
     * strani brskalnika ("Moje strani"). Ime in ikono nato prinese manifest spletne aplikacije.
     */
    private fun dodajSpletno() {
        val ploscice = try { si.safeer.tv.HomeTilesStore.load(this) } catch (_: Throwable) { mutableListOf() }
        val proste = ploscice.filterNot { SpletneAplikacije.jeDodana(this, it.url) }
        // Prva izbira je vedno vnos naslova: uporabnik lahko doda katerokoli stran, ne le tistih,
        // ki jih ima ze na domaci strani brskalnika.
        val imena = (listOf(getString(R.string.os_spletne_vnesi)) +
            proste.map { it.title.ifBlank { SpletneAplikacije.gostitelj(it.url) } }).toTypedArray()
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_spletne_dodaj_naslov))
            .setItems(imena) { _, i ->
                if (i == 0) vnesiNaslovSpletne()
                else proste.getOrNull(i - 1)?.let { dodajSpletnoAplikacijo(it.url, it.title) }
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    /**
     * Vnos naslova z daljincem. Tipkanje na televizorju ni prijetno, zato naslov dopolnimo sami
     * (brez "https://" gre tudi) in ga zavrnemo, ce ni videti kot spletni naslov.
     */
    private fun vnesiNaslovSpletne() {
        val polje = android.widget.EditText(this).apply {
            hint = getString(R.string.os_spletne_vnesi_namig)
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(resources.getColor(R.color.os_besedilo, null))
            setHintTextColor(resources.getColor(R.color.os_umirjeno, null))
        }
        val okvir = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val r = (resources.displayMetrics.density * 24).toInt()
            setPadding(r, r / 2, r, 0)
            addView(polje)
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_spletne_vnesi))
            .setView(okvir)
            .setPositiveButton(getString(R.string.os_spletne_dodaj_gumb)) { _, _ ->
                val naslov = celoten(polje.text.toString())
                if (naslov == null) {
                    Toast.makeText(this, getString(R.string.os_spletne_neveljaven), Toast.LENGTH_LONG).show()
                } else if (SpletneAplikacije.jeDodana(this, naslov)) {
                    Toast.makeText(this, getString(R.string.os_spletne_ze_dodana), Toast.LENGTH_LONG).show()
                } else {
                    dodajSpletnoAplikacijo(naslov, SpletneAplikacije.gostitelj(naslov))
                }
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
        polje.requestFocus()
    }

    /** Naslov, kot ga je vnesel uporabnik, dopolnjen v celoten https naslov; null, ce to ni naslov. */
    private fun celoten(vnos: String): String? {
        val t = vnos.trim().replace(" ", "")
        if (t.isEmpty()) return null
        val z = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        return try {
            val u = java.net.URL(z)
            if (u.host.contains(".") && !u.host.startsWith(".") && !u.host.endsWith(".")) z else null
        } catch (_: Throwable) { null }
    }

    private fun dodajSpletnoAplikacijo(url: String, ime: String) {
        Toast.makeText(this, getString(R.string.os_spletne_dodajam), Toast.LENGTH_SHORT).show()
        SpletneAplikacije.dodaj(this, url, ime) {
            if (isFinishing) return@dodaj
            narisiSpletne()
            // Prvic ponudimo, da se spletne aplikacije pokazejo tudi na domacem zaslonu TV.
            DomacaVrsta.ponudiEnkrat(this)
        }
    }

    // ------------------------------------------------------------------ Aplikacije

    /**
     * Aplikacije televizorja: na domacem zaslonu samo priljubljene, v vrstnem redu uporabnika.
     * Prej so bile tu vse in vrsta se je lomila cez rob zaslona - med desetinami kartic ni bilo
     * mogoce nic najti. Zadnja kartica ("Ostalo") pelje na vse.
     *
     * [fokusPaket]: po premiku naj fokus ostane pri isti aplikaciji.
     */
    private fun narisiAplikacije(fokusPaket: String = "") {
        vrstaAplikacije.removeAllViews()
        val vsi = Aplikacije.seznam(this)
        Priljubljene.pocistiSamodejne(this)
        val izbrani = Priljubljene.seznam(this)
        val po = izbrani.mapNotNull { paket -> vsi.firstOrNull { it.paket == paket } }
        var zeljeni: View? = null
        val kartice = LinkedHashMap<String, View>()
        for (a in po) {
            val v = LayoutInflater.from(this).inflate(R.layout.os_kartica_ikona, vrstaAplikacije, false)
            v.findViewById<ImageView>(R.id.ikona).setImageDrawable(Aplikacije.ikona(this, a))
            v.findViewById<TextView>(R.id.ime).text = a.ime
            v.onFocusChangeListener = fokus
            v.setOnClickListener {
                zadnjaOznaka = "app:" + a.paket
                odpriVarno(Intent(a.namera).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), a.ime, izPloscice(v))
            }
            v.setOnLongClickListener {
                moznostiPriljubljene("app:" + a.paket, a.ime) { Priljubljene.odstrani(this, a.paket) }; true
            }
            v.tag = "app:" + a.paket
            kartice["app:" + a.paket] = v
            if (a.paket == fokusPaket) zeljeni = v
        }
        // Priljubljeni programi z racunalnika: v isti vrsti kot aplikacije televizorja - uporabnik
        // ne loci, kje kaj tece. Ikono imamo shranjeno, zato je kartica tu tudi brez racunalnika.
        val oddaljeni = SafeerAppi.priljubljeni(this).filter { it.vir == AppVir.RACUNALNIK }
        for (p in oddaljeni) {
            val v = LayoutInflater.from(this).inflate(R.layout.os_kartica_ikona, vrstaAplikacije, false)
            val ikona = v.findViewById<ImageView>(R.id.ikona)
            SafeerAppi.ikona(this, p)?.let { ikona.setImageDrawable(it) } ?: ikona.setImageResource(R.drawable.os_ikona_racunalnik)
            v.findViewById<TextView>(R.id.ime).text = p.ime
            v.onFocusChangeListener = fokus
            v.setOnClickListener {
                zadnjaOznaka = "app:" + p.kljuc
                zazeniProgram(Nadaljuj.Vnos(vrsta = Nadaljuj.PROGRAM, ime = p.ime, racunalnik = p.racunalnik,
                    program = p.cilj, igra = p.skupina == "igre"))
            }
            v.setOnLongClickListener {
                moznostiPriljubljene("app:" + p.kljuc, p.ime) { SafeerAppi.odstrani(this, p.kljuc) }; true
            }
            v.tag = "app:" + p.kljuc
            kartice["app:" + p.kljuc] = v
        }
        // En vrstni red za vse priljubljene (televizor in druge naprave skupaj), kot ga uredi uporabnik.
        for (o in Vrstni.red(this, KLJUC_PRILJUBLJENE, kartice.keys.toList())) kartice[o]?.let { vrstaAplikacije.addView(it) }
        // Vec, kot gre na zaslon: vrsta se drsi, mehak rob na desni pove, da je se kaj.
        (vrstaAplikacije.parent as? android.widget.HorizontalScrollView)?.let {
            it.isHorizontalFadingEdgeEnabled = true
            it.setFadingEdgeLength((48 * resources.displayMetrics.density).toInt())
        }
        // Vrsta so aplikacije, ki si jih je uporabnik izbral; vse ostale so pod Aplikacije.
        findViewById<TextView>(R.id.naslovAplikacije)?.setText(R.string.os_odsek_priljubljene)
        // Ploscica "Izberi aplikacije" samo, dokler je vrsta prazna: sicer bi bila druga pot do
        // istega zaslona kot Aplikacije v stranski vrstici.
        val ostalo = LayoutInflater.from(this).inflate(R.layout.os_kartica_ikona, vrstaAplikacije, false)
        ostalo.findViewById<ImageView>(R.id.ikona).setImageResource(R.drawable.os_ikona_mreza)
        // Dokler uporabnik ni izbral nobene, kartica pove, kaj naj naredi - prazna vrsta molci.
        ostalo.findViewById<TextView>(R.id.ime).text =
            getString(if (po.isEmpty() && oddaljeni.isEmpty()) R.string.os_aplikacije_izberi else R.string.os_vse_kartica)
        ostalo.onFocusChangeListener = fokus
        // Vse, kar lahko odpres: aplikacije televizorja, spletne in programi racunalnika na enem mestu.
        ostalo.setOnClickListener { zadnjaOznaka = "app:+"; odpriVarno(Intent(this, AplikacijeHostaActivity::class.java)
            .putExtra(AplikacijeHostaActivity.EXTRA_VIR, "vse"), getString(R.string.os_aplikacije_vse)) }
        ostalo.tag = "app:+"
        if (po.isEmpty() && oddaljeni.isEmpty()) vrstaAplikacije.addView(ostalo)
        uravnajVrsto(vrstaAplikacije, NAJMANJSA_APP_DP, NAJVECJA_APP_DP)
        zeljeni?.let { it.post { it.requestFocus() } }
        // Vrnitev iz aplikacije, odprte s te vrste: izbira je spet na njeni kartici.
        val zadnja = zadnjaOznaka
        if (zeljeni == null && zadnja != null && zadnja.startsWith("app:")) {
            zadnjaOznaka = null
            drsnik.postDelayed({
                val f = currentFocus
                if (!isFinishing && (f == null || !f.isAttachedToWindow)) najdiPoOznaki(zadnja)?.requestFocus()
            }, 80)
        }
    }

    /**
     * Dolg pritisk na priljubljeno: Premakni (puscici levo/desno jo neseta po vrsti, OK konca) ali
     * Odstrani. Prej je bil vsak korak svoje okno ("Premakni levo") - za peto mesto petkrat.
     */
    private fun moznostiPriljubljene(oznaka: String, ime: String, odstrani: () -> Unit) {
        val dejanja = ArrayList<Pair<String, () -> Unit>>()
        if (oznakePriljubljenih().size > 1) dejanja.add(getString(R.string.os_premakni) to { zacniPremik(oznaka) })
        dejanja.add(getString(R.string.os_aplikacije_odstrani) to {
            val mesto = oznakePriljubljenih().indexOf(oznaka)
            odstrani(); narisiAplikacije()
            vrstaAplikacije.post {
                vrstaAplikacije.getChildAt(mesto.coerceIn(0, vrstaAplikacije.childCount - 1))?.requestFocus()
            }
        })
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(ime)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    private fun oznakePriljubljenih(): List<String> =
        (0 until vrstaAplikacije.childCount).mapNotNull { vrstaAplikacije.getChildAt(it)?.tag as? String }
            .filter { it != "app:+" }

    /** Kartica, ki jo uporabnik ta trenutek premika po vrsti priljubljenih (null = ne premika). */
    private var premikam: String? = null
    /** Tipka, ki je premikanje koncala: njen dvig ne sme se odpreti aplikacije. */
    private var pogoltniGor = -1

    private fun zacniPremik(oznaka: String) {
        premikam = oznaka
        vrstaAplikacije.post { oznaciPremik() }
    }

    private fun oznaciPremik() {
        val o = premikam ?: return
        val v = najdiPoOznaki(o) ?: return
        v.requestFocus()
        v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start()
        v.findViewById<TextView>(R.id.ime)?.let { it.text = "◀  " + it.text + "  ▶" }
        opombaSpodaj.text = getString(R.string.os_premakni_namig)
        opombaSpodaj.visibility = View.VISIBLE
    }

    private fun koncajPremik() {
        val o = premikam ?: return
        premikam = null
        opombaSpodaj.visibility = View.GONE
        narisiAplikacije()
        vrstaAplikacije.post { najdiPoOznaki(o)?.requestFocus() }
    }

    private fun premakniPriljubljeno(zamik: Int) {
        val o = premikam ?: return
        if (!Vrstni.premakni(this, KLJUC_PRILJUBLJENE, oznakePriljubljenih(), o, zamik)) return
        narisiAplikacije()
        oznaciPremik()
    }

    /** Med premikanjem gredo tipke samo premikanju: levo/desno premakne, vse ostalo konca. */
    override fun dispatchKeyEvent(dogodek: KeyEvent): Boolean {
        if (premikam != null) {
            if (dogodek.action == KeyEvent.ACTION_DOWN) when (dogodek.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> premakniPriljubljeno(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> premakniPriljubljeno(1)
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { }
                else -> { pogoltniGor = dogodek.keyCode; koncajPremik() }
            }
            return true
        }
        if (dogodek.action == KeyEvent.ACTION_UP && dogodek.keyCode == pogoltniGor) { pogoltniGor = -1; return true }
        return super.dispatchKeyEvent(dogodek)
    }

    // ------------------------------------------------------------------ pomozno

    /**
     * Ko fokus pride v vrsto, jo pripeljemo na zaslon **celo**, skupaj z njenim naslovom. Brez tega
     * je zadnja vrsta prerezana na pol - kartice so odrezane po sredini in zaslon je videti
     * pokvarjen, ceprav se le drsi. Vrstic ne lomimo: ali je cela vidna ali pa je (mehko) ni.
     */
    /** Prva kartica pod velikimi karticami: odprt program, sicer prva spletna aplikacija. */
    private fun prvaSpodaj(): View? =
        (if (drsnikNadaljuj.visibility == View.VISIBLE) vrstaNadaljuj.getChildAt(0) else null)
            ?: vrstaSpletne.getChildAt(0)

    private fun pripeljiVrsto(v: View) {
        val vrsta = v.parent as? View ?: return              // vrsta kartic
        val drsnikVrste = vrsta.parent as? View ?: return    // vodoravni drsnik okoli nje
        val naslovVisina = (44 * resources.displayMetrics.density).toInt()   // naslov odseka nad vrsto
        val okvir = android.graphics.Rect(0, 0, drsnikVrste.width, drsnikVrste.height)
        (drsnik as? ViewGroup)?.offsetDescendantRectToMyCoords(drsnikVrste, okvir) ?: return
        val zgoraj = if (drsnikVrste === drsnikNadaljuj) 0 else (okvir.top - naslovVisina).coerceAtLeast(0)
        val spodaj = okvir.bottom + (16 * resources.displayMetrics.density).toInt()
        val kje = drsnik.scrollY
        val visina = drsnik.height
        if (visina <= 0) return
        val cilj = when {
            zgoraj < kje -> zgoraj
            spodaj > kje + visina -> spodaj - visina
            else -> return
        }
        (drsnik as? android.widget.ScrollView)?.smoothScrollTo(0, cilj.coerceAtLeast(0))
    }

    private val fokus = View.OnFocusChangeListener { v, ima ->
        v.animate().scaleX(if (ima) 1.04f else 1f).scaleY(if (ima) 1.04f else 1f).setDuration(120).start()
        if (ima) v.post { pripeljiVrsto(v) }
        if (ima) (v.parent as? ViewGroup)?.let { it.requestChildFocus(v, v) }
        if (ima) v.post { pokaziCeloPlosco(v) }
    }

    /**
     * Izbira v spodnji vrstici (naprave, hitri dostop, Scit): ScrollView pokaze samo izbrani gumb,
     * plosca okoli njega pa je ostala odrezana pod vrstico pomoci. Pokazemo celo plosco.
     */
    private fun pokaziCeloPlosco(v: View) {
        val vrsta = findViewById<View>(R.id.vrstaSpodnjePlosce) ?: return
        val dostop = findViewById<View>(R.id.ploscaDostop)
        var p: View? = v
        while (p != null && p.parent !== vrsta && p !== dostop) p = p.parent as? View
        if (p == null || !p.isAttachedToWindow) return
        val rob = (16 * resources.displayMetrics.density).toInt()
        p.requestRectangleOnScreen(android.graphics.Rect(0, 0, p.width, p.height + rob), false)
    }

    /**
     * Namera za splet: ce je Safeer Browser namescen kot svoja aplikacija, odpremo njega (en pogon,
     * ena zascita, en seznam zavihkov); sicer nasega vgrajenega, ki je v Safeer OS za ta primer.
     */
    private fun brskalnikNamera(): Intent {
        val paket = Sosed.brskalnik(this)
        val namera = if (paket != null)
            Intent().setComponent(android.content.ComponentName(paket, "si.safeer.tv.MainActivity"))
                // Brskalnik naj ve, od kod je prisel: ob izhodu se vrne v Safeer OS, ne na Android.
                .putExtra("iz_safeer_os", packageName)
        else Intent(this, si.safeer.tv.MainActivity::class.java)
        return namera.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Odpre splet (brskalnik sosede ali vgrajenega), po zelji z naslovom. */
    private fun odpriVBrskalniku(url: String?) {
        val namera = brskalnikNamera()
        if (url != null) { namera.action = Intent.ACTION_VIEW; namera.data = Uri.parse(url) }
        odpriVarno(namera, getString(R.string.os_splet))
    }

    /** Stran Safeer Link v brskalniku (seznanitev, naprave, daljinec); brskalnik pozna dodatek odpri_link. */
    private fun odpriLinkVBrskalniku() {
        // Link je del Safeer OS: stran naj ima ozadje sistema in se ob zaprtju vrne v Safeer OS.
        val namera = brskalnikNamera().putExtra("odpri_link", true)
            .putExtra("iz_safeer_os", packageName)
            .putExtra("os_ozadje", Ozadje.izbrana(this).oznaka)
            .putExtra("os_zatemnitev", Ozadje.zatemnitev(this))
        odpriVarno(namera, getString(R.string.os_link))
    }

    /**
     * Brskalnik po zaprtju strani Safeer Link odpre ta zaslon (singleTask pocisti vse nad njim);
     * dodatek pove, od kod je uporabnik prisel, da ga vrnemo tja in ne na zacetek.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        vrniSeKamorJeBil(intent)
    }

    private fun vrniSeKamorJeBil(namera: Intent?) {
        if (namera?.getStringExtra(EXTRA_VRNI) != VRNI_NAPRAVE) return
        namera.removeExtra(EXTRA_VRNI)
        try { startActivity(Intent(this, NapraveActivity::class.java)) } catch (_: Throwable) { }
    }

    private companion object {
        const val TAG = "SafeerOsDomov"
        /** Dodatek, ki ga brskalnik vrne ob zaprtju strani Safeer Link (glej NapraveActivity). */
        const val EXTRA_VRNI = "os_vrni"
        const val VRNI_NAPRAVE = "naprave"
        /** Najmanjsa sirina kartice, pri kateri je opis se berljiv (velike kartice v vrsti Zacni). */
        /** Majhna kartica Zacni: sest jih gre na zaslon sirine 960 dp. */
        /** Spletna aplikacija: stiri ploscice na zaslon. */
        const val NAJMANJSA_SPLET_DP = 116
        const val NAJVECJA_SPLET_DP = 140
        const val NAJMANJSA_ZACNI_DP = 128
        const val NAJVECJA_ZACNI_DP = 170
        /** Kje je shranjen vrstni red vrste Zacni. */
        const val KLJUC_ZACNI = "red_zacni"
        /** Vrstni red priljubljenih aplikacij na domacem zaslonu (televizor in druge naprave skupaj). */
        const val KLJUC_PRILJUBLJENE = "red_priljubljene"
        /** Kartica aplikacije: ikona in ime; pod to sirino ime ni vec berljivo. */
        const val NAJMANJSA_APP_DP = 116
        /** Nad to sirino kartica aplikacije ni vec videti kot ikona, ampak kot plakat. */
        const val NAJVECJA_APP_DP = 140
    }
}
