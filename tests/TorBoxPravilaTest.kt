package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Naloga 119: TorBox kot vir torrentov ([TorBoxPravila]). */
fun main() {
    println("Preizkus pravil TorBox")
    val hex = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"

    // Kljuc
    preveriEnako("UUID kljuc", "0b8f3c2a-1d4e-4f5a-9b6c-7d8e9f0a1b2c", TorBoxPravila.ocistiKljuc("  0b8f3c2a-1d4e-4f5a-9b6c-7d8e9f0a1b2c \n"))
    preveriEnako("prekratek", null, TorBoxPravila.ocistiKljuc("abc123"))
    preveriEnako("presledek v sredini", null, TorBoxPravila.ocistiKljuc("0b8f3c2a 1d4e 4f5a 9b6c 7d8e9f0a1b2c"))
    preveriEnako("prilepljen naslov", null, TorBoxPravila.ocistiKljuc("https://torbox.app/settings?key=0b8f3c2a1d4e"))
    preveriEnako("sumniki niso kljuc", null, TorBoxPravila.ocistiKljuc("č".repeat(30)))
    preveriEnako("glava", "Bearer abc", TorBoxPravila.glavaAvtorizacije("abc"))

    // Hash
    preveriEnako("heks hash", hex, TorBoxPravila.hashIzMagneta("magnet:?xt=urn:btih:${hex.uppercase()}&dn=Film"))
    preveriEnako("base32 hash", hex, TorBoxPravila.hashIzMagneta("magnet:?dn=Film&xt=urn:btih:YEX6DQDLXISUVHOJ6UM3GNNKPQJWPKEK&tr=udp"))
    preveriEnako("ni magnet", null, TorBoxPravila.hashIzMagneta("https://x/?xt=urn:btih:$hex"))
    preveriEnako("magnet brez btih", null, TorBoxPravila.hashIzMagneta("magnet:?dn=Film"))
    preveriEnako("napacna dolzina", null, TorBoxPravila.hashIzMagneta("magnet:?xt=urn:btih:abc123"))

    // Naslovi: kljuc samo v requestdl, nikjer drugje
    preveriEnako("checkcached brez kljuca", false, TorBoxPravila.naslovPredpomnjeno(hex).contains("token"))
    preveriEnako("checkcached z datotekami", true, TorBoxPravila.naslovPredpomnjeno(hex).endsWith("list_files=true"))
    preveriEnako("requestdl s tokenom (zakodiran)", "https://api.torbox.app/v1/api/torrents/requestdl?token=a%2Bb&torrent_id=7&file_id=3",
        TorBoxPravila.naslovPrenosa("a+b", 7, 3))

    // Izbira datoteke
    val d = listOf(
        TorBoxPravila.Datoteka(0, "Film.Sample.mkv", 50_000_000, "video/x-matroska"),
        TorBoxPravila.Datoteka(1, "Film.mkv", 4_000_000_000, "video/x-matroska"),
        TorBoxPravila.Datoteka(2, "Film.srt", 80_000, "application/x-subrip"),
        TorBoxPravila.Datoteka(3, "Bonus.mp4", 900_000_000, ""))
    preveriEnako("indeks dodatka", 3L, TorBoxPravila.izberiDatoteko(d, 3)?.id)
    preveriEnako("brez indeksa: najvecji video", 1L, TorBoxPravila.izberiDatoteko(d, -1)?.id)
    preveriEnako("indeks na podnapise: najvecji video", 1L, TorBoxPravila.izberiDatoteko(d, 2)?.id)
    preveriEnako("indeks izven seznama: najvecji video", 1L, TorBoxPravila.izberiDatoteko(d, 9)?.id)
    preveriEnako("brez predvajljive", null, TorBoxPravila.izberiDatoteko(listOf(TorBoxPravila.Datoteka(0, "a.nfo", 10, "text/plain")), -1))
    preveriEnako("glasba po koncnici", true, TorBoxPravila.jePredvajljiva(TorBoxPravila.Datoteka(0, "pesem.flac", 1, "")))

    // Napake
    preveriEnako("BAD_TOKEN", TorBoxPravila.NAPAKA_KLJUC, TorBoxPravila.vrstaNapake(403, "BAD_TOKEN"))
    preveriEnako("E3: 403 brez kode (CDN, blokada IP) ni kljuc", TorBoxPravila.NAPAKA_STREZNIK, TorBoxPravila.vrstaNapake(403, null))
    preveriEnako("429", TorBoxPravila.NAPAKA_MEJA, TorBoxPravila.vrstaNapake(429, null))
    preveriEnako("ACTIVE_LIMIT", TorBoxPravila.NAPAKA_MEJA, TorBoxPravila.vrstaNapake(400, "ACTIVE_LIMIT"))
    preveriEnako("DOWNLOAD_TOO_LARGE", TorBoxPravila.NAPAKA_PAKET, TorBoxPravila.vrstaNapake(400, "DOWNLOAD_TOO_LARGE"))
    preveriEnako("BOZO_TORRENT", TorBoxPravila.NAPAKA_NI_DATOTEKE, TorBoxPravila.vrstaNapake(400, "BOZO_TORRENT"))
    preveriEnako("500", TorBoxPravila.NAPAKA_STREZNIK, TorBoxPravila.vrstaNapake(500, "DATABASE_ERROR"))

    // Meja 60 novih na uro
    val zdaj = 10_000_000L
    val polno = (0 until 60).map { zdaj - 3_000_000L + it * 1_000L }
    preveriEnako("59 v zadnji uri: dovoli", true, TorBoxPravila.MejaNovih.dovoli(polno.drop(1), zdaj))
    preveriEnako("60 v zadnji uri: ne", false, TorBoxPravila.MejaNovih.dovoli(polno, zdaj))
    preveriEnako("cakaj, da najstarejsi izpade", 600_000L, TorBoxPravila.MejaNovih.cakajMs(polno, zdaj))
    preveriEnako("prostor: brez cakanja", 0L, TorBoxPravila.MejaNovih.cakajMs(polno.drop(5), zdaj))
    preveriEnako("starejsi od ure odpadejo", 1, TorBoxPravila.MejaNovih.veljavni(listOf(zdaj - 3_600_001L, zdaj - 10L), zdaj).size)
    preveriEnako("cas iz prihodnosti (ura naprave) ne steje", 0, TorBoxPravila.MejaNovih.veljavni(listOf(zdaj + 5_000L), zdaj).size)

    // Pripravljenost novega torrenta
    preveriEnako("prenesen", true, TorBoxPravila.pripravljen("downloading", koncano = true, prisoten = false))
    preveriEnako("predpomnjen", true, TorBoxPravila.pripravljen("cached", koncano = false, prisoten = false))
    preveriEnako("se prenasa", false, TorBoxPravila.pripravljen("downloading", koncano = false, prisoten = false))
    preveriEnako("metapodatki", false, TorBoxPravila.pripravljen("metaDL", koncano = false, prisoten = false))

    if (napak == 0) println("TorBoxPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
