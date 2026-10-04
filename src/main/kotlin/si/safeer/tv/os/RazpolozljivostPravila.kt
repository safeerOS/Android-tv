package si.safeer.tv.os

/**
 * Pravila razpolozljivosti brez Androida (preizkus: tests/RazpolozljivostPravilaTest.kt).
 *
 * Zapis o naslovu ni »da / ne v nacinu X«, ampak dve meji glede na to, kaj naprava zmore s torrenti ([meja]: 0 = nic,
 * bajti = najvecja datoteka, ki gre na njen prosti prostor, [VSE] = vsak torrent):
 *   jeOd - naslov se da predvajati, kadar je meja >= jeOd (0 = neposreden tok: na vsaki napravi);
 *   niDo - naslova se ne da predvajati, kadar je meja <= niDo ([VSE] = noben dodatek nima nicesar).
 * Vsaka meja ima svoj cas dokaza. En odgovor dodatka tako velja na vseh napravah v Linku in v vseh nacinih: ko
 * pomocnik za torrente pride ali gre, ko se spremeni prosti prostor ali seznam dodatkov, dodatkov ni treba sprasevati
 * od zacetka. Do 4. 10. 2026 je imel vsak nacin svoje zapise, sprememba dodatkov pa je pozabila vse - mreza Filmi se
 * je potem polnila znova, en naslov na osem sekund (toliko poizvedb dodatek dovoli, glej Stremio.zeton).
 */
object RazpolozljivostPravila {
    const val BREZ = -1L
    const val VSE = Long.MAX_VALUE
    private const val URA = 3_600_000L

    /**
     * Kako dolgo je odgovor svez; potem ga preverimo znova - v ozadju, prikaz na to ne caka. Vsako preverjanje je
     * poizvedba dodatku, teh pa dom ne sme porabiti prevec: film, ki se je dal predvajati, redko izgine (in ce, to pove
     * dotik), nova vsebina pa sme na seznam priti dan ali dva pozneje.
     */
    const val JE_VELJA = 7 * 24 * URA
    const val NI_VELJA = 2 * 24 * URA
    /** Kako dolgo zadnji znani odgovor se uporabimo za prikaz, medtem ko ga preverjamo znova. */
    const val JE_ZNANO = 30 * 24 * URA
    const val NI_ZNANO = 7 * 24 * URA

    data class Zapis(val jeOd: Long = BREZ, val casJe: Long = 0L, val niDo: Long = BREZ, val casNi: Long = 0L) {
        /** Cas zadnjega dokaza (za izbiro, kaj pozabiti, in za povzetek pri delitvi med napravami). */
        val cas: Long get() = maxOf(casJe, casNi)
    }

    /** Izid ene poizvedbe pri dodatkih. */
    data class Izid(val jeOd: Long = BREZ, val niDo: Long = BREZ) {
        /** true / false za napravo z mejo [meja]; null = odgovor za to napravo nicesar ne pove. */
        fun velja(meja: Long): Boolean? = when {
            jeOd != BREZ && meja >= jeOd -> true
            niDo != BREZ && meja <= niDo -> false
            else -> null
        }
    }

    /** Ura naprav v Linku ni povsem enaka: dokaz do ene ure »iz prihodnosti« je svez, starejsi od tega ni verodostojen. */
    private fun starost(cas: Long, zdaj: Long): Long = (zdaj - cas).let { if (it < 0L && it >= -URA) 0L else it }

    private fun odgovor(z: Zapis, meja: Long, zdaj: Long): Pair<Boolean, Long>? = when {
        z.jeOd != BREZ && meja >= z.jeOd -> true to starost(z.casJe, zdaj)
        z.niDo != BREZ && meja <= z.niDo -> false to starost(z.casNi, zdaj)
        else -> null
    }

    /** Svez odgovor za to napravo; null = ne vemo ali je zastarelo (preveriti znova). */
    fun stanje(z: Zapis?, meja: Long, zdaj: Long): Boolean? {
        val (v, s) = z?.let { odgovor(it, meja, zdaj) } ?: return null
        return if (s in 0..(if (v) JE_VELJA else NI_VELJA)) v else null
    }

    /** Zadnji znani odgovor za prikaz (tudi zastarel, do [JE_ZNANO] / [NI_ZNANO]); null = naslova ne poznamo. */
    fun znano(z: Zapis?, meja: Long, zdaj: Long): Boolean? {
        val (v, s) = z?.let { odgovor(it, meja, zdaj) } ?: return null
        return if (s in 0..(if (v) JE_ZNANO else NI_ZNANO)) v else null
    }

    /**
     * Kaj tok zahteva od naprave: 0 = neposreden naslov (vsaka naprava), bajti = torrent znane velikosti, [VSE] =
     * torrent neznane velikosti (samo naprava, ki zmore vsakega); null = tok ni za predvajanje. Isto pravilo kot
     * Stremio.torrentGre.
     */
    fun potreba(neposreden: Boolean, torrent: Boolean, gb: Double): Long? = when {
        neposreden -> 0L
        !torrent -> null
        gb > 0.0 -> (gb * 1024.0 * 1024.0 * 1024.0).toLong().coerceAtLeast(1L)
        else -> VSE
    }

