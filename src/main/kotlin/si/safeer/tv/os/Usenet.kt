package si.safeer.tv.os

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Naloga 141: Usenet - iskanje pri uporabnikovem indeksatorju (Newznab) in NZB za TorBox ([UsenetPravila]).
 *
 * Naslov in kljuc indeksatorja se hranita sifrirana z Android Keystore ([SporocilaSkrivnosti]), nikoli v dnevniku.
 * Povezave NZB iz odgovora (vsebujejo kljuc) ostanejo samo v pomnilniku pod id-jem toka »usenet:…«; v toku, zgodovini in
 * dnevniku je samo ta id. Klici so blokirajoci - samo z delovne niti.
 */
object Usenet {
    private const val TAG = "SafeerUsenet"
    private const val SKRIVNOST = "usenet_indeksator"

    class Indeksator(val osnova: String, val kljuc: String)

    fun indeksator(c: Context): Indeksator? = try {
        val j = SporocilaSkrivnosti.preberi(c, SKRIVNOST)?.let { JSONObject(it) }
        val o = j?.optString("url")?.let { UsenetPravila.osnova(it) }
        val k = j?.optString("key")?.let { UsenetPravila.ocistiKljuc(it) }
        if (o != null && k != null) Indeksator(o, k) else null
    } catch (_: Exception) { null }

    fun imaIndeksator(c: Context): Boolean = indeksator(c) != null

    /**
     * Indeksator, ki ga Safeer sme klicati (varovalo racunov K1, lastnik 26. 10. 2026): ne, ce ga je indeksator zavrnil
     * (»ne velja«) - prej je vsako odprtje vsebine iskalo z zavrnjenim kljucem. Nova preverba samo z vpisom (Shrani).
     */
    fun zaUporabo(c: Context): Indeksator? = indeksator(c)?.takeIf { DebridPravila.smeKlicati(stanje(c)) }

    /** Izbris z druge naprave (grob usklajevanja) ali razveljavitev - izbris tu in uskladitev. */
    fun pozabi(c: Context) {
        brisi(c); UskladitevKljucev.sprozi(c)
    }

    private fun brisi(c: Context) { SporocilaSkrivnosti.pozabi(c, SKRIVNOST); prefs(c).edit().remove(KLJUC_STANJE).apply() }

    /**
     * Gumb »Odstrani« (pregled Grok 33, F1, kot [Debrid.odstraniKljuc]): potrjen indeksator se odstrani na vseh napravah
     * ozjega kroga, nepreverjen ali zavrnjen samo tu. true = izbris gre tudi drugim napravam.
     */
    fun odstrani(c: Context): Boolean {
        val vKrog = UskladitevKljucevPravila.izbrisVKrog(velja(c))
        UskladitevKljucev.odstrani(c, UskladitevKljucevPravila.KLJUC_USENET, vKrog) { brisi(c) }
        return vKrog
    }

    // ------------------------------------------------------------------ preverba kljuca (lastnik 25. 10. 2026)
    // Kot kljuci debrid (Debrid.vpisiKljuc) in namizje (core/debrid.indeksator_preveri): kljuc indeksatorja ob vpisu preveri
    // indeksator z enim iskanjem; zavrnjen se ne shrani, brez odgovora je nepreverjen. Stanje velja za naslov IN kljuc.

    private const val PREFS = "safeer_usenet"
    private const val KLJUC_STANJE = "stanje"
    /** Zadnja preverba nepreverjenega (samo v pomnilniku - po zagonu se preveri znova). */
    @Volatile private var zadnjaPreverba = 0L

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun odtis(osnova: String, kljuc: String) = DebridPravila.odtisStanja("$osnova|$kljuc")

    /** Stanje shranjenega indeksatorja; null = ga ni ali se ni bil preverjen (stanje drugega naslova ali kljuca ne velja). */
    fun stanje(c: Context): DebridPravila.StanjeKljuca? {
        val ix = indeksator(c) ?: return null
        return DebridPravila.stanjeIzZapisa(prefs(c).getString(KLJUC_STANJE, null), odtis(ix.osnova, ix.kljuc))?.first
    }

