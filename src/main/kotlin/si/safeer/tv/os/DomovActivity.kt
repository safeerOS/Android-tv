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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Domaci zaslon Safeer OS: Zacni (Splet, Datoteke, Safeer Link), naprave v Linku, aplikacije na
 * televizorju. Vse z D-padom; fokus je mint obroba (docs/TV-UI.md).
 *
 * Sredisce Linka gosti Safeer Browser na tem televizorju; Safeer OS vanj vstopi brez kode
 * (Sorodnik). Brez brskalnika lupina pove, kaj namestiti - ne vrze napake.
 */
class DomovActivity : Activity(), LinkOdjemalec.Poslusalec {

    private lateinit var stanjeBesedilo: TextView
    private lateinit var stanjePika: View
    private lateinit var ura: TextView
    private lateinit var vrstaZacni: LinearLayout
    private lateinit var vrstaNaprave: LinearLayout
    private lateinit var napraveOpomba: TextView
    private lateinit var vrstaAplikacije: LinearLayout
    private lateinit var opombaSpodaj: TextView

    private val link by lazy { LinkUpravitelj.pridobi(this) }
    private val glavna = Handler(Looper.getMainLooper())
    private val tikUre = object : Runnable {
        override fun run() {
            ura.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            glavna.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.os_activity_domov)
        stanjeBesedilo = findViewById(R.id.stanjeBesedilo)
        stanjePika = findViewById(R.id.stanjePika)
        ura = findViewById(R.id.ura)
        vrstaZacni = findViewById(R.id.vrstaZacni)
        vrstaNaprave = findViewById(R.id.vrstaNaprave)
        napraveOpomba = findViewById(R.id.napraveOpomba)
        vrstaAplikacije = findViewById(R.id.vrstaAplikacije)
        opombaSpodaj = findViewById(R.id.opombaSpodaj)
        narisiZacni()
        narisiNaprave(emptyList())
    }

    override fun onStart() {
        super.onStart()
        glavna.post(tikUre)
        narisiAplikacije()
        link.dodaj(this)
        osveziScit()
    }

    override fun onStop() {
        glavna.removeCallbacks(tikUre)
        link.odstrani(this)
        super.onStop()
    }

    // ------------------------------------------------------------------ Link

    override fun naStanje(povezan: Boolean, sporocilo: String) {
        val kje = link.imeSredisca.ifBlank { getString(R.string.os_naprava_tv) }
        opombaSpodaj.visibility = View.GONE
        when {
            povezan -> pokaziStanje(true, getString(R.string.os_stanje_povezan, kje))
            sporocilo == "ni_brskalnika" -> {
                pokaziStanje(false, getString(R.string.os_stanje_ni_brskalnika))
                opombaSpodaj.text = getString(R.string.os_ni_brskalnika_dolgo)
                opombaSpodaj.visibility = View.VISIBLE
            }
            sporocilo == "ni" -> pokaziStanje(false, getString(R.string.os_stanje_ni))
            else -> pokaziStanje(false, getString(R.string.os_stanje_povezujem))
        }
    }

