package si.safeer.tv.link

/**
 * Kaj naredi naprava, ko se okno z njenim zaslonom na drugi napravi zapre (ukaz `apps.close` s `stream: true`).
 *
 *  - Zaslon nehamo deliti samo, ce ga delimo NAPRAVI, KI JE UKAZ POSLALA: nihce ga vec ne gleda. Deljenja, ki gre drugi
 *    napravi, ukaz tretje naprave ne ustavi.
 *  - Domov gremo samo, ce je to deljenje zacel »Odpri tukaj« te iste naprave (to ve ta naprava sama - [Zahteve], ne
 *    parametri ukaza) in je Safeer Vnos vklopljen. Zaslona, ki ga je uporabnik delil sam, se ne dotikamo - naprave mu
 *    ne premaknemo izpod prstov.
 *  - Zahteva »Odpri tukaj« iste naprave, ki se caka na soglasje, je s tem umaknjena: poznejsa potrditev ne zacne deljenja.
 *  - Sicer nic: zapoznel ali ponovljen ukaz naprave ne sme vreci na domaci zaslon.
 *
 * Android ne dovoli, da bi ena aplikacija drugo ugasnila; umik v ozadje je najvec, kar gre brez korenskega dostopa.
 */
object PretokKonec {
    data class Odlocitev(val ustaviDeljenje: Boolean, val domov: Boolean, val preklici: Boolean)

    /** Toliko casa zahteva »Odpri tukaj« caka na soglasje na napravi; potrditev po tem ne zacne deljenja. */
    const val CAKA_MS = 120_000L
    /** Toliko po potrjenem soglasju najdlje velja oznaka »deljenje se zaganja« ([Zahteve.zacenjaZa]). */
    const val ZAGON_MS = 10_000L

    /**
     * [stream]: ukaz zapira pretok (drugacnega ne poznamo - tuje aplikacije Android ne da zapreti); [posiljatelj]: naprava,
     * ki je ukaz poslala (doda ga sprejemnik iz sporocila huba, ne posiljatelj sam); [ciljDeljenja]: komu ta naprava zdaj
     * deli zaslon; [odprtoTukajZa]: za katero napravo je tekoce deljenje zacel »Odpri tukaj« ("" = uporabnik je delil
     * sam); [cakaOd]: cigava zahteva caka na soglasje ("" = nobena); [zacenjaZa]: za katero napravo se deljenje pravkar
     * zaganja (soglasje je dano, storitev se ne tece; "" = za nobeno) - velja kot tekoce deljenje tej napravi.
     */
    fun odloci(stream: Boolean, posiljatelj: String, deliZaslon: Boolean, ciljDeljenja: String, odprtoTukajZa: String,
               vnosAktiven: Boolean, cakaOd: String, zacenjaZa: String = ""): Odlocitev {
        if (!stream || posiljatelj.isBlank()) return Odlocitev(false, false, false)
        // Med zagonom »Odpri tukaj« velja oznaka zagona: tekoce deljenje drugi napravi bo novo deljenje zamenjalo, ukaz te
        // druge naprave pa novega ne sme ustaviti (cetrti pregled, R6-K3).
        val njegovo = if (zacenjaZa.isNotEmpty()) zacenjaZa == posiljatelj else deliZaslon && ciljDeljenja == posiljatelj
        return Odlocitev(njegovo, njegovo && odprtoTukajZa == posiljatelj && vnosAktiven, cakaOd == posiljatelj)
    }

    /**
     * Zahteve »Odpri tukaj« na tej napravi: katera caka na soglasje in za koga je bilo zaceto tekoce deljenje. Ena zahteva
     * naenkrat - nova zamenja staro (potrditev stare ne zacne nicesar). [ura]: cas v ms (preizkus ga poda sam).
     */
    class Zahteve(private val ura: () -> Long = { System.currentTimeMillis() }) {
        private var stevec = 0
        private var cakaZeton = 0
        private var cakaOd = ""
        private var cakaDo = 0L
        private var odprtoZa = ""
        private var zacenjaZa = ""
        private var zacenjaDo = 0L

        /** Nova zahteva naprave [posiljatelj]; vrne njen zeton (gre v okno za soglasje). */
        @Synchronized fun nova(posiljatelj: String): Int {
            cakaZeton = ++stevec
            cakaOd = posiljatelj
            cakaDo = ura() + CAKA_MS
            return cakaZeton
        }

        /** Ali zahteva z zetonom se caka (ni umaknjena, zamenjana ali pretecena). */
        @Synchronized fun caka(zeton: Int): Boolean = zeton != 0 && zeton == cakaZeton && ura() < cakaDo

        /** Cigava zahteva caka na soglasje ("" = nobena). */
        @Synchronized fun cakaOd(): String = if (cakaZeton != 0 && ura() < cakaDo) cakaOd else ""

        /** Naprava je zahtevo umaknila (`apps.close`) ali jo je uporabnik zavrnil. */
        @Synchronized fun umakni() {
            cakaZeton = 0
            cakaOd = ""
            cakaDo = 0L
        }

        /** Uporabnik je potrdil zajem zaslona: true = zahteva se velja in deljenje naj se zacne. Zahteva je s tem porabljena. */
        @Synchronized fun potrdi(zeton: Int): Boolean {
            val velja = caka(zeton)
            val od = cakaOd
            if (zeton == cakaZeton) umakni()
            // Od potrditve do zacetka deljenja mine hip (storitev se zaganja): ukaz za konec, ki pride vmes, ne sme izginiti.
            if (velja) { zacenjaZa = od; zacenjaDo = ura() + ZAGON_MS }
            return velja
        }

        /** Za katero napravo se deljenje pravkar zaganja (soglasje je dano, storitev se ne tece); "" = za nobeno. */
        @Synchronized fun zacenjaZa(): String = if (ura() < zacenjaDo) zacenjaZa else ""

        /** Deljenje je steklo ali pa je bilo ustavljeno, preden je steklo: oznaka zagona ne velja vec. */
        @Synchronized fun zagonKoncan() { zacenjaZa = ""; zacenjaDo = 0L }

        /**
         * Zacelo se je deljenje zaslona. [odprtoTukajZa]: naprava, katere »Odpri tukaj« ga je zacel ("" = uporabnik je delil
         * sam) - zapis prejsnjega deljenja s tem ne velja vec. En klic: prej sta zapis delala dva (pobrisi, nastavi), pravilna
         * samo v tem vrstnem redu.
         */
        @Synchronized fun novoDeljenje(odprtoTukajZa: String = "") { odprtoZa = odprtoTukajZa }

        @Synchronized fun odprtoTukajZa(): String = odprtoZa
    }

    val zahteve = Zahteve()
}
