package si.safeer.os

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale

/**
 * Datoteke z racunalnika: Safeer Control na racunalniku deli izbrane mape, televizor jih pregleduje
 * po Safeer Linku (ukaz `files.list`) in predvaja naravnost z racunalnika (HTTPS s pripetim odtisom
 * in zetonom iz odgovora). Nic ne gre skozi sredisce, nic v oblak.
 *
 * Zasloni: seznam racunalnikov (ce jih je vec) -> deljene mape -> mape in datoteke. Nazaj gre za
 * eno raven navzgor, na vrhu zapre zaslon.
 */
class DatotekeActivity : Activity(), LinkOdjemalec.Poslusalec {

    /** Streznik datotek na racunalniku (naslov, odtis potrdila, zeton te naprave) iz odgovora `files.list`. */
    data class Streznik(val osnova: String, val odtis: String, val zeton: String) {
        fun url(id: String): String = osnova + "/d/" + android.net.Uri.encode(id)
        fun vBundle(b: Bundle) { b.putString("s_osnova", osnova); b.putString("s_odtis", odtis); b.putString("s_zeton", zeton) }
        companion object {
            fun iz(b: Bundle?): Streznik? {
                val o = b?.getString("s_osnova") ?: return null
                return Streznik(o, b.getString("s_odtis").orEmpty(), b.getString("s_zeton").orEmpty())
            }
        }
    }

    data class Vnos(val id: String, val ime: String, val vrsta: String, val velikost: Long, val mime: String)

    private data class Raven(val oznaka: String, val ime: String)

    private lateinit var seznam: ListView
    private lateinit var naslov: TextView
    private lateinit var nadnaslov: TextView
    private lateinit var racunalnikZnacka: TextView
    private lateinit var sporocilo: TextView

    private val link by lazy { SafeerOs.link(this) }
    private val prilagojevalnik = Prilagojevalnik()