    override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) {
        val kje = link.imeSredisca.ifBlank { getString(R.string.os_naprava_tv) }
        if (link.povezan) pokaziStanje(true, getString(R.string.os_stanje_povezan, kje))
        narisiNaprave(naprave.filter { it.id != Identiteta.id(this) })
    }

    override fun naNaslov(url: String, naslov: String, od: String) {
        odpriVBrskalniku(url)
        Toast.makeText(this, getString(R.string.os_poslano_tv), Toast.LENGTH_SHORT).show()
    }

    override fun naBesedilo(besedilo: String, od: String) {
        Toast.makeText(this, getString(R.string.os_prejeto_besedilo, od) + "\n" + besedilo.take(200), Toast.LENGTH_LONG).show()
    }

    override fun naZavrnitev() { }

    private fun pokaziStanje(povezan: Boolean, besedilo: String) {
        stanjeBesedilo.text = besedilo
        stanjePika.alpha = if (povezan) 1f else 0.35f
    }

    // ------------------------------------------------------------------ Zacni

    private fun narisiZacni() {
        vrstaZacni.removeAllViews()
        dodajVeliko(R.drawable.os_ikona_splet, getString(R.string.os_splet), getString(R.string.os_splet_opis)) {
            odpriVBrskalniku(null)
        }
        dodajVeliko(R.drawable.os_ikona_datoteke, getString(R.string.os_datoteke), getString(R.string.os_datoteke_opis)) {
            startActivity(Intent(this, DatotekeActivity::class.java))
        }
        dodajVeliko(R.drawable.os_ikona_link, getString(R.string.os_link), getString(R.string.os_link_opis)) {
            odpriLinkVBrskalniku()
        }
        karticaScit = dodajVeliko(R.drawable.os_ikona_scit, getString(R.string.os_scit), getString(R.string.os_scit_preverjam)) { preklopiScit() }
        vrstaZacni.getChildAt(0)?.requestFocus()
    }

    // ------------------------------------------------------------------ Safeer Scit (filter DNS za ves televizor)

    private var karticaScit: View? = null
    private var scitStanje: Scit.Stanje? = null

    private fun osveziScit() {
        Scit.stanje(this) { pokaziScit(it) }
    }

    private fun pokaziScit(s: Scit.Stanje) {
        scitStanje = s
        val opis = karticaScit?.findViewById<TextView>(R.id.opis) ?: return
        opis.text = when {
            !s.naVoljo -> getString(R.string.os_scit_ni_brskalnika)
            s.vklopljen && s.tece -> getString(R.string.os_scit_vklopljen_opis, s.blokiranih)
            s.vklopljen -> getString(R.string.os_scit_prekinjen)
            else -> getString(R.string.os_scit_izklopljen_opis)
        }
    }

    private fun preklopiScit() {
        val s = scitStanje
        if (s == null || !s.naVoljo) { Toast.makeText(this, getString(R.string.os_scit_ni_brskalnika), Toast.LENGTH_LONG).show(); return }
        if (s.vklopljen) {
            Scit.izklopi(this) { pokaziScit(it); Toast.makeText(this, getString(R.string.os_scit_izklopljen_kratko), Toast.LENGTH_SHORT).show() }
        } else {
            Scit.vklopi(this) { nov ->
                if (nov.potrebujeOkno || (!nov.vklopljen && nov.naVoljo)) Scit.odpriVklop(this) else pokaziScit(nov)
            }
        }
        // Storitev se zazene ali ustavi sele trenutek kasneje: stanje preberemo se enkrat, ko je res novo.
        glavna.postDelayed({ osveziScit() }, 2_500)
    }

    private fun dodajVeliko(ikona: Int, naslov: String, opis: String, ob: () -> Unit): View {
        val v = LayoutInflater.from(this).inflate(R.layout.os_kartica_velika, vrstaZacni, false)
        v.findViewById<ImageView>(R.id.ikona).setImageResource(ikona)
        v.findViewById<TextView>(R.id.naslov).text = naslov
        v.findViewById<TextView>(R.id.opis).text = opis
        v.setOnClickListener { ob() }
        v.onFocusChangeListener = fokus
        vrstaZacni.addView(v)
        return v
    }

    // ------------------------------------------------------------------ Naprave

    private fun narisiNaprave(naprave: List<LinkOdjemalec.Naprava>) {
        vrstaNaprave.removeAllViews()
        napraveOpomba.visibility = if (naprave.isEmpty()) View.VISIBLE else View.GONE
        for (n in naprave) {
            val v = LayoutInflater.from(this).inflate(R.layout.os_kartica_naprava, vrstaNaprave, false)
            v.findViewById<TextView>(R.id.ime).text = n.ime.ifBlank { n.id }
            v.findViewById<TextView>(R.id.vloga).text = vrstaNaprave(n)
            v.onFocusChangeListener = fokus
            v.setOnClickListener {
                // Racunalnik s Safeer Controlom, ki deli mape: naravnost v njegove datoteke; sicer stran Linka.
                if (n.zmoznosti.contains("files")) startActivity(Intent(this, DatotekeActivity::class.java).putExtra(DatotekeActivity.EXTRA_RACUNALNIK, n.id))
                else odpriLinkVBrskalniku()
            }
            vrstaNaprave.addView(v)
        }
    }

    private fun vrstaNaprave(n: LinkOdjemalec.Naprava): String = when {
        n.naslov == "127.0.0.1" || n.id.startsWith("tv-") -> "TV · Safeer Link"
        n.id.startsWith("pc-") -> if (n.id.endsWith("-control")) "Safeer Control" else "Safeer Browser · PC"
        n.id.startsWith("phone-") -> "Safeer Browser · Android"
        else -> n.vloga
    }

    // ------------------------------------------------------------------ Aplikacije

    private fun narisiAplikacije() {
        vrstaAplikacije.removeAllViews()
        for (a in Aplikacije.seznam(this)) {
            val v = LayoutInflater.from(this).inflate(R.layout.os_kartica_app, vrstaAplikacije, false)
            v.findViewById<ImageView>(R.id.ikona).setImageDrawable(a.ikona)
            v.findViewById<TextView>(R.id.ime).text = a.ime
            v.onFocusChangeListener = fokus
            v.setOnClickListener {
                try { startActivity(a.namera.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
            }
            vrstaAplikacije.addView(v)
        }
    }

    // ------------------------------------------------------------------ pomozno

    private val fokus = View.OnFocusChangeListener { v, ima ->
        v.animate().scaleX(if (ima) 1.04f else 1f).scaleY(if (ima) 1.04f else 1f).setDuration(120).start()
        if (ima) (v.parent as? ViewGroup)?.let { it.requestChildFocus(v, v) }
    }

    /** Brskalnik je del iste aplikacije: odpre se njegova glavna dejavnost (v svojem opravilu), po zelji z naslovom. */
    private fun odpriVBrskalniku(url: String?) {
        val namera = Intent(this, si.safeer.tv.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (url != null) { namera.action = Intent.ACTION_VIEW; namera.data = Uri.parse(url) }
        try { startActivity(namera) } catch (e: Throwable) {
            Toast.makeText(this, e.message ?: "?", Toast.LENGTH_SHORT).show()
        }
    }

    /** Stran Safeer Link v brskalniku (seznanitev, naprave, daljinec); brskalnik pozna dodatek odpri_link. */
    private fun odpriLinkVBrskalniku() {
        val namera = Intent(this, si.safeer.tv.MainActivity::class.java).putExtra("odpri_link", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(namera) } catch (_: Throwable) { }
    }
}
