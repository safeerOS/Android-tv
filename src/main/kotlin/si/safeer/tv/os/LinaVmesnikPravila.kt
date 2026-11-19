package si.safeer.tv.os

import si.safeer.tv.cast.JsonLahki

/**
 * Cista pravila lokalnega vmesnika Safeer OS za Lino (lastnik, 19. 11. 2026; nacrt
 * outputs/nacrti/uskladitev-kljucev-lina.md, outputs/COORDINACIJA-POTRDITEV-LINA.md).
 *
 * Lina je popolnoma samostojna aplikacija; Safeer OS na isti napravi je neobvezen: Lina mu zapiske in kljuce asistenta
 * samo doprinese, Safeer OS pa jih po obstojecem krogu zaupanja in Global Linku prenese na druge naprave. Lina NIMA
 * lastnega clanstva v krogu in ne podvaja kode Linka. Vmesnik nikoli ne sprozi besedilo klica ali sporocila: klicatelj
 * je Binder sporocilo aplikacije z ISTIM PODPISOM (dovoljenje si.safeer.tv.permission.LINK), preverjen se po uid in
 * podpisu paketa ([jeLina]).
 *
 * Pravila zdruzevanja zapiskov so ISTA kot v Lini (Spomin.kt, funkcija zdruzi) in jih dokazujejo ISTI skupni vektorji
 * (Lina repozitorij, openline/android/skupni-vektorji/zapiski-zdruzitev-v1.json; tests/LinaVmesnikPravilaTest.kt):
 * novejsi cas zmaga; pri enakem casu zmaga izbris; pri enakem casu in izbrisu vecje besedilo. Brisanje se prenasa
 * (izbrisan + cas), za razliko od zaznamkov.
 */
object LinaVmesnikPravila {
    /** Edina aplikacija, ki sme uporabljati vmesnik; NI na seznamu sorodnikov za medijske kljuce (najmanjsa pravica). */
    const val PAKET_LINA = "si.safeer.lina"
    const val VERZIJA = 1

    /** Skupne meje shrambe zapiskov (skupni vektorji Line, razdelek ciscenje): zapiskov in KODNIH TOCK vseh besedil.
     *  Pomnilnik televizorja ni na pretek; vmesnik ne sme biti nacin za zapolnitev pomnilnika ali diska. */
    const val NAJVEC_ZAPISKOV = 5000
    const val NAJVEC_ZNAKOV = 262_144
    /** Posamezen zapisek je omejen samo s skupno mejo (Lina ne pozna manjse meje; prej 20000 je zavracal veljavne zapiske). */
    const val NAJVEC_BESEDILO = NAJVEC_ZNAKOV
    /** Nagrobnik (izbrisan=true) se po tem roku odstrani (skupna pravila Line). Naprava, ki je dlje brez povezave, ga lahko spet oziv. */
    const val ROK_NAGROBNIKA_MS = 90L * 24 * 60 * 60 * 1000
    /** Samo prenos: vhod in shramba kot JSON ne smeta preseci enega Binder kosa (~1 MB na proces); ni del skupnih pravil. */
    const val NAJVEC_TOVOR = 1_048_576
    const val NAJVEC_KLJUCEV = 64
    const val NAJVEC_KLJUC_DOLZINA = 4096

    /** Kategorija usklajevanja zapiskov v krogu (HubUsmerjevalnik sync.data/sync.request, kot safeer.media.v1). */
    const val KATEGORIJA_ZAPISKI = "safeer.notes.v1"

    /** Zapiski iz tovora sync.data: samo prava kategorija in strogo veljaven Zapisi v1; sicer null (ne sprejmemo nicesar). */
    fun izTovora(kategorija: String?, podatki: String?): List<Zapisek>? =
        if (kategorija != KATEGORIJA_ZAPISKI || podatki == null) null else izJson(podatki)

    /**
     * Ponovna objava po sprejemu: samo ce je zdruzeno stanje drugacno od prejetega (prejemnik ima kaj, cesar posiljatelj
     * nima). Ko je enako, se ustavi - dve napravi se ne posiljata v nedogled (obe imata po zdruzitvi isto stanje).
     */
    fun potrebnaObjava(prejeto: List<Zapisek>, zdruzeno: List<Zapisek>): Boolean = prejeto.sortedBy { it.id } != zdruzeno.sortedBy { it.id }

