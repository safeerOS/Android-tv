package si.safeer.tv.link

import kotlin.system.exitProcess

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/**
 * Datoteke telefona v Linku ([LinkDatotekePravila]): deli velike galerije, datum posnetka, raven dostopa (tudi delni
 * dostop Androida 14), navidezne mape, poti `fs:` in slicice.
 */
fun main() {
    println("Preizkus pravil Link datotek")
    val P = LinkDatotekePravila

    // --- deli seznama: 1234 vnosov -> trije deli (500, 500, 234), naslednji del se ne prekriva s prejsnjim
    preveriEnako("privzeto 500 od zacetka", LinkDatotekePravila.Stran(0, 500), P.stran(0, 0))
    preveriEnako("meja navzgor omejena na 500", LinkDatotekePravila.Stran(0, 500), P.stran(0, 10_000))
    preveriEnako("negativni odmik = 0", LinkDatotekePravila.Stran(0, 50), P.stran(-5, 50))
    preveriEnako("negativna meja = privzeto", LinkDatotekePravila.Stran(10, 500), P.stran(10, -1))
    preveriEnako("prevelik odmik ne prelije Int", Int.MAX_VALUE, P.stran(Long.MAX_VALUE, 1).odmik)
    val skupaj = 1234
    var odmik = 0
    val deli = ArrayList<Int>()
    do {
        val s = P.stran(odmik.toLong(), 0)
        val vrnjenih = minOf(s.meja, skupaj - s.odmik)
        deli.add(vrnjenih)
        odmik = P.naslednji(s.odmik, vrnjenih, skupaj)
    } while (odmik != 0 && deli.size < 10)
    preveriEnako("1234 vnosov v treh delih", listOf(500, 500, 234), deli)
    preveriEnako("zadnji del: next_offset 0", 0, P.naslednji(1000, 234, 1234))
    preveriEnako("natanko 500: brez naslednjega", 0, P.naslednji(0, 500, 500))
    preveriEnako("prazna mapa: brez naslednjega", 0, P.naslednji(0, 0, 0))
    preveriEnako("izpusceni vnosi se ne ponovijo (pregledanih, ne vrnjenih)", 500, P.naslednji(0, 500, 501))

    // --- datum nastanka in obdobje
    preveriEnako("datum posnetka ima prednost", 1_000L, P.datumNastanka(1_000L, 9_000L))
    preveriEnako("brez datuma posnetka: sprememba", 9_000L, P.datumNastanka(0L, 9_000L))
    preveriEnako("negativen datum posnetka: sprememba", 9_000L, P.datumNastanka(-1L, 9_000L))
    preveriEnako("brez meja ni obdobja", null, P.obdobje(null, null))
    preveriEnako("negativne meje = brez meja", null, P.obdobje(-1L, -1L))
    val o = P.obdobje(1_000L, 2_000L)
    preveriEnako("od je vkljucno", true, P.vObdobju(1_000L, o))
    preveriEnako("do je izkljucno", false, P.vObdobju(2_000L, o))
    preveriEnako("pred obdobjem", false, P.vObdobju(999L, o))
    preveriEnako("samo od", true, P.vObdobju(5_000L, P.obdobje(1_000L, null)))
    preveriEnako("samo do", true, P.vObdobju(5L, P.obdobje(null, 1_000L)))
    preveriEnako("prazno obdobje", true, LinkDatotekePravila.Obdobje(5L, 5L).prazno())
    preveriEnako("izbor SQL z vpisanimi stevili (ne ?)",
        "COALESCE(NULLIF(datetaken, 0), date_modified * 1000) >= 1000 AND COALESCE(NULLIF(datetaken, 0), date_modified * 1000) < 2000",
        P.izborObdobja(o))
    preveriEnako("izbor SQL samo do, glasba", "date_modified * 1000 < 7", P.izborObdobja(P.obdobje(null, 7L), P.IZRAZ_SPREMEMBE))
    preveriEnako("brez obdobja ni izbora", null, P.izborObdobja(null))
    preveriEnako("zdruzi oba", "(a) AND (b)", P.zdruzi("a", "b"))
    preveriEnako("zdruzi brez prvega", "b", P.zdruzi(null, "b"))
    preveriEnako("zdruzi brez drugega", "a", P.zdruzi("a", " "))
    preveriEnako("zdruzi nic", null, P.zdruzi(null, null))

    // --- dovoljenja in raven dostopa
    preveriEnako("Android 14 vprasa tudi izbrane fotografije", true, P.IZBRANE in P.dovoljenja(34))
    preveriEnako("Android 13 brez izbranih", false, P.IZBRANE in P.dovoljenja(33))
    preveriEnako("Android 12 shramba", listOf(P.SHRAMBA), P.dovoljenja(32))
    preveriEnako("Android 14 samo izbrane fotografije = delni (napaka imamoDovoljenje)", LinkDatotekePravila.Dostop.DELNI,
        P.dostop(34, setOf(P.IZBRANE), false))
    preveriEnako("Android 14 vse slike in videi = poln", LinkDatotekePravila.Dostop.POLN, P.dostop(34, setOf(P.SLIKE, P.VIDEI, P.IZBRANE), false))
    preveriEnako("Android 13 slike in videi = poln", LinkDatotekePravila.Dostop.POLN, P.dostop(33, setOf(P.SLIKE, P.VIDEI, P.ZVOK), false))
    preveriEnako("Android 13 samo glasba = delni", LinkDatotekePravila.Dostop.DELNI, P.dostop(33, setOf(P.ZVOK), false))
    preveriEnako("Android 13 izbrane ne obstajajo", LinkDatotekePravila.Dostop.BREZ, P.dostop(33, setOf(P.IZBRANE), false))
    preveriEnako("brez dovoljenj = brez", LinkDatotekePravila.Dostop.BREZ, P.dostop(34, emptySet(), false))
    preveriEnako("Android 12 shramba = poln", LinkDatotekePravila.Dostop.POLN, P.dostop(31, setOf(P.SHRAMBA), false))
    preveriEnako("Android 12 brez shrambe = brez", LinkDatotekePravila.Dostop.BREZ, P.dostop(31, emptySet(), false))
    preveriEnako("dostop do vseh datotek = poln", LinkDatotekePravila.Dostop.POLN, P.dostop(34, emptySet(), true))
    preveriEnako("oznake za odjemalca", listOf("full", "partial", "none"), LinkDatotekePravila.Dostop.values().map { it.oznaka })
    // Ena privolitev v Linku (lastnik 25. 10. 2026 10:49): najboljse, kar naprava zmore - vse datoteke, sicer mediji.
    val POLN = LinkDatotekePravila.Dostop.POLN
    val DELNI = LinkDatotekePravila.Dostop.DELNI
    val BREZ = LinkDatotekePravila.Dostop.BREZ
    val VSE = LinkDatotekePravila.Prosnja.VSE_DATOTEKE
    val MEDIJI = LinkDatotekePravila.Prosnja.FOTOGRAFIJE
    val NIC = LinkDatotekePravila.Prosnja.NIC
    preveriEnako("prosnja: vse mogoce, se ne odobreno -> vse datoteke", VSE, P.prosnja(POLN, false, true))
    preveriEnako("prosnja: delni (izbrane fotografije), vse mogoce -> vse datoteke", VSE, P.prosnja(DELNI, false, true))
    preveriEnako("prosnja: brez dovoljenj, vse mogoce -> vse datoteke", VSE, P.prosnja(BREZ, false, true))
    preveriEnako("prosnja: vse ze odobreno -> nic", NIC, P.prosnja(POLN, true, true))
    preveriEnako("prosnja: brez moznosti vseh (TV, Android 10), delni -> mediji", MEDIJI, P.prosnja(DELNI, false, false))
    preveriEnako("prosnja: brez moznosti vseh, brez -> mediji", MEDIJI, P.prosnja(BREZ, false, false))
    preveriEnako("prosnja: brez moznosti vseh, poln -> nic", NIC, P.prosnja(POLN, false, false))

    // --- navidezne mape
    preveriEnako("prazno = koren", LinkDatotekePravila.Mapa.Koren, P.mapa(""))
    preveriEnako("root = koren", LinkDatotekePravila.Mapa.Koren, P.mapa("root"))
    preveriEnako("stara zbirka", LinkDatotekePravila.Mapa.Zbirka("image"), P.mapa("media:image"))
    preveriEnako("shramba", LinkDatotekePravila.Mapa.Zbirka("shramba"), P.mapa("media:shramba"))
    preveriEnako("kamera", LinkDatotekePravila.Mapa.Kamera, P.mapa(P.KAMERA))
    preveriEnako("posnetki zaslona", LinkDatotekePravila.Mapa.Posnetki, P.mapa(P.POSNETKI))
    preveriEnako("albumi", LinkDatotekePravila.Mapa.Albumi, P.mapa(P.ALBUMI))
    preveriEnako("album s stevilko", LinkDatotekePravila.Mapa.Album(-1739773001L), P.mapa("media:bucket:-1739773001"))
    preveriEnako("album s potjo zavrnjen", null, P.mapa("media:bucket:../"))
    preveriEnako("album z vrinjenim SQL zavrnjen", null, P.mapa("media:bucket:1 OR 1=1"))
    preveriEnako("album prazen zavrnjen", null, P.mapa("media:bucket:"))
    preveriEnako("album predolg zavrnjen", null, P.mapa("media:bucket:12345678901234567890"))
    preveriEnako("neznana zbirka", null, P.mapa("media:sistem"))
    preveriEnako("datoteka ni mapa", null, P.mapa("media:image:12"))
    preveriEnako("tuja oznaka", null, P.mapa("/storage/emulated/0"))
    preveriEnako("urejanje v kameri", true, P.urejanje(LinkDatotekePravila.Mapa.Kamera))
    preveriEnako("urejanje v albumu", true, P.urejanje(LinkDatotekePravila.Mapa.Album(1)))
    preveriEnako("brez urejanja v korenu", false, P.urejanje(LinkDatotekePravila.Mapa.Koren))
    preveriEnako("brez urejanja v seznamu albumov", false, P.urejanje(LinkDatotekePravila.Mapa.Albumi))
    preveriEnako("brez urejanja v mapah (samo branje)", false, P.urejanje(LinkDatotekePravila.Mapa.Pot("DCIM")))

    // --- poti fs:
    preveriEnako("koren fs", "", P.potFs("fs:"))
    preveriEnako("koren fs s posevnico", "", P.potFs("fs:/"))
    preveriEnako("navadna pot", "DCIM/Camera", P.potFs("fs:DCIM/Camera"))
    preveriEnako("posevnica spredaj in zadaj", "Download/a.pdf", P.potFs("fs:/Download/a.pdf"))
    preveriEnako("sumniki in presledki", "Dokumenti/Šola 2026/zapiski.txt", P.potFs("fs:Dokumenti/Šola 2026/zapiski.txt"))
    preveriEnako("Android/media (WhatsApp) dovoljeno", "Android/media/com.whatsapp/x.jpg", P.potFs("fs:Android/media/com.whatsapp/x.jpg"))
    preveriEnako(".. zavrnjeno", null, P.potFs("fs:DCIM/../.."))
    preveriEnako(". zavrnjeno", null, P.potFs("fs:./DCIM"))
    preveriEnako("skrita mapa zavrnjena", null, P.potFs("fs:.thumbnails/1.jpg"))
    preveriEnako("skrita datoteka zavrnjena", null, P.potFs("fs:Download/.skrito"))
    preveriEnako("Android/data zavrnjeno", null, P.potFs("fs:Android/data/com.banka/a.db"))
    preveriEnako("Android/obb zavrnjeno", null, P.potFs("fs:android/OBB"))
    preveriEnako("dvojna posevnica zavrnjena", null, P.potFs("fs:DCIM//x"))
    preveriEnako("dve posevnici spredaj zavrnjeni", null, P.potFs("fs://etc/passwd"))
    preveriEnako("poševnica nazaj zavrnjena", null, P.potFs("fs:a\\..\\b"))
    preveriEnako("kontrolni znak zavrnjen", null, P.potFs("fs:a\u0000b"))
    preveriEnako("predolga pot zavrnjena", null, P.potFs("fs:" + "a/".repeat(2100)))
    preveriEnako("ne fs: oznaka", null, P.potFs("media:image:1"))
    preveriEnako("mapa fs", LinkDatotekePravila.Mapa.Pot("Download"), P.mapa("fs:Download"))
    preveriEnako("mapa fs zavrnjena", null, P.mapa("fs:Android/data"))
    val koren = "/storage/emulated/0"
    preveriEnako("kanonicna znotraj", "DCIM/a.jpg", P.relativnaZnotraj(koren, "/storage/emulated/0/DCIM/a.jpg"))
    preveriEnako("kanonicna je koren", "", P.relativnaZnotraj("$koren/", koren))
    preveriEnako("povezava ven iz korena", null, P.relativnaZnotraj(koren, "/data/data/si.safeer.phone/shared_prefs/x.xml"))
    preveriEnako("sosednja mapa s skupno predpono", null, P.relativnaZnotraj(koren, "/storage/emulated/01/x"))
    preveriEnako("povezava v Android/data", null, P.relativnaZnotraj(koren, "/storage/emulated/0/Android/data/x/y"))
    preveriEnako("povezava v skrito mapo", null, P.relativnaZnotraj(koren, "/storage/emulated/0/.skrito/y"))
    preveriEnako("prazen koren nic ne odpre", null, P.relativnaZnotraj("", "/x"))

    // --- vrstni red in filter mape fs
    val vnosi = listOf(
        LinkDatotekePravila.Vnos("b.jpg", false, 10, 2_000), LinkDatotekePravila.Vnos("Zeta", true, 0, 1), LinkDatotekePravila.Vnos("a.jpg", false, 10, 3_000),
        LinkDatotekePravila.Vnos(".skrito", false, 1, 9_999), LinkDatotekePravila.Vnos("alfa", true, 0, 5), LinkDatotekePravila.Vnos("c.jpg", false, 10, 2_000),
        LinkDatotekePravila.Vnos("data", true, 0, 1))
    preveriEnako("mape po imenu, datoteke od najnovejse, brez skritih",
        listOf("alfa", "data", "Zeta", "a.jpg", "b.jpg", "c.jpg"), P.uredi("", vnosi).map { it.ime })
    preveriEnako("v mapi Android ni data", listOf("alfa", "Zeta", "a.jpg", "b.jpg", "c.jpg"), P.uredi("Android", vnosi).map { it.ime })
    preveriEnako("obdobje velja za datoteke, mape ostanejo", listOf("alfa", "data", "Zeta", "b.jpg", "c.jpg"),
        P.uredi("", vnosi, P.obdobje(0L, 2_500L)).map { it.ime })
    // Fotografija, prenesena na telefon danes (nov cas spremembe), posneta pa lani: po datumu posnetka.
    val posnete = listOf(LinkDatotekePravila.Vnos("star.jpg", false, 1, 9_000, posneto = 1_000),
        LinkDatotekePravila.Vnos("nov.jpg", false, 1, 5_000))
    preveriEnako("datoteke po datumu posnetka", listOf("nov.jpg", "star.jpg"), P.uredi("DCIM", posnete).map { it.ime })
    preveriEnako("obdobje po datumu posnetka", listOf("star.jpg"), P.uredi("DCIM", posnete, P.obdobje(0L, 2_000L)).map { it.ime })
    preveriEnako("vrsta iz MIME", listOf("video", "audio", "image", "file"),
        listOf("video/mp4", "audio/mpeg", "image/heic", "application/pdf").map { P.vrsta(it) })

    // --- albumi
    val z = LinkDatotekePravila.ZbiralnikAlbumov()
    z.dodaj(1L, "Camera", 100L); z.dodaj(2L, "WhatsApp Images", 300L); z.dodaj(1L, "Camera", 500L); z.dodaj(3L, null, 50L)
    val albumi = z.seznam()
    preveriEnako("albumi od najnovejsega", listOf(1L, 2L, 3L), albumi.map { it.id })
    preveriEnako("stevilo v albumu", 2, albumi[0].stevilo)
    preveriEnako("najnovejse v albumu", 500L, albumi[0].najnovejse)
    preveriEnako("album brez imena dobi stevilko", "3", albumi[2].ime)
    preveriEnako("ime albuma iz MediaStore", "Camera", P.imeAlbuma("Camera", "DCIM/Camera/", "Notranji pomnilnik"))
    preveriEnako("album v korenu pomnilnika", "Notranji pomnilnik", P.imeAlbuma(null, "/", "Notranji pomnilnik"))
    preveriEnako("album v korenu, prazna pot", "Notranji pomnilnik", P.imeAlbuma(" ", "", "Notranji pomnilnik"))
    preveriEnako("album brez imena: zadnji del poti", "Prenosi", P.imeAlbuma(null, "Download/Prenosi/", "Notranji pomnilnik"))
    preveriEnako("album brez imena in poti", null, P.imeAlbuma(null, null, "Notranji pomnilnik"))

    // --- slicice
    preveriEnako("rob privzeto", 256, P.rob(null))
    preveriEnako("rob neveljaven", 256, P.rob("abc"))
    preveriEnako("rob 999 -> 512", 512, P.rob("999"))
    preveriEnako("rob 10 -> 128", 128, P.rob("10"))
    preveriEnako("rob 300", 300, P.rob(" 300 "))
    preveriEnako("parameter s", "384", P.parameter("t=abc&s=384", "s"))
    preveriEnako("parameter manjka", null, P.parameter("t=abc", "s"))
    preveriEnako("parameter ne zamenja st= za s=", null, P.parameter("st=1", "s"))
    preveriEnako("ETag za oznako zbirke", "\"media:image:1234-1792915200000-256\"", P.etag("media:image:1234", 1792915200000L, 256))
    val etagPoti = P.etag("fs:DCIM/\"x\" y.jpg", 5L, 256)
    preveriEnako("ETag poti brez narekovajev znotraj", true, etagPoti.count { it == '"' } == 2 && ' ' !in etagPoti)
    preveriEnako("ETag poti je stalen", etagPoti, P.etag("fs:DCIM/\"x\" y.jpg", 5L, 256))
    preveriEnako("ETag druga velikost je drug", false, P.etag("media:image:1", 5L, 256) == P.etag("media:image:1", 5L, 512))
    preveriEnako("ETag sprememba datoteke je drug", false, P.etag("media:image:1", 5L, 256) == P.etag("media:image:1", 6L, 256))
    val e = P.etag("media:image:1", 5L, 256)
    preveriEnako("If-None-Match enak", true, P.ujemaSe(e, e))
    preveriEnako("If-None-Match sibki", true, P.ujemaSe("W/$e", e))
    preveriEnako("If-None-Match v seznamu", true, P.ujemaSe("\"x\", $e", e))
    preveriEnako("If-None-Match *", true, P.ujemaSe("*", e))
    preveriEnako("If-None-Match drug", false, P.ujemaSe("\"x\"", e))
    preveriEnako("If-None-Match manjka", false, P.ujemaSe(null, e))
    preveriEnako("kljuc slicice: pot, cas, rob", "fs:DCIM/a.jpg|5|256", P.kljucSlicice("fs:DCIM/a.jpg", 5L, 256))
    preveriEnako("kljuc slicice: nov cas je nov kljuc", false, P.kljucSlicice("a", 5L, 256) == P.kljucSlicice("a", 6L, 256))
    val pp = LinkDatotekePravila.PredpomnilnikSlicic(10)
    pp.shrani("a", ByteArray(4)); pp.shrani("b", ByteArray(4))
    pp.dobi("a")  // a je zdaj najnovejsi
    pp.shrani("c", ByteArray(4))  // izrine b (najstarejsi)
    preveriEnako("LRU izrine najdlje nerabljeno", listOf(true, false, true), listOf(pp.dobi("a") != null, pp.dobi("b") != null, pp.dobi("c") != null))
    preveriEnako("LRU meja bajtov", 8, pp.bajtov())
    pp.shrani("velik", ByteArray(11))
    preveriEnako("prevelike ne shrani", null, pp.dobi("velik"))
    pp.shrani("a", ByteArray(2))
    preveriEnako("zamenjava steje nove bajte", 6, pp.bajtov())

    // --- slicica, manjsa od zahtevane (WP28: 320 px na ?s=512)
    preveriEnako("320 na 512 je premajhna", true, P.premajhna(320, 512))
    preveriEnako("512 na 512 ni premajhna", false, P.premajhna(512, 512))
    preveriEnako("470 na 512 ni premajhna (10 %)", false, P.premajhna(470, 512))
    preveriEnako("prazna slicica ni premajhna (ni je)", false, P.premajhna(0, 512))
    preveriEnako("JPEG 50 MP z vzorcenjem", true, P.smeDekodirati("image/jpeg", 8000, 6250))
    preveriEnako("PNG 2400x1080 da", true, P.smeDekodirati("image/png", 1080, 2400))
    preveriEnako("PNG 50 MP ne (pomnilnik)", false, P.smeDekodirati("image/png", 8000, 6250))
    preveriEnako("video ne", false, P.smeDekodirati("video/mp4", 1920, 1080))
    preveriEnako("brez mer ne", false, P.smeDekodirati("image/jpeg", 0, 0))
    // Pregled link-datoteke 6: tudi vzorceni formati imajo zgornjo mejo (JPEG 60 000 x 60 000 ne).
    preveriEnako("JPEG 60000 x 60000 ne (vzorci le do 1/8)", false, P.smeDekodirati("image/jpeg", 60_000, 60_000))
    preveriEnako("HEIC 200 MP se da", true, P.smeDekodirati("image/heic", 20_000, 10_000))

    // --- pregled link-datoteke 2: fs: samo naprava s privolitvijo (ne vse z »datoteke«)
    preveriEnako("fs: deljeno, privolitev, datoteke -> da", true, P.smeVseDatoteke(true, true, true))
    preveriEnako("fs: naprava brez privolitve (stara z »datoteke«) -> ne", false, P.smeVseDatoteke(true, false, true))
    preveriEnako("fs: privolitev, a datoteke odvzete -> ne", false, P.smeVseDatoteke(true, true, false))
    preveriEnako("fs: vse datoteke niso deljene -> ne", false, P.smeVseDatoteke(false, true, true))
    // --- pregled link-datoteke 4: napaka pri branju kroga = umaknjena
    preveriEnako("umik: napaka -> umaknjena", true, P.umaknjenaVarno { throw IllegalStateException("pokvarjen zapis") })
    preveriEnako("umik: ni umaknjena", false, P.umaknjenaVarno { false })
    preveriEnako("umik: umaknjena", true, P.umaknjenaVarno { true })

    // --- pregled link-datoteke 1 (specifikacija 3.7): lokacija posnetka ne gre ven niti v vsebini
    fun glava(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    preveriEnako("glava JPEG", "image/jpeg", P.vrstaPoGlavi(glava(0xFF, 0xD8, 0xFF, 0xE1, 0, 0)))
    preveriEnako("glava PNG", "image/png", P.vrstaPoGlavi(glava(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
    preveriEnako("glava WebP", "image/webp", P.vrstaPoGlavi(ascii("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ")))
    preveriEnako("glava HEIC", "image/heic", P.vrstaPoGlavi(ascii("\u0000\u0000\u0000\u0018ftypheic")))
    preveriEnako("glava MP4", "video/mp4", P.vrstaPoGlavi(ascii("\u0000\u0000\u0000\u0018ftypisom")))
    preveriEnako("glava PDF ni medij", null, P.vrstaPoGlavi(ascii("%PDF-1.7")))
    preveriEnako("prekratka glava", null, P.vrstaPoGlavi(glava(0xFF)))
    preveriEnako("znacke: GPS sirina, dolzina in XMP", true,
        listOf("GPSLatitude", "GPSLongitude", "GPSAltitude", "GPSDateStamp", "Xmp").all { it in P.ZNACKE_LOKACIJE })
    preveriEnako("fs: JPEG v MediaStore (Android 14) -> MediaStore", LinkDatotekePravila.PotVsebine.MEDIASTORE, P.potVsebineFs("image/jpeg", 34, true))
    preveriEnako("fs: JPEG brez MediaStore -> kopija brez lokacije", LinkDatotekePravila.PotVsebine.KOPIJA_BREZ_LOKACIJE, P.potVsebineFs("image/jpeg", 34, false))
    preveriEnako("fs: video v MediaStore -> MediaStore", LinkDatotekePravila.PotVsebine.MEDIASTORE, P.potVsebineFs("video/mp4", 34, true))
    preveriEnako("fs: video brez MediaStore -> ne posljemo", LinkDatotekePravila.PotVsebine.ZAVRNI, P.potVsebineFs("video/mp4", 34, false))
    preveriEnako("fs: Android 9 JPEG -> kopija", LinkDatotekePravila.PotVsebine.KOPIJA_BREZ_LOKACIJE, P.potVsebineFs("image/jpeg", 28, true))
    preveriEnako("fs: Android 9 video -> ne posljemo", LinkDatotekePravila.PotVsebine.ZAVRNI, P.potVsebineFs("video/mp4", 28, true))
    preveriEnako("fs: Android 9 PNG (ExifInterface ga ne zapise) -> ne posljemo", LinkDatotekePravila.PotVsebine.ZAVRNI, P.potVsebineFs("image/png", 28, false))
    preveriEnako("fs: PDF neposredno", LinkDatotekePravila.PotVsebine.NEPOSREDNO, P.potVsebineFs("application/pdf", 34, false))
    preveriEnako("fs: GIF nima lokacije -> neposredno", LinkDatotekePravila.PotVsebine.NEPOSREDNO, P.potVsebineFs("image/gif", 34, false))
    preveriEnako("media: Android 14 -> MediaStore (zakrije sam)", LinkDatotekePravila.PotVsebine.MEDIASTORE, P.potVsebineMedija("image/jpeg", 34))
    preveriEnako("media: Android 9 JPEG -> kopija", LinkDatotekePravila.PotVsebine.KOPIJA_BREZ_LOKACIJE, P.potVsebineMedija("image/jpeg", 28))

    println(if (napak == 0) "Vse ok." else "NAPAK: $napak")
    if (napak > 0) exitProcess(1)
}
