package si.safeer.tv.os

/**
 * Cista pravila za usklajenost zvoka in slike (A/V sync) pri prenosu zaslona racunalnika na TV.
 * Brez Androida, da se da preizkusiti na navadnem JVM.
 */
object ZaslonSinhro {
    /** Pod tem odmikom (vkljucno) ne popravljamo, da drobna nihanja ne povzrocajo trzanja. */
    const val MRTVO_OBMOCJE_MS = 10L

    /** Najvecji dovoljeni popravek ali rocni zamik v obe smeri (ms). */
    const val NAJVEC_ZAMIK_MS = 400L

    /** Ciljna meja usklajenosti: tokova sta usklajena, ce je |odmik| strogo manjsi od te vrednosti. */
    const val CILJ_MS = 20L

    /** Korak rocnega zamika zvoka ob enem pritisku gumba na daljincu (ms). */
    const val KORAK_ROCNO_MS = 10L

    /** Najmanjse stevilo meritev, preden glajenje vrne mediano. */
    const val NAJMANJ_MERITEV = 5

    /**
     * Izracuna trenutni odmik med prikazano slicico in predvajanim zvocnim vzorcem (oba v ms iste ure zajema).
     *
     * Predznak:
     * - pozitivno: slika je NAPREJ (prikazana slicica je novejsa od zvoka, zvok kasni),
     * - negativno: zvok je NAPREJ (zvok je novejsi od prikazane slicice).
     */
    fun odmik(zigSlikeMs: Long, zigZvokaMs: Long): Long = zigSlikeMs - zigZvokaMs

    /** Vrne true, ce sta slika in zvok usklajena znotraj ciljne meje (|odmik| < [CILJ_MS]). */
    fun usklajen(odmikMs: Long): Boolean = odmikMs > -CILJ_MS && odmikMs < CILJ_MS

    /**
     * Izracuna, koliko ms je treba zakasniti enega od tokov, da se slika in zvok uskladita ob upostevanju
     * rocnega zamika zvoka.
     *
     * Predznak rezultata:
     * - pozitivna vrednost (+N): za N ms je treba ZAKASNITI SLIKO (slika je prevec naprej),
     * - negativna vrednost (-N): za N ms je treba ZAKASNITI ZVOK (zvok je prevec naprej),
     * - 0: ostanek je znotraj mrtvega obmocja (|ostanek| <= [MRTVO_OBMOCJE_MS]), popravek ni potreben.
     *
     * Predznak rocnega zamika:
     * - [rocniZamikMs] > 0 pomeni, da je zvok pomaknjen kasneje glede na sliko, zato ga odstejemo od odmika
     *   (ostanek = [odmikMs] - [rocniZamikMs]).
     *
     * Primer: izmerjen odmik je +80 ms (slika je 80 ms naprej), rocni zamik zvoka je +60 ms
     * -> ostanek je 80 - 60 = +20 ms, zato vrne +20 ms (sliko zakasnimo za 20 ms).
     *
     * Rezultat je vedno omejen na obmocje [-[NAJVEC_ZAMIK_MS], [NAJVEC_ZAMIK_MS]].
     */
    fun potrebnaZakasnitev(odmikMs: Long, rocniZamikMs: Long): Long {
        val ostanek = odmikMs - rocniZamikMs
        if (ostanek in -MRTVO_OBMOCJE_MS..MRTVO_OBMOCJE_MS) return 0L
        return ostanek.coerceIn(-NAJVEC_ZAMIK_MS, NAJVEC_ZAMIK_MS)
    }

    /** Omeji rocni zamik zvoka na dovoljeno obmocje [-[NAJVEC_ZAMIK_MS], [NAJVEC_ZAMIK_MS]]. */
    fun omejiRocniZamik(ms: Long): Long = ms.coerceIn(-NAJVEC_ZAMIK_MS, NAJVEC_ZAMIK_MS)

