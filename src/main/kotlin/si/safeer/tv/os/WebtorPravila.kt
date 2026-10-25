package si.safeer.tv.os

/**
 * Webtor rezerva (pot A, 25. 10. 2026; raziskava in meritve: porocilo-rezerva.md): lastnikov osebni dodatek Stremio
 * Webtor je rezerva za torrente, ki jih nas TorBox nima pripravljenih. Dodatek vrne tok »url« =
 * `.../token/<zeton>/stremio/resolve/<JWT>` (JWT 72 h), ki s 302 preusmeri na izvirno datoteko (progresivno, Range) -
 * predvaja nas predvajalnik. Ime toka ima predpono `[⚡TB]` (pri TorBoxu v Webtor profilu pripravljen - podvaja nas
 * TorBox), `[⚡WT]` (Webtor ga ima pripravljenega), `[WT]` (Webtor ga prenasa sproti) ali `[P2P]` (brezplacni racun:
 * posnetek z oglasom za paket). Polje infoHash ostane, `fileIdx` je v JWT (`hash`, `idx`).
 *
 * Vrstni red samodejne izbire (lastnik, 25. 10. 2026): ⚡ pri nasem debridu -> Usenet pripravljen pri TorBoxu -> Usenet s
 * cakanjem -> Webtor (ista izdaja: infoHash + fileIdx) -> TorBox s cakanjem. Ob zacetku toka Webtor gre ta torrent v
 * ozadju v TorBox (naslednjic je ⚡). Napaka 402/404/429 ali prvi bajt po vec kot 15 s: naslednji vir.
 *
 * Koncni naslov po preusmeritvi je vezan na IP naprave (Webtor primerja /24): drugim napravam vedno samo naslov
 * `/resolve/` (v aplikaciji nadomestek brez zetona, [SkrivnostiPravila]), nikoli koncni.
 * Ciste funkcije - preizkus tests/WebtorPravilaTest.kt in skupni primeri tests/skupni-primeri/webtor-rezerva.json.
 */
object WebtorPravila {
    const val ID_MANIFESTA = "org.stremio.webtor.io"
    /** [Stremio.Tok.vir] za tokove dodatka Webtor. */
    const val VIR = "webtor"
    /** Oznaka v vmesniku (ime storitve, ne prevajamo). */
    const val IME = "Webtor"
    /** Prvi bajt toka pozneje od tega: naslednji vir. */
    const val PRVI_BAJT_MS = 15_000L
    /** Samodejna izbira poskusi najvec toliko tokov Webtor (vsak do [PRVI_BAJT_MS]), nato naslednji vir. */
    const val NAJVEC_POSKUSOV = 2

    /** Dodatek je Webtor: prepoznamo ga po `id` manifesta (gostitelj in pot se lahko spremenita, id ne). */
    fun jeWebtor(idManifesta: String?): Boolean = idManifesta?.trim() == ID_MANIFESTA

    enum class Vrsta {
        /** `[⚡TB]`: pripravljen pri TorBoxu, ki ga ima lastnik v Webtor profilu. */
        TORBOX,
        /** `[⚡WT]`: Webtor ga ima pripravljenega (hiter zacetek). */
        PREDPOMNJEN,
        /** `[WT]` ali brez predpone: Webtor ga prenasa sproti. */
        PRENOS,
        /** `[P2P]`: brezplacni racun - Webtor da posnetek z oglasom za paket, ne vsebine. */
        P2P
    }

    data class Oznaka(val vrsta: Vrsta, val ime: String)

    private val RX_PREDPONA = Regex("^\\s*\\[\\s*(⚡️?)?\\s*(TB|WT|P2P)\\s*]\\s*", RegexOption.IGNORE_CASE)

    /** Predpona imena toka -> vrsta in ime brez predpone. */
    fun razcleni(ime: String): Oznaka {
        val m = RX_PREDPONA.find(ime) ?: return Oznaka(Vrsta.PRENOS, ime.trim())
        val strela = m.groupValues[1].isNotEmpty()
        val v = when (m.groupValues[2].uppercase()) {
            "TB" -> Vrsta.TORBOX
            "P2P" -> Vrsta.P2P
            else -> if (strela) Vrsta.PREDPOMNJEN else Vrsta.PRENOS
        }
        return Oznaka(v, ime.substring(m.range.last + 1).trim())
    }

    /** Vrsta iz shranjenega imena ([Vrsta.name]); neznano = prenos. */
    fun vrstaIz(ime: String): Vrsta = Vrsta.values().firstOrNull { it.name == ime } ?: Vrsta.PRENOS

    /**
     * Tok skrijemo: `[⚡TB]` podvaja nas TorBox (le, ce ga imamo - brez njega je to edina pot do TorBoxa), `[P2P]` je
     * posnetek z oglasom za paket, ne film.
     */
    fun skrij(v: Vrsta, imamoTorBox: Boolean): Boolean = v == Vrsta.P2P || (v == Vrsta.TORBOX && imamoTorBox)

    /** Izdaja: infoHash (40 hex, male crke) in datoteka (fileIdx; -1 = ni znana / najvecji video). */
    data class Kljuc(val hash: String, val idx: Int)

    private val RX_HASH = Regex("^[0-9a-f]{40}$")

