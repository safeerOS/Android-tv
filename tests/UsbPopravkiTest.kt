// Popravki USB predpomnilnika brskalnika (15. 11. 2026): XX3 (samo HTTP predpomnilnik, zapornice, brez povezav na poti),
// XX4 (rok 300 ms, privzeto notranji), XX5 (24 h, neuspeh samo ob sesutju), XX6 (en priklop, brisanje brez sledenja
// povezavam), XX7 (praznjenje stare mape), XX8 (nakljucni dostop). Cista pravila (BrskalnikDiskPravila); klici v
// BrskalnikPredpomnilnik so Android in tu NEPREVERJENI. Brez popravka (stara logika v pravilih) preizkus PADE.
// Rocno (ni v run_splet_tests.sh):
//   kotlinc src/main/kotlin/si/safeer/tv/os/BrskalnikDiskPravila.kt tests/UsbPopravkiTest.kt -include-runtime -d /tmp/usb.jar
//   java -cp /tmp/usb.jar si.safeer.tv.UsbPopravkiTestKt
package si.safeer.tv

import si.safeer.tv.os.BrskalnikDiskPravila as B
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

private var padlo = 0
private var preverb = 0

private fun p(pogoj: Boolean, opis: String) {
    preverb++
    if (pogoj) println("OK   $opis") else { padlo++; println("PADE $opis") }
}

private fun obstaja(x: Path) = Files.exists(x, LinkOption.NOFOLLOW_LINKS)

