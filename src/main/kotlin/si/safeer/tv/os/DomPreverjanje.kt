package si.safeer.tv.os

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import si.safeer.tv.os.DomPreverjanjePravila as D
import si.safeer.tv.os.RazpolozljivostPravila as P

/**
 * Preverjevalec doma (pravila: [DomPreverjanjePravila], protokol: docs/HOME-VERIFIER.md).
 *
 * Kaj se da predvajati, v domu sprasuje ena naprava - tista, ki je najdlje vklopljena (televizor pred tablico in
 * telefonom). Dodatek steje vprasanja po domacem naslovu, ne po napravi: dokler je vsaka naprava sprasevala zase, so
 * bile tri naprave skupaj cez mejo, in dodatek potem ni dal niti toka za film (4. 10. 2026).
 *
 * Ta razred ima obe vlogi:
 *  - odjemalec: [preveri] (nekdo caka na naslov) in [naZalogo] (naslovi, ki jih bo uporabnik verjetno potreboval).
 *    Ce je v Linku boljsa naprava, jo vprasa (`avail.get`, dolgo povprasevanje); sicer gre naslov v vrsto te naprave.
 *  - preverjevalec: [odgovori] za druge naprave in vrsta z delavcema, ki edina sprasujeta dodatke. Okno (nekdo caka)
 *    je vedno pred delom na zalogo; delo na zalogo tece pocasi in samo, ko nihce ne caka in ima naprava moc.
 *
 * Velja samo za filme in serije z javnim id-jem ([DomPreverjanjePravila.veljaven]); naslove zasebnih dodatkov vsaka
 * naprava preveri sama, kot doslej.
 */
object DomPreverjanje {
    private const val TAG = "SafeerDom"
    private const val NAJVEC_DELAVCEV = 2
    /** Koliko casa naprave, ki ni mogla biti preverjevalec, ne vprasamo znova. */
    private const val IZLOCEN_MS = 5 * 60_000L
    /** Kljuca, ki smo ga preverjevalcu ze poslali na zalogo, toliko casa ne posiljamo znova. */
    private const val ZALOGA_POSLANA_MS = 15 * 60_000L
    /** Najdaljse cakanje odjemalca na en naslov; potem ga mreza obravnava kot neznanega. */
    private const val CAKANJE_NAJVEC_MS = 120_000L

    enum class Izid {
        /** Dodatki so odgovorili; zapis je v [Razpolozljivost]. */
        ODGOVOR,
        /** Odgovora ni (dodatek ne odgovarja, je v premoru, kataloga ne pozna): naslova nekaj casa ne sprasujemo. */
        NEZNANO,
        /** Uporabnik je odsel, vprasanje ni bilo poslano: nicesar ne sklepamo. */
        PREKLICANO
    }

    private fun zdaj() = System.currentTimeMillis()

    // ================================================================== vrsta te naprave (preverjevalec)

    private val zaklep = Object()
    private val vrsta = D.Vrsta()
    private val zaloga = D.Zaloga()
    /** Kako ta naprava pride do prve epizode serije (iz kataloga kartice); za vprasanja drugih naprav javni katalog. */
    private val resevalci = HashMap<String, () -> String?>()
    private var delavcev = 0
    private var zalogaVDelu = 0
    @Volatile private var zadnjeOknoOb = 0L
    @Volatile private var mocOb = 0L
    @Volatile private var moc = false

    /** Koliko naslovov caka pri tej napravi (za dnevnik in preizkus). */
    fun stanjeVrste(): String = synchronized(zaklep) {
        "okno=${vrsta.velikostOkna}, zaloga=${vrsta.velikostZaloge}, delavcev=$delavcev, na_zalogo_danes=${zaloga.danes}, poizvedb=${Stremio.poizvedbVOzadju.get()}, v_uri=${Stremio.opisProracuna()}"
    }