    /**
     * Izid poizvedbe iz potreb vseh najdenih tokov. [vsiOdgovorili]: odgovorili so vsi vprasani dodatki - sele takrat
     * vemo tudi, kje naslova NI. null = nicesar ne vemo (ni tokov, a tudi vsi niso odgovorili).
     */
    fun izTokov(potrebe: List<Long>, vsiOdgovorili: Boolean): Izid? {
        val najmanj = potrebe.minOrNull()
        return when {
            najmanj == null -> if (vsiOdgovorili) Izid(niDo = VSE) else null
            vsiOdgovorili && najmanj > 0L -> Izid(jeOd = najmanj, niDo = najmanj - 1)
            else -> Izid(jeOd = najmanj)
        }
    }

    /**
     * Nov odgovor dodatkov zdruzi z dosedanjim zapisom. Popoln odgovor (odgovorili so vsi, ali pa je tok neposreden)
     * nadomesti vse. Delni (prvi dodatek s tokom je zadoscal) nizje meje iz prejsnjega odgovora ne povozi, dokler je
     * ta se sveza; njenega casa pa ne podaljsa - dokaz ostane star, kolikor je.
     */
    fun zapisi(prej: Zapis?, izid: Izid, zdaj: Long): Zapis {
        var jeOd = prej?.jeOd ?: BREZ; var casJe = prej?.casJe ?: 0L
        var niDo = prej?.niDo ?: BREZ; var casNi = prej?.casNi ?: 0L
        if (izid.niDo != BREZ || izid.jeOd == 0L) { jeOd = BREZ; casJe = 0L; niDo = BREZ; casNi = 0L }
        if (izid.jeOd != BREZ) {
            if (jeOd == BREZ || izid.jeOd <= jeOd || starost(casJe, zdaj) !in 0..JE_VELJA) { jeOd = izid.jeOd; casJe = zdaj }
            if (niDo != BREZ && niDo >= jeOd) { if (jeOd > 0L) niDo = jeOd - 1 else { niDo = BREZ; casNi = 0L } }
        }
        if (izid.niDo != BREZ) {
            niDo = izid.niDo; casNi = zdaj
            if (jeOd != BREZ && jeOd <= niDo) { jeOd = BREZ; casJe = 0L }
        }
        return Zapis(jeOd, casJe, niDo, casNi)
    }

    /**
     * Zapis druge naprave v Linku (isti dodatki): obvelja tisti z novejsim dokazom, v celoti - mej dveh razlicnih
     * odgovorov ne mesamo. Cas »iz prihodnosti« (ura druge naprave) se pristrize na zdaj.
     */
    fun zdruzi(moj: Zapis?, tuj: Zapis, zdaj: Long): Zapis {
        val t = popravi(Zapis(tuj.jeOd, if (tuj.jeOd == BREZ) 0L else minOf(tuj.casJe, zdaj), tuj.niDo, if (tuj.niDo == BREZ) 0L else minOf(tuj.casNi, zdaj)))
        return if (moj == null || t.cas > moj.cas) t else moj
    }

    /** Meji si ne smeta nasprotovati (niDo < jeOd): popusti starejsi dokaz. */
    private fun popravi(z: Zapis): Zapis {
        if (z.jeOd == BREZ || z.niDo == BREZ || z.niDo < z.jeOd) return z
        return if (z.casJe >= z.casNi) { if (z.jeOd > 0L) z.copy(niDo = z.jeOd - 1) else z.copy(niDo = BREZ, casNi = 0L) }
        else z.copy(jeOd = BREZ, casJe = 0L)
    }

    /** Dodatki so se spremenili: odgovori ostanejo za prikaz, a niso vec svezi - preverimo jih znova, v ozadju. */
    fun zastaraj(z: Zapis, zdaj: Long): Zapis = Zapis(
        z.jeOd, if (z.jeOd == BREZ) 0L else minOf(z.casJe, zdaj - JE_VELJA - 1),
        z.niDo, if (z.niDo == BREZ) 0L else minOf(z.casNi, zdaj - NI_VELJA - 1))

    // ------------------------------------------------------------------ zapis v niz (shramba, delitev med napravami)

    private fun vNiz(v: Long) = when (v) { BREZ -> ""; VSE -> "M"; else -> v.toString() }
    private fun izNiza(s: String): Long? = when (s) { "" -> BREZ; "M" -> VSE; else -> s.toLongOrNull()?.takeIf { it >= 0L } }

    /** »jeOd;casJe;niDo;casNi« (prazno = brez meje, M = vse). */
    fun vNiz(z: Zapis): String = vNiz(z.jeOd) + ";" + z.casJe + ";" + vNiz(z.niDo) + ";" + z.casNi

