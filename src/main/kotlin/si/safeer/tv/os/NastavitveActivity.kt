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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.CheckBox

/**
 * Nastavitve Safeer OS: kar uporabnik lahko kadarkoli vklopi in izklopi.
 *
 *  - **Domaci zaslon televizorja**: ali Safeer OS prevzame tipko Domov ali ostane navadna
 *    aplikacija, prek katere zaganjas spletne aplikacije. Oboje je enakovredno; odloci uporabnik.
 *  - **Nacin delovanja**: Safeer Link (naprave, datoteke z racunalnika, daljinec) ali krajevno.
 *  - **Safeer Scit**: filter DNS za ves televizor.
 */
class NastavitveActivity : OsActivity(), LinkOdjemalec.Poslusalec {

    private class Vrstica(val ikona: Int, val ime: String, val opis: String, val stanje: String, val ob: () -> Unit)

    private lateinit var koren: View
    private lateinit var seznam: ListView
    private lateinit var opomba: TextView
    private val link by lazy { LinkUpravitelj.pridobi(this) }
    private var vrstice: List<Vrstica> = emptyList()
    private var hostVPreverjanju = false
    /** Zadnji prebrani podatki o moci; okno s podrobnostmi jih pokaze brez novega cakanja. */
    private var moc: HostPodatki.Podatki? = null
    private var mocOdprta = false
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

    override fun onStart() {
        super.onStart()
        Ozadje.uporabi(this, koren)
        // Podatke o hostu dobimo po Linku; dokler smo v nastavitvah, naj povezava zivi.
        link.dodaj(this)
    }

    override fun onResume() {
        super.onResume()
        // Sistemsko okno je zaprto: nas zaslon se spet pokaze.
        koren.visibility = View.VISIBLE
        narisi()
        // Scit je lahko v sosednji aplikaciji: stanje preberemo prek mostu in vrstico osvezimo.
        Scit.stanje(this) { narisi() }
        osveziHost()
    }

    override fun onStop() {
        link.odstrani(this)
        super.onStop()
    }

    /**
     * Spodnja vrstica: koliko moci je na voljo. Ce je v Linku racunalnik, vprasamo njega
     * (`host.info`); sicer povemo, kaj ima ta televizor - takrat je on ves host, ki ga imamo.
     */
    private fun osveziHost() {
        if (hostVPreverjanju) return          // en ukaz naenkrat: naprave se javijo v rafalu
        hostVPreverjanju = true
        HostPodatki.preberi(this, link) { p ->
            hostVPreverjanju = false
            if (isFinishing) return@preberi
            moc = p
            narisi()
            if (mocOdprta) { mocOdprta = false; pokaziMoc() }
        }
    }

    // ------------------------------------------------------------------ Link

