package si.safeer.tv.os

/**
 * Nedelujoca vsebina z razlogom (zamisel iz Pinchflat-NGX, brez kode): kanal v zivo ali skladba, ki ne deluje, dobi
 * RAZLOG - uporabnik vidi, zakaj je ni, in jo lahko preveri sam. Ciste odlocitve, brez Androida (preizkus:
 * tests/NedelujocePravilaTest.kt). Naloga 108 (Live TV), 109 (glasba z YouTuba).
 */
object NedelujocePravila {
    /** Kode razlogov (hranijo se v nastavitvah; prazno = ne vemo - stari zapisi). */
    const val NI_ZNANO = ""
    /** Dodatek ali seznam ni dal predvajljivega toka (ali posnetka). */
    const val NI_TOKA = "ni_toka"
    /** Imenik streznika ne pozna (ime ne obstaja, omrezje dela). */
    const val STREZNIK = "streznik"
    /** Streznik ne odgovarja (zavrnjena povezava ali rok ob delujocem omrezju). */
    const val NEODZIVEN = "neodziven"
    /** Odgovor HTTP: »http:<koda>«. */
    const val HTTP = "http:"
    /** YouTube (naloga 109): odstranjen ali sicer nedosegljiv, zaseben, ni v tej drzavi, potrebna prijava ali starost, samo za clane. */
    const val NI_NA_VOLJO = "ni_na_voljo"
    const val ZASEBEN = "zaseben"
    const val DRZAVA = "drzava"
    const val PRIJAVA = "prijava"
    const val CLANI = "clani"

    fun http(koda: Int): String = HTTP + koda

    /** Koda HTTP iz razloga »http:<koda>«; 0 = ni razlog HTTP. */
    fun kodaHttp(razlog: String): Int = if (razlog.startsWith(HTTP)) razlog.removePrefix(HTTP).toIntOrNull() ?: 0 else 0

    private val RX_HTTP = Regex("HTTP (\\d{3})")

    /** Razlog iz opisa sonde ([SondaToka.preveriZRazlogom]): koda HTTP, imenik, zavrnjena povezava ali rok; sicer ne vemo. */
    fun izSonde(opis: String): String {
        RX_HTTP.find(opis)?.let { return http(it.groupValues[1].toInt()) }
        return when {
            opis.startsWith("imenik ne pozna") -> STREZNIK
            opis.startsWith("ConnectException") || opis.startsWith("NoRouteToHostException") || opis.startsWith("rok povezave") -> NEODZIVEN
            else -> NI_ZNANO
        }
    }

    /**
     * Ali je razlog trajen - samodejno ne poskusamo znova (Pinchflat-NGX: »namesto ponavljanja v nedogled«): posnetek
     * odstranjen, zaseben, ni v tej drzavi, potrebna prijava, samo za clane, HTTP 401/403/404/410. Zacasni (omrezje,
     * streznik, 5xx, ni toka - dodatek ima lahko izpad) se preverijo znova po obstojecih pravilih.
     */
    fun trajen(razlog: String): Boolean = razlog in setOf(NI_NA_VOLJO, ZASEBEN, DRZAVA, PRIJAVA, CLANI) || kodaHttp(razlog) in setOf(401, 403, 404, 410)

    /** Zapis razloga in imena (hramba v nastavitvah): »razlog\tIme«; ime brez tabulatorjev in novih vrstic. */
    fun vZapis(razlog: String, ime: String): String = razlog + "\t" + ime.replace('\t', ' ').replace('\n', ' ').trim()

    fun izZapisa(zapis: String?): Pair<String, String> {
        val z = zapis.orEmpty()
        return if ('\t' in z) z.substringBefore('\t') to z.substringAfter('\t') else z to ""
    }

    // ------------------------------------------------------------------ naloga 109: glasba z YouTuba

    /**
     * Razlog iz besedila napake predvajalnika YouTube (`.ytp-error`; strani beremo v anglescini, kot iskanje): zaseben,
     * ni v tej drzavi, potrebna prijava ali starost, samo za clane, odstranjen ali nedosegljiv; sicer ne vemo.
     */
    fun izNapakeYoutube(besedilo: String): String {
        val b = besedilo.lowercase()
        return when {
            b.isBlank() -> NI_ZNANO
            "private" in b -> ZASEBEN
            "your country" in b || "in your region" in b -> DRZAVA
            "members" in b || "join this channel" in b -> CLANI
            "confirm your age" in b || "age-restricted" in b || "sign in to confirm" in b || "sign in" in b -> PRIJAVA
            "unavailable" in b || "no longer available" in b || "removed" in b || "terminated" in b || "copyright" in b -> NI_NA_VOLJO
            else -> NI_ZNANO
        }
    }

    /**
     * Vrsta predvajanja: prvi element od [od] naprej, ki nima trajnega razloga ([razlogi]: null = ne vemo nic) - skladba, ki
     * trajno ne gre, se tiho preskoci, ne ustavi vrste. -1 = ni nobenega vec.
     */
    fun prviPredvajljiv(razlogi: List<String?>, od: Int): Int {
        if (od < 0) return -1
        for (i in od until razlogi.size) if (razlogi[i]?.let { trajen(it) } != true) return i
        return -1
    }

    private val RX_YT_ID = Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})")

    /** Id posnetka YouTube iz naslova (watch?v=, youtu.be/, shorts/, embed/); null = ni posnetek YouTube. */
    fun idYoutube(naslov: String): String? = RX_YT_ID.find(naslov)?.groupValues?.get(1)

    /** »Poisci drug posnetek«: zadetki iskanja (id, sekunde) brez posnetkov, ki ne delujejo. */
    fun brezIzlocenih(zadetki: List<Pair<String, Int>>, izloci: Set<String>): List<Pair<String, Int>> = zadetki.filterNot { it.first in izloci }
}
