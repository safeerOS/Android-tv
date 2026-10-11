package si.safeer.tv.os

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import si.safeer.tv.R
import java.net.HttpURLConnection
import java.net.URL

/**
 * Izbira ponudnika aplikacij v Safeer OS (lastnik, 11. 10. 2026): Safeer nima svoje trgovine in ne
 * pristaja na monopol ene trgovine. Uporabnik izbere, od kod bo namescal aplikacije; ponudnik se namesti
 * iz uradnega vira (najnovejsa izdaja ob izbiri, ne vgrajen naslov), nato aplikacije namesca on.
 * Seznam je po abecedi in brez priporocila. Google Play je na seznamu enakovredno drugim (ni privzet):
 * ce je izklopljen, uporabnika peljemo v sistemske podatke aplikacije, kjer ga sam vklopi; ce ga ni,
 * povemo, da Google uradne namestitvene datoteke ne objavlja.
 */
object TrgovineAplikacij {

    sealed class Vir {
        /** Stalen uradni naslov APK. */
        class Neposredno(val url: String) : Vir()
        /** Zadnja izdaja na GitHubu; izberemo APK za arhitekturo naprave. */
        class GitHub(val repo: String) : Vir()
        /** Predlagana razlicica v uradnem repozitoriju F-Droid. */
        class FDroid(val paket: String) : Vir()
        /** Uradni vmesnik Aptoide za njihovo aplikacijo. */
        class Aptoide(val paket: String) : Vir()
        /** Ne namescamo (ni uradnega APK): odpremo, vklopimo v nastavitvah ali razlozimo. */
        object SamoOdpri : Vir()
    }

    class Ponudnik(val ime: String, val paket: String, val opis: Int, val vir: Vir)

    val SEZNAM = listOf(
        Ponudnik("Aptoide TV", "cm.aptoidetv.pt", R.string.trg_opis_aptoide, Vir.Aptoide("cm.aptoidetv.pt")),
        Ponudnik("Aurora Store", "com.aurora.store", R.string.trg_opis_aurora, Vir.FDroid("com.aurora.store")),
        Ponudnik("Droid-ify", "com.looker.droidify", R.string.trg_opis_droidify, Vir.GitHub("Droid-ify/client")),
        Ponudnik("F-Droid", "org.fdroid.fdroid", R.string.trg_opis_fdroid, Vir.Neposredno("https://f-droid.org/F-Droid.apk")),
        Ponudnik("Flicky", "app.flicky", R.string.trg_opis_flicky, Vir.GitHub("mlm-games/flicky")),
        Ponudnik("Google Play", "com.android.vending", R.string.trg_opis_play, Vir.SamoOdpri),
        Ponudnik("Neo Store", "com.machiav3lli.fdroid", R.string.trg_opis_neo, Vir.GitHub("NeoApplications/Neo-Store")),
        Ponudnik("Obtainium", "dev.imranr.obtainium", R.string.trg_opis_obtainium, Vir.GitHub("ImranR98/Obtainium")),
    )

    private fun namescen(a: Activity, paket: String): Boolean =
        try { a.packageManager.getPackageInfo(paket, 0); true } catch (_: Throwable) { false }

    /** Namescen, a izklopljen (npr. Google Play, ki ga je lastnik naprave izklopil). */
    private fun izklopljen(a: Activity, paket: String): Boolean =
        try { !a.packageManager.getApplicationInfo(paket, 0).enabled } catch (_: Throwable) { false }

    private fun zagon(a: Activity, paket: String): Intent? =
        a.packageManager.getLeanbackLaunchIntentForPackage(paket) ?: a.packageManager.getLaunchIntentForPackage(paket)

