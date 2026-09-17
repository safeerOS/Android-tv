package si.safeer.tv.scit

/**
 * Surovi paketi s tunela Safeer Scita: IPv4/IPv6 + UDP + DNS.
 *
 * Skozi tunel pride samo promet do navideznega strezniku DNS (edina pot v tunelu), zato je vse, kar
 * tu razumemo, poizvedba DNS po UDP. Odgovor sestavimo sami (blokirano: NXDOMAIN) ali pa ga
 * dobimo od pravega streznika in ga zavijemo nazaj v IP/UDP z zamenjanima naslovoma.
 */
object DnsPaket {

    class Poizvedba(
        val razlicica: Int,          // 4 ali 6
        val izvor: ByteArray,        // naslov posiljatelja (4 ali 16 bajtov)
        val cilj: ByteArray,
        val izvornaVrata: Int,
        val ciljnaVrata: Int,
        val dns: ByteArray,          // tovor DNS
        val ime: String,             // vprasano ime (mala crke, brez koncne pike); prazno, ce ga ni
        val vrsta: Int,              // QTYPE
        val id: Int,                 // ID transakcije
    )

    /** Razclenitev paketa s tunela. Vrne null za vse, kar ni UDP s tovorom DNS. */
    fun razcleni(p: ByteArray, dolzina: Int = p.size): Poizvedba? {
        if (dolzina < 28) return null
        val razlicica = (p[0].toInt() ushr 4) and 0xF
        val izvor: ByteArray; val cilj: ByteArray; val udp: Int
        if (razlicica == 4) {
            val ihl = (p[0].toInt() and 0xF) * 4
            if (ihl < 20 || dolzina < ihl + 8) return null
            if ((p[9].toInt() and 0xFF) != 17) return null
            if ((u16(p, 6) and 0x1FFF) != 0 || (p[6].toInt() and 0x20) != 0) return null // fragmenti ne
            izvor = p.copyOfRange(12, 16); cilj = p.copyOfRange(16, 20); udp = ihl
        } else if (razlicica == 6) {
            if (dolzina < 48) return null
            if ((p[6].toInt() and 0xFF) != 17) return null // brez razsiritvenih glav
            izvor = p.copyOfRange(8, 24); cilj = p.copyOfRange(24, 40); udp = 40
        } else return null
        val izvornaVrata = u16(p, udp); val ciljnaVrata = u16(p, udp + 2)
        val udpDolzina = u16(p, udp + 4)
        if (udpDolzina < 8 || udp + udpDolzina > dolzina) return null
        val dns = p.copyOfRange(udp + 8, udp + udpDolzina)
        if (dns.size < 12) return null
        val id = u16(dns, 0)
        if ((dns[2].toInt() and 0x80) != 0) return null // odgovor, ne poizvedba
        val vprasanj = u16(dns, 4)
        var ime = ""; var vrsta = 0
        if (vprasanj >= 1) {
            val (i, konec) = preberiIme(dns, 12) ?: return null
            ime = i
            if (konec + 4 <= dns.size) vrsta = u16(dns, konec)
        }
        return Poizvedba(razlicica, izvor, cilj, izvornaVrata, ciljnaVrata, dns, ime, vrsta, id)
    }

    /** Kopija tovora DNS z drugim ID-jem transakcije (navzgor posljemo svojega, ne od aplikacije). */
    fun zId(dns: ByteArray, id: Int): ByteArray {
        val k = dns.copyOf()
        put16(k, 0, id)
        return k
    }

    /**
     * Vprasanje iz odgovora streznika: (ime, vrsta). Uporabimo ga za preverjanje, da odgovor
     * res pripada nasi poizvedbi - sam ID transakcije za to ni dovolj.
     */
    fun vprasanjeOdgovora(d: ByteArray, dolzina: Int = d.size): Pair<String, Int>? {
        if (dolzina < 12 || u16(d, 4) < 1) return null
        val (ime, konec) = preberiIme(d, 12) ?: return null
        if (konec + 4 > dolzina) return null
        return ime to u16(d, konec)
    }

