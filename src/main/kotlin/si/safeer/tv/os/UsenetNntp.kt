package si.safeer.tv.os

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import si.safeer.tv.os.UsenetPonudnikPravila as P

/**
 * Naloga 42 G (27. 10. 2026): prijava NNTP pri uporabnikovem ponudniku Usenet na Androidu - za gumb »Preveri« v oknu
 * »Moji viri › Usenet ponudnik« ([UsenetPonudnik.preveri]). Odzivi strezniku so prevedeni iz core/usenet_nntp.py
 * (izid_odgovora, meja_povezav, koda_vrstice; skupni primeri tests/skupni-primeri/usenet-nntp-izid.json).
 *
 * - Pred vsako povezavo trda meja povezav na RACUN ([P.MejeRacunov.zasedi]), ob zaprtju sprosti - tudi ob napaki.
 * - TLS samo s sistemsko zbirko potrdil, preverbo imena in TLS 1.2/1.3 ([P.varniParametri]).
 * - Geslo gre samo v ukaz AUTHINFO PASS (enkrat na povezavo); nikoli v dnevnik, izjemo ali toString. Brez ponovitev:
 *   zavrnjena prijava se ne ponovi (zapah racuna je v [P.ZapahRacunov]).
 * Brez Androida (preizkus z laznim streznikom NNTP: tests/UsenetNntpTest.kt).
 */
object UsenetNntp {
    const val ROK_POVEZAVE_MS = 10_000
    const val ROK_ZAHTEVE_MS = 30_000
    /** RFC 3977: odgovor najvec 512 oktetov - daljsa vrstica je napaka ali napad. */
    const val NAJVEC_VRSTICA_BAJTOV = 4096

    /** Odgovor na AUTHINFO USER: streznik zeli geslo (381). */
    const val GESLO = "geslo"
    const val NI_CLANKA = "ni_clanka"

    private val OK_KODE = mapOf("POZDRAV" to setOf(200, 201), "AUTHINFO USER" to setOf(281), "AUTHINFO PASS" to setOf(281),
        "ARTICLE" to setOf(220), "HEAD" to setOf(221), "BODY" to setOf(222), "STAT" to setOf(223), "QUIT" to setOf(205))
    private val CLANEK = setOf("ARTICLE", "HEAD", "BODY", "STAT")
    private val AUTHINFO = setOf("AUTHINFO USER", "AUTHINFO PASS")
    private val MEJA = listOf("too many", "limit", "max", "exceed", "no more")
    private val ZAKLEP = listOf("lock", "ban", "block", "attempt", "suspend", "expire", "disable")
    /** Besedilo o prijavi, geslu ali zaklepu ni nikoli meja povezav (dvom = zavrnjena prijava, ki se ne ponovi). */
    private val NI_MEJA = listOf("fail", "pass", "invalid", "incorrect", "wrong", "auth", "login", "log in", "logon", "sign in",
        "credential") + ZAKLEP

    /** Koda statusne vrstice: tri stevke s prvo 1-5, za njimi presledek ali konec; null = ni veljavna. */
    fun kodaVrstice(vrstica: String): Int? {
        if (vrstica.length < 3 || vrstica[0] !in "12345" || !vrstica.take(3).all { it in '0'..'9' }) return null
        if (vrstica.length > 3 && vrstica[3] != ' ') return null
        return vrstica.take(3).toInt()
    }

    fun mejaPovezav(besedilo: String): Boolean {
        val t = besedilo.lowercase()
        if (NI_MEJA.any { it in t }) return false
        return "connection limit" in t || ("connections" in t && MEJA.any { it in t })
    }

    /** Statusna vrstica odgovora na [ukaz] -> izid (isto kot izid_odgovora na namizju). */
    fun izidOdgovora(ukaz: String, vrstica: String): String {
        val koda = kodaVrstice(vrstica)
        val avtor = ukaz in AUTHINFO
        if (koda == null) return if (avtor) P.NNTP_PRIJAVA else P.NNTP_NAPAKA_PROTOKOLA
        if (koda in OK_KODE[ukaz].orEmpty()) return P.NNTP_OK
        if (ukaz == "AUTHINFO USER" && koda == 381) return GESLO
        if (koda in setOf(400, 480, 481, 482, 502) && mejaPovezav(vrstica.drop(4))) return P.NNTP_ZASEDEN
        if (avtor) return P.NNTP_PRIJAVA
        if (koda in setOf(423, 430) && ukaz in CLANEK) return NI_CLANKA
        if (koda in setOf(480, 481, 482)) return P.NNTP_PRIJAVA
        if (koda == 400) return if (ukaz == "POZDRAV") P.NNTP_NAPAKA_STREZNIKA else P.NNTP_ZAPRTO
        if (koda == 502) return P.NNTP_DOSTOP
        if (koda == 403) return P.NNTP_NAPAKA_STREZNIKA
        if (koda == 205) return P.NNTP_ZAPRTO
        return P.NNTP_NAPAKA_PROTOKOLA
    }

