package si.safeer.tv.link

/**
 * Vklop Safeer Vnosa (storitev dostopnosti) s cim manj truda za uporabnika (lastnik 25. 10. 2026: »Uporabnik ne sme
 * imeti tezav s tem ali kar se da cim manj«).
 *
 * Ozadje (izmerjeno 25. 10. 2026 na OnePlus 9, Android 14): tapu na stikalo Safeer Vnos je sledilo »Prepovedana
 * nastavitev«. Android 13/14 omeji dostopnost in dostop do obvestil za aplikacije, ki NISO bile namescene prek seje
 * PackageInstaller (packageSource LOCAL_FILE); Android 15 doda se prekrivanje. Odkleni jo uporabnik: ⋮ → »Dovoli
 * omejene nastavitve« → PIN. »Prikaz cez druge aplikacije« (Samsung »Pojavitev na vrhu«) je LOCENO dovoljenje -
 * uporabnik je 25. 10. mislil, da zadosca, pa ne.
 *
 * Tu so samo pravila (brez Androida): kateri korak voden tok pokaze. Preizkus tests/OmejenoNastavitvePravilaTest.kt.
 */
object OmejenoNastavitvePravila {

    enum class Korak {
        /** Safeer Vnos je vklopljen: nic ni treba. */
        VKLOPLJENO,
        /** Odpri podrobnosti Safeer Vnosa (ACTION_ACCESSIBILITY_DETAILS_SETTINGS), da uporabnik enkrat tapne stikalo. */
        ODPRI_DOSTOPNOST,
        /** Tap na stikalo je zavrnila »Prepovedana nastavitev«: najprej ⋮ → »Dovoli omejene nastavitve« → PIN. */
        ODKLENI_OMEJENE,
        /** Omejene nastavitve so odklenjene: nazaj na Safeer Vnos, da uporabnik tapne stikalo. */
        VKLOPI_STIKALO
    }

    /**
     * Kaj pokazati, ko uporabnik na strani Dovoljenja izbere Safeer Vnos.
     * [vnosAktiven]: storitev ze tece; [omejenoZaklenjeno]: AppOps access_restricted_settings je `ignore`/`default`
     * (ne `allow`) - omejene nastavitve se niso odklenjene; [poskusilVklop]: uporabnik je ze tapnil stikalo (in ga je
     * »Prepovedana nastavitev« zavrnila).
     */
    fun korak(vnosAktiven: Boolean, omejenoZaklenjeno: Boolean, poskusilVklop: Boolean): Korak = when {
        vnosAktiven -> Korak.VKLOPLJENO
        omejenoZaklenjeno && poskusilVklop -> Korak.ODKLENI_OMEJENE
        omejenoZaklenjeno -> Korak.ODPRI_DOSTOPNOST
        poskusilVklop -> Korak.VKLOPI_STIKALO
        else -> Korak.ODPRI_DOSTOPNOST
    }

    /**
     * Pregled dex-a U12: uporabnik je stikalo »poskusil«, ce je od prvega vodenega odprtja Safeer Vnosa ([odprtoMs]) minilo
     * vsaj [POSKUS_PO_MS] (imel je cas tapniti) in najvec [CIKEL_MS] - prej je ze drugi klic pomenil »poskusil«.
     */
    fun poskusil(odprtoMs: Long, zdajMs: Long): Boolean = odprtoMs > 0 && zdajMs - odprtoMs in POSKUS_PO_MS..CIKEL_MS

    /** Cas prvega odprtja v tem ciklu: obdrzi prejsnjega, dokler cikel velja; sicer zdaj. */
    fun casOdprtja(prejMs: Long, zdajMs: Long): Long = if (prejMs > 0 && zdajMs - prejMs in 0..CIKEL_MS) prejMs else zdajMs

    const val POSKUS_PO_MS = 15_000L
    const val CIKEL_MS = 10L * 60 * 1000

    /**
     * Ali omejene nastavitve veljajo za to namestitev. Android 13+ (API 33); odvisno od vira namestitve
     * (PackageInstaller.SessionParams): trgovina (STORE = 2) in drugo (OTHER = 1) nista omejena, lokalna datoteka
     * ([PACKAGE_SOURCE_LOCAL_FILE] = 3) in prenesena datoteka ([PACKAGE_SOURCE_DOWNLOADED_FILE] = 4) sta. Izmerjeno
     * 25. 10. 2026: omejitev velja za 3 in 4 (prej je tu veljala samo 3).
     */
    const val PACKAGE_SOURCE_LOCAL_FILE = 3
    const val PACKAGE_SOURCE_DOWNLOADED_FILE = 4

    fun omejenoVelja(sdk: Int, packageSource: Int): Boolean =
        sdk >= 33 && (packageSource == PACKAGE_SOURCE_LOCAL_FILE || packageSource == PACKAGE_SOURCE_DOWNLOADED_FILE)
}
