package si.safeer.tv.os

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.speech.RecognizerIntent
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import si.safeer.tv.R

/**
 * Iskalna lupa na vrhu domacega zaslona Safeer OS (TV, tablica, telefon; lastnik, 11. 10. 2026): napises ali poves.
 * Razume oboje:
 *  - »Jungle Book na TV«, »Sintel na racunalniku« - prikaz na drugi napravi v Linku ([PrikaziNa], media.open);
 *  - vse drugo je iskanje: spletni naslov odpre brskalnik, ostalo isce Safeer Media (vsi viri in naprave v Linku).
 * Mikrofon je sistemsko prepoznavanje govora (na Androidu podpira slovenscino).
 *
 * Kompaktna lupa (lastnik, 19. 10. 2026): dokler je ne rabis, vsebini ne jemlje prostora - na ozkem zaslonu je samo
 * ikona z mikrofonom, na sirokem in na televizorju majhno polje v glavi. Razpre se ob dotiku, fokusu daljinca ali
 * tipkanju (crka kjerkoli na domacem zaslonu gre v lupo); mikrofon (pomocnik: »… na TV«) je vedno en dotik stran.
 * Kdor hoce lupo vedno odprto, to izbere v Nastavitvah ([vednoOdprta]).
 */
object DomacaLupa {
    const val GLAS = 7301
    /** Oznaka iskalnega polja lupe (tipkanje kjerkoli na domacem zaslonu, preizkusi). */
    const val OZNAKA_POLJA = "k:lupa_polje"
    private const val KLJUC_ODPRTA = "lupa_odprta"
    /** Sirina zaprte lupe na televizorju in sirokem zaslonu na dotik (majhno polje z namigom). */
    private const val KOMPAKTNA_TV_DP = 300
    private const val KOMPAKTNA_SIROKA_DP = 260

    /**
     * Krmilo lupe je shranjeno na njenem polju (oznaka s kljucem), zato zivi in umre z zaslonom. Prej ga je drzala
     * staticna WeakHashMap: vrednost (Krmilo) mocno drzi svoj kljuc (polje), zato vnos ni nikoli izginil - vsak zasuk
     * telefona (DomovTabletActivity zgradi postavitev znova) je v pomnilniku pustil celo staro drevo pogledov,
     * vsaka nova dejavnost pa staro dejavnost (WP28 S, 19. 10. 2026: 12 zasukov, Views 153 -> 1917).
     */
    private fun krmiloPolja(v: View?): Krmilo? = v?.getTag(R.id.lupa_krmilo) as? Krmilo

    fun vednoOdprta(c: Context): Boolean =
        c.getSharedPreferences(StranskaVrstica.SHRAMBA, Context.MODE_PRIVATE).getBoolean(KLJUC_ODPRTA, false)

    fun nastaviVednoOdprta(c: Context, da: Boolean) {
        c.getSharedPreferences(StranskaVrstica.SHRAMBA, Context.MODE_PRIVATE).edit().putBoolean(KLJUC_ODPRTA, da).apply()
    }

