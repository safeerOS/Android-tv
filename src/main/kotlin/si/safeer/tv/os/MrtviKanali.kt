package si.safeer.tv.os

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Kanali in postaje, ki pri viru ta cas ne delujejo: dotik je dal napako vira ([OsPravila.mrtevKanal]) ali pa dodatek
 * zanje nima prenosa. Do izteka ([OsPravila.MRTEV_KANAL_MS]) jih ne kazemo - kar se ne da predvajati, ne kazemo
 * (lastnik). Potem se kanal vrne in se preveri znova. Zraven: kanali, za katere vemo, da prenos imajo
 * ([OsPravila.ZIV_KANAL_MS]) - mreza kanalov jih pokaze brez novega vprasanja. Hranimo samo id kartice in cas izteka,
 * na tej napravi.
 */
object MrtviKanali {
    private const val PREFS = "safeer_mrtvi_kanali"
    // »2«: odgovor »ziv« od 0.5.53 pomeni, da je odgovoril streznik toka ([SondaToka]), ne samo dodatek - stari ne veljajo.
    private const val PREFS_ZIVI = "safeer_zivi_kanali_2"
    /** Naloga 75: zaporedni negotovi izidi sonde ([OsPravila.IzidSonde.NEGOTOVO]), zapis »stevilo;cas zadnjega«. */
    private const val PREFS_NEGOTOVI = "safeer_negotovi_kanali"
    /** Naloga 90d: kanali, ki jih je uporabnik sam oznacil kot nedelujoce - trajno, brez poteka (kljuc kartice). */
    private const val PREFS_SKRITI = "safeer_skriti_kanali"
    private val skriti: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val doKdaj = ConcurrentHashMap<String, Long>()
    private val zivDo = ConcurrentHashMap<String, Long>()
    private val negotovi = ConcurrentHashMap<String, Pair<Int, Long>>()
    @Volatile private var nalozeno = false

    /** Zaslon se prijavi, da kartico umakne takoj, ko kanal odpove (kljuc kartice). */
    val poslusalci = CopyOnWriteArraySet<(String) -> Unit>()

    private fun nalozi(c: Context) {
        if (nalozeno) return
        synchronized(this) {
            if (nalozeno) return
            val zdaj = System.currentTimeMillis()
            for ((ime, cilj) in listOf(PREFS to doKdaj, PREFS_ZIVI to zivDo)) {
                val p = c.applicationContext.getSharedPreferences(ime, Context.MODE_PRIVATE)
                val potekli = ArrayList<String>()
                for ((k, v) in p.all) {
                    val t = (v as? Long) ?: continue
                    if (t > zdaj) cilj[k] = t else potekli += k
                }
                if (potekli.isNotEmpty()) p.edit().apply { potekli.forEach { remove(it) } }.apply()
            }
            val pn = c.applicationContext.getSharedPreferences(PREFS_NEGOTOVI, Context.MODE_PRIVATE)
            val stari = ArrayList<String>()
            for ((k, v) in pn.all) {
                val d = (v as? String)?.split(";")
                val n = d?.getOrNull(0)?.toIntOrNull()
                val kdaj = d?.getOrNull(1)?.toLongOrNull()
                if (n != null && kdaj != null && zdaj - kdaj in 0 until OsPravila.NEGOTOV_VELJA_MS) negotovi[k] = n to kdaj else stari += k
            }
            if (stari.isNotEmpty()) pn.edit().apply { stari.forEach { remove(it) } }.apply()
            skriti.addAll(c.applicationContext.getSharedPreferences(PREFS_SKRITI, Context.MODE_PRIVATE).all.keys)
            nalozeno = true
        }
    }

    /**
     * Naloga 90d: uporabnik je kartico (vse njene razlicice, [kljuci] po [KanaliPravila.kljuciKartice]) oznacil kot nedelujoco:
     * trajno skrita, dokler je uporabnik ne vrne. Poslusalci jo umaknejo takoj.
     */
    /** Naloga 90d: koliko kartic je uporabnik skril sam. */
    fun steviloSkritih(c: Context): Int { nalozi(c); return skriti.size }

