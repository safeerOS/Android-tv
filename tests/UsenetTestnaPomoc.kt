package si.safeer.tv.os

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Pomoc preizkusom pogona Usenet: kodirnik yEnc po specifikaciji 1.3 (samo za preizkuse - aplikacija yEnc samo bere)
 * in podvajanje pik NNTP (RFC 3977 3.1.1), kot ga naredi streznik.
 */
internal object YencKodirnik {
    fun kodiraj(
        podatki: ByteArray, ime: String, del: Int = 0, delov: Int = 0, zacetek: Long = 0,
        velikost: Long = podatki.size.toLong(), vrstica: Int = 128, ubeziPiko: Boolean = false, crlf: Boolean = true,
    ): ByteArray {
        val o = ByteArrayOutputStream(podatki.size + podatki.size / 30 + 1024)
        val nl = if (crlf) "\r\n" else "\n"
        fun pisi(s: String) = o.write(s.toByteArray(Charsets.UTF_8))
        if (del > 0) {
            pisi("=ybegin part=$del total=$delov line=$vrstica size=$velikost name=$ime$nl")
            pisi("=ypart begin=${zacetek + 1} end=${zacetek + podatki.size}$nl")
        } else {
            pisi("=ybegin line=$vrstica size=$velikost name=$ime$nl")
        }
        var stolpec = 0
        for (i in podatki.indices) {
            val e = (podatki[i] + 42) and 0xFF
            val robni = stolpec == 0 || stolpec >= vrstica - 1
            val ubezi = e == 0 || e == 10 || e == 13 || e == 61 || ((e == 9 || e == 32) && robni) || (ubeziPiko && e == 46 && stolpec == 0)
            if (ubezi) {
                o.write(61)
                o.write((e + 64) and 0xFF)
                stolpec += 2
            } else {
                o.write(e)
                stolpec++
            }
            if (stolpec >= vrstica) {
                pisi(nl)
                stolpec = 0
            }
        }
        if (stolpec > 0) pisi(nl)
        val crc = String.format("%08x", CRC32().apply { update(podatki) }.value)
        if (del > 0) pisi("=yend size=${podatki.size} part=$del pcrc32=$crc$nl") else pisi("=yend size=${podatki.size} crc32=$crc$nl")
        return o.toByteArray()
    }

    /** NNTP: pika na zacetku vrstice se podvoji (streznik pred posiljanjem). */
    fun podvojiPike(b: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(b.size + b.size / 100 + 16)
        var zacetek = true
        for (c in b) {
            if (zacetek && c == '.'.code.toByte()) o.write('.'.code)
            o.write(c.toInt())
            zacetek = c == '\n'.code.toByte()
        }
        return o.toByteArray()
    }
}
