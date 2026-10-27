package si.safeer.tv.os

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Ali Safeer OS prevzame domaci zaslon televizorja.
 *
 * Uporabnik ima dve poti in izbere sam:
 *  - **aplikacija** (privzeto): Safeer OS je navadna aplikacija v zaganjalniku televizorja;
 *    tipka Domov dela kot prej, Safeer OS pa odpres, kadar ga hoces - spletne aplikacije,
 *    datoteke, Link in Scit so v njem.
 *  - **domaci zaslon**: Safeer OS prevzame tipko Domov in je prvo, kar televizor pokaze.
 *
 * Tehnicno je to `activity-alias` s kategorijo HOME, ki je v manifestu **onemogocen**. Dokler ga
 * uporabnik ne vklopi, Android sploh ne ve, da bi Safeer OS lahko bil zaganjalnik, zato ga tudi
 * ne ponuja in nicesar ne vprasa. Ob izklopu ga spet onemogocimo in televizor se vrne na svoj
 * zaganjalnik - pot nazaj je zato vedno tu, tudi ce sistemskih nastavitev ni mogoce odpreti.
 */
object Zaganjalnik {
    private const val TAG = "SafeerOsZaganjalnik"
    private const val ALIAS = "si.safeer.tv.os.ZaganjalnikAlias"
    /**
     * Namizje na zunanjem zaslonu (SECONDARY_HOME, samo telefon in tablica): Android ga vzame iz paketa domacega zaslona,
     * zato je vklopljeno in izklopljeno skupaj z [ALIAS]. Na televizorju ga ni (nastavitev se tiho preskoci).
     */
    private const val ALIAS_NAMIZJE = "si.safeer.tv.os.NamizjeAlias"

    private fun komponenta(context: Context) = ComponentName(context.packageName, ALIAS)

