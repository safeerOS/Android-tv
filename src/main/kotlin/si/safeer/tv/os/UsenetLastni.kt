package si.safeer.tv.os

import android.content.Context
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Most med Medijskim centrom in lastnim pogonom Usenet (lastnik 27. 10. 2026; kot core/usenet_vezava.py na Linuxu in
 * Windows naloga 44). Ponudnik iz okna »Moji viri -> Usenet ponudnik« ([UsenetPonudnik], geslo samo v Keystore), NZB od
 * indeksatorja ([Usenet.tok]), pogon Grok ([UsenetNntp] bazen, [UsenetNzb], [UsenetBralnik], [UsenetStreznik] na
 * 127.0.0.1 z zetonom). Predvajalnik dobi navaden naslov - prikaz in predvajanje ostaneta kot pri vsakem drugem filmu.
 *
 * - En film naenkrat: novo predvajanje najprej zapre prejsnji streznik in bazen, sele nato vzame mesto v meji racuna.
 * - Konec predvajanja, nedejavnost streznika ali »Pozabi« ([ustavi]) zapre streznik, bralnik in bazen ter sprosti mesto.
 * - Zapah racuna, TLS s preverbo potrdila in meja povezav so v pogonu in pravilih ponudnika - tu se nic ne obide.
 * - Nikoli na glavni niti; geslo ne gre v dnevnik, izjema samo z vrsto.
 */
object UsenetLastni {
    private const val TAG = "SafeerUsenetLastni"

    private class Seja(val ponudnik: UsenetPonudnikPravila.Ponudnik, val nntp: UsenetNntp, val meje: UsenetPonudnikPravila.MejeRacunov) {
        @Volatile var streznik: UsenetStreznik? = null
        /** Glasba: streznik za vsako skladbo albuma (ena seja, en bazen racuna). */
        @Volatile var strezniki: List<UsenetStreznik> = emptyList()
        val zaprta = AtomicBoolean(false)
        /** Lastnik 28. 10. 2026: seja, ki si bazen NNTP izposodi od albuma, ki igra, ga ob neuspehu ne zapre. */
        @Volatile var lastnikBazena = true
    }

    private val zaklep = Any()
    @Volatile private var seja: Seja? = null

    /** Ponudnik je vpisan, s TLS in z berljivim geslom, zapah ga ne ustavi (brez omrezja). */
    fun naVoljo(c: Context): Boolean = try {
        val s = UsenetPonudnik.get(c)
        val p = s.preberi()
        p != null && p.tls && s.zaBazen(p).first != null
    } catch (_: Throwable) { false }

    private const val KLJUC_IZBRAN = "usenet_izbran"

    /** Lastnik 27. 10. 2026: izbira v filtru »Torrenti prek« - Usenet (privzeto, ce je ponudnik vpisan) ali debrid. */
    fun izbran(c: Context): Boolean = c.getSharedPreferences("safeer_os", Context.MODE_PRIVATE).getBoolean(KLJUC_IZBRAN, true)

    fun nastaviIzbran(c: Context, da: Boolean) {
        c.getSharedPreferences("safeer_os", Context.MODE_PRIVATE).edit().putBoolean(KLJUC_IZBRAN, da).apply()
    }

    /** Filmi in serije gredo prek lastnega ponudnika: vpisan in izbran v filtru. Glasba uporablja [naVoljo]. */
    fun vUporabi(c: Context): Boolean = naVoljo(c) && izbran(c)

    /** »Preveri« v oknu ponudnika: ena prijava z odklepom zapaha (uporabnikov klik) in mestom v meji racuna. Delovna nit. */
    fun prijava(c: Context, p: UsenetPonudnikPravila.Ponudnik, geslo: UsenetPonudnikPravila.Geslo,
                meje: UsenetPonudnikPravila.MejeRacunov): UsenetPonudnikPravila.Prijava {
        if (!meje.zasedi(p)) return UsenetLastniPravila.prijavaIz(UsenetNntp.Vrsta.ZASEDEN)
        return try {
            UsenetZapahShramba.namesti(c)
            UsenetNntp(UsenetNntp.Nastavitve(p.gostitelj, p.uporabnik, geslo.vrednost(), p.vrata, 1)).use { it.preveri(odkleni = true) }
            UsenetLastniPravila.prijavaIz(null)
        } catch (e: UsenetNntp.NapakaNntp) {
            UsenetLastniPravila.prijavaIz(e.vrsta)
        } catch (e: Throwable) {
            Log.i(TAG, "preveri: ${e.javaClass.simpleName}")
            UsenetLastniPravila.prijavaIz(UsenetNntp.Vrsta.OMREZJE)
        } finally {
            meje.sprosti(p)
        }
    }

