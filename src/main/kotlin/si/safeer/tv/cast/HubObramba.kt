package si.safeer.tv.cast

/**
 * Obrambni mehanizem sredisca Safeer Linka: steje sovrazne dogodke po viru (naslov IP) in vir za nekaj casa zapre.
 *
 * Sredisce poslusa v domacem omrezju. Kdor ni clan kroga zaupanja, tam nima kaj iskati - a doslej je lahko poskusal
 * brez konca: tipal poti, ugibal kodo seznanitve (vsak zacetek seznanitve uporabniku pokaze kodo), posiljal zavrnjene
 * podpise in vstopnice, odpiral povezave. Stevilo poskusov je bilo omejeno samo znotraj ene prijave.
 *
 * Ista pravila in iste stevilke kot `core/link_obramba.py` na racunalniku (docs/LINK-DEFENCE.md):
 *  - vsak sovrazen dogodek ima tezo; ko vsota tez enega vira v zadnji minuti doseze PRAG, je vir zaprt. Zapora je
 *    tiha: povezava se zapre takoj po sprejemu - brez rokovanja TLS, brez potrdila, brez odgovora;
 *  - zapora traja ZAPORA_MS, ob vsaki ponovitvi dvakrat dlje (do NAJDALJSA_ZAPORA_MS);
 *  - vir, ki se je pravkar izkazal kot clan kroga (veljaven podpis ali vstopnica) ali ima pri sredisci odprto
 *    povezavo, je zaupan: gole povezave se mu ne stejejo (telefon, ki lista mapo s slikami, jih odpre na stotine),
 *    sovrazni dogodki pa stejejo polovico;
 *  - ta naprava sama (127.0.0.1, ::1) ni nikoli zaprta: od tam prihajajo lastni zaslon in kanali Global Linka;
 *  - vec zaprtih virov v kratkem casu ali vir, ki se po zapori vraca, je napad.
 *
 * Razred je cista logika (ura je parameter): brez Androida, omrezja in niti - preizkusljiv na JVM.
 */
