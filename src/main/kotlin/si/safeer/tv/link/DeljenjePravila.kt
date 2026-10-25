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

    /**
     * Ali nov zagon storitve deljenja nadaljuje deljenje, ki caka na odklep (isti tok do istega racunalnika, isto okno
     * na njem), namesto da bi zacel novega.
     */
    fun nadaljujVIstemToku(cakaNaOdklep: Boolean, ciljCakanja: String, novCilj: String): Boolean =
        cakaNaOdklep && ciljCakanja.isNotBlank() && ciljCakanja == novCilj
}