    /** Odpre povezavo do ponudnika. Privzeto [TLS]; preizkus poda lazni streznik brez TLS. */
    fun interface Povezovalnik {
        fun odpri(gostitelj: String, vrata: Int, protokoli: List<String>): Socket
    }

    /** TLS s sistemsko zbirko potrdil, preverbo imena (HTTPS) in samo dovoljenimi protokoli. */
    val TLS = Povezovalnik { gostitelj, vrata, protokoli ->
        val surova = Socket()
        try {
            surova.connect(InetSocketAddress(gostitelj, vrata), ROK_POVEZAVE_MS)
            val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(surova, gostitelj, vrata, true) as SSLSocket
            val parametri = P.varniParametri(s.sslParameters)
            val dovoljeni = parametri.protocols.filter { it in protokoli }
            if (dovoljeni.isEmpty()) throw P.NapakaPonudnika(P.NEVAREN_TLS)
            parametri.protocols = dovoljeni.toTypedArray()
            s.sslParameters = parametri
            s.soTimeout = ROK_ZAHTEVE_MS
            s.startHandshake()
            s
        } catch (e: Throwable) {
            try { surova.close() } catch (_: IOException) { }
            throw e
        }
    }

    private class Zaprto : IOException("zaprto")
    private class Predolgo : IOException("vrstica")

    private fun beriVrstico(vhod: InputStream): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = vhod.read()
            if (c < 0) throw Zaprto()
            if (c == '\n'.code) return b.toString("UTF-8").removeSuffix("\r")
            if (b.size() >= NAJVEC_VRSTICA_BAJTOV) throw Predolgo()
            b.write(c)
        }
    }

    private fun poslji(izhod: OutputStream, ukaz: String) {
        izhod.write((ukaz + "\r\n").toByteArray(Charsets.UTF_8))
        izhod.flush()
    }

    /** Izid napake vticnice v fazi [faza] - brez besedila izjeme (lahko vsebuje naslov). */
    private fun izNapake(e: Throwable, faza: String): P.Prijava = when (e) {
        is P.NapakaPonudnika -> throw e
        is SocketTimeoutException -> P.Prijava(P.NNTP_ROK, faza)
        is Zaprto -> P.Prijava(P.NNTP_ZAPRTO, faza)
        is Predolgo -> P.Prijava(P.NNTP_NAPAKA_PROTOKOLA, faza)
        is SSLException -> P.Prijava(if (faza == P.FAZA_POVEZAVA) P.NNTP_POTRDILO else P.NNTP_OMREZJE, faza)
        else -> P.Prijava(P.NNTP_OMREZJE, faza)
    }

    /**
     * ENA prijava (gumb »Preveri«): pozdrav, AUTHINFO USER, AUTHINFO PASS, QUIT. Vrne izid in fazo za
     * [P.preveri]/[P.ZapahRacunov]. Meja racuna dosezena: [P.NNTP_ZASEDEN] brez omrezja.
     */
    fun prijava(p: P.Ponudnik, geslo: P.Geslo, protokoli: List<String>, meje: P.MejeRacunov,
                povezovalnik: Povezovalnik = TLS): P.Prijava {
        // Ukaz z znakom za novo vrstico bi strezniku poslal dva ukaza - ponudnik in geslo sta ze preverjena, a za vsak slucaj.
        if (!P.varnaPoverilnica(p.uporabnik) || !P.varnaPoverilnica(geslo.vrednost())) return P.Prijava(P.NNTP_PRIJAVA, P.FAZA_PRIJAVA)
        if (!meje.zasedi(p)) return P.Prijava(P.NNTP_ZASEDEN, P.FAZA_POVEZAVA)
        var faza = P.FAZA_POVEZAVA
        var s: Socket? = null
        try {
            s = povezovalnik.odpri(p.gostitelj, p.vrata, protokoli)
            if (s.soTimeout <= 0) s.soTimeout = ROK_ZAHTEVE_MS   // brez roka bi tiho streznik zadrzal Preveri v nedogled
            val vhod = BufferedInputStream(s.getInputStream())
            val izhod = s.getOutputStream()
            val pozdrav = izidOdgovora("POZDRAV", beriVrstico(vhod))
            if (pozdrav != P.NNTP_OK) return P.Prijava(pozdrav, faza)
            faza = P.FAZA_PRIJAVA
            poslji(izhod, "AUTHINFO USER " + p.uporabnik)
            val uporabnik = izidOdgovora("AUTHINFO USER", beriVrstico(vhod))
            if (uporabnik != GESLO) return P.Prijava(uporabnik, faza)   // 281 brez gesla = ok, sicer zavrnjeno ali meja
            poslji(izhod, "AUTHINFO PASS " + geslo.vrednost())
            val izid = izidOdgovora("AUTHINFO PASS", beriVrstico(vhod))
            if (izid == P.NNTP_OK) try { poslji(izhod, "QUIT") } catch (_: IOException) { }
            return P.Prijava(izid, faza)
        } catch (e: Throwable) {
            return izNapake(e, faza)
        } finally {
            try { s?.close() } catch (_: IOException) { }
            meje.sprosti(p)
        }
    }
}
