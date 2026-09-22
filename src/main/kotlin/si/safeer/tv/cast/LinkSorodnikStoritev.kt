package si.safeer.tv.cast

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log

/**
 * Sorodne aplikacije na istem televizorju (Safeer OS) vstopijo v Safeer Link brez kode.
 *
 * Storitev je zascitena z dovoljenjem istega podpisa (si.safeer.tv.permission.LINK): odgovor dobi
 * samo aplikacija, podpisana z istim kljucem kot ta brskalnik. Vrne naslov sredisca na tem
 * televizorju, zeton sorodnika (`<id sredisca>-<pripona>`) in odtis potrdila - enako, kot to za
 * Safeer Control naredi koncna tocka /cast/pair/sibling, le v procesu in brez omrezja.
 * Ce Safeer Link ni prizgan, ga prizge: sorodna aplikacija ga potrebuje, uporabnik pa je to zelel,
 * ko jo je odprl.
 */
class LinkSorodnikStoritev : Service() {

    private val odzivnik = Messenger(Handler(Looper.getMainLooper()) { sporocilo ->
        if (sporocilo.what == ZAHTEVA) {
            val komu = sporocilo.replyTo
            val podatki = sporocilo.data ?: Bundle()
            val odgovor = Message.obtain(null, ODGOVOR)
            odgovor.data = pripraviOdgovor(podatki.getString("device_name") ?: "Safeer OS",
                podatki.getString("app") ?: "", podatki.getBoolean("ne_zaganjaj", false),
                podatki.getString("device_id").orEmpty())
            try { komu?.send(odgovor) } catch (e: Throwable) { Log.w(TAG, "Odgovora ni bilo mogoce poslati: ${e.message}") }
        } else if (sporocilo.what == PRIJAVE) {
            // Safeer OS na tem televizorju pokaze kodo za seznanitev nove naprave (glej KodaSeznanitve).
            val seznam = try { HubKrmilnik.usmerjevalnik?.cakajocePrijave().orEmpty() } catch (_: Throwable) { emptyList() }
            val odgovor = Message.obtain(null, PRIJAVE_ODGOVOR)
            odgovor.data = Bundle().apply {
                putStringArray("pair_id", seznam.map { it.pairId }.toTypedArray())
                putStringArray("ime", seznam.map { it.ime }.toTypedArray())
                putStringArray("pin", seznam.map { it.pin }.toTypedArray())
            }
            try { sporocilo.replyTo?.send(odgovor) } catch (e: Throwable) { SafeerLog.napaka("Sorodnik", "odgovor Safeer OS ni poslan", e) }
        } else if (sporocilo.what == ZAVRNI) {
            val id = sporocilo.data?.getString("pair_id").orEmpty()
            if (id.isNotBlank()) try { HubKrmilnik.usmerjevalnik?.zavrniPrijavo(id) } catch (e: Throwable) { SafeerLog.napaka("Sorodnik", "zavrnitev prijave", e) }
            try { sporocilo.replyTo?.send(Message.obtain(null, ZAVRNI_ODGOVOR)) } catch (e: Throwable) { SafeerLog.napaka("Sorodnik", "odgovor na zavrnitev", e) }
        } else if (sporocilo.what == PRIDRUZITEV) {
            // Prijavno okno Safeer OS: QR koda, s katero se telefon pridruzi (PridruzitevSredisca).
            val odgovor = Message.obtain(null, PRIDRUZITEV_ODGOVOR)
            odgovor.data = PridruzitevSredisca.nova(applicationContext, sporocilo.data?.getString("preklici").orEmpty())
            try { sporocilo.replyTo?.send(odgovor) } catch (e: Throwable) { SafeerLog.napaka("Sorodnik", "odgovor Safeer OS ni poslan", e) }
        } else if (sporocilo.what == PRIDRUZITEV_STANJE) {
            val odgovor = Message.obtain(null, PRIDRUZITEV_STANJE_ODGOVOR)
            odgovor.data = Bundle().apply {
                PridruzitevSredisca.zadnja?.let { putLong("cas", it.first); putString("ime", it.second) }
            }
            try { sporocilo.replyTo?.send(odgovor) } catch (e: Throwable) { SafeerLog.napaka("Sorodnik", "odgovor Safeer OS ni poslan", e) }
        } else if (sporocilo.what == PRIDRUZITEV_KONEC) {
            // Okno se zapira: koda ne sme veljati naprej; ob »brez povezave« ugasnemo sredisce, ce smo ga
            // prizgali samo zanjo.
            val d = sporocilo.data ?: Bundle()
            PridruzitevSredisca.preklici(d.getString("qr_id").orEmpty())
            if (d.getBoolean("brez_povezave", false)) PridruzitevSredisca.izklopiCeSamoZaKodo(applicationContext)
            try { sporocilo.replyTo?.send(Message.obtain(null, PRIDRUZITEV_KONEC_ODGOVOR)) } catch (e: Throwable) { SafeerLog.napaka("Sorodnik", "konec pridruzitve", e) }
        }
        true
    })

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == DEJANJE) odzivnik.binder else null

    /**
     * [neZaganjaj] = sorodnik samo pogleda, ali sredisce ze tece (uporabnik pri njem se ni izbral
     * Safeer Linka). Takrat ga ne prizigamo: nicesar ne vklopimo namesto uporabnika.
     */
    private fun pripraviOdgovor(imeNaprave: String, paket: String, neZaganjaj: Boolean, zeleniId: String = ""): Bundle {
        val app = applicationContext
        val b = Bundle()
        try {
            if (!HubKrmilnik.tece()) {
                // Umaknili smo se izvoljenemu hubu (drug clan kroga): sorodnik gre tja, s podpisom svojega kljuca.
                HubKrmilnik.izvoljeniHub(app)?.let { izvoljeni ->
                    val pripona = if (paket.endsWith(".os")) "os" else paket.substringAfterLast('.').ifBlank { "app" }
                    b.putString("hub_url", izvoljeni.naslov)
                    b.putString("token", "")
                    b.putString("fp", izvoljeni.odtis)
                    b.putString("hub_id", izvoljeni.id)
                    b.putString("device_id", zeleniId.ifBlank { HubKrmilnik.lastniId() + "-" + pripona })
                    Log.i(TAG, "Sorodna aplikacija $paket gre na izvoljeni hub ${izvoljeni.id}.")
                    return b
                }
                if (neZaganjaj) {
                    Log.i(TAG, "Sredisce ne tece, sorodnik ($paket) ga ni zahteval - ne zaganjam.")
                    return b
                }
                HubKrmilnik.zazeni(app, zapomni = true)
                HubStoritev.zagotovi(app)
            }
            val u = HubKrmilnik.usmerjevalnik
            val vrata = HubKrmilnik.vrata()
            if (u == null || vrata == 0) {
                Log.w(TAG, "Sredisce ne tece; sorodnik ($paket) ostane brez zetona.")
                return b
            }
            val pripona = if (paket.endsWith(".os")) "os" else paket.substringAfterLast('.').ifBlank { "app" }
            // Sorodnik v svojem procesu ima svoj kljuc in zato svoj id iz kljuca (n-...-os): zeton izdamo temu
            // id-ju, ki ga pove sam. Starejsi sorodnik brez id-ja dobi id po starem (id sredisca + pripona).
            val id = zeleniId.take(HubUsmerjevalnik.NAJVEC_IMENA).ifBlank { HubKrmilnik.lastniId() + "-" + pripona }
            val ime = "$imeNaprave (" + android.os.Build.MODEL + ")"
            val zeton = u.zagotoviLastniZeton(id, ime)
            b.putString("hub_url", "wss://127.0.0.1:$vrata/cast/ws")
            b.putString("token", zeton)
            b.putString("fp", HubTls.lastniOdtis())
            b.putString("hub_id", HubUsmerjevalnik.IDENTITETA_HUBA)
            b.putString("device_id", id)
            Log.i(TAG, "Sorodna aplikacija $paket je dobila zeton ($id).")
        } catch (e: Throwable) {
            Log.w(TAG, "Zetona za sorodnika ni bilo mogoce pripraviti: ${e.message}")
        }
        return b
    }

    companion object {
        const val DEJANJE = "si.safeer.tv.LINK_SORODNIK"
        const val ZAHTEVA = 1
        const val ODGOVOR = 2
        const val PRIJAVE = 3
        const val PRIJAVE_ODGOVOR = 4
        const val ZAVRNI = 5
        const val ZAVRNI_ODGOVOR = 6
        const val PRIDRUZITEV = 7
        const val PRIDRUZITEV_ODGOVOR = 8
        const val PRIDRUZITEV_STANJE = 9
        const val PRIDRUZITEV_STANJE_ODGOVOR = 10
        const val PRIDRUZITEV_KONEC = 11
        const val PRIDRUZITEV_KONEC_ODGOVOR = 12
        private const val TAG = "SafeerLinkSorodnik"
    }
}
