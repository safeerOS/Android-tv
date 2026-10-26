package si.safeer.tv.link

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
import si.safeer.tv.R
import si.safeer.tv.link.LinkDatotekePravila as P

/**
 * Datoteke te naprave za druge naprave v Safeer Linku (ukaz `files.list`) - kot jih deli Safeer
 * Control na racunalniku, le da so tu videi, glasba in slike iz MediaStore (in po izbiri uporabnika vse datoteke).
 *
 * Po hubu gre samo seznam; datoteke same tecejo **neposredno** s te naprave po HTTPS s potrdilom
 * te naprave (isti kljuc kot za hub, odtis `fp` je v odgovoru) in z enkratnim zetonom na napravo
 * (`?t=`). Streznik se zazene sele, ko kdo prvic vprasa po datotekah, in tece, dokler tece proces.
 * Ista datoteka je v brskalniku za telefon (com.safeer.mobile.browser.link); spremembe gredo v obe.
 *
 * 25. 10. 2026 (lastnik: racunalnik s polnim dostopom vidi vse, kar telefon dovoli): seznam po delih (`offset`, `limit`,
 * `next_offset`, `total`), datum posnetka (`date_taken`) in obdobje (`from`, `to`), mape Kamera, Posnetki zaslona in
 * Albumi, raven dostopa (`access`, tudi delni dostop Androida 14), vse datoteke (`fs:`, posebni dostop »vse datoteke«),
 * slicice poljubne velikosti z ETag (`/thumb/<id>?s=`). Odlocitve so v [LinkDatotekePravila].
 */
object DatotekeStreznik {
    private const val TAG = "SafeerDatoteke"
    const val ZMOZNOST = "files"
    private const val PREDPONA = P.PREDPONA
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

    // ------------------------------------------------------------------ dovoljenja

    /** Dovoljenja za deljenje medijev; Android 14+ tudi izbrane fotografije (LinkDatotekePravila.dovoljenja). */
    fun dovoljenja(): Array<String> = P.dovoljenja(Build.VERSION.SDK_INT).toTypedArray()

    private fun odobrena(context: Context): Set<String> =
        dovoljenja().filter { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }.toSet()

    /** Koliko naprava deli: poln, delni (izbrane fotografije, Android 14+) ali nic. */
    fun dostop(context: Context): P.Dostop = P.dostop(Build.VERSION.SDK_INT, odobrena(context), vseDatoteke(context))

    /**
     * Ali naprava sme deliti vsaj del medijev. Prej je na Androidu 14 izbor »Izberi fotografije« (samo
     * READ_MEDIA_VISUAL_USER_SELECTED) tu pomenil »ni dovoljenja« in naprava ni delila nicesar.
     */
    fun imamoDovoljenje(context: Context): Boolean = dostop(context) != P.Dostop.BREZ

    private const val PREFS = "safeer_cast_prefs"
    private const val KLJUC_VKLOP = "link_datoteke"
    /** Android 9: uporabnik je izrecno izbral »vse datoteke« (tam jih odpre ze dovoljenje za shrambo). */
    private const val KLJUC_VSE = "link_datoteke_vse"
    private const val VSE_DATOTEKE = "android.permission.MANAGE_EXTERNAL_STORAGE"

    /** Uporabnikovo stikalo (stran Safeer Linka): privzeto vklopljeno, deli pa se sele z dovoljenjem. */
    fun vklopljeno(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KLJUC_VKLOP, true)

