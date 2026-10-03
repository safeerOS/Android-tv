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

class GlasbaActivity : OsActivity() {

    /** Vrsta kartic; [pogled] je posebna vrsta (npr. tvoji viri), ki se narise tako, kot je. */
    private data class Vrsta(val naslov: String, val kartice: List<Kartica>, val video: Boolean = false, val pogled: View? = null, val mala: Boolean = false,
                             val mreza: Boolean = false,
                             /** Mreza cistih plakatov (Filmi | Serije): samo slika, brez okvirja, znack in napisov. */
                             val cisto: Boolean = false)
    /** Podatki vrste brez zaslona - samo to gre v predpomnilnik (kartice drzijo zaslon). */
    private data class Podatki(val naslov: String, val skladbe: List<Jamendo.Skladba>, val video: Boolean = false)
    private data class Kartica(val naslov: String, val podnaslov: String, val slika: String, val klik: (View) -> Unit,
                               val dolgo: ((View) -> Unit)? = null, val ikona: Int = R.drawable.os_ikona_glasba,
                               val oznaka: String = "", val kakovost: String = "", val ocena: String = "",
                               val tvId: String = "",
                               /** Ko izbira z daljincem obstane na kartici: pripravi, kar bo klik rabil (tokovi filma), da se zacne takoj. */
                               val priprava: (() -> Unit)? = null)

