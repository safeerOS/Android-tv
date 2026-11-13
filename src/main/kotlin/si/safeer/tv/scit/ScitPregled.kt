package si.safeer.tv.scit

/**
 * Lastnikov pregled Scita (3. 11. 2026): ciste odlocitve brez Androida (tests/ScitPregledTest.kt).
 *
 *  - zadnja ustavljena imena (najvec [NAJVEC_ZADNJIH], samo v zasebni mapi aplikacije, ob izklopu pocisceno),
 *  - uporabnikove izjeme (OK na imenu na zaslonu Scita: 30 dni; nikoli za ime s seznama grozenj),
 *  - zasebni DNS (DoT z imenom streznika) Scit obide - povemo, ne prestrezamo,
 *  - brez krajevnega DNS ne izberemo tujega razresevalca (prej 1.1.1.1 in 9.9.9.9: »nic ne zapusti televizorja« ni drzalo).
 */
object ScitPregled {
    /**
     * Preverba TV 4. 11. 2026 (zaslon Scita): »Danes ustavljenih: 20« in hkrati »Se nic ustavljenega«. Ob izklopu se imena
     * zbrisejo, dnevni stevec ostane - prazen seznam ob stevcu > 0 pove, da so bila imena izbrisana.
     */
    fun imenaIzbrisana(steviloZadnjih: Int, blokiranihDanes: Long): Boolean = steviloZadnjih == 0 && blokiranihDanes > 0

    /**
     * Preverba TV 4. 11. 2026: privzeti fokus zaslona je na »Izklopi« in en OK je takoj izklopil zascito. Izklop z
     * zaslona zdaj vedno vprasa (privzeto Preklici); vklop ne.
     */
    fun izklopZPotrditvijo(vklopljen: Boolean): Boolean = vklopljen

    const val NAJVEC_ZADNJIH = 20
    const val NAJVEC_IZJEM = 50
    const val IZJEMA_MS = 30L * 24 * 3600 * 1000
    /** Najdaljse ime, ki ga hranimo (DNS: 253 znakov). */
    private const val NAJDALJSE = 253

    class Zadnje(val ime: String, val ura: Long)

