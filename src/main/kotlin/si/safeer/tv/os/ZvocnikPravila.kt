package si.safeer.tv.os

/**
 * Predvajalnik upravlja zvocnik v omrezju (UPnP AV): cista pravila brez Androida (preizkus: tests/ZvocnikPravilaTest.kt).
 * Ko glasba igra na zvocniku, glavni gumbi predvajalnika (predvajaj/premor, drsnik, +-10 s, naprej/nazaj, glasnost),
 * obvestilo in zaklenjeni zaslon upravljajo zvocnik; ta naprava je daljinec.
 *
 * Izmerjeno z mikrofonom na zvocniku z AVTransport:1 (7. 10. 2026):
 *  - polozaj javlja v celih sekundah;
 *  - preskok med predvajanjem pristane tocno, zvok s cilja se zaslisi priblizno 1,8 s po ukazu (tudi vec zaporednih);
 *  - preskok MED PREMOROM sam nadaljuje predvajanje in pristane priblizno 5 s pred ciljem; enako preskok takoj po Play;
 *    Play, 0,4 s premora in sele nato preskok pa pristane tocno;
 *  - preskok pred Play (ustavljen zvocnik) sprejme, a ga ne uposteva;
 *  - premor med nalaganjem skladbe (TRANSITIONING) zavrne (UPnP 701), med pristajanjem po preskoku pa sprejme.
 * Zato preskok posljemo samo med potrjenim predvajanjem. Preskok, izbran med premorom ali preden skladba stece, pocaka:
 * zaslon ze kaze cilj, zvocnik ga dobi po nadaljevanju. Premor, izbran med nalaganjem, zvocnik dobi, ko skladba stece.
 */
object ZvocnikPravila {
    /** Po Play pocakamo toliko, preden posljemo preskok. */
    const val PREMOR_PRED_PRESKOKOM_MS = 400L
    /** Zvok s ciljnega mesta se po ukazu za preskok zaslisi po priblizno toliko. */
    const val PRISTANEK_MS = 1_800L
    /** Predvajanje se po Play med premorom nadaljuje po priblizno toliko. */
    const val NADALJEVANJE_MS = 200L
    /** Po nasem ukazu odcitki toliko casa ne spreminjajo stanja (zvocnik se javlja staro ali prehodno stanje). */
    const val MIR_PO_UKAZU_MS = 2_500L
    /**
     * Ustavljen zvocnik pomeni konec skladbe samo, ce je bila skladba toliko pred koncem ali blizje: 5 % trajanja, najmanj
     * [KONEC_OKNO_MS] in najvec [NAJVEC_OKNO_KONCA_MS]. Zvocnik trajanje ocenjuje (izmerjeno 7. 10. 2026: skladba 3:24 je bila
     * po njegovem dolga 3:33); s tremi sekundami bi tak konec veljal za prevzem in vrsta bi obstala.
     */
    const val KONEC_OKNO_MS = 3_000L
    const val NAJVEC_OKNO_KONCA_MS = 15_000L

