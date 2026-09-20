package si.safeer.tv.os

import si.safeer.tv.R

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors

/**
 * Slike z racunalnika: ena cez ves zaslon, levo/desno prejsnja/naslednja iz iste mape. Slika se
 * prenese s pripetim potrdilom in zetonom, pomanjsa na velikost zaslona (velike fotografije s
 * telefona ne pozrejo pomnilnika televizorja) in obrne po oznaki EXIF, da je pokoncna fotografija
 * pokoncna tudi tu.
 *
 * Kadar racunalnik dovoli urejanje (Safeer Control 2.1.0+): zadrzan OK (na tablici gumbi v
 * prekritju) zavrti sliko v levo ali desno - shrani se na racunalniku -, jo preimenuje ali izbrise
 * (v Smeti racunalnika).
 */
class SlikaActivity : OsActivity() {

    private lateinit var slika: ImageView
    private lateinit var nalagam: ProgressBar
    private lateinit var prekritje: View
    private lateinit var ime: TextView
    private lateinit var stevec: TextView
    private lateinit var gumbi: View

    private var urli: ArrayList<String> = arrayListOf()
    private var imena: ArrayList<String> = arrayListOf()
    private var oznake: ArrayList<String> = arrayListOf()
    private var i = 0
    private var zeton = ""
    private var lokalno = false
    private var urejanje = false
    private var streznik: DatotekeActivity.Streznik? = null
    private var odjemalec: OkHttpClient? = null
    private val ozadje = Executors.newSingleThreadExecutor()
    private val glavna = Handler(Looper.getMainLooper())
    private val skrij = Runnable { prekritje.visibility = View.GONE }
    private var generacija = 0
    /** Zadrzan OK je odprl moznosti: kratki OK ob spustu tipke ne sme se preklopiti napisa. */
    private var dolgiOk = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.os_activity_slika)
        slika = findViewById(R.id.slika)
        nalagam = findViewById(R.id.nalagam)
        prekritje = findViewById(R.id.prekritje)
        ime = findViewById(R.id.ime)
        stevec = findViewById(R.id.stevec)
        gumbi = findViewById(R.id.gumbi)
        urli = intent.getStringArrayListExtra("urli") ?: arrayListOf()
        imena = intent.getStringArrayListExtra("imena") ?: arrayListOf()
        oznake = intent.getStringArrayListExtra("oznake") ?: arrayListOf()
        i = intent.getIntExtra("zacetek", 0).coerceIn(0, (urli.size - 1).coerceAtLeast(0))
        lokalno = intent.getBooleanExtra("lokalno", false)
        val s = DatotekeActivity.Streznik.iz(intent.extras)
        if (urli.isEmpty() || (!lokalno && s == null)) { finish(); return }
        if (!lokalno) {
            streznik = s
            zeton = s!!.zeton
            odjemalec = PripetiVir.odjemalecZaStreznik(s.odtis)
        }
        urejanje = intent.getBooleanExtra("urejanje", false) && !lokalno && oznake.size == urli.size
        // Na tablici (dotik) so dejanja gumbi v prekritju; na televizorju jih odpre zadrzan OK.
        val dotik = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        gumbi.visibility = if (urejanje && dotik) View.VISIBLE else View.GONE
        findViewById<View>(R.id.zavrtiLevo).setOnClickListener { zavrti(false) }
        findViewById<View>(R.id.zavrtiDesno).setOnClickListener { zavrti(true) }
        findViewById<View>(R.id.izbrisi).setOnClickListener { potrdiBrisanje() }
        slika.setOnClickListener { preklopiPrekritje() }
        slika.setOnLongClickListener { if (urejanje) { moznosti(); true } else false }
        nalozi()
    }

    override fun onDestroy() {
        generacija++
        ozadje.shutdownNow()
        super.onDestroy()
    }

    private fun nalozi() {
        val g = ++generacija
        val url = urli[i]
        ime.text = imena.getOrNull(i).orEmpty()
        stevec.text = getString(R.string.fmt_stevec, i + 1, urli.size)
        prekritje.visibility = View.VISIBLE
        glavna.removeCallbacks(skrij); glavna.postDelayed(skrij, 2_500)
        nalagam.visibility = View.VISIBLE
        val k = odjemalec
        if (!lokalno && k == null) return
        val sirina = maxOf(1280, resources.displayMetrics.widthPixels)
        val visina = maxOf(720, resources.displayMetrics.heightPixels)
        ozadje.execute {
            val b: Bitmap? = try {
                val bajti = if (lokalno) {
                    contentResolver.openInputStream(android.net.Uri.parse(url))?.use { it.readBytes() }
                } else {
                    val z = Request.Builder().url(url).header("X-Safeer-Token", zeton).header("Cache-Control", "no-cache").build()
                    k!!.newCall(z).execute().use { o -> if (o.isSuccessful) o.body?.bytes() else null }
                }
                if (bajti == null) null else UrejanjeDatotek.dekodiraj(bajti, sirina, visina)
            } catch (_: Throwable) { null }
            glavna.post {
                if (g != generacija || isFinishing) return@post
                nalagam.visibility = View.GONE
                if (b == null) Toast.makeText(this, getString(R.string.os_slika_napaka), Toast.LENGTH_SHORT).show()
                else slika.setImageBitmap(b)
            }
        }
    }

    private fun preklopiPrekritje() {
        prekritje.visibility = if (prekritje.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        glavna.removeCallbacks(skrij)
    }

    // ------------------------------------------------------------------ urejanje

    private fun moznosti() {
        if (!urejanje) return
        val dejanja = listOf(
            getString(R.string.os_ur_zavrti_levo) to { zavrti(false) },
            getString(R.string.os_ur_zavrti_desno) to { zavrti(true) },
            getString(R.string.os_ur_preimenuj) to { preimenuj() },
            getString(R.string.os_ur_izbrisi) to { potrdiBrisanje() },
        )
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(imena.getOrNull(i).orEmpty())
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, k -> dejanja.getOrNull(k)?.second?.invoke() }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    /** Zavrti na racunalniku (shrani se v datoteko) in sliko nalozi znova. */
    private fun zavrti(vDesno: Boolean) {
        val s = streznik ?: return
        val id = oznake.getOrNull(i) ?: return
        nalagam.visibility = View.VISIBLE
        UrejanjeDatotek.zavrti(s, id, vDesno) { izid ->
            if (isFinishing) return@zavrti
            if (izid.ok) nalozi() else {
                nalagam.visibility = View.GONE
                Toast.makeText(this, getString(R.string.os_ur_napaka, DatotekeActivity.opisNapake(this, izid.napaka)), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun preimenuj() {
        val s = streznik ?: return
        val id = oznake.getOrNull(i) ?: return
        val staro = imena.getOrNull(i).orEmpty()
        val vnos = EditText(this).apply {
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.os_ur_ime_namig)
            setText(staro)
            setPadding(40, 30, 40, 30)
        }
        val pika = staro.lastIndexOf('.')
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_ur_preimenuj))
            .setView(vnos)
            .setPositiveButton(getString(R.string.os_naprave_shrani)) { _, _ ->
                val novo = vnos.text?.toString().orEmpty().trim()
                if (novo.isEmpty() || novo == staro) return@setPositiveButton
                UrejanjeDatotek.preimenuj(s, id, novo) { izid ->
                    if (isFinishing) return@preimenuj
                    if (!izid.ok) {
                        Toast.makeText(this, getString(R.string.os_ur_napaka, DatotekeActivity.opisNapake(this, izid.napaka)), Toast.LENGTH_LONG).show()
                        return@preimenuj
                    }
                    // Nova oznaka in naslov: slika ostane odprta, seznam mape se osvezi ob vrnitvi.
                    oznake[i] = izid.id.ifBlank { id }
                    imena[i] = izid.ime.ifBlank { novo }
                    urli[i] = s.url(oznake[i])
                    ime.text = imena[i]
                    DatotekeActivity.osveziPoVrnitvi = true
                    Toast.makeText(this, getString(R.string.os_ur_preimenovano), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
        DatotekeActivity.izberiIme(vnos, if (pika > 0) pika else staro.length)
    }

    private fun potrdiBrisanje() {
        val s = streznik ?: return
        val id = oznake.getOrNull(i) ?: return
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_ur_izbrisi_vprasanje, imena.getOrNull(i).orEmpty()))
            .setMessage(getString(R.string.os_ur_izbrisi_opis))
            .setPositiveButton(getString(R.string.os_ur_izbrisi)) { _, _ ->
                UrejanjeDatotek.izbrisi(s, id) { izid ->
                    if (isFinishing) return@izbrisi
                    if (!izid.ok) {
                        Toast.makeText(this, getString(R.string.os_ur_napaka, DatotekeActivity.opisNapake(this, izid.napaka)), Toast.LENGTH_LONG).show()
                        return@izbrisi
                    }
                    DatotekeActivity.osveziPoVrnitvi = true
                    Toast.makeText(this, getString(R.string.os_ur_izbrisano), Toast.LENGTH_SHORT).show()
                    urli.removeAt(i); imena.removeAt(i); oznake.removeAt(i)
                    if (urli.isEmpty()) { finish(); return@izbrisi }
                    i = i.coerceIn(0, urli.size - 1)
                    nalozi()
                }
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
    }

    // ------------------------------------------------------------------ tipke

    /** Plosek pri slikah: A pokaze in skrije napis, ramena listata po mapi, Y odpre moznosti. */
    override fun plosekDejanje(koda: Int): Boolean = when (koda) {
        KeyEvent.KEYCODE_BUTTON_A -> { preklopiPrekritje(); true }
        KeyEvent.KEYCODE_BUTTON_Y -> { moznosti(); true }
        KeyEvent.KEYCODE_BUTTON_L1 -> { if (urli.size > 1) { i = (i - 1 + urli.size) % urli.size; nalozi() }; true }
        KeyEvent.KEYCODE_BUTTON_R1 -> { if (urli.size > 1) { i = (i + 1) % urli.size; nalozi() }; true }
        else -> false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> { if (urli.size > 1) { i = (i + 1) % urli.size; nalozi() }; return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (urli.size > 1) { i = (i - 1 + urli.size) % urli.size; nalozi() }; return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // Kratek OK preklopi napis (ob spustu), zadrzan OK odpre moznosti urejanja.
                if (event?.repeatCount == 0) { dolgiOk = false; event.startTracking() }
                return true
            }
            KeyEvent.KEYCODE_MENU -> { if (urejanje) moznosti() else preklopiPrekritje(); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { preklopiPrekritje(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            dolgiOk = true
            if (urejanje) moznosti() else preklopiPrekritje()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (!dolgiOk && event?.isCanceled != true) preklopiPrekritje()
            dolgiOk = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
