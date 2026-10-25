package si.safeer.tv.link

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import si.safeer.tv.R
import si.safeer.tv.cast.Dostop
import si.safeer.tv.link.VnosVklopPravila.Dejanje
import si.safeer.tv.link.VnosVklopPravila.Pot
import si.safeer.tv.link.VnosVklopPravila.Spomin
import java.util.concurrent.Executors

/**
 * Ena privolitev za Safeer Vnos (pravila: [VnosVklopPravila]; lastnik 25. 10. 2026): kdor napravi v ozjem krogu odpre
 * »Zaslon in upravljanje«, s tem vklopi tudi Safeer Vnos; ko v krogu ni vec nobene naprave z upravljanjem (umik iz
 * kroga, odvzem zmoznosti), se Safeer Vnos izklopi.
 *
 * Z dovoljenjem WRITE_SECURE_SETTINGS (pm grant ob namestitvi prek adb, install_safeer.sh --upravljanje) aplikacija
 * vpise SAMO svojo storitev v enabled_accessibility_services - druge ostanejo (na lastnikovem televizorju Philips
 * SourceService za daljinec) - in nastavi accessibility_enabled=1. Brez dovoljenja nic ne pise: uporabnik dobi voden
 * korak na stran Safeer Vnosa v istem toku. Vnos sprejme ukaze samo od naprav z odprtim zaslonom (cast/Dostop,
 * DostopPravila.zahtevaDejanja: input.* = ZASLON; za vnos tudi clanstvo v krogu - Dostop.smeDejanje).
 *
 * Pregled veje tv-vnos (25. 10. 2026): vse odlocitve in pisanje gredo po ENI niti ([vrsta]) - vklop z druge naprave,
 * izklop ob umiku in uskladitev se ne prehitevajo (tocka 2); vpise samo [vklopi], in to samo, ce ozji krog to zahteva
 * (tocka 1); rocni izklop uporabnika velja do nove privolitve (tocka 4, [Spomin]).
 */
object VnosVklop {
    private const val TAG = "SafeerVnosVklop"
    /** Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES in ACCESSIBILITY_ENABLED. */
    private const val KLJUC_STORITVE = "enabled_accessibility_services"
    private const val KLJUC_VKLOP = "accessibility_enabled"
    private const val PISANJE = "android.permission.WRITE_SECURE_SETTINGS"
    /** Kako dolgo cakamo, da sistem storitev veze (izmerjeno: glej porocilo nacrti/tv-vnos.md). */
    private const val CAKANJE_MS = 6_000L
    private const val KORAK_MS = 200L

    private val glavna = Handler(Looper.getMainLooper())
    /** Ena nit za vse odlocitve in pisanje (brez zaklepov, ki bi se lahko krizali z zaklepi kroga in dovoljenj). */
    private val vrsta = Executors.newSingleThreadExecutor { r -> Thread(r, "safeer-vnos-vklop").apply { isDaemon = true } }
    private const val PREFS = "safeer_vnos_vklop"

