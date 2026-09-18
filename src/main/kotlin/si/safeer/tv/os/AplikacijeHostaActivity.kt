package si.safeer.tv.os

import si.safeer.tv.R

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Programi racunalnika na televizorju: kar ima uporabnik na svojem racunalniku, vidi tudi tu in
 * lahko zazene z daljincem. Seznam pride po Safeer Linku (ukaz `apps.list`, core/link_programi.py),
 * zagon gre nazaj po isti poti (`apps.launch`).
 *
 * Pomembno in pošteno povedano uporabniku: program se odpre **na zaslonu racunalnika**, ne na
 * televizorju. Zrcaljenje zaslona je naslednji korak; dokler ga ni, tega ne obljubljamo.
 */
class AplikacijeHostaActivity : Activity(), LinkOdjemalec.Poslusalec {

    private class Program(val id: String, val ime: String, val opis: String, val ikona: android.graphics.drawable.Drawable?)

    private lateinit var mreza: GridView
    private lateinit var naslov: TextView
    private lateinit var nadnaslov: TextView
    private lateinit var znacka: TextView
    private lateinit var sporocilo: TextView

    private val link by lazy { LinkUpravitelj.pridobi(this) }
    private val prilagojevalnik = Prilagojevalnik()
    private var programi: List<Program> = emptyList()
    private var racunalnik: LinkOdjemalec.Naprava? = null
    private var nalagam = false

