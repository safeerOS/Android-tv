package si.safeer.tv

/**
 * Cista varnostna pravila brskalnika - brez Androida, da jih preverimo v JVM (tests/SpletVarnostPravilaTest.kt).
 *
 * Zunanji pregled kode 8. 10. 2026: stran je z naslovom intent: lahko odprla nase skrite dejavnosti (komponenta in
 * izbirnik sta ostala, zastavice tudi), rezervni naslov se je nalozil brez preverbe sheme, most SafeerBridge je zaupal
 * zastavici, ki se je postavila pred menjavo dokumenta (tudi za about:blank), stran je smela vstaviti content://, okno o
 * napacnem potrdilu pa je imelo fokus na »Odpri« in je nadaljevanje ponudilo tudi za tuje potrdilo javne strani.
 */
object SpletVarnostPravila {
    /** Domaca stran brskalnika; most SafeerBridge sme brati ploscice in premikati brskalnik samo njej. */
    const val DOMACA_BRSKALNIKA = "file:///android_asset/brave_home.html"

    /** Zunanjo aplikacijo sme stran odpreti samo tako dolgo po uporabnikovi tipki ali dotiku (ali s kretnjo). */
    const val ZAGON_PO_DEJANJU_MS = 3000L

    private const val VIEW = "android.intent.action.VIEW"

    /** Sheme, ki jih stran nikoli ne preda drugi aplikaciji: krajevne vsebine, skripte in notranje sheme. */
    private val PREPOVEDANE_SHEME = setOf("content", "file", "javascript", "data", "blob", "about", "intent", "android-app", "chrome")

    /**
     * Ali sme stran odpreti drugo aplikacijo s to namero. Klicatelj je namero ze ocistil: komponenta in izbirnik sta
     * odstranjena, zastavice so samo FLAG_ACTIVITY_NEW_TASK, kategorija BROWSABLE dodana. Tu odlocimo se: samo VIEW,
     * nikoli nas lasten paket, nikoli krajevne ali skriptne sheme.
     */
    fun dovoljenaNamera(akcija: String?, shemaPodatkov: String?, paket: String?, nasPaket: String): Boolean {
        if ((akcija ?: VIEW) != VIEW) return false
        if (paket != null && paket == nasPaket) return false
        val shema = shemaPodatkov?.lowercase() ?: return true
        return shema !in PREPOVEDANE_SHEME
    }

