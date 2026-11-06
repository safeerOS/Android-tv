package si.safeer.tv.os

/**
 * Vrstice domace strani Medijskega centra (lastnik 6. 11. 2026 04:48-04:56): na Domov ni vrstic, ki podvajajo oddelke
 * zgornje vrstice (Radio, TV v zivo, Moji viri, katalogi Glasbe in Videa); uporabnik v Nastavitvah izbere, katere vrstice
 * so na Domov in v kaksnem redu. Ciste odlocitve, brez Androida (preizkus: tests/MeniVsebinePravilaTest.kt); shramba
 * izbire je [DomovVrstice] - isti modul bodo uporabile Nastavitve Medijskega centra.
 */
object DomovVrsticePravila {
    /** Vrstice na izbiro; [kljuc] je zapis v shrambi (ne spreminjaj - shranjene izbire). */
    enum class Vrstica(val kljuc: String) {
        NEDAVNO("nedavno"),          // Nadaljuj z druge naprave in Nedavno
        SEZNAMI("seznami"),          // seznami predvajanja
        PRILJ_GLASBA("prilj_glasba"), // priljubljena glasba (brez radia in kanalov)
        PRILJ_VIDEO("prilj_video"),  // priljubljeni videi
        RADIO("radio"),              // priljubljene postaje
        TV("tv"),                    // priljubljeni kanali v zivo
        VIRI("viri"),                // pripeti Moji viri
    }

    /** Privzeto: vrstice, ki jih oddelki nimajo; Radio, TV v zivo in Moji viri imajo svoj oddelek. */
    val PRIVZETO = listOf(Vrstica.NEDAVNO, Vrstica.SEZNAMI, Vrstica.PRILJ_GLASBA, Vrstica.PRILJ_VIDEO)

    /** Izbira iz zapisa (kljuci, loceni z vejico); null = uporabnik se ni izbiral (privzeto), "" = vse izklopljeno. */
    fun izbrane(zapis: String?): List<Vrstica> {
        if (zapis == null) return PRIVZETO
        val poKljucu = Vrstica.values().associateBy { it.kljuc }
        return zapis.split(',').mapNotNull { poKljucu[it.trim()] }.distinct()
    }

    fun vZapis(izbrane: List<Vrstica>): String = izbrane.distinct().joinToString(",") { it.kljuc }

    /** Vrstice domace strani: izbrane v uporabnikovem redu, brez praznih ([neprazne] = vrstice, ki imajo kaj pokazati). */
    fun vrstice(izbrane: List<Vrstica>, neprazne: Set<Vrstica>): List<Vrstica> = izbrane.filter { it in neprazne }

    /** Vklop (na konec) ali izklop vrstice - za zaslon urejanja v Nastavitvah. */
    fun preklopi(izbrane: List<Vrstica>, v: Vrstica): List<Vrstica> = if (v in izbrane) izbrane - v else izbrane + v

    /** Premik vrstice gor ([smer] -1) ali dol (+1); na robu ostane, kot je. */
    fun premakni(izbrane: List<Vrstica>, v: Vrstica, smer: Int): List<Vrstica> {
        val i = izbrane.indexOf(v)
        val j = i + smer
        if (i < 0 || j !in izbrane.indices) return izbrane
        return izbrane.toMutableList().apply { this[i] = this[j]; this[j] = v }
    }

    /** Priljubljena glasba na Domov: brez radijskih postaj in kanalov v zivo (ti imajo svoje vrstice in oddelke). */
    fun vPriljubljeniGlasbi(video: Boolean, radio: Boolean, kanal: Boolean): Boolean = !video && !radio && !kanal

    /** Priljubljeni videi na Domov: brez kanalov v zivo. */
    fun vPriljubljenihVideih(video: Boolean, kanal: Boolean): Boolean = video && !kanal

    /** Gumbi vrstice oddelkov - isti seznam na Domu in v vseh oddelkih (lastnik 6. 11. 2026 04:54-05:11), Dom prvi. */
    enum class Oddelek { DOM, GLASBA, VIDEO, RADIO, TV_V_ZIVO, VIRI, PREDVAJALNIK }

    fun gumbiOddelkov(): List<Oddelek> = Oddelek.values().toList()

    /** Deli glave nad vrsticami Doma; [ODDELKI] = vrstica oddelkov, [PLOSCA] = velika plosca predvajanja (samo Predvajalnik). */
    enum class Glava { ODDELKI, PLOSCA }

    /**
     * Glava Doma: na TV samo vrstica oddelkov (kot v oddelkih) - brez velikih ploscic s podnapisi, »Razvrsti in filtriraj«
     * in velike plosce »Nazadnje predvajano«. Predvajalnik na telefonu ([vlc]) ima oddelke v zavihkih spodaj in plosco.
     */
    fun glavaDoma(vlc: Boolean): List<Glava> = if (vlc) listOf(Glava.PLOSCA) else listOf(Glava.ODDELKI)

    enum class Nazaj { NA_DOMOV, ZAPRI }

    /**
     * Nazaj na osnovni strani oddelka (brez odprte podstrani): iz oddelka na Domov Medijskega centra, z Domov ven.
     * [jeDomov] = Medijski center ima Domov (samostojni Predvajalnik ga nima - tam kot doslej).
     */
    fun nazaj(naDomovu: Boolean, jeDomov: Boolean): Nazaj = if (jeDomov && !naDomovu) Nazaj.NA_DOMOV else Nazaj.ZAPRI

    /** Vrstica se pokaze le, ce ima elemente (vrstice brez elementov skrijemo). */
    fun prikaziVrstico(prazna: Boolean, steviloElementov: Int): Boolean = !prazna && steviloElementov > 0

    /** Levi odmik vrstic na Domu je enak kot mreze v oddelkih Video/Glasba (kjer zacnejo pri 0). */
    fun leviOdmik(): Int = 0

    /** Naslovi vrstic na Domu so poravnani z levim robom kartic (ki so na 0). */
    fun odmikNaslova(): Int = 0

    /** Razmik med glavo (gumbi) in prvo vrstico na Domu mora biti enak kot v drugih oddelkih (ne tesno). */
    fun razmikGlaveZgoraj(): Int = 14
}
