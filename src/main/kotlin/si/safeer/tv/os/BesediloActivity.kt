package si.safeer.tv.os

import si.safeer.tv.R

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.Request
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Besedilna datoteka z racunalnika (ali s televizorja) kar na televizorju: opombe, seznami,
 * nastavitve, dnevniki. Prej je Safeer OS rekel samo "te vrste datoteke televizor ne zna odpreti" -
 * a besedilo zna pokazati vsak zaslon.
 *
 * Datoteka se prenese po isti pripeti povezavi kot slike in videi; beremo najvec [NAJVEC] bajtov,
 * da dolg dnevnik ne pozre pomnilnika televizorja. Smerne tipke drsijo, Nazaj zapre.
 */
class BesediloActivity : OsActivity() {

    private lateinit var naslov: TextView
    private lateinit var vsebina: TextView
    private lateinit var drsnik: ScrollView
    private lateinit var sporocilo: TextView

    private val ozadje = Executors.newSingleThreadExecutor()
    private val glavna = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.os_activity_besedilo)
        naslov = findViewById(R.id.naslov)
        vsebina = findViewById(R.id.vsebina)
        drsnik = findViewById(R.id.drsnik)
        sporocilo = findViewById(R.id.sporocilo)
        Ozadje.uporabi(this, findViewById(R.id.koren))
        val ime = intent.getStringExtra("ime").orEmpty()
        naslov.text = ime
        val url = intent.getStringExtra("url").orEmpty()
        val lokalno = intent.getBooleanExtra("lokalno", false)
        val streznik = DatotekeActivity.Streznik.iz(intent.extras)
        if (url.isEmpty() || (!lokalno && streznik == null)) { finish(); return }
        sporocilo.text = getString(R.string.os_datoteke_nalagam)
        sporocilo.visibility = View.VISIBLE
        ozadje.execute {
            var prevelika = false
            val besedilo = try {
                val bajti: ByteArray? = if (lokalno) {
                    contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes() }
                } else {
                    val s = streznik!!
                    val k = PripetiVir.odjemalecZaStreznik(s.odtis)
                    k.newCall(Request.Builder().url(url).header("X-Safeer-Token", s.zeton).build())
                        .execute().use { o -> if (o.isSuccessful) o.body?.bytes() else null }
                }
                if (bajti == null) null else {
                    prevelika = bajti.size > NAJVEC
                    String(bajti, 0, minOf(bajti.size, NAJVEC), StandardCharsets.UTF_8)
                }
            } catch (_: Throwable) { null }
            glavna.post {
                if (isFinishing) return@post
                if (besedilo == null) {
                    sporocilo.text = getString(R.string.os_besedilo_napaka)
                    return@post
                }
                sporocilo.visibility = View.GONE
                vsebina.text = if (prevelika) besedilo + "\n\n" + getString(R.string.os_besedilo_prevelika) else besedilo
                drsnik.requestFocus()
            }
        }
    }

    override fun onDestroy() {
        ozadje.shutdownNow()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> { drsnik.smoothScrollBy(0, KORAK); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { drsnik.smoothScrollBy(0, -KORAK); return true }
            KeyEvent.KEYCODE_PAGE_DOWN -> { drsnik.pageScroll(View.FOCUS_DOWN); return true }
            KeyEvent.KEYCODE_PAGE_UP -> { drsnik.pageScroll(View.FOCUS_UP); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        /**
         * Najvec besedila, kolikor pokazemo naenkrat. Vec kot toliko en sam TextView na televizorju
         * ne izrise tekoce - drsenje po pol megabajta besedila je bilo trzajoce.
         */
        private const val NAJVEC = 160 * 1024
        private const val KORAK = 160

        /** Ali zna televizor to datoteko pokazati kot besedilo. */
        fun jeBesedilo(ime: String, mime: String): Boolean {
            val m = mime.lowercase()
            if (m.startsWith("text/")) return true
            if (m in MIME) return true
            val koncnica = ime.substringAfterLast('.', "").lowercase()
            return koncnica.isNotEmpty() && koncnica in KONCNICE
        }

        private val MIME = setOf("application/json", "application/xml", "application/javascript",
            "application/x-sh", "application/x-yaml", "application/x-subrip")

        private val KONCNICE = setOf("txt", "md", "log", "json", "csv", "tsv", "ini", "conf", "cfg",
            "yml", "yaml", "xml", "srt", "vtt", "sh", "py", "kt", "java", "js", "ts", "css", "sql",
            "toml", "env", "list", "nfo", "gitignore", "properties")
    }
}
