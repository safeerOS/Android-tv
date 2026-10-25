package si.safeer.tv.os

import java.net.URL

/**
 * Sonda toka kanala ali postaje v zivo: ali streznik toka s te naprave res odgovori. Dodatek kanalov nasteva tudi
 * kanale, katerih streznika ni vec ali s te mreze ni dosegljiv (izmerjeno 5. 10. 2026: trije od treh izbranih) - takega
 * kanala ne kazemo (lastnik: »samo tok, ki ga lahko predvajamo«). Ena kratka zahteva, prebere samo glavo odgovora.
 * Klice se iz niti v ozadju (caka na omrezje in na [Imenik]).
 */
object SondaToka {
    /** true = streznik je odgovoril z vsebino, false = toka pri viru ni (dokazano), null = ne vemo (kanal ostane). */
    fun dosegljiv(naslov: String, glave: Map<String, String>, agent: String): Boolean? = when (preveri(naslov, glave, agent)) {
        OsPravila.IzidSonde.ZIV -> true
        OsPravila.IzidSonde.MRTEV -> false
        // Naloga 75: negotovo (casovna omejitev, 5xx) samo po sebi ni dokaz - steje ga [MrtviKanali.zapomniNegotovega].
        else -> null
    }

    /** Naloga 75: izid sonde z zanesljivostjo ([OsPravila.IzidSonde]). */
    fun preveri(naslov: String, glave: Map<String, String>, agent: String): OsPravila.IzidSonde = preveriZRazlogom(naslov, glave, agent).first

    /**
     * Naloga 96: izid sonde in razlog za dnevnik (koda HTTP, imenik, zavrnjena povezava, rok ...) - brez naslova toka (ima lahko
     * geslo); gostitelj samo, ce ga imenik ne pozna. Sonda sprejme vsak odgovor 2xx ne glede na vrsto vsebine (tudi surov
     * MPEG-TS, video/mp2t, application/octet-stream) - [OsPravila.izidPoOdgovoru].
     *
     * 25. 10. 2026: seznam HLS preverimo do konca verige ([SondaHlsPravila]) - razlicica, kljuc in zadnji kos. Prej je glavni
     * seznam z odgovorom 200 pomenil »ziv«, ceprav so bile vse razlicice 404 (ABC News Live) ali kosi geografsko zaprti.
     */
    fun preveriZRazlogom(naslov: String, glave: Map<String, String>, agent: String): Pair<OsPravila.IzidSonde, String> =
        // Pregled 25. 10. 2026: veriga z trdim rokom, surovi tokovi, Range, razlicice od prve - [SondaVeriga] (preizkus s
        // krajevnim streznikom HTTP). Tu ostane, kar je Androidovo: imenik ob napaki povezave in glave zahteve.
        SondaVeriga.preveri(naslov, { url -> glaveZahteve(url, glave, agent) }, ::izIzjeme)

    /**
     * Glave vsake zahteve sonde (pregled N4): enake kot pri predvajanju za ta naslov ([SpletniVir.glaveZa] - UA, Referer
     * domene, piskotki, glave gostitelja in toka); UA klicatelja, ce ga predvajalnik se ne pozna.
     */
    private fun glaveZahteve(url: String, glave: Map<String, String>, agent: String): Map<String, String> {
        val g = SpletniVir.glaveZa(url, glave)
        return if (agent.isNotBlank() && g.keys.none { it.equals("User-Agent", ignoreCase = true) }) g + ("User-Agent" to agent) else g
    }

    /** Izid ob napaki povezave: neznan gostitelj in zavrnjena povezava ob delujocem omrezju sta dokaz, rok je negotov. */
    private fun izIzjeme(e: java.io.IOException, url: String): Pair<OsPravila.IzidSonde, String> {
        if (e is java.net.UnknownHostException) {
            val gostitelj = OsPravila.gostiteljIzNapake(e.message).ifEmpty { try { URL(url).host.orEmpty().lowercase() } catch (_: Exception) { "" } }
            if (gostitelj.isEmpty()) return OsPravila.IzidSonde.NEZNANO to "neznan gostitelj"
            val o = Imenik.obstaja(listOf(gostitelj, Imenik.KONTROLA), OsPravila.IMENIK_ROK_MS)
            return (if (OsPravila.mrtevGostitelj(o[0], o[1])) OsPravila.IzidSonde.MRTEV else OsPravila.IzidSonde.NEZNANO) to
                "imenik ne pozna $gostitelj (ime=${o[0]}, kontrola=${o[1]})"
        }
        if (e !is java.net.SocketTimeoutException && e !is java.net.ConnectException && e !is java.net.NoRouteToHostException)
            return OsPravila.IzidSonde.NEZNANO to "napaka ${e.javaClass.simpleName}"
        val o = Imenik.obstaja(listOf(Imenik.KONTROLA), OsPravila.IMENIK_ROK_MS)
        // Naloga 75: zavrnjena povezava ali ni poti ob delujocem omrezju je dokaz (kot doslej); casovna omejitev ni -
        // tok je lahko zacasno nedosegljiv ali geografsko omejen.
        if (e is java.net.SocketTimeoutException) return OsPravila.izidCasovneOmejitve(o[0]) to "rok povezave (kontrola=${o[0]})"
        return (if (OsPravila.mrtevNeodziven(false, o[0])) OsPravila.IzidSonde.MRTEV else OsPravila.IzidSonde.NEZNANO) to
            "${e.javaClass.simpleName} (kontrola=${o[0]})"
    }
}