    fun oknoKonca(trajanjeMs: Long): Long = maxOf(KONEC_OKNO_MS, minOf(trajanjeMs / 20, NAJVEC_OKNO_KONCA_MS))
    /** Skladba neznanega trajanja se je »koncala« samo, ce je prej igrala vsaj toliko (sicer se sploh ni predvajala). */
    const val NAJMANJ_ZA_KONEC_MS = 5_000L
    /** Preskok ne gre v zadnji del skladbe (takoj bi se koncala). */
    const val ROB_KONCA_MS = 1_500L
    /** »Prejsnja« po toliko casa skoci na zacetek skladbe, prej na prejsnjo skladbo. */
    const val NAZAJ_NA_ZACETEK_MS = 5_000L
    /** Ob prenosu na zvocnik (in nazaj) skladba nadaljuje na istem mestu, ce je ze toliko v njej. */
    const val NAJMANJ_ZA_NADALJEVANJE_MS = 5_000L
    const val INTERVAL_ZAGON_MS = 500L
    const val INTERVAL_IGRA_MS = 2_000L
    const val INTERVAL_PREMOR_MS = 4_000L
    /** Toliko zaporednih odcitkov »ustavljen«, preden skladba sploh stece, pomeni, da je zvocnik ne zmore. */
    const val NAJVEC_BREZ_ZAGONA = 12
    /** Skladba, ki v tem casu po Play ne stece (nalaganje brez konca), ne bo stekla. */
    const val NAJVEC_ZAGON_MS = 20_000L
    /**
     * Zvocnik je nedosegljiv, ko toliko zaporednih odcitkov ne uspe v vsaj toliko budnega casa naprave (tik po prebujanju
     * naprave omrezja hip ni, zvocnik pa igra naprej). Seja ostane; odcitki so takrat redkejsi.
     */
    const val NEDOSEGLJIV_PO_ODCITKIH = 3
    const val NEDOSEGLJIV_PO_MS = 20_000L
    const val INTERVAL_NEDOSEGLJIV_MS = 5_000L
    /**
     * Ustavljen zvocnik pri koncu skladbe pomeni »skladba se je koncala - poslji naslednjo« samo, ce zvocnik molci najvec
     * toliko casa: od pricakovanega konca skladbe, kadar je trajanje znano (zvocnik med izpadom omrezja igra naprej), sicer
     * od zadnjega stika z njim ([Stanje.slisanMs]). Po daljsem molku (zvocnik ni bil dosegljiv, naprava je spala ali sla iz
     * omrezja) se je skladba koncala ze zdavnaj: naslednje ne posljemo - glasba ne sme zaceti sama cez ure, v praznem
     * stanovanju ali cez vhod, ki ga zvocnik medtem uporablja za kaj drugega.
     */
    const val NAJVEC_VRZEL_ZA_KONEC_MS = 90_000L
    /** Nedosegljivemu zvocniku ostane naprava na voljo (budna) najvec toliko od njegovega zadnjega odgovora. */
    const val NEDOSEGLJIV_BUDNOST_MS = 15 * 60_000L
    /** Naslov, ki ni nas poslani, vzamemo za nasega samo, ce ga zvocnik javi najvec toliko po nasem Play ([cigavNaslov]). */
    const val POTRDITEV_NASLOVA_MS = 30_000L
    /** Premor, ki ga zvocnik ne izvede, posljemo znova najvec tolikokrat; potem zvocnik ustavimo (Stop). */
    const val NAJVEC_PONOVITEV_PREMORA = 3
    /** Naprava ostane budna, dokler zvocnik premora ne potrdi - najvec toliko. */
    const val PREMOR_BUDNOST_MS = 60_000L

    /**
     * Stanje predvajanja na zvocniku, kot ga vidi uporabnik. Casi so milisekunde ure, ki tece tudi med spanjem naprave.
     * [polozajMs] velja ob [veljaOd] (ta je lahko v prihodnosti: zvok po preskoku se pristaja); med predvajanjem
     * polozaj od takrat tece sam. [cakajociPreskokMs] >= 0: preskok, ki ga zvocnik se ni dobil.
     */
    data class Stanje(
        val zeliIgrati: Boolean = true,
        val polozajMs: Long = 0L,
        val veljaOd: Long = 0L,
        val trajanjeMs: Long = 0L,
        val cakajociPreskokMs: Long = -1L,
        val mirDoMs: Long = 0L,
        /** Zvocnik je predvajanje te skladbe ze potrdil. */
        val igral: Boolean = false,
        val brezZagona: Int = 0,
        /** Kdaj je bil skladbi poslan (prvi) Play - od takrat tece rok [NAJVEC_ZAGON_MS]. */
        val zacetekMs: Long = 0L,
        /** Zvocnik se ne odziva (odcitki ne uspejo): seja traja, zvocnik igra naprej, ukazi pa morda ne pridejo do njega. */
        val nedosegljiv: Boolean = false,
        /**
         * Trajanje je znano s te naprave (njen predvajalnik je skladbo ze igral): zvocnikova ocena, ki se med predvajanjem
         * spreminja (izmerjeno: 5:55, 3:42, 3:30, 3:33 za skladbo 3:24), ga ne povozi.
         */
        val trajanjeZnano: Boolean = false,
        /** Premor je narocen (ob [premorOdMs]), zvocnik ga se ni potrdil; [ponovitevPremora]: kolikokrat smo ga poslali znova. */
        val premorNepotrjen: Boolean = false,
        val premorOdMs: Long = 0L,
        val ponovitevPremora: Int = 0,
        /** Zadnji stik z zvocnikom (uspel odcitek, sprejeta skladba, nadaljevanje) - glej [NAJVEC_VRZEL_ZA_KONEC_MS]. */
        val slisanMs: Long = 0L,
        /** Kdaj je bil tej skladbi nazadnje poslan Play; -1 = se nikoli (samo nalozena med premorom) - glej [cigavNaslov]. */
        val playOdMs: Long = -1L,
    )

