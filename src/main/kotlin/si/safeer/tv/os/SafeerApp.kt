package si.safeer.tv.os

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Enoten model aplikacije v Safeer OS: aplikacija televizorja, spletna aplikacija in program z
 * racunalnika so za uporabnika ista stvar - nekaj, kar odpre. Razlikuje se le, kje tece.
 *
 * Vsi trije viri dobijo iste skupine (igre, pisarna, splet, predstavnost ...), kot jih je doslej
 * imel samo seznam programov racunalnika, zato je en zaslon "Aplikacije" lahko za vse.
 */
enum class AppVir(val kljuc: String) {
    TV("tv"), SPLET("splet"), RACUNALNIK("racunalnik");

    companion object {
        fun iz(kljuc: String?): AppVir? = values().firstOrNull { it.kljuc == kljuc }
    }
}

data class SafeerApp(
    /** Enoten kljuc: "tv:<paket>", "splet:<url>", "racunalnik:<racunalnik>:<program>". */
    val kljuc: String,
    val ime: String,
    val opis: String,
    val skupina: String,
    val ikona: Drawable?,
    val vir: AppVir,
    /** Paket (tv), naslov (splet) ali oznaka programa (racunalnik). */
    val cilj: String,
    val racunalnik: String = "",
    val namera: Intent? = null,
)

object SafeerAppi {

    /** Skupine v vrstnem redu, kot ga uporabnik ze pozna s seznama programov racunalnika. */
    val SKUPINE = listOf("igre", "pisarna", "splet", "predstavnost", "programiranje", "ucenje", "orodja", "drugo")

    // ------------------------------------------------------------------ viri na tej napravi

    /** Aplikacije televizorja; skupino pove sistem sam (kategorija aplikacije, zastavica igre). */
    fun naTelevizorju(c: Context): List<SafeerApp> = Aplikacije.seznam(c).map { v ->
        SafeerApp("tv:" + v.paket, v.ime, "", skupinaAplikacije(c, v.paket),
            Aplikacije.ikona(c, v), AppVir.TV, v.paket, namera = v.namera)
    }

    fun spletne(c: Context): List<SafeerApp> = SpletneAplikacije.seznam(c).map { a ->
        val ime = a.ime.ifBlank { SpletneAplikacije.gostitelj(a.url) }
        SafeerApp("splet:" + a.url, ime, SpletneAplikacije.gostitelj(a.url), skupinaSpleta(a.url),
            SpletneAplikacije.ikona(c, a), AppVir.SPLET, a.url)
    }

    @Suppress("DEPRECATION")
    private fun skupinaAplikacije(c: Context, paket: String): String {
        val info = try { c.packageManager.getApplicationInfo(paket, 0) } catch (_: Throwable) { return "drugo" }
        if (info.flags and ApplicationInfo.FLAG_IS_GAME != 0) return "igre"
        return when (info.category) {
                ApplicationInfo.CATEGORY_GAME -> "igre"
                ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO,
                ApplicationInfo.CATEGORY_IMAGE -> "predstavnost"
                // Na televizorju "produktivnost" pomeni trgovino, prenos datotek, brskalnik - orodja,
                // ne pisarne (tako bi Trgovina Play pristala med pisarniskimi programi).
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> "orodja"
                ApplicationInfo.CATEGORY_NEWS, ApplicationInfo.CATEGORY_SOCIAL -> "splet"
                ApplicationInfo.CATEGORY_MAPS -> "orodja"
                else -> skupinaPoImenu(paket)
            }
    }

    /** Ko aplikacija o sebi nic ne pove: pretocne storitve so skoraj vse predstavnost. */
    private fun skupinaPoImenu(paket: String): String {
        val p = paket.lowercase()
        return when {
            listOf("settings", "vending", "downloader", "explorer", "files", "sourceenabler")
                .any { p.contains(it) } -> "orodja"
            listOf("youtube", "netflix", "amazonvideo", "disney", "hbo", "spotify", "twitch", "plex",
                "kodi", "vlc", "smarttube", "stream", "playtv", "video", "music", "player", "radio")
                .any { p.contains(it) } -> "predstavnost"
            listOf("game", "igra").any { p.contains(it) } -> "igre"
            listOf("browser", "chrome", "firefox").any { p.contains(it) } -> "splet"
            else -> "drugo"
        }
    }

