package si.safeer.tv.os

/**
 * Naloga 87 (lastnik: »Media Center naj uporabniku omogoci predpomnilnik na USB«): ciste odlocitve za predpomnilnik na
 * odstranljivem pomnilniku - katero mapo uporabiti, koliko sme zasesti, kdaj kaj zastara in kaj pocistimo najprej (LRU).
 * Brez Androida in brez diska (preizkus: tests/PredpomnilnikPravilaTest.kt); datoteke in mape ureja [UsbPredpomnilnik].
 *
 * Vrstni red branja ostane RAM -> notranji (majhen, hiter, meje kot doslej) -> USB (velik) -> omrezje. USB ne pospesi
 * omrezja ali dodatkov; da prostor, da vecjih predpomnilnikov ne brisemo prezgodaj (TV: 1,4 GB prostega od 4,9 GB).
 */
object PredpomnilnikPravila {
    const val MB = 1024L * 1024
    const val GB = 1024L * MB
    /** Samodejno: USB samo, ce ima (skupaj z nasim ze zasedenim) vec kot toliko prostora. */
    const val PRAG_PROSTEGA = 1 * GB
    /** Na USB vedno ostane toliko prostega (drugim aplikacijam in datotecnemu sistemu). */
    const val REZERVA = 256 * MB
    /** Velikosti, ki jih uporabnik lahko izbere (GB), in privzeta. */
    val VELIKOSTI_GB = listOf(2, 4, 8)
    const val PRIVZETA_GB = 2

    /**
     * Naloga 87 (+ 57b, lastnik: »uporabnik sam izbere, kje so metapodatki«): SAMODEJNO = USB, ce je priklopljen (sicer ta TV);
     * NOTRANJI = vedno ta TV; LINK = naprava v Safeer Linku ([Mesto.naprava] = njen id), ki ponuja vlogo predpomnilnik.
     * Notranji pomnilnik (majhen, hiter) je vedno prvi nivo; izbrano mesto je drugi (velik).
     */
    enum class Nacin { SAMODEJNO, NOTRANJI, LINK }

    data class Mesto(val nacin: Nacin, val naprava: String = "")

    private val RX_ID_NAPRAVE = Regex("^[A-Za-z0-9._:-]{1,128}$")

    fun mestoIzNiza(s: String?): Mesto = when {
        s == "notranji" -> Mesto(Nacin.NOTRANJI)
        s != null && s.startsWith("link:") && RX_ID_NAPRAVE.matches(s.removePrefix("link:")) -> Mesto(Nacin.LINK, s.removePrefix("link:"))
        else -> Mesto(Nacin.SAMODEJNO)
    }

    fun mestoVNiz(m: Mesto): String = when (m.nacin) {
        Nacin.NOTRANJI -> "notranji"
        Nacin.LINK -> if (RX_ID_NAPRAVE.matches(m.naprava)) "link:" + m.naprava else "samodejno"
        Nacin.SAMODEJNO -> "samodejno"
    }

    /** Izbrana velikost: samo ponujene, sicer privzeta (pokvarjena ali stara nastavitev). */
    fun velikostGb(izbrana: Int): Int = if (izbrana in VELIKOSTI_GB) izbrana else PRIVZETA_GB

    /**
     * Mapa aplikacije na zunanji shrambi (getExternalCacheDirs): [odstranljiv] (USB, kartica - ne vgrajena »zunanja« shramba
     * telefona), [priklopljen] (MEDIA_MOUNTED), [prostih] bajtov prostih, [nasih] bajtov, ki jih nas predpomnilnik tam ze zaseda.
     */
    data class Kandidat(val pot: String, val odstranljiv: Boolean, val priklopljen: Boolean, val prostih: Long, val nasih: Long)

    /**
     * Katero mapo uporabimo za predpomnilnik na USB; null = brez USB. NOTRANJI in LINK: nikoli USB. SAMODEJNO: prvi odstranljiv in
     * priklopljen pomnilnik z vec kot [PRAG_PROSTEGA] (nase datoteke stejejo kot prostor - polna meja ne izklopi USB).
     */
    fun izberi(nacin: Nacin, kandidati: List<Kandidat>): Kandidat? =
        if (nacin != Nacin.SAMODEJNO) null
        else kandidati.firstOrNull { it.odstranljiv && it.priklopljen && it.prostih + it.nasih > PRAG_PROSTEGA }

    /** Koliko bajtov sme predpomnilnik zasesti na [k]: izbrana velikost, a nikoli vec, kot dopusca prostor (z [REZERVA]). */
    fun meja(velikostGb: Int, k: Kandidat): Long =
        minOf(velikostGb(velikostGb) * GB, (k.nasih + k.prostih - REZERVA).coerceAtLeast(0L))

