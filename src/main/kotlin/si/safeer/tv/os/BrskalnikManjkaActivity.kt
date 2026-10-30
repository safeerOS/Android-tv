package si.safeer.tv.os

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import si.safeer.tv.R

/**
 * Safeer OS na televizorju spletnih vsebin ne odpira sam (en sam WebView, v TV Browserju). Ce TV Browserja ni,
 * to povemo in ponudimo namestitev iz Safeer Play Store; vgrajenega brskalnika ne vklopimo nazaj.
 */
class BrskalnikManjkaActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertDialog.Builder(this)
            .setTitle(R.string.os_tvb_manjka_naslov)
            .setMessage(R.string.os_tvb_manjka_opis)
            .setPositiveButton(R.string.os_tvb_manjka_namesti) { _, _ -> odpriTrgovino() }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { finish() }
            .show()
    }

    private fun odpriTrgovino() {
        val namera = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + Sosed.BRSKALNIK))
            .setPackage(TRGOVINA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(namera) } catch (_: Throwable) {
            val zagon = packageManager.getLeanbackLaunchIntentForPackage(TRGOVINA) ?: packageManager.getLaunchIntentForPackage(TRGOVINA)
            try { if (zagon != null) startActivity(zagon) } catch (_: Throwable) { }
        }
    }

    private companion object {
        /** Safeer Play Store (Aurora Store). */
        const val TRGOVINA = "com.aurora.store"
    }
}
