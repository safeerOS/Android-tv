package si.safeer.tv.os

/**
 * Pravila Safeer OS brez televizorja: varne mere ikon, vrstica Nadaljuj in njene ikone,
 * kartica "Zaslon racunalnika". Pade, ce kdo spremeni vedenje, ki ga uporabnik vidi.
 */

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    preveri("$opis (pričakovano=$pricakovano, dobljeno=$dobljeno)", pricakovano == dobljeno)

private data class V(val kljuc: String, val ime: String = kljuc)

/** Naloga 81: iskanje v dodatku samo za iskanje (YouTubio) - rok, ime izvora, povezava YouTube kot posnetek. */
private fun preizkusIskanjaYoutubio() {
    println("\n== naloga 81: YouTubio v iskanju ==")
    preveriEnako("vsi gotovi: konec takoj", true, OsPravila.iskanjeKoncano(true, true, 500))
    preveriEnako("11 s: se cakamo", false, OsPravila.iskanjeKoncano(false, false, 11_000))
    preveriEnako("12 s, YouTubio odgovoril: konec", true, OsPravila.iskanjeKoncano(false, true, 12_000))
    preveriEnako("12 s, YouTubio se isce: cakamo", false, OsPravila.iskanjeKoncano(false, false, 12_500))
    preveriEnako("16 s: konec v vsakem primeru", true, OsPravila.iskanjeKoncano(false, false, 16_000))
    // Naloga 90c: iskanje uporabnika gre mimo varovalke odzivnosti; katalog brez iskanja ne.
    preveriEnako("iskanje Coldplay mimo varovalke", true, OsPravila.iskanjeMimoVarovalke("Coldplay"))
    preveriEnako("prazno iskanje (katalog v ozadju) cez varovalko", false, OsPravila.iskanjeMimoVarovalke("  "))
    // Naloga 90c: Nazaj z iskanja gre na razdelek, iz katerega je bilo odprto.
    preveriEnako("Nazaj z iskanja iz Glasbe: Glasba", 1, OsPravila.ciljNazajIzIskanja(1, 7, 0))
    preveriEnako("Nazaj z iskanja, odprtega iz plosce: plosca", 0, OsPravila.ciljNazajIzIskanja(0, 7, 0))
    preveriEnako("Nazaj z iskanja, ki je izvor sam: plosca", 0, OsPravila.ciljNazajIzIskanja(7, 7, 0))
    preveriEnako("ime izvora: YouTubio | ElfHosted", "YouTubio", OsPravila.imeIzvora("YouTubio | ElfHosted"))
    preveriEnako("ime izvora: brez pripone", "FayaTV", OsPravila.imeIzvora("FayaTV"))
    preveriEnako("ime izvora: samo pripona", "| x", OsPravila.imeIzvora("| x"))
    preveriEnako("youtube.com je posnetek", true, OsPravila.jeYoutubePovezava("https://www.youtube.com/watch?v=abc"))
    preveriEnako("youtu.be je posnetek", true, OsPravila.jeYoutubePovezava("https://youtu.be/abc"))
    preveriEnako("m.youtube.com je posnetek", true, OsPravila.jeYoutubePovezava("http://m.youtube.com/watch?v=abc"))
    preveriEnako("lazen youtube.com.evil ni", false, OsPravila.jeYoutubePovezava("https://youtube.com.evil.test/watch"))
    preveriEnako("uporabnik@ ni", false, OsPravila.jeYoutubePovezava("https://youtube.com@evil.test/"))
    preveriEnako("placilna stran ni", false, OsPravila.jeYoutubePovezava("https://iptv.test/premium"))
    preveriEnako("prazno ni", false, OsPravila.jeYoutubePovezava(""))
}

/** Naloga 79b: povezava za odklep pri dodatku (varna, brez poizvedbe) in isti dodatek pod dvema naslovoma. */
private fun preizkusOdklepa() {
    println("\n== naloga 79b: odklep pri dodatku ==")
    preveriEnako("https: gostitelj in pot", "https://iptv.example/premium", OsPravila.povezavaOdklepa("https://IPTV.example/premium?kljuc=abc#x"))
    preveriEnako("brez poti: /", "http://iptv.example/", OsPravila.povezavaOdklepa("http://iptv.example"))
    preveriEnako("z vrati", "https://iptv.example:8443/unlock", OsPravila.povezavaOdklepa("https://iptv.example:8443/unlock"))
    preveriEnako("javascript ni", null, OsPravila.povezavaOdklepa("javascript:alert(1)"))
    preveriEnako("intent ni", null, OsPravila.povezavaOdklepa("intent://x#Intent;end"))
    preveriEnako("kontrolni znak ni", null, OsPravila.povezavaOdklepa("https://iptv.example/a\nb"))
    preveriEnako("presledek ni", null, OsPravila.povezavaOdklepa("https://iptv.example/a b"))
    preveriEnako("uporabnik@gostitelj ni", null, OsPravila.povezavaOdklepa("https://user@iptv.example/"))
    preveriEnako("predolga ni", null, OsPravila.povezavaOdklepa("https://iptv.example/" + "a".repeat(OsPravila.ODKLEP_NAJVEC)))
    preveriEnako("prazna ni", null, OsPravila.povezavaOdklepa(""))
    preveriEnako("null ni", null, OsPravila.povezavaOdklepa(null))
    val dodani = mapOf("https://a.test/stara" to "org.iptv", "https://a.test/kodaXYZ" to "org.iptv", "https://b.test" to "org.drug", "https://c.test" to "")
    preveriEnako("dvojnik: stari naslov istega id", listOf("https://a.test/stara"), OsPravila.stariNasloviDodatka("https://a.test/kodaXYZ", "org.iptv", dodani))
    preveriEnako("dvojnik: drug dodatek ni", emptyList<String>(), OsPravila.stariNasloviDodatka("https://b.test", "org.drug", dodani))
    preveriEnako("dvojnik: brez id ni", emptyList<String>(), OsPravila.stariNasloviDodatka("https://c.test", "", dodani))
}

/** Naloga 90b: odklep po placilu (poskus ob odprtju) in zapis cele mreze Radia na disk. */
private fun preizkusTv90b() {
    println("\n== naloga 90b: odklep in zapis mreze ==")
    val naslovi = listOf("https://a.test/manifest.json", "https://a.test/x/manifest.json", "https://b.test/manifest.json", "https://c.test/manifest.json")
    val osnova = { n: String -> n.substringBefore("/manifest.json").substringBefore("/x") }
    preveriEnako("poskus samo za zaklenjene, dodatek enkrat", listOf(naslovi[0], naslovi[3]),
        OsPravila.zaklenjeniZaPoskus(naslovi, osnova) { it == naslovi[0] || it == naslovi[1] || it == naslovi[3] })
    preveriEnako("nic zaklenjenega: brez poskusa", emptyList<String>(), OsPravila.zaklenjeniZaPoskus(naslovi, osnova) { false })
    var z = OsPravila.Zaklep()
    repeat(5) { z = OsPravila.poOdgovoruTokov(z, 1, 0, 1000L) }
    preveriEnako("zaklenjen pred poskusom", true, OsPravila.jeZaklenjen(z, 1001L))
    preveriEnako("predvajljiv tok pri poskusu takoj pobrise zaklep", false, OsPravila.jeZaklenjen(OsPravila.poOdgovoruTokov(z, 3, 1, 2000L), 2001L))
    preveriEnako("samo obvestilo pri poskusu ne odklene", true, OsPravila.jeZaklenjen(OsPravila.poOdgovoruTokov(z, 1, 0, 2000L), 2001L))
    val postaje = (1..134).toList()
    preveriEnako("na disk gre vseh 134 postaj, ne 60", 134, OsPravila.zapisMreze(postaje).size)
    preveriEnako("zapis ima zgornjo mejo", OsPravila.ZAPIS_MREZE_NAJVEC, OsPravila.zapisMreze((1..1000).toList()).size)
    preveriEnako("prva stran gre na disk, ce ni pozni virov", true, OsPravila.shraniPrvoStranMreze(false, true))
    preveriEnako("prva stran brez predpomnilnika gre na disk", true, OsPravila.shraniPrvoStranMreze(true, false))
    preveriEnako("prva stran ne povozi vecje mreze, dokler pozni viri tecejo", false, OsPravila.shraniPrvoStranMreze(true, true))
}

/** Naloga 79: dodatek, ki za kanale vraca samo obvestila (placljiv dostop), je »brez prostih tokov«. */
private fun preizkusZaklenjenegaDodatka() {
    println("\n== naloga 79: dodatek brez prostih tokov ==")
    val ura = 3_600_000L
    val zdaj = 1000 * ura
    var z = OsPravila.Zaklep()
    repeat(4) { z = OsPravila.poOdgovoruTokov(z, 1, 0, zdaj) }
    preveriEnako("4 kanali samo obvestila: se ni zaklenjen", false, OsPravila.jeZaklenjen(z, zdaj))
    z = OsPravila.poOdgovoruTokov(z, 1, 0, zdaj)
    preveriEnako("5. zapored: zaklenjen", true, OsPravila.jeZaklenjen(z, zdaj))
    preveriEnako("zaklenjen ob prvem dosegu meje", zdaj, z.zaklenjenOb)
    preveriEnako("prazen odgovor (kanal brez toka) ne spremeni nicesar", z, OsPravila.poOdgovoruTokov(z, 0, 0, zdaj + ura))
    preveriEnako("predvajljiv tok pobrise zaklep", OsPravila.Zaklep(), OsPravila.poOdgovoruTokov(z, 2, 1, zdaj + ura))
    var y = OsPravila.Zaklep()
    repeat(3) { y = OsPravila.poOdgovoruTokov(y, 1, 0, zdaj) }
    y = OsPravila.poOdgovoruTokov(y, 3, 2, zdaj)
    repeat(4) { y = OsPravila.poOdgovoruTokov(y, 1, 0, zdaj) }
    preveriEnako("vmesni ziv kanal steje od zacetka", false, OsPravila.jeZaklenjen(y, zdaj))
    preveriEnako("sklep velja 23 h", true, OsPravila.jeZaklenjen(z, zdaj + 23 * ura))
    preveriEnako("sklep po 24 h ne velja", false, OsPravila.jeZaklenjen(z, zdaj + 24 * ura))
    val obnovljen = OsPravila.poOdgovoruTokov(z, 1, 0, zdaj + 25 * ura)
    preveriEnako("po izteku prvo obvestilo sklep obnovi", true, OsPravila.jeZaklenjen(obnovljen, zdaj + 25 * ura))
    preveriEnako("ura nazaj: ne velja", false, OsPravila.jeZaklenjen(z, zdaj - ura))
    preveriEnako("brez zapisa: ne velja", false, OsPravila.jeZaklenjen(null, zdaj))
    preveriEnako("zapis: tja in nazaj", z, OsPravila.Zaklep.izNiza(z.vNiz()))
    preveriEnako("zapis: pokvarjen", null, OsPravila.Zaklep.izNiza("x;1"))
    preveriEnako("zapis: negativen", null, OsPravila.Zaklep.izNiza("-1;0"))
    // Naloga 82: za mrezo velja sklep tudi po izteku, dokler dodatek ne da zivega toka.
    preveriEnako("82: mreza - sklep velja", true, OsPravila.zaklenjenZaMrezo(z, zdaj + ura))
    preveriEnako("82: mreza - po 24 h brez zivega toka se vedno zaklenjen", true, OsPravila.zaklenjenZaMrezo(z, zdaj + 30 * ura))
    preveriEnako("82: mreza - ziv tok odklene", false, OsPravila.zaklenjenZaMrezo(OsPravila.poOdgovoruTokov(z, 2, 1, zdaj + 30 * ura), zdaj + 30 * ura))
    preveriEnako("82: mreza - 4 obvestila se ni zaklep", false, OsPravila.zaklenjenZaMrezo(OsPravila.Zaklep(4, 0L), zdaj))
    preveriEnako("82: mreza - brez zapisa", false, OsPravila.zaklenjenZaMrezo(null, zdaj))
}

/** Naloga 78: mreza nikoli ni prazna; prazne skupine izbire. */
private fun preizkusNadomestneVsebine() {
    println("\n== naloga 78: nadomestna vsebina ==")
    val N = OsPravila.Nadomestek.values().associateBy { it.name }
    fun n(vararg imena: String) = imena.map { N.getValue(it) }
    preveriEnako("zadnja znana ima prednost", n("ZADNJA_ZNANA"), OsPravila.nadomestnaVsebina(12, listOf(5, 3), false, 0))
    preveriEnako("brez zadnje: sorodne police", n("SORODNO"), OsPravila.nadomestnaVsebina(0, listOf(0, 3), false, 0))
    preveriEnako("prazne sorodne ne stejejo", n("NIC"), OsPravila.nadomestnaVsebina(0, listOf(0, 0), false, 0))
    preveriEnako("filter: sorodno + Pokazi vse + brez filtra", n("SORODNO", "POKAZI_VSE", "BREZ_FILTRA"), OsPravila.nadomestnaVsebina(0, listOf(4), true, 20))
    preveriEnako("filter brez vsega: samo Pokazi vse", n("POKAZI_VSE"), OsPravila.nadomestnaVsebina(0, emptyList(), true, 0))
    preveriEnako("filter z zadnjo znano", n("ZADNJA_ZNANA", "POKAZI_VSE"), OsPravila.nadomestnaVsebina(8, emptyList(), true, 0))
    preveriEnako("res nic: NIC", n("NIC"), OsPravila.nadomestnaVsebina(0, emptyList(), false, 0))
    val sk = listOf("" to true, "videospoti" to false, "moje" to true, "naprava" to true)
    preveriEnako("skupine: prazna izpade", listOf("", "moje", "naprava"), OsPravila.skupineIzbire(sk, ""))
    preveriEnako("skupine: prazna izbrana na koncu", listOf("", "moje", "naprava", "videospoti"), OsPravila.skupineIzbire(sk, "videospoti"))
    preveriEnako("skupine: privzeta ostane tudi brez vira", listOf(""), OsPravila.skupineIzbire(listOf("" to false, "videospoti" to false), ""))
    preveriEnako("skupine: z virom po vrsti", listOf("", "videospoti", "moje"), OsPravila.skupineIzbire(listOf("" to true, "videospoti" to true, "moje" to true), "moje"))
    preveriEnako("skupine: prazen seznam", emptyList<String>(), OsPravila.skupineIzbire(emptyList<Pair<String, Boolean>>(), ""))
}

/** Naloga 77: koncerti z IMDb (tip music) so video, tokove zanje vprasamo kot movie; dodatek samo za iskanje. */
private fun preizkusKoncertovInIskanja() {
    println("\n== naloga 77: koncerti in iskanje ==")
    preveriEnako("imdb: tt0111161", true, OsPravila.jeImdb("tt0111161"))
    preveriEnako("imdb: tt brez stevk", false, OsPravila.jeImdb("tt"))
    preveriEnako("imdb: yt_id ni", false, OsPravila.jeImdb("yt_id:abc"))
    preveriEnako("imdb: tt s crko ni", false, OsPravila.jeImdb("tt12a"))
    preveriEnako("koncert music + tt je video", true, OsPravila.glasbaJeVideo(true, "tt1234567"))
    preveriEnako("album music brez tt ostane zvok", false, OsPravila.glasbaJeVideo(true, "jamendo:123"))
    preveriEnako("film tt ni glasba", false, OsPravila.glasbaJeVideo(false, "tt1234567"))
    preveriEnako("tok: music tt, nihce za music -> movie", "movie", OsPravila.tipToka("music", "tt1234567", false))
    preveriEnako("tok: music tt, dodatek za music je -> music", "music", OsPravila.tipToka("music", "tt1234567", true))
    preveriEnako("tok: music brez tt ostane", "music", OsPravila.tipToka("music", "abc", false))
    preveriEnako("tok: series ostane", "series", OsPravila.tipToka("series", "tt1234567", false))
    preveriEnako("tok: movie ostane", "movie", OsPravila.tipToka("movie", "tt1234567", false))
    preveriEnako("tok: YouTube ostane", "YouTube", OsPravila.tipToka("YouTube", "yt_id:abc", false))
    preveriEnako("samo iskanje: YouTubio (dva kataloga, oba samo search)", true, OsPravila.samoIskanje(listOf(false to true, false to true)))
    preveriEnako("samo iskanje: katalog z mrezo", false, OsPravila.samoIskanje(listOf(true to true, false to true)))
    preveriEnako("samo iskanje: brez katalogov", false, OsPravila.samoIskanje(emptyList()))
    preveriEnako("samo iskanje: katalog z obveznim filtrom brez iskanja", false, OsPravila.samoIskanje(listOf(false to false)))
}

