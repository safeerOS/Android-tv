package si.safeer.tv.os

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.Player
import si.safeer.tv.R
import java.lang.ref.WeakReference
import java.text.Collator
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ConcurrentHashMap

/**
 * Safeer Media: glasba, video in radio z vseh virov na enem mestu, brez oglasov.
 *
 * Levo je stranski meni Safeer OS (Safeer Media je en njegov del, kot na domacem zaslonu). Desno
 * je nadzorna plosca: velike kartice Glasba, Video, Radio in Viri, "zdaj se predvaja" s tipkami
 * in hitrimi dejanji, nedavno predvajano, tvoji viri, priljubljene in priporocila. Kartica odpre
 * svoj razdelek (vrste kartic, ki jih drsis z daljincem); Nazaj se vrne na nadzorno plosco.
 *
 * Viri: Jamendo (neodvisni izvajalci, najbolj poslusano najprej), internetni radio (Radio Browser,
 * najprej domace postaje), PeerTube (vgrajeni in uporabnikovi strezniki), uporabnikovi tokovi in
 * strani ter datoteke na televizorju in napravah v Safeer Linku. Predvaja [GlasbaStoritev], zato
 * vse igra tudi v ozadju; [PredvajanjeActivity] je celozaslonski prikaz.
 *
 * Na plosci so samo dejanja, ki res delujejo - brez gumbov za se nenarejene funkcije.
 */
/** Polica zvrsti iz uporabnikovih virov se pokaze sele z vsaj toliko enotami (redke ostanejo v Filmi/Serije). */
private const val MIN_KARTIC_KATEGORIJE = 3
/** Najvec kartic na zdruzeni polici (Filmi, Serije, Video, zvrst); vse ostalo prek "Pokazi vse". */
private const val POLICA_NAJVEC = 60
/** Koliko naslednjih najboljsih tokov gre v rezervo: mrtva povezava odpove v trenutku, zato jih je lahko vec. */
private const val REZERVNIH_TOKOV = 6
/** Kakovost iz naslova in povezave kartice ([GlasbaActivity.videi]): prevedeno enkrat (prej trije Regex na vsako kartico ob vsakem izrisu). */
/** Oznaka pogleda znacke kakovosti (+ id filma), po njej [GlasbaActivity.zabeleziKakovost] najde znacko na zaslonu. */
private const val OZNAKA_KAKOVOSTI = "kakovost:"
/** CAM in TS (posnetek iz kina): opozorilna barva na kartici in v seznamu virov. */
private const val BARVA_OPOZORILA_KAKOVOSTI = 0xFFFFA24A.toInt()
private val RX_KAKOVOST_4K = Regex("(?:2160p?|4k|uhd)")
private val RX_KAKOVOST_1080 = Regex("1080p?|full[- ]?hd|fhd")
private val RX_KAKOVOST_720 = Regex("720p?|\\bhd\\b")

class GlasbaActivity : OsActivity() {

    /** Vrsta kartic; [pogled] je posebna vrsta (npr. tvoji viri), ki se narise tako, kot je. */
    private data class Vrsta(val naslov: String, val kartice: List<Kartica>, val video: Boolean = false, val pogled: View? = null, val mala: Boolean = false,
                             val mreza: Boolean = false,
                             /** Mreza cistih plakatov (Filmi | Serije): samo slika, brez okvirja, znack in napisov. */
                             val cisto: Boolean = false,
                             /** Mreza kanalov v zivo: lezece ploscice 16:9 z logotipom v celoti in imenom, brez znack. */
                             val kanali: Boolean = false,
                             /** Mreza videospotov: lezece slicice 16:9 (slika videa), naslov in izvajalec pod njo. */
                             val slicice: Boolean = false,
                             /** Mreza skladb (Glasba): na televizorju manjse kartice - sedem v vrsto, dve celi vrsti na zaslonu. */
                             val skladbe: Boolean = false,
                             /** Blok na strani z vec bloki: kaj odpre kartica »Pokazi vse« (privzeto ta blok kot cela mreza, [odpriBlok]). */
                             val vse: (() -> Unit)? = null)
    /** Podatki vrste brez zaslona - samo to gre v predpomnilnik (kartice drzijo zaslon). */
    private data class Podatki(val naslov: String, val skladbe: List<Jamendo.Skladba>, val video: Boolean = false)
    private data class Kartica(val naslov: String, val podnaslov: String, val slika: String, val klik: (View) -> Unit,
                               val dolgo: ((View) -> Unit)? = null, val ikona: Int = R.drawable.os_ikona_glasba,
                               val oznaka: String = "", val kakovost: String = "", val ocena: String = "",
                               val tvId: String = "",
                               /** Id filma pri dodatku (Stremio.razstavi), za oznako kakovosti ([KakovostZnane]); prazno = brez. */
                               val kakovostId: String = "",
                               /** Ko izbira z daljincem obstane na kartici: pripravi, kar bo klik rabil (tokovi filma), da se zacne takoj. */
                               val priprava: (() -> Unit)? = null,
                               /** Stalen kljuc kartice (id vsebine): po njem mreza ob spremembi ve, kateri pogled je kateri ([NarisanaMreza]). */
                               val id: String = "",
                               /** Kartica dejanja na koncu bloka (»Pocisti«, »Pokazi vse«): ob krajsanju bloka ostane ([blokMreze]). */
                               val dejanje: Boolean = false,
                               /** Dejanje odpre vso vsebino bloka: blok ne dobi se ene kartice »Pokazi vse«. */
                               val vse: Boolean = false)

    private val delavec = Executors.newFixedThreadPool(4)
    // Omrezno iskanje ima lasten omejen pool. Prejsnje iskanje preklicemo, da pocasni
    // strezniki ne zasedajo CPU/omrezja se dolgo po novem vnosu.
    // Brez zgornje meje: omrezna zahteva se ob preklicu ne ustavi takoj, novo iskanje pa ne sme cakati v vrsti.
    private val iskanjeDelavec = Executors.newCachedThreadPool()
    /**
     * Naloga 73: strani katalogov kanalov (TV v zivo) za vse dodatke skupaj najvec [KANALI_HKRATI] hkrati in z nizko
     * prednostjo niti - prej je sel vsak vir v neomejen [iskanjeDelavec] (213 katalogov: TV je postal pocasen). Naloge te
     * vrste ne cakajo na druge naloge iste vrste (brez zastoja).
     */
    private val kanaliDelavec = java.util.concurrent.ThreadPoolExecutor(KANALI_HKRATI, KANALI_HKRATI, 30, java.util.concurrent.TimeUnit.SECONDS,
        // Pregled Kimi 231: omejena vrsta - na sibkem TV se ob hitrem brskanju cakajoce naloge ne smejo kopiciti brez meje.
        // Polna vrsta zavrze najstarejso cakajoco (cakalci imajo vsi casovno mejo, zato nihce ne obvisi).
        java.util.concurrent.LinkedBlockingQueue(KANALI_VRSTA), { r: Runnable ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "safeer-kanali").apply { isDaemon = true }
    }, java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy()).apply { allowCoreThreadTimeOut(true) }
    /**
     * Slike (plakati, naslovnice) imajo svoje niti: prej so si jih delile z nalaganjem razdelkov in ob pocasnem
     * strezniku slik je razdelek (npr. TV v zivo) cakal nanje - prazen zaslon "Nalagam" (lastnik, 2. 10. 2026: brez zastojev).
     */
    private val slikeDelavec = Executors.newFixedThreadPool(6)
    private val iskanjeNiti = mutableListOf<Future<*>>()
    private val slikeVTeKu = ConcurrentHashMap.newKeySet<String>()
    /** Pogledi, ki cakajo isto naslovnico; tako ob prihodu slike ne prehodimo celotnega zaslona. */
    private val cakajoceSlike = ConcurrentHashMap<String, MutableList<WeakReference<ImageView>>>()
    /** TV-logotipe zamenjamo v obstojecih pogledih, zato ponovna risba ne premakne D-pad fokusa. */
    private val tvIkone = ConcurrentHashMap<String, MutableList<WeakReference<ImageView>>>()
    private val glavna = Handler(Looper.getMainLooper())

    private lateinit var koren: View
    private lateinit var meniMediji: View
    private lateinit var naslov: TextView
    private lateinit var geslo: TextView
    /** Skupna stranska vrstica Safeer OS. */
    private lateinit var stranskaVrstica: StranskaVrstica
    private lateinit var desnoOkvir: LinearLayout
    private lateinit var iskanjeGumb: View
    private lateinit var vsebina: LinearLayout
    private lateinit var drsnik: ScrollView
    private lateinit var stanje: TextView
    private lateinit var vrstica: LinearLayout
    private lateinit var zdajNaslov: TextView
    private lateinit var zdajIzvajalec: TextView
    private lateinit var zdajCas: TextView
    /** Na dotik: pravi gumb pavza/predvajaj v mali vrstici (znak ▶ se ni dal tapniti). */
    private var zdajGumb: FrameLayout? = null
    private var razdelek = DOMOV
    private var nalaganje = 0
    /** Zadnje nefiltrirane police; dialog mora ponuditi tudi trenutno izklopljen jezik. */
    private val prikazanePolice = HashMap<Int, List<Podatki>>()
    /** Isti klik med omrezno pripravo ne sme zagnati se enega razresevanja. */
    private val pripraveVTeKu = ConcurrentHashMap.newKeySet<String>()
    private var iskalnik: EditText? = null
    /** Po izbiri kartice razdelka gre fokus na prvo kartico vsebine, ko se narise. */
    private var fokusVVsebino = false
    private var videnPrej = false
    private val poslusalec: () -> Unit = { glavna.post { osveziZdaj(); narociVrnitevVidea() } }

    /**
     * Video brez slike nima smisla (lastnik, 11. 10. 2026: »zvok se predvaja, ne pa video«): ko tok sredi filma odpove
     * in storitev preklopi na rezervnega, se je zaslon predvajanja zaprl, film pa je igral naprej samo z zvokom za
     * Medijskim centrom. Ce video tece (ne pavza - Nazaj na predvajalniku ga ustavi) in nobenega zaslona predvajanja ni,
     * ga odpremo sami. Preverimo dvakrat v 1,5 s, da kratek preklop med zasloni ne odpre drugega.
     */
    private fun videoBrezZaslona(): Boolean = vOspredju && !isFinishing && !PredvajanjeActivity.odprt &&
        GlasbaStoritev.trenutna()?.video == true && GlasbaStoritev.tece()
    private val vrniVideo = Runnable {
        if (!videoBrezZaslona()) return@Runnable
        android.util.Log.i("SafeerOsMedia", "video tece brez zaslona: odpiram zaslon predvajanja")
        startActivity(Intent(this, PredvajanjeActivity::class.java))
    }
    private fun narociVrnitevVidea() {
        glavna.removeCallbacks(vrniVideo)
        if (videoBrezZaslona()) glavna.postDelayed(vrniVideo, 1500)
    }
    private val tik = object : Runnable { override fun run() { osveziZdaj(); glavna.postDelayed(this, 1_000) } }
    /**
     * Naloga 77: ali je kaj med nedavnim. Tik ga je spraseval vsako sekundo na glavni niti (razclenitev JSON-a nedavnih,
     * seznam virov, preverjanje mrtvih kanalov), ko nic ne igra - nic se ne spremeni, razen z dejanji spodaj in z
     * zacetkom predvajanja (osveziZdaj), ki ga zavrzeta.
     */
    private val imaNedavno = OsPravila.Obcasna<Boolean>(20_000)
    /** Kdaj je tik zadnjic zapisal napredek videa (uptimeMillis; -1 = se nikoli). */
    private var napredekZapisanOb = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        odprtih++
        VrsteNaslovov.pripravi(this)     // naloga 63: znane serije med filmi (v ozadju z diska)
        // Merjenje odzivnosti (samo razvojna gradnja): vsak dostop do diska/omrezja na glavni niti gre v dnevnik
        // z skladom klicev, cas do prvega izrisa pa pod SafeerOsCas. Izdajna gradnja tega ne dela.
        if (si.safeer.tv.BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            // Vsako sporocilo glavne niti, ki traja vec kot 80 ms, gre v dnevnik s cilje/klicem (razvojna gradnja).
            var zacetek = 0L; var opis = ""
            android.os.Looper.getMainLooper().setMessageLogging { x ->
                if (x.startsWith(">>>>>")) { zacetek = android.os.SystemClock.uptimeMillis(); opis = x }
                else if (x.startsWith("<<<<<")) { val d = android.os.SystemClock.uptimeMillis() - zacetek; if (d > 80) android.util.Log.w("SafeerOsCas", "pocasno sporocilo $d ms: ${opis.take(200)}") }
            }
        }
        val t0 = android.os.SystemClock.uptimeMillis()
        setContentView(zgradi())
        resources.configuration.let { znanaUsmeritev = it.orientation; znanaSirinaDp = it.screenWidthDp }
        val t1 = android.os.SystemClock.uptimeMillis()
        izberi(intent.getStringExtra(ZAVIHEK)?.let { z -> runCatching { razdelekZavihka(StranskaVrstica.Zavihek.valueOf(z)) }.getOrNull() } ?: DOMOV)
        intent.removeExtra(ZAVIHEK)
        val t2 = android.os.SystemClock.uptimeMillis()
        android.util.Log.i("SafeerOsCas", "onCreate: zgradi=${t1 - t0} ms, izberi(DOMOV)=${t2 - t1} ms")
        drsnik.post { android.util.Log.i("SafeerOsCas", "prvi izris po onCreate: ${android.os.SystemClock.uptimeMillis() - t0} ms") }
        drsnik.post { if (window.decorView.findFocus() == null || razdelek == DOMOV) vsebina.findViewWithTag<View>(KLJUC_GLASBA)?.requestFocus() }
        iskanjeIzNamena()
        predvajajIzNamena()
        sprejmiIzNamena()
        preizkusIzNamena()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(ZAVIHEK)?.let { z ->
            intent.removeExtra(ZAVIHEK)
            runCatching { StranskaVrstica.Zavihek.valueOf(z) }.getOrNull()?.let { izberi(razdelekZavihka(it), izrecno = true) }
        }
        iskanjeIzNamena()
        predvajajIzNamena()
        sprejmiIzNamena()
        preizkusIzNamena()
    }

    private fun sprejmiIzNamena() {
        val n = intent ?: return
        if (n.getBooleanExtra(EXTRA_DEBRID, false)) { n.removeExtra(EXTRA_DEBRID); window.decorView.post { if (!isFinishing) nastavitveDebrida() } }
        if (!n.getBooleanExtra(EXTRA_PREDAJA_SPREJMI, false)) return
        n.removeExtra(EXTRA_PREDAJA_SPREJMI)
        sprejmiPonudbo()
    }

    /** "Poslji na napravo": ponudbo, ki tu caka, prevzamemo kot pri "Nadaljuj z druge naprave" (isti predvajalnik, ista sekunda). */
    fun sprejmiPonudbo() {
        PredajaObvestilo.umakni(this)
        val po = Predaja.vzemiCakajoco() ?: return
        val link = LinkUpravitelj.pridobi(this)
        val naprava = link.naprave.firstOrNull { it.id == po.od } ?: LinkOdjemalec.Naprava(po.od, po.odIme, "", emptyList(), "")
        prevzemi(Predaja.Ponudba(naprava, po.skladba, po.polozajMs, po.trajanjeMs, igra = true, nazadnje = false, streznik = po.streznik,
            streznikNaprava = po.streznikNaprava), ustaviTam = false)
    }

    /** Zaslon predvajanja je en sam: obstojecega premaknemo naprej, ne zlagamo novih na sklad. */
    override fun startActivity(intent: Intent?, options: Bundle?) {
        if (intent?.component?.className == PredvajanjeActivity::class.java.name) intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        super.startActivity(intent, options)
    }

    // ------------------------------------------------------------------ VLC slog (predvajalnik na dotik)

    /** Safeer Predvajalnik na telefonu/tablici: zgornja vrstica z ⋮ in spodnji zavihki (kot VLC). */
    private val vlc by lazy { si.safeer.tv.BuildConfig.FLAVOR == "predvajalnik" &&
        !Naprava.jeTelevizor(this@GlasbaActivity) }

    private fun razdelekZavihka(z: StranskaVrstica.Zavihek) = when (z) {
        StranskaVrstica.Zavihek.DOMOV -> DOMOV
        StranskaVrstica.Zavihek.VIDEO -> VIDEO
        StranskaVrstica.Zavihek.GLASBA -> GLASBA
        // V zivo: zadnji izbrani od Radio / TV (zgornja zavihka).
        StranskaVrstica.Zavihek.V_ZIVO -> if (zadnjeVZivo == TV_V_ZIVO) TV_V_ZIVO else RADIO
        StranskaVrstica.Zavihek.BRSKAJ -> VIRI
    }

    private fun zavihekRazdelka(i: Int) = when (i) {
        DOMOV -> StranskaVrstica.Zavihek.DOMOV
        VIDEO -> StranskaVrstica.Zavihek.VIDEO
        GLASBA -> StranskaVrstica.Zavihek.GLASBA
        RADIO, TV_V_ZIVO -> StranskaVrstica.Zavihek.V_ZIVO
        VIRI -> StranskaVrstica.Zavihek.BRSKAJ
        else -> null
    }

    private var zadnjeVZivo = RADIO
    private var imeloDovoljenje = ""

    /** Brez dovoljenja za predstavnost: prijazna kartica namesto praznega mesta (kot VLC "Odobri dovoljenje"). */
    /** Dovoljenja za eno vrsto (Android 13+ loci videe in glasbo). */
    private fun dovoljenjaZa(i: Int): Array<String> = when {
        android.os.Build.VERSION.SDK_INT >= 34 && i == VIDEO -> arrayOf("android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        android.os.Build.VERSION.SDK_INT >= 33 && i == VIDEO -> arrayOf("android.permission.READ_MEDIA_VIDEO")
        android.os.Build.VERSION.SDK_INT >= 33 -> arrayOf("android.permission.READ_MEDIA_AUDIO")
        else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun imaDovoljenjeZa(i: Int) = dovoljenjaZa(i).any { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

    /** Povzetek dovoljenj (ob vrnitvi iz nastavitev vemo, ali se je kaj spremenilo). */
    private fun stanjeDovoljenj() = "${imaDovoljenjeZa(VIDEO)}${imaDovoljenjeZa(GLASBA)}"

    private fun dovoljenjeKartica(i: Int): View? {
        if (imaDovoljenjeZa(i)) return null
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isFocusable = true; isClickable = true
            setBackgroundResource(R.drawable.os_kartica_steklo)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(ikona(R.drawable.os_ikona_mapa, 28, osBarva(R.color.os_mint)))
            addView(LinearLayout(this@GlasbaActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0)
                addView(besedilo(15f, osBarva(R.color.os_besedilo), true).apply {
                    text = getString(if (i == VIDEO) R.string.os_dovoljenje_videi else R.string.os_dovoljenje_glasba) })
                addView(besedilo(12f, osBarva(R.color.os_umirjeno)).apply { text = getString(R.string.os_dovoljenje_opis); maxLines = 3 })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(besedilo(15f, osBarva(R.color.os_mint), true).apply { text = getString(R.string.os_dovoljenje_dovoli) })
            setOnClickListener { zahtevajDovoljenje(i) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        }
    }

    private fun zahtevajDovoljenje(i: Int) {
        val nastavitve = getSharedPreferences("safeer_predvajalnik", MODE_PRIVATE)
        val kljuc = "dovoljenje_vprasano_$i"
        val zeVprasano = nastavitve.getBoolean(kljuc, false)
        // Po zavrnitvi "ne sprasuj vec" Android okna ne pokaze vec - odpremo nastavitve aplikacije.
        if (zeVprasano && dovoljenjaZa(i).none { shouldShowRequestPermissionRationale(it) }) {
            try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))) }
            catch (_: Throwable) { }
            return
        }
        nastavitve.edit().putBoolean(kljuc, true).apply()
        try { requestPermissions(dovoljenjaZa(i), ZAHTEVA_PREDSTAVNOST) } catch (_: Throwable) { }
    }

    override fun onRequestPermissionsResult(zahteva: Int, dovoljenja: Array<out String>, izidi: IntArray) {
        super.onRequestPermissionsResult(zahteva, dovoljenja, izidi)
        if (zahteva != ZAHTEVA_PREDSTAVNOST) return
        imeloDovoljenje = stanjeDovoljenj()
        SEZNAMI.remove(VIDEO); SEZNAMI.remove(GLASBA); krajevno.clear()
        izberi(razdelek)
    }
    private var zivoZavihki: LinearLayout? = null

    /** Zgornja zavihka v razdelku V zivo (kot VIDEI / SEZNAMI PREDVAJANJA pri VLC): RADIO | TV. */
    private fun zgradiZivoZavihke(): View {
        val vrsta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        fun zavihek(niz: Int, cilj: Int) {
            // Crta pod izbranim zavihkom je ozadje besedila (sirina = besedilo), ne svoj pogled.
            val zavihek = besedilo(15f, osBarva(R.color.os_umirjeno), true).apply {
                text = getString(niz).uppercase(Locale.getDefault()); letterSpacing = 0.08f; maxLines = 1
                isFocusable = true; isClickable = true; tag = cilj
                setPadding(dp(4), dp(8), dp(4), dp(12))
                setOnClickListener { izberi(cilj) }
            }
            vrsta.addView(zavihek, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(24) })
        }
        zavihek(R.string.os_zavihek_radio, RADIO)
        zavihek(R.string.os_zavihek_tv, TV_V_ZIVO)
        zivoZavihki = vrsta
        return vrsta
    }

    private fun osveziVlc(i: Int) {
        if (!vlc) return
        if (i == RADIO || i == TV_V_ZIVO) zadnjeVZivo = i
        stranskaVrstica.oznaci(zavihekRazdelka(i))
        val vrsta = zivoZavihki ?: return
        vrsta.visibility = if (i == RADIO || i == TV_V_ZIVO) View.VISIBLE else View.GONE
        for (k in 0 until vrsta.childCount) {
            val zavihek = vrsta.getChildAt(k) as TextView
            val da = zavihek.tag == i
            zavihek.setTextColor(osBarva(if (da) R.color.os_mint else R.color.os_umirjeno))
            zavihek.background = if (!da) null else android.graphics.drawable.LayerDrawable(arrayOf(
                GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(osBarva(R.color.os_mint)) })).apply {
                setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL); setLayerHeight(0, dp(3)) }
        }
    }

    /** ⋮: kar pri VLC skriva meni zgoraj desno - razvrscanje, odpiranje datoteke/naslova, Link, nastavitve. */
    private fun pokaziVec() {
        val sidro = vsebina.rootView.findViewWithTag<View>("k:vec") ?: return
        val meni = android.widget.PopupMenu(this, sidro)
        val razvrsti = razdelek in listOf(DOMOV, VIDEO, GLASBA, RADIO, TV_V_ZIVO)
        if (razvrsti) meni.menu.add(0, 1, 0, R.string.os_media_razvrsti_filtriraj)
        meni.menu.add(0, 2, 1, R.string.os_odpri_datoteko_naslov)
        meni.menu.add(0, 3, 2, R.string.os_meni_naprave)
        meni.menu.add(0, 4, 3, R.string.os_meni_datoteke)
        meni.menu.add(0, 5, 4, R.string.os_meni_nastavitve)
        meni.menu.add(0, 6, 5, R.string.os_predpomnilnik_naslov)
        meni.setOnMenuItemClickListener { m ->
            when (m.itemId) {
                1 -> izberiRazvrstitevInVire(razdelek)
                2 -> odpriPredvajalnik()
                3 -> startActivity(Intent(this, NapraveActivity::class.java))
                4 -> startActivity(Intent(this, DatotekeActivity::class.java))
                5 -> startActivity(Intent(this, NastavitveActivity::class.java))
                6 -> nastavitvePredpomnilnika()
            }
            true
        }
        meni.show()
    }

    /**
     * "Odpri z" (samostojni Safeer Predvajalnik): datoteko ali naslov iz druge aplikacije predvajamo
     * prek skupne storitve [GlasbaStoritev] in odpremo zaslon predvajanja. Vsak namen obdelamo enkrat.
     */
    private fun predvajajIzNamena() {
        val n = intent ?: return
        // "Deli" iz YouTuba ali Spotifyja: povezava seznama predvajanja -> uvoz v nase sezname.
        if (n.action == Intent.ACTION_SEND && !n.getBooleanExtra(NAMEN_OBDELAN, false)) {
            n.putExtra(NAMEN_OBDELAN, true)
            val besedilo = n.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
            val posnetek = Regex("https?://\\S+").find(besedilo)?.value?.trimEnd('.', ',', ')')
            if (UvozSeznama.povezavaIz(besedilo) != null || posnetek == null) uvoziVNozadju(besedilo)
            else {
                // Deljena povezava posameznega posnetka (npr. iz YouTuba): predvajamo jo, kot bi jo odprl iz vira.
                val ime = n.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }
                    ?: (try { java.net.URL(posnetek).host.removePrefix("www.") } catch (_: Exception) { posnetek })
                razresiSplet(SpletniVir.enota(posnetek, ime, "", "", true))
            }
            return
        }
        if (n.action != Intent.ACTION_VIEW || n.getBooleanExtra(NAMEN_OBDELAN, false)) return
        val uri = n.data ?: return
        n.putExtra(NAMEN_OBDELAN, true)
        val mime = n.type ?: contentResolver.getType(uri) ?: ""
        val zvok = mime.startsWith("audio/")
        val ime = imeDatoteke(uri)
        val skladba = Jamendo.Skladba(uri.toString(), ime, "", "", uri.toString(), "", video = !zvok, mime = mime)
        GlasbaStoritev.predvajaj(this, listOf(skladba), 0)
        startActivity(Intent(this, PredvajanjeActivity::class.java))
    }

    /** Ime datoteke za naslov: pri content:// (upravitelj datotek, Prenosi) je zadnji del poti le stevilka,
     *  pravo ime da ponudnik (DISPLAY_NAME); pri file:// in http je to zadnji del poti. */
    private fun imeDatoteke(uri: android.net.Uri): String {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            } catch (_: Throwable) { }
        }
        val zadnji = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        return try { zadnji?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: uri.toString() } catch (_: Throwable) { zadnji ?: uri.toString() }
    }

    /** Naslov, ki ga je druga naprava poslala z »prikazi na TV«; ko so vsi zadetki tu, se film s tem naslovom odpre. */
    private var natancnoZa: String? = null

    /**
     * »Jungle Book na TV« (lastnik, 11. 10. 2026): TV ne pokaze splosnega iskanja, ampak odpre film ali serijo z natancno
     * tem naslovom: pri filmu iz dodatkov izbiro tokov, pri seriji sezone, drugo kot klik kartice. Ce takega zadetka ni
     * med predvajljivimi, ostane iskanje.
     */
    private fun odpriNatancnega(beseda: String, lestvica: List<Pair<Relevantnost.Zadetek<*>, Double>>) {
        val iskani = natancnoZa ?: return
        if (iskani != beseda.trim()) return
        natancnoZa = null
        val kljuc = SpletniVir.cistNaslov(iskani)
        if (kljuc.isBlank()) return
        val sk = lestvica.asSequence().mapNotNull { it.first.stvar as? Jamendo.Skladba }
            .filter { it.video && !it.radio && !Stremio.jeVZivo(it) && !znanoNiNaVoljo(it) && SpletniVir.cistNaslov(it.naslov) == kljuc }
            .firstOrNull() ?: return
        // Naloga 112: id kanala ali dodatka vsebuje naslov (lahko z zetonom) - v dnevnik samo ime.
        android.util.Log.i("SafeerOsMedia", "prikazi na TV: odpiram ${sk.naslov.take(60)}")
        // Film iz dodatkov: pokazemo izbiro tokov (naslov na vrhu), ne predvajamo prvega sami - prvi tok dodatka je bil
        // pri preizkusu 11. 10. 2026 kopija z vgrajenim oglasom za stave. Uporabnik izbere z daljincem.
        if (Stremio.jeEnota(sk)) { glavna.postDelayed({ if (!isFinishing && !isDestroyed) razresiStremio(sk, rocno = true) }, 400); return }
        val kartica = videi(listOf(sk), beseda).firstOrNull() ?: return
        glavna.postDelayed({ if (!isFinishing) kartica.klik(iskalnik ?: window.decorView) }, 400)
    }

    /**
     * Naloga 135: samodejni preizkus po gradnji ([PreizkusActivity], samo adb): film ali epizoda po id-ju IMDb gre po isti
     * poti kot OK na kartici. Id nosi [PreizkusPravila.OZNAKA] - ogled ne gre v zgodovino in shranjena mesta.
     */
    private fun preizkusIzNamena() {
        val imdb = intent.getStringExtra(PREIZKUS_IMDB) ?: return
        val naslov = intent.getStringExtra(PREIZKUS_NASLOV)
        val zeton = intent.getStringExtra(PREIZKUS_ZETON)
        intent.removeExtra(PREIZKUS_IMDB); intent.removeExtra(PREIZKUS_NASLOV); intent.removeExtra(PREIZKUS_ZETON)
        // Pregled B2: GlasbaActivity (okus predvajalnik) in vzdevek UvozSeznamaVhod (telefon, tablica) sta izvozena - druga
        // aplikacija bi sicer tiho predvajala prek uporabnikovega Useneta/TorBoxa brez sledi v zgodovini. Velja samo enkratni
        // zeton, ki ga je v tem procesu izdala PreizkusActivity (zascitena z android.permission.DUMP).
        if (!vzemiZetonPreizkusa(zeton)) {
            android.util.Log.w("SafeerOsMedia", "preizkus po gradnji brez veljavnega zetona - prezrt")
            return
        }
        val v = PreizkusPravila.vnos(imdb, naslov) ?: return
        android.util.Log.i("SafeerOsMedia", "preizkus po gradnji: ${v.tip} ${v.imdb}" + (if (v.epizoda > 0) " S${v.sezona}E${v.epizoda}" else ""))
        val osnova = Stremio.vnosPoImdb(v.tip, v.imdb, v.naslov, 0)
        val sk = osnova.copy(id = osnova.id + PreizkusPravila.OZNAKA).let {
            if (v.epizoda > 0) it.copy(naslov = "${v.naslov} · S${v.sezona}E${v.epizoda}", season = v.sezona, episode = v.epizoda) else it
        }
        predvajaj(listOf(sk), 0)
    }

    /** Iskanje, odprto od drugod (zaslon predvajanja, tipka Isci): razdelek Iskanje, polje pripravljeno. */
    private fun iskanjeIzNamena() {
        val beseda = intent.getStringExtra(ISKANJE_BESEDA) ?: return
        intent.removeExtra(ISKANJE_BESEDA)
        // »Prikazi na TV« z druge naprave (media.open): film ali serija z natancno tem naslovom se odpre sama.
        natancnoZa = if (intent.getBooleanExtra(ISKANJE_NATANCNO, false)) beseda.trim() else null
        intent.removeExtra(ISKANJE_NATANCNO)
        odpriIskanje(beseda)
    }

    /** Safeer Link je med odprto Safeer Media povezan: enotno iskanje vprasa tudi naprave v Linku. */
    private val link by lazy { LinkUpravitelj.pridobi(this) }
    private val linkPoslusalec = object : LinkOdjemalec.Poslusalec {
        override fun naStanje(povezan: Boolean, sporocilo: String) { glavna.post { if (!isFinishing) torrentSeJeSpremenil() } }
        // Ko so naprave v Linku znane (ob zagonu traja hip), uskladimo sezname predvajanja z njimi.
        override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) { glavna.post { if (!isFinishing) { uskladiSezname(); torrentSeJeSpremenil() } } }
        override fun naNaslov(url: String, naslov: String, od: String) { }
        override fun naBesedilo(besedilo: String, od: String) { }
        override fun naZavrnitev() { }
    }

    /** Odgovore dodatkov je dopolnila druga naprava v Linku ([SeznamiSink]): odprta mreza se uredi znova. */
    private val obRazpolozljivosti: () -> Unit = { glavna.post { if (!isFinishing && odprtKatalog != null) narociUmiri() } }

    /**
     * Medijski center je na zaslonu. Ko ni (Domov, drug program, ugasnjen zaslon), dodatkov ne sprasujemo: preverjanje
     * mreze je prej v ozadju teklo do konca proracuna in trosilo poizvedbe, ki jih dom nima na pretek.
     */
    @Volatile private var vOspredju = false

    /** Lastnik 27. 10. 2026: mini predvajalnik tudi v Medijskem centru (prej samo na domacem zaslonu). */
    private var dock: MiniPredvajalnik? = null
    private val dockPoslusalec: () -> Unit = { runOnUiThread { if (!isFinishing && !isDestroyed) dock?.osvezi() } }

    override fun onStart() {
        super.onStart()
        // Samostojni Predvajalnik: to je njegov domaci zaslon - nova razlicica s safeer.si se ponudi tu (tiha pasica).
        if (si.safeer.tv.BuildConfig.FLAVOR == "predvajalnik") Posodobitve.ponudiCeJeCas(this)
        Ozadje.uporabi(this, koren)
        if (!link.jeKrajevni()) link.dodaj(linkPoslusalec)
        vOspredju = true
        nadaljujDebridObVrnitvi()     // pregled 2
        torrentSeJeSpremenil()
        Razpolozljivost.obSpremembi = obRazpolozljivosti
        // Preverjanje odprte mreze, ustavljeno ob odhodu z zaslona, se nadaljuje.
        if (odprtKatalog != null) narociUmiri()
        GlasbaStoritev.poslusalci.add(poslusalec)
        // Mini predvajalnik (skladba z Useneta, radio, seznam): upravljanje brez celozaslonskega predvajalnika. Naslov odpre
        // predvajalnik; Safeer Player (samostojna aplikacija) ga ima prav tako.
        if (dock == null) dock = MiniPredvajalnik.naOknu(this) {
            try { startActivity(Intent(this, PredvajanjeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) } catch (_: Exception) { }
        }
        GlasbaStoritev.poslusalci.add(dockPoslusalec)
        OddaljenoPredvajanje.poslusalci.add(dockPoslusalec)
        dock?.osvezi()
        narociVrnitevVidea()
        // Naloga 124: kaj je novega v posodobljenem dodatku (enkrat).
        pokaziSpremembeDodatkov()
        // Naloga 129: kljuci debrid z druge naprave (potrditev).
        Debrid.poslusalciPrejetih.add(obPrejetihKljucih)
        pokaziPrejeteKljuce()
        // Samodejno usklajevanje kljucev in dodatkov v ozjem krogu: potrditev nove naprave (enkrat) in uskladitev ob odprtju.
        UskladitevKljucev.poslusalci.add(obUskladitvi)
        pokaziCakajoceUskladitve()
        pokaziSpremembeUskladitve()
        // Grok 46, Ö3: geslo seznama je bilo deljeno (lastnik) ali seznam z racunom z druge naprave (prejemnik) - enkrat.
        MedijskiViri.poslusalciRacunov.add(obRacunih)
        pokaziRacune()
        UskladitevKljucev.sprozi(this, 1_500)
        // Kanal, ki je odpovedal, medtem ko je bil na zaslonu predvajalnik, in postaja, ki odpove tukaj.
        MrtviKanali.poslusalci.add(obMrtvemKanalu)
        umakniMrtvega()
        PregledKanalov.obSpremembi = obPregleduKanalov
        // Naloga 87: zasedenost predpomnilnika na kartici v Mojih virih (izracun v ozadju).
        UsbPredpomnilnik.obSpremembi = obPregleduKanalov
        UsbPredpomnilnik.osveziStanje(this)
        glavna.post(tik)
        uskladiSezname()
        // Ob vrnitvi (npr. iz predvajanja) sta se nedavno in stanje predvajanja lahko spremenila.
        // Odprta podstran (seznam predvajanja, »Pokazi vse«) ostane: nedavnega in plosce na njej ni.
        // Vrnitev (npr. s predvajalnika): stran na mesto kartice, s katere je uporabnik odsel (zapomnjeno ob dotiku).
        val odhod = mestoKlika?.takeIf { it.first == razdelek }?.second
        mestoKlika = null
        if (videnPrej && razdelek == DOMOV && !podstrani.odprta) izberiPriKartici(DOMOV, odhod)
        // Po ogledu (Nazaj s predvajanja) osvezimo napredek na kartici videa z naprave.
        // Video: znova narisemo (iz predpomnilnika, brez omrezja), da kartica in "Nadaljuj gledanje" pokazeta novo mesto.
        // Odprta mreza (Filmi | Serije) ostane tam, kjer je bila - po ogledu se uporabnik vrne na isti plakat.
        if (videnPrej) odprtKatalog?.takeIf { it.dodatek.isNotBlank() }?.let { o ->
            delavec.execute {
                val polica = try { prenosiNaRacunalnikih(zasebniZa = o.dodatek, cakajS = 4) } catch (_: Exception) { return@execute }
                glavna.post {
                    if (isFinishing || odprtKatalog !== o || polica.map { it.id to it.izvajalec } == o.prenosi.map { it.id to it.izvajalec }) return@post
                    o.prenosi = polica
                    narisiKatalog(o.naslov)
                }
            }
        }
        if (videnPrej && vlc && razdelek == VIDEO && odprtKatalog == null) { if (podstrani.odprta) osveziNaMestu() else izberiPriKartici(VIDEO, odhod) }
        else if (videnPrej && razdelek == GLASBA) naloziKrajevno(razdelek)
        // Dovoljenje, dano v nastavitvah aplikacije: ob vrnitvi takoj pokazemo, kar je na napravi.
        if (vlc && videnPrej && imeloDovoljenje != stanjeDovoljenj()) {
            imeloDovoljenje = stanjeDovoljenj()
            SEZNAMI.remove(VIDEO); SEZNAMI.remove(GLASBA); krajevno.clear()
            if ((razdelek == VIDEO || razdelek == GLASBA) && odprtKatalog == null) izberi(razdelek)
        }
        videnPrej = true
    }

    /** Pregled 2: uporabnik zares odhaja (tipka Domov, druga aplikacija) - cakanje na debrid/Usenet se prekine. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        prekiniDebrid()
    }

    override fun onStop() {
        skrijPripravljam()      // predvajalnik je odprt ali uporabnik je odsel: pas ne ostane za vrnitev
        Debrid.poslusalciPrejetih.remove(obPrejetihKljucih)
        UskladitevKljucev.poslusalci.remove(obUskladitvi)
        MedijskiViri.poslusalciRacunov.remove(obRacunih)
        // Pregled 2: cakanja na debrid/Usenet onStop ne prekine (ugasnjen zaslon, ohranjevalnik TV) - prekine ga odhod
        // uporabnika (onUserLeaveHint), nova izbira, Preklici ali onDestroy; izid med ustavitvijo pocaka na onStart.
        // Mesto dotika velja za vrnitev samo, ce je ta dotik zaslon zapustil (predvajalnik se odpre v nekaj sekundah);
        // sicer je zastarelo (uporabnik je po dotiku ostal tu in odsel pozneje drugam).
        if (android.os.SystemClock.uptimeMillis() - mestoKlikaOb > 10_000L) mestoKlika = null
        link.odstrani(linkPoslusalec)
        GlasbaStoritev.poslusalci.remove(poslusalec)
        GlasbaStoritev.poslusalci.remove(dockPoslusalec)
        OddaljenoPredvajanje.poslusalci.remove(dockPoslusalec)
        dock?.ustavi()
        MrtviKanali.poslusalci.remove(obMrtvemKanalu)
        if (Razpolozljivost.obSpremembi === obRazpolozljivosti) Razpolozljivost.obSpremembi = null
        if (PregledKanalov.obSpremembi === obPregleduKanalov) PregledKanalov.obSpremembi = null
        if (UsbPredpomnilnik.obSpremembi === obPregleduKanalov) UsbPredpomnilnik.obSpremembi = null
        // Naloga 75: Safeer gre v ozadje - pregled kanalov dodatkov, ki je na vrsti (najvec enkrat na dan, ne med predvajanjem).
        if (!isFinishing) PregledKanalov.obcasno(applicationContext, stremioNaslovi(), seznamiTv())
        vOspredju = false
        glavna.removeCallbacks(tik)
        super.onStop()
    }

    override fun onDestroy() {
        odprtih = (odprtih - 1).coerceAtLeast(0)
        synchronized(iskanjeNiti) { iskanjeNiti.forEach { it.cancel(true) }; iskanjeNiti.clear() }
        iskanjeDelavec.shutdownNow()
        slikeDelavec.shutdownNow()
        delavec.shutdownNow()
        preverjanje.shutdownNow()
        preverjanjeEpizod.shutdownNow()
        preverjanjeKanalov.shutdownNow()
        // Naloga 86 (M2): mreza kanalov gre z zaslonom - njene naloge in poslusalec pregleda zasebnih kategorij (staticni
        // PREGLED_ZASEBNIH) ne drzijo vec te Activity.
        mrezaKanalov?.preklici()
        semeDelavec.shutdownNow()
        kanaliDelavec.shutdownNow()
        nalaganjeKanalov.shutdownNow()
        pregledZasebnihNit.shutdownNow()
        odklepNit.shutdownNow()      // naloga 99 (pregled 231, N2)
        prekiniDebrid()              // pregled 2
        debridDelavec.shutdownNow()  // pregled A1
        glavna.removeCallbacks(umiri)
        tvIkone.clear()
        super.onDestroy()
    }

    /** Nazaj iz razdelka vrne na nadzorno plosco; s plosce zapusti Safeer Media. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Odprt mini predvajalnik se najprej skrije (kot na domacem zaslonu).
        if (dock?.skrijCeOdprt() == true) return
        // Podstran (»Pokazi vse« bloka, seznam predvajanja, epizode, izvajalec): nazaj na stran, s katere je bila odprta,
        // na isto mesto - tudi kadar je bila odprta z druge podstrani ali iz mreze.
        if (podstrani.odprta) { zapriPodstran(); return }
        // Naloga 90c: iskanje - najprej zapre tipkovnico, nato preklice iskanje in gre na zaslon, iz katerega je bilo odprto.
        if (razdelek == ISKANJE) {
            val polje = iskalnik
            if (polje != null && (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(polje.windowToken, 0)) {
                polje.showSoftInputOnFocus = false
                return
            }
            ++nalaganje
            synchronized(iskanjeNiti) { iskanjeNiti.forEach { it.cancel(true) }; iskanjeNiti.clear() }
            zadetki = null
            izberi(OsPravila.ciljNazajIzIskanja(iskanjeIz, ISKANJE, DOMOV))
            return
        }
        // Mreza Filmi | Serije je domaci pogled razdelka Video: Nazaj iz nje gre na plosco, ne v isti razdelek.
        val domacaMreza = odprtKatalog?.tip?.isNotBlank() == true && videoNacin().isNotEmpty()
        if (odprtKatalog != null && !domacaMreza) { odprtKatalog = null; izberi(odprtKatalogIz); return }
        if (razdelek != DOMOV) {
            // Razdelek, odprt s »Pokazi vse« bloka na plosci: nazaj na ta blok, ne na vrh plosce.
            val izBloka = izBlokaDomov?.takeIf { it.first == razdelek }
            if (izBloka != null) {
                mestoVrnitve(izBloka.second.y, izBloka.second.kljuc, izBloka.second.odmik, DOMOV)
                izberi(DOMOV)
                return
            }
            val kljuc = when (razdelek) { GLASBA -> KLJUC_GLASBA; VIDEO -> KLJUC_VIDEO; RADIO -> KLJUC_RADIO; VIRI -> KLJUC_VIRI; TV_V_ZIVO -> KLJUC_TV; else -> KLJUC_GLASBA }
            izberi(DOMOV)
            drsnik.post { vsebina.findViewWithTag<View>(kljuc)?.requestFocus() }
            return
        }
        super.onBackPressed()
    }

    // ------------------------------------------------------------------ postavitev

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** TV profil: na 16:9 televizorju uporabimo prostor bolj vodoravno in vecji Now Playing. */
    private fun jeTv() = Naprava.jeTelevizor(this@GlasbaActivity)
    private fun jeSirokTv() = jeTv() && resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE


    private fun besedilo(vel: Float, barva: Int, krepko: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, vel); setTextColor(barva); maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        if (krepko) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    private fun ikona(res: Int, vel: Int, barva: Int? = null) = ImageView(this).apply {
        setImageResource(res); scaleType = ImageView.ScaleType.FIT_CENTER
        if (barva != null) imageTintList = ColorStateList.valueOf(barva)
        layoutParams = LinearLayout.LayoutParams(dp(vel), dp(vel))
    }

    /** Okrogel gumb: pod fokusom mint obroba, sicer prozoren. */
    private fun ozadjeGumba(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(osBarva(R.color.os_kartica_dvignjena)); setStroke(dp(2), osBarva(R.color.os_mint)) })
        addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0) })
    }

    private fun gumb(res: Int, vel: Int, kljuc: String, klik: () -> Unit) = FrameLayout(this).apply {
        tag = kljuc
        isFocusable = true; isClickable = true
        background = ozadjeGumba()
        setOnClickListener { klik() }
        addView(ImageView(this@GlasbaActivity).apply { setImageResource(res); imageTintList = ColorStateList.valueOf(osBarva(R.color.os_besedilo)) },
            FrameLayout.LayoutParams(dp(vel * 5 / 9), dp(vel * 5 / 9), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(vel), dp(vel)).apply { marginEnd = dp(6) }
    }

    /** Telefon pokonci: prostor gre vsebini, meni ostane kot stolpec ikon (prej je vzel dve tretjini). */
    private fun ozekZaslon() = resources.configuration.screenWidthDp < 600

    private fun prilagodiMeni() {
        val ozek = ozekZaslon()
        stranskaVrstica.prilagodiSirino()
        desnoOkvir.setPadding(dp(if (ozek) 14 else if (jeSirokTv()) 30 else 28), dp(10), dp(if (ozek) 14 else if (jeSirokTv()) 30 else 28), dp(8))
    }

    private var znanaUsmeritev = 0
    private var znanaSirinaDp = 0
    /** Stevec zasukov: risanje za novo sirino, ki ga prehiti naslednji zasuk, se ne izvede. */
    private var zasukov = 0

    /**
     * Zasuk brez novega zaslona (configChanges): meni takoj, stran pa, ko je znana nova sirina vsebine. Mere mrez so
     * odvisne od nje, spremeni pa se sele s postavitvijo po zasuku: prej je bila stran narisana takoj, se za staro sirino
     * (kartice na desni odrezane in nedosegljive ali ozka mreza ob robu), zadetki iskanja pa sploh ne. Stran ostane ista
     * (tudi odprta podstran), narisana od vrha.
     */
    override fun onConfigurationChanged(novo: android.content.res.Configuration) {
        super.onConfigurationChanged(novo)
        prilagodiMeni()
        // Tipkovnica ali daljinec (keyboard, navigation) velikosti ne spremeni: strani ni treba risati znova.
        if (novo.orientation == znanaUsmeritev && novo.screenWidthDp == znanaSirinaDp) return
        znanaUsmeritev = novo.orientation; znanaSirinaDp = novo.screenWidthDp
        val moj = ++zasukov
        val prej = vsebina.width
        var narisano = false
        fun narisiZaNovoSirino() {
            if (narisano || moj != zasukov || isFinishing) return
            narisano = true
            naMestuDo = 0L
            when {
                podstrani.odprta -> narisiPodstran()
                razdelek == ISKANJE -> narisiIskanje()
                else -> izberi(razdelek)
            }
        }
        val poslusalec = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, levo: Int, vrh: Int, desno: Int, dno: Int, prejLevo: Int, prejVrh: Int, prejDesno: Int, prejDno: Int) {
                // Postavitev se s staro sirino (okno se ni dobilo nove velikosti): cakamo na naslednjo.
                if (desno - levo == prej && !narisano && moj == zasukov) return
                v.removeOnLayoutChangeListener(this)
                v.post { narisiZaNovoSirino() }
            }
        }
        vsebina.addOnLayoutChangeListener(poslusalec)
        // Sirina vsebine je ostala enaka (ali postavitve ni bilo): narisemo vseeno, da razpored ustreza novi usmeritvi.
        glavna.postDelayed({ vsebina.removeOnLayoutChangeListener(poslusalec); narisiZaNovoSirino() }, 700)
    }

    private fun zgradi(): View {
        val beli = osBarva(R.color.os_besedilo)
        val desno = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(if (jeSirokTv()) 30 else 28), dp(10), dp(if (jeSirokTv()) 30 else 28), dp(8)) }
        desnoOkvir = desno

        // Glava: naslov in opis razdelka, desno geslo in iskanje
        val glava = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val levo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        naslov = besedilo(if (vlc) 22f else 28f, beli, true).apply { typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) }
        // Opis razdelka sme v dve vrstici: na ozkem telefonu (pokoncno) bi se sicer odrezal.
        stanje = besedilo(14f, osBarva(R.color.os_umirjeno)).apply { maxLines = if (ozekZaslon()) 3 else 2 }
        levo.addView(naslov); levo.addView(stanje)
        // Dolgo ime ("Medijski center" ob stranski vrstici na telefonu, "Safeer Predvajalnik") se zmanjsa, ne odreze.
        naslov.maxLines = 1
        naslov.setAutoSizeTextTypeUniformWithConfiguration(15, if (vlc) 22 else 28, 1, TypedValue.COMPLEX_UNIT_SP)
        if (vlc) {
            // Zgornja vrstica kot pri VLC: znak, ime razdelka, iskanje in ⋮. Stalni opis razdelka
            // skrijemo (prostor gre vsebini); sporocila (nalagam, napaka, prazno) ostanejo vidna.
            glava.addView(ikona(R.drawable.os_znak, 30, null), LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) })
            stanje.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(t: android.text.Editable?) {
                    stanje.visibility = if (t.isNullOrBlank() || t.toString() == opis(razdelek)) View.GONE else View.VISIBLE
                }
            })
            stanje.visibility = View.GONE
        }
        // Telefon pokonci: gumb menija skrite stranske vrstice gre pred naslov (StranskaVrstica).
        levo.tag = StranskaVrstica.OZNAKA_PRED
        glava.addView(levo, LinearLayout.LayoutParams(0, -2, 1f))
        geslo = TextView(this).apply {
            text = getString(R.string.os_media_geslo); setTextColor(osBarva(R.color.os_umirjeno))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); gravity = Gravity.END; setPadding(0, 0, dp(16), 0)
        }
        glava.addView(geslo)
        iskanjeGumb = gumb(R.drawable.os_ikona_isci, 48, "k:iskanje") { odpriIskanje("") }
        glava.addView(iskanjeGumb)
        if (vlc) glava.addView(gumb(R.drawable.os_ikona_vec, 48, "k:vec") { pokaziVec() }.apply {
            contentDescription = getString(R.string.os_vec_moznosti) })
        desno.addView(glava)
        if (vlc) desno.addView(zgradiZivoZavihke(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        vsebina = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(8)) }
        Stremio.mapaPredpomnilnika = java.io.File(cacheDir, "stremio")
        Stremio.pripravi(this)
        drsnik = ScrollView(this).apply { addView(vsebina); isFillViewport = true; isVerticalScrollBarEnabled = false }
        // Odprta mreza (Filmi | Serije, katalog): naslednja stran se nalozi sama, ko se uporabnik priblizuje koncu.
        // Kartice se premaknejo tudi brez drsenja (mreza se dopolni na mestu, kartica izgine): slike uskladimo po postavitvi.
        vsebina.viewTreeObserver.addOnGlobalLayoutListener { narociUskladiSlike() }
        // Kartica, ki je ostala zaradi izbire (naloga 55b), gre, ko se izbira premakne.
        vsebina.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> odprtKatalog?.takeIf { it.drzim }?.let { narociUmiri() } }
        drsnik.setOnScrollChangeListener { _, _, y, _, _ ->
            // Plakati, ki pridejo na zaslon, dobijo sliko; tisti, ki gredo z njega, jo sprostijo (naloga 56).
            if (kotlin.math.abs(y - uskladenPri) >= dp(48)) uskladiSlike()
            val o = odprtKatalog
            if (o != null && !o.nalagam && vsebina.height - (y + drsnik.height) < dp(700)) vecMreze(o, izDrsenja = true)
            // Mreza kanalov: naslednja stran pride sama, ko se uporabnik priblizuje koncu (najvec enkrat na dobre pol sekunde).
            val m = mrezaKanalov
            if (m != null && !m.nalagam && (m.seKaj || m.naprej) && vsebina.height - (y + drsnik.height) < dp(500) &&
                android.os.SystemClock.uptimeMillis() - m.zadnjicVec > 700) vecKanalov(m)
            // Naloga 74: v ozadju preverjamo samo kartice na zaslonu in naslednjo vrsto - ob drsenju nove.
            if (m != null) narociPreverjanje(m)
            val mv = mrezaVsebine
            if (mv != null && !mv.nalagam && mv.seKaj && vsebina.height - (y + drsnik.height) < dp(500) &&
                android.os.SystemClock.uptimeMillis() - mv.zadnjicVec > 700) naloziVecMreze(mv)
        }
        desno.addView(drsnik, LinearLayout.LayoutParams(-1, 0, 1f))

        // Mala vrstica "zdaj se predvaja" v razdelkih (na plosci je velika)
        vrstica = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(osBarva(R.color.os_kartica_dvignjena)); setStroke(dp(1), osBarva(R.color.os_crta)) }
            isFocusable = true; isClickable = true
            setOnClickListener { startActivity(Intent(this@GlasbaActivity, PredvajanjeActivity::class.java)) }
            setOnFocusChangeListener { v, f -> (v.background as GradientDrawable).setStroke(dp(if (f) 2 else 1), osBarva(if (f) R.color.os_mint else R.color.os_crta)) }
            visibility = View.GONE
        }
        val besedila = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        zdajNaslov = besedilo(15f, beli, true); zdajIzvajalec = besedilo(13f, osBarva(R.color.os_umirjeno))
        besedila.addView(zdajNaslov); besedila.addView(zdajIzvajalec)
        vrstica.addView(besedila, LinearLayout.LayoutParams(0, -2, 1f))
        zdajCas = besedilo(14f, osBarva(R.color.os_mint))
        vrstica.addView(zdajCas)
        if (!Naprava.jeTelevizor(this@GlasbaActivity)) {
            zdajCas.visibility = View.GONE
            zdajGumb = gumb(R.drawable.os_ikona_pavza, 44, "k:mala-pavza") {
                GlasbaStoritev.preklopi(); osveziZdaj()
            }.also { g -> g.contentDescription = getString(R.string.os_mediji_predvajaj_pavza); vrstica.addView(g) }
        }
        desno.addView(vrstica, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        // Tipke daljinca samo na televizorju; tablica se upravlja z dotikom.
        if (Naprava.jeTelevizor(this@GlasbaActivity))
            desno.addView(pomoc(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        stranskaVrstica = StranskaVrstica.ovij(this, desno, StranskaVrstica.Razdelek.MEDIJI)
        stranskaVrstica.naZavihek = { z -> izberi(razdelekZavihka(z), izrecno = true); true }
        imeloDovoljenje = stanjeDovoljenj()
        meniMediji = stranskaVrstica.aktivnaPostavka
        koren = stranskaVrstica
        return stranskaVrstica
    }

    /** Vrstica pomoci spodaj: tipke daljinca. */
    private fun pomoc(): View {
        val v = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        fun tipka(t: String, opis: Int) {
            v.addView(besedilo(11f, osBarva(R.color.os_besedilo), true).apply {
                text = t; setBackgroundResource(R.drawable.os_tipka); setPadding(dp(8), dp(2), dp(8), dp(2)) })
            v.addView(besedilo(12f, osBarva(R.color.os_umirjeno)).apply { text = getString(opis); setPadding(dp(6), 0, dp(18), 0) })
        }
        tipka("OK", R.string.os_media_pomoc_izberi)
        tipka("↩", R.string.os_media_pomoc_nazaj)
        tipka("OK ●", R.string.os_media_pomoc_meni)
        tipka("🔍", R.string.os_media_pomoc_isci)
        return v
    }

    /** Kartica; [mala] je za nedavno na plosci (manjsa, samo naslov), da vrsta ostane na zaslonu. */
    /**
     * Cist plakat za mrezo Filmi | Serije: samo slika z zaobljenimi robovi - brez okvirja, znack in napisov,
     * kot uporabniki poznajo iz drugih predvajalnikov (lastnik, 2. 10. 2026). Naslov je pod sliko in je viden
     * le, dokler plakata ni (ali ce ga vir nima); izbira z daljincem je mint rob.
     */
    /**
     * Plakat 2:3, visina sledi sirini: vrstica mreze se sama prilagodi sirini vsebine (na televizorju se stranski
     * meni zozi in razsiri) - brez ponovnega risanja in brez praznega roba.
     */
    private class Plakat(c: android.content.Context) : FrameLayout(c) {
        override fun onMeasure(w: Int, h: Int) {
            val sirina = MeasureSpec.getSize(w)
            super.onMeasure(MeasureSpec.makeMeasureSpec(sirina, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sirina * 3 / 2, MeasureSpec.EXACTLY))
        }
    }

    private fun cistPlakat(k: Kartica, prva: Boolean, kljuc: String): View {
        val polmer = dp(12).toFloat()
        val plakat = Plakat(this).apply {
            background = GradientDrawable().apply { cornerRadius = polmer; setColor(osBarva(R.color.os_kartica)) }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, polmer) }
            }
            // Okvir izbire rise plakat, izbiro pa ima cela kartica (plakat z naslovom).
            isDuplicateParentStateEnabled = true
            foreground = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = polmer; setColor(0); setStroke(dp(3), osBarva(R.color.os_mint)) })
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { cornerRadius = polmer; setColor(0x33FFFFFF) })
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0))
            }
            if (k.slika.isBlank()) {
                addView(ImageView(this@GlasbaActivity).apply { setImageResource(k.ikona); scaleType = ImageView.ScaleType.FIT_CENTER },
                    FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
            } else {
                val slika = ImageView(this@GlasbaActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                addView(slika, FrameLayout.LayoutParams(-1, -1))
                slikaKartice(k.slika, slika)
            }
            // Preverba TV 28. 10. 2026: mreza Filmi | Serije (cisti plakati) oznake kakovosti ni imela - samo police. Ista znacka
            // (»★ 4K«, »⚠ CAM«), ki jo priprava tokov osvezi na mestu ([zabeleziKakovost]).
            if (k.kakovost.isNotBlank() || k.kakovostId.isNotBlank()) addView(besedilo(9f, barvaKakovosti(k.kakovost), true).apply {
                nastaviZnackoKakovosti(this, k.kakovost, barvaKakovosti(k.kakovost))
                setPadding(dp(6), dp(2), dp(6), dp(2))
                tag = k.kakovostId.takeIf { it.isNotBlank() }?.let { OZNAKA_KAKOVOSTI + it }
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = dp(6); topMargin = dp(6) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = kljuc
            contentDescription = k.naslov
            isFocusable = true; isClickable = true
            setOnClickListener { zadnjaKartica = java.lang.ref.WeakReference(it); zapomniMestoKlika(); k.klik(it) }
            k.dolgo?.let { d ->
                setOnLongClickListener { d(it); true }
                setOnKeyListener { v, koda, dogodek ->
                    if (dogodek.action == KeyEvent.ACTION_DOWN && (koda == KeyEvent.KEYCODE_MENU || koda == KeyEvent.KEYCODE_BUTTON_X ||
                            koda == KeyEvent.KEYCODE_BUTTON_Y || koda == KeyEvent.KEYCODE_DEL || koda == KeyEvent.KEYCODE_FORWARD_DEL)) { d(v); true } else false
                }
            }
            if (prva) nextFocusLeftId = meniMediji.id
            k.priprava?.let { pripravi ->
                setOnFocusChangeListener { v, fokus ->
                    if (fokus) v.postDelayed({ if (v.isFocused && !isFinishing) iskanjeDelavec.execute { try { pripravi() } catch (_: Exception) { } } }, 600)
                }
            }
            addView(plakat, LinearLayout.LayoutParams(-1, -2))
            // Naslov pod vsakim plakatom (lastnik, 5. 10. 2026: »pri nekaterih filmih ni naslova«) - vedno dve vrstici, da so
            // kartice v vrsti enako visoke.
            addView(besedilo(if (jeTv()) 11f else 12f, osBarva(R.color.os_besedilo)).apply {
                text = k.naslov; setLines(2); ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(2), dp(4), dp(2), 0)
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /**
     * Slika ploscice kanala: dokler logotipa ni, majhna ikona na sredini; logotip se pokaze cel, z robom (ne odrezan).
     * Temen logotip s prozornim ozadjem dobi svetlo podlago ([OsPravila.svetlaPodlaga]) - na temni ploscici se ni videl.
     */
    private class LogoKanala(c: android.content.Context, private val rob: Int, private val temna: Int, private val svetla: Int) : ImageView(c) {
        override fun setImageDrawable(d: android.graphics.drawable.Drawable?) {
            if (d != null) setPadding(rob, rob, rob, rob)
            val b = (d as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (b != null && !b.isRecycled) setBackgroundColor(if (jeTemen(b)) svetla else temna)
            super.setImageDrawable(d)
        }

        private fun jeTemen(b: android.graphics.Bitmap): Boolean = try {
            val m = android.graphics.Bitmap.createScaledBitmap(b, 16, 16, true)
            val piksli = IntArray(256).also { m.getPixels(it, 0, 16, 0, 0, 16, 16) }
            if (m !== b) m.recycle()
            OsPravila.svetlaPodlaga(piksli)
        } catch (_: Throwable) { false }
    }

    private fun kartica(k: Kartica, video: Boolean, prva: Boolean, kljuc: String, mala: Boolean = false, velikostDp: Int = 0,
                        /** Kanal v zivo: lezeca ploscica 16:9, logotip v celoti, ime v dveh vrsticah, brez znack ([Vrsta.kanali]). */
                        kanal: Boolean = false,
                        /** Videospot: lezeca slicica 16:9 namesto pokoncnega plakata ([Vrsta.slicice]). */
                        slicica: Boolean = false): View {
        // Video katalog na televizorju uporablja pokoncne plakate: vec naslovov je
        // hkrati vidnih, slika pa ni odrezana v sirok 16:9 trak. Majhne kartice "Nedavno" ostanejo 16:9.
        val plakat = video && !mala && !kanal && !slicica
        val sirina = dp(if (velikostDp > 0) velikostDp else if (mala) 96 else if (plakat) 112 else 150)
        val visina = if (kanal || slicica || (mala && video)) sirina * 9 / 16 else if (plakat) sirina * 3 / 2 else sirina
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = kljuc
            if (mala) setPadding(dp(3), dp(3), dp(3), dp(3)) else setPadding(dp(6), dp(6), dp(6), dp(8))
            setBackgroundResource(R.drawable.os_ploscica_app)
            isFocusable = true; isClickable = true
            setOnClickListener { zadnjaKartica = java.lang.ref.WeakReference(it); zapomniMestoKlika(); k.klik(it) }
            k.dolgo?.let { d ->
                setOnLongClickListener { d(it); true }
                setOnKeyListener { v, koda, dogodek ->
                    if (dogodek.action == KeyEvent.ACTION_DOWN) {
                        if (koda == KeyEvent.KEYCODE_MENU || koda == KeyEvent.KEYCODE_BUTTON_X ||
                            koda == KeyEvent.KEYCODE_BUTTON_Y || koda == KeyEvent.KEYCODE_DEL ||
                            koda == KeyEvent.KEYCODE_FORWARD_DEL) {
                            d(v)
                            return@setOnKeyListener true
                        }
                    }
                    false
                }
            }
            // Iz prve kartice levo nazaj v stranski meni.
            if (prva) nextFocusLeftId = meniMediji.id
            k.priprava?.let { pripravi ->
                setOnFocusChangeListener { v, fokus ->
                    if (fokus) v.postDelayed({ if (v.isFocused && !isFinishing) iskanjeDelavec.execute { try { pripravi() } catch (_: Exception) { } } }, 600)
                }
            }
            val slika = (if (kanal) LogoKanala(this@GlasbaActivity, dp(8), osBarva(R.color.os_kartica), 0xFFE6EAF0.toInt()) else ImageView(this@GlasbaActivity)).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(osBarva(R.color.os_kartica))
                setImageResource(k.ikona)
                // Kartica brez slike: ikona zmerne velikosti na sredini, ne cez vso kartico. Kanal: enako, dokler
                // logotipa ni; logotip se nato pokaze cel ([LogoKanala]) - prej je bil odrezan v pokoncen plakat.
                if (k.slika.isBlank() || kanal) { scaleType = ImageView.ScaleType.FIT_CENTER; val r = minOf(sirina, visina) / 4; setPadding(r, r, r, r) }
            }
            if (k.tvId.isNotBlank()) naloziTvIkono(k.tvId, slika)
            if (plakat) {
                addView(FrameLayout(this@GlasbaActivity).apply {
                    addView(slika, FrameLayout.LayoutParams(-1, -1))
                    fun znacka(text: String, barva: Int, spodaj: Int, oznakaPogleda: String? = null) {
                        // Kakovost filma ([KakovostZnane]): znacka obstaja tudi prazna (skrita), da jo priprava tokov osvezi na mestu.
                        if (text.isBlank() && oznakaPogleda == null) return
                        addView(besedilo(10f, barva, true).apply {
                            nastaviZnackoKakovosti(this, text, barva)
                            setPadding(dp(8), dp(4), dp(8), dp(4))
                            tag = oznakaPogleda
                        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = dp(8); topMargin = dp(spodaj) })
                    }
                    znacka(k.oznaka, if (k.oznaka == getString(R.string.os_media_serija)) 0xFFA878FF.toInt() else osBarva(R.color.os_mint), 9)
                    znacka(k.kakovost, barvaKakovosti(k.kakovost), if (k.oznaka.isBlank()) 9 else 42,
                        k.kakovostId.takeIf { it.isNotBlank() }?.let { OZNAKA_KAKOVOSTI + it })
                }, LinearLayout.LayoutParams(sirina, visina))
            } else addView(slika, LinearLayout.LayoutParams(sirina, visina))
            val dveVrstici = plakat || kanal || (velikostDp in 1..103 && !mala && !slicica)
            val izvajalecPod = if (!dveVrstici && '\n' in k.naslov) k.naslov.substringBefore('\n') else ""
            addView(besedilo(if (mala) 11f else if (kanal) 13f else 14f, osBarva(R.color.os_besedilo), true).apply {
                text = if (izvajalecPod.isNotEmpty()) k.naslov.substringAfter('\n') else k.naslov; maxLines = if (plakat) 2 else 1; setPadding(dp(2), dp(if (mala) 2 else 8), 0, 0)
                // Kanal: ime v dveh vrsticah (v eni je bilo odrezano), vedno dve - da so ploscice v vrsti enako visoke.
                // Enako ozka kartica mreze (trije v vrsto na telefonu): v eni vrstici bi od naslova ostalo nekaj crk.
                if (kanal || (velikostDp in 1..103 && !mala && !plakat && !slicica)) { setLines(2); ellipsize = android.text.TextUtils.TruncateAt.END }
                if (slicica) ellipsize = android.text.TextUtils.TruncateAt.END
            },
                LinearLayout.LayoutParams(sirina, -2))
            if (!mala && !kanal) addView(besedilo(12f, osBarva(R.color.os_umirjeno)).apply {
                text = listOf(listOf(izvajalecPod, k.podnaslov).filter { it.isNotBlank() }.joinToString(" · "),
                    k.ocena.takeIf { it.isNotBlank() }?.let { "★ $it" }.orEmpty()).filter { it.isNotBlank() }.joinToString("   ")
                setPadding(dp(2), dp(2), 0, 0)
            },
                LinearLayout.LayoutParams(sirina, -2))
            // Logotip kanala ostane, kot je bil (majhen); slika vsebine se nalozi, ko je kartica na zaslonu (naloga 56).
            if (kanal) naloziSliko(k.slika, slika) else slikaKartice(k.slika, slika)
        }
    }

    /** Kartice s tem kljucem ni vec (izbrisan vir, zadnja v vrsti): najblizja prejsnja v isti vrsti, da izbira ne pade v meni. */
    private fun sosedKljuca(k: String): View? {
        val i = k.lastIndexOf('#')
        val n = (if (i >= 0) k.substring(i + 1).toIntOrNull() else null) ?: return null
        for (j in n - 1 downTo 0) vsebina.findViewWithTag<View>(k.substring(0, i + 1) + j)?.let { return it }
        return null
    }

    /** Kljuc pogleda s fokusom (kartice in gumbi imajo tag "k:..."), da ga po ponovnem risanju najdemo. */
    private fun kljucFokusa(): String? {
        var v: View? = window.decorView.findFocus()
        while (v != null && v !== vsebina) {
            // "r:" = gumb razdelka v glavi (Glasba | Video | ...): tudi nanj se izbira po risanju vrne.
            (v.tag as? String)?.takeIf { it.startsWith("k:") || it.startsWith("r:") }?.let { return it }
            v = v.parent as? View
        }
        return null
    }

    /**
     * Risanje po korakih: police in kartice se dodajajo v majhnih kosih (vsak kos najvec ~8 ms na glavni
     * niti), ne vse v eni slicici. Na televizorju (Philips mt5895, 2 GB) je celoten razdelek v eni slicici
     * trajal 1,2 s ("Skipped 33 frames"); zdaj se prva vrsta pokaze takoj, ostale sledijo brez zastoja.
     * Novo risanje (menjava razdelka) prekine se nedokoncano prejsnje.
     */
    private var risanje = 0
    /**
     * Diagnostika na zahtevo razvijalca (`adb shell setprop log.tag.SafeerOsMreza DEBUG`): polna risanja in dopolnjevanja
     * mreze na mestu. Z njo izmerimo, da uporabnik osvezevanja v ozadju ne cuti (brez polnega risanja, ko nic ne pritiska).
     */
    private fun dnevnikMreze(kaj: () -> String) {
        if (android.util.Log.isLoggable(StranskaVrstica.DNEVNIK_MREZE, android.util.Log.DEBUG)) android.util.Log.d(StranskaVrstica.DNEVNIK_MREZE, kaj())
    }
    /** Kljuc izbire, ki jo mora vrniti risanje v teku (glej [narisi]); pritisk tipke ga razveljavi - uporabnik izbira sam. */
    private var kljucVRisanju: String? = null
    /**
     * [podstran]: risemo podstran na vrhu sklada ([podstrani]); vsako drugo risanje pomeni, da podstrani ni vec na zaslonu.
     * [odVrha]: pravkar odprta stran - zacne se na vrhu, izbira (daljinec) gre na prvo kartico.
     * [naMestu]: ista stran z dopolnjeno vsebino (zadetki iskanja, ki pridejo pozneje) - ostane, kjer jo ima uporabnik.
     */
    private fun narisi(vrste: List<Vrsta>, opis: String, prazno: String = getString(R.string.os_glasba_prazno), glava: List<View> = emptyList(),
                       podstran: Boolean = false, odVrha: Boolean = false, naMestu: Boolean = false) {
        val moje = ++risanje
        // Stran razdelka, mreza ali katalog cez odprto podstran: podstrani ni vec na zaslonu - stanje sledi zaslonu.
        if (!podstran && podstrani.odprta) { podstrani.pocisti(); podPodstranjo = null; odprtSeznam = "" }
        // Mreza, ki jo to risanje postavlja po narocilu pokaziMrezo; vsako drugo risanje zapis o mrezi na zaslonu pozabi.
        val narocena = narocenaMreza
        narocenaMreza = null
        narisanaMreza = null
        // Naloga 88 (TV 08:50: Live TV ob prvem odprtju 0 kartic in »Loading« po 40 s, ceprav so bili kanali z diska v mrezi):
        // mreza, ki je ta hip prazna (ali je pogled), se ne rise po korakih - zapis »risem« bi ostal za vedno in vsaka dopolnitev
        // ([narisiKanale]) bi se odlagala v nedogled. Prvo prazno risanje je bilo ob vsakem prvem odprtju po zagonu.
        risemMrezo = narocena?.takeIf { vrste.size == 1 && vrste[0].pogled == null && vrste[0].kartice.isNotEmpty() }?.first
        // Skrajsano vidno polje mreze ([brezOdrezanihVrst]) velja samo za mrezo, ki ga je nastavila.
        skrajsajDrsnik(0)
        dnevnikMreze { "polno risanje: razdelek=$razdelek, vrst=${vrste.size}, kartic=${vrste.sumOf { it.kartice.size }}, mreza=${narocena != null}, izbira v vsebini=${vsebina.hasFocus()}, drsnik=${drsnik.scrollY}" }
        // Risanje, ki ga prekine naslednje (mreza se dopolni, ko pridejo katalogi), izbire se ni vrnilo: kljuc gre naprej,
        // sicer izbira na televizorju ostane v meniju.
        // Vrnitev s podstrani (Nazaj): izbira se vrne na kartico, s katere je bila podstran odprta - ne na kartico podstrani,
        // ki ima izbiro zdaj. Velja nekaj sekund (stran razdelka se po vrnitvi lahko narise veckrat) ali do pritiska tipke.
        val zdaj = android.os.SystemClock.uptimeMillis()
        if (vrniKljuc != null && zdaj >= vrniKljucDo) vrniKljuc = null
        val vrnjen = if (odVrha) null else vrniKljuc
        val odmikVrnjenega = vrniOdmik
        val naDotik = window.decorView.isInTouchMode
        // Z daljincem se na kartico vrne tudi izbira; na dotik samo stran na svoje mesto.
        val kljuc = if (odVrha) null else vrnjen?.takeIf { !naDotik } ?: ((if (vsebina.hasFocus()) kljucFokusa() else null) ?: kljucVRisanju)
        kljucVRisanju = kljuc
        // Izbira iz vsebine, ki jo bomo zamenjali, pocaka na nevidnem sidru v vsebini. Prej je sla v meni: stranska vrstica
        // se je za hip razsirila in zaslon je utripnil, ceprav uporabnik ni naredil nicesar (lastnikov posnetek, 5. 10. 2026).
        // Ista stran z dopolnjeno vsebino ostane, kjer jo ima uporabnik (lastnik 25. 10. 2026, posnetek telefona: med drsenjem
        // po zadetkih iskanja je stran ob vsaki dopolnitvi skocila na vrh). Izmerjeno na WP28 S: drsnik 145 -> 0 ob polnem
        // risanju - vsebina se izprazni in ScrollView polozaj obreze, izbira pa se vrne na iskalno polje na vrhu. Zato vsebina
        // do konca risanja obdrzi visino, polozaj ostane brez scrollTo, izbira se na dotik ne vrne ([OsPravila.vrniIzbiroPoRisanju]).
        // Grok 10, M1: polozaj preberemo PRED sidrom izbire, da nanj premik izbire ne more vplivati.
        val ostaneY = if (naMestu && !odVrha) drsnik.scrollY else 0
        if (vsebina.hasFocus()) zasidrajIzbiro()
        vsebina.minimumHeight = OsPravila.visinaMedRisanjem(vsebina.height, ostaneY)
        vsebina.removeAllViews()
        stanje.text = if (glava.isEmpty() && vrste.all { it.kartice.isEmpty() && it.pogled == null }) prazno else opis
        iskalnik?.let { vsebina.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
        glava.forEach { vsebina.addView(it) }
        val koraki = ArrayDeque<() -> Unit>()
        for (v0 in vrste) {
            if (v0.kartice.isEmpty() && v0.pogled == null) continue
            // Blok kartic (nekdanja polica) je mreza z najvec dvema vrsticama; polic, po katerih je treba v desno, ni vec.
            val v = if (v0.mreza || v0.pogled != null) v0 else blokMreze(v0)
            val tesno = v.mala || razdelek == DOMOV
            koraki.addLast {
                if (v.naslov.isNotBlank())
                    vsebina.addView(besedilo(if (tesno) 16f else 18f, osBarva(R.color.os_besedilo), true).apply {
                        text = v.naslov; tag = "polica:${v.naslov}"; contentDescription = NASLOV_VRSTE
                        setPadding(dp(4), dp(if (tesno) 5 else 14), 0, dp(if (tesno) 3 else 8)) })
            }
            if (v.pogled != null) { koraki.addLast { vsebina.addView(v.pogled) }; continue }
            run {
                // Mreza (plakati, kartice, ploscice kanalov): toliko v vrsto, kolikor jih gre ([mereMreze]). Kadar jo je
                // narocil pokaziMrezo, si zapomnimo vrstice in poglede, da jo naslednjic dopolnimo na mestu.
                val (n, velikost) = mereMreze(v)
                val nm = narocena?.takeIf { vrste.size == 1 }?.let { NarisanaMreza(it.first, it.second, n, velikost) }
                koraki.addLast {
                    val sidro = View(this).apply { tag = SIDRO_MREZE }
                    vsebina.addView(sidro, LinearLayout.LayoutParams(-1, 0))
                    nm?.sidro = sidro
                }
                val kljuci = kljuciKartic(v.kartice)
                v.kartice.chunked(n).forEachIndexed { r, del ->
                    koraki.addLast {
                        val vrstica = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; tag = MREZA_VRSTA }
                        del.forEachIndexed { i, k ->
                            val p = karticaMreze(v, k, velikost)
                            dodajVMrezo(v, vrstica, p, r * n + i, n)
                            nm?.pogledi?.put(kljuci[r * n + i], p)
                        }
                        polniloMreze(v, vrstica, del.size, n)
                        vsebina.addView(vrstica, LinearLayout.LayoutParams(-1, -2))
                        nm?.vrstice?.add(vrstica)
                    }
                }
                if (nm != null) koraki.addLast { nm.kljuci = kljuci; narisanaMreza = nm; risemMrezo = null }
            }
        }
        // Prvo risanje pred postavitvijo: sirina vsebine se ni znana, mere mreze so ocena. Ko je znana, narisemo znova - to se
        // zgodi pred prvo sliko zaslona. (Mreze, ki jih naroci pokaziMrezo, za to poskrbijo same.)
        if (vsebina.width == 0 && narocena == null && vrste.any { it.kartice.isNotEmpty() && it.pogled == null }) {
            vsebina.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (vsebina.width <= 0) return
                    vsebina.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    if (moje == risanje && !isFinishing) narisi(vrste, opis, prazno, glava, podstran, odVrha, naMestu)
                }
            })
        }
        // Po dejanju na mestu (izbris vira ali seznama, priljubljena, usklajeni seznami) in po vrnitvi s podstrani ostanemo
        // tam, kjer smo bili. Na dotik izbira ni mesto na strani (fokus ima kvecjemu iskalno polje): polozaj se vrne vseeno.
        val yNaMestu = if (naMestu) ostaneY else if (!odVrha && razdelek == naMestuRazdelek && zdaj < naMestuDo) naMestuY else 0
        // Kartica, s katere je bila podstran odprta: stran se vrne tako, da je kartica tam, kjer je bila - tudi ce se je nad
        // njo medtem kaj spremenilo (plosca predvajanja, nedavno). Brez nje velja shranjeni polozaj drsnika.
        val imaSidro = vrnjen != null && odmikVrnjenega != OsPravila.Podstrani.NI_ODMIKA
        var narisanoDoKonca = false
        // Kljuc vrnitve je porabljen sele, ko je kartica na strani IN je stran zasidrana nanjo. Stran, narisana v dveh korakih
        // (najprej zgornji bloki, nato vsi), je po prvem koraku prekratka za sidro; prej je bil kljuc takrat ze porabljen in
        // drugi korak je stran vrnil na shranjeni polozaj drsnika - mimo za spremembo visine nad kartico (tretji pregled, D1).
        var karticaVrnjena = false
        var zasidrano = !imaSidro
        fun porabiKljuc() { if (karticaVrnjena && zasidrano && vrnjen != null && vrniKljuc == vrnjen) vrniKljuc = null }
        // Na mestu brez vrnitve na kartico: polozaj drzi visina vsebine - scrollTo bi stran med vlecenjem prsta vrnil za korak.
        if (!(naMestu && !imaSidro) && (kljuc == null || vrnjen != null || (naDotik && yNaMestu > 0))) {
            val y = yNaMestu
            if (y <= 0 && !imaSidro) drsnik.scrollTo(0, 0) else drsnik.post(object : Runnable {
                var poskusi = 0
                override fun run() {
                    if (moje != risanje) return
                    val pogled = if (imaSidro) vsebina.findViewWithTag<View>(vrnjen) else null
                    val sidro = pogled?.takeIf { it.isLaidOut }
                    // Kartica se ni narisana ali postavljena: pocakamo nanjo (dokler risanje tece), preden velja polozaj drsnika.
                    if (imaSidro && sidro == null && !(narisanoDoKonca && pogled == null) && poskusi++ <= 30) { drsnik.postDelayed(this, 50); return }
                    val cilj = if (sidro != null) (vrhVVsebini(sidro) - odmikVrnjenega).coerceAtLeast(0) else y
                    val dovoljVisoka = vsebina.height >= cilj + drsnik.height
                    if (dovoljVisoka || poskusi++ > 30) {
                        drsnik.scrollTo(0, cilj)
                        // Naslednja risanja te strani (bloki s spleta pridejo pozneje) ostanejo, kjer je stran zdaj.
                        if (sidro != null && razdelek == naMestuRazdelek) naMestuY = drsnik.scrollY
                        // Zasidrano je samo, ce je bila stran za to dovolj visoka (ne, ce smo po cakanju odnehali).
                        if (sidro != null && dovoljVisoka) { zasidrano = true; porabiKljuc() }
                    } else drsnik.postDelayed(this, 50)
                }
            })
        }
        fun koncano() {
            narisanoDoKonca = true
            // Vsa vsebina je na strani: visina je spet njena (krajsi seznam se po potrebi obreze sele zdaj).
            vsebina.minimumHeight = 0
            brezOdrezanihVrst()
            drsnik.post {
                if (moje != risanje) return@post
                kljucVRisanju = null
                // "Nadaljuj" se po zacetku predvajanja zamenja s tipkami - izbira gre na predvajaj/pavza.
                val nazaj = kljuc?.let { vsebina.findViewWithTag<View>(it) ?: (if (it == "k:nadaljuj") vsebina.findViewWithTag<View>("k:predvajaj") else null) ?: sosedKljuca(it) }
                // Izbira je vrnjena, ko je njena kartica na zaslonu; do takrat velja se za naslednje risanje (bloki s spleta pridejo pozneje).
                if (vrnjen != null && vsebina.findViewWithTag<View>(vrnjen) != null) { karticaVrnjena = true; porabiKljuc() }
                when {
                    // Odprt razdelek: izbira gre na prvo kartico; ce kartic se ni (mreza se nalaga), poskusimo ob naslednjem risanju.
                    fokusVVsebino -> { if (fokusNaPrvo()) fokusVVsebino = false }
                    // Na dotik iskalnega polja ne izberemo znova, kadar se stran vraca na svoje mesto: izbira bi jo pomaknila na vrh.
                    nazaj != null && OsPravila.vrniIzbiroPoRisanju(naDotik, yNaMestu) -> nazaj.requestFocus()
                    // Pravkar odprta podstran: izbira na prvo kartico (prej je ostala pri stevilki kartice »Pokazi vse«).
                    odVrha -> fokusNaPrvo()
                    // Po zaprtem oknu (Dodaj vir, Odstrani) fokus ne sme ostati nikjer.
                    window.decorView.findFocus() == null -> meniMediji.requestFocus()
                }
                odsidrajIzbiro()
            }
        }
        fun korak() {
            if (moje != risanje || isFinishing) return
            val zacetek = android.os.SystemClock.uptimeMillis()
            while (koraki.isNotEmpty() && android.os.SystemClock.uptimeMillis() - zacetek < PRORACUN_MS) koraki.removeFirst()()
            if (koraki.isNotEmpty()) glavna.post { korak() } else koncano()
        }
        korak()
    }

    // ------------------------------------------------------------------ mreza na zaslonu: dopolnjevanje na mestu

    /**
     * Mreza, ki je zdaj na zaslonu. Ob spremembi (odgovor preverjanja v ozadju, naslednja stran) jo [dopolniMrezo] uskladi z
     * novim seznamom kartic: novi pogledi se dodajo, odvecni odstranijo, ostali ostanejo isti - izbira, polozaj drsnika in
     * stranska vrstica se ne premaknejo. Prej je vsaka sprememba mrezo izbrisala in narisala znova: izbira je za hip
     * skocila v meni (stranska vrstica se je razsirila), seznam pa na vrh (lastnik, 5. 10. 2026: »uporabnik ne sme cutiti
     * osvezevanja v ozadju«).
     */
    private class NarisanaMreza(val lastnik: Any, val oblika: String, val naVrsto: Int, val velikost: Int) {
        var sidro: View? = null
        val vrstice = ArrayList<LinearLayout>()
        var kljuci: List<String> = emptyList()
        var pogledi = HashMap<String, View>()
    }
    private var narisanaMreza: NarisanaMreza? = null
    /** Lastnik mreze, ki se pravkar rise po korakih ([narisi]): do konca je ni mogoce dopolniti na mestu. */
    private var risemMrezo: Any? = null
    /** Narocilo naslednjemu [narisi]: lastnik in oblika mreze, ki naj si jo zapomni. */
    private var narocenaMreza: Pair<Any, String>? = null

    /** Izbira pocaka na vsebini sami (nevidno), dokler se pogledi pod njo menjajo. */
    private fun zasidrajIzbiro() {
        vsebina.defaultFocusHighlightEnabled = false
        vsebina.isFocusable = true
        vsebina.descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
        if (!vsebina.requestFocus()) meniMediji.requestFocus()
    }

    /** Konec menjave: ce izbira se caka na sidru (kartice, kamor bi sla, ni), gre na prvo kartico ali v meni. */
    private fun odsidrajIzbiro() {
        if (!vsebina.isFocusable) return
        if (vsebina.isFocused && !fokusNaPrvo() && vsebina.isFocused) meniMediji.requestFocus()
        vsebina.isFocusable = false
    }

    /** Koliko kartic gre v vrsto mreze in kako siroke so (dp; 0 = enake utezi cez vso sirino). */
    private fun mereMreze(v: Vrsta): Pair<Int, Int> {
        if (v.cisto) {
            // Plakati cez vso sirino: na telefonu pokonci trije v vrsto, na sirsih zaslonih toliko, kolikor jih gre.
            // Televizor: manjsi plakati (osem v vrsto, dve vrsti na zaslonu), kot v drugih predvajalnikih za TV.
            val sirina = (vsebina.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels * 3 / 4)) - vsebina.paddingLeft - vsebina.paddingRight
            return ((sirina + dp(10)) / (dp(if (jeTv()) 80 else 128) + dp(10))).coerceAtLeast(3) to 0
        }
        // Mreza (Moji viri, katalog): toliko kartic v vrsto, kolikor jih gre celih, ostale v naslednjo vrsto.
        // Na ozkem telefonu vsaj dve kartici v vrsto, zato ozje (plakati najmanj 72 dp, druge kartice 88 dp):
        // ena kartica na vrsto je bil seznam z veliko praznega prostora (lastnik, Moji viri na telefonu pokonci).
        val sirinaVsebine = vsebina.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels * 3 / 4)
        // Kanali: lezece ploscice; na televizorju nekoliko manjse, da jih gre pet v vrsto in tri vrste na zaslon.
        var velikost = if (v.kanali) (if (jeTv()) 132 else 150) else if (v.slicice) 150 else if (v.mala) 96 else if (v.video) 112 else if (v.skladbe && jeTv()) 84 else MREZA_DP
        var n = (sirinaVsebine / dp(velikost + 12 + 14)).coerceAtLeast(1)
        // Plakati in male kartice (Nedavno): na telefonu pokonci trije v vrsto (kot uporabniki poznajo iz drugih
        // predvajalnikov), sicer vsaj dva.
        val lezece = v.kanali || v.slicice
        val drobne = v.mala || (v.video && !lezece)
        // Skladbe in seznami z naslovnico: na telefonu trije v vrsto, kot plakati pri Videu (lastnik: »razpored kot pri video«).
        val naslovnice = v.skladbe && !lezece && !jeTv()
        val najmanjDp = if (v.mala) 64 else if (drobne || naslovnice) 72 else 88
        val najmanj = if ((drobne || naslovnice) && sirinaVsebine / dp(najmanjDp + 12 + 14) >= 3) 3 else 2
        // Male kartice imajo ozji rob (3 dp na stran namesto 6).
        val rob = if (v.mala) 20 else 26
        if (n < najmanj && sirinaVsebine / dp(najmanjDp + 12 + 14) >= najmanj) {
            n = najmanj; velikost = (sirinaVsebine / najmanj / resources.displayMetrics.density).toInt() - rob
        }
        // Kartice zapolnijo vrstico: prej je na telefonu na desni ostal prazen pas. Televizor ohrani svoje mere.
        if (!jeTv()) velikost = OsPravila.sirinaKarticeMreze((sirinaVsebine / resources.displayMetrics.density).toInt(), n, velikost, rob)
        return n to velikost
    }

    /** Stalni kljuci kartic po vrsti (id vsebine, sicer naslov); enaka kljuca dobita zaporedno stevilko. */
    private fun kljuciKartic(kartice: List<Kartica>): List<String> {
        val videni = HashMap<String, Int>()
        return kartice.map { k ->
            val osnova = k.id.ifBlank { "::" + k.naslov }
            val st = (videni[osnova] ?: 0) + 1
            videni[osnova] = st
            if (st == 1) osnova else "$osnova#$st"
        }
    }

    private fun karticaMreze(v: Vrsta, k: Kartica, velikost: Int): View =
        if (v.cisto) cistPlakat(k, false, "") else kartica(k, v.video, false, "", mala = v.mala, velikostDp = velikost, kanal = v.kanali, slicica = v.slicice)

    /**
     * Blok kartic na strani z vec bloki (Domov, Moja glasba, Zate, zadetki iskanja, Brskaj): mreza z najvec
     * [OsPravila.VRSTIC_BLOKA] vrsticami namesto police, po kateri je treba drseti v desno (lastnik, 5. 10. 2026: »raje imam
     * da je razpored kot pri video«; 7. 10. 2026: velja za vse predvajalnike in medijske centre). Ce je kartic vec, zadnje
     * mesto odpre vse: razdelek z izbirami ([Vrsta.vse]) ali ta blok kot celo mrezo ([odpriBlok]). Kartice dejanj
     * (»Pocisti«) ostanejo na koncu.
     */
    private fun blokMreze(v: Vrsta): Vrsta {
        val oblika = v.copy(mreza = true)
        val kartice = v.kartice.filterNot { it.dejanje }
        val dejanja = v.kartice.filter { it.dejanje }
        val imaVse = dejanja.any { it.vse }
        // Male kartice (Nedavno) so nizke: tri vrstice, da gre pet nedavnih in »Pocisti« na stran tudi po dve v vrsto.
        val vrstic = if (v.mala) OsPravila.VRSTIC_BLOKA + 1 else OsPravila.VRSTIC_BLOKA
        val ostane = OsPravila.vsebineVBloku(kartice.size, dejanja.size, mereMreze(oblika).first, vrstic, imaVse)
        if (ostane >= kartice.size) return oblika
        val vse = if (imaVse) emptyList() else listOf(Kartica(getString(R.string.os_media_pokazi_vse), v.naslov, "",
            { (v.vse ?: { odpriBlok(v) })() }, ikona = R.drawable.os_ikona_mreza, dejanje = true, vse = true))
        return oblika.copy(kartice = kartice.take(ostane) + vse + dejanja)
    }

    /**
     * Podstran: »Pokazi vse« bloka, seznam predvajanja, epizode podkasta, skladbe izvajalca. Hrani, IZ CESA se narise (ne
     * narisanih kartic), da jo lahko narisemo znova: po dejanju na njej (priljubljena, odstranjena skladba), po zasuku in
     * ob vrnitvi z globlje podstrani.
     */
    private sealed class Podstran {
        /**
         * »Pokazi vse« bloka [naslov]. [znan]: stran razdelka zna blok prebrati znova ([svezBlok]) - potem velja svez (in
         * ko ga ni vec, podstrani ni vec); sicer ostane [vrsta], kot je bila ob odprtju (blok s spleta).
         */
        class Blok(val naslov: String, val vrsta: Vrsta, val opis: String, val znan: Boolean) : Podstran()
        /** Posnetki: shranjen seznam predvajanja ([shranjen] - bere se iz shrambe po imenu) ali seznam, nalozen z omrezja. */
        class Seznam(val ime: String, val opis: String, val shranjen: Boolean, val skladbe: List<Jamendo.Skladba>) : Podstran()
    }
    /** Odprte podstrani: Nazaj vrne na stran, s katere je bila podstran odprta, na isto mesto ([OsPravila.Podstrani]). */
    private val podstrani = OsPravila.Podstrani<Podstran>()
    /** Mreza ali katalog, ki je bil na zaslonu, ko se je odprla prva podstran: dokler je podstran odprta, se ne rise cez njo. */
    private var podPodstranjo: Any? = null
    /** Kartica, na katero mora risanje vrniti izbiro (daljinec) po vrnitvi s podstrani; velja do [vrniKljucDo] ali do pritiska tipke. */
    private var vrniKljuc: String? = null
    private var vrniOdmik = OsPravila.Podstrani.NI_ODMIKA
    private var vrniKljucDo = 0L
    /** Razdelek, odprt s »Pokazi vse« bloka na plosci, in mesto na plosci: Nazaj iz njega vrne na ta blok. */
    private var izBlokaDomov: Pair<Int, Mesto>? = null

    /**
     * Mesto na strani, kamor se vrne Nazaj: polozaj drsnika ter kartica (oznaka) in njen odmik od vrha vidnega dela. Po
     * kartici stran pristane tocno, tudi ce se je nad njo medtem kaj spremenilo (izmerjeno 7. 10. 2026: po zacetku
     * predvajanja je plosca na vrhu Domov visja in isti polozaj drsnika kaze 210 tock visje lezeco vsebino).
     */
    private data class Mesto(val y: Int, val kljuc: String?, val odmik: Int)

    /** Vrh pogleda v vsebini strani (v tockah vsebine, ne zaslona). */
    private fun vrhVVsebini(v: View): Int {
        var y = 0
        var p: View? = v
        while (p != null && p !== vsebina) { y += p.top; p = p.parent as? View }
        return y
    }

    private fun jeVVsebini(v: View): Boolean {
        var p: android.view.ViewParent? = v.parent
        while (p != null) { if (p === vsebina) return true; p = p.parent }
        return false
    }

    /** Mesto, s katerega uporabnik odpira podstran: kartica, ki jo je pravkar izbral (dotik ali OK), sicer kartica z izbiro. */
    private fun mestoZdaj(): Mesto {
        val karta = zadnjaKartica?.get()?.takeIf { it.isAttachedToWindow && (it.tag as? String)?.startsWith("k:") == true && jeVVsebini(it) }
        val kljuc = (karta?.tag as? String) ?: kljucFokusa()
        val pogled = karta ?: kljuc?.let { vsebina.findViewWithTag<View>(it) }
        return Mesto(drsnik.scrollY, kljuc, if (pogled != null) vrhVVsebini(pogled) - drsnik.scrollY else OsPravila.Podstrani.NI_ODMIKA)
    }

    private fun opisStrani() = if (razdelek == ISKANJE && zadetki != null) opisZadetkov else opis(razdelek)

    /** Blok strani razdelka, prebran znova (zadetki iskanja, Brskaj, krajevni bloki na vrhu razdelka); null = stran ga ne pozna. */
    private fun svezBlok(naslov: String): Vrsta? = when (razdelek) {
        ISKANJE -> (zadetki ?: predIskanjem()).firstOrNull { it.naslov == naslov }
        VIRI -> viriVrste().firstOrNull { it.naslov == naslov }
        else -> zgoraj(razdelek).firstOrNull { it.naslov == naslov }
    }

    /** Kartice podstrani iz svezih podatkov; null = podstrani ni vec (izbrisan seznam, izpraznjen blok). */
    private fun vrstaPodstrani(p: Podstran): Vrsta? = when (p) {
        is Podstran.Blok -> (if (p.znan) svezBlok(p.naslov) else p.vrsta)?.copy(mreza = true)?.takeIf { v -> v.kartice.any { !it.dejanje } }
        is Podstran.Seznam -> {
            val sz = if (p.shranjen) MedijskiViri.seznami(this).firstOrNull { it.ime == p.ime } else null
            val s = if (p.shranjen) sz?.skladbe.orEmpty() else p.skladbe
            val video = sz != null && s.all { it.video }
            Vrsta(p.ime, if (video) videi(s, p.ime, sz) else skladbe(s, p.ime, sz), video = video, mreza = true, skladbe = !video)
                .takeIf { it.kartice.isNotEmpty() }
        }
    }

    /** Odpre podstran nad tem, kar je na zaslonu; [od] je mesto na tej strani, kamor se vrne Nazaj. */
    private fun odpriPodstran(p: Podstran, od: Mesto = mestoZdaj()) {
        if (!podstrani.odprta) podPodstranjo = odprtKatalog ?: mrezaVsebine
        podstrani.odpri(p, od.y, od.kljuc, od.odmik)
        naMestuDo = 0L          // podstran se zacne na vrhu, ne na mestu zadnjega dejanja
        vrniKljuc = null
        narisiPodstran(odVrha = true)
        // Na plosci male vrstice predvajanja ni (tam je velika plosca); na podstrani plosce je.
        osveziZdaj()
    }

    /**
     * Narise podstran na vrhu sklada iz svezih podatkov. Podstran, ki je ni vec (izbrisan seznam, izpraznjen blok), se
     * zapre; ko ne ostane nobena, pride na zaslon stran pod njimi. [odVrha]: pravkar odprta podstran se zacne na vrhu.
     */
    private fun narisiPodstran(odVrha: Boolean = false) {
        var vrh = odVrha
        while (true) {
            val p = podstrani.vrh ?: break
            val v = vrstaPodstrani(p)
            if (v != null) {
                narisi(listOf(v), when (p) { is Podstran.Blok -> p.opis; is Podstran.Seznam -> p.opis }, podstran = true, odVrha = vrh)
                odprtSeznam = (p as? Podstran.Seznam)?.takeIf { it.shranjen }?.ime.orEmpty()
                return
            }
            val nazaj = podstrani.zapri() ?: break
            mestoVrnitve(nazaj.y, nazaj.kljuc, nazaj.odmik)
            vrh = false
        }
        naOsnovnoStran()
    }

    /** Naslednja risanja strani (nekaj sekund, dokler uporabnik cesa ne pritisne) vrnejo stran na to mesto, z daljincem tudi izbiro. */
    private fun mestoVrnitve(y: Int, kljuc: String?, odmik: Int, r: Int = razdelek) {
        val rok = android.os.SystemClock.uptimeMillis() + 4_000
        naMestuRazdelek = r; naMestuY = y; naMestuDo = rok
        vrniKljuc = kljuc; vrniOdmik = odmik; vrniKljucDo = rok
    }

    /** Zadetki iskanja (ali stran pred iskanjem) znova, brez novega iskanja - iskanje, ki se tece, tece naprej. */
    private fun narisiIskanje() { zadetki?.let { narisi(it, opisZadetkov) } ?: narisi(predIskanjem(), getString(R.string.os_glasba_isci_navodilo)) }

    /** Zadnja podstran je zaprta: na zaslon pride stran, s katere je bila odprta. Mreza in katalog ostaneta ista, z vsem, kar je ze nalozeno. */
    private fun naOsnovnoStran() {
        val pod = podPodstranjo
        podPodstranjo = null
        odprtSeznam = ""
        val o = odprtKatalog
        val mv = mrezaVsebine
        when {
            o != null && pod === o -> narisiKatalog(o.naslov)
            mv != null && pod === mv -> { mv.podpis = ""; narisiMrezo(mv) }
            razdelek == ISKANJE -> narisiIskanje()
            else -> izberi(razdelek)
        }
    }

    /** Nazaj s podstrani: stran, s katere je bila odprta (druga podstran ali stran razdelka), na istem mestu. */
    private fun zapriPodstran() {
        val nazaj = podstrani.zapri() ?: return
        mestoVrnitve(nazaj.y, nazaj.kljuc, nazaj.odmik)
        if (podstrani.odprta) narisiPodstran() else naOsnovnoStran()
    }

    /** Stran na zaslonu narisemo znova na istem mestu (nekaj na njej se je spremenilo); odprta podstran ostane odprta. */
    private fun osveziNaMestu() {
        naMestuRazdelek = razdelek; naMestuY = drsnik.scrollY; naMestuDo = android.os.SystemClock.uptimeMillis() + 4_000
        when {
            podstrani.odprta -> narisiPodstran()
            razdelek == ISKANJE -> narisiIskanje()
            else -> izberi(razdelek)
        }
    }

    /** Razdelek [i] narisemo znova; ce je v njem odprta podstran, ostane odprta (narisana znova na mestu). */
    private fun osvezi(i: Int) { if (podstrani.odprta && i == razdelek) osveziNaMestu() else izberi(i) }

    /**
     * Stran razdelka [i], ki je ze na zaslonu, narisemo znova tako, da ostane, kjer je: kartica, ki jo je uporabnik nazadnje
     * izbral, je po risanju na istem mestu zaslona, tudi ce se nad njo kaj spremeni (plosca predvajanja, nedavno). Samo na
     * dotik - z daljincem stran na mesto vrne izbira. Prej je stran po vrnitvi s predvajalnika skocila na vrh (podstran pa
     * ne - tretji pregled, D7).
     */
    private fun izberiPriKartici(i: Int, mesto: Mesto? = null) {
        if (i == razdelek && window.decorView.isInTouchMode) (mesto ?: mestoZdaj()).let { mestoVrnitve(it.y, it.kljuc, it.odmik) }
        izberi(i)
    }

    /**
     * Mesto kartice, ki jo je uporabnik pravkar izbral (razdelek, kartica in njen odmik), za vrnitev s predvajalnika. Ob
     * zacetku predvajanja se Domov narise znova (plosca na vrhu), se preden ga predvajalnik prekrije - ob vrnitvi kartice, s
     * katere je uporabnik odsel, ni vec na zaslonu (izmerjeno 8. 10. 2026). Zato mesto vzamemo ob dotiku.
     */
    private var mestoKlika: Pair<Int, Mesto>? = null
    private var mestoKlikaOb = 0L

    private fun zapomniMestoKlika() {
        mestoKlika = if (window.decorView.isInTouchMode && !podstrani.odprta) razdelek to mestoZdaj() else null
        mestoKlikaOb = android.os.SystemClock.uptimeMillis()
    }

    /** »Pokazi vse« v bloku: ves blok kot mreza na svoji strani. */
    private fun odpriBlok(v: Vrsta) = odpriPodstran(Podstran.Blok(v.naslov, v, opisStrani(), v.naslov.isNotBlank() && svezBlok(v.naslov) != null))

    /** Domov: blok s spleta je izsek razdelka - »Pokazi vse« odpre razdelek (mrezo z izbirami), ne samo teh nekaj kartic. */
    private fun razdelekBloka(naslov: String): (() -> Unit)? {
        val cilj = when (naslov) {
            getString(R.string.os_mediji_vrsta_glasba) -> GLASBA
            getString(R.string.os_mediji_vrsta_radio) -> RADIO
            getString(R.string.os_mediji_vrsta_video) -> VIDEO
            else -> return null
        }
        return {
            // Nazaj iz tako odprtega razdelka vrne na ta blok plosce (polozaj in kartica »Pokazi vse«), ne na njen vrh.
            val od = cilj to mestoZdaj()
            if (cilj == GLASBA) shraniGlasbaSkupino("")
            fokusVVsebino = true
            izberi(cilj)
            izBlokaDomov = od
        }
    }

    /** Kartica seznama predvajanja (Brskaj, Domov, Moja glasba, Zate): naslovnica, ime in stevilo posnetkov; dotik odpre seznam. */
    private fun karticaSeznama(sz: MedijskiViri.Seznam) =
        Kartica(sz.ime, resources.getQuantityString(R.plurals.os_stevilo_posnetkov, sz.skladbe.size, sz.skladbe.size), sz.skladbe.firstOrNull { it.slika.startsWith("http") }?.slika.orEmpty(),
            { odpriSeznam(sz.ime, "", sz) { sz.skladbe } }, { meniSeznama(sz) },
            ikona = if (sz.skladbe.all { it.video }) R.drawable.os_ikona_video else R.drawable.os_ikona_glasba)

    /** Oznaka polozaja (po njej risanje vrne izbiro) in pot iz prve kartice vrste levo v stranski meni. */
    private fun oznaciVMrezi(v: Vrsta, p: View, i: Int, n: Int) {
        p.tag = "k:${v.naslov}#$i"
        p.nextFocusLeftId = if (i % n == 0) meniMediji.id else View.NO_ID
    }

    private fun dodajVMrezo(v: Vrsta, vrstica: LinearLayout, p: View, i: Int, n: Int) {
        oznaciVMrezi(v, p, i, n)
        // Plakati: enake utezi - zapolnijo vrstico in sledijo sirini vsebine. Kartice: svoja sirina.
        vrstica.addView(p, if (v.cisto) LinearLayout.LayoutParams(0, -2, 1f).apply { if (i % n < n - 1) marginEnd = dp(10); bottomMargin = dp(10) }
            else LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14); bottomMargin = dp(14) })
    }

    /** Nepolna zadnja vrstica plakatov dobi prazna mesta, da plakati niso raztegnjeni. */
    private fun polniloMreze(v: Vrsta, vrstica: LinearLayout, zasedenih: Int, n: Int) {
        if (!v.cisto) return
        for (i in zasedenih until n) vrstica.addView(View(this).apply { tag = POLNILO_MREZE }, LinearLayout.LayoutParams(0, 1, 1f).apply { if (i < n - 1) marginEnd = dp(10) })
    }

    /**
     * Mreza razdelka: prvic (ali ob drugacni glavi, obliki ali sirini) se narise v celoti, sicer se dopolni na mestu.
     * [lastnik]: odprta mreza (isti predmet = ista mreza); [podpisGlave]: vse, od cesar je odvisna glava nad mrezo.
     * Vrne true, ce je bila mreza dopolnjena na mestu (klicatelju ni treba vracati polozaja in izbire).
     */
    private fun pokaziMrezo(lastnik: Any, vrsta: Vrsta, opis: String, podpisGlave: String, glava: () -> List<View>): Boolean {
        // Mreza pod odprto podstranjo se ne rise cez njo; narisana bo ob vrnitvi (Nazaj), z vsem, kar je medtem prislo.
        if (podstrani.odprta && podPodstranjo === lastnik) return false
        val oblika = "${vrsta.naslov}|${vrsta.video}|${vrsta.cisto}|${vrsta.kanali}|${vrsta.slicice}|${vrsta.skladbe}|$podpisGlave"
        val nm = narisanaMreza
        if (nm != null && nm.lastnik === lastnik && nm.oblika == oblika && nm.naVrsto == mereMreze(vrsta).first &&
            vrsta.kartice.isNotEmpty() && nm.sidro?.parent === vsebina && nm.vrstice.all { it.parent === vsebina }) {
            dopolniMrezo(nm, vrsta)
            stanje.text = opis
            return true
        }
        narocenaMreza = lastnik to oblika
        narisi(listOf(vrsta), opis, glava = glava())
        return false
    }

    /** Mrezo na zaslonu uskladi z novim seznamom kartic, ne da bi jo izbrisal ([NarisanaMreza]). */
    private fun dopolniMrezo(nm: NarisanaMreza, v: Vrsta) {
        val n = nm.naVrsto
        val kljuci = kljuciKartic(v.kartice)
        if (kljuci == nm.kljuci) return
        // Katera kartica ima izbiro (daljinec): ostane na njej, tudi ce se kartica premakne v drugo vrsto.
        val fokusiran = vsebina.findFocus()
        var fokusKljuc: String? = null
        if (fokusiran != null && fokusiran !== vsebina) {
            for ((k, p) in nm.pogledi) {
                var x: View? = fokusiran
                while (x != null && x !== vsebina && x !== p) x = x.parent as? View
                if (x === p) { fokusKljuc = k; break }
            }
        }
        val fokusIndeks = fokusKljuc?.let { nm.kljuci.indexOf(it) } ?: -1
        val pogledi = ArrayList<View>(kljuci.size)
        val novi = HashMap<String, View>(kljuci.size * 2)
        v.kartice.forEachIndexed { i, k ->
            val p = nm.pogledi[kljuci[i]] ?: karticaMreze(v, k, nm.velikost)
            pogledi += p; novi[kljuci[i]] = p
        }
        var zasidrano = false
        // Pogled z izbiro bomo premaknili ali odstranili: izbira vmes pocaka na sidru (sicer bi skocila v meni).
        fun zasidraj() { if (!zasidrano && fokusKljuc != null) { zasidrano = true; zasidrajIzbiro() } }
        val vrstic = (pogledi.size + n - 1) / n
        while (nm.vrstice.size > vrstic) {
            val vr = nm.vrstice.removeAt(nm.vrstice.size - 1)
            if (vr.hasFocus()) zasidraj()
            vsebina.removeView(vr)
        }
        for (r in 0 until vrstic) {
            val zeleni = pogledi.subList(r * n, minOf((r + 1) * n, pogledi.size))
            val vr = nm.vrstice.getOrNull(r) ?: LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; tag = MREZA_VRSTA }.also { nova ->
                val za = nm.vrstice.lastOrNull() ?: nm.sidro
                vsebina.addView(nova, vsebina.indexOfChild(za) + 1, LinearLayout.LayoutParams(-1, -2))
                nm.vrstice += nova
            }
            val trenutni = (0 until vr.childCount).map { vr.getChildAt(it) }.filter { it.tag != POLNILO_MREZE }
            if (trenutni.size == zeleni.size && trenutni.indices.all { trenutni[it] === zeleni[it] }) {
                // Ista vrstica: pogledi ostanejo, kjer so; osvezimo le oznake polozaja.
                zeleni.forEachIndexed { i, p -> oznaciVMrezi(v, p, r * n + i, n) }
                continue
            }
            if (vr.hasFocus() || zeleni.any { it.hasFocus() }) zasidraj()
            vr.removeAllViews()
            zeleni.forEachIndexed { i, p ->
                (p.parent as? android.view.ViewGroup)?.removeView(p)
                dodajVMrezo(v, vr, p, r * n + i, n)
            }
            polniloMreze(v, vr, zeleni.size, n)
        }
        dnevnikMreze { "na mestu: ${nm.kljuci.size} -> ${kljuci.size} kartic (novih ${kljuci.count { it !in nm.pogledi }}, odstranjenih ${nm.kljuci.count { it !in novi }}), izbira na kartici=${fokusKljuc != null}, izbira prestavljena=$zasidrano, drsnik=${drsnik.scrollY}" }
        nm.kljuci = kljuci
        nm.pogledi = novi
        if (zasidrano) {
            val cilj = novi[fokusKljuc] ?: pogledi.getOrNull(fokusIndeks.coerceIn(0, (pogledi.size - 1).coerceAtLeast(0)))
            if (cilj == null || !cilj.requestFocus()) meniMediji.requestFocus()
            vsebina.isFocusable = false
        } else if (fokusVVsebino && fokusNaPrvo()) fokusVVsebino = false
        // Na prvem zaslonu ni odrezane vrste (kot po polnem risanju).
        if (drsnik.scrollY == 0) brezOdrezanihVrst()
    }

    /** Za koliko px je drsnik zdaj krajsi ([skrajsajDrsnik]). */
    private var odmikDrsnika = 0

    /**
     * Vidno polje vsebine skrajsa za [za] px od spodaj - z zunanjim robom drsnika, da je njegova visina res manjsa:
     * ScrollView pri pomiku na izbrano kartico racuna s svojo visino, notranjega odmika (padding) ne uposteva (izmerjeno
     * na televizorju 5. 10. 2026: izbrana ploscica je bila odrezana).
     */
    private fun skrajsajDrsnik(za: Int) {
        if (za == odmikDrsnika) return
        val p = drsnik.layoutParams as? LinearLayout.LayoutParams ?: return
        p.bottomMargin = p.bottomMargin - odmikDrsnika + za
        odmikDrsnika = za
        drsnik.layoutParams = p
    }

    /**
     * Na prvem zaslonu ni odrezane vrste: prva vrsta, ki ne gre cela nad spodnji rob, se skupaj
     * s svojim naslovom odmakne pod rob (pokaze se ob drsenju). Merimo po postavitvi, ne ugibamo.
     */
    /** Meritev po naslednji postavitvi ze caka ([brezOdrezanihVrst]). */
    private var merimVrste = false

    private fun brezOdrezanihVrst() {
        // Samo televizor (daljinec): na dotik se drsi s prstom in odmik bi pustil prazno luknjo sredi zaslona.
        if (!Naprava.jeTelevizor(this@GlasbaActivity)) return
        // Ena meritev naenkrat: dve v isti postavitvi vidita isto, se neskrajsano visino in vidno polje skrajsata dvakrat
        // (izmerjeno 6. 10. 2026 na televizorju: druga vrsta mreze kanalov odrezana, drsnik 704 px namesto 792 px - dopolnitev
        // na mestu je prisla, preden se je sprozila meritev po polnem risanju).
        if (merimVrste) return
        merimVrste = true
        vsebina.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                vsebina.viewTreeObserver.removeOnGlobalLayoutListener(this)
                merimVrste = false
                val vidno = drsnik.height
                if (vidno <= 0) return
                var vidnaVrsta = false
                var vidneVrsteMreze = 0
                for (i in 0 until vsebina.childCount) {
                    val v = vsebina.getChildAt(i)
                    val jeVrsta = v is HorizontalScrollView || v.tag == MREZA_VRSTA
                    if (v.bottom <= vidno) {
                        if (v.tag == POLICA_KARTIC || v.tag == MREZA_VRSTA) vidnaVrsta = true
                        if (v.tag == MREZA_VRSTA) vidneVrsteMreze++
                        continue
                    }
                    if (v.tag == MREZA_VRSTA) {
                        // Mreza: vrstic ne razmikamo - praznina med vrsticama bi ostala tudi med drsenjem (izmerjeno na
                        // televizorju 5. 10. 2026 v mrezi kanalov). Kadar od zadnje cele vrstice do roba ostane manj kot pol
                        // vrstice, vidno polje skrajsamo do te vrstice: na zaslonu so vedno cele vrstice. Sicer naslednja
                        // ostane delno vidna (pove, da je spodaj se kaj) - tako tudi, kadar gre na zaslon ena sama vrsta.
                        val ostanek = vidno - v.top
                        if (vidneVrsteMreze >= 2 && ostanek > 0 && ostanek * 2 < v.height && odmikDrsnika + ostanek < v.height)
                            skrajsajDrsnik(odmikDrsnika + ostanek)
                        return
                    }
                    // Samo vrste kartic (in njihove naslove); plosce na vrhu ostanejo, kot so.
                    if (!jeVrsta && v.contentDescription != NASLOV_VRSTE) return
                    // Prve vrste nikoli ne odmaknemo: na nizkem zaslonu (telefon lezece) bi sicer
                    // ostala vidna samo glava in zaslon bi bil videti prazen.
                    if (!vidnaVrsta) return
                    val zacetek = if (i > 0 && vsebina.getChildAt(i - 1).contentDescription == NASLOV_VRSTE) i - 1 else i
                    val vrh = vsebina.getChildAt(zacetek).top
                    if (zacetek > 0 && vrh < vidno) vsebina.addView(View(this@GlasbaActivity), zacetek, LinearLayout.LayoutParams(-1, vidno - vrh))
                    return
                }
            }
        })
        // Meritev naj ne caka na naslednjo spremembo zaslona: brez postavitve se ne sprozi in odrezana vrsta ostane.
        vsebina.requestLayout()
    }

    // ------------------------------------------------------------------ slike

    /**
     * Slike kartic na zaslonu (pogled -> naslov slike), naloga 56: v pomnilniku so samo plakati v vidnem delu drsnika in
     * vrsta nad in pod njim. Plakat, ki gre z zaslona, sprosti bitno sliko; ko se vrne, jo spet dobi iz SLIKE ali z
     * diska. Prej je vsaka kartica mreze drzala svojo sliko, dokler je bila mreza odprta - ne glede na stevilo strani.
     */
    private val slikeKartic = java.util.WeakHashMap<ImageView, String>()
    private var uskladiNarocen = false
    /** Polozaj drsnika ob zadnji uskladitvi in koliko zaporednih uskladitev je naletelo na se nepostavljene kartice. */
    private var uskladenPri = Int.MIN_VALUE
    private var nepostavljeniPoskusi = 0

    /** Kartica s sliko: slika se nalozi, ko vemo, ali je kartica na zaslonu (po postavitvi, [uskladiSlike]). */
    private fun slikaKartice(naslov: String, v: ImageView) {
        if (naslov.isBlank()) return
        slikeKartic[v] = naslov
        nepostavljeniPoskusi = 0
        narociUskladiSlike()
    }

    private fun narociUskladiSlike(cezMs: Long = 32) {
        if (uskladiNarocen || isFinishing) return
        uskladiNarocen = true
        drsnik.postDelayed({ uskladiNarocen = false; uskladiSlike() }, cezMs)
    }

    /** Vrh pogleda v vsebini drsnika; null = ni v njej (npr. na podstrani ali v oknu). */
    private fun vrhSlikeVVsebini(v: View): Int? {
        var y = 0
        var x: View = v
        while (x !== vsebina) {
            y += x.top
            x = x.parent as? View ?: return null
        }
        return y
    }

    private fun uskladiSlike() {
        if (isFinishing) return
        val zgoraj = drsnik.scrollY
        val spodaj = zgoraj + drsnik.height
        uskladenPri = zgoraj
        var nepostavljenih = 0
        for ((v, naslov) in slikeKartic.entries.toList()) {
            if (!v.isAttachedToWindow || !v.isLaidOut || v.height == 0) { nepostavljenih++; continue }
            val ima = v.drawable is android.graphics.drawable.BitmapDrawable
            // Kartica zunaj drsnika (podstran, okno): slika kot doslej, takoj.
            val y = vrhSlikeVVsebini(v) ?: run { if (!ima && v.tag != naslov) naloziSliko(naslov, v); null } ?: continue
            val viden = OsPravila.vVidnem(y, v.height, zgoraj, spodaj, OsPravila.rezervaNaslovnic(v.height, drsnik.height, dp(60)))
            if (viden && !ima && v.tag != naslov) naloziSliko(naslov, v)
            else if (!viden && ima) { v.setImageDrawable(null); v.tag = null }
        }
        // Kartice, ki se niso postavljene (pravkar dodane): pogledamo znova, a ne v nedogled (skrite kartice).
        if (nepostavljenih > 0 && nepostavljeniPoskusi++ < 20) narociUskladiSlike(100)
    }

    private fun naloziSliko(naslov: String, v: ImageView) {
        // Krajevni medijski strezniki (NAS, Jellyfin, uporabnikova spletna aplikacija) pogosto
        // ponujajo naslovnice prek navadnega HTTP-ja. Safeer jih ze varno prenese v SpletniVir;
        // tukaj jih ne smemo zavreci samo zato, ker niso HTTPS. Druge sheme ostanejo prepovedane.
        val shema = runCatching { android.net.Uri.parse(naslov).scheme?.lowercase(java.util.Locale.ROOT) }.getOrNull()
        if (shema == "content") { naloziKrajevnoSlicico(naslov, v); return }
        if (shema != "https" && shema != "http") return
        SLIKE.get(naslov)?.let { v.setImageBitmap(it); return }
        v.tag = naslov
        cakajoceSlike.computeIfAbsent(naslov) {
            Collections.synchronizedList(ArrayList<WeakReference<ImageView>>())
        }.add(WeakReference(v))
        // Vec kartic lahko uporablja isto naslovnico. Prenesi/dekodiraj jo samo enkrat.
        if (!slikeVTeKu.add(naslov)) return
        slikeDelavec.execute {
            try {
                // 280 px je dovolj za TV kartice, hkrati pa precej zmanjsa heap in GC sunke na 2 GB TV.
                // Naslovnica z diska (brez omrezja; stara z izvirnimi bajti ali nova WebP); nove shranimo za naslednje
                // odprtje - pomanjsane na 280 px in kot WebP, ne izvirnih bajtov (naloga 56).
                val px = MedijskiPredpomnilnik.NASLOVNICA_PX
                val zacetek = android.os.SystemClock.uptimeMillis()
                val zDiska = MedijskiPredpomnilnik.naslovnica(this, naslov)
                val b = if (zDiska != null) VarnaSlika.izBajtov(zDiska, px)?.let { MedijskiPredpomnilnik.pomanjsaj(it, px) }?.let { MedijskiPredpomnilnik.zaPomnilnik(it) } ?: return@execute
                else {
                    val surovi = SpletniVir.bajtiSlike(this, naslov) ?: Jamendo.bajti(naslov) ?: return@execute
                    val m = VarnaSlika.izBajtov(surovi, px)?.let { MedijskiPredpomnilnik.pomanjsaj(it, px) } ?: return@execute
                    MedijskiPredpomnilnik.stisniNaslovnico(m)?.let { MedijskiPredpomnilnik.shraniNaslovnico(this, naslov, it) }
                    MedijskiPredpomnilnik.zaPomnilnik(m)   // naloga 77: brez prosojnosti RGB_565 (pol bajtov)
                }
                SLIKE.put(naslov, b)
                zabeleziNaslovnico(zDiska != null, android.os.SystemClock.uptimeMillis() - zacetek)
                val cakajoci = cakajoceSlike.remove(naslov)
                val pogledi = cakajoci?.let { synchronized(it) { it.toList() } }.orEmpty()
                glavna.post {
                    if (!isFinishing) pogledi.forEach { ref ->
                        ref.get()?.takeIf { it.tag == naslov }?.setImageBitmap(b)
                    }
                }
            } catch (e: OutOfMemoryError) {
                // Dnevnik TV 28. 10. 2026 (04:56 in 08:07, klik na film): OOM v niti naslovnic je ubil ves proces in TV je
                // skocil na domaci zaslon. Naslovnica izpade, polovica predpomnilnika slik se sprosti, aplikacija tece naprej.
                SLIKE.trimToSize(SLIKE.size() / 2)
                android.util.Log.w("SafeerOsMedia", "naslovnica: zmanjkalo pomnilnika, predpomnilnik slik zmanjsan na ${SLIKE.size() / 1024 / 1024} MB")
            } finally {
                slikeVTeKu.remove(naslov)
                cakajoceSlike.remove(naslov)
            }
        }
    }

    /**
     * Naloga 144: od kod pridejo naslovnice (disk = notranja baza ali USB, omrezje) in koliko casa traja do slike - na
     * vsakih [NASLOVNIC_ZA_DNEVNIK] ena vrstica v dnevnik (brez naslovov).
     */
    private val naslovniceStevci = LongArray(4)
    private fun zabeleziNaslovnico(zDiska: Boolean, ms: Long) {
        val vrstica = synchronized(naslovniceStevci) {
            val i = if (zDiska) 0 else 2
            naslovniceStevci[i]++; naslovniceStevci[i + 1] += ms
            if ((naslovniceStevci[0] + naslovniceStevci[2]) % NASLOVNIC_ZA_DNEVNIK != 0L) null
            else "naslovnice: z diska ${naslovniceStevci[0]} (povp. ${naslovniceStevci[1] / maxOf(1, naslovniceStevci[0])} ms), " +
                "iz omrezja ${naslovniceStevci[2]} (povp. ${naslovniceStevci[3] / maxOf(1, naslovniceStevci[2])} ms), v pomnilniku ${SLIKE.size() / 1024 / 1024} MB"
        }
        if (vrstica != null) android.util.Log.i("SafeerOsMedia", vrstica)
    }

    /** Slicica videa ali naslovnica albuma z naprave (MediaStore); brez nje ostane ikona. */
    private fun naloziKrajevnoSlicico(naslov: String, v: ImageView) {
        SLIKE.get(naslov)?.let { v.setImageBitmap(it); return }
        if (android.os.Build.VERSION.SDK_INT < 29) return
        v.tag = naslov
        val ref = WeakReference(v)
        slikeDelavec.execute {
            val b = try { contentResolver.loadThumbnail(android.net.Uri.parse(naslov), android.util.Size(320, 320), null) } catch (_: Throwable) { null }
                ?: return@execute
            SLIKE.put(naslov, b)
            glavna.post { if (!isFinishing) ref.get()?.takeIf { it.tag == naslov }?.setImageBitmap(b) }
        }
    }

    /** Pokaze shranjen uradni logotip ali obris TV; vedno ostane sredinsko oblikovana ikona. */
    private fun naloziTvIkono(id: String, v: ImageView) {
        v.tag = "tv:$id"
        tvIkone.computeIfAbsent(id) {
            Collections.synchronizedList(ArrayList<WeakReference<ImageView>>())
        }.add(WeakReference(v))
        TvVZivo.ikona(this, id)?.let { v.setImageDrawable(it) }
    }

    /**
     * Logotipi vgrajenih kanalov TV v zivo (z domacih strani izdajateljev). Naloga 57b: brez vgrajenih virov jih ne
     * nalagamo - razen za kanale, ki jih ima uporabnik med priljubljenimi. Brez virov Medijski center od zunaj ne nalaga
     * nicesar.
     */
    private fun osveziIkoneVgrajenih() {
        if (!MedijskiViri.VGRAJENI_SPLETNI_VIRI && MedijskiViri.priljubljene(this).none { it.id.startsWith("tv:") }) return
        TvVZivo.osveziIkone(this) { osveziTvIkone() }
    }

    /** Posodobi samo slike kartic. Postavitev in trenutno fokusiran pogled ostaneta nedotaknjena. */
    private fun osveziTvIkone() {
        if (isFinishing || razdelek != TV_V_ZIVO) return
        tvIkone.forEach { (id, pogledi) ->
            val ikona = TvVZivo.ikona(this, id) ?: return@forEach
            synchronized(pogledi) {
                val i = pogledi.iterator()
                while (i.hasNext()) {
                    val pogled = i.next().get()
                    if (pogled == null) i.remove()
                    else if (pogled.tag == "tv:$id") pogled.setImageDrawable(ikona)
                }
            }
        }
    }

    // ------------------------------------------------------------------ razdelki

    private var naMestuRazdelek = -1
    private var naMestuY = 0
    private var naMestuDo = 0L

    /** Isti razdelek narisemo znova (nekaj je bilo izbrisano ali dodano), drsnik pa ostane, kjer je bil - na dotik
     *  uporabnik sicer po vsakem izbrisu pristane na vrhu in mora nazaj do mesta, kjer je bil. */
    private fun izberiNaMestu(i: Int) {
        naMestuRazdelek = i; naMestuY = drsnik.scrollY; naMestuDo = android.os.SystemClock.uptimeMillis() + 4_000
        izberi(i)
    }

    private fun izberi(i: Int, izrecno: Boolean = false) {
        // Iskanje v zasebnem dodatku velja, dokler je odprto iskanje; iskane besede si ne zapomnimo.
        if (i != ISKANJE && zasebnoIskanje != null) { zasebnoIskanje = null; zadnjaBeseda = "" }
        if (i != izBlokaDomov?.first) izBlokaDomov = null
        razdelek = i
        odprtSeznam = ""
        podstrani.pocisti()
        podPodstranjo = null
        odprtKatalog = null
        mrezaKanalov?.preklici()       // naloga 73: odhod iz TV v zivo ustavi cakajoce strani katalogov
        mrezaKanalov = null
        mrezaVsebine = null
        // Seznami in viri z drugih naprav: vprasamo tudi ob menjavi razdelka (najvec na 45 s), ne le ob odprtju.
        uskladiSezname()
        osveziVlc(i)
        naloziKrajevno(i)
        naslov.text = if (vlc && i == DOMOV) getString(R.string.os_ime_predvajalnik) else getString(when (i) {
            GLASBA -> R.string.os_mediji_glasba; RADIO -> R.string.os_glasba_radio; VIDEO -> R.string.os_glasba_video
            TV_V_ZIVO -> R.string.os_mediji_tv_v_zivo
            VIRI -> R.string.os_mediji_viri; ISKANJE -> R.string.os_glasba_iskanje; else -> R.string.os_media_naslov
        })
        geslo.visibility = if (i == DOMOV && !ozekZaslon()) View.VISIBLE else View.GONE
        iskalnik = if (i == ISKANJE) novIskalnik() else null
        // Na plosci je velik "zdaj se predvaja"; mala vrstica spodaj je samo v razdelkih.
        vrstica.visibility = if (i == DOMOV || GlasbaStoritev.trenutna() == null) View.GONE else View.VISIBLE
        // Video se odpre naravnost v mrezo plakatov (Filmi ali Serije, zadnja izbira) - brez polic vmes (lastnik, 2. 10. 2026).
        if (i == VIDEO && i !in samoTaNaprava) videoNacin().takeIf { it.isNotEmpty() }?.let { ++nalaganje; odpriBrskanje(it, videoZvrst, videoJezik); return }
        // TV v zivo se odpre naravnost v mrezo kanalov z izbiro zvrsti in jezika (lastnik, 5. 10. 2026).
        if (i == TV_V_ZIVO) { ++nalaganje; odpriKanale(izrecno = izrecno); return }
        // Radio in Glasba enako: izbire na vrhu, mreza pod njimi - brez polic, po katerih je treba z daljincem dolgo v
        // desno (lastnik, 5. 10. 2026). Enako v samostojnem Predvajalniku (lastnik, 7. 10. 2026: »to velja za vse predvajalnike
        // in vse media centre«); glasba iz shrambe naprave je tam izbira »Na tej napravi« v isti mrezi.
        if (i == RADIO && i !in samoTaNaprava) { ++nalaganje; odpriMrezo(RADIO); return }
        if (i == GLASBA && i !in samoTaNaprava) { ++nalaganje; odpriMrezo(GLASBA); return }
        val moje = ++nalaganje
        val predpomnjeno = SEZNAMI[i]
        if (predpomnjeno != null) { prikazi(i, predpomnjeno); return }
        if (i == VIRI) { narisi(viriVrste(), opis(i), glava = glavaRazdelka(i)); return }
        if (i == ISKANJE) { zadetki?.let { narisi(it, opisZadetkov) } ?: narisi(predIskanjem(), getString(R.string.os_glasba_isci_navodilo)); return }
        // Zadnji znani pogled z diska pokazemo takoj (tudi po ponovnem zagonu), sveze police pa
        // nalozimo v ozadju in jih zamenjamo samo, ce so drugacne - brez praznega zaslona in cakanja.
        val kljucDiska = kljucPolic(i)
        // Kar je na napravi, pokazemo takoj; zadnji znani pogled z diska in sveze police pridejo z delovne niti
        // (branje in razclenjevanje JSON-a z diska je na televizorju trajalo do pol sekunde na glavni niti).
        narisi(zgoraj(i), getString(R.string.os_glasba_nalagam), getString(R.string.os_glasba_nalagam), glavaRazdelka(i))
        delavec.execute {
            // Naloga 62: pogled z diska iz casa pred spremembo virov ni vec pravi (polica izbrisanega vira bi ostala do
            // naslednjega obiska) - kot da ga ni. Nalaganje, med katerim so se viri spremenili, ne zapise nicesar.
            val rodVirov = MedijskiViri.spremenjeni(this)
            val sCasom = try { MedijskiPredpomnilnik.beriPoliceSCasom(this, kljucDiska)?.takeIf { it.first >= rodVirov } } catch (_: Exception) { null }
            val zDiska = sCasom?.second?.map { (n, v, s) -> Podatki(n, s, v) }
            if (zDiska != null) glavna.post { if (moje == nalaganje && !isFinishing) prikazi(i, zDiska) }
            val sveze = try {
                podatkiRazdelka(i).map { it.copy(skladbe = razvrsti(i, it.skladbe)) }
            } catch (_: Exception) { null }
            val polni = sveze != null && sveze.isNotEmpty() && sveze.all { it.skladbe.isNotEmpty() }
            // Vir, ki obcasno ne odgovori (izmerjeno 5. 10. 2026: lestvica glasbe), ne sme umakniti police, ki jo uporabnik
            // pozna, in je naslednjic vrniti: sveze police dopolnimo s tistimi z diska, ki jih tokrat ni - dokler je pogled
            // z diska mlajsi od enega dne. Na disk gre samo popoln svez pogled, zato se ta rok ne podaljsuje sam.
            val diskVelja = sCasom != null && System.currentTimeMillis() - sCasom.first in 0 until OsPravila.POLICA_OSTANE_MS
            val podatki = if (polni && zDiska != null && diskVelja) OsPravila.dopolniPolice(zDiska, sveze!!) { it.naslov } else sveze
            val viriIsti = MedijskiViri.spremenjeni(this) == rodVirov
            if (polni && podatki === sveze && viriIsti) MedijskiPredpomnilnik.shraniPolice(this, kljucDiska, sveze!!.map { Triple(it.naslov, it.video, it.skladbe) })
            glavna.post {
                if (moje != nalaganje || isFinishing) return@post
                if (podatki == null) {
                    // Brez omrezja: ce je pogled z diska, ostane; sicer povemo, da nalaganje ni uspelo.
                    if (zDiska == null) stanje.text = getString(R.string.os_glasba_napaka)
                    return@post
                }
                // Prazen odgovor (Jamendo obcasno) ne ostane v predpomnilniku - ob naslednji izbiri poskusimo znova.
                if (polni && viriIsti) SEZNAMI[i] = podatki
                // Pogled z diska je na zaslonu: znova ga narisemo samo, ce so police druge (nov vir, nova polica). Kadar se je
                // vsebina istih polic le premesala (lestvica priljubljenih), sveze pocaka na naslednji obisk razdelka -
                // uporabnik osvezevanja v ozadju ne sme cutiti (lastnik, 5. 10. 2026; izmerjeno: polno risanje 3 s po odprtju).
                dnevnikMreze { "police razdelka $i: z diska=${zDiska?.map { it.naslov.take(28) + "(" + it.skladbe.size + ")" }}, " +
                    "sveze=${sveze?.map { it.naslov.take(28) + "(" + it.skladbe.size + ")" }}, polni=$polni, dopolnjene=${podatki !== sveze}, disk velja=$diskVelja" }
                // Pogled z diska je na zaslonu in ostane: sveze police (nova, umaknjena ali premesana polica) pocakajo na
                // naslednji obisk razdelka - uporabnik osvezevanja v ozadju ne sme cutiti (lastnik, 5. 10. 2026; izmerjeno:
                // polno risanje 3,5 s po odprtju, polica izgine ali se pojavi).
                if (zDiska != null) return@post
                prikazi(i, podatki)
            }
        }
    }

    /** Kljuc polic na disku loci tudi vse zacasne poglede, da se med seboj ne pomesajo. */
    private fun kljucPolic(i: Int): String {
        val skriti = zacasnoSkritiViri[i].orEmpty().sorted().joinToString("") { "${it.length}:$it" }
        val jeziki = izklopljeniJeziki(i).sorted().joinToString("") { "${it.length}:$it" }
        // Nova razlicica zavrze police z blokiranimi ali sumljivimi PeerTube videi.
        val peertube = if (i == DOMOV || i == VIDEO) ":pt4" else ""
        val glasba = if (i == GLASBA && glasbaJezik.isNotEmpty()) ":g$glasbaJezik" else ""
        return "police:$i:${resources.configuration.locales[0].toLanguageTag()}:r${razvrstitev(i)}:l${i in samoTaNaprava}:f$skriti:j$jeziki$peertube$glasba"
    }

    private fun izklopljeniJeziki(i: Int): Set<String> =
        getSharedPreferences(NASTAVITVE_POGLEDA, MODE_PRIVATE)
            .getStringSet("$KLJUC_JEZIKOV$i", emptySet()).orEmpty()
            .mapTo(mutableSetOf(), JezikiVsebine::oznaka)

    private fun shraniIzklopljeneJezike(i: Int, jeziki: Set<String>) {
        getSharedPreferences(NASTAVITVE_POGLEDA, MODE_PRIVATE).edit()
            .putStringSet("$KLJUC_JEZIKOV$i", jeziki.mapTo(mutableSetOf(), JezikiVsebine::oznaka)).apply()
    }

    private fun filtrirajJezike(i: Int, skladbe: List<Jamendo.Skladba>) =
        JezikiVsebine.filtriraj(skladbe, izklopljeniJeziki(i)) { it.language }

    /** Razvrscanje je enako za vse razdelke in vedno velja samo znotraj posamezne police. */
    private fun razvrsti(i: Int, skladbe: List<Jamendo.Skladba>): List<Jamendo.Skladba> {
        val nacin = razvrstitev(i)
        if (nacin == RAZVRSTI_PRIPOROCENO) return skladbe
        val collator = Collator.getInstance(resources.configuration.locales[0]).apply {
            strength = Collator.SECONDARY
        }
        val poImenu = Comparator<Jamendo.Skladba> { a, b -> collator.compare(a.naslov, b.naslov) }
        val primerjalnik = when (nacin) {
            RAZVRSTI_NAJNOVEJSE -> Comparator<Jamendo.Skladba> { a, b ->
                when {
                    a.year == 0 && b.year != 0 -> 1
                    a.year != 0 && b.year == 0 -> -1
                    a.year != b.year -> b.year.compareTo(a.year)
                    else -> poImenu.compare(a, b)
                }
            }
            RAZVRSTI_NAJSTAREJSE -> Comparator<Jamendo.Skladba> { a, b ->
                when {
                    a.year == 0 && b.year != 0 -> 1
                    a.year != 0 && b.year == 0 -> -1
                    a.year != b.year -> a.year.compareTo(b.year)
                    else -> poImenu.compare(a, b)
                }
            }
            RAZVRSTI_IME_ZA -> poImenu.reversed()
            else -> poImenu
        }
        return skladbe.sortedWith(primerjalnik)
    }

    private fun razvrstitev(i: Int) = razvrstitve[i] ?: RAZVRSTI_PRIPOROCENO
    /** Vgrajeni viri (»vgrajen:...«) se ne uporabljajo vec (naloga 57, [MedijskiViri.VGRAJENI_SPLETNI_VIRI]). */
    private fun jeVirViden(i: Int, kljuc: String) = kljuc !in zacasnoSkritiViri[i].orEmpty() &&
        (MedijskiViri.VGRAJENI_SPLETNI_VIRI || !kljuc.startsWith(PREDPONA_VGRAJENEGA) || imenikDodan(kljuc))

    /**
     * Naloga 69: Radio Browser in TuneIn nista vec vgrajena (naloga 57), uporabnik pa ju lahko doda sam kot vir (Dodaj vir:
     * radio-browser.info, tunein.com). Takrat obstojeca koda (domace postaje, zvrsti, mreza Radia, iskanje) dela kot prej.
     */
    private fun imenikDodan(kljuc: String) = when (kljuc) {
        VIR_RADIO -> MedijskiViri.imaImenik(this, OsPravila.IMENIK_RADIO_BROWSER)
        VIR_TUNEIN -> MedijskiViri.imaImenik(this, OsPravila.IMENIK_TUNEIN)
        else -> false
    }
    private fun kljucVira(v: MedijskiViri.Vir) = "vir:${v.naslov}"

    /** Lokalni nacin bere samo MediaStore (notranja shramba in USB) in zato ne sprozi omrezja. */
    private fun krajevnePolice(i: Int): List<Podatki> {
        if (!KrajevneDatoteke.imamoDovoljenje(this)) return emptyList()
        fun beri(zbirka: String, video: Boolean) = KrajevneDatoteke.vsebina(this, zbirka).map { v ->
            // Pri videu trajanje (podnaslov na kartici in na zaslonu predvajanja); napredek doda videi().
            Jamendo.Skladba("krajevno:${v.id}", v.ime.substringBeforeLast('.'),
                if (video && v.trajanje > 0) cas(v.trajanje) else "", if (video) v.id else "", v.id, "",
                video = video, mime = v.mime,
                podnapisi = if (video) KrajevniPodnapisi.za(this, "krajevno:${v.id}") else emptyList())
        }
        val glasba = if (i == DOMOV || i == GLASBA) beri(KrajevneDatoteke.AUDIO, false) else emptyList()
        val videi = if (i == DOMOV || i == VIDEO) beri(KrajevneDatoteke.VIDEO, true) else emptyList()
        return listOfNotNull(
            glasba.takeIf { it.isNotEmpty() }?.let { Podatki(getString(R.string.os_mediji_vrsta_glasba), it) },
            videi.takeIf { it.isNotEmpty() }?.let { Podatki(getString(R.string.os_mediji_vrsta_video), it, video = true) }
        )
    }

    /** Razdelek: najprej krajevno (nadzorna plosca, nedavno, priljubljene), nato vrste s spleta. */
    private fun prikazi(i: Int, podatki: List<Podatki>) {
        prikazanePolice[i] = podatki
        // Odprta mreza (Filmi | Serije, Pokazi vse): sveze police si zapomnimo, mreze pa ne prerisemo cez.
        if ((odprtKatalog != null || podstrani.odprta) && i == razdelek) return
        val c0 = android.os.SystemClock.uptimeMillis()
        val filtrirani = podatki.map { it.copy(skladbe = filtrirajJezike(i, it.skladbe)) }
            .filter { it.skladbe.isNotEmpty() }
        if (i == TV_V_ZIVO) tvIkone.clear()
        val c1 = android.os.SystemClock.uptimeMillis()
        val zg = zgoraj(i); val c2 = android.os.SystemClock.uptimeMillis()
        // Domov: brez podvojenih prikazov - kar je ze v blokih na vrhu (nedavno, priljubljeno), se v blokih s spleta ne
        // ponovi; »Pokazi vse« takega bloka odpre njegov razdelek (mrezo z izbirami).
        val splosniDomov = i == DOMOV && i !in samoTaNaprava
        // Steje, kar blok na strani res pokaze (dve vrstici): vsebina, ki je v njem sele za »Pokazi vse«, ostane v bloku s spleta.
        val zeNaStrani = if (splosniDomov) zg.flatMap { v -> (if (v.mreza || v.pogled != null) v else blokMreze(v)).kartice }
            .mapNotNullTo(HashSet<String>()) { k -> k.id.takeIf { it.isNotBlank() } } else emptySet<String>()
        val zaBloke = if (zeNaStrani.isEmpty()) filtrirani
            else filtrirani.map { d -> d.copy(skladbe = d.skladbe.filterNot { it.id in zeNaStrani }) }.filter { it.skladbe.isNotEmpty() }
        val vr = vVrste(zaBloke).map { v -> if (splosniDomov) v.copy(vse = razdelekBloka(v.naslov)) else v }; val c3 = android.os.SystemClock.uptimeMillis()
        val gl = glavaRazdelka(i, podatki); val c4 = android.os.SystemClock.uptimeMillis()
        // Naloga 57: razdelek brez vsebine iz spletnih virov (koda nobenega ne doda sama) pod vsebino z naprav ponudi Dodaj vir.
        val dodaj = if (vr.isEmpty() && i !in samoTaNaprava && i in setOf(DOMOV, GLASBA, VIDEO, RADIO)) listOf(Vrsta("", listOf(karticaDodajVir()))) else emptyList()
        val brezVirov = dodaj.isNotEmpty() && brezSpletnihVirov()
        if (i == GLASBA && glasbaJezik.isNotEmpty() && vr.isEmpty()) narisi(zg + vr + dodaj, opis(i), getString(R.string.os_jezik_ni_vsebine), gl)
        else narisi(zg + vr + dodaj, if (brezVirov) getString(R.string.os_media_brez_virov) else opis(i), glava = gl)
        if (i == VIDEO) skociNaDodatek?.let { naslov ->
            val ime = Stremio.imeIzPredpomnilnika(naslov)
            if (ime != null) {
                skociNaDodatek = null
                drsnik.postDelayed({ (0 until vsebina.childCount).map { vsebina.getChildAt(it) }
                    .firstOrNull { (it.tag as? String)?.startsWith("polica:") == true && (it.tag as String).endsWith(" · $ime") }
                    ?.let { drsnik.smoothScrollTo(0, it.top.coerceAtLeast(0)) } }, 300)
            }
        }
        val c5 = android.os.SystemClock.uptimeMillis()
        if (si.safeer.tv.BuildConfig.DEBUG) android.util.Log.i("SafeerOsCas", "prikazi($i): filtri=${c1 - c0} zgoraj=${c2 - c1} vVrste=${c3 - c2} glava=${c4 - c3} narisi=${c5 - c4} ms, polic=${podatki.size}, kartic=${podatki.sumOf { it.skladbe.size }}")
        if (i == TV_V_ZIVO) osveziIkoneVgrajenih()
    }

    private fun opis(i: Int) = when (i) {
        GLASBA -> getString(R.string.os_media_gl_isci_opis)
        RADIO -> getString(R.string.os_glasba_radiji)
        TV_V_ZIVO -> getString(R.string.os_media_tv_opis)
        VIDEO -> getString(R.string.os_glasba_video_opis)
        VIRI -> getString(R.string.os_mediji_viri_opis)
        else -> getString(R.string.os_media_podnaslov)
    }

    /** Podatki razdelka s spleta (klic na delovni niti). */
    private fun podatkiRazdelka(i: Int): List<Podatki> {
        if (i in samoTaNaprava) return krajevnePolice(i)
        // Naloga 62: viri se med nalaganjem spremenijo - vse kartice razdelka (VSE_VIDEO) potem niso vec prave.
        val rodVirovRazdelka = MedijskiViri.spremenjeni(this)
        // Diagnostika na zahtevo razvijalca (Stremio.dnevnik): katalogi dodatkov; brez vklopa ne naredi nicesar.
        if (Stremio.dnevnik()) try { Stremio.dnevnikKatalogov(stremioNaslovi()) } catch (_: Exception) { }
        return when (i) {
        DOMOV -> {
            val radio = if (jeVirViden(i, VIR_RADIO)) Radio.postajeLocene().let { it.first + it.second } else emptyList()
            val peertube = if (jeVirViden(i, VIR_PEERTUBE)) MedijskiViri.vgrajeniPeerTube(this) else emptyList()
            val peertubeVsebina = PeerTube.najboljGledani(peertube, 12).map { it.second }
            listOfNotNull(
                if (jeVirViden(i, VIR_JAMENDO)) Podatki(getString(R.string.os_mediji_vrsta_glasba), Jamendo.priljubljene(24)) else null,
                radio.takeIf { it.isNotEmpty() }?.let { Podatki(getString(R.string.os_mediji_vrsta_radio), it.take(24)) },
                Podatki(getString(R.string.os_mediji_vrsta_video), izmenicno(peertubeVsebina), video = true),
            ).filter { it.skladbe.isNotEmpty() }
        }
        GLASBA -> {
            // Discovery namesto podvajanja ene same vrste "Most played": najprej en unikaten
            // popularen izbor, nato zvrsti. Posamezna skladba se med vrstami prikaze samo enkrat.
            val uporabljeni = mutableSetOf<String>()
            fun unikatne(s: List<Jamendo.Skladba>, meja: Int = 18) = s.filter { uporabljeni.add(it.id) }.take(meja)
            // Izbran jezik: Jamendo skladbe izbere sam (jezik besedila); iz ostalih virov ostane, kar jezik pove in se ujema.
            val jezik = glasbaJezik
            fun vJeziku(s: List<Jamendo.Skladba>) = if (jezik.isEmpty()) s else s.filter { JezikiVsebine.oznaka(it.language) == jezik }
            val vrste = mutableListOf<Podatki>()
            val vsebinaVirov = vJeziku(SpletniVir.priljubljeno(this, MedijskiViri.vsi(this).filter { jeVirViden(i, kljucVira(it)) }))
            val videospoti = vsebinaVirov.filter { SpletniVir.vrstaVsebine(it) == SpletniVir.VIDEOSPOT }
            val avdioViri = vsebinaVirov.filterNot { it.video || SpletniVir.vrstaVsebine(it) == SpletniVir.VIDEOSPOT }

            // Glasbeni dodatki uporabnika (albumi, skladbe, seznami, podkasti): njegova izbira je pred vgrajenimi viri.
            vrste += policeDodatkov(i, Stremio.GLASBA).map { it.copy(skladbe = vJeziku(it.skladbe)) }.filter { it.skladbe.isNotEmpty() }
            avdioViri.takeIf { it.isNotEmpty() }?.let {
                vrste += Podatki(getString(R.string.os_media_prilj_v_virih), it)
            }
            videospoti.takeIf { it.isNotEmpty() }?.let {
                vrste += Podatki(getString(R.string.os_media_videospoti), it, video = true)
            }
            if (jeVirViden(i, VIR_JAMENDO)) {
                unikatne(Jamendo.priljubljene(24, jezik), 18).takeIf { it.isNotEmpty() }?.let {
                    vrste += Podatki(getString(R.string.os_media_popularno), it)
                }
                // Po glasbenih zvrsteh, kot filmi po zanrih (police nalozimo vzporedno).
                val poZvrsteh = ZVRSTI.filter { it.first.isNotEmpty() }.map { z ->
                    java.util.concurrent.CompletableFuture.supplyAsync { z.third to Jamendo.poZvrsti(z.first, 18, jezik) }
                }.map { it.get() }
                poZvrsteh.forEach { (naziv, skladbe) ->
                    unikatne(skladbe).takeIf { it.isNotEmpty() }?.let { vrste += Podatki(getString(naziv), it) }
                }
            }
            vrste
        }
        RADIO -> {
            val radioViden = jeVirViden(i, VIR_RADIO)
            val tuneInViden = jeVirViden(i, VIR_TUNEIN)
            // Naloga 69: preverba na TV (adb logcat -s SafeerOsMedia) - imenik postaj dela samo kot dodan vir.
            android.util.Log.i("SafeerOsMedia", "radio: imenik Radio Browser=$radioViden, TuneIn=$tuneInViden")
            val vrste = mutableListOf<Podatki>()
            // Radijske postaje iz uporabnikovih dodatkov so pred vgrajenimi.
            vrste += policeDodatkov(i, Stremio.RADIO)
            if (!radioViden && !tuneInViden) return vrste
            val (domaceVse, svetVse) = if (radioViden) Radio.postajeLocene() else emptyList<Jamendo.Skladba>() to emptyList()
            // Naloga 71: Radio Browser ima isto postajo pogosto veckrat (vec tokov); TuneIn lokalne so vecinoma iste domace postaje.
            val domace = brezDvojnikovPostaj(domaceVse)
            val svet = brezDvojnikovPostaj(svetVse)
            if (domace.isNotEmpty()) vrste += Podatki(getString(R.string.os_mediji_domace), domace)
            if (tuneInViden) TuneIn.lokalne().let { t -> brezDvojnikovPostaj(domace + t).filter { TuneIn.jeEnota(it) } }.takeIf { it.isNotEmpty() }?.let {
                vrste += Podatki(getString(R.string.os_media_tunein_lokalne), it)
            }
            if (radioViden) {
                val poZvrsteh = ZVRSTI.map { z ->
                    java.util.concurrent.CompletableFuture.supplyAsync { z.third to Radio.poZvrsti(z.second, 24) }
                }.map { it.get() }
                poZvrsteh.forEach { (naziv, postaje) -> brezDvojnikovPostaj(postaje).let { if (it.isNotEmpty()) vrste += Podatki(getString(naziv), it) } }
                vrste += Podatki(getString(R.string.os_mediji_svet), svet)
            }
            vrste.filter { it.skladbe.isNotEmpty() }
        }
        VIDEO -> {
            val vsiViri = MedijskiViri.vsi(this)
            val medijskiViri = vsiViri.filter { jeVirViden(i, kljucVira(it)) }
            android.util.Log.i("SafeerOsMedia", "viri=${medijskiViri.size}, spletni=${medijskiViri.count { it.jeSplet }}")
            // Uporabnik doda vir in nanj pozabi (lastnik, 2. 10. 2026): police so po VSEBINI (Zate, Filmi, Serije, Video,
            // zvrsti), ne po virih. Vse vire vprasamo hkrati, vsebino zdruzimo in isti film iz vec virov je ena kartica.
            val stremio = vsiViri.filter { it.jeStremio && jeVirViden(i, kljucVira(it)) }.map { it.naslov }
            // Naloga 143: vsak dodatek dobi svoj delez od 12 katalogov (prej so jih zapolnili prvi dodatki).
            val katalogi = if (stremio.isEmpty()) emptyList() else OsPravila.pravicnoPoDodatkih(
                try { Stremio.prikazniKatalogi(Stremio.zKatalogom(stremio)) } catch (_: Exception) { emptyList() }, { it.dodatek }, 12)
            // Manifesti so zdaj znani: zaseben dodatek, ki je sem prisel z usklajevanjem, tu izgine (ZasebniDodatki).
            try { MedijskiViri.odstraniPrevzeteZasebne(this) } catch (_: Exception) { }
            val izKatalogov = katalogi.map { k -> iskanjeDelavec.submit<List<Jamendo.Skladba>> { try { Stremio.katalog(k) } catch (_: Exception) { emptyList() } } }
            val javnaLast = if (jeVirViden(i, VIR_JAVNA_LAST)) iskanjeDelavec.submit<List<Jamendo.Skladba>> { try { JavnaLast.isci() } catch (_: Exception) { emptyList() } } else null
            val peertubeStrezniki = (
                (if (jeVirViden(i, VIR_PEERTUBE)) MedijskiViri.vgrajeniPeerTube(this) else emptyList()) +
                    vsiViri.filter { it.jePeerTube && jeVirViden(i, kljucVira(it)) }.map { it.naslov }).distinct()
            val peertube = if (peertubeStrezniki.isEmpty()) null else iskanjeDelavec.submit<List<Jamendo.Skladba>> {
                try { PeerTube.najboljGledani(peertubeStrezniki, 24).flatMap { it.second } } catch (_: Exception) { emptyList() }
            }
            val surovi = SpletniVir.priljubljeno(this, medijskiViri).filter {
                (it.video || SpletniVir.vrstaVsebine(it) == SpletniVir.SERIJA || SpletniVir.vrstaVsebine(it) == SpletniVir.FILM) &&
                SpletniVir.vrstaVsebine(it) != SpletniVir.VIDEOSPOT &&
                !it.mediaType.equals("MusicVideo", ignoreCase = true)
            }
            fun pocakaj(f: java.util.concurrent.Future<List<Jamendo.Skladba>>?): List<Jamendo.Skladba> =
                try { f?.get(20, java.util.concurrent.TimeUnit.SECONDS).orEmpty() } catch (_: Exception) { emptyList() }
            // Katalogi dodatkov se prepletejo (vsak prispeva po vrsti), da polica ni samo iz prvega dodatka.
            val izDodatkov = prepleti(izKatalogov.map { pocakaj(it) })
            val kandidati = surovi + izDodatkov + pocakaj(javnaLast) + pocakaj(peertube)
            // Ena kartica na vsebino: isti film/video iz vec virov (IMDb, naslov + letnica, naslov + kanal) se zdruzi.
            val vse = SpletniVir.zdruziEnako(kandidati).map { it.first() }
            // Mreza Filmi | Serije dobi VSE kartice, ne le prvih nekaj s polic (police so omejene: filmi javne lasti so
            // za katalogi dodatkov izpadli iz mreze - na televizorju brez pomocnika je ostal en sam film).
            if (MedijskiViri.spremenjeni(this) == rodVirovRazdelka) VSE_VIDEO = vse
            android.util.Log.i("SafeerOsMedia", "video: kandidati=${kandidati.size} (splet ${surovi.size}, dodatki ${izDodatkov.size}), kartice=${vse.size}")
            val filmi = vse.filter { SpletniVir.vrstaVsebine(it) == SpletniVir.FILM }
            val serije = vse.filter { SpletniVir.vrstaVsebine(it) == SpletniVir.SERIJA }
            val ostali = vse.filter { SpletniVir.vrstaVsebine(it) == null }

            val vrste = mutableListOf<Podatki>()
            val priporocila = MediaPriporocila.uredi(this, vse)
            val imaZgodovino = MediaNapredek.seznam(this).any { it.skladba.video } ||
                MedijskiViri.priljubljene(this).any { it.video } ||
                MedijskiViri.nedavno(this).any { it.video }
            val zateSeznam = priporocila.zate.take(15)
            // "Zate" ni ponovitev zacetka druge police: brez zgodovine ogledov (ni cesa priporociti) bi bila
            // ista kot Filmi. Kar Zate pokaze, se v policah pod njo ne ponovi (lastnik, 2. 10. 2026).
            val zateId = zateSeznam.map { it.id }
            fun jeZacetek(l: List<Jamendo.Skladba>) = l.isNotEmpty() && l.take(zateId.size).map { it.id } == zateId
            val zateJe = zateSeznam.isNotEmpty() && imaZgodovino && !jeZacetek(filmi) && !jeZacetek(serije) && !jeZacetek(ostali)
            if (zateJe) vrste += Podatki(getString(R.string.os_media_zate), zateSeznam, video = true)
            val vZate = if (zateJe) zateId.toSet() else emptySet()
            // Knjiznica kroga: kar naprave v Linku in ta naprava hranijo (torrenti iz dodatkov) - ogled ali odstranitev.
            prenosiNaRacunalnikih().takeIf { it.isNotEmpty() }?.let {
                vrste += Podatki(getString(R.string.os_prenosi_racunalnik), it, video = true)
            }
            filmi.filter { it.id !in vZate }.takeIf { it.isNotEmpty() }?.let { vrste += Podatki(getString(R.string.os_media_filmi), it.take(POLICA_NAJVEC), video = true) }
            serije.filter { it.id !in vZate }.takeIf { it.isNotEmpty() }?.let { vrste += Podatki(getString(R.string.os_media_serije), it.take(POLICA_NAJVEC), video = true) }
            if (ostali.isNotEmpty() && (filmi.isEmpty() || ostali.size >= MIN_KARTIC_KATEGORIJE)) {
                ostali.filter { it.id !in vZate }.takeIf { it.isNotEmpty() }?.let { vrste += Podatki(getString(R.string.os_glasba_video), it.take(POLICA_NAJVEC), video = true) }
            }
            // Zvrsti cez vse vire skupaj; polica le, kadar vsebina sama da dovolj mocan signal.
            listOf("Komedija", "Grozljivke", "Drama", "Akcija", "Fantastika", "Kriminalke", "Dokumentarci", "Animacija", "Druzinski", "Romantika").forEach { z ->
                vse.filter { SpletniVir.zvrstVsebine(it) == z }.takeIf { it.size >= MIN_KARTIC_KATEGORIJE }?.let { vrste += Podatki(z, it.take(POLICA_NAJVEC), video = true) }
            }
            android.util.Log.i("SafeerOsMedia", "enote=${vse.size}, z_vrsto=${filmi.size + serije.size}, police=${vrste.size}, prag=$MIN_KARTIC_KATEGORIJE")
            vrste
        }
        else -> emptyList()
        }
    }

    /**
     * Police katalogov uporabnikovih dodatkov izbranega razreda (glasba, radio): polica nosi ime kataloga
     * ("Top albums"), ne dodatka - uporabniku ni treba vedeti, od kod pride. Katalogi z istim imenom so ena polica.
     */
    private fun policeDodatkov(i: Int, razred: String): List<Podatki> {
        val dodatki = MedijskiViri.vsi(this).filter { it.jeStremio && jeVirViden(i, kljucVira(it)) }.map { it.naslov }
        if (dodatki.isEmpty()) return emptyList()
        val katalogi = try { Stremio.katalogiRazreda(dodatki, razred) } catch (_: Exception) { emptyList() }.take(10)
        val strani = katalogi.map { k -> iskanjeDelavec.submit<List<Jamendo.Skladba>> { try { Stremio.katalog(k) } catch (_: Exception) { emptyList() } } }
            .map { f -> try { f.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { emptyList() } }
        val poImenu = LinkedHashMap<String, MutableList<List<Jamendo.Skladba>>>()
        katalogi.zip(strani).forEach { (k, s) -> if (s.isNotEmpty()) poImenu.getOrPut(k.ime) { mutableListOf() } += s }
        // Ista skladba, album ali postaja iz vec dodatkov je ena kartica - tudi med policami (prva polica jo obdrzi).
        val videni = HashSet<String>()
        return poImenu.map { (ime, s) ->
            Podatki(ime, prepleti(s).filter { videni.add(SpletniVir.cistNaslov(it.izvajalec + " " + it.naslov).ifBlank { it.id }) }.take(POLICA_NAJVEC))
        }.filter { it.skladbe.isNotEmpty() }
    }

    private fun vVrste(p: List<Podatki>) = p.mapNotNull { d ->
        val kartice = if (d.video) videi(d.skladbe, d.naslov) else skladbe(d.skladbe, d.naslov)
        // Polica kataloga Stremio: na koncu "Pokazi vse" - mreza z vsemi stranmi kataloga (skip), kot Discover v Stremiu.
        val zVsemi = if (d.video && jePolicaKataloga(d.naslov) && d.skladbe.size >= STRAN_KATALOGA_MIN) kartice + pokaziVseKartica(d.naslov, d.skladbe) else kartice
        // Blok postaj: lezece ploscice z logotipom kot v mrezi Radia; skladbe: na televizorju manjse kartice kot v mrezi Glasbe.
        val postaje = !d.video && d.skladbe.isNotEmpty() && d.skladbe.all { it.radio }
        zVsemi.takeIf { it.isNotEmpty() }?.let { Vrsta(d.naslov, it, d.video, kanali = postaje, skladbe = !d.video && !postaje) }
    }

    // ------------------------------------------------------------------ katalog Stremio (Pokazi vse, strani)

    /** Odprta polica (Pokazi vse): katalogi, ki jo polnijo, do zdaj nalozeni vnosi (zdruzeni), koliko jih je dal vsak katalog in ali je se kaj. */
    private class OdprtKatalog(var katalogi: List<Stremio.Katalog>, val vsi: MutableList<Jamendo.Skladba>,
                               val preneseno: HashMap<Stremio.Katalog, Int>, var seKaj: Boolean,
                               /** Mreza Filmi | Serije ("movie" / "series") cez vse vire; prazno = navaden katalog (Pokazi vse). */
                               val tip: String = "", val zvrst: String = "", var naslov: String = "", var nalagam: Boolean = false,
                               /** Zaporedne strani brez nove vsebine (filter zvrsti na nasi strani): po treh nehamo nalagati. */
                               var prazne: Int = 0,
                               /** Mreza enega (zasebnega) dodatka: njegov naslov; iskanje, odprto od tu, isce samo v njem. */
                               val dodatek: String = "",
                               /** Mreza Filmi | Serije: izbrani jezik vsebine (ISO 639-1); prazno = vsi jeziki. */
                               val jezik: String = "") {
        /** Seznam naslovov v izbranem jeziku ([IzvirniJeziki.seznam]): koliko smo jih ze prosili in ali jih je se kaj. */
        var jezikOdmik = 0
        var jezikSeKaj = false
        /** Polica »Na tvojih napravah« nad mrezo zasebnega dodatka: njegovi prenosi na napravah v Linku (samo tu). */
        var prenosi: List<Jamendo.Skladba> = emptyList()
        /** Koliko naslovov te mreze se preverjamo pri dodatkih, ker nanje caka okno. */
        val caka = java.util.concurrent.atomic.AtomicInteger()
        /** Koliko jih preverjamo v ozadju (korak naprej, zastareli odgovori): zaslon nanje ne caka. */
        val ozadje = java.util.concurrent.atomic.AtomicInteger()
        /** Vsi naslovi mreze v vrstnem redu prikaza, tudi skriti (za osvezevanje zastarelih odgovorov). */
        @Volatile var urejeni: List<Jamendo.Skladba> = emptyList()
        /** Naslovi, za katere dodatki niso odgovorili: v tej mrezi jih ne sprasujemo znova. */
        val neznani: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        /** Kartice, ki so zdaj na zaslonu (id-ji po vrsti): mrezo risemo znova samo, ce bi bila drugacna. */
        var narisani: List<String> = emptyList()
        /** Naloga 61: zgrajene kartice te mreze - Nalozi vec in dopolnitev zgradita samo nove ([videi]). Nova mreza (filter,
         *  zvrst, jezik, vrsta) je nov OdprtKatalog in zacne prazna. */
        val zapomnjene = ZapomnjeneKartice()
        /** Strogi nacin: koliko kartic naj bo vsaj na zaslonu, preden nehamo sami nalagati naslednje strani. */
        var cilj = 1
        var samodejno = 0
        /** Mreza Filmi | Serije pozna vse kartice razdelka (ne le katalogov): sele takrat sami nalagamo naslednje strani. */
        var popolna = tip.isBlank()
        /**
         * Vljudno preverjanje (Stremio.razpolozljivo). Strogi nacin kaze in preverja samo OKNO: prvih [zelja] kandidatov
         * po vrsti (kandidat je preverjena kartica ali naslov, ki ga se ne poznamo; nepredvajljivi izpadejo in okno se
         * samo pomakne naprej). [proracun] = koliko naslovov smemo se vprasati dodatke do naslednjega uporabnikovega
         * dejanja (drsenje proti koncu, Nalozi vec). Tako dodatek ne dobi poizvedbe za ves katalog naenkrat.
         */
        var zelja = ZELJA_KARTIC
        var proracun = PRORACUN_PREVERJANJ
        val preverjenih = java.util.concurrent.atomic.AtomicInteger()
        val zadetkov = java.util.concurrent.atomic.AtomicInteger()
        var zadnjicVec = 0L
        /** Stanje ob zadnjem risanju po koncanem preverjanju (kartice, ali je gumb Nalozi vec): da ne risemo v krogu. */
        var narisano: Pair<List<String>, Boolean>? = null
        /** Naloga 55: kandidati so le prvih zelja + [REZERVA_KANDIDATOV] ([vidniKataloga]); prirezani = za njimi je se kaj. */
        var prirezani = false
        /** Koliko naslovov [urejeni] od zacetka je zadnji izris pregledal (za vzorce knjiznic). */
        var pregledanih = 0
        /** Zadnja ureditev (odtis vhoda -> [urejeni]): enak vhod se ne razvrsca znova ob vsakem izrisu. */
        var ureditev: Pair<List<Any>, List<Jamendo.Skladba>>? = null
        /** Kdaj je bila mreza nazadnje narisana (uptime): dodane kartice se dopolnijo najvec vsakih [DOPOLNI_NAJVEC_MS]. */
        var risanoOb = 0L
        /** Uporabnik je kliknil Nalozi vec, medtem ko se okno se preverja: kartica pove, koliko naslovov se preverjamo. */
        var prosiVec = false
        /**
         * Naloga 55b (lastnik, 22. 10. 2026: »Nalozi vec: takoj pokazi, preveri v ozadju«): strogo preverjenih je le prvih
         * toliko mest okna (prva stran Filmov | Serij); naslednje strani se pokazejo takoj in se preverjajo v ozadju.
         * Navaden katalog (Pokazi vse) ostane ves strog.
         */
        val strogoDo = if (tip.isNotBlank()) ZELJA_KARTIC else Int.MAX_VALUE
        /** Kartica, ki je ze izpadla iz mreze, a ima izbiro (daljinec): ostane do premika izbire. */
        var drzim = false
        /** Uporabnik hoce vec: okno se podaljsa za [korak] kartic, proracun poizvedb je nov. */
        fun vec(korak: Int) { zelja += korak; proracun = PRORACUN_PREVERJANJ; preverjenih.set(0); zadetkov.set(0); samodejno = 0 }
    }

    /** Stanje okna stroge mreze: v oknu je se kaj nepreverjenega; nekaj naslovov caka, ker dodatek omejuje poizvedbe; za oknom so se kandidati. */
    /** [nepreverjeniNaZaslonu]: na zaslonu so naslovi naslednjih strani, ki se niso preverjeni (naloga 55b). */
    private class StanjeMreze(val vOknu: Boolean, val premor: Boolean, val naprej: Boolean, val nepreverjeniNaZaslonu: Boolean = false)
    /** Okno stroge mreze ([oknoMreze]): naslovi v oknu, ali so za njim se kandidati, koliko naslovov caka na dodatek v premoru. */
    private class Okno(val naslovi: List<Jamendo.Skladba>, val naprej: Boolean, val vPremoru: Int)
    private var odprtKatalog: OdprtKatalog? = null
    private var odprtKatalogIz = VIDEO

    private fun jePolicaKataloga(naslov: String) = naslov.startsWith("🎬 ") || naslov.startsWith("📺 ") || naslov.startsWith("📡 ") ||
        (stremioNaslovi().isNotEmpty() && (naslov == getString(R.string.os_media_filmi) || naslov == getString(R.string.os_media_serije)))

    /** Katalogi dodatkov, ki prispevajo polici: Filmi = vsi filmski, Serije = vsi serijski, sicer katalog z istim naslovom police. */
    private fun katalogiPolice(naslov: String): List<Stremio.Katalog> {
        val n = stremioNaslovi()
        val vsi = Stremio.prikazniKatalogi(Stremio.zKatalogom(n))
        return when (naslov) {
            getString(R.string.os_media_filmi) -> vsi.filter { it.tip == "movie" }
            getString(R.string.os_media_serije) -> vsi.filter { it.tip == "series" }
            "📡 " + getString(R.string.os_media_kanali_virov) -> Stremio.katalogiTv(n)
            else -> listOfNotNull((vsi + Stremio.katalogiTv(n)).firstOrNull { naslovKataloga(it) == naslov })
        }
    }

    /** Vsebine vec katalogov v eni vrsti: vsak katalog prispeva po vrsti (1. iz vsakega, 2. iz vsakega ...). */
    private fun prepleti(seznami: List<List<Jamendo.Skladba>>): List<Jamendo.Skladba> {
        val izhod = ArrayList<Jamendo.Skladba>(seznami.sumOf { it.size })
        val najvec = seznami.maxOfOrNull { it.size } ?: 0
        for (j in 0 until najvec) for (sez in seznami) sez.getOrNull(j)?.let { izhod += it }
        return izhod
    }

    private fun pokaziVseKartica(naslov: String, prvaStran: List<Jamendo.Skladba>) =
        Kartica(getString(R.string.os_media_pokazi_vse), naslov.substringAfter(" · "), "", {
            when (naslov) {
                getString(R.string.os_media_filmi) -> odpriBrskanje("movie")
                getString(R.string.os_media_serije) -> odpriBrskanje("series")
                else -> odpriKatalog(naslov, prvaStran)
            }
        }, ikona = R.drawable.os_ikona_mreza, dejanje = true, vse = true)

    /**
     * Kataloge najdemo po naslovu police (police so tudi s predpomnilnika na disku, kjer kataloga ni). Prve strani vseh
     * katalogov police prenesemo znova, da vemo, koliko je dal vsak (od tam naprej gre "Nalozi vec" po katalogih).
     */
    private fun odpriKatalog(naslov: String, prvaStran: List<Jamendo.Skladba>) {
        stanje.text = getString(R.string.os_glasba_nalagam)
        val iz = razdelek
        val odGlobine = podstrani.globina
        delavec.execute {
            val katalogi = try { katalogiPolice(naslov) } catch (_: Exception) { emptyList() }
            val strani = katalogi.map { k -> iskanjeDelavec.submit<List<Jamendo.Skladba>> { try { Stremio.katalog(k) } catch (_: Exception) { emptyList() } } }
                .map { f -> try { f.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { emptyList() } }
            val preneseno = HashMap<Stremio.Katalog, Int>().apply { katalogi.zip(strani).forEach { (k, v) -> put(k, v.size) } }
            val vsi = SpletniVir.zdruziEnako(prvaStran + prepleti(strani)).map { it.first() }.toMutableList()
            glavna.post {
                if (isFinishing || isDestroyed) return@post
                // Uporabnik je medtem odsel drugam (drug razdelek, podstran, Nazaj): kataloga mu ne narisemo cez to, kar gleda.
                if (razdelek != iz || podstrani.globina != odGlobine) return@post
                if (katalogi.isEmpty()) { stanje.text = getString(R.string.os_glasba_napaka); return@post }
                odprtKatalog = OdprtKatalog(katalogi, vsi, preneseno, katalogi.zip(strani).any { (k, s) -> OsPravila.katalogImaVec(s.size, k.strani, STRAN_KATALOGA_MIN) }, naslov = naslov)
                odprtKatalogIz = iz
                narisiKatalog(naslov)
                fokusNaPrvo()
            }
        }
    }

    /** [vnaprej]: izid [vidniKataloga], ce ga je klicatelj (umiri) ravnokar izracunal - ne racunamo ga dvakrat. */
    private fun narisiKatalog(naslov: String, vnaprej: Pair<List<Jamendo.Skladba>, List<Jamendo.Skladba>>? = null) {
        val o = odprtKatalog ?: return
        o.naslov = naslov
        if (podstrani.odprta && podPodstranjo === o) return        // katalog pod odprto podstranjo: narisan bo ob vrnitvi
        val brskanje = o.tip.isNotBlank()
        // Cesar noben dodatek ne predvaja, ne kazemo; ostalo v ozadju preverimo (preveriMrezo). V strogem nacinu
        // (vidniKataloga) kartica pride na zaslon sele, ko vemo, da se da predvajati - nic se ne pokaze in spet skrije.
        val t0 = android.os.SystemClock.uptimeMillis()
        val (kandidati, vsi) = vnaprej ?: vidniKataloga(o)
        val t1 = android.os.SystemClock.uptimeMillis()
        val strogo = vsi.size != kandidati.size || strogaMreza(o)
        preveriMrezo(o, kandidati)
        val t2 = android.os.SystemClock.uptimeMillis()
        val noviId = vsi.map { it.id }
        // Nalozi vec med preverjanjem: napis velja, dokler ne pride nova kartica ali se preverjanje okna ne konca.
        if (o.prosiVec && (o.caka.get() == 0 || noviId.size > o.narisani.size)) o.prosiVec = false
        o.narisani = noviId
        o.risanoOb = android.os.SystemClock.uptimeMillis()
        // Ce nobenega naslova ne predvaja noben dodatek, "Nalozi vec" ne pomaga (naslednje strani bi izginile enako).
        // Vljudno preverjanje: kar je za oknom ali se ni preverjeno, caka na uporabnika (drsenje, Nalozi vec).
        val st = if (strogaMreza(o)) stanjeMreze(o, kandidati) else StanjeMreze(false, false, false)
        val seVec = o.seKaj || st.vOknu || st.naprej
        o.narisano = o.narisani to seVec
        val t3 = android.os.SystemClock.uptimeMillis()
        // Kartica Nalozi vec po kliku med preverjanjem pove, da delamo (dodatek 22. 10. 2026: klik prej ni naredil nic vidnega).
        val napisVec = if (o.prosiVec) getString(R.string.os_media_preverjam_se, o.caka.get()) else getString(R.string.os_media_nalozi_vec)
        o.zapomnjene.novih = 0; o.zapomnjene.ponovljenih = 0
        val kartice = videi(vsi, naslov, zapomnjene = o.zapomnjene) + (if (seVec && (vsi.isNotEmpty() || (o.caka.get() == 0 && !o.nalagam))) listOf(Kartica(napisVec, "", "",
            { vecMreze(o) }, ikona = R.drawable.os_ikona_plus)) else emptyList()) +
            // Naloga 57: prazna mreza brez cesar koli za preverjanje ponudi Dodaj vir.
            // Naloga 78: zvrst ali jezik je izpraznila mrezo Filmi | Serije - kartica, ki filter odstrani.
            (if (vsi.isEmpty() && !seVec && !o.nalagam && o.caka.get() == 0 && o.popolna && o.tip.isNotBlank() && (o.zvrst.isNotBlank() || o.jezik.isNotBlank()))
                listOf(Kartica(getString(R.string.os_media_pokazi_vse), getString(R.string.os_media_pokazi_vse_opis), "", { odpriBrskanje(o.tip) }, ikona = R.drawable.os_ikona_mreza)) else emptyList()) +
            (if (vsi.isEmpty() && !seVec && !o.nalagam && o.caka.get() == 0 && o.popolna) listOf(karticaDodajVir()) else emptyList()) +
            // Naloga 57c: konec kataloga - kartica Nalozi vec ne izgine brez razlage; klik ponudi Dodaj vir.
            (if (vsi.isNotEmpty() && !seVec && !o.nalagam && o.caka.get() == 0 && o.popolna) listOf(Kartica(getString(R.string.os_media_ni_vec_v_katalogu),
                getString(R.string.os_mediji_dodaj), "", { dodajVir() }, ikona = R.drawable.os_ikona_plus)) else emptyList())
        // Pod naslovom razdelka ni stevila kartic (lastnik, 3. 10. 2026) - samo, kadar se kaj nalaga ali ni nicesar.
        val opis = opisMreze(o, st, vsi.isEmpty())
        if (strogo) narociUmiri()
        if (!brskanje) {
            // Zaseben dodatek: nad mrezo njegovi prenosi (predvajaj, obdrzi, odstrani) - na skupnih policah jih ni.
            val polica = o.prenosi.takeIf { it.isNotEmpty() }?.let { Vrsta(getString(R.string.os_prenosi_racunalnik), videi(it), video = true) }
            if (polica != null) narisi(listOf(polica, Vrsta(naslov, kartice, video = true, mreza = true)), opis)
            else pokaziMrezo(o, Vrsta(naslov, kartice, video = true, mreza = true), opis, "katalog") { emptyList() }
            return
        }
        // Filmi | Serije: zavihki, zvrsti in razvrstitev na vrhu, pod njimi mreza plakatov (brez naslova police).
        // Ob spremembi (preverjanje v ozadju, naslednja stran) se mreza dopolni na mestu - brez utripa in skoka.
        val t4 = android.os.SystemClock.uptimeMillis()
        pokaziMrezo(o, Vrsta("", kartice, video = true, mreza = true, cisto = true), opis,
            "${o.tip}|${o.zvrst}|${o.jezik}|${razvrstitev(VIDEO)}|${ozekZaslon()}|$vlc") { glavaBrskanja(o) }
        val t5 = android.os.SystemClock.uptimeMillis()
        // Meritev glavne niti (22. 10. 2026, zastoj ~3,6 s ob prvem izrisu): zapis samo, ce je izris pocasen.
        if (t5 - t0 >= 40) android.util.Log.i("SafeerPerf", "narisiKatalog ${vsi.size} kartic (novih ${o.zapomnjene.novih}, ponovljenih ${o.zapomnjene.ponovljenih}): " +
            "vidni=${t1 - t0} preveri=${t2 - t1} stanje=${t3 - t2} kartice=${t4 - t3} pokazi=${t5 - t4} skupaj=${t5 - t0} ms | $perf")
        perf.setLength(0)
        // Pred prvo postavitvijo sirine se ne poznamo: ko je znana, mrezo narisemo se enkrat s pravo sirino plakatov.
        if (vsebina.width == 0) vsebina.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (vsebina.width <= 0) return
                vsebina.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (odprtKatalog === o && !isFinishing) narisiKatalog(o.naslov)
            }
        })
    }

    /** Zadnja izbira v razdelku Video: "movie" (privzeto) ali "series" = mreza plakatov, "" = police (Zate, na napravi, prenosi). */
    private fun videoNacin(): String = getSharedPreferences(NASTAVITVE_POGLEDA, MODE_PRIVATE).getString("video_nacin", "movie") ?: "movie"
    private fun shraniVideoNacin(n: String) = getSharedPreferences(NASTAVITVE_POGLEDA, MODE_PRIVATE).edit().putString("video_nacin", n).apply()
    /** Izbrana zvrst in jezik ostaneta, dokler je aplikacija odprta (vrnitev v Video pokaze isto mrezo). */
    private var videoZvrst = ""
    /** Jezik mreze Filmi | Serije: privzeto anglescina (lastnik, 22. 10. 2026: tako govori vecina uporabnikov, manj nalaganja);
     *  izbira uporabnika ostane shranjena ("" = vsi jeziki). */
    private var videoJezik: String
        get() = getSharedPreferences(NASTAVITVE_POGLEDA, MODE_PRIVATE).getString("video_jezik", "en") ?: "en"
        set(v) { getSharedPreferences(NASTAVITVE_POGLEDA, MODE_PRIVATE).edit().putString("video_jezik", v).apply() }
    /** Glasba: izbrani jezik besedila skladb (ISO 639-1); prazno = vsi jeziki. */
    private var glasbaJezik = ""

    private fun naloziVecKataloga(naslov: String, samodejno: Boolean = false) {
        val o = odprtKatalog ?: return
        if (o.nalagam) return
        o.nalagam = true
        // Uporabnik je prosil za vec: v strogem nacinu nalagamo, dokler ne pride vsaj ena nova kartica (poPreverjanju).
        if (!samodejno) { o.cilj = o.narisani.size + 1; o.samodejno = 0 }
        val vsi = o.vsi
        stanje.text = getString(R.string.os_glasba_nalagam)
        val zvrst = Zvrsti.poKljucu(o.zvrst)
        delavec.execute {
            // Naslednja stran vsakega kataloga (skip = kolikor je ta katalog ze dal), vse hkrati.
            // Katalog, ki je dal vsaj eno stran - in katalog, katerega dodatek prvic ni odgovoril (-1, npr. 503): poskusimo
            // ga znova od zacetka (naloga 57c; prej je za ves obisk izpadel).
            val katalogi = o.katalogi.filter { (o.preneseno[it] ?: 0) > 0 || o.preneseno[it] == -1 }
            val seznamJezika = if (o.jezikSeKaj) iskanjeDelavec.submit<Pair<List<Jamendo.Skladba>, Boolean>?> { try { naslovniJezika(o.jezik, o.tip, o.jezikOdmik) } catch (_: Exception) { null } } else null
            val strani = katalogi.map { k -> iskanjeDelavec.submit<Pair<Int, List<Jamendo.Skladba>>> { try { straniKataloga(k, zvrst, (o.preneseno[k] ?: 0).coerceAtLeast(0)).let { (n, l) -> n to vrstaIzKataloga(k, o.tip, l) } } catch (_: Exception) { 0 to emptyList() } } }
                .map { f -> try { f.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { 0 to emptyList<Jamendo.Skladba>() } }
            // Jezik za vse kataloge skupaj (isti naslov je v vec katalogih): eno vprasanje namesto dvanajstih.
            val izKatalogov = vJeziku(prepleti(strani.map { it.second }), o.jezik, jezikiKatalogov(katalogi, strani, o.jezik))
            android.util.Log.i("SafeerOsMedia", "brskanje vec: katalogov=${katalogi.size}, strani=${strani.map { it.first }}, po filtru=${izKatalogov.size}, " +
                "jezik=${o.jezik.isNotEmpty()}, zvrst=${o.zvrst.isNotEmpty()}")
            val izSeznama = try { seznamJezika?.get(30, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { null }
            val nove = izSeznama?.first.orEmpty() + izKatalogov
            glavna.post {
                o.nalagam = false
                if (isFinishing || odprtKatalog !== o) return@post
                katalogi.zip(strani).forEach { (k, v) -> o.preneseno[k] = if (v.first < 0) -1 else (o.preneseno[k] ?: 0).coerceAtLeast(0) + v.first }
                if (seznamJezika != null) {
                    // Brez odgovora (izpad) ostanemo na isti strani seznama: naslednjic poskusimo znova.
                    if (izSeznama != null) { o.jezikOdmik += IzvirniJezik.STRAN_SEZNAMA; o.jezikSeKaj = izSeznama.second }
                }
                val znani = vsi.map { it.id }.toSet()
                // Nov vnos, ki je ista vsebina kot ze prikazana kartica, se vanjo zdruzi (ni nove kartice).
                val sveze = SpletniVir.zdruziEnako(vsi + nove.filter { it.id !in znani }).filter { g -> g.none { it.id in znani } }.map { it.first() }
                vsi += sveze
                o.prazne = if (sveze.isEmpty()) o.prazne + 1 else 0
                // Filter na nasi strani (zvrst, jezik): stran brez ustreznih naslovov se ni konec - po treh praznih nehamo.
                o.seKaj = o.jezikSeKaj || (katalogi.zip(strani).any { (k, s) -> OsPravila.katalogImaVec(s.first, k.strani, STRAN_KATALOGA_MIN) } &&
                    (sveze.isNotEmpty() || ((zvrst != null || o.jezik.isNotEmpty()) && o.prazne < 3)))
                // Mreza se narise znova: ostanemo tam, kjer smo bili (ob gumbu Nalozi vec), ne na vrhu.
                val y = drsnik.scrollY
                narisiKatalog(naslov)
                drsnik.post(object : Runnable {
                    var poskusi = 0
                    override fun run() {
                        if (vsebina.height >= y + drsnik.height || poskusi++ > 30) drsnik.scrollTo(0, y) else drsnik.postDelayed(this, 50)
                    }
                })
            }
        }
    }

    /**
     * Stran kataloga za izbrano zvrst: katalog, ki zvrst ponuja kot moznost, filtrira sam (streznik); pri katalogu brez
     * zvrsti stran filtriramo tu po metapodatkih. Vrne (koliko je dal katalog - za `skip`, kaj od tega ustreza).
     */
    /**
     * Naloga 57c: iz splosnega video kataloga (tip ni [tip]) gredo v mrezo Filmi | Serije samo filmi oz. serije - po
     * vrsti vsebine ([SpletniVir.vrstaVsebine]), kot v razdelku Video. Katalog tipa [tip] ostane, kot je.
     */
    private fun vrstaIzKataloga(k: Stremio.Katalog, tip: String, l: List<Jamendo.Skladba>): List<Jamendo.Skladba> {
        if (tip.isBlank()) return l
        val vrsta = if (tip == "movie") SpletniVir.FILM else SpletniVir.SERIJA
        // Katalog prave vrste: izlocimo samo naslove, ki so ZAGOTOVO druge vrste (lastnik 22. 10.: serija Lucifer med Filmi);
        // neznana vrsta ostane. Splosen katalog: samo naslovi prave vrste.
        if (k.tip == tip) return l.filter { SpletniVir.vrstaVsebine(it).let { v -> v == null || v == vrsta } }
        return l.filter { SpletniVir.vrstaVsebine(it) == vrsta }
    }

    private fun straniKataloga(k: Stremio.Katalog, zvrst: Zvrsti.Zvrst?, skip: Int = 0): Pair<Int, List<Jamendo.Skladba>> {
        val moznost = zvrst?.let { Zvrsti.moznost(it, k.zvrsti) }.orEmpty()
        // Izpad (dodatek ni odgovoril, 503 ...) je -1: ni konec kataloga, naslednji »Nalozi vec« ga vprasa znova (naloga 57c).
        val stran = Stremio.katalogAliIzpad(k, skip = skip, zvrst = moznost) ?: return -1 to emptyList()
        return stran.size to (if (zvrst == null || moznost.isNotEmpty()) stran else stran.filter { zvrst.ustreza(it) })
    }

    /**
     * Kartice v izbranem jeziku. Jezik, ki ga vir pove sam (PeerTube, javna last, spletni viri), velja; pri naslovih z
     * id-jem IMDb velja izvirni jezik naslova ([IzvirniJeziki], Wikidata). Naslov z neznanim jezikom izpade - izbira
     * »angleščina« ne sme kazati filmov, za katere ne vemo, v katerem jeziku so. Klic iz ozadja (omrezje).
     * [katalogov]: naslov brez jezika in IMDb (zapisi m3u) -> jezik njegovega kataloga ([jezikiKatalogov], naloga 60).
     */
    private fun vJeziku(kartice: List<Jamendo.Skladba>, jezik: String,
                        katalogov: Map<String, String> = emptyMap()): List<Jamendo.Skladba> {
        if (jezik.isEmpty() || kartice.isEmpty()) return kartice
        val vprasaj = kartice.filter { it.language.isBlank() && it.imdbId.isNotBlank() }.map { it.imdbId }
        val znani = if (vprasaj.isEmpty()) emptyMap() else try { IzvirniJeziki.jeziki(applicationContext, vprasaj) } catch (_: Exception) { emptyMap() }
        // Neznan jezik (zapisi m3u ipd. brez jezika in IMDb) ostane viden: filter skrije samo naslove, za katere VEMO, da so v
        // drugem jeziku (lastnik, 22. 10. 2026: katalog s 5000 filmi je zaradi filtra dal 102 kartici). Naslov brez jezika in
        // IMDb dobi jezik svojega kataloga, ce je katalog odlocen (naloga 60: brazilski katalog pri izbrani anglescini).
        return kartice.filter { s ->
            when {
                s.language.isNotBlank() -> JezikiVsebine.oznaka(s.language) == jezik
                !znani[s.imdbId].isNullOrEmpty() -> jezik in znani[s.imdbId].orEmpty()
                // Naloga 66: jezika ne vemo, drzave izvora pa (Cinemeta: korejski »Hope« med »English«). Drzava, ki ne
                // ustreza, naslov skrije; neznana ali brez drzave ostane viden kot do zdaj.
                // Drzave naslova so natancnejse od jezika kataloga: Cinemeta »top« velja po naslovih za angleski katalog,
                // »Hope« (Juzna Koreja) pa je v njem - izmerjeno na TV 23. 10. 2026. Zato najprej drzave, nato katalog.
                else -> VrsteNaslovov.drzave(s)?.let { OsPravila.jezikPoDrzavah(it, jezik) }
                    ?: katalogov[s.id]?.let { OsPravila.jezikKatalogaUstreza(it, jezik) } ?: true
            }
        }
    }

    /**
     * Naloga 60: id kartice brez jezika in IMDb -> ocenjen jezik njenega kataloga ([Stremio.Katalog.oceniJezik], vzorec
     * naslovov strani). Neodlocen katalog ne da nicesar (kartice ostanejo vidne). Brez izbranega jezika nic. Klic iz
     * delovne niti (ob branju strani kataloga, ne na glavni niti).
     */
    private fun jezikiKatalogov(katalogi: List<Stremio.Katalog>, strani: List<Pair<Int, List<Jamendo.Skladba>>>,
                                jezik: String): Map<String, String> {
        if (jezik.isEmpty()) return emptyMap()
        val izid = HashMap<String, String>()
        katalogi.zip(strani).forEach { (k, s) ->
            val jezikKataloga = k.oceniJezik(s.second.map { it.naslov })
            if (jezikKataloga.isNotEmpty()) for (x in s.second) if (x.language.isBlank() && x.imdbId.isBlank()) izid.putIfAbsent(x.id, jezikKataloga)
        }
        return izid
    }

    /** Stran naslovov v izbranem jeziku po prepoznavnosti (Wikidata): kartice in ali je se kaj; null = brez odgovora. */
    private fun naslovniJezika(jezik: String, tip: String, odmik: Int): Pair<List<Jamendo.Skladba>, Boolean>? =
        IzvirniJeziki.seznam(applicationContext, jezik, tip, odmik)?.let { s -> s.naslovi.map { Stremio.vnosPoImdb(tip, it.imdb, it.naslov, it.leto, jezik) } to s.seKaj }

    /** Jeziki za izbiro: jezik vmesnika in angleščina na vrhu, ostali po abecedi; imena v jeziku vmesnika. */
    private fun jezikiIzbire(kode: List<String>): List<Pair<String, String>> {
        val vmesnik = resources.configuration.locales[0]
        fun ime(k: String) = Locale.forLanguageTag(k).getDisplayLanguage(vmesnik).replaceFirstChar { it.titlecase(vmesnik) }
        val prvi = listOf(vmesnik.language, "en").distinct().filter { it in kode }
        return (prvi + kode.filter { it !in prvi }.sortedWith(compareBy(Collator.getInstance(vmesnik)) { ime(it) })).map { it to ime(it) }
    }

    // ------------------------------------------------------------------ Filmi | Serije: mreza plakatov z zvrstmi

    /**
     * Filmi ali Serije kot mreza plakatov cez VSE vire (dodatki, spletni viri, javna last ...), z izbiro zvrsti in
     * razvrstitvijo - kot na racunalniku in kot uporabniki poznajo iz drugih predvajalnikov (lastnik, 2. 10. 2026).
     * Uporabnik ne izbira vira: isti film iz vec virov je ena kartica.
     */
    private fun odpriBrskanje(tip: String, zvrstKljuc: String = "", jezik: String = "") {
        val naslov = getString(if (tip == "movie") R.string.os_media_filmi else R.string.os_media_serije)
        // Zvrst »Zasebni«: samo katalogi zasebnih dodatkov. Ne pusti sledi (ZasebniDodatki): brez predpomnilnika, izbira
        // se ne zapomni (vrnitev v Video pokaze obicajno mrezo), jezik ne velja (zasebni katalog jezika ne pove).
        val zasebni = zvrstKljuc == ZVRST_ZASEBNI
        val o = OdprtKatalog(emptyList(), mutableListOf(), HashMap(), false, tip, zvrstKljuc, naslov, nalagam = true, jezik = if (zasebni) "" else jezik)
        odprtKatalog = o
        odprtKatalogIz = VIDEO
        // Nic cakanja: zadnja znana mreza (pomnilnik, sicer disk) je na zaslonu takoj; sveza vsebina jo zamenja le, ce je drugacna.
        val kljuc = "brskanje:$tip:$zvrstKljuc:${resources.configuration.locales[0].toLanguageTag()}" + (if (jezik.isEmpty()) "" else ":j$jezik")
        if (!zasebni) BRSKANJE[kljuc]?.let { o.vsi += it }
        drsnik.scrollTo(0, 0)
        narisiKatalog(naslov)
        val zvrst = Zvrsti.poKljucu(zvrstKljuc)
        val vrsta = if (tip == "movie") SpletniVir.FILM else SpletniVir.SERIJA
        videoZvrst = if (zasebni) "" else zvrstKljuc
        videoJezik = jezik
        if (zasebni) { delavec.execute { odpriZasebne(o, tip, naslov) }; return }
        // Naloga 62: mreza z diska iz casa pred spremembo virov ima kartice izbrisanega vira - ne pokazemo je; mreza, ki se
        // nalaga med spremembo, se ne zapise (ne v BRSKANJE ne na disk).
        val rodVirov = MedijskiViri.spremenjeni(this)
        delavec.execute {
            if (o.vsi.isEmpty()) {
                val zDiska = try { MedijskiPredpomnilnik.beriPoliceSCasom(this, kljuc)?.takeIf { it.first >= rodVirov }?.second?.firstOrNull()?.third } catch (_: Exception) { null }
                if (!zDiska.isNullOrEmpty()) glavna.post {
                    if (!isFinishing && odprtKatalog === o && o.vsi.isEmpty()) { o.vsi += zDiska; narisiKatalog(naslov) }
                }
            }
            // 1) Katalogi dodatkov te vrste (hitro, vsi hkrati); ob izbrani zvrsti brez katalogov, ki zvrsti ne poznajo in zahtevajo kaj drugega (npr. leto).
            val dodatki = MedijskiViri.vsi(this).filter { it.jeStremio && jeVirViden(VIDEO, kljucVira(it)) }.map { it.naslov }
            val katalogi = (if (dodatki.isEmpty()) emptyList() else try { Stremio.prikazniKatalogi(Stremio.zKatalogom(dodatki)) } catch (_: Exception) { emptyList() })
                // Naloga 57c: tudi splosni video katalogi uporabnikovih dodatkov (tip ni »movie«/»series«, npr. kanali, zbirke) -
                // iz njih gredo v mrezo filmi oz. serije (po vrsti vsebine, kot v razdelku Video). Do 57 je mrezo polnila Cinemeta.
                .filter { (it.tip == tip || Stremio.razredKataloga(it) == Stremio.VIDEO) && (zvrst == null || Zvrsti.moznost(zvrst, it.zvrsti) != null || "genre" !in it.obvezni) }
                .sortedBy { if (it.tip == tip) 0 else 1 }
                // Naloga 143: vsak dodatek dobi svoj delez od 12 katalogov - prej je Cinemeta (dodana zadnja) izpadla.
                .let { OsPravila.pravicnoPoDodatkih(it, { k -> k.dodatek }, 12) }
            // Izbran jezik: naslovi v tem jeziku po prepoznavnosti (Wikidata) - splosni katalogi jih na prvih straneh skoraj
            // nimajo. Anglescine je v katalogih dovolj; ob izbrani zvrsti ostanejo katalogi (seznam zvrsti ne pozna).
            // Seznam naslovov jezika gre skozi katalog Cinemeta (Stremio.vnosPoImdb): samo, ce ga ima uporabnik (naloga 57).
            val seznamJezika = if (jezik.isNotEmpty() && jezik != "en" && zvrst == null && (MedijskiViri.VGRAJENI_SPLETNI_VIRI || dodatki.any { Stremio.jeCinemeta(it) }))
                iskanjeDelavec.submit<Pair<List<Jamendo.Skladba>, Boolean>?> { try { naslovniJezika(jezik, tip, 0) } catch (_: Exception) { null } } else null
            // Filmi v javni lasti v izbranem jeziku in zvrsti: izbere jih ze Internet Archive (vecina nima jezika ali zvrsti v
            // podatkih, zato jih filter na nasi strani izloci - prazna mreza pri »Komedija · Anglescina«, lastnik 10. 10. 2026).
            val zacetek = android.os.SystemClock.uptimeMillis()
            val javnaIzbrana = if (tip == "movie" && (jezik.isNotEmpty() || zvrst != null) && jeVirViden(VIDEO, VIR_JAVNA_LAST))
                iskanjeDelavec.submit<List<Jamendo.Skladba>> {
                    val r = try { JavnaLast.isci("", jezik, zvrst?.katalog.orEmpty()) } catch (_: Exception) { emptyList() }
                    android.util.Log.i("SafeerOsCas", "brskanje javna last: ${r.size} kartic v ${android.os.SystemClock.uptimeMillis() - zacetek} ms")
                    // Na zaslon takoj, ko pridejo (~1-2 s) - ne cakamo na pocasne kataloge dodatkov (do 20 s).
                    if (r.isNotEmpty()) glavna.post {
                        if (isFinishing || odprtKatalog !== o) return@post
                        val znani = o.vsi.mapTo(HashSet()) { it.id }
                        val nove = r.filter { it.id !in znani }
                        if (nove.isNotEmpty()) { o.vsi += nove; narisiKatalog(naslov) }
                    }
                    r
                } else null
            val strani = katalogi.map { k -> iskanjeDelavec.submit<Pair<Int, List<Jamendo.Skladba>>> { try { straniKataloga(k, zvrst).let { (n, l) -> n to vrstaIzKataloga(k, tip, l) } } catch (_: Exception) { 0 to emptyList() } } }
                .map { f -> try { f.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { 0 to emptyList<Jamendo.Skladba>() } }
            // Jezik za vse kataloge skupaj (isti naslov je v vec katalogih): eno vprasanje namesto dvanajstih.
            val vJezikuKatalogov = vJeziku(prepleti(strani.map { it.second }), jezik, jezikiKatalogov(katalogi, strani, jezik))
            // Na seznam jezika cakamo kratko: pocasen odgovor (velik jezik prvic) ne sme zadrzati mreze - pride z »Naloži več«.
            val izSeznama = try { seznamJezika?.get(8, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { null }
            val izJavneLasti = try { javnaIzbrana?.get(10, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { null }
            val izKatalogov = izSeznama?.first.orEmpty() + vJezikuKatalogov + izJavneLasti.orEmpty()
            android.util.Log.i("SafeerOsCas", "brskanje katalogi: ${katalogi.size} katalogov, ${izKatalogov.size} kartic v ${android.os.SystemClock.uptimeMillis() - zacetek} ms")
            // 2) Kar razdelek Video pozna iz ostalih virov (splet, PeerTube, javna last ...). Ce to se ni nalozeno, mreza
            //    ne caka: katalogi so na zaslonu takoj, ostalo se doda, ko pride.
            fun osnova(police: List<Podatki>, vse: List<Jamendo.Skladba>?) = vJeziku((police.flatMap { it.skladbe } + vse.orEmpty()).distinctBy { it.id }
                .filter { SpletniVir.vrstaVsebine(it) == vrsta && (zvrst == null || zvrst.ustreza(it)) }, jezik)
            val znane = SEZNAMI[VIDEO] ?: prikazanePolice[VIDEO]
            // Police s predpomnilnika na disku (ali po spremembi virov) so okrnjene ali stare: vse kartice razdelka dobimo
            // v drugem koraku (podatkiRazdelka).
            val popolne = SEZNAMI[VIDEO] != null && VSE_VIDEO != null
            // Najprej katalogi (urejeni po priljubljenosti), nato ostalo; ista vsebina je ena kartica.
            fun zdruzi(dodatno: List<Jamendo.Skladba>) = SpletniVir.zdruziEnako(izKatalogov + dodatno).map { it.first() }
            fun shrani(vsi: List<Jamendo.Skladba>) {
                // Prazen odgovor (brez omrezja) ne povozi zadnje znane mreze.
                if (vsi.isEmpty() || MedijskiViri.spremenjeni(this) != rodVirov) return
                BRSKANJE[kljuc] = vsi
                try { MedijskiPredpomnilnik.shraniPolice(this, kljuc, listOf(Triple(naslov, true, vsi))) } catch (_: Exception) { }
            }
            val vsi = zdruzi(znane?.let { osnova(it, if (popolne) VSE_VIDEO else null) }.orEmpty())
            // Naloga 57c: kje se naslovi izgubijo (TV 22. 10.: katalogi 5310 kartic, mreza 102) - po korakih.
            android.util.Log.i("SafeerOsMedia", "brskanje mreza: katalogov=${katalogi.size} (${katalogi.count { it.tip == tip }} tipa $tip), " +
                "strani=${strani.map { it.first }}, iz katalogov=${izKatalogov.size}, popolne=$popolne, skupaj=${vsi.size}, jezik=${jezik.isNotEmpty()}, zvrst=${zvrst != null}")
            if (popolne) shrani(vsi)
            glavna.post {
                if (isFinishing || odprtKatalog !== o) return@post
                o.katalogi = katalogi
                katalogi.zip(strani).forEach { (k, v) -> o.preneseno[k] = v.first }
                // Seznam jezika: brez odgovora (izpad) ga »Naloži več« vpraša znova od zacetka.
                o.jezikOdmik = if (izSeznama != null) IzvirniJezik.STRAN_SEZNAMA else 0
                o.jezikSeKaj = seznamJezika != null && (izSeznama == null || izSeznama.second)
                val seKaj = katalogi.zip(strani).any { (k, s) -> OsPravila.katalogImaVec(s.first, k.strani, STRAN_KATALOGA_MIN) } || o.jezikSeKaj
                // Dokler ne poznamo vseh kartic razdelka (drugi korak), ostane na zaslonu tudi, kar je mreza pokazala iz
                // predpomnilnika (filmi javne lasti ...): sicer bi izginilo in se cez nekaj sekund spet pojavilo.
                val nove = if (popolne) {
                    // Kartica, ki jo uporabnik ze vidi, ne izgine samo zato, ker je sveza lestvica nima vec (predvajati se se da).
                    val imajo = vsi.mapTo(HashSet()) { it.id }; val naZaslonu = o.narisani.toSet()
                    vsi + o.vsi.filter { it.id in naZaslonu && it.id !in imajo }
                } else { val znani = vsi.map { it.id }.toSet(); vsi + o.vsi.filter { it.id !in znani } }
                val enako = nove.isEmpty() || o.vsi.map { it.id } == nove.map { it.id }
                val prej = o.seKaj
                if (!enako) { o.vsi.clear(); o.vsi += nove }
                o.seKaj = seKaj
                o.popolna = popolne
                // Brez katalogov in brez znanih polic: se cakamo na ostale vire (drugi korak).
                o.nalagam = !popolne && o.vsi.isEmpty()
                // Enaka vsebina kot v predpomnilniku: zaslona ne risemo znova (le gumb Nalozi vec, ce ga se ni).
                if (enako && prej == seKaj && o.vsi.isNotEmpty()) { narociUmiri(); return@post }
                val y = drsnik.scrollY
                narisiKatalog(naslov)
                if (y > 0) drsnik.post { drsnik.scrollTo(0, y) }
            }
            if (popolne) return@execute
            val police = try { podatkiRazdelka(VIDEO).map { it.copy(skladbe = razvrsti(VIDEO, it.skladbe)) } } catch (_: Exception) { emptyList() }
            if (police.isNotEmpty() && police.all { it.skladbe.isNotEmpty() } && MedijskiViri.spremenjeni(this) == rodVirov) SEZNAMI[VIDEO] = police
            val dodatno = osnova(police, VSE_VIDEO)
            shrani(zdruzi(dodatno))
            glavna.post {
                if (isFinishing || odprtKatalog !== o) return@post
                o.nalagam = false
                o.popolna = true
                narociUmiri()
                // Dodamo samo, cesar mreza se nima (uporabnik je medtem morda ze nalozil naslednje strani).
                val zdruzeni = if (dodatno.isEmpty()) o.vsi else SpletniVir.zdruziEnako(o.vsi + dodatno).map { it.first() }
                if (zdruzeni.size == o.vsi.size && o.vsi.isNotEmpty()) return@post
                if (zdruzeni.size != o.vsi.size) { val kopija = zdruzeni.toList(); o.vsi.clear(); o.vsi += kopija }
                val y = drsnik.scrollY
                narisiKatalog(naslov)
                if (y > 0) drsnik.post { drsnik.scrollTo(0, y) }
            }
        }
    }

    /**
     * Mreza zvrsti »Zasebni«: prve strani katalogov zasebnih dodatkov te vrste (Filmi: vse, kar niso serije). Naprej
     * z »Naloži več« kot pri vsaki mrezi. Klic iz delovne niti.
     */
    private fun odpriZasebne(o: OdprtKatalog, tip: String, naslov: String) {
        val dodatki = MedijskiViri.vsi(this).filter { it.jeStremio }.map { it.naslov }
            .filter { (try { Stremio.zaseben(it) } catch (_: Exception) { null }) == true }
        val katalogi = dodatki.flatMap { n -> try { Stremio.katalogiDodatka(n) } catch (_: Exception) { emptyList() } }
            .filter { if (tip == "series") it.tip == "series" else it.tip != "series" }
            .let { OsPravila.pravicnoPoDodatkih(it, { k -> k.dodatek }, 12) }
        val strani = katalogi.map { k -> iskanjeDelavec.submit<Pair<Int, List<Jamendo.Skladba>>> { try { straniKataloga(k, null) } catch (_: Exception) { 0 to emptyList() } } }
            .map { f -> try { f.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { 0 to emptyList<Jamendo.Skladba>() } }
        val vsi = SpletniVir.zdruziEnako(prepleti(strani.map { it.second })).map { it.first() }
        glavna.post {
            if (isFinishing || odprtKatalog !== o) return@post
            o.katalogi = katalogi
            katalogi.zip(strani).forEach { (k, v) -> o.preneseno[k] = v.first }
            o.vsi.clear(); o.vsi += vsi
            o.seKaj = katalogi.zip(strani).any { (k, s) -> OsPravila.katalogImaVec(s.first, k.strani, STRAN_KATALOGA_MIN) }
            o.popolna = true
            o.nalagam = false
            narisiKatalog(naslov)
        }
    }

    /**
     * Daljinec (televizor): izbira Filmi | Serije | Zate je v pogledu Zate ozka in levo, zato jo je iskanje fokusa
     * preskocilo - dosegljiva je bila samo iz razdelka Glasba (najdeno 3. 10. 2026 v zivo). Zdaj pelje vanjo DOL iz
     * vsakega razdelka in GOR iz vrstice pod njo.
     */
    private fun poveziIzbiro(razdelki: View, izbire: View, pod: View) {
        fun prva(v: View): View? = if (v.isFocusable && v.isClickable) v
            else (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { prva(g.getChildAt(it)) } }
        val izbira = prva(izbire) ?: return
        if (izbira.id == View.NO_ID) izbira.id = View.generateViewId()
        if (pod.id == View.NO_ID) pod.id = View.generateViewId()
        pod.nextFocusUpId = izbira.id
        izbira.nextFocusDownId = pod.id
        fun vsi(v: View) {
            if (v.isFocusable && v.isClickable) v.nextFocusDownId = izbira.id
            (v as? android.view.ViewGroup)?.let { g -> for (k in 0 until g.childCount) vsi(g.getChildAt(k)) }
        }
        vsi(razdelki)
    }

    /** Glava mreze Filmi | Serije: (v Medijskem centru se razdelki) in stiri izbire - vrsta, razvrstitev, zvrst, jezik. */
    private fun glavaBrskanja(o: OdprtKatalog): List<View> =
        (if (vlc) emptyList() else listOf(razdelkiVrstica(VIDEO))) + listOf(videoIzbire(o))

    /**
     * Izbire razdelka Video v eni vrstici, kot Discover v drugih predvajalnikih (lastnik, 2. 10. 2026):
     * [Filmi ▾] [Priporoceno ▾] [Vse zvrsti ▾] [Vsi jeziki ▾] [⚡ TorBox ▾] (naloga 130). Na telefonu sta vrstici dve;
     * na sirsih zaslonih je pet enako sirokih cez razpolozljivo sirino (najvec 200 dp vsaka),
     * da so na televizorju in tablici vse na zaslonu brez drsenja. Pri policah (Zate) je samo prva, da se uporabnik
     * vrne v mrezo.
     */
    private fun videoIzbire(o: OdprtKatalog?): View {
        val ozek = ozekZaslon()
        val niz = (if (!ozek && o != null) object : LinearLayout(this) {
            override fun onMeasure(sirina: Int, visina: Int) {
                val najvec = dp(5 * 200 + 4 * 8)
                val omejena = View.MeasureSpec.getMode(sirina) != View.MeasureSpec.UNSPECIFIED && View.MeasureSpec.getSize(sirina) > najvec
                super.onMeasure(if (omejena) View.MeasureSpec.makeMeasureSpec(najvec, View.MeasureSpec.EXACTLY) else sirina, visina)
            }
        } else LinearLayout(this)).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(12)) }
        // Telefon: stiri izbire v eni vrstici bi bile preozke za napis - druga vrstica (zvrst, jezik) pride pod prvo.
        val druga = if (ozek && o != null) LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(12)) } else null
        fun izbira(kljuc: String, moznosti: List<String>, izbrana: Int, naslovOkna: String?, cilj: LinearLayout = niz,
                   /** Napis namesto izbrane moznosti (izbrana = -1: nic ni izbrano). */
                   napis: String? = null, obIzbiri: (Int) -> Unit) {
            val prva = cilj.childCount == 0
            cilj.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                tag = "k:$kljuc"; isFocusable = true; isClickable = true
                // Naloga 135: stalna oznaka izbire (bralnik zaslona, uiautomator) - napis se spreminja z izbiro.
                contentDescription = listOfNotNull(naslovOkna, napis ?: moznosti.getOrNull(izbrana)).joinToString(": ")
                setPadding(dp(if (ozek) 10 else 12), dp(10), dp(if (ozek) 8 else 10), dp(10))
                fun oblika(rob: Int, debelina: Int, polnilo: Int) = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(polnilo); setStroke(dp(debelina), rob) }
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), oblika(osBarva(R.color.os_mint), 2, (osBarva(R.color.os_mint) and 0x00FFFFFF) or 0x33000000))
                    addState(intArrayOf(android.R.attr.state_pressed), oblika(0x66FFFFFF, 1, 0x26FFFFFF))
                    addState(intArrayOf(), oblika(0x40FFFFFF, 1, 0x14FFFFFF))
                }
                addView(besedilo(15f, osBarva(R.color.os_besedilo), true).apply {
                    text = napis ?: moznosti.getOrElse(izbrana) { moznosti.first() }; maxLines = 1; gravity = Gravity.CENTER_VERTICAL
                    // Daljsi napis (Priporoceno, Znanstvena fantastika) se na ozkem telefonu pomanjsa, ne odreze.
                    ellipsize = null
                    setAutoSizeTextTypeUniformWithConfiguration(8, if (ozek) 14 else 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                }, LinearLayout.LayoutParams(0, dp(24), 1f))
                addView(besedilo(12f, osBarva(R.color.os_umirjeno)).apply { text = "▾"; setPadding(dp(if (ozek) 2 else 4), 0, 0, 0) })
                if (prva) nextFocusLeftId = meniMediji.id
                setOnClickListener {
                    AlertDialog.Builder(this@GlasbaActivity).apply { naslovOkna?.let { setTitle(it) } }
                        .setSingleChoiceItems(moznosti.toTypedArray(), izbrana) { d, i -> d.dismiss(); if (i != izbrana) obIzbiri(i) }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            // Mreza: izbire so enako siroke in si razdelijo vrstico; sama prva (police Zate) ima svojo sirino.
            }, LinearLayout.LayoutParams(if (o != null) 0 else dp(if (ozek) 150 else 200), -2, if (o != null) 1f else 0f).apply { if (!prva) marginStart = dp(8) })
        }
        // 1) Kaj: Filmi | Serije (mreza) | Zate (police: nadaljuj, na napravi, prenosi, priporocila).
        val nacini = listOf("movie", "series", "")
        izbira("nacin", listOf(getString(R.string.os_media_filmi), getString(R.string.os_media_serije), getString(R.string.os_media_zate)),
            nacini.indexOf(o?.tip.orEmpty()), null) { i ->
            shraniVideoNacin(nacini[i])
            fokusVVsebino = false
            if (nacini[i].isEmpty()) { odprtKatalog = null; izberi(VIDEO) } else odpriBrskanje(nacini[i], o?.zvrst ?: videoZvrst, o?.jezik ?: videoJezik)
        }
        if (o != null) {
            // 2) Razvrstitev.
            val razvrstitveMreze = listOf(RAZVRSTI_PRIPOROCENO to R.string.os_media_razvrsti_priporoceno,
                RAZVRSTI_NAJNOVEJSE to R.string.os_media_razvrsti_najnovejse, RAZVRSTI_IME_AZ to R.string.os_media_razvrsti_ime)
            izbira("razvrsti", razvrstitveMreze.map { getString(it.second) }, razvrstitveMreze.indexOfFirst { it.first == razvrstitev(VIDEO) }.coerceAtLeast(0),
                getString(R.string.os_media_razvrsti)) { i ->
                razvrstitve[VIDEO] = razvrstitveMreze[i].first
                SEZNAMI.remove(VIDEO)   // police (Zate) se uredijo enako
                o.narisani = emptyList()   // nova razvrstitev: vrstni red na zaslonu ne velja vec
                narisiKatalog(o.naslov)
            }
            // 3) Zvrst; na koncu »Zasebni«, ce ima uporabnik zaseben dodatek (njegovi katalogi se med ostale ne mesajo).
            val imaZasebne = MedijskiViri.vsi(this).any { it.jeStremio && Stremio.zasebenZnano(it.naslov) == true }
            val zvrsti = listOf(getString(R.string.os_zvrst_vse)) + Zvrsti.VSE.map { getString(it.ime) } +
                (if (imaZasebne) listOf(getString(R.string.os_zvrst_zasebni)) else emptyList())
            izbira("zvrst", zvrsti, if (o.zvrst == ZVRST_ZASEBNI && imaZasebne) zvrsti.size - 1 else Zvrsti.VSE.indexOfFirst { it.kljuc == o.zvrst } + 1,
                null, druga ?: niz) { i ->
                odpriBrskanje(o.tip, when { i == 0 -> ""; i > Zvrsti.VSE.size -> ZVRST_ZASEBNI; else -> Zvrsti.VSE[i - 1].kljuc }, o.jezik)
            }
            // 4) Jezik vsebine (izvirni jezik naslova).
            val jeziki = jezikiIzbire(IzvirniJezik.KODE)
            izbira("jezik", listOf(getString(R.string.os_jezik_vsi)) + jeziki.map { it.second },
                jeziki.indexOfFirst { it.first == o.jezik } + 1, getString(R.string.os_jezik_vsebine), druga ?: niz) { i ->
                odpriBrskanje(o.tip, o.zvrst, if (i == 0) "" else jeziki[i - 1].first)
            }
            // 5) Naloga 130: prek katere debrid storitve gredo torrenti; privzeto tista, za katero je vpisan kljuc.
            val sKljucem = Debrid.vrstniRed(this)
            val aktivna = Debrid.aktivna(this)
            // Lastnik 27. 10. 2026: tudi lasten ponudnik Usenet je izbira (filmi in serije neposredno prek NNTP) in
            // »All sources« - Usenet, vse debrid storitve s kljucem (prednostna najprej) in ostali viri (povezave, dodatki, Webtor).
            val usenet = UsenetLastni.naVoljo(this)
            val iUsenet = if (usenet) sKljucem.size else -1
            val iVse = if (usenet && sKljucem.isNotEmpty()) sKljucem.size + 1 else -1
            val iBrez = sKljucem.size + (if (usenet) 1 else 0) + (if (iVse >= 0) 1 else 0)
            // Brez kljuca in ponudnika: napis »Debrid: vpisi kljuc«, izbira odpre okno »Debrid storitve«.
            val moznosti = sKljucem.map { DebridPravila.OZNAKA_PREDPOMNJEN + " " + DebridPravila.ime(it) } +
                (if (usenet) listOf(DebridPravila.OZNAKA_PREDPOMNJEN + " Usenet") else emptyList()) +
                (if (iVse >= 0) listOf(DebridPravila.OZNAKA_PREDPOMNJEN + " " + getString(R.string.os_debrid_izbira_vse)) else emptyList()) +
                (if (sKljucem.isEmpty()) emptyList() else listOf(getString(R.string.os_debrid_izbira_brez))) +
                getString(R.string.os_debrid_izbira_nastavitve)
            izbira("debrid", moznosti, when {
                    usenet && UsenetLastni.izbran(this) -> if (aktivna != null && iVse >= 0) iVse else iUsenet
                    sKljucem.isEmpty() -> -1
                    aktivna != null -> sKljucem.indexOf(aktivna)
                    else -> iBrez },
                getString(R.string.os_debrid_izbira_naslov), druga ?: niz,
                if (sKljucem.isEmpty() && !usenet) getString(R.string.os_debrid_izbira_dodaj) else null) { i ->
                when {
                    i < sKljucem.size -> { Debrid.nastaviAktivno(this, sKljucem[i]); UsenetLastni.nastaviIzbran(this, false) }
                    i == iUsenet -> { UsenetLastni.nastaviIzbran(this, true); if (sKljucem.isNotEmpty()) Debrid.nastaviAktivno(this, null) }
                    i == iVse -> { UsenetLastni.nastaviIzbran(this, true); Debrid.nastaviAktivno(this, aktivna ?: sKljucem.first()) }
                    i == iBrez && sKljucem.isNotEmpty() -> { Debrid.nastaviAktivno(this, null); UsenetLastni.nastaviIzbran(this, false) }
                    else -> { nastavitveDebrida(); return@izbira }
                }
                odpriBrskanje(o.tip, o.zvrst, o.jezik)
            }
        }
        // Vrstici sta natanko siroki kot vsebina (enake izbire, nic ne gleda cez rob); sama prva izbira: naravna sirina.
        if (druga != null) {
            niz.setPadding(0, dp(6), 0, dp(8))
            return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(niz); addView(druga) }
        }
        if (ozek || o != null) return niz
        return HorizontalScrollView(this).apply { addView(niz, android.view.ViewGroup.LayoutParams(-2, -2)); isHorizontalScrollBarEnabled = false; clipToPadding = false }
    }

    /** Krajevne vrste na vrhu razdelka: nedavno, tvoji viri, priljubljene in seznami, ki sodijo vanj. */
    /** Krajevni videi/glasba za zavihka Video in Glasba (VLC slog); berejo se loceno od spleta. */
    private val krajevno = HashMap<Int, List<Jamendo.Skladba>>()

    private fun naloziKrajevno(i: Int) {
        if (!vlc || (i != VIDEO && i != GLASBA) || i in samoTaNaprava) return
        // Lastna nit: delavec ima v vrsti nalaganje slik in spleta - krajevno mora biti takoj.
        Thread {
            val l = try { krajevnePolice(i).flatMap { it.skladbe } } catch (_: Throwable) { emptyList() }
            glavna.post {
                if (isFinishing || krajevno[i] == l) return@post
                krajevno[i] = l
                // Odprta mreza (Filmi | Serije) ali podstran ostane; krajevni videi so blok strani »Zate«.
                if (razdelek != i || odprtKatalog != null || podstrani.odprta) return@post
                // Glasba je mreza z izbirami: glasba naprave je v njej izbira »Na tej napravi«.
                if (i == GLASBA) { if (glasbaSkupina() == SKUPINA_NAPRAVA) odpriMrezo(GLASBA); return@post }
                val podatki = prikazanePolice[i] ?: SEZNAMI[i]
                if (podatki != null) prikazi(i, podatki)
                else narisi(zgoraj(i), getString(R.string.os_glasba_nalagam), getString(R.string.os_glasba_nalagam), glavaRazdelka(i))
            }
        }.apply { name = "safeer-krajevno"; isDaemon = true }.start()
    }

    private fun zgoraj(i: Int): List<Vrsta> {
        // Krajevni videi (samostojni Predvajalnik) so blok strani »Zate«; krajevna glasba je izbira »Na tej napravi« v mrezi Glasbe.
        val lokalne = if (vlc && i == VIDEO && i !in samoTaNaprava) krajevno[i].orEmpty() else emptyList()
        val vrsta = if (lokalne.isEmpty()) emptyList() else listOf(Vrsta(getString(R.string.os_krajevno_koren), videi(lokalne), video = true))
        return vrsta + zgorajSplet(i)
    }

    private fun zgorajSplet(i: Int): List<Vrsta> {
        if (i in samoTaNaprava) return emptyList()
        val p = razvrsti(i, filtrirajJezike(i, MedijskiViri.priljubljene(this)))
        // Seznami, ki imajo v izbranih jezikih kaj pokazati.
        val seznami = MedijskiViri.seznami(this).filter { filtrirajJezike(i, it.skladbe).isNotEmpty() }
        // Seznami predvajanja: en blok s kartico na seznam (kot v Brskaj); dotik odpre seznam kot mrezo. Prej je imel vsak
        // seznam svojo polico, po kateri je bilo treba drseti v desno.
        fun seznamiBlok(l: List<MedijskiViri.Seznam>) = if (l.isEmpty()) emptyList()
            else listOf(Vrsta(getString(R.string.os_seznami_predvajanja), l.map { karticaSeznama(it) }, skladbe = true))
        return when (i) {
            DOMOV -> predajaVrsta() + listOf(
                Vrsta(getString(R.string.os_media_nedavno), razvrsti(i, filtrirajJezike(i, MedijskiViri.nedavno(this).filterNot { neodklenjena(it) }))
                    // Isti naslov iz dveh virov (dve razlicici) je med nedavnimi en sam - brez podvojenih prikazov.
                    .distinctBy { kljucNedavnega(it) }.take(5).let { n ->
                    val kartice = n.map { sk ->
                        // Vgrajeni kanal nima slike v podatkih: njegov logotip je shranjen posebej (kot v mrezi kanalov).
                        val tv = sk.id.startsWith("tv:")
                        Kartica(sk.naslov, sk.izvajalec, sk.slika, { predvajaj(listOf(sk), 0, izNedavnih = true) }, { meniNedavno(sk) },
                            ikona = if (tv) R.drawable.os_ikona_tv else if (sk.video) R.drawable.os_ikona_video else if (sk.radio) R.drawable.os_ikona_radio else R.drawable.os_ikona_glasba,
                            tvId = if (tv && sk.slika.isBlank()) sk.id.removePrefix("tv:") else "", id = sk.id)
                    }
                    if (n.isEmpty()) kartice else kartice + Kartica(getString(R.string.os_media_pocisti_nedavno), getString(R.string.os_media_pocisti_nedavno_opis), "", { potrdiPocistiNedavno() }, ikona = R.drawable.os_ikona_ustavi, dejanje = true)
                }, video = true, mala = true),
                Vrsta(getString(R.string.os_media_tvoji_viri), emptyList(), pogled = tvojiViri()),
                Vrsta(getString(R.string.os_mediji_prilj_glasba), skladbe(p.filterNot { it.video }), skladbe = true),
                Vrsta(getString(R.string.os_mediji_prilj_video), videi(p.filter { it.video }), video = true),
            ) + seznamiBlok(seznami)
            GLASBA -> listOf(Vrsta(getString(R.string.os_mediji_prilj_glasba), skladbe(p.filterNot { it.video || it.radio }), skladbe = true)) +
                // Lastnik 27. 10. 2026: priljubljeni albumi z Useneta (samo z lastnim ponudnikom - sicer jih ni mogoce predvajati).
                listOfNotNull(UsenetAlbumi.seznam(this).takeIf { it.isNotEmpty() && UsenetLastni.naVoljo(this) }?.let { albumi ->
                    Vrsta(getString(R.string.os_usenet_albumi), albumi.map { a ->
                        // Lastno ime (ce ga je dal), spodaj ime izdaje in velikost; dolg pritisk: Preimenuj ali Odstrani.
                        Kartica(a.ime.ifBlank { UsenetGlasbaPravila.lepNaslov(a.naslov) },
                            listOf(if (a.ime.isNotBlank()) UsenetGlasbaPravila.lepNaslov(a.naslov) else "", UsenetGlasbaPravila.velikost(a.velikost))
                                .filter { it.isNotBlank() }.joinToString(" · "), "", { predvajajShranjenUsenetAlbum(a) },
                            dolgo = { urediUsenetAlbum(a) }, oznaka = "★",
                            ikona = R.drawable.os_ikona_glasba, id = "usenet-album:" + a.naslov.hashCode())
                    }, skladbe = true)
                }) +
                // Naloga 83: tudi uvozeni seznami YouTube (posnetki) - lastnik jih isce v Music.
                seznamiBlok(seznami.filter { sz -> OsPravila.seznamZaGlasbo(sz.vir, sz.skladbe.all { it.video }) })
            RADIO -> listOf(Vrsta(getString(R.string.os_media_prilj_radio), skladbe(p.filter { it.radio }), kanali = true))
            VIDEO -> {
                val dovoljeniJeziki = filtrirajJezike(i, MediaNapredek.seznam(this).map { it.skladba }).map { it.id }.toSet()
                val neurejeni = MediaNapredek.seznam(this).filter {
                    it.skladba.id in dovoljeniJeziki &&
                    (it.skladba.video || SpletniVir.vrstaVsebine(it.skladba) == SpletniVir.SERIJA || SpletniVir.vrstaVsebine(it.skladba) == SpletniVir.FILM) &&
                    SpletniVir.vrstaVsebine(it.skladba) != SpletniVir.VIDEOSPOT &&
                    !it.skladba.mediaType.equals("MusicVideo", ignoreCase = true) &&
                    !it.skladba.radio
                }
                val poId = neurejeni.associateBy { it.skladba.id }
                val vnosi = razvrsti(i, neurejeni.map { it.skladba }).mapNotNull { poId[it.id] }.take(5)
                val nadaljujVrste = if (vnosi.isNotEmpty()) {
                    val kartice = vnosi.map { vnos ->
                        val sk = vnos.skladba
                        val podnaslov = if (vnos.trajanje > 0) "${cas(vnos.polozaj)} / ${cas(vnos.trajanje)}"
                                        else sk.year.takeIf { it > 0 }?.toString().orEmpty()
                        val tip = when {
                            sk.id.startsWith("krajevno:") -> getString(R.string.os_glasba_video)
                            SpletniVir.vrstaVsebine(sk) == SpletniVir.SERIJA -> getString(R.string.os_media_serija)
                            else -> getString(R.string.os_media_film)
                        }
                        Kartica(sk.naslov, podnaslov, sk.slika, klik = {
                            if (SpletniVir.jeEnota(sk)) razresiSplet(sk) else predvajaj(listOf(sk), 0)
                        }, dolgo = { meniNadaljuj(sk) }, oznaka = tip)
                    } + Kartica(getString(R.string.os_media_pocisti_nadaljuj), getString(R.string.os_media_pocisti_nadaljuj_opis), "",
                        klik = { potrdiPocistiNadaljuj() }, ikona = R.drawable.os_ikona_ustavi, dejanje = true)
                    listOf(Vrsta(getString(R.string.os_media_nadaljuj_gledanje), kartice, video = true))
                } else emptyList()
                nadaljujVrste +
                    listOf(Vrsta(getString(R.string.os_mediji_prilj_video), videi(p.filter { it.video }), video = true)) +
                    seznamiBlok(seznami.filter { sz -> sz.skladbe.all { it.video } })
            }
            else -> emptyList()
        }
    }

    // ------------------------------------------------------------------ vrstica izbir nad mrezo razdelka

    /**
     * Ena izbira v vrstici izbir nad mrezo (napis in ▾); dotik odpre seznam moznosti. Videz kot pri Video (Filmi | Serije).
     * [sveze]: kadar moznosti pridejo sele po risanju (katalogi dodatkov), jih dotik prebere od tu - glave mreze zaradi
     * njih ni treba risati znova.
     */
    private class PoljeIzbire(val kljuc: String, val moznosti: List<String>, val izbrana: Int, val naslovOkna: String?,
                              val sveze: (() -> PoljeIzbire)? = null, val obIzbiri: (Int) -> Unit)

    private fun gumbIzbire(p: PoljeIzbire, prva: Boolean, ozek: Boolean): View = LinearLayout(this).apply {
        val izbrana = p.izbrana.coerceIn(0, (p.moznosti.size - 1).coerceAtLeast(0))
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        tag = "k:${p.kljuc}"; isFocusable = true; isClickable = true
        setPadding(dp(if (ozek) 10 else 12), dp(10), dp(if (ozek) 8 else 10), dp(10))
        fun oblika(rob: Int, debelina: Int, polnilo: Int) = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(polnilo); setStroke(dp(debelina), rob) }
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), oblika(osBarva(R.color.os_mint), 2, (osBarva(R.color.os_mint) and 0x00FFFFFF) or 0x33000000))
            addState(intArrayOf(android.R.attr.state_pressed), oblika(0x66FFFFFF, 1, 0x26FFFFFF))
            addState(intArrayOf(), oblika(0x40FFFFFF, 1, 0x14FFFFFF))
        }
        // Izbira, ki ni privzeta (prva), je obarvana: uporabnik vidi, da gleda filtriran seznam.
        addView(besedilo(15f, osBarva(if (izbrana > 0) R.color.os_mint else R.color.os_besedilo), true).apply {
            text = p.moznosti.getOrElse(izbrana) { "" }; maxLines = 1; gravity = Gravity.CENTER_VERTICAL
            ellipsize = null
            setAutoSizeTextTypeUniformWithConfiguration(8, if (ozek) 14 else 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(0, dp(24), 1f))
        addView(besedilo(12f, osBarva(R.color.os_umirjeno)).apply { text = "▾"; setPadding(dp(if (ozek) 2 else 4), 0, 0, 0) })
        if (prva) nextFocusLeftId = meniMediji.id
        // GOR iz izbir pelje na razdelek, v katerem je uporabnik - ne na tistega, ki slucajno lezi nad izbiro (izmerjeno
        // 6. 10. 2026 na televizorju: z »Vse zvrsti« v TV v zivo je GOR izbral »Video«). Na ozkem zaslonu sta vrstici dve.
        if (!ozek) aktivniRazdelek?.let { nextFocusUpId = it.id }
        setOnClickListener {
            val q = p.sveze?.invoke() ?: p
            val zdaj = q.izbrana.coerceIn(0, (q.moznosti.size - 1).coerceAtLeast(0))
            AlertDialog.Builder(this@GlasbaActivity).apply { q.naslovOkna?.let { setTitle(it) } }
                .setSingleChoiceItems(q.moznosti.toTypedArray(), zdaj) { d, i -> d.dismiss(); if (i != zdaj) q.obIzbiri(i) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    /**
     * Vrstica izbir nad mrezo razdelka: enako siroke izbire cez razpolozljivo sirino (najvec 200 dp vsaka), da so na
     * televizorju in tablici vse na zaslonu brez drsenja; na ozkem telefonu po dve v vrstico, da napisi niso odrezani.
     */
    private fun izbireVrstica(polja: List<PoljeIzbire>): View {
        val ozek = ozekZaslon()
        fun vrstica(del: List<PoljeIzbire>, mest: Int, prvaVrstica: Boolean, zadnja: Boolean): LinearLayout {
            val najvec = dp(mest * 200 + (mest - 1) * 8)
            return (if (ozek) LinearLayout(this) else object : LinearLayout(this) {
                override fun onMeasure(sirina: Int, visina: Int) {
                    val omejena = View.MeasureSpec.getMode(sirina) != View.MeasureSpec.UNSPECIFIED && View.MeasureSpec.getSize(sirina) > najvec
                    super.onMeasure(if (omejena) View.MeasureSpec.makeMeasureSpec(najvec, View.MeasureSpec.EXACTLY) else sirina, visina)
                }
            }).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(if (prvaVrstica) 6 else 0), 0, dp(if (zadnja) 12 else 8))
                del.forEachIndexed { i, p -> addView(gumbIzbire(p, i == 0, ozek), LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(8) }) }
                // Nepolna vrstica (telefon: tretja izbira sama): prazno mesto, da je izbira enako siroka kot zgornji.
                for (i in del.size until mest) addView(View(this@GlasbaActivity), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) })
            }
        }
        if (!ozek || polja.size <= 2) return vrstica(polja, polja.size, prvaVrstica = true, zadnja = true)
        val deli = polja.chunked(2)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            deli.forEachIndexed { i, d -> addView(vrstica(d, 2, prvaVrstica = i == 0, zadnja = i == deli.size - 1)) }
        }
    }

    // ------------------------------------------------------------------ TV v zivo: mreza kanalov z izbiro zvrsti in jezika

    /** Vir kanalov mreze: katalog dodatka (z izbrano moznostjo zvrsti), koliko vnosov je ze dal in ali je pri koncu. */
    private class VirKanalov(val katalog: Stremio.Katalog) {
        @Volatile var dal = 0
        @Volatile var konec = false
        /** Vir tokrat ni odgovoril (izpad, ne prazna stran): kanali, ki jih je dal nazadnje, ostanejo ([naloziKanale]). */
        @Volatile var izpad = false
        /** Stran, ki je vir se ni vrnil (mreza Glasbe in Radia nanjo ne caka, [straniDodatkov]). */
        @Volatile var vTeku: java.util.concurrent.Future<List<Jamendo.Skladba>>? = null
    }

    /**
     * Odprta mreza TV v zivo ([odpriKanale]). Kanali iz dodatkov pridejo po straneh: [domaci] (katalog v jeziku naprave,
     * samo brez izbire) in [kanali] (viri izbire). [presek]: izbrana sta zvrst in jezik - kanal jezika ostane samo, ce
     * je tudi med [dovoljeni] (katalogi zvrsti istega dodatka, [KanaliPravila.izbrani]).
     */
    private class MrezaKanalov(val zvrst: String, val jezik: String) {
        /** Grok 68, Ą2: mrezo je odprl uporabnik z izrecno izbiro zavihka TV v zivo (dotik, daljinec) - samo ta sme narediti prvo povezavo racuna. */
        var uporabnik = false
        val glavni = ArrayList<VirKanalov>()
        var presek = false
        val dovoljeni = HashSet<String>()
        val domaci = ArrayList<Jamendo.Skladba>()
        val kanali = ArrayList<Jamendo.Skladba>()
        /** Id-ji kanalov na zaslonu in vseh, ki so jih viri dali (stran brez novih pomeni konec). */
        val videni = HashSet<String>()
        val prejeti = HashSet<String>()
        var nalagam = true
        var samodejno = 0
        var zadnjicVec = 0L
        /** Kar je zdaj narisano (kanali, izbire, napis): enakega zaslona ne risemo znova. */
        var podpis = ""
        /** Id-ji narisanih kanalov po vrsti: kar uporabnik vidi, ostane na svojem mestu ([OsPravila.stabilenRed]). */
        var narisani: List<String> = emptyList()
        /** Kartica -> razlicice istega kanala, kot jih pozna zadnje risanje ([videi]): kartica na zaslonu ostane ista. */
        val razlicice = HashMap<String, List<Jamendo.Skladba>>()
        /**
         * Preverjanje, ali dodatek za kanal res ima prenos ([preveriKanal]): [okno] = koliko kanalov iz dodatkov naj bo
         * na zaslonu, preden cakamo na uporabnika; [proracun] = koliko poizvedb smemo se poslati do njegovega naslednjega
         * dejanja; [neznani] = kanali, za katere odgovora ni bilo - ostanejo na zaslonu (naloga 74), v tej mrezi jih ne
         * sprasujemo znova; [brezOdgovora] = zaporedna vprasanja, na katera dodatek ni odgovoril (po [BREZ_ODGOVORA_NAJVEC]
         * nehamo sprasevati do uporabnikovega dejanja).
         */
        val brezOdgovora = java.util.concurrent.atomic.AtomicInteger(0)
        var okno = OKNO_KANALOV
        var proracun = PRORACUN_KANALOV
        val neznani: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        /** Za oknom je se kaj nalozenega. */
        var naprej = false
        var risanjeNaroceno = false
        /** Naloga 74: preverjanje vidnih kartic je ze naroceno ([narociPreverjanje]). */
        var preverjanjeNaroceno = false
        /** Naloga 74: priljubljeni kanali zadnjega risanja - vedno na zaslonu, v ozadju jih ne preverjamo. */
        var vedno: Set<String> = emptySet()
        /** Dopolnitev caka, da se prvo risanje te mreze konca ([narisiKanale]). */
        var cakaNaRisanje = false
        /** Naloga 88: od kdaj (uptime) dopolnitev caka na prvo risanje; 0 = ne caka. */
        var cakaRisanjeOd = 0L
        val seKaj: Boolean get() = glavni.any { !it.konec } || rezerva.isNotEmpty() || seznami.any { !it.konec }
        /** Naloga 80: viri brez izbire, ki se niso v [glavni] - samodejno dopolnijo mrezo, ko so glavni izcrpani. */
        val rezerva = ArrayList<VirKanalov>()
        /** Naloga 80: seznami IPTV te mreze (domaci in ostali). */
        val seznami = ArrayList<IptvSeznami.Vir>()
        /** Naloga 80 (53b): meritev - kdaj je bila mreza odprta, ali je prvi izris ze zapisan in od kod je bil. */
        val odprtOb = android.os.SystemClock.uptimeMillis()
        var izmerjeno = false
        @Volatile var izvor = "sveze"
        @Volatile var seme = false
        /** Naloga 82: koliko kartic je zadnje risanje pokazalo (nit nalaganja ve, ali uporabnik ze kaj vidi). */
        @Volatile var vidnih = 0
        /** Naloga 82: kanali mreze brez izbire (pomnilnik ali disk) - nadomestek, ko izbira nima kanalov. */
        @Volatile var nadomestni: List<Jamendo.Skladba> = emptyList()
        val kljuc: String get() = "$zvrst|$jezik"
        /** Naloga 73: mreza ni vec odprta (drug razdelek, druga izbira) - njene naloge nehajo in se ne zacnejo vec. */
        @Volatile var preklicana = false
        val naloge = java.util.concurrent.CopyOnWriteArrayList<java.util.concurrent.Future<*>>()
        fun preklici() { preklicana = true; naloge.forEach { it.cancel(true) }; naloge.clear() }
        /** Zasebne kategorije se niso znane (novi kanali teh dodatkov cakajo): mreza se nalozi znova, ko jih spoznamo. */
        @Volatile var zasebniCakajo = false
        /** Ponovni poskus, ko noben vir ni odgovoril, je ze narocen; cez koliko minut. */
        var ponovitev = false
        var cezMinut = 0
    }
    /** Zaporedni ponovni poskusi mreze kanalov brez odgovora virov (za [KanaliPravila.zamikPonovitve]); 0, ko kanali pridejo. */
    private var poskusiKanalov = 0
    private var mrezaKanalov: MrezaKanalov? = null

    /**
     * TV v zivo kot mreza kanalov z izbiro zvrsti in jezika (lastnik, 5. 10. 2026: »da bi si lahko uporabnik filtriral
     * glede na vsebino, podobno kot pri filmih«). Prej ena polica s prvimi 80 kanali iz prvih 12 katalogov po abecedi
     * in polica na drzavo: do novic, sporta ali kanalov v svojem jeziku uporabnik ni mogel. Vgrajeni uradni kanali
     * so na zaslonu takoj (brez omrezja), kanali iz dodatkov pridejo za njimi.
     */
    private fun odpriKanale(zvrst: String = tvZvrst, jezik: String = tvJezik, izrecno: Boolean = false) {
        tvZvrst = zvrst; tvJezik = jezik
        mrezaKanalov?.preklici()
        val m = MrezaKanalov(zvrst, jezik)
        // Grok 68, Ą2: obnova zaslona, samodejni ponovni poskusi in osvezitev po spremembi virov so [izrecno] = false.
        m.uporabnik = izrecno
        mrezaKanalov = m
        // Nic cakanja: zadnja znana mreza te izbire je na zaslonu takoj; sveza jo zamenja le, ce je drugacna.
        KANALI_MREZE[m.kljuc]?.let { (d, k) -> m.domaci += d; m.kanali += k; m.videni += (d + k).map { it.id }; m.izvor = "pomnilnik" }
        // Vrstni red, kot ga je uporabnik pri tej izbiri nazadnje videl: ob vrnitvi so kanali na istih mestih.
        KANALI_RED["${m.kljuc}|${razvrstitev(TV_V_ZIVO)}"]?.let { m.narisani = it }
        drsnik.scrollTo(0, 0)
        narisiKanale(m)
        // Pred prvo postavitvijo sirine se ne poznamo: ko je znana, mrezo narisemo se enkrat s pravim stevilom ploscic v vrsti.
        if (vsebina.width == 0) vsebina.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (vsebina.width <= 0) return
                vsebina.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (mrezaKanalov === m && !isFinishing) { m.podpis = ""; narisiKanale(m) }
            }
        })
        // Naloga 80 (53b): kanali z diska po zagonu na zaslonu v < 1 s - na lastni niti, ne za manifesti in zasebnimi kategorijami
        // ([delavec] je lahko zaseden z dolgimi pregledi).
        // Naloga 82: kanali brez izbire kot nadomestek prazne izbire - iz pomnilnika takoj, sicer z diska (lastna nit).
        if (m.kljuc != "|") KANALI_MREZE["|"]?.let { (d, k) -> m.nadomestni = d + k }
        if (m.kanali.isEmpty() && m.domaci.isEmpty() || (m.kljuc != "|" && m.nadomestni.isEmpty())) try { semeDelavec.execute {
            if (m.kanali.isEmpty() && m.domaci.isEmpty()) naloziSeme(m)
            naloziNadomestneKanale(m)
        } } catch (_: java.util.concurrent.RejectedExecutionException) { }
        // Naloga 86 (M3): na lastnem izvajalniku - preklicana mreza (hitra menjava zvrsti) ne sme zasesti [delavec].
        try { nalaganjeKanalov.execute { naloziKanale(m) } } catch (_: java.util.concurrent.RejectedExecutionException) { }
        // Naloga 90b: zaklenjene dodatke ob vsakem odprtju poskusimo odkleniti (placilo dodatku), ne sele po 24 h.
        poskusiOdkleniDodatke(m)
        // Naloga 82: prazna mreza se ob rokih narise znova (nadomestek po 3 s, konec »Nalagam« po 45 s), tudi brez odgovorov.
        for (rok in longArrayOf(KanaliPravila.PRAZNA_NADOMESTEK_MS, KanaliPravila.PRAZNA_KONEC_MS))
            glavna.postDelayed({ if (mrezaKanalov === m && !isFinishing && m.vidnih == 0) narisiKanale(m) }, rok + 50)
    }

    /** Naloga 90b: poskusi odklepa v ozadju ([Stremio.poskusiOdklep]); [m] = mreza, ki se po uspehu narise znova (null: brez osvezitve). */
    private val odklepNit = Executors.newSingleThreadExecutor { r -> Thread(r, "safeer-odklep").apply { isDaemon = true } }
    private fun poskusiOdkleniDodatke(m: MrezaKanalov?) {
        if (tvZaklenjeni) return
        // Najvec en poskus na dodatek v [POSKUS_ODKLEPA_MS]: poskus prebere stran kataloga kanalov (IPTV: velik odgovor), ne ob
        // vsakem odprtju Live TV. Shranjen dodatek ([m] == null, uporabnik je pravkar placal ali spremenil naslov) vedno.
        val zdaj = android.os.SystemClock.elapsedRealtime()
        val naslovi = OsPravila.zaklenjeniZaPoskus(stremioNaslovi(), { Stremio.osnova(it) }) { Stremio.zaklenjenZaMrezo(it) }
            .filter { n -> m == null || (ZADNJI_POSKUS_ODKLEPA[Stremio.osnova(n)] ?: Long.MIN_VALUE / 2).let { zdaj - it >= POSKUS_ODKLEPA_MS } }
        if (naslovi.isEmpty()) return
        naslovi.forEach { ZADNJI_POSKUS_ODKLEPA[Stremio.osnova(it)] = zdaj }
        try { odklepNit.execute {
            var odklenjen = false
            for (n in naslovi) if (try { Stremio.poskusiOdklep(n) } catch (_: Exception) { false }) odklenjen = true
            if (odklenjen) {
                android.util.Log.i("SafeerOsMedia", "dodatek je spet dal proste tokove: kanale pokazemo")
                if (m != null) glavna.post { if (mrezaKanalov === m && !isFinishing) odpriKanale(m.zvrst, m.jezik) }
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    /**
     * Naloga 86 (M3): nalaganje mreze kanalov ([naloziKanale]) - dve niti: nova izbira ne caka na prejsnjo, ki se po preklicu
     * konca v najvec sekundi ([OsPravila.cakajPoKoscih]).
     */
    private val nalaganjeKanalov = Executors.newFixedThreadPool(2) { r -> Thread(r, "safeer-kanali-nal").apply { isDaemon = true } }
    /** Naloga 86 (M3): pregled zasebnih katalogov ([pregledZasebnih]) - dolg, zato na svoji niti. */
    private val pregledZasebnihNit = Executors.newSingleThreadExecutor { r -> Thread(r, "safeer-zasebni").apply { isDaemon = true } }

    /** Naloga 80: kratka opravila zaslona, ki ne smejo cakati v vrsti za dolgimi (seme mreze kanalov z diska). */
    private val semeDelavec = Executors.newSingleThreadExecutor { r -> Thread(r, "safeer-seme").apply { isDaemon = true } }

    private fun kljucSemenaKanalov(m: MrezaKanalov) = "kanali|${m.kljuc}|${resources.configuration.locales[0].language}"

    /** Naloga 80: viri SEZNAM, ki so vidni v TV v zivo; najprej seznami drzave naprave ali jezika vmesnika (»si« pred »eng«). */
    private fun seznamiTv(): List<String> {
        val loc = resources.configuration.locales[0]
        return MedijskiViri.vsi(this).filter { it.jeSeznam && jeVirViden(TV_V_ZIVO, kljucVira(it)) }.map { it.naslov }
            .sortedBy { n -> val (d, j) = IptvPravila.izNaslovaSeznama(n); if (d.equals(loc.country, true) || j.equals(loc.language, true)) 0 else 1 }
    }

    /**
     * Naloga 80 (53b, lastnik: »naj se Live TV nalozi hitro kot filmi«): zadnja znana mreza te izbire z diska (najvec
     * [KANALI_SEME_MS] stara) - zasebne kategorije z diska veljajo ([ZasebniKanali]), manifestov ne cakamo. Sveza jo zamenja,
     * ko odgovorijo vsi viri (stabilen red). Kanalov dodatka ali seznama, ki ga uporabnik nima vec, ne vracamo.
     */
    private fun naloziSeme(m: MrezaKanalov) {
        if (mrezaKanalov !== m || m.preklicana) return
        ZasebniKanali.nalozi(this)
        val dodatki = stremioNaslovi()
        val seznami = seznamiTv().toSet()
        if (dodatki.isEmpty() && seznami.isEmpty()) return
        val shranjeno = try { MedijskiPredpomnilnik.beriPoliceSCasom(this, kljucSemenaKanalov(m)) } catch (_: Exception) { null } ?: return
        if (System.currentTimeMillis() - shranjeno.first !in 0 until KANALI_SEME_MS) return
        // Naloga 82: kanalov zaklenjenega dodatka ne vracamo (bili bi skriti, mreza pa bi veljala za polno).
        fun del(ime: String) = shranjeno.second.firstOrNull { it.first == ime }?.third.orEmpty()
            .filter { !Stremio.brezDodatka(it, dodatki) && (!IptvSeznami.jeKanal(it) || it.streznik in seznami) && !neodklenjena(it) }
        val d = del("domaci")
        val k = del("kanali")
        if (d.isEmpty() && k.isEmpty()) return
        m.seme = true
        glavna.post {
            if (isFinishing || mrezaKanalov !== m || m.kanali.isNotEmpty() || m.domaci.isNotEmpty()) return@post
            // Skozi isto sito kot svezi ([dodajKanale]): zasebne kategorije, ce so znane (tudi z diska, naloga 76).
            dodajKanale(m, d, m.domaci); dodajKanale(m, k, m.kanali)
            KANALI_MREZE[m.kljuc] = m.domaci.toList() to m.kanali.toList()
            m.izvor = "disk"
            dnevnikMreze { "kanali: z diska domaci=${m.domaci.size}, kanali=${m.kanali.size}" }
            narisiKanale(m)
        }
    }

    /**
     * Naloga 82: kanali mreze brez izbire z diska (seme »|«) kot nadomestek prazne izbire (zvrst, jezik). Klic iz ozadja;
     * mreza se narise znova, ce je prazna.
     */
    private fun naloziNadomestneKanale(m: MrezaKanalov) {
        if (m.kljuc == "|" || m.nadomestni.isNotEmpty() || mrezaKanalov !== m || m.preklicana) return
        val dodatki = stremioNaslovi()
        val seznami = seznamiTv().toSet()
        val kljuc = "kanali||${resources.configuration.locales[0].language}"
        val shranjeno = try { MedijskiPredpomnilnik.beriPoliceSCasom(this, kljuc) } catch (_: Exception) { null } ?: return
        if (System.currentTimeMillis() - shranjeno.first !in 0 until KANALI_SEME_MS) return
        val n = shranjeno.second.flatMap { it.third }
            .filter { !Stremio.brezDodatka(it, dodatki) && (!IptvSeznami.jeKanal(it) || it.streznik in seznami) && !ZasebniKanali.jeZaseben(it.id) }
            .distinctBy { it.id }.take(NADOMESTNI_KANALI)
        if (n.isEmpty()) return
        glavna.post {
            if (isFinishing || mrezaKanalov !== m || m.nadomestni.isNotEmpty()) return@post
            m.nadomestni = n
            if (m.vidnih == 0) narisiKanale(m)
        }
    }

    /**
     * Naloga 86 (M3): [f], ko je koncan, ali null po [rokMs] ali ob preklicu ([prekinjen]); caka po kosih do 1 s. Klic iz ozadja.
     */
    private fun <T> dokoncan(f: java.util.concurrent.Future<T>, rokMs: Long, prekinjen: () -> Boolean): java.util.concurrent.Future<T>? =
        OsPravila.cakajPoKoscih(rokMs, ura = { android.os.SystemClock.uptimeMillis() }, prekinjen = prekinjen) { ms ->
            try { f.get(ms, java.util.concurrent.TimeUnit.MILLISECONDS); f } catch (_: java.util.concurrent.TimeoutException) { null }
                catch (_: java.util.concurrent.ExecutionException) { f } catch (_: java.util.concurrent.CancellationException) { f }
        }

    /** Naslednja stran vira (klic iz ozadja). Vir je pri koncu, ko stran ni polna. */
    private fun stranKanalov(v: VirKanalov): List<Jamendo.Skladba> {
        if (v.konec) return emptyList()
        val odgovor = try { Stremio.katalogAliIzpad(v.katalog, skip = v.dal) } catch (_: Exception) { null }
        // Izpad ni konec kataloga: za ta obisk od vira ne pricakujemo nicesar vec, njegovi zadnji znani kanali pa ostanejo.
        if (odgovor == null) v.izpad = true
        val stran = odgovor.orEmpty()
        v.dal += stran.size
        if (odgovor == null || !OsPravila.katalogImaVec(stran.size, v.katalog.strani, STRAN_KATALOGA_MIN)) v.konec = true
        // Ime brez tehnicnih pripon seznama (»(720p)«): isti kanal v dveh locljivostih je ena kartica. Lastnik, 25. 10.:
        // kanalov [Not 24/7] ne rabimo - tudi iz dodatkov ne.
        return stran.filterNot { KanaliPravila.jeNe247(it.naslov) }.map { it.copy(naslov = KanaliPravila.imeKanala(it.naslov)) }
    }

    /**
     * Id-ji kanalov virov, po straneh do konca ali do [najvecStrani] na vir (klic iz ozadja). Naloga 73: vir za virom v
     * tej niti (prej vsak vir v svoji niti neomejenega bazena - pri 30 zasebnih katalogih 30 hkratnih razclenjevanj);
     * [velja] = false (mreza zaprta) ustavi delo, ne da bi kaj sklepali (vir velja za izpad).
     */
    private fun idKanalov(viri: List<VirKanalov>, najvecStrani: Int, velja: () -> Boolean = { true }): Set<String> {
        val izhod = HashSet<String>()
        for (v in viri) {
            var strani = 0
            while (!v.konec && strani++ < najvecStrani) {
                if (!velja() || Thread.currentThread().isInterrupted) { v.izpad = true; return izhod }
                val s = stranKanalov(v)
                // Stran brez novih kanalov: katalog strani ne pozna (vraca isto) - konec.
                if (s.isEmpty() || !izhod.addAll(s.map { it.id })) break
            }
        }
        return izhod
    }

    /**
     * Naloga 76: zasebni viri po dodatkih (osnova -> kljuc zapisa in viri). Kljuc so opisi katalogov dodatka
     * ([KanaliPravila.kljucZasebnih]): ko dodatek doda ali odvzame zasebni katalog, zapis ne velja vec.
     */
    private fun zasebniPoDodatkih(viri: List<VirKanalov>): Map<String, Pair<String, List<VirKanalov>>> =
        viri.groupBy { it.katalog.dodatek }.mapValues { (_, v) -> KanaliPravila.kljucZasebnih(v.map { it.katalog.id + "/" + it.katalog.privzeti }) to v }

    /**
     * Naloga 76: pregled zasebnih katalogov, ki tece ([zacniPregledZasebnih]); en na proces. [koncani]: dodatki, katerih
     * zapis je ze nov ([ZasebniKanali]); poslusalci dobijo osnovo vsakega koncanega dodatka in "" ob koncu pregleda.
     */
    private class PregledZasebnih(val dodatki: Set<String>) {
        val koncani: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        val poslusalci = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()
        @Volatile var tece = true
        private val zaklep = java.util.concurrent.locks.ReentrantLock()
        private val sprememba = zaklep.newCondition()
        private fun zbudi() { zaklep.lock(); try { sprememba.signalAll() } finally { zaklep.unlock() } }
        fun koncan(d: String) {
            koncani += d
            zbudi()
            poslusalci.forEach { try { it(d) } catch (_: Throwable) { } }
        }
        fun konec() {
            tece = false
            zbudi()
            poslusalci.forEach { try { it("") } catch (_: Throwable) { } }
            // Naloga 86 (M2): pregled je v staticnem PREGLED_ZASEBNIH - poslusalci (z Activity) ne smejo ostati v njem.
            poslusalci.clear()
        }
        /** Pocaka, da so [d] koncani ali da pregled neha, najvec [ms]. */
        fun pocakaj(d: Set<String>, ms: Long) {
            val doKdaj = android.os.SystemClock.uptimeMillis() + ms
            zaklep.lock()
            try {
                while (tece && !koncani.containsAll(d)) {
                    val ostane = doKdaj - android.os.SystemClock.uptimeMillis()
                    if (ostane <= 0) return
                    try { sprememba.await(ostane, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { return }
                }
            } finally { zaklep.unlock() }
        }
    }

    /**
     * Naloga 76: zacne pregled zasebnih katalogov dodatkov [za] (ali vrne tistega, ki ze tece). Pregled tece v [delavec],
     * strani berejo [ZASEBNI_HKRATI] niti bazena kanalov - ena nit bazena ostane prostim stranem mreze (naloga 73).
     */
    private fun zacniPregledZasebnih(za: Map<String, Pair<String, List<VirKanalov>>>): PregledZasebnih? {
        if (za.isEmpty()) return null
        synchronized(ZASEBNI_ZAKLEP) {
            PREGLED_ZASEBNIH?.takeIf { it.tece }?.let { return it }
            val p = PregledZasebnih(za.keys)
            PREGLED_ZASEBNIH = p
            val app = applicationContext
            // Naloga 86 (M3): lastna nit - pregled traja lahko minute in ne sme zasedati [delavec] (Glasba, Radio, zapisi).
            try { pregledZasebnihNit.execute { try { pregledZasebnih(app, p, za) } finally { p.konec() } } }
            catch (_: java.util.concurrent.RejectedExecutionException) { p.konec(); return null }
            return p
        }
    }

    /**
     * Naloga 76: pregled zasebnih katalogov - najprej prva stran vsakega kataloga (vecina je majhnih in so s tem koncani),
     * nato naslednje strani tistih, ki jih imajo (vrsta: nova stran gre na konec), do [ZASEBNI_STRANI] strani na katalog.
     * Dodatek je koncan, ko so koncani vsi njegovi zasebni katalogi: zapis gre na disk in mreza se lahko osvezi (prej sele
     * po vseh dodatkih). Izpad kateregakoli kataloga dodatka (ali preklic) ni odgovor: zapis dodatka ostane, kot je bil.
     * Delo tece samo, dokler je zaslon spredaj (predvajanje ga ustavi; koncani dodatki ostanejo shranjeni).
     */
    private fun pregledZasebnih(app: android.content.Context, p: PregledZasebnih, za: Map<String, Pair<String, List<VirKanalov>>>) {
        val velja = { !isFinishing && vOspredju }
        val od = android.os.SystemClock.uptimeMillis()
        val idji = HashMap<String, HashSet<String>>()
        val odprtih = HashMap<String, Int>()
        val izpad = HashSet<String>()
        val strani = HashMap<VirKanalov, Int>()
        val vrsta = ArrayDeque<Pair<String, VirKanalov>>()
        for ((d, kv) in za) { idji[d] = HashSet(); odprtih[d] = kv.second.size; kv.second.forEach { vrsta.addLast(d to it) } }
        fun katalogKoncan(d: String) {
            val n = (odprtih[d] ?: return) - 1
            odprtih[d] = n
            if (n > 0) return
            val id = idji.remove(d).orEmpty()
            if (d in izpad) return
            ZasebniKanali.zapisi(app, d, za.getValue(d).first, id)
            android.util.Log.i("SafeerOsMedia", "kanali: zasebne kategorije dodatka znane (${id.size} kanalov, katalogov ${za.getValue(d).second.size}) po ${android.os.SystemClock.uptimeMillis() - od} ms")
            p.koncan(d)
        }
        val prihodi = java.util.concurrent.ExecutorCompletionService<Pair<Pair<String, VirKanalov>, List<Jamendo.Skladba>>>(kanaliDelavec)
        var vTeku = 0
        while (vrsta.isNotEmpty() || vTeku > 0) {
            while (vTeku < ZASEBNI_HKRATI && vrsta.isNotEmpty() && velja()) {
                val dv = vrsta.removeFirst()
                // Dodatek, ki je enkrat izpadel, tokrat ne bo znan: ostalih njegovih strani ne sprasujemo.
                if (dv.first in izpad) { katalogKoncan(dv.first); continue }
                try {
                    prihodi.submit { dv to (if (velja()) (try { stranKanalov(dv.second) } catch (_: Exception) { dv.second.izpad = true; emptyList() })
                        else { dv.second.izpad = true; emptyList() }) }
                    vTeku++
                } catch (_: java.util.concurrent.RejectedExecutionException) { return }
            }
            if (vTeku == 0) return      // preklic (velja) ali nic vec
            val f = try { prihodi.poll(60, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) { return } ?: return
            vTeku--
            val (dv, s) = try { f.get() } catch (_: Exception) { return }
            val (d, v) = dv
            if (v.izpad) izpad += d
            val n = (strani[v] ?: 0) + 1
            strani[v] = n
            // Stran brez novih kanalov: katalog strani ne pozna (vraca isto) - konec (kot [idKanalov]).
            val novi = idji[d]?.addAll(s.map { it.id }) == true
            if (!v.konec && s.isNotEmpty() && novi && n < ZASEBNI_STRANI && d !in izpad) vrsta.addLast(dv) else katalogKoncan(d)
        }
    }

    /**
     * Dodatki (osnove), katerih zasebnih kategorij kanalov ta cas ne poznamo: njihov vir ali manifest ni odgovoril. Prazno:
     * znane so ([ZasebniKanali], tudi z diska) ali jih noben dodatek nima. Klic iz ozadja; iskanje ga potrebuje, preden pokaze
     * kanale dodatkov. Naloga 76: neznane pocaka najvec 10 s (pregled ostalih tece naprej).
     */
    private fun neznaniZasebniKanali(dodatki: List<String>): Set<String> {
        if (dodatki.isEmpty()) return emptySet()
        ZasebniKanali.nalozi(applicationContext)
        val brezManifesta = dodatki.filter { (try { Stremio.manifest(it) } catch (_: Exception) { null }) == null }.map { Stremio.osnova(it) }
        val katalogi = try { Stremio.katalogiTv(dodatki) } catch (_: Exception) { emptyList() }
        val filtri = KanaliPravila.filtri(katalogi.map { KanaliPravila.Katalog(it.dodatek, it.imeDodatka, it.ime, it.zvrsti) })
        if (filtri.zasebni.isEmpty()) return brezManifesta.toSet()
        val poDodatkih = zasebniPoDodatkih(filtri.zasebni.map { VirKanalov(Stremio.zMoznostjo(katalogi[it.katalog], it.moznost)) })
        val stanja = poDodatkih.mapValues { (d, kv) -> ZasebniKanali.stanje(d, kv.first) }
        val neznani = stanja.filterValues { it == KanaliPravila.ZasebniZapis.NI }.keys
        val p = zacniPregledZasebnih(poDodatkih.filterKeys { stanja[it] != KanaliPravila.ZasebniZapis.VELJA })
        if (neznani.isNotEmpty() && p != null) p.pocakaj(neznani, 10_000)
        return (brezManifesta + (neznani - p?.koncani.orEmpty())).toSet()
    }

    private fun naloziKanale(m: MrezaKanalov) {
        val dodatki = stremioNaslovi()
        // Naloga 76: zasebne kategorije z diska, preden pride na zaslon karkoli (tudi seme spodaj gre skozi [dodajKanale]).
        ZasebniKanali.nalozi(this)
        ZasebniKanali.obdrzi(this, dodatki.map { Stremio.osnova(it) })
        // Naloga 86 (M5): datoteke seznamov IPTV, ki jih uporabnik nima vec (odstranjen tu ali na drugi napravi v Linku).
        IptvSeznami.obdrzi(this, MedijskiViri.vsi(this).filter { it.jeSeznam }.map { it.naslov })
        // Zadnja znana mreza z diska je ze na poti ([naloziSeme], lastna nit); tu samo kljuc za zapis svezih kanalov.
        val kljucSemena = kljucSemenaKanalov(m)
        // Naloga 80: seznami IPTV - prva stran z diska (prenos, ce ga se ni; zastarel se osvezi v ozadju), domaci najprej.
        val jezikVmesnika = resources.configuration.locales[0].language
        val drzavaVmesnika = resources.configuration.locales[0].country
        val app = applicationContext
        val seznamiTv = seznamiTv()
        val iptvPrva = if (seznamiTv.isEmpty()) null else kanaliDelavec.submit<Triple<List<IptvSeznami.Vir>, List<Jamendo.Skladba>, List<Jamendo.Skladba>>?> {
            val viri = seznamiTv.flatMap { n ->
                if (m.preklicana) return@flatMap emptyList<IptvSeznami.Vir>()
                IptvSeznami.pripravi(app, n, uporabnik = m.uporabnik)
                listOf(IptvSeznami.Vir(n, true, m.zvrst, m.jezik), IptvSeznami.Vir(n, false, m.zvrst, m.jezik))
            }
            Triple(viri, viri.filter { it.domaci }.flatMap { IptvSeznami.naslednja(app, it, drzavaVmesnika, jezikVmesnika, OKNO_KANALOV) },
                viri.filter { !it.domaci }.flatMap { IptvSeznami.naslednja(app, it, drzavaVmesnika, jezikVmesnika, OKNO_KANALOV) })
        }.also { m.naloge += it }
        // Manifesti vseh dodatkov hkrati (zaporedno je prvo odprtje po zagonu trajalo po sekundo na dodatek).
        // Naloga 86 (M3): skupni rok 20 s po kosih - preklic mreze cakanje konca takoj.
        val manifesti = dodatki.map { n -> iskanjeDelavec.submit<Unit> { try { Stremio.manifest(n) } catch (_: Exception) { }; Unit } }
        val rokManifestov = android.os.SystemClock.uptimeMillis() + 20_000
        // Prekinitev niti (onDestroy: shutdownNow) ni napaka - brez ujetja bi podrla proces (nit izvajalnika).
        try { for (f in manifesti) { dokoncan(f, rokManifestov - android.os.SystemClock.uptimeMillis()) { m.preklicana }; if (m.preklicana) return } }
        catch (_: InterruptedException) { return }
        // Diagnostika na zahtevo razvijalca (Stremio.dnevnik): katalogi dodatkov; brez vklopa ne naredi nicesar.
        if (Stremio.dnevnik()) try { Stremio.dnevnikKatalogov(dodatki) } catch (_: Exception) { }
        val katalogi = if (dodatki.isEmpty()) emptyList() else try { Stremio.katalogiTv(dodatki) } catch (_: Exception) { emptyList() }
        val opisi = katalogi.map { KanaliPravila.Katalog(it.dodatek, it.imeDodatka, it.ime, it.zvrsti) }
        val filtri = KanaliPravila.filtri(opisi)
        FILTRI_KANALOV = filtri
        // Naloga 91b: jeziki katalogov ostanejo na disku - ob naslednjem odprtju so v izbiri takoj, se preden dodatki odgovorijo.
        if (katalogi.isNotEmpty()) {
            SHRANJENI_JEZIKI_KANALOV = filtri.jeziki.map { it.kljuc }
            getSharedPreferences(NASTAVITVE_POGLEDA, MODE_PRIVATE).edit()
                .putString(JEZIKI_KANALOV, filtri.jeziki.joinToString(",") { it.kljuc }).apply()
        }
        fun vir(v: KanaliPravila.Vir) = VirKanalov(Stremio.zMoznostjo(katalogi[v.katalog], v.moznost))
        if (m.preklicana) return
        val velja = { !m.preklicana }
        val izbrani = KanaliPravila.izbrani(filtri, opisi, m.zvrst, m.jezik)
        // Zvrst in jezik hkrati: id-ji kanalov zvrsti (najvec 15 strani na katalog).
        val presekViri = izbrani.presek.map { vir(it) }
        // Brez izbire: najprej domaci kanali - katalogi drzave naprave, jezika vmesnika in regije (naloga 73, KanaliPravila.domaciViri).
        val jezikNaprave = resources.configuration.locales[0].language
        val drzavaNaprave = resources.configuration.locales[0].country
        // Naloga 80 (53c): dodatki brez prostih tokov (naloga 79) ne zasedejo mest virov - mreza bi imela le nekaj vrst.
        // Naloga 82: pri VSAKI izbiri in tudi po izteku 24-urnega sklepa, dokler dodatek ne da zivega toka
        // ([Stremio.zaklenjenZaMrezo]); prej je zvrst »News« po izteku dobila samo »All IPTV News«.
        val zaklenjen: (String) -> Boolean = { d -> !tvZaklenjeni && Stremio.zaklenjenZaMrezo(d) }
        val domaciViri = if (m.zvrst.isEmpty() && m.jezik.isEmpty()) KanaliPravila.brezZaklenjenih(KanaliPravila.domaciViri(opisi, filtri, jezikNaprave, drzavaNaprave), opisi, zaklenjen).first.map { vir(it) } else emptyList()
        // Brez izbire: katalogi kategorij (General, News ...) pred drzavnimi, nato ostali; ostali viri so rezerva za dopolnjevanje.
        val (vsiGlavni, izpusceni) = KanaliPravila.brezZaklenjenih(
            if (m.zvrst.isEmpty() && m.jezik.isEmpty()) KanaliPravila.razvrstiVire(izbrani.glavni, opisi) else izbrani.glavni, opisi, zaklenjen)
        if (izpusceni.isNotEmpty()) android.util.Log.i("SafeerOsMedia", "kanali: izpusceni zaklenjeni viri ${izpusceni.map { katalogi[it.katalog].ime }}")
        val glavni = vsiGlavni.take(12).map { vir(it) }
        val rezerva = vsiGlavni.drop(12).take(48).map { vir(it) }
        val domaciStrani = domaciViri.map { v -> kanaliDelavec.submit<List<Jamendo.Skladba>> { if (m.preklicana) emptyList() else stranKanalov(v) }.also { m.naloge += it } }
        // Viri odgovorijo razlicno hitro (izmerjeno 5. 10. 2026: eden v 0,3 s, drugi sploh ne - 15 s do casovne omejitve).
        // Mreza zato ne caka na vse: kanali so na zaslonu s prvim odgovorom, pocasen vir se doda, ko odgovori.
        val prihodi = java.util.concurrent.ExecutorCompletionService<List<Jamendo.Skladba>>(kanaliDelavec)
        glavni.forEach { v -> m.naloge += prihodi.submit { if (m.preklicana) emptyList() else stranKanalov(v) } }
        // Naloga 73: dolga pregleda (zasebne kategorije, presek zvrsti) gresta v vrsto ZA prvimi stranmi - bazen ima
        // [KANALI_HKRATI] niti in prvi kanali na zaslonu ne smejo cakati nanju.
        // Naloga 76: zasebne kategorije po dodatkih z diska ([ZasebniKanali]); veljaven zapis = nic dela, zastarel velja do
        // konca pregleda v ozadju, samo dodatki brez zapisa so neznani.
        val zasebniPo = zasebniPoDodatkih(filtri.zasebni.map { vir(it) })
        val stanjaZasebnih = zasebniPo.mapValues { (d, kv) -> ZasebniKanali.stanje(d, kv.first) }
        val pregled = zacniPregledZasebnih(zasebniPo.filterKeys { stanjaZasebnih[it] != KanaliPravila.ZasebniZapis.VELJA })
        val dovoljeni = if (presekViri.isEmpty()) null else kanaliDelavec.submit<Set<String>> { idKanalov(presekViri, 15, velja) }.also { m.naloge += it }
        android.util.Log.i("SafeerOsMedia", "kanali: viri domaci=${domaciViri.map { it.katalog.ime }}, glavni=${glavni.map { it.katalog.ime }}, rezerva=${rezerva.size}, " +
            "dodatkov s katalogi TV=${opisi.map { it.skupina }.distinct().size}, zasebnih katalogov=${filtri.zasebni.size}, " +
            "zasebni zapisi=${stanjaZasebnih.values.groupingBy { it }.eachCount()}")
        // Naloga 86 (M3): po kosih do 1 s - preklicana mreza ne caka do roka (prej do 90 s).
        fun <T> pocakaj(f: java.util.concurrent.Future<T>?, privzeto: T, s: Long): T =
            try { f?.let { dokoncan(it, s * 1000) { m.preklicana } }?.get() ?: privzeto } catch (_: Exception) { privzeto }
        // Zasebne kategorije dodatka niso znane (zapisa ni, pregled se ni koncal): novih kanalov tega dodatka ne pokazemo - med
        // njimi so lahko zasebni. Kar je mreza ze imela, je precisceno in ostane. Kratek pregled (vecina zasebnih katalogov
        // ima eno stran) pocakamo najvec 5 s.
        val brezZapisa = stanjaZasebnih.filterValues { it == KanaliPravila.ZasebniZapis.NI }.keys
        if (brezZapisa.isNotEmpty() && pregled != null) pregled.pocakaj(brezZapisa, 5_000)
        val neznaniZasebni: Set<String> = brezZapisa - pregled?.koncani.orEmpty()
        // Naloga 76: mreza se osvezi za vsak dodatek, ko so koncani vsi njegovi zasebni katalogi (prej sele po vseh, do 3 min);
        // do takrat nad prazno mrezo pise »Preverjam kategorije dodatkov ...«.
        if (neznaniZasebni.isNotEmpty() && pregled != null && pregled.tece) {
            m.zasebniCakajo = true
            var znova = false
            val poslusalec: (String) -> Unit = { d ->
                if (d.isEmpty() || d in neznaniZasebni) glavna.post {
                    if (d.isEmpty()) m.zasebniCakajo = false
                    if (isFinishing || mrezaKanalov !== m || znova) return@post
                    if (d.isNotEmpty()) {
                        // Vec dodatkov, koncanih skoraj hkrati, da eno novo nalaganje.
                        znova = true
                        android.util.Log.i("SafeerOsMedia", "kanali: zasebne kategorije dodatka znane, mreza znova")
                        glavna.postDelayed({ if (mrezaKanalov === m && !isFinishing) odpriKanale(m.zvrst, m.jezik) }, 300)
                    } else narisiKanale(m)
                }
            }
            pregled.poslusalci += poslusalec
            // Odhod iz mreze: poslusalec gre z njo.
            m.naloge += java.util.concurrent.CompletableFuture<Unit>().also { f -> f.whenComplete { _, _ -> pregled.poslusalci -= poslusalec } }
            // Pregled je med prijavo morda ze koncal.
            if (!pregled.tece) poslusalec("") else (neznaniZasebni intersect pregled.koncani).firstOrNull()?.let { poslusalec(it) }
        }
        val smejo = dovoljeni?.let { pocakaj(it, emptySet(), 90) }
        val domaci = prepleti(domaciStrani.map { pocakaj(it, emptyList(), 6) })
        // Naloga 80: prvi prenos seznama (eng: ~1 MB) pocakamo najvec 15 s; potem so na disku in to je takoj.
        val iptv = iptvPrva?.let { pocakaj(it, null, 15) }
        val izpadDomacih = domaciViri.any { it.izpad } || domaciStrani.any { !it.isDone }
        val izpadPreseka = presekViri.any { it.izpad }
        // Mreza iz predpomnilnika je ze na zaslonu: zamenjamo jo enkrat, ko odgovorijo vsi (delni odgovor bi kanale
        // pocasnega vira skril in cez nekaj sekund spet pokazal).
        // Naloga 82: seme z diska, ki ga uporabnik ne vidi (vsi kanali skriti - zaklenjen dodatek, mrtvi), ni mreza na
        // zaslonu: prvi odgovor gre takoj na zaslon (prej 30 s »Nalagam« nad prazno mrezo).
        val sproti = m.vidnih == 0
        val zacetek = android.os.SystemClock.uptimeMillis()
        var ostane = glavni.size
        var prvic = true
        var nedocakani = false
        val zbrano = ArrayList<List<Jamendo.Skladba>>()
        while (true) {
            if (m.preklicana) return
            if (ostane > 0) {
                // Naloga 86 (M3): po kosih do 1 s (prej en poll do 30 s) - preklic mreze nit sprosti takoj.
                val f = try { OsPravila.cakajPoKoscih(if (prvic && sproti) 8_000L else 30_000L, ura = { android.os.SystemClock.uptimeMillis() },
                    prekinjen = { m.preklicana }) { ms -> prihodi.poll(ms, java.util.concurrent.TimeUnit.MILLISECONDS) } } catch (_: InterruptedException) { return }
                if (m.preklicana) return
                if (f != null) {
                    ostane--; zbrano += try { f.get() } catch (_: Exception) { emptyList() }
                    // Kar pride skoraj hkrati, gre v isti izris.
                    try { Thread.sleep(150) } catch (_: InterruptedException) { return }
                    while (ostane > 0) { val g = prihodi.poll() ?: break; ostane--; zbrano += try { g.get() } catch (_: Exception) { emptyList() } }
                } else if (!(prvic && sproti)) { ostane = 0; nedocakani = true }   // pocasni viri niso odgovorili: ne cakamo vec
            }
            val zadnjic = ostane == 0
            if (!sproti && !zadnjic) continue
            val stran = prepleti(zbrano.toList()); zbrano.clear()
            val jePrvic = prvic
            prvic = false
            // Vir, ki tokrat ni odgovoril (izpad, ne prazna stran), ne sme umakniti kanalov, ki jih uporabnik ze vidi: kar je
            // mreza imela nazadnje in sveza tega nima, ostane za svezimi (izmerjeno 6. 10. 2026: isti katalog enkrat odgovori
            // v sekundi, drugic v 15 s ne - mreza je imela zato enkrat 20 kartic, drugic 17). Kateri delajo, pove preverjanje.
            val izpad = zadnjic && (nedocakani || izpadDomacih || izpadPreseka || neznaniZasebni.isNotEmpty() || glavni.any { it.izpad })
            android.util.Log.i("SafeerOsMedia", "kanali: zvrst=${m.zvrst.ifEmpty { "-" }}, jezik=${m.jezik.ifEmpty { "-" }}, katalogov=${katalogi.size}, virov=${glavni.size}, " +
                "presek=${smejo?.size ?: -1}, zasebnih=${ZasebniKanali.velikost()}, domaci=${domaci.size}, stran=${stran.size}, prvic=$jePrvic, zadnjic=$zadnjic, " +
                "izpad=$izpad, zasebni neznani=${neznaniZasebni.isNotEmpty()}, po ${android.os.SystemClock.uptimeMillis() - zacetek} ms")
            glavna.post {
                if (isFinishing || mrezaKanalov !== m) return@post
                val prejDomaci = if (jePrvic && izpad) m.domaci.toList() else emptyList()
                val prejKanali = if (jePrvic && izpad) m.kanali.toList() else emptyList()
                val odPrej: Set<String> = if (neznaniZasebni.isEmpty()) emptySet() else (prejDomaci + prejKanali).mapTo(HashSet()) { it.id }
                fun sme(k: Jamendo.Skladba) = neznaniZasebni.isEmpty() || k.id in odPrej || (Stremio.razstavi(k)?.first ?: "") !in neznaniZasebni
                if (jePrvic) {
                    m.glavni.clear(); m.glavni += glavni
                    m.presek = smejo != null
                    m.dovoljeni.clear(); smejo?.let { m.dovoljeni += it }
                    // Vir zvrsti ni odgovoril: kar je bilo nazadnje v tej izbiri, ostane dovoljeno.
                    if (izpadPreseka) m.dovoljeni += (prejDomaci + prejKanali).map { it.id }
                    m.domaci.clear(); m.kanali.clear(); m.videni.clear(); m.prejeti.clear()
                    m.rezerva.clear(); m.rezerva += rezerva
                    // Naloga 80: domaci kanali seznamov IPTV (drzava naprave, jezik vmesnika) so prvi, nato domaci dodatkov.
                    m.seznami.clear(); iptv?.first?.let { m.seznami += it }
                    iptv?.second?.let { dodajKanale(m, it, m.domaci) }
                    dodajKanale(m, domaci.filter { sme(it) }, m.domaci)
                }
                stran.forEach { m.prejeti += it.id }
                dodajKanale(m, stran.filter { sme(it) }, m.kanali)
                if (jePrvic) iptv?.third?.let { dodajKanale(m, it, m.kanali) }
                val ostalih = dodajKanale(m, prejDomaci, m.domaci) + dodajKanale(m, prejKanali, m.kanali)
                if (ostalih > 0) dnevnikMreze { "kanali: vir ni odgovoril, od prej ostane $ostalih kanalov" }
                if (zadnjic) {
                    m.nalagam = false
                    // Zadnja znana mreza gre na disk (ze brez zasebnih kategorij, [dodajKanale]) za naslednji zagon aplikacije.
                    val d = m.domaci.toList()
                    val k = m.kanali.toList()
                    val app = applicationContext
                    if (d.isNotEmpty() || k.isNotEmpty()) try {
                        delavec.execute { MedijskiPredpomnilnik.shraniPolice(app, kljucSemena, listOf(Triple("domaci", true, d), Triple("kanali", true, k))) }
                    } catch (_: java.util.concurrent.RejectedExecutionException) { }
                }
                KANALI_MREZE[m.kljuc] = m.domaci.toList() to m.kanali.toList()
                val y = drsnik.scrollY
                narisiKanale(m)
                if (y > 0) drsnik.post { drsnik.scrollTo(0, y) }
                // Zvrst in jezik hkrati: prva stran jezika ima lahko malo kanalov te zvrsti - sami gremo po naslednje.
                if (zadnjic && m.presek && m.seKaj && m.domaci.size + m.kanali.size < 24 && m.samodejno++ < 6) naloziVecKanalov(m, samodejno = true)
            }
            if (zadnjic) break
        }
    }

    /** Doda nove kanale v [cilj] (brez zasebnih kategorij; ob dveh izbirah samo presek); vrne, koliko jih je dodal. */
    private fun dodajKanale(m: MrezaKanalov, novi: List<Jamendo.Skladba>, cilj: MutableList<Jamendo.Skladba>): Int {
        var dodanih = 0
        for (k in novi) {
            // Naloga 73: meja kanalov v pomnilniku mreze (ostalo z »Naloži več« ni vec potrebno - mreza jih ze ima dovolj).
            if (m.domaci.size + m.kanali.size >= KanaliPravila.NAJVEC_KANALOV_V_MREZI) break
            // Naloga 80: kanal seznama IPTV je ze izbran po zvrsti in jeziku (presek velja za kataloge dodatkov).
            if (ZasebniKanali.jeZaseben(k.id) || (m.presek && !IptvSeznami.jeKanal(k) && k.id !in m.dovoljeni) || !m.videni.add(k.id)) continue
            cilj += k; dodanih++
        }
        return dodanih
    }

    private fun naloziVecKanalov(m: MrezaKanalov, samodejno: Boolean = false) {
        if (mrezaKanalov !== m || m.nalagam || !m.seKaj || isFinishing) return
        m.nalagam = true
        m.zadnjicVec = android.os.SystemClock.uptimeMillis()
        if (!samodejno) m.samodejno = 0
        stanje.text = getString(R.string.os_glasba_nalagam)
        // Naloga 80 (53c): glavni viri so izcrpani - naslednji iz rezerve (po 4), da se okno napolni brez cakanja uporabnika.
        if (m.glavni.none { !it.konec } && m.rezerva.isNotEmpty()) { val n = m.rezerva.take(4); m.rezerva.subList(0, n.size).clear(); m.glavni += n }
        val app = applicationContext
        val loc = resources.configuration.locales[0]
        try { delavec.execute {
            val nove = prepleti(m.glavni.filter { !it.konec }.map { v -> kanaliDelavec.submit<List<Jamendo.Skladba>> { if (m.preklicana) emptyList() else stranKanalov(v) }.also { m.naloge += it } }
                .map { f -> try { f.get(25, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { emptyList() } })
            // Naloga 80: naslednja stran seznamov IPTV z diska (domaci k domacim).
            val seznamDomaci = m.seznami.filter { it.domaci && !it.konec }.flatMap { IptvSeznami.naslednja(app, it, loc.country, loc.language, OKNO_KANALOV) }
            val seznamOstali = m.seznami.filter { !it.domaci && !it.konec }.flatMap { IptvSeznami.naslednja(app, it, loc.country, loc.language, OKNO_KANALOV) }
            glavna.post {
                m.nalagam = false
                if (isFinishing || mrezaKanalov !== m) return@post
                // Stran brez novih kanalov: katalog strani ne pozna (vraca isto) - konec, gumb izgine.
                if (nove.count { m.prejeti.add(it.id) } == 0) m.glavni.forEach { it.konec = true }
                val dodanih = dodajKanale(m, nove, m.kanali) + dodajKanale(m, seznamDomaci, m.domaci) + dodajKanale(m, seznamOstali, m.kanali)
                m.zadnjicVec = android.os.SystemClock.uptimeMillis()
                // Mreza se narise znova: ostanemo tam, kjer smo bili, ne na vrhu.
                val y = drsnik.scrollY
                narisiKanale(m)
                drsnik.post(object : Runnable {
                    var poskusi = 0
                    override fun run() {
                        if (mrezaKanalov !== m) return
                        if (vsebina.height >= y + drsnik.height || poskusi++ > 30) drsnik.scrollTo(0, y) else drsnik.postDelayed(this, 50)
                    }
                })
                if (m.presek && m.seKaj && dodanih < 12 && m.samodejno++ < 6) naloziVecKanalov(m, samodejno = true)
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { m.nalagam = false }
    }

    private fun imeKategorije(kljuc: String) = IMENA_KATEGORIJ[kljuc]?.let { getString(it) } ?: kljuc

    /** Uporabnik hoce vec kanalov (gumb, drsenje proti koncu): najprej podaljsamo okno po ze nalozenih, nato naslednja stran. */
    private fun vecKanalov(m: MrezaKanalov) {
        if (mrezaKanalov !== m || m.nalagam || isFinishing) return
        m.zadnjicVec = android.os.SystemClock.uptimeMillis()
        m.proracun = PRORACUN_KANALOV
        m.samodejno = 0
        m.brezOdgovora.set(0)
        if (m.naprej) { m.okno += OKNO_KANALOV; narisiKanale(m); return }
        if (m.seKaj) { m.okno += OKNO_KANALOV; naloziVecKanalov(m) }
    }

    /**
     * Preverjanje kanalov mreze v ozadju: samo pri dodatku, iz katerega je kanal. Naloga 74: nizka prednost (niti v
     * ozadju, [KANALI_PREVERJAM_HKRATI] hkrati); kanali so na zaslonu ze prej, preverjanje samo umakne dokazano mrtve.
     */
    private val preverjanjeKanalov = Executors.newFixedThreadPool(KANALI_PREVERJAM_HKRATI) { r ->
        Thread { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }.apply { name = "safeer-kanali-prev"; isDaemon = true }
    }
    private val kanaliVPreverjanju: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    /** Naloga 74: do tega casa (uptime) ima uporabnikov klik na kanal prednost - preverjanje v ozadju pocaka. */
    @Volatile private var prednostKlikaDo = 0L

    private fun preveriKanal(m: MrezaKanalov, sk: Jamendo.Skladba) {
        val k = OsPravila.kljucKanala(sk.id)
        if (m.proracun <= 0 || !kanaliVPreverjanju.add(k)) return
        m.proracun--
        val app = applicationContext
        try {
            // Naloga 74: v vrsti so najvec [KANALI_PREVERJAM_HKRATI] ([preveriVidneKanale] jih da le toliko, kolikor je prostih
            // niti); odhod iz razdelka ali druga izbira (mrezaKanalov != m, preklicana) jih ob zacetku preskoci. Ne preklicemo
            // jih s cancel: preklicana naloga v vrsti ne pride do finally in kljuc bi ostal v kanaliVPreverjanju.
            preverjanjeKanalov.execute {
                var odlozeno = 0L
                try {
                    // Samo dokler uporabnik gleda to mrezo.
                    if (mrezaKanalov !== m || m.preklicana || isFinishing || !vOspredju) {
                        dnevnikMreze { "kanal: preverjanje preskoceno (druga mreza=${mrezaKanalov !== m}, v ospredju=$vOspredju)" }
                        return@execute
                    }
                    // Uporabnik je pravkar kliknil kanal: njegov tok ima omrezje zase, to preverjanje pride pozneje.
                    odlozeno = prednostKlikaDo - android.os.SystemClock.uptimeMillis()
                    if (odlozeno > 0) return@execute
                    // Dodatek mora imeti tok IN streznik toka mora s te naprave odgovoriti ([SondaToka]). Naloga 75: ista preverba
                    // kot pregled dodatka - casovna omejitev je negotova, mrtev sele po dveh zaporednih ([MrtviKanali]).
                    val od = android.os.SystemClock.uptimeMillis()
                    val (izid, dodatekOdgovoril) = PregledKanalov.preveri(app, sk)
                    dnevnikMreze { "kanal preverjen: izid=$izid po ${android.os.SystemClock.uptimeMillis() - od} ms, dodatek odgovoril=$dodatekOdgovoril" }
                    if (izid == null) m.neznani += k
                    if (m.preklicana) return@execute
                    // Dodatek, ki zapored ne odgovarja (izmerjeno 5. 10. 2026: po ducatu vprasanj vsako visi 15 s), nehamo
                    // sprasevati do uporabnikovega naslednjega dejanja (»Naloži več«).
                    if (dodatekOdgovoril) m.brezOdgovora.set(0)
                    else if (m.brezOdgovora.incrementAndGet() >= BREZ_ODGOVORA_NAJVEC) glavna.post { m.proracun = 0 }
                } finally {
                    kanaliVPreverjanju.remove(k)
                    val cez = odlozeno
                    glavna.post {
                        if (cez > 0) m.proracun++     // odlozeno ni porabljeno
                        // Naloga 74: dopolnimo mrezo, ki je ZDAJ na zaslonu. Prej le mrezo, ki je preverjanje narocila: ce jo je
                        // medtem zamenjala nova (zasebne kategorije znane -> odpriKanale), je nova cakala na kanale v teku
                        // (kanaliVPreverjanju) in se ni nikoli vec dopolnila - mreza je ostala prazna z »Nalagam ...«.
                        val zdaj = mrezaKanalov ?: return@post
                        if (cez > 0) glavna.postDelayed({ narociPreverjanje(zdaj) }, cez) else narociKanale(zdaj)
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { kanaliVPreverjanju.remove(k); m.proracun++ }
    }

    /** Mrezo kanalov dopolnimo najvec enkrat na slabe pol sekunde, ne glede na to, koliko odgovorov pride vmes. */
    private fun narociKanale(m: MrezaKanalov) {
        if (m.risanjeNaroceno || mrezaKanalov !== m || isFinishing) return
        m.risanjeNaroceno = true
        glavna.postDelayed({
            m.risanjeNaroceno = false
            if (mrezaKanalov === m && !isFinishing) narisiKanale(m)
        }, 400)
    }

    /** Naloga 74: preverjanje vidnih kartic najvec enkrat na cetrt sekunde (drsenje, risanje, odgovori). */
    private fun narociPreverjanje(m: MrezaKanalov) {
        if (m.preverjanjeNaroceno || mrezaKanalov !== m || isFinishing) return
        m.preverjanjeNaroceno = true
        glavna.postDelayed({ m.preverjanjeNaroceno = false; preveriVidneKanale(m) }, 250)
    }

    /**
     * Naloga 74: v ozadju preverimo samo kanale iz dodatkov na kartah, ki so na zaslonu, in v naslednji vrsti
     * ([KanaliPravila.vidneVrste]) - ne vseh nalozenih (TV je sibak). Kartica, ki jo preverjanje dokaze kot mrtvo, izgine
     * ob naslednjem risanju; ostale ostanejo na svojih mestih ([OsPravila.stabilenRed]).
     */
    private fun preveriVidneKanale(m: MrezaKanalov) {
        if (mrezaKanalov !== m || m.preklicana || isFinishing || !vOspredju || m.proracun <= 0 || !smemPreverjati()) return
        val nm = narisanaMreza
        if (nm == null || nm.lastnik !== m) {
            // Prvo risanje te mreze se tece (po korakih): pocakamo nanj.
            if (risemMrezo === m) glavna.postDelayed({ narociPreverjanje(m) }, 300)
            return
        }
        val prostih = KANALI_PREVERJAM_HKRATI - kanaliVPreverjanju.size
        if (prostih <= 0) return
        val vrste = KanaliPravila.vidneVrste(nm.vrstice.map { it.top }, drsnik.scrollY, drsnik.height)
        val izbrani = KanaliPravila.zaPreverjanje(nm.kljuci, vrste, nm.naVrsto, prostih) { id ->
            // Kartica je ziva, ce deluje katera koli razlicica: preverimo prvo se neznano ([razlicicaZaPreverjanje]).
            val sk = razlicicaZaPreverjanje(m, id)
            val kk = sk?.let { OsPravila.kljucKanala(it.id) }
            // Lastnik, 26. 10. 2026: kanal vira z racunom (Xtream, dodatek z zetonom) se ne preverja - viden je, odloci predvajanje.
            sk != null && id !in m.vedno && ((Stremio.jeEnota(sk) && Stremio.dajeLastneTokove(sk)) || IptvSeznami.jeKanal(sk)) && kk !in m.neznani &&
                kk !in kanaliVPreverjanju && PregledKanalov.smeSonda(this, sk)
        }
        if (izbrani.isNotEmpty()) dnevnikMreze { "kanali: preverjam vidne ${izbrani.size} (vrste ${vrste.first}..${vrste.last}, v teku ${kanaliVPreverjanju.size}, proracun ${m.proracun})" }
        izbrani.forEach { id -> razlicicaZaPreverjanje(m, id).let { sk -> if (sk != null) preveriKanal(m, sk) } }
    }

    /** Priljubljeni kanali v zivo (vgrajeni, seznamov IPTV, dodatkov) po uporabnikovem vrstnem redu ([PriljubljeniKanaliPravila]). */
    private fun priljubljeniKanali(): List<Jamendo.Skladba> =
        MedijskiViri.priljubljene(this).filter { PriljubljeniKanaliPravila.jeKanal(it.id, Stremio.jeVZivo(it)) }

    /** Priljubljeni kanali, ki jih mreza pokaze - isti filter kot [narisiKanale] (oznaceni kot nedelujoci izpadejo). */
    private fun vidniPriljubljeni(): List<Jamendo.Skladba> =
        priljubljeniKanali().filter { !znanNedelujocKanal(it) && !MrtviKanali.jeMrtev(this, it.id) }

    /** Naloga 90d: kanal seznama IPTV, katerega tok je znano samo obvestilo ([KanaliPravila.znanNedelujocTok]). */
    private fun znanNedelujocKanal(sk: Jamendo.Skladba) = IptvSeznami.jeKanal(sk) && KanaliPravila.znanNedelujocTok(sk.zvok)

    /**
     * Naloga 120: vsebina dodatka, ki je uporabnik nima odklenjene (samo placilni zid ali zaklenjen dodatek kanalov), se ne
     * kaze nikjer v Medijskem centru - razen ce uporabnik v TV v zivo izbere »Pokazi zaklenjene«.
     */
    private fun neodklenjena(sk: Jamendo.Skladba) = Stremio.neodklenjena(sk, tvZaklenjeni)

    /** Razlicica kartice [id], ki jo preverimo zdaj (mrtve izpustimo, najprej HD); null, ce nobena ne potrebuje preverjanja. */
    private fun razlicicaZaPreverjanje(m: MrezaKanalov, id: String): Jamendo.Skladba? {
        val vse = m.razlicice[id].orEmpty()
        val i = KanaliPravila.razlicicaZaPreverjanje(vse.map { it.naslov }, vse.map { MrtviKanali.stanje(this, it.id) })
        return if (i == null) null else vse[i]
    }

    private fun narisiKanale(m: MrezaKanalov) {
        if (mrezaKanalov !== m || isFinishing) return
        // Prvo risanje te mreze se tece (kartice nastajajo po korakih): dopolnitev pocaka nanj, da jo opravimo na mestu.
        // Sicer bi se mreza takoj narisala znova (izmerjeno 6. 10. 2026: kanali z diska so prisli 0,17 s po odprtju).
        // Naloga 88: varovalka - risanje, ki se ni koncalo v [OsPravila.ODLOZI_RISANJE_MS], ne zadrzuje vec dopolnitev.
        val zdajMs = android.os.SystemClock.uptimeMillis()
        if (risemMrezo === m && OsPravila.odloziDopolnitev(m.cakaRisanjeOd.takeIf { it > 0 } ?: zdajMs.also { m.cakaRisanjeOd = it }, zdajMs)) {
            if (!m.cakaNaRisanje) {
                m.cakaNaRisanje = true
                glavna.postDelayed({ m.cakaNaRisanje = false; if (mrezaKanalov === m && !isFinishing) narisiKanale(m) }, 40)
            }
            return
        }
        if (risemMrezo === m) { android.util.Log.w("SafeerOsMedia", "kanali: prvo risanje se ni koncalo, dopolnim brez cakanja"); risemMrezo = null }
        m.cakaRisanjeOd = 0L
        val brezIzbire = m.zvrst.isEmpty() && m.jezik.isEmpty()
        val krajevno = resources.configuration.locales[0]
        val vgrajeni = (if (!jeVirViden(TV_V_ZIVO, VIR_TV)) emptyList() else try { TvVZivo.kanali() } catch (_: Exception) { emptyList() })
            .filter { (m.zvrst.isEmpty() || it.zvrst == m.zvrst) && (m.jezik.isEmpty() || it.jezik == m.jezik) }
        // Priporoceno: priljubljeni, uradni kanali uporabnikove drzave in jezika, kanali dodatkov v njegovem jeziku, ostali uradni, nato vse ostalo.
        val (doma, drugod) = vgrajeni.partition { it.drzava == krajevno.country.uppercase(Locale.ROOT) || it.jezik == krajevno.language }
        // 25. 10. 2026: tudi priljubljeni kanali seznamov IPTV (prej samo vgrajeni in dodatkov) - po uporabnikovem redu.
        val priljubljeni = if (brezIzbire || tvSamoPriljubljeni) priljubljeniKanali() else emptyList()
        var urejeni = (priljubljeni + doma.map { it.skladba } + m.domaci + drugod.map { it.skladba } + m.kanali).distinctBy { it.id }
        if (razvrstitev(TV_V_ZIVO) == RAZVRSTI_IME_AZ) {
            val collator = Collator.getInstance(krajevno).apply { strength = Collator.SECONDARY }
            urejeni = urejeni.sortedWith { a, b -> collator.compare(a.naslov, b.naslov) }
        }
        // Naloga 74 (lastnik: »Takoj pokazi, preveri v ozadju/ob kliku«): kanal iz dodatka je na zaslonu takoj (prvih
        // [MrezaKanalov.okno]); izgine samo, ce preverjanje v ozadju ([preveriVidneKanale]) ali klik dokaze, da je mrtev.
        // Prej je cakal na dokaz prenosa: pri IPTV je veliko mrtvih tokov (casovne omejitve), mreza je bila minute prazna.
        // Vgrajeni kanali in priljubljeni so vedno na zaslonu. Na omejenem omrezju ne preverjamo (pokazemo vse).
        val preverjam = smemPreverjati()
        val vednoId = priljubljeni.map { it.id }.toSet()
        m.vedno = vednoId
        val vsi = ArrayList<Jamendo.Skladba>(urejeni.size)
        var izDodatkov = 0
        var naprej = false
        var brezProstih = 0
        val narisaniZdaj = m.narisani.toHashSet()
        // Razlicica kartice, ki je ze na zaslonu, ostane (tudi izven okna): ko je glava kartice mrtva, kartico prevzame zivi
        // SD/HD brat, kartica pa ne izgine.
        val razliceNarisanih = HashSet<String>()
        for (id in narisaniZdaj) m.razlicice[id]?.forEach { razliceNarisanih += it.id }
        for (sk in urejeni) {
            // Kanal iz dodatka, ki je samo katalog (tok da drug dodatek), pri njegovem dodatku ni preverljiv: v mrezi je
            // brez preverjanja, kot doslej.
            // Naloga 80: kanal seznama IPTV se preverja enako (sonda toka, brez dodatka).
            val preverljiv = preverjam && sk.id !in vednoId && ((Stremio.jeEnota(sk) && Stremio.dajeLastneTokove(sk)) || IptvSeznami.jeKanal(sk))
            // Vgrajeni in priljubljeni kanali so vedno v mrezi - tudi, ko je okno kanalov dodatkov polno.
            // Naloga 79: dodatek daje tokove samo z narocnino - njegovih kanalov ne pokazemo vnaprej (prej so se pokazali in
            // po preverjanju drug za drugim izginili). Isti kanal iz drugega dodatka (po kljucu imena) je njegova kartica.
            // Naloga 79b: v izbiri »Pokazi zaklenjene kanale« (privzeto izklopljeno) ostanejo; klik ponudi odklep pri dodatku.
            // Naloga 82: tudi po izteku sklepa brez zivega toka ([Stremio.zaklenjenKanalZaMrezo]).
            if (Stremio.jeEnota(sk) && sk.id !in vednoId && neodklenjena(sk)) { brezProstih++; continue }
            // Naloga 90d: znan tok z obvestilom namesto programa - brez sporocila ni v mrezi; druga razlicica kartice ostane.
            if (znanNedelujocKanal(sk)) continue
            if (!preverljiv) { if (!MrtviKanali.jeMrtev(this, sk.id)) vsi += sk; continue }
            // Dokazano mrtev (preverjanje, klik, napaka predvajanja) izgine; ziv in neznan ostaneta.
            if (MrtviKanali.stanje(this, sk.id) == false) continue
            // Kar je ze narisano, ostane - ne glede na vrstni red virov in okno (izmerjeno 6. 10. 2026 na televizorju: ob
            // vrnitvi v mrezo so kanali za 15 s izginili, ker je svez seznam prednje postavil druge).
            if (sk.id in narisaniZdaj || sk.id in razliceNarisanih) { vsi += sk; izDodatkov++; continue }
            if (naprej) continue
            if (izDodatkov >= m.okno) { naprej = true; continue }
            // Novi pridejo za ze narisane ([OsPravila.stabilenRed]): preverjanje jih ne more premakniti.
            vsi += sk; izDodatkov++
        }
        // Kar uporabnik ze vidi, ostane na svojem mestu; novo preverjeni kanali pridejo za tem (pri razvrstitvi po imenu
        // velja abeceda). Brez tega bi se kanali dodatkov vrivali med ze narisane vgrajene.
        val vsiPoVrsti: List<Jamendo.Skladba> = vsi
        // Priljubljeni kanali so vedno spredaj (tudi tisti, ki ga je uporabnik pravkar dodal med priljubljene), po vrstnem redu,
        // ki ga uporabnik doloci s »Premakni« ([PriljubljeniKanaliPravila]) - tudi pri razvrstitvi po imenu. Izbira
        // »Priljubljeni« pokaze samo njih.
        val ostali = vsiPoVrsti.filterNot { it.id in vednoId }
        val ostaliPoVrsti = if (razvrstitev(TV_V_ZIVO) == RAZVRSTI_IME_AZ) ostali else OsPravila.stabilenRed(m.narisani, ostali) { it.id }
        val poId = vsiPoVrsti.associateBy { it.id }
        val prikaz = PriljubljeniKanaliPravila.prikaz(priljubljeni.map { it.id }, vsiPoVrsti.filter { it.id in vednoId }.map { it.id } + ostaliPoVrsti.map { it.id },
            tvSamoPriljubljeni).mapNotNull { poId[it] }
        m.naprej = naprej
        // Naloga 80 (53b): meritev - cas od odprtja mreze do prvega izrisa kanalov dodatkov ali seznamov (logcat SafeerOsMedia).
        if (!m.izmerjeno && prikaz.any { Stremio.jeEnota(it) || IptvSeznami.jeKanal(it) }) {
            m.izmerjeno = true
            android.util.Log.i("SafeerOsMedia", "kanali: prvi izris ${prikaz.size} kanalov po ${android.os.SystemClock.uptimeMillis() - m.odprtOb} ms od odprtja (${m.izvor})")
        }
        dnevnikMreze { "kanali: vseh=${urejeni.size}, v mrezi=${prikaz.size}, iz dodatkov=$izDodatkov, naprej=${m.naprej}, " +
            "v teku=${kanaliVPreverjanju.size}, brez odgovora=${m.neznani.size}, proracun=${m.proracun}, " +
            "preverjam=$preverjam, nalagam=${m.nalagam}, se kaj=${m.seKaj}" }
        // Naloga 74: »Nalagam« samo, dokler na zaslonu ni nobenega kanala in se viri se nalagajo; preverjanje v ozadju
        // napisa nima (prej je ostal, ceprav so bili kanali ze na zaslonu).
        val napisCakanja = KanaliPravila.napisCakanja(prikaz.size, m.nalagam, m.zasebniCakajo, stremioNaslovi().isNotEmpty())
        val cakamDodatke = napisCakanja != KanaliPravila.NapisCakanja.NIC
        // Naloga 73: noben vir ni odgovoril in mreza je prazna - ne »Nalagam ...« za vedno: povemo in sami poskusimo znova,
        // ko premor ali proracun dodatkov to dovoli. Kanali z diska (seme) in vgrajeni ostanejo, ce so.
        val viriTiho = !cakamDodatke && !m.nalagam && prikaz.isEmpty() && m.glavni.isNotEmpty() && m.glavni.all { it.izpad }
        if (viriTiho && !m.ponovitev) {
            m.ponovitev = true
            val premor = (Stremio.premorKoncaOb(stremioNaslovi()) - System.currentTimeMillis()).coerceAtLeast(0L)
            val zamik = KanaliPravila.zamikPonovitve(premor, poskusiKanalov++)
            android.util.Log.i("SafeerOsMedia", "kanali: noben vir ni odgovoril, znova cez ${zamik / 1000} s (poskus $poskusiKanalov)")
            glavna.postDelayed({ if (mrezaKanalov === m && !isFinishing && vOspredju) odpriKanale(m.zvrst, m.jezik) }, zamik)
            m.cezMinut = ((zamik + 59_999) / 60_000).toInt()
        }
        if (prikaz.isNotEmpty()) poskusiKanalov = 0
        m.vidnih = prikaz.size
        // Naloga 82: prazna mreza - nadomestek (kanali brez izbire) takoj, ko nic vec ne pricakujemo, med nalaganjem po 3 s;
        // »Nalagam« nikoli dlje od 45 s ([KanaliPravila.praznaMreza]).
        val nadomestni = if (m.zvrst.isEmpty() && m.jezik.isEmpty()) emptyList() else m.nadomestni.filter { sk ->
            !MrtviKanali.skrij(this, sk) && !neodklenjena(sk)
        }
        val prazna = KanaliPravila.praznaMreza(prikaz.size, cakamDodatke || m.nalagam,
            android.os.SystemClock.uptimeMillis() - m.odprtOb, nadomestni.isNotEmpty())
        val opis = when {
            prazna == KanaliPravila.PraznaMreza.NADOMESTEK -> getString(R.string.os_tv_nadomestek_vsi)
            prazna == KanaliPravila.PraznaMreza.PRAZNO && cakamDodatke -> getString(R.string.os_tv_ni_kanalov)
            // Naloga 76: mreza caka samo se na zasebne kategorije dodatkov (prvi pregled po namestitvi).
            napisCakanja == KanaliPravila.NapisCakanja.KATEGORIJE -> getString(R.string.os_tv_preverjam_kategorije)
            cakamDodatke -> getString(R.string.os_glasba_nalagam)
            viriTiho -> getString(R.string.os_tv_viri_ne_odgovarjajo, m.cezMinut.coerceAtLeast(1))
            prikaz.isEmpty() && brezSpletnihVirov() -> getString(R.string.os_media_brez_virov)
            prikaz.isEmpty() && brezProstih > 0 -> getString(R.string.os_mediji_tokovi_narocnina)
            prikaz.isEmpty() -> getString(R.string.os_tv_ni_kanalov)
            else -> ""
        }
        var samNalagam = false
        // Vsi nalozeni kanali so razreseni, okno pa se ni polno: sami gremo po naslednjo stran (najvec trikrat na dejanje).
        // Naloga 80 (53c): dokler okno ni polno, dopolnjujemo iz naslednjih virov sami (do 8-krat na dejanje, prej 3).
        if (!tvSamoPriljubljeni && !m.naprej && !m.nalagam && m.seKaj && preverjam && izDodatkov < m.okno && m.samodejno < (if (prikaz.size < m.okno) 8 else 3)) {
            m.samodejno++
            samNalagam = true
            glavna.post { naloziVecKanalov(m, samodejno = true) }
        }
        // Gumb »Naloži več« sele, ko so odgovorili vsi viri prve strani (prej dotik ne bi naredil nicesar).
        // Kadar gremo po naslednjo stran sami, gumba ni (prej se je za hip pokazal in izginil - izmerjeno 5. 10. 2026).
        val vec = !tvSamoPriljubljeni && (m.seKaj || m.naprej) && !m.nalagam && !samNalagam && prikaz.isNotEmpty()
        val napisi = napisiIzbirKanalov(m)
        val kazemNadomestek = prazna == KanaliPravila.PraznaMreza.NADOMESTEK
        val podpis = "${prikaz.map { it.id }.hashCode()}|$vec|$opis|$napisi|${razvrstitev(TV_V_ZIVO)}|${vsebina.width}|" +
            (if (kazemNadomestek) nadomestni.map { it.id }.hashCode() else 0) + "|$prazna"
        if (podpis == m.podpis) { if (stanje.text.toString() != opis) stanje.text = opis; narociPreverjanje(m); return }
        m.podpis = podpis
        m.narisani = prikaz.map { it.id }
        KANALI_RED["${m.kljuc}|${razvrstitev(TV_V_ZIVO)}"] = m.narisani
        // (tvIkone ne praznimo: pogledi vgrajenih kanalov ostanejo isti in morajo ostati prijavljeni za osvezitev ikon.)
        val kartice = videi(prikaz, razlicice = m.razlicice) + (if (vec) listOf(Kartica(getString(R.string.os_media_nalozi_vec), "", "",
            { vecKanalov(m) }, ikona = R.drawable.os_ikona_plus)) else emptyList()) +
            // Naloga 78: izbrana zvrst ali jezik je izpraznila mrezo - kartica, ki filter odstrani (pred Dodaj vir).
            // Naloga 82: tudi med nalaganjem, ko je cas za nadomestek ([KanaliPravila.praznaMreza]).
            (if (prikaz.isEmpty() && (prazna != KanaliPravila.PraznaMreza.CAKAJ || !cakamDodatke && !m.nalagam) && (m.zvrst.isNotEmpty() || m.jezik.isNotEmpty())) listOf(Kartica(getString(R.string.os_media_pokazi_vse),
                getString(R.string.os_media_pokazi_vse_opis), "", { odpriKanale("", "", m.uporabnik) }, ikona = R.drawable.os_ikona_mreza)) else emptyList()) +
            // Naloga 82: nadomestek - kanali brez izbire (zadnja znana mreza »vse«), za kartico »Pokazi vse«.
            (if (kazemNadomestek) videi(nadomestni, razlicice = m.razlicice) else emptyList()) +
            (if (prikaz.isEmpty() && (prazna == KanaliPravila.PraznaMreza.PRAZNO || !cakamDodatke && !m.nalagam)) listOf(karticaDodajVir()) else emptyList())
        pokaziMrezo(m, Vrsta("", kartice, video = true, mreza = true, kanali = true), opis,
            "${m.zvrst}|${m.jezik}|${razvrstitev(TV_V_ZIVO)}|$napisi|${ozekZaslon()}") {
            (if (vlc) emptyList() else listOf(razdelkiVrstica(TV_V_ZIVO))) + listOf(kanaliIzbire(m))
        }
        osveziIkoneVgrajenih()
        narociPreverjanje(m)
    }

    /**
     * Izbire mreze kanalov: [Vse zvrsti ▾] [Vsi jeziki ▾] [Priporoceno ▾]. Moznosti dajo katalogi dodatkov in vgrajeni
     * kanali; dotik jih prebere sveze ([PoljeIzbire.sveze]), zato glave ni treba risati znova, ko katalogi odgovorijo
     * (izmerjeno 6. 10. 2026 na televizorju: polno risanje 0,7 s po prvem odprtju mreze).
     */
    private fun kanaliIzbire(m: MrezaKanalov): View =
        izbireVrstica(poljaKanalov(m).mapIndexed { i, p -> PoljeIzbire(p.kljuc, p.moznosti, p.izbrana, p.naslovOkna, { poljaKanalov(m)[i] }, p.obIzbiri) })

    /** Napisi na izbirah mreze kanalov: glava mreze se narise znova samo, ko se spremeni kateri od njih. */
    private fun napisiIzbirKanalov(m: MrezaKanalov): String =
        poljaKanalov(m).joinToString("|") { it.moznosti.getOrElse(it.izbrana) { "" } }

    private fun poljaKanalov(m: MrezaKanalov): List<PoljeIzbire> {
        val f = FILTRI_KANALOV
        val vgrajeni = if (!jeVirViden(TV_V_ZIVO, VIR_TV)) emptyList() else try { TvVZivo.kanali() } catch (_: Exception) { emptyList() }
        // Zvrsti: znane kategorije po stalnem vrstnem redu, nato skupine kanalov z imenom, kot ga poda dodatek, na koncu »Ostalo«.
        val znane = (f?.zvrsti.orEmpty().filter { it.ime.isEmpty() }.map { it.kljuc } + vgrajeni.map { it.zvrst }).toSet()
        val zvrsti = KanaliPravila.KLJUCI_KATEGORIJ.filter { it in znane && it != "ostalo" }.map { it to imeKategorije(it) } +
            f?.zvrsti.orEmpty().filter { it.ime.isNotEmpty() }.map { it.kljuc to it.ime } +
            (if ("ostalo" in znane) listOf("ostalo" to imeKategorije("ostalo")) else emptyList())
        // Naloga 91b: jeziki so takoj na voljo - zadnji znani jeziki katalogov z diska in jeziki prenesenih seznamov IPTV (.info).
        // Naloga 100 (pregled 231, S5): nastavitve preberemo enkrat na proces, potem iz pomnilnika (polja se gradijo pogosto).
        val shranjeni = SHRANJENI_JEZIKI_KANALOV ?: getSharedPreferences(NASTAVITVE_POGLEDA, MODE_PRIVATE).getString(JEZIKI_KANALOV, "").orEmpty()
            .split(',').filter { it.isNotBlank() }.also { SHRANJENI_JEZIKI_KANALOV = it }
        // Naloga 99 (N5): samo iz pomnilnika - jezike napolni priprava seznamov v ozadju ([naloziKanale]); izbira jih bere sveze.
        val izSeznamov = seznamiTv().flatMap { IptvSeznami.podatkiIzPomnilnika(it)?.jeziki.orEmpty() }
        val jeziki = jezikiIzbire(KanaliPravila.jezikiKanalov(f?.jeziki?.map { it.kljuc }, shranjeni, izSeznamov + vgrajeni.map { it.jezik }))
        val nacini = listOf(RAZVRSTI_PRIPOROCENO to R.string.os_media_razvrsti_priporoceno, RAZVRSTI_IME_AZ to R.string.os_media_razvrsti_ime)
        // Pregled 25. 10. (N8): v nacinu »Priljubljeni« zvrst in jezik ne veljata (pokazejo se vsi priljubljeni kanali) -
        // njunih izbir zato ne kazemo; ob »Vsi kanali« sta spet tu, z izbiro, ki je bila.
        return (if (tvSamoPriljubljeni) emptyList() else listOf(
            PoljeIzbire("izb:zvrst", listOf(getString(R.string.os_zvrst_vse)) + zvrsti.map { it.second }, zvrsti.indexOfFirst { it.first == m.zvrst } + 1, null) { i ->
                odpriKanale(if (i == 0) "" else zvrsti[i - 1].first, m.jezik, m.uporabnik)
            },
            PoljeIzbire("izb:jezik", listOf(getString(R.string.os_jezik_vsi)) + jeziki.map { it.second }, jeziki.indexOfFirst { it.first == m.jezik } + 1,
                getString(R.string.os_jezik_vsebine)) { i ->
                odpriKanale(m.zvrst, if (i == 0) "" else jeziki[i - 1].first, m.uporabnik)
            },
        )) + listOf(
            PoljeIzbire("izb:razvrsti", nacini.map { getString(it.second) }, nacini.indexOfFirst { it.first == razvrstitev(TV_V_ZIVO) }.coerceAtLeast(0),
                getString(R.string.os_media_razvrsti)) { i ->
                razvrstitve[TV_V_ZIVO] = nacini[i].first
                m.narisani = emptyList()
                m.razlicice.clear()
                narisiKanale(m)
            },
        ) + run {
            // 25. 10. 2026 (lastnik): izbira »Priljubljeni (N)« - samo priljubljeni kanali; ko jih ni, izbire ni.
            // Pregled N7: N = kartice, ki jih mreza res pokaze (brez kanalov, oznacenih kot nedelujoci).
            val priljubljenih = vidniPriljubljeni().size
            if (priljubljenih == 0 && !tvSamoPriljubljeni) emptyList() else listOf(PoljeIzbire("izb:prilj",
                listOf(getString(R.string.os_tv_vsi_kanali), getString(R.string.os_tv_priljubljeni_n, priljubljenih)), if (tvSamoPriljubljeni) 1 else 0, null) { i ->
                if (tvSamoPriljubljeni != (i == 1)) {
                    tvSamoPriljubljeni = i == 1
                    m.narisani = emptyList()
                    narisiKanale(m)
                }
            })
        } + run {
            // Naloga 79b: zaklenjeni kanali (dodatek daje tokove samo z narocnino) so privzeto skriti.
            // Naloga 90d: kanale, ki jih je uporabnik skril (»Kanal ne deluje - skrij«), vrne tretja izbira.
            val skritih = MrtviKanali.steviloSkritih(this)
            // Naloga 122 (lastnik): izbire »Ne delujejo (N)« (108) ni vec - nedelujocih kanalov uporabnik ne vidi nikjer;
            // oznaka mrtvega kanala potece sama in pregled kanalov (75) jih preverja v ozadju.
            val zaklenjeni = tvZaklenjeni || stremioNaslovi().any { Stremio.zaklenjenZaMrezo(it) }
            if (!zaklenjeni && skritih == 0) emptyList() else {
                val moznosti = ArrayList<Pair<String, () -> Unit>>()
                moznosti += getString(R.string.os_tv_brez_zaklenjenih) to { tvZaklenjeni = false; odpriKanale(m.zvrst, m.jezik, m.uporabnik) }
                moznosti += getString(R.string.os_tv_pokazi_zaklenjene) to { tvZaklenjeni = true; odpriKanale(m.zvrst, m.jezik, m.uporabnik) }
                if (skritih > 0) moznosti += getString(R.string.os_tv_vrni_skrite, skritih) to { MrtviKanali.vrniSkrite(this); odpriKanale(m.zvrst, m.jezik, m.uporabnik) }
                listOf(PoljeIzbire("izb:zaklenjeni", moznosti.map { it.first }, if (tvZaklenjeni) 1 else 0, null) { i -> moznosti.getOrNull(i)?.second?.invoke() })
            }
        }
    }

    // ------------------------------------------------------------------ Radio in Glasba: mreza z izbirami

    /**
     * Odprta mreza razdelka Radio ali Glasba ([odpriMrezo]): izbire na vrhu, pod njimi mreza; naslednja stran pride ob
     * drsenju proti koncu. [viri]: katalogi uporabnikovih dodatkov te mreze (vsak ve, koliko je ze dal).
     */
    private class MrezaVsebine(val razdelek: Int, val kljuc: String) {
        val vsi = ArrayList<Jamendo.Skladba>()
        val videni = HashSet<String>()
        @Volatile var viri: List<VirKanalov> = emptyList()
        @Volatile var stran = 0
        /** Na zaslonu je mreza iz predpomnilnika: svezo prvo stran pokazemo enkrat, ko odgovorijo vsi viri. */
        @Volatile var izPredpomnilnika = false
        /** Kartice spletnih virov, ki jih prva stran ni docakala ([stranGlasbe]): pridejo na konec mreze. */
        @Volatile var spletniVTeku: java.util.concurrent.Future<List<Jamendo.Skladba>>? = null
        /** Naloga 83: stran vgrajenega vira (Jamendo), ki je prva stran ni docakala - pride na konec mreze kot pozni dodatki. */
        @Volatile var vgrajeneVTeku: java.util.concurrent.Future<List<Jamendo.Skladba>>? = null
        /** Naloga 84: TuneIn (lokalne postaje), ki ga prva stran Radia ni docakala - pride na konec mreze. */
        @Volatile var tuneInVTeku: java.util.concurrent.Future<List<Jamendo.Skladba>>? = null
        /** Naloga 83: na kaj prva stran se caka v ozadju (dodatki, spletni viri, vgrajeni, TuneIn). */
        val vTeku: Boolean get() = viri.any { it.vTeku != null } || spletniVTeku != null || vgrajeneVTeku != null || tuneInVTeku != null
        /** Kljuci kartic ([kljucVMrezi]), ki so zdaj na zaslonu, po vrsti: ob dopolnjevanju ostanejo na svojem mestu. */
        var narisani: List<String> = emptyList()
        /** Kolikokrat smo prazno prvo stran ze poskusili znova ([naloziMrezo]). */
        @Volatile var poskusov = 0
        /** Naloga 88: zadnje zapisano stevilo skritih kartic dodatkov (mrtvi, ni na voljo, caka knjiznica). */
        var skrite = Triple(0, 0, 0)
        /** Naloga 78: kar pokazemo namesto prazne mreze ([nadomestnaMreza]): (vrsta, naslov police, kartice). */
        var nadomestne: List<Triple<OsPravila.Nadomestek, String, List<Jamendo.Skladba>>>? = null
        var isceNadomestek = false
        var seKaj = false
        var nalagam = true
        var zadnjicVec = 0L
        var podpis = ""
    }
    private var mrezaVsebine: MrezaVsebine? = null

    private fun glasbaSkupina(): String = getSharedPreferences(NASTAVITVE_POGLEDA, MODE_PRIVATE).getString("glasba_skupina", "").orEmpty()
    private fun shraniGlasbaSkupino(s: String) = getSharedPreferences(NASTAVITVE_POGLEDA, MODE_PRIVATE).edit().putString("glasba_skupina", s).apply()

    private fun kljucMreze(i: Int) = if (i == RADIO) "r|$radioSkupina|$radioZvrst|${Locale.getDefault().country}"
        else "g|${glasbaSkupina()}|$glasbaZvrst|$glasbaJezik"

    /** Ista postaja ali skladba iz dveh virov je ena kartica: postaja po naslovu toka, ostalo po id-ju. */
    /** Naloga 71: postaja v mrezi po kljucu imena (ista postaja iz Radio Browserja in TuneIna na dveh straneh mreze), sicer po toku. */
    private fun kljucVMrezi(s: Jamendo.Skladba) = if (s.radio) OsPravila.kljucImenaPostaje(s.naslov).ifEmpty { s.zvok.ifBlank { s.id } }.let { "p:$it" }
        else s.id

    /** Naloga 71: postaje brez dvojnikov; neposreden tok (ne TuneIn, ki ga razresi sele klik) ima prednost. */
    private fun brezDvojnikovPostaj(postaje: List<Jamendo.Skladba>): List<Jamendo.Skladba> =
        OsPravila.zdruziPostaje(postaje, { it.naslov }, { it.zvok }, { !TuneIn.jeEnota(it) && it.zvok.isNotBlank() })

    private fun odpriMrezo(i: Int) {
        // Moja glasba: priljubljene in seznami predvajanja kot bloka mreze - uporabnikove zbirke, ne katalog.
        if (i == GLASBA && glasbaSkupina() == SKUPINA_MOJE) {
            narisi(zgoraj(GLASBA), opis(GLASBA), glava = listOfNotNull(if (vlc) null else razdelkiVrstica(GLASBA), glasbaIzbire(),
                if (vlc) null else razvrstiInFiltrirajGumb(GLASBA)))
            return
        }
        // Na tej napravi (samostojni Predvajalnik): glasba iz shrambe naprave kot mreza; brez dovoljenja kartica, ki ga zahteva.
        if (i == GLASBA && vlc && glasbaSkupina() == SKUPINA_NAPRAVA) {
            val lokalne = krajevno[GLASBA]
            val urejene = lokalne.orEmpty().let { if (razvrstitev(GLASBA) == RAZVRSTI_PRIPOROCENO) it else razvrsti(GLASBA, it) }
            val dovoljenje = dovoljenjeKartica(GLASBA)
            // Prazna knjiznica: povemo, zakaj (se nalaga / ni nicesar); brez dovoljenja to pove ze kartica dovoljenja.
            val obvestilo = if (urejene.isNotEmpty() || dovoljenje != null) null else besedilo(14f, osBarva(R.color.os_umirjeno)).apply {
                text = getString(if (lokalne == null) R.string.os_glasba_nalagam else R.string.os_krajevno_prazno)
                setPadding(dp(4), dp(14), dp(4), dp(14))
            }
            narisi(listOf(Vrsta("", skladbe(urejene), mreza = true, skladbe = true)), opis(GLASBA), glava = listOfNotNull(glasbaIzbire(), dovoljenje, obvestilo))
            return
        }
        val m = MrezaVsebine(i, kljucMreze(i))
        mrezaVsebine = m
        // Naloga 89: uvozenim seznamom (Spotify) v ozadju poiscemo posnetke, ki se manjkajo - polica seznamov igra brez cakanja.
        // Povratni klic ne drzi te Activity (tek traja minute): samo staticni SEZNAMI na glavni niti.
        if (i == GLASBA) PredIskanjePosnetkov.zacni(this) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { SEZNAMI.remove(DOMOV); SEZNAMI.remove(GLASBA) }
        }
        // Nic cakanja: zadnja znana mreza te izbire je na zaslonu takoj; sveza jo zamenja, ko pride.
        VSEBINA_MREZE[m.kljuc]?.let { m.vsi += it; m.videni += it.map { s -> kljucVMrezi(s) }; m.izPredpomnilnika = it.isNotEmpty() }
        // Naloga 83: po zagonu (pomnilnik prazen) zadnja znana prva stran te izbire z diska, takoj (kot Filmi in Live TV);
        // sveza jo zamenja, ko pride (kar uporabnik ze vidi, ostane na svojem mestu).
        if (m.vsi.isEmpty()) try { semeDelavec.execute { naloziMrezoZDiska(m) } } catch (_: java.util.concurrent.RejectedExecutionException) { }
        drsnik.scrollTo(0, 0)
        narisiMrezo(m)
        if (vsebina.width == 0) vsebina.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (vsebina.width <= 0) return
                vsebina.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (mrezaVsebine === m && !isFinishing) { m.podpis = ""; narisiMrezo(m) }
            }
        })
        try { delavec.execute { naloziMrezo(m, prva = true) } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    /** Naloga 83: zadnja znana prva stran mreze z diska ([MedijskiPredpomnilnik], »mreza|<izbira>«, najvec 7 dni). Klic iz ozadja. */
    private fun naloziMrezoZDiska(m: MrezaVsebine) {
        if (mrezaVsebine !== m) return
        val dodatki = stremioNaslovi()
        val shranjeno = try { MedijskiPredpomnilnik.beriPoliceSCasom(this, "mreza|" + m.kljuc) } catch (_: Exception) { null } ?: return
        if (System.currentTimeMillis() - shranjeno.first !in 0 until KANALI_SEME_MS) return
        val s = shranjeno.second.firstOrNull()?.third.orEmpty().filter { !Stremio.brezDodatka(it, dodatki) }
        if (s.isEmpty()) return
        glavna.post {
            if (isFinishing || mrezaVsebine !== m || m.vsi.isNotEmpty()) return@post
            for (x in s) if (m.videni.add(kljucVMrezi(x))) m.vsi += x
            m.izPredpomnilnika = true
            android.util.Log.i("SafeerOsMedia", "mreza ${m.razdelek}: z diska ${m.vsi.size} kartic")
            narisiMrezo(m)
        }
    }

    private fun naloziVecMreze(m: MrezaVsebine) {
        if (mrezaVsebine !== m || m.nalagam || !m.seKaj || isFinishing) return
        m.nalagam = true
        m.zadnjicVec = android.os.SystemClock.uptimeMillis()
        stanje.text = getString(R.string.os_glasba_nalagam)
        try { delavec.execute { naloziMrezo(m, prva = false) } } catch (_: java.util.concurrent.RejectedExecutionException) { m.nalagam = false }
    }

    /** Naslednja stran mreze (klic iz ozadja), nato izris. */
    private fun naloziMrezo(m: MrezaVsebine, prva: Boolean) {
        val (nove, seKaj) = try { if (m.razdelek == RADIO) stranRadia(m, prva) else stranGlasbe(m, prva) }
            catch (_: Exception) { emptyList<Jamendo.Skladba>() to false }
        // Prazna prva stran, mreze na zaslonu pa ni (vir je odgovoril z napako - izmerjeno na televizorju 5. 10. 2026 ob prvem
        // odprtju po zagonu): poskusimo se dvakrat, preden uporabniku recemo, da ni nicesar. Do takrat pise »Nalagam ...«.
        // Naloga 83: kar je se v teku, pride kot pozni dodatki - ne sprasujemo znova, nadomestek pa je na zaslonu takoj.
        if (prva && nove.isEmpty() && m.vsi.isEmpty() && m.vTeku) glavna.post { if (mrezaVsebine === m && !isFinishing && m.vsi.isEmpty()) nadomestnaMreza(m) }
        else if (prva && nove.isEmpty() && m.vsi.isEmpty() && m.poskusov < 2 && mrezaVsebine === m && !isFinishing) {
            m.poskusov++
            android.util.Log.i("SafeerOsMedia", "mreza ${m.razdelek}: prazna prva stran, poskus ${m.poskusov} od 2")
            // Naloga 83: nadomestek (zadnja znana, sorodne police) TAKOJ ob prvi prazni prvi strani, ne sele po vseh poskusih
            // (~45 s); sveza vsebina ga zamenja, ko pride.
            if (m.poskusov == 1) glavna.post { if (mrezaVsebine === m && !isFinishing && m.vsi.isEmpty()) nadomestnaMreza(m) }
            try { Thread.sleep(1_500) } catch (_: InterruptedException) { return }
            if (mrezaVsebine === m && !isFinishing) naloziMrezo(m, prva = true)
            return
        }
        glavna.post {
            m.nalagam = false
            if (isFinishing || mrezaVsebine !== m) return@post
            // Prazen odgovor prve strani (brez omrezja) ne povozi mreze iz predpomnilnika.
            // Sveza prva stran zamenja mrezo iz predpomnilnika; kar uporabnik ze vidi, pa ostane (na svojem mestu, [narisiMrezo]).
            val prej = if (prva && nove.isNotEmpty() && m.narisani.isNotEmpty()) { val naZaslonu = m.narisani.toSet(); m.vsi.filter { kljucVMrezi(it) in naZaslonu } } else emptyList()
            if (prva && nove.isNotEmpty()) { m.vsi.clear(); m.videni.clear() }
            var dodanih = 0
            for (s in nove) if (m.videni.add(kljucVMrezi(s))) { m.vsi += s; dodanih++ }
            // Stran brez novih enot: konec (vir strani ne pozna ali jih ni vec).
            m.seKaj = seKaj && (prva || dodanih > 0)
            m.zadnjicVec = android.os.SystemClock.uptimeMillis()
            if (prva && m.vsi.isNotEmpty()) {
                VSEBINA_MREZE[m.kljuc] = m.vsi.toList()
                // Naloga 78: zadnja znana prva stran izbire tudi na disk - nadomestek prazne mreze po zagonu.
                // Naloga 90b: ne povozi vec kot 60 postaj z diska s prvo stranjo (60), ce pozni viri stran se dopolnijo.
                if (OsPravila.shraniPrvoStranMreze(m.vTeku, m.izPredpomnilnika)) {
                    val app = applicationContext
                    val zapis = OsPravila.zapisMreze(m.vsi)
                    try { delavec.execute { MedijskiPredpomnilnik.shraniPolice(app, "mreza|" + m.kljuc, listOf(Triple("vsi", true, zapis))) } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { }
                }
            }
            for (s in prej) if (m.videni.add(kljucVMrezi(s))) m.vsi += s
            // Naloga 78: prva stran je prazna in nicesar vec ne pricakujemo - namesto »Nothing found« nadomestna vsebina.
            if (prva && m.vsi.isEmpty() && !m.vTeku) nadomestnaMreza(m)
            val y = drsnik.scrollY
            narisiMrezo(m)
            if (!prva) drsnik.post(object : Runnable {
                var poskusi = 0
                override fun run() {
                    if (mrezaVsebine !== m) return
                    if (vsebina.height >= y + drsnik.height || poskusi++ > 30) drsnik.scrollTo(0, y) else drsnik.postDelayed(this, 50)
                }
            }) else if (y > 0) drsnik.post { drsnik.scrollTo(0, y) }
        }
        // Pocasni dodatki: njihova stran pride na konec mreze, ko odgovorijo (mreza se dopolni na mestu) - uporabnik nanje ne caka.
        if (m.vTeku) try { iskanjeDelavec.execute {
            val jezik = glasbaJezik
            val pozne = (try { straniDodatkov(m, 20_000L, samoVTeku = true) } catch (_: Exception) { emptyList() })
                .let { s -> if (m.razdelek == RADIO || jezik.isEmpty()) s else s.filter { OsPravila.jezikGlasbeUstreza(JezikiVsebine.oznaka(it.language), jezik, Stremio.jeEnota(it)) } } +
                (m.spletniVTeku?.let { f -> m.spletniVTeku = null; try { f.get(25, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { emptyList() } } ?: emptyList()) +
                (m.vgrajeneVTeku?.let { f -> m.vgrajeneVTeku = null; try { f.get(25, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { emptyList() } } ?: emptyList()) +
                (m.tuneInVTeku?.let { f -> m.tuneInVTeku = null; try { f.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { emptyList() } } ?: emptyList())
            glavna.post {
                if (isFinishing || mrezaVsebine !== m) return@post
                var dodanih = 0
                for (s in pozne) if (m.videni.add(kljucVMrezi(s))) { m.vsi += s; dodanih++ }
                android.util.Log.i("SafeerOsMedia", "mreza ${m.razdelek}: pozni dodatki so dali $dodanih novih kartic")
                // Naloga 78: tudi pozni dodatki niso dali nicesar - nadomestna vsebina namesto prazne mreze.
                if (dodanih == 0) { if (m.vsi.isEmpty() && !m.nalagam) nadomestnaMreza(m); return@post }
                m.seKaj = m.seKaj || m.viri.any { !it.konec }
                if (m.stran <= 1) {
                    VSEBINA_MREZE[m.kljuc] = m.vsi.toList()
                    // Naloga 84: prva stran je prisla sele s poznimi viri (imeniki v ozadju) - tudi ta gre na disk za naslednje odprtje.
                    val app = applicationContext
                    val zapis = OsPravila.zapisMreze(m.vsi)
                    try { delavec.execute { MedijskiPredpomnilnik.shraniPolice(app, "mreza|" + m.kljuc, listOf(Triple("vsi", true, zapis))) } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { }
                }
                narisiMrezo(m)
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    /**
     * Naslednje strani katalogov dodatkov te mreze, prepletene (klic iz ozadja). Dodatek, ki v [rokMs] ne odgovori,
     * mreze ne zadrzuje (izmerjeno 5. 10. 2026: en dodatek je odgovoril sele po 15 s, mreza Glasbe je bila toliko casa
     * prazna): njegova stran ostane v teku ([VirKanalov.vTeku]) in pride z naslednjim klicem.
     */
    private fun straniDodatkov(m: MrezaVsebine, rokMs: Long, samoVTeku: Boolean = false): List<Jamendo.Skladba> {
        // [samoVTeku]: pocakamo le na strani, ki so ze v teku (nove strani hitrih virov ne zahtevamo).
        val viri = m.viri.filter { it.vTeku != null || (!samoVTeku && !it.konec) }
        for (v in viri) if (v.vTeku == null) v.vTeku = try {
            iskanjeDelavec.submit<List<Jamendo.Skladba>> {
                val prvaStran = v.dal == 0
                val odgovor = try { Stremio.katalogAliIzpad(v.katalog, skip = v.dal) } catch (_: Exception) { null }
                // Naloga 88: prva stran kataloga dodatka v Glasbi in Radiu z izidom in razlogom (TV 08:50: Music Concerts
                // ni dal kartic - ali je bil katalog prazen, napaka ali ni odgovoril?). Samo gostitelj in id kataloga.
                if (prvaStran) android.util.Log.i("SafeerOsMedia", "mreza ${m.razdelek}: katalog ${android.net.Uri.parse(v.katalog.dodatek).host}/${v.katalog.tip}/${v.katalog.id} -> " +
                    OsPravila.izidKataloga(odgovor?.size, Stremio.zadnjaKodaNiti(), Stremio.neOdgovarja(v.katalog.dodatek)))
                val stran = odgovor.orEmpty()
                v.dal += stran.size
                if (!OsPravila.katalogImaVec(stran.size, v.katalog.strani, STRAN_KATALOGA_MIN)) v.konec = true
                stran
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { null }
        val rok = android.os.SystemClock.uptimeMillis() + rokMs
        return prepleti(viri.map { v ->
            val f = v.vTeku ?: return@map emptyList<Jamendo.Skladba>()
            try {
                f.get((rok - android.os.SystemClock.uptimeMillis()).coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS).also { v.vTeku = null }
            } catch (_: java.util.concurrent.TimeoutException) { emptyList() }       // ostane v teku
            catch (_: Exception) { v.vTeku = null; emptyList() }
        })
    }

    /** Koliko stran mreze caka na kataloge dodatkov: prva stran brez predpomnjene mreze na zaslonu kratko, sicer na vse. */
    private fun rokDodatkov(m: MrezaVsebine, prva: Boolean) = if (prva && !m.izPredpomnilnika) 2_500L else 20_000L

    private fun stranRadia(m: MrezaVsebine, prva: Boolean): Pair<List<Jamendo.Skladba>, Boolean> {
        val svet = radioSkupina == SKUPINA_SVET || Locale.getDefault().country.length != 2
        val brezIzbire = !svet && radioZvrst.isEmpty()
        if (prva) {
            m.stran = 0
            // Postaje iz uporabnikovih dodatkov so pred vgrajenimi (samo v privzetem pogledu: zvrsti dodatek ne pove).
            val dodatki = MedijskiViri.vsi(this).filter { it.jeStremio && jeVirViden(RADIO, kljucVira(it)) }.map { it.naslov }
            m.viri = if (!brezIzbire || dodatki.isEmpty()) emptyList()
                else (try { Stremio.katalogiRazreda(dodatki, Stremio.RADIO) } catch (_: Exception) { emptyList() }).take(10).map { VirKanalov(it) }
        }
        val izhod = ArrayList<Jamendo.Skladba>()
        // Vgrajene postaje in katalogi dodatkov hkrati; pocasen dodatek prve strani ne zadrzuje ([straniDodatkov]).
        val odmik = m.stran * RADIO_NA_STRAN
        val zvrst = radioZvrst
        val vgrajene = if (!jeVirViden(RADIO, VIR_RADIO)) null else iskanjeDelavec.submit<List<Jamendo.Skladba>> {
            Radio.stran(if (svet) "" else Locale.getDefault().country, zvrst, RADIO_NA_STRAN, odmik) }
        val tuneIn = if (prva && brezIzbire && jeVirViden(RADIO, VIR_TUNEIN)) iskanjeDelavec.submit<List<Jamendo.Skladba>> { TuneIn.lokalne() } else null
        // Naloga 84: imenika (Radio Browser, TuneIn) tecejo vzporedno; brez mreze na zaslonu ju prva stran caka najvec
        // [OsPravila.rokImenikaRadia] od zacetka strani (prej do 25 s + 20 s), kar ne pride, gre na konec mreze ([naloziMrezo]).
        val rok = android.os.SystemClock.uptimeMillis() + OsPravila.rokImenikaRadia(prva, m.izPredpomnilnika || m.vsi.isNotEmpty())
        izhod += straniDodatkov(m, rokDodatkov(m, prva))
        fun pocakaj(f: java.util.concurrent.Future<List<Jamendo.Skladba>>?, vTeku: (java.util.concurrent.Future<List<Jamendo.Skladba>>) -> Unit): List<Jamendo.Skladba> =
            if (f == null) emptyList() else try { f.get((rok - android.os.SystemClock.uptimeMillis()).coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS) }
                catch (_: java.util.concurrent.TimeoutException) { if (prva) vTeku(f) else f.cancel(true); emptyList() }
                catch (_: Exception) { emptyList() }
        val postaje = pocakaj(vgrajene) { m.vgrajeneVTeku = it }
        izhod += postaje
        izhod += pocakaj(tuneIn) { m.tuneInVTeku = it }
        m.stran++
        // Naloga 71: dodatki, Radio Browser in TuneIn na isti strani brez dvojnikov (med stranmi jih ujame kljucVMrezi).
        return brezDvojnikovPostaj(izhod) to (postaje.size >= RADIO_NA_STRAN / 2 || m.vgrajeneVTeku != null || m.viri.any { !it.konec || it.vTeku != null })
    }

    /** Katalogi glasbenih dodatkov: zvocni (albumi, skladbe, seznami) ali videospoti (glasbeni katalog, ki se oglasa kot video). */
    private fun glasbeniKatalogi(videospoti: Boolean?): List<Stremio.Katalog> {
        val dodatki = MedijskiViri.vsi(this).filter { it.jeStremio && jeVirViden(GLASBA, kljucVira(it)) }.map { it.naslov }
        if (dodatki.isEmpty()) return emptyList()
        val vsi = (try { Stremio.katalogiRazreda(dodatki, Stremio.GLASBA) } catch (_: Exception) { emptyList() })
            // Naloga 77: glasbeni katalog s posnetki z IMDb (Music Concerts, tip music) je video - sodi med videospote. Prej ga je
            // ta filter (tip music = zvok) izlocil in skupina »Music videos« je bila prazna.
            .filter { videospoti == null || (Stremio.razred(it.tip) != Stremio.GLASBA || Stremio.jeVideoKatalog(it)) == videospoti }
        // Naloga 90a: koncerti (video katalogi) so pred rezom na deset katalogov - prej so ob vec glasbenih katalogih izpadli.
        val izbrani = OsPravila.katalogiZPrednostjo(vsi, 10) { Stremio.jeVideoKatalog(it) }
        android.util.Log.i("SafeerOsGlasba", "Music: katalogov dodatkov ${vsi.size}, izbranih ${izbrani.size}, koncertov ${izbrani.count { Stremio.jeVideoKatalog(it) }}")
        return izbrani
    }

    private fun stranGlasbe(m: MrezaVsebine, prva: Boolean): Pair<List<Jamendo.Skladba>, Boolean> {
        val videospoti = glasbaSkupina() == SKUPINA_VIDEOSPOTI
        val zvrst = glasbaZvrst
        val jezik = glasbaJezik
        // Naloga 83: vnos dodatka brez oznake jezika (koncert) izbran jezik ne skrije ([OsPravila.jezikGlasbeUstreza]).
        fun vJeziku(s: List<Jamendo.Skladba>) = if (jezik.isEmpty()) s else s.filter { OsPravila.jezikGlasbeUstreza(JezikiVsebine.oznaka(it.language), jezik, Stremio.jeEnota(it)) }
        val izhod = ArrayList<Jamendo.Skladba>()
        if (prva) m.stran = 0
        // Naloga 83: ponovni poskus prazne prve strani obdrzi vire - strani v teku (pocasen dodatek, Music Concerts) tecejo
        // naprej. Prej so se viri ob vsakem poskusu zamenjali z novimi in odgovor, ki je prisel po 2,5 s, je bil zavrzen.
        if (prva && (m.poskusov == 0 || m.viri.isEmpty())) {
            // Katalogi dodatkov: uporabnikova izbira je pred vgrajenimi viri. Ob izbrani zvrsti samo katalogi, ki jo ponujajo.
            m.viri = glasbeniKatalogi(if (videospoti) true else null).mapNotNull { k ->
                if (zvrst.isEmpty()) k else k.zvrsti.firstOrNull { it.equals(zvrst, ignoreCase = true) }?.let { Stremio.zMoznostjo(k, it) }
            }.map { VirKanalov(it) }
        }
        // Vgrajeni vir, spletni viri in katalogi dodatkov hkrati; pocasen dodatek prve strani ne zadrzuje ([straniDodatkov]).
        val odmik = m.stran * GLASBA_NA_STRAN
        val vgrajene = if (videospoti || !jeVirViden(GLASBA, VIR_JAMENDO)) null else iskanjeDelavec.submit<List<Jamendo.Skladba>> {
            // Napaka (izjema) steje kot prazen odgovor, da drugi poskus stece tudi takrat.
            fun stran() = try { Jamendo.stran(zvrst, jezik, GLASBA_NA_STRAN, odmik) } catch (_: Exception) { emptyList() }
            // Jamendo obcasno vrne prazen seznam (preverjeno 21. 9. 2026); drugi poskus ga dobi.
            stran().ifEmpty { if (prva) { Thread.sleep(800); stran() } else emptyList() }
        }
        val spletni = if (!prva || zvrst.isNotEmpty()) null else iskanjeDelavec.submit<List<Jamendo.Skladba>> {
            vJeziku(SpletniVir.priljubljeno(this, MedijskiViri.vsi(this).filter { jeVirViden(GLASBA, kljucVira(it)) }))
                .filter { val spot = SpletniVir.vrstaVsebine(it) == SpletniVir.VIDEOSPOT; if (videospoti) spot else !spot && !it.video }
        }
        izhod += vJeziku(straniDodatkov(m, rokDodatkov(m, prva)))
        var seKaj = m.viri.any { !it.konec || it.vTeku != null }
        // Naloga 83: Jamendo je na TV odgovarjal ~10 s (prazna prva stran je cakala nanj); brez mreze na zaslonu ga prva stran
        // caka najvec 4 s, ostalo pride na konec mreze ([naloziMrezo]).
        val skladbe = if (vgrajene == null) emptyList() else try { vgrajene.get(if (prva && !m.izPredpomnilnika) 4_000L else 25_000L, java.util.concurrent.TimeUnit.MILLISECONDS) }
            catch (_: java.util.concurrent.TimeoutException) { if (prva) m.vgrajeneVTeku = vgrajene; emptyList() }
            catch (_: Exception) { emptyList() }
        if (spletni != null) {
            // Spletni viri (stran se nalozi v brskalniku v ozadju) trajajo okoli deset sekund (izmerjeno 5. 10. 2026): prva
            // stran nanje ne caka - kar je ze pripravljeno, gre zraven, ostalo pride na konec mreze ([naloziMrezo]). Z mrezo
            // iz predpomnilnika na zaslonu pocakamo (uporabnik ze ima kaj gledati, sveza stran pride enkrat).
            izhod += try { spletni.get(if (m.izPredpomnilnika) 20_000L else 1L, java.util.concurrent.TimeUnit.MILLISECONDS) }
                catch (_: java.util.concurrent.TimeoutException) { m.spletniVTeku = spletni; emptyList() }
                catch (_: Exception) { emptyList() }
        }
        izhod += skladbe
        seKaj = seKaj || skladbe.size >= GLASBA_NA_STRAN / 2 || m.vgrajeneVTeku != null
        m.stran++
        return izhod to seKaj
    }

    private fun narisiMrezo(m: MrezaVsebine) {
        if (mrezaVsebine !== m || isFinishing) return
        if (podstrani.odprta && podPodstranjo === m) return        // mreza pod odprto podstranjo: narisana bo ob vrnitvi
        val radio = m.razdelek == RADIO
        val videospoti = !radio && glasbaSkupina() == SKUPINA_VIDEOSPOTI
        // Radio brez izbire: priljubljene postaje so prve (uporabnik jih ima takoj pod palcem).
        val priljubljene = if (radio && radioZvrst.isEmpty() && radioSkupina.isEmpty()) MedijskiViri.priljubljene(this).filter { it.radio } else emptyList()
        // Kartice dodatkov: samo, kar se dokazano da predvajati (lastna knjiznica po vzorcu, [vzorciKnjiznice]).
        vzorciKnjiznice(m.vsi)
        val poVrsti = razvrsti(m.razdelek, (priljubljene + m.vsi).distinctBy { kljucVMrezi(it) })
            .filterNot { MrtviKanali.skrij(this, it) || znanoNiNaVoljo(it) || cakaNaKnjiznico(it) || neodklenjena(it) }
        // Kar uporabnik ze vidi, ostane na svojem mestu; novo pride za tem (razen pri izrecni razvrstitvi po imenu ali letnici).
        val vsi = if (razvrstitev(m.razdelek) == RAZVRSTI_PRIPOROCENO) OsPravila.stabilenRed(m.narisani, poVrsti) { kljucVMrezi(it) } else poVrsti
        // Naloga 88: na zaslonu so seznami predvajanja (polica na vrhu Music) - napis ni »Nothing found«.
        val opis = when (OsPravila.opisPrazneMreze(vsi.isNotEmpty(), m.nalagam, !radio && glasbaJezik.isNotEmpty(), brezSpletnihVirov(),
            !radio && seznamiZaGlasbo().isNotEmpty())) {
            OsPravila.OpisMreze.NALAGAM -> getString(R.string.os_glasba_nalagam)
            OsPravila.OpisMreze.JEZIK -> getString(R.string.os_jezik_ni_vsebine)
            OsPravila.OpisMreze.BREZ_VIROV -> getString(R.string.os_media_brez_virov)
            OsPravila.OpisMreze.SAMO_SEZNAMI -> getString(R.string.os_media_glasba_samo_seznami)
            OsPravila.OpisMreze.PRAZNO -> getString(R.string.os_glasba_prazno)
            OsPravila.OpisMreze.NIC -> ""
        }
        // Naloga 88: kartice dodatkov, ki jih mreza skrije (in zakaj) - enkrat na spremembo, v dnevnik.
        val skrite = (priljubljene + m.vsi).filter { Stremio.jeEnota(it) }.let { e ->
            Triple(e.count { MrtviKanali.jeMrtev(this, it.id) }, e.count { znanoNiNaVoljo(it) }, e.count { cakaNaKnjiznico(it) }) }
        if (skrite != Triple(0, 0, 0) && skrite != m.skrite) android.util.Log.i("SafeerOsMedia",
            "mreza ${m.razdelek}: skritih kartic dodatkov mrtvih=${skrite.first}, ni na voljo=${skrite.second}, caka knjiznica=${skrite.third}")
        m.skrite = skrite
        // Naloga 78: prazna mreza z nadomestno vsebino (zadnja znana, sorodno, brez filtra).
        val nadomestne = m.nadomestne
        // Naloga 83: tudi med ponovnimi poskusi (m.nalagam) - nadomestek je na zaslonu takoj.
        if (vsi.isEmpty() && nadomestne != null && nadomestne.any { it.third.isNotEmpty() || !m.nalagam }) { narisiNadomestek(m, nadomestne, opis); return }
        val vec = m.seKaj && !m.nalagam && vsi.isNotEmpty()
        val dodaj = if (vsi.isEmpty() && !m.nalagam) listOf(karticaDodajVir()) else emptyList()
        val podpis = "${vsi.map { it.id }.hashCode()}|$vec|$opis|${razvrstitev(m.razdelek)}|${vsebina.width}"
        if (podpis == m.podpis) { if (stanje.text.toString() != opis) stanje.text = opis; return }
        m.podpis = podpis
        m.narisani = vsi.map { kljucVMrezi(it) }
        val kartice = (if (videospoti) videi(vsi) else skladbe(vsi)) + (if (vec) listOf(Kartica(getString(R.string.os_media_nalozi_vec), "", "",
            { naloziVecMreze(m) }, ikona = R.drawable.os_ikona_plus)) else emptyList()) + dodaj
        // Naloga 83: seznami predvajanja uporabnika (uvozeni Spotify, YouTube) kot polica na vrhu Music.
        val seznami = if (radio) emptyList() else seznamiZaGlasbo()
        pokaziMrezo(m, Vrsta("", kartice, video = videospoti, mreza = true, kanali = radio, slicice = videospoti, skladbe = !radio && !videospoti), opis,
            "${m.kljuc}|${razvrstitev(m.razdelek)}|${ozekZaslon()}|${seznami.map { it.ime to it.skladbe.size }.hashCode()}") {
            (if (vlc) emptyList() else listOf(razdelkiVrstica(m.razdelek))) + listOf(if (radio) radioIzbire() else glasbaIzbire()) + listOfNotNull(policaSeznamov(seznami))
        }
    }

    /**
     * Naloga 78: vsebina namesto prazne prve strani Glasbe ali Radia ([OsPravila.nadomestnaVsebina]), v ozadju: zadnja znana
     * mreza te izbire z diska, sicer sorodne police ([sorodnePolice]); ob filtru »Pokazi vse« in kartice brez filtra iz
     * pomnilnika. Enkrat na odprto mrezo.
     */
    private fun nadomestnaMreza(m: MrezaVsebine) {
        if (m.isceNadomestek) return
        m.isceNadomestek = true
        val app = applicationContext
        val radio = m.razdelek == RADIO
        val videospoti = !radio && glasbaSkupina() == SKUPINA_VIDEOSPOTI
        val filter = if (radio) radioZvrst.isNotEmpty() else glasbaZvrst.isNotEmpty() || glasbaJezik.isNotEmpty() || videospoti
        val brezFiltra = if (!filter) emptyList() else VSEBINA_MREZE[if (radio) "r|$radioSkupina||${Locale.getDefault().country}" else "g|||"].orEmpty()
        val dodatki = stremioNaslovi()
        try { delavec.execute {
            val disk = (try { MedijskiPredpomnilnik.beriPoliceSCasom(app, "mreza|" + m.kljuc) } catch (_: Exception) { null })
                ?.second?.firstOrNull()?.third.orEmpty().filter { !Stremio.brezDodatka(it, dodatki) }
            val sorodne = if (disk.isNotEmpty()) emptyList() else try { sorodnePolice(radio, videospoti) } catch (_: Exception) { emptyList() }
            val izbira = OsPravila.nadomestnaVsebina(disk.size, sorodne.map { it.second.size }, filter, brezFiltra.size)
            val police = izbira.flatMap { n ->
                when (n) {
                    OsPravila.Nadomestek.ZADNJA_ZNANA -> listOf(Triple(n, getString(R.string.os_media_zadnja_znana), disk))
                    OsPravila.Nadomestek.SORODNO -> sorodne.filter { it.second.isNotEmpty() }.map { Triple(n, it.first, it.second) }
                    OsPravila.Nadomestek.BREZ_FILTRA -> listOf(Triple(n, getString(R.string.os_media_brez_filtra), brezFiltra))
                    else -> listOf(Triple(n, "", emptyList()))
                }
            }
            android.util.Log.i("SafeerOsMedia", "mreza ${m.razdelek}: prazna, nadomestek=${izbira}, police=${police.map { it.second to it.third.size }}")
            glavna.post { if (mrezaVsebine === m && !isFinishing) { m.nadomestne = police; m.podpis = ""; narisiMrezo(m) } }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    /**
     * Naloga 78: sorodne police iz uporabnikovih virov (klic iz ozadja): Glasba - koncerti (glasbeni katalogi s posnetki),
     * glasbeni kanali v zivo (katalogi TV z glasbenim imenom, npr. »All IPTV Music«), radijske postaje; Radio - glasbeni
     * kanali v zivo. Po najvec 30 kartic, prva stran kataloga (circuit breaker in predpomnilnik katalogov veljata).
     */
    private fun sorodnePolice(radio: Boolean, videospoti: Boolean): List<Pair<String, List<Jamendo.Skladba>>> {
        val vsiViri = MedijskiViri.vsi(this).filter { it.jeStremio }
        val zaGlasbo = vsiViri.filter { jeVirViden(GLASBA, kljucVira(it)) }.map { it.naslov }
        val zaTv = vsiViri.filter { jeVirViden(TV_V_ZIVO, kljucVira(it)) }.map { it.naslov }
        fun prveStrani(katalogi: List<Stremio.Katalog>): List<Jamendo.Skladba> = katalogi.take(3)
            .map { k -> iskanjeDelavec.submit<List<Jamendo.Skladba>> { try { Stremio.katalog(k).take(30) } catch (_: Exception) { emptyList() } } }
            .flatMap { f -> try { f.get(10, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { emptyList() } }
            .filterNot { ZasebniKanali.jeZaseben(it.id) || MrtviKanali.skrij(this, it) || znanoNiNaVoljo(it) || neodklenjena(it) }
            .distinctBy { it.id }.take(30)
        val police = ArrayList<Pair<String, List<Jamendo.Skladba>>>()
        if (!radio && !videospoti && zaGlasbo.isNotEmpty())
            police += getString(R.string.os_media_sorodno_koncerti) to prveStrani(Stremio.katalogiRazreda(zaGlasbo, Stremio.GLASBA).filter { Stremio.jeVideoKatalog(it) })
        if (zaTv.isNotEmpty())
            police += getString(R.string.os_media_sorodno_kanali) to prveStrani(Stremio.katalogiRazreda(zaTv, Stremio.TV).filter { OsPravila.glasbenoIme(it.ime) })
        if (!radio && jeVirViden(RADIO, VIR_RADIO))
            police += getString(R.string.os_media_sorodno_postaje) to (try { Radio.stran(Locale.getDefault().country, "", 30, 0) } catch (_: Exception) { emptyList() })
        return police
    }

    /**
     * Naloga 83: seznami predvajanja za Music ([OsPravila.seznamZaGlasbo]): uvozeni (tudi YouTube s posnetki) in tisti s skladbami.
     * Mreza se rise pogosto (vzorci, pozni dodatki): razclenitev vseh seznamov na glavni niti najvec enkrat na 5 s.
     */
    private val seznamiGlasbe = OsPravila.Obcasna<List<MedijskiViri.Seznam>>(5_000)
    private fun seznamiZaGlasbo(): List<MedijskiViri.Seznam> = seznamiGlasbe.daj(android.os.SystemClock.uptimeMillis()) {
        MedijskiViri.seznami(this).filter { sz -> OsPravila.seznamZaGlasbo(sz.vir, sz.skladbe.all { it.video }) }
    }

    /**
     * Naloga 83: polica seznamov predvajanja na vrhu Music - ena vrsta kartic (uvozeni najprej, [OsPravila.seznamiNaVrhuGlasbe]);
     * ce jih je vec, zadnja kartica odpre »Moja glasba« z vsemi. Null, ce seznamov ni.
     */
    private fun policaSeznamov(seznami: List<MedijskiViri.Seznam>): View? {
        if (seznami.isEmpty()) return null
        val v0 = Vrsta(getString(R.string.os_seznami_predvajanja), emptyList(), mreza = true, skladbe = true)
        val (n, velikost) = mereMreze(v0)
        val red = OsPravila.seznamiNaVrhuGlasbe(seznami.map { it.vir to it.cas }, seznami.size)
        val vec = red.size > n
        val kartice = red.take(if (vec) (n - 1).coerceAtLeast(1) else n).map { karticaSeznama(seznami[it]) } +
            (if (vec) listOf(Kartica(getString(R.string.os_media_moja_glasba), resources.getQuantityString(R.plurals.os_stevilo_posnetkov, red.size, red.size), "",
                { shraniGlasbaSkupino(SKUPINA_MOJE); fokusVVsebino = true; izberi(GLASBA) }, ikona = R.drawable.os_ikona_mreza)) else emptyList())
        val v = v0.copy(kartice = kartice)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(besedilo(18f, osBarva(R.color.os_besedilo), true).apply {
                text = v.naslov; tag = "polica:${v.naslov}"; contentDescription = NASLOV_VRSTE
                setPadding(dp(4), dp(14), 0, dp(8))
            })
            val vrstica = LinearLayout(this@GlasbaActivity).apply { orientation = LinearLayout.HORIZONTAL }
            kartice.forEachIndexed { i, k -> val p = karticaMreze(v, k, velikost); oznaciVMrezi(v, p, i, n); vrstica.addView(p) }
            addView(vrstica, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** Naloga 78: prazna mreza z nadomestno vsebino - police z oznako, od kod so, in dejanja (Pokazi vse, Dodaj vir, iskanje). */
    private fun narisiNadomestek(m: MrezaVsebine, police: List<Triple<OsPravila.Nadomestek, String, List<Jamendo.Skladba>>>, opisPrazne: String) {
        val radio = m.razdelek == RADIO
        val nic = police.any { it.first == OsPravila.Nadomestek.NIC }
        val podpis = "nadomestek|${police.map { it.second to it.third.map { s -> s.id } }.hashCode()}|${vsebina.width}"
        val opis = if (nic) opisPrazne else getString(R.string.os_media_prazna_izbira)
        if (podpis == m.podpis) { if (stanje.text.toString() != opis) stanje.text = opis; return }
        m.podpis = podpis
        m.narisani = emptyList()
        val dejanja = ArrayList<Kartica>()
        if (police.any { it.first == OsPravila.Nadomestek.POKAZI_VSE }) dejanja += Kartica(getString(R.string.os_media_pokazi_vse),
            getString(R.string.os_media_pokazi_vse_opis), "", {
                if (radio) radioZvrst = "" else { glasbaZvrst = ""; glasbaJezik = ""; shraniGlasbaSkupino("") }
                fokusVVsebino = true; odpriMrezo(m.razdelek)
            }, ikona = R.drawable.os_ikona_mreza)
        if (nic) {
            dejanja += karticaDodajVir()
            // Glasba: dodatek samo za iskanje (YouTubio) - predlog, da tam poisce.
            if (!radio) MedijskiViri.vsi(this).firstOrNull { it.jeStremio && Stremio.samoIskanje(it.naslov) == true }?.let { v ->
                val ime = Stremio.imeIzPredpomnilnika(v.naslov) ?: v.ime
                dejanja += Kartica(getString(R.string.os_media_poisci_v, ime), getString(R.string.os_mediji_samo_iskanje), "", { odpriIskanje("") }, ikona = R.drawable.os_ikona_splet)
            }
        }
        val vrste = (if (dejanja.isEmpty()) emptyList() else listOf(Vrsta("", dejanja, mala = true))) +
            police.filter { it.third.isNotEmpty() }.map { (_, naslov, s) -> Vrsta(naslov, if (s.any { it.video }) videi(s) else skladbe(s)) }
        narisi(vrste, opis, glava = (if (vlc) emptyList() else listOf(razdelkiVrstica(m.razdelek))) + listOf(if (radio) radioIzbire() else glasbaIzbire()) +
            listOfNotNull(if (radio) null else policaSeznamov(seznamiZaGlasbo())))
    }

    /** Izbire mreze Radia: [Domace postaje | Postaje po svetu ▾] [Vse zvrsti ▾] [Priporoceno ▾]. */
    private fun radioIzbire(): View {
        val imaDrzavo = Locale.getDefault().country.length == 2
        val skupine = (if (imaDrzavo) listOf("" to getString(R.string.os_mediji_domace)) else emptyList()) + listOf(SKUPINA_SVET to getString(R.string.os_mediji_svet))
        val nacini = listOf(RAZVRSTI_PRIPOROCENO to R.string.os_media_razvrsti_priporoceno, RAZVRSTI_IME_AZ to R.string.os_media_razvrsti_ime)
        return izbireVrstica(listOf(
            PoljeIzbire("izb:skupina", skupine.map { it.second }, skupine.indexOfFirst { it.first == radioSkupina }.coerceAtLeast(0), null) { i ->
                radioSkupina = skupine[i].first; odpriMrezo(RADIO)
            },
            PoljeIzbire("izb:zvrst", listOf(getString(R.string.os_zvrst_vse)) + ZVRSTI.map { getString(it.third) }, ZVRSTI.indexOfFirst { it.second == radioZvrst } + 1, null) { i ->
                radioZvrst = if (i == 0) "" else ZVRSTI[i - 1].second; odpriMrezo(RADIO)
            },
            PoljeIzbire("izb:razvrsti", nacini.map { getString(it.second) }, nacini.indexOfFirst { it.first == razvrstitev(RADIO) }.coerceAtLeast(0),
                getString(R.string.os_media_razvrsti)) { i ->
                razvrstitve[RADIO] = nacini[i].first
                mrezaVsebine?.let { it.narisani = emptyList(); narisiMrezo(it) }
            },
        ))
    }

    /**
     * Izbire mreze Glasbe: [Glasba | Videospoti | Moja glasba ▾] [Priporoceno ▾] [Vse zvrsti ▾] [Vsi jeziki ▾]. Pri »Moja
     * glasba« (police) je samo prva, da se uporabnik vrne v mrezo. Videospoti in Moja glasba sta na voljo, kadar imata kaj pokazati.
     */
    private fun glasbaIzbire(): View {
        val skupina = glasbaSkupina()
        val viri = MedijskiViri.vsi(this)
        // Videospoti dodatka: samo, dokler ne vemo, da njegova knjiznica tokov ne daje ([ZaupanjeKnjiznic]) - izbira,
        // ki odpre prazno mrezo, ni izbira.
        // Naloga 78: skupina je v izbiri samo, ce ima vir - videospoti: spletni vir ali dodatek z glasbenimi posnetki
        // ([Stremio.imaVideospote], tudi koncerti) in knjiznico, ki tokove daje. Prej je bila »Music videos« ponujena ze ob
        // glasbenem imenu dodatka in je odprla prazno mrezo. Prazna izbrana skupina je na koncu ([OsPravila.skupineIzbire]).
        val imaVideospote = viri.any { it.jeSplet } || Stremio.imaVideospote(viri.filter { it.jeStremio && jeVirViden(GLASBA, kljucVira(it)) &&
            ZaupanjeKnjiznic.stanje(Stremio.osnova(it.naslov)) != false }.map { it.naslov })
        val imaMoje = MedijskiViri.priljubljene(this).any { !it.video && !it.radio } ||
            MedijskiViri.seznami(this).any { sz -> sz.skladbe.isNotEmpty() && OsPravila.seznamZaGlasbo(sz.vir, sz.skladbe.all { it.video }) }
        val imena = mapOf("" to getString(R.string.os_mediji_glasba), SKUPINA_VIDEOSPOTI to getString(R.string.os_media_videospoti),
            SKUPINA_MOJE to getString(R.string.os_media_moja_glasba), SKUPINA_NAPRAVA to getString(R.string.os_krajevno_koren))
        val skupine = OsPravila.skupineIzbire(listOf("" to true, SKUPINA_VIDEOSPOTI to imaVideospote, SKUPINA_MOJE to imaMoje) +
            // Samostojni Predvajalnik: glasba iz shrambe naprave (prej polica nad spletnimi policami).
            (if (vlc) listOf(SKUPINA_NAPRAVA to true) else emptyList()), skupina).map { it to imena.getValue(it) }
        val polja = ArrayList<PoljeIzbire>()
        polja += PoljeIzbire("izb:skupina", skupine.map { it.second }, skupine.indexOfFirst { it.first == skupina }.coerceAtLeast(0), null) { i ->
            shraniGlasbaSkupino(skupine[i].first); fokusVVsebino = false; izberi(GLASBA)
        }
        if (skupina == SKUPINA_NAPRAVA) {
            // Datoteke naprave: zvrsti in jezika ne poznamo - samo razvrstitev (kot jih hrani naprava ali po imenu).
            val nacini = listOf(RAZVRSTI_PRIPOROCENO to R.string.os_media_razvrsti_priporoceno, RAZVRSTI_IME_AZ to R.string.os_media_razvrsti_ime)
            polja += PoljeIzbire("izb:razvrsti", nacini.map { getString(it.second) }, if (razvrstitev(GLASBA) == RAZVRSTI_PRIPOROCENO) 0 else 1,
                getString(R.string.os_media_razvrsti)) { i ->
                razvrstitve[GLASBA] = nacini[i].first; odpriMrezo(GLASBA)
            }
        } else if (skupina != SKUPINA_MOJE) {
            val nacini = listOf(RAZVRSTI_PRIPOROCENO to R.string.os_media_razvrsti_priporoceno,
                RAZVRSTI_NAJNOVEJSE to R.string.os_media_razvrsti_najnovejse, RAZVRSTI_IME_AZ to R.string.os_media_razvrsti_ime)
            polja += PoljeIzbire("izb:razvrsti", nacini.map { getString(it.second) }, nacini.indexOfFirst { it.first == razvrstitev(GLASBA) }.coerceAtLeast(0),
                getString(R.string.os_media_razvrsti)) { i ->
                razvrstitve[GLASBA] = nacini[i].first
                mrezaVsebine?.let { it.narisani = emptyList(); narisiMrezo(it) }
            }
            val zvrsti = ZVRSTI.filter { it.first.isNotEmpty() }
            polja += PoljeIzbire("izb:zvrst", listOf(getString(R.string.os_zvrst_vse)) + zvrsti.map { getString(it.third) }, zvrsti.indexOfFirst { it.first == glasbaZvrst } + 1, null) { i ->
                glasbaZvrst = if (i == 0) "" else zvrsti[i - 1].first; odpriMrezo(GLASBA)
            }
            val jeziki = jezikiIzbire(IzvirniJezik.KODE)
            polja += PoljeIzbire("izb:jezik", listOf(getString(R.string.os_jezik_vsi)) + jeziki.map { it.second }, jeziki.indexOfFirst { it.first == glasbaJezik } + 1,
                getString(R.string.os_jezik_vsebine)) { i ->
                glasbaJezik = if (i == 0) "" else jeziki[i - 1].first; odpriMrezo(GLASBA)
            }
        }
        return izbireVrstica(polja)
    }

    // ------------------------------------------------------------------ nadzorna plosca

    /** Pogledi nad vrstami: na plosci kartice razdelkov ter "zdaj se predvaja" s hitrimi dejanji. */
    private fun glavaRazdelka(i: Int, podatki: List<Podatki> = emptyList()): List<View> = if (vlc) when (i) {
        // Zavihki spodaj nadomestijo kartice razdelkov; razvrscanje je v meniju ⋮ (kot pri VLC).
        DOMOV -> listOfNotNull(zdajPlosca())
        VIDEO -> listOfNotNull(dovoljenjeKartica(i), videoIzbire(null))
        GLASBA -> listOfNotNull(dovoljenjeKartica(i), glasbaVrstica(podatki))
        RADIO, TV_V_ZIVO -> listOfNotNull(skokNaPolico(podatki.map { it.naslov }).takeIf { podatki.size > 1 })
        else -> emptyList()
    } else when (i) {
        DOMOV -> listOfNotNull(kategorije(), razvrstiInFiltrirajGumb(i), zdajPlosca())
        VIDEO -> {
            val razdelki = razdelkiVrstica(i); val izbire = videoIzbire(null); val razvrsti = razvrstiInFiltrirajGumb(i)
            poveziIzbiro(razdelki, izbire, razvrsti)
            listOf(razdelki, izbire, razvrsti)
        }
        GLASBA -> listOf(razdelkiVrstica(i), razvrstiInFiltrirajGumb(i), glasbaVrstica(podatki))
        RADIO, TV_V_ZIVO -> listOfNotNull(
            razdelkiVrstica(i),
            razvrstiInFiltrirajGumb(i),
            skokNaPolico(podatki.map { it.naslov }).takeIf { podatki.size > 1 }
        )
        VIRI -> listOf(razdelkiVrstica(i))
        else -> emptyList()
    }

    /**
     * Razdelki (Glasba, Video, Radio, TV v zivo, Moji viri, Predvajalnik) kot vrsta gumbov na vrhu vsakega
     * razdelka: uporabnik ima izbiro vedno na voljo, ne le na plosci (lastnik, 1. 10. 2026). Predvajalnik
     * (VLC slog) ima za to zavihke spodaj.
     */
    /** Gumb razdelka, v katerem je uporabnik (zadnja narisana vrstica razdelkov): nanj kaze GOR iz izbir mreze. */
    private var aktivniRazdelek: View? = null

    private fun razdelkiVrstica(trenutni: Int): View {
        val vrsta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        aktivniRazdelek = null
        fun gumb(kljuc: String, i: Int?, res: Int, barva: Int, ime: Int, klik: () -> Unit) {
            val aktiven = i != null && i == trenutni
            vrsta.addView(LinearLayout(this).apply {
                tag = "r:$kljuc"
                if (aktiven) { id = View.generateViewId(); aktivniRazdelek = this }
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                isFocusable = true; isClickable = true
                setBackgroundResource(R.drawable.os_kartica_steklo)
                setPadding(dp(10), dp(6), dp(12), dp(6))
                alpha = if (aktiven) 1f else 0.8f
                setOnClickListener { if (!aktiven) klik() else fokusVVsebino = true }
                addView(ikona(res, 20, barva))
                addView(besedilo(13f, osBarva(if (aktiven) R.color.os_mint else R.color.os_besedilo), aktiven).apply {
                    text = getString(ime); setPadding(dp(8), 0, 0, 0); maxLines = 1
                })
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        fun odpri(i: Int) { fokusVVsebino = true; izberi(i) }
        gumb(KLJUC_GLASBA, GLASBA, R.drawable.os_ikona_glasba, 0xFF8FA8FF.toInt(), R.string.os_mediji_glasba) { odpri(GLASBA) }
        gumb(KLJUC_VIDEO, VIDEO, R.drawable.os_ikona_video, 0xFFFF9580.toInt(), R.string.os_glasba_video) { odpri(VIDEO) }
        gumb(KLJUC_RADIO, RADIO, R.drawable.os_ikona_radio, osBarva(R.color.os_mint), R.string.os_glasba_radio) { odpri(RADIO) }
        gumb(KLJUC_TV, TV_V_ZIVO, R.drawable.os_ikona_tv, 0xFFFFC46B.toInt(), R.string.os_mediji_tv_v_zivo) { odpri(TV_V_ZIVO) }
        gumb(KLJUC_VIRI, VIRI, R.drawable.os_ikona_mapa, 0xFF7FB2FF.toInt(), R.string.os_mediji_viri) { odpri(VIRI) }
        gumb(KLJUC_PREDVAJALNIK, null, R.drawable.os_ikona_predvajaj, 0xFF57D6AD.toInt(), R.string.os_mediji_predvajalnik) { odpriPredvajalnik() }
        vrsta.getChildAt(0)?.nextFocusLeftId = meniMediji.id
        return android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            addView(vrsta, android.view.ViewGroup.LayoutParams(-2, -2))
            setPadding(0, dp(2), 0, dp(10))
        }
    }

    private data class VirIzbire(val kljuc: String, val ime: String)

    /** Viri, ki dejansko gradijo police izbranega razdelka. */
    private fun viriIzbire(i: Int): List<VirIzbire> {
        val uporabniski = MedijskiViri.vsi(this)
        return when (i) {
            DOMOV -> listOf(VirIzbire(VIR_JAMENDO, "Jamendo"), VirIzbire(VIR_RADIO, "Radio Browser"), VirIzbire(VIR_PEERTUBE, "PeerTube"))
            GLASBA -> listOf(VirIzbire(VIR_JAMENDO, "Jamendo")) +
                uporabniski.filter { it.jeSplet }.map { VirIzbire(kljucVira(it), it.ime) }
            VIDEO -> listOf(VirIzbire(VIR_JAVNA_LAST, getString(R.string.os_media_javna_last)), VirIzbire(VIR_PEERTUBE, "PeerTube")) +
                uporabniski.filter { it.jeSplet || it.jePeerTube }.map { VirIzbire(kljucVira(it), it.ime) }
            RADIO -> listOf(VirIzbire(VIR_RADIO, "Radio Browser"), VirIzbire(VIR_TUNEIN, "TuneIn"))
            TV_V_ZIVO -> listOf(VirIzbire(VIR_TV, getString(R.string.os_mediji_tv_v_zivo))) +
                uporabniski.filter { it.jeStremio }.map { VirIzbire(kljucVira(it), Stremio.imeIzPredpomnilnika(it.naslov) ?: it.ime) }
            else -> emptyList()
        }.distinctBy { it.kljuc }.filter { MedijskiViri.VGRAJENI_SPLETNI_VIRI || !it.kljuc.startsWith(PREDPONA_VGRAJENEGA) || imenikDodan(it.kljuc) }
    }

    /** Ena kartica v glavi odpre skupni dialog; povzetek pove trenutno aktivni pogled. */
    private fun razvrstiInFiltrirajGumb(i: Int): View {
        val imena = intArrayOf(
            R.string.os_media_razvrsti_priporoceno,
            R.string.os_media_razvrsti_najnovejse,
            R.string.os_media_razvrsti_najstarejse,
            R.string.os_media_razvrsti_ime,
            R.string.os_media_razvrsti_ime_za
        )
        val opis = if (i in samoTaNaprava) getString(R.string.os_media_samo_ta_naprava)
        else {
            val skritih = zacasnoSkritiViri[i].orEmpty().size
            getString(imena[razvrstitev(i)]) + if (skritih > 0) " · ${getString(R.string.os_media_skriti_viri, skritih)}" else ""
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true; isClickable = true
            nextFocusLeftId = meniMediji.id
            // Enak desni rob kot mreza kategorij nad njim (vsaka celica ima marginEnd 12 dp).
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { marginEnd = dp(12) }
            setPadding(dp(16), dp(11), dp(16), dp(11))
            setBackgroundResource(R.drawable.os_kartica_steklo)
            addView(besedilo(15f, osBarva(R.color.os_mint), true).apply {
                text = "↕  " + getString(R.string.os_media_razvrsti_filtriraj)
            })
            addView(besedilo(12f, osBarva(R.color.os_umirjeno)).apply { text = opis })
            setOnClickListener { izberiRazvrstitevInVire(i) }
        }
    }

    /**
     * Glasba: izbira jezika vsebine (jezik besedila skladb) in za njo zvrsti - »poleg zvrsti« (lastnik, 4. 10. 2026).
     * Izbira ostane na zaslonu tudi, kadar v izbranem jeziku ni nicesar: uporabnik jo mora imeti, da se vrne.
     */
    private fun glasbaVrstica(podatki: List<Podatki>): View {
        val jeziki = jezikiIzbire(IzvirniJezik.KODE)
        val izbran = jeziki.indexOfFirst { it.first == glasbaJezik } + 1
        val moznosti = listOf(getString(R.string.os_jezik_vsi)) + jeziki.map { it.second }
        val jezik = besedilo(14f, osBarva(if (izbran > 0) R.color.os_mint else R.color.os_besedilo), true).apply {
            text = moznosti[izbran] + "  ▾"; tag = "k:jezik"; isFocusable = true; isClickable = true
            setPadding(dp(16), dp(8), dp(16), dp(8)); setBackgroundResource(R.drawable.os_meni_postavka)
            setOnClickListener {
                AlertDialog.Builder(this@GlasbaActivity).setTitle(R.string.os_jezik_vsebine)
                    .setSingleChoiceItems(moznosti.toTypedArray(), izbran) { d, i ->
                        d.dismiss()
                        if (i != izbran) {
                            glasbaJezik = if (i == 0) "" else jeziki[i - 1].first
                            SEZNAMI.remove(GLASBA); prikazanePolice.remove(GLASBA)
                            izberi(GLASBA)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        return skokNaPolico(if (podatki.size > 1) podatki.map { it.naslov } else emptyList(), jezik)
    }

    /** Vrstica zvrsti (Glasba, Radio) ali drzav (TV v zivo): klik skoci na polico, kot zanri pri filmih. [spredaj] = izbira pred njimi. */
    private fun skokNaPolico(naslovi: List<String>, spredaj: View? = null): View {
        val niz = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), dp(16), dp(8)) }
        spredaj?.let { it.nextFocusLeftId = meniMediji.id; niz.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) }) }
        naslovi.forEachIndexed { i, cilj ->
            niz.addView(besedilo(14f, osBarva(R.color.os_besedilo), true).apply {
                text = cilj; isFocusable = true; isClickable = true
                setPadding(dp(16), dp(8), dp(16), dp(8)); setBackgroundResource(R.drawable.os_meni_postavka)
                if (i == 0 && spredaj == null) nextFocusLeftId = meniMediji.id
                setOnClickListener {
                    // Prva polica, katere naslov (brez znaka) se zacne s ciljem - tudi police dodatkov.
                    val v = vsebina.findViewWithTag<View>("polica:$cilj") ?: (0 until vsebina.childCount).map { vsebina.getChildAt(it) }
                        .firstOrNull { (it.tag as? String)?.removePrefix("polica:")?.replace(Regex("^[^\\p{L}]+"), "")?.startsWith(cilj) == true }
                    if (v != null) { drsnik.smoothScrollTo(0, v.top.coerceAtLeast(0)); v.nextFocusDownId = View.NO_ID }
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        }
        return android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(niz) }
    }

    /** Skupni dialog uporablja obicajne fokusne kontrolnike, zato je v celoti dosegljiv z D-Padom. */
    private fun izberiRazvrstitevInVire(i: Int) {
        val ovoj = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(8))
        }
        ovoj.addView(besedilo(14f, osBarva(R.color.os_umirjeno), true).apply { text = getString(R.string.os_media_razvrsti) })
        val radio = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val moznosti = intArrayOf(
            R.string.os_media_razvrsti_priporoceno,
            R.string.os_media_razvrsti_najnovejse,
            R.string.os_media_razvrsti_najstarejse,
            R.string.os_media_razvrsti_ime,
            R.string.os_media_razvrsti_ime_za
        )
        moznosti.forEachIndexed { indeks, niz ->
            radio.addView(RadioButton(this).apply {
                id = View.generateViewId(); tag = indeks; text = getString(niz)
                setTextColor(osBarva(R.color.os_besedilo)); isFocusable = true
                isChecked = razvrstitev(i) == indeks
            })
        }
        ovoj.addView(radio)
        ovoj.addView(besedilo(14f, osBarva(R.color.os_umirjeno), true).apply {
            text = getString(R.string.os_media_prikazani_viri); setPadding(0, dp(12), 0, dp(2))
        })
        val samoLokalno = CheckBox(this).apply {
            text = getString(R.string.os_media_samo_ta_naprava)
            setTextColor(osBarva(R.color.os_besedilo)); isFocusable = true
            isChecked = i in samoTaNaprava
        }
        ovoj.addView(samoLokalno)
        val viri = viriIzbire(i)
        val izbire = viri.map { vir ->
            CheckBox(this).apply {
                text = vir.ime; setTextColor(osBarva(R.color.os_besedilo)); isFocusable = true
                isChecked = jeVirViden(i, vir.kljuc); isEnabled = !samoLokalno.isChecked
                ovoj.addView(this)
            }
        }
        samoLokalno.setOnCheckedChangeListener { _, da -> izbire.forEach { it.isEnabled = !da } }
        val jeziki = jezikiTrenutnihPolic(i)
        val izklopljeni = izklopljeniJeziki(i)
        val izbireJezikov = jeziki.map { jezik ->
            CheckBox(this).apply {
                text = imeJezika(jezik); setTextColor(osBarva(R.color.os_besedilo)); isFocusable = true
                isChecked = jezik !in izklopljeni
            }
        }
        if (jeziki.isNotEmpty()) {
            ovoj.addView(besedilo(14f, osBarva(R.color.os_umirjeno), true).apply {
                text = getString(R.string.os_media_jeziki_vsebine); setPadding(0, dp(12), 0, dp(2))
            })
            izbireJezikov.forEach(ovoj::addView)
        }
        val drsniOvoj = ScrollView(this).apply { addView(ovoj) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.os_media_razvrsti_filtriraj)
            .setView(drsniOvoj)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val izbraniNacin = (radio.findViewById<RadioButton>(radio.checkedRadioButtonId)?.tag as? Int)
                    ?: RAZVRSTI_PRIPOROCENO
                razvrstitve[i] = izbraniNacin
                if (samoLokalno.isChecked) samoTaNaprava += i else samoTaNaprava -= i
                zacasnoSkritiViri.getOrPut(i) { mutableSetOf() }.apply {
                    clear()
                    viri.forEachIndexed { indeks, vir -> if (!izbire[indeks].isChecked) add(vir.kljuc) }
                }
                shraniIzklopljeneJezike(i, jeziki.filterIndexed { indeks, _ -> !izbireJezikov[indeks].isChecked }.toSet())
                SEZNAMI.remove(razdelek)
                izberi(razdelek)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        Kontroler.pokazi(dialog)
    }

    /** Jeziki, ki so res navzoci v policah tega razdelka; praznih oznak v dialogu ne prikazujemo. */
    private fun jezikiTrenutnihPolic(i: Int): List<String> {
        val spletne = prikazanePolice[i].orEmpty().flatMap { it.skladbe }
        val shranjene = when (i) {
            DOMOV -> MedijskiViri.nedavno(this) + MedijskiViri.priljubljene(this) + MedijskiViri.seznami(this).flatMap { it.skladbe }
            VIDEO -> MedijskiViri.priljubljene(this).filter { it.video } +
                MedijskiViri.seznami(this).flatMap { it.skladbe }.filter { it.video } + MediaNapredek.seznam(this).map { it.skladba }
            GLASBA -> MedijskiViri.priljubljene(this).filterNot { it.video || it.radio } +
                MedijskiViri.seznami(this).flatMap { it.skladbe }.filterNot { it.video || it.radio }
            RADIO -> MedijskiViri.priljubljene(this).filter { it.radio }
            else -> emptyList()
        }
        val slovensko = Locale.forLanguageTag("sl")
        return (spletne + shranjene).map { JezikiVsebine.oznaka(it.language) }.filter { it.isNotBlank() }
            .distinct().sortedWith(compareBy(Collator.getInstance(slovensko)) { imeJezika(it) })
    }

    private fun imeJezika(koda: String): String {
        val slovensko = Locale.forLanguageTag("sl")
        val ime = Locale.forLanguageTag(koda).getDisplayLanguage(slovensko)
        return ime.takeIf { it.isNotBlank() && !it.equals(koda, ignoreCase = true) }
            ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(slovensko) else it.toString() }
            ?: koda.uppercase(Locale.ROOT)
    }

    private fun kategorije(): View {
        // Televizor (16:9) ima kategorije vedno v eni vrsti; na ozkem zaslonu (tablica pokonci) dve vrsti
        // po dve kartici, na telefonu pokonci ena kartica v vrsti - da opisi niso odrezani.
        val sirina = resources.configuration.screenWidthDp
        val naVrsto = if (jeSirokTv()) 5 else if (sirina < 600) 1 else if (sirina < 900) 2 else 5
        val okvir = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(2)) }
        // Vrstice ustvarimo sproti: kartic je lahko vec kot stiri (npr. TV v zivo), zato ni fiksnega stevila.
        val vrsti = mutableListOf<LinearLayout>()
        var stevec = 0
        fun kat(kljuc: String, res: Int, barva: Int, ime: Int, opis: Int, klik: () -> Unit) {
            while (vrsti.size <= stevec / naVrsto) vrsti += LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also {
                okvir.addView(it, LinearLayout.LayoutParams(-1, -2).apply { if (okvir.childCount > 0) topMargin = dp(10) }) }
            val v = vrsti[stevec / naVrsto]
            stevec++
            v.addView(LinearLayout(this).apply {
                tag = kljuc
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                isFocusable = true; isClickable = true
                setBackgroundResource(R.drawable.os_kartica_steklo)
                setPadding(dp(14), dp(if (jeSirokTv()) 8 else 6), dp(12), dp(if (jeSirokTv()) 8 else 6))
                setOnClickListener { klik() }
                if (v.childCount == 0) nextFocusLeftId = meniMediji.id
                addView(ikona(res, 28, barva))
                val t = LinearLayout(this@GlasbaActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
                t.addView(besedilo(16f, osBarva(R.color.os_besedilo), true).apply { text = getString(ime) })
                t.addView(besedilo(11f, osBarva(R.color.os_umirjeno)).apply { text = getString(opis) })
                addView(t)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        }
        fun odpri(i: Int) { fokusVVsebino = true; izberi(i) }
        kat(KLJUC_GLASBA, R.drawable.os_ikona_glasba, 0xFF8FA8FF.toInt(), R.string.os_mediji_glasba, R.string.os_media_glasba_opis) { odpri(GLASBA) }
        kat(KLJUC_VIDEO, R.drawable.os_ikona_video, 0xFFFF9580.toInt(), R.string.os_glasba_video, R.string.os_media_video_opis) { odpri(VIDEO) }
        kat(KLJUC_RADIO, R.drawable.os_ikona_radio, osBarva(R.color.os_mint), R.string.os_glasba_radio, R.string.os_media_radio_opis) { odpri(RADIO) }
        kat(KLJUC_TV, R.drawable.os_ikona_tv, 0xFFFFC46B.toInt(), R.string.os_mediji_tv_v_zivo, R.string.os_media_tv_opis) { odpri(TV_V_ZIVO) }
        kat(KLJUC_VIRI, R.drawable.os_ikona_mapa, 0xFF7FB2FF.toInt(), R.string.os_mediji_viri, R.string.os_media_viri_opis) { odpri(VIRI) }
        // Predvajalnik: datoteka s te naprave ali spletni naslov - isti zaslon predvajanja kot pri "Odpri z".
        kat(KLJUC_PREDVAJALNIK, R.drawable.os_ikona_predvajaj, 0xFF57D6AD.toInt(), R.string.os_mediji_predvajalnik, R.string.os_media_predvajalnik_opis) { odpriPredvajalnik() }
        return okvir
    }

    // Pogledi plosce "zdaj se predvaja", ki jih tik vsako sekundo osvezi (null, ko plosce ni).
    private var pSlika: ImageView? = null
    private var pSlikaNaslov = ""
    private var pNaslov: TextView? = null
    private var pIzvajalec: TextView? = null
    private var pVir: TextView? = null
    private var pPotek: ProgressBar? = null
    private var pCas: TextView? = null
    private var pPredvajaj: ImageView? = null
    private var pNakljucno: ImageView? = null
    private var pPonavljaj: ImageView? = null
    private var pSrce: ImageView? = null
    private var hHitrost: TextView? = null
    private var hCasovnik: TextView? = null
    /** Ali je plosca narisana za predvajanje (true) ali za zadnje predvajano (false). */
    private var pZaPredvajanje: Boolean? = null
    /** Ali je plosca narisana za predvajanje na zvocniku v omrezju (dejanja ob njej so takrat druga). */
    private var pNaZvocniku = false

    /** "Zdaj se predvaja" (ali zadnje predvajano z gumbom Nadaljuj) in hitra dejanja; null, ce ni nicesar. */
    private fun zdajPlosca(): View? {
        pSlika = null; pNaslov = null; pIzvajalec = null; pVir = null; pPotek = null; pCas = null
        pPredvajaj = null; pNakljucno = null; pPonavljaj = null; pSrce = null; hHitrost = null; hCasovnik = null
        val sk = GlasbaStoritev.trenutna()
        val p = GlasbaStoritev.predvajalnik
        val zadnja = if (sk == null) MedijskiViri.nedavno(this).firstOrNull() else null
        pZaPredvajanje = if (sk != null && p != null) true else if (zadnja != null) false else null
        pNaZvocniku = GlasbaStoritev.naZvocniku()
        val prikaz = sk ?: zadnja ?: return null
        val beli = osBarva(R.color.os_besedilo)
        // Na ozkem zaslonu (tablica pokonci) so hitra dejanja pod plosco, ne ob njej.
        val ozko = !jeSirokTv() && resources.configuration.screenWidthDp < 900
        // Telefon pokonci: kvadratna naslovnica ob besedilu bi vzela vso sirino (naslov "RED...",
        // tipke cez rob - preizkus 1. 10. 2026). Manjsa naslovnica, tipke v svoji vrsti pod njo.
        val telefon = !jeSirokTv() && resources.configuration.screenWidthDp < 480
        var tipkeSpodaj: LinearLayout? = null
        val vrsta = LinearLayout(this).apply { orientation = if (ozko) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }

        val plosca = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(osBarva(R.color.os_kartica_steklo)); setStroke(dp(1), osBarva(R.color.os_kartica_obroba)) }
            setPadding(dp(if (jeSirokTv()) 18 else 14), dp(if (jeSirokTv()) 12 else 10), dp(if (jeSirokTv()) 18 else 16), dp(if (jeSirokTv()) 12 else 10))
        }
        // Oznaka "zdaj se predvaja" je v vrstici z izvajalcem - loceni naslov bi vzel prostor vrsti spodaj.
        val oznaka = getString(if (pZaPredvajanje == true) R.string.os_media_zdaj else R.string.os_media_nazadnje)
        val telo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val slika = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(osBarva(R.color.os_kartica))
            setImageResource(if (prikaz.video) R.drawable.os_ikona_video else if (prikaz.radio) R.drawable.os_ikona_radio else R.drawable.os_ikona_glasba)
        }
        pSlika = slika; pSlikaNaslov = ""
        // Naslovnica odpre celozaslonski prikaz (tam je s tipko dol tudi vrsta).
        val okvir = FrameLayout(this).apply {
            tag = "k:naslovnica"
            isFocusable = true; isClickable = true
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setStroke(dp(2), osBarva(R.color.os_mint)) })
                addState(intArrayOf(), GradientDrawable().apply { setColor(0) })
            }
            nextFocusLeftId = meniMediji.id
            setOnClickListener {
                if (GlasbaStoritev.trenutna() != null) startActivity(Intent(this@GlasbaActivity, PredvajanjeActivity::class.java))
                else predvajaj(listOf(prikaz), 0)
            }
            addView(slika, FrameLayout.LayoutParams(-1, -1))
        }
        // Naslovnica je visoka kot stolpec z besedilom in tipkami ob njej (kvadrat): cim vecja,
        // plosca pa zaradi nje ne zraste - prostor za vrste spodaj ostane (izmerjeno 21. 9. 2026).
        if (telefon) telo.addView(okvir, LinearLayout.LayoutParams(dp(92), dp(92)))
        else {
            okvir.addOnLayoutChangeListener { v, _, t, _, b, _, _, _, _ ->
                val h = b - t
                if (h > 0 && v.layoutParams.width != h) v.post { v.layoutParams = LinearLayout.LayoutParams(h, -1); v.requestLayout() }
            }
            telo.addView(okvir, LinearLayout.LayoutParams(dp(104), -1))
        }
        val desno = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        val oznakaPogled = besedilo(11f, osBarva(R.color.os_mint), true).apply { text = oznaka.uppercase(Locale.getDefault()); letterSpacing = 0.08f }
        // Telefon pokonci: ob naslovnici je stolpec preozek ("NAZADNJE PR...", "Sintel (z...") - oznaka gre cez vso
        // sirino plosce, naslov sme v dve vrstici (preizkus 3. 10. 2026).
        if (telefon) plosca.addView(oznakaPogled, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        else desno.addView(oznakaPogled)
        pIzvajalec = besedilo(if (jeSirokTv()) 15f else 14f, osBarva(R.color.os_umirjeno)).also { desno.addView(it) }
        pNaslov = besedilo(if (jeSirokTv()) 24f else if (telefon) 18f else 21f, beli, true).apply { if (telefon) maxLines = 2 }.also { desno.addView(it) }
        pVir = besedilo(if (jeSirokTv()) 14f else 13f, osBarva(R.color.os_mint)).also { desno.addView(it) }
        if (pZaPredvajanje == true) {
            val potek = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(4)) }
            pPotek = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; progressTintList = ColorStateList.valueOf(osBarva(R.color.os_mint)) }.also { potek.addView(it, LinearLayout.LayoutParams(0, dp(5), 1f)) }
            pCas = besedilo(12f, osBarva(R.color.os_umirjeno)).apply { setPadding(dp(10), 0, 0, 0) }.also { potek.addView(it) }
            desno.addView(potek)
            val tipke = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val pl = GlasbaStoritev.predvajalnik
            if (!prikaz.radio) tipke.addView(gumb(R.drawable.os_ikona_nakljucno, 36, "k:nakljucno") {
                pl?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }; osveziZdaj() }.also { pNakljucno = it.getChildAt(0) as ImageView })
            tipke.addView(gumb(R.drawable.os_ikona_prejsnja, 36, "k:prejsnja") { GlasbaStoritev.prejsnja() })
            tipke.addView(gumb(R.drawable.os_ikona_predvajaj, 48, "k:predvajaj") { GlasbaStoritev.preklopi(); osveziZdaj() }
                .also { pPredvajaj = it.getChildAt(0) as ImageView })
            tipke.addView(gumb(R.drawable.os_ikona_naslednja, 36, "k:naslednja") { GlasbaStoritev.naslednja() })
            if (!prikaz.radio) tipke.addView(gumb(R.drawable.os_ikona_ponavljaj, 36, "k:ponavljaj") {
                pl?.let { it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } }
                osveziZdaj() }.also { pPonavljaj = it.getChildAt(0) as ImageView })
            if (MedijskiViri.shranljiva(prikaz)) tipke.addView(gumb(R.drawable.os_ikona_srce, 36, "k:srce") {
                GlasbaStoritev.trenutna()?.let { t ->
                    val da = MedijskiViri.preklopiPriljubljeno(this, t)
                    Toast.makeText(this, if (da) R.string.os_mediji_dodano_prilj else R.string.os_mediji_odstranjeno_prilj, Toast.LENGTH_SHORT).show()
                    izberi(DOMOV)
                } }.also { pSrce = it.getChildAt(0) as ImageView })
            (tipke.getChildAt(0))?.nextFocusLeftId = meniMediji.id
            if (telefon) { tipke.gravity = Gravity.CENTER; tipkeSpodaj = tipke } else desno.addView(tipke)
        } else {
            val nadaljuj = LinearLayout(this).apply {
                tag = "k:nadaljuj"
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                isFocusable = true; isClickable = true; nextFocusLeftId = meniMediji.id
                setBackgroundResource(R.drawable.os_kartica_steklo)
                setPadding(dp(14), dp(8), dp(18), dp(8))
                setOnClickListener { predvajaj(listOf(prikaz), 0) }
                addView(ikona(R.drawable.os_ikona_predvajaj, 26, osBarva(R.color.os_mint)))
                addView(besedilo(15f, beli, true).apply { text = getString(R.string.os_media_nadaljuj); setPadding(dp(10), 0, 0, 0) })
            }
            // Telefon pokonci: gumb pod naslovnico na sredini (ob njej bi se odrezal v "Nadalj...").
            if (telefon) tipkeSpodaj = LinearLayout(this).apply { gravity = Gravity.CENTER; addView(nadaljuj) }
            else desno.addView(nadaljuj, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        }
        telo.addView(desno, LinearLayout.LayoutParams(0, -2, 1f))
        plosca.addView(telo)
        tipkeSpodaj?.let { plosca.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }) }
        if (ozko) {
            vrsta.addView(plosca, LinearLayout.LayoutParams(-1, -2))
            if (pZaPredvajanje == true) vrsta.addView(hitraDejanja(prikaz), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        } else {
            // Plosca doloci visino vrste, hitra dejanja se ji prilagodijo.
            vrsta.addView(plosca, LinearLayout.LayoutParams(0, -2, if (jeSirokTv()) 2.35f else 2f))
            if (pZaPredvajanje == true) vrsta.addView(hitraDejanja(prikaz), LinearLayout.LayoutParams(0, -1, if (jeSirokTv()) 0.9f else 1f).apply { marginStart = dp(12) })
        }
        osveziPlosco(prikaz)
        return vrsta
    }

    /** Samo dejanja, ki delujejo: zatemnitev, hitrost, casovnik izklopa, ustavi (celozaslonsko je klik na naslovnico). */
    private fun hitraDejanja(sk: Jamendo.Skladba): View {
        val beli = osBarva(R.color.os_besedilo)
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(osBarva(R.color.os_kartica_steklo)); setStroke(dp(1), osBarva(R.color.os_kartica_obroba)) }
            setPadding(dp(12), dp(if (jeSirokTv()) 8 else 10), dp(12), dp(8))
        }
        v.addView(besedilo(15f, beli, true).apply { text = getString(R.string.os_media_hitra); setPadding(dp(6), 0, 0, dp(4)) })
        fun dejanje(kljuc: String, res: Int, ime: String, klik: () -> Unit): TextView {
            val t = besedilo(13f, beli).apply { text = ime; setPadding(dp(10), 0, 0, 0) }
            v.addView(LinearLayout(this).apply {
                tag = kljuc
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                isFocusable = true; isClickable = true
                setBackgroundResource(R.drawable.os_meni_postavka)
                setPadding(dp(8), dp(if (jeSirokTv()) 4 else 5), dp(8), dp(if (jeSirokTv()) 4 else 5))
                setOnClickListener { klik() }
                addView(ikona(res, 18, beli)); addView(t)
            })
            return t
        }
        fun predvajanje(extra: String?) = startActivity(Intent(this, PredvajanjeActivity::class.java).apply { if (extra != null) putExtra(extra, true) })
        if (!sk.video) dejanje("k:zatemni", R.drawable.os_ikona_luna, getString(R.string.os_media_zatemni)) { predvajanje(PredvajanjeActivity.ZATEMNI) }
        // Hitrosti zvocnik v omrezju ne zna spremeniti.
        if (!sk.radio && !GlasbaStoritev.naZvocniku()) hHitrost = dejanje("k:hitrost", R.drawable.os_ikona_hitrost, "") {
            GlasbaStoritev.predvajalnik?.let { p ->
                val zdaj = p.playbackParameters.speed
                p.setPlaybackSpeed(HITROSTI.firstOrNull { it > zdaj + 0.01f } ?: HITROSTI.first())
            }
            osveziZdaj()
        }
        hCasovnik = dejanje("k:casovnik", R.drawable.os_ikona_casovnik, "") {
            val ostalo = GlasbaStoritev.casovnikMinut()
            GlasbaStoritev.nastaviCasovnik(CASOVNIK.firstOrNull { it > ostalo } ?: 0)
            osveziZdaj()
        }
        // Zvocnik v omrezju (DLNA): zvocnik vir potegne sam, telefon je le daljinec.
        val naZvocniku = Zvocniki.aktivni
        if (naZvocniku != null) {
            dejanje("k:zvocnik", R.drawable.os_ikona_predvajaj_na, getString(R.string.zvocnik_na, naZvocniku.ime)) { upravljajZvocnik() }
        } else if (GlasbaStoritev.zaZvocnikPonudba(this, sk) { if (!isFinishing) osvezi(razdelek) }) {
            dejanje("k:zvocnik", R.drawable.os_ikona_predvajaj_na, getString(R.string.zvocnik_predvajaj_na)) { izberiZvocnik(sk) }
        }
        dejanje("k:ustavi", R.drawable.os_ikona_ustavi, getString(R.string.os_media_ustavi)) { GlasbaStoritev.ustavi(this); glavna.postDelayed({ if (razdelek == DOMOV && !podstrani.odprta) izberi(DOMOV) }, 300) }
        return v
    }

    private fun izberiZvocnik(sk: Jamendo.Skladba) = ZvocnikIzbira.izberi(this, sk) { if (!isFinishing) osvezi(razdelek) }
    private fun upravljajZvocnik() = ZvocnikIzbira.upravljaj(this) { if (!isFinishing) osvezi(razdelek) }

    /** Osvezi besedila, potek in stanja tipk na plosci (vsako sekundo in ob spremembi). */
    private fun osveziPlosco(zadnja: Jamendo.Skladba? = null) {
        val sk = GlasbaStoritev.trenutna() ?: zadnja ?: MedijskiViri.nedavno(this).firstOrNull() ?: return
        val p = GlasbaStoritev.predvajalnik
        pNaslov?.text = sk.naslov
        pIzvajalec?.text = if (SpletniVir.jeEnota(sk)) "" else sk.izvajalec
        pVir?.text = when {
            GlasbaStoritev.naZvocniku() -> Zvocniki.napis(this).orEmpty()
            sk.radio -> getString(R.string.os_media_v_zivo)
            // Naloga 96: kanal seznama IPTV ima v [Jamendo.Skladba.streznik] seznam, iz katerega je - ni PeerTube.
            IptvSeznami.jeKanal(sk) -> getString(R.string.os_mediji_tv_v_zivo) + " · " + KanaliPravila.imeSeznama(sk.streznik)
            sk.video && sk.streznik.isNotBlank() -> "PeerTube · ${sk.streznik}"
            sk.povezava.contains("jamen") -> getString(R.string.os_glasba_vir, sk.povezava.removePrefix("https://").removePrefix("http://"))
            else -> ""
        }
        val slika = pSlika
        // Vgrajeni kanal nima slike v podatkih: njegov logotip je shranjen posebej ([TvVZivo.ikona], brez omrezja).
        val vgrajenBrezSlike = sk.id.startsWith("tv:") && sk.slika.isBlank()
        if (slika != null && vgrajenBrezSlike) {
            if (pSlikaNaslov != sk.id) TvVZivo.ikona(this, sk.id.removePrefix("tv:"))?.let {
                pSlikaNaslov = sk.id
                slika.scaleType = ImageView.ScaleType.FIT_CENTER
                slika.setImageDrawable(it)
            }
        } else if (slika != null && sk.slika != pSlikaNaslov) {
            pSlikaNaslov = sk.slika
            // Logotip kanala ali postaje pokazemo cel (obrezan je izgubil prvo crko - izmerjeno 5. 10. 2026);
            // naslovnice in plakati zapolnijo okvir.
            slika.scaleType = if (sk.radio || sk.id.startsWith("tv:") || Stremio.jeVZivo(sk)) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            naloziSliko(sk.slika, slika)
        }
        if (p == null || pZaPredvajanje != true) return
        // Sprotni tok pomocnika: polozaj in trajanje glede na izvirnik (tok tece od zamika naprej).
        val tokPomocnika = SprotnaPomoc.tokZa(sk)
        // Cas, trajanje in stanje tistega, ki igra: te naprave ali zvocnika v omrezju.
        val naZvocniku = GlasbaStoritev.naZvocniku()
        val trajanje = if (naZvocniku) GlasbaStoritev.trajanjeMs() else if (tokPomocnika != null) tokPomocnika.trajanjeMs.coerceAtLeast(0L) else p.duration.takeIf { it > 0 } ?: 0L
        val polozaj = if (naZvocniku) GlasbaStoritev.polozajMs() else (tokPomocnika?.zamikMs ?: 0L) + p.currentPosition.coerceAtLeast(0)
        pPotek?.progress = if (trajanje > 0) (polozaj * 1000 / trajanje).toInt() else 0
        pCas?.text = if (trajanje > 0) "${cas(polozaj)} / ${cas(trajanje)}" else cas(polozaj)
        pPredvajaj?.setImageResource(if (GlasbaStoritev.igra()) R.drawable.os_ikona_pavza else R.drawable.os_ikona_predvajaj)
        val mint = osBarva(R.color.os_mint); val beli = osBarva(R.color.os_besedilo)
        pNakljucno?.imageTintList = ColorStateList.valueOf(if (p.shuffleModeEnabled) mint else beli)
        pPonavljaj?.imageTintList = ColorStateList.valueOf(if (p.repeatMode != Player.REPEAT_MODE_OFF) mint else beli)
        pSrce?.imageTintList = ColorStateList.valueOf(if (MedijskiViri.jePriljubljena(this, sk)) 0xFFFF6B7A.toInt() else beli)
        hHitrost?.text = getString(R.string.os_media_hitrost, String.format(Locale.ROOT, "%s×", p.playbackParameters.speed.toString().removeSuffix(".0")))
        hCasovnik?.text = getString(R.string.os_media_casovnik,
            GlasbaStoritev.casovnikMinut().let { if (it > 0) getString(R.string.os_media_min, it) else getString(R.string.os_media_izklopljen) })
    }

    private fun cas(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /** Vir medijev: vgrajen (ta naprava, Safeer Link, postaje, PeerTube) ali dodan ([dodan]). */
    private class Vir(val kljuc: String, val ikona: Int, val barva: Int, val ime: String, val opis: String,
                      val odpri: () -> Unit, val dodan: MedijskiViri.Vir? = null)

    /** Vsi viri na enem mestu (Moji viri); na plosci so tisti, ki jih uporabnik pripne. */
    private fun vsiViri(): List<Vir> {
        val datoteke = { startActivity(Intent(this, DatotekeActivity::class.java)) }
        val televizor = Naprava.jeTelevizor(this@GlasbaActivity)
        fun razdelek(i: Int): () -> Unit = { fokusVVsebino = true; izberi(i) }
        return listOf(
            Vir("tv", if (televizor) R.drawable.os_ikona_zaslon else R.drawable.os_ikona_naprava, osBarva(R.color.os_mint),
                getString(if (televizor) R.string.os_media_ta_tv else R.string.os_media_ta_naprava), getString(R.string.os_media_ta_tv_opis), datoteke),
            Vir("link", R.drawable.os_ikona_racunalnik, 0xFF8FA8FF.toInt(), getString(R.string.os_media_link), getString(R.string.os_media_link_opis), datoteke),
        ) + listOfNotNull(
            // Naloga 57: postaje in PeerTube nista vec vgrajena vira - ploscici ostaneta, ce ima uporabnik svoje postaje
            // (priljubljene) oziroma svoje streznike PeerTube.
            MedijskiViri.priljubljene(this).count { it.radio }.takeIf { it > 0 || MedijskiViri.VGRAJENI_SPLETNI_VIRI ||
                imenikDodan(VIR_RADIO) || imenikDodan(VIR_TUNEIN) }?.let { n ->
                Vir("radio", R.drawable.os_ikona_radio, 0xFFFF9580.toInt(), getString(R.string.os_mediji_postaje),
                    getString(R.string.os_media_stevilo_prilj, n), razdelek(RADIO)) },
            MedijskiViri.streznikiPeerTube(this).size.takeIf { it > 0 }?.let { n ->
                Vir("peertube", R.drawable.os_ikona_video, 0xFFFF9580.toInt(), "PeerTube",
                    getString(R.string.os_media_stevilo_streznikov, n), razdelek(VIDEO)) },
        ) + MedijskiViri.vgrajeniPeerTube(this).map { s ->
            // Vgrajeni strezniki PeerTube so viri kot vsi drugi: zadrzan OK -> Izbrisi vir (nazaj z Dodaj vir).
            val v = MedijskiViri.vgrajenPeerTubeVir(s)
            Vir(MedijskiViri.kljucPripetega(v), R.drawable.os_ikona_video, 0xFFFF9580.toInt(), s, "PeerTube · $s", razdelek(VIDEO), v)
        } + (if (si.safeer.tv.BuildConfig.FLAVOR == "brskalnik") emptyList() else listOf(
            Vir("magnet", R.drawable.os_ikona_link, 0xFFB69CFF.toInt(), getString(R.string.magnet_naslov), getString(R.string.magnet_vir_opis),
                { startActivity(Intent(this, MagnetActivity::class.java)) }),
        )) + listOf(
            // Naloga 87: predpomnilnik Media Center - mesto (ta TV, USB, naprava v Linku), velikost, zasedenost, ciscenje.
            Vir("predpomnilnik", R.drawable.os_ikona_mreza, 0xFF9FD8C8.toInt(), getString(R.string.os_predpomnilnik_naslov), opisPredpomnilnika(),
                { nastavitvePredpomnilnika() }),
        ) + MedijskiViri.vsi(this).map { v ->
            Vir(MedijskiViri.kljucPripetega(v), when { v.jePeerTube -> R.drawable.os_ikona_video; v.jeSplet || v.jeDodatek -> R.drawable.os_ikona_splet
                    v.jeImenik -> R.drawable.os_ikona_radio; v.jeBesedila -> R.drawable.os_ikona_podnapisi; else -> R.drawable.os_ikona_glasba },
                // Dodatek: ime iz manifesta (Cinemeta, Torrentio ...) in samo streznik - naslov ima lahko kljuc storitve.
                0xFF7FB2FF.toInt(), if (v.jeStremio) Stremio.imeIzPredpomnilnika(v.naslov) ?: v.ime else v.ime, (when {
                    v.jePeerTube -> "PeerTube · ${v.naslov}"
                    // Naloga 57d: dodatek, ki zaporedoma ne odgovarja, ima oznako (izgine sama, ko spet odgovori).
                    v.jeStremio -> getString(R.string.os_mediji_dodatek_stremio) + " · " + (android.net.Uri.parse(Stremio.osnova(v.naslov)).host ?: "") +
                        // Pregled S5: skrivnosti dodatka ta naprava ne zna prebrati (Keystore) - uporabnik izve, kaj storiti.
                        (if (!SkrivniDodatki.pozna(this, v.naslov)) " · " + getString(R.string.os_mediji_dodatek_dodaj_znova,
                            getString(R.string.os_glasba_naslov) + " → " + getString(R.string.os_mediji_viri) + " → " + getString(R.string.os_mediji_dodatki)) else "") +
                        (if (Stremio.neOdgovarja(v.naslov)) " · " + opisNeodziva(v.naslov) else "") +
                        // Naloga 77: dodatek samo za iskanje (YouTubio) nima mreze - uporabnik mora vedeti, kje ga najde.
                        (if (Stremio.samoIskanje(v.naslov) == true) " · " + getString(R.string.os_mediji_samo_iskanje) else "") +
                        // Naloga 79: dodatek daje tokove samo z narocnino (povezave na placilo ne kazemo).
                        (if (Stremio.brezProstihTokov(v.naslov)) " · " + getString(R.string.os_mediji_tokovi_narocnina) else "") +
                        // Naloga 124: dodatek zahteva novejsi Safeer ali manjka obvezna nastavitev.
                        (if (Stremio.zahtevaNovejsiSafeer(v.naslov)) " · " + getString(R.string.os_dodatek_posodobi_safeer) else "") +
                        (Stremio.razsiritevZdaj(v.naslov)?.nastavitve?.takeIf { DodatkiNastavitve.manjkaObvezna(this, Stremio.osnova(v.naslov), it) }
                            ?.let { " · " + getString(R.string.os_dodatek_potrebuje_nastavitve) } ?: "") +
                        // Naloga 75: napredek ali izid pregleda kanalov dodatka.
                        (PregledKanalov.opis(this, v.naslov)?.let { " · $it" } ?: "")
                    v.tip == MedijskiViri.API -> "API · " + (try { java.net.URL(v.naslov.substringBefore('|').trim()).host } catch (_: Exception) { "" })
                    // Naloga 80: seznam IPTV pove, koliko kanalov ima, in napredek pregleda mrtvih.
                    // Grok 39, I5: ponudnik je seznam zavrnil ali prenos ni uspel - kaj je in kdaj poskusimo znova (brez gesla na zaslonu).
                    v.jeSeznam && IptvSeznami.napaka(this, v.naslov) != null -> IptvPravila.zaPrikaz(v.naslov) + " · " +
                        opisNapakeSeznama(IptvSeznami.napaka(this, v.naslov)!!)
                    v.jeSeznam && (IptvSeznami.podatki(this, v.naslov)?.kanalov ?: 0) > 0 -> IptvPravila.zaPrikaz(v.naslov) +
                        " · " + resources.getQuantityString(R.plurals.os_iptv_kanalov, IptvSeznami.podatki(this, v.naslov)!!.kanalov, IptvSeznami.podatki(this, v.naslov)!!.kanalov) +
                        (PregledKanalov.opis(this, v.naslov)?.let { " · $it" } ?: "")
                    // Naloga 89: kje uporabnik besedila najde (predvajalnik), brez naslova streznika.
                    v.jeBesedila -> getString(R.string.os_besedila_vir_opis)
                    // Seznam s poverilnico (Xtream): na zaslonu samo gostitelj - geslo ne.
                    v.jeSeznam -> IptvPravila.zaPrikaz(v.naslov)
                    // Tok z racunom (nadomestek ali pravi naslov, ki ga ni bilo mogoce sifrirati): samo gostitelj (26. 10. 2026).
                    IptvPravila.virZRacunom(v.tip, v.naslov) -> IptvPravila.zaPrikaz(v.naslov)
                    else -> v.naslov.removePrefix("https://").removePrefix("http://")
                }) +
                    // Grok 60, Ů2: podedovan vir z racunom ali vir neznanega izvora se ne prenasa samodejno - uporabnik izve, kaj storiti
                    // (zadrzan OK -> Dovoli samodejni prenos). Vir iz ozjega kroga (26. 10. 2026 12:10) je takoj uporaben, brez pripisa.
                    (if (IptvPravila.virZRacunom(v.tip, v.naslov) && !MedijskiViri.uporabaDovoljena(this, v.naslov))
                        " · " + getString(R.string.os_iptv_ni_dodan_tu) else ""), {
                    when {
                        v.jePeerTube -> { fokusVVsebino = true; izberi(VIDEO) }
                        // Naloga 69: imenik postaj odpre Radio (domace postaje, zvrsti, iskanje).
                        v.jeImenik -> { fokusVVsebino = true; izberi(RADIO) }
                        // Naloga 89: besedila se odprejo v predvajalniku (Vec moznosti -> Besedilo pesmi).
                        v.jeBesedila -> Toast.makeText(this, R.string.os_besedila_vir_opis, Toast.LENGTH_LONG).show()
                        // Dodatek: pokazemo shranjeni naslov (predvajanje prek dodatkov je naslednji korak).
                        // Naloga 77: dodatek samo za iskanje odpre iskanje (njegovi zadetki so med zadetki dodatkov).
                        v.jeStremio && Stremio.samoIskanje(v.naslov) == true -> odpriIskanje("")
                        v.jeStremio -> odpriRazdelekDodatka(v.naslov)
                        // Spletna stran: odpre jo brskalnik Safeer, ki predvaja vse (z vgrajenim Scitom).
                        v.jeSplet -> odpriStran(v.naslov, v.ime)
                        // API: iscemo po njem skupaj z vsemi viri.
                        v.tip == MedijskiViri.API -> odpriIskanje("")
                        else -> predvajaj(listOf(MedijskiViri.kotSkladba(v)), 0)
                    }
                }, v)
        }
    }

    private fun cip(kljuc: String, res: Int, barva: Int, ime: String, opis: String, klik: () -> Unit) = LinearLayout(this).apply {
        tag = kljuc
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        isFocusable = true; isClickable = true
        setBackgroundResource(R.drawable.os_kartica_steklo)
        setPadding(dp(12), dp(7), dp(14), dp(7))
        setOnClickListener { klik() }
        addView(ikona(res, 24, barva))
        val t = LinearLayout(this@GlasbaActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        t.addView(besedilo(13f, osBarva(R.color.os_besedilo), true).apply { text = ime })
        t.addView(besedilo(11f, osBarva(R.color.os_umirjeno)).apply { text = opis })
        addView(t)
    }

    /**
     * Tvoji viri na plosci - kot priljubljene aplikacije na domacem zaslonu: samo pripeti, v uporabnikovem
     * redu. Kar ne gre celo na zaslon, je pod zadnjo kartico "Vsi viri" - nobena kartica ni prerezana.
     */
    private fun tvojiViri(): View {
        val vsi = vsiViri().associateBy { it.kljuc }
        val pripeti = MedijskiViri.pripeti(this).mapNotNull { vsi[it] }
        val vrsta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun lp() = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) }
        for (v in pripeti) vrsta.addView(cip("k:v:" + v.kljuc, v.ikona, v.barva, v.ime, v.opis, v.odpri).apply {
            setOnLongClickListener { moznostiPripetega(v); true } }, lp())
        val vec = cip(KLJUC_VSI_VIRI, R.drawable.os_ikona_mreza, osBarva(R.color.os_besedilo),
            getString(if (pripeti.isEmpty()) R.string.os_media_izberi_vire else R.string.os_media_vsi_viri),
            getString(R.string.os_mediji_viri)) { fokusVVsebino = true; izberi(VIRI) }
        vec.visibility = if (pripeti.isEmpty()) View.VISIBLE else View.GONE
        vrsta.addView(vec, lp())
        vrsta.getChildAt(0)?.nextFocusLeftId = meniMediji.id
        val okvir = HorizontalScrollView(this).apply {
            addView(vrsta); isHorizontalScrollBarEnabled = false
            setPadding(dp(4), dp(4), dp(4), dp(4)); clipToPadding = false
        }
        // Po postavitvi izmerimo: zadnje kartice, ki ne gredo cele na zaslon, se umaknejo pod "Vsi viri".
        okvir.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                okvir.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val sirina = okvir.width - okvir.paddingLeft - okvir.paddingRight
                if (sirina <= 0 || pripeti.isEmpty()) return
                val prosto = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                fun w(v: View) = v.run { measure(prosto, prosto); measuredWidth + dp(10) }
                val kartice = (0 until vrsta.childCount).map { vrsta.getChildAt(it) }.filter { it !== vec }
                var skupaj = kartice.sumOf { w(it) }
                if (skupaj <= sirina) return
                vec.visibility = View.VISIBLE
                var skritih = 0
                for (k in kartice.asReversed()) {
                    if (skupaj + w(vec) <= sirina) break
                    skupaj -= w(k); k.visibility = View.GONE; skritih++
                }
                (vec.getChildAt(1) as LinearLayout).getChildAt(1).let { (it as TextView).text = "+$skritih" }
            }
        })
        return okvir
    }

    /** Vidni viri na plosci, v redu z zaslona (brez "Vsi viri"). */
    private fun vidniPripeti(): List<String> = vsebina.findViewWithTag<View>(KLJUC_VSI_VIRI)?.parent.let { it as? LinearLayout }?.let { v ->
        (0 until v.childCount).map { v.getChildAt(it) }.filter { it.visibility == View.VISIBLE && it.tag != KLJUC_VSI_VIRI }
            .mapNotNull { (it.tag as? String)?.removePrefix("k:v:") }
    } ?: emptyList()

    /**
     * Zadrzan OK na viru na plosci: Premakni (puscici levo/desno ga neseta po vrsti, OK konca) ali umakni
     * s plosce - enako kot priljubljena aplikacija na domacem zaslonu. Na dotik (tablica) premikamo po mestu.
     */
    private fun moznostiPripetega(v: Vir) {
        val vidni = vidniPripeti()
        val mesto = vidni.indexOf(v.kljuc)
        val daljinec = Naprava.jeTelevizor(this@GlasbaActivity)
        val dejanja = ArrayList<Pair<String, () -> Unit>>()
        if (daljinec && vidni.size > 1) dejanja.add(getString(R.string.os_premakni) to { zacniPremik(v.kljuc) })
        if (!daljinec && mesto > 0) dejanja.add(getString(R.string.os_spletne_levo) to { premakniPripetega(v.kljuc, -1) })
        if (!daljinec && mesto in 0 until vidni.size - 1) dejanja.add(getString(R.string.os_spletne_desno) to { premakniPripetega(v.kljuc, 1) })
        dejanja.add(getString(R.string.os_media_s_plosce) to {
            val sosed = vidni.getOrNull(mesto + 1) ?: vidni.getOrNull(mesto - 1)
            MedijskiViri.preklopiPripet(this, v.kljuc)
            izberi(DOMOV)
            drsnik.post { vsebina.findViewWithTag<View>(if (sosed != null) "k:v:$sosed" else KLJUC_VSI_VIRI)?.requestFocus() }
        })
        pokaziBrisanje(AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(v.ime)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(getString(R.string.os_preklici), null)
        )
    }

    /** Vir, ki ga uporabnik ta trenutek premika po plosci (null = ne premika). */
    private var premikam: String? = null
    /** Tipka, ki je premikanje koncala: njen dvig ne sme se odpreti vira. */
    private var pogoltniGor = -1

    private fun zacniPremik(kljuc: String) {
        premikam = kljuc
        drsnik.post { oznaciPremik() }
    }

    private fun oznaciPremik() {
        val v = vsebina.findViewWithTag<View>("k:v:" + (premikam ?: return)) ?: return
        v.requestFocus()
        v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(120).start()
        // Kot pri priljubljenih aplikacijah: puscici ob imenu povesta, da se kartica premika.
        (((v as? LinearLayout)?.getChildAt(1) as? LinearLayout)?.getChildAt(0) as? TextView)?.let { it.text = "◀  " + it.text + "  ▶" }
        stanje.text = getString(R.string.os_premakni_namig)
    }

    /** Premik samo med vidnimi viri: vir ne sme izginiti pod "Vsi viri". */
    private fun premakniPripetega(kljuc: String, zamik: Int) {
        val vidni = vidniPripeti()
        if (vidni.indexOf(kljuc) + zamik !in vidni.indices) return
        if (!MedijskiViri.premakniPripet(this, kljuc, zamik)) return
        izberi(DOMOV)
        drsnik.post { if (premikam != null) oznaciPremik() else vsebina.findViewWithTag<View>("k:v:$kljuc")?.requestFocus() }
    }

    private fun koncajPremik() {
        premikam = null
        izberi(DOMOV)
    }

    /** Med premikanjem gredo tipke samo premikanju: levo/desno premakne, vse ostalo konca. */
    override fun dispatchKeyEvent(dogodek: KeyEvent): Boolean {
        zadnjiDotik = android.os.SystemClock.uptimeMillis()
        kljucVRisanju = null
        vrniKljuc = null
        // Uporabnik izbira sam: cakajoca "izbira na prvo kartico" ne sme vec skociti (klik jo nastavi sele za tem).
        if (dogodek.action == KeyEvent.ACTION_DOWN) fokusVVsebino = false
        val k = premikam
        if (k != null) {
            if (dogodek.action == KeyEvent.ACTION_DOWN) when (dogodek.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> premakniPripetega(k, -1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> premakniPripetega(k, 1)
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { }
                else -> { pogoltniGor = dogodek.keyCode; koncajPremik() }
            }
            return true
        }
        if (dogodek.action == KeyEvent.ACTION_UP && dogodek.keyCode == pogoltniGor) { pogoltniGor = -1; return true }
        return super.dispatchKeyEvent(dogodek)
    }

    /**
     * Moji viri: dodaj vir in vsi viri. Zadrzan OK da vir na plosco ali ga umakne - takoj, brez okna
     * (zvezdica in utrip povesta dovolj), kot pri aplikacijah. Dodan vir ima se "Izbrisi", zato tam izbira.
     */
    private fun viriVrste(): List<Vrsta> {
        val pripeti = MedijskiViri.pripeti(this)
        // Lastnik 27. 10. 2026: seznami predvajanja spadajo h glasbi (tam imajo svojo vrsto) - v Mojih virih jih ni vec na vrhu.
        val usenetVpisan = try { UsenetPonudnik.get(this).stanje().vpisan } catch (_: Throwable) { false }
        return listOf(Vrsta("", listOf(
            Kartica(getString(R.string.os_mediji_dodaj), getString(R.string.os_mediji_dodaj_opis), "", { dodajVir() }, ikona = R.drawable.os_ikona_plus),
            // Naloga 125: dolg pritisk vklopi ali izklopi razvojni nacin dodatkov.
            Kartica(getString(R.string.os_mediji_dodatki), getString(R.string.os_mediji_dodatki_opis), "", { dodajDodatke() }, { preklopiRazvoj() }, ikona = R.drawable.os_ikona_plus),
            Kartica(getString(R.string.os_uvoz_naslov), getString(R.string.os_uvoz_opis), "", { uvoziSeznam() }, ikona = R.drawable.os_ikona_plus),
            // Naloga 119: TorBox - zasebni in hitrejsi torrenti s kljucem uporabnika.
            // Naloga 128: TorBox, Real-Debrid in AllDebrid na enem mestu.
            Kartica(getString(R.string.os_debrid), opisDebrida(), "", { nastavitveDebrida() }, ikona = R.drawable.os_ikona_zaklep),
            // Lastnik 27. 10. 2026: lasten ponudnik Usenet (filmi in serije neposredno, brez TorBoxa) - vidno v Mojih virih.
            Kartica(getString(if (usenetVpisan) R.string.os_usenet_ponudnik_naslov else R.string.os_usenet_dodaj_ponudnika),
                UsenetPonudnikOkno.vrstica(this), "", { UsenetPonudnikOkno.pokazi(this) { osveziNaMestu() } },
                ikona = if (usenetVpisan) R.drawable.os_ikona_zaklep else R.drawable.os_ikona_plus)) +
            vsiViri().map { v -> Kartica((if (v.kljuc in pripeti) "★ " else "") + v.ime, v.opis, "", { v.odpri() }, { dolgoNaViru(v) }, ikona = v.ikona) } +
            // Naloga 124: mape dodatkov (protokol v1) - npr. uporabnikov racun pri storitvi.
            karticeMapDodatkov() +
            // Naloga 125: razvojni nacin - kartica z orodji, samo ko je vklopljen.
            (if (DodatkiRazvoj.vklopljen) listOf(Kartica(getString(R.string.os_razvoj_naslov), getString(R.string.os_razvoj_opis_vkl), "",
                { meniRazvoja() }, ikona = R.drawable.os_ikona_nastavitve)) else emptyList()),
            mreza = true))
    }

    /** V Linku seznam izgine na vseh napravah (SeznamiSink) - to mora uporabnik vedeti, preden potrdi. */
    private fun vprasanjeOdstraniSeznam(): Int =
        if (link.jeKrajevni()) R.string.os_mediji_odstrani_seznam else R.string.os_mediji_odstrani_seznam_vsepovsod

    /** Dolg dotik na seznamu v Brskaj: predvajaj ali odstrani (s potrditvijo). */
    private fun meniSeznama(sz: MedijskiViri.Seznam) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(sz.ime)
            .setItems(arrayOf(getString(R.string.os_mediji_predvajaj), getString(R.string.os_mediji_odstrani_seznam))) { _, k ->
                if (k == 0) sz.skladbe.first().let { prva -> if (jeVrstaPosnetkov(prva, sz)) predvajajVrstoPosnetkov(sz.skladbe, prva) else predvajaj(sz.skladbe, 0) }
                else AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(sz.ime)
                    .setMessage(vprasanjeOdstraniSeznam())
                    // Stran ostane ista (Domov, Moja glasba, Zate, Brskaj ali »Pokazi vse« seznamov) - prej je izbris vrgel v Brskaj.
                    .setPositiveButton(R.string.os_mediji_odstrani_seznam) { _, _ -> MedijskiViri.odstraniSeznam(this, sz.ime); osveziNaMestu() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }.show()
    }

    private fun dolgoNaViru(v: Vir) {
        val preklopi = {
            MedijskiViri.preklopiPripet(this, v.kljuc)
            izberi(VIRI)
            drsnik.post {
                window.decorView.findFocus()?.let { f ->
                    f.animate().scaleX(1.12f).scaleY(1.12f).setDuration(110).withEndAction { f.animate().scaleX(1f).scaleY(1f).setDuration(140).start() }.start()
                }
            }
            Unit
        }
        val dodan = v.dodan ?: return preklopi()
        val na = v.kljuc in MedijskiViri.pripeti(this)
        val dejanja = listOf(getString(if (na) R.string.os_media_s_plosce else R.string.os_media_na_plosco) to preklopi) +
            // Naloga 75: rocni pregled kanalov dodatka (tudi kanalov z znanim izidom - mrtev ni za vedno).
            // Naloga 79b: zaklenjen dodatek - placilo je stvar uporabnika in dodatka; odpremo samo, ce uporabnik to izbere.
            (if (dodan.jeStremio && Stremio.povezavaOdklepa(dodan.naslov) != null) listOf(getString(R.string.os_mediji_odkleni) to {
                odkleniPriDodatku(dodan.naslov) }) else emptyList()) +
            // Naloga 124: nastavitve, ki jih je dodatek opisal (protokol v1).
            (if (dodan.jeStremio && Stremio.razsiritevZdaj(dodan.naslov)?.nastavitve?.isNotEmpty() == true)
                listOf(getString(R.string.os_dodatek_nastavitve) to { nastavitveDodatka(dodan.naslov) }) else emptyList()) +
            // Naloga 113: dodatek preverimo takoj (manifest, kot pri vnosu) - oznaka »ne odgovarja« izgine ali dobi svezo kodo.
            (if (dodan.jeStremio) listOf(getString(R.string.os_mediji_preveri_dodatek) to { preveriDodatekZdaj(dodan.naslov) }) else emptyList()) +
            // Lastnik, 26. 10. 2026: vira s poverilnico (Xtream, dodatek z zetonom) ne pregledamo nikoli - izbire ni.
            (if ((dodan.jeStremio || (dodan.jeSeznam && (IptvSeznami.podatki(this, dodan.naslov)?.kanalov ?: 0) > 0)) &&
                PregledKanalov.smeVir(this, dodan.naslov, dodan.jeSeznam)) listOf(getString(R.string.os_pregled_kanalov_zdaj) to {
                val zacet = PregledKanalov.zacni(applicationContext, dodan.naslov, rocno = true, jeSeznam = dodan.jeSeznam)
                Toast.makeText(this, if (zacet) R.string.os_pregled_kanalov_zacet else R.string.os_pregled_kanalov_po_predvajanju, Toast.LENGTH_SHORT).show()
            }) else emptyList()) +
            // Grok 60, Ů2 in Grok 64 Ź1: podedovan vir z racunom ali vir neznanega izvora: samodejni prenos ob uporabi sele po izrecnem
            // dovoljenju uporabnika. Dovoljenje NE naredi vira za »dodanega tu« (deljenja v ozji krog ni) - za to ga uporabnik vpise sam.
            (if (IptvPravila.virZRacunom(dodan.tip, dodan.naslov) && !MedijskiViri.uporabaDovoljena(this, dodan.naslov))
                listOf(getString(R.string.os_iptv_potrdi_tu) to {
                    val app = applicationContext
                    try { delavec.execute { MedijskiViri.dovoliUporabo(app, dodan.naslov); glavna.post {
                        Toast.makeText(this, R.string.os_iptv_potrjen_tu, Toast.LENGTH_LONG).show()
                        if (!isFinishing && razdelek == VIRI) izberiNaMestu(VIRI) } } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { }
                }) else emptyList()) +
            // Grok 39, I5: po zavrnjenem prenosu (uporabnik je uredil racun pri ponudniku) en poskus takoj, ne sele po premoru.
            // Grok 68, Ą2: seznam z racunom, ki ga na tej napravi se nihce ni uporabil (mreza TV v zivo ga brez uporabnika ne prenese), ima izrecen klik.
            // Grok 60, Ů2: tudi seznam z racunom, ki ni dodan tu in se ne prenasa sam - izrecen klik je dovoljen (najvec 1-krat na minuto).
            // Grok 68, Ą5 (Grok Ź4): premor I5 po napaki velja tudi za ta klik, zato med premorom vira z racunom gumba ni (vrstica vira
            // pove, cez koliko casa je nov poskus mogoc).
            (if (dodan.jeSeznam && IptvPravila.smeZnovaPoskusiti(dodan.naslov, IptvSeznami.napaka(this, dodan.naslov), System.currentTimeMillis()) &&
                    (IptvSeznami.napaka(this, dodan.naslov) != null ||
                    (IptvPravila.virZRacunom(dodan.tip, dodan.naslov) && (!MedijskiViri.uporabaDovoljena(this, dodan.naslov) ||
                        IptvSeznami.podatki(this, dodan.naslov) == null))))
                listOf(getString(R.string.os_iptv_poskusi_znova) to {
                val app = applicationContext
                try { delavec.execute { IptvSeznami.pripravi(app, dodan.naslov, rocno = true); glavna.post { if (!isFinishing && razdelek == VIRI) izberiNaMestu(VIRI) } } }
                catch (_: java.util.concurrent.RejectedExecutionException) { }
            }) else emptyList()) +
            listOf(getString(R.string.os_media_izbrisi_vir) to { odstraniVir(dodan, v.ime) })
        pokaziBrisanje(AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(v.ime)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(getString(R.string.os_preklici), null)
        )
    }

    /** Grok 39, I5: »Ponudnik je seznam zavrnil … Znova poskusim cez 1 uro.« (cas v jeziku naprave). */
    private fun opisNapakeSeznama(n: IptvPravila.NapakaPrenosa): String {
        val zdaj = System.currentTimeMillis()
        val kdaj = android.text.format.DateUtils.getRelativeTimeSpanString(maxOf(n.naslednjiOb, zdaj + 60_000L), zdaj,
            android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
        return getString(if (n.zavrnjen) R.string.os_iptv_seznam_zavrnjen else R.string.os_iptv_seznam_ni_prenesen, kdaj)
    }

    private fun izmenicno(seznami: List<List<Jamendo.Skladba>>) =
        (0 until (seznami.maxOfOrNull { it.size } ?: 0)).flatMap { i -> seznami.mapNotNull { it.getOrNull(i) } }

    /** Kartice skladb vrste; zadrzan OK odpre meni (priljubljeno, shrani vrsto kot seznam). */
    /** Seznam predvajanja s posnetki s strani (YouTube ...) ali uvozenimi skladbami igra kot vrsta: ob koncu naslednji. */
    private fun jeVrstaPosnetkov(sk: Jamendo.Skladba, seznam: MedijskiViri.Seznam?) =
        UvozSeznama.jeIskana(sk) || (seznam != null && SpletniVir.jeEnota(sk))

    private fun predvajajVrstoPosnetkov(s: List<Jamendo.Skladba>, sk: Jamendo.Skladba) {
        val p = s.filter { it.mime != MedijskiViri.STRAN && (UvozSeznama.jeIskana(it) || SpletniVir.jeEnota(it) || it.zvok.isNotBlank()) }
        if (p.isEmpty()) return
        SpletniIgralec.zadnja = java.lang.ref.WeakReference(this)
        GlasbaStoritev.predvajajVrsto(this, p, p.indexOfFirst { it.id == sk.id }.coerceAtLeast(0))
        if (sk.video) startActivity(Intent(this, PredvajanjeActivity::class.java))
    }

    private fun skladbe(s: List<Jamendo.Skladba>, vrsta: String = "", seznam: MedijskiViri.Seznam? = null,
                        celota: List<Jamendo.Skladba> = s) = s.also { vzorciKnjiznice(it) }
        .filterNot { MrtviKanali.skrij(this, it) || znanoNiNaVoljo(it) || cakaNaKnjiznico(it) || neodklenjena(it) }.map { sk ->
        if (sk.mime == MedijskiViri.STRAN) Kartica(sk.naslov, sk.izvajalec, "", { odpriStran(sk.zvok, sk.naslov) }, { meni(sk, s, vrsta, seznam) }, ikona = R.drawable.os_ikona_splet)
        else {
            val oznaka = if (sk.video || SpletniVir.vrstaVsebine(sk) == SpletniVir.VIDEOSPOT) "▶ Video" else ""
            val ikona = when {
                sk.id.startsWith("tv:") -> R.drawable.os_ikona_tv
                sk.radio -> R.drawable.os_ikona_radio
                sk.video || SpletniVir.vrstaVsebine(sk) == SpletniVir.VIDEOSPOT -> R.drawable.os_ikona_video
                else -> R.drawable.os_ikona_glasba
            }
            Kartica(sk.naslov, sk.izvajalec, sk.slika, {
                zadnjiIzbran = sk.id
                if (jeVrstaPosnetkov(sk, seznam) || YoutubeVrstaPravila.kotVrsta(YoutubeZvok.jeYoutube(sk), sk.video)) predvajajVrstoPosnetkov(celota, sk)
                else if (SpletniVir.jeEnota(sk)) razresiSplet(sk)
                else celota.filterNot { it.mime == MedijskiViri.STRAN }.let { p -> predvajaj(p, p.indexOf(sk)) }
            }, { meni(sk, celota, vrsta, seznam) }, ikona = ikona, oznaka = oznaka, id = sk.id,
                tvId = sk.id.removePrefix("tv:").takeIf { sk.id.startsWith("tv:") }.orEmpty())
        }
    }

    /** Spletno stran odpre brskalnik Safeer (predvaja vse); nasa glasba se ustavi, zvok strani ob Domov igra naprej. */
    private fun odpriStran(url: String, ime: String) {
        GlasbaStoritev.premor()
        startActivity(Brskalnik.medijskaStran(this, url, ime))
    }

    /**
     * Isti naslov iz vec virov je ena kartica; Safeer sam izbere vir in ga uporabniku ne izpostavlja.
     * [razlicice] (mreza, ki se dopolnjuje na mestu): kartica -> vse razlicice njene vsebine, kot jih pozna zadnje
     * risanje. Razlicica, ki je ze na zaslonu, ostane kartica svoje skupine tudi, ko je pozneje potrjena boljsa (prej
     * je boljsa kartico prevzela: nov pogled z drugim naslovom na istem mestu, brez uporabnikovega dejanja - izmerjeno
     * 6. 10. 2026 na televizorju). Dotik razlicice prebere od tu, ker pogled kartice ostane tisti od prvega risanja.
     */
    private fun videi(v0: List<Jamendo.Skladba>, vrsta: String = "", seznam: MedijskiViri.Seznam? = null,
                      celota: List<Jamendo.Skladba> = v0,
                      razlicice: MutableMap<String, List<Jamendo.Skladba>>? = null,
                      zapomnjene: ZapomnjeneKartice? = null): List<Kartica> {
        // Vsebine, za katero vemo, da je noben dodatek ne predvaja, ne kazemo nikjer (police, iskanje, mreza).
        // Enako kanal ali postaja, ki pri viru ta cas ne dela ([MrtviKanali]).
        val v = v0.filterNot { znanoNiNaVoljo(it) || cakaNaKnjiznico(it) || MrtviKanali.skrij(this, it) || neodklenjena(it) }
        preveriPolico(v0)
        val napredekKrajevnih = if (v.none { it.id.startsWith("krajevno:") }) emptyMap()
            else MediaNapredek.seznam(this).filter { it.skladba.id.startsWith("krajevno:") && it.polozaj > 0 }.associateBy { it.skladba.id }
        val skupine = SpletniVir.zdruziEnako(v, razlicice?.keys?.toHashSet().orEmpty())
        razlicice?.clear()
        val podvojene = skupine.count { it.size > 1 }
        if (podvojene > 0) android.util.Log.i("SafeerOsMedia",
            "zdruzevanje: kandidati=${v.size}, kartice=${skupine.size}, podvojene_skupine=$podvojene, odstranjeni=${v.size - skupine.size}")
        // Naloga 61: odprta mreza si kartice zapomni - ob Nalozi vec in dopolnitvi se zgradijo samo nove (prej vseh 700-800,
        // 172-196 ms na televizorju). Kartica je odvisna samo od svoje skupine in [vrsta]; celoto (meni »Shrani kot seznam«)
        // prebere ob kliku, da zapomnjena kartica vidi tudi pozneje nalozene naslove.
        val zap = zapomnjene?.takeIf { seznam == null }
        if (zap != null) {
            if (zap.vrsta != vrsta) { zap.kartice.clear(); zap.vrsta = vrsta }
            zap.celota = celota
        }
        val celotaZdaj: () -> List<Jamendo.Skladba> = if (zap != null) ({ zap.celota }) else ({ celota })
        val zdaj = if (zap != null) HashMap<String, Pair<List<Jamendo.Skladba>, Kartica>>(skupine.size * 2) else null
        return skupine.map { urejene ->
            val sk = urejene.first()
            razlicice?.put(sk.id, urejene)
            // Krajevni posnetki in prenosi z racunalnika imajo v podnaslovu napredek (spreminja se): teh ne pomnimo.
            val stalna = zap != null && !sk.id.startsWith("krajevno:") && !sk.id.startsWith(PREDPONA_PC_PRENOSA)
            if (stalna) zap!!.kartice[sk.id]?.let { (prej, k) -> if (prej == urejene) { zdaj!![sk.id] = prej to k; zap.ponovljenih++; return@map k } }
            val tvId = sk.id.removePrefix("tv:").takeIf { sk.id.startsWith("tv:") }.orEmpty()
            // Kanal v zivo: uradni (tv:) ali iz dodatka Stremio tipa "tv" (stremio|tv|...): oznaka V ZIVO in ikona TV.
            val vZivo = tvId.isNotBlank() || Stremio.jeVZivo(sk) || IptvSeznami.jeKanal(sk)
            // Video z naprave: napredek ali trajanje (kot VLC), spletni: letnica.
            val podnaslov = if (sk.id.startsWith("krajevno:") || sk.id.startsWith(PREDPONA_PC_PRENOSA))
                napredekKrajevnih[sk.id]?.let { n -> "${cas(n.polozaj)} / ${cas(n.trajanje)}" } ?: sk.izvajalec
            // Posnetek s seznama predvajanja (uvozen z YouTuba): pod naslovom avtor, letnice nima.
            // Naloga 109: skladba seznama, ki ne deluje - razlog pred avtorjem (»⚠ zaseben posnetek · Avtor«).
            else if (seznam != null && NedelujoceSkladbe.razlog(this, sk.id) != null)
                listOf("⚠ " + NedelujoceSkladbe.opis(this, NedelujoceSkladbe.razlog(this, sk.id).orEmpty()), sk.izvajalec).filter { it.isNotBlank() }.joinToString(" · ")
            else sk.year.takeIf { it > 0 }?.toString() ?: if (seznam != null) sk.izvajalec else ""
            val tip = if (vZivo) getString(R.string.os_media_oznaka_v_zivo) else {
                // Posnetek z uvozenega seznama predvajanja je video, ne film (letnica v naslovu predavanja ga ne naredi filma).
                when (if (seznam != null && SpletniVir.jeEnota(sk)) null else SpletniVir.vrstaVsebine(sk)) {
                    SpletniVir.FILM -> getString(R.string.os_media_film)
                    SpletniVir.SERIJA -> getString(R.string.os_media_serija)
                    SpletniVir.VIDEOSPOT -> getString(R.string.os_media_videospot)
                    else -> getString(R.string.os_glasba_video)
                }
            }
            val signal = (sk.naslov + " " + sk.povezava).lowercase(Locale.ROOT)
            // Lastnik 28. 10. 2026: najboljsa znana kakovost iz tokov (»★ 4K«, »⚠ CAM«), sicer iz metapodatkov kataloga.
            val znana = if (Stremio.jeEnota(sk)) Stremio.razstavi(sk)?.third?.let { KakovostZnane.oznaka(this, it) } else null
            val kakovost = znana?.let { KakovostPravila.napisKartice(it) } ?: when {
                sk.quality >= 2160 || RX_KAKOVOST_4K.containsMatchIn(signal) -> KakovostPravila.Oznaka.UHD.napis
                sk.quality >= 1080 || RX_KAKOVOST_1080.containsMatchIn(signal) -> KakovostPravila.Oznaka.FULL_HD.napis
                sk.quality >= 720 || RX_KAKOVOST_720.containsMatchIn(signal) -> KakovostPravila.Oznaka.HD.napis
                else -> ""
            }
            val kartica = Kartica(sk.naslov, podnaslov, sk.slika, {
                zadnjiIzbran = sk.id
                // Uporabnik vidi eno kartico. V ozadju ostanejo vse razlicice, urejene od najboljse; v mrezi, ki se
                // dopolnjuje na mestu, so to razlicice zadnjega risanja (pogled kartice je se od prvega).
                val vse = razlicice?.get(sk.id) ?: urejene
                zadnjeRazlicice = vse.map { it.id }
                when {
                    jeVrstaPosnetkov(sk, seznam) || YoutubeVrstaPravila.kotVrsta(YoutubeZvok.jeYoutube(sk), sk.video) ->
                        predvajajVrstoPosnetkov(celotaZdaj(), sk)
                    SpletniVir.jeEnota(sk) -> razresiSplet(sk, vse.drop(1))
                    // Naloga 91a: ce dodatki ne dajo toka, igra prva ziva razlicica seznama IPTV (tiho).
                    Stremio.jeEnota(sk) && vse.size > 1 -> {
                        val rezerva = KanaliPravila.rezervaIptv(vse.map { it.naslov }, vse.map { MrtviKanali.stanje(this, it.id) },
                            vse.map { IptvSeznami.jeKanal(it) }, vse.map { KanaliPravila.znanNedelujocTok(it.zvok) })?.let { vse[it] }
                        dnevnikKlikaKanala(sk, vse, "dodatek (glava)", rezerva)
                        razresiStremio(sk, razlicice = vse.drop(1), rezerva = rezerva)
                    }
                    // Kanal seznama IPTV z vec razlicicami (SD, HD, iz dodatkov): predvajamo prvo zivo, mrtve izpustimo.
                    // Naloga 93: ce je prva razlicica iz dodatka, jo razresi dodatek z rezervo iz seznama ([KanaliPravila.klikKanala]).
                    IptvSeznami.jeKanal(sk) && vse.size > 1 -> {
                        val k = KanaliPravila.klikKanala(vse.map { it.naslov }, vse.map { MrtviKanali.stanje(this, it.id) },
                            vse.map { Stremio.jeEnota(it) }, vse.map { IptvSeznami.jeKanal(it) }, vse.map { KanaliPravila.znanNedelujocTok(it.zvok) })
                        val prva = k?.let { vse[it.prva] } ?: sk
                        val rezerva = k?.rezerva?.let { vse[it] }
                        if (k != null && k.dodatki.isNotEmpty()) {
                            dnevnikKlikaKanala(sk, vse, "dodatek (razlicica)", rezerva)
                            razresiStremio(prva, razlicice = k.dodatki.drop(1).map { vse[it] }, rezerva = rezerva)
                        } else if (k == null && KanaliPravila.znanNedelujocTok(sk.zvok)) {
                            // Naloga 100 (N6): ni zive razlicice, glava pa je znana prazna slika - ne predvajamo.
                            dnevnikKlikaKanala(sk, vse, "ni zive, glava znano ne deluje", null)
                        } else {
                            dnevnikKlikaKanala(sk, vse, if (k == null) "seznam (ni zive, glava)" else "seznam", null)
                            predvajaj(listOf(prva), 0)
                        }
                    }
                    else -> {
                        if (vZivo) dnevnikKlikaKanala(sk, vse, if (Stremio.jeEnota(sk)) "dodatek (edina)" else "neposredno", null)
                        predvajaj(listOf(sk), 0)
                    }
                }
            }, { meni(sk, celotaZdaj(), vrsta, seznam) }, oznaka = tip, kakovost = kakovost,
                ocena = sk.rating.takeIf { it > 0.0 }?.let { String.format(Locale.ROOT, "%.1f", it) }.orEmpty(),
                ikona = if (vZivo) R.drawable.os_ikona_tv else if (sk.radio) R.drawable.os_ikona_radio else R.drawable.os_ikona_video,
                tvId = tvId, id = sk.id,
                kakovostId = if (Stremio.jeEnota(sk) && !vZivo) Stremio.razstavi(sk)?.third.orEmpty() else "",
                // Televizor: ko izbira obstane na filmu iz dodatkov, tokove poiscemo vnaprej (predpomnilnik) - OK ga zazene takoj.
                priprava = if (jeTv() && Stremio.jeEnota(sk) && !Stremio.jeSerija(sk) && !vZivo)
                    ({ Stremio.razstavi(sk)?.let { (_, tip, id) -> zabeleziKakovost(id, tokoviVzporedno(tip, id)) }; Unit }) else null)
            if (stalna) { zdaj!![sk.id] = urejene to kartica; zap!!.novih++ }
            kartica
        }.also { if (zap != null) zap.kartice = zdaj!! }     // samo kartice tega izrisa: izpadli naslovi ne ostanejo v pomnilniku
    }

    /**
     * Naloga 61: kartice odprte mreze ([OdprtKatalog.zapomnjene]) po id-ju kartice s skupino, iz katere so zgrajene.
     * [novih]/[ponovljenih] stejeta za meritev (SafeerPerf); [celota] = vsi naslovi zadnjega izrisa (meni kartice).
     */
    private class ZapomnjeneKartice {
        var vrsta = ""
        var celota: List<Jamendo.Skladba> = emptyList()
        var kartice = HashMap<String, Pair<List<Jamendo.Skladba>, Kartica>>()
        var novih = 0
        var ponovljenih = 0
    }

    /**
     * Naloga 93: dnevnik klika kanala v zivo (SafeerOsMedia) - katera pot, koliko razlicic iz dodatkov in seznamov, stanja
     * ([MrtviKanali.stanje]) in ali je rezerva. Brez naslovov tokov in id-jev (povezava IPTV ima lahko geslo).
     */
    private fun dnevnikKlikaKanala(sk: Jamendo.Skladba, vse: List<Jamendo.Skladba>, pot: String, rezerva: Jamendo.Skladba?) {
        fun vrsta(x: Jamendo.Skladba) = if (Stremio.jeEnota(x)) "dodatek" else if (IptvSeznami.jeKanal(x)) "seznam" else "drugo"
        android.util.Log.i("SafeerOsMedia", "klik kanala \"${sk.naslov.take(40)}\": pot=$pot, glava=${vrsta(sk)}, razlicice=" +
            vse.joinToString(",") { vrsta(it) + ":" + when (MrtviKanali.stanje(this, it.id)) { true -> "ziv"; false -> "mrtev"; null -> "?" } } +
            ", rezerva=${rezerva?.let { vrsta(it) } ?: "ni"}")
    }

    /** Isti naslov iz dveh virov je med nedavnimi en vnos; postaje, kanali in datoteke naprave se locijo po oznaki ([OsPravila.kljucNedavnega]). */
    private fun kljucNedavnega(sk: Jamendo.Skladba) = OsPravila.kljucNedavnega(sk.id, SpletniVir.cistNaslov(sk.izvajalec + " " + sk.naslov),
        sk.radio || sk.id.startsWith("tv:") || Stremio.jeVZivo(sk), sk.id.startsWith("krajevno:") || sk.id.startsWith("link:"))

    private fun meniNedavno(sk: Jamendo.Skladba) {
        pokaziBrisanje(AlertDialog.Builder(this).setTitle(sk.naslov)
            .setItems(arrayOf(getString(R.string.os_media_odstrani_nedavno))) { _, _ ->
                // Vnos je lahko vec razlicic istega naslova (prikazana je ena): odstranimo vse, sicer se namesto odstranjene pokaze naslednja.
                val k = kljucNedavnega(sk)
                MedijskiViri.nedavno(this).filter { it.id != sk.id && kljucNedavnega(it) == k }.forEach { MedijskiViri.odstraniNedavno(this, it) }
                MedijskiViri.odstraniNedavno(this, sk)
                imaNedavno.pozabi()
                SEZNAMI.remove(DOMOV)
                osvezi(DOMOV)
            }.setNegativeButton(android.R.string.cancel, null))
    }

    private fun potrdiPocistiNedavno() {
        pokaziBrisanje(AlertDialog.Builder(this).setTitle(R.string.os_media_pocisti_nedavno)
            .setMessage(R.string.os_media_pocisti_nedavno_potrdi)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                MedijskiViri.pocistiNedavno(this)
                imaNedavno.pozabi()
                SEZNAMI.remove(DOMOV)
                osvezi(DOMOV)
            }.setNegativeButton(android.R.string.cancel, null))
    }

    private fun meniNadaljuj(sk: Jamendo.Skladba) {
        val moznosti = arrayOf(
            getString(R.string.os_media_odstrani_nadaljuj),
            getString(R.string.os_media_pocisti_nadaljuj)
        )
        pokaziBrisanje(AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(sk.naslov)
            .setItems(moznosti) { _, k ->
                when (k) {
                    0 -> {
                        MediaNapredek.odstrani(this, sk)
                        Toast.makeText(this, R.string.os_media_odstranjeno_nadaljuj, Toast.LENGTH_SHORT).show()
                        SEZNAMI.remove(VIDEO)
                        if (razdelek == VIDEO) osvezi(VIDEO)
                    }
                    1 -> potrdiPocistiNadaljuj()
                }
            }.setNegativeButton(android.R.string.cancel, null))
    }

    private fun potrdiPocistiNadaljuj() {
        pokaziBrisanje(AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(R.string.os_media_pocisti_nadaljuj)
            .setMessage(R.string.os_media_pocisti_nadaljuj_potrdi)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                MediaNapredek.pocisti(this)
                Toast.makeText(this, R.string.os_media_odstranjeno_nadaljuj, Toast.LENGTH_SHORT).show()
                SEZNAMI.remove(VIDEO)
                if (razdelek == VIDEO) osvezi(VIDEO)
            }.setNegativeButton(android.R.string.cancel, null))
    }

    // ------------------------------------------------------------------ priljubljene

    /**
     * Naloga 109: »Poisci drug posnetek« - isto iskanje kot uvoz ([UvozSeznama.najdi]) brez posnetka, ki ne deluje; najden
     * zamenja skladbo na vseh seznamih ([MedijskiViri.zamenjaj]), zapis o nedelovanju izgine.
     */
    private fun poisciDrugPosnetek(sk: Jamendo.Skladba) {
        val izloci = setOfNotNull(NedelujocePravila.idYoutube(sk.povezava))
        Toast.makeText(this, getString(R.string.os_media_pripravljam, sk.naslov), Toast.LENGTH_SHORT).show()
        delavec.execute {
            val nov = try { UvozSeznama.najdi(UvozSeznama.iskana(sk.naslov, sk.izvajalec, 0, sk.slika), izloci) } catch (_: Exception) { null }
            if (nov != null && nov.id != sk.id) { MedijskiViri.zamenjaj(this, mapOf(sk.id to nov)); NedelujoceSkladbe.pozabi(this, sk.id) }
            android.util.Log.i("SafeerOsMedia", "drug posnetek \"${sk.naslov.take(40)}\": ${if (nov != null && nov.id != sk.id) "najden" else "ni"}")
            glavna.post {
                if (isFinishing || isDestroyed) return@post
                val najden = nov != null && nov.id != sk.id
                Toast.makeText(this, if (najden) R.string.os_nedel_nov_posnetek else R.string.os_nedel_ni_drugega, Toast.LENGTH_SHORT).show()
                if (najden) osveziPriljubljene()
            }
        }
    }

    /**
     * Premik priljubljenega kanala med priljubljenimi kanali (levo, desno, na zacetek); vrstni red je shranjen v priljubljenih
     * ([MedijskiViri.urediPriljubljene]), mreza ga pokaze takoj. Ni kanal ali je edini: prazno.
     */
    private fun moznostiPremikaKanala(sk: Jamendo.Skladba): List<Pair<String, () -> Unit>> {
        val vse = MedijskiViri.priljubljene(this)
        // Pregled N6: premikamo med priljubljenimi kanali, ki so v mrezi na zaslonu - nevidni (nedelujoci, izven mreze)
        // ostanejo na svojih mestih, »Levo« pri prvem vidnem pa ni ponujen.
        val vMrezi = if (razdelek == TV_V_ZIVO) mrezaKanalov?.narisani?.toHashSet() else null
        val kanali = vse.filter { PriljubljeniKanaliPravila.jeKanal(it.id, Stremio.jeVZivo(it)) && (vMrezi == null || it.id in vMrezi) }
            .map { it.id }.toHashSet()
        val jeK: (String) -> Boolean = { it in kanali }
        val ids = vse.map { it.id }
        val (mesto, stevilo) = PriljubljeniKanaliPravila.mesto(ids, jeK, sk.id)
        val (levo, desno, naZacetek) = PriljubljeniKanaliPravila.moznostiPremika(mesto, stevilo)
        fun premik(zamik: Int, zacetek: Boolean): () -> Unit = {
            val nov = PriljubljeniKanaliPravila.premakni(MedijskiViri.priljubljene(this).map { it.id }, jeK, sk.id, zamik, zacetek)
            if (nov != null && MedijskiViri.urediPriljubljene(this, nov)) {
                android.util.Log.i("SafeerOsMedia", "priljubljeni kanal premaknjen: \"${sk.naslov.take(40)}\" ${if (zacetek) "na zacetek" else if (zamik < 0) "levo" else "desno"}")
                // Izbira gre s premaknjenim kanalom (izmerjeno na TV: brez tega je ostala na starem mestu, na sosedu). Priljubljeni
                // so v mrezi spredaj po svojem redu: novo mesto je mesto med priljubljenimi kanali, ki so v mrezi. Risanje vrne
                // izbiro na kartico vrnitve ([mestoVrnitve]), tudi ce se mreza rise po korakih.
                val novoMesto = nov.filter(jeK).indexOf(sk.id)
                if (novoMesto >= 0) mestoVrnitve(drsnik.scrollY, "k:#$novoMesto", OsPravila.Podstrani.NI_ODMIKA)
                osveziPriljubljene()
            }
        }
        val izhod = ArrayList<Pair<String, () -> Unit>>()
        if (levo) izhod += getString(R.string.os_spletne_levo) to premik(-1, false)
        if (desno) izhod += getString(R.string.os_spletne_desno) to premik(1, false)
        if (naZacetek) izhod += getString(R.string.os_premakni_na_zacetek) to premik(0, true)
        return izhod
    }

    private fun meni(sk: Jamendo.Skladba, vrsta: List<Jamendo.Skladba>, ime: String, seznam: MedijskiViri.Seznam?) {
        val dejanja = mutableListOf<Pair<String, () -> Unit>>()
        if (MediaNapredek.polozaj(this, sk) > 0) {
            dejanja += getString(R.string.os_media_odstrani_nadaljuj) to {
                MediaNapredek.odstrani(this, sk)
                Toast.makeText(this, R.string.os_media_odstranjeno_nadaljuj, Toast.LENGTH_SHORT).show()
                SEZNAMI.remove(VIDEO)
                if (razdelek == VIDEO) osvezi(VIDEO)
            }
        }
        if (MedijskiViri.shranljiva(sk)) {
            val je = MedijskiViri.jePriljubljena(this, sk)
            dejanja += getString(if (je) R.string.os_mediji_odstrani_prilj else R.string.os_mediji_dodaj_prilj) to {
                val zdaj = MedijskiViri.preklopiPriljubljeno(this, sk)
                // Pregled N9: »Dodano« samo, ce je res shranjeno (meja priljubljenih).
                Toast.makeText(this, when { zdaj -> R.string.os_mediji_dodano_prilj; je -> R.string.os_mediji_odstranjeno_prilj; else -> R.string.prilj_polno },
                    Toast.LENGTH_SHORT).show()
                // Zadnji priljubljeni kanal odstranjen: izbira »Priljubljeni« nima vec cesa pokazati.
                if (!zdaj && tvSamoPriljubljeni && priljubljeniKanali().isEmpty()) tvSamoPriljubljeni = false
                osveziPriljubljene()
            }
            // 25. 10. 2026 (lastnik): priljubljeni kanal se z daljincem premakne levo, desno ali na zacetek mreze TV v zivo.
            if (je) dejanja += moznostiPremikaKanala(sk)
        }
        // Svoj seznam predvajanja: skladbo dodas na obstojecega ali novega (ne samo "shrani vso vrsto").
        // Kanal v zivo ni skladba za seznam predvajanja: zanj so priljubljeni (zgoraj).
        if (SeznamOkno.mozno(sk) && !sk.id.startsWith("tv:")) dejanja += getString(R.string.os_seznam_dodaj) to { dodajNaSeznam(sk) }
        // Naloga 109: skladba seznama, ki ne deluje - drug posnetek iste skladbe ali preverba znova.
        val razlogSkladbe = if (seznam != null) NedelujoceSkladbe.razlog(this, sk.id) else null
        if (seznam != null && razlogSkladbe != null) {
            if (NedelujocePravila.idYoutube(sk.povezava) != null) dejanja += getString(R.string.os_nedel_poisci_drug) to { poisciDrugPosnetek(sk) }
            dejanja += getString(R.string.os_nedel_preveri_znova) to { NedelujoceSkladbe.pozabi(this, sk.id); predvajaj(listOf(sk), 0) }
        }
        if (seznam != null) dejanja += getString(R.string.os_seznam_odstrani_skladbo) to {
            MedijskiViri.odstraniSSeznama(this, seznam.ime, sk); SEZNAMI.remove(DOMOV); osveziPriljubljene()
        }
        if (seznam != null) dejanja += getString(R.string.os_mediji_odstrani_seznam) to {
            // S potrditvijo: seznam izgine tudi na drugih napravah v Linku (SeznamiSink), zato en dotik ni dovolj.
            pokaziBrisanje(AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(seznam.ime)
                .setMessage(vprasanjeOdstraniSeznam())
                .setPositiveButton(R.string.os_mediji_odstrani_seznam) { _, _ -> MedijskiViri.odstraniSeznam(this, seznam.ime); osveziPriljubljene() }
                .setNegativeButton(android.R.string.cancel, null))
            Unit
        } else if (vrsta.count { MedijskiViri.shranljiva(it) && !it.id.startsWith("tv:") } > 1) dejanja += getString(R.string.os_mediji_shrani_seznam) to {
            val sz = MedijskiViri.shraniSeznam(this, ime.ifBlank { sk.izvajalec.ifBlank { getString(R.string.os_mediji_moja_vrsta) } }, vrsta)
            if (sz != null) Toast.makeText(this, getString(R.string.os_mediji_seznam_shranjen, sz.ime), Toast.LENGTH_SHORT).show()
            osveziPriljubljene()
        }
        // Naloga 90d: kanal v zivo, ki namesto programa kaze obvestilo (strezniki odgovarjajo): uporabnik ga skrije sam, trajno.
        // Skrijejo se vse razlicice kartice, kot jih pozna zadnje risanje mreze; kartica izgine takoj, brez ponovnega nalaganja.
        if (Stremio.jeVZivo(sk) || IptvSeznami.jeKanal(sk)) dejanja += getString(R.string.os_tv_kanal_ne_deluje_skrij) to {
            val razlicice = mrezaKanalov?.razlicice?.get(sk.id).orEmpty().map { it.id }
            zadnjiIzbran = sk.id
            MrtviKanali.skrij(this, KanaliPravila.kljuciKartice(sk.id, razlicice))
        }
        // Film ali serija iz dodatkov: predvajanje samo izbere najboljsi tok; tu ga uporabnik lahko izbere sam.
        if (Stremio.jeEnota(sk)) dejanja += getString(R.string.os_media_izberi_tok) to { razresiStremio(sk, rocno = true) }
        // Naloga 124: dejanja dodatka (protokol v1) in »O vsebini« (dopolnitev v ozadju).
        for (d in Stremio.dejanjaZa(sk)) dejanja += d.naslov.za(jezikUporabnika()) to { izvediDejanjeDodatka(sk, d) }
        if (Stremio.ponujaDopolnitev(sk)) dejanja += getString(R.string.os_dodatek_o_vsebini) to { pokaziDopolnitev(sk) }
        if (dejanja.isEmpty()) return
        pokaziBrisanje(AlertDialog.Builder(this).setTitle(sk.naslov)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, k -> dejanja[k].second() }
            .setNegativeButton(android.R.string.cancel, null))
    }

    // ------------------------------------------------------------------ naloga 124: razsiritve dodatkov (protokol v1)

    private fun jezikUporabnika(): String = resources.configuration.locales[0].language

    /** Dejanje dodatka iz menija vsebine: po potrebi potrditev (privzeto Preklici), klic v ozadju, sporocilo dodatka. */
    private fun izvediDejanjeDodatka(sk: Jamendo.Skladba, d: DodatkiProtokol.Dejanje) {
        val naslov = d.naslov.za(jezikUporabnika())
        val izvedi = {
            val app = this
            delavec.execute {
                val odgovor = try { Stremio.izvediDejanje(sk, d) } catch (_: Exception) { null }
                glavna.post {
                    if (isFinishing || isDestroyed) return@post
                    val besedilo = odgovor?.sporocilo?.za(jezikUporabnika())?.takeIf { it.isNotBlank() }
                        ?: getString(if (odgovor == null) R.string.os_dodatek_ne_odgovori else if (odgovor.ok) R.string.os_dodatek_dejanje_ok else R.string.os_dodatek_dejanje_ni)
                    Toast.makeText(app, besedilo, Toast.LENGTH_LONG).show()
                    if (odgovor?.osvezi == true) osveziNaMestu()
                }
            }
            Unit
        }
        if (!d.potrdi) { izvedi(); return }
        pokaziBrisanje(AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(naslov).setMessage(sk.naslov)
            .setPositiveButton(naslov) { _, _ -> izvedi() }
            .setNegativeButton(R.string.os_preklici, null))
    }

    /**
     * »O vsebini«: okno se odpre takoj z znanim (naslov, leto), dodatek ga dopolni v ozadju (enrich) - ocena, jezik,
     * igralci, opis. Dodatek ne more zamenjati naslova, id-ja ali tokov.
     */
    private fun pokaziDopolnitev(sk: Jamendo.Skladba) {
        val besedilo = TextView(this).apply {
            setTextColor(osBarva(R.color.os_besedilo)); setPadding(48, 24, 48, 24)
            text = listOf(sk.izvajalec, getString(R.string.os_dodatek_nalagam)).filter { it.isNotBlank() }.joinToString("\n\n")
        }
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(sk.naslov)
            .setView(android.widget.ScrollView(this).apply { addView(besedilo) })
            .setNegativeButton(R.string.os_preklici, null).create()
        Kontroler.pokazi(okno); okno.show()
        delavec.execute {
            val m = try { Stremio.dopolni(sk) } catch (_: Exception) { null }
            glavna.post {
                if (isFinishing || !okno.isShowing) return@post
                val vrstice = ArrayList<String>()
                if (sk.izvajalec.isNotBlank()) vrstice += sk.izvajalec
                m?.optString("imdbRating")?.takeIf { it.isNotBlank() }?.let { vrstice += getString(R.string.os_dodatek_ocena, DodatkiProtokol.cisto(it, 10)) }
                m?.optString("language")?.takeIf { it.isNotBlank() }?.let { vrstice += getString(R.string.os_dodatek_jezik, DodatkiProtokol.cisto(it, 40)) }
                m?.optJSONArray("cast")?.let { a -> (0 until minOf(a.length(), 8)).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }
                    ?.takeIf { it.isNotEmpty() }?.let { vrstice += getString(R.string.os_dodatek_igralci, DodatkiProtokol.cisto(it.joinToString(", "), 300)) }
                m?.optString("description")?.takeIf { it.isNotBlank() }?.let { vrstice += DodatkiProtokol.cisto(it, 1500) }
                if (m == null || vrstice.size <= (if (sk.izvajalec.isNotBlank()) 1 else 0)) vrstice += getString(R.string.os_dodatek_ni_podatkov)
                besedilo.text = vrstice.joinToString("\n\n")
            }
        }
    }

    /** Naloga 126: manifesti dodatkov se nalagajo za Moje vire (enkrat hkrati). */
    @Volatile private var nalagamManifeste = false

    /**
     * Naloga 126: manifesti, ki jih se ni v pomnilniku (zagon, »Znova nalozi dodatke«), se nalozijo v ozadju in Moji viri se
     * narisejo znova - prej so kartice map izginile, dokler manifesta ni nalozilo nekaj drugega (katalogi).
     */
    private fun zagotoviManifeste(naslovi: List<String>) {
        val zdaj = android.os.SystemClock.uptimeMillis()
        // Dodatek, ki ne odgovori, ne sme sprozati nalaganja ob vsakem risanju: najvec en poskus na minuto.
        if (nalagamManifeste || zdaj - zadnjiPoskusManifestov < 60_000L || Stremio.manifestiVPomnilniku(naslovi)) return
        nalagamManifeste = true
        zadnjiPoskusManifestov = zdaj
        val prej = naslovi.count { Stremio.razsiritevZdaj(it) != null }
        delavec.execute {
            try { Stremio.naloziManifeste(naslovi) } finally {
                nalagamManifeste = false
                val potem = naslovi.count { Stremio.razsiritevZdaj(it) != null }
                // Osvezimo samo, ce je prisla nova razsiritev (kartica mape ali nastavitev).
                if (potem != prej) glavna.post { if (!isFinishing && razdelek == VIRI) osveziNaMestu() }
            }
        }
    }

    private var zadnjiPoskusManifestov = 0L

    /** Kartice map dodatkov za Moje vire (iz pomnilnika; manjkajoce manifeste nalozi [zagotoviManifeste]). */
    private fun karticeMapDodatkov(): List<Kartica> = stremioNaslovi().also { zagotoviManifeste(it) }.flatMap { n ->
        val r = Stremio.razsiritevZdaj(n) ?: return@flatMap emptyList()
        // Naloga 127: mapo, ki je ta naprava ne more odpreti (http brez razvojnega nacina), ne kazemo.
        if (!Stremio.razsiritveUporabne(n)) return@flatMap emptyList()
        val ime = Stremio.imeIzPredpomnilnika(n) ?: ""
        r.mape.map { mapa ->
            val imeMape = mapa.ime.za(jezikUporabnika())
            Kartica(imeMape, ime, "", { odpriMapoDodatka(n, mapa.id, "", imeMape) }, ikona = R.drawable.os_ikona_mapa)
        }
    }

    /**
     * Mapa dodatka: seznam (z daljincem in na dotik) - mapa odpre globlje, vsebina se predvaja kot vsaka kartica dodatka,
     * »Vec« nalozi naslednjo stran. Vse z delovne niti; dodatek, ki ne odgovori, pove sporocilo.
     */
    private fun odpriMapoDodatka(naslov: String, mapa: String, pot: String, ime: String) {
        Toast.makeText(this, getString(R.string.os_media_pripravljam, ime), Toast.LENGTH_SHORT).show()
        delavec.execute {
            val izid = try { Stremio.mapa(naslov, mapa, pot) } catch (_: Exception) { null }
            glavna.post {
                if (isFinishing || isDestroyed) return@post
                if (izid == null) { Toast.makeText(this, R.string.os_dodatek_ne_odgovori, Toast.LENGTH_LONG).show(); return@post }
                val (vsebina, kartice) = izid
                if (vsebina.elementi.isEmpty()) { Toast.makeText(this, R.string.os_dodatek_mapa_prazna, Toast.LENGTH_SHORT).show(); return@post }
                val imena = vsebina.elementi.map { (if (it.vrsta == "folder") "📁 " else "") + it.ime } +
                    (if (vsebina.naprej.isNotBlank()) listOf(getString(R.string.os_dodatek_vec)) else emptyList())
                val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(ime)
                    .setItems(imena.toTypedArray()) { _, i ->
                        val e = vsebina.elementi.getOrNull(i)
                        when {
                            e == null -> odpriMapoDodatka(naslov, mapa, vsebina.naprej, ime)
                            e.vrsta == "folder" -> odpriMapoDodatka(naslov, mapa, e.pot, e.ime)
                            else -> kartice.getOrNull(i)?.let { sk -> zadnjiIzbran = sk.id; predvajaj(listOf(sk), 0) }
                        }
                    }
                    .setNegativeButton(R.string.os_preklici, null).create()
                Kontroler.pokazi(okno); okno.show()
            }
        }
    }

    /**
     * Nastavitve dodatka (protokol v1): polja, ki jih je dodatek opisal, narisana v nasem slogu. Skrivnost se ne pokaze
     * nazaj - prazno polje pomeni »pusti, kot je«; »Ponastavi« pobrise vse vrednosti dodatka (tudi skrivnosti).
     */
    private fun nastavitveDodatka(naslov: String) {
        val r = Stremio.razsiritevZdaj(naslov) ?: return
        val osnova = Stremio.osnova(naslov)
        val jezik = jezikUporabnika()
        val zdaj = DodatkiNastavitve.vrednosti(this, osnova, r.nastavitve)
        val rob = (20 * resources.displayMetrics.density).toInt()
        val stolpec = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(rob, rob / 2, rob, 0) }
        val bralci = ArrayList<Pair<DodatkiProtokol.Nastavitev, () -> String>>()
        for (n in r.nastavitve) {
            stolpec.addView(TextView(this).apply {
                text = n.naslov.za(jezik) + if (n.obvezna) " *" else ""
                setTextColor(osBarva(R.color.os_besedilo)); setPadding(0, rob / 2, 0, 0)
            })
            when (n.vrsta) {
                "toggle" -> {
                    val stikalo = android.widget.Switch(this).apply { isChecked = (zdaj[n.kljuc] ?: n.privzeto) == "true" }
                    stolpec.addView(stikalo); bralci += n to { stikalo.isChecked.toString() }
                }
                "choice" -> {
                    val izbira = android.widget.Spinner(this)
                    izbira.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, n.moznosti)
                    izbira.setSelection(n.moznosti.indexOf(zdaj[n.kljuc] ?: n.privzeto).coerceAtLeast(0))
                    stolpec.addView(izbira); bralci += n to { n.moznosti.getOrElse(izbira.selectedItemPosition) { n.privzeto } }
                }
                else -> {
                    val polje = EditText(this).apply {
                        isSingleLine = true
                        if (n.skrivnost) {
                            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                            if (DodatkiNastavitve.imaSkrivnost(this@GlasbaActivity, osnova, n)) hint = getString(R.string.os_dodatek_shranjeno_prazno)
                        } else setText(zdaj[n.kljuc] ?: n.privzeto)
                    }
                    stolpec.addView(polje); bralci += n to { polje.text.toString().trim() }
                }
            }
        }
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_dodatek_nastavitve) + " · " + (Stremio.imeIzPredpomnilnika(naslov) ?: ""))
            .setView(android.widget.ScrollView(this).apply { addView(stolpec) })
            .setPositiveButton(R.string.os_dodatek_shrani) { _, _ ->
                val vsa = bralci.all { (n, beri) -> DodatkiNastavitve.shrani(this, osnova, n, beri()) }
                Stremio.pozabiOdgovore(naslov)
                Toast.makeText(this, if (vsa) R.string.os_dodatek_nastavitve_shranjene else R.string.os_dodatek_nastavitve_napaka, Toast.LENGTH_SHORT).show()
                osveziNaMestu()
            }
            .setNeutralButton(R.string.os_dodatek_ponastavi) { _, _ ->
                DodatkiNastavitve.pozabiDodatek(this, osnova, r.nastavitve)
                Stremio.pozabiOdgovore(naslov)
                Toast.makeText(this, R.string.os_dodatek_ponastavljeno, Toast.LENGTH_SHORT).show()
                osveziNaMestu()
            }
            .setNegativeButton(R.string.os_preklici, null).create()
        Kontroler.pokazi(okno); okno.show()
    }

    // ------------------------------------------------------------------ naloga 125: razvojni nacin dodatkov

    /** Vklop (s pojasnilom in potrditvijo) ali izklop razvojnega nacina; privzeti gumb je Preklici. */
    private fun preklopiRazvoj() {
        if (DodatkiRazvoj.vklopljen) {
            DodatkiRazvoj.nastavi(this, false)
            Toast.makeText(this, R.string.os_razvoj_izklopljen, Toast.LENGTH_SHORT).show(); osveziNaMestu(); return
        }
        pokaziBrisanje(AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.os_razvoj_vklopi).setMessage(R.string.os_razvoj_vklopi_vprasanje)
            .setPositiveButton(R.string.os_razvoj_vklopi) { _, _ ->
                DodatkiRazvoj.nastavi(this, true)
                Toast.makeText(this, R.string.os_razvoj_vklopljen, Toast.LENGTH_SHORT).show(); osveziNaMestu()
            }
            .setNegativeButton(R.string.os_preklici, null))
    }

    /** Orodja razvojnega nacina: znova nalozi dodatke, dnevnik odgovorov, izklop. */
    private fun meniRazvoja() {
        val dejanja = listOf(
            getString(R.string.os_razvoj_znova) to {
                val naslovi = stremioNaslovi()
                delavec.execute {
                    Stremio.znovaNalozi(naslovi)
                    // Naloga 126: manifeste takoj znova, da kartice map in nastavitev ne izginejo.
                    Stremio.naloziManifeste(naslovi)
                    glavna.post { if (!isFinishing) { Toast.makeText(this, R.string.os_razvoj_znova_ok, Toast.LENGTH_SHORT).show(); osveziNaMestu() } }
                }
                Unit
            },
            getString(R.string.os_razvoj_dnevnik) to { pokaziDnevnikDodatkov() },
            getString(R.string.os_razvoj_izklopi) to { preklopiRazvoj() })
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(R.string.os_razvoj_naslov)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(R.string.os_preklici, null).create()
        Kontroler.pokazi(okno); okno.show()
    }

    /** Dnevnik odgovorov dodatkov (najnovejsi prvi): cas, gostitelj, vrsta zahteve, koda, velikost, trajanje. */
    private fun pokaziDnevnikDodatkov() {
        val ura = java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT)
        val vrstice = DodatkiRazvoj.zapisi().map { z ->
            "${ura.format(java.util.Date(z.cas))}  ${z.gostitelj}  ${z.vrsta}  ${z.koda}" +
                (if (z.bajtov >= 0) "  ${z.bajtov} B" else "") + (if (z.ms > 0) "  ${z.ms} ms" else "")
        }
        val besedilo = TextView(this).apply {
            setTextColor(osBarva(R.color.os_besedilo)); setPadding(48, 24, 48, 24); typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f
            text = if (vrstice.isEmpty()) getString(R.string.os_razvoj_dnevnik_prazen) else vrstice.joinToString("\n")
        }
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(R.string.os_razvoj_dnevnik)
            .setView(ScrollView(this).apply { addView(besedilo) }).setNegativeButton(R.string.os_preklici, null).create()
        Kontroler.pokazi(okno); okno.show()
    }

    /** Seznam sprememb dodatka enkrat po njegovi posodobitvi (prvi tak dodatek ob vrnitvi na zaslon). */
    private fun pokaziSpremembeDodatkov() {
        for (n in stremioNaslovi()) {
            val b = Stremio.spremembeZaPrikaz(this, n) ?: continue
            val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.os_dodatek_novo, Stremio.imeIzPredpomnilnika(n) ?: ""))
                .setMessage(b.za(jezikUporabnika())).setPositiveButton(android.R.string.ok, null).create()
            Kontroler.pokazi(okno); okno.show()
            return
        }
    }

    /** Na TV je varen privzeti fokus vedno Preklici, nikoli dejanje, ki brise uporabnikove podatke. */
    private fun pokaziBrisanje(graditelj: AlertDialog.Builder): AlertDialog {
        val okno = graditelj.create()
        okno.setOnShowListener { okno.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus() }
        okno.show()
        Kontroler.pokazi(okno)
        return okno
    }

    /** Priljubljene so na vrhu plosce in razdelkov Glasba, Radio in Video - tam jih narisemo znova. */
    private fun osveziPriljubljene() { if (podstrani.odprta) osveziNaMestu() else if (razdelek != VIRI && razdelek != ISKANJE) izberiNaMestu(razdelek) }

    // ------------------------------------------------------------------ iskanje

    /** Zadnji zadetki in beseda: ob vrnitvi na Iskanje so se tam. */
    private var zadetki: List<Vrsta>? = null
    private var zadnjaBeseda = ""
    private var opisZadetkov = ""
    /** Zadetki, ki so zdaj na zaslonu (police in kartice po vrsti): enakih ne risemo znova. */
    private var podpisZadetkov = ""

    private fun novIskalnik() = EditText(this).apply {
        id = View.generateViewId()
        hint = zasebnoIskanje?.let { getString(R.string.os_mediji_isci_v_dodatku, Stremio.imeIzPredpomnilnika(it) ?: (android.net.Uri.parse(Stremio.osnova(it)).host ?: "")) }
            ?: getString(R.string.os_glasba_isci_namig)
        setText(zadnjaBeseda)
        setTextColor(osBarva(R.color.os_besedilo)); setHintTextColor(osBarva(R.color.os_umirjeno))
        setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH; inputType = InputType.TYPE_CLASS_TEXT
        setBackgroundResource(R.drawable.os_iskanje)
        setPadding(dp(20), dp(10), dp(20), dp(10))
        tag = "k:iskalno_polje"
        isFocusable = true
        isFocusableInTouchMode = true
        nextFocusLeftId = meniMediji.id
        // Na TV-ju fokus in odpiranje tipkovnice nista ista stvar: D-pad lahko mirno
        // prečka polje, OK/Enter pa odpre tipkovnico. DOWN jo zapre in gre na zadetke.
        showSoftInputOnFocus = false
        setSelection(text.length)
        setOnClickListener { prikaziTipkovnico(this) }
        setOnEditorActionListener { _, a, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (a == EditorInfo.IME_ACTION_SEARCH || a == EditorInfo.IME_ACTION_DONE || enter) {
                val q = text.toString().trim()
                if (q.length >= 2 && !prikaziNaDrugi(q)) isci(q)
                true
            } else false
        }
        setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    prikaziTipkovnico(this); true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    skrijTipkovnico(this)
                    fokusNaPrvo()
                    true
                }
                else -> false
            }
        }
    }

    /** Pred iskanjem: glasovno iskanje in nedavna iskanja. */
    private fun predIskanjem() = listOf(
        Vrsta(getString(R.string.os_mediji_nedavna), listOf(
            Kartica(getString(R.string.os_mediji_glasovno), getString(R.string.os_mediji_glasovno_opis), "", { glasovno() }, ikona = R.drawable.os_ikona_mikrofon)) +
            MedijskiViri.iskanja(this).map { b -> Kartica(b, getString(R.string.os_glasba_iskanje), "", { odpriIskanje(b) }, ikona = R.drawable.os_ikona_isci) }))

    /** Odpre razdelek Iskanje; z besedo takoj isce, sicer pripravi polje za tipkanje. */
    /** Naslov zasebnega dodatka, v katerem isce to iskanje (odprto iz njegove mreze); null = obicajno iskanje. */
    private var zasebnoIskanje: String? = null
    /** Naloga 81: razdelek, iz katerega je bilo iskanje odprto (iz Glasbe: posnetki dodatkov so glasbeni videi). */
    private var iskanjeIz = DOMOV

    private fun odpriIskanje(beseda: String) {
        // Iskanje, odprto iz mreze zasebnega dodatka, isce samo v njem in ne pusti sledi (ZasebniDodatki).
        odprtKatalog?.dodatek?.takeIf { it.isNotBlank() }?.let { zasebnoIskanje = it; zadnjaBeseda = "" }
        if (razdelek != ISKANJE) { iskanjeIz = razdelek; izberi(ISKANJE) }
        val polje = iskalnik ?: return
        if (beseda.isNotBlank()) { polje.setText(beseda); isci(beseda); return }
        polje.requestFocus()
        polje.post { prikaziTipkovnico(polje) }
    }

    private fun prikaziTipkovnico(polje: EditText) {
        polje.showSoftInputOnFocus = true
        polje.requestFocus()
        polje.setSelection(polje.text.length)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(polje, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun skrijTipkovnico(polje: EditText) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(polje.windowToken, 0)
        polje.showSoftInputOnFocus = false
    }

    private var glasZacetek = 0L

    /** »Jungle Book na TV«: prikaz na drugi napravi v Linku (PrikaziNa); sicer obicajno iskanje. */
    private fun prikaziNaDrugi(q: String): Boolean {
        val cilj = PrikaziNa.razcleni(q)?.takeIf { it.naslov.isNotBlank() } ?: return false
        PrikaziNa.izberiInPoslji(this, cilj)
        return true
    }

    private fun iskanjeAliPrikaz(q: String) {
        if (!prikaziNaDrugi(q)) odpriIskanje(q)
    }

    private fun glasovno() {
        val namen = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.os_mediji_glasovno_opis))
        glasZacetek = System.currentTimeMillis()
        try { @Suppress("DEPRECATION") startActivityForResult(namen, GLAS) }
        catch (_: Exception) { Toast.makeText(this, R.string.os_mediji_ni_glasovnega, Toast.LENGTH_LONG).show() }
    }

    /** Predvajalnik s plosce: izbira datoteke (sistemski izbirnik, brez dovoljenja za shrambo) ali spletni naslov. */
    private fun odpriPredvajalnik() {
        val moznosti = arrayOf(getString(R.string.os_mediji_predvajalnik_datoteka), getString(R.string.os_mediji_predvajalnik_naslov))
        android.app.AlertDialog.Builder(this).setTitle(R.string.os_mediji_predvajalnik).setItems(moznosti) { _, i ->
            if (i == 0) {
                val n = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*", "application/x-mpegURL", "application/vnd.apple.mpegurl", "application/dash+xml"))
                try { @Suppress("DEPRECATION") startActivityForResult(n, PREDVAJALNIK_DATOTEKA) }
                catch (_: Exception) { Toast.makeText(this, R.string.os_mediji_predvajalnik_ni_izbirnika, Toast.LENGTH_LONG).show() }
            } else {
                val polje = android.widget.EditText(this).apply { hint = "https://…"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI; isSingleLine = true }
                android.app.AlertDialog.Builder(this).setTitle(R.string.os_mediji_predvajalnik_naslov).setView(polje)
                    .setPositiveButton(R.string.os_mediji_predvajaj) { _, _ ->
                        val u = polje.text?.toString().orEmpty().trim()
                        if (u.startsWith("http://") || u.startsWith("https://")) predvajajNaslov(android.net.Uri.parse(u), "")
                        else Toast.makeText(this, R.string.os_mediji_predvajalnik_le_http, Toast.LENGTH_LONG).show()
                    }.setNegativeButton(R.string.os_preklici, null).show()
            }
        }.show()
    }

    /** Skupna pot za "Odpri z", izbirnik datotek in vnos naslova. */
    private fun predvajajNaslov(uri: android.net.Uri, mimeNamig: String) {
        val mime = mimeNamig.ifBlank { try { contentResolver.getType(uri) } catch (_: Throwable) { null } ?: "" }
        val zvok = mime.startsWith("audio/")
        val skladba = Jamendo.Skladba(uri.toString(), imeDatoteke(uri), "", "", uri.toString(), "", video = !zvok, mime = mime)
        GlasbaStoritev.predvajaj(this, listOf(skladba), 0)
        startActivity(Intent(this, PredvajanjeActivity::class.java))
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PREDVAJALNIK_DATOTEKA) {
            val uri = data?.data ?: return
            if (resultCode != RESULT_OK) return
            // Trajno dovoljenje za to datoteko: "Nadaljuj gledanje" in "Nedavno" jo lahko odpreta znova.
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) { }
            predvajajNaslov(uri, data.type.orEmpty()); return
        }
        if (requestCode != GLAS) return
        // Takojsnja zavrnitev pomeni, da glasovni vnos na tej napravi ne dela (npr. daljinec brez mikrofona).
        if (resultCode != RESULT_OK) {
            if (System.currentTimeMillis() - glasZacetek < 1_500) Toast.makeText(this, R.string.os_mediji_ni_glasovnega, Toast.LENGTH_LONG).show()
            return
        }
        data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { iskanjeAliPrikaz(it) }
    }

    /**
     * Enotno iskanje (»dodaj vir in pozabi nanj«): hkrati ta naprava, naprave v Safeer Linku (racunalnik,
     * telefon), dodani viri, Jamendo, PeerTube in radio. Zadetke zdruzimo, odstranimo dvojnike in
     * razvrstimo po ujemanju ([Relevantnost]); vsak pove svoj izvor. Splet ponudimo samo, ce lastni
     * viri nimajo dovolj dobrih zadetkov. Fokus gre na prvi zadetek.
     */
    private fun isci(beseda: String) {
        if (beseda.length < 2) return
        iskalnik?.let { skrijTipkovnico(it) }
        val samoDodatek = zasebnoIskanje
        if (samoDodatek == null) MedijskiViri.zapomniIskanje(this, beseda)
        zadnjaBeseda = beseda
        podpisZadetkov = ""
        // Novo iskanje z odprto podstranjo (»Pokazi vse«, epizode): podstran se zapre in pride stran zadetkov - prej je
        // podstran ostala, novi zadetki pa niso prisli na zaslon (»Nalagam ...« brez konca).
        if (podstrani.odprta) { naMestuDo = 0L; narisi(zadetki ?: predIskanjem(), getString(R.string.os_glasba_nalagam)) }
        val moje = ++nalaganje
        stanje.text = getString(R.string.os_glasba_nalagam)
        val strezniki = MedijskiViri.streznikiPeerTube(this)
        val mali = beseda.lowercase()
        val viri = MedijskiViri.vsi(this).filter { it.tip != MedijskiViri.API && !it.jeDodatek && !it.jeImenik && !it.jeBesedila }.filter { it.ime.lowercase().contains(mali) || it.naslov.lowercase().contains(mali) ||
            Relevantnost.ocena(beseda, it.ime, "", it.naslov) >= Relevantnost.DOBER }
        val naprave = if (link.jeKrajevni() || !link.povezan) emptyList() else link.racunalnikiZDatotekami()
        val taNaprava = getString(if (jeTv()) R.string.os_media_ta_tv else R.string.os_media_ta_naprava)
        // Preklic starih poizvedb: hitro zaporedno iskanje ne sme pustiti kupa zivih niti.
        synchronized(iskanjeNiti) { iskanjeNiti.forEach { it.cancel(true) }; iskanjeNiti.clear() }
        val rezultati = arrayOfNulls<Any>(15)
        val stremioNaslovi = MedijskiViri.vsi(this).filter { it.jeStremio }.map { it.naslov }
        val spletniViri = MedijskiViri.vsi(this).filter { it.jeSplet || it.tip == MedijskiViri.API }
        val imenaSpletnihVirov = spletniViri.map { it.ime }
        val izSeznamov = MedijskiViri.iskanjeVSeznamih(this, beseda)
        val torrentZaVzorec = torrentMeja()
        // Naloga 57: vgrajeni spletni viri (Jamendo, Radio Browser, imenik podkastov, Archive.org, TuneIn, kanali TV v zivo)
        // ne iscejo vec - iscejo uporabnikovi viri in naprave.
        val vg = MedijskiViri.VGRAJENI_SPLETNI_VIRI
        // Naloga 69: imenik postaj, ki ga je uporabnik dodal sam, isce kot prej.
        val isciRadioBrowser = vg || imenikDodan(VIR_RADIO)
        val isciTuneIn = vg || imenikDodan(VIR_TUNEIN)
        val opravila = listOf<() -> Any>(
            { if (!vg) emptyList<Jamendo.Skladba>() else try { Jamendo.isciSkladbe(beseda) } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
            { try { PeerTube.isci(strezniki, beseda) } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
            { if (!isciRadioBrowser) emptyList<Jamendo.Skladba>() else try { Radio.isci(beseda) } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
            { if (!vg) emptyList<Jamendo.Izvajalec>() else try { Jamendo.isciIzvajalce(beseda) } catch (_: Exception) { emptyList<Jamendo.Izvajalec>() } },
            { try { KrajevneDatoteke.najdi(this, beseda) } catch (_: Exception) { emptyList<KrajevneDatoteke.Najdeno>() } },
            { isciNaNapravah(naprave, beseda) },
            { if (!vg) emptyList<Jamendo.Skladba>() else try { Podkasti.isci(beseda) } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
            { if (!vg) emptyList<Jamendo.Skladba>() else try { Arhiv.isci(beseda) } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
            { SpletniVir.isciVse(this, spletniViri, beseda) },
            { if (!vg) emptyList<Jamendo.Skladba>() else try { JavnaLast.isci(beseda) } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
            { if (!isciTuneIn) emptyList<Jamendo.Skladba>() else try { TuneIn.isci(beseda) } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
            { try {
                // Zasebne kategorije kanalov morajo biti znane, preden zadetki dodatkov pridejo na zaslon: iskanje po imenu v
                // katalogu »vse« bi sicer nastelo tudi zasebne kanale (do zdaj jih je poznalo sele, ko je uporabnik v tem zagonu
                // aplikacije odprl TV v zivo). Ce njihov vir ne odgovori, kanalov tega dodatka med zadetki ni.
                // Naloga 81: iskanje v dodatkih tece HKRATI s tem (prej sele po njem - do 10 s cakanja na zasebne kategorije,
                // potem je skupni rok 12 s iskanje preklical); katalogi samo za iskanje so v opravilu 13.
                val zadetki = iskanjeDelavec.submit<List<Jamendo.Skladba>> {
                    if (samoDodatek != null) Stremio.isci(listOf(samoDodatek), beseda, tudiZasebni = true)
                    else Stremio.isci(Stremio.zKatalogom(stremioNaslovi), beseda, samoIskanje = false)
                }
                synchronized(iskanjeNiti) { iskanjeNiti.add(zadetki) }
                val neznani = if (samoDodatek != null) emptySet() else neznaniZasebniKanali(stremioNaslovi)
                zadetki.get(15, java.util.concurrent.TimeUnit.SECONDS)
                    .filter { neznani.isEmpty() || Stremio.razredEnote(it) != Stremio.TV || (Stremio.razstavi(it)?.first ?: "") !in neznani }
                    // Zadetki iz lastnih knjiznic dodatkov pridejo na zaslon ze s pravim odgovorom (vzorec, najvec nekaj
                    // sekund) - nic se ne prikaze ali skrije naknadno.
                    .also { vzorciKnjizniceZdaj(it, torrentZaVzorec) }
            } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
            // Vgrajeni kanali TV v zivo (seznam je v aplikaciji - brez omrezja).
            { if (!vg) emptyList<Jamendo.Skladba>() else try { TvVZivo.poDrzavah().flatMap { it.second }.filter { Stremio.ujema(it.naslov, beseda) } } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
            // Naloga 81: katalogi samo za iskanje (YouTubio: tip YouTube, obvezni search) - lastno opravilo z daljsim rokom
            // ([OsPravila.iskanjeKoncano]); zasebnih kategorij kanalov nimajo.
            { if (samoDodatek != null) emptyList<Jamendo.Skladba>() else try { Stremio.isci(Stremio.zKatalogom(stremioNaslovi), beseda, samoIskanje = true) } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
            // Lastnik 27. 10. 2026: glasba z Useneta (izvajalec ali skladba) pri uporabnikovem indeksatorju - samo z lastnim ponudnikom.
            { if (samoDodatek != null || !Usenet.glasbaNaVoljo(applicationContext)) emptyList<UsenetPravila.Zadetek>()
              else try { Usenet.isciGlasbo(applicationContext, beseda) } catch (_: Exception) { emptyList<UsenetPravila.Zadetek>() } },
        )
        // Iskanje v zasebnem dodatku vprasa samo ta dodatek (opravilo 11); ostali viri ostanejo prazni.
        val futures = opravila.mapIndexed { i, f -> iskanjeDelavec.submit { rezultati[i] = if (samoDodatek != null && i != 11) emptyList<Any>() else f() } }
        synchronized(iskanjeNiti) { iskanjeNiti.addAll(futures) }
        /** Narise zadetke, ki so ze prispeli; [koncno] = vsi viri so odgovorili ali je rok potekel. */
        fun prikazi(koncno: Boolean, prvic: Boolean): Boolean {
            @Suppress("UNCHECKED_CAST") val glasba = rezultati[0] as? List<Jamendo.Skladba> ?: emptyList()
            @Suppress("UNCHECKED_CAST") val videi = rezultati[1] as? List<Jamendo.Skladba> ?: emptyList()
            @Suppress("UNCHECKED_CAST") val postaje = rezultati[2] as? List<Jamendo.Skladba> ?: emptyList()
            @Suppress("UNCHECKED_CAST") val izvajalci = rezultati[3] as? List<Jamendo.Izvajalec> ?: emptyList()
            @Suppress("UNCHECKED_CAST") val krajevno = rezultati[4] as? List<KrajevneDatoteke.Najdeno> ?: emptyList()
            @Suppress("UNCHECKED_CAST") val vLinku = rezultati[5] as? List<Relevantnost.Zadetek<Jamendo.Skladba>> ?: emptyList()
            @Suppress("UNCHECKED_CAST") val podkasti = rezultati[6] as? List<Jamendo.Skladba> ?: emptyList()
            @Suppress("UNCHECKED_CAST") val arhiv = rezultati[7] as? List<Jamendo.Skladba> ?: emptyList()
            @Suppress("UNCHECKED_CAST") val izSpleta = rezultati[8] as? List<Pair<MedijskiViri.Vir, Jamendo.Skladba>> ?: emptyList()
            @Suppress("UNCHECKED_CAST") val javnaLast = rezultati[9] as? List<Jamendo.Skladba> ?: emptyList()
            @Suppress("UNCHECKED_CAST") val tuneInSurovi = rezultati[10] as? List<Jamendo.Skladba> ?: emptyList()
            @Suppress("UNCHECKED_CAST") val izDodatkovVsi = ((rezultati[11] as? List<Jamendo.Skladba>).orEmpty() + (rezultati[13] as? List<Jamendo.Skladba>).orEmpty()).distinctBy { it.id }
            zadetkiDodatkov = izDodatkovVsi
            // Cesar noben dodatek ne predvaja, med zadetki ni.
            val izDodatkov = izDodatkovVsi.filterNot { znanoNiNaVoljo(it) || cakaNaKnjiznico(it) }
            @Suppress("UNCHECKED_CAST") val tvKanali = rezultati[12] as? List<Jamendo.Skladba> ?: emptyList()
            // Lastnik 28. 10. 2026: izdaje, ki niso vec na voljo (trajna napaka), se ne pokazejo.
            @Suppress("UNCHECKED_CAST") val usenetAlbumi = (rezultati[14] as? List<UsenetPravila.Zadetek> ?: emptyList())
                .filterNot { UsenetNzbShramba.jeNeuspel(applicationContext, it.naslov, it.velikost) }
            // Zadetki dodatkov po vrsti vsebine: vsak pristane v svoji polici (film, serija, TV v zivo, glasba, radio).
            val dodatkiPoRazredu = izDodatkov.groupBy { Stremio.razredEnote(it) }
            // Naloga 81: drug video (tip YouTube ...) ni film - gre v polico Video; iskanje iz Glasbe: med glasbo (posnetki).
            val dFilmi = dodatkiPoRazredu[Stremio.FILM].orEmpty()
            val dVideo = dodatkiPoRazredu[Stremio.VIDEO].orEmpty()
            val vGlasbo = iskanjeIz == GLASBA
            val dSerije = dodatkiPoRazredu[Stremio.SERIJA].orEmpty()
            val dTv = dodatkiPoRazredu[Stremio.TV].orEmpty()
            val dGlasba = dodatkiPoRazredu[Stremio.GLASBA].orEmpty()
            val dRadio = dodatkiPoRazredu[Stremio.RADIO].orEmpty()
            // Naloga 71: TuneIn toka do klika ne pozna; ista postaja Radio Browserja (po kljucu imena ali toka, OsPravila) ostane,
            // TuneIn izpade. Tudi Radio Browser sam ima isto postajo veckrat.
            val postajeBrez = brezDvojnikovPostaj(postaje + tuneInSurovi)
            val tuneIn = postajeBrez.filter { TuneIn.jeEnota(it) }
            // Vsi zadetki v eni skupni lestvici; prednost odloca med dvojniki (ta naprava pred racunalnikom ...).
            val vsi = ArrayList<Relevantnost.Zadetek<*>>()
            krajevno.forEach { n ->
                val (naslov, izvajalec) = Relevantnost.razdeli(n.naslov, n.izvajalec)
                val sk = Jamendo.Skladba("krajevno:" + n.uri(), naslov, izvajalec, "", n.uri().toString(), "",
                    video = n.zbirka == "video", mime = n.mime)
                vsi.add(Relevantnost.Zadetek(sk, naslov, izvajalec, taNaprava, 0, n.mapa + " " + n.ime))
            }
            vsi.addAll(vLinku)
            viri.forEach { v -> MedijskiViri.kotSkladba(v).let { vsi.add(Relevantnost.Zadetek(it, v.ime, "", v.ime, 2, v.naslov)) } }
            izSeznamov.forEach { (v, s) -> vsi.add(Relevantnost.Zadetek(s, s.naslov, s.izvajalec, v.ime, 2)) }
            izSpleta.forEach { (_, s) -> vsi.add(Relevantnost.Zadetek(s, s.naslov, s.izvajalec, getString(R.string.os_media_tvoji_viri), 2)) }
            arhiv.forEach { vsi.add(Relevantnost.Zadetek(it, it.naslov, it.izvajalec, "Archive.org", 6)) }
            glasba.forEach { vsi.add(Relevantnost.Zadetek(it, it.naslov, it.izvajalec, "Jamendo", 3)) }
            videi.forEach { vsi.add(Relevantnost.Zadetek(it, it.naslov, it.izvajalec, "PeerTube", 4)) }
            javnaLast.forEach { vsi.add(Relevantnost.Zadetek(it, it.naslov, it.izvajalec, getString(R.string.os_media_javna_last), 4)) }
            postajeBrez.filterNot { TuneIn.jeEnota(it) }.forEach { vsi.add(Relevantnost.Zadetek(it, it.naslov, it.izvajalec, getString(R.string.os_glasba_radio), 5)) }
            tuneIn.forEach { vsi.add(Relevantnost.Zadetek(it, it.naslov, it.izvajalec, "TuneIn", 5)) }
            izDodatkov.forEach {
                val vrsta = getString(when (Stremio.razredEnote(it)) {
                    Stremio.SERIJA -> R.string.os_media_serije; Stremio.TV -> R.string.os_mediji_tv_v_zivo
                    Stremio.GLASBA -> R.string.os_mediji_glasba; Stremio.RADIO -> R.string.os_glasba_radio
                    Stremio.FILM -> R.string.os_media_filmi; else -> R.string.os_glasba_video
                })
                vsi.add(Relevantnost.Zadetek(it, it.naslov, "", vrsta, 2))
            }
            tvKanali.forEach { vsi.add(Relevantnost.Zadetek(it, it.naslov, "", getString(R.string.os_mediji_tv_v_zivo), 4)) }
            val lestvica = Relevantnost.razvrsti(beseda, vsi)
            val videniNajboljsi = mutableSetOf<String>()
            // Naloga 120: neodklenjena placljiva vsebina tudi med najboljsimi zadetki ne.
            val najboljsi = lestvica.filter { it.second >= Relevantnost.SPODNJA }
                // Naloga 121: tudi nedelujoc kanal ne.
                .filterNot { (it.first.stvar as? Jamendo.Skladba)?.let { s -> neodklenjena(s) || MrtviKanali.skrij(this, s) } == true }
                // Film ali serija iz kataloga s tocnim naslovom je prvi, pred posnetki z istim imenom (lastnik 25. 10. 2026).
                // Grok 10, M5: skladba, postaja ali izvajalec z enakim naslovom, ocenjeni vise, ostanejo pred filmom.
                .let { l -> OsPravila.najprejDela(l, beseda, { it.first.naslov }, { jeDeloKataloga(it.first) }, { it.first.prednost <= 1 },
                    ostane = { (it.first.stvar as? Jamendo.Skladba)?.video != true }) }.filter { z ->
                val s = z.first.stvar as? Jamendo.Skladba
                if (s != null) {
                    val k = SpletniVir.cistNaslov(s.izvajalec + " " + s.naslov)
                    if (k.isNotBlank()) videniNajboljsi.add(k) else true
                } else true
            }.take(12)
            val splet = Relevantnost.potrebujemSplet(lestvica)
            if (!koncno && lestvica.isEmpty() && izvajalci.isEmpty() && podkasti.isEmpty()) return false
            glavna.post {
                if (moje != nalaganje || isFinishing) return@post
                val spletVrsta = Vrsta(getString(R.string.os_media_na_spletu), listOf(Kartica(getString(R.string.os_media_isci_splet, beseda),
                    getString(R.string.os_media_isci_splet_opis), "", { odpriSplet(beseda) }, ikona = R.drawable.os_ikona_splet)))
                val nicNasli = lestvica.isEmpty() && izvajalci.isEmpty() && podkasti.isEmpty()
                val izSpletaVse = izSpleta.map { it.second }
                val spletVideospoti = izSpletaVse.filter { SpletniVir.vrstaVsebine(it) == SpletniVir.VIDEOSPOT }
                val spletAvdio = izSpletaVse.filterNot { it.video || SpletniVir.vrstaVsebine(it) == SpletniVir.VIDEOSPOT }
                val spletFilmiInSerije = izSpletaVse.filter { it.video && SpletniVir.vrstaVsebine(it) != SpletniVir.VIDEOSPOT }

                // Zadetki dodatkov: najprej tisti, ki se z iskanim res ujemajo. Kadar dobri zadetki so, tistih brez
                // ene same skupne besede (ohlapno iskanje kataloga) ne kazemo - iskanje "pop tv" ne pokaze nakljucnih serij.
                val imaDobre = lestvica.any { it.second >= Relevantnost.DOBER }
                fun poUjemanju(l: List<Jamendo.Skladba>) = l.map { it to Relevantnost.ocena(beseda, it.naslov, it.izvajalec) }
                    .filter { !imaDobre || it.second > 0.0 }.sortedByDescending { it.second }.map { it.first }
                // Brez podvojenih prikazov velja za to, kar blok »Najboljsi zadetki« na strani res pokaze (dve vrstici):
                // zadetek, ki je v njem sele za »Pokazi vse«, ostane tudi v bloku svoje vrste (prej ga na strani ni bilo nikjer).
                val vrstaNajboljsih = Vrsta(getString(R.string.os_media_najboljsi), najboljsi.map { p -> kartica(p.first, imenaSpletnihVirov) })
                val vidnih = OsPravila.vsebineVBloku(najboljsi.size, 0, mereMreze(vrstaNajboljsih.copy(mreza = true)).first, OsPravila.VRSTIC_BLOKA, false)
                val prikazani = najboljsi.take(vidnih).map { (it.first.stvar as Jamendo.Skladba).id }.toSet()
                fun ostali(s: List<Jamendo.Skladba>): List<Jamendo.Skladba> {
                    val filtrirani = s.filterNot { it.id in prikazani }
                    val videni = mutableSetOf<String>()
                    return filtrirani.filter {
                        val k = SpletniVir.cistNaslov(it.izvajalec + " " + it.naslov)
                        if (k.isNotBlank()) videni.add(k) else true
                    }
                }
                val vrste = listOfNotNull(
                    spletVrsta.takeIf { nicNasli && koncno },
                    vrstaNajboljsih,
                    // Dodatki: filmi in serije v loceni polici (jasno, kaj je kaj).
                    Vrsta("🎬 " + getString(R.string.os_media_filmi), videi(ostali(poUjemanju(dFilmi)), beseda), video = true),
                    Vrsta("📺 " + getString(R.string.os_media_serije), videi(ostali(poUjemanju(dSerije)), beseda), video = true),
                    Vrsta("📡 " + getString(R.string.os_mediji_tv_v_zivo), videi(ostali(poUjemanju(dTv + tvKanali)), beseda), video = true),
                    // Lastnik 27. 10. 2026: izdaje glasbe z Useneta - klik takoj predvaja (pri iskani skladbi, kot pri YouTubu).
                    Vrsta("\uD83D\uDCE6 Usenet", usenetAlbumi.map { z ->
                        // Lastnik 27. 10. 2026: dolg pritisk shrani album med priljubljene (ali ga odstrani).
                        // Lastnik 28. 10. 2026: izvajalec vrstico nad albumom, spodaj leto · oblika · velikost (prej celo ime izdaje, odrezano).
                        val (naslovK, podK) = UsenetGlasbaPravila.kartica(z.naslov, z.velikost)
                        Kartica(naslovK, podK, "", { predvajajUsenetAlbum(z, beseda, usenetAlbumi) },
                            dolgo = { preklopiUsenetAlbum(z.naslov, z.velikost) },
                            oznaka = if (UsenetAlbumi.je(this, z.naslov)) "★" else "",
                            ikona = R.drawable.os_ikona_glasba, id = "usenet:" + UsenetPravila.idToka(z.povezava))
                    }, skladbe = true),
                    Vrsta(getString(R.string.os_mediji_glasba), skladbe(ostali(poUjemanju(dGlasba) + (if (vGlasbo) poUjemanju(dVideo) else emptyList()) +
                        glasba + spletAvdio + spletVideospoti), beseda), skladbe = true),
                    Vrsta(getString(R.string.os_glasba_video), videi(ostali((if (vGlasbo) emptyList() else poUjemanju(dVideo)) + videi + javnaLast + spletFilmiInSerije), beseda), video = true),
                    Vrsta(getString(R.string.os_mediji_izvajalci), izvajalci.map { iz ->
                        Kartica(iz.ime, getString(R.string.os_glasba_izvajalec), iz.slika, { odpriIzvajalca(iz) }) }),
                    // Naloga 71: postaje dodatkov, imenikov in seznamov .m3u (zadetki seznamov so ze v lestvici zgoraj) brez dvojnikov.
                    Vrsta(getString(R.string.os_mediji_postaje), skladbe(ostali(brezDvojnikovPostaj(poUjemanju(dRadio) + izSeznamov.map { it.second }.filter { it.radio } +
                        postajeBrez).filterNot { p -> izSeznamov.any { it.second === p } }), beseda), kanali = true),
                    Vrsta(getString(R.string.os_media_podkasti), skladbe(podkasti, beseda), skladbe = true),
                    Vrsta("Archive.org", skladbe(ostali(arhiv), beseda), skladbe = true),
                    spletVrsta.takeIf { splet && !nicNasli && koncno },
                )
                zadetki = vrste
                // Koliko in kje: »5 zadetkov · Racunalnik, Jamendo, PeerTube«.
                // Naloga 81: zadetki dodatkov povejo ime dodatka (»YouTubio«), ne samo vrsto vsebine.
                val izvori = lestvica.map { z -> (z.first.stvar as? Jamendo.Skladba)?.takeIf { Stremio.jeEnota(it) }?.let { Stremio.imeIzvora(it) } ?: z.first.izvor }.distinct()
                opisZadetkov = if (izvori.isEmpty()) getString(R.string.os_media_ni_zadetkov_vir)
                    else getString(R.string.os_media_zadetki_izvori, lestvica.size, izvori.joinToString(", "))
                // Odprt blok zadetkov (»Pokazi vse«) ostane; novi zadetki so na voljo, ko se uporabnik vrne.
                if (razdelek != ISKANJE || podstrani.odprta) return@post
                val opisZdaj = if (koncno) opisZadetkov else opisZadetkov + " · " + getString(R.string.os_glasba_nalagam)
                // Enaki zadetki kot ze na zaslonu (vir je odgovoril brez novega, iskanje se je koncalo): zaslona ne risemo
                // znova, spremeni se le napis pod naslovom (izmerjeno 5. 10. 2026: sest polnih risanj v devetih sekundah).
                val podpis = vrste.joinToString("|") { v -> v.naslov + ":" + v.kartice.joinToString(",") { k -> k.id.ifBlank { k.naslov + "/" + k.podnaslov } } }
                if (koncno) odpriNatancnega(beseda, lestvica)
                if (podpis == podpisZadetkov && !prvic) { stanje.text = opisZdaj; return@post }
                podpisZadetkov = podpis
                // Brez spletnih virov: jasno sporocilo (iskali smo le po napravah), ne »nic nisem nasel« (naloga 57).
                val brezVirov = MedijskiViri.vsi(this).isEmpty()
                // Dopolnitev istega iskanja (vir je odgovoril pozneje, preverjanje razpolozljivosti) ostane na mestu, tudi med
                // drsenjem; na vrh gre samo novo iskanje (tudi mimo mesta zadnjega dejanja na strani).
                if (prvic) naMestuDo = 0L
                narisi(vrste, opisZdaj, prazno = getString(if (brezVirov && koncno) R.string.os_media_isci_brez_virov else R.string.os_glasba_prazno),
                    naMestu = !prvic)
                if (prvic) fokusNaPrvo()
            }
            return true
        }
        Thread {
            // Prikaz po delih: kar prispe v 2,5 s, se takoj pokaze; pocasnejsi viri dopolnijo isti zaslon
            // (narisi obdrzi fokus na isti kartici). Po 12 s ne cakamo vec (spletni viri na TV potrebujejo cas), na vir samo za iskanje do 16 s.
            val zacetek = System.currentTimeMillis()
            var narisanih = -1
            var zadnjic = 0L
            while (moje == nalaganje && !isFinishing) {
                val gotovih = futures.count { it.isDone }
                val cas = System.currentTimeMillis() - zacetek
                // Naloga 81: vir samo za iskanje (opravilo 13, YouTubio) dobi do 16 s, ostali kot doslej 12 s.
                val konec = OsPravila.iskanjeKoncano(gotovih == futures.size, futures[13].isDone, cas)
                // Delni prikaz najvec na dve sekundi: vec virov, ki odgovorijo skoraj hkrati, je eno risanje.
                if ((konec || (gotovih != narisanih && cas >= 2_500 && cas - zadnjic >= 2_000)) && prikazi(konec, narisanih < 0)) { narisanih = gotovih; zadnjic = cas }
                if (konec) break
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
            futures.forEach { if (!it.isDone) it.cancel(true) }
            // Filme in serije iz dodatkov med zadetki preverimo: cesar se ne da predvajati, iz zadetkov izgine.
            if (moje == nalaganje && !isFinishing && preveriZadetke(zadetkiDodatkov) && moje == nalaganje && !isFinishing) prikazi(true, false)
        }.start()
    }

    /** Zadetki dodatkov zadnjega iskanja (za preverjanje razpolozljivosti po prikazu). */
    @Volatile private var zadetkiDodatkov: List<Jamendo.Skladba> = emptyList()

    /** Preveri filme in serije med zadetki (najvec 12, do 10 s); vrne true, ce se katerega ne da predvajati. Klic iz delovne niti. */
    private fun preveriZadetke(l: List<Jamendo.Skladba>): Boolean {
        val naslovi = stremioNaslovi()
        if (naslovi.isEmpty()) return false
        // Naslovov iz lastne knjiznice dodatka ne sprasujemo vsakega posebej (zanje odgovarja vzorec knjiznice).
        val cakajo = l.filter { sk -> !Stremio.izLastneKnjiznice(sk) && kljucRazpolozljivosti(sk)?.let { Razpolozljivost.stanje(this, it) == null } == true }.take(12)
        if (cakajo.isEmpty()) return false
        val torrent = torrentMeja()
        val niti = cakajo.map { sk -> preverjanjeEpizod.submit<Boolean> {
            val seVelja = { !isFinishing && vOspredju }
            val k = kljucRazpolozljivosti(sk)
            if (k != null && DomPreverjanjePravila.veljaven(k)) {
                DomPreverjanje.preveri(applicationContext, k, torrent, { prvaEpizoda(sk) }, seVelja) == DomPreverjanje.Izid.ODGOVOR &&
                    Razpolozljivost.stanjeZa(applicationContext, k, torrent) == false
            } else {
                val r = try { preveriEnoto(sk, naslovi, torrent, seVelja) } catch (_: Exception) { null }
                if (r != null && k != null) Razpolozljivost.zapomni(applicationContext, k, r)
                r?.velja(torrent) == false
            }
        } }
        val rok = System.currentTimeMillis() + 10_000
        return niti.count { f -> try { f.get((rok - System.currentTimeMillis()).coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) { false } } > 0
    }

    /** Streznik datotek naprave v Linku za zadetek (predvajanje s pripetim potrdilom). */
    private val strezniki = ConcurrentHashMap<String, DatotekeActivity.Streznik>()

    /** Film ali serija iz kataloga dodatka (Cinemeta ...) ali z id-jem IMDb/TMDB - delo, ne posnetek z istim imenom. */
    private fun jeDeloKataloga(z: Relevantnost.Zadetek<*>): Boolean {
        val s = z.stvar as? Jamendo.Skladba ?: return false
        if (Stremio.jeEnota(s)) return Stremio.razredEnote(s).let { it == Stremio.FILM || it == Stremio.SERIJA }
        return (s.imdbId.isNotBlank() || s.tmdbId.isNotBlank()) && SpletniVir.vrstaVsebine(s).let { it == SpletniVir.FILM || it == SpletniVir.SERIJA }
    }

    /** Kartica iskanja: spletni vir je notranja podrobnost, zato ga na kartici ne razkrivamo. */
    private fun kartica(z: Relevantnost.Zadetek<*>, imenaVirov: Collection<String>): Kartica {
        val sk = z.stvar as Jamendo.Skladba
        // Spletni zadetek pove leto in avtorja iz naslova posnetka (ime vira ne): trije zadetki »Murderball« so bili na las
        // enake kartice, v resnici pa trije razlicni posnetki YouTuba (lastnik 25. 10. 2026; OsPravila.podnaslovSpletnegaZadetka).
        val podnaslov = if (SpletniVir.jeEnota(sk)) OsPravila.podnaslovSpletnegaZadetka(sk.year, sk.izvajalec, imenaVirov)
            else listOf(sk.izvajalec, z.izvor).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        val klik: (View) -> Unit = {
            val s = strezniki[sk.id]
            when {
                sk.mime == MedijskiViri.STRAN -> odpriStran(sk.zvok, sk.naslov)
                s != null -> {
                    GlasbaStoritev.predvajaj(this, listOf(sk), 0, s)
                    if (sk.video) startActivity(Intent(this, PredvajanjeActivity::class.java))
                }
                else -> predvajaj(listOf(sk), 0)
            }
        }
        val ikona = when { sk.mime == MedijskiViri.STRAN -> R.drawable.os_ikona_splet; sk.radio -> R.drawable.os_ikona_radio
            sk.video -> R.drawable.os_ikona_video; else -> R.drawable.os_ikona_glasba }
        return Kartica(sk.naslov, podnaslov, sk.slika, klik, { meni(sk, listOf(sk), "", null) }, ikona = ikona)
    }

    /**
     * Glasba in videi naprav v Safeer Linku (`files.search`): vsaka naprava odgovori sama, najdlje 8 s;
     * naprava, ki ukaza se ne pozna, preprosto ne prispeva zadetkov.
     */
    private fun isciNaNapravah(naprave: List<LinkOdjemalec.Naprava>, beseda: String): List<Relevantnost.Zadetek<Jamendo.Skladba>> {
        if (naprave.isEmpty()) return emptyList()
        val izid = java.util.Collections.synchronizedList(ArrayList<Relevantnost.Zadetek<Jamendo.Skladba>>())
        val cakam = java.util.concurrent.CountDownLatch(naprave.size)
        for (n in naprave) {
            link.ukaz(n.id, "files.search", org.json.JSONObject().put("q", beseda), 6_000, LinkOdjemalec.Odgovor { odgovor, _ ->
                try {
                    val podatki = odgovor?.takeIf { it.optBoolean("ok") }?.optJSONObject("data")
                    val sv = podatki?.optJSONObject("server")
                    val items = podatki?.optJSONArray("items")
                    if (sv != null && items != null) {
                        val s = DatotekeActivity.Streznik(sv.optString("base_url").trimEnd('/'), sv.optString("fp"), sv.optString("token"), n.id)
                        val izvor = DatotekeActivity.lepoIme(n.ime).ifBlank { n.ime }
                        for (i in 0 until items.length()) {
                            val e = items.optJSONObject(i) ?: continue
                            val vrsta = e.optString("type")
                            if (vrsta != "audio" && vrsta != "video") continue
                            val ime = e.optString("name")
                            val (naslov, izvajalec) = Relevantnost.razdeli(e.optString("title").ifBlank { ime }, e.optString("artist"))
                            val sk = Jamendo.Skladba("link:${n.id}:${e.optString("id")}", naslov, izvajalec, "",
                                s.url(e.optString("id")), "", video = vrsta == "video", mime = e.optString("mime"),
                                podnapisi = Podnapisi.izSeznama(e.optJSONArray("subtitles"), s))
                            strezniki[sk.id] = s
                            izid.add(Relevantnost.Zadetek(sk, naslov, sk.izvajalec, izvor, 1, e.optString("path") + " " + ime))
                        }
                    }
                } finally { cakam.countDown() }
            })
        }
        try { cakam.await(7, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) { }
        return ArrayList(izid)
    }

    /** Uporabnikov spletni vir ostane "dodaj in pozabi": pri globalnem iskanju ponudimo
     * omejeno iskanje znotraj njegove domene. S tem ne ugibamo zasebnih API-jev strani. */
    private fun odpriIskanjeVViru(vir: MedijskiViri.Vir, beseda: String) {
        val host = try { java.net.URL(vir.naslov).host } catch (_: Exception) { return }
        val q = "site:$host $beseda"
        GlasbaStoritev.premor()
        startActivity(Brskalnik.medijskaStran(this,
            "https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8"), vir.ime))
    }

    /** Iskalnik, ki ga je uporabnik izbral v brskalniku; pri Googlu zavihek Videoposnetki. */
    private fun spletnoIskanje(beseda: String): String {
        val i = si.safeer.tv.SmartOmnibox.iskalnik(this)
        return if (i == si.safeer.tv.SmartOmnibox.Iskalnik.GOOGLE) "https://www.google.com/search?tbm=vid&q=" + java.net.URLEncoder.encode(beseda, "UTF-8")
        else si.safeer.tv.SmartOmnibox.iskanje(this, beseda, i)
    }

    /** Iskanje na spletu v brskalniku Safeer (izbrani iskalnik). */
    private fun odpriSplet(beseda: String) {
        GlasbaStoritev.premor()
        try {
            startActivity(Brskalnik.izMedijev(Brskalnik.namera(this)).setAction(Intent.ACTION_VIEW)
                .setData(android.net.Uri.parse(spletnoIskanje(beseda))))
        } catch (_: Exception) { Toast.makeText(this, R.string.os_odpri_ni_aplikacije, Toast.LENGTH_SHORT).show() }
    }

    /** Fokus na prvo kartico prve vrste (za iskalnim poljem). */
    /**
     * Izbira (daljinec) na prvo kartico vsebine. Vrstica razdelkov (Glasba | Video | ...) in izbire mreze (Filmi,
     * razvrstitev, zvrst) niso vsebina: ce pod njimi se ni kartic, gre izbira na odprti razdelek in funkcija vrne false
     * (klicatelj lahko poskusi znova, ko vsebina pride).
     */
    private fun fokusNaPrvo(): Boolean {
        var glava: View? = null
        for (i in 0 until vsebina.childCount) {
            val v = vsebina.getChildAt(i)
            val vrsta = (if (v.tag == MREZA_VRSTA) v else (v as? HorizontalScrollView)?.getChildAt(0)) as? LinearLayout ?: continue
            val prvi = vrsta.getChildAt(0) ?: continue
            val oznaka = prvi.tag as? String
            if (oznaka?.startsWith("r:") == true) {
                glava = (0 until vrsta.childCount).map { vrsta.getChildAt(it) }.firstOrNull { it.alpha == 1f } ?: prvi
                continue
            }
            if (oznaka == "k:nacin") { if (glava == null) glava = prvi; continue }
            if (prvi.requestFocus()) return true
        }
        glava?.requestFocus()
        return false
    }

    /**
     * Seznam iz vira (epizode podkasta, dodani .m3u) ali seznam predvajanja: podstran s celo mrezo; uporabnik izbere, kaj
     * predvaja. Nazaj vrne, od koder je bil odprt (stran razdelka, »Pokazi vse« bloka, mreza), na isto mesto.
     */
    private fun odpriSeznam(ime: String, opis: String, seznam: MedijskiViri.Seznam? = null, nalozi: () -> List<Jamendo.Skladba>) {
        stanje.text = getString(R.string.os_glasba_nalagam)
        val od = mestoZdaj()
        val odRazdelka = razdelek
        val odGlobine = podstrani.globina
        delavec.execute {
            val s = try { nalozi() } catch (_: Exception) { emptyList() }
            glavna.post {
                if (isFinishing || isDestroyed) return@post
                // Uporabnik je medtem odsel drugam (drug razdelek, Nazaj, druga podstran): seznama mu ne odpremo pod prsti.
                if (razdelek != odRazdelka || podstrani.globina != odGlobine) return@post
                if (s.isEmpty()) { stanje.text = getString(R.string.os_glasba_napaka); return@post }
                val p = Podstran.Seznam(ime, opis, seznam != null, s)
                if (vrstaPodstrani(p) == null) { stanje.text = getString(R.string.os_glasba_prazno); return@post }
                odpriPodstran(p, od)
            }
        }
    }

    private fun razresiArhiv(sk: Jamendo.Skladba) {
        stanje.text = getString(R.string.os_glasba_nalagam)
        delavec.execute {
            val s = try { Arhiv.datoteke(sk) } catch (_: Exception) { emptyList() }
            glavna.post {
                if (isFinishing || isDestroyed) return@post
                if (s.isEmpty()) { stanje.text = getString(R.string.os_glasba_napaka); return@post }
                stanje.text = if (razdelek == ISKANJE && zadetki != null) opisZadetkov else opis(razdelek)
                GlasbaStoritev.predvajaj(this, s, 0)
                if (sk.video) startActivity(Intent(this, PredvajanjeActivity::class.java))
            }
        }
    }

    /**
     * Zadetek iz uporabnikove spletne aplikacije: tok ujamemo in ga predvaja nas predvajalnik. Zascitenega
     * toka ne ujamemo - takrat igra stran v skritem pogledu, upravlja pa jo nas predvajalnik ([SpletniIgralec]).
     */
    private fun razresiSplet(sk: Jamendo.Skladba, rezervni: List<Jamendo.Skladba> = emptyList(), obstojecaPriprava: String? = null) {
        val priprava = obstojecaPriprava ?: zacniPripravo(sk) ?: return
        stanje.text = getString(R.string.os_glasba_nalagam)
        SpletniVir.razresi(this, sk) { r ->
            if (isFinishing) { koncajPripravo(priprava); return@razresi }
            stanje.text = if (razdelek == ISKANJE && zadetki != null) opisZadetkov else opis(razdelek)
            if (r == null) {
                // Ce primarni vir ne uspe ponuditi neposrednega toka, kaskadno poskusimo naslednje razpolozljivo ogledalo.
                val naslednji = rezervni.firstOrNull { it.povezava != sk.povezava }
                if (naslednji != null) {
                    razresiSplet(naslednji, rezervni.filterNot { it.povezava == naslednji.povezava }, priprava)
                    return@razresi
                }
                koncajPripravo(priprava)
                SpletniIgralec.zadnja = java.lang.ref.WeakReference(this)
                GlasbaStoritev.predvajajSplet(this, sk)
                if (sk.video) startActivity(Intent(this, PredvajanjeActivity::class.java))
                return@razresi
            }
            koncajPripravo(priprava)
            GlasbaStoritev.predvajaj(this, listOf(r), 0)
            if (r.video) nadaljujKoPripravljen(sk)
            if (r.video) startActivity(Intent(this, PredvajanjeActivity::class.java))
        }
    }

    /** Skladbe izvajalca: podstran s celo mrezo (prej blok z dvema vrsticama, s katerega je Nazaj vodil na plosco). */
    private fun odpriIzvajalca(iz: Jamendo.Izvajalec) {
        stanje.text = getString(R.string.os_glasba_nalagam)
        val od = mestoZdaj()
        val odRazdelka = razdelek
        val odGlobine = podstrani.globina
        delavec.execute {
            val s = try { Jamendo.odIzvajalca(iz.id) } catch (_: Exception) { null }
            glavna.post {
                if (isFinishing || isDestroyed) return@post
                if (razdelek != odRazdelka || podstrani.globina != odGlobine) return@post
                if (s == null) { stanje.text = getString(R.string.os_glasba_napaka); return@post }
                val p = Podstran.Seznam(iz.ime, getString(R.string.os_glasba_od_izvajalca, iz.ime), false, s)
                if (vrstaPodstrani(p) == null) { stanje.text = getString(R.string.os_glasba_prazno); return@post }
                odpriPodstran(p, od)
            }
        }
    }

    // ------------------------------------------------------------------ viri

    /**
     * Naloga 57: koda ne doda nobenega spletnega vira sama ([MedijskiViri.VGRAJENI_SPLETNI_VIRI]), zato prazna mreza ali
     * razdelek ponudi Dodaj vir (obstojeci tok [dodajVir]). Vsebina z naprav (ta naprava, Link) je pokazana pred tem.
     */
    private fun karticaDodajVir() = Kartica(getString(R.string.os_mediji_dodaj), getString(R.string.os_mediji_dodaj_opis), "",
        { dodajVir() }, ikona = R.drawable.os_ikona_plus)
    /** Uporabnik nima nobenega spletnega vira (dodatka, streznika, toka, strani). */
    /** Pregled 70: imenik radijskih postaj ne da filmov ali glasbe - z njim samim mreza se vedno ponudi Dodaj vir. */
    // Naloga 89: tudi vir besedil ne da vsebine.
    private fun brezSpletnihVirov() = MedijskiViri.vsi(this).none { it.tip != MedijskiViri.IMENIK && it.tip != MedijskiViri.BESEDILA }

    private fun dodajVir() {
        val aplikacije = try { SpletneAplikacije.seznam(this) } catch (_: Throwable) { emptyList() }
            .filter { MedijskiViri.obstojeciVir(this, it.url) == null }
        if (aplikacije.isEmpty()) { vpisiVir(); return }
        val imena = aplikacije.map { it.ime.ifBlank { SpletneAplikacije.gostitelj(it.url) } }
        AlertDialog.Builder(this)
            .setTitle(R.string.os_mediji_dodaj)
            .setItems((imena + getString(R.string.os_mediji_vpisi_naslov)).toTypedArray()) { _, i ->
                aplikacije.getOrNull(i)?.let { dodajVNozadju(it.url, imena[i]) } ?: vpisiVir()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun vpisiVir() {
        val polje = EditText(this).apply {
            hint = getString(R.string.os_mediji_dodaj_namig); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.os_mediji_dodaj)
            .setMessage(R.string.os_mediji_dodaj_razlaga)
            .setView(FrameLayout(this).apply { setPadding(dp(20), 0, dp(20), 0); addView(polje) })
            .setPositiveButton(R.string.os_mediji_dodaj_gumb) { _, _ ->
                val vnos = polje.text.toString()
                if (vnos.isBlank()) return@setPositiveButton
                dodajVNozadju(vnos, null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Uvoz seznama predvajanja iz YouTuba ali Spotifyja: uporabnik prilepi povezavo (ali jo deli iz aplikacije). */
    private fun uvoziSeznam() {
        val polje = EditText(this).apply {
            hint = "https://…"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            // Povezava, ki jo je uporabnik pravkar kopiral, je ze v polju - ostane mu samo Uvozi.
            try {
                val odlozisce = getSystemService(android.content.ClipboardManager::class.java)
                odlozisce?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                    ?.let { UvozSeznama.povezavaIz(it) }?.let { setText(it); setSelection(it.length) }
            } catch (_: Exception) { }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.os_uvoz_naslov)
            .setMessage(R.string.os_uvoz_razlaga)
            .setView(FrameLayout(this).apply { setPadding(dp(20), 0, dp(20), 0); addView(polje) })
            .setPositiveButton(R.string.os_uvoz_gumb) { _, _ -> polje.text.toString().takeIf { it.isNotBlank() }?.let { uvoziVNozadju(it) } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun uvoziVNozadju(vnos: String) {
        val povezava = UvozSeznama.povezavaIz(vnos) ?: vnos.trim()
        if (!UvozSeznama.jePovezava(povezava)) {
            AlertDialog.Builder(this).setTitle(R.string.os_uvoz_naslov).setMessage(R.string.os_uvoz_ni_seznam)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        stanje.text = getString(R.string.os_uvoz_uvazam)
        Toast.makeText(this, R.string.os_uvoz_uvazam, Toast.LENGTH_SHORT).show()
        val odRazdelka = razdelek
        val odGlobine = podstrani.globina
        delavec.execute {
            val u = try { UvozSeznama.uvozi(povezava) } catch (_: Exception) { null }
            // Isto ime kot obstojec seznam ga zamenja (ponoven uvoz = osvezitev seznama).
            val sz = u?.let { MedijskiViri.shraniSeznam(this, it.ime.take(60), it.skladbe, it.vir) }
            glavna.post {
                if (isFinishing || isDestroyed) return@post
                // Uporabnik je medtem odsel drugam (uvoz traja): izid povemo, strani pod njim pa ne zamenjamo - seznam najde
                // med seznami predvajanja.
                val tukaj = razdelek == odRazdelka && podstrani.globina == odGlobine
                if (tukaj) stanje.text = opis(razdelek)
                if (sz == null) {
                    AlertDialog.Builder(this).setTitle(R.string.os_uvoz_naslov).setMessage(R.string.os_uvoz_ni_uspel)
                        .setPositiveButton(android.R.string.ok, null).show()
                    return@post
                }
                Toast.makeText(this, getString(R.string.os_uvoz_uspeh, sz.ime, sz.skladbe.size), Toast.LENGTH_LONG).show()
                SEZNAMI.remove(DOMOV); SEZNAMI.remove(GLASBA); SEZNAMI.remove(VIDEO)
                // Najprej Moji viri (tam so seznami predvajanja), nato uvozeni seznam: zacetni zaslon, ki se se
                // nalaga, ga ne prekrije, Nazaj pa pelje k seznamom.
                if (tukaj) {
                    izberi(VIRI)
                    odpriSeznam(sz.ime, "", sz) { sz.skladbe }
                }
                poisciPosnetke(sz.ime)
            }
        }
    }

    /**
     * Uvozenim skladbam brez posnetka (Spotify) v ozadju poiscemo posnetke: seznam dobi slike posameznih skladb in
     * predvajanje zacne brez iskanja. Uporabnik ne caka - seznam je ze odprt in predvajljiv (iskanje ob predvajanju).
     */
    private fun poisciPosnetke(ime: String) {
        val cakajo = MedijskiViri.seznami(this).firstOrNull { it.ime == ime }?.skladbe?.filter { UvozSeznama.jeIskana(it) } ?: return
        if (cakajo.isEmpty()) return
        val bazen = Executors.newFixedThreadPool(3)
        val narejenih = java.util.concurrent.atomic.AtomicInteger(0)
        val najdene = java.util.concurrent.ConcurrentHashMap<String, Jamendo.Skladba>()
        val aplikacija = applicationContext
        fun shraniDel(konec: Boolean) {
            val del = HashMap(najdene)
            if (del.isEmpty() && !konec) return
            del.keys.forEach { najdene.remove(it) }
            MedijskiViri.zamenjaj(aplikacija, del)
            glavna.post {
                if (isFinishing || isDestroyed) return@post
                SEZNAMI.remove(DOMOV); SEZNAMI.remove(GLASBA)
                stanje.text = if (konec) opis(razdelek) else getString(R.string.os_uvoz_iscem, narejenih.get(), cakajo.size)
                // Odprt seznam osvezimo ob koncu (slike skladb), ne sproti - sproti bi uporabniku skakal pod prsti.
                if (konec && odprtSeznam == ime) osveziNaMestu()
            }
        }
        cakajo.forEach { sk ->
            bazen.execute {
                try { UvozSeznama.najdi(sk)?.let { najdene[sk.id] = it } } catch (_: Exception) { }
                val n = narejenih.incrementAndGet()
                if (n == cakajo.size) shraniDel(true) else if (n % 10 == 0) shraniDel(false)
            }
        }
        bazen.shutdown()
    }

    /** Ime seznama predvajanja, ki je trenutno odprt cez ves zaslon (za osvezitev po iskanju posnetkov). */
    private var odprtSeznam = ""

    /** Skladbo doda na izbran seznam predvajanja ali na novega (ime vpise uporabnik). */
    private fun dodajNaSeznam(sk: Jamendo.Skladba) = SeznamOkno.dodaj(this, sk) {
        SEZNAMI.remove(DOMOV); SEZNAMI.remove(GLASBA); SEZNAMI.remove(VIDEO)
        osveziPriljubljene()
    }

    private fun dodajVNozadju(vnos: String, ime: String?) {
                // Povezava seznama predvajanja (YouTube, Spotify) v polju za vir: uvozimo seznam, ne dodajamo strani.
                if (UvozSeznama.jePovezava(UvozSeznama.povezavaIz(vnos) ?: vnos.trim())) { uvoziVNozadju(vnos); return }
                MedijskiViri.obstojeciVir(this, vnos)?.let {
                    Toast.makeText(this, getString(R.string.os_mediji_vir_ze_dodan, it.ime), Toast.LENGTH_SHORT).show()
                    return
                }
                stanje.text = getString(R.string.os_mediji_preverjam)
                delavec.execute {
                    val vir = MedijskiViri.dodaj(this, vnos, ime)
                    glavna.post {
                        if (isFinishing || isDestroyed) return@post
                        if (vir == null) {
                            // Diagnostika (TV 23. 10. 2026: tunein.com s tipkovnico ni bil prepoznan). Naloga 86 (M6): samo dolzina in
                            // razredi znakov, nikoli vsebina - povezava IPTV ima lahko uporabnisko ime in geslo.
                            android.util.Log.i("SafeerOsMedia", "vir zavrnjen: " + OsPravila.opisZavrnjenegaVnosa(vnos))
                            // Vira, ki ga ne znamo predvajati, ne dodamo - in to uporabniku jasno povemo.
                            stanje.text = getString(R.string.os_mediji_ni_vira)
                            AlertDialog.Builder(this)
                                .setTitle(R.string.os_mediji_dodaj)
                                .setMessage(getString(R.string.os_mediji_ni_vira) + "\n\n" + getString(R.string.os_mediji_ni_vira_zakaj))
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                            return@post
                        }
                        Toast.makeText(this, getString(R.string.os_mediji_dodano, vir.ime), Toast.LENGTH_SHORT).show()
                        poSpremembiVirov()
                        izberi(VIRI)
                        // Fokus na pravkar dodani vir: takoj ga lahko odpres.
                        drsnik.post { vsebina.findViewWithTag<View>("k:#${vsiViri().size}")?.requestFocus() }
                    }
                }
    }

    /**
     * Dodatki (Stremio): polje, kamor uporabnik vnese naslov SVOJEGA dodatka. Safeer ne prilaga nobenega kataloga ali
     * dodatka; shrani se le, kar je vneseno in preverjeno. Ponujamo samo dodatke, ki jih Safeer sam poganja.
     */
    private fun dodajDodatke() {
        val stremio = EditText(this).apply {
            hint = getString(R.string.os_mediji_dodatki_stremio_namig); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val napakaVrstica = TextView(this).apply {
            setTextColor(0xFFFF8A80.toInt()); textSize = 13f; setPadding(0, dp(6), 0, 0); visibility = View.GONE
        }
        // Ko uporabnik naslov popravlja, stara napaka izgine.
        stremio.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { napakaVrstica.visibility = View.GONE }
            override fun afterTextChanged(s: android.text.Editable?) { }
        })
        // Z daljincem je tipkanje naslova mucno: gumb Prilepi vzame naslov iz odlozisca (kopiran v Spletu
        // ali poslan z druge naprave), namig pa pove, da dodatek doda ze gumb Namesti na njegovi strani.
        val vrstica = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(stremio, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(android.widget.Button(this@GlasbaActivity).apply {
                text = getString(R.string.os_mediji_dodatki_prilepi); isAllCaps = false
                setOnClickListener {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    val b = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@GlasbaActivity)?.toString()?.trim().orEmpty()
                    if (b.isBlank()) Toast.makeText(this@GlasbaActivity, R.string.os_mediji_dodatki_prilepi_prazno, Toast.LENGTH_LONG).show()
                    else { stremio.setText(b); stremio.setSelection(b.length) }
                }
            })
        }
        val vsebina = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0)
            addView(TextView(this@GlasbaActivity).apply {
                text = getString(R.string.os_mediji_dodatki_namig_splet); setTextColor(osBarva(R.color.os_mint)); textSize = 13f; setPadding(0, dp(4), 0, dp(6))
            })
            addView(TextView(this@GlasbaActivity).apply {
                text = getString(R.string.os_mediji_dodatki_stremio); setTextColor(osBarva(R.color.os_besedilo)); textSize = 14f; setPadding(0, dp(10), 0, dp(2))
            })
            addView(vrstica)
            addView(napakaVrstica)
        }
        val okno = AlertDialog.Builder(this)
            .setTitle(R.string.os_mediji_dodatki)
            .setMessage(R.string.os_mediji_dodatki_razlaga)
            .setView(ScrollView(this).apply { addView(vsebina) })
            .setPositiveButton(R.string.os_mediji_dodatki_shrani, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // Napacen naslov okna ne zapre: napaka ostane pod poljem, dokler je uporabnik ne popravi (obvestilo, ki
        // izgine po treh sekundah, je na televizorju lahko spregledati).
        okno.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val vnos = stremio.text.toString()
            if (vnos.isBlank()) { okno.dismiss(); return@setOnClickListener }
            val (naslov, napaka) = MedijskiViri.preveriDodatek(MedijskiViri.STREMIO, vnos)
            if (naslov == null) {
                napakaVrstica.text = getString(if (napaka == "stremio") R.string.os_mediji_dodatki_napaka_stremio else R.string.os_mediji_dodatki_napaka_naslov)
                napakaVrstica.visibility = View.VISIBLE
                stremio.requestFocus()
                return@setOnClickListener
            }
            // Naloga 57d (lastnik: »Streznike, ki ne odgovarjajo, bi morali zavracati ze pri vnosu«): pred shranitvijo
            // mora manifest odgovoriti (200, veljaven JSON; spec streznik dobi se en poskus). Sicer vira ne shranimo.
            // Pregled S1: dodatek s poverilnico (zeton, kljuc) dobi nadomestek ze PRED preverbo - pravi naslov ne gre v nobeno
            // datoteko nastavitev (ime in zasebnost manifesta se zapiseta pod nadomestkom).
            val zaPreverbo = if (SkrivnostiPravila.skrivnaPredponaDodatka(naslov) != null) SkrivniDodatki.zavaruj(this, naslov) else naslov
            if (zaPreverbo == null) {
                napakaVrstica.text = getString(R.string.os_mediji_dodatek_ni_shranjen)
                napakaVrstica.visibility = View.VISIBLE
                return@setOnClickListener
            }
            val gumb = okno.getButton(AlertDialog.BUTTON_POSITIVE)
            gumb.isEnabled = false
            napakaVrstica.text = getString(R.string.os_mediji_preverjam)
            napakaVrstica.visibility = View.VISIBLE
            delavec.execute {
                val koda = try { Stremio.preveriObVnosu(zaPreverbo) } catch (_: Exception) { 0 }
                glavna.post {
                    if (isFinishing || !okno.isShowing) return@post
                    gumb.isEnabled = true
                    if (koda != 200) {
                        napakaVrstica.text = if (koda > 0) getString(R.string.os_mediji_streznik_ne_odgovarja, koda) else getString(R.string.os_mediji_streznik_ne_odgovarja_brez)
                        gumb.text = getString(R.string.os_mediji_poskusi_znova)
                        gumb.requestFocus()
                        return@post
                    }
                    napakaVrstica.visibility = View.GONE
                    shraniDodatek(okno, zaPreverbo)
                }
            }
        }
    }

    /** Preverjen dodatek shrani in zapre okno (naloga 57d: samo po uspesni preverbi [Stremio.preveriObVnosu]). */
    private fun shraniDodatek(okno: AlertDialog, naslov: String) {
        // Webtor rezerva: dodatek s poverilnico se shrani samo sifriran - ce to ne uspe, ga ne shranimo (okno ostane odprto).
        val v = MedijskiViri.dodajDodatek(this, MedijskiViri.STREMIO, naslov, "")
            ?: run { Toast.makeText(this, R.string.os_mediji_dodatek_ni_shranjen, Toast.LENGTH_LONG).show(); return }
        Toast.makeText(this, getString(R.string.os_mediji_dodatki_shranjen, v.ime), Toast.LENGTH_SHORT).show()
        okno.dismiss()
        poSpremembiVirov(); izberi(VIRI)
        // Naloga 75: kanale novega dodatka preverimo v ozadju (dodatek brez kanalov TV pregled konca takoj).
        PregledKanalov.zacni(applicationContext, v.naslov, rocno = false, jeSeznam = false)
        // Naloga 79b: isti dodatek z novim naslovom (po narocnini) - stari naslov ponudimo v odstranitev.
        preveriDvojnikDodatka(v.naslov)
        poskusiOdkleniDodatke(null)
        // Kartica dodatka pokaze ime iz manifesta (ne naslova streznika), brz ko ga preberemo.
        if (Stremio.imeIzPredpomnilnika(v.naslov) == null) delavec.execute {
            try { Stremio.zaseben(v.naslov) } catch (_: Exception) { }
            glavna.post { if (!isFinishing && razdelek == VIRI && odprtKatalog == null && !podstrani.odprta && Stremio.imeIzPredpomnilnika(v.naslov) != null) izberiNaMestu(VIRI) }
        }
    }

    /** Naloga 113: »Ne odgovarja od 13:02 (HTTP 500)« - od kdaj in s katero kodo ([Stremio.odziv]); brez casa kot doslej. */
    private fun opisNeodziva(naslov: String): String {
        val o = Stremio.odziv(naslov) ?: return getString(R.string.os_mediji_ne_odgovarja)
        if (o.odKdaj <= 0L) return getString(R.string.os_mediji_ne_odgovarja)
        val cas = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, Locale.getDefault()).format(java.util.Date(o.odKdaj))
        return if (o.koda > 0) getString(R.string.os_mediji_ne_odgovarja_od_http, cas, o.koda) else getString(R.string.os_mediji_ne_odgovarja_od, cas)
    }

    /** Naloga 113: »Preveri dodatek zdaj« - manifest kot pri vnosu ([Stremio.preveriObVnosu]); izid in osvezitev kartice. */
    private fun preveriDodatekZdaj(naslov: String) {
        Toast.makeText(this, R.string.os_mediji_preverjam, Toast.LENGTH_SHORT).show()
        delavec.execute {
            val koda = try { Stremio.preveriObVnosu(naslov) } catch (_: Exception) { 0 }
            android.util.Log.i("SafeerOsMedia", "preveri dodatek ${android.net.Uri.parse(Stremio.osnova(naslov)).host}: koda=$koda")
            glavna.post {
                if (isFinishing || isDestroyed) return@post
                Toast.makeText(this, when {
                    koda == 200 -> getString(R.string.os_mediji_dodatek_odgovarja)
                    koda > 0 -> getString(R.string.os_mediji_dodatek_ne_odgovarja_http, koda)
                    else -> getString(R.string.os_mediji_dodatek_ne_odgovarja_brez)
                }, Toast.LENGTH_SHORT).show()
                if (razdelek == VIRI && odprtKatalog == null && !podstrani.odprta) izberiNaMestu(VIRI)
            }
        }
    }

    /**
     * Seznami predvajanja so enaki na vseh napravah v Safeer Linku: ob odprtju vprasamo ostale naprave ([SeznamiSink]).
     * Ce se kaj spremeni, zaslon osvezimo le, ko uporabnik ravno nicesar ne dela (sicer ob naslednjem risanju).
     */
    private fun uskladiSezname() {
        if (link.jeKrajevni()) return
        SeznamiSink.uskladi(this) {
            if (isFinishing) return@uskladi
            SEZNAMI.remove(DOMOV); SEZNAMI.remove(GLASBA); SEZNAMI.remove(VIDEO)
            val miruje = android.os.SystemClock.uptimeMillis() - zadnjiDotik > 2_500
            // Novi ali izbrisani viri (dodatki ...) z druge naprave: kar je nastalo iz virov, ni vec pravo.
            val noviViri = SeznamiSink.viriSpremenjeni != videniViri
            if (noviViri) {
                videniViri = SeznamiSink.viriSpremenjeni
                poSpremembiVirov()
                // Odprta mreza Filmi | Serije se nalozi znova z novimi dodatki.
                odprtKatalog?.takeIf { it.tip.isNotBlank() && razdelek == VIDEO }?.let { o -> odpriBrskanje(o.tip, o.zvrst, o.jezik); return@uskladi }
            }
            if (miruje && odprtKatalog == null && !podstrani.odprta && (noviViri || razdelek == VIRI || razdelek == GLASBA || razdelek == DOMOV)) izberiNaMestu(razdelek)
        }
    }

    /** Zadnja sprememba Mojih virov z druge naprave, ki jo je ta zaslon ze uposteval ([SeznamiSink.viriSpremenjeni]). */
    private var videniViri = SeznamiSink.viriSpremenjeni

    /**
     * Naloga 62 (lastnik, 22. 10. 2026: »filmi odstranjenega vira so izginili sele, ko sem sel ven in nazaj«): vir je bil
     * dodan ali odstranjen (tu ali na drugi napravi). Vse, kar je nastalo iz virov, ni vec pravo: police razdelkov,
     * mreze Filmi | Serije (BRSKANJE), mreze Glasbe in Radia (VSEBINA_MREZE), kanali dodatkov, vse kartice razdelka
     * Video in police, ki jih je zaslon nazadnje pokazal (odpriBrskanje jih vzame za osnovo mreze). Prej so sli samo
     * SEZNAMI[DOMOV, VIDEO, TV_V_ZIVO]: mreza je ob vrnitvi pokazala BRSKANJE s karticami izbrisanega vira, in ker kartica,
     * ki je ze na zaslonu, ne izgine, so ostale. Disk velja po casu ([MedijskiViri.spremenjeni]). Odprta mreza: razdelek
     * Moji viri jo zapre (izberi), ob vrnitvi se nalozi znova brez starih kartic.
     */
    private fun poSpremembiVirov() {
        SEZNAMI.clear(); BRSKANJE.clear(); VSEBINA_MREZE.clear(); KANALI_MREZE.clear(); VSE_VIDEO = null
        prikazanePolice.clear()
        nasloviIzrisa = null
    }

    /** [ime] je ime s kartice (dodatek: iz manifesta) - shranjeno ime dodatka je lahko samo naslov streznika. */
    private fun odstraniVir(v: MedijskiViri.Vir, ime: String = v.ime) {
        // "Tudi z drugih naprav" velja le za vir, ki med napravami res potuje (zaseben dodatek ostane na tej napravi).
        val povsod = !link.jeKrajevni() && MedijskiViri.greMedNaprave(v)
        pokaziBrisanje(AlertDialog.Builder(this)
            .setTitle(ime.ifBlank { v.ime })
            .setMessage(if (povsod) R.string.os_mediji_odstrani_vir_vsepovsod else R.string.os_mediji_odstrani_vprasanje)
            .setPositiveButton(R.string.os_mediji_odstrani) { _, _ ->
                MedijskiViri.odstrani(this, v)
                // Naloga 76: zapis zasebnih kategorij odstranjenega dodatka gre z njim.
                if (v.jeStremio) { val app = applicationContext; delavec.execute { ZasebniKanali.pozabi(app, Stremio.osnova(v.naslov)) } }
                // Naloga 124: nastavitve in skrivnosti odstranjenega dodatka gredo z njim.
                if (v.jeStremio) Stremio.razsiritevZdaj(v.naslov)?.let { DodatkiNastavitve.pozabiDodatek(this, Stremio.osnova(v.naslov), it.nastavitve) }
                // Naloga 86 (M5): seznam IPTV - datoteki kanalov (do 20 MB) in napredek pregleda gresta z njim.
                if (v.jeSeznam) { val app = applicationContext; val n = v.naslov
                    try { delavec.execute { IptvSeznami.pozabi(app, n); PregledKanalov.pozabi(app, n) } } catch (_: java.util.concurrent.RejectedExecutionException) { } }
                poSpremembiVirov()
                izberiNaMestu(VIRI)
            }
            .setNegativeButton(android.R.string.cancel, null)
        )
    }

    // ------------------------------------------------------------------ predvajanje

    private fun zacniPripravo(sk: Jamendo.Skladba): String? {
        val kljuc = sk.id.ifBlank { sk.povezava.ifBlank { sk.naslov } }
        if (!pripraveVTeKu.add(kljuc)) return null
        prekiniDebrid()     // pregled A1: nova izbira - prejsnje cakanje na debrid ne odpre nicesar vec
        pokaziPripravljam(getString(R.string.os_media_pripravljam, sk.naslov))
        return kljuc
    }

    private fun koncajPripravo(kljuc: String) {
        pripraveVTeKu.remove(kljuc)
        if (pripraveVTeKu.isEmpty() && debridDelo == null) skrijPripravljam()
    }

    /**
     * Lastnik 27. 10. 2026: od klika do predvajalnika viden pas »Pripravljam …« na vrhu zaslona (prej kratek Toast, nato
     * nekaj sekund nic, medtem ko se je pripravljal Usenet in preverjali drugi viri). Nad vsebino (addContentView), brez fokusa.
     */
    private var pripravljamPas: TextView? = null

    private fun pokaziPripravljam(besedilo: String) {
        if (isFinishing || isDestroyed) return
        val pas = pripravljamPas ?: TextView(this).apply {
            setTextColor(osBarva(R.color.os_besedilo))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (jeSirokTv()) 18f else 15f)
            background = GradientDrawable().apply {
                setColor(osBarva(R.color.os_kartica_dvignjena)); cornerRadius = dp(20).toFloat(); setStroke(dp(1), osBarva(R.color.os_kartica_obroba))
            }
            setPadding(dp(20), dp(10), dp(20), dp(10))
            elevation = dp(8).toFloat()
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also {
            pripravljamPas = it
            addContentView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(18) })
        }
        pas.text = besedilo
        pas.visibility = View.VISIBLE
        pas.bringToFront()
    }

    private fun skrijPripravljam() { pripravljamPas?.visibility = View.GONE }

    private fun pripravaNiUspela(kljuc: String, sk: Jamendo.Skladba) {
        koncajPripravo(kljuc)
        stanje.text = getString(R.string.os_glasba_napaka)
        Toast.makeText(this, getString(R.string.os_media_priprava_napaka, sk.naslov), Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ nadaljuj z druge naprave (Predaja)

    /** Mesto, ki ga je dala druga naprava (predaja): velja za eno skladbo, enkrat. */
    private var predajaPolozaj: Pair<String, Long>? = null

    /** Vrsta s kartico "Nadaljuj z druge naprave" - samo, kadar so v Linku se druge naprave (nic samodejnega: klik vprasa). */
    private fun predajaVrsta(): List<Vrsta> {
        val link = LinkUpravitelj.pridobi(this)
        if (link.jeKrajevni() || link.naprave.none { !link.jeTaNaprava(it) && "remote" in it.zmoznosti }) return emptyList()
        val kartica = Kartica(getString(R.string.os_predaja_naslov), getString(R.string.os_predaja_opis), "", { nadaljujZDrugeNaprave() }, ikona = R.drawable.os_ikona_link)
        return listOf(Vrsta(getString(R.string.os_predaja_naslov), listOf(kartica), video = true, mala = true))
    }

    /** Vprasa naprave, kaj predvajajo (ali so nazadnje gledale), in ponudi nadaljevanje tukaj - vlecenje na cilju, kot je dolocil lastnik. */
    private fun nadaljujZDrugeNaprave() {
        stanje.text = getString(R.string.os_predaja_vprasam)
        Predaja.poizvedi(this) { ponudbe ->
            if (isFinishing) return@poizvedi
            stanje.text = if (razdelek == ISKANJE && zadetki != null) opisZadetkov else opis(razdelek)
            if (ponudbe.isEmpty()) { Toast.makeText(this, getString(R.string.os_predaja_nic), Toast.LENGTH_LONG).show(); return@poizvedi }
            val imena = ponudbe.map { p ->
                val kje = cas(p.polozajMs) + (if (p.trajanjeMs > 0) " / " + cas(p.trajanjeMs) else "")
                "${DatotekeActivity.lepoIme(p.naprava.ime)} · ${p.skladba.naslov}\n$kje · ${getString(if (p.igra) R.string.os_predaja_igra else R.string.os_predaja_nazadnje)}"
            }
            AlertDialog.Builder(this).setTitle(R.string.os_predaja_naslov)
                .setItems(imena.toTypedArray()) { _, i -> izberiPrevzem(ponudbe[i]) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun izberiPrevzem(p: Predaja.Ponudba) {
        if (!p.igra) { prevzemi(p, false); return }
        // Izvor ne ustavi sam: uporabnik izbere, ali tam tece naprej (druga oseba gleda) ali se ustavi.
        AlertDialog.Builder(this).setTitle(p.skladba.naslov)
            .setMessage(getString(R.string.os_predaja_vprasanje, DatotekeActivity.lepoIme(p.naprava.ime), cas(p.polozajMs)))
            .setPositiveButton(R.string.os_predaja_tukaj) { _, _ -> prevzemi(p, false) }
            .setNeutralButton(R.string.os_predaja_tukaj_ustavi) { _, _ -> prevzemi(p, true) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun prevzemi(p: Predaja.Ponudba, ustaviTam: Boolean) {
        val sk = p.skladba
        val link = LinkUpravitelj.pridobi(this)
        if (ustaviTam) link.ukaz(p.naprava.id, "play.stop", org.json.JSONObject(), 5_000, LinkOdjemalec.Odgovor { _, _ -> })
        predajaPolozaj = sk.id to p.polozajMs
        if (sk.video && p.trajanjeMs > 0) MediaNapredek.zapisi(this, sk, p.polozajMs, p.trajanjeMs, p.streznikNaprava.ifBlank { p.streznik?.naprava.orEmpty() })
        fun zacni(s: DatotekeActivity.Streznik?, skl: Jamendo.Skladba = sk) {
            GlasbaStoritev.predvajaj(this, listOf(skl), 0, s)
            nadaljujKoPripravljen(skl)
            if (skl.video) startActivity(Intent(this, PredvajanjeActivity::class.java))
        }
        when {
            p.streznik != null -> zacni(p.streznik)
            p.streznikNaprava.isNotBlank() && LinkUpravitelj.fizicnaNaprava(p.streznikNaprava) == LinkUpravitelj.fizicnaNaprava(Identiteta.id(this)) -> {
                // Datoteka te naprave, ki se vraca (druga naprava jo je igrala od tod): kar z diska, brez zetona.
                val uri = si.safeer.tv.link.DatotekeStreznik.uriZa(sk.id)
                if (uri == null) { Toast.makeText(this, getString(R.string.os_predaja_napaka), Toast.LENGTH_LONG).show(); return }
                val lokalna = sk.copy(id = "krajevno:$uri", zvok = uri, povezava = "", slika = if (sk.video) uri else sk.slika)
                predajaPolozaj = lokalna.id to p.polozajMs
                if (lokalna.video && p.trajanjeMs > 0) MediaNapredek.zapisi(this, lokalna, p.polozajMs, p.trajanjeMs)
                zacni(null, lokalna)
            }
            p.streznikNaprava.isNotBlank() -> {
                // Datoteka tretje naprave (racunalnik, telefon): svoj zeton dobimo tako kot Datoteke (files.list).
                stanje.text = getString(R.string.os_glasba_nalagam)
                link.ukaz(p.streznikNaprava, "files.list", org.json.JSONObject().put("folder", Predaja.mapaDatoteke(sk.id)), 10_000, LinkOdjemalec.Odgovor { izid, napaka ->
                    if (isFinishing) return@Odgovor
                    Predaja.log("files.list ${p.streznikNaprava}: ${izid?.toString()?.take(300)} napaka=$napaka")
                    stanje.text = opis(razdelek)
                    val s = izid?.optJSONObject("data")?.optJSONObject("server")?.let {
                        DatotekeActivity.Streznik(it.optString("base_url").trimEnd('/'), it.optString("fp"), it.optString("token"), p.streznikNaprava)
                    }
                    if (s == null) { Toast.makeText(this, getString(R.string.os_predaja_napaka), Toast.LENGTH_LONG).show(); return@Odgovor }
                    zacni(s)
                })
            }
            sk.id.startsWith("share:") || sk.id.startsWith("disk:") || sk.id.startsWith("media:") ->
                // Datoteka naprave brez streznika (stara zgodovina brez id-ja naprave): brez zetona je ni mogoce predvajati.
                Toast.makeText(this, getString(R.string.os_predaja_napaka), Toast.LENGTH_LONG).show()
            else -> predvajaj(listOf(sk), 0)
        }
    }

    private fun nadaljujKoPripravljen(sk: Jamendo.Skladba) {
        val predano = predajaPolozaj?.takeIf { it.first == sk.id }?.second?.also { predajaPolozaj = null }
        val od = predano ?: MediaNapredek.polozaj(this, sk)
        if (od <= 0) return
        // Lastnik 28. 10. 2026: uporabnik izbere, ali nadaljuje ali zacne od zacetka (zaslon predvajanja vprasa). Predvajanje
        // do izbire miruje. Predaja z druge naprave (»Nadaljuj tukaj«) in sprotni tok pomocnika ostaneta samodejna.
        if (predano == null && SprotnaPomoc.tokZa(sk) == null && OsPravila.ponudiNadaljevanje(od, 0)) {
            GlasbaStoritev.predvajalnik?.playWhenReady = false
            GlasbaStoritev.ponudbaNadaljevanja = (GlasbaStoritev.trenutna()?.id ?: sk.id) to od
            return
        }
        Toast.makeText(this, getString(R.string.os_nadaljujem_od, cas(od)), Toast.LENGTH_SHORT).show()
        // Predvajalnik zacne naravnost pri shranjenem mestu (GlasbaStoritev.nalozi); sprotni tok pomocnika ga ima ze v sebi.
        if (SprotnaPomoc.tokZa(sk) == null) GlasbaStoritev.zacetnoMesto = sk.id to od
        glavna.postDelayed({
            GlasbaStoritev.predvajalnik?.let { p ->
                // Sprotni tok pomocnika je ze zacel pri shranjenem mestu (SprotnaPomoc): skok bi ga le pokvaril.
                if (GlasbaStoritev.trenutna()?.let { SprotnaPomoc.tokZa(it) } != null) return@let
                // Ze zacel pri shranjenem mestu: skok ni potreben (ostane za primer, ko nalaganje mesta ni upostevalo).
                if (GlasbaStoritev.trenutna()?.id == sk.id && kotlin.math.abs(p.currentPosition - od) < 5_000) return@let
                if (p.duration <= 0 || od < p.duration - 5_000) p.seekTo(od)
            }
        }, 900)
    }

    /** Glasba in radio zacneta takoj (ves seznam v vrsto, naprej/nazaj preklaplja); video najprej razresimo. */
    /**
     * Lastnik 27. 10. 2026: album z Useneta kot vrsta skladb v obstojecem predvajalniku. Priprava (NZB, prvi segment vsake
     * skladbe) tece v ozadju; predvajanje zacne pri skladbi, ki ustreza iskanju (UsenetGlasbaPravila.zacetnaSkladba).
     */
    /** Dolg pritisk na album z Useneta: med priljubljene ali iz njih (samo ime in velikost izdaje). */
    private fun preklopiUsenetAlbum(naslov: String, velikost: Long) {
        val zdaj = UsenetAlbumi.preklopi(this, naslov, velikost)
        Toast.makeText(this, getString(if (zdaj) R.string.os_usenet_album_shranjen else R.string.os_usenet_album_odstranjen,
            UsenetGlasbaPravila.lepNaslov(naslov)), Toast.LENGTH_SHORT).show()
        osveziNaMestu()
    }

    /** Priljubljen album: Preimenuj (ime, ki si ga uporabnik lazje zapomni) ali Odstrani iz priljubljenih. */
    private fun urediUsenetAlbum(a: UsenetAlbumi.Album) {
        val izbire = arrayOf(getString(R.string.os_usenet_album_preimenuj), getString(R.string.os_usenet_album_odstrani))
        val okno = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(a.ime.ifBlank { UsenetGlasbaPravila.lepNaslov(a.naslov) })
            .setItems(izbire) { _, i -> if (i == 0) preimenujUsenetAlbum(a) else preklopiUsenetAlbum(a.naslov, a.velikost) }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .create()
        Kontroler.pokazi(okno); okno.show()
    }

    private fun preimenujUsenetAlbum(a: UsenetAlbumi.Album) {
        val polje = android.widget.EditText(this).apply {
            setSingleLine(); setText(a.ime); hint = getString(R.string.os_usenet_album_ime_namig)
            filters = arrayOf(android.text.InputFilter.LengthFilter(UsenetAlbumi.NAJDALJSE_IME))
        }
        val okno = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.os_usenet_album_preimenuj)
            .setMessage(UsenetGlasbaPravila.lepNaslov(a.naslov))
            .setView(polje)
            .setPositiveButton(android.R.string.ok) { _, _ -> UsenetAlbumi.preimenuj(this, a.naslov, polje.text.toString()); osveziNaMestu() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .create()
        Kontroler.pokazi(okno); okno.show()
    }

    /**
     * Priljubljeni album: povezave nimamo (kljuc!). Lastnik 28. 10. 2026 (kvota): najprej NZB na napravi (brez indeksatorja),
     * sicer ena poizvedba pri indeksatorju po imenu izdaje, nato predvajanje.
     */
    private fun predvajajShranjenUsenetAlbum(a: UsenetAlbumi.Album) {
        val app = applicationContext
        pokaziPripravljam(getString(R.string.os_usenet_album_pripravljam, UsenetGlasbaPravila.lepNaslov(a.naslov)))
        Thread({
            val shranjen = try { Usenet.albumIzShrambe(app, a.naslov, a.velikost) } catch (_: Exception) { null }
            if (shranjen != null && shranjen.skladbe.isNotEmpty()) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    skrijPripravljam()
                    predvajajSkladbeAlbuma(shranjen, a.naslov, "shramba" + a.naslov.hashCode(), "")
                }
                return@Thread
            }
            val zadetki = (try { Usenet.isciGlasbo(app, UsenetGlasbaPravila.lepNaslov(a.naslov)) } catch (_: Exception) { emptyList() })
                .filterNot { UsenetNzbShramba.jeNeuspel(app, it.naslov, it.velikost) }
            val i = UsenetGlasbaPravila.izberiShranjenega(zadetki.map { it.naslov to it.velikost }, a.naslov, a.velikost)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                skrijPripravljam()
                if (i < 0) { Toast.makeText(this, R.string.os_usenet_album_ni_vec, Toast.LENGTH_LONG).show(); return@runOnUiThread }
                predvajajUsenetAlbum(zadetki[i], "", zadetki)
            }
        }, "safeer-usenet-priljubljen").start()
    }

    /**
     * [drugi]: zadetki istega iskanja. Lastnik 28. 10. 2026 (kvota): pri »luknja« najvec NAJVEC_DRUGIH_IZDAJ drugi izdaji
     * istega albuma ([poskus] steje), druge napake brez ponovitve.
     */
    private fun predvajajUsenetAlbum(z: UsenetPravila.Zadetek, beseda: String, drugi: List<UsenetPravila.Zadetek> = emptyList(),
                                     poskus: Int = 0, prvi: String = z.naslov) {
        val app = applicationContext
        if (poskus == 0) Toast.makeText(this, getString(R.string.os_usenet_album_pripravljam, UsenetGlasbaPravila.lepNaslov(z.naslov)), Toast.LENGTH_SHORT).show()
        val id = UsenetPravila.idToka(z.povezava)
        Thread({
            val album = try { Usenet.albumLastni(app, id, z.naslov, z.velikost) } catch (_: Exception) { UsenetLastni.Album(emptyList(), "napaka") }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (album.skladbe.isEmpty()) {
                    android.util.Log.i("SafeerOsMedia", "glasba usenet: ni predvajal (${album.napaka}), poskus ${poskus + 1}")
                    if (UsenetLastniPravila.poskusiDrugoIzdajo(album.napaka) && poskus < UsenetLastniPravila.NAJVEC_DRUGIH_IZDAJ) {
                        val naslovi = drugi.map { it.naslov }
                        val naslednja = UsenetGlasbaPravila.drugeIzdaje(naslovi, prvi, drugi.size) { j ->
                            naslovi[j] == z.naslov || UsenetNzbShramba.jeNeuspel(app, drugi[j].naslov, drugi[j].velikost)
                        }.firstOrNull()
                        if (naslednja != null) { predvajajUsenetAlbum(drugi[naslednja], beseda, drugi, poskus + 1, prvi); return@runOnUiThread }
                    }
                    Toast.makeText(this, getString(if (UsenetLastniPravila.albumStisnjen(album.napaka)) R.string.os_usenet_album_stisnjen
                        else R.string.os_usenet_album_ni), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                predvajajSkladbeAlbuma(album, z.naslov, id, beseda)
            }
        }, "safeer-usenet-album").start()
    }

    private fun predvajajSkladbeAlbuma(album: UsenetLastni.Album, naslov: String, id: String, beseda: String) {
        // Lastnik 27. 10. 2026: naslov in izvajalec lepse (izvajalec ostane, v svojem polju) - pravilo za cel album.
        val lepe = UsenetGlasbaPravila.lepeSkladbe(album.skladbe.map { it.first }, UsenetGlasbaPravila.izvajalec(naslov))
        val seznam = album.skladbe.mapIndexed { i, (_, url) -> Jamendo.Skladba("usenet:$id:$i", lepe[i].first, lepe[i].second, "", url, "") }
        predvajaj(seznam, UsenetGlasbaPravila.zacetnaSkladba(album.skladbe.map { it.first }, beseda))
    }

    private fun predvajaj(seznam: List<Jamendo.Skladba>, i: Int,
                          /** Pregled B-S1: klik na kartico v Nedavno (odgledana epizoda serije -> naslednja). */
                          izNedavnih: Boolean = false) {
        // Pregled A1: nova izbira - prejsnje cakanje na debrid/Usenet ne odpre nicesar vec.
        prekiniDebrid()
        // Naloga 96: kanal seznama IPTV, ki je bil med nedavnimi shranjen brez toka (kot PeerTube), ima tok v povezavi.
        val sk0 = seznam.getOrNull(i) ?: return
        val sk = if (IptvSeznami.jeKanal(sk0) && sk0.zvok.isBlank() && sk0.povezava.isNotBlank()) sk0.copy(zvok = sk0.povezava) else sk0
        if (sk !== sk0) { predvajaj(seznam.mapIndexed { j, x -> if (j == i) sk else x }, i, izNedavnih); return }
        if (TvVZivo.jeStran(sk)) {
            // Izdajatelj prenos ponuja samo na svoji strani (RTV SLO): odpremo jo v brskalniku.
            try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(sk.povezava))) } catch (_: Exception) { }
            return
        }
        // Oddaja podkasta in dodani seznam se odpreta kot seznam; enoto Archive.org razresimo v datoteke.
        if (Podkasti.jeOddaja(sk)) { odpriSeznam(sk.naslov, sk.izvajalec) { Podkasti.epizode(sk.povezava).second }; return }
        if (sk.id.startsWith(MedijskiViri.PREDPONA_SEZNAMA)) { odpriSeznam(sk.naslov, sk.izvajalec) { MedijskiViri.osveziSeznam(this, sk.zvok) }; return }
        if (Arhiv.jeEnota(sk)) { razresiArhiv(sk); return }
        if (JavnaLast.jeEnota(sk)) { razresiJavnoLast(sk); return }
        if (TuneIn.jeEnota(sk)) { razresiTuneIn(sk); return }
        if (UvozSeznama.jeIskana(sk)) { predvajajVrstoPosnetkov(seznam, sk); return }
        if (SpletniVir.jeEnota(sk)) { razresiSplet(sk); return }
        if (Stremio.jeEnota(sk)) {
            // Naloga 140: nazadnje predvajana epizoda (»Nadaljuj«) se nadaljuje, ne odpre seznama sezon serije.
            val ep = SerijePravila.epizodaZaNadaljevanje(Stremio.razstavi(sk)?.second, sk.season, sk.episode, sk.naslov)
            if (ep != null) nadaljujEpizodo(sk, ep.first, ep.second, naprejCeOgledano = izNedavnih && !PreizkusPravila.jePreizkus(sk.id))
            else razresiStremio(sk)
            return
        }
        if (sk.id.startsWith(PREDPONA_PC_PRENOSA)) { meniPrenosa(sk); return }
        if (!sk.video || sk.zvok.isNotBlank()) {
            GlasbaStoritev.predvajaj(this, seznam, i)
            if (sk.video) nadaljujKoPripravljen(sk)
            if (sk.video) startActivity(Intent(this, PredvajanjeActivity::class.java))
            return
        }
        val priprava = zacniPripravo(sk) ?: return
        stanje.text = getString(R.string.os_glasba_nalagam)
        delavec.execute {
            val r = try { PeerTube.razresi(sk, MedijskiViri.streznikiPeerTube(this)) } catch (_: Exception) { null }
            glavna.post {
                if (isFinishing) { koncajPripravo(priprava); return@post }
                if (r == null) { pripravaNiUspela(priprava, sk); return@post }
                koncajPripravo(priprava)
                stanje.text = if (razdelek == ISKANJE && zadetki != null) opisZadetkov else opis(razdelek)
                GlasbaStoritev.predvajaj(this, listOf(r), 0)
                nadaljujKoPripravljen(sk)
                startActivity(Intent(this, PredvajanjeActivity::class.java))
            }
        }
    }

    // ------------------------------------------------------------------ dodatki Stremio

    /** Klik na dodatek Stremio v Moji viri: Video, pomaknjeno na prvo polico tega dodatka. */
    private var skociNaDodatek: String? = null

    /** Dodatek odpre razdelek, kamor sodi njegova vsebina: glasbeni Glasbo, radijski Radio, TV v zivo svojega, ostali Video. */
    private fun odpriRazdelekDodatka(naslov: String) {
        stanje.text = getString(R.string.os_glasba_nalagam)
        val odRazdelka = razdelek
        val odGlobine = podstrani.globina
        delavec.execute {
            // Zaseben dodatek se ne mesa med Filme in Serije: odpre se sam zase in samo od tukaj (ZasebniDodatki).
            if ((try { Stremio.zaseben(naslov) } catch (_: Exception) { null }) == true) { odpriSamDodatek(naslov, odRazdelka, odGlobine); return@execute }
            val razred = try { Stremio.glavniRazred(naslov) } catch (_: Exception) { Stremio.VIDEO }
            glavna.post {
                if (isFinishing || isDestroyed) return@post
                // Uporabnik je medtem odsel drugam: razdelka mu ne zamenjamo pod prsti.
                if (razdelek != odRazdelka || podstrani.globina != odGlobine) return@post
                val cilj = when (razred) { Stremio.TV -> TV_V_ZIVO; Stremio.GLASBA -> GLASBA; Stremio.RADIO -> RADIO; else -> VIDEO }
                if (cilj == VIDEO) skociNaDodatek = naslov
                fokusVVsebino = true; SEZNAMI.remove(cilj); izberi(cilj)
            }
        }
    }

    /** Katalogi enega dodatka kot mreza (zaseben dodatek): prve strani katalogov, naprej "Nalozi vec". Klic iz delovne niti. */
    private fun odpriSamDodatek(naslov: String, odRazdelka: Int, odGlobine: Int) {
        // Prenosi tega dodatka na napravah v Linku: vprasamo hkrati s katalogi in cakamo kratko (naprava, ki molci, police ne zadrzi).
        val prenosi = iskanjeDelavec.submit<List<Jamendo.Skladba>> { try { prenosiNaRacunalnikih(zasebniZa = naslov, cakajS = 4) } catch (_: Exception) { emptyList() } }
        val katalogi = try { Stremio.katalogiDodatka(naslov) } catch (_: Exception) { emptyList() }.take(12)
        val strani = katalogi.map { k -> iskanjeDelavec.submit<List<Jamendo.Skladba>> { try { Stremio.katalog(k) } catch (_: Exception) { emptyList() } } }
            .map { f -> try { f.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { emptyList() } }
        val preneseno = HashMap<Stremio.Katalog, Int>().apply { katalogi.zip(strani).forEach { (k, v) -> put(k, v.size) } }
        val vsi = SpletniVir.zdruziEnako(prepleti(strani)).map { it.first() }.toMutableList()
        val ime = Stremio.imeIzPredpomnilnika(naslov) ?: (android.net.Uri.parse(Stremio.osnova(naslov)).host ?: naslov)
        val polica = try { prenosi.get(6, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { emptyList() }
        glavna.post {
            if (isFinishing || isDestroyed) return@post
            // Uporabnik je medtem odsel drugam (katalogi pridejo po vec sekundah): strani mu ne zamenjamo pod prsti.
            if (razdelek != odRazdelka || podstrani.globina != odGlobine) return@post
            if (vsi.isEmpty() && polica.isEmpty()) { stanje.text = getString(R.string.os_glasba_prazno); return@post }
            odprtKatalogIz = razdelek
            odprtKatalog = OdprtKatalog(katalogi, vsi, preneseno, katalogi.zip(strani).any { (k, s) -> OsPravila.katalogImaVec(s.size, k.strani, STRAN_KATALOGA_MIN) }, naslov = ime, dodatek = naslov)
                .apply { this.prenosi = polica }
            narisiKatalog(ime)
            fokusNaPrvo()
        }
    }

    private fun naslovKataloga(k: Stremio.Katalog): String {
        val vrsta = if (k.tip == "series") "📺 " + getString(R.string.os_media_serije) else "🎬 " + getString(R.string.os_media_filmi)
        // Katalog z obvezno zvrstjo pokazemo s prvo moznostjo (kot Stremio): ime zvrsti v naslovu police.
        val zvrst = k.privzeti.firstOrNull { it.first == "genre" }?.second?.let { " · $it" }.orEmpty()
        return "$vrsta · ${k.ime}$zvrst · ${k.imeDodatka}"
    }

    private fun stremioNaslovi() = MedijskiViri.vsi(this).filter { it.jeStremio }.map { it.naslov }

    /**
     * [stremioNaslovi] za izris mreze: en izracun na izris (naloga 55: ~11 ms na klic - MedijskiViri.vsi bere shranjene
     * vire in spletne aplikacije - klican pa je bil v vsakem izrisu veckrat: strogaMreza, oknoMreze, preveriMrezo).
     * Velja [IZRIS_VELJA_MS]; sprememba virov ga izprazni takoj.
     */
    @Volatile private var nasloviIzrisa: Pair<Long, List<String>>? = null
    private fun nasloviZaIzris(): List<String> {
        val zdaj = android.os.SystemClock.uptimeMillis()
        nasloviIzrisa?.let { (cas, n) -> if (zdaj - cas in 0 until IZRIS_VELJA_MS) return n }
        return stremioNaslovi().also { nasloviIzrisa = zdaj to it }
    }

    /** Kakovost iz tokov, ki jih ze imamo (nikoli klic indeksatorja samo za oznako); spremembo pokaze znacka na mestu. */
    private fun zabeleziKakovost(id: String, tokovi: List<Stremio.Tok>, serija: Boolean = false) {
        val nova = KakovostZnane.zabelezi(applicationContext, id, tokovi.map { it.ime + " " + it.opis }, serija) ?: return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val napis = KakovostPravila.napisKartice(nova)
            fun obisci(v: View) {
                if (v is TextView && v.tag == OZNAKA_KAKOVOSTI + id) nastaviZnackoKakovosti(v, napis, barvaKakovosti(napis))
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) obisci(v.getChildAt(i))
            }
            obisci(window.decorView)
        }
    }

    private fun barvaKakovosti(napis: String): Int = if (napis.startsWith("⚠")) BARVA_OPOZORILA_KAKOVOSTI else 0xFF1CE6F2.toInt()

    private fun nastaviZnackoKakovosti(v: TextView, napis: String, barva: Int) {
        v.text = napis.uppercase(Locale.getDefault())
        v.setTextColor(barva)
        v.visibility = if (napis.isBlank()) View.GONE else View.VISIBLE
        v.background = GradientDrawable().apply { cornerRadius = dp(7).toFloat(); setColor(0xD9181D26.toInt()); setStroke(dp(1), barva) }
    }

    /**
     * Tokovi iz vseh dodatkov hkrati (vsak dodatek svoja nit, skupaj najvec 12 s): cakanje je toliko, kot traja
     * najpocasnejsi dodatek, ne vsota vseh (prej zaporedno).
     */
    private fun tokoviVzporedno(tip0: String, id: String, rokMs: Long = OsPravila.rokTokovMs(false),
                                /** Naloga 130: se en id iste vsebine (epizoda po IMDb), vprasan hkrati pri vseh dodatkih. */
                                dodatniId: String? = null): List<Stremio.Tok> {
        // Naloga 77: vnos tipa »music« z id-jem IMDb (koncert) vprasamo kot »movie«, ce noben dodatek ne da tokov za »music«.
        val tip = Stremio.tipToka(stremioNaslovi(), tip0, id)
        if (tip != tip0) android.util.Log.i("SafeerOsMedia", "tokovi: tip $tip0 -> $tip (noben dodatek ne da tokov za $tip0)")
        val idji = listOfNotNull(id, dodatniId)
        // Pregled B1: vsak dodatek svoja nit, placljivo pa se odloci enkrat po vseh (spodaj) - ne po zadnjem odgovoru.
        val dodatki = stremioNaslovi().flatMap { n -> idji.map { i -> i to iskanjeDelavec.submit<Stremio.OdgovorTokov> {
            try { Stremio.tokoviZOdzivom(listOf(n), tip, i) } catch (_: Exception) { Stremio.OdgovorTokov(emptyList(), 0, false) } } } }
        // Naloga 141: Usenet pri uporabnikovem indeksatorju (po IMDb), hkrati z dodatki; samo film in serija.
        val usenet = if (tip !in setOf("movie", "series") || !Usenet.jeNaVoljo(applicationContext)) null
            else idji.firstOrNull { UsenetPravila.iscemo(it) }?.let { i -> iskanjeDelavec.submit<List<Stremio.Tok>> { Usenet.isci(applicationContext, i) } }
        fun tokoviDodatka(f: java.util.concurrent.Future<Stremio.OdgovorTokov>): List<Stremio.Tok> = try { f.get().tokovi } catch (_: Exception) { emptyList() }
        val zacetek = android.os.SystemClock.uptimeMillis()
        var prviTok = 0L
        // Nic cakanja: ko ima prvi dodatek predvajljiv tok, pocasnim damo se 1,5 s (boljsa izbira), ne vseh 12 s.
        // Naloga 130: z aktivno debrid storitvijo steje tudi torrent in pocakamo 3 s (Torrentio odgovori pocasneje).
        val zDebridom = Debrid.aktivna(applicationContext) != null
        val cakaj = DebridPravila.cakajPoPrvemMs(zDebridom)
        // Pregled A8: z Usenetom pocakamo na njegovo iskanje (do njegovega roka) - prej je konec 3 s po prvem toku Usenet
        // pogosto izpustil in »Usenet prvi« ni bil zanesljiv.
        val rok = if (usenet != null) maxOf(rokMs, UsenetPravila.ISKANJE_CAKAJ_MS) else rokMs
        while (true) {
            val zdaj = android.os.SystemClock.uptimeMillis()
            val usenetKoncan = usenet == null || usenet.isDone
            if ((usenetKoncan && dodatki.all { it.second.isDone }) || zdaj - zacetek > rok) break
            if (prviTok == 0L && dodatki.any { (_, f) -> f.isDone && tokoviDodatka(f).any { DebridPravila.stejeKotPrvi(it.vrsta, zDebridom) } }) prviTok = zdaj
            if (prviTok != 0L && zdaj - prviTok > cakaj && usenetKoncan) break
            try { Thread.sleep(40) } catch (_: InterruptedException) { break }
        }
        val usenetTokovi = usenet?.takeIf { it.isDone }?.let { f -> try { f.get() } catch (_: Exception) { null } }.orEmpty()
        val tokovi = (dodatki.filter { it.second.isDone }.flatMap { tokoviDodatka(it.second) } + usenetTokovi).distinctBy { it.url }
        // Pregled B1: »placljivo« enkrat po vseh dodatkih; ce kateri ni odgovoril (v roku ali sploh), novega zapisa ni.
        val predvajljivih = tokovi.count { Stremio.jePredvajljiv(it) }
        for (i in idji) {
            val zaId = dodatki.filter { it.first == i }.map { it.second }
            val odgovori = zaId.filter { it.isDone }.mapNotNull { f -> try { f.get() } catch (_: Exception) { null } }
            Stremio.zabeleziPlacljivoPoVseh(tip, i, odgovori.sumOf { it.placilnih }, predvajljivih,
                odgovori.size == zaId.size && odgovori.all { it.odgovorili })
        }
        // Dodatek, ki v roku ni odgovoril, bi tok morda imel: brez tokov je to izpad, ne "ni na voljo".
        if (tokovi.isEmpty() && (dodatki.any { !it.second.isDone } || usenet?.isDone == false)) Stremio.zadnjiIzpad.set(System.currentTimeMillis())
        return tokovi
    }

    /** Kaj ta naprava predvaja (zaslon, dekodirniki slike in zvoka) - za izbiro najboljsega toka brez vprasanj. */
    private val zmoznostiNaprave: TokIzbira.Zmoznosti
        get() {
            // Zvocni izhod se med rabo spreminja (zvocnik na HDMI, slusalke): preberemo ga ob vsaki izbiri toka. Kar
            // izhod sprejme kot nedotaknjen tok (npr. TrueHD ali DTS prek sprejemnika), ne potrebuje dekodirnika.
            val predaja = ZvokIzhod.predaja(this)
            val o = zmoznostiOsnovne
            return o.copy(ac3 = o.ac3 || "ac3" in predaja, eac3 = o.eac3 || "eac3" in predaja || "atmos" in predaja,
                dts = o.dts || "dts" in predaja, truehd = o.truehd || "truehd" in predaja, atmos = o.atmos || "atmos" in predaja)
        }

    /** Zaslon in dekodirniki se med rabo ne spreminjajo. */
    private val zmoznostiOsnovne: TokIzbira.Zmoznosti by lazy {
        val kodeki = try {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder }.flatMap { it.supportedTypes.toList() }.map { it.lowercase(Locale.ROOT) }.toSet()
        } catch (_: Exception) { emptySet() }
        @Suppress("DEPRECATION") val zaslon = windowManager.defaultDisplay
        val stranica = try { zaslon.supportedModes.maxOfOrNull { minOf(it.physicalWidth, it.physicalHeight) } } catch (_: Exception) { null }
            ?: minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        @Suppress("DEPRECATION") val hdrTipi = try { zaslon.hdrCapabilities?.supportedHdrTypes?.toSet() } catch (_: Exception) { null } ?: emptySet()
        // Naloga 142: zmore kateri dekoder HEVC profil Main10 (10-bitni)? SM-X210 ga nima (c2.qti.hevc.decoder:
        // DECODING_FAILED pri »10bit HEVC«). Brez podatka velja kot doslej (da).
        val hevc10 = try {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals("video/hevc", ignoreCase = true) } }
                .any { info -> info.getCapabilitiesForType("video/hevc").profileLevels
                    .any { it.profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 } }
        } catch (_: Exception) { true }
        android.util.Log.i("SafeerOsMedia", "zmoznosti: HEVC Main10=$hevc10")
        // Televizor zvok Dolby odda sam ali ga preda zvocniku (HDMI); na telefonu odloca dekodirnik.
        val tv = jeTv()
        TokIzbira.Zmoznosti(hevc10 = hevc10,
            visina = when { stranica >= 2000 -> 2160; stranica >= 1300 -> 1440; stranica >= 1000 -> 1080; else -> 720 },
            hevc = "video/hevc" in kodeki, av1 = "video/av01" in kodeki, hdr = hdrTipi.isNotEmpty(),
            dolbyVision = "video/dolby-vision" in kodeki && android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTipi,
            eac3 = tv || "audio/eac3" in kodeki || "audio/eac3-joc" in kodeki, ac3 = tv || "audio/ac3" in kodeki,
            dts = kodeki.any { it.startsWith("audio/vnd.dts") }, truehd = "audio/true-hd" in kodeki,
            atmos = "audio/eac3-joc" in kodeki)
    }

    /**
     * [razlicice]: ista vsebina iz drugih dodatkov (drug id). Tokove vprasamo za vse hkrati in izberemo najboljsega
     * izmed vseh - uporabnik vidi eno kartico in dobi najboljsi tok, ne glede na to, kateri dodatek ga ima (lastnik, 2. 10. 2026).
     */
    private fun razresiStremio(sk: Jamendo.Skladba, rocno: Boolean = false, razlicice: List<Jamendo.Skladba> = emptyList(),
                               /** Naloga 91a: razlicica seznama IPTV, ki igra, ce dodatki kanala v zivo ne dajo predvajljivega toka. */
                               rezerva: Jamendo.Skladba? = null) {
        val (_, tip, id) = Stremio.razstavi(sk) ?: return
        val priprava = zacniPripravo(sk) ?: return
        // Naloga 74: klik na kanal ima prednost pred preverjanjem mreze v ozadju ([preveriKanal] pocaka).
        if (Stremio.jeVZivo(sk)) prednostKlikaDo = android.os.SystemClock.uptimeMillis() + PREDNOST_KLIKA_MS
        delavec.execute {
            // Naloga 63 (lastnik: »preverimo, ce kliknemo na plakat in ce ima sezone in epizode«): film iz dodatka je morda
            // serija, ki jo je dodatek navedel kot film. Metapodatke (en odgovor, 1 h v predpomnilniku; to je uporabnikov
            // klik, ne preverjanje v ozadju) vprasamo hkrati s tokovi; z epizodami po sezonah se odpre kot serija in to si
            // zapomnimo - iz mreze Filmi izgine. Znana serija tokov filma ne caka.
            val znanaSerija = tip == "movie" && (VrsteNaslovov.vrsta(sk) == VrsteNaslovov.SERIJA || SpletniVir.vrstaVsebine(sk) == SpletniVir.SERIJA)
            val meta = if (tip == "movie" && Stremio.dajeMeta(sk))
                iskanjeDelavec.submit<Pair<String?, List<Stremio.Epizoda>>?> { try { Stremio.vrstaInPosnetki(sk) } catch (_: Exception) { null } } else null
            fun odpriKotSerijo(cakajS: Long): Boolean {
                val r = try { meta?.get(cakajS, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { null } ?: return false
                val ep = r.second.filter { it.sezona >= 1 && it.epizoda >= 1 }
                if (r.first != VrsteNaslovov.SERIJA || ep.isEmpty()) return false
                VrsteNaslovov.zapomni(sk, VrsteNaslovov.SERIJA)
                val serija = Stremio.kotSerija(sk)
                glavna.post {
                    koncajPripravo(priprava)
                    if (isFinishing || isDestroyed) return@post
                    izberiSezono(serija, ep, rocno)
                    if (odprtKatalog != null) narociUmiri()      // mreza Filmi: kartica tiho izpade (drugaVrsta)
                }
                return true
            }
            if (znanaSerija && odpriKotSerijo(15)) return@execute
            val razred = Stremio.razred(tip)
            // Album, seznam, podkast ali kanal z videi ima posnetke kot serija epizode; film, TV kanal in postaja imajo tok naravnost.
            val posnetkiDodatka = if (tip == "series" || razred == Stremio.GLASBA || razred == Stremio.VIDEO)
                (try { Stremio.epizode(sk) } catch (_: Exception) { emptyList() }) else emptyList()
            // Naloga 130: dodatek kartice epizod ni dal - poiscemo jih po id-ju IMDb pri drugih uporabnikovih dodatkih.
            val posnetki = if (tip != "series" || posnetkiDodatka.isNotEmpty()) posnetkiDodatka
                else Stremio.imdbZa(sk)?.let { imdb -> try { Stremio.epizodePoImdb(stremioNaslovi(), imdb, Stremio.razstavi(sk)?.first.orEmpty()) } catch (_: Exception) { emptyList() } }
                    .orEmpty().also { if (it.isNotEmpty()) android.util.Log.i("SafeerOsMedia", "serija: epizode iz drugega dodatka po IMDb (${it.size})") }
            if (tip == "series" || posnetki.size > 1) {
                glavna.post {
                    koncajPripravo(priprava)
                    if (isFinishing || isDestroyed) return@post
                    if (posnetki.isEmpty()) { Toast.makeText(this, R.string.os_stremio_ni_epizod, Toast.LENGTH_LONG).show(); return@post }
                    izberiSezono(sk, posnetki, rocno)
                }
            } else if (posnetki.size == 1) {
                val e = posnetki.first()
                val t = e.tokovi.ifEmpty { try { tokoviVzporedno(tip, e.id) } catch (_: Exception) { emptyList() } }
                val pp = predpomnjeniZa(t, sk, rocno)
                glavna.post { koncajPripravo(priprava); if (!isFinishing && !isDestroyed) izberiTok(sk, sk.naslov, t, rocno, pp) }
            } else {
                val enote = (listOf(tip to id) + razlicice.filter { Stremio.jeEnota(it) }.mapNotNull { r -> Stremio.razstavi(r)?.let { it.second to it.third } }).distinct().take(5)
                // Naloga 88: kanal v zivo - najvec [OsPravila.rokTokovMs] (7 s) za vse dodatke, da je odgovor uporabniku v 8 s.
                val vZivo = razred == Stremio.TV
                val rokT = OsPravila.rokTokovMs(vZivo)
                val konecT = android.os.SystemClock.uptimeMillis() + rokT + 500
                val t = if (enote.size == 1) (try { tokoviVzporedno(tip, id, rokT) } catch (_: Exception) { emptyList() })
                    else enote.map { (t1, i1) -> iskanjeDelavec.submit<List<Stremio.Tok>> { try { tokoviVzporedno(t1, i1, rokT) } catch (_: Exception) { emptyList() } } }
                        .flatMap { f -> try { f.get((konecT - android.os.SystemClock.uptimeMillis()).coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) { emptyList() } }.distinctBy { it.url }
                // Metapodatki so medtem navadno ze prisli: serija z epizodami se odpre kot serija (ne kot film).
                if (!vZivo && odpriKotSerijo(if (t.isEmpty()) 5 else 2)) return@execute
                if (vZivo) android.util.Log.i("SafeerOsMedia", "kanal dodatka: enot=${enote.size}, tokov=${t.size}, predvajljivih=${t.count { Stremio.jePredvajljiv(it) }}, " +
                    "vrste=${t.map { it.vrsta }.distinct()}, dodatek ni odgovoril=${Stremio.dodatekNiOdgovoril()}, rezerva=${rezerva != null}, rocno=$rocno")
                if (vZivo && !rocno && rezerva != null && t.none { Stremio.jePredvajljiv(it) }) {
                    android.util.Log.i("SafeerOsMedia", "kanal dodatka: ni toka - predvajam rezervo iz seznama IPTV")
                    glavna.post { koncajPripravo(priprava); if (!isFinishing && !isDestroyed) predvajaj(listOf(rezerva), 0) }
                    return@execute
                }
                val pp = if (vZivo) Pripravljeno() else predpomnjeniZa(t, sk, rocno)
                glavna.post { koncajPripravo(priprava); if (!isFinishing && !isDestroyed) izberiTok(sk, sk.naslov, t, rocno, pp) }
            }
        }
    }

    /**
     * Naloga 130/136: kateri torrenti so pri debrid storitvi ze pripravljeni (oznaka ⚡, samodejna izbira). Z delovne niti;
     * TorBox en klic do 4 s, Real-Debrid in AllDebrid iz zapomnjenih (brez klica); brez aktivne storitve prazno.
     */
    private fun predpomnjeniZa(tokovi: List<Stremio.Tok>, sk: Jamendo.Skladba? = null, rocno: Boolean = true): Pripravljeno {
        // Lastnik 25. 10. 2026 07:25: Usenet, ki ga ima TorBox ze pripravljenega, je prvi - preverimo ga HKRATI s ⚡ (samo pri
        // samodejni izbiri; prve izdaje vzporedno, najvec 6 s), da se zacetek ne podaljsa.
        val usenet = if (!rocno && sk != null && tokovi.any { it.vrsta == UsenetPravila.VRSTA } && Usenet.jeNaVoljo(applicationContext))
            zacniUsenetPripravljen(usenetKandidati(sk, tokovi)) else null
        val hashi = tokovi.filter { it.vrsta == "torrent" }.mapNotNull { TorBoxPravila.hashIzMagneta(it.url) }
        val pp = if (hashi.isEmpty()) emptySet() else Debrid.predpomnjeni(applicationContext, hashi)
        return Pripravljeno(pp, usenet?.invoke())
    }

    /** »Predvaja prek …«: lasten ponudnik Usenet ima svoj napis (vodja 27. 10. 2026), sicer ime debrid storitve. */
    private fun napisPredvajaPrek(ponudnik: String?): String =
        if (UsenetLastniPravila.jeLastni(ponudnik)) getString(R.string.os_usenet_predvaja_lastni)
        else getString(R.string.os_debrid_predvaja_prek, DebridPravila.ime(ponudnik.orEmpty()))

    /** Kaj je takoj predvajljivo za izbiro toka: ⚡ torrenti pri debridu in (samodejno) Usenet, ki ga ima TorBox pripravljenega. */
    class Pripravljeno(val predpomnjeni: Set<String> = emptySet(), val usenet: Triple<Stremio.Tok, String, String>? = null)

    /** Lastne niti za vzporedno preverbo »Usenet pripravljen« (blokirajoci klici indeksatorja in TorBoxa). */
    private val usenetDelavec = Executors.newCachedThreadPool { r -> Thread(r, "safeer-usenet").apply { isDaemon = true } }

    /**
     * Prvih [WebtorPravila.USENET_VZPOREDNO] izdaj Usenet hkrati, samo ce jih ima TorBox ze pripravljene (add_only_if_cached -
     * nic se ne prenasa). Vrne cakalnik: najboljsa pripravljena izdaja in njena povezava ([WebtorPravila.izberiUsenet]) ali
     * null; skupni rok [WebtorPravila.USENET_ROK_MS] od zacetka (preverba ⚡ tece medtem).
     */
    private fun zacniUsenetPripravljen(kandidati: List<Stremio.Tok>): (() -> Triple<Stremio.Tok, String, String>?)? {
        val app = applicationContext
        // Lastnik 27. 10. 2026: z lastnim ponudnikom NNTP je vsaka izdaja takoj predvajljiva (pretakamo neposredno) - Usenet
        // se izbere sam, brez okna »Izberi vir«; zaporedno (en film naenkrat, en bazen racuna), najboljsa nato naslednja.
        if (UsenetLastni.vUporabi(app)) {
            val zapored = kandidati.take(UsenetLastniPravila.NAJVEC_POSKUSOV)
            if (zapored.isEmpty()) return null
            // Lastnik 27. 10. 2026: priprava lastnega pogona traja nekaj sekund - uporabnik vidi, da se nekaj dogaja.
            glavna.post { if (!isFinishing && !isDestroyed && pripraveVTeKu.isNotEmpty()) pokaziPripravljam(getString(R.string.os_usenet_pripravljam)) }
            return {
                var izbran: Triple<Stremio.Tok, String, String>? = null
                for (t in zapored) {
                    if (izbran != null || Thread.currentThread().isInterrupted) break
                    val izid = try { Usenet.tok(app, t.url, t.opis.substringBefore('\n'), t.datoteka, false, {}, lastni = true) } catch (_: Exception) { null }
                    // Vodja 27. 10. 2026: ponudnik gre zraven (prej samo tok in naslov - obvestilo je reklo TorBox).
                    izid?.url?.let { izbran = Triple(t, it, izid.ponudnik.ifBlank { UsenetLastniPravila.PONUDNIK }) }
                }
                android.util.Log.i("SafeerOsMedia", "tokovi: Usenet lasten pogon: ${if (izbran != null) "da" else "ne"}")
                izbran
            }
        }
        val prvi = kandidati.take(WebtorPravila.USENET_VZPOREDNO)
        if (prvi.isEmpty()) return null
        val zacetek = android.os.SystemClock.elapsedRealtime()
        val niti = try {
            prvi.map { t -> usenetDelavec.submit<TorBox.Izid> { Usenet.tok(app, t.url, t.opis.substringBefore('\n'), t.datoteka, true, {}, lastni = false) } }
        } catch (_: java.util.concurrent.RejectedExecutionException) { return null }
        return {
            var izbran: Triple<Stremio.Tok, String, String>? = null
            while (true) {
                val potekel = android.os.SystemClock.elapsedRealtime() - zacetek >= WebtorPravila.USENET_ROK_MS
                val izidi = niti.map { f -> if (!f.isDone) null else (try { f.get().url != null } catch (_: Exception) { false }) }
                val i = WebtorPravila.izberiUsenet(izidi, potekel)
                if (i >= 0) { niti[i].get().url?.let { izbran = Triple(prvi[i], it, DebridPravila.TORBOX) }; break }
                if (i == WebtorPravila.NOBENA) break
                try { Thread.sleep(100) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
            niti.forEach { if (!it.isDone) it.cancel(true) }
            android.util.Log.i("SafeerOsMedia", "tokovi: Usenet pripravljen (vzporedno ${prvi.size}, " +
                "${android.os.SystemClock.elapsedRealtime() - zacetek} ms): ${if (izbran != null) "da" else "ne"}")
            izbran
        }
    }

    /** Izdaje Usenet v vrstnem redu samodejne izbire (kakovost za to napravo, hitrost povezave, [UsenetPravila.zaSamodejno]). */
    private fun usenetKandidati(sk: Jamendo.Skladba, tokovi: List<Stremio.Tok>): List<Stremio.Tok> {
        val opis = { t: Stremio.Tok -> t.ime + " " + t.opis }
        val zelen = if (sk.video) TokIzbira.zeleniJezik(sk.language) else ""
        val hitrost = HitrostPovezave.hitrost(this)
        val minut = KakovostPravila.minut(sk.season > 0 || sk.episode > 0)
        val urejeni = TokIzbira.uredi(tokovi.filter { it.vrsta == UsenetPravila.VRSTA }, opis, zmoznostiNaprave, zelenJezik = zelen, izvirni = sk.language)
        val poHitrosti = KakovostPravila.urediZaHitrost(urejeni.map { TokIzbira.opisi(opis(it)).gb }, minut, hitrost).map { urejeni[it] }
        return UsenetPravila.zaSamodejno(poHitrosti.map { TokIzbira.opisi(opis(it)).gb }).map { poHitrosti[it] }
    }

    /**
     * Naloga 140: epizoda [sezona]/[epizoda] serije iz zapisa [sk] (nazadnje predvajano) - tokove vprasamo znova (povezava
     * debrid velja le nekaj ur), naslov ostane isti, zato predvajalnik nadaljuje pri shranjenem mestu (MediaNapredek).
     * Epizode serija ne navaja vec: seznam sezon kot prej.
     */
    private fun nadaljujEpizodo(sk: Jamendo.Skladba, sezona: Int, epizoda: Int,
                                /** Pregled B-S1: iz Nedavno - epizoda brez shranjenega mesta je odgledana, odpre se naslednja. */
                                naprejCeOgledano: Boolean = false) {
        // Oznaka preizkusa (naloga 135) ostane - sicer bi preizkusni ogled sel v zgodovino.
        val serija = sk.copy(id = sk.id.substringBefore('#') + (if (PreizkusPravila.jePreizkus(sk.id)) PreizkusPravila.OZNAKA else ""),
            season = 0, episode = 0)
        val tip = Stremio.razstavi(serija)?.second ?: return
        val priprava = zacniPripravo(sk) ?: return
        delavec.execute {
            val vse = try { Stremio.epizode(serija) } catch (_: Exception) { emptyList() }
            val zadnja = vse.firstOrNull { it.sezona == sezona && it.epizoda == epizoda }
            // Pregled B-S1: odgledana epizoda (>= 95 % - MediaNapredek mesta takrat ne hrani vec) se ne zacne znova od zacetka:
            // odpre se naslednja; po zadnji epizodi serije seznam epizod.
            // Pregled 3: samo izrecna oznaka »odgledano« (MediaNapredek, >= 95 %) - manjkajoc zapis mesta ne pomeni nic.
            val odgledana = zadnja != null && naprejCeOgledano && MediaNapredek.odgledano(this, sk)
            val e = if (!odgledana) zadnja else OsPravila.naslednjaEpizoda(vse.map { it.sezona to it.epizoda }, sezona, epizoda)
                ?.let { (s, k) -> vse.firstOrNull { it.sezona == s && it.epizoda == k } }
            if (e == null) {
                glavna.post { koncajPripravo(priprava); if (!isFinishing && !isDestroyed) razresiStremio(serija) }
                return@execute
            }
            val naslovEp = if (!odgledana) sk.naslov else OsPravila.naslovBrezEpizode(sk.naslov) + " · S${e.sezona}E${e.epizoda}" +
                (if (e.ime.isNotBlank()) " · " + e.ime else "")
            if (odgledana) android.util.Log.i("SafeerOsMedia", "nadaljuj: S${sezona}E$epizoda odgledana - naslednja S${e.sezona}E${e.epizoda}")
            val dodatni = SerijePravila.idEpizodePoImdb(Stremio.imdbZa(serija), e.id, e.sezona, e.epizoda)
            val t = e.tokovi.ifEmpty { try { tokoviVzporedno(tip, e.id, dodatniId = dodatni) } catch (_: Exception) { emptyList() } }
            val pp = predpomnjeniZa(t, serija.copy(season = e.sezona, episode = e.epizoda), false)
            glavna.post {
                koncajPripravo(priprava)
                // Brez toka: samo obvestilo - kartica serije zaradi ene epizode ne izgine.
                if (!isFinishing && !isDestroyed) izberiTok(serija.copy(season = e.sezona, episode = e.epizoda), naslovEp, t, false, pp) {
                    Toast.makeText(this, getString(R.string.os_media_ni_na_voljo, naslovEp), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun izberiSezono(sk: Jamendo.Skladba, ep: List<Stremio.Epizoda>, rocno: Boolean = false) {
        val sezone = ep.map { it.sezona }.distinct()
        if (sezone.size == 1) { izberiEpizodo(sk, ep, rocno); return }
        val imena = sezone.map { z -> val n = ep.count { it.sezona == z }
            getString(R.string.os_stremio_sezona, z) + " · " + resources.getQuantityString(R.plurals.os_stremio_epizod, n, n) }
        AlertDialog.Builder(this).setTitle(sk.naslov).setItems(imena.toTypedArray()) { _, k -> izberiEpizodo(sk, ep.filter { it.sezona == sezone[k] }, rocno, edinaSezona = false) }.show()
    }

    private fun izberiEpizodo(sk: Jamendo.Skladba, vseEpizode: List<Stremio.Epizoda>, rocno: Boolean = false,
                              /** Serija ima samo to sezono: ce ni nobene epizode, ni serije. */
                              edinaSezona: Boolean = true) {
        val tip = Stremio.razstavi(sk)?.second ?: "series"
        // Epizoda serije: S1E2 · ime; skladba albuma ali video kanala brez stevilk: zaporedna stevilka in ime.
        val imena = vseEpizode.mapIndexed { i, e ->
            e.id to (if (e.sezona > 0 || e.epizoda > 0) "S${e.sezona}E${e.epizoda}" + (if (e.ime.isNotBlank()) " · " + e.ime else "")
            else "${i + 1}. " + e.ime.ifBlank { sk.naslov })
        }.toMap()
        fun ime(e: Stremio.Epizoda) = imena[e.id].orEmpty()
        // Epizod, za katere ze vemo, da jih noben dodatek nima, ne kazemo; ce ni nobene, serija izgine s seznama.
        // Naloga 132: epizoda izgine samo, ce je ni pri id-ju dodatka IN je ni po IMDb (tt...:S:E) - Torrentio pozna samo IMDb.
        val imdbSerije = Stremio.imdbZa(sk)
        fun imdbEpizode(e: Stremio.Epizoda) = SerijePravila.idEpizodePoImdb(imdbSerije, e.id, e.sezona, e.epizoda)
        val ep = vseEpizode.filter {
            val alt = imdbEpizode(it)
            it.tokovi.isNotEmpty() || !SerijePravila.skrijEpizodo(Razpolozljivost.stanje(this, Razpolozljivost.kljuc(tip, it.id)),
                alt?.let { a -> Razpolozljivost.stanje(this, Razpolozljivost.kljuc(tip, a)) }, alt != null)
        }.toMutableList()
        fun nicNiNaVoljo() {
            Toast.makeText(this, getString(R.string.os_media_ni_na_voljo, sk.naslov), Toast.LENGTH_SHORT).show()
            if (edinaSezona && !imaZnanoEpizodo(sk)) oznaciNiNaVoljo(sk)
        }
        if (ep.isEmpty()) { nicNiNaVoljo(); return }
        val vrstice = android.widget.ArrayAdapter(this, android.R.layout.select_dialog_item, ep.map { ime(it) }.toMutableList())
        var okno: AlertDialog? = null
        okno = AlertDialog.Builder(this).setTitle(sk.naslov).setAdapter(vrstice) { _, k ->
            val seznam = ep.toList()
            val e = seznam.getOrNull(k) ?: return@setAdapter
            Toast.makeText(this, getString(R.string.os_media_pripravljam, ime(e)), Toast.LENGTH_SHORT).show()
            delavec.execute {
                // Naloga 130: poleg id-ja dodatka se id po IMDb (tt...:S:E) - dodatki s torrenti poznajo samo tega.
                val imdb = Stremio.imdbZa(sk)
                fun tokoviZa(x: Stremio.Epizoda) = x.tokovi.ifEmpty {
                    try { tokoviVzporedno(tip, x.id, dodatniId = SerijePravila.idEpizodePoImdb(imdb, x.id, x.sezona, x.epizoda)) } catch (_: Exception) { emptyList() }
                }
                if (!sk.video && !rocno) {
                    // Zvok: tokove izbrane in naslednjih skladb vprasamo hkrati; izbrana se zacne, cim je znana,
                    // naslednjim damo se najvec 1,2 s (kar do takrat pride, gre v vrsto - album igra naprej sam).
                    val naprej = seznam.drop(k).take(40)
                    val niti = naprej.map { x -> iskanjeDelavec.submit<List<Stremio.Tok>> { tokoviZa(x) } }
                    val opis = { t: Stremio.Tok -> t.ime + " " + t.opis }
                    fun najboljsi(t: List<Stremio.Tok>) = TokIzbira.uredi(t.filter { it.vrsta == "url" }, opis, zmoznostiNaprave, { it.url }).firstOrNull()
                    val prvi = try { niti.first().get(15, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { emptyList() }
                    val prviTok = najboljsi(prvi)
                    if (prviTok != null) {
                        val rok = android.os.SystemClock.uptimeMillis() + 1_200
                        val vrsta = ArrayList<Jamendo.Skladba>()
                        for ((j, f) in niti.withIndex()) {
                            val tok = if (j == 0) prviTok else najboljsi(try {
                                f.get((rok - android.os.SystemClock.uptimeMillis()).coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS)
                            } catch (_: Exception) { emptyList() }) ?: break
                            SpletniVir.zapomniGlaveToka(tok.url, tok.glave)
                            vrsta += sk.copy(id = sk.id + "#" + tok.url.hashCode(), naslov = "${sk.naslov} · ${ime(naprej[j])}", zvok = tok.url, povezava = tok.url, video = false)
                        }
                        glavna.post { if (!isFinishing && !isDestroyed) GlasbaStoritev.predvajaj(this, vrsta, 0) }
                        return@execute
                    }
                    glavna.post { if (!isFinishing && !isDestroyed) izberiTok(sk, "${sk.naslov} · ${ime(e)}", prvi, rocno) { epizodeNi(sk, tip, e, vseEpizode, rocno, edinaSezona) } }
                    return@execute
                }
                val t = tokoviZa(e)
                val pp = predpomnjeniZa(t, sk.copy(season = e.sezona, episode = e.epizoda), rocno)
                glavna.post {
                    if (!isFinishing && !isDestroyed) izberiTok(sk.copy(season = e.sezona, episode = e.epizoda), "${sk.naslov} · ${ime(e)}", t, rocno, pp) { epizodeNi(sk, tip, e, vseEpizode, rocno, edinaSezona) }
                }
            }
        }.show()
        // V ozadju preverimo, katere epizode dodatki res imajo: cesar ni, s seznama sproti izgine (in tok izbrane je ze v predpomnilniku).
        preveriEpizode(tip, ep.toList(), { imdbEpizode(it) }) { e ->
            val d = okno
            if (isFinishing || d == null || !d.isShowing) return@preveriEpizode
            val i = ep.indexOfFirst { it.id == e.id }
            if (i < 0) return@preveriEpizode
            ep.removeAt(i)
            vrstice.remove(vrstice.getItem(i))
            if (ep.isEmpty()) { d.dismiss(); nicNiNaVoljo() }
        }
    }

    /** Epizode ni v nobenem dodatku: zapomnimo si in pokazemo seznam brez nje (ostale so morda na voljo). */
    private fun epizodeNi(sk: Jamendo.Skladba, tip: String, e: Stremio.Epizoda, vse: List<Stremio.Epizoda>, rocno: Boolean, edinaSezona: Boolean) {
        skrijInPotrdi(Razpolozljivost.kljuc(tip, e.id)) { naslovi, torrent -> Stremio.razpolozljivo(naslovi, tip, e.id, torrent) }
        izberiEpizodo(sk, vse, rocno, edinaSezona)
    }

    // ------------------------------------------------------------------ razpolozljivost: prikazemo samo, kar se da predvajati

    /**
     * Kateri torrent ta naprava ta hip zmore predvajati: vsakega (Long.MAX_VALUE), ce ji pomaga naprava v Linku
     * (racunalnik, telefon, tablica) ali ima sama veliko prostora; sicer le datoteko, ki gre na njen prosti prostor
     * (bajti); 0 = nobenega (Predvajalnik brez Linka, naprava brez prostora). Glej [Stremio.torrentGre].
     */
    private fun torrentMeja(): Long {
        if (racunalnikiZaPomoc().isNotEmpty() || napraveZaTorrent().isNotEmpty()) return Long.MAX_VALUE
        val prostor = MagnetMotor.prostorZaTok(this)
        return when {
            prostor >= 20L * 1024 * 1024 * 1024 -> Long.MAX_VALUE
            prostor < 150L * 1024 * 1024 -> 0L
            else -> prostor
        }
    }

    /**
     * [torrentMeja] za izris mreze: en izracun za ves izris in nekaj sekund po njem (naloga 55: 14-34 ms na klic -
     * prostor na disku in naprave v Linku - in klic je bil v vsakem izrisu veckrat). Sprememba v Linku gre mimo
     * predpomnilnika ([torrentSeJeSpremenil]).
     */
    @Volatile private var mejaIzrisa: Pair<Long, Long>? = null
    private fun mejaZaIzris(): Long {
        val zdaj = android.os.SystemClock.uptimeMillis()
        mejaIzrisa?.let { (cas, m) -> if (zdaj - cas in 0 until IZRIS_VELJA_MS) return m }
        return torrentMeja().also { mejaIzrisa = zdaj to it }
    }

    private fun torrentSteje() = torrentMeja() > 0L

    /** Dodatki za [Razpolozljivost.pripravi]: naslov in ali dodatek daje tokove (null, dokler manifesta ne poznamo). */
    private fun dodatkiZaRazpolozljivost() = nasloviZaIzris().map { it to Stremio.dajeTokove(it) }

    /** Kaj naprava zmore s torrenti, za dnevnik in za zaznavo spremembe (zapisi razpolozljivosti veljajo za vse nacine). */
    private fun nacinTorrenta(meja: Long = mejaZaIzris()) = when (meja) {
        Long.MAX_VALUE -> ""
        0L -> "brez"
        else -> "do" + meja / (256L * 1024 * 1024)
    }

    /** Naprave v Linku s Safeer OS (telefon, tablica, televizor), ki znajo torrent pretakati drugim ([si.safeer.tv.link.MagnetPomoc]). */
    private fun napraveZaTorrent(): List<LinkOdjemalec.Naprava> {
        if (link.jeKrajevni() || !link.povezan) return emptyList()
        val jaz = LinkUpravitelj.fizicnaNaprava(Identiteta.id(this))
        return link.naprave.filter { n -> si.safeer.tv.link.MagnetPomoc.ZMOZNOST in n.zmoznosti && LinkUpravitelj.fizicnaNaprava(n.id) != jaz }
    }

    private var torrentPrej: String? = null
    /** Pomocnik je prisel v krog ali ga zapustil: kar se da predvajati, je zdaj drugo - mreza se uredi takoj, ne cez ure. */
    private fun torrentSeJeSpremenil() {
        val meja = torrentMeja()
        mejaIzrisa = android.os.SystemClock.uptimeMillis() to meja
        val zdaj = nacinTorrenta(meja)
        val prej = torrentPrej
        torrentPrej = zdaj
        Razpolozljivost.pripravi(this, dodatkiZaRazpolozljivost(), meja)
        if (prej == null || prej == zdaj) return
        android.util.Log.i("SafeerOsMedia", "torrent steje: $prej -> $zdaj")
        SEZNAMI.remove(VIDEO); SEZNAMI.remove(DOMOV)
        odprtKatalog?.let { o -> o.neznani.clear(); o.vec(0); osveziMrezo(o) }
    }

    private fun kljucRazpolozljivosti(sk: Jamendo.Skladba): String? {
        val (_, tip, id) = Stremio.razstavi(sk) ?: return null
        return if (tip == "movie" || tip == "series") Razpolozljivost.kljuc(tip, id) else null
    }

    /**
     * Film ali serija iz dodatkov, za katero vemo, da je noben dodatek ne predvaja - po zadnjem znanem odgovoru, tudi
     * ce ni vec svez: ostane skrita, medtem ko jo v ozadju preverimo znova (seznam na to ne caka).
     */
    private fun znanoNiNaVoljo(sk: Jamendo.Skladba): Boolean {
        if (!Stremio.jeEnota(sk)) return false
        val znano = kljucRazpolozljivosti(sk)?.let { Razpolozljivost.znano(this, it) }
        // Knjiznica dodatka, ki tokov dokazano ne daje ([ZaupanjeKnjiznic]): njenih kartic ne kazemo - razen tiste, ki
        // se je sama ze dala predvajati.
        return znano == false || (znano == null && Stremio.knjiznicaNeDela(sk))
    }

    /**
     * Kartica iz lastne knjiznice dodatka, za katero se cakamo na dokaz, da dodatek zanjo res da tokove
     * ([vzorciKnjiznice]): do takrat je ni na zaslonu (lastnik, 5. 10. 2026: »samo tok, ki ga lahko predvajamo«).
     */
    private fun cakaNaKnjiznico(sk: Jamendo.Skladba): Boolean =
        Stremio.knjiznicaCaka(sk) && kljucRazpolozljivosti(sk)?.let { Razpolozljivost.znano(this, it) } != true

    private fun imamOmrezje(): Boolean = try { getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork != null } catch (_: Exception) { false }

    /**
     * Lastne knjiznice dodatkov med [vsi], o katerih se ne vemo, ali res dajo tokove (ali pa dokaz ni vec svez), vzorcimo
     * v ozadju: najvec tri poizvedbe na dodatek ([Stremio.vzorciKnjiznico]) - toliko gre tudi po omejenem omrezju.
     * [obvesti]: ko odgovor spremeni, kaj smemo pokazati, se odprta mreza dopolni na mestu ([poVzorcuKnjiznic]).
     */
    private fun zacniVzorce(vsi: List<Jamendo.Skladba>, torrent: () -> Long, obvesti: Boolean): List<java.util.concurrent.Future<*>> {
        val skupine = LinkedHashMap<String, MutableList<Jamendo.Skladba>>()
        for (sk in vsi) {
            if (!Stremio.izLastneKnjiznice(sk)) continue
            val osnova = Stremio.razstavi(sk)?.first ?: continue
            skupine.getOrPut(osnova) { ArrayList() }.add(sk)
        }
        if (skupine.isEmpty() || !imamOmrezje()) return emptyList()
        val niti = ArrayList<java.util.concurrent.Future<*>>()
        var meja: Long? = null
        for ((osnova, naslovi) in skupine) {
            if (!ZaupanjeKnjiznic.zacni(osnova)) continue
            val t = meja ?: torrent().also { meja = it }
            try {
                niti += iskanjeDelavec.submit {
                    val prej = ZaupanjeKnjiznic.stanje(osnova)
                    val izid = try { Stremio.vzorciKnjiznico(osnova, naslovi, t) } catch (_: Exception) { null }
                    ZaupanjeKnjiznic.koncaj(osnova, izid)
                    if (obvesti && ZaupanjeKnjiznic.stanje(osnova) != prej) glavna.post { poVzorcuKnjiznic() }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { ZaupanjeKnjiznic.preklici(osnova) }
        }
        return niti
    }

    private fun vzorciKnjiznice(vsi: List<Jamendo.Skladba>) { zacniVzorce(vsi, { mejaZaIzris() }, obvesti = true) }

    /** Kot [vzorciKnjiznice], a klic iz ozadja pocaka na izid (najvec [rokMs]): seznam pride na zaslon ze s pravimi karticami. */
    private fun vzorciKnjizniceZdaj(vsi: List<Jamendo.Skladba>, torrent: Long, rokMs: Long = 5_000) {
        val niti = zacniVzorce(vsi, { torrent }, obvesti = false)
        val rok = android.os.SystemClock.uptimeMillis() + rokMs
        for (f in niti) try { f.get((rok - android.os.SystemClock.uptimeMillis()).coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) { }
    }

    /** Vzorec je spremenil, katere knjiznice smemo pokazati: odprta mreza se dopolni na mestu; police pocakajo na naslednje risanje. */
    private fun poVzorcuKnjiznic() {
        if (isFinishing) return
        if (odprtKatalog != null) { narociUmiri(); return }
        mrezaVsebine?.let { narisiMrezo(it) }
    }

    /** Ali za serijo vemo vsaj za eno epizodo, da se da predvajati (potem serija ostane, cetudi ena sezona manjka). */
    private fun imaZnanoEpizodo(sk: Jamendo.Skladba): Boolean =
        kljucRazpolozljivosti(sk)?.let { Razpolozljivost.znano(this, it) == true } == true

    private fun oznaciNaVoljo(sk: Jamendo.Skladba) {
        kljucRazpolozljivosti(sk)?.let { if (Razpolozljivost.stanje(this, it) != true) Razpolozljivost.zapomniNaVoljo(this, it) }
        // Tok iz lastne knjiznice dodatka: dokaz, da knjiznica dela ([ZaupanjeKnjiznic]).
        Stremio.zabeleziKnjiznico(sk, true)
    }

    /**
     * Dotik ni dal toka: vsebino skrijemo takoj (za nekaj minut), za ure pa si "ni na voljo" zapomnimo sele, ko to
     * potrdijo vsi dodatki. Izpad dodatka ali omrezja tako ne skrije naslova, ki je cez minuto spet na voljo.
     */
    private fun skrijInPotrdi(k: String, preveri: (List<String>, Long) -> RazpolozljivostPravila.Izid?) {
        Razpolozljivost.zacasnoNi(k)
        val naslovi = stremioNaslovi()
        if (naslovi.isEmpty()) return
        val torrent = torrentMeja()
        try {
            preverjanjeEpizod.execute {
                val r = try { preveri(naslovi, torrent) } catch (_: Exception) { null }
                if (r != null) Razpolozljivost.zapomni(applicationContext, k, r)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    /** Vsebine se ne da predvajati: kartico umaknemo z zaslona (mreza ostane, kjer je bila). */
    private fun oznaciNiNaVoljo(sk: Jamendo.Skladba) {
        val k = kljucRazpolozljivosti(sk) ?: return
        skrijInPotrdi(k) { naslovi, torrent -> preveriEnoto(sk, naslovi, torrent) }
        SEZNAMI.remove(VIDEO); SEZNAMI.remove(DOMOV)
        val o = odprtKatalog
        // Mreza se uredi sama; na policah in med zadetki iskanja izgine kartica, ki jo je uporabnik pravkar izbral.
        if (o != null) osveziMrezo(o) else zadnjaKartica?.get()?.let { v ->
            if (v.isAttachedToWindow) { val naslednja = v.focusSearch(View.FOCUS_RIGHT); v.visibility = View.GONE; if (jeTv()) naslednja?.requestFocus() }
        }
    }

    /** Kartica, ki jo je uporabnik nazadnje izbral (da jo lahko umaknemo, ce se vsebine ne da predvajati). */
    private var zadnjaKartica: java.lang.ref.WeakReference<View>? = null
    /** Id vsebine te kartice in vseh njenih razlicic (ista vsebina iz vec virov je ena kartica). */
    private var zadnjiIzbran = ""
    private var zadnjeRazlicice: List<String> = emptyList()

    /** Kanal ali postaja, ki jo je uporabnik pravkar izbral, pri viru ne dela: kartica izgine, izbira gre na naslednjo. */
    private fun umakniMrtvega() {
        if (zadnjiIzbran.isEmpty() || !MrtviKanali.jeMrtev(this, zadnjiIzbran)) return
        zadnjiIzbran = ""
        // Odprta mreza se uredi na mestu: kartice za umaknjeno se pomaknejo naprej (prej je v vrsti ostala luknja -
        // izmerjeno 5. 10. 2026). Na polici spodaj kartico samo skrijemo.
        val lastnik = narisanaMreza?.lastnik
        val mk = mrezaKanalov
        val mv = mrezaVsebine
        if (mk != null && lastnik === mk) { narisiKanale(mk); return }
        if (mv != null && lastnik === mv) { narisiMrezo(mv); return }
        val v = zadnjaKartica?.get() ?: return
        if (!v.isAttachedToWindow || v.visibility != View.VISIBLE) return
        val naslednja = v.focusSearch(View.FOCUS_RIGHT)?.takeIf { it !== v } ?: v.focusSearch(View.FOCUS_LEFT)?.takeIf { it !== v }
        v.visibility = View.GONE
        if (jeTv()) naslednja?.requestFocus()
    }
    private val obMrtvemKanalu: (String) -> Unit = { glavna.post { if (!isFinishing) umakniMrtvega() } }

    /**
     * Naloga 79b (lastnik: »Kako naj uporabnik placa dodatku ... to bi moralo biti omogoceno«): povezava za odklep pri dodatku
     * ([Stremio.povezavaOdklepa]) v Safeer Splet (s Scitom) - samo na izrecno dejanje, s pojasnilom pred odprtjem; nikoli
     * samodejno in nikoli v mrezi.
     */
    private fun odkleniPriDodatku(naslov: String) {
        val povezava = Stremio.povezavaOdklepa(naslov) ?: return
        val ime = Stremio.imeIzPredpomnilnika(naslov) ?: MedijskiViri.vsi(this).firstOrNull { Stremio.osnova(it.naslov) == Stremio.osnova(naslov) }?.ime.orEmpty()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_mediji_odkleni))
            .setMessage(getString(R.string.os_mediji_odkleni_opis, ime.ifBlank { android.net.Uri.parse(povezava).host.orEmpty() }))
            .setPositiveButton(R.string.os_mediji_odkleni_odpri) { _, _ -> odpriStran(povezava, ime) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Naloga 79b: dodan je dodatek, ki je pod drugim naslovom ze dodan (isti id manifesta - npr. nov naslov s kodo po narocnini):
     * sklep »brez prostih tokov« starega naslova pozabimo in ponudimo odstranitev starega. Klic z glavne niti po shranitvi.
     */
    private fun preveriDvojnikDodatka(naslov: String) {
        val app = applicationContext
        try { delavec.execute {
            val nova = Stremio.osnova(naslov)
            val dodani = MedijskiViri.vsi(app).filter { it.jeStremio }.associate { v ->
                Stremio.osnova(v.naslov) to (try { Stremio.manifest(v.naslov)?.id } catch (_: Exception) { null }).orEmpty() }
            val stari = OsPravila.stariNasloviDodatka(nova, dodani[nova].orEmpty(), dodani)
            if (stari.isEmpty()) return@execute
            stari.forEach { Stremio.pozabiZaklep(it) }
            android.util.Log.i("SafeerOsMedia", "dodatek dodan dvakrat (isti id manifesta): starih naslovov ${stari.size}")
            glavna.post {
                if (isFinishing || isDestroyed) return@post
                val star = MedijskiViri.vsi(this).firstOrNull { it.jeStremio && Stremio.osnova(it.naslov) in stari } ?: return@post
                val ime = Stremio.imeIzPredpomnilnika(naslov) ?: star.ime
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(ime)
                    .setMessage(getString(R.string.os_mediji_dvojnik_dodatka, ime))
                    .setPositiveButton(R.string.os_mediji_odstrani) { _, _ ->
                        MedijskiViri.odstrani(this, star)
                        val o = Stremio.osnova(star.naslov)
                        delavec.execute { ZasebniKanali.pozabi(app, o) }
                        poSpremembiVirov(); izberiNaMestu(VIRI)
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    // ------------------------------------------------------------------ naloga 87: predpomnilnik (USB, naprava v Linku)

    private fun velikostBesedilo(b: Long): String = android.text.format.Formatter.formatShortFileSize(this, b)

    /** Opis kartice iz zadnjega izracuna ([UsbPredpomnilnik.stanje]) - brez branja diska na glavni niti. */
    private fun opisPredpomnilnika(): String {
        val st = UsbPredpomnilnik.stanje
        if (!st.izracunano) return getString(R.string.os_predpomnilnik_racunam)
        val mesto = UsbPredpomnilnik.mesto(this)
        return when {
            mesto.nacin == PredpomnilnikPravila.Nacin.LINK -> getString(R.string.os_predpomnilnik_opis_link, imeNapraveLinka(mesto.naprava), velikostBesedilo(st.zasedenoNotranji))
            st.usb -> getString(R.string.os_predpomnilnik_opis_usb, velikostBesedilo(st.zasedenoUsb), velikostBesedilo(st.mejaUsb), velikostBesedilo(st.zasedenoNotranji))
            else -> getString(R.string.os_predpomnilnik_opis_tv, velikostBesedilo(st.zasedenoNotranji))
        }
    }

    private fun imeNapraveLinka(id: String): String = link.naprave.firstOrNull { it.id == id }?.ime?.takeIf { it.isNotBlank() } ?: id

    /** Mesto, velikost na USB in »Pocisti predpomnilnik« (Moji viri -> Predpomnilnik, pri Predvajalniku tudi ⋮). */
    private fun nastavitvePredpomnilnika() {
        val mesto = UsbPredpomnilnik.mesto(this)
        val imeMesta = when (mesto.nacin) {
            PredpomnilnikPravila.Nacin.NOTRANJI -> getString(R.string.os_predpomnilnik_ta_tv)
            PredpomnilnikPravila.Nacin.LINK -> imeNapraveLinka(mesto.naprava)
            PredpomnilnikPravila.Nacin.SAMODEJNO -> getString(R.string.os_predpomnilnik_samodejno)
        }
        val dejanja = listOf<Pair<String, () -> Unit>>(
            getString(R.string.os_predpomnilnik_mesto, imeMesta) to { izberiMestoPredpomnilnika() },
            getString(R.string.os_predpomnilnik_velikost, UsbPredpomnilnik.velikostGb(this)) to {
                val v = PredpomnilnikPravila.VELIKOSTI_GB
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(R.string.os_predpomnilnik_naslov)
                    .setSingleChoiceItems(v.map { getString(R.string.os_predpomnilnik_gb, it) }.toTypedArray(), v.indexOf(UsbPredpomnilnik.velikostGb(this))) { d, i ->
                        d.dismiss(); UsbPredpomnilnik.nastavi(this, velikostGb = v[i])
                    }.show()
                Unit
            },
            getString(R.string.os_predpomnilnik_pocisti) to {
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(R.string.os_predpomnilnik_pocisti)
                    .setMessage(R.string.os_predpomnilnik_pocisti_vprasanje)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        UsbPredpomnilnik.pocisti(this) { glavna.post { if (!isFinishing) Toast.makeText(this, R.string.os_predpomnilnik_pociscen, Toast.LENGTH_SHORT).show() } }
                    }.setNegativeButton(android.R.string.cancel, null).show()
                Unit
            },
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(getString(R.string.os_predpomnilnik_naslov) + " · " + opisPredpomnilnika())
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }.show()
        UsbPredpomnilnik.osveziStanje(this)
    }

    /**
     * Izbira mesta (57b): ta TV, samodejno USB (s prostim prostorom, ce je priklopljen) in naprave v Linku z vlogo predpomnilnik
     * (ime in prosti prostor - vprasamo jih hkrati, najvec 1,5 s; ce ne odgovori, »ne odgovori«).
     */
    private fun izberiMestoPredpomnilnika() {
        val naprave = LinkPredpomnilnik.naprave(this)
        val prostih = HashMap<String, Long?>()
        var prikazano = false
        fun pokazi() {
            if (prikazano || isFinishing) return
            prikazano = true
            val usb = UsbPredpomnilnik.usbPriklopljen
            val moznosti = listOf(PredpomnilnikPravila.Mesto(PredpomnilnikPravila.Nacin.NOTRANJI) to getString(R.string.os_predpomnilnik_ta_tv),
                PredpomnilnikPravila.Mesto(PredpomnilnikPravila.Nacin.SAMODEJNO) to (if (usb != null)
                    getString(R.string.os_predpomnilnik_samodejno_usb, velikostBesedilo(usb.prostih)) else getString(R.string.os_predpomnilnik_samodejno))) +
                naprave.map { (id, ime) -> PredpomnilnikPravila.Mesto(PredpomnilnikPravila.Nacin.LINK, id) to (prostih[id]?.let {
                    getString(R.string.os_predpomnilnik_naprava, ime, velikostBesedilo(it)) } ?: getString(R.string.os_predpomnilnik_naprava_ne_odgovori, ime)) }
            val zdaj = UsbPredpomnilnik.mesto(this)
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(R.string.os_predpomnilnik_naslov)
                .setSingleChoiceItems(moznosti.map { it.second }.toTypedArray(), moznosti.indexOfFirst { it.first == zdaj }) { d, i ->
                    d.dismiss(); UsbPredpomnilnik.nastavi(this, mesto = moznosti[i].first)
                }.show()
        }
        if (naprave.isEmpty()) { pokazi(); return }
        Toast.makeText(this, R.string.os_predpomnilnik_preverjam, Toast.LENGTH_SHORT).show()
        for ((id, _) in naprave) LinkPredpomnilnik.stat(this, id) { p -> prostih[id] = p; if (prostih.size == naprave.size) pokazi() }
        glavna.postDelayed({ pokazi() }, 1_600)
    }

    /** Naloga 75: napredek pregleda kanalov na kartici dodatka - Moji viri se osvezijo na mestu, ko uporabnik miruje. */
    private val obPregleduKanalov: () -> Unit = {
        glavna.post {
            if (!isFinishing && vOspredju && razdelek == VIRI && odprtKatalog == null && !podstrani.odprta &&
                android.os.SystemClock.uptimeMillis() - zadnjiDotik > 2_500) izberiNaMestu(VIRI)
        }
    }

    /** Mrezo narisemo znova brez umaknjenih kartic; drsnik in izbira (daljinec) ostaneta, kjer sta bila. */
    private fun osveziMrezo(o: OdprtKatalog, vnaprej: Pair<List<Jamendo.Skladba>, List<Jamendo.Skladba>>? = null) {
        if (odprtKatalog !== o || isFinishing) return
        // Mreza se dopolni na mestu ([pokaziMrezo]): polozaj in izbira ostaneta sama od sebe. Po polnem risanju (druga
        // glava, sirina) izbiro vrne narisi po oznaki kartice, polozaj pa tukaj.
        val y = drsnik.scrollY
        val prej = narisanaMreza
        narisiKatalog(o.naslov, vnaprej)
        if (narisanaMreza !== prej || prej == null) drsnik.post { drsnik.scrollTo(0, y) }
    }

    /** Naslovi okna cakajo na odgovor doma (DomPreverjanje) - niti vecinoma samo cakajo, zato jih je toliko kot okno sme hkrati. */
    private val preverjanje = Executors.newFixedThreadPool(12)
    /** Epizode odprtega seznama imajo svojo vrsto (uporabnik caka nanje), da jih preverjanje mreze ne zadrzuje. */
    private val preverjanjeEpizod = Executors.newFixedThreadPool(4)
    private val vPreverjanju = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    @Volatile private var zadnjiDotik = 0L
    private var umiriNarocen = false
    /** Mrezo uredimo najvec enkrat na dobro sekundo, ne glede na to, koliko odgovorov dodatkov pride vmes. */
    private fun narociUmiri() {
        if (umiriNarocen) return
        umiriNarocen = true
        glavna.postDelayed(umiri, 1_200)
    }
    private val umiri = object : Runnable {
        override fun run() {
            val o = odprtKatalog ?: run { umiriNarocen = false; return }
            if (isFinishing) { umiriNarocen = false; return }
            // Dokler uporabnik drsi ali izbira, mreze ne premikamo pod prsti; uredimo jo, ko za hip miruje. Prazna
            // mreza ne caka: prve kartice pridejo takoj, ko so preverjene.
            if (o.narisani.isNotEmpty() && android.os.SystemClock.uptimeMillis() - zadnjiDotik < 2_500) { glavna.postDelayed(this, 1_000); return }
            umiriNarocen = false
            // Znova risemo samo, ce bi bila mreza drugacna (kartica, ki je ni vec mogoce predvajati, ali nova preverjena).
            // vidniKataloga enkrat na krog (naloga 55: prej do stirikrat - tu, v narisiKatalog, preveriMrezo, poPreverjanju).
            val izracun = vidniKataloga(o)
            val vidni = izracun.second.map { it.id }
            val drugace = vidni != o.narisani
            // Lastnikova zasnova (naloga 55): prvi zaslon takoj, nato se mreza dopolni enkrat, ko je okno preverjeno, ali
            // najvec vsakih DOPOLNI_NAJVEC_MS - ne ob vsakem odgovoru dodatka. Izginula kartica gre z zaslona takoj.
            val odRisanja = android.os.SystemClock.uptimeMillis() - o.risanoOb
            // Napis »Preverjam ...« na kartici Nalozi vec ne sme ostati, ko je preverjanje okna koncano.
            val risem = (drugace && OsPravila.risiMrezoZdaj(o.narisani, vidni, PRVI_ZASLON, o.caka.get() > 0, odRisanja, DOPOLNI_NAJVEC_MS)) ||
                (o.prosiVec && o.caka.get() == 0)
            if (drugace || o.caka.get() == 0) android.util.Log.i("SafeerOsMedia", "mreza: vidnih=${vidni.size}, narisanih=${o.narisani.size}, vseh=${o.vsi.size}, kandidatov=${izracun.first.size}, caka=${o.caka.get()}, ozadje=${o.ozadje.get()}, " +
                "nalagam=${o.nalagam}, seKaj=${o.seKaj}, okno=${o.zelja}, proracun=${o.proracun}, preverjenih=${o.preverjenih.get()}, zadetkov=${o.zadetkov.get()}, " +
                "samodejno=${o.samodejno}, strogo=${strogaMreza(o)}, torrent=${nacinTorrenta().ifEmpty { "vse" }}, risem=$risem")
            // Risanje samo nadaljuje preverjanje (narisiKatalog -> preveriMrezo); brez risanja ga nadaljujemo tu.
            if (risem) osveziMrezo(o, izracun) else {
                if (strogaMreza(o)) preveriMrezo(o, izracun.first)
                // Odlozeno dopolnjevanje: pogledamo znova cez dobro sekundo (okno je morda ze preverjeno), najkasneje ob roku.
                if (drugace) { umiriNarocen = true; glavna.postDelayed(this, (DOPOLNI_NAJVEC_MS - odRisanja).coerceIn(200L, 1_200L)); return }
            }
            poPreverjanju(o, izracun.first)
        }
    }

    /** Omrezje (neomejeno) za izris mreze: sistem vprasamo najvec enkrat na [IZRIS_VELJA_MS] (naloga 55). */
    @Volatile private var smemIzrisa: Pair<Long, Boolean>? = null
    private fun smemPreverjati(): Boolean {
        val zdaj = android.os.SystemClock.uptimeMillis()
        smemIzrisa?.let { (cas, s) -> if (zdaj - cas in 0 until IZRIS_VELJA_MS) return s }
        val omrezje = getSystemService(android.net.ConnectivityManager::class.java)
        return (omrezje != null && omrezje.activeNetwork != null && !omrezje.isActiveNetworkMetered).also { smemIzrisa = zdaj to it }
    }

    /**
     * Naslov iz dodatkov, ki ga se ne poznamo: samo na take mreza caka. Zastarel odgovor (»je« ali »ni«) velja za
     * prikaz naprej in se preveri znova v ozadju - mreza se ne sprazni in ne obstane.
     */
    private fun nepreverjen(sk: Jamendo.Skladba): Boolean {
        if (!Stremio.jeEnota(sk)) return false
        val k = kljucRazpolozljivosti(sk)
        if (k != null && Razpolozljivost.znano(this, k) != null) return false
        // Lastna knjiznica dodatka caka na vzorec ([vzorciKnjiznice]), ne na poizvedbo za vsak naslov; ostalo na preverjanje.
        return if (Stremio.izLastneKnjiznice(sk)) Stremio.zaupanjeKnjiznice(sk) != true else k != null
    }

    /** Naslov z znanim, a ne vec svezim odgovorom: preverimo ga znova, ko mreza nima nujnejsega dela. */
    private fun zastarel(sk: Jamendo.Skladba): Boolean =
        Stremio.jeEnota(sk) && kljucRazpolozljivosti(sk)?.let {
            val znano = Razpolozljivost.znano(this, it)
            // »Je« kartice iz lastne knjiznice dodatka ne zastara (dodatek jo se vedno nasteva); »ni« preverimo znova.
            znano != null && Razpolozljivost.stanje(this, it) == null && !(znano && Stremio.lastnaKnjiznica(sk))
        } == true

    /**
     * Strogi nacin mreze: kartico iz dodatkov pokazemo sele, ko je preverjena (3. 10. 2026: na televizorju se je 122
     * filmov pokazalo in v hipu izginilo - nobenega ni bilo mogoce predvajati). Nic se ne pokaze in spet skrije: mreza
     * se polni sproti, preverjeno ostane v predpomnilniku (naslednjic je polna takoj). Velja, kadar smemo preverjati
     * (neomejeno omrezje); na mobilnih podatkih ostane po starem - pokazemo vse, cesar ne poznamo kot nepredvajljivo.
     *
     * Lastnik 28. 10. 2026 (»Pokazi vse takoj«): mreza ni vec stroga. Strogi nacin je kazal samo naslove, ki jih je dodatek
     * ze potrdil, dodatki pa smejo odgovarjati vljudno (1 vprasanje na 8 s, ~110 na uro) - kartica »Preverjam … se 9« je
     * cakala ~72 s, po ~110 naslovih v uri pa mreza ni vec rasla. Zdaj pridejo vsi naslovi kataloga takoj; preverjanje
     * tece v ozadju (preveriMrezo), nepredvajljiv naslov izpade (znanoNiNaVoljo), ob kliku pa ga preveri predvajanje.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun strogaMreza(o: OdprtKatalog): Boolean = false

    /** (kandidati = vse, cesar ne poznamo kot nepredvajljivo; vidni = kar od tega res pokazemo). */
    /** Meritev podkorakov izrisa mreze (22. 10. 2026): zapise jo narisiKatalog, ce je izris pocasen. */
    private val perf = StringBuilder()
    private inline fun <T> meri(ime: String, f: () -> T): T {
        val z = android.os.SystemClock.uptimeMillis(); val r = f()
        val d = android.os.SystemClock.uptimeMillis() - z
        if (d >= 5) perf.append(ime).append('=').append(d).append(' ')
        return r
    }

    private fun vidniKataloga(o: OdprtKatalog): Pair<List<Jamendo.Skladba>, List<Jamendo.Skladba>> {
        val meja = meri("torrentMeja") { mejaZaIzris() }
        torrentPrej = nacinTorrenta(meja)
        val dodatki = meri("dodatki") { dodatkiZaRazpolozljivost() }
        meri("pripravi") { Razpolozljivost.pripravi(this, dodatki, meja) }
        val urejeni = meri("red") { urediMrezo(o) }
        o.urejeni = urejeni
        val strogo = strogaMreza(o)
        // Strogo: na zaslon gre samo okno (prvih o.zelja), zato preverimo le toliko kandidatov in rezervo za oknom - ne
        // vseh nalozenih naslovov (naloga 55: Serije 5143 naslovov, 140-235 ms na vsak izris). Ostali pridejo na vrsto,
        // ko se okno pomakne (Nalozi vec, drsenje). Na omejenem omrezju (ne strogo) mreza kaze vse, kot doslej.
        val izbor = meri("znano") {
            if (strogo) OsPravila.prvih(urejeni, o.zelja + REZERVA_KANDIDATOV) { znanoNiNaVoljo(it) || drugaVrsta(o, it) }
            else urejeni.filterNot { znanoNiNaVoljo(it) || drugaVrsta(o, it) }.let { OsPravila.Prvih(it, urejeni.size, false) }
        }
        val kandidati = izbor.izbrani
        o.prirezani = izbor.prirezano
        o.pregledanih = izbor.pregledanih
        // Strogo: samo okno in v njem samo preverjeno - kartice pridejo po vrsti, nic se ne vrine vmes.
        // Ista slika dvakrat je za uporabnika podvojen prikaz (lastnik, 5. 10. 2026: razlicni vnosi z istim ovitkom):
        // v mrezi ostane prva.
        // Lastna knjiznica dodatka brez dokaza ne pride na zaslon niti na omejenem omrezju (vzorec so tri poizvedbe).
        // Naloga 55b: strogo samo prva stran (o.strogoDo mest okna); naslednje strani in kar je ze na zaslonu se pokazejo
        // takoj, preverjanje tece v ozadju - kar se izkaze za nepredvajljivo, izpade (znanoNiNaVoljo).
        val vidni = meri("okno") {
            val naZaslonu = o.narisani.toHashSet()
            (if (strogo) oknoMreze(o, kandidati).naslovi.filterIndexed { i, sk -> OsPravila.vOknuViden(i, o.strogoDo, sk.id in naZaslonu, nepreverjen(sk)) }
                else kandidati.filterNot { cakaNaKnjiznico(it) })
            .distinctBy { it.slika.ifBlank { "id:" + it.id } } }
        return kandidati to obdrziIzbrano(o, vidni)
    }

    /**
     * Naloga 63: naslov druge vrste, kot je mreza - serija med Filmi (dodatek jo v katalogu filmov navede kot film) ali
     * film med Serijami - po tem, kar Safeer o naslovu ze ve ([VrsteNaslovov], vrsta kartice). Izpade kot nepredvajljiva
     * kartica: tiho, kartica z izbiro ostane do premika izbire ([obdrziIzbrano]). Brez omrezja.
     */
    private fun drugaVrsta(o: OdprtKatalog, sk: Jamendo.Skladba): Boolean = when (o.tip) {
        "movie" -> SpletniVir.vrstaVsebine(sk) == SpletniVir.SERIJA || VrsteNaslovov.vrsta(sk) == VrsteNaslovov.SERIJA
        "series" -> VrsteNaslovov.vrsta(sk) == VrsteNaslovov.FILM
        else -> false
    }

    /**
     * Kartica, ki izpade iz mreze (noben vir je ne predvaja), medtem ko ima izbiro: ostane na svojem mestu, dokler se
     * izbira ne premakne - izbira ne skoci (naloga 55b). [OdprtKatalog.drzim] pove, da je treba po premiku risati znova.
     */
    private fun obdrziIzbrano(o: OdprtKatalog, vidni: List<Jamendo.Skladba>): List<Jamendo.Skladba> {
        val (izid, drzim) = OsPravila.obdrziIzbrano(vidni, o.narisani, izbranaKarticaMreze(), { it.id }) { id -> o.vsi.firstOrNull { it.id == id } }
        o.drzim = drzim
        return izid
    }

    /** Kljuc kartice mreze, ki ima izbiro (daljinec); null, ce izbira ni na kartici mreze. */
    private fun izbranaKarticaMreze(): String? {
        val nm = narisanaMreza ?: return null
        val f = vsebina.findFocus() ?: return null
        for ((k, p) in nm.pogledi) {
            var x: View? = f
            while (x != null && x !== vsebina && x !== p) x = x.parent as? View
            if (x === p) return k
        }
        return null
    }

    /**
     * Vrstni red vseh naslovov mreze (jeziki, razvrstitev, stabilen red). Isti vhod (naslovi, razvrstitev, jeziki, kartice
     * na zaslonu) da isti izid, zato ga ne racunamo znova ob vsakem izrisu (naloga 55: red=12-20 ms pri 5143 naslovih).
     */
    private fun urediMrezo(o: OdprtKatalog): List<Jamendo.Skladba> {
        val brskanje = o.tip.isNotBlank()
        val nacin = if (brskanje) razvrstitev(VIDEO) else RAZVRSTI_PRIPOROCENO
        val jeziki = if (brskanje) izklopljeniJeziki(VIDEO) else emptySet()
        val vhod = listOf<Any>(OsPravila.odtis(o.vsi) { it.id }, nacin, jeziki, o.narisani)
        o.ureditev?.let { (prej, urejeni) -> if (prej == vhod) return urejeni }
        val poVrsti = meri("razvrsti") { if (brskanje) razvrsti(VIDEO, JezikiVsebine.filtriraj(o.vsi, jeziki) { it.language }) else o.vsi }
        // Kar uporabnik ze vidi, ostane na svojem mestu: sveze strani in odgovori preverjanja kartice dodajajo za zadnjo, ne
        // vrivajo jih vmes in ne premescajo (izmerjeno na televizorju 5. 10. 2026: ob prihodu svezih katalogov je mreza sedem
        // kartic umaknila in dve vrinila). Izrecna razvrstitev (po imenu, najnovejse) ostane razvrstitev.
        // toList(): o.vsi je spremenljiv seznam - shranjen izid se ne sme spremeniti pod predpomnilnikom.
        val urejeni = if (brskanje && nacin != RAZVRSTI_PRIPOROCENO) poVrsti.toList() else OsPravila.stabilenRed(o.narisani, poVrsti) { it.id }.toList()
        o.ureditev = vhod to urejeni
        return urejeni
    }

    /**
     * Okno stroge mreze: prvih o.zelja kandidatov po vrsti. Naslova, ki ga zdaj ni mogoce preveriti (vsi njegovi dodatki
     * so v premoru, Stremio.vprasaniVPremoru), ne stejemo - sicer bi okno obstalo na njem in do ostalih ne bi prisli.
     */
    private fun oknoMreze(o: OdprtKatalog, kandidati: List<Jamendo.Skladba>): Okno {
        val dodatki = nasloviZaIzris()
        val izhod = ArrayList<Jamendo.Skladba>(o.zelja)
        var premor = 0; var naprej = false
        val naZaslonu = o.narisani.toHashSet()
        for (sk in kandidati) {
            if (izhod.size >= o.zelja) { naprej = true; break }
            val k = kljucRazpolozljivosti(sk)
            // Naslednje strani (naloga 55b) in kar je ze na zaslonu: naslov ostane v oknu, tudi ce je dodatek v premoru.
            val strogo = izhod.size < o.strogoDo && sk.id !in naZaslonu
            if (strogo && k != null && Razpolozljivost.znano(this, k) == null && k !in o.neznani && !Stremio.izLastneKnjiznice(sk)) {
                val r = Stremio.razstavi(sk)
                if (r != null && Stremio.vprasaniVPremoru(dodatki, r.second, r.third)) { premor++; continue }
            }
            izhod += sk
        }
        // Kandidati so prirezani (vidniKataloga): za njimi so se nalozeni naslovi, okno se lahko pomakne naprej.
        return Okno(izhod, naprej || o.prirezani, premor)
    }

    /** Napis pod naslovom mreze: se dela, dodatek omejuje poizvedbe, ni nicesar ali nic. */
    private fun opisMreze(o: OdprtKatalog, st: StanjeMreze, prazno: Boolean): String {
        // »Nalagam« samo, dokler v mrezi ni nobenega plakata: preverjanje v ozadju (ali se da predvajati) ni nalaganje za
        // uporabnika - napis je ostal tudi, ko so bili plakati ze na zaslonu (lastnik, 22. 10. 2026).
        val dela = prazno && ((strogaMreza(o) && (o.caka.get() > 0 || o.nalagam || (st.vOknu && o.proracun > 0))) || o.nalagam || !o.popolna)
        // Naloga 55b: dodatek omejuje poizvedbe (premor ali porabljen urni proracun) - povemo, kdaj nadaljujemo.
        val cez = Stremio.premorKoncaOb(nasloviZaIzris()).let { if (it > 0L) ((it - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1L) else 0L }
        return when {
            dela -> getString(R.string.os_glasba_nalagam)
            cez > 0L && (st.premor || st.nepreverjeniNaZaslonu) -> getString(R.string.os_media_dodatek_omejuje_min, cez.toInt())
            st.premor -> getString(R.string.os_media_dodatek_omejuje)
            prazno -> praznaMreza(o)
            else -> ""
        }
    }

    /** Besedilo prazne mreze: katalog ima naslove, a nobenega ne predvaja noben dodatek - povemo, kaj manjka. */
    private fun praznaMreza(o: OdprtKatalog) = getString(when {
        o.vsi.isEmpty() && brezSpletnihVirov() -> R.string.os_media_brez_virov
        o.vsi.any { znanoNiNaVoljo(it) } -> R.string.os_media_ni_predvajljivih
        o.jezik.isNotEmpty() -> R.string.os_jezik_ni_vsebine
        else -> R.string.os_glasba_prazno
    })

    /**
     * Ko je mreza preverjena: v strogem nacinu stran, ki ni dala nobene nove kartice, ni odgovor na "Nalozi vec" -
     * sami gremo po naslednjo (najvec tri), potem gumb izgine (naslednje strani bi bile enako prazne).
     */
    private fun poPreverjanju(o: OdprtKatalog, kandidati: List<Jamendo.Skladba>? = null) {
        if (odprtKatalog !== o || isFinishing || o.nalagam || o.caka.get() > 0) return
        if (!o.popolna) return
        if (!strogaMreza(o)) {
            if (stanje.text.toString() == getString(R.string.os_glasba_nalagam)) stanje.text = if (o.narisani.isEmpty()) praznaMreza(o) else ""
            return
        }
        val st = stanjeMreze(o, kandidati ?: vidniKataloga(o).first)
        // Vse nalozene strani so razresene, okno pa se ni polno: sami gremo po naslednjo stran (najvec tri, v okviru proracuna).
        if (!st.vOknu && !st.naprej && !st.premor && o.seKaj && o.proracun > 0 && o.narisani.size < o.zelja) {
            if (o.samodejno++ < 3) { naloziVecKataloga(o.naslov, samodejno = true); return }
            o.seKaj = false
        }
        // Preverjanje miruje (konec, porabljen proracun ali premor dodatka): ce bi bila mreza zdaj drugacna (napis pod
        // naslovom, gumb Nalozi vec), jo narisemo se enkrat.
        val prav = opisMreze(o, st, o.narisani.isEmpty())
        if (stanje.text.toString() != prav || o.narisano != (o.narisani to (o.seKaj || st.vOknu || st.naprej))) osveziMrezo(o)
    }

    private fun stanjeMreze(o: OdprtKatalog, kandidati: List<Jamendo.Skladba>): StanjeMreze {
        val okno = oknoMreze(o, kandidati)
        // Okno caka samo na naslove, ki jih se ne poznamo; zastareli odgovori se osvezijo v ozadju.
        fun caka(sk: Jamendo.Skladba) =
            if (Stremio.izLastneKnjiznice(sk)) nepreverjen(sk)       // caka na vzorec svoje knjiznice
            else kljucRazpolozljivosti(sk)?.let { Razpolozljivost.znano(this, it) == null && it !in o.neznani } == true
        // Naloga 55b: na zaslon caka samo strogi del okna (prva stran); naslednje strani so ze na zaslonu.
        val vOknu = okno.naslovi.take(o.strogoDo.coerceAtMost(okno.naslovi.size)).any { caka(it) }
        val nepreverjeni = okno.naslovi.size > o.strogoDo && okno.naslovi.drop(o.strogoDo).any { nepreverjen(it) }
        return StanjeMreze(vOknu, okno.vPremoru > 0, okno.naprej, nepreverjeni)
    }

    /**
     * Uporabnik hoce vec (drsenje proti koncu, Nalozi vec): najprej dokoncamo okno (nov proracun poizvedb), nato ga
     * podaljsamo po ze nalozenih straneh, sele ko teh zmanjka, gremo po naslednjo stran kataloga.
     */
    private fun vecMreze(o: OdprtKatalog, izDrsenja: Boolean = false) {
        if (odprtKatalog !== o || isFinishing || o.nalagam) return
        if (strogaMreza(o)) {
            val zdaj = android.os.SystemClock.uptimeMillis()
            // Naloga 145: dodatek v premoru - na preverjanje okna ne cakamo (sicer Nalozi vec do konca premora ne naredi nic).
            val premor = Stremio.premorKoncaOb(nasloviZaIzris()) > System.currentTimeMillis()
            val cakaj = OsPravila.cakajNaPreverjanje(o.caka.get(), premor)
            if (izDrsenja && (cakaj || zdaj - o.zadnjicVec < 600)) return  // drsenje: en korak, ne pred preverjanjem
            val kandidati = vidniKataloga(o).first
            val st = stanjeMreze(o, kandidati)
            // Naloga 55b (lastnik): klik Nalozi vec pokaze naslednjo stran TAKOJ, tudi ce se preverjanje se tece ali je
            // proracun dodatka porabljen - naslovi naslednjih strani se preverijo v ozadju (ista vljudnost).
            if (!izDrsenja && st.naprej) { o.zadnjicVec = zdaj; o.vec(KORAK_KARTIC); osveziMrezo(o); return }
            if (cakaj) {
                // Prva stran se se preverja in nalozenih naslovov za njo ni: kartica pove, koliko naslovov se preverjamo
                // (dodatek k nalogi 55: prej je klic tu tiho odnehal).
                if (izDrsenja || o.prosiVec) return
                o.zadnjicVec = zdaj
                o.prosiVec = true
                osveziMrezo(o)
                return
            }
            if (st.vOknu && !premor) {
                if (izDrsenja && o.proracun > 0) return
                o.zadnjicVec = zdaj
                o.vec(0)
                stanje.text = getString(R.string.os_glasba_nalagam)
                preveriMrezo(o, kandidati)
                narociUmiri()
                return
            }
            if (st.naprej) { o.zadnjicVec = zdaj; o.vec(KORAK_KARTIC); osveziMrezo(o); return }
            if (!o.seKaj) return
            o.zadnjicVec = zdaj
            o.vec(KORAK_KARTIC)
        }
        // Nestroga mreza: uporabnik gre naprej - preverjanje v ozadju dobi nov proracun za naslove, ki prihajajo.
        if (!strogaMreza(o)) o.vec(0)
        if (o.seKaj) naloziVecKataloga(o.naslov)
    }

    /**
     * Ali je serija ali film na voljo: film vprasamo naravnost, serijo po prvi epizodi. Ena poizvedba na naslov (do
     * 3. 10. 2026 tri na serijo: prva epizoda, prva zadnje sezone, zadnja) - vljudnost do dodatkov, glej Stremio.zeton.
     * [seVelja]: preverjanje v ozadju velja, dokler uporabnik gleda ta zaslon; preklicano vrne null in ne poslje nicesar.
     */
    private fun preveriEnoto(sk: Jamendo.Skladba, naslovi: List<String>, torrent: Long, seVelja: () -> Boolean = { true }): RazpolozljivostPravila.Izid? {
        val (_, tip, id) = Stremio.razstavi(sk) ?: return null
        if (tip == "movie") return Stremio.razpolozljivo(naslovi, tip, id, torrent, seVelja)
        if (!seVelja()) return null
        val ep = (try { Stremio.epizode(sk) } catch (_: Exception) { emptyList() }).filter { it.sezona >= 1 }
        if (ep.isEmpty()) return null
        return Stremio.razpolozljivo(naslovi, tip, ep.first().id, torrent, seVelja)
    }

    /**
     * V ozadju preveri, katere kartice mreze se da predvajati (stiri naslove hkrati, samo na neomejenem omrezju); cesar
     * noben dodatek nima, izgine. Odgovori dodatkov ostanejo v predpomnilniku, zato preverjen film zacne takoj.
     */
    /** Ko se premor dodatka konca, se preverjanje naslednjih strani nadaljuje samo (naloga 55b). */
    private var poPremoruNarocen = false
    private fun narociPoPremoru(o: OdprtKatalog, naslovi: List<String>) {
        if (poPremoruNarocen) return
        val ob = Stremio.premorKoncaOb(naslovi).takeIf { it > 0L } ?: return
        poPremoruNarocen = true
        glavna.postDelayed({ poPremoruNarocen = false; if (odprtKatalog === o && !isFinishing) narociUmiri() },
            (ob - System.currentTimeMillis() + 1_000L).coerceIn(1_000L, 60 * 60_000L))
    }

    private fun preveriMrezo(o: OdprtKatalog, vsi: List<Jamendo.Skladba>) {
        if (!vOspredju) return
        // Lastne knjiznice dodatkov: vzorec (najvec tri poizvedbe na dodatek) - iz naslovov, ki jih je izris pregledal (tudi
        // skritih), ne iz vseh nalozenih (naloga 55: 66-329 ms pri 5143 naslovih). Knjiznica dalje v seznamu pride na vrsto,
        // ko se okno pomakne do nje - prej je tako ali tako ne bi pokazali.
        meri("vzorci") { o.urejeni.let { u -> vzorciKnjiznice(u.subList(0, o.pregledanih.coerceIn(0, u.size))) } }
        if (!smemPreverjati()) return
        val naslovi = meri("naslovi") { nasloviZaIzris() }
        if (naslovi.isEmpty()) return
        // Vljudno do dodatkov (3. 10. 2026 nas je dodatek zavrnil s "Rate limit exceeded"): ne preverimo vsega kataloga,
        // ampak samo okno (prvih o.zelja kandidatov) in najvec o.proracun naslovov do naslednjega uporabnikovega dejanja.
        // Ce prvih 20 ne da nobene kartice, nehamo (naslednji ne bi bili drugacni); uporabnik lahko nadaljuje z Nalozi vec.
        if (o.preverjenih.get() >= 20 && o.zadetkov.get() == 0) o.proracun = 0
        var smem = minOf(o.proracun, 12 - o.caka.get() - o.ozadje.get())
        if (smem <= 0) return
        val torrent = mejaZaIzris()
        // Mreza je se odprta in Safeer je na zaslonu. Pogoj gre z vprasanjem do zadnjega koraka pred omrezjem.
        val seVelja = { odprtKatalog === o && !isFinishing && vOspredju }
        /** [caka]: na ta naslov caka okno (stevec o.caka); sicer tece v ozadju in zaslona ne zadrzuje (o.ozadje). */
        fun vprasaj(sk: Jamendo.Skladba, k: String, stevec: java.util.concurrent.atomic.AtomicInteger, zastarel: Boolean, blag: Boolean = false) {
            if (!vPreverjanju.add(k)) return
            smem--; o.proracun--
            stevec.incrementAndGet()
            try {
                preverjanje.execute {
                    try {
                        // Mreza je zaprta ali Safeer ni vec na zaslonu: dodatkov ne sprasujemo (vprasanje gre nazaj v proracun).
                        // Enako velja za naslove, ki so ob odhodu ze cakali na zeton dodatka: preklicano preverjanje ne
                        // poslje nicesar in ne pove nicesar - naslova ne skrijemo, ob vrnitvi ga vprasamo znova.
                        if (!seVelja()) { glavna.post { o.proracun++ }; return@execute }
                        val je: Boolean?
                        if (DomPreverjanjePravila.veljaven(k)) {
                            // Javni naslov (IMDb): vprasamo DOM - preverjevalca v Linku ali vrsto te naprave. Eno vprasanje
                            // na naslov in en proracun vprasanj za vse naprave v domu (docs/HOME-VERIFIER.md).
                            val izid = DomPreverjanje.preveri(applicationContext, k, torrent, { prvaEpizoda(sk) }, seVelja)
                            if (izid == DomPreverjanje.Izid.PREKLICANO) { glavna.post { o.proracun++ }; return@execute }
                            je = if (izid == DomPreverjanje.Izid.ODGOVOR) Razpolozljivost.stanjeZa(applicationContext, k, torrent) else null
                        } else {
                            // Naslov zasebnega dodatka: preveri ga ta naprava sama.
                            val r = try { preveriEnoto(sk, naslovi, torrent, seVelja) } catch (_: Exception) { null }
                            if (r == null && !seVelja()) { glavna.post { o.proracun++ }; return@execute }
                            if (r != null) Razpolozljivost.zapomni(applicationContext, k, r)
                            je = r?.velja(torrent)
                        }
                        if (!zastarel) { o.preverjenih.incrementAndGet(); if (je == true) o.zadetkov.incrementAndGet() }
                        // Dodatek ni odgovoril (izpad, ustavljena storitev) ali odgovor za to napravo nicesar ne pove: v tej
                        // mrezi ga ne sprasujemo znova. Neznanega naslova nekaj minut ne kazemo; zastarel odgovor obvelja
                        // naprej (za ure si "ni na voljo" zapomnimo le ob pravem odgovoru).
                        // Naslov naslednje strani, ki je ze na zaslonu (naloga 55b): brez odgovora ostane na zaslonu.
                        if (je == null) { o.neznani.add(k); if (!zastarel && !blag) Razpolozljivost.zacasnoNi(k) }
                    } finally {
                        vPreverjanju.remove(k)
                        stevec.decrementAndGet()
                        glavna.post { if (odprtKatalog === o && !isFinishing) narociUmiri() }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { vPreverjanju.remove(k); stevec.decrementAndGet() }
        }
        /** Vsi dodatki, ki bi jih za naslov vprasali, so v premoru (omejitev poizvedb): zdaj ga ne moremo preveriti. */
        fun vPremoru(sk: Jamendo.Skladba) = Stremio.razstavi(sk)?.let { r -> Stremio.vprasaniVPremoru(naslovi, r.second, r.third) } == true
        // 1) Okno: naslovi, ki jih se ne poznamo - samo nanje uporabnik caka (oknoMreze naslove v premoru ze izpusti).
        val okno = oknoMreze(o, vsi).naslovi
        val naZaslonu = o.narisani.toHashSet()
        var cakaPremor = false
        for ((i, sk) in okno.withIndex()) {
            if (smem <= 0) return
            val k = kljucRazpolozljivosti(sk) ?: continue
            if (Razpolozljivost.znano(this, k) != null || k in vPreverjanju || k in o.neznani || Stremio.izLastneKnjiznice(sk)) continue
            // Naslov naslednje strani (ze na zaslonu, naloga 55b), ki ga zdaj ne moremo preveriti (dodatek v premoru):
            // pocaka konec premora, ne izgine in ne steje kot »dodatek ni odgovoril«.
            val blag = i >= o.strogoDo || sk.id in naZaslonu
            if (blag && vPremoru(sk)) { cakaPremor = true; continue }
            vprasaj(sk, k, o.caka, zastarel = false, blag = blag)
        }
        if (cakaPremor) narociPoPremoru(o, naslovi)
        // Delo v ozadju nikoli ne stoji pred oknom: zacne se sele, ko okno ne caka vec, in ima najvec dve poizvedbi
        // hkrati (vsaka caka na zeton dodatka - nova poizvedba okna bi sicer stala v vrsti za njimi).
        if (o.caka.get() > 0) return
        // Naslovov ZA oknom ne preverjamo vnaprej: dodatek steje poizvedbe po domacem naslovu, ne po napravi (4. 10. 2026
        // je dom omejil), in vsako vprasanje na zalogo je vprasanje manj za uporabnika, ki film res izbere.
        // 2) Na zalogo - nihce ne caka nanje, preverjevalec doma jih vprasa, ko ima cas (DomPreverjanje.naZalogo):
        //    zastareli odgovori na ze prikazanem delu seznama (vidni »je« in skriti »ni«) in naslovi ZA oknom, ki jih
        //    se ne poznamo. Ko uporabnik pride do njih (drsenje, Nalozi vec, naslednji obisk), so ze preverjeni - in
        //    to stane eno vprasanje na dom, pocasi in samo, kadar nihce ne caka (4. 10. 2026: preverjanje vnaprej na
        //    vsaki napravi posebej je dom pripeljalo cez mejo dodatka).
        val naZalogo = ArrayList<String>()
        var osvezenih = 0
        val doTu = okno.lastOrNull()?.let { zadnji -> o.urejeni.indexOfFirst { it.id == zadnji.id } } ?: -1
        for (sk in (if (doTu >= 0) o.urejeni.take(doTu + 1) else okno)) {
            if (!zastarel(sk)) continue
            val k = kljucRazpolozljivosti(sk) ?: continue
            if (DomPreverjanjePravila.veljaven(k)) { naZalogo += k; continue }
            // Naslov zasebnega dodatka osvezi ta naprava sama, po nekaj na obisk.
            if (smem <= 0 || osvezenih >= OSVEZI_NA_OBISK || o.ozadje.get() >= OZADJE_HKRATI) continue
            if (k in vPreverjanju || k in o.neznani || vPremoru(sk)) continue
            osvezenih++
            vprasaj(sk, k, o.ozadje, zastarel = true)
        }
        val vOknu = okno.mapTo(HashSet()) { it.id }
        for (sk in vsi) {
            if (naZalogo.size >= DomPreverjanjePravila.NAJVEC_NAPREJ) break
            if (sk.id in vOknu || !nepreverjen(sk)) continue
            val k = kljucRazpolozljivosti(sk) ?: continue
            if (DomPreverjanjePravila.veljaven(k) && k !in o.neznani) naZalogo += k
        }
        if (naZalogo.isNotEmpty()) DomPreverjanje.naZalogo(applicationContext, naZalogo, torrent)
    }

    /** Prva epizoda serije po katalogu kartice (po njej preverimo serijo kot celoto); null, ce katalog serije ne pozna. */
    private fun prvaEpizoda(sk: Jamendo.Skladba): String? =
        (try { Stremio.epizode(sk) } catch (_: Exception) { emptyList() }).firstOrNull { it.sezona >= 1 }?.id

    /**
     * Police (domaca stran, Zate, zadetki): prvih nekaj naslovov iz dodatkov preverimo v ozadju. Zaslona pod prsti ne
     * premikamo - cesar noben dodatek nima, ob naslednjem risanju ne bo vec (do takrat dotik da kratko obvestilo).
     */
    private fun preveriPolico(vsi: List<Jamendo.Skladba>) {
        if (odprtKatalog != null) return        // mrezo preverja preveriMrezo
        vzorciKnjiznice(vsi)
        val enote = vsi.asSequence().filter { Stremio.jeEnota(it) && kljucRazpolozljivosti(it) != null }.take(12).toList()
        if (enote.isEmpty()) return
        val omrezje = getSystemService(android.net.ConnectivityManager::class.java)
        if (omrezje == null || omrezje.activeNetwork == null || omrezje.isActiveNetworkMetered) return
        val naslovi = stremioNaslovi()
        if (naslovi.isEmpty()) return
        val torrent = torrentMeja()
        torrentPrej = nacinTorrenta(torrent)
        Razpolozljivost.pripravi(this, dodatkiZaRazpolozljivost(), torrent)
        val naZalogo = ArrayList<String>()
        for (sk in enote) {
            val k = kljucRazpolozljivosti(sk) ?: continue
            if (Razpolozljivost.stanje(this, k) != null || Stremio.izLastneKnjiznice(sk)) continue
            // Javni naslov: preveri ga preverjevalec doma, ko nihce ne caka (na polici kartica ne caka na odgovor).
            if (DomPreverjanjePravila.veljaven(k)) { naZalogo += k; continue }
            if (!vPreverjanju.add(k)) continue
            try {
                preverjanje.execute {
                    try {
                        // Samo dokler je medijski center na zaslonu (glej vOspredju).
                        val seVelja = { !isFinishing && vOspredju }
                        if (!seVelja()) return@execute
                        val r = try { preveriEnoto(sk, naslovi, torrent, seVelja) } catch (_: Exception) { null }
                        if (r != null) Razpolozljivost.zapomni(applicationContext, k, r)
                    } finally { vPreverjanju.remove(k) }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { vPreverjanju.remove(k) }
        }
        if (naZalogo.isNotEmpty()) DomPreverjanje.naZalogo(applicationContext, naZalogo, torrent)
    }

    /** Epizode odprtega seznama preverimo v ozadju; [obNi] (glavna nit): epizode noben dodatek nima. */
    private fun preveriEpizode(tip: String, ep: List<Stremio.Epizoda>,
                               /** Naloga 132: id epizode po IMDb (tt...:S:E) ali null - preverimo ga, preden epizodo skrijemo. */
                               imdbEpizode: (Stremio.Epizoda) -> String? = { null },
                               obNi: (Stremio.Epizoda) -> Unit) {
        val naslovi = stremioNaslovi()
        if (naslovi.isEmpty()) return
        val torrent = torrentMeja()
        for (e in ep.take(16)) {
            val k = Razpolozljivost.kljuc(tip, e.id)
            val alt = imdbEpizode(e)
            val kAlt = alt?.let { Razpolozljivost.kljuc(tip, it) }
            val stanjeAlt = kAlt?.let { Razpolozljivost.stanje(this, it) }
            if (e.tokovi.isNotEmpty()) continue
            val stanje = Razpolozljivost.stanje(this, k)
            if (stanje != null && !SerijePravila.preveriPoImdb(stanje, stanjeAlt, alt != null)) continue
            preverjanjeEpizod.execute {
                val seVelja = { !isFinishing && vOspredju }
                fun preveri(kljuc: String, id: String): Boolean? = if (DomPreverjanjePravila.veljaven(kljuc)) {
                    if (DomPreverjanje.preveri(applicationContext, kljuc, torrent, null, seVelja) == DomPreverjanje.Izid.ODGOVOR) Razpolozljivost.stanjeZa(applicationContext, kljuc, torrent) else null
                } else {
                    val r = try { Stremio.razpolozljivo(naslovi, tip, id, torrent, seVelja) } catch (_: Exception) { null }
                    if (r != null) Razpolozljivost.zapomni(applicationContext, kljuc, r)
                    r?.velja(torrent)
                }
                val je = stanje ?: preveri(k, e.id)
                // Pri dodatku je ni: preden jo skrijemo, se po IMDb (dodatki s torrenti poznajo samo tega).
                val poImdb = if (alt != null && kAlt != null && SerijePravila.preveriPoImdb(je, stanjeAlt, true)) preveri(kAlt, alt) else stanjeAlt
                if (SerijePravila.skrijEpizodo(je, poImdb, alt != null)) glavna.post { obNi(e) }
            }
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        zadnjiDotik = android.os.SystemClock.uptimeMillis()
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Dodatki kanala v zivo niso dali toka: kartica in njene razlicice so do jutri mrtve ([MrtviKanali]). Naloga 93: razlicice
     * seznama IPTV ne ([KanaliPravila.mrtveObNeuspehuDodatka]) - izpad dodatka jih je prej skril za 24 h.
     */
    private fun oznaciMrtveRazliciceDodatka(sk: Jamendo.Skladba) {
        val razlicice = zadnjeRazlicice.takeIf { sk.id in it }.orEmpty()
        val mrtve = KanaliPravila.mrtveObNeuspehuDodatka(sk.id, razlicice) { it.startsWith(IptvSeznami.PREDPONA) }
        android.util.Log.i("SafeerOsMedia", "kanal dodatka ni dosegljiv: oznacenih mrtvih ${mrtve.size}, razlicic seznama ostane ${(razlicice + sk.id).distinct().size - mrtve.size}")
        mrtve.forEach { MrtviKanali.zapomni(this, it, NedelujocePravila.NI_TOKA, sk.naslov) }
    }

    /**
     * Film ali epizoda se zacne TAKOJ z najboljsim tokom za to napravo (TokIzbira: zaslon, dekodirniki slike in zvoka,
     * hiter zacetek) - brez seznama in brez cakanja na izbiro (lastnik, 2. 10. 2026). Ce tok ne stece, storitev sama
     * poskusi naslednje. Seznam virov ostane rocna moznost (dolg pritisk na kartico > Izberi vir) in izhod v sili,
     * kadar dodatki ponudijo le zunanje povezave (napovednik ipd.).
     */
    private fun izberiTok(sk: Jamendo.Skladba, naslov: String, vsiTokovi: List<Stremio.Tok>, rocno: Boolean = false,
                          /** Naloga 130 + lastnik 25. 10.: kaj je takoj predvajljivo ([predpomnjeniZa]): ⚡ torrenti, pripravljen Usenet. */
                          pripravljeno: Pripravljeno = Pripravljeno(),
                          /** Kaj storiti, ce se vsebine ne da predvajati (epizoda: nazaj na seznam epizod); privzeto: kartica izgine. */
                          obNeuspehu: (() -> Unit)? = null) {
        // Pregled A1: nova izbira toka - prejsnje cakanje na debrid/Usenet ne odpre nicesar vec.
        prekiniDebrid()
        // Oznaka kakovosti (lastnik 28. 10. 2026): tokovi zadnjega predvajanja izboljsajo najboljso znano.
        // Serija (epizoda ima id serije): oznaka kartice je kakovost zadnje predvajane epizode.
        Stremio.razstavi(sk)?.third?.let { zabeleziKakovost(it, vsiTokovi, Stremio.jeSerija(sk)) }
        // Diagnostika razvijalca ([Webtor.samoWebtor], samo prek adb, z rokom): pot, kjer debrid toka nima - brez ⚡ pri
        // debridu, neposrednih povezav in Useneta; torrenti ostanejo za ujemanje izdaje Webtor. Izmerjeno 25. 10. 2026: Sintel in
        // Tears of Steel imata neposredno povezavo, zato rezerve Webtor brez tega ni mogoce izmeriti. Pregled 26, T3: viden znak,
        // brez TorBoxa v ozadju in brez poti mimo Webtorja (ce Webtor ne predvaja, se izbira konca).
        val samoWebtor = Webtor.samoWebtor(this)
        if (samoWebtor) {
            android.util.Log.i("SafeerOsMedia", "tokovi: diagnostika samo Webtor")
            // Pregled Grok 32, G5 velja tudi za diagnostiki Webtor in Usenet: Toast samo na glavni niti.
            runOnUiThread { Toast.makeText(this, R.string.os_webtor_diagnostika, Toast.LENGTH_LONG).show() }
        }
        // Diagnostika razvijalca ([Usenet.samoUsenet], samo prek adb, z rokom; lastnik 25. 10. 2026: meritev Useneta samo po
        // samodejni poti): samodejna izbira vzame samo Usenet - brez ⚡ torrentov, neposrednih povezav in Webtorja; ce Usenet
        // ne predvaja, se izbira konca (brez poti mimo). Viden znak kot pri Webtorju.
        val samoUsenet = !samoWebtor && !rocno && Usenet.samoUsenet(this)
        if (samoUsenet) {
            android.util.Log.i("SafeerOsMedia", "tokovi: diagnostika samo Usenet")
            runOnUiThread { Toast.makeText(this, R.string.os_usenet_diagnostika, Toast.LENGTH_LONG).show() }
        }
        // Diagnostika razvijalca ([Diagnostika.samoP2p], samo prek adb, z rokom; lastnik 25. 10. 2026: Z3 v zivo samo po
        // samodejni poti in samo z odprtimi filmi CC): samodejna izbira vzame najboljsi torrent na TEJ napravi - brez
        // neposrednih povezav, debrida, Useneta, Webtorja in pomocnikov v Linku. Opozorilo pred rojem ostane.
        // Zdruzitev z vejo webtor-popravek: diagnostike se izkljucujejo - samo P2P velja le, ce nista vklopljeni samo Webtor
        // ali samo Usenet (sicer bi se filtri izkljucili ali pa bi se pot tiho prevesila v roj); getprop se tedaj ne klice.
        val samoP2p = !samoWebtor && !samoUsenet && !rocno && Diagnostika.samoP2p(this)
        if (samoP2p) {
            android.util.Log.i("SafeerOsMedia", "tokovi: diagnostika samo P2P")
            // Pregled Grok 32, G5: Toast samo na glavni niti. izberiTok se klice prek glavna.post (preverjeno: vsi klicatelji),
            // runOnUiThread to zagotovi tudi za morebitnega klicatelja z delovne niti (Toast tam vrze izjemo).
            runOnUiThread { Toast.makeText(this, R.string.os_p2p_diagnostika, Toast.LENGTH_LONG).show() }
        }
        val predpomnjeni = if (samoWebtor || samoUsenet) emptySet<String>() else pripravljeno.predpomnjeni
        // Webtor rezerva (25. 10. 2026): tokovi Webtor [⚡TB] (podvajajo nas TorBox) in [P2P] (posnetek z oglasom za paket)
        // izpadejo takoj ([WebtorPravila.skrij]).
        val imamoTorBox = Debrid.aktivna(this) != null && DebridPravila.TORBOX in Debrid.zKljucem(this)
        val tokovi = vsiTokovi.filterNot { it.vir == WebtorPravila.VIR && WebtorPravila.skrij(WebtorPravila.vrstaIz(it.oznakaVira), imamoTorBox) }
            .filter { !samoWebtor || it.vir == WebtorPravila.VIR || it.vrsta == "torrent" }
            .filter { !samoUsenet || it.vrsta == UsenetPravila.VRSTA }
        if (samoUsenet && tokovi.isEmpty()) {
            // Brez izdaj Usenet (indeksator jih nima) se diagnostika konca tu - vsebina ne izgine s seznama.
            android.util.Log.i("SafeerOsMedia", "tokovi: diagnostika samo Usenet - indeksator ni dal izdaj")
            // Pregled Grok 31, H2: viden konec diagnostike (prej samo dnevnik); klicatelj izve za neuspeh.
            Toast.makeText(this, R.string.os_usenet_diagnostika_ni_izdaj, Toast.LENGTH_LONG).show()
            obNeuspehu?.invoke()
            return
        }
        // Brez predvajljivega toka ni okna in ni seznama povezav (prosnje za donacijo, Discord, "No streams found" so ze
        // izlocene v Stremio.tok): kratko obvestilo, vsebina pa izgine s seznama (lastnik, 2. 10. 2026).
        // Torrent steje le, ce ga ta naprava zmore: prek pomocnika v Linku ali sama, ce datoteka gre na njen prostor.
        val meja = torrentMeja()
        fun gre(t: Stremio.Tok) = t.vrsta != "torrent" || Stremio.torrentGre(t, meja)
        // Tok, ki je samo posnetek na YouTubu: pri filmu ali seriji je to napovednik (ne predvajamo ga sami), pri videospotu,
        // kanalu z videi in drugi vsebini pa vsebina sama (lastnik, 5. 10. 2026).
        val posnetek = if (sk.mediaType.equals("MusicVideo", ignoreCase = true) || Stremio.razredEnote(sk) !in setOf(Stremio.FILM, Stremio.SERIJA))
            tokovi.firstOrNull { it.vrsta == "napovednik" } else null
        if (posnetek == null && tokovi.none { Stremio.jePredvajljiv(it) && gre(it) }) {
            // Naloga 90b (lastnik: sporocila za nedelujoce kanale ne rabimo): kanal v zivo, za katerega noben dodatek v roku ne
            // da toka, se tiho oznaci mrtev ([MrtviKanali], 24 h) in izgine iz mreze. Razlicice kartice so ze v isti poizvedbi
            // ([razresiStremio]), zato predvajanje naslednje razlicice pride samo; tu so mrtve vse razlicice dodatkov (93: seznama IPTV ne). Placljiv dostop (79) ima svoje sporocilo.
            if (obNeuspehu == null && Stremio.jeVZivo(sk) && !Stremio.samoObvestilaKanala(sk)) {
                android.util.Log.i("SafeerOsMedia", "kanal ni dosegljiv: tokov=${tokovi.size}, dodatek ni odgovoril=${Stremio.dodatekNiOdgovoril()}")
                oznaciMrtveRazliciceDodatka(sk)
                return
            }
            // Dodatek ni odgovoril (omejuje poizvedbe, izpad, omrezje): to ni "ni na voljo". Naslov ostane na zaslonu,
            // uporabnik izve, da naj poskusi znova - prej je film ob vsakem izpadu dodatka "izginil".
            if (Stremio.dodatekNiOdgovoril()) {
                Toast.makeText(this, R.string.os_media_dodatek_ne_odgovarja, Toast.LENGTH_LONG).show()
                return
            }
            // Kanal ali postaja v zivo brez prenosa (dodatek ga nasteva, toka zanj nima): povemo in ga do jutri ne kazemo.
            // Prej: kratko obvestilo, ploscica je ostala in dotik ni naredil nicesar (izmerjeno 5. 10. 2026).
            // Naloga 79: dodatek je dal samo obvestilo (placljiv dostop) - kanal ni mrtev; povemo zakaj, povezave na placilo ne.
            if (obNeuspehu == null && Stremio.jeVZivo(sk) && Stremio.samoObvestilaKanala(sk)) {
                // Naloga 79b: isto okno kot »Odkleni pri dodatku« v Mojih virih (ce je dodatek dal povezavo).
                val osnova = Stremio.razstavi(sk)?.first.orEmpty()
                if (Stremio.povezavaOdklepa(osnova) != null) odkleniPriDodatku(osnova)
                else Toast.makeText(this, R.string.os_mediji_tokovi_narocnina, Toast.LENGTH_LONG).show()
                // Naloga 120: kanal, ki ga dodatek da samo za placilo, izgine s seznama (Stremio.neodklenjena).
                osveziNaMestu()
                return
            }
            if (obNeuspehu == null && (Stremio.jeVZivo(sk) || Stremio.razredEnote(sk) == Stremio.RADIO)) {
                oznaciMrtveRazliciceDodatka(sk)
                return
            }
            Toast.makeText(this, getString(R.string.os_media_ni_na_voljo, naslov), Toast.LENGTH_SHORT).show()
            // Dodatek je odgovoril, toka pa nima: steje tudi kot dokaz o njegovi knjiznici ([ZaupanjeKnjiznic]).
            if (obNeuspehu != null) obNeuspehu() else { Stremio.zabeleziKnjiznico(sk, false); oznaciNiNaVoljo(sk) }
            return
        }
        oznaciNaVoljo(sk)
        // Glasba in radio iz dodatka ostaneta zvok (predvajalnik za zvok, v ozadju), vse ostalo je video.
        fun skladbaToka(t: Stremio.Tok) = sk.copy(id = sk.id + "#" + t.url.hashCode(), naslov = naslov, zvok = t.url, povezava = t.url, video = sk.video)
        // Webtor rezerva: tok Webtor za isto izdajo kot torrent (dolocen spodaj, ko so tokovi urejeni) in ze poskuseni.
        var webtorZa: (Stremio.Tok) -> Stremio.Tok? = { null }
        // Datoteka toka Webtor (dolocena spodaj, z nasimi torrenti) - za dnevnik sonde.
        var datotekaWebtorZa: (Stremio.Tok) -> WebtorPravila.Datoteka? = { null }
        val poskuseniWebtor = HashSet<String>()
        // Pregled N3: vse sonde Webtor te izbire skupaj najvec 15 s in najvec 2 ([WebtorPravila.rokSonde]).
        var webtorZacetek: Long? = null
        var webtorPoskusov = 0
        fun webtorRok() = WebtorPravila.rokSonde(webtorZacetek, android.os.SystemClock.elapsedRealtime(), webtorPoskusov)
        // Krog sond Webtor (do [WebtorPravila.VZPOREDNO] kandidatov hkrati) v skupnem roku; false = ne poskusimo (rok porabljen
        // ali so vsi ze poskuseni). [predvajaj] dobi izbranega kandidata.
        fun sondaWebtor(kandidati: List<Stremio.Tok>, predvajaj: (Stremio.Tok) -> Unit, naprej: () -> Unit): Boolean {
            val rok = webtorRok()
            val novi = kandidati.filter { it.url !in poskuseniWebtor }.distinctBy { it.url }
            if (rok <= 0L || novi.isEmpty()) return false
            novi.forEach { poskuseniWebtor.add(it.url) }
            if (webtorZacetek == null) webtorZacetek = android.os.SystemClock.elapsedRealtime()
            webtorPoskusov++
            predvajajWebtor(novi, rok, novi.map { "datoteka=${datotekaWebtorZa(it)} h=${it.hash.take(10)} idx=${it.datoteka}" }, predvajaj, naprej,
                // Skupno pravilo v4 (naloga 21 B1): krog, ki je vsebino le hitro zavrnil (.srt pri YTS), ni poskus.
                neStejeKotPoskus = { webtorPoskusov-- })
            return true
        }
        fun odpri(t: Stremio.Tok, rezerve: List<Stremio.Tok> = emptyList()) {
            when (t.vrsta) {
                "url" -> {
                    // Glave, ki jih tok zahteva (proxyHeaders), veljajo za gostitelja toka, dokler ne pride drug tok z istega.
                    SpletniVir.zapomniGlaveToka(t.url, t.glave)
                    val r = skladbaToka(t)
                    GlasbaStoritev.predvajajZRezervami(this, r, rezerve.map { skladbaToka(it) to it.glave })
                    if (r.video) { nadaljujKoPripravljen(r); startActivity(Intent(this, PredvajanjeActivity::class.java)) }
                }
                "torrent" -> {
                    // Grok 9, Z2 (lastnik 23. 10.: brez debrida opozorilo, PREDEN se naprava poveze z rojem): tudi torrent iz
                    // kataloga in povratek po napaki debrida gresta v roj sele po opozorilu ([opozoriPredRojem]).
                    val brez = { opozoriPredRojem(t.url) { torrentPrekRacunalnika(sk, naslov, t.url, t.datoteka,
                        velikost = (TokIzbira.opisi(t.ime + " " + t.opis).gb * 1024.0 * 1024.0 * 1024.0).toLong()) } }
                    fun prekTorrenta() {
                        // Naloga 119: s kljucem TorBox gre torrent prek TorBoxa (zasebnost, hitrejsi tok); brez kljuca obstojeca pot.
                        if (Debrid.aktivna(this) != null) prekDebrida(t.url, t.datoteka, { cdn -> odpri(t.copy(vrsta = "url", url = cdn, glave = emptyMap())) }, brez)
                        else {
                            // Pregled A5: kljuc je vpisan, a neberljiv (Keystore) - ne tiho na neposredni torrent (naslov IP).
                            // Varovalo racunov: enako, ce kljuc ne velja ali je racun zaklenjen (Safeer ga ne klice).
                            val neberljiva = Debrid.neberljiva(this) ?: Debrid.ustavljena(this)
                            if (neberljiva != null) kljucNeberljiv(neberljiva, brez) else brez()
                        }
                    }
                    // Webtor rezerva: izdaja, ki je nas debrid nima pripravljene (⚡), gre najprej prek Webtorja (ista izdaja:
                    // infoHash + fileIdx) - brez cakanja na prenos; ce Webtor ne da toka (402/404/429, prvi bajt po 15 s), kot
                    // doslej (TorBox s cakanjem). Ob zacetku toka Webtor gre torrent v ozadju v TorBox (naslednjic ⚡).
                    val w = if (TorBoxPravila.hashIzMagneta(t.url)?.lowercase() in predpomnjeni) null else webtorZa(t)
                    val zacel = w != null && sondaWebtor(listOf(w), { x -> odpri(x); webtorVTorBox(t.url, false) }, {
                        Toast.makeText(this, R.string.os_webtor_rezerva, Toast.LENGTH_SHORT).show(); prekTorrenta()
                    })
                    if (!zacel) prekTorrenta()
                }
                // Naloga 141: Usenet - NZB od indeksatorja (naprava), prenos in tok TorBox; brez poti »brez zasebnosti«.
                UsenetPravila.VRSTA -> prekDebrida(t.url, t.datoteka, { cdn -> odpri(t.copy(vrsta = "url", url = cdn, glave = emptyMap())) }, null,
                    usenetIme = t.opis.substringBefore('\n'))
                // Napovednik (samo pri rocni izbiri vira): igra v nasem predvajalniku, ne v brskalniku.
                else -> razresiSplet(SpletniVir.enota(t.url, naslov, "", sk.slika, true))
            }
        }
        val opis = { t: Stremio.Tok -> t.ime + " " + t.opis }
        // Rok veljavnosti povezave steje pri vrstnem redu (TokIzbira.veljaSe): potekla povezava je zadnja.
        val zdajS = System.currentTimeMillis() / 1000
        // Jezik zvoka (lastnik, 10. 10. 2026): film v anglescini, slovenski film v slovenscini - sinhronizacija v tujem
        // jeziku (npr. brazilska »Dublado«) ne sme biti prva samo zato, ker je v 4K.
        val zelen = if (sk.video) TokIzbira.zeleniJezik(sk.language) else ""
        // Naloga 133: kar presega izmerjeno hitrost povezave (70 %), gre za tokove, ki tecejo - brez meritve red ostane.
        val hitrost = HitrostPovezave.hitrost(this)
        val minut = KakovostPravila.minut(sk.season > 0 || sk.episode > 0)
        fun zaHitrost(l: List<Stremio.Tok>) = KakovostPravila.urediZaHitrost(l.map { TokIzbira.opisi(opis(it)).gb }, minut, hitrost).map { l[it] }
        // Webtor rezerva: tok Webtor je tudi »url«, a ni neposredna povezava - ima svoje mesto v vrstnem redu (spodaj).
        val neposredni = zaHitrost(TokIzbira.uredi(tokovi.filter { it.vrsta == "url" && it.vir != WebtorPravila.VIR }, opis, zmoznostiNaprave, { it.url }, zdajS, zelen, sk.language))
        val torrentiPoKakovosti = zaHitrost(TokIzbira.uredi(tokovi.filter { it.vrsta == "torrent" && gre(it) }, opis, zmoznostiNaprave,
            zelenJezik = zelen, izvirni = sk.language))
        if (hitrost > 0) android.util.Log.i("SafeerOsMedia", "kakovost za povezavo ${hitrost / 1_000_000} Mb/s: " +
            "${torrentiPoKakovosti.count { KakovostPravila.ustreza(TokIzbira.opisi(opis(it)).gb, minut, hitrost) }} od ${torrentiPoKakovosti.size} torrentov ustreza")
        // Naloga 130: torrenti, ki jih ima debrid storitev pripravljene (⚡), so prvi - znotraj obeh skupin ostane red kakovosti.
        val hashiTorrentov = torrentiPoKakovosti.map { TorBoxPravila.hashIzMagneta(it.url) }
        val torrenti = DebridPravila.predpomnjeniNaprej(hashiTorrentov, predpomnjeni).map { torrentiPoKakovosti[it] }
        fun pripravljen(t: Stremio.Tok) = t.vrsta == "torrent" && TorBoxPravila.hashIzMagneta(t.url)?.lowercase() in predpomnjeni
        // Naloga 141: Usenet (TorBox) po kakovosti.
        val usenet = zaHitrost(TokIzbira.uredi(tokovi.filter { it.vrsta == UsenetPravila.VRSTA }, opis, zmoznostiNaprave,
            zelenJezik = zelen, izvirni = sk.language))
        // Webtor rezerva: tokovi Webtor po kakovosti, nato v vrstnem redu nasih torrentov ([WebtorPravila.vrstniRed]: ista
        // izdaja, kot bi jo izbrali sami, je prva).
        val webtorPoKakovosti = zaHitrost(TokIzbira.uredi(tokovi.filter { it.vir == WebtorPravila.VIR }, opis, zmoznostiNaprave,
            zelenJezik = zelen, izvirni = sk.language))
        fun kljucTorrenta(t: Stremio.Tok) = WebtorPravila.kljuc(TorBoxPravila.hashIzMagneta(t.url), t.datoteka)
        // Izmerjeno 25. 10. 2026: datoteka toka Webtor je `idx` v JWT ([Stremio] -> Tok.datoteka; -2 = neznana).
        fun kljucWebtor(t: Stremio.Tok) = WebtorPravila.kljucToka(t.hash, t.datoteka)
        // Kaj bo /resolve predvajal (video, najvecji video, negotovo - npr. naslovnica, ni video): ime toka ali nas tok
        // za isti hash in indeks ([WebtorPravila.datoteka]).
        val nasiTorrenti = tokovi.filter { it.vrsta == "torrent" }
        fun datotekaWebtor(w: Stremio.Tok): WebtorPravila.Datoteka {
            val k = kljucWebtor(w) ?: return WebtorPravila.Datoteka.NEGOTOVA
            val istiHash = nasiTorrenti.filter { TorBoxPravila.hashIzMagneta(it.url)?.lowercase() == k.hash }
            val nas = istiHash.firstOrNull { it.datoteka == k.idx } ?: istiHash.firstOrNull()
            return WebtorPravila.datoteka(k.idx, w.imeDatoteke, nas?.datoteka, nas?.imeDatoteke.orEmpty())
        }
        val parWebtor = webtorPoKakovosti.map { kljucWebtor(it) to WebtorPravila.vrstaIz(it.oznakaVira) }
        val datotekeWebtor = webtorPoKakovosti.map { datotekaWebtor(it) }
        fun datotekaOd(w: Stremio.Tok) = datotekeWebtor.getOrNull(webtorPoKakovosti.indexOf(w)) ?: datotekaWebtor(w)
        datotekaWebtorZa = { w -> datotekaOd(w) }
        val webtor = WebtorPravila.vrstniRed(torrentiPoKakovosti.map { kljucTorrenta(it) }, parWebtor, imamoTorBox, datoteke = datotekeWebtor)
            .map { webtorPoKakovosti[it] }
        webtorZa = { t -> WebtorPravila.zaIzdajo(kljucTorrenta(t), parWebtor, imamoTorBox, datotekeWebtor).takeIf { it >= 0 }?.let { webtorPoKakovosti[it] } }
        if (webtorPoKakovosti.isNotEmpty()) android.util.Log.i("SafeerOsMedia", "tokovi: Webtor ${webtor.size} od ${webtorPoKakovosti.size}, ista izdaja kot nas torrent: " +
            "${webtor.count { w -> kljucWebtor(w) != null && torrentiPoKakovosti.any { kljucTorrenta(it) == kljucWebtor(w) } }}, datoteke: " +
            WebtorPravila.Datoteka.values().joinToString(" ") { d -> "$d=${datotekeWebtor.count { it == d }}" })
        // [⚡WT] (Webtor ga ima pripravljenega) sodi med »takoj predvajljivo« (lastnik: najhitrejsi zacetek najprej).
        val webtorTakoj = WebtorPravila.vrstniRed(torrentiPoKakovosti.map { kljucTorrenta(it) }, parWebtor, imamoTorBox, samoTakoj = true,
            datoteke = datotekeWebtor).map { webtorPoKakovosti[it] }
        // Diagnostika na zahtevo razvijalca (`setprop log.tag.SafeerWebtorMeri DEBUG`): meritev prvih kandidatov vzporedno, brez
        // predvajanja - cas do prvega bajta pri prenosu in vsebina, ki jo /resolve res vrne (tudi za NEGOTOVA, ki jih ne izberemo).
        if (Webtor.meritev(this) && webtorPoKakovosti.isNotEmpty()) {
            val kandidati = (webtor + webtorPoKakovosti.filter { it !in webtor }).take(16)
            Webtor.meri(this, kandidati.map { w ->
                val k = kljucWebtor(w)
                Triple("${w.oznakaVira} ${datotekaOd(w)} h=${k?.hash?.take(10)} idx=${k?.idx} ${TokIzbira.opisi(opis(w)).let { "${it.visina}p ${"%.2f".format(java.util.Locale.ROOT, it.gb)} GB" }} " +
                    "sejalcev=${TokIzbira.sejalci(opis(w))} ime=${w.imeDatoteke.substringAfterLast('.', "-").take(5)}", w.url, TokIzbira.opisi(opis(w)).gb)
            })
        }
        fun odpriWebtor(kandidati: List<Stremio.Tok>, naprej: () -> Unit) {
            val zacel = sondaWebtor(kandidati, { w ->
                val k = kljucWebtor(w)
                val nas = if (k == null) null else torrentiPoKakovosti.firstOrNull { kljucTorrenta(it) == k }
                odpri(w)
                if (k != null && !samoWebtor) webtorVTorBox(nas?.url ?: WebtorPravila.magnet(k.hash, w.sledilniki), k.hash in predpomnjeni)
            }, naprej)
            if (!zacel) naprej()
        }
        // Samodejna izbira brez Useneta (kot pred nalogo 141); true = nekaj se predvaja.
        fun samodejnoBrezUseneta(): Boolean {
            // Naloga 130: z aktivno debrid storitvijo gre najboljsi pripravljen torrent pred neposrednimi povezavami.
            val k = DebridPravila.samodejniTorrent(Debrid.aktivna(this), hashiTorrentov, predpomnjeni)
            if (k >= 0) {
                android.util.Log.i("SafeerOsMedia", "tokovi: pripravljen pri debridu ${predpomnjeni.size} od ${torrentiPoKakovosti.size} torrentov; " +
                    "izbran=${TokIzbira.opisi(opis(torrentiPoKakovosti[k]))}")
                odpri(torrentiPoKakovosti[k]); return true
            }
            // Povezave, ki je po lastnem zapisu ze potekla, sploh ne poskusamo (streznik bi odgovoril 403 in uporabnik
            // bi cakal na naslednjo) - razen ce so take vse: ura naprave je lahko napacna, zato jih takrat vseeno poskusimo.
            val zivi = neposredni.filter { !TokIzbira.potekla(it.url, zdajS) }.ifEmpty { neposredni }
            android.util.Log.i("SafeerOsMedia", "tokovi: neposredni=${neposredni.size} (poteklih ${neposredni.size - zivi.size}), torrenti=${torrenti.size}, zunanji=${tokovi.size - neposredni.size - torrenti.size}; " +
                "izbran=${(zivi.firstOrNull() ?: torrenti.firstOrNull())?.let { TokIzbira.opisi(opis(it)) }}, " +
                "zvok=${(zivi.firstOrNull() ?: torrenti.firstOrNull())?.let { TokIzbira.jezikZvoka(opis(it)) }}/$zelen, rok=${zivi.firstOrNull()?.let { TokIzbira.veljaSe(it.url, zdajS) }}")
            if (zivi.isNotEmpty()) { odpri(zivi.first(), zivi.drop(1).take(REZERVNIH_TOKOV)); return true }
            if (torrenti.isNotEmpty()) { odpri(torrenti.first()); return true }
            if (posnetek != null) { odpri(posnetek); return true }
            return false
        }
        if (samoP2p) {
            val t = torrentiPoKakovosti.firstOrNull()
            if (t == null) {
                android.util.Log.i("SafeerOsMedia", "tokovi: diagnostika samo P2P - ni torrenta, ki gre na to napravo")
                // Pregled Grok 32, G2: prej tih konec (samo dnevnik) - viden razlog in klicatelj izve za neuspeh (epizoda: nazaj
                // na seznam epizod). Druge poti ni (diagnostika), kartica pa ne izgine (oznaciNiNaVoljo se ne klice).
                runOnUiThread { Toast.makeText(this, R.string.os_p2p_diagnostika_ni_torrenta, Toast.LENGTH_LONG).show() }
                obNeuspehu?.invoke()
                return
            }
            android.util.Log.i("SafeerOsMedia", "tokovi: diagnostika samo P2P; izbran=${TokIzbira.opisi(opis(t))}, sejalcev=${TokIzbira.sejalci(opis(t))}")
            opozoriPredRojem(t.url) { torrentBrezRacunalnika(sk, naslov, t.url, t.datoteka,
                (TokIzbira.opisi(t.ime + " " + t.opis).gb * 1024.0 * 1024.0 * 1024.0).toLong(), "", samoNaprava = TA_NAPRAVA) }
            return
        }
        if (!rocno) {
            // Naloga 141 + lastnik 24.-25. 10. 2026 (07:25): Usenet je prvi, ce ga uporabnik ima (najboljsa kakovost in hitrost,
            // placa ga), a uporabnik ne caka na prenos, ce je kaj takoj predvajljivo. Koraki ([WebtorPravila.koraki]):
            // (1) Usenet, ki ga ima TorBox ze pripravljenega - prve izdaje preverjene HKRATI s preverbo ⚡ ([predpomnjeniZa],
            // najvec 6 s); (2) takoj: ⚡ torrent, ziva neposredna povezava, [⚡WT]; (3) Usenet s cakanjem na prenos;
            // (4) Webtor [WT]; (5) TorBox s cakanjem in ostalo ([samodejnoBrezUseneta]). Nikoli okno »izberi vir«.
            val poskusi = UsenetPravila.zaSamodejno(usenet.map { TokIzbira.opisi(opis(it)).gb }).map { usenet[it] }
            pripravljeno.usenet?.takeIf { !samoWebtor }?.let { (t, cdn, ponudnik) ->
                android.util.Log.i("SafeerOsMedia", "tokovi: ${UsenetLastniPravila.dnevnikPripravljenega(ponudnik)}; izbran=${TokIzbira.opisi(opis(t))}")
                Toast.makeText(this, napisPredvajaPrek(ponudnik), Toast.LENGTH_SHORT).show()
                odpri(t.copy(vrsta = "url", url = cdn, glave = emptyMap()))
                // Ce se Usenet ne zacne (TV 28. 10. 2026: izdaja, ki je predvajalnik ne prebere), gre izbira naprej brez Useneta.
                val brezUseneta = vsiTokovi.filter { it.vrsta != UsenetPravila.VRSTA }
                if (brezUseneta.isNotEmpty()) GlasbaStoritev.obOdpovedi = {
                    if (!isFinishing && !isDestroyed) izberiTok(sk, naslov, brezUseneta, rocno, Pripravljeno(pripravljeno.predpomnjeni), obNeuspehu)
                }
                return
            }
            fun takojBrezWebtorja(): Boolean {
                val k = DebridPravila.samodejniTorrent(Debrid.aktivna(this), hashiTorrentov, predpomnjeni)
                // Meritev 25. 10. 2026 (Webtor rezerva): v dnevniku mora biti, po kateri poti je slo takoj predvajanje.
                if (k >= 0) {
                    android.util.Log.i("SafeerOsMedia", "tokovi: takoj pripravljen pri debridu ${predpomnjeni.size} od ${torrentiPoKakovosti.size}; " +
                        "izbran=${TokIzbira.opisi(opis(torrentiPoKakovosti[k]))}")
                    odpri(torrentiPoKakovosti[k]); return true
                }
                val zivi = neposredni.filter { !TokIzbira.potekla(it.url, zdajS) }
                if (zivi.isNotEmpty()) {
                    android.util.Log.i("SafeerOsMedia", "tokovi: takoj neposredni ${zivi.size}, pripravljenih pri debridu 0 od ${torrentiPoKakovosti.size}; " +
                        "izbran=${TokIzbira.opisi(opis(zivi.first()))}")
                    odpri(zivi.first(), zivi.drop(1).take(REZERVNIH_TOKOV)); return true
                }
                android.util.Log.i("SafeerOsMedia", "tokovi: takoj nic (pripravljenih pri debridu 0 od ${torrentiPoKakovosti.size}, neposrednih 0)")
                return false
            }
            fun naKonec() {
                // Diagnostika samo Webtor (pregled 26, T3): brez TorBoxa s cakanjem in brez torrenta na napravi.
                if (samoWebtor) { android.util.Log.i("SafeerOsMedia", "tokovi: diagnostika samo Webtor - Webtor ni predvajal"); return }
                if (samoUsenet) {
                    // Pregled Grok 31, H2: viden konec (prej samo dnevnik) in v dnevniku, koliko izdaj je bilo poskusenih - meritev
                    // ne sme pokazati »Usenet ne dela«, ce izdaje do meje samodejne izbire sploh ni bilo.
                    android.util.Log.i("SafeerOsMedia", "tokovi: diagnostika samo Usenet - Usenet ni predvajal (pripravljen pri TorBoxu: ne, " +
                        "s cakanjem poskusenih ${poskusi.size} od ${usenet.size}" +
                        (if (poskusi.isEmpty()) " - nobena izdaja do ${UsenetPravila.NAJVEC_GB_SAMODEJNO.toInt()} GB" else "") + ")")
                    Toast.makeText(this, R.string.os_usenet_diagnostika_konec, Toast.LENGTH_LONG).show()
                    obNeuspehu?.invoke()
                    return
                }
                if (!samodejnoBrezUseneta()) Toast.makeText(this, getString(R.string.os_media_ni_na_voljo, naslov), Toast.LENGTH_SHORT).show()
            }
            // Webtor: [samoTakoj] = korak 2 ([⚡WT]), sicer korak 4 ([WT]); skupaj najvec 2 kroga sond in 15 s ([webtorRok]).
            fun poskusiWebtor(samoTakoj: Boolean, potem: () -> Unit) {
                // Izmerjeno 25. 10. 2026: vsak cetrti kandidat je vrnil napacno datoteko - krog preveri VZPOREDNO hkrati.
                val kandidati = (if (samoTakoj) webtorTakoj else webtor).filter { it.url !in poskuseniWebtor }.take(WebtorPravila.VZPOREDNO)
                if (kandidati.isEmpty() || webtorRok() <= 0L) { potem(); return }
                for ((i, w) in kandidati.withIndex())
                    android.util.Log.i("SafeerOsMedia", "tokovi: Webtor${if (samoTakoj) " takoj" else ""} ${i + 1}/${kandidati.size}; izbran=${TokIzbira.opisi(opis(w))}, " +
                        "vrsta=${w.oznakaVira}, datoteka=${datotekaOd(w)}, h=${w.hash.take(10)}, idx=${w.datoteka}, sejalcev=${TokIzbira.sejalci(opis(w))}")
                odpriWebtor(kandidati) {
                    Toast.makeText(this, R.string.os_webtor_rezerva, Toast.LENGTH_SHORT).show()
                    poskusiWebtor(samoTakoj, potem)
                }
            }
            fun poskusiUsenetSCakanjem(i: Int) {
                if (i >= poskusi.size) {
                    if (i > 0) Toast.makeText(this, R.string.os_usenet_rezerva, Toast.LENGTH_SHORT).show()
                    poskusiWebtor(false) { naKonec() }
                    return
                }
                val t = poskusi[i]
                android.util.Log.i("SafeerOsMedia", "tokovi: Usenet s prenosom ${i + 1}/${poskusi.size} od ${usenet.size}; izbran=${TokIzbira.opisi(opis(t))}")
                prekDebrida(t.url, t.datoteka, { cdn -> odpri(t.copy(vrsta = "url", url = cdn, glave = emptyMap())) }, null,
                    samoTakoj = false, usenetIme = t.opis.substringBefore('\n'), obNeuspehu = { poskusiUsenetSCakanjem(i + 1) })
            }
            if (takojBrezWebtorja()) return
            if (poskusi.isNotEmpty() || webtor.isNotEmpty()) {
                poskusiWebtor(true) { if (poskusi.isNotEmpty()) poskusiUsenetSCakanjem(0) else poskusiWebtor(false) { naKonec() } }
                return
            }
            if (samoWebtor || samoUsenet) { naKonec(); return }
            if (samodejnoBrezUseneta()) return
        }
        // Rocna izbira: najboljsi na vrhu, napovednik na koncu.
        // Naloga 130: pripravljeni torrenti (⚡) so na vrhu seznama, pred povezavami.
        // Takoj predvajljivo na vrhu (⚡ torrenti, povezave, [⚡WT]), nato Usenet (najboljsa kakovost), Webtor [WT] in
        // nepripravljeni torrenti - kot pri samodejni izbiri.
        val urejeni = torrenti.filter { pripravljen(it) } + neposredni + webtorTakoj + usenet + webtor.filter { it !in webtorTakoj } +
            torrenti.filter { !pripravljen(it) } +
            tokovi.filter { it.vrsta != "url" && it.vrsta != "torrent" && it.vrsta != UsenetPravila.VRSTA }
        if (urejeni.size == 1 && !rocno) { odpri(urejeni.first()); return }
        val imena = urejeni.map { t ->
            val vrsta = if (t.vir == WebtorPravila.VIR) " · " + WebtorPravila.IME
                else when (t.vrsta) { "torrent" -> " · " + getString(R.string.os_stremio_torrent); UsenetPravila.VRSTA -> " · Usenet"; "napovednik" -> " · " + getString(R.string.os_stremio_zunanji); else -> "" }
            // Webtor rezerva: ⚡ tudi za tok, ki ga ima Webtor pripravljenega ([⚡WT]) ali TorBox v Webtor profilu ([⚡TB]).
            val takoj = pripravljen(t) || (t.vir == WebtorPravila.VIR && WebtorPravila.vrstaIz(t.oznakaVira) != WebtorPravila.Vrsta.PRENOS)
            val o = KakovostPravila.oznaka(t.ime + " " + t.opis)
            val napis = (if (takoj) DebridPravila.OZNAKA_PREDPOMNJEN + " " else "") + (o?.let { "[" + it.napis + "] " } ?: "") +
                listOf(t.ime, t.opis.replace('\n', ' ')).filter { it.isNotBlank() }.joinToString(" · ") + vrsta
            // Lastnik 28. 10. 2026: CAM in TS v opozorilni barvi, enako kot na kartici.
            if (o?.opozorilo == true) android.text.SpannableString(napis).apply {
                val z = napis.indexOf("[" + o.napis + "]")
                setSpan(android.text.style.ForegroundColorSpan(BARVA_OPOZORILA_KAKOVOSTI), z, z + o.napis.length + 2, 0)
            } else napis
        }
        AlertDialog.Builder(this).setTitle(naslov).setItems(imena.toTypedArray<CharSequence>()) { _, k ->
            val t = urejeni[k]
            // Webtor rezerva: rocno izbran tok Webtor - sonda in TorBox v ozadju; ce ne stece, ista izdaja kot torrent.
            if (t.vir == WebtorPravila.VIR) {
                val nas = kljucWebtor(t)?.let { kw -> torrentiPoKakovosti.firstOrNull { kljucTorrenta(it) == kw } }
                odpriWebtor(listOf(t)) {
                    Toast.makeText(this, R.string.os_webtor_rezerva, Toast.LENGTH_SHORT).show()
                    if (nas != null) odpri(nas)
                }
                return@setItems
            }
            // Tudi po rocni izbiri: ce izbrani tok ne stece, gredo za njim ostali neposredni (od najboljsega).
            odpri(t, if (t.vrsta == "url") neposredni.filter { it !== t }.take(REZERVNIH_TOKOV) else emptyList())
        }.show()
    }

    /**
     * Webtor rezerva (25. 10. 2026, [WebtorPravila]): pred predvajanjem krog sond ([Webtor.krog], do
     * [WebtorPravila.VZPOREDNO] kandidatov hkrati, z delovne niti): 402/404/429, prvi bajt po vec kot 15 s ali vsebina, ki ni
     * glavni video ([WebtorPravila.pregled]: naslovnica, BMP, besedilo, vzorec, kodek brez dekoderja) -> naslednji kandidat;
     * noben -> [naprej] (naslednji vir), sicer [predvajaj] z izbranim (predvajalnik dobi nadomestek `/resolve/`, vir podatkov
     * pa koncni naslov, ki ga je sonda preverila - [Webtor.zaPredvajalnik]). Preklic kot pri debridu ([rodDebrida]).
     */
    private fun predvajajWebtor(kandidati: List<Stremio.Tok>, rokMs: Long, opisi: List<String>, predvajaj: (Stremio.Tok) -> Unit, naprej: () -> Unit,
                                /** Krog ni stel kot poskus ([WebtorPravila.stejeKotPoskus]: vse sonde hitro zavrnjene po vsebini). */
                                neStejeKotPoskus: () -> Unit = {}) {
        if (isFinishing || isDestroyed || debridDelavec.isShutdown) return
        prekiniDebrid()
        val rod = rodDebrida.get()
        fun velja() = rodDebrida.get() == rod && !isFinishing && !isDestroyed
        if (vOspredju) Toast.makeText(this, R.string.os_webtor_pripravljam, Toast.LENGTH_SHORT).show()
        val delo = try { debridDelavec.submit(Runnable {
            // Izmerjeno 25. 10. 2026: 206 ni dovolj (naslovnica, BMP, besedilo, vzorec, AVI brez podprtega videa) - krog sond
            // pregleda prve bajte vseh kandidatov hkrati ([Webtor.krog]).
            val zacetekKroga = android.os.SystemClock.elapsedRealtime()
            val krog = try { Webtor.krog(applicationContext, kandidati.map { it.url to TokIzbira.opisi(it.ime + " " + it.opis).gb }, rokMs) }
                catch (e: Exception) { Webtor.Krog(-1, kandidati.map { null }, kandidati.map { null }, kandidati.map { null }) }
            val trajanjeKroga = android.os.SystemClock.elapsedRealtime() - zacetekKroga
            val steje = krog.izidi.any { it == null || WebtorPravila.stejeKotPoskus(it, trajanjeKroga) }
            for (i in kandidati.indices) android.util.Log.i("SafeerOsMedia", "tokovi: Webtor sonda ${i + 1}/${kandidati.size} " +
                (krog.sonde[i]?.let { Webtor.opis(it, krog.pregledi[i]) } ?: "brez odgovora") + ", ${opisi.getOrElse(i) { "" }} -> ${krog.izidi[i] ?: "-"}")
            if (Thread.currentThread().isInterrupted || rodDebrida.get() != rod) return@Runnable
            val w = kandidati.getOrNull(krog.izbran)
            android.util.Log.i("SafeerOsMedia", "tokovi: Webtor krog -> ${if (w != null) "PREDVAJAJ ${krog.izbran + 1}" else "naslednji vir"}")
            // Predvajalnik dobi koncni naslov, ki ga je ta sonda preverila ([Webtor.zaPredvajalnik], pregled U4).
            if (w != null) krog.sonde[krog.izbran]?.let { Webtor.zapomni(w.url, it) }
            runOnUiThread {
                if (!velja()) return@runOnUiThread
                debridDelo = null
                zasloniOstane(false)
                izvediIzidDebrida(rod) {
                    if (w != null) {
                        Toast.makeText(this, getString(R.string.os_debrid_predvaja_prek, WebtorPravila.IME), Toast.LENGTH_SHORT).show()
                        predvajaj(w)
                    } else {
                        if (!steje) {
                            android.util.Log.i("SafeerOsMedia", "tokovi: Webtor krog hitro zavrnjen po vsebini ($trajanjeKroga ms) - ni poskus")
                            neStejeKotPoskus()
                        }
                        naprej()
                    }
                }
            }
        }) } catch (_: java.util.concurrent.RejectedExecutionException) { return }
        debridDelo = delo
        zasloniOstane(true)
    }

    /** Webtor rezerva: torrent, ki ga predvaja Webtor, v ozadju v TorBox ([WebtorPravila.dodajVTorBox]) - naslednjic ⚡. */
    private fun webtorVTorBox(magnet: String, predpomnjen: Boolean) {
        val hash = TorBoxPravila.hashIzMagneta(magnet)?.lowercase()
        if (!WebtorPravila.dodajVTorBox(DebridPravila.TORBOX in Debrid.zKljucem(this), Debrid.aktivna(this) != null, predpomnjen, hash)) return
        Webtor.vTorBoxVOzadju(applicationContext, magnet)
    }

    /**
     * Naloga 119/128: torrent prek debrid storitve (TorBox, Real-Debrid, AllDebrid - [Debrid]), z delovne niti. Uspeh:
     * [naprej] dobi povezavo CDN (brez kljuca), kratko sporocilo pove, prek katere storitve. Neuspeh: NIKOLI tiho na
     * neposredni torrent (to bi razkrilo naslov IP) - okno pove zakaj; uporabnik izbere »Nadaljuj brez (brez zasebnosti)«,
     * preklice, pri »ni pripravljeno« »Pocakaj na prenos« in pri zavrnjenem kljucu »Popravi kljuc«.
     *
     * Pregled A1: vsaka zahteva ima svoj rod ([rodDebrida]); nova izbira, »Preklici« in onStop ga povecajo in prekinejo nit
     * (Future.cancel) - star izid ne odpre nicesar vec (prej je film A po minutah prekinil film B). Ce cakanje traja, je
     * odprto okno z napredkom in gumbom »Preklici«.
     */
    private fun prekDebrida(magnet: String, datoteka: Int, naprej: (String) -> Unit,
                            /** null (Usenet): ni poti brez debrida - gumba »Nadaljuj brez« ni. */
                            brez: (() -> Unit)?, samoTakoj: Boolean? = null,
                            /** Naloga 141: [magnet] je id toka Usenet, to je ime izdaje (za TorBox). */
                            usenetIme: String? = null,
                            /** Naloga 141: samodejna izbira - ob neuspehu brez okna naprej (naslednji vir). */
                            obNeuspehu: (() -> Unit)? = null) {
        // Pregled 1: Activity, ki je ze unicena (ponovno ustvarjena ob spremembi nastavitev), ne zacne nicesar - njen bazen
        // niti je ustavljen (prej RejectedExecutionException in sesutje).
        if (isFinishing || isDestroyed || debridDelavec.isShutdown) return
        prekiniDebrid()
        val rod = rodDebrida.get()
        fun velja() = rodDebrida.get() == rod && !isFinishing && !isDestroyed
        // Lastnik 27. 10. 2026: lasten ponudnik Usenet ni »debrid storitev«; napis ostane viden do predvajalnika ali napake.
        val pripravljam = getString(if (UsenetLastniPravila.pripravaLastnega(usenetIme != null, UsenetLastni.vUporabi(applicationContext)))
            R.string.os_usenet_pripravljam else R.string.os_debrid_pripravljam)
        if (vOspredju) pokaziPripravljam(pripravljam)
        besediloDebrida = pripravljam
        var zadnjiOdstotek = -1
        val delo = try { debridDelavec.submit(Runnable {
            // Ime storitve za napredek: Usenet prenasa TorBox, torrent storitev, ki caka (sprva prednostna).
            // Neodvisni pregled 2: Debrid.tok pove storitev, ki res caka (obCakanju) - ni nujno prednostna.
            var ime = if (usenetIme != null) (if (UsenetLastni.vUporabi(applicationContext)) getString(R.string.os_usenet_lastni_kratko)
                    else DebridPravila.ime(DebridPravila.TORBOX))
                else Debrid.vrstniRed(applicationContext).firstOrNull()?.let { DebridPravila.ime(it) } ?: getString(R.string.os_debrid)
            val napredek = { odstotek: Int ->
                if (odstotek != zadnjiOdstotek) {
                    val obvestilo = zadnjiOdstotek < 0 || odstotek / 25 != zadnjiOdstotek / 25
                    zadnjiOdstotek = odstotek
                    runOnUiThread {
                        if (velja()) {
                            besediloDebrida = getString(R.string.os_torbox_prenasa_odstotek, ime, odstotek)
                            val o = debridOkno
                            if (o != null && o.isShowing) o.setMessage(besediloDebrida)
                            else if (obvestilo && vOspredju) Toast.makeText(this, besediloDebrida, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            val izid = try {
                if (usenetIme != null) Usenet.tok(applicationContext, magnet, usenetIme, datoteka, samoTakoj ?: Debrid.samoTakoj(applicationContext), napredek)
                else Debrid.tok(applicationContext, magnet, datoteka, samoTakoj, napredek) { p -> ime = DebridPravila.ime(p) }
            } catch (e: Exception) {
                android.util.Log.w("SafeerOsMedia", "debrid: ${e.javaClass.simpleName}")
                TorBox.Izid(napaka = TorBoxPravila.NAPAKA_STREZNIK)
            }
            // Preklicana ali zamenjana zahteva ne naredi nicesar vec (ne odpre filma cez tistega, ki ga uporabnik gleda).
            if (Thread.currentThread().isInterrupted || rodDebrida.get() != rod) return@Runnable
            // Pregled 2: izid izvede samo vidna Activity; ce je ustavljena (ugasnjen zaslon, ohranjevalnik), pocaka na onStart -
            // predvajalnika iz ozadja Android 10+ ne dovoli odpreti.
            val obdelaj: () -> Unit = obdelaj@{
                zapriOknoDebrida()
                val url = izid.url
                if (url != null) {
                    Toast.makeText(this, napisPredvajaPrek(izid.ponudnik), Toast.LENGTH_SHORT).show()
                    naprej(url); return@obdelaj
                }
                if (obNeuspehu != null) {
                    android.util.Log.i("SafeerOsMedia", "tokovi: ${if (usenetIme != null) "Usenet" else "debrid"} ni uspel (${izid.napaka}) - naprej samodejno")
                    obNeuspehu(); return@obdelaj
                }
                // Pregled A4: sporocilo imenuje storitev, ki je res padla (TorBox, Real-Debrid, AllDebrid, indeksator Usenet).
                val storitev = imeStoritveNapake(izid)
                val sporocilo = DebridBesedila.sporocilo(this, izid, storitev)
                val naslovOkna = DebridBesedila.naslov(this, izid, storitev)
                val g = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(naslovOkna).setMessage(sporocilo)
                    .setNegativeButton(R.string.os_preklici, null)
                if (brez != null) g.setPositiveButton(R.string.os_debrid_brez) { _, _ -> brez() }
                when (izid.napaka) {
                    TorBoxPravila.NAPAKA_KLJUC, TorBoxPravila.NAPAKA_ZAKLENJEN, UsenetPravila.NAPAKA_INDEKSATOR -> g.setNeutralButton(R.string.os_torbox_popravi) { _, _ -> nastavitveDebrida() }
                    DebridPravila.NAPAKA_NI_PREDPOMNJENO -> g.setNeutralButton(R.string.os_debrid_pocakaj) { _, _ ->
                        prekDebrida(magnet, datoteka, naprej, brez, samoTakoj = false, usenetIme = usenetIme) }
                }
                val okno = g.create()
                // Privzeti gumb je Preklici: zadrzan OK ne sme takoj odpreti torrenta brez zasebnosti.
                okno.setOnShowListener { okno.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus() }
                Kontroler.pokazi(okno); okno.show()
            }
            runOnUiThread {
                if (!velja()) return@runOnUiThread
                debridDelo = null
                zasloniOstane(false)
                izvediIzidDebrida(rod, obdelaj)
            }
        }) } catch (_: java.util.concurrent.RejectedExecutionException) { return }
        debridDelo = delo
        // Pregled 2: med cakanjem se zaslon ne ugasne (telefon, ohranjevalnik TV) - sicer bi onStop ustavil predvajanje izida.
        zasloniOstane(true)
        // Ce cakanje traja (prenos na strezniku, Usenet do 10 min): okno z napredkom in »Preklici« (tudi tipka Nazaj).
        glavna.postDelayed({ if (velja() && !delo.isDone) pokaziOknoDebrida() }, OKNO_DEBRIDA_PO_MS)
    }

    /** Pregled 2: besedilo okna napredka (zadnji napredek) - tudi za okno, ki se odpre sele ob vrnitvi (onStart). */
    private var besediloDebrida = ""
    /** Pregled 2: izid, ki je prisel med ustavljeno Activity (rod, dejanje) - izvede se ob onStart, ce rod se velja. */
    private var odlozenIzidDebrida: Pair<Int, () -> Unit>? = null

    private fun pokaziOknoDebrida() {
        if (debridOkno != null || !vOspredju || isFinishing || isDestroyed) return
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(besediloDebrida)
            .setNegativeButton(R.string.os_preklici) { _, _ -> prekiniDebrid() }
            .setOnCancelListener { prekiniDebrid() }
            .create()
        okno.setCanceledOnTouchOutside(false)
        debridOkno = okno
        Kontroler.pokazi(okno); okno.show()
    }

    private fun izvediIzidDebrida(rod: Int, dejanje: () -> Unit) {
        if (rodDebrida.get() != rod || isFinishing || isDestroyed) return
        if (!vOspredju) { odlozenIzidDebrida = rod to dejanje; return }
        odlozenIzidDebrida = null
        dejanje()
    }

    /** Pregled 2: ob vrnitvi na zaslon - odlozen izid se izvede, se tekoce cakanje spet pokaze okno napredka. */
    private fun nadaljujDebridObVrnitvi() {
        odlozenIzidDebrida?.let { (r, d) -> odlozenIzidDebrida = null; izvediIzidDebrida(r, d); return }
        if (debridDelo?.isDone == false) pokaziOknoDebrida()
    }

    private fun zasloniOstane(da: Boolean) {
        try {
            if (da) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) { }
    }

    /** Pregled A1: rod zahteve debrid/Usenet - vsaka nova izbira, »Preklici« in onStop ga povecajo. */
    private val rodDebrida = java.util.concurrent.atomic.AtomicInteger()
    /** Lastne niti (ne [delavec]): blokirajoc klic stare zahteve ne zadrzi nove. */
    private val debridDelavec = Executors.newCachedThreadPool { r -> Thread(r, "safeer-debrid").apply { isDaemon = true } }
    @Volatile private var debridDelo: java.util.concurrent.Future<*>? = null
    private var debridOkno: AlertDialog? = null

    private fun zapriOknoDebrida() {
        skrijPripravljam()
        val o = debridOkno ?: return
        debridOkno = null
        try { if (o.isShowing) o.dismiss() } catch (_: Exception) { }
    }

    /**
     * Pregled A1: prekine cakanje na debrid/Usenet - nova izbira, Preklici/Nazaj, odhod uporabnika (onUserLeaveHint) in
     * onDestroy; NE ob vsakem onStop (pregled 2: ugasnjen zaslon ni odhod). Star izid ne naredi nicesar.
     */
    private fun prekiniDebrid() {
        rodDebrida.incrementAndGet()
        debridDelo?.cancel(true)
        debridDelo = null
        odlozenIzidDebrida = null
        zasloniOstane(false)
        zapriOknoDebrida()
    }

    /** Pregled A4: ime storitve za sporocilo napake. */
    private fun imeStoritveNapake(izid: TorBox.Izid): String = when {
        // Pregled Grok 3: tudi zavrnjen kljuc in meja indeksatorja (ponudnik Usenet) imenujeta indeksator, ne TorBoxa.
        izid.napaka == UsenetPravila.NAPAKA_INDEKSATOR || izid.ponudnik == UsenetPravila.PONUDNIK -> Usenet.gostitelj(this)?.let { getString(R.string.os_usenet_ime_kratko, it) } ?: "Usenet"
        izid.ponudnik.isNotEmpty() -> DebridPravila.ime(izid.ponudnik)
        else -> getString(R.string.os_debrid)
    }

    /**
     * Pregled A5: kljuc storitve [p] je shranjen, a ga ni mogoce prebrati (Keystore) - torrent NE gre tiho mimo debrida (naslov
     * IP bi bil viden). Uporabnik kljuc vpise znova ali sam izbere »Nadaljuj brez«; privzeti gumb je Preklici.
     */
    private fun kljucNeberljiv(p: String, brez: () -> Unit) {
        val ime = DebridPravila.ime(p)
        // Varovalo racunov: berljiv kljuc, ki ne velja ali je racun zaklenjen - okno pove, kaj narediti, »Popravi« odpre »Preveri«.
        val ustavljen = Debrid.imaKljuc(this, p)
        // Pregled 10: tudi »Odstrani kljuc« - sicer bi se okno pokazalo ob vsakem torrentu. Preklic je tipka Nazaj; privzet
        // (fokus) je varen gumb »Popravi kljuc«, ne »brez zasebnosti«.
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_torbox_napaka_naslov, ime))
            .setMessage(if (ustavljen) navodiloRacuna(p, Debrid.stanjeKljuca(this, p)?.first) else getString(R.string.os_debrid_kljuc_neberljiv, ime))
            .setNegativeButton(R.string.os_torbox_popravi) { _, _ -> if (ustavljen) stanjeKljucaOkno(p) else kljucDebrida(p) }
            .setNeutralButton(R.string.os_torbox_odstrani) { _, _ ->
                // Pregled Grok 33, F1: neberljiv kljuc se odstrani samo tu (druge naprave obdrzijo svoj veljaven).
                odstranjenoSporocilo(ime, Debrid.odstraniKljuc(this, p))
                osveziNaMestu()
            }
            .setPositiveButton(R.string.os_debrid_brez) { _, _ -> brez() }
            .create()
        okno.setOnShowListener { okno.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus() }
        Kontroler.pokazi(okno); okno.show()
    }

    /** Opis kartice »Debrid storitve«: katere storitve imajo kljuc in ali velja »samo takoj predvajljivo«. */
    private fun opisDebrida(): String {
        val z = Debrid.vrstniRed(this)
        if (z.isEmpty()) return getString(R.string.os_debrid_opis_brez)
        return getString(R.string.os_debrid_opis_vpisan, z.joinToString(", ") { DebridPravila.ime(it) },
            getString(if (Debrid.samoTakoj(this)) R.string.os_debrid_samo_takoj_kratko else R.string.os_debrid_s_cakanjem))
    }

    /**
     * Naloga 128: »Debrid storitve« - en pregleden seznam: vsaka storitev s stanjem kljuca (dotik: vpis/odstranitev),
     * stikalo »Samo takoj predvajljivo« in prednostna storitev (ce sta kljuca vsaj dva). Okno se po spremembi odpre znova.
     */
    private fun nastavitveDebrida() {
        val vrstice = ArrayList<Pair<String, () -> Unit>>()
        for (p in DebridPravila.VSI) {
            val ime = DebridPravila.ime(p)
            // Naloga 130: zadnja meritev hitrosti do te naprave (ko jih je vec, predvaja najhitrejsa).
            val meritev = Debrid.zadnjaMeritev(this, p)?.let { " · " + DebridPravila.opisMeritve(it) }.orEmpty()
            // Lastnik 25. 10. 2026: vrstica pove, ali kljuc VELJA (storitev ga je potrdila), ne samo, da je vpisan.
            val stanje = Debrid.stanjeKljuca(this, p)
            val vrstica = when {
                !Debrid.imaKljuc(this, p) -> getString(R.string.os_debrid_vrstica_brez, ime)
                stanje?.first == DebridPravila.StanjeKljuca.VELJA ->
                    getString(R.string.os_debrid_vrstica_velja, ime, stanje.second?.let { " (" + DebridPravila.imePaketa(it) + ")" }.orEmpty())
                stanje?.first == DebridPravila.StanjeKljuca.NE_VELJA -> getString(R.string.os_debrid_vrstica_ne_velja, ime)
                // Varovalo racunov (lastnik 26. 10. 2026): vrstica pove, kaj narediti.
                stanje?.first == DebridPravila.StanjeKljuca.ZAKLENJEN -> when (p) {
                    DebridPravila.REAL_DEBRID -> getString(R.string.os_debrid_vrstica_zaklenjen_rd)
                    DebridPravila.ALLDEBRID -> getString(R.string.os_debrid_vrstica_zaklenjen_ad)
                    else -> getString(R.string.os_debrid_vrstica_zaklenjen, ime)
                }
                else -> getString(R.string.os_debrid_vrstica_nepreverjen, ime)
            }
            // Pregled Grok 33, F3: nov kljuc, vpisan brez povezave, caka ob strani (ali ga je storitev zavrnila).
            val caka = when {
                Debrid.cakajoc(this, p) -> " · " + getString(R.string.os_debrid_vrstica_caka)
                Debrid.cakajocZavrnjen(this, p) -> " · " + getString(R.string.os_debrid_vrstica_caka_zavrnjen)
                else -> ""
            }
            // Varovalo racunov: kljuc, ki ne velja, ali zaklenjen racun - okno z navodilom in »Preveri« (edina nova preverba).
            val ustavljen = Debrid.imaKljuc(this, p) && !DebridPravila.smeKlicati(stanje?.first)
            vrstice += vrstica + caka + meritev to { if (ustavljen) stanjeKljucaOkno(p) else kljucDebrida(p) }
        }
        // Naloga 141: Usenet indeksator (Newznab) - NZB najde indeksator, prenese in pretaka TorBox (Pro).
        // Lastnik 25. 10. 2026: kot pri kljucih debrid vrstica pove, ali je kljuc indeksatorja potrjen.
        val gostIx = Usenet.gostitelj(this)
        val stanjeIx = if (gostIx != null) Usenet.stanje(this) else null
        val vrsticaIx = when {
            gostIx == null -> getString(R.string.os_usenet_vrstica_brez)
            stanjeIx == DebridPravila.StanjeKljuca.VELJA -> getString(R.string.os_usenet_vrstica_vpisan, gostIx)
            stanjeIx == DebridPravila.StanjeKljuca.NE_VELJA -> getString(R.string.os_debrid_vrstica_ne_velja, getString(R.string.os_usenet_ime_kratko, gostIx))
            stanjeIx == DebridPravila.StanjeKljuca.ZAKLENJEN -> getString(R.string.os_debrid_vrstica_zaklenjen, getString(R.string.os_usenet_ime_kratko, gostIx))
            else -> getString(R.string.os_debrid_vrstica_nepreverjen, getString(R.string.os_usenet_ime_kratko, gostIx))
        }
        // Pregled Grok 41, Z2: indeksator »ne velja« ali »zaklenjen« - okno z navodilom in »Preveri« (kot kljuc debrid).
        val ustavljenIx = gostIx != null && !DebridPravila.smeKlicati(stanjeIx)
        vrstice += vrsticaIx to { if (ustavljenIx && gostIx != null) stanjeIndeksatorjaOkno(gostIx) else usenetIndeksator() }
        // Naloga 42 G: lasten ponudnik Usenet (NNTP) - okno »Moji viri › Usenet ponudnik« (geslo v Keystore, Preveri na klik).
        vrstice += UsenetPonudnikOkno.vrstica(this) to { UsenetPonudnikOkno.pokazi(this) { osveziNaMestu() } }
        val takoj = Debrid.samoTakoj(this)
        vrstice += getString(if (takoj) R.string.os_debrid_samo_takoj_vkl else R.string.os_debrid_samo_takoj_izk) to {
            Debrid.nastaviSamoTakoj(this, !takoj); osveziNaMestu(); nastavitveDebrida()
        }
        // Naloga 129: kljuce, vpisane tu (npr. na telefonu), poslji na TV - tam jih ni treba tipkati z daljincem.
        if ((Debrid.imaKateriKljuc(this) || Usenet.imaIndeksator(this) || MedijskiViri.skrivniDodatki(this).isNotEmpty()) && Predaja.cilji(this).isNotEmpty())
            vrstice += getString(R.string.os_debrid_poslji) to { posljiKljuceDebrida() }
        // Samodejno usklajevanje (lastnik, 25. 10. 2026): kljuce in dodatke vneses enkrat, veljajo na vseh napravah ozjega kroga.
        val sinh = UskladitevKljucev.vklopljeno(this)
        vrstice += getString(if (sinh) R.string.os_sinh_vkl else R.string.os_sinh_izk) to {
            UskladitevKljucev.nastaviVklopljeno(this, !sinh); osveziNaMestu(); nastavitveDebrida()
        }
        vrstice += getString(R.string.os_sinh_naprave, UskladitevKljucev.naprave(this).size) to { napraveUskladitve() }
        val red = Debrid.vrstniRed(this)
        if (red.size >= 2) vrstice += getString(R.string.os_debrid_prednostna, DebridPravila.ime(red.first())) to {
            val imena = red.map { DebridPravila.ime(it) }.toTypedArray()
            val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(R.string.os_debrid_prednostna_naslov)
                .setSingleChoiceItems(imena, 0) { d, i -> Debrid.nastaviPrednostno(this, red[i]); d.dismiss(); osveziNaMestu(); nastavitveDebrida() }
                .setNegativeButton(R.string.os_preklici, null).create()
            Kontroler.pokazi(okno); okno.show()
        }
        // Glava: naslov in kratko pojasnilo (seznam spodaj ostane cel - setMessage bi ga skril).
        val g = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        val glava = LinearLayout(g.context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(18), dp(24), dp(6))
            addView(TextView(g.context).apply {
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_DialogWindowTitle); text = getString(R.string.os_debrid)
            })
            addView(TextView(g.context).apply {
                text = getString(R.string.os_debrid_pojasnilo); setPadding(0, dp(6), 0, 0)
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
            })
        }
        val okno = g.setCustomTitle(glava)
            .setItems(vrstice.map { it.first }.toTypedArray()) { _, i -> vrstice[i].second() }
            .setNegativeButton(R.string.os_preklici, null).create()
        Kontroler.pokazi(okno); okno.show()
        // Lastnik 25. 10. 2026: kljuci brez stanja (pred posodobitvijo) ali nepreverjeni se preverijo v ozadju; ce se stanje
        // spremeni in je okno se odprto, se pokaze znova z novim stanjem.
        if (DebridPravila.VSI.any { (Debrid.imaKljuc(this, it) && Debrid.stanjeKljuca(this, it)?.first != DebridPravila.StanjeKljuca.VELJA) || Debrid.cakajoc(this, it) } ||
            (gostIx != null && stanjeIx != DebridPravila.StanjeKljuca.VELJA)) {
            val app = applicationContext
            Thread({
                val spremenjeno = try { Debrid.preveriNepreverjene(app) } catch (_: Exception) { false }
                if (spremenjeno) glavna.post {
                    if (!isFinishing && !isDestroyed && okno.isShowing) { okno.dismiss(); nastavitveDebrida() }
                }
            }, "safeer-debrid-stanje").start()
        }
    }

    /**
     * Naloga 129: kljuci na drugo napravo v Linku - izbira naprave, potrditev (kateri kljuci, sifrirano od naprave do
     * naprave), izid. Tam se shranijo sele, ko jih uporabnik potrdi.
     */
    private fun posljiKljuceDebrida() {
        // Pregled A2: kljuci in indeksator samo napravam v ozjem krogu te naprave (Debrid.posljiKljuce preveri se enkrat).
        val vse = Predaja.cilji(this)
        val cilji = OzjiKrog.samoKrog(this, vse)
        if (cilji.isEmpty()) {
            Toast.makeText(this, if (vse.isEmpty()) R.string.os_predaja_ni_naprav else R.string.os_debrid_poslji_ni_kroga, Toast.LENGTH_LONG).show()
            return
        }
        val imena = cilji.map { DatotekeActivity.lepoIme(it.ime) }
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(R.string.os_debrid_poslji)
            .setItems(imena.toTypedArray()) { _, i ->
                val n = cilji[i]; val ime = imena[i]
                val katere = (Debrid.vrstniRed(this).map { DebridPravila.ime(it) } +
                    listOfNotNull(Usenet.gostitelj(this)?.let { getString(R.string.os_usenet_ime_kratko, it) }) +
                    // Webtor rezerva: dodatki s poverilnico (samo gostitelj, nikoli naslov).
                    MedijskiViri.skrivniDodatki(this).map { getString(R.string.os_skrivni_dodatek_ime, Zasebno.gostitelj(it)) }.distinct()).joinToString(", ")
                pokaziBrisanje(AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(R.string.os_debrid_poslji)
                    .setMessage(getString(R.string.os_debrid_poslji_vprasanje, katere, ime))
                    .setPositiveButton(R.string.os_debrid_poslji_gumb) { _, _ ->
                        Debrid.posljiKljuce(this, n) { koda ->
                            if (isFinishing) return@posljiKljuce
                            Toast.makeText(this, when (koda) {
                                "ok" -> getString(R.string.os_debrid_poslano, ime)
                                "stara_dodatki" -> getString(R.string.os_debrid_poslji_stara_dodatki, ime)
                                "ni_krog" -> getString(R.string.os_ozji_krog_zavrnjeno, ime)
                                "ni_zascite" -> getString(R.string.os_debrid_poslji_ni_zascite, ime)
                                "ni_dovoljeno" -> getString(R.string.os_debrid_poslji_ni_dovoljeno, ime)
                                "stara" -> getString(R.string.os_predaja_poslji_stara, ime)
                                else -> getString(R.string.os_predaja_poslji_napaka, ime)
                            }, Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton(R.string.os_preklici, null))
            }
            .setNegativeButton(R.string.os_preklici, null).create()
        Kontroler.pokazi(okno); okno.show()
    }

    // ------------------------------------------------------------------ samodejno usklajevanje kljucev (ozji krog)

    private var uskladitevOkno: AlertDialog? = null
    private val obUskladitvi: () -> Unit = { if (!isFinishing && !isDestroyed) { pokaziCakajoceUskladitve(); pokaziSpremembeUskladitve() } }

    /**
     * Pregled U1: druga naprava je na tej zamenjala ali izbrisala kljuc - uporabnik to vidi in lahko razveljavi (prejsnje
     * vrednosti se vrnejo in gredo nazaj tudi na tisto napravo).
     */
    private fun pokaziSpremembeUskladitve() {
        if (uskladitevOkno?.isShowing == true || isFinishing) return
        val s = UskladitevKljucev.sprememba() ?: return
        fun ime(x: String) = if (x.startsWith(Debrid.PREDPONA_DODATKA)) getString(R.string.os_skrivni_dodatek_ime, x.removePrefix(Debrid.PREDPONA_DODATKA)) else x
        val katere = UskladitevKljucev.opisSpremembe(s).joinToString(", ") { ime(it) }.ifBlank { "–" }
        val naprava = DatotekeActivity.lepoIme(s.ime).ifBlank { s.ime.ifBlank { s.id } }
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.os_sinh_naprave_naslov)
            .setMessage(getString(R.string.os_sinh_spremenjeno, naprava, katere))
            .setPositiveButton(android.R.string.ok) { _, _ -> UskladitevKljucev.potrdiSpremembo(this, s, false) }
            .setNegativeButton(R.string.os_sinh_razveljavi) { _, _ -> UskladitevKljucev.potrdiSpremembo(this, s, true) }
            .create()
        okno.setOnDismissListener { if (uskladitevOkno === okno) uskladitevOkno = null }
        uskladitevOkno = okno
        Kontroler.pokazi(okno); okno.show()
    }

    /**
     * Nova naprava v ozjem krogu zeli usklajevati kljuce in dodatke ([UskladitevKljucev]): enkratna potrditev - velja tudi za
     * vse prihodnje spremembe in izbrise z nje. Okno, zaprto brez odlocitve, se pokaze znova ob naslednjem odprtju.
     */
    private fun pokaziCakajoceUskladitve() {
        if (uskladitevOkno?.isShowing == true || isFinishing) return
        val z = UskladitevKljucev.cakajoca() ?: return
        fun ime(x: String) = if (x.startsWith(Debrid.PREDPONA_DODATKA)) getString(R.string.os_skrivni_dodatek_ime, x.removePrefix(Debrid.PREDPONA_DODATKA)) else x
        val katere = UskladitevKljucev.opisZapisov(z).joinToString(", ") { ime(it) }.ifBlank { "–" }
        val naprava = DatotekeActivity.lepoIme(z.ime).ifBlank { z.ime.ifBlank { z.id } }
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.os_sinh_naprave_naslov)
            // Pregled U3: okno pove tudi, da gredo kljuci te naprave na drugo napravo.
            .setMessage(getString(R.string.os_sinh_vprasanje, naprava, katere) + "\n\n" + getString(R.string.os_sinh_tudi_nasi, naprava))
            .setPositiveButton(R.string.os_sinh_sprejmi) { _, _ -> UskladitevKljucev.potrdi(this, z.kljuc, true) }
            .setNegativeButton(R.string.os_sinh_zavrni) { _, _ -> UskladitevKljucev.potrdi(this, z.kljuc, false) }
            .create()
        okno.setOnDismissListener { if (uskladitevOkno === okno) uskladitevOkno = null }
        // Privzeto Sprejmi: uporabnik je napravo sam dodal v ozji krog.
        okno.setOnShowListener { okno.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus() }
        uskladitevOkno = okno
        Kontroler.pokazi(okno); okno.show()
    }

    // ------------------------------------------------------------------ Grok 46, Ö3: ze razposlane kopije gesla IPTV

    /** Lastnik, 26. 10. 2026: opozorila »geslo je bilo deljeno« ne kazemo. */
    private val OPOZORILO_GESLA_DELJENO = false

    private var racunOkno: AlertDialog? = null
    private val obRacunih: () -> Unit = { if (!isFinishing && !isDestroyed) pokaziRacune() }

    /**
     * Lastnik: starejsa razlicica je vir z racunom dala napravam zunaj ozjega kroga - enkrat priporocilo, naj geslo zamenja
     * (edini zanesljiv popravek ze razposlanih kopij). Prejemnik: lastnik vira ga s to napravo ne deli vec - odstrani ali obdrzi
     * (samodejno ne: ta naprava je lahko tudi izvor zapisa).
     */
    private fun pokaziRacune() {
        if (racunOkno?.isShowing == true || uskladitevOkno?.isShowing == true || isFinishing) return
        // Lastnik, 26. 10. 2026: opozorilo »geslo je bilo deljeno« prestrasi uporabnika (pomisli na e-posto, banko) - ne kazemo ga;
        // kljuci in skrivnosti se sinhronizirajo v ozjem krogu uporabnikovih naprav, sifrirano. Ostane samo odlocitev o preklicu.
        val gostitelji = if (OPOZORILO_GESLA_DELJENO) MedijskiViri.opozoriloDeljenja(this) else emptyList()
        val okno = if (gostitelji.isNotEmpty()) AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.os_iptv_deljeno_naslov)
            .setMessage(getString(R.string.os_iptv_deljeno_sporocilo, gostitelji.joinToString(", ")))
            .setPositiveButton(android.R.string.ok) { _, _ -> MedijskiViri.opozoriloVideno(this) }
            .create()
        else {
            val p = MedijskiViri.preklic() ?: return
            val naprava = DatotekeActivity.lepoIme(p.ime).ifBlank { p.ime.ifBlank { p.naprava } }
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.os_iptv_preklic_naslov)
                .setMessage(getString(R.string.os_iptv_preklic_sporocilo, MedijskiViri.opisPreklica(p), naprava))
                .setPositiveButton(R.string.os_mediji_odstrani) { _, _ -> MedijskiViri.odlociPreklic(this, p, true); poSpremembiVirov(); izberiNaMestu(razdelek) }
                .setNegativeButton(R.string.os_iptv_preklic_obdrzi) { _, _ -> MedijskiViri.odlociPreklic(this, p, false) }
                .create()
        }
        okno.setOnDismissListener { if (racunOkno === okno) racunOkno = null }
        racunOkno = okno
        Kontroler.pokazi(okno); okno.show()
    }

    /** »Naprave s kljuci«: s katerimi napravami ozjega kroga se ta naprava usklajuje, v kaksnem stanju in kdaj nazadnje. */
    private fun napraveUskladitve() {
        val naprave = UskladitevKljucev.naprave(this)
        val oblika = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, Locale.getDefault())
        val vrstice = naprave.map { n ->
            val ime = DatotekeActivity.lepoIme(n.ime).ifBlank { n.ime.ifBlank { n.id } }
            when (n.stanje) {
                UskladitevKljucevPravila.Stanje.POTRJENA -> getString(R.string.os_sinh_stanje_potrjena, ime,
                    if (n.zadnjic > 0) oblika.format(java.util.Date(n.zadnjic)) else "–")
                UskladitevKljucevPravila.Stanje.POVABLJENA -> getString(R.string.os_sinh_stanje_povabljena, ime)
                UskladitevKljucevPravila.Stanje.CAKA -> getString(R.string.os_sinh_stanje_caka, ime)
                UskladitevKljucevPravila.Stanje.ZAVRNJENA -> getString(R.string.os_sinh_stanje_zavrnjena, ime)
                UskladitevKljucevPravila.Stanje.STARA -> getString(R.string.os_sinh_stanje_stara, ime)
            }
        }
        val g = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(R.string.os_sinh_naprave_naslov)
        if (naprave.isEmpty()) g.setMessage(R.string.os_sinh_naprave_prazno)
        else g.setItems(vrstice.toTypedArray()) { _, i ->
            val n = naprave[i]
            when (n.stanje) {
                UskladitevKljucevPravila.Stanje.ZAVRNJENA -> {
                    UskladitevKljucev.dovoliZnova(this, n.kljuc)
                    Toast.makeText(this, getString(R.string.os_sinh_ponovno, DatotekeActivity.lepoIme(n.ime).ifBlank { n.ime }), Toast.LENGTH_SHORT).show()
                }
                UskladitevKljucevPravila.Stanje.CAKA -> pokaziCakajoceUskladitve()
                else -> UskladitevKljucev.sprozi(this, 200)
            }
        }
        val okno = g.setNegativeButton(R.string.os_preklici, null).create()
        Kontroler.pokazi(okno); okno.show()
    }

    /** Naloga 129: kljuci, ki jih je poslala druga naprava, se shranijo sele po potrditvi tu. */
    private fun pokaziPrejeteKljuce() {
        val p = Debrid.vzemiPrejete() ?: return
        // Naloga 141: tudi indeksator Usenet (ime gostitelja, kljuc se ne pokaze).
        fun ime(x: String) = when {
            x == UsenetPravila.VRSTA -> getString(R.string.os_usenet_ime_kratko,
                p.indeksator?.first?.let { try { java.net.URL(it).host } catch (_: Exception) { it } }.orEmpty())
            // Webtor rezerva: dodatek s poverilnico - samo gostitelj.
            x.startsWith(Debrid.PREDPONA_DODATKA) -> getString(R.string.os_skrivni_dodatek_ime, x.removePrefix(Debrid.PREDPONA_DODATKA))
            else -> DebridPravila.ime(x)
        }
        val katere = (p.kljuci.keys + listOfNotNull(p.indeksator?.let { UsenetPravila.VRSTA }) +
            p.dodatki.map { Debrid.PREDPONA_DODATKA + Zasebno.gostitelj(it) }.distinct()).joinToString(", ") { ime(it) }
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.os_debrid)
            .setMessage(getString(R.string.os_debrid_prejeto_vprasanje, DatotekeActivity.lepoIme(p.od), katere))
            .setPositiveButton(R.string.os_torbox_shrani) { _, _ ->
                // Lastnik 25. 10. 2026: prejeti kljuci se preverijo pri storitvi (omrezje - delovna nit); zavrnjen se ne shrani.
                val app = applicationContext
                Thread({
                    // Pregled Grok 33, F9: telo niti v try (izjema bi sesula aplikacijo); okno loci zavrnjen od nepreverjenega.
                    val izid = try { Debrid.shraniPrejete(app, p) } catch (e: Exception) {
                        android.util.Log.w("SafeerDebrid", "prejeti kljuci: ${e.javaClass.simpleName}"); null
                    }
                    glavna.post {
                        if (isFinishing || isDestroyed) return@post
                        if (izid == null) { Toast.makeText(this, R.string.os_debrid_napaka_shranjevanja, Toast.LENGTH_LONG).show(); return@post }
                        if (izid.shranjeni.isNotEmpty())
                            Toast.makeText(this, getString(R.string.os_debrid_prejeto_shranjeno, izid.shranjeni.joinToString(", ") { ime(it) }), Toast.LENGTH_LONG).show()
                        if (izid.zavrnjeni.isNotEmpty())
                            Toast.makeText(this, getString(R.string.os_debrid_kljuc_zavrnjen, izid.zavrnjeni.joinToString(", ") { ime(it) }), Toast.LENGTH_LONG).show()
                        if (izid.nepreverjeni.isNotEmpty())
                            Toast.makeText(this, getString(R.string.os_debrid_prejeto_ni_preverjeno, izid.nepreverjeni.joinToString(", ") { ime(it) }), Toast.LENGTH_LONG).show()
                        osveziNaMestu()
                    }
                }, "safeer-debrid-prejeti").start()
            }
            .setNegativeButton(R.string.os_debrid_zavrni, null).create()
        // Privzeto Shrani: uporabnik je kljuce ravnokar poslal sam s svojega telefona.
        okno.setOnShowListener { okno.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus() }
        Kontroler.pokazi(okno); okno.show()
    }

    private val obPrejetihKljucih: () -> Unit = { if (!isFinishing) pokaziPrejeteKljuce() }

    /** Vnos ali odstranitev kljuca ene storitve (polje za geslo; kljuc se ne pokaze nazaj). */
    /** Naloga 141: vpis naslova in kljuca indeksatorja Usenet (Newznab); hrani se sifrirano na tej napravi. */
    private fun usenetIndeksator() {
        val rob = (20 * resources.displayMetrics.density).toInt()
        val naslov = android.widget.EditText(this).apply {
            hint = getString(R.string.os_usenet_naslov_namig)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        val kljuc = android.widget.EditText(this).apply {
            hint = getString(R.string.os_usenet_kljuc_namig)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val okvir = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(rob, rob / 2, rob, 0); addView(naslov); addView(kljuc) }
        val g = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.os_usenet_naslov)
            .setMessage(R.string.os_usenet_pojasnilo).setView(okvir)
            .setPositiveButton(R.string.os_torbox_shrani) { _, _ ->
                // Lastnik 25. 10. 2026 (kot kljuci debrid): kljuc preveri indeksator, preden se shrani - zavrnjen se ne shrani.
                val n = naslov.text.toString()
                val k = kljuc.text.toString()
                val ime = UsenetPravila.osnova(n)?.let { o -> try { java.net.URI(o).host } catch (_: Exception) { null } } ?: "Usenet"
                Toast.makeText(this, getString(R.string.os_debrid_preverjam, ime), Toast.LENGTH_SHORT).show()
                val app = applicationContext
                Thread({
                    // Pregled Grok 33, F9 / 34, E8: telo niti v try (izjema bi sesula aplikacijo).
                    val iv = try { Usenet.vpisi(app, n, k) } catch (e: Exception) {
                        android.util.Log.w("SafeerUsenet", "vpis: ${e.javaClass.simpleName}"); null
                    }
                    glavna.post {
                        if (isFinishing || isDestroyed) return@post
                        // Pregled Grok 41, Z9: izjema ni »napaka shranjevanja«; neuspela shramba ni »neveljaven vnos«.
                        if (iv == null) { Toast.makeText(this, getString(R.string.os_debrid_napaka_notranja, ime), Toast.LENGTH_LONG).show(); osveziNaMestu(); nastavitveDebrida(); return@post }
                        val izid = iv.preverba
                        val sporocilo = if (iv.napakaShrambe) getString(R.string.os_debrid_napaka_shranjevanja)
                            else if (iv.pocakaj) getString(R.string.os_debrid_preveri_pocakaj)
                            else if (iv.ostalo) brezOdgovora(izid, ime) else when (izid) {
                            DebridPravila.Preverba.VELJA -> getString(R.string.os_debrid_kljuc_velja, ime, "")
                            DebridPravila.Preverba.NE_VELJA -> getString(R.string.os_debrid_kljuc_zavrnjen, ime)
                            // Varovalo racunov: Newznab 101 - racun suspendiran, indeksatorja ne klicemo.
                            DebridPravila.Preverba.ZAKLENJEN -> getString(R.string.os_debrid_zaklenjen, ime)
                            // Pregled Grok 31, H1: kljuc v polju za naslov ali naslov brez imena - jasno, kaj popraviti (nic ni shranjeno).
                            DebridPravila.Preverba.OBLIKA -> getString(when (UsenetPravila.napakaVnosa(n, k)) {
                                UsenetPravila.NapakaVnosa.NASLOV_JE_KLJUC -> R.string.os_usenet_naslov_je_kljuc
                                UsenetPravila.NapakaVnosa.NASLOV -> R.string.os_usenet_naslov_napacen
                                else -> R.string.os_usenet_neveljavno
                            })
                            else -> getString(R.string.os_debrid_kljuc_nepreverjen, ime)
                        }
                        Toast.makeText(this, sporocilo, Toast.LENGTH_LONG).show()
                        osveziNaMestu(); nastavitveDebrida()
                    }
                }, "safeer-usenet-preverba").start()
            }
            .setNegativeButton(R.string.os_preklici) { _, _ -> nastavitveDebrida() }
        if (Usenet.imaIndeksator(this)) g.setNeutralButton(R.string.os_torbox_odstrani) { _, _ ->
            // Pregled Grok 33, F1: nepreverjen ali zavrnjen indeksator se odstrani samo tu.
            if (Usenet.odstrani(this)) Toast.makeText(this, R.string.os_usenet_odstranjeno, Toast.LENGTH_SHORT).show()
            else odstranjenoSporocilo("Usenet", false)
            osveziNaMestu(); nastavitveDebrida()
        }
        val okno = g.create()
        Kontroler.pokazi(okno); okno.show()
    }

    private fun kljucDebrida(p: String) {
        val ime = DebridPravila.ime(p)
        val rob = (20 * resources.displayMetrics.density).toInt()
        val polje = android.widget.EditText(this).apply {
            hint = getString(R.string.os_torbox_kljuc_namig)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val okvir = android.widget.FrameLayout(this).apply { setPadding(rob, rob / 2, rob, 0); addView(polje) }
        val g = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_debrid_kljuc_naslov, ime))
            .setMessage(getString(R.string.os_debrid_kljuc_pojasnilo, DebridPravila.kjeKljuc(p))).setView(okvir)
            .setPositiveButton(R.string.os_torbox_shrani) { _, _ ->
                // Lastnik 25. 10. 2026: kljuc preveri storitev, preden se shrani - zavrnjen se ne shrani in ne gre na druge naprave.
                val vnos = polje.text.toString()
                Toast.makeText(this, getString(R.string.os_debrid_preverjam, ime), Toast.LENGTH_SHORT).show()
                val app = applicationContext
                Thread({
                    // Pregled Grok 33, F9: telo niti v try (izjema bi sesula aplikacijo).
                    val iv = try { Debrid.vpisiKljuc(app, p, vnos) } catch (e: Exception) {
                        android.util.Log.w("SafeerDebrid", "vpis: ${e.javaClass.simpleName}"); Debrid.IzidVpisa(DebridPravila.Preverba.NI_ODGOVORA, null, notranja = true)
                    }
                    val izid = iv.preverba
                    val paket = iv.paket
                    glavna.post {
                        if (isFinishing || isDestroyed) return@post
                        // Pregled Grok 33, F9: medtem vpisan drug kljuc te storitve - njegov izid pove okno sam.
                        if (iv.zastarel) return@post
                        // Pregled Grok 33, F3: brez povezave novi kljuc caka ob strani, veljaven ostane v uporabi.
                        // Pregled Grok 41, Z9: izjema pri vpisu ni »napaka shranjevanja«.
                        val sporocilo = if (iv.notranja) getString(R.string.os_debrid_napaka_notranja, ime)
                            else if (iv.napakaShrambe) getString(R.string.os_debrid_napaka_shranjevanja)
                            else if (iv.pocakaj) getString(R.string.os_debrid_preveri_pocakaj)
                            else if (iv.ostalo) brezOdgovora(izid, ime)
                            else if (iv.caka) getString(R.string.os_debrid_kljuc_caka, ime) else when (izid) {
                            DebridPravila.Preverba.VELJA ->
                                getString(R.string.os_debrid_kljuc_velja, ime, paket?.let { " (" + DebridPravila.imePaketa(it) + ")" }.orEmpty())
                            DebridPravila.Preverba.NE_VELJA -> getString(R.string.os_debrid_kljuc_zavrnjen, ime)
                            DebridPravila.Preverba.ZAKLENJEN -> navodiloRacuna(p, DebridPravila.StanjeKljuca.ZAKLENJEN)
                            DebridPravila.Preverba.OBLIKA -> getString(R.string.os_torbox_neveljaven)
                            // Pregled Grok 33, F9: 429 ni »ni povezave«.
                            DebridPravila.Preverba.MEJA -> getString(R.string.os_debrid_kljuc_meja, ime)
                            else -> getString(R.string.os_debrid_kljuc_nepreverjen, ime)
                        }
                        Toast.makeText(this, sporocilo, Toast.LENGTH_LONG).show()
                        osveziNaMestu(); nastavitveDebrida()
                    }
                }, "safeer-debrid-preverba").start()
            }
            .setNegativeButton(R.string.os_preklici) { _, _ -> nastavitveDebrida() }
        if (Debrid.kljucShranjen(this, p)) g.setNeutralButton(R.string.os_torbox_odstrani) { _, _ ->
            // Pregled Grok 33, F1: izbris gre v ozji krog samo, ce je kljuc tu veljal.
            odstranjenoSporocilo(ime, Debrid.odstraniKljuc(this, p))
            osveziNaMestu(); nastavitveDebrida()
        }
        val okno = g.create()
        Kontroler.pokazi(okno); okno.show()
    }

    /**
     * Pregled Grok 41, Z2: storitev ni odgovorila (izpad ali meja) na »Preveri« ali ponoven vpis istega kljuca - stanje
     * »ne velja« ali »zaklenjen« ostane in okno to pove (prej »kljuc shranjen kot nepreverjen«).
     */
    private fun brezOdgovora(izid: DebridPravila.Preverba, ime: String): String =
        if (izid == DebridPravila.Preverba.MEJA) getString(R.string.os_debrid_preveri_meja, ime)
        else getString(R.string.os_debrid_preveri_ni_povezave, ime)

    /**
     * Pregled Grok 41, Z2: indeksator »ne velja« ali »zaklenjen« - Safeer ga ne klice. Kot [stanjeKljucaOkno]: navodilo,
     * »Preveri« (edina nova preverba, najvec enkrat na minuto) in »Vpisi nov kljuc« (okno z naslovom, kljucem in »Odstrani«).
     */
    private fun stanjeIndeksatorjaOkno(gost: String) {
        val ime = getString(R.string.os_usenet_ime_kratko, gost)
        val zaklenjen = Usenet.stanje(this) == DebridPravila.StanjeKljuca.ZAKLENJEN
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.os_usenet_naslov)
            .setMessage(getString(if (zaklenjen) R.string.os_debrid_zaklenjen else R.string.os_debrid_ne_velja_navodilo, ime))
            .setPositiveButton(R.string.os_debrid_preveri) { _, _ -> preveriIndeksatorRocno(ime) }
            .setNeutralButton(R.string.os_debrid_vpisi_nov) { _, _ -> usenetIndeksator() }
            .setNegativeButton(R.string.os_preklici) { _, _ -> nastavitveDebrida() }
            .create()
        Kontroler.pokazi(okno); okno.show()
    }

    private fun preveriIndeksatorRocno(ime: String) {
        Toast.makeText(this, getString(R.string.os_debrid_preverjam, ime), Toast.LENGTH_SHORT).show()
        val app = applicationContext
        Thread({
            var notranja = false
            val izid = try { Usenet.preveriRocno(app) } catch (e: Exception) {
                android.util.Log.w("SafeerUsenet", "rocna preverba: ${e.javaClass.simpleName}"); notranja = true; DebridPravila.Preverba.NI_ODGOVORA
            }
            glavna.post {
                if (isFinishing || isDestroyed) return@post
                val sporocilo = if (notranja) getString(R.string.os_debrid_napaka_notranja, ime) else when (DebridPravila.poPreveri(izid)) {
                    DebridPravila.PoPreveri.POCAKAJ -> getString(R.string.os_debrid_preveri_pocakaj)
                    DebridPravila.PoPreveri.VELJA -> getString(R.string.os_debrid_kljuc_velja, ime, "")
                    DebridPravila.PoPreveri.ZAKLENJEN -> getString(R.string.os_debrid_zaklenjen, ime)
                    DebridPravila.PoPreveri.NE_VELJA -> getString(R.string.os_debrid_ne_velja_navodilo, ime)
                    DebridPravila.PoPreveri.MEJA -> getString(R.string.os_debrid_preveri_meja, ime)
                    DebridPravila.PoPreveri.NI_ODGOVORA -> getString(R.string.os_debrid_preveri_ni_povezave, ime)
                }
                Toast.makeText(this, sporocilo, Toast.LENGTH_LONG).show()
                osveziNaMestu(); nastavitveDebrida()
            }
        }, "safeer-usenet-rocno").start()
    }

    /** Varovalo racunov: kaj narediti, ko kljuc ne velja ali je racun zaklenjen (Safeer storitve do »Preveri« ne klice). */
    private fun navodiloRacuna(p: String, s: DebridPravila.StanjeKljuca?): String =
        if (s == DebridPravila.StanjeKljuca.ZAKLENJEN) DebridBesedila.zaklenjen(this, p, DebridPravila.ime(p))
        else getString(R.string.os_debrid_ne_velja_navodilo, DebridPravila.ime(p))

    /**
     * Varovalo racunov K1 (lastnik 26. 10. 2026): kljuc ne velja ali je racun zaklenjen - Safeer storitve ne klice na nobeni
     * napravi. Okno pove, kaj narediti; »Preveri« je edina nova preverba (najvec enkrat na minuto), »Vpisi nov kljuc« odpre vpis.
     */
    private fun stanjeKljucaOkno(p: String) {
        val ime = DebridPravila.ime(p)
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_debrid_kljuc_naslov, ime))
            .setMessage(navodiloRacuna(p, Debrid.stanjeKljuca(this, p)?.first))
            .setPositiveButton(R.string.os_debrid_preveri) { _, _ -> preveriKljucRocno(p) }
            .setNeutralButton(R.string.os_debrid_vpisi_nov) { _, _ -> kljucDebrida(p) }
            .setNegativeButton(R.string.os_preklici) { _, _ -> nastavitveDebrida() }
            .create()
        Kontroler.pokazi(okno); okno.show()
    }

    private fun preveriKljucRocno(p: String) {
        val ime = DebridPravila.ime(p)
        Toast.makeText(this, getString(R.string.os_debrid_preverjam, ime), Toast.LENGTH_SHORT).show()
        val app = applicationContext
        Thread({
            var notranja = false
            val izid = try { Debrid.preveriRocno(app, p) } catch (e: Exception) {
                android.util.Log.w("SafeerDebrid", "rocna preverba: ${e.javaClass.simpleName}"); notranja = true; DebridPravila.Preverba.NI_ODGOVORA
            }
            glavna.post {
                if (isFinishing || isDestroyed) return@post
                // Pregled Grok 41, Z9: 429 ni »ni povezave« (stanje ostane, poskusi cez nekaj minut); izjema ni »ni povezave«.
                val sporocilo = if (notranja) getString(R.string.os_debrid_napaka_notranja, ime) else when (DebridPravila.poPreveri(izid)) {
                    DebridPravila.PoPreveri.POCAKAJ -> getString(R.string.os_debrid_preveri_pocakaj)
                    DebridPravila.PoPreveri.VELJA -> getString(R.string.os_debrid_kljuc_velja, ime, "")
                    DebridPravila.PoPreveri.ZAKLENJEN -> navodiloRacuna(p, DebridPravila.StanjeKljuca.ZAKLENJEN)
                    DebridPravila.PoPreveri.NE_VELJA -> navodiloRacuna(p, DebridPravila.StanjeKljuca.NE_VELJA)
                    DebridPravila.PoPreveri.MEJA -> getString(R.string.os_debrid_preveri_meja, ime)
                    DebridPravila.PoPreveri.NI_ODGOVORA -> getString(R.string.os_debrid_preveri_ni_povezave, ime)
                }
                Toast.makeText(this, sporocilo, Toast.LENGTH_LONG).show()
                osveziNaMestu(); nastavitveDebrida()
            }
        }, "safeer-debrid-rocno").start()
    }

    /** Pregled Grok 33, F1: po »Odstrani« uporabnik izve, ali je kljuc odstranjen tudi na drugih napravah ozjega kroga. */
    private fun odstranjenoSporocilo(ime: String, vKrog: Boolean) {
        Toast.makeText(this, getString(if (vKrog) R.string.os_debrid_odstranjeno else R.string.os_debrid_odstranjeno_lokalno, ime),
            if (vKrog) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
    }

    /**
     * Zakon solidarnosti v Linku: torrent prenasa in pretaka racunalnik (Safeer Control, `magnet.stream`),
     * ta naprava dobi le sproten tok kot pri spletnem videu - nic se ne prenasa in ne shranjuje nanjo.
     * Brez racunalnika televizor torrenta ne prenasa; telefon in tablica ga lahko, ce uporabnik izbere.
     */
    private fun torrentPrekRacunalnika(sk: Jamendo.Skladba, naslov: String, magnet: String, datoteka: Int = -1, prednost: String = "",
                                       /** Velikost datoteke po opisu toka (bajti), 0 = neznana. */
                                       velikost: Long = 0L) {
        if (prednost.isBlank()) { GlasbaStoritev.merimOd = android.os.SystemClock.uptimeMillis(); merim("izbran torrent, racunalnikov v krogu: ${racunalnikiZaPomoc().size}") }
        // Racunalnika, ki je ze odgovoril, da pretakanja torrentov ne pozna (Safeer za Windows), ne sprasujemo znova.
        val vsi = racunalnikiZaPomoc().filter { it.id !in NE_ZNA_TORRENTA }
            .sortedWith(compareByDescending<LinkOdjemalec.Naprava> { it.id == prednost }.thenByDescending { it.zmoznosti.contains("desktop") })
        // Nadzornik solidarnosti: (1) ce ima kateri racunalnik ta film ze (magnet.list), ga pretaka on -
        // ista vsebina se ne prenasa dvakrat na razlicne naprave; (2) sicer dobi delo racunalnik z najvec
        // proste moci (host.info), ne vedno isti.
        if (prednost.isBlank() && vsi.size > 1) {
            val proste = java.util.concurrent.ConcurrentHashMap<String, Double>()
            val zeIma = java.util.Collections.synchronizedSet(HashSet<String>())
            val hash = btih(magnet)
            var cakam = vsi.size * 2
            fun koncano() {
                if (--cakam != 0 || isFinishing) return
                val znajo = vsi.filter { it.id !in NE_ZNA_TORRENTA }
                val izbran = znajo.firstOrNull { it.id in zeIma } ?: znajo.maxByOrNull { proste[it.id] ?: 0.0 }
                merim("racunalnik izbran: ${izbran?.ime ?: "nobeden"}")
                // Noben racunalnik ne zna: "prednost" je ta naprava sama (ni v seznamu racunalnikov) - gremo naravnost naprej.
                torrentPrekRacunalnika(sk, naslov, magnet, datoteka, izbran?.id ?: Identiteta.id(this), velikost)
            }
            for (r in vsi) {
                link.ukaz(r.id, "host.info", org.json.JSONObject(), 2_500, LinkOdjemalec.Odgovor { izid, _ ->
                    izid?.optJSONObject("data")?.let { proste[r.id] = prostaMoc(it) }
                    koncano()
                })
                link.ukaz(r.id, "magnet.list", org.json.JSONObject(), 2_500, LinkOdjemalec.Odgovor { izid, napaka ->
                    if (neznanoDejanje(izid, napaka)) NE_ZNA_TORRENTA += r.id
                    val a = izid?.optJSONObject("data")?.optJSONArray("items")
                    for (i in 0 until (a?.length() ?: 0)) if (hash != null && btih(a!!.optJSONObject(i)?.optString("magnet").orEmpty()) == hash) zeIma += r.id
                    koncano()
                })
            }
            return
        }
        val racunalniki = vsi
        // Brez racunalnika (ali ce noben ne more): naprava predvaja sama ali prosi drugo napravo v krogu - brez okna.
        if (racunalniki.isEmpty()) { torrentBrezRacunalnika(sk, naslov, magnet, datoteka, velikost, ""); return }
        var zadnjaNapaka = ""
        fun poskusi(k: Int) {
            if (k >= racunalniki.size) { torrentBrezRacunalnika(sk, naslov, magnet, datoteka, velikost, zadnjaNapaka); return }
            val r = racunalniki[k]
            val ime = DatotekeActivity.lepoIme(r.ime).ifBlank { r.id }
            Toast.makeText(this, getString(R.string.os_stremio_racunalnik_pripravlja, ime), Toast.LENGTH_LONG).show()
            // »p2p«: uporabnik je opozorilo o roju na tej napravi potrdil ([opozoriPredRojem]) ali film naprava ze hrani.
            link.ukaz(r.id, "magnet.stream", org.json.JSONObject().put("uri", magnet).put("p2p", true).apply { if (datoteka >= 0) put("file", datoteka); opisVZahtevo(this, opisZa(sk, naslov)) }, 150_000, LinkOdjemalec.Odgovor { izid, napaka ->
                if (isFinishing) return@Odgovor
                val podatki = izid?.takeIf { it.optBoolean("ok") }?.optJSONObject("data")
                val srv = podatki?.optJSONObject("server")
                if (podatki == null || srv == null) {
                    if (neznanoDejanje(izid, napaka)) NE_ZNA_TORRENTA += r.id
                    zadnjaNapaka = izid?.optString("code")?.ifBlank { null } ?: izid?.optString("message") ?: napaka
                    merim("racunalnik $ime ne more: $zadnjaNapaka")
                    poskusi(k + 1)
                    return@Odgovor
                }
                merim("racunalnik $ime pripravljen")
                predvajajTokPomocnika(sk, naslov, magnet, r, srv, podatki.optString("path"), podnapisi = podatki.optJSONArray("subs"), imeVidea = podatki.optString("name"))
            })
        }
        poskusi(0)
    }

    /** Tok, ki ga pretaka naprava v Linku (racunalnik ali Safeer OS na telefonu, tablici, TV): pripet streznik + pot. */
    private fun predvajajTokPomocnika(sk: Jamendo.Skladba, naslov: String, magnet: String, r: LinkOdjemalec.Naprava, srv: org.json.JSONObject, pot: String,
                                      /** Pomocnik je racunalnik (Safeer Control); sicer naprava s Safeer OS - oznaka "#tn" za napis vira. */
                                      racunalnik: Boolean = true,
                                      /** Podnapisi iz istega torrenta (`subs` v odgovoru `magnet.stream`): tokovi z istega streznika. */
                                      podnapisi: org.json.JSONArray? = null, imeVidea: String = "") {
        val s = DatotekeActivity.Streznik(srv.optString("base_url").trimEnd('/'), srv.optString("fp"), srv.optString("token"), r.id)
        val url = s.osnova + pot
        val pod = KnjiznicaKroga.podnapisiToka((0 until (podnapisi?.length() ?: 0)).mapNotNull { i ->
            podnapisi?.optJSONObject(i)?.let { it.optString("path") to it.optString("name") } }).map { (p, ime) ->
            val (jezik, oznaka) = Podnapisi.jezik(imeVidea, ime)
            Podnapisi.Podnapis(s.osnova + p, ime.substringAfterLast('/'), jezik, oznaka, Podnapisi.mime(ime))
        }
        val pr = sk.copy(id = sk.id + (if (racunalnik) "#t" else "#tn") + magnet.hashCode(), naslov = naslov, zvok = url, povezava = url, video = true, podnapisi = pod)
        SEZNAMI.remove(VIDEO)   // polica "Prenosi na racunalniku" se osvezi
        // Naslov in plakat iz dodatka (ali da je naslov zaseben) si zapomnimo: kartica na polici je prepoznavna tudi,
        // ce pomocnik opisa ne zna hraniti (starejsa razlicica); zasebnega na polici ni.
        zapomniPrenos(magnet, opisZa(sk, naslov))
        zapomniZasebnega(magnet, sk, naslov)
        GlasbaStoritev.predvajaj(this, listOf(pr), 0, s)
        nadaljujKoPripravljen(pr)
        startActivity(Intent(this, PredvajanjeActivity::class.java))
    }

    /**
     * Grok 9, Z1/Z2: pred vsako potjo, ki to napravo (ali drugo napravo v istem domu - isti naslov IP) poveze z rojem brez
     * debrida, opozorilo kot pri magnet povezavi ([MagnetActivity]): privzeto Preklici, »Dodaj TorBox« samo brez storitve.
     * Potrditev velja za ta torrent do konca procesa (isti seznam kot MagnetActivity).
     */
    private fun opozoriPredRojem(magnet: String, naprej: () -> Unit) {
        val hash = MagnetMotor.hash(magnet) ?: btih(magnet)
        if (hash != null && hash in MagnetActivity.potrjenP2p) { naprej(); return }
        val b = AlertDialog.Builder(this).setTitle(R.string.magnet_p2p_naslov).setMessage(R.string.magnet_p2p_opis)
            .setPositiveButton(R.string.magnet_p2p_vseeno) { _, _ -> hash?.let { MagnetActivity.potrjenP2p.add(it) }; naprej() }
            .setNegativeButton(android.R.string.cancel, null)
        if (Debrid.aktivna(this) == null) b.setNeutralButton(R.string.magnet_p2p_dodaj) { _, _ -> nastavitveDebrida() }
        val okno = b.create()
        // Zadrzan OK na daljincu ne sme sam odpreti roja.
        okno.setOnShowListener { okno.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus() }
        okno.show()
    }

    /**
     * Torrent brez racunalnika v Linku (lastnik, 3. 10. 2026): naprava ga predvaja SAMA, ce datoteka gre na njen prosti
     * prostor (zacasno - po 12 urah brez predvajanja izgine sama); sicer prosi drugo napravo s Safeer OS v krogu
     * (telefon, tablico, televizor), da ga prenasa in ji ga pretaka. Brez okna in brez izbire: film se zacne ali pa
     * kratko povemo, zakaj ne.
     */
    private fun torrentBrezRacunalnika(sk: Jamendo.Skladba, naslov: String, magnet: String, datoteka: Int, velikost: Long, napakaRacunalnika: String,
                                       /** Polica »Preneseno«: film hrani ta naprava ([TA_NAPRAVA]) ali naprava s tem id - predvajamo od tam. */
                                       samoNaprava: String = "") {
        val drugi = if (samoNaprava.isBlank() || samoNaprava == TA_NAPRAVA) napraveZaTorrent() else napraveZaTorrent().filter { it.id == samoNaprava }
        fun konec(koda: String) {
            if (isFinishing) return
            android.util.Log.i("SafeerOsMedia", "torrent ni stekel: $koda")
            Toast.makeText(this, when (koda) {
                "ni_prostora" -> getString(R.string.os_stremio_ni_prostora_nikjer)
                "preobremenjen", "malo_pomnilnika", "baterija", "varcevanje", "pregreto", "predvaja", "sorodnik" -> getString(R.string.os_stremio_naprave_zasedene)
                else -> getString(R.string.os_media_ni_na_voljo, naslov)
            }, Toast.LENGTH_LONG).show()
        }
        fun prosi(k: Int, zadnja: String, od: Long = android.os.SystemClock.uptimeMillis()) {
            if (isFinishing) return
            if (k >= drugi.size) { konec(zadnja); return }
            val r = drugi[k]
            val prvic = android.os.SystemClock.uptimeMillis() - od < 500
            if (prvic) Toast.makeText(this, getString(R.string.os_stremio_naprava_pripravlja, DatotekeActivity.lepoIme(r.ime).ifBlank { r.id }), Toast.LENGTH_LONG).show()
            link.ukaz(r.id, "magnet.stream", org.json.JSONObject().put("uri", magnet).put("p2p", true).apply { if (datoteka >= 0) put("file", datoteka); opisVZahtevo(this, opisZa(sk, naslov)) }, 20_000, LinkOdjemalec.Odgovor { izid, napaka ->
                if (isFinishing) return@Odgovor
                val podatki = izid?.takeIf { it.optBoolean("ok") }?.optJSONObject("data")
                val srv = podatki?.optJSONObject("server")
                when {
                    podatki != null && srv != null -> { merim("naprava ${r.ime} pripravljena"); predvajajTokPomocnika(sk, naslov, magnet, r, srv, podatki.optString("path"), racunalnik = false,
                        podnapisi = podatki.optJSONArray("subs"), imeVidea = podatki.optString("name")) }
                    // Naprava se bere metapodatke torrenta (do minute): vprasamo znova - na 0,7 s (prej 2 s), da
                    // pripravljen tok ne caka na naslednje vprasanje.
                    podatki?.optBoolean("pending") == true && android.os.SystemClock.uptimeMillis() - od < 150_000 ->
                        glavna.postDelayed({ prosi(k, zadnja, od) }, 700)
                    else -> { merim("naprava ${r.ime} ne more: ${izid?.optString("code")}"); prosi(k + 1, izid?.optString("code")?.ifBlank { null } ?: zadnja) }
                }
            })
        }
        val prostor = MagnetMotor.prostorZaTok(this)
        val sam = if (samoNaprava == TA_NAPRAVA) MagnetMotor.naVoljo
            else samoNaprava.isBlank() && MagnetMotor.naVoljo && prostor > 0 && (velikost <= 0 || velikost <= prostor)
        merim(if (sam) "predvajam sam" else "prosim naprave: ${drugi.size}")
        if (!sam) { prosi(0, napakaRacunalnika.ifBlank { if (MagnetMotor.naVoljo) "ni_prostora" else "ni_podprto" }); return }
        Toast.makeText(this, R.string.os_stremio_pripravljam, Toast.LENGTH_LONG).show()
        val app = applicationContext
        Thread({
            val r: Any = try { MagnetMotor.pripraviTok(app, magnet, datoteka) } catch (e: Throwable) { e.message ?: "napaka" }
            glavna.post {
                if (isFinishing || isDestroyed) return@post
                if (r is MagnetMotor.Pripravljen) {
                    merim("sam pripravljen")
                    // Knjiznica kroga: druge naprave vidijo, kaj ta naprava hrani (zasebnega ne).
                    val opis = opisZa(sk, naslov)
                    try { MagnetMotor.zabeleziOpis(app, r.hash, opis, Identiteta.id(this)) } catch (_: Throwable) { }
                    zapomniPrenos(magnet, opis)
                    zapomniZasebnega(magnet, sk, naslov)
                    SEZNAMI.remove(VIDEO)
                    val podnapisi = r.podnapisi.map { (d, url) ->
                        val (jezik, oznaka) = Podnapisi.jezik(r.datoteka.ime, d.ime)
                        Podnapisi.Podnapis(url, d.ime.substringAfterLast('/'), jezik, oznaka, Podnapisi.mime(d.ime))
                    }
                    val pr = sk.copy(id = sk.id + "#t" + magnet.hashCode(), naslov = naslov, zvok = r.url, povezava = r.url, video = true, podnapisi = podnapisi)
                    GlasbaStoritev.predvajaj(this, listOf(pr), 0, null)
                    nadaljujKoPripravljen(pr)
                    startActivity(Intent(this, PredvajanjeActivity::class.java))
                } else prosi(0, r as String)
            }
        }, "safeer-torrent-tukaj").apply { isDaemon = true; start() }
    }

    /**
     * Racunalniki v Linku, ki pomagajo sibkejsim napravam (Safeer Control z datotekami): namizje, ne telefon/TV.
     * Brez jeTaNaprava(): ta po naslovu v omrezju lahko izloci racunalnik, kadar sredisce tece na njem.
     */
    /** Dnevnik cakanja pred zacetkom filma iz torrenta: koliko ms od dotika (GlasbaStoritev.merimOd). */
    private fun merim(kaj: String) {
        val od = GlasbaStoritev.merimOd
        if (od != 0L) android.util.Log.i("SafeerTorrentCas", "${android.os.SystemClock.uptimeMillis() - od} ms: $kaj")
    }

    /** Naprava je odgovorila, da dejanja ne pozna (starejsa ali drugacna razlicica brez pretakanja torrentov). */
    private fun neznanoDejanje(izid: org.json.JSONObject?, napaka: String? = null): Boolean =
        (izid?.optBoolean("ok") != true) && listOf(izid?.optString("message").orEmpty(), napaka.orEmpty())
            .any { it.contains("Neznano dejanje", ignoreCase = true) || it.contains("unknown action", ignoreCase = true) }

    private fun racunalnikiZaPomoc(): List<LinkOdjemalec.Naprava> =
        if (link.jeKrajevni() || !link.povezan) emptyList()
        else link.naprave.filter { n -> n.id != Identiteta.id(this) && "files" in n.zmoznosti &&
            ("desktop" in n.zmoznosti || n.platforma in setOf("linux", "windows", "macos")) }

    /**
     * Knjiznica kroga ([KnjiznicaKroga]): kar naprave v Linku (racunalniki s Safeer Control, telefoni, tablice, televizorji)
     * in ta naprava hranijo iz torrentov - z naslovom in plakatom iz dodatka, nikoli s surovim imenom torrenta. Zasebnega
     * na polici ni. Isti film pri vec napravah je en vnos. Klic iz delovne niti.
     */
    private fun prenosiNaRacunalnikih(
        /** Naslov zasebnega dodatka: samo ZASEBNI prenosi, ki jih je ta naprava prosila (polica v tem dodatku); null = skupna polica. */
        zasebniZa: String? = null, cakajS: Long = 10): List<Jamendo.Skladba> {
        val racunalniki = try { racunalnikiZaPomoc() } catch (_: Exception) { emptyList() }
        val naprave = try { napraveZaTorrent() } catch (_: Exception) { emptyList() }
        val imetniki = (racunalniki + naprave).distinctBy { it.id }
        val zapisi = getSharedPreferences(PREFS_PC_PRENOSI, MODE_PRIVATE)
        val zasebni = getSharedPreferences(PREFS_ZASEBNI_PRENOSI, MODE_PRIVATE)
        val dodatek = zasebniZa?.let { Stremio.osnova(it) }
        class Vnos(val sk: Jamendo.Skladba, val hash: String, val koncan: Boolean, val tukaj: Boolean, val racunalnik: Boolean)
        val izid = java.util.Collections.synchronizedList(mutableListOf<Vnos>())
        fun dodaj(a: org.json.JSONArray?, imetnik: String, ime: String, tukaj: Boolean, racunalnik: Boolean) {
            for (i in 0 until (a?.length() ?: 0)) {
                val t = a!!.optJSONObject(i) ?: continue
                val hash = btih(t.optString("magnet"))
                val lokalni = lokalniOpis(zapisi, hash)
                val (naslov, plakat) = if (dodatek == null)
                    (KnjiznicaKroga.zaPolico(t.optString("title"), t.optString("poster"), t.optBoolean("private"), lokalni) ?: continue)
                else {
                    if (!t.optBoolean("private") && lokalni?.zaseben != true) continue
                    // Naslov pozna samo ta naprava (pomocnik ima le oznako "zasebno"); prenos iz drugega zasebnega dodatka sodi na njegovo polico.
                    val z = zasebniOpis(zasebni, hash)
                    if (z != null && z.third.isNotBlank() && z.third != dodatek) continue
                    (z?.first?.takeIf { it.isNotBlank() } ?: getString(R.string.os_prenos_zaseben)) to z?.second.orEmpty()
                }
                val skupaj = t.optLong("size"); val dobljeno = t.optLong("done")
                val delez = if (skupaj > 0) (dobljeno * 100 / skupaj).toInt() else 0
                // »Obdrži«: naprava, ki ga pozna, v seznamu vedno pove `keep`; starejsa ne - pri njej moznosti ni.
                val drzi: Boolean? = if (t.has("keep")) t.optBoolean("keep") else null
                val opis = (if (drzi == true) getString(R.string.os_prenos_obdrzano) + " · " else "") +
                    ime + " · " + android.text.format.Formatter.formatShortFileSize(this, skupaj) +
                    (if (t.optBoolean("finished")) "" else " · $delez %")
                // Id javne kartice ostane, kot je bil (nanj je vezano mesto nadaljevanja); zasebna nosi oznako "z" - zanjo
                // veljajo pravila zasebnega dodatka (ni zgodovine, ni predaje). Ali je obdrzana, je ob strani (se spreminja).
                val id = PREDPONA_PC_PRENOSA + imetnik + "|" + t.optInt("id") + "|" + t.optInt("file", -1) + (if (dodatek != null) "|z" else "")
                OZNAKE_PRENOSOV[id] = KnjiznicaKroga.oznake(dodatek != null, drzi)
                izid += Vnos(Jamendo.Skladba(id,
                    naslov, opis, plakat, "", t.optString("magnet"), video = true, mediaType = "movie"),
                    hash.orEmpty(), t.optBoolean("finished"), tukaj, racunalnik)
            }
        }
        // Ta naprava: kar je prenesla sama, ko je film predvajala brez pomoci.
        try { dodaj(MagnetMotor.seznamZacasnih(this), TA_NAPRAVA, getString(R.string.os_prenos_ta_naprava), true, false) } catch (_: Throwable) { }
        if (imetniki.isNotEmpty()) {
            val cakam = java.util.concurrent.CountDownLatch(imetniki.size)
            glavna.post {
                for (r in imetniki) link.ukaz(r.id, "magnet.list", org.json.JSONObject(), 8_000, LinkOdjemalec.Odgovor { odgovor, _ ->
                    try {
                        dodaj(odgovor?.takeIf { it.optBoolean("ok") }?.optJSONObject("data")?.optJSONArray("items"), r.id,
                            DatotekeActivity.lepoIme(r.ime).ifBlank { r.id }, false, racunalniki.any { it.id == r.id })
                    } finally { cakam.countDown() }
                })
            }
            cakam.await(cakajS, java.util.concurrent.TimeUnit.SECONDS)
        }
        return KnjiznicaKroga.brezDvojnikov(izid.toList(), { it.hash }, { it.koncan }, { it.tukaj }, { it.racunalnik })
            .map { it.sk }.sortedBy { it.naslov.lowercase(Locale.ROOT) }
    }

    /** Kar ta naprava pove pomocniku o filmu, ki ga prosi: naslov in plakat iz dodatka; iz zasebnega dodatka samo oznako. */
    private fun opisZa(sk: Jamendo.Skladba, naslov: String): KnjiznicaKroga.Opis =
        KnjiznicaKroga.opis(naslov.ifBlank { sk.naslov }, sk.slika, sk.mediaType, if (sk.id.startsWith(PREDPONA_PC_PRENOSA)) "" else sk.id,
            Stremio.jeZasebna(sk) || lokalniOpis(getSharedPreferences(PREFS_PC_PRENOSI, MODE_PRIVATE), btih(sk.povezava))?.zaseben == true)

    private fun opisVZahtevo(p: org.json.JSONObject, o: KnjiznicaKroga.Opis) {
        if (o.zaseben) { p.put("private", true); return }
        if (o.naslov.isBlank()) return
        p.put("title", o.naslov)
        if (o.plakat.isNotBlank()) p.put("poster", o.plakat)
        if (o.vrsta.isNotBlank()) p.put("kind", o.vrsta)
        if (o.ref.isNotBlank()) p.put("ref", o.ref)
    }

    private fun zapomniPrenos(magnet: String, o: KnjiznicaKroga.Opis) {
        val h = btih(magnet) ?: return
        val prej = lokalniOpis(getSharedPreferences(PREFS_PC_PRENOSI, MODE_PRIVATE), h)
        val z = KnjiznicaKroga.zdruzi(prej, o)
        getSharedPreferences(PREFS_PC_PRENOSI, MODE_PRIVATE).edit().putString(h, org.json.JSONObject().put("t", z.naslov).put("p", z.plakat)
            .put("k", z.vrsta).put("r", z.ref).put("z", z.zaseben).toString()).apply()
    }

    /**
     * Zaseben prenos si z naslovom in plakatom zapomni SAMO ta naprava (pomocnik dobi le oznako "zasebno"): tako ga
     * uporabnik v svojem zasebnem dodatku najde, predvaja, obdrzi ali odstrani. Zapis ne gre nikamor in je viden
     * samo na polici v tem dodatku; po 30 dneh brez predvajanja ali ob odstranitvi prenosa izgine.
     */
    private fun zapomniZasebnega(magnet: String, sk: Jamendo.Skladba, naslov: String) {
        val h = btih(magnet) ?: return
        val p = getSharedPreferences(PREFS_ZASEBNI_PRENOSI, MODE_PRIVATE)
        val prej = zasebniOpis(p, h)
        val kartica = sk.id.startsWith(PREDPONA_PC_PRENOSA)
        if (!Stremio.jeZasebna(sk) || (kartica && prej == null)) return
        val z = if (kartica) prej!! else Triple(naslov.ifBlank { sk.naslov }, sk.slika, Stremio.razstavi(sk)?.first.orEmpty())
        val zdaj = System.currentTimeMillis()
        val e = p.edit().putString(h, org.json.JSONObject().put("t", z.first).put("p", z.second).put("d", z.third).put("c", zdaj).toString())
        // Stari zapisi ne ostajajo: cesar 30 dni nihce ni predvajal, je s pomocnika ze zdavnaj izginilo.
        for ((k, v) in p.all) if (k != h && (try { org.json.JSONObject(v as String).optLong("c") } catch (_: Exception) { 0L }) < zdaj - ZASEBNI_ZAPIS_VELJA_MS) e.remove(k)
        e.apply()
    }

    /** (naslov, plakat, osnova dodatka) zasebnega prenosa, ce si ga je ta naprava zapomnila. */
    private fun zasebniOpis(zapisi: android.content.SharedPreferences, hash: String?): Triple<String, String, String>? {
        val v = hash?.let { zapisi.getString(it, "") }.orEmpty()
        if (!v.startsWith("{")) return null
        return try { org.json.JSONObject(v).let { Triple(it.optString("t"), it.optString("p"), it.optString("d")) } } catch (_: Exception) { null }
    }

    /** Zapis te naprave o prenosu (naslov, plakat, zasebnost); starejsi zapis (samo plakat) ne steje - brez naslova ni kartice. */
    private fun lokalniOpis(zapisi: android.content.SharedPreferences, hash: String?): KnjiznicaKroga.Opis? {
        val v = hash?.let { zapisi.getString(it, "") }.orEmpty()
        if (!v.startsWith("{")) return null
        return try {
            val j = org.json.JSONObject(v)
            KnjiznicaKroga.Opis(j.optString("t"), j.optString("p"), j.optString("k"), j.optString("r"), j.optBoolean("z"))
        } catch (_: Exception) { null }
    }

    /** Prosta moc racunalnika iz host.info: prosta jedra + prosti pomnilnik (GB); brez prostora na disku 0. */
    private fun prostaMoc(d: org.json.JSONObject): Double {
        if (d.optJSONObject("pomoc")?.optBoolean("lahko", true) == false) return 0.0
        val cpu = d.optJSONObject("cpu"); val ram = d.optJSONObject("ram"); val disk = d.optJSONObject("disk")
        if (disk != null && disk.optLong("prosto", Long.MAX_VALUE) < 3L * 1024 * 1024 * 1024) return 0.0
        val jedra = cpu?.optDouble("jedra", 1.0) ?: 1.0
        val prostaJedra = (jedra - (cpu?.optDouble("obremenitev", 0.0) ?: 0.0)).coerceAtLeast(0.0)
        return prostaJedra + (ram?.optLong("prosto", 0L) ?: 0L) / 1e9
    }

    private fun btih(magnet: String): String? =
        Regex("btih:([0-9a-zA-Z]{32,40})").find(magnet)?.groupValues?.get(1)?.lowercase(Locale.ROOT)

    /** Prenos s police »Preneseno«: predvajaj od naprave, ki ga hrani, ali ga tam odstrani, ko ga ne rabis vec. */
    private fun meniPrenosa(sk: Jamendo.Skladba) {
        val d = sk.id.removePrefix(PREDPONA_PC_PRENOSA).split('|')
        if (d.size < 3) return
        val (pc, tid, datoteka) = Triple(d[0], d[1].toIntOrNull() ?: return, d[2].toIntOrNull() ?: -1)
        val oznake = OZNAKE_PRENOSOV[sk.id.substringBefore('#')].orEmpty()
        val tukaj = pc == TA_NAPRAVA
        // Polica se nalozi znova: skupna v razdelku Video, zasebna v odprtem zasebnem dodatku.
        fun osveziPolico() {
            SEZNAMI.remove(VIDEO)
            val o = odprtKatalog
            // Skupna polica: razdelek se narise znova na istem mestu (uporabnik ostane pri kartici, ki jo je pravkar spremenil).
            if (o != null && o.dodatek.isNotBlank()) odpriRazdelekDodatka(o.dodatek) else if (razdelek == VIDEO && o == null) osveziNaMestu()
        }
        fun odstranjeno(ok: Boolean, sporocilo: String?) {
            if (isFinishing) return
            Toast.makeText(this, if (ok) getString(R.string.os_prenos_odstranjen)
                else getString(R.string.os_stremio_torrent_napaka, sporocilo.orEmpty()), Toast.LENGTH_LONG).show()
            if (!ok) return
            // Prenosa ni vec: tudi zapis te naprave o njem (naslov zasebnega prenosa) ne ostane.
            btih(sk.povezava)?.let { h ->
                getSharedPreferences(PREFS_ZASEBNI_PRENOSI, MODE_PRIVATE).edit().remove(h).apply()
                getSharedPreferences(PREFS_PC_PRENOSI, MODE_PRIVATE).edit().remove(h).apply()
            }
            osveziPolico()
        }
        fun predvajajPrenos() = when {
            tukaj -> torrentBrezRacunalnika(sk, sk.naslov, sk.povezava, datoteka, 0L, "", samoNaprava = TA_NAPRAVA)
            racunalnikiZaPomoc().any { it.id == pc } -> torrentPrekRacunalnika(sk, sk.naslov, sk.povezava, datoteka, pc)
            else -> torrentBrezRacunalnika(sk, sk.naslov, sk.povezava, datoteka, 0L, "", samoNaprava = pc)
        }
        // »Obdrži«: prenos ne potece po 12 urah. Oznako hrani naprava, ki film hrani.
        fun obdrzi(drzi: Boolean) {
            fun konec(ok: Boolean) {
                if (isFinishing) return
                Toast.makeText(this, when { !ok -> R.string.os_prenos_obdrzi_napaka; drzi -> R.string.os_prenos_obdrzan; else -> R.string.os_prenos_ni_vec_obdrzan }, Toast.LENGTH_LONG).show()
                if (ok) osveziPolico()
            }
            if (tukaj) konec(try { MagnetMotor.nastaviObdrzi(applicationContext, tid, drzi) } catch (_: Throwable) { false })
            else link.ukaz(pc, "magnet.keep", org.json.JSONObject().put("id", tid).put("keep", drzi), 20_000, LinkOdjemalec.Odgovor { izid, _ ->
                konec(izid?.optBoolean("ok") == true)
            })
        }
        fun odstrani() {
            AlertDialog.Builder(this).setTitle(sk.naslov).setMessage(R.string.os_prenos_odstrani_vprasanje)
                .setPositiveButton(R.string.os_prenos_odstrani) { _, _ ->
                    if (tukaj) {
                        val app = applicationContext
                        Thread({
                            val ok = try { MagnetMotor.odstraniZacasnega(app, tid) } catch (_: Throwable) { false }
                            glavna.post { odstranjeno(ok, null) }
                        }, "safeer-prenos-odstrani").apply { isDaemon = true; start() }
                    } else link.ukaz(pc, "magnet.remove", org.json.JSONObject().put("id", tid), 20_000, LinkOdjemalec.Odgovor { izid, napaka ->
                        odstranjeno(izid?.optBoolean("ok") == true, izid?.optString("message") ?: napaka)
                    })
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        val dejanja = mutableListOf<Pair<String, () -> Unit>>(getString(R.string.os_prenos_predvajaj) to { predvajajPrenos() })
        // Samo, kadar naprava, ki film hrani, »Obdrži« pozna (sicer moznosti ne ponudimo).
        if (KnjiznicaKroga.znaObdrzi(oznake)) KnjiznicaKroga.jeObdrzan(oznake).let { je ->
            dejanja += getString(if (je) R.string.os_prenos_ne_obdrzi else R.string.os_prenos_obdrzi) to { obdrzi(!je) }
        }
        dejanja += getString(R.string.os_prenos_odstrani) to { odstrani() }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(sk.naslov)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun razresiJavnoLast(sk: Jamendo.Skladba) {
        val priprava = zacniPripravo(sk) ?: return
        stanje.text = getString(R.string.os_glasba_nalagam)
        delavec.execute {
            val r = try { JavnaLast.razresi(sk) } catch (_: Exception) { null }
            glavna.post {
                if (isFinishing) { koncajPripravo(priprava); return@post }
                if (r == null) { pripravaNiUspela(priprava, sk); return@post }
                koncajPripravo(priprava)
                stanje.text = if (razdelek == ISKANJE && zadetki != null) opisZadetkov else opis(razdelek)
                GlasbaStoritev.predvajaj(this, listOf(r), 0)
                nadaljujKoPripravljen(sk)
                startActivity(Intent(this, PredvajanjeActivity::class.java))
            }
        }
    }

    /** TuneIn objavi tok sele ob kliku; neuspeh ostane omejen na izbrano postajo. */
    private fun razresiTuneIn(sk: Jamendo.Skladba) {
        val priprava = zacniPripravo(sk) ?: return
        stanje.text = getString(R.string.os_glasba_nalagam)
        delavec.execute {
            val r = try { TuneIn.razresi(sk) } catch (_: Exception) { null }
            glavna.post {
                if (isFinishing) { koncajPripravo(priprava); return@post }
                if (r == null) { pripravaNiUspela(priprava, sk); return@post }
                koncajPripravo(priprava)
                stanje.text = if (razdelek == ISKANJE && zadetki != null) opisZadetkov else opis(razdelek)
                GlasbaStoritev.predvajaj(this, listOf(r), 0)
            }
        }
    }

    private fun osveziZdaj() {
        val sk = GlasbaStoritev.trenutna()
        val p = GlasbaStoritev.predvajalnik
        // Na podstrani plosce (seznam predvajanja, »Pokazi vse«) velike plosce ni: ne risemo je cez podstran, pokazemo malo vrstico.
        val naPlosci = razdelek == DOMOV && !podstrani.odprta
        if (naPlosci) {
            // Plosca se zamenja, ko se predvajanje zacne ali konca; sicer samo osvezimo njene podatke.
            if (sk != null && p != null) imaNedavno.pozabi()   // predvajanje doda med nedavno: po koncu naj se prebere znova
            val zeli = if (sk != null && p != null) true
                else if (imaNedavno.daj(android.os.SystemClock.uptimeMillis()) { MedijskiViri.nedavno(this).isNotEmpty() }) false else null
            // Tudi ko glasba preide na zvocnik ali z njega (dejanja ob plosci so takrat druga).
            if (zeli != pZaPredvajanje || (zeli == true && pNaZvocniku != GlasbaStoritev.naZvocniku())) {
                // Predvajanje se je zacelo: plosca »Zdaj se predvaja« je odgovor na uporabnikovo izbiro - stran gre k njej.
                // Konec predvajanja in prehod na zvocnik ali z njega prideta tudi sama od sebe: stran ostane, kjer je.
                if (zeli == true && pZaPredvajanje != true) {
                    // Glasba in radio ostaneta na tem zaslonu (uporabnik vidi vrh s plosco): mesto dotika ne velja vec. Video
                    // odpre predvajalnik - mesto ostane za vrnitev.
                    if (GlasbaStoritev.trenutna()?.video != true) mestoKlika = null
                    izberi(DOMOV)
                } else izberiPriKartici(DOMOV)
            } else osveziPlosco()
        }
        vrstica.visibility = if (sk == null || p == null || naPlosci) View.GONE else View.VISIBLE
        if (sk == null || p == null) return
        // Naloga 77: napredek videa najvec na 5 s (kot v PredvajanjeActivity), ne vsako sekundo: vsak zapis razclenil in znova
        // zapisal ves JSON v SharedPreferences.
        val zdajMs = android.os.SystemClock.uptimeMillis()
        if (OsPravila.jeCasZaZapis(napredekZapisanOb, zdajMs, 5_000)) {
            napredekZapisanOb = zdajMs
            val tokPomocnika = SprotnaPomoc.tokZa(sk)
            if (tokPomocnika != null) { if (tokPomocnika.izvirnik.video) MediaNapredek.zapisi(this, tokPomocnika.izvirnik, tokPomocnika.zamikMs + p.currentPosition.coerceAtLeast(0), tokPomocnika.trajanjeMs, tokPomocnika.streznikIzvirnika?.naprava.orEmpty()) }
            else if (sk.video) MediaNapredek.zapisi(this, sk, p.currentPosition.coerceAtLeast(0), p.duration.coerceAtLeast(0), GlasbaStoritev.streznikTrenutni?.naprava.orEmpty())
        }
        zdajNaslov.text = sk.naslov
        // Mala vrstica pove tudi, da glasba igra na zvocniku (ali da se zvocnik ne odziva) - sicer se ne vidi, da gumb upravlja zvocnik.
        // Napis zvocnika je prvi: vrstica je ena in dolgo ime izvajalca (radio javlja »izvajalec - skladba«) bi ga sicer odrezalo.
        zdajIzvajalec.text = listOf(if (GlasbaStoritev.naZvocniku()) Zvocniki.napis(this).orEmpty() else "", if (SpletniVir.jeEnota(sk)) "" else sk.izvajalec)
            .filter { it.isNotBlank() }.joinToString(" · ")
        val igra = GlasbaStoritev.igra()
        zdajCas.text = if (igra) "▶" else "❚❚"
        (zdajGumb?.getChildAt(0) as? ImageView)?.setImageResource(if (igra) R.drawable.os_ikona_pavza else R.drawable.os_ikona_predvajaj)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { GlasbaStoritev.preklopi(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { GlasbaStoritev.nadaljuj(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { GlasbaStoritev.premor(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { GlasbaStoritev.naslednja(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { GlasbaStoritev.prejsnja(); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { GlasbaStoritev.ustavi(this); return true }
            KeyEvent.KEYCODE_SEARCH -> { odpriIskanje(""); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { event?.startTracking() }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y -> {
                val f = currentFocus
                if (f != null && f.performLongClick()) return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            val f = currentFocus
            if (f != null && f.performLongClick()) return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    companion object {
        private const val NAMEN_OBDELAN = "safeer.namen.obdelan"
        /** Risanje po korakih: casovni proracun enega kosa na glavni niti. */
        private const val PRORACUN_MS = 8L
        /** Beseda za iskanje ob odprtju (prazna: samo odpri iskanje), npr. z zaslona predvajanja. */
        const val ISKANJE_BESEDA = "iskanje"
        const val PREIZKUS_IMDB = "preizkus_imdb"
        const val PREIZKUS_NASLOV = "preizkus_naslov"
        /** Pregled B2: enkratni zeton preizkusa ([izdajZetonPreizkusa]); brez njega [PREIZKUS_IMDB] ne velja. */
        const val PREIZKUS_ZETON = "preizkus_zeton"
        @Volatile private var zetonPreizkusa: Pair<String, Long>? = null

        /** Pregled B2: zeton za en preizkus - klice ga SAMO PreizkusActivity (izvozena le z dovoljenjem DUMP, torej adb). */
        fun izdajZetonPreizkusa(): String {
            val z = java.util.UUID.randomUUID().toString()
            zetonPreizkusa = z to android.os.SystemClock.elapsedRealtime()
            return z
        }

        /** Pregled B2: zeton velja enkrat ([OsPravila.zetonPreizkusaVelja]); porabljen se pobrise. */
        @Synchronized private fun vzemiZetonPreizkusa(prejet: String?): Boolean {
            val velja = OsPravila.zetonPreizkusaVelja(zetonPreizkusa, prejet, android.os.SystemClock.elapsedRealtime())
            if (velja) zetonPreizkusa = null
            return velja
        }

        /** Pregled A1: po tolikem cakanju na debrid/Usenet se odpre okno z napredkom in »Preklici«. */
        private const val OKNO_DEBRIDA_PO_MS = 1_500L
        /** Z [ISKANJE_BESEDA]: film ali serija z natancno tem naslovom se odpre sama (media.open, PrikaziNa). */
        const val ISKANJE_NATANCNO = "iskanje_natancno"
        /** Zavihek spodnje vrstice (StranskaVrstica.Zavihek.name), ki naj se odpre. */
        const val ZAVIHEK = "zavihek"
        /** "Poslji na napravo": uporabnik je v pasici ali obvestilu izbral Sprejmi - Medijski center ponudbo prevzame. */
        const val EXTRA_PREDAJA_SPREJMI = "predaja_sprejmi"
        /** Magnet povezave: »Dodaj TorBox« v opozorilu pred P2P odpre okno »Debrid storitve« (MagnetActivity). */
        const val EXTRA_DEBRID = "odpri_debrid"
        private const val ZAHTEVA_PREDSTAVNOST = 7413

        /** Safeer Media je odprt (pod predvajalnikom); sicer ga Nazaj v predvajalniku odpre. */
        /** Stevec zivih Medijskih centrov (prej da/ne: zaprtje enega je "pozabilo" na drugega in Nazaj s
         *  predvajanja je odprl novega - stresni test 1. 10. 2026: 13 Medijskih centrov na skladu). */
        @Volatile var odprtih = 0
            private set
        val odprta: Boolean get() = odprtih > 0

        private const val PREDVAJALNIK_DATOTEKA = 7412
        private const val KLJUC_PREDVAJALNIK = "kat-predvajalnik"
        private const val DOMOV = 0; private const val GLASBA = 2; private const val RADIO = 3
        private const val PREDPONA_PC_PRENOSA = KnjiznicaKroga.PREDPONA_PRENOSA
        /** Naslov in plakat zasebnega prenosa: samo na napravi, ki ga je prosila, in samo za polico v zasebnem dodatku. */
        private const val PREFS_ZASEBNI_PRENOSI = "safeer_zasebni_prenosi"
        /** Zadnja moznost izbire zvrsti pri Filmih in Serijah: katalogi zasebnih dodatkov (med ostale se ne mesajo). */
        private const val ZVRST_ZASEBNI = "zasebni"
        private const val ZASEBNI_ZAPIS_VELJA_MS = 30L * 24 * 3_600_000
        /** Oznake kartic na polici prenosov (id kartice -> [KnjiznicaKroga.oznake]): ali naprava pozna »Obdrži« in ali je prenos obdrzan. */
        private val OZNAKE_PRENOSOV = java.util.concurrent.ConcurrentHashMap<String, String>()
        /** Imetnik prenosa na polici »Preneseno«, kadar film hrani ta naprava sama. */
        private const val TA_NAPRAVA = "tukaj"
        private const val PREFS_PC_PRENOSI = "safeer_pc_prenosi"
        private const val VIDEO = 4; private const val VIRI = 6; private const val ISKANJE = 7; private const val TV_V_ZIVO = 8
        private const val RAZVRSTI_PRIPOROCENO = 0
        private const val RAZVRSTI_NAJNOVEJSE = 1
        private const val RAZVRSTI_NAJSTAREJSE = 2
        private const val RAZVRSTI_IME_AZ = 3
        private const val RAZVRSTI_IME_ZA = 4
        private const val PREDPONA_VGRAJENEGA = "vgrajen:"
        private const val VIR_JAMENDO = "vgrajen:jamendo"
        private const val VIR_RADIO = "vgrajen:radio"
        private const val VIR_TUNEIN = "vgrajen:tunein"
        private const val VIR_PEERTUBE = "vgrajen:peertube"
        private const val VIR_JAVNA_LAST = "vgrajen:javna-last"
        private const val VIR_TV = "vgrajen:tv"
        private const val GLAS = 41
        private const val NASLOV_VRSTE = "naslov-vrste"
        private const val MREZA_VRSTA = "mreza-vrsta"
        private const val SIDRO_MREZE = "mreza-sidro"
        private const val POLNILO_MREZE = "mreza-polnilo"
        private const val POLICA_KARTIC = "polica-kartic"
        /** Kartica v mrezi Mojih virov: dve vrsti gresta na prvi zaslon televizorja. */
        private const val MREZA_DP = 116
        /** Katalog Stremio ima "Pokazi vse"/"Nalozi vec" sele, ko je stran vsaj tako dolga (kratki katalogi so celi na polici). */
        private const val STRAN_KATALOGA_MIN = 20
        /** Kako dolgo zadnja znana mreza kanalov z diska se velja za prvi prikaz po zagonu ([naloziKanale]). */
        private const val KANALI_SEME_MS = 7L * 24 * 3_600_000
        /** Naloga 82: najvec kanalov brez izbire, ki jih pokazemo kot nadomestek prazne izbire. */
        private const val NADOMESTNI_KANALI = 60
        /** Racunalniki v Linku, ki so odgovorili, da pretakanja torrentov ne poznajo (do konca zivljenja procesa). */
        private val NE_ZNA_TORRENTA: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        /** Vljudno preverjanje mreze: okno ob odprtju, podaljsanje okna ob vsakem "vec", najvec poizvedb na uporabnikovo dejanje. */
        /**
         * Stran stroge mreze (lastnik, 22. 10. 2026: »naj se nalozi npr. 100 filmov, nato ... naslednja stran - spet 100«):
         * okno ob odprtju, podaljsanje ob Nalozi vec in proracun poizvedb na uporabnikovo dejanje. Hitrost poizvedb do
         * dodatka se ne spremeni (Stremio.zeton: 4 takoj, nato ena na 8 s na dodatek; urni proracun TempoDodatka).
         * Do naloge 55: okno 30, korak 30, proracun 40.
         */
        private const val STRAN_MREZE = 100
        private const val ZELJA_KARTIC = STRAN_MREZE
        /** Naloga 55: koliko kandidatov za oknom stroge mreze pregledamo ob izrisu (ne vseh nalozenih naslovov). */
        private const val REZERVA_KANDIDATOV = 100
        /** Naloga 55: do toliko kartic se mreza dopolni takoj (prvi zaslon), potem najvec vsakih DOPOLNI_NAJVEC_MS. */
        private const val PRVI_ZASLON = 21
        private const val DOPOLNI_NAJVEC_MS = 3_000L
        /** Naloga 55: koliko casa velja izracun za izris (meja torrenta, dodatki, omrezje). */
        private const val IZRIS_VELJA_MS = 2_000L
        /** Mreza kanalov: okno preverjenih kanalov iz dodatkov in najvec poizvedb po toku na uporabnikovo dejanje. */
        private const val OKNO_KANALOV = 100
        /** Naloga 74: najvec hkratnih preverjanj kanalov v ozadju (nizka prednost, samo vidne kartice). */
        private const val KANALI_PREVERJAM_HKRATI = 3
        /** Naloga 74: tako dolgo po kliku na kanal preverjanje v ozadju pocaka (tok klika ima omrezje zase). */
        private const val PREDNOST_KLIKA_MS = 12_000L
        /** Naloga 73: najvec hkratnih strani katalogov kanalov na vseh dodatkih skupaj. */
        private const val KANALI_HKRATI = 3
        private const val KANALI_VRSTA = 64
        private const val PRORACUN_KANALOV = 80
        /** Po toliko zaporednih vprasanjih brez odgovora dodatka nehamo preverjati kanale do uporabnikovega dejanja. */
        private const val BREZ_ODGOVORA_NAJVEC = 3
        private const val KORAK_KARTIC = STRAN_MREZE
        private const val PRORACUN_PREVERJANJ = STRAN_MREZE
        /** Koliko zastarelih odgovorov osvezimo v ozadju ob enem klicu preverjanja in koliko poizvedb v ozadju tece hkrati. */
        private const val OSVEZI_NA_OBISK = 10
        private const val OZADJE_HKRATI = 2
        private const val KLJUC_GLASBA = "k:kat:glasba"; private const val KLJUC_VIDEO = "k:kat:video"
        private const val KLJUC_RADIO = "k:kat:radio"; private const val KLJUC_VIRI = "k:kat:viri"
        private const val KLJUC_TV = "k:kat:tv"
        /** Glasbene zvrsti: (oznaka Jamendo, oznaka Radio Browser, ime). Enake kot v Safeer OS na racunalniku. */
        private val ZVRSTI = listOf(
            Triple("pop", "pop", R.string.os_media_zvrst_pop),
            Triple("rock", "rock", R.string.os_media_zvrst_rock),
            Triple("electronic", "electronic", R.string.os_media_zvrst_electronic),
            Triple("hiphop", "hip hop", R.string.os_media_zvrst_hiphop),
            Triple("jazz", "jazz", R.string.os_media_zvrst_jazz),
            Triple("classical", "classical", R.string.os_media_zvrst_classical),
            Triple("metal", "metal", R.string.os_media_zvrst_metal),
            Triple("dance", "dance", R.string.os_media_zvrst_dance),
            Triple("folk", "folk", R.string.os_media_zvrst_folk),
            Triple("reggae", "reggae", R.string.os_media_zvrst_reggae),
            Triple("ambient", "chillout", R.string.os_media_zvrst_ambient),
            Triple("soundtrack", "soundtrack", R.string.os_media_zvrst_soundtrack),
            Triple("country", "country", R.string.os_media_zvrst_country),
            Triple("", "news", R.string.os_media_zvrst_news),
        )
        private const val KLJUC_VSI_VIRI = "k:v:vsi"
        private const val NASTAVITVE_POGLEDA = "safeer_media_pogled"
        /** Naloga 91b: zadnji znani jeziki katalogov kanalov dodatkov (kljuci, locene z vejico). */
        private const val JEZIKI_KANALOV = "tv_jeziki_dodatkov"
        /** Naloga 100: [JEZIKI_KANALOV] v pomnilniku (null = se ni prebrano). */
        @Volatile private var SHRANJENI_JEZIKI_KANALOV: List<String>? = null
        private const val KLJUC_JEZIKOV = "izklopljeni_jeziki_"
        private val HITROSTI = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        private val CASOVNIK = intArrayOf(15, 30, 60, 90)

        /** Seznami razdelkov za cas delovanja aplikacije (ponovna izbira je takojsnja). */
        private val SEZNAMI = HashMap<Int, List<Podatki>>()
        /** Zadnja znana prva stran mreze Filmi | Serije po kljucu (tip, zvrst, jezik): ob ponovnem odprtju takoj na zaslonu. */
        private val BRSKANJE = java.util.concurrent.ConcurrentHashMap<String, List<Jamendo.Skladba>>()
        /** Radio in Glasba: izbire mreze (dokler je aplikacija odprta) in zadnja znana prva stran vsake izbire. */
        private var radioSkupina = ""
        private var radioZvrst = ""
        private var glasbaZvrst = ""
        private const val SKUPINA_SVET = "svet"
        private const val SKUPINA_VIDEOSPOTI = "videospoti"
        private const val SKUPINA_MOJE = "moje"
        /** Glasba iz shrambe naprave (samostojni Predvajalnik). */
        private const val SKUPINA_NAPRAVA = "naprava"
        private const val RADIO_NA_STRAN = 60
        private const val GLASBA_NA_STRAN = 48
        private val VSEBINA_MREZE = java.util.concurrent.ConcurrentHashMap<String, List<Jamendo.Skladba>>()
        /** TV v zivo: izbrana zvrst in jezik (dokler je aplikacija odprta), zadnje znane izbire in zadnja mreza vsake izbire. */
        private var tvZvrst = ""
        private var tvJezik = ""
        /** Naloga 79b: Live TV kaze tudi kanale dodatkov, ki tokove dajejo samo z narocnino (dokler je aplikacija odprta). */
        private var tvZaklenjeni = false
        /** 25. 10. 2026: izbira »Priljubljeni« v TV v zivo - samo priljubljeni kanali (do konca procesa, kot [tvZaklenjeni]). */
        private var tvSamoPriljubljeni = false
        @Volatile private var FILTRI_KANALOV: KanaliPravila.Filtri? = null
        private val KANALI_MREZE = java.util.concurrent.ConcurrentHashMap<String, Pair<List<Jamendo.Skladba>, List<Jamendo.Skladba>>>()
        /** Vrstni red kanalov (id-ji), kot ga je uporabnik pri izbiri »zvrst|jezik|razvrstitev« nazadnje videl - samo v pomnilniku. */
        private val KANALI_RED = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
        /** Naloga 76: pregled zasebnih katalogov, ki tece (en na proces), in njegov zaklep. Zapisi so v [ZasebniKanali]. */
        @Volatile private var PREGLED_ZASEBNIH: PregledZasebnih? = null
        private val ZASEBNI_ZAKLEP = Any()
        /** Naloga 76: zasebni katalogi hkrati (bazen kanalov ima [KANALI_HKRATI] niti) in najvec strani na katalog. */
        private const val ZASEBNI_HKRATI = 2
        private const val ZASEBNI_STRANI = 20
        /** Imena znanih kategorij kanalov ([KanaliPravila.KLJUCI_KATEGORIJ]). */
        private val IMENA_KATEGORIJ = mapOf(
            "novice" to R.string.os_kanali_novice,
            "sport" to R.string.os_kanali_sport,
            "filmi" to R.string.os_kanali_filmi,
            "serije" to R.string.os_kanali_serije,
            "otroski" to R.string.os_kanali_otroski,
            "glasba" to R.string.os_kanali_glasba,
            "dokumentarni" to R.string.os_kanali_dokumentarni,
            "razvedrilo" to R.string.os_kanali_razvedrilo,
            "splosni" to R.string.os_kanali_splosni,
            "animirani" to R.string.os_kanali_animirani,
            "komedija" to R.string.os_kanali_komedija,
            "kultura" to R.string.os_kanali_kultura,
            "izobrazevanje" to R.string.os_kanali_izobrazevanje,
            "druzinski" to R.string.os_kanali_druzinski,
            "slog" to R.string.os_kanali_slog,
            "kuhanje" to R.string.os_kanali_kuhanje,
            "potovanja" to R.string.os_kanali_potovanja,
            "znanost" to R.string.os_kanali_znanost,
            "narava" to R.string.os_kanali_narava,
            "poslovni" to R.string.os_kanali_poslovni,
            "avto" to R.string.os_kanali_avto,
            "klasika" to R.string.os_kanali_klasika,
            "verski" to R.string.os_kanali_verski,
            "vreme" to R.string.os_kanali_vreme,
            "nakupovanje" to R.string.os_kanali_nakupovanje,
            "parlament" to R.string.os_kanali_parlament,
            "javni" to R.string.os_kanali_javni,
            "interaktivno" to R.string.os_kanali_interaktivno,
            "sprostitev" to R.string.os_kanali_sprostitev,
            "ostalo" to R.string.os_kanali_ostalo,
        )
        /** Vse kartice razdelka Video iz zadnjega nalaganja (zdruzene cez vire, neomejene) - osnova mreze Filmi | Serije. */
        @Volatile private var VSE_VIDEO: List<Jamendo.Skladba>? = null
        /** Nastavitve pogleda so zacasne: zivijo samo v pomnilniku procesa, nikoli v SharedPreferences. */
        private val razvrstitve = HashMap<Int, Int>()
        private val zacasnoSkritiViri = HashMap<Int, MutableSet<String>>()
        private val samoTaNaprava = mutableSetOf<Int>()
        /**
         * Naslovnice v pomnilniku, omejene v BAJTIH (naloga 54). Prej 48 kosov ne glede na velikost: slicice naprave
         * (loadThumbnail 320x320 ARGB, ~400 KB) ali vecje naslovnice so lahko zasedle ~20 MB native heapa. 8 MB je
         * ~40 plakatov 280 px - dovolj za zaslon in vrnitev nanj; ostalo pride z diska (MedijskiPredpomnilnik).
         */
        // Naloga 77: plakati brez prosojnosti so RGB_565 (MedijskiPredpomnilnik.zaPomnilnik), ~104 KB namesto ~208 KB; 18 MB
        // drzi ~170 plakatov (vsa mreza 166 Filmov, vrnitev na vrh brez ponovnega nalaganja - lastnik 22. 10.) namesto ~115 pri 24 MB.
        private const val SLIKE_BAJTOV = 18 * 1024 * 1024
        private const val NASLOVNIC_ZA_DNEVNIK = 30L
        private val SLIKE = object : LruCache<String, android.graphics.Bitmap>(SLIKE_BAJTOV) {
            override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.allocationByteCount
        }

        /** Sprememba virov mora ob naslednjem odprtju znova sestaviti spletne police. */
        fun pocistiSpletniPredpomnilnik() {
            SEZNAMI.remove(DOMOV)
            SEZNAMI.remove(GLASBA)
            SEZNAMI.remove(VIDEO)
        }

        /** Slike so ponovljivo nalozljive; ob pomnilniskem pritisku jih ne drzimo v heapu. */
        fun sprostiSlike() {
            SLIKE.evictAll()
        }

        /** Naloga 92: predpomnilnik na USB v uporabi - manj naslovnic v pomnilniku ([PredpomnilnikPravila.mejaSlik]); ostale iz baze. */
        fun prilagodiPomnilnik(usb: Boolean) = SLIKE.resize(PredpomnilnikPravila.mejaSlik(usb, SLIKE_BAJTOV))

        /**
         * Seznami in mreze za takojsnje ponovno odprtje (SEZNAMI, BRSKANJE, VSEBINA_MREZE, KANALI_MREZE, VSE_VIDEO) rastejo
         * ves cas procesa: vsaka zvrst, jezik in razvrstitev doda svoj seznam (naloga 54). Ko je vmesnik skrit ali sistemu
         * zmanjkuje pomnilnika, jih spustimo - ob naslednjem odprtju pridejo s predpomnilnika na disku in iz omrezja.
         * Zaslon, ki je odprt, ima svoje kopije (prikazanePolice, odprtKatalog). KANALI_RED ostane: vrstni red kanalov,
         * ki ga je uporabnik videl, se ne sme spremeniti.
         */
        fun sprostiPodatke() {
            SEZNAMI.clear()
            BRSKANJE.clear()
            VSEBINA_MREZE.clear()
            KANALI_MREZE.clear()
            VSE_VIDEO = null
        }
    }
}

/** Naloga 90b: kdaj (elapsedRealtime) smo dodatek (osnova) nazadnje poskusili odkleniti; velja za ves proces. */
private val ZADNJI_POSKUS_ODKLEPA = java.util.concurrent.ConcurrentHashMap<String, Long>()
private const val POSKUS_ODKLEPA_MS = 10 * 60_000L
