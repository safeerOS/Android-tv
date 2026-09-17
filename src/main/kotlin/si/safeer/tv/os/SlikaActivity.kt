package si.safeer.tv.os

import si.safeer.tv.R

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
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
 * telefona ne pozrejo pomnilnika televizorja).
 */
class SlikaActivity : Activity() {

    private lateinit var slika: ImageView
    private lateinit var nalagam: ProgressBar
    private lateinit var prekritje: View
    private lateinit var ime: TextView
    private lateinit var stevec: TextView

    private var urli: List<String> = emptyList()
    private var imena: List<String> = emptyList()
    private var i = 0
    private var zeton = ""
    private var lokalno = false
    private var odjemalec: OkHttpClient? = null
    private val ozadje = Executors.newSingleThreadExecutor()
    private val glavna = Handler(Looper.getMainLooper())
    private val skrij = Runnable { prekritje.visibility = View.GONE }
    private var generacija = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.os_activity_slika)
        slika = findViewById(R.id.slika)
        nalagam = findViewById(R.id.nalagam)
        prekritje = findViewById(R.id.prekritje)
        ime = findViewById(R.id.ime)
        stevec = findViewById(R.id.stevec)
        urli = intent.getStringArrayListExtra("urli") ?: arrayListOf()
        imena = intent.getStringArrayListExtra("imena") ?: arrayListOf()
        i = intent.getIntExtra("zacetek", 0).coerceIn(0, (urli.size - 1).coerceAtLeast(0))
        lokalno = intent.getBooleanExtra("lokalno", false)
        val s = DatotekeActivity.Streznik.iz(intent.extras)
        if (urli.isEmpty() || (!lokalno && s == null)) { finish(); return }
        if (!lokalno) {
            zeton = s!!.zeton
            odjemalec = PripetiVir.odjemalecZaStreznik(s.odtis)
        }
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
        stevec.text = "${i + 1} / ${urli.size}"
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
                    val z = Request.Builder().url(url).header("X-Safeer-Token", zeton).build()
                    k!!.newCall(z).execute().use { o -> if (o.isSuccessful) o.body?.bytes() else null }
                }
                if (bajti == null) null else {
                    val mere = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bajti, 0, bajti.size, mere)
                    var vzorec = 1
                    while (mere.outWidth / (vzorec * 2) >= sirina && mere.outHeight / (vzorec * 2) >= visina) vzorec *= 2
                    BitmapFactory.decodeByteArray(bajti, 0, bajti.size, BitmapFactory.Options().apply { inSampleSize = vzorec })
                }
            } catch (_: Throwable) { null }
            glavna.post {
                if (g != generacija || isFinishing) return@post
                nalagam.visibility = View.GONE
                if (b == null) Toast.makeText(this, getString(R.string.os_slika_napaka), Toast.LENGTH_SHORT).show()
                else slika.setImageBitmap(b)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> { if (urli.size > 1) { i = (i + 1) % urli.size; nalozi() }; return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (urli.size > 1) { i = (i - 1 + urli.size) % urli.size; nalozi() }; return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                prekritje.visibility = if (prekritje.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                glavna.removeCallbacks(skrij)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
