package si.safeer.tv.link

/**
 * Deljenje zaslona z racunalnikom po vzoru DeX, brez posebnih pravic (nacrt »dex-upravljanje«, tocke A1-A4):
 *
 *  - A1 ena potrditev na ogled, ne na aplikacijo: ko ta naprava zaslon ze deli racunalniku, ki zeli odpreti se eno
 *    aplikacijo, aplikacijo samo odpremo v tekocem deljenju (brez novega soglasja in brez novega okna na racunalniku);
 *  - A2 okno za soglasje takoj, brez tapa na obvestilo, kadar Android zagon iz ozadja dovoli (vklopljen Safeer Vnos,
 *    prikaz cez druge aplikacije ali Safeer v ospredju); obvestilo je potem le se rezerva, ce se okno ni odprlo;
 *  - A3 »Vedno dovoli temu racunalniku« za napravo iz ozjega kroga; ce ima Safeer trajno dovoljenje Androida za prikaz
 *    (AppOps PROJECT_MEDIA = allow, Android sam okna ne pokaze vec), Safeer drugo napravo vprasa sam - razen zaupane;
 *  - A4 zaklep zaslona (Android 15 QPR1+ deljenje ob zaklepu ustavi): racunalnik vidi »odkleni za nadaljevanje«,
 *    po odklepu se deljenje nadaljuje v istem oknu (zaupana naprava samodejno, druga s tapom na obvestilo).
 *
 * Tu so samo pravila, brez Androida (tests/DeljenjePravilaTest.kt v navadnem JVM).
 */
object DeljenjePravila {
    /**
     * Global Link (lastnik 28. 10. 2026): deljenje zaslona gre prek releja link.safeer.si, ce je vklopljen in je naprava
     * zdoma (Link ze tece prek releja), je vklopljen preizkus »samo rele« ali domaci Hub v omrezju ne odgovori. Sicer LAN.
     */
    fun prekReleja(vklopljen: Boolean, zdoma: Boolean, samoRele: Boolean, lanDosegljiv: () -> Boolean): Boolean =
        vklopljen && (zdoma || samoRele || !lanDosegljiv())

    /** Naslov releja (wss://127.0.0.1:vrata) kot osnova HTTPS za Hub (brez koncne poti in posevnice). */
    fun httpIzWs(naslov: String): String = naslov.replace(Regex("^wss"), "https").replace(Regex("^ws"), "http")
        .substringBefore("/cast/ws").substringBefore("/link/ws").substringBefore("/safeer/ws").trimEnd('/')


    // ------------------------------------------------------------------ A1

    enum class Pot {
        /** Deljenje tej napravi ze tece: aplikacijo samo odpremo, soglasja ne zahtevamo znova. */
        ODPRI_V_SEJI,
        /** Deljenje celega zaslona tej napravi ze tece in zeli prav to: nicesar ne naredimo. */
        ZE_DELIM,
        /** Nova seja: vprasamo za soglasje (in morebitno tekoce deljenje drugi napravi zamenjamo). */
        NOVA_SEJA
    }

    /**
     * Kaj naredi »Odpri tukaj« naprave [posiljatelj] ([celZaslon]: »Povezani zasloni« - brez aplikacije). [deliZaslon] in
     * [ciljDeljenja]: ali in komu ta naprava zdaj deli zaslon; [zacenjaZa]: za koga se deljenje pravkar zaganja (soglasje
     * je dano, storitev se ne tece; "" = za nikogar) - kot v [PretokKonec.odloci] velja ta oznaka pred tekocim deljenjem;
     * [cakaNaOdklep]: deljenje stoji, ker je naprava zaklenjena (projekcije ni vec - brez novega soglasja ne gre).
     *
     * Obe nasi poti zahtevata deljenje CELEGA zaslona (MediaProjectionConfig.createConfigForDefaultDisplay), zato se v
     * tekocem deljenju vidi vsaka aplikacija, ki jo odpremo.
     */
    fun pot(posiljatelj: String, celZaslon: Boolean, deliZaslon: Boolean, ciljDeljenja: String, zacenjaZa: String,
            cakaNaOdklep: Boolean): Pot {
        if (posiljatelj.isBlank()) return Pot.NOVA_SEJA
        val njegovo = if (zacenjaZa.isNotEmpty()) zacenjaZa == posiljatelj
            else deliZaslon && !cakaNaOdklep && ciljDeljenja == posiljatelj
        if (!njegovo) return Pot.NOVA_SEJA
        return if (celZaslon) Pot.ZE_DELIM else Pot.ODPRI_V_SEJI
    }

