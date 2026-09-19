package si.safeer.tv.os

import si.safeer.tv.R

import android.app.Activity
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Zaslon racunalnika na televizorju. Safeer OS po Linku prosi racunalnik za sejo (`screen.start`),
 * ta odpre vrata s pripetim potrdilom in enkratnim zetonom, televizor pa se nanje poveze in sliko
 * dekodira strojno.
 *
 * Kar uporabnik vidi, je resnica: dokler slike ni, pise, kaj se dogaja, in ne kaze zamrznjenega
 * okvirja.
 *
 * Ta zaslon tudi **upravlja** racunalnik: tipke daljinca, tipkovnice in igralnega plosecka ter
 * premik miske gredo po isti povezavi nazaj. Zato Nazaj tukaj ni izhod, ampak tipka za racunalnik -
 * sejo konca **dolg pritisk** na Nazaj (`screen.stop`), da zajem ne tece naprej v prazno.
 */
class ZaslonActivity : Activity(), LinkOdjemalec.Poslusalec {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(si.safeer.tv.JezikVmesnika.vKontekstu(newBase))
    }

    private lateinit var pogled: SurfaceView
    private lateinit var sporocilo: TextView
    private lateinit var meritve: TextView
    private lateinit var namig: TextView
    private lateinit var tipkovnicaPogled: android.widget.LinearLayout
    private var tipkovnica: ZaslonTipkovnica? = null

    private val link by lazy { LinkUpravitelj.pridobi(this) }
    private var odjemalec: ZaslonOdjemalec? = null
    private var racunalnik: LinkOdjemalec.Naprava? = null
    private var povrsinaPripravljena = false
    private var seja: JSONObject? = null
    private var kakovost = "srednja"
    /** Kaj televizor gleda: `desktop` = namizje racunalnika, `apps` = locen zaslon s programi s televizorja. */
    private var cilj = "desktop"
    /** Racunalnik je potrdil, da ta seja kaze locen zaslon s programi (ne namizja). */
    private var naDrugem = false
    /** Racunalnik zna na locenem zaslonu skociti na naslednji gumb (krizec v programih). */
    private var fokusPodprt = false
    /** Program, zaradi katerega smo tu (za zapomnjeni nacin tipk), in ali je igra. */
    private var program = ""
    private var igra = false
    private var koncujem = false
    private var poskusov = 0
    private var prosim = false
    private var odklon: Pair<Float, Float>? = null
    private var palicaTece = false
    /**
     * Daljinec ima samo smerne tipke in OK. Namizje racunalnika pa je narejeno za misko, zato
     * privzeto smerne tipke premikajo **kazalec** (pospesujejo se, dokler tipko drzis), OK klikne,
     * dolg OK je desni klik. Kdor upravlja program, ki se ravna po tipkah (predvajalnik, meni),
     * preklopi na tipke z Meni/Info na daljincu ali Start na ploscku.
     */
    private var kazalec = true
    private var smer: Pair<Int, Int>? = null
    private var hitrost = ZaslonVnos.KAZALEC_ZACETNA
    private var smerTece = false
    private var smerOd = 0L
    private var okDrzan = false
    /** Tipke, ki jih uporabnik ta trenutek drzi; ob odhodu jih moramo spustiti. */
    private val drzane = HashSet<Int>()
    /**
     * Ali racunalnik zna narediti navidezni igralni plosek. Kadar zna, gumbi plosecka ne postanejo
     * tipke in miska, ampak gredo naravnost v racunalnik kot plosek - igra tam vidi pravi plosek.
     */
    private var plosekVRacunalnik = false
    /** Uporabnik je izbral, da gre plosek na racunalnik kot igralni plosek (sicer tipkovnica in miska). */
    private var plosekKotPlosek = false
    private fun plosekVIgri() = plosekVRacunalnik && plosekKotPlosek
    /** Gumbi plosecka, ki so ta trenutek pritisnjeni (ob odhodu jih spustimo). */
    private val plosekDrzani = HashSet<Int>()
    /** Nazadnje poslani odkloni palic in sprozilcev; posiljamo samo, kar se je res spremenilo. */
    private val plosekOdkloni = HashMap<String, Float>()
    private var namigPokazan = false
    private lateinit var okvir: View
    /** Velikost slike racunalnika (tocke drugega zaslona) in kje na televizorju je slika zdaj. */
    private var slikaW = 0
    private var slikaH = 0
    private var osnovaL = 0; private var osnovaT = 0; private var osnovaW = 0; private var osnovaH = 0
    private var trenL = 0; private var trenT = 0; private var trenW = 0; private var trenH = 0
    /** Vlecenje (meni seje): levi gumb je drzan, OK ga spusti. */
    private var vlecem = false
    /** Povecava okoli kazalca (meni seje), izrisana na televizorju. */
    private var povecava = false
    private var brezGumbovPovedano = false
    private var zadnjiFokusDaljinec = false
    private val glavna = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.os_activity_zaslon)
        pogled = findViewById(R.id.povrsina)
        sporocilo = findViewById(R.id.sporocilo)
        meritve = findViewById(R.id.meritve)
        namig = findViewById(R.id.namig)
        tipkovnicaPogled = findViewById(R.id.tipkovnica)
        // Okvir izbire: ko krizec ali daljinec skoci na gumb, ga televizor obrobi sam (ostro, ne
        // glede na kakovost slike). Takoj ko se kazalec premakne drugace, izgine.
        val gostota = resources.displayMetrics.density
        okvir = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10 * gostota
                setColor(android.graphics.Color.parseColor("#1A2DD4BF"))
                setStroke((3 * gostota).toInt(), android.graphics.Color.parseColor("#2DD4BF"))
            }
            visibility = View.GONE
        }
        findViewById<android.widget.FrameLayout>(R.id.koren).addView(okvir, 1,
            android.widget.FrameLayout.LayoutParams(0, 0))
        // Tablica: prst je miska (dotik klikne tam, kamor pokaze), meni seje pa je gumb v kotu,
        // ker tablica nima tipke Meni in ne dolgega Nazaj.
        if (naDotik()) {
            pogled.setOnTouchListener { _, e -> dotik(e) }
            val gumb = TextView(this).apply {
                text = "\u2630"
                textSize = 20f
                setTextColor(getColor(R.color.os_besedilo))
                setBackgroundResource(R.drawable.os_znacka)
                gravity = android.view.Gravity.CENTER
                alpha = 0.85f
                contentDescription = getString(R.string.os_zaslon)
                setOnClickListener { odpriMeni() }
            }
            val velikost = (48 * gostota).toInt()
            val lpGumb = android.widget.FrameLayout.LayoutParams(velikost, velikost)
            lpGumb.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            lpGumb.setMargins(0, 0, (14 * gostota).toInt(), (14 * gostota).toInt())
            findViewById<android.widget.FrameLayout>(R.id.koren).addView(gumb, lpGumb)
        }
        tipkovnica = ZaslonTipkovnica(this, tipkovnicaPogled,
            naBesedilo = { z -> poslji(JSONObject().put("vrsta", "besedilo").put("besedilo", z)) },
            naTipko = { t -> poslji(JSONObject().put("vrsta", "tipka").put("tipka", t)) })
        // Vedno najboljse, kar zmore racunalnik: uporabniku ni treba izbirati med kakovostmi,
        // ker za nizjo ni razloga - meritve kazejo, da ostrejsa slika skoraj nic ne stane.
        kakovost = intent.getStringExtra(EXTRA_KAKOVOST) ?: "najvisja"
        cilj = intent.getStringExtra(EXTRA_ZASLON) ?: "desktop"
        program = intent.getStringExtra(EXTRA_PROGRAM).orEmpty()
        igra = intent.getBooleanExtra(EXTRA_IGRA, false)
        pokazi(getString(R.string.os_zaslon_povezujem))
        pogled.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                povrsinaPripravljena = true
                seja?.let { zacniPretok(it) }
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, v: Int) { }
            override fun surfaceDestroyed(h: SurfaceHolder) {
                povrsinaPripravljena = false
                odjemalec?.ustavi()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        link.dodaj(this)
        if (seja == null) zahtevajSejo()
    }

    /** Koda za novo napravo (tablica, telefon) se pokaze tudi cez sliko racunalnika. */
    private val koda by lazy { KodaNaZaslonu(this) }

    override fun onResume() {
        super.onResume()
        koda.zacni()
    }

    override fun onPause() {
        koda.ustavi()
        super.onPause()
    }

    override fun onStop() {
        // Uporabnik je odsel (Domov, klic, ugasnjen zaslon): kar je drzal, mora gor - sicer bi
        // tipka na racunalniku ostala pritisnjena.
        sprostiDrzane()
        // Program s televizorja: kdor sejo zapusti (tudi s tipko Domov ali ko ugasne televizor),
        // ga ne zeli pustiti teci na nevidnem zaslonu racunalnika.
        if (naDrugem && !isChangingConfigurations && !isFinishing) {
            koncaj(zapriPrograme = true)
            finish()
        }
        link.odstrani(this)
        super.onStop()
    }

    override fun onDestroy() {
        // Uporabnik je sejo zapustil (tudi z Y ali domacim zaslonom Safeer OS): program zapremo.
        koncaj(zapriPrograme = isFinishing)
        super.onDestroy()
    }

    /** Racunalnik, ki deli zaslon (zmoznost `desktop`). */
    private fun racunalnikZZaslonom(): LinkOdjemalec.Naprava? =
        link.naprave.firstOrNull { it.zmoznosti.contains("desktop") && it.id != Identiteta.id(this) }

    /**
     * Ena sama zahteva naenkrat. Brez tega zaslon ob vstopu prosi dvakrat (onStart in takoj za njim
     * naNaprave), druga seja pa zapre vrata prve - televizor se je povezoval na vrata, ki jih je
     * nas lastni drugi klic ravnokar zaprl ("Connection refused").
     */
    private fun zahtevajSejo() {
        if (prosim) return
        val r = racunalnikZZaslonom()
        if (r == null) {
            pokazi(getString(
                if (!link.povezan) R.string.os_zaslon_ni_povezave else R.string.os_zaslon_ni_racunalnika))
            return
        }
        racunalnik = r
        prosim = true
        pokazi(getString(R.string.os_zaslon_prosim, r.ime.ifBlank { r.id }))
        link.ukaz(r.id, "screen.start", JSONObject().put("quality", kakovost).put("screen", cilj), 15_000,
            LinkOdjemalec.Odgovor { izid, napaka ->
                prosim = false
                if (isFinishing) return@Odgovor
                if (izid == null) { pokazi(getString(R.string.os_zaslon_napaka, napaka)); return@Odgovor }
                if (!izid.optBoolean("ok")) {
                    val koda = izid.optString("code")
                    if (koda == "ni_programa") {
                        // Program na racunalniku ni vec odprt: namesto napake pospravimo in gremo domov.
                        Toast.makeText(this, getString(R.string.os_zaslon_program_zaprt), Toast.LENGTH_LONG).show()
                        koncaj(); finish(); return@Odgovor
                    }
                    pokazi(if (koda == "ni_dovoljeno") getString(R.string.os_zaslon_ni_dovoljeno,
                        r.ime.ifBlank { r.id }) else getString(R.string.os_zaslon_napaka, izid.optString("message")))
                    return@Odgovor
                }
                val podatki = izid.optJSONObject("data") ?: return@Odgovor
                seja = podatki
                plosekVRacunalnik = podatki.optBoolean("gamepad", false)
                naDrugem = podatki.optString("screen") == "apps"
                fokusPodprt = podatki.optBoolean("focus", false)
                // V igri so puscice puscice: igra, v kateri daljinec premika misko, se ne da igrati.
                // Uporabnikova izbira za ta program ima prednost; sicer igra (s seznama ali od racunalnika).
                val nastavitve = getSharedPreferences("safeer_os", MODE_PRIVATE)
                val tipke = if (program.isNotEmpty() && nastavitve.contains("tipke:$program"))
                    nastavitve.getBoolean("tipke:$program", false)
                else igra || podatki.optBoolean("game", false)
                if (tipke) { ustaviSmer(); kazalec = false }
                // Plosek: privzeto tipkovnica in miska; igralni plosek, ce si ga tu tako pustil.
                plosekKotPlosek = program.isNotEmpty() && nastavitve.getBoolean("plosek:$program", false)
                android.util.Log.i("SafeerZaslon", "seja: navidezni plosek na racunalniku = $plosekVRacunalnik")
                // Zaslon racunalnika je ena najpogostejsih poti; naj bo na domacem zaslonu takoj pri roki.
                // Ime kartice je 'Zaslon racunalnika', ne dolgo ime naprave: na kartici se je
                // lomilo sredi besede in uporabniku ni povedalo nic vec.
                // Samo za pravo namizje: program s televizorja (locen zaslon) ima v Nadaljuj svojo
                // kartico. Prej je vsak zagnan program na vrh potisnil se "Zaslon racunalnika", ki pa
                // odpre namizje - ne programa, ki ga je uporabnik imel odprtega.
                if (OsPravila.zapisiZaslon(naDrugem)) Nadaljuj.zapisi(this, Nadaljuj.Vnos(vrsta = Nadaljuj.ZASLON,
                    ime = getString(R.string.os_zaslon), racunalnik = r.id))
                if (povrsinaPripravljena) zacniPretok(podatki)
            })
    }

    /**
     * Slika mora ohraniti razmerje racunalniskega zaslona: raztegnjeno namizje je takoj videti
     * napacno. Povrsino zato pomanjsamo na najvecji pravokotnik pravega razmerja, ki gre v zaslon.
     */
    private fun uravnajRazmerje(sirinaSlike: Int, visinaSlike: Int) {
        if (sirinaSlike <= 0 || visinaSlike <= 0) return
        val koren = findViewById<View>(R.id.koren)
        koren.post {
            val sirina = koren.width
            val visina = koren.height
            if (sirina <= 0 || visina <= 0) return@post
            val merilo = minOf(sirina.toFloat() / sirinaSlike, visina.toFloat() / visinaSlike)
            val lp = pogled.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.width = (sirinaSlike * merilo).toInt()
            lp.height = (visinaSlike * merilo).toInt()
            lp.gravity = android.view.Gravity.CENTER
            pogled.layoutParams = lp
            osnovaW = lp.width; osnovaH = lp.height
            osnovaL = (sirina - lp.width) / 2; osnovaT = (visina - lp.height) / 2
            trenL = osnovaL; trenT = osnovaT; trenW = osnovaW; trenH = osnovaH
        }
    }

    private fun zacniPretok(podatki: JSONObject) {
        val r = racunalnik ?: return
        slikaW = podatki.optInt("width"); slikaH = podatki.optInt("height")
        // Nova ali obnovljena seja: nic od prejsnje (okvir, povecava, vlecenje) ne sme ostati.
        okvir.visibility = View.GONE
        povecava = false
        vlecem = false
        uravnajRazmerje(slikaW, slikaH)
        val naslov = r.naslov.ifBlank { "" }
        if (naslov.isBlank()) { pokazi(getString(R.string.os_zaslon_napaka, "ni naslova")); return }
        odjemalec?.ustavi()
        val o = ZaslonOdjemalec(
            naslov, podatki.optInt("port"), podatki.optString("fp"), podatki.optString("token"),
            naStanje = { stanje, besedilo ->
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    when (stanje) {
                        ZaslonOdjemalec.Stanje.POVEZUJEM -> pokazi(getString(R.string.os_zaslon_povezujem))
                        ZaslonOdjemalec.Stanje.TECE -> {
                            poskusov = 0; skrij()
                            // Napisa (kako se konca, kaj delajo tipke) sta za zacetnika. Kdor ju je videl ze
                            // enkrat, ga ob vsakem programu samo motita - takrat ju ne kazemo vec.
                            val nast = getSharedPreferences("safeer_os", MODE_PRIVATE)
                            val videno = nast.getInt("zaslon_namigov", 0)
                            if (!namigPokazan && videno < 1) {
                                nast.edit().putInt("zaslon_namigov", videno + 1).apply()
                                Toast.makeText(this, getString(R.string.os_zaslon_namig), Toast.LENGTH_LONG).show()
                                pokaziNamig()          // prvic polnih 6 s
                                namigPokazan = true
                            }
                        }
                        // Prekinjena povezava ni konec seje: enkrat poskusimo znova, sele nato
                        // uporabnika vrnemo nazaj - zamrznjena slika je najslabsi mozni izid.
                        ZaslonOdjemalec.Stanje.KONCANO -> if (!koncujem) ponoviAliKoncaj(getString(R.string.os_zaslon_koncano))
                        ZaslonOdjemalec.Stanje.NAPAKA -> ponoviAliKoncaj(getString(R.string.os_zaslon_napaka, besedilo))
                        // Na locenem zaslonu ni vec programa (igra se je zaprla ob Esc ...): temen
                        // prazen zaslon je slepa ulica, zato gremo takoj nazaj v Safeer OS.
                        ZaslonOdjemalec.Stanje.PRAZNO -> {
                            Toast.makeText(this, getString(if (besedilo == "ni_okna") R.string.os_zaslon_ni_okna
                                else R.string.os_zaslon_program_zaprt), Toast.LENGTH_LONG).show()
                            koncaj(); finish()
                        }
                    }
                }
            },
            naStatistiko = { s ->
                // Gledalcu stevilke o hitrosti prenosa nic ne povedo in mu le stojijo cez sliko;
                // ostanejo v dnevniku, kjer jih potrebujemo, kadar iscemo vzrok tezave.
                android.util.Log.d("SafeerZaslon",
                    "${s.sirina}x${s.visina} ${s.naSekundo} sl/s ${s.megabitov} Mb/s " +
                    "dekoder ${s.dekoderMs} ms zvok=${s.zvok}")
            },
            naObvestilo = { ob -> runOnUiThread { obvestilo(ob) } })
        odjemalec = o
        o.zacni(pogled.holder.surface)
    }

    /** Po prekinitvi enkrat sam od sebe poskusimo znova; sele ce tudi to ne gre, koncamo. */
    private fun ponoviAliKoncaj(razlog: String) {
        if (koncujem) return
        if (poskusov >= 1) { pokazi(razlog); glavna.postDelayed({ if (!isFinishing) { koncaj(); finish() } }, 2500); return }
        poskusov++
        pokazi(getString(R.string.os_zaslon_ponovno))
        seja = null
        odjemalec?.ustavi()
        odjemalec = null
        glavna.postDelayed({ if (!isFinishing && !koncujem) zahtevajSejo() }, 1200)
    }

    private fun pokazi(besedilo: String) {
        sporocilo.text = besedilo
        sporocilo.visibility = View.VISIBLE
        meritve.visibility = View.GONE
    }

    private fun skrij() {
        sporocilo.visibility = View.GONE
        // Meritve ostanejo skrite: med gledanjem racunalnika na zaslonu ni stevilk.
        meritve.visibility = View.GONE
    }

    /** Konec seje: ustavimo tudi zajem na racunalniku, da ne tece v prazno. */
    private fun koncaj(zapriPrograme: Boolean = false) {
        if (koncujem) return
        koncujem = true
        sprostiDrzane()
        odjemalec?.ustavi()
        odjemalec = null
        val r = racunalnik ?: return
        // Konec seje programa (drzi Nazaj ali "Koncaj") zapre program tudi na racunalniku - prej
        // je tekel naprej na nevidnem zaslonu. Kdor ga hoce pustiti odprtega, pritisne Domov.
        val zahteva = JSONObject()
        if (zapriPrograme && naDrugem) zahteva.put("close_apps", true)
        link.ukaz(r.id, "screen.stop", zahteva, 5_000, LinkOdjemalec.Odgovor { _, _ -> })
    }

    // ------------------------------------------------------------------ upravljanje racunalnika

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Odprta tipkovnica se zapre; sicer je kratek Nazaj tipka za racunalnik,
            // dolg pritisk pa konca sejo.
            if (tipkovnica?.jeOdprta == true) { tipkovnica?.zapri(); return true }
            event?.startTracking()
            return true
        }
        // Glasnost pusti televizorju: uporabnik jo pricakuje tam, kjer je zvok.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) return super.onKeyDown(keyCode, event)
        // Barvne tipke daljinca: v brskalniku nazaj, naprej, osvezi, nov zavihek (racunalnik ve,
        // kateri program je v ospredju; drugod tipka ne naredi nicesar).
        barva(keyCode)?.let { b ->
            if (naDrugem && fokusPodprt) {
                if ((event?.repeatCount ?: 0) == 0) poslji(JSONObject().put("vrsta", "barva").put("barva", b))
                return true
            }
        }
        // Plosek v igro: Start in Select takrat pripadata igri, ne meniju seje.
        if (plosekVIgro(keyCode, event, true)) return true
        if (ZaslonVnos.jePreklop(keyCode)) { odpriMeni(); return true }
        // Krizec na plosecku so puscice na tipkovnici (drzane), tudi ko daljinec vodi kazalec.
        if (tipkovnica?.jeOdprta != true && ZaslonVnos.jeIzPlosecka(event) && ZaslonVnos.smer(keyCode) != null) {
            val ponovitev = event?.repeatCount ?: 0
            // V programu (nacin kazalca na locenem zaslonu) krizec skoci na naslednji gumb ali polje.
            if (naDrugem && kazalec && fokusPodprt) {
                if (ponovitev == 0 || ponovitev % 4 == 0) posljiFokus(keyCode)
                return true
            }
            if (ponovitev == 0 || ponovitev % PONOVI_DRZANJE == 0) {
                ZaslonVnos.drzanje(keyCode, true)?.let { poslji(it) }
            }
            drzane.add(keyCode)
            return true
        }
        if (tipkovnica?.jeOdprta == true) {
            // Igralni plosek pise skupaj s tipkovnico: A vtipka, B brise, X presledek, Y velike.
            if (tipkovnica?.plosek(keyCode) == true) return true
            return super.onKeyDown(keyCode, event)
        }
        if (kazalec) {
            // V nacinu kazalca smerne tipke vodijo misko, OK pa klika (dolg OK desni klik).
            val s = ZaslonVnos.smer(keyCode)
            if (s != null) { zacniSmer(s, keyCode); return true }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                if (event?.repeatCount == 0) { okDrzan = true; event.startTracking() }
                return true
            }
        }
        // Kar je smiselno drzati (smerne tipke, OK, presledek), posljemo kot pritisk in spust:
        // igra pospesuje, dokler drzis, in seznam se pomika, dokler drzis. Prej je vsako drzanje
        // razpadlo na vrsto kratkih pritiskov in igra je sunkovito poskakovala.
        if (ZaslonVnos.jeDrzljiva(keyCode)) {
            val ponovitev = event?.repeatCount ?: 0
            // Prvi pritisk drzimo; med drzanjem vsako sekundo javimo, da je se zivo (racunalnik
            // pozabljene tipke sam spusti, ce televizor utihne).
            if (ponovitev == 0 || ponovitev % PONOVI_DRZANJE == 0) {
                ZaslonVnos.drzanje(keyCode, true)?.let { poslji(it) }
            }
            drzane.add(keyCode)
            return true
        }
        val dogodek = ZaslonVnos.izTipke(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        poslji(dogodek)
        return true
    }

    /** Vse drzane tipke in gumbe spustimo (odhod z zaslona, konec seje). */
    private fun sprostiDrzane() {
        if (vlecem) { vlecem = false; poslji(JSONObject().put("vrsta", "gumb").put("gumb", "levi").put("dol", false)) }
        for (koda in drzane.toList()) ZaslonVnos.drzanje(koda, false)?.let { poslji(it) }
        drzane.clear()
        spustiKrizec()
        for (koda in plosekDrzani.toList()) ZaslonVnos.plosekTipka(koda, false)?.let { poslji(it) }
        plosekDrzani.clear()
        for (os in plosekOdkloni.keys.toList()) {
            if (plosekOdkloni[os] != 0f) poslji(ZaslonVnos.plosekOs(os, 0f))
        }
        plosekOdkloni.clear()
    }

    /**
     * Gumb plosecka naravnost v racunalnik. Vrne true, kadar smo ga porabili.
     *
     * Nazaj pustimo televizorju: uporabnik mora imeti pot ven iz seje, tudi ko ima v rokah samo
     * plosek. Vse drugo (vkljucno s Start in Select) gre v igro.
     */
    private fun plosekVIgro(koda: Int, dogodek: KeyEvent?, dol: Boolean): Boolean {
        if (!plosekVIgri() || !ZaslonVnos.jeIzPlosecka(dogodek)) return false
        if (tipkovnica?.jeOdprta == true) return false        // krizec takrat premika po tipkovnici
        if (!ZaslonVnos.jePlosekTipka(koda)) return false
        if (dol && (dogodek?.repeatCount ?: 0) > 0) return true      // drzanje javi Android, plosek ga ze drzi
        // Kadar plosek krizec posilja kot os, tipke krizca ne posiljamo (sla bi dvojno) in jih
        // tudi ne pozremo - naj jih dobi, kdor jih zna uporabiti.
        val ukaz = ZaslonVnos.plosekTipka(koda, dol, dogodek) ?: return false
        poslji(ukaz)
        if (dol) plosekDrzani.add(koda) else plosekDrzani.remove(koda)
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { koncaj(zapriPrograme = true); finish(); return true }
        if (kazalec && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A)) {
            okDrzan = false
            poslji(ZaslonVnos.klik("desni"))
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event != null && !event.isCanceled && !event.isLongPress) {
                poslji(org.json.JSONObject().put("vrsta", "tipka").put("tipka", "nazaj"))
            }
            return true
        }
        if (barva(keyCode) != null && naDrugem && fokusPodprt) return true
        if (tipkovnica?.jeOdprta == true) return super.onKeyUp(keyCode, event)
        if (plosekVIgro(keyCode, event, false)) return true
        if (ZaslonVnos.jeIzPlosecka(event) && ZaslonVnos.smer(keyCode) != null && drzane.remove(keyCode)) {
            ZaslonVnos.drzanje(keyCode, false)?.let { poslji(it) }
            return true
        }
        if (kazalec) {
            if (ZaslonVnos.smer(keyCode) != null) { ustaviSmer(); return true }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                if (okDrzan) { okDrzan = false; if (vlecem) preklopiVlecenje() else poslji(ZaslonVnos.klik("levi")) }
                return true
            }
        }
        if (drzane.remove(keyCode)) {
            ZaslonVnos.drzanje(keyCode, false)?.let { poslji(it) }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Meni seje na tipki Meni (na ploscku Start): vse, kar med gledanjem racunalnika potrebujes,
     * na enem mestu - tipkovnica, nacin tipk, shranjevanje in konec seje. Brez njega bi morali
     * vsako stvar obesiti na svojo tipko, ki je daljinec nima.
     */
    private fun odpriMeni() {
        val dejanja = ArrayList<Pair<String, () -> Unit>>()
        // Meni Start: tipka Super na racunalniku. Daljinec je nima, do spodnjega roba zaslona pa je
        // s kazalcem dolga pot - zato je prvi v meniju.
        // Na locenem zaslonu menija Start ni; tam je prvi preklop med odprtimi programi.
        if (naDrugem) dejanja.add(getString(R.string.os_zaslon_naslednji) to { posljiTipko("preklopi_okno") })
        else dejanja.add(getString(R.string.os_zaslon_meni_start) to { posljiTipko("domov") })
        dejanja.add(getString(R.string.os_zaslon_meni_tipkovnica) to { odpriTipkovnico() })
        if (naDrugem && fokusPodprt) {
            dejanja.add(getString(if (vlecem) R.string.os_zaslon_meni_spusti else R.string.os_zaslon_meni_vleci) to
                { preklopiVlecenje() })
            dejanja.add(getString(if (povecava) R.string.os_zaslon_meni_povecava_izklopi
                else R.string.os_zaslon_meni_povecava) to { preklopiPovecavo() })
        }
        dejanja.add(getString(
            if (kazalec) R.string.os_zaslon_meni_tipke else R.string.os_zaslon_meni_kazalec) to { preklopiNacin() })
        if (plosekVRacunalnik) dejanja.add(getString(
            if (plosekKotPlosek) R.string.os_zaslon_meni_plosek_namizje else R.string.os_zaslon_meni_plosek_igra) to {
            sprostiDrzane()
            plosekKotPlosek = !plosekKotPlosek
            if (program.isNotEmpty()) getSharedPreferences("safeer_os", MODE_PRIVATE).edit()
                .putBoolean("plosek:$program", plosekKotPlosek).apply()
        })
        dejanja.add(getString(R.string.os_zaslon_meni_shrani) to { posljiTipko("shrani") })
        dejanja.add(getString(R.string.os_zaslon_meni_bliznjice) to { odpriBliznjice() })
        dejanja.add(getString(R.string.os_zaslon_meni_koncaj) to { koncaj(zapriPrograme = true); finish() })
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_zaslon))
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    /** Bliznjice, ki jih racunalnik pozna; imena posljemo, prevede jih on. */
    private fun odpriBliznjice() {
        val bliznjice = listOf(
            getString(R.string.os_bliznjica_shrani) to "shrani",
            getString(R.string.os_bliznjica_shrani_kot) to "shrani_kot",
            getString(R.string.os_bliznjica_izberi_vse) to "izberi_vse",
            getString(R.string.os_bliznjica_kopiraj) to "kopiraj",
            getString(R.string.os_bliznjica_prilepi) to "prilepi",
            getString(R.string.os_bliznjica_izrezi) to "izrezi",
            getString(R.string.os_bliznjica_razveljavi) to "razveljavi",
            getString(R.string.os_bliznjica_ponovi) to "ponovi",
            getString(R.string.os_bliznjica_krepko) to "krepko",
            getString(R.string.os_bliznjica_lezece) to "lezece",
            getString(R.string.os_bliznjica_podcrtano) to "podcrtano",
            getString(R.string.os_bliznjica_isci) to "isci",
        )
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_zaslon_meni_bliznjice))
            .setItems(bliznjice.map { it.first }.toTypedArray()) { _, i -> posljiTipko(bliznjice[i].second) }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    private fun posljiTipko(ime: String) {
        poslji(JSONObject().put("vrsta", "tipka").put("tipka", ime))
    }

    /** Preklop med kazalcem in tipkami; uporabnik takoj vidi, kaj zdaj delajo tipke. */
    private fun preklopiNacin() {
        namigPokazan = true
        ustaviSmer()
        kazalec = !kazalec
        // Izbiro si zapomnimo za ta program: naslednjic se odpre tako, kot si ga pustil.
        if (program.isNotEmpty()) getSharedPreferences("safeer_os", MODE_PRIVATE).edit()
            .putBoolean("tipke:$program", !kazalec).apply()
        pokaziNamig()
    }

    /** Namig o upravljanju: pokaze se ob zacetku seje in ob preklopu, nato sam izgine. */
    private fun pokaziNamig() {
        namig.text = getString(
            if (kazalec) R.string.os_zaslon_nacin_kazalec else R.string.os_zaslon_nacin_tipke)
        namig.visibility = View.VISIBLE
        glavna.removeCallbacks(skrijNamig)
        // Kratko: uporabnik je nacin pravkar preklopil sam in hoce le potrditev.
        glavna.postDelayed(skrijNamig, if (namigPokazan) 2_500L else 6_000L)
    }

    private val skrijNamig = Runnable { namig.visibility = View.GONE }

    /**
     * Drzanje smerne tipke premika kazalec: zacne pocasi (da zadenes gumb) in se pospesuje, dokler
     * tipko drzis. Brez pospeska je pot cez zaslon predolga, s samo hitrim premikom pa se ne da
     * natancno zadeti.
     */
    private fun zacniSmer(nova: Pair<Int, Int>, koda: Int) {
        if (smer == nova) return
        smer = nova
        hitrost = ZaslonVnos.KAZALEC_ZACETNA
        // Kratek pritisk je en natancen korak. Prej je kazalec ze ob kratkem pritisku zdrsel
        // mimo gumba in do zelenega mesta ni bilo mogoce priti. V programu na locenem zaslonu
        // kratek pritisk skoci na naslednji gumb, povezavo ali polje - kot na Androidu; kjer jih
        // program ne pozna, racunalnik kazalec le rahlo premakne. Drzanje kazalec vodi prosto.
        if (naDrugem && fokusPodprt) posljiFokus(koda, true)
        else ZaslonVnos.premik(nova.first * ZaslonVnos.KAZALEC_KORAK, nova.second * ZaslonVnos.KAZALEC_KORAK)
            ?.let { poslji(it) }
        smerOd = android.os.SystemClock.uptimeMillis()
        if (smerTece) return
        smerTece = true
        glavna.post(object : Runnable {
            override fun run() {
                val s = smer
                if (s == null || isFinishing || koncujem) { smerTece = false; return }
                if (android.os.SystemClock.uptimeMillis() - smerOd < ZaslonVnos.KAZALEC_ZAMIK) {
                    glavna.postDelayed(this, 16); return
                }
                ZaslonVnos.premik((s.first * hitrost).toInt(), (s.second * hitrost).toInt())
                    ?.let { poslji(it) }
                hitrost = (hitrost * ZaslonVnos.KAZALEC_POSPESEK).coerceAtMost(ZaslonVnos.KAZALEC_NAJVECJA)
                glavna.postDelayed(this, 16)
            }
        })
    }

    private fun ustaviSmer() {
        smer = null
        hitrost = ZaslonVnos.KAZALEC_ZACETNA
    }

    /** Miska, prikljucena na televizor, in leva palica igralnega plosecka. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        ZaslonVnos.izMiske(event)?.let { poslji(it); return true }
        ZaslonVnos.izMiskinihGumbov(event)?.let { poslji(it); return true }
        // Odprta zaslonska tipkovnica: plosek je daljinec - krizec in palica premikata po tipkah.
        // Dogodka ne porabimo, zato ga Android sam spremeni v smerne tipke za fokus.
        if (tipkovnica?.jeOdprta == true &&
            event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) {
            spustiKrizec()
            odklon = null
            return super.onGenericMotionEvent(event)
        }
        if (plosekVIgri()) {
            val odkloni = ZaslonVnos.plosekOdkloni(event)
            if (odkloni.isNotEmpty()) {
                // Posljemo samo os, ki se je res premaknila: palica poslje dogodek ob vsakem
                // drgetu, omrezje pa ni tu zato, da nosi enake stevilke.
                for ((os, vrednost) in odkloni) {
                    if (plosekOdkloni[os] == vrednost) continue
                    plosekOdkloni[os] = vrednost
                    poslji(ZaslonVnos.plosekOs(os, vrednost))
                }
                return true
            }
        }
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) {
            // Namizni nacin (racunalnik plosecka ne ponuja): vsak del plosecka nekaj naredi.
            // Desna palica zdaj premika misko (izPalice); po strani drsita L1 in R1.
            namizniKrizec(event)
            namizniSprozilci(event)
        }
        val palica = ZaslonVnos.izPalice(event)
        if (palica != null) { odklon = palica; zazeniPalico(); return true }
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) {
            odklon = null
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // ------------------------------------------------------------------ plosek kot miska in tipke
    //
    // Nekateri ploscki (tudi Redragon v nacinu Android) smerni krizec posljejo SAMO kot os HAT,
    // sprozilca kot os (GAS/BRAKE) in desno palico kot Z/RZ. Brez navideznega plosecka na
    // racunalniku so ti deli doslej tiho izginili. Zdaj: krizec so smerne tipke (drzane, kot na
    // tipkovnici), desna palica pomika vsebino, R2 je levi in L2 desni klik.

    private var krizecX = 0
    private var krizecY = 0
    private var pomikTece = false
    private var pomik = 0f
    private var r2Pritisnjen = false
    private var l2Pritisnjen = false

    private fun namizniKrizec(e: MotionEvent) {
        val x = Math.round(e.getAxisValue(MotionEvent.AXIS_HAT_X))
        val y = Math.round(e.getAxisValue(MotionEvent.AXIS_HAT_Y))
        if (naDrugem && kazalec && fokusPodprt) {
            // Program: skok po gumbih, dokler drzis pa se ponavlja.
            val nova = when {
                x < 0 -> KeyEvent.KEYCODE_DPAD_LEFT; x > 0 -> KeyEvent.KEYCODE_DPAD_RIGHT
                y < 0 -> KeyEvent.KEYCODE_DPAD_UP; y > 0 -> KeyEvent.KEYCODE_DPAD_DOWN
                else -> 0
            }
            if (nova != fokusSmer) {
                fokusSmer = nova
                glavna.removeCallbacks(ponoviFokus)
                if (nova != 0) { posljiFokus(nova); glavna.postDelayed(ponoviFokus, 450) }
            }
            return
        }
        if (x != krizecX) {
            smernaTipka(if (krizecX < 0) "levo" else "desno", false, krizecX != 0)
            smernaTipka(if (x < 0) "levo" else "desno", true, x != 0)
            krizecX = x
        }
        if (y != krizecY) {
            smernaTipka(if (krizecY < 0) "gor" else "dol", false, krizecY != 0)
            smernaTipka(if (y < 0) "gor" else "dol", true, y != 0)
            krizecY = y
        }
    }

    /** Skok na naslednji gumb/polje v smeri (racunalnik ga najde; v igri gre kot smerna tipka). */
    private fun posljiFokus(koda: Int, daljinec: Boolean = false) {
        val smer = when (koda) {
            KeyEvent.KEYCODE_DPAD_UP -> "gor"; KeyEvent.KEYCODE_DPAD_DOWN -> "dol"
            KeyEvent.KEYCODE_DPAD_LEFT -> "levo"; KeyEvent.KEYCODE_DPAD_RIGHT -> "desno"
            else -> return
        }
        val d = org.json.JSONObject().put("vrsta", "fokus").put("smer", smer)
        // Daljinec vodi kazalec: kjer polj ni, naj se kazalec le malo premakne (ne smerna tipka).
        if (daljinec) d.put("sicer", "kazalec")
        zadnjiFokusDaljinec = daljinec
        poslji(d)
    }

    private var fokusSmer = 0
    private val ponoviFokus = object : Runnable {
        override fun run() {
            if (fokusSmer == 0 || isFinishing || koncujem) return
            posljiFokus(fokusSmer)
            glavna.postDelayed(this, 180)
        }
    }

    /** Vsak dogodek za racunalnik gre tu skozi: premik, klik ali tipka skrije okvir izbire. */
    private fun poslji(d: JSONObject) {
        val vrsta = d.optString("vrsta")
        // Klik okvirja ne skrije: racunalnik takoj preveri, ali je gumb se tam (kalkulator), in
        // okvir potrdi ali pospravi. Vse drugo (premik, tipka, kolesce) ga skrije takoj.
        if (okvir.visibility == View.VISIBLE && vrsta != "fokus" && vrsta != "povecava" && vrsta != "klik")
            okvir.visibility = View.GONE
        odjemalec?.posljiVnos(d)
    }

    // ------------------------------------------------------------------ dotik (tablica)

    /**
     * Na tablici je prava tipkovnica tista, ki jo uporabnik pozna: sistemska. Nevidno polje ujame,
     * kar natipka, in to takoj poslje racunalniku (crke kot besedilo, brisanje in Enter kot tipki).
     * Predlogi so izklopljeni: sestavljanje besede bi racunalniku poslalo polovicne crke.
     */
    private var sistemskoPolje: android.widget.EditText? = null

    private fun odpriTipkovnico() {
        if (!naDotik()) { tipkovnica?.odpri(); return }
        val polje = sistemskoPolje ?: android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                android.view.inputmethod.EditorInfo.IME_ACTION_NONE
            setText(" ")
            alpha = 0f
            addTextChangedListener(object : android.text.TextWatcher {
                private var ponastavljam = false
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (ponastavljam || s == null) return
                    val t = s.toString()
                    when {
                        t.isEmpty() -> poslji(JSONObject().put("vrsta", "tipka").put("tipka", "vracalka"))
                        t.length > 1 -> {
                            val novo = t.substring(1)
                            val brezNove = novo.replace("\n", "")
                            if (brezNove.isNotEmpty()) poslji(JSONObject().put("vrsta", "besedilo").put("besedilo", brezNove))
                            if (novo.contains('\n')) poslji(JSONObject().put("vrsta", "tipka").put("tipka", "vnasalka"))
                        }
                        else -> return
                    }
                    ponastavljam = true
                    s.replace(0, s.length, " ")
                    setSelection(1)
                    ponastavljam = false
                }
            })
            setOnKeyListener { _, koda, e ->
                if (e.action == KeyEvent.ACTION_DOWN && (koda == KeyEvent.KEYCODE_ENTER || koda == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                    poslji(JSONObject().put("vrsta", "tipka").put("tipka", "vnasalka")); true
                } else if (e.action == KeyEvent.ACTION_DOWN && koda == KeyEvent.KEYCODE_DEL && text.length <= 1) {
                    poslji(JSONObject().put("vrsta", "tipka").put("tipka", "vracalka")); true
                } else false
            }
            findViewById<android.widget.FrameLayout>(R.id.koren).addView(this,
                android.widget.FrameLayout.LayoutParams(1, 1))
            sistemskoPolje = this
        }
        polje.requestFocus()
        polje.setSelection(polje.text.length)
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.showSoftInput(polje, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun naDotik(): Boolean =
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN) &&
            (getSystemService(UI_MODE_SERVICE) as? android.app.UiModeManager)?.currentModeType !=
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

    private var dotikX = 0f
    private var dotikY = 0f
    private var vlecemDotik = false
    private var dolgiDotik = false
    private var dvaPrsta = false
    private var dvaPrstaY = 0f

    /** Drzi prst: desni klik tam (kot na telefonu dolg pritisk odpre meni). */
    private val dolgPritisk = Runnable {
        if (!vlecemDotik && !dvaPrsta) {
            dolgiDotik = true
            posljiTocko(dotikX, dotikY)
            poslji(ZaslonVnos.klik("desni"))
        }
    }

    private fun posljiTocko(x: Float, y: Float) {
        if (slikaW <= 0 || slikaH <= 0 || pogled.width <= 0 || pogled.height <= 0) return
        val sx = (x / pogled.width * slikaW).toInt().coerceIn(0, slikaW - 1)
        val sy = (y / pogled.height * slikaH).toInt().coerceIn(0, slikaH - 1)
        poslji(JSONObject().put("vrsta", "tocka").put("x", sx).put("y", sy))
    }

    private fun gumbLevi(dol: Boolean) = poslji(JSONObject().put("vrsta", "gumb").put("gumb", "levi").put("dol", dol))

    /**
     * Dotik: kratek dotik je klik tam, kamor pokazes; vlecenje s prstom vlece (oznaci besedilo,
     * premakne okno); drzanje je desni klik; dva prsta drsita vsebino gor in dol.
     */
    private fun dotik(e: MotionEvent): Boolean {
        val gostota = resources.displayMetrics.density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dotikX = e.x; dotikY = e.y
                vlecemDotik = false; dolgiDotik = false; dvaPrsta = false
                glavna.postDelayed(dolgPritisk, 550)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                glavna.removeCallbacks(dolgPritisk)
                if (vlecemDotik) { gumbLevi(false); vlecemDotik = false }
                dvaPrsta = true
                dvaPrstaY = if (e.pointerCount >= 2) (e.getY(0) + e.getY(1)) / 2 else e.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (dvaPrsta) {
                    if (e.pointerCount >= 2) {
                        val y = (e.getY(0) + e.getY(1)) / 2
                        val d = y - dvaPrstaY
                        val korak = 36 * gostota
                        if (kotlin.math.abs(d) >= korak) {
                            val n = (kotlin.math.abs(d) / korak).toInt()
                            // Prsta gor = vsebina gor, kot na telefonu (kolesce navzdol).
                            poslji(JSONObject().put("vrsta", "kolesce").put("smer", if (d < 0) "dol" else "gor").put("koliko", n))
                            dvaPrstaY += (if (d < 0) -1 else 1) * n * korak
                        }
                    }
                } else if (!dolgiDotik) {
                    if (!vlecemDotik && kotlin.math.hypot(e.x - dotikX, e.y - dotikY) > 12 * gostota) {
                        glavna.removeCallbacks(dolgPritisk)
                        vlecemDotik = true
                        posljiTocko(dotikX, dotikY)
                        gumbLevi(true)
                    }
                    if (vlecemDotik) posljiTocko(e.x, e.y)
                }
            }
            MotionEvent.ACTION_UP -> {
                glavna.removeCallbacks(dolgPritisk)
                if (vlecemDotik) {
                    posljiTocko(e.x, e.y); gumbLevi(false); vlecemDotik = false
                } else if (!dvaPrsta && !dolgiDotik) {
                    posljiTocko(e.x, e.y)
                    poslji(ZaslonVnos.klik("levi"))
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                glavna.removeCallbacks(dolgPritisk)
                if (vlecemDotik) { gumbLevi(false); vlecemDotik = false }
            }
        }
        return true
    }

    private fun barva(koda: Int): String? = when (koda) {
        KeyEvent.KEYCODE_PROG_RED -> "rdeca"
        KeyEvent.KEYCODE_PROG_GREEN -> "zelena"
        KeyEvent.KEYCODE_PROG_YELLOW -> "rumena"
        KeyEvent.KEYCODE_PROG_BLUE -> "modra"
        else -> null
    }

    /** Obvestilo racunalnika (na glavni niti). Stari racunalnik jih ne posilja - takrat nic. */
    private fun obvestilo(o: JSONObject) {
        if (isFinishing) return
        if (o.has("izbira")) {
            val a = o.optJSONArray("izbira")
            if (a != null && a.length() == 4) pokaziOkvir(a.optInt(0), a.optInt(1), a.optInt(2), a.optInt(3))
            else okvir.visibility = View.GONE
        }
        // Samo za daljinec: pri kontrolerju so v takem programu puscice puscice, ne miska.
        if (o.optBoolean("brez_gumbov") && zadnjiFokusDaljinec && !brezGumbovPovedano) {
            brezGumbovPovedano = true
            pokaziNamigBesedilo(getString(R.string.os_zaslon_brez_gumbov), 3_500)
        }
        // Namig o barvnih tipkah samo, ce jih daljinec tega televizorja res ima.
        if (o.optString("profil") == "brskalnik" &&
            android.view.KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_PROG_RED)) {
            val nast = getSharedPreferences("safeer_os", MODE_PRIVATE)
            if (!nast.getBoolean("profil_namig:brskalnik", false)) {
                nast.edit().putBoolean("profil_namig:brskalnik", true).apply()
                pokaziNamigBesedilo(getString(R.string.os_zaslon_profil_brskalnik), 6_000)
            }
        }
        if (o.optBoolean("tipkovnica") && tipkovnica?.jeOdprta != true) odpriTipkovnico()
        o.optJSONArray("kazalec")?.let { k -> if (povecava && k.length() == 2) premakniPovecavo(k.optInt(0), k.optInt(1)) }
    }

    private fun pokaziNamigBesedilo(besedilo: String, ms: Long) {
        namig.text = besedilo
        namig.visibility = View.VISIBLE
        glavna.removeCallbacks(skrijNamig)
        glavna.postDelayed(skrijNamig, ms)
    }

    /** Okvir okoli elementa (x, y, w, h v tockah drugega zaslona), poravnan s sliko in povecavo. */
    private fun pokaziOkvir(x: Int, y: Int, w: Int, h: Int) {
        if (slikaW <= 0 || slikaH <= 0 || trenW <= 0 || trenH <= 0) return
        val sx = trenW.toFloat() / slikaW
        val sy = trenH.toFloat() / slikaH
        val rob = 5 * resources.displayMetrics.density
        val lp = android.widget.FrameLayout.LayoutParams((w * sx + 2 * rob).toInt(), (h * sy + 2 * rob).toInt())
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        lp.leftMargin = (trenL + x * sx - rob).toInt()
        lp.topMargin = (trenT + y * sy - rob).toInt()
        okvir.layoutParams = lp
        okvir.visibility = View.VISIBLE
    }

    /** Vlecenje: levi gumb dol, nato kazalec premikas; OK (ali ponovno v meniju) spusti. */
    private fun preklopiVlecenje() {
        vlecem = !vlecem
        poslji(JSONObject().put("vrsta", "gumb").put("gumb", "levi").put("dol", vlecem))
        if (vlecem) pokaziNamigBesedilo(getString(R.string.os_zaslon_vlecem), 4_000)
    }

    /** Povecava 2x okoli kazalca. Racunalnik sporoca, kje je kazalec; sliko premaknemo sami. */
    private fun preklopiPovecavo() {
        povecava = !povecava
        poslji(JSONObject().put("vrsta", "povecava").put("vkljuceno", povecava))
        okvir.visibility = View.GONE
        if (!povecava) uravnajRazmerje(slikaW, slikaH)
    }

    private fun premakniPovecavo(px: Int, py: Int) {
        if (slikaW <= 0 || slikaH <= 0 || osnovaW <= 0) return
        val w2 = osnovaW * 2
        val h2 = osnovaH * 2
        val l = (osnovaL + osnovaW / 2 - (px.toFloat() / slikaW * w2).toInt()).coerceIn(osnovaL + osnovaW - w2, osnovaL)
        val t = (osnovaT + osnovaH / 2 - (py.toFloat() / slikaH * h2).toInt()).coerceIn(osnovaT + osnovaH - h2, osnovaT)
        if (l == trenL && t == trenT && trenW == w2) return
        val lp = android.widget.FrameLayout.LayoutParams(w2, h2)
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        lp.leftMargin = l
        lp.topMargin = t
        pogled.layoutParams = lp
        trenL = l; trenT = t; trenW = w2; trenH = h2
    }

    private fun smernaTipka(ime: String, dol: Boolean, velja: Boolean) {
        if (!velja) return
        poslji(org.json.JSONObject()
            .put("vrsta", if (dol) "tipka_dol" else "tipka_gor").put("tipka", ime))
    }

    private fun namiznaDesnaPalica(e: MotionEvent) {
        val y = e.getAxisValue(MotionEvent.AXIS_RZ)
        pomik = if (kotlin.math.abs(y) < 0.25f) 0f else y
        if (pomik == 0f || pomikTece) return
        pomikTece = true
        glavna.post(object : Runnable {
            override fun run() {
                val p = pomik
                if (p == 0f || isFinishing || koncujem) { pomikTece = false; return }
                poslji(org.json.JSONObject().put("vrsta", "kolesce")
                    .put("smer", if (p < 0) "gor" else "dol").put("koliko", 1))
                // Bolj ko je palica odklonjena, hitreje se pomika.
                glavna.postDelayed(this, (220 - 170 * kotlin.math.abs(p)).toLong().coerceAtLeast(40))
            }
        })
    }

    private fun namizniSprozilci(e: MotionEvent) {
        val r2 = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
        val l2 = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
        // Histereza: klik ob pritisku cez 0.6, ponovno sele, ko je sprozilec spet pod 0.3.
        if (!r2Pritisnjen && r2 > 0.6f) { r2Pritisnjen = true; poslji(ZaslonVnos.klik("levi")) }
        else if (r2Pritisnjen && r2 < 0.3f) r2Pritisnjen = false
        if (!l2Pritisnjen && l2 > 0.6f) { l2Pritisnjen = true; poslji(ZaslonVnos.klik("desni")) }
        else if (l2Pritisnjen && l2 < 0.3f) l2Pritisnjen = false
    }

    /** Ob odhodu ali menjavi nacina spustimo smerne tipke, ki jih drzi krizec. */
    private fun spustiKrizec() {
        smernaTipka(if (krizecX < 0) "levo" else "desno", false, krizecX != 0)
        smernaTipka(if (krizecY < 0) "gor" else "dol", false, krizecY != 0)
        krizecX = 0; krizecY = 0; pomik = 0f; r2Pritisnjen = false; l2Pritisnjen = false
        fokusSmer = 0; glavna.removeCallbacks(ponoviFokus)
    }

    /**
     * Palica ne poslje dogodka, dokler se premika - poslje ga le ob spremembi odklona. Zato kazalec
     * premikamo sami, dokler je palica odklonjena, in neham, ko se vrne v mirovanje.
     */
    private fun zazeniPalico() {
        if (palicaTece) return
        palicaTece = true
        glavna.post(object : Runnable {
            override fun run() {
                val o = odklon
                if (o == null || isFinishing || koncujem) { palicaTece = false; return }
                ZaslonVnos.premik((o.first * ZaslonVnos.HITROST_PALICE).toInt(),
                                  (o.second * ZaslonVnos.HITROST_PALICE).toInt())
                    ?.let { poslji(it) }
                glavna.postDelayed(this, 16)
            }
        })
    }

    // ------------------------------------------------------------------ Link

    override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) {
        if (seja == null && !prosim) zahtevajSejo()
    }

    override fun naStanje(povezan: Boolean, sporocilo: String) { }
    override fun naNaslov(url: String, naslov: String, od: String) { }
    override fun naBesedilo(besedilo: String, od: String) { }
    override fun naZavrnitev() { }

    companion object {
        const val EXTRA_KAKOVOST = "kakovost"
        /** `apps`, kadar zaslon odpremo po zagonu programa (locen zaslon na racunalniku). */
        const val EXTRA_ZASLON = "zaslon"
        /** Oznaka programa (app:...desktop) in ali je igra - za nacin tipk ob zacetku. */
        const val EXTRA_PROGRAM = "program"
        const val EXTRA_IGRA = "igra"
        /**
         * Na koliko ponovitev drzanja znova javimo, da je tipka se vedno drzana. Android ponavlja
         * priblizno dvajsetkrat na sekundo, racunalnik pa pozabljeno tipko spusti po petih
         * sekundah - enkrat na sekundo je torej varno in skoraj zastonj.
         */
        private const val PONOVI_DRZANJE = 20
    }
}
