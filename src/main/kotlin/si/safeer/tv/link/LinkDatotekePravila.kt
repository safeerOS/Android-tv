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
        val m = mime.lowercase()
        if (m == "image/jpeg" || m == "image/webp" || m == "image/heic" || m == "image/heif" || m == "image/avif") return true
        return m.startsWith("image/") && sirina.toLong() * visina <= NAJVEC_TOCK_BREZ_VZORCENJA
    }

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
