package si.safeer.tv.cast

/**
 * Dnevnik napak Safeer Linka brez skrivnosti. Namesto praznega `catch {}`: napaka, ki je uporabnik ne
 * vidi (pritisne, nic se ne zgodi), mora ostati v dnevniku - a nikoli z zetonom, vstopnico, skrivnostjo
 * iz QR kode ali podpisom. Zato gre vsako besedilo skozi [ocisti].
 *
 * Brez Androida (tece v JVM preizkusih in na telefonu); na Androidu System.err pride v logcat.
 */
object SafeerLog {
    private val SAF = Regex("saf_[A-Za-z0-9_\\-]{6,}")
    private val POLJE = Regex("(\"?(?:token|ticket|secret|skrivnost|nonce|signature|session_token|control_token|poll_secret|x-safeer-token)\"?\\s*[:=]\\s*\"?)[^\"&\\s,}]+",
        RegexOption.IGNORE_CASE)
    private val FRAGMENT = Regex("([#&?](?:s|j|t|i)=)[^&\\s\"]+")
    private val DOLGO = Regex("[A-Za-z0-9+/_\\-]{32,}={0,2}")
    /** Pregled U7: zeton v poti naslova dodatka (`/token/<t>/`, `/apikey/<k>/` ...), tudi krajsi od 32 znakov. */
    private val POT = Regex("(/(?:token|tokens|apikey|api_key|key|torbox|realdebrid|alldebrid|premiumize|debridlink)/)[^/\\s\"?#]+",
        RegexOption.IGNORE_CASE)
    /** Kljuc debrid v nastavitvah dodatka v poti (`.../torbox=<k>|.../manifest.json`). */
    private val KONFIG = Regex("((?:torbox|realdebrid|alldebrid|premiumize|debridlink|offcloud|putio|easydebrid)=)[^|/&?#\\s\"]+",
        RegexOption.IGNORE_CASE)

    /** Kam gre zapis; privzeto System.err (na Androidu logcat). Preizkusi ga lahko prestrezejo. */
    @Volatile
    var izhod: (String) -> Unit = { System.err.println(it) }

    fun ocisti(besedilo: String): String = besedilo
        .replace(SAF, "saf_***")
        .replace(POLJE) { it.groupValues[1] + "***" }
        .replace(FRAGMENT) { it.groupValues[1] + "***" }
        .replace(POT) { it.groupValues[1] + "***" }
        .replace(KONFIG) { it.groupValues[1] + "***" }
        .replace(DOLGO, "***")

    /** Napaka pri [kaj]; [e] je lahko null. Zapis nikoli ne vrze. */
    fun napaka(oznaka: String, kaj: String, e: Throwable? = null) {
        try {
            val opis = if (e == null) "" else ": " + e.javaClass.simpleName + (e.message?.let { " - $it" } ?: "")
            izhod(ocisti("SafeerLink/$oznaka: $kaj$opis").take(600))
        } catch (_: Throwable) {
            // Dnevnik ne sme podreti klicatelja.
        }
    }
}
