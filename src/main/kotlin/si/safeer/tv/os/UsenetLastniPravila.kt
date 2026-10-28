package si.safeer.tv.os

/**
 * Lasten pogon Usenet v Medijskem centru (lastnik 27. 10. 2026: »filmi iz Useneta v nasem Media centru, prikaz in
 * predvajanje kot zdaj, le hitrejse in v boljsi kakovosti; ce je Usenet na voljo, ga predvajalnik izbere sam«). Ciste
 * odlocitve mostu [UsenetLastni] (tests/UsenetLastniPravilaTest.kt); enako kot core/usenet_vezava.py na Linuxu.
 */
object UsenetLastniPravila {
    /** Ime vira v sporocilu »Predvaja prek …« in v izidu (TorBox.Izid.ponudnik). */
    const val PONUDNIK = "Usenet"
    /** Lasten pogon ni predvajal (izdaja, ponudnik, omrezje): samodejna izbira gre naprej, TorBox ostane rezerva. */
    const val NAPAKA = "lastni_pogon"
    /** Samodejno najvec toliko izdaj zapored (kot TorBox in namizje: najboljsa do 15 GB, nato naslednja). */
    const val NAJVEC_POSKUSOV = 2

    /** Pot toka: [izbira] true = samo lasten pogon, false = samo TorBox, null = lasten pogon, ce je ponudnik vpisan. */
    fun lastni(izbira: Boolean?, naVoljo: Boolean): Boolean = izbira ?: naVoljo

    /**
     * Ali je Usenet v Medijskem centru: indeksator in vsaj ena pot predvajanja (TorBox s kljucem in brez »Brez debrida«
     * ali lasten ponudnik NNTP). »Brez debrida« lastnega ponudnika ne izklopi - ni debrid.
     */
    fun naVoljo(imaIndeksator: Boolean, torBox: Boolean, lastni: Boolean): Boolean = imaIndeksator && (torBox || lastni)

    /**
     * Izid preverbe »Preveri« v oknu ponudnika iz napake odjemalca [UsenetNntp] (null = prijava uspela) v obliko pravil
     * okna (UsenetPonudnikPravila.izidNapake: zapah, »ne velja«, »preveč povezav«, »ni odgovora«).
     */
    fun prijavaIz(vrsta: UsenetNntp.Vrsta?): UsenetPonudnikPravila.Prijava =
        when (vrsta) {
            null -> UsenetPonudnikPravila.Prijava(UsenetPonudnikPravila.NNTP_OK, UsenetPonudnikPravila.FAZA_PRIJAVA)
            UsenetNntp.Vrsta.PRIJAVA -> UsenetPonudnikPravila.Prijava(UsenetPonudnikPravila.NNTP_PRIJAVA, UsenetPonudnikPravila.FAZA_PRIJAVA)
            UsenetNntp.Vrsta.ZASEDEN -> UsenetPonudnikPravila.Prijava(UsenetPonudnikPravila.NNTP_ZASEDEN, UsenetPonudnikPravila.FAZA_POVEZAVA)
            UsenetNntp.Vrsta.DOSTOP -> UsenetPonudnikPravila.Prijava(UsenetPonudnikPravila.NNTP_DOSTOP, UsenetPonudnikPravila.FAZA_PRIJAVA)
            UsenetNntp.Vrsta.POTRDILO -> UsenetPonudnikPravila.Prijava(UsenetPonudnikPravila.NNTP_POTRDILO, UsenetPonudnikPravila.FAZA_POVEZAVA)
            UsenetNntp.Vrsta.ROK -> UsenetPonudnikPravila.Prijava(UsenetPonudnikPravila.NNTP_ROK, UsenetPonudnikPravila.FAZA_POVEZAVA)
            UsenetNntp.Vrsta.ZAPRTO -> UsenetPonudnikPravila.Prijava(UsenetPonudnikPravila.NNTP_ZAPRTO, UsenetPonudnikPravila.FAZA_POVEZAVA)
            UsenetNntp.Vrsta.OMREZJE -> UsenetPonudnikPravila.Prijava(UsenetPonudnikPravila.NNTP_OMREZJE, UsenetPonudnikPravila.FAZA_POVEZAVA)
            UsenetNntp.Vrsta.NAPAKA_PROTOKOLA -> UsenetPonudnikPravila.Prijava(UsenetPonudnikPravila.NNTP_NAPAKA_PROTOKOLA, UsenetPonudnikPravila.FAZA_POVEZAVA)
            else -> UsenetPonudnikPravila.Prijava(UsenetPonudnikPravila.NNTP_NAPAKA_STREZNIKA, UsenetPonudnikPravila.FAZA_POVEZAVA)
        }

