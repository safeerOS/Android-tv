package si.safeer.tv.link

/**
 * Ena privolitev za Safeer Vnos (lastnik 25. 10. 2026, 10:47 in 10:49): ko uporabnik napravi v ozjem krogu odpre
 * »Zaslon in upravljanje« (Safeer Link), je s tem vklopljen tudi Safeer Vnos - »drugace nima smisla«. Ko v krogu ni vec
 * nobene naprave z upravljanjem, se Safeer Vnos izklopi.
 *
 * Aplikacija storitev dostopnosti vklopi sama samo z dovoljenjem WRITE_SECURE_SETTINGS (pm grant ob namestitvi prek
 * adb) in na Androidu 13+ z odklenjenimi omejenimi nastavitvami (appops ACCESS_RESTRICTED_SETTINGS allow). Izmerjeno
 * 25. 10. 2026 10:48 na WP28 S (Android 14): brez allow sistem vpis zavrne tudi prek adb (»Skipping enabling service
 * disallowed by device admin policy«), z allow uspe. Brez dovoljenja ostane voden korak v istem toku.
 *
 * Tu so samo pravila (brez Androida): niz enabled_accessibility_services, izbira poti, ali naj Vnos tece, in smer
 * krizca (D-pad) za Android pod 13. Preizkus tests/VnosVklopPravilaTest.kt.
 */
object VnosVklopPravila {

    /** Locilo v Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES (AOSP: ComponentName.flattenToString, locene z »:«). */
    private const val LOCILO = ":"

    /** Komponenta v polni obliki: kratka oblika Androida »paket/.Razred« postane »paket/paket.Razred«. */
    fun polna(komponenta: String): String {
        val k = komponenta.trim()
        val i = k.indexOf('/')
        if (i <= 0 || i == k.length - 1) return k
        val paket = k.substring(0, i)
        val razred = k.substring(i + 1)
        return if (razred.startsWith(".")) "$paket/$paket$razred" else k
    }

    /** Vnosi niza v istem vrstnem redu in zapisu; prazni odpadejo. `null` in »null« (izpis `settings get`) sta prazna. */
    fun vnosi(niz: String?): List<String> =
        if (niz.isNullOrBlank() || niz.trim() == "null") emptyList()
        else niz.split(LOCILO).map { it.trim() }.filter { it.isNotEmpty() }

    /** Ali je [komponenta] v nizu (tudi v kratki obliki). */
    fun vsebuje(niz: String?, komponenta: String): Boolean {
        val nasa = polna(komponenta)
        return vnosi(niz).any { polna(it) == nasa }
    }

    /**
     * Niz z dodano [komponenta]. Druge storitve ostanejo v istem vrstnem redu in zapisu (na lastnikovem televizorju
     * mora ostati com.philips.sourceenabler/.SourceService - daljinec); nasa je natanko enkrat, na koncu.
     */
    fun dodaj(niz: String?, komponenta: String): String {
        val nasa = polna(komponenta)
        return (vnosi(niz).filter { polna(it) != nasa } + komponenta.trim()).joinToString(LOCILO)
    }

    /** Niz brez [komponenta] (tudi podvojene ali v kratki obliki); druge ostanejo. */
    fun odstrani(niz: String?, komponenta: String): String {
        val nasa = polna(komponenta)
        return vnosi(niz).filter { polna(it) != nasa }.joinToString(LOCILO)
    }

    /** AppOpsManager.MODE_ALLOWED: omejene nastavitve so odklenjene (ali jih za to namestitev ni). */
    const val NACIN_DOVOLJENO = 0

    /**
     * Ali omejene nastavitve Androida 13+ (API 33) zaklenejo vklop Safeer Vnosa: nacin AppOps
     * `android:access_restricted_settings` ni allow. [nacin] null: operacije ni bilo mogoce prebrati - ne trdimo, da je
     * zaklenjeno (vpis poskusimo in izmerimo, ali se je storitev vezala).
     */
    fun omejenoZaklenjeno(sdk: Int, nacin: Int?): Boolean = sdk >= 33 && nacin != null && nacin != NACIN_DOVOLJENO