    /** Ime iz odseka vprasanja (brez kazalcev v vprasanju). Vrne (ime, odmik za imenom). */
    fun preberiIme(d: ByteArray, zacetek: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var i = zacetek
        var oznak = 0
        while (i < d.size) {
            val len = d[i].toInt() and 0xFF
            if (len == 0) { i++; break }
            if (len and 0xC0 != 0) return null // stisnjeno ime v vprasanju ni dovoljeno
            if (i + 1 + len > d.size || ++oznak > 127) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 0 until len) sb.append((d[i + 1 + k].toInt() and 0xFF).toChar())
            i += 1 + len
        }
        if (i > d.size) return null
        return sb.toString().lowercase() to i
    }

    /** Odgovor NXDOMAIN na poizvedbo: isti ID in vprasanje, brez zapisov. */
    fun odgovorBlokirano(q: Poizvedba): ByteArray {
        val vprasanjeKonec = (preberiIme(q.dns, 12)?.second ?: 12) + 4
        val n = minOf(vprasanjeKonec, q.dns.size)
        val o = ByteArray(n)
        System.arraycopy(q.dns, 0, o, 0, n)
        val rd = q.dns[2].toInt() and 0x01
        o[2] = (0x80 or rd).toByte()          // QR=1, opcode 0, AA=0, TC=0, RD kot v poizvedbi
        o[3] = (0x80 or 3).toByte()           // RA=1, RCODE=3 (NXDOMAIN)
        o[4] = 0; o[5] = if (n >= 16) 1 else 0 // QDCOUNT
        o[6] = 0; o[7] = 0; o[8] = 0; o[9] = 0; o[10] = 0; o[11] = 0
        return o
    }

    /** Zavije tovor DNS v UDP + IP paket nazaj k posiljatelju poizvedbe (naslovi in vrata zamenjani). */
    fun zavijOdgovor(q: Poizvedba, dns: ByteArray): ByteArray {
        val udpDolzina = 8 + dns.size
        return if (q.razlicica == 4) {
            val p = ByteArray(20 + udpDolzina)
            p[0] = 0x45; p[1] = 0
            put16(p, 2, p.size); put16(p, 4, 0); put16(p, 6, 0x4000) // DF
            p[8] = 64; p[9] = 17
            System.arraycopy(q.cilj, 0, p, 12, 4); System.arraycopy(q.izvor, 0, p, 16, 4)
            put16(p, 10, kontrolnaVsota(p, 0, 20, 0))
            zapisiUdp(p, 20, q, dns)
            p
        } else {
            val p = ByteArray(40 + udpDolzina)
            p[0] = 0x60
            put16(p, 4, udpDolzina); p[6] = 17; p[7] = 64
            System.arraycopy(q.cilj, 0, p, 8, 16); System.arraycopy(q.izvor, 0, p, 24, 16)
            zapisiUdp(p, 40, q, dns)
            p
        }
    }

    private fun zapisiUdp(p: ByteArray, odmik: Int, q: Poizvedba, dns: ByteArray) {
        val dolzina = 8 + dns.size
        put16(p, odmik, q.ciljnaVrata); put16(p, odmik + 2, q.izvornaVrata)
        put16(p, odmik + 4, dolzina); put16(p, odmik + 6, 0)
        System.arraycopy(dns, 0, p, odmik + 8, dns.size)
        // psevdoglava: izvor (= q.cilj), cilj (= q.izvor), protokol 17, dolzina UDP
        var vsota = 0L
        vsota += sestej(q.cilj, 0, q.cilj.size) + sestej(q.izvor, 0, q.izvor.size)
        vsota += 17 + dolzina
        var k = kontrolnaVsota(p, odmik, dolzina, vsota)
        if (k == 0) k = 0xFFFF
        put16(p, odmik + 6, k)
    }

    private fun sestej(b: ByteArray, od: Int, dolzina: Int): Long {
        var s = 0L
        var i = od
        val konec = od + dolzina
        while (i + 1 < konec) { s += u16(b, i); i += 2 }
        if (i < konec) s += (b[i].toInt() and 0xFF) shl 8
        return s
    }

    /** Internetna kontrolna vsota (RFC 1071) nad odsekom, z zacetnim sestevkom psevdoglave. */
    fun kontrolnaVsota(b: ByteArray, od: Int, dolzina: Int, zacetek: Long): Int {
        var s = zacetek + sestej(b, od, dolzina)
        while (s ushr 16 != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return (s.inv() and 0xFFFF).toInt()
    }

    fun u16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    fun put16(b: ByteArray, i: Int, v: Int) { b[i] = (v ushr 8).toByte(); b[i + 1] = v.toByte() }

    /** Sestavi poizvedbo DNS (za teste in za preverjanje streznika): ID, ime, vrsta. */
    fun sestaviPoizvedbo(id: Int, ime: String, vrsta: Int = 1): ByteArray {
        val deli = DomenskiNabor.normaliziraj(ime).split('.')
        val imeBajti = deli.sumOf { it.length + 1 } + 1
        val d = ByteArray(12 + imeBajti + 4)
        put16(d, 0, id); d[2] = 0x01; d[3] = 0
        put16(d, 4, 1)
        var i = 12
        for (del in deli) { d[i++] = del.length.toByte(); for (c in del) d[i++] = c.code.toByte() }
        d[i++] = 0
        put16(d, i, vrsta); put16(d, i + 2, 1)
        return d
    }

    /** Sestavi surov paket s poizvedbo (za teste): IPv4 ali IPv6 + UDP + DNS. */
    fun sestaviPaket(razlicica: Int, izvor: ByteArray, cilj: ByteArray, izvornaVrata: Int, ciljnaVrata: Int, dns: ByteArray): ByteArray {
        val q = Poizvedba(razlicica, cilj, izvor, ciljnaVrata, izvornaVrata, dns, "", 0, 0) // zavijOdgovor zamenja smer
        return zavijOdgovor(q, dns)
    }
}
