package si.safeer.tv.os

fun main() {
    val p = TempoDodatka
    val min = 60_000L
    val t0 = 1_800_000_000_000L - 1_800_000_000_000L % min      // zacetek neke minute

    // --- Stevec zadnje ure -------------------------------------------------------------------------------------------
    val u = TempoDodatka.Ura()
    check(u.vsota(t0) == 0)
    repeat(10) { u.dodaj(t0 + it * 1_000L) }
    check(u.vsota(t0 + 30_000L) == 10)
    u.dodaj(t0 + 20 * min, 5)
    check(u.vsota(t0 + 20 * min) == 15)
    // Predal izpade natanko uro po zacetku svoje minute.
    check(u.vsota(t0 + 59 * min + 59_999L) == 15)
    check(u.vsota(t0 + 60 * min) == 5)
    check(u.vsota(t0 + 79 * min + 59_999L) == 5)
    check(u.vsota(t0 + 80 * min) == 0)
    // Dolg premor (naprava je spala): vse izpade.
    u.dodaj(t0 + 100 * min, 7)
    check(u.vsota(t0 + 500 * min) == 0)

    // Cez koliko bo vsota pod mejo.
    val v = TempoDodatka.Ura()
    v.dodaj(t0, 40); v.dodaj(t0 + 10 * min, 30); v.dodaj(t0 + 30 * min + 15_000L, 30)
    val zdaj = t0 + 30 * min + 20_000L
    check(v.vsota(zdaj) == 100)
    check(v.cezKolikoDo(100, zdaj) == 0L && v.cezKolikoDo(500, zdaj) == 0L)
    check(v.cezKolikoDo(60, zdaj) == t0 + 60 * min - zdaj)          // izpade prvih 40
    check(v.cezKolikoDo(59, zdaj) == t0 + 70 * min - zdaj)          // se naslednjih 30
    check(v.cezKolikoDo(0, zdaj) == t0 + 90 * min - zdaj)           // vse
    // Napoved drzi: tik pred tem je vsota se nad mejo, ob napovedanem casu ni vec.
    check(v.vsota(t0 + 60 * min - 1) == 100 && v.vsota(t0 + 60 * min) == 60)

    // Shramba: zapis in branje; pokvarjen zapis da prazen stevec.
    val w = TempoDodatka.Ura(); w.dodaj(t0, 3); w.dodaj(t0 + 5 * min, 4)
    val branje = TempoDodatka.Ura.izNiza(w.vNiz())
    check(branje.vsota(t0 + 5 * min) == 7 && branje.vsota(t0 + 60 * min) == 4 && branje.vsota(t0 + 65 * min) == 0)
    for (slab in listOf(null, "", "abc", "1;2", "x;" + List(60) { 0 }.joinToString(","), "5;" + List(59) { 0 }.joinToString(","),
        "5;" + List(60) { -1 }.joinToString(","), "5;" + List(60) { "a" }.joinToString(","), "-3;" + List(60) { 1 }.joinToString(",")))
        check(TempoDodatka.Ura.izNiza(slab).vsota(t0) == 0) { "slab zapis: $slab" }
    // Zapis iz »prihodnosti« (ura naprave je sla nazaj): ne obvisi za vedno.
    val prihodnost = TempoDodatka.Ura.izNiza("${(t0 + 5_000 * min) / min};" + List(60) { 2 }.joinToString(","))
    check(prihodnost.vsota(t0) == 0)
    prihodnost.dodaj(t0); check(prihodnost.vsota(t0) == 1)
    // Majhen popravek ure nazaj ne brise nicesar.
    val nazaj = TempoDodatka.Ura(); nazaj.dodaj(t0 + 10 * min, 6)
    check(nazaj.vsota(t0 + 8 * min) == 6)
    nazaj.dodaj(t0 + 8 * min); check(nazaj.vsota(t0 + 10 * min) == 7)

    // --- Meja na uro -------------------------------------------------------------------------------------------------
    val s = TempoDodatka.Stanje()
    check(s.naUro == p.NA_URO && p.smeVOzadju(s, 0) && p.smeVOzadju(s, p.NA_URO - 1) && !p.smeVOzadju(s, p.NA_URO))
    // Na zalogo le do tretjine.
    check(p.smeNaZalogo(s, p.NA_URO / 3 - 1) && !p.smeNaZalogo(s, p.NA_URO / 3) && !p.smeNaZalogo(s, p.NA_URO))
    // Omejitev: polovica tega, kar smo v zadnji uri poslali, a nikoli vec kot polovica stare meje in ne pod najmanjso.
    check(p.poOmejitvi(s, 140, t0) == TempoDodatka.Stanje(70, t0, t0))
    check(p.poOmejitvi(s, 400, t0).naUro == p.NA_URO / 2)
    check(p.poOmejitvi(s, 10, t0).naUro == p.NA_URO_NAJMANJ)
    check(p.poOmejitvi(s, -5, t0).naUro == p.NA_URO_NAJMANJ)
    check(p.poOmejitvi(TempoDodatka.Stanje(70, 1, 1), 70, t0).naUro == 35)
    check(p.poOmejitvi(TempoDodatka.Stanje(p.NA_URO_NAJMANJ, 1, 1), 500, t0).naUro == p.NA_URO_NAJMANJ)
    // Meja se nikoli ne dvigne zaradi omejitve.
    for (n in listOf(30, 31, 60, 100, 150)) for (k in listOf(0, 20, 60, 149, 1000)) check(p.poOmejitvi(TempoDodatka.Stanje(n, 1, 1), k, t0).naUro in p.NA_URO_NAJMANJ..n)

    // Okrevanje: cetrtino na dan brez omejitve (najmanj 5), do privzete; brez omejitve v preteklosti se ne spremeni nic.
    val dan = p.OKREVANJE_MS
    val omejen = p.poOmejitvi(s, 80, t0)
    check(omejen.naUro == 40)
    check(p.okrevaj(omejen, t0 + dan - 1) == omejen)
    check(p.okrevaj(omejen, t0 + dan) == TempoDodatka.Stanje(50, t0, t0 + dan))
    check(p.okrevaj(omejen, t0 + 2 * dan + 5) == TempoDodatka.Stanje(62, t0, t0 + 2 * dan))
    // Po korakih ali naenkrat je isto.
    var poKorakih = omejen
    for (d in 1..10) poKorakih = p.okrevaj(poKorakih, t0 + d * dan)
    check(poKorakih.naUro == p.okrevaj(omejen, t0 + 10 * dan).naUro && poKorakih.naUro == p.NA_URO)
    check(p.okrevaj(omejen, t0 + 400 * dan).naUro == p.NA_URO)
    check(p.okrevaj(s, t0 + 50 * dan) == s)
    check(p.okrevaj(omejen, t0 - dan) == omejen)                      // ura naprave nazaj
    check(p.okrevaj(TempoDodatka.Stanje(p.NA_URO_NAJMANJ, t0, t0), t0 + dan).naUro == p.NA_URO_NAJMANJ + 7)
    // Shramba.
    check(TempoDodatka.Stanje.izNiza(omejen.vNiz()) == omejen)
    for (slab in listOf(null, "", "x", "1;2", "a;1;2", "40;b;2", "40;1;c")) check(TempoDodatka.Stanje.izNiza(slab) == TempoDodatka.Stanje())
    check(TempoDodatka.Stanje.izNiza("5;-1;-1") == TempoDodatka.Stanje(p.NA_URO_NAJMANJ, 0, 0) && TempoDodatka.Stanje.izNiza("9999;1;2") == TempoDodatka.Stanje(p.NA_URO, 1, 2))

    // Porabljen proracun: pocakamo, da vsota pade na tri cetrtine meje - najmanj minuto.
    val poln = TempoDodatka.Ura(); poln.dodaj(t0, 50); poln.dodaj(t0 + 20 * min, 60); poln.dodaj(t0 + 40 * min, 40)
    val ob = t0 + 40 * min + 30_000L
    check(poln.vsota(ob) == 150 && !p.smeVOzadju(s, poln.vsota(ob)))
    check(p.cakajNaProracun(s, poln, ob) == t0 + 60 * min - ob)       // izpade prvih 50: 100 <= 112
    check(p.cakajNaProracun(TempoDodatka.Stanje(40, 1, 1), poln, ob) == t0 + 100 * min - ob)   // do 30: izpasti mora vse
    val komaj = TempoDodatka.Ura(); komaj.dodaj(t0, 150)
    check(p.cakajNaProracun(s, komaj, t0 + 59 * min + 50_000L) == p.CAKAJ_NAJMANJ_MS)

    // --- Kar pove dodatek sam ----------------------------------------------------------------------------------------
    check(p.cakajPoGlavah(emptyMap(), t0) == null)
    check(p.cakajPoGlavah(mapOf("retry-after" to "600"), t0) == 600_000L)
    check(p.cakajPoGlavah(mapOf("retry-after" to " 5 "), t0) == p.CAKAJ_NAJMANJ_MS)
    check(p.cakajPoGlavah(mapOf("retry-after" to "86400"), t0) == p.CAKAJ_NAJVEC_MS)
    check(p.cakajPoGlavah(mapOf("retry-after" to "0"), t0) == null && p.cakajPoGlavah(mapOf("retry-after" to "kmalu"), t0) == null)
    // Datum HTTP: petek, 15. 1. 2027 08:00:00 GMT je 1 800 000 000 s.
    check(p.cakajPoGlavah(mapOf("retry-after" to "Fri, 15 Jan 2027 08:10:00 GMT"), 1_800_000_000_000L) == 600_000L)
    check(p.cakajPoGlavah(mapOf("retry-after" to "Fri, 15 Jan 2027 07:00:00 GMT"), 1_800_000_000_000L) == null)
    // RateLimit-Reset: sekunde do ponastavitve; X-RateLimit-Reset: tudi cas Unix (sekunde ali milisekunde).
    check(p.cakajPoGlavah(mapOf("ratelimit-reset" to "1800"), t0) == 1_800_000L)
    check(p.cakajPoGlavah(mapOf("x-ratelimit-reset" to "1800000900"), 1_800_000_000_000L) == 900_000L)
    check(p.cakajPoGlavah(mapOf("x-ratelimit-reset" to "1800000900000"), 1_800_000_000_000L) == 900_000L)
    check(p.cakajPoGlavah(mapOf("x-ratelimit-reset" to "1800000900.5"), 1_800_000_000_000L) == 900_000L)
    check(p.cakajPoGlavah(mapOf("x-ratelimit-reset" to "1700000000"), 1_800_000_000_000L) == null)      // ze mimo
    // Retry-After ima prednost.
    check(p.cakajPoGlavah(mapOf("retry-after" to "120", "ratelimit-reset" to "3000"), t0) == 120_000L)

    // Dnevnik: samo glave o omejitvi, brez cesarkoli drugega (piskotki, streznik, vsebina).
    val glave = mapOf("retry-after" to "600", "x-ratelimit-limit" to "300", "ratelimit-policy" to "300;w=3600", "set-cookie" to "skrivnost=1",
        "server" to "nekaj", "cf-mitigated" to "challenge", "x-ratelimit-remaining" to "0<script>", "content-type" to "text/html")
    check(p.opisGlav(glave) == "cf-mitigated=challenge, ratelimit-policy=300;w=3600, retry-after=600, x-ratelimit-limit=300, x-ratelimit-remaining=0?script?")
    check(p.opisGlav(mapOf("content-type" to "a")) == "" && p.opisGlav(mapOf("retry-after" to "x".repeat(100))).length == "retry-after=".length + 40)
    println("TempoDodatkaTest: OK")
}
