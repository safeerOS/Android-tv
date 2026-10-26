package si.safeer.tv.os

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * Webtor rezerva (25. 10. 2026): skrivni deli naslovov dodatkov ([SkrivnostiPravila]). Cela osnova dodatka s poverilnico
 * (osebni zeton Webtorja, debrid kljuc v nastavitvah Torrentia ...) je shranjena sifrirana z Android Keystore
 * ([SporocilaSkrivnosti], kot debrid kljuci); v nastavitvah je samo seznam odtisov. Aplikacija pozna le nadomestek
 * (»https://<gostitelj>/safeer-skrito-<odtis>«), pravi naslov vstavimo tik pred omreznim klicem ([dejanski]: Stremio,
 * predvajalnik prek [SpletniVir.virPodatkov], sonda Webtor). Nikoli v dnevnik ali na zaslon.
 *
 * Pregled S4: skrivnosti, ki so samo v TOKOVIH (npr. `/realdebrid/<kljuc>/...`, zeton v poizvedbi), so samo v pomnilniku
 * (najvec [NAJVEC_TOKOV], [VELJA_TOKA_MS]) in ne stejejo v shrambo; tok Webtor ima isto osnovo kot dodatek, zato zanj nove
 * skrivnosti ni. Odstranjen dodatek pozabi svojo osnovo in skrivnosti svojih tokov; dodajanja dodatka shramba ne zavrne.
 * Pregled S5: neberljiva skrivnost (Keystore) - branje se ponovi; ce ostane neberljiva, [pozna] vrne false: na nadomestek ne
 * gre nobena zahteva, Moji viri povedo, naj uporabnik dodatek doda znova (ali ga dobi z naprave v ozjem krogu).
 * Od 26. 10. 2026 tu tudi viri z racunom IPTV ([zavarujCel]): skrivnost je cel naslov seznama ali toka.
 */
object SkrivniDodatki {
    private const val TAG = "SafeerSkrivni"
    private const val PREFS = "safeer_skrivni_dodatki"
    private const val KLJUC_ODTISI = "odtisi"
    /** Opozorilna meja shranjenih osnov (dodatkov s poverilnico); dodajanja ne zavrne. */
    private const val NAJVEC = 200
    private const val NAJVEC_TOKOV = 300
    private const val VELJA_TOKA_MS = 12L * 3600 * 1000
    /** Pravkar dodana skrivnost (okno »Dodaj dodatek« se preverja) ni »zapuscena« [pocisti]. */
    private const val MILOST_MS = 15L * 60 * 1000

    private fun ime(odtis: String) = "dodatek_$odtis"

    /** nadomestna osnova -> prava osnova dodatka (shranjena, sifrirana) */
    private val predpone = ConcurrentHashMap<String, String>()
    private val dodanoOb = ConcurrentHashMap<String, Long>()

