package si.safeer.tv.link

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * »Pretoci aplikacijo«: seznanjena naprava (Safeer Control ali Safeer OS na racunalniku) je poslala
 * `apps.launch` s `stream: true`. Ta nevidna dejavnost vprasa za zajem zaslona (Android to zahteva
 * na napravi za vsako deljenje), zazene deljenje proti napravi, ki je vprasala, in nato odpre aplikacijo.
 *
 * Ce uporabnik zajem zavrne, se aplikacija ne odpre in nic ne odide z naprave.
 */
class PretociActivity : Activity() {

    companion object {
        private const val TAG = "SafeerPretoci"
        private const val ZAHTEVA = 4712
        const val EXTRA_CILJ = "si.safeer.tv.link.PRETOCI_CILJ"
        const val EXTRA_PAKET = "si.safeer.tv.link.PRETOCI_PAKET"

        fun namera(context: Context, cilj: String, paket: String): Intent =
            Intent(context, PretociActivity::class.java)
                .putExtra(EXTRA_CILJ, cilj)
                .putExtra(EXTRA_PAKET, paket)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }

    private var cilj = ""
    private var paket = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cilj = intent?.getStringExtra(EXTRA_CILJ).orEmpty()
        paket = intent?.getStringExtra(EXTRA_PAKET).orEmpty()
        if (savedInstanceState != null) return
        // Odprli smo se (neposredno ali s tapom na obvestilo): obvestilo za zagon ni vec potrebno.
        Daljinec.pospraviObvestiloZagona(this)
        if (cilj.isBlank() || paket.isBlank()) { finish(); return }
        try {
            val upravitelj = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            val namera = try {
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    val razred = Class.forName("android.media.projection.MediaProjectionConfig")
                    val nastavitev = razred.getMethod("createConfigForDefaultDisplay").invoke(null)
                    upravitelj.javaClass.getMethod("createScreenCaptureIntent", razred)
                        .invoke(upravitelj, nastavitev) as Intent
                } else upravitelj.createScreenCaptureIntent()
            } catch (_: Throwable) {
                upravitelj.createScreenCaptureIntent()
            }
            @Suppress("DEPRECATION")
            startActivityForResult(namera, ZAHTEVA)
        } catch (e: Throwable) {
            Log.w(TAG, "Zajema zaslona ni bilo mogoce zahtevati: ${e.message}")
            finish()
        }
    }

    @Deprecated("Activity.onActivityResult")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != ZAHTEVA) return
        if (resultCode == RESULT_OK && data != null) {
            LinkMost.zazeniDeljenje(this, resultCode, data, cilj, cilj)
            Daljinec.nameraZaZagon(this, paket)?.let { namera ->
                try { startActivity(namera) } catch (e: Throwable) { Log.w(TAG, "Aplikacije ni bilo mogoce odpreti: ${e.message}") }
            }
        } else {
            Log.i(TAG, "Uporabnik ni dovolil zajema zaslona; aplikacije ne odpremo.")
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
