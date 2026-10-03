package si.safeer.tv.os

private fun preveri(pogoj: Boolean, kaj: String) { if (!pogoj) throw AssertionError(kaj) }

fun main() {
    val k = KnjiznicaKroga
    // Opis za pomocnika: naslov, plakat samo po https, vrsta movie/series; zaseben nosi samo oznako.
    val o = k.opis("  Sintel ", "https://slike.primer/s.jpg", "movie", "stremio|movie|tt1|https://d.primer", false)
    preveri(o == KnjiznicaKroga.Opis("Sintel", "https://slike.primer/s.jpg", "movie", "stremio|movie|tt1|https://d.primer", false), "opis filma")
    preveri(k.opis("Epizoda", "http://192.168.0.2/p.jpg", "episode", "id", false) == KnjiznicaKroga.Opis("Epizoda", "", "series", "id", false), "plakat brez https odpade")
    preveri(k.opis("Nekaj", "", "glasba", "id", false).vrsta == "", "neznana vrsta")
    preveri(k.opis("Naslov, ki ne sme iz naprave", "https://x/p.jpg", "movie", "id", true) == KnjiznicaKroga.Opis("", "", "", "", true), "zaseben brez naslova in plakata")
    preveri(k.opis("x".repeat(500), "", "movie", "", false).naslov.length == 200, "dolg naslov")

    // Kar pomocnik sprejme od druge naprave.
    preveri(k.sprejet("", "https://x/p.jpg", "movie", "", false) == null, "brez naslova ni opisa")
    preveri(k.sprejet("Film", "ftp://x/p.jpg", "karkoli", "r", false) == KnjiznicaKroga.Opis("Film", "", "", "r", false), "sprejet ociscen")
    preveri(k.sprejet("Film", "https://x/p.jpg", "movie", "r", true) == KnjiznicaKroga.Opis("", "", "", "", true), "sprejet zaseben")

    // Zdruzevanje: zasebno ostane zasebno; prazna polja ne prepisejo znanih.
    val javen = KnjiznicaKroga.Opis("Film", "https://x/p.jpg", "movie", "r", false)
    val zaseben = KnjiznicaKroga.Opis("", "", "", "", true)
    preveri(k.zdruzi(null, javen) == javen, "prvi opis")
    preveri(k.zdruzi(javen, KnjiznicaKroga.Opis("Film 2", "", "", "", false)) == KnjiznicaKroga.Opis("Film 2", "https://x/p.jpg", "movie", "r", false), "dopolnitev")
    preveri(k.zdruzi(javen, zaseben).zaseben && k.zdruzi(javen, zaseben).naslov.isEmpty(), "postane zaseben")
    preveri(k.zdruzi(zaseben, javen) == zaseben, "ostane zaseben")

    // Kdo vnos dobi: javnega vsi, zasebnega samo narocnik.
    preveri(k.pove(javen, listOf("tv"), "telefon"), "javen vsem")
    preveri(k.pove(null, emptyList(), "telefon"), "brez opisa kot prej")
    preveri(!k.pove(zaseben, listOf("tablica"), "tv"), "zaseben ne gre drugim")
    preveri(k.pove(zaseben, listOf("tablica"), "tablica"), "zaseben gre narocniku")
    preveri(!k.pove(zaseben, listOf("tablica"), ""), "zaseben brez posiljatelja ne gre")

    // Polica: zasebnega ni nikoli; surovega imena torrenta (vnos brez naslova) ne pokazemo, razen ce naslov poznamo sami.
    preveri(k.zaPolico("Sintel", "https://x/p.jpg", false, null) == ("Sintel" to "https://x/p.jpg"), "naslov pomocnika")
    preveri(k.zaPolico("", "", false, null) == null, "brez naslova ni kartice")
    preveri(k.zaPolico("", "", false, javen) == ("Film" to "https://x/p.jpg"), "naslov te naprave")
    preveri(k.zaPolico("Sintel", "", false, javen) == ("Sintel" to "https://x/p.jpg"), "plakat te naprave")
    preveri(k.zaPolico("Sintel", "https://x/p.jpg", true, null) == null, "zaseben pri pomocniku")
    preveri(k.zaPolico("Sintel", "https://x/p.jpg", false, zaseben) == null, "zaseben na tej napravi")
    preveri(k.zaPolico("Sintel", "http://x/p.jpg", false, null) == ("Sintel" to ""), "plakat pomocnika brez https")

    // Isti film pri vec napravah: enkrat - koncan pred nedokoncanim, nato ta naprava, nato racunalnik.
    data class V(val hash: String, val naprava: String, val koncan: Boolean, val tukaj: Boolean = false, val racunalnik: Boolean = false)
    val vnosi = listOf(V("a", "telefon", false), V("a", "racunalnik", true, racunalnik = true), V("b", "tv", true), V("b", "jaz", true, tukaj = true),
        V("c", "telefon", true), V("c", "pc", true, racunalnik = true), V("", "x", true), V("", "y", true))
    val brez = k.brezDvojnikov(vnosi, { it.hash }, { it.koncan }, { it.tukaj }, { it.racunalnik })
    preveri(brez.map { it.naprava } == listOf("racunalnik", "jaz", "pc", "x", "y"), "brez dvojnikov: ${brez.map { it.naprava }}")
    println("KnjiznicaKrogaTest: OK")
}
