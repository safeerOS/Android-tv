package si.safeer.tv.os

import android.os.Looper
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pogon Usenet, korak 1: odjemalec NNTP samo prek TLS (RFC 3977, AUTHINFO RFC 4643, NNTP prek TLS RFC 8143, vrata 563)
 * z bazenom povezav. Brez Androida razen preverbe glavne niti (tests/UsenetNntpTest.kt: lazni streznik na 127.0.0.1).
 *
 * - TLS 1.2 ali 1.3; potrdilo preveri [tovarna] (privzeto sistemske overitelje), IME streznika pa dvakrat:
 *   endpointIdentificationAlgorithm = "HTTPS" (SSLSocket imena sam NE preveri) in VEDNO se nasa [imeUstreza] po RFC 6125
 *   pred pozdravom in AUTHINFO - ne zanasamo se, da ga Conscrypt na vsaki TV upostevan (pregled Grok 35 Y1). [imePotrdila]
 *   je izbirna tretja preverba (Android: HttpsURLConnection.getDefaultHostnameVerifier()).
 * - Bazen: privzeto [PRIVZETO_POVEZAV], najvec [NAJVEC_POVEZAV] (ponudnik ima mejo povezav na racun, npr. 50 - vec naprav
 *   v krogu si jih deli; [nastaviNajvec] za proracun povezav med napravami, korak 3). Povezava, ki je bila predolgo
 *   prosta, gre stran; kaj naredi bazen po napaki, pove [ukrep] (skupni primeri nntp_ukrep, enako namizje).
 * - Zaklep racuna (lastnik 26. 10. 2026 03:05: »Nevarnost zaklepa racuna je prioriteta«): AUTHINFO za isti racun gre v
 *   procesu vedno ena naenkrat (vrata prijave), tudi po uspeli prijavi; izid se zapise, preden naslednja povezava poslje
 *   geslo. Dokler ni zive prijave, se odpira ena sama povezava naenkrat ([smeOdpreti]). Pri AUTHINFO je vsak odgovor
 *   razen 281/381 in jasne meje povezav zavrnjena prijava - tudi »481 Authentication failed« po uspeli prijavi (geslo se
 *   je med sejo morda spremenilo; pregled Grok 44 D1). Zavrnjena prijava ustavi bazen, zapre vse povezave in zapre
 *   racun, dokler uporabnik poverilnic ne vpise znova ([dovoliPrijavoZnova]) ali sam ne klikne »Preveri«
 *   ([preveri] z odkleni) - napacno geslo pride do streznika enkrat. Zapah je trajen (pregled Grok 44 D2): prezivi
 *   ponovni zagon, sesutje in posodobitev aplikacije ([shrambaZapaha]; aplikacija pred prvo rabo poklice
 *   [UsenetZapahShramba.namesti]).
 *   »Prevec povezav« bazen skrci na sprejete povezave in zacne premor novih ([novPremor]) - nikoli nov poskus prijave v
 *   zanki.
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
        /** Milisekund na sekundo premora (preizkusi skrajsajo premor; v aplikaciji vedno 1000). */
        internal val msNaSekundoPremora: Long = 1000,
        /**
         * Skupen proracun pomnilnika teles v bazenu (pregled Grok 35 Y3): TV (Philips, izmerjeno 17. 9.: heapgrowthlimit
         * 192 MB, largeHeap 512 MB, Safeer TV ze ~85 MB Dalvik) - 24 MiB je 14 hkratnih osnov po 1,5 MiB + rast.
         */
        val proracunBajtov: Long = PRORACUN_BAJTOV,
        /** Samo preizkus: izklopi endpointIdentificationAlgorithm (kot naprava, ki ga ne uposteva) - [imeUstreza] ostane. */
        internal val endpointHttps: Boolean = true,
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

    /**
     * Trajna hramba zapaha racuna (pregled Grok 44 D2): besedilo z zapisi (sol, odtis racuna, pocasen odtis gesla) -
     * nikoli geslo. Aplikacija: [UsenetZapahShramba] (SporocilaSkrivnosti, kljuc Android Keystore).
     */
    interface ShrambaZapaha {
        fun beri(): String?
        fun pisi(besedilo: String): Boolean
    }

    /** Telo odgovora brez koncne vrstice in podvojenih pik ter bajti za njo (naslednji odgovor). */
    class Telo(val telo: ByteArray, val ostanek: ByteArray)

    /**
     * Napaka NNTP: samo vrsta in koda odgovora (brez besedila streznika, id-ja, imena ali gesla). Izvirne izjeme NI kot
     * vzroka (Grok 35 Y9: UnknownHostException, ConnectException in SSLHandshakeException nosijo gostitelja, IP in ime
     * iz potrdila) - ostane samo ime njenega razreda v [vzrokVrsta].
     */
    class NapakaNntp(val vrsta: Vrsta, val koda: Int = 0, vzrok: Throwable? = null) :
        IOException("NNTP " + vrsta.name + (if (koda > 0) " $koda" else "")) {
        val vzrokVrsta: String? = vzrok?.javaClass?.simpleName
    }

    companion object {
        /** Racuni, ki jim je ponudnik zavrnil prijavo: odtis s sljo procesa (nikoli geslo), samo v pomnilniku (U-4). */
        private val ZAVRNJENI: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val SOL = ByteArray(16).also { SecureRandom().nextBytes(it) }
        /**
         * Vrata prijave (pregled Grok 44 D1): kljuc racuna (ponudnik, uporabnik) -> zaklep. AUTHINFO gre za racun v procesu
         * vedno ena naenkrat, tudi po uspeli prijavi - zavrnitev postavi zapah, preden naslednja povezava poslje geslo.
         */
        private val PRIJAVE = ConcurrentHashMap<String, ReentrantLock>()
        /** Prijave brez odgovora po odtisu racuna (geslo je morda prislo do streznika): prvic premor, drugic zapored zapah. */
        private val NEZNANE = ConcurrentHashMap<String, Int>()

        /** Drugi nivo pod drzavno domeno, ki se ni ponudnik (news.x.co.uk -> x.co.uk) - brez seznama javnih pripon. */
        private val DRUGI_NIVO = setOf("co", "com", "net", "org", "ac", "or", "ne", "gov", "edu")
        /** Zivi bazeni: zapah racuna ustavi vse bazene istega racuna (pregled Grok 44 D2). */
        private val BAZENI: MutableSet<UsenetNntp> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

        /**
         * Ponudnik iz imena streznika (skupni primeri nntp_ponudnik): zadnji dve oznaki (news.x.com, eu.news.x.com. ->
         * x.com), pri drzavni domeni z drugim nivojem tri (news.x.co.uk -> x.co.uk); naslov IP ostane cel. Zapah in vrata
         * prijave veljajo za racun pri ponudniku, ne za ime streznika (Grok 44 D2: vsako ime je dobilo svoj poskus).
         */
        fun ponudnik(gostitelj: String): String {
            val g = gostitelj.trim().lowercase().trimEnd('.')
            if (g.isEmpty() || ':' in g || g.all { it in '0'..'9' || it == '.' }) return g
            val d = g.split('.')
            val n = if (d.size >= 3 && d.last().length == 2 && d[d.size - 2] in DRUGI_NIVO) 3 else 2
            return d.takeLast(n).joinToString(".")
        }

        private fun zgosti(vararg polja: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(SOL)
            for (x in polja) {
                val b = x.toByteArray(Charsets.UTF_8)
                md.update(byteArrayOf((b.size ushr 24).toByte(), (b.size ushr 16).toByte(), (b.size ushr 8).toByte(), b.size.toByte()))
                md.update(b)
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun odtisRacuna(gostitelj: String, uporabnik: String?, geslo: String?): String? =
            if (uporabnik == null) null else zgosti(ponudnik(gostitelj), uporabnik, geslo.orEmpty())

        /** Zaklep prijave racuna (ponudnik, uporabnik) - en za vse bazene in gesla tega racuna v procesu. */
        private fun vrataPrijave(gostitelj: String, uporabnik: String): ReentrantLock =
            PRIJAVE.computeIfAbsent(zgosti("prijava", ponudnik(gostitelj), uporabnik)) { ReentrantLock() }

        /**
         * Ali je ponudnik te poverilnice ze zavrnil - v tem procesu ali prej (trajni zapah, Grok 44 D2). Do ponovnega vpisa
         * ali uporabnikovega »Preveri« jih ne posiljamo. Bere hrambo - ne na glavni niti.
         */
        fun prijavaZavrnjena(gostitelj: String, uporabnik: String?, geslo: String?): Boolean {
            val o = odtisRacuna(gostitelj, uporabnik, geslo) ?: return false
            if (o in ZAVRNJENI) return true
            if (uporabnik == null || !trajnoZavrnjen(ponudnik(gostitelj), uporabnik, geslo.orEmpty())) return false
            ZAVRNJENI += o
            return true
        }

        /**
         * Trajni zapah (pregled Grok 44 D2): zapis = sol (16 B, svoja za vsak zapis), racun = SHA-256(sol, ponudnik,
         * uporabnik), geslo = PBKDF2-HMAC-SHA256(ponudnik, uporabnik, geslo; sol, [ZAPAH_PONOVITEV]) - nikoli geslo. Pocasen
         * odtis, ker je zavrnjeno geslo pogosto tipkarska napaka pravega. Zapis z lastno soljo je prenosljiv (korak 3).
         */
        const val ZAPAH_PONOVITEV = 100_000
        const val NAJVEC_ZAPAHOV = 32
        private const val GLAVA_ZAPAHA = "safeer-usenet-zapah 1"
        private val ZAPAH_ZAKLEP = Any()
        /** Prebrani zapisi (pod [ZAPAH_ZAKLEP]); null = se niso prebrani - Keystore se ne odpira ob vsaki prijavi. */
        private var zapahi: List<Zapis>? = null

        /** Hramba trajnega zapaha; null = samo pomnilnik procesa (preizkusi JVM). */
        @Volatile var shrambaZapaha: ShrambaZapaha? = null
            set(v) {
                synchronized(ZAPAH_ZAKLEP) {
                    field = v
                    zapahi = null
                }
            }

        private class Zapis(val sol: ByteArray, val racun: String, val geslo: String, val cas: Long)

        private fun polja(vararg p: String): ByteArray {
            val o = java.io.ByteArrayOutputStream()
            for (x in p) {
                val b = x.toByteArray(Charsets.UTF_8)
                o.write(byteArrayOf((b.size ushr 24).toByte(), (b.size ushr 16).toByte(), (b.size ushr 8).toByte(), b.size.toByte()))
                o.write(b)
            }
            return o.toByteArray()
        }

        private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

        private fun odtisZapisa(sol: ByteArray, ponudnik: String, uporabnik: String): String =
            MessageDigest.getInstance("SHA-256").let {
                it.update(sol)
                it.update(polja(ponudnik, uporabnik))
                hex(it.digest())
            }

        /** (racun, geslo) zapisa trajnega zapaha, sestnajstisko (skupni primeri nntp_zapah_odtis; namizje isto). */
        fun zapahOdtis(sol: ByteArray, ponudnik: String, uporabnik: String, geslo: String): Pair<String, String> =
            odtisZapisa(sol, ponudnik, uporabnik) to hex(pbkdf2(polja(ponudnik, uporabnik, geslo), sol, ZAPAH_PONOVITEV))

        /** PBKDF2-HMAC-SHA256 z enim blokom (32 B); geslo so bajti (SecretKeyFactory sprejme samo znake). */
        private fun pbkdf2(geslo: ByteArray, sol: ByteArray, ponovitev: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(geslo, "HmacSHA256"))
            mac.update(sol)
            var u = mac.doFinal(byteArrayOf(0, 0, 0, 1))
            val t = u.copyOf()
            for (i in 1 until ponovitev) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            return t
        }

        private fun jeHex(s: String): Boolean = s.all { it in '0'..'9' || it in 'a'..'f' }

        /** Zapisi iz hrambe (pod [ZAPAH_ZAKLEP]); nepopolno ali tuje besedilo = brez zapisov (ne ustavi pogona). */
        private fun zapisi(): List<Zapis> {
            zapahi?.let { return it }
            val besedilo = try { shrambaZapaha?.beri() } catch (_: Throwable) { null }
            val vrstice = besedilo?.take(64 * 1024)?.lines().orEmpty()
            val z = if (vrstice.firstOrNull() != GLAVA_ZAPAHA) emptyList() else vrstice.drop(1).mapNotNull { v ->
                val d = v.split(' ')
                if (d.size == 4 && d[0].length == 32 && d[1].length == 64 && d[2].length == 64 && jeHex(d[0] + d[1] + d[2]) &&
                    d[3].length in 1..18 && d[3].all { it in '0'..'9' })
                    Zapis(ByteArray(16) { d[0].substring(2 * it, 2 * it + 2).toInt(16).toByte() }, d[1], d[2], d[3].toLong())
                else null
            }.takeLast(NAJVEC_ZAPAHOV)
            zapahi = z
            return z
        }

        private fun pisiZapise(z: List<Zapis>) {
            val s = shrambaZapaha ?: return
            val b = (listOf(GLAVA_ZAPAHA) + z.map { "${hex(it.sol)} ${it.racun} ${it.geslo} ${it.cas}" }).joinToString("\n") + "\n"
            zapahi = z   // velja do konca procesa tudi, ce se hramba ne posreci
            try { s.pisi(b) } catch (_: Throwable) { }
        }

        private fun zapisiRacuna(z: List<Zapis>, ponudnik: String, uporabnik: String): List<Zapis> =
            z.filter { MessageDigest.isEqual(odtisZapisa(it.sol, ponudnik, uporabnik).toByteArray(), it.racun.toByteArray()) }

        private fun trajnoZavrnjen(ponudnik: String, uporabnik: String, geslo: String): Boolean {
            if (shrambaZapaha == null) return false
            val z = synchronized(ZAPAH_ZAKLEP) { zapisiRacuna(zapisi(), ponudnik, uporabnik) }
            return z.any { MessageDigest.isEqual(zapahOdtis(it.sol, ponudnik, uporabnik, geslo).second.toByteArray(), it.geslo.toByteArray()) }
        }

        private fun zapisiZapah(ponudnik: String, uporabnik: String, geslo: String) {
            if (shrambaZapaha == null) return
            val sol = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val (racun, g) = zapahOdtis(sol, ponudnik, uporabnik, geslo)   // pocasen odtis izven zaklepa
            synchronized(ZAPAH_ZAKLEP) { pisiZapise((zapisi() + Zapis(sol, racun, g, System.currentTimeMillis() / 1000)).takeLast(NAJVEC_ZAPAHOV)) }
        }

        /** Zapahi racuna gredo stran (nov vpis, »Preveri«, uspela prijava - staro napacno geslo ni vec shranjeno). */
        private fun pozabiZapahe(ponudnik: String, uporabnik: String) {
            if (shrambaZapaha == null) return
            synchronized(ZAPAH_ZAKLEP) {
                val z = zapisi()
                val r = zapisiRacuna(z, ponudnik, uporabnik)
                if (r.isNotEmpty()) pisiZapise(z - r.toSet())
            }
        }

        /**
         * Uporabnik je poverilnice vpisal znova (tudi enake - npr. po podaljsanju narocnine): en nov poskus prijave je spet
         * dovoljen. Klice ga samo izrecno dejanje uporabnika, nikoli samodejno.
         */
        fun dovoliPrijavoZnova(gostitelj: String, uporabnik: String?, geslo: String?) {
            odtisRacuna(gostitelj, uporabnik, geslo)?.let {
                ZAVRNJENI.remove(it)
                NEZNANE.remove(it)
            }
            if (uporabnik != null) pozabiZapahe(ponudnik(gostitelj), uporabnik)
        }

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
        /**
         * Zaporedni ukazi, zavrnjeni z 502 ali (na sveze prijavljeni povezavi) 480/481, brez uspeha vmes, ustavijo bazen:
         * posamezen clanek (omejena skupina) ga ne ustavi, racun brez pravic pa ne odpira novih prijav v zanki (Grok 44 D4).
         */
        const val NAPAK_UKAZA_ZAPORED = 3
        /**
         * Prijava je ziva samo, ce je povezava v zadnjih 30 s uspesno koncala prijavo ali ukaz (pregled Grok 44 D1: odprta
         * vticnica ni dokaz - ponudnik je sejo ob menjavi gesla morda ze zaprl). Samo ziva prijava dovoli vzporedno odpiranje.
         */
        const val ZIVA_PRIJAVA_S = 30L

        private val MEJA = listOf("too many", "limit", "max", "exceed", "no more")
        private val ZAKLEP = listOf("lock", "ban", "block", "attempt", "suspend", "expire", "disable")
        /** Besedilo o prijavi, geslu ali avtentikaciji ni nikoli meja povezav (lastnik 26. 10. 2026, pregled Grok 44 D1). */
        private val NI_MEJA = listOf("fail", "pass", "invalid", "incorrect", "wrong", "auth", "login", "log in", "logon",
            "sign in", "credential") + ZAKLEP

        /** RFC 3977: vrstica odgovora najvec 512 bajtov; dovolimo vec, a ne neomejeno (vrstica_bajtov). */
        const val NAJVEC_VRSTICE = 4096
        /** Prvi blok telesa: 256 KiB (prej 1 MiB za vsak BODY), raste do osnove in nato z zetonom rasti. */
        private const val ZACETNI_BLOK = 256 * 1024

        /** Privzeti proracun teles na bazen (Android) in osnova enega telesa (clanek do ~1,25 MiB je ne preseze). */
        const val PRORACUN_BAJTOV = 24L * 1024 * 1024
        const val OSNOVA_TELESA = 1536 * 1024
        private const val MEDPOMNILNIK = 64 * 1024

        /** RFC 3977 priporoca streznikom vsaj 3 min; 60 s je varno pod tem. */
        private const val NAJDLJE_PROSTA_NS = 60_000_000_000L

        private val PROTOKOLI = setOf("TLSv1.3", "TLSv1.2")

        private const val LF: Byte = 10
        private const val CR: Byte = 13
        private const val PIKA: Byte = 46

        /**
         * Ali besedilo odgovora jasno pove mejo povezav (»too many connections«, »connection limit«). Ce omenja tudi
         * prijavo, geslo, avtentikacijo ali zaklep, NI meja: dvom je zavrnjena prijava, ki se ne ponovi (lastnik 26. 10.
         * 2026: nevarnost zaklepa racuna je prioriteta).
         */
        fun mejaPovezav(besedilo: String): Boolean {
            val t = besedilo.lowercase()
            if (NI_MEJA.any { it in t }) return false
            return "connection limit" in t || ("connections" in t && MEJA.any { it in t })
        }

        /**
         * Izid vrstice odgovora [vrstica] na ukaz [faza] (skupni primeri nntp_izid). Koda so prve tri stevke (prva 1-5),
         * za njimi presledek ali konec. Besedilo (lahko ponovi id ali ime) se uporabi samo za razvrstitev in se nikoli ne
         * shrani. Pri AUTHINFO je vse razen 281/381 in jasne meje povezav ([mejaPovezav]) PRIJAVA (RFC 4643 2.3.2) - tudi
         * po uspeli prijavi istega racuna. Pregled Grok 44 D1: prej je bil »481 Authentication failed« po uspeli prijavi
         * meja povezav, zato je po menjavi gesla med sejo staro geslo slo k strezniku z vsako novo povezavo.
         */
        fun izid(faza: Faza, vrstica: String): Izid {
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
            if (avtor) return Izid.PRIJAVA
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
         * ali 1. ZNOVA je vedno z novo povezavo; po AUTHINFO nikoli ponovitev v zanki. Zapah racuna (USTAVI_RACUN) samo po
         * odgovoru na AUTHINFO (pregled Grok 44 D4: 480 pri BODY ali v pozdravu je prej zaklenil pravilno geslo).
         */
        fun ukrep(vrsta: Vrsta, faza: FazaPovezave, stara: Boolean, poskus: Int): Ukrep = when (faza) {
            FazaPovezave.POVEZAVA -> when (vrsta) {
                Vrsta.OMREZJE -> if (poskus == 0) Ukrep.ZNOVA else Ukrep.NAPAKA
                Vrsta.ZASEDEN -> Ukrep.ZMANJSAJ_POCAKAJ
                Vrsta.NAPAKA_STREZNIKA -> Ukrep.POCAKAJ
                Vrsta.DOSTOP, Vrsta.POTRDILO -> Ukrep.USTAVI_BAZEN
                // 48x v pozdravu (ali zapah drugega bazena): geslo ni bilo poslano - bazen se ustavi, novega zapaha ni.
                Vrsta.PRIJAVA -> Ukrep.USTAVI_BAZEN
                else -> Ukrep.NAPAKA
            }
            FazaPovezave.PRIJAVA -> when (vrsta) {
                Vrsta.ZASEDEN -> Ukrep.ZMANJSAJ_POCAKAJ
                // Geslo je morda prislo do streznika, odgovora pa ni.
                Vrsta.OMREZJE, Vrsta.ROK, Vrsta.ZAPRTO, Vrsta.NAPAKA_PROTOKOLA -> Ukrep.NEZNANA_PRIJAVA
                else -> Ukrep.USTAVI_RACUN
            }
            FazaPovezave.UKAZ -> when (vrsta) {
                // 502 pri enem clanku (omejena skupina) ne ustavi bazena; NAPAK_UKAZA_ZAPORED zapored ga ustavi.
                Vrsta.NI_CLANKA, Vrsta.DOSTOP -> Ukrep.VRNI
                // 480/481 pri ukazu: streznik je sejo pozabil - enkrat nova povezava z novo prijavo (vrata prijave).
                Vrsta.PRIJAVA -> if (stara && poskus == 0) Ukrep.ZNOVA else Ukrep.NAPAKA
                Vrsta.POTRDILO -> Ukrep.USTAVI_BAZEN
                Vrsta.ZASEDEN -> if (stara && poskus == 0) Ukrep.ZNOVA else Ukrep.ZMANJSAJ_POCAKAJ
                Vrsta.OMREZJE, Vrsta.NAPAKA_STREZNIKA -> if (poskus == 0) Ukrep.ZNOVA else Ukrep.NAPAKA
                else -> if (stara && poskus == 0) Ukrep.ZNOVA else Ukrep.NAPAKA
            }
        }

        /**
         * Ali bazen sme odpreti novo povezavo (skupni primeri nntp_sme_odpreti): dokler ni zive prijave ([uspela]:
         * prijavljena povezava je v zadnjih [ZIVA_PRIJAVA_S] s uspesno koncala prijavo ali ukaz), se odpira ena sama
         * naenkrat (sonda). Geslo gre ne glede na to vedno ena prijava naenkrat (vrata prijave).
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

        /**
         * Ime gostitelja proti potrdilu (RFC 6125): DNS samo iz subjectAltName (brez CN - javni overitelji imen v CN ne
         * uporabljajo); nadomestni znak samo kot cela prva oznaka in ne nad zadnjima dvema (»*.com« ne); IP samo proti
         * IP v subjectAltName. Brez DNS (InetAddress samo za zapis IP). Neodvisno od platforme TLS.
         */
        fun imeUstreza(gostitelj: String, potrdilo: X509Certificate): Boolean {
            val san = try { potrdilo.subjectAlternativeNames } catch (_: CertificateParsingException) { null } ?: return false
            val g = gostitelj.trim().trimEnd('.').lowercase()
            if (g.isEmpty()) return false
            val ip = ipBajti(g)
            if (ip != null) {
                return san.any { e -> e.size >= 2 && e[0] == 7 && (e[1] as? String)?.let { ipBajti(it.lowercase()) }?.contentEquals(ip) == true }
            }
            val ascii = try { IDN.toASCII(g) } catch (_: IllegalArgumentException) { return false }
            return san.any { e -> e.size >= 2 && e[0] == 2 && (e[1] as? String)?.let { dnsUstreza(ascii, it) } == true }
        }

        /** Ime gostitelja (ASCII, male crke) proti enemu dNSName iz potrdila (RFC 6125 6.4). */
        fun dnsUstreza(gostitelj: String, vzorec: String): Boolean {
            val v = vzorec.trim().trimEnd('.').lowercase()
            if (v.isEmpty() || gostitelj.isEmpty()) return false
            if (!v.startsWith("*.")) return '*' !in v && v == gostitelj
            val pripona = v.substring(1)
            if ('*' in pripona || pripona.count { it == '.' } < 2) return false
            if (!gostitelj.endsWith(pripona)) return false
            val prva = gostitelj.substring(0, gostitelj.length - pripona.length)
            return prva.isNotEmpty() && '.' !in prva
        }

        /** Bajti naslova IP iz zapisa (IPv4 s stirimi stevili ali IPv6 z »:«); null = ni IP. Brez DNS. */
        private fun ipBajti(s: String): ByteArray? {
            val v4 = Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}").matches(s)
            if (!v4 && ':' !in s) return null
            if (v4 && s.split('.').any { it.toInt() > 255 }) return null
            if (!v4 && !s.all { it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.' }) return null
            return try { InetAddress.getByName(s).address } catch (_: Exception) { null }
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
            Izid.ZAPRTO -> Vrsta.ZAPRTO
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

    /**
     * Ena povezava TLS; uporablja jo ena nit naenkrat. Nastane prazna (bazen jo steje, preden se odpre), [odpri] jo
     * poveze: vticnica je v [surova] PRED connect, zato jo [close] bazena zapre tudi med vzpostavljanjem.
     */
    private inner class Povezava {
        @Volatile private var surova: Socket? = null
        @Volatile private var tls: SSLSocket? = null
        private var vhod: InputStream? = null
        private var izhod: OutputStream? = null
        private val buf = ByteArray(MEDPOMNILNIK)
        private var poz = 0
        private var dolz = 0
        /** Zadnja raba (System.nanoTime) - prosta povezava, nedejavna dlje od [PROSTA_NAJDLJE_S], gre stran. */
        @Volatile var prosta = System.nanoTime()
        /** Pozdrav in prijava sta uspela. */
        @Volatile var pripravljena = false
        /** Bazen: ta povezava je edina, ki se odpira, dokler prijava ne uspe. */
        var sonda = false
        /** Kje je nastala zadnja napaka ([ukrep]). */
        @Volatile var faza = FazaPovezave.POVEZAVA
        /** Zadnja uspela prijava ali odgovor na ukaz (System.nanoTime) - ziva prijava ([ZIVA_PRIJAVA_S]). */
        @Volatile var zadnjiUspeh = 0L

        fun ukaz(v: String) {
            try {
                val o = izhod ?: throw NapakaNntp(Vrsta.ZAPRTO)
                o.write((v + "\r\n").toByteArray(Charsets.UTF_8))
                o.flush()
            } catch (e: IOException) {
                throw napakaVticnice(e)
            }
        }

        private fun beri(b: ByteArray, od: Int, koliko: Int): Int {
            val r = try {
                (vhod ?: throw NapakaNntp(Vrsta.ZAPRTO)).read(b, od, koliko)
            } catch (e: IOException) {
                throw napakaVticnice(e)
            }
            if (r < 0) throw NapakaNntp(if (zaprto) Vrsta.ZAPRTO else Vrsta.OMREZJE)
            return r
        }

        fun odgovor(): String {
            var pregledano = 0
            while (true) {
                var k = poz + pregledano
                while (k < dolz && buf[k] != LF) k++
                if (k < dolz) {
                    // Tudi, ce je LF prisel v istem branju (Grok 37 C7 velja tudi tu).
                    if (k - poz > NAJVEC_VRSTICE) throw NapakaNntp(Vrsta.NAPAKA_PROTOKOLA)
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

        /** TCP, TLS (potrdilo in ime), pozdrav in prijava. */
        fun odpri() {
            faza = FazaPovezave.POVEZAVA
            val s = Socket()
            surova = s
            val t: SSLSocket
            try {
                if (zaprto) throw NapakaNntp(Vrsta.ZAPRTO)
                s.connect(InetSocketAddress(n.gostitelj, n.vrata), n.rokPovezaveMs)
                s.soTimeout = n.rokBranjaMs
                s.tcpNoDelay = true
                t = tovarna.createSocket(s, n.gostitelj, n.vrata, true) as SSLSocket
                tls = t
                if (zaprto) throw NapakaNntp(Vrsta.ZAPRTO)
                val protokoli = t.supportedProtocols.filter { it in PROTOKOLI }
                if (protokoli.isEmpty()) throw NapakaNntp(Vrsta.POTRDILO)
                val p = t.sslParameters
                if (n.endpointHttps) p.endpointIdentificationAlgorithm = "HTTPS"
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
                // Nasa preverba imena VEDNO, pred pozdravom in AUTHINFO (Grok 35 Y1): poverilnice ne gredo k napacnemu imenu.
                val list = seja.peerCertificates.firstOrNull() as? X509Certificate ?: throw NapakaNntp(Vrsta.POTRDILO)
                if (!imeUstreza(n.gostitelj, list)) throw NapakaNntp(Vrsta.POTRDILO)
                if (imePotrdila != null && !imePotrdila.verify(n.gostitelj, seja)) throw NapakaNntp(Vrsta.POTRDILO)
                vhod = t.inputStream
                izhod = t.outputStream
            } catch (e: IOException) {
                zapri()
                throw e as? NapakaNntp ?: napakaVticnice(e)
            }
            odprto.incrementAndGet()
            preveri(Faza.POZDRAV)
            if (n.uporabnik != null) prijava(n.uporabnik, n.geslo)
            faza = FazaPovezave.UKAZ
            pripravljena = true
        }

        private fun prijava(uporabnik: String, geslo: String?) {
            if (!varenArgument(uporabnik) || (geslo != null && !varenArgument(geslo))) throw NapakaNntp(Vrsta.PRIJAVA)
            // Vrata prijave racuna (Grok 44 D1): ta prijava racuna je edina v procesu; zavrnjen racun ali zaprt bazen -
            // geslo ne gre k strezniku niti iz vzporedne povezave.
            predPrijavo()?.let { throw NapakaNntp(it) }
            var izidPrijave: Vrsta? = Vrsta.NAPAKA_PROTOKOLA   // nepricakovana napaka sredi prijave: odgovor ni znan
            try {
                faza = FazaPovezave.PRIJAVA
                ukaz("AUTHINFO USER $uporabnik")
                if (preveri(Faza.UPORABNIK) != Izid.OK) {
                    if (geslo == null) throw NapakaNntp(Vrsta.PRIJAVA, 381)
                    ukaz("AUTHINFO PASS $geslo")
                    preveri(Faza.GESLO)
                }
                zadnjiUspeh = System.nanoTime()
                izidPrijave = null
            } catch (e: NapakaNntp) {
                izidPrijave = e.vrsta
                throw e
            } finally {
                poPrijavi(izidPrijave)   // zapah ali stevec PRED sprostitvijo vrat prijave
            }
        }

        fun body(id: String, rokDo: Long): ByteArray {
            ukaz("BODY <$id>")
            val v = odgovor()
            val i = izid(Faza.BODY, v)
            if (i == Izid.OK || i == Izid.NI_CLANKA) zadnjiUspeh = System.nanoTime()   // streznik je odgovoril: seja ziva
            vrsta(i)?.let { throw NapakaNntp(it, v.take(3).toIntOrNull() ?: 0) }
            // Odgovor za drug clanek (Grok 35 Y7): povezava ni usklajena - napaka protokola, povezava gre stran.
            if (!idOdgovoraUstreza(v, id)) throw NapakaNntp(Vrsta.NAPAKA_PROTOKOLA, 222)
            return blok(rokDo)
        }

        fun stat(id: String): Boolean {
            ukaz("STAT <$id>")
            val v = odgovor()
            val i = izid(Faza.STAT, v)
            if (i == Izid.OK || i == Izid.NI_CLANKA) zadnjiUspeh = System.nanoTime()
            if (i == Izid.NI_CLANKA) return false
            vrsta(i)?.let { throw NapakaNntp(it, v.take(3).toIntOrNull() ?: 0) }
            if (!idOdgovoraUstreza(v, id)) throw NapakaNntp(Vrsta.NAPAKA_PROTOKOLA, 223)
            if (dolz > poz) pripravljena = false   // bajti za odgovorom: povezava ne gre v bazen
            return true
        }

        /**
         * Telo do vrstice ».« (brez nje), brez podvojenih pik ([telo]). Medpomnilnik raste do osnove proracuna, cez njo
         * samo z zetonom rasti (Grok 35 Y3: prej 1 MiB na vsak BODY in do 16 MiB na povezavo brez skupne meje).
         */
        private fun blok(rokDo: Long): ByteArray {
            if (!proracun.zasezi(rokDo)) throw NapakaNntp(Vrsta.ROK)
            var rast = false
            try {
                var izid = ByteArray(minOf(maxOf(ZACETNI_BLOK, dolz - poz), NAJVEC_TELESA))
                var n = dolz - poz
                System.arraycopy(buf, poz, izid, 0, n)
                poz = 0
                dolz = 0
                var od = 0
                while (true) {
                    val k = najdiKonec(izid, 0, od, n)
                    if (k >= 0) {
                        // Brez cevovoda streznik za koncem ne poslje nicesar. Ce pa (Grok 35 Y7), bi se ostanek prebral kot
                        // odgovor na naslednji ukaz - povezava zato ne gre v bazen (telo je celo in velja).
                        if (n > k + 3) pripravljena = false
                        return odstraniPike(izid, 0, k)
                    }
                    od = maxOf(0, n - 2)   // konec je lahko razdeljen med dve branji
                    if (System.nanoTime() - rokDo > 0) throw NapakaNntp(Vrsta.ROK)
                    if (n == izid.size) {
                        if (izid.size >= NAJVEC_TELESA) throw NapakaNntp(Vrsta.NAPAKA_PROTOKOLA)
                        val nova = if (izid.size < proracun.osnova) minOf(izid.size * 2, proracun.osnova)
                            else minOf(izid.size * 2, NAJVEC_TELESA)
                        if (nova > proracun.osnova && !rast) {
                            if (!proracun.rasti(rokDo)) throw NapakaNntp(Vrsta.ROK)
                            rast = true
                        }
                        izid = izid.copyOf(nova)
                    }
                    n += beri(izid, n, izid.size - n)
                }
            } finally {
                proracun.sprosti(rast)
            }
        }

        /** Zapre brez pisanja (tudi z glavne niti in med branjem druge niti): najprej vticnica pod TLS. */
        fun zapri() {
            pripravljena = false
            try { surova?.close() } catch (_: IOException) { }
            try { tls?.close() } catch (_: IOException) { }
        }

        /** Vljudno (QUIT) - ponudnik takoj sprosti mesto; samo za prosto povezavo, ki ni v rabi. */
        fun zapriVljudno() {
            try {
                tls?.soTimeout = 1000
                izhod?.let { it.write("QUIT\r\n".toByteArray(Charsets.US_ASCII)); it.flush() }
            } catch (_: IOException) { }
            zapri()
        }
    }

    /**
     * Skupen proracun pomnilnika teles (enako namizje, core/usenet_nntp.Proracun): vsako telo pred branjem zaseze osnovo
     * [osnova]; vecje vzame se edini zeton rasti (do [NAJVEC_TELESA]). Nihce ne caka, medtem ko drzi del rasti - zato ni
     * zastoja (zasezanje po delih je na namizju 6 hkratnih teles po 1,2 MiB v 3 MiB zaklenilo do roka). Steje medpomnilnik
     * telesa; kratkotrajna kopija brez pik ni steta.
     */
    internal class Proracun(bajtov: Long, val osnova: Int = OSNOVA_TELESA) {
        val rast: Int = maxOf(0, NAJVEC_TELESA - osnova)
        val bajtov: Long = maxOf(bajtov, osnova.toLong() + rast)
        private val z = ReentrantLock()
        private val c = z.newCondition()
        private var osnov = 0
        private var rastZasedena = false
        @Volatile var najvecZasedeno = 0L

        val zasedeno: Long get() = osnov.toLong() * osnova + if (rastZasedena) rast else 0

        private fun cakaj(rokDo: Long, lahko: () -> Boolean): Boolean {
            while (!lahko()) {
                val ns = rokDo - System.nanoTime()
                if (ns <= 0) return false
                try { c.awaitNanos(ns) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); return false }
            }
            return true
        }

        fun zasezi(rokDo: Long): Boolean {
            z.lock()
            try {
                if (!cakaj(rokDo) { (osnov + 1).toLong() * osnova + rast <= bajtov }) return false
                osnov++
                najvecZasedeno = maxOf(najvecZasedeno, zasedeno)
                return true
            } finally { z.unlock() }
        }

        fun rasti(rokDo: Long): Boolean {
            z.lock()
            try {
                if (!cakaj(rokDo) { !rastZasedena }) return false
                rastZasedena = true
                najvecZasedeno = maxOf(najvecZasedeno, zasedeno)
                return true
            } finally { z.unlock() }
        }

        fun sprosti(rast: Boolean) {
            z.lock()
            try {
                osnov--
                if (rast) rastZasedena = false
                c.signalAll()
            } finally { z.unlock() }
        }
    }

    internal val proracun = Proracun(n.proracunBajtov)

    /** IOException vticnice -> vrsta: rok, zaprto (bazen zaprt) ali omrezje. */
    private fun napakaVticnice(e: IOException): NapakaNntp = when {
        e is NapakaNntp -> e
        zaprto -> NapakaNntp(Vrsta.ZAPRTO, vzrok = e)
        e is SocketTimeoutException -> NapakaNntp(Vrsta.ROK, vzrok = e)
        else -> NapakaNntp(Vrsta.OMREZJE, vzrok = e)
    }

    // ---------------------------------------------------------------- bazen (vse spodaj pod [zaklep])

    private val zaklep = ReentrantLock()
    private val pogoj = zaklep.newCondition()
    private val prosti = ArrayDeque<Povezava>()
    private val vRabi = HashSet<Povezava>()
    private val odprto = AtomicInteger()
    @Volatile private var zaprto = false
    /** Zapah racuna se preveri ob prvi zahtevi ([preveriZapah]) - hramba in pocasen odtis nikoli na glavni niti. */
    @Volatile private var ustavljen: Vrsta? = null
    @Volatile private var zapahPreverjen = false

    init {
        BAZENI += this
    }
    private val odtis = odtisRacuna(n.gostitelj, n.uporabnik, n.geslo)
    private val vrata: ReentrantLock? = n.uporabnik?.let { vrataPrijave(n.gostitelj, it) }
    private var zgornja = n.povezav
    private var najvec = n.povezav
    private var sondaTece = false
    private var premorS = 0L
    private var premorDo = 0L
    private var premorVrsta = Vrsta.ZASEDEN
    private var zadnjaMeja = 0L
    /** Zaporedni ukazi, zavrnjeni z 502 ali 480/481 ([NAPAK_UKAZA_ZAPORED]). */
    private var napakUkaza = 0

    /** Trenutno odprte povezave (proste in v rabi). */
    val odprtih: Int get() = zaklep.zZaklepom { prosti.count { it.pripravljena } + vRabi.count { it.pripravljena } }

    /** Vse povezave, odprte od zacetka (za meritev in preizkus ponovne rabe). */
    val odprtoSkupaj: Int get() = odprto.get()

    /** Trenutna meja povezav bazena (zmanjsa jo ponudnik z mejo povezav, [nastaviNajvec] klicatelj). */
    val najvecPovezav: Int get() = zaklep.zZaklepom { najvec }

    private inline fun <T> ReentrantLock.zZaklepom(f: () -> T): T {
        lock()
        try { return f() } finally { unlock() }
    }

    private fun prijavljenih(): Int = prosti.count { it.pripravljena } + vRabi.count { it.pripravljena }

    /** Prijavljene povezave, ki so v zadnjih [ZIVA_PRIJAVA_S] s uspesno koncale prijavo ali ukaz (Grok 44 D1). */
    private fun zivihPrijav(zdaj: Long): Int {
        val meja = ZIVA_PRIJAVA_S * 1_000_000_000L
        return prosti.count { it.pripravljena && zdaj - it.zadnjiUspeh <= meja } +
            vRabi.count { it.pripravljena && zdaj - it.zadnjiUspeh <= meja }
    }

    /**
     * Tik pred AUTHINFO (pregled Grok 44 D1): pocaka na vrata prijave racuna - prijava racuna je v procesu vedno ena
     * naenkrat, tudi po uspeli prijavi - in vrne oviro (zaprt bazen, zapah, rok) ali null. Pri null so vrata zaprta za
     * druge, dokler [poPrijavi] ne zapise izida. Cakanje konca [close] bazena ali rok povezave.
     */
    private fun predPrijavo(): Vrsta? {
        val v = vrata ?: return null
        val rokDo = System.nanoTime() + n.rokPovezaveMs * 1_000_000L
        while (true) {
            if (zaprto) return Vrsta.ZAPRTO
            val ns = maxOf(0L, minOf(rokDo - System.nanoTime(), 50_000_000L))
            try {
                if (v.tryLock(ns, TimeUnit.NANOSECONDS)) break
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return Vrsta.ZAPRTO
            }
            if (System.nanoTime() - rokDo >= 0) return Vrsta.ROK   // faza je se povezava: geslo ni bilo poslano
        }
        val ovira = zaklep.zZaklepom {
            if (ustavljen == null && odtis != null && odtis in ZAVRNJENI) ustavljen = Vrsta.PRIJAVA   // zapah drugega bazena racuna
            if (zaprto) Vrsta.ZAPRTO else ustavljen
        }
        if (ovira != null) v.unlock()
        return ovira
    }

    /**
     * Izid prijave ([vrsta] null = uspela), zapisan PRED sprostitvijo vrat prijave: naslednja povezava ze vidi zapah
     * (zavrnjeno geslo gre k strezniku enkrat) ali drugo prijavo brez odgovora (prvic premor, drugic zapah).
     */
    private fun poPrijavi(vrsta: Vrsta?) {
        val v = vrata ?: return
        try {
            val k = odtis ?: return
            if (vrsta == null) {
                NEZNANE.remove(k)
                pozabiZapahe(ponudnik(n.gostitelj), n.uporabnik!!)   // racun dela s tem geslom: stari zapahi niso potrebni
                return
            }
            when (ukrep(vrsta, FazaPovezave.PRIJAVA, false, 0)) {
                Ukrep.NEZNANA_PRIJAVA -> if ((NEZNANE.merge(k, 1) { a, b -> a + b } ?: 1) >= 2) zapahni()
                Ukrep.USTAVI_RACUN -> zapahni()
                else -> { }
            }
        } finally {
            v.unlock()
        }
    }

    /** Zapah racuna (zavrnjena prijava ali druga zapored brez odgovora): nobenega AUTHINFO vec, bazen ustavljen. */
    private fun zapahni() {
        odtis?.let {
            NEZNANE.remove(it)
            // Trajno (Grok 44 D2): prezivi ponovni zagon, sesutje in posodobitev aplikacije.
            if (ZAVRNJENI.add(it)) zapisiZapah(ponudnik(n.gostitelj), n.uporabnik!!, n.geslo.orEmpty())
        }
        zaklep.zZaklepom {
            if (ustavljen == null) ustavljen = Vrsta.PRIJAVA
            pogoj.signalAll()
        }
    }

    /** Trajni zapah racuna ob prvi zahtevi (bere hrambo; zDelom ni nikoli na glavni niti). */
    private fun preveriZapah() {
        if (zapahPreverjen) return
        if (prijavaZavrnjena(n.gostitelj, n.uporabnik, n.geslo)) zaklep.zZaklepom { if (ustavljen == null) ustavljen = Vrsta.PRIJAVA }
        zapahPreverjen = true
    }

    /**
     * »Preizkusi povezavo«: vedno nova povezava s prijavo (prosta povezava ni dokaz, da streznik se dela). Zapah velja
     * tudi tu. [odkleni]: uporabnik je sam kliknil »Preveri« (lastnik 26. 10. 2026: zapah odklene samo nov vpis poverilnic
     * ali uporabnikov »Preveri«) - en nov poskus prijave; nikoli samodejno. Napaka: [NapakaNntp].
     */
    fun preveri(odkleni: Boolean = false) {
        if (odkleni && odtis != null) {
            dovoliPrijavoZnova(n.gostitelj, n.uporabnik, n.geslo)
            zapahPreverjen = true
            zaklep.zZaklepom {
                if (ustavljen == Vrsta.PRIJAVA) ustavljen = null
                pogoj.signalAll()
            }
        }
        zDelom(true) { }
    }

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

    /**
     * Zgornja meja povezav od zunaj (korak 3: proracun povezav med napravami kroga, U-1). Zmanjsanje zapre odvecne proste
     * povezave; povecanje velja takoj, razen ce je bazen zmanjsal ponudnik (rast po [RAST_BAZENA_S]).
     */
    fun nastaviNajvec(povezav: Int) {
        val odvec = zaklep.zZaklepom {
            zgornja = povezav.coerceIn(1, NAJVEC_POVEZAV)
            if (najvec > zgornja || zadnjaMeja == 0L) najvec = zgornja
            pogoj.signalAll()
            odvec()
        }
        for (p in odvec) p.zapriVljudno()
    }

    /** Zapre vse povezave (tudi tiste v rabi in v vzpostavljanju - te koncajo z ZAPRTO). Brez pisanja, z vsake niti. */
    override fun close() {
        val vse = zaklep.zZaklepom {
            zaprto = true
            val v = prosti.toList() + vRabi.toList()
            prosti.clear()
            pogoj.signalAll()
            v
        }
        for (p in vse) p.zapri()
    }

    private fun odvec(): List<Povezava> {
        val odvec = ArrayList<Povezava>()
        while (prosti.isNotEmpty() && prijavljenih() > najvec) odvec += prosti.removeFirst()
        return odvec
    }

    private fun msDo(ns: Long): Long = (ns - System.nanoTime()) / 1_000_000L

    /** (povezava, stara): prosta ali nova, ce jo vrata dovolijo ([smeOdpreti]); sicer caka do [rokDo] (nanoTime). */
    private fun vzemi(rokDo: Long, sveza: Boolean): Pair<Povezava, Boolean> {
        val zavrzi = ArrayList<Povezava>()
        try {
            zaklep.lock()
            try {
                while (true) {
                    if (zaprto) throw NapakaNntp(Vrsta.ZAPRTO)
                    if (ustavljen == null && odtis != null && odtis in ZAVRNJENI) {
                        ustavljen = Vrsta.PRIJAVA   // zapah drugega bazena istega racuna: tudi stare seje ne (Grok 44 D2)
                        zavrzi += prosti
                        prosti.clear()
                    }
                    ustavljen?.let { throw NapakaNntp(it) }
                    val zdaj = System.nanoTime()
                    if (najvec < zgornja && zadnjaMeja != 0L && zdaj - zadnjaMeja >= RAST_BAZENA_S * n.msNaSekundoPremora * 1_000_000L) {
                        najvec++   // druga naprava je morda koncala: eno mesto nazaj na 10 min
                        zadnjaMeja = zdaj
                    }
                    while (prosti.isNotEmpty() && zdaj - prosti.first().prosta > PROSTA_NAJDLJE_S * 1_000_000_000L) {
                        zavrzi += prosti.removeFirst()   // ponudnik ali NAT jo je verjetno ze zaprl
                    }
                    if (prosti.isNotEmpty() && !sveza) {
                        val p = prosti.removeLast()
                        vRabi += p
                        return p to true
                    }
                    if (sveza && prosti.isNotEmpty() && prosti.size + vRabi.size >= najvec) {
                        zavrzi += prosti.removeFirst()   // prostor za svezo povezavo: najstarejsa prosta gre
                        continue
                    }
                    val uspela = zivihPrijav(zdaj) > 0
                    val premor = zdaj - premorDo < 0
                    if (smeOdpreti(false, premor, prosti.size + vRabi.size, najvec, uspela, sondaTece)) {
                        val p = Povezava()
                        p.sonda = !uspela
                        if (p.sonda) sondaTece = true
                        vRabi += p
                        return p to false
                    }
                    if (zdaj - rokDo >= 0) throw NapakaNntp(if (premor) premorVrsta else Vrsta.ZASEDEN)
                    // Nobena povezava se ne bo sprostila pred rokom: takoj (brez cakanja na rok in brez zanke).
                    if (vRabi.isEmpty() && premor && premorDo - rokDo >= 0) throw NapakaNntp(premorVrsta)
                    var cakaj = rokDo - zdaj
                    if (premor) cakaj = minOf(cakaj, premorDo - zdaj)
                    try {
                        pogoj.awaitNanos(cakaj)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw NapakaNntp(Vrsta.ZAPRTO, vzrok = e)
                    }
                }
            } finally {
                zaklep.unlock()
            }
        } finally {
            for (p in zavrzi) p.zapriVljudno()
        }
    }

    private fun vrni(p: Povezava) {
        val obdrzi = zaklep.zZaklepom {
            vRabi.remove(p)
            if (p.sonda) {
                p.sonda = false
                sondaTece = false
            }
            // Steje samo prijavljene: povezava v odpiranju (morda dobi »prevec povezav«) ne izrine delujoce.
            val da = !zaprto && ustavljen == null && p.pripravljena && prijavljenih() < najvec
            if (da) {
                p.prosta = System.nanoTime()
                prosti.addLast(p)
            }
            pogoj.signalAll()
            da
        }
        if (!obdrzi) p.zapri()
    }

    /** Povezava je odprta in prijavljena: premor nazaj na nic; bazen ni manjsi od prijavljenih (te je streznik sprejel). */
    private fun odprta() = zaklep.zZaklepom {
        premorS = 0
        najvec = maxOf(najvec, minOf(zgornja, prijavljenih()))
        pogoj.signalAll()
    }

    /** Ustavi bazen (zavrnjena prijava, dostop, potrdilo): vse povezave zaprte, zahteve v teku prekinjene. */
    private fun ustavi(vrsta: Vrsta, racun: Boolean) {
        if (racun) zapahni()
        val vse = zaklep.zZaklepom {
            if (ustavljen == null) ustavljen = vrsta
            val v = prosti.toList() + vRabi.toList()
            prosti.clear()
            pogoj.signalAll()
            v
        }
        for (p in vse) p.zapri()
        if (racun && odtis != null) {
            // Zavrnjena prijava ustavi vse povezave racuna - tudi drugih zivih bazenov (Grok 44 D2).
            val drugi = synchronized(BAZENI) { BAZENI.filter { it !== this && it.odtis == odtis } }
            for (b in drugi) b.ustavi(Vrsta.PRIJAVA, false)
        }
    }

    private fun premor(zmanjsaj: Boolean, vrsta: Vrsta) {
        val odvec = zaklep.zZaklepom {
            val zdaj = System.nanoTime()
            val tece = zdaj - premorDo < 0
            val (nn, ps) = novPremor(najvec, prijavljenih(), premorS, tece, zmanjsaj)
            najvec = nn
            premorS = ps
            if (!tece) premorDo = zdaj + ps * n.msNaSekundoPremora * 1_000_000L
            premorVrsta = vrsta
            if (zmanjsaj) zadnjaMeja = zdaj
            pogoj.signalAll()
            odvec()
        }
        for (p in odvec) p.zapriVljudno()
    }

    /** Stara povezava je bila mrtva: proste, nedejavne vsaj tako dolgo, so verjetno tudi. */
    private fun zavrziStarejse(prosta: Long) {
        val stare = zaklep.zZaklepom {
            val s = prosti.filter { it.prosta - prosta <= 0 }
            prosti.removeAll(s.toSet())
            s
        }
        for (p in stare) p.zapri()
    }

    private fun <T> zDelom(svezaNajprej: Boolean = false, delo: (Povezava) -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "NNTP ne sme teci na glavni niti" }
        preveriZapah()
        val rokDo = System.nanoTime() + n.rokCakanjaMs * 1_000_000L
        var sveza = svezaNajprej
        var poskus = 0
        while (true) {
            val (p, stara) = vzemi(rokDo, sveza)
            try {
                if (!stara) {
                    p.odpri()
                    odprta()
                }
                val r = delo(p)
                zaklep.zZaklepom { napakUkaza = 0 }
                vrni(p)
                return r
            } catch (e: IOException) {
                val napaka = e as? NapakaNntp ?: napakaVticnice(e)
                var u = ukrep(napaka.vrsta, p.faza, stara, minOf(poskus, 1))
                // 480 na stari seji je pozabljena seja (ZNOVA), ne racun brez pravic: steje samo na sveze prijavljeni.
                if (p.faza == FazaPovezave.UKAZ && (napaka.vrsta == Vrsta.DOSTOP || (napaka.vrsta == Vrsta.PRIJAVA && !stara))) {
                    if (zaklep.zZaklepom { ++napakUkaza } >= NAPAK_UKAZA_ZAPORED) u = Ukrep.USTAVI_BAZEN   // racun brez pravic
                } else if (napaka.vrsta == Vrsta.NI_CLANKA) {
                    zaklep.zZaklepom { napakUkaza = 0 }
                }
                if (u != Ukrep.VRNI) p.zapri()
                // Stanje bazena PRED sprostitvijo sonde: cakajoca nit ne sme poslati gesla, preden je zapah postavljen.
                when (u) {
                    Ukrep.USTAVI_RACUN -> ustavi(Vrsta.PRIJAVA, true)
                    Ukrep.USTAVI_BAZEN -> ustavi(napaka.vrsta, false)
                    // Stevec je ze zapisal poPrijavi (pod vrati prijave): drugic zapored je racun ze zapahnjen.
                    Ukrep.NEZNANA_PRIJAVA -> ustavljen.let { if (it != null) ustavi(it, false) else premor(false, Vrsta.OMREZJE) }
                    Ukrep.ZMANJSAJ_POCAKAJ, Ukrep.POCAKAJ -> premor(u == Ukrep.ZMANJSAJ_POCAKAJ, napaka.vrsta)
                    Ukrep.ZNOVA -> if (stara) zavrziStarejse(p.prosta)
                    else -> { }
                }
                vrni(p)
                if (zaprto) throw NapakaNntp(Vrsta.ZAPRTO)
                ustavljen?.let { throw NapakaNntp(it, napaka.koda) }
                if (u == Ukrep.VRNI || u == Ukrep.NAPAKA || poskus + 1 >= n.poskusov) throw napaka
                sveza = u == Ukrep.ZNOVA   // ZNOVA vedno z novo povezavo; pri premoru caka obstojeco
                poskus++
            } catch (e: Throwable) {
                // RuntimeException, OutOfMemoryError ...: v medpomnilniku je lahko pol odgovora - povezava ne v bazen.
                p.zapri()
                vrni(p)
                throw e
            }
        }
    }
}
