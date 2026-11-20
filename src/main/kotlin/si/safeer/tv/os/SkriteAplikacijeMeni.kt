package si.safeer.tv.os

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast

/** Seznam skritih aplikacij (glej [SkriteAplikacijePravila]); dotik na aplikaciji jo vrne na domaci zaslon. */
object SkriteAplikacijeMeni {
    fun pokazi(a: Activity, poPokazano: () -> Unit) {
        val skrite = SkriteAplikacije.seznam(a).toList()
        val pm = a.packageManager
        val imena = skrite.map { p -> try { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } catch (_: Throwable) { p } }
        val gradnik = AlertDialog.Builder(a, si.safeer.tv.R.style.Os_Okno).setTitle(a.getString(si.safeer.tv.R.string.os_skrite_naslov))
        if (skrite.isEmpty()) gradnik.setMessage(a.getString(si.safeer.tv.R.string.os_skrite_prazno))
        else gradnik.setItems(imena.toTypedArray()) { _, i ->
            Aplikacije.pokazi(a, skrite[i])
            Aplikacije.osveziVOzadju(a, null, brezIkon = true) { if (!a.isFinishing && !a.isDestroyed) poPokazano() }
            Toast.makeText(a, a.getString(si.safeer.tv.R.string.os_pokazano_sporocilo, imena[i]), Toast.LENGTH_SHORT).show()
        }
        val okno = gradnik.setNegativeButton(a.getString(si.safeer.tv.R.string.os_preklici), null).create()
        Kontroler.pokazi(okno)
        okno.show()
    }
}
