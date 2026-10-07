package si.safeer.tv.cast

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Safeer Link: zascita ukazov od naprave do naprave (E2E v1). Ista pravila kot core/link_e2e.py na racunalniku;
 * tests/E2eTest.kt preverja iste vrednosti kot tests/test_link_e2e.py - izvedbi se morata ujemati v bajt.
 *
 * Zakaj. Naprava, ki vsebino hrani, je posiljatelja doslej prepoznala po oznaki, ki jo sporocilu vpise sredisce. Oznaka,
 * ki je le podobna oznaki druge naprave, je dobila njen dostop (izmerjeno 7. 10. 2026 na racunalniku); sredisce pa ta
 * sporocila posreduje nesifrirana in posiljatelja vpise samo - kdor ga gosti, bi jih lahko bral in ponarejal (sledi iz
 * zgradbe). Tu napravi dokazeta KLJUC: vsaka podpise svoj del dogovora s kljucem naprave, druga ga preveri s kljucem
 * iz SVOJEGA kroga zaupanja. Dostop se potem veze na ta kljuc, ne na oznako.
 *
 * Prenos: `data.offer` / `data.answer` / `data.chunk` / `data.error` z namenom "link" - sredisca jih samo posredujejo
 * po polju `target`. Dogovor, gradivo seje in oblika sporocil so opisani v core/link_e2e.py (en opis za obe izvedbi).
 *
 * Vsako notranje sporocilo se sifrira NATANKO ENKRAT. Obvestilo »seje ni« (`data.error`, ni podpisano) sejo samo
 * zavrze in klicatelju javi zavrnitev - sporocil ne posljemo znova, ker bi sredisce s ponarejenim obvestilom sicer
 * doseglo, da se ze izveden ukaz izvede se enkrat.
 *
 * Oblika polj. Polja so v podpisanih bajtih locena z znakom nove vrstice, zato NOBENO polje ne sme vsebovati krmilnega
 * znaka: oznaka naprave je 1-128 vidnih znakov ASCII ([veljavnaOznaka]), oznaka seje crke, stevke, `-` in `_`, nonce,
 * enkratni kljuc in podpis pa standardni base64. To se preveri, PREDEN se karkoli podpise ali preveri. Brez tega je
 * oznaka s prelomom vrstice iste podpisane bajte razdelila na dva nacina in sredisce je sejo ene naprave prevezalo na
 * drugo (drugi neodvisni pregled, 7. 10. 2026).
 *
 * Razred ne pozna Androida, sredisca ali kroga: podpis, preverbo, posiljanje in uro dobi od klicatelja. Kljuc naprave
 * samo podpisuje (AndroidKeyStore drugega ne zna in ne sme); skupna skrivnost je ECDH med ENKRATNIMA kljucema.
 */