    // ------------------------------------------------------------------ A2

    /** Toliko po neposrednem zagonu preverimo, ali se je okno res odprlo; sele potem rezervno obvestilo. */
    const val NAMIG_PO_MS = 2_000L

    /**
     * Ali Android dovoli, da Safeer iz ozadja odpre dejavnost (sicer zagon TIHO zavrne, v dnevniku BAL_BLOCK).
     * [vnosAktiven]: Safeer Vnos (storitev dostopnosti) je vklopljen - sistem jo veze z BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS
     * (AOSP AccessibilityServiceConnection), kar procesu Safeerja dovoli zagon iz ozadja.
     */
    fun smeIzOzadja(sdk: Int, prekrivanje: Boolean, vnosAktiven: Boolean, vOspredju: Boolean): Boolean =
        sdk < 29 || prekrivanje || vnosAktiven || vOspredju

    /**
     * Rezervno obvestilo (»tapni za nadaljevanje«) takoj: zagon iz ozadja ni dovoljen ali pa ne bomo mogli preveriti,
     * ali se je okno odprlo. Sicer ga objavimo sele po [NAMIG_PO_MS], ce se okno ni odprlo ([namigPozneje]).
     */
    fun namigTakoj(neposredno: Boolean, preverljivo: Boolean): Boolean = !neposredno || !preverljivo

    /** Po [NAMIG_PO_MS]: [odprlo] = okno je na zaslonu (null = ne vemo). Obvestilo samo, ce se ni odprlo. */
    fun namigPozneje(odprlo: Boolean?): Boolean = odprlo != true

    // ------------------------------------------------------------------ A3

    enum class Potrditev {
        /** Potrdi sistemsko okno Androida (MediaProjection) - Safeer ne doda svojega vprasanja. */
        SISTEMSKA,
        /** Android okna ne bo pokazal (trajno dovoljenje), naprava ni zaupana: vprasa Safeer. */
        NASA,
        /** Android okna ne bo pokazal in naprava je zaupana (»Vedno dovoli«): brez vprasanja. */
        BREZ
    }

    /** [vednoDovoli]: uporabnik je tej napravi rekel »Vedno dovoli«; [vKrogu]: naprava je v ozjem krogu ([OzjiKrogPravila]). */
    fun zaupano(vednoDovoli: Boolean, vKrogu: Boolean): Boolean = vednoDovoli && vKrogu

    /**
     * [projekcijaDovoljena]: AppOps PROJECT_MEDIA je za Safeer `allow` - Android okna za soglasje ne pokaze (SystemUI
     * MediaProjectionPermissionActivity: hasProjectionPermission). Zaslona brez kakrsnegakoli vprasanja tako ne dobi nihce,
     * ki mu uporabnik tega ni izrecno dovolil.
     */
    fun potrditev(projekcijaDovoljena: Boolean, zaupano: Boolean): Potrditev = when {
        !projekcijaDovoljena -> Potrditev.SISTEMSKA
        zaupano -> Potrditev.BREZ
        else -> Potrditev.NASA
    }

    /** Ali gumb »Vedno dovoli« ponudimo: samo napravi iz ozjega kroga, ki se ni zaupana (in nikoli tej napravi sami). */
    fun ponudiZaupanje(vKrogu: Boolean, vednoDovoli: Boolean, taNaprava: Boolean): Boolean = vKrogu && !vednoDovoli && !taNaprava

