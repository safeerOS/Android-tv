package si.safeer.tv.os

import si.safeer.tv.R

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import si.safeer.tv.os.DovoljenjaPravila.Dejanje
import si.safeer.tv.os.DovoljenjaPravila.Stanje
import si.safeer.tv.os.DovoljenjaPravila.Vrsta

/**
 * Stran »Dovoljenja« (Nastavitve › Dovoljenja; lastnik, 25. 10. 2026): vse, kar Safeer na tej napravi res zahteva, z
 * razlogom v preprostem jeziku in sprotnim stanjem (✓/✗). Vsaka vrstica je en velik gumb (daljinec ali dotik), ki
 * dovoljenje vprasa kar v aplikaciji ali odpre TOCNO pravo sistemsko stran za Safeer - uporabniku ni treba iskati po
 * sistemskih nastavitvah. Ce se vrne s strani za prikaz cez druge aplikacije ali dostopnost brez dovoljenja (Android
 * 13+ »omejene nastavitve« za aplikacije mimo trgovine), dobi kratek namig v dveh korakih in gumb do Podatkov o
 * aplikaciji. Dovoljenj nikoli ne vklapljamo sami. Odlocitve so v [DovoljenjaPravila].
 *
 * Odpre se iz Nastavitev, enkrat ob zagonu domacega zaslona, ce manjka kaj pomembnega ([ponudiObZagonu]), in iz
 * obvestila Safeer Linka »Dovoli« ([namera] s poudarjeno vrstico).
 */
class DovoljenjaActivity : OsActivity() {

