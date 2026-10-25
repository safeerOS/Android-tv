package si.safeer.tv.os

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import si.safeer.tv.R
import si.safeer.tv.os.NamestitevPravila.Izvor
import si.safeer.tv.os.NamestitevPravila.Odziv
import si.safeer.tv.os.NamestitevPravila.Podpis
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Namestitev APK prek seje PackageInstaller, z rezervo v sistemski namestitelj (ACTION_VIEW). Zakaj in kateri vir
 * povemo Androidu: [NamestitevPravila]. Uporabljata jo [Posodobitve] (posodobitev iz safeer.si) in [NamestiApk].
 *
 * Varnost: APK je v zasebni mapi aplikacije (cacheDir). Vsoto SHA-256 izracunamo na bajtih, ki jih pisemo v sejo, in
 * sejo pred commit opustimo, ce se ne ujema s preverjeno vsoto (posodobitev: os/razlicice.json; NamestiApk: vsota ob
 * prenosu, ko je uporabnik videl ime in paket). setAppPackageName: Android zavrne APK z drugim paketom, kot ga je
 * uporabnik potrdil.
 */
object NamestitevSeja {
    private const val OZNAKA = "SafeerNamestitev"
    private const val MIME = "application/vnd.android.package-archive"
    private const val PREFS = "safeer_namestitev_seja"
    private const val SAMOPOSODOBITEV = "samoposodobitev"
    /** Kako dolgo po oddaji seje se Safeer po posodobitvi sam odpre (uporabnik je morda potrjeval v oknu Androida). */
    private const val ODPRI_MS = 15 * 60_000L

    /** Ena namestitev: kaj namescamo in kam se vrnemo, ce seja ne dela. */
    private class Zahteva(
        val dejavnost: WeakReference<Activity>,
        val datoteka: File,
        val paket: String,
        val ime: String,
        val izvor: Izvor,
        val rezerva: (Activity) -> Unit
    )

    private val cakajo = ConcurrentHashMap<Int, Zahteva>()

    fun log(s: String) = Log.i(OZNAKA, s)

