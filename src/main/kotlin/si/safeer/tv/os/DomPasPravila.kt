package si.safeer.tv.os

/**
 * Pas na vrhu Doma Medijskega centra ([DomPas]): kaze, kar igra (video v pasu), sicer zadnjo nedokoncano vsebino z gumbom
 * Nadaljuj, sicer izbor dneva. Cista pravila brez Androida (preizkus: tests/DomPasPravilaTest.kt) - da se postavitev,
 * napisi in mere preverijo brez naprave, kot pri [MiniPredvajalnikPravila].
 */
object DomPasPravila {
    /**
     * Lastnik 18. 11. 2026 (posnetek TV): pas Doma se ne razteg ne do roba zaslona - desni okvir ima vodoravni odmik, pas
     * pa je sirok samo toliko kot vsebina. Negativni rob ob obeh straneh (enak odmiku okvirja) ga potegne do roba.
     */
    fun robPasu(odmikOkviraPx: Int): Int = -odmikOkviraPx.coerceAtLeast(0)

    /** V pasu igra vsebina: gumb kaze Premor, klik odpre cel zaslon. */
    const val IGRA = "igra"
    /** Nic ne igra, a obstaja nedokoncana vsebina: gumb kaze Nadaljuj. */
    const val NADALJUJ = "nadaljuj"
    /** Nic ne igra in ni nedokoncanega: pas kaze izbor dneva (Kinema hero.ts) - Dom nikoli brez pasu. */
    const val IZBOR = "izbor"
    /** Pasa ni (zunaj Doma, na podstrani): [DomPas.prikazi] ga skrije. Enako ime in vrednost kot pri [MiniPredvajalnikPravila]. */
    const val MC_NIC = "nic"

    /** Kljuc napisa glavnega gumba; [DomPas] ga preslika v obstojece nize. */
    const val NAPIS_PREDVAJAJ = "predvajaj"
    const val NAPIS_PREMOR = "premor"
    const val NAPIS_NADALJUJ = "nadaljuj"

    /**
     * Kaj pise na glavnem gumbu: kar igra -> Premor (tudi ce vrsta tega ne pove, npr. po preklopu iz seznama),
     * nedokoncana vsebina -> Nadaljuj, izbor -> Predvajaj. Neznana vrsta ne pusti gumba brez napisa.
     */
    fun napisGlavnega(vrsta: String, igra: Boolean): String = when {
        igra || vrsta == IGRA -> NAPIS_PREMOR
        vrsta == NADALJUJ -> NAPIS_NADALJUJ
        else -> NAPIS_PREDVAJAJ
    }

    /**
     * »12:03 / 45:10« ali »1:16:05 / 2:10:00«, kadar ena od vrednosti doseze uro (da sta oba casa enake oblike).
     * Negativni polozaj je 0; brez trajanja (tok v zivo, se nalaga) vrne prazen niz - crte in casa takrat ni.
     */
    fun casNapredka(polozajMs: Long, trajanjeMs: Long): String {
        if (trajanjeMs <= 0) return ""
        val p = polozajMs.coerceAtLeast(0L)
        val ure = trajanjeMs >= 3_600_000L || p >= 3_600_000L
        return if (ure) String.format(java.util.Locale.ROOT, "%d:%02d:%02d / %d:%02d:%02d",
            p / 3_600_000L, (p % 3_600_000L) / 60_000L, (p % 60_000L) / 1000L,
            trajanjeMs / 3_600_000L, (trajanjeMs % 3_600_000L) / 60_000L, (trajanjeMs % 60_000L) / 1000L)
        else String.format(java.util.Locale.ROOT, "%d:%02d / %d:%02d",
            p / 60_000L, (p % 60_000L) / 1000L, trajanjeMs / 60_000L, (trajanjeMs % 60_000L) / 1000L)
    }

    /** Delež napredka za crto 0..100; brez trajanja ali pred zacetkom 0, cez konec 100 (crta ne sme uit). */
    fun odstotek(polozajMs: Long, trajanjeMs: Long): Int {
        if (trajanjeMs <= 0 || polozajMs <= 0L) return 0
        if (polozajMs >= trajanjeMs) return 100
        return (polozajMs * 100 / trajanjeMs).toInt()
    }

    /** Ozek zaslon (telefon pokoncen) 170 dp - pas ne sme vzeti pol ekrana. Brez visine zaslona: 230 dp (staro vedenje). */
    fun visinaDp(sirinaDp: Int): Int = visinaDp(sirinaDp, 0)