    /** Pregled dex-a (Grok, 25. 10. 2026) U4: »Vedno dovoli« se zapise samo za napravo iz ozjega kroga, nikoli za to napravo. */
    fun smeZaupati(vKrogu: Boolean, taNaprava: Boolean): Boolean = vKrogu && !taNaprava

    /**
     * Pregled dex-a U5: dejanje »Vedno dovoli« iz obvestila velja samo za napravo, za katero je bilo obvestilo ([ciljNamere]
     * = [cilj] tekocega deljenja), samo med tekocim deljenjem (ne med cakanjem na odklep) in samo, ce jo je se smiselno
     * ponuditi ([ponudi]). Preklic (»ne vec«) velja vedno za tekoci cilj.
     */
    fun zaupajIzObvestila(zaupaj: Boolean, tece: Boolean, cilj: String, ciljNamere: String, cakaNaOdklep: Boolean, ponudi: Boolean,
                          /** Grok 15, N11: Android 10-11 (brez setAuthenticationRequired) - »Vedno dovoli« ne na zaklenjeni napravi. */
                          zaklenjeno: Boolean = false): Boolean =
        tece && cilj.isNotBlank() && ciljNamere == cilj && (!zaupaj || (!cakaNaOdklep && ponudi && !zaklenjeno))

    /**
     * Pregled 100, N1 (26. 10. 2026): toliko casa po koncu zadnjega vnosa z druge naprave (Safeer Vnos) »Vedno dovoli« iz
     * obvestila ne velja. Tap gledalca pride v storitev v nekaj ms; 5 s je rezerva za pocasno napravo.
     */
    const val OKNO_VNOSA_MS = 5_000L

    /** Najdaljsa gesta, ki jo Android izvede (GestureDescription.getMaxGestureDuration): daljse trajanje z omrezja odrezemo. */
    const val NAJDALJSA_GESTA_MS = 60_000L

    /**
     * Pregled 100, N1: kdaj se vnos z druge naprave konca - dotik ali poteg z [trajanjeMs] (stevilo z omrezja, omejeno na
     * [NAJDALJSA_GESTA_MS]) tapne sele ob koncu geste, zato okno steje od konca, ne od zacetka.
     */
    fun konecVnosa(zdajMs: Long, trajanjeMs: Long): Long = zdajMs + trajanjeMs.coerceIn(0L, NAJDALJSA_GESTA_MS)

    /**
     * Pregled 100, N1 (26. 10. 2026): kot [zaupajIzObvestila], le da »Vedno dovoli« ne velja, ce je bil v zadnjih
     * [OKNO_VNOSA_MS] vnos z druge naprave - obvestilo je na zaslonu, ki ga gledalec vidi in s Safeer Vnosom tapne, in
     * setAuthenticationRequired zahteva samo odklenjeno napravo. Tap na lastnem zaslonu bi gledalcu sicer dal zaslon brez
     * vprasanja (Potrditev.BREZ). Preklic ostane vedno dovoljen, ker zaupanje samo odvzame. [zadnjiVnosMs] je konec
     * zadnjega vnosa ([konecVnosa]), [zdajMs] ista monotona ura (elapsedRealtime); 0 = vnosa se ni bilo. Brez privzetih
     * vrednosti: klicatelj, ki casa vnosa ne poda, se ne prevede.
     */
    fun zaupajIzObvestilaBrezVnosa(zaupaj: Boolean, tece: Boolean, cilj: String, ciljNamere: String, cakaNaOdklep: Boolean,
                                   ponudi: Boolean, zaklenjeno: Boolean, zadnjiVnosMs: Long, zdajMs: Long): Boolean =
        zaupajIzObvestila(zaupaj, tece, cilj, ciljNamere, cakaNaOdklep, ponudi, zaklenjeno) &&
            (!zaupaj || zadnjiVnosMs <= 0L || zdajMs - zadnjiVnosMs > OKNO_VNOSA_MS)

