package si.safeer.tv.os

import java.net.URLDecoder

/**
 * Cista pravila magnet povezav (brez Androida in libtorrent): preverba, hash, ociscenje za libtorrent.
 * Neodvisni pregled 12. 11. 2026: (S1) URLDecoder je ob »%zz« metal izjemo in sesul aplikacijo; (S2) naslov brez stirih pik
 * (127.1, 0x7f.0.0.1, 192.168.1) je zaobsel blokado domacega omrezja.
 */
object MagnetPravila {
    private val BTIH = Regex("urn:btih:([0-9a-fA-F]{40}|[A-Za-z2-7]{32})$")

    /** Najvec sledilnikov v povezavi (S3: dodatek je lahko navedel poljubno stevilo; dolg magnet hash() zavrne nad 8192 znakov). */
    const val NAJVEC_SLEDILNIKOV = 20
    const val NAJVEC_IMENA = 200

    /**
     * Ime iz dn= ali filename: brez kontrolnih znakov (Cc) in znakov oblike (Cf, npr. U+202E obrne smer besedila in lahko
     * skrije koncnico - neodvisni pregled 12. 11. 2026, N2), presledki strnjeni, najvec [NAJVEC_IMENA] znakov.
     */
    fun ocistiIme(ime: String): String {
        val sb = StringBuilder()
        for (z in ime) {
            val t = Character.getType(z)
            if (t == Character.CONTROL.toInt() || t == Character.FORMAT.toInt()) sb.append(' ') else sb.append(z)
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim().take(NAJVEC_IMENA)
    }

    /**
     * Magnet iz toka dodatka (Stremio `infoHash`, `filename`, `sources`): hash mora biti 40 heks ali 32 base32 znakov (prej se je
     * vstavil kar koli, tudi »&tr=...«), ime ociscen, sledilnikov najvec [NAJVEC_SLEDILNIKOV] (samo z oznako »tracker:« in brez
     * kontrolnih znakov); null, ce hash ni veljaven.
     */
    fun izDodatka(infoHash: String, ime: String, viri: List<String>): String? {
        val h = infoHash.trim()
        if (!(Regex("[0-9a-fA-F]{40}").matches(h) || Regex("[A-Za-z2-7]{32}").matches(h))) return null
        fun kod(x: String) = java.net.URLEncoder.encode(x, "UTF-8").replace("+", "%20")
        val dn = ocistiIme(ime)
        val tr = viri.asSequence().filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:").trim() }
            .filter { it.isNotEmpty() && it.length <= 512 && it.none { z -> Character.isISOControl(z) || z == ' ' } }
            .distinct().take(NAJVEC_SLEDILNIKOV).toList()
        return "magnet:?xt=urn:btih:$h" + (if (dn.isNotEmpty()) "&dn=" + kod(dn) else "") + tr.joinToString("") { "&tr=" + kod(it) }
    }

    /** Dekodiranje, ki ob neveljavnem % zaporedju vrne null namesto izjeme. */
    private fun dekodiraj(v: String): String? = try { URLDecoder.decode(v, "UTF-8") } catch (_: IllegalArgumentException) { null }

    /** Hash (40 hex) iz veljavne magnet povezave ali null. */
    fun hash(uri: String): String? {
        val u = uri.trim()
        if (!u.startsWith("magnet:?", ignoreCase = true) || u.length > 8192 || u.any { it == '\n' || it == '\r' || it == ' ' || it == '\t' }) return null
        for (del in u.substring(8).split('&')) {
            val (k, v) = del.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (k != "xt") continue
            val m = BTIH.find(dekodiraj(v) ?: continue) ?: continue
            val h = m.groupValues[1]
            return if (h.length == 40) h.lowercase() else base32vHex(h)
        }
        return null
    }

    private fun base32vHex(b32: String): String {
        val abeceda = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var biti = 0; var vrednost = 0
        val izhod = StringBuilder()
        for (c in b32.uppercase()) {
            vrednost = (vrednost shl 5) or abeceda.indexOf(c)
            biti += 5
            if (biti >= 8) { biti -= 8; izhod.append("%02x".format((vrednost shr biti) and 0xff)) }
        }
        return izhod.toString()
    }

    /**
     * Ocisceno povezava za libtorrent: xt, dn in samo javni sledilniki (brez naslovov v domacem omrezju - tuja povezava ne
     * sme usmerjati zahtev na usmerjevalnik). Brez sledilnikov dodamo [privzeti]. Neveljavno % zaporedje v dn/tr se preskoci.
     */
    fun zSledilniki(uri: String, privzeti: List<String>): String {
        val h = hash(uri) ?: return uri
        val deli = uri.substringAfter('?', "").split('&').mapNotNull {
            val k = it.substringBefore('=', ""); if (k.isEmpty()) null else dekodiraj(it.substringAfter('='))?.let { v -> k to v }
        }
        val ime = deli.firstOrNull { it.first == "dn" }?.second.orEmpty().let { ocistiIme(it) }
        val sledilniki = deli.filter { it.first == "tr" && javenSledilnik(it.second) }.map { it.second }.take(NAJVEC_SLEDILNIKOV)
            .ifEmpty { privzeti }
        fun kod(x: String) = java.net.URLEncoder.encode(x, "UTF-8")
        return "magnet:?xt=urn:btih:$h" + (if (ime.isNotEmpty()) "&dn=" + kod(ime) else "") +
            sledilniki.joinToString("") { "&tr=" + kod(it) }
    }

    private val CETVERICA = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** Zadnja oznaka je stevilka ali 0x...: getaddrinfo to razume kot naslov IP (127.1, 0x7f.0.0.1, 192.168.1, 2130706433). */
    private fun videzStevilke(gostitelj: String): Boolean {
        val zadnja = gostitelj.substringAfterLast('.')
        return zadnja.isNotEmpty() && (zadnja.all { it.isDigit() } || zadnja.startsWith("0x"))
    }

    fun javenSledilnik(t: String): Boolean {
        if (!(t.startsWith("udp://") || t.startsWith("http://") || t.startsWith("https://"))) return false
        val gostitelj = try { java.net.URI(t).host?.lowercase()?.trimEnd('.') } catch (_: Throwable) { null } ?: return false
        if (gostitelj.isEmpty() || gostitelj == "localhost" || '.' !in gostitelj && ':' !in gostitelj) return false
        if (listOf(".localhost", ".local", ".lan", ".home", ".internal", ".home.arpa").any { gostitelj.endsWith(it) }) return false
        val literal = CETVERICA.matches(gostitelj) || ':' in gostitelj
        // Skrajsani ali sestnajstiski zapis naslova: ne razresujemo, zavrnemo (pregled S2).
        if (!literal && videzStevilke(gostitelj)) return false
        if (!literal) return true
        return try {
            val a = java.net.InetAddress.getByName(gostitelj.trim('[', ']'))
            val ula = a is java.net.Inet6Address && (a.address[0].toInt() and 0xFE) == 0xFC
            !(a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress || ula ||
                (a.address.size == 4 && (a.address[0].toInt() and 0xFF) == 100 && (a.address[1].toInt() and 0xC0) == 64))
        } catch (_: Throwable) { false }
    }
}
