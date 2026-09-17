package si.safeer.tv.os

import si.safeer.tv.R

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * Nastavitve Safeer OS: kar uporabnik lahko kadarkoli vklopi in izklopi.
 *
 *  - **Domaci zaslon televizorja**: ali Safeer OS prevzame tipko Domov ali ostane navadna
 *    aplikacija, prek katere zaganjas spletne aplikacije. Oboje je enakovredno; odloci uporabnik.
 *  - **Nacin delovanja**: Safeer Link (naprave, datoteke z racunalnika, daljinec) ali krajevno.
 *  - **Safeer Scit**: filter DNS za ves televizor.
 */
class NastavitveActivity : Activity() {

    private class Vrstica(val ikona: Int, val ime: String, val opis: String, val stanje: String, val ob: () -> Unit)

    private lateinit var koren: View
    private lateinit var seznam: ListView
    private lateinit var opomba: TextView
    private val link by lazy { LinkUpravitelj.pridobi(this) }
    private var vrstice: List<Vrstica> = emptyList()
    private val prilagojevalnik = Prilagojevalnik()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.os_activity_nastavitve)
        koren = findViewById(R.id.koren)
        seznam = findViewById(R.id.seznam)
        opomba = findViewById(R.id.opomba)
        opomba.text = getString(R.string.os_nastavitve_opomba)
        seznam.adapter = prilagojevalnik
        seznam.setOnItemClickListener { _, _, i, _ -> vrstice.getOrNull(i)?.ob?.invoke() }
    }

    override fun onResume() {
        super.onResume()
        // Sistemsko okno je zaprto: nas zaslon se spet pokaze.
        koren.visibility = View.VISIBLE
        narisi()
    }

    /**
     * Sistemska okna (izbira domacega zaslona, dovoljenje za Scit) so na televizorju prosojna in
     * nasa vsebina prosevaja skoznje - besedilo cez besedilo, neberljivo. Zato svoj zaslon skrijemo,
     * dokler je sistemsko okno spredaj; ob vrnitvi (onResume) ga spet pokazemo.
     */
    private fun sistemskoOkno(odpri: () -> Unit) {
        koren.visibility = View.INVISIBLE
        odpri()
    }

    private fun narisi() {
        val jeDomaci = Zaganjalnik.jeIzbran(this)
        val ponujen = Zaganjalnik.jePonujen(this)
        val domaciStanje = when {
            jeDomaci -> getString(R.string.os_zaganjalnik_izbran)
            ponujen -> getString(R.string.os_zaganjalnik_ponujen)
            else -> getString(R.string.os_izklopljeno)
        }
        val krajevni = link.jeKrajevni()
        vrstice = listOf(
            Vrstica(R.drawable.os_ikona_nastavitve, getString(R.string.os_zaganjalnik),
                getString(R.string.os_zaganjalnik_opis), domaciStanje) { preklopiZaganjalnik(jeDomaci || ponujen) },
            Vrstica(R.drawable.os_ikona_link, getString(R.string.os_nacin),
                getString(R.string.os_nacin_kratko),
                getString(if (krajevni) R.string.os_stanje_nacin_krajevni else R.string.os_stanje_nacin_link)) { preklopiNacin(krajevni) },
            Vrstica(R.drawable.os_ikona_scit, getString(R.string.os_scit),
                getString(R.string.os_scit_nastavitev_opis),
                getString(if (si.safeer.tv.scit.Scit.jeVklopljen(this)) R.string.os_vklopljeno else R.string.os_izklopljeno)) { preklopiScit() },
        )
        prilagojevalnik.notifyDataSetChanged()
        if (seznam.selectedItemPosition < 0) seznam.requestFocus()
    }

    /**
     * Vklop ne more biti tih: domaci zaslon izbere sistem, ne aplikacija. Mi moznost samo ponudimo
     * in odpremo sistemsko okno; izklop pa naredimo sami, da je pot nazaj vedno pri roki.
     */
    private fun preklopiZaganjalnik(jeVklopljen: Boolean) {
        if (jeVklopljen) {
            Zaganjalnik.opusti(this)
            Toast.makeText(this, getString(R.string.os_zaganjalnik_izklopljen), Toast.LENGTH_LONG).show()
            narisi()
            return
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_zaganjalnik))
            .setMessage(getString(R.string.os_zaganjalnik_vprasanje))
            .setPositiveButton(getString(R.string.os_zaganjalnik_vklopi)) { _, _ ->
                sistemskoOkno {
                    if (!Zaganjalnik.ponudi(this)) {
                        koren.visibility = View.VISIBLE
                        Toast.makeText(this, getString(R.string.os_zaganjalnik_ni_nastavitev), Toast.LENGTH_LONG).show()
                    }
                }
                narisi()
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .show()
    }

    private fun preklopiNacin(krajevni: Boolean) {
        if (krajevni) {
            link.vklopiLink()
            Toast.makeText(this, getString(R.string.os_nacin_link_vklopljen), Toast.LENGTH_SHORT).show()
        } else {
            link.krajevniNacin()
            Toast.makeText(this, getString(R.string.os_nacin_krajevni_izbran), Toast.LENGTH_LONG).show()
        }
        narisi()
    }

    private fun preklopiScit() {
        val namera = Intent(this, si.safeer.tv.scit.ScitActivity::class.java)
        if (si.safeer.tv.scit.Scit.jeVklopljen(this)) namera.putExtra(si.safeer.tv.scit.ScitActivity.EXTRA_IZKLOPI, true)
        sistemskoOkno {
            try { startActivity(namera) } catch (_: Throwable) { koren.visibility = View.VISIBLE }
        }
    }

    private inner class Prilagojevalnik : BaseAdapter() {
        override fun getCount(): Int = vrstice.size
        override fun getItem(i: Int): Any = vrstice[i]
        override fun getItemId(i: Int): Long = i.toLong()
        override fun getView(i: Int, star: View?, roditelj: ViewGroup): View {
            val v = star ?: LayoutInflater.from(this@NastavitveActivity)
                .inflate(R.layout.os_vrstica_nastavitev, roditelj, false)
            val vr = vrstice[i]
            v.findViewById<ImageView>(R.id.ikona).setImageResource(vr.ikona)
            v.findViewById<TextView>(R.id.ime).text = vr.ime
            v.findViewById<TextView>(R.id.opis).text = vr.opis
            v.findViewById<TextView>(R.id.stanje).text = vr.stanje
            v.setBackgroundResource(R.drawable.os_izbor_vrstice)
            return v
        }
    }
}