    /**
     * Film iz [nzb] (izdaja [ime]) z lastnim ponudnikom: naslov http://127.0.0.1:<vrata>/<zeton>/video.<koncnica> ali napaka
     * [UsenetLastniPravila.NAPAKA] (samodejna izbira gre naprej). Prvi segment pove, ali izdajo lahko predvajamo (stisnjen
     * ali sifriran RAR, luknje - napaka pred predvajalnikom).
     */
    fun predvajaj(c: Context, nzb: ByteArray, ime: String): TorBox.Izid {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Usenet ne sme teci na glavni niti" }
        val shramba = UsenetPonudnik.get(c)
        val p = shramba.preberi() ?: return napaka("ni_ponudnika")
        val (geslo, ovira) = shramba.zaBazen(p)
        if (geslo == null) return napaka(ovira ?: "ni_gesla")
        ustavi()   // en film naenkrat: prejsnji streznik in bazen se zapreta, preden vzamemo mesto v meji racuna
        val meje = shramba.meje
        if (!meje.zasedi(p)) return napaka("zaseden")
        var nova: Seja? = null
        return try {
            UsenetZapahShramba.namesti(c)
            val nntp = UsenetNntp(UsenetNntp.Nastavitve(p.gostitelj, p.uporabnik, geslo.vrednost(), p.vrata, p.najvecPovezav))
            nova = Seja(p, nntp, meje)
            val razclenjen = UsenetNzb.razcleni(nzb)
            // Lastnik 28. 10. 2026: clanki prek predpomnilnika na USB (ce je v uporabi).
            val telo = UsenetUsb.ovij(c, nntp::body)
            val (vir, _) = UsenetBralnik.pripravi(razclenjen, telo)
            val profil = UsenetBralnik.PROFILI.getValue(if (Naprava.jeTelevizor(c)) "tv" else "telefon")
            val s = nova
            val streznik = UsenetStreznik(UsenetBralnik.Bralnik(telo, vir, profil), obKoncu = { koncaj(s) })
            s.streznik = streznik
            synchronized(zaklep) { seja = s }
            Log.i(TAG, "lasten pogon: pripravljen (${vir.velikost / (1024 * 1024)} MiB)")   // brez imena, poti in zetona
            TorBox.Izid(url = streznik.url, ponudnik = UsenetLastniPravila.PONUDNIK)
        } catch (e: Throwable) {
            if (e is InterruptedException || e is java.io.InterruptedIOException) Thread.currentThread().interrupt()
            Log.i(TAG, "lasten pogon: ${e.javaClass.simpleName}")
            val s = nova
            if (s != null) koncaj(s) else meje.sprosti(p)
            napaka(razlog(e))
        }
    }

    /** Album za predvajalnik: (ime skladbe, naslov 127.0.0.1) po vrsti, ali [napaka] (stalen niz). */
    class Album(val skladbe: List<Pair<String, String>>, val napaka: String = "")

