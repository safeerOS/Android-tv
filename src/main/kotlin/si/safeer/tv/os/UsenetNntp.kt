package si.safeer.tv.os

import android.os.Looper
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Pogon Usenet, korak 1: odjemalec NNTP samo prek TLS (RFC 3977, AUTHINFO RFC 4643, NNTP prek TLS RFC 8143, vrata 563)
 * z bazenom povezav. Brez Androida razen preverbe glavne niti (tests/UsenetNntpTest.kt: lazni streznik na 127.0.0.1).
 *
 * - TLS 1.2 ali 1.3; potrdilo preveri [tovarna] (privzeto sistemske overitelje), IME streznika pa
 *   endpointIdentificationAlgorithm = "HTTPS": SSLSocket imena sam NE preveri (veljavno potrdilo za drugo ime bi
 *   sicer obveljalo). [imePotrdila] je dodatna preverba po rokovanju (Android: HttpsURLConnection.getDefaultHostnameVerifier()).
 * - Bazen: privzeto [PRIVZETO_POVEZAV], najvec [NAJVEC_POVEZAV] (ponudnik ima mejo povezav na racun, npr. 50 - vec naprav
 *   v krogu si jih deli). Povezava, ki je bila predolgo prosta, gre stran; ob napaki omrezja, napaki streznika (403) ali
 *   »400« na prosti povezavi en nov poskus z novo povezavo.
 * - Odgovori streznika ([izid], skupni primeri nntp_izid) in napake odjemalca ([Vrsta]): ni clanka, prijava, zaseden,
 *   omrezje, potrdilo, napaka streznika, napaka protokola. Ob prijavi in potrdilu ni ponovitve (ponavljanje napacnega
 *   gesla lahko zaklene racun).
 * - Nikoli na glavni niti (IllegalStateException).
 * - Geslo, uporabnisko ime in message-id niso nikoli v dnevniku, sporocilu izjeme ali toString; besedilo odgovora
 *   streznika (lahko ponovi id ali ime) se uporabi samo za razvrstitev, ne shrani.
 * - BODY vrne telo brez koncne vrstice ».« in s pikami, kot jih je poslal posiljatelj ([telo], skupni primeri
 *   nntp_telo): vrstica, ki se zacne z »..«, izgubi eno piko. Telo gre nato v [UsenetYenc.dekodiraj].
 *
 * Napisano na novo po RFC 3977, 4643 in 8143; nic prevzetega iz drugih projektov (ne iz GPL).
 */
