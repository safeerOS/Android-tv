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
        // Scit je lahko v sosednji aplikaciji: stanje preberemo prek mostu in vrstico osvezimo.
        Scit.stanje(this) { narisi() }
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
        val stanjeZaganjalnika = Zaganjalnik.stanje(this)
        val jeDomaci = stanjeZaganjalnika == Zaganjalnik.IZBRAN
        val ponujen = stanjeZaganjalnika != Zaganjalnik.IZKLOPLJEN
        val domaciStanje = when (stanjeZaganjalnika) {
            Zaganjalnik.IZBRAN -> getString(R.string.os_zaganjalnik_izbran)
            // Vklopljeno je, a televizor tipke Domov ne da naprej (proizvajalcev prestreznik).
            Zaganjalnik.TELEVIZOR_OBDRZI -> getString(R.string.os_zaganjalnik_televizor_obdrzi)
            else -> getString(R.string.os_izklopljeno)
        }
        val krajevni = link.jeKrajevni()
        vrstice = listOf(
            Vrstica(R.drawable.os_ikona_nastavitve, getString(R.string.os_zaganjalnik),
                getString(R.string.os_zaganjalnik_opis), domaciStanje) { preklopiZaganjalnik(jeDomaci || ponujen) },
            Vrstica(R.drawable.os_ikona_link, getString(R.string.os_nacin),
                getString(R.string.os_nacin_kratko),
                getString(if (krajevni) R.string.os_stanje_nacin_krajevni else R.string.os_stanje_nacin_link)) { preklopiNacin(krajevni) },
            Vrstica(R.drawable.os_ikona_racunalnik, getString(R.string.os_host),
                getString(R.string.os_host_opis),
                if (Host.jeOddaljen(this)) Host.gostitelj(this).orEmpty() else getString(R.string.os_host_doma)) { preklopiHost() },
            Vrstica(R.drawable.os_ikona_scit, getString(R.string.os_scit),
                getString(R.string.os_scit_nastavitev_opis),
                getString(if (Scit.jeVklopljen(this)) R.string.os_vklopljeno else R.string.os_izklopljeno)) { preklopiScit() },
            Vrstica(R.drawable.os_ikona_naprava, getString(R.string.os_izhod),
                getString(R.string.os_izhod_opis), "") { izhod() },
        )
        prilagojevalnik.notifyDataSetChanged()
        if (seznam.selectedItemPosition < 0) seznam.requestFocus()
    }

    /**
     * Vklop ne more biti tih: domaci zaslon izbere sistem, ne aplikacija. Mi moznost samo ponudimo
     * in odpremo sistemsko okno; izklop pa naredimo sami, da je pot nazaj vedno pri roki.
     */
    private fun preklopiZaganjalnik(jeVklopljen: Boolean) {
        // Vklopljeno, a televizor domacega zaslona ne da: povejmo naravnost, kaj se dogaja,
        // in ponudimo izklop - brez tega uporabnik misli, da je napaka pri nas.
        if (jeVklopljen && Zaganjalnik.stanje(this) == Zaganjalnik.TELEVIZOR_OBDRZI) {
            val kdo = Zaganjalnik.domaciZaslon(this).orEmpty()
            android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.os_zaganjalnik))
                .setMessage(getString(R.string.os_zaganjalnik_televizor_obdrzi_opis, kdo))
                .setPositiveButton(getString(R.string.os_zaganjalnik_izklopi_kratko)) { _, _ ->
                    Zaganjalnik.opusti(this)
                    narisi()
                }
                .setNeutralButton(getString(R.string.os_zaganjalnik_sistemske)) { _, _ ->
                    sistemskoOkno { Zaganjalnik.odpriSistemskoIzbiro(this) }
                }
                .setNegativeButton(getString(R.string.os_preklici), null)
                .show()
            return
        }
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

    // ------------------------------------------------------------------ host (kje je racunalniska moc)

    /**
     * Host je privzeto doma. Kdor ima svoj streznik zunaj hise (najet ali v oblaku), ga tu vpise;
     * seznanitev je enaka kot doma - koda in pripeto potrdilo - povemo pa mu naravnost, da v tem
     * nacinu promet zapusti domace omrezje.
     */
    private fun preklopiHost() {
        // Uporabnik je seznanitev ze zacel in sel po kodo na strezniku: ko se vrne, mora
        // nadaljevati tam, kjer je ostal - ne zaceti znova z novo kodo.
        val vTeku = Host.naslov(this)
        if (!Host.jeOddaljen(this) && vTeku != null && si.safeer.tv.cast.HubPairing.cakaNaKodo()) {
            vnesiKodoHosta(vTeku)
            return
        }
        if (Host.jeOddaljen(this)) {
            android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.os_host))
                .setMessage(getString(R.string.os_host_oddaljen_vprasanje, Host.gostitelj(this).orEmpty()))
                .setPositiveButton(getString(R.string.os_host_domov)) { _, _ ->
                    Host.domov(this)
                    link.ponovnoPoveziSe()
                    Toast.makeText(this, getString(R.string.os_host_spet_doma), Toast.LENGTH_LONG).show()
                    narisi()
                }
                .setNegativeButton(getString(R.string.os_preklici), null)
                .show()
            return
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_host))
            .setMessage(getString(R.string.os_host_vprasanje))
            .setPositiveButton(getString(R.string.os_host_vnesi)) { _, _ -> vnesiNaslovHosta() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .show()
    }

    private fun vnesiNaslovHosta() {
        val vnos = android.widget.EditText(this).apply {
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.os_host_naslov_namig)
            setPadding(40, 30, 40, 30)
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_host_naslov))
            .setMessage(getString(R.string.os_host_naslov_opis))
            .setView(vnos)
            .setPositiveButton(getString(R.string.os_naprej)) { _, _ ->
                val url = Host.izVnosa(vnos.text?.toString().orEmpty())
                if (url == null) {
                    Toast.makeText(this, getString(R.string.os_host_naslov_napaka), Toast.LENGTH_LONG).show()
                    vnesiNaslovHosta()
                } else {
                    zacniSeznanitevHosta(url)
                }
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .show()
        vnos.requestFocus()
    }

    private fun zacniSeznanitevHosta(url: String) {
        // Ce je od prej odprta seznanitev (uporabnik je vmes zapustil zaslon), jo opustimo: stara
        // prijava bi novo tiho zavrnila in uporabnik bi cakal na okno, ki ne bi prislo.
        si.safeer.tv.cast.HubPairing.prekini()
        Host.zapomniNaslov(this, url)
        Toast.makeText(this, getString(R.string.os_host_povezujem), Toast.LENGTH_SHORT).show()
        si.safeer.tv.cast.HubPairing.pair(this, url, Identiteta.id(this), "Safeer OS (" + android.os.Build.MODEL + ")",
            { _, _ -> if (!isFinishing) vnesiKodoHosta(url) },
            { uspelo -> if (!uspelo && !isFinishing) Toast.makeText(this, getString(R.string.os_host_ni_odgovora), Toast.LENGTH_LONG).show() })
    }

    private fun vnesiKodoHosta(url: String) {
        val vnos = android.widget.EditText(this).apply {
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.os_host_koda_namig)
            setPadding(40, 30, 40, 30)
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_host_koda))
            .setMessage(getString(R.string.os_host_koda_opis))
            .setView(vnos)
            .setPositiveButton(getString(R.string.os_host_poveziSe)) { _, _ -> potrdiKodoHosta(url, vnos.text?.toString().orEmpty()) }
            .setNegativeButton(getString(R.string.os_preklici)) { _, _ -> si.safeer.tv.cast.HubPairing.prekini() }
            .show()
        vnos.requestFocus()
    }

    private fun potrdiKodoHosta(url: String, koda: String) {
        si.safeer.tv.cast.HubPairing.potrdiKodo(this, koda, Identiteta.id(this)) { uspelo, napaka ->
            if (isFinishing) return@potrdiKodo
            val izid = si.safeer.tv.cast.HubPairing.zadnjaSeznanitev
            if (uspelo && izid != null) {
                Host.shrani(this, url, izid.zeton, izid.odtis, izid.hubId)
                link.ponovnoPoveziSe()
                Toast.makeText(this, getString(R.string.os_host_povezan, Host.gostitelj(this).orEmpty()), Toast.LENGTH_LONG).show()
                narisi()
                return@potrdiKodo
            }
            val sporocilo = when (napaka) {
                "napacna_koda" -> getString(R.string.os_host_napacna_koda)
                "prevec_poskusov" -> getString(R.string.os_host_prevec_poskusov)
                else -> getString(R.string.os_host_ni_odgovora)
            }
            Toast.makeText(this, sporocilo, Toast.LENGTH_LONG).show()
            if (napaka == "napacna_koda") vnesiKodoHosta(url)
        }
    }

    /**
     * Scit je na televizorju eden sam. Kadar je Safeer Browser namescen, je njegov in tu ga samo
     * preklopimo prek mostu; sistemsko okno z dovoljenjem za VPN sme odpreti samo lastnik.
     */
    private fun preklopiScit() {
        if (Scit.jeVklopljen(this)) {
            Scit.izklopi(this) { narisi() }
            return
        }
        Scit.vklopi(this) { s ->
            if (s.potrebujeOkno) sistemskoOkno { Scit.odpriVklop(this) } else narisi()
        }
    }

    /**
     * Izhod v Android. Nicesar ne izklopimo: uporabnik samo pogleda domaci zaslon televizorja in se
     * lahko takoj vrne. Ce hoce Safeer OS odstraniti z domacega zaslona za stalno, to naredi z
     * vrstico "Domaci zaslon televizorja" zgoraj.
     */
    private fun izhod() {
        if (!Zaganjalnik.izhodVAndroid(this)) {
            Toast.makeText(this, getString(R.string.os_izhod_ni), Toast.LENGTH_LONG).show()
            return
        }
        finish()
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
