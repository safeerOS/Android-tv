package si.safeer.tv.os

import android.content.Context
import android.widget.Toast
import org.json.JSONObject
import si.safeer.tv.R
import si.safeer.tv.link.Daljinec

/**
 * Povezani zasloni za naprave Android (telefon, tablica, televizor): ta naprava zaprosi drugo, naj ji deli cel zaslon.
 * Druga naprava pokaze sistemsko potrditev (Android jo zahteva za vsako deljenje - je ni mogoce obiti); po potrditvi
 * slika pride sem kot share.screen in se odpre v gledalcu (CastReceiverService). Lastnik, 11. 10. 2026.
 */
object PovezaniZasloni {
    fun zaprosi(c: Context, link: LinkOdjemalec, r: LinkOdjemalec.Naprava) {
        val ime = r.ime.ifBlank { r.id }
        link.ukaz(r.id, "apps.launch", JSONObject().put("app", Daljinec.CEL_ZASLON).put("stream", true), 15_000) { izid, napaka ->
            val ok = napaka.isEmpty() && izid?.optBoolean("ok") == true
            val besedilo = if (ok) c.getString(R.string.os_zasloni_potrdi_na, ime)
                else c.getString(R.string.os_zasloni_ni_uspelo, ime)
            Toast.makeText(c, besedilo, Toast.LENGTH_LONG).show()
        }
    }
}