    fun nastavi(context: Context, vklop: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KLJUC_VKLOP, vklop).apply()
    }

    @Volatile private var vseDeklarirano: Boolean? = null

    /**
     * Ali ta aplikacija dostop do vseh datotek sploh pozna: MANAGE_EXTERNAL_STORAGE je samo v manifestu telefona in
     * tablice (okusi/telefon, okusi/tablica), televizor in brskalnik ga nimata. Android 10 ga nima (omejena shramba).
     */
    fun vseMogoce(context: Context): Boolean {
        if (Build.VERSION.SDK_INT == 29) return false
        val d = vseDeklarirano ?: try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            (info.requestedPermissions ?: emptyArray()).contains(VSE_DATOTEKE)
        } catch (_: Throwable) { false }.also { vseDeklarirano = it }
        return d
    }

    /**
     * Dostop do vseh datotek: Android 11+ posebni dostop (uporabnik ga vklopi v sistemskih nastavitvah za Safeer in ga
     * tam tudi umakne), Android 9 dovoljenje za shrambo IN izrecna izbira »vse datoteke« (dovoljenje za shrambo je bilo
     * dano za medije - brez izbire ne razsirimo deljenja potiho).
     */
    fun vseDatoteke(context: Context): Boolean {
        if (!vseMogoce(context)) return false
        return if (Build.VERSION.SDK_INT >= 30) {
            try { Environment.isExternalStorageManager() } catch (_: Throwable) { false }
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KLJUC_VSE, false) &&
                context.checkSelfPermission(P.SHRAMBA) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Vse datoteke so deljene: stikalo deljenja je vklopljeno in dostop do vseh datotek odobren. */
    fun vseDatotekeDeljene(context: Context): Boolean = vklopljeno(context) && vseDatoteke(context)

    /**
     * Ali naprava [idNaprave] sme `fs:` (pregled link-datoteke, tocka 2): vse datoteke so deljene, uporabnik je tej napravi
     * datoteke odprl s privolitvijo po uvedbi (cast/Dostop.privolitevVsehDatotek) in datoteke so ji se odprte.
     */
    fun smeVseDatoteke(context: Context, idNaprave: String): Boolean = idNaprave.isNotBlank() &&
        P.smeVseDatoteke(vseDatotekeDeljene(context), si.safeer.tv.cast.Dostop.privolitevVsehDatotek(context, idNaprave),
            si.safeer.tv.cast.Dostop.sme(context, idNaprave, si.safeer.tv.cast.DostopPravila.Zmoznost.DATOTEKE))

    /**
     * Namera za sistemsko stran »dostop do vseh datotek« prav za Safeer (Android 11+); null, kjer je ni. Odpre jo samo
     * dotik uporabnika (privolitev v Linku, obvestilo prosnje druge naprave) - telefon je nikoli ne odpre sam.
     */
    fun nameraVseDatoteke(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < 30 || !vseMogoce(context)) return null
        val zaSafeer = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:" + context.packageName))
        if (zaSafeer.resolveActivity(context.packageManager) != null) return zaSafeer
        val splosna = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        return if (splosna.resolveActivity(context.packageManager) != null) splosna else null
    }

    /**
     * Uporabnik je privolil v Linku ali se dotaknil obvestila prosnje (DostopDatotekActivity): Android 11+ odpre
     * sistemsko stran (izid pride v onActivityResult s [kodaStrani]), Android 9 vprasa za shrambo ([koda]) in si izbiro
     * zapomni. Vrne false, ce na tej napravi ni mogoce. Klice se samo ob dotiku uporabnika.
     */
    fun zahtevajVseDatoteke(dejavnost: android.app.Activity, koda: Int, kodaStrani: Int): Boolean {
        if (!vseMogoce(dejavnost)) return false
        if (Build.VERSION.SDK_INT >= 30) {
            val namera = nameraVseDatoteke(dejavnost) ?: return false
            @Suppress("DEPRECATION")
            return try { dejavnost.startActivityForResult(namera, kodaStrani); true } catch (_: Throwable) { false }
        }
        dejavnost.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KLJUC_VSE, true).apply()
        if (dejavnost.checkSelfPermission(P.SHRAMBA) != PackageManager.PERMISSION_GRANTED) {
            try { dejavnost.requestPermissions(arrayOf(P.SHRAMBA), koda) } catch (_: Throwable) { return false }
        }
        return true
    }

    /** Uporabnik vseh datotek ne deli vec (Android 9: izbira; Android 11+ dostop umakne v sistemskih nastavitvah). */
    fun nehajDelitiVse(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KLJUC_VSE, false).apply()
    }

    /**
     * Stanje za stran Linka: {"vklopljeno", "dovoljenje", "deli", "dostop" (full/partial/none), "vse_datoteke",
     * "vse_mogoce"} - deli le, ko je oboje.
     */
    fun stanje(context: Context): JSONObject {
        val v = vklopljeno(context)
        val d = imamoDovoljenje(context)
        return JSONObject().put("vklopljeno", v).put("dovoljenje", d).put("deli", v && d)
            .put("dostop", dostop(context).oznaka).put("vse_datoteke", vseDatoteke(context)).put("vse_mogoce", vseMogoce(context))
    }

    // ------------------------------------------------------------------ seznam

    /** Del seznama: vnosi, vseh v mapi, odmik naslednjega dela (0 = konec) in ali so med vnosi datoteke. */
    private class Del(val vnosi: JSONArray, val skupaj: Int, val naslednji: Int, val datotek: Boolean)
    private val PRAZNO get() = Del(JSONArray(), 0, 0, false)

    /** Branje velike galerije ne gre na glavno nit (Daljinec se izvaja na njej): dve niti, kratka vrsta. */
    private val delavci = java.util.concurrent.ThreadPoolExecutor(2, 2, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(32)) { r -> Thread(r, "safeer-datoteke-seznam").apply { isDaemon = true } }
        .apply { allowCoreThreadTimeOut(true) }

    /** Izvede [delo] v ozadju; false, ce je vrsta polna (odjemalec po izteku casa vprasa znova). */
    fun vOzadju(delo: () -> Unit): Boolean = try {
        delavci.execute { try { delo() } catch (e: Throwable) { Log.w(TAG, "Seznam v ozadju: ${e.message}") } }
        true
    } catch (_: java.util.concurrent.RejectedExecutionException) { false }

    private fun stevilo(p: JSONObject, kljuc: String): Long? =
        if (p.has(kljuc) && !p.isNull(kljuc)) p.optLong(kljuc, -1L) else null

    /**
     * Odgovor na `files.list`: {"items", "folder", "shared", "access", "total", "next_offset"?, "edit", "server"?} - ista
     * oblika kot pri Controlu. Kadar naprava ne deli, pove zakaj: "reason" = "off" (stikalo) ali "permission" (brez
     * dovoljenja). Parametri ([parametri]) so izbirni: `offset`, `limit` (najvec 500), `from`, `to` (ms, datum nastanka).
     */
    fun seznam(context: Context, mapa: String, idNaprave: String, parametri: JSONObject = JSONObject()): JSONObject {
        appContext = context.applicationContext
        val o = JSONObject().put("folder", if (mapa == P.KOREN) "" else mapa)
        if (!vklopljeno(context)) return o.put("items", JSONArray()).put("shared", false).put("reason", "off")
        // Umaknjena naprava ne dobi niti seznama (pregled link-datoteke, tocka 3; Dostop.smeDejanje ga ze zavrne).
        if (idNaprave.isNotBlank() && umaknjena(context, idNaprave))
            return o.put("items", JSONArray()).put("shared", false).put("reason", "revoked")
        val dostop = dostop(context)
        o.put("access", dostop.oznaka)
        if (dostop == P.Dostop.BREZ) return o.put("items", JSONArray()).put("shared", false).put("reason", "permission")
        val m = P.mapa(mapa)
        val stran = P.stran(parametri.optLong("offset", 0L), parametri.optLong("limit", 0L))
        val obdobje = P.obdobje(stevilo(parametri, "from"), stevilo(parametri, "to"))
        val del = if (m == null || obdobje?.prazno() == true) PRAZNO else try {
            when (m) {
                P.Mapa.Koren -> koren(context, idNaprave)
                is P.Mapa.Zbirka -> zbirka(context, m.ime, stran, obdobje)
                P.Mapa.Kamera -> kamera(context, stran, obdobje)
                P.Mapa.Posnetki -> slikeVidei(context, izborMape(POSNETKI_ZASLONA), stran, obdobje)
                P.Mapa.Albumi -> albumi(context, stran, obdobje)
                is P.Mapa.Album -> slikeVidei(context, "bucket_id = ${m.id}" to null, stran, obdobje)
                is P.Mapa.Pot -> mapaFs(context, m.rel, stran, obdobje, idNaprave)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Branje mape ni uspelo: ${e.message}")
            PRAZNO
        }
        o.put("items", del.vnosi).put("shared", true).put("total", del.skupaj)
        if (del.naslednji > 0) o.put("next_offset", del.naslednji)
        // Urejanje (brisanje, vrtenje slik) je mogoce v zbirkah z datotekami, ne v korenu, seznamu albumov in mapah.
        o.put("edit", m != null && P.urejanje(m))
        if (del.datotek) opisStreznika(idNaprave)?.let { o.put("server", it) }
        return o
    }

    private val KAMERA_MAPE = listOf("DCIM/Camera/")
    private val DCIM = listOf("DCIM/")
    private val POSNETKI_ZASLONA = listOf("Pictures/Screenshots/", "DCIM/Screenshots/")

    private fun datotekeUri(): Uri =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Files.getContentUri("external")

    /** Pogoj za mape (predpone poti pod notranjim pomnilnikom, npr. DCIM/Camera/); Android 9 nima RELATIVE_PATH. */
    private fun izborMape(predpone: List<String>): Pair<String, Array<String>> =
        if (Build.VERSION.SDK_INT >= 29) predpone.joinToString(" OR ") { "relative_path LIKE ?" } to predpone.map { "$it%" }.toTypedArray()
        else predpone.joinToString(" OR ") { "_data LIKE ?" } to predpone.map { "%/$it%" }.toTypedArray()

    /** Ali je v [uri] vsaj en vnos s pogojem [izbor] (mapa kamere, posnetki zaslona). */
    private fun imaVnose(context: Context, uri: Uri, izbor: Pair<String?, Array<String>?>): Boolean = try {
        val k = if (Build.VERSION.SDK_INT >= 30) context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID),
            android.os.Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, izbor.first)
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, izbor.second)
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 1)
            }, null)
        else context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), izbor.first, izbor.second, null)
        k?.use { it.count > 0 } ?: false
    } catch (_: Throwable) { false }

    private fun slikeVideiIzbor(izbor: Pair<String, Array<String>?>): Pair<String?, Array<String>?> =
        P.zdruzi(P.IZBOR_SLIK_VIDEOV, izbor.first) to izbor.second

    /**
     * Koren: najprej fotografije s kamere (odjemalec prvo mapo fotografij odpre kot »Fotografije«), posnetki zaslona in
     * albumi - kadar jih naprava ima -, nato zbirke kot prej in, ce jih uporabnik deli, vse datoteke.
     */
    private fun koren(context: Context, idNaprave: String): Del {
        val v = JSONArray()
        fun mapa(id: String, ime: String) { v.put(JSONObject().put("id", id).put("name", ime).put("type", "folder")) }
        val datoteke = datotekeUri()
        if (imaVnose(context, datoteke, slikeVideiIzbor(izborMape(DCIM)))) mapa(P.KAMERA, context.getString(R.string.link_datoteke_kamera))
        if (imaVnose(context, datoteke, slikeVideiIzbor(izborMape(POSNETKI_ZASLONA)))) mapa(P.POSNETKI, context.getString(R.string.link_datoteke_posnetki))
        if (imaVnose(context, datoteke, P.IZBOR_SLIK_VIDEOV to null)) mapa(P.ALBUMI, context.getString(R.string.link_datoteke_albumi))
        // Shramba: datoteke, ki jih je ta naprava shranila za druge naprave (skupni prostor, Shramba.kt).
        val zbirke = listOf("video", "audio", "image") + (if (Build.VERSION.SDK_INT >= 29) listOf("shramba") else emptyList())
        for (z in zbirke) mapa(PREDPONA + z, imeZbirke(context, z))
        if (smeVseDatoteke(context, idNaprave)) mapa(P.FS, context.getString(R.string.link_datoteke_vse))
        return Del(v, v.length(), 0, false)
    }

    private fun kamera(context: Context, stran: P.Stran, obdobje: P.Obdobje?): Del {
        // Vecina telefonov shranjuje v DCIM/Camera; drugi (npr. DCIM/100ANDRO) - takrat ves DCIM.
        val samoKamera = imaVnose(context, datotekeUri(), slikeVideiIzbor(izborMape(KAMERA_MAPE)))
        return slikeVidei(context, izborMape(if (samoKamera) KAMERA_MAPE else DCIM), stran, obdobje)
    }

    /** Slike in videi iz MediaStore.Files s pogojem [izbor]; oznake ostanejo media:image:<id> / media:video:<id>. */
    private fun slikeVidei(context: Context, izbor: Pair<String, Array<String>?>, stran: P.Stran, obdobje: P.Obdobje?): Del {
        val (pogoj, argumenti) = slikeVideiIzbor(izbor)
        return beriMedije(context, datotekeUri(), "", pogoj, argumenti, P.IZRAZ_DATUMA, stran, obdobje)
    }

    private fun zbirka(context: Context, zbirka: String, stran: P.Stran, obdobje: P.Obdobje?): Del {
        val uri = zbirkaUri(zbirka) ?: return PRAZNO
        val (izbor, argumenti) = if (zbirka == "shramba")
            (MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?") to arrayOf(Environment.DIRECTORY_DOWNLOADS + "/" + Shramba.MAPA + "%")
        else null to null
        val izraz = if (zbirka == "audio" || (Build.VERSION.SDK_INT < 29 && zbirka != "image" && zbirka != "video")) P.IZRAZ_SPREMEMBE else P.IZRAZ_DATUMA
        return beriMedije(context, uri, zbirka, izbor, argumenti, izraz, stran, obdobje)
    }

    /** Stolpci, ki jih ima izvor: [zbirka] "" = MediaStore.Files (slike in videi skupaj). */
    private fun stolpci(zbirka: String, polni: Boolean): Array<String> {
        val s = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_MODIFIED)
        if (zbirka == "") s.add(MediaStore.Files.FileColumns.MEDIA_TYPE)
        if (zbirka == "video" || zbirka == "audio" || zbirka == "") s.add("duration")
        if (!polni) return s.toTypedArray()
        val novi = Build.VERSION.SDK_INT >= 29
        val slike = zbirka == "image" || zbirka == "video" || zbirka == ""
        // Brez zemljepisne sirine/dolzine: lokacije posnetkov ne damo ven (specifikacija 3.7).
        if (novi || slike) s.addAll(listOf("datetaken", "width", "height"))
        // MediaColumns.ORIENTATION je od API 30; prej ga ima samo zbirka slik (drugje bi poizvedba padla in seznam bi
        // izgubil datum posnetka - rezervna poizvedba brez polnih stolpcev).
        if (Build.VERSION.SDK_INT >= 30 || zbirka == "image") s.add("orientation")
        if (novi) s.addAll(listOf("bucket_display_name", "relative_path"))
        return s.toTypedArray()
    }

    private fun poizvedi(context: Context, uri: Uri, stolpci: Array<String>, izbor: String?, argumenti: Array<String>?, red: String): Cursor? = try {
        context.contentResolver.query(uri, stolpci, izbor, argumenti, red)
    } catch (e: Throwable) {
        Log.i(TAG, "Poizvedba ($red): ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        null
    }

    /**
     * Del zbirke MediaStore po datumu nastanka (najnovejse najprej) z obdobjem v SQL. Ce ponudnik izraza ne sprejme
     * (strozja slovnica na kaksni napravi), uredi po spremembi in obdobje preveri tu - odgovor je enak, le pocasnejsi.
     */
    private fun beriMedije(context: Context, uri: Uri, zbirka: String, izbor: String?, argumenti: Array<String>?,
                           izraz: String, stran: P.Stran, obdobje: P.Obdobje?): Del {
        var vKodi = false
        val k = poizvedi(context, uri, stolpci(zbirka, true), P.zdruzi(izbor, P.izborObdobja(obdobje, izraz)), argumenti, "$izraz DESC")
            ?: run { vKodi = obdobje != null; poizvedi(context, uri, stolpci(zbirka, true), izbor, argumenti, "date_modified DESC") }
            ?: run { vKodi = obdobje != null; poizvedi(context, uri, stolpci(zbirka, false), izbor, argumenti, "date_modified DESC") }
            ?: return PRAZNO
        k.use {
            val s = Stolpci(k)
            val vnosi = JSONArray()
            if (!vKodi) {
                val skupaj = k.count
                if (stran.odmik >= skupaj || !k.moveToPosition(stran.odmik)) return Del(vnosi, skupaj, 0, false)
                var pregledanih = 0
                do {
                    pregledanih++
                    vnosMedija(k, s, zbirka)?.let { vnosi.put(it) }
                } while (pregledanih < stran.meja && k.moveToNext())
                return Del(vnosi, skupaj, P.naslednji(stran.odmik, pregledanih, skupaj), vnosi.length() > 0)
            }
            var ujemajocih = 0
            var vOknu = 0
            while (k.moveToNext()) {
                if (!P.vObdobju(P.datumNastanka(s.dolg(k, s.posneto), s.dolg(k, s.spremenjeno) * 1000L), obdobje)) continue
                if (ujemajocih >= stran.odmik && vOknu < stran.meja) {
                    vOknu++
                    vnosMedija(k, s, zbirka)?.let { vnosi.put(it) }
                }
                ujemajocih++
            }
            return Del(vnosi, ujemajocih, P.naslednji(stran.odmik, vOknu, ujemajocih), vnosi.length() > 0)
        }
    }

    private class Stolpci(k: Cursor) {
        val id = k.getColumnIndex(MediaStore.MediaColumns._ID)
        val ime = k.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val velikost = k.getColumnIndex(MediaStore.MediaColumns.SIZE)
        val mime = k.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
        val spremenjeno = k.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
        val vrsta = k.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val trajanje = k.getColumnIndex("duration")
        val posneto = k.getColumnIndex("datetaken")
        val sirina = k.getColumnIndex("width")
        val visina = k.getColumnIndex("height")
        val zasuk = k.getColumnIndex("orientation")
        val album = k.getColumnIndex("bucket_display_name")
        val pot = k.getColumnIndex("relative_path")

        fun dolg(k: Cursor, i: Int): Long = if (i >= 0 && !k.isNull(i)) k.getLong(i) else 0L
        fun niz(k: Cursor, i: Int): String? = if (i >= 0 && !k.isNull(i)) k.getString(i) else null
    }

    /** Vrstica MediaStore -> vnos seznama; null za vrstico brez imena ali (Files) za kar ni slika ali video. */
    private fun vnosMedija(k: Cursor, s: Stolpci, zbirka: String): JSONObject? {
        val ime = s.niz(k, s.ime) ?: return null
        val mime = s.niz(k, s.mime).orEmpty()
        val z = if (zbirka.isNotEmpty()) zbirka else when (s.dolg(k, s.vrsta).toInt()) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> "image"
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> "video"
            else -> return null
        }
        val vrsta = if (z != "shramba") z else P.vrsta(mime)
        val j = JSONObject().put("id", "$PREDPONA$z:${s.dolg(k, s.id)}").put("name", ime)
            .put("type", vrsta).put("size", s.dolg(k, s.velikost)).put("mime", mime)
            .put("modified", s.dolg(k, s.spremenjeno) * 1000L)
            .put("duration_ms", s.dolg(k, s.trajanje))
        s.dolg(k, s.posneto).takeIf { it > 0L }?.let { j.put("date_taken", it) }
        s.dolg(k, s.sirina).takeIf { it > 0L }?.let { j.put("width", it) }
        s.dolg(k, s.visina).takeIf { it > 0L }?.let { j.put("height", it) }
        if (s.zasuk >= 0 && !k.isNull(s.zasuk) && vrsta == "image") j.put("orientation", s.dolg(k, s.zasuk))
        s.niz(k, s.album)?.let { j.put("bucket", it) }
        // Pot samo za prikaz, brez /storage/emulated/0 (pot telefona ne gre ven).
        s.niz(k, s.pot)?.let { j.put("relative_path", it) }
        return j
    }

    /** Albumi: mape s slikami ali videi (BUCKET_ID), od najnovejse; oznaka je stevilka, nikoli pot. */
    private fun albumi(context: Context, stran: P.Stran, obdobje: P.Obdobje?): Del {
        val z = P.ZbiralnikAlbumov()
        val uri = datotekeUri()
        val stolpci = arrayOf("bucket_id", "bucket_display_name", "datetaken", MediaStore.MediaColumns.DATE_MODIFIED) +
            (if (Build.VERSION.SDK_INT >= 29) arrayOf("relative_path") else emptyArray<String>())
        // Mapa brez imena (slike in videi v korenu pomnilnika) dobi ime »Notranji pomnilnik«, ne stevilke BUCKET_ID.
        val korenIme = context.getString(R.string.link_datoteke_koren)
        var vKodi = false
        val k = poizvedi(context, uri, stolpci, P.zdruzi(P.IZBOR_SLIK_VIDEOV, P.izborObdobja(obdobje)), null, P.IZRAZ_DATUMA + " DESC")
            ?: run { vKodi = obdobje != null; poizvedi(context, uri, stolpci, P.IZBOR_SLIK_VIDEOV, null, "date_modified DESC") }
            ?: return PRAZNO
        k.use {
            val s = Stolpci(k)
            val iId = k.getColumnIndex("bucket_id")
            while (k.moveToNext()) {
                if (iId < 0 || k.isNull(iId)) continue
                val datum = P.datumNastanka(s.dolg(k, s.posneto), s.dolg(k, s.spremenjeno) * 1000L)
                if (vKodi && !P.vObdobju(datum, obdobje)) continue
                z.dodaj(k.getLong(iId), P.imeAlbuma(s.niz(k, s.album), s.niz(k, s.pot), korenIme), datum)
            }
        }
        val vsi = z.seznam()
        val okno = vsi.drop(stran.odmik).take(stran.meja)
        val v = JSONArray()
        for (a in okno) v.put(JSONObject().put("id", P.ALBUM + a.id).put("name", a.ime).put("type", "folder")
            .put("count", a.stevilo).put("modified", a.najnovejse))
        return Del(v, vsi.size, P.naslednji(stran.odmik, okno.size, vsi.size), false)
    }

    // ------------------------------------------------------------------ vse datoteke (fs:)

    /** Kanonicni koren notranjega pomnilnika (/storage/emulated/0). */
    private fun korenFs(): File? = try { Environment.getExternalStorageDirectory().canonicalFile } catch (_: Throwable) { null }

    /**
     * Oznaka `fs:<pot>` -> datoteka ali mapa pod notranjim pomnilnikom; null, ce pot ni dovoljena ali po razresitvi
     * simbolnih povezav ne ostane pod korenom (LinkDatotekePravila.potFs, relativnaZnotraj).
     */
    private fun datotekaFs(oznaka: String): File? {
        val rel = P.potFs(oznaka) ?: return null
        val koren = korenFs() ?: return null
        val f = if (rel.isEmpty()) koren else File(koren, rel)
        val kanonicna = try { f.canonicalPath } catch (_: Throwable) { return null }
        P.relativnaZnotraj(koren.path, kanonicna) ?: return null
        return File(kanonicna)
    }

    private class SeznamMape(val spremenjena: Long, val narejen: Long, val vnosi: List<P.Vnos>)
    /** Zadnje brane mape: listanje velike mape (npr. 18 000 slik v DCIM/Camera) po delih ne bere mape vsakic znova. */
    private val mape = object : LinkedHashMap<String, SeznamMape>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SeznamMape>?): Boolean = size > 4
    }
    private const val MAPA_VELJA_MS = 60_000L

    /**
     * Vnosi mape brez simbolnih povezav; datum posnetka iz MediaStore, kjer ga ima ([posneti] - poizvedba samo, ko mape ni
     * v predpomnilniku; pregled link-datoteke, tocka 5).
     */
    private fun beriMapo(mapa: File, posneti: () -> Map<String, Long>): List<P.Vnos> {
        val kljuc = mapa.path
        val spremenjena = mapa.lastModified()
        synchronized(mape) {
            mape[kljuc]?.takeIf { it.spremenjena == spremenjena && zdaj() - it.narejen < MAPA_VELJA_MS }?.let { return it.vnosi }
        }
        val vnosi = ArrayList<P.Vnos>()
        val imena = mapa.list() ?: emptyArray()
        val datumi = posneti()
        for (ime in imena) {
            if (!P.dovoljenDel(ime)) continue
            val a = try {
                java.nio.file.Files.readAttributes(File(mapa, ime).toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)
            } catch (_: Throwable) { continue }
            if (a.isSymbolicLink || !(a.isDirectory || a.isRegularFile)) continue
            vnosi.add(P.Vnos(ime, a.isDirectory, if (a.isDirectory) 0L else a.size(), a.lastModifiedTime().toMillis(), datumi[ime] ?: 0L))
        }
        synchronized(mape) { mape[kljuc] = SeznamMape(spremenjena, zdaj(), vnosi) }
        return vnosi
    }

    /** Datum posnetka slik in videov v mapi [rel] iz MediaStore (ime -> ms); prazno, ce ga ni. */
    private fun posnetiVMapi(context: Context, rel: String): Map<String, Long> {
        if (rel.isEmpty() || Build.VERSION.SDK_INT < 29) return emptyMap()
        val k = poizvedi(context, datotekeUri(), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, "datetaken"),
            "relative_path = ? AND datetaken > 0", arrayOf("$rel/"), MediaStore.MediaColumns._ID) ?: return emptyMap()
        val izid = HashMap<String, Long>()
        k.use { while (it.moveToNext()) { val ime = it.getString(0) ?: continue; izid[ime] = it.getLong(1) } }
        return izid
    }

    private fun mimeIzImena(ime: String): String {
        val koncnica = ime.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(koncnica) ?: "application/octet-stream"
    }

    /** Mapa notranjega pomnilnika: mape po imenu, nato datoteke od najnovejse (datum posnetka, ce ga ima). Samo branje. */
    private fun mapaFs(context: Context, rel: String, stran: P.Stran, obdobje: P.Obdobje?, idNaprave: String): Del {
        if (!smeVseDatoteke(context, idNaprave)) return PRAZNO
        val mapa = datotekaFs(P.oznakaFs(rel))?.takeIf { it.isDirectory } ?: return PRAZNO
        val urejeni = P.uredi(rel, beriMapo(mapa) { posnetiVMapi(context, rel) }, obdobje)
        val okno = urejeni.drop(stran.odmik).take(stran.meja)
        val v = JSONArray()
        var datotek = false
        for (n in okno) {
            val j = JSONObject().put("id", P.oznakaFs(P.podpot(rel, n.ime))).put("name", n.ime).put("modified", n.spremenjeno)
            if (n.mapa) j.put("type", "folder")
            else {
                val mime = mimeIzImena(n.ime)
                j.put("type", P.vrsta(mime)).put("mime", mime).put("size", n.velikost)
                if (n.posneto > 0L) j.put("date_taken", n.posneto)
                datotek = true
            }
            v.put(j)
        }
        return Del(v, urejeni.size, P.naslednji(stran.odmik, okno.size, urejeni.size), datotek)
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

    private fun imeZbirke(context: Context, zbirka: String): String = context.getString(when (zbirka) {
        "audio" -> R.string.os_krajevno_glasba
        "image" -> R.string.os_krajevno_slike
        "shramba" -> R.string.os_krajevno_shramba
        else -> R.string.os_krajevno_videi
    })

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

    // ------------------------------------------------------------------ prosnja za dostop

    /**
     * `files.request_access` z druge naprave (racunalnik v ozjem krogu z zmoznostjo datotek): na tem telefonu pokaze
     * obvestilo; sistemsko okno (stran »vse datoteke«, ce je ni, izbor medijev) se odpre sele ob dotiku uporabnika.
     * Telefon nikoli sam ne odpre sistemskega okna. Ponudi najboljse, kar naprava zmore (LinkDatotekePravila.prosnja);
     * parameter `all` starejsih odjemalcev ni vec potreben.
     */
    fun prosiZaDostop(context: Context, idNaprave: String): JSONObject {
        val d = dostop(context)
        val vd = vseDatoteke(context)
        val o = JSONObject().put("access", d.oznaka).put("all_files", vd)
        if (!vklopljeno(context)) return o.put("shown", false).put("reason", "off")
        // Vse datoteke so ze odobrene, ta naprava pa zanje se nima privolitve (pregled link-datoteke, 2): uporabnik jo da z
        // dotikom obvestila, ki pove, katera naprava prosi.
        val sme = smeVseDatoteke(context, idNaprave)
        o.put("all_files_allowed", sme)
        val prosnja = if (vd && !sme && vseMogoce(context)) P.Prosnja.VSE_DATOTEKE else P.prosnja(d, vd, vseMogoce(context))
        if (prosnja == P.Prosnja.NIC) return o.put("shown", false)
        val privzeto = try { si.safeer.tv.cast.KrogNaprave.krog(context).clanZaId(idNaprave)?.ime } catch (_: Throwable) { null }
        val ime = LinkVzdevki.ime(context, idNaprave, privzeto?.takeIf { it.isNotBlank() } ?: context.getString(R.string.link_datoteke_naprava))
        val prikazano = DostopDatotekActivity.obvesti(context, ime, prosnja, idNaprave)
        return if (prikazano) o.put("shown", true) else o.put("shown", false).put("reason", "notifications")
    }

    /**
     * Privolitev v Linku na TEJ napravi (lastnik 25. 10. 2026 10:49: naprave iz ozjega kroga dobijo dostop z eno
     * privolitvijo, brez locenih stikal): druga naprava je na novo dobila datoteke (Nastavitve -> Ozji krog zaupanja ali
     * Safeer Link -> naprava -> Dostop). Deljenje se vklopi in, ce Android za to se nima dovoljenj, se takoj odpre
     * njegovo okno - vse datoteke (telefon, tablica), ce jih uporabnik ne odobri, mediji. Klice se samo ob dotiku
     * uporabnika (Shrani). Vrne true, ce se je odprlo okno dovoljenja.
     */
    fun poPrivolitvi(a: android.app.Activity): Boolean {
        if (!vklopljeno(a)) nastavi(a, true)
        return DostopDatotekActivity.zacni(a, P.prosnja(dostop(a), vseDatoteke(a), vseMogoce(a)))
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

    /** Naprava, ki ji je bil izdan zivi zeton [z] (dostop preveri [zetonVelja]); null, ce takega zetona ni. */
    private fun napravaZetona(z: String?, zdaj: Long = zdaj()): String? {
        if (z.isNullOrBlank()) return null
        val zb = z.toByteArray()
        return zetoni.entries.firstOrNull { it.value.zivi(zdaj) && MessageDigest.isEqual(it.value.vrednost.toByteArray(), zb) }
            ?.key?.substringBefore('#')
    }

    private fun umaknjena(ctx: Context, idNaprave: String): Boolean =
        P.umaknjenaVarno { si.safeer.tv.cast.KrogNaprave.krog(ctx).jeUmaknjen(idNaprave) }

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
            postreziZahtevo(deli[0], cilj.substringBefore('?'), zeton, glave, vhod, izhod, poizvedba)
        } catch (e: Throwable) {
            Log.i(TAG, "Zahteva: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        } finally {
            try { s.close() } catch (_: Throwable) { }
        }
    }

    /**
     * Iste poti prek Huba (`/cast/d/<id>`, `/cast/thumb/<id>`, `/cast/live/<id>`, `/cast/magnet/<skrivnost>`): po
     * Global Linku rele pripelje samo povezavo do vrat Huba. Zeton samo v glavi (naslov prek releja ne nosi
     * skrivnosti). Vrne false, ce pot ni za streznik datotek. [poizvedba] = parametri naslova (velikost slicice `s`).
     */
    fun jePotPrekHuba(pot: String): Boolean =
        pot.startsWith("/cast/d/") || pot.startsWith("/cast/thumb/") || pot.startsWith("/cast/live/") || pot.startsWith("/cast/magnet/")

    fun prekHuba(metoda: String, pot: String, glave: Map<String, String>, vhod: InputStream, izhod: OutputStream,
                 poizvedba: Map<String, String> = emptyMap()): Boolean {
        if (!jePotPrekHuba(pot)) return false
        try {
            val s = poizvedba["s"]?.let { "s=$it" }.orEmpty()
            postreziZahtevo(metoda, pot.removePrefix("/cast"), glave["x-safeer-token"], glave, vhod, izhod, s)
        } catch (e: Throwable) {
            Log.i(TAG, "Zahteva prek Huba: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        }
        return true
    }

    private fun postreziZahtevo(metoda: String, pot: String, zeton: String?, glave: Map<String, String>,
                                vhod: InputStream, izhod: OutputStream, poizvedba: String = "") {
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
            val palec = pot.startsWith("/thumb/")
            val oznaka = try { URLDecoder.decode(pot.substring(if (palec) 7 else 3), "UTF-8") } catch (_: Throwable) { null }
            // Urejanje (POST) samo z odprtimi datotekami - izrecno poslana datoteka je samo za branje.
            if (!zetonVelja(zeton, oznaka = if (metoda == "POST") null else oznaka)) { napaka(izhod, 401, "manjka ali napacen zeton"); return }
            val ctx = appContext ?: run { napaka(izhod, 503, "ni pripravljeno"); return }
            if (oznaka == null) { napaka(izhod, 404, "datoteke ni"); return }
            if (oznaka.startsWith(P.FS)) {
                // Vse datoteke: samo, dokler jih uporabnik deli (stikalo in dostop do vseh datotek), samo napravi s
                // privolitvijo za vse datoteke (pregled link-datoteke, 2), samo branje in brez lokacije posnetkov (1).
                val f = if (smeVseDatoteke(ctx, napravaZetona(zeton).orEmpty())) datotekaFs(oznaka)?.takeIf { it.isFile } else null
                if (f == null) { napaka(izhod, 404, "datoteke ni"); return }
                if (metoda == "POST") { napaka(izhod, 405, "samo branje"); return }
                if (palec) { posljiSlicico(ctx, oznaka, null, f, metoda, glave, poizvedba, izhod); return }
                posljiFsBrezLokacije(ctx, f, metoda, glave, izhod)
                return
            }
            val uri = uriIz(oznaka) ?: run { napaka(izhod, 404, "datoteke ni"); return }
            if (palec) { posljiSlicico(ctx, oznaka, uri, null, metoda, glave, poizvedba, izhod); return }
            if (metoda == "POST") { uredi(ctx, uri, glave, vhod, izhod); return }
            val vrsta = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            // Android 9 lokacije v MediaStore ne zakrije: JPEG kot kopija brez lokacije (specifikacija 3.7).
            if (P.potVsebineMedija(vrsta, Build.VERSION.SDK_INT) == P.PotVsebine.KOPIJA_BREZ_LOKACIJE) {
                posljiKopijo(ctx, { ctx.contentResolver.openInputStream(uri) }, vrsta, metoda, glave, izhod)
                return
            }
            val opis = try { ctx.contentResolver.openAssetFileDescriptor(uri, "r") } catch (_: Throwable) { null }
                ?: run { napaka(izhod, 404, "datoteke ni"); return }
            posljiVsebino(opis, vrsta, metoda, glave, izhod)
        }
    }

    /**
     * Vsebina datoteke z delnimi zahtevami (Range). `nosniff` in `sandbox`: datoteka (npr. HTML iz Prenosov), odprta v
     * brskalniku na drugi napravi, ne tece kot stran na izvoru tega streznika in ne bere zetona iz naslova.
     */
    private fun posljiVsebino(opis: AssetFileDescriptor, vrsta: String, metoda: String, glave: Map<String, String>, izhod: OutputStream) {
        opis.use { o ->
            val velikost = o.length
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
            sb.append("X-Content-Type-Options: nosniff\r\nContent-Security-Policy: sandbox\r\n")
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

    private fun preskoci(vir: InputStream, koliko: Long) {
        var ostane = koliko
        while (ostane > 0) {
            val n = vir.skip(ostane)
            if (n <= 0) { if (vir.read() < 0) return; ostane-- } else ostane -= n
        }
    }

    // ------------------------------------------------------------------ slicice

    /** Najvec dve hkratni dekodiranji slicic (pomnilnik telefona); ostale cakajo do 30 s, nato 503. */
    /**
     * Vsebina datoteke iz `fs:` brez lokacije posnetka (specifikacija 3.7; pregled link-datoteke, tocka 1): vrsta po prvih
     * bajtih (ime lahko lazje), slika ali video prek MediaStore (Android 10+ lokacijo zakrije sam), sicer kopija brez EXIF
     * GPS in XMP; kar ne gre, ne posljemo (403).
     */
    private fun posljiFsBrezLokacije(ctx: Context, f: File, metoda: String, glave: Map<String, String>, izhod: OutputStream) {
        val glava = ByteArray(16)
        val n = try { f.inputStream().use { it.read(glava) } } catch (_: Throwable) { -1 }
        val mime = (if (n > 0) P.vrstaPoGlavi(glava.copyOf(n)) else null) ?: mimeIzImena(f.name)
        val uri = if (P.nosiLokacijo(mime) && Build.VERSION.SDK_INT >= 29) uriMedija(ctx, f, mime) else null
        when (P.potVsebineFs(mime, Build.VERSION.SDK_INT, uri != null)) {
            P.PotVsebine.NEPOSREDNO -> {
                val opis = try { AssetFileDescriptor(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY), 0, f.length()) }
                    catch (_: Throwable) { null } ?: run { napaka(izhod, 404, "datoteke ni"); return }
                posljiVsebino(opis, mimeIzImena(f.name), metoda, glave, izhod)
            }
            P.PotVsebine.MEDIASTORE -> {
                val opis = try { uri?.let { ctx.contentResolver.openAssetFileDescriptor(it, "r") } } catch (_: Throwable) { null }
                    ?: run { napaka(izhod, 404, "datoteke ni"); return }
                posljiVsebino(opis, mime, metoda, glave, izhod)
            }
            P.PotVsebine.KOPIJA_BREZ_LOKACIJE -> posljiKopijo(ctx, { f.inputStream() }, mime, metoda, glave, izhod)
            P.PotVsebine.ZAVRNI -> napaka(izhod, 403, "lokacije posnetka ni mogoce odstraniti")
        }
    }

    /** MediaStore naslov slike ali videa [f] (Android 10+), ce ga MediaStore pozna (po poti); sicer null. */
    private fun uriMedija(ctx: Context, f: File, mime: String): Uri? {
        val zbirka = if (mime.startsWith("video/")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        @Suppress("DEPRECATION")
        val k = poizvedi(ctx, zbirka, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DATA} = ?",
            arrayOf(f.path), MediaStore.MediaColumns._ID) ?: return null
        k.use { if (it.moveToFirst()) return ContentUris.withAppendedId(zbirka, it.getLong(0)) }
        return null
    }

    /** Poslje kopijo vsebine brez lokacije ([kopijaBrezLokacije]); ce je ni mogoce narediti, 403. Kopija se izbrise. */
    private fun posljiKopijo(ctx: Context, vir: () -> InputStream?, mime: String, metoda: String, glave: Map<String, String>,
                             izhod: OutputStream) {
        val kopija = kopijaBrezLokacije(ctx, vir, mime) ?: run { napaka(izhod, 403, "lokacije posnetka ni mogoce odstraniti"); return }
        try {
            val opis = try { AssetFileDescriptor(ParcelFileDescriptor.open(kopija, ParcelFileDescriptor.MODE_READ_ONLY), 0, kopija.length()) }
                catch (_: Throwable) { null } ?: run { napaka(izhod, 404, "datoteke ni"); return }
            posljiVsebino(opis, mime, metoda, glave, izhod)
        } finally { kopija.delete() }
    }

    /**
     * Kopija slike v predpomnilniku aplikacije brez EXIF GPS in XMP; null, ce je prevelika ([P.NAJVEC_KOPIJE]), ce zapis
     * ne uspe ali ce ima kopija po zapisu se vedno lokacijo (preverba na novo prebrane kopije).
     * Pregled 99, F1 in V1 (26. 10. 2026): kopijo in odstranitev XMP naredi [P.pripraviKopijo] (prej predpona "k" -
     * createTempFile je vedno vrgel izjemo), ExifInterface nato odstrani EXIF GPS, na koncu se preveri se XMP.
     */
    private fun kopijaBrezLokacije(ctx: Context, vir: () -> InputStream?, mime: String): File? {
        val mapa = File(ctx.cacheDir, "link-brez-lokacije").apply { mkdirs() }
        val t = P.pripraviKopijo(mapa, vir, mime) ?: return null
        try {
            val e = android.media.ExifInterface(t.path)
            for (z in P.ZNACKE_LOKACIJE) e.setAttribute(z, null)
            e.saveAttributes()
            val p = android.media.ExifInterface(t.path)
            if (p.getLatLong(FloatArray(2)) || P.ZNACKE_LOKACIJE.any { p.getAttribute(it) != null } || P.vsebujeXmp(t)) {
                t.delete(); return null
            }
            return t
        } catch (_: Throwable) { t.delete(); return null }
    }

    private val dekodiranja = java.util.concurrent.Semaphore(2)
    private val slicice = P.PredpomnilnikSlicic(6 * 1024 * 1024)

    /** Vrsta in cas spremembe (ms) datoteke iz zbirke - za ETag. */
    private fun podatkiMedija(ctx: Context, uri: Uri): Pair<String, Long> {
        var mime = ""
        var spremenjeno = 0L
        try {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use { k ->
                if (k.moveToFirst()) { mime = k.getString(0).orEmpty(); spremenjeno = k.getLong(1) * 1000L }
            }
        } catch (_: Throwable) { }
        if (mime.isEmpty()) mime = try { ctx.contentResolver.getType(uri).orEmpty() } catch (_: Throwable) { "" }
        return mime to spremenjeno
    }

    /**
     * Majhna JPEG slicica za druge Safeer naprave (`?s=` rob 128-512, privzeto 256); polne fotografije nikoli ne
     * dekodiramo. ETag (oznaka, sprememba, rob) in If-None-Match -> 304; ista slicica iz predpomnilnika.
     */
    private fun posljiSlicico(ctx: Context, oznaka: String, uri: Uri?, datoteka: File?, metoda: String,
                              glave: Map<String, String>, poizvedba: String, izhod: OutputStream) {
        val rob = P.rob(P.parameter(poizvedba, "s"))
        val (mime, spremenjeno) = if (datoteka != null) mimeIzImena(datoteka.name) to datoteka.lastModified()
            else podatkiMedija(ctx, uri ?: return napaka(izhod, 404, "slicice ni"))
        val etag = P.etag(oznaka, spremenjeno, rob)
        if (P.ujemaSe(glave["if-none-match"], etag)) {
            izhod.write("HTTP/1.1 304 Not Modified\r\nETag: $etag\r\nCache-Control: private, max-age=3600\r\nConnection: close\r\n\r\n".toByteArray())
            izhod.flush()
            return
        }
        val kljuc = P.kljucSlicice(oznaka, spremenjeno, rob)
        val telo = slicice.dobi(kljuc) ?: run {
            if (!dekodiranja.tryAcquire(30, java.util.concurrent.TimeUnit.SECONDS)) { napaka(izhod, 503, "zasedeno"); return }
            val narejena = try { naredi(ctx, uri, datoteka, mime, rob) } finally { dekodiranja.release() }
            narejena?.also { slicice.shrani(kljuc, it) }
        } ?: run { napaka(izhod, 404, "slicice ni"); return }
        izhod.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${telo.size}\r\nETag: $etag\r\n" +
            "Cache-Control: private, max-age=3600\r\nConnection: close\r\n\r\n").toByteArray())
        if (metoda != "HEAD") izhod.write(telo)
        izhod.flush()
    }

    private fun naredi(ctx: Context, uri: Uri?, datoteka: File?, mime: String, rob: Int): ByteArray? {
        val video = mime.startsWith("video/")
        val bitmap = try {
            if (datoteka != null) slicicaDatoteke(datoteka, video, mime.startsWith("image/"), rob)
            else if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(uri!!, Size(rob, rob), null)
            else {
                val id = uri?.lastPathSegment?.toLongOrNull()
                @Suppress("DEPRECATION")
                if (id != null && video) MediaStore.Video.Thumbnails.getThumbnail(ctx.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                else if (id != null) MediaStore.Images.Thumbnails.getThumbnail(ctx.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
                else null
            }
        } catch (_: Throwable) { null } ?: return null
        // Nekatere naprave vrnejo manjso slicico, kot je zahtevana (WP28 S: 320 px na 512): sliko dekodiramo z zmanjsanjem.
        val vecja = if (datoteka == null && uri != null && mime.startsWith("image/") && P.premajhna(maxOf(bitmap.width, bitmap.height), rob))
            vecjaSlika(ctx, uri, mime, rob, maxOf(bitmap.width, bitmap.height)) else null
        if (vecja != null) bitmap.recycle()
        val izvor = vecja ?: bitmap
        val omejena = omeji(izvor, rob)
        try {
            var telo = ByteArray(0)
            for (q in P.KAKOVOSTI) {
                val b = ByteArrayOutputStream()
                omejena.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, b)
                telo = b.toByteArray()
                if (telo.size <= P.NAJVEC_SLICICE) break
            }
            return telo
        } finally {
            if (omejena !== izvor) omejena.recycle()
            izvor.recycle()
        }
    }

    /**
     * Slika iz MediaStore, dekodirana na daljsi rob [rob] (ImageDecoder z vzorcenjem, upostevan zasuk EXIF); null, ce ni
     * vecja od [imamo] ali je ni varno dekodirati (LinkDatotekePravila.smeDekodirati - velik PNG).
     */
    private fun vecjaSlika(ctx: Context, uri: Uri, mime: String, rob: Int, imamo: Int): android.graphics.Bitmap? = try {
        val b = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            val w = info.size.width
            val h = info.size.height
            if (!P.smeDekodirati(info.mimeType ?: mime, w, h)) throw IllegalStateException("prevelika za dekodiranje")
            val m = rob.toFloat() / maxOf(w, h)
            if (m < 1f) d.setTargetSize(maxOf(1, (w * m).toInt()), maxOf(1, (h * m).toInt()))
            d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
        if (maxOf(b.width, b.height) > imamo) b else { b.recycle(); null }
    } catch (_: Throwable) { null }

    /** Slicica datoteke iz mape (ni v MediaStore ali je ne iscemo): samo slike in videi. */
    private fun slicicaDatoteke(f: File, video: Boolean, slika: Boolean, rob: Int): android.graphics.Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            return when {
                video -> android.media.ThumbnailUtils.createVideoThumbnail(f, Size(rob, rob), null)
                slika -> android.media.ThumbnailUtils.createImageThumbnail(f, Size(rob, rob), null)
                else -> null
            }
        }
        @Suppress("DEPRECATION")
        if (video) return android.media.ThumbnailUtils.createVideoThumbnail(f.path, MediaStore.Images.Thumbnails.MINI_KIND)
        if (!slika) return null
        val meje = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, meje)
        var vzorec = 1
        while (maxOf(meje.outWidth, meje.outHeight) / (vzorec * 2) >= rob) vzorec *= 2
        return android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = vzorec })
    }

    /** Daljsi rob najvec [rob] px (nekatere naprave vrnejo vecjo slicico, kot je zahtevana). */
    private fun omeji(b: android.graphics.Bitmap, rob: Int): android.graphics.Bitmap {
        val vecji = maxOf(b.width, b.height)
        if (vecji <= rob || vecji <= 0) return b
        val m = rob.toFloat() / vecji
        return android.graphics.Bitmap.createScaledBitmap(b, maxOf(1, (b.width * m).toInt()), maxOf(1, (b.height * m).toInt()), true)
    }

    private fun napaka(izhod: OutputStream, koda: Int, besedilo: String) {
        val telo = JSONObject().put("napaka", besedilo).toString().toByteArray()
        val beseda = when (koda) { 401 -> "Unauthorized"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 503 -> "Service Unavailable"; else -> "Error" }
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