    /**
     * PREMOR: zvocnik naj dobi premor zdaj (skladba je stekla, uporabnik je premor izbral ze prej; ali pa premora se ni
     * izvedel). USTAVI: premora ne izvede niti po ponovitvah - ustavimo ga (Stop) in sejo koncamo. KONEC_POZEN: skladba se
     * je koncala, a tega nismo videli sproti ([NAJVEC_VRZEL_ZA_KONEC_MS]) - seja se konca, zvocnik ne dobi nicesar.
     */
    enum class Dejanje { NIC, PRESKOK, PREMOR, KONEC_SKLADBE, KONEC_POZEN, PREVZET, NI_ZACELO, USTAVI }

    data class Odlocitev(val stanje: Stanje, val dejanje: Dejanje = Dejanje.NIC, val ciljMs: Long = -1L)

    /** Polozaj za zaslon, sejo in obvestilo. */
    fun polozajZdaj(s: Stanje, zdaj: Long): Long {
        if (s.cakajociPreskokMs >= 0L) return s.cakajociPreskokMs
        val p = if (s.zeliIgrati && s.igral && zdaj > s.veljaOd) s.polozajMs + (zdaj - s.veljaOd) else s.polozajMs
        return if (s.trajanjeMs > 0L) p.coerceIn(0L, s.trajanjeMs) else p.coerceAtLeast(0L)
    }

    /** Mesto, na katerem skladba nadaljuje ob prenosu med napravo in zvocnikom (0 = od zacetka). */
    fun odPolozaja(polozajMs: Long, trajanjeMs: Long, vZivo: Boolean): Long = when {
        vZivo || polozajMs < NAJMANJ_ZA_NADALJEVANJE_MS -> 0L
        trajanjeMs > 0L && polozajMs > trajanjeMs - NAJMANJ_ZA_NADALJEVANJE_MS -> 0L
        else -> polozajMs
    }

    /**
     * Nova skladba na zvocniku. [odMs]: mesto, na katerem naj nadaljuje (glej [odPolozaja]). [igraj] = false: skladba je
     * zvocniku samo nalozena (naprej/nazaj med premorom) - Play dobi ob nadaljevanju.
     */
    fun zacetek(zdaj: Long, odMs: Long = 0L, trajanjeMs: Long = 0L, igraj: Boolean = true): Stanje = Stanje(
        zeliIgrati = igraj, polozajMs = 0L, veljaOd = zdaj, trajanjeMs = trajanjeMs.coerceAtLeast(0L), trajanjeZnano = trajanjeMs > 0L,
        cakajociPreskokMs = if (odMs >= NAJMANJ_ZA_NADALJEVANJE_MS) odMs else -1L, mirDoMs = zdaj + MIR_PO_UKAZU_MS, zacetekMs = zdaj,
        slisanMs = zdaj, playOdMs = if (igraj) zdaj else -1L)

    /**
     * Pravo trajanje skladbe, izvedeno naknadno (iz glave datoteke): odslej velja namesto ocene zvocnika. Skladbi, ki se
     * zacne neposredno na zvocniku (naslednja v vrsti, nova izbira med sejo), ga ta naprava prej ne pozna. Neznano
     * trajanje, prenos v zivo ali trajanje, ki ga javljeni polozaj ze presega, ne spremeni nicesar.
     */
    fun sTrajanjem(s: Stanje, trajanjeMs: Long, vZivo: Boolean): Stanje =
        if (vZivo || trajanjeMs <= 0L || s.polozajMs > trajanjeMs + 2_000L) s else s.copy(trajanjeMs = trajanjeMs, trajanjeZnano = true)

    /** Premor velja na zaslonu takoj; zvocnik ga mora se potrditi ([Stanje.premorNepotrjen], glej [poOdcitku]). */
    fun premor(s: Stanje, zdaj: Long): Stanje = if (!s.zeliIgrati) s else s.copy(zeliIgrati = false,
        polozajMs = polozajZdaj(s.copy(cakajociPreskokMs = -1L), zdaj), veljaOd = zdaj, mirDoMs = zdaj + MIR_PO_UKAZU_MS,
        premorNepotrjen = true, premorOdMs = zdaj, ponovitevPremora = 0)