    /** Premakne rocni zamik zvoka za en korak ([KORAK_ROCNO_MS]) gor ali dol in ga omeji na dovoljene meje. */
    fun rocniKorak(trenutno: Long, gor: Boolean): Long =
        omejiRocniZamik(if (gor) trenutno + KORAK_ROCNO_MS else trenutno - KORAK_ROCNO_MS)

    /**
     * Drsece okno zadnjih [velikost] meritev odmika z racunanjem mediane.
     * Mediana je odporna na posamezne osamelce (npr. enkraten skok zaradi zastoja omrezja ali medpomnilnika).
     */
    class Glajenje(val velikost: Int = 15) {
        private val vzorci = LongArray(velikost.also { require(it >= NAJMANJ_MERITEV) { "velikost mora biti vsaj $NAJMANJ_MERITEV, sicer mediana nikoli ne vrne popravka" } })
        private var stevilo = 0
        private var naslednji = 0

        /** Doda novo meritev odmika v drsece okno; ce je okno polno, prepise najstarejso. */
        fun dodaj(odmikMs: Long) {
            vzorci[naslednji] = odmikMs
            naslednji = (naslednji + 1) % velikost
            if (stevilo < velikost) stevilo++
        }

        /**
         * Vrne mediano zadnjih meritev ali null, dokler ni zbranih vsaj [NAJMANJ_MERITEV] (5) meritev.
         */
        fun mediana(): Long? {
            if (stevilo < NAJMANJ_MERITEV) return null
            val urejeno = vzorci.copyOf(stevilo)
            urejeno.sort()
            val sredina = stevilo / 2
            return if (stevilo % 2 == 1) {
                urejeno[sredina]
            } else {
                urejeno[sredina - 1] + (urejeno[sredina] - urejeno[sredina - 1]) / 2L
            }
        }

        /** Izbrise vse zbrane meritve. */
        fun ponastavi() {
            stevilo = 0
            naslednji = 0
        }
    }

    /** Okvir ziga zajema (glava "zig": {"okvir": 4}); enako kot OKVIR_ZIG na racunalniku. */
    const val OKVIR_ZIG = 4

    /** Okvir ziga je kratek JSON; daljsi je pokvarjen in ga preskocimo. */
    const val NAJVEC_ZIG_BAJTOV = 200

    /** Po uveljavljenem koraku toliko ms ne merimo: prej meritve kazejo se prehod (medpomnilnik, ze izdane slike). */
    const val MIROVANJE_PO_KORAKU_MS = 200L

    /** Casovni zig AudioTrack, starejsi od tega (ns), ni vec zanesljiv za preracun (in bi preliv mnozenja). */
    const val NAJVEC_STAROST_ZIGA_NS = 1_000_000_000L

    /** Tako dalec od zacetka odseka (ms) se zig zvoka se podaljsa; odsek se osvezi vsaj vsako sekundo. */
    const val NAJVEC_OD_ODSEKA_MS = 2000L

    /** Brez izhoda iz dekoderja toliko ms, medtem ko slike prihajajo noter: zastoj, zahtevaj kljucni okvir. */
    const val ZASTOJ_ZA_KLJUCNI_MS = 1000L

    /** Najmanj slik v dekoderju za zastoj (dekoder, ki eno sliko zadrzi, je normalen). */
    const val ZASTOJ_SLIK_V_DEKODERJU = 3L

    /** Oznaka v tabeli: isti kljuc dvakrat, zato zig ni enoznacen. */
    private const val DVOUMEN_ZIG = Long.MIN_VALUE

    /** Zig okvirja 4: [vrsta] 1 = slika, 2 = zvok; [bajt] = bajtov vsebine te vrste pred okvirjem, na katerega se nanasa. */
    data class Zig(val vrsta: Int, val zigMs: Long, val bajt: Long)