    /**
     * Zgled Jellyfin + KefinTweaks (lastnik 8. 11. 2026): na TV in lezeci tablici je pas uvod strani - 38 % visine zaslona
     * v mejah 200-400 dp, a nikoli vec kot 55 % visine (nizek zaslon) in ne manj kot 170 dp. Ozek zaslon 170 dp.
     * TV 8. 11. 2026 (960x540 dp): pri 42 % (297 dp) je naslednja vrstica zacela na ~446 dp, kartice Nadaljuj z ogledom
     * (~182 dp) niso bile na prvem zaslonu - zdaj 205 dp.
     */
    fun visinaDp(sirinaDp: Int, visinaZaslonaDp: Int): Int = when {
        sirinaDp < 600 -> 170
        visinaZaslonaDp <= 0 -> 230
        // Slepi pregled 8. 11. (6): pas ne sme preseci 55 % visine (telefon lezece 360 dp); najmanj pa 170 dp kot na telefonu.
        else -> (visinaZaslonaDp * 38 / 100).coerceIn(200, 400).coerceAtMost(visinaZaslonaDp * 55 / 100).coerceAtLeast(170)
    }

    /** Opis (2 vrstici) gre v pas sele od te visine - nizje bi z naslovom 40 sp in gumbi odrezal vrh naslova (pregled 7). */
    const val OPIS_OD_DP = 290   // TV 960x540 dp ima od 8. 11. pas 205 dp - opisa ni (naslov, podnaslov, crta in gumbi)

    fun prikaziOpis(siroko: Boolean, visinaPasuDp: Int, imaOpis: Boolean): Boolean = imaOpis && siroko && visinaPasuDp >= OPIS_OD_DP

    /** Naslov uvoda po zgledu KefinTweaks: na TV 40 sp (berljiv s kavca, kot naslov v zgledu), na telefonu 20 sp. */
    fun velikostNaslovaSp(sirinaDp: Int): Float = velikostNaslovaSp(sirinaDp, 0)

    /**
     * Drugi pregled (3): na nizkem sirokem pasu (telefon lezece, ~200 dp) dve vrstici po 40 sp z gumbi ne gresta -
     * pod 260 dp je naslov 24 sp. [visinaPasuDp] 0 = neznana (velja sirina).
     */
    fun velikostNaslovaSp(sirinaDp: Int, visinaPasuDp: Int): Float = when {
        sirinaDp < 600 -> 20f
        visinaPasuDp in 1 until 260 -> 24f
        else -> 40f
    }

    /**
     * TV 8. 11. 2026: v 205 dp visokem pasu (960x540) gresta z gumbi in crto ena vrstica naslova 24 sp in logotip 44 dp -
     * dve vrstici ali logotip 120 dp bi odrezala vrh. Nizki siroki pas (pod 260 dp) ima zato eno vrstico in nizji logotip.
     */
    fun vrsticNaslova(sirinaDp: Int, visinaPasuDp: Int): Int = if (sirinaDp >= 600 && visinaPasuDp in 1 until 260) 1 else 2

    fun visinaLogotipaDp(sirinaDp: Int, visinaPasuDp: Int): Int = when {
        sirinaDp < 600 -> 64
        visinaPasuDp in 1 until 260 -> 44
        else -> 120
    }

    /**
     * Pri vecji pisavi (dostopnost) naslov, crta in gumbi zrastejo, pas pa ne - v nizkem pasu logotip zato manjsamo,
     * da gumbi ne zdrsnejo v spodnji odmik (slepi pregled 8. 11. 2026). Najmanj 24 dp.
     */
    fun visinaLogotipaDp(sirinaDp: Int, visinaPasuDp: Int, meriloPisave: Float): Int {
        val osnova = visinaLogotipaDp(sirinaDp, visinaPasuDp)
        if (meriloPisave <= 1f || visinaPasuDp !in 1 until 260) return osnova
        return (osnova / meriloPisave).toInt().coerceAtLeast(24)
    }

    /**
     * Kritika 8. 11. 2026 (1, 3): ostra slika ozadja in logotip filma - Cinemeta/metahub (ze uporabljen za plakate v
     * [Stremio]) ima po IMDb id ozadje 1920x1080 in logotip (izmerjeno 8. 11.: tt31193180 -> 200, 1920x1080 in 800x310).
     * Id najdemo v polju imdbId ali v id-ju vsebine (»tt…«, tudi »tt…:1:2« za epizode); brez njega prazno.
     */
    fun imdbId(vararg nizi: String): String {
        for (n in nizi) Regex("tt\\d{5,10}").find(n)?.let { return it.value }
        return ""
    }

    fun ozadjeUrl(imdb: String): String = if (imdb.isEmpty()) "" else "https://images.metahub.space/background/medium/$imdb/img"

