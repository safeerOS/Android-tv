package si.safeer.tv.os

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.MimeTypeMap
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import si.safeer.tv.R
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Teamwork na Androidu (lastnik, 14. 10. 2026): datoteko z racunalnika, ki jo morda ureja tudi kdo drug, ta naprava
 * uredi v programu, ki ta format podpira. Ce takega programa ni, jo odpre racunalnik in tu se pokaze njegov zaslon
 * (DatotekeActivity.odpriNaRacunalniku) - tako ali tako brez napake.
 *
 * Potek: prenos cele datoteke v predpomnilnik aplikacije, urejevalnik dobi FileProvider URI z dovoljenjem za pisanje;
 * dokler je urejevalnik odprt, ta naprava lastniku na 5 s javlja `files.session` (odprto). Vsaka shranitev (datoteka se
 * spremeni in miruje 1,5 s) gre lastniku kot popravek »celota« (TeamworkPravila). Ko se uporabnik vrne v Safeer
 * (onResume), je urejevalnik zaprt: javimo zaprto in se dve minuti spremljamo pozne shranitve.
 */
object TeamworkAndroid {
    private const val TAG = "SafeerTeamwork"
    private const val KANAL = "safeer_teamwork"
    private const val PING_ODPRTO_MS = 5_000L
    private const val PING_ZAPRTO_MS = 30_000L
    private const val MIRUJE_MS = 1_500L
    private const val PO_ZAPRTJU_MS = 120_000L

    private class Seja(val naprava: String, val imeNaprave: String, val id: String, var s: DatotekeActivity.Streznik,
                       val datoteka: File, var sha: String) {
        var osnova = sha
        var odprto = true
        var teamwork = false
        var poslanoStanje = datoteka.lastModified() to datoteka.length()
        var spremembaOd = 0L
        var spremembaStanje: Pair<Long, Long>? = null
        var naslednjiPing = 0L
        var konecOb = Long.MAX_VALUE
    }

    private val seje = ConcurrentHashMap<String, Seja>()
    private val ozadje = Executors.newSingleThreadScheduledExecutor()
    private val glavna = Handler(Looper.getMainLooper())
    @Volatile private var tece = false

    fun mime(ime: String, mime: String): String =
        mime.ifBlank { MimeTypeMap.getSingleton().getMimeTypeFromExtension(ime.substringAfterLast('.', "").lowercase()).orEmpty() }

    /**
     * Ali je na tej napravi program, ki ta format UREJA (ACTION_EDIT; brez Safeerja samega). Program, ki datoteko samo
     * pokaze, ni dovolj: urejas je ne (na WP28 S 14. 10. je Files by Google .txt samo prikazal) - takrat jo uredis na
     * racunalniku prek njegovega zaslona.
     */
    fun imaProgram(c: Context, ime: String, mime: String): Boolean {
        val m = mime(ime, mime).ifBlank { return false }
        return try {
            c.packageManager.queryIntentActivities(Intent(Intent.ACTION_EDIT).setType(m), 0).any { it.activityInfo?.packageName != c.packageName }
        } catch (_: Throwable) { false }
    }

    /**
     * Prenese datoteko in jo odpre v urejevalniku te naprave. [naNapako] se poklice, ce prenos ali odpiranje ne uspe
     * (klicatelj takrat datoteko odpre na racunalniku in pokaze njegov zaslon).
     */
    fun uredi(a: Activity, s: DatotekeActivity.Streznik, naprava: String, imeNaprave: String, v: DatotekeActivity.Vnos,
              naNapako: () -> Unit) {
        val c = a.applicationContext
        val mapa = File(c.cacheDir, "teamwork/" + TeamworkPravila.varnoIme(naprava)).apply { mkdirs() }
        val cilj = File(mapa, TeamworkPravila.varnoIme(v.ime))
        ozadje.execute {
            val ok = try { prenesi(s, v.id, cilj) } catch (t: Throwable) { Log.w(TAG, "prenos: ${t.javaClass.simpleName}"); false }
            if (!ok) { glavna.post(naNapako); return@execute }
            val seja = Seja(naprava, imeNaprave, v.id, s, cilj, TeamworkPravila.sha256(cilj))
            glavna.post {
                // Racunalnik mora Teamwork znati (files.session), sicer bi shranitev ostala samo tu: takrat ga odpre on.
                val p = JSONObject().put("id", v.id).put("odprto", true).put("program", "android").put("osnova", seja.sha)
                LinkUpravitelj.pridobi(c).ukaz(naprava, "files.session", p, 10_000, LinkOdjemalec.Odgovor { izid, _ ->
                    val d = izid?.optJSONObject("data")
                    if (izid?.optBoolean("ok") != true || d == null || !d.optBoolean("ok", true)) { naNapako(); return@Odgovor }
                    if (!odpriUrejevalnik(a, cilj, mime(v.ime, v.mime))) { naNapako(); return@Odgovor }
                    seje[kljuc(naprava, v.id)] = seja
                    seja.naslednjiPing = System.currentTimeMillis() + PING_ODPRTO_MS
                    zazeni(c)
                    ozadje.execute { obdelaj(c, seja, d) }
                })
            }
        }
    }

