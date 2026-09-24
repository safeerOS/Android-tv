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
import android.widget.Toast
import si.safeer.tv.link.DatotekeStreznik

/**
 * Naprave v Safeer Linku na svojem zaslonu. Na domacem zaslonu je bila to se ena vrsta kartic in
 * je jemala prostor spletnim aplikacijam, ki jih uporabnik odpira vsak dan; naprave pa pogleda
 * takrat, ko hoce kaj poslati ali odpreti datoteke z racunalnika.
 *
 * Racunalnik, ki deli mape, pelje naravnost v svoje datoteke; vse drugo na stran Safeer Link v
 * brskalniku (seznanitev, daljinec, posiljanje). Dolg pritisk na napravo (ali OK na vrstici »Ta
 * naprava«) jo preimenuje: ime hrani sredisce in ga vidijo vse naprave.
 */
class NapraveActivity : OsActivity(), LinkOdjemalec.Poslusalec {

    private class Vrstica(val ikona: Int, val ime: String, val opis: String, val stanje: String, val id: String = "", val ob: () -> Unit)

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
        // Dolg pritisk (OK na daljincu, prst na tablici): preimenuj napravo v vrstici.
        seznam.setOnItemLongClickListener { _, _, i, _ ->
            val v = vrstice.getOrNull(i)
            if (v != null && v.id.isNotBlank()) { preimenuj(v.id, v.ime); true } else false
        }
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
        val jaz = naprave.firstOrNull { it.id == Identiteta.id(this) }
        val tuje = naprave.filter { it.id != Identiteta.id(this) }
        val nove = ArrayList<Vrstica>()
        // Ta naprava: ime, kot ga vidijo druge naprave; OK jo preimenuje.
        if (jaz != null) nove.add(Vrstica(ikonaNaprave(jaz.platforma), jaz.ime.ifBlank { jaz.id },
            getString(R.string.os_naprave_ta), getString(R.string.os_naprave_preimenuj_kratko), jaz.id) { preimenuj(jaz.id, jaz.ime) })
        for (n in tuje) {
            val datoteke = n.zmoznosti.contains("files")
            val lepo = DatotekeActivity.lepoIme(n.ime).ifBlank { n.id }
            nove.add(Vrstica(
                if (datoteke) R.drawable.os_ikona_racunalnik else ikonaNaprave(n.platforma),
                lepo,
                opisNaprave(n),
                getString(if (datoteke) R.string.os_naprave_datoteke else R.string.os_naprave_preimenuj_kratko),
                n.id,
            ) {
                if (datoteke) izbiraNaprave(n) else preimenuj(n.id, n.ime)
            })
        }
        // Nova naprava: prijavno okno (prikaze QR kodo in 6-mestno kodo za povezavo)
        nove.add(Vrstica(
            R.drawable.os_ikona_naprava,
            getString(R.string.os_naprave_povezi),
            getString(R.string.os_naprave_povezi_opis),
            "",
            ""
        ) { startActivity(Intent(this, PrijavaActivity::class.java)) })

        // Deljenje datotek s te naprave v Safeer Linku (vklopljeno / izklopljeno)
        val vklopljeno = DatotekeStreznik.vklopljeno(this)
        nove.add(Vrstica(
            R.drawable.os_ikona_racunalnik,
            getString(R.string.os_naprave_deljenje_datotek),
            getString(R.string.os_naprave_deljenje_opis),
            getString(if (vklopljeno) R.string.os_vklopljeno else R.string.os_izklopljeno),
            ""
        ) {
            val novoStanje = !vklopljeno
            DatotekeStreznik.nastavi(this, novoStanje)
            if (novoStanje && !DatotekeStreznik.imamoDovoljenje(this) && android.os.Build.VERSION.SDK_INT >= 23) {
                requestPermissions(DatotekeStreznik.dovoljenja(), 101)
            }
            Toast.makeText(this, getString(if (novoStanje) R.string.os_naprave_deljenje_vklopljeno else R.string.os_naprave_deljenje_izklopljeno), Toast.LENGTH_SHORT).show()
            narisi(link.naprave)
        })
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

