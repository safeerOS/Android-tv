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
        NADALJUJ_Z_OGLEDOM("nadaljuj_z_ogledom"),
        NADALJUJ_S_POSLUSANJEM("nadaljuj_s_poslusanjem"),
        NEDAVNO_DODANO("nedavno_dodano"),
        PRIPOROCENO("priporoceno"),
        NEDAVNO("nedavno"),          // Nadaljuj z druge naprave in Nedavno
        SEZNAMI("seznami"),          // seznami predvajanja
        PRILJ_GLASBA("prilj_glasba"), // priljubljena glasba (brez radia in kanalov)
        PRILJ_VIDEO("prilj_video"),  // priljubljeni videi
    }

    /** Privzeto: vrstice, ki jih oddelki nimajo; Radio, TV v zivo in Moji viri imajo svoj oddelek. */
    val PRIVZETO = listOf(Vrstica.NADALJUJ_Z_OGLEDOM, Vrstica.NADALJUJ_S_POSLUSANJEM, Vrstica.NEDAVNO_DODANO, Vrstica.PRIPOROCENO)

    /** Izbira iz zapisa (kljuci, loceni z vejico); null = uporabnik se ni izbiral (privzeto), "" = vse izklopljeno. */
    fun izbrane(zapis: String?): List<Vrstica> {
        if (zapis == null) return PRIVZETO
        val poKljucu = Vrstica.values().associateBy { it.kljuc }
        return zapis.split(',').mapNotNull { poKljucu[it.trim()] }.distinct()
    }

    fun vZapis(izbrane: List<Vrstica>): String = izbrane.distinct().joinToString(",") { it.kljuc }

    /** Vrstice domace strani: izbrane v uporabnikovem redu, brez praznih ([neprazne] = vrstice, ki imajo kaj pokazati). */
    fun vrstice(izbrane: List<Vrstica>, neprazne: Set<Vrstica>): List<Vrstica> = izbrane.filter { it in neprazne }

    /** 
     * Vrstni red vseh vrstic v oknu urejanja.
     * Ob odprtju okna ([prejsnje] = null) so najprej izbrane (vidne), nato ostale.
     * Kasneje se vrstni red ohranja ob preklopih (skrita vrstica ostane na svojem mestu).
     */
    fun vrstniRedVOkna(izbrane: List<Vrstica>, prejsnje: List<Vrstica>?): List<Vrstica> {
        if (prejsnje != null) return prejsnje
        return izbrane + (Vrstica.values().toList() - izbrane)
    }

    /** Vklop ali izklop vrstice - za zaslon urejanja v Nastavitvah. Skrita vrstica ostane na mestu v oknu. */
    fun preklopiVOkna(izbrane: List<Vrstica>, v: Vrstica, vseVOkna: List<Vrstica>): List<Vrstica> {
        val novoStanje = if (v in izbrane) izbrane - v else izbrane + v
        return vseVOkna.filter { it in novoStanje }
    }

    /** Premik vrstice gor ([smer] -1) ali dol (+1) v oknu urejanja. */
    fun premakniVOkna(vseVOkna: List<Vrstica>, v: Vrstica, smer: Int): List<Vrstica> {
        val i = vseVOkna.indexOf(v)
        val j = i + smer
        if (i < 0 || j !in vseVOkna.indices) return vseVOkna
        return vseVOkna.toMutableList().apply { this[i] = this[j]; this[j] = v }
    }

    /** Priljubljena glasba na Domov: brez radijskih postaj in kanalov v zivo (ti imajo svoje vrstice in oddelke). */
    fun vPriljubljeniGlasbi(video: Boolean, radio: Boolean, kanal: Boolean): Boolean = !video && !radio && !kanal

    /** Priljubljeni videi na Domov: brez kanalov v zivo. */
    fun vPriljubljenihVideih(video: Boolean, kanal: Boolean): Boolean = video && !kanal

    /** Gumbi vrstice oddelkov - isti seznam na Domu in v vseh oddelkih (lastnik 6. 11. 2026 04:54-05:11), Dom prvi. */
    enum class Oddelek { DOM, GLASBA, VIDEO, RADIO, TV_V_ZIVO, VIRI, PREDVAJALNIK }

    fun gumbiOddelkov(): List<Oddelek> = Oddelek.values().toList() - Oddelek.VIRI

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

    enum class DelVrstice { STIKALO, PREMAKNI }

    interface Naslov {
        val id: String
        val naslov: String
        val izvajalec: String
        val zvrsti: List<String>
    }

    /**
     * Priporoceno: iz katalogov izbere naslove istih zvrsti ali ustvarjalcev (izvajalcev) kot v zgodovini,
     * brez ze gledanih (po id ali naslovu).
     */
    fun <T : Naslov> priporoceno(katalogi: List<T>, zgodovina: List<T>): List<T> {
        if (zgodovina.isEmpty()) return emptyList()
        val gledanoId = zgodovina.map { it.id }.toSet()
        val gledanoNaslovi = zgodovina.map { it.naslov.lowercase().trim() }.toSet()
        val zvrsti = zgodovina.flatMap { it.zvrsti }.map { it.lowercase().trim() }.filter { it.isNotBlank() }.toSet()
        val izvajalci = zgodovina.map { it.izvajalec.lowercase().trim() }.filter { it.isNotBlank() }.toSet()
        
        return katalogi.filter { k ->
            k.id !in gledanoId && k.naslov.lowercase().trim() !in gledanoNaslovi &&
            (k.zvrsti.any { z -> z.lowercase().trim() in zvrsti } ||
             (k.izvajalec.isNotBlank() && k.izvajalec.lowercase().trim() in izvajalci))
        }.distinctBy { it.id }
    }

    /** Samo na sirokem zaslonu (TV, tablica lezece). */
    fun imaPlosco(jeTv: Boolean, jeLezece: Boolean, ozekZaslon: Boolean): Boolean =
        if (jeTv) true else jeLezece && !ozekZaslon

    /** Polje v plosci (TV v zivo, Prenosi) se pokaze le, ce ni prazno. */
    fun prikaziPoljePlosce(prazno: Boolean): Boolean = !prazno
}