    fun logoUrl(imdb: String): String = if (imdb.isEmpty()) "" else "https://images.metahub.space/logo/medium/$imdb/img"

    /** Trajanje za podnaslov (kritika 9): »2 h 17 min«, »45 min«; brez trajanja prazno. */
    fun trajanje(ms: Long): String {
        if (ms < 60_000L) return ""
        val min = ms / 60_000L
        return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    }

    /** Podnaslov iz delov (leto, trajanje, ...) - prazni izpadejo. */
    fun podnaslov(vararg deli: String): String = deli.map { it.trim() }.filter { it.isNotEmpty() && it != "0" }.joinToString(" · ")

    /** Podnaslov, ko vsebina igra (TV 8. 11. 2026: pri filmu je bilo samo leto): video leto (ali izvajalec) in trajanje, zvok kot doslej. */
    fun podnaslovIgra(video: Boolean, leto: Int, izvajalec: String, trajanjeMs: Long): String {
        val prvi = if (leto != 0) leto.toString() else izvajalec
        return if (video) podnaslov(prvi, trajanje(trajanjeMs)) else prvi
    }

    /**
     * Ambientalna barva ozadja Doma (kritika 4): povprecna barva slike, zmesana s temnim ozadjem strani, da je besedilo
     * vedno berljivo - delez slike najvec 35 %.
     */
    /**
     * Lastnik 9. 11. 2026: ambientalno ozadje Doma (barva slike pasu cez uporabnikovo ozadje) je pri svetlih slikah (sneg,
     * nebo) dalo sivo stran in skrilo uporabnikovo ozadje - Dom ima ozadje kot ostali zavihki. Izracun ostane za morebitno izbiro.
     */
    val AMBIENT_NA_DOMU: Boolean = false

