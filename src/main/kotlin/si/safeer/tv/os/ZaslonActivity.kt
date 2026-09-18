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
    private var okDrzan = false
    private var namigPokazan = false
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
        tipkovnica = ZaslonTipkovnica(this, tipkovnicaPogled,
            naBesedilo = { z -> odjemalec?.posljiVnos(JSONObject().put("vrsta", "besedilo").put("besedilo", z)) },
            naTipko = { t -> odjemalec?.posljiVnos(JSONObject().put("vrsta", "tipka").put("tipka", t)) })
        // Vedno najboljse, kar zmore racunalnik: uporabniku ni treba izbirati med kakovostmi,
        // ker za nizjo ni razloga - meritve kazejo, da ostrejsa slika skoraj nic ne stane.
        kakovost = intent.getStringExtra(EXTRA_KAKOVOST) ?: "najvisja"
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

    override fun onStop() {
        link.odstrani(this)
        super.onStop()
    }

    override fun onDestroy() {
        koncaj()
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
        link.ukaz(r.id, "screen.start", JSONObject().put("quality", kakovost), 15_000,
            LinkOdjemalec.Odgovor { izid, napaka ->
                prosim = false
                if (isFinishing) return@Odgovor
                if (izid == null) { pokazi(getString(R.string.os_zaslon_napaka, napaka)); return@Odgovor }
                if (!izid.optBoolean("ok")) {
                    val koda = izid.optString("code")
                    pokazi(if (koda == "ni_dovoljeno") getString(R.string.os_zaslon_ni_dovoljeno,
                        r.ime.ifBlank { r.id }) else getString(R.string.os_zaslon_napaka, izid.optString("message")))
                    return@Odgovor
                }
                val podatki = izid.optJSONObject("data") ?: return@Odgovor
                seja = podatki
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
        }
    }

    private fun zacniPretok(podatki: JSONObject) {
        val r = racunalnik ?: return
        uravnajRazmerje(podatki.optInt("width"), podatki.optInt("height"))
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
                            // Nazaj je zdaj tipka za racunalnik, zato uporabniku enkrat povemo, kako se konca.
                            if (!namigPokazan) {
                                namigPokazan = true
                                Toast.makeText(this, getString(R.string.os_zaslon_namig), Toast.LENGTH_LONG).show()
                            }
                            // Napis o upravljanju: kaj delajo tipke zdaj, in kako se preklopi.
                            pokaziNamig()
                        }
                        // Prekinjena povezava ni konec seje: enkrat poskusimo znova, sele nato
                        // uporabnika vrnemo nazaj - zamrznjena slika je najslabsi mozni izid.
                        ZaslonOdjemalec.Stanje.KONCANO -> if (!koncujem) ponoviAliKoncaj(getString(R.string.os_zaslon_koncano))
                        ZaslonOdjemalec.Stanje.NAPAKA -> ponoviAliKoncaj(getString(R.string.os_zaslon_napaka, besedilo))
                    }
                }
            },
            naStatistiko = { s ->
                runOnUiThread {
                    if (!isFinishing) meritve.text = getString(R.string.os_zaslon_meritve,
                        s.sirina, s.visina, s.naSekundo, s.megabitov, s.dekoderMs) +
                        (if (s.zvok) " · " + getString(R.string.os_zaslon_zvok) else "")
                }
            })
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
        meritve.visibility = View.VISIBLE
    }

    /** Konec seje: ustavimo tudi zajem na racunalniku, da ne tece v prazno. */
    private fun koncaj() {
        if (koncujem) return
        koncujem = true
        odjemalec?.ustavi()
        odjemalec = null
        val r = racunalnik ?: return
        link.ukaz(r.id, "screen.stop", JSONObject(), 5_000, LinkOdjemalec.Odgovor { _, _ -> })
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
        if (ZaslonVnos.jePreklop(keyCode)) { odpriMeni(); return true }
        if (tipkovnica?.jeOdprta == true) {
            // Igralni plosek pise skupaj s tipkovnico: A vtipka, B brise, X presledek, Y velike.
            if (tipkovnica?.plosek(keyCode) == true) return true
            return super.onKeyDown(keyCode, event)
        }
        if (kazalec) {
            // V nacinu kazalca smerne tipke vodijo misko, OK pa klika (dolg OK desni klik).
            val s = ZaslonVnos.smer(keyCode)
            if (s != null) { zacniSmer(s); return true }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                if (event?.repeatCount == 0) { okDrzan = true; event.startTracking() }
                return true
            }
        }
        val dogodek = ZaslonVnos.izTipke(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        odjemalec?.posljiVnos(dogodek)
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { koncaj(); finish(); return true }
        if (kazalec && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A)) {
            okDrzan = false
            odjemalec?.posljiVnos(ZaslonVnos.klik("desni"))
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event != null && !event.isCanceled && !event.isLongPress) {
                odjemalec?.posljiVnos(org.json.JSONObject().put("vrsta", "tipka").put("tipka", "nazaj"))
            }
            return true
        }
        if (tipkovnica?.jeOdprta == true) return super.onKeyUp(keyCode, event)
        if (kazalec) {
            if (ZaslonVnos.smer(keyCode) != null) { ustaviSmer(); return true }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                if (okDrzan) { okDrzan = false; odjemalec?.posljiVnos(ZaslonVnos.klik("levi")) }
                return true
            }
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
        dejanja.add(getString(R.string.os_zaslon_meni_tipkovnica) to { tipkovnica?.odpri() })
        dejanja.add(getString(
            if (kazalec) R.string.os_zaslon_meni_tipke else R.string.os_zaslon_meni_kazalec) to { preklopiNacin() })
        dejanja.add(getString(R.string.os_zaslon_meni_shrani) to { posljiTipko("shrani") })
        dejanja.add(getString(R.string.os_zaslon_meni_bliznjice) to { odpriBliznjice() })
        dejanja.add(getString(R.string.os_zaslon_meni_koncaj) to { koncaj(); finish() })
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
        odjemalec?.posljiVnos(JSONObject().put("vrsta", "tipka").put("tipka", ime))
    }

    /** Preklop med kazalcem in tipkami; uporabnik takoj vidi, kaj zdaj delajo tipke. */
    private fun preklopiNacin() {
        ustaviSmer()
        kazalec = !kazalec
        pokaziNamig()
    }

    /** Namig o upravljanju: pokaze se ob zacetku seje in ob preklopu, nato sam izgine. */
    private fun pokaziNamig() {
        namig.text = getString(
            if (kazalec) R.string.os_zaslon_nacin_kazalec else R.string.os_zaslon_nacin_tipke)
        namig.visibility = View.VISIBLE
        glavna.removeCallbacks(skrijNamig)
        glavna.postDelayed(skrijNamig, 6_000)
    }

    private val skrijNamig = Runnable { namig.visibility = View.GONE }

    /**
     * Drzanje smerne tipke premika kazalec: zacne pocasi (da zadenes gumb) in se pospesuje, dokler
     * tipko drzis. Brez pospeska je pot cez zaslon predolga, s samo hitrim premikom pa se ne da
     * natancno zadeti.
     */
    private fun zacniSmer(nova: Pair<Int, Int>) {
        if (smer == nova) return
        smer = nova
        hitrost = ZaslonVnos.KAZALEC_ZACETNA
        if (smerTece) return
        smerTece = true
        glavna.post(object : Runnable {
            override fun run() {
                val s = smer
                if (s == null || isFinishing || koncujem) { smerTece = false; return }
                ZaslonVnos.premik((s.first * hitrost).toInt(), (s.second * hitrost).toInt())
                    ?.let { odjemalec?.posljiVnos(it) }
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
        ZaslonVnos.izMiske(event)?.let { odjemalec?.posljiVnos(it); return true }
        ZaslonVnos.izMiskinihGumbov(event)?.let { odjemalec?.posljiVnos(it); return true }
        val palica = ZaslonVnos.izPalice(event)
        if (palica != null) { odklon = palica; zazeniPalico(); return true }
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) {
            odklon = null
            return true
        }
        return super.onGenericMotionEvent(event)
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
                    ?.let { odjemalec?.posljiVnos(it) }
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
    }
}
