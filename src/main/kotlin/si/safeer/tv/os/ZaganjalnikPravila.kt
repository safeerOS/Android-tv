package si.safeer.tv.os

/**
 * Pravila prilagodljivega zaganjalnika Safeer OS (tablica, telefon, televizor) - brez Androida, da jih
 * preverijo testi v navadnem JVM (tests/ZaganjalnikPravilaTest.kt).
 *
 * Postavitev se izbere po sirini okna in nacinu upravljanja, ne po vrsti naprave (lastnik, 15. 10. 2026):
 * ista tablica je pokonci "srednja", lezece "siroka"; razdeljen zaslon ali zlozljiv telefon dobi postavitev,
 * ki ustreza svojemu oknu. Meje so razredi velikosti okna iz Androidovih smernic (600 dp, 840 dp).
 */
object ZaganjalnikPravila {

    /** Razred sirine okna (Android window size classes). */
    enum class Sirina { OZKA, SREDNJA, SIROKA }

    /** Kako uporabnik upravlja napravo. */
    enum class Vnos { DOTIK, DALJINEC }

    /** Stranska vrstica: ikone in imena, samo ikone (tirnica) ali skrita za rocajem. */
    enum class Vrstica { Z_IMENI, IKONE }

    data class Postavitev(
        val sirina: Sirina,
        val vnos: Vnos,
        val vrstica: Vrstica,
        /** Stolpci mreze odsekov domacega zaslona (priljubljene, datoteke ...) - 1 pomeni en stolpec pod drugim. */
        val stolpci: Int,
        /** Faktor velikosti besedila in ploscic (televizor se gleda z razdalje). */
        val merilo: Float,
        /** Sirina ploscice aplikacije v dp. */
        val ploscicaDp: Int,
        /** Vrste kartic namesto mreze (televizor: vodoravne vrste, D-pad levo/desno). */
        val vrste: Boolean,
    )

    fun sirina(sirinaDp: Int): Sirina = when {
        sirinaDp < 600 -> Sirina.OZKA
        sirinaDp < 840 -> Sirina.SREDNJA
        else -> Sirina.SIROKA
    }

    /**
     * Postavitev za okno [sirinaDp] x [visinaDp] in nacin [vnos].
     *  - telefon pokonci (ozko): tirnica z ikonami (skrije se s potegom), en stolpec;
     *  - tablica pokonci (srednje): tirnica z ikonami, dva stolpca;
     *  - tablica lezece / namizje (siroko): vrstica z imeni, mreza kot na namizju (trije stolpci);
     *  - televizor (daljinec): vrstica z imeni (na televizorju se v vsebini skrci), vrste kartic, vecja pisava.
     * Nizko okno (telefon lezece, < 480 dp visine) ima tirnico, da ostane cim vec prostora vsebini.
     */
    fun izberi(sirinaDp: Int, visinaDp: Int, vnos: Vnos): Postavitev {
        val s = sirina(sirinaDp)
        if (vnos == Vnos.DALJINEC) {
            return Postavitev(s, vnos, Vrstica.Z_IMENI, stolpci = 1, merilo = 1.2f, ploscicaDp = 120, vrste = true)
        }
        val nizko = visinaDp in 1 until 480
        return when (s) {
            Sirina.OZKA -> Postavitev(s, vnos, Vrstica.IKONE, stolpci = 1, merilo = 1f, ploscicaDp = 76, vrste = false)
            Sirina.SREDNJA -> Postavitev(s, vnos, Vrstica.IKONE, stolpci = 2, merilo = 1f, ploscicaDp = 88, vrste = false)
            Sirina.SIROKA -> Postavitev(s, vnos, if (nizko) Vrstica.IKONE else Vrstica.Z_IMENI,
                stolpci = if (sirinaDp >= 1200) 3 else 2, merilo = 1f, ploscicaDp = 96, vrste = false)
        }
    }

