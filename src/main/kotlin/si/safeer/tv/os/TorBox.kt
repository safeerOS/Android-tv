package si.safeer.tv.os

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Naloga 119: TorBox kot vir torrentov - magnet -> checkcached -> (createtorrent) -> requestdl -> povezava CDN, ki jo
 * predvaja nas predvajalnik. Pravila in naslovi: [TorBoxPravila].
 *
 * Kljuc vpise uporabnik (Moji viri -> TorBox); hrani se sifriran s kljucem v Android Keystore ([SporocilaSkrivnosti],
 * AES-GCM), nikoli v dnevniku ali v naslovu, ki gre predvajalniku. Brez kljuca ostane obstojeca pot (racunalnik v Linku
 * ali ta naprava). Klici so blokirajoci - samo z delovne niti.
 */
object TorBox {
    private const val TAG = "SafeerTorBox"
    private const val SKRIVNOST = "torbox_api_kljuc"
    private const val PREFS = "safeer_torbox"
    private const val KLJUC_NOVI = "novi_casi"

    fun kljuc(c: Context): String? = SporocilaSkrivnosti.preberi(c, SKRIVNOST)?.let { TorBoxPravila.ocistiKljuc(it) }
    fun imaKljuc(c: Context): Boolean = kljuc(c) != null

    /** Pregled A5: kljuc je shranjen (tudi ce ga Keystore ne zna vec odsifrirati) - [Debrid.neberljiva]. */
    fun kljucShranjen(c: Context): Boolean = SporocilaSkrivnosti.ima(c, SKRIVNOST)

    /** Shrani ocisceni kljuc; vrne false, ce vnos ni kljuc ali shramba ni uspela. */
    fun nastaviKljuc(c: Context, vnos: String): Boolean {
        val k = TorBoxPravila.ocistiKljuc(vnos) ?: return false
        return SporocilaSkrivnosti.shrani(c, SKRIVNOST, k)
    }

    fun pozabiKljuc(c: Context) { SporocilaSkrivnosti.pozabi(c, SKRIVNOST) }

    /**
     * Lastnik 25. 10. 2026: preverba kljuca z »user/me« (nic ne doda v racun). Odgovor nosi tudi e-naslov racuna - vzamemo
     * SAMO paket (polje »plan«), nic ne gre v dnevnik. (izid, paket); brez povezave NI_ODGOVORA.
     */
    fun preveri(kljuc: String): Pair<DebridPravila.Preverba, Int?> = try {
        val o = klic(DebridPravila.TORBOX_UPORABNIK, kljuc, rokMs = 8_000)
        val j = o.json
        val data = j?.optJSONObject("data")
        // Skupni primeri z namizjem (kljuci-preverba.json): polja strogo, kot jih prebere namizje.
        DebridPravila.preverbaTorBoxOdgovor(o.koda, j?.opt("success"), j?.opt("error"), data != null, data?.opt("plan"))
    } catch (e: Exception) {
        Log.i(TAG, "preverba: ${e.javaClass.simpleName}"); DebridPravila.Preverba.NI_ODGOVORA to null
    }

    /** Izid: [url] povezava CDN za predvajanje ali [napaka] (TorBoxPravila.NAPAKA_*) z [opis] TorBoxa za uporabnika. */
    class Izid(val url: String? = null, val napaka: String = "", val opis: String = "", val cakajMs: Long = 0L,
               /** Naloga 128: storitev, ki je dala povezavo ali napako (DebridPravila.TORBOX ...). */
               val ponudnik: String = "")

    private class Odgovor(val koda: Int, val json: JSONObject?)