    /** Kako do vklopljenega Safeer Vnosa na tej napravi. */
    enum class Pot {
        /** Storitve v tej gradnji ni (npr. brskalnik): nic ne zahtevamo in nikamor ne vodimo. */
        NI_STORITVE,
        /** Safeer Vnos ze tece. */
        ZE_VKLOPLJENO,
        /** Aplikacija ga vklopi sama (WRITE_SECURE_SETTINGS, omejene nastavitve niso ovira). */
        SAMODEJNO,
        /** WRITE_SECURE_SETTINGS je, a omejene nastavitve (Android 13+) niso odklenjene: manjka appops ... allow. */
        MANJKA_ODKLEP,
        /** Brez WRITE_SECURE_SETTINGS (namestitev brez adb): voden korak na stran Safeer Vnosa. */
        VODENO
    }

    fun pot(vGradnji: Boolean, aktivna: Boolean, pisanje: Boolean, omejenoZaklenjeno: Boolean): Pot = when {
        !vGradnji -> Pot.NI_STORITVE
        aktivna -> Pot.ZE_VKLOPLJENO
        pisanje && !omejenoZaklenjeno -> Pot.SAMODEJNO
        pisanje -> Pot.MANJKA_ODKLEP
        else -> Pot.VODENO
    }

    /**
     * Ali naj Safeer Vnos tece: v ozjem krogu je vsaj ena DRUGA naprava, ki je se clan kroga (ni umaknjena) in ji je
     * odprto »Zaslon in upravljanje«. [jedra]: naprave z odprtim zaslonom (cast/Dostop). Napaka pri preverbi = ne steje.
     */
    fun zeleno(jedra: Collection<String>, vKrogu: (String) -> Boolean, taNaprava: (String) -> Boolean): Boolean =
        jedra.any { j ->
            j.isNotBlank() && try { vKrogu(j) && !taNaprava(j) } catch (_: Throwable) { false }
        }

    enum class Dejanje { NIC, VKLOPI, IZKLOPI }

    /**
     * Kar si aplikacija zapomni o vpisu Safeer Vnosa: [vpisaliMi] - vpis je naredila aplikacija sama (ob privolitvi), ne
     * uporabnik v Nastavitvah; [zadnjicVpisano] - ali je bil vpisan ob zadnji preverbi; [izklopilUporabnik] - vpis je
     * izginil brez nas (uporabnik ga je izklopil v Nastavitvah naprave).
     */
    data class Spomin(val vpisaliMi: Boolean = false, val zadnjicVpisano: Boolean = false, val izklopilUporabnik: Boolean = false)

    /** Aplikacija je Vnos vpisala sama. */
    val PO_VPISU = Spomin(vpisaliMi = true, zadnjicVpisano = true, izklopilUporabnik = false)

    /**
     * Pregled veje tv-vnos (25. 10. 2026, tocka 4): rocni izklop uporabnika velja do nove privolitve. Ce je bil Vnos ob
     * zadnji preverbi vpisan, zdaj pa ga ni (mi ga nismo umaknili - [poIzpisu]), ga je izklopil uporabnik; ce se je
     * pojavil brez nas, ga je vklopil uporabnik.
     */
    fun opazi(s: Spomin, vpisano: Boolean): Spomin = when {
        s.zadnjicVpisano && !vpisano -> Spomin(vpisaliMi = false, zadnjicVpisano = false, izklopilUporabnik = true)
        vpisano && !s.zadnjicVpisano -> s.copy(zadnjicVpisano = true, izklopilUporabnik = false)
        else -> s
    }

    /** Aplikacija je svoj vpis umaknila (izklop ali storitev se ni vezala): to ni izklop uporabnika. */
    fun poIzpisu(s: Spomin): Spomin = Spomin(vpisaliMi = false, zadnjicVpisano = false, izklopilUporabnik = s.izklopilUporabnik)

