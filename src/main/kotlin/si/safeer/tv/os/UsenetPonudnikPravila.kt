package si.safeer.tv.os

import java.net.IDN
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLParameters

/**
 * Cista pravila ponudnika Usenet (brez Android odvisnosti), da jih preizkus tece na JVM in da so ISTA kot Linux
 * referenca core/usenet_ponudnik.py (+ usenet_nntp.py za zapah, ponudnik() in varna_poverilnica).
 *
 * Zakaj tu: shramba (UsenetPonudnik.kt) in okno samo klicejo ta pravila; ce bi jih podvojili v Activity, bi se
 * Android in Linux razsla in isti zapis iz ozjega kroga bi na eni napravi veljal, na drugi ne.
 *
 * Geslo nikoli ni v sporocilu napake, toString ali dnevniku: napake nosijo samo razlog (kot NapakaPonudnika v
 * referenci), Geslo.toString je "<skrito>".
 */
object UsenetPonudnikPravila {
    const val VRATA = 563
    const val PRIVZETO_POVEZAV = 8
    // Bazen (UsenetNntp) sam ne odpre vec kot 50; vnos dovoli 100 kot referenca, dejanska meja je min(vnos, 50).
    const val NAJVEC_POVEZAV_NNTP = 50
    const val NAJVEC_POVEZAV_RACUNA = 100
    const val NAJVEC_IME = 80
    // Najvec ena preverba na 10 s na racun (lastnik 28. 10. 2026; prej 1/min iz naloge 74, ne od lastnika): prepreci dvojni
    // klik in tiscanje gumba; zavrnjenega gesla tako ali tako ne poslje znova zapah racuna.
    const val PREVERBA_NA_MS = 10_000L
    const val NAJVEC_POVERILNICA_BAJTOV = 400
    const val NAJVEC_NASTAVITEV_BAJTOV = 64 * 1024
    const val VERZIJA = 1
    const val PREDPONA_KLJUCA = "usenet-ponudnik-"
    // Kot usenet_nntp: zapah je drag (PBKDF2), da odtis gesla na disku ne izda gesla.
    const val ZAPAH_PONOVITEV = 100_000
    const val NAJVEC_ZAPAHOV = 32
    const val GLAVA_ZAPAHA = "safeer-usenet-zapah 1"

    // Izidi preverbe (enaki nizi kot v referenci, ker gredo v zapis "preverba" in v primerjavo).
    const val VELJA = "VELJA"
    const val NE_VELJA = "NE_VELJA"
    const val ZAKLENJEN = "ZAKLENJEN"
    const val NI_ODGOVORA = "NI_ODGOVORA"
    const val PREVEC_POVEZAV = "PREVEC_POVEZAV"
    const val PREPOGOSTO = "PREPOGOSTO"
    const val NEPREVERJEN = "NEPREVERJEN"

    // Razlogi NapakaPonudnika (brez vrednosti).
    const val NI_GESLA = "ni_gesla"
    const val BREZ_TLS = "brez_tls_ni_podprt"
    const val RACUN_ZAKLENJEN = "racun_zaklenjen"
    // Dodatna razloga: v referenci sta RuntimeError oz. ValueError iz varen_kontekst; tu ista tocka, isti ucinek
    // (brez omrezja), a v eni vrsti napake, da jo okno lahko prevede.
    const val GLAVNA_NIT = "omrezje_na_glavni_niti"
    const val NEVAREN_TLS = "tls_brez_preverbe_potrdila"

    // Izidi usenet_nntp (isti nizi kot Python, da UsenetNntp.kt lahko poda svoje izide brez prevajanja).
    const val NNTP_OK = "ok"
    const val NNTP_PRIJAVA = "prijava"
    const val NNTP_ZASEDEN = "zaseden"
    const val NNTP_NAPAKA_STREZNIKA = "napaka_streznika"
    const val NNTP_NAPAKA_PROTOKOLA = "napaka_protokola"
    const val NNTP_ZAPRTO = "zaprto"
    const val NNTP_DOSTOP = "dostop"
    const val NNTP_OMREZJE = "omrezje"
    const val NNTP_ROK = "rok"
    const val NNTP_POTRDILO = "potrdilo"
    const val FAZA_POVEZAVA = "povezava"
    const val FAZA_PRIJAVA = "prijava"
    const val FAZA_UKAZ = "ukaz"
    const val ZMANJSAJ_POCAKAJ = "zmanjsaj_pocakaj"
    const val NEZNANA_PRIJAVA = "neznana_prijava"
    const val USTAVI_RACUN = "ustavi_racun"

    // Sporocila napak validacije - dobesedno kot ValueError v referenci (primerjava s Pythonom jih primerja).
    const val N_GOSTITELJ = "usenet ponudnik: neveljaven gostitelj"
    const val N_VRATA = "usenet ponudnik: neveljavna vrata"
    const val N_UPORABNIK = "usenet ponudnik: neveljavno uporabnisko ime"
    const val N_POVEZAV = "usenet ponudnik: najvec_povezav mora biti 1-100"
    const val N_IME = "usenet ponudnik: neveljavno ime"
    const val N_TLS_BOOL = "usenet ponudnik: tls mora biti bool"
    const val N_BREZ_TLS = "usenet ponudnik: brez TLS samo z nevarno_brez_tls=True"
    const val N_BREZ_TLS_DOMA = "usenet ponudnik: brez TLS samo do naslova IP v domacem omrezju"
    const val N_ZAPIS = "usenet ponudnik: neveljaven zapis"
    const val N_GESLO = "usenet ponudnik: neveljavno geslo"

    val POLJA = listOf("gostitelj", "uporabnik", "vrata", "tls", "najvec_povezav", "ime", "nevarno_brez_tls")

    /** Napaka brez omrezja; nosi SAMO razlog, nikoli gesla, uporabnika ali gostitelja. */
    class NapakaPonudnika(val razlog: String) : Exception("usenet ponudnik: $razlog") {
        override fun toString(): String = "NapakaPonudnika($razlog)"
    }

    /** Neveljaven vnos; sporocilo je eno od N_* in nikoli ne vsebuje vnesene vrednosti (gre lahko v dnevnik). */
    class NeveljavenVnos(sporocilo: String) : IllegalArgumentException(sporocilo)

    // ------------------------------------------------------------------ poverilnice

