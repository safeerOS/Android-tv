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
    @Volatile private var nalagam = false
    /** Klici po koncu nalaganja (pripravi); kljuci, pozabljeni med nalaganjem. */
    private val poNalaganju = ArrayList<() -> Unit>()
    private val pozabljeni = ConcurrentHashMap.newKeySet<String>()
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

    /**
     * Pregled 6. 11. 2026 (U3): do 3000 zapisov iz SharedPreferences se je bralo na glavni niti (prvi izris mreze). Zdaj v
     * ozadju; do konca nalaganja zapisa ni (naslov se ne pokaze, »se iscemo«) in indeksatorja ne vprasamo - sicer bi znova
     * spraseval za naslove, ki imajo zapis na disku. [obKoncu] (nit ozadja) samo, ce se nalaganje sele zacne ali tece.
     */
    fun pripravi(c: Context, obKoncu: (() -> Unit)? = null) {
        if (nalozeno) return
        synchronized(this) {
            if (nalozeno) return
            if (obKoncu != null && poNalaganju.size < 8) poNalaganju.add(obKoncu)
            if (nalagam) return
            nalagam = true
        }
        val app = c.applicationContext
        Thread({
            try {
                val p = app.getSharedPreferences(DATOTEKA, Context.MODE_PRIVATE)
                for ((k, v) in p.all) F.izNiza(v as? String)?.let { zapisi.putIfAbsent(k, it) }
                if (zapisi.size > NAJVEC_ZAPISOV) {
                    val stari = zapisi.entries.sortedBy { it.value.cas }.take(zapisi.size - NAJVEC_ZAPISOV).map { it.key }
                    val e = p.edit(); stari.forEach { zapisi.remove(it); e.remove(it) }; e.apply()
                }
                pozabljeni.forEach { zapisi.remove(it) }
            } catch (e: Exception) { Log.w(TAG, "nalaganje: ${e.javaClass.simpleName}") }
            pozabljeni.clear()
            // Pod zaklepom: klic, dodan med nalaganjem, gre v ta seznam ali pa ze vidi nalozeno (nobeden ne ostane viset).
            val klici = synchronized(this) { nalozeno = true; poNalaganju.toList().also { poNalaganju.clear() } }
            klici.forEach { try { it() } catch (_: Throwable) { } }
        }, "usenet-filter-nalaganje").apply { isDaemon = true; start() }
    }

    private fun nalozi(c: Context): Boolean { if (!nalozeno) pripravi(c); return nalozeno }

    private fun shrani(c: Context, k: String, z: F.Zapis) {
        zapisi[k] = z
        try { c.getSharedPreferences(DATOTEKA, Context.MODE_PRIVATE).edit().putString(k, F.vNiz(z)).apply() } catch (_: Exception) { }
    }

    fun zapis(c: Context, k: String): F.Zapis? = if (nalozi(c)) zapisi[k] else null
    /** [pomocnik]: v Linku je zdaj pomocnik za Usenet (UsenetPomocniki.zdaj) - izdaje, ki jih zna samo on, se pokazejo. */
    fun pokazi(c: Context, k: String, pomocnik: Boolean = false): Boolean = F.pokazi(zapis(c, k), zdaj(), pomocnik)
    fun oznaka(c: Context, k: String, pomocnik: Boolean = false): KakovostPravila.Oznaka? =
        zapis(c, k)?.takeIf { F.pokazi(it, zdaj(), pomocnik) }?.oznaka
    fun seIscemo(c: Context, k: String): Boolean = !nalozi(c) || k in vTeku || F.seIscemo(zapis(c, k), zdaj())
    fun vprasati(c: Context, k: String): Boolean = nalozi(c) && k !in vTeku && F.vprasati(zapis(c, k), zdaj())
    fun vTeku(): Int = vTeku.size

    /** Dnevnik (vodja 6. 11. 2026): zakaj preverba ne steče - stevila po razlogu, brez kljucev in naslovov. */
    fun zakajNe(c: Context, kljuci: List<String>): String {
        if (!nalozeno) return "zapisi se nalagajo"
        var brezZapisa = 0; var sveziZapis = 0; var cakaPonovitev = 0; var vTekuK = 0
        for (k in kljuci) {
            val z = zapisi[k]
            when {
                k in vTeku -> vTekuK++
                z == null -> brezZapisa++
                F.vprasati(z, zdaj()) -> brezZapisa++
                z.izid == F.Izid.NE_VEMO -> cakaPonovitev++
                else -> sveziZapis++
            }
        }
        return "kljucev=${kljuci.size}, za vprasat=$brezZapisa, svezih=$sveziZapis, ne_vemo_caka=$cakaPonovitev, v_teku=$vTekuK, " +
            "premor=${omejevalnik.vPremoru(zdaj())}, kvota=${omejevalnik.lahko(zdaj())}"
    }

    /** Vodja 6. 11. 2026: predpriprava naslednje epizode vprasa indeksator - steje v isto kvoto kot preverba filtra. */
    fun smemPredpripravo(): Boolean = omejevalnik.smem(zdaj()).also { if (it) porabljeno.incrementAndGet() }
    fun vPremoru(): Boolean = omejevalnik.vPremoru(zdaj())

    /** Pregled 6. 11. 2026 (U2): zagon naslednje epizode vprasa indeksator (izdaje lastnega pogona) - steje v isto omejitev. */
    fun zabeleziZagon() { omejevalnik.zabelezi(zdaj()); porabljeno.incrementAndGet() }

    /** Predvajanje samo Usenet ni naslo izdaje: zapis pozabimo, naslov izgine in se preveri znova (NI ali NE_VEMO). */
    fun pozabi(c: Context, k: String) {
        if (!nalozi(c)) pozabljeni.add(k)
        zapisi.remove(k)
        try { c.getSharedPreferences(DATOTEKA, Context.MODE_PRIVATE).edit().remove(k).apply() } catch (_: Exception) { }
    }

    /**
     * Preveri naslov [k] v ozadju. [id] (na delovni niti): film `tt…` ali epizoda `tt…:S:E`. Vrne false, ce zdaj ne
     * smemo (kvota, ze v teku, vrsta polna) - takrat ni zapisa in ni klica [obKoncu]. [obKoncu] tece na delovni niti.
     */
    fun preveri(c: Context, k: String, id: () -> String?, z: TokIzbira.Zmoznosti, seVelja: () -> Boolean, obKoncu: () -> Unit): Boolean {
        if (!nalozi(c)) return false
        if (!vprasati(c, k) || vTeku.size >= F.HKRATI) return false
        // U1: tu kvote ne porabimo (samo pogledamo) - porabi jo zahteva, ki gre res indeksatorju (spodaj).
        if (!omejevalnik.lahko(zdaj())) return false
        if (!vTeku.add(k)) return false
        val app = c.applicationContext
        try {
            delavec.execute {
                try {
                    if (!seVelja()) return@execute
                    val racun = if (Usenet.jeNaVoljo(app)) F.Izid.NA_VOLJO else F.Izid.NE_VEMO
                    val iden = try { id() } catch (_: Exception) { null }
                    var izdaje: List<F.Izdaja>? = null
                    val iscemo = iden != null && UsenetPravila.iscemo(iden)
                    val poslji = F.poslji(racun, iden, iscemo)
                    // V dnevnik samo id IMDb (tt…, tt…:S:E); id dodatka (lahko daljsi niz dodatka) ne.
                    val idDnevnik = if (iscemo) iden else "-"
                    // Kvoto je med cakanjem v vrsti porabil kdo drug: brez zahteve in brez zapisa (naslov se preveri pozneje).
                    // Lastnik 6. 11. 2026: razmik med zahtevami indeksatorju pod 1 s (delovna nit, najvec RAZMIK_MS).
                    val cakaj = if (poslji) omejevalnik.rezerviraj(zdaj()) else 0L
                    if (poslji && cakaj != null && cakaj > 0) try { Thread.sleep(cakaj) } catch (_: InterruptedException) { }
                    if (poslji && cakaj == null) { Log.i(TAG, "preverba: id=$idDnevnik, odlozeno (kvota)"); return@execute }
                    var odgovor: String? = null
                    val t0 = android.os.SystemClock.uptimeMillis()
                    if (poslji && iden != null) {
                        porabljeno.incrementAndGet()
                        val r = Usenet.iskanje(app, iden, F.ROK_MS)
                        if (r.meja) { omejevalnik.meja(zdaj()); Log.i(TAG, "indeksator omejuje hitrost: premor ${omejevalnik.premorSe(zdaj()) / 1000} s") }
                        else if (r.izdaje != null) omejevalnik.uspeh(zdaj())
                        izdaje = r.izdaje?.map { F.Izdaja(it.naslov, it.velikost / 1_073_741_824.0, it.geslo) }
                        odgovor = r.razlog.ifBlank { null }
                    }
                    // Lastnik 5. 11. 2026 (zakon solidarnosti): zapis pove tudi, ali izdajo predvaja pomocnik v Linku -
                    // prisotnost pomocnika se presodi ob prikazu, ne tu (ne zapisemo »NI za 6 h« zaradi trenutka brez njega).
                    val pv = F.preverba(F.Izidi(racun, F.Odgovor(izdaje)), pogoji(), z)
                    shrani(app, k, F.zapisi(zapisi[k], pv.izid, pv.oznaka, zdaj(), pv.pomoc))
                    // Tocka d (lastnik 6. 11. 2026): id naslova (IMDb, pri seriji S:E - brez imena) in razlog »ne vemo«; kljuca nikoli.
                    val razlog = if (pv.izid == F.Izid.NE_VEMO) F.razlogNeVemo(racun, iden, iscemo, poslji, odgovor) ?: "?" else "-"
                    Log.i(TAG, "preverba: id=$idDnevnik, izdaj=${izdaje?.size ?: -1}, izid=${pv.izid}, razlog=$razlog, pomoc=${pv.pomoc}, " +
                        "ms=${android.os.SystemClock.uptimeMillis() - t0}, zahtev od zagona=${porabljeno.get()}")
                } finally {
                    vTeku.remove(k)
                    obKoncu()
                }
            }
        } catch (_: RejectedExecutionException) { vTeku.remove(k); return false }
        return true
    }
}
