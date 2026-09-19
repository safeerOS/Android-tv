package si.safeer.tv.tablica

import si.safeer.tv.R
import si.safeer.tv.os.AplikacijeHostaActivity
import si.safeer.tv.os.DatotekeActivity
import si.safeer.tv.os.Host
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
import android.widget.Toast

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

    /** Druga sredisca v tej hisi (televizor ...), ki jim se tablica lahko pridruzi. */
    private var hubi: List<IskanjeHubov.Hub> = emptyList()
    private var iscem = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.tablet_activity_domov)
        si.safeer.tv.os.Robovi.uporabi(this)
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
        isci()
    }

    /**
     * Racunalnik je seznanjen s srediscem na televizorju, tablica pa ima svoje, prazno. Zato
     * poiscemo sredisca v hisi in ponudimo, da se tablica pridruzi tistemu, kjer so naprave.
     */
    private fun isci() {
        if (iscem || imamoDatoteke || imamoPrograme || imamoZaslon) return
        iscem = true
        IskanjeHubov.najdi(this) { najdeni ->
            iscem = false
            if (isFinishing) return@najdi
            // Sredisce, s katerim smo ze povezani, ni ponudba.
            hubi = najdeni.filter { !(link.povezan && it.ime == link.imeSredisca) }
            slediSredisculu(najdeni)
            narisi()
        }
    }

    /**
     * Televizor je dobil nov naslov IP (usmerjevalnik ga dodeli znova): sredisce prepoznamo po
     * odtisu potrdila in naslov popravimo sami - brez nove kode.
     */
    private fun slediSredisculu(najdeni: List<IskanjeHubov.Hub>) {
        val p = Host.poverilnice(this) ?: return
        if (!Host.jeOddaljen(this) || link.povezan) return
        val isti = najdeni.firstOrNull { it.odtis.isNotBlank() && it.odtis.equals(p.odtis, ignoreCase = true) } ?: return
        if (isti.naslov == p.hubUrl) return
        Host.shrani(this, isti.naslov, p.zeton, p.odtis, p.hubId)
        link.ponovnoPoveziSe()
    }

    /** Seznanitev s srediscem: kodo pokaze sredisce na svojem zaslonu, uporabnik jo vtipka tu. */
    private fun seznani(hub: IskanjeHubov.Hub) {
        si.safeer.tv.cast.HubPairing.prekini()
        Host.zapomniNaslov(this, hub.naslov)
        Toast.makeText(this, getString(R.string.tablet_povezujem, hub.ime), Toast.LENGTH_SHORT).show()
        si.safeer.tv.cast.HubPairing.pair(this, hub.naslov, Identiteta.id(this),
            getString(R.string.os_ime_vrste) + " (" + android.os.Build.MODEL + ")",
            { _, _ -> if (!isFinishing) vnesiKodo(hub) },
            { uspelo -> if (!uspelo && !isFinishing)
                Toast.makeText(this, getString(R.string.tablet_ni_odgovora, hub.ime), Toast.LENGTH_LONG).show() })
    }

    private fun vnesiKodo(hub: IskanjeHubov.Hub) {
        val vnos = android.widget.EditText(this).apply {
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 28f
            gravity = android.view.Gravity.CENTER
            setPadding(40, 30, 40, 30)
        }
        val okno = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.tablet_koda_naslov, hub.ime))
            .setMessage(getString(R.string.tablet_koda_opis, hub.ime))
            .setView(vnos)
            .setPositiveButton(getString(R.string.tablet_poveziSe)) { _, _ -> potrdi(hub, vnos.text?.toString().orEmpty()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> si.safeer.tv.cast.HubPairing.prekini() }
            // Kode ni na televizorju (potekla, TV je bil ugasnjen): nova prijava, nova koda.
            .setNeutralButton(getString(R.string.tablet_nova_koda)) { _, _ -> seznani(hub) }
            .show()
        vnos.requestFocus()
        // Koda velja pet minut; potem okno ne sme vec cakati na nekaj, cesar ni.
        ploscice.postDelayed({
            if (okno.isShowing) {
                okno.dismiss()
                si.safeer.tv.cast.HubPairing.prekini()
                Toast.makeText(this, getString(R.string.tablet_koda_potekla), Toast.LENGTH_LONG).show()
            }
        }, 290_000)
    }

    private fun potrdi(hub: IskanjeHubov.Hub, koda: String) {
        si.safeer.tv.cast.HubPairing.potrdiKodo(this, koda, Identiteta.id(this)) { uspelo, napaka ->
            if (isFinishing) return@potrdiKodo
            val izid = si.safeer.tv.cast.HubPairing.zadnjaSeznanitev
            if (uspelo && izid != null) {
                Host.shrani(this, hub.naslov, izid.zeton, izid.odtis, izid.hubId)
                link.ponovnoPoveziSe()
                hubi = emptyList()
                Toast.makeText(this, getString(R.string.tablet_povezana, hub.ime), Toast.LENGTH_LONG).show()
                pokaziStanje()
                narisi()
                return@potrdiKodo
            }
            val sporocilo = when (napaka) {
                "napacna_koda" -> getString(R.string.tablet_napacna_koda)
                "prevec_poskusov" -> getString(R.string.tablet_prevec_poskusov)
                "prijava_ne_obstaja", "seznanitev_ne_tece" -> getString(R.string.tablet_koda_potekla)
                else -> getString(R.string.tablet_ni_odgovora, hub.ime)
            }
            Toast.makeText(this, sporocilo, Toast.LENGTH_LONG).show()
            if (napaka == "napacna_koda") vnesiKodo(hub)
        }
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
        // Brez Safeer Linka tablica ne vidi racunalnika. Televizor ima vklop na svojem domacem
        // zaslonu in v nastavitvah, tablica pa doslej ni imela nikjer - zato je tu prva ploscica
        // prav vklop. Krajevnega nacina tablici ne ponujamo: brez racunalnika ta zaslon nima cesa
        // pokazati, zato bi bila izbira samo videz izbire.
        if (!link.povezan && (link.vprasamoZaNacin() || link.jeKrajevni() || sporociloStanja == "ni_linka")) {
            dodaj(R.drawable.os_ikona_link, R.string.tablet_vklopi, R.string.tablet_vklopi_opis) {
                link.vklopiLink()
                Toast.makeText(this, getString(R.string.tablet_vklopljen), Toast.LENGTH_LONG).show()
                pokaziStanje()
                narisi()
            }
        } else if (!link.povezan && sporociloStanja == "ni") {
            // Sredisce tece, povezave pa ni: poskus od zacetka je edino, kar uporabnik lahko stori.
            dodaj(R.drawable.os_ikona_link, R.string.tablet_znova, R.string.tablet_znova_opis) {
                link.ponovnoPoveziSe()
                pokaziStanje()
                narisi()
            }
        }
        // Sredisca v hisi: tablica se pridruzi tistemu, s katerim je seznanjen racunalnik.
        if (!imamoDatoteke && !imamoPrograme && !imamoZaslon) {
            for (h in hubi) dodajBesedilo(R.drawable.os_ikona_link, getString(R.string.tablet_pridruzi, h.ime),
                getString(R.string.tablet_pridruzi_opis, h.ime)) { seznani(h) }
        }
        // Naprave so vedno na voljo: tam se naprave seznanijo in tam se vidi, kaj manjka.
        dodaj(R.drawable.os_ikona_link, R.string.tablet_naprave, R.string.tablet_naprave_opis) {
            startActivity(Intent(this, NapraveActivity::class.java))
        }
        opomba.text = when {
            imamoDatoteke || imamoPrograme || imamoZaslon -> getString(R.string.tablet_v_pripravi)
            hubi.isNotEmpty() -> getString(R.string.tablet_pridruzi_namig)
            // Dokler tablica ni v Linku, ji racunalnika ne manjka - manjka ji seznanitev.
            !link.povezan -> getString(R.string.tablet_ni_linka)
            else -> getString(R.string.tablet_ni_racunalnika)
        }
        pokaziStanje()
    }

    private fun dodaj(ikona: Int, ime: Int, opis: Int, ob: () -> Unit) =
        dodajBesedilo(ikona, getString(ime), getString(opis), ob)

    private fun dodajBesedilo(ikona: Int, ime: String, opis: String, ob: () -> Unit) {
        val v: View = LayoutInflater.from(this).inflate(R.layout.tablet_ploscica, ploscice, false)
        v.findViewById<ImageView>(R.id.ikona).setImageResource(ikona)
        v.findViewById<TextView>(R.id.ime).text = ime
        v.findViewById<TextView>(R.id.opis).text = opis
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
            // Tablica je povezana samo s svojim, praznim srediscem: to ni "povezano" v smislu, ki
            // uporabnika zanima (televizor, racunalnik), zato tega ne trdimo.
            link.povezan && !Host.jeOddaljen(this) && !imamoDatoteke && !imamoPrograme && !imamoZaslon ->
                getString(R.string.tablet_stanje_ni_tv)
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
        if (!povezan && sporocilo == "ni") isci()
    }

    override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) {
        pokaziStanje()
        val datoteke = racunalnik("files") != null
        val programi = racunalnik("apps") != null
        val zaslon = racunalnik("desktop") != null
        if (datoteke != imamoDatoteke || programi != imamoPrograme || zaslon != imamoZaslon) narisi()
        // Racunalnika ni: morda je v drugem srediscu v hisi (ali je televizor dobil nov naslov).
        if (!datoteke && !programi && !zaslon) isci()
    }

    override fun naNaslov(url: String, naslov: String, od: String) { }
    override fun naBesedilo(besedilo: String, od: String) { }
    override fun naZavrnitev() { }
}
