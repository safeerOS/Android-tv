package si.safeer.tv.tablica

import si.safeer.tv.R
import si.safeer.tv.os.AplikacijeHostaActivity
import si.safeer.tv.os.DatotekeActivity
import si.safeer.tv.os.Identiteta
import si.safeer.tv.os.LinkOdjemalec
import si.safeer.tv.os.LinkUpravitelj
import si.safeer.tv.os.NapraveActivity
import si.safeer.tv.os.ZaslonActivity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * Domaci zaslon Safeer OS Tablet.
 *
 * Televizorjev Safeer OS je narejen za daljinec: fokusne vrste, pisava za dva metra, vse skozi
 * smerne tipke. Tablica je dotik od blizu, zato tu ni fokusnih vrst, ampak mreza velikih ploscic
 * (dve v vrsti pokoncno, tri na sirokem zaslonu), vsaka visoka najmanj 132 dp - cela ploscica je
 * tipka, ne majhna ikona v njej.
 *
 * Jedro je isto kot na televizorju (Safeer Link, datoteke, programi, zaslon racunalnika): delita
 * si kodo, ne postavitve. Zato se popravek v jedru pozna na obeh, tablica pa ima svojo lupino.
 *
 * Posteno: ploscica se pokaze sele, ko racunalnik tisto res deli. Kadar racunalnika ni, tablica
 * pove, kaj je treba narediti, in nicesar ne obljublja.
 */
class DomovTabletActivity : Activity(), LinkOdjemalec.Poslusalec {

    private lateinit var naslov: TextView
    private lateinit var podnaslov: TextView
    private lateinit var stanje: TextView
    private lateinit var opomba: TextView
    private lateinit var ploscice: GridLayout
    private val link by lazy { LinkUpravitelj.pridobi(this) }

    /** Zadnje sporocilo o stanju Linka ("ni", "ni_linka", "krajevni" ...); loci "nisem povezan"
     *  od "povezan sem, a racunalnika ni" - to dvoje ni ista stvar in uporabnik mora vedeti, katera je. */
    private var sporociloStanja = ""

    /** Zmoznosti, ki jih je nazadnje ponujal kaksen racunalnik; ob spremembi mrezo narisemo znova. */
    private var imamoDatoteke = false
    private var imamoPrograme = false
    private var imamoZaslon = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.tablet_activity_domov)
        naslov = findViewById(R.id.naslov)
        podnaslov = findViewById(R.id.podnaslov)
        stanje = findViewById(R.id.stanje)
        opomba = findViewById(R.id.opomba)
        ploscice = findViewById(R.id.ploscice)
        naslov.text = getString(R.string.tablet_ime)
        podnaslov.text = getString(R.string.tablet_podnaslov)
        opomba.text = getString(R.string.tablet_v_pripravi)
        narisi()
    }

    override fun onStart() {
        super.onStart()
        link.dodaj(this)
        pokaziStanje()
        narisi()
    }

    override fun onStop() {
        link.odstrani(this)
        super.onStop()
    }

    // ------------------------------------------------------------------ mreza

    /** Racunalnik, ki ponuja doloceno zmoznost (files, apps, desktop). */
    private fun racunalnik(zmoznost: String): LinkOdjemalec.Naprava? =
        link.naprave.firstOrNull { it.zmoznosti.contains(zmoznost) && it.id != Identiteta.id(this) }

    private fun narisi() {
        imamoDatoteke = racunalnik("files") != null
        imamoPrograme = racunalnik("apps") != null
        imamoZaslon = racunalnik("desktop") != null

        ploscice.removeAllViews()
        if (imamoDatoteke) {
            dodaj(R.drawable.os_ikona_datoteke, R.string.tablet_datoteke, R.string.tablet_datoteke_opis) {
                startActivity(Intent(this, DatotekeActivity::class.java))
            }
        }
        if (imamoPrograme) {
            dodaj(R.drawable.os_ikona_racunalnik, R.string.tablet_programi, R.string.tablet_programi_opis) {
                startActivity(Intent(this, AplikacijeHostaActivity::class.java))
            }
        }
        if (imamoZaslon) {
            val r = racunalnik("desktop")
            dodaj(R.drawable.os_ikona_zaslon, R.string.tablet_zaslon, R.string.tablet_zaslon_opis) {
                val namera = Intent(this, ZaslonActivity::class.java)
                if (r != null) namera.putExtra(DatotekeActivity.EXTRA_RACUNALNIK, r.id)
                startActivity(namera)
            }
        }
        // Naprave so vedno na voljo: tam se naprave seznanijo in tam se vidi, kaj manjka.
        dodaj(R.drawable.os_ikona_link, R.string.tablet_naprave, R.string.tablet_naprave_opis) {
            startActivity(Intent(this, NapraveActivity::class.java))
        }
        opomba.text = when {
            imamoDatoteke || imamoPrograme || imamoZaslon -> getString(R.string.tablet_v_pripravi)
            // Dokler tablica ni v Linku, ji racunalnika ne manjka - manjka ji seznanitev.
            !link.povezan -> getString(R.string.tablet_ni_linka)
            else -> getString(R.string.tablet_ni_racunalnika)
        }
    }

    private fun dodaj(ikona: Int, ime: Int, opis: Int, ob: () -> Unit) {
        val v: View = LayoutInflater.from(this).inflate(R.layout.tablet_ploscica, ploscice, false)
        v.findViewById<ImageView>(R.id.ikona).setImageResource(ikona)
        v.findViewById<TextView>(R.id.ime).setText(ime)
        v.findViewById<TextView>(R.id.opis).setText(opis)
        v.setOnClickListener { ob() }
        // Enako siroki stolpci: sirino doloci utez, ne vsebina ploscice.
        val mere = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED),
            GridLayout.spec(GridLayout.UNDEFINED, 1f))
        mere.width = 0
        val rob = (6 * resources.displayMetrics.density).toInt()
        mere.setMargins(rob, rob, rob, rob)
        v.layoutParams = mere
        ploscice.addView(v)
    }

    private fun pokaziStanje() {
        val kje = link.imeSredisca.ifBlank { getString(R.string.os_naprava_tv) }
        stanje.text = when {
            link.povezan -> getString(R.string.os_stanje_povezan, kje)
            sporociloStanja == "krajevni" -> getString(R.string.os_stanje_krajevni)
            sporociloStanja == "ni_linka" -> getString(R.string.os_stanje_ni_linka)
            sporociloStanja == "ni" -> getString(R.string.os_stanje_ni)
            else -> getString(R.string.os_stanje_povezujem)
        }
    }

    // ------------------------------------------------------------------ Link

    override fun naStanje(povezan: Boolean, sporocilo: String) {
        sporociloStanja = sporocilo
        pokaziStanje()
        narisi()
    }

    override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) {
        pokaziStanje()
        val datoteke = racunalnik("files") != null
        val programi = racunalnik("apps") != null
        val zaslon = racunalnik("desktop") != null
        if (datoteke != imamoDatoteke || programi != imamoPrograme || zaslon != imamoZaslon) narisi()
    }

    override fun naNaslov(url: String, naslov: String, od: String) { }
    override fun naBesedilo(besedilo: String, od: String) { }
    override fun naZavrnitev() { }
}