    @Volatile private var dnevnikOb = 0L
    /** Vrstica v dnevnik najvec na nekaj sekund (brez naslovov - samo stevila). */
    private fun dnevnik(kaj: String, razmikMs: Long = 5_000L) {
        val t = zdaj()
        if (t - dnevnikOb in 0 until razmikMs) return
        dnevnikOb = t
        Log.i(TAG, "$kaj: ${stanjeVrste()}")
    }

    private fun dodatki(app: Context): List<String> = try { MedijskiViri.vsi(app).filter { it.jeStremio }.map { it.naslov } } catch (_: Throwable) { emptyList() }

    /** Neomejeno omrezje: na mobilnih podatkih ne preverjamo v ozadju (isto pravilo kot mreza). */
    private fun omrezje(app: Context): Boolean = try {
        val o = app.getSystemService(android.net.ConnectivityManager::class.java)
        o != null && o.activeNetwork != null && !o.isActiveNetworkMetered
    } catch (_: Throwable) { false }

    /** Ali ima naprava moc za delo na zalogo (zakon solidarnosti: napajanje ali dovolj baterije, ne predvaja). */
    private fun imaMoc(app: Context): Boolean {
        val t = zdaj()
        if (t - mocOb in 0 until 30_000L) return moc
        moc = try { omrezje(app) && si.safeer.tv.link.Zmogljivost.porocilo(app).optJSONObject("pomoc")?.optBoolean("lahko") == true } catch (_: Throwable) { false }
        mocOb = t
        return moc
    }