    fun ambient(povprecje: Int, ozadje: Int): Int {
        fun k(c: Int, s: Int) = ((c shr s) and 0xFF)
        fun m(s: Int) = (k(povprecje, s) * 35 + k(ozadje, s) * 65) / 100
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    /**
     * Preliv ozadja Doma (zgoraj ambient, spodaj ozadje strani). TV 8. 11. 2026: pri 0xE0/0xF2 je uporabnikovo ozadje
     * prosevalo za vrsticami nizje na strani - 0xF0/0xFA.
     */
    fun ambientPreliv(ambient: Int, ozadje: Int): IntArray =
        intArrayOf((ambient and 0x00FFFFFF) or (0xF0 shl 24), (ozadje and 0x00FFFFFF) or (0xFA shl 24))

    /** Povprecna barva vzorcev (slepi pregled 8. 11.: pomanjsava na 1x1 vzame le sredino slike); prazno = crna. */
    fun povprecje(piksli: IntArray): Int {
        if (piksli.isEmpty()) return 0xFF000000.toInt()
        var r = 0L; var g = 0L; var b = 0L
        for (c in piksli) { r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF }
        val n = piksli.size
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /** Izbor na pasu krozi vsakih [SEKUND_KROZENJA] s (zgled: pike desno spodaj). */
    const val SEKUND_KROZENJA = 8
    /** Najvec toliko vsebin krozi v pasu (in toliko pik) - vec bi bilo nepregledno. */
    const val NAJVEC_KROZENJA = 6

    /** Naslednji element krozenja; prazen seznam vrne 0. */
    fun naslednji(indeks: Int, n: Int): Int = if (n <= 0) 0 else Math.floorMod(indeks + 1, n)

    /**
     * Krozi samo izbor: ne, ko igra ali je nedokoncana vsebina (ta ostane), ne, ko ima pas ali gumb fokus (uporabnik
     * bere ali bo kliknil - klik ne sme odpreti druge vsebine), ne pri zmanjsanem gibanju in ne z manj kot dvema.
     */
    fun smeKroziti(vrsta: String, imaFokus: Boolean, zmanjsanoGibanje: Boolean, n: Int): Boolean =
        vrsta == IZBOR && !imaFokus && !zmanjsanoGibanje && n >= 2

    /** Oznaka na koncu naslova slike: nalagalnik pasu sliko dekodira drobno (mehko zamegljeno ozadje). */
    const val ZAMEGLI = "#zamegli"
    /** 48 px po daljsi stranici: CENTER_CROP drobno sliko raztegne cez pas v mehko zameglitev, brez lastnega filtra. */
    const val ZAMEGLJENO_PX = 48

    fun zamegljeno(url: String): String = if (url.isBlank() || jeZamegljeno(url)) url else url + ZAMEGLI

    fun jeZamegljeno(url: String): Boolean = url.endsWith(ZAMEGLI)

    fun brezOznake(url: String): String = url.removeSuffix(ZAMEGLI)

    /**
     * Slike pasu: (slika cez ves pas, ostra slika ob strani). Ozadje filma -> cez pas, brez plakata. Brez ozadja na sirokem
     * zaslonu: film -> plakat 2:3 ob strani; zvok -> TV 8. 11. 2026: majhen logotip postaje je bil raztegnjen cez ves pas
     * (zamegljen) - zdaj oster kvadrat ob strani in ista slika zamegljena cez pas. Ozek zaslon: slika cez pas kot doslej.
     */
    fun slikePasu(ozadjeUrl: String, slikaUrl: String, siroko: Boolean, video: Boolean): Pair<String, String> {
        val oz = ozadjeUrl.trim()
        val sl = slikaUrl.trim()
        if (oz.isNotEmpty()) return oz to ""
        if (!siroko || sl.isEmpty()) return sl to ""
        return if (video) "" to sl else zamegljeno(sl) to sl
    }

    /** Zacasna datoteka zapisa slike pasu, starejsa od ure (ostanek prekinjenega zapisa). */
    fun jeStaraZacasna(ime: String, starostMs: Long): Boolean = ime.endsWith(".tmp") && starostMs > 60L * 60 * 1000

    /**
     * Koliko pikslov dekodiramo za pogled z merami [sirinaPx] x [visinaPx] (layoutParams; <= 0 = MATCH_PARENT ali
     * WRAP_CONTENT): slika ob strani (kvadrat zvoka ~274 px na TV) ne potrebuje 1280 px - slepi pregled 8. 11. 2026.
     */
    fun pikslovSlike(sirinaPx: Int, visinaPx: Int, polni: Int): Int =
        if (sirinaPx <= 0 || visinaPx <= 0) polni else maxOf(sirinaPx, visinaPx).coerceIn(160, polni)

    /** Slika z diska je premajhna za ta pogled (shranila jo je slika ob strani) - raje omrezje kot razmazana slika. */
    fun premajhnaZDiska(najvecjaStranPx: Int, ciljPx: Int): Boolean = najvecjaStranPx < ciljPx * 3 / 4

    /** Mera slike ob strani (sirina, visina v dp): film 2:3, zvok (postaja, album) kvadrat 1:1. */
    fun meraPlakata(visinaPasuDp: Int, video: Boolean): Pair<Int, Int> =
        if (video) (visinaPasuDp * 4 / 9) to (visinaPasuDp * 2 / 3) else (visinaPasuDp * 2 / 3) to (visinaPasuDp * 2 / 3)

    /** Vsebina za primerjavo ([OsPravila.istaVsebina]): tok ima pogosto drug id kot kartica, IMDb s sezono in epizodo pa isti. */
    data class Vsebina(val id: String, val imdb: String, val sezona: Int, val epizoda: Int)

    /** Nadaljuj s pasu velja za vsebino, ki se pripravi v tem casu (debrid/Usenet zna trajati) - nato pozabljeno. */
    const val ROK_NADALJUJ_IZ_PASU_MS = 180_000L

    /**
     * TV 8. 11. 2026: »Nadaljuj« na pasu je ze izbira - predvajalnik ne vprasa znova »Nadaljuj od / Od zacetka«. Samo za
     * isto vsebino (tok ali izvirnik), v [ROK_NADALJUJ_IZ_PASU_MS]; kartice vrstice Nadaljuj z ogledom vprasajo (28. 10.).
     */
    fun nadaljujBrezVprasanja(izPasu: Vsebina?, msOdKlika: Long, tok: Vsebina, izvirnik: Vsebina?): Boolean {
        if (izPasu == null || msOdKlika !in 0..ROK_NADALJUJ_IZ_PASU_MS) return false
        return listOfNotNull(tok, izvirnik).any {
            OsPravila.istaVsebina(izPasu.id, izPasu.imdb, izPasu.sezona, izPasu.epizoda, it.id, it.imdb, it.sezona, it.epizoda)
        }
    }

    /**
     * Diskovni predpomnilnik slik pasu: kazala datotek, ki gredo ven (najstarejse najprej), da skupaj ne presezejo
     * [mejaBajtov]. Vnos = (cas zapisa, velikost).
     */
    fun zavrziZaMejo(vnosi: List<Pair<Long, Long>>, mejaBajtov: Long): List<Int> {
        var skupaj = vnosi.sumOf { it.second }
        val ven = mutableListOf<Int>()
        for (i in vnosi.indices.sortedBy { vnosi[it].first }) {
            if (skupaj <= mejaBajtov) break
            ven += i
            skupaj -= vnosi[i].second
        }
        return ven
    }
}