    /** Varovalo racunov: dokoncno stanje indeksatorja za ozji krog (odtis naslova in kljuca, brez kljuca); null = ga ni. */
    fun stanjeZaKrog(c: Context): JSONObject? {
        val ix = indeksator(c) ?: return null
        val o = odtis(ix.osnova, ix.kljuc)
        val z = DebridPravila.stanjeIzZapisa(prefs(c).getString(KLJUC_STANJE, null), o) ?: return null
        if (!DebridPravila.stanjeVKrog(z.first)) return null
        return JSONObject().put("p", UsenetPravila.VRSTA).put("o", o).put("s", z.first.name.lowercase(java.util.Locale.ROOT)).put("c", z.third)
    }

    /** Prejeto stanje istega indeksatorja z naprave v ozjem krogu (brez omrezja); true = spremenjeno. */
    fun prejmiStanje(c: Context, odtis: String, s: DebridPravila.StanjeKljuca, ob: Long, zdaj: Long): Boolean {
        val ix = indeksator(c) ?: return false
        if (odtis(ix.osnova, ix.kljuc) != odtis) return false
        val tu = DebridPravila.stanjeIzZapisa(prefs(c).getString(KLJUC_STANJE, null), odtis)
        if (!DebridPravila.sprejmiStanje(tu?.third, s, ob, zdaj)) return false
        prefs(c).edit().putString(KLJUC_STANJE, DebridPravila.zapisStanja(s, odtis, null, ob)).apply()
        Log.i(TAG, "indeksator: stanje z druge naprave v ozjem krogu - ${s.name.lowercase(java.util.Locale.ROOT)}")
        return true
    }

    /** Indeksator, ki ga je indeksator potrdil - samo tak gre med naprave v ozjem krogu (UskladitevKljucevPravila.oddaja). */
    fun velja(c: Context): Boolean = DebridPravila.zaUskladitev(stanje(c))

    /**
     * Indeksator z druge naprave (usklajevanje, »Poslji kljuce«) - delovna nit. Uporabi se samo, ce ga indeksator potrdi tudi
     * s te naprave ([UsenetPravila.sprejem]); zavrnjen nikoli ne prepise shranjenega, brez odgovora pocaka na naslednjo
     * uskladitev. false = ni uporabljen.
     */
    /** Pregled Grok 33, F9 (kot Debrid.sprejmiKljuc): izid preverbe, VELJA = shranjen. */
    fun sprejmi(c: Context, naslov: String, kljuc: String): DebridPravila.Preverba {
        val (izid, pr) = preveriPrejetega(c, naslov, kljuc)
        if (pr == null) return izid
        return if (shraniPrejetega(c, pr)) DebridPravila.Preverba.VELJA else DebridPravila.Preverba.NI_ODGOVORA
    }

    /** Prejet indeksator, ki ga je indeksator potrdil s te naprave; shrani ga [shraniPrejetega] brez omrezja. */
    class Potrjen(val osnova: String, val kljuc: String, val zeShranjen: Boolean)

    /**
     * Pregled Grok 33, F2: preverba prejetega indeksatorja (omrezje - delovna nit brez zaklepa usklajevanja) loceno od
     * shranjevanja ([shraniPrejetega], brez omrezja). (izid, potrjen); potrjen samo pri VELJA.
     */
    fun preveriPrejetega(c: Context, naslov: String, kljuc: String): Pair<DebridPravila.Preverba, Potrjen?> {
        val prejet = UsenetPravila.prejetIndeksator(naslov, kljuc)
        val shranjen = indeksator(c)?.let { it.osnova to it.kljuc }
        // Varovalo racunov K1: isti indeksator, ki ga je indeksator tu zavrnil - brez klica.
        if (prejet != null && prejet == shranjen && !DebridPravila.smeKlicati(stanje(c))) return DebridPravila.Preverba.NE_VELJA to null
        var izid = DebridPravila.Preverba.OBLIKA
        val odlocitev = UsenetPravila.sprejem(prejet, shranjen, velja(c)) { o, k -> preveri(o, k).also { izid = it } }
        val ix = prejet ?: return izid to null
        return when (odlocitev) {
            UsenetPravila.Sprejem.ENAK -> DebridPravila.Preverba.VELJA to Potrjen(ix.first, ix.second, true)
            UsenetPravila.Sprejem.ZAVRNI -> { Log.i(TAG, "prejeti indeksator: $izid - ni uporabljen"); izid to null }
            UsenetPravila.Sprejem.SHRANI -> izid to Potrjen(ix.first, ix.second, false)
        }
    }