class UsenetNntp(
    private val n: Nastavitve,
    private val tovarna: SSLSocketFactory = SSLContext.getDefault().socketFactory,
    private val imePotrdila: HostnameVerifier? = null,
) : Closeable {

    /** Nastavitve ponudnika. [toString] nima gostitelja, imena ne gesla. */
    class Nastavitve(
        val gostitelj: String,
        val uporabnik: String?,
        geslo: String?,
        val vrata: Int = VRATA,
        povezav: Int = PRIVZETO_POVEZAV,
        val rokPovezaveMs: Int = 10_000,
        val rokBranjaMs: Int = 15_000,
        val rokClankaMs: Long = 60_000,
        val rokCakanjaMs: Long = 30_000,
        val poskusov: Int = 2,
    ) {
        internal val geslo: String? = geslo
        val povezav: Int = povezav.coerceIn(1, NAJVEC_POVEZAV)
        override fun toString() = "Nastavitve(vrata=$vrata, povezav=$povezav)"
    }

    enum class Vrsta {
        /** Clanka ni (430, 423; odstranjen ali potekel) - poskusi drugo izdajo ali rezervnega ponudnika. */
        NI_CLANKA,
        /** Napacno ime ali geslo, racun potekel ali brez pravic - bazen ne poskusa vec (zapah na racun). */
        PRIJAVA,
        /** Ponudnik ne dovoli (502: trajno ali brez pravic) - bazen se ustavi, ponovitev ne pomaga. */
        DOSTOP,
        /** Rok (branje, clanek, povezava). */
        ROK,
        /** Streznik ali bazen je zaprl povezavo (205, 400 pri ukazu, close()). */
        ZAPRTO,
        /** Streznik ne sprejme vec povezav (meja racuna) ali vse povezave bazena so zasedene. */
        ZASEDEN,
        /** Povezava, rok, prekinitev ali streznik zapira povezavo (205). */
        OMREZJE,
        /** Potrdilo TLS ni veljavno ali ni za to ime, ali TLS starejsi od 1.2. */
        POTRDILO,
        /** Napaka streznika (403, npr. arhivski streznik) - nov poskus z novo povezavo. */
        NAPAKA_STREZNIKA,
        /** Nepricakovan odgovor ali prevelik clanek. */
        NAPAKA_PROTOKOLA,
    }

    /** Ukaz, na katerega streznik odgovarja ([izid]; v skupnih primerih »AUTHINFO USER« = UPORABNIK, »AUTHINFO PASS« = GESLO). */
    enum class Faza { POZDRAV, UPORABNIK, GESLO, ARTICLE, HEAD, BODY, STAT, QUIT }

    /** Izid vrstice odgovora; imena so ista kot v skupnih primerih (nntp_izid, male crke). */
    enum class Izid { OK, GESLO, ZASEDEN, PRIJAVA, DOSTOP, NI_CLANKA, NAPAKA_STREZNIKA, ZAPRTO, NAPAKA_PROTOKOLA }

    /** Faza povezave ob napaki (skupni primeri nntp_ukrep): POVEZAVA = TCP, TLS, pozdrav (nic AUTHINFO). */
    enum class FazaPovezave { POVEZAVA, PRIJAVA, UKAZ }

    /** Kaj bazen naredi po napaki (skupni primeri nntp_ukrep; namizje isto v core/usenet_nntp.ukrep_bazena). */
    enum class Ukrep { VRNI, ZNOVA, NAPAKA, ZMANJSAJ_POCAKAJ, POCAKAJ, NEZNANA_PRIJAVA, USTAVI_RACUN, USTAVI_BAZEN }

    /** Telo odgovora brez koncne vrstice in podvojenih pik ter bajti za njo (naslednji odgovor). */
    class Telo(val telo: ByteArray, val ostanek: ByteArray)

    /** Napaka NNTP: samo vrsta in koda odgovora (brez besedila streznika, id-ja, imena ali gesla). */
    class NapakaNntp(val vrsta: Vrsta, val koda: Int = 0, vzrok: Throwable? = null) :
        IOException("NNTP " + vrsta.name + (if (koda > 0) " $koda" else ""), vzrok)

    companion object {
        const val VRATA = 563
        const val PRIVZETO_POVEZAV = 8
        const val NAJVEC_POVEZAV = 50

        /**
         * Telo clanka (surovo) vecje od tega je napaka (skupni primeri: telo_bajtov). Izmerjeno 25. 10. 2026 na 5 pravih
         * NZB (prototip): clanek povprecno 384-719 KiB; 4 MiB je vec kot 5x toliko.
         */
        const val NAJVEC_TELESA = 4 * 1024 * 1024

        /** Argument AUTHINFO najvec v bajtih UTF-8 (RFC 3977: ukaz najvec 512 oktetov; poverilnica_bajtov). */
        const val NAJVEC_POVERILNICE = 400

        /** Prosta povezava, nedejavna dlje, se zapre; premor po meji povezav; rast bazena (skupni primeri usenet_meje). */
        const val PROSTA_NAJDLJE_S = 60L
        const val PREMOR_OD_S = 10L
        const val PREMOR_BREZ_ODPRTIH_S = 60L
        const val PREMOR_DO_S = 300L
        const val RAST_BAZENA_S = 600L

        private val MEJA = listOf("too many", "limit", "max", "exceed", "no more")
        private val ZAKLEP = listOf("lock", "ban", "block", "attempt", "suspend", "expire", "disable")
        private val NI_MEJA = listOf("fail", "pass", "invalid", "incorrect", "wrong") + ZAKLEP

        /** RFC 3977: vrstica odgovora najvec 512 bajtov; dovolimo vec, a ne neomejeno (vrstica_bajtov). */
        const val NAJVEC_VRSTICE = 4096
        private const val ZACETNI_BLOK = 1024 * 1024
        private const val MEDPOMNILNIK = 64 * 1024

        /** RFC 3977 priporoca streznikom vsaj 3 min; 60 s je varno pod tem. */
        private const val NAJDLJE_PROSTA_NS = 60_000_000_000L

        private val PROTOKOLI = setOf("TLSv1.3", "TLSv1.2")

        private const val LF: Byte = 10
        private const val CR: Byte = 13
        private const val PIKA: Byte = 46

        /**
         * Ali besedilo odgovora jasno pove mejo povezav. Ce omenja tudi napacno prijavo ali zaklep, NI meja: dvom je
         * zavrnjena prijava, ki se ne ponovi (lastnik 26. 10. 2026: nevarnost zaklepa racuna je prioriteta).
         */
        fun mejaPovezav(besedilo: String): Boolean {
            val t = besedilo.lowercase()
            if (NI_MEJA.any { it in t }) return false
            return "connection limit" in t || ("connections" in t && MEJA.any { it in t })
        }

        /**
         * Izid vrstice odgovora [vrstica] na ukaz [faza] (skupni primeri nntp_izid). Koda so prve tri stevke (prva 1-5),
         * za njimi presledek ali konec. Besedilo (lahko ponovi id ali ime) se uporabi samo za razvrstitev in se nikoli ne
         * shrani. Pri AUTHINFO je vse razen 281/381 in meje povezav PRIJAVA (RFC 4643 2.3.2); [prijavaUspela] = ista
         * prijava je v bazenu ze uspela in prijavljena povezava je odprta - takrat je 480/481/502 meja povezav (ponudnik:
         * prevec povezav = napaka prijave 480), razen ce besedilo omenja zaklep ali poskuse.
         */
        fun izid(faza: Faza, vrstica: String, prijavaUspela: Boolean = false): Izid {
            val avtor = faza == Faza.UPORABNIK || faza == Faza.GESLO
            if (vrstica.length < 3 || vrstica[0] !in '1'..'5' || vrstica[1] !in '0'..'9' || vrstica[2] !in '0'..'9' ||
                (vrstica.length > 3 && vrstica[3] != ' ')) return if (avtor) Izid.PRIJAVA else Izid.NAPAKA_PROTOKOLA
            val koda = vrstica.substring(0, 3).toInt()
            val pricakovana = when (faza) {
                Faza.POZDRAV -> koda == 200 || koda == 201
                Faza.UPORABNIK, Faza.GESLO -> koda == 281
                Faza.ARTICLE -> koda == 220
                Faza.HEAD -> koda == 221
                Faza.BODY -> koda == 222
                Faza.STAT -> koda == 223
                Faza.QUIT -> koda == 205
            }
            if (pricakovana) return Izid.OK
            if (faza == Faza.UPORABNIK && koda == 381) return Izid.GESLO
            val besedilo = if (vrstica.length > 4) vrstica.substring(4) else ""
            if (koda in setOf(400, 480, 481, 482, 502) && mejaPovezav(besedilo)) return Izid.ZASEDEN
            if (avtor) {
                val zaklep = besedilo.lowercase().let { t -> ZAKLEP.any { it in t } }
                return if (prijavaUspela && koda in setOf(480, 481, 502) && !zaklep) Izid.ZASEDEN else Izid.PRIJAVA
            }
            val clanek = faza == Faza.ARTICLE || faza == Faza.HEAD || faza == Faza.BODY || faza == Faza.STAT
            return when {
                (koda == 423 || koda == 430) && clanek -> Izid.NI_CLANKA
                koda == 480 || koda == 481 || koda == 482 -> Izid.PRIJAVA
                koda == 400 -> if (faza == Faza.POZDRAV) Izid.NAPAKA_STREZNIKA else Izid.ZAPRTO
                koda == 502 -> Izid.DOSTOP
                koda == 403 -> Izid.NAPAKA_STREZNIKA
                koda == 205 -> Izid.ZAPRTO
                else -> Izid.NAPAKA_PROTOKOLA
            }
        }

        /**
         * Ukrep bazena po napaki [vrsta] v [faza] (skupni primeri nntp_ukrep): [stara] = povezava iz bazena, [poskus] 0
         * ali 1. ZNOVA je vedno z novo povezavo; po AUTHINFO nikoli ponovitev v zanki.
         */
        fun ukrep(vrsta: Vrsta, faza: FazaPovezave, stara: Boolean, poskus: Int): Ukrep = when (faza) {
            FazaPovezave.POVEZAVA -> when (vrsta) {
                Vrsta.OMREZJE -> if (poskus == 0) Ukrep.ZNOVA else Ukrep.NAPAKA
                Vrsta.ZASEDEN -> Ukrep.ZMANJSAJ_POCAKAJ
                Vrsta.NAPAKA_STREZNIKA -> Ukrep.POCAKAJ
                Vrsta.DOSTOP, Vrsta.POTRDILO -> Ukrep.USTAVI_BAZEN
                Vrsta.PRIJAVA -> Ukrep.USTAVI_RACUN
                else -> Ukrep.NAPAKA
            }
            FazaPovezave.PRIJAVA -> when (vrsta) {
                Vrsta.ZASEDEN -> Ukrep.ZMANJSAJ_POCAKAJ
                // Geslo je morda prislo do streznika, odgovora pa ni.
                Vrsta.OMREZJE, Vrsta.ROK, Vrsta.ZAPRTO, Vrsta.NAPAKA_PROTOKOLA -> Ukrep.NEZNANA_PRIJAVA
                else -> Ukrep.USTAVI_RACUN
            }
            FazaPovezave.UKAZ -> when (vrsta) {
                Vrsta.NI_CLANKA -> Ukrep.VRNI
                Vrsta.PRIJAVA -> Ukrep.USTAVI_RACUN
                Vrsta.DOSTOP, Vrsta.POTRDILO -> Ukrep.USTAVI_BAZEN
                Vrsta.ZASEDEN -> if (stara && poskus == 0) Ukrep.ZNOVA else Ukrep.ZMANJSAJ_POCAKAJ
                Vrsta.OMREZJE, Vrsta.NAPAKA_STREZNIKA -> if (poskus == 0) Ukrep.ZNOVA else Ukrep.NAPAKA
                else -> if (stara && poskus == 0) Ukrep.ZNOVA else Ukrep.NAPAKA
            }
        }

        /**
         * Ali bazen sme odpreti novo povezavo (skupni primeri nntp_sme_odpreti): dokler prijava ni uspela (ali ni nobena
         * prijavljena povezava odprta), se odpira ena sama naenkrat (sonda) - napacno geslo gre k strezniku enkrat.
         */
        fun smeOdpreti(ustavljen: Boolean, premor: Boolean, odprtih: Int, najvec: Int, uspela: Boolean, sonda: Boolean): Boolean {
            if (ustavljen || premor || odprtih >= najvec) return false
            return uspela || !sonda
        }

        /**
         * (najvec, premor v s) po meji povezav ali zacasni napaki (skupni primeri nntp_premor). [odprtih] = DRUGE
         * prijavljene odprte povezave (te je streznik sprejel): bazen pade nanje, najmanj 1 - hkratne zavrnitve ga ne
         * skrcijo pod to. Premor 10 s ali 60 s (ni nobene druge), nato dvakrat daljsi do 300 s; [tece] ga ne podaljsa.
         */
        fun novPremor(najvec: Int, odprtih: Int, premorS: Long, tece: Boolean, zmanjsaj: Boolean): Pair<Int, Long> {
            val n = if (zmanjsaj) maxOf(1, minOf(najvec, odprtih)) else najvec
            val p = if (tece) premorS else if (premorS > 0) minOf(PREMOR_DO_S, 2 * premorS)
                else if (odprtih > 0) PREMOR_OD_S else PREMOR_BREZ_ODPRTIH_S
            return n to p
        }

        /** Ali odgovor 222/223 velja za zahtevani clanek (skupni primeri nntp_id_odgovora): prvi <...> mora biti [id]. */
        fun idOdgovoraUstreza(vrstica: String, id: String): Boolean {
            val i = vrstica.indexOf('<')
            val j = if (i >= 0) vrstica.indexOf('>', i + 1) else -1
            return i < 0 || j < 0 || vrstica.substring(i + 1, j) == id
        }

        /** Napaka odjemalca za izid odgovora; null = uspeh (ali pri prijavi: posli geslo). */
        private fun vrsta(i: Izid): Vrsta? = when (i) {
            Izid.OK, Izid.GESLO -> null
            Izid.ZASEDEN -> Vrsta.ZASEDEN
            Izid.PRIJAVA -> Vrsta.PRIJAVA
            Izid.DOSTOP -> Vrsta.DOSTOP
            Izid.NI_CLANKA -> Vrsta.NI_CLANKA
            Izid.NAPAKA_STREZNIKA -> Vrsta.NAPAKA_STREZNIKA
            Izid.ZAPRTO -> Vrsta.OMREZJE
            Izid.NAPAKA_PROTOKOLA -> Vrsta.NAPAKA_PROTOKOLA
        }

        /**
         * Argument AUTHINFO (skupni primeri nntp_poverilnica): 1-[NAJVEC_POVERILNICE] bajtov UTF-8, brez znakov pod 0x20
         * in 0x7F (CR/LF bi zacel nov ukaz) in brez osamljenih nadomestnih znakov (toByteArray bi jih tiho zamenjal z »?«).
         */
        fun varenArgument(s: String): Boolean {
            if (s.isEmpty() || s.any { it.code < 0x20 || it.code == 0x7F }) return false
            var bajtov = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                    bajtov += 4
                    i += 2
                    continue
                }
                if (Character.isSurrogate(c)) return false
                bajtov += if (c.code < 0x80) 1 else if (c.code < 0x800) 2 else 3
                i++
            }
            return bajtov <= NAJVEC_POVERILNICE
        }

        /**
         * Telo odgovora v [b] med [od] in [doo] (skupni primeri nntp_telo): do vrstice ».« s CRLF, vrstica z »..« na
         * zacetku izgubi eno piko; null = konca ni (prekinjena povezava).
         */
        fun telo(b: ByteArray, od: Int = 0, doo: Int = b.size): Telo? {
            val k = najdiKonec(b, od, od, doo)
            if (k < 0) return null
            return Telo(odstraniPike(b, od, k), b.copyOfRange(k + 3, doo))
        }

        /**
         * Indeks pike vrstice ».« + CRLF med [od] in [doo] ali -1. Vrstica se zacne na [zacetekBloka] ali za LF (iskanje
         * lahko nadaljuje sredi bloka - [od] ni nujno zacetek vrstice).
         */
        fun najdiKonec(b: ByteArray, zacetekBloka: Int, od: Int, doo: Int): Int {
            var k = od
            while (k + 2 < doo) {
                if (b[k] == PIKA && (k == zacetekBloka || b[k - 1] == LF) && b[k + 1] == CR && b[k + 2] == LF) return k
                k++
            }
            return -1
        }

        /**
         * Kopija [od, doo) brez prve pike vrstic, ki se zacnejo z »..« (RFC 3977 3.1.1). Vmesne dele kopira arraycopy -
         * podvojene pike so redke (meritev 25. 10. 2026: telo + yEnc 98-172 MB/s z zanko po bajtih, glej porocilo).
         */
        private fun odstraniPike(b: ByteArray, od: Int, doo: Int): ByteArray {
            val izid = ByteArray(doo - od)
            var o = 0
            var zac = od
            var i = od
            if (doo - od >= 2 && b[od] == PIKA && b[od + 1] == PIKA) {
                zac = od + 1
                i = od + 1
            }
            while (i + 2 < doo) {
                if (b[i] == LF && b[i + 1] == PIKA && b[i + 2] == PIKA) {
                    System.arraycopy(b, zac, izid, o, i + 1 - zac)
                    o += i + 1 - zac
                    zac = i + 2
                    i += 2
                } else {
                    i++
                }
            }
            System.arraycopy(b, zac, izid, o, doo - zac)
            o += doo - zac
            return if (o == izid.size) izid else izid.copyOf(o)
        }
    }

    /** Ena povezava TLS; uporablja jo ena nit naenkrat. */
    private inner class Povezava(private val surova: Socket, private val tls: SSLSocket) {
        private val vhod: InputStream = tls.inputStream
        private val izhod: OutputStream = tls.outputStream
        private val buf = ByteArray(MEDPOMNILNIK)
        private var poz = 0
        private var dolz = 0
        @Volatile var prosta = System.nanoTime()

        fun ukaz(v: String) {
            try {
                izhod.write((v + "\r\n").toByteArray(Charsets.UTF_8))
                izhod.flush()
            } catch (e: IOException) {
                throw NapakaNntp(Vrsta.OMREZJE, vzrok = e)
            }
        }

        private fun beri(b: ByteArray, od: Int, koliko: Int): Int {
            val r = try { vhod.read(b, od, koliko) } catch (e: IOException) { throw NapakaNntp(Vrsta.OMREZJE, vzrok = e) }
            if (r < 0) throw NapakaNntp(Vrsta.OMREZJE)
            return r
        }

        fun odgovor(): String {
            var pregledano = 0
            while (true) {
                var k = poz + pregledano
                while (k < dolz && buf[k] != LF) k++
                if (k < dolz) {
                    val v = String(buf, poz, k - poz, Charsets.ISO_8859_1).trimEnd('\r')
                    poz = k + 1
                    return v
                }
                pregledano = dolz - poz
                if (pregledano >= NAJVEC_VRSTICE) throw NapakaNntp(Vrsta.NAPAKA_PROTOKOLA)
                if (poz > 0) {
                    System.arraycopy(buf, poz, buf, 0, dolz - poz)
                    dolz -= poz
                    poz = 0
                }
                dolz += beri(buf, dolz, buf.size - dolz)
            }
        }

        /** Izid odgovora na [faza]; napaka kot [NapakaNntp] s kodo (brez besedila). */
        private fun preveri(faza: Faza): Izid {
            val v = odgovor()
            val i = izid(faza, v)
            vrsta(i)?.let { throw NapakaNntp(it, v.take(3).toIntOrNull() ?: 0) }
            return i
        }

        fun pozdrav() { preveri(Faza.POZDRAV) }

        fun prijava(uporabnik: String?, geslo: String?) {
            if (uporabnik == null) return
            if (!varenArgument(uporabnik) || (geslo != null && !varenArgument(geslo))) throw NapakaNntp(Vrsta.PRIJAVA)
            ukaz("AUTHINFO USER $uporabnik")
            if (preveri(Faza.UPORABNIK) == Izid.OK) return
            if (geslo == null) throw NapakaNntp(Vrsta.PRIJAVA, 381)
            ukaz("AUTHINFO PASS $geslo")
            preveri(Faza.GESLO)
        }

        fun body(id: String, rokDo: Long): ByteArray {
            ukaz("BODY <$id>")
            preveri(Faza.BODY)
            return blok(rokDo)
        }

        fun stat(id: String): Boolean {
            ukaz("STAT <$id>")
            val v = odgovor()
            val i = izid(Faza.STAT, v)
            if (i == Izid.NI_CLANKA) return false
            vrsta(i)?.let { throw NapakaNntp(it, v.take(3).toIntOrNull() ?: 0) }
            return true
        }

        /** Telo do vrstice ».« (brez nje), brez podvojenih pik ([telo]). */
        private fun blok(rokDo: Long): ByteArray {
            var izid = ByteArray(maxOf(ZACETNI_BLOK, dolz - poz))
            var n = dolz - poz
            System.arraycopy(buf, poz, izid, 0, n)
            poz = 0
            dolz = 0
            var od = 0
            while (true) {
                val k = najdiKonec(izid, 0, od, n)
                if (k >= 0) {
                    // Brez cevovoda streznik za koncem ne poslje nicesar; ce pa, ostane za naslednji odgovor.
                    val ostanek = n - (k + 3)
                    if (ostanek > buf.size) throw NapakaNntp(Vrsta.NAPAKA_PROTOKOLA)
                    System.arraycopy(izid, k + 3, buf, 0, ostanek)
                    dolz = ostanek
                    return odstraniPike(izid, 0, k)
                }
                od = maxOf(0, n - 2)   // konec je lahko razdeljen med dve branji
                if (System.nanoTime() - rokDo > 0) throw NapakaNntp(Vrsta.OMREZJE)
                if (n == izid.size) {
                    if (izid.size >= NAJVEC_TELESA) throw NapakaNntp(Vrsta.NAPAKA_PROTOKOLA)
                    izid = izid.copyOf(minOf(izid.size * 2, NAJVEC_TELESA))
                }
                n += beri(izid, n, izid.size - n)
            }
        }

        /** Zapre brez pisanja (tudi z glavne niti in med branjem druge niti): najprej vticnica pod TLS. */
        fun zapri() {
            try { surova.close() } catch (_: IOException) { }
            try { tls.close() } catch (_: IOException) { }
        }
    }

    private val prosti = LinkedBlockingDeque<Povezava>()
    private val vse: MutableSet<Povezava> = ConcurrentHashMap.newKeySet()
    private val mesta = Semaphore(n.povezav, true)
    private val odprto = AtomicInteger()
    @Volatile private var zaprto = false

    /** Trenutno odprte povezave (proste in v rabi). */
    val odprtih: Int get() = vse.size

    /** Vse povezave, odprte od zacetka (za meritev in preizkus ponovne rabe). */
    val odprtoSkupaj: Int get() = odprto.get()

    /** Telo clanka [id] (message-id z < > ali brez) za [UsenetYenc.dekodiraj]. Napaka: [NapakaNntp]; neveljaven id: IllegalArgumentException. */
    fun body(id: String): ByteArray {
        val c = requireNotNull(UsenetNzb.cistId(id)) { "neveljaven message-id" }
        return zDelom { it.body(c, System.nanoTime() + n.rokClankaMs * 1_000_000L) }
    }

    /** Ali clanek [id] obstaja (STAT): true/false; druge napake: [NapakaNntp]. */
    fun stat(id: String): Boolean {
        val c = requireNotNull(UsenetNzb.cistId(id)) { "neveljaven message-id" }
        return zDelom { it.stat(c) }
    }

    /** Zapre vse povezave (tudi tiste v rabi - njihovo branje se konca z OMREZJE). Brez pisanja, varno z vsake niti. */
    override fun close() {
        zaprto = true
        for (p in vse) p.zapri()
        vse.clear()
        prosti.clear()
    }

    private fun <T> zDelom(delo: (Povezava) -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "NNTP ne sme teci na glavni niti" }
        if (zaprto) throw NapakaNntp(Vrsta.OMREZJE)
        val dobil = try {
            mesta.tryAcquire(n.rokCakanjaMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw NapakaNntp(Vrsta.OMREZJE, vzrok = e)
        }
        if (!dobil) throw NapakaNntp(Vrsta.ZASEDEN)
        try {
            var poskus = 1
            while (true) {
                if (zaprto) throw NapakaNntp(Vrsta.OMREZJE)
                // Ponovni poskus vedno z novo povezavo (prosta je lahko ze zaprta pri strezniku).
                val prosta = if (poskus == 1) vzemi() else null
                val izBazena = prosta != null
                val p = prosta ?: odpri()
                try {
                    val r = delo(p)
                    vrni(p)
                    return r
                } catch (e: IOException) {
                    val napaka = e as? NapakaNntp ?: NapakaNntp(Vrsta.OMREZJE, vzrok = e)
                    if (napaka.vrsta == Vrsta.NI_CLANKA) vrni(p) else zavrzi(p)
                    // Nov poskus z novo povezavo: omrezje, napaka streznika, in »400 Idle timeout« na dolgo prosti povezavi.
                    val ponovi = napaka.vrsta == Vrsta.OMREZJE || napaka.vrsta == Vrsta.NAPAKA_STREZNIKA ||
                        (napaka.vrsta == Vrsta.ZASEDEN && izBazena)
                    if (!ponovi || poskus >= n.poskusov || zaprto) throw napaka
                    poskus++
                } catch (e: RuntimeException) {
                    zavrzi(p)
                    throw e
                }
            }
        } finally {
            mesta.release()
        }
    }

    private fun vzemi(): Povezava? {
        while (true) {
            val p = prosti.pollFirst() ?: return null
            if (System.nanoTime() - p.prosta < NAJDLJE_PROSTA_NS) return p
            zavrzi(p)
        }
    }

    private fun vrni(p: Povezava) {
        p.prosta = System.nanoTime()
        prosti.offerFirst(p)
        if (zaprto) {
            prosti.remove(p)
            zavrzi(p)
        }
    }

    private fun zavrzi(p: Povezava) {
        vse.remove(p)
        p.zapri()
    }

    private fun odpri(): Povezava {
        val surova = Socket()
        val tls: SSLSocket
        try {
            surova.connect(InetSocketAddress(n.gostitelj, n.vrata), n.rokPovezaveMs)
            surova.soTimeout = n.rokBranjaMs
            surova.tcpNoDelay = true
            val t = tovarna.createSocket(surova, n.gostitelj, n.vrata, true) as SSLSocket
            val protokoli = t.supportedProtocols.filter { it in PROTOKOLI }
            if (protokoli.isEmpty()) throw NapakaNntp(Vrsta.POTRDILO)
            val p = t.sslParameters
            p.endpointIdentificationAlgorithm = "HTTPS"
            p.protocols = protokoli.toTypedArray()
            t.sslParameters = p
            t.soTimeout = n.rokBranjaMs
            try {
                t.startHandshake()
            } catch (e: SSLHandshakeException) {
                throw NapakaNntp(Vrsta.POTRDILO, vzrok = e)
            }
            val seja = t.session
            if (seja.protocol !in PROTOKOLI) throw NapakaNntp(Vrsta.POTRDILO)
            if (imePotrdila != null && !imePotrdila.verify(n.gostitelj, seja)) throw NapakaNntp(Vrsta.POTRDILO)
            tls = t
        } catch (e: IOException) {
            try { surova.close() } catch (_: IOException) { }
            throw e as? NapakaNntp ?: NapakaNntp(Vrsta.OMREZJE, vzrok = e)
        }
        val pov = Povezava(surova, tls)
        vse.add(pov)
        odprto.incrementAndGet()
        if (zaprto) {
            zavrzi(pov)
            throw NapakaNntp(Vrsta.OMREZJE)
        }
        try {
            pov.pozdrav()
            pov.prijava(n.uporabnik, n.geslo)
        } catch (e: IOException) {
            zavrzi(pov)
            throw e as? NapakaNntp ?: NapakaNntp(Vrsta.OMREZJE, vzrok = e)
        }
        return pov
    }
}