    private val delavec = Executors.newFixedThreadPool(4)
    // Omrezno iskanje ima lasten omejen pool. Prejsnje iskanje preklicemo, da pocasni
    // strezniki ne zasedajo CPU/omrezja se dolgo po novem vnosu.
    // Brez zgornje meje: omrezna zahteva se ob preklicu ne ustavi takoj, novo iskanje pa ne sme cakati v vrsti.
    private val iskanjeDelavec = Executors.newCachedThreadPool()
    /**
     * Slike (plakati, naslovnice) imajo svoje niti: prej so si jih delile z nalaganjem razdelkov in ob pocasnem
     * strezniku slik je razdelek (npr. TV v zivo) cakal nanje - prazen zaslon "Nalagam" (Matej, 2. 10. 2026: brez zastojev).
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
    private val poslusalec: () -> Unit = { glavna.post { osveziZdaj() } }
    private val tik = object : Runnable { override fun run() { osveziZdaj(); glavna.postDelayed(this, 1_000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        odprtih++
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(ZAVIHEK)?.let { z ->
            intent.removeExtra(ZAVIHEK)
            runCatching { StranskaVrstica.Zavihek.valueOf(z) }.getOrNull()?.let { izberi(razdelekZavihka(it)) }
        }
        iskanjeIzNamena()
        predvajajIzNamena()
        sprejmiIzNamena()
    }

    private fun sprejmiIzNamena() {
        val n = intent ?: return
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
        !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) }

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
        meni.setOnMenuItemClickListener { m ->
            when (m.itemId) {
                1 -> izberiRazvrstitevInVire(razdelek)
                2 -> odpriPredvajalnik()
                3 -> startActivity(Intent(this, NapraveActivity::class.java))
                4 -> startActivity(Intent(this, DatotekeActivity::class.java))
                5 -> startActivity(Intent(this, NastavitveActivity::class.java))
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

    /** Iskanje, odprto od drugod (zaslon predvajanja, tipka Isci): razdelek Iskanje, polje pripravljeno. */
    private fun iskanjeIzNamena() {
        val beseda = intent.getStringExtra(ISKANJE_BESEDA) ?: return
        intent.removeExtra(ISKANJE_BESEDA)
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

    override fun onStart() {
        super.onStart()
        // Samostojni Predvajalnik: to je njegov domaci zaslon - nova razlicica s safeer.si se ponudi tu (tiha pasica).
        if (si.safeer.tv.BuildConfig.FLAVOR == "predvajalnik") Posodobitve.ponudiCeJeCas(this)
        Ozadje.uporabi(this, koren)
        if (!link.jeKrajevni()) link.dodaj(linkPoslusalec)
        torrentSeJeSpremenil()
        GlasbaStoritev.poslusalci.add(poslusalec)
        glavna.post(tik)
        uskladiSezname()
        // Ob vrnitvi (npr. iz predvajanja) sta se nedavno in stanje predvajanja lahko spremenila.
        if (videnPrej && razdelek == DOMOV) izberi(DOMOV)
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
        if (videnPrej && vlc && razdelek == VIDEO && odprtKatalog == null) izberi(VIDEO)
        else if (videnPrej && razdelek == GLASBA) naloziKrajevno(razdelek)
        // Dovoljenje, dano v nastavitvah aplikacije: ob vrnitvi takoj pokazemo, kar je na napravi.
        if (vlc && videnPrej && imeloDovoljenje != stanjeDovoljenj()) {
            imeloDovoljenje = stanjeDovoljenj()
            SEZNAMI.remove(VIDEO); SEZNAMI.remove(GLASBA); krajevno.clear()
            if ((razdelek == VIDEO || razdelek == GLASBA) && odprtKatalog == null) izberi(razdelek)
        }
        videnPrej = true
    }

    override fun onStop() {
        link.odstrani(linkPoslusalec)
        GlasbaStoritev.poslusalci.remove(poslusalec)
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
        glavna.removeCallbacks(umiri)
        tvIkone.clear()
        super.onDestroy()
    }

    /** Nazaj iz razdelka vrne na nadzorno plosco; s plosce zapusti Safeer Media. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Mreza Filmi | Serije je domaci pogled razdelka Video: Nazaj iz nje gre na plosco, ne v isti razdelek.
        val domacaMreza = odprtKatalog?.tip?.isNotBlank() == true && videoNacin().isNotEmpty()
        if (odprtKatalog != null && !domacaMreza) { odprtKatalog = null; izberi(odprtKatalogIz); return }
        if (razdelek != DOMOV) {
            val kljuc = when (razdelek) { GLASBA -> KLJUC_GLASBA; VIDEO -> KLJUC_VIDEO; RADIO -> KLJUC_RADIO; VIRI -> KLJUC_VIRI; else -> KLJUC_GLASBA }
            izberi(DOMOV)
            drsnik.post { vsebina.findViewWithTag<View>(kljuc)?.requestFocus() }
            return
        }
        super.onBackPressed()
    }

    // ------------------------------------------------------------------ postavitev

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** TV profil: na 16:9 televizorju uporabimo prostor bolj vodoravno in vecji Now Playing. */
    private fun jeTv() = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
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

    /** Zasuk brez novega zaslona (configChanges): meni in trenutni razdelek narisemo za novo sirino. */
    override fun onConfigurationChanged(novo: android.content.res.Configuration) {
        super.onConfigurationChanged(novo)
        prilagodiMeni()
        if (razdelek != ISKANJE) izberi(razdelek)
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
        drsnik.setOnScrollChangeListener { _, _, y, _, _ ->
            val o = odprtKatalog
            if (o != null && !o.nalagam && vsebina.height - (y + drsnik.height) < dp(700)) vecMreze(o, izDrsenja = true)
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
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) {
            zdajCas.visibility = View.GONE
            zdajGumb = gumb(R.drawable.os_ikona_pavza, 44, "k:mala-pavza") {
                GlasbaStoritev.predvajalnik?.let { if (it.isPlaying) it.pause() else it.play() }; osveziZdaj()
            }.also { g -> g.contentDescription = getString(R.string.os_mediji_predvajaj_pavza); vrstica.addView(g) }
        }
        desno.addView(vrstica, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        // Tipke daljinca samo na televizorju; tablica se upravlja z dotikom.
        if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK))
            desno.addView(pomoc(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        stranskaVrstica = StranskaVrstica.ovij(this, desno, StranskaVrstica.Razdelek.MEDIJI)
        stranskaVrstica.naZavihek = { z -> izberi(razdelekZavihka(z)); true }
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
     * kot uporabniki poznajo iz drugih predvajalnikov (Matej, 2. 10. 2026). Naslov je pod sliko in je viden
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
        return Plakat(this).apply {
            tag = kljuc
            contentDescription = k.naslov
            isFocusable = true; isClickable = true
            background = GradientDrawable().apply { cornerRadius = polmer; setColor(osBarva(R.color.os_kartica)) }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, polmer) }
            }
            foreground = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = polmer; setColor(0); setStroke(dp(3), osBarva(R.color.os_mint)) })
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { cornerRadius = polmer; setColor(0x33FFFFFF) })
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0))
            }
            setOnClickListener { zadnjaKartica = java.lang.ref.WeakReference(it); k.klik(it) }
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
            val brezSlike = k.slika.isBlank()
            addView(besedilo(13f, osBarva(R.color.os_besedilo), true).apply {
                text = k.naslov; gravity = if (brezSlike) Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM else Gravity.CENTER
                maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(8), dp(8), dp(8), dp(if (brezSlike) 16 else 8))
            }, FrameLayout.LayoutParams(-1, -1))
            if (brezSlike) {
                addView(ImageView(this@GlasbaActivity).apply { setImageResource(k.ikona); scaleType = ImageView.ScaleType.FIT_CENTER },
                    FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
            } else {
                val slika = ImageView(this@GlasbaActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                addView(slika, FrameLayout.LayoutParams(-1, -1))
                naloziSliko(k.slika, slika)
            }
        }
    }

    private fun kartica(k: Kartica, video: Boolean, prva: Boolean, kljuc: String, mala: Boolean = false, velikostDp: Int = 0): View {
        // Video katalog na televizorju uporablja pokoncne plakate: vec naslovov je
        // hkrati vidnih, slika pa ni odrezana v sirok 16:9 trak. Majhne kartice "Nedavno" ostanejo 16:9.
        val plakat = video && !mala
        val sirina = dp(if (velikostDp > 0) velikostDp else if (mala) 96 else if (plakat) 112 else 150)
        val visina = if (mala && video) sirina * 9 / 16 else if (plakat) sirina * 3 / 2 else sirina
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = kljuc
            if (mala) setPadding(dp(3), dp(3), dp(3), dp(3)) else setPadding(dp(6), dp(6), dp(6), dp(8))
            setBackgroundResource(R.drawable.os_ploscica_app)
            isFocusable = true; isClickable = true
            setOnClickListener { zadnjaKartica = java.lang.ref.WeakReference(it); k.klik(it) }
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
            val slika = ImageView(this@GlasbaActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(osBarva(R.color.os_kartica))
                setImageResource(k.ikona)
                // Kartica brez slike: ikona zmerne velikosti na sredini, ne cez vso kartico.
                if (k.slika.isBlank()) { scaleType = ImageView.ScaleType.FIT_CENTER; val r = minOf(sirina, visina) / 4; setPadding(r, r, r, r) }
            }
            if (k.tvId.isNotBlank()) naloziTvIkono(k.tvId, slika)
            if (plakat) {
                addView(FrameLayout(this@GlasbaActivity).apply {
                    addView(slika, FrameLayout.LayoutParams(-1, -1))
                    fun znacka(text: String, barva: Int, spodaj: Int) {
                        if (text.isBlank()) return
                        addView(besedilo(10f, barva, true).apply {
                            this.text = text.uppercase(Locale.getDefault())
                            setPadding(dp(8), dp(4), dp(8), dp(4))
                            background = GradientDrawable().apply {
                                cornerRadius = dp(7).toFloat(); setColor(0xD9181D26.toInt()); setStroke(dp(1), barva)
                            }
                        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = dp(8); topMargin = dp(spodaj) })
                    }
                    znacka(k.oznaka, if (k.oznaka == getString(R.string.os_media_serija)) 0xFFA878FF.toInt() else osBarva(R.color.os_mint), 9)
                    znacka(k.kakovost, 0xFF1CE6F2.toInt(), if (k.oznaka.isBlank()) 9 else 42)
                }, LinearLayout.LayoutParams(sirina, visina))
            } else addView(slika, LinearLayout.LayoutParams(sirina, visina))
            addView(besedilo(if (mala) 11f else 14f, osBarva(R.color.os_besedilo), true).apply {
                text = k.naslov; maxLines = if (plakat) 2 else 1; setPadding(dp(2), dp(if (mala) 2 else 8), 0, 0)
            },
                LinearLayout.LayoutParams(sirina, -2))
            if (!mala) addView(besedilo(12f, osBarva(R.color.os_umirjeno)).apply {
                text = listOf(k.podnaslov, k.ocena.takeIf { it.isNotBlank() }?.let { "★ $it" }.orEmpty()).filter { it.isNotBlank() }.joinToString("   ")
                setPadding(dp(2), dp(2), 0, 0)
            },
                LinearLayout.LayoutParams(sirina, -2))
            naloziSliko(k.slika, slika)
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
    /** Kljuc izbire, ki jo mora vrniti risanje v teku (glej [narisi]); pritisk tipke ga razveljavi - uporabnik izbira sam. */
    private var kljucVRisanju: String? = null
    private fun narisi(vrste: List<Vrsta>, opis: String, prazno: String = getString(R.string.os_glasba_prazno), glava: List<View> = emptyList()) {
        val moje = ++risanje
        // Risanje, ki ga prekine naslednje (mreza se dopolni, ko pridejo katalogi), izbire se ni vrnilo: kljuc gre naprej,
        // sicer izbira na televizorju ostane v meniju.
        val kljuc = (if (vsebina.hasFocus()) kljucFokusa() else null) ?: kljucVRisanju
        kljucVRisanju = kljuc
        // Fokus iz vsebine, ki jo bomo zamenjali, v meni - sicer skoci na prvi element zaslona.
        if (vsebina.hasFocus()) meniMediji.requestFocus()
        vsebina.removeAllViews()
        stanje.text = if (glava.isEmpty() && vrste.all { it.kartice.isEmpty() && it.pogled == null }) prazno else opis
        iskalnik?.let { vsebina.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
        glava.forEach { vsebina.addView(it) }
        val koraki = ArrayDeque<() -> Unit>()
        for (v in vrste) {
            if (v.kartice.isEmpty() && v.pogled == null) continue
            val tesno = v.mala || razdelek == DOMOV
            koraki.addLast {
                if (v.naslov.isNotBlank())
                    vsebina.addView(besedilo(if (tesno) 16f else 18f, osBarva(R.color.os_besedilo), true).apply {
                        text = v.naslov; tag = "polica:${v.naslov}"; contentDescription = NASLOV_VRSTE
                        setPadding(dp(4), dp(if (tesno) 5 else 14), 0, dp(if (tesno) 3 else 8)) })
            }
            if (v.pogled != null) { koraki.addLast { vsebina.addView(v.pogled) }; continue }
            if (v.mreza && v.cisto) {
                // Plakati cez vso sirino: na telefonu pokonci trije v vrsto, na sirsih zaslonih toliko, kolikor jih gre.
                val reza = dp(10)
                val sirinaVsebine = (vsebina.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels * 3 / 4)) - vsebina.paddingLeft - vsebina.paddingRight
                // Televizor: manjsi plakati (osem v vrsto, dve vrsti na zaslonu), kot v drugih predvajalnikih za TV.
                val n = ((sirinaVsebine + reza) / (dp(if (jeTv()) 80 else 128) + reza)).coerceAtLeast(3)
                v.kartice.chunked(n).forEachIndexed { r, del ->
                    koraki.addLast {
                        vsebina.addView(LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL; tag = MREZA_VRSTA
                            // Enake utezi: plakati zapolnijo vrstico in sledijo sirini vsebine; nepolna zadnja vrstica dobi prazna mesta.
                            del.forEachIndexed { i, k ->
                                addView(cistPlakat(k, i == 0, "k:${v.naslov}#${r * n + i}"), LinearLayout.LayoutParams(0, -2, 1f).apply { if (i < n - 1) marginEnd = reza; bottomMargin = reza })
                            }
                            for (i in del.size until n) addView(View(this@GlasbaActivity), LinearLayout.LayoutParams(0, 1, 1f).apply { if (i < n - 1) marginEnd = reza })
                        }, LinearLayout.LayoutParams(-1, -2))
                    }
                }
                continue
            }
            if (v.mreza) {
                // Mreza (Moji viri, katalog): toliko kartic v vrsto, kolikor jih gre celih, ostale v naslednjo vrsto.
                // Na ozkem telefonu vsaj dve kartici v vrsto, zato ozje (plakati najmanj 72 dp, druge kartice 88 dp):
                // ena kartica na vrsto je bil seznam z veliko praznega prostora (Matej, Moji viri na telefonu pokonci).
                val sirinaVsebine = vsebina.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels * 3 / 4)
                var velikost = if (v.video) 112 else MREZA_DP
                var n = (sirinaVsebine / dp(velikost + 12 + 14)).coerceAtLeast(1)
                // Plakati: na telefonu pokonci trije v vrsto (kot uporabniki poznajo iz drugih predvajalnikov), sicer vsaj dva.
                val najmanj = if (v.video && sirinaVsebine / dp(72 + 12 + 14) >= 3) 3 else 2
                if (n < najmanj && sirinaVsebine / dp((if (v.video) 72 else 88) + 12 + 14) >= najmanj) {
                    n = najmanj; velikost = (sirinaVsebine / najmanj / resources.displayMetrics.density).toInt() - 26
                }
                v.kartice.chunked(n).forEachIndexed { r, del ->
                    koraki.addLast {
                        vsebina.addView(LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL; tag = MREZA_VRSTA
                            del.forEachIndexed { i, k ->
                                addView(kartica(k, v.video, i == 0, "k:${v.naslov}#${r * n + i}", velikostDp = velikost), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14); bottomMargin = dp(14) })
                            }
                        })
                    }
                }
                continue
            }
            val niz = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            // Prvih nekaj kartic skupaj s polico (toliko, kot jih je vidnih), ostale po kosih.
            v.kartice.chunked(KARTIC_NA_KORAK).forEachIndexed { c, del ->
                koraki.addLast {
                    if (c == 0) vsebina.addView(HorizontalScrollView(this).apply {
                        addView(niz); isHorizontalScrollBarEnabled = false; clipToPadding = false; tag = POLICA_KARTIC
                    })
                    del.forEachIndexed { j, k ->
                        val i = c * KARTIC_NA_KORAK + j
                        niz.addView(kartica(k, v.video, i == 0, "k:${v.naslov}#$i", v.mala), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(if (v.mala) 10 else 14) })
                    }
                }
            }
        }
        if (kljuc == null) {
            // Po dejanju na mestu (izbris vira ali seznama, priljubljena, usklajeni seznami) ostanemo tam, kjer smo bili.
            val y = if (razdelek == naMestuRazdelek && android.os.SystemClock.uptimeMillis() < naMestuDo) naMestuY else 0
            if (y <= 0) drsnik.scrollTo(0, 0) else drsnik.post(object : Runnable {
                var poskusi = 0
                override fun run() {
                    if (moje != risanje) return
                    if (vsebina.height >= y + drsnik.height || poskusi++ > 30) drsnik.scrollTo(0, y) else drsnik.postDelayed(this, 50)
                }
            })
        }
        fun koncano() {
            brezOdrezanihVrst()
            drsnik.post {
                if (moje != risanje) return@post
                kljucVRisanju = null
                // "Nadaljuj" se po zacetku predvajanja zamenja s tipkami - izbira gre na predvajaj/pavza.
                val nazaj = kljuc?.let { vsebina.findViewWithTag<View>(it) ?: (if (it == "k:nadaljuj") vsebina.findViewWithTag<View>("k:predvajaj") else null) ?: sosedKljuca(it) }
                when {
                    // Odprt razdelek: izbira gre na prvo kartico; ce kartic se ni (mreza se nalaga), poskusimo ob naslednjem risanju.
                    fokusVVsebino -> { if (fokusNaPrvo()) fokusVVsebino = false }
                    nazaj != null -> nazaj.requestFocus()
                    // Po zaprtem oknu (Dodaj vir, Odstrani) fokus ne sme ostati nikjer.
                    window.decorView.findFocus() == null -> meniMediji.requestFocus()
                }
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

    /**
     * Na prvem zaslonu ni odrezane vrste: prva vrsta, ki ne gre cela nad spodnji rob, se skupaj
     * s svojim naslovom odmakne pod rob (pokaze se ob drsenju). Merimo po postavitvi, ne ugibamo.
     */
    private fun brezOdrezanihVrst() {
        // Samo televizor (daljinec): na dotik se drsi s prstom in odmik bi pustil prazno luknjo sredi zaslona.
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) return
        vsebina.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                vsebina.viewTreeObserver.removeOnGlobalLayoutListener(this)
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
                    // Mreza plakatov, kjer gre na zaslon ena sama vrsta (odprta je mala vrstica predvajanja): druge
                    // vrste ne odmaknemo - sicer bi bila pod prvo vrsto praznina cez pol zaslona.
                    if (v.tag == MREZA_VRSTA && vidneVrsteMreze < 2) return
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
    }

    // ------------------------------------------------------------------ slike

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
                // Naslovnica z diska (brez omrezja); nove shranimo za naslednje odprtje.
                val bajti = MedijskiPredpomnilnik.naslovnica(this, naslov)
                    ?: (SpletniVir.bajtiSlike(this, naslov) ?: Jamendo.bajti(naslov))?.also {
                        MedijskiPredpomnilnik.shraniNaslovnico(this, naslov, it)
                    }
                val b = bajti?.let { VarnaSlika.izBajtov(it, 280) } ?: return@execute
                SLIKE.put(naslov, b)
                val cakajoci = cakajoceSlike.remove(naslov)
                val pogledi = cakajoci?.let { synchronized(it) { it.toList() } }.orEmpty()
                glavna.post {
                    if (!isFinishing) pogledi.forEach { ref ->
                        ref.get()?.takeIf { it.tag == naslov }?.setImageBitmap(b)
                    }
                }
            } finally {
                slikeVTeKu.remove(naslov)
                cakajoceSlike.remove(naslov)
            }
        }
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

    private fun izberi(i: Int) {
        // Iskanje v zasebnem dodatku velja, dokler je odprto iskanje; iskane besede si ne zapomnimo.
        if (i != ISKANJE && zasebnoIskanje != null) { zasebnoIskanje = null; zadnjaBeseda = "" }
        razdelek = i
        odprtSeznam = ""
        odprtKatalog = null
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
        // Video se odpre naravnost v mrezo plakatov (Filmi ali Serije, zadnja izbira) - brez polic vmes (Matej, 2. 10. 2026).
        if (i == VIDEO && i !in samoTaNaprava) videoNacin().takeIf { it.isNotEmpty() }?.let { ++nalaganje; odpriBrskanje(it, videoZvrst); return }
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
        // TV v zivo: vgrajeni kanali so v aplikaciji (brez omrezja) - na zaslonu so takoj; kanali iz dodatkov se dodajo, ko pridejo.
        if (i == TV_V_ZIVO && i !in samoTaNaprava && jeVirViden(i, VIR_TV)) {
            val vgrajeni = try { TvVZivo.poDrzavah().map { (drzava, kanali) -> Podatki(drzava, kanali, video = true) } } catch (_: Exception) { emptyList() }
            if (vgrajeni.isNotEmpty()) prikazi(i, vgrajeni)
        }
        delavec.execute {
            val zDiska = try { MedijskiPredpomnilnik.beriPolice(this, kljucDiska)?.map { (n, v, s) -> Podatki(n, s, v) } } catch (_: Exception) { null }
            if (zDiska != null) glavna.post { if (moje == nalaganje && !isFinishing) prikazi(i, zDiska) }
            val podatki = try {
                podatkiRazdelka(i).map { it.copy(skladbe = razvrsti(i, it.skladbe)) }
            } catch (_: Exception) { null }
            val polni = podatki != null && podatki.isNotEmpty() && podatki.all { it.skladbe.isNotEmpty() }
            if (polni) MedijskiPredpomnilnik.shraniPolice(this, kljucDiska, podatki!!.map { Triple(it.naslov, it.video, it.skladbe) })
            glavna.post {
                if (moje != nalaganje || isFinishing) return@post
                if (podatki == null) {
                    // Brez omrezja: ce je pogled z diska, ostane; sicer povemo, da nalaganje ni uspelo.
                    if (zDiska == null) stanje.text = getString(R.string.os_glasba_napaka)
                    return@post
                }
                // Prazen odgovor (Jamendo obcasno) ne ostane v predpomnilniku - ob naslednji izbiri poskusimo znova.
                if (polni) SEZNAMI[i] = podatki
                if (zDiska != null && (!polni || zDiska == podatki)) return@post  // nic novega: pogleda ne risemo znova
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
        return "police:$i:${resources.configuration.locales[0].toLanguageTag()}:r${razvrstitev(i)}:l${i in samoTaNaprava}:f$skriti:j$jeziki$peertube"
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
    private fun jeVirViden(i: Int, kljuc: String) = kljuc !in zacasnoSkritiViri[i].orEmpty()
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
        if (odprtKatalog != null && i == razdelek) return
        val c0 = android.os.SystemClock.uptimeMillis()
        val filtrirani = podatki.map { it.copy(skladbe = filtrirajJezike(i, it.skladbe)) }
            .filter { it.skladbe.isNotEmpty() }
        if (i == TV_V_ZIVO) tvIkone.clear()
        val c1 = android.os.SystemClock.uptimeMillis()
        val zg = zgoraj(i); val c2 = android.os.SystemClock.uptimeMillis()
        val vr = vVrste(filtrirani); val c3 = android.os.SystemClock.uptimeMillis()
        val gl = glavaRazdelka(i, podatki); val c4 = android.os.SystemClock.uptimeMillis()
        narisi(zg + vr, opis(i), glava = gl)
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
        if (i == TV_V_ZIVO) TvVZivo.osveziIkone(this) { osveziTvIkone() }
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
            val vrste = mutableListOf<Podatki>()
            val vsebinaVirov = SpletniVir.priljubljeno(this, MedijskiViri.vsi(this).filter { jeVirViden(i, kljucVira(it)) })
            val videospoti = vsebinaVirov.filter { SpletniVir.vrstaVsebine(it) == SpletniVir.VIDEOSPOT }
            val avdioViri = vsebinaVirov.filterNot { it.video || SpletniVir.vrstaVsebine(it) == SpletniVir.VIDEOSPOT }

            // Glasbeni dodatki uporabnika (albumi, skladbe, seznami, podkasti): njegova izbira je pred vgrajenimi viri.
            vrste += policeDodatkov(i, Stremio.GLASBA)
            avdioViri.takeIf { it.isNotEmpty() }?.let {
                vrste += Podatki(getString(R.string.os_media_prilj_v_virih), it)
            }
            videospoti.takeIf { it.isNotEmpty() }?.let {
                vrste += Podatki(getString(R.string.os_media_videospoti), it, video = true)
            }
            if (jeVirViden(i, VIR_JAMENDO)) {
                unikatne(Jamendo.priljubljene(24), 18).takeIf { it.isNotEmpty() }?.let {
                    vrste += Podatki(getString(R.string.os_media_popularno), it)
                }
                // Po glasbenih zvrsteh, kot filmi po zanrih (police nalozimo vzporedno).
                val poZvrsteh = ZVRSTI.filter { it.first.isNotEmpty() }.map { z ->
                    java.util.concurrent.CompletableFuture.supplyAsync { z.third to Jamendo.poZvrsti(z.first, 18) }
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
            val vrste = mutableListOf<Podatki>()
            // Radijske postaje iz uporabnikovih dodatkov so pred vgrajenimi.
            vrste += policeDodatkov(i, Stremio.RADIO)
            if (!radioViden && !tuneInViden) return vrste
            val (domace, svet) = if (radioViden) Radio.postajeLocene() else emptyList<Jamendo.Skladba>() to emptyList()
            if (domace.isNotEmpty()) vrste += Podatki(getString(R.string.os_mediji_domace), domace)
            if (tuneInViden) TuneIn.lokalne().takeIf { it.isNotEmpty() }?.let {
                vrste += Podatki(getString(R.string.os_media_tunein_lokalne), it)
            }
            if (radioViden) {
                val poZvrsteh = ZVRSTI.map { z ->
                    java.util.concurrent.CompletableFuture.supplyAsync { z.third to Radio.poZvrsti(z.second, 24) }
                }.map { it.get() }
                poZvrsteh.forEach { (naziv, postaje) -> if (postaje.isNotEmpty()) vrste += Podatki(getString(naziv), postaje) }
                vrste += Podatki(getString(R.string.os_mediji_svet), svet)
            }
            vrste.filter { it.skladbe.isNotEmpty() }
        }
        TV_V_ZIVO -> {
            // Kanali v zivo iz uporabnikovih dodatkov Stremio (katalogi tipa "tv"): vsi katalogi skupaj v eni polici (uporabnik
            // vira ne rabi poznati), isti kanal iz vec dodatkov enkrat; "Pokazi vse" odpre vse kanale. Pred uradnimi prenosi.
            val stremio = MedijskiViri.vsi(this).filter { it.jeStremio && jeVirViden(i, kljucVira(it)) }.map { it.naslov }
            val izDodatkov = mutableListOf<Podatki>()
            if (stremio.isNotEmpty()) {
                val katalogi = try { Stremio.katalogiTv(stremio) } catch (_: Exception) { emptyList() }.take(12)
                val vsebine = katalogi.map { k -> iskanjeDelavec.submit<List<Jamendo.Skladba>> { try { Stremio.katalog(k) } catch (_: Exception) { emptyList() } } }
                    .map { f -> try { f.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { emptyList() } }
                // Isti kanal iz vec dodatkov je ena kartica; razlicice ostanejo zraven, da ob dotiku izberemo najboljsi tok izmed vseh.
                val kanali = SpletniVir.zdruziEnako(prepleti(vsebine)).take(80).flatten()
                if (kanali.isNotEmpty()) izDodatkov += Podatki("📡 " + getString(R.string.os_media_kanali_virov), kanali, video = true)
            }
            izDodatkov + (if (jeVirViden(i, VIR_TV)) TvVZivo.poDrzavah().map { (drzava, kanali) -> Podatki(drzava, kanali, video = true) } else emptyList())
        }
        VIDEO -> {
            val vsiViri = MedijskiViri.vsi(this)
            val medijskiViri = vsiViri.filter { jeVirViden(i, kljucVira(it)) }
            android.util.Log.i("SafeerOsMedia", "viri=${medijskiViri.size}, spletni=${medijskiViri.count { it.jeSplet }}")
            // Uporabnik doda vir in nanj pozabi (Matej, 2. 10. 2026): police so po VSEBINI (Zate, Filmi, Serije, Video,
            // zvrsti), ne po virih. Vse vire vprasamo hkrati, vsebino zdruzimo in isti film iz vec virov je ena kartica.
            val stremio = vsiViri.filter { it.jeStremio && jeVirViden(i, kljucVira(it)) }.map { it.naslov }
            val katalogi = if (stremio.isEmpty()) emptyList() else try { Stremio.prikazniKatalogi(Stremio.zKatalogom(stremio)) } catch (_: Exception) { emptyList() }.take(12)
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
            VSE_VIDEO = vse
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
            // ista kot Filmi. Kar Zate pokaze, se v policah pod njo ne ponovi (Matej, 2. 10. 2026).
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
        zVsemi.takeIf { it.isNotEmpty() }?.let { Vrsta(d.naslov, it, d.video) }
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
                               val dodatek: String = "") {
        /** Polica »Na tvojih napravah« nad mrezo zasebnega dodatka: njegovi prenosi na napravah v Linku (samo tu). */
        var prenosi: List<Jamendo.Skladba> = emptyList()
        /** Koliko naslovov te mreze se preverjamo pri dodatkih. */
        val caka = java.util.concurrent.atomic.AtomicInteger()
        /** Naslovi, za katere dodatki niso odgovorili: v tej mrezi jih ne sprasujemo znova. */
        val neznani: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        /** Kartice, ki so zdaj na zaslonu (id-ji po vrsti): mrezo risemo znova samo, ce bi bila drugacna. */
        var narisani: List<String> = emptyList()
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
        /** Uporabnik hoce vec: okno se podaljsa za [korak] kartic, proracun poizvedb je nov. */
        fun vec(korak: Int) { zelja += korak; proracun = PRORACUN_PREVERJANJ; preverjenih.set(0); zadetkov.set(0); samodejno = 0 }
    }

    /** Stanje okna stroge mreze: v oknu je se kaj nepreverjenega; nekaj naslovov caka, ker dodatek omejuje poizvedbe; za oknom so se kandidati. */
    private class StanjeMreze(val vOknu: Boolean, val premor: Boolean, val naprej: Boolean)
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
        }, ikona = R.drawable.os_ikona_mreza)

    /**
     * Kataloge najdemo po naslovu police (police so tudi s predpomnilnika na disku, kjer kataloga ni). Prve strani vseh
     * katalogov police prenesemo znova, da vemo, koliko je dal vsak (od tam naprej gre "Nalozi vec" po katalogih).
     */
    private fun odpriKatalog(naslov: String, prvaStran: List<Jamendo.Skladba>) {
        stanje.text = getString(R.string.os_glasba_nalagam)
        val iz = razdelek
        delavec.execute {
            val katalogi = try { katalogiPolice(naslov) } catch (_: Exception) { emptyList() }
            val strani = katalogi.map { k -> iskanjeDelavec.submit<List<Jamendo.Skladba>> { try { Stremio.katalog(k) } catch (_: Exception) { emptyList() } } }
                .map { f -> try { f.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { emptyList() } }
            val preneseno = HashMap<Stremio.Katalog, Int>().apply { katalogi.zip(strani).forEach { (k, v) -> put(k, v.size) } }
            val vsi = SpletniVir.zdruziEnako(prvaStran + prepleti(strani)).map { it.first() }.toMutableList()
            glavna.post {
                if (isFinishing) return@post
                if (katalogi.isEmpty()) { stanje.text = getString(R.string.os_glasba_napaka); return@post }
                odprtKatalog = OdprtKatalog(katalogi, vsi, preneseno, strani.any { it.size >= STRAN_KATALOGA_MIN }, naslov = naslov)
                odprtKatalogIz = iz
                narisiKatalog(naslov)
                fokusNaPrvo()
            }
        }
    }

    private fun narisiKatalog(naslov: String) {
        val o = odprtKatalog ?: return
        o.naslov = naslov
        val brskanje = o.tip.isNotBlank()
        // Cesar noben dodatek ne predvaja, ne kazemo; ostalo v ozadju preverimo (preveriMrezo). V strogem nacinu
        // (vidniKataloga) kartica pride na zaslon sele, ko vemo, da se da predvajati - nic se ne pokaze in spet skrije.
        val (kandidati, vsi) = vidniKataloga(o)
        val strogo = vsi.size != kandidati.size || strogaMreza(o)
        preveriMrezo(o, kandidati)
        o.narisani = vsi.map { it.id }
        // Ce nobenega naslova ne predvaja noben dodatek, "Nalozi vec" ne pomaga (naslednje strani bi izginile enako).
        // Vljudno preverjanje: kar je za oknom ali se ni preverjeno, caka na uporabnika (drsenje, Nalozi vec).
        val st = if (strogaMreza(o)) stanjeMreze(o, kandidati) else StanjeMreze(false, false, false)
        val seVec = o.seKaj || st.vOknu || st.naprej
        o.narisano = o.narisani to seVec
        val kartice = videi(vsi, naslov) + (if (seVec && (vsi.isNotEmpty() || (o.caka.get() == 0 && !o.nalagam))) listOf(Kartica(getString(R.string.os_media_nalozi_vec), "", "",
            { vecMreze(o) }, ikona = R.drawable.os_ikona_plus)) else emptyList())
        // Pod naslovom razdelka ni stevila kartic (Matej, 3. 10. 2026) - samo, kadar se kaj nalaga ali ni nicesar.
        val opis = opisMreze(o, st, vsi.isEmpty())
        if (strogo) narociUmiri()
        if (!brskanje) {
            // Zaseben dodatek: nad mrezo njegovi prenosi (predvajaj, obdrzi, odstrani) - na skupnih policah jih ni.
            val polica = o.prenosi.takeIf { it.isNotEmpty() }?.let { Vrsta(getString(R.string.os_prenosi_racunalnik), videi(it), video = true) }
            narisi(listOfNotNull(polica) + Vrsta(naslov, kartice, video = true, mreza = true), opis); return
        }
        // Filmi | Serije: zavihki, zvrsti in razvrstitev na vrhu, pod njimi mreza plakatov (brez naslova police).
        narisi(listOf(Vrsta("", kartice, video = true, mreza = true, cisto = true)), opis, glava = glavaBrskanja(o))
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
    /** Izbrana zvrst ostane, dokler je aplikacija odprta (vrnitev v Video pokaze isto mrezo). */
    private var videoZvrst = ""

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
            val katalogi = o.katalogi.filter { (o.preneseno[it] ?: 0) > 0 }
            val strani = katalogi.map { k -> iskanjeDelavec.submit<Pair<Int, List<Jamendo.Skladba>>> { try { straniKataloga(k, zvrst, o.preneseno[k] ?: 0) } catch (_: Exception) { 0 to emptyList() } } }
                .map { f -> try { f.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { 0 to emptyList<Jamendo.Skladba>() } }
            val nove = prepleti(strani.map { it.second })
            glavna.post {
                o.nalagam = false
                if (isFinishing || odprtKatalog !== o) return@post
                katalogi.zip(strani).forEach { (k, v) -> o.preneseno[k] = (o.preneseno[k] ?: 0) + v.first }
                val znani = vsi.map { it.id }.toSet()
                // Nov vnos, ki je ista vsebina kot ze prikazana kartica, se vanjo zdruzi (ni nove kartice).
                val sveze = SpletniVir.zdruziEnako(vsi + nove.filter { it.id !in znani }).filter { g -> g.none { it.id in znani } }.map { it.first() }
                vsi += sveze
                o.prazne = if (sveze.isEmpty()) o.prazne + 1 else 0
                o.seKaj = strani.any { it.first >= STRAN_KATALOGA_MIN } && (sveze.isNotEmpty() || (zvrst != null && o.prazne < 3))
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
    private fun straniKataloga(k: Stremio.Katalog, zvrst: Zvrsti.Zvrst?, skip: Int = 0): Pair<Int, List<Jamendo.Skladba>> {
        val moznost = zvrst?.let { Zvrsti.moznost(it, k.zvrsti) }.orEmpty()
        val stran = Stremio.katalog(k, skip = skip, zvrst = moznost)
        return stran.size to (if (zvrst == null || moznost.isNotEmpty()) stran else stran.filter { zvrst.ustreza(it) })
    }

    // ------------------------------------------------------------------ Filmi | Serije: mreza plakatov z zvrstmi

    /**
     * Filmi ali Serije kot mreza plakatov cez VSE vire (dodatki, spletni viri, javna last ...), z izbiro zvrsti in
     * razvrstitvijo - kot na racunalniku in kot uporabniki poznajo iz drugih predvajalnikov (Matej, 2. 10. 2026).
     * Uporabnik ne izbira vira: isti film iz vec virov je ena kartica.
     */
    private fun odpriBrskanje(tip: String, zvrstKljuc: String = "") {
        val naslov = getString(if (tip == "movie") R.string.os_media_filmi else R.string.os_media_serije)
        val o = OdprtKatalog(emptyList(), mutableListOf(), HashMap(), false, tip, zvrstKljuc, naslov, nalagam = true)
        odprtKatalog = o
        odprtKatalogIz = VIDEO
        // Nic cakanja: zadnja znana mreza (pomnilnik, sicer disk) je na zaslonu takoj; sveza vsebina jo zamenja le, ce je drugacna.
        val kljuc = "brskanje:$tip:$zvrstKljuc:${resources.configuration.locales[0].toLanguageTag()}"
        BRSKANJE[kljuc]?.let { o.vsi += it }
        drsnik.scrollTo(0, 0)
        narisiKatalog(naslov)
        val zvrst = Zvrsti.poKljucu(zvrstKljuc)
        val vrsta = if (tip == "movie") SpletniVir.FILM else SpletniVir.SERIJA
        videoZvrst = zvrstKljuc
        delavec.execute {
            if (o.vsi.isEmpty()) {
                val zDiska = try { MedijskiPredpomnilnik.beriPolice(this, kljuc)?.firstOrNull()?.third } catch (_: Exception) { null }
                if (!zDiska.isNullOrEmpty()) glavna.post {
                    if (!isFinishing && odprtKatalog === o && o.vsi.isEmpty()) { o.vsi += zDiska; narisiKatalog(naslov) }
                }
            }
            // 1) Katalogi dodatkov te vrste (hitro, vsi hkrati); ob izbrani zvrsti brez katalogov, ki zvrsti ne poznajo in zahtevajo kaj drugega (npr. leto).
            val dodatki = MedijskiViri.vsi(this).filter { it.jeStremio && jeVirViden(VIDEO, kljucVira(it)) }.map { it.naslov }
            val katalogi = (if (dodatki.isEmpty()) emptyList() else try { Stremio.prikazniKatalogi(Stremio.zKatalogom(dodatki)) } catch (_: Exception) { emptyList() })
                .filter { it.tip == tip && (zvrst == null || Zvrsti.moznost(zvrst, it.zvrsti) != null || "genre" !in it.obvezni) }.take(12)
            val strani = katalogi.map { k -> iskanjeDelavec.submit<Pair<Int, List<Jamendo.Skladba>>> { try { straniKataloga(k, zvrst) } catch (_: Exception) { 0 to emptyList() } } }
                .map { f -> try { f.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { 0 to emptyList<Jamendo.Skladba>() } }
            val izKatalogov = prepleti(strani.map { it.second })
            // 2) Kar razdelek Video pozna iz ostalih virov (splet, PeerTube, javna last ...). Ce to se ni nalozeno, mreza
            //    ne caka: katalogi so na zaslonu takoj, ostalo se doda, ko pride.
            fun osnova(police: List<Podatki>, vse: List<Jamendo.Skladba>?) = (police.flatMap { it.skladbe } + vse.orEmpty()).distinctBy { it.id }
                .filter { SpletniVir.vrstaVsebine(it) == vrsta && (zvrst == null || zvrst.ustreza(it)) }
            val znane = SEZNAMI[VIDEO] ?: prikazanePolice[VIDEO]
            // Police s predpomnilnika na disku (ali po spremembi virov) so okrnjene ali stare: vse kartice razdelka dobimo
            // v drugem koraku (podatkiRazdelka).
            val popolne = SEZNAMI[VIDEO] != null && VSE_VIDEO != null
            // Najprej katalogi (urejeni po priljubljenosti), nato ostalo; ista vsebina je ena kartica.
            fun zdruzi(dodatno: List<Jamendo.Skladba>) = SpletniVir.zdruziEnako(izKatalogov + dodatno).map { it.first() }
            fun shrani(vsi: List<Jamendo.Skladba>) {
                // Prazen odgovor (brez omrezja) ne povozi zadnje znane mreze.
                if (vsi.isEmpty()) return
                BRSKANJE[kljuc] = vsi
                try { MedijskiPredpomnilnik.shraniPolice(this, kljuc, listOf(Triple(naslov, true, vsi))) } catch (_: Exception) { }
            }
            val vsi = zdruzi(znane?.let { osnova(it, if (popolne) VSE_VIDEO else null) }.orEmpty())
            if (popolne) shrani(vsi)
            glavna.post {
                if (isFinishing || odprtKatalog !== o) return@post
                o.katalogi = katalogi
                katalogi.zip(strani).forEach { (k, v) -> o.preneseno[k] = v.first }
                val seKaj = strani.any { it.first >= STRAN_KATALOGA_MIN }
                // Dokler ne poznamo vseh kartic razdelka (drugi korak), ostane na zaslonu tudi, kar je mreza pokazala iz
                // predpomnilnika (filmi javne lasti ...): sicer bi izginilo in se cez nekaj sekund spet pojavilo.
                val nove = if (popolne) vsi else { val znani = vsi.map { it.id }.toSet(); vsi + o.vsi.filter { it.id !in znani } }
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
            if (police.isNotEmpty() && police.all { it.skladbe.isNotEmpty() }) SEZNAMI[VIDEO] = police
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

    /** Glava mreze Filmi | Serije: (v Medijskem centru se razdelki) in tri izbire - vrsta, razvrstitev, zvrst. */
    private fun glavaBrskanja(o: OdprtKatalog): List<View> =
        (if (vlc) emptyList() else listOf(razdelkiVrstica(VIDEO))) + listOf(videoIzbire(o))

    /**
     * Izbire razdelka Video v eni vrstici, kot Discover v drugih predvajalnikih (Matej, 2. 10. 2026):
     * [Filmi ▾] [Priporoceno ▾] [Vse zvrsti ▾]. Na telefonu enako siroke cez ves zaslon. Pri policah (Zate)
     * je samo prva, da se uporabnik vrne v mrezo.
     */
    private fun videoIzbire(o: OdprtKatalog?): View {
        val ozek = ozekZaslon()
        val niz = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(12)) }
        fun izbira(kljuc: String, moznosti: List<String>, izbrana: Int, naslovOkna: String?, obIzbiri: (Int) -> Unit) {
            val prva = niz.childCount == 0
            niz.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                tag = "k:$kljuc"; isFocusable = true; isClickable = true
                setPadding(dp(if (ozek) 10 else 12), dp(10), dp(if (ozek) 8 else 10), dp(10))
                fun oblika(rob: Int, debelina: Int, polnilo: Int) = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(polnilo); setStroke(dp(debelina), rob) }
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), oblika(osBarva(R.color.os_mint), 2, 0x3357D6AD))
                    addState(intArrayOf(android.R.attr.state_pressed), oblika(0x66FFFFFF, 1, 0x26FFFFFF))
                    addState(intArrayOf(), oblika(0x40FFFFFF, 1, 0x14FFFFFF))
                }
                addView(besedilo(15f, osBarva(R.color.os_besedilo), true).apply {
                    text = moznosti.getOrElse(izbrana) { moznosti.first() }; maxLines = 1; gravity = Gravity.CENTER_VERTICAL
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
            }, LinearLayout.LayoutParams(if (ozek && o != null) 0 else dp(if (ozek) 150 else 200), -2, if (ozek && o != null) 1f else 0f).apply { if (!prva) marginStart = dp(8) })
        }
        // 1) Kaj: Filmi | Serije (mreza) | Zate (police: nadaljuj, na napravi, prenosi, priporocila).
        val nacini = listOf("movie", "series", "")
        izbira("nacin", listOf(getString(R.string.os_media_filmi), getString(R.string.os_media_serije), getString(R.string.os_media_zate)),
            nacini.indexOf(o?.tip.orEmpty()), null) { i ->
            shraniVideoNacin(nacini[i])
            fokusVVsebino = false
            if (nacini[i].isEmpty()) { odprtKatalog = null; izberi(VIDEO) } else odpriBrskanje(nacini[i], o?.zvrst ?: videoZvrst)
        }
        if (o != null) {
            // 2) Razvrstitev.
            val razvrstitveMreze = listOf(RAZVRSTI_PRIPOROCENO to R.string.os_media_razvrsti_priporoceno,
                RAZVRSTI_NAJNOVEJSE to R.string.os_media_razvrsti_najnovejse, RAZVRSTI_IME_AZ to R.string.os_media_razvrsti_ime)
            izbira("razvrsti", razvrstitveMreze.map { getString(it.second) }, razvrstitveMreze.indexOfFirst { it.first == razvrstitev(VIDEO) }.coerceAtLeast(0),
                getString(R.string.os_media_razvrsti)) { i ->
                razvrstitve[VIDEO] = razvrstitveMreze[i].first
                SEZNAMI.remove(VIDEO)   // police (Zate) se uredijo enako
                narisiKatalog(o.naslov)
            }
            // 3) Zvrst.
            izbira("zvrst", listOf(getString(R.string.os_zvrst_vse)) + Zvrsti.VSE.map { getString(it.ime) },
                Zvrsti.VSE.indexOfFirst { it.kljuc == o.zvrst } + 1, null) { i ->
                odpriBrskanje(o.tip, if (i == 0) "" else Zvrsti.VSE[i - 1].kljuc)
            }
        }
        // Telefon: vrstica je natanko siroka kot vsebina (tri enake izbire, nic ne gleda cez rob); sirsi zasloni: naravna sirina.
        if (ozek) return niz
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
                // Odprta mreza (Filmi | Serije) ostane; krajevni videi so v policah (Zate).
                if (razdelek != i || odprtKatalog != null) return@post
                val podatki = prikazanePolice[i] ?: SEZNAMI[i]
                if (podatki != null) prikazi(i, podatki)
                else narisi(zgoraj(i), getString(R.string.os_glasba_nalagam), getString(R.string.os_glasba_nalagam), glavaRazdelka(i))
            }
        }.apply { name = "safeer-krajevno"; isDaemon = true }.start()
    }

    private fun zgoraj(i: Int): List<Vrsta> {
        val lokalne = if (vlc && i !in samoTaNaprava) krajevno[i].orEmpty() else emptyList()
        val vrsta = if (lokalne.isEmpty()) emptyList() else listOf(
            if (i == VIDEO) Vrsta(getString(R.string.os_krajevno_koren), videi(lokalne), video = true)
            else Vrsta(getString(R.string.os_krajevno_koren), skladbe(lokalne)))
        return vrsta + zgorajSplet(i)
    }

    private fun zgorajSplet(i: Int): List<Vrsta> {
        if (i in samoTaNaprava) return emptyList()
        val p = razvrsti(i, filtrirajJezike(i, MedijskiViri.priljubljene(this)))
        val seznami = MedijskiViri.seznami(this).map { it.copy(skladbe = filtrirajJezike(i, it.skladbe)) }
            .filter { it.skladbe.isNotEmpty() }
        fun seznamVrsta(sz: MedijskiViri.Seznam) = sz.skladbe.all { it.video }.let { video ->
            val urejene = razvrsti(i, sz.skladbe)
            // Dolg (uvozen) seznam: na polici prvih nekaj, zadnja kartica odpre vsega - polica s 300 karticami bi bila pocasna.
            val prve = urejene.take(NA_POLICI_SEZNAMA)
            val kartice = if (video) videi(prve, sz.ime, sz, urejene) else skladbe(prve, sz.ime, sz, urejene)
            val vse = if (urejene.size <= NA_POLICI_SEZNAMA) emptyList() else listOf(Kartica(getString(R.string.os_seznam_vse, urejene.size), sz.ime, "",
                { odpriSeznam(sz.ime, "", sz) { sz.skladbe } }, ikona = if (video) R.drawable.os_ikona_video else R.drawable.os_ikona_glasba))
            Vrsta("≡  " + sz.ime, kartice + vse, video) }
        return when (i) {
            DOMOV -> predajaVrsta() + listOf(
                Vrsta(getString(R.string.os_media_nedavno), razvrsti(i, filtrirajJezike(i, MedijskiViri.nedavno(this))).take(5).let { n ->
                    val kartice = n.map { sk ->
                        Kartica(sk.naslov, sk.izvajalec, sk.slika, { predvajaj(listOf(sk), 0) }, { meniNedavno(sk) },
                            ikona = if (sk.video) R.drawable.os_ikona_video else if (sk.radio) R.drawable.os_ikona_radio else R.drawable.os_ikona_glasba)
                    }
                    if (n.isEmpty()) kartice else kartice + Kartica(getString(R.string.os_media_pocisti_nedavno), getString(R.string.os_media_pocisti_nedavno_opis), "", { potrdiPocistiNedavno() }, ikona = R.drawable.os_ikona_ustavi)
                }, video = true, mala = true),
                Vrsta(getString(R.string.os_media_tvoji_viri), emptyList(), pogled = tvojiViri()),
                Vrsta(getString(R.string.os_mediji_prilj_glasba), skladbe(p.filterNot { it.video })),
                Vrsta(getString(R.string.os_mediji_prilj_video), videi(p.filter { it.video }), video = true),
            ) + seznami.map { seznamVrsta(it) }
            GLASBA -> listOf(Vrsta(getString(R.string.os_mediji_prilj_glasba), skladbe(p.filterNot { it.video || it.radio }))) +
                seznami.filterNot { sz -> sz.skladbe.all { it.video } }.map { seznamVrsta(it) }
            RADIO -> listOf(Vrsta(getString(R.string.os_media_prilj_radio), skladbe(p.filter { it.radio })))
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
                        klik = { potrdiPocistiNadaljuj() }, ikona = R.drawable.os_ikona_ustavi)
                    listOf(Vrsta(getString(R.string.os_media_nadaljuj_gledanje), kartice, video = true))
                } else emptyList()
                nadaljujVrste +
                    listOf(Vrsta(getString(R.string.os_mediji_prilj_video), videi(p.filter { it.video }), video = true)) +
                    seznami.filter { sz -> sz.skladbe.all { it.video } }.map { seznamVrsta(it) }
            }
            else -> emptyList()
        }
    }

    // ------------------------------------------------------------------ nadzorna plosca

    /** Pogledi nad vrstami: na plosci kartice razdelkov ter "zdaj se predvaja" s hitrimi dejanji. */
    private fun glavaRazdelka(i: Int, podatki: List<Podatki> = emptyList()): List<View> = if (vlc) when (i) {
        // Zavihki spodaj nadomestijo kartice razdelkov; razvrscanje je v meniju ⋮ (kot pri VLC).
        DOMOV -> listOfNotNull(zdajPlosca())
        VIDEO -> listOfNotNull(dovoljenjeKartica(i), videoIzbire(null))
        GLASBA -> listOfNotNull(dovoljenjeKartica(i), skokNaPolico(podatki.map { it.naslov }).takeIf { podatki.size > 1 })
        RADIO, TV_V_ZIVO -> listOfNotNull(skokNaPolico(podatki.map { it.naslov }).takeIf { podatki.size > 1 })
        else -> emptyList()
    } else when (i) {
        DOMOV -> listOfNotNull(kategorije(), razvrstiInFiltrirajGumb(i), zdajPlosca())
        VIDEO -> {
            val razdelki = razdelkiVrstica(i); val izbire = videoIzbire(null); val razvrsti = razvrstiInFiltrirajGumb(i)
            poveziIzbiro(razdelki, izbire, razvrsti)
            listOf(razdelki, izbire, razvrsti)
        }
        GLASBA, RADIO, TV_V_ZIVO -> listOfNotNull(
            razdelkiVrstica(i),
            razvrstiInFiltrirajGumb(i),
            skokNaPolico(podatki.map { it.naslov }).takeIf { podatki.size > 1 }
        )
        VIRI -> listOf(razdelkiVrstica(i))
        else -> emptyList()
    }

    /**
     * Razdelki (Glasba, Video, Radio, TV v zivo, Moji viri, Predvajalnik) kot vrsta gumbov na vrhu vsakega
     * razdelka: uporabnik ima izbiro vedno na voljo, ne le na plosci (Matej, 1. 10. 2026). Predvajalnik
     * (VLC slog) ima za to zavihke spodaj.
     */
    private fun razdelkiVrstica(trenutni: Int): View {
        val vrsta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun gumb(kljuc: String, i: Int?, res: Int, barva: Int, ime: Int, klik: () -> Unit) {
            val aktiven = i != null && i == trenutni
            vrsta.addView(LinearLayout(this).apply {
                tag = "r:$kljuc"
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
        }.distinctBy { it.kljuc }
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

    /** Vrstica zvrsti (Glasba, Radio) ali drzav (TV v zivo): klik skoci na polico, kot zanri pri filmih. */
    private fun skokNaPolico(naslovi: List<String>): View {
        val niz = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), dp(16), dp(8)) }
        naslovi.forEachIndexed { i, cilj ->
            niz.addView(besedilo(14f, osBarva(R.color.os_besedilo), true).apply {
                text = cilj; isFocusable = true; isClickable = true
                setPadding(dp(16), dp(8), dp(16), dp(8)); setBackgroundResource(R.drawable.os_meni_postavka)
                if (i == 0) nextFocusLeftId = meniMediji.id
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

    /** "Zdaj se predvaja" (ali zadnje predvajano z gumbom Nadaljuj) in hitra dejanja; null, ce ni nicesar. */
    private fun zdajPlosca(): View? {
        pSlika = null; pNaslov = null; pIzvajalec = null; pVir = null; pPotek = null; pCas = null
        pPredvajaj = null; pNakljucno = null; pPonavljaj = null; pSrce = null; hHitrost = null; hCasovnik = null
        val sk = GlasbaStoritev.trenutna()
        val p = GlasbaStoritev.predvajalnik
        val zadnja = if (sk == null) MedijskiViri.nedavno(this).firstOrNull() else null
        pZaPredvajanje = if (sk != null && p != null) true else if (zadnja != null) false else null
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
            tipke.addView(gumb(R.drawable.os_ikona_predvajaj, 48, "k:predvajaj") { pl?.let { if (it.isPlaying) it.pause() else it.play() }; osveziZdaj() }
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
        if (!sk.radio) hHitrost = dejanje("k:hitrost", R.drawable.os_ikona_hitrost, "") {
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
        // Zvocnik v omrezju (DLNA, npr. JBL): zvocnik vir potegne sam, telefon je le daljinec.
        val naZvocniku = Zvocniki.aktivni
        if (naZvocniku != null) {
            dejanje("k:zvocnik", R.drawable.os_ikona_zvocnik, getString(R.string.zvocnik_na, naZvocniku.ime)) { upravljajZvocnik() }
        } else if (!sk.video && DlnaPravila.zaZvocnik(sk.zvok)) {
            dejanje("k:zvocnik", R.drawable.os_ikona_zvocnik, getString(R.string.zvocnik_predvajaj_na)) { izberiZvocnik(sk) }
        }
        dejanje("k:ustavi", R.drawable.os_ikona_ustavi, getString(R.string.os_media_ustavi)) { GlasbaStoritev.ustavi(this); glavna.postDelayed({ if (razdelek == DOMOV) izberi(DOMOV) }, 300) }
        return v
    }

    private fun izberiZvocnik(sk: Jamendo.Skladba) = ZvocnikIzbira.izberi(this, sk) { if (!isFinishing) izberi(razdelek) }
    private fun upravljajZvocnik() = ZvocnikIzbira.upravljaj(this) { if (!isFinishing) izberi(razdelek) }

    /** Osvezi besedila, potek in stanja tipk na plosci (vsako sekundo in ob spremembi). */
    private fun osveziPlosco(zadnja: Jamendo.Skladba? = null) {
        val sk = GlasbaStoritev.trenutna() ?: zadnja ?: MedijskiViri.nedavno(this).firstOrNull() ?: return
        val p = GlasbaStoritev.predvajalnik
        pNaslov?.text = sk.naslov
        pIzvajalec?.text = if (SpletniVir.jeEnota(sk)) "" else sk.izvajalec
        pVir?.text = when {
            sk.radio -> getString(R.string.os_media_v_zivo)
            sk.video && sk.streznik.isNotBlank() -> "PeerTube · ${sk.streznik}"
            sk.povezava.contains("jamen") -> getString(R.string.os_glasba_vir, sk.povezava.removePrefix("https://").removePrefix("http://"))
            else -> ""
        }
        val slika = pSlika
        if (slika != null && sk.slika != pSlikaNaslov) { pSlikaNaslov = sk.slika; naloziSliko(sk.slika, slika) }
        if (p == null || pZaPredvajanje != true) return
        // Sprotni tok pomocnika: polozaj in trajanje glede na izvirnik (tok tece od zamika naprej).
        val tokPomocnika = SprotnaPomoc.tokZa(sk)
        val trajanje = if (tokPomocnika != null) tokPomocnika.trajanjeMs.coerceAtLeast(0L) else p.duration.takeIf { it > 0 } ?: 0L
        val polozaj = (tokPomocnika?.zamikMs ?: 0L) + p.currentPosition.coerceAtLeast(0)
        pPotek?.progress = if (trajanje > 0) (polozaj * 1000 / trajanje).toInt() else 0
        pCas?.text = if (trajanje > 0) "${cas(polozaj)} / ${cas(trajanje)}" else cas(polozaj)
        pPredvajaj?.setImageResource(if (p.isPlaying) R.drawable.os_ikona_pavza else R.drawable.os_ikona_predvajaj)
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
        val televizor = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        fun razdelek(i: Int): () -> Unit = { fokusVVsebino = true; izberi(i) }
        return listOf(
            Vir("tv", if (televizor) R.drawable.os_ikona_zaslon else R.drawable.os_ikona_naprava, osBarva(R.color.os_mint),
                getString(if (televizor) R.string.os_media_ta_tv else R.string.os_media_ta_naprava), getString(R.string.os_media_ta_tv_opis), datoteke),
            Vir("link", R.drawable.os_ikona_racunalnik, 0xFF8FA8FF.toInt(), getString(R.string.os_media_link), getString(R.string.os_media_link_opis), datoteke),
            Vir("radio", R.drawable.os_ikona_radio, 0xFFFF9580.toInt(), getString(R.string.os_mediji_postaje),
                getString(R.string.os_media_stevilo_prilj, MedijskiViri.priljubljene(this).count { it.radio }), razdelek(RADIO)),
            Vir("peertube", R.drawable.os_ikona_video, 0xFFFF9580.toInt(), "PeerTube",
                getString(R.string.os_media_stevilo_streznikov, MedijskiViri.streznikiPeerTube(this).size), razdelek(VIDEO)),
        ) + MedijskiViri.vgrajeniPeerTube(this).map { s ->
            // Vgrajeni strezniki PeerTube so viri kot vsi drugi: zadrzan OK -> Izbrisi vir (nazaj z Dodaj vir).
            val v = MedijskiViri.vgrajenPeerTubeVir(s)
            Vir(MedijskiViri.kljucPripetega(v), R.drawable.os_ikona_video, 0xFFFF9580.toInt(), s, "PeerTube · $s", razdelek(VIDEO), v)
        } + (if (si.safeer.tv.BuildConfig.FLAVOR == "brskalnik") emptyList() else listOf(
            Vir("magnet", R.drawable.os_ikona_link, 0xFFB69CFF.toInt(), getString(R.string.magnet_naslov), getString(R.string.magnet_vir_opis),
                { startActivity(Intent(this, MagnetActivity::class.java)) }),
        )) + MedijskiViri.vsi(this).map { v ->
            Vir(MedijskiViri.kljucPripetega(v), when { v.jePeerTube -> R.drawable.os_ikona_video; v.jeSplet || v.jeDodatek -> R.drawable.os_ikona_splet; else -> R.drawable.os_ikona_glasba },
                // Dodatek: ime iz manifesta (Cinemeta, Torrentio ...) in samo streznik - naslov ima lahko kljuc storitve.
                0xFF7FB2FF.toInt(), if (v.jeStremio) Stremio.imeIzPredpomnilnika(v.naslov) ?: v.ime else v.ime, when {
                    v.jePeerTube -> "PeerTube · ${v.naslov}"
                    v.jeStremio -> getString(R.string.os_mediji_dodatek_stremio) + " · " + (android.net.Uri.parse(Stremio.osnova(v.naslov)).host ?: "")
                    v.tip == MedijskiViri.API -> "API · " + (try { java.net.URL(v.naslov.substringBefore('|').trim()).host } catch (_: Exception) { "" })
                    else -> v.naslov.removePrefix("https://").removePrefix("http://")
                }, {
                    when {
                        v.jePeerTube -> { fokusVVsebino = true; izberi(VIDEO) }
                        // Dodatek: pokazemo shranjeni naslov (predvajanje prek dodatkov je naslednji korak).
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
        val daljinec = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
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
        // Seznami predvajanja (Shrani vrsto na zaslonu predvajanja) imajo v Brskaj svojo polico.
        val seznami = MedijskiViri.seznami(this)
        val vrstaSeznamov = if (seznami.isEmpty()) emptyList() else listOf(Vrsta(getString(R.string.os_seznami_predvajanja),
            seznami.map { sz ->
                Kartica(sz.ime, resources.getQuantityString(R.plurals.os_stevilo_posnetkov, sz.skladbe.size, sz.skladbe.size), sz.skladbe.firstOrNull { it.slika.startsWith("http") }?.slika.orEmpty(),
                    { odpriSeznam(sz.ime, "", sz) { sz.skladbe } }, { meniSeznama(sz) },
                    ikona = if (sz.skladbe.all { it.video }) R.drawable.os_ikona_video else R.drawable.os_ikona_glasba)
            }, mala = true))
        return vrstaSeznamov + listOf(Vrsta("", listOf(
            Kartica(getString(R.string.os_mediji_dodaj), getString(R.string.os_mediji_dodaj_opis), "", { dodajVir() }, ikona = R.drawable.os_ikona_plus),
            Kartica(getString(R.string.os_mediji_dodatki), getString(R.string.os_mediji_dodatki_opis), "", { dodajDodatke() }, ikona = R.drawable.os_ikona_plus),
            Kartica(getString(R.string.os_uvoz_naslov), getString(R.string.os_uvoz_opis), "", { uvoziSeznam() }, ikona = R.drawable.os_ikona_plus)) +
            vsiViri().map { v -> Kartica((if (v.kljuc in pripeti) "★ " else "") + v.ime, v.opis, "", { v.odpri() }, { dolgoNaViru(v) }, ikona = v.ikona) },
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
                    .setPositiveButton(R.string.os_mediji_odstrani_seznam) { _, _ -> MedijskiViri.odstraniSeznam(this, sz.ime); izberiNaMestu(VIRI) }
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
        val dejanja = listOf(getString(if (na) R.string.os_media_s_plosce else R.string.os_media_na_plosco) to preklopi,
            getString(R.string.os_media_izbrisi_vir) to { odstraniVir(dodan, v.ime) })
        pokaziBrisanje(AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(v.ime)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(getString(R.string.os_preklici), null)
        )
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
                        celota: List<Jamendo.Skladba> = s) = s.map { sk ->
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
                if (jeVrstaPosnetkov(sk, seznam)) predvajajVrstoPosnetkov(celota, sk)
                else if (SpletniVir.jeEnota(sk)) razresiSplet(sk)
                else celota.filterNot { it.mime == MedijskiViri.STRAN }.let { p -> predvajaj(p, p.indexOf(sk)) }
            }, { meni(sk, celota, vrsta, seznam) }, ikona = ikona, oznaka = oznaka,
                tvId = sk.id.removePrefix("tv:").takeIf { sk.id.startsWith("tv:") }.orEmpty())
        }
    }

    /** Spletno stran odpre brskalnik Safeer (predvaja vse); nasa glasba se ustavi, zvok strani ob Domov igra naprej. */
    private fun odpriStran(url: String, ime: String) {
        GlasbaStoritev.predvajalnik?.pause()
        startActivity(Brskalnik.medijskaStran(this, url, ime))
    }

    /** Isti naslov iz vec virov je ena kartica; Safeer sam izbere vir in ga uporabniku ne izpostavlja. */
    private fun videi(v0: List<Jamendo.Skladba>, vrsta: String = "", seznam: MedijskiViri.Seznam? = null,
                      celota: List<Jamendo.Skladba> = v0): List<Kartica> {
        // Vsebine, za katero vemo, da je noben dodatek ne predvaja, ne kazemo nikjer (police, iskanje, mreza).
        val v = v0.filterNot { znanoNiNaVoljo(it) }
        preveriPolico(v)
        val napredekKrajevnih = if (v.none { it.id.startsWith("krajevno:") }) emptyMap()
            else MediaNapredek.seznam(this).filter { it.skladba.id.startsWith("krajevno:") && it.polozaj > 0 }.associateBy { it.skladba.id }
        val skupine = SpletniVir.zdruziEnako(v)
        val podvojene = skupine.count { it.size > 1 }
        if (podvojene > 0) android.util.Log.i("SafeerOsMedia",
            "zdruzevanje: kandidati=${v.size}, kartice=${skupine.size}, podvojene_skupine=$podvojene, odstranjeni=${v.size - skupine.size}")
        return skupine.map { urejene ->
            val sk = urejene.first()
            val tvId = sk.id.removePrefix("tv:").takeIf { sk.id.startsWith("tv:") }.orEmpty()
            // Kanal v zivo: uradni (tv:) ali iz dodatka Stremio tipa "tv" (stremio|tv|...): oznaka V ZIVO in ikona TV.
            val vZivo = tvId.isNotBlank() || Stremio.jeVZivo(sk)
            // Video z naprave: napredek ali trajanje (kot VLC), spletni: letnica.
            val podnaslov = if (sk.id.startsWith("krajevno:") || sk.id.startsWith(PREDPONA_PC_PRENOSA))
                napredekKrajevnih[sk.id]?.let { n -> "${cas(n.polozaj)} / ${cas(n.trajanje)}" } ?: sk.izvajalec
            // Posnetek s seznama predvajanja (uvozen z YouTuba): pod naslovom avtor, letnice nima.
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
            val kakovost = when {
                sk.quality >= 2160 || Regex("(?:2160p?|4k|uhd)").containsMatchIn(signal) -> "4K UHD"
                sk.quality >= 1080 || Regex("1080p?|full[- ]?hd|fhd").containsMatchIn(signal) -> "HD 1080P"
                sk.quality >= 720 || Regex("720p?|\\bhd\\b").containsMatchIn(signal) -> "HD"
                else -> ""
            }
            Kartica(sk.naslov, podnaslov, sk.slika, {
                // Uporabnik vidi eno kartico. V ozadju ostanejo vse razlicice, urejene od najboljse.
                when {
                    jeVrstaPosnetkov(sk, seznam) -> predvajajVrstoPosnetkov(celota, sk)
                    SpletniVir.jeEnota(sk) -> razresiSplet(sk, urejene.drop(1))
                    Stremio.jeEnota(sk) && urejene.size > 1 -> razresiStremio(sk, razlicice = urejene.drop(1))
                    else -> predvajaj(listOf(sk), 0)
                }
            }, { meni(sk, celota, vrsta, seznam) }, oznaka = tip, kakovost = kakovost,
                ocena = sk.rating.takeIf { it > 0.0 }?.let { String.format(Locale.ROOT, "%.1f", it) }.orEmpty(),
                ikona = if (vZivo) R.drawable.os_ikona_tv else if (sk.radio) R.drawable.os_ikona_radio else R.drawable.os_ikona_video,
                tvId = tvId,
                // Televizor: ko izbira obstane na filmu iz dodatkov, tokove poiscemo vnaprej (predpomnilnik) - OK ga zazene takoj.
                priprava = if (jeTv() && Stremio.jeEnota(sk) && !Stremio.jeSerija(sk) && !vZivo)
                    ({ Stremio.razstavi(sk)?.let { (_, tip, id) -> tokoviVzporedno(tip, id) }; Unit }) else null)
        }
    }

    private fun meniNedavno(sk: Jamendo.Skladba) {
        pokaziBrisanje(AlertDialog.Builder(this).setTitle(sk.naslov)
            .setItems(arrayOf(getString(R.string.os_media_odstrani_nedavno))) { _, _ ->
                MedijskiViri.odstraniNedavno(this, sk)
                SEZNAMI.remove(DOMOV)
                izberi(DOMOV)
            }.setNegativeButton(android.R.string.cancel, null))
    }

    private fun potrdiPocistiNedavno() {
        pokaziBrisanje(AlertDialog.Builder(this).setTitle(R.string.os_media_pocisti_nedavno)
            .setMessage(R.string.os_media_pocisti_nedavno_potrdi)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                MedijskiViri.pocistiNedavno(this)
                SEZNAMI.remove(DOMOV)
                izberi(DOMOV)
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
                        if (razdelek == VIDEO) izberi(VIDEO)
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
                if (razdelek == VIDEO) izberi(VIDEO)
            }.setNegativeButton(android.R.string.cancel, null))
    }

    // ------------------------------------------------------------------ priljubljene

    private fun meni(sk: Jamendo.Skladba, vrsta: List<Jamendo.Skladba>, ime: String, seznam: MedijskiViri.Seznam?) {
        val dejanja = mutableListOf<Pair<String, () -> Unit>>()
        if (MediaNapredek.polozaj(this, sk) > 0) {
            dejanja += getString(R.string.os_media_odstrani_nadaljuj) to {
                MediaNapredek.odstrani(this, sk)
                Toast.makeText(this, R.string.os_media_odstranjeno_nadaljuj, Toast.LENGTH_SHORT).show()
                SEZNAMI.remove(VIDEO)
                if (razdelek == VIDEO) izberi(VIDEO)
            }
        }
        if (MedijskiViri.shranljiva(sk)) {
            val je = MedijskiViri.jePriljubljena(this, sk)
            dejanja += getString(if (je) R.string.os_mediji_odstrani_prilj else R.string.os_mediji_dodaj_prilj) to {
                val zdaj = MedijskiViri.preklopiPriljubljeno(this, sk)
                Toast.makeText(this, if (zdaj) R.string.os_mediji_dodano_prilj else R.string.os_mediji_odstranjeno_prilj, Toast.LENGTH_SHORT).show()
                osveziPriljubljene()
            }
        }
        // Svoj seznam predvajanja: skladbo dodas na obstojecega ali novega (ne samo "shrani vso vrsto").
        if (SeznamOkno.mozno(sk)) dejanja += getString(R.string.os_seznam_dodaj) to { dodajNaSeznam(sk) }
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
        } else if (vrsta.count { MedijskiViri.shranljiva(it) } > 1) dejanja += getString(R.string.os_mediji_shrani_seznam) to {
            val sz = MedijskiViri.shraniSeznam(this, ime.ifBlank { sk.izvajalec.ifBlank { getString(R.string.os_mediji_moja_vrsta) } }, vrsta)
            if (sz != null) Toast.makeText(this, getString(R.string.os_mediji_seznam_shranjen, sz.ime), Toast.LENGTH_SHORT).show()
            osveziPriljubljene()
        }
        // Film ali serija iz dodatkov: predvajanje samo izbere najboljsi tok; tu ga uporabnik lahko izbere sam.
        if (Stremio.jeEnota(sk)) dejanja += getString(R.string.os_media_izberi_tok) to { razresiStremio(sk, rocno = true) }
        if (dejanja.isEmpty()) return
        pokaziBrisanje(AlertDialog.Builder(this).setTitle(sk.naslov)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, k -> dejanja[k].second() }
            .setNegativeButton(android.R.string.cancel, null))
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
    private fun osveziPriljubljene() { if (razdelek != VIRI && razdelek != ISKANJE) izberiNaMestu(razdelek) }

    // ------------------------------------------------------------------ iskanje

    /** Zadnji zadetki in beseda: ob vrnitvi na Iskanje so se tam. */
    private var zadetki: List<Vrsta>? = null
    private var zadnjaBeseda = ""
    private var opisZadetkov = ""

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
                if (q.length >= 2) isci(q)
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

    private fun odpriIskanje(beseda: String) {
        // Iskanje, odprto iz mreze zasebnega dodatka, isce samo v njem in ne pusti sledi (ZasebniDodatki).
        odprtKatalog?.dodatek?.takeIf { it.isNotBlank() }?.let { zasebnoIskanje = it; zadnjaBeseda = "" }
        if (razdelek != ISKANJE) izberi(ISKANJE)
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
        data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { odpriIskanje(it) }
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
        val moje = ++nalaganje
        stanje.text = getString(R.string.os_glasba_nalagam)
        val strezniki = MedijskiViri.streznikiPeerTube(this)
        val mali = beseda.lowercase()
        val viri = MedijskiViri.vsi(this).filter { it.tip != MedijskiViri.API && !it.jeDodatek }.filter { it.ime.lowercase().contains(mali) || it.naslov.lowercase().contains(mali) ||
            Relevantnost.ocena(beseda, it.ime, "", it.naslov) >= Relevantnost.DOBER }
        val naprave = if (link.jeKrajevni() || !link.povezan) emptyList() else link.racunalnikiZDatotekami()
        val taNaprava = getString(if (jeTv()) R.string.os_media_ta_tv else R.string.os_media_ta_naprava)
        // Preklic starih poizvedb: hitro zaporedno iskanje ne sme pustiti kupa zivih niti.
        synchronized(iskanjeNiti) { iskanjeNiti.forEach { it.cancel(true) }; iskanjeNiti.clear() }
        val rezultati = arrayOfNulls<Any>(13)
        val stremioNaslovi = MedijskiViri.vsi(this).filter { it.jeStremio }.map { it.naslov }
        val spletniViri = MedijskiViri.vsi(this).filter { it.jeSplet || it.tip == MedijskiViri.API }
        val izSeznamov = MedijskiViri.iskanjeVSeznamih(this, beseda)
        val opravila = listOf<() -> Any>(
            { try { Jamendo.isciSkladbe(beseda) } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
            { try { PeerTube.isci(strezniki, beseda) } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
            { try { Radio.isci(beseda) } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
            { try { Jamendo.isciIzvajalce(beseda) } catch (_: Exception) { emptyList<Jamendo.Izvajalec>() } },
            { try { KrajevneDatoteke.najdi(this, beseda) } catch (_: Exception) { emptyList<KrajevneDatoteke.Najdeno>() } },
            { isciNaNapravah(naprave, beseda) },
            { try { Podkasti.isci(beseda) } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
            { try { Arhiv.isci(beseda) } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
            { SpletniVir.isciVse(this, spletniViri, beseda) },
            { try { JavnaLast.isci(beseda) } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
            { try { TuneIn.isci(beseda) } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
            { try { if (samoDodatek != null) Stremio.isci(listOf(samoDodatek), beseda, tudiZasebni = true) else Stremio.isci(Stremio.zKatalogom(stremioNaslovi), beseda) } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
            // Vgrajeni kanali TV v zivo (seznam je v aplikaciji - brez omrezja).
            { try { TvVZivo.poDrzavah().flatMap { it.second }.filter { Stremio.ujema(it.naslov, beseda) } } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
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
            @Suppress("UNCHECKED_CAST") val izDodatkovVsi = rezultati[11] as? List<Jamendo.Skladba> ?: emptyList()
            zadetkiDodatkov = izDodatkovVsi
            // Cesar noben dodatek ne predvaja, med zadetki ni.
            val izDodatkov = izDodatkovVsi.filterNot { znanoNiNaVoljo(it) }
            @Suppress("UNCHECKED_CAST") val tvKanali = rezultati[12] as? List<Jamendo.Skladba> ?: emptyList()
            // Zadetki dodatkov po vrsti vsebine: vsak pristane v svoji polici (film, serija, TV v zivo, glasba, radio).
            val dodatkiPoRazredu = izDodatkov.groupBy { Stremio.razredEnote(it) }
            val dFilmi = dodatkiPoRazredu[Stremio.FILM].orEmpty() + dodatkiPoRazredu[Stremio.VIDEO].orEmpty()
            val dSerije = dodatkiPoRazredu[Stremio.SERIJA].orEmpty()
            val dTv = dodatkiPoRazredu[Stremio.TV].orEmpty()
            val dGlasba = dodatkiPoRazredu[Stremio.GLASBA].orEmpty()
            val dRadio = dodatkiPoRazredu[Stremio.RADIO].orEmpty()
            // TuneIn toka do klika navadno se ne pozna. Takrat istoimensko postajo Radio Browserja
            // obdrzimo kot prvi, ze razreseni zadetek; pri znanih tokovih primerjamo tudi naslov toka.
            val tuneIn = tuneInSurovi.filterNot { t ->
                postaje.any { r ->
                    SpletniVir.cistNaslov(r.naslov) == SpletniVir.cistNaslov(t.naslov) &&
                        (t.zvok.isBlank() || r.zvok.isBlank() || r.zvok == t.zvok)
                }
            }
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
            postaje.forEach { vsi.add(Relevantnost.Zadetek(it, it.naslov, it.izvajalec, getString(R.string.os_glasba_radio), 5)) }
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
            val najboljsi = lestvica.filter { it.second >= Relevantnost.SPODNJA }.filter { z ->
                val s = z.first.stvar as? Jamendo.Skladba
                if (s != null) {
                    val k = SpletniVir.cistNaslov(s.izvajalec + " " + s.naslov)
                    if (k.isNotBlank()) videniNajboljsi.add(k) else true
                } else true
            }.take(12)
            val prikazani = najboljsi.map { (it.first.stvar as Jamendo.Skladba).id }.toSet()
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
                    Vrsta(getString(R.string.os_media_najboljsi), najboljsi.map { p -> kartica(p.first) }),
                    // Dodatki: filmi in serije v loceni polici (jasno, kaj je kaj).
                    Vrsta("🎬 " + getString(R.string.os_media_filmi), videi(ostali(poUjemanju(dFilmi)), beseda), video = true),
                    Vrsta("📺 " + getString(R.string.os_media_serije), videi(ostali(poUjemanju(dSerije)), beseda), video = true),
                    Vrsta("📡 " + getString(R.string.os_mediji_tv_v_zivo), videi(ostali(poUjemanju(dTv + tvKanali)), beseda), video = true),
                    Vrsta(getString(R.string.os_mediji_glasba), skladbe(ostali(poUjemanju(dGlasba) + glasba + spletAvdio + spletVideospoti), beseda)),
                    Vrsta(getString(R.string.os_glasba_video), videi(ostali(videi + javnaLast + spletFilmiInSerije), beseda), video = true),
                    Vrsta(getString(R.string.os_mediji_izvajalci), izvajalci.map { iz ->
                        Kartica(iz.ime, getString(R.string.os_glasba_izvajalec), iz.slika, { odpriIzvajalca(iz) }) }),
                    Vrsta(getString(R.string.os_mediji_postaje), skladbe(ostali(poUjemanju(dRadio) + postaje + tuneIn), beseda)),
                    Vrsta(getString(R.string.os_media_podkasti), skladbe(podkasti, beseda)),
                    Vrsta("Archive.org", skladbe(ostali(arhiv), beseda)),
                    spletVrsta.takeIf { splet && !nicNasli && koncno },
                )
                zadetki = vrste
                // Koliko in kje: »5 zadetkov · janez-PC, Jamendo, PeerTube«.
                val izvori = lestvica.map { it.first.izvor }.distinct()
                opisZadetkov = if (izvori.isEmpty()) getString(R.string.os_media_ni_zadetkov_vir)
                    else getString(R.string.os_media_zadetki_izvori, lestvica.size, izvori.joinToString(", "))
                if (razdelek != ISKANJE) return@post
                narisi(vrste, if (koncno) opisZadetkov else opisZadetkov + " · " + getString(R.string.os_glasba_nalagam))
                if (prvic) fokusNaPrvo()
            }
            return true
        }
        Thread {
            // Prikaz po delih: kar prispe v 2,5 s, se takoj pokaze; pocasnejsi viri dopolnijo isti zaslon
            // (narisi obdrzi fokus na isti kartici). Po 12 s ne cakamo vec (spletni viri na TV potrebujejo cas).
            val zacetek = System.currentTimeMillis()
            var narisanih = -1
            while (moje == nalaganje && !isFinishing) {
                val gotovih = futures.count { it.isDone }
                val cas = System.currentTimeMillis() - zacetek
                val konec = gotovih == futures.size || cas >= 12_000
                if ((konec || (gotovih != narisanih && cas >= 2_500)) && prikazi(konec, narisanih < 0)) narisanih = gotovih
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
        val cakajo = l.filter { sk -> kljucRazpolozljivosti(sk)?.let { Razpolozljivost.stanje(this, it) == null } == true }.take(12)
        if (cakajo.isEmpty()) return false
        val torrent = torrentMeja()
        val niti = cakajo.map { sk -> preverjanjeEpizod.submit<Boolean> {
            val r = try { preveriEnoto(sk, naslovi, torrent) } catch (_: Exception) { null }
            if (r != null) kljucRazpolozljivosti(sk)?.let { Razpolozljivost.zapomni(applicationContext, it, r) }
            r == false
        } }
        val rok = System.currentTimeMillis() + 10_000
        return niti.count { f -> try { f.get((rok - System.currentTimeMillis()).coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) { false } } > 0
    }

    /** Streznik datotek naprave v Linku za zadetek (predvajanje s pripetim potrdilom). */
    private val strezniki = ConcurrentHashMap<String, DatotekeActivity.Streznik>()

    /** Kartica iskanja: spletni vir je notranja podrobnost, zato ga na kartici ne razkrivamo. */
    private fun kartica(z: Relevantnost.Zadetek<*>): Kartica {
        val sk = z.stvar as Jamendo.Skladba
        val podnaslov = if (SpletniVir.jeEnota(sk)) sk.year.takeIf { it > 0 }?.toString().orEmpty()
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
        GlasbaStoritev.predvajalnik?.pause()
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
        GlasbaStoritev.predvajalnik?.pause()
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

    /** Seznam iz vira (epizode podkasta, dodani .m3u): prikaz kot vrsta, uporabnik izbere, kaj predvaja. */
    private fun odpriSeznam(ime: String, opis: String, seznam: MedijskiViri.Seznam? = null, nalozi: () -> List<Jamendo.Skladba>) {
        stanje.text = getString(R.string.os_glasba_nalagam)
        delavec.execute {
            val s = try { nalozi() } catch (_: Exception) { emptyList() }
            glavna.post {
                if (isFinishing) return@post
                if (s.isEmpty()) { stanje.text = getString(R.string.os_glasba_napaka); return@post }
                val video = seznam != null && s.all { it.video }
                narisi(listOf(Vrsta(ime, if (video) videi(s, ime, seznam) else skladbe(s, ime, seznam), video = video, mreza = seznam != null)), opis)
                fokusNaPrvo()
                odprtSeznam = seznam?.ime.orEmpty()
            }
        }
    }

    private fun razresiArhiv(sk: Jamendo.Skladba) {
        stanje.text = getString(R.string.os_glasba_nalagam)
        delavec.execute {
            val s = try { Arhiv.datoteke(sk) } catch (_: Exception) { emptyList() }
            glavna.post {
                if (isFinishing) return@post
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

    private fun odpriIzvajalca(iz: Jamendo.Izvajalec) {
        stanje.text = getString(R.string.os_glasba_nalagam)
        delavec.execute {
            val s = try { Jamendo.odIzvajalca(iz.id) } catch (_: Exception) { null }
            glavna.post {
                if (isFinishing) return@post
                if (s == null) { stanje.text = getString(R.string.os_glasba_napaka); return@post }
                narisi(listOf(Vrsta(iz.ime, skladbe(s, iz.ime))), getString(R.string.os_glasba_od_izvajalca, iz.ime))
                fokusNaPrvo()
            }
        }
    }

    // ------------------------------------------------------------------ viri

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
            hint = getString(R.string.os_mediji_dodaj_namig); setSingleLine(); inputType = InputType.TYPE_TEXT_VARIATION_URI
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
            hint = "https://…"; setSingleLine(); inputType = InputType.TYPE_TEXT_VARIATION_URI
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
        delavec.execute {
            val u = try { UvozSeznama.uvozi(povezava) } catch (_: Exception) { null }
            // Isto ime kot obstojec seznam ga zamenja (ponoven uvoz = osvezitev seznama).
            val sz = u?.let { MedijskiViri.shraniSeznam(this, it.ime.take(60), it.skladbe, it.vir) }
            glavna.post {
                if (isFinishing) return@post
                stanje.text = opis(razdelek)
                if (sz == null) {
                    AlertDialog.Builder(this).setTitle(R.string.os_uvoz_naslov).setMessage(R.string.os_uvoz_ni_uspel)
                        .setPositiveButton(android.R.string.ok, null).show()
                    return@post
                }
                Toast.makeText(this, getString(R.string.os_uvoz_uspeh, sz.ime, sz.skladbe.size), Toast.LENGTH_LONG).show()
                SEZNAMI.remove(DOMOV); SEZNAMI.remove(GLASBA); SEZNAMI.remove(VIDEO)
                // Najprej Moji viri (tam so seznami predvajanja), nato uvozeni seznam: zacetni zaslon, ki se se
                // nalaga, ga ne prekrije, Nazaj pa pelje k seznamom.
                izberi(VIRI)
                odpriSeznam(sz.ime, "", sz) { sz.skladbe }
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
                if (isFinishing) return@post
                SEZNAMI.remove(DOMOV); SEZNAMI.remove(GLASBA)
                stanje.text = if (konec) opis(razdelek) else getString(R.string.os_uvoz_iscem, narejenih.get(), cakajo.size)
                // Odprt seznam osvezimo ob koncu (slike skladb), ne sproti - sproti bi uporabniku skakal pod prsti.
                if (konec && odprtSeznam == ime) MedijskiViri.seznami(this).firstOrNull { it.ime == ime }?.let { sz -> odpriSeznam(sz.ime, "", sz) { sz.skladbe } }
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
                        if (isFinishing) return@post
                        if (vir == null) {
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
                        SEZNAMI.remove(DOMOV); SEZNAMI.remove(VIDEO); SEZNAMI.remove(TV_V_ZIVO)
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
            hint = getString(R.string.os_mediji_dodatki_stremio_namig); setSingleLine(); inputType = InputType.TYPE_TEXT_VARIATION_URI
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
            val v = MedijskiViri.dodajDodatek(this, MedijskiViri.STREMIO, naslov, "")
            Toast.makeText(this, getString(R.string.os_mediji_dodatki_shranjen, v.ime), Toast.LENGTH_SHORT).show()
            okno.dismiss()
            SEZNAMI.remove(DOMOV); SEZNAMI.remove(VIDEO); SEZNAMI.remove(TV_V_ZIVO); izberi(VIRI)
            // Kartica dodatka pokaze ime iz manifesta (ne naslova streznika), brz ko ga preberemo.
            if (Stremio.imeIzPredpomnilnika(v.naslov) == null) delavec.execute {
                try { Stremio.zaseben(v.naslov) } catch (_: Exception) { }
                glavna.post { if (!isFinishing && razdelek == VIRI && odprtKatalog == null && odprtSeznam.isBlank() && Stremio.imeIzPredpomnilnika(v.naslov) != null) izberiNaMestu(VIRI) }
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
                SEZNAMI.clear(); BRSKANJE.clear(); VSE_VIDEO = null
                // Odprta mreza Filmi | Serije se nalozi znova z novimi dodatki.
                odprtKatalog?.takeIf { it.tip.isNotBlank() && razdelek == VIDEO }?.let { o -> odpriBrskanje(o.tip, o.zvrst); return@uskladi }
            }
            if (miruje && odprtKatalog == null && odprtSeznam.isBlank() && (noviViri || razdelek == VIRI || razdelek == GLASBA || razdelek == DOMOV)) izberiNaMestu(razdelek)
        }
    }

    /** Zadnja sprememba Mojih virov z druge naprave, ki jo je ta zaslon ze uposteval ([SeznamiSink.viriSpremenjeni]). */
    private var videniViri = SeznamiSink.viriSpremenjeni

    /** [ime] je ime s kartice (dodatek: iz manifesta) - shranjeno ime dodatka je lahko samo naslov streznika. */
    private fun odstraniVir(v: MedijskiViri.Vir, ime: String = v.ime) {
        // "Tudi z drugih naprav" velja le za vir, ki med napravami res potuje (zaseben dodatek ostane na tej napravi).
        val povsod = !link.jeKrajevni() && MedijskiViri.greMedNaprave(v)
        pokaziBrisanje(AlertDialog.Builder(this)
            .setTitle(ime.ifBlank { v.ime })
            .setMessage(if (povsod) R.string.os_mediji_odstrani_vir_vsepovsod else R.string.os_mediji_odstrani_vprasanje)
            .setPositiveButton(R.string.os_mediji_odstrani) { _, _ ->
                MedijskiViri.odstrani(this, v)
                SEZNAMI.remove(DOMOV); SEZNAMI.remove(VIDEO); SEZNAMI.remove(TV_V_ZIVO)
                izberiNaMestu(VIRI)
            }
            .setNegativeButton(android.R.string.cancel, null)
        )
    }

    // ------------------------------------------------------------------ predvajanje

    private fun zacniPripravo(sk: Jamendo.Skladba): String? {
        val kljuc = sk.id.ifBlank { sk.povezava.ifBlank { sk.naslov } }
        if (!pripraveVTeKu.add(kljuc)) return null
        Toast.makeText(this, getString(R.string.os_media_pripravljam, sk.naslov), Toast.LENGTH_SHORT).show()
        return kljuc
    }

    private fun koncajPripravo(kljuc: String) { pripraveVTeKu.remove(kljuc) }

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

    /** Vprasa naprave, kaj predvajajo (ali so nazadnje gledale), in ponudi nadaljevanje tukaj - vlecenje na cilju, kot je dolocil Matej. */
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
    private fun predvajaj(seznam: List<Jamendo.Skladba>, i: Int) {
        val sk = seznam.getOrNull(i) ?: return
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
        if (Stremio.jeEnota(sk)) { razresiStremio(sk); return }
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
        delavec.execute {
            // Zaseben dodatek se ne mesa med Filme in Serije: odpre se sam zase in samo od tukaj (ZasebniDodatki).
            if ((try { Stremio.zaseben(naslov) } catch (_: Exception) { null }) == true) { odpriSamDodatek(naslov); return@execute }
            val razred = try { Stremio.glavniRazred(naslov) } catch (_: Exception) { Stremio.VIDEO }
            glavna.post {
                if (isFinishing) return@post
                val cilj = when (razred) { Stremio.TV -> TV_V_ZIVO; Stremio.GLASBA -> GLASBA; Stremio.RADIO -> RADIO; else -> VIDEO }
                if (cilj == VIDEO) skociNaDodatek = naslov
                fokusVVsebino = true; SEZNAMI.remove(cilj); izberi(cilj)
            }
        }
    }

    /** Katalogi enega dodatka kot mreza (zaseben dodatek): prve strani katalogov, naprej "Nalozi vec". Klic iz delovne niti. */
    private fun odpriSamDodatek(naslov: String) {
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
            if (isFinishing) return@post
            if (vsi.isEmpty() && polica.isEmpty()) { stanje.text = getString(R.string.os_glasba_prazno); return@post }
            odprtKatalogIz = razdelek
            odprtKatalog = OdprtKatalog(katalogi, vsi, preneseno, strani.any { it.size >= STRAN_KATALOGA_MIN }, naslov = ime, dodatek = naslov)
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
     * Tokovi iz vseh dodatkov hkrati (vsak dodatek svoja nit, skupaj najvec 12 s): cakanje je toliko, kot traja
     * najpocasnejsi dodatek, ne vsota vseh (prej zaporedno).
     */
    private fun tokoviVzporedno(tip: String, id: String): List<Stremio.Tok> {
        val niti = stremioNaslovi().map { n -> iskanjeDelavec.submit<List<Stremio.Tok>> { try { Stremio.tokovi(listOf(n), tip, id) } catch (_: Exception) { emptyList() } } }
        val zacetek = android.os.SystemClock.uptimeMillis()
        var prviTok = 0L
        // Nic cakanja: ko ima prvi dodatek predvajljiv tok, pocasnim damo se 1,5 s (boljsa izbira), ne vseh 12 s.
        while (true) {
            val zdaj = android.os.SystemClock.uptimeMillis()
            if (niti.all { it.isDone } || zdaj - zacetek > 12_000) break
            if (prviTok == 0L && niti.any { f -> f.isDone && (try { f.get() } catch (_: Exception) { emptyList() }).any { it.vrsta == "url" } }) prviTok = zdaj
            if (prviTok != 0L && zdaj - prviTok > 1_500) break
            try { Thread.sleep(40) } catch (_: InterruptedException) { break }
        }
        val tokovi = niti.filter { it.isDone }.flatMap { f -> try { f.get() } catch (_: Exception) { emptyList() } }
        // Dodatek, ki v roku ni odgovoril, bi tok morda imel: brez tokov je to izpad, ne "ni na voljo".
        if (tokovi.isEmpty() && niti.any { !it.isDone }) Stremio.zadnjiIzpad.set(System.currentTimeMillis())
        return tokovi
    }

    /** Kaj ta naprava predvaja (zaslon, dekodirniki slike in zvoka) - za izbiro najboljsega toka brez vprasanj. */
    private val zmoznostiNaprave: TokIzbira.Zmoznosti by lazy {
        val kodeki = try {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder }.flatMap { it.supportedTypes.toList() }.map { it.lowercase(Locale.ROOT) }.toSet()
        } catch (_: Exception) { emptySet() }
        @Suppress("DEPRECATION") val zaslon = windowManager.defaultDisplay
        val stranica = try { zaslon.supportedModes.maxOfOrNull { minOf(it.physicalWidth, it.physicalHeight) } } catch (_: Exception) { null }
            ?: minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        @Suppress("DEPRECATION") val hdrTipi = try { zaslon.hdrCapabilities?.supportedHdrTypes?.toSet() } catch (_: Exception) { null } ?: emptySet()
        // Televizor zvok Dolby odda sam ali ga preda zvocniku (HDMI); na telefonu odloca dekodirnik.
        val tv = jeTv()
        TokIzbira.Zmoznosti(
            visina = when { stranica >= 2000 -> 2160; stranica >= 1300 -> 1440; stranica >= 1000 -> 1080; else -> 720 },
            hevc = "video/hevc" in kodeki, av1 = "video/av01" in kodeki, hdr = hdrTipi.isNotEmpty(),
            dolbyVision = "video/dolby-vision" in kodeki && android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTipi,
            eac3 = tv || "audio/eac3" in kodeki || "audio/eac3-joc" in kodeki, ac3 = tv || "audio/ac3" in kodeki,
            dts = kodeki.any { it.startsWith("audio/vnd.dts") }, truehd = "audio/true-hd" in kodeki)
    }

    /**
     * [razlicice]: ista vsebina iz drugih dodatkov (drug id). Tokove vprasamo za vse hkrati in izberemo najboljsega
     * izmed vseh - uporabnik vidi eno kartico in dobi najboljsi tok, ne glede na to, kateri dodatek ga ima (Matej, 2. 10. 2026).
     */
    private fun razresiStremio(sk: Jamendo.Skladba, rocno: Boolean = false, razlicice: List<Jamendo.Skladba> = emptyList()) {
        val (_, tip, id) = Stremio.razstavi(sk) ?: return
        val priprava = zacniPripravo(sk) ?: return
        delavec.execute {
            val razred = Stremio.razred(tip)
            // Album, seznam, podkast ali kanal z videi ima posnetke kot serija epizode; film, TV kanal in postaja imajo tok naravnost.
            val posnetki = if (tip == "series" || razred == Stremio.GLASBA || razred == Stremio.VIDEO)
                (try { Stremio.epizode(sk) } catch (_: Exception) { emptyList() }) else emptyList()
            if (tip == "series" || posnetki.size > 1) {
                glavna.post {
                    koncajPripravo(priprava)
                    if (isFinishing) return@post
                    if (posnetki.isEmpty()) { Toast.makeText(this, R.string.os_stremio_ni_epizod, Toast.LENGTH_LONG).show(); return@post }
                    izberiSezono(sk, posnetki, rocno)
                }
            } else if (posnetki.size == 1) {
                val e = posnetki.first()
                val t = e.tokovi.ifEmpty { try { tokoviVzporedno(tip, e.id) } catch (_: Exception) { emptyList() } }
                glavna.post { koncajPripravo(priprava); if (!isFinishing) izberiTok(sk, sk.naslov, t, rocno) }
            } else {
                val enote = (listOf(tip to id) + razlicice.filter { Stremio.jeEnota(it) }.mapNotNull { r -> Stremio.razstavi(r)?.let { it.second to it.third } }).distinct().take(5)
                val t = if (enote.size == 1) (try { tokoviVzporedno(tip, id) } catch (_: Exception) { emptyList() })
                    else enote.map { (t1, i1) -> iskanjeDelavec.submit<List<Stremio.Tok>> { try { tokoviVzporedno(t1, i1) } catch (_: Exception) { emptyList() } } }
                        .flatMap { f -> try { f.get(15, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { emptyList() } }.distinctBy { it.url }
                glavna.post { koncajPripravo(priprava); if (!isFinishing) izberiTok(sk, sk.naslov, t, rocno) }
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
        val ep = vseEpizode.filter { it.tokovi.isNotEmpty() || Razpolozljivost.stanje(this, Razpolozljivost.kljuc(tip, it.id)) != false }.toMutableList()
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
                fun tokoviZa(x: Stremio.Epizoda) = x.tokovi.ifEmpty { try { tokoviVzporedno(tip, x.id) } catch (_: Exception) { emptyList() } }
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
                        glavna.post { if (!isFinishing) GlasbaStoritev.predvajaj(this, vrsta, 0) }
                        return@execute
                    }
                    glavna.post { if (!isFinishing) izberiTok(sk, "${sk.naslov} · ${ime(e)}", prvi, rocno) { epizodeNi(sk, tip, e, vseEpizode, rocno, edinaSezona) } }
                    return@execute
                }
                val t = tokoviZa(e)
                glavna.post {
                    if (!isFinishing) izberiTok(sk.copy(season = e.sezona, episode = e.epizoda), "${sk.naslov} · ${ime(e)}", t, rocno) { epizodeNi(sk, tip, e, vseEpizode, rocno, edinaSezona) }
                }
            }
        }.show()
        // V ozadju preverimo, katere epizode dodatki res imajo: cesar ni, s seznama sproti izgine (in tok izbrane je ze v predpomnilniku).
        preveriEpizode(tip, ep.toList()) { e ->
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

    private fun torrentSteje() = torrentMeja() > 0L

    /** Zapisi razpolozljivosti so loceni po tem, kaj naprava zmore ([Razpolozljivost.kljuc]); velikost po 256 MB. */
    private fun nacinTorrenta(meja: Long = torrentMeja()) = when (meja) {
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
        val zdaj = nacinTorrenta()
        val prej = torrentPrej
        torrentPrej = zdaj
        if (prej == null) Razpolozljivost.pripravi(this, stremioNaslovi(), zdaj)
        if (prej == null || prej == zdaj) return
        android.util.Log.i("SafeerOsMedia", "torrent steje: $prej -> $zdaj")
        Razpolozljivost.pripravi(this, stremioNaslovi(), zdaj)
        SEZNAMI.remove(VIDEO); SEZNAMI.remove(DOMOV)
        odprtKatalog?.let { o -> o.neznani.clear(); o.vec(0); osveziMrezo(o) }
    }

    private fun kljucRazpolozljivosti(sk: Jamendo.Skladba): String? {
        val (_, tip, id) = Stremio.razstavi(sk) ?: return null
        return if (tip == "movie" || tip == "series") Razpolozljivost.kljuc(tip, id) else null
    }

    /** Film ali serija iz dodatkov, za katero vemo, da je noben dodatek ne predvaja. */
    private fun znanoNiNaVoljo(sk: Jamendo.Skladba): Boolean =
        Stremio.jeEnota(sk) && kljucRazpolozljivosti(sk)?.let { Razpolozljivost.stanje(this, it) == false } == true

    /** Ali za serijo vemo vsaj za eno epizodo, da se da predvajati (potem serija ostane, cetudi ena sezona manjka). */
    private fun imaZnanoEpizodo(sk: Jamendo.Skladba): Boolean =
        kljucRazpolozljivosti(sk)?.let { Razpolozljivost.stanje(this, it) == true } == true

    private fun oznaciNaVoljo(sk: Jamendo.Skladba) {
        kljucRazpolozljivosti(sk)?.let { if (Razpolozljivost.stanje(this, it) != true) Razpolozljivost.zapomni(this, it, true) }
    }

    /**
     * Dotik ni dal toka: vsebino skrijemo takoj (za nekaj minut), za ure pa si "ni na voljo" zapomnimo sele, ko to
     * potrdijo vsi dodatki. Izpad dodatka ali omrezja tako ne skrije naslova, ki je cez minuto spet na voljo.
     */
    private fun skrijInPotrdi(k: String, preveri: (List<String>, Long) -> Boolean?) {
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

    /** Mrezo narisemo znova brez umaknjenih kartic; drsnik in izbira (daljinec) ostaneta, kjer sta bila. */
    private fun osveziMrezo(o: OdprtKatalog) {
        if (odprtKatalog !== o || isFinishing) return
        val y = drsnik.scrollY
        val izbrana = vsebina.findFocus()?.tag
        narisiKatalog(o.naslov)
        drsnik.post {
            drsnik.scrollTo(0, y)
            if (izbrana != null) (vsebina.findViewWithTag<View>(izbrana) ?: vsebina.findViewWithTag<View>("k:0"))?.requestFocus()
        }
    }

    private val preverjanje = Executors.newFixedThreadPool(4)
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
            val vidni = vidniKataloga(o).second.map { it.id }
            val drugace = vidni != o.narisani
            if (drugace || o.caka.get() == 0) android.util.Log.i("SafeerOsMedia", "mreza: vidnih=${vidni.size}, narisanih=${o.narisani.size}, vseh=${o.vsi.size}, caka=${o.caka.get()}, " +
                "nalagam=${o.nalagam}, seKaj=${o.seKaj}, okno=${o.zelja}, proracun=${o.proracun}, preverjenih=${o.preverjenih.get()}, zadetkov=${o.zadetkov.get()}, " +
                "samodejno=${o.samodejno}, strogo=${strogaMreza(o)}, torrent=${nacinTorrenta().ifEmpty { "vse" }}, risem=$drugace")
            // Risanje samo nadaljuje preverjanje (narisiKatalog -> preveriMrezo); brez risanja ga nadaljujemo tu.
            if (drugace) osveziMrezo(o) else if (strogaMreza(o)) preveriMrezo(o, vidniKataloga(o).first)
            poPreverjanju(o)
        }
    }

    private fun smemPreverjati(): Boolean {
        val omrezje = getSystemService(android.net.ConnectivityManager::class.java)
        return omrezje != null && omrezje.activeNetwork != null && !omrezje.isActiveNetworkMetered
    }

    /**
     * Naslov iz dodatkov, za katerega se ne vemo, ali se da predvajati. Kar se je nedavno dalo (zapis je le zastarel),
     * ostane na zaslonu, medtem ko ga preverjamo znova - mreza se po treh dneh ne sprazni.
     */
    private fun nepreverjen(sk: Jamendo.Skladba): Boolean =
        Stremio.jeEnota(sk) && kljucRazpolozljivosti(sk)?.let { Razpolozljivost.stanje(this, it) == null && !Razpolozljivost.nekocNaVoljo(this, it) } == true

    /**
     * Strogi nacin mreze: kartico iz dodatkov pokazemo sele, ko je preverjena (3. 10. 2026: na televizorju se je 122
     * filmov pokazalo in v hipu izginilo - nobenega ni bilo mogoce predvajati). Nic se ne pokaze in spet skrije: mreza
     * se polni sproti, preverjeno ostane v predpomnilniku (naslednjic je polna takoj). Velja, kadar smemo preverjati
     * (neomejeno omrezje); na mobilnih podatkih ostane po starem - pokazemo vse, cesar ne poznamo kot nepredvajljivo.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun strogaMreza(o: OdprtKatalog): Boolean = stremioNaslovi().isNotEmpty() && smemPreverjati()

    /** (kandidati = vse, cesar ne poznamo kot nepredvajljivo; vidni = kar od tega res pokazemo). */
    private fun vidniKataloga(o: OdprtKatalog): Pair<List<Jamendo.Skladba>, List<Jamendo.Skladba>> {
        val torrent = nacinTorrenta()
        torrentPrej = torrent
        Razpolozljivost.pripravi(this, stremioNaslovi(), torrent)
        val kandidati = (if (o.tip.isNotBlank()) razvrsti(VIDEO, filtrirajJezike(VIDEO, o.vsi)) else o.vsi).filterNot { znanoNiNaVoljo(it) }
        // Strogo: samo okno in v njem samo preverjeno - kartice pridejo po vrsti, nic se ne vrine vmes.
        return kandidati to (if (strogaMreza(o)) oknoMreze(o, kandidati).naslovi.filterNot { nepreverjen(it) } else kandidati)
    }

    /**
     * Okno stroge mreze: prvih o.zelja kandidatov po vrsti. Naslova, ki ga zdaj ni mogoce preveriti (vsi njegovi dodatki
     * so v premoru, Stremio.vprasaniVPremoru), ne stejemo - sicer bi okno obstalo na njem in do ostalih ne bi prisli.
     */
    private fun oknoMreze(o: OdprtKatalog, kandidati: List<Jamendo.Skladba>): Okno {
        val dodatki = stremioNaslovi()
        val izhod = ArrayList<Jamendo.Skladba>(o.zelja)
        var premor = 0; var naprej = false
        for (sk in kandidati) {
            if (izhod.size >= o.zelja) { naprej = true; break }
            val k = kljucRazpolozljivosti(sk)
            if (k != null && Razpolozljivost.stanje(this, k) == null && k !in o.neznani && !Razpolozljivost.nekocNaVoljo(this, k)) {
                val r = Stremio.razstavi(sk)
                if (r != null && Stremio.vprasaniVPremoru(dodatki, r.second, r.third)) { premor++; continue }
            }
            izhod += sk
        }
        return Okno(izhod, naprej, premor)
    }

    /** Napis pod naslovom mreze: se dela, dodatek omejuje poizvedbe, ni nicesar ali nic. */
    private fun opisMreze(o: OdprtKatalog, st: StanjeMreze, prazno: Boolean): String {
        val dela = (strogaMreza(o) && (o.caka.get() > 0 || o.nalagam || (st.vOknu && o.proracun > 0))) || ((o.nalagam || !o.popolna) && prazno)
        return when {
            dela -> getString(R.string.os_glasba_nalagam)
            st.premor -> getString(R.string.os_media_dodatek_omejuje)
            prazno -> praznaMreza(o)
            else -> ""
        }
    }

    /** Besedilo prazne mreze: katalog ima naslove, a nobenega ne predvaja noben dodatek - povemo, kaj manjka. */
    private fun praznaMreza(o: OdprtKatalog) =
        getString(if (o.vsi.any { znanoNiNaVoljo(it) }) R.string.os_media_ni_predvajljivih else R.string.os_glasba_prazno)

    /**
     * Ko je mreza preverjena: v strogem nacinu stran, ki ni dala nobene nove kartice, ni odgovor na "Nalozi vec" -
     * sami gremo po naslednjo (najvec tri), potem gumb izgine (naslednje strani bi bile enako prazne).
     */
    private fun poPreverjanju(o: OdprtKatalog) {
        if (odprtKatalog !== o || isFinishing || o.nalagam || o.caka.get() > 0) return
        if (!o.popolna) return
        if (!strogaMreza(o)) {
            if (stanje.text.toString() == getString(R.string.os_glasba_nalagam)) stanje.text = if (o.narisani.isEmpty()) praznaMreza(o) else ""
            return
        }
        val st = stanjeMreze(o, vidniKataloga(o).first)
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
        val vOknu = okno.naslovi.any { sk -> kljucRazpolozljivosti(sk)?.let { Razpolozljivost.stanje(this, it) == null && it !in o.neznani } == true }
        return StanjeMreze(vOknu, okno.vPremoru > 0, okno.naprej)
    }

    /**
     * Uporabnik hoce vec (drsenje proti koncu, Nalozi vec): najprej dokoncamo okno (nov proracun poizvedb), nato ga
     * podaljsamo po ze nalozenih straneh, sele ko teh zmanjka, gremo po naslednjo stran kataloga.
     */
    private fun vecMreze(o: OdprtKatalog, izDrsenja: Boolean = false) {
        if (odprtKatalog !== o || isFinishing || o.nalagam) return
        if (strogaMreza(o)) {
            if (o.caka.get() > 0) return                       // preverjanje se tece: pocakamo na njegov izid
            val zdaj = android.os.SystemClock.uptimeMillis()
            if (izDrsenja && zdaj - o.zadnjicVec < 600) return  // en korak na potezo drsenja
            val st = stanjeMreze(o, vidniKataloga(o).first)
            if (st.vOknu) {
                if (izDrsenja && o.proracun > 0) return
                o.zadnjicVec = zdaj
                o.vec(0)
                stanje.text = getString(R.string.os_glasba_nalagam)
                preveriMrezo(o, vidniKataloga(o).first)
                narociUmiri()
                return
            }
            if (st.naprej) { o.zadnjicVec = zdaj; o.vec(KORAK_KARTIC); osveziMrezo(o); return }
            if (!o.seKaj) return
            o.zadnjicVec = zdaj
            o.vec(KORAK_KARTIC)
        }
        if (o.seKaj) naloziVecKataloga(o.naslov)
    }

    /**
     * Ali je serija ali film na voljo: film vprasamo naravnost, serijo po prvi epizodi. Ena poizvedba na naslov (do
     * 3. 10. 2026 tri na serijo: prva epizoda, prva zadnje sezone, zadnja) - vljudnost do dodatkov, glej Stremio.zeton.
     */
    private fun preveriEnoto(sk: Jamendo.Skladba, naslovi: List<String>, torrent: Long): Boolean? {
        val (_, tip, id) = Stremio.razstavi(sk) ?: return null
        if (tip == "movie") return Stremio.razpolozljivo(naslovi, tip, id, torrent)
        val ep = (try { Stremio.epizode(sk) } catch (_: Exception) { emptyList() }).filter { it.sezona >= 1 }
        if (ep.isEmpty()) return null
        return Stremio.razpolozljivo(naslovi, tip, ep.first().id, torrent)
    }

    /**
     * V ozadju preveri, katere kartice mreze se da predvajati (stiri naslove hkrati, samo na neomejenem omrezju); cesar
     * noben dodatek nima, izgine. Odgovori dodatkov ostanejo v predpomnilniku, zato preverjen film zacne takoj.
     */
    private fun preveriMrezo(o: OdprtKatalog, vsi: List<Jamendo.Skladba>) {
        if (!smemPreverjati()) return
        val naslovi = stremioNaslovi()
        if (naslovi.isEmpty()) return
        // Vljudno do dodatkov (3. 10. 2026 nas je dodatek zavrnil s "Rate limit exceeded"): ne preverimo vsega kataloga,
        // ampak samo okno (prvih o.zelja kandidatov) in najvec o.proracun naslovov do naslednjega uporabnikovega dejanja.
        // Ce prvih 20 ne da nobene kartice, nehamo (naslednji ne bi bili drugacni); uporabnik lahko nadaljuje z Nalozi vec.
        if (o.preverjenih.get() >= 20 && o.zadetkov.get() == 0) o.proracun = 0
        var smem = minOf(o.proracun, 12 - o.caka.get())
        if (smem <= 0) return
        val torrent = torrentMeja()
        for (sk in oknoMreze(o, vsi).naslovi) {
            if (smem <= 0) break
            val k = kljucRazpolozljivosti(sk) ?: continue
            if (Razpolozljivost.stanje(this, k) != null || k in vPreverjanju || k in o.neznani) continue
            if (!vPreverjanju.add(k)) continue
            smem--; o.proracun--
            o.caka.incrementAndGet()
            try {
                preverjanje.execute {
                    try {
                        if (odprtKatalog !== o || isFinishing) return@execute      // mreza je zaprta: dodatkov ne sprasujemo vec
                        val r = try { preveriEnoto(sk, naslovi, torrent) } catch (_: Exception) { null }
                        o.preverjenih.incrementAndGet(); if (r == true) o.zadetkov.incrementAndGet()
                        // Dodatek ni odgovoril (izpad, ustavljena storitev): naslova zdaj ni mogoce predvajati, zato ga
                        // nekaj minut ne kazemo; za ure si "ni na voljo" zapomnimo le ob pravem odgovoru.
                        if (r != null) Razpolozljivost.zapomni(applicationContext, k, r) else { o.neznani.add(k); Razpolozljivost.zacasnoNi(k) }
                    } finally {
                        vPreverjanju.remove(k)
                        o.caka.decrementAndGet()
                        glavna.post { if (odprtKatalog === o && !isFinishing) narociUmiri() }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { vPreverjanju.remove(k); o.caka.decrementAndGet() }
        }
    }

    /**
     * Police (domaca stran, Zate, zadetki): prvih nekaj naslovov iz dodatkov preverimo v ozadju. Zaslona pod prsti ne
     * premikamo - cesar noben dodatek nima, ob naslednjem risanju ne bo vec (do takrat dotik da kratko obvestilo).
     */
    private fun preveriPolico(vsi: List<Jamendo.Skladba>) {
        if (odprtKatalog != null) return        // mrezo preverja preveriMrezo
        val enote = vsi.asSequence().filter { Stremio.jeEnota(it) && kljucRazpolozljivosti(it) != null }.take(12).toList()
        if (enote.isEmpty()) return
        val omrezje = getSystemService(android.net.ConnectivityManager::class.java)
        if (omrezje == null || omrezje.activeNetwork == null || omrezje.isActiveNetworkMetered) return
        val naslovi = stremioNaslovi()
        if (naslovi.isEmpty()) return
        val torrent = torrentMeja()
        torrentPrej = nacinTorrenta(torrent)
        Razpolozljivost.pripravi(this, naslovi, nacinTorrenta(torrent))
        for (sk in enote) {
            val k = kljucRazpolozljivosti(sk) ?: continue
            if (Razpolozljivost.stanje(this, k) != null || !vPreverjanju.add(k)) continue
            try {
                preverjanje.execute {
                    try {
                        if (isFinishing) return@execute
                        val r = try { preveriEnoto(sk, naslovi, torrent) } catch (_: Exception) { null }
                        if (r != null) Razpolozljivost.zapomni(applicationContext, k, r)
                    } finally { vPreverjanju.remove(k) }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { vPreverjanju.remove(k) }
        }
    }

    /** Epizode odprtega seznama preverimo v ozadju; [obNi] (glavna nit): epizode noben dodatek nima. */
    private fun preveriEpizode(tip: String, ep: List<Stremio.Epizoda>, obNi: (Stremio.Epizoda) -> Unit) {
        val naslovi = stremioNaslovi()
        if (naslovi.isEmpty()) return
        val torrent = torrentMeja()
        for (e in ep.take(16)) {
            val k = Razpolozljivost.kljuc(tip, e.id)
            if (e.tokovi.isNotEmpty() || Razpolozljivost.stanje(this, k) != null) continue
            preverjanjeEpizod.execute {
                val r = try { Stremio.razpolozljivo(naslovi, tip, e.id, torrent) } catch (_: Exception) { null }
                if (r != null) Razpolozljivost.zapomni(applicationContext, k, r)
                if (r == false) glavna.post { obNi(e) }
            }
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        zadnjiDotik = android.os.SystemClock.uptimeMillis()
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Film ali epizoda se zacne TAKOJ z najboljsim tokom za to napravo (TokIzbira: zaslon, dekodirniki slike in zvoka,
     * hiter zacetek) - brez seznama in brez cakanja na izbiro (Matej, 2. 10. 2026). Ce tok ne stece, storitev sama
     * poskusi naslednje. Seznam virov ostane rocna moznost (dolg pritisk na kartico > Izberi vir) in izhod v sili,
     * kadar dodatki ponudijo le zunanje povezave (napovednik ipd.).
     */
    private fun izberiTok(sk: Jamendo.Skladba, naslov: String, tokovi: List<Stremio.Tok>, rocno: Boolean = false,
                          /** Kaj storiti, ce se vsebine ne da predvajati (epizoda: nazaj na seznam epizod); privzeto: kartica izgine. */
                          obNeuspehu: (() -> Unit)? = null) {
        // Brez predvajljivega toka ni okna in ni seznama povezav (prosnje za donacijo, Discord, "No streams found" so ze
        // izlocene v Stremio.tok): kratko obvestilo, vsebina pa izgine s seznama (Matej, 2. 10. 2026).
        // Torrent steje le, ce ga ta naprava zmore: prek pomocnika v Linku ali sama, ce datoteka gre na njen prostor.
        val meja = torrentMeja()
        fun gre(t: Stremio.Tok) = t.vrsta != "torrent" || Stremio.torrentGre(t, meja)
        if (tokovi.none { Stremio.jePredvajljiv(it) && gre(it) }) {
            // Dodatek ni odgovoril (omejuje poizvedbe, izpad, omrezje): to ni "ni na voljo". Naslov ostane na zaslonu,
            // uporabnik izve, da naj poskusi znova - prej je film ob vsakem izpadu dodatka "izginil".
            if (Stremio.dodatekNiOdgovoril()) {
                Toast.makeText(this, R.string.os_media_dodatek_ne_odgovarja, Toast.LENGTH_LONG).show()
                return
            }
            Toast.makeText(this, getString(R.string.os_media_ni_na_voljo, naslov), Toast.LENGTH_SHORT).show()
            if (obNeuspehu != null) obNeuspehu() else oznaciNiNaVoljo(sk)
            return
        }
        oznaciNaVoljo(sk)
        // Glasba in radio iz dodatka ostaneta zvok (predvajalnik za zvok, v ozadju), vse ostalo je video.
        fun skladbaToka(t: Stremio.Tok) = sk.copy(id = sk.id + "#" + t.url.hashCode(), naslov = naslov, zvok = t.url, povezava = t.url, video = sk.video)
        fun odpri(t: Stremio.Tok, rezerve: List<Stremio.Tok> = emptyList()) {
            when (t.vrsta) {
                "url" -> {
                    // Glave, ki jih tok zahteva (proxyHeaders), veljajo za gostitelja toka, dokler ne pride drug tok z istega.
                    SpletniVir.zapomniGlaveToka(t.url, t.glave)
                    val r = skladbaToka(t)
                    GlasbaStoritev.predvajajZRezervami(this, r, rezerve.map { skladbaToka(it) to it.glave })
                    if (r.video) { nadaljujKoPripravljen(r); startActivity(Intent(this, PredvajanjeActivity::class.java)) }
                }
                "torrent" -> torrentPrekRacunalnika(sk, naslov, t.url, t.datoteka,
                    velikost = (TokIzbira.opisi(t.ime + " " + t.opis).gb * 1024.0 * 1024.0 * 1024.0).toLong())
                // Napovednik (samo pri rocni izbiri vira): igra v nasem predvajalniku, ne v brskalniku.
                else -> razresiSplet(SpletniVir.enota(t.url, naslov, "", sk.slika, true))
            }
        }
        val opis = { t: Stremio.Tok -> t.ime + " " + t.opis }
        // Rok veljavnosti povezave steje pri vrstnem redu (TokIzbira.veljaSe): potekla povezava je zadnja.
        val zdajS = System.currentTimeMillis() / 1000
        val neposredni = TokIzbira.uredi(tokovi.filter { it.vrsta == "url" }, opis, zmoznostiNaprave, { it.url }, zdajS)
        val torrenti = TokIzbira.uredi(tokovi.filter { it.vrsta == "torrent" && gre(it) }, opis, zmoznostiNaprave)
        if (!rocno) {
            // Povezave, ki je po lastnem zapisu ze potekla, sploh ne poskusamo (streznik bi odgovoril 403 in uporabnik
            // bi cakal na naslednjo) - razen ce so take vse: ura naprave je lahko napacna, zato jih takrat vseeno poskusimo.
            val zivi = neposredni.filter { !TokIzbira.potekla(it.url, zdajS) }.ifEmpty { neposredni }
            android.util.Log.i("SafeerOsMedia", "tokovi: neposredni=${neposredni.size} (poteklih ${neposredni.size - zivi.size}), torrenti=${torrenti.size}, zunanji=${tokovi.size - neposredni.size - torrenti.size}; " +
                "izbran=${(zivi.firstOrNull() ?: torrenti.firstOrNull())?.let { TokIzbira.opisi(opis(it)) }}, rok=${zivi.firstOrNull()?.let { TokIzbira.veljaSe(it.url, zdajS) }}")
            if (zivi.isNotEmpty()) { odpri(zivi.first(), zivi.drop(1).take(REZERVNIH_TOKOV)); return }
            if (torrenti.isNotEmpty()) { odpri(torrenti.first()); return }
        }
        // Rocna izbira: najboljsi na vrhu, napovednik na koncu.
        val urejeni = neposredni + torrenti + tokovi.filter { it.vrsta != "url" && it.vrsta != "torrent" }
        if (urejeni.size == 1 && !rocno) { odpri(urejeni.first()); return }
        val imena = urejeni.map { t ->
            val vrsta = when (t.vrsta) { "torrent" -> " · " + getString(R.string.os_stremio_torrent); "napovednik" -> " · " + getString(R.string.os_stremio_zunanji); else -> "" }
            listOf(t.ime, t.opis.replace('\n', ' ')).filter { it.isNotBlank() }.joinToString(" · ") + vrsta
        }
        AlertDialog.Builder(this).setTitle(naslov).setItems(imena.toTypedArray()) { _, k ->
            val t = urejeni[k]
            // Tudi po rocni izbiri: ce izbrani tok ne stece, gredo za njim ostali neposredni (od najboljsega).
            odpri(t, if (t.vrsta == "url") neposredni.filter { it !== t }.take(REZERVNIH_TOKOV) else emptyList())
        }.show()
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
            link.ukaz(r.id, "magnet.stream", org.json.JSONObject().put("uri", magnet).apply { if (datoteka >= 0) put("file", datoteka); opisVZahtevo(this, opisZa(sk, naslov)) }, 150_000, LinkOdjemalec.Odgovor { izid, napaka ->
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
     * Torrent brez racunalnika v Linku (Matej, 3. 10. 2026): naprava ga predvaja SAMA, ce datoteka gre na njen prosti
     * prostor (zacasno - po 48 urah brez predvajanja izgine sama); sicer prosi drugo napravo s Safeer OS v krogu
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
            link.ukaz(r.id, "magnet.stream", org.json.JSONObject().put("uri", magnet).apply { if (datoteka >= 0) put("file", datoteka); opisVZahtevo(this, opisZa(sk, naslov)) }, 20_000, LinkOdjemalec.Odgovor { izid, napaka ->
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
                if (isFinishing) return@post
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
            if (o != null && o.dodatek.isNotBlank()) odpriRazdelekDodatka(o.dodatek) else if (razdelek == VIDEO && o == null) izberiNaMestu(VIDEO)
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
        // »Obdrži«: prenos ne potece po 48 urah. Oznako hrani naprava, ki film hrani.
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
        if (razdelek == DOMOV) {
            // Plosca se zamenja, ko se predvajanje zacne ali konca; sicer samo osvezimo njene podatke.
            val zeli = if (sk != null && p != null) true else if (MedijskiViri.nedavno(this).isNotEmpty()) false else null
            if (zeli != pZaPredvajanje) izberi(DOMOV) else osveziPlosco()
        }
        vrstica.visibility = if (sk == null || p == null || razdelek == DOMOV) View.GONE else View.VISIBLE
        if (sk == null || p == null) return
        val tokPomocnika = SprotnaPomoc.tokZa(sk)
        if (tokPomocnika != null) { if (tokPomocnika.izvirnik.video) MediaNapredek.zapisi(this, tokPomocnika.izvirnik, tokPomocnika.zamikMs + p.currentPosition.coerceAtLeast(0), tokPomocnika.trajanjeMs, tokPomocnika.streznikIzvirnika?.naprava.orEmpty()) }
        else if (sk.video) MediaNapredek.zapisi(this, sk, p.currentPosition.coerceAtLeast(0), p.duration.coerceAtLeast(0), GlasbaStoritev.streznikTrenutni?.naprava.orEmpty())
        zdajNaslov.text = sk.naslov
        zdajIzvajalec.text = if (SpletniVir.jeEnota(sk)) "" else sk.izvajalec
        zdajCas.text = if (p.isPlaying) "▶" else "❚❚"
        (zdajGumb?.getChildAt(0) as? ImageView)?.setImageResource(if (p.isPlaying) R.drawable.os_ikona_pavza else R.drawable.os_ikona_predvajaj)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val p = GlasbaStoritev.predvajalnik
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { p?.let { if (it.isPlaying) it.pause() else it.play() }; return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p?.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p?.pause(); return true }
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
        /** Koliko skladb seznama predvajanja pokaze polica; ostale odpre kartica "Prikazi vse". */
        private const val NA_POLICI_SEZNAMA = 20
        /** Risanje po korakih: kartic na kos in casovni proracun enega kosa na glavni niti. */
        private const val KARTIC_NA_KORAK = 6
        private const val PRORACUN_MS = 8L
        /** Beseda za iskanje ob odprtju (prazna: samo odpri iskanje), npr. z zaslona predvajanja. */
        const val ISKANJE_BESEDA = "iskanje"
        /** Zavihek spodnje vrstice (StranskaVrstica.Zavihek.name), ki naj se odpre. */
        const val ZAVIHEK = "zavihek"
        /** "Poslji na napravo": uporabnik je v pasici ali obvestilu izbral Sprejmi - Medijski center ponudbo prevzame. */
        const val EXTRA_PREDAJA_SPREJMI = "predaja_sprejmi"
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
        private const val VIR_JAMENDO = "vgrajen:jamendo"
        private const val VIR_RADIO = "vgrajen:radio"
        private const val VIR_TUNEIN = "vgrajen:tunein"
        private const val VIR_PEERTUBE = "vgrajen:peertube"
        private const val VIR_JAVNA_LAST = "vgrajen:javna-last"
        private const val VIR_TV = "vgrajen:tv"
        private const val GLAS = 41
        private const val NASLOV_VRSTE = "naslov-vrste"
        private const val MREZA_VRSTA = "mreza-vrsta"
        private const val POLICA_KARTIC = "polica-kartic"
        /** Kartica v mrezi Mojih virov: dve vrsti gresta na prvi zaslon televizorja. */
        private const val MREZA_DP = 116
        /** Katalog Stremio ima "Pokazi vse"/"Nalozi vec" sele, ko je stran vsaj tako dolga (kratki katalogi so celi na polici). */
        private const val STRAN_KATALOGA_MIN = 20
        /** Racunalniki v Linku, ki so odgovorili, da pretakanja torrentov ne poznajo (do konca zivljenja procesa). */
        private val NE_ZNA_TORRENTA: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        /** Vljudno preverjanje mreze: okno ob odprtju, podaljsanje okna ob vsakem "vec", najvec poizvedb na uporabnikovo dejanje. */
        private const val ZELJA_KARTIC = 30
        private const val KORAK_KARTIC = 30
        private const val PRORACUN_PREVERJANJ = 40
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
        private const val KLJUC_JEZIKOV = "izklopljeni_jeziki_"
        private val HITROSTI = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        private val CASOVNIK = intArrayOf(15, 30, 60, 90)

        /** Seznami razdelkov za cas delovanja aplikacije (ponovna izbira je takojsnja). */
        private val SEZNAMI = HashMap<Int, List<Podatki>>()
        /** Zadnja znana prva stran mreze Filmi | Serije po kljucu (tip, zvrst, jezik): ob ponovnem odprtju takoj na zaslonu. */
        private val BRSKANJE = java.util.concurrent.ConcurrentHashMap<String, List<Jamendo.Skladba>>()
        /** Vse kartice razdelka Video iz zadnjega nalaganja (zdruzene cez vire, neomejene) - osnova mreze Filmi | Serije. */
        @Volatile private var VSE_VIDEO: List<Jamendo.Skladba>? = null
        /** Nastavitve pogleda so zacasne: zivijo samo v pomnilniku procesa, nikoli v SharedPreferences. */
        private val razvrstitve = HashMap<Int, Int>()
        private val zacasnoSkritiViri = HashMap<Int, MutableSet<String>>()
        private val samoTaNaprava = mutableSetOf<Int>()
        private val SLIKE = LruCache<String, android.graphics.Bitmap>(48)

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
    }
}
