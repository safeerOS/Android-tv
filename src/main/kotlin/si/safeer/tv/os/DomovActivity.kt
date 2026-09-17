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
    private lateinit var vrstaSpletne: LinearLayout
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
        vrstaSpletne = findViewById(R.id.vrstaSpletne)
        opombaSpodaj = findViewById(R.id.opombaSpodaj)
        narisiZacni()
        narisiNaprave(emptyList())
        // Ce nas je odprla tipka Domov, smo res domaci zaslon tega televizorja.
        if (intent?.categories?.contains(Intent.CATEGORY_HOME) == true) Zaganjalnik.zabeleziZagonDomov(this)
    }

    override fun onStart() {
        super.onStart()
        glavna.post(tikUre)
        ZagonOb.pospravi(this)      // ce nas je ob vklopu odprlo obvestilo, naj ga uporabnik ne vidi
        narisiAplikacije()
        narisiSpletne()
        prevzemiSpletne()
        link.dodaj(this)
        osveziScit()
        // Vrsta s spletnimi aplikacijami na domacem zaslonu televizorja ostane usklajena; ko ima
        // uporabnik prvo spletno aplikacijo, ga sistem enkrat vprasa, ali jo doda na domaci zaslon.
        DomacaVrsta.osvezi(this)
        if (SpletneAplikacije.seznam(this).isNotEmpty()) DomacaVrsta.ponudiEnkrat(this)
        if (link.vprasamoZaNacin()) glavna.postDelayed({ if (!isFinishing && link.vprasamoZaNacin()) vprasajZaNacin() }, 600)
    }

    /**
     * Kadar je Safeer OS domaci zaslon televizorja, tipka Nazaj nima kam: zapustili bi ga in
     * uporabnik bi ostal pred praznim zaslonom. Takrat je Nazaj brez ucinka, kot pri zaganjalniku.
     */
    override fun onBackPressed() {
        if (Zaganjalnik.jeIzbran(this)) return
        @Suppress("DEPRECATION") super.onBackPressed()
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
            sporocilo == "krajevni" -> pokaziStanje(false, getString(R.string.os_stanje_krajevni))
            sporocilo == "ni_linka" -> pokaziStanje(false, getString(R.string.os_stanje_ni_linka))
            sporocilo == "ni" -> pokaziStanje(false, getString(R.string.os_stanje_ni))
            else -> pokaziStanje(false, getString(R.string.os_stanje_povezujem))
        }
        osveziKartice()
        if (!povezan) narisiNaprave(emptyList())
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

    private var karticaDatoteke: View? = null
    private var karticaLink: View? = null

    private fun narisiZacni() {
        vrstaZacni.removeAllViews()
        dodajVeliko(R.drawable.os_ikona_splet, getString(R.string.os_splet), getString(R.string.os_splet_opis)) {
            odpriVBrskalniku(null)
        }
        karticaDatoteke = dodajVeliko(R.drawable.os_ikona_datoteke, getString(R.string.os_datoteke), getString(R.string.os_datoteke_opis)) {
            startActivity(Intent(this, DatotekeActivity::class.java))
        }
        karticaLink = dodajVeliko(R.drawable.os_ikona_link, getString(R.string.os_link), getString(R.string.os_link_opis)) {
            if (link.povezan) odpriLinkVBrskalniku() else vprasajZaNacin()
        }
        karticaScit = dodajVeliko(R.drawable.os_ikona_scit, getString(R.string.os_scit), getString(R.string.os_scit_preverjam)) { preklopiScit() }
        dodajVeliko(R.drawable.os_ikona_nastavitve, getString(R.string.os_nastavitve), getString(R.string.os_nastavitve_opis)) {
            startActivity(Intent(this, NastavitveActivity::class.java))
        }
        uravnajVrsto(vrstaZacni, NAJMANJSA_VELIKA_DP)
        vrstaZacni.getChildAt(0)?.requestFocus()
        osveziKartice()
    }

    /** Opisa kartic Datoteke in Safeer Link povesta, kaj je zdaj na voljo. */
    private fun osveziKartice() {
        karticaDatoteke?.findViewById<TextView>(R.id.opis)?.text =
            getString(if (link.povezan) R.string.os_datoteke_opis else R.string.os_datoteke_opis_krajevno)
        karticaLink?.findViewById<TextView>(R.id.opis)?.text = when {
            link.povezan -> getString(R.string.os_link_opis)
            link.jeKrajevni() -> getString(R.string.os_link_krajevni_opis)
            else -> getString(R.string.os_link_izklopljen_opis)
        }
    }

    /**
     * Ce Safeer Link ne tece, uporabnik enkrat izbere: vklopi Safeer Link (naprave, datoteke z
     * racunalnika, daljinec) ali delaj krajevno (samo viri tega televizorja). Izbira se zapomni.
     */
    private fun vprasajZaNacin() {
        if (link.povezan) { odpriLinkVBrskalniku(); return }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_nacin_naslov))
            .setMessage(getString(R.string.os_nacin_opis))
            .setPositiveButton(getString(R.string.os_nacin_link)) { _, _ ->
                link.vklopiLink()
                Toast.makeText(this, getString(R.string.os_nacin_link_vklopljen), Toast.LENGTH_SHORT).show()
                osveziKartice()
            }
            .setNegativeButton(getString(R.string.os_nacin_krajevni)) { _, _ ->
                link.krajevniNacin()
                Toast.makeText(this, getString(R.string.os_nacin_krajevni_izbran), Toast.LENGTH_LONG).show()
                osveziKartice()
            }
            .setCancelable(true)
            .show()
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

    /**
     * Televizor ni telefon: kar pade cez rob zaslona, ni "malo zunaj", ampak odrezano besedilo -
     * in televizorji vrh tega odrezejo se nekaj slikovnih tock (overscan). Zato sirine kartic ne
     * ugibamo vnaprej: izracunamo jo iz sirine zaslona tako, da med robovoma stoji **cel** kos
     * kartic. Kar je vec, se pokaze ob pomiku, nikoli pa ni na zaslonu polovica besedila.
     */
    private fun uravnajVrsto(vrsta: LinearLayout, najmanjDp: Int) {
        val m = resources.displayMetrics
        val rob = resources.getDimensionPixelSize(R.dimen.os_rob)
        val vrzel = (16 * m.density).toInt()
        val naVoljo = m.widthPixels - 2 * rob
        val n = vrsta.childCount
        if (n == 0 || naVoljo <= 0) return
        val najmanj = (najmanjDp * m.density).toInt()
        val kolikoGre = ((naVoljo + vrzel) / (najmanj + vrzel)).coerceAtLeast(1)
        val k = if (kolikoGre > n) n else kolikoGre
        val sirina = (naVoljo - (k - 1) * vrzel) / k
        for (i in 0 until n) {
            val v = vrsta.getChildAt(i) ?: continue
            val lp = v.layoutParams as? LinearLayout.LayoutParams ?: continue
            lp.width = sirina
            lp.marginEnd = if (i == n - 1) 0 else vrzel
            v.layoutParams = lp
        }
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
        napraveOpomba.text = when {
            link.povezan -> getString(R.string.os_ni_naprav)
            link.jeKrajevni() -> getString(R.string.os_naprave_krajevni)
            else -> getString(R.string.os_naprave_ni_linka)
        }
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
        uravnajVrsto(vrstaNaprave, NAJMANJSA_NAPRAVA_DP)
    }

    private fun vrstaNaprave(n: LinkOdjemalec.Naprava): String = when {
        n.naslov == "127.0.0.1" || n.id.startsWith("tv-") -> "TV · Safeer Link"
        n.id.startsWith("pc-") -> if (n.id.endsWith("-control")) "Safeer Control" else "Safeer Browser · PC"
        n.id.startsWith("phone-") -> "Safeer Browser · Android"
        else -> n.vloga
    }

    // ------------------------------------------------------------------ Spletne aplikacije
    //
    // Spletna stran, ki se obnasa kot aplikacija: svoja ikona in ime, zagon cez ves zaslon brez
    // vrstice z naslovom. Ideja Firefox OS/Capyloon, le da tu spletna aplikacija podeduje vso
    // zascito brskalnika (blokiranje oglasov in sledilcev, nevarne strani, Scit) in Safeer Link.

    /** Ob prvem zagonu Safeer OS prevzame spletne aplikacije, ki jih je uporabnik dodal v brskalniku. */
    private fun prevzemiSpletne() {
        Thread({
            try { SpletneAplikacije.prevzemiOdBrskalnika(this) { glavna.post { narisiSpletne() } } }
            catch (_: Throwable) { }
            glavna.post { narisiSpletne() }
        }, "safeer-os-prevzem").start()
    }

    /** [fokusUrl]: po premiku ali odstranitvi naj fokus ostane pri isti aplikaciji. */
    private fun narisiSpletne(fokusUrl: String = "") {
        vrstaSpletne.removeAllViews()
        var zeljeni: View? = null
        for (a in SpletneAplikacije.seznam(this)) {
            val v = LayoutInflater.from(this).inflate(R.layout.os_kartica_app, vrstaSpletne, false)
            v.findViewById<ImageView>(R.id.ikona).setImageDrawable(SpletneAplikacije.ikona(this, a))
            v.findViewById<TextView>(R.id.ime).text = a.ime.ifBlank { SpletneAplikacije.gostitelj(a.url) }
            v.onFocusChangeListener = fokus
            v.setOnClickListener { zazeniSpletno(a) }
            v.setOnLongClickListener { moznostiSpletne(a); true }
            vrstaSpletne.addView(v)
            if (a.url == fokusUrl) zeljeni = v
        }
        zeljeni?.let { it.post { it.requestFocus() } }
        val dodaj = LayoutInflater.from(this).inflate(R.layout.os_kartica_app, vrstaSpletne, false)
        dodaj.findViewById<ImageView>(R.id.ikona).setImageResource(R.drawable.os_ikona_splet)
        dodaj.findViewById<TextView>(R.id.ime).text = getString(R.string.os_spletne_dodaj)
        dodaj.onFocusChangeListener = fokus
        dodaj.setOnClickListener { dodajSpletno() }
        vrstaSpletne.addView(dodaj)
    }

    private fun zazeniSpletno(a: SpletneAplikacije.Aplikacija) {
        val namera = brskalnikNamera()
            .putExtra("spletna_aplikacija", a.url)
            .putExtra("aplikacija_ime", a.ime.ifBlank { SpletneAplikacije.gostitelj(a.url) })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(namera) } catch (_: Throwable) { }
    }

    /**
     * Dolg pritisk na spletno aplikacijo: uporabnik si vrsto uredi sam. Na daljincu ni vlecenja,
     * zato premikamo po enem mestu; ponudimo samo tisto, kar je na tem mestu res mogoce.
     */
    private fun moznostiSpletne(a: SpletneAplikacije.Aplikacija) {
        val seznam = SpletneAplikacije.seznam(this)
        val mesto = seznam.indexOfFirst { it.url == a.url }
        val dejanja = ArrayList<Pair<String, () -> Unit>>()
        if (mesto > 0) dejanja.add(getString(R.string.os_spletne_levo) to { premakniSpletno(a, -1) })
        if (mesto >= 0 && mesto < seznam.size - 1) dejanja.add(getString(R.string.os_spletne_desno) to { premakniSpletno(a, 1) })
        dejanja.add(getString(R.string.os_spletne_odstrani) to { odstraniSpletno(a) })
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(a.ime.ifBlank { SpletneAplikacije.gostitelj(a.url) })
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .show()
    }

    private fun premakniSpletno(a: SpletneAplikacije.Aplikacija, zamik: Int) {
        if (!SpletneAplikacije.premakni(this, a.url, zamik)) return
        narisiSpletne(a.url)
        DomacaVrsta.osvezi(this)
    }

    private fun odstraniSpletno(a: SpletneAplikacije.Aplikacija) {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(a.ime.ifBlank { SpletneAplikacije.gostitelj(a.url) })
            .setMessage(getString(R.string.os_spletne_odstrani_vprasanje))
            .setPositiveButton(getString(R.string.os_spletne_odstrani)) { _, _ ->
                SpletneAplikacije.odstrani(this, a.url); narisiSpletne(); DomacaVrsta.osvezi(this)
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .show()
    }

    /**
     * Dodajanje brez tipkanja na daljincu: ponudimo strani, ki jih ima uporabnik ze na domaci
     * strani brskalnika ("Moje strani"). Ime in ikono nato prinese manifest spletne aplikacije.
     */
    private fun dodajSpletno() {
        val ploscice = try { si.safeer.tv.HomeTilesStore.load(this) } catch (_: Throwable) { mutableListOf() }
        val proste = ploscice.filterNot { SpletneAplikacije.jeDodana(this, it.url) }
        // Prva izbira je vedno vnos naslova: uporabnik lahko doda katerokoli stran, ne le tistih,
        // ki jih ima ze na domaci strani brskalnika.
        val imena = (listOf(getString(R.string.os_spletne_vnesi)) +
            proste.map { it.title.ifBlank { SpletneAplikacije.gostitelj(it.url) } }).toTypedArray()
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_spletne_dodaj_naslov))
            .setItems(imena) { _, i ->
                if (i == 0) vnesiNaslovSpletne()
                else proste.getOrNull(i - 1)?.let { dodajSpletnoAplikacijo(it.url, it.title) }
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .show()
    }

    /**
     * Vnos naslova z daljincem. Tipkanje na televizorju ni prijetno, zato naslov dopolnimo sami
     * (brez "https://" gre tudi) in ga zavrnemo, ce ni videti kot spletni naslov.
     */
    private fun vnesiNaslovSpletne() {
        val polje = android.widget.EditText(this).apply {
            hint = getString(R.string.os_spletne_vnesi_namig)
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(resources.getColor(R.color.os_besedilo, null))
            setHintTextColor(resources.getColor(R.color.os_umirjeno, null))
        }
        val okvir = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val r = (resources.displayMetrics.density * 24).toInt()
            setPadding(r, r / 2, r, 0)
            addView(polje)
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_spletne_vnesi))
            .setView(okvir)
            .setPositiveButton(getString(R.string.os_spletne_dodaj_gumb)) { _, _ ->
                val naslov = celoten(polje.text.toString())
                if (naslov == null) {
                    Toast.makeText(this, getString(R.string.os_spletne_neveljaven), Toast.LENGTH_LONG).show()
                } else if (SpletneAplikacije.jeDodana(this, naslov)) {
                    Toast.makeText(this, getString(R.string.os_spletne_ze_dodana), Toast.LENGTH_LONG).show()
                } else {
                    dodajSpletnoAplikacijo(naslov, SpletneAplikacije.gostitelj(naslov))
                }
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .show()
        polje.requestFocus()
    }

    /** Naslov, kot ga je vnesel uporabnik, dopolnjen v celoten https naslov; null, ce to ni naslov. */
    private fun celoten(vnos: String): String? {
        val t = vnos.trim().replace(" ", "")
        if (t.isEmpty()) return null
        val z = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        return try {
            val u = java.net.URL(z)
            if (u.host.contains(".") && !u.host.startsWith(".") && !u.host.endsWith(".")) z else null
        } catch (_: Throwable) { null }
    }

    private fun dodajSpletnoAplikacijo(url: String, ime: String) {
        Toast.makeText(this, getString(R.string.os_spletne_dodajam), Toast.LENGTH_SHORT).show()
        SpletneAplikacije.dodaj(this, url, ime) {
            if (isFinishing) return@dodaj
            narisiSpletne()
            // Prvic ponudimo, da se spletne aplikacije pokazejo tudi na domacem zaslonu TV.
            DomacaVrsta.ponudiEnkrat(this)
        }
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

    /**
     * Namera za splet: ce je Safeer Browser namescen kot svoja aplikacija, odpremo njega (en pogon,
     * ena zascita, en seznam zavihkov); sicer nasega vgrajenega, ki je v Safeer OS za ta primer.
     */
    private fun brskalnikNamera(): Intent {
        val paket = Sosed.brskalnik(this)
        val namera = if (paket != null)
            Intent().setComponent(android.content.ComponentName(paket, "si.safeer.tv.MainActivity"))
                // Brskalnik naj ve, od kod je prisel: ob izhodu se vrne v Safeer OS, ne na Android.
                .putExtra("iz_safeer_os", packageName)
        else Intent(this, si.safeer.tv.MainActivity::class.java)
        return namera.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Odpre splet (brskalnik sosede ali vgrajenega), po zelji z naslovom. */
    private fun odpriVBrskalniku(url: String?) {
        val namera = brskalnikNamera()
        if (url != null) { namera.action = Intent.ACTION_VIEW; namera.data = Uri.parse(url) }
        try { startActivity(namera) } catch (e: Throwable) {
            Toast.makeText(this, e.message ?: "?", Toast.LENGTH_SHORT).show()
        }
    }

    /** Stran Safeer Link v brskalniku (seznanitev, naprave, daljinec); brskalnik pozna dodatek odpri_link. */
    private fun odpriLinkVBrskalniku() {
        val namera = brskalnikNamera().putExtra("odpri_link", true)
        try { startActivity(namera) } catch (_: Throwable) { }
    }

    private companion object {
        /** Najmanjsa sirina kartice, pri kateri je opis se berljiv (velike kartice v vrsti Zacni). */
        const val NAJMANJSA_VELIKA_DP = 200
        /** Kartica naprave: ime in vloga v eni vrstici vsak. */
        const val NAJMANJSA_NAPRAVA_DP = 220
    }
}
