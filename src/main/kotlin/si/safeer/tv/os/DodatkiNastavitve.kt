package si.safeer.tv.os

import android.content.Context

/**
 * Naloga 124: vrednosti nastavitev dodatkov (protokol v1, docs/DODATKI-PROTOKOL.md 4) na tej napravi. Navadne vrednosti
 * so v nastavitvah aplikacije; skrivnosti (`secret`) sifrirane s kljucem v Android Keystore ([SporocilaSkrivnosti],
 * AES-GCM) - nikoli v dnevniku, nikoli prikazane nazaj. Kljuc zapisa je osnova dodatka + kljuc nastavitve.
 */
object DodatkiNastavitve {
    private const val PREFS = "safeer_dodatki_nastavitve"
    private const val PREDPONA_VERZIJE = "verzija|"

    private fun kljuc(osnova: String, k: String) = "${Integer.toHexString(osnova.hashCode())}|$k"
    private fun imeSkrivnosti(osnova: String, k: String) = "dodatek|" + kljuc(osnova, k)
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Shranjene vrednosti za [opis] (manjkajoce izpusti - glava vzame privzeto). */
    fun vrednosti(c: Context, osnova: String, opis: List<DodatkiProtokol.Nastavitev>): Map<String, String> {
        val p = prefs(c)
        return opis.mapNotNull { n ->
            val v = if (n.skrivnost) SporocilaSkrivnosti.preberi(c, imeSkrivnosti(osnova, n.kljuc)) else p.getString(kljuc(osnova, n.kljuc), null)
            v?.let { n.kljuc to it }
        }.toMap()
    }

    /** Ali je skrivnost [n] shranjena (za »shranjeno« v polju - vrednost se ne pokaze). */
    fun imaSkrivnost(c: Context, osnova: String, n: DodatkiProtokol.Nastavitev): Boolean =
        SporocilaSkrivnosti.preberi(c, imeSkrivnosti(osnova, n.kljuc)) != null

    /**
     * Shrani vrednost; prazna skrivnost pomeni »pusti, kot je« (polje za geslo je prazno tudi, ko je shranjena),
     * [pobrisi] jo odstrani. Vrne false, ce sifriranje ni uspelo.
     */
    fun shrani(c: Context, osnova: String, n: DodatkiProtokol.Nastavitev, vrednost: String, pobrisi: Boolean = false): Boolean {
        if (n.skrivnost) {
            if (pobrisi) { SporocilaSkrivnosti.pozabi(c, imeSkrivnosti(osnova, n.kljuc)); return true }
            if (vrednost.isEmpty()) return true
            return SporocilaSkrivnosti.shrani(c, imeSkrivnosti(osnova, n.kljuc), vrednost)
        }
        prefs(c).edit().putString(kljuc(osnova, n.kljuc), vrednost).apply()
        return true
    }

    /** Manjkajoca obvezna nastavitev (dodatek brez nje ne bo deloval) - za oznako v Mojih virih. */
    fun manjkaObvezna(c: Context, osnova: String, opis: List<DodatkiProtokol.Nastavitev>): Boolean {
        val v = vrednosti(c, osnova, opis)
        return opis.any { it.obvezna && v[it.kljuc].isNullOrEmpty() && it.privzeto.isEmpty() }
    }

    /** Odstranjen dodatek: vse njegove vrednosti in skrivnosti izginejo. */
    fun pozabiDodatek(c: Context, osnova: String, opis: List<DodatkiProtokol.Nastavitev>) {
        val e = prefs(c).edit()
        for (n in opis) { e.remove(kljuc(osnova, n.kljuc)); if (n.skrivnost) SporocilaSkrivnosti.pozabi(c, imeSkrivnosti(osnova, n.kljuc)) }
        e.remove(PREDPONA_VERZIJE + Integer.toHexString(osnova.hashCode())).apply()
    }

    fun videnaVerzija(c: Context, osnova: String): String? = prefs(c).getString(PREDPONA_VERZIJE + Integer.toHexString(osnova.hashCode()), null)
    fun zapomniVerzijo(c: Context, osnova: String, verzija: String) {
        prefs(c).edit().putString(PREDPONA_VERZIJE + Integer.toHexString(osnova.hashCode()), verzija).apply()
    }
}

/**
 * Naloga 125: razvojni nacin dodatkov (docs/DODATKI-PROTOKOL.md 7) - privzeto izklopljen. Vklopljen dovoli razsiritve
 * (dejanja, dopolnitev, mape) po http na krajevni naslov in vodi dnevnik odgovorov dodatkov: gostitelj, vrsta zahteve,
 * koda, velikost, cas - nikoli pot, naslov toka ali vrednosti nastavitev.
 */
object DodatkiRazvoj {
    private const val PREFS = "safeer_dodatki_razvoj"
    private const val KLJUC = "vklopljen"
    private const val NAJVEC = 60

    @Volatile var vklopljen = false
        private set

    data class Zapis(val cas: Long, val gostitelj: String, val vrsta: String, val koda: Int, val bajtov: Int, val ms: Long)
    private val dnevnik = java.util.ArrayDeque<Zapis>()

    fun nalozi(c: Context) { vklopljen = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KLJUC, false) }

    fun nastavi(c: Context, da: Boolean) {
        vklopljen = da
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KLJUC, da).apply()
        if (!da) synchronized(dnevnik) { dnevnik.clear() }
    }

    /** Zapis enega odgovora dodatka (samo v razvojnem nacinu, samo v pomnilniku). */
    fun zapisi(url: String, koda: Int, bajtov: Int, ms: Long) {
        if (!vklopljen) return
        val g = try { java.net.URL(url).host.orEmpty() } catch (_: Exception) { "?" }
        synchronized(dnevnik) {
            dnevnik.addFirst(Zapis(System.currentTimeMillis(), g, DodatkiProtokol.vrstaZahteve(url), koda, bajtov, ms))
            while (dnevnik.size > NAJVEC) dnevnik.removeLast()
        }
    }

    fun zapisi(): List<Zapis> = synchronized(dnevnik) { dnevnik.toList() }
}
