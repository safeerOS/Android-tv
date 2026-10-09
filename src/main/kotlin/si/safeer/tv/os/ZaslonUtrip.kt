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
     * Prvi odgovor seje pove se pot (`neposredno` ali `hub`) in [rokMs], koliko je trajala povezava s pripetim
     * potrdilom; v ostalih sta null in ju ni.
     *
     * Stevila so v obliki Locale.ROOT: slovenski jezik naprave ne sme narediti »59,8« (to ni vec JSON).
     */
    fun odgovorRtt(
        n: Long, t: Long, r: Long, b: Long,
        fps: Double, mbps: Double, dek: Long, zastoji: Int, izpusceno: Int,
        pot: String?, rokMs: Long?,
    ): String {
        val s = StringBuilder(192)
        s.append("{\"vrsta\":\"rtt\",\"n\":").append(n).append(",\"t\":").append(t)
            .append(",\"r\":").append(r).append(",\"b\":").append(b)
            .append(",\"fps\":").append(decimalno(fps, 1))
            .append(",\"mbps\":").append(decimalno(mbps, 2))
            .append(",\"dek\":").append(dek)
            .append(",\"zastoji\":").append(zastoji)
            .append(",\"izpusceno\":").append(izpusceno)
        if (pot != null) s.append(",\"pot\":").append(niz(pot))
        if (rokMs != null) s.append(",\"rok\":").append(rokMs)
        return s.append('}').toString()
    }

    /** Zakasnitev za »Podatke o povezavi«, npr. »104 ms«. */
    fun opisRtt(ms: Int): String = "$ms ms"

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