    /** Nadaljevanje po premoru; [Dejanje.PRESKOK]: po Play (in premoru [PREMOR_PRED_PRESKOKOM_MS]) poslji se preskok. */
    fun nadaljuj(s: Stanje, zdaj: Long): Odlocitev {
        if (s.zeliIgrati) return Odlocitev(s)                               // ze igra: nic (cas se ne sme premakniti nazaj)
        // Nadaljevanje je nov stik z zvocnikom: premor, ki je trajal ure, ne sme veljati za »molk« (R6-A1a).
        if (s.cakajociPreskokMs >= 0L && s.igral) return izvediCakajoci(s.copy(zeliIgrati = true, premorNepotrjen = false, ponovitevPremora = 0, playOdMs = zdaj, slisanMs = zdaj), zdaj)
        // Skladba, ki se ni stekla (nalozena med premorom), dobi zdaj svoj prvi Play: rok zagona tece od tu.
        return Odlocitev(s.copy(zeliIgrati = true, premorNepotrjen = false, ponovitevPremora = 0, veljaOd = zdaj + NADALJEVANJE_MS, mirDoMs = zdaj + MIR_PO_UKAZU_MS,
            zacetekMs = if (s.igral) s.zacetekMs else zdaj, brezZagona = 0, playOdMs = zdaj, slisanMs = zdaj))
    }

    /** Uporabnik je izbral novo mesto (drsnik, +-10 s). [Dejanje.PRESKOK]: poslji zdaj; sicer preskok pocaka. */
    fun preskok(s: Stanje, ciljMs: Long, zdaj: Long): Odlocitev {
        if (s.trajanjeMs <= 0L) return Odlocitev(s)                         // v zivo ali neznano trajanje
        val cilj = omeji(ciljMs, s.trajanjeMs)
        if (!s.zeliIgrati || !s.igral) return Odlocitev(s.copy(cakajociPreskokMs = cilj))
        val pristane = zdaj + PRISTANEK_MS
        return Odlocitev(s.copy(polozajMs = cilj, veljaOd = pristane, cakajociPreskokMs = -1L, mirDoMs = pristane + 700L), Dejanje.PRESKOK, cilj)
    }

    private fun omeji(ciljMs: Long, trajanjeMs: Long): Long =
        if (trajanjeMs > 0L) ciljMs.coerceIn(0L, (trajanjeMs - ROB_KONCA_MS).coerceAtLeast(0L)) else ciljMs.coerceAtLeast(0L)

    private fun izvediCakajoci(n: Stanje, zdaj: Long): Odlocitev {
        val cilj = omeji(n.cakajociPreskokMs, n.trajanjeMs)
        val pristane = zdaj + PREMOR_PRED_PRESKOKOM_MS + PRISTANEK_MS
        return Odlocitev(n.copy(polozajMs = cilj, veljaOd = pristane, cakajociPreskokMs = -1L, mirDoMs = pristane + 700L), Dejanje.PRESKOK, cilj)
    }

    /** Zvocnik javlja cele sekunde: odcitek, ki se z nasim casom ujema na sekundo, casa ne premakne (ne skace nazaj). */
    private fun popraviPolozaj(n: Stanje, relMs: Long, zdaj: Long): Stanje {
        if (n.cakajociPreskokMs >= 0L || relMs < 0L) return n
        val nas = polozajZdaj(n, zdaj)
        return if (relMs <= nas && nas < relMs + 1_000L) n else n.copy(polozajMs = relMs, veljaOd = zdaj)
    }

    /**
     * Odcitek stanja zvocnika (GetTransportInfo + GetPositionInfo). [svez] = od narocila odcitka ni bilo nasega ukaza
     * (sicer je odcitek zastarel). [vZivo]: radio ali prenos v zivo (trajanja nima, tudi ce ga zvocnik javi).
     * Ustavljen zvocnik je konec skladbe samo, ce je bila skladba pri koncu; sicer ga je prevzel kdo drug (drug vhod,
     * druga naprava) in mu naslednje skladbe ne smemo poslati - prekinili bi mu, kar zdaj predvaja.
     */
    fun poOdcitku(prej: Stanje, prenos: String, relMs: Long, trajMs: Long, zdaj: Long, svez: Boolean, vZivo: Boolean): Odlocitev {
        // Vsak uspel odcitek (tudi zastarel) pove, da je zvocnik ta hip dosegljiv; odlocitev gleda prejsnji odgovor.
        val o = odlociPoOdcitku(prej, prenos, relMs, trajMs, zdaj, svez, vZivo)
        return o.copy(stanje = o.stanje.copy(slisanMs = zdaj))
    }

