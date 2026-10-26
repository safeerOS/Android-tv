package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Naloga 128: debrid storitve - TorBox, Real-Debrid, AllDebrid ([DebridPravila]). */
fun main() {
    println("Preizkus pravil debrid storitev")
    val tb = DebridPravila.TORBOX; val rd = DebridPravila.REAL_DEBRID; val ad = DebridPravila.ALLDEBRID

    // Vrstni red: samo storitve s kljucem; prednostna prva.
    preveriEnako("brez kljucev: nic", emptyList<String>(), DebridPravila.vrstniRed(emptySet(), null))
    preveriEnako("samo Real-Debrid", listOf(rd), DebridPravila.vrstniRed(setOf(rd), tb))
    preveriEnako("vsi, brez prednostne: privzeti red", listOf(tb, rd, ad), DebridPravila.vrstniRed(setOf(ad, rd, tb), null))
    preveriEnako("vsi, prednostna AllDebrid", listOf(ad, tb, rd), DebridPravila.vrstniRed(setOf(ad, rd, tb), ad))
    preveriEnako("prednostna brez kljuca ne velja", listOf(tb, rd), DebridPravila.vrstniRed(setOf(tb, rd), ad))

    // Kljuci
    preveriEnako("RD zeton (52 znakov)", "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOP",
        DebridPravila.ocistiKljuc(" ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOP\n"))
    preveriEnako("AD kljuc (20 znakov)", "aBcD1234eFgH5678iJkL", DebridPravila.ocistiKljuc("aBcD1234eFgH5678iJkL"))
    preveriEnako("prekratek", null, DebridPravila.ocistiKljuc("abc"))
    preveriEnako("naslov ni kljuc", null, DebridPravila.ocistiKljuc("https://real-debrid.com/apitoken"))
    preveriEnako("glava Bearer", "Bearer k", DebridPravila.glavaAvtorizacije("k"))

    // Kaj pove uporabniku, ko nobena ne da povezave
    preveriEnako("kljuc je najpomembnejsi", TorBoxPravila.NAPAKA_KLJUC,
        DebridPravila.razlogZaUporabnika(listOf(DebridPravila.NAPAKA_NI_PREDPOMNJENO, TorBoxPravila.NAPAKA_KLJUC)))
    preveriEnako("ni predpomnjeno pred streznikom", DebridPravila.NAPAKA_NI_PREDPOMNJENO,
        DebridPravila.razlogZaUporabnika(listOf(TorBoxPravila.NAPAKA_STREZNIK, DebridPravila.NAPAKA_NI_PREDPOMNJENO)))
    preveriEnako("brez napak: streznik", TorBoxPravila.NAPAKA_STREZNIK, DebridPravila.razlogZaUporabnika(emptyList()))

    // Cakanje: samo ce filter ni vklopljen in je razlog samo »ni predpomnjeno«
    val nip = DebridPravila.NAPAKA_NI_PREDPOMNJENO
    preveriEnako("filter izklopljen, nobena nima: pocakaj", true, DebridPravila.poskusiSCakanjem(false, listOf(nip, nip)))
    preveriEnako("filter vklopljen: ne cakaj", false, DebridPravila.poskusiSCakanjem(true, listOf(nip)))
    preveriEnako("zavrnjen kljuc: ne cakaj", false, DebridPravila.poskusiSCakanjem(false, listOf(nip, TorBoxPravila.NAPAKA_KLJUC)))
    preveriEnako("brez storitev: ne cakaj", false, DebridPravila.poskusiSCakanjem(false, emptyList()))

    // Real-Debrid (kode iz tabele v dokumentaciji)
    preveriEnako("RD downloaded", true, DebridPravila.rdPripravljen("downloaded"))
    preveriEnako("RD downloading ni pripravljen", false, DebridPravila.rdPripravljen("downloading"))
    preveriEnako("RD dead je koncna", true, DebridPravila.rdKoncnaNapaka("dead"))
    preveriEnako("RD queued ni koncna", false, DebridPravila.rdKoncnaNapaka("queued"))
    preveriEnako("RD 8 bad token", TorBoxPravila.NAPAKA_KLJUC, DebridPravila.rdVrstaNapake(401, 8))
    // Varovalo racunov (lastnik 26. 10. 2026, skupni primeri v2): 14, 22 = racun zaklenjen, 9 = ne velja (prej 9 paket = velja).
    preveriEnako("RD 9 permission denied: kljuc (nikoli velja)", TorBoxPravila.NAPAKA_KLJUC, DebridPravila.rdVrstaNapake(403, 9))
    preveriEnako("RD 14 account locked: zaklenjen", TorBoxPravila.NAPAKA_ZAKLENJEN, DebridPravila.rdVrstaNapake(403, 14))
    preveriEnako("RD 22 IP not allowed: zaklenjen", TorBoxPravila.NAPAKA_ZAKLENJEN, DebridPravila.rdVrstaNapake(403, 22))
    preveriEnako("RD 20 ne za brezplacne: paket", TorBoxPravila.NAPAKA_PAKET, DebridPravila.rdVrstaNapake(403, 20))
    preveriEnako("RD 34 too many requests", TorBoxPravila.NAPAKA_MEJA, DebridPravila.rdVrstaNapake(429, 34))
    preveriEnako("RD 23 promet porabljen", TorBoxPravila.NAPAKA_MEJA, DebridPravila.rdVrstaNapake(503, 23))
    preveriEnako("RD 35 infringing file", TorBoxPravila.NAPAKA_NI_DATOTEKE, DebridPravila.rdVrstaNapake(451, 35))
    preveriEnako("RD 37 disabled endpoint (403) ni paket", TorBoxPravila.NAPAKA_STREZNIK, DebridPravila.rdVrstaNapake(403, 37))
    preveriEnako("RD brez kode: 401 kljuc", TorBoxPravila.NAPAKA_KLJUC, DebridPravila.rdVrstaNapake(401, null))
    preveriEnako("RD brez kode: 403 paket", TorBoxPravila.NAPAKA_PAKET, DebridPravila.rdVrstaNapake(403, null))

    // AllDebrid
    preveriEnako("AD AUTH_BAD_APIKEY", TorBoxPravila.NAPAKA_KLJUC, DebridPravila.adVrstaNapake(200, "AUTH_BAD_APIKEY"))
    preveriEnako("AD AUTH_BLOCKED (nova lokacija): zaklenjen", TorBoxPravila.NAPAKA_ZAKLENJEN, DebridPravila.adVrstaNapake(200, "AUTH_BLOCKED"))
    preveriEnako("AD AUTH_USER_BANNED: zaklenjen", TorBoxPravila.NAPAKA_ZAKLENJEN, DebridPravila.adVrstaNapake(200, "AUTH_USER_BANNED"))
    preveriEnako("AD MUST_BE_PREMIUM", TorBoxPravila.NAPAKA_PAKET, DebridPravila.adVrstaNapake(200, "MUST_BE_PREMIUM"))
    preveriEnako("AD MAGNET_INVALID_URI", TorBoxPravila.NAPAKA_NI_DATOTEKE, DebridPravila.adVrstaNapake(200, "MAGNET_INVALID_URI"))
    preveriEnako("AD 429", TorBoxPravila.NAPAKA_MEJA, DebridPravila.adVrstaNapake(429, null))
    val hex = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"
    preveriEnako("AD dobi hash iz magneta", hex, DebridPravila.magnetAliHash("magnet:?xt=urn:btih:$hex&dn=x&tr=udp://t"))
    preveriEnako("AD brez btih: magnet ostane", "magnet:?dn=x", DebridPravila.magnetAliHash(" magnet:?dn=x "))

    preveriEnako("imena", listOf("TorBox", "Real-Debrid", "AllDebrid"), DebridPravila.VSI.map { DebridPravila.ime(it) })
    preveriEnako("kje kljuc RD", "real-debrid.com/apitoken", DebridPravila.kjeKljuc(rd))

    // Naloga 129: kljuci s telefona na TV
    val rdk = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOP"
    preveriEnako("129: prejeti - samo znane storitve in veljavni kljuci", mapOf(rd to rdk),
        DebridPravila.prejetiKljuci(mapOf(rd to " $rdk ", "neznana" to rdk, ad to "kratek")))
    preveriEnako("129: prazno", emptyMap<String, String>(), DebridPravila.prejetiKljuci(emptyMap()))
    preveriEnako("129: potrditev velja 10 min", true, DebridPravila.prejetoVelja(1_000L, 1_000L + DebridPravila.PREJETO_VELJA_MS - 1))
    preveriEnako("129: po 10 min ne velja", false, DebridPravila.prejetoVelja(1_000L, 1_000L + DebridPravila.PREJETO_VELJA_MS))
    preveriEnako("129: ura nazaj ne velja", false, DebridPravila.prejetoVelja(5_000L, 1_000L))
    preveriEnako("129: brez sifrirane poti ne posljemo", false, DebridPravila.smePoslatiKljuc(znaZascito = false, povezan = true))
    preveriEnako("129: brez povezave ne posljemo", false, DebridPravila.smePoslatiKljuc(znaZascito = true, povezan = false))
    preveriEnako("129: sifrirano in povezano: posljemo", true, DebridPravila.smePoslatiKljuc(znaZascito = true, povezan = true))

    // Naloga 130: izbira ob zvrsteh
    preveriEnako("130: privzeto storitev s kljucem", tb, DebridPravila.aktivna(setOf(tb), null, false))
    preveriEnako("130: brez kljuca ni aktivne", null, DebridPravila.aktivna(emptySet(), tb, false))
    preveriEnako("130: »Brez debrida«", null, DebridPravila.aktivna(setOf(tb, rd), tb, true))
    preveriEnako("130: izbrana Real-Debrid", rd, DebridPravila.aktivna(setOf(tb, rd), rd, false))
    preveriEnako("130: izbrana brez kljuca -> prva s kljucem", tb, DebridPravila.aktivna(setOf(tb), ad, false))

    // Naloga 130: hashi za checkcached
    val h1 = "2849211f150e966ef7924f43aec22727a644e10d"; val h2 = "9f23769f0000000000000000000000000000abcd"
    preveriEnako("130: veljavni, mala crka, brez ponovitev", listOf(h1, h2),
        DebridPravila.hashiZaPreverbo(listOf(h1.uppercase(), null, "kratek", h1, " $h2 ", "z".repeat(40))))
    preveriEnako("130: najvec 100", 100, DebridPravila.hashiZaPreverbo((0 until 150).map { it.toString().padStart(40, '0') }).size)
    preveriEnako("130: predpomnjeni samo vprasani", setOf(h1), DebridPravila.predpomnjeni(listOf(h1.uppercase(), "drug"), listOf(h1, h2)))

    // Naloga 130: vrstni red in samodejna izbira
    preveriEnako("130: pripravljeni naprej, red ostane", listOf(1, 3, 0, 2, 4),
        DebridPravila.predpomnjeniNaprej(listOf("a", h1, null, h2, "b"), setOf(h1, h2)))
    preveriEnako("130: brez pripravljenih red ostane", listOf(0, 1), DebridPravila.predpomnjeniNaprej(listOf(h1, h2), emptySet()))
    preveriEnako("130: samodejno prvi pripravljen", 1, DebridPravila.samodejniTorrent(tb, listOf(null, h2.uppercase(), h1), setOf(h1, h2)))
    preveriEnako("130: brez aktivne storitve -1", -1, DebridPravila.samodejniTorrent(null, listOf(h1), setOf(h1)))
    preveriEnako("130: nic pripravljenega -1", -1, DebridPravila.samodejniTorrent(tb, listOf(h1), emptySet()))
    preveriEnako("130: z debridom pocakamo dlje", 3_000L, DebridPravila.cakajPoPrvemMs(true))
    preveriEnako("130: brez debrida 1,5 s", 1_500L, DebridPravila.cakajPoPrvemMs(false))
    preveriEnako("130: torrent steje samo z debridom", false, DebridPravila.stejeKotPrvi("torrent", false))
    preveriEnako("130: torrent z debridom steje", true, DebridPravila.stejeKotPrvi("torrent", true))
    preveriEnako("130: povezava steje vedno", true, DebridPravila.stejeKotPrvi("url", false))

    // Naloga 130: najhitrejsa storitev (vec kljucev)
    val hitra = DebridPravila.Meritev(tb, 80, 3_000_000, 1_000)      // 24 Mb/s
    val pocasna = DebridPravila.Meritev(rd, 20, 1_000_000, 1_000)    // 8 Mb/s
    val podobna = DebridPravila.Meritev(ad, 30, 2_800_000, 1_000)    // 22,4 Mb/s (> 85 %)
    preveriEnako("130: hitrost v bitih", 24_000_000L, hitra.bitovNaSekundo)
    preveriEnako("130: najvisja hitrost zmaga", tb, DebridPravila.najhitrejsa(listOf(pocasna, hitra)))
    preveriEnako("130: podobna hitrost -> manjsi ping", ad, DebridPravila.najhitrejsa(listOf(hitra, podobna, pocasna)))
    preveriEnako("130: neuspela meritev ne steje", rd, DebridPravila.najhitrejsa(listOf(DebridPravila.Meritev(tb, 5, 0, 1_000), pocasna)))
    preveriEnako("130: brez meritev null", null, DebridPravila.najhitrejsa(emptyList()))
    preveriEnako("130: opis meritve", "24 Mb/s · 80 ms", DebridPravila.opisMeritve(hitra))
    preveriEnako("130: opis pod 10 Mb/s", "8.0 Mb/s · 20 ms", DebridPravila.opisMeritve(pocasna))
    preveriEnako("130: zapis in nazaj", hitra to 123L, DebridPravila.meritevIzZapisa(tb, DebridPravila.zapisMeritve(hitra, 123L)))
    preveriEnako("130: neveljaven zapis", null, DebridPravila.meritevIzZapisa(tb, "1,2"))
    preveriEnako("130: meritev velja teden", true, DebridPravila.meritevVelja(0L, DebridPravila.MERITEV_VELJA_MS - 1))
    preveriEnako("130: starejsa ne velja", false, DebridPravila.meritevVelja(0L, DebridPravila.MERITEV_VELJA_MS))

    // Naloga 136: ⚡ za Real-Debrid in AllDebrid - zapomnjeni pripravljeni torrenti z rokom
    val rok = DebridPravila.ZAPOMNJEN_VELJA_MS
    val z1 = DebridPravila.zapomniPripravljen(emptyList(), h1.uppercase(), rd, 1_000L)
    preveriEnako("136: zapomni (mala crka, rok)", listOf(DebridPravila.Zapomnjen(h1, rd, 1_000L + rok)), z1)
    preveriEnako("136: TorBox se ne zapomni (ima pravo preverbo)", emptyList<DebridPravila.Zapomnjen>(),
        DebridPravila.zapomniPripravljen(emptyList(), h1, tb, 1_000L))
    preveriEnako("136: neveljaven hash se ne zapomni", emptyList<DebridPravila.Zapomnjen>(),
        DebridPravila.zapomniPripravljen(emptyList(), "abc", rd, 1_000L))
    preveriEnako("136: null hash se ne zapomni", emptyList<DebridPravila.Zapomnjen>(),
        DebridPravila.zapomniPripravljen(emptyList(), null, rd, 1_000L))
    val z2 = DebridPravila.zapomniPripravljen(z1, h1, rd, 5_000L)
    preveriEnako("136: ponovno - podaljsan rok, brez dvojnika", listOf(DebridPravila.Zapomnjen(h1, rd, 5_000L + rok)), z2)
    val z3 = DebridPravila.zapomniPripravljen(z2, h1, ad, 5_000L)
    preveriEnako("136: isti hash pri drugi storitvi je loceno", 2, z3.size)
    preveriEnako("136: za storitev s kljucem", setOf(h1), DebridPravila.zapomnjeniZa(z3, listOf(h1, h2, null), setOf(ad), 6_000L))
    preveriEnako("136: brez kljuca te storitve nic", emptySet<String>(), DebridPravila.zapomnjeniZa(z1, listOf(h1), setOf(ad, tb), 2_000L))
    preveriEnako("136: velja do roka", setOf(h1), DebridPravila.zapomnjeniZa(z1, listOf(h1), setOf(rd), 1_000L + rok - 1))
    preveriEnako("136: po roku ne velja", emptySet<String>(), DebridPravila.zapomnjeniZa(z1, listOf(h1), setOf(rd), 1_000L + rok))
    preveriEnako("136: velika crka v vprasanju", setOf(h1), DebridPravila.zapomnjeniZa(z1, listOf(h1.uppercase()), setOf(rd), 2_000L))
    preveriEnako("136: pozabi samo pri tej storitvi", listOf(DebridPravila.Zapomnjen(h1, ad, 5_000L + rok)),
        DebridPravila.pozabiPripravljen(z3, h1.uppercase(), rd))
    preveriEnako("136: poteceni odpadejo ob zapisu", listOf(DebridPravila.Zapomnjen(h2, rd, 2_000L + 2 * rok)),
        DebridPravila.zapomniPripravljen(z1, h2, rd, 1_000L + rok + 1_000L))
    val polno = (0 until DebridPravila.NAJVEC_ZAPOMNJENIH).map { DebridPravila.Zapomnjen(it.toString(16).padStart(40, '0'), rd, rok + it) }
    val prepolno = DebridPravila.zapomniPripravljen(polno, h1, rd, 10L)
    preveriEnako("136: najvec zapomnjenih", DebridPravila.NAJVEC_ZAPOMNJENIH, prepolno.size)
    preveriEnako("136: pri polnem odpade najstarejsi", false, prepolno.any { it.hash == polno.first().hash })
    preveriEnako("136: zapis in nazaj", z3, DebridPravila.zapomnjeniIzZapisa(DebridPravila.zapisZapomnjenih(z3)))
    preveriEnako("136: pokvarjen zapis -> preskoci vrstico", listOf(DebridPravila.Zapomnjen(h1, rd, 7L)),
        DebridPravila.zapomnjeniIzZapisa("x,y\n$h1,$rd,7\n$h2,neznana,7\n$h2,$rd,ni\n"))
    preveriEnako("136: null zapis", emptyList<DebridPravila.Zapomnjen>(), DebridPravila.zapomnjeniIzZapisa(null))

    // Naloga 136: prejeto z druge naprave v Linku (preostali cas, ne ura posiljatelja)
    preveriEnako("136: poslano kot preostali cas", Triple(h1, rd, rok - 4_000L),
        DebridPravila.zaPoslati(DebridPravila.Zapomnjen(h1, rd, 1_000L + rok), 5_000L))
    preveriEnako("136: potecen se ne poslje", null, DebridPravila.zaPoslati(DebridPravila.Zapomnjen(h1, rd, 1_000L), 1_000L))
    val prejeti = DebridPravila.prejetiZapomnjeni(emptyList(), listOf(
        Triple(h1.uppercase(), rd, 60_000L),
        Triple(h2, ad, rok * 10),            // rok ne sme biti daljsi od nasega
        Triple(h2, tb, 60_000L),             // TorBox ima pravo preverbo
        Triple("zz", rd, 60_000L),
        Triple(h1, ad, -5L),                 // potecen
        Triple(h1, "neznana", 60_000L)), 100L)
    preveriEnako("136: prejeti - samo veljavni, rok omejen",
        listOf(DebridPravila.Zapomnjen(h1, rd, 60_100L), DebridPravila.Zapomnjen(h2, ad, 100L + rok)), prejeti)
    preveriEnako("136: prejeti ne skrajsajo naseg roka", listOf(DebridPravila.Zapomnjen(h1, rd, 5_000L + rok)),
        DebridPravila.prejetiZapomnjeni(z2, listOf(Triple(h1, rd, 10L)), 5_000L))
    preveriEnako("136: prejetih najvec v enem sporocilu", DebridPravila.NAJVEC_PREJETIH,
        DebridPravila.prejetiZapomnjeni(emptyList(), (0 until 500).map { Triple(it.toString(16).padStart(40, '0'), rd, 60_000L) }, 0L).size)
    preveriEnako("136: dejanje Linka", "debrid.cached", DebridPravila.DEJANJE_PREDPOMNJENI)

    // Pregled A4: sporocilo imenuje storitev, ki je res padla.
    preveriEnako("A4: brez poskusov ni storitve", "", DebridPravila.ponudnikNapake(emptyList()))
    preveriEnako("A4: ena storitev", rd, DebridPravila.ponudnikNapake(listOf(rd to TorBoxPravila.NAPAKA_STREZNIK)))
    preveriEnako("A4: storitev z najpomembnejso napako", ad, DebridPravila.ponudnikNapake(listOf(
        tb to TorBoxPravila.NAPAKA_STREZNIK, rd to TorBoxPravila.NAPAKA_MEJA, ad to TorBoxPravila.NAPAKA_KLJUC)))
    // Pregled Grok 2 in neodvisni pregled 2: caka prva storitev po vrstnem redu, ki je rekla »ni predpomnjeno«; »ni
    // predpomnjeno« (z gumbom »Pocakaj«) je razlog, kadar bi kdo res cakal - kljuc, meja ali rok drugih tega ne preglasi.
    val kl = TorBoxPravila.NAPAKA_KLJUC; val st = TorBoxPravila.NAPAKA_STREZNIK; val me = TorBoxPravila.NAPAKA_MEJA
    preveriEnako("cakalna: prednostna ni predpomnjeno", tb, DebridPravila.cakalnaStoritev(false, listOf(tb to nip, rd to kl)))
    preveriEnako("cakalna: prednostna rok, druga ni predpomnjeno -> druga", rd, DebridPravila.cakalnaStoritev(false, listOf(tb to st, rd to nip)))
    preveriEnako("cakalna: prednostna zavrnjen kljuc, druga ni predpomnjeno -> druga", ad, DebridPravila.cakalnaStoritev(false, listOf(tb to kl, rd to me, ad to nip)))
    preveriEnako("cakalna: dve ni predpomnjeno -> prva po vrstnem redu", rd, DebridPravila.cakalnaStoritev(false, listOf(tb to st, rd to nip, ad to nip)))
    preveriEnako("cakalna: samo takoj -> nihce", null, DebridPravila.cakalnaStoritev(true, listOf(tb to nip)))
    preveriEnako("cakalna: nobena ni predpomnjeno -> nihce", null, DebridPravila.cakalnaStoritev(false, listOf(tb to st, rd to kl)))
    preveriEnako("cakalna: brez storitev -> nihce", null, DebridPravila.cakalnaStoritev(false, emptyList()))
    preveriEnako("razlog: prednostna ni predpomnjeno, druga kljuc", nip, DebridPravila.razlogPoStoritvah(listOf(tb to nip, rd to kl)))
    preveriEnako("razlog: prednostna rok, druga ni predpomnjeno", nip, DebridPravila.razlogPoStoritvah(listOf(tb to st, rd to nip)))
    preveriEnako("razlog: prednostna kljuc, druga ni predpomnjeno (druga bi prenesla)", nip, DebridPravila.razlogPoStoritvah(listOf(tb to kl, rd to nip)))
    preveriEnako("razlog: brez »ni predpomnjeno« - najpomembnejsa", me, DebridPravila.razlogPoStoritvah(listOf(tb to st, rd to me)))
    preveriEnako("razlog: brez poskusov streznik", st, DebridPravila.razlogPoStoritvah(emptyList()))
    preveriEnako("ponudnik napake = storitev, ki bi cakala", rd, DebridPravila.ponudnikNapake(listOf(tb to st, rd to nip, ad to kl)))
    preveriEnako("Pocakaj ponujen = nekdo bi cakal", true, DebridPravila.cakalnaStoritev(false, listOf(tb to st, rd to nip)) != null &&
        DebridPravila.razlogPoStoritvah(listOf(tb to st, rd to nip)) == nip)
    preveriEnako("A4: enaka napaka - prva po vrstnem redu", rd, DebridPravila.ponudnikNapake(listOf(
        rd to TorBoxPravila.NAPAKA_STREZNIK, tb to TorBoxPravila.NAPAKA_STREZNIK)))
    preveriEnako("A4: neznana napaka - prva storitev", ad, DebridPravila.ponudnikNapake(listOf(ad to "nekaj", tb to "drugo")))

    // Pregled Grok 8: odgovor API z mejo.
    preveriEnako("Grok 8: meja 8 MiB", 8 * 1024 * 1024, DebridPravila.NAJVEC_ODGOVORA)
    preveriEnako("Grok 8: pod mejo cel", 100, DebridPravila.beriOmejeno(java.io.ByteArrayInputStream(ByteArray(100)), 100)?.size)
    preveriEnako("Grok 8: nad mejo zavrnjen", null, DebridPravila.beriOmejeno(java.io.ByteArrayInputStream(ByteArray(101)), 100))
    preveriEnako("Grok 8: prazno", 0, DebridPravila.beriOmejeno(java.io.ByteArrayInputStream(ByteArray(0)))?.size)
    preveriEnako("Grok 8: vec kosov (40 KiB)", 40_000, DebridPravila.beriOmejeno(java.io.ByteArrayInputStream(ByteArray(40_000)))?.size)

    // Neodvisni pregled 5: brisemo samo torrent, ki ga je dodal ta poskus.
    val t0 = 1_793_000_000_000L
    preveriEnako("RD added ISO", 1_000L, DebridPravila.rdDodanoMs("1970-01-01T00:00:01.000Z"))
    preveriEnako("RD added brez ms", 0L, DebridPravila.rdDodanoMs("1970-01-01T00:00:00Z"))
    preveriEnako("RD added z zamikom +01:00", 1_000L, DebridPravila.rdDodanoMs("1970-01-01T01:00:01.000+01:00"))
    preveriEnako("RD added z zamikom -0230", 1_000L, DebridPravila.rdDodanoMs("1969-12-31T21:30:01-0230"))
    preveriEnako("RD added prazen", null, DebridPravila.rdDodanoMs(""))
    preveriEnako("RD added null", null, DebridPravila.rdDodanoMs(null))
    preveriEnako("RD added neberljiv", null, DebridPravila.rdDodanoMs("vceraj"))
    preveriEnako("AD uploadDate", 5_000L, DebridPravila.adDodanoMs(5L))
    preveriEnako("AD uploadDate 0", null, DebridPravila.adDodanoMs(0L))
    // Drugi pregled N3: primerjamo uri streznika (added/uploadDate proti glavi Date odgovora na dodajanje) - ura naprave ne
    // vstopa, zato naprava, ki prehiteva ali zaostaja (tudi za ure), odloci enako.
    val dateStreznika = t0
    for ((opisUre, uraNaprave) in listOf("ura naprave prehiteva 1 h" to t0 + 3_600_000, "ura naprave zaostaja 1 h" to t0 - 3_600_000, "ura naprave tocna" to t0)) {
        check(uraNaprave != 0L)
        preveriEnako("N3 $opisUre: nas (dodan ob odgovoru)", true, DebridPravila.dodalSmoMi(t0 - 400, dateStreznika))
        preveriEnako("N3 $opisUre: tuj (dodan minuto prej, druga naprava)", false, DebridPravila.dodalSmoMi(t0 - 60_000, dateStreznika))
    }
    preveriEnako("nas: dodan v rezervi 10 s", true, DebridPravila.dodalSmoMi(t0 - 9_000, t0))
    preveriEnako("ni nas: dodan 11 s prej", false, DebridPravila.dodalSmoMi(t0 - 11_000, t0))
    preveriEnako("ni nas: dodan uro prej", false, DebridPravila.dodalSmoMi(t0 - 3_600_000, t0))
    preveriEnako("neznano: ne brisi", false, DebridPravila.dodalSmoMi(null, t0))
    preveriEnako("brez glave Date: ne brisi", false, DebridPravila.dodalSmoMi(t0, null))
    preveriEnako("glava Date 0: ne brisi", false, DebridPravila.dodalSmoMi(t0, 0L))

    // Magnet povezave (lastnik 23. 10. 2026): s storitvijo prek nje, brez nje opozorilo pred rojem.
    preveriEnako("magnet s TorBoxom: prek storitve", DebridPravila.MAGNET_DEBRID, DebridPravila.potMagneta(DebridPravila.TORBOX, false, false))
    preveriEnako("magnet s storitvijo, potrjeno prej: se vedno prek storitve", DebridPravila.MAGNET_DEBRID, DebridPravila.potMagneta(DebridPravila.REAL_DEBRID, false, true))
    preveriEnako("magnet brez storitve: opozorilo", DebridPravila.MAGNET_OPOZORI, DebridPravila.potMagneta(null, false, false))
    preveriEnako("magnet brez storitve, zahteva z druge naprave: opozorilo", DebridPravila.MAGNET_OPOZORI, DebridPravila.potMagneta(null, true, false))
    preveriEnako("magnet brez storitve, potrjeno: neposredno", DebridPravila.MAGNET_P2P, DebridPravila.potMagneta(null, false, true))
    preveriEnako("s storitvijo izbrano neposredno: opozorilo", DebridPravila.MAGNET_OPOZORI, DebridPravila.potMagneta(DebridPravila.TORBOX, true, false))
    preveriEnako("s storitvijo izbrano neposredno, potrjeno: neposredno", DebridPravila.MAGNET_P2P, DebridPravila.potMagneta(DebridPravila.TORBOX, true, true))
    preveriEnako("ime iz dn", "Big Buck Bunny", DebridPravila.imeMagneta("magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c&dn=Big+Buck+Bunny&tr=udp%3A%2F%2Ftracker"))
    preveriEnako("brez dn: prazno", "", DebridPravila.imeMagneta("magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"))
    preveriEnako("pokvarjen dn: prazno", "", DebridPravila.imeMagneta("magnet:?xt=urn:btih:dd82&dn=%E0%A4%A"))

    // Lastnik 25. 10. 2026: vzorec kljuca iz navodil (»XXXXXXXX-XXXX-…«) se je shranil kot »kljuc vpisan ✓« in usklajevanje ga je
    // razneslo po ozjem krogu. Vzorec ni kljuc; prejeti vzorec ne gre naprej; stanje odloca o shranjevanju in usklajevanju.
    val vzorec = "XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX"
    val tbk = "3f1c9a2e-7b4d-4e8a-9c21-5d6f0b8e1a47"
    preveriEnako("vzorec X ni kljuc", true, DebridPravila.jeVzorecKljuca(vzorec))
    preveriEnako("vzorec x z 4 in y ni kljuc", true, DebridPravila.jeVzorecKljuca("xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx"))
    preveriEnako("ista crka ni kljuc", true, DebridPravila.jeVzorecKljuca("AAAAAAAAAAAAAAAAAAAAAAAA"))
    preveriEnako("your_api_key ni kljuc", true, DebridPravila.jeVzorecKljuca("YOUR_API_KEY_HERE_1234567"))
    preveriEnako("pravi UUID TorBox je kljuc", false, DebridPravila.jeVzorecKljuca(tbk))
    preveriEnako("RD zeton je kljuc", false, DebridPravila.jeVzorecKljuca("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOP"))
    preveriEnako("AD kljuc je kljuc", false, DebridPravila.jeVzorecKljuca("aBcD1234eFgH5678iJkL"))
    preveriEnako("vnos: vzorec TorBox zavrnjen", null, DebridPravila.kljucIzVnosa(" $vzorec ", tb))
    preveriEnako("vnos: pravi TorBox", tbk, DebridPravila.kljucIzVnosa(" $tbk\n", tb))
    preveriEnako("prejeti vzorec ne gre v shrambo (usklajevanje, Poslji kljuce)", emptyMap<String, String>(),
        DebridPravila.prejetiKljuci(mapOf(tb to vzorec, rd to "RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR")))
    preveriEnako("prejeti pravi ostane", mapOf(tb to tbk), DebridPravila.prejetiKljuci(mapOf(tb to tbk)))
    // Izid preverbe pri storitvi
    preveriEnako("TorBox 200 success: velja", DebridPravila.Preverba.VELJA, DebridPravila.preverbaTorBox(200, true, null))
    preveriEnako("TorBox 401 BAD_TOKEN: ne velja", DebridPravila.Preverba.NE_VELJA, DebridPravila.preverbaTorBox(401, false, "BAD_TOKEN"))
    preveriEnako("TorBox 403 AUTH_ERROR: ne velja", DebridPravila.Preverba.NE_VELJA, DebridPravila.preverbaTorBox(403, false, "AUTH_ERROR"))
    preveriEnako("TorBox 429: meja (kljuc verjetno dober)", DebridPravila.Preverba.MEJA, DebridPravila.preverbaTorBox(429, false, null))
    preveriEnako("TorBox 502: ni odgovora", DebridPravila.Preverba.NI_ODGOVORA, DebridPravila.preverbaTorBox(502, false, null))
    preveriEnako("brez povezave: ni odgovora", DebridPravila.Preverba.NI_ODGOVORA, DebridPravila.preverbaTorBox(0, false, null))
    preveriEnako("RD 200 z uporabnikom: velja", DebridPravila.Preverba.VELJA, DebridPravila.preverbaRd(200, null, true))
    preveriEnako("RD bad_token (8): ne velja", DebridPravila.Preverba.NE_VELJA, DebridPravila.preverbaRd(401, 8, false))
    preveriEnako("RD account locked (14): zaklenjen, ne velja", DebridPravila.Preverba.ZAKLENJEN, DebridPravila.preverbaRd(403, 14, false))
    preveriEnako("RD 9 nikoli velja", DebridPravila.Preverba.NE_VELJA, DebridPravila.preverbaRdOdgovor(403, 9L, null, null))
    preveriEnako("RD 22 IP not allowed: zaklenjen (prej ni odgovora - preverba na 10 min)", DebridPravila.Preverba.ZAKLENJEN, DebridPravila.preverbaRdOdgovor(403, 22L, null, null))
    preveriEnako("AD AUTH_BLOCKED: zaklenjen", DebridPravila.Preverba.ZAKLENJEN, DebridPravila.preverbaAd(200, "error", "AUTH_BLOCKED"))
    // Varovalo racunov K1: ne velja in zaklenjen se ne klice (tudi ne samodejna preverba); rocno najvec 1 na minuto.
    val SKL = DebridPravila.StanjeKljuca.values().toList()
    preveriEnako("K1: sme klicati", listOf(true, false, true, false), SKL.map { DebridPravila.smeKlicati(it) })
    preveriEnako("K1: brez stanja sme (preverba ob vpisu)", true, DebridPravila.smeKlicati(null))
    preveriEnako("K1: zaklenjen se shrani kot zaklenjen", DebridPravila.StanjeKljuca.ZAKLENJEN, DebridPravila.stanjePoPreverbi(DebridPravila.Preverba.ZAKLENJEN))
    preveriEnako("K1: zaklenjen se ne preveri sam", false, DebridPravila.preveriZdaj(DebridPravila.StanjeKljuca.ZAKLENJEN, 0L, 10_000_000L))
    preveriEnako("K1: zaklenjen prejet se ne sprejme", false, DebridPravila.sprejmiPrejetega(DebridPravila.Preverba.ZAKLENJEN))
    preveriEnako("K1: zaklenjen ne gre v krog", false, DebridPravila.zaUskladitev(DebridPravila.StanjeKljuca.ZAKLENJEN))
    preveriEnako("K1: rocno - prvic", true, DebridPravila.rocnaPreverbaDovoljena(0L, 1_000_000L))
    preveriEnako("K1: rocno - po 30 s ne", false, DebridPravila.rocnaPreverbaDovoljena(1_000_000L, 1_030_000L))
    preveriEnako("K1: rocno - po minuti da", true, DebridPravila.rocnaPreverbaDovoljena(1_000_000L, 1_060_000L))
    preveriEnako("K1: rocno brez odgovora - stanje ostane", null, DebridPravila.stanjePoRocniPreverbi(DebridPravila.Preverba.NI_ODGOVORA))
    preveriEnako("K1: rocno zavrnjen - ne velja", DebridPravila.StanjeKljuca.NE_VELJA, DebridPravila.stanjePoRocniPreverbi(DebridPravila.Preverba.NE_VELJA))
    preveriEnako("K1: rocno potrjen - velja", DebridPravila.StanjeKljuca.VELJA, DebridPravila.stanjePoRocniPreverbi(DebridPravila.Preverba.VELJA))
    preveriEnako("K1: nov kljuc zaklenjenega racuna ne zamenja veljavnega", DebridPravila.Vpis.ZAVRNI, DebridPravila.obVpisu(DebridPravila.StanjeKljuca.ZAKLENJEN, shranjenVelja = true, enak = false))
    preveriEnako("K1: isti kljuc dobi zaklenjen", DebridPravila.Vpis.ZAMENJAJ, DebridPravila.obVpisu(DebridPravila.StanjeKljuca.ZAKLENJEN, shranjenVelja = true, enak = true))
    preveriEnako("K1: cakajoci zaklenjen se zavrze (brez ponovitev)", DebridPravila.Cakajoc.ZAVRZI, DebridPravila.poPreverbiCakajocega(DebridPravila.Preverba.ZAKLENJEN))
    preveriEnako("K1: zaklenjen je najpomembnejsi razlog", TorBoxPravila.NAPAKA_ZAKLENJEN, DebridPravila.razlogZaUporabnika(listOf(TorBoxPravila.NAPAKA_KLJUC, TorBoxPravila.NAPAKA_ZAKLENJEN)))
    // Pregled Grok 34, E3 / 33, F7: brez pozitivnega dokaza ni »velja«.
    preveriEnako("E3: RD 403 brez telesa (CDN, blokada IP): ni odgovora", DebridPravila.Preverba.NI_ODGOVORA, DebridPravila.preverbaRdOdgovor(403, null, null, null))
    preveriEnako("E3: RD 403 permission_denied (9) s kodo RD: ne velja (varovalo racunov - nikoli velja)", DebridPravila.Preverba.NE_VELJA, DebridPravila.preverbaRdOdgovor(403, 9, null, null))
    preveriEnako("F7: RD 200 brez telesa: ni odgovora", DebridPravila.Preverba.NI_ODGOVORA, DebridPravila.preverbaRdOdgovor(200, null, null, null))
    preveriEnako("F7: RD 200 z id: velja", DebridPravila.Preverba.VELJA, DebridPravila.preverbaRdOdgovor(200, null, 123L, null))
    preveriEnako("F7: RD 200 z username: velja", DebridPravila.Preverba.VELJA, DebridPravila.preverbaRdOdgovor(200, null, null, "uporabnik"))
    preveriEnako("F7: RD 200 s praznim username: ni odgovora", DebridPravila.Preverba.NI_ODGOVORA, DebridPravila.preverbaRdOdgovor(200, null, null, " "))
    preveriEnako("E3: RD 401 brez telesa: ne velja (skupni primeri)", DebridPravila.Preverba.NE_VELJA, DebridPravila.preverbaRdOdgovor(401, null, null, null))
    preveriEnako("E3: RD 429 brez telesa: meja", DebridPravila.Preverba.MEJA, DebridPravila.preverbaRdOdgovor(429, null, null, null))
    preveriEnako("E3: RD 502 brez telesa: ni odgovora", DebridPravila.Preverba.NI_ODGOVORA, DebridPravila.preverbaRdOdgovor(502, null, null, null))
    preveriEnako("AD success: velja", DebridPravila.Preverba.VELJA, DebridPravila.preverbaAd(200, "success", null))
    preveriEnako("AD AUTH_BAD_APIKEY s HTTP 200: ne velja", DebridPravila.Preverba.NE_VELJA, DebridPravila.preverbaAd(200, "error", "AUTH_BAD_APIKEY"))
    // Varovalo racunov: stanje racuna (brez kljuca) v ozji krog - ne velja / zaklenjen ustavi vse naprave, Preveri jih spusti.
    val SK2 = DebridPravila.StanjeKljuca.values().toList()
    preveriEnako("krog: dokoncna stanja", listOf(true, true, false, true), SK2.map { DebridPravila.stanjeVKrog(it) })
    preveriEnako("krog: zaklenjen z druge naprave ustavi tukajsnji veljaven", true, DebridPravila.sprejmiStanje(1_000L, DebridPravila.StanjeKljuca.ZAKLENJEN, 2_000L, 3_000L))
    preveriEnako("krog: ne velja brez tukajsnjega stanja", true, DebridPravila.sprejmiStanje(null, DebridPravila.StanjeKljuca.NE_VELJA, 2_000L, 3_000L))
    preveriEnako("krog: starejse ne", false, DebridPravila.sprejmiStanje(2_000L, DebridPravila.StanjeKljuca.VELJA, 1_000L, 3_000L))
    preveriEnako("krog: enak cas ne (brez ping-ponga)", false, DebridPravila.sprejmiStanje(2_000L, DebridPravila.StanjeKljuca.ZAKLENJEN, 2_000L, 3_000L))
    preveriEnako("krog: nepreverjen ne gre", false, DebridPravila.sprejmiStanje(null, DebridPravila.StanjeKljuca.NEPREVERJEN, 2_000L, 3_000L))
    preveriEnako("krog: iz dalnje prihodnosti ne", false, DebridPravila.sprejmiStanje(null, DebridPravila.StanjeKljuca.ZAKLENJEN, 3_000L + 11 * 60_000L, 3_000L))
    preveriEnako("krog: brez casa ne", false, DebridPravila.sprejmiStanje(null, DebridPravila.StanjeKljuca.ZAKLENJEN, 0L, 3_000L))
    preveriEnako("krog: ime stanja", DebridPravila.StanjeKljuca.ZAKLENJEN, DebridPravila.stanjeIzImena("zaklenjen"))
    preveriEnako("krog: neznano ime", null, DebridPravila.stanjeIzImena("odklenjen"))
    // Pregled Grok 33, F5: izid zahteve velja samo za kljuc in storitev zahteve.
    preveriEnako("F5: isti kljuc in storitev", true, DebridPravila.izidZaKljuc("K1", "K1", "torbox", "torbox"))
    preveriEnako("F5: storitev izida ni vpisana (prazno)", true, DebridPravila.izidZaKljuc("K1", "K1", "realdebrid", ""))
    preveriEnako("F5: kljuc zamenjan med zahtevo", false, DebridPravila.izidZaKljuc("K2", "K1", "torbox", "torbox"))
    preveriEnako("F5: izid druge storitve", false, DebridPravila.izidZaKljuc("K1", "K1", "realdebrid", "alldebrid"))
    preveriEnako("F5: kljuca medtem ni", false, DebridPravila.izidZaKljuc(null, "K1", "torbox", "torbox"))
    // Pregled Grok 33, F9: prejeti kljuc, ki ga ni bilo mogoce preveriti, ni »zavrnjen«.
    preveriEnako("F9: prejet veljaven: shranjen", DebridPravila.Prejem.SHRANJEN, DebridPravila.prejem(DebridPravila.Preverba.VELJA))
    preveriEnako("F9: prejet brez povezave: ni preverjen (poslji znova)", DebridPravila.Prejem.NI_PREVERJEN, DebridPravila.prejem(DebridPravila.Preverba.NI_ODGOVORA))
    preveriEnako("F9: prejet ob meji: ni preverjen", DebridPravila.Prejem.NI_PREVERJEN, DebridPravila.prejem(DebridPravila.Preverba.MEJA))
    preveriEnako("F9: prejet zavrnjen: zavrnjen", DebridPravila.Prejem.ZAVRNJEN, DebridPravila.prejem(DebridPravila.Preverba.NE_VELJA))
    preveriEnako("F9: prejet zaklenjen racun: zavrnjen", DebridPravila.Prejem.ZAVRNJEN, DebridPravila.prejem(DebridPravila.Preverba.ZAKLENJEN))
    // Pregled Grok 33, F3: vpis brez povezave (429, 5xx) ne prepise veljavnega - caka ob strani.
    preveriEnako("F3: veljaven + nepreverjen nov -> ob strani", DebridPravila.Vpis.OB_STRANI, DebridPravila.obVpisu(DebridPravila.StanjeKljuca.NEPREVERJEN, shranjenVelja = true, enak = false))
    preveriEnako("F3: veljaven + isti nepreverjen -> ohrani", DebridPravila.Vpis.OHRANI, DebridPravila.obVpisu(DebridPravila.StanjeKljuca.NEPREVERJEN, shranjenVelja = true, enak = true))
    preveriEnako("F3: potrjen nov zamenja", DebridPravila.Vpis.ZAMENJAJ, DebridPravila.obVpisu(DebridPravila.StanjeKljuca.VELJA, shranjenVelja = true, enak = false))
    preveriEnako("F3: brez veljavnega nepreverjen zamenja", DebridPravila.Vpis.ZAMENJAJ, DebridPravila.obVpisu(DebridPravila.StanjeKljuca.NEPREVERJEN, shranjenVelja = false, enak = false))
    preveriEnako("F3: cakajoci potrjen zamenja", DebridPravila.Cakajoc.ZAMENJAJ, DebridPravila.poPreverbiCakajocega(DebridPravila.Preverba.VELJA))
    preveriEnako("F3: cakajoci zavrnjen se zavrze", DebridPravila.Cakajoc.ZAVRZI, DebridPravila.poPreverbiCakajocega(DebridPravila.Preverba.NE_VELJA))
    preveriEnako("F3: cakajoci vzorec se zavrze", DebridPravila.Cakajoc.ZAVRZI, DebridPravila.poPreverbiCakajocega(DebridPravila.Preverba.OBLIKA))
    preveriEnako("F3: cakajoci brez povezave caka", DebridPravila.Cakajoc.CAKA, DebridPravila.poPreverbiCakajocega(DebridPravila.Preverba.NI_ODGOVORA))
    preveriEnako("F3: cakajoci meja caka", DebridPravila.Cakajoc.CAKA, DebridPravila.poPreverbiCakajocega(DebridPravila.Preverba.MEJA))
    // Shranjevanje, usklajevanje, prejem
    preveriEnako("zavrnjen se ne shrani", null, DebridPravila.stanjePoPreverbi(DebridPravila.Preverba.NE_VELJA))
    preveriEnako("vzorec se ne shrani", null, DebridPravila.stanjePoPreverbi(DebridPravila.Preverba.OBLIKA))
    preveriEnako("brez povezave: nepreverjen", DebridPravila.StanjeKljuca.NEPREVERJEN, DebridPravila.stanjePoPreverbi(DebridPravila.Preverba.NI_ODGOVORA))
    preveriEnako("veljaven: velja", DebridPravila.StanjeKljuca.VELJA, DebridPravila.stanjePoPreverbi(DebridPravila.Preverba.VELJA))
    preveriEnako("usklajevanje: samo veljaven", listOf(true, false, false, false),
        listOf(DebridPravila.StanjeKljuca.VELJA, DebridPravila.StanjeKljuca.NEPREVERJEN, DebridPravila.StanjeKljuca.NE_VELJA, null).map { DebridPravila.zaUskladitev(it) })
    preveriEnako("prejeti: samo, ce ga storitev potrdi tudi tu", listOf(true, false, false, false),
        listOf(DebridPravila.Preverba.VELJA, DebridPravila.Preverba.NE_VELJA, DebridPravila.Preverba.NI_ODGOVORA, DebridPravila.Preverba.MEJA).map { DebridPravila.sprejmiPrejetega(it) })
    // Stanje je vezano na kljuc
    val o1 = DebridPravila.odtisStanja(tbk)
    preveriEnako("odtis ne razkrije kljuca", false, o1.contains(tbk.take(8)))
    val zapis = DebridPravila.zapisStanja(DebridPravila.StanjeKljuca.VELJA, o1, 2, 1000L)
    preveriEnako("stanje istega kljuca", Triple(DebridPravila.StanjeKljuca.VELJA, 2, 1000L), DebridPravila.stanjeIzZapisa(zapis, o1))
    preveriEnako("stanje drugega kljuca ne velja", null, DebridPravila.stanjeIzZapisa(zapis, DebridPravila.odtisStanja(vzorec)))
    preveriEnako("ime paketa Pro", "Pro", DebridPravila.imePaketa(2))
    preveriEnako("preveri: brez stanja da (se ni bil preverjen)", true, DebridPravila.preveriZdaj(null, 0L, 1_800_000_000_000L))
    preveriEnako("v2: brez stanja najvec enkrat na 10 min za ta kljuc", false, DebridPravila.preveriZdaj(null, 1_000_000L, 1_001_000L))
    preveriEnako("preveri: nepreverjen po 10 min da", true, DebridPravila.preveriZdaj(DebridPravila.StanjeKljuca.NEPREVERJEN, 0L, DebridPravila.PREVERI_ZNOVA_MS))
    preveriEnako("preveri: nepreverjen pred 10 min ne", false, DebridPravila.preveriZdaj(DebridPravila.StanjeKljuca.NEPREVERJEN, 0L, DebridPravila.PREVERI_ZNOVA_MS - 1))
    preveriEnako("preveri: veljaven ne (do napake 401)", false, DebridPravila.preveriZdaj(DebridPravila.StanjeKljuca.VELJA, 0L, 10 * DebridPravila.PREVERI_ZNOVA_MS))

    // Pregled Grok 41, Z2: ponoven vpis istega kljuca brez odgovora ne odklene »ne velja« ali »zaklenjen«; vpis in »Preveri«
    // istega kljuca najvec enkrat na minuto (po kljucu, ne po storitvi).
    val sk = DebridPravila.StanjeKljuca.values().associateBy { it.name }
    preveriEnako("Z2: zaklenjen + isti kljuc + brez odgovora -> zaklenjen", sk["ZAKLENJEN"],
        DebridPravila.stanjePoVpisu(sk["ZAKLENJEN"], DebridPravila.Preverba.NI_ODGOVORA))
    preveriEnako("Z2: ne velja + isti kljuc + meja (429) -> ne velja", sk["NE_VELJA"],
        DebridPravila.stanjePoVpisu(sk["NE_VELJA"], DebridPravila.Preverba.MEJA))
    preveriEnako("Z2: zaklenjen + odgovor velja -> velja", sk["VELJA"], DebridPravila.stanjePoVpisu(sk["ZAKLENJEN"], DebridPravila.Preverba.VELJA))
    preveriEnako("Z2: zaklenjen + odgovor ne velja -> ne shrani", null, DebridPravila.stanjePoVpisu(sk["ZAKLENJEN"], DebridPravila.Preverba.NE_VELJA))
    preveriEnako("Z2: nov kljuc brez odgovora -> nepreverjen", sk["NEPREVERJEN"], DebridPravila.stanjePoVpisu(null, DebridPravila.Preverba.NI_ODGOVORA))
    preveriEnako("Z2: veljaven isti kljuc brez odgovora -> nepreverjen (obVpisu ga ohrani)", sk["NEPREVERJEN"],
        DebridPravila.stanjePoVpisu(sk["VELJA"], DebridPravila.Preverba.NI_ODGOVORA))
    val rocne = DebridPravila.RocnePreverbe()
    val tr0 = 1_800_000_000_000L
    preveriEnako("Z2: prvi vpis kljuca gre storitvi", 0L, rocne.cez(rd, "o1", tr0))
    preveriEnako("Z2: isti kljuc cez 30 s - brez klica, se 30 s", 30_000L, rocne.cez(rd, "o1", tr0 + 30_000))
    preveriEnako("Z2: drug kljuc iste storitve gre", 0L, rocne.cez(rd, "o2", tr0 + 30_000))
    preveriEnako("Z2: isti odtis druge storitve gre", 0L, rocne.cez(tb, "o1", tr0 + 30_000))
    preveriEnako("Z2: isti kljuc po minuti spet gre", 0L, rocne.cez(rd, "o1", tr0 + 60_000))
    preveriEnako("Z2: ura nazaj ne zaklene za vedno", 0L, rocne.cez(rd, "o1", tr0 - 1))

    // Pregled Grok 41, Z3 (in namizje S3): zapora po kljucu, ne po storitvi; »Razveljavi« ne vrne zaklenjenega kljuca kot
    // nepreverjenega; zapora v pomnilniku velja pred zapisom; neberljiv zapis istega kljuca odpove zaprto.
    val zo1 = "aaaaaaaaaaaaaaaa"; val zo2 = "bbbbbbbbbbbbbbbb"
    var zap = DebridPravila.zZaporo(null, zo1, DebridPravila.StanjeKljuca.ZAKLENJEN)
    zap = DebridPravila.zZaporo(zap, zo2, DebridPravila.StanjeKljuca.VELJA)
    preveriEnako("Z3: zapora K1 ostane, ko je shranjen veljaven K2", sk["ZAKLENJEN"], DebridPravila.zaporeIzZapisa(zap)[zo1])
    preveriEnako("Z3: K1 (na mestu je K2) je zaklenjen, ne »brez stanja«", sk["ZAKLENJEN"],
        DebridPravila.stanjeZZaporo(null, null, DebridPravila.zaporeIzZapisa(zap)[zo1]))
    preveriEnako("Z3: »Razveljavi« vrne zaklenjen K1 zaklenjen", sk["ZAKLENJEN"], DebridPravila.stanjeObObnovi(sk["ZAKLENJEN"]))
    preveriEnako("Z3: »Razveljavi« kljuca, ki ne velja - ne velja", sk["NE_VELJA"], DebridPravila.stanjeObObnovi(sk["NE_VELJA"]))
    preveriEnako("Z3: »Razveljavi« brez zapore - nepreverjen (ena preverba)", sk["NEPREVERJEN"], DebridPravila.stanjeObObnovi(null))
    preveriEnako("Z3: nepreverjen na mestu zapore ne odpravi", sk["NE_VELJA"], DebridPravila.stanjeZZaporo(null, sk["NEPREVERJEN"], sk["NE_VELJA"]))
    preveriEnako("Z3: »velja« na mestu (Preveri) zaporo odpravi", sk["VELJA"], DebridPravila.stanjeZZaporo(null, sk["VELJA"], sk["NE_VELJA"]))
    preveriEnako("Z3: »velja« pobrise zapis zapore", null,
        DebridPravila.zaporeIzZapisa(DebridPravila.zZaporo(zap, zo1, DebridPravila.StanjeKljuca.VELJA))[zo1])
    preveriEnako("Z3: »nepreverjen« zapore ne pobrise", sk["ZAKLENJEN"],
        DebridPravila.zaporeIzZapisa(DebridPravila.zZaporo(zap, zo1, DebridPravila.StanjeKljuca.NEPREVERJEN))[zo1])
    preveriEnako("S3: zapora v pomnilniku velja, tudi ce zapisa ni", sk["ZAKLENJEN"], DebridPravila.stanjeZZaporo(sk["ZAKLENJEN"], null, null))
    preveriEnako("S3: zapora v pomnilniku pred starim »velja« na mestu", sk["NE_VELJA"], DebridPravila.stanjeZZaporo(sk["NE_VELJA"], sk["VELJA"], null))
    var mnogo: String? = null
    for (i in 0 until 12) mnogo = DebridPravila.zZaporo(mnogo, "odtis$i", DebridPravila.StanjeKljuca.NE_VELJA)
    val mz = DebridPravila.zaporeIzZapisa(mnogo)
    preveriEnako("Z3: najvec 8 zapor, najstarejse odpadejo", listOf(8, null, sk["NE_VELJA"]), listOf(mz.size, mz["odtis0"], mz["odtis11"]))
    preveriEnako("S3: neberljiv zapis je »ne velja«", sk["NE_VELJA"], DebridPravila.stanjeIzZapisa("pokvarjeno", o1)?.first)
    preveriEnako("S3: neznano stanje istega kljuca je »ne velja«", sk["NE_VELJA"], DebridPravila.stanjeIzZapisa("NOVO|$o1||5", o1)?.first)
    preveriEnako("S3: zapis istega kljuca brez casa je »ne velja«", sk["NE_VELJA"], DebridPravila.stanjeIzZapisa("VELJA|$o1", o1)?.first)
    preveriEnako("S3: zapis drugega kljuca ostane »brez stanja«", null,
        DebridPravila.stanjeIzZapisa("VELJA|${DebridPravila.odtisStanja(vzorec)}||5", o1))

    if (napak == 0) println("DebridPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
