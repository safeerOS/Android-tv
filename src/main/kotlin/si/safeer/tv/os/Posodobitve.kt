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
import org.json.JSONObject
import si.safeer.tv.BuildConfig
import si.safeer.tv.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Posodobitve iz safeer.si (os/razlicice.json): aplikacija sama pove, da je na voljo nova razlicica, in jo z enim
 * dotikom prenese ter preda sistemskemu namescanju (APK s preverjeno vsoto SHA-256). Nic se ne namesti brez uporabnika.
 *
 * Preverba: ob odprtju domacega zaslona najvec vsakih 6 ur (tiho, v niti); nova razlicica se pokaze kot pasica na dnu
 * (enaka kot pri »Poslji na napravo«) najvec enkrat na dan za isto razlicico, v Nastavitvah pa vedno (vrstica Posodobitve).
 */
object Posodobitve {
    const val MANIFEST = "https://safeer.si/os/razlicice.json"
    private const val PREFS = "safeer_posodobitve"
    private const val PREVERBA_MS = 6 * 3600_000L
    private const val OPOMNIK_MS = 24 * 3600_000L
    private const val CAKA_MS = 10 * 60_000L
    private const val OZNAKA = "SafeerPosodobitve"

    class Nova(val razlicica: String, val koda: Int, val url: String, val sha256: String, val velikost: Long, val novo: JSONObject? = null) {
        fun json(): JSONObject = JSONObject().put("razlicica", razlicica).put("koda", koda).put("url", url).put("sha256", sha256).put("velikost", velikost)
            .put("novo", novo ?: JSONObject())
        /** Kaj je novega, v jeziku vmesnika (sl ali en), ali prazno. */
        fun kajJeNovega(ctx: Context): String {
            val n = novo ?: return ""
            val jezik = ctx.resources.configuration.locales[0].language
            return n.optString(if (jezik == "sl") "sl" else "en").ifBlank { n.optString("en") }
        }
        companion object {
            fun iz(o: JSONObject?): Nova? {
                o ?: return null
                val r = o.optString("razlicica"); val u = o.optString("url"); val s = o.optString("sha256")
                if (r.isBlank() || !u.startsWith("https://") || s.length != 64) return null
                return Nova(r, o.optInt("koda"), u, s.lowercase(), o.optLong("velikost"), o.optJSONObject("novo"))
            }
        }
    }

    /** Zadnji izid preverbe (null = ni novejse ali se ni bilo preverjeno). */
    @Volatile var naVoljo: Nova? = null
        private set
    @Volatile private var zadnjaPreverba = 0L
    @Volatile private var preverjam = false

    fun log(s: String) = android.util.Log.i(OZNAKA, s)