    private fun danes(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    private fun zazeniDelavce(app: Context) {
        synchronized(zaklep) {
            while (delavcev < NAJVEC_DELAVCEV) {
                delavcev++
                Thread({ delavec(app) }, "safeer-dom-preverjanje").apply { isDaemon = true }.start()
            }
            zaklep.notifyAll()
        }
    }

    private fun delavec(app: Context) {
        try {
            while (true) {
                var vnos: D.Vnos? = null
                var okno = false
                // Pogoji za delo na zalogo (vedro, moc naprave) se preberejo zunaj zaklepa: berejo nastavitve in baterijo.
                // Na zalogo dela samo naprava, ki za dom preverja sama, in najvec en delavec.
                val morda = nadzornikPomnjen == null && synchronized(zaklep) { vrsta.velikostZaloge > 0 && zalogaVDelu == 0 }
                // Urni proracun dodatkov: na zalogo le, dokler ga je porabljena manj kot tretjina ([TempoDodatka]).
                val zetonov = if (morda) dodatki(app).let { d -> if (Stremio.smeNaZalogo(d)) Stremio.zetonov(d) else 0.0 } else 0.0
                val imaMocZdaj = morda && imaMoc(app)
                val dan = if (morda) danes() else ""
                synchronized(zaklep) {
                    val t = zdaj()
                    var p = vrsta.naslednji(t, tudiZaloga = false)
                    if (p == null && morda && zalogaVDelu == 0 &&
                        zaloga.smem(t, dan, zetonov, maxOf(Stremio.uporabnikOb, zadnjeOknoOb), Stremio.premorOb, imaMocZdaj)) {
                        p = vrsta.naslednji(t, tudiZaloga = true)
                        if (p != null && !p.second) zalogaVDelu++
                    }
                    if (p != null) { vnos = p.first; okno = p.second }
                    else if (vrsta.velikostOkna == 0 && vrsta.velikostZaloge == 0) { delavcev--; return }
                    // Nic za zdaj: okno pride z notifyAll, zaloga, ko so pogoji (vedro, premor, moc) spet dobri.
                    else try { zaklep.wait(5_000L) } catch (_: InterruptedException) { delavcev--; return }
                }
                val v = vnos ?: continue
                try { obdelaj(app, v, okno) } catch (e: Throwable) {
                    Log.w(TAG, "preverjanje ${v.kljuc.substringBefore('|')}: ${e.message}")
                    synchronized(zaklep) { vrsta.koncano(v.kljuc, false, zdaj()); if (!okno) zalogaVDelu = (zalogaVDelu - 1).coerceAtLeast(0); zaklep.notifyAll() }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "delavec: ${e.message}")
            synchronized(zaklep) { delavcev = (delavcev - 1).coerceAtLeast(0) }
        }
    }

    /** Eno vprasanje dodatkom za naslov iz vrste. Edino mesto, kjer preverjevalec doma sprasuje dodatke. */
    private fun obdelaj(app: Context, v: D.Vnos, okno: Boolean) {
        val k = v.kljuc
        fun konec(odgovor: Boolean, preklicano: Boolean = false, vprasano: Boolean = false) {
            synchronized(zaklep) {
                val t = zdaj()
                vrsta.koncano(k, odgovor, t, preklicano)
                if (!okno) { zalogaVDelu = (zalogaVDelu - 1).coerceAtLeast(0); if (vprasano) zaloga.vprasano(t) }
                if (!preklicano) resevalci.remove(k)
                zaklep.notifyAll()
            }
        }
        // Se zeleno: okno - nekdo ga se sprasuje; zaloga - nihce ne caka na kaj nujnejsega.
        val velja = { synchronized(zaklep) { val t = zdaj(); if (okno) vrsta.seZelen(k, t) else (vrsta.seZelen(k, t) && !vrsta.oknoCaka(t)) } }
        val naslovi = dodatki(app)
        if (naslovi.isEmpty() || !omrezje(app)) return konec(false)
        val (tip, id0) = D.razstavi(k)
        if (!velja()) return konec(false, preklicano = true)
        val id = if (D.jeSerija(k)) {
            val resi = synchronized(zaklep) { resevalci[k] }
            (try { resi?.invoke() } catch (_: Throwable) { null }) ?: Stremio.prvaEpizoda(id0) ?: return konec(false)
        } else id0
        val izid = try { Stremio.razpolozljivo(naslovi, tip, id, v.meja, velja) } catch (_: Exception) { null }
        if (izid == null && !velja()) return konec(false, preklicano = true)
        if (izid != null) Razpolozljivost.zapomni(app, k, izid)
        // Okno: odgovor steje, ce pove kaj za najsibkejso napravo, ki caka; zaloga: vsak odgovor dodatkov.
        konec(if (okno) izid?.velja(v.meja) != null else izid != null, vprasano = true)
        if (!okno && izid != null) Razpolozljivost.obvesti()
        dnevnik(if (okno) "preverjeno (okno)" else "preverjeno (zaloga)", if (okno) 5_000L else 0L)
    }

    /** Naslov v vrsti te naprave: pocaka na odgovor delavca. */
    private fun lokalno(app: Context, kljuc: String, meja: Long, epizoda: (() -> String?)?, seVelja: () -> Boolean): Izid {
        val zacetek = zdaj()
        synchronized(zaklep) {
            vrsta.zeli(kljuc, meja, zacetek)
            if (epizoda != null) resevalci[kljuc] = epizoda
            zadnjeOknoOb = zacetek
        }
        zazeniDelavce(app)
        while (true) {
            if (!seVelja()) return Izid.PREKLICANO          // vnos v vrsti poteče sam in gre med zalogo
            if (Razpolozljivost.zapisZa(app, kljuc, meja) != null) return Izid.ODGOVOR
            synchronized(zaklep) {
                val t = zdaj()
                if (vrsta.neuspeli(listOf(kljuc), t).isNotEmpty()) return Izid.NEZNANO
                if (t - zacetek > CAKANJE_NAJVEC_MS) return Izid.NEZNANO
                vrsta.zeli(kljuc, meja, t)
                zadnjeOknoOb = t
                try { zaklep.wait(1_000L) } catch (_: InterruptedException) { return Izid.PREKLICANO }
            }
        }
    }

    // ================================================================== odjemalec

    private class Cakajoc(val meja: Long) {
        val zapah = CountDownLatch(1)
        @Volatile var izid: Izid? = null
        /** Preverjevalec ne more (ne odgovori, drugi dodatki): naslov preveri ta naprava sama. */
        @Volatile var sam = false
    }

    private val cakajoci = ConcurrentHashMap<String, Cakajoc>()
    private val zaPoslati = LinkedHashSet<String>()
    private val poslano = ConcurrentHashMap<String, Long>()
    private val izloceni = ConcurrentHashMap<String, Long>()
    private var anketar: Thread? = null
    /** Zadnji izbrani preverjevalec (null = ta naprava); delavec po njem ve, ali sme delati na zalogo. */
    @Volatile private var nadzornikPomnjen: String? = null
    /** Kaj ta naprava zmore s torrenti (zadnji klic [preveri] ali [naZalogo]); gre v vprasanja brez okna. */
    @Volatile private var mojaMeja = P.VSE

    private fun semLahkoPreverjevalec(): Boolean = si.safeer.tv.BuildConfig.FLAVOR in setOf("os", "tablica", "telefon")

    /** Naprava, ki preverja za dom, ali null, ce je to ta naprava (ali Linka ni). */
    private fun nadzornik(app: Context): String? {
        val izbran = try {
            val link = LinkUpravitelj.pridobi(app)
            if (link.jeKrajevni() || !link.povezan) null else {
                val t = zdaj()
                val drugi = link.naprave.filter { !link.jeTaNaprava(it) && "remote" in it.zmoznosti && D.ZMOZNOST in it.zmoznosti }
                    .map { D.Kandidat(it.id, LinkUpravitelj.fizicnaNaprava(it.id), it.platforma) }
                val jaz = if (semLahkoPreverjevalec()) Identiteta.id(app).let { D.Kandidat(it, LinkUpravitelj.fizicnaNaprava(it), si.safeer.tv.cast.HubKrmilnik.platforma(app)) } else null
                D.izberi(jaz, drugi, izloceni.filterValues { it > t }.keys)
            }
        } catch (_: Throwable) { null }
        if (izbran != nadzornikPomnjen) {
            Log.i(TAG, if (izbran == null) "za dom preverja ta naprava" else "za dom preverja druga naprava v Linku")
            nadzornikPomnjen = izbran
            if (izbran == null) synchronized(zaklep) { zaklep.notifyAll() }
        }
        return izbran
    }

    /**
     * Ali se da naslov predvajati - vprasa dom. Klic z delovne niti; caka, dokler ni odgovora, dokler [seVelja] (zaslon
     * je se odprt) in najvec dve minuti. Ob [Izid.ODGOVOR] je zapis v [Razpolozljivost]. [meja]: kaj ta naprava zmore
     * s torrenti; [epizoda]: prva epizoda serije po katalogu kartice (samo za »series|tt…« brez epizode).
     */
    fun preveri(ctx: Context, kljuc: String, meja: Long, epizoda: (() -> String?)?, seVelja: () -> Boolean): Izid {
        val app = ctx.applicationContext
        if (!D.veljaven(kljuc)) return Izid.NEZNANO
        if (!seVelja()) return Izid.PREKLICANO
        mojaMeja = meja
        if (nadzornik(app) != null) {
            val c = cakajoci.getOrPut(kljuc) { Cakajoc(meja) }
            zazeniAnketarja(app)
            val zacetek = zdaj()
            while (true) {
                val konec = try { c.zapah.await(500L, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { cakajoci.remove(kljuc, c); return Izid.PREKLICANO }
                if (konec) break
                if (!seVelja()) { cakajoci.remove(kljuc, c); return Izid.PREKLICANO }
                if (zdaj() - zacetek > CAKANJE_NAJVEC_MS) { cakajoci.remove(kljuc, c); return Izid.NEZNANO }
            }
            if (!c.sam) return c.izid ?: Izid.NEZNANO
        }
        return lokalno(app, kljuc, meja, epizoda, seVelja)
    }

    /**
     * Naslovi, ki jih bo uporabnik verjetno potreboval (za oknom seznama, zastareli odgovori): preverjevalec doma jih
     * preveri, ko nihce ne caka. Ne caka in nic ne vrne; odgovori pridejo v [Razpolozljivost] in odprta mreza se uredi.
     * [meja]: kaj ta naprava zmore s torrenti - na zalogo gre samo, cesar zanjo se ne vemo.
     */
    fun naZalogo(ctx: Context, kljuci: List<String>, meja: Long) {
        val app = ctx.applicationContext
        val t = zdaj()
        mojaMeja = meja
        // Samo, cesar ta naprava se ne ve (svez odgovor za njeno mejo): klic pride ob vsakem risanju mreze.
        val novi = kljuci.asSequence().filter { D.veljaven(it) && Razpolozljivost.zapisZa(app, it, meja) == null }.distinct().take(D.NAJVEC_NAPREJ).toList()
        if (novi.isEmpty()) return
        if (nadzornik(app) != null) {
            var dodano = false
            synchronized(zaPoslati) {
                for (k in novi) {
                    if (t - (poslano[k] ?: 0L) in 0 until ZALOGA_POSLANA_MS) continue
                    if (zaPoslati.size < D.NAJVEC_ZALOGE && zaPoslati.add(k)) dodano = true
                }
            }
            if (poslano.size > 4_000) poslano.entries.removeAll { t - it.value !in 0 until ZALOGA_POSLANA_MS }
            if (dodano) zazeniAnketarja(app)
            return
        }
        var dodano = false
        synchronized(zaklep) { val prej = vrsta.velikostZaloge; for (k in novi) vrsta.naZalogo(k, t); dodano = vrsta.velikostZaloge > prej }
        if (dodano) zazeniDelavce(app)
    }

    private fun zazeniAnketarja(app: Context) {
        synchronized(cakajoci) {
            if (anketar?.isAlive == true) return
            anketar = Thread({ anketiraj(app) }, "safeer-dom-anketar").apply { isDaemon = true; start() }
        }
    }

    private fun sprosti(k: String, izid: Izid?, sam: Boolean = false) {
        val c = cakajoci.remove(k) ?: return
        c.izid = izid; c.sam = sam
        c.zapah.countDown()
    }

    /** Preverjevalec ne more: kar caka, preveri ta naprava sama; kar je bilo namenjeno na zalogo, gre v njeno vrsto. */
    private fun prevzemiSam(app: Context, zal: List<String>) {
        for (k in cakajoci.keys.toList()) sprosti(k, null, sam = true)
        val vse = synchronized(zaPoslati) { (zal + zaPoslati).also { zaPoslati.clear() } }
        if (vse.isEmpty()) return
        val t = zdaj()
        var dodano = false
        synchronized(zaklep) { for (k in vse) if (vrsta.naZalogo(k, t)) dodano = true }
        if (dodano) zazeniDelavce(app)
    }

    /** Ukaz preverjevalcu po Linku; null = ni odgovoril. */
    private fun vprasaj(app: Context, cilj: String, p: JSONObject, potekMs: Long): JSONObject? {
        val zapah = CountDownLatch(1)
        var odgovor: JSONObject? = null
        try {
            LinkUpravitelj.pridobi(app).ukaz(cilj, D.DEJANJE, p, potekMs, LinkOdjemalec.Odgovor { izid, _ ->
                odgovor = izid?.takeIf { it.optBoolean("ok") }?.optJSONObject("data")
                zapah.countDown()
            })
        } catch (_: Throwable) { return null }
        try { zapah.await(potekMs + 2_000L, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { return null }
        return odgovor
    }

    /** Ena nit na proces: zbere, kar caka, vprasa preverjevalca (dolgo povprasevanje) in razdeli odgovore. */
    private fun anketiraj(app: Context) {
        var napak = 0
        var prazno = 0
        var odgovorov = 0
        var odgovoriOb = 0L
        while (true) {
            val kljuci = cakajoci.keys.toList().take(D.NAJVEC_OKNO)
            val zal = synchronized(zaPoslati) { zaPoslati.take(D.NAJVEC_NAPREJ).also { zaPoslati.removeAll(it.toSet()) } }
            if (kljuci.isEmpty() && zal.isEmpty()) {
                // Kratek pocitek, da naslednji naslov iste mreze ne zaganja nove niti; nato konec.
                if (++prazno > 10) {
                    synchronized(cakajoci) { if (cakajoci.isEmpty() && synchronized(zaPoslati) { zaPoslati.isEmpty() }) { anketar = null; return } }
                    prazno = 0
                }
                try { Thread.sleep(200L) } catch (_: InterruptedException) { return }
                continue
            }
            prazno = 0
            val cilj = nadzornik(app)
            if (cilj == null) { prevzemiSam(app, zal); continue }
            val meja = kljuci.mapNotNull { cakajoci[it]?.meja }.minOrNull() ?: mojaMeja
            val p = JSONObject().put("odtis", Razpolozljivost.odtis(app)).put("meja", D.mejaVNiz(meja))
                .put("okno", JSONArray(kljuci)).put("naprej", JSONArray(zal))
                .put("cakaj", if (kljuci.isEmpty()) 0L else D.CAKAJ_NAJVEC_MS)
            val o = vprasaj(app, cilj, p, D.CAKAJ_NAJVEC_MS + 4_000L)
            val napaka = o?.optString("napaka").orEmpty()
            if (o == null || napaka.isNotEmpty()) {
                // Prvic lahko samo zamuja (sredisce se menja); drugic ali ob jasni zavrnitvi (drugi dodatki, omejeno
                // omrezje) ga nekaj minut ne sprasujemo vec in preverimo sami.
                if (o != null || ++napak >= 2) {
                    Log.i(TAG, "preverjevalec ne more (${napaka.ifEmpty { "ni odgovora" }}): nekaj minut preverja ta naprava")
                    izloceni[cilj] = zdaj() + IZLOCEN_MS
                    napak = 0
                    prevzemiSam(app, zal)
                } else synchronized(zaPoslati) { zaPoslati.addAll(zal) }
                continue
            }
            napak = 0
            val t = zdaj()
            for (k in zal) poslano[k] = t
            // Dodatek je omejil dom: pocakamo tudi mi (premor velja za vse naprave).
            try {
                o.optJSONObject("premori")?.takeIf { it.length() > 0 }?.let { pr ->
                    for (naslov in dodatki(app)) {
                        val se = pr.optLong(P.odtisNaslova(Stremio.osnova(naslov)))
                        if (se > 0L) Stremio.prevzemiPremor(naslov, se)
                    }
                }
            } catch (_: Throwable) { }
            try { o.optJSONObject("zapisi")?.takeIf { it.length() > 0 }?.let { Razpolozljivost.prevzemi(app, it) } } catch (e: Throwable) { Log.w(TAG, "zapisi: ${e.message}") }
            odgovorov += o.optJSONObject("zapisi")?.length() ?: 0
            if (t - odgovoriOb > 5_000L) { Log.i(TAG, "preverjevalec doma: $odgovorov odgovorov, caka se ${o.optInt("caka")}"); odgovoriOb = t; odgovorov = 0 }
            for (k in kljuci) { val c = cakajoci[k] ?: continue; if (Razpolozljivost.zapisZa(app, k, c.meja) != null) sprosti(k, Izid.ODGOVOR) }
            o.optJSONArray("neznano")?.let { a -> for (i in 0 until a.length()) sprosti(a.optString(i), Izid.NEZNANO) }
        }
    }

    // ================================================================== preverjevalec odgovarja drugi napravi

    private fun kljuci(a: JSONArray?, najvec: Int): List<String> {
        if (a == null) return emptyList()
        val izhod = LinkedHashSet<String>()
        for (i in 0 until minOf(a.length(), najvec)) a.optString(i).takeIf { D.veljaven(it) }?.let { izhod.add(it) }
        return izhod.toList()
    }

    @Volatile private var dodatkiOb = 0L

    /** Odtis dodatkov mora biti znan, preden primerjamo (kot ob usklajevanju seznamov): manifesti z diska ali omrezja. */
    private fun pripraviDodatke(app: Context) {
        val t = zdaj()
        if (t - dodatkiOb in 0 until 60_000L) return
        dodatkiOb = t
        try {
            Stremio.pripravi(app)
            val d = dodatki(app)
            d.filter { Stremio.dajeTokove(it) == null }.forEach { try { Stremio.manifest(it) } catch (_: Exception) { } }
            Razpolozljivost.nastaviDodatke(app, d.map { it to Stremio.dajeTokove(it) })
        } catch (_: Throwable) { }
    }

    /**
     * Odgovor na `avail.get` druge naprave v Linku. Klic z delovne niti (ne z glavne): caka do `cakaj` ms na prvi nov
     * odgovor. Vrne `{odtis, zapisi, caka, neznano, premori}` ali `{odtis, napaka}` (odtis: drugi dodatki; omrezje:
     * naprava je na omejenem omrezju; dodatki: nima dodatkov s tokovi).
     */
    fun odgovori(ctx: Context, p: JSONObject): JSONObject {
        val app = ctx.applicationContext
        pripraviDodatke(app)
        val moj = Razpolozljivost.odtis(app)
        val o = JSONObject().put("odtis", moj)
        if (moj.isEmpty()) return o.put("napaka", "dodatki")
        if (p.optString("odtis") != moj) return o.put("napaka", "odtis")
        if (!omrezje(app)) return o.put("napaka", "omrezje")
        val meja = D.mejaIzNiza(p.optString("meja"))
        val okno = kljuci(p.optJSONArray("okno"), D.NAJVEC_OKNO)
        val naprej = kljuci(p.optJSONArray("naprej"), D.NAJVEC_NAPREJ)
        val rok = zdaj() + p.optLong("cakaj").coerceIn(0L, D.CAKAJ_NAJVEC_MS)
        val manjkajo = okno.filter { Razpolozljivost.zapisZa(app, it, meja) == null }
        val naZalogo = naprej.filter { Razpolozljivost.zapisZa(app, it, meja) == null }
        if (okno.isNotEmpty() || naprej.isNotEmpty()) dnevnik("vprasanje druge naprave (okno ${okno.size}, od tega neznanih ${manjkajo.size}; na zalogo ${naZalogo.size})", 10_000L)
        if (manjkajo.isNotEmpty() || naZalogo.isNotEmpty()) {
            synchronized(zaklep) {
                val t = zdaj()
                for (k in manjkajo) vrsta.zeli(k, meja, t)
                for (k in naZalogo) vrsta.naZalogo(k, t)
                if (manjkajo.isNotEmpty()) zadnjeOknoOb = t
            }
            zazeniDelavce(app)
        }
        // Ce odjemalec od tega vprasanja se nicesar ne more narisati, pocakamo na prvi odgovor (dolgo povprasevanje).
        if (manjkajo.isNotEmpty() && manjkajo.size == okno.size) {
            synchronized(zaklep) {
                while (true) {
                    val t = zdaj()
                    if (t >= rok || manjkajo.any { Razpolozljivost.zapisZa(app, it, meja) != null } || vrsta.neuspeli(manjkajo, t).isNotEmpty()) break
                    try { zaklep.wait(minOf(rok - t, 500L)) } catch (_: InterruptedException) { break }
                }
            }
        }
        val zapisi = JSONObject()
        for (k in okno + naprej) Razpolozljivost.zapisZa(app, k, meja)?.let { zapisi.put(k, it) }
        val (caka, neznano) = synchronized(zaklep) { val t = zdaj(); vrsta.caka(okno) to vrsta.neuspeli(okno, t) }
        val premori = JSONObject()
        try { Stremio.premori().forEach { (osnova, se) -> premori.put(P.odtisNaslova(osnova), se) } } catch (_: Throwable) { }
        return o.put("zapisi", zapisi).put("caka", caka).put("neznano", JSONArray(neznano.filter { !zapisi.has(it) })).put("premori", premori)
    }
}