    /** Odtisi SHA-256 potrdil, s katerimi je podpisan ta Safeer. */
    fun nasiPodpisniki(ctx: Context): List<String> = try {
        odtisi(ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES))
    } catch (_: Throwable) { emptyList() }

    /** Odtisi SHA-256 potrdil APK v datoteki; prazno, ce jih Android ne da. */
    fun podpisnikiApk(ctx: Context, f: File): List<String> = try {
        odtisi(ctx.packageManager.getPackageArchiveInfo(f.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES))
    } catch (_: Throwable) { emptyList() }

    private fun odtisi(pi: PackageInfo?): List<String> {
        val si = pi?.signingInfo ?: return emptyList()
        // Trenutni podpisniki (brez potrdil iz zgodovine zamenjave kljuca): za posodobitev mora biti podpis isti.
        val potrdila = si.apkContentsSigners ?: return emptyList()
        return potrdila.map { NamestitevPravila.hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
    }

    fun podpis(ctx: Context, f: File): Podpis = NamestitevPravila.podpis(nasiPodpisniki(ctx), podpisnikiApk(ctx, f))

    /**
     * Pravila (NamestitevPravila, preizkus brez Androida) -> parametri seje. Stevilke so enake konstantam Androida (preizkus
     * jih preveri); preslikava z imeni konstant, da lint vidi dovoljene vrednosti.
     */
    @SuppressLint("WrongConstant")
    private fun parametri(paket: String, velikost: Long, izvor: Izvor, podpis: Podpis, vir: Int): PackageInstaller.SessionParams {
        val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        p.setAppPackageName(paket)
        p.setSize(velikost)
        p.setInstallReason(PackageManager.INSTALL_REASON_USER)
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(
            if (NamestitevPravila.zahtevaUporabnika(izvor, podpis) == NamestitevPravila.UPORABNIK_NI_POTREBEN)
                PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED else PackageInstaller.SessionParams.USER_ACTION_UNSPECIFIED)
        if (Build.VERSION.SDK_INT >= 33) p.setPackageSource(when (vir) {
            NamestitevPravila.VIR_DRUGO -> PackageInstaller.PACKAGE_SOURCE_OTHER
            NamestitevPravila.VIR_DATOTEKA -> PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
            else -> PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
        })
        return p
    }

    /**
     * Namesti [f] (paket [paket], ime za sporocila [ime]) prek seje. [vsota] je ze preverjena vsota SHA-256 datoteke; v
     * sejo gre samo, ce se ujema z bajti, ki jih pisemo. [rezerva] se pozene na glavni niti, ce seje na tej napravi ni
     * mogoce uporabiti (stara pot: sistemski namestitelj). Klic z glavne niti; pisanje v sejo tece v svoji niti.
     */
    fun namesti(a: Activity, f: File, vsota: String, paket: String, ime: String, izvor: Izvor, rezerva: (Activity) -> Unit) {
        val app = a.applicationContext
        val glavna = Handler(Looper.getMainLooper())
        val zahteva = Zahteva(WeakReference(a), f, paket, ime, izvor, rezerva)
        Thread({
            var id = -1
            var oddano = false
            val pi = app.packageManager.packageInstaller
            // Branje podpisa razcleni APK (desetine MB): v tej niti, ne na glavni.
            val podpis = podpis(app, f)
            val vir = NamestitevPravila.virPaketa(izvor, podpis)
            if (vir == null) {
                log("$paket: podpis APK ni Safeerjev - posodobitve ne namestimo")
                f.delete()
                glavna.post { sporociNapako(app, izvor, ime) }
                return@Thread
            }
            try {
                id = pi.createSession(parametri(paket, f.length(), izvor, podpis, vir))
                pi.openSession(id).use { s ->
                    val dobljena = f.inputStream().use { v ->
                        s.openWrite("base.apk", 0, f.length()).use { o ->
                            val h = NamestitevPravila.prepisi(v, o)
                            s.fsync(o)
                            h
                        }
                    }
                    if (!NamestitevPravila.enakaVsota(dobljena, vsota)) {
                        // Datoteka se je od preverbe spremenila: ne namestimo (in ne gremo na staro pot).
                        log("$paket: vsota v seji $dobljena ni preverjena $vsota - seja opuscena")
                        s.abandon()
                        f.delete()
                        glavna.post { sporociNapako(app, izvor, ime) }
                        return@Thread
                    }
                    cakajo[id] = zahteva
                    val namen = Intent(app, Prejemnik::class.java).setPackage(app.packageName)
                    // FLAG_MUTABLE: stanje (EXTRA_STATUS, EXTRA_INTENT) v namen vpise sistem. Namen je ekspliciten (razred).
                    val zastavice = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                        else PendingIntent.FLAG_UPDATE_CURRENT
                    log("$paket: seja $id oddana (vir $vir, izvor $izvor, podpis $podpis, sdk ${Build.VERSION.SDK_INT})")
                    // Posodobitev sebe: Android ob namestitvi konca nas proces in okna »Odpri« (kot pri sistemskem
                    // namestitelju) ni - [PoPosodobitvi] Safeer odpre znova.
                    if (paket == app.packageName) zapomniSamoposodobitev(app)
                    s.commit(PendingIntent.getBroadcast(app, id, namen, zastavice).intentSender)
                    oddano = true
                }
            } catch (t: Throwable) {
                // Po oddaji odloca stanje seje (Prejemnik), sicer bi rezerva namestila se enkrat.
                if (oddano) { log("$paket: seja $id oddana, izjema ob zapiranju: $t"); return@Thread }
                log("$paket: seje ni bilo mogoce uporabiti ($t) - sistemski namestitelj")
                cakajo.remove(id)
                if (id >= 0) try { pi.abandonSession(id) } catch (_: Throwable) { }
                glavna.post { zahteva.dejavnost.get()?.takeIf { !it.isFinishing }?.let { rezerva(it) } }
            }
        }, "safeer-namestitev-seja").start()
    }

    /** Stara pot (rezerva): APK predamo sistemskemu namestitelju prek FileProvider (cache_files/[mapa]/ime). */
    fun sistemskiNamestitelj(a: Activity, mapa: String, f: File): Boolean {
        val uri = Uri.parse("content://" + a.packageName + ".fileprovider/cache_files/" + mapa + "/" + Uri.encode(f.name))
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // Naravnost v sistemski namestitelj (brez izbirnika, ce se za APK prijavi se kaksna aplikacija, npr. Termux).
        try {
            a.packageManager.queryIntentActivities(i, PackageManager.MATCH_SYSTEM_ONLY)
                .firstOrNull()?.activityInfo?.let { i.setClassName(it.packageName, it.name) }
        } catch (_: Throwable) { }
        return try { a.startActivity(i); true } catch (t: Throwable) { log("sistemski namestitelj: $t"); false }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** commit() namenoma (ne apply()): Android nas proces konca takoj po namestitvi, zapis mora biti prej na disku. */
    @SuppressLint("ApplySharedPref")
    private fun zapomniSamoposodobitev(ctx: Context) {
        prefs(ctx).edit().putLong(SAMOPOSODOBITEV, System.currentTimeMillis()).commit()
    }

    private fun sporociNapako(ctx: Context, izvor: Izvor, ime: String) {
        val b = if (izvor == Izvor.POSODOBITEV) ctx.getString(R.string.os_posodobitev_napaka)
            else ctx.getString(R.string.apk_ni_namescena, ime)
        Toast.makeText(ctx.applicationContext, b, Toast.LENGTH_LONG).show()
    }

    /** Okno Androida za potrditev mora biti sistemsko (namen pride od sistema, a preverimo, kam bi ga poslali). */
    private fun jeSistemsko(ctx: Context, i: Intent): Boolean = try {
        val r = ctx.packageManager.resolveActivity(i, PackageManager.MATCH_SYSTEM_ONLY)?.activityInfo
        r != null && (r.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (_: Throwable) { false }

    @Suppress("DEPRECATION")
    private fun potrditev(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else i.getParcelableExtra(Intent.EXTRA_INTENT)

    /** Stanje seje od sistema (samo prek PendingIntent - prejemnik ni izvozen). */
    class Prejemnik : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
            val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            val sporocilo = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
            val z = cakajo[id]
            val paket = z?.paket ?: intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "?"
            log("$paket: seja $id stanje $status $sporocilo")
            val odziv = NamestitevPravila.odziv(status)
            // Posodobitev sebe ni uspela (ali gre na staro pot z oknom »Odpri«): Safeerja po njej ne odpiramo sami.
            if (paket == context.packageName && odziv != Odziv.POTRDITEV && odziv != Odziv.USPEH) prefs(context).edit().remove(SAMOPOSODOBITEV).apply()
            when (odziv) {
                Odziv.POTRDITEV -> {
                    val p = potrditev(intent)
                    // Okno Androida odpremo iz nase dejavnosti, ce je se odprta (zagon iz ozadja Android lahko zavrne).
                    val od: Context = z?.dejavnost?.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: context
                    if (p != null && jeSistemsko(context, p)) {
                        try { od.startActivity(p.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (t: Throwable) { log("potrditev: $t") }
                    } else log("potrditev: namen ni sistemski - ne odpremo")
                    // Okna potrditve ni bilo mogoce odpreti: opusti sejo in pojdi na staro pot.
                    try { context.packageManager.packageInstaller.abandonSession(id) } catch (_: Throwable) { }
                    cakajo.remove(id)
                    z?.dejavnost?.get()?.takeIf { !it.isFinishing }?.let { z.rezerva(it) }
                }
                Odziv.USPEH -> {
                    cakajo.remove(id); z?.datoteka?.delete()
                    if (z != null && z.paket != context.packageName) namesceno(context, z)
                }
                Odziv.PREKLIC -> { cakajo.remove(id); z?.datoteka?.delete() }
                Odziv.STARA_POT -> {
                    cakajo.remove(id)
                    val a = z?.dejavnost?.get()?.takeIf { !it.isFinishing }
                    if (z != null && a != null) z.rezerva(a) else z?.let { sporociNapako(context, it.izvor, it.ime) }
                }
                Odziv.NAPAKA -> {
                    cakajo.remove(id); z?.datoteka?.delete()
                    if (z != null) sporociNapako(context, z.izvor, z.ime)
                }
            }
        }

        /** Seja ne pokaze »Aplikacija je namescena / Odpri« kot sistemski namestitelj - to naredimo mi. */
        private fun namesceno(ctx: Context, z: Zahteva) {
            val besedilo = ctx.getString(R.string.apk_namescena, z.ime)
            val a = z.dejavnost.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
            val zagon = ctx.packageManager.getLaunchIntentForPackage(z.paket) ?: ctx.packageManager.getLeanbackLaunchIntentForPackage(z.paket)
            if (a == null) { Toast.makeText(ctx.applicationContext, besedilo, Toast.LENGTH_LONG).show(); return }
            val b = AlertDialog.Builder(a).setTitle(R.string.apk_naslov).setMessage(besedilo).setNegativeButton(android.R.string.ok, null)
            if (zagon != null) b.setPositiveButton(R.string.os_spor_app_odpri) { _, _ ->
                try { a.startActivity(zagon.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
            }
            b.show()
        }
    }

    /**
     * Po posodobitvi sebe prek seje (MY_PACKAGE_REPLACED): Safeer se odpre znova, kot bi uporabnik pritisnil »Odpri« v
     * sistemskem namestitelju. Samo, ce je posodobitev v zadnjih [ODPRI_MS] sprozil Safeer sam (zapis v [namesti]), ne
     * ob posodobitvi iz trgovine ali adb. Zagon iz ozadja Android dovoli aplikaciji z dovoljenjem »Prikaz cez druge
     * aplikacije« (izmerjeno 25. 10. 2026, WP28 S Android 14: »Background activity start ... allowed because
     * SYSTEM_ALERT_WINDOW permission is granted«, Safeer spet na zaslonu 2 s po oddaji seje); brez njega ga Android
     * zavrne in uporabnik Safeer odpre sam (to pove obvestilo ob namestitvi, Posodobitve.prenesiInNamesti).
     */
    class PoPosodobitvi : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            val p = prefs(context)
            val cas = p.getLong(SAMOPOSODOBITEV, 0L)
            if (cas == 0L) return
            p.edit().remove(SAMOPOSODOBITEV).apply()
            val pred = System.currentTimeMillis() - cas
            if (pred < 0 || pred > ODPRI_MS) { log("po posodobitvi: zapis star ${pred / 1000} s - ne odpremo"); return }
            val pm = context.packageManager
            val zagon = pm.getLaunchIntentForPackage(context.packageName) ?: pm.getLeanbackLaunchIntentForPackage(context.packageName)
            if (zagon == null) { log("po posodobitvi: ni zaganjalnika"); return }
            try {
                context.startActivity(zagon.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                log("po posodobitvi: odpiram ${zagon.component?.className} (${pred / 1000} s po oddaji)")
            } catch (t: Throwable) { log("po posodobitvi: $t") }
        }
    }
}
