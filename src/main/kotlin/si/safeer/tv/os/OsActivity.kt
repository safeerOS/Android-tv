package si.safeer.tv.os

import android.app.Activity
import android.app.ActivityOptions
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import si.safeer.tv.R

/**
 * Skupna osnova zaslonov Safeer OS: poskrbi, da igralni plosek dela povsod enako (glej
 * [Kontroler]). Zaslon, ki hoce kaksen gumb zase, prepise [plosekDejanje] in vrne true.
 */
open class OsActivity : Activity() {

    /** Uporabnikova tema (Nastavitve › Videz) - nastavljena pred izrisom; celozaslonski zasloni dobijo crno razlicico. */
    @android.annotation.SuppressLint("SourceLockedOrientationActivity")   // samo preizkusna gradnja »TV nacin«
    override fun onCreate(savedInstanceState: Bundle?) {
        val celozaslonski = this is PredvajanjeActivity || this is PredvajalnikActivity || this is SlikaActivity
        Tema.uporabi(this, predvajalnik = celozaslonski)
        // Predvajalnik ima crno razlicico in se ne narise znova (ustavil bi predvajanje).
        temaOb = if (celozaslonski) null else Tema.izbrana(this).id
        // Preizkusna gradnja »TV nacin« (glej [Naprava]): lezeci zaslon kot na televizorju. V izdajah se to ne izvede.
        if (si.safeer.tv.BuildConfig.TV_NACIN_PREIZKUS) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        super.onCreate(savedInstanceState)
    }

