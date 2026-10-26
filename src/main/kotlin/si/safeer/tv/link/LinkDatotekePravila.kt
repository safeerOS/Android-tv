package si.safeer.tv.link

import java.security.MessageDigest

/**
 * Datoteke te naprave za druge naprave v ozjem krogu (`files.list`, `/d/`, `/thumb/`): ciste odlocitve brez Androida
 * (preizkus: tests/LinkDatotekePravilaTest.kt). Branje MediaStore in map ter odgovore dela [DatotekeStreznik].
 *
 * Lastnik, 25. 10. 2026: racunalnik s polnim dostopom mora videti VSE datoteke, ki jih telefon dovoli, jih filtrirati po
 * datumu nastanka (datum posnetka), videti fotografije s kamere s slicicami, velike galerije pa se nalagajo po delih.
 * Pogodba z odjemalcem (Windows, Linux): specifikacija Windows Datotek, razdelek 3 - vsa nova polja in parametri so
 * izbirni, stari odjemalci jih prezrejo.
 */
object LinkDatotekePravila {

    // ------------------------------------------------------------------ deli seznama

    /** Najvec vnosov v enem odgovoru (tudi privzeto); naslednje odjemalec dobi z `offset`. */
    const val NAJVEC = 500

    /** Del seznama: [odmik] vnosov preskoci, vrne jih najvec [meja]. */
    data class Stran(val odmik: Int, val meja: Int)

    /** Iz parametrov `offset` in `limit` (manjkajoc ali neveljaven = 0): odmik >= 0, meja 1..[NAJVEC], privzeto [NAJVEC]. */
    fun stran(offset: Long, limit: Long): Stran = Stran(
        odmik = offset.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        meja = if (limit <= 0L) NAJVEC else limit.coerceAtMost(NAJVEC.toLong()).toInt())

    /**
     * `next_offset`: odmik naslednjega dela ali 0, ko ga ni. [pregledanih] = vrstic, ki jih je ta del pregledal (tudi
     * izpuscene, npr. brez imena - sicer bi jih naslednji del ponovil), [skupaj] = vseh v mapi.
     */
    fun naslednji(odmik: Int, pregledanih: Int, skupaj: Int): Int =
        if (pregledanih > 0 && odmik.toLong() + pregledanih < skupaj) odmik + pregledanih else 0

    // ------------------------------------------------------------------ datum nastanka

    /** Datum nastanka v ms: datum posnetka ([posneto], MediaStore DATE_TAKEN, ms), ce ga ni (0), cas spremembe (ms). */
    fun datumNastanka(posneto: Long, spremenjeno: Long): Long = if (posneto > 0L) posneto else spremenjeno

    /** Obdobje `from` (vkljucno) .. `to` (izkljucno), ms UTC; null meja = odprto. */
    data class Obdobje(val od: Long?, val doo: Long?) {
        /** Obdobje, v katerem ne more biti nicesar (od >= do). */
        fun prazno(): Boolean = od != null && doo != null && od >= doo
    }

    /** Iz parametrov `from`/`to` (manjkajoc ali negativen = brez meje); null, ce ni nobene meje. */
    fun obdobje(od: Long?, doo: Long?): Obdobje? {
        val a = od?.takeIf { it >= 0L }
        val b = doo?.takeIf { it >= 0L }
        return if (a == null && b == null) null else Obdobje(a, b)
    }

    fun vObdobju(datum: Long, o: Obdobje?): Boolean =
        o == null || ((o.od == null || datum >= o.od) && (o.doo == null || datum < o.doo))

    /** Izraz SQL za datum nastanka (ms) v MediaStore: datum posnetka (ms) ali, ce ga ni, cas spremembe (s) * 1000. */
    const val IZRAZ_DATUMA = "COALESCE(NULLIF(datetaken, 0), date_modified * 1000)"

    /** Zbirke brez datuma posnetka (glasba): samo cas spremembe. */
    const val IZRAZ_SPREMEMBE = "date_modified * 1000"

    /**
     * Pogoj SQL za obdobje nad izrazom [izraz]; null, ce obdobja ni. Meji sta vpisani kot stevili (Long, ne besedilo
     * od drugod): argument `?` bi SQLite primerjal kot besedilo in izraz brez afinitete (COALESCE) ne bi bil nikoli
     * vecji od besedila - filter bi vrnil prazno.
     */
    fun izborObdobja(o: Obdobje?, izraz: String = IZRAZ_DATUMA): String? {
        if (o == null) return null
        val deli = ArrayList<String>(2)
        o.od?.let { deli.add("$izraz >= $it") }
        o.doo?.let { deli.add("$izraz < $it") }
        return deli.joinToString(" AND ").ifEmpty { null }
    }

    /** Dva pogoja SQL v enega (AND); prazen ali null del izpusti. */
    fun zdruzi(a: String?, b: String?): String? = when {
        a.isNullOrBlank() -> b?.takeIf { it.isNotBlank() }
        b.isNullOrBlank() -> a
        else -> "($a) AND ($b)"
    }