    /**
     * Razclemba okvirja 4 brez org.json (preizkus na JVM brez Androida): raven objekt, kljuci brez ubeznih znakov,
     * vrednosti cela stevila. Null pri vec kot [NAJVEC_ZIG_BAJTOV] B, manjkajocem ali podvojenem polju, vrsti, ki
     * ni 1 ali 2, ne-stevilu (tudi "500" v narekovajih ali 1.5) in negativni vrednosti. Neznana polja se prezrejo.
     */
    fun razcleniZig(telo: ByteArray): Zig? {
        if (telo.isEmpty() || telo.size > NAJVEC_ZIG_BAJTOV) return null
        val polja = razcleniRavenObjekt(String(telo, Charsets.UTF_8)) ?: return null
        val vrsta = celoStevilo(polja["vrsta"]) ?: return null
        val zig = celoStevilo(polja["zig_ms"]) ?: return null
        val bajt = celoStevilo(polja["bajt"]) ?: return null
        if (vrsta != 1L && vrsta != 2L) return null
        if (zig < 0L || bajt < 0L) return null
        return Zig(vrsta.toInt(), zig, bajt)
    }

    /** Zig velja samo za okvir, pred katerim je racunalnik poslal tocno [Zig.bajt] bajtov te vrste. */
    fun zigVelja(zig: Zig, prejetoBajtov: Long): Boolean = zig.bajt == prejetoBajtov

    private fun celoStevilo(s: String?): Long? {
        if (s == null || s.isEmpty()) return null
        val stevke = if (s[0] == '-') s.substring(1) else s
        if (stevke.isEmpty() || !stevke.all { c -> c in '0'..'9' }) return null
        return s.toLongOrNull()
    }

