package si.safeer.tv.cast

/**
 * Varovalka kode za povezavo: skupna (ne po viru) omejitev ugibanja 6-mestne kode.
 *
 * Sestmestna koda (900.000 moznosti) je edina skrivnost v Safeer Linku, ki jo je mogoce ugibati. Seznanitev tece po
 * SPAKE2: vsak krog (`/cast/pair/spake`) napravi pove, ali je njena koda prava - en krog je en poskus. Prijava dovoli
 * pet krogov, prijav pa je bilo lahko poljubno. Obramba (HubObramba) steje po viru: kdor hiti, je ustavljen, kdor
 * ostane pod pragom ali menja naslov v domacem omrezju, pa ne - ena naprava je lahko poskusila vec sto kod na uro.
 *
 * Varovalka zato steje VSE poskuse kode in VSE zacetke prijave skupaj, ne glede na vir. Ista pravila in iste stevilke
 * kot `core/link_varovalka.py` na racunalniku (docs/LINK-DEFENCE.md):
 *  - v eni uri najvec POSKUSOV neuspelih poskusov kode in ZACETKOV zacetkov prijave (vsak zacetek uporabniku pokaze
 *    kodo na zaslonu). Uspesna seznanitev svoj poskus in svoj zacetek vrne - domace povezovanje ne steje;
 *  - ko je meja dosezena, se povezovanje s kodo ZAPRE: prvic za eno uro, drugic za en dan, potem za en teden. Po
 *    zapori veljata manjsi meji, dokler ne mine POZABI_MS brez nove zapore;
 *  - med zaporo se nova naprava poveze samo, ce je uporabnik na eni od svojih naprav odprl »Poveži naprave« (vabilo):
 *    to je izrecno dejanje zaupane naprave. Tudi takrat je poskusov najvec KREDIT_VABILA na zaporo;
 *  - povezava s kodo QR ni prizadeta: njena skrivnost ima 128 bitov in je ni mogoce uganiti.
 *
 * Stanje prezivi ponovni zagon (`shrani`/`stanje`): sicer bi vsak zagon sredisca napadalcu vrnil polno mejo.
 *
 * Razred je cista logika (ura je parameter): brez Androida, omrezja in niti - preizkusljiv na JVM.
 */