/** Naloga 75: zanesljivost izida sonde (mrtev ni za vedno), urnik pregleda dodatka, pomnilniski pritisk. */
private fun preizkusPregledaKanalov() {
    println("\n== naloga 75: pregled kanalov ==")
    preveriEnako("izid: 200 ziv", OsPravila.IzidSonde.ZIV, OsPravila.izidPoOdgovoru(200))
    preveriEnako("izid: 404 mrtev", OsPravila.IzidSonde.MRTEV, OsPravila.izidPoOdgovoru(404))
    preveriEnako("izid: 410 mrtev", OsPravila.IzidSonde.MRTEV, OsPravila.izidPoOdgovoru(410))
    preveriEnako("izid: 403 (geo) mrtev do izteka", OsPravila.IzidSonde.MRTEV, OsPravila.izidPoOdgovoru(403))
    preveriEnako("izid: 401 prijava ne vemo", OsPravila.IzidSonde.NEZNANO, OsPravila.izidPoOdgovoru(401))
    preveriEnako("izid: 429 omejitev ne vemo", OsPravila.IzidSonde.NEZNANO, OsPravila.izidPoOdgovoru(429))
    preveriEnako("izid: 500 negotovo", OsPravila.IzidSonde.NEGOTOVO, OsPravila.izidPoOdgovoru(500))
    preveriEnako("izid: 503 negotovo", OsPravila.IzidSonde.NEGOTOVO, OsPravila.izidPoOdgovoru(503))
    preveriEnako("casovna omejitev ob delujocem omrezju: negotovo", OsPravila.IzidSonde.NEGOTOVO, OsPravila.izidCasovneOmejitve(true))
    preveriEnako("casovna omejitev brez omrezja: ne vemo", OsPravila.IzidSonde.NEZNANO, OsPravila.izidCasovneOmejitve(false))
    preveriEnako("casovna omejitev, imenik ni odgovoril: ne vemo", OsPravila.IzidSonde.NEZNANO, OsPravila.izidCasovneOmejitve(null))
    preveriEnako("ena casovna omejitev ni mrtev", false, OsPravila.mrtevPoNegotovih(1))
    preveriEnako("dve zaporedni sta mrtev", true, OsPravila.mrtevPoNegotovih(2))
    preveriEnako("sonda (stara pot) ostane: 5xx ne vemo", null, OsPravila.sondaPoOdgovoru(500))
    val dan = OsPravila.PREGLED_VSAKIH_MS
    val ura = OsPravila.PREGLED_PONOVI_MS
    val zdaj = 100 * dan
    preveriEnako("pregled: se nikoli -> na vrsti", true, OsPravila.pregledJeNaVrsti(0, 0, zdaj))
    preveriEnako("pregled: poln pred uro -> ne", false, OsPravila.pregledJeNaVrsti(zdaj - ura, zdaj - ura, zdaj))
    preveriEnako("pregled: poln pred dnevom -> da", true, OsPravila.pregledJeNaVrsti(zdaj - dan, zdaj - dan, zdaj))
    preveriEnako("pregled: prekinjen pred 10 min -> ne", false, OsPravila.pregledJeNaVrsti(zdaj - 2 * dan, zdaj - 600_000L, zdaj))
    preveriEnako("pregled: prekinjen pred dvema urama -> da", true, OsPravila.pregledJeNaVrsti(zdaj - 2 * dan, zdaj - 2 * ura, zdaj))
    preveriEnako("pregled: ura naprave nazaj -> na vrsti", true, OsPravila.pregledJeNaVrsti(zdaj + dan, zdaj + dan, zdaj))
    preveriEnako("pomnilnik: RUNNING_LOW", true, OsPravila.pritiskPomnilnika(10))
    preveriEnako("pomnilnik: RUNNING_CRITICAL", true, OsPravila.pritiskPomnilnika(15))
    preveriEnako("pomnilnik: UI_HIDDEN ni pritisk", false, OsPravila.pritiskPomnilnika(20))
    preveriEnako("pomnilnik: BACKGROUND ni pritisk", false, OsPravila.pritiskPomnilnika(40))
    preveriEnako("pomnilnik: MODERATE", true, OsPravila.pritiskPomnilnika(60))
    preveriEnako("pomnilnik: COMPLETE", true, OsPravila.pritiskPomnilnika(80))
}

private fun preizkusMer() {
    println("\n== mere ikon ==")
    preveriEnako("majhna ikona ostane cela", 1, OsPravila.vzorec(128, 128, 512))
    preveriEnako("ikona tocno na meji ostane cela", 1, OsPravila.vzorec(512, 512, 512))
    preveriEnako("1024 se pomanjsa na polovico", 2, OsPravila.vzorec(1024, 1024, 512))
    preveriEnako("visoka ozka slika se pomanjsa po daljsi stranici", 8, OsPravila.vzorec(64, 4000, 512))
    preveriEnako("sirina 8192 je se sprejeta (pomanjsana)", 16, OsPravila.vzorec(8192, 8192, 512))
    preveriEnako("ogromna slika se ne dekodira", 0, OsPravila.vzorec(20000, 20000, 512))
    preveriEnako("ogromna v eni smeri se ne dekodira", 0, OsPravila.vzorec(100, 50000, 512))
    preveriEnako("nicelne mere (ni slika) se ne dekodirajo", 0, OsPravila.vzorec(0, 0, 512))
    preveriEnako("negativne mere se ne dekodirajo", 0, OsPravila.vzorec(-1, 10, 512))
    for ((s, v) in listOf(513 to 513, 999 to 10, 4096 to 4096, 8192 to 1, 1 to 8192)) {
        val n = OsPravila.vzorec(s, v, 512)
        preveri("$s x $v: daljsa stranica po pomanjsanju najvec 512", n > 0 && maxOf(s, v) / n <= 512)
    }
}

private fun preizkusNadaljuj() {
    println("\n== vrstica Nadaljuj ==")
    val stari = listOf(V("a"), V("b"), V("c"))
    preveriEnako("nov vnos gre na vrh", listOf("d", "a", "b", "c"),
        OsPravila.naVrh(V("d"), stari, 6) { it.kljuc }.map { it.kljuc })
    // Ponovni zagon programa (tudi s pripete kartice): se ne podvoji, ampak gre na vrh.
    preveriEnako("ponovno odprt program se premakne na vrh, ne podvoji", listOf("c", "a", "b"),
        OsPravila.naVrh(V("c", "novo ime"), stari, 6) { it.kljuc }.map { it.kljuc })
    preveriEnako("na vrhu je novi zapis (sveze ime)", "novo ime",
        OsPravila.naVrh(V("c", "novo ime"), stari, 6) { it.kljuc }.first().ime)
    val polno = (1..6).map { V("p$it") }
    val po = OsPravila.naVrh(V("nov"), polno, 6) { it.kljuc }
    preveriEnako("vrstica ne zraste cez najvec", 6, po.size)
    preveriEnako("najstarejsi izpade", false, po.any { it.kljuc == "p6" })
    preveriEnako("prazna vrstica dobi en vnos", 1, OsPravila.naVrh(V("x"), emptyList(), 6) { it.kljuc }.size)
}

private fun preizkusIkon() {
    println("\n== ikone vrstice Nadaljuj ==")
    val a = OsPravila.imeIkone("program|pc|app:kalkulator")
    val b = OsPravila.imeIkone("program|pc|app:igra")
    preveri("ime ikone je stabilno", a == OsPravila.imeIkone("program|pc|app:kalkulator"))
    preveri("razlicni programi, razlicni datoteki", a != b)
    preveri("ime je varno ime datoteke", Regex("[0-9a-f]+\\.png").matches(a))
    preveriEnako("ikona odstranjene vrstice se izbrise", listOf(b),
        OsPravila.odvecneIkone(listOf(a, b), listOf("program|pc|app:kalkulator")))
    preveriEnako("tuje datoteke v mapi se pobrisejo", listOf("smet.tmp"),
        OsPravila.odvecneIkone(listOf(a, "smet.tmp"), listOf("program|pc|app:kalkulator")))
    preveriEnako("prazna vrstica pobrise vse", listOf(a, b), OsPravila.odvecneIkone(listOf(a, b), emptyList()))
}

private fun preizkusZaslona() {
    println("\n== kartica Zaslon racunalnika ==")
    preveri("celo namizje zapise kartico Zaslon", OsPravila.zapisiZaslon(naLocenemZaslonu = false))
    preveri("program na locenem zaslonu NE zapise kartice Zaslon", !OsPravila.zapisiZaslon(naLocenemZaslonu = true))
}

private fun pasMedVideom() {
    // Daljinec: odprta vrsta kartic pas drzi, sicer se skrije (tudi med pavzo - kot doslej).
    preveri("daljinec: brez vrste se pas skrije", OsPravila.pasSeSkrije(dotik = false, vrstaOdprta = false, tece = true))
    preveri("daljinec: brez vrste se pas skrije tudi med pavzo", OsPravila.pasSeSkrije(dotik = false, vrstaOdprta = false, tece = false))
    preveri("daljinec: odprta vrsta pas drzi", !OsPravila.pasSeSkrije(dotik = false, vrstaOdprta = true, tece = true))
    // Dotik: ko video tece, se pas skrije tudi z odprto vrsto kartic; med pavzo ostane.
    preveri("dotik: video tece, vrsta odprta - pas se skrije", OsPravila.pasSeSkrije(dotik = true, vrstaOdprta = true, tece = true))
    preveri("dotik: video tece, vrsta zaprta - pas se skrije", OsPravila.pasSeSkrije(dotik = true, vrstaOdprta = false, tece = true))
    preveri("dotik: pavza - pas ostane", !OsPravila.pasSeSkrije(dotik = true, vrstaOdprta = true, tece = false) &&
        !OsPravila.pasSeSkrije(dotik = true, vrstaOdprta = false, tece = false))
    preveri("po pavzi se pas skrije prej kot sicer", OsPravila.pasZamik(poPavzi = true) < OsPravila.pasZamik(poPavzi = false) &&
        OsPravila.pasZamik(poPavzi = true) >= 1_000L)
    // Odhod z zaslona predvajanja: video se ustavi, razen ce se je medtem zacelo novo predvajanje ali ga kaze drug zaslon.
    preveri("odhod (Nazaj, Domov): video se ustavi", !OsPravila.novoPredvajanjeObOdhodu(zagonovObOdhodu = 7, zagonovZdaj = 7, drugihZaslonov = 0))
    preveri("nov posnetek, zagnan med odhodom starega zaslona, se ne ustavi",
        OsPravila.novoPredvajanjeObOdhodu(zagonovObOdhodu = 7, zagonovZdaj = 8, drugihZaslonov = 0))
    preveri("video ze kaze drug zaslon predvajanja: se ne ustavi",
        OsPravila.novoPredvajanjeObOdhodu(zagonovObOdhodu = 7, zagonovZdaj = 7, drugihZaslonov = 1))
    preveri("onPause ni zabelezen: staro vedenje (video se ustavi)",
        !OsPravila.novoPredvajanjeObOdhodu(zagonovObOdhodu = -1, zagonovZdaj = 3, drugihZaslonov = 0))
    preveri("stevec na zacetku (0 zagonov) ne pomeni novega predvajanja",
        !OsPravila.novoPredvajanjeObOdhodu(zagonovObOdhodu = 0, zagonovZdaj = 0, drugihZaslonov = 0))
}

private fun preizkusIkonNaprav() {
    println("\n== ikone naprav v Safeer Linku ==")
    preveriEnako("telefon", "telefon", OsPravila.ikonaNaprave("phone", false))
    preveriEnako("telefon, ki deli datoteke, ostane telefon", "telefon", OsPravila.ikonaNaprave("phone", true))
    preveriEnako("tablica, ki deli datoteke, ostane tablica (prej ikona racunalnika)", "tablica", OsPravila.ikonaNaprave("tablet", true))
    preveriEnako("racunalnik z Linuxom", "racunalnik", OsPravila.ikonaNaprave("linux", true))
    preveriEnako("racunalnik z Windows brez deljenih datotek", "racunalnik", OsPravila.ikonaNaprave("windows", false))
    preveriEnako("televizor", "naprava", OsPravila.ikonaNaprave("tv", true))
    preveriEnako("naprava brez platforme, ki deli datoteke, je racunalnik (kot doslej)", "racunalnik", OsPravila.ikonaNaprave("", true))
    preveriEnako("naprava brez platforme in brez datotek", "naprava", OsPravila.ikonaNaprave("", false))
}

private fun preizkusOpisaNaprav() {
    println("\n== podnapis naprav v Safeer Linku ==")
    fun opis(platforma: String, id: String, ime: String, prikazano: String = ime, naslov: String = "10.0.0.7") =
        OsPravila.opisNaprave(platforma, id, ime, prikazano, naslov)
    preveriEnako("racunalnik s Safeer Control: program v podnapisu, ker ga prikazano ime nima",
        "racunalnik" to "Safeer Control", opis("windows", "n-1-control", "Safeer Control (PISARNA)", "PISARNA"))
    preveriEnako("racunalnik z brskalnikom: ime ze pove program", "racunalnik" to "",
        opis("linux", "n-2", "Safeer Browser (dnevna-soba)"))
    preveriEnako("preimenovan racunalnik z brskalnikom", "racunalnik" to "Safeer Browser", opis("linux", "n-2", "Stari prenosnik"))
    preveriEnako("telefon s Safeer OS ni vec »Safeer Browser · Android«", "telefon" to "", opis("phone", "n-3", "Safeer OS (LE2113)"))
    preveriEnako("telefon s Safeer OS Mobile", "telefon" to "", opis("phone", "n-4", "Safeer OS Mobile (SM-S931B)"))
    preveriEnako("preimenovan telefon: programa ne ugibamo", "telefon" to "", opis("phone", "n-5", "Lastnikov telefon"))
    preveriEnako("telefon s Safeer Browserjem", "telefon" to "Safeer Browser", opis("phone", "n-6", "Safeer (Pixel 8)"))
    preveriEnako("preimenovana tablica", "tablica" to "Safeer OS", opis("tablet", "n-7", "Tablica v kuhinji"))
    preveriEnako("tablica s privzetim imenom", "tablica" to "", opis("tablet", "n-8", "Safeer OS Tablet (SM-X210)"))
    preveriEnako("televizor", "tv" to "Safeer Link", opis("tv", "n-9", "Safeer TV (Philips)"))
    preveriEnako("starejsi televizor brez platforme", "tv" to "Safeer Link", opis("", "tv-philips", "Televizor"))
    preveriEnako("sredisce brez platforme (naslov zanke)", "tv" to "Safeer Link", opis("", "x1", "Dnevna soba", naslov = "127.0.0.1"))
    preveriEnako("starejsi racunalnik brez platforme", "racunalnik" to "Safeer Control", opis("", "pc-abc-control", "Pisarna"))
    preveriEnako("starejsi telefon brez platforme", "telefon" to "Safeer Browser", opis("", "phone-pixel", "Pixel"))
    preveriEnako("neznana naprava ostane brez podnapisa", "" to "", opis("", "n-10", "Nekaj"))
    preveriEnako("program telefona: privzeto ime huba", "Safeer Browser", OsPravila.programTelefona("Safeer telefon (Pixel 8)"))
    preveriEnako("program telefona: »Safeer« sredi imena ni dovolj", "", OsPravila.programTelefona("Moj Safeer (Pixel 8)"))
}

private fun preizkusPovezave() {
    println("\n== prejeto besedilo: ena sama povezava ==")
    preveriEnako("https povezava", true, OsPravila.jePovezava("https://safeer.si/prenos"))
    preveriEnako("http povezava s presledki okoli", true, OsPravila.jePovezava("  http://10.0.0.7:8080/a?b=1\n"))
    preveriEnako("stavek s povezavo ni povezava", false, OsPravila.jePovezava("poglej https://safeer.si"))
    preveriEnako("povezava in se kaj za njo", false, OsPravila.jePovezava("https://safeer.si in se nekaj"))
    preveriEnako("dve vrstici", false, OsPravila.jePovezava("https://safeer.si\nhttps://example.org"))
    preveriEnako("samo shema", false, OsPravila.jePovezava("https://"))
    preveriEnako("navadno besedilo", false, OsPravila.jePovezava("Kupi kruh"))
    preveriEnako("druga shema", false, OsPravila.jePovezava("javascript:alert(1)"))
    preveriEnako("prazno", false, OsPravila.jePovezava(""))
}

