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
    } finally {
        Locale.setDefault(privzeti)
    }
    println("ZaslonUtripTest: OK")
}
