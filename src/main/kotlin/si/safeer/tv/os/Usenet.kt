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

    /** Shrani indeksator; false, ce naslov ali kljuc nista veljavna ali shramba ni uspela. */
    fun nastavi(c: Context, naslov: String, kljuc: String): Boolean {
        val o = UsenetPravila.osnova(naslov) ?: return false
        val k = UsenetPravila.ocistiKljuc(kljuc) ?: return false
        // Sprememba gre sama na naprave v ozjem krogu (UskladitevKljucev).
        return SporocilaSkrivnosti.shrani(c, SKRIVNOST, JSONObject().put("url", o).put("key", k).toString()).also { if (it) UskladitevKljucev.sprozi(c) }
    }

    fun pozabi(c: Context) {
        SporocilaSkrivnosti.pozabi(c, SKRIVNOST); prefs(c).edit().remove(KLJUC_STANJE).apply(); UskladitevKljucev.sprozi(c)
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

    private fun zapisiStanje(c: Context, osnova: String, kljuc: String, s: DebridPravila.StanjeKljuca) {
        prefs(c).edit().putString(KLJUC_STANJE, DebridPravila.zapisStanja(s, odtis(osnova, kljuc), null, System.currentTimeMillis())).apply()
    }

    private fun shrani(c: Context, osnova: String, kljuc: String): Boolean =
        SporocilaSkrivnosti.shrani(c, SKRIVNOST, JSONObject().put("url", osnova).put("key", kljuc).toString())

    /**
     * Preverba pri indeksatorju: eno iskanje ([UsenetPravila.naslovPreverbe]), brez sledenja preusmeritvi (kljuc je v naslovu),
     * rok 8 s, najvec 64 KiB odgovora. Naslov ne gre v dnevnik. Samo z delovne niti.
     */
    fun preveri(osnova: String, kljuc: String): DebridPravila.Preverba = try {
        val p = URL(UsenetPravila.naslovPreverbe(osnova, kljuc)).openConnection() as HttpURLConnection
        try {
            p.connectTimeout = 8_000; p.readTimeout = 8_000
            p.instanceFollowRedirects = false
            p.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml")
            val koda = p.responseCode
            val vhod = if (koda in 200..299) p.inputStream else p.errorStream
            val telo = if (vhod == null) ByteArray(0) else vhod.use { beriZacetek(it, 64 * 1024) }
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
            else -> return
        }
        val prej = stanje(c)
        if (s == prej) return
        zapisiStanje(c, ix.osnova, ix.kljuc, s)
        Log.i(TAG, "indeksator: stanje ${s.name.lowercase(java.util.Locale.ROOT)} po iskanju")
        if (s == DebridPravila.StanjeKljuca.VELJA) UskladitevKljucev.sprozi(c)
    }

    /** Indeksator je shranjen (tudi ce ga Keystore ta trenutek ne zna prebrati) - usklajevanje ga takrat ne izbrise. */
    fun shranjen(c: Context): Boolean = SporocilaSkrivnosti.ima(c, SKRIVNOST)

    /** Gostitelj indeksatorja za prikaz v oknu (brez kljuca). */
    fun gostitelj(c: Context): String? = indeksator(c)?.let { try { URL(it.osnova).host } catch (_: Exception) { null } }

    /**
     * Ali Usenet sploh iscemo: indeksator, kljuc TorBox (prenese in pretaka TorBox) in uporabnik ni izbral »Brez debrida«.
     * TorBox brez paketa Pro vrne napako paketa ob predvajanju (okno pove zakaj).
     */
    fun jeNaVoljo(c: Context): Boolean = imaIndeksator(c) && TorBox.imaKljuc(c) && Debrid.aktivna(c) != null

    /**
     * Diagnostika razvijalca (lastnik 25. 10. 2026: Usenet merimo samo po samodejni poti, brez rocne izbire vira):
     * `debug.safeer.usenet_samo` = cas izteka v sekundah (najvec 6 h, samo adb, ne ostane po ponovnem zagonu - kot
     * [Webtor.samoWebtor]). Samodejna izbira takrat vzame samo Usenet: pripravljen pri TorBoxu, nato s cakanjem na prenos.
     */
    fun samoUsenet(): Boolean = Webtor.diagnostika("debug.safeer.usenet_samo")

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
        val ix = indeksator(c) ?: return emptyList()
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
        return TorBox.usenet(c, nzb, ime, indeks, samoTakoj, napredek).also {
            // Lastnik 25. 10. 2026: stanje kljuca TorBox sledi uporabi (401 -> »ne velja«).
            try { Debrid.obIzidu(c, DebridPravila.TORBOX, it.napaka, it.url != null) } catch (_: Exception) { }
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

    /** Prvih najvec [najvec] bajtov (ostanek se ne bere) - za preverbo zadostuje zacetek odgovora. */
    private fun beriZacetek(vhod: java.io.InputStream, najvec: Int): ByteArray {
        val izhod = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (izhod.size() < najvec) {
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