    private fun vVrsti(delo: () -> Unit) {
        try { vrsta.execute { try { delo() } catch (e: Throwable) { Log.w(TAG, "Safeer Vnos: ${e.message}") } } }
        catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    private fun spomin(c: Context): Spomin = try {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        Spomin(p.getBoolean("vpisali_mi", false), p.getBoolean("zadnjic_vpisano", false), p.getBoolean("izklopil_uporabnik", false))
    } catch (_: Throwable) { Spomin() }

    private fun shrani(c: Context, s: Spomin) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("vpisali_mi", s.vpisaliMi)
                .putBoolean("zadnjic_vpisano", s.zadnjicVpisano).putBoolean("izklopil_uporabnik", s.izklopilUporabnik).commit()
        } catch (_: Throwable) { }
    }

    /** Spomin, posodobljen s tem, kar je zdaj vpisano (rocni izklop ali vklop uporabnika). Samo v [vrsta]. */
    private fun opazi(c: Context): Spomin {
        val prej = spomin(c)
        val s = VnosVklopPravila.opazi(prej, vpisano(c))
        if (s != prej) {
            if (s.izklopilUporabnik && !prej.izklopilUporabnik) Log.i(TAG, "Safeer Vnos je izklopil uporabnik: do nove privolitve ga ne vklopimo.")
            shrani(c, s)
        }
        return s
    }

    fun komponenta(c: Context): ComponentName = ComponentName(c.packageName, VnosStoritev::class.java.name)

    private fun nasa(c: Context): String = komponenta(c).flattenToString()

    /** Ali ima ta gradnja storitev Safeer Vnos (brskalnik je nima): sicer je nikoli ne zahtevamo. */
    fun vGradnji(c: Context): Boolean = try {
        @Suppress("DEPRECATION") c.packageManager.getServiceInfo(komponenta(c), 0); true
    } catch (_: Throwable) { false }

    fun smePisati(c: Context): Boolean = c.checkSelfPermission(PISANJE) == PackageManager.PERMISSION_GRANTED

    /** Nacin AppOps `android:access_restricted_settings` (samo branje) ali null (Android < 13 ali ga ni mogoce prebrati). */
    fun nacinOmejenih(c: Context): Int? {
        if (Build.VERSION.SDK_INT < 33) return null
        return try {
            val ao = c.getSystemService(android.app.AppOpsManager::class.java) ?: return null
            ao.unsafeCheckOpNoThrow("android:access_restricted_settings", android.os.Process.myUid(), c.packageName)
        } catch (_: Throwable) { null }
    }

    fun omejenoZaklenjeno(c: Context): Boolean = VnosVklopPravila.omejenoZaklenjeno(Build.VERSION.SDK_INT, nacinOmejenih(c))

    /** Ali je nasa storitev vpisana med vklopljene (lahko se ni vezana). */
    fun vpisano(c: Context): Boolean =
        VnosVklopPravila.vsebuje(Settings.Secure.getString(c.contentResolver, KLJUC_STORITVE), nasa(c))

    fun pot(c: Context): Pot = VnosVklopPravila.pot(vGradnji(c), VnosStoritev.aktivna(), smePisati(c), omejenoZaklenjeno(c))

    /**
     * Ali naj Safeer Vnos tece: druga naprava, ki je se clan kroga (ne umaknjena), ima zaslon, odprt s privolitvijo
     * uporabnika (Dostop.napraveSPrivolitvijoZaslona - podedovane ne, pregled tv-vnos tocka 5). Null: krog ali dovoljenj
     * ni bilo mogoce prebrati.
     */
    fun zeleno(c: Context): Boolean? = try {
        VnosVklopPravila.zeleno(Dostop.napraveSPrivolitvijoZaslona(c),
            { j -> Dostop.clanKroga(c, j) }, { j -> Dostop.jeTaNaprava(c, j) })
    } catch (e: Throwable) {
        Log.w(TAG, "Stanja kroga ni bilo mogoce prebrati: ${e.message}"); null
    }

    /** Izid vklopa: pot, po kateri je slo, in ali storitev zdaj res tece (vezana, [VnosStoritev.aktivna]). */
    class Izid(val pot: Pot, val aktivna: Boolean)

    /**
     * Vklop z WRITE_SECURE_SETTINGS: vpis nase storitve (druge ostanejo), accessibility_enabled=1, nato merimo, ali se
     * je storitev res vezala. Ce se v [CAKANJE_MS] ne, vpis umaknemo (stanje ne sme biti lazno »vklopljeno«) in [potem]
     * dobi pot, ki jo mora uporabnik se narediti. [potem] tece na glavni niti. Vpise samo, ce ozji krog to ZDAJ zahteva in
     * ga uporabnik ni rocno izklopil (VnosVklopPravila.smeVpisati) - sicer [potem] dobi voden korak na tej napravi.
     */
    fun vklopi(c: Context, potem: ((Izid) -> Unit)? = null) {
        val app = c.applicationContext
        vVrsti { vklopiZdaj(app, potem) }
    }

    private fun vklopiZdaj(app: Context, potem: ((Izid) -> Unit)?) {
        val p = pot(app)
        if (p != Pot.SAMODEJNO) { glavna.post { potem?.invoke(Izid(p, VnosStoritev.aktivna())) }; return }
        val s = opazi(app)
        if (!VnosVklopPravila.smeVpisati(zeleno(app), s)) {
            Log.i(TAG, "Safeer Vnosa ne vpisemo sami: ozji krog ga ne zahteva ali ga je uporabnik izklopil.")
            glavna.post { potem?.invoke(Izid(Pot.VODENO, false)) }
            return
        }
        val cr = app.contentResolver
        val dodali: Boolean
        try {
            val prej = Settings.Secure.getString(cr, KLJUC_STORITVE)
            dodali = !VnosVklopPravila.vsebuje(prej, nasa(app))
            if (dodali) Settings.Secure.putString(cr, KLJUC_STORITVE, VnosVklopPravila.dodaj(prej, nasa(app)))
            if (Settings.Secure.getInt(cr, KLJUC_VKLOP, 0) != 1) Settings.Secure.putInt(cr, KLJUC_VKLOP, 1)
            if (dodali) shrani(app, VnosVklopPravila.PO_VPISU)
        } catch (e: Throwable) {
            Log.w(TAG, "Vpisa Safeer Vnosa ni bilo mogoce narediti: ${e.message}")
            glavna.post { potem?.invoke(Izid(Pot.VODENO, false)) }
            return
        }
        Log.i(TAG, "Safeer Vnos vpisan (ena privolitev v ozjem krogu); cakam na vezavo storitve.")
        val zacetek = SystemClock.elapsedRealtime()
        glavna.postDelayed(object : Runnable {
            override fun run() {
                val ms = SystemClock.elapsedRealtime() - zacetek
                if (VnosStoritev.aktivna()) {
                    Log.i(TAG, "Safeer Vnos tece ($ms ms po vpisu).")
                    // Ob koncu se enkrat: krog se je med cakanjem morda spremenil (umik) - potem takoj izklop (tocka 2).
                    uskladi(app, "po vklopu")
                    potem?.invoke(Izid(Pot.ZE_VKLOPLJENO, true)); return
                }
                if (ms < CAKANJE_MS) { glavna.postDelayed(this, KORAK_MS); return }
                // Sistem ga ni vezal (npr. omejene nastavitve, ki jih branje ni pokazalo): vpis umaknemo.
                if (dodali) vVrsti { odstraniVpis(app) }
                val manjka = if (Build.VERSION.SDK_INT >= 33) Pot.MANJKA_ODKLEP else Pot.VODENO
                Log.w(TAG, "Safeer Vnos se v $ms ms ni vezal; vpis umaknjen, potreben je korak uporabnika ($manjka).")
                potem?.invoke(Izid(manjka, false))
            }
        }, KORAK_MS)
    }

    /** Umakne nas vpis (samo v [vrsta]); druge storitve ostanejo. */
    private fun odstraniVpis(c: Context): Boolean {
        val cr = c.contentResolver
        val prej = Settings.Secure.getString(cr, KLJUC_STORITVE)
        if (!VnosVklopPravila.vsebuje(prej, nasa(c))) return false
        // accessibility_enabled ostane, kot je: druge storitve (npr. Philips SourceService) morajo teci naprej.
        Settings.Secure.putString(cr, KLJUC_STORITVE, VnosVklopPravila.odstrani(prej, nasa(c)))
        shrani(c, VnosVklopPravila.poIzpisu(spomin(c)))
        return true
    }

    /** Izklop z WRITE_SECURE_SETTINGS (v krogu ni vec naprave z upravljanjem). Brez dovoljenja nic. Samo v [vrsta]. */
    private fun izklopiZdaj(app: Context) {
        if (!smePisati(app)) return
        try {
            if (odstraniVpis(app)) Log.i(TAG, "Safeer Vnos izklopljen: v ozjem krogu ni vec naprave z upravljanjem.")
        } catch (e: Throwable) {
            Log.w(TAG, "Izklopa Safeer Vnosa ni bilo mogoce narediti: ${e.message}")
        }
    }

    /**
     * Uskladitev brez okna (zagon storitve Linka, posodobitev aplikacije, sprememba kroga - tudi umik naprave): z
     * WRITE_SECURE_SETTINGS vklopi ali izklopi, kot zahteva ozji krog (VnosVklopPravila.dejanje). Brez dovoljenja ne
     * spremeni nicesar. Tece v [vrsta].
     */
    fun uskladi(c: Context, razlog: String) {
        val app = c.applicationContext
        vVrsti {
            if (!vGradnji(app)) return@vVrsti
            val s = opazi(app)
            when (VnosVklopPravila.dejanje(zeleno(app), vpisano(app), smePisati(app), s)) {
                Dejanje.VKLOPI -> vklopiZdaj(app) { izid -> Log.i(TAG, "Uskladitev ($razlog): ${izid.pot}, tece=${izid.aktivna}") }
                Dejanje.IZKLOPI -> izklopiZdaj(app)
                Dejanje.NIC -> { }
            }
        }
    }

    /**
     * Uporabnik je pravkar shranil ozji krog ali dostop naprave (privolitev): prejsnji rocni izklop ne velja vec. Z
     * dovoljenjem Vnos vklopi sam in to pove; brez njega v istem toku pokaze en korak z gumbom na tocno stran Safeer
     * Vnosa. Ce v krogu ni vec naprave z upravljanjem, z dovoljenjem izklopi vpis, ki ga je naredila aplikacija.
     */
    fun poPrivolitvi(a: Activity) {
        val app = a.applicationContext
        if (!vGradnji(app)) return
        vVrsti {
            shrani(app, VnosVklopPravila.poPrivolitvi(opazi(app)))
            val zeleno = zeleno(app) ?: return@vVrsti
            if (!zeleno) { if (spomin(app).vpisaliMi) izklopiZdaj(app); return@vVrsti }
            when (val p = pot(app)) {
                Pot.NI_STORITVE, Pot.ZE_VKLOPLJENO -> return@vVrsti
                Pot.SAMODEJNO -> vklopiZdaj(app) { izid ->
                    if (izid.aktivna) Toast.makeText(app, app.getString(R.string.vnos_vklopljen), Toast.LENGTH_LONG).show()
                    else if (!a.isFinishing && !a.isDestroyed) korak(a, izid.pot)
                }
                Pot.MANJKA_ODKLEP, Pot.VODENO -> glavna.post { if (!a.isFinishing && !a.isDestroyed) korak(a, p) }
            }
        }
    }

    /** En korak uporabnika v istem toku: kaj se bo zgodilo in gumb na tocno stran. */
    private fun korak(a: Activity, p: Pot) {
        val tv = si.safeer.tv.os.Naprava.jeTelevizor(a)
        val besedilo = when {
            p == Pot.MANJKA_ODKLEP -> a.getString(R.string.vnos_korak_odklep)
            tv -> a.getString(R.string.vnos_korak_tv)
            else -> a.getString(R.string.vnos_korak_dotik)
        }
        try {
            val okno = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.getString(R.string.vnos_korak_naslov))
                .setMessage(besedilo)
                .setPositiveButton(a.getString(R.string.vnos_odpri)) { _, _ ->
                    try { if (p == Pot.MANJKA_ODKLEP) odpriPodatkeAplikacije(a) else odpriPodrobnosti(a) } catch (e: Throwable) {
                        Log.w(TAG, "Nastavitev ni bilo mogoce odpreti: ${e.message}")
                    }
                }
                .setNegativeButton(a.getString(R.string.os_preklici), null)
                .create()
            si.safeer.tv.os.Kontroler.pokazi(okno.also { it.show() })
        } catch (e: Throwable) {
            Log.w(TAG, "Okna za Safeer Vnos ni bilo mogoce pokazati: ${e.message}")
        }
    }

    /**
     * Od API 31 naravnost na podrobnosti Safeer Vnosa (ACTION_ACCESSIBILITY_DETAILS_SETTINGS - stikalo je takoj vidno);
     * nizje seznam dostopnosti z oznaceno postavko (`fragment_args`). Ob napaki navaden seznam dostopnosti. Storitve,
     * ki je ta gradnja nima, ne zahtevamo: vrne false.
     */
    fun odpriPodrobnosti(c: Context): Boolean {
        if (!vGradnji(c)) return false
        val komponenta = nasa(c)
        val oznaci = android.os.Bundle().apply { putString(":settings:fragment_args_key", komponenta) }
        val akcija = if (Build.VERSION.SDK_INT >= 31) "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
            else Settings.ACTION_ACCESSIBILITY_SETTINGS
        val namera = Intent(akcija).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(":settings:fragment_args_key", komponenta)
            .putExtra(":settings:show_fragment_args", oznaci)
        try {
            if (c.packageManager.resolveActivity(namera, 0) != null) { c.startActivity(namera); return true }
        } catch (_: Throwable) { }
        c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(":settings:fragment_args_key", komponenta)
            .putExtra(":settings:show_fragment_args", oznaci))
        return true
    }

    /** Podatki aplikacije (odklep omejenih nastavitev je tam, kjer ga proizvajalec ponuja). */
    fun odpriPodatkeAplikacije(c: Context) {
        c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Ime storitve, kot ga uporabnik vidi v Nastavitvah naprave (jezik naprave), sicer »Safeer Vnos«. */
    fun ime(c: Context): String = try {
        @Suppress("DEPRECATION") c.packageManager.getServiceInfo(komponenta(c), 0).loadLabel(c.packageManager).toString()
            .ifBlank { "Safeer Vnos" }
    } catch (_: Throwable) { "Safeer Vnos" }

    /**
     * Naslov okna privolitve (ozji krog, dostop naprave) z jasnim opisom, kaj se zgodi (lastnik 25. 10. 2026: »Racunalniki
     * iz tvojega ozjega kroga bodo lahko upravljali to napravo z misko in tipkovnico«). [kontekst] je kontekst okna
     * (AlertDialog.Builder.context), da so barve besedila iz teme okna. Brez storitve v gradnji samo naslov.
     */
    fun naslovPrivolitve(kontekst: Context, naslov: String): android.view.View {
        val gostota = kontekst.resources.displayMetrics.density
        fun dp(v: Int) = (v * gostota).toInt()
        val sloj = android.widget.LinearLayout(kontekst).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(4))
        }
        sloj.addView(android.widget.TextView(kontekst).apply {
            text = naslov
            setTextAppearance(android.R.style.TextAppearance_DeviceDefault_DialogWindowTitle)
        })
        if (vGradnji(kontekst)) sloj.addView(android.widget.TextView(kontekst).apply {
            text = kontekst.getString(R.string.vnos_privolitev)
            setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
            setPadding(0, dp(8), 0, 0)
        })
        return sloj
    }
}