    /** Ime kljuca asistenta: samo imenski prostor `asistent.`, nikoli ime medijskega kljuca (Debrid, Usenet, IPTV ...). */
    private val IME_KLJUCA = Regex("^asistent\\.[a-z0-9_]{1,40}$")

    /** Klicatelj je Lina: paket je `si.safeer.lina`, ni ta aplikacija in ima ISTI podpis (ponaredek z drugim podpisom nikoli). */
    fun jeLina(paket: String?, jaz: String, podpisEnak: Boolean): Boolean =
        paket != null && paket == PAKET_LINA && paket != jaz && podpisEnak

    fun veljavnoImeKljuca(ime: String): Boolean = IME_KLJUCA.matches(ime)

    fun veljavnaVrednostKljuca(vrednost: String): Boolean =
        vrednost.isNotEmpty() && vrednost.length <= NAJVEC_KLJUC_DOLZINA && vrednost.none { it < ' ' || it == '\u007f' }

    data class Zapisek(val id: String, val besedilo: String, val cas: Long, val izbrisan: Boolean)

    /** Zdruzitev je komutativna (a,b in b,a dasta isti izid); izid je urejen po id (kot v Lini). */
    fun zdruzi(a: List<Zapisek>, b: List<Zapisek>): List<Zapisek> {
        val mapa = LinkedHashMap<String, Zapisek>()
        for (z in a + b) {
            val obstojeci = mapa[z.id]
            if (obstojeci == null) {
                mapa[z.id] = z
            } else if (z.cas > obstojeci.cas) {
                mapa[z.id] = z
            } else if (z.cas == obstojeci.cas) {
                if (z.izbrisan && !obstojeci.izbrisan) mapa[z.id] = z
                else if (z.izbrisan == obstojeci.izbrisan && z.besedilo > obstojeci.besedilo) mapa[z.id] = z
            }
        }
        return mapa.values.sortedBy { it.id }
    }

    /**
     * Zapisi (verzija 1) kot v Lini: `{"verzija":1,"zapiski":[{"id","besedilo","cas","izbrisan"}],"opomniki":[...]}`.
     * Opomnike Safeer OS ne obdela (ne hrani jih, vektorji jih ne pokrivajo). Vse, kar ni strogo v redu (tip polja, verzija,
     * meje, podvojen id), pomeni null: napacen vnos ne sme tiho spremeniti shrambe.
     */
    fun izJson(vir: String): List<Zapisek>? {
        if (vir.isBlank() || vir.length > NAJVEC_TOVOR) return null
        if (!JsonLahki.strogObjekt(vir)) return null
        val koren = JsonLahki.objekt(vir) ?: return null
        if (koren.stevilo("verzija") != VERZIJA.toDouble()) return null
        val surovi = koren.surovo("zapiski") ?: return null
        val elementi = JsonLahki.elementi(surovi) ?: return null
        // Stevila zapiskov tu ne omejujemo: vhod nad mejo se najprej pociste (nagrobniki), sele nato velja meja (pocistiNagrobnike).
        val izid = ArrayList<Zapisek>(elementi.size)
        val videni = HashSet<String>()
        for (e in elementi) {
            val o = JsonLahki.objekt(e) ?: return null
            val id = o.niz("id") ?: return null
            val besedilo = o.niz("besedilo") ?: return null
            val cas = o.stevilo("cas") ?: return null
            val izbrisan = o.logicno("izbrisan") ?: return null
            if (id.isEmpty() || id.length > 128 || besedilo.length > NAJVEC_BESEDILO) return null
            if (cas < 0 || cas != Math.floor(cas) || cas > 9.0e15) return null
            if (!videni.add(id)) return null
            izid.add(Zapisek(id, besedilo, cas.toLong(), izbrisan))
        }
        return izid
    }

    /** Kodne tocke Unicode vseh besedil (tudi nagrobnikov), kot v Lini (ZapiskiZapis.znakov). */
    fun znakov(seznam: List<Zapisek>): Int = seznam.sumOf { it.besedilo.codePointCount(0, it.besedilo.length) }

    data class IzidCiscenja(val zapiski: List<Zapisek>, val sprejeto: Boolean)

