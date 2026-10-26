package si.safeer.tv.os

/**
 * Samodejno usklajevanje kljucev in dodatkov v ozjem krogu (lastnik, 25. 10. 2026: »vneses enkrat, npr. na telefonu«):
 * debrid kljuci (TorBox, Real-Debrid, AllDebrid), indeksator Usenet (naslov + kljuc), dodatki s poverilnico (pravi
 * naslov, ki je v aplikaciji nadomestek) in od 26. 10. 2026 viri z racunom IPTV ([PREDPONA_IPTV]) gredo sami na vsako napravo v OZJEM KROGU te naprave, ki zna dejanje [DEJANJE]
 * (zmoznost [ZMOZNOST]) - po sifrirani poti od naprave do naprave (Link, isto kot »Poslji kljuce«). Na prejemni napravi se
 * shranijo sifrirani (Android Keystore; namizje: sistemska zbirka skrivnosti). Protokol za namizje:
 * outputs/nacrti/uskladitev-kljucev.md.
 *
 * Zapis = kljuc + cas spremembe (ms, nikoli starejsi od prejsnjega zapisa) + izvor (id naprave, ki je spremenila) +
 * izbrisan (grob). Velja novejsi po (cas, izvor, izbrisan) - [novejsi]: vse naprave pridejo do istega stanja ne glede na
 * vrstni red sporocil, ponovljeno sporocilo ne spremeni nicesar. Izbris se prenese kot grob s casom; kasnejsi vnos ga
 * preglasi. Vrednosti so v sporocilu samo pri zapisih, ki niso izbrisani; v nastavitvah je le odtis vrednosti (iz njega se
 * kljuca ne da izracunati) - z njim naprava opazi lastno spremembo.
 *
 * Potrditev: enkrat na NOVO napravo na OBEH straneh (neodvisni pregled Webtor rezerve 25. 10. 2026, U1): pobudnik poslje
 * povabilo samo z imeni kljucev ([zaPovabilo], U3), vrednosti gredo sele napravi, ki jo je uporabnik na tej napravi
 * potrdil ([vrednostiZa]); prejeto od nepotrjene naprave caka na potrditev tu ([sprejem]) najvec [CAKA_VELJA_MS]. Zapis
 * sprejmemo samo, ce je njegov izvor posiljatelj sam ([izvorPosiljatelja]) - in posljemo samo lastne zapise ([zaPoslati],
 * U2): vsak krog je svoj svet, vsaka naprava odloca o svojem. Cas iz prihodnosti najvec [NAJVEC_NAPREJ_MS]. Naprava, ki ni
 * (vec) v ozjem krogu, nicesar ne dobi in nicesar ne sprejme - preveri se ob vsakem posiljanju in prejemu. Brisanja na
 * daljavo ni (lastnik: kljuce izgubljene naprave zamenja uporabnik sam). Ciste funkcije - tests/UskladitevKljucevPravilaTest.kt.
 */
object UskladitevKljucevPravila {
    const val DEJANJE = "secrets.sync"
    const val ZMOZNOST = "secrets"
    const val VERZIJA = 1
    const val NAJVEC_ZAPISOV = 64
    /** Cas zapisa za vrednost, ki je obstajala pred tem usklajevanjem (»uvoz«): vsaka kasnejsa sprememba jo preglasi. */
    const val CAS_UVOZA = 1L
    /**
     * Cas iz prihodnosti (napacna ura druge naprave) sprejmemo najvec toliko naprej. Pregled U1: prej 366 dni - naprava si
     * je s casom v prihodnosti zagotovila zmago v zadnjem zapisu za leto dni.
     */
    const val NAJVEC_NAPREJ_MS = 10L * 60 * 1000
    /** Prejeto od nepotrjene naprave caka na potrditev najvec toliko (kot DebridPravila.PREJETO_VELJA_MS; pregled U3, U11). */
    const val CAKA_VELJA_MS = 10L * 60 * 1000

