package si.safeer.tv.link

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Skupni prostor v Safeer Linku (zakon solidarnosti, korak 2): naprava z dovolj prostora shrani
 * datoteko druge naprave (racunalnika, ki mu zmanjkuje prostora).
 *
 * Racunalnik poslje `storage.put` (naslov datoteke na svojem strezniku, odtis potrdila, zeton, ime,
 * velikost, SHA-256). Naprava datoteko prenese sama - po HTTPS s pripetim potrdilom - v javno mapo
 * Prenosi/Safeer Shramba (ostane tudi, ce se aplikacijo odstrani), med prenosom racuna SHA-256 in jo
 * objavi sele, ko se ujema. Racunalnik napredek bere s `storage.status`; original na racunalniku izbrise
 * sele uporabnik, ko vidi, kje je kopija.
 */
object Shramba {
    private const val TAG = "SafeerShramba"
    const val MAPA = "Safeer Shramba"
    /** Ta naprava si vedno pusti vsaj toliko prostora zase. */
    private const val REZERVA = 2L * 1024 * 1024 * 1024

    class Opravilo(val id: String, val ime: String, val velikost: Long) {
        /** Zaporedna stevilka nastanka: iz seznama gre najprej najstarejse KONCANO opravilo (PrenosPravila.izrini). */
        val nastal = stevec.incrementAndGet()
        val koncano get() = stanje != "prenasam"
        @Volatile var stanje = "prenasam"     // prenasam | koncano | napaka
        @Volatile var preneseno = 0L
        @Volatile var napaka = ""
        @Volatile var oznaka = ""            // id datoteke za files.list (media:shramba:<id>)
        fun json(): JSONObject = JSONObject().put("id", id).put("state", stanje).put("done", preneseno)
            .put("size", velikost).put("name", ime).put("error", napaka).put("file_id", oznaka)
            .put("where", Environment.DIRECTORY_DOWNLOADS + "/" + MAPA + "/" + ime)
    }

    private val opravila = ConcurrentHashMap<String, Opravilo>()
    private val stevec = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Pregled 10. 11. 2026 (3.3): prej nova nit za vsak `storage.put`. Najvec [PrenosPravila.NAJVEC_PRENOSOV] niti, brez
     * cakalne vrste (presezek zavrne [sprejmi] s kodo »preobremenjen«); mirujoce niti se po 30 s koncajo.
     */
    private val izvajalec = java.util.concurrent.ThreadPoolExecutor(0, PrenosPravila.NAJVEC_PRENOSOV, 30L,
        java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue()) { r -> Thread(r, "safeer-shramba").apply { isDaemon = true } }

    fun prostor(): Long = try {
        val s = StatFs(Environment.getExternalStorageDirectory().path)
        s.availableBlocksLong * s.blockSizeLong
    } catch (_: Throwable) { -1L }

    /** `storage.put`: preveri, ali naprava sme in zmore, in zacne prenos v ozadju. */
    fun sprejmi(context: Context, p: JSONObject): Daljinec.Izid {
        if (Build.VERSION.SDK_INT < 29) return Daljinec.Izid(false, "Shramba potrebuje Android 10 ali novejsi", koda = "ni_podprto")
        val url = p.optString("url"); val odtis = p.optString("fp"); val zeton = p.optString("token")
        val ime = varnoIme(p.optString("name"))
        val velikost = p.optLong("size", -1L)
        val sha = p.optString("sha256").lowercase()
        if (!url.startsWith("https://") || odtis.length != 64 || zeton.isBlank() || ime.isBlank() || sha.length != 64)
            return Daljinec.Izid(false, "Nepopolna zahteva", koda = "napacna_zahteva")
        // Pregled 10. 11. 2026 (1.1): cilj kot pri Pretoku - ta naprava sama in povezavno-lokalni naslovi niso vir.
        if (PretokHttp.ciljPrenosaNedovoljen(url)) return Daljinec.Izid(false, "Vir ni dovoljen", koda = PrenosPravila.KODA_CILJ)
        val pomoc = Zmogljivost.porocilo(context).optJSONObject("pomoc")
        if (pomoc?.optBoolean("lahko", true) == false)
            return Daljinec.Izid(false, "Naprava ta trenutek ne more pomagati", koda = pomoc.optString("razlog"))
        val app = context.applicationContext
        // Pregled 10. 11. 2026 (1.2, 3.3): napovedana velikost je obvezna in omejena, prostor steje tudi tekoce prenose,
        // hkrati najvec dva prenosa. Preverba in vpis skupaj, da dva socasna ukaza ne gresta oba mimo meje.
        synchronized(this) {
            val tekoca = opravila.values.filter { !it.koncano }
            val obljubljeno = tekoca.sumOf { (it.velikost - it.preneseno).coerceAtLeast(0L) }
            PrenosPravila.preveriVelikost(velikost, PrenosPravila.NAJVEC_SHRAMBA_B, prostor(), REZERVA, obljubljeno)?.let { koda ->
                return Daljinec.Izid(false, besediloZavrnitve(koda), koda = koda)
            }
            if (PrenosPravila.preobremenjen(tekoca.size, PrenosPravila.NAJVEC_PRENOSOV))
                return Daljinec.Izid(false, "Naprava ze prenasa dve datoteki", koda = PrenosPravila.KODA_PREOBREMENJEN)
            val o = Opravilo(java.util.UUID.randomUUID().toString(), ime, velikost)
            opravila[o.id] = o
            try { izvajalec.execute { prenesi(app, o, url, odtis, zeton, sha) } }
            catch (_: java.util.concurrent.RejectedExecutionException) {
                opravila.remove(o.id)
                return Daljinec.Izid(false, "Naprava ze prenasa dve datoteki", koda = PrenosPravila.KODA_PREOBREMENJEN)
            }
            PrenosPravila.izrini(opravila.values.map { PrenosPravila.Opis(it.id, it.nastal, it.koncano) }, PrenosPravila.HRANI_PRENOSOV)
                .forEach { opravila.remove(it) }
            return Daljinec.Izid(true, "Prenasam", o.json())
        }
    }

