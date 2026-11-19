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

    /** Meje (pomnilnik televizorja ni na pretek; vmesnik ne sme biti nacin za zapolnitev pomnilnika ali diska). */
    const val NAJVEC_ZAPISKOV = 5000
    const val NAJVEC_BESEDILO = 20_000
    const val NAJVEC_TOVOR = 1_048_576
    /** Najvec znakov JSON celotne shrambe zapiskov: odgovor je EN Binder kos (omejitev ~1 MB na proces, znak = 2 bajta). */
    const val NAJVEC_SHRAMBA = 262_144
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
        vrednost.isNotEmpty() && vrednost.length <= NAJVEC_KLJUC_DOLZINA && vrednost.none { it == '\u0000' }

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
        if (elementi.size > NAJVEC_ZAPISKOV) return null
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

    /** Zdruzena shramba sme na disk samo znotraj mej; sicer vmesnik sporoci neuspeh in shramba ostane nedotaknjena. */
    fun smeShraniti(zapiski: List<Zapisek>): Boolean = zapiski.size <= NAJVEC_ZAPISKOV && vJson(zapiski).length <= NAJVEC_SHRAMBA

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
    class TrajniZapiski(private val beri: () -> String?, private val pisi: (String) -> Boolean) {
        private var trajno: List<Zapisek>? = null

        fun zapiski(): List<Zapisek> = trajno ?: (izJson(beri() ?: "") ?: emptyList()).also { trajno = it }

        /** Zdruzeno stanje ali null (prevelika shramba ali zapis ni uspel; vidno ostane prejsnje trajno stanje). */
        fun sprejmi(vhod: List<Zapisek>): List<Zapisek>? {
            val zdruzeno = zdruzi(zapiski(), vhod)
            if (!smeShraniti(zdruzeno)) return null
            if (!pisi(vJson(zdruzeno))) return null
            trajno = zdruzeno
            return zdruzeno
        }
    }
}
