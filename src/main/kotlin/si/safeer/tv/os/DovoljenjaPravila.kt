package si.safeer.tv.os

/**
 * Stran »Dovoljenja« (lastnik, 25. 10. 2026: »Prikaz čez druge aplikacije« Android nikoli ne našteje med dovoljenji
 * in uporabnik ne ve, kam tapniti). Na eni strani vse, kar Safeer res zahteva (samo dovoljenja, ki jih ima okus v
 * manifestu - nic izmisljenega), z razlogom, stanjem in gumbom, ki odpre TOCNO pravo stran ali vprasa kar v aplikaciji.
 *
 * Ciste odlocitve, brez Androida (preizkus: tests/DovoljenjaPravilaTest.kt); branje stanja in odpiranje strani dela
 * [DovoljenjaActivity].
 */
object DovoljenjaPravila {
    /** Postavke strani, v tem (stalnem) vrstnem redu: najprej, brez cesar Link ne dela. */
    enum class Vrsta { PRIKAZ, OBVESTILA, CELOZASLONSKA, SLIKE_VIDEI, GLASBA, VNOS, NAMESCANJE }

    enum class Stanje { DA, DELNO, NE }

    /** Kaj naredi gumb vrstice. */
    enum class Dejanje {
        /** Sistemska stran »Prikaz čez druge aplikacije« za Safeer (ACTION_MANAGE_OVERLAY_PERMISSION + package:). */
        STRAN_PRIKAZ,
        /** Sistemsko okno dovoljenja v aplikaciji (requestPermissions) - brez nastavitev. */
        VPRASAJ,
        /** Nastavitve obvestil aplikacije (ACTION_APP_NOTIFICATION_SETTINGS). */
        STRAN_OBVESTILA,
        /** Celozaslonska obvestila (ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT + package:, Android 14+). */
        STRAN_CELOZASLONSKA,
        /** Dostopnost (ACTION_ACCESSIBILITY_SETTINGS, oznacen Safeer Vnos). */
        STRAN_DOSTOPNOST,
        /** Namescanje neznanih aplikacij za Safeer (ACTION_MANAGE_UNKNOWN_APP_SOURCES + package:). */
        STRAN_NAMESCANJE,
        /** Podatki o aplikaciji (ACTION_APPLICATION_DETAILS_SETTINGS): dovoljenje, ki ga sistem ne vprasa vec. */
        STRAN_APLIKACIJA,
    }

    const val SAW = "android.permission.SYSTEM_ALERT_WINDOW"
    const val OBVESTILA = "android.permission.POST_NOTIFICATIONS"
    const val CELOZASLONSKA = "android.permission.USE_FULL_SCREEN_INTENT"
    const val NAMESCANJE = "android.permission.REQUEST_INSTALL_PACKAGES"
    const val SLIKE = "android.permission.READ_MEDIA_IMAGES"
    const val VIDEI = "android.permission.READ_MEDIA_VIDEO"
    const val IZBRANE = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    const val ZVOK = "android.permission.READ_MEDIA_AUDIO"
    const val SHRAMBA = "android.permission.READ_EXTERNAL_STORAGE"

    /**
     * Postavke za to napravo: samo, kar okus res deklarira ([deklarirana] = requestedPermissions), in samo, kjer na
     * tej razlicici Androida obstaja. Televizor ne kaze obvestil aplikacij (vrstice obvestil ni, kot v Nastavitvah).
     * [imaVnos] = storitev dostopnosti Safeer Vnos je v manifestu (telefon, tablica).
     */
    fun postavke(sdk: Int, tv: Boolean, imaVnos: Boolean, deklarirana: Set<String>): List<Vrsta> = Vrsta.values().filter { v ->
        when (v) {
            Vrsta.PRIKAZ -> SAW in deklarirana
            Vrsta.OBVESTILA -> !tv && (sdk < 33 || OBVESTILA in deklarirana)
            Vrsta.CELOZASLONSKA -> !tv && sdk >= 34 && CELOZASLONSKA in deklarirana
            Vrsta.SLIKE_VIDEI, Vrsta.GLASBA -> dovoljenja(v, sdk).isNotEmpty() && dovoljenja(v, sdk).all { it in deklarirana || it == IZBRANE }
            Vrsta.VNOS -> imaVnos
            Vrsta.NAMESCANJE -> NAMESCANJE in deklarirana
        }
    }