    private fun odlociPoOdcitku(prej: Stanje, prenos: String, relMs: Long, trajMs: Long, zdaj: Long, svez: Boolean, vZivo: Boolean): Odlocitev {
        if (!svez) return Odlocitev(prej)
        // Trajanje: radio ga nima; znano s te naprave ima prednost pred oceno zvocnika - dokler ga javljeni polozaj ne
        // preseze (potem se je izkazalo za napacno in velja, kar javi zvocnik). Javljeno trajanje, ki ga polozaj presega, ne velja.
        val znano = prej.trajanjeZnano && prej.trajanjeMs > 0L && relMs <= prej.trajanjeMs + 2_000L
        val s = if (prej.trajanjeZnano && !znano) prej.copy(trajanjeZnano = false) else prej
        var t = if (vZivo) 0L else if (znano) s.trajanjeMs else if (trajMs > 0L) trajMs else s.trajanjeMs
        if (t > 0L && relMs > t + 2_000L) t = 0L
        when (prenos) {
            "PLAYING" -> {
                var n = s.copy(trajanjeMs = t, igral = true, brezZagona = 0)
                if (!s.igral) {
                    // Skladba je pravkar stekla: od zdaj cas tece.
                    n = n.copy(polozajMs = relMs.coerceAtLeast(0L), veljaOd = zdaj)
                    // Premor, izbran med nalaganjem (zvocnik ga takrat zavrne), velja zdaj.
                    if (!s.zeliIgrati) return Odlocitev(n.copy(mirDoMs = zdaj + MIR_PO_UKAZU_MS), Dejanje.PREMOR)
                    return if (s.cakajociPreskokMs >= 0L) izvediCakajoci(n, zdaj) else Odlocitev(n)
                }
                // Preskok, ki caka in ga predvajanje ze dovoli, ne sme obviseti.
                if (s.zeliIgrati && s.cakajociPreskokMs >= 0L) return izvediCakajoci(n, zdaj)
                if (zdaj < s.mirDoMs) return Odlocitev(n)
                if (!s.zeliIgrati && s.premorNepotrjen) {
                    // Premora zvocnik ni izvedel (zavrnjen med nalaganjem, izgubljen ukaz, tok v zivo): posljemo ga znova; po
                    // nekaj poskusih zvocnik ustavimo - uporabnik (ali casovnik izklopa) je hotel tisino.
                    return if (s.ponovitevPremora < NAJVEC_PONOVITEV_PREMORA)
                        Odlocitev(n.copy(ponovitevPremora = s.ponovitevPremora + 1, mirDoMs = zdaj + MIR_PO_UKAZU_MS), Dejanje.PREMOR)
                    else Odlocitev(n, Dejanje.USTAVI)
                }
                if (!s.zeliIgrati) {
                    // Nekdo je nadaljeval na zvocniku samem (njegov daljinec, druga naprava): sledimo zvocniku.
                    n = n.copy(zeliIgrati = true, polozajMs = relMs.coerceAtLeast(0L), veljaOd = zdaj)
                    return if (s.cakajociPreskokMs >= 0L) izvediCakajoci(n, zdaj) else Odlocitev(n)
                }
                return Odlocitev(popraviPolozaj(n, relMs, zdaj))
            }
            "PAUSED_PLAYBACK" -> {
                var n = s.copy(trajanjeMs = t, igral = true, brezZagona = 0, premorNepotrjen = false, ponovitevPremora = 0)
                if (zdaj < s.mirDoMs) return Odlocitev(n)
                if (s.zeliIgrati) return Odlocitev(n.copy(zeliIgrati = false, polozajMs = if (relMs >= 0L) relMs else polozajZdaj(s, zdaj), veljaOd = zdaj))
                if (s.cakajociPreskokMs < 0L && relMs >= 0L && kotlin.math.abs(s.polozajMs - relMs) > 1_000L) n = n.copy(polozajMs = relMs, veljaOd = zdaj)
                return Odlocitev(n)
            }
            "STOPPED", "NO_MEDIA_PRESENT" -> {
                if (!s.igral) {
                    if (!s.zeliIgrati) return Odlocitev(s)                  // nalozena med premorom: zvocnik caka na Play
                    val n = s.copy(brezZagona = s.brezZagona + 1)
                    val niSteklo = n.brezZagona >= NAJVEC_BREZ_ZAGONA || zdaj - s.zacetekMs > NAJVEC_ZAGON_MS
                    return Odlocitev(n, if (niSteklo) Dejanje.NI_ZACELO else Dejanje.NIC)
                }
                if (zdaj < s.mirDoMs) return Odlocitev(s)
                val polozaj = polozajZdaj(s.copy(cakajociPreskokMs = -1L), zdaj)
                val priKoncu = if (s.trajanjeMs > 0L) polozaj >= s.trajanjeMs - oknoKonca(s.trajanjeMs) else !vZivo && polozaj >= NAJMANJ_ZA_KONEC_MS
                if (!priKoncu || !s.zeliIgrati) return Odlocitev(s, Dejanje.PREVZET)
                // Pri koncu skladbe. Polozaj je izracunan iz casa (po dolgi vrzeli je vedno »pri koncu«): ce zvocnik molci ze
                // dlje, je konec lahko star ure - naslednje skladbe ne posljemo ([NAJVEC_VRZEL_ZA_KONEC_MS]).
                val tisinaMs = if (s.trajanjeMs > 0L) zdaj - (s.veljaOd + (s.trajanjeMs - s.polozajMs)) else zdaj - s.slisanMs
                return Odlocitev(s, if (tisinaMs > NAJVEC_VRZEL_ZA_KONEC_MS) Dejanje.KONEC_POZEN else Dejanje.KONEC_SKLADBE)
            }
            else -> {
                // TRANSITIONING (nalaganje, prehod) ali stanje, ki ga ne poznamo: nicesar ne sklepamo - razen da skladba,
                // ki po roku se vedno ni stekla, ne bo stekla.
                if (!s.igral && s.zeliIgrati && zdaj - s.zacetekMs > NAJVEC_ZAGON_MS) return Odlocitev(s, Dejanje.NI_ZACELO)
                return Odlocitev(if (prenos == "TRANSITIONING") s.copy(trajanjeMs = t, brezZagona = 0) else s)
            }
        }
    }

