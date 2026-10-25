package si.safeer.tv.os

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32

/**
 * Pogon Usenet, korak 1 (lastnik, 25. 10. 2026: lasten pogon za neposredno predvajanje z Useneta): dekodiranje yEnc
 * (specifikacija yEnc 1.3, www.yenc.org) s preverbo CRC32. Cist objekt brez Androida in omrezja
 * (tests/UsenetYencTest.kt; pravila so skupna z namizjem: tests/skupni-primeri/usenet-pogon.json, funkcija yenc_dekodiraj).
 *
 * Vhod je telo clanka PO NNTP: podvojene pike je ze odstranil [UsenetNntp.telo] (skupni primeri: nntp_telo). Pravila:
 * - prva vrstica, ki se zacne z »=ybegin «; size obvezen; pri part mora naslednja vrstica biti »=ypart « z
 *   1 <= begin <= end <= size; del nad [NAJVEC_DELA] je zavrnjen pred dekodiranjem;
 * - podatki do prve vrstice »=yend «; CR in LF se izpustita povsod - tudi med »=« in ubeznim znakom (ubeznik na koncu
 *   vrstice velja za prvi znak naslednje); osamljen »=« pred =yend se izpusti;
 * - =yend part mora biti enak (ce ga ima =ybegin), dolzina enaka =yend size in pricakovani; pcrc32 (sicer pri
 *   enodelnem crc32) 1-8 heks.
 *
 * Napisano na novo po specifikaciji, ne iz GPL (sabyenc, SABnzbd). Zanka po ByteArray brez kopij, CRC32 iz
 * java.util.zip (strojno pospesen); hitrost: tests/UsenetYencTest.kt meritev (porocilo koraka 1).
 */
object UsenetYenc {
    /** Del (en clanek) nad 16 MiB je pokvarjen ali zlonameren vhod (skupni primeri: del_bajtov). */
    const val NAJVEC_DELA = 16 * 1024 * 1024

    /** Imena so ista kot v skupnih primerih (izid v malih crkah). */
    enum class Napaka {
        /** Ni vrstice =ybegin (clanek ni yEnc). */
        NI_YENC,
        /** Pokvarjena ali manjkajoca polja v =ybegin, =ypart ali =yend, neveljaven zapis CRC. */
        NAPAKA_OBLIKE,
        /** Del vecji od [NAJVEC_DELA]. */
        PREVELIK,
        /** Ni vrstice =yend - clanek je odrezan. */
        PRIREZANO,
        /** Dekodiranih bajtov ni toliko, kot pravi glava. */
        NAPAKA_VELIKOSTI,
        /** CRC32 se ne ujema. */
        NAPAKA_CRC,
    }

    /** Napaka dekodiranja; sporocilo nima vsebine clanka ne imena datoteke. */
    class NapakaYenc(val vrsta: Napaka) : Exception("yEnc: " + vrsta.name)

    /**
     * Dekodiran clanek. [zacetek] in [konec] sta odmika v datoteki od 0 (konec izkljucno; v skupnih primerih je zacetek
     * od 1 in konec vkljucno - ista stevilka). [del] in [delov] sta null, ce ju glava nima. [ime] je ze varno ime
     * ([UsenetNzb.varnoIme]) ali null. [crcPreverjen] = glava je imela CRC tega dela in se je ujemal.
     */
    class Del(
        val ime: String?,
        val del: Int?,
        val delov: Int?,
        val velikostDatoteke: Long,
        val zacetek: Long,
        val konec: Long,
        val podatki: ByteArray,
        val crc32: Long,
        val crcPreverjen: Boolean,
    ) {
        override fun toString() = "Del($del/$delov, $zacetek-$konec, ${podatki.size} B)"
    }

    private const val LF: Byte = 10
    private const val CR: Byte = 13
    private const val ENAKO: Byte = 61

