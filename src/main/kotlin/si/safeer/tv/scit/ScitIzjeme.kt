package si.safeer.tv.scit

/**
 * Webtor rezerva (25. 10. 2026; pregled V1/N5): domene, ki jih Scit ne blokira zaradi seznamov OGLASOV, tudi ce so na njih
 * (seznami pogosto vsebujejo cele vrhnje domene, npr. `.buzz`). Seznami GROZENJ (zlonamerno, ribarjenje) imajo vedno
 * prednost - izjema jih ne umakne (ScitStoritev).
 *  - Stalne (s poddomenami): webtor.io (API, razresevanje toka) in cosmic-crab.buzz (Webtorjeva domena za pretakanje,
 *    `abra--<id>.api.cosmic-crab.buzz`).
 *  - Naucene: samo NATANKO gostitelj (brez poddomen), na katerega je preusmerila zahteva, ki se je zacela na
 *    `https://webtor.io/.../resolve/`, in sele po odgovoru 2xx ([si.safeer.tv.os.WebtorPravila.naucenGostitelj]); ni
 *    javna pripona in ni registrabilna domena sama (vsaj ena oznaka vec, skupaj vsaj 3), ni na nasih seznamih (grozenj in
 *    oglasov); velja [VELJA_MS] (preteceno ne velja ze ob preverbi), najvec [NAJVEC].
 * Zapis v datoteki `scit/izjeme.txt` (vrstica: domena TAB velja-do-ms) bere proces Scita. Ciste funkcije -
 * tests/ScitIzjemeTest.kt.
 */
object ScitIzjeme {
    /**
     * Stalne izjeme (pregled U10): natanko webtor.io in api.webtor.io ter gostitelji tokov `abra--<id>.api.cosmic-crab.buzz`
     * - ne vse poddomene (prej: *.webtor.io in *.cosmic-crab.buzz, tudi ce domena zamenja lastnika).
     */
    val STALNE = listOf("webtor.io", "api.webtor.io")
    private val STALNI_VZOREC = Regex("^abra--[a-z0-9-]{1,63}\\.api\\.cosmic-crab\\.buzz$")

    fun jeStalna(ime: String): Boolean { val d = normaliziraj(ime); return d in STALNE || STALNI_VZOREC.matches(d) }

    /**
     * Ali Scit domeno blokira (pregled U10): na seznamu in ni izjema; na seznamu grozenj vedno; dokler nabor grozenj ni
     * nalozen (pred prvo osvezitvijo seznamov po nadgradnji), izjeme ne veljajo - »ne vem« ni »da« (prej: stalne so).
     */
    fun blokirana(naSeznamu: Boolean, grozneNalozene: Boolean, naGroznih: Boolean, izjema: Boolean): Boolean =
        naSeznamu && (!grozneNalozene || naGroznih || !izjema)
    const val NAJVEC = 20
    const val VELJA_MS = 30L * 24 * 3600 * 1000

    /**
     * Javne pripone z vec oznakami (najmanjsi nabor iz Public Suffix List - Android nima javnega API-ja): drzavne
     * druge ravni in gostovanja, kjer vsak uporabnik dobi svojo poddomeno. Ena oznaka (com, buzz ...) je vedno javna pripona.
     */
    private val JAVNE_PRIPONE = setOf(
        "co.uk", "org.uk", "me.uk", "ac.uk", "gov.uk", "ltd.uk", "plc.uk", "net.uk",
        "com.au", "net.au", "org.au", "edu.au", "co.nz", "net.nz", "org.nz", "co.za", "org.za",
        "co.jp", "ne.jp", "or.jp", "ac.jp", "co.kr", "or.kr", "com.cn", "net.cn", "org.cn", "com.tw", "com.hk",
        "com.sg", "com.my", "co.id", "co.in", "net.in", "org.in", "co.il", "com.tr", "com.br", "net.br", "com.ar",
        "com.mx", "com.co", "com.pe", "com.ua", "com.pl", "net.pl", "org.pl", "com.ru", "com.es", "com.pt", "co.at",
        "github.io", "gitlab.io", "blogspot.com", "appspot.com", "herokuapp.com", "netlify.app", "vercel.app",
        "pages.dev", "workers.dev", "web.app", "firebaseapp.com", "cloudfront.net", "amazonaws.com", "s3.amazonaws.com",
        "azurewebsites.net", "cloudapp.net", "r2.dev", "duckdns.org", "ddns.net", "no-ip.org", "ngrok.io", "ngrok-free.app",
        "glitch.me", "onrender.com", "fly.dev", "trycloudflare.com", "repl.co", "deno.dev", "surge.sh", "now.sh"
    )

