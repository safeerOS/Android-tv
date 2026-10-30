package si.safeer.tv.os

import si.safeer.tv.R

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import java.io.File

/** Aplikacije na televizorju, kot jih vidi zaganjalnik (LEANBACK_LAUNCHER), brez te aplikacije (brskalnik + Safeer OS). */
object Aplikacije {
    data class Vnos(val paket: String, val ime: String, val ikona: Drawable?, val namera: Intent,
                    val info: ActivityInfo? = null)

    /**
     * Kot jih nasteje sistem - v tem vrstnem redu jih ima televizor sam (namescanje). To vzamemo
     * za privzeti vrstni red priljubljenih, da domaci zaslon Safeer OS zacne tam, kjer je
     * uporabnik ze doma; naprej si jih razvrsti sam.
     */
    fun sistemskiVrstniRed(context: Context): List<Vnos> = najdi(context)

    fun seznam(context: Context): List<Vnos> = najdi(context).also { shrani(context, it) }.sortedBy { it.ime.lowercase() }

    /**
     * Zadnji znani seznam z diska (paket, ime, razred), po abecedi in brez ikon: pokaze se takoj, ob odprtju
     * zaslona. Ikone dobi [ikonaLeno]; svez seznam prinese [osveziVOzadju]. null = seznama se nimamo.
     */
    fun zadnjiZnani(context: Context): List<Vnos>? {
        val zapisi = AplikacijePredpomnilnik.razcleni(try { datoteka(context).readText() } catch (_: Throwable) { null })
            ?: return null
        val tv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return zapisi.filter { it.paket != context.packageName }
            .map { Vnos(it.paket, it.ime, null, zagonskaNamera(ComponentName(it.paket, it.razred), tv, brezKategorije = true)) }
            .sortedBy { it.ime.lowercase() }
    }

