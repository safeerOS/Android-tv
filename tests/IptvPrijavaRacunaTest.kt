package si.safeer.tv.os

// Pregled 105, S2 in S3 (26. 10. 2026): prejet vir z racunom pod naslovom, ki ga ima v aplikaciji (nadomestek), in ena prijava na RACUN.
fun main() {
    var napak = 0
    fun preveriEnako(ime: String, dobljeno: Any?, pricakovano: Any?) {
        if (dobljeno != pricakovano) { napak++; println("NAPAKA $ime: dobljeno=$dobljeno, pricakovano=$pricakovano") }
    }

    // --- S2: vir z racunom iz ozjega kroga (pravi naslov) -> nadomestek, pod katerim so tu vir, grob izbrisa in dovoljenje.
    val seznam = "http://panel.example:8080/get.php?username=uporabnik1&password=Geslo1&type=m3u_plus"
    val nadomestek = SkrivnostiPravila.nadomestek(seznam)
    preveriEnako("S2 nadomestek obstaja", nadomestek != null, true)
    preveriEnako("S2 seznam z racunom", IptvPravila.naslovVAplikaciji("seznam", seznam), nadomestek)
    preveriEnako("S2 vrsta url (racunalnik) kot seznam", IptvPravila.naslovVAplikaciji("url", seznam), nadomestek)
    val kanal = "http://panel.example:8080/live/uporabnik1/Geslo1/123.ts"
    preveriEnako("S2 tok z racunom", IptvPravila.naslovVAplikaciji("tok", kanal), SkrivnostiPravila.nadomestek(kanal))
    // Grob izbrisa in dovoljenje sta pod »seznam|<nadomestek>«: kljuc iz prejetega vira ga zdaj najde.
    val grobovi = mapOf("seznam|$nadomestek" to 500L)
    preveriEnako("S2 grob izbrisa najden", grobovi["seznam|" + IptvPravila.naslovVAplikaciji("seznam", seznam)], 500L)
    // Vir brez racuna in ze nadomestek ostaneta, kot sta (javni seznami in viri brez poverilnic delujejo kot doslej).
    val javni = "https://iptv-org.github.io/iptv/countries/si.m3u"
    preveriEnako("S2 javni seznam ostane", IptvPravila.naslovVAplikaciji("seznam", javni), javni)
    preveriEnako("S2 nadomestek ostane", IptvPravila.naslovVAplikaciji("seznam", nadomestek!!), nadomestek)
    preveriEnako("S2 podkast ostane", IptvPravila.naslovVAplikaciji("podkast", "https://oddaja.example/rss"), "https://oddaja.example/rss")

    // --- S3: kljuc racuna = gostitelj + uporabnik; ne zdruzi razlicnih racunov ali gostiteljev.
    val m3u = "http://panel.example:8080/get.php?username=uporabnik1&password=Geslo1&type=m3u"
    val epg = "http://panel.example:8080/xmltv.php?username=uporabnik1&password=Geslo1"
    val kRacun = IptvPravila.kljucRacuna(seznam)
    preveriEnako("S3 m3u in m3u_plus isti racun", IptvPravila.kljucRacuna(m3u), kRacun)
    preveriEnako("S3 xmltv isti racun", IptvPravila.kljucRacuna(epg), kRacun)
    preveriEnako("S3 pot Xtream isti racun", IptvPravila.kljucRacuna(kanal), kRacun)
    preveriEnako("S3 kratka pot isti racun", IptvPravila.kljucRacuna("http://panel.example:8080/uporabnik1/Geslo1/123"), kRacun)
    preveriEnako("S3 velike crke gostitelja in vrata", IptvPravila.kljucRacuna("http://PANEL.example/get.php?username=uporabnik1&password=Geslo1&type=m3u"), kRacun)
    preveriEnako("S3 odkodiran uporabnik", IptvPravila.kljucRacuna("http://panel.example:8080/get.php?username=uporabnik%31&password=Geslo1"), kRacun)
    preveriEnako("S3 odkodirana pot Xtream", IptvPravila.kljucRacuna("http://panel.example:8080/live/uporabnik%31/Geslo1/7.ts"), kRacun)
    preveriEnako("S3 uporabnik:geslo@", IptvPravila.kljucRacuna("http://uporabnik1:Geslo1@panel.example/seznam.m3u"), kRacun)
    preveriEnako("S3 kljuc je racun", kRacun.startsWith("racun:"), true)
    preveriEnako("S3 brez uporabnika in gesla v kljucu", "uporabnik1" in kRacun || "Geslo1" in kRacun, false)
    preveriEnako("S3 drug uporabnik, drug racun", IptvPravila.kljucRacuna(seznam.replace("uporabnik1", "metka")) == kRacun, false)
    preveriEnako("S3 velike crke uporabnika, drug racun", IptvPravila.kljucRacuna(seznam.replace("uporabnik1", "Uporabnik1")) == kRacun, false)
    preveriEnako("S3 drug gostitelj, drug racun", IptvPravila.kljucRacuna(seznam.replace("panel.example", "drug.example")) == kRacun, false)
    preveriEnako("S3 poddomena, drug racun", IptvPravila.kljucRacuna(seznam.replace("panel.example", "a.panel.example")) == kRacun, false)
    preveriEnako("S3 Stalker mac ni uporabnik",
        IptvPravila.kljucRacuna("http://panel.example/portal.php?mac=uporabnik1") == kRacun, false)
    // Brez prepoznanega uporabnika (zeton): po viru kot doslej - dva zetona se ne zdruzita.
    val z1 = "https://cdn.example/a/b/123?token=AAA111"
    val z2 = "https://cdn.example/a/b/123?token=BBB222"
    preveriEnako("S3 zeton po viru", IptvPravila.kljucRacuna(z1), "vir:" + IptvPravila.odtisRacuna(z1))
    preveriEnako("S3 dva zetona, dva kljuca", IptvPravila.kljucRacuna(z1) == IptvPravila.kljucRacuna(z2), false)
    // Premor I5 po poverilnici: isto geslo deli premor, novo geslo (popravek) ne caka na starega.
    preveriEnako("S3 poverilnica m3u in xmltv", IptvPravila.kljucPoverilnice(m3u), IptvPravila.kljucPoverilnice(epg))
    preveriEnako("S3 novo geslo, nova poverilnica",
        IptvPravila.kljucPoverilnice(m3u) == IptvPravila.kljucPoverilnice(m3u.replace("Geslo1", "Geslo2")), false)
    preveriEnako("S3 zeton brez poverilnice", IptvPravila.kljucPoverilnice(z1), null)
    preveriEnako("S3 locila v uporabniku se ne zlijejo",
        IptvPravila.kljucPoverilnice("http://p.example/get.php?username=a%0Ab&password=c") ==
            IptvPravila.kljucPoverilnice("http://p.example/get.php?username=a&password=b%0Ac"), false)

    // --- S3: ena prijava na racun (kot v IptvSeznami.pripravi: kljuc racuna, naslov vira).
    val prijave = IptvPravila.EnaPrijava()
    preveriEnako("S3 prvi vir racuna", prijave.rezerviraj(IptvPravila.kljucRacuna(m3u), "nad-m3u") { IptvPravila.Priprava.ZDAJ }, IptvPravila.Priprava.ZDAJ)
    var odlocal = false
    preveriEnako("S3 drug vir istega racuna med prenosom",
        prijave.rezerviraj(IptvPravila.kljucRacuna(seznam), "nad-plus") { odlocal = true; IptvPravila.Priprava.ZDAJ }, null)
    preveriEnako("S3 drug vir brez odlocitve", odlocal, false)
    preveriEnako("S3 drug racun ni zadrzan",
        prijave.rezerviraj(IptvPravila.kljucRacuna(seznam.replace("uporabnik1", "metka")), "nad-metka") { IptvPravila.Priprava.ZDAJ }, IptvPravila.Priprava.ZDAJ)
    preveriEnako("S3 vsi = naslovi virov", prijave.vsi(), setOf("nad-m3u", "nad-metka"))
    prijave.sprosti(IptvPravila.kljucRacuna(m3u))
    preveriEnako("S3 po sprostitvi", prijave.rezerviraj(IptvPravila.kljucRacuna(seznam), "nad-plus") { IptvPravila.Priprava.V_OZADJU }, IptvPravila.Priprava.V_OZADJU)
    preveriEnako("S3 BREZ ne rezervira", prijave.rezerviraj("racun:x", "nad-x") { IptvPravila.Priprava.BREZ }, IptvPravila.Priprava.BREZ)
    preveriEnako("S3 BREZ ni v vsi", "nad-x" in prijave.vsi(), false)
    // Stara oblika (kljuc = naslov) deluje kot prej.
    val stara = IptvPravila.EnaPrijava()
    preveriEnako("stara oblika", stara.rezerviraj("javni") { IptvPravila.Priprava.ZDAJ }, IptvPravila.Priprava.ZDAJ)
    preveriEnako("stara oblika zadrzi", stara.rezerviraj("javni") { IptvPravila.Priprava.ZDAJ }, null)
    preveriEnako("stara oblika vsi", stara.vsi(), setOf("javni"))

    // --- S3: premor racuna velja tudi za drug vir (strozja napaka).
    val vira = IptvPravila.NapakaPrenosa(1, 1_000L, false, 100L)
    val racuna = IptvPravila.NapakaPrenosa(3, 9_000L, true, 200L)
    preveriEnako("S3 strozja: racun", IptvPravila.strozjaNapaka(vira, racuna), racuna)
    preveriEnako("S3 strozja: vir", IptvPravila.strozjaNapaka(racuna, vira), racuna)
    preveriEnako("S3 strozja: brez racuna", IptvPravila.strozjaNapaka(vira, null), vira)
    preveriEnako("S3 strozja: brez vira", IptvPravila.strozjaNapaka(null, racuna), racuna)
    preveriEnako("S3 strozja: nobene", IptvPravila.strozjaNapaka(null, null), null)

    if (napak > 0) { println("IptvPrijavaRacunaTest: $napak napak"); kotlin.system.exitProcess(1) }
    println("IptvPrijavaRacunaTest: VSE OK")
}