    /** Ali je Safeer OS ponujen kot domaci zaslon (alias omogocen). */
    fun jePonujen(context: Context): Boolean = try {
        context.packageManager.getComponentEnabledSetting(komponenta(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } catch (e: Throwable) {
        Log.w(TAG, "Stanja ni bilo mogoce prebrati: ${e.message}"); false
    }

    /**
     * Ali je Safeer OS res domaci zaslon tega televizorja.
     *
     * Zanesljiv je samo dokaz iz prakse: ce nas je sistem kdaj zagnal z namero domacega zaslona
     * (CATEGORY_HOME), potem tipka Domov pride k nam. Razresevanje namere povemo samo kot drugo
     * moznost - Android 11+ aplikaciji pokaze predvsem njo samo, zato bi sama po sebi lahko
     * trdila, da je domaci zaslon, ceprav ga televizor pelje drugam.
     */
    fun jeIzbran(context: Context): Boolean {
        // Tablica, telefon (Android 10+): vloga HOME je zanesljiv odgovor - tudi ko uporabnik v sistemskih
        // nastavitvah izbere drug zaganjalnik. Televizorji vloge pogosto nimajo; tam velja dokaz iz prakse.
        vlogaNaVoljo(context)?.let { return it }
        if (nasZagonDomov(context)) return true
        val kdo = domaciZaslon(context)
        return kdo != null && kdo == context.packageName
    }

    /**
     * Naloga 67 (izmerjeno na TV 0.5.109: DomovActivity dvakrat - v opravilu domacega zaslona in v navadnem opravilu pod
     * ViriActivity): namera, s katero pridemo do EDINE DomovActivity, kadar je Safeer OS domaci zaslon televizorja; null,
     * ce ni (takrat velja izrecna namera kot doslej). DomovActivity je singleTask, a Android opravila domacega zaslona
     * (vrsta home) ne zdruzi z navadnim: izrecna namera (ikona v zaganjalniku, zagon ob vklopu, Domov v stranski
     * vrstici, vrnitev iz brskalnika) je vedno odprla drugo instanco v novem navadnem opravilu. Namera domacega zaslona
     * brez komponente (le paket) pa gre v opravilo domacega zaslona - tako kot tipka Domov.
     */
    fun nameraDomov(context: Context): Intent? {
        if (!jePonujen(context) || !jeIzbran(context)) return null
        // Pregled 70: sistem kaze drug zaganjalnik (uporabnik ga je izbral pozneje) - dokaz iz prakse je zastarel.
        domaciZaslon(context)?.let { kdo -> if (kdo != "android" && kdo != context.packageName) return null }
        val n = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(context.packageName)
        return try { if (context.packageManager.resolveActivity(n, 0) != null) n else null } catch (_: Throwable) { null }
    }

    /** Sistem nas je zagnal kot domaci zaslon: to si zapomnimo, ker je edini trden dokaz. */
    fun zabeleziZagonDomov(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KLJUC_DOMOV, true).apply()
    }

    private fun nasZagonDomov(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KLJUC_DOMOV, false)

    private const val PREFS = "safeer_os"
    private const val KLJUC_DOMOV = "zaganjalnik_zagon_domov"

    /**
     * Kdo dejansko dobi tipko Domov. Nekateri televizorji (Philips: org.droidtv.homeintentresolver,
     * Google TV: com.google.android.tvlauncher) imajo svoj sistemski prestreznik domacega zaslona
     * in ga tuji aplikaciji ne dajo, tudi ce je vloga HOME nasa. To moramo uporabniku povedati,
     * ne pa pustiti, da ugiba, zakaj se ob tipki Domov odpre televizorjev zaslon.
     */
    fun domaciZaslon(context: Context): String? = try {
        val namera = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(namera, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    } catch (e: Throwable) {
        Log.w(TAG, "Domacega zaslona ni bilo mogoce ugotoviti: ${e.message}"); null
    }

    /** Stanje za nastavitve: IZKLOPLJEN, TELEVIZOR_OBDRZI (vklopljeno, a televizor ne da) ali IZBRAN. */
    fun stanje(context: Context): String = when {
        jeIzbran(context) -> IZBRAN
        jePonujen(context) -> TELEVIZOR_OBDRZI
        else -> IZKLOPLJEN
    }

    const val IZKLOPLJEN = "izklopljen"
    const val TELEVIZOR_OBDRZI = "televizor_obdrzi"
    const val IZBRAN = "izbran"

    /**
     * Ponudi Safeer OS kot domaci zaslon. Izbira ostane sistemska: mi samo omogocimo moznost,
     * uporabnik pa jo potrdi v sistemskem oknu. Vrne true, ce je bilo okno odprto.
     */
    fun ponudi(context: Context): Boolean {
        nastavi(context, true)
        return odpriSistemskoIzbiro(context)
    }

    /** Ali je izklop domacega zaslona varen: obstaja drug pravi zaganjalnik (sicer bi ostal crn FallbackHome). */
    fun smeOpustiti(context: Context): Boolean = ZaganjalnikPravila.smeOpustiti(drugiZaganjalniki(context).size)

    /** Vrni domaci zaslon televizorju: alias onemogocimo in sistem vzame svoj zaganjalnik. */
    fun opusti(context: Context) {
        nastavi(context, false)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KLJUC_DOMOV).apply()
    }

    private fun nastavi(context: Context, omogocen: Boolean) {
        val stanje = if (omogocen) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        try {
            context.packageManager.setComponentEnabledSetting(komponenta(context), stanje, PackageManager.DONT_KILL_APP)
            Log.i(TAG, "Safeer OS kot domaci zaslon: ${if (omogocen) "ponujen" else "opuscen"}")
        } catch (e: Throwable) {
            Log.w(TAG, "Nastavitve ni bilo mogoce spremeniti: ${e.message}")
        }
        // Namizje na zunanjem zaslonu: le v okusih na dotik (na televizorju komponente ni - to ni napaka).
        try {
            context.packageManager.setComponentEnabledSetting(ComponentName(context.packageName, ALIAS_NAMIZJE), stanje, PackageManager.DONT_KILL_APP)
        } catch (_: Throwable) { }
        // Varnostna vrata: padci pred tem trenutkom ne stejejo vec (ob vklopu in ob vrnitvi zacnemo znova).
        vrata(context).edit().putLong(KLJUC_OD, System.currentTimeMillis()).remove(KLJUC_PADCI).apply()
    }

    /**
     * Izhod iz lupine: pokazi domaci zaslon televizorja (Android), ne da bi karkoli spreminjali.
     * Uporabnik, ki hoce navaden Android, ga dobi takoj - tudi kadar je Safeer OS izbran domaci
     * zaslon. Trajno se odloci z izklopom zgoraj. Vrne true, ce je zaganjalnik odprt.
     */
    fun izhodVAndroid(context: Context): Boolean {
        val namera = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val najdeni = try {
            context.packageManager.queryIntentActivities(namera, 0)
        } catch (e: Throwable) {
            Log.w(TAG, "Zaganjalnikov ni bilo mogoce presteti: ${e.message}"); emptyList()
        }
        for (r in najdeni) {
            val info = r.activityInfo ?: continue
            if (info.packageName == context.packageName) continue
            try {
                context.startActivity(Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setComponent(ComponentName(info.packageName, info.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Throwable) {
                Log.w(TAG, "Zaganjalnika ${info.packageName} ni bilo mogoce odpreti: ${e.message}")
            }
        }
        return false
    }

    // ------------------------------------------------------------------ tablica in telefon (Android 10+: vloga HOME)

    /** Ali drzimo vlogo HOME; null, kadar sistem vloge nima (televizor, Android pod 10). */
    private fun vlogaNaVoljo(context: Context): Boolean? = try {
        if (android.os.Build.VERSION.SDK_INT < 29 || context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) null else {
            val rm = context.getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME) else null
        }
    } catch (_: Throwable) { null }

    /** Koda zahteve za sistemsko okno vloge domacega zaslona (onActivityResult). */
    const val ZAHTEVA_VLOGE = 7411

    /**
     * Uporabnik je izbral »Safeer OS kot domaci zaslon«: alias omogocimo in odpremo sistemsko vprasanje.
     * Android 10+: okno vloge (en dotik »Nastavi kot privzeto«), sicer sistemske nastavitve domacega zaslona.
     * Odlocitev je vedno uporabnikova in sistemska; nikoli je ne naredimo sami.
     */
    fun zahtevajVlogo(a: android.app.Activity): Boolean {
        // Varnostna vrata: zapomnimo si, kateri zaganjalnik je bil domaci zaslon pred nami - vanj se vrnemo z enim dotikom.
        domaciZaslon(a)?.takeIf { it != a.packageName && it != "android" }?.let { vrata(a).edit().putString(KLJUC_PREJSNJI, it).apply() }
        nastavi(a, true)
        if (android.os.Build.VERSION.SDK_INT >= 29) try {
            val rm = a.getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                if (rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) return true
                @Suppress("DEPRECATION")
                a.startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), ZAHTEVA_VLOGE)
                return true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Okna vloge ni bilo mogoce odpreti: ${e.message}")
        }
        return odpriSistemskoIzbiro(a)
    }

    /** Pravi zaganjalniki razen nas (brez sistemskega FallbackHome) - kam se lahko uporabnik vrne. */
    fun drugiZaganjalniki(context: Context): List<android.content.pm.ResolveInfo> = try {
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .filter { r ->
                val i = r.activityInfo ?: return@filter false
                ZaganjalnikPravila.jePraviZaganjalnik(i.packageName, i.name, r.priority, context.packageName)
            }
    } catch (_: Throwable) { emptyList() }

    // ------------------------------------------------------------------ zascita pred padci

    private const val KLJUC_PADCI = "zaganjalnik_padci"
    private const val KLJUC_VRNJEN = "zaganjalnik_vrnjen_po_padcu"
    private const val KLJUC_OD = "padci_stejejo_od"
    private const val KLJUC_PREJSNJI = "prejsnji_zaganjalnik"

    /**
     * Varnostna vrata imajo svoje nastavitve (lastnik, 25. 10. 2026): zapis padcev ni v isti datoteki kot vse drugo, kar
     * domaci zaslon med delom pise - pokvarjena ali prevelika glavna datoteka ne sme onemogociti vrnitve.
     */
    private fun vrata(c: Context) = c.applicationContext.getSharedPreferences("safeer_varnostna_vrata", Context.MODE_PRIVATE)
    @Volatile private var zascitaNamescena = false

    /**
     * Ce Safeer OS kot domaci zaslon pade dvakrat v minuti, uporabnik ne sme ostati ujet v zanki »se je ustavil«:
     * alias domacega zaslona onemogocimo in tipka Domov spet pripelje do prejsnjega zaganjalnika (One UI Home ...).
     * Brez drugega zaganjalnika (televizor) ostanemo - sistem nas zazene znova. Ob naslednjem odprtju Safeer OS
     * uporabniku povemo, kaj se je zgodilo, in ponudimo ponovno nastavitev ([vrnjenPoPadcu]).
     */
    fun namestiZascito(context: Context) {
        if (zascitaNamescena) return
        zascitaNamescena = true
        val app = context.applicationContext
        val prej = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { nit, napaka ->
            try { zabeleziPadec(app) } catch (_: Throwable) { }
            // Izmerjeno (WP28 S, 15. 10. 2026): po padcu je proces visel minute (sistemsko javljanje padca ni vrnilo),
            // zaslon pa je ostal zamrznjen. Domaci zaslon tega ne sme: najvec 3 s po padcu se proces konca sam, sistem
            // pa zazene domaci zaslon znova (ali prejsnji zaganjalnik, ce smo ga zgoraj vrnili).
            Thread({
                try { Thread.sleep(3_000) } catch (_: Throwable) { }
                android.os.Process.killProcess(android.os.Process.myPid())
            }, "SafeerPadecKonec").apply { isDaemon = true }.start()
            if (prej != null) prej.uncaughtException(nit, napaka) else {
                android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10)
            }
        }
    }

