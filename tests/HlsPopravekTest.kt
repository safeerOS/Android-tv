package si.safeer.tv.os

/** Preizkus [HlsPopravek]: seznam HLS z neveljavnim datumom se da predvajati, veljaven ostane nedotaknjen. */
fun main() {
    // Tak seznam je 5. 10. 2026 na televizorju dal »ParserException: Invalid date/time format« in crn zaslon.
    val slab = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:1201\n" +
        "#EXT-X-PROGRAM-DATE-TIME:10/05/2026T15:35:59.070-04:00\n#EXTINF:6.006,\nkos1201.ts\n" +
        "#EXT-X-PROGRAM-DATE-TIME:10/05/2026T15:36:05.076-04:00\n#EXTINF:6.006,\nkos1202.ts\n"
    val popravljen = HlsPopravek.pocisti(slab)
    check(popravljen != null) { "seznam z neveljavnim datumom ni bil popravljen" }
    check(!popravljen.contains("PROGRAM-DATE-TIME")) { "vrstica z neveljavnim datumom je ostala" }
    check(popravljen.contains("#EXTINF:6.006,\nkos1201.ts") && popravljen.contains("kos1202.ts")) { "kosi prenosa so se izgubili" }
    check(popravljen.startsWith("#EXTM3U\n#EXT-X-VERSION:3")) { "glava seznama se je spremenila" }

    // Veljavni datumi (oblike, ki jih Media3 sprejme) ostanejo: seznama ne diramo.
    val dober = "#EXTM3U\n#EXT-X-PROGRAM-DATE-TIME:2026-10-05T15:35:59.070-04:00\n#EXTINF:6,\na.ts\n" +
        "#EXT-X-PROGRAM-DATE-TIME:2026-10-05T19:36:05Z\n#EXTINF:6,\nb.ts\n"
    check(HlsPopravek.pocisti(dober) == null) { "veljaven seznam je bil spremenjen" }
    for (d in listOf("2026-10-05T15:35:59Z", "2026-10-05T15:35:59.070-04:00", "2026-10-05t15:35:59,5+0200", "2026-10-05T15:35:59"))
        check(HlsPopravek.veljavenDatum(d)) { "veljaven datum zavrnjen: $d" }
    for (d in listOf("10/05/2026T15:35:59.070-04:00", "2026-10-05", "05.10.2026 15:35", "", "vceraj"))
        check(!HlsPopravek.veljavenDatum(d)) { "neveljaven datum sprejet: $d" }

    // Meane veljavnih in neveljavnih vrstic: izpade samo neveljavna.
    val mesan = "#EXTM3U\n#EXT-X-PROGRAM-DATE-TIME:2026-10-05T15:35:59Z\n#EXTINF:6,\na.ts\n#EXT-X-PROGRAM-DATE-TIME:5/10/26 15:36\n#EXTINF:6,\nb.ts\n"
    val m = HlsPopravek.pocisti(mesan)!!
    check(m.contains("2026-10-05T15:35:59Z") && !m.contains("5/10/26")) { "mesan seznam ni pravilno popravljen" }

    // Casovni obseg z neveljavnim datumom izpade, z veljavnim ostane.
    val obseg = "#EXTM3U\n#EXT-X-DATERANGE:ID=\"a\",START-DATE=\"10/05/2026T15:00:00\",DURATION=30\n" +
        "#EXT-X-DATERANGE:ID=\"b\",START-DATE=\"2026-10-05T15:00:00Z\",DURATION=30\n#EXTINF:6,\na.ts\n"
    val o = HlsPopravek.pocisti(obseg)!!
    check(!o.contains("ID=\"a\"") && o.contains("ID=\"b\"")) { "casovni obseg ni pravilno popravljen" }

    // Kar ni seznam HLS (kos prenosa, stran z napako), ostane, kot je.
    check(HlsPopravek.pocisti("<html>404</html>") == null)
    check(HlsPopravek.pocisti("#EXT-X-PROGRAM-DATE-TIME:10/05/2026T15:35:59") == null) { "besedilo brez glave #EXTM3U ni seznam" }
    // Oznaka vrstnega reda bajtov in konci vrstic CRLF.
    val crlf = "\uFEFF#EXTM3U\r\n#EXT-X-PROGRAM-DATE-TIME:10/05/2026T15:35:59\r\n#EXTINF:6,\r\na.ts\r\n"
    val c = HlsPopravek.pocisti(crlf)!!
    check(!c.contains("PROGRAM-DATE-TIME") && c.contains("a.ts")) { "seznam s CRLF ni popravljen" }
    println("HlsPopravekTest: OK")
}