class E2e(
    private val mojId: () -> String,
    /** Poslje sporocilo srediscu: (tip, cilj, oznaka sporocila, tovor kot JSON). True, ce je slo v vrsto. */
    private val poslji: (tip: String, cilj: String, oznaka: String, tovorJson: String) -> Boolean,
    /** Podpis bajtov s kljucem TE naprave (base64, SHA256withECDSA DER). */
    private val podpisi: (ByteArray) -> String,
    /** Javni kljuc naprave z danim id-jem iz NASEGA kroga (base64 SPKI DER) ali null. */
    private val kljucZa: (String) -> String?,
    private val preveri: (kljuc: String, podatki: ByteArray, podpis: String) -> Boolean,
    /** Jedro naprave iz kljuca (n-<16 hex>). */
    private val idIzKljuca: (String) -> String,
    /** Sporocilo je prislo zasciteno: (notranje sporocilo kot JSON, id posiljatelja, jedro iz preverjenega kljuca). */
    private val obSporocilu: (notranjeJson: String, od: String, jedro: String) -> Unit,
    /** Z napravo je vzpostavljena preverjena seja (klicatelj si zapomni, da naprava zascito zna). */
    private val obSeji: ((jedro: String) -> Unit)? = null,
    /**
     * Sporocilo do naprave ni prislo ali ga ta ni mogla sprejeti (naprave ni v Linku, seje ne pozna vec, nasega kljuca
     * nima, dogovor je trajal predolgo): (opis sporocila, besedilo, koda). Opis je tisto, kar je klicatelj podal ob
     * [poslji] - celega sporocila ta razred ne hrani.
     */
    private val obZavrnitvi: ((opis: String, napaka: String, koda: String) -> Unit)? = null,
    /** Sredisce je sporocilo sprejelo v posredovanje ciljni napravi: (opis sporocila). */
    private val obSprejemu: ((opis: String) -> Unit)? = null,
    private val ura: () -> Long = { System.nanoTime() / 1_000_000L },
    private val dnevnik: (String) -> Unit = {},
    private val meje: Meje = Meje(),
) {

    class Napaka(sporocilo: String) : Exception(sporocilo)

    /** Meje stevila sej, dogovorov in pomnilnika (v preizkusih manjse). */
    class Meje(
        val sej: Int = NAJVEC_SEJ,
        val sejNaNapravo: Int = NAJVEC_SEJ_NA_NAPRAVO,
        val dogovorov: Int = NAJVEC_DOGOVOROV,
        val nedokoncanih: Int = NAJVEC_NEDOKONCANIH,
        val rezerviranoNaNapravo: Long = NAJVEC_REZERVIRANO_NA_NAPRAVO_B,
        val rezervirano: Long = NAJVEC_REZERVIRANO_B,
        val podpisovNaNapravo: Int = NAJVEC_PODPISOV_NA_NAPRAVO,
        val cakajocihNaNapravo: Long = NAJVEC_CAKAJOCIH_NA_NAPRAVO_B,
        val cakajocih: Long = NAJVEC_CAKAJOCIH_B,
    )

    /** Polja prejetega tovora; klicatelj jih bere iz svojega JSON-a (org.json na napravi, JsonLahki v preizkusih). */
    interface Polja {
        fun niz(ime: String): String?
        /** Celo stevilo ali null (niz, decimalka, manjkajoce polje). */
        fun celo(ime: String): Long?
    }

    class Kljuci(val kAb: ByteArray, val pAb: ByteArray, val kBa: ByteArray, val pBa: ByteArray) {
        fun smer(smer: String): Pair<ByteArray, ByteArray> = if (smer == "ab") kAb to pAb else kBa to pBa
    }

    private class Seja(
        val sessionId: String, val tujId: String, val vloga: Char, val kljuci: Kljuci, val jedro: String, val nastala: Long,
        /**
         * Ali je druga stran dokazala, da kljuc seje ima ZDAJ. Seja, ki smo jo zaceli mi, je potrjena z odgovorom (podpis
         * veze nas svezi nonce). Seja iz prejete ponudbe se ne - ponudbo lahko sredisce ponovi; potrdi jo sele prvi kos,
         * ki se pod njenim kljucem desifrira.
         */
        var potrjena: Boolean,
    ) {
        var seqVen = 0L
        var stSporocila = 0L
        var zadnjiNoter = -1L
        /** Zadnja poslana sporocila: po »seje ni« jih javimo klicatelju kot zavrnjena. */
        val zadnja = ArrayList<Poslano>()
        val deli = LinkedHashMap<Long, Triple<Long, Int, HashMap<Int, ByteArray>>>()  // m -> (cas, n, {i: del})
        val smerVen: String get() = if (vloga == 'a') "ab" else "ba"
        val smerNoter: String get() = if (vloga == 'a') "ba" else "ab"
    }

    /** Poslano sporocilo: stevca prvega in zadnjega kosa, cas in opis (glej [poslji]). */
    private class Poslano(val prvi: Long, val zadnji: Long, val cas: Long, val opis: String)

    private class Dogovor(
        val sessionId: String, val tujId: String, val zasebni: PrivateKey, val epk: String, val nonce: String, val zacet: Long,
        /** Sporocila, ki cakajo na ta dogovor: (kdaj je prislo v vrsto, opis sporocila, cistopis). */
        val vrsta: MutableList<Triple<Long, String, ByteArray>>,
        /** Jedro naprave iz kljuca v nasem krogu (meje na napravo). */
        val jedro: String,
    )

    private val zaklep = Any()
    // Po vrsti nastanka (LinkedHashMap): pri enaki starosti izpade tisto, kar je nastalo prej - kot na racunalniku.
    private val seje = LinkedHashMap<String, Seja>()
    private val za = HashMap<String, String>()
    private val dogovori = LinkedHashMap<String, Dogovor>()
    /**
     * Oznaka sporocila prenosa -> (cas, ali je ponudba dogovora, opisi sporocil, ki jih nosi ali nanj cakajo, ali
     * »sprejeto« pomeni sprejem sporocila, oznake VSEH kosov istega sporocila, zapis med zadnjimi poslanimi).
     */
    private class Izhodno(
        val cas: Long, val dogovor: Boolean, val notranja: () -> List<String>, val sprejem: Boolean,
        val vsi: List<String> = emptyList(), val zapis: Poslano? = null,
    )
    private val izhodna = LinkedHashMap<String, Izhodno>()
    /** Ali v kateri od vrst dogovorov kaj caka. Pise se pod zaklepom, bere tudi brez njega (glej [pospravi]). */
    @Volatile private var nekajCaka = false

    /** Vrste so se spremenile (klice se pod zaklepom): zastavica mora ugasniti takoj, ko ne caka nic vec. */
    private fun posodobiCakanje() { nekajCaka = dogovori.values.any { it.vrsta.isNotEmpty() } }

    // ------------------------------------------------------------------ posiljanje

    fun imaSejo(tujId: String): Boolean = synchronized(zaklep) { sejaZa(tujId) != null }

    fun jedroSeje(tujId: String): String = synchronized(zaklep) { sejaZa(tujId)?.jedro ?: "" }

    /** Koliko sporocil v vec delih seja z napravo trenutno sestavlja (za preizkus meje). */
    fun nedokoncanih(tujId: String): Int = synchronized(zaklep) { sejaZa(tujId)?.deli?.size ?: 0 }

    /** Pomnilnik, rezerviran za sporocila v vec delih, ki se sestavljajo: vseh naprav ali naprave z jedrom [jedro]. */
    fun rezervirano(jedro: String? = null): Long = synchronized(zaklep) {
        var vsota = 0L
        for (s in seje.values) if (jedro == null || s.jedro == jedro) for (v in s.deli.values) vsota += v.second.toLong() * DOLZINA_DELA
        vsota
    }

    /** Stevilo sej (vseh ali naprave z jedrom [jedro]) in ali seja s to oznako obstaja - za preizkus mej. */
    fun stSej(jedro: String? = null): Int = synchronized(zaklep) { seje.values.count { jedro == null || it.jedro == jedro } }
    fun imaSejoZOznako(sessionId: String): Boolean = synchronized(zaklep) { seje.containsKey(sessionId) }
    fun oznakaSejeZa(tujId: String): String = synchronized(zaklep) { sejaZa(tujId)?.sessionId ?: "" }
    fun cakajociDogovori(): List<String> = synchronized(zaklep) { dogovori.keys.toList() }

    /** Koliko znakov besedila hranijo zapisi o poslanem (opisi) - za preizkus, da celih sporocil ne hranimo. */
    fun hranjenihZnakov(): Long = synchronized(zaklep) {
        var vsota = 0L
        for (v in izhodna.values) for (n in v.notranja()) vsota += n.length
        for (s in seje.values) for (z in s.zadnja) vsota += z.opis.length
        vsota
    }

    /** Koliko bajtov cistopisa caka v vrstah dogovorov (vseh ali naprave z jedrom [jedro]) - za preizkus meje. */
    fun cakajocihBajtov(jedro: String? = null): Long = synchronized(zaklep) { cakajocih(jedro) }

    /**
     * Ali z napravo dogovor sploh lahko uspe: oznaka sme v podpisane bajte in njen kljuc je v nasem krogu. Brez
     * podpisovanja - za odlocitev, ali se dogovor splaca zaceti (seznam naprav pise sredisce).
     */
    fun poznaKljuc(tujId: String): Boolean = try { veljavnaOznaka(tujId) && !kljucZa(tujId).isNullOrBlank() } catch (_: Throwable) { false }

    /**
     * Ce z napravo imamo sejo, klicatelju se enkrat sporoci njeno jedro (obSeji) in vrne true. Zapis o tem, katere
     * naprave zascito znajo, se lahko izgubi (pise se v ozadju) - klicatelj ga tako obnovi.
     */
    fun znovaJaviSejo(tujId: String): Boolean {
        val s = synchronized(zaklep) { sejaZa(tujId) } ?: return false
        javiSejo(s)
        return true
    }

    /**
     * Zacne dogovor z napravo, ne da bi zanjo imeli sporocilo: napravi si dokazeta kljuc, se preden je poslan prvi ukaz
     * (klicatelj si prek obSeji zapomni, da naprava zascito zna). True: seja ze obstaja ali je dogovor na poti.
     */
    fun dogovoriSe(tujId: String): Boolean {
        if (tujId.isBlank()) return false
        val izrinjena = ArrayList<String>()
        try {
            synchronized(zaklep) {
                if (sejaZa(tujId) != null) return true
                val d = dogovori[tujId]
                if (d != null && ura() - d.zacet <= DOGOVOR_CAKA_MS) return true
                return zacniDogovor(tujId, null, izrinjena)
            }
        } finally {
            zavrni(izrinjena, BESEDILO_CAS, "cas")        // zunaj zaklepa
        }
    }

    /**
     * Seja velja, dokler ji ni potekel rok IN dokler je kljuc, s katerim se je naprava izkazala, se veljaven clan
     * nasega kroga. Naprava, ki jo uporabnik odstrani iz Linka, tako izgubi tudi ze vzpostavljeno sejo.
     */
    private fun veljaSe(s: Seja): Boolean {
        if (ura() - s.nastala > SEJA_VELJA_MS) return false
        return try {
            val kljuc = kljucZa(s.tujId)
            !kljuc.isNullOrBlank() && idIzKljuca(kljuc) == s.jedro
        } catch (_: Throwable) { false }
    }

    private fun sejaZa(tujId: String): Seja? {
        val sid = za[tujId] ?: return null
        val s = seje[sid]
        // Kazalec brez seje ali na sejo DRUGE naprave ne velja (seja pripada napravi, s katero je bila dogovorjena).
        if (s == null || s.tujId != tujId) { za.remove(tujId); return null }
        if (!veljaSe(s)) { odstrani(s); return null }
        return s
    }

    private fun odstrani(s: Seja) {
        if (seje[s.sessionId] === s) seje.remove(s.sessionId)
        za.entries.removeAll { it.value == s.sessionId }
    }

    /**
     * Naprava je odsla ali se zamenjala: njene seje in cakajoci dogovor ne veljajo vec. Sporocila, ki so cakala na
     * dogovor, klicatelj izve (kot iztek casa) - prej so izginila brez sledu, cistopis pa je ostal v zapisu o ponudbi.
     */
    fun pozabi(tujId: String) {
        val izrinjena = ArrayList<String>()
        synchronized(zaklep) {
            seje.values.filter { it.tujId == tujId }.forEach { odstrani(it) }
            za.remove(tujId)
            dogovori.remove(tujId)?.let { opustiDogovor(it, izrinjena) }
        }
        zavrni(izrinjena, BESEDILO_CAS, "cas")        // zunaj zaklepa
    }

    /** Vse seje in cakajoci dogovori ne veljajo vec. Sporocila, ki so cakala na dogovor, klicatelj izve. */
    fun pozabiVse() {
        val izrinjena = ArrayList<String>()
        synchronized(zaklep) {
            for (d in dogovori.values) {
                for (v in d.vrsta) izrinjena.add(v.second)
                d.vrsta.clear()
            }
            seje.clear(); za.clear(); dogovori.clear(); izhodna.clear()
            nekajCaka = false
        }
        zavrni(izrinjena, BESEDILO_CAS, "cas")        // zunaj zaklepa
    }

    /**
     * Cakajoci dogovor ne velja vec (klice se POD zaklepom, dogovor je ze vzet iz zapisa): zapis o poslani ponudbi gre,
     * opisi sporocil iz vrste pridejo med [izrinjena] (klicatelj jih javi zunaj zaklepa), cistopisi se sprostijo.
     */
    private fun opustiDogovor(d: Dogovor, izrinjena: MutableList<String>) {
        izhodna.remove(PREDPONA_OZNAK + d.sessionId.take(10))
        for (v in d.vrsta) izrinjena.add(v.second)
        d.vrsta.clear()
        posodobiCakanje()
    }

    /**
     * Sporocila, ki predolgo cakajo na dogovor, javi klicatelju kot zavrnjena in sprosti njihov pomnilnik. Klicatelj to
     * poklice ob vsakem prejetem sporocilu: prej se je to zgodilo sele ob naslednjem POSILJANJU - program, ki ni vec
     * posiljal, je cistopis drzal naprej. Nikoli ne vrze.
     */
    fun pospravi() {
        // Brez zaklepa, kadar v vrstah ne caka nic: klice jo bralna nit ob VSAKEM sporocilu sredisca in ne sme cakati na
        // nit, ki ravno podpisuje ali posilja (sesti pregled, 7. 10. 2026). Kadar kaj caka, pocaka najvec na en podpis -
        // posiljanje (vrsta povezave) na omrezje ne caka.
        if (!nekajCaka) return
        val izrinjena = ArrayList<String>()
        try {
            synchronized(zaklep) {
                pospraviVrste(ura(), izrinjena)
                posodobiCakanje()
            }
        } catch (_: Throwable) { }
        zavrni(izrinjena, BESEDILO_CAS, "cas")        // zunaj zaklepa
    }

    /**
     * Poslje notranje sporocilo (JSON) napravi [tujId] zasciteno. Ce seje se ni, zacne dogovor in sporocilo pocaka nanj.
     * [opis] je kratek zapis o sporocilu (pri nas JSON s tipom in oznako - ZascitaLinka), ki ga klicatelj dobi nazaj ob
     * sprejemu ali zavrnitvi: ta razred hrani samo opis, ne sporocila. Brez njega (preizkusi) je opis kar sporocilo, ce
     * je kratko; predolg opis se ne hrani.
     * False: zascita ni mogoca (kljuca naprave ne poznamo, sporocilo je predolgo, vrsta ali meja podpisov je polna) -
     * klicatelj NE sme poslati nezasciteno, razen ce naprava zascite sploh ne zna.
     */
    fun poslji(tujId: String, notranjeJson: String, opis: String? = null): Boolean {
        if (tujId.isBlank()) return false
        val cistopis = notranjeJson.toByteArray(Charsets.UTF_8)
        if (cistopis.size > NAJVEC_DELOV * DOLZINA_DELA) return false      // predolgo za zasciteno pot: klicatelj izve takoj
        val kratekOpis = (opis ?: notranjeJson).let { if (it.length <= NAJVEC_OPISA) it else "" }
        val izrinjena = ArrayList<String>()
        try {
            synchronized(zaklep) {
                sejaZa(tujId)?.let { return posljiPoSeji(it, kratekOpis, cistopis) }
                val zdaj = ura()
                pospraviVrste(zdaj, izrinjena)
                val d = dogovori[tujId]
                if (d != null && zdaj - d.zacet <= DOGOVOR_CAKA_MS) {
                    if (d.vrsta.size >= NAJVEC_V_VRSTI || !jeProstorVVrstah(d.jedro, cistopis.size)) return false
                    d.vrsta.add(Triple(zdaj, kratekOpis, cistopis))
                    nekajCaka = true
                    return true
                }
                // Dogovora se ni ali pa nanj cakamo predolgo: zacnemo znova (glej zacniDogovor, kaj se zgodi s sporocili,
                // ki so cakala na starega).
                return zacniDogovor(tujId, kratekOpis to cistopis, izrinjena)
            }
        } finally {
            zavrni(izrinjena, BESEDILO_CAS, "cas")        // zunaj zaklepa
        }
    }

    /**
     * Ali smemo za napravo s tem jedrom se enkrat podpisati s kljucem naprave. [vloga] 'a' je ponudba, ki jo zacnemo mi,
     * 'b' odgovor na prejeto ponudbo - vsaka ima svojo mejo. Ce vrne true in je [porabi], je podpis ze stet; s
     * porabi = false samo pogleda. Zapis je skupen programu (glej podpisiProgramov in NAJVEC_PODPISOV_NA_NAPRAVO).
     */
    private fun smePodpisati(jedro: String, zdaj: Long, porabi: Boolean = true, vloga: Char = 'a'): Boolean {
        if (jedro.isEmpty()) return false
        val moj = try { mojId() } catch (_: Throwable) { return false }
        val kljuc = "$moj\n$vloga\n$jedro"
        synchronized(podpisiProgramov) {
            val casi = podpisiProgramov.getOrPut(kljuc) { ArrayList() }
            casi.removeAll { zdaj - it >= OKNO_PODPISOV_MS }
            val dovoljeno = casi.size < meje.podpisovNaNapravo
            if (dovoljeno && porabi) casi.add(zdaj)
            if (casi.isEmpty()) podpisiProgramov.remove(kljuc)
            if (podpisiProgramov.size > NAJVEC_ZAPISOV_PODPISOV) {
                podpisiProgramov.entries.removeAll { it.value.isEmpty() || zdaj - it.value.last() >= OKNO_PODPISOV_MS }
                while (podpisiProgramov.size > NAJVEC_ZAPISOV_PODPISOV) podpisiProgramov.remove(podpisiProgramov.keys.first())
            }
            return dovoljeno
        }
    }

    private fun kljucPremora(tujId: String): String = (try { mojId() } catch (_: Throwable) { "" }) + "\n" + tujId

    /** Ali dogovor s to oznako pravkar ni uspel in novega se ne zacnemo. Zapis je skupen programu (neuspeliProgramov). */
    private fun vPremoru(tujId: String, zdaj: Long): Boolean {
        val kljuc = kljucPremora(tujId)
        synchronized(neuspeliProgramov) {
            val kdaj = neuspeliProgramov[kljuc] ?: return false
            if (zdaj - kdaj in 0 until NEUSPEL_DOGOVOR_CAKA_MS) return true
            neuspeliProgramov.remove(kljuc)
            return false
        }
    }

    /** Dogovor s to oznako ni uspel: glej NEUSPEL_DOGOVOR_CAKA_MS. */
    private fun zabeleziNeuspeh(tujId: String, zdaj: Long) {
        val kljuc = kljucPremora(tujId)
        synchronized(neuspeliProgramov) {
            if (!neuspeliProgramov.containsKey(kljuc) && neuspeliProgramov.size >= NAJVEC_NEUSPELIH) {
                neuspeliProgramov.entries.removeAll { zdaj - it.value !in 0 until NEUSPEL_DOGOVOR_CAKA_MS }
                while (neuspeliProgramov.size >= NAJVEC_NEUSPELIH) neuspeliProgramov.remove(neuspeliProgramov.keys.first())
            }
            neuspeliProgramov[kljuc] = zdaj
        }
    }

    /** Naprava je tu in nas pozna (dogovor je uspel): premora po neuspelem dogovoru ni vec. */
    private fun konecPremora(tujId: String) {
        val kljuc = kljucPremora(tujId)
        synchronized(neuspeliProgramov) { neuspeliProgramov.remove(kljuc) }
    }

    /**
     * Najvec NAJVEC_IZHODNIH zapisov o poslanem (klice se pod zaklepom). Z najstarejsim izpadejo VSI kosi istega
     * sporocila: zavrnitve kosa, katerega zapis je izpadel, ne bi opazili in klicatelj bi za sporocilo dobil »sprejeto«
     * (sesti pregled, 7. 10. 2026) - tako ne dobi izida (iztek casa).
     */
    private fun omejiIzhodna() {
        while (izhodna.size > NAJVEC_IZHODNIH) {
            val vnos = izhodna.remove(izhodna.keys.first()) ?: break
            for (druga in vnos.vsi) izhodna.remove(druga)
        }
    }

    private fun cakajocih(jedro: String?): Long {
        var vsota = 0L
        for (d in dogovori.values) if (jedro == null || d.jedro == jedro) for (v in d.vrsta) vsota += v.third.size
        return vsota
    }

    /** Ali sme v vrsto cakajocega dogovora z napravo [jedro] se sporocilo z [novo] bajti (pod zaklepom). */
    private fun jeProstorVVrstah(jedro: String, novo: Int): Boolean =
        cakajocih(jedro) + novo <= meje.cakajocihNaNapravo && cakajocih(null) + novo <= meje.cakajocih

    /**
     * Iz vrst VSEH cakajocih dogovorov vzame sporocila, ki cakajo predolgo (ne gredo vec - glej V_VRSTI_VELJA_MS):
     * pomnilnik se sprosti, njihovi opisi gredo med [izrinjena] (klicatelj izve). Klice se pod zaklepom.
     */
    private fun pospraviVrste(zdaj: Long, izrinjena: MutableList<String>) {
        for (d in dogovori.values) {
            if (d.vrsta.isEmpty() || zdaj - d.vrsta[0].first <= V_VRSTI_VELJA_MS) continue
            val po = d.vrsta.iterator()
            while (po.hasNext()) {
                val v = po.next()
                if (zdaj - v.first > V_VRSTI_VELJA_MS) { izrinjena.add(v.second); po.remove() }
            }
        }
    }

    /**
     * Zacne dogovor z napravo (klice se POD zaklepom). [sporocilo] je (opis, cistopis) sporocila, ki nanj caka; null
     * pomeni dogovor brez sporocila (dokaz kljuca). V [izrinjena] pridejo OPISI sporocil, ki zaradi tega dogovora NE bodo
     * poslana - klicatelj jih javi kot zavrnjena zunaj zaklepa:
     * - kar je na katerikoli dogovor cakalo dlje kot V_VRSTI_VELJA_MS (ukaz, ki bi se izvedel z zamudo, je slabsi od
     *   ukaza, ki se ne izvede); sveze caka naprej - tudi na novi dogovor z isto napravo;
     * - sporocila dogovora z DRUGO napravo, ki je moral narediti prostor.
     */
    private fun zacniDogovor(tujId: String, sporocilo: Pair<String, ByteArray>?, izrinjena: MutableList<String>): Boolean {
        val moj = mojId()
        if (!veljavnaOznaka(tujId) || !veljavnaOznaka(moj)) return false   // oznaka, ki ne sme v podpisane bajte dogovora
        val kljuc = kljucZa(tujId)
        if (kljuc.isNullOrBlank()) return false               // naprave ni v nasem krogu: odgovora ne bi mogli preveriti
        val jedro = try { idIzKljuca(kljuc) } catch (_: Throwable) { return false }
        val zdaj = ura()
        pospraviVrste(zdaj, izrinjena)
        // Dogovor s to oznako pravkar ni uspel: kratek premor (NEUSPEL_DOGOVOR_CAKA_MS).
        if (vPremoru(tujId, zdaj)) return false
        // Cakajoca sporocila te naprave (ali vseh skupaj) ze drzijo ves dovoljeni pomnilnik:
        if (sporocilo != null && !jeProstorVVrstah(jedro, sporocilo.second.size)) return false
        // Prevec dogovorov s to napravo v kratkem casu (vsak je podpis s kljucem naprave):
        if (!smePodpisati(jedro, zdaj, porabi = false)) return false
        for (star in dogovori.entries.filter { it.key != tujId && zdaj - it.value.zacet > DOGOVOR_VELJA_MS }) {
            dogovori.remove(star.key)
            opustiDogovor(star.value, izrinjena)
        }
        while (!dogovori.containsKey(tujId) && dogovori.size >= meje.dogovorov) {
            // Prostor naredi najprej dogovor, na katerega ne caka nobeno sporocilo (dokaz kljuca ob seznamu naprav).
            // Seznam naprav pise sredisce: z izmisljenimi napravami ne sme izriniti dogovora, na katerega caka ukaz.
            val prazen = dogovori.entries.filter { it.value.vrsta.isEmpty() }.minByOrNull { it.value.zacet }
            if (prazen != null) { dogovori.remove(prazen.key); opustiDogovor(prazen.value, izrinjena); continue }
            if (sporocilo == null) return false
            val izrinjen = dogovori.entries.minByOrNull { it.value.zacet } ?: break
            dogovori.remove(izrinjen.key)
            opustiDogovor(izrinjen.value, izrinjena)
        }
        smePodpisati(jedro, zdaj)       // podpis stejemo tik pred njim (zgoraj smo samo pogledali)
        val par: Pair<PrivateKey, String>
        val sessionId: String
        val nonce: String
        val podpis: String
        try {
            par = novPar()
            sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(nakljucno(16))
            nonce = b64(nakljucno(16))
            podpis = podpisi(podatkiPonudbe(sessionId, moj, tujId, nonce, par.second))
        } catch (_: Throwable) { return false }
        if (podpis.isBlank()) return false
        val vrsta = ArrayList<Triple<Long, String, ByteArray>>()
        dogovori[tujId]?.let { prejsnji ->
            izhodna.remove(PREDPONA_OZNAK + prejsnji.sessionId.take(10))
            vrsta.addAll(prejsnji.vrsta)        // zastarela je odstranil ze pospraviVrste zgoraj
            prejsnji.vrsta.clear()
        }
        if (sporocilo != null) vrsta.add(Triple(zdaj, sporocilo.first, sporocilo.second))
        while (vrsta.size > NAJVEC_V_VRSTI) izrinjena.add(vrsta.removeAt(0).second)
        val dogovor = Dogovor(sessionId, tujId, par.first, par.second, nonce, zdaj, vrsta, jedro)
        dogovori[tujId] = dogovor
        if (vrsta.isNotEmpty()) nekajCaka = true
        val oznaka = PREDPONA_OZNAK + sessionId.take(10)
        zapomniIzhodno(oznaka, dogovor = true) { dogovor.vrsta.map { it.second } }
        val tovor = "{\"session_id\":${niz(sessionId)},\"purpose\":\"$NAMEN\",\"v\":$RAZLICICA,\"from\":${niz(moj)},\"to\":${niz(tujId)}," +
            "\"nonce\":${niz(nonce)},\"epk\":${niz(par.second)},\"ts\":${System.currentTimeMillis() / 1000},\"sig\":${niz(podpis)}}"
        val ok = poslji("data.offer", tujId, oznaka, tovor)
        if (!ok) {
            dogovori.remove(tujId)
            izhodna.remove(oznaka)
            // Kar je cakalo se od prej, klicatelj izve; za novo sporocilo dobi false.
            val prenesena = if (sporocilo != null) dogovor.vrsta.dropLast(1) else dogovor.vrsta.toList()
            izrinjena.addAll(prenesena.map { it.second })
            dogovor.vrsta.clear()
            posodobiCakanje()
            zabeleziNeuspeh(tujId, zdaj)
        }
        return ok
    }

    /**
     * Cistopis sporocila sifrira in poslje. Vsako notranje sporocilo gre skozi to funkcijo NATANKO ENKRAT - ponovnega
     * posiljanja ni (glej prejmiNapako), zato ga prejemnik ne more dobiti dvakrat. Zapomnimo si samo [opis] sporocila
     * (za sprejem ali zavrnitev), ne sporocila: odgovor, ki ponovi dolgo oznako ukaza ali nosi velik seznam, je sicer
     * ostal v pomnilniku za vsako sejo in vsako potrditev (cetrti neodvisni pregled, 7. 10. 2026).
     */
    private fun posljiPoSeji(s: Seja, opis: String, cistopis: ByteArray): Boolean {
        val stDelov = if (cistopis.isEmpty()) 1 else (cistopis.size + DOLZINA_DELA - 1) / DOLZINA_DELA
        if (stDelov > NAJVEC_DELOV) return false
        val (kljuc, predpona) = s.kljuci.smer(s.smerVen)
        val m = s.stSporocila++
        val prvi = s.seqVen
        s.seqVen += stDelov
        // Zapomnimo si PRED posiljanjem: obvestilo »ni seje« lahko pride, se preden se posiljanje vrne.
        val zdaj = ura()
        s.zadnja.removeAll { zdaj - it.cas > NEPOTRJENA_VELJAJO_MS }
        val zapis = Poslano(prvi, prvi + stDelov - 1, zdaj, opis)
        s.zadnja.add(zapis)
        while (s.zadnja.size > HRANI_ZADNJIH) s.zadnja.removeAt(0)
        // Potrditve sredisca: VSAK kos ima svoj zapis. Sporocilo je SPREJETO, ko je sprejet njegov zadnji kos in pred njim
        // ni bil zavrnjen noben; ZAVRNJENO, ko je zavrnjen katerikoli (javimo enkrat). Prej smo spremljali samo prvega in
        // zadnjega: zavrnjen srednji kos je ostal neopazen in klicatelj je dobil »sprejeto« (peti pregled, 7. 10. 2026).
        val oznake = List(stDelov) { "$PREDPONA_OZNAK${s.sessionId.take(8)}-${prvi + it}" }
        val nosi = listOf(opis)
        izhodna.entries.removeAll { zdaj - it.value.cas > IZHODNA_VELJAJO_MS }
        for ((i, oznaka) in oznake.withIndex()) izhodna[oznaka] = Izhodno(zdaj, false, { nosi }, i == stDelov - 1, oznake, zapis)
        omejiIzhodna()
        for (i in 0 until stDelov) {
            val seq = prvi + i
            val od = i * DOLZINA_DELA
            val kos = cistopis.copyOfRange(od, minOf(cistopis.size, od + DOLZINA_DELA))
            // Izjema pri sifriranju ali posiljanju je isto kot »ni slo«: zapisi se pospravijo, klicatelj izve.
            val poslano = try {
                val podatki = sifriraj(kljuc, predpona, seq, kos, aad(s.sessionId, s.smerVen, seq, m, i, stDelov))
                val tovor = "{\"session_id\":${niz(s.sessionId)},\"seq\":$seq,\"m\":$m,\"i\":$i,\"n\":$stDelov,\"data\":${niz(b64(podatki))}}"
                poslji("data.chunk", s.tujId, oznake[i], tovor)
            } catch (_: Throwable) { false }
            if (!poslano) {
                // Sporocilo ni slo (povezave s srediscem ni): klicatelj dobi false ali »ni poslano«. Zapisov o njem ne
                // pustimo - poznejsa potrditev ze poslanega kosa ali »seje ni« ga ne sme javiti se enkrat.
                for (oznaka in oznake) izhodna.remove(oznaka)
                pozabiZadnje(zapis)
                return false
            }
        }
        return true
    }

    /**
     * Iz zapisov »zadnja poslana« (za obvestilo »seje ni«) vzame sporocilo, katerega izid je klicatelj ze dobil (klice
     * se pod zaklepom).
     */
    private fun pozabiZadnje(zapis: Poslano?) {
        if (zapis == null) return
        for (seja in seje.values) seja.zadnja.removeAll { it === zapis }
    }

    private fun zapomniIzhodno(oznaka: String, dogovor: Boolean = false, sprejem: Boolean = true, notranja: () -> List<String>) {
        val zdaj = ura()
        izhodna.entries.removeAll { zdaj - it.value.cas > IZHODNA_VELJAJO_MS }
        izhodna[oznaka] = Izhodno(zdaj, dogovor, notranja, sprejem)
        omejiIzhodna()
    }

    /** Klicatelju javi, da sporocila s temi opisi do naprave niso prisla (klice se ZUNAJ zaklepa). */
    private fun zavrni(sporocila: List<String>, besedilo: String, koda: String) {
        val zavrnitev = obZavrnitvi ?: return
        for (n in sporocila) try { zavrnitev(n, besedilo, koda) } catch (_: Throwable) { }
    }

    // ------------------------------------------------------------------ prejem

    /**
     * Obdela prejeto data.offer / data.answer / data.chunk / data.error z namenom "link" in potrditev sredisca
     * (data.ack) za nasa sporocila prenosa. [posiljatelj] je polje `sender` (prazno pri potrditvi sredisca), [tovor]
     * polja `payload` (pri data.ack polja samega sporocila: ref_id, status, error, error_code).
     * True: sporocilo je bilo nase (klicatelj ga ne obravnava naprej); False: ni del zascite Linka. Nikoli ne vrze
     * izjeme: karkoli pride po omrezju, sme sporocilo kvecjemu zavreci, ne pa prekiniti povezave s srediscem.
     */
    fun prejmi(tip: String, posiljatelj: String, tovor: Polja): Boolean {
        pospravi()
        try {
            when (tip) {
                "data.ack" -> return prejmiPotrditev(posiljatelj, tovor)
                "data.offer" -> {
                    if (tovor.niz("purpose") != NAMEN) return false
                    prejmiPonudbo(tovor, posiljatelj)
                    return true
                }
                "data.answer" -> {
                    if (tovor.niz("purpose") != NAMEN) return false
                    prejmiOdgovor(tovor, posiljatelj)
                    return true
                }
                "data.chunk" -> {
                    val sid = tovor.niz("session_id") ?: ""
                    val s = synchronized(zaklep) {
                        val seja = seje[sid]
                        // Rok je potekel ali pa naprave ni vec v krogu: kot da seje ni.
                        if (seja != null && !veljaSe(seja)) { odstrani(seja); null } else seja
                    }
                    if (s == null) {
                        if (tovor.celo("m") == null) return false           // kos drugega prenosa, ne nase seje
                        javiNapako(posiljatelj, sid, "ni_seje", tovor.celo("seq") ?: -1L)
                        return true
                    }
                    prejmiKos(s, tovor, posiljatelj)
                    return true
                }
                "data.error" -> return prejmiNapako(tovor, posiljatelj)
                else -> return false
            }
        } catch (e: Napaka) {
            dnevnik("zavrnjeno ($tip od ${zakrij(posiljatelj)}): ${e.message}")
            return true
        } catch (e: Throwable) {
            // Nepricakovana oblika ali napaka v obdelavi: sporocilo zavrzemo, povezava ostane.
            dnevnik("sporocila ni bilo mogoce obdelati ($tip od ${zakrij(posiljatelj)}): ${e.javaClass.simpleName}")
            return true
        }
    }

    private fun prejmiPotrditev(posiljatelj: String, t: Polja): Boolean {
        val oznaka = t.niz("ref_id") ?: ""
        if (!oznaka.startsWith(PREDPONA_OZNAK) || posiljatelj.isNotBlank()) return false
        val stanje = t.niz("status") ?: ""
        val sprejeto = stanje == "accepted" || stanje == "queued"
        val notranja: List<String>
        val jeDogovor: Boolean
        val sprejem: Boolean
        synchronized(zaklep) {
            val vnos = izhodna.remove(oznaka) ?: return true
            notranja = vnos.notranja()
            jeDogovor = vnos.dogovor
            sprejem = vnos.sprejem
            if (!sprejeto || sprejem) for (druga in vnos.vsi) izhodna.remove(druga)      // drugi kosi: izid javimo enkrat
            if (!sprejeto) {
                pozabiZadnje(vnos.zapis)        // ze javljeno: poznejsi »seje ni« ga ne javi se enkrat
                val zdaj = ura()
                for (zavrnjen in dogovori.entries.filter { PREDPONA_OZNAK + it.value.sessionId.take(10) == oznaka }) {
                    dogovori.remove(zavrnjen.key)
                    zavrnjen.value.vrsta.clear()            // opisi so ze v `notranja`; cistopisi se sprostijo
                    zabeleziNeuspeh(zavrnjen.key, zdaj)
                }
                posodobiCakanje()
            }
        }
        if (sprejeto) {
            // Sprejeta ponudba se ni sprejeto sporocilo (to se caka na odgovor naprave); pri sporocilu v vec delih je
            // sprejeto sele z zadnjim kosom.
            val klic = obSprejemu
            if (stanje == "accepted" && !jeDogovor && sprejem && klic != null)
                for (n in notranja) try { klic(n) } catch (_: Throwable) { }
            return true
        }
        // Zavrnitev SREDISCA pride klicatelju s kodo sredisca - razen kadar bi ta pomenila nekaj nasega (»ni bilo
        // poslano«, »seje ni« ...): sredisce, ki kos dostavi in ga nato »zavrne« s tako kodo, klicatelja ne sme
        // prepricati, da ukaz ni sel.
        var koda = t.niz("error_code") ?: ""
        if (koda in LASTNE_KODE || koda.length > NAJVEC_KODE_SREDISCA) koda = KODA_ZAVRNITVE_SREDISCA     // predolge ne podajamo naprej
        zavrni(notranja, (t.niz("error") ?: "").take(NAJVEC_BESEDILA_NAPAKE), koda)
        return true
    }

    private fun prejmiPonudbo(t: Polja, posiljatelj: String) {
        val sessionId = t.niz("session_id") ?: ""
        val odId = t.niz("from") ?: ""
        val doId = t.niz("to") ?: ""
        val nonce = t.niz("nonce") ?: ""
        val epk = t.niz("epk") ?: ""
        val sig = t.niz("sig") ?: ""
        val moj = mojId()
        if (sessionId.isEmpty() || odId.isEmpty() || nonce.isEmpty() || epk.isEmpty() || sig.isEmpty() || t.celo("v") != RAZLICICA.toLong())
            throw Napaka("ponudbi manjka polje ali ima drugo razlicico")
        if (!(veljavnaOznaka(odId) && veljavnaOznakaSeje(sessionId) && veljavenB64(nonce, 64) && veljavenB64(epk, 400) && veljavenB64(sig, 400)))
            throw Napaka("ponudba ima polje, ki ne sme v podpisane bajte (oblika ali dolzina)")
        if (doId != moj || !veljavnaOznaka(moj)) throw Napaka("ponudba ni namenjena temu programu")
        if (posiljatelj.isNotEmpty() && posiljatelj != odId) throw Napaka("posiljatelj sredisca se ne ujema s podpisano ponudbo")
        synchronized(zaklep) {
            // Ponovljena ponudba (sredisce jo lahko ponavlja v nedogled) je zavrnjena, PREDEN karkoli preverimo ali
            // podpisemo. Ista preverba je spodaj se enkrat - za ponudbi, ki prideta hkrati.
            if (seje.containsKey(sessionId) || dogovori.values.any { it.sessionId == sessionId })
                throw Napaka("seja s to oznako ze obstaja ali jo ravno dogovarjamo")
        }
        val kljuc = kljucZa(odId)?.takeIf { it.isNotBlank() }
        if (kljuc == null) {
            // Posiljatelj naj izve takoj (sicer njegov ukaz tiho caka na iztek casa): dogovor z nami ne more uspeti.
            javiNapako(posiljatelj.ifEmpty { odId }, sessionId, "ni_kljuca", null)
            throw Napaka("naprave ni v krogu zaupanja")
        }
        if (!preveri(kljuc, podatkiPonudbe(sessionId, odId, doId, nonce, epk), sig))
            throw Napaka("podpis ponudbe se ne ujema s kljucem naprave v krogu")
        val jedro = idIzKljuca(kljuc)
        synchronized(zaklep) {
            // Odgovor je podpis s kljucem naprave: za eno napravo jih je v oknu omejeno stevilo (PO preverbi podpisa -
            // mejo naprave tako porabijo samo njene prave ponudbe, ne ponaredki v njenem imenu).
            if (!smePodpisati(jedro, ura(), vloga = 'b')) throw Napaka("prevec dogovorov s to napravo v kratkem casu")
        }
        val (zasebni, mojEpk) = novPar()
        val mojNonce = b64(nakljucno(16))
        val podpis = try { podpisi(podatkiOdgovora(sessionId, moj, odId, nonce, mojNonce, epk, mojEpk)) } catch (_: Throwable) { "" }
        if (podpis.isBlank()) throw Napaka("odgovora ni bilo mogoce podpisati")
        val kljuci = izpelji(ecdh(zasebni, epk), sessionId, nonce, mojNonce, odId, moj)
        val seja = Seja(sessionId, odId, 'b', kljuci, jedro, ura(), potrjena = false)
        val tovor = "{\"session_id\":${niz(sessionId)},\"purpose\":\"$NAMEN\",\"v\":$RAZLICICA,\"from\":${niz(moj)},\"to\":${niz(odId)}," +
            "\"offer_nonce\":${niz(nonce)},\"nonce\":${niz(mojNonce)},\"epk\":${niz(mojEpk)},\"ts\":${System.currentTimeMillis() / 1000},\"sig\":${niz(podpis)}}"
        synchronized(zaklep) {
            // Oznako seje izbere posiljatelj ponudbe in je srediscu vidna: ne sme se ujeti z nobeno naso sejo ne z
            // dogovorom, ki ga ravno cakamo (sicer bi odgovor nanj sejo pod to oznako zamenjal).
            if (seje.containsKey(sessionId) || dogovori.values.any { it.sessionId == sessionId })
                throw Napaka("seja s to oznako ze obstaja ali jo ravno dogovarjamo")
            omejiSeje(seja.jedro)
            seje[sessionId] = seja
            // Odgovor gre ven PRED kazalcem posiljanja: druga nit po tej seji ne sme poslati nicesar, dokler odgovor ni
            // na poti (druga stran bi kos dobila pred odgovorom in javila, da seje ne pozna).
            poslji("data.answer", odId, PREDPONA_OZNAK + sessionId.take(10) + "-o", tovor)
            // Po tej seji tudi posiljamo, razen ce ravno cakamo na odgovor na SVOJO ponudbo (takrat velja nasa).
            if (!dogovori.containsKey(odId)) za[odId] = sessionId
            konecPremora(odId)
        }
        javiSejo(seja)
    }

    private fun prejmiOdgovor(t: Polja, posiljatelj: String) {
        val sessionId = t.niz("session_id") ?: ""
        val odId = t.niz("from") ?: ""
        val doId = t.niz("to") ?: ""
        val nonce = t.niz("nonce") ?: ""
        val epk = t.niz("epk") ?: ""
        val sig = t.niz("sig") ?: ""
        val pozna = ArrayList<String>()
        val cakan = synchronized(zaklep) {
            val cakajoc = dogovori[odId]?.takeIf { it.sessionId == sessionId } ?: throw Napaka("odgovor ne pripada dogovoru, ki ga cakamo")
            if (ura() - cakajoc.zacet > DOGOVOR_VELJA_MS) { dogovori.remove(odId); opustiDogovor(cakajoc, pozna); null } else cakajoc
        }
        if (cakan == null) {
            zavrni(pozna, BESEDILO_CAS, "cas")        // zunaj zaklepa
            throw Napaka("odgovor je prisel prepozno")
        }
        val d: Dogovor = cakan
        val moj = mojId()
        if (doId != moj || !(veljavnaOznaka(odId) && veljavenB64(nonce, 64) && veljavenB64(epk, 400) && veljavenB64(sig, 400)))
            throw Napaka("odgovoru manjka polje, polje nima prave oblike ali pa odgovor ni namenjen temu programu")
        if (posiljatelj.isNotEmpty() && posiljatelj != odId) throw Napaka("posiljatelj sredisca se ne ujema s podpisanim odgovorom")
        if (t.niz("offer_nonce") != d.nonce) throw Napaka("odgovor ne veze nase ponudbe")
        val kljuc = kljucZa(odId)?.takeIf { it.isNotBlank() } ?: throw Napaka("naprave ni v krogu zaupanja")
        if (!preveri(kljuc, podatkiOdgovora(sessionId, odId, moj, d.nonce, nonce, d.epk, epk), sig))
            throw Napaka("podpis odgovora se ne ujema s kljucem naprave v krogu")
        val kljuci = izpelji(ecdh(d.zasebni, epk), sessionId, d.nonce, nonce, moj, odId)
        val seja = Seja(sessionId, odId, 'a', kljuci, idIzKljuca(kljuc), ura(), potrjena = true)
        val zastarela = ArrayList<String>()
        val neposlana = ArrayList<String>()
        synchronized(zaklep) {
            if (dogovori[odId] !== d) throw Napaka("dogovor se je medtem zamenjal")
            if (seje.containsKey(sessionId)) throw Napaka("seja s to oznako ze obstaja")
            dogovori.remove(odId)
            izhodna.remove(PREDPONA_OZNAK + sessionId.take(10))
            omejiSeje(seja.jedro)
            seje[sessionId] = seja
            za[odId] = sessionId
            konecPremora(odId)
            val vrsta = d.vrsta.toList()
            d.vrsta.clear()
            posodobiCakanje()
            val zdaj = ura()
            for ((cas, opis, cistopis) in vrsta) {
                // Sporocilo, ki je na dogovor cakalo predolgo (sredisce je odgovor zadrzalo), ne gre vec: ukaz, ki bi se
                // izvedel z zamudo, je slabsi od ukaza, ki se ne izvede.
                if (zdaj - cas > V_VRSTI_VELJA_MS) { zastarela.add(opis); continue }
                // Eno sporocilo ne sme vzeti s seboj ostalih (vrsta je ze prazna). Ce ne gre (povezave s srediscem ni
                // vec), mora klicatelj izvedeti (prej je cakal na iztek casa).
                val poslano = try { posljiPoSeji(seja, opis, cistopis) } catch (_: Throwable) { false }
                if (!poslano) neposlana.add(opis)
            }
        }
        zavrni(zastarela, BESEDILO_CAS, "cas")
        zavrni(neposlana, BESEDILO_NI_POSLANO, "ni_poslano")
        javiSejo(seja)
    }

    private fun javiSejo(seja: Seja) { try { obSeji?.invoke(seja.jedro) } catch (_: Throwable) { } }

    /**
     * Naredi prostor za novo sejo naprave z jedrom [jedro]. Najprej izpadejo seje, ki jim je potekel rok. Potem velja
     * meja NA NAPRAVO (vsi njeni programi skupaj): naprava, ki odpira sejo za sejo (in vsako potrdi), izrine svoje seje -
     * ne sej drugih naprav (drugi neodvisni pregled, 7. 10. 2026). Nazadnje skupna meja.
     *
     * Vrstni red pri meji na napravo: najprej NEPOTRJENE seje (nastanejo iz prejetih ponudb, tudi ponovljenih - z njimi
     * nihce ne sme izriniti seje, ki res deluje), potem NADOMESCENE (starejse od novejse potrjene seje istega programa in
     * iste vloge: program se je znova zagnal in se dogovoril znova), sele nato zive, najstarejsa prva. Tako ziva, redko
     * rabljena seja enega programa ne izpade zato, ker se drug program iste naprave pogosto zaganja (tretji pregled).
     */
    private fun omejiSeje(jedro: String) {
        val zdaj = ura()
        seje.values.filter { zdaj - it.nastala > SEJA_VELJA_MS }.forEach { odstrani(it) }
        val njene = if (jedro.isNotEmpty()) seje.values.filter { it.jedro == jedro }.toMutableList() else ArrayList()
        if (njene.size >= meje.sejNaNapravo) {
            val najnovejsa = HashMap<Pair<String, Char>, Long>()
            for (s in njene) if (s.potrjena) {
                val par = s.tujId to s.vloga
                najnovejsa[par] = maxOf(najnovejsa[par] ?: s.nastala, s.nastala)
            }
            fun red(s: Seja): Int = if (!s.potrjena) 0 else if (s.nastala < (najnovejsa[s.tujId to s.vloga] ?: s.nastala)) 1 else 2
            njene.sortWith(compareBy<Seja>({ red(it) }, { it.nastala }))
            while (njene.size >= meje.sejNaNapravo) odstrani(njene.removeAt(0))
        }
        while (seje.size >= meje.sej) {
            val kandidati = seje.values.filter { !it.potrjena }.ifEmpty { seje.values.toList() }
            odstrani(kandidati.minByOrNull { it.nastala } ?: break)
        }
    }

    /**
     * Pomnilnik za sporocila v vec delih je omejen na napravo in skupaj (drugi neodvisni pregled: ena naprava je z
     * nedokoncanimi sporocili v vec sejah lahko drzala vec kot gigabajt). Vsako nedokoncano sporocilo steje s polno
     * napovedano velikostjo. Ko za novo ([n] delov) zmanjka prostora, izpadejo najstarejsa nedokoncana - najprej ISTE
     * naprave, potem katerakoli. Ob tem se zavrzejo zastarela nedokoncana sporocila vseh sej (klice se pod zaklepom).
     */
    private fun narediProstorZaDele(seja: Seja, n: Int, zdaj: Long) {
        class Vnos(val cas: Long, val s: Seja, val m: Long, val rezervirano: Long)
        val vsa = ArrayList<Vnos>()
        for (s in seje.values) {
            s.deli.entries.removeAll { zdaj - it.value.first > NEDOKONCANA_VELJAJO_MS }
            for ((m, v) in s.deli) vsa.add(Vnos(v.first, s, m, v.second.toLong() * DOLZINA_DELA))
        }
        vsa.sortBy { it.cas }
        val novo = n.toLong() * DOLZINA_DELA
        var njeno = novo
        var skupaj = novo
        for (v in vsa) { skupaj += v.rezervirano; if (v.s.jedro == seja.jedro) njeno += v.rezervirano }
        val po = vsa.iterator()
        while (njeno > meje.rezerviranoNaNapravo && po.hasNext()) {
            val v = po.next()
            if (v.s.jedro != seja.jedro) continue
            v.s.deli.remove(v.m)
            po.remove()
            njeno -= v.rezervirano
            skupaj -= v.rezervirano
        }
        while (skupaj > meje.rezervirano && vsa.isNotEmpty()) {
            val v = vsa.removeAt(0)
            v.s.deli.remove(v.m)
            skupaj -= v.rezervirano
        }
    }

    private fun prejmiKos(s: Seja, t: Polja, posiljatelj: String) {
        if (posiljatelj.isNotEmpty() && posiljatelj != s.tujId) throw Napaka("kos je prisel od druge naprave kot seja")
        val seq = t.celo("seq") ?: -1L
        val m = t.celo("m") ?: -1L
        val iDolg = t.celo("i") ?: -1L
        val nDolg = t.celo("n") ?: -1L
        if (seq < 0 || nDolg < 1 || nDolg > NAJVEC_DELOV || iDolg < 0 || iDolg >= nDolg || m < 0) throw Napaka("neveljavna delitev sporocila")
        val i = iDolg.toInt()
        val n = nDolg.toInt()
        val zapis = t.niz("data") ?: ""
        if (zapis.length > NAJVEC_ZAPISA_KOSA) throw Napaka("kos je daljsi od dogovorjenega")       // predolgega niti ne dekodiramo
        val sifropis = izB64(zapis, "data")
        if (sifropis.size > DOLZINA_DELA + 16) throw Napaka("kos je daljsi od dogovorjenega")
        val (kljuc, predpona) = s.kljuci.smer(s.smerNoter)
        var cistopis: ByteArray? = null
        var neujemanje = false
        synchronized(zaklep) {
            if (seje[s.sessionId] !== s) throw Napaka("seja je bila medtem zavrzena")
            if (seq <= s.zadnjiNoter) throw Napaka("stevec se ponavlja ali gre nazaj")
            val kos: ByteArray? = try {
                desifriraj(kljuc, predpona, seq, sifropis, aad(s.sessionId, s.smerNoter, seq, m, i, n))
            } catch (e: Napaka) {
                if (s.potrjena) throw e         // seja deluje; pokvarjen ali podtaknjen kos zavrzemo, seja ostane
                // Seja iz ponudbe, ki je druga stran se ni potrdila, in ze prvi kos se ne desifrira: kljuca se ne
                // ujemata (ponudbo je kdo ponovil ali pa ima druga stran pod to oznako drugo sejo). Zavrzemo jo in to
                // povemo, da se druga stran dogovori znova - sicer bi obe molce zavracali vse.
                odstrani(s)
                neujemanje = true
                null
            }
            if (kos != null) {
                s.zadnjiNoter = seq
                s.potrjena = true
                val zdaj = ura()
                s.deli.entries.removeAll { zdaj - it.value.first > NEDOKONCANA_VELJAJO_MS }
                if (n == 1) {
                    cistopis = kos
                } else {
                    if (!s.deli.containsKey(m)) {
                        while (s.deli.size >= meje.nedokoncanih)
                            s.deli.remove(s.deli.entries.minByOrNull { it.value.first }?.key ?: break)
                        narediProstorZaDele(s, n, zdaj)
                    }
                    val vnos = s.deli.getOrPut(m) { Triple(zdaj, n, HashMap()) }
                    if (vnos.second != n) { s.deli.remove(m); throw Napaka("deli sporocila se ne ujemajo") }
                    vnos.third[i] = kos
                    if (vnos.third.size >= n) {
                        s.deli.remove(m)
                        val vse = ByteArrayOutputStream()
                        for (j in 0 until n) vse.write(vnos.third[j] ?: throw Napaka("deli sporocila se ne ujemajo"))
                        cistopis = vse.toByteArray()
                    }
                }
            }
        }
        if (neujemanje) {
            javiNapako(posiljatelj.ifEmpty { s.tujId }, s.sessionId, "ni_seje", seq)
            throw Napaka("nepotrjena seja se s posiljateljevo ne ujema - zavrzena")
        }
        val celo = cistopis ?: return           // sporocilo v vec delih se ni celo
        val notranje = utf8Strogo(celo) ?: throw Napaka("notranje sporocilo ni veljaven UTF-8")
        if (!notranje.trimStart().startsWith("{")) throw Napaka("notranje sporocilo ni JSON")
        // Tudi notranje sporocilo gre potem v org.json, ki gnezdenja ne omejuje: poslje ga naprava s kljucem v krogu, ne
        // nujno z nasim programom (cetrti neodvisni pregled, 7. 10. 2026). Glej JsonLahki.pregloboko.
        if (JsonLahki.pregloboko(notranje)) throw Napaka("notranje sporocilo je pregloboko gnezdeno ali ni strogi JSON")
        obSporocilu(notranje, s.tujId, s.jedro)
    }

    private fun javiNapako(cilj: String, sessionId: String, koda: String, seq: Long?) {
        if (cilj.isBlank() || sessionId.isBlank()) return
        val stevec = if (seq != null) ",\"seq\":$seq" else ""
        poslji("data.error", cilj, PREDPONA_OZNAK + "err-" + sessionId.take(8),
            "{\"session_id\":${niz(sessionId.take(64))},\"code\":${niz(koda)}$stevec}")
    }

    /**
     * Druga naprava seje ne pozna vec (»ni_seje«) ali pa nasega kljuca nima v krogu (»ni_kljuca«). Obvestilo NI
     * podpisano - lahko ga je poslalo tudi sredisce. Zato z njim dosezemo samo to, kar sredisce doseze ze s tem, da
     * sporocila zavrze: sejo (ali cakajoci dogovor) opustimo in klicatelju javimo, da sveza sporocila niso prisla.
     * Sporocil NE posljemo se enkrat - prejemnik jih je morda ze izvedel, in ponovitev bi jih izvedla znova.
     */
    private fun prejmiNapako(t: Polja, posiljatelj: String): Boolean {
        val sid = t.niz("session_id") ?: ""
        val koda = t.niz("code") ?: ""
        var zavrnjena: List<String> = emptyList()
        var besedilo = ""
        synchronized(zaklep) {
            val s = seje[sid]
            if (s == null) {
                val d = dogovori.values.firstOrNull { it.sessionId == sid } ?: return false
                if ((posiljatelj.isNotEmpty() && posiljatelj != d.tujId) || koda != "ni_kljuca") return true
                if (dogovori[d.tujId] === d) dogovori.remove(d.tujId)
                val opisi = ArrayList<String>()
                opustiDogovor(d, opisi)
                zavrnjena = opisi
                zabeleziNeuspeh(d.tujId, ura())
                besedilo = "Naprava te naprave nima v svojem krogu zaupanja."
            } else {
                if ((posiljatelj.isNotEmpty() && posiljatelj != s.tujId) || koda != "ni_seje") return true
                val od = t.celo("seq") ?: -1L
                val zdaj = ura()
                // Sporocilo v vec delih steje po ZADNJEM kosu: ce prejemnik sejo izgubi sredi sporocila, ga ni dobil.
                val javljena = s.zadnja.filter { od >= 0 && it.zadnji >= od && zdaj - it.cas <= NEPOTRJENA_VELJAJO_MS }
                zavrnjena = javljena.map { it.opis }
                // Izid teh sporocil je s tem javljen: potrditve sredisca za njihove kose, ki pridejo pozneje, jih ne smejo
                // javiti se enkrat - ne kot sprejeta ne kot zavrnjena (sesti pregled, 7. 10. 2026).
                for (z in javljena) for (seq in z.prvi..z.zadnji) izhodna.remove("$PREDPONA_OZNAK${s.sessionId.take(8)}-$seq")
                odstrani(s)
                besedilo = "Naprava je sejo zaščite izgubila. Poskusi znova."
            }
        }
        zavrni(zavrnjena, besedilo, koda)
        return true
    }

    companion object {
        const val NAMEN = "link"
        const val RAZLICICA = 2
        /** Zmoznost, ki jo naprava prijavi srediscu: zna zascito od naprave do naprave. */
        const val ZMOZNOST = "e2e1"
        /**
         * Sporocila, ki med napravama z zascito nikoli ne gredo nezascitena: ukazi, njihovi odgovori in vse, kar na
         * napravi samo odpre stran ali predvajanje (stran, nadzor predvajanja, »nadaljuj na napravi«). `cast.media` danes
         * ne obdela noben sprejemnik (sredisca ga se usmerjajo); je v naboru, da bo zasciten, ce ga kdaj kdo bo.
         */
        val ZASCITENI_TIPI = setOf("control.command", "control.result", "cast.url", "cast.media", "cast.control", "handoff.request")
        val TIPI_PRENOSA = setOf("data.offer", "data.answer", "data.chunk", "data.error")
        const val PREDPONA_OZNAK = "e2e-"

        /** Cistopis enega dela: z base64 in ovojnico ~64 KiB (sredisce na Androidu sprejme sporocilo do 256 KiB, vrsta 512 KiB). */
        const val DOLZINA_DELA = 48 * 1024
        const val NAJVEC_DELOV = 24
        /** Po tem casu brez odgovora se ob naslednjem sporocilu dogovor zacne znova. */
        const val DOGOVOR_CAKA_MS = 8_000L
        /** Po tem casu cakajocega dogovora ni vec (pozen odgovor ne ustvari seje). */
        const val DOGOVOR_VELJA_MS = 60_000L
        /** Sporocilo, ki je na dogovor cakalo dlje, ne gre vec: klicatelj je ze javil napako. */
        const val V_VRSTI_VELJA_MS = DOGOVOR_CAKA_MS
        const val NAJVEC_V_VRSTI = 64
        const val NAJVEC_DOGOVOROV = 64
        const val SEJA_VELJA_MS = 12 * 3600_000L
        const val NAJVEC_SEJ = 256
        /** Sej z ENO napravo (jedrom, vsi njeni programi): naprava izrine le svoje seje. */
        const val NAJVEC_SEJ_NA_NAPRAVO = 16
        /** Zadnja poslana sporocila: po »ni_seje« jih javimo klicatelju kot zavrnjena. */
        const val HRANI_ZADNJIH = 16
        /** Starejsih ne javljamo: klicatelj je zanje ze dobil odgovor ali iztek casa. */
        const val NEPOTRJENA_VELJAJO_MS = 5_000L
        const val NEDOKONCANA_VELJAJO_MS = 30_000L
        /** Sporocil v vec delih, ki jih ena seja sestavlja hkrati. */
        const val NAJVEC_NEDOKONCANIH = 4
        /** Najdaljsa oznaka naprave v dogovoru. */
        const val NAJVEC_OZNAKE = 128
        /** Pomnilnik za sporocila v vec delih, ki se sestavljajo (vsako steje s polno napovedano velikostjo): ena naprava. */
        const val NAJVEC_REZERVIRANO_NA_NAPRAVO_B = 8L * 1024 * 1024
        /** ... in vse naprave skupaj. */
        const val NAJVEC_REZERVIRANO_B = 32L * 1024 * 1024
        const val BESEDILO_CAS = "Naprava ni odgovorila pravočasno. Poskusi znova."
        const val BESEDILO_NI_POSLANO = "Ukaza ni bilo mogoče poslati."
        /** Za koliko zadnjih sporocil prenosa (vsak kos posebej) vemo, katero notranje sporocilo nosijo. */
        const val NAJVEC_IZHODNIH = 1024
        /** Potrditev sredisca pride v milisekundah; po tem casu zapis o poslanem zavrzemo. */
        const val IZHODNA_VELJAJO_MS = 30_000L
        /**
         * Podpisi s kljucem naprave za ENO napravo (jedro, vsi njeni programi) v enem oknu - posebej za ponudbe, ki jih
         * zacnemo mi, in posebej za odgovore na prejete ponudbe. Vsak dogovor je podpis v varni shrambi sistema: brez
         * meje je seznanjena naprava s ponudbo za ponudbo - ali sredisce s ponavljanjem ene same ponudbe - to napravo
         * prisilila v neomejeno podpisovanje (cetrti neodvisni pregled, 7. 10. 2026; izmerjeno: 500 ponudb = 500
         * podpisov). Meja je na napravo: kdor jo izcrpa, zadrzi samo dogovore s seboj. Meji sta loceni, da naprava s
         * svojimi ponudbami ne zapre nasih dogovorov z njo, in veljata za PROGRAM, ne za en primerek tega razreda
         * (glej podpisiProgramov; peti pregled).
         */
        const val NAJVEC_PODPISOV_NA_NAPRAVO = 32
        const val OKNO_PODPISOV_MS = 60_000L
        /** Za koliko naprav hranimo case zadnjih podpisov. */
        const val NAJVEC_ZAPISOV_PODPISOV = 1024
        /**
         * Casi podpisov s kljucem naprave: "<nas id>\n<vloga>\n<jedro druge naprave>" -> casi v zadnjem oknu. Vloga 'a' =
         * ponudbe, ki jih zacnemo mi, 'b' = odgovori na prejete ponudbe. Zapis je skupen programu: ob novi povezavi s
         * srediscem nastane nov primerek, in s prekinjanjem povezave bi sredisce mejo sicer ponastavljalo.
         */
        private val podpisiProgramov = LinkedHashMap<String, ArrayList<Long>>()
        /**
         * Po dogovoru, ki ni uspel (ponudba ni sla, sredisce jo je zavrnilo, naprava nas nima v krogu), s to oznako toliko
         * casa ne zacnemo novega. Tipka daljinca ob napravi, ki je ni v Linku, je sicer z vsakim ukazom porabila en podpis
         * in po 32 ukazih zaprla mejo podpisov se za minuto po tem, ko se je naprava vrnila (peti pregled, 7. 10. 2026).
         */
        const val NEUSPEL_DOGOVOR_CAKA_MS = 3_000L
        /**
         * Kdaj dogovor nazadnje ni uspel: "<nas id>\n<id druge naprave>" -> cas. Iz istega razloga kot podpisiProgramov
         * skupno programu: sredisce, ki ponudbo zavrne in prekine povezavo, bi z vsakim novim primerkom sicer dobilo nov
         * podpis brez premora (sesti pregled, 7. 10. 2026).
         */
        private val neuspeliProgramov = LinkedHashMap<String, Long>()
        /** Za koliko oznak si zapomnimo zadnji neuspeli dogovor. */
        const val NAJVEC_NEUSPELIH = 256
        /** Daljsa koda napake sredisca dobi splosno kodo. */
        const val NAJVEC_KODE_SREDISCA = 64
        /** Sporocila, ki cakajo na dogovor, drzijo pomnilnik: meja v bajtih za eno napravo (jedro) ... */
        const val NAJVEC_CAKAJOCIH_NA_NAPRAVO_B = 4L * 1024 * 1024
        /** ... in za vse skupaj. */
        const val NAJVEC_CAKAJOCIH_B = 16L * 1024 * 1024
        /** Najdaljsi opis sporocila, ki si ga zapomnimo za potrditve (glej poslji). */
        const val NAJVEC_OPISA = 512
        /** Zapis enega kosa v base64: najvec toliko znakov (del cistopisa + znacka AES-GCM). Daljsega ne dekodiramo. */
        const val NAJVEC_ZAPISA_KOSA = 4 * ((DOLZINA_DELA + 16 + 2) / 3)
        /** Koda, s katero klicatelj izve za zavrnitev SREDISCA, kadar bi njegova koda pomenila nekaj nasega (prejmiPotrditev). */
        const val KODA_ZAVRNITVE_SREDISCA = "zavrnjeno"
        val LASTNE_KODE = setOf("cas", "ni_poslano", "ni_seje", "ni_kljuca", "zascita", KODA_ZAVRNITVE_SREDISCA)
        /** Besedila napake sredisca ne podajamo naprej v poljubni dolzini. */
        const val NAJVEC_BESEDILA_NAPAKE = 200

        private val nakljucje = SecureRandom()

        private fun nakljucno(n: Int): ByteArray = ByteArray(n).also { nakljucje.nextBytes(it) }

        fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

        /** Strogo: standardni base64 s polnilom (dekodirnik Jave bi sprejel tudi zapis brez polnila - racunalnik ga ne). */
        fun izB64(s: String, kaj: String): ByteArray {
            if (!veljavenB64(s, Int.MAX_VALUE)) throw Napaka("neveljaven zapis ($kaj)")
            return try { Base64.getDecoder().decode(s) } catch (_: IllegalArgumentException) { throw Napaka("neveljaven zapis ($kaj)") }
        }

        /**
         * Ali sme oznaka naprave v podpisane bajte dogovora: 1-128 vidnih znakov ASCII (brez presledka, krmilnih znakov
         * in locil vrstic). Vse oznake, ki jih Safeer izdela sam, so take. Glej »Oblika polj« v opisu razreda.
         */
        fun veljavnaOznaka(s: String): Boolean = s.isNotEmpty() && s.length <= NAJVEC_OZNAKE && s.all { it in '!'..'~' }

        fun veljavnaOznakaSeje(s: String): Boolean =
            s.isNotEmpty() && s.length <= 64 && s.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }

        /** Standardni base64 s polnilom in nicimer drugim (brez presledkov in prelomov vrstic). */
        fun veljavenB64(s: String, najvec: Int): Boolean {
            if (s.isEmpty() || s.length > najvec || s.length % 4 != 0) return false
            val jedro = s.trimEnd('=')
            return jedro.isNotEmpty() && s.length - jedro.length <= 2 &&
                jedro.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' }
        }

        /** Besedilo iz bajtov UTF-8 ali null, ce bajti niso veljaven UTF-8 (brez tihega nadomescanja znakov). */
        fun utf8Strogo(bajti: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bajti)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { null }

        /** Enkratni par P-256: (zasebni kljuc, javni kljuc kot base64 SPKI DER). */
        fun novPar(): Pair<PrivateKey, String> {
            val g = KeyPairGenerator.getInstance("EC")
            g.initialize(ECGenParameterSpec("secp256r1"), nakljucje)
            val par = g.generateKeyPair()
            return par.private to b64(par.public.encoded)
        }

        /** Skupna skrivnost (koordinata x, 32 bajtov) med nasim enkratnim zasebnim in tujim enkratnim javnim kljucem. */
        fun ecdh(zasebni: PrivateKey, tujJavniB64: String): ByteArray {
            try {
                val tuj = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(izB64(tujJavniB64, "epk")))
                val nasa = (zasebni as java.security.interfaces.ECKey).params
                if (tuj !is ECPublicKey || tuj.params.curve != nasa.curve || tuj.params.generator != nasa.generator ||
                    tuj.params.order != nasa.order) throw Napaka("enkratni kljuc ni P-256")
                val dogovor = KeyAgreement.getInstance("ECDH")
                dogovor.init(zasebni)
                dogovor.doPhase(tuj, true)
                return dogovor.generateSecret()
            } catch (e: Napaka) {
                throw e
            } catch (_: Throwable) {
                throw Napaka("ECDH ni uspel")
            }
        }

        /** HKDF-SHA256 (RFC 5869). */
        fun hkdf(ikm: ByteArray, sol: ByteArray, info: ByteArray, dolzina: Int): ByteArray {
            fun hmac(kljuc: ByteArray, podatki: ByteArray): ByteArray =
                Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(kljuc, "HmacSHA256")) }.doFinal(podatki)
            val prk = hmac(if (sol.isEmpty()) ByteArray(32) else sol, ikm)
            val izhod = ByteArrayOutputStream()
            var blok = ByteArray(0)
            var i = 1
            while (izhod.size() < dolzina) {
                blok = hmac(prk, blok + info + byteArrayOf(i.toByte()))
                izhod.write(blok)
                i++
            }
            return izhod.toByteArray().copyOf(dolzina)
        }

        fun izpelji(skrivnost: ByteArray, sessionId: String, nonceA: String, nonceB: String, aId: String, bId: String): Kljuci {
            val info = "safeer-link-e2e-v1\n".toByteArray(Charsets.UTF_8) + izB64(nonceA, "nonce") + izB64(nonceB, "nonce") +
                "\n$aId\n$bId".toByteArray(Charsets.UTF_8)
            val sol = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray(Charsets.UTF_8))
            val g = hkdf(skrivnost, sol, info, 72)
            return Kljuci(g.copyOfRange(0, 32), g.copyOfRange(32, 36), g.copyOfRange(36, 68), g.copyOfRange(68, 72))
        }

        fun podatkiPonudbe(sessionId: String, odId: String, doId: String, nonce: String, epk: String): ByteArray =
            "safeer-link-e2e-offer-v1\n$sessionId\n$odId\n$doId\n$nonce\n$epk".toByteArray(Charsets.UTF_8)

        fun podatkiOdgovora(sessionId: String, odId: String, doId: String, noncePonudbe: String, nonce: String, epkA: String, epkB: String): ByteArray =
            "safeer-link-e2e-answer-v1\n$sessionId\n$odId\n$doId\n$noncePonudbe\n$nonce\n$epkA\n$epkB".toByteArray(Charsets.UTF_8)

        fun aad(sessionId: String, smer: String, seq: Long, m: Long, i: Int, n: Int): ByteArray =
            "safeer-link-e2e-msg-v1\n$sessionId\n$smer\n$seq\n$m\n$i\n$n".toByteArray(Charsets.UTF_8)

        private fun nonce(predpona: ByteArray, seq: Long): ByteArray {
            if (seq < 0) throw Napaka("neveljaven stevec")
            return predpona + java.nio.ByteBuffer.allocate(8).putLong(seq).array()
        }

        fun sifriraj(kljuc: ByteArray, predpona: ByteArray, seq: Long, cistopis: ByteArray, dodatno: ByteArray): ByteArray {
            val sifra = Cipher.getInstance("AES/GCM/NoPadding")
            sifra.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kljuc, "AES"), GCMParameterSpec(128, nonce(predpona, seq)))
            sifra.updateAAD(dodatno)
            return sifra.doFinal(cistopis)
        }

        fun desifriraj(kljuc: ByteArray, predpona: ByteArray, seq: Long, sifropis: ByteArray, dodatno: ByteArray): ByteArray {
            try {
                val sifra = Cipher.getInstance("AES/GCM/NoPadding")
                sifra.init(Cipher.DECRYPT_MODE, SecretKeySpec(kljuc, "AES"), GCMParameterSpec(128, nonce(predpona, seq)))
                sifra.updateAAD(dodatno)
                return sifra.doFinal(sifropis)
            } catch (e: Napaka) {
                throw e
            } catch (_: Throwable) {
                throw Napaka("sporocilo se ni desifriralo (pokvarjeno ali podtaknjeno)")
            }
        }

        /** Niz kot vrednost JSON (z narekovaji). */
        fun niz(s: String): String {
            val b = StringBuilder(s.length + 2).append('"')
            for (c in s) when {
                c == '"' -> b.append("\\\"")
                c == '\\' -> b.append("\\\\")
                c == '\n' -> b.append("\\n")
                c == '\r' -> b.append("\\r")
                c == '\t' -> b.append("\\t")
                c < ' ' -> b.append("\\u%04x".format(c.code))
                else -> b.append(c)
            }
            return b.append('"').toString()
        }

        /** Oznaka naprave za dnevnik: brez sredine (dnevniki se kopirajo v porocila). */
        fun zakrij(id: String): String {
            val d = if (id.length < 12) id else id.take(2) + "..." + id.drop(14)
            // Oznako pise sredisce: v dnevnik gre samo kot vidni ASCII in omejene dolzine.
            return buildString { for (c in d.take(60)) if (c in ' '..'~') append(c) else append("\\u%04x".format(c.code)) }
        }
    }
}