    /** browser_fallback_url iz namere: samo spletni naslov http(s) z gostiteljem (nikoli javascript:, file:, content:). */
    fun varenRezervniNaslov(url: String?): Boolean {
        if (url.isNullOrEmpty() || url != url.trim()) return false
        return try {
            val u = java.net.URI(url)
            (u.scheme.equals("https", ignoreCase = true) || u.scheme.equals("http", ignoreCase = true)) && !u.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    /** Oglas ob nalaganju strani ne sme sam odpreti druge aplikacije: potrebna je kretnja ali nedavno dejanje. */
    fun dovoljenZunanjiZagon(kretnja: Boolean, msOdDejanja: Long): Boolean =
        kretnja || msOdDejanja in 0..ZAGON_PO_DEJANJU_MS

    /** Natanko domaca stran brskalnika (poizvedba in sidro smeta biti); about:blank, prazen naslov in drugo ne. */
    fun jeDomacaBrskalnika(url: String?): Boolean {
        val cist = url?.substringBefore('#')?.substringBefore('?') ?: return false
        return cist == DOMACA_BRSKALNIKA
    }

    /**
     * content:// (krajevne datoteke in mediji naprave) sme biti samo glavni dokument, ki ga je odprl uporabnik (priponka
     * HTML iz druge aplikacije). Kot vir strani (slika, skripta, okvir) nikoli - stran bi sicer preverjala ali brala
     * krajevne medije.
     */
    fun dovoliContentZahtevo(url: String, glavniOkvir: Boolean): Boolean =
        glavniOkvir || !url.trim().startsWith("content:", ignoreCase = true)

    /**
     * Ali okno o neveljavnem potrdilu ponudi »Odpri vseeno« (SslError: NOTYETVALID 0, EXPIRED 1, IDMISMATCH 2,
     * UNTRUSTED 3, DATE_INVALID 4, INVALID 5). Tuj izdajatelj ali napacno ime (napad v sredini) samo za naprave v
     * domacem omrezju (usmerjevalnik, NAS s samopodpisanim potrdilom); napaka casa tudi drugod.
     */
    fun sslSmeNadaljevati(napaka: Int, gostitelj: String): Boolean = when (napaka) {
        0, 1, 4 -> true
        2, 3, 5 -> jeZasebniGostitelj(gostitelj)
        else -> false
    }

    /** Kaj storiti ob neveljavnem potrdilu (ni nas Hub, ni prava banka): preklici, nadaljuj ali vprasaj uporabnika. */
    enum class SslOdlocitev { PREKLICI, NADALJUJ, VPRASAJ }

    /**
     * Vrstni red je pomemben (zunanji pregled 8. 10. 2026, F1): trda ovira - tuj izdajatelj ali napacno ime pri
     * javnem naslovu - velja PRED zapomnjeno izjemo te seje. Zapomnjena izjema (dovoljeniSsl) je vezana le na
     * gostitelja:vrata, ne na vrsto napake; brez tega vrstnega reda bi uporabnikov klik »odpri« ob pretecenem
     * potrdilu kasneje tiho spustil skozi podtaknjeno nezaupano potrdilo za istega gostitelja.
     */
    fun sslOdlocitev(napaka: Int, gostitelj: String, zeDovoljen: Boolean): SslOdlocitev = when {
        !sslSmeNadaljevati(napaka, gostitelj) -> SslOdlocitev.PREKLICI
        zeDovoljen -> SslOdlocitev.NADALJUJ
        else -> SslOdlocitev.VPRASAJ
    }

    /** Naprava v domacem omrezju: zasebni naslov IPv4/IPv6 (oktete razclenimo) ali krajevno ime (.local, ime brez pike). */
    fun jeZasebniGostitelj(gostitelj: String): Boolean {
        var h = gostitelj.trim().lowercase()
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1)
        h = h.substringBefore('%')
        if (h.isEmpty()) return false
        ipv4(h)?.let { return zasebniIpv4(it) }
        if (':' in h) return zasebniIpv6(h)
        if (h == "localhost") return true
        if (!IME.matches(h)) return false
        if ('.' !in h) return !h.all { it.isDigit() }
        return KRAJEVNE_PRIPONE.any { h.endsWith(it) }
    }

    /** Kljuc odlocitve o potrdilu: gostitelj in vrata (ne samo ime). Prazen niz, ce naslov ni veljaven. */
    fun sslKljuc(url: String?): String = try {
        val u = java.net.URI(url ?: "")
        val shema = u.scheme?.lowercase()
        val host = u.host?.lowercase()
        val vrata = when {
            u.port >= 0 -> u.port
            shema == "https" -> 443
            shema == "http" -> 80
            else -> -1
        }
        if (host.isNullOrEmpty() || vrata < 0) "" else "$host:$vrata"
    } catch (_: Exception) {
        ""
    }

    private val IME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$")
    private val KRAJEVNE_PRIPONE = listOf(".local", ".lan", ".home.arpa", ".internal")

    /** Stroga oblika a.b.c.d (desetisko, brez vodilnih nicel); sicer null. */
    private fun ipv4(h: String): IntArray? {
        val deli = h.split('.')
        if (deli.size != 4) return null
        val o = IntArray(4)
        for ((i, d) in deli.withIndex()) {
            if (d.isEmpty() || d.length > 3 || !d.all { it in '0'..'9' } || (d.length > 1 && d[0] == '0')) return null
            o[i] = d.toInt()
            if (o[i] > 255) return null
        }
        return o
    }

    private fun zasebniIpv4(o: IntArray): Boolean =
        o[0] == 10 || o[0] == 127 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168) || (o[0] == 169 && o[1] == 254)

    private fun zasebniIpv6(h: String): Boolean {
        // Samo zapis naslova (brez imen): getByName tak niz razcleni brez poizvedbe DNS.
        if (!Regex("^[0-9a-f:.]+$").matches(h)) return false
        val a = try { java.net.InetAddress.getByName(h) } catch (_: Exception) { return false }
        if (a is java.net.Inet4Address) return zasebniIpv4(a.address.map { it.toInt() and 0xff }.toIntArray())
        val b = a.address
        return a.isLoopbackAddress || a.isLinkLocalAddress || (b[0].toInt() and 0xfe) == 0xfc
    }
}