    /** Slike in videi v MediaStore.Files (MEDIA_TYPE_IMAGE = 1, MEDIA_TYPE_VIDEO = 3). */
    const val IZBOR_SLIK_VIDEOV = "media_type IN (1, 3)"

    // ------------------------------------------------------------------ dovoljenja in raven dostopa

    const val SHRAMBA = "android.permission.READ_EXTERNAL_STORAGE"
    const val VIDEI = "android.permission.READ_MEDIA_VIDEO"
    const val ZVOK = "android.permission.READ_MEDIA_AUDIO"
    const val SLIKE = "android.permission.READ_MEDIA_IMAGES"
    const val IZBRANE = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    /**
     * Dovoljenja, ki jih vprasamo za deljenje medijev. Android 14+ tudi izbrane fotografije: z njimi sistem ponudi
     * »Izberi fotografije« in »Dovoli vse« (brez njih bi delni izbor naprava videla kot zavrnitev).
     */
    fun dovoljenja(sdk: Int): List<String> = when {
        sdk >= 34 -> listOf(VIDEI, ZVOK, SLIKE, IZBRANE)
        sdk >= 33 -> listOf(VIDEI, ZVOK, SLIKE)
        else -> listOf(SHRAMBA)
    }

    /** Kaj naprava deli: odgovor `access` v `files.list`. */
    enum class Dostop(val oznaka: String) { POLN("full"), DELNI("partial"), BREZ("none") }

    /**
     * Raven dostopa iz odobrenih dovoljenj ([odobrena]) in posebnega dostopa do vseh datotek ([vseDatoteke]):
     * POLN z vsemi slikami in videi (ali z dostopom do vseh datotek), DELNI z izbranimi fotografijami (Android 14+) ali
     * samo z delom zbirk (npr. samo glasba), BREZ brez dovoljenj.
     *
     * Napaka do 25. 10. 2026: Android 14 ob »Izberi fotografije« odobri samo READ_MEDIA_VISUAL_USER_SELECTED, naprava pa
     * je preverjala samo READ_MEDIA_* in javila, da datotek ne deli (`shared: false, reason: permission`).
     */
    fun dostop(sdk: Int, odobrena: Set<String>, vseDatoteke: Boolean): Dostop = when {
        vseDatoteke -> Dostop.POLN
        sdk < 33 -> if (SHRAMBA in odobrena) Dostop.POLN else Dostop.BREZ
        SLIKE in odobrena && VIDEI in odobrena -> Dostop.POLN
        SLIKE in odobrena || VIDEI in odobrena || ZVOK in odobrena || (sdk >= 34 && IZBRANE in odobrena) -> Dostop.DELNI
        else -> Dostop.BREZ
    }

    /** Kaj ta naprava vprasa uporabnika ob privolitvi v Linku ali ob prosnji druge naprave (`files.request_access`). */
    enum class Prosnja { NIC, FOTOGRAFIJE, VSE_DATOTEKE }

    /**
     * Lastnik, 25. 10. 2026 10:49: naprave iz ozjega kroga dobijo dostop z eno privolitvijo v Linku, brez locenih stikal.
     * Zato privolitev (in prosnja druge naprave) ponudi najboljse, kar naprava zmore: vse datoteke ([vseMogoce]:
     * Android 11+ posebni dostop ali Android 9), dokler jih ni; sicer medije, ce jih nima vseh. Ce uporabnik vseh datotek
     * ne odobri, DostopDatotekActivity sam vprasa se za medije (rezervni nacin). Kar je ze odobreno, ne vprasa znova.
     * Prej je vse datoteke ponudila samo prosnja z `all: true`; privolitev v Linku dovoljenj Androida ni vprasala.
     */
    fun prosnja(dostop: Dostop, vseDatoteke: Boolean, vseMogoce: Boolean): Prosnja = when {
        vseMogoce && !vseDatoteke -> Prosnja.VSE_DATOTEKE
        dostop != Dostop.POLN -> Prosnja.FOTOGRAFIJE
        else -> Prosnja.NIC
    }

    // ------------------------------------------------------------------ mape (navidezne oznake)

    const val KOREN = "root"
    const val PREDPONA = "media:"
    const val FS = "fs:"
    const val KAMERA = "media:camera"
    const val POSNETKI = "media:screenshots"
    const val ALBUMI = "media:albums"
    const val ALBUM = "media:bucket:"
    val ZBIRKE = listOf("video", "audio", "image", "shramba")

    /** Mapa, ki jo odjemalec odpre (`folder`). Telefon nikoli ne sprejme poti od drugod: oznake so navidezne. */
    sealed class Mapa {
        object Koren : Mapa()
        data class Zbirka(val ime: String) : Mapa()
        object Kamera : Mapa()
        object Posnetki : Mapa()
        object Albumi : Mapa()
        data class Album(val id: Long) : Mapa()
        /** Mapa notranjega pomnilnika (dostop do vseh datotek); "" = njegov koren. */
        data class Pot(val rel: String) : Mapa()
    }

