package si.safeer.tv

/** Cista pravila mostu zacetne strani; brez Androida, da jih lahko preverimo v JVM. */
object SpletMostPravila {
    const val DOMACA = "file:///android_asset/splet/splet.html"
    const val NAJVEC_POIZVEDBE = 2048

    sealed class Ukaz {
        data class Navigacija(val url: String) : Ukaz()
        /** Vnos v iskalno polje zacetne strani, kot ga je uporabnik napisal: naslov ali iskanje odloci gostitelj. */
        data class Poizvedba(val besedilo: String) : Ukaz()
        object DodajBliznjico : Ukaz()
        /** Uporabnik je bliznjico odstranil z zacetne strani (tudi vgrajeno): nic ni vsiljeno. */
        data class OdstraniBliznjico(val url: String) : Ukaz()
        object ObnoviBliznjice : Ukaz()
    }

    /** Most je dovoljen samo dokumentu splet.html, ne drugim datotekam v assets/splet. */
    fun jeDovoljenIzvor(url: String?): Boolean {
        val cist = url?.substringBefore('#')?.substringBefore('?') ?: return false
        return cist == DOMACA
    }

    /** Kljuc bliznjice (enak kot v splet.js): naslov brez sheme, www. in koncne posevnice, z malimi crkami. */
    fun kljucBliznjice(url: String): String = url.trim().replace(Regex("^https?://(www\\.)?", RegexOption.IGNORE_CASE), "").trimEnd('/').lowercase()

    /** Sprejmemo samo dokumentirane oblike in samo spletne naslove http(s). */
    fun razcleni(json: String): Ukaz? {
        if (json.length !in 2..4096) return null
        return when (polje(json, "action")) {
            "navigate" -> polje(json, "url")?.takeIf(::jeSpletniNaslov)?.let(Ukaz::Navigacija)
            "query" -> polje(json, "text")?.trim()?.takeIf { it.isNotEmpty() && it.length <= NAJVEC_POIZVEDBE }?.let(Ukaz::Poizvedba)
            "open_sidebar" -> if (polje(json, "service") == "add_portal") Ukaz.DodajBliznjico else null
            "remove_portal" -> polje(json, "url")?.takeIf(::jeSpletniNaslov)?.let(Ukaz::OdstraniBliznjico)
            "reset_portals" -> Ukaz.ObnoviBliznjice
            else -> null
        }
    }

    fun izberiJezik(nastavitev: String?, jezikNaprave: String?): String {
        val podprti = setOf("sl", "en", "de", "es", "fr", "it")
        val izbran = nastavitev?.lowercase()?.take(2)
        if (izbran in podprti && nastavitev != "auto") return izbran!!
        val naprava = jezikNaprave?.lowercase()?.take(2)
        return naprava?.takeIf { it in podprti } ?: "en"
    }

    fun jeSpletniNaslov(url: String): Boolean = try {
        val u = java.net.URI(url.trim())
        (u.scheme.equals("https", true) || u.scheme.equals("http", true)) && !u.host.isNullOrBlank()
    } catch (_: Exception) { false }

    private fun polje(json: String, ime: String): String? {
        val zacetek = Regex("\\\"" + Regex.escape(ime) + "\\\"\\s*:\\s*\\\"").find(json)?.range?.last?.plus(1)
            ?: return null
        val ven = StringBuilder()
        var i = zacetek
        while (i < json.length) {
            val c = json[i++]
            if (c == '"') return ven.toString()
            if (c != '\\') { ven.append(c); continue }
            if (i >= json.length) return null
            when (val e = json[i++]) {
                '"', '\\', '/' -> ven.append(e)
                'b' -> ven.append('\b')
                'f' -> ven.append('\u000C')
                'n' -> ven.append('\n')
                'r' -> ven.append('\r')
                't' -> ven.append('\t')
                'u' -> {
                    if (i + 4 > json.length) return null
                    ven.append(json.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: return null); i += 4
                }
                else -> return null
            }
        }
        return null
    }
}