    /** NAS: nic ne sklepamo. POTRDI: nasa skladba igra pod tem naslovom - zapomnimo si ga. TUJ: zvocnik predvaja nekaj drugega. */
    enum class Naslov { NAS, POTRDI, TUJ }

    /**
     * Cigav je naslov skladbe, ki ga zvocnik javlja ([javljen]) v stanju [prenos]. [poslan]: naslov, ki smo ga poslali;
     * [potrjen]: naslov, pod katerim je nasa skladba ze stekla ("" = se ni). Nekateri zvocniki javijo drugacnega od
     * poslanega (preusmeritev), zato drugacen naslov vzamemo za nasega - a samo, ko zvocnik zaigra tik po NASEM Play.
     * Skladba, ki je zvocniku samo nalozena (naprej/nazaj med premorom, Play ni bil poslan), ne more igrati: ce zvocnik
     * takrat igra pod drugim naslovom, ga je prevzel kdo drug (drug vhod, druga naprava). Prej je seja tak naslov vzela
     * za svojega, tujemu predvajanju poslala Pause in mu sledila (tretji pregled, A6).
     */
    fun cigavNaslov(javljen: String, poslan: String, potrjen: String, prenos: String, s: Stanje, zdaj: Long): Naslov {
        if (javljen.isBlank()) return Naslov.NAS
        if (potrjen.isNotEmpty()) return if (javljen == poslan || javljen == potrjen) Naslov.NAS else Naslov.TUJ
        if (prenos != "PLAYING") return Naslov.NAS                 // nalaganje, premor: nic ne sklepamo in nic ne posljemo
        if (javljen == poslan) return Naslov.POTRDI
        return if (s.playOdMs >= 0L && zdaj - s.playOdMs <= POTRDITEV_NASLOVA_MS) Naslov.POTRDI else Naslov.TUJ
    }

    /** Cez koliko naslednji odcitek: med premorom redko, ob zagonu pogosto, tik pred koncem skladbe ob njenem koncu. */
    fun intervalMs(s: Stanje, zdaj: Long): Long {
        if (s.nedosegljiv) return INTERVAL_NEDOSEGLJIV_MS
        if (!s.zeliIgrati) return INTERVAL_PREMOR_MS
        if (!s.igral) return INTERVAL_ZAGON_MS
        if (s.trajanjeMs > 0L && s.cakajociPreskokMs < 0L) {
            val preostalo = s.trajanjeMs - polozajZdaj(s, zdaj)
            if (preostalo + 300L < INTERVAL_IGRA_MS) return (preostalo + 300L).coerceAtLeast(300L)
        }
        return INTERVAL_IGRA_MS
    }

