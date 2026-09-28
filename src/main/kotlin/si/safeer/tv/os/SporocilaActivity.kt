package si.safeer.tv.os

import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import si.safeer.tv.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Safeer OS Sporocila: e-posta in Chatwoot v enem seznamu pogovorov (en pogovor = ena oseba).
 * Enako kot na racunalniku: gesla so v Android Keystore, sinhronizacija ne oznaci pisem kot prebrana.
 */
class SporocilaActivity : OsActivity() {
    private lateinit var koren: View
    private lateinit var shramba: SporocilaShramba
    private lateinit var kanaliVrsta: LinearLayout
    private lateinit var dodajGumb: View
    private lateinit var seznamPlosca: View
    private lateinit var seznam: LinearLayout
    private lateinit var prazno: TextView
    private lateinit var pogovorPlosca: LinearLayout
    private lateinit var pogovorIme: TextView
    private lateinit var pogovorZadeva: TextView
    private lateinit var nazajGumb: View
    private lateinit var sporocilaDrsnik: ScrollView
    private lateinit var sporocilaSeznam: LinearLayout
    private lateinit var odgovor: EditText
    private lateinit var posljiGumb: View

    private val delavec = Executors.newSingleThreadExecutor()
    private val glavna = Handler(Looper.getMainLooper())
    private var siroko = false
    private var izbran: SporocilaShramba.Pogovor? = null
    private var unicena = false
    private val osvezi = object : Runnable {
        override fun run() { sinhroniziraj(); glavna.postDelayed(this, OSVEZI_MS) }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shramba = SporocilaShramba(this)
        siroko = resources.configuration.screenWidthDp >= 720
        setContentView(StranskaVrstica.ovij(this, zgradi(), StranskaVrstica.Razdelek.SPOROCILA))
        narisiKanale(); narisiSeznam(); pokaziPogovor(null)
        // Z daljincem zacnemo v vsebini: prvi pogovor ali, ce jih se ni, Dodaj kanal.
        koren.post { (if (seznam.childCount > 0) seznam.getChildAt(0) else dodajGumb).requestFocus() }
    }

    override fun onStart() {
        super.onStart()
        Ozadje.uporabi(this, koren)
        glavna.post(osvezi)
    }

    override fun onStop() {
        glavna.removeCallbacks(osvezi)
        super.onStop()
    }

