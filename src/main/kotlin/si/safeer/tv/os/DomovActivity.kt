package si.safeer.tv.os

import si.safeer.tv.R

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
class DomovActivity : OsActivity(), LinkOdjemalec.Poslusalec {

    private lateinit var stanjeBesedilo: TextView
    private lateinit var stanjePika: View
    private lateinit var ura: TextView
    private lateinit var vrstaZacni: LinearLayout
    private lateinit var vrstaNadaljuj: LinearLayout
    private lateinit var naslovNadaljuj: TextView
    private lateinit var drsnikNadaljuj: View
    private lateinit var vrstaAplikacije: LinearLayout
    private lateinit var vrstaSpletne: LinearLayout
    private lateinit var opombaSpodaj: TextView
    private lateinit var pomocPlosek: TextView
    private lateinit var drsnik: View

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
        vrstaNadaljuj = findViewById(R.id.vrstaNadaljuj)
        naslovNadaljuj = findViewById(R.id.naslovNadaljuj)
        drsnikNadaljuj = findViewById(R.id.drsnikNadaljuj)
        vrstaAplikacije = findViewById(R.id.vrstaAplikacije)
        vrstaSpletne = findViewById(R.id.vrstaSpletne)
        opombaSpodaj = findViewById(R.id.opombaSpodaj)
        pomocPlosek = findViewById(R.id.pomocPlosek)
        pomocPlosek.text = getString(R.string.os_pomoc_plosek)
        drsnik = findViewById(R.id.drsnik)
        narisiZacni()
        // Ce nas je odprla tipka Domov, smo res domaci zaslon tega televizorja.
        if (intent?.categories?.contains(Intent.CATEGORY_HOME) == true) Zaganjalnik.zabeleziZagonDomov(this)
    }

    /** Vrstica z gumbi ploscka se pokaze takoj, ko uporabnik plosek prvic uporabi. */
    override fun plosekZaznan() {
        pomocPlosek.visibility =
            if (Kontroler.jePriklopljen(this)) View.VISIBLE else View.GONE
    }

    override fun onStart() {
        super.onStart()
        Ozadje.uporabi(this, drsnik)      // ozadje po izbiri uporabnika
        // Gumbi plosecka v vrstici pomoci, kadar je plosek v rabi; sicer je ne kazemo.
        plosekZaznan()
        glavna.post(tikUre)
        ZagonOb.pospravi(this)      // ce nas je ob vklopu odprlo obvestilo, naj ga uporabnik ne vidi
        narisiNadaljuj()
        narisiAplikacije()
        narisiSpletne()
        prevzemiSpletne()
        // Ikone, shranjene s prejsnjo razlicico, so bile premajhne in zato zamegljene; enkrat jih
        // poiscemo v vecji locljivosti.
        SpletneAplikacije.osveziIkone(this) { zZapomnjenimFokusom { narisiSpletne() } }
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
    }

    override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) {
        val kje = link.imeSredisca.ifBlank { getString(R.string.os_naprava_tv) }
        if (link.povezan) pokaziStanje(true, getString(R.string.os_stanje_povezan, kje))
        // Racunalnik se je javil (ali odsel): vrsta Zacni dobi ali izgubi kartico s programi.
        val programi = naprave.any { it.zmoznosti.contains("apps") && it.id != Identiteta.id(this) }
        val zaslon = naprave.any { it.zmoznosti.contains("desktop") && it.id != Identiteta.id(this) }
        if (programi != imamoPrograme || zaslon != imamoZaslon) {
            imamoPrograme = programi; imamoZaslon = zaslon
            zZapomnjenimFokusom { narisiZacni() }
        }
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
    /** Ali kateri racunalnik v Linku deli svoje programe (zmoznost "apps"). */
    private var imamoPrograme = false
    /** Ali kateri racunalnik v Linku deli svoj zaslon (zmoznost "desktop"). */
    private var imamoZaslon = false

    /**
     * Fokus mora preziveti osvezitev. Vrste se ponovno izrisejo same od sebe (racunalnik se javi,
     * spletna aplikacija dobi ikono, Scit odgovori), pri tem pa se poglede zavrze skupaj s fokusom
     * in ta skoci na zacetek - sredi tipkanja z daljincem je to zoprno. Zato vsaka kartica nosi
     * svojo oznako, pred izrisom si jo zapomnimo in jo po izrisu poiscemo nazaj.
     */
    private fun zZapomnjenimFokusom(kaj: () -> Unit) {
        val oznaka = currentFocus?.tag as? String
        kaj()
        if (oznaka.isNullOrEmpty()) return
        drsnik.post { najdiPoOznaki(oznaka)?.takeIf { !it.hasFocus() }?.requestFocus() }
    }

    private fun najdiPoOznaki(oznaka: String): View? {
        for (vrsta in listOf(vrstaNadaljuj, vrstaZacni, vrstaSpletne, vrstaAplikacije)) {
            for (i in 0 until vrsta.childCount) {
                val v = vrsta.getChildAt(i) ?: continue
                if (v.tag == oznaka) return v
            }
        }
        return null
    }

    /** Ena kartica v vrsti Zacni; [kljuc] se shrani v vrstni red, zato se nikoli ne spremeni. */
    private class Zacni(val kljuc: String, val ikona: Int, val naslov: String, val ob: () -> Unit)

    /**
     * Vrsta Zacni. Kaj je v njej, dolocimo mi (in kaj je ta trenutek na voljo), v katerem vrstnem
     * redu, pa uporabnik - dolg pritisk na kartico jo premakne levo ali desno.
     *
     * [fokusKljuc]: po premiku naj fokus ostane na isti kartici.
     */
    private fun narisiZacni(fokusKljuc: String = "") {
        vrstaZacni.removeAllViews()
        karticaDatoteke = null; karticaLink = null; karticaScit = null
        val vse = ArrayList<Zacni>()
        vse.add(Zacni("splet", R.drawable.os_ikona_splet, getString(R.string.os_splet)) {
            odpriVBrskalniku(null)
        })
        vse.add(Zacni("datoteke", R.drawable.os_ikona_datoteke, getString(R.string.os_datoteke)) {
            odpriVarno(Intent(this, DatotekeActivity::class.java), getString(R.string.os_datoteke))
        })
        // Naprave so dobile svoj zaslon: na domacem je bila to se ena vrsta kartic in je jemala
        // prostor spletnim aplikacijam, ki jih uporabnik odpira vsak dan.
        vse.add(Zacni("naprave", R.drawable.os_ikona_link, getString(R.string.os_naprave_naslov)) {
            if (link.povezan || !link.vprasamoZaNacin()) odpriVarno(Intent(this, NapraveActivity::class.java), getString(R.string.os_naprave_naslov))
            else vprasajZaNacin()
        })
        vse.add(Zacni("scit", R.drawable.os_ikona_scit, getString(R.string.os_scit)) { preklopiScit() })
        // Zaslon racunalnika: kartica se pokaze samo, kadar ga racunalnik res deli.
        if (imamoZaslon) {
            vse.add(Zacni("zaslon", R.drawable.os_ikona_zaslon, getString(R.string.os_zaslon)) {
                odpriVarno(Intent(this, ZaslonActivity::class.java), getString(R.string.os_zaslon))
            })
        }
        // Programi racunalnika: kartico pokazemo samo, kadar jih kaksen racunalnik res deli -
        // sicer bi obljubljala nekaj, cesar ni.
        if (imamoPrograme) {
            vse.add(Zacni("programi", R.drawable.os_ikona_racunalnik, getString(R.string.os_programi)) {
                odpriVarno(Intent(this, AplikacijeHostaActivity::class.java), getString(R.string.os_programi))
            })
        }
        vse.add(Zacni("nastavitve", R.drawable.os_ikona_nastavitve, getString(R.string.os_nastavitve)) {
            odpriVarno(Intent(this, NastavitveActivity::class.java), getString(R.string.os_nastavitve))
        })

        val red = Vrstni.red(this, KLJUC_ZACNI, vse.map { it.kljuc })
        var zeljeni: View? = null
        for (kljuc in red) {
            val z = vse.firstOrNull { it.kljuc == kljuc } ?: continue
            val v = dodajMalo(z.ikona, z.naslov, z.ob)
            v.tag = "zacni:" + z.kljuc
            v.setOnLongClickListener { moznostiZacni(z, red); true }
            when (z.kljuc) {
                "datoteke" -> karticaDatoteke = v
                "naprave" -> karticaLink = v
                "scit" -> karticaScit = v
            }
            if (z.kljuc == fokusKljuc) zeljeni = v
        }
        uravnajVrsto(vrstaZacni, NAJMANJSA_ZACNI_DP, NAJVECJA_ZACNI_DP)
        // Fokus prevzamemo samo, kadar ga nihce nima (prvi izris). Ce uporabnik ravno izbira
        // spodaj, ga racunalnik, ki se je pravkar javil, ne sme vreci nazaj gor.
        if (zeljeni != null) zeljeni.post { zeljeni.requestFocus() }
        else if (currentFocus == null) vrstaZacni.getChildAt(0)?.requestFocus()
        osveziKartice()
        osveziScit()
    }

    /** Dolg pritisk na kartico v vrsti Zacni: uporabnik si vrsto uredi po svoje. */
    private fun moznostiZacni(z: Zacni, red: List<String>) {
        val mesto = red.indexOf(z.kljuc)
        val dejanja = ArrayList<Pair<String, () -> Unit>>()
        if (z.kljuc == "scit") dejanja.add(getString(R.string.os_scit_podrobnosti) to { podrobnostiScita() })
        if (mesto > 0) dejanja.add(getString(R.string.os_spletne_levo) to { premakniZacni(z, red, -1) })
        if (mesto in 0 until red.size - 1) dejanja.add(getString(R.string.os_spletne_desno) to { premakniZacni(z, red, 1) })
        if (dejanja.isEmpty()) return
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(z.naslov)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    private fun premakniZacni(z: Zacni, red: List<String>, zamik: Int) {
        if (!Vrstni.premakni(this, KLJUC_ZACNI, red, z.kljuc, zamik)) return
        narisiZacni(z.kljuc)
    }

    /** Na majhnih karticah opisov ni; stanje pove samo Scit (in vrstica na vrhu zaslona). */
    private fun osveziKartice() { }

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
            .let { Kontroler.pokazi(it.show()) }
    }

    // ------------------------------------------------------------------ Safeer Scit (filter DNS za ves televizor)

    private var karticaScit: View? = null
    private var scitStanje: Scit.Stanje? = null

    private fun osveziScit() {
        Scit.stanje(this) { pokaziScit(it) }
    }

    /**
     * Na kartici je samo stanje in stevilka - dolgo besedilo se je na televizorju odrezalo na robu.
     * Vse ostalo (kaj Scit sploh blokira, zakaj je prekinjen, kaj potrebuje) je pod "Podrobnosti",
     * ki jih uporabnik odpre z zadrzanim OK.
     */
    private fun pokaziScit(s: Scit.Stanje) {
        scitStanje = s
        val opis = karticaScit?.findViewById<TextView>(R.id.stanje) ?: return
        opis.visibility = View.VISIBLE
        opis.text = when {
            !s.naVoljo -> getString(R.string.os_scit_kratko_ni)
            // Na majhni kartici je prostora za dve besedi: dolg napis se je odrezal sredi besede.
            s.vklopljen && s.tece -> getString(R.string.os_scit_ploscica_vklopljen, s.blokiranih)
            s.vklopljen -> getString(R.string.os_scit_kratko_prekinjen)
            else -> getString(R.string.os_scit_kratko_izklopljen)
        }
    }

    /** Celotna razlaga Scita; na kartici je ni, ker je predolga. */
    private fun podrobnostiScita() {
        val s = scitStanje
        val besedilo = when {
            s == null -> getString(R.string.os_scit_preverjam)
            !s.naVoljo -> getString(R.string.os_scit_ni_brskalnika)
            s.vklopljen && s.tece -> getString(R.string.os_scit_vklopljen_opis, s.blokiranih)
            s.vklopljen -> getString(R.string.os_scit_prekinjen)
            else -> getString(R.string.os_scit_izklopljen_opis)
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_scit))
            .setMessage(besedilo + "\n\n" + getString(R.string.os_scit_nastavitev_opis))
            .setPositiveButton(getString(
                if (s?.vklopljen == true) R.string.os_zaganjalnik_izklopi_kratko else R.string.os_vklopi)) { _, _ -> preklopiScit() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
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
    /**
     * Sirine kartic v vrsti: izracunamo jih tako, da se celo stevilo kartic natanko izide do roba
     * zaslona. Brez tega je zadnja vidna kartica odrezana na robu in vrsta je videti pokvarjena.
     * [najvecDp] prepreci, da bi se ob dveh ali treh karticah raztegnile cez pol zaslona.
     */
    private fun uravnajVrsto(vrsta: LinearLayout, najmanjDp: Int, najvecDp: Int = 0) {
        val m = resources.displayMetrics
        val rob = resources.getDimensionPixelSize(R.dimen.os_rob)
        val vrzel = (16 * m.density).toInt()
        val naVoljo = m.widthPixels - 2 * rob
        val n = vrsta.childCount
        if (n == 0 || naVoljo <= 0) return
        val najmanj = (najmanjDp * m.density).toInt()
        val kolikoGre = ((naVoljo + vrzel) / (najmanj + vrzel)).coerceAtLeast(1)
        val k = if (kolikoGre > n) n else kolikoGre
        var sirina = (naVoljo - (k - 1) * vrzel) / k
        if (najvecDp > 0) sirina = sirina.coerceAtMost((najvecDp * m.density).toInt())
        for (i in 0 until n) {
            val v = vrsta.getChildAt(i) ?: continue
            val lp = v.layoutParams as? LinearLayout.LayoutParams ?: continue
            lp.width = sirina
            lp.marginEnd = if (i == n - 1) 0 else vrzel
            v.layoutParams = lp
        }
    }

    /**
     * Kartica v vrsti Zacni: ikona in ime, brez opisa. Na zaslonu 960x540 dp so prav opisi pojedli
     * toliko visine, da tri vrste niso sle skupaj in se je zadnja rezala. Kar je treba povedati o
     * stanju, gre v eno kratko mint vrstico (Scit), vse ostalo pa pod zadrzan OK.
     */
    private fun dodajMalo(ikona: Int, naslov: String, ob: () -> Unit): View {
        val v = LayoutInflater.from(this).inflate(R.layout.os_kartica_mala, vrstaZacni, false)
        v.findViewById<ImageView>(R.id.ikona).setImageResource(ikona)
        v.findViewById<TextView>(R.id.naslov).text = naslov
        v.setOnClickListener { ob() }
        v.onFocusChangeListener = fokus
        vrstaZacni.addView(v)
        return v
    }

    // ------------------------------------------------------------------ Nadaljuj
    //
    // Pot do filma je na televizorju dolga: Datoteke, racunalnik, mapa, mapa, datoteka. Kdor je
    // vceraj gledal film, ga hoce danes odpreti takoj - zato je prva vrsta domacega zaslona to,
    // kar je nazadnje odprl. Vrsta se pokaze samo, kadar kaj je; prazne vrste ne kazemo.

    private fun narisiNadaljuj() {
        val vnosi = Nadaljuj.seznam(this)
        vrstaNadaljuj.removeAllViews()
        val vidno = if (vnosi.isEmpty()) View.GONE else View.VISIBLE
        naslovNadaljuj.visibility = vidno
        drsnikNadaljuj.visibility = vidno
        for (n in vnosi) {
            val v = LayoutInflater.from(this).inflate(R.layout.os_kartica_ikona, vrstaNadaljuj, false)
            val ikona = v.findViewById<ImageView>(R.id.ikona)
            val spletna = if (n.vrsta == Nadaljuj.SPLETNA)
                SpletneAplikacije.seznam(this).firstOrNull { it.url == n.url } else null
            if (spletna != null) ikona.setImageDrawable(SpletneAplikacije.ikona(this, spletna))
            else ikona.setImageResource(ikonaZa(n.vrsta))
            // Zaslon racunalnika je bil prej zapisan z dolgim imenom naprave in se je na kartici
            // lomil sredi besede; ime izpisemo iz prevoda, tudi za vrstice, zapisane prej.
            v.findViewById<TextView>(R.id.ime).text =
                if (n.vrsta == Nadaljuj.ZASLON) getString(R.string.os_zaslon) else n.ime
            v.onFocusChangeListener = fokus
            v.setOnClickListener { odpriNadaljuj(n) }
            v.setOnLongClickListener { moznostiNadaljuj(n); true }
            v.tag = "nadaljuj:" + n.kljuc()
            vrstaNadaljuj.addView(v)
        }
    }

    private fun ikonaZa(vrsta: String): Int = when (vrsta) {
        Nadaljuj.VIDEO -> R.drawable.os_ikona_video
        Nadaljuj.GLASBA -> R.drawable.os_ikona_glasba
        Nadaljuj.SLIKA -> R.drawable.os_ikona_slika
        Nadaljuj.BESEDILO -> R.drawable.os_ikona_datoteka
        Nadaljuj.SPLETNA -> R.drawable.os_ikona_splet
        Nadaljuj.ZASLON -> R.drawable.os_ikona_zaslon
        else -> R.drawable.os_ikona_racunalnik
    }

    private fun moznostiNadaljuj(n: Nadaljuj.Vnos) {
        val okno = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(n.ime)
            .setPositiveButton(getString(R.string.os_nadaljuj_odstrani)) { _, _ ->
                Nadaljuj.odstrani(this, n)
                zZapomnjenimFokusom { narisiNadaljuj() }
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
        Kontroler.pokazi(okno.show())
    }

    /**
     * Odpre, kar je uporabnik nazadnje gledal. Zetona in naslova streznika ne hranimo (velja samo,
     * dokler seja tece), zato ga za datoteke z racunalnika znova vprasamo - in ce racunalnika ni,
     * to posteno povemo, namesto da bi kartica tiho ne naredila nicesar.
     */
    private fun odpriNadaljuj(n: Nadaljuj.Vnos) {
        when (n.vrsta) {
            Nadaljuj.SPLETNA -> {
                val a = SpletneAplikacije.seznam(this).firstOrNull { it.url == n.url }
                if (a != null) zazeniSpletno(a) else odpriVBrskalniku(n.url)
            }
            Nadaljuj.ZASLON -> odpriVarno(Intent(this, ZaslonActivity::class.java)
                .putExtra(DatotekeActivity.EXTRA_RACUNALNIK, n.racunalnik), getString(R.string.os_zaslon))
            Nadaljuj.PROGRAM -> zazeniProgram(n)
            else -> if (n.krajevno) odpriDatoteko(n, null) else odpriZRacunalnika(n)
        }
    }

    private fun racunalnikZa(n: Nadaljuj.Vnos): LinkOdjemalec.Naprava? =
        link.naprave.firstOrNull { it.id == n.racunalnik }

    private fun zazeniProgram(n: Nadaljuj.Vnos) {
        val r = racunalnikZa(n) ?: run { niVec(n); return }
        Toast.makeText(this, getString(R.string.os_nadaljuj_odpiram, n.ime), Toast.LENGTH_SHORT).show()
        val zaslon = r.zmoznosti.contains("desktop")
        link.ukaz(r.id, "apps.launch", org.json.JSONObject().put("app", n.program), 10_000,
            LinkOdjemalec.Odgovor { izid, _ ->
                if (isFinishing) return@Odgovor
                if (izid?.optBoolean("ok") != true) { niVec(n); return@Odgovor }
                if (zaslon) odpriVarno(Intent(this, ZaslonActivity::class.java)
                    .putExtra(DatotekeActivity.EXTRA_RACUNALNIK, r.id), getString(R.string.os_zaslon))
            })
    }

    /** Datoteka z racunalnika: najprej si od njega izprosimo svezo sejo, sele nato odpremo. */
    private fun odpriZRacunalnika(n: Nadaljuj.Vnos) {
        val r = racunalnikZa(n) ?: run { niVec(n); return }
        Toast.makeText(this, getString(R.string.os_nadaljuj_odpiram, n.ime), Toast.LENGTH_SHORT).show()
        link.ukaz(r.id, "files.list", org.json.JSONObject().put("folder", ""), 12_000,
            LinkOdjemalec.Odgovor { izid, _ ->
                if (isFinishing) return@Odgovor
                val streznik = izid?.optJSONObject("data")?.optJSONObject("server")
                if (izid?.optBoolean("ok") != true || streznik == null) { niVec(n); return@Odgovor }
                odpriDatoteko(n, DatotekeActivity.Streznik(
                    streznik.optString("base_url").trimEnd('/'),
                    streznik.optString("fp"), streznik.optString("token")))
            })
    }

    private fun odpriDatoteko(n: Nadaljuj.Vnos, s: DatotekeActivity.Streznik?) {
        val naslovDatoteke = if (s == null) n.id else s.url(n.id)
        val namera = when (n.vrsta) {
            Nadaljuj.SLIKA -> Intent(this, SlikaActivity::class.java)
                .putStringArrayListExtra("urli", arrayListOf(naslovDatoteke))
                .putStringArrayListExtra("imena", arrayListOf(n.ime))
                .putExtra("zacetek", 0)
            Nadaljuj.BESEDILO -> Intent(this, BesediloActivity::class.java)
                .putExtra("url", naslovDatoteke).putExtra("ime", n.ime)
            else -> Intent(this, PredvajalnikActivity::class.java)
                .putExtra("url", naslovDatoteke).putExtra("ime", n.ime)
                .putExtra("mime", n.mime).putExtra("zvok", n.vrsta == Nadaljuj.GLASBA)
        }
        namera.putExtra("lokalno", s == null)
        if (s != null) { val b = Bundle(); s.vBundle(b); namera.putExtras(b) }
        odpriVarno(namera, n.ime)
    }

    /** Kar je bilo, ni vec dosegljivo: povejmo in kartico ponudimo v odstranitev. */
    private fun niVec(n: Nadaljuj.Vnos) {
        Toast.makeText(this, getString(R.string.os_nadaljuj_ni_vec, n.ime), Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ Naprave

    // ------------------------------------------------------------------ Spletne aplikacije
    //
    // Spletna stran, ki se obnasa kot aplikacija: svoja ikona in ime, zagon cez ves zaslon brez
    // vrstice z naslovom. Ideja Firefox OS/Capyloon, le da tu spletna aplikacija podeduje vso
    // zascito brskalnika (blokiranje oglasov in sledilcev, nevarne strani, Scit) in Safeer Link.

    /** Ob prvem zagonu Safeer OS prevzame spletne aplikacije, ki jih je uporabnik dodal v brskalniku. */
    private fun prevzemiSpletne() {
        Thread({
            try { SpletneAplikacije.prevzemiOdBrskalnika(this) { glavna.post { zZapomnjenimFokusom { narisiSpletne() } } } }
            catch (e: Throwable) { Log.w(TAG, "Prevzem spletnih aplikacij: ${e.message}") }
            glavna.post { zZapomnjenimFokusom { narisiSpletne() } }
        }, "safeer-os-prevzem").start()
    }

    /** [fokusUrl]: po premiku ali odstranitvi naj fokus ostane pri isti aplikaciji. */
    private fun narisiSpletne(fokusUrl: String = "") {
        vrstaSpletne.removeAllViews()
        var zeljeni: View? = null
        for (a in SpletneAplikacije.seznam(this)) {
            val v = LayoutInflater.from(this).inflate(R.layout.os_kartica_ikona, vrstaSpletne, false)
            v.findViewById<ImageView>(R.id.ikona).setImageDrawable(SpletneAplikacije.ikona(this, a))
            v.findViewById<TextView>(R.id.ime).text = a.ime.ifBlank { SpletneAplikacije.gostitelj(a.url) }
            v.onFocusChangeListener = fokus
            v.setOnClickListener { zazeniSpletno(a) }
            v.setOnLongClickListener { moznostiSpletne(a); true }
            v.tag = "splet:" + a.url
            vrstaSpletne.addView(v)
            if (a.url == fokusUrl) zeljeni = v
        }
        zeljeni?.let { it.post { it.requestFocus() } }
        val dodaj = LayoutInflater.from(this).inflate(R.layout.os_kartica_ikona, vrstaSpletne, false)
        dodaj.findViewById<ImageView>(R.id.ikona).setImageResource(R.drawable.os_ikona_splet)
        dodaj.findViewById<TextView>(R.id.ime).text = getString(R.string.os_spletne_dodaj)
        dodaj.onFocusChangeListener = fokus
        dodaj.setOnClickListener { dodajSpletno() }
        dodaj.tag = "splet:+"
        vrstaSpletne.addView(dodaj)
    }

    private fun zazeniSpletno(a: SpletneAplikacije.Aplikacija) {
        Nadaljuj.zapisi(this, Nadaljuj.Vnos(vrsta = Nadaljuj.SPLETNA,
            ime = a.ime.ifBlank { SpletneAplikacije.gostitelj(a.url) }, url = a.url))
        val namera = brskalnikNamera()
            .putExtra("spletna_aplikacija", a.url)
            .putExtra("aplikacija_ime", a.ime.ifBlank { SpletneAplikacije.gostitelj(a.url) })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        odpriVarno(namera, a.ime.ifBlank { SpletneAplikacije.gostitelj(a.url) })
    }

    /**
     * Odpiranje, ki ne utihne. Doslej smo napako pri startActivity tiho pozrli in uporabnik je
     * pritisnil OK, pa se ni zgodilo nic - brez pojasnila in brez poti naprej. Zdaj pove, kaj je
     * slo narobe, in ponudi ponovni poskus.
     */
    private fun odpriVarno(namera: Intent, ime: String) {
        try {
            startActivity(namera)
        } catch (e: Throwable) {
            Log.w(TAG, "Odpiranje ni uspelo ($ime): ${e.message}")
            val razlog = when (e) {
                is android.content.ActivityNotFoundException -> getString(R.string.os_odpri_ni_aplikacije)
                is SecurityException -> getString(R.string.os_odpri_ni_dovoljenja)
                else -> e.message.orEmpty().ifBlank { e.javaClass.simpleName }
            }
            android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(ime)
                .setMessage(getString(R.string.os_odpri_napaka, razlog))
                .setPositiveButton(getString(R.string.os_poskusi_znova)) { _, _ -> odpriVarno(namera, ime) }
                .setNegativeButton(getString(R.string.os_preklici), null)
                .let { Kontroler.pokazi(it.show()) }
        }
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
            .let { Kontroler.pokazi(it.show()) }
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
            .let { Kontroler.pokazi(it.show()) }
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
            .let { Kontroler.pokazi(it.show()) }
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
            .let { Kontroler.pokazi(it.show()) }
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

    /**
     * Aplikacije televizorja: na domacem zaslonu samo priljubljene, v vrstnem redu uporabnika.
     * Prej so bile tu vse in vrsta se je lomila cez rob zaslona - med desetinami kartic ni bilo
     * mogoce nic najti. Zadnja kartica ("Ostalo") pelje na vse.
     *
     * [fokusPaket]: po premiku naj fokus ostane pri isti aplikaciji.
     */
    private fun narisiAplikacije(fokusPaket: String = "") {
        vrstaAplikacije.removeAllViews()
        val vsi = Aplikacije.seznam(this)
        Priljubljene.pocistiSamodejne(this)
        val izbrani = Priljubljene.seznam(this)
        val po = izbrani.mapNotNull { paket -> vsi.firstOrNull { it.paket == paket } }
        var zeljeni: View? = null
        for (a in po) {
            val v = LayoutInflater.from(this).inflate(R.layout.os_kartica_ikona, vrstaAplikacije, false)
            v.findViewById<ImageView>(R.id.ikona).setImageDrawable(Aplikacije.ikona(this, a))
            v.findViewById<TextView>(R.id.ime).text = a.ime
            v.onFocusChangeListener = fokus
            v.setOnClickListener { odpriVarno(Intent(a.namera).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), a.ime) }
            v.setOnLongClickListener { moznostiAplikacije(a, po.size); true }
            v.tag = "app:" + a.paket
            vrstaAplikacije.addView(v)
            if (a.paket == fokusPaket) zeljeni = v
        }
        val ostalo = LayoutInflater.from(this).inflate(R.layout.os_kartica_ikona, vrstaAplikacije, false)
        ostalo.findViewById<ImageView>(R.id.ikona).setImageResource(R.drawable.os_ikona_mreza)
        // Dokler uporabnik ni izbral nobene, kartica pove, kaj naj naredi - prazna vrsta molci.
        ostalo.findViewById<TextView>(R.id.ime).text =
            getString(if (po.isEmpty()) R.string.os_aplikacije_izberi else R.string.os_aplikacije_ostalo)
        ostalo.onFocusChangeListener = fokus
        ostalo.setOnClickListener { odpriVarno(Intent(this, AplikacijeTvActivity::class.java), getString(R.string.os_aplikacije_vse)) }
        ostalo.tag = "app:+"
        vrstaAplikacije.addView(ostalo)
        uravnajVrsto(vrstaAplikacije, NAJMANJSA_APP_DP, NAJVECJA_APP_DP)
        zeljeni?.let { it.post { it.requestFocus() } }
    }

    /** Dolg pritisk na priljubljeno aplikacijo: uredi vrstni red ali jo umakni z domacega zaslona. */
    private fun moznostiAplikacije(a: Aplikacije.Vnos, koliko: Int) {
        val mesto = Priljubljene.seznam(this).indexOf(a.paket)
        val dejanja = ArrayList<Pair<String, () -> Unit>>()
        if (mesto > 0) dejanja.add(getString(R.string.os_spletne_levo) to { premakniAplikacijo(a, -1) })
        if (mesto in 0 until koliko - 1) dejanja.add(getString(R.string.os_spletne_desno) to { premakniAplikacijo(a, 1) })
        dejanja.add(getString(R.string.os_aplikacije_odstrani) to {
            Priljubljene.odstrani(this, a.paket); narisiAplikacije()
        })
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(a.ime)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    private fun premakniAplikacijo(a: Aplikacije.Vnos, zamik: Int) {
        if (!Priljubljene.premakni(this, a.paket, zamik)) return
        narisiAplikacije(a.paket)
    }

    // ------------------------------------------------------------------ pomozno

    /**
     * Ko fokus pride v vrsto, jo pripeljemo na zaslon **celo**, skupaj z njenim naslovom. Brez tega
     * je zadnja vrsta prerezana na pol - kartice so odrezane po sredini in zaslon je videti
     * pokvarjen, ceprav se le drsi. Vrstic ne lomimo: ali je cela vidna ali pa je (mehko) ni.
     */
    private fun pripeljiVrsto(v: View) {
        val vrsta = v.parent as? View ?: return              // vrsta kartic
        val drsnikVrste = vrsta.parent as? View ?: return    // vodoravni drsnik okoli nje
        val naslovVisina = (44 * resources.displayMetrics.density).toInt()   // naslov odseka nad vrsto
        val zgoraj = (drsnikVrste.top - naslovVisina).coerceAtLeast(0)
        val spodaj = drsnikVrste.bottom + (16 * resources.displayMetrics.density).toInt()
        val kje = drsnik.scrollY
        val visina = drsnik.height
        if (visina <= 0) return
        val cilj = when {
            zgoraj < kje -> zgoraj
            spodaj > kje + visina -> spodaj - visina
            else -> return
        }
        (drsnik as? android.widget.ScrollView)?.smoothScrollTo(0, cilj.coerceAtLeast(0))
    }

    private val fokus = View.OnFocusChangeListener { v, ima ->
        v.animate().scaleX(if (ima) 1.04f else 1f).scaleY(if (ima) 1.04f else 1f).setDuration(120).start()
        if (ima) v.post { pripeljiVrsto(v) }
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
        odpriVarno(namera, getString(R.string.os_splet))
    }

    /** Stran Safeer Link v brskalniku (seznanitev, naprave, daljinec); brskalnik pozna dodatek odpri_link. */
    private fun odpriLinkVBrskalniku() {
        odpriVarno(brskalnikNamera().putExtra("odpri_link", true), getString(R.string.os_link))
    }

    private companion object {
        const val TAG = "SafeerOsDomov"
        /** Najmanjsa sirina kartice, pri kateri je opis se berljiv (velike kartice v vrsti Zacni). */
        /** Majhna kartica Zacni: sest jih gre na zaslon sirine 960 dp. */
        /** Spletna aplikacija: stiri ploscice na zaslon. */
        const val NAJMANJSA_SPLET_DP = 116
        const val NAJVECJA_SPLET_DP = 140
        const val NAJMANJSA_ZACNI_DP = 128
        const val NAJVECJA_ZACNI_DP = 170
        /** Kje je shranjen vrstni red vrste Zacni. */
        const val KLJUC_ZACNI = "red_zacni"
        /** Kartica aplikacije: ikona in ime; pod to sirino ime ni vec berljivo. */
        const val NAJMANJSA_APP_DP = 116
        /** Nad to sirino kartica aplikacije ni vec videti kot ikona, ampak kot plakat. */
        const val NAJVECJA_APP_DP = 140
    }
}
