package si.safeer.os

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

    private val link by lazy { LinkOdjemalec(this) }
    private val glavna = Handler(Looper.getMainLooper())
    private var prosimZaPoverilnice = false
    private val tikUre = object : Runnable {
        override fun run() {
            ura.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            glavna.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_domov)
        stanjeBesedilo = findViewById(R.id.stanjeBesedilo)
        stanjePika = findViewById(R.id.stanjePika)
        ura = findViewById(R.id.ura)
        vrstaZacni = findViewById(R.id.vrstaZacni)
        vrstaNaprave = findViewById(R.id.vrstaNaprave)
        napraveOpomba = findViewById(R.id.napraveOpomba)
        vrstaAplikacije = findViewById(R.id.vrstaAplikacije)
        opombaSpodaj = findViewById(R.id.opombaSpodaj)
        link.poslusalec = this
        narisiZacni()
        narisiNaprave(emptyList())
    }

    override fun onStart() {
        super.onStart()
        glavna.post(tikUre)
        narisiAplikacije()
        poveziLink()
    }

    override fun onStop() {
        glavna.removeCallbacks(tikUre)
        link.ustavi()
        super.onStop()
    }

    // ------------------------------------------------------------------ Link

    private fun poveziLink() {
        if (!Sorodnik.jeBrskalnikNamescen(this)) {
            pokaziStanje(false, getString(R.string.stanje_ni_brskalnika))
            opombaSpodaj.text = getString(R.string.ni_brskalnika_dolgo)
            opombaSpodaj.visibility = View.VISIBLE
            return
        }
        opombaSpodaj.visibility = View.GONE
        val shranjene = Identiteta.beri(this)
        if (shranjene != null) {
            pokaziStanje(false, getString(R.string.stanje_povezujem))
            link.zazeni(shranjene)
        } else {
            zahtevajPoverilnice()
        }
    }

    private fun zahtevajPoverilnice() {
        if (prosimZaPoverilnice) return
        prosimZaPoverilnice = true
        pokaziStanje(false, getString(R.string.stanje_povezujem))
        Sorodnik.zahtevaj(this) { p ->
            prosimZaPoverilnice = false
            if (p == null) {
                pokaziStanje(false, getString(R.string.stanje_ni))
                return@zahtevaj
            }
            Identiteta.shrani(this, p)
            link.zazeni(p)
        }
    }

    override fun naStanje(povezan: Boolean, sporocilo: String) {
        if (povezan) {
            val kje = link.imeSredisca.ifBlank { getString(R.string.naprava_tv) }
            pokaziStanje(true, getString(R.string.stanje_povezan, kje))
        } else {
            pokaziStanje(false, getString(R.string.stanje_povezujem))
        }
    }

    override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) {
        val kje = link.imeSredisca.ifBlank { getString(R.string.naprava_tv) }
        if (link.povezan) pokaziStanje(true, getString(R.string.stanje_povezan, kje))
        narisiNaprave(naprave.filter { it.id != Identiteta.id(this) })
    }

    override fun naNaslov(url: String, naslov: String, od: String) {
        odpriVBrskalniku(url)
        Toast.makeText(this, getString(R.string.poslano_tv), Toast.LENGTH_SHORT).show()
    }

    override fun naBesedilo(besedilo: String, od: String) {
        Toast.makeText(this, getString(R.string.prejeto_besedilo, od) + "\n" + besedilo.take(200), Toast.LENGTH_LONG).show()
    }

    override fun naZavrnitev() {
        // Sredisce je bilo ponastavljeno ali je Safeer OS odstranjen s seznama: vstopimo znova brez kode.
        Identiteta.pozabi(this)
        zahtevajPoverilnice()
    }

    private fun pokaziStanje(povezan: Boolean, besedilo: String) {
        stanjeBesedilo.text = besedilo
        stanjePika.alpha = if (povezan) 1f else 0.35f
    }

    // ------------------------------------------------------------------ Zacni

    private fun narisiZacni() {
        vrstaZacni.removeAllViews()
        val brskalnik = Sorodnik.jeBrskalnikNamescen(this)
        dodajVeliko(R.drawable.ikona_splet, getString(R.string.splet), getString(R.string.splet_opis)) {
            if (brskalnik) odpriVBrskalniku(null) else odpriVBrskalniku("https://safeer.si/browser/tv/")
        }
        dodajVeliko(R.drawable.ikona_datoteke, getString(R.string.datoteke), getString(R.string.datoteke_kmalu)) {
            Toast.makeText(this, getString(R.string.datoteke_kmalu), Toast.LENGTH_SHORT).show()
        }
        dodajVeliko(R.drawable.ikona_link, getString(R.string.link), getString(R.string.link_opis)) {
            odpriLinkVBrskalniku()
        }
        vrstaZacni.getChildAt(0)?.requestFocus()
    }

    private fun dodajVeliko(ikona: Int, naslov: String, opis: String, ob: () -> Unit) {
        val v = LayoutInflater.from(this).inflate(R.layout.kartica_velika, vrstaZacni, false)
        v.findViewById<ImageView>(R.id.ikona).setImageResource(ikona)
        v.findViewById<TextView>(R.id.naslov).text = naslov
        v.findViewById<TextView>(R.id.opis).text = opis
        v.setOnClickListener { ob() }
        v.onFocusChangeListener = fokus
        vrstaZacni.addView(v)
    }

    // ------------------------------------------------------------------ Naprave

    private fun narisiNaprave(naprave: List<LinkOdjemalec.Naprava>) {
        vrstaNaprave.removeAllViews()
        napraveOpomba.visibility = if (naprave.isEmpty()) View.VISIBLE else View.GONE
        for (n in naprave) {
            val v = LayoutInflater.from(this).inflate(R.layout.kartica_naprava, vrstaNaprave, false)
            v.findViewById<TextView>(R.id.ime).text = n.ime.ifBlank { n.id }
            v.findViewById<TextView>(R.id.vloga).text = vrstaNaprave(n)
            v.onFocusChangeListener = fokus
            v.setOnClickListener { odpriLinkVBrskalniku() }
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
            val v = LayoutInflater.from(this).inflate(R.layout.kartica_app, vrstaAplikacije, false)
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

    private fun odpriVBrskalniku(url: String?) {
        val pm = packageManager
        val namera = if (url == null) {
            pm.getLeanbackLaunchIntentForPackage(Sorodnik.PAKET_BRSKALNIKA) ?: pm.getLaunchIntentForPackage(Sorodnik.PAKET_BRSKALNIKA)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                if (Sorodnik.jeBrskalnikNamescen(this@DomovActivity)) setPackage(Sorodnik.PAKET_BRSKALNIKA)
            }
        } ?: return
        try { startActivity(namera.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Throwable) {
            Toast.makeText(this, e.message ?: "?", Toast.LENGTH_SHORT).show()
        }
    }

    /** Stran Safeer Link v brskalniku (seznanitev, naprave, daljinec). Brskalnik pozna dodatek odpri_link. */
    private fun odpriLinkVBrskalniku() {
        val pm = packageManager
        val namera = pm.getLeanbackLaunchIntentForPackage(Sorodnik.PAKET_BRSKALNIKA)
            ?: pm.getLaunchIntentForPackage(Sorodnik.PAKET_BRSKALNIKA)
        if (namera == null) { odpriVBrskalniku("https://safeer.si/browser/tv/"); return }
        namera.putExtra("odpri_link", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(namera) } catch (_: Throwable) { }
    }
}