    /**
     * Besedilo posodobitev z imenom TE aplikacije: prevodi govorijo o "Safeer OS", Predvajalnik in TV brskalnik
     * pa imata svoje ime (prej je Predvajalnik ponujal "novo razlicico Safeer OS 0.2.21").
     */
    fun besedilo(ctx: Context, id: Int, vararg argumenti: Any): String {
        val b = ctx.getString(id, *argumenti)
        if (BuildConfig.FLAVOR != "predvajalnik" && BuildConfig.FLAVOR != "brskalnik") return b
        val ime = try { ctx.applicationInfo.loadLabel(ctx.packageManager).toString() } catch (_: Throwable) { "" }
        return if (ime.isBlank()) b else b.replace("Safeer OS", ime)
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Shranjena nova razlicica iz prejsnje preverbe (da jo Nastavitve pokazejo takoj, se pred novo preverbo). */
    fun shranjena(ctx: Context): Nova? {
        val n = naVoljo ?: Nova.iz(try { JSONObject(prefs(ctx).getString("nova", "") ?: "") } catch (_: Throwable) { null })
        return n?.takeIf { it.koda > BuildConfig.VERSION_CODE }
    }

    /** Vrstica v Nastavitvah: "Najnovejsa (0.5.27)" ali "Na voljo 0.5.28". */
    fun stanje(ctx: Context): String {
        val n = shranjena(ctx)
        return if (n != null) ctx.getString(R.string.os_posodobitev_na_voljo, n.razlicica)
        else ctx.getString(R.string.os_posodobitev_najnovejsa, BuildConfig.VERSION_NAME)
    }

    /** Tiho preveri (v niti), ce je od zadnje preverbe minilo dovolj ali je [vsiljeno]; [nato] na glavni niti. */
    fun preveri(ctx: Context, vsiljeno: Boolean = false, nato: ((Nova?, String?) -> Unit)? = null) {
        val app = ctx.applicationContext
        val glavna = Handler(Looper.getMainLooper())
        if (!vsiljeno && (System.currentTimeMillis() - zadnjaPreverba < PREVERBA_MS || preverjam)) { nato?.let { glavna.post { it(shranjena(app), null) } }; return }
        preverjam = true
        Thread({
            var napaka: String? = null
            var nova: Nova? = null
            try {
                val c = URL(MANIFEST).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("User-Agent", "SafeerOS/${BuildConfig.VERSION_NAME} (${app.packageName})")
                c.setRequestProperty("Cache-Control", "no-cache")
                val besedilo = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val vnos = JSONObject(besedilo).optJSONObject("android")?.optJSONObject(app.packageName)
                nova = Nova.iz(vnos)?.takeIf { it.koda > BuildConfig.VERSION_CODE }
                zadnjaPreverba = System.currentTimeMillis()
                prefs(app).edit().putString("nova", nova?.json()?.toString() ?: "").putLong("preverjeno", zadnjaPreverba).apply()
                log("preverba: nasa ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), na voljo ${nova?.razlicica ?: "nic novejsega"}")
            } catch (t: Throwable) {
                napaka = t.toString(); log("preverba ni uspela: $t")
            }
            naVoljo = nova
            preverjam = false
            nato?.let { k -> glavna.post { k(nova, napaka) } }
        }, "safeer-posodobitve").start()
    }

    /**
     * Po vrnitvi iz sistemskih nastavitev (dovoljenje za namescanje): namestitev se nadaljuje sama, brez
     * ponovnega iskanja gumba Namesti. Zapis je v nastavitvah (ne v pomnilniku), ker Android ob spremembi tega
     * dovoljenja aplikacijo lahko ponovno zazene. Velja 10 minut.
     */
    fun nadaljujCeCaka(a: Activity): Boolean {
        val p = prefs(a)
        val zapis = p.getString("caka", "") ?: ""
        if (zapis.isEmpty()) return false
        val cas = p.getLong("caka_cas", 0L)
        p.edit().remove("caka").remove("caka_cas").apply()
        if (System.currentTimeMillis() - cas > CAKA_MS) return false
        val nova = Nova.iz(try { JSONObject(zapis) } catch (_: Throwable) { null }) ?: return false
        if (nova.koda <= BuildConfig.VERSION_CODE) return false
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) return false
        prenesiInNamesti(a, nova)
        return true
    }

    /** Ob odprtju zaslona: preveri (najvec na 6 h) in novo razlicico ponudi s pasico (najvec enkrat na dan za isto). */
    fun ponudiCeJeCas(a: Activity) {
        if (nadaljujCeCaka(a)) return
        preveri(a) { nova, _ ->
            if (nova == null || a.isFinishing) return@preveri
            val p = prefs(a)
            val kljuc = "ponujeno_" + nova.koda
            if (System.currentTimeMillis() - p.getLong(kljuc, 0L) < OPOMNIK_MS) return@preveri
            p.edit().putLong(kljuc, System.currentTimeMillis()).apply()
            PredajaObvestilo.pasicaSplosna(a, besedilo(a, R.string.os_posodobitev_pasica, nova.razlicica),
                a.getString(R.string.os_posodobitev_namesti), a.getString(R.string.os_posodobitev_pozneje), { prenesiInNamesti(a, nova) }, {})
        }
    }

    /** Iz Nastavitev: najprej pove, kaj je novega (Namesti / Pozneje), nato prenos in namescanje. */
    fun ponudiZOpisom(a: Activity, nova: Nova) {
        val novo = nova.kajJeNovega(a)
        val b = AlertDialog.Builder(a).setTitle(besedilo(a, R.string.os_posodobitev_pasica, nova.razlicica))
            .setPositiveButton(R.string.os_posodobitev_namesti) { _, _ -> prenesiInNamesti(a, nova) }
            .setNegativeButton(R.string.os_posodobitev_pozneje, null)
        if (novo.isNotBlank()) b.setMessage(novo)
        b.show()
    }

