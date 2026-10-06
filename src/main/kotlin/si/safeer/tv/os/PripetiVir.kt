// Media3 oznacuje del API-ja kot @UnstableApi (se lahko spremeni med razlicicami). Uporabljamo ga
// namerno (DASH, lasten vir podatkov); ob posodobitvi Media3 to datoteko preverimo.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package si.safeer.tv.os

import si.safeer.tv.R

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Vir podatkov za ExoPlayer, ki bere z racunalnika prek OkHttp s pripetim potrdilom Safeer
 * Controla in zetonom te naprave. Obsegi (Range) za iskanje po datoteki; brez potrdila z
 * dobljenim odtisom povezava ne steče.
 */
class PripetiVir(private val odjemalec: OkHttpClient, private val zeton: String,
                 private val context: android.content.Context? = null, private val naprava: String = "") : BaseDataSource(true) {

    /** [naprava] (id v Linku) omogoci tok prek Global Linka, kadar naprava ni v istem omrezju. */
    class Tovarna(streznikOdtis: String, private val zeton: String,
                  context: android.content.Context? = null, private val naprava: String = "") : DataSource.Factory {
        private val odjemalec = odjemalecZaStreznik(streznikOdtis)
        private val app = context?.applicationContext
        override fun createDataSource(): DataSource = PripetiVir(odjemalec, zeton, app, naprava)
    }

    private var odziv: Response? = null
    private var tok: InputStream? = null
    private var uri: Uri? = null
    private var preostane: Long = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val doKonca = dataSpec.length == C.LENGTH_UNSET.toLong()
        fun zahteva(url: String): Response {
            val z = Request.Builder().url(url).header("X-Safeer-Token", zeton)
            if (dataSpec.position != 0L || !doKonca) {
                val konec = if (doKonca) "" else (dataSpec.position + dataSpec.length - 1).toString()
                z.header("Range", "bytes=${dataSpec.position}-$konec")
            }
            return odjemalec.newCall(z.build()).execute()
        }
        // Doma neposredno, zdoma isti tok prek Global Linka do Huba te naprave (gl. [poPoti]).
        val r = poPoti(context, naprava, dataSpec.uri.toString()) { zahteva(it) }
        if (!r.isSuccessful) {
            r.close()
            throw IOException("HTTP ${r.code}")
        }
        val telo = r.body ?: run { r.close(); throw IOException("prazen odgovor") }
        odziv = r
        tok = telo.byteStream()
        if (r.code == 200 && dataSpec.position > 0) {
            // Streznik obsega ni upostevala: preskocimo do zeljenega mesta.
            var ostane = dataSpec.position
            val kos = ByteArray(64 * 1024)
            while (ostane > 0) {
                val n = tok!!.read(kos, 0, minOf(kos.size.toLong(), ostane).toInt())
                if (n < 0) throw IOException("datoteka je krajsa od zahtevanega mesta")
                ostane -= n
            }
        }
        preostane = when {
            !doKonca -> dataSpec.length
            telo.contentLength() >= 0 -> telo.contentLength()
            else -> C.LENGTH_UNSET.toLong()
        }
        transferStarted(dataSpec)
        return preostane
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (preostane == 0L) return C.RESULT_END_OF_INPUT
        val t = tok ?: throw IOException("vir ni odprt")
        val koliko = if (preostane == C.LENGTH_UNSET.toLong()) length else minOf(preostane, length.toLong()).toInt()
        val n = t.read(buffer, offset, koliko)
        if (n == -1) return C.RESULT_END_OF_INPUT
        if (preostane != C.LENGTH_UNSET.toLong()) preostane -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try { tok?.close() } catch (_: Throwable) { }
        try { odziv?.close() } catch (_: Throwable) { }
        val bilo = tok != null
        tok = null
        odziv = null
        if (bilo) transferEnded()
    }

    companion object {
        /** OkHttp s pripetim potrdilom streznika datotek (odtis SHA-256 iz odgovora `files.list`). */
        fun odjemalecZaStreznik(odtis: String): OkHttpClient {
            val (tovarna, zaupnik) = Pin.tovarna(odtis)
            return OkHttpClient.Builder()
                .sslSocketFactory(tovarna, zaupnik)
                .hostnameVerifier(Pin.brezImena)
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        private val odjemalci = java.util.concurrent.ConcurrentHashMap<String, OkHttpClient>()

        /** Kontekst aplikacije (za rele), zapomnjen ob prvi rabi - urejanje ga nima pri roki. */
        @Volatile private var aplikacija: android.content.Context? = null

        /**
         * Kot [odjemalecZaStreznik], le da gre branje (GET, HEAD) po isti poti kot predvajanje: doma
         * neposredno, zdoma prek Global Linka. Za besedilo, slike in slicice, ki ne tecejo skozi
         * predvajalnik - prej so zdoma ostale brez poti, ceprav je glasba z iste naprave igrala.
         * En odjemalec na napravo, da se povezava (in z njo kanal releja) uporabi veckrat.
         */
        fun odjemalecZaNapravo(odtis: String, context: android.content.Context?, naprava: String): OkHttpClient {
            val app = context?.applicationContext
            if (app != null) aplikacija = app
            if (app == null || naprava.isBlank()) return odjemalecZaStreznik(odtis)
            if (odjemalci.size > 8) odjemalci.clear()
            return odjemalci.getOrPut("$odtis|$naprava") {
                odjemalecZaStreznik(odtis).newBuilder().addInterceptor(object : Interceptor {
                    override fun intercept(chain: Interceptor.Chain): Response {
                        val z = chain.request()
                        if (z.method != "GET" && z.method != "HEAD") return chain.proceed(z)
                        return poPoti(app, naprava, z.url.toString()) { url -> chain.proceed(z.newBuilder().url(url).build()) }
                    }
                }).build()
            }
        }

        /** Ali do te naprave trenutno beremo prek Global Linka (pocasnejsa pot, na telefonu lahko mobilni podatki). */
        fun prekGlobalLinka(naprava: String, url: String): Boolean =
            si.safeer.tv.link.GlobalLink.zdoma ||
                PotDoNaprave.zadnja(PotDoNaprave.kljuc(naprava, url)) == PotDoNaprave.Pot.RELE

        /** Preverjanje poti v ozadju (sonda); nikoli na niti, ki riše. */
        private val ozadje = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "safeer-pot").apply { isDaemon = true }
        }

        /** Toliko casa ima naprava v istem omrezju, da sprejme povezavo; kdor ne odgovori, je zdoma. */
        private const val SONDA_MS = 700

        /** Kratek poskus povezave na naslov naprave: ali je v tem omrezju. Brez podatkov in brez rokovanja TLS. */
        private fun sonda(url: String): Boolean = try {
            val u = java.net.URI(url)
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(u.host, if (u.port > 0) u.port else 443), SONDA_MS) }
            true
        } catch (_: Throwable) { false }

        private fun vrstniRed(context: android.content.Context?, naprava: String, url: String): List<PotDoNaprave.Pot> =
            PotDoNaprave.vrstniRed(PotDoNaprave.kljuc(naprava, url),
                // Nastavitev »Preizkus: tudi doma prek interneta« velja tudi za branje datotek: rele prvi.
                si.safeer.tv.link.GlobalLink.zdoma || (context != null && si.safeer.tv.link.GlobalLink.samoRele(context)),
                releMogoc(context, naprava, url), { android.os.SystemClock.elapsedRealtime() },
                // Naprava brez naslova v domacem omrezju (mobilni podatki): sonda nima kam - takoj prek Huba.
                { !PotDoNaprave.brezOmrezja(url) && sonda(url) },
                { delo -> ozadje.execute(delo) })

        /**
         * Pot do naprave ugotovimo vnaprej (v ozadju), takoj ko vemo za njen streznik: prvi dotik datoteke
         * potem ne caka na sondo. [naprej] (na niti ozadja) pove, da je pot znana.
         */
        fun ogrej(context: android.content.Context?, naprava: String, url: String, naprej: () -> Unit = {}) {
            val app = context?.applicationContext
            if (app != null) aplikacija = app
            try { ozadje.execute { try { vrstniRed(app, naprava, url) } catch (_: Throwable) { }; naprej() } }
            catch (_: Throwable) { }
        }

        /**
         * Izvede [zahteva] z naslovom po pravi poti do naprave [naprava] (id v Linku): doma z izvirnim
         * naslovom, zdoma z naslovom krajevnega konca releja do njenega Huba. Kadar prva pot odpove
         * (IOException), poskusi drugo; pot, ki je uspela, velja za naslednje zahteve (previjanje,
         * naslednja slika). Ne klici na glavni niti.
         */
        internal fun <T> poPoti(context: android.content.Context?, naprava: String, url: String, zahteva: (String) -> T): T {
            val kljuc = PotDoNaprave.kljuc(naprava, url)
            var napaka: IOException? = null
            for (pot in vrstniRed(context, naprava, url)) {
                // Na naslovu brez omrezja ni nikogar: neposredni poskus bi samo podaljsal cakanje na napako.
                if (pot == PotDoNaprave.Pot.NEPOSREDNO && PotDoNaprave.brezOmrezja(url)) continue
                try {
                    val naslov = if (pot == PotDoNaprave.Pot.NEPOSREDNO) url else relejniNaslov(context, naprava, url) ?: continue
                    val izid = zahteva(naslov)
                    PotDoNaprave.zapomni(kljuc, pot, android.os.SystemClock.elapsedRealtime())
                    zabelezi(kljuc, naprava, pot)
                    return izid
                } catch (e: IOException) {
                    napaka = e
                }
            }
            throw napaka ?: IOException("ni poti do naprave")
        }

        /**
         * Naslov za zahtevo, ki se sme izvesti samo ENKRAT (urejanje, POST): po poti, ki do naprave velja zdaj.
         * Druge poti po neuspehu ne poskusamo - preimenovanje ali brisanje se ne sme izvesti dvakrat. Prek Global
         * Linka samo, kadar Hub naprave urejanje zna ([hubZnaUrejanje]: polje `hub` >= 2 v `files.list`).
         * Ne klici na glavni niti (pot se po potrebi preveri s sondo).
         */
        fun naslovZaUrejanje(naprava: String, url: String, hubZnaUrejanje: Boolean): String {
            val c = aplikacija
            if (!hubZnaUrejanje || c == null) return url
            val prva = try { vrstniRed(c, naprava, url).firstOrNull() } catch (_: Throwable) { null }
            if (prva != PotDoNaprave.Pot.RELE) return url
            val prekReleja = relejniNaslov(c, naprava, url) ?: return url
            android.util.Log.i("SafeerPot", "Urejanje na napravi $naprava: prek Global Linka")
            return prekReleja
        }

        /** Zadnja pot, po kateri je naprava odgovorila. V dnevnik gre samo sprememba - brez naslovov in imen datotek. */
        private val zadnjaPot = java.util.concurrent.ConcurrentHashMap<String, PotDoNaprave.Pot>()

        private fun zabelezi(kljuc: String, naprava: String, pot: PotDoNaprave.Pot) {
            if (naprava.isBlank() || zadnjaPot.put(kljuc, pot) == pot) return
            android.util.Log.i("SafeerPot", "Branje z naprave $naprava: " +
                if (pot == PotDoNaprave.Pot.RELE) "prek Global Linka" else "neposredno")
        }

        /** Naslov na tej napravi (lastni streznik) ni nikoli za rele. */
        private fun naTejNapravi(url: String): Boolean {
            val gostitelj = try { java.net.URI(url).host } catch (_: Throwable) { null } ?: return true
            return gostitelj == "127.0.0.1" || gostitelj == "localhost" || gostitelj == "::1" || gostitelj == "[::1]"
        }

        private fun releMogoc(context: android.content.Context?, naprava: String, url: String): Boolean {
            val c = context ?: return false
            if (naprava.isBlank() || naTejNapravi(url)) return false
            return si.safeer.tv.link.GlobalLink.releMogoc(c, naprava) && PotDoNaprave.relejniNaslov(url, 1) != null
        }

        /**
         * Isti naslov prek Global Linka: krajevna vrata releja do Huba naprave + `/cast` (Hub streze deljene
         * datoteke na `/cast/d/<id>`). Potrdilo Huba je isto kot potrdilo streznika datotek (isti kljuc), zato
         * velja isti pripeti odtis. Null, ce rele ni mogoc (izklopljen Global Link, neznana naprava).
         */
        private fun relejniNaslov(context: android.content.Context?, naprava: String, url: String): String? {
            val c = context ?: return null
            if (naprava.isBlank() || naTejNapravi(url)) return null
            val vrata = si.safeer.tv.link.GlobalLink.vrataReleja(c, naprava) ?: return null
            return PotDoNaprave.relejniNaslov(url, vrata)
        }
    }
}