    /** Dekodira telo clanka yEnc iz [b] med [od] in [doo]. Ob napaki [NapakaYenc]. */
    fun dekodiraj(b: ByteArray, od: Int = 0, doo: Int = b.size): Del {
        require(od in 0..doo && doo <= b.size)
        val zacetekGlave = najdiVrstico(b, od, doo, "=ybegin ") ?: throw NapakaYenc(Napaka.NI_YENC)
        val konecGlave = konecVrstice(b, zacetekGlave, doo) ?: throw NapakaYenc(Napaka.PRIREZANO)
        val glava = polja(besedilo(b, zacetekGlave + 8, konecGlave))
        val velikost = glava["size"]?.toLongOrNull()?.takeIf { it >= 0 } ?: throw NapakaYenc(Napaka.NAPAKA_OBLIKE)
        val del = glava["part"]?.let { it.toIntOrNull()?.takeIf { d -> d >= 1 } ?: throw NapakaYenc(Napaka.NAPAKA_OBLIKE) }
        val delov = glava["total"]?.let { it.toIntOrNull()?.takeIf { d -> d >= 1 } ?: throw NapakaYenc(Napaka.NAPAKA_OBLIKE) }
        val ime = glava["name"]?.let { UsenetNzb.varnoIme(ime(it)) }

        var zacetek = 0L
        var konec = velikost
        var lf = konecGlave           // indeks LF, ki konca zadnjo vrstico glave
        if (del != null) {
            // Vecdelni clanek: naslednja vrstica mora biti =ypart begin=... end=... (od 1, konec vkljucno).
            val zp = lf + 1
            if (!jePredpona(b, zp, doo, "=ypart ")) throw NapakaYenc(Napaka.NAPAKA_OBLIKE)
            val kp = konecVrstice(b, zp, doo) ?: throw NapakaYenc(Napaka.PRIREZANO)
            val p = polja(besedilo(b, zp + 7, kp))
            val zac = p["begin"]?.toLongOrNull() ?: throw NapakaYenc(Napaka.NAPAKA_OBLIKE)
            val kon = p["end"]?.toLongOrNull() ?: throw NapakaYenc(Napaka.NAPAKA_OBLIKE)
            if (zac < 1 || kon < zac || kon > velikost) throw NapakaYenc(Napaka.NAPAKA_OBLIKE)
            zacetek = zac - 1
            konec = kon
            lf = kp
        }
        val pricakovano = konec - zacetek
        if (pricakovano > NAJVEC_DELA) throw NapakaYenc(Napaka.PREVELIK)
        // Dekodiranih bajtov ni nikoli vec kot surovih - pokvarjena glava ne sme zahtevati velikega pomnilnika.
        val izhod = ByteArray(minOf(pricakovano, (doo - lf).toLong()).toInt())
        var o = 0
        var prevec = false
        var i = lf + 1
        var konecGlavePodatkov = -1
        var ubeznik = false
        while (i < doo) {
            // Zacetek vrstice: »=yend « konca podatke (cakajoci ubeznik se izpusti).
            if (b[i] == ENAKO && jePredpona(b, i, doo, "=yend ")) {
                konecGlavePodatkov = i
                break
            }
            var c = b[i]
            while (c != LF) {
                if (c != CR) {
                    if (ubeznik) {
                        ubeznik = false
                        if (o < izhod.size) izhod[o++] = (c - 106).toByte() else prevec = true
                    } else if (c == ENAKO) {
                        ubeznik = true
                    } else {
                        if (o < izhod.size) izhod[o++] = (c - 42).toByte() else prevec = true
                    }
                }
                i++
                if (i == doo) break
                c = b[i]
            }
            i++
        }
        if (konecGlavePodatkov < 0) throw NapakaYenc(Napaka.PRIREZANO)
        val ke = konecVrstice(b, konecGlavePodatkov, doo) ?: doo
        val k = polja(besedilo(b, konecGlavePodatkov + 6, ke))
        // =yend part se primerja samo, ce ga ima tudi =ybegin (skupni primeri v2).
        if (del != null) k["part"]?.let { if (it.toIntOrNull() != del) throw NapakaYenc(Napaka.NAPAKA_OBLIKE) }
        if (prevec || o.toLong() != pricakovano) throw NapakaYenc(Napaka.NAPAKA_VELIKOSTI)
        k["size"]?.let {
            val s = it.toLongOrNull() ?: throw NapakaYenc(Napaka.NAPAKA_OBLIKE)
            if (s != pricakovano) throw NapakaYenc(Napaka.NAPAKA_VELIKOSTI)
        }
        val crc = CRC32().apply { update(izhod, 0, o) }.value
        // pcrc32 je CRC tega dela; crc32 je pri vecdelnem CRC cele datoteke (tu se ne preveri), pri enodelnem tega dela.
        val zapisan = k["pcrc32"] ?: if (del == null) k["crc32"] else null
        var preverjen = false
        if (zapisan != null) {
            val z = crcIz(zapisan) ?: throw NapakaYenc(Napaka.NAPAKA_OBLIKE)
            if (z != crc) throw NapakaYenc(Napaka.NAPAKA_CRC)
            preverjen = true
        }
        return Del(ime, del, delov, velikost, zacetek, konec, izhod, crc, preverjen)
    }

