package si.safeer.tv.os

/** Preizkus [PotDoNaprave]: po kateri poti beremo z druge naprave (neposredno ali prek Global Linka). */
fun main() {
    val n = PotDoNaprave.Pot.NEPOSREDNO
    val r = PotDoNaprave.Pot.RELE
    val k = PotDoNaprave.kljuc("n-0123456789abcdef-control", "https://192.0.2.10:8443/d/a")

    // Ura, sonda in delo v ozadju so v preizkusu v nasih rokah.
    var zdaj = 1_000L
    var doma = true
    var sond = 0
    val cakajoce = ArrayList<() -> Unit>()
    fun red(zdoma: Boolean = false, releMogoc: Boolean = true) =
        PotDoNaprave.vrstniRed(k, zdoma, releMogoc, { zdaj }, { sond++; doma }, { delo -> cakajoce.add(delo) })

    // 1) Brez releja ostane samo neposredna pot; povezava v Link prek releja pomeni rele prvi. Sonde ni.
    PotDoNaprave.pocisti()
    check(red(zdoma = true, releMogoc = false) == listOf(n)) { "brez releja samo neposredno" }
    check(red(zdoma = true) == listOf(r, n)) { "zdoma: najprej rele" }
    check(sond == 0 && PotDoNaprave.zadnja(k) == null) { "za to sonda ni potrebna in nic se ne zapomni" }

    // 2) Nic znanega, doma: sonda uspe, neposredno prvo; ugotovitev velja in sonde ne ponavljamo.
    check(red() == listOf(n, r) && sond == 1) { "doma: neposredno, rele je zasilni izhod" }
    zdaj += PotDoNaprave.VELJA_MS - 1
    check(red() == listOf(n, r) && sond == 1) { "sveza ugotovitev: brez nove sonde" }

    // 3) Zastarelo »neposredno«: sonda takoj (doma traja trenutek). Zdaj smo zdoma - rele prvi, brez dolgega cakanja.
    zdaj += 2
    doma = false
    check(red() == listOf(r, n) && sond == 2) { "odsli smo od doma: sonda to pove, rele prvi" }
    check(PotDoNaprave.zadnja(k) == r)

    // 4) Zastarelo »rele«: zahteva gre takoj prek releja, sonda tece v ozadju in samo ena naenkrat.
    zdaj += PotDoNaprave.VELJA_MS + 1
    check(red() == listOf(r, n) && sond == 2 && cakajoce.size == 1) { "zdoma uporabnik ne caka: sonda gre v ozadje" }
    check(red() == listOf(r, n) && cakajoce.size == 1) { "druga zahteva ne sprozi se ene sonde" }
    cakajoce.removeAt(0)()
    check(sond == 3 && PotDoNaprave.zadnja(k) == r) { "se vedno zdoma: ostane rele" }
    check(red() == listOf(r, n) && sond == 3 && cakajoce.isEmpty()) { "ugotovitev je spet sveza" }

    // 5) Spet doma: sonda v ozadju to opazi, naslednja zahteva gre neposredno.
    zdaj += PotDoNaprave.VELJA_MS + 1
    doma = true
    check(red() == listOf(r, n) && cakajoce.size == 1) { "ta zahteva gre se prek releja" }
    cakajoce.removeAt(0)()
    check(PotDoNaprave.zadnja(k) == n && red() == listOf(n, r)) { "doma: nazaj na neposredno pot" }

    // 6) Uspeh po drugi poti (neposredna je odpovedala, rele je uspel) velja za naslednje zahteve; vsak uspeh podaljsa.
    PotDoNaprave.zapomni(k, r, zdaj)
    check(red() == listOf(r, n))
    zdaj += PotDoNaprave.VELJA_MS - 1
    PotDoNaprave.zapomni(k, r, zdaj)
    zdaj += PotDoNaprave.VELJA_MS - 1
    val pred = sond
    check(red() == listOf(r, n) && sond == pred && cakajoce.isEmpty()) { "raba ugotovitev podaljsuje" }

    // 7) Delo v ozadju, ki ga ni mogoce oddati, ne zaklene preverjanja.
    zdaj += PotDoNaprave.VELJA_MS + 1
    check(PotDoNaprave.vrstniRed(k, false, true, { zdaj }, { doma }, { throw IllegalStateException("zaprto") }) == listOf(r, n))
    check(red() == listOf(r, n) && cakajoce.size == 1) { "naslednja zahteva spet poskusi preveriti" }
    cakajoce.clear()

    // 8) Naslov prek releja: Hub naprave streze datoteke in slicice pod /cast; poizvedba in kodiranje ostaneta.
    check(PotDoNaprave.relejniNaslov("https://192.0.2.10:8443/d/share%3A0%3AGlasba%2Fa%20b.ogg", 40123) ==
        "https://127.0.0.1:40123/cast/d/share%3A0%3AGlasba%2Fa%20b.ogg") { "datoteka" }
    check(PotDoNaprave.relejniNaslov("https://192.0.2.10:8443/thumb/media%3Aimage%3A7?v=2", 40123) ==
        "https://127.0.0.1:40123/cast/thumb/media%3Aimage%3A7?v=2") { "slicica s poizvedbo" }
    check(PotDoNaprave.relejniNaslov("https://192.0.2.10:8443/live/abc", 40123) ==
        "https://127.0.0.1:40123/cast/live/abc") { "sprotni tok gre zdoma po isti poti" }
    check(PotDoNaprave.relejniNaslov("https://192.0.2.10:8443/m/skrivnost/film.mkv", 40123) ==
        "https://127.0.0.1:40123/cast/m/skrivnost/film.mkv") { "tok torrenta z racunalnika" }
    check(PotDoNaprave.relejniNaslov("https://192.0.2.10:8443/magnet/skrivnost", 40123) ==
        "https://127.0.0.1:40123/cast/magnet/skrivnost") { "tok torrenta z naprave" }
    check(PotDoNaprave.relejniNaslov("https://192.0.2.10:8443/drugo/abc", 40123) == null) { "drugih poti Hub ne streze" }
    check(PotDoNaprave.relejniNaslov("https://192.0.2.10:8443/cast/ws", 40123) == null)
    check(PotDoNaprave.relejniNaslov("https://192.0.2.10:8443/d/x", 0) == null) { "brez vrat releja ni naslova" }
    check(PotDoNaprave.relejniNaslov("ni naslov", 40123) == null)

    // 9) Kljuc: ista naprava na istem naslovu; druga naprava na istem naslovu ali ista na drugih vratih je drug kljuc.
    check(k == PotDoNaprave.kljuc("n-0123456789abcdef-control", "https://192.0.2.10:8443/thumb/b?x=1")) { "pot in poizvedba ne spremenita kljuca" }
    check(k == PotDoNaprave.kljuc("n-0123456789abcdef-control", "https://192.0.2.10:8443")) { "osnovni naslov streznika je isti kljuc" }
    check(k != PotDoNaprave.kljuc("n-fedcba9876543210-control", "https://192.0.2.10:8443/d/a")) { "druga naprava na istem naslovu" }
    check(k != PotDoNaprave.kljuc("n-0123456789abcdef-control", "https://192.0.2.10:8444/d/a")) { "druga vrata" }
    check(k != PotDoNaprave.kljuc("n-0123456789abcdef-control", "https://192.0.2.11:8443/d/a")) { "drug naslov" }
    check(PotDoNaprave.zadnja(PotDoNaprave.kljuc("n-fedcba9876543210-control", "https://192.0.2.10:8443/d/a")) == null) { "ugotovitev velja samo za to napravo" }

    println("PotDoNapraveTest: V REDU")
}
