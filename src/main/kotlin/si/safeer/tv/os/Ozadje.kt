package si.safeer.tv.os

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import si.safeer.tv.R
import java.io.File
import java.io.FileOutputStream

/**
 * Ozadje Safeer OS. Ozadje je loceno od teme (barv): uporabnik izbere sliko posebej in barve
 * posebej, ker se okusa ne ujemata vedno.
 *
 * Pripravljene slike so nase lastne risbe (vektor, nekaj kilobajtov, ostre na vsakem zaslonu) -
 * nobene tuje fotografije, zato tudi nobenih tujih pravic. Poleg njih lahko uporabnik izbere
 * svojo fotografijo: kopijo shranimo v aplikacijo, obrezano na razmerje zaslona, da je ob vsakem
 * zagonu takoj pri roki in ne visi od tega, ali je racunalnik ali USB se prikljucen.
 *
 * Nad sliko sta dve plasti: zavesa (levo skoraj neprozorna, da so naslovi odsekov berljivi) in
 * zatemnitev, ki jo uporabnik nastavi sam z drsnikom. Svetla fotografija z vecjo zatemnitvijo
 * ostane berljiva; kdor hoce sliko videti v polni moci, zatemnitev spusti na nic.
 */
object Ozadje {
    private const val PREFS = "safeer_os"
    private const val KLJUC = "ozadje"
    private const val KLJUC_ZATEMNITEV = "ozadje_zatemnitev"
    private const val DATOTEKA = "ozadje-uporabnika.jpg"

    /** Oznaka izbire "lastna fotografija"; shranjena je v nastavitvah, zato se ne spreminja. */
    const val LASTNA = "lastna"
    const val BREZ = "brez"

    /** Zatemnitev v odstotkih: privzeto toliko, da je drobno besedilo berljivo z dveh metrov. */
    const val PRIVZETA_ZATEMNITEV = 35
    const val NAJVECJA_ZATEMNITEV = 85
    const val KORAK_ZATEMNITVE = 5

    /** Najvecja shranjena slika: ravno zaslon televizorja, da ne jemljemo pomnilnika po nepotrebnem. */
    private const val SIRINA = 1920
    private const val VISINA = 1080

    /** [oznaka] se shrani v nastavitve, zato se nikoli ne spremeni. */
    class Izbira(val oznaka: String, val imeRes: Int, val risba: Int)

    val VSE = listOf(
        Izbira("valovi", R.string.os_ozadje_valovi, R.drawable.os_slika_valovi),
        Izbira("gore", R.string.os_ozadje_gore, R.drawable.os_slika_gore),
        Izbira("gozd", R.string.os_ozadje_gozd, R.drawable.os_slika_gozd),
        Izbira("sij", R.string.os_ozadje_sij, R.drawable.os_slika_sij),
        Izbira("morje", R.string.os_ozadje_morje, R.drawable.os_slika_morje),
        Izbira(LASTNA, R.string.os_ozadje_lastna, 0),
        Izbira(BREZ, R.string.os_ozadje_brez, 0),
    )

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Privzeto so abstraktni valovi: barve znamke, brez motenj pod karticami. */
    fun izbrana(c: Context): Izbira {
        val oznaka = prefs(c).getString(KLJUC, null) ?: return VSE[0]
        val i = VSE.firstOrNull { it.oznaka == oznaka } ?: return VSE[0]
        // Uporabnik je izbral svojo fotografijo, datoteke pa ni vec (pocistil pomnilnik aplikacije):
        // takrat ne pustimo praznega zaslona, ampak se vrnemo na privzeto sliko.
        if (i.oznaka == LASTNA && !imaLastno(c)) return VSE[0]
        return i
    }

    fun nastavi(c: Context, izbira: Izbira) {
        prefs(c).edit().putString(KLJUC, izbira.oznaka).apply()
    }

    fun ime(c: Context): String = c.getString(izbrana(c).imeRes)

    fun zatemnitev(c: Context): Int =
        prefs(c).getInt(KLJUC_ZATEMNITEV, PRIVZETA_ZATEMNITEV).coerceIn(0, NAJVECJA_ZATEMNITEV)

    fun nastaviZatemnitev(c: Context, odstotek: Int) {
        prefs(c).edit().putInt(KLJUC_ZATEMNITEV, odstotek.coerceIn(0, NAJVECJA_ZATEMNITEV)).apply()
    }