    /** Koliko programov (z ikonami) prosimo naenkrat; en kos mora ostati krepko pod 256 kB. */
    private val STRAN = 18

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.os_activity_programi)
        mreza = findViewById(R.id.mreza)
        naslov = findViewById(R.id.naslov)
        nadnaslov = findViewById(R.id.nadnaslov)
        znacka = findViewById(R.id.racunalnik)
        sporocilo = findViewById(R.id.sporocilo)
        nadnaslov.text = getString(R.string.os_programi)
        naslov.text = getString(R.string.os_programi_naslov)
        mreza.adapter = prilagojevalnik
        mreza.setOnItemClickListener { _, _, i, _ -> programi.getOrNull(i)?.let { zazeni(it) } }
    }

    override fun onStart() {
        super.onStart()
        Ozadje.uporabi(this, findViewById(R.id.koren))
        link.dodaj(this)
        if (programi.isEmpty()) nalozi()
    }

    override fun onStop() {
        link.odstrani(this)
        super.onStop()
    }

    /** Racunalnik, ki programe deli (zmoznost "apps"); brez njega ni kaj pokazati. */
    private fun racunalnikSProgrami(): LinkOdjemalec.Naprava? =
        link.naprave.firstOrNull { it.zmoznosti.contains("apps") && it.id != Identiteta.id(this) }

    private fun nalozi() {
        if (nalagam) return
        val r = racunalnikSProgrami()
        if (r == null) {
            znacka.visibility = View.GONE
            pokaziSporocilo(getString(
                if (!link.povezan) R.string.os_programi_ni_povezave else R.string.os_programi_ni_racunalnika))
            return
        }
        racunalnik = r
        znacka.text = r.ime.ifBlank { r.id }
        znacka.visibility = View.VISIBLE
        programi = emptyList()
        prilagojevalnik.notifyDataSetChanged()
        pokaziSporocilo(getString(R.string.os_programi_nalagam))
        naloziStran(r, 0)
    }

    /**
     * Seznam pride po kosih. Ikone so slike in racunalnik jih ima lahko sto: celoten odgovor bi
     * presegel omejitev sporocila v Linku (256 kB) in bi tiho padel skozi - uporabnik bi cakal v
     * prazno. Zato prosimo za [STRAN] programov naenkrat in jih sproti dodajamo v mrezo.
     */
    private fun naloziStran(r: LinkOdjemalec.Naprava, od: Int) {
        nalagam = true
        val zahteva = JSONObject().put("offset", od).put("limit", STRAN)
        link.ukaz(r.id, "apps.list", zahteva, 25_000, LinkOdjemalec.Odgovor { izid, napaka ->
            nalagam = false
            if (isFinishing || racunalnik?.id != r.id) return@Odgovor
            if (izid == null) { koncajSSporocilom(getString(R.string.os_programi_napaka, napaka)); return@Odgovor }
            if (!izid.optBoolean("ok")) { koncajSSporocilom(getString(R.string.os_programi_napaka, izid.optString("message"))); return@Odgovor }
            val podatki = izid.optJSONObject("data") ?: JSONObject()
            if (!podatki.optBoolean("enabled", false)) {
                koncajSSporocilom(getString(R.string.os_programi_izklopljeno, r.ime.ifBlank { r.id })); return@Odgovor
            }
            val polje = podatki.optJSONArray("items")
            val novi = ArrayList<Program>(programi)
            if (polje != null) for (i in 0 until polje.length()) {
                val o = polje.optJSONObject(i) ?: continue
                val id = o.optString("id"); if (id.isBlank()) continue
                novi.add(Program(id, o.optString("name"), o.optString("comment"), ikona(o.optString("icon_png"))))
            }
            val prvi = programi.isEmpty() && novi.isNotEmpty()
            programi = novi
            prilagojevalnik.notifyDataSetChanged()
            if (novi.isNotEmpty()) skrijSporocilo()
            if (prvi) { mreza.requestFocus(); mreza.setSelection(0) }
            val skupaj = podatki.optInt("total", novi.size)
            val prejeto = polje?.length() ?: 0
            when {
                novi.size < skupaj && prejeto > 0 -> naloziStran(r, od + prejeto)
                novi.isEmpty() -> pokaziSporocilo(getString(R.string.os_programi_prazno))
            }
        })
    }

    /** Napaka sredi nalaganja: ce smo kaj ze pokazali, pustimo to in samo povemo, kaj je slo narobe. */
    private fun koncajSSporocilom(b: String) {
        pokaziSporocilo(b)
    }

    /** Ikona programa: PNG v base64 iz odgovora, oblikovan enako kot ikone spletnih aplikacij. */
    private fun ikona(base64: String): android.graphics.drawable.Drawable? {
        if (base64.isBlank()) return null
        return try {
            val bajti = Base64.decode(base64, Base64.DEFAULT)
            val slika = BitmapFactory.decodeByteArray(bajti, 0, bajti.size) ?: return null
            SpletneAplikacije.ikonaIzSlike(this, slika)
        } catch (_: Throwable) { null }
    }

    /**
     * Zagon: povemo racunalniku, naj odpre program. Uporabniku takoj povemo resnico - program se
     * odpre na zaslonu racunalnika, ne tukaj.
     */
    private fun zazeni(p: Program) {
        val r = racunalnik ?: return
        link.ukaz(r.id, "apps.launch", JSONObject().put("app", p.id), 10_000, LinkOdjemalec.Odgovor { izid, napaka ->
            if (isFinishing) return@Odgovor
            val uspelo = izid?.optBoolean("ok") == true
            val besedilo = if (uspelo) getString(R.string.os_programi_zagnan, p.ime, r.ime.ifBlank { r.id })
            else getString(R.string.os_programi_napaka, izid?.optString("message").orEmpty().ifBlank { napaka })
            Toast.makeText(this, besedilo, Toast.LENGTH_LONG).show()
        })
    }

    private fun pokaziSporocilo(b: String) { sporocilo.text = b; sporocilo.visibility = View.VISIBLE }
    private fun skrijSporocilo() { sporocilo.visibility = View.GONE }

    // ------------------------------------------------------------------ Link

    override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) {
        if (programi.isEmpty() && !nalagam) nalozi()
    }

    override fun naStanje(povezan: Boolean, sporocilo: String) { }
    override fun naNaslov(url: String, naslov: String, od: String) { }
    override fun naBesedilo(besedilo: String, od: String) { }
    override fun naZavrnitev() { }

    private inner class Prilagojevalnik : BaseAdapter() {
        override fun getCount(): Int = programi.size
        override fun getItem(i: Int): Any = programi[i]
        override fun getItemId(i: Int): Long = i.toLong()
        override fun getView(i: Int, star: View?, roditelj: ViewGroup): View {
            val v = star ?: LayoutInflater.from(this@AplikacijeHostaActivity)
                .inflate(R.layout.os_kartica_program, roditelj, false)
            val p = programi[i]
            val ikona = v.findViewById<ImageView>(R.id.ikona)
            if (p.ikona != null) ikona.setImageDrawable(p.ikona) else ikona.setImageResource(R.drawable.os_ikona_racunalnik)
            v.findViewById<TextView>(R.id.ime).text = p.ime
            return v
        }
    }
}