    /**
     * Lastnik 27. 10. 2026 (glasba): album iz [nzb] - zvocne datoteke (UsenetGlasbaPravila.skladbe), vsaka svoj streznik
     * na 127.0.0.1 z zetonom, ena seja in en bazen racuna (meja povezav velja za ves album). Prejsnji film ali album se
     * najprej zapre. Album v RAR store (vodja 27. 10. 2026) gre skozi [UsenetBralnik.pripraviAlbum]; stisnjen ali sifriran
     * arhiv = napaka »stisnjen«/»sifriran«, izdaja brez zvocnih datotek = »ni_skladb«.
     */
    fun predvajajAlbum(c: Context, nzb: ByteArray): Album {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Usenet ne sme teci na glavni niti" }
        val shramba = UsenetPonudnik.get(c)
        val p = shramba.preberi() ?: return Album(emptyList(), "ni_ponudnika")
        val (geslo, ovira) = shramba.zaBazen(p)
        if (geslo == null) return Album(emptyList(), ovira ?: "ni_gesla")
        val meje = shramba.meje
        // Lastnik 28. 10. 2026: album, ki igra, ostane, dokler nov ni pripravljen - nov se pripravi v njegovem bazenu (isti
        // racun, brez dodatnih povezav). Film ali drug racun: kot prej, najprej zapri.
        val stara = synchronized(zaklep) { seja }?.takeIf {
            !it.zaprta.get() && it.streznik == null && it.ponudnik.kljucRacuna == p.kljucRacuna && it.ponudnik.vrata == p.vrata
        }
        if (stara == null) {
            ustavi()
            if (!meje.zasedi(p)) return Album(emptyList(), "zaseden")
        }
        var nova: Seja? = null
        return try {
            UsenetZapahShramba.namesti(c)
            val nntp = stara?.nntp ?: UsenetNntp(UsenetNntp.Nastavitve(p.gostitelj, p.uporabnik, geslo.vrednost(), p.vrata, p.najvecPovezav))
            val s = Seja(p, nntp, meje).apply { lastnikBazena = stara == null }
            nova = s
            val razclenjen = UsenetNzb.razcleni(nzb)
            val izbrane = UsenetGlasbaPravila.skladbe(razclenjen.datoteke.map { it.ime })
            // Lastnik 27. 10. 2026: skladba se pripravi sele ob prvi zahtevi predvajalnika (AlbumIzvor); luknja da 502 in
            // predvajalnik gre na naslednjo.
            val viri: List<Pair<String?, () -> UsenetBralnik.Vir>> = if (izbrane.isNotEmpty()) izbrane.map { i ->
                val d = razclenjen.datoteke[i]
                d.ime to { UsenetBralnik.pripraviDatoteko(d, nntp::body) }
            } else {
                // Vodja 27. 10. 2026: albumi so vecinoma v RAR - skladbe iz zvezkov (prej vedno »ni_skladb«).
                val rar = try { UsenetBralnik.pripraviAlbum(razclenjen, nntp::body) }
                    catch (e: UsenetRar.NapakaRar) { koncaj(s); return Album(emptyList(), UsenetLastniPravila.napakaAlbumaRar(e.izid.name)) }
                    catch (e: UsenetBralnik.NapakaIzdaje) { koncaj(s); return Album(emptyList(), e.izid) }
                if (rar.isEmpty()) { koncaj(s); return Album(emptyList(), "ni_skladb") }
                Log.i(TAG, "glasba: album iz RAR (${rar.size} skladb)")   // brez imen
                rar.map { (ime, vir) -> ime to { vir } }
            }
            val profil = UsenetBralnik.profilAlbuma(UsenetBralnik.PROFILI.getValue(if (Naprava.jeTelevizor(c)) "tv" else "telefon"))
            // En streznik za cel album (lastnik 27. 10. 2026): v pomnilniku najvec AlbumIzvor.NAJVEC_ODPRTIH skladb; skladba,
            // ki se ni na vrsti, ne potece (rok nedejavnosti za album, prej 5 min - album je obstal po 4 skladbah).
            val izvor = AlbumIzvor(viri, UsenetUsb.ovij(c, nntp::body), profil)
            val st = UsenetStreznik(izvor, nedejavnostMs = UsenetLastniPravila.NEDEJAVNOST_ALBUMA_MS)
            izvor.pripraviVnaprej(0)    // povezava NNTP in prva skladba med pripravo predvajalnika
            s.strezniki = listOf(st)
            val skladbe = viri.mapIndexed { i, (ime, _) -> UsenetGlasbaPravila.imeSkladbe(ime) to st.urlSkladbe(i) }
            if (skladbe.isEmpty()) { koncaj(s); return Album(emptyList(), "luknja") }
            if (stara != null) {
                // Bazen preide na nov album; star album zapre samo svoj streznik.
                stara.lastnikBazena = false
                s.lastnikBazena = true
                koncaj(stara)
            }
            synchronized(zaklep) { seja = s }
            Log.i(TAG, "glasba: album pripravljen (${skladbe.size} skladb)${if (stara != null) ", prejsnji zaprt" else ""}")   // brez imen
            Album(skladbe)
        } catch (e: Throwable) {
            if (e is InterruptedException || e is java.io.InterruptedIOException) Thread.currentThread().interrupt()
            Log.i(TAG, "glasba: ${e.javaClass.simpleName}")
            val s = nova
            if (s != null) koncaj(s) else if (stara == null) meje.sprosti(p)
            Album(emptyList(), razlog(e))
        }
    }

    /** Konec predvajanja ali »Pozabi«: zapre streznik, bralnik in bazen trenutnega filma. Varno veckrat in iz katerekoli niti. */
    fun ustavi() {
        val s = synchronized(zaklep) { seja.also { seja = null } } ?: return
        koncaj(s)
    }

    private fun koncaj(s: Seja) {
        if (!s.zaprta.compareAndSet(false, true)) return
        synchronized(zaklep) { if (seja === s) seja = null }
        try { s.streznik?.close() } catch (_: Throwable) { }
        s.strezniki.forEach { try { it.close() } catch (_: Throwable) { } }
        if (!s.lastnikBazena) return   // bazen je izposojen (album, ki igra) ali predan novemu albumu
        try { s.nntp.close() } catch (_: Throwable) { }
        s.meje.sprosti(s.ponudnik)
    }

    /** Razlog za dnevnik in okno: stalen niz brez message-id, imen datotek ali gostitelja. */
    private fun razlog(e: Throwable): String = when (e) {
        is UsenetNntp.NapakaNntp -> e.vrsta.name.lowercase()
        is UsenetRar.NapakaRar -> e.izid.name.lowercase()
        is UsenetTok.NapakaToka -> e.izid.name.lowercase()
        is UsenetNzb.NapakaNzb -> "nzb"
        else -> "omrezje"
    }

    private fun napaka(opis: String) = TorBox.Izid(napaka = UsenetLastniPravila.NAPAKA, opis = opis, ponudnik = UsenetLastniPravila.PONUDNIK)
}
