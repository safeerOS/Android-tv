package si.safeer.tv

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Kam se shranjujejo prenesene datoteke.
 *
 * Eno samo mesto za celo aplikacijo: brskalnikovi prenosi in - ko bo prenos datotek prek
 * Safeer Linka narejen - tudi datoteke, ki jih naprava prejme z druge naprave v hisi.
 * Enako kot v brskalniku za telefon, da je odgovor na "kje je moja datoteka" povsod isti.
 *
 * Privzeto ostane tako, kot je bilo doslej: javna mapa Prenosi, brez podmape.
 */
object PrenosiMapa {

    private const val PREFS = "safeer_ui_prefs"
    private const val KLJUC_MAPA = "download_dir"
    private const val KLJUC_PODMAPA = "download_subdir"

    const val PRENOSI = "DOWNLOADS"
    const val DOKUMENTI = "DOCUMENTS"
    const val SLIKE = "PICTURES"
    const val GLASBA = "MUSIC"
    const val FILMI = "MOVIES"

    val VSE = listOf(PRENOSI, DOKUMENTI, SLIKE, GLASBA, FILMI)

    const val PODMAPA_SAFEER = "Safeer"

    fun izbranaMapa(context: Context): String {
        val shranjeno = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KLJUC_MAPA, PRENOSI) ?: PRENOSI
        return if (VSE.contains(shranjeno)) shranjeno else PRENOSI
    }

    fun nastaviMapo(context: Context, mapa: String) {
        val veljavna = if (VSE.contains(mapa)) mapa else PRENOSI
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KLJUC_MAPA, veljavna).apply()
    }

    fun podmapa(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KLJUC_PODMAPA, "") ?: ""

    fun nastaviPodmapo(context: Context, podmapa: String) {
        val ocisceno = podmapa.trim().trim('/').replace("..", "").replace("/", "")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KLJUC_PODMAPA, ocisceno).apply()
    }

    /** Ime javne mape, kot ga pozna Android. */
    fun sistemskoIme(izbira: String): String = when (izbira) {
        DOKUMENTI -> Environment.DIRECTORY_DOCUMENTS
        SLIKE -> Environment.DIRECTORY_PICTURES
        GLASBA -> Environment.DIRECTORY_MUSIC
        FILMI -> Environment.DIRECTORY_MOVIES
        else -> Environment.DIRECTORY_DOWNLOADS
    }

    /** Pot znotraj javne mape, kot jo pricakuje DownloadManager. */
    fun relativnaPot(context: Context, imeDatoteke: String): String {
        val pod = podmapa(context).trim().trim('/')
        return if (pod.isEmpty()) imeDatoteke else "$pod/$imeDatoteke"
    }

    /** Javna mapa, ki jo je izbral uporabnik (za datoteke, prejete prek Safeer Linka). */
    fun ciljnaMapa(context: Context): File {
        @Suppress("DEPRECATION")
        val koren = Environment.getExternalStoragePublicDirectory(sistemskoIme(izbranaMapa(context)))
        val pod = podmapa(context).trim().trim('/')
        val mapa = if (pod.isEmpty()) koren else File(koren, pod)
        if (!mapa.exists()) {
            try { mapa.mkdirs() } catch (_: Throwable) { }
        }
        return mapa
    }

    /** Kratek opis za meni, npr. "Download/Safeer". */
    fun opis(context: Context): String {
        val ime = when (izbranaMapa(context)) {
            DOKUMENTI -> "Documents"
            SLIKE -> "Pictures"
            GLASBA -> "Music"
            FILMI -> "Movies"
            else -> "Download"
        }
        val pod = podmapa(context).trim().trim('/')
        return if (pod.isEmpty()) ime else "$ime/$pod"
    }
}
