package si.safeer.tv.os

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import si.safeer.tv.R

/**
 * Ponovno odpiranje datoteke iz vrste Nadaljuj (domaci zaslon televizorja in zaganjalnik tablice/telefona -
 * ena koda, ne dve). Datoteka z racunalnika: najprej si od njega izprosimo svezo sejo (zetona nikoli ne hranimo,
 * glej [Nadaljuj]), sele nato odpremo; krajevna datoteka se odpre takoj.
 */
object NadaljujOdpri {

    /** [niVec] dobi true, ce racunalnika ni v Linku, false, ce datoteke ni bilo mogoce odpreti. */
    fun datoteka(a: Activity, link: LinkUpravitelj, n: Nadaljuj.Vnos, niVec: (Boolean) -> Unit) {
        if (n.krajevno) { odpri(a, n, null); return }
        val r = link.naprave.firstOrNull { it.id == n.racunalnik } ?: run { niVec(true); return }
        if (OzjiKrog.zavrni(a, r.id, r.ime)) return              // ni_ozji_krog
        Toast.makeText(a, a.getString(R.string.os_nadaljuj_odpiram, n.ime), Toast.LENGTH_SHORT).show()
        link.ukaz(r.id, "files.list", org.json.JSONObject().put("folder", ""), 12_000,
            LinkOdjemalec.Odgovor { izid, _ ->
                if (a.isFinishing) return@Odgovor
                val streznik = izid?.optJSONObject("data")?.optJSONObject("server")
                if (izid?.optBoolean("ok") != true || streznik == null) { niVec(false); return@Odgovor }
                odpri(a, n, DatotekeActivity.Streznik(
                    streznik.optString("base_url").trimEnd('/'),
                    streznik.optString("fp"), streznik.optString("token"), r.id))
            })
    }

    fun odpri(a: Activity, n: Nadaljuj.Vnos, s: DatotekeActivity.Streznik?) {
        val naslovDatoteke = if (s == null) n.id else s.url(n.id)
        val namera = when (n.vrsta) {
            Nadaljuj.SLIKA -> Intent(a, SlikaActivity::class.java)
                .putStringArrayListExtra("urli", arrayListOf(naslovDatoteke))
                .putStringArrayListExtra("imena", arrayListOf(n.ime))
                .putExtra("zacetek", 0)
            Nadaljuj.BESEDILO -> Intent(a, BesediloActivity::class.java)
                .putExtra("url", naslovDatoteke).putExtra("ime", n.ime)
            else -> Intent(a, PredvajalnikActivity::class.java)
                .putExtra("url", naslovDatoteke).putExtra("ime", n.ime)
                .putExtra("mime", n.mime).putExtra("zvok", n.vrsta == Nadaljuj.GLASBA)
        }
        namera.putExtra("lokalno", s == null)
        if (s != null) { val b = Bundle(); s.vBundle(b); namera.putExtras(b) }
        try { a.startActivity(namera) } catch (e: Throwable) {
            Toast.makeText(a, a.getString(R.string.os_odpri_napaka, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }
}