class HubVarovalka(
    private val ura: () -> Long = { System.currentTimeMillis() },
    stanje: String? = null,
    private val shrani: ((String) -> Unit)? = null
) {
    data class Zapora(val trajanjeMs: Long, val razlog: String, val ponovitev: Int, val viri: List<Pair<String, Int>>)

    data class Stanje(
        val zaprto: Boolean, val seMs: Long, val poskusov: Int, val mejaPoskusov: Int,
        val zacetkov: Int, val mejaZacetkov: Int, val ponovitev: Int, val kredit: Int
    )

    /** Klic ob zapori (zunaj zaklepa varovalke). */
    @Volatile
    var obZapori: ((Zapora) -> Unit)? = null

    private class Zapis(val cas: Long, val vir: String)

    private val zaklep = Any()
    private var poskusi = ArrayList<Zapis>()
    private var zacetki = ArrayList<Zapis>()
    private var zaprtoDo = 0L
    private var ponovitev = 0
    private var kredit = 0

    init {
        uvozi(stanje)
    }

    // ------------------------------------------------------------------ stanje v shrambi

    private fun uvozi(stanje: String?) {
        if (stanje.isNullOrBlank()) return
        try {
            val vrstice = stanje.split('\n')
            if (vrstice.size < 6 || vrstice[0] != RAZLICICA) return
            val do_ = vrstice[1].toLong()
            val pon = vrstice[2].toInt().coerceIn(0, 1000)
            val kr = vrstice[3].toInt().coerceIn(0, KREDIT_VABILA)
            val p = beriZapise(vrstice[4])
            val z = beriZapise(vrstice[5])
            zaprtoDo = do_
            ponovitev = pon
            kredit = kr
            poskusi = p
            zacetki = z
        } catch (_: Exception) {
            // Pokvarjen zapis: varovalka zacne znova.
        }
    }

    private fun beriZapise(vrstica: String): ArrayList<Zapis> {
        val izid = ArrayList<Zapis>()
        if (vrstica.isEmpty()) return izid
        for (del in vrstica.split(',')) {
            val locilo = del.indexOf('@')
            if (locilo <= 0) continue
            val cas = del.substring(0, locilo).toLongOrNull() ?: continue
            izid.add(Zapis(cas, del.substring(locilo + 1).take(NAJVEC_VIRA)))
        }
        val najvec = maxOf(POSKUSOV, ZACETKOV)
        return if (izid.size > najvec) ArrayList(izid.subList(izid.size - najvec, izid.size)) else izid
    }

    fun izvozi(): String = synchronized(zaklep) { izvoz() }

    private fun izvoz(): String {
        fun seznam(s: List<Zapis>) = s.joinToString(",") { "${it.cas}@${it.vir}" }
        return listOf(RAZLICICA, zaprtoDo.toString(), ponovitev.toString(), kredit.toString(), seznam(poskusi), seznam(zacetki))
            .joinToString("\n")
    }

    /** Klice se pod zaklepom. Napaka pri pisanju varovalke ne ustavi. */
    private fun shraniStanje() {
        val shrani = this.shrani ?: return
        try {
            shrani(izvoz())
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ pravila

    /** Klice se pod zaklepom. Zapis iz »prihodnosti« (ura je sla nazaj) velja za pravkar narejenega. */
    private fun pocisti(zdaj: Long) {
        val meja = zdaj - OKNO_MS
        fun sveze(s: List<Zapis>) = ArrayList(s.map { Zapis(minOf(it.cas, zdaj), it.vir) }.filter { it.cas > meja })
        poskusi = sveze(poskusi)
        zacetki = sveze(zacetki)
        val najdaljsa = ZAPORE_MS[ZAPORE_MS.size - 1]
        if (zaprtoDo > zdaj + najdaljsa) zaprtoDo = zdaj + najdaljsa
        if (ponovitev > 0 && zaprtoDo <= zdaj && zdaj - zaprtoDo > POZABI_MS) ponovitev = 0
    }

    private fun mejaPoskusov() = if (ponovitev > 0) POSKUSOV_PO_ZAPORI else POSKUSOV
    private fun mejaZacetkov() = if (ponovitev > 0) ZACETKOV_PO_ZAPORI else ZACETKOV

    /** Klice se pod zaklepom. Vrne dogodek za `obZapori`. */
    private fun zapri(zdaj: Long, razlog: String): Zapora {
        val trajanje = ZAPORE_MS[minOf(ponovitev, ZAPORE_MS.size - 1)]
        val stetje = LinkedHashMap<String, Int>()
        for (z in (if (razlog == RAZLOG_KODE) poskusi else zacetki)) {
            if (z.vir.isNotEmpty()) stetje[z.vir] = (stetje[z.vir] ?: 0) + 1
        }
        val viri = stetje.entries.sortedWith(compareBy({ -it.value }, { it.key })).take(NAJVEC_VIROV).map { it.key to it.value }
        ponovitev += 1
        zaprtoDo = zdaj + trajanje
        kredit = KREDIT_VABILA
        poskusi = ArrayList()
        zacetki = ArrayList()
        shraniStanje()
        return Zapora(trajanje, razlog, ponovitev, viri)
    }

    private fun sporoci(z: Zapora?) {
        if (z == null) return
        try {
            obZapori?.invoke(z)
        } catch (_: Throwable) {
        }
    }

    fun zaprto(): Boolean = synchronized(zaklep) { ura() < zaprtoDo }

    /** Ali sme nova naprava zdaj zaceti prijavo s kodo (brez stetja). */
    fun smeZaceti(vabilo: Boolean = false): Boolean = synchronized(zaklep) {
        val zdaj = ura()
        pocisti(zdaj)
        if (zdaj >= zaprtoDo) true else vabilo && kredit > 0
    }

    /** Nova naprava zacenja prijavo s kodo. false = povezovanje s kodo je zaprto (prijave se ne odpre). */
    fun zacetek(vir: String = "", vabilo: Boolean = false): Boolean {
        var dogodek: Zapora? = null
        synchronized(zaklep) {
            val zdaj = ura()
            pocisti(zdaj)
            if (zdaj < zaprtoDo) return vabilo && kredit > 0
            zacetki.add(Zapis(zdaj, cist(vir)))
            if (zacetki.size >= mejaZacetkov()) dogodek = zapri(zdaj, RAZLOG_ZACETKI) else shraniStanje()
        }
        sporoci(dogodek)
        // Zacetek, ki je sprozil zaporo, velja le, ce je uporabnik sam odprl »Poveži naprave«.
        return if (dogodek != null) vabilo else true
    }

    /** En krog SPAKE2 = en poskus kode. false = poskusa ne izvedemo (povezovanje s kodo je zaprto). */
    fun poskus(vir: String = "", vabilo: Boolean = false): Boolean {
        var dogodek: Zapora? = null
        synchronized(zaklep) {
            val zdaj = ura()
            pocisti(zdaj)
            if (zdaj < zaprtoDo) {
                if (!vabilo || kredit <= 0) return false
                kredit -= 1
                shraniStanje()
                return true
            }
            poskusi.add(Zapis(zdaj, cist(vir)))
            if (poskusi.size >= mejaPoskusov()) {
                dogodek = zapri(zdaj, RAZLOG_KODE)
                if (vabilo) {
                    kredit -= 1
                    shraniStanje()
                }
            } else {
                shraniStanje()
            }
        }
        sporoci(dogodek)
        return if (dogodek != null) vabilo else true
    }

    /** Seznanitev je uspela: njen poskus in njen zacetek ne stejeta (koda je bila prava). */
    fun uspeh(vir: String = "") {
        synchronized(zaklep) {
            val zdaj = ura()
            pocisti(zdaj)
            val v = cist(vir)
            if (zdaj < zaprtoDo) kredit = minOf(KREDIT_VABILA, kredit + 1)
            for (seznam in listOf(poskusi, zacetki)) {
                val i = seznam.indexOfLast { it.vir == v }
                if (i >= 0) seznam.removeAt(i)
            }
            shraniStanje()
        }
    }

    /** Uporabnik je zaporo sam koncal. Stetje ponovitev ostane: naslednja zapora je daljsa. */
    fun odpri() {
        synchronized(zaklep) {
            val zdaj = ura()
            if (zdaj < zaprtoDo) {
                zaprtoDo = zdaj
                kredit = 0
                shraniStanje()
            }
        }
    }

    fun stanje(): Stanje = synchronized(zaklep) {
        val zdaj = ura()
        pocisti(zdaj)
        val zaprto = zdaj < zaprtoDo
        Stanje(
            zaprto, if (zaprto) zaprtoDo - zdaj else 0L, poskusi.size, mejaPoskusov(),
            zacetki.size, mejaZacetkov(), ponovitev, if (zaprto) kredit else 0
        )
    }

    companion object {
        /** Okno stetja. */
        const val OKNO_MS = 3_600_000L

        /** Neuspelih poskusov kode v oknu, preden se povezovanje s kodo zapre; manjsa meja velja po zapori. */
        const val POSKUSOV = 20
        const val POSKUSOV_PO_ZAPORI = 5

        /** Zacetkov prijave v oknu (vsak pokaze kodo na zaslonu); manjsa meja velja po zapori. */
        const val ZACETKOV = 30
        const val ZACETKOV_PO_ZAPORI = 10

        /** Trajanje prve, druge in vsake naslednje zapore. */
        val ZAPORE_MS = longArrayOf(3_600_000L, 86_400_000L, 7 * 86_400_000L)

        /** Toliko casa po koncu zadnje zapore se stetje ponovitev zacne znova. */
        const val POZABI_MS = 30 * 86_400_000L

        /** Poskusov kode med zaporo, kadar je uporabnik na svoji napravi odprl »Poveži naprave«. */
        const val KREDIT_VABILA = 10

        /** Koliko virov nasteje dogodek ob zapori (najpogostejsi najprej). */
        const val NAJVEC_VIROV = 3

        const val RAZLOG_KODE = "kode"
        const val RAZLOG_ZACETKI = "zacetki"

        private const val RAZLICICA = "1"
        private const val NAJVEC_VIRA = 64

        /** Vir v zapisu ne sme vsebovati locil zapisa. */
        internal fun cist(vir: String): String =
            vir.filter { it != ',' && it != '@' && it != '\n' && it != '\r' }.take(NAJVEC_VIRA)
    }
}
