package si.safeer.tv.cast

import org.json.JSONObject
import si.safeer.tv.cast.DostopPravila.Zahteva
import si.safeer.tv.cast.DostopPravila.Zmoznost

/**
 * Prosnja za dostop (14. 10. 2026): naprava v Safeer Linku, ki ji ta naprava kaksne zmoznosti ni odprla, ob zavrnitvi
 * dobi vprasanje na TEJ napravi - »<ime> prosi za dostop: <zmoznost> na tej napravi« z Dovoli / Zavrni. Odloci samo
 * uporabnik te naprave (zascita pred napadalcem); nic v dohodnem sporocilu ne more dostopa odobriti.
 *
 * Cista pravila brez Androida (tests/ProsnjeDostopaTest.kt); shrambo in obvestilo dodaja [ProsnjeDostopa].
 *
 * - Vprasanje je samo za dejanje, ki zahteva ENO zmoznost (datoteke, programi, predvajalnik, zaslon) - ne za prosta
 *   dejanja in ne za tista, ki zahtevajo vse (ozji krog, npr. usklajevanje).
 * - Samo za napravo, ki je kljuc dokazala (jedro iz preverjenega kljuca zascite) in je clan kroga TE naprave. Ime pride
 *   iz kroga te naprave, nikoli iz sporocila.
 * - Isto jedro + zmoznost: najvec eno vprasanje na 10 minut; po »Zavrni« 24 ur nobenega. Hkrati najvec 5 cakajocih.
 * - »Dovoli« doda samo to eno zmoznost (druge ostanejo, kot so bile).
 *
 * Odgovor prosilcu (enako kot v Linux izvedbi): obstojeca oblika zavrnitve ostane, dodani sta polji
 * `access_request` = "pending" | "declined" | "none" in `capability` = crka zmoznosti (d, p, v, z); pri odgovoru s
 * podatki (seznam z `shared`/`enabled` = false) tudi v `data`.
 */
object ProsnjeDostopaPravila {
    const val POLJE_PROSNJA = "access_request"
    const val POLJE_ZMOZNOST = "capability"

    const val PONOVNO_MS = 10 * 60_000L
    const val ZAVRNITEV_MS = 24 * 60 * 60_000L
    const val NAJVEC_CAKAJOCIH = 5
    const val NAJVEC_IMENA = 60
    /** Toliko zapisov zavrnitev hranimo najvec (stejejo samo clani kroga, a zapis naj ne raste brez meje). */
    const val NAJVEC_ZAVRNITEV = 256

    enum class Stanje(val polje: String) { PENDING("pending"), DECLINED("declined"), NONE("none") }

    class Prosnja(val jedro: String, val ime: String, val zmoznost: Zmoznost, val cas: Long) {
        val kljuc: String get() = kljuc(jedro, zmoznost)
    }

    /** Izid ob zavrnitvi: kaj povemo prosilcu, in ali naj ta naprava zdaj pokaze vprasanje ([novaProsnja]). */
    class Odlocitev(val stanje: Stanje, val zmoznost: Zmoznost?, val novaProsnja: Prosnja? = null)

    fun kljuc(jedro: String, z: Zmoznost): String = "$jedro|${z.crka}"

    /** Katero ENO zmoznost zahteva dejanje; null za prosta dejanja in za tista, ki zahtevajo vse. */
    fun zmoznostZahteve(z: Zahteva): Zmoznost? = when (z) {
        Zahteva.DATOTEKE -> Zmoznost.DATOTEKE
        Zahteva.PROGRAMI -> Zmoznost.PROGRAMI
        Zahteva.PREDVAJALNIK -> Zmoznost.PREDVAJALNIK
        Zahteva.ZASLON -> Zmoznost.ZASLON
        Zahteva.PROSTO, Zahteva.VSE -> null
    }

    fun zmoznostIzCrke(c: String): Zmoznost? = if (c.length != 1) null else Zmoznost.values().firstOrNull { it.crka == c[0] }

