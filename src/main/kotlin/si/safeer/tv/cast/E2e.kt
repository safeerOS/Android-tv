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
     * nima, dogovor je trajal predolgo): (notranje sporocilo kot JSON, besedilo, koda).
     */
    private val obZavrnitvi: ((notranjeJson: String, napaka: String, koda: String) -> Unit)? = null,
    /** Sredisce je sporocilo sprejelo v posredovanje ciljni napravi: (notranje sporocilo kot JSON). */
    private val obSprejemu: ((notranjeJson: String) -> Unit)? = null,
    private val ura: () -> Long = { System.nanoTime() / 1_000_000L },
    private val dnevnik: (String) -> Unit = {},
) {

    class Napaka(sporocilo: String) : Exception(sporocilo)

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
        val zadnja = ArrayList<Triple<Long, Long, String>>()                 // (prvi seq, cas, notranje sporocilo)
        val deli = HashMap<Long, Triple<Long, Int, HashMap<Int, ByteArray>>>()  // m -> (cas, n, {i: del})
        val smerVen: String get() = if (vloga == 'a') "ab" else "ba"
        val smerNoter: String get() = if (vloga == 'a') "ba" else "ab"
    }

    private class Dogovor(
        val sessionId: String, val tujId: String, val zasebni: PrivateKey, val epk: String, val nonce: String, val zacet: Long,
        val vrsta: MutableList<Pair<Long, String>>,          // (kdaj je sporocilo prislo v vrsto, notranje sporocilo)
    )

    private val zaklep = Any()
    private val seje = HashMap<String, Seja>()
    private val za = HashMap<String, String>()
    private val dogovori = HashMap<String, Dogovor>()
    /** Oznaka sporocila prenosa -> (cas, ali je ponudba dogovora, notranja sporocila, ki jih nosi ali nanj cakajo). */
    private class Izhodno(val cas: Long, val dogovor: Boolean, val notranja: () -> List<String>)
    private val izhodna = LinkedHashMap<String, Izhodno>()

    // ------------------------------------------------------------------ posiljanje

    fun imaSejo(tujId: String): Boolean = synchronized(zaklep) { sejaZa(tujId) != null }

    fun jedroSeje(tujId: String): String = synchronized(zaklep) { sejaZa(tujId)?.jedro ?: "" }

    /** Koliko sporocil v vec delih seja z napravo trenutno sestavlja (za preizkus meje). */
    fun nedokoncanih(tujId: String): Int = synchronized(zaklep) { sejaZa(tujId)?.deli?.size ?: 0 }

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
        synchronized(zaklep) {
            if (sejaZa(tujId) != null) return true
            val d = dogovori[tujId]
            if (d != null && ura() - d.zacet <= DOGOVOR_CAKA_MS) return true
            return zacniDogovor(tujId, mutableListOf())
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

    /** Naprava je odsla ali se zamenjala: njene seje in cakajoci dogovor ne veljajo vec. */
    fun pozabi(tujId: String) = synchronized(zaklep) {
        seje.values.filter { it.tujId == tujId }.forEach { odstrani(it) }
        za.remove(tujId)
        dogovori.remove(tujId)
        Unit
    }

    fun pozabiVse() = synchronized(zaklep) { seje.clear(); za.clear(); dogovori.clear(); izhodna.clear() }

    /**
     * Poslje notranje sporocilo (JSON) napravi [tujId] zasciteno. Ce seje se ni, zacne dogovor in sporocilo pocaka nanj.
     * False: zascita ni mogoca (kljuca naprave ne poznamo, vrsta je polna) - klicatelj NE sme poslati nezasciteno,
     * razen ce naprava zascite sploh ne zna.
     */
    fun poslji(tujId: String, notranjeJson: String): Boolean {
        if (tujId.isBlank()) return false
        synchronized(zaklep) {
            sejaZa(tujId)?.let { return posljiPoSeji(it, notranjeJson) }
            val zdaj = ura()
            val d = dogovori[tujId]
            if (d != null && zdaj - d.zacet <= DOGOVOR_CAKA_MS) {
                if (d.vrsta.size >= NAJVEC_V_VRSTI) return false
                d.vrsta.add(zdaj to notranjeJson)
                return true
            }
            // Dogovora se ni ali pa nanj cakamo predolgo: zacnemo znova. Kar je cakalo na starega, zavrzemo - ukaz, ki bi
            // se izvedel cez vec sekund, je slabsi od ukaza, ki se ne izvede (klicatelj je medtem ze javil napako).
            return zacniDogovor(tujId, mutableListOf(zdaj to notranjeJson))
        }
    }

    private fun zacniDogovor(tujId: String, vrsta: MutableList<Pair<Long, String>>): Boolean {
        if (kljucZa(tujId).isNullOrBlank()) return false      // naprave ni v nasem krogu: odgovora ne bi mogli preveriti
        val moj = mojId()
        if (moj.isBlank()) return false
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
        val zdaj = ura()
        dogovori.entries.removeAll { zdaj - it.value.zacet > DOGOVOR_VELJA_MS }
        while (dogovori.size >= NAJVEC_DOGOVOROV && !dogovori.containsKey(tujId))
            dogovori.remove(dogovori.entries.minByOrNull { it.value.zacet }?.key ?: break)
        val dogovor = Dogovor(sessionId, tujId, par.first, par.second, nonce, zdaj, vrsta)
        dogovori[tujId] = dogovor
        val oznaka = PREDPONA_OZNAK + sessionId.take(10)
        zapomniIzhodno(oznaka, dogovor = true) { dogovor.vrsta.map { it.second } }
        val tovor = "{\"session_id\":${niz(sessionId)},\"purpose\":\"$NAMEN\",\"v\":$RAZLICICA,\"from\":${niz(moj)},\"to\":${niz(tujId)}," +
            "\"nonce\":${niz(nonce)},\"epk\":${niz(par.second)},\"ts\":${System.currentTimeMillis() / 1000},\"sig\":${niz(podpis)}}"
        val ok = poslji("data.offer", tujId, oznaka, tovor)
        if (!ok) dogovori.remove(tujId)
        return ok
    }

    /**
     * Sporocilo sifrira in poslje. Vsako notranje sporocilo gre skozi to funkcijo NATANKO ENKRAT - ponovnega posiljanja
     * ni (glej prejmiNapako), zato ga prejemnik ne more dobiti dvakrat.
     */
    private fun posljiPoSeji(s: Seja, notranjeJson: String): Boolean {
        val cistopis = notranjeJson.toByteArray(Charsets.UTF_8)
        val stDelov = if (cistopis.isEmpty()) 1 else (cistopis.size + DOLZINA_DELA - 1) / DOLZINA_DELA
        if (stDelov > NAJVEC_DELOV) return false
        val (kljuc, predpona) = s.kljuci.smer(s.smerVen)
        val m = s.stSporocila++
        val prvi = s.seqVen
        s.seqVen += stDelov
        // Zapomnimo si PRED posiljanjem: obvestilo »ni seje« lahko pride, se preden se posiljanje vrne.
        val zdaj = ura()
        s.zadnja.removeAll { zdaj - it.second > NEPOTRJENA_VELJAJO_MS }
        s.zadnja.add(Triple(prvi, zdaj, notranjeJson))
        while (s.zadnja.size > HRANI_ZADNJIH) s.zadnja.removeAt(0)
        zapomniIzhodno("$PREDPONA_OZNAK${s.sessionId.take(8)}-$prvi") { listOf(notranjeJson) }
        for (i in 0 until stDelov) {
            val seq = prvi + i
            val od = i * DOLZINA_DELA
            val kos = cistopis.copyOfRange(od, minOf(cistopis.size, od + DOLZINA_DELA))
            val podatki = sifriraj(kljuc, predpona, seq, kos, aad(s.sessionId, s.smerVen, seq, m, i, stDelov))
            val tovor = "{\"session_id\":${niz(s.sessionId)},\"seq\":$seq,\"m\":$m,\"i\":$i,\"n\":$stDelov,\"data\":${niz(b64(podatki))}}"
            if (!poslji("data.chunk", s.tujId, "$PREDPONA_OZNAK${s.sessionId.take(8)}-$seq", tovor)) return false
        }
        return true
    }

    private fun zapomniIzhodno(oznaka: String, dogovor: Boolean = false, notranja: () -> List<String>) {
        val zdaj = ura()
        izhodna.entries.removeAll { zdaj - it.value.cas > IZHODNA_VELJAJO_MS }
        izhodna[oznaka] = Izhodno(zdaj, dogovor, notranja)
        while (izhodna.size > NAJVEC_IZHODNIH) izhodna.remove(izhodna.keys.first())
    }

    /** Klicatelju javi, da ta sporocila do naprave niso prisla (klice se ZUNAJ zaklepa). */
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
        synchronized(zaklep) {
            val vnos = izhodna.remove(oznaka) ?: return true
            notranja = vnos.notranja()
            jeDogovor = vnos.dogovor
            if (!sprejeto) dogovori.entries.removeAll { PREDPONA_OZNAK + it.value.sessionId.take(10) == oznaka }
        }
        if (sprejeto) {
            // Sprejeta ponudba se ni sprejeto sporocilo: to se caka na odgovor naprave.
            val sprejem = obSprejemu
            if (stanje == "accepted" && !jeDogovor && sprejem != null)
                for (n in notranja) try { sprejem(n) } catch (_: Throwable) { }
            return true
        }
        zavrni(notranja, t.niz("error") ?: "", t.niz("error_code") ?: "")
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
        if (sessionId.length > 64 || nonce.length > 64 || epk.length > 400 || sig.length > 400 || odId.length > NAJVEC_OZNAKE)
            throw Napaka("ponudba ima predolgo polje")
        if (doId != moj) throw Napaka("ponudba ni namenjena temu programu")
        if (posiljatelj.isNotEmpty() && posiljatelj != odId) throw Napaka("posiljatelj sredisca se ne ujema s podpisano ponudbo")
        val kljuc = kljucZa(odId)?.takeIf { it.isNotBlank() }
        if (kljuc == null) {
            // Posiljatelj naj izve takoj (sicer njegov ukaz tiho caka na iztek casa): dogovor z nami ne more uspeti.
            javiNapako(posiljatelj.ifEmpty { odId }, sessionId, "ni_kljuca", null)
            throw Napaka("naprave ni v krogu zaupanja")
        }
        if (!preveri(kljuc, podatkiPonudbe(sessionId, odId, doId, nonce, epk), sig))
            throw Napaka("podpis ponudbe se ne ujema s kljucem naprave v krogu")
        val (zasebni, mojEpk) = novPar()
        val mojNonce = b64(nakljucno(16))
        val podpis = try { podpisi(podatkiOdgovora(sessionId, moj, odId, nonce, mojNonce, epk, mojEpk)) } catch (_: Throwable) { "" }
        if (podpis.isBlank()) throw Napaka("odgovora ni bilo mogoce podpisati")
        val kljuci = izpelji(ecdh(zasebni, epk), sessionId, nonce, mojNonce, odId, moj)
        val seja = Seja(sessionId, odId, 'b', kljuci, idIzKljuca(kljuc), ura(), potrjena = false)
        val tovor = "{\"session_id\":${niz(sessionId)},\"purpose\":\"$NAMEN\",\"v\":$RAZLICICA,\"from\":${niz(moj)},\"to\":${niz(odId)}," +
            "\"offer_nonce\":${niz(nonce)},\"nonce\":${niz(mojNonce)},\"epk\":${niz(mojEpk)},\"ts\":${System.currentTimeMillis() / 1000},\"sig\":${niz(podpis)}}"
        synchronized(zaklep) {
            // Oznako seje izbere posiljatelj ponudbe in je srediscu vidna: ne sme se ujeti z nobeno naso sejo ne z
            // dogovorom, ki ga ravno cakamo (sicer bi odgovor nanj sejo pod to oznako zamenjal).
            if (seje.containsKey(sessionId) || dogovori.values.any { it.sessionId == sessionId })
                throw Napaka("seja s to oznako ze obstaja ali jo ravno dogovarjamo")
            omejiSeje()
            seje[sessionId] = seja
            // Odgovor gre ven PRED kazalcem posiljanja: druga nit po tej seji ne sme poslati nicesar, dokler odgovor ni
            // na poti (druga stran bi kos dobila pred odgovorom in javila, da seje ne pozna).
            poslji("data.answer", odId, PREDPONA_OZNAK + sessionId.take(10) + "-o", tovor)
            // Po tej seji tudi posiljamo, razen ce ravno cakamo na odgovor na SVOJO ponudbo (takrat velja nasa).
            if (!dogovori.containsKey(odId)) za[odId] = sessionId
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
        val d = synchronized(zaklep) {
            val cakajoc = dogovori[odId]?.takeIf { it.sessionId == sessionId } ?: throw Napaka("odgovor ne pripada dogovoru, ki ga cakamo")
            if (ura() - cakajoc.zacet > DOGOVOR_VELJA_MS) { dogovori.remove(odId); throw Napaka("odgovor je prisel prepozno") }
            cakajoc
        }
        val moj = mojId()
        if (nonce.isEmpty() || epk.isEmpty() || sig.isEmpty() || doId != moj || nonce.length > 64 || epk.length > 400 || sig.length > 400)
            throw Napaka("odgovoru manjka polje ali ni namenjen temu programu")
        if (posiljatelj.isNotEmpty() && posiljatelj != odId) throw Napaka("posiljatelj sredisca se ne ujema s podpisanim odgovorom")
        if (t.niz("offer_nonce") != d.nonce) throw Napaka("odgovor ne veze nase ponudbe")
        val kljuc = kljucZa(odId)?.takeIf { it.isNotBlank() } ?: throw Napaka("naprave ni v krogu zaupanja")
        if (!preveri(kljuc, podatkiOdgovora(sessionId, odId, moj, d.nonce, nonce, d.epk, epk), sig))
            throw Napaka("podpis odgovora se ne ujema s kljucem naprave v krogu")
        val kljuci = izpelji(ecdh(d.zasebni, epk), sessionId, d.nonce, nonce, moj, odId)
        val seja = Seja(sessionId, odId, 'a', kljuci, idIzKljuca(kljuc), ura(), potrjena = true)
        val zastarela = ArrayList<String>()
        synchronized(zaklep) {
            if (dogovori[odId] !== d) throw Napaka("dogovor se je medtem zamenjal")
            if (seje.containsKey(sessionId)) throw Napaka("seja s to oznako ze obstaja")
            dogovori.remove(odId)
            izhodna.remove(PREDPONA_OZNAK + sessionId.take(10))
            omejiSeje()
            seje[sessionId] = seja
            za[odId] = sessionId
            val vrsta = d.vrsta.toList()
            d.vrsta.clear()
            val zdaj = ura()
            for ((cas, sporocilo) in vrsta) {
                // Sporocilo, ki je na dogovor cakalo predolgo (sredisce je odgovor zadrzalo), ne gre vec: ukaz, ki bi se
                // izvedel z zamudo, je slabsi od ukaza, ki se ne izvede.
                if (zdaj - cas > V_VRSTI_VELJA_MS) zastarela.add(sporocilo) else posljiPoSeji(seja, sporocilo)
            }
        }
        zavrni(zastarela, "Naprava ni odgovorila pravočasno. Poskusi znova.", "cas")
        javiSejo(seja)
    }

    private fun javiSejo(seja: Seja) { try { obSeji?.invoke(seja.jedro) } catch (_: Throwable) { } }

    /**
     * Naredi prostor za novo sejo. Najprej izpadejo seje, ki jim je potekel rok, potem NEPOTRJENE (nastanejo iz prejetih
     * ponudb, tudi ponovljenih - z njimi nihce ne sme izriniti seje, ki res deluje), sele nato najstarejse.
     */
    private fun omejiSeje() {
        val zdaj = ura()
        seje.values.filter { zdaj - it.nastala > SEJA_VELJA_MS }.forEach { odstrani(it) }
        while (seje.size >= NAJVEC_SEJ) {
            val kandidati = seje.values.filter { !it.potrjena }.ifEmpty { seje.values.toList() }
            odstrani(kandidati.minByOrNull { it.nastala } ?: break)
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
        val sifropis = izB64(t.niz("data") ?: "", "data")
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
                    if (!s.deli.containsKey(m)) while (s.deli.size >= NAJVEC_NEDOKONCANIH)
                        s.deli.remove(s.deli.entries.minByOrNull { it.value.first }?.key ?: break)
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
        val notranje = String(celo, Charsets.UTF_8)
        if (!notranje.trimStart().startsWith("{")) throw Napaka("notranje sporocilo ni JSON")
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
                izhodna.remove(PREDPONA_OZNAK + sid.take(10))
                zavrnjena = d.vrsta.map { it.second }
                d.vrsta.clear()
                besedilo = "Naprava te naprave nima v svojem krogu zaupanja."
            } else {
                if ((posiljatelj.isNotEmpty() && posiljatelj != s.tujId) || koda != "ni_seje") return true
                val od = t.celo("seq") ?: -1L
                val zdaj = ura()
                zavrnjena = s.zadnja.filter { od >= 0 && it.first >= od && zdaj - it.second <= NEPOTRJENA_VELJAJO_MS }.map { it.third }
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
         * napravi samo odpre stran ali predvajanje (stran, nadzor predvajanja, »nadaljuj na napravi«).
         */
        val ZASCITENI_TIPI = setOf("control.command", "control.result", "cast.url", "cast.control", "handoff.request")
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
        /** Zadnja poslana sporocila: po »ni_seje« jih javimo klicatelju kot zavrnjena. */
        const val HRANI_ZADNJIH = 16
        /** Starejsih ne javljamo: klicatelj je zanje ze dobil odgovor ali iztek casa. */
        const val NEPOTRJENA_VELJAJO_MS = 5_000L
        const val NEDOKONCANA_VELJAJO_MS = 30_000L
        /** Sporocil v vec delih, ki jih ena seja sestavlja hkrati. */
        const val NAJVEC_NEDOKONCANIH = 4
        /** Najdaljsa oznaka naprave v dogovoru. */
        const val NAJVEC_OZNAKE = 128
        const val NAJVEC_IZHODNIH = 256
        /** Potrditev sredisca pride v milisekundah; po tem casu zapis o poslanem zavrzemo. */
        const val IZHODNA_VELJAJO_MS = 30_000L

        private val nakljucje = SecureRandom()

        private fun nakljucno(n: Int): ByteArray = ByteArray(n).also { nakljucje.nextBytes(it) }

        fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

        fun izB64(s: String, kaj: String): ByteArray =
            try { Base64.getDecoder().decode(s) } catch (_: IllegalArgumentException) { throw Napaka("neveljaven zapis ($kaj)") }

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
        fun zakrij(id: String): String = if (id.length < 12) id else id.take(2) + "…" + id.drop(14)
    }
}