    /** Dovoljenja, ki jih vprasamo v aplikaciji (requestPermissions); prazno = posebni dostop (sistemska stran). */
    fun dovoljenja(v: Vrsta, sdk: Int): List<String> = when (v) {
        Vrsta.OBVESTILA -> if (sdk >= 33) listOf(OBVESTILA) else emptyList()
        // Android 14+: delni dostop (izbrane fotografije) - z IZBRANE sistem ponudi »Izberi fotografije« in »Dovoli vse«.
        Vrsta.SLIKE_VIDEI -> when {
            sdk >= 34 -> listOf(SLIKE, VIDEI, IZBRANE)
            sdk >= 33 -> listOf(SLIKE, VIDEI)
            else -> listOf(SHRAMBA)
        }
        Vrsta.GLASBA -> if (sdk >= 33) listOf(ZVOK) else listOf(SHRAMBA)
        else -> emptyList()
    }

    /**
     * Stanje dovoljenj, ki se vprasajo v aplikaciji, iz odobrenih ([odobrena]): fotografije in videi so DELNO, ko je
     * odobren samo izbor (Android 14+); vse ostalo DA ali NE.
     */
    fun stanjeIzOdobrenih(v: Vrsta, sdk: Int, odobrena: Set<String>): Stanje {
        val d = dovoljenja(v, sdk)
        if (d.isEmpty()) return Stanje.NE
        if (v == Vrsta.SLIKE_VIDEI && sdk >= 34) {
            if (SLIKE in odobrena && VIDEI in odobrena) return Stanje.DA
            return if (IZBRANE in odobrena || SLIKE in odobrena || VIDEI in odobrena) Stanje.DELNO else Stanje.NE
        }
        return if (d.all { it in odobrena }) Stanje.DA else Stanje.NE
    }

    /** Ali sistem okno dovoljenja se pokaze: nikoli vprasano ali zavrnjeno enkrat (utemeljitev); po drugi zavrnitvi ne. */
    fun sistemVprasa(vprasanoPrej: Boolean, utemeljitev: Boolean): Boolean = !vprasanoPrej || utemeljitev

    /** Gumb vrstice: v aplikaciji, kadar sistem se vprasa; sicer tocno prava sistemska stran. */
    fun dejanje(v: Vrsta, sdk: Int, stanje: Stanje, sistemVprasa: Boolean): Dejanje = when (v) {
        Vrsta.PRIKAZ -> Dejanje.STRAN_PRIKAZ
        Vrsta.CELOZASLONSKA -> Dejanje.STRAN_CELOZASLONSKA
        Vrsta.VNOS -> Dejanje.STRAN_DOSTOPNOST
        Vrsta.NAMESCANJE -> Dejanje.STRAN_NAMESCANJE
        // Obvestila pred Androidom 13 niso dovoljenje (samo stikalo v nastavitvah); izklopljena po dovoljenju tudi.
        Vrsta.OBVESTILA -> if (sdk >= 33 && stanje == Stanje.NE && sistemVprasa) Dejanje.VPRASAJ else Dejanje.STRAN_OBVESTILA
        Vrsta.SLIKE_VIDEI, Vrsta.GLASBA -> if (stanje != Stanje.DA && sistemVprasa) Dejanje.VPRASAJ else Dejanje.STRAN_APLIKACIJA
    }