    private fun klic(naslov: String, kljuc: String?, metoda: String = "GET", obrazec: Map<String, String>? = null,
                     rokMs: Int = 20_000,
                     /** Naloga 141: datoteka v obrazcu (polje `file`): ime in vsebina. */
                     datoteka: Pair<String, ByteArray>? = null): Odgovor {
        val p = URL(naslov).openConnection() as HttpURLConnection
        try {
            p.requestMethod = metoda
            p.connectTimeout = minOf(10_000, rokMs); p.readTimeout = rokMs
            p.setRequestProperty("Accept", "application/json")
            if (kljuc != null) p.setRequestProperty("Authorization", TorBoxPravila.glavaAvtorizacije(kljuc))
            if (obrazec != null) {
                val meja = "----safeer" + System.nanoTime()
                p.doOutput = true
                p.setRequestProperty("Content-Type", "multipart/form-data; boundary=$meja")
                val sb = StringBuilder()
                for ((k, v) in obrazec) sb.append("--").append(meja).append("\r\nContent-Disposition: form-data; name=\"").append(k)
                    .append("\"\r\n\r\n").append(v).append("\r\n")
                p.outputStream.use { izhod ->
                    izhod.write(sb.toString().toByteArray(Charsets.UTF_8))
                    if (datoteka != null) {
                        izhod.write(("--$meja\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${datoteka.first}\"\r\n" +
                            "Content-Type: application/x-nzb\r\n\r\n").toByteArray(Charsets.UTF_8))
                        izhod.write(datoteka.second)
                        izhod.write("\r\n".toByteArray(Charsets.UTF_8))
                    }
                    izhod.write("--$meja--\r\n".toByteArray(Charsets.UTF_8))
                }
            }
            val koda = p.responseCode
            // Pregled Grok 8: odgovor najvec DebridPravila.NAJVEC_ODGOVORA bajtov (prej readBytes() brez meje) - vecji je napaka.
            if (p.contentLengthLong > DebridPravila.NAJVEC_ODGOVORA) throw java.io.IOException("odgovor prevelik")
            val telo = (if (koda in 200..299) p.inputStream else p.errorStream)?.use {
                (DebridPravila.beriOmejeno(it) ?: throw java.io.IOException("odgovor prevelik")).toString(Charsets.UTF_8)
            }.orEmpty()
            return Odgovor(koda, try { JSONObject(telo) } catch (_: Throwable) { null })
        } finally { p.disconnect() }
    }

    private fun napaka(o: Odgovor): Izid = Izid(napaka = TorBoxPravila.vrstaNapake(o.koda, o.json?.optString("error")?.takeIf { it.isNotBlank() && it != "null" }),
        opis = o.json?.optString("detail").orEmpty().takeIf { it != "null" }.orEmpty().take(300))

    private fun uspeh(o: Odgovor): Boolean = o.koda in 200..299 && o.json?.optBoolean("success") == true

