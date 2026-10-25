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
 *
 * Ce ima Safeer trajno dovoljenje Androida za prikaz (AppOps PROJECT_MEDIA), Android okna ne pokaze: takrat napravo,
 * ki ji uporabnik ni rekel »Vedno dovoli«, vprasa Safeer sam (DeljenjePravila.potrditev, nacrt »dex-upravljanje« A3).
 */
class PretociActivity : Activity() {

    companion object {
        private const val TAG = "SafeerPretoci"
        private const val ZAHTEVA = 4712
        const val EXTRA_CILJ = "si.safeer.tv.link.PRETOCI_CILJ"
        const val EXTRA_PAKET = "si.safeer.tv.link.PRETOCI_PAKET"
        const val EXTRA_ZAHTEVA = "si.safeer.tv.link.PRETOCI_ZAHTEVA"
        const val EXTRA_ODPRTO_TUKAJ = "si.safeer.tv.link.PRETOCI_ODPRTO_TUKAJ"
        private const val STANJE_VPRASANJE = "safeer_vprasanje"

        /** Okno, ki caka na soglasje (najvec eno). */
        @Volatile private var odprta: java.lang.ref.WeakReference<PretociActivity>? = null

        /**
         * Pregled dex-a U2: ali je odprto okno za soglasje (Safeerjevo vprasanje ali sistemsko okno Androida nad njim) - takrat
         * Daljinec zavrne vnos vseh naprav (naprava, ki zahteva zaslon, bi sicer sama tapnila »Dovoli«).
         */
        fun vprasanjeOdprto(): Boolean = odprta?.get()?.let { !it.isFinishing && !it.isDestroyed } == true

        /** Zeton zahteve, za katero se je to okno nazadnje odprlo (A2: ali je neposredni zagon uspel). */
        @Volatile var odprtaZahteva: Int = 0
            private set

        /**
         * [zahteva]: zeton zahteve ([PretokKonec.Zahteve.nova]) - umaknjena ali pretecena zahteva ne zacne deljenja.
         * [odprtoTukaj]: deljenje zacenja »Odpri tukaj« naprave [cilj] (njen `apps.close` sme aplikacijo umakniti z
         * zaslona); false pri nadaljevanju deljenja, ki ga je uporabnik zacel sam (A4).
         */
        fun namera(context: Context, cilj: String, paket: String, zahteva: Int, odprtoTukaj: Boolean = true): Intent =
            Intent(context, PretociActivity::class.java)
                .putExtra(EXTRA_CILJ, cilj)
                .putExtra(EXTRA_PAKET, paket)
                .putExtra(EXTRA_ZAHTEVA, zahteva)
                .putExtra(EXTRA_ODPRTO_TUKAJ, odprtoTukaj)
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
    private var odprtoTukaj = true
    private var vprasanje: android.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cilj = intent?.getStringExtra(EXTRA_CILJ).orEmpty()
        paket = intent?.getStringExtra(EXTRA_PAKET).orEmpty()
        zahteva = intent?.getIntExtra(EXTRA_ZAHTEVA, 0) ?: 0
        odprtoTukaj = intent?.getBooleanExtra(EXTRA_ODPRTO_TUKAJ, true) ?: true
        odprta = java.lang.ref.WeakReference(this)
        odprtaZahteva = zahteva
        if (savedInstanceState != null) {
            // Obrat zaslona med nasim vprasanjem: vprasanje pokazemo znova (sistemsko okno vrne izid samo).
            if (savedInstanceState.getBoolean(STANJE_VPRASANJE, false) && PretokKonec.zahteve.caka(zahteva)) vprasajSafeer()
            return
        }
        // Odprli smo se (neposredno ali s tapom na obvestilo): obvestilo za zagon ni vec potrebno.
        Daljinec.pospraviObvestiloZagona(this)
        if (cilj.isBlank() || paket.isBlank()) { finish(); return }
        // Zahteva je bila medtem umaknjena, zamenjana ali je pretekla (tap na staro obvestilo): ne sprasujemo.
        if (!PretokKonec.zahteve.caka(zahteva)) { Log.i(TAG, "Zahteva ne velja vec; zajema zaslona ne zahtevamo."); finish(); return }
        val potrditev = DeljenjePravila.potrditev(ZaupanjeZaslona.projekcijaDovoljena(this), ZaupanjeZaslona.zaupano(this, cilj))
        Log.i(TAG, "Soglasje za deljenje zaslona: $potrditev")
        if (potrditev == DeljenjePravila.Potrditev.NASA) vprasajSafeer() else zahtevajZajem()
    }

