package si.safeer.tv.cast

/**
 * Zelo majhen bralec in pisec JSON za ovojnice sporocil Safeer Cast/Sync.
 *
 * Zakaj ne org.json, ki ga Android ze ima:
 *  - Vozlisce bere samo ovojnico (type, id, target) in tovora ne razlaga. Sporocilo posreduje
 *    naprej tako, kot ga je prejelo - besedilo v besedilo. Zato ne potrebujemo drevesa
 *    objektov za tovor, ki je lahko velik: preberemo le, kar res beremo, ostalo pustimo pri
 *    miru. Pri 256 kB sporocilu je razlika v pomnilniku velika, televizor pa ga nima na pretek.
 *  - Isti razred se prevede in preizkusi v navadnem JVM. Ce bi uporabili org.json, bi morali
 *    pri preizkusu podtakniti nadomestek in bi preizkusali nadomestek, ne pa kode, ki tece
 *    na televizorju.
 *
 * Bralec je namenoma strog: kar ni pravilen JSON, ni sporocilo. Vgnezdene vrednosti kljub
 * temu v celoti preveri (le v pomnilnik jih ne razgrne), da pokvarjen tovor ne pride naprej.
 */
object JsonLahki {

    /** Globlje od tega ne gremo; branje ne sme biti nacin, da nekdo porabi sklad. */
    const val NAJVECJA_GLOBINA = 12

    /**
     * Ali besedila NE smemo dati razclenjevalniku org.json, ker bi bilo lahko gnezdeno globlje od [najvec]. org.json
     * gnezdenja ne omejuje: z zelo globokim JSON-om porabi sklad niti (StackOverflowError ni Exception - drugi neodvisni
     * pregled, 7. 10. 2026). Sporocila Safeer Linka so gnezdena nekaj ravni.
     *
     * Globino potrdi STROGI bralec ([strogObjekt]). org.json je namrec popustljiv: sprejme gole besede, enojne
     * narekovaje in komentarje, zato se z zapisom, ki ni strogi JSON, stetje oklepajev »zunaj nizov« da pretentati
     * (`{a":[[[[...` - golo besedo `a"` org.json prebere kot kljuc; cetrti neodvisni pregled, 7. 10. 2026). Besedilo, ki
     * ni strogi objekt JSON, sme zato naprej samo, ce v njem sploh ni vec kot [najvec] oklepajev - potem ne more biti
     * gnezdeno globlje, kakorkoli ga kdo bere.
     */
    fun pregloboko(vir: String, najvec: Int = 64): Boolean {
        if (strogObjekt(vir, najvec)) return false
        var oklepajev = 0
        for (c in vir) if (c == '{' || c == '[') { if (++oklepajev > najvec) return true }
        return false
    }

    /**
     * Ali je [vir] en sam objekt strogega JSON, gnezden najvec [najvecGlobina] ravni (objekt na vrhu je prva raven).
     * Samo preveri - vrednosti ne odkodira in ne hrani.
     */
    fun strogObjekt(vir: String, najvecGlobina: Int = NAJVECJA_GLOBINA): Boolean = try {
        Bralec(vir, najvecGlobina, samoPreverba = true).jeEnObjekt()
    } catch (e: Exception) {
        false
    }

    enum class Vrsta { NIZ, STEVILO, LOGICNO, NIC, OBJEKT, SEZNAM }

    /** Prebere en sam objekt JSON. Vrne null, ce vir ni pravilen objekt ali ce kaj ostane za njim. */
    fun objekt(vir: String): Pogled? {
        val bralec = Bralec(vir)
        return try {
            bralec.preskociPresledke()
            val pogled = bralec.objekt(1) ?: return null
            bralec.preskociPresledke()
            if (!bralec.konec()) null else pogled
        } catch (e: Exception) {
            null
        }
    }

    /** Polje objekta: vrsta, obmocje v izvirnem besedilu in, pri nizih, ze odkodirana vrednost. */
    class Polje internal constructor(
        val vrsta: Vrsta,
        internal val zacetek: Int,
        internal val konec: Int,
        internal val niz: String?
    )