    /** Naloga 90d: vrne vse kanale, ki jih je uporabnik skril (izbira v mrezi TV v zivo). */
    fun vrniSkrite(c: Context) {
        nalozi(c)
        skriti.clear()
        c.applicationContext.getSharedPreferences(PREFS_SKRITI, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun skrij(c: Context, kljuci: List<String>) {
        nalozi(c)
        val e = c.applicationContext.getSharedPreferences(PREFS_SKRITI, Context.MODE_PRIVATE).edit()
        for (k in kljuci) { skriti += k; zivDo.remove(k); e.putBoolean(k, true) }
        e.apply()
        for (k in kljuci) for (p in poslusalci) try { p(k) } catch (_: Throwable) { }
    }

    fun zapomni(c: Context, id: String) {
        nalozi(c)
        val k = OsPravila.kljucKanala(id)
        val t = System.currentTimeMillis() + OsPravila.MRTEV_KANAL_MS
        doKdaj[k] = t
        zivDo.remove(k)
        pozabiNegotovega(c, k)
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(k, t).apply()
        c.applicationContext.getSharedPreferences(PREFS_ZIVI, Context.MODE_PRIVATE).edit().remove(k).apply()
        for (p in poslusalci) try { p(k) } catch (_: Throwable) { }
    }

    fun jeMrtev(c: Context, id: String): Boolean {
        nalozi(c)
        if (doKdaj.isEmpty() && skriti.isEmpty()) return false
        val k = OsPravila.kljucKanala(id)
        if (k in skriti) return true
        val t = doKdaj[k] ?: return false
        if (t > System.currentTimeMillis()) return true
        doKdaj.remove(k)
        return false
    }

    /**
     * Dodatek ima za kanal prenos (preverjeno v ozadju). Naloga 75: mrtev ni za vedno - kanal, ki je bil mrtev (napaka
     * predvajanja, prejsnji pregled) in ga rocni »Preveri« najde zivega, se vrne takoj, ne sele po izteku.
     */
    fun zapomniZivega(c: Context, id: String) {
        nalozi(c)
        val k = OsPravila.kljucKanala(id)
        val t = System.currentTimeMillis() + OsPravila.ZIV_KANAL_MS
        zivDo[k] = t
        pozabiNegotovega(c, k)
        if (doKdaj.remove(k) != null) c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(k).apply()
        c.applicationContext.getSharedPreferences(PREFS_ZIVI, Context.MODE_PRIVATE).edit().putLong(k, t).apply()
    }

    /**
     * Naloga 75: sonda je dala negotov izid (casovna omejitev ob delujocem omrezju, 5xx). Sam ni dokaz; po
     * [OsPravila.NEGOTOVIH_ZA_MRTEV] zaporednih (v [OsPravila.NEGOTOV_VELJA_MS]) je kanal mrtev. Vrne true, ce je zdaj mrtev.
     */
    // Pregled Kimi 231: beri-povecaj-zapisi mora biti nedeljivo (preverjalci tecejo vzporedno).
    @Synchronized fun zapomniNegotovega(c: Context, id: String): Boolean {
        nalozi(c)
        val k = OsPravila.kljucKanala(id)
        val zdaj = System.currentTimeMillis()
        val prej = negotovi[k]?.takeIf { zdaj - it.second in 0 until OsPravila.NEGOTOV_VELJA_MS }?.first ?: 0
        if (OsPravila.mrtevPoNegotovih(prej + 1)) { zapomni(c, id); return true }
        negotovi[k] = (prej + 1) to zdaj
        c.applicationContext.getSharedPreferences(PREFS_NEGOTOVI, Context.MODE_PRIVATE).edit().putString(k, "${prej + 1};$zdaj").apply()
        return false
    }

    private fun pozabiNegotovega(c: Context, k: String) {
        if (negotovi.remove(k) != null) c.applicationContext.getSharedPreferences(PREFS_NEGOTOVI, Context.MODE_PRIVATE).edit().remove(k).apply()
    }

    /** true = dodatek ima prenos, false = kanal ta cas ne dela, null = se ne vemo (ali je odgovor potekel). */
    fun stanje(c: Context, id: String): Boolean? {
        if (jeMrtev(c, id)) return false
        val k = OsPravila.kljucKanala(id)
        val t = zivDo[k] ?: return null
        if (t > System.currentTimeMillis()) return true
        zivDo.remove(k)
        return null
    }
}