    /**
     * Android 13+ »omejene nastavitve«: za aplikacijo, namesceno mimo trgovine, je stikalo sivo, dokler uporabnik v
     * Podatkih o aplikaciji (⋮) ne izbere »Dovoli omejene nastavitve«. Velja za dostopnost od Androida 13, za prikaz cez
     * druge aplikacije od Androida 15. Namig pokazemo, ko se je uporabnik s sistemske strani vrnil ([neuspelih] >= 1),
     * dovoljenja pa se vedno ni - zaznati sivega stikala ne moremo, zato ga ne pokazemo vnaprej.
     */
    fun namigOmejene(v: Vrsta, sdk: Int, stanje: Stanje, neuspelih: Int): Boolean = stanje != Stanje.DA && neuspelih >= 1 && when (v) {
        Vrsta.VNOS -> sdk >= 33
        Vrsta.PRIKAZ -> sdk >= 35
        else -> false
    }

    /** Brez cesar Safeer Link na tej napravi ne dela, kot uporabnik pricakuje (prikaz ob prvem zagonu). */
    fun pomembne(tv: Boolean): Set<Vrsta> = if (tv) setOf(Vrsta.PRIKAZ) else setOf(Vrsta.PRIKAZ, Vrsta.OBVESTILA)

    /**
     * Stran ob zagonu (enkrat): manjka pomembna postavka, ki je uporabniku se nismo pokazali ([zePokazane]). Kar je
     * uporabnik videl in namenoma pustil izklopljeno, ga ob vsaki posodobitvi ne nadleguje vec.
     */
    fun pokaziObZagonu(manjkajo: Set<Vrsta>, zePokazane: Set<Vrsta>, tv: Boolean): Boolean =
        (manjkajo intersect pomembne(tv)).any { it !in zePokazane }

    /** Stevilo postavk, ki niso dovoljene v celoti (vrstica v Nastavitvah: »Manjka: N«). */
    fun manjka(stanja: Map<Vrsta, Stanje>): Int = stanja.values.count { it != Stanje.DA }

    /**
     * Ali je Safeer Vnos vklopljen: seznam vklopljenih storitev dostopnosti (Settings.Secure, »a/b:c/d«) vsebuje nasega,
     * polno ([polno] = paket/razred) ali kratko ([kratko] = paket/.razred) zapisano; velikost crk ni pomembna.
     */
    fun vnosVklopljen(vklopljene: String?, polno: String, kratko: String): Boolean =
        vklopljene.orEmpty().split(':').map { it.trim() }.any { it.equals(polno, ignoreCase = true) || it.equals(kratko, ignoreCase = true) }

    /**
     * Ime posebnega dostopa »prikaz cez druge aplikacije«, kot ga uporabnik VIDI na svoji napravi (samo za prepoznavo -
     * gumb odpre tocno pravo stikalo): Samsung (One UI) ga imenuje drugace (sl »Pojavitev na vrhu«, en »Appear on top«),
     * ostali (cisti Android, OnePlus/OxygenOS, WP28 S) sl »Prekrivanje drugih aplikacij« (preverjeno na napravah lastnika
     * 25. 10. 2026), en »Display over other apps«. Drugih proizvajalcev (npr. Xiaomi) nismo preverili - zanje splosno
     * ime; nov proizvajalec se doda v [IMENA_PRIKAZA] (in nizi v values*).
     */
    enum class ImePrikaza { SPLOSNO, SAMSUNG }

    private val IMENA_PRIKAZA = mapOf("samsung" to ImePrikaza.SAMSUNG)

    fun imePrikaza(proizvajalec: String?): ImePrikaza = IMENA_PRIKAZA[proizvajalec.orEmpty().trim().lowercase()] ?: ImePrikaza.SPLOSNO

    /** Zapis mnozice postavk v nastavitve (imena, locena z vejico) in nazaj; neznana imena se izpustijo. */
    fun vNiz(v: Set<Vrsta>): String = v.map { it.name }.sorted().joinToString(",")
    fun izNiza(s: String?): Set<Vrsta> = s.orEmpty().split(',').mapNotNull { n -> Vrsta.values().firstOrNull { it.name == n.trim() } }.toSet()
}