    /** Kot nntp.varna_poverilnica: brez kontrolnih znakov (ukaz AUTHINFO bi jih razbil), 1-400 bajtov UTF-8. */
    fun varnaPoverilnica(v: Any?): Boolean {
        if (v !is String) return false
        var bajtov = 0
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c < ' ' || c == '\u007f') return false
            if (Character.isHighSurrogate(c)) {
                // Osamljen nadomestek ni kodljiv v UTF-8 (Python: UnicodeEncodeError) - zavrnemo enako.
                if (i + 1 >= v.length || !Character.isLowSurrogate(v[i + 1])) return false
                bajtov += 4; i += 2; continue
            }
            if (Character.isLowSurrogate(c)) return false
            bajtov += when { c.code < 0x80 -> 1; c.code < 0x800 -> 2; else -> 3 }
            i++
        }
        return bajtov in 1..NAJVEC_POVERILNICA_BAJTOV
    }

    /**
     * Geslo samo v tem ovoju: toString/equals ne izdata vrednosti, da ga nihce po nesreci ne zapise v dnevnik,
     * napako ali URL (lastnik). Vrednost dobi samo, kdor jo izrecno zahteva (shramba, prijava NNTP).
     */
    class Geslo private constructor(private val v: String) {
        fun vrednost(): String = v
        override fun toString(): String = "Geslo(<skrito>)"
        companion object {
            /** Neveljavno geslo: NeveljavenVnos(N_GESLO) brez vrednosti. */
            fun iz(v: String?): Geslo {
                if (!varnaPoverilnica(v)) throw NeveljavenVnos(N_GESLO)
                return Geslo(v!!)
            }
            fun aliNull(v: String?): Geslo? = if (varnaPoverilnica(v)) Geslo(v!!) else null
        }
    }

    // ------------------------------------------------------------------ gostitelj in IP

    // Brez InetAddress: ta za imena dela DNS (omrezje) - validacija mora biti brez omrezja in enaka Pythonu.
    private const val PREPOVEDANI = "@/\\ \t\r\n\u0000[]"
    private val OZNAKA = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    private val PIKE = Regex("[.\u3002\uff0e\uff61]")

    private fun stKodnihTock(s: String): Int = s.codePointCount(0, s.length)

    private fun imaOsamljenNadomestek(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
                i += 2; continue
            }
            if (Character.isLowSurrogate(c)) return true
            i++
        }
        return false
    }

    /** Kot Python g.encode("idna"): null = kodiranje ne uspe. */
    internal fun idna(g: String): String? {
        if (g.isEmpty()) return ""
        if (g.all { it.code < 0x80 }) {
            // Hitra pot Pythona: prazna oznaka samo na koncu, nobena oznaka >= 64.
            val o = g.split(".")
            if (o.dropLast(1).any { it.isEmpty() }) return null
            if (o.any { it.length >= 64 }) return null
            return g
        }
        if (imaOsamljenNadomestek(g)) return null
        val o = g.split(PIKE).toMutableList()
        var pika = ""
        if (o.isNotEmpty() && o.last().isEmpty()) { pika = "."; o.removeAt(o.size - 1) }
        val izid = StringBuilder()
        for (oz in o) {
            if (izid.isNotEmpty()) izid.append('.')
            if (oz.isEmpty()) return null
            val a = try { IDN.toASCII(oz, 0) } catch (e: IllegalArgumentException) { return null }
            if (a.isEmpty() || a.length >= 64) return null
            izid.append(a)
        }
        return izid.toString() + pika
    }

    /** Kot nntp._preveri_gostitelja (brez vrat). */
    internal fun nntpGostitelj(g: Any?): Boolean {
        val s = g as? String ?: return false
        val n = stKodnihTock(s)
        if (n < 1 || n > 253 || s.any { PREPOVEDANI.indexOf(it) >= 0 }) return false
        return idna(s) != null
    }

    /** IPv4 kot Python ipaddress: tocno 4 deli, 1-3 ASCII stevke, brez vodilnih nicel, <= 255. */
    internal fun ipv4(s: String): Long? {
        val d = s.split(".")
        if (d.size != 4) return null
        var r = 0L
        for (x in d) {
            if (x.isEmpty() || x.length > 3 || !x.all { it in '0'..'9' }) return null
            if (x != "0" && x[0] == '0') return null
            val v = x.toInt()
            if (v > 255) return null
            r = (r shl 8) or v.toLong()
        }
        return r
    }

    /** Hextet kot Python: 0-4 sestnajstiske stevke (prazen niz je 0, kot int("", 16) ga Python ne doseze). */
    private fun hextet(h: String): Int? {
        if (h.length > 4 || !h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        if (h.isEmpty()) return null
        return h.toInt(16)
    }

    /** IPv6 kot Python ipaddress.IPv6Address (prenesen algoritem _ip_int_from_string + %scope). 16 bajtov ali null. */
    internal fun ipv6(vhod: String): ByteArray? {
        var s = vhod
        val p = s.indexOf('%')
        if (p >= 0) {
            val scope = s.substring(p + 1)
            if (scope.isEmpty() || scope.contains('%')) return null
            s = s.substring(0, p)
        }
        if (s.isEmpty()) return null
        val deli = s.split(":").toMutableList()
        if (deli.size < 3) return null
        if (deli.last().contains('.')) {
            val v4 = ipv4(deli.removeAt(deli.size - 1)) ?: return null
            deli.add(java.lang.Long.toHexString((v4 shr 16) and 0xFFFF))
            deli.add(java.lang.Long.toHexString(v4 and 0xFFFF))
        }
        if (deli.size > 9) return null
        var preskok = -1
        for (i in 1 until deli.size - 1) {
            if (deli[i].isEmpty()) {
                if (preskok >= 0) return null
                preskok = i
            }
        }
        val hi: Int
        val lo: Int
        if (preskok >= 0) {
            var h = preskok
            var l = deli.size - preskok - 1
            if (deli[0].isEmpty()) { h -= 1; if (h != 0) return null }
            if (deli.last().isEmpty()) { l -= 1; if (l != 0) return null }
            if (8 - (h + l) < 1) return null
            hi = h; lo = l
        } else {
            if (deli.size != 8) return null
            if (deli[0].isEmpty() || deli.last().isEmpty()) return null
            hi = deli.size; lo = 0
        }
        val hex = IntArray(8)
        for (i in 0 until hi) hex[i] = hextet(deli[i]) ?: return null
        for (i in 0 until lo) hex[8 - lo + i] = hextet(deli[deli.size - lo + i]) ?: return null
        val b = ByteArray(16)
        for (i in 0 until 8) { b[2 * i] = (hex[i] shr 8).toByte(); b[2 * i + 1] = hex[i].toByte() }
        return b
    }

    private fun naslovIp(g: String): ByteArray? {
        val v4 = ipv4(g)
        if (v4 != null) return byteArrayOf((v4 shr 24).toByte(), (v4 shr 16).toByte(), (v4 shr 8).toByte(), v4.toByte())
        if (g.contains('/')) return null
        return ipv6(g)
    }

    /** Ime ali IP brez sheme, poti, vrat in presledkov (kot _veljaven_gostitelj). */
    fun veljavenGostitelj(g: Any?): Boolean {
        if (!nntpGostitelj(g)) return false
        val s = g as String
        // Pregled Safeer-linux 1.0.134 (A8): ideografske pike (。．｡) niso locila imena - ime je v nastavitvah enoumno.
        if (s.any { it == '\u3002' || it == '\uff0e' || it == '\uff61' }) return false
        if (naslovIp(s) != null) return true
        val a = (idna(s) ?: return false).lowercase()
        val oznake = a.split(".")
        if (a.length > 253 || !oznake.all { OZNAKA.matches(it) }) return false
        return !oznake.all { o -> o.all { it in '0'..'9' } }  // "999.1.1.1" ni IP in ni ime
    }

    private class Obseg(val naslov: ByteArray, val bitov: Int)

    private fun obseg(ip: String, bitov: Int) = Obseg(naslovIp(ip)!!, bitov)

    // Izrecen seznam kot referenca: "zasebno" po splosni definiciji bi spustilo tudi dokumentacijske obsege.
    private val DOMACA = listOf(
        obseg("10.0.0.0", 8), obseg("172.16.0.0", 12), obseg("192.168.0.0", 16), obseg("127.0.0.0", 8),
        obseg("169.254.0.0", 16), obseg("::1", 128), obseg("fc00::", 7), obseg("fe80::", 10))

    private fun vObsegu(ip: ByteArray, o: Obseg): Boolean {
        if (ip.size != o.naslov.size) return false
        for (i in 0 until o.bitov) {
            val m = 0x80 shr (i % 8)
            if ((ip[i / 8].toInt() and m) != (o.naslov[i / 8].toInt() and m)) return false
        }
        return true
    }

    /** Samo naslov IP v domacem obsegu; ime nikoli (brez DNS ne vemo, kam kaze). */
    fun domaceOmrezje(g: String): Boolean {
        var ip = naslovIp(g) ?: return false
        if (ip.size == 16 && (0 until 10).all { ip[it].toInt() == 0 } &&
            ip[10].toInt() == -1 && ip[11].toInt() == -1) {
            ip = ip.copyOfRange(12, 16)  // ::ffff:a.b.c.d se presoja kot IPv4
        }
        return DOMACA.any { vObsegu(ip, it) }
    }

    private val DRUGI_NIVO = setOf("co", "com", "net", "org", "ac", "or", "ne", "gov", "edu")

    /** Kot nntp.ponudnik: racun pri ponudniku, da drugo ime istega streznika ne da nove meje ali preverbe. */
    fun ponudnikRacuna(gostitelj: String): String {
        val g = gostitelj.trim().lowercase().trimEnd('.')
        if (g.isEmpty() || g.contains(':') || g.all { it in "0123456789." }) return g
        val d = g.split(".")
        val n = if (d.size >= 3 && d.last().length == 2 && d[d.size - 2] in DRUGI_NIVO) 3 else 2
        return d.takeLast(n).joinToString(".")
    }

    // ------------------------------------------------------------------ ponudnik

    private fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Polja z dolzino (4 bajti big-endian + UTF-8), da "ab|c" in "a|bc" nista isti odtis. */
    internal fun polja(vararg p: String): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        for (x in p) {
            val b = x.toByteArray(Charsets.UTF_8)
            o.write(byteArrayOf((b.size ushr 24).toByte(), (b.size ushr 16).toByte(), (b.size ushr 8).toByte(), b.size.toByte()))
            o.write(b)
        }
        return o.toByteArray()
    }

    private fun jeInt(v: Any?): Boolean = v is Int || v is Long  // Boolean ni stevilo vrat (kot _je_int)

    /** Prva krsitev v istem vrstnem redu kot Ponudnik.__post_init__ ali null. */
    fun napaka(gostitelj: Any?, uporabnik: Any?, vrata: Any?, tls: Any?, najvecPovezav: Any?, ime: Any?,
               nevarnoBrezTls: Any?): String? {
        if (!veljavenGostitelj(gostitelj)) return N_GOSTITELJ
        if (!jeInt(vrata) || (vrata as Number).toLong() !in 1L..65535L) return N_VRATA
        if (!varnaPoverilnica(uporabnik)) return N_UPORABNIK
        if (!jeInt(najvecPovezav) || (najvecPovezav as Number).toLong() !in 1L..NAJVEC_POVEZAV_RACUNA.toLong()) return N_POVEZAV
        if (ime !is String || stKodnihTock(ime) > NAJVEC_IME || ime.any { it < ' ' || it == '\u007f' }) return N_IME
        if (tls !is Boolean || nevarnoBrezTls !is Boolean) return N_TLS_BOOL
        if (!tls) {
            // Geslo v golem besedilu: samo izrecna odlocitev in samo doma (lastnik: TLS privzeto).
            if (!nevarnoBrezTls) return N_BREZ_TLS
            if (!domaceOmrezje(gostitelj as String)) return N_BREZ_TLS_DOMA
        }
        return null
    }

    /** Nastavitev brez gesla. Neveljavne vrednosti ne morejo obstajati (NeveljavenVnos brez vrednosti). */
    class Ponudnik private constructor(
        val gostitelj: String, val uporabnik: String, val vrata: Int, val tls: Boolean, val najvecPovezav: Int,
        val ime: String, val nevarnoBrezTls: Boolean,
    ) {
        /** Kljuc gesla: odtis, ker gola vrednost bi uporabnika pokazala v imenu vnosa shrambe. */
        val kljucGesla: String get() = PREDPONA_KLJUCA + sha256Hex(polja(gostitelj.lowercase(), uporabnik))

        /** Racun pri ponudniku (kot _kljuc_racuna): meja povezav in preverba veljata za racun, ne za ime streznika. */
        val kljucRacuna: String get() = sha256Hex((ponudnikRacuna(gostitelj) + "\u0000" + uporabnik).toByteArray(Charsets.UTF_8))

        /** Trda meja odprtih povezav racuna: vnos uporabnika, a nikoli cez mejo pogona. */
        val mejaPovezav: Int get() = minOf(najvecPovezav, NAJVEC_POVEZAV_NNTP)

        fun vSlovar(): Map<String, Any> = linkedMapOf(
            "gostitelj" to gostitelj, "uporabnik" to uporabnik, "vrata" to vrata, "tls" to tls,
            "najvec_povezav" to najvecPovezav, "ime" to ime, "nevarno_brez_tls" to nevarnoBrezTls)

        // Uporabnik nikoli v celoti: dnevnik in porocila o napakah gredo ven.
        override fun toString(): String =
            "Ponudnik(ime='$ime', $gostitelj:$vrata, tls=${if (tls) "True" else "False"}, povezav=$najvecPovezav, uporabnik=<skrit>)"

        override fun equals(other: Any?): Boolean = other is Ponudnik && vSlovar() == other.vSlovar()
        override fun hashCode(): Int = vSlovar().hashCode()

        companion object {
            /**
             * Pregled Safeer-linux 1.0.134 (A8): ime z mednarodnimi znaki v obliki ASCII (punycode) - TLS, odtis racuna in
             * kljuc gesla so enaki za »news.münchen.de« in »news.xn--mnchen-3ya.de«. IP ostane, kot je.
             */
            private fun ascii(g: String): String =
                if (naslovIp(g) != null || g.all { it.code < 0x80 }) g else (idna(g) ?: g).lowercase()

            fun ustvari(gostitelj: String, uporabnik: String, vrata: Int = VRATA, tls: Boolean = true,
                        najvecPovezav: Int = PRIVZETO_POVEZAV, ime: String = "", nevarnoBrezTls: Boolean = false): Ponudnik {
                napaka(gostitelj, uporabnik, vrata, tls, najvecPovezav, ime, nevarnoBrezTls)?.let { throw NeveljavenVnos(it) }
                return Ponudnik(ascii(gostitelj), uporabnik, vrata, tls, najvecPovezav, ime, nevarnoBrezTls)
            }

            /** Kot iz_slovarja: neznano polje ali manjkajoc gostitelj/uporabnik = "neveljaven zapis". */
            fun izSlovarja(d: Any?): Ponudnik {
                if (d !is Map<*, *> || d.keys.any { it !is String || it !in POLJA }) throw NeveljavenVnos(N_ZAPIS)
                if (!d.containsKey("gostitelj") || !d.containsKey("uporabnik")) throw NeveljavenVnos(N_ZAPIS)
                val g = d["gostitelj"]; val u = d["uporabnik"]
                val v = if (d.containsKey("vrata")) d["vrata"] else VRATA
                val t = if (d.containsKey("tls")) d["tls"] else true
                val n = if (d.containsKey("najvec_povezav")) d["najvec_povezav"] else PRIVZETO_POVEZAV
                val i = if (d.containsKey("ime")) d["ime"] else ""
                val nb = if (d.containsKey("nevarno_brez_tls")) d["nevarno_brez_tls"] else false
                napaka(g, u, v, t, n, i, nb)?.let { throw NeveljavenVnos(it) }
                return Ponudnik(ascii(g as String), u as String, (v as Number).toInt(), t as Boolean, (n as Number).toInt(),
                    i as String, nb as Boolean)
            }
        }
    }

    // ------------------------------------------------------------------ omejitev preverb

    /** 1 preverba na [PREVERBA_NA_MS] na racun. Pod zaklepom: dva hkratna klika ne odpreta dveh povezav. */
    class OmejevalecPreverb(private val ura: () -> Long) {
        private val zadnje = HashMap<String, Long>()

        @Synchronized
        fun rezerviraj(kljucRacuna: String): Boolean {
            val zdaj = ura()
            val z = zadnje[kljucRacuna]
            if (z != null && zdaj - z < PREVERBA_NA_MS) return false
            zadnje[kljucRacuna] = zdaj
            zadnje.entries.removeIf { it.key != kljucRacuna && zdaj - it.value >= PREVERBA_NA_MS }  // omejena velikost
            return true
        }

        /** Nov vpis sme takoj na "Preveri" (kot shrani_ponudnika). */
        @Synchronized
        fun pozabi(kljucRacuna: String) { zadnje.remove(kljucRacuna) }

        @Synchronized
        fun velikost(): Int = zadnje.size
    }

    // ------------------------------------------------------------------ trda meja povezav na racun

    /**
     * Steje ODPRTE povezave na racun cez vse bazene v procesu. Zakaj: referenca omeji vsak bazen posebej; dva bazena
     * (npr. predvajanje + predpomnjenje) bi skupaj presegla mejo ponudnika, kar ta kaznuje z zaklepom racuna.
     */
    class MejeRacunov {
        private val odprte = HashMap<String, Int>()

        /** true = povezavo smes odpreti (stevec je ze povecan); false = meja racuna dosezena, brez omrezja. */
        @Synchronized
        fun zasedi(p: Ponudnik): Boolean {
            val k = p.kljucRacuna
            val n = odprte[k] ?: 0
            if (n >= p.mejaPovezav) return false
            odprte[k] = n + 1
            return true
        }

        @Synchronized
        fun sprosti(p: Ponudnik) {
            val k = p.kljucRacuna
            val n = (odprte[k] ?: 0) - 1
            if (n <= 0) odprte.remove(k) else odprte[k] = n
        }

        @Synchronized
        fun odprtih(p: Ponudnik): Int = odprte[p.kljucRacuna] ?: 0

        /** Kot _BazenRacuna.nastavi_najvec: tudi zahteva od zunaj ne gre cez mejo racuna. */
        fun omejiNajvec(p: Ponudnik, zahtevano: Int): Int = maxOf(1, minOf(zahtevano, p.mejaPovezav))
    }

    // ------------------------------------------------------------------ zapah racuna

    /** Shramba vrstic zapaha (Android: zasebne nastavitve; preizkus: pomnilnik). Brez gesla, samo odtisi. */
    interface ShrambaZapaha {
        fun beri(): String?
        fun pisi(besedilo: String)
    }

    internal fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun izHex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** PBKDF2-HMAC-SHA256 nad bajti (PBEKeySpec sprejme samo znake, polja z dolzino pa niso besedilo). */
    internal fun pbkdf2(geslo: ByteArray, sol: ByteArray, ponovitev: Int, dolzina: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(geslo, "HmacSHA256"))
        val izid = java.io.ByteArrayOutputStream()
        var blok = 1
        while (izid.size() < dolzina) {
            mac.update(sol)
            var u = mac.doFinal(byteArrayOf((blok ushr 24).toByte(), (blok ushr 16).toByte(), (blok ushr 8).toByte(), blok.toByte()))
            val t = u.copyOf()
            for (i in 1 until ponovitev) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            izid.write(t)
            blok++
        }
        return izid.toByteArray().copyOf(dolzina)
    }

    /** Kot nntp.zapah_odtis: (racun, odtis) v hex. Zapis z istim formatom kot Linux, a se NE sinhronizira. */
    fun zapahOdtis(sol: ByteArray, ponudnik: String, uporabnik: String, geslo: String): Pair<String, String> {
        val racun = sha256Hex(sol + polja(ponudnik, uporabnik))
        return racun to hex(pbkdf2(polja(ponudnik, uporabnik, geslo), sol, ZAPAH_PONOVITEV, 32))
    }

    private class Zapis(val sol: ByteArray, val racun: String, val odtis: String, val cas: Long)

    private fun casStevke(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' }

    /**
     * Zapah racuna po zavrnjeni prijavi (kot usenet_nntp _po_prijavi/_zapahni): zavrnjeno geslo gre k strezniku
     * ENKRAT; potem nic vec, dokler uporabnik poverilnic ne vpise znova (odklene()). Zakaj trajno: po ponovnem
     * zagonu aplikacije bi sicer znova poslali isto zavrnjeno geslo in ponudnik bi racun zaklenil.
     */
    class ZapahRacunov(private val shramba: ShrambaZapaha?, private val nakljucno: (Int) -> ByteArray,
                       private val casS: () -> Long) {
        // racun -> odtisi v procesu (hitro, brez PBKDF2 na vsakem klicu); po racunu, da pozabi brez gesla
        // odstrani samo ta racun.
        private val zavrnjeni = HashMap<String, MutableSet<String>>()
        private val neznane = HashMap<String, Int>()
        private val preverjeni = HashMap<String, Boolean>()  // predpomnilnik trajne presoje, brisan ob vsakem pisanju

        private fun odtisProcesa(ponudnik: String, uporabnik: String, geslo: String) =
            sha256Hex(SOL_PROCESA + polja(ponudnik, uporabnik, geslo))

        private fun racunProcesa(ponudnik: String, uporabnik: String) = sha256Hex(SOL_PROCESA + polja(ponudnik, uporabnik))

        private fun jeZavrnjen(pr: String, u: String, o: String) = zavrnjeni[racunProcesa(pr, u)]?.contains(o) == true

        private fun beri(): List<Zapis> {
            val b = try { shramba?.beri() } catch (e: Exception) { null } ?: return emptyList()
            if (b.length > 64 * 1024) return emptyList()
            val v = b.split("\n").filter { it.isNotEmpty() }
            if (v.isEmpty() || v[0] != GLAVA_ZAPAHA) return emptyList()
            val hexZ = "0123456789abcdef"
            return v.drop(1).mapNotNull { vr ->
                val d = vr.split(" ")
                if (d.size == 4 && d[0].length == 32 && d[1].length == 64 && d[2].length == 64 && casStevke(d[3]) &&
                    (d[0] + d[1] + d[2]).all { it in hexZ } && d[3].length < 19) Zapis(izHex(d[0]), d[1], d[2], d[3].toLong()) else null
            }.takeLast(NAJVEC_ZAPAHOV)
        }

        private fun pisi(z: List<Zapis>) {
            preverjeni.clear()
            val s = shramba ?: return
            val b = (listOf(GLAVA_ZAPAHA) + z.takeLast(NAJVEC_ZAPAHOV).map { "${hex(it.sol)} ${it.racun} ${it.odtis} ${it.cas}" })
                .joinToString("\n") + "\n"
            try { s.pisi(b) } catch (e: Exception) { /* velja do konca procesa (zavrnjeni v pomnilniku) */ }
        }

        private fun zaRacun(z: List<Zapis>, ponudnik: String, uporabnik: String) =
            z.filter { MessageDigest.isEqual(sha256Hex(it.sol + polja(ponudnik, uporabnik)).toByteArray(), it.racun.toByteArray()) }

        @Synchronized
        fun zavrnjen(gostitelj: String, uporabnik: String, geslo: Geslo): Boolean {
            val pr = ponudnikRacuna(gostitelj)
            val o = odtisProcesa(pr, uporabnik, geslo.vrednost())
            if (jeZavrnjen(pr, uporabnik, o)) return true
            val znan = preverjeni[o]
            if (znan != null) return znan
            val da = zaRacun(beri(), pr, uporabnik).any {
                MessageDigest.isEqual(zapahOdtis(it.sol, pr, uporabnik, geslo.vrednost()).second.toByteArray(), it.odtis.toByteArray())
            }
            preverjeni[o] = da
            if (da) zavrnjeni.getOrPut(racunProcesa(pr, uporabnik)) { HashSet() }.add(o)
            return da
        }

        /** Kot _po_prijavi: izid prijave (faza prijava) -> ali je racun zdaj zapahnjen. */
        @Synchronized
        fun poPrijavi(gostitelj: String, uporabnik: String, geslo: Geslo, izid: String): Boolean {
            val pr = ponudnikRacuna(gostitelj)
            val o = odtisProcesa(pr, uporabnik, geslo.vrednost())
            if (izid == NNTP_OK) {
                neznane.remove(o)
                pozabiTrajne(pr, uporabnik)
                return false
            }
            when (ukrepPrijave(izid)) {
                NEZNANA_PRIJAVA -> {
                    val n = (neznane[o] ?: 0) + 1
                    neznane[o] = n
                    if (n >= 2) zapahni(o, pr, uporabnik, geslo)  // drugic brez odgovora: morda je geslo zavrnjeno
                }
                USTAVI_RACUN -> zapahni(o, pr, uporabnik, geslo)
            }
            return jeZavrnjen(pr, uporabnik, o)
        }

        private fun zapahni(o: String, pr: String, uporabnik: String, geslo: Geslo) {
            neznane.remove(o)
            if (zavrnjeni.getOrPut(racunProcesa(pr, uporabnik)) { HashSet() }.add(o)) {
                val sol = nakljucno(16)
                val (racun, odtis) = zapahOdtis(sol, pr, uporabnik, geslo.vrednost())
                pisi(beri() + Zapis(sol, racun, odtis, casS()))
            }
        }

        private fun pozabiTrajne(pr: String, uporabnik: String) {
            val z = beri()
            val r = zaRacun(z, pr, uporabnik)
            if (r.isNotEmpty()) pisi(z.filter { it !in r })
        }

        /**
         * EDINO odklepanje: izrecen nov vpis uporabnika (shrani) ali pozabi. Ne klici iz sinhronizacije ali
         * samodejno - to bi spet poslalo zavrnjeno geslo.
         */
        @Synchronized
        fun odkleni(gostitelj: String, uporabnik: String, geslo: Geslo?) {
            val pr = ponudnikRacuna(gostitelj)
            // Kot dovoli_prijavo_znova + _pozabi_zapahe: ves racun (vsa gesla), v procesu in trajno.
            zavrnjeni.remove(racunProcesa(pr, uporabnik))
            if (geslo != null) neznane.remove(odtisProcesa(pr, uporabnik, geslo.vrednost()))
            preverjeni.clear()
            pozabiTrajne(pr, uporabnik)
        }

        @Synchronized
        fun steviloTrajnih(): Int = beri().size
    }

    private val SOL_PROCESA: ByteArray = java.security.SecureRandom().let { r -> ByteArray(16).also { r.nextBytes(it) } }

    /** Kot ukrep_bazena za fazo prijave. */
    fun ukrepPrijave(izid: String): String = when (izid) {
        NNTP_ZASEDEN -> ZMANJSAJ_POCAKAJ
        NNTP_OMREZJE, NNTP_ROK, NNTP_ZAPRTO, NNTP_NAPAKA_PROTOKOLA -> NEZNANA_PRIJAVA
        else -> USTAVI_RACUN
    }

    // ------------------------------------------------------------------ TLS

    /** Kar o TLS pove povezovalnik; preveriTls ga presodi PRED omrezjem. */
    class Tls(val preveriPotrdilo: Boolean, val preveriIme: Boolean, val protokoli: List<String>) {
        companion object {
            /** Sistemska zbirka potrdil + preverba imena + TLS 1.2/1.3 - edina nastavitev, ki jo shramba poda. */
            val SISTEMSKI = Tls(true, true, listOf("TLSv1.3", "TLSv1.2"))
        }
    }

    private val VARNI_PROTOKOLI = setOf("TLSv1.2", "TLSv1.3")

    /** Kot nntp.varen_kontekst: brez preverbe potrdila ali imena NapakaPonudnika pred omrezjem; < 1.2 se odreze. */
    fun preveriTls(t: Tls): List<String> {
        if (!t.preveriPotrdilo || !t.preveriIme) throw NapakaPonudnika(NEVAREN_TLS)
        val p = t.protokoli.filter { it in VARNI_PROTOKOLI }
        if (p.isEmpty()) throw NapakaPonudnika(NEVAREN_TLS)
        return p
    }

    /**
     * Nastavi parametre vticnice za NNTP: "HTTPS" je v JSSE/Android edini nacin, da SSLSocket sam preveri ime
     * gostitelja v potrdilu (brez tega bi sprejel veljavno potrdilo TUJEGA imena). Protokoli samo 1.2/1.3.
     */
    fun varniParametri(p: SSLParameters): SSLParameters {
        p.endpointIdentificationAlgorithm = "HTTPS"
        val pr = p.protocols?.filter { it in VARNI_PROTOKOLI }.orEmpty()
        if (pr.isEmpty()) throw NapakaPonudnika(NEVAREN_TLS)
        p.protocols = pr.toTypedArray()
        return p
    }

    /** Presoja parametrov vticnice pred connect (povezovalnik jo poklice, ce parametre nastavlja sam). */
    fun tlsIzParametrov(p: SSLParameters, privzetaZbirka: Boolean): Tls =
        Tls(privzetaZbirka, p.endpointIdentificationAlgorithm.equals("HTTPS", ignoreCase = true), p.protocols?.toList().orEmpty())

    // ------------------------------------------------------------------ preverba

    /** Izid ene prijave iz pogona: izid usenet_nntp ("ok", "prijava", ...) in faza, v kateri se je koncala. */
    class Prijava(val izid: String, val faza: String)

    /** Kot _izid_napake. zapahnjen = stanje zapaha po _po_prijavi. */
    fun izidNapake(izid: String, faza: String, zapahnjen: Boolean): String {
        if (izid == NNTP_ZASEDEN || (faza == FAZA_POVEZAVA && izid == NNTP_DOSTOP)) return PREVEC_POVEZAV
        if (faza == FAZA_PRIJAVA) {
            if (zapahnjen) return if (izid == NNTP_PRIJAVA) NE_VELJA else ZAKLENJEN
            return NI_ODGOVORA  // geslo je morda prislo do streznika, odgovora ni (prvic: brez zapaha)
        }
        return if (zapahnjen) ZAKLENJEN else NI_ODGOVORA
    }

    /**
     * Gumb "Preveri" (kot preveri_ponudnika): ENA prijava, vrstni red preverb enak referenci. Vse zavrnitve so PRED
     * klicem prijava (brez omrezja). Brez [odkleni] je po NE_VELJA naslednja preverba ZAKLENJEN brez omrezja.
     * Z [odkleni] (izrecen klik uporabnika, lastnik 28. 10. 2026) zapah zavrnjenega racuna sprosti za EN nov poskus,
     * a sele ko omejevalec dovoli preverbo (PREPOGOSTO zapaha ne sprosti); stevec neznanih izidov ostane.
     */
    fun preveri(p: Ponudnik, geslo: Geslo, naGlavniNiti: Boolean, tls: Tls, zapahi: ZapahRacunov,
                omejevalec: OmejevalecPreverb, prijava: (Ponudnik, Geslo, List<String>) -> Prijava,
                odkleni: Boolean = false): String {
        if (naGlavniNiti) throw NapakaPonudnika(GLAVNA_NIT)  // omrezje na glavni niti zamrzne okno (ANR)
        if (!p.tls) throw NapakaPonudnika(BREZ_TLS)            // pogon zna samo TLS
        val protokoli = preveriTls(tls)
        if (!odkleni && zapahi.zavrnjen(p.gostitelj, p.uporabnik, geslo)) return ZAKLENJEN
        if (!omejevalec.rezerviraj(p.kljucRacuna)) return PREPOGOSTO
        if (odkleni && zapahi.zavrnjen(p.gostitelj, p.uporabnik, geslo)) zapahi.odkleni(p.gostitelj, p.uporabnik, null)
        val r = try {
            prijava(p, geslo, protokoli)
        } catch (e: NapakaPonudnika) {
            throw e
        } catch (e: Exception) {
            Prijava(NNTP_OMREZJE, FAZA_POVEZAVA)  // nepricakovana napaka pogona: kot omrezje, brez zapaha
        }
        if (r.faza == FAZA_PRIJAVA || r.izid == NNTP_OK) {
            val zapahnjen = zapahi.poPrijavi(p.gostitelj, p.uporabnik, geslo, r.izid)
            if (r.izid == NNTP_OK) return VELJA
            return izidNapake(r.izid, r.faza, zapahnjen)
        }
        return izidNapake(r.izid, r.faza, zapahi.zavrnjen(p.gostitelj, p.uporabnik, geslo))
    }

    /** Kot ustvari_bazen: pred bazenom za predvajanje; null = sme, sicer razlog brez omrezja. */
    fun oviraBazena(p: Ponudnik, geslo: Geslo?, zapahi: ZapahRacunov): String? {
        if (!p.tls) return BREZ_TLS
        if (geslo == null) return NI_GESLA
        if (zapahi.zavrnjen(p.gostitelj, p.uporabnik, geslo)) return RACUN_ZAKLENJEN
        return null
    }

    // ------------------------------------------------------------------ sinhronizacija v ozjem krogu

    /**
     * Vrednost za secrets.sync (sifrirano, SAMO ozji krog). toString ne izda nicesar. Zapah in izid preverbe se
     * NE sinhronizirata: zapah je stanje te naprave (kot zapora pri Debrid), odklene ga samo vpis na tej napravi.
     */
    class ZapisKroga(val ponudnik: Map<String, Any>, val geslo: Geslo) {
        override fun toString(): String = "ZapisKroga(<skrito>)"
    }

    fun zaKrog(p: Ponudnik, geslo: Geslo): ZapisKroga = ZapisKroga(p.vSlovar(), geslo)

    /** Prejeto iz kroga: ista validacija kot vnos; neveljavno = null (nic se ne shrani). */
    fun izKroga(ponudnik: Any?, geslo: String?): Pair<Ponudnik, Geslo>? {
        val p = try { Ponudnik.izSlovarja(ponudnik) } catch (e: NeveljavenVnos) { return null }
        val g = Geslo.aliNull(geslo) ?: return null
        return p to g
    }

    // ------------------------------------------------------------------ JSON (majhen, brez knjiznic)

    /**
     * Majhen JSON za zapis nastavitev: cisto jedro ne sme odvisno od org.json (ga na JVM ni), zapis pa je ploscat.
     * Objekti -> LinkedHashMap, cela stevila -> Long (prevelika -> Double), ostala -> Double.
     */
    object Json {
        fun beri(s: String): Any? {
            val b = Bralec(s)
            val v = b.vrednost()
            b.presledki()
            require(b.i == s.length) { "json" }
            return v
        }

        private class Bralec(val s: String) {
            var i = 0
            fun presledki() { while (i < s.length && s[i] in " \t\r\n") i++ }
            fun vrednost(): Any? {
                presledki()
                require(i < s.length) { "json" }
                return when (s[i]) {
                    '{' -> objekt()
                    '[' -> seznam()
                    '"' -> niz()
                    't' -> beseda("true", true)
                    'f' -> beseda("false", false)
                    'n' -> beseda("null", null)
                    else -> stevilo()
                }
            }
            fun beseda(w: String, v: Any?): Any? { require(s.startsWith(w, i)) { "json" }; i += w.length; return v }
            fun objekt(): Map<String, Any?> {
                val m = LinkedHashMap<String, Any?>()
                i++; presledki()
                if (i < s.length && s[i] == '}') { i++; return m }
                while (true) {
                    presledki()
                    require(i < s.length && s[i] == '"') { "json" }
                    val k = niz(); presledki()
                    require(i < s.length && s[i] == ':') { "json" }
                    i++
                    m[k] = vrednost(); presledki()
                    require(i < s.length) { "json" }
                    if (s[i] == ',') { i++; continue }
                    require(s[i] == '}') { "json" }
                    i++; return m
                }
            }
            fun seznam(): List<Any?> {
                val l = ArrayList<Any?>()
                i++; presledki()
                if (i < s.length && s[i] == ']') { i++; return l }
                while (true) {
                    l.add(vrednost()); presledki()
                    require(i < s.length) { "json" }
                    if (s[i] == ',') { i++; continue }
                    require(s[i] == ']') { "json" }
                    i++; return l
                }
            }
            fun niz(): String {
                i++
                val o = StringBuilder()
                while (true) {
                    require(i < s.length) { "json" }
                    val c = s[i++]
                    when {
                        c == '"' -> return o.toString()
                        c == '\\' -> {
                            require(i < s.length) { "json" }
                            when (val e = s[i++]) {
                                '"' -> o.append('"'); '\\' -> o.append('\\'); '/' -> o.append('/')
                                'b' -> o.append('\b'); 'f' -> o.append('\u000c'); 'n' -> o.append('\n')
                                'r' -> o.append('\r'); 't' -> o.append('\t')
                                'u' -> { require(i + 4 <= s.length) { "json" }; o.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                                else -> throw IllegalArgumentException("json $e")
                            }
                        }
                        c < ' ' -> throw IllegalArgumentException("json")
                        else -> o.append(c)
                    }
                }
            }
            fun stevilo(): Any {
                val z = i
                if (i < s.length && s[i] == '-') i++
                while (i < s.length && (s[i] in '0'..'9' || s[i] in ".eE+-")) i++
                val t = s.substring(z, i)
                require(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(t)) { "json" }
                if (t.all { it == '-' || it in '0'..'9' }) return t.toLongOrNull() ?: t.toDouble()
                return t.toDouble()
            }
        }

        fun pisi(v: Any?): String = StringBuilder().also { pisi(v, it) }.toString()

        private fun pisi(v: Any?, o: StringBuilder) {
            when (v) {
                null -> o.append("null")
                is Boolean, is Int, is Long, is Double -> o.append(v.toString())
                is String -> {
                    o.append('"')
                    for (c in v) when {
                        c == '"' -> o.append("\\\"")
                        c == '\\' -> o.append("\\\\")
                        c < ' ' || c.code >= 0x7f -> o.append("\\u%04x".format(c.code))
                        else -> o.append(c)
                    }
                    o.append('"')
                }
                is Map<*, *> -> {
                    o.append('{')
                    var prvi = true
                    for ((k, x) in v.entries.sortedBy { it.key.toString() }) {
                        if (!prvi) o.append(','); prvi = false
                        pisi(k.toString(), o); o.append(':'); pisi(x, o)
                    }
                    o.append('}')
                }
                else -> throw IllegalArgumentException("json tip")
            }
        }
    }

    // ------------------------------------------------------------------ shramba (cista; Android poda vmesnike)

    /** Kljuc-vrednost (Android: zasebne SharedPreferences). null = brisi. */
    interface Nastavitve {
        fun beri(kljuc: String): String?
        fun pisi(kljuc: String, vrednost: String?)
    }

    /** Sifrirana zbirka gesel (Android: AndroidKeyStore). Vrne false, ce trajna zbirka ni na voljo. */
    interface ZbirkaGesel {
        fun shrani(kljuc: String, geslo: Geslo): Boolean
        fun preberi(kljuc: String): String?
        fun pozabi(kljuc: String)
    }

    const val KLJUC_NASTAVITEV = "usenet_ponudnik"
    const val KLJUC_ZAPAHA = "usenet_zapah"

    /**
     * Shramba ponudnika kot usenet_ponudnik.py (shrani/preberi/pozabi/preveri/stanje). Geslo gre SAMO v ZbirkaGesel;
     * v Nastavitve pride samo zapis brez gesla (preizkus to preveri). Brez trajne zbirke ostane geslo samo v
     * pomnilniku procesa (kot referenca) - nikoli v navadno datoteko.
     */
    class ShrambaPonudnika(
        private val nastavitve: Nastavitve,
        private val zbirka: ZbirkaGesel,
        val zapahi: ZapahRacunov,
        private val omejevalec: OmejevalecPreverb,
        private val casS: () -> Long,
    ) {
        private val vPomnilniku = HashMap<String, Geslo>()

        private fun beriZapis(): Map<*, *>? {
            val s = try { nastavitve.beri(KLJUC_NASTAVITEV) } catch (e: Exception) { null } ?: return null
            if (s.toByteArray(Charsets.UTF_8).size > NAJVEC_NASTAVITEV_BAJTOV) return null  // tuj ali pokvarjen zapis
            val d = try { Json.beri(s) } catch (e: Exception) { return null }
            if (d !is Map<*, *>) return null
            val v = d["verzija"]
            // Python primerja d.get("verzija") == 1, kar velja tudi za 1.0 in True - enako, da zapis velja povsod.
            if (!(v == VERZIJA.toLong() || v == VERZIJA.toDouble() || v == true)) return null
            return d
        }

        private fun ponudnikIz(d: Map<*, *>?): Ponudnik? =
            if (d == null) null else try { Ponudnik.izSlovarja(d["ponudnik"]) } catch (e: NeveljavenVnos) { null }

        private fun pisiZapis(p: Ponudnik, preverba: Map<String, Any>?) {
            nastavitve.pisi(KLJUC_NASTAVITEV, Json.pisi(mapOf("verzija" to VERZIJA, "ponudnik" to p.vSlovar(), "preverba" to preverba)))
        }

        private fun shraniGeslo(kljuc: String, g: Geslo): Boolean {
            val trajno = try { zbirka.shrani(kljuc, g) } catch (e: Exception) { false }
            synchronized(vPomnilniku) { if (trajno) vPomnilniku.remove(kljuc) else vPomnilniku[kljuc] = g }
            return trajno
        }

        private fun pozabiGeslo(kljuc: String) {
            synchronized(vPomnilniku) { vPomnilniku.remove(kljuc) }
            try { zbirka.pozabi(kljuc) } catch (e: Exception) { }
        }

        /** Geslo iz zbirke; pokvarjen vnos ne gre v ukaz (kot _preberi_geslo). */
        fun geslo(p: Ponudnik): Geslo? {
            synchronized(vPomnilniku) { vPomnilniku[p.kljucGesla] }?.let { return it }
            return Geslo.aliNull(try { zbirka.preberi(p.kljucGesla) } catch (e: Exception) { null })
        }

        fun preberi(): Ponudnik? = ponudnikIz(beriZapis())

        /** Izrecni vpis uporabnika v oknu (tudi enakih poverilnic) odklene zapah; enako izrecni »Preveri« (lastnik 28. 10. 2026). */
        @Synchronized
        fun shrani(p: Ponudnik, geslo: Geslo): Boolean {
            val star = preberi()
            val prejsnje = star?.let { geslo(it) }
            val trajno = zapisi(p, geslo)
            zapahi.odkleni(p.gostitelj, p.uporabnik, geslo)
            // Samo DRUGO geslo istega racuna sme takoj na »Preveri« (kot Safeer-linux, pregled 1.0.134 A2): ponoven vpis
            // istega gesla, drugo ime streznika istega racuna ali »Pozabi« + vpis ne obidejo omejitve 10 s.
            if (star != null && star.kljucRacuna == p.kljucRacuna && prejsnje != null && prejsnje.vrednost() != geslo.vrednost())
                omejevalec.pozabi(p.kljucRacuna)
            return trajno
        }

        private fun zapisi(p: Ponudnik, geslo: Geslo): Boolean {
            val star = preberi()
            pisiZapis(p, null)
            if (star != null && star.kljucGesla != p.kljucGesla) pozabiGeslo(star.kljucGesla)  // staro geslo ne ostane
            return shraniGeslo(p.kljucGesla, geslo)
        }

        /**
         * Prejeto iz ozjega kroga (secrets.sync je ze desifriral). Ista validacija kot vnos. NE odklene zapaha in NE
         * ponastavi omejitve: to ni uporabnikov vpis na tej napravi. Drugacno geslo zapah tako ali tako obide, ker je
         * geslo del odtisa; isto zavrnjeno geslo ostane zapahnjeno.
         */
        @Synchronized
        fun prejetoIzKroga(ponudnik: Any?, geslo: String?): Boolean {
            val (p, g) = izKroga(ponudnik, geslo) ?: return false
            zapisi(p, g)
            return true
        }

        /** Za secrets.sync (sifrirano, samo ozji krog). Brez zapaha in izida preverbe. */
        fun zaKrog(): ZapisKroga? {
            val p = preberi() ?: return null
            val g = geslo(p) ?: return null
            return zaKrog(p, g)
        }

        /** Uporabnik je racun odstranil: zapis, geslo, zapah in omejitev. */
        @Synchronized
        fun pozabi() {
            val p = preberi()
            if (p != null) {
                zapahi.odkleni(p.gostitelj, p.uporabnik, geslo(p))
                pozabiGeslo(p.kljucGesla)
            }
            nastavitve.pisi(KLJUC_NASTAVITEV, null)
        }

        /** Gumb "Preveri" (samo na klik, nikoli na glavni niti ali v ozadju). Zapise VELJA/NE_VELJA/ZAKLENJEN. */
        fun preveri(p: Ponudnik, geslo: Geslo, naGlavniNiti: Boolean, tls: Tls,
                    prijava: (Ponudnik, Geslo, List<String>) -> Prijava): String {
            // Lastnik 28. 10. 2026 (enotno na vseh izdajah): izrecni »Preveri« odklene zapah za en nov poskus, kot »Shrani«;
            // omejitev ene preverbe na 10 s ([omejevalec]) ostane.
            val izid = UsenetPonudnikPravila.preveri(p, geslo, naGlavniNiti, tls, zapahi, omejevalec, prijava, odkleni = true)
            if (izid == VELJA || izid == NE_VELJA || izid == ZAKLENJEN) zapisiPreverbo(p, izid, geslo)
            return izid
        }

        @Synchronized
        private fun zapisiPreverbo(p: Ponudnik, izid: String, g: Geslo) {
            val d = beriZapis()
            val s = ponudnikIz(d)
            if (s == null || s.kljucGesla != p.kljucGesla) return  // medtem zamenjan ponudnik: izid ne velja zanj
            // Kot Safeer-linux (pregled 1.0.134 A4 in krog 2): med preverbo vpisano drugo geslo, vrata, TLS ali uporabnik -
            // izid starega vpisa ne velja; drugo prikazno ime ali stevilo povezav izida ne spremeni.
            if (zaPreverbo(s) != zaPreverbo(p) || geslo(s)?.vrednost() != g.vrednost()) return
            try { pisiZapis(s, mapOf("izid" to izid, "cas" to casS())) } catch (e: Exception) { }
        }

        private fun zaPreverbo(p: Ponudnik) = listOf(p.gostitelj.lowercase(), p.vrata, p.tls, p.nevarnoBrezTls, p.uporabnik)

        /** Pred bazenom za predvajanje (kot ustvari_bazen): null = sme. */
        fun oviraBazena(p: Ponudnik): String? = oviraBazena(p, geslo(p), zapahi)

        fun stanje(): StanjeVmesnika {
            val d = beriZapis()
            val p = ponudnikIz(d) ?: return stanjeZaVmesnik(null, null, false, false)
            val pr = d!!["preverba"] as? Map<*, *>
            val g = geslo(p)
            val zap = g != null && zapahi.zavrnjen(p.gostitelj, p.uporabnik, g)
            return stanjeZaVmesnik(p, pr?.get("izid"), g != null, zap)
        }
    }

    // ------------------------------------------------------------------ vmesnik

    class StanjeVmesnika(
        val vpisan: Boolean, val usenetViden: Boolean, val ime: String?, val gostitelj: String?, val vrata: Int?,
        val tls: Boolean?, val najvecPovezav: Int?, val gesloShranjeno: Boolean, val stanje: String?, val besedilo: String?,
    )

    /** Kot stanje_za_vmesnik; besedilo je kljuc za BESEDILA (null = ni vpisan). Brez gesla in uporabnika. */
    fun stanjeZaVmesnik(p: Ponudnik?, zadnjiIzid: Any?, gesloShranjeno: Boolean, zapahnjen: Boolean): StanjeVmesnika {
        if (p == null) return StanjeVmesnika(false, false, null, null, null, null, null, false, null, null)
        val zadnji = if (zadnjiIzid == VELJA || zadnjiIzid == NE_VELJA || zadnjiIzid == ZAKLENJEN) zadnjiIzid else null
        val stanje = when {
            gesloShranjeno && zapahnjen -> if (zadnji == NE_VELJA) NE_VELJA else ZAKLENJEN  // zapah pred staro VELJA
            zadnji == VELJA -> VELJA
            else -> NEPREVERJEN
        }
        val besedilo = if (!gesloShranjeno && stanje == NEPREVERJEN) NI_GESLA else stanje
        return StanjeVmesnika(true, true, p.ime, p.gostitelj, p.vrata, p.tls, p.najvecPovezav, gesloShranjeno, stanje, besedilo)
    }

    val JEZIKI = listOf("sl", "en", "de", "es", "fr", "it")

    /** Besedilo v jeziku; neznan jezik -> en, manjkajoc kljuc -> kljuc (preizkus zahteva, da nobeden ne manjka). */
    fun besedilo(kljuc: String?, jezik: String): String {
        val k = kljuc ?: "NI_VPISAN"
        val b = BESEDILA[k] ?: return k
        return b[jezik] ?: b["en"] ?: k
    }

    /** Besedila okna "Moji viri -> Usenet ponudnik". sl za stanja dobesedno iz reference (_BESEDILA). */
    val BESEDILA: Map<String, Map<String, String>> = mapOf(
        "NASLOV" to mapOf("sl" to "Usenet ponudnik", "en" to "Usenet provider", "de" to "Usenet-Anbieter",
            "es" to "Proveedor de Usenet", "fr" to "Fournisseur Usenet", "it" to "Provider Usenet"),
        "POLJE_IME" to mapOf("sl" to "Ime (neobvezno)", "en" to "Name (optional)", "de" to "Name (optional)",
            "es" to "Nombre (opcional)", "fr" to "Nom (facultatif)", "it" to "Nome (facoltativo)"),
        "POLJE_STREZNIK" to mapOf("sl" to "Strežnik", "en" to "Server", "de" to "Server", "es" to "Servidor",
            "fr" to "Serveur", "it" to "Server"),
        "POLJE_VRATA" to mapOf("sl" to "Vrata", "en" to "Port", "de" to "Port", "es" to "Puerto", "fr" to "Port",
            "it" to "Porta"),
        "POLJE_TLS" to mapOf("sl" to "Šifrirana povezava (TLS)", "en" to "Encrypted connection (TLS)",
            "de" to "Verschlüsselte Verbindung (TLS)", "es" to "Conexión cifrada (TLS)",
            "fr" to "Connexion chiffrée (TLS)", "it" to "Connessione cifrata (TLS)"),
        "POLJE_UPORABNIK" to mapOf("sl" to "Uporabniško ime", "en" to "Username", "de" to "Benutzername",
            "es" to "Nombre de usuario", "fr" to "Nom d’utilisateur", "it" to "Nome utente"),
        "POLJE_GESLO" to mapOf("sl" to "Geslo", "en" to "Password", "de" to "Passwort", "es" to "Contraseña",
            "fr" to "Mot de passe", "it" to "Password"),
        "POLJE_POVEZAV" to mapOf("sl" to "Največ povezav", "en" to "Maximum connections", "de" to "Maximale Verbindungen",
            "es" to "Conexiones máximas", "fr" to "Connexions maximales", "it" to "Connessioni massime"),
        "NAMIG_POVEZAV" to mapOf(
            "sl" to "Vpiši največ toliko, kolikor dovoli tvoj paket. Safeer jih ne odpre več.",
            "en" to "Enter at most what your plan allows. Safeer never opens more than that.",
            "de" to "Höchstens so viele, wie dein Tarif erlaubt. Safeer öffnet nie mehr.",
            "es" to "Introduce como máximo las que permite tu plan. Safeer nunca abre más.",
            "fr" to "Indique au maximum ce que ton abonnement autorise. Safeer n’en ouvre jamais plus.",
            "it" to "Inserisci al massimo quante ne consente il tuo piano. Safeer non ne apre mai di più."),
        "GUMB_SHRANI" to mapOf("sl" to "Shrani", "en" to "Save", "de" to "Speichern", "es" to "Guardar",
            "fr" to "Enregistrer", "it" to "Salva"),
        "GUMB_PREVERI" to mapOf("sl" to "Preveri", "en" to "Check", "de" to "Prüfen", "es" to "Comprobar",
            "fr" to "Vérifier", "it" to "Verifica"),
        "GUMB_ZAPRI" to mapOf("sl" to "Zapri", "en" to "Close", "de" to "Schließen", "es" to "Cerrar",
            "fr" to "Fermer", "it" to "Chiudi"),
        "GUMB_POZABI" to mapOf("sl" to "Pozabi ponudnika", "en" to "Forget provider", "de" to "Anbieter entfernen",
            "es" to "Olvidar proveedor", "fr" to "Oublier le fournisseur", "it" to "Dimentica provider"),
        "OPOZORILO_BREZ_TLS" to mapOf(
            "sl" to "Brez TLS gre geslo po omrežju nešifrirano. Dovoljeno samo do naslova IP v domačem omrežju.",
            "en" to "Without TLS the password travels unencrypted. Allowed only to an IP address on your home network.",
            "de" to "Ohne TLS wird das Passwort unverschlüsselt übertragen. Nur für eine IP-Adresse im Heimnetz erlaubt.",
            "es" to "Sin TLS la contraseña viaja sin cifrar. Solo se permite a una dirección IP de tu red doméstica.",
            "fr" to "Sans TLS, le mot de passe circule en clair. Autorisé uniquement vers une adresse IP de ton réseau local.",
            "it" to "Senza TLS la password viaggia in chiaro. Consentito solo verso un indirizzo IP della rete domestica."),
        "NI_VPISAN" to mapOf(
            "sl" to "Usenet se pokaže, ko v nastavitvah vpišeš svojega ponudnika NNTP.",
            "en" to "Usenet appears once you enter your own NNTP provider in the settings.",
            "de" to "Usenet erscheint, sobald du in den Einstellungen deinen eigenen NNTP-Anbieter einträgst.",
            "es" to "Usenet aparece cuando introduces tu propio proveedor NNTP en los ajustes.",
            "fr" to "Usenet apparaît dès que tu saisis ton propre fournisseur NNTP dans les réglages.",
            "it" to "Usenet compare quando inserisci il tuo provider NNTP nelle impostazioni."),
        NEPREVERJEN to mapOf(
            "sl" to "Ponudnik je shranjen. Klikni »Preveri«, če želiš preizkusiti prijavo.",
            "en" to "The provider is saved. Tap “Check” if you want to test the login.",
            "de" to "Der Anbieter ist gespeichert. Tippe auf „Prüfen“, um die Anmeldung zu testen.",
            "es" to "El proveedor está guardado. Pulsa «Comprobar» si quieres probar el inicio de sesión.",
            "fr" to "Le fournisseur est enregistré. Appuie sur « Vérifier » pour tester la connexion.",
            "it" to "Il provider è salvato. Tocca «Verifica» se vuoi provare l’accesso."),
        NI_GESLA to mapOf(
            "sl" to "Geslo za ponudnika ni shranjeno. Vpiši ga znova v nastavitvah Useneta.",
            "en" to "The provider password is not saved. Enter it again in the Usenet settings.",
            "de" to "Das Passwort des Anbieters ist nicht gespeichert. Gib es in den Usenet-Einstellungen erneut ein.",
            "es" to "La contraseña del proveedor no está guardada. Vuelve a introducirla en los ajustes de Usenet.",
            "fr" to "Le mot de passe du fournisseur n’est pas enregistré. Saisis-le à nouveau dans les réglages Usenet.",
            "it" to "La password del provider non è salvata. Inseriscila di nuovo nelle impostazioni Usenet."),
        VELJA to mapOf("sl" to "Prijava pri ponudniku deluje.", "en" to "Login to the provider works.",
            "de" to "Die Anmeldung beim Anbieter funktioniert.", "es" to "El inicio de sesión en el proveedor funciona.",
            "fr" to "La connexion au fournisseur fonctionne.", "it" to "L’accesso al provider funziona."),
        NE_VELJA to mapOf(
            "sl" to "Ponudnik je zavrnil prijavo. Preveri uporabniško ime in geslo in ju vpiši znova - do takrat Safeer gesla ne pošilja (zaščita pred zaklepom računa).",
            "en" to "The provider rejected the login. Check your username and password and enter them again - until then Safeer does not send the password (protection against account lockout).",
            "de" to "Der Anbieter hat die Anmeldung abgelehnt. Prüfe Benutzername und Passwort und gib sie erneut ein - bis dahin sendet Safeer das Passwort nicht (Schutz vor Kontosperre).",
            "es" to "El proveedor rechazó el inicio de sesión. Revisa el usuario y la contraseña y vuelve a introducirlos; hasta entonces Safeer no envía la contraseña (protección contra el bloqueo de la cuenta).",
            "fr" to "Le fournisseur a refusé la connexion. Vérifie l’identifiant et le mot de passe puis saisis-les à nouveau - d’ici là, Safeer n’envoie pas le mot de passe (protection contre le blocage du compte).",
            "it" to "Il provider ha rifiutato l’accesso. Controlla nome utente e password e inseriscili di nuovo - fino ad allora Safeer non invia la password (protezione dal blocco dell’account)."),
        ZAKLENJEN to mapOf(
            "sl" to "Prijava je bila zavrnjena, zato Safeer gesla ne pošilja več. Ko poverilnice preveriš pri ponudniku, jih vpiši znova.",
            "en" to "The login was rejected, so Safeer no longer sends the password. Once you have checked your credentials with the provider, enter them again.",
            "de" to "Die Anmeldung wurde abgelehnt, daher sendet Safeer das Passwort nicht mehr. Wenn du die Zugangsdaten beim Anbieter geprüft hast, gib sie erneut ein.",
            "es" to "El inicio de sesión fue rechazado, así que Safeer ya no envía la contraseña. Cuando hayas comprobado tus credenciales con el proveedor, vuelve a introducirlas.",
            "fr" to "La connexion a été refusée, Safeer n’envoie donc plus le mot de passe. Après avoir vérifié tes identifiants auprès du fournisseur, saisis-les à nouveau.",
            "it" to "L’accesso è stato rifiutato, quindi Safeer non invia più la password. Dopo aver verificato le credenziali con il provider, inseriscile di nuovo."),
        NI_ODGOVORA to mapOf(
            "sl" to "Strežnik ni odgovoril. Preveri strežnik, vrata in omrežje.",
            "en" to "The server did not respond. Check the server, port and network.",
            "de" to "Der Server hat nicht geantwortet. Prüfe Server, Port und Netzwerk.",
            "es" to "El servidor no respondió. Revisa el servidor, el puerto y la red.",
            "fr" to "Le serveur n’a pas répondu. Vérifie le serveur, le port et le réseau.",
            "it" to "Il server non ha risposto. Controlla server, porta e rete."),
        PREVEC_POVEZAV to mapOf(
            "sl" to "Ponudnik javlja preveč hkratnih povezav. Zapri druge programe ali zmanjšaj število povezav.",
            "en" to "The provider reports too many simultaneous connections. Close other apps or lower the number of connections.",
            "de" to "Der Anbieter meldet zu viele gleichzeitige Verbindungen. Schließe andere Programme oder verringere die Anzahl der Verbindungen.",
            "es" to "El proveedor indica demasiadas conexiones simultáneas. Cierra otras aplicaciones o reduce el número de conexiones.",
            "fr" to "Le fournisseur signale trop de connexions simultanées. Ferme les autres applications ou réduis le nombre de connexions.",
            "it" to "Il provider segnala troppe connessioni simultanee. Chiudi altre app o riduci il numero di connessioni."),
        PREPOGOSTO to mapOf(
            "sl" to "Preverba je mogoča enkrat na 10 sekund. Poskusi znova čez nekaj sekund.",
            "en" to "You can check once every 10 seconds. Try again in a few seconds.",
            "de" to "Eine Prüfung ist alle 10 Sekunden möglich. Versuche es in ein paar Sekunden erneut.",
            "es" to "Solo se puede comprobar una vez cada 10 segundos. Inténtalo de nuevo en unos segundos.",
            "fr" to "La vérification est possible une fois toutes les 10 secondes. Réessaie dans quelques secondes.",
            "it" to "La verifica è possibile una volta ogni 10 secondi. Riprova tra qualche secondo."),
        BREZ_TLS to mapOf(
            "sl" to "Povezava brez TLS ni podprta. Vklopi TLS (vrata 563).",
            "en" to "Connections without TLS are not supported. Turn on TLS (port 563).",
            "de" to "Verbindungen ohne TLS werden nicht unterstützt. Aktiviere TLS (Port 563).",
            "es" to "No se admiten conexiones sin TLS. Activa TLS (puerto 563).",
            "fr" to "Les connexions sans TLS ne sont pas prises en charge. Active TLS (port 563).",
            "it" to "Le connessioni senza TLS non sono supportate. Attiva TLS (porta 563)."),
        N_GOSTITELJ to mapOf("sl" to "Vpiši samo ime strežnika ali naslov IP, brez http:// in vrat.",
            "en" to "Enter only the server name or IP address, without http:// or a port.",
            "de" to "Gib nur den Servernamen oder die IP-Adresse ein, ohne http:// und Port.",
            "es" to "Introduce solo el nombre del servidor o la dirección IP, sin http:// ni puerto.",
            "fr" to "Saisis uniquement le nom du serveur ou l’adresse IP, sans http:// ni port.",
            "it" to "Inserisci solo il nome del server o l’indirizzo IP, senza http:// né porta."),
        N_VRATA to mapOf("sl" to "Vrata morajo biti število od 1 do 65535.", "en" to "The port must be a number from 1 to 65535.",
            "de" to "Der Port muss eine Zahl von 1 bis 65535 sein.", "es" to "El puerto debe ser un número del 1 al 65535.",
            "fr" to "Le port doit être un nombre de 1 à 65535.", "it" to "La porta deve essere un numero da 1 a 65535."),
        N_UPORABNIK to mapOf("sl" to "Uporabniško ime ni veljavno.", "en" to "The username is not valid.",
            "de" to "Der Benutzername ist ungültig.", "es" to "El nombre de usuario no es válido.",
            "fr" to "Le nom d’utilisateur n’est pas valide.", "it" to "Il nome utente non è valido."),
        N_GESLO to mapOf("sl" to "Geslo ni veljavno.", "en" to "The password is not valid.", "de" to "Das Passwort ist ungültig.",
            "es" to "La contraseña no es válida.", "fr" to "Le mot de passe n’est pas valide.", "it" to "La password non è valida."),
        N_POVEZAV to mapOf("sl" to "Število povezav mora biti od 1 do 100.", "en" to "The number of connections must be from 1 to 100.",
            "de" to "Die Anzahl der Verbindungen muss zwischen 1 und 100 liegen.",
            "es" to "El número de conexiones debe estar entre 1 y 100.",
            "fr" to "Le nombre de connexions doit être compris entre 1 et 100.",
            "it" to "Il numero di connessioni deve essere compreso tra 1 e 100."),
        N_IME to mapOf("sl" to "Ime je predolgo ali vsebuje nedovoljene znake.", "en" to "The name is too long or contains invalid characters.",
            "de" to "Der Name ist zu lang oder enthält ungültige Zeichen.", "es" to "El nombre es demasiado largo o contiene caracteres no válidos.",
            "fr" to "Le nom est trop long ou contient des caractères non valides.", "it" to "Il nome è troppo lungo o contiene caratteri non validi."),
        N_BREZ_TLS to mapOf("sl" to "Za povezavo brez TLS potrdi opozorilo.", "en" to "To connect without TLS, confirm the warning.",
            "de" to "Bestätige die Warnung, um ohne TLS zu verbinden.", "es" to "Para conectar sin TLS, confirma la advertencia.",
            "fr" to "Pour te connecter sans TLS, confirme l’avertissement.", "it" to "Per connetterti senza TLS, conferma l’avviso."),
        N_BREZ_TLS_DOMA to mapOf("sl" to "Brez TLS je dovoljen samo naslov IP v domačem omrežju.",
            "en" to "Without TLS only an IP address on your home network is allowed.",
            "de" to "Ohne TLS ist nur eine IP-Adresse im Heimnetz erlaubt.",
            "es" to "Sin TLS solo se permite una dirección IP de tu red doméstica.",
            "fr" to "Sans TLS, seule une adresse IP de ton réseau local est autorisée.",
            "it" to "Senza TLS è consentito solo un indirizzo IP della rete domestica."),
    )
}
