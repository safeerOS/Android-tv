package si.safeer.tv.os

import java.util.Locale

/**
 * Odgovor gledalca na utrip racunalnika (meritev zakasnitve med sejo zaslona): ena vrstica JSON, ki jo racunalnik
 * prebere v core/link_zaslon.py (_beri_vnos, vrsta "rtt"). Stevila morajo biti v obliki JSON tudi na napravi v
 * slovenscini, kjer bi navadno oblikovanje napisalo »59,8«.
 */
fun main() {
    val privzeti = Locale.getDefault()
    try {
        Locale.setDefault(Locale.forLanguageTag("sl"))

        // Ena vrstica (novo vrstico doda posiljanje vnosa), vrsta "rtt" spredaj.
        val o = ZaslonUtrip.odgovorRtt(17, 123456, 987654, 12345678, 59.8, 4.12, 12, 0, 0, null, null)
        check(!o.contains('\n') && !o.contains('\r')) { "brez nove vrstice: $o" }
        check(o.startsWith("{\"vrsta\":\"rtt\"")) { "vrsta spredaj: $o" }
        check(o.endsWith("}")) { "cel objekt: $o" }
        check(o == "{\"vrsta\":\"rtt\",\"n\":17,\"t\":123456,\"r\":987654,\"b\":12345678,\"fps\":59.8,\"mbps\":4.12," +
            "\"dek\":12,\"zastoji\":0,\"izpusceno\":0}") { "oblika odgovora: $o" }

        // n, t, r in b gredo nazaj natanko, tudi nad 2^31 (ura racunalnika, bajti dolge seje).
        val velik = (1L shl 31) + 5
        val ogromen = 9_007_199_254_740_993L
        val v = ZaslonUtrip.odgovorRtt(velik, ogromen, velik + 1, ogromen - 1, 0.0, 0.0, 0, 0, 0, null, null)
        check(v.contains("\"n\":$velik,")) { "n nad 2^31: $v" }
        check(v.contains("\"t\":$ogromen,")) { "t nad 2^53: $v" }
        check(v.contains("\"r\":${velik + 1},")) { "r nad 2^31: $v" }
        check(v.contains("\"b\":${ogromen - 1},")) { "b nad 2^53: $v" }

        // Slovenscina pise decimalno vejico (to preverimo, sicer preizkus ne bi nic povedal); v odgovoru mora biti pika.
        check(String.format("%.1f", 59.8) == "59,8") { "privzeti jezik ni slovenscina" }
        check(o.contains("\"fps\":59.8,") && o.contains("\"mbps\":4.12,")) { "decimalna pika: $o" }
        check(!Regex("\\d,\\d").containsMatchIn(o)) { "nikjer decimalne vejice: $o" }
        val z = ZaslonUtrip.odgovorRtt(1, 2, 3, 4, 1234.56, 0.005, 7, 3, 2, null, null)
        check(z.contains("\"fps\":1234.6,") && z.contains("\"mbps\":0.01,")) { "zaokrozevanje: $z" }
        check(z.contains("\"dek\":7,\"zastoji\":3,\"izpusceno\":2}")) { "statistika gledalca: $z" }

        // NaN in neskoncno nista JSON: gresta kot 0.
        val n = ZaslonUtrip.odgovorRtt(1, 2, 3, 4, Double.NaN, Double.POSITIVE_INFINITY, 0, 0, 0, null, null)
        check(n.contains("\"fps\":0,") && n.contains("\"mbps\":0,")) { "NaN/neskoncno: $n" }

        // Prvi odgovor pove se pot in cas povezovanja; ostali ne.
        check(!o.contains("\"pot\"") && !o.contains("\"rok\"")) { "brez poti in roka: $o" }
        val prvi = ZaslonUtrip.odgovorRtt(1, 2, 3, 4, 60.0, 5.0, 9, 0, 0, "neposredno", 42)
        check(prvi.endsWith(",\"pot\":\"neposredno\",\"rok\":42}")) { "prvi odgovor: $prvi" }
        check(ZaslonUtrip.odgovorRtt(1, 2, 3, 4, 0.0, 0.0, 0, 0, 0, "hub", null).endsWith(",\"pot\":\"hub\"}")) { "pot brez roka" }
        // Pot ne more pokvariti vrstice (narekovaj, posevnica, nova vrstica).
        val cudna = ZaslonUtrip.odgovorRtt(1, 2, 3, 4, 0.0, 0.0, 0, 0, 0, "a\"b\\c\nd", null)
        check(cudna.endsWith(",\"pot\":\"a\\\"b\\\\c\\u000ad\"}") && !cudna.contains('\n')) { "ubezni znaki: $cudna" }

        // Zakasnitev za »Podatke o povezavi«.
        check(ZaslonUtrip.opisRtt(104) == "104 ms") { "opis: ${ZaslonUtrip.opisRtt(104)}" }
        check(ZaslonUtrip.opisRtt(1234) == "1234 ms") { "brez locila tisocic: ${ZaslonUtrip.opisRtt(1234)}" }

        tisinaToka()
        obdobje()
        trajanjeZastojev()
    } finally {
        Locale.setDefault(privzeti)
    }
    println("ZaslonUtripTest: OK")
}