private fun preizkusJezikaKataloga() {
    println("\n== jezik kataloga iz naslovov (naloga 60) ==")
    // Naslovi iz kataloga m3u (brazilski filmi, portugalski naslovi) in angleskega kataloga.
    val pt = listOf("Homem-Aranha", "Maçã Podre", "Maca Podre", "Cara-de-Barro", "O Auto da Compadecida", "Cidade de Deus",
        "Central do Brasil", "Tropa de Elite", "Bacurau", "O Som ao Redor", "Minha Mãe é uma Peça", "Carandiru", "O Palhaço",
        "Meu Nome Não é Johnny", "Homem de Ferro", "O Rei Leão", "O Poderoso Chefão", "De Volta para o Futuro", "Os Incríveis")
    val en = listOf("The Godfather", "Pulp Fiction", "The Lord of the Rings: The Return of the King", "Fight Club", "Star Wars",
        "Back to the Future", "Harry Potter and the Goblet of Fire", "The Silence of the Lambs", "The Lion King", "Toy Story",
        "Night of the Living Dead", "Gone with the Wind", "Raiders of the Lost Ark", "The Dark Knight", "Spider-Man: No Way Home",
        "Inception", "Avatar", "The Incredibles", "The Matrix", "Titanic")
    preveriEnako("naslov: Homem-Aranha je portugalski", "pt", OsPravila.jezikNaslova("Homem-Aranha"))
    preveriEnako("naslov: Maçã Podre je portugalski", "pt", OsPravila.jezikNaslova("Maçã Podre"))
    preveriEnako("naslov: Maca Podre (brez diakritike) je neodlocen", "", OsPravila.jezikNaslova("Maca Podre"))
    preveriEnako("naslov: The Dark Knight je angleski", "en", OsPravila.jezikNaslova("The Dark Knight"))
    preveriEnako("naslov: Spider-Man: No Way Home ni portugalski (neodlocen)", "", OsPravila.jezikNaslova("Spider-Man: No Way Home"))
    preveriEnako("naslov: ime brez besed (Avatar) je neodloceno", "", OsPravila.jezikNaslova("Avatar"))
    preveriEnako("naslov: Das Leben der Anderen je nemski", "de", OsPravila.jezikNaslova("Das Leben der Anderen"))
    preveriEnako("naslov: La vita è bella je italijanski", "it", OsPravila.jezikNaslova("La vita è bella"))
    preveriEnako("naslov: francoska elizija", "fr", OsPravila.jezikNaslova("Le fabuleux destin d'Amélie Poulain"))
    preveriEnako("naslov: El laberinto del fauno je spanski", "es", OsPravila.jezikNaslova("El laberinto del fauno"))
    preveriEnako("naslov: Ljubezen na odoru je slovenski", "sl", OsPravila.jezikNaslova("Ljubezen na odoru"))
    preveriEnako("naslov: Život je čudo je hr/sr/bs", OsPravila.JEZIK_HBS, OsPravila.jezikNaslova("Život je čudo"))
    preveriEnako("katalog s portugalskimi naslovi je pt", "pt", OsPravila.jezikKataloga(pt + pt + pt))
    preveriEnako("angleski katalog je en", "en", OsPravila.jezikKataloga(en + en + en))
    preveriEnako("pol portugalskih, pol angleskih: neodlocen", "", OsPravila.jezikKataloga((pt.take(12) + en.take(12)).let { it + it + it }))
    preveriEnako("premalo odlocenih naslovov: neodlocen", "", OsPravila.jezikKataloga(listOf("Homem-Aranha", "Cidade de Deus", "O Rei Leão") + List(40) { "Avatar $it" }))
    preveriEnako("samo imena (brez besed jezika): neodlocen", "", OsPravila.jezikKataloga(List(80) { listOf("Avatar", "Titanic", "Bacurau", "Carandiru")[it % 4] }))
    preveriEnako("prazen katalog: neodlocen", "", OsPravila.jezikKataloga(emptyList()))
    preveri("vzorec gleda najvec ${OsPravila.VZOREC_JEZIKA} naslovov", OsPravila.jezikKataloga(List(OsPravila.VZOREC_JEZIKA) { "Avatar" } + pt + pt + pt) == "")
    preveriEnako("hr/sr/bs katalog ustreza srbscini", true, OsPravila.jezikKatalogaUstreza(OsPravila.JEZIK_HBS, "sr"))
    preveriEnako("pt katalog ne ustreza anglescini", false, OsPravila.jezikKatalogaUstreza("pt", "en"))
    preveriEnako("pt katalog ustreza portugalscini", true, OsPravila.jezikKatalogaUstreza("pt", "pt"))
}

private fun preizkusVrsteIzMeta() {
    println("\n== film ali serija iz metapodatkov (naloga 63) ==")
    preveriEnako("ostevilcene epizode: serija, tudi ce dodatek pravi film", "series", OsPravila.vrstaIzMeta("movie", "2016", listOf(1 to 1, 1 to 2)))
    preveriEnako("type series", "series", OsPravila.vrstaIzMeta("series", "", emptyList()))
    preveriEnako("obdobje 2016–2021 (pomisljaj): serija", "series", OsPravila.vrstaIzMeta("movie", "2016–2021", emptyList()))
    preveriEnako("obdobje 2016- (se tece): serija", "series", OsPravila.vrstaIzMeta("movie", "2016-", emptyList()))
    preveriEnako("film z eno letnico", "movie", OsPravila.vrstaIzMeta("movie", "2016", emptyList()))
    preveriEnako("film z videom brez stevilk (napovednik): film", "movie", OsPravila.vrstaIzMeta("movie", "2019", listOf(0 to 0)))
    preveriEnako("sezona 0 ali epizoda 0 ni epizoda", "movie", OsPravila.vrstaIzMeta("movie", "", listOf(1 to 0, 0 to 3)))
    preveriEnako("velike crke v type", "series", OsPravila.vrstaIzMeta(" Series ", "", emptyList()))
    preveriEnako("brez podatkov: ne vemo", null, OsPravila.vrstaIzMeta("", "", emptyList()))
    preveriEnako("drug tip (tv) brez epizod: ne vemo", null, OsPravila.vrstaIzMeta("tv", "2020", emptyList()))
    preveriEnako("letnica z besedilom ni obdobje", "movie", OsPravila.vrstaIzMeta("movie", "2016 (remaster 2021)", emptyList()))
}

private fun preizkusJezikaPoDrzavah() {
    println("\n== jezik iz drzave izvora (naloga 66) ==")
    preveriEnako("US in Juzna Koreja: anglescina ustreza", true, OsPravila.jezikPoDrzavah("United States, South Korea", "en"))
    preveriEnako("Juzna Koreja: anglescina ne ustreza (Hope)", false, OsPravila.jezikPoDrzavah("South Korea", "en"))
    preveriEnako("Juzna Koreja: korejscina ustreza", true, OsPravila.jezikPoDrzavah("South Korea", "ko"))
    preveriEnako("prazno: ne vemo, naslov ostane", null, OsPravila.jezikPoDrzavah("", "en"))
    preveriEnako("presledki in vejice: ne vemo", null, OsPravila.jezikPoDrzavah(" , ", "en"))
    preveriEnako("vec drzav: UK, Avstralija, US", true, OsPravila.jezikPoDrzavah("United Kingdom, Australia, United States", "en"))
    preveriEnako("Kanada je tudi francoska", true, OsPravila.jezikPoDrzavah("Canada", "fr"))
    preveriEnako("Svica je nemska, francoska in italijanska", true, OsPravila.jezikPoDrzavah("Switzerland", "it"))
    preveriEnako("znana in neznana drzava, ni ujemanja: ne vemo", null, OsPravila.jezikPoDrzavah("South Korea, Atlantis", "en"))
    preveriEnako("neznana drzava: ne vemo", null, OsPravila.jezikPoDrzavah("Atlantis", "en"))
    preveriEnako("velike crke, »the«, podpicje", true, OsPravila.jezikPoDrzavah("FRANCE; The United States", "en"))
    preveriEnako("Jugoslavija ustreza slovenscini", true, OsPravila.jezikPoDrzavah("Yugoslavia", "sl"))
    preveriEnako("Brazilija ni anglescina", false, OsPravila.jezikPoDrzavah("Brazil", "en"))
    preveriEnako("brez izbranega jezika: ne vemo", null, OsPravila.jezikPoDrzavah("Brazil", ""))
}

private fun preizkusSeznamaPostaj() {
    println("\n== seznam postaj ali ena postaja (naloga 68) ==")
    preveriEnako("Radio Browser: razlicna imena, tokovi brez koncnice -> seznam", true,
        OsPravila.jeSeznamPostaj(listOf("Radio 1", "Radio Aktual", "Val 202", "Radio Center"), 0))
    preveriEnako(".pls s tremi zrcali istega imena -> ena postaja", false,
        OsPravila.jeSeznamPostaj(listOf("Radio Paradise", "Radio Paradise", "Radio Paradise"), 0))
    preveriEnako(".m3u brez imen in brez koncnic -> ena postaja", false, OsPravila.jeSeznamPostaj(listOf("", "", ""), 0))
    preveriEnako("ime se razlikuje le v velikih crkah in presledkih -> ena postaja", false,
        OsPravila.jeSeznamPostaj(listOf("Radio  1", "radio 1"), 0))
    preveriEnako("eno ime in prazni vnosi -> ena postaja", false, OsPravila.jeSeznamPostaj(listOf("Val 202", "", ""), 0))
    preveriEnako("polovica vnosov z koncnico -> seznam (kot do zdaj)", true, OsPravila.jeSeznamPostaj(listOf("", "", "", ""), 2))
    preveriEnako("en sam vnos ni seznam", false, OsPravila.jeSeznamPostaj(listOf("Radio 1"), 1))
    preveriEnako("vnos brez koncnice je radio", true, OsPravila.jeRadijskiVnos(false, "#EXTINF:-1,Radio 1"))
    preveriEnako("vnos .pls brez koncnice je radio", true, OsPravila.jeRadijskiVnos(false, ""))
    preveriEnako("datoteka (.mp3/.m3u8) ni radio", false, OsPravila.jeRadijskiVnos(true, "#EXTINF:-1,Radio 1"))
    preveriEnako("kanal iz seznama IPTV ni radio", false,
        OsPravila.jeRadijskiVnos(false, "#EXTINF:-1 tvg-id=\"cnn\" group-title=\"News\",CNN"))
}

private fun preizkusImenikaPostaj() {
    println("\n== imenik radijskih postaj kot vir (naloga 69) ==")
    val rb = OsPravila.IMENIK_RADIO_BROWSER; val ti = OsPravila.IMENIK_TUNEIN
    preveriEnako("radio-browser.info", rb, OsPravila.imenikPostaj("radio-browser.info"))
    preveriEnako("https://www.radio-browser.info/", rb, OsPravila.imenikPostaj("https://www.radio-browser.info/"))
    preveriEnako("naslov API de1.api.radio-browser.info", rb, OsPravila.imenikPostaj("https://de1.api.radio-browser.info/json/stations/search"))
    preveriEnako("velike crke in presledki okoli", rb, OsPravila.imenikPostaj("  Radio-Browser.INFO "))
    preveriEnako("presledek, ki ga vrine tipkovnica", rb, OsPravila.imenikPostaj("radio-browser. info"))
    preveriEnako("seznam .m3u z radio-browser (naloga 68) ni imenik", null,
        OsPravila.imenikPostaj("https://de1.api.radio-browser.info/m3u/stations/bycountrycodeexact/SI?hidebroken=true"))
    preveriEnako("seznam .pls z radio-browser ni imenik", null, OsPravila.imenikPostaj("https://de1.api.radio-browser.info/pls/stations/byname/val"))
    preveriEnako("tunein.com", ti, OsPravila.imenikPostaj("tunein.com"))
    preveriEnako("https://tunein.com/radio/home/", ti, OsPravila.imenikPostaj("https://tunein.com/radio/home/"))
    preveriEnako("OPML opml.radiotime.com", ti, OsPravila.imenikPostaj("http://opml.radiotime.com/"))
    preveriEnako("tok ene postaje Tune.ashx ni imenik", null, OsPravila.imenikPostaj("http://opml.radiotime.com/Tune.ashx?id=s12345"))
    preveriEnako("podobno ime ni imenik", null, OsPravila.imenikPostaj("notradio-browser.info.example.com"))
    preveriEnako("drug naslov ni imenik", null, OsPravila.imenikPostaj("https://live.radio.si/Radio1"))
    preveriEnako("API z {q} ni imenik", null, OsPravila.imenikPostaj("https://de1.api.radio-browser.info/json/stations/byname/{q}"))
    preveriEnako("prazno", null, OsPravila.imenikPostaj(""))
}

private data class P(val ime: String, val tok: String, val neposreden: Boolean = true)

private fun preizkusPostaj() {
    println("\n== ista postaja iz vec imenikov (naloga 71) ==")
    preveriEnako("Radio 1 ostane radio1 (ne samo 1)", "radio1", OsPravila.kljucImenaPostaje("Radio 1"))
    preveriEnako("frekvenca za imenom odpade", "radio1", OsPravila.kljucImenaPostaje("Radio 1 105.2"))
    preveriEnako("frekvenca v oklepaju odpade", "val202", OsPravila.kljucImenaPostaje("Val 202 (98.9MHz)"))
    preveriEnako("beseda radio in FM odpadeta", "center", OsPravila.kljucImenaPostaje("Radio Center 87.6 FM"))
    preveriEnako("naglasi odpadejo", "student", OsPravila.kljucImenaPostaje("Radio Študent"))
    preveriEnako("Rock Radio", "rock", OsPravila.kljucImenaPostaje("Rock Radio"))
    preveriEnako("drzava na koncu: Rock Radio Slovenia", "rock", OsPravila.kljucImenaPostaje("Rock Radio Slovenia"))
    preveriEnako("drzava na koncu: ROCK RADIO SI", "rock", OsPravila.kljucImenaPostaje("ROCK RADIO SI"))
    preveriEnako("podkanal z drzavo ostane svoj", "rockbestballads", OsPravila.kljucImenaPostaje("Rock Radio Best Ballads SLO"))
    preveriEnako("Radio SI ostane (ne prazno)", "si", OsPravila.kljucImenaPostaje("Radio SI"))
    preveriEnako("TuneIn: zvrst v oklepaju in FM 87", OsPravila.kljucImenaPostaje("Radio 1 Rock"), OsPravila.kljucImenaPostaje("Radio 1 ROCK 87 (Rock)"))
    preveriEnako("TuneIn: Rock Radio Si 103.3 (Rock)", "rock", OsPravila.kljucImenaPostaje("Rock Radio Si 103.3 (Rock)"))
    preveriEnako("Val 202 ostane 202", "val202", OsPravila.kljucImenaPostaje("Val 202"))
    preveriEnako("Radio 1 Maribor ni Radio 1", "1maribor", OsPravila.kljucImenaPostaje("Radio 1 Maribor"))
    preveriEnako("najboljsi tok iste postaje, mesto prve pojavitve",
        listOf("Rock Radio SLO" to 160, "Val 202" to 128, "Rock Radio Best Ballads SLO" to 128),
        OsPravila.najboljsiTokovi(listOf("Rock Radio Slovenia" to 128, "Val 202" to 128, "ROCK RADIO SI" to 128,
            "Rock Radio SLO" to 160, "Rock Radio Best Ballads SLO" to 128, "Val 202" to 64), { it.first }, { it.second }))
    preveriEnako("tok: shema, www, poizvedba, posevnica", "live.radio.si/radio1", OsPravila.kljucTokaPostaje("https://www.Live.radio.si/Radio1/?x=1"))
    fun zdruzi(vse: List<P>) = OsPravila.zdruziPostaje(vse, { it.ime }, { it.tok }, { it.neposreden })
    preveriEnako("Radio Browser in TuneIn: ena postaja, ostane Radio Browser",
        listOf(P("Radio 1", "http://live.radio.si/Radio1")), zdruzi(listOf(P("Radio 1", "http://live.radio.si/Radio1"), P("Radio 1", "", false))))
    preveriEnako("TuneIn prvi: postaja z neposrednim tokom stopi na njegovo mesto",
        listOf(P("Radio 1", "http://x/r1"), P("Val 202", "http://a/val")),
        zdruzi(listOf(P("Radio 1", "", false), P("Val 202", "http://a/val"), P("Radio 1", "http://x/r1"))))
    preveriEnako("Val 202 in Val 202 (98.9MHz) sta ista", 1, zdruzi(listOf(P("Val 202", "http://a/val"), P("Val 202 (98.9MHz)", "", false))).size)
    preveriEnako("Rock Radio in Rock Radio Best Ballads nista ista", 2,
        zdruzi(listOf(P("Rock Radio", "http://r/1"), P("Rock Radio Best Ballads", "http://r/2"))).size)
    preveriEnako("isti tok, drugo ime: ista postaja", 1, zdruzi(listOf(P("Center", "http://c.si/live"), P("Radio Center", "https://www.c.si/live/"))).size)
    preveriEnako("brez imena in toka: nic ne zdruzimo", 2, zdruzi(listOf(P("", ""), P("", ""))).size)
}

