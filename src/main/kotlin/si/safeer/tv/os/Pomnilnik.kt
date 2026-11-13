package si.safeer.tv.os

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Lastnik 30. 10. 2026 (TV: 2,7 GB RAM, prosto 0,55 GB, swap poln): sprostitev pomnilnika brez roota in brez ADB.
 * Android dovoli zapreti samo procese v OZADJU (KILL_BACKGROUND_PROCESSES): aplikacija v ospredju in tista, ki predvaja
 * (storitev v ospredju), ostaneta. Nikoli ne zapremo Safeerja, tipkovnice ali sistemskih storitev televizorja.
 */
object Pomnilnik {
    private const val TAG = "SafeerPomnilnik"

    /**
     * Stevec odhodov iz nasih dejavnosti (onPause katerekoli). Lastnik 2. 11. 2026: TV Browser, odprt 3 s po vrnitvi
     * domov, je zaprlo ciscenje ozadja, ki se je ravno izvajalo. Ciscenje se ustavi, ko se stevec spremeni.
     */
    @Volatile var odhodi: Long = 0L
        private set

    /**
     * Stevec tipk in zagonov iz zaganjalnika (lastnik 8. 11. 2026): zabelezi ga dispatchKeyEvent in vsak startActivity iz
     * doma, takoj na glavni niti - ciscenje, ki tece, se ustavi pred naslednjim force-stop, ne sele ob onPause.
     */
    @Volatile var vnosi: Long = 0L
        private set

    fun vnos() { vnosi++ }

    /** Lastni zapis zadnje uporabe paketov (TV 8. 11. 2026: statistika uporabe ni dostopna) - v pomnilniku procesa. */
    private val uporaba = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var zadnjiZagnan: String? = null

    /** Uporabnik je paket uporabljal do zdaj (odhod iz njega v Safeer Vnosu). */
    fun uporabljeno(paket: String?, casMs: Long = System.currentTimeMillis()) {
        if (paket.isNullOrBlank()) return
        if (uporaba.size > 200) uporaba.clear()
        uporaba[paket] = casMs
    }

    /** Zagon iz doma: paket je v rabi od zdaj; ob vrnitvi domov ([vrnitevDomov]) do takrat. */
    fun zagnano(namera: android.content.Intent?) {
        val p = namera?.`package` ?: namera?.component?.packageName ?: return
        zadnjiZagnan = p
        uporabljeno(p)
    }

    /** Ob vrnitvi domov: neigrajoce aplikacije se zaprejo takoj (lastnik 9. 11. 2026). */
    fun pocistiObVrnitviDomov(c: Context) =
        pocistiOzadje(c, cakajMs = OsPravila.VRNITEV_DOMOV_CAKAJ_MS, nedejavnostMs = OsPravila.VRNITEV_DOMOV_NEDEJAVNOST_MS, ponavljaj = true)

    /** Dom je spet na zaslonu: nazadnje zagnana aplikacija je bila v rabi do tega trenutka. */
    fun vrnitevDomov() { zadnjiZagnan?.let { uporabljeno(it) }; zadnjiZagnan = null }   // samo iz onResume doma (slepi pregled)
    @Volatile private var spremljam = false