    /** Koliko ploscic gre v eno vrsto mreze sirine [sirinaDp] (najmanj 3, najvec 8). */
    fun ploscicVVrsti(sirinaDp: Int, ploscicaDp: Int, razmikDp: Int = 12): Int =
        ((sirinaDp + razmikDp) / (ploscicaDp + razmikDp).coerceAtLeast(1)).coerceIn(3, 8)

    // ------------------------------------------------------------------ zascita pred padci

    /** Toliko padcev v [OKNO_PADCEV_MS] pomeni, da se domaci zaslon ne more vec zagnati sam. */
    const val MEJA_PADCEV = 2
    const val OKNO_PADCEV_MS = 60_000L

    /**
     * Varnostna vrata (lastnik, 25. 10. 2026): tudi padci, razprseni cez nekaj minut (npr. 3 v 5 min), pomenijo, da
     * domaci zaslon ni zanesljiv - uporabnik ne sme ostati pri zaganjalniku, ki vsake toliko pade.
     */
    const val MEJA_PADCEV_DOLGO = 3
    const val OKNO_PADCEV_DOLGO_MS = 5 * 60_000L

    /** Casi padcev, ki se stejejo (zadnji v oknu); starejse pozabimo. Hrani se daljse okno. */
    fun svezi(casi: List<Long>, zdaj: Long, okno: Long = OKNO_PADCEV_DOLGO_MS): List<Long> =
        casi.filter { it in (zdaj - okno)..zdaj }.takeLast(10)

    /**
     * Padci iz dveh virov - lasten zapis ob padcu (lovilec v procesu) in sistemski zapis izhodov procesa (Android 11+,
     * zunaj nasega procesa: vidi tudi padec v kodi C/C++, ANR in padec pred zagonom zaganjalnika). Isti padec je lahko
     * zapisan v obeh (razlika do [razlikaMs]): steje enkrat. Vrne urejeno narascajoce.
     */
    fun zdruziPadce(lastni: List<Long>, sistemski: List<Long>, razlikaMs: Long = 3_500L): List<Long> {
        val izid = ArrayList<Long>()
        for (t in (lastni + sistemski).sorted()) if (izid.isEmpty() || t - izid.last() > razlikaMs) izid.add(t)
        return izid
    }

    /**
     * Ali naj zaganjalnik po padcu vrne domaci zaslon prejsnjemu zaganjalniku: samo, ce je padcev v kratkem
     * dovolj (en sam nakljucen padec ne sme vzeti izbire) IN obstaja drug pravi zaganjalnik. Brez drugega
     * (televizor z onemogocenim zaganjalnikom proizvajalca) bi ostal le sistemski FallbackHome - crn zaslon,
     * zato tam ostanemo in nas sistem zazene znova.
     */
    fun vrniPrejsnjemu(casi: List<Long>, zdaj: Long, drugiZaganjalniki: Int): Boolean =
        drugiZaganjalniki > 0 && (svezi(casi, zdaj, OKNO_PADCEV_MS).size >= MEJA_PADCEV ||
            svezi(casi, zdaj, OKNO_PADCEV_DOLGO_MS).size >= MEJA_PADCEV_DOLGO)

    /**
     * Stejejo samo padci po [od] - po zadnjem vklopu Safeerja kot domacega zaslona ali po zadnji vrnitvi. Sicer bi stari
     * padci (se v sistemskem zapisu) uporabnika, ki Safeer znova izbere, takoj spet vrgli ven.
     */
    fun padciPo(casi: List<Long>, od: Long): List<Long> = casi.filter { it > od }

    /** Ali je zaganjalnik pravi (ne sistemski FallbackHome z negativno prednostjo). */
    fun jePraviZaganjalnik(paket: String, razred: String, prednost: Int, nas: String): Boolean =
        paket != nas && prednost >= 0 && !razred.endsWith("FallbackHome")

    // ------------------------------------------------------------------ znacke obvestil

