package si.safeer.tv.os

import si.safeer.tv.cast.DostopPravila

/**
 * Povezani zasloni (14. 10. 2026): katere naprave lahko uporabnik gleda in upravlja. Cista pravila brez Androida
 * (tests/ZasloniPravilaTest.kt).
 *
 * Prej so bili na izbiro samo racunalniki z zmoznostjo `desktop`: na telefonu je bil viden samo prenosnik, ne pa
 * drugi telefoni, tablica in televizor, in ne racunalnik, ki deljenja zaslona (se) nima vklopljenega.
 *
 * - [Vrsta.NAMIZJE]: racunalnik deli zaslon (`desktop`) - ZaslonActivity kot doslej.
 * - [Vrsta.ANDROID]: telefon, tablica, televizor z `screen` in `remote` - ukaz `screen.start`; naprava sama vprasa
 *   svojega uporabnika za zajem zaslona (MediaProjection, ob vsaki seji) in pretaka sliko tej napravi.
 * - [Vrsta.NAMIZJE_IZKLOPLJENO]: racunalnik brez `desktop` - prikazan sivo z namigom; dotik poslje obicajno zahtevo,
 *   da racunalnik lahko pokaze prosnjo za dostop (ali pove, da deljenje ni vklopljeno).
 *
 * Ta naprava (njen id in vse oznake z istim jedrom kljuca) ni nikoli na seznamu; ista fizicna naprava pod vec oznakami
 * (sorodniki z istim jedrom) je na seznamu enkrat - z najboljso vrsto.
 */
object ZasloniPravila {
    enum class Vrsta { NAMIZJE, ANDROID, NAMIZJE_IZKLOPLJENO }

    /** Naprava iz seznama Linka, kolikor jo pravila potrebujejo. */
    class Naprava(val id: String, val ime: String, val vloga: String, val platforma: String, val zmoznosti: List<String>)

    class Kandidat(val id: String, val ime: String, val vrsta: Vrsta)

    private val RACUNALNIKI = setOf("linux", "windows", "macos")
    private val ANDROID = setOf("phone", "tablet", "tv")

    fun vrsta(n: Naprava): Vrsta? = when {
        "desktop" in n.zmoznosti -> Vrsta.NAMIZJE
        n.platforma in RACUNALNIKI -> Vrsta.NAMIZJE_IZKLOPLJENO
        // Starejsi sprejemniki platforme ne povedo: Android jih prepozna po zaslonu in daljincu.
        (n.platforma in ANDROID || n.platforma.isBlank()) && "screen" in n.zmoznosti && "remote" in n.zmoznosti -> Vrsta.ANDROID
        else -> null
    }

    /** Kandidati za Povezane zaslone: najprej racunalniki z deljenjem, nato Android naprave, nato izklopljeni racunalniki. */
    fun kandidati(naprave: List<Naprava>, lastniId: String): List<Kandidat> {
        val jaz = if (lastniId.isBlank()) "" else DostopPravila.jedroOznake(lastniId)
        val izbrani = LinkedHashMap<String, Kandidat>()
        for (n in naprave) {
            if (n.id.isBlank() || n.vloga == "hub") continue
            val jedro = DostopPravila.jedroOznake(n.id)
            if (n.id == lastniId || (jaz.isNotEmpty() && jedro == jaz)) continue
            val v = vrsta(n) ?: continue
            val prej = izbrani[jedro]
            if (prej == null || v.ordinal < prej.vrsta.ordinal) izbrani[jedro] = Kandidat(n.id, n.ime.ifBlank { n.id }, v)
        }
        return izbrani.values.sortedBy { it.vrsta.ordinal }
    }

    /** Opis kartice: imena kandidatov, ki jih je mogoce gledati takoj (izklopljeni racunalniki niso nasteti). */
    fun opis(kandidati: List<Kandidat>): List<String> =
        kandidati.filter { it.vrsta != Vrsta.NAMIZJE_IZKLOPLJENO }.map { it.ime }

    /** Kako dolgo ta naprava pricakuje zaslon naprave, ki jo je sama prosila (soglasje za zajem na drugi napravi). */
    const val PRICAKUJ_MS = 3 * 60_000L

    /**
     * Zaslon, ki ga je ta naprava SAMA zahtevala od druge naprave (Povezani zasloni, »Odpri tukaj«). Ko druga naprava
     * deljenje zacne, ga sredisce poslje tej (share.screen) - in to sme odpreti, ceprav ta naprava drugi zaslona ni
     * odprla v Dostopu: uporabnik ga je pravkar izbral sam. Velja [PRICAKUJ_MS] in enkrat.
     */
    class Pricakovani {
        private val zapisi = HashMap<String, Long>()

        @Synchronized fun pricakuj(id: String, zdaj: Long) {
            if (id.isBlank()) return
            zapisi.entries.removeAll { zdaj - it.value >= PRICAKUJ_MS }
            if (zapisi.size >= 16) return
            zapisi[DostopPravila.jedroOznake(id)] = zdaj
        }

        /** Ali je deljenje od [posiljatelj] pricakovano; ob pritrdilnem odgovoru se zapis porabi. */
        @Synchronized fun sprejmi(posiljatelj: String, zdaj: Long): Boolean {
            if (posiljatelj.isBlank()) return false
            val k = DostopPravila.jedroOznake(posiljatelj)
            val t = zapisi[k] ?: return false
            zapisi.remove(k)
            return zdaj - t in 0 until PRICAKUJ_MS
        }
    }

    val pricakovani = Pricakovani()
}
