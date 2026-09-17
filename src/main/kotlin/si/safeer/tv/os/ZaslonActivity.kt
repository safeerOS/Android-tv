package si.safeer.tv.os

import si.safeer.tv.R

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
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
 * okvirja. Tipka Nazaj konca sejo tudi na racunalniku (`screen.stop`), da zajem ne tece naprej v prazno.
 */
class ZaslonActivity : Activity(), LinkOdjemalec.Poslusalec {

    private lateinit var pogled: SurfaceView
    private lateinit var sporocilo: TextView
    private lateinit var meritve: TextView

    private val link by lazy { LinkUpravitelj.pridobi(this) }
    private var odjemalec: ZaslonOdjemalec? = null
    private var racunalnik: LinkOdjemalec.Naprava? = null
    private var povrsinaPripravljena = false
    private var seja: JSONObject? = null
    private var kakovost = "srednja"
    private var koncujem = false
    private var poskusov = 0
    private var prosim = false
    private val glavna = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.os_activity_zaslon)
        pogled = findViewById(R.id.povrsina)
        sporocilo = findViewById(R.id.sporocilo)
        meritve = findViewById(R.id.meritve)
        kakovost = intent.getStringExtra(EXTRA_KAKOVOST) ?: ZaslonNastavitve.kakovost(this)
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
                        ZaslonOdjemalec.Stanje.TECE -> { poskusov = 0; skrij() }
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
                        s.sirina, s.visina, s.naSekundo, s.megabitov, s.dekoderMs)
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { koncaj(); finish(); return true }
        return super.onKeyDown(keyCode, event)
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