    /** Pregled dex-a U7: zaupanje ostane samo napravam, ki so se v ozjem krogu (umik ga izbrise; ponovna vkljucitev ga ne obudi). */
    fun zaupanjaVKrogu(zapis: Set<String>, vKrogu: (String) -> Boolean): Set<String> =
        zapis.filter { j -> try { vKrogu(j) } catch (_: Throwable) { false } }.toSet()

    /**
     * Pregled dex-a U1: nacin AppOps PROJECT_MEDIA -> ali ima Safeer trajno dovoljenje. Ce nacina ni bilo mogoce prebrati
     * ([nacin] null), velja, kot da ga ima (»ne vem« ni »ne«): takrat Safeer sam vprasa in zahteva viden znak - prej je
     * napaka pomenila »Android bo vprasal sam«, in obe varovalki sta se izklopili.
     */
    fun projekcijaPoNacinu(nacin: Int?): Boolean = nacin == null || nacin == MODE_ALLOWED

    /** AppOpsManager.MODE_ALLOWED. */
    const val MODE_ALLOWED = 0

    // ------------------------------------------------------------------ A4

    /** Najdlje toliko deljenje caka na odklep; potem se konca kot vsako drugo (racunalnik zapre okno). */
    const val CAKA_ODKLEP_MS = 10 * 60_000L

    enum class Konec {
        /** Navaden konec (uporabnik, napaka, zamenjava): deljenje se konca. */
        NAVADEN,
        /** Android je projekcijo ustavil, ker se je naprava zaklenila: deljenje pocaka na odklep. */
        ZAKLEP
    }

    /**
     * Zakaj se je projekcija ustavila. [zaklenjeno]: zaklepni zaslon je aktiven (KeyguardManager.isKeyguardLocked);
     * [zaslonPrizgan]: PowerManager.isInteractive. Android 15 QPR1+ projekcijo ustavi ob zaklepu (MediaProjectionStopController),
     * torej z ugasnjenim zaslonom; »Ustavi« na zaklepnem zaslonu (prizgan zaslon) je uporabnikova odlocitev in ostane konec.
     * [ustavljam]: konec je sprozil Safeer sam; [tokVzpostavljen]: racunalnik ze gleda (je kam pokazati sporocilo).
     */
    fun konecProjekcije(zaklenjeno: Boolean, zaslonPrizgan: Boolean, ustavljam: Boolean, tokVzpostavljen: Boolean): Konec =
        if (!ustavljam && tokVzpostavljen && zaklenjeno && !zaslonPrizgan) Konec.ZAKLEP else Konec.NAVADEN

    enum class Nadaljevanje {
        /** Zaupana naprava: soglasje zahtevamo takoj po odklepu (s trajnim dovoljenjem sploh brez okna). */
        SAMODEJNO,
        /** Druga naprava: po odklepu le obvestilo »Nadaljuj deljenje« - nic se ne zacne samo. */
        OBVESTILO,
        /** Cakanje je poteklo: deljenje se konca. */
        KONEC
    }

    fun poOdklepu(zaupano: Boolean, cakaOd: Long, zdaj: Long): Nadaljevanje = when {
        zdaj - cakaOd >= CAKA_ODKLEP_MS -> Nadaljevanje.KONEC
        zaupano -> Nadaljevanje.SAMODEJNO
        else -> Nadaljevanje.OBVESTILO
    }

    /**
     * Ali bo uporabnik po odklepu se kaj potrdil na napravi (druga vrstica sporocila na racunalniku): da, razen ce je
     * naprava zaupana, ima Safeer trajno dovoljenje Androida in sme okno odpreti iz ozadja ([izOzadja], [smeIzOzadja]) -
     * takrat se slika vrne sama, brez okna in brez obvestila.
     */
    fun poOdklepuPotrdi(zaupano: Boolean, projekcijaDovoljena: Boolean, izOzadja: Boolean): Boolean =
        potrditev(projekcijaDovoljena, zaupano) != Potrditev.BREZ || !izOzadja