    private var racunalnik: LinkOdjemalec.Naprava? = null
    private val pot = ArrayList<Raven>()
    private var streznik: Streznik? = null
    private var vnosi: List<Vnos> = emptyList()
    private var nalagam = false
    private var izbiramRacunalnik = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_datoteke)
        seznam = findViewById(R.id.seznam)
        naslov = findViewById(R.id.naslov)
        nadnaslov = findViewById(R.id.nadnaslov)
        racunalnikZnacka = findViewById(R.id.racunalnik)
        sporocilo = findViewById(R.id.sporocilo)
        seznam.adapter = prilagojevalnik
        seznam.setOnItemClickListener { _, _, i, _ -> izberi(i) }
    }

    override fun onStart() {
        super.onStart()
        link.dodaj(this)
        if (racunalnik == null) zacni()
    }

    override fun onStop() {
        link.odstrani(this)
        super.onStop()
    }

    /** Vstop: racunalnik iz namere, edini z datotekami, ali seznam racunalnikov. */
    private fun zacni() {
        val zeleni = intent.getStringExtra(EXTRA_RACUNALNIK)
        val kandidati = link.racunalnikiZDatotekami()
        val r = kandidati.firstOrNull { it.id == zeleni } ?: kandidati.singleOrNull()
        when {
            r != null -> odpriRacunalnik(r)
            kandidati.isEmpty() -> pokaziRacunalnike(emptyList())
            else -> pokaziRacunalnike(kandidati)
        }
    }

    private fun pokaziRacunalnike(r: List<LinkOdjemalec.Naprava>) {
        izbiramRacunalnik = true
        racunalnik = null
        pot.clear()
        nadnaslov.text = getString(R.string.datoteke)
        naslov.text = getString(R.string.datoteke_racunalniki)
        racunalnikZnacka.visibility = View.GONE
        vnosi = r.map { Vnos(it.id, it.ime.ifBlank { it.id }, "computer", -1, "") }
        prilagojevalnik.notifyDataSetChanged()
        if (r.isEmpty()) {
            if (!link.povezan) pokaziSporocilo(getString(R.string.datoteke_ni_povezave))
            else pokaziSporocilo(getString(R.string.datoteke_ni_racunalnika))
        } else {
            skrijSporocilo()
            seznam.requestFocus(); seznam.setSelection(0)
        }
    }

    private fun odpriRacunalnik(r: LinkOdjemalec.Naprava) {
        izbiramRacunalnik = false
        racunalnik = r
        pot.clear()
        streznik = null
        racunalnikZnacka.text = r.ime.ifBlank { r.id }
        racunalnikZnacka.visibility = View.VISIBLE
        nalozi("")
    }

    private fun nalozi(oznaka: String) {
        val r = racunalnik ?: return
        nalagam = true
        vnosi = emptyList()
        prilagojevalnik.notifyDataSetChanged()
        nadnaslov.text = r.ime.ifBlank { getString(R.string.datoteke) }
        naslov.text = if (pot.isEmpty()) getString(R.string.datoteke_koren) else pot.joinToString(" / ") { it.ime }
        pokaziSporocilo(getString(R.string.datoteke_nalagam))
        link.ukaz(r.id, "files.list", JSONObject().put("folder", oznaka), 12_000, LinkOdjemalec.Odgovor { izid, napaka ->
            if (isFinishing || racunalnik?.id != r.id) return@Odgovor
            nalagam = false
            if (izid == null) {
                pokaziSporocilo(getString(if (napaka == "ni_povezave") R.string.datoteke_ni_povezave else R.string.datoteke_napaka, napaka))
                return@Odgovor
            }
            if (!izid.optBoolean("ok")) {
                pokaziSporocilo(getString(R.string.datoteke_napaka, izid.optString("message")))
                return@Odgovor
            }
            val podatki = izid.optJSONObject("data") ?: JSONObject()
            podatki.optJSONObject("server")?.let {
                streznik = Streznik(it.optString("base_url").trimEnd('/'), it.optString("fp"), it.optString("token"))
            }
            if (!podatki.optBoolean("shared", true)) {
                pokaziSporocilo(getString(R.string.datoteke_prazno_racunalnik, r.ime.ifBlank { r.id }))
                return@Odgovor
            }
            val polje = podatki.optJSONArray("items")
            val nov = ArrayList<Vnos>()
            if (polje != null) for (i in 0 until polje.length()) {
                val v = polje.optJSONObject(i) ?: continue
                nov.add(Vnos(v.optString("id"), v.optString("name"), v.optString("type", "file"), v.optLong("size", -1), v.optString("mime")))
            }
            vnosi = nov
            prilagojevalnik.notifyDataSetChanged()
            if (nov.isEmpty()) pokaziSporocilo(getString(R.string.datoteke_prazna_mapa)) else {
                skrijSporocilo()
                seznam.requestFocus(); seznam.setSelection(0)
            }
        })
    }

    private fun izberi(i: Int) {
        val v = vnosi.getOrNull(i) ?: return
        if (izbiramRacunalnik) {
            link.racunalnikiZDatotekami().firstOrNull { it.id == v.id }?.let { odpriRacunalnik(it) }
            return
        }
        when (v.vrsta) {
            "folder" -> { pot.add(Raven(v.id, v.ime)); nalozi(v.id) }
            "video", "audio" -> predvajaj(v)
            "image" -> pokaziSliko(v)
            else -> Toast.makeText(this, getString(R.string.datoteke_neznana_vrsta), Toast.LENGTH_SHORT).show()
        }
    }

    private fun predvajaj(v: Vnos) {
        val s = streznik ?: return
        val namera = Intent(this, PredvajalnikActivity::class.java)
            .putExtra("url", s.url(v.id)).putExtra("ime", v.ime).putExtra("mime", v.mime).putExtra("zvok", v.vrsta == "audio")
        val b = Bundle(); s.vBundle(b); namera.putExtras(b)
        startActivity(namera)
    }

    private fun pokaziSliko(v: Vnos) {
        val s = streznik ?: return
        val slike = vnosi.filter { it.vrsta == "image" }
        val namera = Intent(this, SlikaActivity::class.java)
            .putStringArrayListExtra("urli", ArrayList(slike.map { s.url(it.id) }))
            .putStringArrayListExtra("imena", ArrayList(slike.map { it.ime }))
            .putExtra("zacetek", slike.indexOfFirst { it.id == v.id }.coerceAtLeast(0))
        val b = Bundle(); s.vBundle(b); namera.putExtras(b)
        startActivity(namera)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            when {
                pot.isNotEmpty() -> { pot.removeAt(pot.size - 1); nalozi(pot.lastOrNull()?.oznaka ?: ""); return true }
                racunalnik != null && link.racunalnikiZDatotekami().size > 1 -> { pokaziRacunalnike(link.racunalnikiZDatotekami()); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun pokaziSporocilo(b: String) { sporocilo.text = b; sporocilo.visibility = View.VISIBLE }
    private fun skrijSporocilo() { sporocilo.visibility = View.GONE }

    // ------------------------------------------------------------------ Link

    override fun naStanje(povezan: Boolean, sporocilo: String) {
        if (!povezan && racunalnik == null) pokaziSporocilo(getString(R.string.datoteke_ni_povezave))
    }

    override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) {
        val z = link.racunalnikiZDatotekami()
        val r = racunalnik
        if (r == null) {
            // Racunalnik se je pravkar prikljucil (ali odsel): seznam brez ponovnega odpiranja zaslona.
            if (izbiramRacunalnik || vnosi.isEmpty()) {
                val edini = z.singleOrNull()
                if (edini != null && intent.getStringExtra(EXTRA_RACUNALNIK).let { it == null || it == edini.id }) odpriRacunalnik(edini)
                else pokaziRacunalnike(z)
            }
        } else if (z.none { it.id == r.id }) {
            pokaziRacunalnike(z)
        }
    }

    override fun naNaslov(url: String, naslov: String, od: String) { }
    override fun naBesedilo(besedilo: String, od: String) { }
    override fun naZavrnitev() { }

    // ------------------------------------------------------------------ seznam

    private inner class Prilagojevalnik : BaseAdapter() {
        override fun getCount(): Int = vnosi.size
        override fun getItem(i: Int): Any = vnosi[i]
        override fun getItemId(i: Int): Long = i.toLong()
        override fun getView(i: Int, obstojeci: View?, stars: ViewGroup?): View {
            val v = obstojeci ?: LayoutInflater.from(this@DatotekeActivity).inflate(R.layout.vrstica_datoteka, stars, false)
            val vnos = vnosi[i]
            v.findViewById<ImageView>(R.id.ikona).setImageResource(ikona(vnos.vrsta))
            v.findViewById<TextView>(R.id.ime).text = vnos.ime
            v.findViewById<TextView>(R.id.opis).text = opis(this@DatotekeActivity, vnos)
            return v
        }
    }

    companion object {
        const val EXTRA_RACUNALNIK = "racunalnik"

        fun ikona(vrsta: String): Int = when (vrsta) {
            "folder" -> R.drawable.ikona_mapa
            "video" -> R.drawable.ikona_video
            "audio" -> R.drawable.ikona_glasba
            "image" -> R.drawable.ikona_slika
            "computer" -> R.drawable.ikona_racunalnik
            else -> R.drawable.ikona_datoteka
        }

        fun opis(c: Context, v: Vnos): String {
            val vrsta = when (v.vrsta) {
                "folder" -> c.getString(R.string.vrsta_mapa)
                "video" -> c.getString(R.string.vrsta_video)
                "audio" -> c.getString(R.string.vrsta_audio)
                "image" -> c.getString(R.string.vrsta_slika)
                "computer" -> "Safeer Control"
                else -> c.getString(R.string.vrsta_datoteka)
            }
            return if (v.velikost >= 0) "$vrsta · ${velikost(v.velikost)}" else vrsta
        }

        fun velikost(b: Long): String = when {
            b >= 1L shl 30 -> String.format(Locale.getDefault(), "%.1f GB", b / (1L shl 30).toDouble())
            b >= 1L shl 20 -> String.format(Locale.getDefault(), "%.0f MB", b / (1L shl 20).toDouble())
            b >= 1L shl 10 -> String.format(Locale.getDefault(), "%.0f kB", b / 1024.0)
            else -> "$b B"
        }
    }
}