private fun preizkusPodnapisovDodatkov() {
    println("\n== podnapisi iz dodatkov Stremio (naloga 72) ==")
    preveriEnako("film: movie / tt", "movie" to "tt0111161", OsPravila.idPodnapisov("tt0111161", 0, 0))
    preveriEnako("epizoda: series / tt:S:E", "series" to "tt0944947:1:2", OsPravila.idPodnapisov("tt0944947", 1, 2))
    preveriEnako("brez IMDb ni podnapisov iz dodatka", null, OsPravila.idPodnapisov("", 0, 0))
    preveriEnako("id dodatka (ne IMDb) ni dovolj", null, OsPravila.idPodnapisov("kitsu:123", 0, 0))
    preveriEnako("eng -> en", "en", OsPravila.jezikPodnapisa("eng"))
    preveriEnako("slv -> sl", "sl", OsPravila.jezikPodnapisa("slv"))
    preveriEnako("pob (brazilska) -> pt", "pt", OsPravila.jezikPodnapisa("pob"))
    preveriEnako("ger -> de", "de", OsPravila.jezikPodnapisa("ger"))
    val odgovor = OsPravila.podnapisiDodatka(listOf(
        "https://subs5.strem.io/en/download/file/1" to "eng",
        "https://subs5.strem.io/en/download/file/1" to "eng",          // isti naslov dvakrat
        "https://subs5.strem.io/sl/download/file/2" to "slv",
        "javascript:alert(1)" to "eng",                                // ni http(s)
        "https://subs5.strem.io/x/3" to "",                            // brez jezika
        "https://subs5.strem.io/x/4\n" to "eng"))                    // kontrolni znak
    preveriEnako("razclenitev: dva veljavna, brez ponovitev in neveljavnih",
        listOf(OsPravila.PodnapisDodatka("https://subs5.strem.io/en/download/file/1", "en"), OsPravila.PodnapisDodatka("https://subs5.strem.io/sl/download/file/2", "sl")),
        odgovor)
    preveriEnako("prazen odgovor", emptyList<OsPravila.PodnapisDodatka>(), OsPravila.podnapisiDodatka(emptyList()))
    preveriEnako("jeziki: vmesnik, anglescina, ostali po abecedi", listOf("sl", "en", "de", "hr", "pt"),
        OsPravila.jezikiPodnapisov(listOf("pt", "en", "hr", "sl", "de", "en"), "sl"))
    preveriEnako("jeziki: vmesnik je anglescina", listOf("en", "de", "fr"), OsPravila.jezikiPodnapisov(listOf("fr", "de", "en"), "en"))
    preveriEnako("jeziki: jezika vmesnika ni", listOf("en", "fr"), OsPravila.jezikiPodnapisov(listOf("fr", "en"), "sl"))
}

private fun preizkusTipkMenija() {
    println("\n== stranska vrstica ob brskalniku: tipke daljinca ==")
    preveriEnako("GOR gre meniju (prej v naslovno vrstico strani)", OsPravila.TipkaMenija.MENIJU, OsPravila.tipkaVMeniju(19, true, 0))
    preveriEnako("DOL gre meniju", OsPravila.TipkaMenija.MENIJU, OsPravila.tipkaVMeniju(20, true, 0))
    preveriEnako("OK gre meniju (pritisk)", OsPravila.TipkaMenija.MENIJU, OsPravila.tipkaVMeniju(23, true, 0))
    preveriEnako("OK gre meniju (spust - brez njega ni klika)", OsPravila.TipkaMenija.MENIJU, OsPravila.tipkaVMeniju(23, false, 0))
    preveriEnako("Enter gre meniju", OsPravila.TipkaMenija.MENIJU, OsPravila.tipkaVMeniju(66, true, 0))
    preveriEnako("LEVO ne naredi nicesar (prej premik v strani in zaprt meni)", OsPravila.TipkaMenija.NIC, OsPravila.tipkaVMeniju(21, true, 0))
    preveriEnako("DESNO vrne v stran", OsPravila.TipkaMenija.V_VSEBINO, OsPravila.tipkaVMeniju(22, true, 0))
    preveriEnako("spust tipke DESNO ne naredi nicesar", OsPravila.TipkaMenija.NIC, OsPravila.tipkaVMeniju(22, false, 0))
    preveriEnako("NAZAJ zapusti Splet (prej zgodovina strani)", OsPravila.TipkaMenija.IZHOD, OsPravila.tipkaVMeniju(4, true, 0))
    preveriEnako("drzanje NAZAJ ne steje", OsPravila.TipkaMenija.NIC, OsPravila.tipkaVMeniju(4, true, 3))
    preveriEnako("spust tipke NAZAJ ne steje (pritisk je meni morda sele odprl)", OsPravila.TipkaMenija.NIC, OsPravila.tipkaVMeniju(4, false, 0))
    preveriEnako("predvajaj/pavza ni za meni", OsPravila.TipkaMenija.DRUGAM, OsPravila.tipkaVMeniju(85, true, 0))
    preveriEnako("rdeca tipka ni za meni", OsPravila.TipkaMenija.DRUGAM, OsPravila.tipkaVMeniju(183, true, 0))
    check(OsPravila.TipkaMenija.values().size == 5)
}

private fun preizkusGlasbenegaImena() {
    println("\n== glasbeni dodatek ==")
    for (ime in listOf("Music - Charts", "Vse-Music", "Live Concerts", "Koncerti", "Glasbeni videospoti", "Música en vivo", "Top Songs"))
        preveri("glasbeno ime: $ime", OsPravila.glasbenoIme(ime))
    for (ime in listOf("Musical", "Top filmi", "Movies - Popular", "Kanali News", "Amusical Night", ""))
        preveri("ni glasbeno ime: »$ime«", !OsPravila.glasbenoIme(ime))
}

private fun preizkusPolic() {
    println("\n== police razdelka: vir, ki tokrat ne odgovori ==")
    fun dop(stare: String, sveze: String) = OsPravila.dopolniPolice(stare.map { it.toString() }, sveze.map { it.toString() }) { it }.joinToString("")
    preveriEnako("prva polica manjka: ostane spredaj", "abc", dop("abc", "bc"))
    preveriEnako("srednja polica manjka: ostane med sosedama", "abc", dop("abc", "ac"))
    preveriEnako("zadnja polica manjka: ostane zadaj", "abc", dop("abc", "ab"))
    preveriEnako("nic ne manjka: sveze, kot so (tudi nova polica)", "abd", dop("ab", "abd"))
    preveriEnako("nov vrstni red svezih ostane, manjkajoca pride za prejsnjo sosedo", "cab", dop("abc", "ca"))
    preveriEnako("dve manjkata zapored", "abcd", dop("abcd", "ad"))
    preveriEnako("brez starega pogleda: sveze", "ab", dop("", "ab"))
    val sveze = listOf("a", "b")
    preveri("nic ne manjka: vrne isti seznam (klicatelj po tem loci popoln pogled)", OsPravila.dopolniPolice(listOf("a"), sveze) { it } === sveze)
}

private fun preizkusStabilnegaReda() {
    println("\n== mreza se dopolnjuje brez premescanja ==")
    fun red(naZaslonu: String, novi: String) = OsPravila.stabilenRed(naZaslonu.map { it.toString() }, novi.map { it.toString() }) { it }.joinToString("")
    preveriEnako("prazna mreza: vrstni red vira", "abcd", red("", "abcd"))
    preveriEnako("nove kartice pridejo za prikazanimi, ne vmes", "bdace", red("bd", "abcde"))
    preveriEnako("drugacen vrstni red vira prikazanih ne premesca", "abc", red("abc", "cba"))
    preveriEnako("kartica, ki je vir nima vec, izpade; ostale ostanejo na mestu", "acbd", red("axc", "bcda"))
    preveriEnako("nic od prikazanega ni vec v viru: vrstni red vira", "abc", red("xy", "abc"))
    preveriEnako("podvojen id v viru: ostane prvi", "ab", red("a", "aba").let { it.toList().distinct().joinToString("") })
}

private fun preizkusStalneGlave() {
    println("\n== kartica zdruzene vsebine se ne zamenja ==")
    fun glava(poOceni: String, naZaslonu: String) =
        OsPravila.stalnaGlava(poOceni.map { it.toString() }, naZaslonu.map { it.toString() }.toSet()) { it }.joinToString("")
    preveriEnako("nic se ni na zaslonu: najboljsa razlicica je prva", "abc", glava("abc", ""))
    preveriEnako("na zaslonu je najboljsa: red ostane", "abc", glava("abc", "a"))
    preveriEnako("na zaslonu je najslabsa: ostane kartica, ostale za njo po kakovosti", "cab", glava("abc", "c"))
    preveriEnako("pozneje potrjena boljsa razlicica kartice ne prevzame", "bac", glava("abc", "b"))
    preveriEnako("na zaslonu ni nobene od teh razlicic: najboljsa je prva", "abc", glava("abc", "xy"))
    preveriEnako("ena sama razlicica", "a", glava("a", "a"))
    val red = listOf("a", "b")
    preveri("brez spremembe vrne isti seznam", OsPravila.stalnaGlava(red, setOf("a")) { it } === red)
}

private fun preizkusLogotipa() {
    println("\n== logotip kanala ==")
    val prozoren = 0x00000000; val crn = 0xFF101010.toInt(); val bel = 0xFFF0F0F0.toInt(); val rdec = 0xFFE02020.toInt()
    fun slika(vidni: Int, barva: Int) = IntArray(256) { if (it < vidni) barva else prozoren }
    preveri("temen logotip na prozornem ozadju: svetla podlaga", OsPravila.svetlaPodlaga(slika(100, crn)))
    preveri("svetel logotip na prozornem ozadju: ostane temna", !OsPravila.svetlaPodlaga(slika(100, bel)))
    preveri("barven (rdec) logotip na prozornem ozadju: svetlost 89 -> svetla podlaga", OsPravila.svetlaPodlaga(slika(100, rdec)))
    preveri("temen logotip s svojo (neprozorno) podlago: ostane, kot je", !OsPravila.svetlaPodlaga(slika(256, crn)))
    preveri("skoraj neprozorna slika (manj kot petina prozorne): ostane", !OsPravila.svetlaPodlaga(slika(230, crn)))
    preveri("povsem prozorna slika: ostane temna", !OsPravila.svetlaPodlaga(slika(0, crn)))
    preveri("prazen seznam", !OsPravila.svetlaPodlaga(IntArray(0)))
}

private fun preizkusKanalov() {
    println("\n== kanal, ki ne stece ==")
    preveriEnako("404: kanala pri viru ni", true, OsPravila.mrtevKanal(2004, 404))
    preveriEnako("410: kanala pri viru ni vec", true, OsPravila.mrtevKanal(2004, 410))
    preveriEnako("403: vir zavraca", true, OsPravila.mrtevKanal(2004, 403))
    preveriEnako("429: vir omejuje - zacasno", false, OsPravila.mrtevKanal(2004, 429))
    preveriEnako("401: zahteva prijavo - ni dokaz", false, OsPravila.mrtevKanal(2004, 401))
    preveriEnako("503: izpad streznika - zacasno", false, OsPravila.mrtevKanal(2004, 503))
    preveriEnako("izpad omrezja te naprave ni mrtev kanal", false, OsPravila.mrtevKanal(2001, 0))
    preveriEnako("casovna omejitev ni mrtev kanal", false, OsPravila.mrtevKanal(2002, 0))
    preveriEnako("seznama ni mogoce prebrati", true, OsPravila.mrtevKanal(3002, 0))
    preveriEnako("neznan vsebnik", true, OsPravila.mrtevKanal(3003, 0))
    preveriEnako("napaka dekodirnika ni mrtev kanal (pomaga naprava v Linku)", false, OsPravila.mrtevKanal(4001, 0))
    preveriEnako("zaostanek za prenosom v zivo ni mrtev kanal", false, OsPravila.mrtevKanal(1002, 0))
    preveriEnako("imenik: imena ni, kontrola odgovori -> kanala pri viru ni", true, OsPravila.mrtevGostitelj(false, true))
    preveriEnako("imenik: imena ni, kontrola brez odgovora -> lahko je omrezje te naprave", false, OsPravila.mrtevGostitelj(false, null))
    preveriEnako("imenik: imena ni, kontrole tudi ni -> imenik te mreze ne pove resnice", false, OsPravila.mrtevGostitelj(false, false))
    preveriEnako("imenik: brez odgovora za ime -> ni dokaz", false, OsPravila.mrtevGostitelj(null, true))
    preveriEnako("imenik: ime obstaja -> kanal ni mrtev", false, OsPravila.mrtevGostitelj(true, true))
    preveriEnako("gostitelj iz sporocila sistema", "tok.primer.test",
        OsPravila.gostiteljIzNapake("Unable to resolve host \"Tok.Primer.test\": No address associated with hostname"))
    preveriEnako("sporocilo brez gostitelja", "", OsPravila.gostiteljIzNapake("timeout"))
    preveriEnako("brez sporocila", "", OsPravila.gostiteljIzNapake(null))
    preveriEnako("streznik se ne odziva, imenik dela -> kanala ta cas ni", true, OsPravila.mrtevNeodziven(zacelo = false, kontrola = true))
    preveriEnako("streznik se ne odziva, imenik molci -> lahko je omrezje te naprave", false, OsPravila.mrtevNeodziven(zacelo = false, kontrola = null))
    preveriEnako("streznik se ne odziva, kontrolnega imena ni -> ni dokaz", false, OsPravila.mrtevNeodziven(zacelo = false, kontrola = false))
    preveriEnako("izpad med predvajanjem ni mrtev kanal", false, OsPravila.mrtevNeodziven(zacelo = true, kontrola = true))
    preveriEnako("sonda: 200 -> tok je", true, OsPravila.sondaPoOdgovoru(200))
    preveriEnako("sonda: 206 -> tok je", true, OsPravila.sondaPoOdgovoru(206))
    preveriEnako("sonda: 404 -> toka ni", false, OsPravila.sondaPoOdgovoru(404))
    preveriEnako("sonda: 403 -> vir zavraca", false, OsPravila.sondaPoOdgovoru(403))
    preveriEnako("sonda: 429 -> omejitev, ne vemo", null, OsPravila.sondaPoOdgovoru(429))
    preveriEnako("sonda: 503 -> izpad streznika, ne vemo", null, OsPravila.sondaPoOdgovoru(503))
    preveriEnako("sonda: brez odgovora -> ne vemo", null, OsPravila.sondaPoOdgovoru(0))
    preveriEnako("kljuc kartice: predvajana enota brez oznake toka", "stremio|tv|kanal1|https://dodatek.test", OsPravila.kljucKanala("stremio|tv|kanal1|https://dodatek.test#123456"))
    preveriEnako("kljuc kartice: negativna oznaka toka", "stremio|tv|kanal1|https://dodatek.test", OsPravila.kljucKanala("stremio|tv|kanal1|https://dodatek.test#-98765"))
    preveriEnako("kljuc kartice: torrent ostane", "stremio|movie|tt1|x#t3", OsPravila.kljucKanala("stremio|movie|tt1|x#t3"))
    preveriEnako("kljuc kartice: uradni kanal ostane", "tv:slo1", OsPravila.kljucKanala("tv:slo1"))
    preveri("daljinec: ob napaki pas z razlago ostane (prej crn zaslon)", !OsPravila.pasSeSkrije(dotik = false, vrstaOdprta = false, tece = false, napaka = true))
    preveri("daljinec: brez napake se pas skrije kot doslej", OsPravila.pasSeSkrije(dotik = false, vrstaOdprta = false, tece = true, napaka = false))
}

