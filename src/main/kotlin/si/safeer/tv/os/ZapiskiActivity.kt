package si.safeer.tv.os

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import si.safeer.tv.R
import java.text.DateFormat
import java.util.Date

/** Lokalni zapiski Safeer OS, uporabni z daljincem ali dotikom. */
class ZapiskiActivity : OsActivity() {
    private lateinit var koren: View
    private lateinit var novGumb: View
    private lateinit var iskanje: EditText
    private lateinit var seznam: LinearLayout
    private lateinit var prazno: TextView
    private lateinit var shramba: ZapiskiShramba

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shramba = ZapiskiShramba(getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).getString(KLJUC, "").orEmpty())
        setContentView(StranskaVrstica.ovij(this, zgradi(), StranskaVrstica.Razdelek.ZAPISKI))
        narisi()
    }

    override fun onStart() {
        super.onStart()
        Ozadje.uporabi(this, koren)
    }

    private fun zgradi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(24))
            setBackgroundColor(getColor(R.color.os_ozadje))
        }
        koren = root

        val glava = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        glava.addView(ImageView(this).apply {
            setImageResource(R.drawable.os_ikona_zapiski)
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) })
        val naslovi = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        naslovi.addView(TextView(this).apply {
            text = getString(R.string.os_zapiski)
            setTextColor(getColor(R.color.os_besedilo)); textSize = 27f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        })
        naslovi.addView(TextView(this).apply {
            text = getString(R.string.os_zapiski_opis)
            setTextColor(getColor(R.color.os_umirjeno)); textSize = 13f
        })
        glava.addView(naslovi, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        novGumb = gumb(getString(R.string.os_zapiski_nov)).apply {
            id = View.generateViewId(); nextFocusLeftId = id; setOnClickListener { uredi(null) }
        }
        glava.addView(novGumb)
        root.addView(glava)

        iskanje = EditText(this).apply {
            id = View.generateViewId()
            hint = getString(R.string.os_zapiski_iskanje)
            setTextColor(getColor(R.color.os_besedilo)); setHintTextColor(getColor(R.color.os_umirjeno))
            textSize = 16f; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(getColor(R.color.os_kartica_dvignjena)); setStroke(dp(1), getColor(R.color.os_crta))
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = narisi()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(iskanje, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20); bottomMargin = dp(14)
        })
        novGumb.nextFocusDownId = iskanje.id
        iskanje.nextFocusUpId = novGumb.id
        iskanje.nextFocusLeftId = iskanje.id

        prazno = TextView(this).apply {
            text = getString(R.string.os_zapiski_prazno); gravity = Gravity.CENTER
            setTextColor(getColor(R.color.os_umirjeno)); textSize = 16f; setPadding(0, dp(48), 0, dp(48))
        }
        seznam = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val vsebina = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(prazno); addView(seznam) }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(vsebina) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun gumb(ime: String) = TextView(this).apply {
        text = ime; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
        setTextColor(getColor(R.color.os_besedilo)); textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(18), dp(11), dp(18), dp(11)); setBackgroundResource(R.drawable.os_meni_postavka)
    }

    private fun narisi() {
        if (!::seznam.isInitialized || !::shramba.isInitialized) return
        val zapiski = shramba.seznam(if (::iskanje.isInitialized) iskanje.text?.toString().orEmpty() else "")
        seznam.removeAllViews()
        prazno.visibility = if (zapiski.isEmpty()) View.VISIBLE else View.GONE
        var prejsnji: View? = null
        for (z in zapiski) {
            val kartica = LinearLayout(this).apply {
                id = View.generateViewId(); orientation = LinearLayout.VERTICAL
                isFocusable = true; isClickable = true; setBackgroundResource(R.drawable.os_ploscica_app)
                setPadding(dp(18), dp(14), dp(18), dp(14)); setOnClickListener { uredi(z) }
                addView(TextView(this@ZapiskiActivity).apply {
                    text = z.naslov.ifBlank { getString(R.string.os_zapiski_brez_naslova) }
                    setTextColor(getColor(R.color.os_besedilo)); textSize = 17f; maxLines = 1
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                addView(TextView(this@ZapiskiActivity).apply {
                    text = z.vsebina.replace('\n', ' ').ifBlank { getString(R.string.os_zapiski_brez_vsebine) }
                    setTextColor(getColor(R.color.os_umirjeno)); textSize = 13f; maxLines = 2
                })
                addView(TextView(this@ZapiskiActivity).apply {
                    text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(z.spremenjen))
                    setTextColor(getColor(R.color.os_mint)); textSize = 11f; gravity = Gravity.END
                })
            }
            prejsnji?.let { it.nextFocusDownId = kartica.id; kartica.nextFocusUpId = it.id }
                ?: run { kartica.nextFocusUpId = iskanje.id; iskanje.nextFocusDownId = kartica.id }
            seznam.addView(kartica, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
            prejsnji = kartica
        }
        prejsnji?.let { it.nextFocusDownId = it.id }
        if (prejsnji == null) iskanje.nextFocusDownId = iskanje.id
    }

    private fun uredi(zapisek: ZapiskiShramba.Zapisek?) {
        val naslov = EditText(this).apply {
            hint = getString(R.string.os_zapiski_naslov); setSingleLine(); setText(zapisek?.naslov.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val vsebina = EditText(this).apply {
            hint = getString(R.string.os_zapiski_vsebina); setText(zapisek?.vsebina.orEmpty()); gravity = Gravity.TOP
            minLines = 6; maxLines = 12
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val polja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0); addView(naslov); addView(vsebina)
        }
        val graditelj = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (zapisek == null) R.string.os_zapiski_nov else R.string.os_zapiski_uredi)
            .setView(polja)
            .setPositiveButton(R.string.os_zapiski_shrani) { _, _ ->
                shramba.shrani(zapisek?.id, naslov.text?.toString().orEmpty(), vsebina.text?.toString().orEmpty())
                zapisi(); narisi()
            }
            .setNegativeButton(R.string.os_preklici, null)
        if (zapisek != null) graditelj.setNeutralButton(R.string.os_zapiski_izbrisi) { _, _ -> potrdiBrisanje(zapisek) }
        val okno = Kontroler.pokazi(graditelj.create())
        okno.setOnShowListener {
            naslov.requestFocus()
            naslov.setSelection(naslov.text?.length ?: 0)
            naslov.postDelayed({
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(naslov, InputMethodManager.SHOW_IMPLICIT)
            }, 180)
        }
        okno.show()
    }

    private fun potrdiBrisanje(zapisek: ZapiskiShramba.Zapisek) {
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.os_zapiski_izbrisi)
            .setMessage(R.string.os_zapiski_izbrisi_vprasanje)
            .setPositiveButton(R.string.os_zapiski_izbrisi) { _, _ -> shramba.izbrisi(zapisek.id); zapisi(); narisi() }
            .setNegativeButton(R.string.os_preklici, null)
            .create()
        okno.setOnShowListener { okno.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus() }
        Kontroler.pokazi(okno)
        okno.show()
    }

    private fun zapisi() {
        getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit().putString(KLJUC, shramba.kodirano()).apply()
    }

    companion object {
        private const val NASTAVITVE = "safeer_zapiski"
        private const val KLJUC = "zapiski"
    }
}