    const val PREDPONA_DEBRID = "debrid:"
    const val KLJUC_USENET = "usenet"
    const val PREDPONA_DODATKA = "dodatek:"
    /**
     * Lastnik, 26. 10. 2026: vir z racunom IPTV (seznam Xtream, tok z geslom - [IptvPravila.skrivnostVira]); vrednost je JSON
     * `{"url": pravi naslov, "tip": vrsta vira, "ime": ime}`, odtis = [IptvPravila.odtisRacuna] (enak odtisu nadomestka).
     * Naprava, ki kljuca ne pozna (starejsa razlicica, namizje), ga prezre ([veljavenKljuc]).
     */
    const val PREDPONA_IPTV = "iptv:"

    fun kljucDebrid(ponudnik: String) = PREDPONA_DEBRID + ponudnik
    fun kljucDodatka(odtis: String) = PREDPONA_DODATKA + odtis
    fun kljucIptv(odtis: String) = PREDPONA_IPTV + odtis

    private val RX_ODTIS = Regex("^[0-9a-f]{16}$")

    fun veljavenKljuc(k: String, ponudniki: Set<String>): Boolean = when {
        k.startsWith(PREDPONA_DEBRID) -> k.removePrefix(PREDPONA_DEBRID) in ponudniki
        k == KLJUC_USENET -> true
        k.startsWith(PREDPONA_DODATKA) -> RX_ODTIS.matches(k.removePrefix(PREDPONA_DODATKA))
        k.startsWith(PREDPONA_IPTV) -> RX_ODTIS.matches(k.removePrefix(PREDPONA_IPTV))
        else -> false
    }

    /** Stanje kljuca na tej napravi (brez vrednosti). [odtis] = odtis vrednosti, "" pri grobu. */
    data class Meta(val cas: Long, val izvor: String, val izbrisan: Boolean, val odtis: String)

    /**
     * Zapis v sporocilu; [vrednost] samo, ce ni izbrisan in je prejemnik potrjen ([vrednostiZa]). [gostitelj]: pri dodatku
     * gostitelj (za okno potrditve), tudi v povabilu brez vrednosti.
     */
    data class Zapis(val kljuc: String, val cas: Long, val izvor: String, val izbrisan: Boolean, val vrednost: String,
                     val gostitelj: String = "")

    /** Zapis (cas, izvor, izbrisan) je novejsi od [star] (null = kljuca tu se ni). */
    fun novejsi(cas: Long, izvor: String, izbrisan: Boolean, star: Meta?): Boolean {
        if (star == null) return true
        if (cas != star.cas) return cas > star.cas
        if (izvor != star.izvor) return izvor > star.izvor
        return izbrisan && !star.izbrisan
    }

    fun novCas(ura: Long, prejsnji: Long): Long = maxOf(ura, prejsnji + 1)

    /**
     * Lastne spremembe te naprave: [trenutni] = kljuc -> odtis trenutne vrednosti (kar je zdaj vpisano), [neznani] = kljuci,
     * katerih vrednosti ta trenutek ni mogoce prebrati (Keystore) - zanje ni ne spremembe ne groba. [uvoz] = prvi zagon
     * usklajevanja: obstojece vrednosti dobijo [CAS_UVOZA] (ne preglasijo kasnejsih sprememb z drugih naprav).
     * Vrne nove zapise za kljuce, ki so se spremenili (vsi ostali ostanejo, kot so).
     */
    fun lokalneSpremembe(meta: Map<String, Meta>, trenutni: Map<String, String>, neznani: Set<String>, jaz: String, ura: Long,
                         uvoz: Boolean): Map<String, Meta> {
        val izid = LinkedHashMap<String, Meta>()
        for ((k, odtis) in trenutni) {
            if (k in neznani) continue
            val m = meta[k]
            when {
                m == null -> izid[k] = Meta(if (uvoz) CAS_UVOZA else ura, jaz, false, odtis)
                m.izbrisan || m.odtis != odtis -> izid[k] = Meta(novCas(ura, m.cas), jaz, false, odtis)
            }
        }
        for ((k, m) in meta) {
            if (k in trenutni || k in neznani || m.izbrisan) continue
            izid[k] = Meta(novCas(ura, m.cas), jaz, true, "")
        }
        return izid
    }

    /** Kaj gre za en kljuc (debrid ali indeksator) v [lokalneSpremembe]: vrednost, neznan ali nic (izbris). */
    enum class Oddaja { VREDNOST, NEZNAN, BREZ }