    /** Ime, kot ga hranimo in kazemo: male crke, brez koncne pike, samo znaki imena DNS (nic drugega v datoteko ali vmesnik). */
    fun cisto(ime: String): String? {
        val d = ScitIzjeme.normaliziraj(ime)
        if (d.isEmpty() || d.length > NAJDALJSE) return null
        if (!d.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' || it == '_' }) return null
        return d
    }

    /** Novo ustavljeno ime na vrh; isto ime se ne podvoji (premakne se na vrh z novo uro); najvec [NAJVEC_ZADNJIH]. */
    fun dodajZadnje(seznam: List<Zadnje>, ime: String, ura: Long): List<Zadnje> {
        val d = cisto(ime) ?: return seznam
        val nov = ArrayList<Zadnje>(NAJVEC_ZADNJIH)
        nov.add(Zadnje(d, ura))
        for (z in seznam) { if (nov.size >= NAJVEC_ZADNJIH) break; if (z.ime != d) nov.add(z) }
        return nov
    }

    fun zapisZadnjih(seznam: List<Zadnje>): String = seznam.take(NAJVEC_ZADNJIH).joinToString("\n") { "${it.ura}\t${it.ime}" }

    fun preberiZadnje(besedilo: String?): List<Zadnje> {
        val r = ArrayList<Zadnje>()
        for (v in besedilo.orEmpty().lineSequence()) {
            if (r.size >= NAJVEC_ZADNJIH) break
            val d = v.split('\t')
            if (d.size != 2) continue
            val ura = d[0].trim().toLongOrNull() ?: continue
            val ime = cisto(d[1]) ?: continue
            if (r.none { it.ime == ime }) r.add(Zadnje(ime, ura))
        }
        return r
    }

    // ------------------------------------------------------------------ uporabnikove izjeme

    /** Zapis (domena TAB velja-do-ms) -> veljavne izjeme ob [zdaj]; pretecene ne veljajo ze ob branju. */
    fun preberiIzjeme(besedilo: String?, zdaj: Long): Map<String, Long> {
        val m = LinkedHashMap<String, Long>()
        for (v in besedilo.orEmpty().lineSequence()) {
            val d = v.split('\t')
            if (d.size != 2) continue
            val g = cisto(d[0]) ?: continue
            val t = d[1].trim().toLongOrNull() ?: continue
            if (t > zdaj && ScitIzjeme.veljavna(g) && m.size < NAJVEC_IZJEM) m[g] = t
        }
        return m
    }

    fun zapisIzjem(m: Map<String, Long>): String = ScitIzjeme.zapis(m)

    /**
     * Uporabnik je na zaslonu Scita izbral ustavljeno ime: spusti ga [IZJEMA_MS]. Null = ni dovoljeno: ime s seznama
     * grozenj ([naGroznih]; tudi ob napaki ali ce seznam grozenj se ni nalozen - »ne vem« ni »da«), neveljavno ime.
     */
    fun dodajIzjemo(m: Map<String, Long>, ime: String, ura: Long, grozneNalozene: Boolean, naGroznih: (String) -> Boolean): Map<String, Long>? {
        val d = cisto(ime) ?: return null
        if (!ScitIzjeme.veljavna(d) || !grozneNalozene) return null
        if (try { naGroznih(d) } catch (_: Exception) { true }) return null
        val nov = LinkedHashMap(m.filterValues { it > ura })
        nov.remove(d)
        nov[d] = ura + IZJEMA_MS
        while (nov.size > NAJVEC_IZJEM) nov.remove(nov.minByOrNull { it.value }!!.key)
        return nov
    }

    fun prekliciIzjemo(m: Map<String, Long>, ime: String): Map<String, Long> {
        val d = cisto(ime) ?: return m
        return LinkedHashMap(m).apply { remove(d) }
    }

    /** Uporabnikova izjema velja NATANKO za to ime (ne za poddomene) in samo do poteka. */
    fun uporabniskaVelja(ime: String, m: Map<String, Long>, zdaj: Long): Boolean {
        val d = cisto(ime) ?: return false
        return (m[d] ?: 0L) > zdaj
    }

    /**
     * OK na ustavljenem imenu (lastnik in vodja, TV 4. 11. 2026 23:18): prvi fokus zaslona je na prvi vrstici »Nazadnje
     * ustavljeno« (npr. static.doubleclick.net) in en OK po nesreci je oglasni streznik spustil za 30 dni. OK zato samo
     * odpre vprasanje; spust je izrecna izbira »Da«, privzeti gumb je »Ne«.
     */
    enum class OkNaImenu { VPRASAJ, NIC }
    fun okNaImenu(lahkoSpusti: Boolean): OkNaImenu = if (lahkoSpusti) OkNaImenu.VPRASAJ else OkNaImenu.NIC
    /** V vprasanju o spustu ima ob odprtju fokus »Ne« (OK po nesreci nicesar ne spusti). */
    const val PRIVZETO_SPUSTI = false

    /** Gumb »Spusti« ima samo ime, ki ni s seznama grozenj (in seznam grozenj je nalozen). */
    fun lahkoSpusti(grozneNalozene: Boolean, naGroznih: Boolean): Boolean = grozneNalozene && !naGroznih

    // ------------------------------------------------------------------ zasebni DNS in razresevalci naprej

    /**
     * Ali sistemski zasebni DNS (DoT) Scit obide: nacin »hostname« (Settings.Global private_dns_mode) ali omrezje, ki
     * ze ima ime streznika zasebnega DNS (LinkProperties.privateDnsServerName). »opportunistic« (privzeto) ne obide:
     * sistem DoT poskusi pri nasem naslovu, vrata 853 dobijo RST in poizvedba gre po vratih 53 skozi Scit.
     */
    fun zasebniDnsObide(nacin: String?, imeStreznika: String?): Boolean =
        nacin?.trim()?.lowercase() == "hostname" || !imeStreznika.isNullOrBlank()

    /**
     * Razresevalci naprej: samo krajevni (iz omrezja televizorja). Brez njih NE izberemo tujega - poizvedbo odgovorimo
     * s SERVFAIL ([DnsPaket.odgovorNapakaZa]), dokler omrezje ne javi svojega.
     */
    fun <T> naprej(krajevni: List<T>): List<T> = krajevni

    // ------------------------------------------------------------------ privzeto vklopljen, en Scit na napravo (4. 11. 2026)

    /**
     * Lastnik 4. 11. 2026: »Privzeto naj bo Scit vkljucen.« Android za VPN zahteva enkratno sistemsko potrditev, ki je ne
     * obidemo: ob prvem zagonu jo PONUDIMO enkrat. Ne, ce: Scita ne vodi ta aplikacija, je ze bil ponujen, je vklopljen
     * ali je bil ze kdaj v rabi (statistika obstaja - uporabnik ga je sam izklopil; njegove izbire ne povozimo).
     */
    /**
     * Tablica 13. 11. 2026: Safeer OS za TV na tablici, kjer Link (in Scit) vodi Safeer OS Tablet, je ponudil in vklopil
     * svoj Scit in s tem prevzel VPN tablice (Android dovoli en VPN). Svoj tunel vklopimo samo, ce Link te naprave vodimo
     * mi in Scita ne vodi sosednja aplikacija.
     */
    fun smemoVklopitiSami(vodimLink: Boolean, sosed: String?): Boolean = vodimLink && sosed == null

    fun ponudiObZagonu(vodimo: Boolean, ponujeno: Boolean, vklopljen: Boolean, prejUporabljen: Boolean): Boolean =
        vodimo && !ponujeno && !vklopljen && !prejUporabljen

    /**
     * Kljukica »Safeer Scit: vse aplikacije« v meniju brskalnika: kadar Scit vodi drug paket (Safeer OS), velja NJEGOV
     * odgovor (ScitSorodnikStoritev); brez odgovora ne trdimo, da je vklopljen. Prej: vedno zastavica lastnega paketa -
     * na TV s Safeer OS je bil Scit vklopljen, kljukica pa prazna (lastnik 4. 11. 2026, fotografija).
     */
    fun kljukica(vodimo: Boolean, lokalnoVklopljen: Boolean, sosedVklopljen: Boolean?): Boolean =
        if (vodimo) lokalnoVklopljen else sosedVklopljen == true

    enum class Klik { LOKALNO, PREK_SOSEDA_VKLOPI, PREK_SOSEDA_IZKLOPI, ZASLON_SOSEDA }

    /**
     * Klik na Scit v meniju brskalnika. Kadar Scit vodi Safeer OS, ga krmilimo TAM (en VPN naenkrat; prej je klik odprl
     * domaci zaslon Safeer OS in brskalnik je izginil - videti kot sesutje, lastnik 4. 11. 2026). Brez znanega stanja
     * odpremo zaslon Scita v Safeer OS, ki pokaze resnico.
     */
    fun klik(vodimo: Boolean, sosedVklopljen: Boolean?): Klik = when {
        vodimo -> Klik.LOKALNO
        sosedVklopljen == null -> Klik.ZASLON_SOSEDA
        sosedVklopljen -> Klik.PREK_SOSEDA_IZKLOPI
        else -> Klik.PREK_SOSEDA_VKLOPI
    }
}
