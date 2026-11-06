package si.safeer.tv.os

import android.content.Context
import android.util.Log

/**
 * Diagnostika predvajanja na napravi ([DiagnostikaPredvajanjaPravila]): privzeto izklopljena; vklop v »Pravno in o
 * aplikaciji« -> »Diagnostika predvajanja« velja 24 ur. Dnevnik (najvec 50 vrstic) je v zasebni datoteki aplikacije
 * (SharedPreferences, varnostna kopija aplikacije je izklopljena) - ob izteku ali izklopu se izbrise. Vsak vnos gre ob
 * nastanku tudi v logcat z oznako [DiagnostikaPredvajanjaPravila.OZNAKA]; ob odprtju okna »Pravno in o aplikaciji« pri
 * vklopljeni diagnostiki se ves dnevnik izpise znova (logcat TV se prepise v nekaj minutah). Branje: adb logcat -d -s SafeerDiag.
 * Izklopljena stane eno branje iz SharedPreferences (v pomnilniku) - brez sestavljanja vnosa.
 */
object DiagnostikaPredvajanja {
    private const val PREFS = "safeer_diag_predvajanja"
    private const val DO = "do"
    private const val DNEVNIK = "dnevnik"
    private val P = DiagnostikaPredvajanjaPravila

    private class Cakajoci(val kdaj: Long, val poskus: DiagnostikaPredvajanjaPravila.Poskus)
    /** Neuspeli poskusi zadnjih minut (samo v pomnilniku, samo pri vklopljeni diagnostiki). */
    private val poskusi = ArrayList<Cakajoci>()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun zdaj() = System.currentTimeMillis()

    /** Rok vklopa (ms od 1970) ali 0, ce ni vklopljena; potekel vklop izbrise dnevnik. */
    fun vklopljenaDo(c: Context): Long = try {
        val p = prefs(c)
        val d = p.getLong(DO, 0L)
        if (P.velja(d, zdaj())) d else { if (d != 0L || p.contains(DNEVNIK)) izklopi(c); 0L }
    } catch (_: Throwable) { 0L }

    fun vklopljena(c: Context): Boolean = vklopljenaDo(c) != 0L

    fun vklopi(c: Context) {
        val d = zdaj() + P.TRAJANJE_MS
        prefs(c).edit().putLong(DO, d).apply()
        Log.i(P.OZNAKA, "diagnostika predvajanja vklopljena za 24 h")
    }

    /** Izklop: dnevnik in poskusi se izbrisejo takoj. */
    fun izklopi(c: Context) {
        synchronized(poskusi) { poskusi.clear() }
        try { prefs(c).edit().clear().apply() } catch (_: Throwable) {}
        Log.i(P.OZNAKA, "diagnostika predvajanja izklopljena, dnevnik izbrisan")
    }

    /** Neuspel poskus enega vira: [vir] = vrsta (usenet, debrid, webtor, p2p, neposredno ...), [naslovVira] = samo za gostitelja. */
    fun poskus(c: Context, vir: String, naslovVira: String?, razlog: String) {
        if (!vklopljena(c)) return
        val p = P.poskus(vir, naslovVira, razlog)
        synchronized(poskusi) { poskusi += Cakajoci(android.os.SystemClock.elapsedRealtime(), p); while (poskusi.size > 20) poskusi.removeAt(0) }
        Log.i(P.OZNAKA, "poskus: ${p.vir}${if (p.gostitelj.isNotEmpty()) "@" + p.gostitelj else ""} (${p.razlog})")
    }

    /** Neuspel krog vec hkratnih kandidatov enega vira (Webtor): vsak kandidat svoj poskus ([DiagnostikaPredvajanjaPravila.poskusiKroga]). */
    fun poskusiKroga(c: Context, vir: String, naslovi: List<String?>, razlog: String) {
        if (!vklopljena(c)) return
        val novi = P.poskusiKroga(vir, naslovi, razlog)
        synchronized(poskusi) {
            val kdaj = android.os.SystemClock.elapsedRealtime()
            novi.forEach { poskusi += Cakajoci(kdaj, it) }
            while (poskusi.size > 20) poskusi.removeAt(0)
        }
        novi.forEach { p -> Log.i(P.OZNAKA, "poskus: ${p.vir}${if (p.gostitelj.isNotEmpty()) "@" + p.gostitelj else ""} (${p.razlog})") }
    }

    /**
     * Pregled 6. 11. 2026 (U4): branje, dopolnitev in zapis dnevnika pod zaklepom - dva hkratna neuspeha (Webtor in Usenet
     * vzporedno) sta prebrala isti dnevnik in drugi zapis je prepisal prvega.
     */
    private val zaklepDnevnika = Any()

    /** Predvajanje [naslov] ni uspelo: vnos s poskusi zadnjih minut in razlogom. */
    fun neuspeh(c: Context, naslov: String?, vrsta: String, razlog: String) {
        synchronized(zaklepDnevnika) { neuspehPodZaklepom(c, naslov, vrsta, razlog) }
    }

    private fun neuspehPodZaklepom(c: Context, naslov: String?, vrsta: String, razlog: String) {
        try {
            val prefs = prefs(c)
            val dnevnik = prefs.getString(DNEVNIK, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
            val novi = P.zapis(prefs.getLong(DO, 0L), zdaj(), dnevnik) {
                val meja = android.os.SystemClock.elapsedRealtime() - P.OKNO_POSKUSOV_MS
                val nedavni = synchronized(poskusi) { poskusi.filter { it.kdaj >= meja }.map { it.poskus }.also { poskusi.clear() } }
                val cas = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(zdaj()))
                P.vrstica(cas, naslov, vrsta, nedavni, razlog).also { Log.i(P.OZNAKA, "neuspeh: $it") }
            } ?: return
            if (novi.isEmpty()) izklopi(c) else prefs.edit().putString(DNEVNIK, novi.joinToString("\n")).apply()
        } catch (_: Throwable) {}
    }

    /** Ves dnevnik znova v logcat (ob odprtju okna »Pravno in o aplikaciji« pri vklopljeni diagnostiki). */
    fun izpisi(c: Context) {
        if (!vklopljena(c)) return
        val d = prefs(c).getString(DNEVNIK, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        Log.i(P.OZNAKA, "dnevnik: ${d.size} vnosov")
        d.forEachIndexed { i, v -> Log.i(P.OZNAKA, "${i + 1}/${d.size} $v") }
    }

    /** Vrsta vsebine za vnos: serija, film/video, kanal, radio ali glasba. */
    fun vrsta(sk: Jamendo.Skladba?): String = when {
        sk == null -> "?"
        sk.radio -> "radio"
        Stremio.jeVZivo(sk) || IptvSeznami.jeKanal(sk) || sk.id.startsWith("tv:") -> "kanal"
        Stremio.jeSerija(sk) -> "serija"
        sk.video -> "film"
        else -> "glasba"
    }
}
