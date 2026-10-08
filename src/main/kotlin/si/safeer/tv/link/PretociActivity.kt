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
        const val EXTRA_ZAHTEVA = "si.safeer.tv.link.PRETOCI_ZAHTEVA"

        /** Okno, ki caka na soglasje (najvec eno). */
        @Volatile private var odprta: java.lang.ref.WeakReference<PretociActivity>? = null

        /** [zahteva]: zeton zahteve ([PretokKonec.Zahteve.nova]) - umaknjena ali pretecena zahteva ne zacne deljenja. */
        fun namera(context: Context, cilj: String, paket: String, zahteva: Int): Intent =
            Intent(context, PretociActivity::class.java)
                .putExtra(EXTRA_CILJ, cilj)
                .putExtra(EXTRA_PAKET, paket)
                .putExtra(EXTRA_ZAHTEVA, zahteva)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        /** Zahteva je umaknjena ali zamenjana: okno za soglasje se zapre (skupaj s sistemskim vprasanjem za zajem zaslona). */
        fun zapriCakajoco() {
            val okno = odprta?.get() ?: return
            okno.runOnUiThread {
                try { @Suppress("DEPRECATION") okno.finishActivity(ZAHTEVA) } catch (_: Throwable) { }
                if (!okno.isFinishing) okno.finish()
            }
        }
    }

    private var cilj = ""
    private var paket = ""
    private var zahteva = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cilj = intent?.getStringExtra(EXTRA_CILJ).orEmpty()
        paket = intent?.getStringExtra(EXTRA_PAKET).orEmpty()
        zahteva = intent?.getIntExtra(EXTRA_ZAHTEVA, 0) ?: 0
        odprta = java.lang.ref.WeakReference(this)
        if (savedInstanceState != null) return
        // Odprli smo se (neposredno ali s tapom na obvestilo): obvestilo za zagon ni vec potrebno.
        Daljinec.pospraviObvestiloZagona(this)
        if (cilj.isBlank() || paket.isBlank()) { finish(); return }
        // Zahteva je bila medtem umaknjena, zamenjana ali je pretekla (tap na staro obvestilo): ne sprasujemo.
        if (!PretokKonec.zahteve.caka(zahteva)) { Log.i(TAG, "Zahteva ne velja vec; zajema zaslona ne zahtevamo."); finish(); return }
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
            // Potrditev velja samo za zahtevo, ki se caka: naprava jo je lahko medtem umaknila ali pa je pretekla.
            if (PretokKonec.zahteve.potrdi(zahteva)) {
                // To deljenje je zacel »Odpri tukaj« naprave [cilj]: samo zanj sme `apps.close` te naprave umakniti aplikacijo.
                // Zapis nastavi zacetek deljenja sam (zadnji parameter).
                LinkMost.zazeniDeljenje(this, resultCode, data, cilj, cilj, cilj)
                Daljinec.nameraZaZagon(this, paket)?.let { namera ->
                    try { startActivity(namera) } catch (e: Throwable) { Log.w(TAG, "Aplikacije ni bilo mogoce odpreti: ${e.message}") }
                }
            } else {
                Log.i(TAG, "Zahteva ne velja vec (umaknjena ali pretecena); deljenja ne zacnemo.")
                // Uporabnik je pravkar dovolil zajem, zgodilo pa se ne bo nic: povemo mu, zakaj.
                val sl = try { resources.configuration.locales[0].language == "sl" } catch (_: Throwable) { false }
                try {
                    android.widget.Toast.makeText(applicationContext, if (sl) "Zahteva ne velja vec. Na drugi napravi znova izberi »Odpri tukaj«."
                        else "The request is no longer valid. Choose \"Open here\" on the other device again.", android.widget.Toast.LENGTH_LONG).show()
                } catch (_: Throwable) { }
            }
        } else {
            if (PretokKonec.zahteve.caka(zahteva)) PretokKonec.zahteve.umakni()
            Log.i(TAG, "Uporabnik ni dovolil zajema zaslona; aplikacije ne odpremo.")
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (odprta?.get() === this) odprta = null
        super.onDestroy()
    }
}
