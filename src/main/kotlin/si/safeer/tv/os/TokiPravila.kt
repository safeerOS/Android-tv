package si.safeer.tv.os

/**
 * Zdruzevanje dvojnikov med tokovi dodatkov (lastnik, 5. 11. 2026: viri tokov). Isti torrent (isti infoHash in ista
 * datoteka) iz Torrentia, MediaFusiona, Comet ... je bil na seznamu veckrat: izbira je isto izdajo poskusila znova,
 * ko prva ni stekla, namesto da bi takoj sla na naslednjo moznost. Zdaj je en vnos z vsemi sledilniki vseh dodatkov
 * in najvecjim stevilom sejalcev. Cista pravila brez Androida (tests/TokiPravilaTest.kt).
 */
object TokiPravila {
    /** Najvec sledilnikov v magnetu po zdruzitvi (dolg magnet ne pomaga, odvec sledilniki le zamujajo zagon). */
    const val NAJVEC_SLEDILNIKOV = 30

    private val SLEDILNIK = Regex("[?&]tr=([^&]*)")
    // 3D (lastnik, 10. 11. 2026: »3D ne sme prikazovati na TV« - na TV je bil film v dveh polovicah slike, SBS). »3d« steje kot
    // oznaka izdaje, razen ko mu sledi letnica: takrat je del naslova (»Film.3D.2010.1080p« je navadna izdaja filma »Film 3D«).
    private val OZNAKA_3D = Regex("(?<![a-z0-9])3d(?![a-z0-9])(?![\\s._-]*\\(?(?:19|20)\\d\\d)")
    private val OZNAKA_SBS = Regex("(?<![a-z0-9])(?:h-?sbs|half[\\s._-]?sbs|sbs|h-ou|half[\\s._-]?ou|h-tab|half[\\s._-]?tab)(?![a-z0-9])")

    /** Izdaja je 3D (dve sliki v eni slicici): na TV je ne ponudimo in ne predvajamo. */
    fun je3d(besedilo: String): Boolean = besedilo.lowercase().let { OZNAKA_3D.containsMatchIn(it) || OZNAKA_SBS.containsMatchIn(it) }

    /** Tokovi brez 3D izdaj; vrstni red ostane. */
    fun <T> brez3d(tokovi: List<T>, besedilo: (T) -> String): List<T> = tokovi.filter { !je3d(besedilo(it)) }

    private val SEJALCI_V_OPISU = Regex("(👤\\s*)(\\d{1,6})|((?i:seed(?:er)?s?)\\s*[:=]?\\s*)(\\d{1,6})")

    /**
     * Kljuc dvojnika: isti kljuc = ista vsebina. null = toka ne zdruzujemo (http/debrid brez infoHash, Usenet, neznana
     * datoteka Webtorja). Zdruzujemo le znotraj iste vrste in vira ([vir]), da Webtor ostane locen od nasih torrentov.
     * [hash]: infoHash iz podatkov toka (Webtor rezerva), sicer iz magneta [url]. [datoteka]: fileIdx (-1 = najvecji video).
     */
    fun kljuc(vrsta: String, url: String, hash: String, vir: String, datoteka: Int): String? {
        val h = when {
            vrsta == "torrent" -> TorBoxPravila.hashIzMagneta(url)
            hash.isNotBlank() -> hash.trim().lowercase()
            else -> null
        } ?: return null
        // Webtor: -2 = indeks ni znan (ni v JWT) - lahko je druga datoteka iste izdaje, zato ne zdruzimo.
        if (datoteka == WebtorPravila.IDX_NEZNAN) return null
        return "$vrsta|$vir|$h|$datoteka"
    }

    /** Sledilniki (kodirani, kot so v magnetu) iz parametrov `tr=`. */
    fun sledilnikiMagneta(magnet: String): List<String> = SLEDILNIK.findAll(magnet).map { it.groupValues[1] }.filter { it.isNotBlank() }.toList()

    /** Magnet z dodanimi sledilniki [dodatni] (kodirani), brez dvojnikov, skupaj najvec [meja]. */
    fun zSledilniki(magnet: String, dodatni: List<String>, meja: Int = NAJVEC_SLEDILNIKOV): String {
        val ima = sledilnikiMagneta(magnet)
        val prostora = meja - ima.size
        if (prostora <= 0) return magnet
        val nova = dodatni.distinct().filter { it.isNotBlank() && it !in ima }.take(prostora)
        return magnet + nova.joinToString("") { "&tr=$it" }
    }

    /** Opis z navedenim stevilom sejalcev [n]: zamenja prvo navedbo (»👤 12«, »Seeders: 12«) ali doda »👤 n«. */
    fun zSejalci(opis: String, n: Int): String {
        if (n < 0) return opis
        val m = SEJALCI_V_OPISU.find(opis) ?: return (if (opis.isBlank()) "" else "$opis ") + "👤 $n"
        val skupina = if (m.groups[2] != null) 2 else 4
        val r = m.groups[skupina]!!.range
        return opis.substring(0, r.first) + n + opis.substring(r.last + 1)
    }

