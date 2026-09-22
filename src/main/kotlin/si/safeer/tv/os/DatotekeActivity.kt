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
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
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
class DatotekeActivity : OsActivity(), LinkOdjemalec.Poslusalec {

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
    private lateinit var namigDrzi: TextView

    private val link by lazy { LinkUpravitelj.pridobi(this) }
    private val prilagojevalnik = Prilagojevalnik()

    private var racunalnik: LinkOdjemalec.Naprava? = null
    private val pot = ArrayList<Raven>()
    private var streznik: Streznik? = null
    /** Vse vrstice trenutne ravni; [vnosi] so tiste, ki ustrezajo iskanju po imenu. */
    private var vsi: List<Vnos> = emptyList()
    private var vidni: List<Vnos> = emptyList()
    private var iskano = ""
    private lateinit var iskanje: EditText
    /**
     * Seznam, ki ga zaslon kaze. Nov seznam (druga mapa, drug vir) pocisti iskanje: iskalni niz je
     * veljal za prejsnjo mapo, v novi bi skril vse in uporabnik ne bi vedel zakaj.
     */
    private var vnosi: List<Vnos>
        get() = vidni
        set(v) {
            vsi = v
            if (iskano.isNotEmpty() && ::iskanje.isInitialized) { iskano = ""; iskanje.setText("") }
            vidni = filtrirano()
        }
    private var nalagam = false
    private var izbiramRacunalnik = false
    /** Krajevni vir: datoteke tega televizorja (MediaStore), brez Safeer Linka. */
    private var krajevni = false
    /** Zaslon je odprt zato, da uporabnik izbere sliko (za ozadje); klik na sliko jo vrne nazaj. */
    private var izbiramSliko = false
    private var krajevnaZbirka = ""
    /** Racunalnik dovoli urejanje datotek te mape (`edit` v odgovoru `files.list`; Safeer Control 2.1.0+). */
    private var urejanje = false
    /** Po vrnitvi iz pregledovalnika slik je treba seznam osveziti, ko je Link spet povezan. */
    private var cakamOsvezitev = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.os_activity_datoteke)
        seznam = findViewById(R.id.seznam)
        naslov = findViewById(R.id.naslov)
        nadnaslov = findViewById(R.id.nadnaslov)
        racunalnikZnacka = findViewById(R.id.racunalnik)
        sporocilo = findViewById(R.id.sporocilo)
        namigDrzi = findViewById(R.id.namigDrzi)
        namigDrzi.text = getString(R.string.os_datoteke_pomoc_drzi)
        seznam.adapter = prilagojevalnik
        // Iskanje po imenu: v domaci mapi je hitro sto map, puscica dol do prave pa je dolga pot.
        iskanje = findViewById(R.id.iskanje)
        iskanje.showSoftInputOnFocus = false
        iskanje.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun afterTextChanged(s: Editable?) {
                iskano = poenostavi(s?.toString().orEmpty().trim())
                vidni = filtrirano()
                prilagojevalnik.notifyDataSetChanged()
            }
        })
        iskanje.setOnKeyListener { _, koda, dogodek ->
            if (dogodek.action == KeyEvent.ACTION_DOWN &&
                (koda == KeyEvent.KEYCODE_DPAD_CENTER || koda == KeyEvent.KEYCODE_ENTER)) {
                odpriTipkovnico(); true
            } else false
        }
        iskanje.setOnClickListener { odpriTipkovnico() }
        // Tipka za iskanje na tipkovnici: tipkovnica se zapre, izbira skoci na prvi zadetek.
        iskanje.setOnEditorActionListener { _, _, _ ->
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(iskanje.windowToken, 0)
            if (vidni.isNotEmpty()) { seznam.requestFocus(); seznam.setSelection(0) }
            true
        }
        seznam.setOnItemClickListener { _, _, i, _ -> izberi(i) }
        seznam.setOnItemLongClickListener { _, _, i, _ -> moznosti(i); true }
        izbiramSliko = intent.getBooleanExtra(EXTRA_IZBERI_SLIKO, false)
        // Napis v sporocilu je nalaganje takoj prepisalo, zato povemo z obvestilom: uporabnik mora
        // vedeti, zakaj se mu je odprl seznam datotek.
        if (izbiramSliko) Toast.makeText(this, getString(R.string.os_izberi_sliko), Toast.LENGTH_LONG).show()
    }

    override fun onStart() {
        super.onStart()
        Ozadje.uporabi(this, findViewById(R.id.koren))
        link.dodaj(this)
        if (racunalnik == null) zacni()
    }

    override fun onResume() {
        super.onResume()
        // Pregledovalnik slik je datoteko izbrisal ali preimenoval: seznam mape osvezimo - takoj,
        // ce je Link ze povezan, sicer ko se povezava po vrnitvi vzpostavi (naStanje).
        if (osveziPoVrnitvi) { osveziPoVrnitvi = false; cakamOsvezitev = true }
        osveziCeTreba()
    }

    private fun osveziCeTreba() {
        if (!cakamOsvezitev || racunalnik == null || krajevni || !link.povezan) return
        cakamOsvezitev = false
        nalozi(pot.lastOrNull()?.oznaka ?: "")
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
        namigDrzi.visibility = View.GONE
        racunalnik = null
        streznik = null
        pot.clear()
        krajevnaZbirka = ""
        // Znacka bi ponovila nadnaslov ("Ta televizor"), zato je ne kazemo.
        racunalnikZnacka.visibility = View.GONE
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
        namigDrzi.visibility = View.GONE
        racunalnik = null
        pot.clear()
        nadnaslov.text = getString(R.string.os_datoteke)
        naslov.text = getString(R.string.os_datoteke_viri)
        racunalnikZnacka.visibility = View.GONE
        // Ta naprava je vedno prvi vir; racunalniki s Safeer Controlom so za njim.
        vnosi = listOf(Vnos(KrajevneDatoteke.KOREN, getString(R.string.os_krajevno_ta_tv), vrstaTeNaprave(), -1, "")) +
            r.map { Vnos(it.id, lepoIme(it.ime).ifBlank { it.id }, vrstaNaprave(it), -1, "",
                pod = if (vrstaNaprave(it) == "tv") getString(R.string.os_ur_tv_vir_opis) else "") }
        prilagojevalnik.notifyDataSetChanged()
        if (r.isEmpty()) pokaziSporocilo(getString(
            if (!link.povezan) R.string.os_datoteke_ni_linka else R.string.os_datoteke_ni_racunalnika))
        else skrijSporocilo()
        seznam.requestFocus(); seznam.setSelection(0)
    }

    /** Vrsta TE naprave za ikono prvega vira: telefon, tablica ali televizor. */
    private fun vrstaTeNaprave(): String = si.safeer.tv.cast.HubKrmilnik.platforma(this)

    /** Vrsta vira za ikono in podnapis: telefon, tablica ali racunalnik (Safeer Control). */
    private fun vrstaNaprave(n: LinkOdjemalec.Naprava): String =
        when (n.platforma) { "phone" -> "phone"; "tablet" -> "tablet"; "tv" -> "tv"; else -> "computer" }

    private fun odpriRacunalnik(r: LinkOdjemalec.Naprava) {
        izbiramRacunalnik = false
        krajevni = false
        // Datoteke racunalnika zna odpreti tudi racunalnik sam (namizje); telefon in tablica tega nimata.
        namigDrzi.visibility = if (izbiramSliko || !r.zmoznosti.contains("desktop")) View.GONE else View.VISIBLE
        racunalnik = r
        pot.clear()
        streznik = null
        // Ime racunalnika je ze v nadnaslovu; znacka bi ga le ponovila.
        racunalnikZnacka.visibility = View.GONE
        nalozi("")
    }

    private fun nalozi(oznaka: String) {
        val r = racunalnik ?: return
        nalagam = true
        vnosi = emptyList()
        prilagojevalnik.notifyDataSetChanged()
        nadnaslov.text = lepoIme(r.ime).ifBlank { getString(R.string.os_datoteke) }
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
            urejanje = podatki.optBoolean("edit", false) && streznik != null
            if (!izbiramSliko) {
                val namizje = r.zmoznosti.contains("desktop")
                namigDrzi.text = getString(when {
                    urejanje && jeNaprava() -> R.string.os_ur_pomoc_drzi_naprava
                    urejanje -> R.string.os_ur_pomoc_drzi
                    else -> R.string.os_datoteke_pomoc_drzi
                })
                namigDrzi.visibility = if (urejanje || namizje) View.VISIBLE else View.GONE
            }
            if (!podatki.optBoolean("shared", true)) {
                // Telefon in tablica povesta, zakaj ne delita: brez dovoljenja za medije ali izklopljeno.
                val ime = lepoIme(r.ime).ifBlank { r.id }
                pokaziSporocilo(getString(when (podatki.optString("reason")) {
                    "permission" -> R.string.os_datoteke_naprava_dovoljenje
                    "off" -> R.string.os_datoteke_naprava_izklop
                    else -> R.string.os_datoteke_prazno_racunalnik
                }, ime))
                return@Odgovor
            }
            val polje = podatki.optJSONArray("items")
            val nov = ArrayList<Vnos>()
            if (polje != null) for (i in 0 until polje.length()) {
                val v = polje.optJSONObject(i) ?: continue
                val id = v.optString("id")
                // Zbirke telefona in tablice (media:video ...) poimenujemo v jeziku televizorja, ne naprave.
                val ime = when (id) {
                    "media:video" -> getString(R.string.os_krajevno_videi)
                    "media:audio" -> getString(R.string.os_krajevno_glasba)
                    "media:image" -> getString(R.string.os_krajevno_slike)
                    else -> v.optString("name")
                }
                nov.add(Vnos(id, ime, v.optString("type", "file"), v.optLong("size", -1), v.optString("mime")))
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
            if (v.id == KrajevneDatoteke.KOREN) odpriKrajevno()
            else link.racunalnikiZDatotekami().firstOrNull { it.id == v.id }?.let { odpriRacunalnik(it) }
            return
        }
        when (v.vrsta) {
            "folder" -> { pot.add(Raven(v.id, v.ime)); if (krajevni) naloziKrajevno(v.id) else nalozi(v.id) }
            "image" -> if (izbiramSliko) vrniSliko(v) else pokaziSliko(v)
            "video", "audio" -> if (izbiramSliko)
                Toast.makeText(this, getString(R.string.os_izberi_sliko), Toast.LENGTH_SHORT).show()
                else predvajaj(v)
            else -> odpriDrugo(v)
        }
    }

    /** Izbrano sliko vrnemo zaslonu, ki nas je odprl (Videz); prenos naredi on sam. */
    private fun vrniSliko(v: Vnos) {
        val namera = Intent().putExtra("lokalno", krajevni)
        if (krajevni) namera.putExtra("url", v.id)
        else {
            val s = streznik ?: return
            namera.putExtra("url", s.url(v.id))
            val b = Bundle(); s.vBundle(b); namera.putExtras(b)
        }
        setResult(RESULT_OK, namera)
        finish()
    }

    /**
     * Zadrzan OK na datoteki racunalnika: video, glasbo ali sliko zna televizor predvajati sam,
     * lahko pa jo odpre racunalnik s svojim programom - in kadar deli zaslon, to takoj vidimo tudi
     * tu. Kratek OK ostane, kar je bil: predvajaj na televizorju.
     */
    private fun moznosti(i: Int) {
        val v = vnosi.getOrNull(i) ?: return
        if (izbiramSliko || izbiramRacunalnik || krajevni) return
        if (v.vrsta == "computer" || v.vrsta == "phone" || v.vrsta == "tablet" || v.vrsta == "tv") return
        val r = racunalnik ?: return
        val zaslon = link.naprave.any { it.id == r.id && it.zmoznosti.contains("desktop") }
        val mapa = v.vrsta == "folder"
        // Dejanja: odpiranje na racunalniku (ne za mape), potem urejanje, ce ga racunalnik dovoli.
        val dejanja = ArrayList<Pair<String, () -> Unit>>()
        // Telefon, tablica in televizor znajo medije le nasteti in postreci (files.list);
        // ukaza files.open nimajo, zato odpiranja pri njih sploh ne ponudimo.
        if (!mapa && !jeNaprava()) {
            dejanja.add(getString(R.string.os_odpri_na_racunalniku) to { odpriNaRacunalniku(v, false) })
            if (zaslon) dejanja.add(getString(R.string.os_odpri_in_poglej) to { odpriNaRacunalniku(v, true) })
        }
        if (urejanje) {
            if (v.vrsta == "image") {
                dejanja.add(getString(R.string.os_ur_zavrti_levo) to { zavrti(v, false) })
                dejanja.add(getString(R.string.os_ur_zavrti_desno) to { zavrti(v, true) })
            }
            // Telefon in tablica hranita medije v zbirki (MediaStore): tam ni map, ime pa je del
            // zapisa. Preimenovanja in premika zato ne ponujamo - bolje nic kot moznost, ki pade.
            if (!jeNaprava()) {
                dejanja.add(getString(R.string.os_ur_preimenuj) to { preimenujDatoteko(v) })
                dejanja.add(getString(R.string.os_ur_premakni) to { premakniDatoteko(v) })
            }
            dejanja.add(getString(R.string.os_ur_izbrisi) to { potrdiBrisanje(v) })
        }
        if (dejanja.isEmpty()) return
        val okno = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(v.ime)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, k -> dejanja.getOrNull(k)?.second?.invoke() }
            .setNegativeButton(getString(R.string.os_preklici), null)
        Kontroler.pokazi(okno.show())
    }

    // ------------------------------------------------------------------ urejanje datotek racunalnika

    /** Ali datoteke streze naprava (telefon, tablica, televizor) in ne racunalnik? */
    private fun jeNaprava(): Boolean {
        val id = racunalnik?.id ?: return false
        val n = link.naprave.firstOrNull { it.id == id } ?: return false
        return n.platforma == "phone" || n.platforma == "tablet" || n.platforma == "tv"
    }

    private fun zavrti(v: Vnos, vDesno: Boolean) {
        val s = streznik ?: return
        UrejanjeDatotek.zavrti(s, v.id, vDesno) { izid ->
            if (isFinishing) return@zavrti
            javiIzid(izid, R.string.os_ur_zavrteno)
        }
    }

    private fun preimenujDatoteko(v: Vnos) {
        val s = streznik ?: return
        // Polje kaze ime brez koncnice; koncnica se ob shranjevanju doda sama (ce je uporabnik ne vpise).
        val (deblo, koncnica) = razdeliIme(v.ime, v.vrsta == "folder")
        val vnos = EditText(this).apply {
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.os_ur_ime_namig)
            setText(deblo)
            setSelection(deblo.length)
            setPadding(40, 30, 40, 30)
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_ur_preimenuj))
            .setMessage(if (koncnica.isEmpty()) null else getString(R.string.os_ur_koncnica_ostane, koncnica))
            .setView(vnos)
            .setPositiveButton(getString(R.string.os_naprave_shrani)) { _, _ ->
                val ime = zdruziIme(vnos.text?.toString().orEmpty(), koncnica)
                if (ime.isEmpty() || ime == v.ime) return@setPositiveButton
                UrejanjeDatotek.preimenuj(s, v.id, ime) { izid ->
                    if (isFinishing) return@preimenuj
                    if (javiIzid(izid, R.string.os_ur_preimenovano)) nalozi(pot.lastOrNull()?.oznaka ?: "")
                }
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    /** Cilj premika: podmape te mape in nadrejena mapa (znotraj deljene mape). */
    private fun premakniDatoteko(v: Vnos) {
        val s = streznik ?: return
        val cilji = ArrayList<Pair<String, String>>()
        if (pot.size >= 2) cilji.add(getString(R.string.os_ur_nadrejena) to pot[pot.size - 2].oznaka)
        for (m in vsi) if (m.vrsta == "folder" && m.id != v.id) cilji.add(m.ime to m.id)
        if (cilji.isEmpty()) {
            Toast.makeText(this, getString(R.string.os_ur_napaka, getString(R.string.os_ur_n_ni_dovoljeno)), Toast.LENGTH_SHORT).show()
            return
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_ur_kam, v.ime))
            .setItems(cilji.map { it.first }.toTypedArray()) { _, k ->
                val cilj = cilji.getOrNull(k)?.second ?: return@setItems
                UrejanjeDatotek.premakni(s, v.id, cilj) { izid ->
                    if (isFinishing) return@premakni
                    if (javiIzid(izid, R.string.os_ur_premaknjeno)) nalozi(pot.lastOrNull()?.oznaka ?: "")
                }
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    private fun potrdiBrisanje(v: Vnos) {
        val s = streznik ?: return
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_ur_izbrisi_vprasanje, v.ime))
            .setMessage(getString(if (jeNaprava()) R.string.os_ur_izbrisi_opis_naprava else R.string.os_ur_izbrisi_opis))
            .setPositiveButton(getString(R.string.os_ur_izbrisi)) { _, _ ->
                UrejanjeDatotek.izbrisi(s, v.id) { izid ->
                    if (isFinishing) return@izbrisi
                    if (javiIzid(izid, R.string.os_ur_izbrisano)) nalozi(pot.lastOrNull()?.oznaka ?: "")
                }
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    /** Obvestilo o izidu; vrne true ob uspehu. */
    private fun javiIzid(izid: UrejanjeDatotek.Izid, uspeh: Int): Boolean {
        Toast.makeText(this, if (izid.ok) getString(uspeh) else getString(R.string.os_ur_napaka, opisNapake(this, izid.napaka)),
            if (izid.ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        // Naprava potrditev pokaze pri sebi in nam izida ne javi; seznam osvezimo ob vrnitvi.
        if (izid.napaka == "potrebna_potrditev") osveziPoVrnitvi = true
        return izid.ok
    }

    /**
     * Datoteka, ki ni video, glasba ali slika. Besedilo (opombe, seznami, dnevniki) pokazemo kar
     * tu - televizor zna pokazati besedilo. Vsega drugega (docx, pdf, arhiv) televizor ne zna, zna
     * pa racunalnik: odpre jo on, in kadar deli zaslon, jo takoj vidimo tudi na televizorju. Brez
     * vprasanj in opozoril - uporabnik je datoteko odprl, ne prosil za razlago, zakaj ne gre.
     */
    private fun odpriDrugo(v: Vnos) {
        if (izbiramSliko) {
            Toast.makeText(this, getString(R.string.os_izberi_sliko), Toast.LENGTH_SHORT).show()
            return
        }
        // Kar zna televizor, odpre televizor: besedilo tu, videe, glasbo in slike pa ze prej.
        if (BesediloActivity.jeBesedilo(v.ime, v.mime)) { pokaziBesedilo(v); return }
        if (krajevni) {
            Toast.makeText(this, getString(R.string.os_datoteke_neznana_vrsta), Toast.LENGTH_SHORT).show()
            return
        }
        // Vsega drugega (dokumenti, preglednice, arhivi) televizor ne zna - zna pa racunalnik.
        // Brez vprasanja: datoteko odpre on, in kadar deli zaslon, jo takoj vidimo tudi tu.
        val r = racunalnik ?: return
        val zaslon = link.naprave.any { it.id == r.id && it.zmoznosti.contains("desktop") }
        odpriNaRacunalniku(v, zaslon)
    }

    private fun pokaziBesedilo(v: Vnos) {
        zapomniSi(v, Nadaljuj.BESEDILO)
        val namera = Intent(this, BesediloActivity::class.java)
            .putExtra("ime", v.ime).putExtra("lokalno", krajevni)
        if (krajevni) namera.putExtra("url", v.id)
        else {
            val s = streznik ?: return
            namera.putExtra("url", s.url(v.id))
            val b = Bundle(); s.vBundle(b); namera.putExtras(b)
        }
        startActivity(namera)
    }

    /** Racunalnik odpre datoteko s svojim programom; [inPoglej] zraven odpre se zaslon racunalnika. */
    private fun odpriNaRacunalniku(v: Vnos, inPoglej: Boolean) {
        val r = racunalnik ?: return
        Toast.makeText(this, getString(R.string.os_odpri_posiljam), Toast.LENGTH_SHORT).show()
        link.ukaz(r.id, "files.open", JSONObject().put("id", v.id), 10_000, LinkOdjemalec.Odgovor { izid, napaka ->
            if (isFinishing) return@Odgovor
            val uspeh = izid?.optBoolean("ok") == true
            if (!uspeh) {
                Toast.makeText(this, getString(R.string.os_odpri_racunalnik_napaka,
                    izid?.optString("message").orEmpty().ifBlank { napaka.orEmpty() }), Toast.LENGTH_LONG).show()
                return@Odgovor
            }
            Toast.makeText(this, getString(R.string.os_odpri_poslano), Toast.LENGTH_SHORT).show()
            if (inPoglej) startActivity(Intent(this, ZaslonActivity::class.java)
                .putExtra(DatotekeActivity.EXTRA_RACUNALNIK, r.id))
        })
    }

    /**
     * Glasba in video z naprave gresta skozi skupni predvajalnik Safeer OS ([GlasbaStoritev]): igrata
     * tudi v ozadju, pri glasbi se v vrsto uvrstijo vse skladbe v tej mapi (naprej/nazaj na daljincu).
     */
    private fun predvajaj(v: Vnos) {
        zapomniSi(v, if (v.vrsta == "audio") Nadaljuj.GLASBA else Nadaljuj.VIDEO)
        val s = if (krajevni) null else (streznik ?: return)
        val izbor = if (v.vrsta == "audio") vnosi.filter { it.vrsta == "audio" } else listOf(v)
        val seznam = izbor.map { e ->
            Jamendo.Skladba(e.id, e.ime, "", "", if (s == null) e.id else s.url(e.id), "", video = e.vrsta != "audio", mime = e.mime)
        }
        GlasbaStoritev.predvajaj(this, seznam, izbor.indexOf(v).coerceAtLeast(0), s)
        startActivity(Intent(this, PredvajanjeActivity::class.java))
    }

    private fun pokaziSliko(v: Vnos) {
        zapomniSi(v, Nadaljuj.SLIKA)
        val slike = vnosi.filter { it.vrsta == "image" }
        val s = if (krajevni) null else (streznik ?: return)
        val namera = Intent(this, SlikaActivity::class.java)
            .putStringArrayListExtra("urli", ArrayList(slike.map { if (s == null) it.id else s.url(it.id) }))
            .putStringArrayListExtra("imena", ArrayList(slike.map { it.ime }))
            .putStringArrayListExtra("oznake", ArrayList(slike.map { it.id }))
            .putExtra("zacetek", slike.indexOfFirst { it.id == v.id }.coerceAtLeast(0))
            .putExtra("lokalno", krajevni)
            .putExtra("urejanje", urejanje && !krajevni)
            .putExtra("naprava", jeNaprava())
        if (s != null) { val b = Bundle(); s.vBundle(b); namera.putExtras(b) }
        startActivity(namera)
    }

    /**
      * Zapomnimo si, kaj je uporabnik odprl, da mu domaci zaslon to ponudi v vrsti Nadaljuj.
      * Shranimo samo oznako datoteke in id racunalnika - zetona in naslova streznika ne, ker
      * velja samo, dokler seja tece.
      */
    private fun zapomniSi(v: Vnos, vrsta: String) {
        Nadaljuj.zapisi(this, Nadaljuj.Vnos(vrsta = vrsta, ime = v.ime,
            racunalnik = if (krajevni) "" else racunalnik?.id.orEmpty(),
            id = v.id, mime = v.mime, krajevno = krajevni))
    }

    private fun odpriTipkovnico() {
        iskanje.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(iskanje, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Sumnike poenostavimo, da "dok" najde "Dokumenti" in "cis" "Čiščenje". */
    private fun poenostavi(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")

    private fun filtrirano(): List<Vnos> =
        if (iskano.isEmpty()) vsi else vsi.filter { poenostavi(it.ime).contains(iskano) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Nazaj najprej pocisti iskanje: kdor je iskal, hoce nazaj celo mapo, ne raven vise.
            if (iskano.isNotEmpty()) {
                iskanje.setText("")
                seznam.requestFocus(); seznam.setSelection(0)
                return true
            }
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
        if (povezan) osveziCeTreba()
        if (!povezan && racunalnik == null && !krajevni) {
            pokaziSporocilo(getString(if (sporocilo == "ni_linka" || sporocilo == "krajevni") R.string.os_datoteke_ni_linka else R.string.os_datoteke_ni_povezave))
        }
    }

    override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) {
        if (krajevni) return   // uporabnik gleda datoteke televizorja; ne prekinjamo ga
        osveziCeTreba()
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
        const val EXTRA_IZBERI_SLIKO = "izberi_sliko"
        const val EXTRA_KRAJEVNO = "krajevno"
        private const val ZAHTEVA_DOVOLJENJA = 7321
        /** Pregledovalnik slik je datoteko spremenil: seznam se ob vrnitvi osvezi. */
        @Volatile var osveziPoVrnitvi = false

        /** "dopust.jpg" -> ("dopust", ".jpg"); mape in imena brez pike ostanejo cela. */
        fun razdeliIme(ime: String, mapa: Boolean): Pair<String, String> {
            val pika = if (mapa) -1 else ime.lastIndexOf('.')
            return if (pika > 0) ime.substring(0, pika) to ime.substring(pika) else ime to ""
        }

        /** Novo ime iz polja + stara koncnica; kdor vpise svojo (ime s piko), obdrzi svojo. */
        fun zdruziIme(vneseno: String, koncnica: String): String {
            val ime = vneseno.trim().trimEnd('.')
            if (ime.isEmpty()) return ""
            return if (koncnica.isNotEmpty() && !ime.contains('.')) ime + koncnica else ime
        }

        /** Kratka koda napake racunalnika ali naprave -> besedilo za uporabnika. */
        fun opisNapake(c: Context, koda: String): String = when (koda) {
            "obstaja" -> c.getString(R.string.os_ur_n_obstaja)
            "ni_dovoljeno", "napacno_ime", "ni_mape", "ni_datoteke" -> c.getString(R.string.os_ur_n_ni_dovoljeno)
            "ni_povezave" -> c.getString(R.string.os_ur_n_ni_povezave)
            "ni_pillow" -> c.getString(R.string.os_ur_n_ni_pillow)
            // Android ne dovoli tihega brisanja ali spreminjanja tujih fotografij: vprasanje se
            // pokaze na napravi, kjer slika je, in uporabnik ga potrdi tam.
            "potrebna_potrditev" -> c.getString(R.string.os_ur_n_potrdi_na_napravi)
            "zavrnjeno" -> c.getString(R.string.os_ur_n_zavrnjeno)
            "ni_mogoce_na_napravi" -> c.getString(R.string.os_ur_n_ni_na_napravi)
            "prevelika_slika" -> c.getString(R.string.os_ur_n_prevelika)
            else -> c.getString(R.string.os_ur_n_drugo, koda)
        }

        fun ikona(vrsta: String): Int = when (vrsta) {
            "folder" -> R.drawable.os_ikona_mapa
            "video" -> R.drawable.os_ikona_video
            "audio" -> R.drawable.os_ikona_glasba
            "image" -> R.drawable.os_ikona_slika
            "computer" -> R.drawable.os_ikona_racunalnik
            "phone" -> R.drawable.os_ikona_telefon
            "tablet" -> R.drawable.os_ikona_zaslon
            "tv" -> R.drawable.os_ikona_naprava
            else -> R.drawable.os_ikona_datoteka
        }

        /**
         * Ime racunalnika brez imena programa: "Safeer Control (dnevna-soba)" -> "dnevna-soba". Ime
         * programa je ze v podnapisu vrstice in ga ni treba brati dvakrat.
         */
        fun lepoIme(ime: String): String =
            Regex("^Safeer (?:Control|Link) \\((.+)\\)$").find(ime.trim())?.groupValues?.get(1) ?: ime

        fun opis(c: Context, v: Vnos): String {
            val vrsta = when (v.vrsta) {
                "folder" -> c.getString(R.string.os_vrsta_mapa)
                "video" -> c.getString(R.string.os_vrsta_video)
                "audio" -> c.getString(R.string.os_vrsta_audio)
                "image" -> c.getString(R.string.os_vrsta_slika)
                "computer" -> c.getString(R.string.os_datoteke_racunalnik_opis)
                "phone" -> c.getString(R.string.os_datoteke_telefon_opis)
                "tablet" -> c.getString(R.string.os_datoteke_tablica_opis)
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