    /** Ikona po vrsti naprave: telefon je telefon, tablica in racunalniski zaslon zaslon, televizor televizor. */
    private fun ikonaNaprave(platforma: String): Int = when (platforma) {
        "phone" -> R.drawable.os_ikona_telefon
        "tablet" -> R.drawable.os_ikona_zaslon
        "linux", "windows" -> R.drawable.os_ikona_racunalnik
        else -> R.drawable.os_ikona_naprava
    }

    private fun opisNaprave(n: LinkOdjemalec.Naprava): String = when {
        // Platforma, kot jo pove naprava (Protocol v1); sredisce je lahko tudi racunalnik.
        n.platforma == "linux" -> if (n.id.endsWith("-control")) "PC · Safeer Control" else "PC · Safeer Browser"
        n.platforma == "tv" -> "TV · Safeer Link"
        n.platforma == "tablet" -> "Tablica · Safeer OS"
        n.platforma == "phone" -> "Safeer Browser · Android"
        n.naslov == "127.0.0.1" || n.id.startsWith("tv-") -> "TV · Safeer Link"
        n.id.startsWith("pc-") -> if (n.id.endsWith("-control")) "PC · Safeer Control" else "PC · Safeer Browser"
        n.id.startsWith("phone-") -> "Safeer Browser · Android"
        else -> n.vloga
    }

    /**
     * Novo ime naprave: shrani ga sredisce in ga vidijo vse naprave (telefon, racunalnik, televizor).
     * Prazno ime vrne prvotnega. Ime racunalnika pokazemo brez imena programa, shranimo pa celo.
     */
    private fun preimenuj(id: String, trenutno: String) {
        val vnos = android.widget.EditText(this).apply {
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = getString(R.string.os_naprave_preimenuj_namig)
            setText(trenutno)
            setSelection(text?.length ?: 0)
            setPadding(40, 30, 40, 30)
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_naprave_preimenuj))
            .setMessage(getString(R.string.os_naprave_preimenuj_opis))
            .setView(vnos)
            .setPositiveButton(getString(R.string.os_naprave_shrani)) { _, _ ->
                link.preimenuj(id, vnos.text?.toString().orEmpty()) { ok, _ ->
                    if (isFinishing) return@preimenuj
                    Toast.makeText(this, getString(if (ok) R.string.os_naprave_preimenovano else R.string.os_naprave_preimenovanje_napaka),
                        Toast.LENGTH_SHORT).show()
                    if (ok) narisi(link.naprave)
                }
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    /** Izbira za tujo napravo z deljenimi mapami: odpri datoteke ali preimenuj napravo. */
    private fun izbiraNaprave(n: LinkOdjemalec.Naprava) {
        val moznosti = arrayOf(
            getString(R.string.os_naprave_odpri_datoteke),
            getString(R.string.os_naprave_preimenuj)
        )
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(DatotekeActivity.lepoIme(n.ime).ifBlank { n.id })
            .setItems(moznosti) { _, i ->
                when (i) {
                    0 -> startActivity(Intent(this, DatotekeActivity::class.java).putExtra(DatotekeActivity.EXTRA_RACUNALNIK, n.id))
                    1 -> preimenuj(n.id, n.ime)
                }
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        narisi(link.naprave)
    }

    /** Stran Safeer Link v brskalniku (seznanitev, naprave, daljinec). */
    private fun odpriLinkVBrskalniku() {
        val paket = Sosed.brskalnik(this)
        val namera = if (paket != null)
            Intent().setComponent(ComponentName(paket, "si.safeer.tv.MainActivity"))
                .putExtra("iz_safeer_os", packageName)
        else Intent(this, si.safeer.tv.MainActivity::class.java)
        namera.putExtra("odpri_link", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Link je del Safeer OS: stran naj ima ozadje sistema in se ob zaprtju vrne sem.
        namera.putExtra("iz_safeer_os", packageName)
            .putExtra("os_ozadje", Ozadje.izbrana(this).oznaka)
            .putExtra("os_zatemnitev", Ozadje.zatemnitev(this))
            .putExtra("os_vrni", "naprave")
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