    fun izNiza(s: String?): Zapis? {
        val d = s?.split(';') ?: return null
        if (d.size != 4) return null
        val jeOd = izNiza(d[0]) ?: return null
        val niDo = izNiza(d[2]) ?: return null
        val casJe = d[1].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val casNi = d[3].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        if (jeOd == BREZ && niDo == BREZ) return null
        return popravi(Zapis(jeOd, if (jeOd == BREZ) 0L else casJe, niDo, if (niDo == BREZ) 0L else casNi))
    }

    /**
     * Zapis iz shrambe pred 4. 10. 2026: kljuc »tip|id« (vsak torrent steje), »tip|id|brez« (nobeden) ali »tip|id|doN«
     * (do N x 256 MB); vrednost = cas, negativen pomeni »ni na voljo«. Vrne (nov kljuc, zapis) ali null.
     */
    fun izStarega(kljuc: String, vrednost: Long): Pair<String, Zapis>? {
        val d = kljuc.split('|')
        if (d.size !in 2..3 || d[0].isBlank() || d[1].isBlank() || vrednost == 0L) return null
        val cas = kotlin.math.abs(vrednost)
        val je = vrednost > 0L
        val blok = 256L * 1024 * 1024
        val z = when {
            d.size == 2 -> if (je) Zapis(jeOd = VSE, casJe = cas) else Zapis(niDo = VSE, casNi = cas)
            d[2] == "brez" -> if (je) Zapis(jeOd = 0L, casJe = cas) else Zapis(niDo = 0L, casNi = cas)
            d[2].startsWith("do") -> {
                val n = d[2].removePrefix("do").toLongOrNull()?.takeIf { it in 0..1_000_000L } ?: return null
                // Meja naprave je bila nekje med n in n+1 bloki: »je« velja zanesljivo od n+1 blokov, »ni« do n blokov.
                if (je) Zapis(jeOd = (n + 1) * blok, casJe = cas) else Zapis(niDo = n * blok, casNi = cas)
            }
            else -> return null
        }
        return (d[0] + "|" + d[1]) to z
    }

    // ------------------------------------------------------------------ dodatki

    /** Kratek odtis naslova dodatka (v shrambi in pri delitvi ne hranimo naslovov - v njih so lahko zetoni). */
    fun odtisNaslova(osnova: String): String = sha(osnova.trim()).take(12)

    private fun sha(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    class Sprememba(val zastaraj: Boolean, val dodatki: Map<String, Char>)

    /**
     * Primerja dodatke ob zadnjem zagonu ([prej]) z zdajsnjimi ([zdaj]): odtis dodatka -> 's' (daje tokove), 'n' (jih
     * ne daje: podnapisi, katalog) ali 'u' (manifesta se ne poznamo). Dodan ali odstranjen dodatek, ki daje tokove (ali
     * tega se ne vemo), pomeni, da odgovori niso vec svezi; dodatek brez tokov ne spremeni nicesar. [prej] == null:
     * prvi zagon - nic ne sklepamo.
     */
    fun dodatki(prej: Map<String, Char>?, zdaj: Map<String, Char>): Sprememba {
        if (prej == null) return Sprememba(false, zdaj)
        var zastaraj = false
        val novo = LinkedHashMap<String, Char>()
        for ((k, v) in zdaj) {
            val p = prej[k]
            when {
                p == null -> { if (v != 'n') zastaraj = true; novo[k] = v }
                v == 'u' -> novo[k] = p                       // manifest se ni nalozen: obdrzimo, kar smo vedeli
                else -> { if (p == 'n' && v == 's') zastaraj = true; novo[k] = v }
            }
        }
        for ((k, p) in prej) if (k !in zdaj && p != 'n') zastaraj = true
        return Sprememba(zastaraj, novo)
    }

    /** Odtis vseh dodatkov, ki dajejo tokove: zapise si delijo samo naprave z enakim (prazno = ni kaj deliti). */
    fun odtis(dodatki: Map<String, Char>): String {
        val tokovni = dodatki.filterValues { it == 's' }.keys.sorted()
        return if (tokovni.isEmpty()) "" else sha(tokovni.joinToString(",")).take(16)
    }

    fun dodatkiVNiz(d: Map<String, Char>): String = d.entries.joinToString(",") { it.key + ":" + it.value }

    fun dodatkiIzNiza(s: String?): Map<String, Char>? {
        if (s == null) return null
        val m = LinkedHashMap<String, Char>()
        for (del in s.split(',')) {
            val k = del.substringBefore(':'); val v = del.substringAfter(':', "")
            if (k.isNotBlank() && v.length == 1 && v[0] in "snu") m[k] = v[0]
        }
        return m
    }

    /** Kljuc, ki ga smemo deliti z drugimi napravami: film ali serija z javnim id-jem (IMDb). Zasebni dodatki ostanejo doma. */
    fun zaDelitev(kljuc: String): Boolean {
        if (kljuc.length > 64) return false
        val tip = kljuc.substringBefore('|'); val id = kljuc.substringAfter('|', "")
        return (tip == "movie" || tip == "series") && Regex("^tt[0-9]{5,10}(:[0-9]{1,4}:[0-9]{1,4})?$").matches(id)
    }
}