    /**
     * Ali obvestilo res potrebuje uporabnika (lastnik, 28. 9. 2026: znacke od storitev v ozadju so moteci
     * hrup). Steje sporocilo, klic, opomnik, e-posta ... Ne steje: trajno obvestilo (predvajanje, prenos,
     * storitev v ospredju), povzetek skupine, napredek, tiho obvestilo (pomembnost nizka ali manj) in
     * obvestilo, ki ga aplikacija sama ne zeli kot znacko.
     *
     * [pomembnost]: 0 = neznana (pred Androidom 8 ali brez razvrstitve) - takrat odloci kategorija.
     */
    fun potrebujeUporabnika(
        trajno: Boolean,
        storitevVOspredju: Boolean,
        povzetekSkupine: Boolean,
        kategorija: String?,
        pomembnost: Int,
        dovoliZnacko: Boolean,
        napredek: Boolean,
    ): Boolean {
        if (trajno || storitevVOspredju || povzetekSkupine || napredek || !dovoliZnacko) return false
        if (kategorija in KATEGORIJE_OZADJE) return false
        // NotificationManager.IMPORTANCE_LOW = 2: tiho obvestilo (brez zvoka, ne pokaze se na vrhu).
        if (pomembnost in 1..2) return false
        return true
    }

    /** Kategorije, ki so po naravi stanje sistema ali aplikacije, ne prosnja uporabniku. */
    val KATEGORIJE_OZADJE = setOf("service", "progress", "transport", "sys", "status", "recommendation", "navigation", "location_sharing", "stopwatch", "workout")

    /** Stevilka na znacki: 1-9, nato "9+". Nic = brez znacke. */
    fun besediloZnacke(koliko: Int): String = when {
        koliko <= 0 -> ""
        koliko > 9 -> "9+"
        else -> koliko.toString()
    }

    // ------------------------------------------------------------------ vsebina domacega zaslona

    /**
     * Priljubljene aplikacije, kot jih kaze domaci zaslon: v uporabnikovem vrstnem redu, brez dvojnikov in
     * brez aplikacij, ki jih ni vec (odstranjene). "Ni nedelujocih ikon" (lastnik, 15. 10. 2026).
     */
    fun priljubljene(izbrane: List<String>, namescene: Set<String>): List<String> =
        izbrane.distinct().filter { it in namescene }

    /**
     * Ena vsebina - en vnos (lastnik, 21. 9. 2026): program naprave, ki je med priljubljenimi, se v vrsti
     * "Na tvojih napravah" ne ponovi. Kljuc je naprava + program. Vrstni red: najprej priljubljeni, nato
     * nazadnje zagnani; najvec [najvec].
     */
    fun programiNaprav(priljubljeni: List<Pair<String, String>>, nedavni: List<Pair<String, String>>, najvec: Int = 12): List<Pair<String, String>> =
        (priljubljeni + nedavni).distinct().take(najvec)

    /** Vrste datotek, ki sodijo v "Nedavne datoteke" (spletne aplikacije, zaslon in programi so drugje). */
    val VRSTE_DATOTEK = setOf("video", "glasba", "slika", "besedilo")

    // ------------------------------------------------------------------ namizje (tablica lezece, zunanji zaslon, DeX)

    /**
     * Nacin upravljanja. Televizor je vedno daljinec. Zunanji zaslon in Samsung DeX nimata dotika, a imata misko in
     * tipkovnico - kazalec dela kot prst, zato razporeditev na dotik (ne vrste kartic za daljinec). Drugje zaslon brez
     * dotika pomeni daljinec.
     */
    fun vnos(televizor: Boolean, brezDotika: Boolean, namizniZaslon: Boolean): Vnos = when {
        televizor -> Vnos.DALJINEC
        namizniZaslon -> Vnos.DOTIK
        brezDotika -> Vnos.DALJINEC
        else -> Vnos.DOTIK
    }

    /** Najnizje okno (dp) za namizno razporeditev: telefon lezece (~ 393 dp) ostane razporeditev za dotik. */
    const val NAMIZJE_NAJNIZJE_DP = 480