    private lateinit var koren: View
    private lateinit var drsnik: ScrollView
    private lateinit var vrstice: LinearLayout
    private lateinit var opozorilo: TextView
    private val nastavitve by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val tv by lazy { Naprava.jeTelevizor(this) }
    private var obZagonu = false
    /** Vrstica, ki naj ima izbiro (poudarjena iz obvestila ali zadnja uporabljena). */
    private var fokus: Vrsta? = null
    /** Postavka, katere sistemsko stran smo odprli: ob vrnitvi preverimo, ali je zdaj dovoljena (namig za omejene). */
    private var odprta: Vrsta? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(StranskaVrstica.ovij(this, R.layout.os_activity_dovoljenja, StranskaVrstica.Razdelek.NASTAVITVE))
        koren = findViewById(R.id.koren)
        drsnik = findViewById(R.id.drsnik)
        vrstice = findViewById(R.id.vrstice)
        opozorilo = findViewById(R.id.opozorilo)
        obZagonu = intent.getBooleanExtra(EXTRA_OB_ZAGONU, false)
        fokus = intent.getStringExtra(EXTRA_POUDARI)?.let { n -> Vrsta.values().firstOrNull { it.name == n } }
    }

    override fun onStart() {
        super.onStart()
        Ozadje.uporabi(this, koren)
    }

    override fun onResume() {
        super.onResume()
        // Sistemsko okno je zaprto (na televizorju je prosojno, zato je bil nas zaslon skrit).
        koren.visibility = View.VISIBLE
        odprta?.let { v ->
            odprta = null
            val kljuc = NEUSPELIH + v.name
            if (stanje(this, v) == Stanje.DA) nastavitve.edit().remove(kljuc).apply()
            else nastavitve.edit().putInt(kljuc, nastavitve.getInt(kljuc, 0) + 1).apply()
        }
        narisi()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ZAHTEVA) narisi()
    }

    // ------------------------------------------------------------------ risanje

    private fun narisi() {
        val stanja = stanja(this)
        val pomembne = DovoljenjaPravila.pomembne(tv)
        val manjkaPomembno = stanja.any { it.key in pomembne && it.value != Stanje.DA }
        opozorilo.visibility = if (obZagonu && manjkaPomembno) View.VISIBLE else View.GONE
        opozorilo.text = getString(R.string.dov_ob_zagonu)
        vrstice.removeAllViews()
        var namigZaFokus: View? = null
        for ((v, s) in stanja) {
            vrstice.addView(vrstica(v, s, pomembna = obZagonu && v in pomembne && s != Stanje.DA))
            if (DovoljenjaPravila.namigOmejene(v, Build.VERSION.SDK_INT, s, nastavitve.getInt(NEUSPELIH + v.name, 0))) {
                val n = namig()
                vrstice.addView(n)
                if (v == fokus) namigZaFokus = n
            }
        }
        // Izbira: poudarjena ali zadnja uporabljena vrstica; sicer prva (daljinec). Ce se je uporabnik vrnil brez dovoljenja
        // in je pod vrstico namig, je naslednji korak gumb namiga - izbira gre nanj, namig je v celoti na zaslonu.
        val cilj = namigZaFokus?.findViewWithTag<View>(OZNAKA_NAMIGA)
            ?: fokus?.let { vrstice.findViewWithTag<View>(OZNAKA + it.name) } ?: if (tv) vrstice.getChildAt(0) else null
        val namig = namigZaFokus
        cilj?.post {
            cilj.requestFocus()
            if (fokus == null) return@post
            // Polozaj v drsniku (vrstice so pod uvodom): namig v celoti viden (njegovo dno), sicer vrstica na vrhu.
            val zgoraj = vrstice.top + (namig ?: cilj).top - dp(12)
            val y = if (namig != null) minOf(zgoraj, vrstice.top + namig.bottom + dp(12) - drsnik.height) else zgoraj
            drsnik.smoothScrollTo(0, y.coerceAtLeast(0))
        }
    }

    private fun barva(atribut: Int): Int {
        val v = TypedValue()
        theme.resolveAttribute(atribut, v, true)
        return v.data
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun besedilo(niz: String, velikost: Float, atribut: Int, krepko: Boolean = false) = TextView(this).apply {
        text = niz
        textSize = velikost
        setTextColor(barva(atribut))
        if (krepko) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** Ena vrstica = en velik gumb: ikona, ime, razlog, stanje in dejanje (tipka se obarva z izbiro vrstice). */
    private fun vrstica(v: Vrsta, s: Stanje, pomembna: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.os_kartica)
        setPadding(dp(18), dp(14), dp(16), dp(14))
        isFocusable = true
        isClickable = true
        tag = OZNAKA + v.name
        contentDescription = ime(this@DovoljenjaActivity, v)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        addView(ImageView(this@DovoljenjaActivity).apply {
            setImageResource(ikona(v))
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(16) })
        addView(LinearLayout(this@DovoljenjaActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(besedilo(ime(this@DovoljenjaActivity, v), 17f, R.attr.osBesedilo, krepko = true))
            addView(besedilo(getString(razlog(v)), 13f, R.attr.osUmirjeno).apply { setPadding(0, dp(2), 0, dp(4)) })
            addView(besedilo(getString(when (s) {
                Stanje.DA -> R.string.dov_da
                Stanje.DELNO -> R.string.dov_delno
                Stanje.NE -> R.string.dov_ne
            }), 14f, if (s == Stanje.DA) R.attr.osMint else R.attr.osOpozorilo, krepko = pomembna))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(besedilo(getString(if (s == Stanje.DA) R.string.dov_gumb_nastavitev else R.string.dov_gumb_dovoli), 16f, R.attr.osBesedilo, krepko = true).apply {
            setBackgroundResource(R.drawable.os_tipka_velika)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            isDuplicateParentStateEnabled = true
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        setOnClickListener { fokus = v; izvedi(v, stanje(this@DovoljenjaActivity, v)) }
    }

    /**
     * Android 13+ »omejene nastavitve« (aplikacija mimo trgovine): stikalo je sivo, dokler uporabnik v Podatkih o
     * aplikaciji (⋮) ne dovoli omejenih nastavitev. Dva koraka in gumb do prave strani; sami ne vklopimo nicesar.
     */
    private fun namig(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.os_kartica_mirna)
        setPadding(dp(18), dp(12), dp(18), dp(14))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12); marginStart = dp(46)
        }
        addView(besedilo(getString(R.string.dov_namig_naslov), 15f, R.attr.osOpozorilo, krepko = true))
        addView(besedilo(getString(R.string.dov_namig_1), 14f, R.attr.osBesedilo).apply { setPadding(0, dp(6), 0, 0) })
        addView(besedilo(getString(R.string.dov_namig_2), 14f, R.attr.osBesedilo).apply { setPadding(0, dp(4), 0, 0) })
        addView(besedilo(getString(R.string.dov_namig_zakaj), 12f, R.attr.osUmirjeno).apply { setPadding(0, dp(6), 0, dp(8)) })
        addView(besedilo(getString(R.string.dov_namig_gumb), 15f, R.attr.osBesedilo, krepko = true).apply {
            setBackgroundResource(R.drawable.os_tipka_velika)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            isFocusable = true
            isClickable = true
            tag = OZNAKA_NAMIGA
            setOnClickListener { odpri(null, podatkiOAplikaciji()) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    // ------------------------------------------------------------------ dejanja

    private fun izvedi(v: Vrsta, s: Stanje) {
        val sdk = Build.VERSION.SDK_INT
        val utemeljitev = DovoljenjaPravila.dovoljenja(v, sdk).any { shouldShowRequestPermissionRationale(it) }
        val vprasa = DovoljenjaPravila.sistemVprasa(nastavitve.getBoolean(VPRASANO + v.name, false), utemeljitev)
        when (DovoljenjaPravila.dejanje(v, sdk, s, vprasa)) {
            Dejanje.VPRASAJ -> {
                nastavitve.edit().putBoolean(VPRASANO + v.name, true).apply()
                try { requestPermissions(DovoljenjaPravila.dovoljenja(v, sdk).toTypedArray(), ZAHTEVA) } catch (_: Throwable) { odpri(v, podatkiOAplikaciji()) }
            }
            Dejanje.STRAN_PRIKAZ -> odpri(v, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Dejanje.STRAN_OBVESTILA -> odpri(v, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            Dejanje.STRAN_CELOZASLONSKA -> odpri(v, if (Build.VERSION.SDK_INT >= 34)
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")) else podatkiOAplikaciji())
            Dejanje.STRAN_DOSTOPNOST -> {
                if (si.safeer.tv.link.VnosVklop.smePisati(this)) {
                    val cr = contentResolver
                    val nasa = komponentaVnosa(this).flattenToString()
                    val prej = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    if (s != Stanje.DA) {
                        try {
                            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, si.safeer.tv.link.VnosVklopPravila.dodaj(prej, nasa))
                            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                            Toast.makeText(this, R.string.dov_da, Toast.LENGTH_SHORT).show()
                        } catch (e: Throwable) {
                            android.util.Log.w("SafeerOs", "Napaka pri vklopu vnosa: ${e.message}")
                        }
                    } else {
                        try {
                            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, si.safeer.tv.link.VnosVklopPravila.odstrani(prej, nasa))
                        } catch (e: Throwable) {
                            android.util.Log.w("SafeerOs", "Napaka pri izklopu vnosa: ${e.message}")
                        }
                    }
                    narisi()
                    return
                }
                // Kot Safeer Link (input.enable): nastavitve dostopnosti z oznacenim Safeer Vnosom, kjer sistem to zna.
                val komponenta = komponentaVnosa(this).flattenToString()
                odpri(v, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .putExtra(":settings:fragment_args_key", komponenta)
                    .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", komponenta) }))
            }
            Dejanje.STRAN_NAMESCANJE -> odpri(v, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            Dejanje.STRAN_APLIKACIJA -> odpri(v, podatkiOAplikaciji())
        }
    }

    private fun podatkiOAplikaciji() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    /**
     * Sistemska stran; ce je naprava nima (nekateri televizorji), Podatki o aplikaciji. Na televizorju je sistemsko okno
     * prosojno in nas zaslon bi prosevel skozenj - zato ga skrijemo do vrnitve (kot Nastavitve).
     */
    private fun odpri(v: Vrsta?, namera: Intent) {
        koren.visibility = View.INVISIBLE
        for (i in listOf(namera, podatkiOAplikaciji())) {
            try { startActivity(i); odprta = v; return } catch (_: Throwable) { }
        }
        koren.visibility = View.VISIBLE
        Toast.makeText(this, R.string.nast_ni_odprto, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_POUDARI = "poudari"
        const val EXTRA_OB_ZAGONU = "ob_zagonu"
        private const val PREFS = "safeer_dovoljenja"
        private const val ZE_POKAZANE = "ze_pokazane"
        private const val VPRASANO = "vprasano_"
        private const val NEUSPELIH = "neuspelih_"
        private const val OZNAKA = "dov:"
        private const val OZNAKA_NAMIGA = "dov-namig"
        private const val ZAHTEVA = 7420

        private fun ikona(v: Vrsta) = when (v) {
            Vrsta.PRIKAZ -> R.drawable.os_ikona_zaslon
            Vrsta.OBVESTILA -> R.drawable.os_ikona_zvonec
            Vrsta.CELOZASLONSKA -> R.drawable.os_ikona_naprava
            Vrsta.SLIKE_VIDEI -> R.drawable.os_ikona_slika
            Vrsta.GLASBA -> R.drawable.os_ikona_glasba
            Vrsta.VNOS -> R.drawable.os_ikona_dostopnost
            Vrsta.NAMESCANJE -> R.drawable.os_ikona_posodobi
        }

        private fun razlog(v: Vrsta) = when (v) {
            Vrsta.PRIKAZ -> R.string.dov_prikaz_opis
            Vrsta.OBVESTILA -> R.string.dov_obvestila_opis
            Vrsta.CELOZASLONSKA -> R.string.dov_celozaslonska_opis
            Vrsta.SLIKE_VIDEI -> R.string.dov_slike_opis
            Vrsta.GLASBA -> R.string.dov_glasba_opis
            Vrsta.VNOS -> R.string.dov_vnos_opis
            Vrsta.NAMESCANJE -> R.string.dov_namescanje_opis
        }

        /** Ime, kot ga uporabnik vidi v sistemu: prikaz po proizvajalcu, Safeer Vnos po imenu storitve dostopnosti. */
        fun ime(c: Context, v: Vrsta): String = when (v) {
            Vrsta.PRIKAZ -> imePrikaza(c)
            Vrsta.OBVESTILA -> c.getString(R.string.dov_obvestila)
            Vrsta.CELOZASLONSKA -> c.getString(R.string.dov_celozaslonska)
            Vrsta.SLIKE_VIDEI -> c.getString(R.string.dov_slike)
            Vrsta.GLASBA -> c.getString(R.string.dov_glasba)
            Vrsta.VNOS -> try {
                @Suppress("DEPRECATION") c.packageManager.getServiceInfo(komponentaVnosa(c), 0).loadLabel(c.packageManager).toString()
            } catch (_: Throwable) { "Safeer Vnos" }
            Vrsta.NAMESCANJE -> c.getString(R.string.dov_namescanje)
        }

        /** »Prikaz čez druge aplikacije« z imenom, ki ga uporablja ta naprava (Samsung: »Pojavitev na vrhu«). */
        fun imePrikaza(c: Context): String = c.getString(when (DovoljenjaPravila.imePrikaza(Build.MANUFACTURER)) {
            DovoljenjaPravila.ImePrikaza.SAMSUNG -> R.string.dov_prikaz_samsung
            DovoljenjaPravila.ImePrikaza.ONEPLUS -> R.string.dov_prikaz_oneplus
            DovoljenjaPravila.ImePrikaza.SPLOSNO -> R.string.dov_prikaz
        })

        private fun komponentaVnosa(c: Context) = ComponentName(c.packageName, "si.safeer.tv.link.VnosStoritev")

        private fun deklarirana(c: Context): Set<String> = try {
            @Suppress("DEPRECATION")
            c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet() ?: emptySet()
        } catch (_: Throwable) { emptySet() }

        @Suppress("DEPRECATION")
        private fun imaVnos(c: Context): Boolean = try { c.packageManager.getServiceInfo(komponentaVnosa(c), 0); true } catch (_: Throwable) { false }

        /** Stanje postavke na tej napravi (sproti, brez predpomnjenja). */
        fun stanje(c: Context, v: Vrsta): Stanje = try {
            val sdk = Build.VERSION.SDK_INT
            when (v) {
                Vrsta.PRIKAZ -> if (Settings.canDrawOverlays(c)) Stanje.DA else Stanje.NE
                // Obvestila: dovoljenje (13+) in stikalo aplikacije skupaj - kar uporabnik dejansko vidi.
                Vrsta.OBVESTILA -> if (si.safeer.tv.link.Obvestila.dovoljena(c)) Stanje.DA else Stanje.NE
                Vrsta.CELOZASLONSKA -> if (Build.VERSION.SDK_INT >= 34 &&
                    c.getSystemService(android.app.NotificationManager::class.java)?.canUseFullScreenIntent() == true) Stanje.DA else Stanje.NE
                Vrsta.SLIKE_VIDEI, Vrsta.GLASBA -> DovoljenjaPravila.stanjeIzOdobrenih(v, sdk,
                    DovoljenjaPravila.dovoljenja(v, sdk).filter { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }.toSet())
                Vrsta.VNOS -> {
                    val k = komponentaVnosa(c)
                    if (DovoljenjaPravila.vnosVklopljen(Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                            k.flattenToString(), k.flattenToShortString())) Stanje.DA else Stanje.NE
                }
                Vrsta.NAMESCANJE -> if (c.packageManager.canRequestPackageInstalls()) Stanje.DA else Stanje.NE
            }
        } catch (_: Throwable) { Stanje.NE }

        /** Postavke te naprave in njihovo stanje, v stalnem vrstnem redu ([DovoljenjaPravila.postavke]). */
        fun stanja(c: Context): Map<Vrsta, Stanje> =
            DovoljenjaPravila.postavke(Build.VERSION.SDK_INT, Naprava.jeTelevizor(c), imaVnos(c), deklarirana(c)).associateWith { stanje(c, it) }

        /** Stanje za vrstico v Nastavitvah: »Vse dovoljeno« ali »Manjka: N«. */
        fun povzetek(c: Context): String {
            val n = DovoljenjaPravila.manjka(stanja(c))
            return if (n == 0) c.getString(R.string.dov_stanje_vse) else c.getString(R.string.dov_stanje_manjka, n)
        }

        /** Ali ta okus stran sploh ima (brskalnik je nima - tam ostane neposredna sistemska stran). */
        fun naVoljo(c: Context): Boolean = try { Intent(c, DovoljenjaActivity::class.java).resolveActivity(c.packageManager) != null } catch (_: Throwable) { false }

        /** Namera za stran; [poudari] = vrstica z izbiro (npr. prikaz iz obvestila Safeer Linka). */
        fun namera(c: Context, poudari: Vrsta? = null): Intent =
            Intent(c, DovoljenjaActivity::class.java).apply { poudari?.let { putExtra(EXTRA_POUDARI, it.name) } }

        /**
         * Domaci zaslon: stran se pokaze sama, enkrat, ce manjka pomembna postavka, ki je uporabnik se ni videl
         * ([DovoljenjaPravila.pokaziObZagonu]). Prvo vprasanje za obvestila (Obvestila.zaprosiEnkrat) in druga okna imajo
         * prednost: pocakamo hip in stran odpremo samo, ce ima domaci zaslon se vedno okno v ospredju.
         */
        fun ponudiObZagonu(a: Activity) {
            try { a.window.decorView.postDelayed({ if (!a.isFinishing && a.hasWindowFocus()) ponudiZdaj(a) }, 900) } catch (_: Throwable) { }
        }

        private fun ponudiZdaj(a: Activity) {
            try {
                if (!naVoljo(a)) return
                val tv = Naprava.jeTelevizor(a)
                val manjkajo = stanja(a).filterValues { it != Stanje.DA }.keys
                val p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val ze = DovoljenjaPravila.izNiza(p.getString(ZE_POKAZANE, ""))
                if (!DovoljenjaPravila.pokaziObZagonu(manjkajo, ze, tv)) return
                p.edit().putString(ZE_POKAZANE, DovoljenjaPravila.vNiz(ze + (manjkajo intersect DovoljenjaPravila.pomembne(tv)))).apply()
                android.util.Log.i("SafeerOs", "dovoljenja: ob zagonu manjka ${manjkajo.joinToString()}")
                a.startActivity(Intent(a, DovoljenjaActivity::class.java).putExtra(EXTRA_OB_ZAGONU, true))
            } catch (_: Throwable) { }
        }
    }
}
