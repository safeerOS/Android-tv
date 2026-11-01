package si.safeer.tv.os

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import si.safeer.tv.R
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Safeer OS na televizorju spletnih vsebin ne odpira sam (en sam WebView, v TV Browserju). Ce TV Browserja ni,
 * to povemo in ponudimo namestitev iz Safeer Play Store; vgrajenega brskalnika ne vklopimo nazaj.
 */
class BrskalnikManjkaActivity : Activity() {
    private var izbranNamesti = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertDialog.Builder(this)
            .setTitle(R.string.os_tvb_manjka_naslov)
            .setMessage(R.string.os_tvb_manjka_opis)
            .setPositiveButton(R.string.os_tvb_manjka_namesti) { _, _ -> izbranNamesti = true; odpriTrgovino() }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (!izbranNamesti) finish() }
            .show()
    }

    private fun odpriTrgovino() {
        val manjkajo = KompletPravila.manjkajoci(Sosed.jeTelevizor(this), Sosed.smoOs(this),
            setOf(Sosed.BRSKALNIK, Sosed.TRGOVINA).filter { Sosed.namescen(this, it) }.toSet())
        if (manjkajo.isEmpty()) { odpriStaro(); return }

        Thread {
            try {
                val c = URL(Posodobitve.MANIFEST).openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 5000
                c.setRequestProperty("User-Agent", "SafeerOS/${si.safeer.tv.BuildConfig.VERSION_NAME} ($packageName)")
                c.setRequestProperty("Cache-Control", "no-cache")
                val besedilo = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                
                val vnosi = KompletPravila.vnosi(JSONObject(besedilo), manjkajo)
                runOnUiThread {
                    if (vnosi != null) {
                        KompletNamestitev.namesti(this, manjkajo) { finish() }
                    } else {
                        if (Sosed.namescen(this, Sosed.TRGOVINA)) odpriStaro() else finish()
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    if (Sosed.namescen(this, Sosed.TRGOVINA)) odpriStaro() 
                    else {
                        android.widget.Toast.makeText(this, R.string.os_posodobitev_napaka, android.widget.Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
        }.start()
    }

    private fun odpriStaro() {
        val namera = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + Sosed.BRSKALNIK))
            .setPackage(Sosed.TRGOVINA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(namera) } catch (_: Throwable) {
            val zagon = packageManager.getLeanbackLaunchIntentForPackage(Sosed.TRGOVINA) ?: packageManager.getLaunchIntentForPackage(Sosed.TRGOVINA)
            try { if (zagon != null) startActivity(zagon) } catch (_: Throwable) { }
        }
        finish()
    }
}
