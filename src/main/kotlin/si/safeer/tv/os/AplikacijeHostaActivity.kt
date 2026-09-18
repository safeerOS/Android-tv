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
 * Programi racunalnika na televizorju: kar ima uporabnik na svojem racunalniku, vidi tudi tu in
 * lahko zazene z daljincem. Seznam pride po Safeer Linku (ukaz `apps.list`, core/link_programi.py),
 * zagon gre nazaj po isti poti (`apps.launch`).
 *
 * Racunalnik ima hitro sto programov in abecedna mreza je bila zato seznam, po katerem se je bilo
 * treba prebijati. Zato sta tu **skupine** (igre, pisarna, splet, predstavnost, programiranje,
 * ucenje, orodja) - iste, kot jih uporabnik pozna iz menija svojega namizja - in **iskanje po
 * imenu**, ki seznam ozi ze med tipkanjem.
 *
 * Program se odpre na zaslonu racunalnika. Kadar racunalnik svoj zaslon deli, Safeer OS takoj
 * preklopi nanj, da uporabnik to, kar je odprl, tudi vidi in upravlja; kadar ga ne deli, to
 * posteno pove in nicesar ne obljublja.
 */
class AplikacijeHostaActivity : OsActivity(), LinkOdjemalec.Poslusalec {

    private class Program(val id: String, val ime: String, val opis: String, val skupina: String,
                          val ikona: android.graphics.drawable.Drawable?)

    private lateinit var mreza: GridView
    private lateinit var naslov: TextView
    private lateinit var nadnaslov: TextView
    private lateinit var znacka: TextView
    private lateinit var sporocilo: TextView
    private lateinit var iskanje: EditText
    private lateinit var skupineVrsta: LinearLayout
    private lateinit var skupineDrsnik: HorizontalScrollView

