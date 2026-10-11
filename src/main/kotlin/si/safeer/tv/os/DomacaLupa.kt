package si.safeer.tv.os

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import si.safeer.tv.R

/**
 * Iskalna lupa na vrhu domacega zaslona Safeer OS (TV, tablica, telefon; lastnik, 11. 10. 2026): napises ali poves.
 * Razume oboje:
 *  - »Jungle Book na TV«, »Sintel na racunalniku« - prikaz na drugi napravi v Linku ([PrikaziNa], media.open);
 *  - vse drugo je iskanje: spletni naslov odpre brskalnik, ostalo isce Safeer Media (vsi viri in naprave v Linku).
 * Mikrofon je sistemsko prepoznavanje govora (na Androidu podpira slovenscino).
 */
object DomacaLupa {
    const val GLAS = 7301

    /** Vstavi lupo nad vrsto velikih kartic; vrne polje ali null, ce postavitev vrste nima. */
    fun vstavi(a: Activity): EditText? {
        val sidro = a.findViewById<View>(R.id.vrstaVelikeKartice) ?: return null
        val stars = sidro.parent as? ViewGroup ?: return null
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), a.resources.displayMetrics).toInt() }
        val tv = a.packageManager.hasSystemFeature("android.software.leanback")
        val polje = EditText(a).apply {
            hint = a.getString(R.string.os_lupa_namig)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
            background = null
            setTextColor(a.getColor(R.color.os_besedilo)); setHintTextColor(a.getColor(R.color.os_umirjeno))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tv) 18f else 16f)
            setPadding(dp(14), dp(10), dp(8), dp(10))
            isFocusable = true; isFocusableInTouchMode = true
            // TV: fokus z daljincem polja ne odpre; OK/Enter odpre tipkovnico (kot iskanje v Safeer Media).
            if (tv) showSoftInputOnFocus = false
            setOnEditorActionListener { _, akcija, dogodek ->
                val enter = dogodek?.keyCode == KeyEvent.KEYCODE_ENTER && dogodek.action == KeyEvent.ACTION_UP
                if (akcija == EditorInfo.IME_ACTION_SEARCH || akcija == EditorInfo.IME_ACTION_DONE || enter) {
                    izvedi(a, text.toString()); true
                } else false
            }
            if (tv) setOnKeyListener { v, koda, dogodek ->
                if (dogodek.action == KeyEvent.ACTION_DOWN && (koda == KeyEvent.KEYCODE_DPAD_CENTER || koda == KeyEvent.KEYCODE_ENTER)) {
                    (v as EditText).showSoftInputOnFocus = true
                    (a.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0)
                    true
                } else false
            }
        }
        val mikrofon = ImageButton(a).apply {
            setImageResource(R.drawable.os_ikona_mikrofon)
            contentDescription = a.getString(R.string.os_lupa_glas)
            setColorFilter(a.getColor(R.color.os_besedilo))
            background = null
            setPadding(dp(12), dp(10), dp(14), dp(10))
            isFocusable = true
            setOnClickListener { glas(a) }
        }
        val vrsta = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.os_iskanje)
            addView(polje, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(mikrofon, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            tag = "k:domaca_lupa"
        }
        val mere = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10); bottomMargin = dp(14) }
        stars.addView(vrsta, stars.indexOfChild(sidro), mere)
        return polje
    }

    /**
     * Tablica in telefon ze imata iskalno vrstico na vrhu (lastnik, 11. 10. 2026: dve lupi sta odvec).
     * Obstojeca vrstica dobi isto vedenje kot lupa ([izvedi]) in gumb za mikrofon; druge lupe ne vstavimo.
     * Prazen Enter pokliče [prazno] (npr. odpre brskalnik).
     */
    fun prevzemi(a: Activity, polje: EditText, okvir: ViewGroup, prazno: () -> Unit) {
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), a.resources.displayMetrics).toInt() }
        polje.hint = a.getString(R.string.os_lupa_namig)
        polje.setOnEditorActionListener { v, akcija, dogodek ->
            val enter = dogodek?.keyCode == KeyEvent.KEYCODE_ENTER && dogodek.action == KeyEvent.ACTION_DOWN
            if (akcija == EditorInfo.IME_ACTION_SEARCH || akcija == EditorInfo.IME_ACTION_DONE || enter) {
                val niz = v.text.toString().trim()
                (a.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
                if (niz.isEmpty()) prazno() else izvedi(a, niz)
                true
            } else false
        }
        if (polje.layoutParams is LinearLayout.LayoutParams) {
            // Polje je bilo match_parent: zdaj deli vrstico z mikrofonom.
            polje.layoutParams = (polje.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f }
        }
        if (okvir.findViewWithTag<View>("k:domaca_lupa_glas") != null) return
        val mikrofon = ImageButton(a).apply {
            setImageResource(R.drawable.os_ikona_mikrofon)
            contentDescription = a.getString(R.string.os_lupa_glas)
            setColorFilter(polje.currentTextColor)
            background = null
            setPadding(dp(10), dp(4), 0, dp(4))
            isFocusable = true
            tag = "k:domaca_lupa_glas"
            setOnClickListener { glas(a) }
        }
        okvir.addView(mikrofon, LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.MATCH_PARENT))
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

    fun izvedi(a: Activity, niz: String) {
        val q = niz.trim()
        if (q.length < 2) return
        PrikaziNa.razcleni(q)?.takeIf { it.naslov.isNotBlank() }?.let { PrikaziNa.izberiInPoslji(a, it); return }
        if (jeNaslov(q)) {
            val url = if (q.startsWith("http://") || q.startsWith("https://")) q else "https://$q"
            try { a.startActivity(Brskalnik.namera(a).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url))); return } catch (_: Throwable) { }
        }
        a.startActivity(Intent(a, GlasbaActivity::class.java).putExtra(GlasbaActivity.ISKANJE_BESEDA, q))
    }

    /** Spletni naslov (domena s piko, brez presledkov) - gre v brskalnik, ne v iskanje medijev. */
    fun jeNaslov(q: String): Boolean =
        !q.contains(' ') && (q.startsWith("http://") || q.startsWith("https://") ||
            Regex("^[\\p{L}\\p{N}-]+(\\.[\\p{L}\\p{N}-]+)*\\.[a-z]{2,}(/.*)?$", RegexOption.IGNORE_CASE).matches(q))
}