    /** Podmape predpomnilnika na USB (vrsta vsebine). */
    const val NASLOVNICE = "naslovnice"
    const val KATALOGI = "katalogi"
    const val POLICE = "police"
    const val IPTV = "iptv"
    const val KANALI = "kanali"

    private const val URA = 3_600_000L
    private const val DAN = 24 * URA

    /**
     * Kako dolgo datoteka vrste [vrsta] na USB velja (potem jo ciscenje izbrise): naslovnice 14 dni in katalogi 24 h kot
     * notranji; police (zadnja znana mreza) 7 dni kot seme; seznami IPTV 7 dni (osvezijo se po 24 h, stari velja do takrat);
     * strani kanalov 24 h (beremo jih samo, dokler veljajo za katalog).
     */
    fun ttl(vrsta: String): Long = when (vrsta) {
        NASLOVNICE -> 14 * DAN
        KATALOGI -> DAN
        POLICE -> 7 * DAN
        IPTV -> 7 * DAN
        KANALI -> DAN
        else -> 7 * DAN
    }

    /** Datoteka na USB za ciscenje: [vrsta] = podmapa, [spremenjena] = zadnja uporaba (zapis ali branje). */
    data class Datoteka(val pot: String, val vrsta: String, val bajtov: Long, val spremenjena: Long)

    /** Zacasna datoteka (prekinjen zapis, odklop med pisanjem) je smet po toliko casa. */
    const val ZACASNA_SMET_MS = URA

    /**
     * Katere datoteke izbrisemo: najprej zastarele ([ttl]), zacasne ».tmp« starejse od [ZACASNA_SMET_MS] in tiste z uro iz
     * prihodnosti (> 1 dan), nato najdlje neuporabljene, dokler skupaj ni najvec [meja] bajtov (LRU).
     */
    fun zaBrisanje(datoteke: List<Datoteka>, meja: Long, zdaj: Long): List<String> {
        val ven = LinkedHashSet<String>()
        for (d in datoteke) {
            val starost = zdaj - d.spremenjena
            if (starost > ttl(d.vrsta) || starost < -DAN || (d.pot.endsWith(".tmp") && starost > ZACASNA_SMET_MS)) ven += d.pot
        }
        var skupaj = datoteke.filter { it.pot !in ven }.sumOf { it.bajtov }
        if (skupaj > meja) for (d in datoteke.filter { it.pot !in ven }.sortedBy { it.spremenjena }) {
            if (skupaj <= meja) break
            ven += d.pot
            skupaj -= d.bajtov
        }
        return ven.toList()
    }

    /** Ciscenje po vsaki dvajsetini meje zapisanih bajtov ali najvec na 10 min (in ob prvem zapisu procesa). */
    fun jeCasZaCiscenje(zapisanihOdZadnjega: Long, zadnjeOb: Long, zdaj: Long, meja: Long): Boolean =
        zadnjeOb == 0L || zapisanihOdZadnjega > meja / 20 || zdaj - zadnjeOb !in 0 until 10 * 60_000L

    /** Kako dolgo velja zadnja preverba, ali je USB priklopljen (potem pogledamo znova; odklop izgubi najvec ta cas). */
    const val PREVERBA_MS = 30_000L

    // ---------------------------------------------------------------- naprava v Safeer Linku (57b)

    /** Zmoznost, s katero se naprava v Linku javi kot predpomnilnik (ukazi cache.get, cache.put, cache.stat). */
    const val ZMOZNOST_LINK = "cache"
    /** Najdlje cakanje na odgovor naprave pri branju - potem takoj notranji ali omrezje (uporabnik nikoli ne caka). */
    const val LINK_ROK_MS = 300L
    /** Najvec bajtov vrednosti po Linku (ukaz gre po obstojeci sifrirani povezavi kot JSON; seznami IPTV ostanejo na TV ali USB). */
    const val LINK_NAJVEC_BAJTOV = 256 * 1024
    /** Vrste, ki gredo po Linku: majhni metapodatki. Strani kanalov in seznami IPTV ne (velikost, pogostost). */
    val LINK_VRSTE = setOf(NASLOVNICE, KATALOGI, POLICE)

    /**
     * Ali vrednost sme na drugo napravo: samo vrste [LINK_VRSTE], najvec [LINK_NAJVEC_BAJTOV] in NIKOLI vsebina zasebnih
     * dodatkov ali zasebnih kategorij ([zasebno]: ZasebniDodatki, ZasebniKanali - odloci klicatelj, neznano steje kot zasebno).
     */
    fun smeNaLink(vrsta: String, bajtov: Int, zasebno: Boolean): Boolean =
        !zasebno && vrsta in LINK_VRSTE && bajtov in 1..LINK_NAJVEC_BAJTOV

    private val RX_ODTIS = Regex("^[0-9a-f]{40}$")