    /** Sprememba, ki jo morajo videti seja, obvestilo in zasloni (drobnih popravkov casa ne). */
    fun bistvena(prej: Stanje, potem: Stanje, zdaj: Long): Boolean =
        prej.zeliIgrati != potem.zeliIgrati || prej.trajanjeMs != potem.trajanjeMs || prej.igral != potem.igral ||
            prej.nedosegljiv != potem.nedosegljiv ||
            prej.cakajociPreskokMs != potem.cakajociPreskokMs || kotlin.math.abs(polozajZdaj(prej, zdaj) - polozajZdaj(potem, zdaj)) > 1_500L

    /**
     * Odcitek stanja zvocnika ni uspel ([neuspehov] zaporednih s tem vred, [odPrvegaMs] budnega casa od prvega). Seja se
     * NE konca: zvocnik igra naprej in ko se spet oglasi, ga upravljamo naprej. Prej smo sejo po 20 s koncali - zvocnik je
     * igral brez daljinca, »predvajaj« pa je isto zacel se na tej napravi.
     */
    fun poNeuspehu(s: Stanje, neuspehov: Int, odPrvegaMs: Long): Stanje =
        if (!s.nedosegljiv && neuspehov >= NEDOSEGLJIV_PO_ODCITKIH && odPrvegaMs >= NEDOSEGLJIV_PO_MS) s.copy(nedosegljiv = true) else s

    /** Odcitek je (spet) uspel. */
    fun poUspehu(s: Stanje): Stanje = if (s.nedosegljiv) s.copy(nedosegljiv = false) else s

    /**
     * Kaj sme na zvocnik: zvok, ki ga zvocnik lahko potegne sam ali mu ga ponudi ta naprava. Slika ne. Seznam kosov (HLS,
     * DASH) tudi ne: zvocnik naslov sprejme, predvajati ga ne zna - nova izbira bi bila tiho, dokler ne obupamo.
     */
    fun primerna(zvok: String, video: Boolean, mime: String = ""): Boolean {
        if (video || !DlnaPravila.zaZvocnik(zvok)) return false
        val pot = zvok.substringBefore('?').substringBefore('#').lowercase()
        val vrsta = mime.lowercase()
        return !(pot.endsWith(".m3u8") || pot.endsWith(".mpd") || vrsta.contains("mpegurl") || vrsta.contains("dash+xml"))
    }

    fun nazajNaZacetek(polozajMs: Long, imaPrejsnjo: Boolean): Boolean = polozajMs > NAZAJ_NA_ZACETEK_MS || !imaPrejsnjo

    // ------------------------------------------------------------------ glasnost
    // Zvocnik je lahko veliko glasnejsi od telefona, lestvice pa so razlicne (0..100, 0..40, 0..15 ...): koraki so delez
    // lestvice, navzgor pa gre glasnost vedno po malem (drzana tipka, tipka v zepu, hiter poteg ne smejo poskociti).

    // [znana] = false: zvocnik svoje lestvice ni povedal (ali je nismo mogli prebrati). Takrat stejemo 0..100, a po
    // najmanjsih korakih - na zvocniku z lestvico 0..30 bi koraki za 0..100 v nekaj sekundah pripeljali do najvecje glasnosti.

    /** Tipka za glasnost: en korak (priblizno 2 % lestvice). */
    fun korakGlasnosti(najvec: Int, znana: Boolean = true): Int = if (!znana) 1 else (najvec / 50).coerceAtLeast(1)

    /** Navzgor najvec toliko naenkrat (priblizno 4 % lestvice). */
    fun najvecDvig(najvec: Int, znana: Boolean = true): Int = if (!znana) 1 else ((najvec + 12) / 25).coerceAtLeast(1)

    /** Poteg cez ves zaslon spremeni glasnost zvocnika za toliko (dve petini lestvice, ne vsa). */
    fun obsegKretnje(najvec: Int, znana: Boolean = true): Int = if (!znana) 10 else (najvec * 2 / 5).coerceAtLeast(2)

    fun novaGlasnost(zdaj: Int, sprememba: Int, najvec: Int, znana: Boolean = true): Int =
        (zdaj + sprememba.coerceAtMost(najvecDvig(najvec, znana))).coerceIn(0, najvec.coerceAtLeast(1))

    /** Korak proti izbrani glasnosti (drsnik sistema, kretnja): navzgor najvec [najvecDvig] naenkrat, navzdol takoj. */
    fun korakProtiCilju(zdaj: Int, cilj: Int, najvec: Int, znana: Boolean = true): Int {
        val c = cilj.coerceIn(0, najvec.coerceAtLeast(1))
        return if (c > zdaj) minOf(c, zdaj + najvecDvig(najvec, znana)) else c
    }