    /** Oznaka mape -> mapa; null za vse, cesar ne poznamo (tudi `media:bucket:../`). */
    fun mapa(oznaka: String): Mapa? {
        if (oznaka.isBlank() || oznaka == KOREN) return Mapa.Koren
        if (oznaka.startsWith(FS)) return potFs(oznaka)?.let { Mapa.Pot(it) }
        if (!oznaka.startsWith(PREDPONA)) return null
        val r = oznaka.removePrefix(PREDPONA)
        return when {
            r == "camera" -> Mapa.Kamera
            r == "screenshots" -> Mapa.Posnetki
            r == "albums" -> Mapa.Albumi
            r in ZBIRKE -> Mapa.Zbirka(r)
            r.startsWith("bucket:") -> idAlbuma(r.removePrefix("bucket:"))?.let { Mapa.Album(it) }
            else -> null
        }
    }

    private val STEVILO = Regex("-?[0-9]{1,19}")

    /** BUCKET_ID je stevilka (zgoscena pot mape, lahko negativna) - nikoli pot. */
    fun idAlbuma(s: String): Long? = if (STEVILO.matches(s)) s.toLongOrNull() else null

    /** Mape, v katerih je urejanje (brisanje v Smeti, vrtenje slike) mogoce: zbirke MediaStore z datotekami. */
    fun urejanje(m: Mapa): Boolean = when (m) {
        is Mapa.Zbirka, Mapa.Kamera, Mapa.Posnetki, is Mapa.Album -> true
        else -> false
    }

    // ------------------------------------------------------------------ poti notranjega pomnilnika (fs:)

    private const val NAJDALJSA_POT = 4096

    /**
     * Relativna pot pod korenom notranjega pomnilnika iz oznake `fs:<pot>` ("" = koren); null, ce je ne smemo odpreti:
     * `.` in `..`, prazni deli, skrite mape in datoteke (pika spredaj), kontrolni znaki, `\`, Android/data in Android/obb
     * (podatki drugih aplikacij). Koren doda telefon sam; absolutne poti od drugod ne sprejme.
     */
    fun potFs(oznaka: String): String? {
        if (!oznaka.startsWith(FS)) return null
        val rel = oznaka.substring(FS.length)
        if (rel.isEmpty() || rel == "/") return ""
        if (rel.length > NAJDALJSA_POT) return null
        val deli = rel.removePrefix("/").removeSuffix("/").split('/')
        if (deli.any { !dovoljenDel(it) }) return null
        if (zasebnaMapa(deli)) return null
        return deli.joinToString("/")
    }

    /** Del poti, ki ga pokazemo in odpremo: ni prazen, ni skrit (tudi `.` in `..`), brez kontrolnih znakov in `\`. */
    fun dovoljenDel(d: String): Boolean = d.isNotEmpty() && !d.startsWith(".") && d.none { it < ' ' || it == '\\' || it == '\u007f' }

    /** Android/data in Android/obb: zasebne mape aplikacij. */
    fun zasebnaMapa(deli: List<String>): Boolean = deli.size >= 2 && deli[0].equals("Android", ignoreCase = true) &&
        (deli[1].equals("data", ignoreCase = true) || deli[1].equals("obb", ignoreCase = true))

    /**
     * Pot po razresitvi simbolnih povezav ([kanonicna]) mora ostati pod kanonicnim korenom ([koren]) in biti dovoljena
     * ([potFs]); vrne relativno pot ali null. Tako povezava v mapi ne pripelje ven iz pomnilnika ali v Android/data.
     */
    fun relativnaZnotraj(koren: String, kanonicna: String): String? {
        val k = koren.trimEnd('/')
        if (k.isEmpty()) return null
        if (kanonicna == k) return ""
        if (!kanonicna.startsWith("$k/")) return null
        return potFs(FS + kanonicna.substring(k.length + 1))
    }

    fun oznakaFs(rel: String): String = FS + rel

    fun podpot(relMape: String, ime: String): String = if (relMape.isEmpty()) ime else "$relMape/$ime"

    /** Vnos mape notranjega pomnilnika: [spremenjeno] ms, [posneto] = datum posnetka iz MediaStore (0 = ni znan). */
    data class Vnos(val ime: String, val mapa: Boolean, val velikost: Long, val spremenjeno: Long, val posneto: Long = 0L) {
        val nastanek: Long get() = datumNastanka(posneto, spremenjeno)
    }

    private val RED = Comparator<Vnos> { a, b ->
        when {
            a.mapa != b.mapa -> if (a.mapa) -1 else 1
            a.mapa -> String.CASE_INSENSITIVE_ORDER.compare(a.ime, b.ime).takeIf { it != 0 } ?: a.ime.compareTo(b.ime)
            else -> b.nastanek.compareTo(a.nastanek).takeIf { it != 0 } ?: a.ime.compareTo(b.ime)
        }
    }

