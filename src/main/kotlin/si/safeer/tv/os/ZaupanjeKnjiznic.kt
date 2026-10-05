package si.safeer.tv.os

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import si.safeer.tv.os.RazpolozljivostPravila as P

/**
 * Ali lastna knjiznica dodatka res da tokove ([RazpolozljivostPravila.Zaupanje]) - po dodatku, prezivi ponovni zagon.
 * Lastnik, 5. 10. 2026: »samo tok, ki ga lahko predvajamo«. Dodatek, ki je svojo knjiznico nasteval, tokov zanjo pa ni
 * dajal, je mrezo napolnil s karticami, ki se jih ni dalo predvajati. Zdaj knjiznica pride na zaslon sele po dokazu:
 * vzorec treh naslovov v ozadju ([Stremio.vzorciKnjiznico]) ali uspel dotik. Kljuc v shrambi je odtis naslova dodatka
 * (v naslovu je lahko zeton).
 */
object ZaupanjeKnjiznic {
    private const val DATOTEKA = "safeer_zaupanje_knjiznic"
    /** Po vzorcu brez odgovora (izpad, omejitev poizvedb) knjiznice nekaj casa ne vzorcimo znova - in je ne kazemo. */
    private const val POSKUS_VELJA = 10 * 60_000L
    /** Vzorec, ki se v tem casu ni koncal (zaslon je bil zaprt, preden je prisel na vrsto), ne zadrzuje novega. */
    private const val VZOREC_NAJDLJE = 60_000L
    private val stanja = ConcurrentHashMap<String, P.Zaupanje>()
    private val vVzorcenju = ConcurrentHashMap<String, Long>()
    private val poskusOb = ConcurrentHashMap<String, Long>()
    @Volatile private var shramba: android.content.SharedPreferences? = null

    fun pripravi(c: Context) {
        if (shramba != null) return
        synchronized(stanja) {
            if (shramba != null) return
            try {
                val p = c.applicationContext.getSharedPreferences(DATOTEKA, Context.MODE_PRIVATE)
                p.all.forEach { (k, v) -> P.zaupanjeIzNiza(v as? String)?.let { stanja.putIfAbsent(k, it) } }
                shramba = p
            } catch (_: Exception) { }
        }
    }

    private fun kljuc(osnova: String) = P.odtisNaslova(osnova)

    private fun zapisi(k: String, z: P.Zaupanje) {
        stanja[k] = z
        try { shramba?.edit()?.putString(k, P.zaupanjeVNiz(z))?.apply() } catch (_: Exception) { }
    }

    /**
     * true = knjiznica dokazano daje tokove; false = dokazano jih ne daje (ali pa dodatek ta hip ne odgovarja); null =
     * se ne vemo, vzorec se ni odgovoril. Brez omrezja.
     */
    fun stanje(osnova: String): Boolean? {
        val k = kljuc(osnova)
        val zdaj = System.currentTimeMillis()
        P.zaupanje(stanja[k], zdaj)?.let { return it }
        val poskus = poskusOb[k] ?: return null
        return if (zdaj - poskus in 0..POSKUS_VELJA) false else null
    }

    /** Ali je treba knjiznico zdaj vzorciti; true jo oznaci kot »v vzorcenju« - klicatelj nato poklice [koncaj] ali [preklici]. */
    fun zacni(osnova: String): Boolean {
        val k = kljuc(osnova)
        val zdaj = System.currentTimeMillis()
        if (!P.vzorciti(stanja[k], zdaj)) return false
        poskusOb[k]?.let { if (zdaj - it in 0..POSKUS_VELJA) return false }
        vVzorcenju[k]?.let { if (zdaj - it in 0..VZOREC_NAJDLJE) return false }
        vVzorcenju[k] = zdaj
        return true
    }

    /** Izid vzorca: true / false = dokaz; null = dodatek ni odgovoril - poskusimo pozneje, zadnji dokaz ostane. */
    fun koncaj(osnova: String, izid: Boolean?) {
        val k = kljuc(osnova)
        val zdaj = System.currentTimeMillis()
        if (izid != null) { zapisi(k, P.poVzorcu(izid, zdaj)); poskusOb.remove(k) } else poskusOb[k] = zdaj
        vVzorcenju.remove(k)
    }

    /** Vzorec se ni zacel (zaslon se zapira): nic ne vemo in nic si ne zapomnimo. */
    fun preklici(osnova: String) { vVzorcenju.remove(kljuc(osnova)) }

    /** Dotik naslova iz knjiznice: dodatek je tok dal ([uspeh]) ali je odgovoril, da ga nima. */
    fun poDotiku(osnova: String, uspeh: Boolean) {
        val k = kljuc(osnova)
        val prej = stanja[k]
        val z = P.poDotiku(prej, uspeh, System.currentTimeMillis())
        if (z != prej) zapisi(k, z)
        if (uspeh) poskusOb.remove(k)
    }
}