    /** Okno z izbiro: ime, kratek opis in ali je ponudnik ze na napravi. */
    fun pokazi(a: Activity) {
        val vidni = SEZNAM
        val vrstice = vidni.map { p ->
            val stanje = when {
                izklopljen(a, p.paket) -> " · " + a.getString(R.string.trg_izklopljeno)
                namescen(a, p.paket) -> " · " + a.getString(R.string.trg_namesceno)
                else -> ""
            }
            p.ime + stanje + "\n" + a.getString(p.opis)
        }.toTypedArray()
        AlertDialog.Builder(a).setTitle(R.string.trg_naslov)
            .setItems(vrstice) { _, i -> izberi(a, vidni[i]) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun izberi(a: Activity, p: Ponudnik) {
        if (izklopljen(a, p.paket)) {
            // Vklopi ga uporabnik sam v sistemskih podatkih aplikacije (gumb »Omogoci«); Safeer ga ne vklaplja.
            AlertDialog.Builder(a).setTitle(p.ime).setMessage(a.getString(R.string.trg_izklopljen_opis, p.ime))
                .setPositiveButton(R.string.trg_odpri_nastavitve) { _, _ ->
                    try {
                        a.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + p.paket)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Throwable) { }
                }.setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        if (namescen(a, p.paket)) {
            val z = zagon(a, p.paket)
            if (z != null) { try { a.startActivity(z.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Throwable) { } }
            Toast.makeText(a, a.getString(R.string.trg_ni_zagona, p.ime), Toast.LENGTH_LONG).show()
            return
        }
        if (p.vir is Vir.SamoOdpri) {
            AlertDialog.Builder(a).setTitle(p.ime).setMessage(a.getString(R.string.trg_ni_uradnega, p.ime))
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        Toast.makeText(a, a.getString(R.string.trg_iscem, p.ime), Toast.LENGTH_SHORT).show()
        val glavna = Handler(Looper.getMainLooper())
        Thread({
            val url = try { naslovApk(p.vir) } catch (_: Throwable) { null }
            glavna.post {
                if (a.isFinishing) return@post
                if (url == null) Toast.makeText(a, a.getString(R.string.trg_ni_vira, p.ime), Toast.LENGTH_LONG).show()
                else NamestiApk.izSpleta(a, url, null, p.ime + ".apk")
            }
        }, "safeer-trgovine").start()
    }

    private fun json(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "SafeerOS")
        if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** Naslov APK za ta vir (omrezje: klici iz ozadja). */
    fun naslovApk(v: Vir): String? = when (v) {
        is Vir.Neposredno -> v.url
        is Vir.FDroid -> {
            val koda = JSONObject(json("https://f-droid.org/api/v1/packages/" + v.paket)).getLong("suggestedVersionCode")
            "https://f-droid.org/repo/" + v.paket + "_" + koda + ".apk"
        }
        is Vir.Aptoide -> JSONObject(json("https://ws75.aptoide.com/api/7/app/getMeta/package_name=" + v.paket))
            .getJSONObject("data").getJSONObject("file").getString("path").takeIf { it.startsWith("https://") }
        is Vir.GitHub -> {
            val sredstva = JSONObject(json("https://api.github.com/repos/" + v.repo + "/releases/latest")).getJSONArray("assets")
            val apk = (0 until sredstva.length()).map { sredstva.getJSONObject(it) }
                .filter { it.optString("name").lowercase().endsWith(".apk") }
                .map { it.optString("name") to it.optString("browser_download_url") }
            izberiZaNapravo(apk, Build.SUPPORTED_ABIS.toList())
        }
        Vir.SamoOdpri -> null
    }

    private val ABI = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /**
     * Iz izdaje izbere APK za to napravo: najprej za njeno arhitekturo, sicer splosnega (universal / brez oznake
     * arhitekture). Razlicice »fdroid« preskocimo, ce je na voljo navadna (podpisuje jih drug kljuc).
     */
    fun izberiZaNapravo(apk: List<Pair<String, String>>, abiNaprave: List<String>): String? {
        if (apk.isEmpty()) return null
        val navadni = apk.filter { !it.first.lowercase().contains("fdroid") }.ifEmpty { apk }
        for (abi in abiNaprave) navadni.firstOrNull { it.first.lowercase().contains(abi) }?.let { return it.second }
        navadni.firstOrNull { it.first.lowercase().contains("universal") }?.let { return it.second }
        return navadni.firstOrNull { n -> ABI.none { n.first.lowercase().contains(it) } }?.second
    }
}
