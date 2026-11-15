package si.safeer.tv.scit

/** Ciste funkcije (preizkus v JVM). */
object ScitPoMeriPravila {
    const val NAJVEC = 20
    const val NAJVEC_BAJTOV = 24 * 1024 * 1024

    /**
     * Imena, ki nikoli niso javna: localhost in pripone .localhost, .local (mDNS), .internal, .localdomain, .home.arpa, .lan, .corp, .home,
     * .intranet, .private (omrezja podjetij in domov), .box (fritz.box), .test (RFC 6761) ter javna imena, ki jih DNS razresi na naslov,
     * vpisan v ime (nip.io, sslip.io, xip.io, traefik.me, localtest.me, lvh.me). Prenos iz Linuxa (core/os_scit.py IME_KRAJEVNO, veja
     * zdruzitev-kandidat-1411, 15. 11. 2026: »isto kot TV«). SEZNAM NI VARNOSTNA MEJA, samo hitra zavrnitev ob vpisu: nepopoln je po
     * zasnovi (vsaka domena z A-zapisom na zasebni naslov ga obide). Na TV ob prenosu naslova ne preverimo (HttpURLConnection ga
     * razresi sam), zato je to edina plast pred prenosom - glej [HttpsListFetcher] in porocilo.
     */
    val IME_KRAJEVNO = listOf(".localhost", ".local", ".internal", ".localdomain", ".home.arpa", ".lan", ".corp", ".home", ".intranet", ".private",
        ".box", ".test", ".nip.io", ".sslip.io", ".xip.io", ".traefik.me", ".localtest.me", ".lvh.me")