    /** Uporabnik se je vrnil v Safeer: urejevalniki so zaprti (shranitve se dve minuti se spremljamo). */
    fun vrnjen(c: Context) {
        val zdaj = System.currentTimeMillis()
        for (seja in seje.values) if (seja.odprto) {
            seja.odprto = false
            seja.konecOb = zdaj + PO_ZAPRTJU_MS
            ozadje.execute { ping(c.applicationContext, seja) }
        }
    }

    private fun kljuc(naprava: String, id: String) = naprava + "\n" + id

    private fun prenesi(s: DatotekeActivity.Streznik, id: String, cilj: File): Boolean {
        val k = PripetiVir.odjemalecZaStreznik(s.odtis).newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        val naslov = PripetiVir.naslovZaUrejanje(s.naprava, s.url(id), s.hub >= 2)
        k.newCall(Request.Builder().url(naslov).header("X-Safeer-Token", s.zeton).build()).execute().use { o ->
            if (!o.isSuccessful) return false
            val telo = o.body ?: return false
            val zacasna = File(cilj.parentFile, "." + cilj.name + ".part")
            zacasna.outputStream().use { izhod -> telo.byteStream().copyTo(izhod) }
            return zacasna.renameTo(cilj)
        }
    }

    private fun odpriUrejevalnik(a: Activity, f: File, mime: String): Boolean {
        val uri = try {
            androidx.core.content.FileProvider.getUriForFile(a, a.packageName + ".fileprovider", f)
        } catch (t: Throwable) { Log.w(TAG, "uri: ${t.javaClass.simpleName}"); return false }
        for (dejanje in listOf(Intent.ACTION_EDIT)) {
            val namera = Intent(dejanje).setDataAndType(uri, mime.ifBlank { "*/*" })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            // Izbirnik brez programov ne vrze napake, ampak pokaze »tega ne more nobena aplikacija« (WP28 S, 14. 10.):
            // odpremo samo, ce ga kak program res zna urejati.
            val programi = try {
                a.packageManager.queryIntentActivities(namera, 0).filter { it.activityInfo?.packageName != a.packageName }
            } catch (_: Throwable) { emptyList() }
            if (programi.isEmpty()) continue
            try {
                a.startActivity(Intent.createChooser(namera, f.name))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (t: Throwable) { Log.w(TAG, "urejevalnik: ${t.javaClass.simpleName}") }
        }
        return false
    }

    private fun zazeni(c: Context) {
        if (tece) return
        tece = true
        ozadje.scheduleWithFixedDelay({
            try { tik(c) } catch (t: Throwable) { Log.w(TAG, "tik: ${t.javaClass.simpleName}") }
        }, 2, 2, TimeUnit.SECONDS)
    }

    private fun tik(c: Context) {
        val zdaj = System.currentTimeMillis()
        for ((k, seja) in seje) {
            if (!seja.datoteka.exists() || zdaj > seja.konecOb) { seje.remove(k); continue }
            val stanje = seja.datoteka.lastModified() to seja.datoteka.length()
            if (stanje != seja.poslanoStanje) {
                if (stanje != seja.spremembaStanje) { seja.spremembaStanje = stanje; seja.spremembaOd = zdaj }
                else if (zdaj - seja.spremembaOd >= MIRUJE_MS) { seja.spremembaStanje = null; poslji(c, seja) }
            }
            if (zdaj >= seja.naslednjiPing) ping(c, seja)
        }
    }

    private fun ping(c: Context, seja: Seja) {
        seja.naslednjiPing = System.currentTimeMillis() + if (seja.odprto) PING_ODPRTO_MS else PING_ZAPRTO_MS
        val p = JSONObject().put("id", seja.id).put("odprto", seja.odprto).put("program", "android").put("osnova", seja.osnova)
        glavna.post {
            LinkUpravitelj.pridobi(c).ukaz(seja.naprava, "files.session", p, 10_000, LinkOdjemalec.Odgovor { izid, _ ->
                val d = izid?.optJSONObject("data") ?: return@Odgovor
                ozadje.execute { obdelaj(c, seja, d) }
            })
        }
    }

    private fun obdelaj(c: Context, seja: Seja, d: JSONObject) {
        d.optJSONObject("streznik")?.let { n ->
            seja.s = DatotekeActivity.Streznik(n.optString("base_url", seja.s.osnova), n.optString("fp", seja.s.odtis),
                n.optString("token", seja.s.zeton), seja.s.naprava, n.optInt("hub", seja.s.hub))
        }
        val lokalno = (seja.datoteka.lastModified() to seja.datoteka.length()) != seja.poslanoStanje
        val o = TeamworkPravila.odziv(d, seja.teamwork, seja.sha, seja.odprto, lokalno, seja.imeNaprave)
        seja.teamwork = o.teamwork
        if (o.novaRazlicica) {
            // Nova razlicica pri lastniku, tu nic neposlanega: urejevalnik jo dobi ob ponovnem odprtju.
            if (try { prenesi(seja.s, seja.id, seja.datoteka) } catch (_: Throwable) { false }) {
                seja.sha = o.sha
                if (!seja.odprto) seja.osnova = o.sha
                seja.poslanoStanje = seja.datoteka.lastModified() to seja.datoteka.length()
            }
        }
        if (o.obvestilo.isNotEmpty()) obvesti(c, o.obvestilo, seja.datoteka.name, o.drugi.joinToString(", ").ifBlank {
            TeamworkPravila.kratkoIme(seja.imeNaprave) }, "")
    }

    private fun poslji(c: Context, seja: Seja) {
        val f = seja.datoteka
        val sha = try { TeamworkPravila.sha256(f) } catch (_: Throwable) { return }
        val stanje = f.lastModified() to f.length()
        if (sha == seja.sha) { seja.poslanoStanje = stanje; return }
        val glava = TeamworkPravila.glavaCelote(f.length(), sha, seja.osnova)
        val telo = object : RequestBody() {
            override fun contentType(): MediaType = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = glava.size + f.length()
            override fun writeTo(sink: BufferedSink) {
                sink.write(glava)
                f.source().use { sink.writeAll(it) }
            }
        }
        val s = seja.s
        val url = TeamworkPravila.naslovPopravka(s.osnova, seja.id) { android.net.Uri.encode(it) }
        try {
            val k = PripetiVir.odjemalecZaStreznik(s.odtis).newBuilder().writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS).build()
            val z = Request.Builder().url(PripetiVir.naslovZaUrejanje(s.naprava, url, s.hub >= 2))
                .header("X-Safeer-Token", s.zeton).post(telo).build()
            k.newCall(z).execute().use { o ->
                val j = try { JSONObject(o.body?.string().orEmpty()) } catch (_: Throwable) { JSONObject() }
                if (!o.isSuccessful || !j.optBoolean("ok")) { Log.w(TAG, "popravek: http ${o.code} ${j.optString("koda")}"); return }
                if (j.optBoolean("spor")) {
                    obvesti(c, "spor", f.name, TeamworkPravila.kratkoIme(seja.imeNaprave), j.optString("kopija"))
                    // Tvoja razlicica je pri lastniku (kopija); glavna razlicica pride ob naslednjem javljanju.
                    seja.poslanoStanje = stanje
                    return
                }
                seja.sha = sha
                seja.osnova = sha
                seja.poslanoStanje = stanje
            }
        } catch (t: Throwable) {
            Log.w(TAG, "popravek: ${t.javaClass.simpleName}")         // naslednji tik poskusi znova
        }
    }

