package si.safeer.tv.scit

/**
 * Segmenti TCP s tunela Safeer Scita (IPv4/IPv6 + TCP).
 *
 * Skozi tunel pride samo promet do navideznega streznika DNS, zato je edini TCP, ki ga tu vidimo,
 * DNS prek TCP (vrata 53). Razreševalec ga uporabi, kadar je odgovor po UDP prevelik (zastavica TC)
 * ali kadar tako zahteva omrezje; ce takega segmenta ne razumemo, poizvedba tiho pade v prazno.
 *
 * Tu je samo branje in sestavljanje paketov - brez stanja. Stanje povezav je v [TcpDns].
 */
object TcpPaket {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10

    /** Privzeto okno: promet ne gre nikamor razen v jedro te naprave, zato je lahko veliko. */
    private const val OKNO = 65535

    class Segment(
        val razlicica: Int,          // 4 ali 6
        val izvor: ByteArray,        // naslov aplikacije
        val cilj: ByteArray,         // navidezni streznik DNS
        val izvornaVrata: Int,
        val ciljnaVrata: Int,
        val seq: Long,
        val ack: Long,
        val zastavice: Int,
        val tovor: ByteArray,
    ) {
        fun ima(zastavica: Int): Boolean = (zastavice and zastavica) != 0
        /** Kljuc povezave: ena aplikacija, ena vrata. */
        val kljuc: String get() = izvor.joinToString(".") { (it.toInt() and 0xFF).toString() } + ":" + izvornaVrata
    }

    /** Razclenitev segmenta s tunela. Vrne null za vse, kar ni TCP. */
    fun razcleni(p: ByteArray, dolzina: Int = p.size): Segment? {
        if (dolzina < 40) return null
        val razlicica = (p[0].toInt() ushr 4) and 0xF
        val izvor: ByteArray; val cilj: ByteArray; val tcp: Int; val konecIp: Int
        if (razlicica == 4) {
            val ihl = (p[0].toInt() and 0xF) * 4
            if (ihl < 20 || dolzina < ihl + 20) return null
            if ((p[9].toInt() and 0xFF) != 6) return null
            if ((DnsPaket.u16(p, 6) and 0x1FFF) != 0 || (p[6].toInt() and 0x20) != 0) return null // fragmenti ne
            val skupna = DnsPaket.u16(p, 2)
            if (skupna < ihl + 20 || skupna > dolzina) return null
            izvor = p.copyOfRange(12, 16); cilj = p.copyOfRange(16, 20); tcp = ihl; konecIp = skupna
        } else if (razlicica == 6) {
            if ((p[6].toInt() and 0xFF) != 6) return null   // brez razsiritvenih glav
            val tovorDolzina = DnsPaket.u16(p, 4)
            if (tovorDolzina < 20 || 40 + tovorDolzina > dolzina) return null
            izvor = p.copyOfRange(8, 24); cilj = p.copyOfRange(24, 40); tcp = 40; konecIp = 40 + tovorDolzina
        } else return null

        val izvornaVrata = DnsPaket.u16(p, tcp)
        val ciljnaVrata = DnsPaket.u16(p, tcp + 2)
        val seq = u32(p, tcp + 4)
        val ack = u32(p, tcp + 8)
        val glava = ((p[tcp + 12].toInt() ushr 4) and 0xF) * 4
        if (glava < 20 || tcp + glava > konecIp) return null
        val zastavice = p[tcp + 13].toInt() and 0x3F
        val tovor = p.copyOfRange(tcp + glava, konecIp)
        return Segment(razlicica, izvor, cilj, izvornaVrata, ciljnaVrata, seq, ack, zastavice, tovor)
    }

    /**
     * Sestavi segment v nasprotni smeri (od navideznega streznika k aplikaciji): naslova in vrata
     * zamenjamo, [seq] in [ack] pa sta nasa. Brez moznosti v glavi; okno je stalno.
     */
    fun sestavi(s: Segment, seq: Long, ack: Long, zastavice: Int, tovor: ByteArray = PRAZNO): ByteArray {
        val tcpDolzina = 20 + tovor.size
        val p: ByteArray
        val odmik: Int
        if (s.razlicica == 4) {
            p = ByteArray(20 + tcpDolzina)
            odmik = 20
            p[0] = 0x45; p[1] = 0
            DnsPaket.put16(p, 2, p.size); DnsPaket.put16(p, 4, 0); DnsPaket.put16(p, 6, 0x4000) // DF
            p[8] = 64; p[9] = 6
            System.arraycopy(s.cilj, 0, p, 12, 4); System.arraycopy(s.izvor, 0, p, 16, 4)
            DnsPaket.put16(p, 10, DnsPaket.kontrolnaVsota(p, 0, 20, 0))
        } else {
            p = ByteArray(40 + tcpDolzina)
            odmik = 40
            p[0] = 0x60
            DnsPaket.put16(p, 4, tcpDolzina); p[6] = 6; p[7] = 64
            System.arraycopy(s.cilj, 0, p, 8, 16); System.arraycopy(s.izvor, 0, p, 24, 16)
        }
        DnsPaket.put16(p, odmik, s.ciljnaVrata); DnsPaket.put16(p, odmik + 2, s.izvornaVrata)
        put32(p, odmik + 4, seq); put32(p, odmik + 8, ack)
        p[odmik + 12] = (5 shl 4).toByte()      // dolzina glave 20 bajtov
        p[odmik + 13] = zastavice.toByte()
        DnsPaket.put16(p, odmik + 14, OKNO)
        System.arraycopy(tovor, 0, p, odmik + 20, tovor.size)
        // psevdoglava: izvor (= s.cilj), cilj (= s.izvor), protokol 6, dolzina TCP
        var vsota = DnsPaket.sestej(s.cilj, 0, s.cilj.size) + DnsPaket.sestej(s.izvor, 0, s.izvor.size)
        vsota += 6 + tcpDolzina
        DnsPaket.put16(p, odmik + 16, DnsPaket.kontrolnaVsota(p, odmik, tcpDolzina, vsota))
        return p
    }

    fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
            ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)

    fun put32(b: ByteArray, i: Int, v: Long) {
        b[i] = (v ushr 24).toByte(); b[i + 1] = (v ushr 16).toByte()
        b[i + 2] = (v ushr 8).toByte(); b[i + 3] = v.toByte()
    }

    /** Zaporedne stevilke TCP tecejo po 32 bitih in se prelijejo. */
    fun naprej(seq: Long, koliko: Long): Long = (seq + koliko) and 0xFFFFFFFFL

    private val PRAZNO = ByteArray(0)
}
