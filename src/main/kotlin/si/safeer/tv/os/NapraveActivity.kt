package si.safeer.tv.os

import si.safeer.tv.R

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

/**
 * Naprave v Safeer Linku na svojem zaslonu. Na domacem zaslonu je bila to se ena vrsta kartic in
 * je jemala prostor spletnim aplikacijam, ki jih uporabnik odpira vsak dan; naprave pa pogleda
 * takrat, ko hoce kaj poslati ali odpreti datoteke z racunalnika.
 *
 * Racunalnik, ki deli mape, pelje naravnost v svoje datoteke; vse drugo na stran Safeer Link v
 * brskalniku (seznanitev, daljinec, posiljanje).
 */
class NapraveActivity : OsActivity(), LinkOdjemalec.Poslusalec {

    private class Vrstica(val ikona: Int, val ime: String, val opis: String, val stanje: String, val ob: () -> Unit)

    private lateinit var koren: View
    private lateinit var seznam: ListView
    private lateinit var naslov: TextView
    private lateinit var nadnaslov: TextView
    private lateinit var sporocilo: TextView

    private val link by lazy { LinkUpravitelj.pridobi(this) }
    private val prilagojevalnik = Prilagojevalnik()
    private var vrstice: List<Vrstica> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.os_activity_naprave)
        koren = findViewById(R.id.koren)
        seznam = findViewById(R.id.seznam)
        naslov = findViewById(R.id.naslov)
        nadnaslov = findViewById(R.id.nadnaslov)
        sporocilo = findViewById(R.id.sporocilo)
        nadnaslov.text = getString(R.string.os_link)
        naslov.text = getString(R.string.os_naprave_naslov)
        seznam.adapter = prilagojevalnik
        seznam.setOnItemClickListener { _, _, i, _ -> vrstice.getOrNull(i)?.ob?.invoke() }
    }

    override fun onStart() {
        super.onStart()
        Ozadje.uporabi(this, koren)
        link.dodaj(this)
        narisi(link.naprave)
    }

    override fun onStop() {
        link.odstrani(this)
        super.onStop()
    }

    private fun narisi(naprave: List<LinkOdjemalec.Naprava>) {
        val tuje = naprave.filter { it.id != Identiteta.id(this) }
        val nove = ArrayList<Vrstica>()
        for (n in tuje) {
            val datoteke = n.zmoznosti.contains("files")
            nove.add(Vrstica(
                if (datoteke) R.drawable.os_ikona_racunalnik else R.drawable.os_ikona_naprava,
                // "Safeer Control (janez-pc)" -> "janez-pc": ime programa je ze v podnapisu.
                DatotekeActivity.lepoIme(n.ime).ifBlank { n.id },
                opisNaprave(n),
                getString(if (datoteke) R.string.os_naprave_datoteke else R.string.os_naprave_posiljanje),
            ) {
                if (datoteke) startActivity(Intent(this, DatotekeActivity::class.java)
                    .putExtra(DatotekeActivity.EXTRA_RACUNALNIK, n.id))
                else odpriLinkVBrskalniku()
            })
        }
        // Zadnja vrstica je vedno pot naprej: stran Safeer Link, kjer se naprave seznanijo.
        nove.add(Vrstica(R.drawable.os_ikona_link, getString(R.string.os_naprave_stran),
            getString(R.string.os_naprave_stran_opis), "") { odpriLinkVBrskalniku() })
        vrstice = nove
        prilagojevalnik.notifyDataSetChanged()
        sporocilo.text = when {
            tuje.isNotEmpty() -> ""
            link.jeKrajevni() -> getString(R.string.os_naprave_krajevni)
            link.povezan -> getString(R.string.os_ni_naprav)
            else -> getString(R.string.os_naprave_ni_linka)
        }
        sporocilo.visibility = if (sporocilo.text.isNullOrBlank()) View.GONE else View.VISIBLE
        if (currentFocus == null) seznam.requestFocus()
    }

    private fun opisNaprave(n: LinkOdjemalec.Naprava): String = when {
        n.naslov == "127.0.0.1" || n.id.startsWith("tv-") -> "TV · Safeer Link"
        n.id.startsWith("pc-") -> if (n.id.endsWith("-control")) "PC · Safeer Control" else "PC · Safeer Browser"
        n.id.startsWith("phone-") -> "Safeer Browser · Android"
        else -> n.vloga
    }

    /** Stran Safeer Link v brskalniku (seznanitev, naprave, daljinec). */
    private fun odpriLinkVBrskalniku() {
        val paket = Sosed.brskalnik(this)
        val namera = if (paket != null)
            Intent().setComponent(ComponentName(paket, "si.safeer.tv.MainActivity"))
                .putExtra("iz_safeer_os", packageName)
        else Intent(this, si.safeer.tv.MainActivity::class.java)
        namera.putExtra("odpri_link", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(namera) } catch (_: Throwable) { }
    }

    // ------------------------------------------------------------------ Link

    override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) { narisi(naprave) }
    override fun naStanje(povezan: Boolean, sporocilo: String) { narisi(link.naprave) }
    override fun naNaslov(url: String, naslov: String, od: String) { }
    override fun naBesedilo(besedilo: String, od: String) { }
    override fun naZavrnitev() { }

    private inner class Prilagojevalnik : BaseAdapter() {
        override fun getCount(): Int = vrstice.size
        override fun getItem(i: Int): Any = vrstice[i]
        override fun getItemId(i: Int): Long = i.toLong()
        override fun getView(i: Int, star: View?, roditelj: ViewGroup): View {
            val v = star ?: LayoutInflater.from(this@NapraveActivity)
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
