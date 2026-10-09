package si.safeer.tv.os

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

/**
 * Povezava TLS s **pripetim** potrdilom do naprave, ki ima lahko vec naslovov (docs/LINK-MESH.md,
 * pravilo 8): naslov iz seznama naprav Safeer Linka in tisti, ki jih je naprava nastela sama. Kadar naprave v
 * nasem omrezju ni (zdoma), vodi do iste seje pot prek njenega Huba (Global Link): [poveziPrekHuba], [tekma].
 *
 * Brez Androidovih razredov (razen dnevnika), da jo preizkus pozene v navadnem JVM s pravimi vticnicami
 * (tests/NeposrednaPovezavaTest.kt).
 */
object NeposrednaPovezava {
    /** Cas za povezavo in rokovanje, kadar je naslov en sam. */
    const val CAS_ENEGA_MS = 8_000

    /** Cas za en naslov, kadar jih je vec: stirje poskusi se izidejo v 30 s, kolikor naprava caka gledalca. */
    const val CAS_KANDIDATA_MS = 4_000

    /** Cas za pot prek Huba naprave: kanal releja (okrog pol sekunde), rokovanje in odgovor Huba. */
    const val CAS_HUBA_MS = 12_000

    /**
     * Toliko prednosti ima neposredna pot, preden hkrati poskusimo se pot prek Huba. Doma je neposredna povezava
     * v tem casu ze koncana in releja se sploh ne dotaknemo.
     */
    const val PREDNOST_NEPOSREDNE_MS = 300L

    /** Pot na Hubu naprave, ki povezavo preda seji zaslona (core/link_hub_streznik.py: POT_NAMIZJE). */
    const val POT_NAMIZJA = "/cast/desktop"
    const val NADGRADNJA = "safeer-desktop"
    const val GLAVA_ZETONA = "X-Safeer-Desktop"

    /** Po kateri poti smo prisli do naprave. */
    enum class Pot { NEPOSREDNO, HUB }

    /**
     * Povezava do naprave in pot, po kateri tece. [casMs]: od zacetka [tekma] do koncanega rokovanja zmagovalne poti
     * (prek Huba skupaj z odgovorom Huba), zaokrozeno navzgor - izmerjena povezava zato nikoli ni 0; 0 pomeni, da ni
     * izmerjena. Samo za dnevnik in meritve, na izbiro poti ne vpliva.
     */
    class Izid(val vticnica: SSLSocket, val pot: Pot, val casMs: Long = 0)

    /** Milisekunde od [zacetekNs] (System.nanoTime), zaokrozeno navzgor. */
    private fun casOd(zacetekNs: Long): Long = (System.nanoTime() - zacetekNs + 999_999) / 1_000_000

    /** En naslov dobi ves cas; vec naslovov si ga razdeli, da napacen prvi ne porabi cakanja naprave. */
    fun casZa(steviloNaslovov: Int): Int = if (steviloNaslovov <= 1) CAS_ENEGA_MS else CAS_KANDIDATA_MS

    /**
     * TCP in TLS do prvega naslova, na katerem odgovori naprava s potrdilom [odtis]. Naslov, na katerem ni nikogar,
     * je tam druga naprava (rokovanje s pripetim odtisom pade) ali naprava molci, preskocimo. Vrne povezavo po
     * koncanem rokovanju ali null, ce naprave ni na nobenem naslovu.
     *
     * Klicatelj sele po tem poslje svoj enkratni zeton - naprava na napacnem naslovu ga zato nikoli ne vidi.
     * [tece] = false prekine iskanje (uporabnik je zaslon zapustil).
     */
    fun povezi(
        naslovi: List<String>,
        vrata: Int,
        odtis: String,
        casMs: Int = casZa(naslovi.size),
        oznaka: String = "SafeerPovezava",
        tece: () -> Boolean = { true },
    ): SSLSocket? {
        val (tovarna, _) = Pin.tovarna(odtis)
        for (n in naslovi) {
            if (!tece()) return null
            val goli = Socket()
            try {
                goli.connect(InetSocketAddress(n, vrata), casMs)
                goli.tcpNoDelay = true                     // brez Naglejevega zbiranja: vsak paket takoj
                val s = tovarna.createSocket(goli, n, vrata, true) as SSLSocket
                s.soTimeout = casMs                        // nema naprava ne sme zadrzati naslednjega naslova
                s.startHandshake()
                s.soTimeout = 0
                return s
            } catch (e: Throwable) {
                Log.i(oznaka, "$n:$vrata ni dosegljiv: ${e.message}")
                try { goli.close() } catch (_: Throwable) { }
            }
        }
        return null
    }

