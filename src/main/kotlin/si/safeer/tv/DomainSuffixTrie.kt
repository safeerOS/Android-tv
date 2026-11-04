package si.safeer.tv

/**
 * Seznam domen za blokiranje, shranjen strnjeno.
 *
 * Prej je bila tu drevesna struktura (suffix trie): vsak del domene je imel svoje vozlisce
 * s svojim HashMapom. Meritev je pokazala 292 bajtov na domeno - pri vec sto tisoc domenah
 * je to stotine megabajtov na napravi, ki jih nima. Televizor je zaradi tega umiral.
 *
 * Zdaj so vse domene v enem samem nizu, poleg pa so tri majhna polja: kje se vsaka zacne,
 * kako je dolga in iz katerega seznama je. Iskanje gre z bisekcijo po urejenih zacetkih.
 * Poraba pade na priblizno trideset bajtov na domeno, ujemanje pa je natanko isto kot prej:
 * domena se ujame tudi za vse svoje poddomene, obvelja pa najkrajsa shranjena pripona.
 *
 * Nalasc ne hranimo zgoscenih vrednosti namesto domen: to bi bilo se manjse, a bi ob
 * (zelo redkem) trku zablokiralo nedolzno stran. Pri varnostni funkciji tega ne tvegamo.
 */
class DomainSuffixTrie {

    data class MatchResult(
        val isMatched: Boolean,
        val matchedDomain: String,
        val category: String?,
        val sourceFeed: String?
    )

    /** Bazen vseh domen, ena za drugo brez locil. */
    private var bazen: String = ""

    /** Med vstavljanjem pisemo sem; ob prvem iskanju to postane bazen. */
    private var gradilnik: StringBuilder? = null

    private var zacetki = IntArray(0)
    private var dolzine = ShortArray(0)
    private var kategorije = ShortArray(0)
    private var viri = ShortArray(0)
    private var stevilo = 0

    /** True, ko so polja urejena in brez dvojnikov (pripravljena za bisekcijo). */
    private var urejeno = true

    /** Kategorije in imena seznamov so le nekaj razlicnih nizov; hranimo jih enkrat. */
    private val besede = ArrayList<String?>()
    private val besedeIndeks = HashMap<String?, Int>()

    val size: Int
        @Synchronized get() {
            strni()
            return stevilo
        }

    // ------------------------------------------------------------------ vstavljanje

    @Synchronized
    fun insert(domain: String, category: String? = null, sourceFeed: String? = null) {
        val clean = normaliziraj(domain) ?: return
        val g = gradilnik ?: StringBuilder(bazen).also { gradilnik = it }
        if (stevilo == zacetki.size) povecajPolja()
        zacetki[stevilo] = g.length
        dolzine[stevilo] = clean.length.toShort()
        kategorije[stevilo] = indeksBesede(category)
        viri[stevilo] = indeksBesede(sourceFeed)
        g.append(clean)
        stevilo++
        urejeno = false
    }

    /**
     * Lastnik 27. 10. 2026 (TV: OutOfMemoryError v strni na glavni niti ob odpiranju strani): uredi seznam zdaj, v niti, ki
     * ga je zgradila - prvo iskanje (shouldOverrideUrlLoading) ga ne gradi vec na glavni niti.
     */
    @Synchronized
    fun pripravi() { strni() }

    @Synchronized
    fun insertAll(domains: Collection<String>, category: String? = null, sourceFeed: String? = null) {
        for (d in domains) insert(d, category, sourceFeed)
    }

    @Synchronized
    fun clear() {
        bazen = ""
        gradilnik = null
        zacetki = IntArray(0)
        dolzine = ShortArray(0)
        kategorije = ShortArray(0)
        viri = ShortArray(0)
        stevilo = 0
        urejeno = true
        besede.clear()
        besedeIndeks.clear()
    }

    // ------------------------------------------------------------------ iskanje