    /** Pogled na en objekt: kljuci prve ravni, vgnezdeno le kot obmocje besedila. */
    class Pogled internal constructor(
        private val vir: String,
        private val polja: Map<String, Polje>
    ) {
        fun ima(kljuc: String): Boolean = polja.containsKey(kljuc)

        fun kljuci(): Set<String> = polja.keys

        fun vrsta(kljuc: String): Vrsta? = polja[kljuc]?.vrsta

        fun niz(kljuc: String): String? {
            val polje = polja[kljuc] ?: return null
            return if (polje.vrsta == Vrsta.NIZ) polje.niz else null
        }

        /** Niz ali prazno; udobno tam, kjer manjkajoca vrednost pomeni isto kot prazna. */
        fun nizAli(kljuc: String, privzeto: String = ""): String = niz(kljuc) ?: privzeto

        fun stevilo(kljuc: String): Double? {
            val polje = polja[kljuc] ?: return null
            if (polje.vrsta != Vrsta.STEVILO) return null
            return vir.substring(polje.zacetek, polje.konec).toDoubleOrNull()
        }

        fun logicno(kljuc: String): Boolean? {
            val polje = polja[kljuc] ?: return null
            if (polje.vrsta != Vrsta.LOGICNO) return null
            return vir.substring(polje.zacetek, polje.konec) == "true"
        }

        /** Izvirno besedilo vrednosti, brez razlaganja - za posredovanje naprej. */
        fun surovo(kljuc: String): String? {
            val polje = polja[kljuc] ?: return null
            return vir.substring(polje.zacetek, polje.konec)
        }

        /** Vgnezden objekt se prebere sele, ko ga kdo res potrebuje. */
        fun objekt(kljuc: String): Pogled? {
            val polje = polja[kljuc] ?: return null
            if (polje.vrsta != Vrsta.OBJEKT) return null
            return JsonLahki.objekt(vir.substring(polje.zacetek, polje.konec))
        }

        /** Seznam nizov (npr. zmoznosti naprave); kar ni niz, se tiho izpusti. */
        fun nizi(kljuc: String): List<String> {
            val polje = polja[kljuc] ?: return emptyList()
            if (polje.vrsta != Vrsta.SEZNAM) return emptyList()
            val besedilo = vir.substring(polje.zacetek, polje.konec)
            val bralec = Bralec(besedilo)
            return try {
                bralec.nizi()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    // ------------------------------------------------------------------ branje

    internal class Bralec(
        private val vir: String,
        /** Globlje od tega bralec ne gre (branje ne sme biti nacin, da nekdo porabi sklad). */
        private val najvecGlobina: Int = NAJVECJA_GLOBINA,
        /** Samo preverba oblike: nizov ne odkodira in ne hrani (glej strogObjekt). */
        private val samoPreverba: Boolean = false,
    ) {
        private var i = 0
        private var zadnjiNiz: String? = null

        /** Ali je ves vir en sam objekt (z dovoljenimi presledki pred njim in za njim). */
        fun jeEnObjekt(): Boolean {
            preskociPresledke()
            if (!preskociObjekt(1)) return false
            preskociPresledke()
            return konec()
        }

        fun konec(): Boolean = i >= vir.length

        fun preskociPresledke() {
            while (i < vir.length) {
                when (vir[i]) {
                    ' ', '\t', '\n', '\r' -> i++
                    else -> return
                }
            }
        }

        fun objekt(globina: Int): Pogled? {
            if (globina > najvecGlobina) return null
            if (i >= vir.length || vir[i] != '{') return null
            i++
            val polja = LinkedHashMap<String, Polje>()
            preskociPresledke()
            if (i < vir.length && vir[i] == '}') {
                i++
                return Pogled(vir, polja)
            }
            while (true) {
                preskociPresledke()
                val kljuc = niz() ?: return null
                preskociPresledke()
                if (i >= vir.length || vir[i] != ':') return null
                i++
                preskociPresledke()
                val zacetek = i
                val vrsta = vrednost(globina) ?: return null
                polja[kljuc] = Polje(vrsta, zacetek, i, if (vrsta == Vrsta.NIZ) zadnjiNiz else null)
                preskociPresledke()
                if (i >= vir.length) return null
                when (vir[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return Pogled(vir, polja)
                    }
                    else -> return null
                }
            }
        }

        /** Prebere vrednost; nize odkodira, vgnezdeno samo preveri in preskoci. */
        private fun vrednost(globina: Int): Vrsta? {
            if (i >= vir.length) return null
            return when (vir[i]) {
                '"' -> {
                    zadnjiNiz = niz() ?: return null
                    Vrsta.NIZ
                }
                '{' -> if (preskociObjekt(globina + 1)) Vrsta.OBJEKT else null
                '[' -> if (preskociSeznam(globina + 1)) Vrsta.SEZNAM else null
                't' -> if (vir.startsWith("true", i)) { i += 4; Vrsta.LOGICNO } else null
                'f' -> if (vir.startsWith("false", i)) { i += 5; Vrsta.LOGICNO } else null
                'n' -> if (vir.startsWith("null", i)) { i += 4; Vrsta.NIC } else null
                else -> if (stevilo()) Vrsta.STEVILO else null
            }
        }

        private fun preskociObjekt(globina: Int): Boolean {
            if (globina > najvecGlobina) return false
            if (i >= vir.length || vir[i] != '{') return false
            i++
            preskociPresledke()
            if (i < vir.length && vir[i] == '}') {
                i++
                return true
            }
            while (true) {
                preskociPresledke()
                if (niz() == null) return false
                preskociPresledke()
                if (i >= vir.length || vir[i] != ':') return false
                i++
                preskociPresledke()
                if (vrednost(globina) == null) return false
                preskociPresledke()
                if (i >= vir.length) return false
                when (vir[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return true
                    }
                    else -> return false
                }
            }
        }

        private fun preskociSeznam(globina: Int): Boolean {
            if (globina > najvecGlobina) return false
            if (i >= vir.length || vir[i] != '[') return false
            i++
            preskociPresledke()
            if (i < vir.length && vir[i] == ']') {
                i++
                return true
            }
            while (true) {
                preskociPresledke()
                if (vrednost(globina) == null) return false
                preskociPresledke()
                if (i >= vir.length) return false
                when (vir[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return true
                    }
                    else -> return false
                }
            }
        }

        /** Seznam na vrhu vira; uporabljeno za zmoznosti naprave. */
        fun nizi(): List<String> {
            preskociPresledke()
            if (i >= vir.length || vir[i] != '[') return emptyList()
            i++
            val izpis = ArrayList<String>()
            preskociPresledke()
            if (i < vir.length && vir[i] == ']') return izpis
            while (true) {
                preskociPresledke()
                if (i >= vir.length) return izpis
                if (vir[i] == '"') {
                    val vrednost = niz() ?: return izpis
                    if (izpis.size < NAJVEC_V_SEZNAMU) izpis.add(vrednost)
                } else if (vrednost(1) == null) {
                    return izpis
                }
                preskociPresledke()
                if (i >= vir.length) return izpis
                when (vir[i]) {
                    ',' -> i++
                    ']' -> return izpis
                    else -> return izpis
                }
            }
        }

        /** Preskoci niz, ne da bi ga odkodiral; ista pravila kot [niz] (strogi JSON). */
        private fun preskociNiz(): Boolean {
            if (i >= vir.length || vir[i] != '"') return false
            i++
            val n = vir.length
            while (i < n) {
                val c = vir[i]
                when {
                    c == '"' -> { i++; return true }
                    c == '\\' -> {
                        i++
                        if (i >= n) return false
                        when (vir[i]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> i++
                            'u' -> {
                                if (i + 4 >= n) return false
                                for (k in 1..4) {
                                    val h = vir[i + k]
                                    if (!(h in '0'..'9' || h in 'a'..'f' || h in 'A'..'F')) return false
                                }
                                i += 5
                            }
                            else -> return false
                        }
                    }
                    c.code < 0x20 -> return false       // neubezani nadzorni znaki niso dovoljeni
                    else -> i++
                }
            }
            return false
        }

        private fun niz(): String? {
            if (samoPreverba) return if (preskociNiz()) "" else null
            if (i >= vir.length || vir[i] != '"') return null
            i++
            // Hitra pot: niz brez ubeznih znakov (velika vecina, tudi 32 KiB kosi tokov v base64) je kar izsek
            // vira. Prej je sel vsak znak posebej skozi StringBuilder - pri tokovih Internet Gatewaya je to
            // zasedlo celo jedro telefona.
            val zacetek = i
            var j = i
            val n = vir.length
            while (j < n) {
                val c = vir[j]
                if (c == '"') {
                    i = j + 1
                    return vir.substring(zacetek, j)
                }
                if (c == '\\' || c.code < 0x20) break
                j++
            }
            if (j >= n) return null
            val izpis = StringBuilder(j - zacetek + 32)
            izpis.append(vir, zacetek, j)
            i = j
            while (i < vir.length) {
                val c = vir[i]
                when {
                    c == '"' -> {
                        i++
                        return izpis.toString()
                    }
                    c == '\\' -> {
                        i++
                        if (i >= vir.length) return null
                        when (vir[i]) {
                            '"' -> izpis.append('"')
                            '\\' -> izpis.append('\\')
                            '/' -> izpis.append('/')
                            'b' -> izpis.append('\b')
                            'f' -> izpis.append('')
                            'n' -> izpis.append('\n')
                            'r' -> izpis.append('\r')
                            't' -> izpis.append('\t')
                            'u' -> {
                                if (i + 4 >= vir.length) return null
                                val koda = vir.substring(i + 1, i + 5).toIntOrNull(16) ?: return null
                                izpis.append(koda.toChar())
                                i += 4
                            }
                            else -> return null
                        }
                        i++
                    }
                    c.code < 0x20 -> return null   // neubezani nadzorni znaki niso dovoljeni
                    else -> {
                        izpis.append(c)
                        i++
                    }
                }
            }
            return null
        }

        private fun stevilo(): Boolean {
            val zacetek = i
            if (i < vir.length && vir[i] == '-') i++
            var stevk = 0
            while (i < vir.length && vir[i].isDigit()) { i++; stevk++ }
            if (stevk == 0) { i = zacetek; return false }
            if (i < vir.length && vir[i] == '.') {
                i++
                var decimalk = 0
                while (i < vir.length && vir[i].isDigit()) { i++; decimalk++ }
                if (decimalk == 0) { i = zacetek; return false }
            }
            if (i < vir.length && (vir[i] == 'e' || vir[i] == 'E')) {
                i++
                if (i < vir.length && (vir[i] == '+' || vir[i] == '-')) i++
                var eksponentnih = 0
                while (i < vir.length && vir[i].isDigit()) { i++; eksponentnih++ }
                if (eksponentnih == 0) { i = zacetek; return false }
            }
            return true
        }
    }

    // ------------------------------------------------------------------ pisanje

    /** Ubezi niz za JSON. Nadzorni znaki gredo v \\u obliko, da izpis ostane veljaven. */
    fun ubezi(besedilo: String): String {
        val n = besedilo.length
        var i = 0
        // Hitra pot: nic za ubezati (identifikatorji, base64) - vrnemo isti niz, brez kopije.
        while (i < n) {
            val c = besedilo[i]
            if (c == '"' || c == '\\' || c.code < 0x20) break
            i++
        }
        if (i == n) return besedilo
        val izpis = StringBuilder(n + 64)
        var cisto = 0          // zacetek odseka brez posebnih znakov; prepise se v enem kosu
        while (i < n) {
            val c = besedilo[i]
            val zamenjava: String? = when {
                c == '"' -> "\\\""
                c == '\\' -> "\\\\"
                c == '\n' -> "\\n"
                c == '\r' -> "\\r"
                c == '\t' -> "\\t"
                c.code < 0x20 -> String.format("\\u%04x", c.code)
                else -> null
            }
            if (zamenjava != null) {
                izpis.append(besedilo, cisto, i).append(zamenjava)
                cisto = i + 1
            }
            i++
        }
        izpis.append(besedilo, cisto, n)
        return izpis.toString()
    }

    /** Stevilo brez znanstvenega zapisa - drugi konec bere JSON, ne Jave. */
    fun stevilo(vrednost: Double): String {
        if (vrednost == vrednost.toLong().toDouble()) return vrednost.toLong().toString()
        return java.math.BigDecimal(vrednost)
            .setScale(3, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }

    /** Sestavljanje objekta po delih; vrstni red kljucev je tak, kot smo jih dodali. */
    class Zapis {
        private val deli = ArrayList<String>()

        fun niz(kljuc: String, vrednost: String?): Zapis {
            deli.add(if (vrednost == null) "\"${ubezi(kljuc)}\":null"
                     else "\"${ubezi(kljuc)}\":\"${ubezi(vrednost)}\"")
            return this
        }

        fun stevilo(kljuc: String, vrednost: Double): Zapis {
            deli.add("\"${ubezi(kljuc)}\":${stevilo(vrednost)}")
            return this
        }

        fun logicno(kljuc: String, vrednost: Boolean): Zapis {
            deli.add("\"${ubezi(kljuc)}\":$vrednost")
            return this
        }

        fun nic(kljuc: String): Zapis {
            deli.add("\"${ubezi(kljuc)}\":null")
            return this
        }

        /** Ze pripravljen JSON (objekt, seznam ...) - vstavi se tak, kot je. */
        fun surovo(kljuc: String, jsonVrednost: String): Zapis {
            deli.add("\"${ubezi(kljuc)}\":$jsonVrednost")
            return this
        }

        fun seznamNizov(kljuc: String, vrednosti: Collection<String>): Zapis =
            surovo(kljuc, vrednosti.joinToString(",", "[", "]") { "\"${ubezi(it)}\"" })

        override fun toString(): String = deli.joinToString(",", "{", "}")
    }

    private const val NAJVEC_V_SEZNAMU = 32
}
