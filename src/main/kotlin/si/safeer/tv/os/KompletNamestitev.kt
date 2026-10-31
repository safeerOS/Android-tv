package si.safeer.tv.os

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import si.safeer.tv.BuildConfig
import si.safeer.tv.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object KompletNamestitev {
    private const val PREFS = "safeer_komplet"
    private const val OPOMNIK_MS = 24 * 3600_000L

    fun ponudiPrvic(a: Activity) {
        val p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - p.getLong("ponujeno", 0L) < OPOMNIK_MS) return
        val manjkajo = KompletPravila.manjkajoci(Sosed.jeTelevizor(a), Sosed.smoOs(a), 
            setOf(Sosed.BRSKALNIK, Sosed.TRGOVINA).filter { Sosed.namescen(a, it) }.toSet())
        if (manjkajo.isEmpty()) return

        p.edit().putLong("ponujeno", System.currentTimeMillis()).apply()
        
        val dialog = AlertDialog.Builder(a)
            .setTitle(R.string.os_komplet_naslov)
            .setMessage(R.string.os_komplet_opis)
            .setPositiveButton(R.string.os_komplet_namesti) { _, _ -> namesti(a, manjkajo) }
            .setNegativeButton(R.string.os_komplet_pozneje, null)
            .show()
            
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    fun namesti(a: Activity, paketi: List<String>, obKoncu: () -> Unit = {}) {
        if (paketi.isEmpty()) { obKoncu(); return }
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(a).setTitle(R.string.os_komplet_naslov).setMessage(Posodobitve.besedilo(a, R.string.os_posodobitev_dovoljenje))
                .setPositiveButton(R.string.os_posodobitev_dovoli) { _, _ ->
                    try { a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + a.packageName))) }
                    catch (_: Throwable) { try { a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)) } catch (_: Throwable) { } }
                }.setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener { obKoncu() }
                .show()
            return
        }

        val dp = { v: Int -> (v * a.resources.displayMetrics.density).toInt() }
        val napredek = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; isIndeterminate = false }
        val opis = TextView(a).apply { text = a.getString(R.string.os_komplet_prenasam) }
        val vsebina = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(opis); addView(napredek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        var prekinjeno = false
        val okno = AlertDialog.Builder(a).setTitle(R.string.os_komplet_naslov).setView(vsebina).setCancelable(true)
            .setNegativeButton(android.R.string.cancel) { _, _ -> prekinjeno = true }
            .setOnCancelListener { prekinjeno = true }
            .setOnDismissListener { obKoncu() }
            .show()
        val glavna = Handler(Looper.getMainLooper())
        
        Thread({
            val mapa = File(a.cacheDir, "namestitve").apply { mkdirs() }
            var trenutenPaket = ""
            try {
                mapa.listFiles()?.forEach { it.delete() }
                
                val c = URL(Posodobitve.MANIFEST).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("User-Agent", "SafeerOS/${BuildConfig.VERSION_NAME} (${a.packageName})")
                c.setRequestProperty("Cache-Control", "no-cache")
                val besedilo = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                
                val vnosi = KompletPravila.vnosi(JSONObject(besedilo), paketi)
                if (vnosi == null) {
                    throw IllegalStateException("ni veljavnega vnosa za vse pakete")
                }

                val prenesene = mutableListOf<Pair<String, File>>()
                for (paket in paketi) {
                    if (prekinjeno) break
                    trenutenPaket = paket
                    val vnos = vnosi[paket] ?: throw IllegalStateException("ni vnosa za $paket")
                    glavna.post { opis.text = a.getString(R.string.os_komplet_prenasam_paket, paket); napredek.progress = 0; napredek.isIndeterminate = true }
                    val cilj = File(mapa, "$paket.apk")
                    Posodobitve.prenesiDatoteko(vnos.url, vnos.sha256, 0L, cilj, glavna, { prekinjeno }) { d -> 
                        if (napredek.isIndeterminate) napredek.isIndeterminate = false
                        napredek.progress = d 
                    }
                    
                    val podpis = NamestitevSeja.podpis(a, cilj)
                    if (podpis != NamestitevPravila.Podpis.NAS) {
                        throw IllegalStateException("napacen podpis ($podpis) za $paket")
                    }
                    prenesene.add(paket to cilj)
                }
                
                glavna.post {
                    okno.dismiss()
                    if (prekinjeno || a.isFinishing) {
                        mapa.listFiles()?.forEach { it.delete() }
                        return@post
                    }
                    
                    for ((paket, cilj) in prenesene) {
                        val ime = if (paket == Sosed.TRGOVINA) "Safeer Play Store" else "Safeer TV Browser"
                        NamestitevSeja.namesti(a, cilj, vnosi[paket]!!.sha256, paket, ime, NamestitevPravila.Izvor.POSODOBITEV) { d ->
                            if (!NamestitevSeja.sistemskiNamestitelj(d, "namestitve", cilj))
                                Toast.makeText(d, R.string.os_posodobitev_napaka, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (t: Throwable) {
                Posodobitve.log("komplet prenos napaka: $t")
                mapa.listFiles()?.forEach { it.delete() }
                glavna.post {
                    okno.dismiss()
                    if (!prekinjeno) {
                        val msg = if (trenutenPaket.isNotEmpty()) a.getString(R.string.os_komplet_napaka_paket, trenutenPaket) 
                                  else a.getString(R.string.os_posodobitev_napaka)
                        Toast.makeText(a, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }, "safeer-komplet-prenos").start()
    }
}
