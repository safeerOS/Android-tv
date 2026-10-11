package si.safeer.tv.cast

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.Socket
import java.nio.file.Files

/**
 * Preizkus tokov Safeer Huba: deljenje zaslona (MJPEG prek Huba) in prenos datotek.
 * Tece v navadnem JVM z resnicnimi vticnicami, brez Androida.
 */

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

private fun preberiVrstico(vhod: InputStream): String {
    val sb = StringBuilder()
    while (true) {
        val c = vhod.read()
        if (c < 0) break
        if (c == '\n'.code) break
        if (c != '\r'.code) sb.append(c.toChar())
    }
    return sb.toString()
}

private fun preberiGlave(vhod: InputStream): Pair<String, Map<String, String>> {
    val prva = preberiVrstico(vhod)
    val glave = HashMap<String, String>()
    while (true) {
        val v = preberiVrstico(vhod)
        if (v.isEmpty()) break
        val i = v.indexOf(':')
        if (i > 0) glave[v.substring(0, i).trim().lowercase()] = v.substring(i + 1).trim()
    }
    return prva to glave
}

private fun preberiTocno(vhod: InputStream, koliko: Int): ByteArray {
    val out = ByteArray(koliko)
    var p = 0
    while (p < koliko) {
        val n = vhod.read(out, p, koliko - p)
        if (n < 0) break
        p += n
    }
    return out
}

private fun zahteva(vrata: Int, metoda: String, pot: String, glave: Map<String, String> = emptyMap(), telo: ByteArray = ByteArray(0)): Triple<String, ByteArray, Map<String, String>> {
    Socket("127.0.0.1", vrata).use { s ->
        s.soTimeout = 10_000
        val out = s.getOutputStream()
        val g = StringBuilder("$metoda $pot HTTP/1.1\r\nHost: x\r\nConnection: close\r\n")
        for ((k, v) in glave) g.append("$k: $v\r\n")
        g.append("Content-Length: ${telo.size}\r\n\r\n")
        out.write(g.toString().toByteArray(Charsets.US_ASCII)); out.write(telo); out.flush()
        val vhod = s.getInputStream()
        val (prva, gl) = preberiGlave(vhod)
        val dolzina = gl["content-length"]?.toIntOrNull()
        val tel = if (dolzina != null) preberiTocno(vhod, dolzina) else vhod.readBytes()
        return Triple(prva, tel, gl)
    }
}

private fun polje(json: String, kljuc: String): String = JsonLahki.objekt(json)?.niz(kljuc) ?: ""

