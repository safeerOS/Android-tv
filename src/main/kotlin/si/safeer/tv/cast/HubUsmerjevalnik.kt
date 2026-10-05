package si.safeer.tv.cast

import java.security.SecureRandom
import java.util.UUID

/**
 * Usmerjevalnik Safeer Huba na televizorju: register naprav, seznanjanje, vstopnice in
 * posredovanje sporocil Cast/Sync.
 *
 * Govori natanko isti jezik kot Hub na racunalniku (core/cast/protocol.py, hub.py, router.py,
 * razlicica protokola 0.2), zato odjemalcev na telefonu in televizorju ni treba spreminjati -
 * zamenja se le, kje streznik stoji.
 *
 * Tri stvari so tu drugacne kot na racunalniku, in to namenoma:
 *
 *  1. Potrjevanje je krajevno in kratko. Kodo pokaze gostitelj - naprava, na kateri Safeer
 *     Link tece - uporabnik pa jo prepise na napravo, ki se prikljucuje. Kdor gostiteljevega
 *     zaslona ne vidi, se ne more prikljuciti, tudi ce je v istem omrezju; prav zato je
 *     Safeer Link uporaben tudi na javnem wifiju. Sest stevilk se z daljincem prebere, ne
 *     tipka - tipka jih telefon. Starejsi odjemalec, ki kodo se vedno kaze pri sebi in caka
 *     na potrditev tu, je prepoznan po povprasevanju /cast/pair/claim in ga vmesnik obravnava
 *     po starem. Zetona se na televizor ne vnasa nikoli.
 *  2. Potrditev ni na voljo po omrezju. Koncne tocke za cakajoce prijave, potrditev in odvzem
 *     dostopa obstajajo samo kot metode, ki jih poklice uporabniski vmesnik televizorja.
 *     Cesar ni v streznku, ni mogoce zlorabiti.
 *  3. Meje so postavljene ze v zasnovi, ne naknadno. Register naprav, cakajoce prijave,
 *     vstopnice in shramba za sinhronizacijo imajo vsak svojo zgornjo mejo, ker je televizor
 *     naprava z malo pomnilnika in ga sistem ob pomanjkanju brez opozorila ubije.
 *
 * Razred ne pozna Androida, da ga je mogoce preizkusiti v navadnem JVM.
 */
