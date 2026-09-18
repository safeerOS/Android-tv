package si.safeer.tv.tablica

import si.safeer.tv.R
import si.safeer.tv.os.DatotekeActivity
import si.safeer.tv.os.LinkOdjemalec
import si.safeer.tv.os.LinkUpravitelj
import si.safeer.tv.os.NapraveActivity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Domaci zaslon Safeer OS Tablet - prvi kamen, ne se cela hisa.
 *
 * Televizorjev Safeer OS je narejen za daljinec: fokus, vrste kartic, pisava za dva metra. Tablica
 * je dotik od blizu, zato tu ni fokusnih vrst, ampak ploscice cez celo sirino, visoke vsaj 88 dp.
 * Jedro (Safeer Link, datoteke z racunalnika) je isto kot na televizorju - delita si kodo, ne
 * postavitve.
 *
 * Kar ze dela: stanje povezave in datoteke z racunalnika (isti zaslon kot na televizorju, ki je
 * seznam in je z dotikom uporaben). Vse drugo pride po vrsti; televizorja se pri tem ne dotikamo.
 */
class DomovTabletActivity : Activity(), LinkOdjemalec.Poslusalec {

    private lateinit var stanje: TextView
    private lateinit var ploscice: LinearLayout
    private val link by lazy { LinkUpravitelj.pridobi(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.tablet_activity_domov)
        stanje = findViewById(R.id.stanje)
        ploscice = findViewById(R.id.ploscice)
        narisi()
    }

    override fun onStart() {
        super.onStart()
        link.dodaj(this)
        pokaziStanje()
    }

    override fun onStop() {
        link.odstrani(this)
        super.onStop()
    }

    private fun narisi() {
        ploscice.removeAllViews()
        dodaj(R.drawable.os_ikona_datoteke, getString(R.string.tablet_datoteke),
            getString(R.string.tablet_datoteke_opis)) {
            startActivity(Intent(this, DatotekeActivity::class.java))
        }
        dodaj(R.drawable.os_ikona_link, getString(R.string.tablet_naprave),
            getString(R.string.tablet_naprave_opis)) {
            startActivity(Intent(this, NapraveActivity::class.java))
        }
    }

    private fun dodaj(ikona: Int, ime: String, opis: String, ob: () -> Unit) {
        val v: View = LayoutInflater.from(this).inflate(R.layout.tablet_ploscica, ploscice, false)
        v.findViewById<ImageView>(R.id.ikona).setImageResource(ikona)
        v.findViewById<TextView>(R.id.ime).text = ime
        v.findViewById<TextView>(R.id.opis).text = opis
        v.setOnClickListener { ob() }
        ploscice.addView(v)
    }

    private fun pokaziStanje() {
        val kje = link.imeSredisca.ifBlank { getString(R.string.os_naprava_tv) }
        stanje.text = if (link.povezan) getString(R.string.os_stanje_povezan, kje)
        else getString(R.string.os_stanje_povezujem)
    }

    // ------------------------------------------------------------------ Link

    override fun naStanje(povezan: Boolean, sporocilo: String) { pokaziStanje() }
    override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) { pokaziStanje() }
    override fun naNaslov(url: String, naslov: String, od: String) { }
    override fun naBesedilo(besedilo: String, od: String) { }
    override fun naZavrnitev() { }
}