    /**
     * Vidni vnosi mape [relMape] v stalnem vrstnem redu (deli seznama se ne prekrivajo): najprej mape po imenu, nato
     * datoteke od najnovejse po datumu nastanka. Brez skritih in zasebnih; obdobje ([o]) velja za datoteke, mape
     * ostanejo (vanje se gre).
     */
    fun uredi(relMape: String, vnosi: List<Vnos>, o: Obdobje? = null): List<Vnos> = vnosi
        .filter { dovoljenDel(it.ime) && potFs(FS + podpot(relMape, it.ime)) != null }
        .filter { it.mapa || vObdobju(it.nastanek, o) }
        .sortedWith(RED)

    /** Vrsta vnosa iz vrste MIME. */
    fun vrsta(mime: String): String = when {
        mime.startsWith("video/") -> "video"
        mime.startsWith("audio/") -> "audio"
        mime.startsWith("image/") -> "image"
        else -> "file"
    }

    // ------------------------------------------------------------------ albumi

    class AlbumVnos(val id: Long, val ime: String, val stevilo: Int, val najnovejse: Long)

    /**
     * Ime albuma za prikaz: ime mape iz MediaStore; mapa brez imena je koren notranjega pomnilnika (relative_path "/"
     * ali prazno) - [korenIme] -, sicer zadnji del poti. Prej je tak album dobil kar stevilko BUCKET_ID.
     */
    fun imeAlbuma(ime: String?, relPot: String?, korenIme: String): String? {
        if (!ime.isNullOrBlank()) return ime
        val pot = relPot?.trim('/').orEmpty()
        if (relPot != null && pot.isEmpty()) return korenIme
        return pot.substringAfterLast('/').ifEmpty { null }
    }

    /** Albumi (BUCKET_ID) iz vrstic MediaStore: stevilo in najnovejsi datum; urejeni od najnovejsega. */
    class ZbiralnikAlbumov {
        private class Z(val ime: String, var stevilo: Int, var najnovejse: Long)
        private val albumi = LinkedHashMap<Long, Z>()

        fun dodaj(id: Long, ime: String?, datum: Long) {
            val z = albumi.getOrPut(id) { Z(ime?.takeIf { it.isNotBlank() } ?: id.toString(), 0, 0L) }
            z.stevilo++
            if (datum > z.najnovejse) z.najnovejse = datum
        }