    private class TokSkrivnost(val prava: String, val lastnik: String, val cas: Long)
    /** nadomestek toka -> skrivnost (samo v pomnilniku, LRU) */
    private val tokovi = object : LinkedHashMap<String, TokSkrivnost>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TokSkrivnost>?): Boolean = size > NAJVEC_TOKOV
    }

    @Volatile private var nalozeno = false
    @Volatile private var naslednjiPoskus = 0L
    private var poskusov = 0
    /** Koliko shranjenih skrivnosti po vseh poskusih ni bilo mogoce prebrati (Keystore). */
    @Volatile var neberljivih = 0
        private set

    private fun odtisi(c: Context): List<String> = try {
        val a = JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KLJUC_ODTISI, "[]") ?: "[]")
        (0 until a.length()).map { a.optString(it) }.filter { Regex("^[0-9a-f]{16}$").matches(it) }
    } catch (_: Exception) { emptyList() }

    /**
     * Prebere shranjene skrivnosti v pomnilnik. Pregled S5: ce katere ni mogoce prebrati, se branje ponovi (najvec 3-krat,
     * z razmikom), sele nato je stanje koncno.
     */
    fun nalozi(c: Context) {
        if (nalozeno || System.currentTimeMillis() < naslednjiPoskus) return
        synchronized(this) {
            if (nalozeno || System.currentTimeMillis() < naslednjiPoskus) return
            val a = c.applicationContext
            var neuspesnih = 0
            for (o in odtisi(a)) {
                if (predpone.values.any { SkrivnostiPravila.odtis(it) == o }) continue
                val p = SporocilaSkrivnosti.preberi(a, ime(o))
                if (p == null) { neuspesnih++; continue }
                SkrivnostiPravila.nadomestek(p)?.let { predpone[it] = p }
            }
            neberljivih = neuspesnih
            poskusov++
            if (neuspesnih == 0 || poskusov >= 3) nalozeno = true
            else naslednjiPoskus = System.currentTimeMillis() + 20_000L * poskusov
            if (neuspesnih > 0) Log.w(TAG, "neberljivih skrivnih dodatkov: $neuspesnih (poskus $poskusov)")
            else if (predpone.isNotEmpty()) Log.i(TAG, "skrivnih dodatkov: ${predpone.size}")
        }
    }

    /**
     * Naslov dodatka s skrivnostjo -> nadomestek (cela osnova); skrivnost se shrani sifrirana. null = naslov nima skrivnosti
     * ali je shramba ni mogla sifrirati (klicatelj pravega naslova NE shrani).
     */
    fun zavaruj(c: Context, naslov: String): String? {
        val p = SkrivnostiPravila.skrivnaPredponaDodatka(naslov) ?: return null
        val n = SkrivnostiPravila.nadomestek(p) ?: return null
        if (!shraniSkrivnost(c, p, n)) return null
        return SkrivnostiPravila.vNadomestek(naslov)
    }

    /**
     * Vir z racunom IPTV (lastnik, 26. 10. 2026; [IptvPravila.skrivnostVira]): skrivnost je CEL naslov (seznam Xtream
     * get.php?username=&password=, tok /live/<u>/<g>/ ...). Shrani se sifriran, vrne nadomestek »<shema>://<gostitelj>/
     * safeer-skrito-<odtis>« brez poti in poizvedbe; [dejanski] vrne cel pravi naslov. null = sifriranje ni uspelo.
     */
    fun zavarujCel(c: Context, naslov: String): String? {
        val p = naslov.trim()
        if (IptvPravila.skrivnostVira(MedijskiViri.SEZNAM, p) != p) return null
        val n = SkrivnostiPravila.nadomestek(p) ?: return null
        return if (shraniSkrivnost(c, p, n)) n else null
    }

    /** Skrivnost [p] z nadomestkom [n] v sifrirano shrambo (ce je se ni); false = sifriranje ni uspelo. */
    private fun shraniSkrivnost(c: Context, p: String, n: String): Boolean {
        nalozi(c)
        if (predpone[n] != p) synchronized(this) {
            val a = c.applicationContext
            val o = SkrivnostiPravila.odtis(p)
            if (!SporocilaSkrivnosti.shrani(a, ime(o), p) && !SporocilaSkrivnosti.shrani(a, ime(o), p)) {
                Log.w(TAG, "sifriranje ni uspelo"); return false
            }
            val zdaj = odtisi(a)
            if (o !in zdaj) {
                if (zdaj.size >= NAJVEC) Log.w(TAG, "veliko skrivnih dodatkov: ${zdaj.size + 1}")
                a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KLJUC_ODTISI, JSONArray(zdaj + o).toString()).commit()
            }
            predpone[n] = p
        }
        dodanoOb[n] = System.currentTimeMillis()
        return true
    }

    /**
     * Tok dodatka s poverilnico ([lastnik] = osnova dodatka): (1) zacne se z znano osnovo dodatka (tok Webtor
     * `.../stremio/resolve/<JWT>`) -> isti nadomestek kot dodatek (velja tudi na drugih napravah v ozjem krogu); (2) ima
     * svojo skrivnost -> nadomestek samo v pomnilniku; (3) sicer nespremenjen.
     */
    fun zakrijTok(url: String, lastnik: String): String {
        val n = url.trim()
        var najdaljsa: Map.Entry<String, String>? = null
        for (e in predpone.entries) {
            val p = e.value
            if (n.startsWith(p) && (n.length == p.length || n[p.length] in "/?#") && (najdaljsa == null || p.length > najdaljsa.value.length)) najdaljsa = e
        }
        if (najdaljsa != null) return najdaljsa.key + n.substring(najdaljsa.value.length)
        val p = SkrivnostiPravila.skrivnaPredponaToka(n) ?: return n
        val z = SkrivnostiPravila.nadomestek(p) ?: return n
        val osnova = SkrivnostiPravila.nadomestnaOsnova(lastnik) ?: lastnik
        synchronized(tokovi) { tokovi[z] = TokSkrivnost(p, osnova, System.currentTimeMillis()) }
        return z + n.substring(p.length)
    }

    private fun pravaZa(nadomestek: String): String? = predpone[nadomestek] ?: synchronized(tokovi) {
        tokovi[nadomestek]?.takeIf { System.currentTimeMillis() - it.cas < VELJA_TOKA_MS }?.prava
    }

    /** Nadomestek -> pravi naslov za omrezni klic (ce skrivnost poznamo); vse drugo nespremenjeno. */
    fun dejanski(naslov: String): String {
        val n = SkrivnostiPravila.nadomestnaOsnova(naslov) ?: return naslov
        val p = pravaZa(n) ?: return naslov
        return p + naslov.trim().substring(n.length)
    }

    fun dejanski(c: Context?, naslov: String): String {
        if (c != null && SkrivnostiPravila.jeNadomestek(naslov)) nalozi(c)
        return dejanski(naslov)
    }

    /** Pregled S5: nadomestek, za katerega skrivnosti ta naprava ne pozna (ali ni nadomestek: true). */
    fun pozna(c: Context?, naslov: String): Boolean {
        val n = SkrivnostiPravila.nadomestnaOsnova(naslov) ?: return true
        if (c != null) nalozi(c)
        return pravaZa(n) != null
    }

    /** Naslov se zacne s katero od znanih pravih osnov (npr. slika iz kataloga dodatka z zetonom v poti). */
    fun vsebujeSkrivnost(naslov: String): Boolean = naslov.isNotBlank() && predpone.values.any { naslov.startsWith(it) }

    /** Prave osnove (za prehod nastavitev - nikoli v dnevnik ali na zaslon). */
    fun pravePredpone(c: Context): Map<String, String> { nalozi(c); return predpone.entries.associate { it.value to it.key } }

    /** Pozabi skrivnost dodatka [naslov] (nadomestek) in skrivnosti njegovih tokov. */
    fun pozabi(c: Context, naslov: String) {
        val n = SkrivnostiPravila.nadomestnaOsnova(naslov) ?: return
        nalozi(c)
        synchronized(tokovi) { tokovi.entries.removeAll { it.value.lastnik == n } }
        synchronized(this) {
            val p = predpone.remove(n) ?: return
            dodanoOb.remove(n)
            val o = SkrivnostiPravila.odtis(p)
            val a = c.applicationContext
            SporocilaSkrivnosti.pozabi(a, ime(o))
            a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KLJUC_ODTISI, JSONArray(odtisi(a) - o).toString()).apply()
        }
    }

    /**
     * Zapuscene skrivnosti (dodatek ni bil shranjen - preklic ali neuspela preverba): osnove, ki jih ne rabi noben vir
     * ([uporabljene] = nadomestne osnove virov), izbrisemo; pravkar dodane ([MILOST_MS]) ostanejo.
     */
    fun pocisti(c: Context, uporabljene: Set<String>) {
        nalozi(c)
        val zdaj = System.currentTimeMillis()
        val stran = predpone.keys.filter { it !in uporabljene && zdaj - (dodanoOb[it] ?: 0L) > MILOST_MS }
        if (stran.isEmpty()) return
        for (n in stran) pozabi(c, n)
        Log.i(TAG, "pospravljenih zapuscenih skrivnosti: ${stran.size}")
    }
}
