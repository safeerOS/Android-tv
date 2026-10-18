package si.safeer.tv.cast

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Izhodna vrsta ODHODNE sosednje povezave (HubMesh, OkHttp) z nadomescanjem po kljucu - isto kot
 * HubStreznik.Povezava.poslji(nadomesti=) na srediscu in link_mesh.OdhodnaSosednja na racunalniku.
 *
 * OkHttp ima svojo vrsto (do 16 MiB), iz katere cakajocega sporocila ni mogoce zamenjati. Do 0.5.91 je zato starejsi
 * sosed (brez devdelta1) ob valu prijav dobil vsak cel seznam naprav posebej, enega za drugim. Zdaj OkHttp dobi samo
 * toliko, da pise brez cakanja ([V_OKHTTP] bajtov v njegovi vrsti); vse drugo caka tu, kjer novejsi cel seznam
 * ([dodaj] s kljucem) zamenja cakajocega na istem mestu. Sporocila brez kljuca gredo vedno v vrstnem redu, nobeno se ne
 * izgubi. Ko se OkHttp-jeva vrsta izprazni (preverjamo vsakih [PREMOR_MS], samo dokler kaj caka), gre naprej.
 *
 * Razred ne pozna Androida ne OkHttp-ja (preizkus: tests/VrstaSosedaTest.kt v navadnem JVM).
 */
class VrstaSoseda(
    /** Preda sporocilo OkHttp-ju (WebSocket.send); false = povezava je zaprta. */
    private val poslji: (String) -> Boolean,
    /** Bajtov, ki cakajo v OkHttp-jevi vrsti (WebSocket.queueSize). */
    private val vOkhttp: () -> Long,
    /** Sosed ne bere (vrsta prepolna): povezavo zapremo, sosed se poveze znova. */
    private val naPrepolno: () -> Unit,
    /** Casovnik za praznjenje (zamik ms, opravilo); preizkusi ga zamenjajo. */
    private val nacrtuj: (Long, () -> Unit) -> Unit = { zamik, f ->
        try { casovnik.schedule({ f() }, zamik, TimeUnit.MILLISECONDS) } catch (_: Throwable) { }
    }
) {
    private val vrsta = ArrayDeque<Pair<String, String?>>()
    private var znakov = 0L
    private var nacrtovano = false
    private var zaprta = false

    /** Meritev: koliko cakajocih sporocil je novejse nadomestilo. */
    var nadomestenih = 0L
        private set

    fun cakajocih(): Int = synchronized(this) { vrsta.size }

    /**
     * Sporocilo za soseda. [kljuc]: cakajoce sporocilo istega kljuca (cel seznam starejsemu sosedu) novo zamenja na
     * istem mestu. False: povezava je zaprta ali sosed ne bere (vrsta prepolna - povezava se zapre).
     */
    fun dodaj(besedilo: String, kljuc: String? = null): Boolean {
        var prepolna = false
        synchronized(this) {
            if (zaprta) return false
            if (kljuc != null) {
                val i = vrsta.indexOfFirst { it.second == kljuc }
                if (i >= 0) {
                    znakov += besedilo.length - vrsta[i].first.length
                    vrsta[i] = besedilo to kljuc
                    nadomestenih++
                    return true
                }
            }
            if (vrsta.isEmpty() && vOkhttp() < V_OKHTTP) {
                if (poslji(besedilo)) return true
                zapriVrsto()
                return false
            }
            if (vrsta.size >= NAJVEC_SPOROCIL || znakov + besedilo.length > NAJVEC_ZNAKOV) {
                zapriVrsto()
                prepolna = true
            } else {
                vrsta.addLast(besedilo to kljuc)
                znakov += besedilo.length
                if (!nacrtovano) { nacrtovano = true; nacrtuj(PREMOR_MS, ::izprazni) }
            }
        }
        if (prepolna) {
            try { naPrepolno() } catch (_: Throwable) { }
            return false
        }
        return true
    }

    /** Preda OkHttp-ju, kar caka, dokler je njegova vrsta pod [V_OKHTTP]; ce se kaj caka, se ponovi. */
    fun izprazni() {
        synchronized(this) {
            nacrtovano = false
            while (!zaprta && vrsta.isNotEmpty() && vOkhttp() < V_OKHTTP) {
                val (besedilo, _) = vrsta.removeFirst()
                znakov -= besedilo.length
                if (!poslji(besedilo)) zapriVrsto()
            }
            if (!zaprta && vrsta.isNotEmpty()) { nacrtovano = true; nacrtuj(PREMOR_MS, ::izprazni) }
        }
    }

    fun zapri() = synchronized(this) { zapriVrsto() }

    private fun zapriVrsto() {
        zaprta = true
        vrsta.clear()
        znakov = 0
    }

    companion object {
        /** Toliko bajtov sme cakati v OkHttp-jevi vrsti (zamenjati jih ni mogoce); vec caka tu. */
        const val V_OKHTTP = 256L * 1024
        /** Sosed, ki ne bere: toliko cakajocih sporocil kot na racunalniku (link_mesh.NAJVEC_V_VRSTI) ... */
        const val NAJVEC_SPOROCIL = 512
        /** ... ali toliko znakov - skupaj z OkHttp-jem kot prej njegova meja 16 MiB. */
        const val NAJVEC_ZNAKOV = 16L * 1024 * 1024 - V_OKHTTP
        const val PREMOR_MS = 20L

        private val casovnik: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "safeer-mesh-vrsta").apply { isDaemon = true } }
        }
    }
}