    /** Shrani potrjen prejeti indeksator - brez omrezja (tudi pod zaklepom usklajevanja). */
    fun shraniPrejetega(c: Context, pr: Potrjen): Boolean {
        if (pr.zeShranjen) return true
        if (!shrani(c, pr.osnova, pr.kljuc)) return false
        zapisiStanje(c, pr.osnova, pr.kljuc, DebridPravila.StanjeKljuca.VELJA)
        zadnjaPreverba = System.currentTimeMillis()
        return true
    }

    /** Razveljavitev spremembe z druge naprave (pregled Grok 33, F2): prejsnji indeksator nazaj brez omrezja, kot nepreverjen. */
    fun obnovi(c: Context, naslov: String, kljuc: String): Boolean {
        val ix = UsenetPravila.izVnosa(naslov, kljuc) ?: return false
        if (!shrani(c, ix.first, ix.second)) return false
        zapisiStanje(c, ix.first, ix.second, DebridPravila.StanjeKljuca.NEPREVERJEN)
        zadnjaPreverba = 0L
        return true
    }

    private fun zapisiStanje(c: Context, osnova: String, kljuc: String, s: DebridPravila.StanjeKljuca) {
        prefs(c).edit().putString(KLJUC_STANJE, DebridPravila.zapisStanja(s, odtis(osnova, kljuc), null, System.currentTimeMillis())).apply()
    }

    private fun shrani(c: Context, osnova: String, kljuc: String): Boolean =
        SporocilaSkrivnosti.shrani(c, SKRIVNOST, JSONObject().put("url", osnova).put("key", kljuc).toString())

    /**
     * Preverba pri indeksatorju: eno iskanje ([UsenetPravila.naslovPreverbe]), brez sledenja preusmeritvi (kljuc je v naslovu),
     * rok 8 s, najvec 64 KiB odgovora. Naslov ne gre v dnevnik. Samo z delovne niti.
     */
    fun preveri(osnova: String, kljuc: String): DebridPravila.Preverba = if (!Debrid.smeOmrezje(TAG)) DebridPravila.Preverba.NI_ODGOVORA else try {
        val p = URL(UsenetPravila.naslovPreverbe(osnova, kljuc)).openConnection() as HttpURLConnection
        try {
            // Pregled Grok 34, E5: skupni rok branja (prej 8 s na vsako branje - streznik, ki kaplja bajte, je preverbo zadrzal
            // skoraj brez meje) in najvec 8 KiB: za odlocitev zadosca zacetek (<rss, <channel ali <error). DNS rok ostane sistemski.
            val doMs = android.os.SystemClock.elapsedRealtime() + PREVERBA_ROK_MS
            p.connectTimeout = 8_000; p.readTimeout = 8_000
            p.instanceFollowRedirects = false
            p.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml")
            val koda = p.responseCode
            val vhod = if (koda in 200..299) p.inputStream else p.errorStream
            val telo = if (vhod == null) ByteArray(0) else vhod.use { beriZacetek(it, 8 * 1024, doMs) }
            UsenetPravila.preverbaIndeksatorja(koda, String(telo, Charsets.UTF_8))
        } finally { p.disconnect() }
    } catch (e: Exception) {
        Log.i(TAG, "preverba: ${e.javaClass.simpleName}"); DebridPravila.Preverba.NI_ODGOVORA
    }.also { Log.i(TAG, "preverba indeksatorja: $it") }