    internal fun besediloZavrnitve(koda: String): String = when (koda) {
        PrenosPravila.KODA_PROSTOR -> "Premalo prostora"
        PrenosPravila.KODA_PREVELIKA -> "Datoteka je prevelika"
        else -> "Nepopolna zahteva"
    }

    fun stanje(id: String): Daljinec.Izid {
        val o = opravila[id] ?: return Daljinec.Izid(false, "Ni takega prenosa", koda = "ni_opravila")
        return Daljinec.Izid(true, o.stanje, o.json())
    }

    private fun varnoIme(ime: String): String =
        ime.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\u0000-\\u001f]"), "").trim().take(200)
            .takeIf { it.isNotBlank() && it != "." && it != ".." }.orEmpty()

    private fun prenesi(ctx: Context, o: Opravilo, url: String, odtis: String, zeton: String, sha: String) {
        if (Build.VERSION.SDK_INT < 29) return   // MediaStore.Downloads (sprejmi to ze preveri; za Lint)
        val cr = ctx.contentResolver
        var uri: Uri? = null
        try {
            val vrednosti = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, o.ime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + MAPA)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = cr.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), vrednosti)
                ?: throw PrenosPravila.Napaka(PrenosPravila.KODA_SHRANJEVANJE)
            // Pregled 10. 11. 2026 (1.1): razresena imena preveri isti DNS kot pri Pretoku (brez DNS rebindinga).
            val odjemalec = si.safeer.tv.os.PripetiVir.odjemalecZaStreznik(odtis, PretokHttp.dnsPripeteNaprave())
            val zahteva = Request.Builder().url(url).header("X-Safeer-Token", zeton).build()
            val md = MessageDigest.getInstance("SHA-256")
            odjemalec.newCall(zahteva).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("racunalnik je vrnil ${r.code}")
                val telo = r.body ?: throw IllegalStateException("prazen odgovor")
                // Pregled 10. 11. 2026 (1.2): vir ne sme poslati vec, kot je napovedal - sicer napolni disk. Delno
                // datoteko pobrise veja napake spodaj.
                if (PrenosPravila.glavaPresega(telo.contentLength(), o.velikost)) throw PrenosPravila.Napaka(PrenosPravila.KODA_PRESEGA)
                cr.openOutputStream(uri)!!.use { izhod ->
                    telo.byteStream().use { vhod ->
                        val b = ByteArray(256 * 1024)
                        while (true) {
                            val n = vhod.read(b)
                            if (n < 0) break
                            if (PrenosPravila.presega(o.preneseno, n, o.velikost)) throw PrenosPravila.Napaka(PrenosPravila.KODA_PRESEGA)
                            izhod.write(b, 0, n)
                            md.update(b, 0, n)
                            o.preneseno += n
                        }
                    }
                }
            }
            val dobljen = md.digest().joinToString("") { "%02x".format(it) }
            if (o.preneseno != o.velikost || dobljen != sha) throw PrenosPravila.Napaka(PrenosPravila.KODA_UJEMANJE)
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            o.oznaka = "media:shramba:" + android.content.ContentUris.parseId(uri)
            o.stanje = "koncano"
            Log.i(TAG, "Shranjeno (${o.preneseno} B)")
        } catch (e: Throwable) {
            // V stanje gre koda, ne besedilo izjeme (pregled 10. 11. 2026, 1.1); v dnevnik brez imena datoteke.
            val koda = PrenosPravila.kodaNapake(e) { it is PretokHttp.NedovoljenCilj }
            Log.w(TAG, "Shranjevanje ni uspelo: $koda (${e.javaClass.simpleName})")
            o.napaka = koda
            o.stanje = "napaka"
            try { if (uri != null) cr.delete(uri, null, null) } catch (_: Throwable) { }
        }
    }
}
