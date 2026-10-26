package si.safeer.tv.os

fun main() {
    val ura = 3_600_000L
    val zdaj = 1_000 * ura
    // Lastnik 25. 10. 2026: 12 ur po zadnjem predvajanju torrent gre (prej 48 ur).
    check(ZacasniPravila.VELJA_MS == 12 * ura)
    check(!ZacasniPravila.odstrani(zdaj - 11 * ura, zdaj, false))
    check(!ZacasniPravila.odstrani(zdaj - 12 * ura, zdaj, false))
    check(ZacasniPravila.odstrani(zdaj - 12 * ura - 1, zdaj, false))
    check(ZacasniPravila.odstrani(zdaj - 13 * ura, zdaj, false))
    check(ZacasniPravila.odstrani(zdaj - 26 * ura, zdaj, false))
    // Izrecno shranjeno (»Obdrži«) ostane tudi po 12 urah.
    check(!ZacasniPravila.odstrani(zdaj - 13 * ura, zdaj, false, obdrzan = true))
    // Za nov film manjka prostora: gredo tudi mlajsi, a ne tisti, ki ga je kdo predvajal v zadnjih urah.
    check(ZacasniPravila.odstrani(zdaj - 7 * ura, zdaj, true))
    check(!ZacasniPravila.odstrani(zdaj - 5 * ura, zdaj, true))
    check(!ZacasniPravila.odstrani(zdaj - 7 * ura, zdaj, false))
    // Ura naprave, prestavljena nazaj (zapis "iz prihodnosti"), ne odstrani nicesar.
    check(!ZacasniPravila.odstrani(zdaj + 5 * ura, zdaj, true))
    // Najdlje neuporabljeni prvi.
    check(ZacasniPravila.poVrsti(listOf(30L to "b", 10L to "a", 20L to "c")).map { it.second } == listOf("a", "c", "b"))
    // Prostor, ki ga smemo obljubiti: kar bi sprostili torrenti, ki jih ze nekaj ur nihce ne gleda.
    check(ZacasniPravila.sprostljiv(zdaj - 7 * ura, zdaj) && !ZacasniPravila.sprostljiv(zdaj - 1 * ura, zdaj))
    // Skrajsana roka (preizkus na napravi).
    check(ZacasniPravila.odstrani(zdaj - 61_000, zdaj, false, velja = 60_000, vTeku = 10_000))
    // »Obdrži« (krog 85): obdrzan prenos ne potece, ne gre ob pomanjkanju prostora in se ne steje kot sprostljiv prostor.
    check(!ZacasniPravila.odstrani(zdaj - 500 * ura, zdaj, false, obdrzan = true))
    check(!ZacasniPravila.odstrani(zdaj - 500 * ura, zdaj, true, obdrzan = true))
    check(ZacasniPravila.odstrani(zdaj - 500 * ura, zdaj, false, obdrzan = false))
    check(!ZacasniPravila.sprostljiv(zdaj - 7 * ura, zdaj, obdrzan = true))
    // Grok 9, Z12: zapis iz prihodnosti velja od zdaj (potece cez 12 ur), pravi cas ostane.
    check(ZacasniPravila.popraviCas(zdaj + 5 * ura, zdaj) == zdaj)
    check(ZacasniPravila.popraviCas(zdaj - 5 * ura, zdaj) == zdaj - 5 * ura)
    // Grok 9, Z9: povezava v mapi torrenta na tujo mapo - mapa torrenta gre, tuja vsebina ostane.
    val koren = java.nio.file.Files.createTempDirectory("safeer-zacasni").toFile()
    try {
        val tuja = java.io.File(koren, "tuja").apply { mkdirs() }
        val skrivnost = java.io.File(tuja, "skrivnost.txt").apply { writeText("ostane") }
        val torrent = java.io.File(koren, "Film").apply { mkdirs() }
        java.io.File(torrent, "film.mkv").writeText("x")
        java.io.File(torrent, "Podnapisi").mkdirs()
        java.io.File(torrent, "Podnapisi/sl.srt").writeText("1")
        java.nio.file.Files.createSymbolicLink(java.io.File(torrent, "povezava").toPath(), tuja.toPath())
        java.nio.file.Files.createSymbolicLink(java.io.File(torrent, "datoteka").toPath(), skrivnost.toPath())
        check(ZacasniPravila.izbrisiBrezPovezav(torrent)) { "Z9: mapa torrenta ni izbrisana" }
        check(!torrent.exists()) { "Z9: mapa torrenta ostala" }
        check(skrivnost.isFile && skrivnost.readText() == "ostane") { "Z9: izbrisana vsebina zunaj torrenta (sledenje povezavi)" }
        check(ZacasniPravila.izbrisiBrezPovezav(java.io.File(koren, "ni-je"))) { "Z9: neobstojeca pot" }
    } finally {
        // Pospravi samo prave datoteke (morebitne povezave brez sledenja).
        ZacasniPravila.izbrisiBrezPovezav(koren)
    }
    // Grok 9, Z3: po ogledu zacasen torrent ne ostane v roju; tece samo, dokler je odprta povezava toka.
    check(ZacasniPravila.ustaviPoOgledu(obdrzan = false, deliNaprej = false, odprtihTokov = 0, msOdBranja = ZacasniPravila.PO_OGLEDU_MS)) { "Z3: po ogledu ustavi" }
    check(!ZacasniPravila.ustaviPoOgledu(false, false, 1, ZacasniPravila.ZASTOJ_MS - 1)) { "Z3: odprt tok, ki se bere, tece" }
    check(!ZacasniPravila.ustaviPoOgledu(false, false, 0, ZacasniPravila.PO_OGLEDU_MS - 1)) { "Z3: previjanje - nova povezava pride v hipu" }
    check(!ZacasniPravila.ustaviPoOgledu(true, false, 0, 10 * ura)) { "Z3: Obdrži tece do konca kot Prenesi" }
    check(!ZacasniPravila.ustaviPoOgledu(false, true, 0, 10 * ura)) { "Z3: Deli naprej" }
    check(ZacasniPravila.ustaviPoOgledu(false, false, 0, Long.MAX_VALUE, ZacasniPravila.ZACETEK_MS)) { "Z3: nihce ga ni odprl (obnova ob zagonu)" }
    check(!ZacasniPravila.ustaviPoOgledu(false, false, 0, ZacasniPravila.ZACETEK_MS - 1, ZacasniPravila.ZACETEK_MS)) { "Z3: predvajalnik se se povezuje" }
    check(ZacasniPravila.PO_OGLEDU_MS <= 10_000L && ZacasniPravila.ZACETEK_MS > ZacasniPravila.PO_OGLEDU_MS) { "Z3: roka" }
    // Grok 27, Q1: odprta povezava, po kateri 2 min ni sel noben bajt (odjemalec ne bere), torrenta ne drzi v roju.
    check(ZacasniPravila.ustaviPoOgledu(false, false, 1, ZacasniPravila.ZASTOJ_MS, ZacasniPravila.ZACETEK_MS)) { "Q1: zastala povezava" }
    check(ZacasniPravila.ustaviPoOgledu(false, false, 3, 10 * ura, ZacasniPravila.ZACETEK_MS)) { "Q1: vec zastalih povezav" }
    check(!ZacasniPravila.ustaviPoOgledu(false, false, 1, ZacasniPravila.ZASTOJ_MS - 1, ZacasniPravila.ZACETEK_MS)) { "Q1: povezava se bere" }
    check(!ZacasniPravila.ustaviPoOgledu(true, false, 1, 10 * ura, ZacasniPravila.ZACETEK_MS)) { "Q1: Obdrži ostane" }
    check(ZacasniPravila.ZASTOJ_MS > 90_000L) { "Q1: zastoj daljsi od cakanja na kos (CAKAJ_KOS_MS), med katerim se branje steje" }
    // Grok 27, Q2: povezava zmanjsa svoj stevec - odstranitev in ponovno dodajanje med odprtim tokom ne ustavi novega ogleda.
    val st = ZacasniPravila.StevciTokov()
    val h = "ABCDEF0123456789abcdef0123456789ABCDEF01"
    val stari = st.odpri(h)
    check(st.odprtih(h.lowercase()) == 1) { "Q5: isti hash ne glede na velikost crk" }
    st.pozabi(h)
    val nov = st.odpri(h)
    check(st.zapri(stari) == 0)
    check(st.odprtih(h) == 1) { "Q2: stara povezava je zmanjsala nov stevec" }
    check(st.zapri(nov) == 0 && st.odprtih(h) == 0)
    check(st.zapri(nov) == 0 && st.odprtih(h) == 0) { "Q2: stevec pod nic" }
    val a = st.odpri(h); val b = st.odpri(h)
    check(a === b && st.odprtih(h) == 2 && st.zapri(a) == 1 && st.odprtih(h) == 1) { "Q2: vec povezav istega torrenta" }
    check(ZacasniPravila.kanon(" ABC ") == "abc")
    // Grok 27, Q1: med cakanjem na kos streznik opazi, da je odjemalec (predvajalnik) zaprl povezavo.
    java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { ss ->
        val odjemalec = java.net.Socket("127.0.0.1", ss.localPort)
        ss.accept().use { s ->
            s.soTimeout = 30_000
            odjemalec.getOutputStream().write("GET /t/x HTTP/1.1\r\n\r\n".toByteArray())
            s.getInputStream().read(ByteArray(64))
            check(ZacasniPravila.odjemalecZiv(s)) { "Q1: odjemalec je se tam" }
            check(s.soTimeout == 30_000) { "Q1: rok vticnice obnovljen" }
            odjemalec.close()
            var ziv = true
            for (i in 0 until 50) { ziv = ZacasniPravila.odjemalecZiv(s); if (!ziv) break; Thread.sleep(10) }
            check(!ziv) { "Q1: zaprt odjemalec ni opazen" }
        }
    }
    println("ZacasniPravilaTest: OK")
}