    fun matches(host: String): Boolean = findMatch(host) != null

    /**
     * Poisce najkrajso shranjeno pripono gostitelja - enako kot prej drevo, ki se je
     * ustavilo pri prvem oznacenem vozliscu na poti od vrhnje domene navznoter.
     */
    @Synchronized
    fun findMatch(host: String): MatchResult? {
        val clean = normaliziraj(host) ?: return null
        strni()
        if (stevilo == 0) return null

        // Pripone beremo kar iz gostitelja, od najkrajse proti najdaljsi: "com", "b.com",
        // "a.b.com". Niza ne sestavljamo - primerjamo po odmiku, zato ob iskanju ne nastane
        // nic smeti. Pri blokiranju oglasov se to zgodi ob vsaki zahtevi strani.
        var odmik = clean.length
        while (true) {
            odmik = clean.lastIndexOf('.', odmik - 1)
            val zacetekPripone = if (odmik < 0) 0 else odmik + 1
            val najden = poisci(clean, zacetekPripone)
            if (najden >= 0) {
                return MatchResult(
                    isMatched = true,
                    matchedDomain = clean.substring(zacetekPripone),
                    category = besede.getOrNull(kategorije[najden].toInt()),
                    sourceFeed = besede.getOrNull(viri[najden].toInt())
                )
            }
            if (odmik <= 0) return null
        }
    }

    // ------------------------------------------------------------------ notranjost

    private fun indeksBesede(beseda: String?): Short {
        besedeIndeks[beseda]?.let { return it.toShort() }
        if (besede.size >= Short.MAX_VALUE) return 0   // toliko razlicnih virov ne pricakujemo
        besede.add(beseda)
        val indeks = besede.size - 1
        besedeIndeks[beseda] = indeks
        return indeks.toShort()
    }

    private fun povecajPolja() {
        val nova = if (zacetki.isEmpty()) 64 else zacetki.size * 2
        zacetki = zacetki.copyOf(nova)
        dolzine = dolzine.copyOf(nova)
        kategorije = kategorije.copyOf(nova)
        viri = viri.copyOf(nova)
    }

    /**
     * Uredi vnose po vsebini in odstrani dvojnike (obvelja prvi vstavljeni, tako kot prej,
     * ko drugi vpis v ze oznaceno vozlisce ni spremenil nicesar). Bazen ob tem prepisemo,
     * da za dvojniki ne ostaja prostor.
     */
    private fun strni() {
        if (urejeno) return
        val g = gradilnik
        if (g != null) {
            bazen = g.toString()
            gradilnik = null
        }
        if (stevilo == 0) {
            urejeno = true
            return
        }

        // Urejanje indeksov v IntArray (prej sortedWith na List<Int>: en Integer na vnos, pri 300 000 domenah ~5 MB kopice
        // prehodno). Ob enakem nizu je prvi vstavljeni prvi, zato je vrstni red popoln in rezultat enak kot prej.
        val vrstniRed = urediIndekse()

        val novBazen = StringBuilder(bazen.length)
        val novZacetki = IntArray(stevilo)
        val novDolzine = ShortArray(stevilo)
        val novKategorije = ShortArray(stevilo)
        val novViri = ShortArray(stevilo)
        var novih = 0

        for (i in vrstniRed) {
            if (novih > 0 && primerjajZNizom(novZacetki, novDolzine, novBazen, novih - 1, i, bazen) == 0) {
                continue                      // dvojnik; prvi je ze notri
            }
            novZacetki[novih] = novBazen.length
            novDolzine[novih] = dolzine[i]
            novKategorije[novih] = kategorije[i]
            novViri[novih] = viri[i]
            novBazen.append(bazen, zacetki[i], zacetki[i] + dolzine[i].toInt())
            novih++
        }

        // Star bazen spustimo PRED novim nizom: ob malo pomnilnika ga GC lahko sprosti za novo dodelitev (prej so bile
        // hkrati zive tri kopije - star bazen, gradilnik in nov niz; TV 27. 10. 2026: 5,5 MB dodelitev ni uspela).
        bazen = ""
        bazen = novBazen.toString()
        zacetki = novZacetki.copyOf(novih)
        dolzine = novDolzine.copyOf(novih)
        kategorije = novKategorije.copyOf(novih)
        viri = novViri.copyOf(novih)
        stevilo = novih
        urejeno = true
    }