    private fun dp(a: Activity, v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), a.resources.displayMetrics).toInt()

    /**
     * Zaprta ali razprta lupa. [okvir] je vrstica lupe (ikona, polje, mikrofon) v vodoravni glavi; [kompaktnaDp] je
     * sirina zaprte lupe (0 = samo ikona in mikrofon, polje skrito); [umakni] se ob razprtju skrije (npr. stanje Linka
     * na ozkem zaslonu), da lupa dobi celo glavo - glava ostane enako visoka, vsebina pod njo ne poskoci.
     */
    private class Krmilo(
        val a: Activity,
        val okvir: LinearLayout,
        val polje: EditText,
        val kompaktnaDp: Int,
        val umakni: List<View>,
    ) {
        private var razprta: Boolean? = null

        /** Izbira »lupa vedno odprta« v Nastavitvah velja takoj, ko se uporabnik vrne na domaci zaslon. */
        private val naFokusOkna = android.view.ViewTreeObserver.OnWindowFocusChangeListener { ima -> if (ima) uveljavi() }

        init {
            polje.setTag(R.id.lupa_krmilo, this)
            // Poslusalec je na opazovalcu OKNA (prezivi zamenjavo postavitve): ob odpetju lupe ga odstranimo, sicer bi
            // okno drzalo staro lupo in z njo staro drevo pogledov.
            okvir.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { v.viewTreeObserver.addOnWindowFocusChangeListener(naFokusOkna) }
                override fun onViewDetachedFromWindow(v: View) { v.viewTreeObserver.removeOnWindowFocusChangeListener(naFokusOkna) }
            })
            if (okvir.isAttachedToWindow) okvir.viewTreeObserver.addOnWindowFocusChangeListener(naFokusOkna)
        }

        fun uveljavi() {
            val zdaj = VrsticaPravila.lupaRazsirjena(okvir.hasFocus(), polje.text, vednoOdprta(a))
            if (zdaj == razprta) return
            val prvic = razprta == null
            razprta = zdaj
            (okvir.parent as? ViewGroup)?.takeIf { !prvic }?.let { android.transition.TransitionManager.beginDelayedTransition(it) }
            polje.visibility = if (zdaj || kompaktnaDp > 0) View.VISIBLE else View.GONE
            val lp = okvir.layoutParams as? LinearLayout.LayoutParams ?: return
            if (zdaj) { lp.width = 0; lp.weight = 1f }
            else { lp.width = if (kompaktnaDp > 0) dp(a, kompaktnaDp) else ViewGroup.LayoutParams.WRAP_CONTENT; lp.weight = 0f }
            okvir.layoutParams = lp
            umakni.forEach { it.visibility = if (zdaj) View.GONE else View.VISIBLE }
            // Zaprta lupa na ozkem zaslonu je gumb: bralnik zaslona jo poimenuje (polje je skrito).
            okvir.contentDescription = if (zdaj) null else a.getString(R.string.os_lupa_namig)
        }

        /** Razpre lupo in postavi fokus v polje; [tipkovnica] jo odpre (dotik), daljinec jo odpre z OK. */
        fun razpri(tipkovnica: Boolean) {
            polje.visibility = View.VISIBLE
            polje.requestFocus()
            uveljavi()
            if (tipkovnica) polje.post {
                (a.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(polje, 0)
            }
        }

        /**
         * Iskanje je koncano ali preklicano: polje se izprazni in lupa se zapre. Daljinec fokus obdrzi (sicer bi ga
         * Android dal prvemu pogledu - meniju); lupa se zapre, ko ga uporabnik premakne drugam.
         */
        fun zapri() {
            (a.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(polje.windowToken, 0)
            zapriTipkovnico(a)
            polje.setText("")
            if (polje.isInTouchMode) polje.clearFocus()
            uveljavi()
        }

        fun poveziFokus(vararg pogledi: View) {
            for (v in pogledi) {
                val prej = v.onFocusChangeListener
                v.setOnFocusChangeListener { p, f -> prej?.onFocusChange(p, f); okvir.post { uveljavi() } }
            }
        }
    }

    private fun krmilo(a: Activity): Krmilo? =
        krmiloPolja(a.window?.decorView?.findViewWithTag<View>(OZNAKA_POLJA))

    /**
     * Televizor: lupa je v glavi domacega zaslona (ob stanju Linka in uri), ne v svoji vrsti nad karticami - vsebina
     * dobi vrsto vec. Ce glave ni, gre nad vrsto velikih kartic kot prej. Vrne polje ali null.
     */
    fun vstavi(a: Activity): EditText? {
        val glava = a.findViewById<View>(R.id.stanjeOkvir)?.parent as? LinearLayout
        val sidro = a.findViewById<View>(R.id.vrstaVelikeKartice)
        val stars = sidro?.parent as? ViewGroup
        if (glava == null && stars == null) return null
        val tv = a.packageManager.hasSystemFeature("android.software.leanback")
        val polje = EditText(a).apply {
            hint = a.getString(R.string.os_lupa_namig)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
            background = null
            tag = OZNAKA_POLJA
            setTextColor(a.osBarva(R.color.os_besedilo)); setHintTextColor(a.osBarva(R.color.os_umirjeno))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tv) 18f else 16f)
            setPadding(dp(a, 14), dp(a, 10), dp(a, 8), dp(a, 10))
            isFocusable = true; isFocusableInTouchMode = true
            // TV: fokus z daljincem polja ne odpre; OK/Enter odpre tipkovnico (kot iskanje v Safeer Media).
            if (tv) showSoftInputOnFocus = false
            setOnEditorActionListener { v, akcija, dogodek ->
                val enter = dogodek?.keyCode == KeyEvent.KEYCODE_ENTER && dogodek.action == KeyEvent.ACTION_UP
                if (akcija == EditorInfo.IME_ACTION_SEARCH || akcija == EditorInfo.IME_ACTION_DONE || enter) {
                    val niz = text.toString()
                    if (niz.isNotBlank()) { izvedi(a, niz); krmiloPolja(v)?.zapri() }
                    true
                } else false
            }
            if (tv) setOnKeyListener { v, koda, dogodek ->
                if (dogodek.action == KeyEvent.ACTION_DOWN && (koda == KeyEvent.KEYCODE_DPAD_CENTER || koda == KeyEvent.KEYCODE_ENTER)) {
                    if (jeFizicnaTipkovnica(a)) {
                        (v as EditText).showSoftInputOnFocus = true
                        (a.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0)
                    } else {
                        odpriTipkovnico(a, v as EditText)
                    }
                    true
                } else false
            }
        }
        val mikrofon = ImageButton(a).apply {
            setImageResource(R.drawable.os_ikona_mikrofon)
            contentDescription = a.getString(R.string.os_lupa_glas)
            setColorFilter(a.osBarva(R.color.os_besedilo))
            // Daljinec: fokus na mikrofonu mora biti viden (mint okvir), ne samo obroba celotne lupe.
            if (tv) setBackgroundResource(R.drawable.os_meni_postavka) else background = null
            setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10))
            isFocusable = true
            setOnClickListener { glas(a) }
        }
        val vrsta = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.os_iskanje)
            // Obroba lupe pod fokusom tudi, ko je fokus na polju ali mikrofonu v njej.
            setAddStatesFromChildren(true)
            addView(polje, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(mikrofon, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            tag = "k:domaca_lupa"
        }
        if (glava != null) {
            // Razprta lupa dobi prostor razmika in kapsule stanja Linka (med iskanjem ni potrebna): z dolgim imenom
            // naprave v kapsuli se lupa sicer sploh ni razsirila (TV, 19. 10. 2026).
            val razmik = glava.getChildAt(0)?.takeIf { it !is ViewGroup && (it.layoutParams as? LinearLayout.LayoutParams)?.weight == 1f }
            val stanje = a.findViewById<View>(R.id.stanjeOkvir)
            glava.addView(vrsta, 0, LinearLayout.LayoutParams(dp(a, KOMPAKTNA_TV_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(a, 16) })
            val k = Krmilo(a, vrsta, polje, KOMPAKTNA_TV_DP, listOfNotNull(razmik, stanje))
            k.poveziFokus(polje, mikrofon)
            k.uveljavi()
        } else {
            val mere = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(a, 10); bottomMargin = dp(a, 14) }
            stars!!.addView(vrsta, stars.indexOfChild(sidro), mere)
        }
        return polje
    }

    /**
     * Tablica in telefon ze imata iskalno vrstico na vrhu (lastnik, 11. 10. 2026: dve lupi sta odvec).
     * Obstojeca vrstica dobi isto vedenje kot lupa ([izvedi]) in gumb za mikrofon; druge lupe ne vstavimo.
     * Prazen Enter pokliče [prazno] (npr. odpre brskalnik). [kompaktno]: zaprta lupa (glej [Krmilo]); [umakni] se
     * skrije, ko je razprta na ozkem zaslonu.
     */
    /**
     * [prednost]: zaslon lahko oddano iskanje prevzame prvi (namizje zaganjalnika odpre aplikacijo, ki ustreza); ce vrne
     * false, gre iskanje naprej kot doslej (naslov v Splet, drugo v Medijski center).
     */
    fun prevzemi(a: Activity, polje: EditText, okvir: ViewGroup, kompaktno: Boolean = false, umakni: List<View> = emptyList(),
                 prednost: ((String) -> Boolean)? = null, zMikrofonom: Boolean = true, prazno: () -> Unit) {
        val dp = { v: Int -> dp(a, v) }
        polje.hint = a.getString(R.string.os_lupa_namig)
        polje.tag = OZNAKA_POLJA
        polje.setOnEditorActionListener { v, akcija, dogodek ->
            val enter = dogodek?.keyCode == KeyEvent.KEYCODE_ENTER && dogodek.action == KeyEvent.ACTION_DOWN
            if (akcija == EditorInfo.IME_ACTION_SEARCH || akcija == EditorInfo.IME_ACTION_DONE || enter) {
                val niz = v.text.toString().trim()
                (a.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
                if (niz.isEmpty()) prazno() else if (prednost?.invoke(niz) != true) izvedi(a, niz)
                // Iskanje je oddano: lupa se zapre in je ob vrnitvi spet samo ikona.
                krmiloPolja(v)?.zapri()
                true
            } else false
        }
        if (polje.layoutParams is LinearLayout.LayoutParams && zMikrofonom) {
            // Polje je bilo match_parent: zdaj deli vrstico z mikrofonom.
            polje.layoutParams = (polje.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f }
        }
        if (zMikrofonom && okvir.findViewWithTag<View>("k:domaca_lupa_glas") == null) {
            val mikrofon = ImageButton(a).apply {
                setImageResource(R.drawable.os_ikona_mikrofon)
                contentDescription = a.getString(R.string.os_lupa_glas)
                setColorFilter(polje.currentTextColor)
                TypedValue().also { tv ->
                    if (a.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) setBackgroundResource(tv.resourceId)
                    else background = null
                }
                setPadding(dp(8), dp(4), dp(8), dp(4))
                isFocusable = true
                tag = "k:domaca_lupa_glas"
                setOnClickListener { glas(a) }
            }
            okvir.addView(mikrofon, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT))
        }
        if (!kompaktno || okvir !is LinearLayout) return
        val siroko = a.resources.configuration.screenWidthDp >= VrsticaPravila.MEJA_PREDALA_DP
        val k = Krmilo(a, okvir, polje, if (siroko) KOMPAKTNA_SIROKA_DP else 0, if (siroko) emptyList() else umakni)
        // Zaprta lupa je gumb: dotik kjerkoli na njej (ikona) jo razpre in odpre tipkovnico.
        okvir.isClickable = true
        okvir.setOnClickListener { k.razpri(tipkovnica = true) }
        k.poveziFokus(polje)
        k.uveljavi()
    }

    /**
     * Natipkan znak na domacem zaslonu (tipkovnica daljinca ali telefona), ko fokus ni v polju: lupa se razpre in znak
     * gre vanjo. Klici iz dispatchKeyEvent; vrne true, ce je tipko porabila.
     */
    fun tipka(a: Activity, d: KeyEvent): Boolean {
        if (d.action != KeyEvent.ACTION_DOWN || a.currentFocus is EditText || !znakTipke(d)) return false
        val k = krmilo(a) ?: return false
        k.razpri(tipkovnica = false)
        k.polje.append(String(Character.toChars(d.unicodeChar)))
        return true
    }

    /** Tipka je natipkan znak za iskanje (stevke daljinca ne, glej [VrsticaPravila.znakZaLupo]). */
    fun znakTipke(d: KeyEvent): Boolean = VrsticaPravila.znakZaLupo(d.unicodeChar,
        polnaTipkovnica = try { d.keyCharacterMap.keyboardType == android.view.KeyCharacterMap.FULL } catch (_: Throwable) { false })

    /**
     * Nazaj ob razprti lupi (tipkovnica je ze skrita): iskanje se preklice in lupa se zapre. Daljinec: Nazaj izbrise
     * natipkano (sicer je crka, ki je po nesreci odprla lupo, ostala v njej); prazna lupa Nazaj ne porabi - fokus gre v
     * stransko vrstico kot iz drugih delov zaslona.
     */
    fun nazaj(a: Activity): Boolean {
        val k = krmilo(a) ?: return false
        // Nazaj najprej zapre vrstico znakov (fokus nazaj v polje), sele nato lupo.
        if (zapriTipkovnico(a)) { k.polje.requestFocus(); return true }
        val prazno = k.polje.text.isNullOrBlank()
        if (!k.polje.isInTouchMode) {
            if (prazno) return false
            k.zapri()
            return true
        }
        if (!k.okvir.hasFocus() && prazno) return false
        if (vednoOdprta(a) && prazno) return false
        k.zapri()
        return true
    }

    /** Dotik zunaj prazne razprte lupe jo zapre (tipkovnica se umakne). Dogodka ne porabi. */
    fun dotik(a: Activity, e: MotionEvent) {
        if (e.actionMasked != MotionEvent.ACTION_DOWN) return
        val k = krmilo(a) ?: return
        if (!k.polje.hasFocus() || !k.polje.text.isNullOrBlank()) return
        val r = Rect()
        if (k.okvir.getGlobalVisibleRect(r) && r.contains(e.rawX.toInt(), e.rawY.toInt())) return
        k.zapri()
    }

    fun glas(a: Activity) {
        val namen = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, a.getString(R.string.os_lupa_namig))
        try { @Suppress("DEPRECATION") a.startActivityForResult(namen, GLAS) }
        catch (_: ActivityNotFoundException) { Toast.makeText(a, R.string.os_mediji_ni_glasovnega, Toast.LENGTH_LONG).show() }
    }

    /** Iz onActivityResult domacega zaslona; true, ce je bil to rezultat lupe. */
    fun naRezultat(a: Activity, zahteva: Int, izid: Int, podatki: Intent?): Boolean {
        if (zahteva != GLAS) return false
        if (izid != Activity.RESULT_OK) return true
        podatki?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { izvedi(a, it) }
        return true
    }

    /**
     * Naloga 42 E: ENA razclemba ukazov za lupo in glas ([LupaPravila.razcleni]) - »<kaj> na <napravo>« (naprava po imenu
     * v ozjem krogu ali po vrsti), vreme, »isci v spletu«, spletni naslov, sicer iskanje v Medijih.
     */
    /**
     * Okno z zadetki in filtri lupe (domaci zaslon, lastnik 2. 11. 2026 19:03): [obsegLupe] je izbira v tej lupi (ni
     * shranjena - vsako novo iskanje zacne na Vse); [okNaPolju] prevzame OK (fokus na prvi zadetek, neposredna stran) in
     * vrne true, sicer izvedi isce po izbiri.
     */
    interface IzbiraLupe {
        fun obsegLupe(): String
        fun okNaPolju(niz: String): Boolean
    }

    fun izvedi(a: Activity, niz: String) {
        val q = niz.trim()
        // Vse poti (OK na polju, Enter tipkovnice, »Isci« na zaslonski tipkovnici) gredo sem - zato se prvi zadetek odpre tu.
        if (a is IzbiraLupe && a.okNaPolju(q)) return
        if (!LupaPravila.okIsce(q, obseg(a))) return
        dodajZgodovino(a, q)
        val u = LupaPravila.razcleni(q, napraveKroga(a))
        when (u.vrsta) {
            LupaPravila.PRIKAZI_NA -> if (u.vsebina.isNotBlank()) { prikaziNa(a, u); return }
            LupaPravila.VREME -> { vreme(a, u); return }
            LupaPravila.ISCI_SPLET -> if (u.vsebina.isNotBlank()) { odpriSplet(a, si.safeer.tv.SmartOmnibox.iskanje(a, u.vsebina)); return }
            LupaPravila.SPLET -> if (odpriSplet(a, u.vsebina)) return
        }
        // Brez ukaza isce tam, kjer je uporabnik izbral (Vse in druga okna z lupo: splet); brez brskalnika Medijski center.
        when (LupaPravila.iskalniObseg(obseg(a))) {
            LupaPravila.OBSEG_YOUTUBE -> if (odpriSplet(a, LupaPravila.youtubeIskanje(q))) return
            LupaPravila.OBSEG_SPLET -> if (odpriSplet(a, si.safeer.tv.SmartOmnibox.iskanje(a, q))) return
        }
        a.startActivity(Intent(a, GlasbaActivity::class.java).putExtra(GlasbaActivity.ISKANJE_BESEDA, q))
    }

    private const val PREFS_LUPE = "safeer_os"
    /** Do 2. 11. 2026 shranjena izbira (»Isci v«); zdaj ni vec shranjena - [pozabiStaroIzbiro] jo pobrise. */
    private const val KLJUC_OBSEG = "obseg_lupe"

    /** Kaj lupa prikaze in kje isce: izbira odprte lupe ([IzbiraLupe]); drugje Vse. */
    fun obseg(c: Context): String = LupaPravila.obseg((c as? IzbiraLupe)?.obsegLupe())

    /** Lastnik 19:03: izbira se ne pomni vec - stara shranjena vrednost ne sme ostati v nastavitvah naprave. */
    fun pozabiStaroIzbiro(c: Context) {
        try {
            val p = c.getSharedPreferences(PREFS_LUPE, Context.MODE_PRIVATE)
            if (p.contains(KLJUC_OBSEG)) p.edit().remove(KLJUC_OBSEG).apply()
        } catch (_: Throwable) {}
    }

    /** Druge naprave v OZJEM krogu (id, ime) - samo te so lahko cilj ukaza po imenu. */
    fun napraveKroga(c: Context): List<Pair<String, String>> = try {
        val link = LinkUpravitelj.pridobi(c.applicationContext)
        link.naprave.filter { !link.jeTaNaprava(it) && OzjiKrog.vKrogu(c, it.id) }.map { it.id to DatotekeActivity.lepoIme(it.ime) }
    } catch (_: Throwable) { emptyList() }

    /** Zadetek skupine YouTube: iskanje na YouTubu v brskalniku (isto kot Enter v izbiri YouTube). */
    fun isciNaYoutubu(a: Activity, q: String) {
        if (q.isBlank()) return
        dodajZgodovino(a, q.trim())
        odpriSplet(a, LupaPravila.youtubeIskanje(q))
    }

    private fun odpriSplet(a: Activity, url: String): Boolean = try {
        a.startActivity(Brskalnik.namera(a).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url))); true
    } catch (_: Throwable) { false }

    private fun prikaziNa(a: Activity, u: LupaPravila.Ukaz) {
        if (u.naprava.isNotEmpty()) {
            val n = LinkUpravitelj.pridobi(a.applicationContext).naprave.firstOrNull { it.id == u.naprava }
            if (n != null) { PrikaziNa.poslji(a, n, u.vsebina); return }
        }
        PrikaziNa.izberiInPoslji(a, PrikaziNa.Cilj(u.vsebina, u.vrsteNaprav))
    }

    /**
     * Vreme: »vreme« ali »vreme v <kraj>« - iskanje v spletu na tej napravi (iskalnik uporabnika); »vreme na TV« ali na
     * napravo po imenu - ista stran se odpre na tisti napravi (ukaz open_url prek Linka, samo naprava v ozjem krogu).
     */
    private fun vreme(a: Activity, u: LupaPravila.Ukaz) {
        val poizvedba = (a.getString(R.string.pk_vreme_beseda) + " " + u.vsebina).trim()
        val url = si.safeer.tv.SmartOmnibox.iskanje(a, poizvedba)
        val link = LinkUpravitelj.pridobi(a.applicationContext)
        val cilji = when {
            u.naprava.isNotEmpty() -> link.naprave.filter { it.id == u.naprava }
            u.vrsteNaprav.isNotEmpty() -> link.naprave.filter { !link.jeTaNaprava(it) && it.platforma in u.vrsteNaprav && OzjiKrog.vKrogu(a, it.id) }
            else -> emptyList()
        }
        if (u.naprava.isEmpty() && u.vrsteNaprav.isEmpty()) { odpriSplet(a, url); return }
        val n = cilji.firstOrNull()
        if (n == null) { Toast.makeText(a, R.string.os_prikazi_na_ni_naprave, Toast.LENGTH_LONG).show(); return }
        val ime = DatotekeActivity.lepoIme(n.ime)
        link.ukaz(n.id, "open_url", org.json.JSONObject().put("url", url).put("title", poizvedba), 15_000,
            LinkOdjemalec.Odgovor { izid, _ ->
                a.runOnUiThread {
                    if (a.isFinishing) return@runOnUiThread
                    Toast.makeText(a, if (izid?.optBoolean("ok") == true) a.getString(R.string.os_prikazi_na_odpira, ime, poizvedba)
                        else a.getString(R.string.os_prikazi_na_napaka, ime), Toast.LENGTH_LONG).show()
                }
            })
    }

    /** Spletni naslov (domena s piko, brez presledkov) - gre v brskalnik, ne v iskanje medijev. */
    fun jeNaslov(q: String): Boolean =
        !q.contains(' ') && (q.startsWith("http://") || q.startsWith("https://") ||
            Regex("^[\\p{L}\\p{N}-]+(\\.[\\p{L}\\p{N}-]+)*\\.[a-z]{2,}(/.*)?$", RegexOption.IGNORE_CASE).matches(q))

    fun jeFizicnaTipkovnica(c: Context): Boolean {
        if (c.resources.configuration.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS) return true
        for (id in android.view.InputDevice.getDeviceIds()) {
            val dev = android.view.InputDevice.getDevice(id)
            if (dev != null && dev.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC) return true
        }
        return false
    }

    fun odpriTipkovnico(a: Activity, polje: EditText) {
        val t = a.window.decorView.findViewWithTag<LinearLayout>("lupaTipkovnica") ?: return
        if (t.visibility == View.VISIBLE) return
        narisiTipkovnico(a, t, polje, stevilke = false)
        t.visibility = View.VISIBLE
        t.post { (t.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.requestFocus() }
    }

    /** Tipk v eni vrsti: pregled 2. 11. 2026 - vse v eni vrsti (40+ tipk) je slo cez rob, vecine ni bilo videti. */
    private const val TIPK_V_VRSTI = 11

    private fun narisiTipkovnico(a: Activity, t: LinearLayout, polje: EditText, stevilke: Boolean) {
        t.removeAllViews()
        var v = LinearLayout(a)
        fun novaVrsta() {
            v = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
            t.addView(v)
        }
        novaVrsta()
        // Jezik vmesnika (JezikVmesnika), ne jezik televizorja.
        val jezik = (a.resources.configuration.locales[0] ?: java.util.Locale.getDefault()).language
        val sumniki = when (jezik) {
            "sl" -> "čšž"
            "de" -> "äöüß"
            "es" -> "ñ"
            "fr" -> "éèêëàâùüçœ"
            "it" -> "àèéìíòóùú"
            else -> ""
        }
        val znaki = if (stevilke) "1234567890!?:;\"'()/@#€%&*+=_~-." else "abcdefghijklmnopqrstuvwxyz$sumniki"
        
        fun tipka(napis: String, sirinaDp: Int, ob: () -> Unit) {
            val btn = TextView(a).apply {
                text = napis
                gravity = Gravity.CENTER
                setTextColor(a.osBarva(R.color.os_besedilo))
                textSize = if (napis.length > 2) 12f else 17f
                setBackgroundResource(R.drawable.os_tipka_velika)
                isFocusable = true
                isClickable = true
                setOnClickListener { ob() }
                setOnKeyListener(View.OnKeyListener { _, koda, e ->
                    if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0 && 
                        (koda == KeyEvent.KEYCODE_DPAD_CENTER || koda == KeyEvent.KEYCODE_ENTER || koda == KeyEvent.KEYCODE_NUMPAD_ENTER || koda == KeyEvent.KEYCODE_BUTTON_A)) {
                        ob(); true
                    } else false
                })
                layoutParams = LinearLayout.LayoutParams((sirinaDp * a.resources.displayMetrics.density).toInt(), (42 * a.resources.displayMetrics.density).toInt()).apply {
                    val m = (2 * a.resources.displayMetrics.density).toInt()
                    setMargins(m, m, m, m)
                }
            }
            v.addView(btn)
        }

        for ((i, z) in znaki.withIndex()) {
            if (i > 0 && i % TIPK_V_VRSTI == 0) novaVrsta()
            tipka(z.toString(), 34) { polje.append(z.toString()) }
        }
        novaVrsta()
        tipka(if (stevilke) "ABC" else "123", 50) {
            narisiTipkovnico(a, t, polje, !stevilke)
            t.post { (t.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.requestFocus() }
        }
        tipka(a.getString(R.string.os_tipk_presledek), 60) { polje.append(" ") }
        tipka(a.getString(R.string.os_tipk_vracalka), 60) {
            val s = polje.text.toString()
            if (s.isNotEmpty()) polje.setText(s.substring(0, s.length - 1))
            polje.setSelection(polje.text.length)
        }
        // Prej pk_lupa_splet (»Isci v spletu: »%1$s«« z nadomestkom) - na tipki je bil viden %1$s.
        tipka(a.getString(R.string.os_glasba_iskanje), 72) {
            val q = polje.text.toString()
            if (q.isNotBlank()) izvedi(a, q)
            zapriTipkovnico(a)
            krmilo(a)?.zapri()
        }
        
    }

    fun tipkovnicaOdprta(a: Activity): Boolean =
        a.window.decorView.findViewWithTag<LinearLayout>("lupaTipkovnica")?.visibility == View.VISIBLE

    /** Skrije vrstico znakov; true, ce je bila odprta. */
    fun zapriTipkovnico(a: Activity): Boolean {
        val t = a.window.decorView.findViewWithTag<LinearLayout>("lupaTipkovnica") ?: return false
        if (t.visibility != View.VISIBLE) return false
        t.visibility = View.GONE
        return true
    }

    /** Samo pravo iskanje (OK/oddaja); smeti in zacetki istega iskanja se ne shranijo (LupaPravila.dodajVZgodovino). */
    private fun dodajZgodovino(c: Context, q: String) {
        val s = c.getSharedPreferences(PREFS_LUPE, Context.MODE_PRIVATE)
        val staro = (s.getString("zgodovina", "") ?: "").split("\n")
        s.edit().putString("zgodovina", LupaPravila.dodajVZgodovino(staro, q).joinToString("\n")).apply()
    }

    /** Zgodovina brez smeti; ce jih je bilo kaj (zapisi pred 2. 11. 2026), se ociscena zapise nazaj. */
    fun beriZgodovino(c: Context): List<String> {
        val s = c.getSharedPreferences(PREFS_LUPE, Context.MODE_PRIVATE)
        val surovo = (s.getString("zgodovina", "") ?: "").split("\n").filter { it.isNotBlank() }
        val ciste = LupaPravila.ocistiZgodovino(surovo)
        if (ciste != surovo) s.edit().putString("zgodovina", ciste.joinToString("\n")).apply()
        return ciste
    }
}
