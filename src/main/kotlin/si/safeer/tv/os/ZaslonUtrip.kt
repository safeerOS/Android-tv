package si.safeer.tv.os

import java.util.Locale

/**
 * Utrip zaslona racunalnika: meritev zakasnitve med sejo. Samo opazovanje - slika, poti in roki ostanejo, kot so.
 *
 * Racunalnik, ki je v glavi toka potrdil `"rtt": true` (gledalec je `rtt` ponudil v `caps`, [ZaslonKodek.zmoznosti]),
 * vsake pol sekunde poslje obvestilo `{"rtt":{"n":17,"t":123456,"z":104}}`. Gledalec ga vrne po kanalu za vnos kot eno
 * vrstico JSON ([odgovorRtt]). Ur ni treba uskladiti: racunalnik meri na svoji uri, gledalec `n` in `t` samo vrne in
 * doda svojo uro `r` ter vse prejete bajte `b` - iz razlik med zaporednima odgovoroma racunalnik izracuna, koliko
 * pride do gledalca. `z` je zadnja zakasnitev tja in nazaj (ms), za »Podatke o povezavi«.
 *
 * Brez Androida in brez org.json, da se da preizkusiti na JVM (tests/ZaslonUtripTest.kt).
 */
object ZaslonUtrip {
    /**
     * Odgovor na utrip: ena vrstica JSON brez znaka za novo vrstico (tega doda posiljanje vnosa). [n] in [t] gresta
     * nazaj nespremenjena, [r] je ura gledalca (ms), [b] vsi prejeti bajti seje skupaj z glavami okvirjev. Ostalo je
     * zadnja sekunda na gledalcu: slike na sekundo, megabiti na sekundo, cas v dekoderju, zastoji in izpuscene enote.
     * [zastojMs], [zastoji100], [zastoji250] in [zastojiKljucna] (skupno trajanje zastojev v ms, zastoji nad 100 ms in
     * nad 250 ms, zastoji, ki jih je koncala kljucna slika) gredo samo, ce niso null - starejsi racunalnik jih prezre.
     * Prvi odgovor seje pove se pot (`neposredno` ali `hub`) in [rokMs], koliko je trajala povezava s pripetim
     * potrdilom; v ostalih sta null in ju ni.
     *
     * Stevila so v obliki Locale.ROOT: slovenski jezik naprave ne sme narediti »59,8« (to ni vec JSON).
     */
    fun odgovorRtt(
        n: Long, t: Long, r: Long, b: Long,
        fps: Double, mbps: Double, dek: Long, zastoji: Int, izpusceno: Int,
        pot: String?, rokMs: Long?,
        zastojMs: Long? = null, zastoji100: Int? = null, zastoji250: Int? = null, zastojiKljucna: Int? = null,
    ): String {
        val s = StringBuilder(192)
        s.append("{\"vrsta\":\"rtt\",\"n\":").append(n).append(",\"t\":").append(t)
            .append(",\"r\":").append(r).append(",\"b\":").append(b)
            .append(",\"fps\":").append(decimalno(fps, 1))
            .append(",\"mbps\":").append(decimalno(mbps, 2))
            .append(",\"dek\":").append(dek)
            .append(",\"zastoji\":").append(zastoji)
            .append(",\"izpusceno\":").append(izpusceno)
        if (zastojMs != null) s.append(",\"zastoj_ms\":").append(zastojMs)
        if (zastoji100 != null) s.append(",\"zastoji_100\":").append(zastoji100)
        if (zastoji250 != null) s.append(",\"zastoji_250\":").append(zastoji250)
        if (zastojiKljucna != null) s.append(",\"zastoji_kljucna\":").append(zastojiKljucna)
        if (pot != null) s.append(",\"pot\":").append(niz(pot))
        if (rokMs != null) s.append(",\"rok\":").append(rokMs)
        return s.append('}').toString()
    }

    /** Zakasnitev za »Podatke o povezavi«, npr. »104 ms«. */
    fun opisRtt(ms: Int): String = "$ms ms"

    /** Dolzina obdobja statistike gledalca (ms). */
    const val OBDOBJE_MS = 1000L

    /** Presledek med zaporednima slikama, ki ga oko pri 60 slikah na sekundo ze zazna kot zatik (ms). */
    const val ZASTOJ_MS = 50L

    /** Meji razredov zastojev (ms): daljsi zastoji, ki jih [ZASTOJ_MS] steje enako kot kratke. */
    const val ZASTOJ_100_MS = 100L
    const val ZASTOJ_250_MS = 250L

    /**
     * Rok tisine toka, kadar racunalnik poslje utrip. Pred zamrznjeno sliko varuje samo rok branja: ce 10 s ne pride
     * noben bajt, seja pade, gledalec se poveze znova in racunalnik zazene nov zajem. Utrip pa pride vsake pol sekunde
     * neodvisno od slike - tudi kadar zajem ali kodirnik na racunalniku obtici - in bi rok branja podaljseval v
     * nedogled. Zato utrip ne steje: zivljenje toka je vsak drug okvir (slika, zvok, druga obvestila), natanko kot pred
     * utripom. Ko razen utripov vec kot [rokMs] ne pride nic, [utrip] vrne true in seja pade kot ob roku branja.
     */
    class TisinaToka(private val rokMs: Long, zacetekMs: Long) {
        private var zadnji = zacetekMs

