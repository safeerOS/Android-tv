package si.safeer.tv.scit

import android.content.Context
import com.safeer.threatfeed.HttpsListFetcher
import com.safeer.threatfeed.PlainListParser
import com.safeer.threatfeed.PlainListSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Filtri po meri (lastnik 9. 11. 2026, kot »Dodaj filter po meri« v AdGuardu): uporabnik doda naslov javnega seznama
 * domen (vec jih je na filterlists.com). Seznam gre samo v nabor Scita (DNS), ne v brskalnik in ne med groznje.
 * Shramba: files/scit/po-meri.json (bere jo tudi proces :scit; vsakic sveze, brez SharedPreferences).
 */
object ScitPoMeri {
    class Filter(val url: String, val vklop: Boolean) {
        val id: String get() = ScitPoMeriPravila.id(url)
    }

    private fun datoteka(c: Context) = File(File(c.applicationContext.filesDir, "scit"), "po-meri.json")

    fun seznam(c: Context): List<Filter> = try {
        val d = datoteka(c)
        if (!d.isFile || d.length() > 64 * 1024) emptyList() else {
            val a = JSONArray(d.readText())
            (0 until minOf(a.length(), ScitPoMeriPravila.NAJVEC)).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (ScitPoMeriPravila.napaka(url) == null) Filter(url, o.optBoolean("vklop", true)) else null
            }.distinctBy { it.id }
        }
    } catch (_: Throwable) { emptyList() }

    @Synchronized
    private fun zapisi(c: Context, filtri: List<Filter>): Boolean = try {
        val a = JSONArray()
        for (f in filtri.take(ScitPoMeriPravila.NAJVEC)) a.put(JSONObject().put("url", f.url).put("vklop", f.vklop))
        val d = datoteka(c)
        d.parentFile?.mkdirs()
        val z = File.createTempFile("po-meri", ".tmp", d.parentFile)
        z.writeText(a.toString())
        if (z.renameTo(d)) true else { z.delete(); false }
    } catch (_: Throwable) { false }

    @Synchronized
    // CodeRabbit 9. 11. 2026: branje in zapis skupaj pod istim zaklepom (sicer hkratna dodaja/preklop izgubi spremembo).
    fun dodaj(c: Context, url: String): Boolean {
        val zdaj = seznam(c)
        if (zdaj.size >= ScitPoMeriPravila.NAJVEC || zdaj.any { it.id == ScitPoMeriPravila.id(url) }) return false
        return zapisi(c, zdaj + Filter(url, true))
    }

    @Synchronized
    fun nastavi(c: Context, id: String, vklop: Boolean): Boolean = zapisi(c, seznam(c).map { if (it.id == id) Filter(it.url, vklop) else it })

    @Synchronized
    fun odstrani(c: Context, id: String): Boolean = zapisi(c, seznam(c).filter { it.id != id })

    /** Viri za agenta Scita: samo vklopljeni, samo DNS (ne brskalnik, ne groznje), brez glave (90 % vrstic so imena). */
    fun viri(c: Context): List<PlainListSource> = seznam(c).filter { it.vklop }.map {
        PlainListSource(it.id, ScitPoMeriPravila.ime(it.url), it.url, "Filter po meri", "", minEntries = 1,
            maxBytes = ScitPoMeriPravila.NAJVEC_BAJTOV, headerless = true, dnsOnly = true)
    }

    /**
     * Preizkusni prenos pred dodajanjem (iz ozadja): HTTPS, brez preusmeritve drugam, najvec [ScitPoMeriPravila.NAJVEC_BAJTOV],
     * ne HTML in vsaj eno ime. Vrne stevilo imen ali -1 (seznam ni primeren / ni odgovora).
     */
    fun preizkusi(url: String): Int = try {
        val src = PlainListSource("preizkus", "preizkus", url, "", "", minEntries = 1,
            maxBytes = ScitPoMeriPravila.NAJVEC_BAJTOV, headerless = true, dnsOnly = true)
        val odg = HttpsListFetcher().fetch(url, src.maxBytes, null, null)
        val telo = odg.body
        if (telo == null) -1 else PlainListParser.parse(telo, src).size
    } catch (_: Throwable) { -1 }
}
