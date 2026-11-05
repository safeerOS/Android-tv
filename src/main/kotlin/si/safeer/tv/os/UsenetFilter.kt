package si.safeer.tv.os

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import si.safeer.tv.os.FilterViraPravila as F

/**
 * Preverbe filtra vira Usenet v ozadju (lastnik 5. 11. 2026): ali indeksator za naslov vrne izdajo, ki izpolni vse
 * pogoje ([FilterViraPravila.lahkoGleda]). Zapisi na disku po kljucu vir + odtis racuna + naslov (6 h, tudi
 * negativni); najvec [F.HKRATI] hkrati in [F.NA_MINUTO] zahtev na minuto za vse mreze skupaj.
 */
object UsenetFilter {
    private const val TAG = "SafeerUsenetFilter"
    private const val DATOTEKA = "usenet_filter"
    private const val NAJVEC_ZAPISOV = 3000

    private val zapisi = ConcurrentHashMap<String, F.Zapis>()
    @Volatile private var nalozeno = false
    private val omejevalnik = F.Omejevalnik(F.NA_MINUTO)
    private val vTeku = ConcurrentHashMap.newKeySet<String>()
    private val stevec = AtomicInteger()
    /** Zahtev indeksatorju od zagona (za oceno porabe v dnevniku). */
    private val porabljeno = AtomicInteger()
    private val delavec = ThreadPoolExecutor(F.HKRATI, F.HKRATI, 30, TimeUnit.SECONDS, LinkedBlockingQueue(F.PRORACUN_MREZE)) { r ->
        Thread(r, "usenet-filter-" + stevec.incrementAndGet()).apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    /** Pogoji, ki jih pot predvajanja »samo Usenet« res zahteva: samodejna izbira vzame izdaje do 15 GB. */
    fun pogoji() = F.Pogoji(F.VIR_USENET, najvecGb = UsenetPravila.NAJVEC_GB_SAMODEJNO)

    private fun zdaj() = System.currentTimeMillis()

    private fun nalozi(c: Context) {
        if (nalozeno) return
        synchronized(this) {
            if (nalozeno) return
            try {
                val p = c.getSharedPreferences(DATOTEKA, Context.MODE_PRIVATE)
                for ((k, v) in p.all) F.izNiza(v as? String)?.let { zapisi[k] = it }
                if (zapisi.size > NAJVEC_ZAPISOV) {
                    val stari = zapisi.entries.sortedBy { it.value.cas }.take(zapisi.size - NAJVEC_ZAPISOV).map { it.key }
                    val e = p.edit(); stari.forEach { zapisi.remove(it); e.remove(it) }; e.apply()
                }
            } catch (e: Exception) { Log.w(TAG, "nalaganje: ${e.javaClass.simpleName}") }
            nalozeno = true
        }
    }

    private fun shrani(c: Context, k: String, z: F.Zapis) {
        zapisi[k] = z
        try { c.getSharedPreferences(DATOTEKA, Context.MODE_PRIVATE).edit().putString(k, F.vNiz(z)).apply() } catch (_: Exception) { }
    }

    fun zapis(c: Context, k: String): F.Zapis? { nalozi(c); return zapisi[k] }
    /** [pomocnik]: v Linku je zdaj pomocnik za Usenet (UsenetPomocniki.zdaj) - izdaje, ki jih zna samo on, se pokazejo. */
    fun pokazi(c: Context, k: String, pomocnik: Boolean = false): Boolean = F.pokazi(zapis(c, k), zdaj(), pomocnik)
    fun oznaka(c: Context, k: String, pomocnik: Boolean = false): KakovostPravila.Oznaka? =
        zapis(c, k)?.takeIf { F.pokazi(it, zdaj(), pomocnik) }?.oznaka
    fun seIscemo(c: Context, k: String): Boolean = k in vTeku || F.seIscemo(zapis(c, k), zdaj())
    fun vprasati(c: Context, k: String): Boolean = k !in vTeku && F.vprasati(zapis(c, k), zdaj())
    fun vTeku(): Int = vTeku.size

    /** Vodja 6. 11. 2026: predpriprava naslednje epizode vprasa indeksator - steje v isto kvoto kot preverba filtra. */
    fun smemPredpripravo(): Boolean = omejevalnik.smem(zdaj()).also { if (it) porabljeno.incrementAndGet() }
    fun vPremoru(): Boolean = omejevalnik.vPremoru(zdaj())

    /** Predvajanje samo Usenet ni naslo izdaje: zapis pozabimo, naslov izgine in se preveri znova (NI ali NE_VEMO). */
    fun pozabi(c: Context, k: String) {
        nalozi(c)
        zapisi.remove(k)
        try { c.getSharedPreferences(DATOTEKA, Context.MODE_PRIVATE).edit().remove(k).apply() } catch (_: Exception) { }
    }

    /**
     * Preveri naslov [k] v ozadju. [id] (na delovni niti): film `tt…` ali epizoda `tt…:S:E`. Vrne false, ce zdaj ne
     * smemo (kvota, ze v teku, vrsta polna) - takrat ni zapisa in ni klica [obKoncu]. [obKoncu] tece na delovni niti.
     */
    fun preveri(c: Context, k: String, id: () -> String?, z: TokIzbira.Zmoznosti, seVelja: () -> Boolean, obKoncu: () -> Unit): Boolean {
        if (!vprasati(c, k) || vTeku.size >= F.HKRATI) return false
        if (!omejevalnik.smem(zdaj())) return false
        if (!vTeku.add(k)) return false
        val app = c.applicationContext
        try {
            delavec.execute {
                try {
                    if (!seVelja()) return@execute
                    val racun = if (Usenet.jeNaVoljo(app)) F.Izid.NA_VOLJO else F.Izid.NE_VEMO
                    val iden = try { id() } catch (_: Exception) { null }
                    var izdaje: List<F.Izdaja>? = null
                    if (racun == F.Izid.NA_VOLJO && iden != null && UsenetPravila.iscemo(iden)) {
                        porabljeno.incrementAndGet()
                        val r = Usenet.iskanje(app, iden, F.ROK_MS)
                        if (r.meja) omejevalnik.meja(zdaj())
                        izdaje = r.izdaje?.map { F.Izdaja(it.naslov, it.velikost / 1_073_741_824.0, it.geslo) }
                    }
                    // Lastnik 5. 11. 2026 (zakon solidarnosti): zapis pove tudi, ali izdajo predvaja pomocnik v Linku -
                    // prisotnost pomocnika se presodi ob prikazu, ne tu (ne zapisemo »NI za 6 h« zaradi trenutka brez njega).
                    val pv = F.preverba(F.Izidi(racun, F.Odgovor(izdaje)), pogoji(), z)
                    shrani(app, k, F.zapisi(zapisi[k], pv.izid, pv.oznaka, zdaj(), pv.pomoc))
                    Log.i(TAG, "preverba: izdaj=${izdaje?.size ?: -1}, izid=${pv.izid}, pomoc=${pv.pomoc}, zahtev od zagona=${porabljeno.get()}")
                } finally {
                    vTeku.remove(k)
                    obKoncu()
                }
            }
        } catch (_: RejectedExecutionException) { vTeku.remove(k); return false }
        return true
    }
}
