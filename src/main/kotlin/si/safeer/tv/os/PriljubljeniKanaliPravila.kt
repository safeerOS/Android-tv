package si.safeer.tv.os

/**
 * Priljubljeni kanali v TV v zivo (lastnik, 25. 10. 2026): izbira »Priljubljeni« pokaze samo njih, v polni mrezi so na
 * zacetku, uporabnik jih z daljincem premakne levo, desno ali na zacetek; vrstni red ostane shranjen. Priljubljene so
 * en seznam za vso vsebino (skladbe, filmi, kanali) - kanali se premikajo samo med kanali, ostale vrstice ostanejo na
 * svojih mestih; nov priljubljeni kanal gre za zadnjega ([dodaj]). Ciste odlocitve, brez Androida (preizkus: tests/PriljubljeniKanaliPravilaTest.kt).
 */
object PriljubljeniKanaliPravila {
    /** Id kanala v zivo: vgrajeni (»tv:«), s seznama IPTV (»iptv:«); kanal dodatka pove klicatelj ([dodatekVZivo]). */
    fun jeKanal(id: String, dodatekVZivo: Boolean = false): Boolean = id.startsWith("tv:") || id.startsWith("iptv:") || dodatekVZivo

    /**
     * Mreza: najprej priljubljeni ([priljubljeni], po uporabnikovem vrstnem redu - samo tisti, ki so v mrezi), nato ostali
     * v danem redu. [samoPriljubljeni]: samo priljubljeni.
     */
    fun prikaz(priljubljeni: List<String>, vsi: List<String>, samoPriljubljeni: Boolean): List<String> {
        val vMrezi = vsi.toHashSet()
        val spredaj = priljubljeni.filter { it in vMrezi }.distinct()
        if (samoPriljubljeni) return spredaj
        val p = spredaj.toHashSet()
        return spredaj + vsi.filter { it !in p }
    }

    /** Mesto kanala [id] med kanali seznama in stevilo kanalov; mesto -1 = ni med priljubljenimi kanali. */
    fun mesto(ids: List<String>, jeKanal: (String) -> Boolean, id: String): Pair<Int, Int> {
        val kanali = ids.filter(jeKanal)
        return kanali.indexOf(id) to kanali.size
    }

    /**
     * Seznam priljubljenih po premiku kanala [id] za [zamik] mest med kanali (levo -1, desno +1); [naZacetek] ga postavi
     * pred vse kanale. Ne-kanali ostanejo na svojih mestih. null = premik ni mogoc (ni kanal, ze na robu).
     */
    fun premakni(ids: List<String>, jeKanal: (String) -> Boolean, id: String, zamik: Int, naZacetek: Boolean = false): List<String>? {
        val mesta = ids.indices.filter { jeKanal(ids[it]) }
        val kanali = mesta.map { ids[it] }.toMutableList()
        val i = kanali.indexOf(id)
        if (i < 0) return null
        val j = if (naZacetek) 0 else i + zamik
        if (j !in kanali.indices || j == i) return null
        kanali.add(j, kanali.removeAt(i))
        val izhod = ids.toMutableList()
        mesta.forEachIndexed { k, m -> izhod[m] = kanali[k] }
        return izhod
    }

    /**
     * Nov priljubljeni kanal [nov] gre za zadnji priljubljeni kanal - vrstni red ze izbranih ostane, kot ga je uporabnik
     * uredil (»na zacetek« ga premakne meni). Brez kanalov gre na zacetek, kot ostale priljubljene. Ze priljubljen: brez spremembe.
     */
    fun dodaj(ids: List<String>, jeKanal: (String) -> Boolean, nov: String): List<String> {
        if (nov in ids) return ids
        val zadnji = ids.indexOfLast(jeKanal)
        return ids.toMutableList().apply { add(zadnji + 1, nov) }
    }

    /**
     * Pregled 25. 10. (N9): najvec [najvec] priljubljenih - najprej odpadejo ne-kanali s konca (najstarejse skladbe), sele
     * nato kanali s konca. Nov kanal tako ne izgine zaradi starih skladb.
     */
    fun omeji(ids: List<String>, jeKanal: (String) -> Boolean, najvec: Int): List<String> {
        if (ids.size <= najvec) return ids
        var odvec = ids.size - najvec
        val odstrani = HashSet<Int>()
        for (i in ids.indices.reversed()) { if (odvec == 0) break; if (!jeKanal(ids[i])) { odstrani += i; odvec-- } }
        for (i in ids.indices.reversed()) { if (odvec == 0) break; if (i !in odstrani) { odstrani += i; odvec-- } }
        return ids.filterIndexed { i, _ -> i !in odstrani }
    }

    /** Katera premika ponudi meni kartice: (levo, desno, na zacetek). */
    fun moznostiPremika(mesto: Int, kanalov: Int): Triple<Boolean, Boolean, Boolean> =
        if (mesto < 0 || kanalov < 2) Triple(false, false, false) else Triple(mesto > 0, mesto < kanalov - 1, mesto > 1)
}