    /** Nova privolitev uporabnika (ozji krog, dostop naprave): prejsnji rocni izklop ne velja vec. */
    fun poPrivolitvi(s: Spomin): Spomin = s.copy(izklopilUporabnik = false)

    /**
     * Uskladitev ob spremembi kroga ali zagonu. Brez pisanja ne spreminjamo nicesar. Vklop samo, ce ozji krog to zahteva
     * in ga uporabnik ni rocno izklopil (pregled tv-vnos, tocka 4). Izklop samo vpisa, ki ga je naredila aplikacija
     * ([Spomin.vpisaliMi]; rocni vklop uporabnika ostane) - tudi, ko stanja kroga ni bilo mogoce prebrati ([zeleno] null:
     * »ne vem« ni »da«, tocka 3).
     */
    fun dejanje(zeleno: Boolean?, vpisano: Boolean, pisanje: Boolean, spomin: Spomin): Dejanje = when {
        !pisanje -> Dejanje.NIC
        vpisano -> if (spomin.vpisaliMi && zeleno != true) Dejanje.IZKLOPI else Dejanje.NIC
        smeVpisati(zeleno, spomin) -> Dejanje.VKLOPI
        else -> Dejanje.NIC
    }

    /**
     * Ali sme aplikacija Vnos vpisati sama (VnosVklop.vklopi - edina pot pisanja, tudi za input.enable z druge naprave):
     * samo, ce ozji krog to zahteva ([zeleno] = true; pregled tv-vnos, tocka 1) in ga uporabnik ni rocno izklopil.
     */
    fun smeVpisati(zeleno: Boolean?, spomin: Spomin): Boolean = zeleno == true && !spomin.izklopilUporabnik

    /**
     * Ob katerih oddajah se Vnos uskladi z ozjim krogom: samo zasciteni sistemski BOOT_COMPLETED in MY_PACKAGE_REPLACED.
     * QUICKBOOT_POWERON lahko poslje katerakoli aplikacija (pregled tv-vnos, tocka 4).
     */
    fun uskladiObOddaji(akcija: String): Boolean =
        akcija == "android.intent.action.BOOT_COMPLETED" || akcija == "android.intent.action.MY_PACKAGE_REPLACED"

    // ------------------------------------------------------------------ krizec (D-pad) pod Androidom 13

    enum class Smer { GOR, DOL, LEVO, DESNO }

    fun smer(ime: String): Smer? = when (ime.trim().lowercase()) {
        "up", "gor" -> Smer.GOR
        "down", "dol" -> Smer.DOL
        "left", "levo" -> Smer.LEVO
        "right", "desno" -> Smer.DESNO
        else -> null
    }

    /** Okvir elementa na zaslonu v pikslih (AccessibilityNodeInfo.getBoundsInScreen). */
    class Okvir(val levo: Int, val zgoraj: Int, val desno: Int, val spodaj: Int) {
        val prazen: Boolean get() = desno <= levo || spodaj <= zgoraj
        val sredinaX: Double get() = (levo + desno) / 2.0
        val sredinaY: Double get() = (zgoraj + spodaj) / 2.0
        fun enak(o: Okvir): Boolean = levo == o.levo && zgoraj == o.zgoraj && desno == o.desno && spodaj == o.spodaj
    }

    /** Ali [cilj] lezi v smeri od [od] (kot android.view.FocusFinder.isCandidate). */
    private fun vSmeri(od: Okvir, cilj: Okvir, s: Smer): Boolean = when (s) {
        Smer.LEVO -> (od.desno > cilj.desno || od.levo >= cilj.desno) && od.levo > cilj.levo
        Smer.DESNO -> (od.levo < cilj.levo || od.desno <= cilj.levo) && od.desno < cilj.desno
        Smer.GOR -> (od.spodaj > cilj.spodaj || od.zgoraj >= cilj.spodaj) && od.zgoraj > cilj.zgoraj
        Smer.DOL -> (od.zgoraj < cilj.zgoraj || od.spodaj <= cilj.zgoraj) && od.spodaj < cilj.spodaj
    }