    private fun zagonskaNamera(c: ComponentName, tv: Boolean, brezKategorije: Boolean = false): Intent =
        Intent(Intent.ACTION_MAIN).apply {
            if (!brezKategorije) addCategory(if (tv) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
        }.setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun datoteka(c: Context) = File(c.filesDir, "aplikacije_zadnji_seznam.txt")

    private fun zapisi(vnosi: List<Vnos>): List<AplikacijePredpomnilnik.Zapis> = vnosi.mapNotNull { v ->
        v.namera.component?.let { AplikacijePredpomnilnik.Zapis(v.paket, v.ime, it.className) }
    }

    @Synchronized private fun shrani(c: Context, vnosi: List<Vnos>) {
        val novi = zapisi(vnosi)
        if (novi.isEmpty()) return      // prazen izpad poizvedbe ne sme pobrisati dobrega seznama
        try {
            val stari = AplikacijePredpomnilnik.razcleni(try { datoteka(c).readText() } catch (_: Throwable) { null })
            if (stari != null && AplikacijePredpomnilnik.enako(stari, novi)) return
            val cilj = datoteka(c)
            val zacasna = File(cilj.path + ".tmp")
            zacasna.writeText(AplikacijePredpomnilnik.kodiraj(novi))
            if (!zacasna.renameTo(cilj)) { cilj.delete(); zacasna.renameTo(cilj) }
        } catch (e: Throwable) { android.util.Log.w("SafeerAplikacije", "Seznama ni mogoce shraniti: ${e.message}") }
    }

    @Volatile private var osvezujem = false
    @Volatile private var zadnjeOsvezevanje = 0L

    /**
     * Svez seznam preberemo v ozadju (nizka prednost). [naSpremembo] dobi glavna nit samo, ce se seznam
     * razlikuje od [prikazan]. Klici, ki prihajajo drug za drugim (vsak izris vrste), se zdruzijo.
     */
    /** [brezIkon]: seznam brez ikon (namizje zaganjalnika ima svoje majhne - velike ikone vseh aplikacij so desetine MB). */
    fun osveziVOzadju(context: Context, prikazan: List<Vnos>?, brezIkon: Boolean = false, naSpremembo: (List<Vnos>) -> Unit) {
        val app = context.applicationContext
        val zdaj = android.os.SystemClock.elapsedRealtime()
        if (osvezujem || zdaj - zadnjeOsvezevanje < 2000L) return
        osvezujem = true
        prijaviSprejemnik(app)
        val prikazanoZapisi = prikazan?.let { zapisi(it).sortedBy { z -> z.ime.lowercase() } }
        Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val svez = najdi(app, brezIkon).sortedBy { it.ime.lowercase() }
                shrani(app, svez)
                if (prikazanoZapisi == null || !AplikacijePredpomnilnik.enako(prikazanoZapisi, zapisi(svez))) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post { naSpremembo(svez) }
                }
            } catch (e: Throwable) {
                android.util.Log.w("SafeerAplikacije", "Osvezitev seznama ni uspela: ${e.message}")
            } finally {
                zadnjeOsvezevanje = android.os.SystemClock.elapsedRealtime()
                osvezujem = false
            }
        }, "SafeerAplikacijeOsvezi").start()
    }

    private var sprejemnikPrijavljen = false

    /** Namestitev ali odstranitev aplikacije razveljavi ikono (in pri odstranitvi vnos) predpomnilnika. */
    @Synchronized private fun prijaviSprejemnik(app: Context) {
        if (sprejemnikPrijavljen) return
        sprejemnikPrijavljen = true
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        val sprejemnik = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val paket = i.data?.schemeSpecificPart
                if (paket == null) oblikovane.pocisti() else oblikovane.odstrani(paket)
                // Naloga 99 (pregled 231, N1): datoteka seznama se bere in pise na niti ikon, ne v onReceive na glavni niti.
                if (i.action == Intent.ACTION_PACKAGE_REMOVED && paket != null && !i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) try { niti.execute {
                    val stari = AplikacijePredpomnilnik.razcleni(try { datoteka(app).readText() } catch (_: Throwable) { null })
                    if (stari != null) try {
                        datoteka(app).writeText(AplikacijePredpomnilnik.kodiraj(AplikacijePredpomnilnik.brez(stari, paket)))
                    } catch (_: Throwable) { }
                } } catch (_: java.util.concurrent.RejectedExecutionException) { }
                zadnjeOsvezevanje = 0L      // naslednje odprtje takoj prebere svez seznam
            }
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(sprejemnik, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(sprejemnik, filter)
        } catch (e: Throwable) { sprejemnikPrijavljen = false }
    }

    private val niti = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "SafeerAplikacijeIkone").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
    }
    private val cakajoceIkone = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Ikona, ki je ze v pomnilniku (po velikosti omejen LRU); sicer null in se ne bere nic z diska.
     * Za izris kartice na glavni niti.
     */
    fun ikonaIzPomnilnika(paket: String): Drawable? = oblikovane.daj(paket)

    /** Ikono naloziti v ozadju z nizko prednostjo; [naNalozeno] pride na glavno nit, ko je v predpomnilniku. */
    fun ikonaLeno(context: Context, v: Vnos, naNalozeno: () -> Unit) {
        if (oblikovane.daj(v.paket) != null || !cakajoceIkone.add(v.paket)) return
        val app = context.applicationContext
        try {
            niti.execute {
                try { if (ikona(app, v) != null) android.os.Handler(android.os.Looper.getMainLooper()).post(naNalozeno) }
                catch (_: Throwable) { }
                finally { cakajoceIkone.remove(v.paket) }
            }
        } catch (_: Throwable) { cakajoceIkone.remove(v.paket) }
    }

    private fun najdi(context: Context, brezIkon: Boolean = false): List<Vnos> {
        val pm = context.packageManager
        // Telefon in tablica: seznam, kot ga vidi zaganjalnik (LauncherApps) - isti vir kot sistemski zaganjalnik,
        // tudi za aplikacije, ki jih paketni upravitelj z nevidnostjo paketov (Android 11+) ne bi pokazal.
        if (!pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) zaganjalnikovSeznam(context, brezIkon)?.let { return it }
        val poizvedba = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val zadetki = ArrayList(try { pm.queryIntentActivities(poizvedba, 0) } catch (_: Throwable) { emptyList() })
        // Tablica (naprava brez leanbacka): aplikacije za televizor ima le redka - Safeer OS Tablet je
        // na zaslonu aplikacij pokazal 7 od 50. Tam stejejo aplikacije obicajnega zaganjalnika.
        val leanback = zadetki.mapNotNullTo(HashSet()) { it.activityInfo?.packageName }
        if (!pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            val obicajne = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            try { zadetki.addAll(pm.queryIntentActivities(obicajne, 0)) } catch (_: Throwable) { }
        } else {
            // Lastnik 30. 10. 2026: Claude, namescen iz Safeer Play Store, ni bil med aplikacijami - na TV smo
            // steli samo aplikacije za TV. Telefonske aplikacije zdaj tecejo z misko in tablicnim nacinom, zato
            // pridejo zraven vse, ki jih je namestil uporabnik (sistemske brez TV zaganjalnika ne).
            val obicajne = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val telefonske = try { pm.queryIntentActivities(obicajne, 0) } catch (_: Throwable) { emptyList() }
            zadetki.addAll(telefonske.filter { r ->
                val ai = r.activityInfo?.applicationInfo
                ai != null && r.activityInfo.packageName !in leanback &&
                    OsPravila.uporabniskaAplikacija(ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0,
                        ai.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
            })
        }
        val videni = HashSet<String>()
        val vnosi = ArrayList<Vnos>()
        val tv = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        for (z in zadetki) {
            val info = z.activityInfo ?: continue
            if (info.packageName == context.packageName || !videni.add(info.packageName)) continue
            // Namero sestavimo iz ze najdene dejavnosti: getLeanbackLaunchIntentForPackage je bil za vsak
            // paket svoja poizvedba (50 aplikacij = 50 klicev paketnemu upravitelju).
            val namera = zagonskaNamera(ComponentName(info.packageName, info.name), tv && info.packageName in leanback)
            // Ikona pred plakatom: plakat (banner) je sirok 320x180 in v majhni kvadratni
            // ploscici na domacem zaslonu postane neberljiv drobiz - prav to se je videlo pri
            // Safeer Browserju in SmartTubu. Kvadratna ikona aplikacije je vedno citljiva.
            val ikona = if (brezIkon) null else try { info.loadIcon(pm) ?: info.loadBanner(pm) } catch (_: Throwable) { null }
            vnosi.add(Vnos(info.packageName, z.loadLabel(pm).toString().ifBlank { info.packageName }, ikona, namera, info))
        }
        // Safeer Browser je ista aplikacija kot Safeer OS in ima svojo kartico Splet, zato ga ni.
        return vnosi
    }

    private fun zaganjalnikovSeznam(context: Context, brezIkon: Boolean = false): List<Vnos>? = try {
        val la = context.getSystemService(android.content.pm.LauncherApps::class.java)
        val videni = HashSet<String>()
        la.getActivityList(null, android.os.Process.myUserHandle()).mapNotNull { a ->
            val paket = a.componentName.packageName
            if (paket == context.packageName || !videni.add(paket)) return@mapNotNull null
            val namera = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(a.componentName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val ikona = if (brezIkon) null else try { a.getIcon(0) } catch (_: Throwable) { null }
            Vnos(paket, a.label?.toString().orEmpty().ifBlank { paket }, ikona, namera,
                if (android.os.Build.VERSION.SDK_INT >= 31) a.activityInfo else null)
        }
    } catch (e: Throwable) {
        android.util.Log.w("SafeerAplikacije", "LauncherApps ni dal seznama: ${e.message}"); null
    }

    /** Ena aplikacija (za priljubljene na domacem zaslonu): ime, ikona in zagon, brez branja vseh aplikacij. */
    fun ena(context: Context, paket: String): Vnos? {
        val pm = context.packageManager
        val namera = (pm.getLeanbackLaunchIntentForPackage(paket).takeIf { pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
            ?: pm.getLaunchIntentForPackage(paket) ?: return null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val info = try { pm.resolveActivity(namera, 0)?.activityInfo } catch (_: Throwable) { null }
        val ime = try { (info?.loadLabel(pm) ?: pm.getApplicationLabel(pm.getApplicationInfo(paket, 0))).toString() } catch (_: Throwable) { paket }
        val ikona = try { info?.loadIcon(pm) ?: pm.getApplicationIcon(paket) } catch (_: Throwable) { null }
        return Vnos(paket, ime.ifBlank { paket }, ikona, namera, info)
    }

    /**
     * Ikona aplikacije, oblikovana enako kot ikone spletnih aplikacij: zaobljena ploscica, temen
     * logotip na svetli podlagi (in obratno). Brez tega se temne ikone zlijejo s temnim ozadjem.
     * Rezultat si zapomnimo, ker se seznam aplikacij med brskanjem veckrat izrise.
     */
    private val oblikovane = AplikacijePredpomnilnik.MajhenLru<String, Drawable>(64)

    fun ikona(c: Context, v: Vnos): Drawable? {
        oblikovane.daj(v.paket)?.let { return it }
        // Najprej ostra razlicica iz iste aplikacije; sele ce je ni, tista, ki jo da loadIcon.
        val ostra = ostraIkona(c, v)
        val vir = ostra ?: v.ikona ?: izvirnaIkona(c, v) ?: return null
        val slika = vBitmap(vir) ?: return v.ikona
        val d = try { SpletneAplikacije.ikonaIzSlike(c, slika) } catch (_: Throwable) { vir }
        oblikovane.dodaj(v.paket, d)
        return d
    }

    /** Vnos iz predpomnilnika diska nima ikone ne podatkov o dejavnosti: preberemo jih iz njegove komponente. */
    private fun dejavnost(c: Context, v: Vnos): ActivityInfo? = v.info ?: try {
        v.namera.component?.let { c.packageManager.getActivityInfo(it, 0) }
    } catch (_: Throwable) { null }

    private fun izvirnaIkona(c: Context, v: Vnos): Drawable? {
        val info = dejavnost(c, v) ?: return null
        return try { info.loadIcon(c.packageManager) ?: info.loadBanner(c.packageManager) } catch (_: Throwable) { null }
    }

    /**
     * Ista ikona kot jo da loadIcon, a v najvisji gostoti, ki jo aplikacija nosi (xxxhdpi).
     * Televizor ima 320 dpi, zato loadIcon vrne 96 px staro ikono, ploscica pa je 184 px - ikona
     * se napihne in je mehka. Iz xxxhdpi dobimo 192 px (prilagodljiva: 432 px). Risbo porabimo
     * samo za oblikovanje in je ne hranimo, da velike slike ne ostanejo v pomnilniku televizorja.
     */
    private fun ostraIkona(c: Context, v: Vnos): Drawable? {
        val info = dejavnost(c, v) ?: return null
        val res = info.iconResource
        if (res == 0) return null
        return try {
            c.packageManager.getResourcesForApplication(info.applicationInfo)
                .getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null)
        } catch (_: Throwable) { null }
    }

    /** Risba (tudi prilagodljiva ikona) v sliko, v velikosti, ki jo risba sama ponudi. */
    private fun vBitmap(d: Drawable): Bitmap? {
        if (d is BitmapDrawable) d.bitmap?.let { return it }
        val sirina = d.intrinsicWidth.takeIf { it > 0 } ?: 192
        val visina = d.intrinsicHeight.takeIf { it > 0 } ?: 192
        return try {
            val b = Bitmap.createBitmap(sirina.coerceAtMost(512), visina.coerceAtMost(512), Bitmap.Config.ARGB_8888)
            val platno = Canvas(b)
            d.setBounds(0, 0, platno.width, platno.height)
            d.draw(platno)
            b
        } catch (_: Throwable) { null }
    }

    /**
     * Majhna ikona za mrezo vseh aplikacij na namizju zaganjalnika: ikona v gostoti naprave, narisana v [px] x [px], brez
     * velike xxxhdpi slike in brez skupnega predpomnilnika; shrani se kot majhen PNG v predpomnilnik aplikacije. Izmerjeno 25. 10. 2026 (testni telefon, 3 ponovitve): mreza
     * z velikimi ikonami ([ikona]) je dodala 12-40 MB PSS.
     */
    fun ikonaMala(c: Context, v: Vnos, px: Int): Drawable? {
        val mera = px.coerceIn(16, 256)
        // Najprej majhna slika z diska: brez odpiranja virov tuje aplikacije (ta je drag del - izmerjeno na tablici
        // ~10 MB domorodnega pomnilnika za ~70 aplikacij). Velja, dokler aplikacija ni posodobljena.
        // Po zgledu CVasilakis/luncher: cas posodobitve preberemo neposredno iz APK datoteke brez Binder IPC klicev.
        val virApk = v.info?.applicationInfo?.sourceDir ?: dejavnost(c, v)?.applicationInfo?.sourceDir
        val posodobljena = virApk?.let { File(it).lastModified() }?.takeIf { it > 0L }
            ?: try { c.packageManager.getPackageInfo(v.paket, 0).lastUpdateTime } catch (_: Throwable) { 0L }
        val datoteka = File(File(c.cacheDir, "namizje-ikone"), "${v.paket}-$mera.png")
        if (posodobljena > 0 && datoteka.isFile && datoteka.lastModified() >= posodobljena) {
            try { android.graphics.BitmapFactory.decodeFile(datoteka.path)?.let { return BitmapDrawable(c.resources, it) } } catch (_: Throwable) { }
        }
        val vir = v.ikona ?: izvirnaIkona(c, v) ?: return null
        return try {
            val b = Bitmap.createBitmap(mera, mera, Bitmap.Config.ARGB_8888)
            val platno = Canvas(b)
            vir.setBounds(0, 0, platno.width, platno.height)
            vir.draw(platno)
            try {
                datoteka.parentFile?.mkdirs()
                val zacasna = File(datoteka.path + ".tmp")
                zacasna.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (!zacasna.renameTo(datoteka)) zacasna.delete()
            } catch (_: Throwable) { }
            BitmapDrawable(c.resources, b)
        } catch (_: Throwable) { null }
    }

    fun jeNamescena(context: Context, paket: String): Boolean = try {
        context.packageManager.getPackageInfo(paket, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }
}
