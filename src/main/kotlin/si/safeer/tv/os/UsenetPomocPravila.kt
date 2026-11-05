package si.safeer.tv.os

/**
 * Pomocnik za Usenet v Safeer Linku (lastnik 5. 11. 2026, zakon solidarnosti) - cista pravila brez Androida
 * (tests/UsenetPomocPravilaTest.kt).
 *
 * Filter »⚡ Usenet« pokaze izdajo, katere kodeka TV ne zna, samo ce je ZDAJ v Linku naprava, ki: sme pomagati
 * (host.info -> pomoc.lahko, ocena SprotnaPomoc > 0: kodirnik H.264 vsaj 1080p), ima svoj Usenet (lasten ponudnik NNTP)
 * in ISTI indeksator (host.info -> usenet.ix == UsenetPravila.odtisIzdaj nasega indeksatorja). Najsibkejsa naprava dela
 * najmanj: pomocnik izdajo prenese in bere sam s svojim racunom, TV mu pove samo guid, ime in odtis - nikoli kljuca.
 *
 * Seznam pomocnikov osvezimo najvec enkrat na [OSVEZI_MS] (host.info vsem napravam z rokom [ROK_MS]) in velja najvec
 * [VELJA_MS]; brez osvezitve ga ne uporabimo (pomocnik je lahko odsel). Zanka brez meje ni dovoljena: osvezitev sprozi
 * samo prikaz mreze ali predvajanje, nikoli sama sebe.
 */
object UsenetPomocPravila {
    const val OSVEZI_MS = 60_000L
    const val VELJA_MS = 3 * 60_000L
    const val ROK_MS = 2_500L
    /** Ponudnik v izidu samodejne izbire (GlasbaActivity.Pripravljeno): predvaja pomocnik v Linku, ne ta naprava. */
    const val PONUDNIK = "pomocnik"
    /** Predpona »naslova« izdaje, ki jo predvaja pomocnik (ni omrezni naslov - ne gre v predvajalnik). */
    const val PREDPONA = "safeer-pomoc:"

    /**
     * Ali naprava pride v postev kot pomocnik za Usenet: [lahko] (usenet.lahko iz host.info), [ocena] pomocnika za
     * pretvorbo (SprotnaPomoc.ocena), [ix] odtis njenega indeksatorja, [moj] odtis nasega. Brez katerega koli: ne.
     */
    fun ustreza(lahko: Boolean, ocena: Long, ix: String?, moj: String?): Boolean =
        lahko && ocena > 0 && !moj.isNullOrBlank() && !ix.isNullOrBlank() && ix == moj

    /** Sme nova osvezitev: prva, ali [OSVEZI_MS] po zadnji (ura nazaj = sme). */
    fun smemOsveziti(zadnja: Long, zdaj: Long): Boolean = zadnja <= 0L || zdaj - zadnja !in 0 until OSVEZI_MS

    /** Seznam iz osvezitve ob [cas] se velja ob [zdaj]. */
    fun velja(cas: Long, zdaj: Long): Boolean = cas > 0L && zdaj - cas in 0..VELJA_MS

    /**
     * Pomocniki, ki so ZDAJ v Linku: iz zadnje osvezitve ([znani], urejeni po oceni) samo tisti, ki so med [vLinku] -
     * naprava, ki odide, izgine takoj (ne sele ob naslednji osvezitvi).
     */
    fun zdaj(znani: List<String>, cas: Long, zdaj: Long, vLinku: Set<String>): List<String> =
        if (!velja(cas, zdaj)) emptyList() else znani.filter { it in vLinku }

    /** Identiteta izdaje za `video.stream` (polje `usenet`): samo veljaven guid, ime do 200 znakov in odtis; sicer null. */
    fun identiteta(guid: String?, ime: String, ix: String?): Triple<String, String, String>? {
        val g = UsenetPravila.guid(guid) ?: return null
        if (ix.isNullOrBlank() || !Regex("[0-9a-f]{16}").matches(ix)) return null
        return Triple(g, ime.replace(Regex("\\s+"), " ").trim().take(200), ix)
    }
}