    private fun obvesti(c: Context, koda: String, ime: String, kdo: String, kopija: String) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 33 &&
                c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(KANAL) == null) {
                nm.createNotificationChannel(NotificationChannel(KANAL, c.getString(R.string.tw_kanal), NotificationManager.IMPORTANCE_DEFAULT))
            }
            val (naslov, besedilo) = when (koda) {
                "zacetek" -> c.getString(R.string.tw_zacetek_naslov) to c.getString(R.string.tw_zacetek, ime, kdo)
                "konec" -> c.getString(R.string.tw_konec_naslov) to c.getString(R.string.tw_konec, ime)
                "nova" -> c.getString(R.string.tw_nova_naslov) to c.getString(R.string.tw_nova, ime, kdo)
                else -> c.getString(R.string.tw_spor_naslov) to c.getString(R.string.tw_spor, ime, kdo, kopija)
            }
            @Suppress("DEPRECATION")
            val g = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, KANAL) else Notification.Builder(c)
            g.setSmallIcon(R.drawable.os_znak).setContentTitle(naslov).setContentText(besedilo)
                .setStyle(Notification.BigTextStyle().bigText(besedilo)).setAutoCancel(true)
            nm.notify(("tw" + ime).hashCode() and 0x7fff, g.build())
        } catch (t: Throwable) {
            Log.w(TAG, "obvestilo: ${t.javaClass.simpleName}")
        }
    }
}
