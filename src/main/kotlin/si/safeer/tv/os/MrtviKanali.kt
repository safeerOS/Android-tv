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
    // »3« (25. 10. 2026): »ziv« pomeni, da odgovorijo seznam HLS, razlicica, kljuc in kos ([SondaHlsPravila]) - prej je bil
    // ziv ze kanal, katerega glavni seznam je odgovoril (ABC News Live: vse razlicice 404); stari odgovori ne veljajo.
    private const val PREFS_ZIVI = "safeer_zivi_kanali_3"
    /** Enkratne selitve oznak (kljuc = selitev, ki je ze opravljena). */
    private const val PREFS_SELITEV = "safeer_mrtvi_kanali_selitev"
    private const val SELITEV_SONDA_HLS = "sonda-hls-1"
    /** Naloga 75: zaporedni negotovi izidi sonde ([OsPravila.IzidSonde.NEGOTOVO]), zapis »stevilo;cas zadnjega«. */
    private const val PREFS_NEGOTOVI = "safeer_negotovi_kanali"
    /** Naloga 90d: kanali, ki jih je uporabnik sam oznacil kot nedelujoce - trajno, brez poteka (kljuc kartice). */
    private const val PREFS_SKRITI = "safeer_skriti_kanali"
    /** Naloga 108: razlog in ime oznacenega kanala (»razlog\tIme«, [NedelujocePravila.vZapis]) - za izbiro »Ne delujejo«. */
    private const val PREFS_OPIS = "safeer_mrtvi_kanali_opis"
    private val opisi = ConcurrentHashMap<String, String>()
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
            // Naloga 108: opis samo za kanale, ki so se oznaceni (ostale pocistimo).
            val po = c.applicationContext.getSharedPreferences(PREFS_OPIS, Context.MODE_PRIVATE)
            val brezOznake = ArrayList<String>()
            for ((k, v) in po.all) if (doKdaj.containsKey(k)) (v as? String)?.let { opisi[k] = it } else brezOznake += k
            if (brezOznake.isNotEmpty()) po.edit().apply { brezOznake.forEach { remove(it) } }.apply()
            preseli(c.applicationContext)
            nalozeno = true
        }
    }

    /**
     * 25. 10. 2026, enkrat: oznake z razlogom HTTP 4xx izpred popravka predvajalnika in sonde HLS se pozabijo
     * ([NedelujocePravila.preveriZnovaPoPopravkuSonde]) - kanal s preusmeritvijo je predvajalnik prej iskal pri napacnem
     * strezniku (HTTP 414/404, kanal pa dela). Nova sonda jih preveri znova (vidne takoj, ostale pregled v ozadju).
     */
    private fun preseli(app: Context) {
        val ps = app.getSharedPreferences(PREFS_SELITEV, Context.MODE_PRIVATE)
        if (ps.getBoolean(SELITEV_SONDA_HLS, false)) return
        val pozabi = doKdaj.keys.filter { NedelujocePravila.preveriZnovaPoPopravkuSonde(NedelujocePravila.izZapisa(opisi[it]).first) }
        if (pozabi.isNotEmpty()) {
            pozabi.forEach { doKdaj.remove(it); opisi.remove(it) }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply { pozabi.forEach { remove(it) } }.apply()
            app.getSharedPreferences(PREFS_OPIS, Context.MODE_PRIVATE).edit().apply { pozabi.forEach { remove(it) } }.apply()
        }
        android.util.Log.i("SafeerOsMedia", "mrtvi kanali: selitev sonde HLS, znova preverim ${pozabi.size} oznak HTTP 4xx")
        ps.edit().putBoolean(SELITEV_SONDA_HLS, true).apply()
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

    /** Naloga 108: [razlog] ([NedelujocePravila]) in [ime] kanala za izbiro »Ne delujejo« (brez naslova toka). */
    fun zapomni(c: Context, id: String, razlog: String = NedelujocePravila.NI_ZNANO, ime: String = "") {
        nalozi(c)
        val k = OsPravila.kljucKanala(id)
        val t = System.currentTimeMillis() + OsPravila.MRTEV_KANAL_MS
        doKdaj[k] = t
        // Brez novega imena ostane staro (oznaka iz ozadja ime pogosto pozna, predvajalnik pa ne).
        val ostaneIme = ime.ifBlank { NedelujocePravila.izZapisa(opisi[k]).second }
        val zapis = NedelujocePravila.vZapis(razlog, ostaneIme)
        opisi[k] = zapis
        c.applicationContext.getSharedPreferences(PREFS_OPIS, Context.MODE_PRIVATE).edit().putString(k, zapis).apply()
        zivDo.remove(k)
        pozabiNegotovega(c, k)
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(k, t).apply()
        c.applicationContext.getSharedPreferences(PREFS_ZIVI, Context.MODE_PRIVATE).edit().remove(k).apply()
        for (p in poslusalci) try { p(k) } catch (_: Throwable) { }
    }

    /** Naloga 121: vsebine ne kazemo nikjer v Medijskem centru ([KanaliPravila.skrijNedelujoc]). */
    fun skrij(c: Context, s: Jamendo.Skladba): Boolean = KanaliPravila.skrijNedelujoc(jeMrtev(c, s.id), IptvSeznami.jeKanal(s),
        KanaliPravila.znanNedelujocTok(s.zvok) || KanaliPravila.znanNedelujocTok(s.povezava))

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
        if (opisi.remove(k) != null) c.applicationContext.getSharedPreferences(PREFS_OPIS, Context.MODE_PRIVATE).edit().remove(k).apply()
        c.applicationContext.getSharedPreferences(PREFS_ZIVI, Context.MODE_PRIVATE).edit().putLong(k, t).apply()
    }

    /**
     * Naloga 75: sonda je dala negotov izid (casovna omejitev ob delujocem omrezju, 5xx). Sam ni dokaz; po
     * [OsPravila.NEGOTOVIH_ZA_MRTEV] zaporednih (v [OsPravila.NEGOTOV_VELJA_MS]) je kanal mrtev. Vrne true, ce je zdaj mrtev.
     */
    // Pregled Kimi 231: beri-povecaj-zapisi mora biti nedeljivo (preverjalci tecejo vzporedno).
    @Synchronized fun zapomniNegotovega(c: Context, id: String, razlog: String = NedelujocePravila.NI_ZNANO, ime: String = ""): Boolean {
        nalozi(c)
        val k = OsPravila.kljucKanala(id)
        val zdaj = System.currentTimeMillis()
        val prej = negotovi[k]?.takeIf { zdaj - it.second in 0 until OsPravila.NEGOTOV_VELJA_MS }?.first ?: 0
        if (OsPravila.mrtevPoNegotovih(prej + 1)) { zapomni(c, id, razlog, ime); return true }
        negotovi[k] = (prej + 1) to zdaj
        c.applicationContext.getSharedPreferences(PREFS_NEGOTOVI, Context.MODE_PRIVATE).edit().putString(k, "${prej + 1};$zdaj").apply()
        return false
    }

    private fun pozabiNegotovega(c: Context, k: String) {
        if (negotovi.remove(k) != null) c.applicationContext.getSharedPreferences(PREFS_NEGOTOVI, Context.MODE_PRIVATE).edit().remove(k).apply()
    }

    /** Naloga 108: oznaka kanala [kljuc] izgine (»Preveri znova« pri kanalu, ki ga sonda ne zna preveriti) - preveri ga naslednji klik. */
    fun pozabi(c: Context, kljuc: String) {
        nalozi(c)
        val k = OsPravila.kljucKanala(kljuc)
        if (doKdaj.remove(k) != null) c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(k).apply()
        if (opisi.remove(k) != null) c.applicationContext.getSharedPreferences(PREFS_OPIS, Context.MODE_PRIVATE).edit().remove(k).apply()
        pozabiNegotovega(c, k)
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