    /** Med cakanjem na odklep vnosa z racunalnika ne izvedemo: tipke in dotiki bi sli na zaklepni zaslon. */
    fun vnosDovoljen(cakaNaOdklep: Boolean): Boolean = !cakaNaOdklep

    enum class ZavrnitevVnosa { NI, VPRASANJE, CAKA_NA_ODKLEP, ZAKLENJENO }

    /**
     * Tece (ali caka na odklep) deljenje napravi [cilj], ki ji zaslon ni vec odprt - konec takoj (pravilo 25. 10. 2026).
     * Grok 15, N1: umik iz ozjega kroga ([vKrogu] = false: prazen nabor ali umaknjena naprava) konca vsako sejo z njo;
     * odvzem zaslona konca sejo samo, ce se je spremenil dostop prav te naprave ([spremenjenaJeCilj]) - sprememba dostopa
     * druge naprave ali kroga zakonite seje ne konca.
     */
    fun ustaviZaradiDostopa(tece: Boolean, cakaNaOdklep: Boolean, cilj: String, smeZaslon: Boolean,
                            vKrogu: Boolean = smeZaslon, spremenjenaJeCilj: Boolean = true): Boolean =
        (tece || cakaNaOdklep) && cilj.isNotBlank() && (!vKrogu || (spremenjenaJeCilj && !smeZaslon))

    /**
     * Grok 23, R2: zahteva za konec zaradi dostopa nosi napravo, za katero je bila odlocena ([pricakovan]). Ce se je vmes
     * zacelo deljenje DRUGI napravi ([cilj], [tece]), ga ne ustavi. null = konec brez pogoja (gumb Ustavi, obvestilo).
     */
    fun ustaviZaCilj(pricakovan: String?, cilj: String, tece: Boolean, cakaNaOdklep: Boolean): Boolean =
        pricakovan == null || pricakovan == cilj || (!tece && !cakaNaOdklep)

    /** Tipke Safeer Vnosa, ki pomenijo »Nazaj« (VnosStoritev.tipka: GLOBAL_ACTION_BACK). */
    val TIPKE_NAZAJ = setOf("back", "nazaj")

    /** Grok 15, N2: ukaz Safeer Vnosa je samo »Nazaj« (vprasanje za deljenje zaslona ga samo zavrne). */
    fun samoNazaj(dejanje: String, tipka: String): Boolean = dejanje == "input.key" && tipka.trim().lowercase() in TIPKE_NAZAJ

    /** Grok 15, N2: vprasanje za deljenje zaslona brez odgovora se po tem casu zavrne samo - vnos drugih naprav ni blokiran dlje. */
    const val ROK_VPRASANJA_MS = 60_000L

    /**
     * Pregled dex-a U2 in U6: vnos z druge naprave (dotik, tipka, besedilo, igralni plosek) zavrnemo, dokler je odprto
     * vprasanje za deljenje zaslona (Safeerjevo ali sistemsko - naprava, ki zaslon zahteva, bi sama tapnila »Dovoli«), dokler
     * deljenje caka na odklep in dokler je naprava zaklenjena (po stanju zaklepa, ne po ustavitvi projekcije - pred Androidom
     * 15 QPR1 projekcija ob zaklepu tece naprej). Grok 15, N2: »Nazaj« ([samoNazaj]) vprasanje samo zavrne - TV, ki ga
     * upravljas samo s telefonom, med vprasanjem ne obtici.
     */
    fun zavrnitevVnosa(cakaNaOdklep: Boolean, zaklenjeno: Boolean, vprasanjeOdprto: Boolean, samoNazaj: Boolean = false): ZavrnitevVnosa = when {
        // Grok 23, R4: izjema »Nazaj« ne velja na zaklenjeni napravi in med cakanjem na odklep (zaklenjeno = brez vnosa).
        vprasanjeOdprto && samoNazaj && !cakaNaOdklep && !zaklenjeno -> ZavrnitevVnosa.NI
        vprasanjeOdprto -> ZavrnitevVnosa.VPRASANJE
        cakaNaOdklep -> ZavrnitevVnosa.CAKA_NA_ODKLEP
        zaklenjeno -> ZavrnitevVnosa.ZAKLENJENO
        else -> ZavrnitevVnosa.NI
    }