    fun kljuc(hash: String?, idx: Int?): Kljuc? {
        val h = hash?.trim()?.lowercase() ?: return null
        if (!RX_HASH.matches(h)) return null
        return Kljuc(h, if (idx == null || idx < 0) -1 else idx)
    }

    /**
     * (hash, idx) iz JWT v naslovu `.../resolve/<JWT>` - samo za ujemanje izdaje, kadar tok nima polj infoHash/fileIdx
     * (podpis preveri Webtor sam). null = ni JWT ali ni hasha.
     */
    fun izJwt(url: String): Kljuc? {
        val jwt = url.substringBefore('?').substringBefore('#').substringAfter("/resolve/", "").substringBefore('/')
        val deli = jwt.split('.')
        if (deli.size != 3 || deli[1].isEmpty()) return null
        val telo = try { String(java.util.Base64.getUrlDecoder().decode(deli[1].trimEnd('=')), Charsets.UTF_8) } catch (_: Exception) { return null }
        val h = Regex("\"hash\"\\s*:\\s*\"([0-9a-fA-F]{40})\"").find(telo)?.groupValues?.get(1)
        val i = Regex("\"idx\"\\s*:\\s*\"?(-?\\d{1,6})").find(telo)?.groupValues?.get(1)?.toIntOrNull()
        return kljuc(h, i ?: -1)
    }

    /**
     * Vrstni red tokov Webtor za samodejno izbiro (indeksi v [webtor]): najprej tisti, ki se ujemajo z NASIMI torrenti, v
     * vrstnem redu nasih torrentov (ista izdaja, kot bi jo izbrali sami - kakovost, jezik, ⚡); nato ostali, `[⚡WT]` pred
     * `[WT]` (hitrejsi zacetek), sicer v danem redu (kakovost). Skriti ([skrij]) izpadejo. Ujemanje: isti hash IN ista
     * datoteka (paket z vec filmi ali epizodami - napacna datoteka bi bila napacen film).
     */
    fun vrstniRed(nasi: List<Kljuc?>, webtor: List<Pair<Kljuc?, Vrsta>>, imamoTorBox: Boolean): List<Int> {
        val vidni = webtor.indices.filter { !skrij(webtor[it].second, imamoTorBox) }
        val izbrani = LinkedHashSet<Int>()
        for (k in nasi) {
            if (k == null) continue
            vidni.firstOrNull { it !in izbrani && webtor[it].first == k }?.let { izbrani += it }
        }
        val ostali = vidni.filter { it !in izbrani }
        return izbrani.toList() + ostali.filter { webtor[it].second == Vrsta.PREDPOMNJEN } + ostali.filter { webtor[it].second != Vrsta.PREDPOMNJEN }
    }

    /** Tok Webtor za izdajo [nas] (torrent, ki ga je uporabnik izbral): indeks v [webtor] ali -1. */
    fun zaIzdajo(nas: Kljuc?, webtor: List<Pair<Kljuc?, Vrsta>>, imamoTorBox: Boolean): Int =
        if (nas == null) -1 else webtor.indices.firstOrNull { webtor[it].first == nas && !skrij(webtor[it].second, imamoTorBox) } ?: -1

    enum class Izid { PREDVAJAJ, NI_PAKETA, NI_TOKA, OMEJITEV, PREPOCASI, NAPAKA }

    /**
     * Sonda toka (zahteva za prvi bajt): [koda] HTTP (0 = ni odgovora), [prviBajtMs] cas do prvega bajta (null = ga ni
     * bilo), [potekel] = rok [PRVI_BAJT_MS] je potekel. 402 = ni paketa, 404/410 = Webtor toka nima, 429 = omejitev ali
     * drug naslov IP; vse razen [Izid.PREDVAJAJ] pomeni: naslednji vir.
     */
    fun odlocitev(koda: Int, prviBajtMs: Long?, potekel: Boolean): Izid = when {
        koda == 402 -> Izid.NI_PAKETA
        koda == 404 || koda == 410 -> Izid.NI_TOKA
        koda == 429 -> Izid.OMEJITEV
        potekel -> Izid.PREPOCASI
        koda in 200..299 && prviBajtMs != null -> if (prviBajtMs <= PRVI_BAJT_MS) Izid.PREDVAJAJ else Izid.PREPOCASI
        else -> Izid.NAPAKA
    }

    /**
     * Ob zacetku toka Webtor dodamo torrent TorBoxu (v ozadju, brez cakanja), da je naslednjic takoj predvajljiv (⚡):
     * samo s kljucem TorBox, ce uporabnik ni izbral »Brez debrida« in ce ga TorBox se nima. To je namerno predpomnjenje -
     * pospravljanje (»samo takoj«) ga ne brise.
     */
    fun dodajVTorBox(imamoTorBox: Boolean, debridVklopljen: Boolean, predpomnjen: Boolean, hash: String?): Boolean =
        imamoTorBox && debridVklopljen && !predpomnjen && hash != null && RX_HASH.matches(hash.lowercase())

    /** Magnet za izdajo, za katero nimamo svojega (z dodatnimi sledilniki). */
    fun magnet(hash: String): String = "magnet:?xt=urn:btih:" + hash.lowercase()
}