    /**
     * Ime za vprasanje: brez krmilnih in nevidnih znakov (tudi znakov za smer besedila), en presledek namesto vec,
     * najvec [NAJVEC_IMENA] znakov.
     */
    fun cistoIme(s: String): String {
        val sb = StringBuilder()
        var presledek = false
        for (ch in s) {
            val tip = Character.getType(ch)
            val nevidno = Character.isISOControl(ch) || tip == Character.FORMAT.toInt() || tip == Character.LINE_SEPARATOR.toInt() ||
                tip == Character.PARAGRAPH_SEPARATOR.toInt() || tip == Character.PRIVATE_USE.toInt() || tip == Character.UNASSIGNED.toInt()
            if (nevidno) continue
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) { presledek = sb.isNotEmpty(); continue }
            if (presledek) { sb.append(' '); presledek = false }
            sb.append(ch)
        }
        var izid = sb.toString()
        if (izid.length > NAJVEC_IMENA) {
            var konec = NAJVEC_IMENA
            if (Character.isHighSurrogate(izid[konec - 1])) konec--
            izid = izid.substring(0, konec).trimEnd()
        }
        return izid
    }

    /** Clan kroga TE naprave, kolikor ga potrebujemo za ime. */
    class Clan(val id: String, val kljuc: String, val ime: String, val imenovano: Double)

    /**
     * Ime naprave z jedrom [jedro] iz kroga TE naprave (najnovejse poimenovanje), ali null, ce jedro ni clan kroga.
     * Ime nikoli ne pride iz dohodnega sporocila.
     */
    fun imeIzKroga(clani: List<Clan>, jedro: String, idIzKljuca: (String) -> String): String? {
        if (!DostopPravila.jeJedro(jedro)) return null
        val ujemanje = clani.filter { c -> c.kljuc.isNotBlank() && try { idIzKljuca(c.kljuc) == jedro } catch (_: Throwable) { false } }
        if (ujemanje.isEmpty()) return null
        val c = ujemanje.maxByOrNull { it.imenovano } ?: return null
        return cistoIme(c.ime).ifBlank { cistoIme(c.id).ifBlank { jedro } }
    }

    /** Stanje prosenj na tej napravi (v pomnilniku; [ProsnjeDostopa] ga hrani v nastavitvah). */
    class Shramba {
        val cakajoce = ArrayList<Prosnja>()
        /** Kdaj je bilo za kljuc (jedro|crka) nazadnje pokazano vprasanje. */
        val zadnjiPoziv = HashMap<String, Long>()
        /** Kdaj je uporabnik prosnjo za kljuc zavrnil (velja [ZAVRNITEV_MS]). */
        val zavrnjeno = HashMap<String, Long>()

        fun pocisti(zdaj: Long) {
            zavrnjeno.entries.removeAll { zdaj - it.value >= ZAVRNITEV_MS || it.value > zdaj + ZAVRNITEV_MS }
            zadnjiPoziv.entries.removeAll { zdaj - it.value >= PONOVNO_MS || it.value > zdaj + PONOVNO_MS }
            // Prosnja, na katero uporabnik 24 ur ni odgovoril, ugasne (ne zaseda mesta med cakajocimi).
            cakajoce.removeAll { zdaj - it.cas >= ZAVRNITEV_MS || it.cas > zdaj + ZAVRNITEV_MS }
        }
    }

    /**
     * Dejanje je bilo zavrnjeno, ker posiljatelju manjka dostop. [jedroPreverjeno] je jedro iz PREVERJENEGA kljuca
     * (zasciteno sporocilo) ali prazno; [imeIzKroga] ime iz kroga te naprave ali null, ce posiljatelj ni clan.
     * [obstojece] so zmoznosti, ki jih jedro ze ima (za varnost: ce zmoznost ze ima, vprasanja ni).
     */
    fun obZavrnitvi(
        s: Shramba, jedroPreverjeno: String, imeIzKroga: String?, zahteva: Zahteva, obstojece: Set<Zmoznost>, zdaj: Long,
    ): Odlocitev {
        val z = zmoznostZahteve(zahteva) ?: return Odlocitev(Stanje.NONE, null)
        if (!DostopPravila.jeJedro(jedroPreverjeno) || imeIzKroga == null || z in obstojece) return Odlocitev(Stanje.NONE, z)
        s.pocisti(zdaj)
        val k = kljuc(jedroPreverjeno, z)
        if (s.zavrnjeno.containsKey(k)) return Odlocitev(Stanje.DECLINED, z)
        val cakajoca = s.cakajoce.indexOfFirst { it.kljuc == k }
        if (s.zadnjiPoziv.containsKey(k)) {
            // V zadnjih 10 minutah je bilo vprasanje ze pokazano: prosnja caka (ali je bila pravkar odmaknjena).
            return Odlocitev(if (cakajoca >= 0) Stanje.PENDING else Stanje.NONE, z)
        }
        if (cakajoca < 0 && s.cakajoce.size >= NAJVEC_CAKAJOCIH) return Odlocitev(Stanje.NONE, z)
        val p = Prosnja(jedroPreverjeno, cistoIme(imeIzKroga).ifBlank { jedroPreverjeno }, z, zdaj)
        if (cakajoca >= 0) s.cakajoce[cakajoca] = p else s.cakajoce.add(p)
        s.zadnjiPoziv[k] = zdaj
        return Odlocitev(Stanje.PENDING, z, p)
    }

    /**
     * Uporabnik je na tej napravi pritisnil »Dovoli«: nov nabor = [obstojece] + ta ena zmoznost. Null, ce take
     * cakajoce prosnje ni (npr. ze odlocena) - takrat se nic ne spremeni.
     */
    fun dovoli(s: Shramba, jedro: String, z: Zmoznost, obstojece: Set<Zmoznost>): Set<Zmoznost>? {
        val k = kljuc(jedro, z)
        if (!s.cakajoce.removeAll { it.kljuc == k }) return null
        s.zavrnjeno.remove(k)
        return obstojece + z
    }

    /** Uporabnik je pritisnil »Zavrni«: 24 ur nobenega vprasanja za to jedro in zmoznost. */
    fun zavrni(s: Shramba, jedro: String, z: Zmoznost, zdaj: Long): Boolean {
        val k = kljuc(jedro, z)
        val bila = s.cakajoce.removeAll { it.kljuc == k }
        if (!bila) return false
        s.zavrnjeno[k] = zdaj
        if (s.zavrnjeno.size > NAJVEC_ZAVRNITEV) {
            s.zavrnjeno.entries.sortedBy { it.value }.take(s.zavrnjeno.size - NAJVEC_ZAVRNITEV).forEach { s.zavrnjeno.remove(it.key) }
        }
        return true
    }

    /**
     * Doda polji prosnje odgovoru, ki ga ze tvori zavrnitev (obstojeca polja ostanejo nespremenjena). Ce ima odgovor
     * podatke (`data`, npr. seznam s `shared` = false), gresta polji tudi tja.
     */
    fun dopolniOdgovor(odgovor: JSONObject, o: Odlocitev): JSONObject {
        odgovor.put(POLJE_PROSNJA, o.stanje.polje)
        o.zmoznost?.let { odgovor.put(POLJE_ZMOZNOST, it.crka.toString()) }
        odgovor.optJSONObject("data")?.let { d ->
            d.put(POLJE_PROSNJA, o.stanje.polje)
            o.zmoznost?.let { d.put(POLJE_ZMOZNOST, it.crka.toString()) }
        }
        return odgovor
    }

    /** Kaj prosilcu pokazati iz odgovora (vrh ali `data`): PENDING, DECLINED ali null (nic posebnega). */
    fun stanjeOdgovora(odgovor: JSONObject?): Stanje? {
        if (odgovor == null) return null
        val v = odgovor.optString(POLJE_PROSNJA, "").ifBlank { odgovor.optJSONObject("data")?.optString(POLJE_PROSNJA, "").orEmpty() }
        return when (v) {
            Stanje.PENDING.polje -> Stanje.PENDING
            Stanje.DECLINED.polje -> Stanje.DECLINED
            else -> null
        }
    }
}
