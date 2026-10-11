package si.safeer.tv.os

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import org.json.JSONObject
import si.safeer.tv.R
import java.text.Normalizer

/**
 * »Prikazi na TV / na racunalniku« (lastnik, 11. 10. 2026): kar uporabnik pove ali natipka v iskanju Safeer Media
 * (»Jungle Book na TV«), se odpre v Medijskem centru izbrane naprave v Safeer Linku (ukaz `media.open`: naslov, ki ga
 * tam poisce iskanje). Ce je naprav te vrste vec (npr. dva racunalnika), uporabnik izbere. Isti vzorci kot v lupi na
 * racunalniku (assets/os/delovna.js ciljMedija): slovenscina, anglescina, nemscina, spanscina, portugalscina,
 * francoscina, italijanscina.
 */
object PrikaziNa {
    data class Cilj(val naslov: String, val vrste: List<String>)

    private val PC = listOf("windows", "linux", "mac")
    private val VZORCI: List<Pair<Regex, List<String>>> = listOf(
        Regex(" (na|v) (tv|televiziji|televizorju|televizor|televizijo)$") to listOf("tv"),
        Regex(" na (racunalniku|racunalnik|pc|prenosniku)$") to PC,
        Regex(" na (tablici|tablico|tablica)$") to listOf("tablet"),
        Regex(" na (telefonu|telefon|mobitelu|mobitel)$") to listOf("phone"),
        Regex(" on (the |my )?(tv|television|telly)$") to listOf("tv"),
        Regex(" on (the |my |this )?(computer|pc|laptop)$") to PC,
        Regex(" on (the |my )?tablet$") to listOf("tablet"),
        Regex(" on (the |my )?(phone|mobile)$") to listOf("phone"),
        Regex(" (auf|im) (dem |meinem )?(fernseher|tv|fernsehen)$") to listOf("tv"),
        Regex(" auf (dem |meinem )?(computer|pc|rechner|laptop)$") to PC,
        Regex(" auf (dem |meinem )?tablet$") to listOf("tablet"),
        Regex(" auf (dem |meinem )?(handy|telefon|smartphone)$") to listOf("phone"),
        Regex(" en (la |mi )?(tele|television|tv)$") to listOf("tv"),
        Regex(" en (el |mi )?(ordenador|computador|computadora|pc|portatil)$") to PC,
        Regex(" en (la |mi )?tableta$") to listOf("tablet"),
        Regex(" en (el |mi )?(movil|telefono|celular)$") to listOf("phone"),
        Regex(" n(a|o) (tv|televisao|televisor)$") to listOf("tv"),
        Regex(" no (computador|pc|portatil)$") to PC,
        Regex(" no tablet$") to listOf("tablet"),
        Regex(" no (telemovel|celular|telefone)$") to listOf("phone"),
        Regex(" sur (la |ma )?(tele|television|tv)$") to listOf("tv"),
        Regex(" sur (lordinateur|mon ordinateur|le pc|mon pc)$") to PC,
        Regex(" sur (la |ma )?tablette$") to listOf("tablet"),
        Regex(" sur (le |mon )?(telephone|portable|mobile)$") to listOf("phone"),
        Regex(" (sulla|in) (tv|televisione)$") to listOf("tv"),
        Regex(" sul (computer|pc)$") to PC,
        Regex(" sul tablet$") to listOf("tablet"),
        Regex(" sul (telefono|cellulare)$") to listOf("phone"),
    )
    private val GLAGOLI = setOf("prikazi", "pokazi", "predvajaj", "odpri", "zavrti", "show", "play", "open", "put", "zeige",
        "spiele", "offne", "muestra", "reproduce", "pon", "abre", "mostra", "reproduz", "toca", "passa",
        "montre", "joue", "ouvre", "lance", "mostra", "riproduci", "apri", "metti")

    private fun kljuc(beseda: String): String =
        Normalizer.normalize(beseda.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .replace("đ", "d").replace("ł", "l").replace("ø", "o").replace("ß", "ss")
            .replace(Regex("[^\\p{L}\\p{N}]+"), "")

    /** Cilj na koncu niza ali null; naslov je niz brez cilja in brez glagola na zacetku (lahko prazen). */
    fun razcleni(niz: String): Cilj? {
        val besede = niz.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val k = " " + besede.joinToString(" ") { kljuc(it) }
        for ((vzorec, vrste) in VZORCI) {
            val m = vzorec.find(k) ?: continue
            var ostanek = besede.take(besede.size - m.value.trim().split(" ").size)
            if (ostanek.isNotEmpty() && kljuc(ostanek[0]) in GLAGOLI) ostanek = ostanek.drop(1)
            return Cilj(ostanek.joinToString(" "), vrste)
        }
        return null
    }

    /** Naprave v Linku za cilj (vse z daljincem razen te). */
    fun naprave(ctx: Context, cilj: Cilj): List<LinkOdjemalec.Naprava> {
        val link = LinkUpravitelj.pridobi(ctx.applicationContext)
        return link.naprave.filter { !link.jeTaNaprava(it) && "remote" in it.zmoznosti && it.platforma in cilj.vrste }
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