/** Okvir toka, kot ga vidi crpalka gledalca (ZaslonOdjemalec.crpaj): cas prihoda in ali je utrip racunalnika. */
private data class Okvir(val ms: Long, val utrip: Boolean)

/**
 * Kdaj seja pade zaradi tisine, kot v ZaslonOdjemalec.crpaj: utrip vprasa [ZaslonUtrip.TisinaToka.utrip], vsak drug
 * okvir je zivljenje toka. Vrne cas padca ali null, ce seja tece do konca okvirjev.
 */
private fun padec(okvirji: List<Okvir>, zacetekMs: Long = 0L): Long? {
    val t = ZaslonUtrip.TisinaToka(10_000, zacetekMs)
    for (o in okvirji.sortedBy { it.ms }) {
        if (!o.utrip) t.tok(o.ms) else if (t.utrip(o.ms)) return o.ms
    }
    return null
}

private fun utripi(odMs: Long, doMs: Long): List<Okvir> = (odMs..doMs step 500).map { Okvir(it, true) }
private fun tok(odMs: Long, doMs: Long, korakMs: Long): List<Okvir> = (odMs..doMs step korakMs).map { Okvir(it, false) }

/**
 * Edina straza pred zamrznjeno sliko je rok tisine (10 s brez enega bajta). Utrip racunalnika pride vsake pol sekunde
 * neodvisno od slike in ga ne sme podaljsevati: ko slika in zvok obstaneta (zajem ali kodirnik obtici), mora seja pasti
 * kot pred utripom, ne pa kazati zamrznjene slike, dokler je uporabnik sam ne zapusti.
 */
private fun tisinaToka() {
    // Slika tece 3 s, nato obstane; racunalnik naprej poslje samo utrip. Brez utripa bi rok branja padel ob 13,0 s.
    val obstala = tok(0, 3000, 20) + utripi(500, 60_000)
    check(padec(obstala) == 13_500L) { "utrip ne podaljsuje roka: ${padec(obstala)}" }
    // Kodirnik se odpre, a ne vrne nicesar: seja pade po roku od zacetka crpanja, kot prej.
    check(padec(utripi(500, 60_000), zacetekMs = 0) == 10_500L) { "brez slike od zacetka: ${padec(utripi(500, 60_000))}" }
    // Brez zvoka in s sliko: utrip in slika skupaj - seja tece.
    check(padec(tok(0, 60_000, 16) + utripi(500, 60_000)) == null) { "slika tece" }
    // Mirna slika z redkimi okvirji (manj kot rok narazen) ali samo zvok: tece kot prej.
    check(padec(tok(0, 60_000, 9_900) + utripi(500, 60_000)) == null) { "redki okvirji" }
    // Druga obvestila (medij, program) so zivljenje toka kot prej; utrip med njimi ni.
    check(padec(tok(0, 60_000, 5_000) + utripi(250, 60_000)) == null) { "obvestila" }
    // Tocno rok brez toka se ne steje kot tisina (rok branja pade sele po njem).
    check(padec(listOf(Okvir(0, false), Okvir(10_000, true), Okvir(10_001, true))) == 10_001L) { "meja roka" }
}

/**
 * Sekunda gledalca: zapre jo prvi okvir po sekundi - slika ali utrip. Med zastojem slike gre z odgovorom na utrip
 * 0 slik/s, ne zadnja sekunda pred zastojem.
 */