    /** Racunalnik se je javil (ali odsel): vrstica o hostu naj pove novo stanje. */
    override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) { osveziHost() }

    override fun naStanje(povezan: Boolean, sporocilo: String) {}
    override fun naNaslov(url: String, naslov: String, od: String) {}
    override fun naBesedilo(besedilo: String, od: String) {}
    override fun naZavrnitev() {}

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
        val vse = listOf(
            Vrstica(R.drawable.os_ikona_nastavitve, getString(R.string.os_zaganjalnik),
                getString(R.string.os_zaganjalnik_opis), domaciStanje) { preklopiZaganjalnik(jeDomaci || ponujen) },
            Vrstica(R.drawable.os_ikona_naprava, getString(R.string.os_zagon),
                getString(R.string.os_zagon_opis),
                getString(if (ZagonOb.jeVklopljen(this)) R.string.os_vklopljeno else R.string.os_izklopljeno)) { preklopiZagon() },
            Vrstica(R.drawable.os_ikona_link, getString(R.string.os_nacin),
                getString(R.string.os_nacin_kratko),
                getString(if (krajevni) R.string.os_stanje_nacin_krajevni else R.string.os_stanje_nacin_link)) { preklopiNacin(krajevni) },
            Vrstica(R.drawable.os_ikona_racunalnik, getString(R.string.os_host),
                getString(R.string.os_host_opis),
                if (Host.jeOddaljen(this)) Host.gostitelj(this).orEmpty() else getString(R.string.os_host_doma)) { preklopiHost() },
            Vrstica(R.drawable.os_ikona_slika, getString(R.string.os_videz),
                getString(R.string.os_videz_opis), Ozadje.ime(this)) { odpriVidez() },
            Vrstica(R.drawable.os_ikona_nastavitve, getString(R.string.menu_language),
                getString(R.string.os_jezik_opis),
                si.safeer.tv.JezikVmesnika.imeIzbire(this)) { izberiJezik() },
            // Zmogljivost ima svojo vrstico z ikono: prej je bila ena dolga vrstica na dnu
            // zaslona, ki je sekala nastavitve nad sabo in se je odrezala sredi podatka.
            Vrstica(R.drawable.os_ikona_naprava, getString(R.string.os_plosek_preizkus),
                getString(R.string.os_plosek_preizkus_opis), "") {
                startActivity(android.content.Intent(this, PlosekActivity::class.java))
            },
            Vrstica(R.drawable.os_ikona_moc, getString(R.string.os_moc),
                getString(R.string.os_moc_opis),
                moc?.ime.orEmpty().ifBlank { getString(R.string.os_moc_berem) }) { pokaziMoc() },
            Vrstica(R.drawable.os_ikona_scit, getString(R.string.os_scit),
                getString(R.string.os_scit_nastavitev_opis),
                getString(if (Scit.jeVklopljen(this)) R.string.os_vklopljeno else R.string.os_izklopljeno)) { preklopiScit() },
            Vrstica(R.drawable.os_ikona_link, "Internet prek Safeer Linka",
                "Telefon lahko zaupanim Safeer napravam posreduje internet prek Wi-Fi ali dovoljenega mobilnega omrezja.",
                if (getSharedPreferences("safeer_internet_gateway", MODE_PRIVATE).getBoolean("gateway_enabled", false)) getString(R.string.os_vklopljeno) else getString(R.string.os_izklopljeno)) { nastaviInternetGateway() },
            Vrstica(R.drawable.os_ikona_datoteka, getString(R.string.os_pravno),
                getString(R.string.os_pravno_opis), "") { pokaziPravno() },
            Vrstica(R.drawable.os_ikona_naprava, getString(R.string.os_izhod),
                getString(R.string.os_izhod_opis), "") { izhod() },
        )
        // Tablica si deli te nastavitve s televizorjem, a nekatere veljajo samo za televizor:
        // domaci zaslon in zagon ob vklopu TV, preizkus igralnega ploscka (tablica ga ne podpira),
        // krajevni nacin (brez naprav tablica nima cesa pokazati) in izhod v sistem televizorja.
        val skrij = if (packageName.endsWith(".tablet") || packageName.endsWith(".phone")) setOf(getString(R.string.os_zaganjalnik),
            getString(R.string.os_zagon), getString(R.string.os_nacin), getString(R.string.os_plosek_preizkus),
            getString(R.string.os_izhod)) else emptySet()
        vrstice = vse.filter { it.ime !in skrij && (packageName.endsWith(".phone") || it.ime != "Internet prek Safeer Linka") }
        prilagojevalnik.notifyDataSetChanged()
        if (seznam.selectedItemPosition < 0) seznam.requestFocus()
    }

    private fun nastaviInternetGateway() {
        val p = getSharedPreferences("safeer_internet_gateway", MODE_PRIVATE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 8) }
        val gateway = CheckBox(this).apply { text = "Deli internet z zaupanimi Safeer napravami"; isChecked = p.getBoolean("gateway_enabled", false) }
        val mobile = CheckBox(this).apply { text = "Dovoli mobilne podatke (4G/5G)"; isChecked = p.getBoolean("allow_cellular", false) }
        val roaming = CheckBox(this).apply { text = "Dovoli roaming"; isChecked = p.getBoolean("allow_roaming", false) }
        val limit = EditText(this).apply { hint = "Mesecna omejitev mobilnih podatkov v MB (0 = brez omejitve)"; inputType = 2; val b=p.getLong("cellular_limit",0L); if(b>0) setText((b/1024/1024).toString()) }
        box.addView(gateway); box.addView(mobile); box.addView(roaming); box.addView(limit)
        android.app.AlertDialog.Builder(this).setTitle("Safeer Internet Gateway").setView(box)
            .setPositiveButton("Shrani") { _, _ ->
                val mb = limit.text.toString().toLongOrNull()?.coerceAtLeast(0) ?: 0L
                p.edit().putBoolean("gateway_enabled", gateway.isChecked).putBoolean("allow_cellular", mobile.isChecked)
                    .putBoolean("allow_roaming", roaming.isChecked && mobile.isChecked).putLong("cellular_limit", mb * 1024L * 1024L).apply()
                val i = Intent(this, si.safeer.tv.cast.CastReceiverService::class.java).apply { action = si.safeer.tv.cast.CastReceiverService.ACTION_GATEWAY_CHANGED }
                try { startService(i) } catch (_: Throwable) { }
                narisi()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    /** Podrobnosti o moci: svoje okno, vsak podatek v svoji vrstici. */
    private fun pokaziMoc() {
        val p = moc
        if (p == null) {
            // Se nimamo odgovora: povejmo, da beremo, in okno odprimo, ko podatki pridejo.
            mocOdprta = true
            Toast.makeText(this, getString(R.string.os_moc_berem), Toast.LENGTH_SHORT).show()
            osveziHost()
            return
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_moc))
            .setMessage(HostPodatki.podrobnosti(this, p))
            .setPositiveButton(getString(R.string.os_moc_zapri), null)
            .setNeutralButton(getString(R.string.os_moc_osvezi)) { _, _ -> mocOdprta = true; osveziHost() }
            .let { Kontroler.pokazi(it.show()) }
    }

    /** Videz: ozadje in zatemnitev sta svoj zaslon, ker se izbira vidi sele v zivo. */
    /**
     * Pravna pojasnila v sami aplikaciji, ne samo na spletni strani: licenca, odgovornost za to,
     * za kaj se Safeer uporablja, in odkrito povedano, da je pri razvoju sodelovala umetna
     * inteligenca in da koda zato lahko vsebuje napake.
     */
    private fun pokaziPravno() {
        val razlicica = try {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        } catch (_: Throwable) { "" }
        val okno = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_pravno))
            .setMessage(getString(R.string.os_pravno_besedilo, razlicica, packageName))
            .setPositiveButton(getString(R.string.os_moc_zapri), null)
        Kontroler.pokazi(okno.show())
    }

    /**
     * Jezik vmesnika: isti seznam kot v brskalniku. Izbira velja takoj - zaslon narisemo znova,
     * da uporabnik vidi ucinek brez ponovnega zagona aplikacije.
     */
    private fun izberiJezik() {
        val jeziki = si.safeer.tv.JezikVmesnika.JEZIKI
        val oznake = listOf(si.safeer.tv.JezikVmesnika.SAMODEJNO) + jeziki.map { it.first }
        val imena = (listOf(getString(R.string.ui_lang_auto)) + jeziki.map { it.second }).toTypedArray()
        val izbran = si.safeer.tv.JezikVmesnika.izbrani(this)
        val kje = oznake.indexOf(izbran).let { if (it < 0) 0 else it }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.menu_language))
            .setSingleChoiceItems(imena, kje) { okno, i ->
                si.safeer.tv.JezikVmesnika.nastavi(this, oznake[i])
                okno.dismiss()
                recreate()
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    private fun odpriVidez() {
        startActivity(Intent(this, VidezActivity::class.java))
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
                .let { Kontroler.pokazi(it.show()) }
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
            .let { Kontroler.pokazi(it.show()) }
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

    /**
     * Tipke Domov nam televizor morda ne da, prvi zaslon po vklopu pa smo lahko vseeno: ob zagonu
     * sistema se Safeer OS odpre cez ves zaslon. Televizorju s tem nicesar ne spremenimo.
     */
    private fun preklopiZagon() {
        val vklopljen = ZagonOb.jeVklopljen(this)
        if (vklopljen) {
            ZagonOb.nastavi(this, false)
            narisi()
            return
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_zagon))
            .setMessage(getString(R.string.os_zagon_vprasanje))
            .setPositiveButton(getString(R.string.os_vklopi)) { _, _ ->
                ZagonOb.nastavi(this, true)
                narisi()
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
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
                .let { Kontroler.pokazi(it.show()) }
            return
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_host))
            .setMessage(getString(R.string.os_host_vprasanje))
            .setPositiveButton(getString(R.string.os_host_vnesi)) { _, _ -> vnesiNaslovHosta() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
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
            .let { Kontroler.pokazi(it.show()) }
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
            .let { Kontroler.pokazi(it.show()) }
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
            v.findViewById<TextView>(R.id.stanje).apply {
                text = vr.stanje; visibility = if (vr.stanje.isBlank()) View.GONE else View.VISIBLE
            }
            v.setBackgroundResource(R.drawable.os_izbor_vrstice)
            return v
        }
    }
}
