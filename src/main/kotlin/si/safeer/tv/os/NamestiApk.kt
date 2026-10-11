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
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Namestitev aplikacije iz datoteke APK - brez Google Play (lastnik, 11. 10. 2026).
 *
 * Vir je datoteka na napravi (Datoteke, content://) ali prenos iz brskalnika (http/https). APK najprej
 * prekopiramo v predpomnilnik (cache/namestitve), preverimo, da je res aplikacija (ime, paket, razlicica),
 * uporabnika vprasamo z imenom aplikacije in opozorilom, da ni iz trgovine, nato jo preda sistemskemu
 * namestitelju (enako kot posodobitve Safeer OS). Dovoljenje »namescanje neznanih aplikacij« da uporabnik
 * v sistemskih nastavitvah; ko se vrne, se namestitev nadaljuje sama (nadaljujCeCaka v onResume).
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
        pripravi(a, ime, -1L) { a.contentResolver.openInputStream(uri) ?: throw IllegalStateException("ni datoteke") }

    /** Prenos iz brskalnika: APK prenesemo sami (z napredkom), nato ga ponudimo v namestitev. */
    fun izSpleta(a: Activity, url: String, userAgent: String?, ime: String) =
        pripravi(a, ime, 0L) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
            if (!userAgent.isNullOrBlank()) c.setRequestProperty("User-Agent", userAgent)
            try { android.webkit.CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) } } catch (_: Throwable) { }
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            velikostZadnjega = c.contentLengthLong
            c.inputStream
        }

    @Volatile private var velikostZadnjega = -1L

    private fun pripravi(a: Activity, ime: String, znanaVelikost: Long, odpri: () -> InputStream) {
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
                odpri().use { v ->
                    val skupaj = velikostZadnjega
                    if (skupaj > 0) glavna.post { napredek.isIndeterminate = false }
                    cilj.outputStream().use { o ->
                        val b = ByteArray(1 shl 16); var n: Int; var prebrano = 0L; var zadnji = 0L
                        while (v.read(b).also { n = it } > 0) {
                            if (prekinjeno) throw InterruptedException("prekinjeno")
                            o.write(b, 0, n); prebrano += n
                            if (skupaj > 0 && prebrano - zadnji > skupaj / 100) { zadnji = prebrano; val d = (prebrano * 1000 / skupaj).toInt(); glavna.post { napredek.progress = d } }
                        }
                    }
                }
                glavna.post {
                    try { okno.dismiss() } catch (_: Throwable) { }
                    if (!prekinjeno && !a.isFinishing) vprasaj(a, cilj)
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

    private fun vprasaj(a: Activity, f: File) {
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
        AlertDialog.Builder(a).setTitle(R.string.apk_naslov).setMessage(sporocilo + "\n\n" + a.getString(R.string.apk_opozorilo))
            .setPositiveButton(R.string.apk_namesti) { _, _ -> namesti(a, f) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> f.delete() }.show()
    }

    private fun namesti(a: Activity, f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(a).setTitle(R.string.apk_naslov).setMessage(R.string.apk_dovoljenje)
                .setPositiveButton(R.string.os_posodobitev_dovoli) { _, _ ->
                    prefs(a).edit().putString("caka", f.absolutePath).putLong("caka_cas", System.currentTimeMillis()).apply()
                    try { a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName))) }
                    catch (_: Throwable) { try { a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)) } catch (_: Throwable) { } }
                }.setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        val uri = Uri.parse("content://" + a.packageName + ".fileprovider/cache_files/" + MAPA + "/" + Uri.encode(f.name))
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // Naravnost v sistemski namestitelj (brez izbirnika, ce se za APK prijavi se kaksna aplikacija).
        try {
            a.packageManager.queryIntentActivities(i, android.content.pm.PackageManager.MATCH_SYSTEM_ONLY)
                .firstOrNull()?.activityInfo?.let { i.setClassName(it.packageName, it.name) }
        } catch (_: Throwable) { }
        try { a.startActivity(i) } catch (_: Throwable) {
            Toast.makeText(a, a.getString(R.string.apk_napaka), Toast.LENGTH_LONG).show()
        }
    }

    /** Po vrnitvi iz nastavitev dovoljenja: nadaljuj namestitev, ki je cakala (najvec 10 minut). */
    fun nadaljujCeCaka(a: Activity): Boolean {
        val p = prefs(a)
        val pot = p.getString("caka", "") ?: ""
        if (pot.isEmpty()) return false
        val cas = p.getLong("caka_cas", 0L)
        p.edit().remove("caka").remove("caka_cas").apply()
        if (System.currentTimeMillis() - cas > CAKA_MS) return false
        val f = File(pot)
        if (!f.isFile) return false
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) return false
        namesti(a, f)
        return true
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