        /** Prisel je okvir, ki ni utrip racunalnika. */
        fun tok(zdajMs: Long) { zadnji = zdajMs }

        /** Prisel je utrip racunalnika: true, ce razen utripov ze vec kot [rokMs] ni prislo nic. */
        fun utrip(zdajMs: Long): Boolean = zdajMs - zadnji > rokMs
    }

    /** Ena sekunda na gledalcu: za dnevnik, »Podatke o povezavi« in odgovor na utrip. */
    data class Sekunda(val slik: Int, val naSekundo: Double, val megabitov: Double, val dekoderMs: Long,
                       val zvok: Boolean, val zastojev: Int, val izpusceno: Int,
                       /** Skupno trajanje zastojev (cel presledek, ms), ki so se koncali v tej sekundi. */
                       val zastojMs: Long = 0, val zastojev100: Int = 0, val zastojev250: Int = 0,
                       /** Zastoji, ki jih je koncala kljucna slika. */
                       val zastojevKljucna: Int = 0)

    /**
     * Stevci gledalca za tekoco sekundo. Obdobje zapre prvi okvir po [OBDOBJE_MS] - slika ali utrip racunalnika. Ce bi
     * ga zapirala samo slika, bi odgovori na utrip med zastojem slike ponavljali zadnjo sekundo pred njim (60 slik/s,
     * brez zastojev): gledalec bi bil »zdrav« ravno takrat, ko slika stoji. Tako ima zastoj 0 slik na sekundo, presledek
     * pa steje kot zastoj v sekundi, ko pride naslednja slika.
     */
    class Obdobje(zacetekMs: Long) {
        var bajtov = 0L
        var izpusceno = 0
        var zvok = false
        private var slik = 0
        private var zastojev = 0
        private var zastojMs = 0L
        private var zastojev100 = 0
        private var zastojev250 = 0
        private var zastojevKljucna = 0
        private var zadnjaSlika = 0L
        private var od = zacetekMs

        /** Zadnja zaprta sekunda ali null, dokler ni minila prva: gre z odgovorom na utrip. */
        var zadnja: Sekunda? = null
            private set

        /**
         * Dekoder je vrnil sliko ob [zdajMs]; presledek, daljsi od [ZASTOJ_MS], je zastoj. Steje v sekundi, ko pride
         * slika, ki ga konca, z vsem trajanjem. [kljucna]: to sliko je dekoder dobil kot kljucno.
         */
        fun slika(zdajMs: Long, kljucna: Boolean = false) {
            slik++
            val presledek = if (zadnjaSlika != 0L) zdajMs - zadnjaSlika else 0L
            if (presledek > ZASTOJ_MS) {
                zastojev++
                zastojMs += presledek
                if (presledek > ZASTOJ_100_MS) zastojev100++
                if (presledek > ZASTOJ_250_MS) zastojev250++
                if (kljucna) zastojevKljucna++
            }
            zadnjaSlika = zdajMs
        }

        /** Ce je od zacetka obdobja minila vsaj sekunda, jo strne, zacne novo obdobje in jo vrne; sicer null. */
        fun zapri(zdajMs: Long, dekoderMs: Long): Sekunda? {
            if (zdajMs - od < OBDOBJE_MS) return null
            val sekunde = (zdajMs - od) / 1000.0
            val s = Sekunda(slik, slik / sekunde, bajtov * 8 / 1e6 / sekunde, dekoderMs, zvok, zastojev, izpusceno,
                zastojMs, zastojev100, zastojev250, zastojevKljucna)
            slik = 0; bajtov = 0; zastojev = 0; izpusceno = 0; zvok = false; od = zdajMs
            zastojMs = 0; zastojev100 = 0; zastojev250 = 0; zastojevKljucna = 0
            zadnja = s
            return s
        }
    }

    /** Decimalno stevilo z [mest] decimalkami, vedno s piko; NaN ali neskoncno (JSON ju ne pozna) je 0. */
    private fun decimalno(x: Double, mest: Int): String =
        if (x.isNaN() || x.isInfinite()) "0" else String.format(Locale.ROOT, "%.${mest}f", x)

    /** Niz JSON v narekovajih: narekovaj, posevnica in kontrolni znaki ne smejo pokvariti vrstice. */
    private fun niz(v: String): String {
        val s = StringBuilder(v.length + 2).append('"')
        for (c in v) {
            when {
                c == '"' -> s.append("\\\"")
                c == '\\' -> s.append("\\\\")
                c < ' ' -> s.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                else -> s.append(c)
            }
        }
        return s.append('"').toString()
    }
}
