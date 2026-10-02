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

class GlasbaActivity : OsActivity() {

    /** Vrsta kartic; [pogled] je posebna vrsta (npr. tvoji viri), ki se narise tako, kot je. */
    private data class Vrsta(val naslov: String, val kartice: List<Kartica>, val video: Boolean = false, val pogled: View? = null, val mala: Boolean = false,
                             val mreza: Boolean = false)
    /** Podatki vrste brez zaslona - samo to gre v predpomnilnik (kartice drzijo zaslon). */
    private data class Podatki(val naslov: String, val skladbe: List<Jamendo.Skladba>, val video: Boolean = false)
    private data class Kartica(val naslov: String, val podnaslov: String, val slika: String, val klik: (View) -> Unit,
                               val dolgo: ((View) -> Unit)? = null, val ikona: Int = R.drawable.os_ikona_glasba,
                               val oznaka: String = "", val kakovost: String = "", val ocena: String = "",
                               val tvId: String = "")

    private val delavec = Executors.newFixedThreadPool(4)
    // Omrezno iskanje ima lasten omejen pool. Prejsnje iskanje preklicemo, da pocasni
    // strezniki ne zasedajo CPU/omrezja se dolgo po novem vnosu.
    // Brez zgornje meje: omrezna zahteva se ob preklicu ne ustavi takoj, novo iskanje pa ne sme cakati v vrsti.
    private val iskanjeDelavec = Executors.newCachedThreadPool()
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
        override fun naStanje(povezan: Boolean, sporocilo: String) { }
        override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) { }
        override fun naNaslov(url: String, naslov: String, od: String) { }
        override fun naBesedilo(besedilo: String, od: String) { }
        override fun naZavrnitev() { }
    }

    override fun onStart() {
        super.onStart()
        Ozadje.uporabi(this, koren)
        if (!link.jeKrajevni()) link.dodaj(linkPoslusalec)
        GlasbaStoritev.poslusalci.add(poslusalec)
        glavna.post(tik)
        // Ob vrnitvi (npr. iz predvajanja) sta se nedavno in stanje predvajanja lahko spremenila.
        if (videnPrej && razdelek == DOMOV) izberi(DOMOV)
        // Po ogledu (Nazaj s predvajanja) osvezimo napredek na kartici videa z naprave.
        // Video: znova narisemo (iz predpomnilnika, brez omrezja), da kartica in "Nadaljuj gledanje" pokazeta novo mesto.
        if (videnPrej && vlc && razdelek == VIDEO) izberi(VIDEO)
        else if (videnPrej && razdelek == GLASBA) naloziKrajevno(razdelek)
        // Dovoljenje, dano v nastavitvah aplikacije: ob vrnitvi takoj pokazemo, kar je na napravi.
        if (vlc && videnPrej && imeloDovoljenje != stanjeDovoljenj()) {
            imeloDovoljenje = stanjeDovoljenj()
            SEZNAMI.remove(VIDEO); SEZNAMI.remove(GLASBA); krajevno.clear()
            if (razdelek == VIDEO || razdelek == GLASBA) izberi(razdelek)
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
        delavec.shutdownNow()
        tvIkone.clear()
        super.onDestroy()
    }

    /** Nazaj iz razdelka vrne na nadzorno plosco; s plosce zapusti Safeer Media. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (odprtKatalog != null) { odprtKatalog = null; izberi(odprtKatalogIz); return }
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
        stanje = besedilo(14f, osBarva(R.color.os_umirjeno)).apply { maxLines = 2 }
        levo.addView(naslov); levo.addView(stanje)
        if (vlc) {
            // Dolgo ime ("Safeer Predvajalnik") se zmanjsa, ne odreze.
            naslov.maxLines = 1
            naslov.setAutoSizeTextTypeUniformWithConfiguration(15, 22, 1, TypedValue.COMPLEX_UNIT_SP)
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
        drsnik = ScrollView(this).apply { addView(vsebina); isFillViewport = true; isVerticalScrollBarEnabled = false }
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
            setOnClickListener { k.klik(it) }
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

    /** Kljuc pogleda s fokusom (kartice in gumbi imajo tag "k:..."), da ga po ponovnem risanju najdemo. */
    private fun kljucFokusa(): String? {
        var v: View? = window.decorView.findFocus()
        while (v != null && v !== vsebina) {
            (v.tag as? String)?.takeIf { it.startsWith("k:") }?.let { return it }
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
    private fun narisi(vrste: List<Vrsta>, opis: String, prazno: String = getString(R.string.os_glasba_prazno), glava: List<View> = emptyList()) {
        val moje = ++risanje
        val kljuc = if (vsebina.hasFocus()) kljucFokusa() else null
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
            if (v.mreza) {
                // Mreza (Moji viri, katalog): toliko kartic v vrsto, kolikor jih gre celih, ostale v naslednjo vrsto.
                // Na ozkem telefonu vsaj dve kartici v vrsto, zato ozje (plakati najmanj 72 dp, druge kartice 88 dp):
                // ena kartica na vrsto je bil seznam z veliko praznega prostora (Matej, Moji viri na telefonu pokonci).
                val sirinaVsebine = vsebina.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels * 3 / 4)
                var velikost = if (v.video) 112 else MREZA_DP
                var n = (sirinaVsebine / dp(velikost + 12 + 14)).coerceAtLeast(1)
                if (n < 2 && sirinaVsebine / dp((if (v.video) 72 else 88) + 12 + 14) >= 2) {
                    n = 2; velikost = (sirinaVsebine / 2 / resources.displayMetrics.density).toInt() - 26
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
        if (kljuc == null) drsnik.scrollTo(0, 0)
        fun koncano() {
            brezOdrezanihVrst()
            drsnik.post {
                if (moje != risanje) return@post
                // "Nadaljuj" se po zacetku predvajanja zamenja s tipkami - izbira gre na predvajaj/pavza.
                val nazaj = kljuc?.let { vsebina.findViewWithTag<View>(it) ?: if (it == "k:nadaljuj") vsebina.findViewWithTag<View>("k:predvajaj") else null }
                when {
                    nazaj != null -> nazaj.requestFocus()
                    fokusVVsebino -> { fokusVVsebino = false; fokusNaPrvo() }
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
                for (i in 0 until vsebina.childCount) {
                    val v = vsebina.getChildAt(i)
                    val jeVrsta = v is HorizontalScrollView || v.tag == MREZA_VRSTA
                    if (v.bottom <= vidno) { if (v.tag == POLICA_KARTIC || v.tag == MREZA_VRSTA) vidnaVrsta = true; continue }
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
        delavec.execute {
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
        delavec.execute {
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

    private fun izberi(i: Int) {
        razdelek = i
        odprtKatalog = null
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
            if (!radioViden && !tuneInViden) return emptyList()
            val (domace, svet) = if (radioViden) Radio.postajeLocene() else emptyList<Jamendo.Skladba>() to emptyList()
            val vrste = mutableListOf<Podatki>()
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
            // Kanali v zivo iz uporabnikovih dodatkov Stremio (katalogi tipa "tv"): vsak katalog svoja polica, pred uradnimi prenosi.
            val stremio = MedijskiViri.vsi(this).filter { it.jeStremio && jeVirViden(i, kljucVira(it)) }.map { it.naslov }
            val izDodatkov = mutableListOf<Podatki>()
            if (stremio.isNotEmpty()) {
                val katalogi = try { Stremio.katalogiTv(stremio) } catch (_: Exception) { emptyList() }.take(12)
                val vsebine = katalogi.map { k -> iskanjeDelavec.submit<List<Jamendo.Skladba>> { try { Stremio.katalog(k) } catch (_: Exception) { emptyList() } } }
                katalogi.zip(vsebine).forEach { (k, f) ->
                    val vsebina = try { f.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { emptyList() }
                    if (vsebina.isNotEmpty()) izDodatkov += Podatki("📡 ${k.ime} · ${k.imeDodatka}", vsebina.take(80), video = true)
                }
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
            val enakFilmom = filmi.isNotEmpty() && zateSeznam.size == filmi.size && zateSeznam.map { it.id } == filmi.map { it.id }
            val enakSerijam = serije.isNotEmpty() && zateSeznam.size == serije.size && zateSeznam.map { it.id } == serije.map { it.id }
            val enakOstalim = ostali.isNotEmpty() && zateSeznam.size == ostali.size && zateSeznam.map { it.id } == ostali.map { it.id }
            if (zateSeznam.isNotEmpty() && (imaZgodovino || (!enakFilmom && !enakSerijam && !enakOstalim))) {
                vrste += Podatki(getString(R.string.os_media_zate), zateSeznam, video = true)
            }
            // Kar racunalniki v Linku hranijo za to napravo (torrenti iz dodatkov): ogled ali odstranitev.
            prenosiNaRacunalnikih().takeIf { it.isNotEmpty() }?.let {
                vrste += Podatki("💻 " + getString(R.string.os_prenosi_racunalnik), it, video = true)
            }
            filmi.takeIf { it.isNotEmpty() }?.let { vrste += Podatki(getString(R.string.os_media_filmi), it.take(POLICA_NAJVEC), video = true) }
            serije.takeIf { it.isNotEmpty() }?.let { vrste += Podatki(getString(R.string.os_media_serije), it.take(POLICA_NAJVEC), video = true) }
            if (ostali.isNotEmpty() && (filmi.isEmpty() || ostali.size >= MIN_KARTIC_KATEGORIJE)) {
                vrste += Podatki(getString(R.string.os_glasba_video), ostali.take(POLICA_NAJVEC), video = true)
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

    private fun vVrste(p: List<Podatki>) = p.mapNotNull { d ->
        val kartice = if (d.video) videi(d.skladbe, d.naslov) else skladbe(d.skladbe, d.naslov)
        // Polica kataloga Stremio: na koncu "Pokazi vse" - mreza z vsemi stranmi kataloga (skip), kot Discover v Stremiu.
        val zVsemi = if (d.video && jePolicaKataloga(d.naslov) && d.skladbe.size >= STRAN_KATALOGA_MIN) kartice + pokaziVseKartica(d.naslov, d.skladbe) else kartice
        zVsemi.takeIf { it.isNotEmpty() }?.let { Vrsta(d.naslov, it, d.video) }
    }

    // ------------------------------------------------------------------ katalog Stremio (Pokazi vse, strani)

    /** Odprta polica (Pokazi vse): katalogi, ki jo polnijo, do zdaj nalozeni vnosi (zdruzeni), koliko jih je dal vsak katalog in ali je se kaj. */
    private class OdprtKatalog(val katalogi: List<Stremio.Katalog>, val vsi: MutableList<Jamendo.Skladba>,
                               val preneseno: HashMap<Stremio.Katalog, Int>, var seKaj: Boolean)
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
        Kartica(getString(R.string.os_media_pokazi_vse), naslov.substringAfter(" · "), "", { odpriKatalog(naslov, prvaStran) }, ikona = R.drawable.os_ikona_mreza)

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
                odprtKatalog = OdprtKatalog(katalogi, vsi, preneseno, strani.any { it.size >= STRAN_KATALOGA_MIN })
                odprtKatalogIz = iz
                narisiKatalog(naslov)
                fokusNaPrvo()
            }
        }
    }

    private fun narisiKatalog(naslov: String) {
        val o = odprtKatalog ?: return
        val vsi = o.vsi
        val kartice = videi(vsi, naslov) + (if (o.seKaj) listOf(Kartica(getString(R.string.os_media_nalozi_vec), vsi.size.toString(), "",
            { naloziVecKataloga(naslov) }, ikona = R.drawable.os_ikona_plus)) else emptyList())
        narisi(listOf(Vrsta(naslov, kartice, video = true, mreza = true)), vsi.size.toString())
    }

    private fun naloziVecKataloga(naslov: String) {
        val o = odprtKatalog ?: return
        val vsi = o.vsi
        stanje.text = getString(R.string.os_glasba_nalagam)
        delavec.execute {
            // Naslednja stran vsakega kataloga (skip = kolikor je ta katalog ze dal), vse hkrati.
            val katalogi = o.katalogi.filter { (o.preneseno[it] ?: 0) > 0 }
            val strani = katalogi.map { k -> iskanjeDelavec.submit<List<Jamendo.Skladba>> { try { Stremio.katalog(k, skip = o.preneseno[k] ?: 0) } catch (_: Exception) { emptyList() } } }
                .map { f -> try { f.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { emptyList() } }
            val nove = prepleti(strani)
            glavna.post {
                if (isFinishing || odprtKatalog !== o) return@post
                katalogi.zip(strani).forEach { (k, v) -> o.preneseno[k] = (o.preneseno[k] ?: 0) + v.size }
                val znani = vsi.map { it.id }.toSet()
                // Nov vnos, ki je ista vsebina kot ze prikazana kartica, se vanjo zdruzi (ni nove kartice).
                val sveze = SpletniVir.zdruziEnako(vsi + nove.filter { it.id !in znani }).filter { g -> g.none { it.id in znani } }.map { it.first() }
                vsi += sveze
                o.seKaj = sveze.isNotEmpty() && strani.any { it.size >= STRAN_KATALOGA_MIN }
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
                if (razdelek != i) return@post
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
            Vrsta("≡  " + sz.ime, if (video) videi(urejene, sz.ime, sz) else skladbe(urejene, sz.ime, sz), video) }
        return when (i) {
            DOMOV -> listOf(
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
        VIDEO -> listOfNotNull(dovoljenjeKartica(i), videoKategorije())
        GLASBA -> listOfNotNull(dovoljenjeKartica(i), skokNaPolico(podatki.map { it.naslov }).takeIf { podatki.size > 1 })
        RADIO, TV_V_ZIVO -> listOfNotNull(skokNaPolico(podatki.map { it.naslov }).takeIf { podatki.size > 1 })
        else -> emptyList()
    } else when (i) {
        DOMOV -> listOfNotNull(kategorije(), razvrstiInFiltrirajGumb(i), zdajPlosca())
        VIDEO -> listOf(razdelkiVrstica(i), videoKategorije(), razvrstiInFiltrirajGumb(i))
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

    /** Hitra zgornja navigacija video kataloga; skoci neposredno na polico. */
    private fun videoKategorije(): View {
        val niz = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), dp(16), dp(8)) }
        val kategorije = listOf(
            "🔥 " + getString(R.string.os_media_zate) to getString(R.string.os_media_zate),
            "🎬 " + getString(R.string.os_media_filmi) to getString(R.string.os_media_filmi),
            "📺 " + getString(R.string.os_media_serije) to getString(R.string.os_media_serije)
        )
        kategorije.forEachIndexed { i, (napis, cilj) ->
            niz.addView(besedilo(14f, osBarva(R.color.os_besedilo), true).apply {
                text = napis; isFocusable = true; isClickable = true
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
        return HorizontalScrollView(this).apply { addView(niz); isHorizontalScrollBarEnabled = false; clipToPadding = false }
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
        desno.addView(besedilo(11f, osBarva(R.color.os_mint), true).apply { text = oznaka.uppercase(Locale.getDefault()); letterSpacing = 0.08f })
        pIzvajalec = besedilo(if (jeSirokTv()) 15f else 14f, osBarva(R.color.os_umirjeno)).also { desno.addView(it) }
        pNaslov = besedilo(if (jeSirokTv()) 24f else 21f, beli, true).also { desno.addView(it) }
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
            tipke.addView(gumb(R.drawable.os_ikona_prejsnja, 36, "k:prejsnja") { pl?.seekToPreviousMediaItem() })
            tipke.addView(gumb(R.drawable.os_ikona_predvajaj, 48, "k:predvajaj") { pl?.let { if (it.isPlaying) it.pause() else it.play() }; osveziZdaj() }
                .also { pPredvajaj = it.getChildAt(0) as ImageView })
            tipke.addView(gumb(R.drawable.os_ikona_naslednja, 36, "k:naslednja") { pl?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } })
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
                    v.jeKodi -> getString(R.string.os_mediji_dodatek_kodi) + " · " + v.naslov.removePrefix("https://").removePrefix("http://")
                    v.tip == MedijskiViri.API -> "API · " + (try { java.net.URL(v.naslov.substringBefore('|').trim()).host } catch (_: Exception) { "" })
                    else -> v.naslov.removePrefix("https://").removePrefix("http://")
                }, {
                    when {
                        v.jePeerTube -> { fokusVVsebino = true; izberi(VIDEO) }
                        // Dodatek: pokazemo shranjeni naslov (predvajanje prek dodatkov je naslednji korak).
                        v.jeStremio -> { skociNaDodatek = v.naslov; fokusVVsebino = true; SEZNAMI.remove(VIDEO); izberi(VIDEO) }
                        v.jeKodi -> kodiPojasnilo(v)
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
        val vrstaSeznamov = if (!vlc || seznami.isEmpty()) emptyList() else listOf(Vrsta(getString(R.string.os_seznami_predvajanja),
            seznami.map { sz ->
                Kartica(sz.ime, resources.getQuantityString(R.plurals.os_stevilo_posnetkov, sz.skladbe.size, sz.skladbe.size), sz.skladbe.firstOrNull { it.slika.startsWith("http") }?.slika.orEmpty(),
                    { odpriSeznam(sz.ime, "") { sz.skladbe } }, { meniSeznama(sz) },
                    ikona = if (sz.skladbe.all { it.video }) R.drawable.os_ikona_video else R.drawable.os_ikona_glasba)
            }, mala = true))
        return vrstaSeznamov + listOf(Vrsta("", listOf(
            Kartica(getString(R.string.os_mediji_dodaj), getString(R.string.os_mediji_dodaj_opis), "", { dodajVir() }, ikona = R.drawable.os_ikona_plus),
            Kartica(getString(R.string.os_mediji_dodatki), getString(R.string.os_mediji_dodatki_opis), "", { dodajDodatke() }, ikona = R.drawable.os_ikona_plus)) +
            vsiViri().map { v -> Kartica((if (v.kljuc in pripeti) "★ " else "") + v.ime, v.opis, "", { v.odpri() }, { dolgoNaViru(v) }, ikona = v.ikona) },
            mreza = true))
    }

    /** Dolg dotik na seznamu v Brskaj: predvajaj ali odstrani (s potrditvijo). */
    private fun meniSeznama(sz: MedijskiViri.Seznam) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(sz.ime)
            .setItems(arrayOf(getString(R.string.os_mediji_predvajaj), getString(R.string.os_mediji_odstrani_seznam))) { _, k ->
                if (k == 0) predvajaj(sz.skladbe, 0)
                else AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(sz.ime)
                    .setMessage(R.string.os_mediji_odstrani_seznam)
                    .setPositiveButton(R.string.os_mediji_odstrani_seznam) { _, _ -> MedijskiViri.odstraniSeznam(this, sz.ime); izberi(VIRI) }
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
            getString(R.string.os_media_izbrisi_vir) to { odstraniVir(dodan) })
        pokaziBrisanje(AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(v.ime)
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(getString(R.string.os_preklici), null)
        )
    }

    private fun izmenicno(seznami: List<List<Jamendo.Skladba>>) =
        (0 until (seznami.maxOfOrNull { it.size } ?: 0)).flatMap { i -> seznami.mapNotNull { it.getOrNull(i) } }

    /** Kartice skladb vrste; zadrzan OK odpre meni (priljubljeno, shrani vrsto kot seznam). */
    private fun skladbe(s: List<Jamendo.Skladba>, vrsta: String = "", seznam: MedijskiViri.Seznam? = null) = s.map { sk ->
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
                if (SpletniVir.jeEnota(sk)) razresiSplet(sk)
                else s.filterNot { it.mime == MedijskiViri.STRAN }.let { p -> predvajaj(p, p.indexOf(sk)) }
            }, { meni(sk, s, vrsta, seznam) }, ikona = ikona, oznaka = oznaka,
                tvId = sk.id.removePrefix("tv:").takeIf { sk.id.startsWith("tv:") }.orEmpty())
        }
    }

    /** Spletno stran odpre brskalnik Safeer (predvaja vse); nasa glasba se ustavi, zvok strani ob Domov igra naprej. */
    private fun odpriStran(url: String, ime: String) {
        GlasbaStoritev.predvajalnik?.pause()
        startActivity(Brskalnik.medijskaStran(this, url, ime))
    }

    /** Isti naslov iz vec virov je ena kartica; Safeer sam izbere vir in ga uporabniku ne izpostavlja. */
    private fun videi(v: List<Jamendo.Skladba>, vrsta: String = "", seznam: MedijskiViri.Seznam? = null): List<Kartica> {
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
            val vZivo = tvId.isNotBlank() || sk.id.startsWith("stremio|tv|")
            // Video z naprave: napredek ali trajanje (kot VLC), spletni: letnica.
            val podnaslov = if (sk.id.startsWith("krajevno:") || sk.id.startsWith(PREDPONA_PC_PRENOSA))
                napredekKrajevnih[sk.id]?.let { n -> "${cas(n.polozaj)} / ${cas(n.trajanje)}" } ?: sk.izvajalec
            else sk.year.takeIf { it > 0 }?.toString().orEmpty()
            val tip = if (vZivo) getString(R.string.os_media_oznaka_v_zivo) else {
                when (SpletniVir.vrstaVsebine(sk)) {
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
                if (SpletniVir.jeEnota(sk)) razresiSplet(sk, urejene.drop(1)) else predvajaj(listOf(sk), 0)
            }, { meni(sk, v, vrsta, seznam) }, oznaka = tip, kakovost = kakovost,
                ocena = sk.rating.takeIf { it > 0.0 }?.let { String.format(Locale.ROOT, "%.1f", it) }.orEmpty(),
                ikona = if (vZivo) R.drawable.os_ikona_tv else if (sk.radio) R.drawable.os_ikona_radio else R.drawable.os_ikona_video,
                tvId = tvId)
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
        if (seznam != null) dejanja += getString(R.string.os_mediji_odstrani_seznam) to {
            MedijskiViri.odstraniSeznam(this, seznam.ime); osveziPriljubljene()
        } else if (vrsta.count { MedijskiViri.shranljiva(it) } > 1) dejanja += getString(R.string.os_mediji_shrani_seznam) to {
            val sz = MedijskiViri.shraniSeznam(this, ime.ifBlank { sk.izvajalec.ifBlank { getString(R.string.os_mediji_moja_vrsta) } }, vrsta)
            if (sz != null) Toast.makeText(this, getString(R.string.os_mediji_seznam_shranjen, sz.ime), Toast.LENGTH_SHORT).show()
            osveziPriljubljene()
        }
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
    private fun osveziPriljubljene() { if (razdelek != VIRI && razdelek != ISKANJE) izberi(razdelek) }

    // ------------------------------------------------------------------ iskanje

    /** Zadnji zadetki in beseda: ob vrnitvi na Iskanje so se tam. */
    private var zadetki: List<Vrsta>? = null
    private var zadnjaBeseda = ""
    private var opisZadetkov = ""

    private fun novIskalnik() = EditText(this).apply {
        id = View.generateViewId()
        hint = getString(R.string.os_glasba_isci_namig)
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
    private fun odpriIskanje(beseda: String) {
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
        MedijskiViri.zapomniIskanje(this, beseda)
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
        val rezultati = arrayOfNulls<Any>(12)
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
            { try { Stremio.isci(Stremio.zKatalogom(stremioNaslovi), beseda) } catch (_: Exception) { emptyList<Jamendo.Skladba>() } },
        )
        val futures = opravila.mapIndexed { i, f -> iskanjeDelavec.submit { rezultati[i] = f() } }
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
            @Suppress("UNCHECKED_CAST") val izDodatkov = rezultati[11] as? List<Jamendo.Skladba> ?: emptyList()
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
            izDodatkov.forEach { vsi.add(Relevantnost.Zadetek(it, it.naslov, "", getString(R.string.os_stremio_dodatki), 2)) }
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
                    Vrsta("🎬 " + getString(R.string.os_media_filmi), videi(ostali(izDodatkov.filterNot { Stremio.jeSerija(it) }), beseda), video = true),
                    Vrsta("📺 " + getString(R.string.os_media_serije), videi(ostali(izDodatkov.filter { Stremio.jeSerija(it) }), beseda), video = true),
                    Vrsta(getString(R.string.os_mediji_glasba), skladbe(ostali(glasba + spletAvdio + spletVideospoti), beseda)),
                    Vrsta(getString(R.string.os_glasba_video), videi(ostali(videi + javnaLast + spletFilmiInSerije), beseda), video = true),
                    Vrsta(getString(R.string.os_mediji_izvajalci), izvajalci.map { iz ->
                        Kartica(iz.ime, getString(R.string.os_glasba_izvajalec), iz.slika, { odpriIzvajalca(iz) }) }),
                    Vrsta(getString(R.string.os_mediji_postaje), skladbe(ostali(postaje + tuneIn), beseda)),
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
        }.start()
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
    private fun fokusNaPrvo() {
        for (i in 0 until vsebina.childCount) {
            val v = vsebina.getChildAt(i)
            val vrsta = (if (v.tag == MREZA_VRSTA) v else (v as? HorizontalScrollView)?.getChildAt(0)) as? LinearLayout ?: continue
            vrsta.getChildAt(0)?.requestFocus()
            return
        }
    }

    /** Seznam iz vira (epizode podkasta, dodani .m3u): prikaz kot vrsta, uporabnik izbere, kaj predvaja. */
    private fun odpriSeznam(ime: String, opis: String, nalozi: () -> List<Jamendo.Skladba>) {
        stanje.text = getString(R.string.os_glasba_nalagam)
        delavec.execute {
            val s = try { nalozi() } catch (_: Exception) { emptyList() }
            glavna.post {
                if (isFinishing) return@post
                if (s.isEmpty()) { stanje.text = getString(R.string.os_glasba_napaka); return@post }
                narisi(listOf(Vrsta(ime, skladbe(s, ime))), opis)
                fokusNaPrvo()
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

    private fun dodajVNozadju(vnos: String, ime: String?) {
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
     * Dodatki (Stremio, Kodi): dve jasno oznaceni polji, kamor uporabnik vnese naslove SVOJIH dodatkov.
     * Safeer ne prilaga nobenega kataloga ali dodatka; shrani se le, kar je vneseno in preverjeno.
     */
    private fun dodajDodatke() {
        fun polje(namig: Int) = EditText(this).apply {
            hint = getString(namig); setSingleLine(); inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        fun oznaka(besedilo: Int) = TextView(this).apply {
            text = getString(besedilo); setTextColor(osBarva(R.color.os_besedilo)); textSize = 14f; setPadding(0, dp(10), 0, dp(2))
        }
        val stremio = polje(R.string.os_mediji_dodatki_stremio_namig)
        val kodi = polje(R.string.os_mediji_dodatki_kodi_namig)
        // Z daljincem je tipkanje naslova mucno: gumb Prilepi vzame naslov iz odlozisca (kopiran v Spletu
        // ali poslan z druge naprave), namig pa pove, da dodatek doda ze gumb Namesti na njegovi strani.
        fun vrsticaZGumbom(polje: EditText) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(polje, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(android.widget.Button(this@GlasbaActivity).apply {
                text = getString(R.string.os_mediji_dodatki_prilepi); isAllCaps = false
                setOnClickListener {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    val b = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@GlasbaActivity)?.toString()?.trim().orEmpty()
                    if (b.isBlank()) Toast.makeText(this@GlasbaActivity, R.string.os_mediji_dodatki_prilepi_prazno, Toast.LENGTH_LONG).show()
                    else { polje.setText(b); polje.setSelection(b.length) }
                }
            })
        }
        val vsebina = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0)
            addView(TextView(this@GlasbaActivity).apply {
                text = getString(R.string.os_mediji_dodatki_namig_splet); setTextColor(osBarva(R.color.os_mint)); textSize = 13f; setPadding(0, dp(4), 0, dp(6))
            })
            addView(oznaka(R.string.os_mediji_dodatki_stremio)); addView(vrsticaZGumbom(stremio))
            addView(oznaka(R.string.os_mediji_dodatki_kodi)); addView(vrsticaZGumbom(kodi))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.os_mediji_dodatki)
            .setMessage(R.string.os_mediji_dodatki_razlaga)
            .setView(ScrollView(this).apply { addView(vsebina) })
            .setPositiveButton(R.string.os_mediji_dodatki_shrani) { _, _ ->
                var shranjeno = 0
                for ((tip, vnos) in listOf(MedijskiViri.STREMIO to stremio.text.toString(), MedijskiViri.KODI to kodi.text.toString())) {
                    if (vnos.isBlank()) continue
                    val (naslov, napaka) = MedijskiViri.preveriDodatek(tip, vnos)
                    if (naslov == null) {
                        Toast.makeText(this, getString(if (napaka == "stremio") R.string.os_mediji_dodatki_napaka_stremio else R.string.os_mediji_dodatki_napaka_naslov), Toast.LENGTH_LONG).show()
                        continue
                    }
                    val v = MedijskiViri.dodajDodatek(this, tip, naslov, "")
                    Toast.makeText(this, getString(R.string.os_mediji_dodatki_shranjen, v.ime), Toast.LENGTH_SHORT).show()
                    shranjeno++
                }
                if (shranjeno > 0) { SEZNAMI.remove(DOMOV); SEZNAMI.remove(VIDEO); SEZNAMI.remove(TV_V_ZIVO); izberi(VIRI) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun odstraniVir(v: MedijskiViri.Vir) {
        pokaziBrisanje(AlertDialog.Builder(this)
            .setTitle(v.ime)
            .setMessage(R.string.os_mediji_odstrani_vprasanje)
            .setPositiveButton(R.string.os_mediji_odstrani) { _, _ ->
                MedijskiViri.odstrani(this, v)
                SEZNAMI.remove(DOMOV); SEZNAMI.remove(VIDEO); SEZNAMI.remove(TV_V_ZIVO)
                izberi(VIRI)
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

    private fun nadaljujKoPripravljen(sk: Jamendo.Skladba) {
        val od = MediaNapredek.polozaj(this, sk)
        if (od <= 0) return
        Toast.makeText(this, getString(R.string.os_nadaljujem_od, cas(od)), Toast.LENGTH_SHORT).show()
        glavna.postDelayed({
            GlasbaStoritev.predvajalnik?.let { p ->
                // Sprotni tok pomocnika je ze zacel pri shranjenem mestu (SprotnaPomoc): skok bi ga le pokvaril.
                if (GlasbaStoritev.trenutna()?.let { SprotnaPomoc.tokZa(it) } != null) return@let
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

    /**
     * Dodatki Kodi so programi v Pythonu za aplikacijo Kodi - Safeer jih ne more zagnati (in jih ne bo
     * posnemal). Povemo odkrito; ce je Kodi namescen, ga odpremo.
     */
    private fun kodiPojasnilo(v: MedijskiViri.Vir) {
        val kodi = listOf("org.xbmc.kodi", "org.xbmc.kodi.beta").firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
        val b = AlertDialog.Builder(this).setTitle(v.ime).setMessage(getString(R.string.os_kodi_pojasnilo, v.naslov))
        if (kodi != null) b.setPositiveButton(R.string.os_kodi_odpri) { _, _ -> try { startActivity(kodi) } catch (_: Exception) { } }
            .setNegativeButton(android.R.string.cancel, null)
        else b.setPositiveButton(android.R.string.ok, null)
        b.show()
    }

    private fun naslovKataloga(k: Stremio.Katalog): String {
        val vrsta = if (k.tip == "series") "📺 " + getString(R.string.os_media_serije) else "🎬 " + getString(R.string.os_media_filmi)
        // Katalog z obvezno zvrstjo pokazemo s prvo moznostjo (kot Stremio): ime zvrsti v naslovu police.
        val zvrst = k.privzeti.firstOrNull { it.first == "genre" }?.second?.let { " · $it" }.orEmpty()
        return "$vrsta · ${k.ime}$zvrst · ${k.imeDodatka}"
    }

    private fun stremioNaslovi() = MedijskiViri.vsi(this).filter { it.jeStremio }.map { it.naslov }

    private fun razresiStremio(sk: Jamendo.Skladba) {
        val (_, tip, id) = Stremio.razstavi(sk) ?: return
        val priprava = zacniPripravo(sk) ?: return
        delavec.execute {
            if (tip == "series") {
                val ep = try { Stremio.epizode(sk) } catch (_: Exception) { emptyList() }
                glavna.post {
                    koncajPripravo(priprava)
                    if (isFinishing) return@post
                    if (ep.isEmpty()) { Toast.makeText(this, R.string.os_stremio_ni_epizod, Toast.LENGTH_LONG).show(); return@post }
                    izberiSezono(sk, ep)
                }
            } else {
                val t = try { Stremio.tokovi(stremioNaslovi(), tip, id) } catch (_: Exception) { emptyList() }
                glavna.post { koncajPripravo(priprava); if (!isFinishing) izberiTok(sk, sk.naslov, t) }
            }
        }
    }

    private fun izberiSezono(sk: Jamendo.Skladba, ep: List<Stremio.Epizoda>) {
        val sezone = ep.map { it.sezona }.distinct()
        if (sezone.size == 1) { izberiEpizodo(sk, ep); return }
        val imena = sezone.map { z -> val n = ep.count { it.sezona == z }
            getString(R.string.os_stremio_sezona, z) + " · " + resources.getQuantityString(R.plurals.os_stremio_epizod, n, n) }
        AlertDialog.Builder(this).setTitle(sk.naslov).setItems(imena.toTypedArray()) { _, k -> izberiEpizodo(sk, ep.filter { it.sezona == sezone[k] }) }.show()
    }

    private fun izberiEpizodo(sk: Jamendo.Skladba, ep: List<Stremio.Epizoda>) {
        val imena = ep.map { e -> "S${e.sezona}E${e.epizoda}" + (if (e.ime.isNotBlank()) " · " + e.ime else "") }
        AlertDialog.Builder(this).setTitle(sk.naslov).setItems(imena.toTypedArray()) { _, k ->
            val e = ep[k]
            Toast.makeText(this, getString(R.string.os_media_pripravljam, imena[k]), Toast.LENGTH_SHORT).show()
            delavec.execute {
                val t = try { Stremio.tokovi(stremioNaslovi(), "series", e.id) } catch (_: Exception) { emptyList() }
                glavna.post { if (!isFinishing) izberiTok(sk.copy(season = e.sezona, episode = e.epizoda), "${sk.naslov} · ${imena[k]}", t) }
            }
        }.show()
    }

    /** En tok: takoj; vec: izbira (ime dodatka in opis toka, kot v Stremiu); nic: jasno sporocilo. */
    private fun izberiTok(sk: Jamendo.Skladba, naslov: String, tokovi: List<Stremio.Tok>) {
        if (tokovi.isEmpty()) {
            AlertDialog.Builder(this).setTitle(naslov).setMessage(R.string.os_stremio_ni_tokov).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        fun odpri(t: Stremio.Tok) {
            when (t.vrsta) {
                "url" -> {
                    // Glave, ki jih tok zahteva (proxyHeaders), veljajo za gostitelja toka, dokler ne pride drug tok z istega.
                    SpletniVir.zapomniGlaveToka(t.url, t.glave)
                    val r = sk.copy(id = sk.id + "#" + t.url.hashCode(), naslov = naslov, zvok = t.url, povezava = t.url, video = true)
                    GlasbaStoritev.predvajaj(this, listOf(r), 0)
                    nadaljujKoPripravljen(r)
                    startActivity(Intent(this, PredvajanjeActivity::class.java))
                }
                "torrent" -> torrentPrekRacunalnika(sk, naslov, t.url, t.datoteka)
                else -> odpriStran(t.url, naslov)
            }
        }
        if (tokovi.size == 1) { odpri(tokovi.first()); return }
        val imena = tokovi.map { t ->
            val vrsta = when (t.vrsta) { "torrent" -> " · " + getString(R.string.os_stremio_torrent); "zunanji" -> " · " + getString(R.string.os_stremio_zunanji); else -> "" }
            listOf(t.ime, t.opis.replace('\n', ' ')).filter { it.isNotBlank() }.joinToString(" · ") + vrsta
        }
        AlertDialog.Builder(this).setTitle(naslov).setItems(imena.toTypedArray()) { _, k -> odpri(tokovi[k]) }.show()
    }

    /**
     * Zakon solidarnosti v Linku: torrent prenasa in pretaka racunalnik (Safeer Control, `magnet.stream`),
     * ta naprava dobi le sproten tok kot pri spletnem videu - nic se ne prenasa in ne shranjuje nanjo.
     * Brez racunalnika televizor torrenta ne prenasa; telefon in tablica ga lahko, ce uporabnik izbere.
     */
    private fun torrentPrekRacunalnika(sk: Jamendo.Skladba, naslov: String, magnet: String, datoteka: Int = -1, prednost: String = "") {
        val vsi = racunalnikiZaPomoc()
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
                val izbran = vsi.firstOrNull { it.id in zeIma } ?: vsi.maxByOrNull { proste[it.id] ?: 0.0 } ?: vsi.first()
                torrentPrekRacunalnika(sk, naslov, magnet, datoteka, izbran.id)
            }
            for (r in vsi) {
                link.ukaz(r.id, "host.info", org.json.JSONObject(), 2_500, LinkOdjemalec.Odgovor { izid, _ ->
                    izid?.optJSONObject("data")?.let { proste[r.id] = prostaMoc(it) }
                    koncano()
                })
                link.ukaz(r.id, "magnet.list", org.json.JSONObject(), 2_500, LinkOdjemalec.Odgovor { izid, _ ->
                    val a = izid?.optJSONObject("data")?.optJSONArray("items")
                    for (i in 0 until (a?.length() ?: 0)) if (hash != null && btih(a!!.optJSONObject(i)?.optString("magnet").orEmpty()) == hash) zeIma += r.id
                    koncano()
                })
            }
            return
        }
        val racunalniki = vsi
        fun brez(sporocilo: String) {
            val d = AlertDialog.Builder(this).setTitle(naslov).setMessage(sporocilo).setPositiveButton(android.R.string.ok, null)
            if (!jeTv()) d.setNeutralButton(R.string.os_stremio_prenesi_sem) { _, _ ->
                startActivity(Intent(this, MagnetActivity::class.java).putExtra(MagnetActivity.EXTRA_URI, magnet))
            }
            d.show()
        }
        if (racunalniki.isEmpty()) { brez(getString(R.string.os_stremio_torrent_brez_racunalnika)); return }
        var zadnjaNapaka = ""
        fun poskusi(k: Int) {
            if (k >= racunalniki.size) {
                brez(when (zadnjaNapaka) {
                    "preobremenjen", "malo_pomnilnika", "baterija", "varcevanje", "pregreto" -> getString(R.string.os_stremio_racunalnik_zaseden)
                    "ni_prostora" -> getString(R.string.os_stremio_racunalnik_ni_prostora)
                    else -> getString(R.string.os_stremio_torrent_napaka, zadnjaNapaka)
                })
                return
            }
            val r = racunalniki[k]
            val ime = DatotekeActivity.lepoIme(r.ime).ifBlank { r.id }
            Toast.makeText(this, getString(R.string.os_stremio_racunalnik_pripravlja, ime), Toast.LENGTH_LONG).show()
            link.ukaz(r.id, "magnet.stream", org.json.JSONObject().put("uri", magnet).apply { if (datoteka >= 0) put("file", datoteka) }, 150_000, LinkOdjemalec.Odgovor { izid, napaka ->
                if (isFinishing) return@Odgovor
                val podatki = izid?.takeIf { it.optBoolean("ok") }?.optJSONObject("data")
                val srv = podatki?.optJSONObject("server")
                if (podatki == null || srv == null) {
                    zadnjaNapaka = izid?.optString("code")?.ifBlank { null } ?: izid?.optString("message") ?: napaka
                    poskusi(k + 1)
                    return@Odgovor
                }
                val s = DatotekeActivity.Streznik(srv.optString("base_url").trimEnd('/'), srv.optString("fp"), srv.optString("token"), r.id)
                val url = s.osnova + podatki.optString("path")
                val pr = sk.copy(id = sk.id + "#t" + magnet.hashCode(), naslov = naslov, zvok = url, povezava = url, video = true)
                SEZNAMI.remove(VIDEO)   // polica "Prenosi na racunalniku" se osvezi
                // Plakat in ime iz dodatka si zapomnimo, da je kartica prenosa na racunalniku prepoznavna.
                btih(magnet)?.let { h -> getSharedPreferences(PREFS_PC_PRENOSI, MODE_PRIVATE).edit().putString(h, sk.slika).apply() }
                GlasbaStoritev.predvajaj(this, listOf(pr), 0, s)
                nadaljujKoPripravljen(pr)
                startActivity(Intent(this, PredvajanjeActivity::class.java))
            })
        }
        poskusi(0)
    }

    /**
     * Racunalniki v Linku, ki pomagajo sibkejsim napravam (Safeer Control z datotekami): namizje, ne telefon/TV.
     * Brez jeTaNaprava(): ta po naslovu v omrezju lahko izloci racunalnik, kadar sredisce tece na njem.
     */
    private fun racunalnikiZaPomoc(): List<LinkOdjemalec.Naprava> =
        if (link.jeKrajevni() || !link.povezan) emptyList()
        else link.naprave.filter { n -> n.id != Identiteta.id(this) && "files" in n.zmoznosti &&
            ("desktop" in n.zmoznosti || n.platforma in setOf("linux", "windows", "macos")) }

    /** Prenosi, ki jih racunalniki (Safeer Control, `magnet.list`) hranijo za naprave. Klic iz delovne niti. */
    private fun prenosiNaRacunalnikih(): List<Jamendo.Skladba> {
        val racunalniki = try { racunalnikiZaPomoc() } catch (_: Exception) { emptyList() }
        if (racunalniki.isEmpty()) return emptyList()
        val izid = java.util.Collections.synchronizedList(mutableListOf<Jamendo.Skladba>())
        val plakati = getSharedPreferences(PREFS_PC_PRENOSI, MODE_PRIVATE)
        val cakam = java.util.concurrent.CountDownLatch(racunalniki.size)
        glavna.post {
            for (r in racunalniki) link.ukaz(r.id, "magnet.list", org.json.JSONObject(), 8_000, LinkOdjemalec.Odgovor { odgovor, _ ->
                try {
                    val a = odgovor?.takeIf { it.optBoolean("ok") }?.optJSONObject("data")?.optJSONArray("items")
                    val ime = DatotekeActivity.lepoIme(r.ime).ifBlank { r.id }
                    for (i in 0 until (a?.length() ?: 0)) {
                        val t = a!!.optJSONObject(i) ?: continue
                        val skupaj = t.optLong("size"); val dobljeno = t.optLong("done")
                        val delez = if (skupaj > 0) (dobljeno * 100 / skupaj).toInt() else 0
                        val opis = ime + " · " + android.text.format.Formatter.formatShortFileSize(this, skupaj) +
                            (if (t.optBoolean("finished")) "" else " · $delez %")
                        val plakat = btih(t.optString("magnet"))?.let { plakati.getString(it, "") }.orEmpty()
                        izid += Jamendo.Skladba(PREDPONA_PC_PRENOSA + r.id + "|" + t.optInt("id") + "|" + t.optInt("file", -1),
                            t.optString("name"), opis, plakat, "", t.optString("magnet"), video = true, mediaType = "movie")
                    }
                } finally { cakam.countDown() }
            })
        }
        cakam.await(10, java.util.concurrent.TimeUnit.SECONDS)
        return izid.sortedBy { it.naslov.lowercase(Locale.ROOT) }
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

    /** Prenos na racunalniku: predvajaj (racunalnik pretaka) ali odstrani z racunalnika, ko ga ne rabis vec. */
    private fun meniPrenosa(sk: Jamendo.Skladba) {
        val d = sk.id.removePrefix(PREDPONA_PC_PRENOSA).split('|')
        if (d.size < 3) return
        val (pc, tid, datoteka) = Triple(d[0], d[1].toIntOrNull() ?: return, d[2].toIntOrNull() ?: -1)
        AlertDialog.Builder(this).setTitle(sk.naslov).setMessage(sk.izvajalec)
            .setPositiveButton(R.string.os_prenos_predvajaj) { _, _ -> torrentPrekRacunalnika(sk, sk.naslov, sk.povezava, datoteka, pc) }
            .setNeutralButton(R.string.os_prenos_odstrani) { _, _ ->
                AlertDialog.Builder(this).setTitle(sk.naslov).setMessage(R.string.os_prenos_odstrani_vprasanje)
                    .setPositiveButton(R.string.os_prenos_odstrani) { _, _ ->
                        link.ukaz(pc, "magnet.remove", org.json.JSONObject().put("id", tid), 20_000, LinkOdjemalec.Odgovor { izid, napaka ->
                            if (isFinishing) return@Odgovor
                            val ok = izid?.optBoolean("ok") == true
                            Toast.makeText(this, if (ok) getString(R.string.os_prenos_odstranjen)
                                else getString(R.string.os_stremio_torrent_napaka, izid?.optString("message") ?: napaka), Toast.LENGTH_LONG).show()
                            if (ok) { SEZNAMI.remove(VIDEO); if (razdelek == VIDEO) izberi(VIDEO) }
                        })
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }
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
        if (tokPomocnika != null) { if (tokPomocnika.izvirnik.video) MediaNapredek.zapisi(this, tokPomocnika.izvirnik, tokPomocnika.zamikMs + p.currentPosition.coerceAtLeast(0), tokPomocnika.trajanjeMs) }
        else if (sk.video) MediaNapredek.zapisi(this, sk, p.currentPosition.coerceAtLeast(0), p.duration.coerceAtLeast(0))
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
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (p?.hasNextMediaItem() == true) p.seekToNextMediaItem(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { p?.seekToPreviousMediaItem(); return true }
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
        /** Risanje po korakih: kartic na kos in casovni proracun enega kosa na glavni niti. */
        private const val KARTIC_NA_KORAK = 6
        private const val PRORACUN_MS = 8L
        /** Beseda za iskanje ob odprtju (prazna: samo odpri iskanje), npr. z zaslona predvajanja. */
        const val ISKANJE_BESEDA = "iskanje"
        /** Zavihek spodnje vrstice (StranskaVrstica.Zavihek.name), ki naj se odpre. */
        const val ZAVIHEK = "zavihek"
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
        private const val PREDPONA_PC_PRENOSA = "pcprenos|"
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