    /** [delez]: poteg navzgor (+) ali navzdol (-) kot delez visine zaslona, -1..1. */
    fun glasnostIzKretnje(zacetek: Int, delez: Float, najvec: Int, znana: Boolean = true): Int =
        (zacetek + Math.round(delez.coerceIn(-1f, 1f) * obsegKretnje(najvec, znana))).coerceIn(0, najvec.coerceAtLeast(1))

    /**
     * Spremembe glasnosti, ki cakajo na delavca: tipka (korak) ali cilj (drsnik sistema, kretnja). Tipka cilj preklice -
     * »tisje« med dvigovanjem proti cilju mora veljati takoj. Prej je tipka, pritisnjena med dvema korakoma dviga, izginila
     * (cilj je imel prednost) in glasnost je sla se navzgor.
     */
    class CakajocaGlasnost {
        private var sprememba = 0
        private var cilj = -1

        @Synchronized fun tipka(korak: Int) { cilj = -1; sprememba += korak }
        @Synchronized fun naCilj(vrednost: Int) { cilj = vrednost.coerceAtLeast(0); sprememba = 0 }

        /** Delavec vzame, kar caka: (sprememba, cilj); cilj -1 = ni cilja. */
        @Synchronized fun vzemi(): Pair<Int, Int> { val par = sprememba to cilj; sprememba = 0; cilj = -1; return par }

        /** Cilj se ni dosezen: naprej proti njemu - razen ce je uporabnik medtem izbral kaj drugega. */
        @Synchronized fun nadaljuj(c: Int) { if (cilj < 0 && sprememba == 0) cilj = c }

        @Synchronized fun pocisti() { sprememba = 0; cilj = -1 }
        @Synchronized fun caka(): Boolean = sprememba != 0 || cilj >= 0
    }

    /**
     * Ali mora naprava med predvajanjem na zvocniku ostati budna: samo, kadar ima se delo - zvocniku streze svojo datoteko,
     * mu bo morala po koncu skladbe poslati naslednjo ([imaNadaljevanje]; tok v zivo se ne konca, zato pri radiu ne steje)
     * ali mora ob svojem casu ustaviti predvajanje ([casovnik] izklopa - casovniki med spanjem naprave stojijo).
     * Kadar se zvocnik ne odziva, velja to se do roka (glej spodaj).
     */
    fun potrebujeBudnost(s: Stanje, lokalniVir: Boolean, imaNadaljevanje: Boolean, vZivo: Boolean, casovnik: Boolean, zdaj: Long): Boolean {
        // Premor, ki ga zvocnik se ni potrdil (tudi ob izteku casovnika izklopa): naprava ostane budna, dokler ga ne potrdi
        // ali ga po ponovitvah ne ustavimo - najvec [PREMOR_BUDNOST_MS]. Prej je naprava zaspala, se preden je ukaz odsel.
        if (s.premorNepotrjen && zdaj - s.premorOdMs < PREMOR_BUDNOST_MS) return true
        // Casovnik izklopa mora docakati svoj cas, tudi ce zvocnik ta hip ni dosegljiv (do takrat je lahko spet).
        if (s.zeliIgrati && casovnik) return true
        val delo = s.zeliIgrati && (lokalniVir || (imaNadaljevanje && !vZivo))
        if (!delo || !s.nedosegljiv) return delo
        // Zvocnik se ne odziva. Izpad je obicajno kratek in zvocnik medtem igra naprej: naprava ostane budna, da mu po
        // vrnitvi streze svojo datoteko in mu po koncu skladbe poslje naslednjo (prej je po 20 s izpada zaspala in vrsta je
        // obstala - tretji pregled, A1). Rok: najvec [NEDOSEGLJIV_BUDNOST_MS] od zadnjega odgovora in ne dlje kot do
        // pricakovanega konca skladbe + [NAJVEC_VRZEL_ZA_KONEC_MS] - po tem bi bil konec za naslednjo skladbo ze prepozen
        // (isto pravilo kot v [poOdcitku]). Potem naprava lahko zaspi; seja ostane.
        var rok = s.slisanMs + NEDOSEGLJIV_BUDNOST_MS
        if (s.igral && s.trajanjeMs > 0L) rok = minOf(rok, s.veljaOd + (s.trajanjeMs - s.polozajMs) + NAJVEC_VRZEL_ZA_KONEC_MS)
        return zdaj < rok
    }
}