    private fun skupinaSpleta(url: String): String {
        val g = SpletneAplikacije.gostitelj(url).lowercase()
        return if (listOf("youtube", "netflix", "rtvslo", "rtv", "twitch", "spotify", "disney", "max.",
                "primevideo", "voyo", "arnes", "dailymotion", "vimeo", "radio").any { g.contains(it) })
            "predstavnost" else "splet"
    }

    // ------------------------------------------------------------------ priljubljeni z racunalnika

    /**
     * Priljubljeni programi z racunalnika in spletne aplikacije, ki jih uporabnik hoce na domacem
     * zaslonu. Aplikacije televizorja imajo svoje (Priljubljene); tu so ostali viri. Ikono shranimo
     * v datoteko, da jo domaci zaslon pokaze, tudi ko racunalnik se ni povezan.
     */
    data class Priljubljen(val kljuc: String, val ime: String, val vir: AppVir, val cilj: String,
                           val racunalnik: String, val skupina: String, val ikona: String)

    private const val PREFS = "safeer_os"
    private const val KLJUC = "priljubljeni_viri"

    fun priljubljeni(c: Context): List<Priljubljen> {
        val s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KLJUC, "[]") ?: "[]"
        return try {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val vir = AppVir.iz(o.optString("vir")) ?: return@mapNotNull null
                Priljubljen(o.optString("kljuc"), o.optString("ime"), vir, o.optString("cilj"),
                    o.optString("racunalnik"), o.optString("skupina"), o.optString("ikona"))
            }
        } catch (_: Throwable) { emptyList() }
    }

    private fun shrani(c: Context, seznam: List<Priljubljen>) {
        val a = JSONArray()
        for (p in seznam) a.put(JSONObject().put("kljuc", p.kljuc).put("ime", p.ime).put("vir", p.vir.kljuc)
            .put("cilj", p.cilj).put("racunalnik", p.racunalnik).put("skupina", p.skupina).put("ikona", p.ikona))
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KLJUC, a.toString()).apply()
    }

    /** Ali je na domacem zaslonu (za vse tri vire). */
    fun jePriljubljena(c: Context, app: SafeerApp): Boolean = when (app.vir) {
        AppVir.TV -> Priljubljene.je(c, app.cilj)
        else -> priljubljeni(c).any { it.kljuc == app.kljuc }
    }

    /** Doda ali odstrani z domacega zaslona; vrne novo stanje. [ikonaPng] = ikona programa (PNG). */
    fun preklopi(c: Context, app: SafeerApp, ikonaPng: ByteArray? = null): Boolean {
        if (app.vir == AppVir.TV) {
            val bila = Priljubljene.je(c, app.cilj)
            if (bila) Priljubljene.odstrani(c, app.cilj) else Priljubljene.dodaj(c, app.cilj)
            return !bila
        }
        val seznam = priljubljeni(c).toMutableList()
        seznam.filter { it.kljuc == app.kljuc }.forEach { pobrisiIkono(it) }
        val bila = seznam.removeAll { it.kljuc == app.kljuc }
        if (!bila) {
            var pot = ""
            if (ikonaPng != null && OsPravila.shraniIkono(ikonaPng.size)) try {
                val mapa = File(c.filesDir, "ikone").apply { mkdirs() }
                val f = File(mapa, Integer.toHexString(app.kljuc.hashCode()) + ".png")
                f.writeBytes(ikonaPng)
                pot = f.absolutePath
            } catch (_: Throwable) { }
            seznam.add(Priljubljen(app.kljuc, app.ime, app.vir, app.cilj, app.racunalnik, app.skupina, pot))
        }
        shrani(c, seznam)
        return !bila
    }

    fun odstrani(c: Context, kljuc: String) {
        val seznam = priljubljeni(c)
        seznam.filter { it.kljuc == kljuc }.forEach { pobrisiIkono(it) }
        shrani(c, seznam.filterNot { it.kljuc == kljuc })
    }

    /** Odstranjena kartica ne pusti ikone v shrambi televizorja. */
    private fun pobrisiIkono(p: Priljubljen) {
        if (p.ikona.isNotBlank()) try { File(p.ikona).delete() } catch (_: Throwable) { }
    }

    fun ikona(c: Context, p: Priljubljen): Drawable? {
        if (p.ikona.isBlank()) return null
        return try {
            val slika = VarnaSlika.izDatoteke(p.ikona) ?: return null
            SpletneAplikacije.ikonaIzSlike(c, slika)
        } catch (_: Throwable) { null }
    }
}