    /**
     * Razlog, zakaj naslov ni sprejemljiv, ali null. Samo HTTPS, brez uporabnika/gesla v naslovu, z imenom gostitelja. Isti nabor pravil kot
     * Linux (core/os_scit.napaka_naslova): { } zavrnjena (»{KLJUC}« je samo za vgrajeni vir), gostitelj STROGO ime ali IPv4 ali [IPv6]
     * z najvec enim :vrata, brez kodiranj (%31), imen .local/.internal ..., desetiskih/sestnajstiskih/osmiskih zapisov IPv4 (127.1,
     * 0x7f.1, 0127.0.0.1) in nejavnih naslovov (10.x, 127.x, 169.254.x, 192.168.x, ::1 ...). Dodatno proti Linuxu (TV nima druge
     * preverbe): prazne oznake (a..b), oznaka daljsa od 63 znakov, ime daljse od 253 znakov, oznaka z vezajem na robu in eno samo ime
     * brez pike po odstranitvi koncne pike (»intranet.«).
     */
    fun napaka(url: String): String? {
        val u = url.trim()
        if (u.length !in 12..2000) return "dolzina"
        if (!u.startsWith("https://")) return "https"
        if (u.any { it.isWhitespace() || it.code < 0x21 || it.code > 0x7E || it == '{' || it == '}' }) return "znaki"
        val gostitelj = u.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in gostitelj || gostitelj.isEmpty() || '.' !in gostitelj) return "gostitelj"
        return if (gostiteljSprejemljiv(gostitelj.lowercase())) null else "gostitelj"
    }

    /** Strogi gostitelj: »ime« ali »IPv4« z neobveznimi :vrata, ali »[IPv6]« z neobveznimi :vrata. Sicer false. */
    private fun gostiteljSprejemljiv(g: String): Boolean {
        val v6 = g.startsWith("[")
        val ime: String
        val vrata: String
        if (v6) {
            val k = g.indexOf(']')
            if (k < 2) return false
            ime = g.substring(1, k)
            val rep = g.substring(k + 1)
            if (rep.isNotEmpty() && !rep.startsWith(":")) return false
            vrata = rep.removePrefix(":")
            if (rep.isNotEmpty() && vrata.isEmpty()) return false
        } else {
            val k = g.indexOf(':')
            ime = if (k < 0) g else g.substring(0, k)
            vrata = if (k < 0) "" else g.substring(k + 1)
            if (k >= 0 && vrata.isEmpty()) return false
        }
        if (vrata.isNotEmpty() && (vrata.length > 5 || !vrata.all { it in '0'..'9' } || vrata.toInt() > 65535)) return false
        return if (v6) ime.isNotEmpty() && ime.all { it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.' } && naslovJeJavenIp(ime)
        else imeSprejemljivo(ime)
    }

    private fun imeSprejemljivo(surovo: String): Boolean {
        if (surovo.isEmpty() || !(surovo[0] in 'a'..'z' || surovo[0] in '0'..'9')) return false
        if (!surovo.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }) return false
        val ime = surovo.trimEnd('.')               // »10.0.0.1.« in »localhost.« razume tudi posrednik in getaddrinfo
        if (ime.isEmpty() || '.' !in ime || ime.length > 253) return false
        if (ime == "localhost" || IME_KRAJEVNO.any { ime.endsWith(it) }) return false
        val oznake = ime.split('.')
        if (oznake.any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) return false
        // Same stevilke ali sestnajstiski zapis: inet_aton ga razume kot IPv4 (127.1, 0x7f.1); osmiski zapis (0127.0.0.1) razlaga
        // vsak odjemalec drugace, zato ga zavrnemo; ostalo preveri javnost naslova.
        if (oznake.all { stevilskaOznaka(it) }) {
            if (oznake.any { it.length > 1 && it[0] == '0' && !it.startsWith("0x") }) return false
            val v4 = inetAton(oznake) ?: return false
            return jeJavenV4(v4)
        }
        return true                                  // ime: razresitev in zasebni naslovi bi se preverili ob povezavi
    }

    private fun stevilskaOznaka(o: String): Boolean = o.all { it in '0'..'9' } || (o.startsWith("0x") && o.length > 2 && o.drop(2).all { it in '0'..'9' || it in 'a'..'f' })

    /** inet_aton: 1-4 delov (desetiski ali 0x), zadnji del zapolni preostale bajte. null = neveljavno. */
    private fun inetAton(oznake: List<String>): Long? {
        if (oznake.size > 4) return null
        val vrednosti = oznake.map { o ->
            if (o.startsWith("0x")) o.drop(2).toLongOrNull(16) else o.toLongOrNull()
        }
        if (vrednosti.any { it == null || it < 0 }) return null
        val v = vrednosti.map { it!! }
        for (i in 0 until v.size - 1) if (v[i] > 255) return null
        val zadnjiNajvec = (1L shl (8 * (5 - v.size))) - 1
        if (v.last() > zadnjiNajvec) return null
        var izid = 0L
        for (i in 0 until v.size - 1) izid = izid or (v[i] shl (24 - 8 * i))
        return izid or v.last()
    }

    private fun v4(a: Int, b: Int, c: Int, d: Int): Long = (a.toLong() shl 24) or (b.toLong() shl 16) or (c.toLong() shl 8) or d.toLong()

    /** Nejavna omrezja IPv4 (Linux _NEJAVNA_V4): naslov + dolzina predpone. */
    private val NEJAVNA_V4 = listOf(
        v4(0, 0, 0, 0) to 8, v4(10, 0, 0, 0) to 8, v4(100, 64, 0, 0) to 10, v4(127, 0, 0, 0) to 8, v4(169, 254, 0, 0) to 16,
        v4(172, 16, 0, 0) to 12, v4(192, 0, 0, 0) to 24, v4(192, 0, 2, 0) to 24, v4(192, 88, 99, 0) to 24, v4(192, 168, 0, 0) to 16,
        v4(198, 18, 0, 0) to 15, v4(198, 51, 100, 0) to 24, v4(203, 0, 113, 0) to 24, v4(224, 0, 0, 0) to 4, v4(240, 0, 0, 0) to 4,
    )

    private fun jeJavenV4(naslov: Long): Boolean = NEJAVNA_V4.none { (osnova, predpona) -> (naslov ushr (32 - predpona)) == (osnova ushr (32 - predpona)) }

    /** Nejavna omrezja IPv6 (Linux _NEJAVNA_V6); ::ffff:0:0/96 preslikani IPv4, 64:ff9b::/96 in 2002::/16 odlocajo z vgrajenim IPv4. */
    private val NEJAVNA_V6: List<Pair<ByteArray, Int>> by lazy {
        listOf("::" to 96, "::ffff:0:0" to 96, "64:ff9b:1::" to 48, "100::" to 64, "2001::" to 23, "2001:db8::" to 32, "3fff::" to 20,
            "fc00::" to 7, "fe80::" to 10, "fec0::" to 10, "ff00::" to 8, "::ffff:0:0:0" to 96, "5f00::" to 16, "100:0:0:1::" to 64)
            .map { (n, p) -> requireNotNull(razclenV6(n)) { "napacno omrezje $n" } to p }
    }

    private fun predponaV6(a: ByteArray, osnova: ByteArray, bitov: Int): Boolean {
        for (i in 0 until bitov) {
            val maska = 0x80 shr (i % 8)
            if ((a[i / 8].toInt() and maska) != (osnova[i / 8].toInt() and maska)) return false
        }
        return true
    }

    private fun v4IzV6(a: ByteArray, od: Int): Long =
        (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (a[od + i].toLong() and 0xFF) }

    /** Dobesedni naslov IPv4 (a.b.c.d, desetisko, brez vodilnih nicel) ali IPv6 (brez %cone) iz kodiranega besedila. */
    private fun razclenV6(s: String): ByteArray? {
        val dvojna = s.indexOf("::")
        if (dvojna >= 0 && s.indexOf("::", dvojna + 1) >= 0) return null
        fun skupine(del: String, smeV4: Boolean): List<Int>? {
            if (del.isEmpty()) return emptyList()
            val deli = del.split(':')
            val izid = ArrayList<Int>()
            for ((i, d) in deli.withIndex()) {
                if (d.isEmpty()) return null
                if ('.' in d) {
                    if (!smeV4 || i != deli.lastIndex) return null
                    val v = razclenV4Dobesedno(d) ?: return null
                    izid += (v ushr 16).toInt(); izid += (v and 0xFFFF).toInt()
                } else {
                    if (d.length > 4 || !d.all { it in '0'..'9' || it in 'a'..'f' }) return null
                    izid += d.toInt(16)
                }
            }
            return izid
        }
        val skupaj: List<Int> = if (dvojna < 0) {
            val l = skupine(s, true) ?: return null
            if (l.size != 8) return null
            l
        } else {
            val l = skupine(s.substring(0, dvojna), false) ?: return null
            val r = skupine(s.substring(dvojna + 2), true) ?: return null
            if (l.size + r.size > 7) return null
            l + List(8 - l.size - r.size) { 0 } + r
        }
        val bajti = ByteArray(16)
        for (i in 0 until 8) { bajti[2 * i] = (skupaj[i] shr 8).toByte(); bajti[2 * i + 1] = skupaj[i].toByte() }
        return bajti
    }

    private fun razclenV4Dobesedno(s: String): Long? {
        val deli = s.split('.')
        if (deli.size != 4) return null
        var izid = 0L
        for (d in deli) {
            if (d.isEmpty() || d.length > 3 || !d.all { it in '0'..'9' } || (d.length > 1 && d[0] == '0')) return null
            val n = d.toInt()
            if (n > 255) return null
            izid = (izid shl 8) or n.toLong()
        }
        return izid
    }

    /** Ali je dobesedni naslov (IPv4 ali IPv6) javni naslov interneta (Linux _naslov_je_javen). Neveljavno besedilo: false. */
    fun naslovJeJavenIp(besedilo: String): Boolean {
        val s = besedilo.lowercase()
        if (':' !in s) return razclenV4Dobesedno(s)?.let { jeJavenV4(it) } ?: false
        val a = razclenV6(s) ?: return false
        if (predponaV6(a, requireNotNull(razclenV6("::ffff:0:0")), 96)) return jeJavenV4(v4IzV6(a, 12))       // ::ffff:a.b.c.d
        if (predponaV6(a, requireNotNull(razclenV6("64:ff9b::")), 96)) return jeJavenV4(v4IzV6(a, 12))        // NAT64
        if (predponaV6(a, requireNotNull(razclenV6("2002::")), 16)) return jeJavenV4(v4IzV6(a, 2))            // 6to4
        return NEJAVNA_V6.none { (osnova, predpona) -> predponaV6(a, osnova, predpona) }
    }

    /** Stalna oznaka filtra (ime datoteke agenta): »pm-« + 12 znakov zgoscenke naslova. */
    fun id(url: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256").digest(url.trim().toByteArray(Charsets.UTF_8))
        return "pm-" + md.take(6).joinToString("") { "%02x".format(it) }
    }

    /** Kratko ime za zaslon: gostitelj in ime datoteke (npr. »raw.githubusercontent.com … hosts«). */
    fun ime(url: String): String {
        val brez = url.trim().removePrefix("https://")
        val gostitelj = brez.substringBefore('/')
        val datoteka = brez.substringBefore('?').substringAfterLast('/', "")
        return if (datoteka.isEmpty() || datoteka == gostitelj) gostitelj else "$gostitelj … $datoteka"
    }
}
