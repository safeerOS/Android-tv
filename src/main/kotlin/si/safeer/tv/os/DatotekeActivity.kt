package si.safeer.tv.os

import si.safeer.tv.R

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

    data class Vnos(val id: String, val ime: String, val vrsta: String, val velikost: Long, val mime: String, val pod: String = "")

    private data class Raven(val oznaka: String, val ime: String)

    private lateinit var seznam: ListView
    private lateinit var naslov: TextView
    private lateinit var nadnaslov: TextView
    private lateinit var racunalnikZnacka: TextView
    private lateinit var sporocilo: TextView

    private val link by lazy { LinkUpravitelj.pridobi(this) }
    private val prilagojevalnik = Prilagojevalnik()

    private var racunalnik: LinkOdjemalec.Naprava? = null
    private val pot = ArrayList<Raven>()
    private var streznik: Streznik? = null
    private var vnosi: List<Vnos> = emptyList()
    private var nalagam = false
    private var izbiramRacunalnik = false
    /** Krajevni vir: datoteke tega televizorja (MediaStore), brez Safeer Linka. */
    private var krajevni = false
    private var krajevnaZbirka = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.os_activity_datoteke)
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

    /** Vstop: vir iz namere, edini racunalnik, krajevne datoteke (brez Linka) ali seznam virov. */
    private fun zacni() {
        val zeleni = intent.getStringExtra(EXTRA_RACUNALNIK)
        // V krajevnem nacinu Safeer Linka ni: tudi ce je zaslon dobil racunalnik iz prejsnje
        // povezave, odpremo datoteke tega televizorja.
        val kandidati = if (link.jeKrajevni()) emptyList() else link.racunalnikiZDatotekami()
        val r = kandidati.firstOrNull { it.id == zeleni }
        // Kadar je racunalnik na voljo, uporabnika vedno najprej vprasamo, od kod hoce datoteke -
        // s tega televizorja ali z racunalnika. Prej smo pri enem samem racunalniku to preskocili
        // in uporabnik je padel naravnost v deljene mape, ne da bi imel izbiro.
        when {
            intent.getBooleanExtra(EXTRA_KRAJEVNO, false) || link.jeKrajevni() -> odpriKrajevno()
            r != null -> odpriRacunalnik(r)
            kandidati.isEmpty() -> odpriKrajevno()
            else -> pokaziRacunalnike(kandidati)
        }
    }

    // ------------------------------------------------------------------ krajevne datoteke (ta televizor)

    private fun odpriKrajevno() {
        izbiramRacunalnik = false
        krajevni = true
        racunalnik = null
        streznik = null
        pot.clear()
        krajevnaZbirka = ""
        racunalnikZnacka.text = getString(R.string.os_krajevno_ta_tv)
        racunalnikZnacka.visibility = View.VISIBLE
        if (!KrajevneDatoteke.imamoDovoljenje(this)) {
            nadnaslov.text = getString(R.string.os_krajevno_ta_tv)
            naslov.text = getString(R.string.os_krajevno_koren)
            vnosi = emptyList(); prilagojevalnik.notifyDataSetChanged()
            pokaziSporocilo(getString(R.string.os_krajevno_dovoljenje))
            try { requestPermissions(KrajevneDatoteke.dovoljenja(), ZAHTEVA_DOVOLJENJA) } catch (_: Throwable) { }
            return
        }
        naloziKrajevno("")
    }

    private fun naloziKrajevno(zbirka: String) {
        krajevnaZbirka = zbirka
        nadnaslov.text = getString(R.string.os_krajevno_ta_tv)
        naslov.text = if (zbirka.isEmpty()) getString(R.string.os_krajevno_koren) else pot.lastOrNull()?.ime.orEmpty()
        pokaziSporocilo(getString(R.string.os_datoteke_nalagam))
        // Branje MediaStore je lahko pocasno (USB s tisoci datotek): v ozadju.
        Thread({
            val novi = try {
                if (zbirka.isEmpty()) KrajevneDatoteke.koren(this) else KrajevneDatoteke.vsebina(this, zbirka)
            } catch (_: Throwable) { emptyList() }
            runOnUiThread {
                if (isFinishing || !krajevni || krajevnaZbirka != zbirka) return@runOnUiThread
                vnosi = novi
                prilagojevalnik.notifyDataSetChanged()
                if (novi.isEmpty()) pokaziSporocilo(getString(R.string.os_krajevno_prazno)) else {
                    skrijSporocilo(); seznam.requestFocus(); seznam.setSelection(0)
                }
            }
        }, "safeer-os-krajevne").start()
    }

    override fun onRequestPermissionsResult(zahteva: Int, dovoljenja: Array<out String>, izidi: IntArray) {
        super.onRequestPermissionsResult(zahteva, dovoljenja, izidi)
        if (zahteva != ZAHTEVA_DOVOLJENJA) return
        if (KrajevneDatoteke.imamoDovoljenje(this)) naloziKrajevno("")
        else pokaziSporocilo(getString(R.string.os_krajevno_brez_dovoljenja))
    }

    private fun pokaziRacunalnike(r: List<LinkOdjemalec.Naprava>) {
        izbiramRacunalnik = true
        krajevni = false
        racunalnik = null
        pot.clear()
        nadnaslov.text = getString(R.string.os_datoteke)
        naslov.text = getString(R.string.os_datoteke_viri)
        racunalnikZnacka.visibility = View.GONE
        // Ta televizor je vedno prvi vir; racunalniki s Safeer Controlom so za njim.
        vnosi = listOf(Vnos(KrajevneDatoteke.KOREN, getString(R.string.os_krajevno_ta_tv), "tv", -1, "")) +
            r.map { Vnos(it.id, it.ime.ifBlank { it.id }, "computer", -1, "") }
        prilagojevalnik.notifyDataSetChanged()
        if (r.isEmpty()) pokaziSporocilo(getString(
            if (!link.povezan) R.string.os_datoteke_ni_linka else R.string.os_datoteke_ni_racunalnika))
        else skrijSporocilo()
        seznam.requestFocus(); seznam.setSelection(0)
    }

    private fun odpriRacunalnik(r: LinkOdjemalec.Naprava) {
        izbiramRacunalnik = false
        krajevni = false
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
        nadnaslov.text = r.ime.ifBlank { getString(R.string.os_datoteke) }
        naslov.text = if (pot.isEmpty()) getString(R.string.os_datoteke_koren) else pot.joinToString(" / ") { it.ime }
        pokaziSporocilo(getString(R.string.os_datoteke_nalagam))
        link.ukaz(r.id, "files.list", JSONObject().put("folder", oznaka), 12_000, LinkOdjemalec.Odgovor { izid, napaka ->
            if (isFinishing || racunalnik?.id != r.id) return@Odgovor
            nalagam = false
            if (izid == null) {
                pokaziSporocilo(getString(if (napaka == "ni_povezave") R.string.os_datoteke_ni_povezave else R.string.os_datoteke_napaka, napaka))
                return@Odgovor
            }
            if (!izid.optBoolean("ok")) {
                pokaziSporocilo(getString(R.string.os_datoteke_napaka, izid.optString("message")))
                return@Odgovor
            }
            val podatki = izid.optJSONObject("data") ?: JSONObject()
            podatki.optJSONObject("server")?.let {
                streznik = Streznik(it.optString("base_url").trimEnd('/'), it.optString("fp"), it.optString("token"))
            }
            if (!podatki.optBoolean("shared", true)) {
                pokaziSporocilo(getString(R.string.os_datoteke_prazno_racunalnik, r.ime.ifBlank { r.id }))
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
            if (nov.isEmpty()) pokaziSporocilo(getString(R.string.os_datoteke_prazna_mapa)) else {
                skrijSporocilo()
                seznam.requestFocus(); seznam.setSelection(0)
            }
        })
    }

    private fun izberi(i: Int) {
        val v = vnosi.getOrNull(i) ?: return
        if (izbiramRacunalnik) {
            if (v.vrsta == "tv") odpriKrajevno()
            else link.racunalnikiZDatotekami().firstOrNull { it.id == v.id }?.let { odpriRacunalnik(it) }
            return
        }
        when (v.vrsta) {
            "folder" -> { pot.add(Raven(v.id, v.ime)); if (krajevni) naloziKrajevno(v.id) else nalozi(v.id) }
            "video", "audio" -> predvajaj(v)
            "image" -> pokaziSliko(v)
            else -> Toast.makeText(this, getString(R.string.os_datoteke_neznana_vrsta), Toast.LENGTH_SHORT).show()
        }
    }

    private fun predvajaj(v: Vnos) {
        val namera = Intent(this, PredvajalnikActivity::class.java)
            .putExtra("ime", v.ime).putExtra("mime", v.mime).putExtra("zvok", v.vrsta == "audio")
        if (krajevni) {
            namera.putExtra("url", v.id).putExtra("lokalno", true)
        } else {
            val s = streznik ?: return
            namera.putExtra("url", s.url(v.id))
            val b = Bundle(); s.vBundle(b); namera.putExtras(b)
        }
        startActivity(namera)
    }

    private fun pokaziSliko(v: Vnos) {
        val slike = vnosi.filter { it.vrsta == "image" }
        val s = if (krajevni) null else (streznik ?: return)
        val namera = Intent(this, SlikaActivity::class.java)
            .putStringArrayListExtra("urli", ArrayList(slike.map { if (s == null) it.id else s.url(it.id) }))
            .putStringArrayListExtra("imena", ArrayList(slike.map { it.ime }))
            .putExtra("zacetek", slike.indexOfFirst { it.id == v.id }.coerceAtLeast(0))
            .putExtra("lokalno", krajevni)
        if (s != null) { val b = Bundle(); s.vBundle(b); namera.putExtras(b) }
        startActivity(namera)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val viri = link.racunalnikiZDatotekami()
            when {
                pot.isNotEmpty() -> {
                    pot.removeAt(pot.size - 1)
                    val oznaka = pot.lastOrNull()?.oznaka ?: ""
                    if (krajevni) naloziKrajevno(oznaka) else nalozi(oznaka)
                    return true
                }
                // Na vrhu vira: nazaj na seznam virov, kadar je kaj za izbirati (racunalniki + ta televizor).
                (krajevni || racunalnik != null) && viri.isNotEmpty() && !link.jeKrajevni() -> { pokaziRacunalnike(viri); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun pokaziSporocilo(b: String) { sporocilo.text = b; sporocilo.visibility = View.VISIBLE }
    private fun skrijSporocilo() { sporocilo.visibility = View.GONE }

    // ------------------------------------------------------------------ Link

    override fun naStanje(povezan: Boolean, sporocilo: String) {
        if (!povezan && racunalnik == null && !krajevni) {
            pokaziSporocilo(getString(if (sporocilo == "ni_linka" || sporocilo == "krajevni") R.string.os_datoteke_ni_linka else R.string.os_datoteke_ni_povezave))
        }
    }

    override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) {
        if (krajevni) return   // uporabnik gleda datoteke televizorja; ne prekinjamo ga
        val z = link.racunalnikiZDatotekami()
        val r = racunalnik
        if (r == null) {
            // Racunalnik se je pravkar prikljucil (ali odsel): seznam brez ponovnega odpiranja zaslona.
            if (izbiramRacunalnik || vnosi.isEmpty()) {
                // Racunalnik, ki ga je uporabnik ze izbral (kartica naprave na domacem zaslonu),
                // odpremo takoj; sicer ostane izbira pri njem - tudi kadar je racunalnik en sam.
                val zeleni = intent.getStringExtra(EXTRA_RACUNALNIK)
                val izbran = if (zeleni != null) z.firstOrNull { it.id == zeleni } else null
                if (izbran != null) odpriRacunalnik(izbran) else pokaziRacunalnike(z)
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
            val v = obstojeci ?: LayoutInflater.from(this@DatotekeActivity).inflate(R.layout.os_vrstica_datoteka, stars, false)
            val vnos = vnosi[i]
            v.findViewById<ImageView>(R.id.ikona).setImageResource(ikona(vnos.vrsta))
            v.findViewById<TextView>(R.id.ime).text = vnos.ime
            v.findViewById<TextView>(R.id.opis).text = opis(this@DatotekeActivity, vnos)
            return v
        }
    }

    companion object {
        const val EXTRA_RACUNALNIK = "racunalnik"
        const val EXTRA_KRAJEVNO = "krajevno"
        private const val ZAHTEVA_DOVOLJENJA = 7321

        fun ikona(vrsta: String): Int = when (vrsta) {
            "folder" -> R.drawable.os_ikona_mapa
            "video" -> R.drawable.os_ikona_video
            "audio" -> R.drawable.os_ikona_glasba
            "image" -> R.drawable.os_ikona_slika
            "computer" -> R.drawable.os_ikona_racunalnik
            "tv" -> R.drawable.os_ikona_naprava
            else -> R.drawable.os_ikona_datoteka
        }

        fun opis(c: Context, v: Vnos): String {
            val vrsta = when (v.vrsta) {
                "folder" -> c.getString(R.string.os_vrsta_mapa)
                "video" -> c.getString(R.string.os_vrsta_video)
                "audio" -> c.getString(R.string.os_vrsta_audio)
                "image" -> c.getString(R.string.os_vrsta_slika)
                "computer" -> "Safeer Control"
                "tv" -> c.getString(R.string.os_krajevno_ta_tv_opis)
                else -> c.getString(R.string.os_vrsta_datoteka)
            }
            if (v.pod.isNotEmpty()) return v.pod
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