    /** Prenos APK v predpomnilnik (z napredkom), preverba SHA-256, nato sistemsko namescanje. */
    fun prenesiInNamesti(a: Activity, nova: Nova) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            // Android najprej vprasa, ali sme Safeer OS namescati aplikacije; uporabnik se vrne in pritisne Namesti znova.
            AlertDialog.Builder(a).setTitle(R.string.os_posodobitve).setMessage(besedilo(a, R.string.os_posodobitev_dovoljenje))
                .setPositiveButton(R.string.os_posodobitev_dovoli) { _, _ ->
                    prefs(a).edit().putString("caka", nova.json().toString()).putLong("caka_cas", System.currentTimeMillis()).apply()
                    try { a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName))) }
                    catch (_: Throwable) { try { a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)) } catch (_: Throwable) { } }
                }.setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        val dp = { v: Int -> (v * a.resources.displayMetrics.density).toInt() }
        val napredek = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; isIndeterminate = nova.velikost <= 0 }
        val opis = TextView(a).apply { text = a.getString(R.string.os_posodobitev_prenasam, nova.razlicica) }
        val vsebina = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(opis); addView(napredek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        var prekinjeno = false
        val okno = AlertDialog.Builder(a).setTitle(R.string.os_posodobitve).setView(vsebina).setCancelable(true)
            .setNegativeButton(android.R.string.cancel) { _, _ -> prekinjeno = true }.setOnCancelListener { prekinjeno = true }.show()
        val glavna = Handler(Looper.getMainLooper())
        Thread({
            try {
                val mapa = File(a.cacheDir, "posodobitve").apply { mkdirs() }
                mapa.listFiles()?.forEach { it.delete() }
                val cilj = File(mapa, nova.url.substringAfterLast('/').ifBlank { "posodobitev.apk" })
                val c = URL(nova.url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
                val skupaj = if (nova.velikost > 0) nova.velikost else c.contentLengthLong
                val h = MessageDigest.getInstance("SHA-256")
                c.inputStream.use { v ->
                    cilj.outputStream().use { o ->
                        val b = ByteArray(1 shl 16); var n: Int; var prebrano = 0L; var zadnji = 0L
                        while (v.read(b).also { n = it } > 0) {
                            if (prekinjeno) throw InterruptedException("prekinjeno")
                            o.write(b, 0, n); h.update(b, 0, n); prebrano += n
                            if (skupaj > 0 && prebrano - zadnji > skupaj / 100) { zadnji = prebrano; val d = (prebrano * 1000 / skupaj).toInt(); glavna.post { napredek.progress = d } }
                        }
                    }
                }
                val vsota = h.digest().joinToString("") { "%02x".format(it) }
                if (vsota != nova.sha256) { cilj.delete(); throw IllegalStateException("SHA-256 se ne ujema") }
                glavna.post {
                    okno.dismiss()
                    if (prekinjeno) return@post
                    val uri = Uri.parse("content://" + a.packageName + ".fileprovider/cache_files/posodobitve/" + cilj.name)
                    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    // Brez izbirnika "Odpiranje z aplikacijo" (npr. Termux se prijavi za APK): naravnost v sistemski namestitelj.
                    try {
                        a.packageManager.queryIntentActivities(i, android.content.pm.PackageManager.MATCH_SYSTEM_ONLY)
                            .firstOrNull()?.activityInfo?.let { i.setClassName(it.packageName, it.name) }
                    } catch (_: Throwable) { }
                    try { a.startActivity(i) } catch (t: Throwable) {
                        log("namescanje: $t"); android.widget.Toast.makeText(a, R.string.os_posodobitev_napaka, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } catch (t: Throwable) {
                log("prenos: $t")
                glavna.post {
                    okno.dismiss()
                    if (!prekinjeno) android.widget.Toast.makeText(a, R.string.os_posodobitev_napaka, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }, "safeer-posodobitev-prenos").start()
    }
}