class HubObramba(
    private val ura: () -> Long = { System.nanoTime() / 1_000_000L },
    private val obZapori: ((vir: String, trajanjeMs: Long, razlog: String) -> Unit)? = null,
    private val obNapadu: ((viri: List<String>) -> Unit)? = null,
    private val izvzet: (String) -> Boolean = { jeTaNaprava(it) },
    /** Vir je na polovici praga (vsota tez, sestava po vrstah): samo za dnevnik, najvec enkrat na OPOZORILO_VSAKIH_MS. */
    private val obOpozorilu: ((vir: String, vsota: Int, sestava: Map<String, Int>) -> Unit)? = null
) {
    /**
     * Zaupanje od zunaj: ali ima vir pri sredisci odprto povezavo (prijavljena naprava, sosednje sredisce).
     * Klice se zunaj zaklepa obrambe.
     */
    @Volatile
    var zaupan: ((String) -> Boolean)? = null

    private class Dogodek(val cas: Long, val teza: Int, val vrsta: String)

    private class Vir {
        val dogodki = ArrayDeque<Dogodek>()
        var zaprtDo = 0L
        var ponovitev = 0
        var zadnjaZapora = Long.MIN_VALUE / 2
        var zaupanDo = 0L
        var razlog = ""
        var sestava: Map<String, Int> = emptyMap()
        var opozorjen = Long.MIN_VALUE / 2
    }

    data class Zaprt(val vir: String, val seMs: Long, val razlog: String, val zapora: Int, val sestava: Map<String, Int>)
    data class Stanje(val zaprti: List<Zaprt>, val zavrnjenih: Long, val napad: Boolean)

    private val zaklep = Any()

    /** Urejeno po zadnjem dostopu: najstarejsi nezaprti vir izpade, ko je spomin poln. */
    private val viri = LinkedHashMap<String, Vir>(64, 0.75f, true)
    private var zavrnjenih = 0L
    private val zapore = ArrayDeque<Pair<Long, String>>()
    private var napadJavljen = Long.MIN_VALUE / 2

    // ------------------------------------------------------------------ vhod

    /** Ob sprejemu povezave: false = vir je zaprt, povezavo zapri brez odgovora. Povezavo tudi presteje. */
    fun dovoli(vir: String): Boolean {
        if (vir.isEmpty() || izvzet(vir)) return true
        val povezan = jePovezan(vir)
        var obvestila: List<Obvestilo> = emptyList()
        val dovoljen: Boolean
        synchronized(zaklep) {
            val zdaj = ura()
            val v = vir(vir, zdaj)
            if (v.zaprtDo > zdaj) {
                zavrnjenih++
                return false
            }
            if (v.zaupanDo <= zdaj && !povezan) {
                dodaj(v, Dogodek(zdaj, teza(POVEZAVA), POVEZAVA))
                obvestila = oceni(vir, v, zdaj)
            }
            dovoljen = v.zaprtDo <= zdaj
        }
        obvesti(obvestila)
        return dovoljen
    }

    /** Sovrazen dogodek vira (vrsta iz TEZE; neznana vrsta steje kot tipanje). */
    fun dogodek(vir: String, vrsta: String) {
        if (vir.isEmpty() || vrsta.isEmpty() || izvzet(vir)) return
        val povezan = jePovezan(vir)
        val obvestila: List<Obvestilo>
        synchronized(zaklep) {
            val zdaj = ura()
            val v = vir(vir, zdaj)
            if (v.zaprtDo > zdaj) return
            var t = teza(vrsta)
            if (v.zaupanDo > zdaj || povezan) t /= 2
            dodaj(v, Dogodek(zdaj, t, vrsta))
            obvestila = oceni(vir, v, zdaj)
        }
        obvesti(obvestila)
    }

    /** Vir se je izkazal kot clan kroga (veljaven podpis, vstopnica ali zeton). */
    fun zaupaj(vir: String) {
        if (vir.isEmpty() || izvzet(vir)) return
        synchronized(zaklep) {
            val zdaj = ura()
            val v = vir(vir, zdaj)
            v.zaupanDo = zdaj + ZAUPANJE_MS
            // Povezave, ki jih je odprl pred prijavo, niso bile poplava.
            v.dogodki.removeAll { it.vrsta == POVEZAVA }
        }
    }

    // ------------------------------------------------------------------ stanje

    fun zaprt(vir: String): Boolean = synchronized(zaklep) { (viri[vir]?.zaprtDo ?: 0L) > ura() }

    /** Za vmesnik in dnevnik: zaprti viri, stevilo zavrnjenih povezav, ali je bil pravkar javljen napad. */
    fun stanje(): Stanje = synchronized(zaklep) {
        val zdaj = ura()
        Stanje(
            viri.filter { it.value.zaprtDo > zdaj }
                .map { Zaprt(it.key, it.value.zaprtDo - zdaj, it.value.razlog, it.value.ponovitev, it.value.sestava) },
            zavrnjenih,
            zdaj - napadJavljen < NAPAD_OKNO_MS
        )
    }

    /** Uporabnik vir sprosti sam (npr. svojo napravo, ki je zgresila kodo). */
    fun sprosti(vir: String): Boolean = synchronized(zaklep) {
        val v = viri[vir] ?: return@synchronized false
        if (v.zaprtDo <= ura()) return@synchronized false
        v.zaprtDo = 0L
        v.ponovitev = 0
        v.dogodki.clear()
        true
    }

    // ------------------------------------------------------------------ notranje

    private sealed class Obvestilo {
        class Zapora(val vir: String, val trajanjeMs: Long, val razlog: String) : Obvestilo()
        class Napad(val viri: List<String>) : Obvestilo()
        class Opozorilo(val vir: String, val vsota: Int, val sestava: Map<String, Int>) : Obvestilo()
    }

    private fun jePovezan(vir: String): Boolean = try { zaupan?.invoke(vir) ?: false } catch (_: Throwable) { false }

    private fun dodaj(v: Vir, d: Dogodek) {
        if (v.dogodki.size >= NAJVEC_DOGODKOV) v.dogodki.removeFirst()
        v.dogodki.addLast(d)
    }

    private fun vir(ime: String, zdaj: Long): Vir {
        viri[ime]?.let { return it }
        val v = Vir()
        viri[ime] = v
        if (viri.size > NAJVEC_VIROV) {
            // Najstarejsi vir, ki ni zaprt, izpade; ce so zaprti vsi (poplava z mnogo naslovov), najstarejsi.
            val izpade = viri.entries.firstOrNull { it.value.zaprtDo <= zdaj && it.value !== v }?.key
                ?: viri.keys.first()
            viri.remove(izpade)
        }
        return v
    }

    private fun oceni(ime: String, v: Vir, zdaj: Long): List<Obvestilo> {
        while (v.dogodki.isNotEmpty() && v.dogodki.first().cas <= zdaj - OKNO_MS) v.dogodki.removeFirst()
        val vsota = v.dogodki.sumOf { it.teza }
        if (vsota < OPOZORILO_PRAG) return emptyList()
        val teze = LinkedHashMap<String, Int>()
        for (d in v.dogodki) teze[d.vrsta] = (teze[d.vrsta] ?: 0) + d.teza
        if (vsota < PRAG) {
            if (zdaj - v.opozorjen < OPOZORILO_VSAKIH_MS) return emptyList()
            v.opozorjen = zdaj
            return listOf(Obvestilo.Opozorilo(ime, vsota, teze))
        }
        // Razlog = vrsta z najvecjo skupno tezo (kaj je vir v resnici pocel).
        v.razlog = teze.maxByOrNull { it.value }?.key ?: ""
        v.sestava = teze
        if (zdaj - v.zadnjaZapora > POZABI_PONOVITVE_MS) v.ponovitev = 0
        val trajanje = minOf(NAJDALJSA_ZAPORA_MS, ZAPORA_MS shl minOf(v.ponovitev, 8))
        v.ponovitev++
        v.zadnjaZapora = zdaj
        v.zaprtDo = zdaj + trajanje
        v.dogodki.clear()
        if (zapore.size >= 64) zapore.removeFirst()
        zapore.addLast(zdaj to ime)
        val obvestila = ArrayList<Obvestilo>(2)
        obvestila.add(Obvestilo.Zapora(ime, trajanje, v.razlog))
        val nedavni = zapore.filter { it.first > zdaj - NAPAD_OKNO_MS }.map { it.second }.distinct().sorted()
        if ((nedavni.size >= NAPAD_VIROV || v.ponovitev >= NAPAD_PONOVITEV) && zdaj - napadJavljen >= NAPAD_OKNO_MS) {
            napadJavljen = zdaj
            obvestila.add(Obvestilo.Napad(nedavni))
        }
        return obvestila
    }

    private fun obvesti(obvestila: List<Obvestilo>) {
        for (o in obvestila) {
            try {
                when (o) {
                    is Obvestilo.Zapora -> obZapori?.invoke(o.vir, o.trajanjeMs, o.razlog)
                    is Obvestilo.Napad -> obNapadu?.invoke(o.viri)
                    is Obvestilo.Opozorilo -> obOpozorilu?.invoke(o.vir, o.vsota, o.sestava)
                }
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        const val POVEZAVA = "povezava"
        const val ROKOVANJE = "rokovanje"
        const val TIPANJE = "tipanje"
        const val BREZ_ZAUPANJA = "brez_zaupanja"
        const val OKVIR = "okvir"
        const val POSKUS_KODE = "poskus_kode"
        const val SEZNANITEV = "seznanitev"
        const val ZACETEK_SEZNANITVE = "zacetek_seznanitve"

        /**
         * Teza dogodka (cela stevila, da je vsota natancna). Legitimna naprava jih naredi nekaj na minuto (prijava pod
         * drugim id-jem vrne 401); napadalec jih naredi na desetine.
         */
        val TEZE: Map<String, Int> = mapOf(
            POVEZAVA to 1,              // sprejeta povezava vira, ki ni zaupan (poplava: 400 na minuto)
            ROKOVANJE to 10,            // rokovanje TLS ni uspelo (ni TLS, pregledovalnik vrat, tiha povezava)
            TIPANJE to 10,              // pot, ki je sredisce nima, ali okvarjena zahteva
            BREZ_ZAUPANJA to 20,        // zavrnjen podpis, vstopnica ali zeton; koda QR, ki je ni (20 na minuto)
            OKVIR to 20,                // pokvarjen okvir WebSocket
            POSKUS_KODE to 20,          // en krog SPAKE2 = en poskus kode (prijava ima pet krogov)
            SEZNANITEV to 40,           // napacna koda, prevec poskusov, zacetek prijave s kodo QR (10 na minuto)
            ZACETEK_SEZNANITVE to 100   // zacetek seznanitve s kodo: uporabniku pokaze kodo (cetrti v minuti zapre vir)
        )

        fun teza(vrsta: String): Int = TEZE[vrsta] ?: 10

        const val OKNO_MS = 60_000L
        const val PRAG = 400
        const val ZAPORA_MS = 600_000L
        const val NAJDALJSA_ZAPORA_MS = 3_600_000L
        const val POZABI_PONOVITVE_MS = 24 * 3_600_000L
        const val ZAUPANJE_MS = 600_000L
        const val NAJVEC_VIROV = 1024
        const val NAJVEC_DOGODKOV = 1024
        const val NAPAD_VIROV = 3
        const val NAPAD_PONOVITEV = 3
        const val NAPAD_OKNO_MS = 1_800_000L

        /** Opozorilo v dnevnik na polovici praga: lazni preplah prave naprave se vidi, se preden je zaprta. */
        const val OPOZORILO_PRAG = PRAG / 2
        const val OPOZORILO_VSAKIH_MS = 600_000L

        /** Sestava dogodkov za dnevnik: "brez_zaupanja 380, povezava 21". */
        fun opis(sestava: Map<String, Int>): String =
            sestava.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" }

        /** Ali povezava prihaja s te naprave same (zanka). */
        fun jeTaNaprava(vir: String): Boolean {
            var v = vir.trim().lowercase()
            if (v.startsWith("::ffff:")) v = v.substring(7)
            return v == "::1" || v == "0:0:0:0:0:0:0:1" || v == "localhost" || v.startsWith("127.")
        }

        /**
         * Koda napake v odgovoru sredisca -> vrsta sovraznega dogodka. Cesar tu ni, ni sovrazno: 405 je del prepoznave
         * sredisca, 409/410/503 so stanje, »naprava_ni_povezana« je odgovor seznanjeni napravi.
         */
        val VRSTA_PO_NAPAKI: Map<String, String> = mapOf(
            "ni_poti" to TIPANJE, "samo_krajevno" to TIPANJE, "ni_nadgradnje" to TIPANJE,
            "manjka_device_id" to TIPANJE, "manjka_pair_id" to TIPANJE, "manjka_pb" to TIPANJE, "manjka_cb" to TIPANJE,
            "manjka_koda" to TIPANJE, "manjka_alias" to TIPANJE, "neveljavno" to TIPANJE,
            "naprava_ni_seznanjena" to BREZ_ZAUPANJA, "ni_v_krogu" to BREZ_ZAUPANJA, "naprava_ni_v_krogu" to BREZ_ZAUPANJA,
            "ni_vstopnice" to BREZ_ZAUPANJA, "ni_sorodnik" to BREZ_ZAUPANJA, "neveljaven_izziv" to BREZ_ZAUPANJA,
            "napacen_podpis" to BREZ_ZAUPANJA, "neveljaven_kljuc" to BREZ_ZAUPANJA,
            "qr_ne_obstaja" to BREZ_ZAUPANJA, "prijava_ne_obstaja" to BREZ_ZAUPANJA,
            "napacna_koda" to SEZNANITEV, "prevec_poskusov" to SEZNANITEV, "prevec_prijav" to SEZNANITEV,
            "neveljavna_tocka" to SEZNANITEV,
            // Varovalka je povezovanje s kodo zaprla: kdor vseeno sprasuje, tipa (uporabnikova nova naprava vprasa enkrat).
            "seznanitev_zaprta" to TIPANJE
        )

        private val KODA_V_TELESU = Regex("\"(?:code|error_code)\"\\s*:\\s*\"([a-z_]+)\"")

        /** Vrsta sovraznega dogodka za odgovor z napako ("" = ni sovrazno). `telo` je JSON odgovora. */
        fun vrstaNapake(kodaHttp: Int, telo: String): String {
            if (kodaHttp < 400) return ""
            val oznaka = KODA_V_TELESU.find(telo)?.groupValues?.get(1) ?: ""
            VRSTA_PO_NAPAKI[oznaka]?.let { return it }
            // Odgovori streznika samega nimajo kode: 404 je pot, ki je ni; 403 pri nadgradnji je zavrnjena vstopnica.
            return if (oznaka.isEmpty() && kodaHttp == 404) TIPANJE else ""
        }
    }
}