private fun obdobje() {
    val o = ZaslonUtrip.Obdobje(0)
    check(o.zadnja == null) { "pred prvo sekundo ni nicesar" }
    // 60 slik v prvi sekundi, 125 000 bajtov: 1 Mb/s, brez zastojev.
    for (i in 0 until 60) o.slika(i * 1000L / 60)
    o.bajtov += 125_000
    check(o.zapri(999, 12) == null) { "sekunda se ni minila" }
    val prva = o.zapri(1000, 12)
    check(prva != null && prva.slik == 60 && prva.zastojev == 0 && prva.dekoderMs == 12L) { "prva sekunda: $prva" }
    check(Math.abs(prva.naSekundo - 60.0) < 1e-9 && Math.abs(prva.megabitov - 1.0) < 1e-9) { "hitrost: $prva" }
    check(o.zadnja == prva) { "zadnja je prva" }

    // Slika obstane ob 1,0 s za 5 s; racunalnik poslje utrip vsake pol sekunde (crpaj ob utripu poklice zapri).
    val odmevi = mutableListOf<ZaslonUtrip.Sekunda?>()
    for (t in 1500L..6000L step 500) { o.zapri(t, 12); odmevi.add(o.zadnja) }
    check(odmevi[0] == prva) { "ob 1,5 s se velja prva sekunda" }
    for ((i, s) in odmevi.withIndex().drop(1)) {
        check(s != null && s.slik == 0 && s.naSekundo == 0.0 && s.zastojev == 0) { "med zastojem 0 slik/s ($i): $s" }
    }
    val odmev = ZaslonUtrip.odgovorRtt(9, 1, 2, 3, odmevi.last()!!.naSekundo, odmevi.last()!!.megabitov,
        odmevi.last()!!.dekoderMs, odmevi.last()!!.zastojev, odmevi.last()!!.izpusceno, null, null)
    check(odmev.contains("\"fps\":0.0,") && odmev.contains("\"zastoji\":0,")) { "odgovor med zastojem: $odmev" }

    // Slika se vrne ob 6,2 s: presledek je en zastoj v sekundi, ko pride naslednja slika.
    for (i in 0 until 48) o.slika(6200 + i * 1000L / 60)
    o.izpusceno++
    val po = o.zapri(7000, 15)
    check(po != null && po.slik == 48 && po.zastojev == 1 && po.izpusceno == 1) { "po zastoju: $po" }
    check(o.zapri(7400, 15) == null && o.zadnja == po) { "nova sekunda se tece" }
    // Zvok v sekundi se zapise in nato pobrise.
    o.zvok = true
    check(o.zapri(8000, 15)?.zvok == true && o.zapri(9000, 15)?.zvok == false) { "zvok po sekundah" }
}

/**
 * Trajanje in razredi zastojev: vsak presledek nad 50 ms steje enkrat (kot prej) in z vsem trajanjem; nad 100 ms in
 * nad 250 ms posebej; zastoj, ki ga konca kljucna slika, posebej. Odgovor na utrip nosi nova polja samo, ce so podana.
 */
private fun trajanjeZastojev() {
    val o = ZaslonUtrip.Obdobje(0)
    var t = 0L
    fun slike(n: Int) { repeat(n) { o.slika(t); t += 17 } }
    slike(10)                                  // brez zastoja
    t += 33; o.slika(t, kljucna = true)        // presledek 50 ms (17 + 33): ni zastoj, tudi ob kljucni sliki ne
    t += 60; o.slika(t)                        // 60 ms: zastoj
    t += 120; o.slika(t, kljucna = true)       // 120 ms ob kljucni sliki: zastoj nad 100
    t += 300; o.slika(t)                       // 300 ms: nad 100 in nad 250
    val s = o.zapri(1000, 10)!!
    check(s.zastojev == 3) { "zastojev: $s" }
    check(s.zastojMs == 60L + 120 + 300) { "trajanje: $s" }
    check(s.zastojev100 == 2 && s.zastojev250 == 1) { "razredi: $s" }
    check(s.zastojevKljucna == 1) { "ob kljucni: $s" }
    // Nova sekunda zacne pri nic.
    t += 17; o.slika(t)
    val n = o.zapri(2000, 10)!!
    check(n.zastojev == 0 && n.zastojMs == 0L && n.zastojev100 == 0 && n.zastojev250 == 0 && n.zastojevKljucna == 0) {
        "nova sekunda: $n"
    }

    // Odgovor: nova polja za starimi, samo ce so podana (starejsi klic da natanko isto vrstico kot prej).
    val star = ZaslonUtrip.odgovorRtt(1, 2, 3, 4, 59.8, 1.0, 12, 3, 0, null, null)
    check(star.endsWith("\"zastoji\":3,\"izpusceno\":0}")) { "brez novih polj: $star" }
    val nov = ZaslonUtrip.odgovorRtt(1, 2, 3, 4, 59.8, 1.0, 12, s.zastojev, 0, null, null,
        zastojMs = s.zastojMs, zastoji100 = s.zastojev100, zastoji250 = s.zastojev250, zastojiKljucna = s.zastojevKljucna)
    check(nov.endsWith("\"zastoji\":3,\"izpusceno\":0,\"zastoj_ms\":480,\"zastoji_100\":2,\"zastoji_250\":1," +
        "\"zastoji_kljucna\":1}")) { "nova polja: $nov" }
    val sPotjo = ZaslonUtrip.odgovorRtt(1, 2, 3, 4, 0.0, 0.0, 0, 0, 0, "hub", 87, zastojMs = 0, zastoji100 = 0,
        zastoji250 = 0, zastojiKljucna = 0)
    check(sPotjo.endsWith("\"izpusceno\":0,\"zastoj_ms\":0,\"zastoji_100\":0,\"zastoji_250\":0,\"zastoji_kljucna\":0," +
        "\"pot\":\"hub\",\"rok\":87}")) { "s potjo: $sPotjo" }
}
