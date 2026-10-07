package si.safeer.tv.link

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocket

/**
 * Datoteke te naprave za druge naprave v Safeer Linku (ukaz `files.list`) - kot jih deli Safeer
 * Control na racunalniku, le da so tu videi, glasba in slike iz MediaStore.
 *
 * Po hubu gre samo seznam; datoteke same tecejo **neposredno** s te naprave po HTTPS s potrdilom
 * te naprave (isti kljuc kot za hub, odtis `fp` je v odgovoru) in z enkratnim zetonom na napravo
 * (`?t=`). Streznik se zazene sele, ko kdo prvic vprasa po datotekah, in tece, dokler tece proces.
 * Ista datoteka je v brskalniku za telefon (com.safeer.mobile.browser.link); spremembe gredo v obe.
 */
object DatotekeStreznik {
    private const val TAG = "SafeerDatoteke"
    const val ZMOZNOST = "files"
    private const val NAJVEC = 500
    private const val PREDPONA = "media:"
    /** Zgornja meja telesa ukaza: ukaz je nekaj deset bajtov JSON. */
    private const val NAJVEC_TELESA = 64 * 1024

    private val tece = AtomicBoolean(false)
    @Volatile private var vticnica: ServerSocket? = null
    @Volatile private var vrata = 0
    /**
     * kljuc (id naprave ali id#izdan za prejsnjega) -> zeton. Zeton velja [ZETON_VELJA_MS] od zadnje rabe (tok,
     * ki tece, ga drzi pri zivljenju), najvec [NAJDLJE_MS] od izdaje; po polovici roka naprava dobi novega.
     * Cas je monoton (SystemClock.elapsedRealtime), da ga premik ure ne podaljsa ali skrajsa.
     */
    private class Zeton(val vrednost: String, val izdan: Long, @Volatile var rabljen: Long) {
        fun zivi(zdaj: Long) = zdaj - rabljen < ZETON_VELJA_MS && zdaj - izdan < NAJDLJE_MS
    }
    private val zetoni = ConcurrentHashMap<String, Zeton>()
    /**
     * Datoteke, ki jih je uporabnik te naprave drugi napravi sam poslal ali jih naprava z odprtim predvajalnikom
     * nadaljuje: "jedro naprave|oznaka" -> do kdaj (monotoni cas). Prav ta datoteka je napravi dosegljiva tudi brez
     * odprtih datotek - samo za branje.
     */
    private val izrecno = ConcurrentHashMap<String, Long>()

    fun dovoliIzrecno(ctx: Context, idNaprave: String, oznaka: String) {
        if (idNaprave.isBlank() || oznaka.isBlank()) return
        val zdaj = zdaj()
        izrecno.entries.removeIf { it.value <= zdaj }
        izrecno[kljucIzrecnega(ctx, idNaprave, oznaka)] = zdaj + NAJDLJE_MS
        while (izrecno.size > 512) izrecno.entries.minByOrNull { it.value }?.let { izrecno.remove(it.key) }
    }

    private fun izrecnoDovoljena(ctx: Context, idNaprave: String, oznaka: String?): Boolean {
        if (oznaka.isNullOrBlank()) return false
        val rok = izrecno[kljucIzrecnega(ctx, idNaprave, oznaka)] ?: return false
        return zdaj() < rok
    }

    /**
     * Kljuc shrambe izrecnih dovoljenj: jedro naprave in oznaka datoteke. Oznaka naprave, pod katero je v krogu drug
     * kljuc (prazno jedro), dobi kljuc, ki ni enak nobenemu jedru (DostopPravila.kljucShrambe): dovoljenje prave
     * naprave zanjo ne velja in obratno.
     */
    private fun kljucIzrecnega(ctx: Context, idNaprave: String, oznaka: String): String =
        si.safeer.tv.cast.DostopPravila.kljucShrambe(idNaprave, si.safeer.tv.cast.Dostop.jedro(ctx, idNaprave)) + "|" + oznaka
    private const val ZETON_VELJA_MS = 12 * 3600_000L
    private const val NAJDLJE_MS = 7 * 24 * 3600_000L
    private fun zdaj() = android.os.SystemClock.elapsedRealtime()
    private val nakljucje = SecureRandom()