    /**
     * Vpis v oknu »Usenet indeksator« (delovna nit): ne-kljuc ali vzorec -> [DebridPravila.Preverba.OBLIKA] brez klica;
     * zavrnjen kljuc se NE shrani; brez odgovora se shrani kot nepreverjen in ne gre na druge naprave, dokler ga indeksator
     * ne potrdi ([preveriNepreverjenega]). Veljaven gre sam v ozji krog.
     */
    fun vpisi(c: Context, naslov: String, kljuc: String): DebridPravila.Preverba {
        val v = UsenetPravila.vpis(naslov, kljuc) { o, k -> preveri(o, k) }
        val ix = v.shrani ?: return v.izid
        val s = v.stanje ?: return v.izid
        if (!shrani(c, ix.first, ix.second)) return DebridPravila.Preverba.OBLIKA
        zapisiStanje(c, ix.first, ix.second, s)
        zadnjaPreverba = System.currentTimeMillis()
        if (DebridPravila.zaUskladitev(s)) UskladitevKljucev.sprozi(c)
        return v.izid
    }

    /**
     * Indeksator brez stanja (pred posodobitvijo) ali nepreverjen: preverba (najvec na [DebridPravila.PREVERI_ZNOVA_MS]).
     * Zavrnjen ostane shranjen z oznako »ne velja«. Delovna nit. true = stanje se je spremenilo.
     */
    fun preveriNepreverjenega(c: Context): Boolean {
        val ix = indeksator(c) ?: return false
        val prej = stanje(c)
        val zdaj = System.currentTimeMillis()
        if (!DebridPravila.preveriZdaj(prej, zadnjaPreverba, zdaj)) return false
        zadnjaPreverba = zdaj
        val s = DebridPravila.stanjePoPreverbi(preveri(ix.osnova, ix.kljuc)) ?: DebridPravila.StanjeKljuca.NE_VELJA
        if (s == prej) return false
        zapisiStanje(c, ix.osnova, ix.kljuc, s)
        return true
    }

    /** Med iskanjem: zavrnjen kljuc (401, Newznab 100-102) -> »ne velja«; odgovor RSS -> velja. Zapis samo ob spremembi. */
    private fun obOdgovoru(c: Context, ix: Indeksator, izid: DebridPravila.Preverba) {
        val s = when (izid) {
            DebridPravila.Preverba.NE_VELJA -> DebridPravila.StanjeKljuca.NE_VELJA
            DebridPravila.Preverba.VELJA -> DebridPravila.StanjeKljuca.VELJA
            // Varovalo racunov: Newznab 101 (racun suspendiran) - indeksatorja ne klicemo vec.
            DebridPravila.Preverba.ZAKLENJEN -> DebridPravila.StanjeKljuca.ZAKLENJEN
            else -> return
        }
        val prej = stanje(c)
        if (s == prej) return
        zapisiStanje(c, ix.osnova, ix.kljuc, s)
        Log.i(TAG, "indeksator: stanje ${s.name.lowercase(java.util.Locale.ROOT)} po iskanju")
        // Varovalo racunov: tudi »ne velja« gre v ozji krog (druge naprave z istim indeksatorjem ga ne klicejo vec).
        UskladitevKljucev.sprozi(c)
    }

    /** Indeksator je shranjen (tudi ce ga Keystore ta trenutek ne zna prebrati) - usklajevanje ga takrat ne izbrise. */
    fun shranjen(c: Context): Boolean = SporocilaSkrivnosti.ima(c, SKRIVNOST)

    /** Gostitelj indeksatorja za prikaz v oknu (brez kljuca). */
    fun gostitelj(c: Context): String? = indeksator(c)?.let { try { URL(it.osnova).host } catch (_: Exception) { null } }

    /**
     * Ali Usenet sploh iscemo: indeksator, kljuc TorBox (prenese in pretaka TorBox) in uporabnik ni izbral »Brez debrida«.
     * TorBox brez paketa Pro vrne napako paketa ob predvajanju (okno pove zakaj).
     */
    fun jeNaVoljo(c: Context): Boolean = zaUporabo(c) != null && DebridPravila.TORBOX in Debrid.zKljucem(c) && Debrid.aktivna(c) != null

    /**
     * Diagnostika razvijalca (lastnik 25. 10. 2026: Usenet merimo samo po samodejni poti, brez rocne izbire vira):
     * `debug.safeer.usenet_samo` = cas izteka v sekundah (najvec 6 h, samo adb, ne ostane po ponovnem zagonu - kot
     * [Webtor.samoWebtor]). Samodejna izbira takrat vzame samo Usenet: pripravljen pri TorBoxu, nato s cakanjem na prenos.
     */
    fun samoUsenet(c: Context): Boolean = Webtor.diagnostika(c, "debug.safeer.usenet_samo")

