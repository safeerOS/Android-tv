package si.safeer.tv.os

import java.text.Normalizer
import java.util.Locale

/**
 * Pravila stranske vrstice, iskalne lupe in iskanja po nastavitvah - brez Androida, da jih preverijo testi v navadnem
 * JVM (tests/VrsticaPravilaTest.kt).
 *
 * Lastnik, 19. 10. 2026: vrstica in lupa vsebini ne smeta jemati prostora, a morata biti enostavno dosegljivi na vseh
 * napravah. Nacin izberemo po sirini okna in vnosu (kot [ZaganjalnikPravila]), ne po vrsti naprave.
 */
object VrsticaPravila {

    enum class Nacin {
        /** Ozko okno na dotik (telefon pokonci, razdeljen zaslon): vrstica je skrita, gumb menija ali poteg z levega
         *  roba jo odpre cez vsebino; dotik vsebine ali Nazaj jo zapre. */
        PREDAL,
        /** Siroko okno na dotik (tablica, telefon lezece): tirnica z ikonami, imena pokaze gumb menija (zapomnjeno). */
        TIRNICA,
        /** Televizor: v vsebini skrcena na ikone, ob fokusu v meniju razsirjena z imeni. */
        DALJINEC,
    }

    /** Pod to sirino okna je vrstica predal (razred velikosti okna \"compact\" iz Androidovih smernic). */
    const val MEJA_PREDALA_DP = 600

    /** Poteg z roba se steje, ce se zacne najvec toliko od levega roba. */
    const val ROB_POTEGA_DP = 24f

    /** Najmanjsa vodoravna pot, da je poteg namerni (ne drsenje seznama). */
    const val PRAG_POTEGA_DP = 40f

    fun nacin(sirinaDp: Int, daljinec: Boolean): Nacin = when {
        daljinec -> Nacin.DALJINEC
        sirinaDp in 1 until MEJA_PREDALA_DP -> Nacin.PREDAL
        else -> Nacin.TIRNICA
    }

    /** Ali vrstica kaze imena ob ikonah. */
    fun zImeni(nacin: Nacin, predalOdprt: Boolean, izbranaImena: Boolean, fokusVMeniju: Boolean): Boolean = when (nacin) {
        Nacin.PREDAL -> predalOdprt
        Nacin.TIRNICA -> izbranaImena
        Nacin.DALJINEC -> fokusVMeniju
    }

    /** Ali vrstica zaseda prostor ob vsebini; predal lebdi cez vsebino in ji ne vzame nic. */
    fun zasedaProstor(nacin: Nacin): Boolean = nacin != Nacin.PREDAL

    /** Sirina odprtega predala: imena cela, a ob robu ostane vidna vsebina (dotik nanjo predal zapre). */
    fun sirinaPredalaDp(sirinaOknaDp: Int, zelenaDp: Int = 280): Int =
        minOf(zelenaDp, (sirinaOknaDp * 0.85f).toInt()).coerceAtLeast(minOf(200, sirinaOknaDp))

    private fun vodoravni(dx: Float, dy: Float): Boolean =
        kotlin.math.abs(dx) >= PRAG_POTEGA_DP && kotlin.math.abs(dx) > 1.5f * kotlin.math.abs(dy)

    /** Poteg z levega roba v desno odpre predal (vrednosti v dp). */
    fun potegOdpre(zacetekX: Float, dx: Float, dy: Float): Boolean = zacetekX <= ROB_POTEGA_DP && dx > 0 && vodoravni(dx, dy)

    /** Poteg v levo (kjerkoli) zapre odprt predal ali skrije imena tirnice. */
    fun potegZapre(dx: Float, dy: Float): Boolean = dx < 0 && vodoravni(dx, dy)

    // ------------------------------------------------------------------ naloga 127: tirnico se da popolnoma skriti

    /** Stanja tirnice na dotik: popolnoma skrita (samo lezece), ikone, ikone z imeni. */
    const val TIRNICA_SKRITA = 0
    const val TIRNICA_IKONE = 1
    const val TIRNICA_IMENA = 2