/** Mreze namesto polic (lastnik, 5. in 7. 10. 2026): blok na strani z vec bloki nima drsenja v desno. */
private fun preizkusBlokov() {
    println("\n== bloki kartic: mreza namesto police ==")
    val v = OsPravila.VRSTIC_BLOKA
    preveriEnako("blok ima najvec dve vrstici", 2, v)
    // telefon, glasba: 2 v vrsto -> 4 mesta
    preveriEnako("kar gre v blok, ostane vse", 4, OsPravila.vsebineVBloku(4, 0, 2, v, false))
    preveriEnako("24 skladb, 2 v vrsto: 3 in kartica »Pokazi vse«", 3, OsPravila.vsebineVBloku(24, 0, 2, v, false))
    // telefon, plakati: 3 v vrsto -> 6 mest
    preveriEnako("12 videov, 3 v vrsto: 5 in »Pokazi vse«", 5, OsPravila.vsebineVBloku(12, 0, 3, v, false))
    // Nedavno: 5 nedavnih + »Pocisti« gre natanko v dve vrstici po tri
    preveriEnako("5 nedavnih in »Pocisti« v 3 x 2", 5, OsPravila.vsebineVBloku(5, 1, 3, v, false))
    preveriEnako("7 kartic in dejanje v 3 x 2: 4, »Pokazi vse« in dejanje", 4, OsPravila.vsebineVBloku(7, 1, 3, v, false))
    // blok s svojo kartico za vse (katalog »Pokazi vse«): druge ne dobi
    preveriEnako("blok s svojo kartico za vse: 5 in ta kartica", 5, OsPravila.vsebineVBloku(60, 1, 3, v, true))
    // televizor: 7 v vrsto
    preveriEnako("televizor, 24 skladb, 7 v vrsto: 13 in »Pokazi vse«", 13, OsPravila.vsebineVBloku(24, 0, 7, v, false))
    preveriEnako("televizor, 14 skladb: vse", 14, OsPravila.vsebineVBloku(14, 0, 7, v, false))
    // robovi: vsaj ena kartica vsebine, nikoli vec kot jih je, neveljavne mere ne podrejo
    preveriEnako("ena v vrsto: ena kartica in »Pokazi vse«", 1, OsPravila.vsebineVBloku(9, 0, 1, v, false))
    preveriEnako("vec dejanj kot mest: vsaj ena kartica vsebine", 1, OsPravila.vsebineVBloku(9, 5, 2, v, false))
    preveriEnako("prazen blok", 0, OsPravila.vsebineVBloku(0, 1, 3, v, false))
    preveriEnako("nic v vrsto se steje kot ena", 1, OsPravila.vsebineVBloku(9, 0, 0, v, false))
    for (vseh in 0..40) for (n in 1..8) for (d in 0..2) for (imaVse in listOf(false, true)) {
        val ostane = OsPravila.vsebineVBloku(vseh, d, n, v, imaVse)
        val kartic = ostane + d + (if (ostane < vseh && !imaVse) 1 else 0)
        if (ostane !in 0..vseh || (vseh > 0 && ostane == 0) || (kartic > n * v && ostane > 1)) {
            preveri("blok vseh=$vseh dejanj=$d naVrsto=$n imaVse=$imaVse -> $ostane (kartic $kartic)", false); return
        }
    }
    preveri("blok nikoli ne preseze dveh vrstic (razen z eno samo kartico vsebine) in nikoli ni prazen", true)

    println("\n== sirina kartic mreze ==")
    // Predvajalnik na telefonu: vsebina 344 dp, 2 kartici po 116 dp + rob 26 = 284 dp -> 60 dp praznega na desni
    preveriEnako("dve kartici zapolnita vrstico", 146, OsPravila.sirinaKarticeMreze(344, 2, 116, 26))
    preveriEnako("kartice, ki vrstico ze zapolnijo, ostanejo", 116, OsPravila.sirinaKarticeMreze(284, 2, 116, 26))
    preveriEnako("ozja vsebina kartic ne zmanjsa", 116, OsPravila.sirinaKarticeMreze(250, 2, 116, 26))
    preveriEnako("raztegnejo se najvec za polovico", 174, OsPravila.sirinaKarticeMreze(2000, 2, 116, 26))
    preveriEnako("tablica: pet v vrsto", 134, OsPravila.sirinaKarticeMreze(800, 5, 116, 26))
    preveriEnako("nic v vrsto se steje kot ena (ne deli z nic)", 174, OsPravila.sirinaKarticeMreze(344, 0, 116, 26))
}

private fun preizkusPodstrani() {
    println("\n== podstrani: Nazaj vrne na stran, s katere je bila odprta, na isto mesto ==")
    val p = OsPravila.Podstrani<String>()
    preveri("na zacetku ni odprta nobena", !p.odprta && p.globina == 0 && p.vrh == null && p.zapri() == null)
    // Domov (drsnik 840, izbrana kartica seznama 2) -> seznam predvajanja
    p.odpri("seznam:Moja vrsta", 840, "k:Seznami predvajanja#2")
    preveri("po odpiranju je odprta", p.odprta && p.globina == 1 && p.vrh == "seznam:Moja vrsta")
    preveriEnako("Nazaj s seznama: stran razdelka, isti polozaj in ista kartica",
        OsPravila.Podstrani.Vrnitev<String>(null, 840, "k:Seznami predvajanja#2"), p.zapri())
    preveri("po vrnitvi ni odprta nobena", !p.odprta)
    // Gnezdenje: zadetki (300, podkast 5) -> »Pokazi vse« podkastov (1200, podkast 17) -> epizode
    p.odpri("blok:Podkasti", 300, "k:Podkasti#5")
    p.odpri("epizode:Oddaja", 1200, "k:Podkasti#17")
    preveriEnako("globina 2", 2, p.globina)
    preveriEnako("Nazaj z epizod: blok podkastov na istem mestu (ne stran zadetkov)",
        OsPravila.Podstrani.Vrnitev("blok:Podkasti", 1200, "k:Podkasti#17"), p.zapri())
    preveriEnako("Nazaj iz bloka: stran zadetkov na istem mestu",
        OsPravila.Podstrani.Vrnitev<String>(null, 300, "k:Podkasti#5"), p.zapri())
    // Drugi seznam iz istega bloka: blok si zapomni zadnje mesto, stran razdelka svojega
    p.odpri("blok:Seznami", 100, null)
    p.odpri("seznam:A", 50, "k:Seznami#1")
    p.zapri()
    p.odpri("seznam:B", 70, "k:Seznami#4")
    preveriEnako("drugi seznam iz istega bloka: vrne na zadnje mesto v bloku",
        OsPravila.Podstrani.Vrnitev("blok:Seznami", 70, "k:Seznami#4"), p.zapri())
    preveriEnako("mesto na strani razdelka se pri tem ne spremeni", OsPravila.Podstrani.Vrnitev<String>(null, 100, null), p.zapri())
    // Drug razdelek ali novo iskanje: podstrani ni vec
    p.odpri("blok:X", 10, "k"); p.odpri("seznam:Y", 20, "k2")
    p.pocisti()
    preveri("po menjavi razdelka ni odprta nobena in Nazaj ne vraca nikamor", !p.odprta && p.zapri() == null)
    p.odpri("blok:Z", 0, null)
    preveriEnako("staro mesto razdelka se ne prenese", OsPravila.Podstrani.Vrnitev<String>(null, 0, null), p.zapri())

    // Mesto je kartica in njen odmik od vrha vidnega dela (stran nad njo se lahko spremeni); brez kartice velja polozaj drsnika.
    p.odpri("seznam:M", 840, "k:Seznami predvajanja#3", 212)
    preveriEnako("odmik kartice se vrne z mestom", OsPravila.Podstrani.Vrnitev<String>(null, 840, "k:Seznami predvajanja#3", 212), p.zapri())
    p.odpri("seznam:N", 5, null)
    preveriEnako("brez kartice ni odmika", OsPravila.Podstrani.NI_ODMIKA, p.zapri()?.odmik)

    println("\n== nedavno: brez podvojenih prikazov, a razlicne stvari ostanejo razlicne ==")
    preveriEnako("dve razlicici istega naslova sta en vnos",
        OsPravila.kljucNedavnega("a1", "the love hypothesis", false, false), OsPravila.kljucNedavnega("b2", "the love hypothesis", false, false))
    preveri("dve postaji z enakim imenom sta dve postaji",
        OsPravila.kljucNedavnega("r1", "radio 1", true, false) != OsPravila.kljucNedavnega("r2", "radio 1", true, false))
    preveri("dve datoteki naprave z enakim imenom sta dve datoteki",
        OsPravila.kljucNedavnega("f1", "track 01", false, true) != OsPravila.kljucNedavnega("f2", "track 01", false, true))
    preveri("brez precisenega naslova odloca oznaka", OsPravila.kljucNedavnega("x", "", false, false) != OsPravila.kljucNedavnega("y", "", false, false))
    preveri("postaja in skladba z enakim imenom nista isti vnos",
        OsPravila.kljucNedavnega("r1", "energy", true, false) != OsPravila.kljucNedavnega("s1", "energy", false, false))
}

private fun preizkusVrsteKartic() {
    println("\n== zaslon predvajanja na dotik: omejena vrsta kartic in »Pokazi vse« ==")
    // Sirina vrste, sirina kartice in razmik v istih enotah (dp).
    preveriEnako("ozek telefon pokonci (344, kartica 130): tri kartice", 3, OsPravila.karticVVrsti(344, 130, 12))
    preveriEnako("tablica lezece (1168)", 8, OsPravila.karticVVrsti(1168, 130, 12))
    preveriEnako("telefon lezece, manjse kartice (789, kartica 86)", 8, OsPravila.karticVVrsti(789, 86, 12))
    preveriEnako("tablica pokonci (688)", 5, OsPravila.karticVVrsti(688, 130, 12))
    preveriEnako("nikoli manj kot tri", 3, OsPravila.karticVVrsti(200, 130, 12))
    preveriEnako("sirina 0 (pred prvo postavitvijo) racuna ne podre", 3, OsPravila.karticVVrsti(0, 130, 12))
    preveriEnako("prazna vrsta", OsPravila.OknoVrste(0, 0, false), OsPravila.oknoVrste(0, -1, 3))
    preveriEnako("ena skladba: sama, brez »Pokazi vse«", OsPravila.OknoVrste(0, 1, false), OsPravila.oknoVrste(1, 0, 3))
    preveriEnako("toliko skladb kot mest: vse, brez »Pokazi vse«", OsPravila.OknoVrste(0, 3, false), OsPravila.oknoVrste(3, 2, 3))
    preveriEnako("vec skladb kot mest: zadnje mesto je »Pokazi vse«", OsPravila.OknoVrste(0, 2, true), OsPravila.oknoVrste(4, 0, 3))
    preveriEnako("okno se zacne pri skladbi, ki igra", OsPravila.OknoVrste(5, 3, true), OsPravila.oknoVrste(20, 5, 4))
    preveriEnako("naslednja skladba je se v oknu: kartice ostanejo na mestu", OsPravila.OknoVrste(5, 3, true), OsPravila.oknoVrste(20, 6, 4, 5))
    preveriEnako("... tudi zadnja v oknu", OsPravila.OknoVrste(5, 3, true), OsPravila.oknoVrste(20, 7, 4, 5))
    preveriEnako("skladba za oknom: okno se premakne nanjo", OsPravila.OknoVrste(8, 3, true), OsPravila.oknoVrste(20, 8, 4, 5))
    preveriEnako("skladba pred oknom (prejsnja): okno se premakne nanjo", OsPravila.OknoVrste(4, 3, true), OsPravila.oknoVrste(20, 4, 4, 5))
    preveriEnako("konec seznama: vrsta ostane polna", OsPravila.OknoVrste(17, 3, true), OsPravila.oknoVrste(20, 19, 4))
    preveriEnako("predlogi (iz njih nic ne igra): od zacetka", OsPravila.OknoVrste(0, 3, true), OsPravila.oknoVrste(12, -1, 4, 6))
    preveriEnako("staro okno, ki ga krajsi seznam nima vec: znova pri skladbi, ki igra", OsPravila.OknoVrste(1, 3, true), OsPravila.oknoVrste(6, 1, 4, 9))
    preveriEnako("manj kot dve mesti ni vrsta: racunamo z dvema", OsPravila.OknoVrste(2, 1, true), OsPravila.oknoVrste(5, 2, 1))
}

/** Imena iz kataloga glasbe pridejo HTML-kodirana (»John Dada &amp; the Weathermen«) - na kartici se kazejo kot besedilo. */
private fun preizkusHtmlImen() {
    preveriEnako("&amp;", "John Dada & the Weathermen", OsPravila.htmlBesedilo("John Dada &amp; the Weathermen"))
    preveriEnako("narekovaji", "\"Hi\" 'there' <3", OsPravila.htmlBesedilo("&quot;Hi&quot; &#39;there&#039; &lt;3"))
    preveriEnako("stevilske", "é & ÿ", OsPravila.htmlBesedilo("&#233; &#x26; &#xFF;"))
    preveriEnako("enkrat", "&amp;", OsPravila.htmlBesedilo("&amp;amp;"))
    preveriEnako("brez entitet", "AC/DC & Co", OsPravila.htmlBesedilo("AC/DC & Co"))
    preveriEnako("neznana ostane", "&nbspx &foo;", OsPravila.htmlBesedilo("&nbspx &foo;"))
    preveriEnako("neveljavna stevilka ostane", "&#0; &#xD800; &#99999999;", OsPravila.htmlBesedilo("&#0; &#xD800; &#99999999;"))
}

/** Naloga 55: mreza z 5143 naslovi je ob vsakem izrisu preverila vse; zdaj le okno in rezervo, dopolni se brez utripa. */
private fun preizkusOknaMreze() {
    println("\n== mreza: le okno + rezerva, dopolnjevanje po straneh ==")
    val vsi = (0 until 5143).map { "t$it" }
    var klicev = 0
    val p = OsPravila.prvih(vsi, 130) { klicev++; it.removePrefix("t").toInt() % 2 == 1 }
    preveriEnako("izbranih je toliko, kot jih okno + rezerva potrebuje", 130, p.izbrani.size)
    preveriEnako("vrstni red ostane", "t0,t2,t4", p.izbrani.take(3).joinToString(","))
    preveri("pregledani so le naslovi do zadnjega izbranega, ne vseh 5143 (klicev=$klicev)", klicev == 259 && p.pregledanih == 259)
    preveri("za izbranimi je se kaj", p.prirezano)
    val vse = OsPravila.prvih(listOf("a", "b"), 10) { false }
    preveri("kratek seznam: vse, nic prirezano", vse.izbrani == listOf("a", "b") && !vse.prirezano && vse.pregledanih == 2)
    preveri("tocno toliko, kolikor je: ni prirezano", !OsPravila.prvih(listOf("a", "b"), 2) { false }.prirezano)

    preveri("odtis: enak seznam, enak odtis", OsPravila.odtis(listOf("a", "b")) { it } == OsPravila.odtis(listOf("a", "b")) { it })
    preveri("odtis: vrstni red steje", OsPravila.odtis(listOf("a", "b")) { it } != OsPravila.odtis(listOf("b", "a")) { it })
    preveri("odtis: dodan naslov", OsPravila.odtis(listOf("a")) { it } != OsPravila.odtis(listOf("a", "b")) { it })

    fun risi(na: String, novi: String, caka: Boolean, od: Long) =
        OsPravila.risiMrezoZdaj(na.map { it.toString() }, novi.map { it.toString() }, 3, caka, od, 3_000)
    preveri("enaka mreza: ne risemo", !risi("abc", "abc", true, 10_000))
    preveri("prvi zaslon: nova kartica takoj", risi("ab", "abc", true, 10))
    preveri("po prvem zaslonu: dodane pocakajo, dokler okno se caka", !risi("abc", "abcd", true, 1_000))
    preveri("... najvec 3 s", risi("abc", "abcd", true, 3_000))
    preveri("okno preverjeno: dopolnimo takoj", risi("abc", "abcd", false, 10))
    preveri("kartica izgine: takoj (ne kazemo nepredvajljivega)", risi("abcd", "abd", true, 10))
    preveri("kartica bi se premaknila: takoj", risi("abcd", "abdce", true, 10))
}

