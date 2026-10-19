package si.safeer.tv.os

import si.safeer.tv.R

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.CheckBox
import si.safeer.tv.os.SistemskeNastavitve.Skupina

/**
 * Nastavitve Safeer OS: kar uporabnik lahko kadarkoli vklopi in izklopi, in celotne sistemske nastavitve Androida.
 *
 *  - **Safeer OS**: domaci zaslon televizorja, nacin delovanja, Scit, Link, videz, jezik, vrstica in lupa ...
 *  - **Sistem** (lastnik, 19. 10. 2026): Wi-Fi, mobilno omrezje, Bluetooth, zaslon, zvok, obvestila, aplikacije,
 *    shramba, baterija, lokacija, varnost, racuni, dostopnost, jezik, datum, razvijalci, o napravi; na televizorju
 *    se vhodi, daljinci, ohranjevalnik in meniji slike. Vsaka postavka odpre sistemski zaslon in samo, ce ga naprava
 *    res ima ([SistemskeNastavitve]); Nazaj vrne sem.
 *  - Iskanje najde nastavitev po imenu ali opisu v vseh jezikih aplikacije; skupine imajo naslove.
 */
class NastavitveActivity : OsActivity(), LinkOdjemalec.Poslusalec {

    /**
     * Vrstica seznama: naslov skupine ([glava]), nastavitev ali sporocilo, da ni zadetkov ([ob] == null in ne glava).
     * [iskanje] so ime in opis v vseh jezikih aplikacije.
     */
    private class Vrstica(
        val ikona: Int,
        val ime: String,
        val opis: String,
        val stanje: String,
        val skupina: Skupina,
        val iskanje: List<String> = emptyList(),
        val glava: Boolean = false,
        val ob: (() -> Unit)? = null,
    )

