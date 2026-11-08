package si.safeer.tv.os

import java.util.Locale

/**
 * Videz Doma Medijskega centra na TV (meritev TV 8. 11. 2026, 960x540 dp): stolpci kartic po vrsti, poudarek fokusa,
 * naslovi seznamov z imenom racuna, monogram postaje brez logotipa, kompaktna kartica dejanja in vrstica pomoci.
 * Brez Androida, da jih preizkus preveri v navadnem JVM.
 */
object McVidezPravila {

    /** Vrsta kartic na Domu - po njej je stevilo stolpcev. */
    enum class Tip { NADALJUJ, KVADRAT, PLAKAT, DRUGO }

    /** Vodoravni razmik med karticami vrst Doma (prej 14 dp, merila so se razlikovala). */
    const val RAZMIK_DOMA_DP = 16

    /** Notranji rob kartice (6 dp levo in desno, [GlasbaActivity.kartica]). */
    const val NOTRANJI_ROB_DP = 12

    /** Tip vrste iz zastavic [GlasbaActivity.Vrsta]; Nedavno (male), kanali in cisti plakati ohranijo svoje mere. */
    fun tipVrste(nadaljuj: Boolean, skladbe: Boolean, video: Boolean, mala: Boolean, kanali: Boolean, slicice: Boolean, cisto: Boolean): Tip = when {
        cisto || mala || kanali -> Tip.DRUGO
        nadaljuj -> Tip.NADALJUJ
        slicice -> Tip.DRUGO
        video -> Tip.PLAKAT
        skladbe -> Tip.KVADRAT
        else -> Tip.DRUGO
    }

    /**
     * Stolpci vrste na Domu (TV): Nadaljuj z ogledom 4 (prej 3 po ~230 dp), kvadrati glasbe in seznamov 7 (prej 8),
     * plakati ostanejo pri 6-7 (mreze Nedavno dodano in Priporoceno ostanejo mreze). null = mere ostanejo dosedanje.
     */
    fun stolpciDoma(tip: Tip, dosedanji: Int): Int? = when (tip) {
        Tip.NADALJUJ -> 4
        Tip.KVADRAT -> 7
        Tip.PLAKAT -> dosedanji.coerceIn(6, 7)
        Tip.DRUGO -> null
    }

    /** Sirina slike kartice (dp), da gre [stolpcev] kartic z robom in razmikom v [sirinaDp]. */
    fun sirinaKartice(sirinaDp: Int, stolpcev: Int): Int =
        (sirinaDp / stolpcev.coerceAtLeast(1) - NOTRANJI_ROB_DP - RAZMIK_DOMA_DP).coerceAtLeast(1)

    /** Povecava kartice s fokusom; ob zmanjsanem gibanju je ni (ostane samo obroba). */
    fun povecavaFokusa(fokus: Boolean, zmanjsanoGibanje: Boolean): Float = if (fokus && !zmanjsanoGibanje) 1.04f else 1f

    /** Trajanje povecave fokusa (ms). */
    const val TRAJANJE_FOKUSA_MS = 120L

    /** Locilo med imenom seznama in racunom ali kanalom (»Oglej si pozneje · Kanal A«). */
    const val LOCILO = " · "

    /**
     * TV 8. 11. 2026: dva seznama »Oglej si pozneje · Kanal A/B« sta bila videti enaka, ker je bil racun odrezan.
     * Vrne (naslov, racun): naslov je besedilo pred zadnjim locilom, racun za njim; brez locila racuna ni.
     */
    fun razdeliNaslovSeznama(ime: String): Pair<String, String> {
        val i = ime.lastIndexOf(LOCILO)
        if (i < 0) return ime to ""
        val naslov = ime.substring(0, i).trim()
        val racun = ime.substring(i + LOCILO.length).trim()
        return if (naslov.isEmpty() || racun.isEmpty()) ime to "" else naslov to racun
    }

    /** Podnaslov kartice seznama: »racun · N posnetkov«, brez racuna samo stevilo. */
    fun podnaslovSeznama(racun: String, stevilo: String): String = if (racun.isBlank()) stevilo else racun + LOCILO + stevilo

    /** Besede, ki postaje ne locijo (skoraj vsaka je »Radio ...«) - izpustimo jih, ce so v imenu se druge. */
    private val SPLOSNE_BESEDE = setOf("radio", "radijo", "rádio", "radyo")

    /** Do dve zacetnici imena postaje (»Radio Center« -> »C«, »Val 202« -> »V2«); prazno, ce v imenu ni crk ali stevk. */
    fun inicialkePostaje(ime: String): String {
        val besede = ime.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (besede.isEmpty()) return ""
        val izbrane = besede.filterNot { it.lowercase(Locale.ROOT) in SPLOSNE_BESEDE }.ifEmpty { besede }
        return izbrane.take(2).joinToString("") { String(Character.toChars(it.codePointAt(0))) }.uppercase(Locale.ROOT)
    }

    /** Barva monograma iz imena: vedno ista za isto postajo (tudi z zvezdico priljubljene), med [barv] barvami. */
    fun indeksBarve(ime: String, barv: Int): Int {
        if (barv <= 0) return 0
        val h = ime.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.fold(0) { h, c -> (h * 31 + c.code) and 0x7fffffff }
        return h % barv
    }

    /** Kartica dejanja brez slike (»Pokazi vse«, »Pocisti«, »Poglej, kaj igra«) je na Domu kompaktna ploscica. */
    fun kompaktnoDejanje(dejanje: Boolean, imaSliko: Boolean): Boolean = dejanje && !imaSliko

    /** Visina slike kompaktne ploscice: pol visine kartice, a ne manj kot [najmanj] (ce je kartica sama visja). */
    fun visinaDejanja(visina: Int, najmanj: Int): Int = maxOf(visina / 2, minOf(visina, najmanj))

    /** Vrstica pomoci (tipke daljinca) zbledi po toliko ms brez pritiska tipke. */
    const val POMOC_SKRIJ_MS = 10_000L

    /** Bledenje vrstice pomoci (ms); ob zmanjsanem gibanju takoj. */
    const val POMOC_ZBLEDI_MS = 300L

    /** Ali je vrstica pomoci vidna [odTipke] ms po zadnjem pritisku tipke. */
    fun pomocVidna(odTipke: Long): Boolean = odTipke < POMOC_SKRIJ_MS
}
