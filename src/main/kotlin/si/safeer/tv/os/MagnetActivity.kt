package si.safeer.tv.os

import si.safeer.tv.R

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Magnet povezave v Safeer OS: odpre povezavo (iz brskalnika, z druge naprave v Linku ali vpisano),
 * pokaže vsebino, predvaja glasbo ali video že med prenosom, prenese izbrano, pošlje povezavo na drugo
 * napravo ali jo deli z drugimi, in iz lastne datoteke naredi magnet. Motor: [MagnetMotor].
 */
class MagnetActivity : OsActivity() {

    companion object {
        const val EXTRA_URI = "magnet_uri"
        /**
         * Z druge naprave: en sam posnetek ali ena skladba se začne predvajati takoj. Vrednost mora biti
         * [ZETON] tega procesa - dejavnost je izvožena (magnet:), zato tuja aplikacija ali stran s
         * preusmeritvijo ne more sama sprožiti prenosa; pokaže se le seznam in uporabnik pritisne Predvajaj.
         */
        const val EXTRA_SAMODEJNO = "magnet_samodejno"
        val ZETON: String = java.util.UUID.randomUUID().toString()
        private const val IZBERI_DATOTEKO = 7401
    }

    private lateinit var polje: EditText
    private lateinit var sporocilo: TextView
    private lateinit var seznam: LinearLayout
    private val ozadje = Executors.newSingleThreadExecutor()
    private val glavna = Handler(Looper.getMainLooper())
    private val link by lazy { LinkUpravitelj.pridobi(this) }
    private var opis: MagnetMotor.Opis? = null
    private var prenosi: LinearLayout? = null
    private val osvezi = object : Runnable {
        override fun run() { osveziPrenose(); glavna.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.os_activity_magnet)
        Ozadje.uporabi(this, findViewById(R.id.koren))
        polje = findViewById(R.id.polje)
        sporocilo = findViewById(R.id.sporocilo)
        seznam = findViewById(R.id.seznam)
        findViewById<Button>(R.id.odpri).setOnClickListener { preberi(polje.text.toString().trim(), false) }
        obdelajNamero(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        obdelajNamero(intent)
    }

    override fun onResume() { super.onResume(); glavna.post(osvezi) }
    override fun onPause() { super.onPause(); glavna.removeCallbacks(osvezi) }
    override fun onDestroy() { super.onDestroy(); ozadje.shutdown() }

    private fun obdelajNamero(i: Intent?) {
        val uri = i?.getStringExtra(EXTRA_URI) ?: i?.data?.takeIf { it.scheme.equals("magnet", true) }?.toString() ?: ""
        if (uri.isNotBlank()) {
            polje.setText(uri)
            // Z naprave v krogu (žeton) beremo in predvajamo takoj; povezava od drugod ne sproži omrežja,
            // dokler uporabnik ne pritisne Odpri (spletna stran lahko magnet odpre tudi brez klika).
            if (i?.getStringExtra(EXTRA_SAMODEJNO) == ZETON) preberi(uri, true)
            else { narisi(); pokazi(getString(R.string.magnet_pritisni_odpri)); findViewById<android.view.View>(R.id.odpri).requestFocus() }
        } else narisi()
    }

    private fun pokazi(besedilo: String) {
        sporocilo.text = besedilo
        sporocilo.visibility = if (besedilo.isBlank()) View.GONE else View.VISIBLE
    }

    private fun napaka(koda: String?): String = getString(when (koda) {
        "ni_magnet" -> R.string.magnet_napaka_ni_magnet
        "ni_izbranih" -> R.string.magnet_napaka_ni_izbranih
        "ni_metapodatkov" -> R.string.magnet_napaka_ni_metapodatkov
        "ni_predvajljivo" -> R.string.magnet_napaka_ni_predvajljivo
        else -> R.string.magnet_napaka
    })

    private fun vOzadju(delo: () -> Unit) = ozadje.execute {
        try { delo() } catch (e: Throwable) { glavna.post { pokazi(napaka(e.message)) } }
    }

    // ------------------------------------------------------------------ vsebina povezave

    private fun preberi(uri: String, samodejno: Boolean) {
        if (MagnetMotor.hash(uri) == null) { pokazi(napaka("ni_magnet")); return }
        pokazi(getString(R.string.magnet_berem))
        vOzadju {
            val o = MagnetMotor.preberi(this, uri)
            glavna.post {
                if (isFinishing) return@post
                if (opis?.hash != o.hash) potrjene.clear()
                opis = o
                pokazi("")
                narisi()
                if (samodejno) {
                    val predvajljive = o.datoteke.filter { it.predvajljiva }
                    val videi = predvajljive.filter { it.vrsta == "video" }
                    (if (predvajljive.size == 1) predvajljive.first() else videi.singleOrNull())?.let { predvajaj(o.uri, it) }
                }
            }
        }
    }

    private fun gumb(besedilo: String, dejanje: (Button) -> Unit): Button = Button(this).apply {
        text = besedilo
        isAllCaps = false
        setTextColor(osBarva(R.color.os_besedilo))
        background = getDrawable(R.drawable.os_hitri_gumb)
        setPadding(28, 0, 28, 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (44 * resources.displayMetrics.density).toInt())
            .apply { marginEnd = (8 * resources.displayMetrics.density).toInt(); topMargin = (6 * resources.displayMetrics.density).toInt() }
        setOnClickListener { dejanje(this) }
    }

    private fun besedilo(t: String, velikost: Float = 15f, umirjeno: Boolean = false): TextView = TextView(this).apply {
        text = t
        textSize = velikost
        setTextColor(osBarva(if (umirjeno) R.color.os_umirjeno else R.color.os_besedilo))
    }

    private fun vrstica(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = getDrawable(R.drawable.os_kartica_mirna)
        val p = (12 * resources.displayMetrics.density).toInt()
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
    }

    /** Gumbi v vrsti se prelomijo (ožji zaslon telefona): HorizontalScrollView bi skril dejanja. */
    /** Vrsta gumbov, ki se na ozkem zaslonu (telefon) prelomi v novo vrstico, namesto da gumbe stisne. */
    private fun dejanja(): ViewGroup = OvijalnaVrsta(this)

    private fun velikost(b: Long): String = when {
        b >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", b / 1073741824.0)
        b >= 1L shl 20 -> "${b shr 20} MB"
        else -> "${maxOf(1, b shr 10)} kB"
    }

    private fun imeVrste(v: String) = getString(when (v) {
        "video" -> R.string.magnet_vrsta_video; "audio" -> R.string.magnet_vrsta_audio
        "podnapisi" -> R.string.magnet_vrsta_podnapisi; "slika" -> R.string.magnet_vrsta_slika
        "nevarno" -> R.string.magnet_vrsta_nevarno; else -> R.string.magnet_vrsta_drugo
    })

    /** Datoteke, ki so videti kot program, a jih je uporabnik po opozorilu vseeno izbral (za trenutno povezavo). */
    private val potrjene = mutableSetOf<Int>()

    private fun narisi() {
        seznam.removeAllViews()
        opis?.let { o ->
            seznam.addView(besedilo(o.ime, 18f))
            if (o.sumljiv) seznam.addView(besedilo(getString(R.string.magnet_sumljiv), 14f).apply { setTextColor(0xFFF0B060.toInt()) })
            val izbire = mutableListOf<Pair<MagnetMotor.Datoteka, CheckBox>>()
            for (d in o.datoteke) {
                val v = vrstica()
                val izbira = CheckBox(this).apply {
                    text = "${d.ime}  ·  ${velikost(d.velikost)}  ·  ${imeVrste(d.vrsta)}"
                    setTextColor(osBarva(if (d.vrsta == "nevarno") R.color.os_umirjeno else R.color.os_besedilo))
                    isChecked = d.privzetoIzbrana || d.i in potrjene
                    // Morda program: privzeto ne, a uporabnik lahko po opozorilu vseeno izbere.
                    if (d.vrsta == "nevarno") setOnCheckedChangeListener { gumb, izbrano ->
                        if (!izbrano) { potrjene.remove(d.i); return@setOnCheckedChangeListener }
                        if (d.i in potrjene) return@setOnCheckedChangeListener
                        AlertDialog.Builder(this@MagnetActivity).setTitle(R.string.magnet_nevarno_naslov)
                            .setMessage(getString(R.string.magnet_nevarno_opis, d.ime.substringAfterLast('/')))
                            .setPositiveButton(R.string.magnet_vseeno) { _, _ -> potrjene.add(d.i) }
                            .setNegativeButton(android.R.string.cancel) { _, _ -> gumb.isChecked = false }
                            .setOnCancelListener { gumb.isChecked = false }
                            .show()
                    }
                }
                izbire += d to izbira
                v.addView(izbira)
                if (d.predvajljiva) v.addView(dejanja().apply { addView(gumb("▶ " + getString(R.string.magnet_predvajaj)) { predvajaj(o.uri, d) }) })
                seznam.addView(v)
            }
            val d = dejanja()
            d.addView(gumb(getString(R.string.magnet_prenesi_izbrane)) {
                val izbrane = izbire.filter { it.second.isChecked && (it.first.vrsta != "nevarno" || it.first.i in potrjene) }.map { it.first.i }
                if (izbrane.isEmpty()) { pokazi(napaka("ni_izbranih")); return@gumb }
                val potrjeneZdaj = potrjene.toSet()
                vOzadju { MagnetMotor.dodaj(this, o.uri, izbrane, potrjeneZdaj); glavna.post { toast(getString(R.string.magnet_prenasam)); osveziPrenose() } }
            })
            d.addView(gumb(getString(R.string.magnet_poslji)) { posljiNaNapravo(o.uri) })
            d.addView(gumb(getString(R.string.magnet_deli)) { deliZDrugimi(o.uri) })
            seznam.addView(d)
        }
        seznam.addView(besedilo(getString(R.string.magnet_prenosi), 16f).apply { setPadding(0, (18 * resources.displayMetrics.density).toInt(), 0, 0) })
        prenosi = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        seznam.addView(prenosi)
        seznam.addView(dejanja().apply { addView(gumb(getString(R.string.magnet_deli_datoteko)) { izberiDatoteko() }) })
        osveziPrenose()
    }

    // ------------------------------------------------------------------ dejanja

    private fun predvajaj(uri: String, d: MagnetMotor.Datoteka) {
        toast(getString(R.string.magnet_zaganjam))
        vOzadju {
            // Podnapise iz istega torrenta prenesemo zraven (majhni so) in jih ponudimo v predvajalniku.
            val podnapisi = if (d.vrsta == "video") MagnetMotor.podnapisiZa(this, uri, d.i) else emptyList()
            val hash = MagnetMotor.dodaj(this, uri, listOf(d.i) + podnapisi.map { it.i })
            val url = MagnetMotor.tok(this, hash, d.i)
            val seznamPodnapisov = podnapisi.map { p ->
                val (jezik, oznaka) = Podnapisi.jezik(d.ime, p.ime)
                Podnapisi.Podnapis(MagnetMotor.tok(this, hash, p.i), p.ime.substringAfterLast('/'), jezik, oznaka, Podnapisi.mime(p.ime))
            }
            glavna.post {
                val ime = d.ime.substringAfterLast('/').substringBeforeLast('.')
                val sk = Jamendo.Skladba("magnet:$hash:${d.i}", ime, "Magnet", "", url, "", video = d.vrsta == "video",
                    podnapisi = seznamPodnapisov)
                GlasbaStoritev.predvajaj(this, listOf(sk), 0, null)
                startActivity(Intent(this, PredvajanjeActivity::class.java))
            }
        }
    }

    private fun posljiNaNapravo(uri: String) {
        val naprave = link.naprave.filter { it.zmoznosti.contains("magnet") && !link.jeTaNaprava(it) }
        if (naprave.isEmpty()) { toast(getString(R.string.magnet_ni_naprav)); return }
        AlertDialog.Builder(this).setTitle(R.string.magnet_poslji)
            .setItems(naprave.map { it.ime }.toTypedArray()) { _, k ->
                val n = naprave[k]
                link.ukaz(n.id, "magnet.open", JSONObject().put("uri", uri), 15_000, LinkOdjemalec.Odgovor { izid, _ ->
                    toast(if (izid?.optBoolean("ok") == true) getString(R.string.magnet_poslano, n.ime) else getString(R.string.magnet_ni_poslano))
                })
            }.show()
    }

    private fun deliZDrugimi(uri: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, uri),
            getString(R.string.magnet_deli)))
    }

    private fun izberiDatoteko() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IZBERI_DATOTEKO)
        } catch (_: Throwable) { toast(getString(R.string.magnet_napaka)) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(zahteva: Int, rezultat: Int, podatki: Intent?) {
        super.onActivityResult(zahteva, rezultat, podatki)
        val uri = podatki?.data ?: return
        if (zahteva != IZBERI_DATOTEKO || rezultat != RESULT_OK) return
        pokazi(getString(R.string.magnet_pripravljam))
        vOzadju {
            // Kopija v naši mapi: izvirnik ostane nedotaknjen, torrent pa ima stalno pot za oddajanje.
            val ime = (contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "datoteka").replace('/', '_').replace(Regex("^\\.+"), "")
            val cilj = File(File(MagnetMotor.mapa(this), "Deljeno").apply { mkdirs() }, ime)
            contentResolver.openInputStream(uri)!!.use { v -> cilj.outputStream().use { v.copyTo(it) } }
            val magnet = MagnetMotor.deli(this, cilj)
            glavna.post {
                pokazi(getString(R.string.magnet_deljeno, ime))
                AlertDialog.Builder(this).setTitle(ime).setMessage(magnet)
                    .setPositiveButton(R.string.magnet_poslji) { _, _ -> posljiNaNapravo(magnet) }
                    .setNeutralButton(R.string.magnet_deli) { _, _ -> deliZDrugimi(magnet) }
                    .setNegativeButton(android.R.string.ok, null).show()
                osveziPrenose()
            }
        }
    }

    private fun osveziPrenose() {
        val cilj = prenosi ?: return
        vOzadju {
            val s = MagnetMotor.seznam(this)
            glavna.post {
                if (isFinishing) return@post
                val podpis = (0 until s.length()).joinToString { s.getJSONObject(it).let { x -> "${x.optString("hash")}${x.optBoolean("premor")}${x.optBoolean("deli_naprej")}${x.optBoolean("koncano")}" } }
                if (cilj.tag == podpis) {
                    for (k in 0 until s.length()) posodobi(cilj.findViewWithTag<TextView>("stanje:" + s.getJSONObject(k).optString("hash")), s.getJSONObject(k))
                    return@post
                }
                cilj.tag = podpis
                cilj.removeAllViews()
                if (s.length() == 0) { cilj.addView(besedilo(getString(R.string.magnet_ni_prenosov), 14f, true)); return@post }
                for (k in 0 until s.length()) cilj.addView(vrsticaPrenosa(s.getJSONObject(k)))
            }
        }
    }

    private fun posodobi(t: TextView?, x: JSONObject) {
        t ?: return
        val skupaj = x.optLong("skupaj").coerceAtLeast(1)
        val odst = (100 * x.optLong("preneseno") / skupaj).toInt()
        t.text = if (x.optBoolean("koncano")) getString(R.string.magnet_koncano) + " · " + velikost(x.optLong("skupaj"))
        else "$odst % · " + String.format(java.util.Locale.ROOT, "%.1f MB/s", x.optInt("hitrost") / 1048576.0) + " · " +
            getString(R.string.magnet_povezav, x.optInt("povezave")) +
            (if (x.optBoolean("premor")) " · " + getString(R.string.magnet_premor) else "")
    }

    private fun vrsticaPrenosa(x: JSONObject): View {
        val hash = x.optString("hash")
        val v = vrstica()
        v.addView(besedilo(x.optString("ime"), 16f))
        v.addView(besedilo("", 13f, true).apply { tag = "stanje:$hash" }.also { posodobi(it, x) })
        val d1 = dejanja(); val d2 = dejanja()
        val datoteke = x.optJSONArray("datoteke")
        val prva = (0 until (datoteke?.length() ?: 0)).map { datoteke!!.getJSONObject(it) }
            .firstOrNull { it.optBoolean("vkljucena") && it.optString("vrsta") in setOf("video", "audio") }
        if (prva != null) d1.addView(gumb("▶ " + getString(R.string.magnet_predvajaj)) {
            predvajaj(MagnetMotor.magnet(this, hash) ?: return@gumb,
                MagnetMotor.Datoteka(prva.optInt("i"), prva.optString("ime"), 0, prva.optString("vrsta")))
        })
        d1.addView(gumb(getString(if (x.optBoolean("premor")) R.string.magnet_nadaljuj else R.string.magnet_premor)) {
            vOzadju { MagnetMotor.rocaj(this, hash)?.let { if (x.optBoolean("premor")) it.resume() else it.pause() }; glavna.post { prenosi?.tag = null; osveziPrenose() } }
        })
        d1.addView(gumb((if (x.optBoolean("deli_naprej")) "✓ " else "") + getString(R.string.magnet_deli_naprej)) {
            vOzadju { MagnetMotor.nastaviDeliNaprej(this, hash, !x.optBoolean("deli_naprej")); glavna.post { prenosi?.tag = null; osveziPrenose() } }
        })
        d2.addView(gumb(getString(R.string.magnet_poslji)) { MagnetMotor.magnet(this, hash)?.let { posljiNaNapravo(it) } })
        d2.addView(gumb(getString(R.string.magnet_deli)) { MagnetMotor.magnet(this, hash)?.let { deliZDrugimi(it) } })
        d2.addView(gumb(getString(R.string.magnet_odstrani)) {
            vOzadju { MagnetMotor.odstrani(this, hash, false); glavna.post { prenosi?.tag = null; osveziPrenose() } }
        })
        if (!x.optBoolean("lastna")) d2.addView(gumb(getString(R.string.magnet_izbrisi)) { g ->
            // Dva koraka: brisanje prenesenih datotek je nepovratno.
            if (g.tag != "potrdi") { g.tag = "potrdi"; g.text = getString(R.string.magnet_izbrisi_res); return@gumb }
            vOzadju { MagnetMotor.odstrani(this, hash, true); glavna.post { prenosi?.tag = null; osveziPrenose() } }
        })
        v.addView(d1); v.addView(d2)
        return v
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}

/** Postavi otroke v vrstice po vrsti in prelomi, ko zmanjka širine (kot besedilo). */
internal class OvijalnaVrsta(c: android.content.Context) : ViewGroup(c) {
    override fun onMeasure(sirinaSpec: Int, visinaSpec: Int) {
        val najvec = MeasureSpec.getSize(sirinaSpec).let { if (MeasureSpec.getMode(sirinaSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else it }
        var x = 0; var y = 0; var vrsta = 0; var sirina = 0
        for (i in 0 until childCount) {
            val o = getChildAt(i)
            if (o.visibility == GONE) continue
            measureChildWithMargins(o, sirinaSpec, 0, visinaSpec, 0)
            val lp = o.layoutParams as MarginLayoutParams
            val w = o.measuredWidth + lp.leftMargin + lp.rightMargin
            val h = o.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > 0 && x + w > najvec) { y += vrsta; x = 0; vrsta = 0 }
            x += w; vrsta = maxOf(vrsta, h); sirina = maxOf(sirina, x)
        }
        setMeasuredDimension(resolveSize(sirina, sirinaSpec), resolveSize(y + vrsta, visinaSpec))
    }

    override fun onLayout(spremenjeno: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val najvec = r - l
        // Najprej vrste (kateri otroci gredo v katero vrsto in kako visoka je), nato postavitev na sredino vrste.
        val vrste = mutableListOf<MutableList<View>>(mutableListOf())
        val visine = mutableListOf(0)
        var x = 0
        for (i in 0 until childCount) {
            val o = getChildAt(i)
            if (o.visibility == GONE) continue
            val lp = o.layoutParams as MarginLayoutParams
            val w = o.measuredWidth + lp.leftMargin + lp.rightMargin
            val h = o.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > 0 && x + w > najvec) { vrste.add(mutableListOf()); visine.add(0); x = 0 }
            vrste.last().add(o); visine[visine.size - 1] = maxOf(visine.last(), h); x += w
        }
        var y = 0
        for ((k, vrsta) in vrste.withIndex()) {
            x = 0
            for (o in vrsta) {
                val lp = o.layoutParams as MarginLayoutParams
                val w = o.measuredWidth + lp.leftMargin + lp.rightMargin
                val h = o.measuredHeight + lp.topMargin + lp.bottomMargin
                val vrh = y + (visine[k] - h) / 2 + lp.topMargin
                val levo = if (layoutDirection == LAYOUT_DIRECTION_RTL) najvec - x - w + lp.leftMargin else x + lp.leftMargin
                o.layout(levo, vrh, levo + o.measuredWidth, vrh + o.measuredHeight)
                x += w
            }
            y += visine[k]
        }
    }

    override fun generateLayoutParams(attrs: android.util.AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)
    override fun generateDefaultLayoutParams(): LayoutParams = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = if (p is MarginLayoutParams) MarginLayoutParams(p) else MarginLayoutParams(p)
    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams
}