    /** Sistemsko okno za soglasje (ali takojsnja odobritev SystemUI, ce ima Safeer trajno dovoljenje). */
    private fun zahtevajZajem() {
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

    /**
     * A3: Android okna ne bo pokazal (trajno dovoljenje), naprava pa ni zaupana - zaslona brez vprasanja ne dobi.
     * »Vedno dovoli« si zapomni to napravo; preklic: obvestilo deljenja ali Safeer OS › Naprave.
     */
    private fun vprasajSafeer() {
        val ime = Daljinec.imeNaprave(this, cilj)
        try {
            val graditelj = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(si.safeer.tv.R.string.deljenje_potrditev_naslov))
                .setMessage(getString(si.safeer.tv.R.string.deljenje_potrditev_besedilo, ime))
                .setPositiveButton(getString(si.safeer.tv.R.string.deljenje_dovoli)) { _, _ -> zahtevajZajem() }
            // Pregled dex-a U4: »Vedno dovoli« samo napravi iz ozjega kroga, ki se ni zaupana (kot v obvestilu).
            if (ZaupanjeZaslona.ponudi(this, cilj)) graditelj.setNeutralButton(Daljinec.oznakaVednoDovoli(this, cilj)) { _, _ ->
                ZaupanjeZaslona.nastavi(this, cilj, true)
                zahtevajZajem()
            }
            vprasanje = graditelj
                .setNegativeButton(getString(si.safeer.tv.R.string.deljenje_zavrni)) { _, _ -> zavrnjeno() }
                .setOnCancelListener { zavrnjeno() }
                .show()
        } catch (e: Throwable) {
            Log.w(TAG, "Vprasanja ni bilo mogoce pokazati: ${e.message}")
            zavrnjeno()
        }
    }

    /** Uporabnik deljenja ni dovolil: zahteva je umaknjena; deljenje, ki caka na odklep tej napravi, se konca (A4). */
    private fun zavrnjeno() {
        if (PretokKonec.zahteve.caka(zahteva)) PretokKonec.zahteve.umakni()
        if (DeljenjeZaslonaStoritev.cakaNaOdklep && DeljenjeZaslonaStoritev.cilj == cilj) DeljenjeZaslonaStoritev.ustavi(this)
        Log.i(TAG, "Uporabnik ni dovolil zajema zaslona; aplikacije ne odpremo.")
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
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
                // Zapis nastavi zacetek deljenja sam (zadnji parameter). V obvestilu deljenja je ime naprave, ne njen id.
                LinkMost.zazeniDeljenje(this, resultCode, data, cilj, Daljinec.imeNaprave(this, cilj), if (odprtoTukaj) cilj else "")
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
            zavrnjeno()
            return
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STANJE_VPRASANJE, vprasanje?.isShowing == true)
    }

    override fun onDestroy() {
        // Okno se zapira (zamenjana zahteva, obrat): nase vprasanje gre z njim, brez odgovora v imenu uporabnika.
        try { vprasanje?.setOnCancelListener(null); vprasanje?.dismiss() } catch (_: Throwable) { }
        vprasanje = null
        if (odprta?.get() === this) odprta = null
        super.onDestroy()
    }
}