fun main() {
    val mapaPrenosov = Files.createTempDirectory("safeer-prenosi").toFile()
    val mapaZacasna = Files.createTempDirectory("safeer-zacasno").toFile()
    val prejete = ArrayList<String>()
    val tokovi = HubTokovi(
        mapaPrenosov = { mapaPrenosov },
        mapaZacasna = { mapaZacasna },
        jeVeljavenZeton = { it == "pravi" },
        lastniId = { "tv-gostitelj" },
        naPrejetoDatoteko = { ime, _ -> prejete.add(ime) }
    )
    val streznik = HubStreznik(
        zeljenaVrata = 0,
        naZahtevo = { null },
        preveriVstopnico = { "ni" },
        naPovezavo = { },
        naTok = { z, vhod, izhod, vt -> tokovi.obdelaj(z, vhod, izhod, vt) }
    )
    preveri("streznik tece", streznik.zazeni())
    val vrata = streznik.vrata

    println("\n== datoteke ==")
    val vsebina = ByteArray(300_000) { (it % 251).toByte() }
    val brez = zahteva(vrata, "PUT", "/cast/file?name=slika.jpg&target=tv-gostitelj", telo = vsebina)
    preveri("brez zetona je 401", brez.first.contains("401"))

    val za = zahteva(vrata, "PUT", "/cast/file?name=../../slika.jpg&target=tv-gostitelj", mapOf("x-safeer-token" to "pravi"), vsebina)
    preveri("datoteka za gostitelja sprejeta", za.first.contains("200"))
    val odgovor = String(za.second, Charsets.UTF_8)
    preveri("ime je ociscene poti", polje(odgovor, "name") == "slika.jpg")
    val pricakovanOdtis = java.security.MessageDigest.getInstance("SHA-256").digest(vsebina).joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
    preveri("odgovor nosi prstni odtis SHA-256 vsebine", polje(odgovor, "sha256") == pricakovanOdtis)
    val napacen = zahteva(vrata, "PUT", "/cast/file?name=x.jpg&target=tv-gostitelj", mapOf("x-safeer-token" to "pravi", "x-safeer-sha256" to "00ff"), vsebina)
    preveri("napovedan napacen odtis zavrne datoteko (400)", napacen.first.contains("400") && !File(mapaPrenosov, "x.jpg").exists())
    val pravilen = zahteva(vrata, "PUT", "/cast/file?name=y.jpg&target=tv-gostitelj", mapOf("x-safeer-token" to "pravi", "x-safeer-sha256" to pricakovanOdtis), vsebina)
    preveri("napovedan pravilen odtis je sprejet", pravilen.first.contains("200"))
    preveri("gostitelj je obvescen", prejete.firstOrNull() == "slika.jpg" && prejete.contains("y.jpg"))
    val shranjena = File(mapaPrenosov, "slika.jpg")
    preveri("datoteka je v mapi prenosov, cela", shranjena.isFile && shranjena.readBytes().contentEquals(vsebina))

    val se = zahteva(vrata, "PUT", "/cast/file?name=slika.jpg&target=tv-gostitelj", mapOf("x-safeer-token" to "pravi"), vsebina)
    preveri("druga z istim imenom dobi enolicno ime", polje(String(se.second, Charsets.UTF_8), "name") == "slika (1).jpg")

    val zaDrugo = zahteva(vrata, "PUT", "/cast/file?name=zapis.txt&target=fon2", mapOf("x-safeer-token" to "pravi"), "zdravo".toByteArray())
    val odg2 = String(zaDrugo.second, Charsets.UTF_8)
    preveri("datoteka za drugo napravo je v zacasni mapi", polje(odg2, "for_host") == "false" || odg2.contains("\"for_host\":false"))
    preveri("prejemnik dobi izvirno ime, brez zacasne predpone", polje(odg2, "name") == "zapis.txt")
    val id2 = polje(odg2, "id"); val k2 = polje(odg2, "key")
    preveri("napacen kljuc ne dobi datoteke", zahteva(vrata, "GET", "/cast/file/$id2?k=napacen").first.contains("404"))
    val prevzem = zahteva(vrata, "GET", "/cast/file/$id2?k=$k2")
    preveri("prevzem s kljucem uspe", prevzem.first.contains("200") && String(prevzem.second, Charsets.UTF_8) == "zdravo")
    preveri("prevzem nosi glavo z odtisom", prevzem.third["x-safeer-sha256"] == polje(odg2, "sha256") && polje(odg2, "sha256").length == 64)
    preveri("po prevzemu zacasne ni vec", zahteva(vrata, "GET", "/cast/file/$id2?k=$k2").first.contains("404"))

    // Prekinjen prenos: napovedanih 999999 bajtov, poslanih 10, nato zaprta vticnica.
    Socket("127.0.0.1", vrata).use { sk ->
        sk.getOutputStream().write(("PUT /cast/file?name=x.bin&target=tv-gostitelj HTTP/1.1\r\nHost: x\r\n" +
            "x-safeer-token: pravi\r\nContent-Length: 999999\r\n\r\n").toByteArray())
        sk.getOutputStream().write(ByteArray(10)); sk.getOutputStream().flush()
    }
    Thread.sleep(300)
    preveri("nepopolna datoteka ne ostane na disku", !File(mapaPrenosov, "x.bin").exists())

    println("\n== nadaljevanje oddaje in prevzema ==")
    val velika = ByteArray(400_000) { ((it * 7) % 253).toByte() }
    val shaV = java.security.MessageDigest.getInstance("SHA-256").digest(velika).joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
    val poizv = "/cast/file?name=film.mkv&target=fon2&dir=Filmi/../2026/.skrito"
    // Prvi poskus: napovedanih 400000, poslanih 150000, nato vticnica pade (izpad Wi-Fi).
    Socket("127.0.0.1", vrata).use { sk ->
        sk.getOutputStream().write(("PUT $poizv HTTP/1.1\r\nHost: x\r\nx-safeer-token: pravi\r\n" +
            "x-safeer-sha256: $shaV\r\nx-safeer-size: ${velika.size}\r\nx-safeer-offset: 0\r\n" +
            "Content-Length: ${velika.size}\r\n\r\n").toByteArray())
        sk.getOutputStream().write(velika, 0, 150_000); sk.getOutputStream().flush()
        Thread.sleep(300)
    }
    Thread.sleep(300)
    val glava = zahteva(vrata, "HEAD", "/cast/file?target=fon2&sha256=$shaV&size=${velika.size}", mapOf("x-safeer-token" to "pravi"))
    val imamo = glava.third["x-safeer-offset"]?.toLongOrNull() ?: -1L
    preveri("HEAD pove, koliko ze imamo (${imamo})", glava.first.contains("200") && glava.third["x-safeer-resume"] == "1" && imamo in 1..150_000)
    preveri("HEAD brez zetona ne pove nicesar", zahteva(vrata, "HEAD", "/cast/file?target=fon2&sha256=$shaV&size=${velika.size}").third["x-safeer-offset"] == "0")
    val napacnoMesto = zahteva(vrata, "PUT", poizv, mapOf("x-safeer-token" to "pravi", "x-safeer-sha256" to shaV,
        "x-safeer-size" to "${velika.size}", "x-safeer-offset" to "${imamo + 5}"), velika.copyOfRange((imamo + 5).toInt(), velika.size))
    preveri("napacen odmik je 409 z resnicnim odmikom", napacnoMesto.first.contains("409") && polje(String(napacnoMesto.second, Charsets.UTF_8), "koda") == "napacen_odmik")
    val ostanekV = zahteva(vrata, "PUT", poizv, mapOf("x-safeer-token" to "pravi", "x-safeer-sha256" to shaV,
        "x-safeer-size" to "${velika.size}", "x-safeer-offset" to "$imamo"), velika.copyOfRange(imamo.toInt(), velika.size))
    val odgV = String(ostanekV.second, Charsets.UTF_8)
    preveri("nadaljevanje uspe in odtis se ujema", ostanekV.first.contains("200") && polje(odgV, "sha256") == shaV)
    val idV = polje(odgV, "id"); val kV = polje(odgV, "key")
    val delni = zahteva(vrata, "GET", "/cast/file/$idV?k=$kV", mapOf("Range" to "bytes=300000-"))
    preveri("Range da 206 z ostankom", delni.first.contains("206") && delni.third["content-range"] == "bytes 300000-399999/400000" &&
        delni.second.contentEquals(velika.copyOfRange(300_000, velika.size)))
    // Ostanek do konca je dokoncan prevzem: zacasna datoteka je opravila svoje.
    preveri("po prevzemu ostanka zacasne ni vec", zahteva(vrata, "GET", "/cast/file/$idV?k=$kV").first.contains("404"))
    preveri("varna mapa", HubTokovi.varnaMapa("Filmi/../2026/.skrito") == "Filmi/2026/skrito" && HubTokovi.varnaMapa("") == "")
    preveri("obseg", HubTokovi.zacetekObsega("bytes=10-", 100) == 10L && HubTokovi.zacetekObsega("bytes=0-", 100) == 0L &&
        HubTokovi.zacetekObsega("bytes=5-9", 100) == 0L && HubTokovi.zacetekObsega("bytes=100-", 100) == 0L)
    // Za gostitelja: mapa posiljatelja v Prenosih; nepopolne datoteke uporabnik ne vidi pod pravim imenom.
    val zaG = zahteva(vrata, "PUT", "/cast/file?name=a.txt&target=tv-gostitelj&dir=Po%C4%8Ditnice/Dan%201", mapOf("x-safeer-token" to "pravi"), "abc".toByteArray())
    preveri("datoteka za gostitelja v podmapi", zaG.first.contains("200") && File(mapaPrenosov, "Počitnice/Dan 1/a.txt").isFile)
    preveri("brez ostankov .safeer-delno", mapaPrenosov.walkTopDown().none { it.name.endsWith(".safeer-delno") })

    println("\n== zaslon ==")
    val zacetek = tokovi.zacniZaslon("fon1")
    preveri("deljenje se zacne", zacetek != null)
    val (id, kljuc) = zacetek!!
    preveri("stran gledalca je HTML", String(zahteva(vrata, "GET", "/cast/screen/$id/view?k=$kljuc").second, Charsets.UTF_8).contains("object-fit:contain"))
    preveri("stran z napacnim kljucem je 404", zahteva(vrata, "GET", "/cast/screen/$id/view?k=x").first.contains("404"))

    // Dotik gledalca: sredisce ga preda napravi, ki deli zaslon, z znakom deljenja (DostopPravila.znakGledalca).
    val vnosi = java.util.concurrent.CopyOnWriteArrayList<Triple<String, String, String>>()
    tokovi.naVnosGledalca = { komu, akcija, parametri -> vnosi.add(Triple(komu, akcija, parametri)) }
    val dotik = zahteva(vrata, "POST", "/cast/screen/$id/input?k=$kljuc",
        telo = "{\"vrsta\":\"tap\",\"x\":0.5,\"y\":0.25,\"_znak\":\"vsiljen\"}".toByteArray())
    preveri("dotik gledalca je sprejet in gre napravi, ki deli zaslon",
        dotik.first.contains("200") && vnosi.size == 1 && vnosi[0].first == "fon1" && vnosi[0].second == "input.tap")
    preveri("dotik nosi znak deljenja, ki ga doloci sredisce (ne telo zahteve)",
        vnosi.isNotEmpty() && DostopPravila.znakGledalca(id, kljuc).length == 64 &&
            polje(vnosi[0].third, DostopPravila.PARAM_ZNAK_GLEDALCA) == DostopPravila.znakGledalca(id, kljuc))
    preveri("dotik z napacnim kljucem je 404 in ne gre naprej",
        zahteva(vrata, "POST", "/cast/screen/$id/input?k=x", telo = "{\"vrsta\":\"tap\",\"x\":0.5,\"y\":0.5}".toByteArray())
            .first.contains("404") && vnosi.size == 1)
    tokovi.naVnosGledalca = null

    // gledalec se prikljuci pred prvim okvirjem
    val gledalec = Socket("127.0.0.1", vrata).apply { soTimeout = 10_000 }
    gledalec.getOutputStream().write("GET /cast/screen/$id/stream?k=$kljuc HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); gledalec.getOutputStream().flush()
    val (prvaG, glaveG) = preberiGlave(gledalec.getInputStream())
    preveri("gledalec dobi MJPEG", prvaG.contains("200") && (glaveG["content-type"] ?: "").contains("multipart/x-mixed-replace"))

    // okvirje sme potiskati samo naprava, ki je deljenje zacela
    tokovi.napravaZeZetona = { z -> if (z == "pravi") "fon1" else if (z == "tuj") "fon9" else null }
    val tujZeton = mapOf("x-safeer-token" to "tuj")
    tokovi.napravaZeZetona = { z -> if (z == "pravi") "fon1" else if (z == "tuj") "fon9" else null }
    val jeVeljavenTudiTuj = HubTokovi(mapaPrenosov = { mapaPrenosov }, mapaZacasna = { mapaZacasna },
        jeVeljavenZeton = { it == "pravi" || it == "tuj" }, lastniId = { "tv-gostitelj" })
    preveri("pomozni tokovi obstajajo", jeVeljavenTudiTuj.zaslonTece("x") == false)
    // (streznik uporablja `tokovi`, kjer velja samo zeton "pravi"; tuj zeton pade ze na 401)
    preveri("tuj zeton ne more potiskati okvirjev", zahteva(vrata, "POST", "/cast/screen/$id?k=$kljuc", tujZeton).first.contains("401"))

    // posiljatelj potisne 3 okvirje
    val posiljatelj = Socket("127.0.0.1", vrata).apply { soTimeout = 10_000 }
    val pOut = DataOutputStream(posiljatelj.getOutputStream())
    pOut.write("POST /cast/screen/$id?k=$kljuc HTTP/1.1\r\nHost: x\r\nx-safeer-token: pravi\r\n\r\n".toByteArray()); pOut.flush()
    val okvirji = listOf(ByteArray(1000) { 1 }, ByteArray(2000) { 2 }, ByteArray(1500) { 3 })
    for (o in okvirji) { pOut.writeInt(o.size); pOut.write(o); pOut.flush(); Thread.sleep(60) }

    val gIn = gledalec.getInputStream()
    var prejetih = 0
    for (pricakovan in okvirji) {
        var vrstica = preberiVrstico(gIn)
        while (vrstica.isEmpty()) vrstica = preberiVrstico(gIn)
        preveri("meja okvirja", vrstica.startsWith("--"))
        val (_, gl) = preberiGlave(gIn).let { it.first to it.second }
        val dolz = gl["content-length"]?.toIntOrNull() ?: -1
        val bajti = preberiTocno(gIn, dolz)
        preveri("okvir $prejetih je cel in nespremenjen", bajti.contentEquals(pricakovan))
        prejetih++
    }
    preveri("gledalec je dobil vse tri okvirje", prejetih == 3)

    // konec deljenja: posiljatelj zapre - gledalec dobi zakljucno mejo
    posiljatelj.close()
    val ostanek = ByteArrayOutputStream()
    try { while (true) { val c = gIn.read(); if (c < 0) break; ostanek.write(c) } } catch (_: Exception) { }
    preveri("po koncu gledalec dobi zakljucek", String(ostanek.toByteArray()).contains("--safeerokvir--"))
    preveri("deljenje je koncano", !tokovi.zaslonTece(id))
    gledalec.close()

    streznik.ustavi()
    mapaPrenosov.deleteRecursively(); mapaZacasna.deleteRecursively()
    println(if (napak == 0) "\nVSE V REDU" else "\nNapak: $napak")
    if (napak > 0) System.exit(1)
}
