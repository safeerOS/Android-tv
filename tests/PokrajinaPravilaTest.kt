package si.safeer.tv.os

import java.util.Locale
import java.util.TimeZone

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Naloga 42 A: tema Pokrajina novega TV zaganjalnika ([PokrajinaPravila]) in vreme ([VremePravila]). */
fun main() {
    println("Preizkus zaganjalnika Pokrajina")
    val utc = TimeZone.getTimeZone("UTC")
    // torek, 27. 10. 2026, 03:29 UTC
    val t = 1_793_071_740_000L

    println("\n== ura in datum v jeziku vmesnika ==")
    preveriEnako("ura", "03:29", PokrajinaPravila.ura(t, Locale("sl"), utc))
    preveriEnako("datum sl", "Tor., 27. okt.", PokrajinaPravila.datum(t, Locale("sl"), utc))
    preveriEnako("datum en (napaka zgleda: EN vmesnik, SL datum)", "Tue, 27 Oct", PokrajinaPravila.datum(t, Locale("en"), utc))
    preveriEnako("datum de", "Di., 27. Okt.", PokrajinaPravila.datum(t, Locale("de"), utc))
    preveriEnako("datum es", "Mar, 27 oct", PokrajinaPravila.datum(t, Locale("es"), utc))
    preveriEnako("datum fr", "Mar. 27 oct.", PokrajinaPravila.datum(t, Locale("fr"), utc))
    preveriEnako("datum it", "Mar 27 ott", PokrajinaPravila.datum(t, Locale("it"), utc))
    preveriEnako("datum ne sledi privzetemu jeziku JVM", "Tue, 27 Oct",
        Locale.getDefault().let { prej -> Locale.setDefault(Locale("sl")); try { PokrajinaPravila.datum(t, Locale("en"), utc) } finally { Locale.setDefault(prej) } })
    preveriEnako("do naslednje minute", 20_000L, PokrajinaPravila.doNaslednjeMinute(t + 40_000L))
    preveriEnako("na polni minuti cela minuta", 60_000L, PokrajinaPravila.doNaslednjeMinute(t))

    println("\n== tema ==")
    preveriEnako("nov zaganjalnik brez izbire: Pokrajina", "pokrajina", PokrajinaPravila.tema(null, true))
    preveriEnako("stari zaganjalnik brez izbire: prejsnja privzeta", "namizje", PokrajinaPravila.tema(null, false))
    preveriEnako("izbrana ostane pri novem", "nord", PokrajinaPravila.tema("nord", true))
    preveriEnako("izbrana Pokrajina ostane pri starem", "pokrajina", PokrajinaPravila.tema("pokrajina", false))
    preveriEnako("prazna izbira = ni izbire", "namizje", PokrajinaPravila.tema(" ", false))
    preveriEnako("vrstica razdelkov po zgledu", listOf("mediji", "splet", "link", "sporocila", "aplikacije", "datoteke", "nastavitve"), PokrajinaPravila.DOK)

    println("\n== zmanjsano gibanje in fokus ==")
    preveriEnako("navadno", 200L, PokrajinaPravila.trajanje(200, 1f, false))
    preveriEnako("Android brez animacij", 0L, PokrajinaPravila.trajanje(200, 0f, false))
    preveriEnako("izbira zmanjsano", 0L, PokrajinaPravila.trajanje(200, 1f, true))
    preveriEnako("pocasnejse animacije sistema", 400L, PokrajinaPravila.trajanje(200, 2f, false))
    preveriEnako("brez povecave pri zmanjsanem", 1f, PokrajinaPravila.povecava(true))
    preveriEnako("povecava sicer", true, PokrajinaPravila.povecava(false) > 1f)
    preveriEnako("fokus desno", 3, PokrajinaPravila.naslednji(2, 1, 7))
    preveriEnako("fokus na desnem robu ostane", 6, PokrajinaPravila.naslednji(6, 1, 7))
    preveriEnako("fokus na levem robu ostane", 0, PokrajinaPravila.naslednji(0, -1, 7))
    preveriEnako("prazna vrstica", -1, PokrajinaPravila.naslednji(0, 1, 0))

    println("\n== kontrast ==")
    preveriEnako("belo na crnem 21", 21.0, Math.round(PokrajinaPravila.kontrast(0xFFFFFFFF.toInt(), 0xFF000000.toInt()) * 10) / 10.0)
    preveriEnako("ista barva 1", 1.0, PokrajinaPravila.kontrast(0xFF336699.toInt(), 0xFF336699.toInt()))
    preveriEnako("simetricno", PokrajinaPravila.kontrast(0xFF101A2B.toInt(), 0xFFEAF2FF.toInt()), PokrajinaPravila.kontrast(0xFFEAF2FF.toInt(), 0xFF101A2B.toInt()))
    preveriEnako("50 % belo cez crno", 0xFF808080.toInt(), PokrajinaPravila.cez(0x80FFFFFF.toInt(), 0xFF000000.toInt()))
    preveriEnako("neprosojno cez karkoli", 0xFF123456.toInt(), PokrajinaPravila.cez(0xFF123456.toInt(), 0xFFFFFFFF.toInt()))

    println("\n== vreme ==")
    preveriEnako("jasno", "jasno", VremePravila.vrsta(0))
    preveriEnako("delno oblacno", "delno", VremePravila.vrsta(2))
    preveriEnako("megla", "megla", VremePravila.vrsta(45))
    preveriEnako("prsi", "dez", VremePravila.vrsta(61))
    preveriEnako("plohe", "dez", VremePravila.vrsta(81))
    preveriEnako("sneg", "sneg", VremePravila.vrsta(73))
    preveriEnako("nevihta", "nevihta", VremePravila.vrsta(95))
    preveriEnako("neznana koda: oblacno", "oblacno", VremePravila.vrsta(42))
    preveriEnako("brez kraja ni zahteve", null, VremePravila.naslovIskanja(" ", "sl"))
    preveriEnako("iskanje kraja v jeziku vmesnika, kodirano",
        "https://geocoding-api.open-meteo.com/v1/search?name=Novo+mesto&count=1&language=sl&format=json", VremePravila.naslovIskanja(" Novo   mesto ", "sl"))
    preveriEnako("neveljaven jezik -> en", true, VremePravila.naslovIskanja("Ljubljana", "x&y")!!.contains("language=en"))
    preveriEnako("vbrizg v ime je kodiran", true, VremePravila.naslovIskanja("a&count=99", "sl")!!.contains("name=a%26count%3D99&count=1"))
    val kraj = VremePravila.razcleniKraj("""{"results":[{"name":"Ljubljana","latitude":46.05108,"longitude":14.50513,"country":"Slovenija"}],"generationtime_ms":0.5}""")
    preveriEnako("kraj", VremePravila.Kraj("Ljubljana", "Slovenija", 46.05108, 14.50513), kraj)
    preveriEnako("ni zadetka", null, VremePravila.razcleniKraj("""{"generationtime_ms":0.5}"""))
    preveriEnako("koordinate zunaj obsega", null, VremePravila.razcleniKraj("""{"results":[{"name":"X","latitude":123,"longitude":1}]}"""))
    preveriEnako("pokvarjen odgovor", null, VremePravila.razcleniKraj("<html>"))
    preveriEnako("napoved na dve decimalki (kraj, ne naslov)",
        "https://api.open-meteo.com/v1/forecast?latitude=46.05&longitude=14.51&current=temperature_2m,weather_code&timezone=auto",
        VremePravila.naslovNapovedi(kraj!!))
    val v = VremePravila.razcleniVreme("""{"latitude":46.06,"current_units":{"temperature_2m":"°C"},"current":{"time":"2026-10-27T03:15","temperature_2m":19.6,"weather_code":1}}""")
    preveriEnako("trenutno vreme", VremePravila.Vreme(20, 1), v)
    preveriEnako("napis", "20 °C", VremePravila.napis(v!!))
    preveriEnako("napis pod nic s pravim minusom", "−4 °C", VremePravila.napis(VremePravila.Vreme(-4, 71)))
    preveriEnako("brez temperature", null, VremePravila.razcleniVreme("""{"current":{"weather_code":1}}"""))
    preveriEnako("nesmiselna temperatura", null, VremePravila.razcleniVreme("""{"current":{"temperature_2m":999,"weather_code":1}}"""))
    preveriEnako("sveze", true, VremePravila.svezo(1_000L, 1_000L + 60_000L))
    preveriEnako("zastarelo 3 h", false, VremePravila.svezo(1_000L, 1_000L + 3 * 3_600_000L))
    preveriEnako("nikoli: ni sveze", false, VremePravila.svezo(0L, 5_000L))
    preveriEnako("ura nazaj: ni sveze", false, VremePravila.svezo(10_000L, 5_000L))
    preveriEnako("osvezi po 30 min", true, VremePravila.osvezi(1_000L, 1_000L + 30 * 60_000L))
    preveriEnako("ne osvezi po 5 min", false, VremePravila.osvezi(1_000L, 1_000L + 5 * 60_000L))

    if (napak == 0) println("PokrajinaPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