    // ------------------------------------------------------------------ lastna fotografija

    fun datoteka(c: Context): File = File(c.applicationContext.filesDir, DATOTEKA)

    fun imaLastno(c: Context): Boolean = datoteka(c).let { it.isFile && it.length() > 0 }

    /**
     * Shrani uporabnikovo fotografijo: pomanjsano in obrezano na razmerje zaslona, da je ozadje
     * ostro in nic raztegnjeno. Vrne true, kadar je slika pristala na disku.
     */
    fun shraniLastno(c: Context, bajti: ByteArray): Boolean {
        val slika = odkodiraj(bajti) ?: return false
        val obrezana = obrezi(slika)
        return try {
            FileOutputStream(datoteka(c)).use { obrezana.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (obrezana !== slika) slika.recycle()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun pozabiLastno(c: Context) {
        try { datoteka(c).delete() } catch (_: Throwable) { }
    }

    private fun odkodiraj(bajti: ByteArray): Bitmap? = try {
        val mere = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bajti, 0, bajti.size, mere)
        var vzorec = 1
        while (mere.outWidth / (vzorec * 2) >= SIRINA && mere.outHeight / (vzorec * 2) >= VISINA) vzorec *= 2
        BitmapFactory.decodeByteArray(bajti, 0, bajti.size, BitmapFactory.Options().apply { inSampleSize = vzorec })
    } catch (_: Throwable) { null }

    /** Obrez na sredini na razmerje 16:9 in pomanjsanje na velikost zaslona. */
    private fun obrezi(slika: Bitmap): Bitmap {
        val zelenoRazmerje = SIRINA.toFloat() / VISINA
        val razmerje = slika.width.toFloat() / slika.height
        val sirina: Int
        val visina: Int
        if (razmerje > zelenoRazmerje) { visina = slika.height; sirina = (visina * zelenoRazmerje).toInt() }
        else { sirina = slika.width; visina = (sirina / zelenoRazmerje).toInt() }
        val x = (slika.width - sirina) / 2
        val y = (slika.height - visina) / 2
        return try {
            val izrez = Bitmap.createBitmap(slika, x, y, sirina.coerceAtLeast(1), visina.coerceAtLeast(1))
            if (izrez.width <= SIRINA) izrez
            else Bitmap.createScaledBitmap(izrez, SIRINA, VISINA, true).also { if (it !== izrez) izrez.recycle() }
        } catch (_: Throwable) { slika }
    }

    // ------------------------------------------------------------------ risanje

    /** Risba izbire brez zavese: za predogled v seznamu ozadij. */
    fun predogled(c: Context, izbira: Izbira): Drawable? = when {
        izbira.oznaka == BREZ -> ColorDrawable(c.getColor(R.color.os_ozadje))
        izbira.oznaka == LASTNA -> lastnaRisba(c)
        else -> try { c.getDrawable(izbira.risba) } catch (_: Throwable) { null }
    }

    private fun lastnaRisba(c: Context): Drawable? {
        val d = datoteka(c)
        if (!d.isFile) return null
        return try {
            val slika = BitmapFactory.decodeFile(d.absolutePath) ?: return null
            BitmapDrawable(c.resources, slika)
        } catch (_: Throwable) { null }
    }

    /** Cela podlaga zaslona: barva, slika, zavesa in uporabnikova zatemnitev. */
    fun sestavi(c: Context, izbira: Izbira, zatemnitev: Int): Drawable {
        val osnova = ColorDrawable(c.getColor(R.color.os_ozadje))
        val slika = if (izbira.oznaka == BREZ) null else predogled(c, izbira)
        if (slika == null) return osnova
        val plasti = ArrayList<Drawable>(4)
        plasti.add(osnova)
        plasti.add(slika)
        c.getDrawable(R.drawable.os_zavesa)?.let { plasti.add(it) }
        val a = (zatemnitev.coerceIn(0, NAJVECJA_ZATEMNITEV) * 255) / 100
        if (a > 0) plasti.add(ColorDrawable(Color.argb(a, 0, 0, 0)))
        return LayerDrawable(plasti.toTypedArray())
    }

    /** Ozadje zaslona po izbiri uporabnika; "Enobarvno" pusti mirno temno ploskev. */
    fun uporabi(a: Activity, koren: View) {
        koren.background = sestavi(a, izbrana(a), zatemnitev(a))
    }
}
