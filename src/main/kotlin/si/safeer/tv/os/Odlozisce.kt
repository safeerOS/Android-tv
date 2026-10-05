package si.safeer.tv.os

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import si.safeer.tv.R

/** Kopiranje besedila v odlozisce naprave (prejeto besedilo, sporocilo). */
object Odlozisce {

    /**
     * Vrne true, ce je besedilo v odloziscu. Android 13+ kopiranje potrdi sam (predogled ob robu zaslona);
     * na starejsih pokazemo kratko potrdilo, sicer uporabnik ne ve, ali se je kaj zgodilo.
     */
    fun kopiraj(c: Context, besedilo: String): Boolean = try {
        val odlozisce = c.getSystemService(ClipboardManager::class.java)
        if (odlozisce == null) false else {
            odlozisce.setPrimaryClip(ClipData.newPlainText("Safeer", besedilo))
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(c, R.string.os_sporocila_kopirano, Toast.LENGTH_SHORT).show()
            true
        }
    } catch (_: Throwable) {
        false
    }
}
