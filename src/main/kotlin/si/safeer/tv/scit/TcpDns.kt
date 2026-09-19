package si.safeer.tv.scit

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * DNS prek TCP v tunelu Safeer Scita.
 *
 * Razreševalec preide na TCP, kadar je odgovor po UDP prevelik (zastavica TC) ali kadar tako
 * zahteva omrezje. Ce takega prometa ne obravnavamo, poizvedba tiho pade v prazno in stran se
 * ne odpre - navidez nakljucno, kar je najhujsa vrsta napake.
 *
 * Nasproti nas ni pravega omrezja: drugi konec je jedro te naprave prek datotecnega opisnika
 * tunela, zato paketi ne gredo v izgubo in jih ni treba posiljati znova. Dovolj je preprost
 * potek: SYN -> SYN+ACK, prevzem vprasanja (dolzinska predpona + sporocilo DNS), odgovor v
 * kosih in FIN. Brez stanja jedra, brez moznosti v glavi, brez okenskega racunanja.
 *
 * Razred je namenoma brez Androida: posiljanje in razreševanje dobi od zunaj, zato ga je mogoce
 * v celoti preizkusiti na JVM ([TcpDnsTest]).
 */
class TcpDns(
    /** Zapise surov paket IP nazaj v tunel. */
    private val poslji: (ByteArray) -> Unit,
    /** Razresi sporocilo DNS; odgovor (ali null ob napaki) vrne prek povratnega klica. */
    private val razresi: (ByteArray, (ByteArray?) -> Unit) -> Unit,
    private val zdaj: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /** Najvecji kos tovora v enem segmentu (MTU tunela 1500 - glavi IPv6 in TCP). */
        const val KOS = 1200
        /** Dolgo mirujoca povezava se opusti. */
        const val POTEK_MS = 20_000L
        /** Vec kot toliko hkratnih povezav ne drzimo. */
        const val NAJVEC = 32
        /** Najvecje sporocilo DNS prek TCP (RFC: dolzinska predpona je 16-bitna). */
        const val NAJVECJE_SPOROCILO = 65_535
    }

    private class Povezava(val vzorec: TcpPaket.Segment, var nasSeq: Long, var pricakovan: Long) {
        val vhod = ByteArrayOutputStream()
        var odgovorjeno = false
        var zaprto = false
        var cas = 0L
    }

    private val povezave = ConcurrentHashMap<String, Povezava>()
    private val nakljucni = SecureRandom()

    /** Koliko povezav je odprtih (za dnevnik in teste). */
    fun stevilo(): Int = povezave.size

    /**
     * Obravnavaj paket s tunela. Vrne true, ce je bil TCP (in smo ga prevzeli), sicer false,
     * da ga poskusi razumeti kdo drug.
     */
    @Synchronized
    fun naPaket(p: ByteArray, dolzina: Int = p.size): Boolean {
        val s = TcpPaket.razcleni(p, dolzina) ?: return false
        if (s.ciljnaVrata != 53) { odbij(s); return true }
        val kljuc = s.kljuc

        if (s.ima(TcpPaket.RST)) { povezave.remove(kljuc); return true }

        if (s.ima(TcpPaket.SYN) && !s.ima(TcpPaket.ACK)) {
            val obstojeca = povezave[kljuc]
            if (obstojeca != null && obstojeca.vzorec.seq == s.seq) {
                // Ponovljen SYN (nas SYN+ACK se je izgubil): odgovorimo enako kot prvic.
                poslji(TcpPaket.sestavi(s, TcpPaket.naprej(obstojeca.nasSeq, -1), obstojeca.pricakovan,
                    TcpPaket.SYN or TcpPaket.ACK))
                return true
            }
            if (povezave.size >= NAJVEC) naredimoProstor()
            val nasIsn = (nakljucni.nextInt().toLong() and 0xFFFFFFFFL)
            val c = Povezava(s, TcpPaket.naprej(nasIsn, 1), TcpPaket.naprej(s.seq, 1))
            c.cas = zdaj()
            povezave[kljuc] = c
            poslji(TcpPaket.sestavi(s, nasIsn, c.pricakovan, TcpPaket.SYN or TcpPaket.ACK))
            return true
        }

        val c = povezave[kljuc]
        if (c == null) { odbij(s); return true }
        c.cas = zdaj()

        if (s.tovor.isNotEmpty()) {
            if (s.seq == c.pricakovan) {
                c.vhod.write(s.tovor)
                c.pricakovan = TcpPaket.naprej(c.pricakovan, s.tovor.size.toLong())
                poslji(TcpPaket.sestavi(s, c.nasSeq, c.pricakovan, TcpPaket.ACK))
                preveriVprasanje(kljuc, c, s)
            } else {
                // Ponovljen ali neurejen segment: povemo, kje smo.
                poslji(TcpPaket.sestavi(s, c.nasSeq, c.pricakovan, TcpPaket.ACK))
            }
        }

        if (s.ima(TcpPaket.FIN)) {
            c.pricakovan = TcpPaket.naprej(c.pricakovan, 1)
            poslji(TcpPaket.sestavi(s, c.nasSeq, c.pricakovan, TcpPaket.ACK))
            if (c.zaprto || c.odgovorjeno) povezave.remove(kljuc)
            else if (c.vhod.size() == 0) { zapri(kljuc, c, s); }
        }
        return true
    }

    /** Ali je celo sporocilo DNS ze tu (dolzinska predpona + toliko bajtov)? */
    private fun preveriVprasanje(kljuc: String, c: Povezava, s: TcpPaket.Segment) {
        if (c.odgovorjeno) return
        val b = c.vhod.toByteArray()
        if (b.size < 2) return
        val dolzina = DnsPaket.u16(b, 0)
        if (dolzina < 12 || dolzina > NAJVECJE_SPOROCILO) { odbijPovezavo(kljuc, c, s); return }
        if (b.size < 2 + dolzina) return
        c.odgovorjeno = true
        val vprasanje = b.copyOfRange(2, 2 + dolzina)
        // Razreševanje tece v drugi niti; odgovor obravnavamo pod istim kljucem kot pakete,
        // da se stanje povezave ne spreminja z dveh strani hkrati.
        razresi(vprasanje) { odgovor ->
            synchronized(this) {
                if (odgovor == null) odbijPovezavo(kljuc, c, s) else posljiOdgovor(kljuc, c, s, odgovor)
            }
        }
    }

    private fun posljiOdgovor(kljuc: String, c: Povezava, s: TcpPaket.Segment, odgovor: ByteArray) {
        if (c.zaprto) return
        val celota = ByteArray(2 + odgovor.size)
        DnsPaket.put16(celota, 0, odgovor.size)
        System.arraycopy(odgovor, 0, celota, 2, odgovor.size)
        var i = 0
        while (i < celota.size) {
            val n = minOf(KOS, celota.size - i)
            poslji(TcpPaket.sestavi(s, c.nasSeq, c.pricakovan, TcpPaket.PSH or TcpPaket.ACK,
                celota.copyOfRange(i, i + n)))
            c.nasSeq = TcpPaket.naprej(c.nasSeq, n.toLong())
            i += n
        }
        zapri(kljuc, c, s)
    }

    /** Konec: posljemo FIN in povezavo pustimo se nekaj casa, da potrdimo odjemalcev FIN. */
    private fun zapri(kljuc: String, c: Povezava, s: TcpPaket.Segment) {
        if (c.zaprto) return
        c.zaprto = true
        poslji(TcpPaket.sestavi(s, c.nasSeq, c.pricakovan, TcpPaket.FIN or TcpPaket.ACK))
        c.nasSeq = TcpPaket.naprej(c.nasSeq, 1)
        c.cas = zdaj()
    }

    /** Napaka pri razreševanju ali pokvarjeno vprasanje: povezavo takoj podremo. */
    private fun odbijPovezavo(kljuc: String, c: Povezava, s: TcpPaket.Segment) {
        povezave.remove(kljuc)
        poslji(TcpPaket.sestavi(s, c.nasSeq, c.pricakovan, TcpPaket.RST or TcpPaket.ACK))
    }

    /** Segment brez povezave (ali na napacna vrata): odgovorimo z RST, da odjemalec ne caka. */
    private fun odbij(s: TcpPaket.Segment) {
        if (s.ima(TcpPaket.RST)) return
        val seq = if (s.ima(TcpPaket.ACK)) s.ack else 0L
        val ack = TcpPaket.naprej(s.seq, (s.tovor.size + (if (s.ima(TcpPaket.SYN)) 1 else 0) +
            (if (s.ima(TcpPaket.FIN)) 1 else 0)).toLong())
        poslji(TcpPaket.sestavi(s, seq, ack, TcpPaket.RST or TcpPaket.ACK))
    }

    /** Opusti povezave, ki predolgo mirujejo. */
    @Synchronized
    fun pocisti() {
        val t = zdaj()
        povezave.entries.removeIf { t - it.value.cas > POTEK_MS }
    }

    /** Ob polnem seznamu opusti najstarejso povezavo, ne vseh. */
    private fun naredimoProstor() {
        pocisti()
        while (povezave.size >= NAJVEC) {
            val najstarejsa = povezave.entries.minByOrNull { it.value.cas } ?: return
            povezave.remove(najstarejsa.key, najstarejsa.value)
        }
    }
}
