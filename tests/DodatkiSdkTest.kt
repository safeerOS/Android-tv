package si.safeer.tv.sdk

import si.safeer.tv.os.DodatkiProtokol
import si.safeer.tv.os.OsPravila

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/**
 * Naloga 125: SDK dodatkov - primer dodatka (lazni dodatek na krajevnih vratih) in orodje za preverjanje proti njemu.
 * Isti scenariji kot v aplikaciji: razsiritve, mapa, dejanje, enrich, placilni zid (120), nastavitve po http brez skrivnosti.
 */
fun main() {
    println("Preizkus SDK dodatkov (primer + orodje za preverjanje)")
    val d = PrimerDodatka(0).zazeni()
    try {
        val osnova = "http://127.0.0.1:${d.vrata}"
        val p = PreveriDodatek { PreveriDodatek.omrezje(it) }
        val napakOrodja = p.preveri("$osnova/manifest.json", "movie" to "primer:film1", dejanja = true)
        p.vrstice.forEach { println("    " + it) }
        preveriEnako("primer dodatka: brez napak", 0, napakOrodja)
        preveriEnako("razsiritve prebrane", true, p.vrstice.any { it.contains("razsiritve safeer v1: dejanj 1, map 1, nastavitev 2") })
        preveriEnako("http: opozorilo o skrivnosti", true, p.vrstice.any { it.contains("Safeer jih po http ne poslje") })
        preveriEnako("tok predvajljiv", true, p.vrstice.any { it.contains("stream movie/primer:film1: predvajljivih 1") })
        preveriEnako("enrich dela", true, p.vrstice.any { it.startsWith("OK") && it.contains("enrich movie/primer:film1") })
        preveriEnako("dejanje poklicano", true, p.vrstice.any { it.contains("dejanje add-to-list: ok=true") })

        val placljiv = PreveriDodatek { PreveriDodatek.omrezje(it) }
        placljiv.preveri("$osnova/manifest.json", "movie" to "primer:placljiv")
        preveriEnako("placilni zid: opozorilo (Safeer skrije)", true, placljiv.vrstice.any { it.contains("samo placilni zid") })

        // Mapa: po dejanju je film v »Moj seznam / All«.
        val vsebina = PreveriDodatek.omrezje(DodatkiProtokol.naslovMape(osnova, "my-list", "all"))
        val mapa = vsebina?.let { DodatkiProtokol.preberiMapo(it.telo) }
        preveriEnako("mapa po dejanju", listOf("primer:film1"), mapa?.elementi?.map { it.id })

        // Nastavitve, kot jih poslje aplikacija: po http samo navadne (skrivnost nikoli nesifrirano).
        val r = DodatkiProtokol.preberi(PrimerDodatka.MANIFEST)!!
        val url = DodatkiProtokol.naslovDejanja(osnova, "add-to-list", "movie", "primer:film1")
        val glava = DodatkiProtokol.glavaNastavitev(DodatkiProtokol.nastavitveZaZahtevo(r.nastavitve, url), mapOf("api_key" to "skrito", "quality" to "720p"))
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.setRequestProperty("X-Safeer-Config", glava)
        val odgovor = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        preveriEnako("dodatek je dobil nastavitve", """{"quality":"720p"}""", d.zadnjaNastavitev)
        preveriEnako("skrivnost ni sla po http", true, odgovor.contains("brez API") || odgovor.contains("brez kljuca"))

        // Pokvarjen dodatek: orodje najde napake.
        val pokvarjen = PreveriDodatek { u -> if (u.endsWith("/manifest.json")) PreveriDodatek.Odziv(200,
            """{"id":"x","name":"X","version":"1","resources":["stream"],"types":["movie"],"safeer":{"actions":[]}}""", 10) else null }
        preveriEnako("brez protocol: napaka", 1, pokvarjen.preveri("https://ex.test/manifest.json"))
        val ni = PreveriDodatek { null }
        preveriEnako("dodatek ne odgovori: napaka", 1, ni.preveri("https://ex.test/manifest.json"))
        val velik = PreveriDodatek { PreveriDodatek.Odziv(200, "{\"x\":\"" + "a".repeat(OsPravila.NAJVEC_ODGOVORA_DODATKA) + "\"}", 30_000) }
        velik.preveri("https://ex.test/manifest.json")
        preveriEnako("prevelik in prepocasen odgovor", true, velik.vrstice.any { it.contains("8 MiB") } && velik.vrstice.any { it.contains("20 s") })

        // Meja po vrsti zahteve kot v aplikaciji: manifest (vir Stremio) 2 MiB je v redu, mapa (razsiritev) 2 MiB ne.
        val dva = "a".repeat(2 * 1024 * 1024)
        val srednji = PreveriDodatek { u ->
            if (u.endsWith("/manifest.json")) PreveriDodatek.Odziv(200, """{"id":"x","name":"X","version":"1.0.0","resources":["stream"],"types":["movie"],
                "safeer":{"protocol":1,"folders":[{"id":"f","name":"F"}]},"pad":"$dva"}""", 10)
            else PreveriDodatek.Odziv(200, """{"items":[],"pad":"$dva"}""", 10)
        }
        preveriEnako("manifest 2 MiB sprejet, mapa 2 MiB zavrnjena", 1, srednji.preveri("https://ex.test/manifest.json"))
        preveriEnako("mapa: meja 1 MiB", true, srednji.vrstice.any { it.contains("mapa f: odgovor vecji od 1 MiB") })
        preveriEnako("manifest: brez napake meje", false, srednji.vrstice.any { it.contains("manifest: odgovor vecji") })
    } finally { d.ustavi() }
    if (napak == 0) println("DodatkiSdkTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
