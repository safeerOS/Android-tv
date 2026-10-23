package si.safeer.tv.os

/**
 * Cista pravila za predpomnilnik seznama aplikacij (brez Androida, preizkus v tests/AplikacijePredpomnilnikTest.kt).
 * Na disk gre samo paket, ime in razred dejavnosti - ikone so velike in se nalagajo lenobno.
 */
object AplikacijePredpomnilnik {
    data class Zapis(val paket: String, val ime: String, val razred: String)

    /** Prva vrstica datoteke; ob spremembi oblike zapisa povecaj stevilko, stara datoteka se zavrze. */
    private const val GLAVA = "SAFEER-APLIKACIJE 1"

    /** Ena vrstica na aplikacijo: paket, razred, ime (ime je zadnje, ker je edino prosto besedilo). */
    fun kodiraj(zapisi: List<Zapis>): String {
        val sb = StringBuilder(GLAVA).append('\n')
        for (z in zapisi) {
            if (z.paket.isBlank() || z.razred.isBlank()) continue
            sb.append(cisto(z.paket)).append('\t').append(cisto(z.razred)).append('\t').append(cisto(z.ime)).append('\n')
        }
        return sb.toString()
    }

    /** null = datoteke ni, je od druge oblike ali pokvarjena; napacne vrstice preskocimo, ostale obdrzimo. */
    fun razcleni(besedilo: String?): List<Zapis>? {
        if (besedilo == null) return null
        val vrstice = besedilo.split('\n')
        if (vrstice.firstOrNull() != GLAVA) return null
        val rezultat = ArrayList<Zapis>()
        for (v in vrstice.drop(1)) {
            if (v.isBlank()) continue
            val d = v.split('\t', limit = 3)
            if (d.size < 3 || d[0].isBlank() || d[1].isBlank()) continue
            rezultat.add(Zapis(d[0], d[2].ifBlank { d[0] }, d[1]))
        }
        return rezultat
    }

    /** Svez seznam zamenja prikazanega samo, ce se res razlikuje (po paketu, razredu in imenu, vrstni red je vazen). */
    fun enako(a: List<Zapis>, b: List<Zapis>): Boolean = a == b

    /** Seznam brez enega paketa (odstranitev aplikacije), ne da bi brali vse znova. */
    fun brez(zapisi: List<Zapis>, paket: String): List<Zapis> = zapisi.filter { it.paket != paket }

    private fun cisto(s: String): String = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    /**
     * Majhen predpomnilnik: ob polnem vrze ven najdlje neuporabljeno. Dostop je zaklenjen, ker ikone
     * nalagamo v ozadju, kartice pa rise glavna nit.
     */
    class MajhenLru<K : Any, V : Any>(private val najvec: Int) {
        private val zemljevid = object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > najvec
        }

        @Synchronized fun daj(k: K): V? = zemljevid[k]
        @Synchronized fun dodaj(k: K, v: V) { zemljevid[k] = v }
        @Synchronized fun odstrani(k: K) { zemljevid.remove(k) }
        @Synchronized fun pocisti() { zemljevid.clear() }
        @Synchronized fun velikost(): Int = zemljevid.size
    }
}