    fun normaliziraj(ime: String): String = ime.trim().lowercase().trim('.')

    /** Veljavna domena (ne naslov IP, brez praznih delov). */
    fun veljavna(d: String): Boolean = d.length in 4..253 && '.' in d && !d.contains("..") && !d.startsWith("-") &&
        d.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' } && d.any { it in 'a'..'z' }

    /** Registrabilna domena: najdaljsa znana javna pripona + ena oznaka; sicer zadnji dve oznaki. */
    fun registrabilna(d: String): String {
        val n = normaliziraj(d)
        val oznake = n.split('.')
        val pripona = JAVNE_PRIPONE.filter { n == it || n.endsWith(".$it") }.maxByOrNull { it.length }
        val dolzinaPripone = pripona?.split('.')?.size ?: 1
        return if (oznake.size <= dolzinaPripone) n else oznake.takeLast(dolzinaPripone + 1).joinToString(".")
    }

    /** Gostitelj je primeren za naucenje: veljaven, vsaj 3 oznake, ni javna pripona in ni registrabilna domena sama. */
    fun primerenZaUcenje(gostitelj: String): Boolean {
        val g = normaliziraj(gostitelj)
        if (!veljavna(g) || g.split('.').size < 3 || g in JAVNE_PRIPONE) return false
        return registrabilna(g) != g
    }

    /** Domena je med izjemami: stalna (s poddomenami) ali naucena NATANKO in se veljavna ob [zdaj]. */
    fun dovoljena(ime: String, naucene: Map<String, Long>, zdaj: Long): Boolean {
        val d = normaliziraj(ime)
        if (d.isEmpty()) return false
        if (jeStalna(d)) return true
        return (naucene[d] ?: 0L) > zdaj
    }

    /** Zapis iz datoteke -> veljavne naucene izjeme (domena -> velja do). */
    fun preberi(besedilo: String?, zdaj: Long): Map<String, Long> {
        val m = LinkedHashMap<String, Long>()
        for (v in besedilo.orEmpty().lineSequence()) {
            val d = v.split('\t')
            if (d.size != 2) continue
            val g = normaliziraj(d[0]); val t = d[1].trim().toLongOrNull() ?: continue
            if (t > zdaj && primerenZaUcenje(g)) m[g] = t
        }
        return m
    }

    /**
     * Doda [gostitelj] (velja [VELJA_MS]); null = ni spremembe: neprimeren ([primerenZaUcenje]), ze stalna izjema, na
     * nasih seznamih ([naSeznamu]: grozenj ali oglasov - take domene nikoli ne umaknemo) ali nedavno dodan.
     */
    fun dodaj(zdaj: Map<String, Long>, gostitelj: String, ura: Long, naSeznamu: (String) -> Boolean): Map<String, Long>? {
        val g = normaliziraj(gostitelj)
        if (!primerenZaUcenje(g) || jeStalna(g)) return null
        if (try { naSeznamu(g) } catch (_: Exception) { true }) return null
        val nov = LinkedHashMap(zdaj.filterValues { it > ura })
        val prej = nov[g]
        if (prej != null && prej - ura > VELJA_MS / 2) return null
        nov[g] = ura + VELJA_MS
        while (nov.size > NAJVEC) nov.remove(nov.minByOrNull { it.value }!!.key)
        return nov
    }

    fun zapis(m: Map<String, Long>): String = m.entries.joinToString("\n") { "${it.key}\t${it.value}" }
}