fun main() {
    val tmp = Files.createTempDirectory("usbtest")
    val H = 3_600_000L
    val usb = "/storage/ABCD-1234/Android/data/si.safeer.tv/cache"
    val zdaj = 1_800_000_000_000L

    // ---------------- XX3: kaj gre na disk
    p(B.smeNaDisk("WebView/Default/HTTP Cache"), "XX3 HTTP predpomnilnik sme na disk")
    p(!B.smeNaDisk("WebView"), "XX3 cela mapa WebView ne gre na disk")
    p(!B.smeNaDisk("WebView/Default/HTTP Cache/Code Cache"), "XX3 Code Cache ne gre na disk")
    p(!B.smeNaDisk("WebView/Default/GPUCache"), "XX3 GPUCache ne gre na disk")
    val dz = Files.createDirectories(tmp.resolve("zap/brskalnik-http"))
    p(B.nastaviZapornice(dz) && Files.isRegularFile(dz.resolve("Code Cache"), LinkOption.NOFOLLOW_LINKS) &&
        Files.isRegularFile(dz.resolve("GPUCache"), LinkOption.NOFOLLOW_LINKS), "XX3 zapornici Code Cache in GPUCache sta datoteki")
    p(!B.imaVsebino(dz), "XX5 samo zapornice niso dokaz pisanja")
    Files.write(dz.resolve("index"), byteArrayOf(1))
    p(B.imaVsebino(dz), "XX5 datoteka predpomnilnika je dokaz pisanja")

    // brez povezav na poti (prava povezava v tmp)
    val koren = Files.createDirectories(tmp.resolve("disk/Android/data/x/cache"))
    val drugam = Files.createDirectories(tmp.resolve("drugam"))
    Files.createSymbolicLink(koren.resolve("brskalnik-http"), drugam)
    p(!B.brezPovezav(koren, koren.resolve("brskalnik-http")), "XX3 povezava kot cilj zavrnjena")
    p(!B.brezPovezav(koren, koren.resolve("brskalnik-http/pod")), "XX3 povezava na poti zavrnjena")
    Files.createDirectories(koren.resolve("prava"))
    p(B.brezPovezav(koren, koren.resolve("prava")), "XX3 prava mapa sprejeta")
    p(!B.brezPovezav(koren, tmp.resolve("drugam")), "XX3 cilj izven korena zavrnjen")

    // ---------------- XX6: brisanje ne sledi povezavam
    val zunaj = Files.createDirectories(tmp.resolve("zunaj"))
    val dragoceno = Files.write(zunaj.resolve("dragoceno.txt"), "x".toByteArray())
    val m = Files.createDirectories(tmp.resolve("m/brskalnik-http"))
    Files.write(m.resolve("a"), byteArrayOf(1))
    Files.createDirectories(m.resolve("pod/globje"))
    Files.write(m.resolve("pod/globje/b"), byteArrayOf(2))
    Files.createSymbolicLink(m.resolve("povezava"), zunaj)
    Files.createSymbolicLink(m.resolve("pod/povezava2"), zunaj)
    val ok = B.pocistiVsebino(m)
    p(ok && Files.isDirectory(m) && Files.list(m).use { it.count() } == 0L, "XX6 vsebina izpraznjena, mapa ostane")
    p(Files.exists(dragoceno), "XX6 datoteka za povezavo NI izbrisana")
    val kotPovezava = Files.createSymbolicLink(tmp.resolve("kot-povezava"), zunaj)
    p(!B.pocistiVsebino(kotPovezava) && Files.exists(dragoceno), "XX6 mapa, ki je povezava, se ne prazni")
    p(B.odstraniPovezavo(kotPovezava) && !obstaja(kotPovezava) && Files.exists(dragoceno), "XX6 povezava odstranjena kot povezava")
    val prava = Files.createDirectories(tmp.resolve("prava-mapa"))
    Files.write(prava.resolve("f"), byteArrayOf(1))
    p(B.odstraniPovezavo(prava) && Files.exists(prava.resolve("f")), "XX6 prava mapa ni povezava in ostane")

    // ---------------- XX6: en priklop na proces
    val e = B.Enkrat()
    val prvih = AtomicInteger()
    val start = CountDownLatch(1)
    val niti = (1..16).map { Thread { start.await(); if (e.prvic()) prvih.incrementAndGet() } }
    niti.forEach { it.start() }; start.countDown(); niti.forEach { it.join() }
    p(prvih.get() == 1, "XX6 od 16 hkratnih klicev priklopi samo eden")
    p(!e.prvic(), "XX6 kasnejsi klic ne priklopi")

    // ---------------- XX4: rok in privzeto notranji
    run {
        val pociscena = AtomicInteger()
        val pr = B.Priprava { pociscena.incrementAndGet() }
        // Glavna nit v svoji niti: ce caka brez roka (stara logika), preizkus ne obvisi, ampak PADE.
        var rez = true
        val t0 = System.nanoTime()
        val glavna = Thread { rez = pr.pocakaj(300) }.apply { isDaemon = true; start(); join(2000) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        p(!glavna.isAlive && !rez && ms in 250..600, "XX4 brez odgovora diska glavna nit caka ~300 ms ($ms ms) in velja notranji")
        p(pociscena.get() == 1, "XX4 ob roku je stara povezava odstranjena (notranji)")
        var namescena = false
        p(!pr.namesti { namescena = true } && !namescena, "XX4 po roku ozadje povezave NE namesti")
    }
    run {
        val pr = B.Priprava { }
        val t = Thread { Thread.sleep(50); pr.namesti { } }
        t.start()
        p(pr.pocakaj(300), "XX4 hiter disk: povezava namescena pred WebView")
        t.join()
    }
    run {
        val pr = B.Priprava { }
        Thread { pr.koncajBrez() }.start()
        val t0 = System.nanoTime()
        val rez = pr.pocakaj(300)
        p(rez && !pr.odpovedano && (System.nanoTime() - t0) / 1_000_000 < 250, "XX4 odlocitev notranji: brez cakanja do roka")
    }
    p(B.ROK_PRIKLOPA_MS <= 300, "XX4 rok najvec 300 ms")

    // ---------------- XX5: stanje po casu
    val z = B.preberi("$usb|neuspel|${zdaj - 2 * H}")
    p(z == B.Zapis(usb, B.Izid.NEUSPEL, zdaj - 2 * H), "XX5 zapis s casom prebran")
    p(B.preberi(B.zapisi(B.Zapis("/a|b", B.Izid.USPEL, 5))) == B.Zapis("/a|b", B.Izid.USPEL, 5), "XX5 zapis/branje (| v poti)")
    p(B.preberi("$usb|neuspel") == B.Zapis(usb, B.Izid.NEUSPEL, 0), "XX5 stari zapis brez casa: cas 0")
    p(B.prejsnji(z, usb, zdaj) == false, "XX5 neuspel pred 2 h velja")
    p(B.prejsnji(B.Zapis(usb, B.Izid.NEUSPEL, zdaj - 25 * H), usb, zdaj) == null, "XX5 neuspel pred 25 h je potekel")
    p(B.prejsnji(B.preberi("$usb|neuspel"), usb, zdaj) == null, "XX5 stari neuspel (brez casa) je potekel")
    p(B.prejsnji(B.Zapis(usb, B.Izid.POSKUS, zdaj - H), usb, zdaj) == null, "XX5 poskus ni neuspeh")
    p(B.prejsnji(B.Zapis(usb, B.Izid.USPEL, 1), usb, zdaj) == true, "XX5 uspel ostane")
    p(B.prejsnji(B.Zapis("/drug", B.Izid.NEUSPEL, zdaj), usb, zdaj) == null, "XX5 neuspeh velja samo za isti disk")
    val poskus = B.Zapis(usb, B.Izid.POSKUS, zdaj - H)
    p(B.obZagonu(poskus, usb, null, zdaj) == poskus, "XX5 zagon brez sesutja (brez strani) poskusa ne spremeni v neuspel")
    p(B.obZagonu(poskus, usb, zdaj - 3 * H, zdaj) == poskus, "XX5 sesutje PRED poskusom ne steje")
    p(B.obZagonu(poskus, usb, zdaj - H / 2, zdaj)?.izid == B.Izid.NEUSPEL, "XX5 sesutje po poskusu: neuspel")
    p(!B.smemPotrditi(poskus, usb + "/brskalnik-http", false), "XX5 stran brez zapisa na disk ni uspeh")
    p(B.smemPotrditi(poskus, usb + "/brskalnik-http", true), "XX5 zapis na disk je uspeh")
    p(!B.smemPotrditi(B.Zapis(usb, B.Izid.NEUSPEL, zdaj), usb, true), "XX5 neuspel se ne potrdi")

    // ---------------- XX7: kaj izprazniti
    val stara = "$usb/brskalnik-http"
    p(B.zaPraznjenje(stara, emptyList(), null) == listOf(stara), "XX7 'Ta TV'/odklop: stara mapa na disku se izprazni")
    p(B.zaPraznjenje(null, listOf(stara), null) == listOf(stara), "XX7 zapomnjena (disk je bil izkljucen) se izprazni")
    p(B.zaPraznjenje(stara, emptyList(), "$stara/") == listOf(stara), "XX3 nov cilj se izprazni pred uporabo (enkrat)")
    p(B.zaPraznjenje("/data/user/0/si.safeer.tv/files", listOf("/", "$usb/../brskalnik-http", "/storage/X/Android/data/si.safeer.tv/cache"), null).isEmpty(),
        "XX7 tuje poti se nikoli ne praznijo")

    // ---------------- XX8: nakljucni dostop
    p(!B.nakljucnoDovolj(null, 100_000), "XX8 brez meritve diska: notranji")
    p(!B.nakljucnoDovolj(5_000_000, 100_000), "XX8 50x pocasnejsi mali dostop: notranji")
    p(B.nakljucnoDovolj(150_000, 100_000), "XX8 1,5x: disk")
    p(B.nakljucnoVelja(zdaj - H, zdaj) && !B.nakljucnoVelja(zdaj - 8 * 24 * H, zdaj) && !B.nakljucnoVelja(0, zdaj), "XX8 meritev velja 7 dni")
    val mer = Files.createDirectories(tmp.resolve("meritev"))
    val ns = B.meriNakljucno(mer.toFile(), n = 8)
    p(ns != null && ns > 0 && Files.list(mer).use { it.count() } == 0L, "XX8 meritev vrne cas in pospravi za sabo ($ns ns)")
    var u = 0L
    p(B.meriNakljucno(mer.toFile(), n = 8, rokNs = 10, ura = { u += 100; u }) == null, "XX8 meritev spostuje rok")
    Files.createSymbolicLink(mer.resolve(".safeer-nakljucno"), zunaj)
    p(B.meriNakljucno(mer.toFile(), n = 4) == null && Files.exists(dragoceno) && Files.list(zunaj).use { it.count() } == 1L,
        "XX8 meritev ne pise skozi povezavo")

    println("preverb: $preverb, padlo: $padlo")
    if (padlo > 0) exitProcess(1)
}