    /** Povezave NZB po id-ju toka (najvec [NAJVEC_POVEZAV], najstarejse odpadejo). */
    private const val NAJVEC_POVEZAV = 500
    private val povezave = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > NAJVEC_POVEZAV
    }

    /**
     * Tokovi Usenet za id Stremio (film `tt…` ali epizoda `tt…:S:E`); brez indeksatorja, za drug id ali ob napaki prazno.
     * [rokMs] za odgovor indeksatorja.
     */
    fun isci(c: Context, id: String, rokMs: Int = UsenetPravila.ISKANJE_ROK_MS): List<Stremio.Tok> {
        val ix = zaUporabo(c) ?: return emptyList()
        val naslov = UsenetPravila.naslovIskanja(ix.osnova, ix.kljuc, id) ?: return emptyList()
        return try {
            val (koda, telo) = beri(naslov, rokMs, 4 * 1024 * 1024)
                ?: run { Log.i(TAG, "iskanje: odgovor prevelik"); return emptyList() }
            // Lastnik 25. 10. 2026: stanje kljuca sledi uporabi (zavrnjen -> »ne velja«, odgovor RSS -> velja).
            try { obOdgovoru(c, ix, UsenetPravila.preverbaIndeksatorja(koda, telo)) } catch (_: Exception) { }
            if (koda !in 200..299) { Log.i(TAG, "iskanje: HTTP $koda"); return emptyList() }
            UsenetPravila.napaka(telo)?.let { Log.i(TAG, "iskanje: napaka indeksatorja ($it)"); return emptyList() }
            val zadetki = UsenetPravila.zadetki(telo, ix.osnova)
            Log.i(TAG, "iskanje: ${zadetki.size} zadetkov")
            zadetki.map { z ->
                val idToka = UsenetPravila.idToka(z.povezava)
                synchronized(povezave) { povezave[idToka] = z.povezava }
                Stremio.Tok(UsenetPravila.VRSTA, idToka, "", UsenetPravila.opis(z), UsenetPravila.VRSTA)
            }
        } catch (e: Exception) {
            Log.w(TAG, "iskanje: ${e.javaClass.simpleName}"); emptyList()
        }
    }

    /** NZB za tok [idToka]: vsebina ali napaka (UsenetPravila.NAPAKA_INDEKSATOR, kljuc, meja). Z delovne niti. */
    fun nzb(idToka: String): Pair<ByteArray?, String> {
        val povezava = synchronized(povezave) { povezave[idToka] } ?: return null to UsenetPravila.NAPAKA_INDEKSATOR
        return try {
            val p = URL(povezava).openConnection() as HttpURLConnection
            try {
                p.connectTimeout = 10_000; p.readTimeout = 30_000
                p.instanceFollowRedirects = true
                val koda = p.responseCode
                // Pregled A11: NZB nad mejo je napaka (prej tiho odrezan - TorBox bi dobil pokvarjeno datoteko).
                if (koda in 200..299 && UsenetPravila.nzbPrevelik(p.contentLengthLong)) {
                    Log.i(TAG, "nzb: prevelik (${p.contentLengthLong} B)"); return null to UsenetPravila.NAPAKA_INDEKSATOR
                }
                val vhod = if (koda in 200..299) p.inputStream else p.errorStream
                val prebrano = if (vhod == null) ByteArray(0) else vhod.use { beriDo(it, UsenetPravila.NAJVEC_NZB_BAJTOV) }
                if (prebrano == null) { Log.i(TAG, "nzb: prevelik"); return null to UsenetPravila.NAPAKA_INDEKSATOR }
                val telo: ByteArray = prebrano
                val zacetek = String(telo, 0, minOf(telo.size, 4_096), Charsets.UTF_8)
                when {
                    koda in 200..299 && UsenetPravila.jeNzb(zacetek) -> telo to ""
                    else -> {
                        val n = UsenetPravila.napakaNzb(koda, zacetek)
                        Log.i(TAG, "nzb: HTTP $koda, napaka $n")
                        null to n
                    }
                }
            } finally { p.disconnect() }
        } catch (e: Exception) {
            Log.w(TAG, "nzb: ${e.javaClass.simpleName}"); null to UsenetPravila.NAPAKA_INDEKSATOR
        }
    }

    /**
     * Celotna pot predvajanja: NZB od indeksatorja (naprava), nato TorBox. Pregled Grok 3: vrsta napake indeksatorja (kljuc,
     * meja, druga napaka) gre v izid s ponudnikom [UsenetPravila.PONUDNIK] - okno imenuje indeksator (ne TorBoxa), »Popravi«
     * ponudi samo pri zavrnjenem kljucu (prej je bila vsaka napaka [UsenetPravila.NAPAKA_INDEKSATOR] z gumbom »Popravi«).
     */
    fun tok(c: Context, idToka: String, ime: String, indeks: Int, samoTakoj: Boolean, napredek: (Int) -> Unit): TorBox.Izid {
        val (nzb, napaka) = nzb(idToka)
        if (nzb == null) return TorBox.Izid(napaka = napaka.ifBlank { UsenetPravila.NAPAKA_INDEKSATOR }, ponudnik = UsenetPravila.PONUDNIK)
        // Pregled Grok 33, F5: izid velja za kljuc TorBox, s katerim je sla zahteva (ne za kljuc, vpisan med prenosom).
        val kljucZahteve = TorBox.kljuc(c)
        return TorBox.usenet(c, nzb, ime, indeks, samoTakoj, napredek).also {
            // Lastnik 25. 10. 2026: stanje kljuca TorBox sledi uporabi (401 -> »ne velja«).
            if (kljucZahteve != null) try { Debrid.obIzidu(c, DebridPravila.TORBOX, kljucZahteve, DebridPravila.TORBOX, it.napaka, it.url != null) } catch (_: Exception) { }
        }
    }

    /** (koda, telo) ali null, ce je odgovor vecji od [najvec] (ga ne odrezemo - zavrnemo). */
    private fun beri(naslov: String, rokMs: Int, najvec: Int): Pair<Int, String>? {
        val p = URL(naslov).openConnection() as HttpURLConnection
        try {
            p.connectTimeout = minOf(UsenetPravila.ISKANJE_POVEZAVA_MS, rokMs); p.readTimeout = rokMs
            p.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml")
            val koda = p.responseCode
            if (p.contentLengthLong > najvec) return null
            val vhod = if (koda in 200..299) p.inputStream else p.errorStream
            val telo = (if (vhod == null) ByteArray(0) else vhod.use { beriDo(it, najvec) }) ?: return null
            return koda to String(telo, Charsets.UTF_8)
        } finally { p.disconnect() }
    }

    /** Skupni rok preverbe indeksatorja (povezava in branje; pregled Grok 34, E5). */
    private const val PREVERBA_ROK_MS = 10_000L

    /** Prvih najvec [najvec] bajtov do [doMs] (elapsedRealtime; ostanek se ne bere) - za preverbo zadostuje zacetek odgovora. */
    private fun beriZacetek(vhod: java.io.InputStream, najvec: Int, doMs: Long = Long.MAX_VALUE): ByteArray {
        val izhod = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (izhod.size() < najvec && android.os.SystemClock.elapsedRealtime() < doMs) {
            val n = vhod.read(buf, 0, minOf(buf.size, najvec - izhod.size()))
            if (n < 0) break
            izhod.write(buf, 0, n)
        }
        return izhod.toByteArray()
    }

    /** Najvec [najvec] bajtov; null, ce jih je vec (beremo najvec + 1, da to vemo). Prekinitev niti ustavi branje. */
    private fun beriDo(vhod: java.io.InputStream, najvec: Int): ByteArray? {
        val izhod = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (izhod.size() <= najvec) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val n = vhod.read(buf, 0, minOf(buf.size, najvec + 1 - izhod.size()))
            if (n < 0) break
            izhod.write(buf, 0, n)
        }
        return if (izhod.size() > najvec) null else izhod.toByteArray()
    }
}