/** Naloga 56: naslovnica na disku najvec 280 px po daljsi stranici; plakat zunaj zaslona sprosti sliko. */
private fun preizkusNaslovnic() {
    println("\n== naslovnice: 280 px in samo plakati na zaslonu ==")
    preveriEnako("pokoncen plakat 1000x1500 -> 186x280", 186 to 280, OsPravila.meraNaslovnice(1000, 1500, 280))
    preveriEnako("lezeca 1920x1080 -> 280x157", 280 to 157, OsPravila.meraNaslovnice(1920, 1080, 280))
    preveriEnako("majhna ostane", 200 to 300, OsPravila.meraNaslovnice(200, 300, 300))
    preveriEnako("tocno 280 ostane", 187 to 280, OsPravila.meraNaslovnice(187, 280, 280))
    preveriEnako("ozka ne pade na 0", 1 to 280, OsPravila.meraNaslovnice(10, 5000, 280))
    preveri("neveljavne mere", OsPravila.meraNaslovnice(0, 100, 280) == null)
    // Zaslon 0..1000, vrsta 300 px, rezerva ena vrsta.
    preveri("na zaslonu", OsPravila.vVidnem(400, 300, 0, 1000, 300))
    preveri("vrsta tik pod zaslonom (rezerva)", OsPravila.vVidnem(1200, 300, 0, 1000, 300))
    preveri("dve vrsti pod zaslonom: sprosti", !OsPravila.vVidnem(1400, 300, 0, 1000, 300))
    preveri("vrsta nad zaslonom v rezervi", OsPravila.vVidnem(1700, 300, 2000, 3000, 300))
    preveri("dalec nad zaslonom: sprosti", !OsPravila.vVidnem(1000, 300, 2000, 3000, 300))
}

/** Naloga 77: naslovnice brez alfe v RGB_565, napredek najvec na 5 s, redko spreminjajoca se vrednost ne na vsak tik. */
private fun preizkusPoraboRama() {
    println("\n== poraba RAM in procesorja: RGB_565, razmik zapisa, obcasna vrednost ==")
    preveriEnako("186x280 ARGB = 208.320 B", 208_320, OsPravila.bajtovNaslovnice(186, 280, true))
    preveriEnako("186x280 RGB_565 = polovica", 104_160, OsPravila.bajtovNaslovnice(186, 280, false))
    preveriEnako("neveljavne mere = 0", 0, OsPravila.bajtovNaslovnice(0, 280, false))
    preveriEnako("ogromne mere ne prekoracijo Int", Int.MAX_VALUE, OsPravila.bajtovNaslovnice(100_000, 100_000, true))
    val argb = OsPravila.naslovnicVProracunu(24 * 1024 * 1024, 186, 280, true)
    val rgb = OsPravila.naslovnicVProracunu(18 * 1024 * 1024, 186, 280, false)
    preveri("24 MB ARGB drzi manj kot 166 plakatov ($argb)", argb < 166)
    preveri("18 MB RGB_565 drzi vse 166 plakatov ($rgb)", rgb >= 166)
    preveriEnako("neveljavne mere: nic plakatov", 0, OsPravila.naslovnicVProracunu(1000, 0, 0, true))

    preveri("zapis: se nikoli", OsPravila.jeCasZaZapis(-1, 1_000, 5_000))
    preveri("zapis: prezgodaj", !OsPravila.jeCasZaZapis(10_000, 11_000, 5_000))
    preveri("zapis: tocno po razmiku", OsPravila.jeCasZaZapis(10_000, 15_000, 5_000))
    preveri("zapis: ura gre nazaj", OsPravila.jeCasZaZapis(10_000, 500, 5_000))

    var klicev = 0
    val o = OsPravila.Obcasna<Boolean>(15_000)
    preveri("obcasna: prvi klic izracuna", o.daj(1_000) { klicev++; true } && klicev == 1)
    preveri("obcasna: drugi klic znotraj veljavnosti ne izracuna", o.daj(10_000) { klicev++; false } && klicev == 1)
    preveri("obcasna: po izteku izracuna znova", !o.daj(16_000) { klicev++; false } && klicev == 2)
    o.pozabi()
    preveri("obcasna: po pozabi takoj znova", o.daj(16_100) { klicev++; true } && klicev == 3)
    preveri("obcasna: ura nazaj izracuna znova", o.daj(100) { klicev++; false }.not() && klicev == 4)
    val prazna = OsPravila.Obcasna<List<Int>>(1_000)
    preveriEnako("obcasna: tudi prazen seznam se pomni", 1, run { prazna.daj(0) { klicev = 0; emptyList() }; prazna.daj(10) { klicev = 99; emptyList() }; if (klicev == 0) 1 else 0 })
}

/** Naloga 55b: naslednje strani takoj, prva stran strogo; izbrana kartica ne izgine pod izbiro. */
private fun preizkusNaslednjihStrani() {
    println("\n== Nalozi vec: takoj pokazi, preveri v ozadju ==")
    preveri("prva stran: nepreverjen se ne pokaze", !OsPravila.vOknuViden(5, 100, false, true))
    preveri("prva stran: preverjen se pokaze", OsPravila.vOknuViden(5, 100, false, false))
    preveri("naslednja stran: nepreverjen se pokaze takoj", OsPravila.vOknuViden(100, 100, false, true))
    preveri("ze na zaslonu (premaknjen na prvo stran): ostane", OsPravila.vOknuViden(3, 100, true, true))
    preveri("navaden katalog: vse strogo", !OsPravila.vOknuViden(500, Int.MAX_VALUE, false, true))
    fun drzi(vidni: String, na: String, izbran: String?) =
        OsPravila.obdrziIzbrano(vidni.map { it.toString() }, na.map { it.toString() }, izbran, { it }) { it }.let { (l, d) -> l.joinToString("") + "/" + d }
    preveriEnako("izbrana izpadla kartica ostane na svojem mestu", "abcd/true", drzi("abd", "abcd", "c"))
    preveriEnako("brez izbire izpade", "abd/false", drzi("abd", "abcd", null))
    preveriEnako("izbrana je se vidna: nic", "abcd/false", drzi("abcd", "abcd", "c"))
    preveriEnako("izbrana ni bila na zaslonu: nic", "ab/false", drzi("ab", "ab", "x"))
    preveriEnako("zadnja izpadla ostane na koncu", "abc/true", drzi("ab", "abc", "c"))
}

/** Naloga 57c: naslednja stran kataloga uporabnikovega dodatka (ne samo Cinemete s 50+ vnosi na stran). */
private fun preizkusStraniKataloga() {
    println("\n== Nalozi vec: strani katalogov uporabnikovih dodatkov ==")
    preveri("katalog s »skip« in majhno stranjo (12): je se stran", OsPravila.katalogImaVec(12, true, 20))
    preveri("katalog s »skip«, prazna stran: konec", !OsPravila.katalogImaVec(0, true, 20))
    preveri("brez oznake, polna stran: morda se kaj", OsPravila.katalogImaVec(20, false, 20))
    preveri("brez oznake, nepolna stran: konec (kot doslej)", !OsPravila.katalogImaVec(12, false, 20))
    preveri("izpad (-1) ni stran", !OsPravila.katalogImaVec(-1, true, 20) && !OsPravila.katalogImaVec(-1, false, 20))
}

/** Naloga 57d: dodatek, ki ne odgovarja - ob vnosu se en poskus, pozneje preskok brez brisanja. */
private fun preizkusOdzivaDodatka() {
    println("\n== dodatek, ki ne odgovarja ==")
    val t = 1_800_000_000_000L
    var o: OsPravila.Odziv? = null
    o = OsPravila.poOdzivu(o, false, t)
    o = OsPravila.poOdzivu(o, false, t + 60_000)
    preveri("dva izpada: se ne preskocimo", !OsPravila.neOdgovarja(o, t + 60_000))
    o = OsPravila.poOdzivu(o, false, t + 120_000)
    preveri("trije izpadi v 10 min: preskok", OsPravila.neOdgovarja(o, t + 120_000))
    preveri("... 30 min", OsPravila.neOdgovarja(o, t + 120_000 + 29 * 60_000) && !OsPravila.neOdgovarja(o, t + 120_000 + 30 * 60_000))
    preveri("odgovor oznako takoj pobrise", !OsPravila.neOdgovarja(OsPravila.poOdzivu(o, true, t + 130_000), t + 130_000))
    var p: OsPravila.Odziv? = null
    p = OsPravila.poOdzivu(p, false, t); p = OsPravila.poOdzivu(p, false, t + 11 * 60_000); p = OsPravila.poOdzivu(p, false, t + 12 * 60_000)
    preveri("izpadi, razprseni cez vec kot 10 min: ni preskoka", !OsPravila.neOdgovarja(p, t + 12 * 60_000))
    // Naloga 73: polodprto stanje - po preskoku en poskusni zahtevek; izpad spet odpre, odgovor zapre.
    val konec = t + 120_000 + 30 * 60_000L
    preveriEnako("odprto: zahtevek ne sme", null, OsPravila.dovoliZahtevo(o, t + 130_000))
    val poskus = OsPravila.dovoliZahtevo(o, konec)
    preveri("polodprto: prvi zahtevek sme (poskus)", poskus != null && poskus.poskusOd == konec)
    preveriEnako("polodprto: drugi hkratni zahtevek ne sme", null, OsPravila.dovoliZahtevo(poskus, konec + 1_000))
    preveri("polodprto s poskusom v teku: oznaka »ne odgovarja«", OsPravila.neOdgovarja(poskus, konec + 1_000))
    preveri("poskus, ki visi, po 30 s spusti naslednjega", OsPravila.dovoliZahtevo(poskus, konec + OsPravila.POSKUS_MS) != null)
    preveri("izpad poskusa: spet odprto 30 min", OsPravila.neOdgovarja(OsPravila.poOdzivu(poskus, false, konec + 2_000), konec + 29 * 60_000))
    preveriEnako("odgovor poskusa: zaprto", OsPravila.Odziv(), OsPravila.poOdzivu(poskus, true, konec + 2_000))
    preveri("zaprto: zahtevek sme", OsPravila.dovoliZahtevo(null, t) != null)
    // Naloga 73: politika po vrsti zahtevka.
    val tokKlik = OsPravila.politikaZahteve("https://d.test/stream/movie/tt1.json", false)
    val tokOzadje = OsPravila.politikaZahteve("https://d.test/stream/movie/tt1.json", true)
    val katalog = OsPravila.politikaZahteve("https://d.test/catalog/tv/all.json", false)
    preveri("tok ob kliku: najvisja prednost", tokKlik.prednost < katalog.prednost && tokKlik.prednost < tokOzadje.prednost)
    preveri("katalog: daljsa veljavnost kot tok", katalog.veljaMs > tokKlik.veljaMs)
    preveri("tok ob kliku: daljsi rok kot katalog v ozadju", tokKlik.rokMs > katalog.rokMs)
    preveriEnako("meta: 1 h", 60 * 60_000L, OsPravila.politikaZahteve("https://d.test/meta/movie/tt1.json", false).veljaMs)
    preveriEnako("manifest z diska: svez", "uporabi", OsPravila.manifestZDiska(60_000L))
    preveriEnako("manifest z diska: star 7 h - uporabi in osvezi", "uporabi+osvezi", OsPravila.manifestZDiska(7 * 3_600_000L))
    preveriEnako("manifest z diska: prestar", "prenesi", OsPravila.manifestZDiska(8 * 24 * 3_600_000L))
    preveriEnako("manifesta ni", "prenesi", OsPravila.manifestZDiska(-1L))
    preveri("ob vnosu: 503 in brez odgovora se enkrat, 404 ne", OsPravila.vnosZnova(503) && OsPravila.vnosZnova(0) && !OsPravila.vnosZnova(404))
}

/** Naloga 83: Music - seznami na vrhu, jezik koncertov. */
private fun preizkusGlasbe83() {
    println("\n== naloga 83: Music ==")
    val sz = listOf("" to 5L, "Spotify" to 1L, "YouTube" to 3L, "" to 9L, "spotify" to 2L)
    preveriEnako("83: uvozeni najprej, od najnovejsega", listOf(2, 4, 1, 3, 0), OsPravila.seznamiNaVrhuGlasbe(sz, 10))
    preveriEnako("83: najvec", listOf(2, 4), OsPravila.seznamiNaVrhuGlasbe(sz, 2))
    preveriEnako("83: prazno", emptyList<Int>(), OsPravila.seznamiNaVrhuGlasbe(emptyList(), 5))
    preveriEnako("83: negativna meja", emptyList<Int>(), OsPravila.seznamiNaVrhuGlasbe(sz, -1))
    preveriEnako("83: YouTube s samimi posnetki gre v Music", true, OsPravila.seznamZaGlasbo("YouTube", true))
    preveriEnako("83: lasten seznam samih videov ne", false, OsPravila.seznamZaGlasbo("", true))
    preveriEnako("83: lasten seznam s skladbami da", true, OsPravila.seznamZaGlasbo("", false))
    preveriEnako("83: brez izbire jezika vse", true, OsPravila.jezikGlasbeUstreza("", "", false))
    preveriEnako("83: koncert brez jezika ostane", true, OsPravila.jezikGlasbeUstreza("", "sl", true))
    preveriEnako("83: Jamendo brez jezika ne", false, OsPravila.jezikGlasbeUstreza("", "sl", false))
    preveriEnako("83: drug jezik ne", false, OsPravila.jezikGlasbeUstreza("en", "sl", true))
    preveriEnako("83: isti jezik da", true, OsPravila.jezikGlasbeUstreza("sl", "sl", false))
}

/** Naloga 84: Radio - rok imenikov, vrstni red streznikov. */
private fun preizkusRadia84() {
    println("\n== naloga 84: Radio ==")
    preveriEnako("84: prva stran brez mreze na zaslonu - 3 s", 3_000L, OsPravila.rokImenikaRadia(true, false))
    preveriEnako("84: prva stran z mrezo z diska - na vse", 25_000L, OsPravila.rokImenikaRadia(true, true))
    preveriEnako("84: naslednja stran - na vse", 25_000L, OsPravila.rokImenikaRadia(false, false))
    val s = listOf("a", "b", "c")
    preveriEnako("84: brez dobrega - po vrsti", s, OsPravila.vrstniRedStreznikov(s, null))
    preveriEnako("84: zadnji dober najprej", listOf("b", "a", "c"), OsPravila.vrstniRedStreznikov(s, "b"))
    preveriEnako("84: neznan dober se ne doda", s, OsPravila.vrstniRedStreznikov(s, "x"))
}

/** Naloga 86: popravki pregleda 85 - cakanje po kosih (M3), velikost strani kanalov (M4), dnevnik zavrnjenega vira (M6). */
private fun preizkusPregleda86() {
    println("\n== naloga 86: popravki pregleda 85 ==")
    var ura = 0L
    val kosi = ArrayList<Long>()
    val nic = OsPravila.cakajPoKoscih<String>(3_500, ura = { ura }, prekinjen = { false }) { ms -> kosi += ms; ura += ms; null }
    preveriEnako("86 M3: brez izida do roka - null", null, nic)
    preveriEnako("86 M3: kosi po 1 s, zadnji ostanek", listOf(1_000L, 1_000L, 1_000L, 500L), kosi)
    ura = 0L; kosi.clear()
    var preklic = false
    val prekinjeno = OsPravila.cakajPoKoscih<String>(30_000, ura = { ura }, prekinjen = { preklic }) { ms -> kosi += ms; ura += ms; if (kosi.size == 2) preklic = true; null }
    preveriEnako("86 M3: preklic konca cakanje po trenutnem kosu", null to 2, prekinjeno to kosi.size)
    ura = 0L
    preveriEnako("86 M3: izid takoj", "ok", OsPravila.cakajPoKoscih(30_000, ura = { ura }, prekinjen = { false }) { "ok" })
    preveriEnako("86 M3: ze preklicano - brez poskusa", null, OsPravila.cakajPoKoscih<String>(30_000, ura = { ura }, prekinjen = { true }) { error("ne sme") })
    preveriEnako("86 M3: rok 0 - brez poskusa", null, OsPravila.cakajPoKoscih<String>(0, ura = { ura }, prekinjen = { false }) { error("ne sme") })

    preveriEnako("86 M4: stran 1000 kartic", 1001, OsPravila.velikostStraniKanalov(1000))
    preveriEnako("86 M4: prazna stran steje 1", 1, OsPravila.velikostStraniKanalov(0))
    preveriEnako("86 M4: 10 polnih strani je meja", true, 10 * OsPravila.velikostStraniKanalov(1000) > OsPravila.NAJVEC_KARTIC_STRANI_KANALOV)

    val geslo = "http://x.tv/get.php?username=ana&password=Skrivno123"
    val o = OsPravila.opisZavrnjenegaVnosa(geslo)
    preveriEnako("86 M6: ni gesla ne uporabnika ne gostitelja", false, "Skrivno" in o || "ana" in o || "x.tv" in o || "get.php" in o)
    preveriEnako("86 M6: razredi", "dolzina=${geslo.length}, crk=39, stevk=3, locil=10, presledkov=0, kontrolnih=0, ne-ASCII=0", o)
    preveriEnako("86 M6: nevidni znak tipkovnice in nbsp", "dolzina=12, crk=10, stevk=0, locil=0, presledkov=0, kontrolnih=0, ne-ASCII=2 (FORMAT,PRESLEDEK)",
        OsPravila.opisZavrnjenegaVnosa("tunein\u200Bcom\u00A0x"))
    preveriEnako("86 M6: crka s kljukico je CRKA, brez kode", "dolzina=5, crk=3, stevk=0, locil=0, presledkov=0, kontrolnih=1, ne-ASCII=1 (CRKA)",
        OsPravila.opisZavrnjenegaVnosa("abc\t\u010D"))
    preveriEnako("86 M6: prazen", "dolzina=0, crk=0, stevk=0, locil=0, presledkov=0, kontrolnih=0, ne-ASCII=0", OsPravila.opisZavrnjenegaVnosa(""))
}

