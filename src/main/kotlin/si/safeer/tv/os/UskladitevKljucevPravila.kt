package si.safeer.tv.os

/**
 * Samodejno usklajevanje kljucev in dodatkov v ozjem krogu (lastnik, 25. 10. 2026: »vneses enkrat, npr. na telefonu«):
 * debrid kljuci (TorBox, Real-Debrid, AllDebrid), indeksator Usenet (naslov + kljuc) in dodatki s poverilnico (pravi
 * naslov, ki je v aplikaciji nadomestek) gredo sami na vsako napravo v OZJEM KROGU te naprave, ki zna dejanje [DEJANJE]
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

    fun kljucDebrid(ponudnik: String) = PREDPONA_DEBRID + ponudnik
    fun kljucDodatka(odtis: String) = PREDPONA_DODATKA + odtis

    private val RX_ODTIS = Regex("^[0-9a-f]{16}$")

    fun veljavenKljuc(k: String, ponudniki: Set<String>): Boolean = when {
        k.startsWith(PREDPONA_DEBRID) -> k.removePrefix(PREDPONA_DEBRID) in ponudniki
        k == KLJUC_USENET -> true
        k.startsWith(PREDPONA_DODATKA) -> RX_ODTIS.matches(k.removePrefix(PREDPONA_DODATKA))
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
     * Grok 15, N3: zapisi za kljuce, ki jih ta naprava ta hip ne more prebrati (Keystore), se ne uporabijo - razveljavitev
     * jih ne bi mogla vrniti (izbrisala bi jih, tudi na drugi napravi). Pridejo znova ob naslednji uskladitvi.
     */
    fun brezNeberljivih(zapisi: List<Zapis>, neberljivi: Set<String>): List<Zapis> = zapisi.filterNot { it.kljuc in neberljivi }

    /**
     * Grok 15, N4: druga sprememba iste naprave se zdruzi s prvo - kljuci obeh, za vsak kljuc vrednost PRED prvo spremembo
     * (razveljavitev sicer vrne vmesno vrednost, npr. napadalcevo prvo).
     */
    fun zdruziSpremembi(stariKljuci: List<String>, starePrejsnje: Map<String, String?>, noviKljuci: List<String>,
                        novePrejsnje: Map<String, String?>): Pair<List<String>, Map<String, String?>> {
        val kljuci = (stariKljuci + noviKljuci).distinct()
        val vrednosti = LinkedHashMap<String, String?>()
        for (k in kljuci) vrednosti[k] = if (k in stariKljuci) starePrejsnje[k] else novePrejsnje[k]
        return kljuci to vrednosti
    }

    /** Grok 15, N9: prehod odtisov je koncan, ko med obstojecimi kljuci ni nobenega neberljivega (sicer ga ponovimo). */
    fun prehodKoncan(obstojeci: Set<String>, neberljivi: Set<String>): Boolean = obstojeci.none { it in neberljivi }

    /**
     * Grok 15, N10: gostitelj dodatka za prikaz - z vrednostjo velja gostitelj iz nje ([izVrednosti]); trditev posiljatelja
     * ([trditev], polje g) samo brez vrednosti in samo kot ime gostitelja (brez znakov za smer besedila in nevidnih znakov).
     */
    fun prikazGostitelja(vrednost: String, trditev: String, izVrednosti: String): String =
        if (vrednost.isNotBlank() && izVrednosti.isNotBlank()) izVrednosti else samoImeGostitelja(trditev)

    /** Ime gostitelja: samo [a-z0-9.-] (IDN je v punycode). */
    fun samoImeGostitelja(g: String): String =
        g.lowercase(java.util.Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }.take(253)

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
