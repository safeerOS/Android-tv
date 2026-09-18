package si.safeer.tv.os

import si.safeer.tv.R

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import okhttp3.Request
import java.util.concurrent.Executors

/**
 * Videz: ozadje domacega zaslona in zatemnitev nad njim.
 *
 * Ozadje je loceno od barvne teme, ker se okusa ne ujemata vedno: kdor ima rad mirno enobarvno
 * podlago, jo lahko ima tudi v barviti temi. Izbira se vidi takoj - zaslon sam je predogled, ne
 * majhna sliica v oknu. Uporabnik lahko doda svojo fotografijo (s tega televizorja ali z
 * racunalnika po Safeer Linku); kopijo shranimo v aplikacijo, obrezano na razmerje zaslona.
 */
class VidezActivity : Activity() {

    private lateinit var koren: View
    private lateinit var seznam: android.widget.ListView
    private lateinit var drsnik: SeekBar
    private lateinit var odstotek: TextView
    private val prilagojevalnik = Prilagojevalnik()
    private val ozadjeNit = Executors.newSingleThreadExecutor()
    private val glavna = Handler(Looper.getMainLooper())

    /** Kar je trenutno na zaslonu: izbira pod kazalcem (predogled) in tista, ki je shranjena. */
    private var predogled: Ozadje.Izbira = Ozadje.VSE[0]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.os_activity_videz)
        koren = findViewById(R.id.koren)
        seznam = findViewById(R.id.seznam)
        drsnik = findViewById(R.id.drsnik)
        odstotek = findViewById(R.id.odstotek)
        seznam.adapter = prilagojevalnik
        seznam.setOnItemClickListener { _, _, i, _ -> izberi(i) }
        seznam.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(s: AdapterView<*>?, v: View?, i: Int, id: Long) {
                Ozadje.VSE.getOrNull(i)?.let { pokazi(it) }
            }
            override fun onNothingSelected(s: AdapterView<*>?) { }
        }
        drsnik.max = Ozadje.NAJVECJA_ZATEMNITEV / Ozadje.KORAK_ZATEMNITVE
        drsnik.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, vrednost: Int, odUporabnika: Boolean) {
                val v = vrednost * Ozadje.KORAK_ZATEMNITVE
                odstotek.text = getString(R.string.os_odstotek, v)
                if (odUporabnika) { Ozadje.nastaviZatemnitev(this@VidezActivity, v); narisi() }
            }
            override fun onStartTrackingTouch(s: SeekBar?) { }
            override fun onStopTrackingTouch(s: SeekBar?) { }
        })
    }

    override fun onStart() {
        super.onStart()
        predogled = Ozadje.izbrana(this)
        drsnik.progress = Ozadje.zatemnitev(this) / Ozadje.KORAK_ZATEMNITVE
        odstotek.text = getString(R.string.os_odstotek, Ozadje.zatemnitev(this))
        narisi()
        prilagojevalnik.notifyDataSetChanged()
        val i = Ozadje.VSE.indexOfFirst { it.oznaka == predogled.oznaka }
        if (i >= 0) seznam.setSelection(i)
        seznam.requestFocus()
    }

    override fun onStop() {
        // Kar je ostalo samo predogled, ne sme prezivet zaslona: vrnemo se na shranjeno izbiro.
        predogled = Ozadje.izbrana(this)
        super.onStop()
    }

    override fun onDestroy() {
        ozadjeNit.shutdownNow()
        super.onDestroy()
    }

    /** Predogled: ozadje zaslona se zamenja, izbira se se ne shrani. */
    private fun pokazi(izbira: Ozadje.Izbira) {
        if (izbira.oznaka == Ozadje.LASTNA && !Ozadje.imaLastno(this)) {
            predogled = izbira
            koren.background = Ozadje.sestavi(this, Ozadje.VSE[0], Ozadje.zatemnitev(this))
            return
        }
        predogled = izbira
        narisi()
    }

    private fun narisi() {
        koren.background = Ozadje.sestavi(this, predogled, Ozadje.zatemnitev(this))
    }

    private fun izberi(i: Int) {
        val izbira = Ozadje.VSE.getOrNull(i) ?: return
        if (izbira.oznaka == Ozadje.LASTNA) { lastna(); return }
        Ozadje.nastavi(this, izbira)
        predogled = izbira
        narisi()
        prilagojevalnik.notifyDataSetChanged()
    }

    /** Lastna fotografija: ce je ze shranjena, jo uporabimo; sicer gremo naravnost po novo. */
    private fun lastna() {
        if (!Ozadje.imaLastno(this)) { poisciSliko(); return }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_ozadje_lastna))
            .setPositiveButton(getString(R.string.os_ozadje_uporabi)) { _, _ ->
                Ozadje.nastavi(this, Ozadje.VSE.first { it.oznaka == Ozadje.LASTNA })
                predogled = Ozadje.izbrana(this)
                narisi(); prilagojevalnik.notifyDataSetChanged()
            }
            .setNeutralButton(getString(R.string.os_ozadje_izberi_drugo)) { _, _ -> poisciSliko() }
            .setNegativeButton(getString(R.string.os_ozadje_odstrani)) { _, _ ->
                Ozadje.pozabiLastno(this)
                if (Ozadje.izbrana(this).oznaka == Ozadje.LASTNA) Ozadje.nastavi(this, Ozadje.VSE[0])
                predogled = Ozadje.izbrana(this)
                narisi(); prilagojevalnik.notifyDataSetChanged()
            }
            .show()
    }

    private fun poisciSliko() {
        startActivityForResult(Intent(this, DatotekeActivity::class.java)
            .putExtra(DatotekeActivity.EXTRA_IZBERI_SLIKO, true), ZAHTEVA_SLIKA)
    }

    override fun onActivityResult(zahteva: Int, izid: Int, podatki: Intent?) {
        super.onActivityResult(zahteva, izid, podatki)
        if (zahteva != ZAHTEVA_SLIKA || izid != RESULT_OK || podatki == null) return
        val url = podatki.getStringExtra("url").orEmpty()
        if (url.isEmpty()) return
        val lokalno = podatki.getBooleanExtra("lokalno", false)
        val streznik = DatotekeActivity.Streznik.iz(podatki.extras)
        Toast.makeText(this, getString(R.string.os_ozadje_shranjujem), Toast.LENGTH_SHORT).show()
        ozadjeNit.execute {
            val bajti: ByteArray? = try {
                if (lokalno) contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes() }
                else {
                    val s = streznik ?: return@execute
                    val k = PripetiVir.odjemalecZaStreznik(s.odtis)
                    k.newCall(Request.Builder().url(url).header("X-Safeer-Token", s.zeton).build())
                        .execute().use { o -> if (o.isSuccessful) o.body?.bytes() else null }
                }
            } catch (_: Throwable) { null }
            val uspeh = bajti != null && Ozadje.shraniLastno(this, bajti)
            glavna.post {
                if (isFinishing) return@post
                if (!uspeh) { Toast.makeText(this, getString(R.string.os_ozadje_napaka), Toast.LENGTH_LONG).show(); return@post }
                Ozadje.nastavi(this, Ozadje.VSE.first { it.oznaka == Ozadje.LASTNA })
                predogled = Ozadje.izbrana(this)
                narisi()
                prilagojevalnik.notifyDataSetChanged()
            }
        }
    }

    private inner class Prilagojevalnik : BaseAdapter() {
        override fun getCount(): Int = Ozadje.VSE.size
        override fun getItem(i: Int): Any = Ozadje.VSE[i]
        override fun getItemId(i: Int): Long = i.toLong()
        override fun getView(i: Int, star: View?, roditelj: ViewGroup?): View {
            val v = star ?: LayoutInflater.from(this@VidezActivity)
                .inflate(R.layout.os_vrstica_ozadje, roditelj, false)
            val izbira = Ozadje.VSE[i]
            val slika = v.findViewById<ImageView>(R.id.predogled)
            val risba = Ozadje.predogled(this@VidezActivity, izbira)
            if (risba != null) slika.setImageDrawable(risba)
            else slika.setImageResource(R.drawable.os_ikona_slika)
            v.findViewById<TextView>(R.id.ime).text = getString(izbira.imeRes)
            val shranjena = Ozadje.izbrana(this@VidezActivity).oznaka == izbira.oznaka
            v.findViewById<TextView>(R.id.stanje).text =
                if (shranjena) getString(R.string.os_ozadje_izbrano) else ""
            return v
        }
    }

    companion object { private const val ZAHTEVA_SLIKA = 8431 }
}