/** Naloga 88: Live TV prvo risanje (varovalka), rok tokov kanala, dnevnik kataloga, napis prazne Glasbe. */
private fun preizkusTv88() {
    println("\n== naloga 88: popravki pred izdajo TV ==")
    preveriEnako("88: dopolnitev caka na prvo risanje", true, OsPravila.odloziDopolnitev(1_000, 1_500))
    preveriEnako("88: po 2 s ne caka vec", false, OsPravila.odloziDopolnitev(1_000, 3_000))
    preveriEnako("88: ura nazaj ne caka", false, OsPravila.odloziDopolnitev(5_000, 1_000))
    preveriEnako("88: kanal v zivo 7 s (sporocilo v 8 s)", 7_000L, OsPravila.rokTokovMs(true))
    preveriEnako("88: film 12 s kot doslej", 12_000L, OsPravila.rokTokovMs(false))
    preveriEnako("88: katalog z vnosi", "30 vnosov", OsPravila.izidKataloga(30, 200, false))
    preveriEnako("88: katalog prazen", "prazno (dodatek je odgovoril brez vnosov)", OsPravila.izidKataloga(0, 200, false))
    preveriEnako("88: katalog 404", "prazno (dodatek je odgovoril brez vnosov, 404)", OsPravila.izidKataloga(0, 404, false))
    preveriEnako("88: varovalka", "ni vprasan (varovalka: dodatek zapored ne odgovarja)", OsPravila.izidKataloga(null, 0, true))
    preveriEnako("88: ni odgovora", "ni odgovora (cas, omrezje)", OsPravila.izidKataloga(null, 0, false))
    preveriEnako("88: omejitev", "omejitev dodatka (HTTP 429)", OsPravila.izidKataloga(null, 429, false))
    preveriEnako("88: napaka", "napaka (HTTP 500)", OsPravila.izidKataloga(null, 500, false))
    preveriEnako("88: kartice - brez napisa", OsPravila.OpisMreze.NIC, OsPravila.opisPrazneMreze(true, true, true, true, true))
    preveriEnako("88: nalagam", OsPravila.OpisMreze.NALAGAM, OsPravila.opisPrazneMreze(false, true, false, false, true))
    preveriEnako("88: seznami na zaslonu - ne »Nothing found«", OsPravila.OpisMreze.SAMO_SEZNAMI, OsPravila.opisPrazneMreze(false, false, false, false, true))
    preveriEnako("88: seznami tudi brez virov", OsPravila.OpisMreze.SAMO_SEZNAMI, OsPravila.opisPrazneMreze(false, false, false, true, true))
    preveriEnako("88: izbran jezik ima prednost", OsPravila.OpisMreze.JEZIK, OsPravila.opisPrazneMreze(false, false, true, false, true))
    preveriEnako("88: brez virov", OsPravila.OpisMreze.BREZ_VIROV, OsPravila.opisPrazneMreze(false, false, false, true, false))
    preveriEnako("88: res prazno", OsPravila.OpisMreze.PRAZNO, OsPravila.opisPrazneMreze(false, false, false, false, false))
}

/** Naloga 89: posnetki uvozenih seznamov vnaprej - vrstni red in ponovni poskus. */
private fun preizkusGlasbe89() {
    println("\n== naloga 89: posnetki seznamov vnaprej ==")
    val dan = 24 * 3_600_000L
    preveriEnako("89: se nikoli ni odpovedalo - iscemo", true, OsPravila.poskusiZnovaIskanje(null, 10 * dan))
    preveriEnako("89: odpovedalo pred 3 dnevi - ne", false, OsPravila.poskusiZnovaIskanje(7 * dan, 10 * dan))
    preveriEnako("89: po 7 dneh znova", true, OsPravila.poskusiZnovaIskanje(3 * dan, 10 * dan))
    preveriEnako("89: ura nazaj - znova", true, OsPravila.poskusiZnovaIskanje(11 * dan, 10 * dan))
    val seznami = listOf(listOf("a1", "a2", "a3"), listOf("b1"), listOf("c1", "c2"))
    preveriEnako("89: izmenicno po polozaju", listOf("a1", "b1", "c1", "a2", "c2", "a3"), OsPravila.vrstniRedIskanja(seznami, 10))
    preveriEnako("89: najvec", listOf("a1", "b1", "c1", "a2"), OsPravila.vrstniRedIskanja(seznami, 4))
    preveriEnako("89: ista skladba v dveh seznamih enkrat", listOf("x", "y"), OsPravila.vrstniRedIskanja(listOf(listOf("x", "y"), listOf("x")), 10))
    preveriEnako("89: brez seznamov", emptyList<String>(), OsPravila.vrstniRedIskanja(emptyList(), 10))
}

fun main() {
    println("Preizkus pravil Safeer OS")
    preizkusHtmlImen()
    preizkusBlokov()
    preizkusPodstrani()
    preizkusVrsteKartic()
    preizkusKanalov()
    preizkusLogotipa()
    preizkusStabilnegaReda()
    preizkusOknaMreze()
    preizkusNaslovnic()
    preizkusPoraboRama()
    preizkusNaslednjihStrani()
    preizkusStraniKataloga()
    preizkusOdzivaDodatka()
    preizkusStalneGlave()
    preizkusPolic()
    preizkusGlasbenegaImena()
    preizkusTipkMenija()
    preizkusIkonNaprav()
    preizkusOpisaNaprav()
    preizkusPovezave()
    preizkusPodnapisovDodatkov()
    preizkusPostaj()
    preizkusImenikaPostaj()
    preizkusSeznamaPostaj()
    preizkusJezikaPoDrzavah()
    preizkusVrsteIzMeta()
    preizkusJezikaKataloga()
    preizkusMer()
    preizkusNadaljuj()
    preizkusIkon()
    preizkusZaslona()
    pasMedVideom()
    preizkusPregledaKanalov()
    preizkusKoncertovInIskanja()
    preizkusNadomestneVsebine()
    preizkusZaklenjenegaDodatka()
    preizkusOdklepa()
    preizkusIskanjaYoutubio()
    preizkusGlasbe83()
    preizkusRadia84()
    preizkusPregleda86()
    preizkusTv88()
    preizkusTv90b()
    preizkusGlasbe89()
    preizkusKatalogov90()
    preizkusNamestitveDodatka94()
    preizkusPonovnePovezave95()
    preizkusNadomestneSlike97()
    preizkusDokazaNadomestka100()
    preizkusPrikazaVZivo104()
    preizkusOdKdaj113()
    preizkusScenarijevVZivo114()
    preizkusNarascajocegaZamika115()
    preizkusPlacljivega120()
    println()
    if (napak == 0) println("Vse v redu.") else { println("Napak: $napak"); System.exit(1) }
}

private fun preizkusKatalogov90() {
    println("\n== katalogi glasbe s prednostjo (naloga 90a) ==")
    val vsi = (1..12).map { "a$it" } + "koncerti"
    val izbrani = OsPravila.katalogiZPrednostjo(vsi, 10) { it == "koncerti" }
    preveri("prednostni katalog je med desetimi", "koncerti" in izbrani && izbrani.size == 10)
    preveri("ostali ohranijo vrstni red", izbrani.drop(1) == (1..9).map { "a$it" })
    preveri("brez prednostnih: prvih deset", OsPravila.katalogiZPrednostjo(vsi.dropLast(1), 10) { false } == (1..10).map { "a$it" })
    preveri("manj kot n: vsi", OsPravila.katalogiZPrednostjo(listOf("x", "koncerti"), 10) { it == "koncerti" }.toSet() == setOf("x", "koncerti"))
}

private fun preizkusNamestitveDodatka94() {
    println("\n== namestitev dodatka iz Spleta (naloga 94) ==")
    val m = "https://watchio-addon.pages.dev/manifest.json"
    preveriEnako("stremio:// -> https, ista pot", m, OsPravila.naslovDodatka("stremio://watchio-addon.pages.dev/manifest.json"))
    preveriEnako("stremio:// s potjo nastavitev", "https://a.test/abc%7B%22x%22%7D/manifest.json",
        OsPravila.naslovDodatka("stremio://a.test/abc%7B%22x%22%7D/manifest.json"))
    preveriEnako("spletni Stremio, kodiran naslov", m, OsPravila.naslovDodatka("https://web.stremio.com/#/addons?addon=https%3A%2F%2Fwatchio-addon.pages.dev%2Fmanifest.json"))
    preveriEnako("spletni Stremio, nekodiran naslov (kot stremio-addons.net)", m, OsPravila.naslovDodatka("https://web.stremio.com/#/addons?addon=$m"))
    preveriEnako("spletni Stremio, addon ni prvi parameter", m, OsPravila.naslovDodatka("https://app.strem.io/#/addons?type=all&addon=$m"))
    preveriEnako("spletni Stremio z naslovom stremio://", m, OsPravila.naslovDodatka("https://web.stremio.com/#/addons?addon=stremio%3A%2F%2Fwatchio-addon.pages.dev%2Fmanifest.json"))
    preveriEnako("neposreden manifest.json", m, OsPravila.naslovDodatka(m))
    preveriEnako("manifest.json z delom za #", m, OsPravila.naslovDodatka("$m#x"))
    preveriEnako("manifest.json na http", "http://192.168.1.5:7000/manifest.json", OsPravila.naslovDodatka("http://192.168.1.5:7000/manifest.json"))
    preveriEnako("MANIFEST.JSON z velikimi crkami", "https://a.test/MANIFEST.JSON", OsPravila.naslovDodatka("https://a.test/MANIFEST.JSON"))
    preveriEnako("stran dodatka ni namestitev", null, OsPravila.naslovDodatka("https://stremio-addons.net/addons/watchio-tv"))
    preveriEnako("spletni Stremio brez addon=", null, OsPravila.naslovDodatka("https://web.stremio.com/#/addons"))
    preveriEnako("spletni Stremio, druga stran", null, OsPravila.naslovDodatka("https://web.stremio.com/#/discover?addon=$m"))
    preveriEnako("spletni Stremio, addon ni manifest", null, OsPravila.naslovDodatka("https://web.stremio.com/#/addons?addon=https%3A%2F%2Fa.test%2Fstran"))
    preveriEnako("spletni Stremio v spletnem Stremiu ni namestitev", null,
        OsPravila.naslovDodatka("https://web.stremio.com/#/addons?addon=https%3A%2F%2Fweb.stremio.com%2Fmanifest.json"))
    preveriEnako("stremio:// brez gostitelja (globoka povezava aplikacije)", null, OsPravila.naslovDodatka("stremio:///detail/movie/tt1"))
    preveriEnako("stremio:// brez manifest.json", null, OsPravila.naslovDodatka("stremio://a.test/configure"))
    preveriEnako("manifest.json samo v poizvedbi ni manifest", null, OsPravila.naslovDodatka("https://a.test/go?u=/manifest.json"))
    preveriEnako("druga shema", null, OsPravila.naslovDodatka("intent://a.test/manifest.json#Intent;scheme=https;end"))
    preveriEnako("prazno", null, OsPravila.naslovDodatka(""))
}

private fun preizkusPonovnePovezave95() {
    println("\n== kanal v zivo: ponovna povezava (naloga 95) ==")
    val n = OsPravila.PONOVITEV_V_ZIVO_NAJVEC
    preveriEnako("v zivo, progresivno, konec po 5 s - ponovi (1)", 1, OsPravila.ponovnaPovezavaVZivo(true, true, true, 0, 5_000))
    preveriEnako("novi podatki po prejsnjih ponovitvah - stevec znova 1", 1, OsPravila.ponovnaPovezavaVZivo(true, true, true, 3, 4_800))
    preveriEnako("brez novih podatkov - naslednja zaporedna", 3, OsPravila.ponovnaPovezavaVZivo(true, true, true, 2, 300))
    preveriEnako("zadnja dovoljena brez podatkov", n, OsPravila.ponovnaPovezavaVZivo(true, true, true, n - 1, 0))
    preveriEnako("prevec ponovitev brez podatkov - konec", 0, OsPravila.ponovnaPovezavaVZivo(true, true, true, n, 0))
    preveriEnako("prevec ponovitev, a spet podatki - ponovi", 1, OsPravila.ponovnaPovezavaVZivo(true, true, true, n, OsPravila.NOVI_PODATKI_V_ZIVO_MS))
    preveriEnako("HLS - ne", 0, OsPravila.ponovnaPovezavaVZivo(true, false, true, 0, 5_000))
    preveriEnako("film (ni v zivo) - ne", 0, OsPravila.ponovnaPovezavaVZivo(false, true, true, 0, 5_000))
    preveriEnako("se ni zacelo (napaka pri prvi povezavi) - ne", 0, OsPravila.ponovnaPovezavaVZivo(true, true, false, 0, 0))
    preveriEnako("negativen stevec je 0", 1, OsPravila.ponovnaPovezavaVZivo(true, true, true, -3, 0))
}

private fun preizkusNadomestneSlike97() {
    println("\n== kanal v zivo: nadomestna slika in meja ponovitev (naloga 97) ==")
    preveriEnako("5,0/5,0/5,0 s - nadomestek", true, OsPravila.nadomestnaSlikaVZivo(listOf(5_008, 5_008, 5_008)))
    preveriEnako("5,0/5,2/4,9 s - nadomestek (razlika < 10 %)", true, OsPravila.nadomestnaSlikaVZivo(listOf(5_000, 5_200, 4_900)))
    preveriEnako("60/45/80 s - pravi prenos", false, OsPravila.nadomestnaSlikaVZivo(listOf(60_000, 45_000, 80_000)))
    preveriEnako("5,0/30 s - pravi prenos", false, OsPravila.nadomestnaSlikaVZivo(listOf(5_000, 30_000)))
    preveriEnako("5,0/5,0/30 s - zadnji dolg, pravi", false, OsPravila.nadomestnaSlikaVZivo(listOf(5_000, 5_000, 30_000)))
    preveriEnako("samo dva kratka - se ne vemo", false, OsPravila.nadomestnaSlikaVZivo(listOf(5_000, 5_000)))
    preveriEnako("kratki, a razlicni (3/5/8 s) - ne", false, OsPravila.nadomestnaSlikaVZivo(listOf(3_000, 5_000, 8_000)))
    preveriEnako("prej pravi, zdaj trije enaki kratki - nadomestek", true, OsPravila.nadomestnaSlikaVZivo(listOf(600_000, 5_000, 5_000, 5_000)))
    preveriEnako("nic predvajano - ne", false, OsPravila.nadomestnaSlikaVZivo(listOf(0, 0, 0)))
    val zdaj = 1_000_000L
    val deset = (1..10).map { zdaj - it * 5_000L }
    preveriEnako("10 ponovitev v 2 min - konec, ceprav so novi podatki", 0,
        OsPravila.ponovnaPovezavaVZivo(true, true, true, 1, 5_008, deset, zdaj))
    preveriEnako("9 ponovitev v 2 min - se ponovi", 1, OsPravila.ponovnaPovezavaVZivo(true, true, true, 1, 5_008, deset.drop(1), zdaj))
    preveriEnako("stare ponovitve (pred 2 min) ne stejejo", 1,
        OsPravila.ponovnaPovezavaVZivo(true, true, true, 1, 5_008, (1..10).map { zdaj - 130_000L - it }, zdaj))
}