    @android.annotation.SuppressLint("ApplySharedPref")   // namenoma commit: proces se takoj po tem konca
    private fun zabeleziPadec(app: Context) {
        val v = vrata(app)
        val zdaj = System.currentTimeMillis()
        val casi = ZaganjalnikPravila.svezi(lastniPadci(app) + zdaj, zdaj)
        // commit: proces se takoj konca, apply ne bi utegnil zapisati.
        v.edit().putString(KLJUC_PADCI, casi.joinToString(",")).commit()
        val smoDomaci = jePonujen(app)
        val stejejo = ZaganjalnikPravila.padciPo(casi, v.getLong(KLJUC_OD, 0L))
        if (smoDomaci && ZaganjalnikPravila.vrniPrejsnjemu(stejejo, zdaj, drugiZaganjalniki(app).size)) {
            Log.w(TAG, "Domaci zaslon je padel ${stejejo.size}x v kratkem: vracam ga prejsnjemu zaganjalniku")
            vrniPoPadcih(app)
        }
    }

    private fun lastniPadci(c: Context): List<Long> =
        (vrata(c).getString(KLJUC_PADCI, "") ?: "").split(',').mapNotNull { it.toLongOrNull() }

    @android.annotation.SuppressLint("ApplySharedPref")   // namenoma commit: lahko se proces takoj konca
    private fun vrniPoPadcih(app: Context) {
        nastavi(app, false)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KLJUC_DOMOV).putBoolean(KLJUC_VRNJEN, true).commit()
        vrata(app).edit().remove(KLJUC_PADCI).putLong(KLJUC_OD, System.currentTimeMillis()).commit()
    }

    /**
     * Padci glavnega procesa iz sistemskega zapisa izhodov (Android 11+). Ta zapis je zunaj nasega procesa, zato vidi
     * tudi, cesar nas lovilec ne more: padec v kodi C/C++, ANR (zamrznjen zaslon, ki ga sistem ubije) in padec, preden
     * se zaganjalnik sploh zazene. Uporabnikov izhod ali posodobitev se ne steje.
     */
    private fun sistemskiPadci(c: Context): List<Long> {
        if (android.os.Build.VERSION.SDK_INT < 30) return emptyList()
        return try {
            val am = c.getSystemService(android.app.ActivityManager::class.java) ?: return emptyList()
            val razlogi = setOf(android.app.ApplicationExitInfo.REASON_CRASH, android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
                android.app.ApplicationExitInfo.REASON_ANR, android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)
            am.getHistoricalProcessExitReasons(c.packageName, 0, 16)
                .filter { it.reason in razlogi && it.processName == c.packageName }
                .map { it.timestamp }
        } catch (e: Throwable) { Log.w(TAG, "Zapisa izhodov ni bilo mogoce prebrati: ${e.message}"); emptyList() }
    }

    /**
     * Varnostna vrata ob vsakem zagonu domacega zaslona: ce je bilo od zadnjega vklopa padcev prevec (lasten zapis +
     * sistemski zapis izhodov), domaci zaslon vrnemo prejsnjemu zaganjalniku in ga takoj odpremo. Vrne true, ce je bil
     * vrnjen - klicatelj naj se konca.
     */
    fun preveriObZagonu(context: Context): Boolean = try {
        preveri(context)
    } catch (t: Throwable) {
        // Grok 8, Z2: izjema v samih vratih bi sesula vsak zagon domacega zaslona (zanka) - takrat so vrata sama znak okvare:
        // domaci zaslon gre prejsnjemu zaganjalniku, ce obstaja, sicer ostanemo (brez njega bi ostal crn FallbackHome).
        Log.w(TAG, "Varnostna vrata: izjema ${t.javaClass.simpleName} - vracam domaci zaslon")
        try {
            if (drugiZaganjalniki(context).isNotEmpty()) { vrniPoPadcih(context.applicationContext); odpriTovarniskega(context); true } else false
        } catch (_: Throwable) { false }
    }

    private fun preveri(context: Context): Boolean {
        if (!jePonujen(context)) return false
        val zdaj = System.currentTimeMillis()
        val od = try { vrata(context).getLong(KLJUC_OD, 0L) } catch (_: ClassCastException) { 0L }
        val vsi = ZaganjalnikPravila.padciPo(
            ZaganjalnikPravila.zdruziPadce(lastniPadci(context), sistemskiPadci(context)), od, zdaj)
        if (!ZaganjalnikPravila.vrniPrejsnjemu(vsi, zdaj, drugiZaganjalniki(context).size)) return false
        Log.w(TAG, "Varnostna vrata: ${vsi.size} padcev od vklopa - domaci zaslon gre nazaj prejsnjemu zaganjalniku")
        vrniPoPadcih(context.applicationContext)
        odpriTovarniskega(context)
        return true
    }

    /**
     * Varnostna vrata, en dotik (Nastavitve, dolg pritisk na namizje, bliznjica ikone, ploscica hitrih nastavitev):
     * Safeer ni vec domaci zaslon in prejsnji zaganjalnik se takoj odpre.
     */
    fun vrniTovarniskega(context: Context): Boolean {
        opusti(context)
        return odpriTovarniskega(context)
    }

    /** Odpre zaganjalnik, ki je bil domaci zaslon pred Safeerjem (sicer prvi pravi zaganjalnik naprave). */
    fun odpriTovarniskega(context: Context): Boolean {
        val prejsnji = vrata(context).getString(KLJUC_PREJSNJI, null)
        val drugi = drugiZaganjalniki(context).sortedBy { if (it.activityInfo?.packageName == prejsnji) 0 else 1 }
        for (r in drugi) {
            val info = r.activityInfo ?: continue
            try {
                context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setComponent(ComponentName(info.packageName, info.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Throwable) { Log.w(TAG, "Zaganjalnika ${info.packageName} ni bilo mogoce odpreti: ${e.message}") }
        }
        return izhodVAndroid(context)
    }

    /** Domaci zaslon je bil po padcih vrnjen prejsnjemu zaganjalniku (enkratno obvestilo; prebere in pocisti). */
    fun vrnjenPoPadcu(context: Context): Boolean {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(KLJUC_VRNJEN, false)) return false
        p.edit().remove(KLJUC_VRNJEN).apply()
        return true
    }

    /** Sistemsko okno za izbiro domacega zaslona; nekateri televizorji ga nimajo. */
    fun odpriSistemskoIzbiro(context: Context): Boolean {
        for (dejanje in listOf(Settings.ACTION_HOME_SETTINGS, Settings.ACTION_SETTINGS)) {
            try {
                context.startActivity(Intent(dejanje).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Throwable) { }
        }
        Log.w(TAG, "Sistemskih nastavitev za domaci zaslon ni mogoce odpreti")
        return false
    }
}