    /**
     * Lastnik 25. 10. 2026 (v polje TorBox vpisan vzorec se je raznesel po ozjem krogu): z vrednostjo gre samo kljuc, ki ga je
     * storitev ali indeksator potrdil ([velja]). Shranjen, a nepreverjen, zavrnjen ali ta hip neberljiv ([prebran] false)
     * kljuc je neznan - drugim napravam ne povozi in ne izbrise veljavnega. Kljuca, ki ga ni, ni (izbris).
     */
    fun oddaja(prebran: Boolean, shranjen: Boolean, velja: Boolean): Oddaja = when {
        prebran && velja -> Oddaja.VREDNOST
        prebran || shranjen -> Oddaja.NEZNAN
        else -> Oddaja.BREZ
    }

    /**
     * Grok 52, Ñ4 (ustreza Linuxovemu Ê4): vir z racunom IPTV gre z vrednostjo v ozji krog te naprave samo, ce je bil dodan na TEJ
     * napravi ([dodanTu] = MedijskiViri.dodanTuZDokazom: zapis ob dodajanju tu ali potrditev uporabnika). Prejet (lists.get od
     * naprave v krogu ali secrets.sync) in podedovan od starejse razlicice je tuj, tudi na TV (Grok 60, Ů2: izjeme za vire izpred
     * pravila ni): neznan - ne gre naprej in ne ustvari groba. Ozji krog je na napravo (uporabnik ga ureja na vsaki posebej):
     * brez tega bi geslo preslo A -> B -> C, ki ni v krogu A.
     */
    fun oddajaIptv(berljiv: Boolean, dodanTu: Boolean): Oddaja = if (berljiv && dodanTu) Oddaja.VREDNOST else Oddaja.NEZNAN

    /**
     * Prejeti zapisi -> tisti, ki jih je treba uporabiti tu (novejsi od lokalnih), z veljavnim kljucem in vrednostjo
     * ([veljavnaVrednost]). Najvec [NAJVEC_ZAPISOV]; za isti kljuc velja najnovejsi; cas iz dalnje prihodnosti zavrnemo.
     */
    fun zaUporabo(meta: Map<String, Meta>, prejeti: List<Zapis>, ura: Long, ponudniki: Set<String>,
                  veljavnaVrednost: (String, String) -> Boolean, izvorDovoljen: (String) -> Boolean): List<Zapis> {
        val najnovejsi = LinkedHashMap<String, Zapis>()
        for (z in prejeti.take(NAJVEC_ZAPISOV)) {
            if (!veljavenZapis(z, ura, ponudniki, veljavnaVrednost)) continue
            // Pregled U1: izvor zapisa je posiljatelj sam - naprava se ne more predstaviti kot druga.
            if (!try { izvorDovoljen(z.izvor) } catch (_: Exception) { false }) continue
            val prej = najnovejsi[z.kljuc]
            if (prej == null || novejsi(z.cas, z.izvor, z.izbrisan, Meta(prej.cas, prej.izvor, prej.izbrisan, ""))) najnovejsi[z.kljuc] = z
        }
        return najnovejsi.values.filter { novejsi(it.cas, it.izvor, it.izbrisan, meta[it.kljuc]) }
    }

    fun veljavenZapis(z: Zapis, ura: Long, ponudniki: Set<String>, veljavnaVrednost: (String, String) -> Boolean): Boolean {
        if (!veljavenKljuc(z.kljuc, ponudniki)) return false
        if (z.cas < CAS_UVOZA || z.cas > ura + NAJVEC_NAPREJ_MS) return false
        if (z.izvor.isBlank() || z.izvor.length > 128 || z.izvor.any { it < ' ' }) return false
        if (z.izbrisan) return z.vrednost.isEmpty()
        if (z.vrednost.isEmpty() || z.vrednost.length > 4096 || z.vrednost.any { it < ' ' && it != '\n' }) return false
        return try { veljavnaVrednost(z.kljuc, z.vrednost) } catch (_: Exception) { false }
    }