class HubUsmerjevalnik(
    private val shramba: Shramba? = null,
    internal val ura: () -> Long = { System.currentTimeMillis() },
    internal val nakljucni: (Int) -> String = { privzetoNakljucno(it) },
    /** Skupni krog zaupanja naprave (KrogNaprave.krog): hub in naprava NE smeta imeti vsak svoje kopije iste shrambe. */
    krogZaupanja: KrogZaupanja? = null
) {

    /** Trajna shramba za zetone seznanjenih naprav (na Androidu SharedPreferences). */
    interface Shramba {
        fun beri(kljuc: String): String?
        fun pisi(kljuc: String, vrednost: String)
    }

    /** Ena povezana naprava, kakor jo vidi usmerjevalnik. */
    interface Odjemalec {
        val naslov: String
        /** Vstopnica, s katero je bila povezava odprta (poizvedba ?ticket=); veze prijavo na napravo. */
        val vstopnica: String? get() = null
        fun poslji(besedilo: String)
        fun zapri(koda: Int, razlog: String)
    }

    internal class Prijava(
        val pairId: String,
        val deviceId: String,
        val ime: String,
        val pin: String,
        val naslov: String,
        var nastala: Long,
        var potrjena: Boolean = false,
        var zeton: String? = null,
        /** Koliko napacnih kod je naprava ze vtipkala; po NAJVEC_POSKUSOV prijava pade. */
        var poskusov: Int = 0,
        /**
         * Naprava govori po starem: kodo kaze sama in caka, da jo uporabnik potrdi tu.
         * Prepoznamo jo po tem, da povprasuje /cast/pair/claim. Samo takim napravam
         * vmesnik ponudi gumb Potrdi - vse druge kodo vtipkajo.
         */
        var staroPovprasevanje: Boolean = false,
        /** Tekoci krog SPAKE2 (po /cast/pair/spake, pred /cast/pair/finish). */
        var spake: Spake2? = null,
        /** Koliko krogov SPAKE2 je naprava zacela; tudi to je omejeno, da ne sonda v nedogled. */
        var krogov: Int = 0
    )

    /** Izid vnosa kode na napravi, ki se prikljucuje. */
    data class IzidKode(val zeton: String?, val napaka: String?)

    /** Izid prvega koraka SPAKE2: Hubovo sporocilo in potrditev, ali napaka. */
    data class IzidSpake(val pa: ByteArray?, val ca: ByteArray?, val napaka: String?)

    internal class Kategorija(
        val ime: String,
        val razlicica: Double,
        val cas: Double,
        val podatkiSurovo: String,
        val vir: String?
    ) {
        val bajtov: Int = podatkiSurovo.toByteArray(Charsets.UTF_8).size
    }

    /** Podatki o cakajoci prijavi za vmesnik televizorja. */
    data class CakajocaPrijava(
        val pairId: String,
        val ime: String,
        val pin: String,
        val naslov: String,
        val starostSekund: Int,
        /** Naprava po starem caka na potrditev tu; nova napravo kodo vtipka pri sebi. */
        val potrebujePotrditev: Boolean = false
    )

    data class SeznanjenaNaprava(val deviceId: String, val ime: String, val seznanjenaOb: Double)

    internal val kljucnica = Any()

    /**
      * Kdo je povezan in kaj o sebi pove (RegisterNaprav). Kljucavnica je ista kot tu: register
      * je del istega stanja, le da je prijava in odklop naprav zdaj na enem mestu, ne razsuto po
      * dveh tisoc vrsticah.
      */
    internal val register = RegisterNaprav(kljucnica, { ura() }, NAJVEC_NAPRAV, NAJVEC_IMENA)
    private val prijave = LinkedHashMap<String, Prijava>()
    private val zetoni = LinkedHashMap<String, SeznanjenaNaprava>()
    /** Vstopnica za WebSocket: kdaj je bila izdana in kateri napravi (zeton ali podpis), ce je znano. */
    internal class Vstopnica(val izdana: Long, val deviceId: String?, val podpis: Boolean = false)

    internal val vstopnice = LinkedHashMap<String, Vstopnica>()

    /** Porabljene vstopnice, vezane na napravo: vstopnica -> device_id, dokler se povezava ne prijavi. */
    private val vezaneVstopnice = LinkedHashMap<String, String>()
    /** Porabljene vstopnice, izdane s podpisom kljuca iz kroga (samo take smejo odpreti sosednjo povezavo). */
    private val podpisaneVstopnice = LinkedHashSet<String>()
    internal val sinhronizacija = LinkedHashMap<String, Kategorija>()

    /** Klice se, ko se seznam cakajocih prijav spremeni, da vmesnik pokaze kodo brez spraševanja. */
    @Volatile
    var naSpremembePrijav: (() -> Unit)? = null

    /**
     * Prstni odtis (SHA-256, hex) potrdila TLS tega Huba. Vpleten je v seznanitev: naprava
     * v svoj izracun vplete odtis, ki ga je videla na povezavi, Hub svojega. Ce ju je kdo
     * vmes zamenjal (napadalec s svojim potrdilom), se potrditvi ne ujemata in seznanitev
     * pade - napadalec kode ne pozna in je ne more popraviti. Prazen v preizkusih brez TLS.
     */
    @Volatile
    var lastniOdtis: String = ""

    /** Klice se ob spremembi seznama povezanih naprav (za prikaz v Safeer Linku). */
    @Volatile
    var naSpremembeNaprav: (() -> Unit)? = null

    /**
     * Tokovi (zaslon, datoteke); nastavi jih krmilnik, ki pozna mape naprave. Ob nastavitvi se
     * usmerjevalnik pripne nanje: ko je datoteka cela, poslje cilju share.file; ko se deljenje
     * zaslona konca, poslje cilju share.screen stop. Posiljatelj za to ne rabi WebSocketa.
     */
    @Volatile
    var tokovi: HubTokovi? = null
        set(vrednost) {
            field = vrednost
            vrednost?.naDatoteko = { d -> datotekaPrispela(d) }
            vrednost?.naKonecZaslona = { id, _ -> zaslonKoncan(id) }
            vrednost?.jeCiljPovezan = { cilj -> register.povezavaOd(cilj) != null }
            vrednost?.napravaZeZetona = { zeton -> napravaZeZetona(zeton) }
            vrednost?.zasediCilj = { cilj, posiljatelj -> zasedi(cilj, posiljatelj, "file") }
            vrednost?.sprostiCilj = { cilj, posiljatelj -> sprosti(cilj, posiljatelj) }
        }

    /** Deljeni zasloni, ki tecejo: id deljenja -> ciljna naprava oz. posiljatelj. */
    internal val deljeniZasloni = HashMap<String, String>()
    internal val deljeniZasloniPosiljatelji = HashMap<String, String>()

    // ------------------------------------------------------------------ ena naprava naenkrat
    //
    // Z eno napravo deli naenkrat samo ena naprava. Ce tablica deli zaslon s televizorjem,
    // mora racunalnik pocakati, da tablica konca; s telefonom pa lahko racunalnik deli
    // medtem. Tako na cilju nikoli ne trcita dva vira in uporabnik vedno ve, kaj gleda.

    private class Zasedba(val posiljatelj: String, val vrsta: String, val od: Long)

    /** cilj -> kdo z njim trenutno deli */
    private val zasedeno = HashMap<String, Zasedba>()

    /**
     * Zasede cilj za posiljatelja. Vrne null, ce je cilj prost (ali ga ze ima isti posiljatelj),
     * sicer id naprave, ki ga ima. Zasedba se sprosti ob koncu deljenja (sprosti).
     */
    internal fun zasedi(cilj: String, posiljatelj: String, vrsta: String): String? {
        var spremenjeno = false
        val kdo = synchronized(kljucnica) {
            val z = zasedeno[cilj]
            if (z != null && z.posiljatelj != posiljatelj) return@synchronized z.posiljatelj
            if (z == null) spremenjeno = true
            zasedeno[cilj] = Zasedba(posiljatelj, vrsta, ura())
            null
        }
        if (spremenjeno) objaviNaprave()
        return kdo
    }

    internal fun sprosti(cilj: String, posiljatelj: String) {
        val spremenjeno = synchronized(kljucnica) {
            val z = zasedeno[cilj]
            if (z != null && z.posiljatelj == posiljatelj) { zasedeno.remove(cilj); true } else false
        }
        if (spremenjeno) objaviNaprave()
    }

    /** Kdo trenutno deli s ciljem (id), ali null. */
    fun zasedenOd(cilj: String): String? = synchronized(kljucnica) { zasedeno[cilj]?.posiljatelj }

    internal fun odgovorZasedeno(cilj: String, kdo: String): HubStreznik.Odgovor {
        val ime = imeNaprave(kdo)
        return HubStreznik.Odgovor(
            409,
            JsonLahki.Zapis()
                .niz("napaka", "Z napravo trenutno deli $ime. Počakaj, da konča.")
                .niz("koda", "naprava_zasedena")
                .niz("busy_by", kdo)
                .niz("busy_by_name", ime)
                .niz("target", cilj)
                .toString()
        )
    }

    // ------------------------------------------------------------------ imena naprav
    //
    // Uporabnik lahko napravo poimenuje po svoje ("Dnevna soba", "Lastnikova tablica"). Ime
    // hrani Hub, zato ga vidijo vse naprave enako, ne glede na to, kaj naprava trdi o sebi.

    private val vzdevki = HashMap<String, String>()

    private fun naloziVzdevke() {
        val zapis = shramba?.beri(KLJUC_VZDEVKOV) ?: return
        val pogled = JsonLahki.objekt(zapis) ?: return
        for (id in pogled.kljuci()) {
            val ime = pogled.niz(id) ?: continue
            if (ime.isNotBlank()) vzdevki[id] = ime.take(NAJVEC_IMENA)
        }
    }

    private fun shraniVzdevke() {
        val shramba = this.shramba ?: return
        val zapis = JsonLahki.Zapis()
        for ((id, ime) in vzdevki) zapis.niz(id, ime)
        shramba.pisi(KLJUC_VZDEVKOV, zapis.toString())
    }

    /**
     * Fizicna naprava, ki ji pripada [id]: id iz kljuca (`n-<16 hex>`) njenega clana v krogu zaupanja.
     * Identiteta naprave je kljuc; id-ji so le imena zanj. Brskalnik in Safeer Control na istem
     * racunalniku (isti kljuc, `pc-x` in `pc-x-control`), stari in novi id iste naprave (`tv-…` in `n-…`)
     * imajo zato isto napravo. Naprava brez kljuca v krogu (odjemalec pred krogom) je nima: null.
     */
    fun napravaIzKljuca(id: String): String? = krog.clanZaId(id)?.let { KrogZaupanja.idIzKljuca(it.kljuc) }

    /**
     * Ime za [id], ki ga je dal uporabnik. Naprava s kljucem ima ime v krogu zaupanja (skupno vsem hubom: po
     * menjavi huba ostane isto); lokalni vzdevek ostane samo za naprave brez kljuca in kot prehod.
     */
    internal fun vzdevek(id: String): String? = synchronized(kljucnica) {
        vzdevki[id]?.let { return@synchronized it }
        val naprava = napravaIzKljuca(id) ?: return@synchronized null
        vzdevki[naprava] ?: vzdevki.entries.firstOrNull { (k, _) -> k != id && napravaIzKljuca(k) == naprava }?.value
            ?: (krog.clan(id) ?: krog.clanZaId(id))?.ime?.takeIf { it.isNotBlank() && it != id }
    }

    /**
     * Ime naprave (vseh id-jev istega kljuca) zapise v krog; trust.update ga ponese vsem napravam in s tem
     * vsakemu prihodnjemu hubu. Prazno ime = lastno ime naprave (kot ga pove sama), ce ga poznamo.
     */
    private fun preimenujVKrogu(naprava: String, ime: String) {
        val zdaj = KrogZaupanja.zdaj()
        for (c in krog.clani().filter { KrogZaupanja.idIzKljuca(it.kljuc) == naprava }) {
            val novo = ime.ifEmpty {
                register.najdi(c.id)?.ime?.takeIf { it.isNotBlank() }
                    ?: zetoni.values.firstOrNull { it.deviceId == c.id }?.ime ?: c.ime
            }.take(NAJVEC_IMENA)
            // Novejse ime zmaga pri zdruzevanju krogov na vseh napravah (tudi ob zamaknjeni uri); dodano ostane.
            if (novo != c.ime) krog.preimenuj(c.id, novo, maxOf(zdaj, c.imenovano + 0.001))
        }
    }

    /** Ime naprave ali sosednjega sredisca, ki ga sredisce pozna s tega naslova, ali "". Za obvestilo obrambe. */
    fun imePoNaslovu(naslov: String): String {
        if (naslov.isEmpty()) return ""
        val n = register.vse().filter { it.naslov == naslov }.maxByOrNull { it.zadnjic }
        if (n != null) return imeNaprave(n.id).takeIf { it != n.id } ?: n.ime
        // Sosednje sredisce (Link Mesh) ni v registru naprav: ime ima krog zaupanja.
        val sosed = synchronized(kljucnica) {
            sosedje.entries.firstOrNull { naslovSoseda(it.value.naslov) == naslov }?.key
        } ?: return ""
        return imeNaprave(sosed).takeIf { it != sosed } ?: krog.clanZaId(sosed)?.ime.orEmpty()
    }

    /** Ime, kot ga vidi uporabnik: njegov vzdevek, sicer ime, ki ga je naprava povedala o sebi. */
    fun imeNaprave(id: String): String = synchronized(kljucnica) {
        vzdevek(id) ?: register.najdi(id)?.ime ?: zetoni.values.firstOrNull { it.deviceId == id }?.ime ?: id
    }

    /**
     * Preimenuje napravo; prazno ime vzdevek odstrani. Vrne false pri neveljavnem imenu. Naprava s kljucem
     * v krogu dobi vzdevek kot celota (vsi njeni id-ji), sicer velja za ta id.
     */
    fun preimenuj(id: String, ime: String): Boolean {
        val cisto = ime.replace(Regex("[\\u0000-\\u001f<>]"), "").trim().take(NAJVEC_IMENA)
        if (id.isBlank()) return false
        val naprava = synchronized(kljucnica) {
            val naprava = napravaIzKljuca(id)
            if (naprava != null) {
                // Ime gre v krog; lokalni vzdevki iste naprave bi ga prekrili.
                vzdevki.keys.filter { it == id || it == naprava || napravaIzKljuca(it) == naprava }.forEach { vzdevki.remove(it) }
            } else if (cisto.isEmpty()) vzdevki.remove(id) else vzdevki[id] = cisto
            shraniVzdevke()
            naprava
        }
        if (naprava != null) preimenujVKrogu(naprava, cisto)
        objaviNaprave()
        naSpremembeNaprav?.invoke()
        return true
    }

    // ------------------------------------------------------------------ krog zaupanja
    //
    // Kljuci naprav (KrogZaupanja). Hub ga hrani in razposilja; naprava, ki se izkaze s starim
    // zetonom, vanj vpise svoj kljuc in od takrat naprej pride s podpisom - brez nove kode.

    // En sam krog na proces: prej je imel hub svojo kopijo (KrogZaupanja(shramba)) poleg KrogNaprave.krog, obe pa sta
    // pisali isti kljuc v SharedPreferences - zadnji pisec je prepisal clane drugega. Na S25 (2. 10. 2026) je hub po
    // seznanitvi tako shranil krog samo s seboj; po ponovnem zagonu naprava ni poznala nikogar (401, brez sosedov).
    val krog = krogZaupanja ?: KrogZaupanja(shramba)

    /** Id in odtis TLS tega huba; nastavi krmilnik. Podpis prijave je vezan na odtis, da ga ni mogoce prenesti na drug hub. */
    @Volatile
    var lastniId: String = ""

    /** Odprti izzivi za prijavo s podpisom: nonce -> (device_id, izdan). */
    internal val izzivi = LinkedHashMap<String, Pair<String, Long>>()

    init {
        naloziZetone()
        naloziVzdevke()
        // Prehod: vzdevki naprav s kljucem gredo v krog (skupen vsem hubom), lokalni se pobrisejo.
        val zaKrog = vzdevki.entries.mapNotNull { (k, ime) -> napravaIzKljuca(k)?.let { it to ime } }
        if (zaKrog.isNotEmpty()) {
            for ((naprava, ime) in zaKrog) preimenujVKrogu(naprava, ime)
            vzdevki.keys.removeAll { napravaIzKljuca(it) != null }
            shraniVzdevke()
        }
        krog.naSpremembo = { objaviKrog() }
    }

    /** Umakne clane brez stika vec kot KrogZaupanja.DNI_BREZ_STIKA; umik gre vsem (naSpremembo -> objaviKrog). */
    fun pospraviKrog(): List<String> {
        val umaknjeni = try { krog.pospravi(lastniId, ura() / 1000.0) } catch (_: Throwable) { emptyList() }
        if (umaknjeni.isNotEmpty()) android.util.Log.i("SafeerHubUsmerjevalnik", "Krog: umaknjeni clani brez stika ${KrogZaupanja.DNI_BREZ_STIKA} dni: $umaknjeni")
        return umaknjeni
    }

    private fun objaviKrog() {
        val sporocilo = sporociloKroga()
        for (povezava in register.povezanePovezave()) if (povezava !is Namestnik) posljiVarno(povezava, sporocilo)
        posljiKrogSosedom(null)
    }

    /** "ip:vrata" tega huba za QR kodo, ki jo pokaze druga naprava v Linku; prazno, ce naslova ne vemo. */
    @Volatile
    var naslovZaQr: String = ""

    private fun krajevniNaslovHuba(): String = naslovZaQr

    /** Nas naslov na poti do soseda z danim IP ("" = neznan). Zamenljivo, da preizkus ne odpira vticnic. */
    @Volatile
    internal var nasNaslovProti: (String) -> String = { HubNaslovi.nasProti(it) }

    /** Kode, ki smo jih razposlali clanom (pair.code); ko prijave ni vec, jim povemo (pair.done). */
    private val razposlaneKode = mutableSetOf<String>()

    /**
     * Koda za novo napravo se pokaze na VSEH napravah Linka (televizor, tablica, racunalnik), ne samo
     * na tej: uporabnik jo prebere tam, kjer je. Clani so v krogu zaupanja; nova naprava je ne dobi.
     */
    fun razposljiKode() {
        val povezave = register.povezanePovezave()
        val sporocila = synchronized(kljucnica) {
            pocistiPrijave()
            val zdaj = prijave.values.filter { !it.potrjena }
            val nove = zdaj.filter { razposlaneKode.add(it.pairId) }.map { p ->
                ovojnica("pair.code").surovo("payload", JsonLahki.Zapis().niz("pair_id", p.pairId).niz("name", p.ime)
                    .niz("code", p.pin).stevilo("expires_in_seconds", ((PIN_VELJA_MS - (ura() - p.nastala)) / 1000).toDouble()).toString()).toString()
            }
            val koncane = razposlaneKode.filter { k -> zdaj.none { it.pairId == k } }
            razposlaneKode.removeAll(koncane.toSet())
            nove + koncane.map { ovojnica("pair.done").surovo("payload", JsonLahki.Zapis().niz("pair_id", it).toString()).toString() }
        }
        for (s in sporocila) for (p in povezave) posljiVarno(p, s)
    }

    private fun sporociloKroga(): String = ovojnica("trust.update").surovo("payload", krog.json()).toString()

    /** Hub sam je clan kroga: krmilnik vpise njegov kljuc ob zagonu. */
    fun vpisiLastniKljuc(deviceId: String, ime: String, kljucB64: String, platforma: String) {
        lastniId = deviceId
        val obstojeci = krog.clan(deviceId)
        // Isti kljuc: ime v krogu je morda dal uporabnik - ne povozimo ga z imenom, ki ga hub pove o sebi.
        if (obstojeci != null && obstojeci.kljuc == kljucB64) return
        val imeNaprave = krog.clani().firstOrNull { it.kljuc == kljucB64 }?.ime ?: ime
        krog.dodaj(KrogZaupanja.Clan(deviceId, kljucB64, imeNaprave, platforma, KrogZaupanja.zdaj(), deviceId))
    }

    /** Kaj naprava podpise ob prijavi: vezano na ta hub (odtis) in na izziv, zato podpis drugje ne velja. */
    fun podatkiZaPodpis(deviceId: String, nonce: String): ByteArray =
        "safeer-link-auth\n${lastniOdtis.lowercase()}\n$nonce\n$deviceId".toByteArray(Charsets.UTF_8)

    internal fun pocistiIzzive() {
        val zdaj = ura()
        val potekli = izzivi.filterValues { zdaj - it.second > IZZIV_VELJA_MS }.keys.toList()
        for (k in potekli) izzivi.remove(k)
    }

    // ------------------------------------------------------------------ zetoni naprav

    private fun naloziZetone() {
        val zapis = shramba?.beri(KLJUC_ZETONOV) ?: return
        val pogled = JsonLahki.objekt(zapis) ?: return
        var podvojenih = 0
        for (zeton in pogled.kljuci()) {
            if (zetoni.size >= NAJVEC_SEZNANJENIH) break
            val naprava = pogled.objekt(zeton) ?: continue
            val id = naprava.niz("device_id") ?: continue
            val nova = SeznanjenaNaprava(id, naprava.nizAli("name", id), naprava.stevilo("paired_at") ?: 0.0)
            // Stare shrambe imajo isto napravo veckrat (vsaka ponovna seznanitev je dodala zeton);
            // obdrzimo najnovejso seznanitev.
            val obstojeca = zetoni.entries.firstOrNull { it.value.deviceId == id }
            if (obstojeca != null) {
                podvojenih++
                if (obstojeca.value.seznanjenaOb >= nova.seznanjenaOb) continue
                zetoni.remove(obstojeca.key)
            }
            zetoni[zeton] = nova
        }
        if (podvojenih > 0) shraniZetone()
    }

    /** Seznam je poln, razen ce se ista naprava le znova seznanja (njen stari vnos bo zamenjan). */
    internal fun jePolno(deviceId: String): Boolean =
        zetoni.size >= NAJVEC_SEZNANJENIH && zetoni.values.none { it.deviceId == deviceId }

    /**
     * Ponovna seznanitev iste naprave zamenja prejsnjo: stari zeton je naprava ze zavrgla,
     * v seznamu pa bi jo uporabnik sicer videl dvakrat.
     */
    internal fun vpisiZeton(zeton: String, naprava: SeznanjenaNaprava) {
        val stari = zetoni.filterValues { it.deviceId == naprava.deviceId }.keys.toList()
        for (kljuc in stari) zetoni.remove(kljuc)
        zetoni[zeton] = naprava
    }

    internal fun shraniZetone() {
        val shramba = this.shramba ?: return
        val zapis = JsonLahki.Zapis()
        for ((zeton, naprava) in zetoni) {
            zapis.surovo(
                zeton,
                JsonLahki.Zapis()
                    .niz("device_id", naprava.deviceId)
                    .niz("name", naprava.ime)
                    .stevilo("paired_at", naprava.seznanjenaOb)
                    .toString()
            )
        }
        shramba.pisi(KLJUC_ZETONOV, zapis.toString())
    }

    /** Primerjava v stalnem casu; zeton je varnostna vrednost, ne navaden niz. */
    private fun enaka(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var razlika = 0
        for (i in a.indices) razlika = razlika or (a[i].code xor b[i].code)
        return razlika == 0
    }

    fun jeVeljavenZeton(zeton: String?): Boolean {
        if (zeton.isNullOrEmpty()) return false
        synchronized(kljucnica) {
            for ((znani, naprava) in zetoni) if (enaka(znani, zeton)) return !krog.jeUmaknjen(naprava.deviceId)
        }
        return napravaSeje(zeton) != null
    }

    // ------------------------------------------------------------------ seje (krog zaupanja)

    /** Sejni zeton -> (naprava, potece). Izda ga prijava s podpisom; velja za HTTP kot zeton seznanitve. */
    private val seje = LinkedHashMap<String, Pair<String, Long>>()

    /**
     * Sejni zeton za napravo, ki se je prijavila s podpisom kljuca (krog zaupanja). Brez njega bi
     * naprava, ki se je umaknila izvoljenemu hubu, lahko odprla WebSocket, ne pa poslala datoteke ali
     * deliti zaslona (te gredo po HTTP z zetonom). Zeton zivi v pomnilniku huba: ob novem hubu se
     * naprava prijavi znova in dobi novega.
     */
    fun izdajSejo(deviceId: String): String = synchronized(kljucnica) {
        pocistiSeje()
        while (seje.size >= NAJVEC_SEJ) seje.remove(seje.keys.first())
        val zeton = "saf_seja_" + nakljucni(24)
        seje[zeton] = Pair(deviceId, ura() + SEJA_VELJA_MS)
        zeton
    }

    private fun pocistiSeje() {
        val zdaj = ura()
        val potekle = seje.filterValues { it.second < zdaj }.keys.toList()
        for (k in potekle) seje.remove(k)
    }

    /** Naprava sejnega zetona, ce je zeton veljaven in je naprava se v krogu (umik iz kroga sejo ubije). */
    private fun napravaSeje(zeton: String): String? {
        val naprava = synchronized(kljucnica) {
            pocistiSeje()
            seje.entries.firstOrNull { enaka(it.key, zeton) }?.value?.first
        } ?: return null
        return if (krog.clanZaId(naprava) != null) naprava else null
    }

    /**
     * Naprava, ki ji zeton pripada. Zahteve po HTTP tako ne morejo trditi, da prihajajo z
     * druge naprave: posiljatelj je tisti, cigar zeton je, ne tisti, ki je zapisan v telesu.
     */
    fun napravaZeZetona(zeton: String?): String? {
        if (zeton.isNullOrEmpty()) return null
        synchronized(kljucnica) {
            // Umaknjena naprava (tudi ce je umik prisel z druge naprave v krogu) z zetonom nima vec dostopa.
            for ((znani, naprava) in zetoni) if (enaka(znani, zeton))
                return naprava.deviceId.takeUnless { krog.jeUmaknjen(it) }
        }
        return napravaSeje(zeton)
    }

    // ------------------------------------------------------------------ seznanjanje

    /**
     * Varovalka kode: skupna (ne po viru) omejitev ugibanja 6-mestne kode. Stanje prezivi ponovni zagon sredisca -
     * sicer bi vsak zagon napadalcu vrnil polno mejo.
     */
    val varovalka = HubVarovalka(ura, shramba?.beri(KLJUC_VAROVALKE), { zapis -> shramba?.pisi(KLJUC_VAROVALKE, zapis) })
        .also { it.obZapori = { zapora -> zaporaKode(zapora) } }

    /** Povezovanje s kodo se je zaprlo: sredisce pokaze obvestilo. */
    @Volatile
    var naZaporoKode: ((HubVarovalka.Zapora) -> Unit)? = null

    /** Varovalka je zaprla povezovanje s kodo: cakajoce prijave brez vabila padejo, kode na zaslonih ugasnejo. */
    private fun zaporaKode(zapora: HubVarovalka.Zapora) {
        synchronized(kljucnica) {
            pocistiPridruzitve()
            val odprte = pridruzitve.values.map { it.pin }.toSet()
            val padle = prijave.filterValues { !it.potrjena && it.pin !in odprte }.keys.toList()
            for (kljuc in padle) prijave.remove(kljuc)
            if (padle.isNotEmpty()) naSpremembePrijav?.invoke()
        }
        try { naZaporoKode?.invoke(zapora) } catch (e: Throwable) { SafeerLog.napaka("Usmerjevalnik", "naZaporoKode", e) }
    }

    /** Ali je povezovanje s kodo zdaj zaprto za novo napravo (varovalka; odprto vabilo ga med zaporo odpre). */
    fun kodaZaprta(): Boolean = synchronized(kljucnica) {
        pocistiPridruzitve()
        !varovalka.smeZaceti(pridruzitve.isNotEmpty())
    }

    private fun pocistiPrijave() {
        val zdaj = ura()
        val potekle = prijave.filterValues {
            zdaj - it.nastala > (if (it.potrjena) PREVZEM_VELJA_MS else PIN_VELJA_MS)
        }.keys.toList()
        for (kljuc in potekle) prijave.remove(kljuc)
    }

    /**
     * Naprava se prijavi in dobi kodo, ki jo pokaze na svojem zaslonu. Vrne null, ce je
     * cakajocih prijav prevec - takrat naj naprava poskusi cez nekaj minut - ali ce je varovalka
     * povezovanje s kodo zaprla ([kodaZaprta]).
     */
    fun zacniSeznanitev(deviceId: String, ime: String, naslov: String): Pair<String, String>? {
        synchronized(kljucnica) {
            pocistiPrijave()
            pocistiPridruzitve()
            // Ista naprava, ki poskusa znova, naj ne kopici prijav.
            val stare = prijave.filterValues { it.deviceId == deviceId }.keys.toList()
            for (kljuc in stare) prijave.remove(kljuc)
            if (prijave.size >= NAJVEC_CAKAJOCIH) return null
            if (!varovalka.zacetek(naslov, pridruzitve.isNotEmpty())) return null
            // Politika A: ce je naprava v krogu ze odprla povabilo (pairing host),
            // uporabimo ze prikazano kodo, da jo uporabnik le prepise z zaslona.
            val aktivnaKoda = pridruzitve.values.lastOrNull()?.pin ?: pin()
            val prijava = Prijava(
                pairId = nakljucni(8),
                deviceId = deviceId,
                ime = if (ime.isBlank()) deviceId else ime,
                pin = aktivnaKoda,
                naslov = naslov,
                nastala = ura()
            )
            prijave[prijava.pairId] = prijava
            naSpremembePrijav?.invoke()
            return prijava.pairId to prijava.pin
        }
    }

    /** Sestmestna koda. SecureRandom, ne navadni random - to je varnostna vrednost. */
    internal fun pin(): String {
        val stevilka = 100000 + (nakljucniStevec.nextInt(900000))
        return stevilka.toString()
    }

    /** Kaj caka na potrditev; vmesnik televizorja pokaze ime in kodo. */
    fun cakajocePrijave(): List<CakajocaPrijava> = synchronized(kljucnica) {
        pocistiPrijave()
        prijave.values.map {
            CakajocaPrijava(it.pairId, it.ime, it.pin, it.naslov,
                ((ura() - it.nastala) / 1000).toInt(), it.staroPovprasevanje)
        }
    }

    /**
     * Uporabnik je na televizorju pritisnil V redu. Izda zeton, ki ga naprava prevzame sama -
     * televizor ga nikoli ne pokaze in uporabniku ga ni treba nikamor prepisovati.
     */
    fun potrdiPrijavo(pairId: String): Boolean = synchronized(kljucnica) {
        pocistiPrijave()
        val prijava = prijave[pairId] ?: return false
        if (jePolno(prijava.deviceId)) {
            // Raje povemo, da ne gre, kot da bi seznam rasel v nedogled.
            return false
        }
        prijava.potrjena = true
        prijava.nastala = ura()
        prijava.zeton = "saf_tv_" + nakljucni(24)
        vpisiZeton(prijava.zeton!!, SeznanjenaNaprava(prijava.deviceId, prijava.ime, ura() / 1000.0))
        shraniZetone()
        naSpremembePrijav?.invoke()
        return true
    }

    /**
     * Naprava, ki se prikljucuje, vtipka sestmestno kodo, ki jo gostitelj pokaze na svojem
     * zaslonu. Kdor kode ne vidi, se ne more prikljuciti - tudi ce je v istem omrezju.
     * Prav to je razlog, da je Safeer Link varen tudi na javnem wifiju.
     *
     * Ugibanja ni: po NAJVEC_POSKUSOV zgresenih kodah prijava pade in naprava mora zaceti
     * znova, kar pomeni novo kodo. Primerjava kode tece v stalnem casu.
     */
    fun potrdiSKodo(pairId: String, koda: String): IzidKode = synchronized(kljucnica) {
        pocistiPrijave()
        val prijava = prijave[pairId] ?: return IzidKode(null, "prijava_ne_obstaja")
        if (prijava.potrjena && prijava.zeton != null) {
            // Ista naprava je kodo ze vnesla; zeton dobi natanko enkrat.
            val zeton = prijava.zeton
            prijave.remove(pairId)
            naSpremembePrijav?.invoke()
            return IzidKode(zeton, null)
        }
        val vnos = koda.trim()
        pocistiPridruzitve()
        if (!varovalka.poskus(prijava.naslov, pridruzitve.values.any { it.pin == prijava.pin })) {
            prijave.remove(pairId)
            naSpremembePrijav?.invoke()
            return IzidKode(null, "seznanitev_zaprta")
        }
        if (!enaka(prijava.pin, vnos)) {
            prijava.poskusov += 1
            if (prijava.poskusov >= NAJVEC_POSKUSOV) {
                prijave.remove(pairId)
                naSpremembePrijav?.invoke()
                return IzidKode(null, "prevec_poskusov")
            }
            naSpremembePrijav?.invoke()
            return IzidKode(null, "napacna_koda")
        }
        // Koda je bila prava: ta poskus ni ugibanje, tudi ce sredisce nima vec prostora.
        varovalka.uspeh(prijava.naslov)
        if (jePolno(prijava.deviceId)) {
            return IzidKode(null, "preveč_naprav")
        }
        val zeton = "saf_tv_" + nakljucni(24)
        vpisiZeton(zeton, SeznanjenaNaprava(prijava.deviceId, prijava.ime, ura() / 1000.0))
        shraniZetone()
        prijave.remove(pairId)
        naSpremembePrijav?.invoke()
        return IzidKode(zeton, null)
    }

    /**
     * Prvi korak seznanitve s SPAKE2 (RFC 9382): naprava poslje svojo tocko pB, Hub iz kode,
     * ki jo kaze na zaslonu, izpelje svojo in vrne pA s potrditvijo cA. Koda po omrezju ne
     * potuje; kdor je ne pozna, iz pA/pB ne izve nic in je ne more uganiti brez povezave.
     *
     * Kot sol sluzi pair_id (isti prepis ne velja v dveh sejah), kot dodatni podatek (AAD)
     * pa odtis potrdila TLS: naprava vplete odtis, ki ga je videla, Hub svojega.
     */
    fun spakeKorak1(pairId: String, deviceId: String, pb: ByteArray): IzidSpake = synchronized(kljucnica) {
        pocistiPrijave()
        val prijava = prijave[pairId] ?: return IzidSpake(null, null, "prijava_ne_obstaja")
        if (prijava.deviceId != deviceId) return IzidSpake(null, null, "prijava_ne_obstaja")
        prijava.krogov += 1
        if (prijava.krogov > NAJVEC_POSKUSOV) {
            prijave.remove(pairId)
            naSpremembePrijav?.invoke()
            return IzidSpake(null, null, "prevec_poskusov")
        }
        // En krog napravi pove, ali je njena koda prava: to JE poskus kode in steje v skupno mejo.
        pocistiPridruzitve()
        if (!varovalka.poskus(prijava.naslov, pridruzitve.values.any { it.pin == prijava.pin })) {
            prijave.remove(pairId)
            naSpremembePrijav?.invoke()
            return IzidSpake(null, null, "seznanitev_zaprta")
        }
        return try {
            val s = Spake2.streznik(prijava.pin, IDENTITETA_HUBA, prijava.deviceId,
                lastniOdtis.toByteArray(Charsets.UTF_8), pairId.toByteArray(Charsets.UTF_8))
            val ca = s.zakljuci(pb)
            prijava.spake = s
            IzidSpake(s.sporocilo(), ca, null)
        } catch (e: IllegalArgumentException) {
            prijava.spake = null
            IzidSpake(null, null, "neveljavna_tocka")
        }
    }

    /**
     * Drugi korak: naprava poslje svojo potrditev cB. Ujemanje pomeni, da pozna isto kodo
     * in da je videla isto potrdilo TLS - takrat dobi zeton. Sicer steje kot zgresena koda.
     */
    fun spakeKorak2(pairId: String, deviceId: String, cb: ByteArray, pubkey: String = "", platform: String = ""): IzidKode = synchronized(kljucnica) {
        pocistiPrijave()
        val prijava = prijave[pairId] ?: return IzidKode(null, "prijava_ne_obstaja")
        if (prijava.deviceId != deviceId) return IzidKode(null, "prijava_ne_obstaja")
        val s = prijava.spake ?: return IzidKode(null, "manjka_korak")
        prijava.spake = null
        if (!s.preveri(cb)) {
            prijava.poskusov += 1
            if (prijava.poskusov >= NAJVEC_POSKUSOV) {
                prijave.remove(pairId)
                naSpremembePrijav?.invoke()
                return IzidKode(null, "prevec_poskusov")
            }
            naSpremembePrijav?.invoke()
            return IzidKode(null, "napacna_koda")
        }
        // Koda je bila prava: ta poskus ni ugibanje, tudi ce sredisce nima vec prostora.
        varovalka.uspeh(prijava.naslov)
        if (jePolno(prijava.deviceId)) return IzidKode(null, "preveč_naprav")
        val zeton = "saf_tv_" + nakljucni(24)
        vpisiZeton(zeton, SeznanjenaNaprava(prijava.deviceId, prijava.ime, ura() / 1000.0))
        shraniZetone()
        // Krog zaupanja: ce je nova naprava poslala svoj javni kljuc, jo takoj vpisemo v krog
        if (pubkey.isNotBlank() && KrogZaupanja.dekodirajKljuc(pubkey) != null) {
            krog.dodaj(KrogZaupanja.Clan(prijava.deviceId, pubkey, prijava.ime,
                platform.take(16).ifBlank { "unknown" }, KrogZaupanja.zdaj(), lastniId))
        }
        prijave.remove(pairId)
        pridruzitve.entries.removeAll { it.value.pin == prijava.pin }
        naSpremembePrijav?.invoke()
        naSpremembeNaprav?.invoke()
        return IzidKode(zeton, null)
    }

    /** Zabelezi, da naprava caka po starem, da ji vmesnik ponudi gumb Potrdi. */
    internal fun oznaciStaroNapravo(pairId: String): Unit = synchronized(kljucnica) {
        val prijava = prijave[pairId] ?: return
        if (!prijava.staroPovprasevanje) {
            prijava.staroPovprasevanje = true
            naSpremembePrijav?.invoke()
        }
    }

    /** Naprava, ki je prijavo zacela, jo je opustila: koda na zaslonu ne sme viseti do poteka. */
    fun prekliciPrijavo(pairId: String, deviceId: String): Boolean = synchronized(kljucnica) {
        val prijava = prijave[pairId] ?: return false
        if (prijava.deviceId != deviceId || prijava.potrjena) return false
        prijave.remove(pairId)
        naSpremembePrijav?.invoke()
        return true
    }

    fun zavrniPrijavo(pairId: String): Boolean = synchronized(kljucnica) {
        val odstranjena = prijave.remove(pairId) != null
        if (odstranjena) naSpremembePrijav?.invoke()
        return odstranjena
    }

    /** Naprava prevzame svoj zeton. Uspe natanko enkrat. */
    fun prevzemiZeton(pairId: String): String? = synchronized(kljucnica) {
        pocistiPrijave()
        val prijava = prijave[pairId] ?: return null
        if (!prijava.potrjena || prijava.zeton == null) return null
        prijave.remove(pairId)
        naSpremembePrijav?.invoke()
        return prijava.zeton
    }

    /**
     * Zeton za napravo, na kateri Hub tece: gostitelj je hkrati zaslon, na katerega je mogoce
     * posiljati. Klice se samo iz procesa, nikoli po omrezju - koncne tocke za to ni.
     * Ista naprava dobi vedno isti zeton, da se ob vsakem zagonu ne kopicijo novi.
     */
    fun zagotoviLastniZeton(deviceId: String, ime: String): String = synchronized(kljucnica) {
        for ((zeton, naprava) in zetoni) if (naprava.deviceId == deviceId) return zeton
        val zeton = "saf_tv_" + nakljucni(24)
        zetoni[zeton] = SeznanjenaNaprava(deviceId, ime, ura() / 1000.0)
        shraniZetone()
        return zeton
    }

    // ------------------------------------------------------------------ prijava s QR kodo

    /**
     * Prijava s QR kodo (Safeer OS in Safeer Control na racunalniku): naprava pokaze QR, uporabnik ga
     * poskenira s telefonom ali tablico, ki sta ze v Safeer Linku, in tam potrdi »Dovoli«.
     *
     * - V QR je skrivnost; hub pozna samo njen SHA-256, zato je ne more izdati niti sam.
     * - Dovoli lahko samo ze seznanjena naprava (njen zeton) in samo, kdor QR vidi (skrivnost).
     * - Zeton prevzame samo naprava, ki je prijavo zacela: za prevzem ima drugo skrivnost, ki je v QR
     *   ni. Kdor QR fotografira, z njim zetona ne dobi.
     * - Ugibanja ni: po NAJVEC_POSKUSOV napacnih skrivnostih prijava pade; velja PIN_VELJA_MS.
     */
    private class QrPrijava(
        val qrId: String,
        val deviceId: String,
        val ime: String,
        val platforma: String,
        /** SHA-256 (hex) skrivnosti iz QR. */
        val odtisSkrivnosti: String,
        /** Skrivnost za prevzem zetona; pozna jo samo naprava, ki je prijavo zacela. */
        val prevzem: String,
        var nastala: Long,
        var zeton: String? = null,
        var odobril: String = "",
        var poskusov: Int = 0
    )

    private val qrPrijave = LinkedHashMap<String, QrPrijava>()

    /** Kar naprava, ki dovoljuje, pokaze uporabniku, preden potrdi. */
    data class QrPodatki(val deviceId: String, val ime: String, val platforma: String)

    private fun pocistiQr() {
        val zdaj = ura()
        qrPrijave.entries.removeAll { zdaj - it.value.nastala > (if (it.value.zeton != null) PREVZEM_VELJA_MS else PIN_VELJA_MS) }
    }

    /** Odpre prijavo s QR kodo. Vrne qr_id ali napako (prevec_prijav, neveljavno). */
    fun zacniQr(deviceId: String, ime: String, platforma: String, odtisSkrivnosti: String, prevzem: String): Pair<String?, String?> =
        synchronized(kljucnica) {
            if (!Regex("^[0-9a-f]{64}$").matches(odtisSkrivnosti) || prevzem.length !in 16..128) return null to "neveljavno"
            pocistiQr()
            qrPrijave.entries.removeAll { it.value.deviceId == deviceId }
            if (qrPrijave.size >= NAJVEC_CAKAJOCIH) return null to "prevec_prijav"
            val p = QrPrijava(nakljucni(12), deviceId, if (ime.isBlank()) deviceId else ime,
                platforma.take(16), odtisSkrivnosti, prevzem, ura())
            qrPrijave[p.qrId] = p
            return p.qrId to null
        }

    /** Prijava, ce skrivnost iz QR drzi; sicer napaka (qr_ne_obstaja, prevec_poskusov). Klice se pod kljucnico. */
    private fun qrZaSkrivnost(qrId: String, skrivnost: String): Pair<QrPrijava?, String?> {
        pocistiQr()
        val p = qrPrijave[qrId] ?: return null to "qr_ne_obstaja"
        if (!enaka(sha256Hex(skrivnost), p.odtisSkrivnosti)) {
            p.poskusov += 1
            if (p.poskusov >= NAJVEC_POSKUSOV) {
                qrPrijave.remove(qrId)
                return null to "prevec_poskusov"
            }
            return null to "qr_ne_obstaja"
        }
        return p to null
    }

    /** Kdo se zeli prijaviti - za vprasanje na napravi, ki dovoljuje. */
    fun qrPodatki(qrId: String, skrivnost: String): Pair<QrPodatki?, String?> = synchronized(kljucnica) {
        val (p, napaka) = qrZaSkrivnost(qrId, skrivnost)
        if (p == null) return null to napaka
        return QrPodatki(p.deviceId, p.ime, p.platforma) to null
    }

    /**
     * Seznanjena naprava [odobril] dovoli prijavo. Zeton nastane takoj (kot pri kodi), prevzame
     * ga naprava, ki je prijavo zacela. Ponovna potrditev iste prijave nicesar ne spremeni.
     */
    fun odobriQr(qrId: String, skrivnost: String, odobril: String): Pair<QrPodatki?, String?> {
        val izid = synchronized(kljucnica) {
            val (p, napaka) = qrZaSkrivnost(qrId, skrivnost)
            if (p == null) return null to napaka
            if (p.deviceId == odobril) return null to "ista_naprava"
            if (p.zeton == null) {
                if (jePolno(p.deviceId)) return null to "prevec_naprav"
                val zeton = "saf_tv_" + nakljucni(24)
                vpisiZeton(zeton, SeznanjenaNaprava(p.deviceId, p.ime, ura() / 1000.0))
                shraniZetone()
                p.zeton = zeton
                p.odobril = odobril
                p.nastala = ura()
            }
            QrPodatki(p.deviceId, p.ime, p.platforma)
        }
        naSpremembeNaprav?.invoke()
        return izid to null
    }

    /**
     * Naprava, ki je prijavo zacela, vprasa za izid: ("caka", null), ("odobreno", zeton) natanko enkrat,
     * ali ("qr_ne_obstaja", null) - tudi ob napacni skrivnosti za prevzem, da ne izdamo, kaj obstaja.
     */
    fun prevzemiQr(qrId: String, deviceId: String, prevzem: String): Pair<String, String?> = synchronized(kljucnica) {
        pocistiQr()
        val p = qrPrijave[qrId] ?: return "qr_ne_obstaja" to null
        if (p.deviceId != deviceId || !enaka(p.prevzem, prevzem)) return "qr_ne_obstaja" to null
        val zeton = p.zeton ?: return "caka" to null
        qrPrijave.remove(qrId)
        return "odobreno" to zeton
    }

    /** Naprava je okno zaprla ali QR osvezila: stara prijava ne sme viseti do poteka. */
    fun prekliciQr(qrId: String, deviceId: String, prevzem: String): Boolean = synchronized(kljucnica) {
        val p = qrPrijave[qrId] ?: return false
        if (p.deviceId != deviceId || !enaka(p.prevzem, prevzem) || p.zeton != null) return false
        qrPrijave.remove(qrId)
        return true
    }

    // ------------------------------------------------------------------ pridruzitev s QR kodo sredisca

    /**
     * QR, ki ga pokaze SREDISCE (prijavno okno Safeer OS na televizorju): telefon ali tablica ga poskenira
     * in se pridruzi. Velja enako kot 6-mestna koda: pridruzi se lahko samo, kdor vidi zaslon sredisca.
     * V kodi je celoten odtis potrdila sredisca, zato telefon ze prvo povezavo pripne nanj (vsiljivec v
     * sredini z drugim potrdilom pade). Skrivnost ima 128 bitov, velja PIN_VELJA_MS, porabi se enkrat,
     * ugibanje je omejeno. Kodo ustvari proces sredisca (zaslon) ali seznanjena naprava v krajevnem
     * omrezju (/cast/pair/qr/invite, »Poveži novo napravo« na racunalniku) - nikoli tujec.
     */
    internal class Pridruzitev(val id: String, val odtisSkrivnosti: String, val skrivnost: String, val pin: String, val nastala: Long, var poskusov: Int = 0)

    data class PridruzitevIzid(val id: String, val skrivnost: String, val pin: String)

    internal val pridruzitve = LinkedHashMap<String, Pridruzitev>()

    /** Koda -> ime naprave, ki se je z njo pridruzila (za »povezano« na napravi, ki je kodo pokazala). */
    internal val pridruzeni = LinkedHashMap<String, String>()

    /** Sredisce izve, kdo se je pridruzil (device_id, ime) - zaslon pokaze »povezano« in novo kodo. */
    @Volatile
    var naPridruzitev: ((String, String) -> Unit)? = null

    internal fun pocistiPridruzitve() {
        val zdaj = ura()
        pridruzitve.entries.removeAll { zdaj - it.value.nastala > PIN_VELJA_MS }
    }

    /** Nova koda za zaslon sredisca: (id, skrivnost, pin). Vedno skuje SVEZO kodo in staro zavrze -
        za to poklici samo, ko zaslon eksplicitno zamenja kodo (npr. redna obnovitev pred potekom,
        ali ko se je nekdo ravno pridruzil in caka naslednja naprava). Za ponovni izris istega zaslona
        (npr. ker klic ni uspel in poskusa znova) uporabi zagotoviPridruzitev(), da se koda ne spreminja. */
    fun ustvariPridruzitev(): PridruzitevIzid = synchronized(kljucnica) {
        pocistiPridruzitve()
        while (pridruzitve.size >= NAJVEC_CAKAJOCIH) pridruzitve.remove(pridruzitve.keys.first())
        val id = nakljucni(12)
        val skrivnost = nakljucni(16)
        val koda = pin()
        pridruzitve[id] = Pridruzitev(id, sha256Hex(skrivnost), skrivnost, koda, ura())
        PridruzitevIzid(id, skrivnost, koda)
    }

    /** Kot ustvariPridruzitev(), le da NE skuje nove kode, ce ze imamo se veljavno: zaslon jo lahko
        klice poljubnokrat (npr. vsakih 5 s, ko krajevni naslov/vrata sredisca se niso pripravljena, ali
        katerikoli drug ponovni poskus) in uporabnik vidno vidi VEDNO ISTO kodo, dokler ne potece ali
        dokler je zaslon eksplicitno ne zamenja prek ustvariPridruzitev(). To je popravek napake, kjer se
        je koda na zaslonu spreminjala prehitro, da bi jo uporabnik utegnil prepisati. */
    fun zagotoviPridruzitev(): PridruzitevIzid = synchronized(kljucnica) {
        pocistiPridruzitve()
        val obstojeca = pridruzitve.values.lastOrNull()
        if (obstojeca != null) PridruzitevIzid(obstojeca.id, obstojeca.skrivnost, obstojeca.pin)
        else ustvariPridruzitev()
    }

    /** Zaslon je kodo zamenjal ali zaprl. */
    fun prekliciPridruzitev(id: String): Unit = synchronized(kljucnica) { pridruzitve.remove(id) }

    /** Aktivni PIN za seznanitev (ce je koda ze odprta). */
    fun aktivniPin(): String? = synchronized(kljucnica) {
        pocistiPridruzitve()
        pridruzitve.values.lastOrNull()?.pin
    }

    /** Naprava s skrivnostjo iz QR se pridruzi: (zeton, null) ali (null, napaka). Koda velja enkrat. */
    fun pridruzi(id: String, skrivnost: String, deviceId: String, ime: String, pubkey: String = "", platform: String = ""): Pair<String?, String?> {
        val zeton = synchronized(kljucnica) {
            pocistiPridruzitve()
            val p = pridruzitve[id] ?: return null to "qr_ne_obstaja"
            if (!enaka(sha256Hex(skrivnost), p.odtisSkrivnosti)) {
                p.poskusov += 1
                if (p.poskusov >= NAJVEC_POSKUSOV) {
                    pridruzitve.remove(id)
                    return null to "prevec_poskusov"
                }
                return null to "qr_ne_obstaja"
            }
            if (jePolno(deviceId)) return null to "prevec_naprav"
            pridruzitve.remove(id)
            while (pridruzeni.size >= NAJVEC_CAKAJOCIH) pridruzeni.remove(pridruzeni.keys.first())
            val cistoIme = if (ime.isBlank()) deviceId else ime
            pridruzeni[id] = cistoIme
            val nov = "saf_tv_" + nakljucni(24)
            vpisiZeton(nov, SeznanjenaNaprava(deviceId, cistoIme, ura() / 1000.0))
            shraniZetone()
            if (pubkey.isNotBlank() && KrogZaupanja.dekodirajKljuc(pubkey) != null) {
                krog.dodaj(KrogZaupanja.Clan(deviceId, pubkey, cistoIme, platform.take(16).ifBlank { "unknown" }, KrogZaupanja.zdaj(), lastniId))
            }
            nov
        }
        naSpremembeNaprav?.invoke()
        try { naPridruzitev?.invoke(deviceId, ime) } catch (e: Throwable) { SafeerLog.napaka("Usmerjevalnik", "naPridruzitev", e) }
        return zeton to null
    }

    private fun sha256Hex(niz: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(niz.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun seznanjeneNaprave(): List<SeznanjenaNaprava> = synchronized(kljucnica) {
        zetoni.values.map { n -> vzdevek(n.deviceId)?.let { n.copy(ime = it) } ?: n }
    }

    /** Odvzame dostop napravi in jo, ce je povezana, tudi odklopi. */
    fun prekliciNapravo(deviceId: String): Int {
        val odklopi = ArrayList<Odjemalec>()
        val koliko: Int
        synchronized(kljucnica) {
            val odvzeti = zetoni.filterValues { it.deviceId == deviceId }.keys.toList()
            for (kljuc in odvzeti) zetoni.remove(kljuc)
            koliko = odvzeti.size
            if (koliko > 0) shraniZetone()
            register.povezavaOd(deviceId)?.let { odklopi.add(it) }
        }
        for (povezava in odklopi) {
            try {
                povezava.zapri(1008, "dostop odvzet")
            } catch (e: Exception) {
                // Naprave, ki je ze izginila, ni treba odklapljati.
            }
        }
        return koliko
    }

    /**
     * Naprava sama zapusti Safeer Link (»Odjavi ta racunalnik«, nezaupan racunalnik ob koncu prijave):
     * odvzamemo zetone in jo umaknemo iz kroga zaupanja - z njo vred vse id-je z istim kljucem (ista
     * naprava pod drugim imenom, npr. Control in brskalnik na istem racunalniku). Druge naprave ostanejo.
     * Vrne id-je, ki so odsli.
     */
    fun odidi(deviceId: String): List<String> {
        val kljuc = krog.clanZaId(deviceId)?.kljuc
        val idji = LinkedHashSet<String>()
        idji.add(deviceId)
        if (kljuc != null) for (c in krog.clani()) if (c.kljuc == kljuc && c.id != lastniId) idji.add(c.id)
        for (id in idji) {
            prekliciNapravo(id)
            // Umik mora biti novejsi od vpisa (vpis v isti milisekundi bi ga sicer preglasil).
            val dodano = krog.clan(id)?.dodano ?: 0.0
            if (id != lastniId) krog.umakni(id, deviceId, maxOf(KrogZaupanja.zdaj(), dodano + 0.001))
        }
        synchronized(kljucnica) { seje.entries.removeAll { it.value.first in idji } }
        naSpremembeNaprav?.invoke()
        return idji.toList()
    }

    // ------------------------------------------------------------------ vstopnice

    /**
     * Enokratna vstopnica za WebSocket, kratke veljavnosti. Povezava brez nje sploh ne nastane -
     * enako kot na racunalniku, kjer jo izda Controlov SessionManager.
     */
    fun izdajVstopnico(deviceId: String? = null, podpis: Boolean = false): String = synchronized(kljucnica) {
        pocistiVstopnice()
        if (vstopnice.size >= NAJVEC_VSTOPNIC) {
            // Najstarejsa pade ven; drugace bi jih nekdo lahko naracal poljubno veliko.
            vstopnice.remove(vstopnice.keys.first())
        }
        val vstopnica = nakljucni(16)
        vstopnice[vstopnica] = Vstopnica(ura(), deviceId?.takeIf { it.isNotBlank() }, podpis)
        return vstopnica
    }

    private fun pocistiVstopnice() {
        val zdaj = ura()
        val potekle = vstopnice.filterValues { zdaj - it.izdana > VSTOPNICA_VELJA_MS }.keys.toList()
        for (kljuc in potekle) vstopnice.remove(kljuc)
    }

    /**
     * Porabi vstopnico; druga uporaba iste ne uspe. Vstopnica, izdana znani napravi (zeton ali
     * podpis), ostane vezana nanjo, da se povezava po njej ne more prijaviti pod tujim device_id.
     */
    fun porabiVstopnico(vstopnica: String?): Boolean {
        if (vstopnica.isNullOrEmpty()) return false
        synchronized(kljucnica) {
            pocistiVstopnice()
            val najdena = vstopnice.keys.firstOrNull { enaka(it, vstopnica) } ?: return false
            val v = vstopnice.remove(najdena)
            if (v?.deviceId != null) {
                if (vezaneVstopnice.size >= NAJVEC_VSTOPNIC) vezaneVstopnice.remove(vezaneVstopnice.keys.first())
                vezaneVstopnice[najdena] = v.deviceId
                if (v.podpis) {
                    if (podpisaneVstopnice.size >= NAJVEC_VSTOPNIC) podpisaneVstopnice.remove(podpisaneVstopnice.first())
                    podpisaneVstopnice.add(najdena)
                }
            }
            return true
        }
    }

    /** Naprava, ki ji je bila vstopnica izdana, ali null, ce vstopnica ni bila vezana (ali je ni). */
    fun napravaVstopnice(vstopnica: String?): String? {
        if (vstopnica.isNullOrEmpty()) return null
        return synchronized(kljucnica) { vezaneVstopnice.entries.firstOrNull { enaka(it.key, vstopnica) }?.value }
    }

    // ------------------------------------------------------------------ register naprav

    /** [nasNaslov]: null za odjemalca s te naprave (surov seznam); sicer nas naslov na poti do odjemalca. */
    private fun napraveJson(nasNaslov: String? = null): String {
        // Vse povezane naprave, z vlogo zraven: "zaslon" (receiver) sprejema strani in videe,
        // deliti (besedilo, datoteka, zaslon) pa je mogoce s katerokoli. Kdo je kaj, odloci
        // vmesnik po polju role, ne Hub s filtriranjem.
        return register.povezane().joinToString(",", "[", "]") { napravaJson(it, nasNaslov) }
    }

    private fun napravaJson(naprava: RegisterNaprav.Naprava, nasNaslov: String? = null): String {
        // Program s TE naprave ima pri nas 127.0.0.1 in dobi polje `here`. Odjemalcu s te naprave naslov ostane (po
        // njem prepozna programe svoje naprave); odjemalec od DRUGOD bi se z njim povezal sam nase, zato dobi nas
        // naslov na poti do njega.
        val tukaj = naprava.povezava !is Namestnik && naprava.naslov.isNotBlank() && HubNaslovi.samoTukaj(naprava.naslov)
        val zapis = JsonLahki.Zapis()
            .niz("id", naprava.id)
            .niz("name", vzdevek(naprava.id) ?: naprava.ime)
            .niz("own_name", naprava.ime)
            .niz("role", naprava.vloga)
            .seznamNizov("capabilities", naprava.zmoznosti)
            .niz("ip", if (tukaj && nasNaslov != null) nasNaslov else naprava.naslov)
            .nic("port")
            .stevilo("last_seen", naprava.zadnjic)
        if (tukaj) zapis.logicno("here", true)
        // Fizicna naprava (kljuc): vmesnik zdruzi sorodnike (brskalnik + Control) v eno napravo.
        napravaIzKljuca(naprava.id)?.let { zapis.niz("device", it) }
        // Protocol v1: model naprave in katalog aplikacij, samo kadar ju naprava pove.
        if (naprava.protokol.isNotBlank()) zapis.niz("protocol", naprava.protokol)
        if (naprava.platforma.isNotBlank()) zapis.niz("platform", naprava.platforma)
        if (naprava.vrsta.isNotBlank()) zapis.niz("kind", naprava.vrsta)
        if (naprava.razlicica.isNotBlank()) zapis.niz("version", naprava.razlicica)
        if (naprava.prioriteta > 0) zapis.stevilo("priority", naprava.prioriteta.toDouble())
        if (naprava.aplikacije.isNotBlank()) zapis.surovo("apps", naprava.aplikacije)
        val z = zasedeno[naprava.id]
        if (z != null) {
            zapis.niz("busy_by", z.posiljatelj)
                .niz("busy_by_name", vzdevek(z.posiljatelj) ?: register.najdi(z.posiljatelj)?.ime ?: z.posiljatelj)
                .niz("busy_kind", z.vrsta)
        }
        return zapis.toString()
    }

    /** Seznam povezanih prejemnikov za vmesnik in za koncno tocko /cast/devices. */
    fun povezaniPrejemniki(): String = synchronized(kljucnica) { napraveJson() }

    fun steviloNaprav(): Int = register.steviloPovezanih()

    private fun idPovezave(povezava: Odjemalec): String? = register.idPovezave(povezava)

    fun odklopi(povezava: Odjemalec) {
        sosedPovezave(povezava)?.let { odstraniSoseda(it, povezava); return }
        if (register.odklopi(povezava)) {
            objaviNaprave()
            naSpremembeNaprav?.invoke()
        }
    }

    // ------------------------------------------------------------------ obdelava sporocil

    fun obdelaj(od: Odjemalec, surovo: String) {
        val odgovor = odgovorNa(od, surovo)
        if (odgovor != null) od.poslji(odgovor)
    }

    /**
     * Vrne odgovor, ki naj se poslje posiljatelju, ali null, ce odgovora ni.
     * Locena metoda zato, da jo je mogoce preizkusiti brez omrezja.
     */
    fun odgovorNa(od: Odjemalec, surovo: String): String? {
        val sporocilo = JsonLahki.objekt(surovo)
            ?: return potrditev("unknown", "error", "Neveljavno sporočilo.", koda = "neveljavno_sporocilo")
        val tip = sporocilo.niz("type")
        val id = sporocilo.nizAli("id", "unknown")
        val prostor = prostorOd(tip)

        if (tip.isNullOrEmpty()) return potrditev(id, "error", "Sporočilu manjka polje 'type'.", koda = "manjka_type")

        sosedPovezave(od)?.let { return obdelajSoseda(it, od, sporocilo) }
        if (tip.startsWith("mesh.")) return potrditev(id, "rejected", "Samo sosednji Hub.", "mesh", "ni_sosed")

        if (tip == "cast.register") {
            val t = sporocilo.objekt("payload")
            if (t?.niz("role") == "hub" && t.nizi("capabilities").contains(MESH)) return sprejmiSoseda(od, t, id)
            return registriraj(od, sporocilo, id)
        }

        if (tip == "cast.ping") return ovojnica("cast.pong", id).toString()

        if (tip in SYNC_POSREDOVANJE) return usmeriSinhronizacijo(od, sporocilo, surovo, id)

        if (tip == "sync.ack") {
            val cilj = sporocilo.niz("target")
            register.povezavaOd(cilj)?.poslji(surovo)
            return null
        }

        if (tip in CAST_POSREDOVANJE) {
            val cilj = sporocilo.niz("target")
            // Stran (cast.url) sme na vsako napravo, ki jo zna odpreti - tudi na telefon ali
            // racunalnik, ko jo poslje televizor. Predvajanje in nadzor ostaneta za zaslone.
            val prejemnik = register.najdi(cilj)?.takeIf {
                it.vloga == "receiver" || (tip == "cast.url" && it.zmoznosti.contains("url"))
            }?.takeIf { it.povezava !== od }?.povezava
                ?: return potrditev(id, "rejected", "Ciljna naprava '${cilj ?: ""}' ni povezana ali ne obstaja.", koda = "naprava_ni_povezana")
            return if (posljiVarno(prejemnik, surovo)) potrditev(id, "accepted")
            else potrditev(id, "error", "Napaka pri posredovanju prejemniku.", koda = "posredovanje_ni_uspelo")
        }

        if (tip in SHARE_POSREDOVANJE) {
            // Deljenje med napravama: besedilo, datoteka, zaslon. Cilj je lahko katerakoli
            // povezana naprava, ne le "zaslon" - telefon poslje telefonu, tablica racunalniku.
            // Hub vsebine ne odpira; posreduje jo napravi, ki jo je uporabnik izbral.
            val cilj = sporocilo.niz("target") ?: ""
            val posiljatelj = idPovezave(od) ?: ""
            val prejemnik = register.povezavaOd(cilj)
                ?: return potrditev(id, "rejected", "Ciljna naprava '$cilj' ni povezana ali ne obstaja.", "share", "naprava_ni_povezana")
            if (prejemnik === od) return potrditev(id, "rejected", "Naprava ne more deliti sama s sabo.", "share", "isti_naprava")
            zasedenOd(cilj)?.let { kdo ->
                if (kdo != posiljatelj) return potrditev(id, "rejected", "Z napravo trenutno deli ${imeNaprave(kdo)}. Počakaj, da konča.", "share", "naprava_zasedena")
            }
            val zapis = JsonLahki.objekt(surovo) ?: return potrditev(id, "error", "Neveljavno sporočilo.", "share", "neveljavno_sporocilo")
            return if (posredujDeljenje(tip, posiljatelj, cilj, zapis.surovo("payload"), id)) potrditev(id, "accepted", null, "share")
            else potrditev(id, "error", "Napaka pri posredovanju.", "share", "posredovanje_ni_uspelo")
        }

        if (tip == "handoff.request") {
            // Nadaljuj na: uporabnik izrecno preda stran/predvajanje izbrani napravi. Hub vsebine
            // ne odpira; vpise pravega posiljatelja in tovor (url, naslov, polozaj) posreduje cilju.
            val cilj = sporocilo.niz("target") ?: ""
            val posiljatelj = idPovezave(od) ?: ""
            val prejemnik = register.povezavaOd(cilj)
                ?: return potrditev(id, "rejected", "Ciljna naprava '$cilj' ni povezana ali ne obstaja.", "handoff", "naprava_ni_povezana")
            if (prejemnik === od) return potrditev(id, "rejected", "Ista naprava.", "handoff", "ista_naprava")
            val tovor = JsonLahki.objekt(surovo)?.surovo("payload")
            if (tovor == null || tovor.length > 8 * 1024) return potrditev(id, "rejected", "Neveljavna predaja.", "handoff", "neveljavno")
            val naprej = JsonLahki.Zapis().niz("id", id).niz("type", tip).niz("target", cilj)
                .niz("sender", posiljatelj).niz("sender_name", imeNaprave(posiljatelj)).stevilo("timestamp", ura() / 1000.0)
                .surovo("payload", tovor)
            return if (posljiVarno(prejemnik, naprej.toString())) potrditev(id, "accepted", null, "handoff")
            else potrditev(id, "error", "Napaka pri posredovanju.", "handoff", "posredovanje_ni_uspelo")
        }

        if (tip in INTERNET_POSREDOVANJE) {
            // Application gateway: samo kontrolni/omejeni podatkovni kosi med dvema seznanjenima
            // napravama. Hub sam nikoli ne odpira interneta. Sender vedno vpise hub.
            val cilj = sporocilo.niz("target") ?: ""
            val posiljatelj = idPovezave(od) ?: ""
            val prejemnik = register.povezavaOd(cilj)
                ?: return potrditev(id, "rejected", "Internet gateway ni povezan.", "internet", "naprava_ni_povezana")
            if (prejemnik === od) return potrditev(id, "rejected", "Ista naprava.", "internet", "ista_naprava")
            val zapis = sporocilo          // ze razclenjeno zgoraj; drugo razclenjevanje 32 KiB kosa bi bilo cisto delo zastonj
            val naprej = JsonLahki.Zapis().niz("id", id).niz("type", tip).niz("target", cilj)
                .niz("sender", posiljatelj).niz("sender_name", imeNaprave(posiljatelj)).stevilo("timestamp", ura() / 1000.0)
            for (polje in listOf("stream_id", "host", "path_id", "reason", "kind")) zapis.niz(polje)?.let { naprej.niz(polje, it) }
            // Kos toka (base64, do 32 KiB) gre naprej tak, kot je prisel: brez razlaganja in ponovnega ubezanja.
            if (zapis.vrsta("data") == JsonLahki.Vrsta.NIZ) zapis.surovo("data")?.let { naprej.surovo("data", it) }
            // port: cilj; v: razlicica protokola; bytes: potrditev (internet.window); off: odmik kosa v toku;
            // epoch: doba odjemalca (zamenja jo ob ponovnem zagonu ali izgubi Linka).
            for (polje in listOf("port", "v", "bytes", "off", "epoch")) zapis.stevilo(polje)?.let { naprej.stevilo(polje, it) }
            // internet.status: kaj ponudnik dovoli (majhen objekt; sredisce ga ne razlaga).
            if (zapis.vrsta("payload") == JsonLahki.Vrsta.OBJEKT) zapis.surovo("payload")?.takeIf { it.length <= 16 * 1024 }?.let { naprej.surovo("payload", it) }
            return if (posljiVarno(prejemnik, naprej.toString())) null
            else potrditev(id, "error", "Gateway sporocila ni bilo mogoce dostaviti.", "internet", "posredovanje_ni_uspelo")
        }

        if (tip in CONTROL_POSREDOVANJE) {
            // Daljinec med napravama (Safeer Control): ukaz gre samo napravi, ki je prijavila
            // zmoznost "remote", odgovor pa nazaj posiljatelju ukaza. Hub ukaza ne izvaja in
            // ga ne razlaga; posiljatelja vpise sam, da se ga ne da ponarediti.
            val cilj = sporocilo.niz("target") ?: ""
            val posiljatelj = idPovezave(od) ?: ""
            val (prejemnik, zmoznosti) = register.najdi(cilj).let { Pair(it?.povezava, it?.zmoznosti ?: emptyList()) }
            if (prejemnik == null) {
                android.util.Log.i("SafeerHubUsmerjevalnik", "$tip za $cilj (od $posiljatelj): cilj ni povezan - znane naprave: ${register.vse().joinToString { it.id + (if (it.povezava == null) "(brez)" else "") }}")
                return potrditev(id, "rejected", "Ciljna naprava '$cilj' ni povezana ali ne obstaja.", "control", "naprava_ni_povezana")
            }
            if (prejemnik === od) return potrditev(id, "rejected", "Naprava ne more upravljati sama sebe.", "control", "isti_naprava")
            if (tip == "control.command" && !zmoznosti.contains(ZMOZNOST_DALJINEC)) {
                return potrditev(id, "rejected", "Naprave '${imeNaprave(cilj)}' ni mogoče upravljati; posodobi Safeer na njej.", "control", "brez_daljinca")
            }
            val zapis = JsonLahki.objekt(surovo) ?: return potrditev(id, "error", "Neveljavno sporočilo.", "control", "neveljavno_sporocilo")
            val naprej = JsonLahki.Zapis()
                .niz("id", id)
                .niz("type", tip)
                .niz("target", cilj)
                .niz("sender", posiljatelj)
                .niz("sender_name", imeNaprave(posiljatelj))
                .stevilo("timestamp", ura() / 1000.0)
            zapis.niz("ref_id")?.let { naprej.niz("ref_id", it) }
            zapis.surovo("payload")?.let { naprej.surovo("payload", it) }
            return if (posljiVarno(prejemnik, naprej.toString())) {
                // Odgovor na sam ukaz pride kasneje kot control.result; potrditev pove le, da je
                // ukaz prisel do naprave. Odgovorov (control.result) ne potrjujemo nazaj.
                if (tip == "control.command") potrditev(id, "accepted", null, "control") else null
            } else potrditev(id, "error", "Napaka pri posredovanju.", "control", "posredovanje_ni_uspelo")
        }

        if (tip == "chat.send") return usmeriKlepet(od, surovo, id)

        if (tip in DATA_POSREDOVANJE) {
            // Safeer Data Transport (v0.26, docs/P2P-NACRT.md): dogovor (data.offer/data.answer) in
            // nato sifrirani kosi (data.chunk/data.ack/data.close) med dvema seznanjenima napravama.
            // Hub tovora ne razlaga in ga ne more razlagati - podpisan je s kljuci naprav (offer/answer)
            // ali sifriran z izpeljanim sejnim kljucem (kosi), ki ju Hub nikoli ne pozna. Isti splosni
            // vzorec kot internet.*: samo posreduj cilju, posiljatelja vpise Hub sam.
            val cilj = sporocilo.niz("target") ?: ""
            val posiljatelj = idPovezave(od) ?: ""
            val prejemnik = register.povezavaOd(cilj)
                ?: return potrditev(id, "rejected", "Ciljna naprava '$cilj' ni povezana ali ne obstaja.", "data", "naprava_ni_povezana")
            if (prejemnik === od) return potrditev(id, "rejected", "Naprava ne more prenasati sama sebi.", "data", "isti_naprava")
            val zapis = JsonLahki.objekt(surovo) ?: return potrditev(id, "error", "Neveljavno sporočilo.", "data", "neveljavno_sporocilo")
            val naprej = JsonLahki.Zapis()
                .niz("id", id)
                .niz("type", tip)
                .niz("target", cilj)
                .niz("sender", posiljatelj)
                .niz("sender_name", imeNaprave(posiljatelj))
                .stevilo("timestamp", ura() / 1000.0)
            zapis.surovo("payload")?.let { naprej.surovo("payload", it) }
            return if (posljiVarno(prejemnik, naprej.toString())) {
                if (tip.endsWith(".ack") || tip.endsWith(".result")) null else potrditev(id, "accepted", null, "data")
            } else potrditev(id, "error", "Napaka pri posredovanju.", "data", "posredovanje_ni_uspelo")
        }

        if (tip == "cast.status") {
            register.osveziZadnjic(sporocilo.niz("device_id"))
            objaviPosiljateljem(surovo)
            return null
        }

        if (tip == "cast.ack") return null

        if (tip == "trust.names") {
            // Naprava ponudi imena iz svojega kroga (npr. preimenovanje na drugem hubu ali prehod starih vzdevkov).
            // Hub vzame samo imena clanov, ki jih ze pozna z istim kljucem - nic novega ne vstopi v krog.
            if (register.najdi(idPovezave(od)) == null) return potrditev(id, "rejected", "Naprava ni prijavljena.", "trust", "ni_prijavljena")
            val tovor = sporocilo.surovo("payload") ?: return potrditev(id, "rejected", "Manjka krog.", "trust", "manjka_krog")
            if (krog.zdruziImena(tovor)) { objaviNaprave(); naSpremembeNaprav?.invoke() }
            return potrditev(id, "accepted", null, "trust")
        }

        if (tip == "pair.invite") {
            // Naprava v Linku pokaze QR kodo in 6-mestno kodo za novo napravo; kodo naredi sredisce, naprava jo le pokaze.
            if (register.najdi(idPovezave(od)) == null) return potrditev(id, "rejected", "Naprava ni prijavljena.", "pair", "ni_prijavljena")
            val zahtevaPreklic = sporocilo.objekt("payload")?.niz("preklici").orEmpty()
            if (zahtevaPreklic.isNotBlank()) prekliciPridruzitev(zahtevaPreklic)
            // Brez izrecnega preklica ponovimo isto kodo (naprava morda le ponavlja neuspel poskus) -
            // da se koda, ki jo uporabnik ravno prepisuje, ne spreminja izpod prstov.
            val (qrId, skrivnost, pin) = if (zahtevaPreklic.isBlank()) zagotoviPridruzitev() else ustvariPridruzitev()
            val naslov = krajevniNaslovHuba()
            posljiVarno(od, ovojnica("pair.invite.ok").surovo("payload", JsonLahki.Zapis()
                .niz("qr_id", qrId).niz("secret", skrivnost).niz("fp", lastniOdtis).niz("address", naslov)
                .niz("pin", pin).niz("code", pin)
                .stevilo("expires_in_seconds", (PIN_VELJA_MS / 1000).toDouble()).toString()).toString())
            return potrditev(id, "accepted", null, "pair")
        }

        if (tip == "pair.invite.cancel") {
            if (register.najdi(idPovezave(od)) == null) return potrditev(id, "rejected", "Naprava ni prijavljena.", "pair", "ni_prijavljena")
            sporocilo.objekt("payload")?.niz("qr_id")?.takeIf { it.isNotBlank() }?.let { prekliciPridruzitev(it) }
            return potrditev(id, "accepted", null, "pair")
        }

        if (tip == "pair.reject") {
            // Uporabnik je kodo zavrnil na drugi napravi v Linku (koda je bila pokazana povsod).
            if (register.najdi(idPovezave(od)) == null) return potrditev(id, "rejected", "Naprava ni prijavljena.", "pair", "ni_prijavljena")
            zavrniPrijavo(sporocilo.objekt("payload")?.niz("pair_id").orEmpty())
            return potrditev(id, "accepted", null, "pair")
        }

        if (tip == "apps.announce") {
            // Protocol v1: naprava (ponudnik) naknadno objavi ali osvezi svoj katalog aplikacij.
            // Hub ga hrani in razposlje v cast.devices; vsebine ne razlaga.
            val katalog = sporocilo.objekt("payload")?.surovo("apps")
                ?: return potrditev(id, "rejected", "Manjka apps.", "apps", "manjka_apps")
            val preverjen = preveriKatalog(katalog)
            val spremenjeno = synchronized(kljucnica) {
                val n = register.najdi(idPovezave(od)) ?: return potrditev(id, "rejected", "Naprava ni prijavljena.", "apps", "ni_prijavljena")
                if (n.aplikacije == preverjen) false else { n.aplikacije = preverjen; true }
            }
            if (spremenjeno) { objaviNaprave(); naSpremembeNaprav?.invoke() }
            return potrditev(id, "accepted", null, "apps")
        }

        // Kar ni na seznamu, se ne posreduje nikamor. Dovoljenja se ne smejo siriti po nesreci.
        return potrditev(id, "error", "Neznan tip sporočila: '$tip'", prostor, koda = "neznan_tip")
    }

    /**
     * Ista naprava pod dvema id-jema (stari id in id iz kljuca, ali sorodnika z istim kljucem): vstopnica,
     * izdana enemu, velja za prijavo drugega. Kljuc je identiteta, id je le ime zanjo.
     */
    private fun istiKljuc(a: String, b: String): Boolean {
        val ka = krog.clanZaId(a)?.kljuc ?: return false
        val kb = krog.clanZaId(b)?.kljuc ?: return false
        return ka == kb
    }

    private fun registriraj(od: Odjemalec, sporocilo: JsonLahki.Pogled, id: String): String {
        val tovor = sporocilo.objekt("payload")
        val deviceId = tovor?.niz("device_id")
        if (deviceId.isNullOrBlank()) return potrditev(id, "rejected", "Manjka device_id.", koda = "manjka_device_id")
        // Vstopnica je bila izdana znani napravi (po zetonu ali podpisu): prijava pod drugim id ne velja.
        // Tako je device_id vezan na zeton oz. kljuc, ne le na to, kar naprava trdi o sebi.
        val vezana = napravaVstopnice(od.vstopnica)
        if (vezana != null && vezana != deviceId && !istiKljuc(vezana, deviceId)) {
            return potrditev(id, "rejected", "device_id se ne ujema z napravo, ki ji je bila izdana vstopnica.", koda = "napacen_device_id")
        }
        od.vstopnica?.let { v -> synchronized(kljucnica) { vezaneVstopnice.keys.firstOrNull { enaka(it, v) }?.let { vezaneVstopnice.remove(it) } } }

        val vloga = tovor.niz("role") ?: "receiver"
        val zmoznosti = tovor.nizi("capabilities").ifEmpty { listOf("url", "control") }
        // Ista naprava z novo povezavo (po izpadu, ponovnem zagonu): nova zamenja staro, stara se
        // zapre - za to poskrbi register.
        val izid = register.registriraj(
            od = od,
            deviceId = deviceId,
            ime = tovor.nizAli("name"),
            vloga = vloga,
            zmoznosti = zmoznosti,
            protokol = tovor.nizAli("protocol"),
            platforma = tovor.nizAli("platform"),
            vrsta = tovor.nizAli("kind"),
            razlicica = tovor.nizAli("version"),
            prioriteta = (tovor.stevilo("priority") ?: 0.0).toInt(),
            aplikacije = tovor.surovo("apps")?.let { preveriKatalog(it) }
        )
        if (!izid.sprejeta) {
            return potrditev(id, "rejected", "Preveč naprav; odklopite katero od prejšnjih.", koda = izid.koda)
        }
        krog.zabeleziStik(listOf(deviceId), ura() / 1000.0)
        // Vsaka nova naprava spremeni seznam za vse: tudi posiljatelj je zdaj mozen cilj deljenja.
        objaviNaprave()
        naSpremembeNaprav?.invoke()
        // Krog zaupanja dobi vsaka naprava ob prijavi, da ga ima tudi takrat, ko hub ugasne.
        if (krog.stevilo() > 0) posljiVarno(od, sporociloKroga())
        // Safeer Chat: sporocila, ki so cakala, da se naprava spet poveze.
        dostaviCakajociKlepet(deviceId, od)
        if (register.najdi(deviceId)?.zmoznosti?.contains(ZMOZNOST_KLEPET) == true)
            napravaIzKljuca(deviceId)?.let { dostaviCakajociKlepet(it, od) }
        return potrditev(id, "accepted")
    }

    // ------------------------------------------------------------------ Link Mesh (docs/LINK-MESH.md)
    //
    // Vsaka naprava gosti svoj Hub; Hubi so sosedje vsak z vsakim. Naprave soseda so v registru z
    // namestnikom (Namestnik): kar jim posljemo, gre sosedu kot mesh.route, on jih preda svoji
    // lokalni napravi. Sosed nikoli ne posreduje naprej, zato zank ni.

    private val sosedje = LinkedHashMap<String, Odjemalec>()
    private val sosedNaprave = HashMap<String, MutableSet<String>>()
    /** Zadnji relay zemljevid vsakega soseda (naprave njegovih neposrednih sosedov): sosed -> id -> zapis. */
    private val zadnjiRelay = HashMap<String, Map<String, JsonLahki.Pogled>>()
    private val zacetnikSoseda = java.util.IdentityHashMap<Odjemalec, String>()
    @Volatile private var zadnjiMesh = ""

    /** Sosed je prisel (id, naslov povezave) ali odsel (id, ""): krmilnik si zapomni naslov in po izgubi hitro poskusi znova. */
    @Volatile var naSosedu: ((String, String) -> Unit)? = null

    fun sosedjeIdji(): List<String> = synchronized(kljucnica) { sosedje.keys.sorted() }

    /** Sosednja povezava za id Huba (za preizkuse in stanje). */
    internal fun javi(sosedId: String): Odjemalec? = synchronized(kljucnica) { sosedje[sosedId] }

    private fun sosedPovezave(od: Odjemalec): String? = synchronized(kljucnica) {
        sosedje.entries.firstOrNull { it.value === od }?.key
    }

    private fun jeClan(id: String): Boolean = krog.clanZaId(id) != null

    /**
     * Nase lokalne naprave za sosede: samo clani kroga, brez namestnikov (id -> zapis).
     *
     * [nasNaslov] je nas naslov na poti do soseda, ki mu seznam posiljamo: program s TE naprave (pri nas 127.0.0.1)
     * gre cez mejo s tem naslovom - gl. [HubNaslovi.zaDruge]. Brez njega (null) ostane zapis surov; tak je samo
     * kljuc, po katerem vemo, ali se je seznam spremenil.
     */
    private fun lokalneZaSosede(nasNaslov: String? = null): String {
        val lokalne = register.povezane().filter { it.povezava !is Namestnik && jeClan(it.id) }.sortedBy { it.id }
        return lokalne.joinToString(",", "{", "}") { n ->
            val z = JsonLahki.Zapis().niz("name", n.ime).niz("role", n.vloga).seznamNizov("capabilities", n.zmoznosti)
                .niz("ip", if (nasNaslov == null) n.naslov else HubNaslovi.zaDruge(n.naslov, nasNaslov))
            if (n.protokol.isNotBlank()) z.niz("protocol", n.protokol)
            if (n.platforma.isNotBlank()) z.niz("platform", n.platforma)
            if (n.vrsta.isNotBlank()) z.niz("kind", n.vrsta)
            if (n.razlicica.isNotBlank()) z.niz("version", n.razlicica)
            if (n.prioriteta > 0) z.stevilo("priority", n.prioriteta.toDouble())
            if (n.aplikacije.isNotBlank()) z.surovo("apps", n.aplikacije)
            "\"" + JsonLahki.ubezi(n.id) + "\":" + z.toString()
        }
    }

    private fun zapisZaSosede(n: RegisterNaprav.Naprava, hub: String? = null): String {
        val z = JsonLahki.Zapis().niz("name", n.ime).niz("role", n.vloga).seznamNizov("capabilities", n.zmoznosti)
            .niz("ip", n.naslov)
        if (n.protokol.isNotBlank()) z.niz("protocol", n.protokol)
        if (n.platforma.isNotBlank()) z.niz("platform", n.platforma)
        if (n.vrsta.isNotBlank()) z.niz("kind", n.vrsta)
        if (n.razlicica.isNotBlank()) z.niz("version", n.razlicica)
        if (n.prioriteta > 0) z.stevilo("priority", n.prioriteta.toDouble())
        if (n.aplikacije.isNotBlank()) z.surovo("apps", n.aplikacije)
        if (hub != null) z.niz("hub", hub)
        return "\"" + JsonLahki.ubezi(n.id) + "\":" + z.toString()
    }

    /**
     * Naprave nasih NEPOSREDNIH sosedov (ne posrednih): sosed, ki do njih nima svoje poti, jih doseze
     * prek nas. Pot je tako najvec en vmesni Hub - brez zank.
     */
    private fun posredneZaSosede(): String =
        register.povezane().filter { val p = it.povezava; p is Namestnik && !p.posredno && jeClan(it.id) }
            .sortedBy { it.id }.joinToString(",", "{", "}") { zapisZaSosede(it, (it.povezava as Namestnik).sosedId) }

    private fun sporociloNaprav(seznam: String, relay: String): String =
        ovojnica("mesh.devices").surovo("payload", JsonLahki.Zapis().niz("hub", lastniId).surovo("devices", seznam)
            .surovo("relay", relay).toString()).toString()

    /** Sosedom poslje nase lokalne naprave - vsem samo ob spremembi, ali enemu (novemu) vedno. */
    private fun objaviSosedom(samo: Odjemalec?) {
        val lokalne = lokalneZaSosede()
        val relay = posredneZaSosede()
        val seznam = lokalne + "|" + relay
        val prejemniki = synchronized(kljucnica) {
            if (samo == null && seznam == zadnjiMesh) return
            if (samo == null) zadnjiMesh = seznam
            if (samo != null) listOf(samo) else sosedje.values.toList()
        }
        // Vsak sosed dobi nas naslov, kot velja na poti do njega; prek releja (Global Link) nobenega.
        for (p in prejemniki) {
            val sosed = HubNaslovi.soseda(p.naslov)
            val nas = if (sosed.isEmpty()) "" else try { nasNaslovProti(sosed) } catch (_: Throwable) { "" }
            posljiVarno(p, sporociloNaprav(lokalneZaSosede(nas), relay))
        }
    }

    private fun posljiKrogSosedom(razen: Odjemalec?) {
        val sporocilo = ovojnica("mesh.trust").surovo("payload", krog.json()).toString()
        val vsi = synchronized(kljucnica) { sosedje.toList() }
        for ((sid, p) in vsi) {
            if (!jeClan(sid)) {
                odstraniSoseda(sid, p)
                try { p.zapri(1008, "umaknjen iz kroga") } catch (_: Throwable) { }
                continue
            }
            if (p !== razen) posljiVarno(p, sporocilo)
        }
        // Naprave, ki niso vec v krogu, izginejo tudi kot oddaljene.
        val tujci = register.povezane().filter { it.povezava is Namestnik && !jeClan(it.id) }.map { it.id }
        if (tujci.isNotEmpty()) {
            synchronized(kljucnica) { for (i in tujci) { register.odstrani(i); sosedNaprave.values.forEach { it.remove(i) } } }
            objaviNaprave(); naSpremembeNaprav?.invoke()
        }
    }

    /**
     * Sosednja povezava je vzpostavljena ([zacel] = id Huba, ki jo je odprl). Ce za istega soseda ze
     * obstaja druga, ostane tista, ki jo je odprl manjsi id - obe strani izbereta isto.
     */
    fun dodajSoseda(sosedId: String, povezava: Odjemalec, zacel: String): Boolean {
        if (sosedId.isBlank() || sosedId == lastniId) return false
        val stara: Odjemalec? = synchronized(kljucnica) {
            val obstojeca = sosedje[sosedId]
            if (obstojeca != null && obstojeca !== povezava) {
                val zacetnikStare = zacetnikSoseda[obstojeca].orEmpty()
                if (zacetnikStare == minOf(sosedId, lastniId) && zacel != zacetnikStare) return false
            }
            if (obstojeca == null && sosedje.size >= NAJVEC_SOSEDOV) return false
            zacetnikSoseda[povezava] = zacel
            sosedje[sosedId] = povezava
            obstojeca?.takeIf { it !== povezava }
        }
        if (stara != null) {
            pocistiSoseda(sosedId)
            synchronized(kljucnica) { zacetnikSoseda.remove(stara) }
            try { stara.zapri(1000, "podvojena sosednja povezava") } catch (_: Throwable) { }
        }
        try { naSosedu?.invoke(sosedId, povezava.naslov) } catch (_: Throwable) { }
        krog.zabeleziStik(listOf(sosedId), ura() / 1000.0)
        objaviSosedom(povezava)
        posljiVarno(povezava, ovojnica("mesh.trust").surovo("payload", krog.json()).toString())
        return true
    }

    /** Zavrnjena sosednja povezava se po odgovoru zapre - sicer bi vsak ponovni poskus pustil odprto vticnico. */
    private fun sprejmiSoseda(od: Odjemalec, tovor: JsonLahki.Pogled, id: String): String? {
        val odgovor = odlocitevSoseda(od, tovor, id)
        if (!odgovor.contains("\"rejected\"")) return odgovor
        posljiVarno(od, odgovor)
        try { od.zapri(1008, "zavrnjeno") } catch (_: Throwable) { }
        return null
    }

    private fun odlocitevSoseda(od: Odjemalec, tovor: JsonLahki.Pogled, id: String): String {
        val sosedId = tovor.niz("device_id")?.trim()?.take(NAJVEC_IMENA).orEmpty()
        val vstopnica = od.vstopnica
        val vezana = napravaVstopnice(vstopnica)
        val podpisana = vstopnica != null && synchronized(kljucnica) { podpisaneVstopnice.any { enaka(it, vstopnica) } }
        if (sosedId.isBlank() || vezana != sosedId || !podpisana || !jeClan(sosedId)) {
            return potrditev(id, "rejected", "Sosed mora biti clan kroga s podpisom.", koda = "ni_sosed")
        }
        if (!dodajSoseda(sosedId, od, sosedId)) return potrditev(id, "rejected", "Sosednja povezava ze obstaja.", koda = "podvojen_sosed")
        return potrditev(id, "accepted")
    }

    /** Oddaljene naprave soseda postanejo znane, a nepovezane (klepet zanje pocaka). */
    private fun pocistiSoseda(sosedId: String): Boolean {
        val spremenjeno = synchronized(kljucnica) {
            zadnjiRelay.remove(sosedId)
            val idji = sosedNaprave.remove(sosedId) ?: emptySet<String>()
            var sprememba = false
            for (n in register.vse()) {
                val p = n.povezava
                if (p is Namestnik && p.sosedId == sosedId && (p.posredno || n.id in idji)) { register.odklopi(p); sprememba = true }
            }
            sprememba
        }
        // Naprave izgubljenega soseda so morda se dosegljive prek drugega soseda.
        uporabiPosredne()
        return spremenjeno
    }

    /**
     * Iz zadnjih relay zemljevidov sosedov sestavi posredne poti. Lokalna in neposredna pot imata vedno
     * prednost; ce napravo ponuja vec sosedov, velja prvi po id (vsi Hubi izberejo enako).
     */
    private fun uporabiPosredne() {
        val novi = ArrayList<Pair<String, Odjemalec>>()
        synchronized(kljucnica) {
            val zeljene = LinkedHashMap<String, Pair<String, JsonLahki.Pogled>>()
            for (sosed in zadnjiRelay.keys.sorted()) {
                if (sosed !in sosedje) continue
                for ((did, z) in zadnjiRelay[sosed].orEmpty()) {
                    if (did in zeljene || did == lastniId || z.niz("hub") == lastniId) continue
                    val p = register.najdi(did)?.povezava
                    if (p != null && !(p is Namestnik && p.posredno)) continue     // lokalna ali neposredna pot
                    zeljene[did] = sosed to z
                }
            }
            for (n in register.vse()) {
                val p = n.povezava
                if (p is Namestnik && p.posredno && zeljene[n.id]?.first != p.sosedId) register.odklopi(p)
            }
            for ((did, par) in zeljene) {
                val (sosed, z) = par
                val prej = register.najdi(did)?.povezava
                val naslov = HubNaslovi.zaDruge(z.nizAli("ip"), "").take(64)
                val namestnik = if (prej is Namestnik && prej.posredno && prej.sosedId == sosed) prej
                    else Namestnik(sosed, sosedje.getValue(sosed), did, naslov, posredno = true)
                register.registriraj(
                    od = namestnik, deviceId = did, ime = z.nizAli("name", did),
                    vloga = z.nizAli("role", "receiver").take(16),
                    zmoznosti = z.nizi("capabilities").take(24).map { it.take(24) },
                    protokol = z.nizAli("protocol"), platforma = z.nizAli("platform"), vrsta = z.nizAli("kind"),
                    razlicica = z.nizAli("version"), prioriteta = (z.stevilo("priority") ?: 0.0).toInt(),
                    aplikacije = z.surovo("apps")?.let { preveriKatalog(it) } ?: ""
                )
                register.najdi(did)?.naslov = naslov
                if (prej == null) novi.add(did to namestnik)
            }
        }
        dostaviCakajoce(novi)
    }

    private fun dostaviCakajoce(prispeli: List<Pair<String, Odjemalec>>) {
        for ((did, p) in prispeli) {
            dostaviCakajociKlepet(did, p)
            if (register.najdi(did)?.zmoznosti?.contains(ZMOZNOST_KLEPET) == true)
                napravaIzKljuca(did)?.let { dostaviCakajociKlepet(it, p) }
        }
    }

    private fun odstraniSoseda(sosedId: String, povezava: Odjemalec?) {
        synchronized(kljucnica) {
            if (povezava != null && sosedje[sosedId] !== povezava) { zacetnikSoseda.remove(povezava); return }
            sosedje.remove(sosedId)?.let { zacetnikSoseda.remove(it) }
        }
        if (pocistiSoseda(sosedId)) { objaviNaprave(); naSpremembeNaprav?.invoke() }
        try { naSosedu?.invoke(sosedId, "") } catch (_: Throwable) { }
    }

    private fun sosedoveNaprave(sosedId: String, povezava: Odjemalec, naprave: JsonLahki.Pogled?, relay: JsonLahki.Pogled?) {
        if (naprave == null) return
        val novi = naprave.kljuci().take(NAJVEC_NAPRAV)
            .filter { it.isNotBlank() && it.length <= NAJVEC_IMENA && it != lastniId && jeClan(it) }
        krog.zabeleziStik(novi, ura() / 1000.0)
        val prispeli = ArrayList<Pair<String, Odjemalec>>()
        val gostitelj = HubNaslovi.soseda(povezava.naslov)
        synchronized(kljucnica) {
            if (sosedje[sosedId] !== povezava) return
            val stari = sosedNaprave[sosedId] ?: mutableSetOf()
            val obdrzani = mutableSetOf<String>()
            for (did in novi) {
                val z = naprave.objekt(did) ?: continue
                val obstojeca = register.najdi(did)
                val p = obstojeca?.povezava
                if (p != null && p !is Namestnik) continue                   // lokalna prijava ima prednost
                if (p is Namestnik && p.sosedId != sosedId && !p.posredno) continue   // ze neposredno prek drugega soseda
                val naslov = HubNaslovi.zaDruge(z.nizAli("ip"), gostitelj).take(64)
                val namestnik = if (p is Namestnik && p.sosedId == sosedId && !p.posredno) p else Namestnik(sosedId, povezava, did, naslov)
                register.registriraj(
                    od = namestnik, deviceId = did, ime = z.nizAli("name", did),
                    vloga = z.nizAli("role", "receiver").take(16),
                    zmoznosti = z.nizi("capabilities").take(24).map { it.take(24) },
                    protokol = z.nizAli("protocol"), platforma = z.nizAli("platform"), vrsta = z.nizAli("kind"),
                    razlicica = z.nizAli("version"), prioriteta = (z.stevilo("priority") ?: 0.0).toInt(),
                    aplikacije = z.surovo("apps")?.let { preveriKatalog(it) } ?: ""
                )
                register.najdi(did)?.naslov = naslov
                if (p == null) prispeli.add(did to namestnik)
                obdrzani.add(did)
            }
            for (did in stari - obdrzani) {
                val p = register.najdi(did)?.povezava
                if (p is Namestnik && p.sosedId == sosedId && !p.posredno) register.odklopi(p)
            }
            sosedNaprave[sosedId] = obdrzani
            if (relay != null) {
                zadnjiRelay[sosedId] = relay.kljuci().take(NAJVEC_NAPRAV)
                    .filter { it.isNotBlank() && it.length <= NAJVEC_IMENA && jeClan(it) }
                    .mapNotNull { k -> relay.objekt(k)?.let { k to it } }.toMap()
            }
        }
        dostaviCakajoce(prispeli)
        uporabiPosredne()
        objaviNaprave()
        naSpremembeNaprav?.invoke()
    }

    /** Sporocilo sosednjega Huba. Sosed nikoli ne posreduje naprej - samo nasim lokalnim napravam. */
    private fun obdelajSoseda(sosedId: String, od: Odjemalec, sporocilo: JsonLahki.Pogled): String? {
        val tovor = sporocilo.objekt("payload")
        when (sporocilo.niz("type")) {
            "mesh.devices" -> { sosedoveNaprave(sosedId, od, tovor?.objekt("devices"), tovor?.objekt("relay")); return null }
            "mesh.route" -> {
                val cilj = tovor?.niz("to").orEmpty()
                val msg = tovor?.niz("msg") ?: return null
                if (msg.length > NAJVEC_MESH_SPOROCILO) return null
                val prejemnik = register.povezavaOd(cilj)
                val vsebina = JsonLahki.objekt(msg) ?: return null
                val posiljatelj = vsebina.niz("sender").orEmpty()
                if (tovor?.logicno("relay") == true) {
                    // En vmesni skok: samo napravi, ki je NEPOSREDNO pri nasem drugem sosedu, in samo v imenu
                    // naprave, ki je lokalno pri sosedu, ki prosi. Nikoli se enkrat naprej.
                    val lokalneSoseda = synchronized(kljucnica) { sosedNaprave[sosedId]?.toSet() ?: emptySet() }
                    if (prejemnik !is Namestnik || prejemnik.posredno || prejemnik.sosedId == sosedId) return null
                    if (posiljatelj.isBlank() || posiljatelj !in lokalneSoseda) return null
                    posljiVarno(prejemnik, msg)
                    return null
                }
                if (prejemnik == null || prejemnik is Namestnik) return null        // samo lokalne; nikoli naprej
                // Sosed govori v imenu svojih naprav in naprav, ki jih posreduje (relay, en skok).
                val njegove = synchronized(kljucnica) {
                    (sosedNaprave[sosedId]?.toSet() ?: emptySet()) + zadnjiRelay[sosedId].orEmpty().keys
                }
                // Sosed sme govoriti samo v imenu svojih naprav (ali svojem); brez posiljatelja samo seznanitev.
                if (posiljatelj.isBlank()) { if (!vsebina.nizAli("type").startsWith("pair.")) return null }
                else if (posiljatelj !in njegove && posiljatelj != sosedId) return null
                posljiVarno(prejemnik, msg)
                return null
            }
            "mesh.trust" -> {
                val krogJson = sporocilo.surovo("payload") ?: return null
                // Nov clan, drug kljuc in umik samo s podpisom clana, ki ga ze poznamo (tudi Linux in Windows
                // zdaj podpisujeta vnose). Sosed tako ne more podtakniti tujega kljuca.
                if (krog.zdruzi(krogJson, obvesti = false, preveriPodpise = true)) {
                    val s = sporociloKroga()
                    for (p in register.povezanePovezave()) if (p !is Namestnik) posljiVarno(p, s)
                    posljiKrogSosedom(od)
                    naSpremembeNaprav?.invoke()
                }
                return null
            }
            "cast.ping" -> return ovojnica("cast.pong", sporocilo.nizAli("id")).toString()
        }
        return null
    }

    // ------------------------------------------------------------------ Safeer Chat
    /** Sporocila za nepovezane naprave (npr. telefon z zaprto aplikacijo): cilj -> surova sporocila s casom. */
    private val cakajociKlepet = HashMap<String, ArrayDeque<Pair<Long, String>>>()
    private var klepetNalozen = false

    /** Cakajoca sporocila prezivijo ponovni zagon huba (televizor ugasne): hrani jih Shramba. Klici pod zaklepom. */
    private fun naloziKlepetZaklenjeno() {
        if (klepetNalozen) return
        klepetNalozen = true
        val zapis = shramba?.beri(KLJUC_KLEPETA) ?: return
        for (vrstica in zapis.split('\n')) {
            val deli = vrstica.split('\t')
            if (deli.size != 3) continue
            val cas = deli[1].toLongOrNull() ?: continue
            val surovo = try { String(java.util.Base64.getDecoder().decode(deli[2]), Charsets.UTF_8) } catch (_: Throwable) { continue }
            cakajociKlepet.getOrPut(deli[0]) { ArrayDeque() }.addLast(cas to surovo)
        }
    }

    private fun shraniKlepetZaklenjeno() {
        val shramba = this.shramba ?: return
        val zapis = cakajociKlepet.flatMap { (cilj, vrsta) ->
            vrsta.map { (cas, surovo) -> cilj + "\t" + cas + "\t" + java.util.Base64.getEncoder().encodeToString(surovo.toByteArray(Charsets.UTF_8)) }
        }.joinToString("\n")
        try { shramba.pisi(KLJUC_KLEPETA, zapis) } catch (_: Throwable) { }
    }

    /**
     * Safeer Chat med napravami v Linku. Hub vsebine ne razlaga; vpise pravega posiljatelja in ime ter
     * sporocilo posreduje cilju. Ce cilj ni povezan, pa je znana naprava, sporocilo pocaka (najvec
     * [KLEPET_NA_NAPRAVO] na napravo, [KLEPET_ZIVLJENJE_MS]) in pride ob naslednji prijavi.
     */
    private fun usmeriKlepet(od: Odjemalec, surovo: String, id: String): String? {
        val zapis = JsonLahki.objekt(surovo) ?: return potrditev(id, "error", "Neveljavno sporočilo.", "chat", "neveljavno_sporocilo")
        val cilj = zapis.niz("target") ?: ""
        val posiljatelj = idPovezave(od) ?: return potrditev(id, "rejected", "Naprava ni prijavljena.", "chat", "naprava_ni_povezana")
        if (cilj.isBlank() || cilj == posiljatelj) return potrditev(id, "rejected", "Neveljaven prejemnik.", "chat", "isti_naprava")
        val tovor = zapis.surovo("payload")
        val besedilo = JsonLahki.objekt(tovor ?: "")?.niz("text") ?: ""
        if (tovor == null || besedilo.isEmpty() || besedilo.toByteArray().size > KLEPET_NAJVEC_B || tovor.length > KLEPET_NAJVEC_B * 2)
            return potrditev(id, "rejected", "Sporočilo je prazno ali preveliko.", "chat", "meja")
        // Cilj je fizicna naprava (kljuc iz kroga, en pogovor ne glede na to, katera aplikacija na njej
        // je povezana) ali posamezen id. Za kljuc izberemo povezano registracijo, ki zna klepet.
        val kandidati = register.vse().filter { it.zmoznosti.contains(ZMOZNOST_KLEPET) && napravaIzKljuca(it.id) == cilj }
        val znan = register.najdi(cilj) != null || kandidati.isNotEmpty()
        if (!znan) return potrditev(id, "rejected", "Naprave ni v Safeer Linku.", "chat", "ni_naprave")
        if (napravaIzKljuca(posiljatelj)?.let { it == cilj } == true)
            return potrditev(id, "rejected", "Neveljaven prejemnik.", "chat", "isti_naprava")
        val zapisNaprej = JsonLahki.Zapis().niz("id", id).niz("type", "chat.send").niz("target", cilj)
            .niz("sender", posiljatelj).niz("sender_name", imeNaprave(posiljatelj))
        napravaIzKljuca(posiljatelj)?.let { zapisNaprej.niz("sender_device", it) }
        val naprej = zapisNaprej.stevilo("timestamp", ura() / 1000.0).surovo("payload", tovor).toString()
        val prejemnik = register.povezavaOd(cilj) ?: kandidati.firstOrNull { it.povezava != null }?.povezava
        if (prejemnik != null && posljiVarno(prejemnik, naprej)) return potrditev(id, "accepted", null, "chat")
        synchronized(cakajociKlepet) {
            naloziKlepetZaklenjeno()
            val vrsta = cakajociKlepet.getOrPut(cilj) { ArrayDeque() }
            while (vrsta.size >= KLEPET_NA_NAPRAVO) vrsta.removeFirst()
            vrsta.addLast(ura() to naprej)
            shraniKlepetZaklenjeno()
        }
        return potrditev(id, "queued", null, "chat")
    }

    private fun dostaviCakajociKlepet(cilj: String, povezava: Odjemalec) {
        val zdaj = ura()
        val zaDostavo = synchronized(cakajociKlepet) {
            naloziKlepetZaklenjeno()
            cakajociKlepet.remove(cilj)?.toList().also { if (it != null) shraniKlepetZaklenjeno() }
        } ?: return
        val neDostavljeno = zaDostavo.filter { (cas, sporocilo) ->
            zdaj - cas <= KLEPET_ZIVLJENJE_MS && !posljiVarno(povezava, sporocilo)
        }
        if (neDostavljeno.isNotEmpty()) synchronized(cakajociKlepet) {
            cakajociKlepet.getOrPut(cilj) { ArrayDeque() }.addAll(0, neDostavljeno)
            shraniKlepetZaklenjeno()
        }
    }

    internal fun steviloCakajocihKlepetov(cilj: String): Int = synchronized(cakajociKlepet) {
        naloziKlepetZaklenjeno(); cakajociKlepet[cilj]?.size ?: 0
    }

    /**
     * Katalog aplikacij, kot ga sme hub hraniti: JSON objekt {"<id>": {"name": "...", "kind": "..."}},
     * najvec NAJVEC_APLIKACIJ vnosov, kratka imena. Kar ne ustreza, odpade - naprava z malo
     * pomnilnika ne sme hraniti tujega smetja. Vrne ociscen zapis ali prazno.
     */
    fun preveriKatalog(surovo: String): String {
        if (surovo.length > NAJVEC_KATALOG_BAJTOV) return ""
        val pogled = JsonLahki.objekt(surovo) ?: return ""
        val zapis = JsonLahki.Zapis()
        var stevilo = 0
        for (idApp in pogled.kljuci()) {
            if (stevilo >= NAJVEC_APLIKACIJ) break
            val a = pogled.objekt(idApp) ?: continue
            val cistId = idApp.take(NAJVEC_IMENA)
            if (cistId.isBlank()) continue
            val vnos = JsonLahki.Zapis()
                .niz("name", a.nizAli("name", cistId).take(NAJVEC_IMENA))
                .niz("kind", a.nizAli("kind").take(16))
            a.niz("icon")?.let { if (it.length <= 256) vnos.niz("icon", it) }
            zapis.surovo(cistId, vnos.toString())
            stevilo++
        }
        return if (stevilo == 0) "" else zapis.toString()
    }

    private fun usmeriSinhronizacijo(
        od: Odjemalec,
        sporocilo: JsonLahki.Pogled,
        surovo: String,
        id: String
    ): String? {
        val tip = sporocilo.niz("type")
        val posiljatelj = synchronized(kljucnica) { idPovezave(od) }
        val tovor = sporocilo.objekt("payload")

        if (tip == "sync.data" && tovor != null) {
            shraniKategorijo(tovor, posiljatelj)
        }

        if (tip == "sync.request" && tovor != null) {
            val ime = tovor.niz("category") ?: ""
            val shranjeno = synchronized(kljucnica) { sinhronizacija[ime] }
            if (shranjeno != null) {
                val od_razlicice = tovor.stevilo("since_version")
                if (od_razlicice == null || shranjeno.razlicica > od_razlicice) {
                    val tovorNazaj = JsonLahki.Zapis()
                        .niz("category", shranjeno.ime)
                        .stevilo("version", shranjeno.razlicica)
                        .stevilo("timestamp", shranjeno.cas)
                        .surovo("data", shranjeno.podatkiSurovo)
                    val odgovor = ovojnica("sync.data", cilj = posiljatelj)
                        .surovo("payload", tovorNazaj.toString())
                    posljiVarno(od, odgovor.toString())
                }
                return potrditev(id, "accepted", null, "sync")
            }
        }

        val cilj = sporocilo.niz("target")
        if (!cilj.isNullOrEmpty() && cilj != VSEM) {
            val povezava = register.povezavaOd(cilj)
                ?: return potrditev(id, "rejected", "Naprava '$cilj' ni povezana ali ne obstaja.", "sync", "naprava_ni_povezana")
            return if (posljiVarno(povezava, surovo)) potrditev(id, "accepted", null, "sync")
            else potrditev(id, "error", "Napaka pri posredovanju.", "sync", "posredovanje_ni_uspelo")
        }

        val prejemniki = register.povezane().filter {
            it.id != posiljatelj && (it.zmoznosti.contains(ZMOZNOST_SYNC) || it.vloga == "sync-client")
        }.mapNotNull { it.povezava }
        if (prejemniki.isEmpty()) {
            return potrditev(id, "rejected", "Nobena druga naprava ne sinhronizira.", "sync", "nobena_ne_sinhronizira")
        }
        var dostavljeno = 0
        for (povezava in prejemniki) if (posljiVarno(povezava, surovo)) dostavljeno++
        return if (dostavljeno > 0) potrditev(id, "accepted", null, "sync")
        else potrditev(id, "error", "Nobene naprave ni bilo mogoče doseči.", "sync", "nobene_ni_doseglo")
    }

    /**
     * Shrani zadnje stanje kategorije, da naprava, ki je bila ugasnjena, lahko dohiti.
     * Meje so tu, ker gre za pomnilnik televizorja: prevelika kategorija se ne shrani,
     * prevec kategorij pa ne nastane.
     */
    private fun shraniKategorijo(tovor: JsonLahki.Pogled, vir: String?) {
        val ime = tovor.niz("category") ?: return
        val podatki = tovor.surovo("data") ?: return
        val bajtov = podatki.toByteArray(Charsets.UTF_8).size
        if (bajtov > NAJVECJA_KATEGORIJA) return
        val cas = tovor.stevilo("timestamp") ?: 0.0
        synchronized(kljucnica) {
            val staro = sinhronizacija[ime]
            if (staro != null && staro.cas > cas) return
            val nova = Kategorija(ime, tovor.stevilo("version") ?: 0.0, cas, podatki, vir)
            sinhronizacija[ime] = nova
            // Ce smo cez skupno mejo, gredo ven najstarejsi vpisi, dokler nismo spet pod njo.
            while (sinhronizacija.size > NAJVEC_KATEGORIJ ||
                sinhronizacija.values.sumOf { it.bajtov } > NAJVEC_SKUPAJ_SYNC
            ) {
                val najstarejsa = sinhronizacija.keys.firstOrNull { it != ime } ?: break
                sinhronizacija.remove(najstarejsa)
            }
        }
    }

    fun kategorijeSinhronizacije(): List<String> = synchronized(kljucnica) { sinhronizacija.keys.sorted() }

    private fun objaviNaprave() {
        // Seznam dobijo vsi povezani, ne le posiljatelji: tudi zaslon mora vedeti, komu lahko
        // kaj poslje, ker je deljenje dvosmerno. Odjemalci s te naprave dobijo isti (surov) seznam,
        // odjemalci od drugod svojega - gl. napravaJson.
        val seznami = HashMap<String?, String>()
        val nasi = HashMap<String, String>()
        for (povezava in register.povezanePovezave()) {
            if (povezava is Namestnik) continue
            val odKod = HubNaslovi.soseda(povezava.naslov)            // "" = s te naprave (ali neznano)
            val nas: String? = if (odKod.isEmpty()) null
                else nasi.getOrPut(odKod) { try { nasNaslovProti(odKod) } catch (_: Throwable) { "" } }
            val sporocilo = seznami.getOrPut(nas) {
                ovojnica("cast.devices").surovo("devices", synchronized(kljucnica) { napraveJson(nas) }).toString()
            }
            posljiVarno(povezava, sporocilo)
        }
        objaviSosedom(null)
    }

    private fun objaviPosiljateljem(sporocilo: String) {
        for (posiljatelj in register.posiljateljiKopija()) {
            if (!posljiVarno(posiljatelj, sporocilo)) {
                register.odstraniPosiljatelja(posiljatelj)
            }
        }
    }

    /**
     * Posreduje deljenje ciljni napravi. Posiljatelja vpise Hub, da se ga ne da ponarediti;
     * ime posiljatelja vzame iz registra, ce ga pozna. Vrne false, ce cilj ni povezan.
     */
    internal fun posredujDeljenje(tip: String, posiljatelj: String, cilj: String, tovor: String?, id: String = novId()): Boolean {
        val prejemnik = register.povezavaOd(cilj) ?: return false
        val naprej = JsonLahki.Zapis()
            .niz("id", id)
            .niz("type", tip)
            .niz("target", cilj)
            .niz("sender", posiljatelj)
            .niz("sender_name", imeNaprave(posiljatelj))
            .stevilo("timestamp", ura() / 1000.0)
        if (tovor != null) naprej.surovo("payload", tovor)
        return posljiVarno(prejemnik, naprej.toString())
    }

    private fun novId(): String = "hub-" + nakljucni(8)

    /** Datoteka je na Hubu cela: cilju povemo, kje jo prevzame (ali da je ze v njegovi mapi). */
    private fun datotekaPrispela(d: HubTokovi.Datoteka) {
        val tovor = JsonLahki.Zapis()
            .niz("id", d.id)
            .niz("name", d.ime)
            .stevilo("size", d.velikost.toDouble())
            .niz("path", if (d.zaGostitelja) "" else d.potPrevzema())
            .niz("sha256", d.sha256)
            .logicno("for_host", d.zaGostitelja)
            .toString()
        // Ce cilj ni povezan, datoteka pocaka na Hubu (eno uro); posiljatelj je dobil odgovor 200.
        posredujDeljenje("share.file", d.posiljatelj, d.cilj, tovor)
    }

    /** Deljenje zaslona se je koncalo (posiljatelj je nehal ali odsel): cilj naj neha gledati. */
    internal fun zaslonKoncan(id: String) {
        val cilj = synchronized(kljucnica) { deljeniZasloni.remove(id) } ?: return
        val posiljatelj = synchronized(kljucnica) { deljeniZasloniPosiljatelji.remove(id) } ?: ""
        posredujDeljenje("share.screen", posiljatelj, cilj,
            JsonLahki.Zapis().niz("action", "stop").niz("id", id).toString())
        sprosti(cilj, posiljatelj)
    }

    private fun posljiVarno(komu: Odjemalec, besedilo: String): Boolean = try {
        komu.poslji(besedilo)
        true
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------------ ovojnice

    private fun ovojnica(tip: String, id: String? = null, cilj: String? = null): JsonLahki.Zapis =
        JsonLahki.Zapis()
            .niz("id", id ?: UUID.randomUUID().toString())
            .niz("type", tip)
            .stevilo("timestamp", ura() / 1000.0)
            .nic("sender")
            .niz("target", cilj)

    internal fun potrditev(
        refId: String,
        stanje: String,
        napaka: String? = null,
        prostor: String = "cast",
        koda: String? = null
    ): String = ovojnica("$prostor.ack")
        .niz("ref_id", refId)
        .niz("status", stanje)
        .niz("error", napaka)
        // Stabilna oznaka: odjemalec jo prevede v svoj jezik, besedilo je le rezerva.
        .niz("error_code", koda)
        .toString()

    private fun prostorOd(tip: String?): String {
        if (tip.isNullOrEmpty() || !tip.contains(".")) return "cast"
        return tip.substringBefore(".")
    }

    // ------------------------------------------------------------------ HTTP

    /**
     * Koncne tocke, ki jih televizor ponuja po omrezju. Namenoma jih je malo:
     * prijava, prevzem zetona, vstopnica, seznam naprav in stanje. Potrjevanje in odvzem
     * dostopa se dogajata samo na televizorju, zato ju tu ni.
     *
     * Poti so razdeljene po skupinah (seznanitev, deljenje, naprave, zaupanje, prijava, stanje).
     * Vsaka skupina je svoja metoda: doda se nova pot na enem mestu, preizkusi pa se lahko skupina
     * zase. Vrstni red skupin ne vpliva na vedenje - poti se ne prekrivajo.
     */
    fun odgovori(zahteva: HubStreznik.Zahteva): HubStreznik.Odgovor? {
        val pot = zahteva.pot
        val krajevni = jeKrajevni(zahteva.odjemalec)
        odgovoriSeznanitev(zahteva, pot, krajevni)?.let { return it }
        odgovoriDeljenje(zahteva, pot, krajevni)?.let { return it }
        odgovoriNaprave(zahteva, pot, krajevni)?.let { return it }
        odgovoriZaupanje(zahteva, pot, krajevni)?.let { return it }
        odgovoriPrijava(zahteva, pot, krajevni)?.let { return it }
        odgovoriStanje(zahteva, pot, krajevni)?.let { return it }
        // Znana pot z napacnim glagolom ni "ni te poti": naprava, ki isce Hub, prav po tem
        // loci Safeer Hub od poljubnega streznika na istih vratih.
        if (pot in ZNANE_POTI) {
            return HubStreznik.Odgovor(405, napakaJson("Ta način za to pot ni dovoljen.", "metoda_ni_dovoljena"))
        }
        return null
    }

    // ------------------------------------------------------------------ spletni odjemalec (naprava brez Safeerja)

    /** Datoteke spletnega odjemalca (assets/link-web/...); nastavi krmilnik. Brez tega spletna vrata vracajo 404. */
    @Volatile var beriSredstvo: ((String) -> String?)? = null

    /** Vrata spletnega odjemalca, kot jih je odprl krmilnik (0 = ni na voljo); gredo v vabilo za novo napravo. */
    @Volatile var spletnaVrata: Int = 0

    /**
     * Zahteve na spletnih vratih (goli HTTP, samo domace omrezje): stran spletnega odjemalca in ozek izbor
     * poti huba, ki jih ta potrebuje. Telefon brez Safeerja tako iz navadnega brskalnika poslje povezavo
     * ali besedilo in upravlja televizor. Datotek, zaslona in kroga zaupanja po tej poti ni - to je
     * namenoma samo za tisto, kar sme teci brez sifriranja v domacem omrezju.
     */
    fun odgovoriSplet(zahteva: HubStreznik.Zahteva): HubStreznik.Odgovor? {
        if (!jeKrajevni(zahteva.odjemalec)) return HubStreznik.Odgovor(403, napakaJson("Samo v krajevnem omrežju.", "samo_krajevno"))
        val pot = zahteva.pot
        if (zahteva.metoda == "GET" && (pot == "/" || pot == "/index.html" || pot.startsWith("/web/"))) {
            val ime = if (pot == "/" || pot == "/index.html") "index.html" else pot.removePrefix("/web/")
            if (ime.isBlank() || ime.contains("..") || ime.contains('/')) return HubStreznik.Odgovor(404, napakaJson("Ni te poti.", "ni_poti"))
            val vsebina = beriSredstvo?.invoke(ime) ?: return HubStreznik.Odgovor(404, napakaJson("Ni te poti.", "ni_poti"))
            val vrsta = when (ime.substringAfterLast('.', "")) {
                "html" -> "text/html; charset=utf-8"
                "js" -> "application/javascript; charset=utf-8"
                "css" -> "text/css; charset=utf-8"
                "svg" -> "image/svg+xml"
                "json" -> "application/json; charset=utf-8"
                else -> "text/plain; charset=utf-8"
            }
            return HubStreznik.Odgovor(200, vsebina, vrsta)
        }
        if (pot in SPLETNE_POTI) return odgovori(zahteva)
        return HubStreznik.Odgovor(404, napakaJson("Ni te poti.", "ni_poti"))
    }

    /**
     * Nadgradnja v WebSocket je dovoljena samo iz krajevnega omrezja in samo z veljavno
     * enokratno vstopnico. Vrne razlog zavrnitve ali null, ce je vse v redu.
     */
    fun preveriVstopnico(zahteva: HubStreznik.Zahteva): String? {
        if (zahteva.pot != "/cast/ws") return "neznana pot"
        if (!jeKrajevni(zahteva.odjemalec)) return "samo v krajevnem omrežju"
        if (!porabiVstopnico(zahteva.poizvedba["ticket"])) return "neveljavna ali potekla vstopnica"
        return null
    }

    internal fun napakaJson(sporocilo: String, koda: String = ""): String =
        JsonLahki.Zapis().niz("detail", sporocilo).niz("code", koda).toString()

    companion object {
        const val RAZLICICA_PROTOKOLA = "0.2"
        const val VSEM = "all"
        /** Vrata spletnega odjemalca (goli HTTP; ce so zasedena, jih streznik izbere sam in QR koda nosi prava). */
        const val SPLETNA_VRATA = 8991
        /** Poti huba, ki jih spletni odjemalec sme klicati: pridruzitev, vstopnica, naprave, besedilo, odhod, preimenovanje. */
        val SPLETNE_POTI = setOf("/cast/pair/qr/join", "/cast/ticket", "/cast/devices", "/cast/share/text", "/cast/health", "/cast/devices/leave",
            "/cast/devices/rename")
        const val ZMOZNOST_SYNC = "sync"

        private const val KLJUC_ZETONOV = "cast_naprave"
        private const val KLJUC_VZDEVKOV = "cast_vzdevki"
        private const val KLJUC_KLEPETA = "cast_klepet_vrsta"

        private val ZNANE_POTI = setOf(
            "/cast/pair/start", "/cast/pair/claim", "/cast/pair/sibling", "/cast/ticket", "/cast/devices", "/cast/health",
            "/cast/trust/enroll", "/cast/trust/ring", "/cast/trust/alias", "/cast/auth/challenge", "/cast/auth/ticket",
            "/cast/pair/qr/start", "/cast/pair/qr/info", "/cast/pair/qr/approve", "/cast/pair/qr/status", "/cast/pair/qr/cancel",
            "/cast/pair/qr/join", "/cast/pair/qr/invite", "/cast/pair/qr/invite/status", "/cast/pair/qr/invite/cancel",
            "/cast/pair/qr/odprto", "/cast/devices/leave"
        )
        /** Izziv za prijavo s podpisom velja minuto: dovolj za en krog po omrezju, premalo za zbiranje. */
        internal const val IZZIV_VELJA_MS = 60_000L

        private val CAST_POSREDOVANJE = setOf("cast.url", "cast.media", "cast.control")
        private val SYNC_POSREDOVANJE = setOf("sync.request", "sync.data", "sync.status")
        /** Deljenje med napravama; Hub vsebine ne odpira, le posreduje izbrani napravi. */
        private val SHARE_POSREDOVANJE = setOf("share.text", "share.file", "share.screen")
        /** Daljinec (Safeer Control): ukaz napravi z zmoznostjo "remote" in njen odgovor nazaj. */
        private val CONTROL_POSREDOVANJE = setOf("control.command", "control.result")
        private val INTERNET_POSREDOVANJE = setOf("internet.open", "internet.opened", "internet.data", "internet.close", "internet.error",
            // protokol 2 (docs/INTERNET-GATEWAY.md): vprasanje in stanje, nadzor pretoka, pol-zaprtje
            "internet.query", "internet.status", "internet.window", "internet.eof")
        /** Safeer Data Transport (v0.26): dogovor + sifrirani kosi med dvema seznanjenima napravama. */
        const val ZMOZNOST_KLEPET = "chat"
        private const val KLEPET_NAJVEC_B = 16 * 1024
        private const val KLEPET_NA_NAPRAVO = 100
        private const val KLEPET_ZIVLJENJE_MS = 7L * 24 * 3600 * 1000
        private val DATA_POSREDOVANJE = setOf("data.offer", "data.answer", "data.chunk", "data.ack", "data.close", "data.error")
        const val ZMOZNOST_DALJINEC = "remote"

        // Meje so del zasnove, ne naknadni popravek. Televizor ima malo pomnilnika in ga
        // sistem ob pomanjkanju ubije brez opozorila, zato ima vsak seznam svojo streho.
        const val NAJVEC_NAPRAV = 32
        /** Link Mesh: zmoznost sosednje povezave, najvec sosedov in najvecje posredovano sporocilo. */
        const val MESH = "mesh1"
        /** Lastnost oglasa mDNS, s katero Hub pove, da zna sosednje povezave. */
        const val TXT_MESH = "mesh"
        const val NAJVEC_SOSEDOV = 16
        const val NAJVEC_MESH_SPOROCILO = 1024 * 1024
        const val NAJVEC_CAKAJOCIH = 8
        const val NAJVEC_SEZNANJENIH = 16
        const val NAJVEC_VSTOPNIC = 8
        const val NAJVEC_KATEGORIJ = 8
        const val NAJVECJA_KATEGORIJA = 192 * 1024
        const val NAJVEC_SKUPAJ_SYNC = 512 * 1024
        const val NAJVEC_IMENA = 64
        /** Protocol v1: katalog aplikacij ene naprave (vnosov in bajtov). */
        const val NAJVEC_APLIKACIJ = 200
        const val NAJVEC_KATALOG_BAJTOV = 32 * 1024
        /** Razlicica protokola, ki jo odjemalci v1 povedo v cast.register (`protocol`). */
        const val PROTOKOL_V1 = "1.0"
        /** Sejni zetoni (prijava s podpisom): najvec hkrati in koliko casa veljajo. */
        const val NAJVEC_SEJ = 64
        const val SEJA_VELJA_MS = 12 * 60 * 60 * 1000L
        /** Najvec znakov besedila v enem deljenju (share.text po HTTP). */
        const val NAJVEC_BESEDILA = 20_000

        /** Koliko zgresenih kod prenese ena prijava, preden pade. Ugibanje s tem nima smisla. */
        const val NAJVEC_POSKUSOV = 5

        /** Kodo pokaze gostitelj, naprava jo vtipka. Starejsi Hub tega nacina ne pozna. */
        const val NACIN_KODA_NA_GOSTITELJU = "koda_na_gostitelju"
        /** Seznanitev s SPAKE2: koda ostane na obeh zaslonih, po omrezju gredo le tocke krivulje. */
        const val NACIN_SPAKE2 = "spake2"
        /** Identiteta Huba v transkriptu SPAKE2 (obe strani jo poznata vnaprej). */
        const val IDENTITETA_HUBA = "safeer-link-hub"

        fun hexVBajte(h: String): ByteArray? {
            if (h.isEmpty() || h.length % 2 != 0 || h.length > 4096 || !h.all { it in "0123456789abcdefABCDEF" }) return null
            return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        fun bajteVHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

        const val PIN_VELJA_MS = 300_000L

        /**
         * Naslov IP sosednje povezave: dohodna nosi naslov odjemalca, odhodna naslov sredisca
         * (wss://ip:vrata/cast/ws ali ip:vrata).
         */
        internal fun naslovSoseda(naslov: String): String {
            var n = naslov.substringAfter("://", naslov).substringBefore('/')
            if (n.startsWith("[")) return n.substringAfter('[').substringBefore(']')
            if (n.count { it == ':' } == 1) n = n.substringBefore(':')
            return n
        }

        /** Kljuc v shrambi za stanje varovalke kode. */
        const val KLJUC_VAROVALKE = "hub_varovalka"

        /** Odgovor napravi, ki zeli zaceti prijavo s kodo, ko jo je varovalka zaprla. */
        const val BESEDILO_KODA_ZAPRTA = "Povezovanje s kodo je začasno zaprto, ker je nekdo ugibal kodo. Na napravi v Safeer Linku odpri »Poveži naprave« ali uporabi kodo QR."
        const val PREVZEM_VELJA_MS = 600_000L
        const val VSTOPNICA_VELJA_MS = 60_000L

        private val nakljucniStevec = SecureRandom()

        private fun privzetoNakljucno(bajtov: Int): String {
            val podatki = ByteArray(bajtov)
            nakljucniStevec.nextBytes(podatki)
            val izpis = StringBuilder(bajtov * 2)
            for (b in podatki) izpis.append(String.format("%02x", b.toInt() and 0xFF))
            return izpis.toString()
        }

        /**
         * Seznanjanje in predvajanje sta krajevna stvar; z interneta se naprava ne sme prijaviti.
         * Enako kot pri Chromecastu: kdor je v hisi, sme vprasati, kdor ni, ne more niti vprasati.
         */
        fun jeKrajevni(naslov: String): Boolean {
            if (naslov.isBlank()) return false
            return try {
                val ip = java.net.InetAddress.getByName(naslov)
                ip.isSiteLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress ||
                    ip.isAnyLocalAddress
            } catch (e: Exception) {
                false
            }
        }
    }
}

/**
 * Link Mesh: povezava do naprave, ki je prijavljena pri sosednjem Hubu. Kar Hub poslje tej napravi,
 * se ovije v mesh.route in gre sosedu; ta sporocilo nespremenjeno preda svoji lokalni napravi.
 */
class Namestnik(
    val sosedId: String,
    val povezava: HubUsmerjevalnik.Odjemalec,
    val cilj: String,
    override val naslov: String,
    /** Naprava je pri sosedu nasega soseda: sosed naj sporocilo preda naprej (en sam vmesni skok). */
    val posredno: Boolean = false
) : HubUsmerjevalnik.Odjemalec {
    override fun poslji(besedilo: String) {
        val tovor = JsonLahki.Zapis().niz("to", cilj).niz("msg", besedilo)
        if (posredno) tovor.logicno("relay", true)
        povezava.poslji(JsonLahki.Zapis().niz("id", UUID.randomUUID().toString()).niz("type", "mesh.route")
            .surovo("payload", tovor.toString()).toString())
    }

    /** Zapreti se da samo sosednjo povezavo, ne posamezne oddaljene naprave. */
    override fun zapri(koda: Int, razlog: String) {}
}

/**
 * Naslov naprave cez mejo Huba (isto kot core/link_hub_streznik.py na racunalniku).
 *
 * Hub programu, ki se nanj poveze z iste naprave (Safeer OS na svojem telefonu), pripise 127.0.0.1. Ta naslov velja
 * samo tam: kdor ga dobi na drugi napravi, se z njim poveze sam nase (telefon je 5. 10. 2026 namesto zaslona
 * racunalnika klical svoja vrata) ali pa tuj program steje za »to napravo«. Cez mejo Huba zato namesto zanke potuje
 * naslov naprave, na kateri program tece.
 */
internal object HubNaslovi {
    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /** Gostitelj iz naslova povezave: goli IP (dohodna povezava) ali wss://gostitelj:vrata/pot (odhodna). */
    fun gostitelj(naslov: String): String {
        var n = naslov.trim()
        if (n.contains("://")) n = try { java.net.URI(n).host.orEmpty() } catch (_: Throwable) { "" }
        n = n.trim('[', ']').substringBefore('%')
        if (n.startsWith("::ffff:", ignoreCase = true) && n.contains('.')) n = n.substring(7)
        return n
    }

    /** Naslov, ki velja samo na napravi, kjer je nastal: zanka, IPv6 naslov povezave (fe80::/10) ali nic. */
    fun samoTukaj(naslov: String): Boolean {
        val g = gostitelj(naslov).lowercase()
        return g.isEmpty() || g == "localhost" || g == "::1" || g == "0:0:0:0:0:0:0:1" || g.startsWith("127.") ||
            g.take(3) in setOf("fe8", "fe9", "fea", "feb")
    }

    /**
     * Naslov naprave, kot velja ZUNAJ naprave, na kateri tece njen Hub. Ce naslova te naprave ne poznamo (sosed prek
     * Global Linka), naslova ni: "" pove resnico, 127.0.0.1 bi kazal na napacno napravo.
     */
    fun zaDruge(naslov: String, gostiteljHuba: String): String =
        if (!samoTukaj(naslov)) naslov else if (samoTukaj(gostiteljHuba)) "" else gostitelj(gostiteljHuba)

    /** Naslov naprave, na kateri tece sosednji Hub, iz naslova sosednje povezave; "" prek releja (127.0.0.1). */
    fun soseda(naslovPovezave: String): String = gostitelj(naslovPovezave).takeUnless { samoTukaj(it) }.orEmpty()

    /**
     * Odjemalceva stran istega pravila: naslov naprave iz seznama sredisca, kot velja pri odjemalcu - za NEPOSREDNO
     * povezavo (slika zaslona racunalnika).
     *
     * Sredisce programu na svoji napravi pripise 127.0.0.1; starejsa sredisca ta naslov posljejo tudi odjemalcem od
     * drugod. Ce nase sredisce tece drugje ([sredisceTu] = false), je naprava z zanko na NJEGOVEM naslovu ([hubUrl] je
     * pravi naslov sredisca, ne krajevni konec releja). Prek Global Linka ([prekReleja]) smo zdoma: neposredne poti v
     * domace omrezje ni, zato naslova ni - tudi ce ga seznam navaja.
     */
    fun zaOdjemalca(naslov: String, hubUrl: String, sredisceTu: Boolean, prekReleja: Boolean): String {
        if (prekReleja) return ""
        val n = naslov.trim()
        if (n.isEmpty() || !samoTukaj(n)) return n
        return if (sredisceTu) n else soseda(hubUrl)
    }

    /** Najvec naslovov, ki jih gledalec poskusi za eno sejo (docs/LINK-MESH.md, pravilo 8). */
    const val NAJVEC_KANDIDATOV = 4

    /**
     * Ali je [naslov] iz odgovora naprave naslov IPv4, na katerem jo ima smisel iskati: stiri desetiska stevila brez
     * vodilnih nicel; zanka (127.x), 0.x ter skupinski in rezervirani naslovi (224 in vec) odpadejo. Ime gostitelja
     * ni naslov - gledalec zaradi odgovora naprave ne sprasuje DNS.
     */
    fun naslovNaprave(naslov: String): Boolean {
        val deli = naslov.split('.')
        if (deli.size != 4) return false
        val stevila = IntArray(4)
        for ((i, d) in deli.withIndex()) {
            if (d.isEmpty() || d.length > 3 || !d.all { it in '0'..'9' } || (d.length > 1 && d[0] == '0')) return false
            stevila[i] = d.toInt()
        }
        return stevila.all { it <= 255 } && stevila[0] != 0 && stevila[0] != 127 && stevila[0] < 224
    }

    /**
     * Naslovi, na katerih gledalec isce napravo: najprej [naslov] iz seznama naprav Safeer Linka, nato tisti, ki jih je
     * naprava sama nastela v odgovoru na `screen.start` ([hosts]). Vsak samo enkrat, najvec [NAJVEC_KANDIDATOV].
     */
    fun kandidati(naslov: String, hosts: List<String>?): List<String> {
        val izid = ArrayList<String>()
        val prvi = naslov.trim()
        if (prvi.isNotEmpty()) izid.add(prvi)
        for (h0 in hosts.orEmpty()) {
            if (izid.size >= NAJVEC_KANDIDATOV) break
            val h = h0.trim()
            if (h.isNotEmpty() && h !in izid && naslovNaprave(h)) izid.add(h)
        }
        return izid.take(NAJVEC_KANDIDATOV)
    }

    /**
     * Nas naslov na poti do [gostitelj]: vticnica UDP se samo »poveze« - sistem izbere pot in s tem nas naslov,
     * paketa ne poslje. "" za ime namesto naslova IP (brez poizvedbe DNS) in ob napaki.
     */
    fun nasProti(gostitelj: String): String {
        if (!IPV4.matches(gostitelj) && !gostitelj.contains(':')) return ""
        return try {
            java.net.DatagramSocket().use { s ->
                s.connect(java.net.InetAddress.getByName(gostitelj), 9)
                val nas = s.localAddress
                if (nas == null || nas.isAnyLocalAddress) "" else gostitelj(nas.hostAddress.orEmpty()).takeUnless { samoTukaj(it) }.orEmpty()
            }
        } catch (_: Throwable) { "" }
    }
}
