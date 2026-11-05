package si.safeer.tv.os

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pomocniki za Usenet v Safeer Linku (lastnik 5. 11. 2026, zakon solidarnosti; pravila [UsenetPomocPravila]).
 *
 * Filter »⚡ Usenet« in samodejna izbira toka vprasata [zdaj]: ali je v Linku naprava v ozjem krogu, ki sme pomagati,
 * ima kodirnik in svoj Usenet z istim indeksatorjem. Seznam osvezi host.info vsem napravam (najvec 1 na minuto, rok 2,5 s);
 * ko se nabor spremeni, [obSpremembi] osvezi mrezo (odlocitev za izdaje »samo s pomocnikom« je ob prikazu, ne v zapisu).
 */
object UsenetPomocniki {
    private const val TAG = "SafeerUsenetPomoc"

    @Volatile private var znani: List<String> = emptyList()
    @Volatile private var cas = 0L
    private val vTeku = AtomicBoolean(false)
    private val glavna = Handler(Looper.getMainLooper())

    /** Klice se na glavni niti, ko se nabor pomocnikov spremeni (mreza filtra se osvezi). */
    @Volatile var obSpremembi: (() -> Unit)? = null

    /** Identitete izdaj po viru (krajevni naslov lastnega pogona ali id toka »usenet:…«) - samo v pomnilniku, najvec 20. */
    private val viri = object : LinkedHashMap<String, JSONObject>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?) = size > 20
    }

    @Synchronized fun zapomniVir(vir: String, identiteta: JSONObject) { if (vir.isNotBlank()) viri[vir] = identiteta }
    @Synchronized fun identitetaVira(vir: String): JSONObject? = viri[vir]

    private fun vOzjemKrogu(c: Context, id: String): Boolean = try {
        si.safeer.tv.cast.Dostop.zmoznosti(c, id).containsAll(si.safeer.tv.cast.DostopPravila.OZJI_KROG)
    } catch (_: Throwable) { false }

    /** Ali naprava [info] (host.info) pomaga pri Usenetu z indeksatorjem [moj]; [ocena] = SprotnaPomoc.ocena. */
    fun ustreza(info: JSONObject, ocena: Long, moj: String?): Boolean {
        val u = info.optJSONObject("usenet") ?: return false
        return UsenetPomocPravila.ustreza(u.optBoolean("lahko", false), ocena, u.optString("ix").takeIf { it.isNotBlank() }, moj)
    }

    /** Pomocniki, ki so zdaj v Linku (urejeni po oceni). Sprozi osvezitev, ce je na vrsti (ne caka nanjo). */
    fun seznam(c: Context): List<String> {
        osvezi(c)
        val link = LinkUpravitelj.pridobi(c.applicationContext)
        if (!link.povezan) return emptyList()
        return UsenetPomocPravila.zdaj(znani, cas, SystemClock.elapsedRealtime(), link.naprave.map { it.id }.toSet())
    }

    fun zdaj(c: Context): Boolean = seznam(c).isNotEmpty()

    /** Osvezitev v ozadju (najvec 1 na [UsenetPomocPravila.OSVEZI_MS], ena hkrati). */
    fun osvezi(c: Context) {
        val app = c.applicationContext
        val zdaj = SystemClock.elapsedRealtime()
        if (!UsenetPomocPravila.smemOsveziti(cas, zdaj) || !vTeku.compareAndSet(false, true)) return
        val moj = try { Usenet.odtisIzdaj(app) } catch (_: Exception) { null }
        val link = LinkUpravitelj.pridobi(app)
        val kandidati = if (moj == null || !link.povezan || link.jeKrajevni()) emptyList()
            else link.naprave.filter { n -> !link.jeTaNaprava(n) && "files" in n.zmoznosti && vOzjemKrogu(app, n.id) }
        if (kandidati.isEmpty()) { koncaj(emptyList(), zdaj); return }
        val ocene = java.util.concurrent.ConcurrentHashMap<String, Long>()
        var cakam = kandidati.size
        val konec = AtomicBoolean(false)
        fun izid() { if (konec.compareAndSet(false, true)) koncaj(ocene.entries.sortedByDescending { it.value }.map { it.key }, zdaj) }
        // Ce kateri odgovor ne pride (ukaz brez povratnega klica), osvezitev ne obvisi: po roku vzamemo, kar imamo.
        glavna.postDelayed({ izid() }, UsenetPomocPravila.ROK_MS + 1_000L)
        for (n in kandidati) {
            link.ukaz(n.id, "host.info", JSONObject(), UsenetPomocPravila.ROK_MS, LinkOdjemalec.Odgovor { izid, _ ->
                izid?.optJSONObject("data")?.let { info ->
                    val o = SprotnaPomoc.ocena(info, JSONObject())
                    if (ustreza(info, o, moj)) ocene[n.id] = o
                }
                glavna.post { if (--cakam == 0) izid() }
            })
        }
    }

    private fun koncaj(novi: List<String>, ob: Long) {
        val prej = znani
        znani = novi; cas = ob; vTeku.set(false)
        if (prej.isEmpty() != novi.isEmpty()) {
            Log.i(TAG, "pomocnikov za Usenet: ${novi.size}")
            glavna.post { obSpremembi?.invoke() }
        }
    }
}