    /**
     * Koliko podatkov o toku pove njegovo besedilo (locljivost, velikost, sejalci, zvok, kodek, jezik): med dvojniki
     * obdrzimo najbolje oznacenega, da ocena v [TokIzbira.ocena] ne izgubi podatka.
     */
    fun podatkov(besedilo: String): Int {
        val o = TokIzbira.opisi(besedilo)
        return listOf(o.visina > 0, o.gb > 0.0, TokIzbira.sejalci(besedilo) >= 0, o.zvok.isNotBlank(),
            o.hevc || o.av1 || o.hdr || o.dv, TokIzbira.jezikZvoka(besedilo).isNotBlank()).count { it }
    }

    /**
     * Dvojniki ([kljuc] enak in ne null) postanejo en vnos na mestu prvega pojava; tok brez kljuca ostane, kot je.
     * Predstavnik je najbolje oznacen ([podatkov]), pri enakem tisti z vec sejalci, nato prvi. [zdruzi] dobi predstavnika
     * in vse dvojnike skupine (vkljucno z njim) in vrne zdruzen tok.
     */
    fun <T> zdruziDvojnike(tokovi: List<T>, kljuc: (T) -> String?, besedilo: (T) -> String,
                           zdruzi: (predstavnik: T, skupina: List<T>) -> T): List<T> {
        val skupine = LinkedHashMap<Any, MutableList<T>>()
        tokovi.forEachIndexed { i, t -> skupine.getOrPut(kljuc(t) ?: i) { mutableListOf() }.add(t) }
        return skupine.values.map { s ->
            if (s.size == 1) s[0] else {
                val izbran = s.withIndex().maxWithOrNull(compareBy<IndexedValue<T>> { podatkov(besedilo(it.value)) }
                    .thenBy { TokIzbira.sejalci(besedilo(it.value)) }.thenByDescending { it.index })!!.value
                zdruzi(izbran, s)
            }
        }
    }

    /** Kar o toku potrebuje zdruzevanje (polja Stremio.Tok), da pravila tecejo brez Androida. */
    data class Vnos(val vrsta: String, val url: String, val ime: String, val opis: String, val dodatek: String,
                    val datoteka: Int = -1, val hash: String = "", val vir: String = "",
                    val sledilniki: List<String> = emptyList(), val izDodatkov: List<String> = emptyList())

    /**
     * Dvojniki ([kljuc]) postanejo en vnos: predstavnik ([zdruziDvojnike]) z najvecjim stevilom sejalcev skupine (popravljena
     * prva navedba v »ime opis«, kot jo bere [TokIzbira.sejalci]), sledilniki vseh (magnet in Webtor `sources`, brez
     * dvojnikov, najvec [NAJVEC_SLEDILNIKOV]) in imeni vseh dodatkov. Vrne indeks predstavnika v [vnosi] in zdruzen vnos.
     * 3D izdaj ([je3d]) ne vrne.
     */
    fun zdruziVnose(vnosi: List<Vnos>): List<Pair<Int, Vnos>> {
        fun besedilo(v: Vnos) = v.ime + " " + v.opis
        val zdruzeni = HashMap<Int, Vnos>()
        // 3D izdaje izpadejo ze tu, da jih ne vidi ne seznam virov ne samodejna izbira.
        val izbrani = zdruziDvojnike(vnosi.indices.filter { !je3d(besedilo(vnosi[it])) }, { kljuc(vnosi[it].vrsta, vnosi[it].url, vnosi[it].hash, vnosi[it].vir, vnosi[it].datoteka) },
            { besedilo(vnosi[it]) }) { p, skupinaI ->
            val skupina = skupinaI.map { vnosi[it] }
            val izbran = vnosi[p]
            val sejalcev = skupina.maxOf { TokIzbira.sejalci(besedilo(it)) }
            var t = izbran
            if (sejalcev > TokIzbira.sejalci(besedilo(izbran)))
                t = if (TokIzbira.sejalci(izbran.ime) >= 0) t.copy(ime = zSejalci(izbran.ime, sejalcev)) else t.copy(opis = zSejalci(izbran.opis, sejalcev))
            if (t.vrsta == "torrent") t = t.copy(url = zSledilniki(izbran.url, skupina.flatMap { sledilnikiMagneta(it.url) }))
            if (skupina.any { it.sledilniki.isNotEmpty() })
                t = t.copy(sledilniki = (izbran.sledilniki + skupina.flatMap { it.sledilniki }).distinct().take(NAJVEC_SLEDILNIKOV))
            zdruzeni[p] = t.copy(izDodatkov = skupina.flatMap { it.izDodatkov.ifEmpty { listOf(it.dodatek) } }.distinct())
            p
        }
        return izbrani.map { it to (zdruzeni[it] ?: vnosi[it]) }
    }
}