    /**
     * Stanje te naprave za posiljanje: samo LASTNI zapisi (izvor [jaz]; pregled U2 - prejetih tujih ne posiljamo naprej, sicer
     * bi prisli do naprav, ki jih izvorna naprava nima v krogu) z vrednostmi iz [vrednosti]; brez vrednosti izpadejo.
     */
    fun zaPoslati(meta: Map<String, Meta>, vrednosti: Map<String, String>, jaz: String): List<Zapis> = meta.mapNotNull { (k, m) ->
        if (m.izvor != jaz || jaz.isBlank()) null
        else if (m.izbrisan) Zapis(k, m.cas, m.izvor, true, "")
        else vrednosti[k]?.let { Zapis(k, m.cas, m.izvor, false, it) }
    }.take(NAJVEC_ZAPISOV)

    /** Povabilo (pregled U3): imena kljucev brez vrednosti - vrednosti sele po potrditvi na drugi napravi. */
    fun zaPovabilo(zapisi: List<Zapis>): List<Zapis> = zapisi.map { it.copy(vrednost = "") }

    /** Vrednosti gredo samo napravi, ki jo je uporabnik na tej napravi potrdil (pregled U3). */
    fun vrednostiZa(stanje: Stanje?): Boolean = stanje == Stanje.POTRJENA

    /** Pregled U1: izvor zapisa (fizicna naprava) je posiljatelj sam; prazno ni nikoli. */
    fun izvorPosiljatelja(izvorNaprava: String, odNaprava: String): Boolean = izvorNaprava.isNotBlank() && izvorNaprava == odNaprava

    /** Cakajoce s prejetimi zapisi velja [CAKA_VELJA_MS] (pregled U3, U11). */
    fun cakaVelja(prejeto: Long, zdaj: Long): Boolean = zdaj - prejeto in 0 until CAKA_VELJA_MS

    /**
     * Pregled U1: kateri uporabljeni zapisi so ZAMENJALI ali IZBRISALI kljuc, ki je tu ze obstajal - te uporabniku pokazemo z
     * moznostjo razveljavitve. Nov kljuc (ga tu ni bilo) ali grob na grob nista zamenjava.
     */
    fun zamenjave(meta: Map<String, Meta>, uporabljeni: List<Zapis>): List<Zapis> =
        uporabljeni.filter { z -> meta[z.kljuc]?.let { !it.izbrisan } == true }

    // ------------------------------------------------------------------ naprave

    enum class Stanje {
        /** Ta naprava ji je poslala prva - njene zapise sprejme sama. */
        POVABLJENA,
        /** Potrjena (tu ali tam): zapisi gredo v obe smeri brez vprasanja. */
        POTRJENA,
        /** Prvi zapisi z nje cakajo na potrditev na tej napravi. */
        CAKA,
        /** Uporabnik je usklajevanje z njo zavrnil: nic ne gre ne tja ne od tam (preklic v seznamu naprav). */
        ZAVRNJENA,
        /** V ozjem krogu, a starejsa razlicica brez [ZMOZNOST] (ne zna samodejnega usklajevanja). */
        STARA
    }

    enum class Odziv { SPREJMI, VPRASAJ, ZAVRNI }

    /**
     * Kaj ta naprava stori s prejetimi zapisi z naprave v stanju [stanje] (null = se nikoli). Pregled U1: tudi naprava, ki jo
     * je ta povabila (POVABLJENA), mora biti tu enkrat potrjena - prej je pobudnik njene zapise uporabil tiho.
     */
    fun sprejem(vklopljeno: Boolean, vKrogu: Boolean, stanje: Stanje?): Odziv = when {
        !vklopljeno || !vKrogu -> Odziv.ZAVRNI
        stanje == Stanje.POTRJENA -> Odziv.SPREJMI
        stanje == Stanje.ZAVRNJENA -> Odziv.ZAVRNI
        else -> Odziv.VPRASAJ
    }

    /**
     * Ali tej napravi posljemo svoje kljuce in dodatke: vklopljeno, v ozjem krogu TE naprave (preveri se ob vsakem posiljanju
     * - naprava, ki jo uporabnik odstrani iz kroga, nic vec ne dobi), sifrirana pot od naprave do naprave in povezan Link,
     * zna dejanje, ni zavrnjena.
     */
    fun smePoslati(vklopljeno: Boolean, vKrogu: Boolean, zascita: Boolean, povezan: Boolean, zmoznost: Boolean, stanje: Stanje?): Boolean =
        vklopljeno && vKrogu && zascita && povezan && zmoznost && stanje != Stanje.ZAVRNJENA && stanje != Stanje.CAKA
}
