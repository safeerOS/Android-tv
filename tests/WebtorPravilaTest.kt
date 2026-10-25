package si.safeer.tv.os

import si.safeer.tv.os.WebtorPravila.Datoteka
import si.safeer.tv.os.WebtorPravila.Izid
import si.safeer.tv.os.WebtorPravila.Kljuc
import si.safeer.tv.os.WebtorPravila.Korak
import si.safeer.tv.os.WebtorPravila.Oznaka
import si.safeer.tv.os.WebtorPravila.Posoda
import si.safeer.tv.os.WebtorPravila.Pregled
import si.safeer.tv.os.WebtorPravila.Takoj
import si.safeer.tv.os.WebtorPravila.Vrsta
import si.safeer.tv.os.WebtorPravila.Vsebina

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

private fun jwt(telo: String): String {
    val e = java.util.Base64.getUrlEncoder().withoutPadding()
    return e.encodeToString("{\"alg\":\"HS256\"}".toByteArray()) + "." + e.encodeToString(telo.toByteArray()) + ".podpis"
}

/** Webtor rezerva ([WebtorPravila]): prepoznava, ucenje Scita, predpone, izdaja, vrstni red, sonda, TorBox v ozadju. */
fun main() {
    println("Preizkus pravil Webtor")
    val W = WebtorPravila
    // --- V1: prepoznava dodatka in ucenje gostitelja
    preveriEnako("id + webtor.io", true, W.jeWebtorDodatek("org.stremio.webtor.io", "https://webtor.io/safeer-skrito-0123456789abcdef"))
    preveriEnako("id + webtor.io (pravi naslov)", true, W.jeWebtorDodatek("org.stremio.webtor.io", "https://webtor.io/token/x1/stremio"))
    preveriEnako("lazen id na drugem gostitelju", false, W.jeWebtorDodatek("org.stremio.webtor.io", "https://evil.example/stremio"))
    preveriEnako("lazen id na poddomeni", false, W.jeWebtorDodatek("org.stremio.webtor.io", "https://x.webtor.io.evil.example/a"))
    preveriEnako("lazen id na podobni domeni", false, W.jeWebtorDodatek("org.stremio.webtor.io", "https://evilwebtor.io/a"))
    preveriEnako("brez https", false, W.jeWebtorDodatek("org.stremio.webtor.io", "http://webtor.io/token/x/stremio"))
    preveriEnako("webtor.io z drugim id", false, W.jeWebtorDodatek("com.stremio.torrentio.addon", "https://webtor.io/x"))
    val resolve = "https://webtor.io/token/x1/stremio/resolve/a.b.c"
    preveriEnako("uci se: resolve -> 206", "abra--1.api.cosmic-crab.buzz", W.naucenGostitelj(resolve, "https://abra--1.api.cosmic-crab.buzz/f.mkv", 206))
    preveriEnako("ne uci se: zacetek ni webtor.io", null, W.naucenGostitelj("https://evil.example/stremio/resolve/a", "https://tok.example.net/f", 206))
    preveriEnako("ne uci se: zacetek ni /resolve/", null, W.naucenGostitelj("https://webtor.io/token/x/stremio/stream/movie/tt1.json", "https://tok.example.net/f", 200))
    preveriEnako("ne uci se: 302 na oglasno domeno brez 2xx", null, W.naucenGostitelj(resolve, "https://ads.doubleclick.net/x", 302))
    preveriEnako("ne uci se: 404", null, W.naucenGostitelj(resolve, "https://tok.example.net/f", 404))
    preveriEnako("ne uci se: koncni brez https", null, W.naucenGostitelj(resolve, "http://tok.example.net/f", 206))
    preveriEnako("ne uci se: koncni je webtor.io", null, W.naucenGostitelj(resolve, "https://webtor.io/x", 200))
    preveriEnako("gostitelj", "a.example.net", W.gostitelj("https://u@A.Example.net:443/x?y"))

    // --- predpone
    preveriEnako("[⚡TB]", Oznaka(Vrsta.TORBOX, "Torrentio\n4k"), W.razcleni("[⚡TB] Torrentio\n4k"))
    preveriEnako("[⚡WT]", Oznaka(Vrsta.PREDPOMNJEN, "Torrentio 1080p"), W.razcleni("[⚡WT] Torrentio 1080p"))
    preveriEnako("[⚡️WT] z izbirnikom", Oznaka(Vrsta.PREDPOMNJEN, "X"), W.razcleni("[⚡️WT] X"))
    preveriEnako("[WT]", Oznaka(Vrsta.PRENOS, "Torrentio 720p"), W.razcleni("[WT] Torrentio 720p"))
    preveriEnako("[wt] male crke", Oznaka(Vrsta.PRENOS, "x"), W.razcleni("[wt]x"))
    preveriEnako("[P2P]", Oznaka(Vrsta.P2P, "Torrentio"), W.razcleni("[P2P] Torrentio"))
    preveriEnako("brez predpone", Oznaka(Vrsta.PRENOS, "Torrentio"), W.razcleni(" Torrentio "))
    preveriEnako("predpona sredi imena ni predpona", Oznaka(Vrsta.PRENOS, "Film [WT]"), W.razcleni("Film [WT]"))
    preveriEnako("vrsta iz imena", Vrsta.TORBOX, W.vrstaIz("TORBOX"))
    preveriEnako("vrsta neznana", Vrsta.PRENOS, W.vrstaIz(""))
    preveriEnako("skrij TB, ce imamo TorBox", true, W.skrij(Vrsta.TORBOX, true))
    preveriEnako("TB ostane brez nasega TorBoxa", false, W.skrij(Vrsta.TORBOX, false))
    preveriEnako("skrij P2P", true, W.skrij(Vrsta.P2P, false))
    preveriEnako("WT ostane", false, W.skrij(Vrsta.PRENOS, true))
    preveriEnako("⚡WT je takoj", true, W.takoj(Vrsta.PREDPOMNJEN))
    preveriEnako("WT ni takoj", false, W.takoj(Vrsta.PRENOS))

    // --- izdaja
    val a = "a".repeat(40); val b = "b".repeat(40); val c = "c".repeat(40); val d = "d".repeat(40); val e = "e".repeat(40)
    preveriEnako("kljuc: male crke", Kljuc(a, 2), W.kljuc("A".repeat(40), 2))
    preveriEnako("kljuc: -1 za neznano datoteko", Kljuc(a, -1), W.kljuc(a, null))
    preveriEnako("kljuc: base32 ni hex", null, W.kljuc("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567", 0))
    preveriEnako("JWT: hash in idx", Kljuc(a, 3), W.izJwt("https://webtor.io/safeer-skrito-0123456789abcdef/resolve/" + jwt("{\"hash\":\"$a\",\"idx\":3,\"exp\":1}")))
    preveriEnako("JWT: brez idx", Kljuc(b, -1), W.izJwt("https://x/resolve/" + jwt("{\"hash\":\"$b\"}") + "?q=1"))
    preveriEnako("JWT: ni JWT", null, W.izJwt("https://x/resolve/abc"))
    preveriEnako("JWT: ni resolve", null, W.izJwt("https://x/stream/" + jwt("{\"hash\":\"$b\"}")))

    // --- vrstni red tokov Webtor
    val nasi = listOf(Kljuc(a, 0), Kljuc(b, 0), null, Kljuc(c, 1))
    val wt = listOf(Kljuc(c, 1) to Vrsta.PRENOS, Kljuc(d, 0) to Vrsta.PREDPOMNJEN, Kljuc(a, 0) to Vrsta.TORBOX, Kljuc(b, 0) to Vrsta.PRENOS,
        Kljuc(e, 0) to Vrsta.PRENOS, null to Vrsta.PREDPOMNJEN, Kljuc(e, 1) to Vrsta.P2P)
    preveriEnako("vrstni red: nasi najprej (brez TB, ce imamo TorBox), nato takoj, nato WT; P2P izpade", listOf(3, 0, 1, 5, 4), W.vrstniRed(nasi, wt, true))
    preveriEnako("vrstni red: brez nasega TorBoxa ostane TB", listOf(2, 3, 0, 1, 5, 4), W.vrstniRed(nasi, wt, false))
    preveriEnako("samo takoj (korak ⚡)", listOf(1, 5), W.vrstniRed(nasi, wt, true, samoTakoj = true))
    preveriEnako("druga datoteka istega torrenta ni ista izdaja", listOf(0), W.vrstniRed(listOf(Kljuc(a, 1)), listOf(Kljuc(a, 2) to Vrsta.PRENOS), true))
    preveriEnako("prazno", emptyList<Int>(), W.vrstniRed(nasi, emptyList(), true))
    preveriEnako("za izdajo", 3, W.zaIzdajo(Kljuc(b, 0), wt, true))
    preveriEnako("za izdajo: TB skrit", -1, W.zaIzdajo(Kljuc(a, 0), wt, true))
    preveriEnako("za izdajo: ni", -1, W.zaIzdajo(Kljuc(e, 5), wt, true))
    preveriEnako("za izdajo: brez kljuca", -1, W.zaIzdajo(null, wt, true))

    // --- sonda
    preveriEnako("sonda 206 hitro", Izid.PREDVAJAJ, W.odlocitev(206, 300, false))
    preveriEnako("sonda 200 na meji", Izid.PREDVAJAJ, W.odlocitev(200, 15_000, false))
    preveriEnako("sonda prvi bajt po 15 s", Izid.PREPOCASI, W.odlocitev(206, 15_001, false))
    preveriEnako("sonda rok potekel", Izid.PREPOCASI, W.odlocitev(0, null, true))
    preveriEnako("402 ni paketa", Izid.NI_PAKETA, W.odlocitev(402, null, false))
    preveriEnako("404 ni toka", Izid.NI_TOKA, W.odlocitev(404, null, false))
    preveriEnako("429 omejitev ali drug IP", Izid.OMEJITEV, W.odlocitev(429, null, true))
    preveriEnako("500 napaka", Izid.NAPAKA, W.odlocitev(500, null, false))
    preveriEnako("206 brez bajta", Izid.NAPAKA, W.odlocitev(206, null, false))
    preveriEnako("brez odgovora", Izid.NAPAKA, W.odlocitev(0, null, false))
    preveriEnako("rok prve sonde", 15_000L, W.rokSonde(null, 100, 0))
    preveriEnako("rok druge sonde: ostanek skupnega", 5_000L, W.rokSonde(100, 10_100, 1))
    preveriEnako("rok porabljen", 0L, W.rokSonde(100, 20_000, 1))
    preveriEnako("najvec 2 sondi", 0L, W.rokSonde(100, 200, 2))

    // --- TorBox v ozadju in magnet
    preveriEnako("TorBox v ozadju: da", true, W.dodajVTorBox(true, true, false, a))
    preveriEnako("TorBox v ozadju: ze pripravljen", false, W.dodajVTorBox(true, true, true, a))
    preveriEnako("TorBox v ozadju: brez kljuca", false, W.dodajVTorBox(false, true, false, a))
    preveriEnako("TorBox v ozadju: Brez debrida", false, W.dodajVTorBox(true, false, false, a))
    preveriEnako("TorBox v ozadju: brez hasha", false, W.dodajVTorBox(true, true, false, null))
    preveriEnako("magnet s sledilniki toka", "magnet:?xt=urn:btih:$a&tr=udp%3A%2F%2Ft.example%3A80",
        W.magnet("A".repeat(40), listOf("tracker:udp://t.example:80", "dht:abc", "tracker:udp://t.example:80")))
    preveriEnako("magnet s privzetimi sledilniki", true, W.magnet(a).startsWith("magnet:?xt=urn:btih:$a&tr=udp%3A%2F%2Ftracker.opentrackr.org"))

    // --- vrstni red virov (lastnik 25. 10. 2026 07:25)
    preveriEnako("koraki z Usenetom", listOf(Korak.USENET_PRIPRAVLJEN, Korak.TAKOJ, Korak.USENET_CAKANJE, Korak.WEBTOR, Korak.CAKANJE_DEBRID), W.koraki(true))
    preveriEnako("koraki brez Useneta", listOf(Korak.TAKOJ, Korak.WEBTOR, Korak.CAKANJE_DEBRID), W.koraki(false))
    preveriEnako("takoj: ⚡ torrent prvi", Takoj.TORRENT, W.takojPrvi(true, true, true))
    preveriEnako("takoj: povezava pred ⚡WT", Takoj.POVEZAVA, W.takojPrvi(false, true, true))
    preveriEnako("takoj: ⚡WT", Takoj.WEBTOR, W.takojPrvi(false, false, true))
    preveriEnako("takoj: nic", null, W.takojPrvi(false, false, false))
    preveriEnako("Usenet: najboljsi pripravljen", 0, W.izberiUsenet(listOf(true, null, null), false))
    preveriEnako("Usenet: boljsi se ni odgovoril - cakaj", W.CAKAJ, W.izberiUsenet(listOf(null, true, false), false))
    preveriEnako("Usenet: boljsi ni pripravljen", 1, W.izberiUsenet(listOf(false, true, null), false))
    preveriEnako("Usenet: rok potekel - najboljsi med odgovorjenimi", 1, W.izberiUsenet(listOf(null, true, false), true))
    preveriEnako("Usenet: nobena", W.NOBENA, W.izberiUsenet(listOf(false, false, false), false))
    preveriEnako("Usenet: rok potekel, nic", W.NOBENA, W.izberiUsenet(listOf(null, false, null), true))
    preveriEnako("Usenet: prazno", W.NOBENA, W.izberiUsenet(emptyList(), false))

    // --- Izmerjeno 25. 10. 2026 (OnePlus 9, 0 od 3): datoteka toka Webtor je `idx` v JWT, ne polje fileIdx odgovora.
    // Kolesarski tatovi 1080p 6,56 GB: dodatek (Torrentio) toka ni dal fileIdx -> Webtor idx 0 -> /resolve = naslovnica .jpg.
    val kt = "c4f058d39c".padEnd(40, '0')
    val tokKt = "https://webtor.io/safeer-skrito-0123456789abcdef/resolve/" + jwt("{\"hash\":\"$kt\",\"idx\":0,\"exp\":1}")
    preveriEnako("Webtor: datoteka iz JWT (fileIdx 0 v odgovoru izpuscen)", Kljuc(kt, 0), W.kljucWebtor(kt, tokKt))
    preveriEnako("Webtor: JWT brez idx = najvecji video", Kljuc(kt, -1),
        W.kljucWebtor(kt, "https://webtor.io/x/resolve/" + jwt("{\"hash\":\"$kt\",\"exp\":1}")))
    preveriEnako("Webtor: brez JWT datoteka neznana", Kljuc(kt, W.IDX_NEZNAN), W.kljucWebtor(kt, "https://webtor.io/x/resolve/abc"))
    preveriEnako("Webtor: brez JWT in brez hasha", null, W.kljucWebtor("", "https://webtor.io/x/resolve/abc"))
    preveriEnako("kljuc toka ohrani neznano datoteko", Kljuc(kt, W.IDX_NEZNAN), W.kljucToka(kt, W.IDX_NEZNAN))
    preveriEnako("kljuc toka: -1", Kljuc(kt, -1), W.kljucToka(kt.uppercase(), -1))
    preveriEnako("nas tok brez fileIdx (-1) NI ista izdaja kot Webtor idx 0", false, W.kljuc(kt, -1) == W.kljucWebtor(kt, tokKt))
    preveriEnako("nas tok fileIdx 0 JE ista izdaja kot Webtor idx 0", true, W.kljuc(kt, 0) == W.kljucWebtor(kt, tokKt))
    preveriEnako("ime videa", true, W.jeVideoIme("Bicycle.Thieves.1948.1080p.BluRay.x264-[YTS.AM].mp4"))
    preveriEnako("ime: naslovnica", false, W.jeVideoIme("Cover.jpg"))
    preveriEnako("ime: glasba", false, W.jeVideoIme("01 - Theme.mp3"))
    preveriEnako("ime: vzorec", false, W.jeVideoIme("Movie.2020/Sample/movie-sample.mkv"))
    preveriEnako("ime: WMV ga predvajalnik ne zna", false, W.jeVideoIme("film.wmv"))
    preveriEnako("ime: brez koncnice", null, W.jeVideoIme("Bicycle Thieves [1948] 1080p"))
    preveriEnako("ime: prazno", null, W.jeVideoIme(""))
    preveriEnako("datoteka: idx 0 brez imena in brez nase datoteke", Datoteka.NEGOTOVA, W.datoteka(0, "", -1, ""))
    preveriEnako("datoteka: idx 0 brez imena, nasega toka ni", Datoteka.NEGOTOVA, W.datoteka(0, "", null, ""))
    preveriEnako("datoteka: ime toka Webtor je video", Datoteka.VIDEO, W.datoteka(0, "Film.1948.1080p.mkv", null, ""))
    preveriEnako("datoteka: ime toka Webtor je slika", Datoteka.NI_VIDEO, W.datoteka(3, "poster.jpg", 3, "Film.mkv"))
    preveriEnako("datoteka: JWT brez idx", Datoteka.NAJVECJI, W.datoteka(-1, "", null, ""))
    preveriEnako("datoteka: nas dodatek je dal isti indeks", Datoteka.VIDEO, W.datoteka(2, "", 2, "Film.mkv"))
    preveriEnako("datoteka: nas dodatek isti indeks brez imena", Datoteka.VIDEO, W.datoteka(2, "", 2, ""))
    preveriEnako("datoteka: nas indeks je drug", Datoteka.NEGOTOVA, W.datoteka(0, "", 5, "Film.mkv"))
    preveriEnako("datoteka: brez JWT", Datoteka.NEGOTOVA, W.datoteka(W.IDX_NEZNAN, "Film.mkv", 0, "Film.mkv"))
    // Vrstni red z datotekami: negotova datoteka ni »takoj« in je zadnja, NI_VIDEO izpade, ista izdaja ne velja za negotovo.
    val wd = listOf(Kljuc(kt, 0) to Vrsta.PRENOS, Kljuc(a, 0) to Vrsta.PRENOS, Kljuc(b, -1) to Vrsta.PREDPOMNJEN,
        Kljuc(c, 4) to Vrsta.PREDPOMNJEN, Kljuc(d, 1) to Vrsta.PRENOS)
    val dd = listOf(Datoteka.NEGOTOVA, Datoteka.VIDEO, Datoteka.NAJVECJI, Datoteka.NEGOTOVA, Datoteka.NI_VIDEO)
    preveriEnako("vrstni red z datotekami", listOf(1, 2, 0, 3), W.vrstniRed(listOf(W.kljuc(kt, -1), Kljuc(a, 0)), wd, true, datoteke = dd))
    preveriEnako("vrstni red z datotekami: negotova z istim kljucem ni ista izdaja", listOf(1, 2, 0, 3),
        W.vrstniRed(listOf(Kljuc(kt, 0), Kljuc(a, 0)), wd, true, datoteke = dd))
    preveriEnako("vrstni red z datotekami: takoj brez negotove", listOf(2), W.vrstniRed(emptyList(), wd, true, samoTakoj = true, datoteke = dd))
    preveriEnako("za izdajo: negotova ne", -1, W.zaIzdajo(Kljuc(kt, 0), wd, true, dd))
    preveriEnako("za izdajo: video", 1, W.zaIzdajo(Kljuc(a, 0), wd, true, dd))
    preveriEnako("za izdajo: ni video", -1, W.zaIzdajo(Kljuc(d, 1), wd, true, dd))

    // --- Vsebina sonde: prvi bajti, Content-Type, velikost (Grob krejsnic: AVI s kodekom, ki ga Media3 izpusti -> samo zvok).
    fun bajti(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
    fun z(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    fun le(v: Int) = bajti(v and 0xFF, (v shr 8) and 0xFF, (v shr 16) and 0xFF, (v shr 24) and 0xFF)
    fun avi(kodek: String): ByteArray {
        val strh = z("strh") + le(56) + z("vids") + z("XVID") + ByteArray(48)
        val strf = z("strf") + le(40) + le(40) + le(720) + le(480) + bajti(1, 0, 24, 0) + z(kodek) + ByteArray(20)
        val strl = z("LIST") + le(4 + strh.size + strf.size) + z("strl") + strh + strf
        val avih = z("avih") + le(56) + ByteArray(56)
        val hdrl = z("LIST") + le(4 + avih.size + strl.size) + z("hdrl") + avih + strl
        return z("RIFF") + le(1_000_000) + z("AVI ") + hdrl + ByteArray(4096)
    }
    val mkv = bajti(0x1A, 0x45, 0xDF, 0xA3) + ByteArray(4092)
    val mp4 = bajti(0, 0, 0, 0x20) + z("ftypisom") + ByteArray(4084)
    val ts = ByteArray(4096).also { for (i in 0 until 4096 step 188) it[i] = 0x47 }
    val m2ts = ByteArray(4096).also { for (i in 4 until 4096 step 192) it[i] = 0x47 }
    val jpg = bajti(0xFF, 0xD8, 0xFF, 0xE0) + z("\u0000\u0010JFIF") + ByteArray(100)
    val mp3 = z("ID3") + bajti(3, 0, 0) + ByteArray(100)
    val mp3brez = bajti(0xFF, 0xFB, 0x90, 0x64) + ByteArray(100)
    val GiB = 1024L * 1024L * 1024L
    val vse: (String) -> Boolean = { true }
    val brezMp4v: (String) -> Boolean = { it != "video/mp4v-es" }
    preveriEnako("posoda MKV", Posoda.MKV, W.posoda(mkv))
    preveriEnako("posoda MP4", Posoda.MP4, W.posoda(mp4))
    preveriEnako("posoda HEIF ni video", Posoda.HEIF, W.posoda(bajti(0, 0, 0, 0x18) + z("ftypheic") + ByteArray(20)))
    preveriEnako("posoda M4A", Posoda.M4A, W.posoda(bajti(0, 0, 0, 0x18) + z("ftypM4A ") + ByteArray(20)))
    preveriEnako("posoda TS", Posoda.TS, W.posoda(ts))
    preveriEnako("posoda M2TS", Posoda.TS, W.posoda(m2ts))
    preveriEnako("posoda AVI", Posoda.AVI, W.posoda(avi("XVID")))
    preveriEnako("posoda JPEG", Posoda.JPEG, W.posoda(jpg))
    preveriEnako("posoda MP3 (ID3)", Posoda.ZVOK, W.posoda(mp3))
    preveriEnako("posoda MP3 (okvir)", Posoda.ZVOK, W.posoda(mp3brez))
    preveriEnako("posoda besedilo (nfo)", Posoda.BESEDILO, W.posoda(z("Release notes: Bicycle Thieves 1948 1080p BluRay x264 ....... 1234567890")))
    preveriEnako("posoda PS", Posoda.PS, W.posoda(bajti(0, 0, 1, 0xBA) + ByteArray(20)))
    preveriEnako("posoda ASF", Posoda.ASF, W.posoda(bajti(0x30, 0x26, 0xB2, 0x75) + ByteArray(20)))
    preveriEnako("posoda prazno", Posoda.NEZNANO, W.posoda(ByteArray(0)))
    preveriEnako("AVI kodek XVID", "XVID", W.aviKodek(avi("XVID")))
    preveriEnako("AVI kodek DIV3", "DIV3", W.aviKodek(avi("DIV3")))
    preveriEnako("AVI brez glave strl", "", W.aviKodek(z("RIFF") + le(10) + z("AVI ") + ByteArray(200)))
    preveriEnako("AVI MIME XVID", "video/mp4v-es", W.aviMime("XVID"))
    preveriEnako("AVI MIME DIV3 (Media3 ga izpusti)", null, W.aviMime("DIV3"))
    preveriEnako("AVI MIME x264 (Media3 ga izpusti)", null, W.aviMime("x264"))
    preveriEnako("pregled: naslovnica jpg z 206", Vsebina.NI_VIDEO, W.pregled("image/jpeg", jpg, 120_000, 6.56, vse).vsebina)
    preveriEnako("pregled: jpg z octet-stream", Vsebina.NI_VIDEO, W.pregled("application/octet-stream", jpg, 120_000, 6.56, vse).vsebina)
    preveriEnako("pregled: mp3", Vsebina.NI_VIDEO, W.pregled("audio/mpeg", mp3, 8_000_000, 0.64, vse).vsebina)
    preveriEnako("pregled: MKV octet-stream", Pregled(Vsebina.VIDEO, Posoda.MKV), W.pregled("application/octet-stream", mkv, 7 * GiB, 6.56, vse))
    preveriEnako("pregled: MKV brez Content-Type", Vsebina.VIDEO, W.pregled(null, mkv, 7 * GiB, 0.0, vse).vsebina)
    preveriEnako("pregled: MKV z audio/x-matroska (mka)", Vsebina.NI_VIDEO, W.pregled("audio/x-matroska", mkv, 300_000_000, 0.0, vse).vsebina)
    preveriEnako("pregled: MP4 video/mp4", Vsebina.VIDEO, W.pregled("video/mp4", mp4, 2 * GiB, 1.4, vse).vsebina)
    preveriEnako("pregled: TS", Vsebina.VIDEO, W.pregled("video/mp2t", ts, 2 * GiB, 2.0, vse).vsebina)
    preveriEnako("pregled: AVI XVID", Pregled(Vsebina.VIDEO, Posoda.AVI, "XVID"), W.pregled("video/x-msvideo", avi("XVID"), 700_000_000, 0.64, vse))
    preveriEnako("pregled: AVI DIV3 - Media3 izpusti video", Pregled(Vsebina.NEPODPRTA, Posoda.AVI, "DIV3"),
        W.pregled("video/x-msvideo", avi("DIV3"), 700_000_000, 0.64, vse))
    preveriEnako("pregled: AVI MP43 brez dekoderja", Vsebina.NEPODPRTA, W.pregled("video/x-msvideo", avi("MP43"), 700_000_000, 0.64, { it != "video/mp43" }).vsebina)
    preveriEnako("pregled: AVI XVID brez dekoderja", Vsebina.NEPODPRTA, W.pregled(null, avi("XVID"), 700_000_000, 0.64, brezMp4v).vsebina)
    preveriEnako("pregled: ASF/WMV", Vsebina.NEPODPRTA, W.pregled("video/x-ms-asf", bajti(0x30, 0x26, 0xB2, 0x75) + ByteArray(20), GiB, 1.0, vse).vsebina)
    preveriEnako("pregled: vzorec 60 MB pri filmu 6,56 GB", Vsebina.PREMAJHNA, W.pregled(null, mkv, 60_000_000, 6.56, vse).vsebina)
    preveriEnako("pregled: datoteka pod 10 MiB", Vsebina.PREMAJHNA, W.pregled(null, mkv, 5_000_000, 0.0, vse).vsebina)
    preveriEnako("pregled: paket 480 GB, datoteka 1,4 GB", Vsebina.VIDEO, W.pregled(null, mp4, 1_500_000_000, 480.0, vse).vsebina)
    preveriEnako("pregled: film 700 MB pri opisu 6,56 GB", Vsebina.VIDEO, W.pregled(null, mkv, 700_000_000, 6.56, vse).vsebina)
    preveriEnako("pregled: neznana velikost", Vsebina.VIDEO, W.pregled(null, mkv, -1, 6.56, vse).vsebina)
    preveriEnako("pregled: neznano z video/*", Vsebina.VIDEO, W.pregled("video/x-flv2", bajti(1, 2, 3, 4, 5) + ByteArray(10), GiB, 1.0, vse).vsebina)
    preveriEnako("pregled: neznano z octet-stream", Vsebina.NI_VIDEO, W.pregled("application/octet-stream", bajti(1, 2, 3, 4, 5) + ByteArray(10), GiB, 1.0, vse).vsebina)
    preveriEnako("pregled: html stran napake", Vsebina.NI_VIDEO, W.pregled("text/html", z("<!doctype html><html><body>Error</body></html>"), 47, 1.0, vse).vsebina)
    preveriEnako("sonda 206 + naslovnica = napacna vsebina", Izid.NAPACNA_VSEBINA, W.odlocitev(206, 2796, false, Vsebina.NI_VIDEO))
    preveriEnako("sonda 206 + AVI brez videa = napacna vsebina", Izid.NAPACNA_VSEBINA, W.odlocitev(206, 6690, false, Vsebina.NEPODPRTA))
    preveriEnako("sonda 206 + video = predvajaj", Izid.PREDVAJAJ, W.odlocitev(206, 800, false, Vsebina.VIDEO))
    preveriEnako("sonda 404 ostane ni toka", Izid.NI_TOKA, W.odlocitev(404, null, false, null))
    preveriEnako("velikost iz Content-Range", 7_043_825_664L, W.skupnaVelikost(206, "bytes 0-4095/7043825664", -1))
    preveriEnako("velikost: Content-Range *", -1L, W.skupnaVelikost(206, "bytes 0-4095/*", -1))
    preveriEnako("velikost iz Content-Length (200)", 123L, W.skupnaVelikost(200, null, 123))
    preveriEnako("velikost: 206 brez Content-Range", -1L, W.skupnaVelikost(206, null, 4096))

    // Pregled U4: sonda ne sme v domace omrezje ali na to napravo (po razresitvi DNS).
    fun ip(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    preveriEnako("192.168.1.1 zaseben", true, W.zasebenNaslov(ip(192, 168, 1, 1)))
    preveriEnako("10.0.0.1 zaseben", true, W.zasebenNaslov(ip(10, 0, 0, 1)))
    preveriEnako("172.16.5.4 zaseben", true, W.zasebenNaslov(ip(172, 16, 5, 4)))
    preveriEnako("172.32.0.1 javen", false, W.zasebenNaslov(ip(172, 32, 0, 1)))
    preveriEnako("127.0.0.1 zanka", true, W.zasebenNaslov(ip(127, 0, 0, 1)))
    preveriEnako("169.254.1.1 link-local", true, W.zasebenNaslov(ip(169, 254, 1, 1)))
    preveriEnako("100.64.0.1 CGNAT", true, W.zasebenNaslov(ip(100, 64, 0, 1)))
    preveriEnako("0.0.0.0 nedolocen", true, W.zasebenNaslov(ip(0, 0, 0, 0)))
    preveriEnako("1.1.1.1 javen", false, W.zasebenNaslov(ip(1, 1, 1, 1)))
    preveriEnako("::1 zanka", true, W.zasebenNaslov(ip(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
    preveriEnako("fd00::1 ULA", true, W.zasebenNaslov(ip(0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
    preveriEnako("fe80::1 link-local", true, W.zasebenNaslov(ip(0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
    preveriEnako("::ffff:192.168.0.1 zaseben", true, W.zasebenNaslov(ip(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 192, 168, 0, 1)))
    preveriEnako("2606:4700::1111 javen", false, W.zasebenNaslov(ip(0x26, 0x06, 0x47, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x11, 0x11)))

    if (napak == 0) println("WebtorPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
