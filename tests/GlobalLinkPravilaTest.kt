package si.safeer.tv.link

private var stevilo = 0
private fun preveri(pogoj: Boolean, sporocilo: String) { check(pogoj) { sporocilo }; stevilo++ }

fun main(args: Array<String>) {
    val P = GlobalLinkPravila
    // Vrsta posiljanja
    preveri(P.odlocitevVrste(1_000_001L, 11_000L, false) == GlobalLinkPravila.Akcija.PREKINI, "vrsta polna 11 s -> prekini")
    preveri(P.odlocitevVrste(1_000_001L, 10_000L, false) == GlobalLinkPravila.Akcija.CAKAJ, "natanko 10 s se caka")
    preveri(P.odlocitevVrste(1_000_001L, 5_000L, false) == GlobalLinkPravila.Akcija.CAKAJ, "polna vrsta, cas se ni potekel")
    preveri(P.odlocitevVrste(1_000_000L, 2_000L, false) == GlobalLinkPravila.Akcija.NADALJUJ, "vrsta se sprazni -> nadaljuj")
    preveri(P.odlocitevVrste(1_000_000L, 60_000L, false) == GlobalLinkPravila.Akcija.NADALJUJ, "prazna vrsta ne prekine niti po dolgem casu")
    preveri(P.odlocitevVrste(1_000_001L, 0L, true) == GlobalLinkPravila.Akcija.PREKINI, "zaprta vticnica -> takoj")
    preveri(P.odlocitevVrste(0L, 0L, true) == GlobalLinkPravila.Akcija.PREKINI, "zaprta vticnica, prazna vrsta")
    // Zamiki: zaporedje ob stalnih napakah je 5, 10, 20 ... 300 s (prvi poskus NE caka 10 s)
    var naVrsti = P.ZAMIK_PRVI_MS
    val zaporedje = ArrayList<Long>()
    repeat(9) { val zdaj = P.zamikZdaj(naVrsti, 1_000L); zaporedje.add(zdaj); naVrsti = P.naslednjiZamik(zdaj) }
    preveri(zaporedje == listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L, 300_000L), "zaporedje $zaporedje")
    preveri(P.zamikZdaj(300_000L, 61_000L) == 5_000L, "po zdravi povezavi spet 5 s")
    preveri(P.zamikZdaj(300_000L, 60_000L) == 5_000L, "meja zdrave povezave je vkljucena")
    preveri(P.zamikZdaj(300_000L, 59_999L) == 300_000L, "kratka povezava zamika ne ponastavi")
    preveri(P.zamikZdaj(0L, 0L) == 5_000L, "nikoli manj kot 5 s (brez vrtenja v prazno)")
    preveri(P.naslednjiZamik(200_000L) == 300_000L, "najvec 5 min")
    // Kanali releja (neodvisni pregled 12. 11. 2026)
    preveri(P.veljavenKanal("0123456789abcdef0123456789abcdef"), "kanal 32 heks znakov velja")
    preveri(!P.veljavenKanal("0123456789abcdef0123456789abcde&"), "kanal z & ne velja (32 znakov)")
    preveri(!P.veljavenKanal("0123456789abcdef0123456789abcdE0"), "velike crke ne veljajo")
    preveri(!P.veljavenKanal("0123456789abcdef0123456789abcde"), "31 znakov ne velja")
    preveri(!P.veljavenKanal("0123456789abcdef0123456789abcde#"), "kanal z # ne velja")
    preveri(P.smeSprejeti(0) && P.smeSprejeti(GlobalLinkPravila.NAJVEC_KANALOV - 1), "pod mejo se sprejme")
    preveri(!P.smeSprejeti(GlobalLinkPravila.NAJVEC_KANALOV), "na meji se zavrne")
    preveri((1..1000).count { P.smeSprejeti(it) } == GlobalLinkPravila.NAJVEC_KANALOV - 1, "1000 hkrati: nad mejo se zavrne")
    // N2: umaknjena naprava ni v seznamu dovoljenih (seznam se gradi iz TRENUTNEGA kroga)
    val krog = listOf("n-aaaa", "n-bbbb", null, "n-cccc", "n-bbbb", "n-nas")
    preveri(P.dovoljeniIdji(krog, "n-nas") == listOf("n-aaaa", "n-bbbb", "n-cccc"), "dovoljeni: brez nas, brez null, brez podvojenih")
    preveri(P.dovoljeniIdji(krog.filter { it != "n-bbbb" }, "n-nas") == listOf("n-aaaa", "n-cccc"), "umaknjena naprava izgine iz seznama")
    preveri(P.dovoljeniIdji((1..1000).map { "n-%04d".format(it) }, "n-nas").size == P.NAJVEC_DOVOLJENIH, "najvec 256 dovoljenih")
    // N3: odjemalec releja ne sledi preusmeritvi - podpisane glave ne pridejo na drug naslov (krajevna streznika)
    run {
        val b = java.net.ServerSocket(0)
        val prejelB = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val nitB = kotlin.concurrent.thread(isDaemon = true) {
            try { b.accept().use { sk ->
                val r = sk.getInputStream().bufferedReader()
                prejelB.set(generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.joinToString("\n"))
                sk.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
            } } catch (_: Exception) { }
        }
        val a = java.net.ServerSocket(0)
        kotlin.concurrent.thread(isDaemon = true) {
            try { a.accept().use { sk ->
                val r = sk.getInputStream().bufferedReader()
                generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.count()
                sk.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${b.localPort}/kradem\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
            } } catch (_: Exception) { }
        }
        val odj = P.odjemalec(okhttp3.OkHttpClient.Builder().connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)).build()
        val zahteva = okhttp3.Request.Builder().url("http://127.0.0.1:${a.localPort}/v1/presence").header("X-Safeer-Signature", "podpis-skrivnost").build()
        val koda = odj.newCall(zahteva).execute().use { it.code }
        nitB.join(500)
        preveri(koda == 302, "preusmeritev ni sledena (koda $koda)")
        preveri(prejelB.get() == null, "drug naslov ni prejel podpisanih glav")
        a.close(); b.close()
    }
    // Povezava v kodi: vsi odjemalci Global Linka gredo skozi odjemalec(), umik kroga sproži objavo (vir kot besedilo)
    val vir = java.io.File(args.firstOrNull() ?: ".", "si/safeer/tv/link/GlobalLink.kt").readText()
    preveri(Regex("""OkHttpClient\.Builder\(\)""").findAll(vir).count() == Regex("""GlobalLinkPravila\.odjemalec\(OkHttpClient\.Builder\(\)""").findAll(vir).count(),
        "vsak OkHttpClient.Builder v GlobalLink.kt gre skozi GlobalLinkPravila.odjemalec")
    preveri("obSpremembiKroga(\"global-link-hub\")" in vir && "GlobalLinkPravila.dovoljeniIdji(" in vir, "umik kroga sproži objavo, seznam iz pravil")
    // Nakljucni zamik (izpad Microsoft 365, 13. 11. 2026): meje in vse tri zanke ponovnih povezav ga uporabljajo
    preveri(P.razprsen(10_000L, 0.0) == 5_000L, "spodnja meja je polovica")
    preveri(P.razprsen(10_000L, 1.0) == 10_000L, "zgornja meja je cel zamik")
    preveri(P.razprsen(10_000L, 7.0) == 10_000L && P.razprsen(10_000L, -1.0) == 5_000L, "vir zunaj [0, 1] omejen")
    preveri(P.razprsen(-5L, 0.5) == 0L, "negativen zamik -> 0")
    val razlicni = (1..50).map { P.razprsen(300_000L) }.toSet()
    preveri(razlicni.size > 40 && razlicni.all { it in 150_000L..300_000L }, "privzeti vir razprsi v mejah")
    val koren = java.io.File(args.firstOrNull() ?: ".")
    for ((dat, vzorec) in listOf(
        "si/safeer/tv/link/GlobalLink.kt" to "GlobalLinkPravila.razprsen(zdaj)",
        "si/safeer/tv/os/LinkOdjemalec.kt" to "GlobalLinkPravila.razprsen(minOf(30_000L",
        "si/safeer/tv/os/LinkUpravitelj.kt" to "GlobalLinkPravila.razprsen(minOf(60_000L")) {
        preveri(vzorec in java.io.File(koren, dat).readText(), "$dat: ponovna povezava z nakljucnim zamikom")
    }
    println("GlobalLinkPravilaTest: OK ($stevilo preverb)")
}
