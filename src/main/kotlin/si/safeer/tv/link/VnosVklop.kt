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
import si.safeer.tv.cast.DostopPravila
import si.safeer.tv.cast.KrogNaprave
import si.safeer.tv.cast.KrogZaupanja
import si.safeer.tv.link.VnosVklopPravila.Dejanje
import si.safeer.tv.link.VnosVklopPravila.Pot

/**
 * Ena privolitev za Safeer Vnos (pravila: [VnosVklopPravila]; lastnik 25. 10. 2026): kdor napravi v ozjem krogu odpre
 * »Zaslon in upravljanje«, s tem vklopi tudi Safeer Vnos; ko v krogu ni vec nobene naprave z upravljanjem (umik iz
 * kroga, odvzem zmoznosti), se Safeer Vnos izklopi.
 *
 * Z dovoljenjem WRITE_SECURE_SETTINGS (pm grant ob namestitvi prek adb, install_safeer.sh --upravljanje) aplikacija
 * vpise SAMO svojo storitev v enabled_accessibility_services - druge ostanejo (na lastnikovem televizorju Philips
 * SourceService za daljinec) - in nastavi accessibility_enabled=1. Brez dovoljenja nic ne pise: uporabnik dobi voden
 * korak na stran Safeer Vnosa v istem toku. Vnos sprejme ukaze samo od naprav z odprtim zaslonom (cast/Dostop,
 * DostopPravila.zahtevaDejanja: input.* = ZASLON).
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
     * Ali naj Safeer Vnos tece: druga naprava, ki je se clan kroga (ne umaknjena), ima odprt zaslon. Null: krog ali
     * dovoljenj ni bilo mogoce prebrati.
     */
    fun zeleno(c: Context): Boolean? = try {
        val krog = KrogNaprave.krog(c)
        val clani = krog.clani()
        val jedraKroga = HashSet<String>()
        for (cl in clani) {
            jedraKroga.add(cl.id)
            try { jedraKroga.add(KrogZaupanja.idIzKljuca(cl.kljuc)) } catch (_: Throwable) { }
        }
        VnosVklopPravila.zeleno(Dostop.napraveZ(c, DostopPravila.Zmoznost.ZASLON),
            { j -> j in jedraKroga && !krog.jeUmaknjen(j) }, { j -> Dostop.jeTaNaprava(c, j) })
    } catch (e: Throwable) {
        Log.w(TAG, "Stanja kroga ni bilo mogoce prebrati: ${e.message}"); null
    }

    /** Izid vklopa: pot, po kateri je slo, in ali storitev zdaj res tece (vezana, [VnosStoritev.aktivna]). */
    class Izid(val pot: Pot, val aktivna: Boolean)

    /**
     * Vklop z WRITE_SECURE_SETTINGS: vpis nase storitve (druge ostanejo), accessibility_enabled=1, nato merimo, ali se
     * je storitev res vezala. Ce se v [CAKANJE_MS] ne, vpis umaknemo (stanje ne sme biti lazno »vklopljeno«) in [potem]
     * dobi pot, ki jo mora uporabnik se narediti. [potem] tece na glavni niti.
     */
    fun vklopi(c: Context, potem: ((Izid) -> Unit)? = null) {
        val app = c.applicationContext
        val p = pot(app)
        if (p != Pot.SAMODEJNO) { glavna.post { potem?.invoke(Izid(p, VnosStoritev.aktivna())) }; return }
        val cr = app.contentResolver
        val dodali: Boolean
        try {
            val prej = Settings.Secure.getString(cr, KLJUC_STORITVE)
            dodali = !VnosVklopPravila.vsebuje(prej, nasa(app))
            if (dodali) Settings.Secure.putString(cr, KLJUC_STORITVE, VnosVklopPravila.dodaj(prej, nasa(app)))
            if (Settings.Secure.getInt(cr, KLJUC_VKLOP, 0) != 1) Settings.Secure.putInt(cr, KLJUC_VKLOP, 1)
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
                    potem?.invoke(Izid(Pot.ZE_VKLOPLJENO, true)); return
                }
                if (ms < CAKANJE_MS) { glavna.postDelayed(this, KORAK_MS); return }
                // Sistem ga ni vezal (npr. omejene nastavitve, ki jih branje ni pokazalo): vpis umaknemo.
                if (dodali) try { odstraniVpis(app) } catch (_: Throwable) { }
                val manjka = if (Build.VERSION.SDK_INT >= 33) Pot.MANJKA_ODKLEP else Pot.VODENO
                Log.w(TAG, "Safeer Vnos se v $ms ms ni vezal; vpis umaknjen, potreben je korak uporabnika ($manjka).")
                potem?.invoke(Izid(manjka, false))
            }
        }, KORAK_MS)
    }

    private fun odstraniVpis(c: Context): Boolean {
        val cr = c.contentResolver
        val prej = Settings.Secure.getString(cr, KLJUC_STORITVE)
        if (!VnosVklopPravila.vsebuje(prej, nasa(c))) return false
        // accessibility_enabled ostane, kot je: druge storitve (npr. Philips SourceService) morajo teci naprej.
        Settings.Secure.putString(cr, KLJUC_STORITVE, VnosVklopPravila.odstrani(prej, nasa(c)))
        return true
    }

    /** Izklop z WRITE_SECURE_SETTINGS (v krogu ni vec naprave z upravljanjem). Brez dovoljenja nic. */
    fun izklopi(c: Context): Boolean {
        val app = c.applicationContext
        if (!smePisati(app)) return false
        return try {
            odstraniVpis(app).also { if (it) Log.i(TAG, "Safeer Vnos izklopljen: v ozjem krogu ni vec naprave z upravljanjem.") }
        } catch (e: Throwable) {
            Log.w(TAG, "Izklopa Safeer Vnosa ni bilo mogoce narediti: ${e.message}"); false
        }
    }

    /**
     * Uskladitev brez okna (zagon storitve Linka, posodobitev aplikacije, sprememba kroga - tudi umik naprave): z
     * WRITE_SECURE_SETTINGS vklopi ali izklopi, kot zahteva ozji krog. Brez dovoljenja ne spremeni nicesar.
     */
    fun uskladi(c: Context, razlog: String) {
        val app = c.applicationContext
        try {
            if (!vGradnji(app)) return
            when (VnosVklopPravila.dejanje(zeleno(app), vpisano(app), smePisati(app))) {
                Dejanje.VKLOPI -> vklopi(app) { izid -> Log.i(TAG, "Uskladitev ($razlog): ${izid.pot}, tece=${izid.aktivna}") }
                Dejanje.IZKLOPI -> izklopi(app)
                Dejanje.NIC -> { }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Uskladitev Safeer Vnosa ($razlog) ni uspela: ${e.message}")
        }
    }

    /**
     * Uporabnik je pravkar shranil ozji krog ali dostop naprave (privolitev). Z dovoljenjem Vnos vklopi sam in to pove;
     * brez njega v istem toku pokaze en korak z gumbom na tocno stran Safeer Vnosa. Ce v krogu ni vec naprave z
     * upravljanjem, ga z dovoljenjem izklopi.
     */
    fun poPrivolitvi(a: Activity) {
        val app = a.applicationContext
        if (!vGradnji(app)) return
        val zeleno = zeleno(app) ?: return
        if (!zeleno) { izklopi(app); return }
        when (val p = pot(app)) {
            Pot.NI_STORITVE, Pot.ZE_VKLOPLJENO -> return
            Pot.SAMODEJNO -> vklopi(app) { izid ->
                if (izid.aktivna) Toast.makeText(app, app.getString(R.string.vnos_vklopljen), Toast.LENGTH_LONG).show()
                else if (!a.isFinishing && !a.isDestroyed) korak(a, izid.pot)
            }
            Pot.MANJKA_ODKLEP, Pot.VODENO -> korak(a, p)
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