    /** Album v RAR (vodja 27. 10. 2026): izid UsenetRar -> stalen niz napake albuma (stisnjen/sifriran ima svoje besedilo). */
    fun napakaAlbumaRar(izid: String): String = when (izid.uppercase()) {
        "STISNJEN" -> "stisnjen"
        "SIFRIRAN", "SIFRIRANE_GLAVE" -> "sifriran"
        "NI_RAR", "NI_DATOTEKE" -> "ni_skladb"
        else -> "rar_" + izid.lowercase()
    }

    /** Okno: stisnjen ali sifriran arhiv ima svoje besedilo (sproti ga ne moremo brati), drugo splosno. */
    fun albumStisnjen(napaka: String): Boolean = napaka == "stisnjen" || napaka == "sifriran"

    /**
     * Vodja 27. 10. 2026 (TV beta.12): film je sel prek lastnega pogona, obvestilo pa je reklo »TorBox« - pripravljen Usenet
     * je izgubil ponudnika. Lasten pogon = [PONUDNIK] (TorBox.Izid.ponudnik iz tokLastni); vse drugo je TorBox.
     */
    fun jeLastni(ponudnik: String?): Boolean = ponudnik == PONUDNIK

    /** Dnevnik izbire (brez imena izdaje): kdo predvaja pripravljen Usenet. */
    fun dnevnikPripravljenega(ponudnik: String?): String =
        if (jeLastni(ponudnik)) "Usenet lasten pogon" else "Usenet pripravljen pri TorBoxu"

    /** Lastnik 27. 10. 2026: priprava toka Usenet z lastnim ponudnikom ima svoj napis (ne »prek debrid storitve«). */
    fun pripravaLastnega(usenet: Boolean, lastniVUporabi: Boolean): Boolean = usenet && lastniVUporabi

    /**
     * Lastnik 27. 10. 2026: strezniki skladb albuma nastanejo vsi ob zacetku; skladba, ki je se ni na vrsti, ne sme poteci
     * po 5 min mirovanja (UsenetStreznik.NEDEJAVNOST_MS - album je obstal po 4 skladbah). Zaprejo se z UsenetLastni.ustavi.
     */
    const val NEDEJAVNOST_ALBUMA_MS = 4L * 60 * 60 * 1000

    /**
     * Lastnik 28. 10. 2026 (kvota indeksatorja): izdaja s trajno napako se zapomni [NEUSPELI_DNI] dni in se ne pokaze vec;
     * omrezje, zaseden ponudnik, kljuc ali indeksator niso trajni (drugic lahko uspe).
     */
    fun trajnaNapakaAlbuma(napaka: String): Boolean =
        napaka in setOf("luknja", "ni_podprto", "ni_skladb", "ni_filma", "stisnjen", "sifriran", "nzb") || napaka.startsWith("rar_")

    /** Samo manjkajoci deli (»luknja«) se pri drugi izdaji istega albuma lahko izidejo; sifrirano ali RAR ostane enako. */
    fun poskusiDrugoIzdajo(napaka: String): Boolean = napaka == "luknja"

    /** Na klik najvec toliko drugih izdaj (vsaka je en prenos NZB - kvota). */
    const val NAJVEC_DRUGIH_IZDAJ = 2
    const val NEUSPELI_DNI = 30
    const val NEUSPELIH_NAJVEC = 300
    /** Shramba NZB na napravi: najvec datotek in bajtov (najstarejsa uporaba gre prva). */
    const val NZB_NAJVEC_DATOTEK = 40
    const val NZB_NAJVEC_BAJTOV = 40L * 1024 * 1024
    const val NZB_NAJVEC_ENA = 4 * 1024 * 1024

    /** Neuspeli: odtis -> cas; ostanejo mlajsi od [NEUSPELI_DNI], najvec [NEUSPELIH_NAJVEC] najnovejsih. */
    fun pocistiNeuspele(vsi: Map<String, Long>, zdajMs: Long): Map<String, Long> =
        vsi.filterValues { zdajMs - it in 0 until NEUSPELI_DNI * 24L * 60 * 60 * 1000 }
            .entries.sortedByDescending { it.value }.take(NEUSPELIH_NAJVEC).associate { it.key to it.value }

    /** Datoteke shrambe NZB (ime, velikost, zadnja uporaba) za brisanje: najstarejse, dokler ni v mejah. */
    fun zaBrisanje(datoteke: List<Triple<String, Long, Long>>): List<String> {
        val po = datoteke.sortedByDescending { it.third }
        var bajtov = 0L
        return po.filterIndexed { i, d -> bajtov += d.second; i >= NZB_NAJVEC_DATOTEK || bajtov > NZB_NAJVEC_BAJTOV }.map { it.first }
    }
}