    /**
     * Do seje zaslona prek Huba naprave (Global Link). [vrata] so krajevna vrata releja do tega Huba; skozenj tece
     * nespremenjen TLS s pripetim potrdilom naprave, zato rele vidi samo sifrirane bajte. Po rokovanju prosimo
     * Hub, naj povezavo preda seji (`GET /cast/desktop`, zeton seje v glavi). Po odgovoru 101 tece po povezavi
     * isti pretok kot po neposredni - brez pozdrava, ker je zeton Hub ze preveril.
     *
     * Zeton gre na pot sele po rokovanju: naprava z drugim potrdilom ga ne vidi. Vrne null, ce Huba ni, ce seje ne
     * preda (starejsi Safeer na racunalniku, sejo ima ze drug gledalec) ali ce [tece] medtem ugasne.
     */
    fun poveziPrekHuba(
        vrata: Int,
        odtis: String,
        zeton: String,
        casMs: Int = CAS_HUBA_MS,
        gostitelj: String = "127.0.0.1",
        oznaka: String = "SafeerPovezava",
        tece: () -> Boolean = { true },
    ): SSLSocket? {
        // Zeton gre v glavo HTTP: presledek ali nova vrstica v njem bi bila druga glava, ne zeton.
        if (zeton.isEmpty() || zeton.any { it <= ' ' || it > '~' }) return null
        if (!tece()) return null
        val (tovarna, _) = Pin.tovarna(odtis)
        val goli = Socket()
        try {
            goli.connect(InetSocketAddress(gostitelj, vrata), casMs)
            goli.tcpNoDelay = true
            val s = tovarna.createSocket(goli, gostitelj, vrata, true) as SSLSocket
            s.soTimeout = casMs
            s.startHandshake()
            if (!tece()) { s.close(); return null }
            val izhod = s.outputStream
            izhod.write(("GET $POT_NAMIZJA HTTP/1.1\r\nHost: $gostitelj:$vrata\r\nUpgrade: $NADGRADNJA\r\n" +
                "Connection: Upgrade\r\n$GLAVA_ZETONA: $zeton\r\n\r\n").toByteArray(Charsets.US_ASCII))
            izhod.flush()
            val stanje = prvaVrsticaOdgovora(s.inputStream)
            if (!stanje.startsWith("HTTP/1.1 101")) {
                Log.i(oznaka, "Hub naprave seje ni predal: ${stanje.take(60)}")
                s.close()
                return null
            }
            s.soTimeout = 0
            return s
        } catch (e: Throwable) {
            Log.i(oznaka, "Pot prek Huba naprave ni uspela: ${e.message}")
            try { goli.close() } catch (_: Throwable) { }
            return null
        }
    }

    /**
     * Prva vrstica odgovora HTTP. Glavo preberemo do prazne vrstice bajt za bajtom in niti bajta vec: takoj za njo
     * je ze pretok seje. Brez cele glave (konec povezave, predolga glava) je izid prazen.
     */
    private fun prvaVrsticaOdgovora(vhod: InputStream, najvec: Int = 4096): String {
        val b = ByteArrayOutputStream()
        var videno = 0                      // koliko znakov zaporedja \r\n\r\n je ze prislo
        while (b.size() < najvec && videno < 4) {
            val z = vhod.read()
            if (z < 0) break
            b.write(z)
            videno = if (z == '\r'.code) { if (videno == 2) 3 else 1 }
                else if (z == '\n'.code && (videno == 1 || videno == 3)) videno + 1
                else 0
        }
        if (videno < 4) return ""
        return b.toString("US-ASCII").substringBefore("\r\n")
    }

    /**
     * Dve poti do iste seje hkrati: [neposredno] takoj, [prekHuba] po [prednostMs] ali takoj, ko neposredna
     * odpove. Velja tista, ki prva pride do konca; povezavo pocasnejse zapremo. Doma neposredna pot zmaga, preden
     * se pot prek Huba sploh zacne; zdoma nihce ne caka, da se iztecejo poskusi na naslove domacega omrezja.
     *
     * Vsaka pot dobi svoj »tece«: ugasne, ko uporabnik zaslon zapusti ([tece]) ali ko je druga pot ze zmagala.
     * Neposredna pot zeton poslje sele klicatelj (po vrnitvi), pot prek Huba ga je poslala Hubu iste naprave -
     * seja zato vedno dobi natanko enega gledalca. Izid pove tudi, koliko je zmagovalna pot trajala ([Izid.casMs]).
     */
    fun tekma(
        neposredno: ((() -> Boolean) -> SSLSocket?)?,
        prekHuba: ((() -> Boolean) -> SSLSocket?)?,
        prednostMs: Long = PREDNOST_NEPOSREDNE_MS,
        tece: () -> Boolean = { true },
    ): Izid? {
        if (neposredno == null && prekHuba == null) return null
        val zacetek = System.nanoTime()
        if (prekHuba == null || neposredno == null) {
            val edina = if (neposredno != null) Pot.NEPOSREDNO else Pot.HUB
            val delo = neposredno ?: prekHuba
            val s = if (delo != null && tece()) delo(tece) else null
            return if (s != null) Izid(s, edina, casOd(zacetek)) else null
        }
        val zmagovalec = AtomicReference<Izid?>(null)
        val neposrednaKoncana = CountDownLatch(1)
        val koncanih = CountDownLatch(2)
        val odloceno = CountDownLatch(1)
        val se: () -> Boolean = { tece() && zmagovalec.get() == null }
        fun pozeni(vrsta: Pot, delo: (() -> Boolean) -> SSLSocket?, pred: () -> Unit, po: () -> Unit) {
            Thread({
                try {
                    pred()
                    val s = if (se()) delo(se) else null
                    if (s != null) {
                        if (zmagovalec.compareAndSet(null, Izid(s, vrsta, casOd(zacetek)))) odloceno.countDown()
                        else try { s.close() } catch (_: Throwable) { }
                    }
                } catch (_: Throwable) {
                } finally {
                    po()
                    koncanih.countDown()
                    if (koncanih.count == 0L) odloceno.countDown()
                }
            }, "safeer-pot-" + vrsta.name.lowercase()).apply { isDaemon = true; start() }
        }
        pozeni(Pot.NEPOSREDNO, neposredno, pred = { }, po = { neposrednaKoncana.countDown() })
        pozeni(Pot.HUB, prekHuba,
            pred = { try { neposrednaKoncana.await(prednostMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { } },
            po = { })
        try { odloceno.await() } catch (_: InterruptedException) { }
        return zmagovalec.get()
    }
}