    // TRIM_MEMORY_RUNNING_* in COMPLETE: opusceni v API 35, Android 9-13 (npr. TV z Androidom 11) jih se posilja.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_MODERATE) {
            LahkiWebViewPool.sprostiProstega()
            GlasbaActivity.sprostiSlike(level)
        }
        // Vmesnik skrit (UI_HIDDEN in vec) ali malo pomnilnika (RUNNING_LOW/CRITICAL): se seznami Medijev (naloga 54).
        // Naloga 92: s predpomnilnikom na USB ze od RUNNING_MODERATE, tudi strani katalogov kanalov (kopija je na USB).
        val usb = UsbPredpomnilnik.mejaUsb() > 0L
        if (PredpomnilnikPravila.sprostiPodatkeOb(level, usb)) GlasbaActivity.sprostiPodatke()
        if (PredpomnilnikPravila.sprostiStraniKanalovOb(level, usb)) Stremio.sprostiStraniKanalov()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        LahkiWebViewPool.sprostiProstega()
        GlasbaActivity.sprostiSlike()
        if (UsbPredpomnilnik.mejaUsb() > 0L) Stremio.sprostiStraniKanalov()
    }

    /** Vsi zasloni Safeer OS govorijo jezik, ki ga je uporabnik izbral
     *  (enako kot brskalnik); pri "samodejno" ostane jezik televizorja. */
    override fun attachBaseContext(newBase: android.content.Context) {
        jezikOb = si.safeer.tv.JezikVmesnika.izbrani(newBase)
        super.attachBaseContext(Naprava.zaslonTelevizorja(si.safeer.tv.JezikVmesnika.vKontekstu(newBase)))
    }

    /**
     * Jezik, v katerem je zaslon narisan. Ko uporabnik v Nastavitvah izbere drugega, se zasloni za
     * njimi (domaci zaslon) ob vrnitvi narisejo znova - prej so ostali v starem jeziku.
     */
    private var jezikOb: String? = null

    /**
     * Tema, s katero je zaslon narisan. Pregled 5. 11. 2026: izbira v Videzu se je uveljavila samo na zaslonu Videz -
     * Nastavitve in domaci zaslon za njim so ostali v stari temi do ponovnega zagona.
     */
    private var temaOb: String? = null

    private val palica by lazy { Kontroler.Palica(this) }

    /** Po setContentView: vsebina se odmakne od sistemskih vrstic (Android 15+, glej [Robovi]). */
    override fun onContentChanged() {
        super.onContentChanged()
        Robovi.uporabi(this)
    }

    /** Zaslon lahko gumb prevzame; privzeto ga prepusti skupnemu prevodu. */
    open fun plosekDejanje(koda: Int): Boolean = false

    /** Zaslon lahko ob prvi uporabi ploscka osvezi svojo pomoc; privzeto ni kaj osvezevati. */
    open fun plosekZaznan() {}

    override fun dispatchKeyEvent(dogodek: KeyEvent): Boolean {
        if (zablodelaPotrditev(dogodek)) return true
        if (stranskaVrstica()?.prestrezi(dogodek) == true) return true
        if (Kontroler.jeDokazPloska(dogodek) &&
            Kontroler.zabelezi(this, dogodek.device)) plosekZaznan()
        if (Kontroler.tipka(this, dogodek) { koda -> plosekDejanje(koda) }) return true
        return super.dispatchKeyEvent(dogodek)
    }

    /** Nazaj iz glavnega zaslona razdelka vedno vrne Safeer OS Domov. V samostojnem Safeer Predvajalniku
     *  Domov Safeer OS ni del aplikacije: Nazaj iz razdelka vrne v Medije, iz Medijev pa zapusti aplikacijo.
     *  Televizor: Nazaj iz vsebine najprej v stransko vrstico (kot sistemski zaganjalnik), sele iz nje naprej. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (stranskaVrstica()?.nazajVMeni() == true) return
        val samoPredvajalnik = si.safeer.tv.BuildConfig.FLAVOR == "predvajalnik"
        if (samoPredvajalnik) {
            if (this !is GlasbaActivity && stranskaVrstica() != null) {
                try {
                    startActivity(android.content.Intent(this, GlasbaActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    finish()
                    return
                } catch (_: Throwable) { }
            }
            super.onBackPressed()
            return
        }
        if (!ZaganjalnikActivity.jeDomaci(this) && stranskaVrstica() != null) {
            try {
                startActivity(android.content.Intent(this, ZaganjalnikActivity.domov(this))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                finish()
                return
            } catch (_: Throwable) { }
        }
        super.onBackPressed()
    }

    override fun onConfigurationChanged(nova: android.content.res.Configuration) {
        super.onConfigurationChanged(nova)
        stranskaVrstica()?.prilagodiSirino()
    }

    private fun stranskaVrstica(): StranskaVrstica? = StranskaVrstica.na(this)

    override fun onGenericMotionEvent(dogodek: MotionEvent): Boolean {
        if (Kontroler.jeOdklon(dogodek) &&
            Kontroler.zabelezi(this, dogodek.device)) plosekZaznan()
        if (palica.dogodek(dogodek)) return true
        return super.onGenericMotionEvent(dogodek)
    }

    // ------------------------------------------------------------------ koda za novo napravo

    /** Nova naprava se povezuje: kodo pokazemo cez karkoli je na zaslonu (glej KodaNaZaslonu). */
    private val koda by lazy { KodaNaZaslonu(this) }

    override fun onResume() {
        super.onResume()
        if (jezikOb != null && si.safeer.tv.JezikVmesnika.izbrani(this) != jezikOb) { recreate(); return }
        if (temaOb != null && Tema.izbrana(this).id != temaOb) { recreate(); return }
        viden = SystemClock.uptimeMillis()
        pritisnjene.clear()
        koda.zacni()
        // "Poslji na napravo": ponudba druge naprave se pokaze kot tiha pasica na zaslonu v ospredju (ne kot okno).
        Predaja.prikaz = { po -> if (!isFinishing) PredajaObvestilo.pasica(this, po) }
        Predaja.cakajoca?.takeIf { it.velja() }?.let { PredajaObvestilo.umakni(this); PredajaObvestilo.pasica(this, it) }
        // Prosnja za poln dostop (ozji krog): tiha pasica Dovoli / Zavrni; caka, dokler uporabnik ne odloci ali ne poteče.
        KrogProsnja.prikaz = { c -> runOnUiThread { if (!isFinishing) pokaziKrogProsnjo(c) } }
        KrogProsnja.cakajoca()?.let { pokaziKrogProsnjo(it) }
        // Naloga 42 C: novo sporocilo je tiho opozorilo cez ta zaslon (ne vzame fokusa, ne ustavi predvajanja). Del
        // novega zaganjalnika (beta): s starim ostane vse kot prej.
        KlepetLinka.prikaz = if (!ZaganjalnikBeta.vklopljen(this)) null
            else { _: String, ime: String, besedilo: String -> runOnUiThread { SporociloPasica.pokazi(this, ime, besedilo) } }
    }

    // ------------------------------------------------------------------ zablodel OK

    /** Kdaj je zaslon postal viden (onResume). */
    private var viden = 0L
    /** Potrditvene tipke, katerih pritisk je videl prav ta zaslon. */
    private val pritisnjene = HashSet<Int>()

    /**
     * OK, ki ga zaslon ni videl sam, ne sme nicesar izbrati.
     *
     * 21. 9. 2026: »Vse aplikacije« na domacem zaslonu je odprl seznam, ta pa v trenutku brskalnik
     * (prvo ploscico) - drugi OK (odboj tipke ali ponoven pritisk, ker se zaslon ni odzval takoj)
     * je padel ze na nov zaslon. Zato pritisk v prvih [ZASCITA_MS] po odprtju ne steje, spust
     * brez pritiska na tem zaslonu pa tudi ne (pritisk je bil na prejsnjem zaslonu).
     */
    private fun zablodelaPotrditev(d: KeyEvent): Boolean {
        val k = d.keyCode
        if (k != KeyEvent.KEYCODE_DPAD_CENTER && k != KeyEvent.KEYCODE_ENTER &&
            k != KeyEvent.KEYCODE_NUMPAD_ENTER && k != KeyEvent.KEYCODE_BUTTON_A) return false
        return when (d.action) {
            KeyEvent.ACTION_DOWN -> if (d.repeatCount == 0) {
                if (SystemClock.uptimeMillis() - viden < ZASCITA_MS) true else { pritisnjene.add(k); false }
            } else k !in pritisnjene
            KeyEvent.ACTION_UP -> !pritisnjene.remove(k)
            else -> false
        }
    }

    /**
     * Aplikacija naj zraste iz ploscice, ki jo je odprla - kot pri zaganjalniku televizorja -
     * namesto trdega reza na njen prazen zacetni zaslon, dokler se ne nalozi.
     */
    fun izPloscice(izvor: View?): Bundle? = try {
        if (izvor == null || izvor.width <= 0 || izvor.height <= 0) null
        else ActivityOptions.makeScaleUpAnimation(izvor, 0, 0, izvor.width, izvor.height).toBundle()
    } catch (_: Throwable) { null }

    companion object {
        /** Po odprtju zaslona OK toliko casa ne steje (odboj tipke, dvojni pritisk). */
        const val ZASCITA_MS = 500L

        /**
         * Lastnik 28. 10. 2026: TV ima 2 GB sistemskega RAM-a.
         * Z vklopom `android:largeHeap="true"` v manifestu (okusi/os/AndroidManifest.xml)
         * sistem dvigne mejo delovnega pomnilnika (ART heap) s privzetih 192 MB na 512 MB (dalvik.vm.heapsize),
         * kar preprecuje sesutja (OOM) ob socasnem delovanju zaganjalnika, predvajalnika in predpomnilnikov ikon.
         */
        fun mejaPomnilnikaMb(context: android.content.Context): Int {
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return 192
            val jeLarge = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_LARGE_HEAP) != 0
            return if (jeLarge) am.largeMemoryClass else am.memoryClass
        }
    }

    /** Zaslon ni vec viden: prost (neuporabljen) lahki WebView takoj unicimo - ne caka na pomnilniski pritisk. */
    override fun onStop() {
        super.onStop()
        LahkiWebViewPool.sprostiProstega()
    }

    private fun pokaziKrogProsnjo(c: KrogProsnja.Cakajoca) {
        PredajaObvestilo.pasicaSplosna(this, getString(R.string.os_krog_prosnja, c.ime), getString(R.string.os_krog_dovoli),
            getString(R.string.os_krog_zavrni), { KrogProsnja.dovoli(this, c) }, { KrogProsnja.zavrni(c) })
    }

    override fun onPause() {
        koda.ustavi()
        palica.ustavi()
        if (Predaja.prikaz != null) { Predaja.prikaz = null; PredajaObvestilo.umakniPasico() }
        KrogProsnja.prikaz = null
        KlepetLinka.prikaz = null
        SporociloPasica.umakniZdaj()
        super.onPause()
    }
}
