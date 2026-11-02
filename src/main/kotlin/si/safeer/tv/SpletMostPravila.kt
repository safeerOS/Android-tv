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

    /** Vrednost niza na vrhnji ravni sporocila; null, ce je ni ali ni niz. */
    private fun polje(json: String, ime: String): String? = polja(json)?.get(ime) as? String

    /**
     * Vrhnja polja predmeta JSON (niz kot String, drugo kot Unit). Null, ce sporocilo ni natanko en veljaven predmet ali
     * ima isti kljuc dvakrat. Zunanji pregled 2. 11. 2026: prej je iskanje »"kljuc":« vzelo tudi kljuc iz gnezdenega
     * predmeta ali drugo pojavitev; org.json v preizkusih JVM ni na voljo, zato majhen, a popoln razclenjevalnik.
     */
    fun polja(json: String): Map<String, Any>? = try {
        val b = Bralnik(json)
        val izid = b.predmet(0)
        b.presledki()
        if (b.i != json.length) null else izid
    } catch (_: IllegalArgumentException) {
        null
    }

    private class Bralnik(val s: String) {
        var i = 0

        fun presledki() { while (i < s.length && s[i] in " \t\r\n") i++ }

        private fun znak(c: Char) {
            presledki()
            require(i < s.length && s[i] == c)
            i++
        }

        fun predmet(globina: Int): Map<String, Any> {
            require(globina < 32)
            znak('{')
            val izid = LinkedHashMap<String, Any>()
            presledki()
            if (i < s.length && s[i] == '}') { i++; return izid }
            while (true) {
                presledki()
                val kljuc = niz()
                znak(':')
                val v = vrednost(globina)
                require(izid.put(kljuc, v) == null)
                presledki()
                require(i < s.length)
                if (s[i] == ',') { i++; continue }
                require(s[i] == '}'); i++
                return izid
            }
        }

        private fun seznam(globina: Int) {
            require(globina < 32)
            znak('[')
            presledki()
            if (i < s.length && s[i] == ']') { i++; return }
            while (true) {
                vrednost(globina)
                presledki()
                require(i < s.length)
                if (s[i] == ',') { i++; continue }
                require(s[i] == ']'); i++
                return
            }
        }

        private fun vrednost(globina: Int): Any {
            presledki()
            require(i < s.length)
            return when (s[i]) {
                '"' -> niz()
                '{' -> { predmet(globina + 1); Unit }
                '[' -> { seznam(globina + 1); Unit }
                else -> { beseda(); Unit }
            }
        }

        /** Stevilo, true, false ali null. */
        private fun beseda() {
            val z = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] in "+-.")) i++
            val w = s.substring(z, i)
            require(w == "true" || w == "false" || w == "null" || Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(w))
        }

        fun niz(): String {
            presledki()
            require(i < s.length && s[i] == '"')
            i++
            val ven = StringBuilder()
            while (true) {
                require(i < s.length)
                val c = s[i++]
                if (c == '"') return ven.toString()
                require(c >= ' ')
                if (c != '\\') { ven.append(c); continue }
                require(i < s.length)
                when (val e = s[i++]) {
                    '"', '\\', '/' -> ven.append(e)
                    'b' -> ven.append('\b')
                    'f' -> ven.append('\u000C')
                    'n' -> ven.append('\n')
                    'r' -> ven.append('\r')
                    't' -> ven.append('\t')
                    'u' -> {
                        require(i + 4 <= s.length)
                        val k = s.substring(i, i + 4).toIntOrNull(16)
                        require(k != null)
                        ven.append(k.toChar()); i += 4
                    }
                    else -> throw IllegalArgumentException()
                }
            }
        }
    }
}