    private fun datoteke(a: JSONArray?): List<TorBoxPravila.Datoteka> = if (a == null) emptyList() else
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            TorBoxPravila.Datoteka(it.optLong("id"), it.optString("short_name").ifBlank { it.optString("name") }, it.optLong("size"), it.optString("mimetype"))
        }

    private fun obstojeci(kljuc: String, hash: String): Long {
        val o = klic(TorBoxPravila.naslovVsi(), kljuc)
        val a = o.json?.optJSONArray("data") ?: return -1L
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .firstOrNull { it.optString("hash").equals(hash, ignoreCase = true) }?.optLong("id", -1L) ?: -1L
    }

    /**
     * Naloga 130: kateri od [hashi] so pri TorBoxu ze pripravljeni (en klic checkcached za do 100 hashev; ne doda nicesar
     * v uporabnikov racun in ne steje v mejo 60/uro). Brez kljuca, ob napaki ali po roku: prazno (oznake ⚡ ni).
     */
    fun predpomnjeni(c: Context, hashi: List<String>, rokMs: Int = 4_000): Set<String> {
        val kljuc = kljuc(c) ?: return emptySet()
        val h = DebridPravila.hashiZaPreverbo(hashi)
        if (h.isEmpty()) return emptySet()
        return try {
            val o = klic(TorBoxPravila.naslovPredpomnjenihVec(h), kljuc, rokMs = rokMs)
            if (!uspeh(o)) {
                // Lastnik 25. 10. 2026: zavrnjen kljuc ze ob preverbi ⚡ - vrstica v »Debrid storitve« pove »ne velja«.
                Debrid.obIzidu(c, DebridPravila.TORBOX, napaka(o).napaka, false)
                return emptySet()
            }
            val data = o.json?.optJSONObject("data") ?: return emptySet()
            DebridPravila.predpomnjeni(data.keys().asSequence().toList(), h).also { Log.i(TAG, "checkcached: ${it.size} od ${h.size} pripravljenih") }
        } catch (e: Exception) {
            Log.w(TAG, "checkcached (vec): ${e.javaClass.simpleName}"); emptySet()
        }
    }

    private val zaklepNovih = Any()

    /**
     * Pregled N4: meja novih torrentov na uro pod zaklepom (predvajanje in TorBox v ozadju iz dveh niti ne preskocita stetja).
     * null = sme (zabelezeno), sicer koliko ms je treba pocakati.
     */
    private fun zabeleziNovega(c: Context): Long? = synchronized(zaklepNovih) {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val casi = prefs.getString(KLJUC_NOVI, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        val zdaj = System.currentTimeMillis()
        if (!TorBoxPravila.MejaNovih.dovoli(casi, zdaj)) TorBoxPravila.MejaNovih.cakajMs(casi, zdaj)
        else {
            prefs.edit().putString(KLJUC_NOVI, (TorBoxPravila.MejaNovih.veljavni(casi, zdaj) + zdaj).joinToString(",")).commit()
            null
        }
    }

    /**
     * Povezava za predvajanje za [magnet] (datoteka [indeks] po dodatku, -1 = najvecja). [napredek] dobi sporocila o
     * cakanju (z delovne niti). Predpomnjen torrent: takoj. Nov: steje v mejo 60/uro; cakamo do 3 min, da ga TorBox prenese.
     */
    fun tok(c: Context, magnet: String, indeks: Int, napredek: (Int) -> Unit = {},
            /** Naloga 128: samo predpomnjen torrent - nepredpomnjenega ne dodamo (ne steje v mejo 60/uro). */
            samoPredpomnjeno: Boolean = false): Izid {
        val kljuc = kljuc(c) ?: return Izid(napaka = TorBoxPravila.NAPAKA_KLJUC)
        val hash = TorBoxPravila.hashIzMagneta(magnet) ?: return Izid(napaka = TorBoxPravila.NAPAKA_NI_DATOTEKE)
        return try {
            Log.i(TAG, "checkcached ${hash.take(8)}…")
            val pred = klic(TorBoxPravila.naslovPredpomnjeno(hash), kljuc)
            if (!uspeh(pred)) return napaka(pred)
            val vnos = pred.json?.optJSONObject("data")?.optJSONObject(hash)
            val predpomnjen = vnos != null
            if (!predpomnjen && samoPredpomnjeno) return Izid(napaka = DebridPravila.NAPAKA_NI_PREDPOMNJENO)
            // Pregled Grok 2: preklican poskus ne doda torrenta (in ne steje v mejo 60/uro) - klic HTTP prekinitve ne vidi.
            Debrid.preveriPreklic()
            if (!predpomnjen) zabeleziNovega(c)?.let { return Izid(napaka = TorBoxPravila.NAPAKA_MEJA, cakajMs = it) }
            Log.i(TAG, "createtorrent ${hash.take(8)}… predpomnjen=$predpomnjen")
            val ustvari = klic(TorBoxPravila.naslovUstvari(), kljuc, "POST", mapOf("magnet" to magnet, "allow_zip" to "false"))
            var id = ustvari.json?.optJSONObject("data")?.optLong("torrent_id", -1L) ?: -1L
            // Torrent je ze v uporabnikovem racunu (DUPLICATE_ITEM): poiscemo ga po hashu v njegovem seznamu.
            if (!uspeh(ustvari) && ustvari.json?.optString("error") == "DUPLICATE_ITEM") id = obstojeci(kljuc, hash)
            else if (!uspeh(ustvari)) return napaka(ustvari)
            if (id < 0) return napaka(ustvari)
            // Datoteke: predpomnjen jih je dal ze checkcached; za nov torrent (in kot rezerva) mylist, dokler ni prenesen.
            var seznam = datoteke(vnos?.optJSONArray("files"))
            if (!predpomnjen || seznam.isEmpty()) {
                var cakano = 0L
                var pripravljen = false
                while (!pripravljen && cakano <= TorBoxPravila.CAKAJ_NOVEGA_MS) {
                    // Pregled A1: preklic (nova izbira, Preklici) ustavi cakanje takoj, ne sele po 3 min.
                    if (Thread.interrupted()) throw InterruptedException()
                    val o = klic(TorBoxPravila.naslovSeznam(id), kljuc)
                    if (!uspeh(o)) return napaka(o)
                    val t = o.json?.optJSONObject("data")
                    seznam = datoteke(t?.optJSONArray("files"))
                    pripravljen = t != null && TorBoxPravila.pripravljen(t.optString("download_state"),
                        t.optBoolean("download_finished"), t.optBoolean("download_present")) && seznam.isNotEmpty()
                    if (!pripravljen) {
                        napredek((t?.optDouble("progress", 0.0)?.times(100))?.toInt()?.coerceIn(0, 100) ?: 0)
                        Thread.sleep(TorBoxPravila.CAKAJ_KORAK_MS); cakano += TorBoxPravila.CAKAJ_KORAK_MS
                    }
                }
                if (!pripravljen) return Izid(napaka = TorBoxPravila.NAPAKA_PRENASA)
            }
            val d = TorBoxPravila.izberiDatoteko(seznam, indeks) ?: return Izid(napaka = TorBoxPravila.NAPAKA_NI_DATOTEKE)
            // requestdl: kljuc je v naslovu (token) - ta naslov se ne zapise nikamor.
            val dl = klic(TorBoxPravila.naslovPrenosa(kljuc, id, d.id), null)
            val url = dl.json?.optString("data").orEmpty()
            if (!uspeh(dl) || !url.startsWith("https://")) return napaka(dl)
            Log.i(TAG, "tok pripravljen: ${Zasebno.zaDnevnik(url)}")
            Izid(url = url)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); Izid(napaka = TorBoxPravila.NAPAKA_STREZNIK)
        } catch (e: Exception) {
            Log.w(TAG, "TorBox: ${e.javaClass.simpleName}")
            Izid(napaka = TorBoxPravila.NAPAKA_STREZNIK)
        }
    }

    /**
     * Webtor rezerva (25. 10. 2026): torrent, ki ga ta cas predvaja Webtor, dodamo TorBoxu brez cakanja - naslednjic je
     * takoj predvajljiv (⚡). Steje v mejo novih na uro ([TorBoxPravila.MejaNovih]); ze dodan (DUPLICATE_ITEM) je uspeh. To
     * je namerno predpomnjenje. TorBox nima pospravljanja (»samo takoj« pri TorBoxu nicesar ne brise), zato oznaka »cakan« ni
     * potrebna (pregled N4). Tudi ob »Samo takoj predvajljivo«: ta nastavitev pove, da uporabnik ne zeli CAKATI na prenos -
     * na ta prenos ne caka nihce.
     * Z delovne niti; vrne true, ce je torrent pri TorBoxu.
     */
    fun dodajZaPozneje(c: Context, magnet: String): Boolean {
        val kljuc = kljuc(c) ?: return false
        val hash = TorBoxPravila.hashIzMagneta(magnet) ?: return false
        if (zabeleziNovega(c) != null) { Log.i(TAG, "v ozadju: meja novih na uro - ${hash.take(8)}… ni dodan"); return false }
        return try {
            val o = klic(TorBoxPravila.naslovUstvari(), kljuc, "POST", mapOf("magnet" to magnet, "allow_zip" to "false"))
            val ze = !uspeh(o) && o.json?.optString("error") == "DUPLICATE_ITEM"
            Log.i(TAG, "v ozadju (Webtor): createtorrent ${hash.take(8)}… ${if (uspeh(o)) "dodan" else if (ze) "ze v racunu" else "napaka ${o.koda}"}")
            uspeh(o) || ze
        } catch (e: Exception) {
            Log.w(TAG, "v ozadju: ${e.javaClass.simpleName}"); false
        }
    }

    /**
     * Naloga 141: Usenet prek TorBoxa (paket Pro). [nzb] je datoteka, ki jo je naprava prenesla od indeksatorja ([Usenet]) -
     * TorBox kljuca indeksatorja ne vidi. [samoTakoj]: `add_only_if_cached` - nic se ne doda, ce TorBox prenosa se nima
     * (napaka »ni predpomnjeno«, uporabnik lahko izbere »Pocakaj na prenos«). Sicer cakamo do
     * [UsenetPravila.CAKAJ_PRENOSA_MS] z napredkom. Izid nosi ponudnika TorBox. Z delovne niti.
     */
    fun usenet(c: Context, nzb: ByteArray, ime: String, indeks: Int, samoTakoj: Boolean, napredek: (Int) -> Unit = {}): Izid {
        val kljuc = kljuc(c) ?: return Izid(napaka = TorBoxPravila.NAPAKA_KLJUC, ponudnik = DebridPravila.TORBOX)
        fun z(iz: Izid) = Izid(iz.url, iz.napaka, iz.opis, iz.cakajMs, DebridPravila.TORBOX)
        return try {
            Debrid.preveriPreklic()
            val ustvari = klic(UsenetPravila.naslovUstvari(), kljuc, "POST",
                mapOf("name" to ime.take(120), "add_only_if_cached" to samoTakoj.toString(), "post_processing" to "-1"),
                rokMs = 60_000, datoteka = "safeer.nzb" to nzb)
            var id = ustvari.json?.optJSONObject("data")?.optLong("usenetdownload_id", -1L) ?: -1L
            if (!uspeh(ustvari)) {
                val n = napaka(ustvari)
                when {
                    ustvari.json?.optString("error") == "DUPLICATE_ITEM" && id < 0 -> id = obstojeciUsenet(kljuc, ime)
                    ustvari.json?.optString("error") == "DUPLICATE_ITEM" -> { }
                    UsenetPravila.niPredpomnjeno(samoTakoj, n.napaka) -> {
                        Log.i(TAG, "usenet: ni v predpomnilniku TorBox"); return z(Izid(napaka = DebridPravila.NAPAKA_NI_PREDPOMNJENO))
                    }
                    else -> return z(n)
                }
            }
            if (id < 0) return z(napaka(ustvari))
            Log.i(TAG, "usenet: prenos $id samoTakoj=$samoTakoj")
            var seznam = emptyList<TorBoxPravila.Datoteka>()
            var cakano = 0L
            var pripravljen = false
            while (!pripravljen && cakano <= UsenetPravila.CAKAJ_PRENOSA_MS) {
                // Pregled A1: preklic ustavi cakanje (do 10 min) takoj.
                if (Thread.interrupted()) throw InterruptedException()
                val o = klic(UsenetPravila.naslovSeznam(id), kljuc)
                if (!uspeh(o)) return z(napaka(o))
                val t = o.json?.optJSONObject("data")
                seznam = datoteke(t?.optJSONArray("files"))
                pripravljen = t != null && TorBoxPravila.pripravljen(t.optString("download_state"),
                    t.optBoolean("download_finished"), t.optBoolean("download_present")) && seznam.isNotEmpty()
                if (!pripravljen) {
                    if (t?.optString("download_state")?.lowercase()?.startsWith("failed") == true)
                        return z(Izid(napaka = TorBoxPravila.NAPAKA_NI_DATOTEKE))
                    napredek((t?.optDouble("progress", 0.0)?.times(100))?.toInt()?.coerceIn(0, 100) ?: 0)
                    Thread.sleep(TorBoxPravila.CAKAJ_KORAK_MS); cakano += TorBoxPravila.CAKAJ_KORAK_MS
                }
            }
            if (!pripravljen) return z(Izid(napaka = TorBoxPravila.NAPAKA_PRENASA))
            val d = TorBoxPravila.izberiDatoteko(seznam, indeks) ?: return z(Izid(napaka = TorBoxPravila.NAPAKA_NI_DATOTEKE))
            // requestdl: kljuc je v naslovu (token) - ta naslov se ne zapise nikamor.
            val dl = klic(UsenetPravila.naslovPrenosa(kljuc, id, d.id), null)
            val url = dl.json?.optString("data").orEmpty()
            if (!uspeh(dl) || !url.startsWith("https://")) return z(napaka(dl))
            Log.i(TAG, "usenet: tok pripravljen: ${Zasebno.zaDnevnik(url)}")
            Izid(url = url, ponudnik = DebridPravila.TORBOX)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); z(Izid(napaka = TorBoxPravila.NAPAKA_STREZNIK))
        } catch (e: Exception) {
            Log.w(TAG, "usenet: ${e.javaClass.simpleName}"); z(Izid(napaka = TorBoxPravila.NAPAKA_STREZNIK))
        }
    }

    /** Usenet prenos z imenom [ime] je ze v uporabnikovem racunu (DUPLICATE_ITEM): njegov id ali -1. */
    private fun obstojeciUsenet(kljuc: String, ime: String): Long {
        val a = klic(UsenetPravila.naslovVsi(), kljuc).json?.optJSONArray("data") ?: return -1L
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .firstOrNull { it.optString("name") == ime.take(120) }?.optLong("id", -1L) ?: -1L
    }
}
