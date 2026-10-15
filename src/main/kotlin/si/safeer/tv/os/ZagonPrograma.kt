package si.safeer.tv.os

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import org.json.JSONObject
import si.safeer.tv.R

/**
 * Zagon programa ali aplikacije druge naprave v Safeer Linku z domacega zaslona (vrsta »Na tvojih napravah«).
 * Racunalnik (zmoznost "apps") program odpre pri sebi; ce deli zaslon, ga takoj pokazemo tu. Android naprava
 * (tablica, telefon, TV) aplikacijo odpre in po potrditvi deli svoj zaslon nazaj (stream) - isto vedenje kot
 * na zaslonu Programi.
 */
object ZagonPrograma {

    fun zazeni(a: Activity, link: LinkUpravitelj, napravaId: String, program: String, ime: String, igra: Boolean) {
        val r = link.naprave.firstOrNull { it.id == napravaId }
        if (r == null) {
            Toast.makeText(a, a.getString(R.string.os_nadaljuj_ni_racunalnika), Toast.LENGTH_LONG).show()
            return
        }
        val android = !r.zmoznosti.contains("apps")
        val parametri = JSONObject().put("app", program)
        if (android) parametri.put("stream", true)
        Toast.makeText(a, a.getString(R.string.os_nadaljuj_odpiram, ime), Toast.LENGTH_SHORT).show()
        link.ukaz(r.id, "apps.launch", parametri, if (android) 15_000 else 10_000, LinkOdjemalec.Odgovor { izid, napaka ->
            if (a.isFinishing) return@Odgovor
            if (izid?.optBoolean("ok") != true) {
                val stanje = DostopNaprave.stanje(izid)
                val besedilo = if (stanje != null && stanje != ProsnjaDostopa.Stanje.IZKLOPLJENO)
                    DostopNaprave.besedilo(a, stanje, r.ime.ifBlank { r.id }, R.string.os_programi_izklopljeno)
                else a.getString(R.string.os_programi_napaka, izid?.optString("message").orEmpty().ifBlank { napaka })
                Toast.makeText(a, besedilo, Toast.LENGTH_LONG).show()
                return@Odgovor
            }
            Nadaljuj.zapisi(a, Nadaljuj.Vnos(vrsta = Nadaljuj.PROGRAM, ime = ime, racunalnik = r.id, program = program, igra = igra))
            if (!android && r.zmoznosti.contains("desktop")) {
                try {
                    a.startActivity(Intent(a, ZaslonActivity::class.java)
                        .putExtra(DatotekeActivity.EXTRA_RACUNALNIK, r.id)
                        .putExtra(ZaslonActivity.EXTRA_ZASLON, "apps")
                        .putExtra(ZaslonActivity.EXTRA_PROGRAM, program)
                        .putExtra(ZaslonActivity.EXTRA_IGRA, igra))
                } catch (_: Throwable) { }
            } else {
                Toast.makeText(a, a.getString(if (android) R.string.os_programi_zagnan_naprava else R.string.os_programi_zagnan,
                    ime, r.ime.ifBlank { r.id }), Toast.LENGTH_LONG).show()
            }
        })
    }
}
