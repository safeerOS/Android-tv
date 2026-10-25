package si.safeer.tv.os

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import si.safeer.tv.R
import si.safeer.tv.os.NamestitevPravila.Izvor
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Namestitev aplikacije iz datoteke APK - brez Google Play (lastnik, 11. 10. 2026).
 *
 * Vir je datoteka na napravi (Datoteke, content://) ali prenos iz brskalnika (http/https). APK najprej
 * prekopiramo v predpomnilnik (cache/namestitve), preverimo, da je res aplikacija (ime, paket, razlicica),
 * uporabnika vprasamo z imenom aplikacije in opozorilom, da ni iz trgovine, nato jo namestimo prek seje
 * PackageInstaller (os/NamestitevSeja, enako kot posodobitve Safeer OS; rezerva je sistemski namestitelj). Safeerjeve
 * aplikacije (isti podpis) Android tako ne omeji z »omejenimi nastavitvami«; tuj APK ostane oznacen kot datoteka ali
 * prenos (os/NamestitevPravila). Dovoljenje »namescanje neznanih aplikacij« da uporabnik v sistemskih nastavitvah; ko
 * se vrne, se namestitev nadaljuje sama (nadaljujCeCaka v onResume).
 */
object NamestiApk {
    private const val MIME = "application/vnd.android.package-archive"
    private const val PREFS = "namesti_apk"
    private const val CAKA_MS = 10 * 60 * 1000L
    private const val MAPA = "namestitve"

    fun jeApk(ime: String?, mime: String?): Boolean =
        (mime ?: "").equals(MIME, ignoreCase = true) || (ime ?: "").trim().lowercase().endsWith(".apk")

    /** Ime datoteke brez poti in znakov, ki v imenu datoteke nimajo kaj iskati. */
    fun varnoIme(ime: String?): String {
        val osnova = (ime ?: "").substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().take(80)
        val z = if (osnova.isBlank()) "aplikacija" else osnova
        return if (z.lowercase().endsWith(".apk")) z else "$z.apk"
    }

    /** Datoteka na napravi (content:// ali file://). */
    fun izDatoteke(a: Activity, uri: Uri, ime: String) =
        pripravi(a, ime, -1L, a.getString(R.string.apk_vir_naprava), Izvor.DATOTEKA) { a.contentResolver.openInputStream(uri) ?: throw IllegalStateException("ni datoteke") }

    /** Prenos iz brskalnika: APK prenesemo sami (z napredkom), nato ga ponudimo v namestitev. */
    fun izSpleta(a: Activity, url: String, userAgent: String?, ime: String) =
        pripravi(a, ime, 0L, try { Uri.parse(url).host ?: url } catch (_: Throwable) { url }, Izvor.SPLET) {
            // Grok 8, S4: APK samo prek https (http poskusimo kot https); preusmeritvi na http HttpURLConnection ne sledi,
            // koncni naslov preverimo vseeno.
            val varen = NamestitevPravila.httpsNaslov(url) ?: throw IllegalStateException("ni_https")
            val c = URL(varen).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
            if (!userAgent.isNullOrBlank()) c.setRequestProperty("User-Agent", userAgent)
            try { android.webkit.CookieManager.getInstance().getCookie(varen)?.let { c.setRequestProperty("Cookie", it) } } catch (_: Throwable) { }
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            if (!c.url.protocol.equals("https", ignoreCase = true)) throw IllegalStateException("ni_https")
            velikostZadnjega = c.contentLengthLong
            c.inputStream
        }

    @Volatile private var velikostZadnjega = -1L

    private fun pripravi(a: Activity, ime: String, znanaVelikost: Long, vir: String, izvor: Izvor, odpri: () -> InputStream) {
        val dp = { v: Int -> (v * a.resources.displayMetrics.density).toInt() }
        val napredek = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; isIndeterminate = true }
        val opis = TextView(a).apply { text = a.getString(R.string.apk_pripravljam, ime) }
        val vsebina = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(opis); addView(napredek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        var prekinjeno = false
        val okno = AlertDialog.Builder(a).setTitle(R.string.apk_naslov).setView(vsebina).setCancelable(true)
            .setNegativeButton(android.R.string.cancel) { _, _ -> prekinjeno = true }.setOnCancelListener { prekinjeno = true }.show()
        val glavna = Handler(Looper.getMainLooper())
        velikostZadnjega = znanaVelikost
        Thread({
            val mapa = File(a.cacheDir, MAPA).apply { mkdirs() }
            mapa.listFiles()?.forEach { it.delete() }
            val cilj = File(mapa, varnoIme(ime))
            try {
                // Vsota SHA-256 na istih bajtih, ki gredo v datoteko: v sejo gre kasneje samo, ce se ujema (TOCTOU).
                val vsota = odpri().use { v ->
                    val skupaj = velikostZadnjega
                    // Grok 8, S3: meja velikosti in prostor na disku, preden pisemo (neskoncen tok ne napolni naprave).
                    if (skupaj > NamestitevPravila.NAJVEC_APK || (skupaj > 0 && mapa.usableSpace < skupaj + NamestitevPravila.REZERVA_PROSTORA))
                        throw IllegalStateException("prevelik")
                    if (skupaj > 0) glavna.post { napredek.isIndeterminate = false }
                    var zadnji = 0L
                    var vse = 0L
                    val h = cilj.outputStream().use { o ->
                        NamestitevPravila.prepisi(v, o, { prekinjeno }, NamestitevPravila.NAJVEC_APK) { prebrano ->
                            vse = prebrano
                            if (skupaj > 0 && prebrano - zadnji > skupaj / 100) { zadnji = prebrano; val d = (prebrano * 1000 / skupaj).toInt(); glavna.post { napredek.progress = d } }
                        }
                    }
                    // Grok 8, S4: prekinjen prenos (manj bajtov od napovedanih) ni APK za namestitev.
                    if (!NamestitevPravila.popolnPrenos(vse, skupaj)) throw IllegalStateException("nepopoln")
                    h
                }
                glavna.post {
                    try { okno.dismiss() } catch (_: Throwable) { }
                    if (!prekinjeno && !a.isFinishing) vprasaj(a, cilj, vir, vsota, izvor)
                }
            } catch (t: Throwable) {
                cilj.delete()
                glavna.post {
                    try { okno.dismiss() } catch (_: Throwable) { }
                    if (!prekinjeno) Toast.makeText(a, a.getString(R.string.apk_napaka), Toast.LENGTH_LONG).show()
                }
            }
        }, "safeer-namesti-apk").start()
    }

    /** Ime, paket in razlicica iz APK; null, ce datoteka ni aplikacija. */
    private fun podatki(a: Context, f: File): Triple<String, String, String>? = try {
        val pm = a.packageManager
        val pi = pm.getPackageArchiveInfo(f.absolutePath, 0) ?: null
        pi?.applicationInfo?.let { ai ->
            ai.sourceDir = f.absolutePath; ai.publicSourceDir = f.absolutePath
            Triple(ai.loadLabel(pm).toString(), pi.packageName ?: "", pi.versionName ?: "")
        }
    } catch (_: Throwable) { null }

    private fun vprasaj(a: Activity, f: File, vir: String, vsota: String, izvor: Izvor) {
        val p = podatki(a, f)
        if (p == null) {
            f.delete()
            AlertDialog.Builder(a).setTitle(R.string.apk_naslov).setMessage(R.string.apk_ni_veljavna).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val (ime, paket, razlicica) = p
        val namescena = try { a.packageManager.getPackageInfo(paket, 0).versionName } catch (_: Throwable) { null }
        val sporocilo = if (namescena != null) a.getString(R.string.apk_vprasanje_posodobi, ime, razlicica.ifBlank { "?" }, namescena ?: "?")
            else a.getString(R.string.apk_vprasanje, ime, razlicica.ifBlank { "?" })
        // Uporabnik vidi, kaj se namesti (ime, paket, razlicica), od kod in koliko - in da Safeer ne posodablja sam.
        val mb = String.format(java.util.Locale.getDefault(), "%.1f", f.length() / 1048576.0)
        val podrobno = a.getString(R.string.apk_podrobno, paket, vir, mb)
        AlertDialog.Builder(a).setTitle(R.string.apk_naslov)
            .setMessage(sporocilo + "\n\n" + podrobno + "\n\n" + a.getString(R.string.apk_opozorilo) + " " + a.getString(R.string.apk_posodobitve))
            .setPositiveButton(R.string.apk_namesti) { _, _ -> namesti(a, f, vsota, paket, ime, izvor) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> f.delete() }.show()
    }

    private fun namesti(a: Activity, f: File, vsota: String, paket: String, ime: String, izvor: Izvor) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(a).setTitle(R.string.apk_naslov).setMessage(R.string.apk_dovoljenje)
                .setPositiveButton(R.string.os_posodobitev_dovoli) { _, _ ->
                    // Vsota, paket in ime, ki ju je uporabnik potrdil: po vrnitvi namestimo prav to (ne znova prebrane datoteke).
                    prefs(a).edit().putString("caka", f.absolutePath).putLong("caka_cas", System.currentTimeMillis())
                        .putString("caka_vsota", vsota).putString("caka_paket", paket).putString("caka_ime", ime)
                        .putString("caka_izvor", izvor.name).apply()
                    try { a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName))) }
                    catch (_: Throwable) { try { a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)) } catch (_: Throwable) { } }
                }.setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        NamestitevSeja.namesti(a, f, vsota, paket, ime, izvor) { d ->
            // Rezerva: seja na tej napravi ne dela - sistemski namestitelj (vir datoteka, kot pred 25. 10. 2026).
            if (!NamestitevSeja.sistemskiNamestitelj(d, MAPA, f)) Toast.makeText(d, d.getString(R.string.apk_napaka), Toast.LENGTH_LONG).show()
        }
    }

    /** Po vrnitvi iz nastavitev dovoljenja: nadaljuj namestitev, ki je cakala (najvec 10 minut). */
    fun nadaljujCeCaka(a: Activity): Boolean {
        val p = prefs(a)
        val pot = p.getString("caka", "") ?: ""
        if (pot.isEmpty()) return false
        val cas = p.getLong("caka_cas", 0L)
        val vsota = p.getString("caka_vsota", "") ?: ""
        val paket = p.getString("caka_paket", "") ?: ""
        val ime = p.getString("caka_ime", "") ?: ""
        val izvor = Izvor.values().firstOrNull { it.name == p.getString("caka_izvor", "") } ?: Izvor.DATOTEKA
        p.edit().remove("caka").remove("caka_cas").remove("caka_vsota").remove("caka_paket").remove("caka_ime").remove("caka_izvor").apply()
        if (System.currentTimeMillis() - cas > CAKA_MS) return false
        val f = File(pot)
        if (!f.isFile || vsota.length != 64 || paket.isBlank()) return false
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) return false
        namesti(a, f, vsota, paket, ime.ifBlank { paket }, izvor)
        return true
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
