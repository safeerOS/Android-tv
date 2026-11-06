package si.safeer.tv.os

/**
 * Meni vsebine v Medijskem centru (OK-dolgo na kartici) in vrstice prazne Glasbe - ciste odlocitve, brez Androida
 * (preizkus: tests/MeniVsebinePravilaTest.kt).
 *
 * Lastnik 6. 11. 2026 04:34-04:40 (posnetki TV): v seznamu predvajanja ni bilo »Odstrani s seznama predvajanja«, meni pa
 * je ponujal »Shrani kot seznam predvajanja«, ceprav je bil element ze na seznamu; prazna Glasba je imela dve vrstici
 * »Seznami predvajanja« (seznami in posamezni posnetki iz njih); vrstica »Nedavno« tam ni imela »Pocisti«.
 */
object MeniVsebinePravila {
    /** Gumbi menija, o katerih odloca [gumbi] (ostala dejanja - vir, dodatek, kanal - doda meni sam). */
    enum class Gumb { ODSTRANI_S_SEZNAMA, DODAJ_PRILJ, ODSTRANI_PRILJ, DODAJ_NA_SEZNAM, DODAJ_NA_DRUG_SEZNAM, SHRANI_VRSTO, ODSTRANI_NEDAVNO }

    /**
     * Gumbi glede na pogled in stanje: [vSeznamu] = meni je odprt na elementu znotraj seznama predvajanja; [nedavno] = v
     * vrstici »Nedavno«; [ponudiPrilj]/[jePrilj] = priljubljene; [ponudiSeznam] = element lahko gre na seznam predvajanja;
     * [shranljivihVVrsti] = koliko elementov vrste bi shranil »Shrani kot seznam predvajanja« (cela vrsta, ne ta element).
     * V seznamu: odstrani s tega seznama, priljubljene, dodaj na DRUG seznam - shranjevanje vrste tam nima smisla.
     */
    fun gumbi(vSeznamu: Boolean, nedavno: Boolean, ponudiPrilj: Boolean, jePrilj: Boolean, ponudiSeznam: Boolean,
              shranljivihVVrsti: Int): List<Gumb> {
        val r = ArrayList<Gumb>()
        if (vSeznamu) r += Gumb.ODSTRANI_S_SEZNAMA
        if (ponudiPrilj) r += if (jePrilj) Gumb.ODSTRANI_PRILJ else Gumb.DODAJ_PRILJ
        if (ponudiSeznam) r += if (vSeznamu) Gumb.DODAJ_NA_DRUG_SEZNAM else Gumb.DODAJ_NA_SEZNAM
        if (!vSeznamu && !nedavno && ponudiSeznam && shranljivihVVrsti > 1) r += Gumb.SHRANI_VRSTO
        if (nedavno) r += Gumb.ODSTRANI_NEDAVNO
        return r
    }

    /** Seznami, ki jih ponudi »Dodaj na seznam«: vsi razen tistih, v katerih element ze je (prazno = samo »Nov seznam«). */
    fun ponujeniSeznami(vsi: List<String>, vsebujejo: Set<String>): List<String> = vsi.filterNot { it in vsebujejo }

    /**
     * Mesto vnosa za odstranitev: [mesto], na katerem je uporabnik, ce je tam se isti element; sicer (seznam se je medtem
     * spremenil, npr. uskladitev z drugo napravo) prvo mesto z [id]; -1 = elementa ni vec.
     */
    fun mestoOdstranitve(ids: List<String>, mesto: Int, id: String): Int =
        if (mesto in ids.indices && ids[mesto] == id) mesto else ids.indexOf(id)

    /** Seznam brez vnosa na [mestoOdstranitve] (ostali in njihov red ostanejo); nespremenjen, ce elementa ni. */
    fun <T> odstraniNaMestu(vnosi: List<T>, mesto: Int, id: String, idOd: (T) -> String): List<T> {
        val i = mestoOdstranitve(vnosi.map(idOd), mesto, id)
        return if (i < 0) vnosi else vnosi.filterIndexed { k, _ -> k != i }
    }

    // ------------------------------------------------------------------ prazna Glasba (»sorodno iz tvojih virov«)

    /** Vrstice prazne Glasbe ali Radia; [kljuc] = ime besedila naslova vrstice (res/values*). */
    enum class Polica(val kljuc: String) {
        SEZNAMI("os_seznami_predvajanja"),
        KONCERTI("os_media_sorodno_koncerti"),
        KANALI("os_media_sorodno_kanali"),
        NEDAVNO("os_media_nedavno"),
    }

    /**
     * Vrstice prazne Glasbe ali Radia po stalnem vrstnem redu: seznami predvajanja (kartice seznamov, v glavi), koncerti,
     * glasbeni kanali, nedavno. Posnetkov iz seznamov tu ni (lastnik 6. 11. 2026 04:39): vidni so, ko seznam odpres.
     */
    fun policePrazne(radio: Boolean, videospoti: Boolean, seznamov: Int, glasbeniVir: Boolean, tvVir: Boolean): List<Polica> {
        val r = ArrayList<Polica>()
        if (!radio && seznamov > 0) r += Polica.SEZNAMI
        if (!radio && !videospoti && glasbeniVir) r += Polica.KONCERTI
        if (radio && tvVir) r += Polica.KANALI
        if (!radio) r += Polica.NEDAVNO
        return r
    }

    // ------------------------------------------------------------------ nedavno

    /** Kljuc nedavnega v shrambi Medijskega centra (safeer_mediji); napredek »Nadaljuj« je v svoji shrambi (MediaNapredek). */
    const val KLJUC_NEDAVNO = "nedavno"

    /**
     * »Pocisti nedavno«: v shrambi ([kljuc] -> JSON) izprazni samo nedavno. Priljubljene, seznami predvajanja in napredek
     * »Nadaljuj« ostanejo (lastnik 6. 11. 2026 04:40).
     */
    fun pocistiNedavno(shramba: Map<String, String>): Map<String, String> = shramba + (KLJUC_NEDAVNO to "[]")

    /** Odstrani element iz nedavnega z vsemi razlicicami istega naslova ([kljuc]) - sicer se namesto njega pokaze druga. */
    fun <T> odstraniNedavno(vnosi: List<T>, izbran: T, idOd: (T) -> String, kljuc: (T) -> String): List<T> {
        val id = idOd(izbran)
        val k = kljuc(izbran)
        return vnosi.filterNot { idOd(it) == id || kljuc(it) == k }
    }

    /** Kartice vrstice »Nedavno«: vnosi in na koncu »Pocisti« ([pocisti]); prazno nedavno = brez vrstice (prazen seznam). */
    fun <T> karticeNedavno(vnosi: List<T>, pocisti: T): List<T> = if (vnosi.isEmpty()) emptyList() else vnosi + pocisti
}