    /**
     * Ciscenje nagrobnikov STROGO po skupnih pravilih (vektorji, razdelek ciscenje; Lina ZapiskiZapis.pocistiNagrobnike):
     * nagrobnik s cas < zdaj - rok gre; ob preseku mej gre najprej najstarejsi nagrobnik (pri enakem casu manjsi id);
     * ZIVI ZAPISKI NIKOLI; sprejeto=false, ce meja ostane presezena.
     */
    fun pocistiNagrobnike(
        seznam: List<Zapisek>, zdajMs: Long,
        rokMs: Long = ROK_NAGROBNIKA_MS, najvecZapiskov: Int = NAJVEC_ZAPISKOV, najvecZnakov: Int = NAJVEC_ZNAKOV,
    ): IzidCiscenja {
        val s = seznam.filterNot { it.izbrisan && it.cas < zdajMs - rokMs }
        var stevilo = s.size
        var kodnihTock = znakov(s)
        var ostali = s
        if (stevilo > najvecZapiskov || kodnihTock > najvecZnakov) {
            // Isti izid kot odstranjevanje enega najstarejsega nagrobnika naenkrat, a v O(n log n): 5 ciklov po vhodu do 1 MiB
            // (deset tisoc drobnih zapiskov) bi sicer na 32-bitnem TV zasedlo nit Binder.
            val odstrani = HashSet<Int>()
            for (i in s.indices.filter { s[it].izbrisan }.sortedWith(compareBy({ s[it].cas }, { s[it].id }))) {
                if (stevilo <= najvecZapiskov && kodnihTock <= najvecZnakov) break
                odstrani.add(i); stevilo--; kodnihTock -= s[i].besedilo.let { it.codePointCount(0, it.length) }
            }
            ostali = s.filterIndexed { i, _ -> i !in odstrani }
        }
        return IzidCiscenja(ostali.sortedBy { it.id }, stevilo <= najvecZapiskov && kodnihTock <= najvecZnakov)
    }

    /** Stanje sme na disk le znotraj skupnih mej in znotraj enega Binder kosa; sicer sprejeto=false in shramba ostane nedotaknjena. */
    fun smeShraniti(zapiski: List<Zapisek>): Boolean =
        zapiski.size <= NAJVEC_ZAPISKOV && znakov(zapiski) <= NAJVEC_ZNAKOV && vJson(zapiski).length <= NAJVEC_TOVOR

    fun vJson(zapiski: List<Zapisek>): String {
        val seznam = zapiski.joinToString(",", "[", "]") {
            JsonLahki.Zapis().niz("id", it.id).niz("besedilo", it.besedilo).stevilo("cas", it.cas.toDouble()).logicno("izbrisan", it.izbrisan).toString()
        }
        return JsonLahki.Zapis().stevilo("verzija", VERZIJA.toDouble()).surovo("zapiski", seznam).surovo("opomniki", "[]").toString()
    }

    /**
     * Zapiski z zadnjim TRAJNIM stanjem (pregled CodeRabbit 19. 11. 2026): ce zapis na disk ne uspe, SharedPreferences
     * obdrzi novo vrednost v pomnilniku (commit() je ne povrne), zato bi naslednje branje pokazalo zavrnjeno spremembo
     * (npr. izbris) in jo kasnejsi uspesen commit drugega kljuca zapisal na disk. Zato zapiski() vrne izkljucno stanje,
     * ki je bilo uspesno zapisano; [pisi] pri neuspehu sam povrne staro vrednost v shrambi.
     */
    class TrajniZapiski(
        private val beri: () -> String?, private val pisi: (String) -> Boolean,
        private val ura: () -> Long = { System.currentTimeMillis() },
    ) {
        private var trajno: List<Zapisek>? = null

        fun zapiski(): List<Zapisek> = trajno ?: (izJson(beri() ?: "") ?: emptyList()).also { trajno = it }

        /** Zdruzeno stanje ali null (prevelika shramba ali zapis ni uspel; vidno ostane prejsnje trajno stanje). */
        fun sprejmi(vhod: List<Zapisek>): List<Zapisek>? {
            val izid = pocistiNagrobnike(zdruzi(zapiski(), vhod), ura())
            if (!izid.sprejeto || !smeShraniti(izid.zapiski)) return null
            val zdruzeno = izid.zapiski
            if (!pisi(vJson(zdruzeno))) return null
            trajno = zdruzeno
            return zdruzeno
        }
    }
}
