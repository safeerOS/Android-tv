package si.safeer.tv.os

import si.safeer.tv.R

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager

/**
 * Vse aplikacije tega televizorja ("Ostalo"). Na domacem zaslonu stojijo samo priljubljene; tu so
 * vse, urejene po abecedi, in tu si uporabnik priljubljene doloci - dolg pritisk doda ali odstrani.
 */
class AplikacijeTvActivity : OsActivity() {
    companion object {
        /** Odprto z »+ Dodaj«: klik doda na domaci zaslon. */
        const val IZBIRA_ZA_DOMOV = "si.safeer.tv.os.IZBIRA_ZA_DOMOV"
    }

    private lateinit var mreza: GridView
    private lateinit var sporocilo: TextView
    private val prilagojevalnik = Prilagojevalnik()
    private var vnosi: List<Aplikacije.Vnos> = emptyList()
    /** Vse aplikacije; [vnosi] so tiste, ki ustrezajo iskanju. */
    private var vse: List<Aplikacije.Vnos> = emptyList()
    private lateinit var iskanje: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.os_activity_programi)
        mreza = findViewById(R.id.mreza)
        sporocilo = findViewById(R.id.sporocilo)
        findViewById<TextView>(R.id.nadnaslov).text = getString(R.string.os_odsek_aplikacije)
        findViewById<TextView>(R.id.naslov).text = getString(R.string.os_aplikacije_vse)
        findViewById<TextView>(R.id.racunalnik).visibility = View.GONE
        findViewById<ImageView>(R.id.glavaIkona)?.setImageResource(R.drawable.os_ikona_mreza)
        mreza.adapter = prilagojevalnik
        // Iskanje po imenu: aplikacij na televizorju je hitro petdeset, abeceda pa ni iskanje.
        findViewById<View>(R.id.orodja).visibility = View.VISIBLE
        findViewById<View>(R.id.skupineDrsnik).visibility = View.GONE
        iskanje = findViewById(R.id.iskanje)
        iskanje.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun afterTextChanged(s: Editable?) { filtriraj() }
        })
        iskanje.setOnKeyListener { _, koda, dogodek ->
            if (dogodek.action == KeyEvent.ACTION_DOWN &&
                (koda == KeyEvent.KEYCODE_DPAD_CENTER || koda == KeyEvent.KEYCODE_ENTER)) {
                odpriTipkovnico(); true
            } else false
        }
        iskanje.setOnClickListener { odpriTipkovnico() }
        iskanje.showSoftInputOnFocus = false
        // Odprto z »+ Dodaj« na domacem zaslonu: klik doda aplikacijo na domaci zaslon in se vrne (ne odpre je).
        val izbira = intent.getBooleanExtra(IZBIRA_ZA_DOMOV, false)
        mreza.setOnItemClickListener { _, v, i, _ ->
            val a = vnosi.getOrNull(i) ?: return@setOnItemClickListener
            if (izbira) {
                if (!Priljubljene.je(this, a.paket)) Priljubljene.dodaj(this, a.paket)
                Toast.makeText(this, getString(R.string.os_aplikacije_dodana, a.ime), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Priljubljene.preveriZagonInZazeni(this, a.paket, a.ime) { zazeni(a, v) }
            }
        }
        mreza.setOnItemLongClickListener { _, _, i, _ ->
            vnosi.getOrNull(i)?.let { moznosti(it) }; true
        }
    }

    /**
     * Pregled zaslonov 5. 11. 2026: odstranjena (ali na novo namescena) aplikacija je ostala v mrezi, dokler zaslon ni bil
     * znova odprt. Kot domaci zaslon (PokrajinaActivity): prijava v onStart, odjava v onStop.
     */
    private val obSpremembiPaketov: () -> Unit = {
        android.os.Handler(android.os.Looper.getMainLooper()).post { if (!isFinishing && !isDestroyed) narisi() }
    }

    override fun onStart() {
        super.onStart()
        Ozadje.uporabi(this, findViewById(R.id.koren))
        Aplikacije.obSpremembiPaketov.add(obSpremembiPaketov)
        narisi()
    }

    override fun onStop() {
        Aplikacije.obSpremembiPaketov.remove(obSpremembiPaketov)
        super.onStop()
    }

    private fun odpriTipkovnico() {
        iskanje.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(iskanje, Tipkovnica.NAMIG)
    }

    /** Sumnike poenostavimo, da "cit" najde "Čitalnik". */
    private fun poenostavi(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")

    private fun filtriraj() {
        val iskano = poenostavi(iskanje.text?.toString().orEmpty().trim())
        vnosi = if (iskano.isEmpty()) vse else vse.filter { poenostavi(it.ime).contains(iskano) }
        prilagojevalnik.notifyDataSetChanged()
    }

    private fun narisi() {
        // Zunanji pregled 3. 11. 2026: Aplikacije.seznam (dvakrat paketni upravitelj + ikona vsake aplikacije) je tekel na
        // glavni niti ob vsakem onStart. Zdaj zadnji znani seznam takoj, svez v ozadju brez ikon.
        val znani = Aplikacije.zadnjiZnani(this)
        vse = znani ?: emptyList()
        Aplikacije.osveziVOzadju(this, znani, brezIkon = true) { svez ->
            if (isFinishing || isDestroyed) return@osveziVOzadju
            vse = svez; filtriraj(); pokaziSporocilo()
        }
        filtriraj()
        if (znani == null) { sporocilo.visibility = View.GONE; return }
        pokaziSporocilo()
    }

    private fun pokaziSporocilo() {
        if (vnosi.isEmpty()) {
            sporocilo.text = getString(R.string.os_aplikacije_prazno)
            sporocilo.visibility = View.VISIBLE
        } else {
            sporocilo.text = getString(R.string.os_aplikacije_namig)
            sporocilo.visibility = View.VISIBLE
            if (currentFocus == null) { mreza.requestFocus(); mreza.setSelection(0) }
        }
    }

    /** Odpiranje, ki ne utihne: ce ne gre, povemo zakaj in ponudimo ponovni poskus. */
    private fun zazeni(a: Aplikacije.Vnos, izvor: android.view.View? = null) {
        val namera = Intent(a.namera).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Zunanji pregled 3. 11. 2026: zagnana aplikacija dobi vec prostega pomnilnika - prosti WebView in ikone stran.
        try { LahkiWebViewPool.sprostiProstega() } catch (_: Throwable) { }
        maleIkone.clear()
        try {
            startActivity(namera, izPloscice(izvor))
        } catch (e: Throwable) {
            val razlog = when (e) {
                is android.content.ActivityNotFoundException -> getString(R.string.os_odpri_ni_aplikacije)
                is SecurityException -> getString(R.string.os_odpri_ni_dovoljenja)
                else -> e.message.orEmpty().ifBlank { e.javaClass.simpleName }
            }
            android.app.AlertDialog.Builder(this, si.safeer.tv.R.style.Os_Okno)
                .setTitle(a.ime)
                .setMessage(getString(R.string.os_odpri_napaka, razlog))
                .setPositiveButton(getString(R.string.os_poskusi_znova)) { _, _ -> zazeni(a) }
                .setNegativeButton(getString(R.string.os_preklici), null)
                .let { Kontroler.pokazi(it.show()) }
        }
    }

    /** Dolg pritisk: prikazi moznosti za dodajanje na zacetni zaslon ali odstranitev aplikacije. */
    private fun moznosti(a: Aplikacije.Vnos) {
        val bila = Priljubljene.je(this, a.paket)
        val dejanja = ArrayList<Pair<String, () -> Unit>>()
        dejanja.add(getString(if (bila) R.string.os_aplikacije_odstrani_domov else R.string.os_aplikacije_dodaj_domov) to {
            preklopiPriljubljeno(a)
        })

        val info = try { packageManager.getApplicationInfo(a.paket, 0) } catch (_: Throwable) { null }
        val jeSistemska = (info?.flags?.and(android.content.pm.ApplicationInfo.FLAG_SYSTEM) ?: 0) != 0
        val jePosodobljenaSistemska = (info?.flags?.and(android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) ?: 0) != 0

        if (AplikacijePravila.dejanjeOdstranitve(jeSistemska, jePosodobljenaSistemska) == AplikacijePravila.DejanjeOdstranitve.ONEMOGOCI) {
            dejanja.add(getString(R.string.os_aplikacije_onemogoci_app) to {
                odpriNastavitveAplikacije(a.paket)
            })
        } else {
            dejanja.add(getString(R.string.os_aplikacije_odstrani_app) to {
                odstraniAplikacijo(a.paket)
            })
        }

        android.app.AlertDialog.Builder(this, si.safeer.tv.R.style.Os_Okno)
            .setTitle(a.ime)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    private fun odstraniAplikacijo(paket: String) {
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = android.net.Uri.parse("package:$paket")
            }
            startActivity(intent)
        } catch (e: Throwable) {
            Toast.makeText(this, e.message.orEmpty(), Toast.LENGTH_SHORT).show()
        }
    }

    private fun odpriNastavitveAplikacije(paket: String) {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$paket")
            }
            startActivity(intent)
        } catch (e: Throwable) {
            Toast.makeText(this, e.message.orEmpty(), Toast.LENGTH_SHORT).show()
        }
    }

    private fun preklopiPriljubljeno(a: Aplikacije.Vnos) {
        val bila = Priljubljene.je(this, a.paket)
        if (bila) Priljubljene.odstrani(this, a.paket) else Priljubljene.dodaj(this, a.paket)
        prilagojevalnik.notifyDataSetChanged()
        Toast.makeText(this, getString(
            if (bila) R.string.os_aplikacije_odstranjena else R.string.os_aplikacije_dodana, a.ime),
            Toast.LENGTH_SHORT).show()
    }

    private val maleIkone = HashMap<String, android.graphics.drawable.Drawable>()
    private val cakajoceIkone = HashSet<String>()
    private val delavecIkon = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "SafeerAplikacijeTvIkone").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
    }

    private fun naloziIkono(a: Aplikacije.Vnos) {
        if (!cakajoceIkone.add(a.paket)) return
        val app = applicationContext
        val px = (48 * resources.displayMetrics.density).toInt()
        try {
            delavecIkon.execute {
                val d = try { Aplikacije.ikonaMala(app, a, px) } catch (_: Throwable) { null }
                runOnUiThread {
                    cakajoceIkone.remove(a.paket)
                    if (d == null || isDestroyed) return@runOnUiThread
                    maleIkone[a.paket] = d
                    mreza.findViewWithTag<ImageView>(a.paket)?.setImageDrawable(d)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { cakajoceIkone.remove(a.paket) }
    }

    override fun onDestroy() {
        delavecIkon.shutdownNow()
        super.onDestroy()
    }

    private inner class Prilagojevalnik : BaseAdapter() {
        override fun getCount(): Int = vnosi.size
        override fun getItem(i: Int): Any = vnosi[i]
        override fun getItemId(i: Int): Long = i.toLong()
        override fun getView(i: Int, star: View?, roditelj: ViewGroup): View {
            val v = star ?: LayoutInflater.from(this@AplikacijeTvActivity)
                .inflate(R.layout.os_kartica_program, roditelj, false)
            val a = vnosi[i]
            val ikona = v.findViewById<ImageView>(R.id.ikona)
            // Zunanji pregled 3. 11. 2026: na TV je Aplikacije.ikona dekodiral pasico 320x180 (ARGB_8888, do 64 slik) na
            // glavni niti za vsako ploscico - drsenje je zastajalo. Zdaj majhna kvadratna ikona (ikonaMala) v ozadju.
            ikona.tag = a.paket
            val lp = ikona.layoutParams
            lp.width = (48 * resources.displayMetrics.density).toInt()
            ikona.layoutParams = lp
            val risba = maleIkone[a.paket]
            if (risba != null) ikona.setImageDrawable(risba) else {
                ikona.setImageResource(R.drawable.os_ikona_mreza)
                naloziIkono(a)
            }
            // Zvezdica pove, da je aplikacija na domacem zaslonu; z dveh metrov je vidna takoj.
            v.findViewById<TextView>(R.id.ime).text =
                if (Priljubljene.je(this@AplikacijeTvActivity, a.paket)) "★ " + a.ime else a.ime
            return v
        }
    }
}
