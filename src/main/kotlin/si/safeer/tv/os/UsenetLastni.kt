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
    }

    private val zaklep = Any()
    @Volatile private var seja: Seja? = null

    /** Ponudnik je vpisan, s TLS in z berljivim geslom, zapah ga ne ustavi (brez omrezja). */
    fun naVoljo(c: Context): Boolean = try {
        val s = UsenetPonudnik.get(c)
        val p = s.preberi()
        p != null && p.tls && s.zaBazen(p).first != null
    } catch (_: Throwable) { false }

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
            val (vir, _) = UsenetBralnik.pripravi(razclenjen, nntp::body)
            val profil = UsenetBralnik.PROFILI.getValue(if (Naprava.jeTelevizor(c)) "tv" else "telefon")
            val s = nova
            val streznik = UsenetStreznik(UsenetBralnik.Bralnik(nntp::body, vir, profil), obKoncu = { koncaj(s) })
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
     * najprej zapre. Album v RAR (brez golih zvocnih datotek) = napaka »ni_skladb«.
     */
    fun predvajajAlbum(c: Context, nzb: ByteArray): Album {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Usenet ne sme teci na glavni niti" }
        val shramba = UsenetPonudnik.get(c)
        val p = shramba.preberi() ?: return Album(emptyList(), "ni_ponudnika")
        val (geslo, ovira) = shramba.zaBazen(p)
        if (geslo == null) return Album(emptyList(), ovira ?: "ni_gesla")
        ustavi()
        val meje = shramba.meje
        if (!meje.zasedi(p)) return Album(emptyList(), "zaseden")
        var nova: Seja? = null
        return try {
            UsenetZapahShramba.namesti(c)
            val nntp = UsenetNntp(UsenetNntp.Nastavitve(p.gostitelj, p.uporabnik, geslo.vrednost(), p.vrata, p.najvecPovezav))
            val s = Seja(p, nntp, meje)
            nova = s
            val razclenjen = UsenetNzb.razcleni(nzb)
            val izbrane = UsenetGlasbaPravila.skladbe(razclenjen.datoteke.map { it.ime })
            if (izbrane.isEmpty()) { koncaj(s); return Album(emptyList(), "ni_skladb") }
            val profil = UsenetBralnik.PROFILI.getValue(if (Naprava.jeTelevizor(c)) "tv" else "telefon")
            val skladbe = ArrayList<Pair<String, String>>()
            val strezniki = ArrayList<UsenetStreznik>()
            for (i in izbrane) {
                val d = razclenjen.datoteke[i]
                // Skladba z luknjo ali brez prvega segmenta izpade, album gre naprej.
                val vir = try { UsenetBralnik.pripraviDatoteko(d, nntp::body) } catch (e: UsenetTok.NapakaToka) { null }
                if (vir != null) {
                    val st = UsenetStreznik(UsenetBralnik.Bralnik(nntp::body, vir, profil))
                    strezniki.add(st)
                    skladbe.add(UsenetGlasbaPravila.imeSkladbe(d.ime) to st.url)
                }
            }
            s.strezniki = strezniki
            if (skladbe.isEmpty()) { koncaj(s); return Album(emptyList(), "luknja") }
            synchronized(zaklep) { seja = s }
            Log.i(TAG, "glasba: album pripravljen (${skladbe.size} skladb)")   // brez imen
            Album(skladbe)
        } catch (e: Throwable) {
            if (e is InterruptedException || e is java.io.InterruptedIOException) Thread.currentThread().interrupt()
            Log.i(TAG, "glasba: ${e.javaClass.simpleName}")
            val s = nova
            if (s != null) koncaj(s) else meje.sprosti(p)
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
