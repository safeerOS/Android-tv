package si.safeer.tv.os

import android.content.Context
import android.os.Bundle
import si.safeer.tv.cast.LinkSorodnikStoritev
import si.safeer.tv.cast.PridruzitevSredisca

/**
 * QR koda za pridruzitev naprave (prijavno okno Safeer OS). Sredisce je v brskalniku, ce je namescen
 * (vprasamo ga prek storitve istega podpisa), sicer v tem procesu.
 */
object PridruzitevKoda {

    /** [naprej] dobi Bundle s "povezava", "qr_id", "velja_ms" ali "napaka". */
    fun nova(context: Context, preklici: String, naprej: (Bundle) -> Unit) {
        val app = context.applicationContext
        val brskalnik = Sosed.linkBrskalnik(app)
        if (brskalnik == null) {
            Thread {
                val b = PridruzitevSredisca.nova(app, preklici)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    // Sredisce je na drugi napravi (npr. Safeer Control): kodo naredi ono, mi jo le pokazemo.
                    if (b.getString("napaka") == "drugo_sredisce") odTujegaSredisca(app, preklici, naprej) else naprej(b)
                }
            }.start()
            return
        }
        Sosed.poslji(app, brskalnik, LinkSorodnikStoritev.DEJANJE, LinkSorodnikStoritev.PRIDRUZITEV,
            LinkSorodnikStoritev.PRIDRUZITEV_ODGOVOR, Bundle().apply { putString("preklici", preklici) }, 8_000) { b ->
            naprej(b ?: Bundle().apply { putString("napaka", "ni_sredisca") })
        }
    }

    /**
     * QR koda sredisca, ki tece na DRUGI napravi v Linku: povabilo zahtevamo po Safeer Linku
     * (pair.invite), sredisce vrne enkratno skrivnost, svoj naslov in odtis potrdila.
     */
    private fun odTujegaSredisca(app: Context, preklici: String, naprej: (Bundle) -> Unit) {
        val link = LinkUpravitelj.pridobi(app)
        if (preklici.isNotBlank()) link.odjemalec.prekliciPovabilo(preklici)
        link.odjemalec.zahtevajPovabilo(preklici) { p ->
            val b = Bundle()
            if (p == null || p.naslov.isBlank() || p.odtis.isBlank()) {
                b.putString("napaka", "drugo_sredisce")
            } else {
                b.putString("qr_id", p.qrId)
                b.putString("povezava", PridruzitevSredisca.povezavaZaTujeSredisce(p.naslov, p.odtis, p.qrId, p.skrivnost))
                b.putLong("velja_ms", p.veljaMs)
            }
            naprej(b)
        }
    }

    /** Zadnja pridruzitev (cas ms, ime) ali null. */
    fun zadnja(context: Context, naprej: (Pair<Long, String>?) -> Unit) {
        val app = context.applicationContext
        val brskalnik = Sosed.linkBrskalnik(app)
        if (brskalnik == null) { naprej(PridruzitevSredisca.zadnja); return }
        Sosed.poslji(app, brskalnik, LinkSorodnikStoritev.DEJANJE, LinkSorodnikStoritev.PRIDRUZITEV_STANJE,
            LinkSorodnikStoritev.PRIDRUZITEV_STANJE_ODGOVOR, null, 2_000) { b ->
            val cas = b?.getLong("cas", 0L) ?: 0L
            naprej(if (cas > 0L) cas to b?.getString("ime").orEmpty() else null)
        }
    }

    /** Okno se zapira; [brezPovezave] = uporabnik je izbral »Nadaljuj brez povezave naprav«. */
    fun konec(context: Context, qrId: String, brezPovezave: Boolean) {
        val app = context.applicationContext
        val brskalnik = Sosed.linkBrskalnik(app)
        if (brskalnik == null) {
            PridruzitevSredisca.preklici(qrId)
            // Koda tujega sredisca (drugo napravo v Linku) preklicemo po Safeer Linku.
            try { LinkUpravitelj.pridobi(app).odjemalec.prekliciPovabilo(qrId) } catch (_: Throwable) { }
            if (brezPovezave) PridruzitevSredisca.izklopiCeSamoZaKodo(app)
            return
        }
        Sosed.poslji(app, brskalnik, LinkSorodnikStoritev.DEJANJE, LinkSorodnikStoritev.PRIDRUZITEV_KONEC,
            LinkSorodnikStoritev.PRIDRUZITEV_KONEC_ODGOVOR, Bundle().apply {
                putString("qr_id", qrId); putBoolean("brez_povezave", brezPovezave)
            }, 2_000) { }
    }
}