    /** Stabilno urejanje z zlivanjem nad IntArray brez zapakiranih Integerjev (primerjava ima se indeks kot drugi kljuc). */
    private fun urediIndekse(): IntArray {
        var a = IntArray(stevilo) { it }
        var b = IntArray(stevilo)
        var sirina = 1
        while (sirina < stevilo) {
            var levo = 0
            while (levo < stevilo) {
                val sredina = minOf(levo + sirina, stevilo)
                val desno = minOf(levo + 2 * sirina, stevilo)
                var i = levo; var j = sredina; var k = levo
                while (i < sredina && j < desno) {
                    val c = primerjajVnosa(a[i], a[j])
                    if (c < 0 || (c == 0 && a[i] < a[j])) b[k++] = a[i++] else b[k++] = a[j++]
                }
                while (i < sredina) b[k++] = a[i++]
                while (j < desno) b[k++] = a[j++]
                levo = desno
            }
            val t = a; a = b; b = t
            sirina *= 2
        }
        return a
    }

    private fun primerjajVnosa(a: Int, b: Int): Int {
        val za = zacetki[a]
        val zb = zacetki[b]
        val da = dolzine[a].toInt()
        val db = dolzine[b].toInt()
        val n = if (da < db) da else db
        for (k in 0 until n) {
            val x = bazen[za + k]
            val y = bazen[zb + k]
            if (x != y) return if (x < y) -1 else 1
        }
        return da - db
    }

    /** Primerja ze prepisan vnos (v novem bazenu) s se neprepisanim (v starem). */
    private fun primerjajZNizom(
        novZacetki: IntArray, novDolzine: ShortArray, novBazen: StringBuilder,
        novIndeks: Int, starIndeks: Int, starBazen: String
    ): Int {
        val zn = novZacetki[novIndeks]
        val dn = novDolzine[novIndeks].toInt()
        val zs = zacetki[starIndeks]
        val ds = dolzine[starIndeks].toInt()
        val n = if (dn < ds) dn else ds
        for (k in 0 until n) {
            val x = novBazen[zn + k]
            val y = starBazen[zs + k]
            if (x != y) return if (x < y) -1 else 1
        }
        return dn - ds
    }

    private fun poisci(kljuc: String, odKje: Int): Int {
        var spodaj = 0
        var zgoraj = stevilo - 1
        while (spodaj <= zgoraj) {
            val sredina = (spodaj + zgoraj) ushr 1
            val c = primerjajSKljucem(sredina, kljuc, odKje)
            when {
                c == 0 -> return sredina
                c < 0 -> spodaj = sredina + 1
                else -> zgoraj = sredina - 1
            }
        }
        return -1
    }

    private fun primerjajSKljucem(i: Int, kljuc: String, odKje: Int): Int {
        val z = zacetki[i]
        val d = dolzine[i].toInt()
        val dolzinaKljuca = kljuc.length - odKje
        val n = if (d < dolzinaKljuca) d else dolzinaKljuca
        for (k in 0 until n) {
            val x = bazen[z + k]
            val y = kljuc[odKje + k]
            if (x != y) return if (x < y) -1 else 1
        }
        return d - dolzinaKljuca
    }