    /** Kljuc na napravi: »vrsta/odtis« (odtis SHA-1 naslova, ne naslov sam); null = neveljaven. */
    fun kljucLink(vrsta: String, odtis: String): String? = if (vrsta in LINK_VRSTE && RX_ODTIS.matches(odtis)) "$vrsta/$odtis" else null

    /** Odgovor naprave na cache.get velja: cas zapisa ni iz prihodnosti (> 1 dan) in ni starejsi od [ttl] vrste. */
    fun veljavenOdgovor(vrsta: String, cas: Long, zdaj: Long): Boolean = cas > 0 && zdaj - cas in -DAN..ttl(vrsta)

    /**
     * »Varovalka«: po 3 zaporednih neodgovorih (potek [LINK_ROK_MS], napaka) napravo 60 s ne sprasujemo - vsako branje bi
     * sicer dodalo 300 ms. Uspeh stevec pobrise (klicatelj).
     */
    fun linkSme(zaporednihNapak: Int, zadnjaNapakaOb: Long, zdaj: Long): Boolean =
        zaporednihNapak < 3 || zdaj - zadnjaNapakaOb !in 0 until 60_000L

    /** Najvec hkratnih zapisov (cache.put) v teku; vec jih izpustimo (predpomnilnik ni nujen). */
    const val LINK_ZAPISOV_HKRATI = 8

    /** Naprave v izbiri mesta: tiste z zmoznostjo [ZMOZNOST_LINK], brez te naprave, po imenu. */
    fun napraveZaIzbiro(naprave: List<Triple<String, String, List<String>>>, jaz: String): List<Pair<String, String>> =
        naprave.filter { (id, _, z) -> id != jaz && ZMOZNOST_LINK in z }.map { it.first to it.second }.distinctBy { it.first }.sortedBy { it.second.lowercase() }

    // ---------------------------------------------------------------- SQLite (57c)

    /**
     * Naloga 87 (57c, lastnik: »en predpomnilnik = ena datoteka SQLite«): na exFAT/FAT32 ima vsaka majhna datoteka vsaj eno
     * gruco (32-128 KB) - tisoce plakatov in JSON-ov zapravi prostor. Vrednost do [BLOB_NAJVEC] gre v bazo kot BLOB, vecja
     * kot datoteka poleg baze (zapis v bazi kaze nanjo).
     */
    const val BLOB_NAJVEC = 200 * 1024
    /** Notranja baza (prvi nivo na disku, ta TV): majhna - prostor na TV je skop (1,4 GB prostega). */
    const val NOTRANJA_MEJA = 128 * MB

    /** Vnos v bazi za ciscenje: [poteka] = ustvarjeno + [ttl] vrste, [zadnjaUporaba] za LRU. */
    data class Vnos(val kljuc: String, val velikost: Long, val zadnjaUporaba: Long, val poteka: Long)

    /** Kdaj vnos vrste [vrsta], ustvarjen ob [ustvarjeno], zastara. */
    fun poteka(vrsta: String, ustvarjeno: Long): Long = ustvarjeno + ttl(vrsta)

    /**
     * Kateri vnosi gredo iz baze: zastareli ([Vnos.poteka] <= zdaj) in z uro iz prihodnosti (> 1 dan), nato najdlje
     * neuporabljeni, dokler skupaj ni najvec [meja] bajtov (LRU). Isto pravilo kot [zaBrisanje] za datoteke.
     */
    fun zaBrisanjeVnosov(vnosi: List<Vnos>, meja: Long, zdaj: Long): List<String> {
        val ven = LinkedHashSet<String>()
        for (v in vnosi) if (v.poteka <= zdaj || v.zadnjaUporaba - zdaj > DAN) ven += v.kljuc
        var skupaj = vnosi.filter { it.kljuc !in ven }.sumOf { it.velikost }
        if (skupaj > meja) for (v in vnosi.filter { it.kljuc !in ven }.sortedBy { it.zadnjaUporaba }) {
            if (skupaj <= meja) break
            ven += v.kljuc
            skupaj -= v.velikost
        }
        return ven.toList()
    }

    /** Branje zapise cas uporabe (pisanje!) najvec enkrat na uro na vnos - LRU je dovolj natancen, disk pa miruje. */
    fun osveziUporabo(zadnjaUporaba: Long, zdaj: Long): Boolean = zdaj - zadnjaUporaba !in 0 until URA

    /** Kljuc vnosa: »vrsta/odtis« (odtis SHA-1 naslova ali kljuca police - nikoli naslov sam). */
    fun kljucBaze(vrsta: String, odtis: String): String = "$vrsta/$odtis"

    /** Ime datoteke za vrednost vecjo od [BLOB_NAJVEC] (poleg baze): kljuc brez locil. */
    fun imeVelike(kljuc: String): String = kljuc.replace('/', '_').filter { it.isLetterOrDigit() || it == '_' }
}
