package si.safeer.tv.os

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kar je uporabnik nazadnje odprl - da lahko nadaljuje z enim pritiskom.
 *
 * Na televizorju je vsaka pot do datoteke dolga: Datoteke, izberi racunalnik, mapa, mapa, datoteka.
 * Kdor je vceraj gledal film, ga hoce danes odpreti takoj. Zato si zapomnimo zadnjih nekaj stvari
 * in jih pokazemo v prvi vrsti domacega zaslona.
 *
 * Kaj hranimo: samo toliko, da znamo isto stvar odpreti znova - vrsto, ime, oznako datoteke ali
 * naslov in id racunalnika. **Nikoli zetona ali naslova streznika**: ta velja samo, dokler seja
 * tece, zato ga ob ponovnem odpiranju znova vprasamo racunalnik. Tako v shrambi televizorja ne
 * ostane nic, s cimer bi se dalo do uporabnikovih datotek.
 *
 * Seznam je uporabnikov: vsako vrstico lahko odstrani (dolg pritisk), vse skupaj pa v nastavitvah.
 */
object Nadaljuj {

    private const val TAG = "SafeerOsNadaljuj"
    private const val PREFS = "safeer_os"
    private const val KLJUC = "nadaljuj"

    /** Koliko stvari hranimo. Vec kot ena vrsta kartic na zaslonu ne pomaga. */
    const val NAJVEC = 6

    // Vrste, ki jih znamo odpreti znova.
    const val VIDEO = "video"
    const val GLASBA = "glasba"
    const val SLIKA = "slika"
    const val BESEDILO = "besedilo"
    const val SPLETNA = "spletna"
    const val ZASLON = "zaslon"
    const val PROGRAM = "program"

    /**
     * [id] je oznaka datoteke na racunalniku ali naslov content:// na televizorju, [url] naslov
     * spletne aplikacije, [program] oznaka programa (`app:...`), [racunalnik] pa naprava v Linku.
     */
    data class Vnos(
        val vrsta: String,
        val ime: String,
        val kdaj: Long = System.currentTimeMillis(),
        val racunalnik: String = "",
        val id: String = "",
        val mime: String = "",
        val krajevno: Boolean = false,
        val url: String = "",
        val program: String = "",
        /** Program je igra (skupina igre): zaslon se odpre v nacinu tipk. */
        val igra: Boolean = false,
    ) {
        /** Dve vrstici sta ista stvar, kadar se ujemata vrsta in to, kar odpreta. */
        fun kljuc(): String = vrsta + "|" + racunalnik + "|" + (id.ifBlank { url.ifBlank { program } })
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun seznam(c: Context): List<Vnos> {
        val surovo = prefs(c).getString(KLJUC, null) ?: return emptyList()
        return try {
            val polje = JSONArray(surovo)
            (0 until polje.length()).mapNotNull { i ->
                val o = polje.optJSONObject(i) ?: return@mapNotNull null
                val vrsta = o.optString("vrsta")
                if (vrsta.isBlank()) null else Vnos(
                    vrsta = vrsta,
                    ime = o.optString("ime"),
                    kdaj = o.optLong("kdaj"),
                    racunalnik = o.optString("racunalnik"),
                    id = o.optString("id"),
                    mime = o.optString("mime"),
                    krajevno = o.optBoolean("krajevno"),
                    url = o.optString("url"),
                    program = o.optString("program"),
                    igra = o.optBoolean("igra"),
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Seznama ni bilo mogoce prebrati: ${e.message}")
            emptyList()
        }
    }

    /** Doda na vrh; ista stvar se ne podvoji, ampak se le premakne naprej. */
    fun zapisi(c: Context, v: Vnos) {
        if (v.ime.isBlank() && v.url.isBlank()) return
        shrani(c, OsPravila.naVrh(v, seznam(c), NAJVEC) { it.kljuc() })
    }

    fun odstrani(c: Context, v: Vnos) {
        shrani(c, seznam(c).filterNot { it.kljuc() == v.kljuc() })
    }

    fun pocisti(c: Context) {
        prefs(c).edit().remove(KLJUC).apply()
        pocistiIkone(c, emptyList())
    }

    // ------------------------------------------------------------------ ikone programov
    //
    // Program z racunalnika je imel v Nadaljuj splosno ikono racunalnika: sest enakih kartic, ki jih
    // loci samo napis. Ikono, ki jo racunalnik poslje s seznamom programov, zato ob zagonu shranimo
    // k vrstici; ko vrstica izpade iz seznama, izbrisemo tudi njeno ikono.

    private fun mapaIkon(c: Context) = java.io.File(c.applicationContext.filesDir, "nadaljuj-ikone")

    private fun datotekaIkone(c: Context, v: Vnos) = java.io.File(mapaIkon(c), OsPravila.imeIkone(v.kljuc()))

    fun shraniIkono(c: Context, v: Vnos, png: ByteArray) {
        if (png.isEmpty() || png.size > 512 * 1024) return
        try {
            mapaIkon(c).mkdirs()
            datotekaIkone(c, v).writeBytes(png)
        } catch (e: Throwable) {
            Log.w(TAG, "Ikone ni bilo mogoce shraniti: ${e.message}")
        }
    }

    fun ikona(c: Context, v: Vnos): android.graphics.drawable.Drawable? {
        val f = datotekaIkone(c, v)
        if (!f.isFile) return null
        return try {
            val slika = VarnaSlika.izDatoteke(f.absolutePath) ?: return null
            SpletneAplikacije.ikonaIzSlike(c, slika)
        } catch (_: Throwable) { null }
    }

    /** Ikone vrstic, ki jih ni vec: mapa nikoli ne zraste cez NAJVEC datotek. */
    private fun pocistiIkone(c: Context, vnosi: List<Vnos>) {
        try {
            val datoteke = mapaIkon(c).listFiles()?.map { it.name } ?: return
            for (ime in OsPravila.odvecneIkone(datoteke, vnosi.map { it.kljuc() })) java.io.File(mapaIkon(c), ime).delete()
        } catch (_: Throwable) { }
    }

    private fun shrani(c: Context, vnosi: List<Vnos>) {
        val polje = JSONArray()
        for (v in vnosi.take(NAJVEC)) {
            polje.put(JSONObject()
                .put("vrsta", v.vrsta).put("ime", v.ime).put("kdaj", v.kdaj)
                .put("racunalnik", v.racunalnik).put("id", v.id).put("mime", v.mime)
                .put("krajevno", v.krajevno).put("url", v.url).put("program", v.program).put("igra", v.igra))
        }
        prefs(c).edit().putString(KLJUC, polje.toString()).apply()
        pocistiIkone(c, vnosi.take(NAJVEC))
    }
}