    // ------------------------------------------------------------------ seznam

    fun dovoljenja(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf("android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO", "android.permission.READ_MEDIA_IMAGES")
        else -> arrayOf("android.permission.READ_EXTERNAL_STORAGE")
    }

    fun imamoDovoljenje(context: Context): Boolean =
        dovoljenja().any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private const val PREFS = "safeer_cast_prefs"
    private const val KLJUC_VKLOP = "link_datoteke"

    /** Uporabnikovo stikalo (stran Safeer Linka): privzeto vklopljeno, deli pa se sele z dovoljenjem. */
    fun vklopljeno(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KLJUC_VKLOP, true)

    fun nastavi(context: Context, vklop: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KLJUC_VKLOP, vklop).apply()
    }

    /** Stanje za stran Linka: {"vklopljeno", "dovoljenje", "deli"} - deli le, ko je oboje. */
    fun stanje(context: Context): JSONObject {
        val v = vklopljeno(context)
        val d = imamoDovoljenje(context)
        return JSONObject().put("vklopljeno", v).put("dovoljenje", d).put("deli", v && d)
    }

    /** Odgovor na `files.list`: {"items", "folder", "shared", "server"?} - ista oblika kot pri Controlu.
     *  Kadar naprava ne deli, pove zakaj: "reason" = "off" (stikalo) ali "permission" (brez dovoljenja). */
    fun seznam(context: Context, mapa: String, idNaprave: String): JSONObject {
        appContext = context.applicationContext
        val o = JSONObject().put("folder", if (mapa == "root") "" else mapa)
        if (!vklopljeno(context)) return o.put("items", JSONArray()).put("shared", false).put("reason", "off")
        if (!imamoDovoljenje(context)) return o.put("items", JSONArray()).put("shared", false).put("reason", "permission")
        val vnosi = JSONArray()
        var datotek = false
        if (mapa.isBlank() || mapa == "root") {
            // Shramba: datoteke, ki jih je ta naprava shranila za druge naprave (skupni prostor, Shramba.kt).
            val zbirke = listOf("video", "audio", "image") + (if (Build.VERSION.SDK_INT >= 29) listOf("shramba") else emptyList())
            for ((oznaka, ime) in zbirke.map { it to imeZbirke(context, it) }) {
                vnosi.put(JSONObject().put("id", PREDPONA + oznaka).put("name", ime).put("type", "folder"))
            }
        } else {
            val zbirka = mapa.removePrefix(PREDPONA)
            val uri = zbirkaUri(zbirka) ?: return o.put("items", JSONArray()).put("shared", true)
            val stolpci = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_MODIFIED)
            if (zbirka == "video") stolpci.add(MediaStore.Video.VideoColumns.DURATION)
            try {
                val (izbor, argumenti) = if (zbirka == "shramba")
                    (MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?") to arrayOf(android.os.Environment.DIRECTORY_DOWNLOADS + "/" + Shramba.MAPA + "%")
                else null to null
                context.contentResolver.query(uri, stolpci.toTypedArray(), izbor, argumenti, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")?.use { k ->
                    val ci = k.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val cn = k.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val cs = k.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val cm = k.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val cd = k.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val ct = k.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
                    while (k.moveToNext() && vnosi.length() < NAJVEC) {
                        val ime = k.getString(cn) ?: continue
                        val mime = k.getString(cm) ?: ""
                        val vrsta = if (zbirka != "shramba") zbirka else when {
                            mime.startsWith("video/") -> "video"; mime.startsWith("audio/") -> "audio"; mime.startsWith("image/") -> "image"; else -> "file" }
                        vnosi.put(JSONObject().put("id", "$PREDPONA$zbirka:${k.getLong(ci)}").put("name", ime)
                            .put("type", vrsta).put("size", k.getLong(cs)).put("mime", k.getString(cm) ?: "")
                            .put("modified", k.getLong(cd) * 1000L)
                            .put("duration_ms", if (ct >= 0) k.getLong(ct) else 0L))
                        datotek = true
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Branje zbirke ni uspelo: ${e.message}")
            }
        }
        o.put("items", vnosi).put("shared", true)
        // Urejanje (brisanje, vrtenje slik) je mogoce v zbirkah, ne v korenu, kjer so same mape.
        o.put("edit", !(mapa.isBlank() || mapa == "root"))
        if (datotek) opisStreznika(idNaprave)?.let { o.put("server", it) }
        return o
    }

    /**
     * Odgovor na `files.search` (enotno iskanje Safeer Media na drugi napravi): glasba in videi te
     * naprave, v katerih so vse besede poizvedbe - ista oblika kot `files.list`, predvajanje prek istega
     * streznika z zetonom. Deli samo, ce deli tudi `files.list` (stikalo in dovoljenje).
     */
    fun isci(context: Context, poizvedba: String, idNaprave: String): JSONObject {
        appContext = context.applicationContext
        val o = JSONObject()
        if (!vklopljeno(context) || !imamoDovoljenje(context)) return o.put("items", JSONArray()).put("shared", false)
        val vnosi = JSONArray()
        for (n in si.safeer.tv.os.KrajevneDatoteke.najdi(context, poizvedba, 30)) {
            vnosi.put(JSONObject().put("id", "$PREDPONA${n.zbirka}:${n.vrstica}").put("name", n.ime).put("type", n.zbirka)
                .put("mime", n.mime).put("title", n.naslov).put("artist", n.izvajalec).put("path", n.mapa.trimEnd('/')))
        }
        o.put("items", vnosi).put("shared", true)
        if (vnosi.length() > 0) opisStreznika(idNaprave)?.let { o.put("server", it) }
        return o
    }

    private fun imeZbirke(context: Context, zbirka: String): String {
        val ime = when (zbirka) { "audio" -> "os_krajevno_glasba"; "image" -> "os_krajevno_slike"; "shramba" -> "os_krajevno_shramba"; else -> "os_krajevno_videi" }
        val id = context.resources.getIdentifier(ime, "string", context.packageName)
        return if (id != 0) context.getString(id) else when (zbirka) { "audio" -> "Music"; "image" -> "Photos"; "shramba" -> "Safeer Shramba"; else -> "Videos" }
    }

    private fun zbirkaUri(zbirka: String): Uri? = when (zbirka) {
        "audio" -> if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        "image" -> if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        "video" -> if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        "shramba" -> if (Build.VERSION.SDK_INT >= 29) MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL) else null
        else -> null
    }

    /** Oznaka datoteke (media:video:123) -> content URI; null za vse, kar ni datoteka iz zbirke. */
    /** Obratno od [uriIz]: content:// naslov te naprave -> oznaka `media:<zbirka>:<id>` za pot `/d/` (predaja predvajanja). */
    fun oznakaZa(uri: String): String? {
        val u = uri.trimEnd('/')
        val id = u.substringAfterLast('/').toLongOrNull() ?: return null
        for (zbirka in listOf("video", "audio", "image", "shramba")) {
            val osnova = zbirkaUri(zbirka)?.toString()?.trimEnd('/') ?: continue
            if (u == "$osnova/$id") return "$PREDPONA$zbirka:$id"
        }
        return null
    }

    /** Oznaka te naprave (media:video:123) -> content:// naslov za predvajanje z diska; null za tuje oznake. */
    fun uriZa(oznaka: String): String? = if (oznaka.startsWith(PREDPONA)) uriIz(oznaka)?.toString() else null

    private fun uriIz(oznaka: String): Uri? {
        val deli = oznaka.removePrefix(PREDPONA).split(":")
        if (deli.size != 2) return null
        val id = deli[1].toLongOrNull() ?: return null
        return ContentUris.withAppendedId(zbirkaUri(deli[0]) ?: return null, id)
    }

    // ------------------------------------------------------------------ zetoni

    @Synchronized
    private fun zetonZa(idNaprave: String, zdaj: Long = zdaj()): String {
        val kljuc = idNaprave.ifBlank { "naprava" }
        zetoni.entries.removeIf { !it.value.zivi(zdaj) }
        zetoni[kljuc]?.takeIf { zdaj - it.izdan < ZETON_VELJA_MS / 2 }?.let { return it.vrednost }
        val b = ByteArray(24); nakljucje.nextBytes(b)
        val z = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        // Prejsnji zeton te naprave velja do konca svojega roka (film, ki ravno tece, ne pade).
        zetoni[kljuc]?.let { zetoni["$kljuc#${it.izdan}"] = it }
        zetoni[kljuc] = Zeton(z, zdaj, zdaj)
        while (zetoni.size > 64) zetoni.entries.minByOrNull { it.value.rabljen }?.let { zetoni.remove(it.key) }
        return z
    }

    private fun zetonVelja(z: String?, zdaj: Long = zdaj(), zahtevaDeljenje: Boolean = true, oznaka: String? = null): Boolean {
        if (z.isNullOrBlank()) return false
        val zb = z.toByteArray()
        val (kljuc, najden) = zetoni.entries.firstOrNull { it.value.zivi(zdaj) && MessageDigest.isEqual(it.value.vrednost.toByteArray(), zb) }
            ?.let { it.key to it.value } ?: return false
        // Deljenje izklopljeno ali naprava umaknjena iz kroga (tudi z druge naprave): zeton takoj ne velja vec,
        // ne sele po [ZETON_VELJA_MS] (pregled 29. 9. 2026, tocka 12).
        val ctx = appContext
        if (ctx != null && ((zahtevaDeljenje && !vklopljeno(ctx)) || umaknjena(ctx, kljuc.substringBefore('#')))) {
            zetoni.remove(kljuc)
            return false
        }
        // Dovoljenje naprave se preveri ob VSAKI zahtevi: ko ji uporabnik dostop odvzame, datoteke takoj niso vec
        // dosegljive. Datoteke te naprave zahtevajo odprte »datoteke« (cast/Dostop) - ali pa je bila prav ta datoteka
        // napravi izrecno poslana (samo branje). Tokovi pomoci (pretvorba, torrent) imajo svojo nakljucno oznako, ki jo
        // dobi le naprava, ki je tok smela zahtevati ali ji je bil poslan; zanje zadosca zeton. Zetona ob zavrnitvi ne
        // zavrzemo: velja naprej za izrecno poslano in za tokove.
        if (ctx != null && zahtevaDeljenje) {
            val naprava = kljuc.substringBefore('#')
            if (!si.safeer.tv.cast.Dostop.sme(ctx, naprava, si.safeer.tv.cast.DostopPravila.Zmoznost.DATOTEKE) &&
                !izrecnoDovoljena(ctx, naprava, oznaka)) return false
        }
        najden.rabljen = zdaj
        return true
    }

    private fun umaknjena(ctx: Context, idNaprave: String): Boolean =
        try { si.safeer.tv.cast.KrogNaprave.krog(ctx).jeUmaknjen(idNaprave) } catch (_: Throwable) { false }

    // ------------------------------------------------------------------ streznik

    private var appContext: Context? = null

    @Synchronized
    private fun zazeni(): Boolean {
        if (tece.get() && vticnica?.isClosed == false) return true
        return try {
            val v = si.safeer.tv.cast.HubTls.streznik().createServerSocket(0, 8) as SSLServerSocket
            si.safeer.tv.cast.HubTls.nastaviStrezno(v)
            vticnica = v
            vrata = v.localPort
            tece.set(true)
            Thread({ zanka(v) }, "safeer-datoteke").apply { isDaemon = true; start() }
            Log.i(TAG, "Streznik datotek na vratih $vrata")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Streznika datotek ni bilo mogoce zagnati: ${e.message}")
            false
        }
    }

    fun pripravi(context: Context) { appContext = context.applicationContext }

    /** Streznik te naprave za drugo napravo ([idNaprave]): {base_url, fp, token, hub} ali null, ce ga ni mogoce zagnati. */
    fun streznikZa(context: Context, idNaprave: String): JSONObject? {
        appContext = context.applicationContext
        return opisStreznika(idNaprave)
    }

    /**
     * Naslov iz dokumentacijskega obsega (RFC 5737), na katerem ni nikogar. Stoji v opisu streznika, kadar ta naprava
     * nima naslova v domacem omrezju (mobilni podatki): neposredni poskus nanj odpove, odjemalec gre prek Huba.
     */
    const val NASLOV_BREZ_OMREZJA = "192.0.2.1"

    /**
     * Opis streznika te naprave za napravo [idNaprave]: naslov, odtis potrdila, njen zeton in `hub: 2` (Hub te
     * naprave streze iste poti pod /cast - datoteka, slicica, urejanje, tokovi; zdoma kot doma).
     *
     * Naslov v domacem omrezju NI pogoj. Na mobilnih podatkih ga naprava nima, do njenih datotek pa pride druga
     * naprava prek Huba (Global Link). Prej je opis brez domacega naslova izostal: druga naprava je videla seznam,
     * odpreti pa ni mogla nicesar.
     */
    private fun opisStreznika(idNaprave: String): JSONObject? {
        if (!zazeni()) return null
        val naslov = krajevniNaslov() ?: NASLOV_BREZ_OMREZJA
        return JSONObject().put("base_url", "https://$naslov:$vrata")
            .put("fp", si.safeer.tv.cast.HubTls.lastniOdtis()).put("token", zetonZa(idNaprave))
            .put("hub", 2)
    }

    fun ustavi() {
        tece.set(false)
        try { vticnica?.close() } catch (_: Throwable) { }
        vticnica = null
    }

    private fun zanka(v: ServerSocket) {
        while (tece.get() && !v.isClosed) {
            val s = try { v.accept() } catch (_: Throwable) { break }
            Thread({ postrezi(s) }, "safeer-datoteke-zahteva").apply { isDaemon = true; start() }
        }
    }

    private fun postrezi(s: Socket) {
        try {
            s.soTimeout = 15_000
            val vhod = s.getInputStream()
            val izhod = s.getOutputStream()
            val prva = preberiVrstico(vhod) ?: return
            val glave = HashMap<String, String>()
            while (true) {
                val v = preberiVrstico(vhod) ?: break
                if (v.isEmpty()) break
                val i = v.indexOf(':')
                if (i > 0) glave[v.substring(0, i).trim().lowercase()] = v.substring(i + 1).trim()
            }
            val deli = prva.split(" ")
            if (deli.size < 2) { napaka(izhod, 405, "samo GET ali POST"); return }
            val cilj = deli[1]
            // Zeton kot pri Controlu: glava X-Safeer-Token (predvajalnik, slike) ali ?t= (neposredna povezava).
            val poizvedba = cilj.substringAfter('?', "")
            val zeton = glave["x-safeer-token"]
                ?: poizvedba.split("&").firstOrNull { it.startsWith("t=") }?.substring(2)?.let { URLDecoder.decode(it, "UTF-8") }
            postreziZahtevo(deli[0], cilj.substringBefore('?'), zeton, glave, vhod, izhod)
        } catch (e: Throwable) {
            Log.i(TAG, "Zahteva: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        } finally {
            try { s.close() } catch (_: Throwable) { }
        }
    }

    /**
     * Iste poti prek Huba (`/cast/d/<id>`, `/cast/thumb/<id>`, `/cast/live/<id>`, `/cast/magnet/<skrivnost>`): po
     * Global Linku rele pripelje samo povezavo do vrat Huba. Zeton samo v glavi (naslov prek releja ne nosi
     * skrivnosti). Vrne false, ce pot ni za streznik datotek.
     */
    fun jePotPrekHuba(pot: String): Boolean =
        pot.startsWith("/cast/d/") || pot.startsWith("/cast/thumb/") || pot.startsWith("/cast/live/") || pot.startsWith("/cast/magnet/")

    fun prekHuba(metoda: String, pot: String, glave: Map<String, String>, vhod: InputStream, izhod: OutputStream): Boolean {
        if (!jePotPrekHuba(pot)) return false
        try {
            postreziZahtevo(metoda, pot.removePrefix("/cast"), glave["x-safeer-token"], glave, vhod, izhod)
        } catch (e: Throwable) {
            Log.i(TAG, "Zahteva prek Huba: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        }
        return true
    }

    private fun postreziZahtevo(metoda: String, pot: String, zeton: String?, glave: Map<String, String>,
                                vhod: InputStream, izhod: OutputStream) {
        run {
            if (metoda != "GET" && metoda != "HEAD" && metoda != "POST") { napaka(izhod, 405, "samo GET ali POST"); return }
            if (pot.startsWith("/live/")) {
                // Sprotno pretvorjeni tok za drugo napravo (Pretok): zeton kot pri datotekah, a brez pogoja,
                // da naprava deli svoje datoteke - tok ni njena datoteka.
                if (metoda == "POST") { napaka(izhod, 405, "samo GET"); return }
                if (!zetonVelja(zeton, zahtevaDeljenje = false)) { napaka(izhod, 401, "manjka ali napacen zeton"); return }
                Pretok.postrezi(pot.substring(6).substringBefore('?'), metoda, izhod)
                return
            }
            if (pot.startsWith("/magnet/")) {
                // Torrent, ki ga ta naprava prenasa in pretaka drugi napravi v Linku (MagnetPomoc): zeton kot pri
                // datotekah, a brez pogoja, da naprava deli svoje datoteke - tok ni njena datoteka.
                if (metoda == "POST") { napaka(izhod, 405, "samo GET"); return }
                if (!zetonVelja(zeton, zahtevaDeljenje = false)) { napaka(izhod, 401, "manjka ali napacen zeton"); return }
                si.safeer.tv.os.MagnetMotor.postreziNapravi(pot.substring(8).substringBefore('?'), metoda, glave["range"].orEmpty(), izhod)
                return
            }
            if (!pot.startsWith("/d/") && !pot.startsWith("/thumb/")) { napaka(izhod, 404, "ni take poti"); return }
            // Urejanje (POST) samo z odprtimi datotekami - izrecno poslana datoteka je samo za branje.
            val oznakaZahteve = if (metoda == "POST") null else try {
                URLDecoder.decode(pot.substring(if (pot.startsWith("/thumb/")) 7 else 3), "UTF-8")
            } catch (_: Throwable) { null }
            if (!zetonVelja(zeton, oznaka = oznakaZahteve)) { napaka(izhod, 401, "manjka ali napacen zeton"); return }
            val ctx = appContext ?: run { napaka(izhod, 503, "ni pripravljeno"); return }
            val palec = pot.startsWith("/thumb/")
            val uri = uriIz(URLDecoder.decode(pot.substring(if (palec) 7 else 3), "UTF-8")) ?: run { napaka(izhod, 404, "datoteke ni"); return }
            if (palec) { posljiSlicico(ctx, uri, metoda, izhod); return }
            if (metoda == "POST") { uredi(ctx, uri, glave, vhod, izhod); return }
            val opis = try { ctx.contentResolver.openAssetFileDescriptor(uri, "r") } catch (_: Throwable) { null }
                ?: run { napaka(izhod, 404, "datoteke ni"); return }
            opis.use { o ->
                val velikost = o.length
                val vrsta = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                var zacetek = 0L
                var konec = velikost - 1
                var delni = false
                glave["range"]?.takeIf { it.startsWith("bytes=") }?.let { r ->
                    val ab = r.substring(6).split("-", limit = 2)
                    try {
                        if (ab[0].isEmpty() && ab.size > 1 && ab[1].isNotEmpty()) zacetek = maxOf(0L, velikost - ab[1].toLong())
                        else {
                            zacetek = ab[0].toLong()
                            if (ab.size > 1 && ab[1].isNotEmpty()) konec = minOf(velikost - 1, ab[1].toLong())
                        }
                        delni = true
                    } catch (_: Throwable) { delni = false }
                }
                if (delni && (zacetek > konec || zacetek >= velikost)) {
                    izhod.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$velikost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                val dolzina = konec - zacetek + 1
                val sb = StringBuilder()
                sb.append(if (delni) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                sb.append("Content-Type: $vrsta\r\nAccept-Ranges: bytes\r\nContent-Length: $dolzina\r\n")
                if (delni) sb.append("Content-Range: bytes $zacetek-$konec/$velikost\r\n")
                sb.append("Cache-Control: private, max-age=0\r\nConnection: close\r\n\r\n")
                izhod.write(sb.toString().toByteArray())
                if (metoda == "HEAD") return
                o.createInputStream().use { vir ->
                    preskoci(vir, zacetek)
                    val b = ByteArray(64 * 1024)
                    var ostane = dolzina
                    while (ostane > 0) {
                        val n = vir.read(b, 0, minOf(b.size.toLong(), ostane).toInt())
                        if (n <= 0) break
                        izhod.write(b, 0, n)
                        ostane -= n
                    }
                }
                izhod.flush()
            }
        }
    }

    private fun preskoci(vir: InputStream, koliko: Long) {
        var ostane = koliko
        while (ostane > 0) {
            val n = vir.skip(ostane)
            if (n <= 0) { if (vir.read() < 0) return; ostane-- } else ostane -= n
        }
    }

    /** Majhna JPEG slicica za druge Safeer naprave; polne fotografije nikoli ne dekodiramo. */
    private fun posljiSlicico(ctx: Context, uri: Uri, metoda: String, izhod: OutputStream) {
        val mime = ctx.contentResolver.getType(uri).orEmpty()
        val video = mime.startsWith("video/")
        val id = uri.lastPathSegment?.toLongOrNull()
        val bitmap = try {
            if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(uri, Size(256, 256), null)
            else if (id != null && video) MediaStore.Video.Thumbnails.getThumbnail(ctx.contentResolver, id,
                MediaStore.Video.Thumbnails.MINI_KIND, null)
            else if (id != null) MediaStore.Images.Thumbnails.getThumbnail(ctx.contentResolver, id,
                MediaStore.Images.Thumbnails.MINI_KIND, null)
            else null
        } catch (_: Throwable) { null } ?: run { napaka(izhod, 404, "slicice ni"); return }
        val b = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, b)
        bitmap.recycle()
        val telo = b.toByteArray()
        izhod.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${telo.size}\r\n" +
            "Cache-Control: private, max-age=3600\r\nConnection: close\r\n\r\n").toByteArray())
        if (metoda != "HEAD") izhod.write(telo)
        izhod.flush()
    }

    private fun napaka(izhod: OutputStream, koda: Int, besedilo: String) {
        val telo = JSONObject().put("napaka", besedilo).toString().toByteArray()
        val beseda = when (koda) { 401 -> "Unauthorized"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error" }
        izhod.write("HTTP/1.1 $koda $beseda\r\nContent-Type: application/json\r\nContent-Length: ${telo.size}\r\nConnection: close\r\n\r\n".toByteArray())
        izhod.write(telo)
        izhod.flush()
    }

    private fun preberiVrstico(vhod: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val z = vhod.read()
            if (z < 0) return if (sb.isEmpty()) null else sb.toString()
            if (z == '\n'.code) break
            if (z != '\r'.code) sb.append(z.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    /** Naslov IPv4 te naprave v domacem omrezju (brez loopback in brez tun). */
    fun krajevniNaslov(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.name.startsWith("dummy") }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Throwable) { null }

    // ------------------------------------------------------------------ urejanje

    /**
     * `POST /d/<oznaka>` z JSON telesom - ista pogodba kot pri Safeer Controlu na racunalniku, zato
     * televizorju ni treba loceno znati za naprave. Brisanje gre v Smeti naprave, vrtenje slike JPEG
     * pa spremeni samo oznako EXIF.
     *
     * Kadar Android zahteva privolitev uporabnika (fotografija, ki je Safeer ni ustvaril), odgovorimo
     * z oznako `potrebna_potrditev` in na tej napravi pokazemo sistemsko vprasanje. Televizor to
     * izpise kot »Potrdi na napravi«.
     */
    private fun uredi(ctx: Context, uri: Uri, glave: Map<String, String>, vhod: InputStream, izhod: OutputStream) {
        val dolzina = glave["content-length"]?.toIntOrNull() ?: 0
        if (dolzina <= 0 || dolzina > NAJVEC_TELESA) { napaka(izhod, 400, "telo je prazno ali preveliko"); return }
        val telo = ByteArray(dolzina)
        var brano = 0
        while (brano < dolzina) {
            val n = vhod.read(telo, brano, dolzina - brano)
            if (n < 0) break
            brano += n
        }
        if (brano < dolzina) { napaka(izhod, 400, "telo ni celo"); return }
        val j = try { JSONObject(String(telo, Charsets.UTF_8)) } catch (_: Throwable) {
            napaka(izhod, 400, "telo ni JSON"); return
        }
        var izid = when (val op = j.optString("op")) {
            "delete" -> UrejanjeMedijev.izbrisi(ctx, uri)
            "rotate" -> UrejanjeMedijev.zavrti(ctx, uri, j.optInt("degrees", 90))
            // Preimenovanja in premikanja v zbirki (MediaStore) namenoma ne ponujamo: mape so
            // sistemske, ime pa je del zapisa v zbirki. Televizor teh moznosti ne kaze.
            "rename", "move" -> UrejanjeMedijev.Izid(false, "ni_mogoce_na_napravi")
            else -> UrejanjeMedijev.Izid(false, if (op.isBlank()) "manjka_op" else "neznan_op")
        }
        if (izid.potrditev) {
            val dejanje = if (j.optString("op") == "delete") PotrditevActivity.DEJANJE_BRISANJE
                          else PotrditevActivity.DEJANJE_VRTENJE
            PotrditevActivity.pokazi(ctx, uri, dejanje, j.optInt("degrees", 90))
            // Zahtevo zadrzimo, dokler lastnik ne odgovori: televizor tako takoj pokaze
            // zavrteno ali izbrisano sliko. Ce se ne odzove, odgovorimo kot prej in dejanje
            // se vseeno dokonca, ko potrdi - seznam se osvezi ob vrnitvi nanj.
            PotrditevActivity.pocakaj(uri, dejanje, PotrditevActivity.CAKANJE)?.let { izid = it }
        }
        val odgovor = JSONObject().put("ok", izid.ok)
        if (!izid.ok) odgovor.put("napaka", izid.napaka)
        posljiJson(izhod, if (izid.ok) 200 else 409, odgovor.toString())
    }

    private fun posljiJson(izhod: OutputStream, koda: Int, telo: String) {
        val b = telo.toByteArray(Charsets.UTF_8)
        izhod.write(("HTTP/1.1 $koda ${if (koda == 200) "OK" else "Conflict"}\r\n" +
                "Content-Type: application/json; charset=utf-8\r\nContent-Length: ${b.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        izhod.write(b)
        izhod.flush()
    }
}