    private val link by lazy { LinkUpravitelj.pridobi(this) }
    private val prilagojevalnik = Prilagojevalnik()
    private var programi: List<Program> = emptyList()
    private var vidni: List<Program> = emptyList()
    private var izbranaSkupina = ""          // prazno = vse skupine
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
        iskanje = findViewById(R.id.iskanje)
        skupineVrsta = findViewById(R.id.skupine)
        skupineDrsnik = findViewById(R.id.skupineDrsnik)
        nadnaslov.text = getString(R.string.os_programi)
        naslov.text = getString(R.string.os_programi_naslov)
        mreza.adapter = prilagojevalnik
        mreza.setOnItemClickListener { _, _, i, _ -> vidni.getOrNull(i)?.let { zazeni(it) } }
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
            pokaziOrodja(false)
            pokaziSporocilo(getString(
                if (!link.povezan) R.string.os_programi_ni_povezave else R.string.os_programi_ni_racunalnika))
            return
        }
        racunalnik = r
        znacka.text = r.ime.ifBlank { r.id }
        znacka.visibility = View.VISIBLE
        programi = emptyList()
        vidni = emptyList()
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
                novi.add(Program(id, o.optString("name"), o.optString("comment"),
                    o.optString("group").ifBlank { DRUGO }, ikona(o.optString("icon_png"))))
            }
            val prvi = programi.isEmpty() && novi.isNotEmpty()
            programi = novi
            narisiSkupine()
            osveziSeznam()
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

    // ------------------------------------------------------------------ skupine in iskanje

    /** Skupine v vrstnem redu, kot jih uporabnik pricakuje; kar racunalnik doda novega, pade v "drugo". */
    private val vrstniRedSkupin = listOf("igre", "pisarna", "splet", "predstavnost",
                                         "programiranje", "ucenje", "orodja", DRUGO)

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

    /**
     * Gumbi skupin. Pokazemo samo tiste, ki na tem racunalniku res kaj vsebujejo - prazna skupina
     * bi obljubljala programe, ki jih ni. Ob vsaki je stevilo, da se takoj vidi, kje je kaj.
     */
    private fun narisiSkupine() {
        val stevila = LinkedHashMap<String, Int>()
        for (kljuc in vrstniRedSkupin) {
            val n = programi.count { it.skupina == kljuc }
            if (n > 0) stevila[kljuc] = n
        }
        // Ena sama skupina ni razvrstitev: takrat gumbov ne kazemo, ker ne povedo nicesar.
        val kaziSkupine = stevila.size > 1
        pokaziOrodja(programi.isNotEmpty())
        skupineDrsnik.visibility = if (kaziSkupine) View.VISIBLE else View.GONE
        if (!kaziSkupine) { izbranaSkupina = ""; return }
        if (izbranaSkupina.isNotEmpty() && !stevila.containsKey(izbranaSkupina)) izbranaSkupina = ""
        skupineVrsta.removeAllViews()
        skupineVrsta.addView(gumbSkupine("", getString(R.string.os_skupina_vse), programi.size))
        for ((kljuc, n) in stevila) skupineVrsta.addView(gumbSkupine(kljuc, imeSkupine(kljuc), n))
    }

    private fun gumbSkupine(kljuc: String, ime: String, koliko: Int): View {
        val t = TextView(this)
        t.text = getString(R.string.os_skupina_s_stevilom, ime, koliko)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        t.setTextColor(getColorStateList(R.color.os_skupina_besedilo))
        t.setBackgroundResource(R.drawable.os_skupina)
        t.gravity = Gravity.CENTER
        val vodoravno = (16 * resources.displayMetrics.density).toInt()
        val navpicno = (9 * resources.displayMetrics.density).toInt()
        t.setPadding(vodoravno, navpicno, vodoravno, navpicno)
        t.isFocusable = true
        t.isActivated = kljuc == izbranaSkupina
        val mere = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        mere.marginEnd = (8 * resources.displayMetrics.density).toInt()
        t.layoutParams = mere
        t.setOnClickListener {
            izbranaSkupina = kljuc
            for (i in 0 until skupineVrsta.childCount) {
                skupineVrsta.getChildAt(i).isActivated = skupineVrsta.getChildAt(i) === t
            }
            osveziSeznam()
        }
        return t
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
        val iskano = poenostavi(iskanje.text?.toString().orEmpty().trim())
        vidni = programi.filter { p ->
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
            val slika = BitmapFactory.decodeByteArray(bajti, 0, bajti.size) ?: return null
            SpletneAplikacije.ikonaIzSlike(this, slika)
        } catch (_: Throwable) { null }
    }

    /**
     * Zagon. Program se odpre na racunalniku; kadar racunalnik deli svoj zaslon, takoj preklopimo
     * nanj, da uporabnik to, kar je odprl, vidi in upravlja tu. Prej je ostal na seznamu in je
     * program tekel nekje, kjer ga ni videl - pri igri ali predvajalniku je bilo to neuporabno.
     */
    private fun zazeni(p: Program) {
        val r = racunalnik ?: return
        val zaslon = link.naprave.any { it.id == r.id && it.zmoznosti.contains("desktop") }
        link.ukaz(r.id, "apps.launch", JSONObject().put("app", p.id), 10_000, LinkOdjemalec.Odgovor { izid, napaka ->
            if (isFinishing) return@Odgovor
            if (izid?.optBoolean("ok") != true) {
                Toast.makeText(this, getString(R.string.os_programi_napaka,
                    izid?.optString("message").orEmpty().ifBlank { napaka }), Toast.LENGTH_LONG).show()
                return@Odgovor
            }
            if (zaslon) {
                Toast.makeText(this, getString(R.string.os_programi_odpiram, p.ime), Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, ZaslonActivity::class.java)
                    .putExtra(DatotekeActivity.EXTRA_RACUNALNIK, r.id))
            } else {
                Toast.makeText(this, getString(R.string.os_programi_zagnan, p.ime,
                    r.ime.ifBlank { r.id }), Toast.LENGTH_LONG).show()
            }
        })
    }

    /**
     * Nazaj najprej pocisti iskanje in skupino: uporabnik, ki je iskal, si najbrz zeli nazaj cel
     * seznam, ne ven z zaslona. Drugi Nazaj zapre zaslon kot obicajno.
     */
    override fun onBackPressed() {
        if (iskanje.text?.isNotEmpty() == true || izbranaSkupina.isNotEmpty()) {
            iskanje.setText("")
            izbranaSkupina = ""
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
        if (programi.isEmpty() && !nalagam) nalozi()
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
            if (p.ikona != null) ikona.setImageDrawable(p.ikona) else ikona.setImageResource(R.drawable.os_ikona_racunalnik)
            v.findViewById<TextView>(R.id.ime).text = p.ime
            return v
        }
    }

    companion object {
        /** Oznaka skupine za program, ki svoje kategorije nima (ista beseda kot v core/link_programi.py). */
        private const val DRUGO = "drugo"
    }
}