    /** Ali se [cilj] prekriva z [od] v precni osi (»zarek« v smeri gibanja). */
    private fun vZarku(od: Okvir, cilj: Okvir, s: Smer): Boolean = when (s) {
        Smer.LEVO, Smer.DESNO -> cilj.spodaj > od.zgoraj && cilj.zgoraj < od.spodaj
        Smer.GOR, Smer.DOL -> cilj.desno > od.levo && cilj.levo < od.desno
    }

    private fun glavnaRazdalja(od: Okvir, cilj: Okvir, s: Smer): Long = maxOf(0, when (s) {
        Smer.LEVO -> od.levo - cilj.desno
        Smer.DESNO -> cilj.levo - od.desno
        Smer.GOR -> od.zgoraj - cilj.spodaj
        Smer.DOL -> cilj.zgoraj - od.spodaj
    }).toLong()

    private fun precnaRazdalja(od: Okvir, cilj: Okvir, s: Smer): Double = when (s) {
        Smer.LEVO, Smer.DESNO -> kotlin.math.abs(od.sredinaY - cilj.sredinaY)
        Smer.GOR, Smer.DOL -> kotlin.math.abs(od.sredinaX - cilj.sredinaX)
    }

    /**
     * Indeks najblizjega kandidata v smeri [s] od [od] ali -1. Po vzoru android.view.FocusFinder: kandidat mora lezati v
     * smeri, prednost ima kandidat v zarku, nato najmanjsa utezena razdalja 13 * glavna^2 + precna^2. Prazni okvirji in
     * izhodisce samo odpadejo.
     */
    fun najblizji(od: Okvir, kandidati: List<Okvir>, s: Smer): Int {
        var najboljsi = -1
        var najZarek = false
        var najRazdalja = Double.MAX_VALUE
        for ((i, c) in kandidati.withIndex()) {
            if (c.prazen || c.enak(od) || !vSmeri(od, c, s)) continue
            val zarek = vZarku(od, c, s)
            val g = glavnaRazdalja(od, c, s).toDouble()
            val p = precnaRazdalja(od, c, s)
            val razdalja = 13.0 * g * g + p * p
            val boljsi = when {
                najboljsi < 0 -> true
                zarek != najZarek -> zarek
                else -> razdalja < najRazdalja
            }
            if (boljsi) { najboljsi = i; najZarek = zarek; najRazdalja = razdalja }
        }
        return najboljsi
    }

    /**
     * Enter pod Androidom 13 (pregled tv-vnos, tocka 7): klikne element s fokusom, ce je klikljiv; prednika samo, ce
     * zaseda isti okvir (isti vidni element, npr. kartica okoli besedila) - nikoli vecjega vsebnika. [veriga]: element s
     * fokusom, nato starsi (okvir, klikljiv). Vrne indeks ali -1.
     */
    fun ciljEnter(veriga: List<Pair<Okvir, Boolean>>): Int {
        val prvi = veriga.firstOrNull()?.first ?: return -1
        for ((i, e) in veriga.withIndex()) {
            if (i > 0 && !e.first.enak(prvi)) return -1
            if (e.second) return i
        }
        return -1
    }

    /** Brez fokusa: prvi element zgoraj levo (vrstica, nato stolpec), ali -1. */
    fun prvi(kandidati: List<Okvir>): Int {
        var najboljsi = -1
        for ((i, c) in kandidati.withIndex()) {
            if (c.prazen) continue
            val b = kandidati.getOrNull(najboljsi)
            if (b == null || c.zgoraj < b.zgoraj || (c.zgoraj == b.zgoraj && c.levo < b.levo)) najboljsi = i
        }
        return najboljsi
    }
}