    /** CRC32 iz zapisa heks: 1-8 znakov 0-9, a-f, A-F (brez »0x«); null = neveljaven zapis. */
    fun crcIz(s: String): Long? {
        if (s.isEmpty() || s.length > 8 || !s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return s.toLong(16)
    }

    /**
     * Polja vrstice s kljucno besedo: »part=1 total=10 line=128 size=500000 name=moje ime.mkv«. Ime je zadnje in zajame
     * ostanek vrstice (s presledki); ostala polja so loceni pari kljuc=vrednost (prvi zapis velja).
     */
    fun polja(vrstica: String): Map<String, String> {
        val m = HashMap<String, String>()
        var ostanek = vrstica
        val n = IME.find(vrstica)
        if (n != null) {
            m["name"] = vrstica.substring(n.range.last + 1)
            ostanek = vrstica.substring(0, n.range.first)
        }
        for (del in ostanek.trim().split(PRESLEDKI)) {
            val e = del.indexOf('=')
            if (e > 0) m.putIfAbsent(del.substring(0, e).lowercase(), del.substring(e + 1))
        }
        return m
    }

    private val IME = Regex("(^|\\s)name=")
    private val PRESLEDKI = Regex("\\s+")

    /** Ime iz glave: bajti so v nizu kot latin-1; ce so veljaven UTF-8, je to UTF-8. */
    private fun ime(surovo: String): String {
        val bajti = surovo.toByteArray(Charsets.ISO_8859_1)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bajti)).toString()
        } catch (_: CharacterCodingException) {
            surovo
        }
    }

    /** Besedilo vrstice glave kot latin-1 (bajt = znak), brez CR na koncu. */
    private fun besedilo(b: ByteArray, od: Int, lf: Int): String {
        var k = lf
        if (k > od && b[k - 1] == CR) k--
        return String(b, od, maxOf(0, k - od), Charsets.ISO_8859_1)
    }

    /** Indeks LF, ki konca vrstico od [od]; null = vrstica ni koncana. */
    private fun konecVrstice(b: ByteArray, od: Int, doo: Int): Int? {
        var i = od
        while (i < doo) {
            if (b[i] == LF) return i
            i++
        }
        return null
    }

    private fun jePredpona(b: ByteArray, od: Int, doo: Int, p: String): Boolean {
        if (od < 0 || od + p.length > doo) return false
        for (k in p.indices) if (b[od + k] != p[k].code.toByte()) return false
        return true
    }

    /** Zacetek prve vrstice, ki se zacne s [p] (pred =ybegin so lahko prazne vrstice ali besedilo). */
    private fun najdiVrstico(b: ByteArray, od: Int, doo: Int, p: String): Int? {
        var i = od
        while (i < doo) {
            if (jePredpona(b, i, doo, p)) return i
            val lf = konecVrstice(b, i, doo) ?: return null
            i = lf + 1
        }
        return null
    }
}
