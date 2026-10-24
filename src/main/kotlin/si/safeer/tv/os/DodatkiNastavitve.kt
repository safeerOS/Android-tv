package si.safeer.tv.os

import android.content.Context

/**
 * Naloga 124: vrednosti nastavitev dodatkov (protokol v1, docs/DODATKI-PROTOKOL.md 4) na tej napravi. Navadne vrednosti
 * so v nastavitvah aplikacije; skrivnosti (`secret`) sifrirane s kljucem v Android Keystore ([SporocilaSkrivnosti],
 * AES-GCM) - nikoli v dnevniku, nikoli prikazane nazaj. Kljuc zapisa je id osnove dodatka ([DodatkiProtokol.idOsnove],
 * SHA-256 cele osnove) + kljuc nastavitve. Do pregleda 25. 10. 2026 je bil id 32-bitni String.hashCode osnove: dva dodatka z
 * enakim hashCode (lahko ga je namerno izracunati) sta si delila nastavitve in skrivnosti. Stare zapise enkrat preselimo
 * ([preseli]), samo kadar je jasno, cigavi so ([DodatkiProtokol.smeSeliti]).
 */
object DodatkiNastavitve {
    private const val PREFS = "safeer_dodatki_nastavitve"
    private const val PREDPONA_VERZIJE = "verzija|"
    private const val PREDPONA_NOVI = "s2|"
    private const val PREDPONA_SELITVE = "selitev|"

    private fun kljuc(osnova: String, k: String) = PREDPONA_NOVI + DodatkiProtokol.idOsnove(osnova) + "|" + k
    private fun imeSkrivnosti(osnova: String, k: String) = "dodatek|" + kljuc(osnova, k)
    private fun kljucVerzije(osnova: String) = PREDPONA_VERZIJE + PREDPONA_NOVI + DodatkiProtokol.idOsnove(osnova)
    // Stara oblika (samo za selitev in pozabo): 32-bitni hashCode osnove.
    private fun stariKljuc(osnova: String, k: String) = DodatkiProtokol.stariIdOsnove(osnova) + "|" + k
    private fun staroImeSkrivnosti(osnova: String, k: String) = "dodatek|" + stariKljuc(osnova, k)
    private fun stariKljucVerzije(osnova: String) = PREDPONA_VERZIJE + DodatkiProtokol.stariIdOsnove(osnova)
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Osnove vseh namescenih dodatkov Stremio; null, ce seznama ni mogoce prebrati (takrat ne selimo). */
    private fun vseOsnove(c: Context): List<String>? = try {
        MedijskiViri.vsi(c.applicationContext).filter { it.jeStremio }.map { Stremio.osnova(it.naslov) }
    } catch (_: Throwable) { null }

    /** Ali stari zapisi osnove [osnova] nedvoumno pripadajo njej (noben drug namescen dodatek nima istega starega id-ja). */
    private fun nedvoumno(c: Context, osnova: String): Boolean =
        vseOsnove(c)?.let { DodatkiProtokol.smeSeliti(osnova, it) } ?: false

    private fun oznakaSelitve(osnova: String, k: String) = PREDPONA_SELITVE + DodatkiProtokol.idOsnove(osnova) + "|" + k

    /**
     * Enkratna selitev starih zapisov za kljuce [kljuci] (kljuc, skrivnost) osnove [osnova] v novo obliko: stara vrednost
     * gre v novi kljuc samo, ce novega se ni, nato se stari zapis odstrani. Ob dvoumnem starem id-ju (trcenje z drugim
     * namescenim dodatkom) se nic ne preseli - vrednost uporabnik vpise znova; poskusi se znova ob naslednjem branju.
     * Vsak kljuc ima svojo oznako, ker klicatelji podajo tudi le del opisa (po http brez skrivnosti).
     */
    private fun preseli(c: Context, osnova: String, kljuci: List<Pair<String, Boolean>>) {
        val p = prefs(c)
        val odprti = kljuci.filter { !p.getBoolean(oznakaSelitve(osnova, it.first), false) }
        if (odprti.isEmpty()) return
        synchronized(this) {
            val e = p.edit()
            var nedvoumna: Boolean? = null
            for ((k, skrivnost) in odprti) {
                if (p.getBoolean(oznakaSelitve(osnova, k), false)) continue
                val staro = if (skrivnost) SporocilaSkrivnosti.preberi(c, staroImeSkrivnosti(osnova, k)) else p.getString(stariKljuc(osnova, k), null)
                if (staro != null) {
                    if (nedvoumna == null) nedvoumna = nedvoumno(c, osnova)
                    if (nedvoumna != true) continue
                    if (skrivnost) {
                        val ok = SporocilaSkrivnosti.preberi(c, imeSkrivnosti(osnova, k)) != null ||
                            SporocilaSkrivnosti.shrani(c, imeSkrivnosti(osnova, k), staro)
                        if (!ok) continue
                        SporocilaSkrivnosti.pozabi(c, staroImeSkrivnosti(osnova, k))
                    } else {
                        if (!p.contains(kljuc(osnova, k))) e.putString(kljuc(osnova, k), staro)
                        e.remove(stariKljuc(osnova, k))
                    }
                }
                e.putBoolean(oznakaSelitve(osnova, k), true)
            }
            e.commit()
        }
    }