    override fun onDestroy() {
        unicena = true
        delavec.shutdownNow()
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!siroko && izbran != null) { pokaziPogovor(null); return }
        super.onBackPressed()
    }

    // ------------------------------------------------------------------ zgradba
    private fun zgradi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val rob = if (siroko) 24 else 14
            setPadding(dp(rob), dp(if (siroko) 20 else 14), dp(rob), dp(14))
            setBackgroundColor(getColor(R.color.os_ozadje))
        }
        koren = root

        val glava = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        glava.addView(ImageView(this).apply { setImageResource(R.drawable.os_ikona_sporocila) },
            LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) })
        val naslovi = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        naslovi.addView(TextView(this).apply {
            text = getString(R.string.os_meni_sporocila)
            setTextColor(getColor(R.color.os_besedilo)); textSize = if (siroko) 27f else 22f; maxLines = 1
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        })
        naslovi.addView(TextView(this).apply {
            text = getString(R.string.os_spor_opis)
            setTextColor(getColor(R.color.os_umirjeno)); textSize = 13f
        })
        glava.addView(naslovi, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dodajGumb = gumb((if (siroko) "" else "+ ") + getString(R.string.os_spor_dodaj)).apply {
            if (!siroko) { textSize = 13f; setPadding(dp(12), dp(9), dp(12), dp(9)) }
            id = View.generateViewId(); setOnClickListener { dodajKanal() }
        }
        glava.addView(dodajGumb)
        root.addView(glava)

        kanaliVrsta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(kanaliVrsta)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14); bottomMargin = dp(12)
        })

        // Seznam pogovorov
        prazno = TextView(this).apply {
            gravity = Gravity.CENTER; setTextColor(getColor(R.color.os_umirjeno)); textSize = 16f
            setPadding(dp(12), dp(40), dp(12), dp(40))
        }
        seznam = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        seznamPlosca = ScrollView(this).apply {
            isFillViewport = true
            addView(LinearLayout(this@SporocilaActivity).apply { orientation = LinearLayout.VERTICAL; addView(prazno); addView(seznam) })
        }

        // Pogovor
        pogovorPlosca = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(getColor(R.color.os_kartica_dvignjena)) }
        }
        val pGlava = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nazajGumb = gumb("‹ " + getString(R.string.os_spor_nazaj)).apply {
            id = View.generateViewId(); setOnClickListener { pokaziPogovor(null) }
        }
        pGlava.addView(nazajGumb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) })
        val pNaslovi = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pogovorIme = TextView(this).apply {
            setTextColor(getColor(R.color.os_besedilo)); textSize = 19f; maxLines = 1
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        pogovorZadeva = TextView(this).apply { setTextColor(getColor(R.color.os_umirjeno)); textSize = 12f; maxLines = 1 }
        pNaslovi.addView(pogovorIme); pNaslovi.addView(pogovorZadeva)
        pGlava.addView(pNaslovi, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pogovorPlosca.addView(pGlava)

        sporocilaSeznam = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        sporocilaDrsnik = ScrollView(this).apply { isFillViewport = true; addView(sporocilaSeznam) }
        pogovorPlosca.addView(sporocilaDrsnik, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val vnos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        odgovor = EditText(this).apply {
            id = View.generateViewId()
            hint = getString(R.string.os_spor_odgovor)
            setTextColor(getColor(R.color.os_besedilo)); setHintTextColor(getColor(R.color.os_umirjeno)); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 1; maxLines = 5; imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(getColor(R.color.os_ozadje)); setStroke(dp(1), getColor(R.color.os_crta))
            }
            setOnKeyListener { _, koda, d ->
                if (koda == KeyEvent.KEYCODE_ENTER && d.isCtrlPressed && d.action == KeyEvent.ACTION_UP) { poslji(); true } else false
            }
        }
        vnos.addView(odgovor, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
        posljiGumb = gumb(getString(R.string.os_spor_poslji)).apply { id = View.generateViewId(); setOnClickListener { poslji() } }
        vnos.addView(posljiGumb)
        pogovorPlosca.addView(vnos, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        odgovor.nextFocusRightId = posljiGumb.id
        posljiGumb.nextFocusLeftId = odgovor.id

        val telo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (siroko) {
            telo.addView(seznamPlosca, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f).apply { marginEnd = dp(14) })
            telo.addView(pogovorPlosca, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f))
            nazajGumb.visibility = View.GONE
        } else {
            telo.addView(seznamPlosca, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            telo.addView(pogovorPlosca, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        root.addView(telo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun gumb(ime: String) = TextView(this).apply {
        text = ime; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
        setTextColor(getColor(R.color.os_besedilo)); textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(18), dp(11), dp(18), dp(11)); setBackgroundResource(R.drawable.os_meni_postavka)
    }

    // ------------------------------------------------------------------ risanje
    private fun besediloStanja(stanje: String): String = when (stanje) {
        "povezan" -> ""
        "napaka:geslo" -> getString(R.string.os_spor_napaka_geslo)
        "napaka:prijava" -> getString(R.string.os_spor_napaka_prijava)
        "napaka:omrezje" -> getString(R.string.os_spor_napaka_omrezje)
        "" -> getString(R.string.os_spor_povezujem)
        else -> if (stanje.startsWith("napaka")) getString(R.string.os_spor_napaka) else ""
    }

    private fun narisiKanale() {
        kanaliVrsta.removeAllViews()
        for (k in shramba.kanali()) {
            val napaka = k.stanje.startsWith("napaka")
            val cip = TextView(this).apply {
                text = (if (k.vrsta == "email") "✉ " else "💬 ") + k.ime + (if (napaka) "  ⚠" else "")
                setTextColor(getColor(if (napaka) R.color.os_opozorilo else R.color.os_besedilo)); textSize = 13f
                isFocusable = true; isClickable = true; maxLines = 1; tag = "kanal|" + k.id
                setPadding(dp(14), dp(8), dp(14), dp(8)); setBackgroundResource(R.drawable.os_meni_postavka)
                contentDescription = k.ime + " " + besediloStanja(k.stanje)
                setOnClickListener { urediKanal(k) }
            }
            kanaliVrsta.addView(cip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
    }

    private fun narisiSeznam() {
        val kanali = shramba.kanali()
        val pogovori = shramba.pogovori()
        seznam.removeAllViews()
        prazno.text = getString(if (kanali.isEmpty()) R.string.os_spor_prazno else R.string.os_spor_ni_pogovorov)
        prazno.visibility = if (pogovori.isEmpty()) View.VISIBLE else View.GONE
        val imena = kanali.associate { it.id to it.ime }
        var prejsnji: View? = null
        for (p in pogovori) {
            val oznacen = izbran?.id == p.id && izbran?.kanalId == p.kanalId
            val kartica = LinearLayout(this).apply {
                id = View.generateViewId(); orientation = LinearLayout.VERTICAL
                tag = p.kanalId + "|" + p.id
                isFocusable = true; isClickable = true; setBackgroundResource(R.drawable.os_ploscica_app)
                isSelected = oznacen
                setPadding(dp(16), dp(12), dp(16), dp(12)); setOnClickListener { pokaziPogovor(p) }
                val vrh = LinearLayout(this@SporocilaActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                vrh.addView(TextView(this@SporocilaActivity).apply {
                    text = p.ime.ifBlank { p.oseba }
                    setTextColor(getColor(R.color.os_besedilo)); textSize = 16f; maxLines = 1
                    typeface = Typeface.create("sans-serif-medium", if (p.neprebrano > 0) Typeface.BOLD else Typeface.NORMAL)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                vrh.addView(TextView(this@SporocilaActivity).apply {
                    text = kratekCas(p.cas); setTextColor(getColor(R.color.os_umirjeno)); textSize = 11f
                })
                if (p.neprebrano > 0) vrh.addView(TextView(this@SporocilaActivity).apply {
                    text = p.neprebrano.toString(); setTextColor(getColor(R.color.os_mint_temna)); textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                    setPadding(dp(7), dp(1), dp(7), dp(1))
                    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(getColor(R.color.os_mint)) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
                addView(vrh)
                addView(TextView(this@SporocilaActivity).apply {
                    text = p.zadnje.replace('\n', ' ').trim()
                    setTextColor(getColor(R.color.os_umirjeno)); textSize = 13f; maxLines = 1
                })
                addView(TextView(this@SporocilaActivity).apply {
                    text = imena[p.kanalId].orEmpty(); setTextColor(getColor(R.color.os_mint)); textSize = 10f; maxLines = 1
                })
            }
            prejsnji?.let { it.nextFocusDownId = kartica.id; kartica.nextFocusUpId = it.id }
            if (siroko) kartica.nextFocusRightId = odgovor.id
            seznam.addView(kartica, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            prejsnji = kartica
        }
        prejsnji?.let { it.nextFocusDownId = it.id }
    }

    private fun pokaziPogovor(p: SporocilaShramba.Pogovor?) {
        izbran = p
        if (!siroko) {
            seznamPlosca.visibility = if (p == null) View.VISIBLE else View.GONE
            pogovorPlosca.visibility = if (p == null) View.GONE else View.VISIBLE
        }
        if (p == null) {
            pogovorIme.text = getString(R.string.os_spor_izberi); pogovorZadeva.text = ""
            sporocilaSeznam.removeAllViews()
            odgovor.isEnabled = false; posljiGumb.isEnabled = false; posljiGumb.alpha = 0.5f
            narisiSeznam()
            return
        }
        pogovorIme.text = p.ime.ifBlank { p.oseba }
        pogovorZadeva.text = if (p.zadeva.isNotBlank()) p.zadeva else p.oseba
        odgovor.isEnabled = true; posljiGumb.isEnabled = true; posljiGumb.alpha = 1f
        narisiSporocila()
        if (p.neprebrano > 0) delavec.execute {
            try { SporocilaKanali.oznaciPrebrano(this, shramba, p.kanalId, p.id) } catch (_: Throwable) {}
            glavna.post { if (!unicena) narisiSeznam() }
        } else narisiSeznam()
        odgovor.requestFocus()
    }

    private fun narisiSporocila() {
        val p = izbran ?: return
        sporocilaSeznam.removeAllViews()
        for (s in shramba.sporocila(p.kanalId, p.id)) {
            val ven = s.smer == "ven"
            val vrstica = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = if (ven) Gravity.END else Gravity.START
            }
            vrstica.addView(TextView(this).apply {
                text = s.besedilo.trim(); textSize = 15f; setTextIsSelectable(false)
                setTextColor(getColor(if (ven) R.color.os_mint_temna else R.color.os_besedilo))
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat(); setColor(getColor(if (ven) R.color.os_mint else R.color.os_ozadje))
                }
                maxWidth = (resources.displayMetrics.widthPixels * (if (siroko) 0.4f else 0.75f)).toInt()
            })
            vrstica.addView(TextView(this).apply {
                text = kratekCas(s.cas); textSize = 10f; setTextColor(getColor(R.color.os_umirjeno))
                setPadding(dp(6), dp(2), dp(6), 0)
            })
            sporocilaSeznam.addView(vrstica, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        sporocilaDrsnik.post { sporocilaDrsnik.fullScroll(View.FOCUS_DOWN) }
    }

    private fun kratekCas(iso: String): String {
        val d = try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso.take(19))
        } catch (_: Throwable) { null } ?: return ""
        val zdaj = Calendar.getInstance(); val c = Calendar.getInstance().apply { time = d }
        val danes = zdaj.get(Calendar.YEAR) == c.get(Calendar.YEAR) && zdaj.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
        return SimpleDateFormat(if (danes) "HH:mm" else if (zdaj.get(Calendar.YEAR) == c.get(Calendar.YEAR)) "d. M." else "d. M. yyyy",
            Locale.getDefault()).format(d)
    }

    // ------------------------------------------------------------------ delo v ozadju
    private fun sinhroniziraj() {
        if (shramba.kanali().isEmpty()) return
        delavec.execute {
            try { SporocilaKanali.sinhroniziraj(this, shramba) } catch (_: Throwable) {}
            glavna.post { if (!unicena) osveziPrikaz() }
        }
    }

    private fun osveziPrikaz() {
        val fokus = currentFocus
        // Seznam se na novo izrise: z daljincem mora fokus ostati na istem pogovoru, ne skociti drugam.
        val fokusKljuc = fokus?.tag as? String
        val prej = izbran
        izbran = prej?.let { i -> shramba.pogovori().firstOrNull { it.id == i.id && it.kanalId == i.kanalId } }
        if (prej != null && izbran == null) { narisiKanale(); pokaziPogovor(null); return }   // kanal odstranjen
        narisiKanale()
        narisiSeznam()
        if (izbran != null) narisiSporocila()
        if (fokus == odgovor) odgovor.requestFocus()
        else if (fokusKljuc != null)
            (seznam.findViewWithTag<View>(fokusKljuc) ?: kanaliVrsta.findViewWithTag(fokusKljuc))?.requestFocus()
    }

    private fun poslji() {
        val p = izbran ?: return
        val besedilo = odgovor.text?.toString().orEmpty().trim()
        if (besedilo.isEmpty()) return
        posljiGumb.isEnabled = false; posljiGumb.alpha = 0.5f
        delavec.execute {
            val napaka = try { SporocilaKanali.poslji(this, shramba, p.kanalId, p.id, besedilo); null } catch (e: Throwable) { e }
            glavna.post {
                if (unicena) return@post
                posljiGumb.isEnabled = true; posljiGumb.alpha = 1f
                if (napaka == null) { odgovor.setText(""); osveziPrikaz() }
                else Toast.makeText(this, getString(R.string.os_spor_ni_poslano) + " " + besediloStanja(SporocilaKanali.razlog(napaka)), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------------ kanali
    private fun polje(namig: Int, vrsta: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        hint = getString(namig); isSingleLine = true; inputType = vrsta
        typeface = Typeface.DEFAULT
        // Gesla hrani Android Keystore; brez ponudbe upravitelja gesel sredi urejanja.
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    private fun dodajKanal() {
        var vrsta = "email"
        val eposta = gumb(getString(R.string.os_spor_eposta))
        val chatwoot = gumb(getString(R.string.os_spor_chatwoot))
        val izbira = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(eposta, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            addView(chatwoot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val naslov = polje(R.string.os_spor_naslov, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val geslo = polje(R.string.os_spor_geslo, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val imap = polje(R.string.os_spor_imap, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val smtp = polje(R.string.os_spor_smtp, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val url = polje(R.string.os_spor_url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val racun = polje(R.string.os_spor_racun, InputType.TYPE_CLASS_NUMBER)
        val zeton = polje(R.string.os_spor_zeton, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val namig = TextView(this).apply { setTextColor(getColor(R.color.os_umirjeno)); textSize = 12f; visibility = View.GONE }
        val napaka = TextView(this).apply { setTextColor(getColor(R.color.os_opozorilo)); textSize = 13f; visibility = View.GONE }
        val eFields = listOf(naslov, geslo, imap, smtp)
        val cFields = listOf(url, racun, zeton)

        fun posodobiNamig() {
            val t = naslov.text?.toString().orEmpty()
            if (!t.contains("@")) { namig.visibility = View.GONE; return }
            val s = SporocilaKanali.streznik(t)
            if (imap.text.isNullOrBlank() || imap.tag == "samodejno") { imap.setText(s.first); imap.tag = "samodejno" }
            if (smtp.text.isNullOrBlank() || smtp.tag == "samodejno") { smtp.setText(s.second); smtp.tag = "samodejno" }
            namig.text = when (s.third) {
                "aplikacije" -> getString(R.string.os_spor_geslo_aplikacije)
                "oauth" -> getString(R.string.os_spor_oauth)
                else -> ""
            }
            namig.visibility = if (namig.text.isNullOrEmpty() || vrsta != "email") View.GONE else View.VISIBLE
        }
        naslov.setOnFocusChangeListener { _, f -> if (!f) posodobiNamig() }
        imap.setOnFocusChangeListener { _, f -> if (f) imap.tag = null }
        smtp.setOnFocusChangeListener { _, f -> if (f) smtp.tag = null }

        fun nastaviVrsto(v: String) {
            vrsta = v
            eposta.isSelected = v == "email"; chatwoot.isSelected = v == "chatwoot"
            eposta.isActivated = v == "email"; chatwoot.isActivated = v == "chatwoot"
            eFields.forEach { it.visibility = if (v == "email") View.VISIBLE else View.GONE }
            cFields.forEach { it.visibility = if (v == "chatwoot") View.VISIBLE else View.GONE }
            napaka.visibility = View.GONE
            posodobiNamig()
        }
        eposta.setOnClickListener { nastaviVrsto("email") }
        chatwoot.setOnClickListener { nastaviVrsto("chatwoot") }

        val polja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(izbira); (eFields + cFields).forEach { addView(it) }; addView(namig); addView(napaka)
        }
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.os_spor_dodaj)
            .setView(ScrollView(this).apply { addView(polja) })
            .setPositiveButton(R.string.os_spor_dodaj, null)
            .setNegativeButton(R.string.os_preklici, null)
            .create()
        okno.setCanceledOnTouchOutside(false)
        okno.setOnShowListener {
            nastaviVrsto("email"); naslov.requestFocus()
            okno.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { gumbDodaj ->
                val id = UUID.randomUUID().toString().take(8)
                val kanal: SporocilaShramba.Kanal
                val skrivnost: String
                if (vrsta == "email") {
                    posodobiNamig()
                    val t = naslov.text?.toString().orEmpty().trim()
                    skrivnost = geslo.text?.toString().orEmpty()
                    if (!t.contains("@") || skrivnost.isEmpty()) { pokaziNapako(napaka, getString(R.string.os_spor_napaka_prijava)); return@setOnClickListener }
                    if (SporocilaKanali.streznik(t).third == "oauth") { pokaziNapako(napaka, getString(R.string.os_spor_oauth)); return@setOnClickListener }
                    val (iH, iP) = gostitelj(imap.text?.toString().orEmpty(), 993)
                    val (sH, sP) = gostitelj(smtp.text?.toString().orEmpty(), 465)
                    kanal = SporocilaShramba.Kanal(id, "email", t, "", JSONObject().put("naslov", t).put("uporabnik", t)
                        .put("imap", iH).put("imap_vrata", iP).put("smtp", sH).put("smtp_vrata", sP))
                } else {
                    val u = url.text?.toString().orEmpty().trim().trimEnd('/')
                    skrivnost = zeton.text?.toString().orEmpty().trim()
                    val r = racun.text?.toString()?.trim()?.toIntOrNull()
                    if (!u.startsWith("https://")) { pokaziNapako(napaka, getString(R.string.os_spor_https)); return@setOnClickListener }
                    if (r == null || skrivnost.isEmpty()) { pokaziNapako(napaka, getString(R.string.os_spor_napaka_prijava)); return@setOnClickListener }
                    kanal = SporocilaShramba.Kanal(id, "chatwoot", u.removePrefix("https://"), "", JSONObject().put("url", u).put("account_id", r))
                }
                gumbDodaj.isEnabled = false
                pokaziNapako(napaka, getString(R.string.os_spor_povezujem), false)
                delavec.execute {
                    val ime = kanal.vrsta + ":" + kanal.id
                    val e = try {
                        if (!SporocilaSkrivnosti.shrani(this, ime, skrivnost)) throw SporocilaKanali.ManjkaSkrivnost()
                        if (kanal.vrsta == "email") SporocilaKanali.preveriEposto(kanal.nastavitve, skrivnost)
                        else SporocilaKanali.preveriChatwoot(this, kanal)
                        shramba.dodajKanal(kanal.copy(stanje = "povezan"))
                        null
                    } catch (t: Throwable) { SporocilaSkrivnosti.pozabi(this, ime); t }
                    glavna.post {
                        if (unicena) return@post
                        if (e == null) {
                            okno.dismiss(); narisiKanale(); narisiSeznam(); sinhroniziraj()
                        } else {
                            gumbDodaj.isEnabled = true
                            pokaziNapako(napaka, besediloStanja(SporocilaKanali.razlog(e)))
                        }
                    }
                }
            }
        }
        Kontroler.pokazi(okno)
        okno.show()
    }

    private fun pokaziNapako(v: TextView, besedilo: String, napaka: Boolean = true) {
        v.text = besedilo; v.visibility = View.VISIBLE
        v.setTextColor(getColor(if (napaka) R.color.os_opozorilo else R.color.os_umirjeno))
    }

    private fun gostitelj(vnos: String, privzeto: Int): Pair<String, Int> {
        val t = vnos.trim()
        val i = t.lastIndexOf(':')
        if (i > 0) t.substring(i + 1).toIntOrNull()?.let { return t.substring(0, i) to it }
        return t to privzeto
    }

    private fun urediKanal(k: SporocilaShramba.Kanal) {
        val stanje = besediloStanja(k.stanje)
        val geslo = polje(if (k.vrsta == "email") R.string.os_spor_geslo else R.string.os_spor_zeton,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val polja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            if (stanje.isNotEmpty()) addView(TextView(this@SporocilaActivity).apply {
                text = stanje; setTextColor(getColor(R.color.os_opozorilo)); textSize = 13f
            })
            addView(geslo)
        }
        val okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(k.ime)
            .setView(polja)
            .setPositiveButton(R.string.os_spor_novo_geslo) { _, _ ->
                val g = geslo.text?.toString().orEmpty()
                if (g.isNotEmpty()) {
                    SporocilaSkrivnosti.shrani(this, k.vrsta + ":" + k.id, g)
                    shramba.stanje(k.id, ""); narisiKanale(); sinhroniziraj()
                }
            }
            .setNeutralButton(R.string.os_spor_odstrani) { _, _ ->
                SporocilaSkrivnosti.pozabi(this, k.vrsta + ":" + k.id)
                shramba.odstraniKanal(k.id)
                if (izbran?.kanalId == k.id) izbran = null
                narisiKanale(); pokaziPogovor(null)
            }
            .setNegativeButton(R.string.os_preklici, null)
            .create()
        Kontroler.pokazi(okno)
        okno.show()
    }

    companion object {
        private const val OSVEZI_MS = 60_000L
    }
}