    /**
     * Ali nov zagon storitve deljenja nadaljuje deljenje, ki caka na odklep (isti tok do istega racunalnika, isto okno
     * na njem), namesto da bi zacel novega.
     */
    fun nadaljujVIstemToku(cakaNaOdklep: Boolean, ciljCakanja: String, novCilj: String): Boolean =
        cakaNaOdklep && ciljCakanja.isNotBlank() && ciljCakanja == novCilj

    // ------------------------------------------------------------------ meritev M1 (25. 10. 2026)

    /**
     * Viden znak deljenja. Meritev M1: OnePlus 9 (Android 14) med deljenjem s trajnim dovoljenjem ni pokazal nicesar, obvestilo
     * Safeerja z »Ustavi« pa je bilo skrito, ker obvestila za Safeer niso dovoljena. Deljenje BREZ okna za soglasje se zato
     * sme zaceti samo, ce ga Safeer na napravi lahko pokaze: z obvestilom ([obvestila]) ali s svojim gumbom na zaslonu
     * ([prekrivanje] - prikaz cez druge aplikacije). Z oknom za soglasje uporabnik ob zacetku ve, kaj je dovolil.
     */
    fun smeZacetiBrezOkna(projekcijaDovoljena: Boolean, obvestila: Boolean, prekrivanje: Boolean): Boolean =
        !projekcijaDovoljena || obvestila || prekrivanje

    /**
     * Pregled dex-a U3: po zacetku deljenja brez okna za soglasje mora biti znak RES viden - obvestilo ali gumb, ki se je
     * pokazal ([gumbPrikazan]); ce addView ne uspe in obvestil ni, se deljenje konca (prej je teklo naprej brez znaka).
     */
    fun znakZagotovljen(projekcijaDovoljena: Boolean, obvestila: Boolean, gumbPrikazan: Boolean): Boolean =
        !projekcijaDovoljena || obvestila || gumbPrikazan

    /** Odgovor na »Odpri tukaj« brez vprasanja pocaka, da deljenje res stece - najvec toliko (racunalnik caka 8 s). */
    const val CAKA_ZACETEK_MS = 4_000L

    /**
     * Ali odgovor racunalniku pocaka na dejanski zacetek deljenja: samo, ce vprasanja ne bo ([Potrditev.BREZ]) in se okno
     * odpre samo ([izOzadja]) - takrat deljenje stece v delcu sekunde (M1: 0,10-0,16 s). Z vprasanjem odgovorimo takoj.
     */
    fun pocakajZacetek(potrditev: Potrditev, izOzadja: Boolean): Boolean = potrditev == Potrditev.BREZ && izOzadja

    /** Deljenje napravi [posiljatelj] tece in Hub ga je sprejel (znak gledalca je znan). */
    fun zacetekPotrjen(tece: Boolean, ciljDeljenja: String, posiljatelj: String, znakGledalca: String): Boolean =
        tece && posiljatelj.isNotBlank() && ciljDeljenja == posiljatelj && znakGledalca.isNotEmpty()

    enum class BrezZagona {
        /** Obvestilo »tapni za nadaljevanje« je res na zaslonu. */
        OBVESTILO,
        /** Obvestila so izklopljena: na napravi ni nicesar - racunalnik pove, kje na napravi kaj vklopiti. */
        NASTAVITVE
    }

    /** Safeer okna iz ozadja ne sme odpreti: kaj uporabnik na napravi sploh vidi ([obvestila] vklopljena ali ne). */
    fun brezZagona(obvestila: Boolean): BrezZagona = if (obvestila) BrezZagona.OBVESTILO else BrezZagona.NASTAVITVE
}
