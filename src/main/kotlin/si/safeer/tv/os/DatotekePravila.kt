package si.safeer.tv.os

/**
 * Naloga 107 (lastnik: »Safeer Files - upravljanje lastnih datotek«): ciste odlocitve za preimenovanje, premik in brisanje
 * datotek te naprave (MediaStore, [KrajevneDatoteke]). Brez Androida (preizkus: tests/DatotekePravilaTest.kt).
 * Nikoli sistemske mape in podatki drugih aplikacij (Android/data, Android/obb, Android/media drugih paketov).
 */
object DatotekePravila {
    /** Razlogi, zakaj ime ni veljavno ([napakaImena]). */
    const val PRAZNO = "prazno"
    const val ZNAKI = "znaki"
    const val PIKA = "pika"
    const val DOLGO = "dolgo"

    private const val NEDOVOLJENI = "/\\\u0000:*?\"<>|"

    /**
     * Novo ime iz polja + stara koncnica (polje kaze ime brez nje, okno pove »Koncnica ... ostane«). Koncnica se doda tudi,
     * ce ima novo ime piko (»dopust 2026.10« -> »dopust 2026.10.jpg«; pregled 25. 10. 2026 D9: prej se je izgubila);
     * ne doda se le, ce se ime ze konca z njo (ne glede na velike crke).
     */
    fun zdruziIme(vneseno: String, koncnica: String): String {
        val ime = vneseno.trim().trimEnd('.').trim()
        if (ime.isEmpty()) return ""
        if (koncnica.isEmpty() || ime.endsWith(koncnica, ignoreCase = true)) return ime
        // Pregled 7: uporabnik je sam vpisal drugo znano koncnico (»film.mp4« za »film.mkv«) - obdrzimo njegovo, ne
        // »film.mp4.mkv«. Neznana (»dopust 2026.10«) ali brez koncnice: stara koncnica se doda.
        val vpisana = ime.substringAfterLast('.', "").lowercase()
        return if (ime.contains('.') && vpisana in ZNANE_KONCNICE) ime else ime + koncnica
    }

    /** Pregled 7: znane koncnice predstavnostnih datotek in podnapisov (brez pike, male crke). */
    val ZNANE_KONCNICE = setOf(
        "mp4", "mkv", "avi", "mov", "webm", "m4v", "ts", "m2ts", "mts", "mpg", "mpeg", "wmv", "flv", "3gp", "ogv",
        "mp3", "flac", "wav", "ogg", "oga", "opus", "m4a", "aac", "wma", "alac", "aiff",
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff", "avif",
        "srt", "ass", "ssa", "vtt", "sub")

    /**
     * Ali je [ime] (z koncnico) veljavno ime datoteke na Androidu (FAT/exFAT na USB in ext4/f2fs): null = veljavno, sicer
     * razlog. Ne sme biti prazno, imeti /, \, :, *, ?, ", <, >, | ali kontrolnih znakov, se zaceti s piko (skrita datoteka) in
     * biti daljse od 255 bajtov UTF-8.
     */
    fun napakaImena(ime: String): String? {
        val i = ime.trim()
        return when {
            i.isEmpty() -> PRAZNO
            i.any { it in NEDOVOLJENI || it.code < 32 || it.code == 127 } -> ZNAKI
            i.startsWith(".") -> PIKA
            i.toByteArray(Charsets.UTF_8).size > 255 -> DOLGO
            else -> null
        }
    }

    /** Mape, v katere sme MediaStore premakniti datoteko te vrste ("video", "audio", "image"); RELATIVE_PATH s koncnim »/«. */
    private val CILJI = mapOf(
        "video" to listOf("Movies/", "DCIM/", "Pictures/"),
        "audio" to listOf("Music/", "Podcasts/", "Ringtones/", "Alarms/", "Notifications/"),
        "image" to listOf("Pictures/", "DCIM/"),
    )

    /** Mapa (RELATIVE_PATH) v obliki »Mapa/Podmapa/«: brez vodilnega »/«, s koncnim. */
    fun normaliziraj(pot: String): String {
        val p = pot.trim().replace('\\', '/').trim('/')
        return if (p.isEmpty()) "" else "$p/"
    }

    private val RX_KOREN_NOSILCA = Regex("^/(?:storage/emulated/\\d+|storage/(?!emulated/)[^/]+|mnt/media_rw/[^/]+|sdcard)/(.*)/[^/]*$")

    /**
     * Mapa datoteke (RELATIVE_PATH) iz polne poti (stolpec DATA, Android 9 in starejsi): »/storage/emulated/0/Movies/a.mp4« ->
     * »Movies/«; datoteka v korenu nosilca ali neznana pot -> "" (ne vemo - [smeUrejati] je takrat false).
     */
    fun relativnaIzPoti(pot: String): String = RX_KOREN_NOSILCA.find(pot.trim())?.groupValues?.get(1)?.let { normaliziraj(it) }.orEmpty()

    /**
     * Ali sme Safeer datoteko v mapi [pot] urejati: ne v sistemskih mapah (Android/ - data, obb, media drugih aplikacij)
     * in ne brez znane mape (ne vemo, kje je).
     */
    fun smeUrejati(pot: String): Boolean {
        val p = normaliziraj(pot)
        return p.isNotEmpty() && !p.startsWith("Android/", ignoreCase = true)
    }

    /**
     * Cilji premika za datoteko vrste [vrsta] v mapi [trenutna]: standardne mape te vrste, brez trenutne zgornje mape.
     * Prazno = premik ni mogoc (neznana vrsta ali sistemska mapa).
     */
    fun ciljiPremika(vrsta: String, trenutna: String): List<String> {
        if (!smeUrejati(trenutna)) return emptyList()
        val zgornja = normaliziraj(trenutna).substringBefore('/') + "/"
        return CILJI[vrsta].orEmpty().filterNot { it.equals(zgornja, ignoreCase = true) && normaliziraj(trenutna).count { c -> c == '/' } == 1 }
    }
}