    /**
     * Namizna razporeditev - kot namizje Safeer OS na racunalniku (lupa, stranska vrstica z imeni, pozdrav, ura, mreza
     * vseh aplikacij): zunanji zaslon ali DeX vedno, sicer siroko in dovolj visoko okno na dotik (tablica lezece).
     * Majhen zaslon telefona ostane razporeditev za dotik; televizor nikoli - njegova tema je narejena za daljinec
     * (lastnik, 25. 10. 2026).
     */
    fun namizje(sirinaDp: Int, visinaDp: Int, vnos: Vnos, namizniZaslon: Boolean): Boolean = when {
        vnos == Vnos.DALJINEC -> false
        namizniZaslon -> true
        else -> sirina(sirinaDp) == Sirina.SIROKA && visinaDp >= NAMIZJE_NAJNIZJE_DP
    }

    /** Besedilo za iskanje: male crke brez naglasov (c, s, z najdejo tudi c, s, z s stresico). */
    fun normaliziraj(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(NAGLASI, "").trim()

    private val NAGLASI = Regex("\\p{Mn}+")
    private val LOCILA_BESED = Regex("[\\s._\\-:]+")

    /**
     * Aplikacije za poizvedbo [q] v lupi namizja: najprej ime, ki se z njo zacne, nato ime z besedo, ki se z njo zacne,
     * nato ime ali paket, ki jo vsebuje. Znotraj skupine ostane vrstni red seznama (abeceda). Prazna poizvedba vrne vse.
     */
    fun <T> poisci(seznam: List<T>, q: String, ime: (T) -> String, paket: (T) -> String): List<T> {
        val n = normaliziraj(q)
        if (n.isEmpty()) return seznam
        fun razred(t: T): Int {
            val i = normaliziraj(ime(t))
            return when {
                i.startsWith(n) -> 0
                i.split(LOCILA_BESED).any { it.startsWith(n) } -> 1
                i.contains(n) || paket(t).lowercase(java.util.Locale.ROOT).contains(n) -> 2
                else -> 3
            }
        }
        return seznam.map { it to razred(it) }.filter { it.second < 3 }.sortedBy { it.second }.map { it.first }
    }

    /**
     * Vrstni red aplikacij, ki jih naprava ponudi drugim napravam v Safeer Linku (apps.list - Prostori, »Odpri tukaj«):
     * najprej pripete na domacem zaslonu (v uporabnikovem vrstnem redu), nato druge v danem redu. Druga naprava tako
     * najprej vidi, kar uporabnik na tej napravi res uporablja - isti izbor kot zaganjalnik. Pripeta, ki je ni vec, izpade.
     */
    /**
     * Okno nove aplikacije na namizju (px, glede na delovno povrsino [sirinaPx] x [visinaPx]): 62 % sirine in 72 % visine,
     * vsako naslednje zamaknjeno za [zamikPx] (kaskada kot na racunalniku, prejsnje okno ostane vidno), po sestih spet od
     * zacetka; vedno v celoti na zaslonu. Vrne [levo, zgoraj, desno, spodaj].
     */
    fun oknoAplikacije(sirinaPx: Int, visinaPx: Int, zaporedje: Int, zamikPx: Int = 48): IntArray {
        val s = (sirinaPx * 0.62f).toInt().coerceAtLeast(1)
        val v = (visinaPx * 0.72f).toInt().coerceAtLeast(1)
        val k = Math.floorMod(zaporedje, 6)
        val levo = ((sirinaPx - s) / 2 - 2 * zamikPx + k * zamikPx).coerceIn(0, (sirinaPx - s).coerceAtLeast(0))
        val zgoraj = ((visinaPx - v) / 2 - 2 * zamikPx + k * zamikPx).coerceIn(0, (visinaPx - v).coerceAtLeast(0))
        return intArrayOf(levo, zgoraj, levo + s, zgoraj + v)
    }

    fun zaLink(paketi: List<String>, pripeti: List<String>): List<String> {
        val vsi = paketi.toSet()
        val prvi = pripeti.distinct().filter { it in vsi }
        val p = prvi.toSet()
        return prvi + paketi.filter { it !in p }
    }
}