    private lateinit var koren: View
    private lateinit var seznam: ListView
    private lateinit var iskanje: EditText
    private lateinit var pocisti: View
    private val link by lazy { LinkUpravitelj.pridobi(this) }
    /** Vse nastavitve (brez glav), kot jih naprava ponuja; [prikaz] je filtriran seznam z naslovi skupin. */
    private var vse: List<Vrstica> = emptyList()
    private var prikaz: List<Vrstica> = emptyList()
    /** Sistemske nastavitve, ki jih ta naprava res odpre (poiscemo enkrat, v ozadju). */
    @Volatile private var sistemske: List<Vrstica> = emptyList()
    private val jeziki by lazy { SistemskeNastavitve.Jeziki(this) }
    private var hostVPreverjanju = false
    /** Zadnji prebrani podatki o moci; okno s podrobnostmi jih pokaze brez novega cakanja. */
    private var moc: HostPodatki.Podatki? = null
    private var mocOdprta = false
    private val prilagojevalnik = Prilagojevalnik()
    private val tv by lazy { Naprava.jeTelevizor(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(StranskaVrstica.ovij(this, R.layout.os_activity_nastavitve,
            StranskaVrstica.Razdelek.NASTAVITVE))
        koren = findViewById(R.id.koren)
        seznam = findViewById(R.id.seznam)
        iskanje = findViewById(R.id.iskanje)
        pocisti = findViewById(R.id.pocisti)
        seznam.adapter = prilagojevalnik
        seznam.setOnItemClickListener { _, _, i, _ -> prikaz.getOrNull(i)?.ob?.invoke() }
        pripraviIskanje()
        poisciSistemske()
    }

    /**
     * Iskalno polje: filtrira sproti. Na televizorju fokus polja ne odpre tipkovnice (bi pokrila pol zaslona), odpre jo
     * OK; natipkana crka kjerkoli na zaslonu gre v polje ([dispatchKeyEvent]).
     */
    private fun pripraviIskanje() {
        if (tv) iskanje.showSoftInputOnFocus = false
        iskanje.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pocisti.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                filtriraj()
            }
        })
        iskanje.setOnEditorActionListener { v, akcija, _ ->
            if (akcija == EditorInfo.IME_ACTION_SEARCH || akcija == EditorInfo.IME_ACTION_DONE) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                // Prvi zadetek dobi fokus: z daljincem ali tipkovnico takoj OK.
                if (prikaz.any { it.ob != null }) seznam.requestFocus()
                true
            } else false
        }
        if (tv) iskanje.setOnKeyListener { v, koda, d ->
            if (d.action == KeyEvent.ACTION_DOWN && (koda == KeyEvent.KEYCODE_DPAD_CENTER || koda == KeyEvent.KEYCODE_ENTER)) {
                (v as EditText).showSoftInputOnFocus = true
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0)
                true
            } else false
        }
        pocisti.setOnClickListener { iskanje.setText(""); iskanje.requestFocus() }
    }

    /** Natipkana crka (tipkovnica telefona, daljinca ali TV) zacne iskanje, ne glede na to, kje je fokus. */
    override fun dispatchKeyEvent(dogodek: KeyEvent): Boolean {
        if (dogodek.action == KeyEvent.ACTION_DOWN && currentFocus !is EditText &&
            DomacaLupa.znakTipke(dogodek)) {
            iskanje.requestFocus()
            iskanje.append(String(Character.toChars(dogodek.unicodeChar)))
            return true
        }
        return super.dispatchKeyEvent(dogodek)
    }

    /** Sistemske nastavitve: ~50 poizvedb v upravitelju paketov in prevodi - v ozadju, da se zaslon odpre takoj. */
    private fun poisciSistemske() {
        if (si.safeer.tv.BuildConfig.FLAVOR == "predvajalnik") return
        Thread {
            val najdene = try {
                SistemskeNastavitve.naVoljo(this).map { (v, namera) ->
                    Vrstica(v.ikona, getString(v.ime), getString(v.opis), "", v.skupina,
                        jeziki.vsa(v.ime) + jeziki.vsa(v.opis)) { odpriSistemsko(namera) }
                }
            } catch (e: Throwable) {
                android.util.Log.w("SafeerNastavitve", "Sistemskih nastavitev ni bilo mogoce prebrati: ${e.message}")
                emptyList()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                sistemske = najdene
                narisi()
            }
        }.apply { name = "SafeerNastavitve"; isDaemon = true }.start()
    }

    private fun odpriSistemsko(namera: Intent) {
        try { sistemskoOkno { startActivity(namera) } }
        catch (_: Throwable) {
            koren.visibility = View.VISIBLE
            Toast.makeText(this, R.string.nast_ni_odprto, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        Ozadje.uporabi(this, koren)
        // Podatke o hostu dobimo po Linku; dokler smo v nastavitvah, naj povezava zivi.
        link.dodaj(this)
    }

    /** Okno vloge domacega zaslona: ce je uporabnik rekel ne, ostane vse kot prej (alias spet onemogocen). */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode == Zaganjalnik.ZAHTEVA_VLOGE) {
            if (!Zaganjalnik.jeIzbran(this)) Zaganjalnik.opusti(this)
            return
        }
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onResume() {
        super.onResume()
        // Sistemsko okno je zaprto: nas zaslon se spet pokaze.
        koren.visibility = View.VISIBLE
        // Nevidna vsebina fokusa ne more imeti, zato ga je Android dal meniju (Domov): daljinec je bil po vrnitvi iz
        // sistemske nastavitve v meniju namesto na isti vrstici (TV, 19. 10. 2026). Seznam obdrzi izbrano vrstico.
        if (fokusNaSeznamu) { fokusNaSeznamu = false; seznam.requestFocus() }
        narisi()
        // Scit je lahko v sosednji aplikaciji: stanje preberemo prek mostu in vrstico osvezimo.
        Scit.stanje(this) { narisi() }
        // Posodobitve: tiha preverba (najvec na 6 h), vrstica se osvezi.
        if (!Posodobitve.nadaljujCeCaka(this)) Posodobitve.preveri(this) { _, _ -> if (!isFinishing) narisi() }
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
        fokusNaSeznamu = seznam.hasFocus()
        koren.visibility = View.INVISIBLE
        odpri()
    }

    /** Pred odprtjem sistemskega okna je bil fokus na seznamu (daljinec): ob vrnitvi gre spet tja. */
    private var fokusNaSeznamu = false

    /** Posodobitve: takoj preveri safeer.si; novo razlicico prenese in preda namescanju, sicer pove, da je najnovejsa. */
    private fun posodobi() {
        Toast.makeText(this, R.string.os_posodobitev_preverjam, Toast.LENGTH_SHORT).show()
        Posodobitve.preveri(this, vsiljeno = true) { nova, napaka ->
            if (isFinishing) return@preveri
            narisi()
            when {
                nova != null -> Posodobitve.ponudiZOpisom(this, nova)
                napaka != null -> Toast.makeText(this, R.string.os_posodobitev_napaka, Toast.LENGTH_LONG).show()
                else -> Toast.makeText(this, getString(R.string.os_posodobitev_najnovejsa, si.safeer.tv.BuildConfig.VERSION_NAME), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Sistemske nastavitve obvestil za Safeer OS (od Androida 8 stran aplikacije, prej splosne). */
    private fun odpriObvestila() {
        val i = if (android.os.Build.VERSION.SDK_INT >= 26)
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
        else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(android.net.Uri.parse("package:$packageName"))
        try { sistemskoOkno { startActivity(i) } } catch (_: Throwable) { koren.visibility = View.VISIBLE }
    }

    /** Nastavitev Safeer OS: ime in opis iz virov (za iskanje v vseh jezikih), stanje in dejanje. */
    private fun safeer(ikona: Int, ime: Int, opis: Int, stanje: String, besedilo: String = getString(opis), ob: () -> Unit) =
        Pair(ime, Vrstica(ikona, getString(ime), besedilo, stanje, Skupina.SAFEER, jeziki.vsa(ime) + jeziki.vsa(opis), ob = ob))

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
        fun vklop(da: Boolean) = getString(if (da) R.string.os_vklopljeno else R.string.os_izklopljeno)
        val krajevni = link.jeKrajevni()
        val naDotik = packageName.endsWith(".tablet") || packageName.endsWith(".phone")
        val predvajalnik = si.safeer.tv.BuildConfig.FLAVOR == "predvajalnik"
        val shramba = getSharedPreferences(StranskaVrstica.SHRAMBA, MODE_PRIVATE)
        // Domaci zaslon naprave (HOME) je samo na televizorju (lastnik, 18. 10. 2026): telefon in tablica ostaneta
        // navadni aplikaciji z ikono v predalu, brez vloge zaganjalnika in brez znack obvestil zanj.
        val safeer = listOfNotNull(
            if (naDotik) null
            else safeer(R.drawable.os_ikona_nastavitve, R.string.os_zaganjalnik, R.string.os_zaganjalnik_opis, domaciStanje) {
                preklopiZaganjalnik(jeDomaci || ponujen) },
            safeer(R.drawable.os_ikona_naprava, R.string.os_zagon, R.string.os_zagon_opis, vklop(ZagonOb.jeVklopljen(this))) { preklopiZagon() },
            safeer(R.drawable.os_ikona_link, R.string.os_nacin, R.string.os_nacin_kratko,
                getString(if (krajevni) R.string.os_stanje_nacin_krajevni else R.string.os_stanje_nacin_link)) { preklopiNacin(krajevni) },
            safeer(R.drawable.os_ikona_racunalnik, R.string.os_host, R.string.os_host_opis,
                if (Host.jeOddaljen(this)) Host.gostitelj(this).orEmpty() else getString(R.string.os_host_doma)) { preklopiHost() },
            safeer(R.drawable.os_ikona_slika, R.string.os_videz, R.string.os_videz_opis, Ozadje.ime(this)) { odpriVidez() },
            safeer(R.drawable.os_ikona_nastavitve, R.string.menu_language, R.string.os_jezik_opis,
                si.safeer.tv.JezikVmesnika.imeIzbire(this)) { izberiJezik() },
            // Stranska vrstica na dotik: imena ob ikonah na sirokem zaslonu (isto kot gumb menija na vrhu vrstice).
            if (tv || predvajalnik) null
            else safeer(R.drawable.os_ikona_meni, R.string.nast_imena, R.string.nast_imena_opis,
                vklop(shramba.getBoolean(StranskaVrstica.KLJUC_IMENA, false))) {
                val novo = !shramba.getBoolean(StranskaVrstica.KLJUC_IMENA, false)
                // Vrstica tega zaslona se spremeni takoj (nastaviImena tudi shrani izbiro); drugi zasloni ob vrnitvi.
                StranskaVrstica.na(this)?.nastaviImena(novo) ?: shramba.edit().putBoolean(StranskaVrstica.KLJUC_IMENA, novo).apply()
                narisi()
            },
            // Iskalna lupa domacega zaslona: kompaktna (privzeto) ali vedno odprta.
            if (predvajalnik) null
            else safeer(R.drawable.os_ikona_isci, R.string.nast_lupa, R.string.nast_lupa_opis, vklop(DomacaLupa.vednoOdprta(this))) {
                DomacaLupa.nastaviVednoOdprta(this, !DomacaLupa.vednoOdprta(this)); narisi()
            },
            // Zmogljivost ima svojo vrstico z ikono: prej je bila ena dolga vrstica na dnu
            // zaslona, ki je sekala nastavitve nad sabo in se je odrezala sredi podatka.
            safeer(R.drawable.os_ikona_naprava, R.string.os_plosek_preizkus, R.string.os_plosek_preizkus_opis, "") {
                startActivity(android.content.Intent(this, PlosekActivity::class.java))
            },
            safeer(R.drawable.os_ikona_moc, R.string.os_moc, R.string.os_moc_opis,
                moc?.ime.orEmpty().ifBlank { getString(R.string.os_moc_berem) }) { pokaziMoc() },
            safeer(R.drawable.os_ikona_scit, R.string.os_scit, R.string.os_scit_nastavitev_opis, vklop(Scit.jeVklopljen(this))) { preklopiScit() },
            // Obvestila (telefon, tablica): brez njih "Poslji na napravo" in sporocila z drugih naprav v ozadju niso vidni;
            // dovoljenje, ki ga je uporabnik enkrat zavrnil, sistem ne ponudi vec - zato pot do nastavitev aplikacije tu.
            safeer(R.drawable.os_ikona_zvonec, R.string.os_obvestila, R.string.os_obvestila_opis,
                vklop(si.safeer.tv.link.Obvestila.dovoljena(this))) { odpriObvestila() },
            safeer(R.drawable.os_ikona_posodobi, R.string.os_posodobitve, R.string.os_posodobitve_opis, Posodobitve.stanje(this),
                Posodobitve.besedilo(this, R.string.os_posodobitve_opis)) { posodobi() },
            safeer(R.drawable.os_ikona_link, R.string.os_gl_ime, R.string.os_gl_opis,
                vklop(si.safeer.tv.link.GlobalLink.vklopljen(this))) { nastaviGlobalLink() },
            // Ozji krog zaupanja (kot na racunalniku): samo naprave v njem vidijo vsebine te naprave in samo njihove
            // programe, datoteke in zaslon vidis tu. Telefon zunaj kroga (npr. druzinski) ga tu ustvari in ureja.
            safeer(R.drawable.os_ikona_link, R.string.os_ozji_krog, R.string.os_ozji_krog_opis,
                getString(R.string.os_ozji_krog_stevilo, OzjiKrog.stevilo(this, link))) { OzjiKrog.uredi(this, link) { narisi() } },
            // »Pomagaj drugim napravam v Linku pri povezavi« (link/PomocLinka; isto kot na racunalniku).
            safeer(R.drawable.os_ikona_link, R.string.os_pomoc_ime, R.string.os_pomoc_opis,
                vklop(si.safeer.tv.link.PomocLinka.vklopljena(this))) {
                si.safeer.tv.link.PomocLinka.shrani(this, !si.safeer.tv.link.PomocLinka.vklopljena(this))
                narisi()
            },
            safeer(R.drawable.os_ikona_link, R.string.os_ig_naslov, R.string.os_ig_opis,
                vklop(getSharedPreferences(si.safeer.tv.link.AndroidApplicationGateway.PREFS, MODE_PRIVATE).getBoolean("gateway_enabled", false))) {
                nastaviInternetGateway() },
            safeer(R.drawable.os_ikona_datoteka, R.string.os_pravno, R.string.os_pravno_opis, "") { pokaziPravno() },
            safeer(R.drawable.os_ikona_naprava, R.string.os_izhod, R.string.os_izhod_opis, "") { izhod() },
        )
        // Tablica si deli te nastavitve s televizorjem, a nekatere veljajo samo za televizor:
        // domaci zaslon in zagon ob vklopu TV, preizkus igralnega ploscka (tablica ga ne podpira),
        // krajevni nacin (brez naprav tablica nima cesa pokazati) in izhod v sistem televizorja.
        val skrij = HashSet<Int>()
        if (naDotik) skrij += listOf(R.string.os_zaganjalnik, R.string.os_zagon, R.string.os_nacin, R.string.os_plosek_preizkus, R.string.os_izhod)
        // Televizor tihih obvestil aplikacij ne kaze: vrstica Obvestila le na telefonu in tablici (tudi v Predvajalniku).
        if (packageManager.hasSystemFeature("android.software.leanback")) skrij += R.string.os_obvestila
        // Safeer Predvajalnik je samostojen program, ne domaci zaslon: brez nastavitev zaganjalnika,
        // racunalniskega hosta, igralnega ploscka, moci, Scita in izhoda v sistem (preizkus 1. 10. 2026).
        if (predvajalnik) skrij += listOf(R.string.os_zaganjalnik, R.string.os_zagon, R.string.os_host, R.string.os_plosek_preizkus,
            R.string.os_moc, R.string.os_scit, R.string.os_izhod)
        // Internet prek Linka deli samo telefon (mobilni podatki).
        if (!packageName.endsWith(".phone")) skrij += R.string.os_ig_naslov
        vse = safeer.filter { it.first !in skrij }.map { it.second } + sistemske
        filtriraj()
    }

    /** Seznam po skupinah z naslovi; ob iskanju samo ustrezne nastavitve (ime ali opis v kateremkoli jeziku). */
    private fun filtriraj() {
        val poizvedba = if (::iskanje.isInitialized) iskanje.text.toString() else ""
        val zadetki = vse.filter { VrsticaPravila.ujema(poizvedba, it.iskanje + it.ime + it.opis) }
        val nov = ArrayList<Vrstica>()
        for (s in Skupina.values()) {
            val v = zadetki.filter { it.skupina == s }
            if (v.isEmpty()) continue
            nov += Vrstica(0, getString(s.naslov), "", "", s, glava = true)
            nov += v
        }
        if (nov.isEmpty()) nov += Vrstica(0, getString(R.string.nast_ni_zadetkov), "", "", Skupina.SAFEER)
        prikaz = nov
        prilagojevalnik.notifyDataSetChanged()
        if (seznam.selectedItemPosition < 0 && !iskanje.hasFocus()) seznam.requestFocus()
    }

    private fun nastaviGlobalLink() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 8) }
        val vklop = CheckBox(this).apply { text = getString(R.string.os_gl_vklop); isChecked = si.safeer.tv.link.GlobalLink.vklopljen(this@NastavitveActivity) }
        val preizkus = CheckBox(this).apply { text = getString(R.string.os_gl_preizkus); isChecked = si.safeer.tv.link.GlobalLink.samoRele(this@NastavitveActivity) }
        box.addView(vklop); box.addView(preizkus)
        // Pot do vsakega sredisca zdoma: Lokalno (v istem omrezju), Neposredno (internet) ali Prek releja, z zamikom.
        // Neposredno je mozno, ce je na racunalniku doma vklopljena "Neposredna povezava od zunaj" (ena preusmerjena vrata).
        val poti = si.safeer.tv.link.GlobalLink.stanjePoti()
        val krog = try { si.safeer.tv.cast.KrogNaprave.krog(this).clani() } catch (_: Throwable) { emptyList() }
        val vrstice = poti.map { (cilj, p) ->
            val ime = krog.firstOrNull { runCatching { si.safeer.tv.cast.KrogZaupanja.idIzKljuca(it.kljuc) }.getOrNull() == cilj }?.ime ?: cilj
            val pot = getString(if (p.first == si.safeer.tv.link.NeposrednaPot.POT_NEPOSREDNO) R.string.os_gl_neposredno else R.string.os_gl_rele)
            "$ime: $pot" + (p.second?.let { " · $it ms" } ?: "")
        }
        box.addView(TextView(this).apply {
            setPadding(0, 24, 0, 0)
            text = if (si.safeer.tv.link.GlobalLink.zdoma || vrstice.isNotEmpty())
                (listOf(getString(R.string.os_gl_pot_zdoma)) + vrstice.ifEmpty { listOf(getString(R.string.os_gl_rele)) }).joinToString("\n")
            else getString(R.string.os_gl_pot_lokalno)
        })
        android.app.AlertDialog.Builder(this).setTitle(getString(R.string.os_gl_ime)).setView(box)
            .setPositiveButton(getString(R.string.os_ig_shrani)) { _, _ ->
                si.safeer.tv.link.GlobalLink.nastavi(this, vklop.isChecked, preizkus.isChecked)
                si.safeer.tv.link.GlobalLink.izklopi()
                val i = Intent(this, si.safeer.tv.cast.CastReceiverService::class.java).apply { action = si.safeer.tv.cast.CastReceiverService.ACTION_GATEWAY_CHANGED }
                try { startService(i) } catch (_: Throwable) { }
                narisi()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    /**
     * Internet prek Safeer Linka: deljenje, mobilni podatki, omejitev, poraba in naprave, ki smejo.
     * Dovoljenje je po napravah: kljukica = sme, brez = ne sme. Naprava, ki se caka na odlocitev, ostane
     * taka, dokler je uporabnik ne oznaci (ali odgovori na vprasanje v obvestilu).
     */
    private fun nastaviInternetGateway() {
        val p = getSharedPreferences(si.safeer.tv.link.AndroidApplicationGateway.PREFS, MODE_PRIVATE)
        val dovoljenja = si.safeer.tv.link.AndroidApplicationGateway.dovoljenja(this)
        val rob = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(rob, rob / 2, rob, 0) }
        fun vrstica(niz: String, krepko: Boolean = false) = TextView(this).apply {
            text = niz
            setPadding(0, rob / 2, 0, rob / 4)
            if (krepko) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        box.addView(vrstica(getString(R.string.os_ig_opis)))
        val gateway = CheckBox(this).apply { text = getString(R.string.os_ig_deli); isChecked = p.getBoolean("gateway_enabled", false) }
        val mobile = CheckBox(this).apply { text = getString(R.string.os_ig_mobilni); isChecked = p.getBoolean("allow_cellular", false) }
        val roaming = CheckBox(this).apply {
            text = getString(R.string.os_ig_gostovanje); isChecked = p.getBoolean("allow_roaming", false); isEnabled = mobile.isChecked
        }
        mobile.setOnCheckedChangeListener { _, vklop -> roaming.isEnabled = vklop; if (!vklop) roaming.isChecked = false }
        val meja = p.getLong("cellular_limit", 0L)
        val limit = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(7))
            setText((meja / 1024 / 1024).toString())
        }
        box.addView(gateway); box.addView(mobile); box.addView(roaming)
        box.addView(vrstica(getString(R.string.os_ig_omejitev))); box.addView(limit)
        val (danes, mesec) = si.safeer.tv.link.AndroidApplicationGateway.poraba(this)
        fun vel(b: Long) = android.text.format.Formatter.formatShortFileSize(this, b)
        box.addView(vrstica(if (meja > 0) getString(R.string.os_ig_poraba_omejitev, vel(danes), vel(mesec), vel(meja))
                            else getString(R.string.os_ig_poraba, vel(danes), vel(mesec))))
        box.addView(vrstica(getString(R.string.os_ig_naprave), krepko = true))
        val vnosi = dovoljenja.vsi()
        val izbire = vnosi.map { v ->
            CheckBox(this).apply {
                text = if (v.stanje == si.safeer.tv.link.InternetDovoljenja.Stanje.CAKA) getString(R.string.os_ig_naprava_caka, v.ime) else v.ime
                isChecked = v.stanje == si.safeer.tv.link.InternetDovoljenja.Stanje.DOVOLJENO
            }
        }
        if (vnosi.isEmpty()) box.addView(vrstica(getString(R.string.os_ig_ni_naprav))) else izbire.forEach { box.addView(it) }
        val drsnik = android.widget.ScrollView(this).apply { addView(box) }
        android.app.AlertDialog.Builder(this).setTitle(getString(R.string.os_ig_naslov)).setView(drsnik)
            .setPositiveButton(getString(R.string.os_ig_shrani)) { _, _ ->
                val mb = limit.text.toString().toLongOrNull()?.coerceAtLeast(0) ?: 0L
                p.edit().putBoolean("gateway_enabled", gateway.isChecked).putBoolean("allow_cellular", mobile.isChecked)
                    .putBoolean("allow_roaming", roaming.isChecked && mobile.isChecked).putLong("cellular_limit", mb * 1024L * 1024L).apply()
                vnosi.forEachIndexed { i, v ->
                    val prej = v.stanje == si.safeer.tv.link.InternetDovoljenja.Stanje.DOVOLJENO
                    val zdaj = izbire[i].isChecked
                    // Samo, kar je uporabnik spremenil: naprava, ki caka in je ni oznacil, caka naprej.
                    if (zdaj != prej) {
                        dovoljenja.odloci(v.naprava, zdaj, v.ime)
                        si.safeer.tv.link.InternetVprasanje.umakni(this, v.naprava)
                    }
                }
                val i = Intent(this, si.safeer.tv.cast.CastReceiverService::class.java).apply { action = si.safeer.tv.cast.CastReceiverService.ACTION_GATEWAY_PERMISSION }
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
            { uspelo -> if (!uspelo && !isFinishing) Toast.makeText(this, getString(if (si.safeer.tv.cast.HubPairing.kodaZaprtaPriSrediscu())
                R.string.link_seznanitev_zaprta else R.string.os_host_ni_odgovora), Toast.LENGTH_LONG).show() })
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
        override fun getCount(): Int = prikaz.size
        override fun getItem(i: Int): Any = prikaz[i]
        override fun getItemId(i: Int): Long = i.toLong()
        override fun getViewTypeCount(): Int = 3
        override fun getItemViewType(i: Int): Int = when {
            prikaz[i].glava -> 0
            prikaz[i].ob == null -> 1
            else -> 2
        }
        /** Naslovi skupin in sporocilo »ni zadetkov« niso izbirni: daljinec jih preskoci. */
        override fun areAllItemsEnabled(): Boolean = false
        override fun isEnabled(i: Int): Boolean = prikaz.getOrNull(i)?.ob != null

        private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

        override fun getView(i: Int, star: View?, roditelj: ViewGroup): View {
            val vr = prikaz[i]
            if (vr.glava || vr.ob == null) {
                val t = (star as? TextView) ?: TextView(this@NastavitveActivity).apply {
                    if (vr.glava) {
                        setTextColor(osBarva(R.color.os_mint)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        letterSpacing = 0.12f; isAllCaps = true
                        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                        setPadding(dp(18), dp(20), dp(18), dp(6))
                        // Bralnik zaslona: naslov skupine je naslov, ne gumb.
                        isAccessibilityHeading = true
                    } else {
                        setTextColor(osBarva(R.color.os_umirjeno)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        setPadding(dp(18), dp(24), dp(18), dp(24))
                    }
                }
                t.text = vr.ime
                return t
            }
            // Zasuk ne naredi novega zaslona (configChanges): vrstica iz druge postavitve
            // (layout-w600dp, stanje desno) se ne sme ponovno uporabiti - naslov bi se odrezal.
            val siroka = resources.configuration.screenWidthDp >= 600
            val v = star?.takeIf { it.getTag(R.id.stanje) == siroka } ?: LayoutInflater.from(this@NastavitveActivity)
                .inflate(R.layout.os_vrstica_nastavitev, roditelj, false).also { it.setTag(R.id.stanje, siroka) }
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
