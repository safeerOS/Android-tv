package si.safeer.tv.os

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import org.json.JSONObject
import si.safeer.tv.R

/**
 * »Prikazi na TV / na racunalniku« (lastnik, 11. 10. 2026): kar uporabnik pove ali natipka v iskanju Safeer Media
 * (»Jungle Book na TV«), se odpre v Medijskem centru izbrane naprave v Safeer Linku (ukaz `media.open`: naslov, ki ga
 * tam poisce iskanje). Ce je naprav te vrste vec (npr. dva racunalnika), uporabnik izbere. Isti vzorci kot v lupi na
 * racunalniku (assets/os/delovna.js ciljMedija): slovenscina, anglescina, nemscina, spanscina, portugalscina,
 * francoscina, italijanscina.
 */
object PrikaziNa {
    data class Cilj(val naslov: String, val vrste: List<String>)

    /**
     * Cilj na koncu niza ali null; naslov je niz brez cilja in brez glagola na zacetku (lahko prazen). Naloga 42 E: ena
     * razclemba za lupo, glas in iskanje v Medijih - [LupaPravila.razcleni] (tu samo cilj po vrsti naprave).
     */
    fun razcleni(niz: String): Cilj? =
        LupaPravila.razcleni(niz).takeIf { it.vrsta == LupaPravila.PRIKAZI_NA && it.vrsteNaprav.isNotEmpty() }
            ?.let { Cilj(it.vsebina, it.vrsteNaprav) }

    /** Naprave v Linku za cilj (vse z daljincem razen te), naloga 42 E: samo v ozjem krogu. */
    fun naprave(ctx: Context, cilj: Cilj): List<LinkOdjemalec.Naprava> {
        val link = LinkUpravitelj.pridobi(ctx.applicationContext)
        return link.naprave.filter { !link.jeTaNaprava(it) && "remote" in it.zmoznosti && it.platforma in cilj.vrste &&
            OzjiKrog.vKrogu(ctx, it.id) }
    }

    /** Izbira naprave (ena: takoj; vec: uporabnik izbere) in posiljanje; izid kot kratko sporocilo. */
    fun izberiInPoslji(a: Activity, cilj: Cilj) {
        val seznam = naprave(a, cilj)
        when {
            seznam.isEmpty() -> Toast.makeText(a, R.string.os_prikazi_na_ni_naprave, Toast.LENGTH_LONG).show()
            seznam.size == 1 -> poslji(a, seznam[0], cilj.naslov)
            else -> AlertDialog.Builder(a).setTitle(R.string.os_prikazi_na_izberi)
                .setItems(seznam.map { DatotekeActivity.lepoIme(it.ime) }.toTypedArray()) { _, i -> poslji(a, seznam[i], cilj.naslov) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    fun poslji(a: Activity, n: LinkOdjemalec.Naprava, naslov: String) {
        val ime = DatotekeActivity.lepoIme(n.ime)
        LinkUpravitelj.pridobi(a.applicationContext).ukaz(n.id, "media.open", JSONObject().put("title", naslov), 15_000,
            LinkOdjemalec.Odgovor { izid, napaka ->
                a.runOnUiThread {
                    if (a.isFinishing) return@runOnUiThread
                    val koda = izid?.optString("code").orEmpty()
                    val sporocilo = when {
                        izid?.optBoolean("ok") == true -> a.getString(R.string.os_prikazi_na_odpira, ime, naslov)
                        koda == "neznano_dejanje" || koda == "ni_podprto" -> a.getString(R.string.os_prikazi_na_ne_zna, ime)
                        else -> a.getString(R.string.os_prikazi_na_napaka, ime) + if (napaka.isNotBlank()) " ($napaka)" else ""
                    }
                    Toast.makeText(a, sporocilo, Toast.LENGTH_LONG).show()
                }
            })
    }

    /** Naslov za `media.open` (prejemnik): obrezan, brez kontrolnih znakov. */
    fun naslovIz(p: JSONObject): String = p.optString("title").replace(Regex("\\p{Cntrl}"), " ").trim().replace(Regex("\\s+"), " ").take(200)
}