        fun seznam(): List<AlbumVnos> = albumi.entries.map { AlbumVnos(it.key, it.value.ime, it.value.stevilo, it.value.najnovejse) }
            .sortedWith(compareByDescending<AlbumVnos> { it.najnovejse }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.ime })
    }

    // ------------------------------------------------------------------ slicice

    const val ROB_PRIVZETO = 256
    const val ROB_NAJMANJ = 128
    const val ROB_NAJVEC = 512

    /** Najvec bajtov ene slicice; vecjo stisnemo mocneje ([KAKOVOSTI]). */
    const val NAJVEC_SLICICE = 200 * 1024
    val KAKOVOSTI = intArrayOf(78, 62, 45)

    /**
     * Slicica iz MediaStore je opazno manjsa od zahtevane (izmerjeno 25. 10. 2026: WP28 S na ?s=512 vrne 320 px, OnePlus 9
     * 512 px): takrat sliko dekodiramo sami z zmanjsanjem ([smeDekodirati]).
     */
    fun premajhna(imamo: Int, rob: Int): Boolean = imamo in 1 until rob * 9 / 10

    /** Najvec slikovnih tock slike brez vzorcenja pri dekodiranju (PNG, GIF, BMP): 16 MP = 64 MB v pomnilniku. */
    const val NAJVEC_TOCK_BREZ_VZORCENJA = 16_000_000L

    /**
     * Ali sme telefon sam dekodirati sliko [mime] z mermi [sirina] x [visina] v slicico: JPEG, WebP, HEIF in AVIF se
     * dekodirajo z vzorcenjem (polne slike ni v pomnilniku); drugo samo do [NAJVEC_TOCK_BREZ_VZORCENJA].
     */
    fun smeDekodirati(mime: String, sirina: Int, visina: Int): Boolean {
        if (sirina <= 0 || visina <= 0) return false
        // Pregled link-datoteke (6): JPEG ImageDecoder vzorci le do 1/8 - 60 000 x 60 000 bi bilo ~225 MB.
        if (sirina.toLong() * visina > NAJVEC_TOCK_Z_VZORCENJEM) return false
        val m = mime.lowercase()
        if (m == "image/jpeg" || m == "image/webp" || m == "image/heic" || m == "image/heif" || m == "image/avif") return true
        return m.startsWith("image/") && sirina.toLong() * visina <= NAJVEC_TOCK_BREZ_VZORCENJA
    }

    /** Najvec slikovnih tock tudi pri formatih z vzorcenjem (pregled link-datoteke, tocka 6): 200 MP. */
    const val NAJVEC_TOCK_Z_VZORCENJEM = 200_000_000L

    // ------------------------------------------------------------------ vse datoteke (fs:) na napravo

    /**
     * Pregled link-datoteke (25. 10. 2026, tocka 2): `fs:` (ves notranji pomnilnik) dobi samo naprava, ki ji je uporabnik
     * datoteke odprl s privolitvijo po uvedbi te funkcije ([privolitev]) - ne vse naprave z »datoteke« naenkrat, ko ena
     * privolitev odobri dostop do vseh datotek.
     */
    fun smeVseDatoteke(deljeno: Boolean, privolitev: Boolean, smeDatoteke: Boolean): Boolean = deljeno && privolitev && smeDatoteke

    /** Preverba umika naprave (pregled link-datoteke, tocka 4): napaka pri branju kroga = umaknjena (»ne vem« ni »da«). */
    fun umaknjenaVarno(preveri: () -> Boolean): Boolean = try { preveri() } catch (_: Throwable) { true }

    // ------------------------------------------------------------------ lokacija v vsebini (specifikacija 3.7)

    /** Kako poslati vsebino, da lokacija posnetka (EXIF GPS, XMP, ©xyz) ne gre ven (pregled link-datoteke, tocka 1). */
    enum class PotVsebine { NEPOSREDNO, MEDIASTORE, KOPIJA_BREZ_LOKACIJE, ZAVRNI }

    /** EXIF in XMP znacke z lokacijo (imena kot android.media.ExifInterface.TAG_GPS_* in TAG_XMP). */
    val ZNACKE_LOKACIJE = listOf(
        "GPSVersionID", "GPSLatitudeRef", "GPSLatitude", "GPSLongitudeRef", "GPSLongitude", "GPSAltitudeRef", "GPSAltitude",
        "GPSTimeStamp", "GPSSatellites", "GPSStatus", "GPSMeasureMode", "GPSDOP", "GPSSpeedRef", "GPSSpeed", "GPSTrackRef",
        "GPSTrack", "GPSImgDirectionRef", "GPSImgDirection", "GPSMapDatum", "GPSDestLatitudeRef", "GPSDestLatitude",
        "GPSDestLongitudeRef", "GPSDestLongitude", "GPSDestBearingRef", "GPSDestBearing", "GPSDestDistanceRef",
        "GPSDestDistance", "GPSProcessingMethod", "GPSAreaInformation", "GPSDateStamp", "GPSDifferential",
        "GPSHPositioningError", "Xmp")

    /**
     * Slike brez EXIF in XMP (lokacije ne morejo nositi). Pregled 99, V2 (26. 10. 2026): prej je bil tu seznam slik Z
     * lokacijo in vse ostalo (RAW cr2/nef/arw ...) je slo ven surovo z GPS; zdaj velja obratno - neznana slika nosi lokacijo.
     */
    private val SLIKE_BREZ_LOKACIJE = setOf("image/gif", "image/bmp", "image/x-ms-bmp", "image/x-icon",
        "image/vnd.microsoft.icon", "image/vnd.wap.wbmp", "image/svg+xml")

    /** Vsi videi in vse slike razen [SLIKE_BREZ_LOKACIJE] lahko nosijo lokacijo (fail-closed, Pregled 99, V2). */
    fun nosiLokacijo(mime: String): Boolean {
        val m = mime.lowercase()
        return m.startsWith("video/") || (m.startsWith("image/") && m !in SLIKE_BREZ_LOKACIJE)
    }

    /** Vrsta po prvih bajtih - ime lahko lazje (IMG.jpg.bak je se vedno JPEG z GPS): MIME ali null. */
    fun vrstaPoGlavi(g: ByteArray): String? {
        fun b(i: Int) = if (i < g.size) g[i].toInt() and 0xFF else -1
        fun niz(od: Int, n: Int) = if (g.size >= od + n) String(g, od, n, Charsets.ISO_8859_1) else ""
        return when {
            b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF -> "image/jpeg"
            b(0) == 0x89 && niz(1, 3) == "PNG" -> "image/png"
            niz(0, 4) == "RIFF" && niz(8, 4) == "WEBP" -> "image/webp"
            // Pregled 99, V2: AVI, TIFF (DNG, CR2, NEF, ARW, ORF, RW2), RAF, Matroska in QuickTime brez ftyp so prej
            // ostali neprepoznani in pod laznim imenom sli ven surovo (z EXIF GPS).
            niz(0, 4) == "RIFF" && niz(8, 4) == "AVI " -> "video/x-msvideo"
            niz(0, 4) == "II*\u0000" || niz(0, 4) == "MM\u0000*" || niz(0, 4) == "IIRO" || niz(0, 4) == "IIU\u0000" -> "image/tiff"
            niz(0, 8) == "FUJIFILM" -> "image/x-fuji-raf"
            b(0) == 0x1A && b(1) == 0x45 && b(2) == 0xDF && b(3) == 0xA3 -> "video/x-matroska"
            niz(4, 4) == "ftyp" -> when (niz(8, 4)) {
                "heic", "heix", "hevc", "heim", "heis", "mif1", "msf1" -> "image/heic"
                "avif", "avis" -> "image/avif"
                else -> "video/mp4"
            }
            niz(4, 4) in ATOMI_QUICKTIME -> "video/quicktime"
            else -> null
        }
    }

    /** Prvi atom starejsih .mov brez ftyp. */
    private val ATOMI_QUICKTIME = setOf("moov", "mdat", "wide", "free", "skip", "pnot")

    /** Ali zna android.media.ExifInterface to vrsto zapisati: JPEG povsod, PNG in WebP od API 30. */
    fun kopijaMogoca(mime: String, sdk: Int): Boolean {
        val m = mime.lowercase()
        return m == "image/jpeg" || (sdk >= 30 && (m == "image/png" || m == "image/webp"))
    }

    /**
     * `fs:` (surova datoteka): slika ali video z lokacijo prek MediaStore, ce ga ima (Android 10+ brez ACCESS_MEDIA_LOCATION
     * lokacijo zakrije sam); sicer kopija brez EXIF GPS in XMP; kar tako ne gre (video ali HEIC brez MediaStore, Android 9)
     * - ne posljemo. Druge datoteke (dokumenti, GIF) neposredno.
     */
    fun potVsebineFs(mime: String, sdk: Int, vMediaStore: Boolean): PotVsebine = when {
        !nosiLokacijo(mime) -> PotVsebine.NEPOSREDNO
        sdk >= 29 && vMediaStore -> PotVsebine.MEDIASTORE
        kopijaMogoca(mime, sdk) -> PotVsebine.KOPIJA_BREZ_LOKACIJE
        else -> PotVsebine.ZAVRNI
    }

    /**
     * `media:*` (MediaStore): Android 10+ lokacijo zakrije sam. Android 9 je ne: JPEG gre kot kopija brez lokacije; video in
     * HEIC na Androidu 9 ostaneta neposredno (odprto - porocilo android-232).
     */
    fun potVsebineMedija(mime: String, sdk: Int): PotVsebine = when {
        sdk >= 29 || !nosiLokacijo(mime) -> PotVsebine.MEDIASTORE
        kopijaMogoca(mime, sdk) -> PotVsebine.KOPIJA_BREZ_LOKACIJE
        else -> PotVsebine.MEDIASTORE
    }

    /** Najvecja datoteka, ki jo za kopijo brez lokacije prekopiramo (vecja = ne posljemo). */
    const val NAJVEC_KOPIJE = 64L * 1024 * 1024

    // ------------------------------------------------------------------ kopija brez XMP (Pregled 99, F1 in V1)

    private val XMP_JPEG = listOf("http://ns.adobe.com/xap/1.0/\u0000", "http://ns.adobe.com/xmp/extension/\u0000")
        .map { it.toByteArray(Charsets.ISO_8859_1) }
    private const val XMP_PNG = "XML:com.adobe.xmp"
    private val ZNAKI_XMP = listOf("adobe:ns:meta", "ns.adobe.com/xap/1.0", XMP_PNG).map { it.toByteArray(Charsets.ISO_8859_1) }

    /**
     * Kopija [vir] v mapi [mapa] brez paketov XMP, najvec [NAJVEC_KOPIJE] bajtov; null, ce je prevelika, oblika ni znana
     * ali kar koli ne uspe. EXIF GPS nato odstrani klicatelj (android.media.ExifInterface) in preveri se [vsebujeXmp].
     * Pregled 99, F1: predpona zacasne datoteke mora imeti vsaj 3 znake - s "k" je createTempFile vedno vrgel
     * IllegalArgumentException in kopija ni nastala nikoli (Android 9: vsak JPEG iz galerije 403).
     */
    fun pripraviKopijo(mapa: java.io.File, vir: () -> java.io.InputStream?, mime: String): java.io.File? {
        val surova = try { java.io.File.createTempFile("link-vir", ".tmp", mapa) } catch (_: Throwable) { return null }
        val t = try { java.io.File.createTempFile("link-kopija", ".tmp", mapa) } catch (_: Throwable) { surova.delete(); return null }
        try {
            val vhod = vir() ?: run { t.delete(); return null }
            if (!kopirajOmejeno(vhod, surova)) { t.delete(); return null }
            if (!odstraniXmp(surova, t, mime)) { t.delete(); return null }
            return t
        } catch (_: Throwable) { t.delete(); return null } finally { surova.delete() }
    }

    /** [vhod] v [cilj], najvec [NAJVEC_KOPIJE] bajtov (false, ce je vec); vhod se zapre. */
    private fun kopirajOmejeno(vhod: java.io.InputStream, cilj: java.io.File): Boolean {
        val o = cilj.outputStream()
        try {
            val b = ByteArray(64 * 1024)
            var skupaj = 0L
            var n = vhod.read(b)
            while (n >= 0) {
                skupaj += n
                if (skupaj > NAJVEC_KOPIJE) return false
                o.write(b, 0, n)
                n = vhod.read(b)
            }
            return true
        } finally { o.close(); vhod.close() }
    }

    /**
     * Pregled 99, V1: [vir] v [cilj] brez XMP, ki ga android.media.ExifInterface ne odstrani (Android 9 XMP ne pozna,
     * locen JPEG APP1 XMP prepise nespremenjen, PNG iTXt in WebP "XMP " ne bere) - XMP lahko nosi exif:GPSLatitude.
     * JPEG: APP1 XMP in razsirjeni XMP; PNG: iTXt/tEXt/zTXt "XML:com.adobe.xmp"; WebP: "XMP " in zastavica v VP8X.
     * false za neznano ali pokvarjeno obliko - klicatelj ne poslje nicesar (403).
     */
    fun odstraniXmp(vir: java.io.File, cilj: java.io.File, mime: String): Boolean = try {
        when (mime.lowercase()) {
            "image/jpeg" -> prepisi(vir, cilj) { i, o -> jpegBrezXmp(i, o) }
            "image/png" -> prepisi(vir, cilj) { i, o -> pngBrezXmp(i, o) }
            "image/webp" -> webpBrezXmp(vir, cilj)
            else -> false
        }
    } catch (_: Throwable) { false }

    private fun prepisi(vir: java.io.File, cilj: java.io.File, f: (java.io.InputStream, java.io.OutputStream) -> Boolean): Boolean {
        val i = vir.inputStream().buffered()
        try {
            val o = cilj.outputStream().buffered()
            try { return f(i, o) } finally { o.close() }
        } finally { i.close() }
    }

    private fun beri(i: java.io.InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var r = 0
        while (r < n) {
            val k = i.read(b, r, n - r)
            if (k < 0) throw java.io.EOFException()
            r += k
        }
        return b
    }

    private fun zacne(a: ByteArray, p: ByteArray): Boolean = a.size >= p.size && p.indices.all { a[it] == p[it] }

    private fun jpegBrezXmp(i: java.io.InputStream, o: java.io.OutputStream): Boolean {
        if (i.read() != 0xFF || i.read() != 0xD8) return false
        o.write(0xFF); o.write(0xD8)
        while (true) {
            var c = i.read()
            if (c != 0xFF) return false
            while (c == 0xFF) c = i.read()          // polnilni bajti
            if (c < 0) return false
            if (c == 0xDA || c == 0xD9) {           // SOS ali EOI: ostalo nespremenjeno
                o.write(0xFF); o.write(c); i.copyTo(o); o.flush()
                return true
            }
            if (c == 0x01 || c in 0xD0..0xD7) { o.write(0xFF); o.write(c) } else {
                val d = beri(i, 2)
                val dolzina = ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
                if (dolzina < 2) return false
                val telo = beri(i, dolzina - 2)
                val xmp = c == 0xE1 && XMP_JPEG.any { zacne(telo, it) }
                if (!xmp) { o.write(0xFF); o.write(c); o.write(d); o.write(telo) }
            }
        }
    }

    private val PNG_PODPIS = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun pngBrezXmp(i: java.io.InputStream, o: java.io.OutputStream): Boolean {
        if (!beri(i, 8).contentEquals(PNG_PODPIS)) return false
        o.write(PNG_PODPIS)
        while (true) {
            val glava = beri(i, 8)
            val n = java.nio.ByteBuffer.wrap(glava, 0, 4).int
            if (n < 0 || n > NAJVEC_KOPIJE) return false
            val vrsta = String(glava, 4, 4, Charsets.ISO_8859_1)
            val podatki = beri(i, n)
            val crc = beri(i, 4)
            val besedilo = vrsta == "iTXt" || vrsta == "tEXt" || vrsta == "zTXt"
            val konecKljuca = podatki.indexOf(0.toByte()).let { if (it < 0) podatki.size else it }
            val xmp = besedilo && String(podatki, 0, konecKljuca, Charsets.ISO_8859_1) == XMP_PNG
            if (!xmp) { o.write(glava); o.write(podatki); o.write(crc) }
            if (vrsta == "IEND") { o.flush(); return true }
        }
    }

    private fun webpBrezXmp(vir: java.io.File, cilj: java.io.File): Boolean {
        val b = vir.readBytes()   // najvec NAJVEC_KOPIJE (pripraviKopijo)
        if (b.size < 12 || String(b, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(b, 8, 4, Charsets.ISO_8859_1) != "WEBP") return false
        val izhod = java.io.ByteArrayOutputStream(b.size)
        var p = 12
        while (p < b.size) {
            if (p + 8 > b.size) return false
            val vrsta = String(b, p, 4, Charsets.ISO_8859_1)
            val n = java.nio.ByteBuffer.wrap(b, p + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            if (n < 0 || p + 8 + n > b.size) return false
            val konec = minOf(p + 8 + n + (n and 1), b.size)
            if (vrsta != "XMP ") {
                val kos = b.copyOfRange(p, konec)
                if (vrsta == "VP8X" && n >= 1) kos[8] = (kos[8].toInt() and 0x04.inv()).toByte()  // zastavica XMP
                izhod.write(kos)
            }
            p = konec
        }
        val telo = izhod.toByteArray()
        val riff = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        riff.put("RIFF".toByteArray(Charsets.ISO_8859_1)).putInt(4 + telo.size)
        val o = cilj.outputStream()
        try { o.write(riff.array()); o.write("WEBP".toByteArray(Charsets.ISO_8859_1)); o.write(telo) } finally { o.close() }
        return true
    }

    /** Zadnja preverba kopije (Pregled 99, V1): ali je v datoteki se kak paket XMP - neodvisno od ExifInterface. */
    fun vsebujeXmp(f: java.io.File): Boolean {
        val najdaljsi = ZNAKI_XMP.maxOf { it.size }
        val i = f.inputStream()
        try {
            val okno = ByteArray(64 * 1024 + najdaljsi)
            var ostanek = 0
            var n = i.read(okno, 0, 64 * 1024)
            while (n >= 0) {
                val vse = ostanek + n
                if (ZNAKI_XMP.any { z -> najdi(okno, vse, z) }) return true
                ostanek = minOf(najdaljsi - 1, vse)
                System.arraycopy(okno, vse - ostanek, okno, 0, ostanek)
                n = i.read(okno, ostanek, 64 * 1024)
            }
            return false
        } finally { i.close() }
    }

    private fun najdi(b: ByteArray, dolzina: Int, z: ByteArray): Boolean =
        (0..dolzina - z.size).any { s -> z.indices.all { k -> b[s + k] == z[k] } }

    /** Rob slicice iz `?s=`: 128..512, privzeto 256 (kot prej, ko parametra ni bilo). */
    fun rob(s: String?): Int = s?.trim()?.toIntOrNull()?.coerceIn(ROB_NAJMANJ, ROB_NAJVEC) ?: ROB_PRIVZETO

    /** Vrednost parametra [ime] iz poizvedbe naslova (`a=1&s=256`); null, ce ga ni. */
    fun parameter(poizvedba: String, ime: String): String? =
        poizvedba.split('&').firstOrNull { it.startsWith("$ime=") }?.substring(ime.length + 1)

    private val VARNA = Regex("[A-Za-z0-9:._-]{1,200}")

    /** ETag slicice: oznaka, cas spremembe in rob - spremenjena datoteka ali druga velikost je nova slicica. */
    fun etag(oznaka: String, spremenjeno: Long, rob: Int): String = "\"${varnaOznaka(oznaka)}-$spremenjeno-$rob\""

    /** Oznaka v ETag brez narekovajev in presledkov: varne pustimo (media:image:12), druge (poti) zgostimo. */
    fun varnaOznaka(o: String): String = if (VARNA.matches(o)) o else "h" + hex(MessageDigest.getInstance("SHA-256").digest(o.toByteArray(Charsets.UTF_8))).take(32)

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** If-None-Match (en ETag, seznam, sibki W/ ali *) ustreza [etag]. */
    fun ujemaSe(ifNoneMatch: String?, etag: String): Boolean =
        ifNoneMatch?.split(',')?.map { it.trim().removePrefix("W/") }?.any { it == etag || it == "*" } ?: false

    /** Kljuc predpomnilnika slicic: pot ali oznaka, cas spremembe in rob. */
    fun kljucSlicice(oznaka: String, spremenjeno: Long, rob: Int): String = "$oznaka|$spremenjeno|$rob"

    /** Majhen predpomnilnik slicic v pomnilniku, LRU po bajtih: ista slicica se ne dekodira dvakrat. */
    class PredpomnilnikSlicic(private val najvecBajtov: Int) {
        private val vnosi = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
        private var bajtov = 0

        @Synchronized fun dobi(kljuc: String): ByteArray? = vnosi[kljuc]

        @Synchronized fun shrani(kljuc: String, b: ByteArray) {
            if (b.size > najvecBajtov) return
            vnosi.remove(kljuc)?.let { bajtov -= it.size }
            vnosi[kljuc] = b
            bajtov += b.size
            val it = vnosi.entries.iterator()
            while (bajtov > najvecBajtov && it.hasNext()) {
                val e = it.next()
                bajtov -= e.value.size
                it.remove()
            }
        }

        @Synchronized fun bajtov(): Int = bajtov
        @Synchronized fun stevilo(): Int = vnosi.size
    }
}
