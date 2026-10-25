package si.safeer.tv.os

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import si.safeer.tv.R
import si.safeer.tv.cast.Dostop
import si.safeer.tv.cast.DostopPravila
import si.safeer.tv.link.OzjiKrogPravila

/**
 * Ozji krog zaupanja te naprave (pravila: [OzjiKrogPravila], isto kot na racunalniku). Programe, datoteke in zaslon
 * druge naprave Safeer OS pokaze in odpre samo, ce je tista naprava v ozjem krogu te naprave (cast/Dostop: ta naprava
 * ji je odprla vsaj eno zmoznost). Naprava zunaj kroga (npr. druzinski telefon) ostane v Safeer Linku in pomaga pri
 * povezavi, njenih vsebin pa tu ni. Krog uporabnik ustvari in ureja v Nastavitvah (»Ozji krog zaupanja«), podrobno po
 * zmoznostih pa v Safeer Linku.
 */
object OzjiKrog {
    private const val TAG = "SafeerOzjiKrog"

    fun vKrogu(c: Context, id: String): Boolean = try {
        id.isNotBlank() && OzjiKrogPravila.vKrogu(Dostop.zmoznosti(c, id), Dostop.jeTaNaprava(c, id))
    } catch (_: Throwable) { false }     // »ne vem« ni »da«

    fun samoKrog(c: Context, naprave: List<LinkOdjemalec.Naprava>): List<LinkOdjemalec.Naprava> =
        OzjiKrogPravila.samoKrog(naprave) { vKrogu(c, it.id) }

    /**
     * Neposreden klic (programi, datoteke, zaslon) za napravo zunaj ozjega kroga: zavrnemo ga tu, tista naprava ne dobi
     * nobenega ukaza. Uporabnik izve, kje napravo doda v krog. Vrne true, ce je klic zavrnjen.
     */
    fun zavrni(c: Context, id: String, ime: String): Boolean {
        if (vKrogu(c, id)) return false
        Log.i(TAG, "Zavrnjeno (${OzjiKrogPravila.KODA}): ${id.take(12)} ni v ozjem krogu te naprave")
        Toast.makeText(c, c.getString(R.string.os_ozji_krog_zavrnjeno, ime.ifBlank { id }), Toast.LENGTH_LONG).show()
        return true
    }

    /** Druge naprave v Linku, ena vrstica na fizicno napravo (brskalnik in Safeer OS istega televizorja sta ena). */
    private fun naprave(c: Context, link: LinkUpravitelj): List<Pair<String, String>> {
        val videne = HashSet<String>()
        val izid = ArrayList<Pair<String, String>>()
        for (n in link.naprave) {
            if (link.jeTaNaprava(n)) continue
            val jedro = try { Dostop.jedro(c, n.id) } catch (_: Throwable) { "" }
            if (jedro.isBlank() || !videne.add(jedro)) continue
            izid.add(DatotekeActivity.lepoIme(n.ime).ifBlank { n.ime.ifBlank { n.id } } to n.id)
        }
        return izid
    }

    /** Koliko drugih naprav je ta trenutek v ozjem krogu (za vrstico v Nastavitvah). */
    fun stevilo(c: Context, link: LinkUpravitelj): Int = naprave(c, link).count { vKrogu(c, it.second) }

    /**
     * Nastavitve -> »Ozji krog zaupanja«: seznam naprav v Linku, oznacene so v krogu. Nova v krogu dobi vse zmoznosti,
     * odstranjena nic; delni dostop ostane (podrobno: Safeer Link). [potem] po shranjevanju (preris vrstice).
     */
    fun uredi(a: Activity, link: LinkUpravitelj, potem: () -> Unit) {
        val seznam = naprave(a, link)
        if (seznam.isEmpty()) {
            Toast.makeText(a, a.getString(R.string.os_ozji_krog_ni_naprav), Toast.LENGTH_LONG).show(); return
        }
        val prej = seznam.associate { (_, id) -> id to (try { Dostop.zmoznosti(a, id) } catch (_: Throwable) { emptySet() }) }
        val izbrano = BooleanArray(seznam.size) { vKrogu(a, seznam[it].second) }
        val okno = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(a.getString(R.string.os_ozji_krog))
            .setMultiChoiceItems(seznam.map { it.first }.toTypedArray(), izbrano) { _, i, da -> izbrano[i] = da }
            .setPositiveButton(a.getString(R.string.os_naprave_shrani)) { _, _ ->
                val izbrane = seznam.filterIndexed { i, _ -> izbrano[i] }.map { it.second }.toSet()
                val spremembe = OzjiKrogPravila.spremembe(prej, izbrane, DostopPravila.VSE_ZMOZNOSTI)
                for ((id, dano) in spremembe) Dostop.nastavi(a, id, dano)
                Toast.makeText(a, a.getString(R.string.os_ozji_krog_shranjeno, izbrane.size), Toast.LENGTH_LONG).show()
                // Ena privolitev (lastnik 25. 10. 2026 10:49): naprava, ki je na novo v krogu, dobi tudi datoteke te naprave -
                // Android za to takoj vprasa za svoje dovoljenje (vse datoteke, sicer mediji), brez locenega stikala.
                if (OzjiKrogPravila.novaZmoznost(prej, spremembe, DostopPravila.Zmoznost.DATOTEKE))
                    si.safeer.tv.link.DatotekeStreznik.poPrivolitvi(a)
                potem()
            }
            .setNeutralButton(a.getString(R.string.os_ozji_krog_podrobno)) { _, _ ->
                try { a.startActivity(Intent(a, NapraveActivity::class.java)) } catch (_: Throwable) { }
            }
            .setNegativeButton(a.getString(R.string.os_preklici), null)
            .create()
        Kontroler.pokazi(okno.also { it.show() })
    }
}