    private fun spremljajOdhode(c: Context) {
        if (spremljam) return
        val app = c.applicationContext as? android.app.Application ?: return
        synchronized(this) {
            if (spremljam) return
            app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityPaused(a: android.app.Activity) { odhodi++ }
                override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
                override fun onActivityStarted(a: android.app.Activity) {}
                override fun onActivityResumed(a: android.app.Activity) {}
                override fun onActivityStopped(a: android.app.Activity) {}
                override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
                override fun onActivityDestroyed(a: android.app.Activity) {}
            })
            spremljam = true
        }
    }

    data class Stanje(val prostoMb: Int, val skupajMb: Int, val malo: Boolean, val stiskaZaSeje: Boolean = false)

    fun stanje(c: Context): Stanje {
        val am = c.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        try { am.getMemoryInfo(mi) } catch (_: Throwable) { }
        val prosto = (mi.availMem / 1_048_576).toInt()
        val skupaj = (mi.totalMem / 1_048_576).toInt()
        return Stanje(prosto, skupaj, OsPravila.maloPomnilnika(prosto, skupaj) || mi.lowMemory, OsPravila.stiskaZaSeje(prosto, mi.lowMemory))
    }

    /** Aplikacije z zaganjalnikom, ki jih smemo zapreti (ce tecejo v ozadju). */
    private fun kandidati(c: Context): List<String> {
        val pm = c.packageManager
        val ime = try { android.provider.Settings.Secure.getString(c.contentResolver, "default_input_method")
            ?.substringBefore('/') } catch (_: Throwable) { null }
        val paketi = LinkedHashSet<String>()
        for (k in listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            try { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(k), 0) } catch (_: Throwable) { emptyList() }
                .mapNotNullTo(paketi) { it.activityInfo?.packageName }
        }
        return paketi.filter { p ->
            val ai = try { pm.getApplicationInfo(p, 0) } catch (_: Throwable) { null } ?: return@filter false
            OsPravila.smemoZapreti(p, c.packageName, ime, (ai.flags and ApplicationInfo.FLAG_PERSISTENT) != 0)
        }
    }

    /**
     * Zapre [paketi]. Lastnik 1. 11. 2026: na TV (Android 11) killBackgroundProcesses tujih aplikacij ne zapre - z
     * uporabnikovim soglasjem gre zapiranje prek lastnega adb ([AdbLokalno], am force-stop), sicer kot doslej.
     * Nikoli na glavni niti (omrezje).
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun zapriPakete(c: Context, paketi: List<String>, vOspredju: Set<String>, ime: String?, nadaljuj: () -> Boolean = { true }): List<String> {
        if (paketi.isEmpty()) return emptyList()
        if (AdbLokalno.soglasje(c)) {
            // Sistemskih aplikacij televizorja ne zapiramo na silo (killBackgroundProcesses jih prej tudi ni).
            val pm = c.packageManager
            // Aplikacije z vklopljeno storitvijo (dostopnost, obvestila): force-stop bi jim storitev izklopil.
            val sStoritvijo = listOf("enabled_accessibility_services", "enabled_notification_listeners").flatMap { k ->
                AdbLokalnoPravila.paketiStoritev(try { android.provider.Settings.Secure.getString(c.contentResolver, k) } catch (_: Throwable) { null })
            }.toSet() + vpnPaketi(c)
            val dovoljeni = paketi.filter { p ->
                AdbLokalnoPravila.smemoZapreti(p, c.packageName, vOspredju, ime, sStoritvijo) &&
                    (try { (pm.getApplicationInfo(p, 0).flags and ApplicationInfo.FLAG_SYSTEM) == 0 } catch (_: Throwable) { false })
            }
            return AdbLokalno.zapri(c, dovoljeni, nadaljuj = nadaljuj)
        }
        val am = c.getSystemService(ActivityManager::class.java)
        val zaprti = ArrayList<String>()
        val vpn = vpnPaketi(c)
        // CodeRabbit 8. 11. 2026: tudi rezervna pot se ustavi, ko uporabnik pritisne tipko ali odpre aplikacijo.
        for (p in paketi) {
            if (!nadaljuj()) break
            if (p in vpn) continue
            try { am.killBackgroundProcesses(p); zaprti.add(p) } catch (_: Throwable) { }
        }
        return zaprti
    }

    /**
     * Aplikacije VPN (storitev android.net.VpnService): zaprtje bi uporabniku prekinilo zasciteno povezavo (preizkus na
     * tablici 13. 11. 2026: ciscenje ozadja je na seznam za zapiranje dalo NordVPN in AdGuard). Nikoli jih ne zapremo.
     */
    private fun vpnPaketi(c: Context): Set<String> = try {
        c.packageManager.queryIntentServices(Intent(android.net.VpnService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo?.packageName }.toSet()
    } catch (_: Throwable) { emptySet() }

    private fun tipkovnica(c: Context): String? = try {
        android.provider.Settings.Secure.getString(c.contentResolver, "default_input_method")?.substringBefore('/')
    } catch (_: Throwable) { null }

    /** Dovoljenje imajo samo okusi z zaganjalnikom (os, telefon, tablica); brskalnik in predvajalnik ga nimata. */
    private fun smemo(c: Context): Boolean =
        c.checkSelfPermission(android.Manifest.permission.KILL_BACKGROUND_PROCESSES) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Zapre aplikacije v ozadju. Vrne stevilo poskusov; meritev pred in po da [stanje]. Klic iz ozadja. */
    @android.annotation.SuppressLint("MissingPermission")
    fun sprosti(c: Context): Int {
        if (!smemo(c)) return 0
        val seznam = kandidati(c)
        val zaprti = zapriPakete(c, seznam, emptySet(), tipkovnica(c))
        Log.i(TAG, "sprosceno: ${zaprti.size} od ${seznam.size} aplikacij v ozadju")
        return zaprti.size
    }

    /** Ena aplikacija (telefonska po vrnitvi domov), samo ce je pomnilnika malo. */
    @android.annotation.SuppressLint("MissingPermission")
    fun zapriCeMalo(c: Context, paket: String) {
        if (!smemo(c) || !stanje(c).malo) return
        if (!OsPravila.smemoZapreti(paket, c.packageName, null, false)) return
        Thread { zapriPakete(c, listOf(paket), emptySet(), tipkovnica(c))
            Log.i(TAG, "telefonska aplikacija zaprta po vrnitvi domov (malo pomnilnika): $paket") }.start()
    }

    /** Zvok ob trenutku menjave (pred zamikom): po 3 s ze igra NOVA aplikacija (film) in prejsnja se ne bi nikoli zaprla. */
    private const val PREFS = "safeer_os"

    fun zvokAktiven(c: Context): Boolean = try {
        (c.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)?.isMusicActive == true
    } catch (_: Exception) { false }

    @android.annotation.SuppressLint("MissingPermission")   // dovoljenje preveri smemo(); brskalnik ga nima
    fun zapriObMenjavi(c: Context, prejsnja: String, nova: String?, casMs: Long, predvaja: Boolean,
                       vrnilSe: () -> Boolean = { false }, odhodiObMenjavi: Long = -1L) {
        val odhodiOd = if (odhodiObMenjavi >= 0) odhodiObMenjavi else odhodi
        if (!smemo(c)) return
        val vklopljeno = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("zapri_neuporabljeno", true)
        val ime = try { android.provider.Settings.Secure.getString(c.contentResolver, "default_input_method")?.substringBefore('/') } catch (_: Throwable) { null }
        val pm = c.packageManager
        val trajen = try { (pm.getApplicationInfo(prejsnja, 0).flags and ApplicationInfo.FLAG_PERSISTENT) != 0 } catch (_: Exception) { false }
        
        // Lastnik 8. 11. 2026 06:30: neigrajoca se zapre, igrajoca (znana seja predvajanja) ostane.
        val nedejavnost = OsPravila.nedejavnostNeigrajocih()
        val igraTuja = TujePredvajanje.igraPaket()
        // Slepi pregled + CodeRabbit: zvok ob menjavi pripisemo znani seji samo, ce ta ZDAJ igra, ni prejsnja aplikacija
        // in je en sam vir zvoka; sicer zvok (morda prejsnje aplikacije brez seje) zadrzi zapiranje kot doslej.
        val virov = try { (c.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)?.activePlaybackConfigurations?.size ?: 0 } catch (_: Throwable) { 0 }
        val zdaj = TujePredvajanje.igraZdaj()
        val igra = igraTuja == prejsnja || OsPravila.zvokUstaviVse(predvaja, zdaj != null && zdaj != prejsnja && virov <= 1)
        if (OsPravila.zapriObMenjavi(prejsnja, nova, casMs, c.packageName, ime, trajen, vklopljeno, igra, nedejavnost) && !vrnilSe() &&
            AdbLokalnoPravila.smemoNadaljevati(odhodiOd, odhodi)) {
            Thread {
                val pred = stanje(c)
                spremljajOdhode(c)
                // Ne zapri, ce se je uporabnik vrnil v aplikacijo ali ce je od menjave zapustil dom (odprl karkoli).
                val zaprti = zapriPakete(c, listOf(prejsnja), setOfNotNull(nova), ime) {
                    !vrnilSe() && AdbLokalnoPravila.smemoNadaljevati(odhodiOd, odhodi)
                }
                val po = stanje(c)
                Log.i(TAG, "zapiranje ob menjavi ($prejsnja -> $nova, ${casMs / 1000} s, zaprto=${zaprti.isNotEmpty()}), RAM pred: ${pred.prostoMb}MB, po: ${po.prostoMb}MB")
            }.start()
        }
    }

    /**
     * [nasePredvajanje]: klic ob zagonu NASEGA predvajanja (film, glasba v Safeer OS). Zvok, ki tece takrat, je nas, zato
     * ne sme ustaviti zapiranja (pregled 1. 11. 2026: po 3 s je isMusicActive vedno true in se ni zaprlo nic).
     * Zvok merimo PRED zamikom: ob vrnitvi domov pomeni, da igra ena od aplikacij v ozadju - takrat ne zapremo nicesar.
     */
    @android.annotation.SuppressLint("MissingPermission")   // dovoljenje preveri smemo(); brskalnik ga nima
    fun pocistiOzadje(c: Context, nasePredvajanje: Boolean = false, cakajMs: Long = 3000L, poskus: Int = 0, odhodiOd: Long = -1L,
                      nedejavnostMs: Long = OsPravila.nedejavnostNeigrajocih(), ponavljaj: Boolean = false, ustavljen: String? = null) {
        if (!smemo(c)) return
        spremljajOdhode(c)
        // Ponovitev ([poskus] > 0) velja samo, dokler uporabnik od PRVEGA poskusa ni odsel z doma.
        val odhodiObZacetku = if (odhodiOd >= 0L) odhodiOd else odhodi
        val vnosiObZacetku = vnosi
        val nadaljuj = { AdbLokalnoPravila.smemoNadaljevati(odhodiObZacetku, odhodi, vnosiObZacetku, vnosi) }
        val am = c.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        // Kdo igra in ali zvok tece. [ustavljen] (X v malem predvajalniku, slepi pregled 9. 11. 2026): ta paket je dobil stop,
        // a ob kliku se igra (isMusicActive, igraBrezPrikaza) - zato zanj merimo PO zamiku in ga ne stejemo kot igrajocega.
        val meri = {
            val igraTuja = OsPravila.igrajocRazenUstavljenega(TujePredvajanje.igraPaket(), ustavljen)
            // Slepi pregled: zvok iz aplikacije BREZ seje predvajanja (radio, igra) ob hkratni znani seji - vec virov zvoka kot
            // znanih sej -> kot doslej ustavi vse.
            val virov = try { am?.activePlaybackConfigurations?.size ?: 0 } catch (_: Throwable) { 0 }
            // CodeRabbit: zvok pojasni samo seja, ki ZDAJ igra (ne zascita premora), in samo, ce je en sam vir zvoka.
            val predvaja = !nasePredvajanje && OsPravila.zvokUstaviVse(try { am?.isMusicActive == true } catch (_: Exception) { false },
                OsPravila.igrajocRazenUstavljenega(TujePredvajanje.igraZdaj(), ustavljen) != null && virov <= 1)
            igraTuja to predvaja
        }
        val prej = if (ustavljen == null) meri() else null
        Thread {
            if (cakajMs > 0L) try { Thread.sleep(cakajMs) } catch (_: Exception) {}
            if (!nadaljuj()) {
                // Lastnik 9. 11. 2026 (FilmBox je ostal odprt): vsaka tipka je ciscenje preklicala za vedno. Ce je uporabnik
                // se doma (samo tipke, brez odhoda), poskusi znova po mirovanju; ob odhodu ali zagonu aplikacije ne.
                // Slepi pregled 9. 11.: samo klic z doma ([ponavljaj]) in z aplikacijskim kontekstom (ne drzi dejavnosti).
                if (OsPravila.ponoviCiscenje(ponavljaj, odhodiObZacetku, odhodi, poskus)) {
                    Log.i(TAG, "ciscenje ozadja odlozeno - uporabnik pritiska tipke (poskus ${poskus + 1})")
                    val ac = c.applicationContext
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        { pocistiOzadje(ac, nasePredvajanje, cakajMs, poskus + 1, odhodiObZacetku, nedejavnostMs, true, ustavljen) },
                        OsPravila.PONOVI_CISCENJE_MS)
                } else Log.i(TAG, "ciscenje ozadja preskoceno - uporabnik je odprl aplikacijo")
                return@Thread
            }
            var (igraTuja, predvaja) = prej ?: meri()
            // Slepi pregled 9. 11.: X - aplikacija zvok sprosti pocasneje (izmeri se enkrat po zamiku) ali stop prezre (igra
            // naprej: nic ne zapremo, mali predvajalnik se vrne).
            if (OsPravila.ponovnoIzmeriPoUstavitvi(ustavljen, predvaja)) {
                if (cakajMs > 0L) try { Thread.sleep(cakajMs) } catch (_: Exception) {}
                val ponovno = meri(); igraTuja = ponovno.first; predvaja = ponovno.second
                if (predvaja) {
                    Log.i(TAG, "ciscenje po X: $ustavljen se igra - ne zapremo, mali predvajalnik spet pokazan")
                    TujePredvajanje.osveziPoUstavitvi()
                    return@Thread
                }
            }
            val amgr = c.getSystemService(ActivityManager::class.java)
            val pred = stanje(c)
            // Lastnik 8. 11. 2026 06:30: neigrajoce aplikacije se zaprejo (3 s), igrajoca ostane in jo upravlja mali predvajalnik.
            val nedejavnost = nedejavnostMs
            val k = kandidati(c).toMutableList()
            // Lastnik 1. 11. 2026: tudi nase aplikacije (TV Browser, Play Store, Predvajalnik), ko jih ne uporabljamo vec.
            for (nas in listOf("si.safeer.tv", "si.safeer.trgovina", "si.safeer.player")) if (!k.contains(nas)) k.add(nas)
            val ime = try { android.provider.Settings.Secure.getString(c.contentResolver, "default_input_method")?.substringBefore('/') } catch (_: Throwable) { null }
            val pm = c.packageManager
            val zdaj = System.currentTimeMillis()
            val vklopljeno = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("zapri_neuporabljeno", true)
            val runningAppProcesses = amgr.runningAppProcesses
            val foregroundPackages = runningAppProcesses?.filter { it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }?.flatMap { it.pkgList.toList() } ?: emptyList()
            val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
            val stats = usm?.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, zdaj - 1000 * 60 * 60 * 24, zdaj)
            val statistikaNaVoljo = !stats.isNullOrEmpty()
            if (!statistikaNaVoljo) Log.i(TAG, "ciscenje ozadja: statistika uporabe ni dostopna - velja lastni zapis (${uporaba.size} paketov)")
            val zaZapreti = mutableListOf<String>()
            for (p in k) {
                if (foregroundPackages.contains(p)) continue
                if (p == igraTuja) { Log.i(TAG, "ciscenje ozadja: $p igra - ostane"); continue }
                // CodeRabbit 8. 11. 2026: queryUsageStats vrne lahko vec zapisov na paket (po dnevih) - vzemi zadnjo uporabo, ne prvega zadetka.
                val lastUsed = OsPravila.zadnjaUporaba(stats?.filter { it.packageName == p }?.maxOfOrNull { it.lastTimeUsed } ?: 0L,
                    uporaba[p] ?: 0L, statistikaNaVoljo, true, zdaj)   // neznana uporaba = neuporabljena (lastnik 06:30)
                val trajen = try { (pm.getApplicationInfo(p, 0).flags and ApplicationInfo.FLAG_PERSISTENT) != 0 } catch (_: Exception) { false }
                if (OsPravila.zapriObVrnitvi(p, c.packageName, ime, trajen, vklopljeno, predvaja, lastUsed, zdaj, nedejavnost)) zaZapreti.add(p)
            }
            val pocisceno = zapriPakete(c, zaZapreti, foregroundPackages.toSet(), ime, nadaljuj)
            val po = stanje(c)
            if (pocisceno.isNotEmpty()) Log.i(TAG, "zaprto v ozadju: ${pocisceno.joinToString(", ")}, RAM pred: ${pred.prostoMb}MB, po: ${po.prostoMb}MB")
        }.start()
    }
}