    private fun preskociPresledke(s: String, od: Int): Int {
        var i = od
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    /** Kljuc -> surova vrednost (z narekovaji, ce je niz); null, ce besedilo ni raven objekt. */
    private fun razcleniRavenObjekt(besedilo: String): Map<String, String>? {
        val s = besedilo.trim()
        if (s.length < 2 || s[0] != '{' || s[s.length - 1] != '}') return null
        val polja = HashMap<String, String>()
        var i = preskociPresledke(s, 1)
        if (i == s.length - 1) return polja
        while (i < s.length) {
            if (s[i] != '"') return null
            val konecKljuca = s.indexOf('"', i + 1)
            if (konecKljuca < 0) return null
            val kljuc = s.substring(i + 1, konecKljuca)
            if (kljuc.contains('\\')) return null
            i = preskociPresledke(s, konecKljuca + 1)
            if (i >= s.length || s[i] != ':') return null
            i = preskociPresledke(s, i + 1)
            val zacetek = i
            if (i < s.length && s[i] == '"') {
                val konec = s.indexOf('"', i + 1)
                if (konec < 0 || s.substring(i + 1, konec).contains('\\')) return null
                i = konec + 1
            } else {
                while (i < s.length && s[i] != ',' && s[i] != '}' && !s[i].isWhitespace()) {
                    if (s[i] == '{' || s[i] == '[' || s[i] == '"') return null
                    i++
                }
            }
            val vrednost = s.substring(zacetek, i)
            if (vrednost.isEmpty() || polja.containsKey(kljuc)) return null
            polja[kljuc] = vrednost
            i = preskociPresledke(s, i)
            if (i >= s.length) return null
            if (s[i] == '}') return if (i == s.length - 1) polja else null
            if (s[i] != ',') return null
            i = preskociPresledke(s, i + 1)
        }
        return null
    }

    /**
     * En korak samodejnega zamika (predznak kot [potrebnaZakasnitev]: + = slika kasneje, - = zvok kasneje).
     * Korak je vedno [KORAK_ROCNO_MS] prek [rocniKorak], da popravek ne skace in ne preseze meje.
     */
    fun samodejniKorak(zamikMs: Long, potrebnoMs: Long): Long = when {
        potrebnoMs > 0L -> rocniKorak(zamikMs, true)
        potrebnoMs < 0L -> rocniKorak(zamikMs, false)
        else -> zamikMs
    }

    /** Del zamika, ki zakasni sliko (izris ob casu). */
    fun zamikSlikeMs(zamikMs: Long): Long = maxOf(zamikMs, 0L)

    /** Del zamika, ki zakasni zvok (tisina v vrsti); izpust ga ne sme steti kot zaostanek. */
    fun zamikZvokaMs(zamikMs: Long): Long = maxOf(-zamikMs, 0L)

    /** Ali je od zadnjega koraka minilo dovolj casa, da meritev ze kaze novo stanje. */
    fun smeMeriti(zdajNs: Long, zadnjiKorakNs: Long?): Boolean =
        zadnjiKorakNs == null || zdajNs - zadnjiKorakNs >= MIROVANJE_PO_KORAKU_MS * 1_000_000L

    /**
     * Kateri okvir zvoka se predvaja ob [nanoCilj] (System.nanoTime), iz AudioTrack.getTimestamp
     * ([tsOkvir] predvajan ob [tsNano]). Null pri neveljavni frekvenci ali prestarem zigu.
     */
    fun okvirOb(nanoCilj: Long, tsOkvir: Long, tsNano: Long, hz: Int): Long? {
        if (hz <= 0) return null
        val razlikaNs = nanoCilj - tsNano
        if (razlikaNs > NAJVEC_STAROST_ZIGA_NS || razlikaNs < -NAJVEC_STAROST_ZIGA_NS) return null
        return tsOkvir + razlikaNs * hz / 1_000_000_000L
    }

    /**
     * Zig zajema okvirja zvoka [okvir] iz odseka, ki se zacne pri zapisanem okvirju [odsekOkvir] z zigom [odsekZig].
     * Null, ce okvir ni v odseku ali je dlje od [NAJVEC_OD_ODSEKA_MS].
     */
    fun zigOkvirja(okvir: Long, hz: Int, odsekOkvir: Long, odsekZig: Long): Long? {
        if (hz <= 0) return null
        val odOdseka = okvir - odsekOkvir
        if (odOdseka < 0L || odOdseka > hz.toLong() * NAJVEC_OD_ODSEKA_MS / 1000L) return null
        return odsekZig + odOdseka * 1000L / hz
    }

    /** Pts izhoda iz dekoderja ustreza kljucu [kljucUs], ki je bil prebran najvec 1 ms pred oddajo v dekoder. */
    fun ujemaPts(ptsUs: Long, kljucUs: Long): Boolean = ptsUs - kljucUs in 0L..1000L

    /** Zastoj slike: slike gredo v dekoder, ven pa nic ze [ZASTOJ_ZA_KLJUCNI_MS]. */
    fun jeZastojSlike(zdajMs: Long, zadnjiIzhodMs: Long, slikVDekoderju: Long): Boolean =
        slikVDekoderju >= ZASTOJ_SLIK_V_DEKODERJU && zdajMs - zadnjiIzhodMs >= ZASTOJ_ZA_KLJUCNI_MS

    /** Enota kljucne slike: H.264 IDR (5), HEVC IRAP (16..21: BLA, IDR, CRA). */
    fun jeKljucnaEnota(hevc: Boolean, vrstaEnote: Int): Boolean =
        if (hevc) vrstaEnote in 16..21 else vrstaEnote == 5

    /** Kljuc za shranjeni rocni zamik zvoka za dolocen zaslon (kot rob: samo ASCII crke in stevilke). */
    fun kljucZamika(zaslon: String): String =
        if (zaslon.isNotEmpty() && zaslon.all { c -> c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z' }) {
            "zaslon_zvok_zamik_$zaslon"
        } else {
            "zaslon_zvok_zamik_privzeto"
        }

    /**
     * Pari kljuc -> zig zajema (pts slike -> zig, zapisan okvir zvoka -> zig) v krozni tabeli.
     * Isti kljuc z drugim zigom (dve sliki v isti ms) oznaci kot dvoumen, da meritev ne vzame ziga druge slike.
     * Sinhronizirano: pise nit toka, bere nit prikaza (OnFrameRenderedListener).
     */
    class ZigTabela(val velikost: Int = 512) {
        private val kljuci = LongArray(velikost.also { require(it >= 1) { "velikost mora biti vsaj 1" } })
        private val zigi = LongArray(velikost)
        private var stevilo = 0
        private var naslednji = 0

        @Synchronized
        fun dodaj(kljuc: Long, zig: Long) {
            for (i in 0 until stevilo) {
                if (kljuci[i] == kljuc) {
                    // Ista slika (vec rezin v isti ms) ostane veljavna, razlicni sliki ne.
                    if (zigi[i] != zig) zigi[i] = DVOUMEN_ZIG
                    return
                }
            }
            kljuci[naslednji] = kljuc
            zigi[naslednji] = zig
            naslednji = (naslednji + 1) % velikost
            if (stevilo < velikost) stevilo++
        }

        /** Zig za natanko ta kljuc ali null (tudi, ce je kljuc dvoumen). */
        @Synchronized
        fun natancno(kljuc: Long): Long? {
            for (i in 0 until stevilo) {
                if (kljuci[i] == kljuc) return if (zigi[i] == DVOUMEN_ZIG) null else zigi[i]
            }
            return null
        }

        /** Veljaven vnos z najvecjim kljucem, ki ni vecji od [kljuc] (kljuc vnosa to zig), ali null. */
        @Synchronized
        fun navzdol(kljuc: Long): Pair<Long, Long>? {
            var najden = -1
            for (i in 0 until stevilo) {
                if (zigi[i] != DVOUMEN_ZIG && kljuci[i] <= kljuc && (najden < 0 || kljuci[i] > kljuci[najden])) najden = i
            }
            return if (najden < 0) null else kljuci[najden] to zigi[najden]
        }

        @Synchronized
        fun ponastavi() {
            stevilo = 0
            naslednji = 0
        }
    }

    /**
     * Zanka usklajevanja: meritve -> mediana -> potrebnaZakasnitev -> en korak [samodejniKorak].
     * Po vsakem koraku okno pozabi stare meritve in pocaka [MIROVANJE_PO_KORAKU_MS] (pregled S2: brez tega
     * mediana se ~8 meritev kaze star odmik in popravek se uveljavi veckrat - preseganje).
     */
    class Krmilnik(velikostOkna: Int = 15) {
        private val glajenje = Glajenje(velikostOkna)
        private var zadnjiKorakNs: Long? = null

        /** Trenutni samodejni zamik (+ = slika kasneje, - = zvok kasneje). */
        var zamikMs = 0L
            private set

        /** Doda meritev ob [zdajNs]; vrne nov zamik, ce se je spremenil, sicer null. */
        fun meritev(odmikMs: Long, zdajNs: Long, rocniZamikMs: Long): Long? {
            if (!smeMeriti(zdajNs, zadnjiKorakNs)) return null
            glajenje.dodaj(odmikMs)
            val mediana = glajenje.mediana() ?: return null
            val potrebno = potrebnaZakasnitev(mediana, omejiRocniZamik(rocniZamikMs))
            if (potrebno == 0L) return null
            val nov = samodejniKorak(zamikMs, potrebno)
            glajenje.ponastavi()
            zadnjiKorakNs = zdajNs
            if (nov == zamikMs) return null
            zamikMs = nov
            return nov
        }

        /** Nova seja ali nov AudioTrack: zamik in meritve od zacetka. */
        fun ponastavi() {
            glajenje.ponastavi()
            zadnjiKorakNs = null
            zamikMs = 0L
        }

        /** Meritve so neveljavne (npr. izpust zvoka), zamik ostane. */
        fun pozabiMeritve() {
            glajenje.ponastavi()
        }
    }
}