private fun preizkusDokazaNadomestka100() {
    println("\n== kanal v zivo: vsebinski dokaz nadomestne slike, zamik ponovitve (naloga 100) ==")
    val kb64 = OsPravila.NADOMESTEK_BAJTOV
    val r = OsPravila.NADOMESTEK_RAZMIK_MS
    preveriEnako("enaka odtisa po 6 s - nadomestek", true, OsPravila.nadomestekPoOdtisih("aa11", "aa11", kb64, kb64, r))
    preveriEnako("razlicna odtisa - pravi prenos", false, OsPravila.nadomestekPoOdtisih("aa11", "bb22", kb64, kb64, r + 500))
    preveriEnako("prvo branje ni uspelo - ni dokaza", null, OsPravila.nadomestekPoOdtisih(null, "bb22", 0, kb64, r))
    preveriEnako("drugo branje ni uspelo - ni dokaza", null, OsPravila.nadomestekPoOdtisih("aa11", null, kb64, 0, r))
    preveriEnako("premalo podatkov - ni dokaza", null, OsPravila.nadomestekPoOdtisih("aa11", "aa11", 1000, 1000, r))
    preveriEnako("prekratek razmik - ni dokaza", null, OsPravila.nadomestekPoOdtisih("aa11", "aa11", kb64, kb64, r - 1))
    preveriEnako("prazen odtis - ni dokaza", null, OsPravila.nadomestekPoOdtisih("", "", kb64, kb64, r))
    preveriEnako("konec toka - takoj", 0L, OsPravila.zamikPonovitveVZivo(0))
    preveriEnako("HTTP 403/404 (2004) - brez ponovitve", null, OsPravila.zamikPonovitveVZivo(2004))
    preveriEnako("cleartext (2007) - brez ponovitve", null, OsPravila.zamikPonovitveVZivo(2007))
    preveriEnako("povezava ni uspela (2001) - z zamikom", OsPravila.ZAMIK_PONOVITVE_OMREZJE_MS, OsPravila.zamikPonovitveVZivo(2001))
    preveriEnako("rok (2002) - z zamikom", OsPravila.ZAMIK_PONOVITVE_OMREZJE_MS, OsPravila.zamikPonovitveVZivo(2002))
    preveriEnako("druga napaka vira (2000) - takoj", 0L, OsPravila.zamikPonovitveVZivo(2000))
    // Naloga 101 (pregled 100, S1): pri 2004 odloci odgovor HTTP.
    val z = OsPravila.ZAMIK_PONOVITVE_OMREZJE_MS
    for (h in listOf(401, 403, 404, 410, 400)) preveriEnako("101: 2004 s HTTP $h - brez ponovitve", null, OsPravila.zamikPonovitveVZivo(2004, h))
    for (h in listOf(500, 502, 503, 509, 408, 429)) preveriEnako("101: 2004 s HTTP $h - z zamikom", z, OsPravila.zamikPonovitveVZivo(2004, h))
    preveriEnako("101: 2004 brez znane kode - brez ponovitve", null, OsPravila.zamikPonovitveVZivo(2004, 0))
    preveriEnako("101: konec toka z http ne vpliva", 0L, OsPravila.zamikPonovitveVZivo(0, 503))
    // Naloga 103 (pregled 102, tocka 1): tih konec ob meji ponovitev samo brez druge moznosti.
    preveriEnako("103: konec toka, nic naprej - tih konec", true, OsPravila.tihKonecPoMeji(napaka = false, imaRezervo = false, imaNaslednjega = false))
    preveriEnako("103: napaka z rezervnim tokom - rezerva (obstojeca pot)", false, OsPravila.tihKonecPoMeji(napaka = true, imaRezervo = true, imaNaslednjega = false))
    preveriEnako("103: konec toka z rezervo - rezerve konec toka ne uporablja, tih konec", true, OsPravila.tihKonecPoMeji(napaka = false, imaRezervo = true, imaNaslednjega = false))
    preveriEnako("103: naslednji element vrste - naprej (obstojeca pot)", false, OsPravila.tihKonecPoMeji(napaka = false, imaRezervo = false, imaNaslednjega = true))
    preveriEnako("pregled 103 #2: napaka brez rezerve in brez naslednjega - sporocilo z razlogom, ne tih konec", false, OsPravila.tihKonecPoMeji(napaka = true, imaRezervo = false, imaNaslednjega = false))
}

private fun preizkusPrikazaVZivo104() {
    println("\n== zaslon predvajanja: V zivo (naloga 104) ==")
    preveriEnako("predvajalnik pove v zivo", true, OsPravila.prikazVZivo(isLive = true, dinamicno = false, radioAliKanal = false))
    preveriEnako("dinamicno okno (HLS v zivo)", true, OsPravila.prikazVZivo(isLive = false, dinamicno = true, radioAliKanal = false))
    preveriEnako("radio ali kanal (surov TS prek http)", true, OsPravila.prikazVZivo(isLive = false, dinamicno = false, radioAliKanal = true))
    preveriEnako("film - drsnik in cas", false, OsPravila.prikazVZivo(isLive = false, dinamicno = false, radioAliKanal = false))
}

private fun preizkusOdKdaj113() {
    println("\n== dodatek ne odgovarja: od kdaj in koda (naloga 113) ==")
    val t = 1_000_000L
    var o = OsPravila.poOdzivu(null, false, t, 500)
    preveriEnako("prvi izpad: od kdaj", t, o.odKdaj)
    preveriEnako("prvi izpad: koda", 500, o.koda)
    o = OsPravila.poOdzivu(o, false, t + 60_000, 502)
    o = OsPravila.poOdzivu(o, false, t + 120_000, 0)
    preveri("tretji izpad: ne odgovarja", OsPravila.neOdgovarja(o, t + 120_000))
    preveriEnako("od kdaj ostane prvi izpad", t, o.odKdaj)
    preveriEnako("koda je zadnja (0 = brez odgovora)", 0, o.koda)
    val poskus = OsPravila.dovoliZahtevo(o, o.preskociDo + 1)
    preveriEnako("polodprto: od kdaj ostane", t, poskus?.odKdaj)
    preveriEnako("izpad poskusa: od kdaj ostane", t, OsPravila.poOdzivu(poskus, false, o.preskociDo + 2, 500).odKdaj)
    preveriEnako("odgovor vse pobrise", OsPravila.Odziv(), OsPravila.poOdzivu(o, true, t + 130_000, 200))
}

/**
 * Naloga 114: zaporedja dogodkov kanala v zivo skozi [OsPravila.odlocitevVZivo]. Stevce vodimo kot GlasbaStoritev.ponoviVZivo:
 * ob VZIVO_ZNOVA se zapise zaporedna stevilka in cas ponovitve. Vrne vrste odlocitev po vrsti.
 */
private fun scenarij(dogodki: List<Triple<Long, Long, Int>>, http: Int = 0, kanal: Boolean = true, progresivno: Boolean = true,
                     zacelo: Boolean = true, imaRezervo: Boolean = false, imaNaslednjega: Boolean = false): List<Int> {
    var prejsnjih = 0
    val casi = ArrayList<Long>()
    val izidi = ArrayList<Int>()
    for ((zdaj, predvajano, koda) in dogodki) {
        val o = OsPravila.odlocitevVZivo(kanal, progresivno, zacelo, koda, http, prejsnjih, predvajano, casi, zdaj, imaRezervo, imaNaslednjega)
        izidi += o.vrsta
        if (o.vrsta == OsPravila.VZIVO_ZNOVA) { prejsnjih = o.zaporedno; casi += zdaj }
    }
    return izidi
}

private fun preizkusScenarijevVZivo114() {
    println("\n== kanal v zivo: scenariji odlocitev (naloga 114) ==")
    val ZN = OsPravila.VZIVO_ZNOVA
    val TK = OsPravila.VZIVO_TIH_KONEC
    val OP = OsPravila.VZIVO_OBSTOJECA_POT
    val t = 10_000_000L
    // 5-sekundna prepoved operaterja (meritev 23. 10. 14:31): konec toka vsakih ~5 s, vsakic 5008 ms predvajano.
    val prepoved = (0 until 12).map { Triple(t + it * 5_100L, 5_008L, 0) }
    preveriEnako("prepoved: 10 ponovitev, nato tih konec", List(10) { ZN } + listOf(TK, TK), scenarij(prepoved))
    preveriEnako("prepoved z naslednjim elementom vrste: obstojeca pot (naslednji)", List(10) { ZN } + listOf(OP, OP), scenarij(prepoved, imaNaslednjega = true))
    preveriEnako("prepoved, konec toka z rezervo: tih konec (rezerve konec toka ne uporablja)", List(10) { ZN } + listOf(TK, TK), scenarij(prepoved, imaRezervo = true))
    // Napaka vira (2000) po meji z rezervnim tokom dodatka: obstojeca pot preklopi na rezervo (naloga 103).
    val napake = (0 until 11).map { Triple(t + it * 5_100L, 5_008L, 2000) }
    preveriEnako("napake vira z rezervo: po meji rezerva", List(10) { ZN } + listOf(OP), scenarij(napake, imaRezervo = true))
    // Pravi CBR tok, ki ga streznik zapre vsakih 30 s: v 2 min najvec 4 ponovitve - tece naprej.
    val cbr = (0 until 20).map { Triple(t + it * 30_000L, 30_000L, 0) }
    preveriEnako("pravi tok, zapre vsakih 30 s: vedno znova", List(20) { ZN }, scenarij(cbr))
    // Izpad omrezja (2001): ponovitev z zamikom; 403 (2004): takoj obstojeca pot.
    preveriEnako("izpad omrezja: zamik", OsPravila.ZAMIK_PONOVITVE_OMREZJE_MS,
        OsPravila.odlocitevVZivo(true, true, true, 2001, 0, 0, 3_000, emptyList(), t, false, false).zamikMs)
    preveriEnako("403: obstojeca pot", listOf(OP), scenarij(listOf(Triple(t, 3_000L, 2004)), http = 403))
    preveriEnako("503: znova", listOf(ZN), scenarij(listOf(Triple(t, 3_000L, 2004)), http = 503))
    // Brez novih podatkov (vsak tok < 2 s): 5 zaporednih, nato konec.
    val brez = (0 until 7).map { Triple(t + it * 1_000L, 500L, 0) }
    preveriEnako("brez podatkov: 5 ponovitev, nato tih konec", List(5) { ZN } + listOf(TK, TK), scenarij(brez))
    preveriEnako("HLS: obstojeca pot", listOf(OP), scenarij(listOf(Triple(t, 5_000L, 0)), progresivno = false))
    preveriEnako("se ni zacelo: obstojeca pot", listOf(OP), scenarij(listOf(Triple(t, 0L, 2001)), zacelo = false))
    preveriEnako("ni kanal (film): obstojeca pot", listOf(OP), scenarij(listOf(Triple(t, 5_000L, 0)), kanal = false))
}

private fun preizkusNarascajocegaZamika115() {
    println("\n== kanal v zivo: narascajoc zamik ponovitve (naloga 115) ==")
    val z = OsPravila.ZAMIK_PONOVITVE_OMREZJE_MS
    preveriEnako("prva ponovitev: osnova", z, OsPravila.narascajocZamik(z, 1))
    preveriEnako("druga zaporedna: dvakrat", 2 * z, OsPravila.narascajocZamik(z, 2))
    preveriEnako("tretja: stirikrat", 4 * z, OsPravila.narascajocZamik(z, 3))
    preveriEnako("cetrta: osemkrat (12 s)", 8 * z, OsPravila.narascajocZamik(z, 4))
    preveriEnako("najvec 12 s", OsPravila.ZAMIK_PONOVITVE_NAJVEC_MS, OsPravila.narascajocZamik(z, 9))
    preveriEnako("takojsnja ostane takojsnja", 0L, OsPravila.narascajocZamik(0L, 5))
    // Izpad omrezja brez podatkov: zamiki narascajo; tok, ki spet da podatke, zacne znova pri osnovi.
    val t = 20_000_000L
    var prejsnjih = 0
    val casi = ArrayList<Long>()
    val zamiki = ArrayList<Long>()
    for (i in 0 until 4) {
        val o = OsPravila.odlocitevVZivo(true, true, true, 2001, 0, prejsnjih, 0L, casi, t + i * 20_000L, false, false)
        zamiki += o.zamikMs; prejsnjih = o.zaporedno; casi += t + i * 20_000L
    }
    preveriEnako("izpad brez podatkov: 1,5 / 3 / 6 / 12 s", listOf(z, 2 * z, 4 * z, 8 * z), zamiki)
    preveriEnako("po novih podatkih spet osnova", z,
        OsPravila.odlocitevVZivo(true, true, true, 2001, 0, prejsnjih, 5_000L, casi, t + 100_000L, false, false).zamikMs)
}

private fun preizkusPlacljivega120() {
    println("\n== neodklenjena placljiva vsebina se ne kaze (naloga 120) ==")
    preveriEnako("Unlock Premium Access", true, OsPravila.jePlacljivoObvestilo("Click to Unlock Premium Access"))
    preveriEnako("Subscribe", true, OsPravila.jePlacljivoObvestilo("Subscribe to watch this channel"))
    preveriEnako("VIP", true, OsPravila.jePlacljivoObvestilo("VIP only"))
    preveriEnako("slovensko: narocnina", true, OsPravila.jePlacljivoObvestilo("Potrebna je naročnina"))
    preveriEnako("No streams found ni placilo", false, OsPravila.jePlacljivoObvestilo("No streams found"))
    preveriEnako("Discord ni placilo", false, OsPravila.jePlacljivoObvestilo("Join our Discord https://discord.gg/x"))
    preveriEnako("donacija ni placilo", false, OsPravila.jePlacljivoObvestilo("Support us with a donation"))
    val t = 50_000_000L
    preveriEnako("samo placilni zid: zapis zdaj", t, OsPravila.placljivoPoOdgovoru(null, 2, 0, t))
    preveriEnako("ponovno: cas prvega zapisa ostane", t, OsPravila.placljivoPoOdgovoru(t, 1, 0, t + 9_000L))
    preveriEnako("predvajljiv tok: odklenjeno", null, OsPravila.placljivoPoOdgovoru(t, 3, 1, t + 1L))
    preveriEnako("prazen odgovor ne spremeni", t, OsPravila.placljivoPoOdgovoru(t, 0, 0, t + 1L))
    preveriEnako("prazen odgovor ne zapise", null, OsPravila.placljivoPoOdgovoru(null, 0, 0, t))
    preveriEnako("zapisano: skrito", true, OsPravila.placljivoSkrito(t, t + 1_000L, false))
    preveriEnako("Pokazi zaklenjene: vidno", false, OsPravila.placljivoSkrito(t, t + 1_000L, true))
    preveriEnako("po 7 dneh: preveri znova (vidno)", false, OsPravila.placljivoSkrito(t, t + OsPravila.PLACLJIVO_VELJA_MS, false))
    preveriEnako("ura nazaj: vidno", false, OsPravila.placljivoSkrito(t, t - 1L, false))
    preveriEnako("brez zapisa: vidno", false, OsPravila.placljivoSkrito(null, t, false))

    // Naloga 143: najvec 12 katalogov, a vsak dodatek dobi svoj delez (prej je Cinemeta, dodana zadnja, izpadla - vseh 12
    // so zapolnili drugi dodatki)
    val k143 = (1..12).map { "A$it" } + listOf("B1", "B2", "B3") + listOf("C1", "C2")
    preveriEnako("143: vsak dodatek ima delez", listOf("A1", "B1", "C1", "A2", "B2", "C2", "A3", "B3", "A4", "A5", "A6", "A7"),
        OsPravila.pravicnoPoDodatkih(k143, { it.take(1) }, 12))
    preveriEnako("143: manj od meje ostane", listOf("A1", "A2"), OsPravila.pravicnoPoDodatkih(listOf("A1", "A2"), { it.take(1) }, 12))
    preveriEnako("143: en dodatek kot prej", (1..12).map { "A$it" }, OsPravila.pravicnoPoDodatkih((1..20).map { "A$it" }, { it.take(1) }, 12))
    preveriEnako("143: prazno", emptyList<String>(), OsPravila.pravicnoPoDodatkih(emptyList<String>(), { it }, 12))
    preveriEnako("145: preverjanje tece - pocakaj", true, OsPravila.cakajNaPreverjanje(12, premor = false))
    preveriEnako("145: dodatek v premoru - ne cakaj", false, OsPravila.cakajNaPreverjanje(12, premor = true))
    preveriEnako("145: nic ne caka", false, OsPravila.cakajNaPreverjanje(0, premor = false))
    preveriEnako("144: rezerva en zaslon", 900, OsPravila.rezervaNaslovnic(330, 900, 90))
    preveriEnako("144: rezerva najmanj kot prej", 420, OsPravila.rezervaNaslovnic(330, 0, 90))
    preveriEnako("143: meja 3", listOf("X1", "Y1", "X2"), OsPravila.pravicnoPoDodatkih((1..30).map { "X$it" } + "Y1", { it.take(1) }, 3))
}
