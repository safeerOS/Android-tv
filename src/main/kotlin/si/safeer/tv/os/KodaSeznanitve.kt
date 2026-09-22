package si.safeer.tv.os

import android.content.Context
import android.os.Bundle
import si.safeer.tv.cast.HubKrmilnik
import si.safeer.tv.cast.LinkSorodnikStoritev

/**
 * Koda za seznanitev nove naprave (tablica, telefon, racunalnik) s srediscem na tem televizorju.
 *
 * Sredisce tece v brskalniku Safeer; ta je kodo pokazal samo, kadar je bil sam na zaslonu. Kdor je
 * bil v Safeer OS (to je skoraj vedno), kode ni videl in se nova naprava ni mogla povezati. Zato
 * Safeer OS, dokler je na zaslonu, sredisce vprasa po cakajocih prijavah (prek storitve istega
 * podpisa, brez omrezja) in kodo pokaze sam.
 */
object KodaSeznanitve {

    data class Prijava(val pairId: String, val ime: String, val pin: String)

    fun poglej(context: Context, naprej: (List<Prijava>) -> Unit) {
        val app = context.applicationContext
        val brskalnik = Sosed.linkBrskalnik(app)
        if (brskalnik == null) {
            val seznam = try {
                HubKrmilnik.cakajocePrijave().map { Prijava(it.pairId, it.ime, it.pin) }
            } catch (_: Throwable) { emptyList() }
            naprej(seznam)
            return
        }
        Sosed.poslji(app, brskalnik, LinkSorodnikStoritev.DEJANJE, LinkSorodnikStoritev.PRIJAVE,
            LinkSorodnikStoritev.PRIJAVE_ODGOVOR, null, 2_000) { b -> naprej(izBundla(b)) }
    }

    fun zavrni(context: Context, pairId: String) {
        val app = context.applicationContext
        val brskalnik = Sosed.linkBrskalnik(app)
        if (brskalnik == null) {
            try { HubKrmilnik.zavrniPrijavo(pairId) } catch (_: Throwable) { }
            return
        }
        Sosed.poslji(app, brskalnik, LinkSorodnikStoritev.DEJANJE, LinkSorodnikStoritev.ZAVRNI,
            LinkSorodnikStoritev.ZAVRNI_ODGOVOR, Bundle().apply { putString("pair_id", pairId) }, 2_000) { }
    }

    private fun izBundla(b: Bundle?): List<Prijava> {
        if (b == null) return emptyList()
        val id = b.getStringArray("pair_id") ?: return emptyList()
        val ime = b.getStringArray("ime") ?: return emptyList()
        val pin = b.getStringArray("pin") ?: return emptyList()
        return id.indices.mapNotNull { i ->
            if (i < ime.size && i < pin.size && pin[i].isNotBlank()) Prijava(id[i], ime[i], pin[i]) else null
        }
    }
}