    private fun kljuciOpisa(opis: List<DodatkiProtokol.Nastavitev>) = opis.map { it.kljuc to it.skrivnost }

    /** Shranjene vrednosti za [opis] (manjkajoce izpusti - glava vzame privzeto). */
    fun vrednosti(c: Context, osnova: String, opis: List<DodatkiProtokol.Nastavitev>): Map<String, String> {
        preseli(c, osnova, kljuciOpisa(opis))
        val p = prefs(c)
        return opis.mapNotNull { n ->
            val v = if (n.skrivnost) SporocilaSkrivnosti.preberi(c, imeSkrivnosti(osnova, n.kljuc)) else p.getString(kljuc(osnova, n.kljuc), null)
            v?.let { n.kljuc to it }
        }.toMap()
    }

    /** Ali je skrivnost [n] shranjena (za »shranjeno« v polju - vrednost se ne pokaze). */
    fun imaSkrivnost(c: Context, osnova: String, n: DodatkiProtokol.Nastavitev): Boolean {
        preseli(c, osnova, listOf(n.kljuc to n.skrivnost))
        return SporocilaSkrivnosti.preberi(c, imeSkrivnosti(osnova, n.kljuc)) != null
    }

    /**
     * Shrani vrednost; prazna skrivnost pomeni »pusti, kot je« (polje za geslo je prazno tudi, ko je shranjena),
     * [pobrisi] jo odstrani. Vrne false, ce sifriranje ni uspelo.
     */
    fun shrani(c: Context, osnova: String, n: DodatkiProtokol.Nastavitev, vrednost: String, pobrisi: Boolean = false): Boolean {
        // Najprej selitev: »prazno = pusti, kot je« mora pustiti tudi se nepreseljeno staro skrivnost.
        preseli(c, osnova, listOf(n.kljuc to n.skrivnost))
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

    /**
     * Odstranjen dodatek: vse njegove vrednosti, skrivnosti in oznake selitve izginejo. Zapise v stari obliki odstrani samo,
     * ce nedvoumno pripadajo temu dodatku - zapisa drugega dodatka z enakim starim id-jem ne sme izbrisati.
     */
    fun pozabiDodatek(c: Context, osnova: String, opis: List<DodatkiProtokol.Nastavitev>) {
        val stareTudi = nedvoumno(c, osnova)
        val e = prefs(c).edit()
        for (n in opis) {
            e.remove(kljuc(osnova, n.kljuc)); e.remove(oznakaSelitve(osnova, n.kljuc))
            if (n.skrivnost) SporocilaSkrivnosti.pozabi(c, imeSkrivnosti(osnova, n.kljuc))
            if (stareTudi) {
                e.remove(stariKljuc(osnova, n.kljuc))
                if (n.skrivnost) SporocilaSkrivnosti.pozabi(c, staroImeSkrivnosti(osnova, n.kljuc))
            }
        }
        e.remove(kljucVerzije(osnova))
        if (stareTudi) e.remove(stariKljucVerzije(osnova))
        e.apply()
    }

    /** Videna verzija dodatka (za seznam sprememb); stari zapis se ob prvem branju preseli, ce je nedvoumen. */
    fun videnaVerzija(c: Context, osnova: String): String? {
        val p = prefs(c)
        p.getString(kljucVerzije(osnova), null)?.let { return it }
        val stara = p.getString(stariKljucVerzije(osnova), null) ?: return null
        if (!nedvoumno(c, osnova)) return null
        p.edit().putString(kljucVerzije(osnova), stara).remove(stariKljucVerzije(osnova)).apply()
        return stara
    }

    fun zapomniVerzijo(c: Context, osnova: String, verzija: String) {
        prefs(c).edit().putString(kljucVerzije(osnova), verzija).apply()
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