    /**
     * Zapise urejen seznam v datoteko za [DomenskoKazalo] (4. 11. 2026: drevo grozenj ob zagonu iz mmap namesto gradnje v
     * kopici). [kljuc] pove, iz cesa je seznam zgrajen; klicatelj ga izracuna iz SHA-256 preverjenih seznamov.
     */
    @Synchronized
    fun zapisiKazalo(datoteka: java.io.File, kljuc: ByteArray) {
        strni()
        val b = bazen
        val z = zacetki
        val d = dolzine
        val k = kategorije
        val v = viri
        val tabela = com.safeer.threatfeed.SortedStringTable.Table(
            stevilo,
            { i -> Odsek(b, z[i], d[i].toInt()) },
            { i -> (k[i].toInt() shl 16) or v[i].toInt() },
        )
        com.safeer.threatfeed.SortedStringTable.write(datoteka, kljuc, ArrayList(besede), listOf(tabela))
    }

    /** Pogled na del bazena brez kopije niza (zapis kazala). */
    private class Odsek(private val s: String, private val od: Int, override val length: Int) : CharSequence {
        override fun get(index: Int): Char = s[od + index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = s.substring(od + startIndex, od + endIndex)
        override fun toString(): String = s.substring(od, od + length)
    }

    companion object {
        /** Domena, daljsa od tega, ni prava domena; dolzino hranimo v dveh bajtih. */
        private const val NAJDALJSA = 255

        /** Normalizacija je ista kot prej: male crke, brez praznih delov, komentarji odpadejo. */
        internal fun normaliziraj(vhod: String): String? {
            val clean = vhod.trim().lowercase()
            if (clean.isEmpty() || clean.startsWith("#")) return null
            val deli = clean.split('.').filter { it.isNotEmpty() }
            if (deli.isEmpty()) return null
            val zdruzeno = deli.joinToString(".")
            // Dolzino hranimo v dveh bajtih; daljse od tega ni prava domena.
            return if (zdruzeno.length > NAJDALJSA) null else zdruzeno
        }
    }
}

/**
 * Isti seznam kot [DomainSuffixTrie], odprt iz datoteke (FileChannel.map): ni v kopici in ga ni treba graditi ob zagonu.
 * Iskanje je natanko isto - najkrajsa shranjena pripona gostitelja obvelja, kategorija in vir iz istih besed.
 */
class DomenskoKazalo private constructor(private val tabela: com.safeer.threatfeed.SortedStringTable) {
    val size: Int get() = tabela.size(0)
    val bajtov: Long get() = tabela.bytes

    fun matches(host: String): Boolean = findMatch(host) != null

    fun findMatch(host: String): DomainSuffixTrie.MatchResult? {
        val clean = DomainSuffixTrie.normaliziraj(host) ?: return null
        if (tabela.size(0) == 0) return null
        var odmik = clean.length
        while (true) {
            odmik = clean.lastIndexOf('.', odmik - 1)
            val zacetekPripone = if (odmik < 0) 0 else odmik + 1
            val v = tabela.find(0, clean, zacetekPripone)
            if (v >= 0) {
                return DomainSuffixTrie.MatchResult(
                    isMatched = true,
                    matchedDomain = clean.substring(zacetekPripone),
                    category = tabela.words.getOrNull(v ushr 16),
                    sourceFeed = tabela.words.getOrNull(v and 0xffff)
                )
            }
            if (odmik <= 0) return null
        }
    }

    /** Celotna datoteka je neposkodovana (CRC32); klici v ozadju, ne na glavni niti. */
    fun neposkodovano(): Boolean = tabela.bodyIntact()

    companion object {
        /** Odpre kazalo, ce je datoteka cela in zgrajena iz [kljuc]; sicer null. Bere samo glavo (ms). */
        fun odpri(datoteka: java.io.File, kljuc: ByteArray): DomenskoKazalo? {
            val t = com.safeer.threatfeed.SortedStringTable.open(datoteka, kljuc) ?: return null
            return if (t.tableCount == 1) DomenskoKazalo(t) else null
        }
    }
}