    fun stanjeTirnice(skrita: Boolean, imena: Boolean, lezece: Boolean): Int = when {
        skrita && lezece -> TIRNICA_SKRITA
        imena -> TIRNICA_IMENA
        else -> TIRNICA_IKONE
    }

    /**
     * Naloga 127 (lastnik): poteg proti sredini zaslona tirnico pokaze za eno stanje (skrita -> ikone -> imena), poteg proti
     * robu jo skrije za eno (imena -> ikone -> skrita). Popolnoma skriti jo je mogoce samo lezece; pokonci (tablica)
     * ostanejo ikone.
     */
    fun tirnicaPoPotegu(stanje: Int, protiSredini: Boolean, lezece: Boolean): Int = when {
        protiSredini -> minOf(stanje + 1, TIRNICA_IMENA)
        stanje == TIRNICA_IKONE && !lezece -> TIRNICA_IKONE
        else -> maxOf(stanje - 1, TIRNICA_SKRITA)
    }

    /** Skrito tirnico pokaze samo poteg z levega roba (kot predal); poteg po vidni tirnici velja, kjer se zacne na njej. */
    fun potegZRobaTirnice(skrita: Boolean, zacetekXDp: Float, sirinaTirniceDp: Float): Boolean =
        if (skrita) zacetekXDp <= ROB_POTEGA_DP else zacetekXDp < sirinaTirniceDp

    // ------------------------------------------------------------------ iskalna lupa

    /** Lupa je razsirjena, kadar uporabnik z njo dela (fokus, besedilo) ali jo je izbral za vedno odprto. */
    fun lupaRazsirjena(fokus: Boolean, besedilo: CharSequence, vednoOdprta: Boolean): Boolean =
        vednoOdprta || fokus || besedilo.isNotBlank()

    /**
     * Natipkan znak (tipkovnica daljinca ali telefona) zacne iskanje v lupi; puscice, OK, presledek in sistemske tipke ne.
     * Stevka samo s prave tipkovnice ([polnaTipkovnica]): stevilke na daljincu televizorja so za kanale, ne za iskanje.
     */
    fun znakZaLupo(znak: Int, polnaTipkovnica: Boolean = true): Boolean =
        znak > 0 && !Character.isISOControl(znak) && !Character.isWhitespace(znak) && Character.isDefined(znak) &&
            (polnaTipkovnica || !Character.isDigit(znak))

    // ------------------------------------------------------------------ iskanje po nastavitvah

    private val OZNAKE = Regex("\\p{M}+")
    private val LOCILA = Regex("[^\\p{L}\\p{N}]+")

    /** Male crke, brez stresic in sumnikov, locila so presledki: »Zvok in vibriranje« in »zvok, vibriranje« sta enaka. */
    fun normaliziraj(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(OZNAKE, "").replace(LOCILA, " ").trim()

    /**
     * Ali vnos ustreza poizvedbi: vsaka beseda poizvedbe je zacetek kake besede v enem od besedil (ime in opis v vseh
     * jezikih aplikacije), daljsa beseda (3+ znake) pa sme biti tudi sredi besede (»tooth« najde »Bluetooth«).
     */
    fun ujema(poizvedba: String, besedila: Collection<String>): Boolean {
        val besede = normaliziraj(poizvedba).split(' ').filter { it.isNotEmpty() }
        if (besede.isEmpty()) return true
        val vse = besedila.map { normaliziraj(it) }
        return besede.all { b -> vse.any { t -> t.split(' ').any { it.startsWith(b) } || (b.length >= 3 && t.contains(b)) } }
    }

    /**
     * Sistemske dejavnosti, ki jih proizvajalec ali Google na televizorju prestreze in ne naredijo nicesar (Philips
     * IgnoreIntents, Googlovi nadomestki za TV): taka postavka bi bila mrtev gumb, zato je ne pokazemo.
     */
    fun jeNadomestek(paket: String, razred: String): Boolean {
        val r = razred.lowercase(Locale.ROOT)
        return paket == "com.google.android.tv.frameworkpackagestubs" || r.contains("ignoreintent") ||
            r.endsWith("stubactivity") || r.contains("stubs$") || r.endsWith(".stub")
    }
}
