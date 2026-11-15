// Popravki TV-2 (15. 11. 2026): P1/SL-S2 in nova najdba 1 (piskotki samo za cilj sam), P3/SL-S1 (deljeni zaslon po gostitelju
// in poti, seja sele po potrjenem MPD), P4 (laurl v linearnem casu, skupni rok branja), P5.1 (generacija), P8 (Nazaj po
// prekrivnem sloju), P10 (OK na ogledu, ubeg JS), P11 (piskotki po imenu, meja JSON). Cista pravila; klici v DashPrevzem,
// SiteProfile in TvKeyRouter so Android in tu NEPREVERJENI. Brez popravka (stara logika v pravilih) preizkus PADE.
//   tests/run_splet_tests.sh
package si.safeer.tv

import si.safeer.tv.DashVarnostPravila as D
import si.safeer.tv.ProfilStraniPravila as S
import si.safeer.tv.UsmerjevalnikTipkPravila as U
import kotlin.system.exitProcess

private var padlo = 0
private var preverb = 0

private fun p(pogoj: Boolean, opis: String) {
    preverb++
    if (pogoj) println("OK   $opis") else { padlo++; println("PADE $opis") }
}

/** Prejsnji extractLaurl (DashPrevzem 9a0a8ca8, vrstice 534-555) - samo kot kontrola enakosti in casa. */
private fun staraLaurl(mpdXml: String): String? {
    if (mpdXml.isEmpty()) return null
    val found = LinkedHashSet<String>()
    listOf(
        Regex("""(?is)<[\w.-]*:?laurl[^>]*>([^<]+)</"""),
        Regex("""(?i)(?:laurl|licenseUrl|license_url)\s*[="'>\s]+(https?://[^"'<\s]+)"""),
        Regex("""(?i)https?://[^\s"'<>]+(?:drmtoday|license-proxy-widevine|/widevine|/license)[^\s"'<>]*"""),
        Regex("""https?://[^\s"'<>]*(?:/lic[./]|lic\.)[^\s"'<>]*""")
    ).forEach { re ->
        re.findAll(mpdXml).forEach { m ->
            val raw = (m.groups.lastOrNull { it != null && it.value.startsWith("http", true) }?.value
                ?: m.groupValues.getOrNull(1)
                ?: "").trim()
            if (raw.startsWith("http", ignoreCase = true)) found.add(raw.trimEnd('"', '\'', '<', '>', ' '))
        }
    }
    return found.firstOrNull { it.contains("drmtoday", true) }
        ?: found.firstOrNull { it.contains("widevine", true) }
        ?: found.firstOrNull()
}

/** Izvede [delo] v loceni niti; true, ce je koncano v [ms]. Nit je demon, exitProcess jo ustavi. */
private fun vRoku(ms: Long, delo: () -> Unit): Boolean {
    val t = Thread { delo() }.also { it.isDaemon = true; it.start() }
    t.join(ms)
    return !t.isAlive
}

/** Dekodira niz JS v enojnih narekovajih (podmnozica, ki jo jsNiz proizvede). */
private fun jsDekodiraj(s: String): String? {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\'' || c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029') return null
        if (c != '\\') { sb.append(c); i++; continue }
        if (i + 1 >= s.length) return null
        when (val e = s[i + 1]) {
            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
            '\\', '\'', '"' -> sb.append(e)
            'u' -> { sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
            else -> return null
        }
        i += 2
    }
    return sb.toString()
}

fun main() {
    // --- P1/SL-S2: piskotki samo za cilj sam -------------------------------------------------------------------------
    val jar: (String) -> String? = { u ->
        when (D.gostitelj(u)) {
            "bank.test" -> "seja=TAJNO; pref=1"
            "napadalec.test" -> "napad=1"
            else -> null
        }
    }
    val izvor = "https://bank.test"
    val tuj = D.sestaviGlave("https://napadalec.test/a.mpd", mapOf("Accept" to "*/*", "Cookie" to "seja=TAJNO"), izvor, "UA", jar)
    p(tuj.values.none { "TAJNO" in it }, "P1: manifest na tujem gostitelju NE dobi piskotkov strani (je: ${tuj["Cookie"]})")
    p(tuj["Cookie"] == "napad=1", "P1: tuj gostitelj dobi samo svoje piskotke")
    p(tuj["Referer"] == "https://bank.test/" && tuj["Origin"] == "https://bank.test" && tuj["Accept"] == "*/*" && tuj["User-Agent"] == "UA", "P1: Referer/Origin samo izvor, druge glave ostanejo")
    val svoj = D.sestaviGlave("https://bank.test:443/v/a.mpd", emptyMap(), izvor, "UA", jar)
    p(svoj["Cookie"] == "seja=TAJNO; pref=1", "P1: manifest na gostitelju strani dobi njene piskotke (kot v brskalniku)")
    val http = D.sestaviGlave("http://bank.test/a.mpd", emptyMap(), izvor, "UA", jar)
    p(http["Cookie"] == null && http["Referer"] == null && !D.smeOmrezje("http://bank.test/a.mpd", izvor), "P1: http s strani https: brez piskotkov, brez Referer, brez prenosa")
    p(D.smeOmrezje("http://stara.test/a.mpd", "http://stara.test") && !D.smeOmrezje("http://drug.test/a.mpd", "http://stara.test"), "P1: http samo na strani http istega gostitelja")
    p(D.gostitelj("https://bank.test@napadalec.test/a.mpd") == "napadalec.test" && D.gostitelj("https://napadalec.test\\@bank.test/a.mpd") == null
        && D.gostitelj("HTTPS://Bank.Test./x") == "bank.test" && D.gostitelj("https://[::1]:8443/x") == "[::1]" && D.gostitelj("https://a b/x") == null
        && D.gostitelj("ftp://bank.test/") == null && D.gostitelj("https://bank.test:99999/") == null, "P1: razclenjevalnik gostitelja (userinfo, \\, velike crke, IPv6, vrata)")
    p(D.preusmeritev("https://bank.test/a/b.mpd", "/c.mpd") == "https://bank.test/c.mpd" && D.preusmeritev("https://bank.test/a", "https://napadalec.test/x") == "https://napadalec.test/x"
        && D.preusmeritev("https://bank.test/a", "javascript:x") == null && D.preusmeritev("https://bank.test/a", "") == null, "P1: skok preusmeritve se razresi, ne-http zavrnjen")
    val skok = D.sestaviGlave(D.preusmeritev("https://bank.test/r.mpd", "https://napadalec.test/x.mpd")!!, D.brezPiskotkov(svoj), izvor, "UA", jar)
    p(skok.values.none { "TAJNO" in it }, "P1: preusmeritev z gostitelja strani na tujega ne nese piskotkov strani")
    p(D.brezPrivzetihVrat("https://a.test:443/x:443/y") == "https://a.test/x:443/y" && D.brezPrivzetihVrat("http://a.test:443/x") == "http://a.test:443/x"
        && D.brezPrivzetihVrat("http://a.test:80?q") == "http://a.test?q", "P2: privzeta vrata samo v avtoriteti")

    // --- nova najdba 1: namig licence (widevine+license v naslovu) ------------------------------------------------------
    val namig = D.brezPiskotkov(mapOf("Cookie" to "seja=TAJNO", "X-Dt-Custom-Data" to "abc"))
    val lic = D.sestaviGlave("https://napadalec.test/widevine/license", namig, izvor, "UA", jar)
    p(lic.values.none { "TAJNO" in it } && lic["Cookie"] == "napad=1" && lic["X-Dt-Custom-Data"] == "abc", "Nova 1: licencni naslov dobi samo piskotke svojega izvora, ne strani, ki ga je podala")
    p(D.brezPiskotkov(mapOf("cookie" to "x", "COOKIE2" to "y", "A" to "b")) == mapOf("A" to "b"), "Nova 1: shranjene glave brez piskotkov (ne glede na velike crke)")

    // --- P3/SL-S1 ------------------------------------------------------------------------------------------------------
    p(S.deljenZaslon("https://192.168.1.20:8443/cast/screen/ab12/view?k=xyz") && S.deljenZaslon("https://safeer-hub.local:8443/cast/screen/a-b_1/view?k=1")
        && S.deljenZaslon("http://10.0.0.5/cast/screen/x/view?a=1&k=q") && S.deljenZaslon("https://127.0.0.1:8443/cast/screen/x/view?k=1#v"), "P3: pravi naslovi gledalca na srediscu v lokalnem omrezju")
    p(!S.deljenZaslon("https://evil.example/cast/screen/x/view") && !S.deljenZaslon("https://evil.example/cast/screen/x/view?k=1")
        && !S.deljenZaslon("https://evil.example/?u=/cast/screen/x/view&k=1") && !S.deljenZaslon("https://evil.example/#/cast/screen/x/view?k=1"), "P3: replaceState na tujem gostitelju ne skrije vrstice")
    p(!S.deljenZaslon("https://192.168.1.20/cast/screen/x/view") && !S.deljenZaslon("https://192.168.1.20/cast/screen/x/stream?k=1")
        && !S.deljenZaslon("https://192.168.1.20/a/cast/screen/x/view?k=1") && !S.deljenZaslon("https://192.168.1.20/cast/screen/../view?k=1")
        && !S.deljenZaslon("https://192.168.1.20/cast/screen/x/view/y?k=1"), "P3: tocna pot in kljuc k")
    p(!S.deljenZaslon("https://2130706433/cast/screen/x/view?k=1") && !S.deljenZaslon("https://192.168.1.20.evil.example/cast/screen/x/view?k=1")
        && !S.deljenZaslon("https://192.168.1.20@evil.example/cast/screen/x/view?k=1") && !S.deljenZaslon("https://evil.example\\@192.168.1.20/cast/screen/x/view?k=1")
        && !S.deljenZaslon("https://8.8.8.8/cast/screen/x/view?k=1") && !S.deljenZaslon("https://010.1.1.1/cast/screen/x/view?k=1") && !S.deljenZaslon(null), "P3: javni, stevilski in zamaskirani gostitelji ne")
    p(!D.imaSejo("https://x.test/a.mpd", potrjen = false, prepuscen = false), "P3: zahtevek .mpd brez potrjenega MPD ni seja (vrstica ostane)")
    p(D.imaSejo("https://x.test/a.mpd", potrjen = true, prepuscen = false) && !D.imaSejo("https://x.test/a.mpd", true, true) && !D.imaSejo("", true, false), "P3: potrjen MPD je seja, prepuscen ni")

    // --- P4 --------------------------------------------------------------------------------------------------------------
    val vzorci = listOf(
        """<MPD><ContentProtection><dashif:Laurl>https://lic.example.com/wv?t=1</dashif:Laurl></ContentProtection></MPD>""",
        """<MPD><ms:laurl licenseUrl="https://pr.example.com/rightsmanager.asmx?x=1"/></MPD>""",
        """<MPD><ContentProtection><clearkey:Laurl Lic_type="EME-1.0">https://ck.example.com/lic</clearkey:Laurl></ContentProtection></MPD>""",
        """{"license_url": "https://lic.drmtoday.com/license-proxy-widevine/cenc/?specConform=true"}""",
        """<MPD><BaseURL>https://cdn.example.com/v/</BaseURL><x a='https://wv.example.com/widevine/getlicense'/></MPD>""",
        """<MPD>laurl = 'https://a.example/lic.php' LAURL>"HTTPS://B.EXAMPLE/X" </MPD>""",
        """<MPD><BaseURL>https://cdn.example.com/v/</BaseURL></MPD>""",
        """<MPD>https://x.example/lic/abc http://y.example/foo/license?q=1 https://z.example/a/lic.bin</MPD>""",
        """<a:laurl>  http://plain.example/license  </a:laurl><b:laurl></b:laurl><c:laurl>ftp://x</c:laurl>""",
        """<MPD><laurl x="1">https://one.example/a</laurl>https://two.example/drmtoday/x<laurl>https://three.example/widevine</laurl></MPD>""",
        """licenseUrl=>https://q.example/w?a="b" license_url=https://r.example/s<x>""",
        "", "laurl", "<laurl>", "https://", "<laurl>https://x.example/a<"
    )
    p(vzorci.all { D.najdiLaurl(it) == staraLaurl(it) }, "P4: linearno iskanje laurl da enak rezultat kot prejsnji regexi (${vzorci.size} vzorcev; razlike: ${vzorci.filter { D.najdiLaurl(it) != staraLaurl(it) }.map { it.take(30) }})")
    // nakljucni nizi iz delcev, ki jih regexi locujejo (fiksno seme, ponovljivo)
    val delci = listOf("<", ">", "</", "laurl", "LaUrl", "licenseUrl", "license_url", ":", "x", ".", "-", "=", "\"", "'", " ", "\n",
        "http://", "https://", "HTTPS://", "a.test", "/license", "/widevine", "drmtoday", "license-proxy-widevine", "/lic/", "lic.", "/lic.", "?q=1")
    val rnd = java.util.Random(20261115L)
    val nakljucni = List(5000) { buildString { repeat(1 + rnd.nextInt(24)) { append(delci[rnd.nextInt(delci.size)]) } } }
    val razlike = nakljucni.filter { D.najdiLaurl(it) != staraLaurl(it) }
    p(razlike.isEmpty(), "P4: enak rezultat tudi na 5000 nakljucnih nizih (razlik ${razlike.size}: ${razlike.take(3)})")
    p(D.najdiLaurl("""<x:laurl>http://plain.example/license</x:laurl>""", samoHttps = true) == null
        && D.najdiLaurl("""<x:laurl>http://a.example/license</x:laurl><y:laurl>https://b.example/l</y:laurl>""", samoHttps = true) == "https://b.example/l", "Nova 3: licenca iz MPD samo prek https")
    val n = 270 * 1024
    val zlonamerni = listOf(
        "laurl" + " ".repeat(n),
        "<laurl".repeat(n / 6),
        "http://a".repeat(n / 8),
        "licenseUrl=" + "\"".repeat(n),
        "<x:" + "laurl".repeat(n / 5)
    )
    val zac = System.nanoTime()
    var rez = 0
    val koncano = vRoku(5_000) { zlonamerni.forEach { if (D.najdiLaurl(it) == null) rez++ } }
    val ms = (System.nanoTime() - zac) / 1_000_000
    p(koncano && ms < 1000, "P4: 5 zlonamernih MPD po 270 KiB obdelanih v ${ms} ms (< 1000 ms)")
    p(vRoku(2_000) { staraLaurl(zlonamerni[0]) }.not(), "P4 kontrola: prejsnji regex na istem nizu ne konca v 2 s (vzorec je res zlonameren)")
    // skupni rok: streznik kaplja po en bajt, ura tece 1 s na branje -> branje se ustavi ob roku
    var ura = 0L
    var prebrano = 0
    val kaplja = object : java.io.InputStream() {
        override fun read(): Int { prebrano++; ura += 1_000_000_000L; return 'x'.code }
        override fun read(b: ByteArray, off: Int, len: Int): Int { b[off] = read().toByte(); return 1 }
    }
    val telo = D.beriZRokom(kaplja, 256 * 1024, 15_000_000_000L) { ura }
    p(telo == null && prebrano <= 16, "P4: skupni rok ustavi pocasen streznik (prebrano $prebrano bajtov, telo ${telo?.size})")
    p(D.beriZRokom("abc".byteInputStream(), 256 * 1024, Long.MAX_VALUE / 2) { 0L }?.decodeToString() == "abc"
        && D.beriZRokom(ByteArray(300_000).inputStream(), 256 * 1024, Long.MAX_VALUE / 2) { 0L }?.size == 256 * 1024, "P4: navadno telo prebrano, meja 256 KiB")

    // --- P5.1: generacija ----------------------------------------------------------------------------------------------
    // Model DashPrevzem: zahtevek zajame generacijo; reset()/naStrani/nov tok jo povecajo; objava preveri rezultatVelja.
    var gen = 0L
    var mpdLaurl = ""
    val genA = gen                 // stran A (napadalec) zahteva pocasen manifest
    gen++                          // reset() ob odhodu
    gen++                          // naStrani(B)
    val genB = gen                 // stran B (prijavljen uporabnik) svoj manifest
    if (D.rezultatVelja(genA, gen)) mpdLaurl = "https://napadalec.test/lic"   // pocasen rezultat A pride zdaj
    p(mpdLaurl.isEmpty(), "P5.1: zastarel rezultat strani A ne nastavi licence za tok strani B")
    if (D.rezultatVelja(genB, gen)) mpdLaurl = "https://lic.bank.test/wv"
    p(mpdLaurl == "https://lic.bank.test/wv", "P5.1: rezultat trenutnega toka velja")

    // --- P8: Nazaj po prekrivnem sloju ---------------------------------------------------------------------------------
    // Model TvKeyRouter (vrstice 54-67): vrne "pogoltnjen", "sloj" (Nazaj zapre sloj) ali "naprej" (handleBrowserBack).
    var zast = false
    fun nazaj(dol: Boolean, pon: Int, slojViden: Boolean): String {
        if (zast) { val r = U.nazajPoSloju(zast, dol, pon); zast = r.zastavica; if (r.pogoltni) return "pogoltnjen" }
        if (slojViden) { if (dol) zast = true; return "sloj" }
        return if (dol) "naprej" else "spust"
    }
    p(nazaj(true, 0, true) == "sloj" && nazaj(true, 1, false) == "pogoltnjen" && nazaj(false, 0, false) == "pogoltnjen" && !zast, "P8: Nazaj zapre sloj, ponovitve in spust pogoltnjeni, zastavica pociscena")
    zast = false
    nazaj(true, 0, true)           // DOWN zapre sloj; spust se izgubi (ugasnjen zaslon, nativno predvajanje)
    p(nazaj(true, 0, false) == "naprej", "P8: po izgubljenem spustu naslednji Nazaj NI pogoltnjen")
    p(!zast && nazaj(false, 0, false) == "spust", "P8: zastavica po novem pritisku ne ostane")

    // --- P10 -------------------------------------------------------------------------------------------------------------
    var zadnji = 0L
    val dejanja = mutableListOf<S.OgledOk>()
    fun ok(dol: Boolean, pon: Int, t: Long) { val r = S.okNaOgledu(dol, pon, t, zadnji); zadnji = r.zadnjiKlik; dejanja += r.dejanje }
    ok(true, 0, 10_000); for (k in 1..8) ok(true, k, 10_000L + 50 * k); ok(false, 0, 10_500)
    p(dejanja.first() == S.OgledOk.PREDVAJAJ && dejanja.drop(1).all { it == S.OgledOk.NIC }, "P10: drzan OK na ogledu: en premor/predvajanje, ponovitve in spust brez preklopa celozaslonskega ($dejanja)")
    dejanja.clear(); zadnji = 0L
    ok(true, 0, 20_000); ok(true, 0, 20_200)
    p(dejanja == listOf(S.OgledOk.PREDVAJAJ, S.OgledOk.CELOZASLONSKO), "P10: dva hitra pritiska = celozaslonski nacin (kot prej)")
    val iskanja = listOf("a\nb", "x\r\ny", "\u2028\u2029", "o'reilly \\ \"q\"", "</script><b>", "\u0000\u001f\u007f", "\u010d\u0161\u017e \u65e5\u672c")
    p(iskanja.all { q -> val e = S.jsNiz(q); e.none { it < ' ' || it == '\u2028' || it == '\u2029' || it == '\u007f' } && jsDekodiraj(e) == q },
        "P10: iskanje z novo vrstico, U+2028/9 in narekovaji je veljaven niz JS z enako vsebino")

    // --- P11 -------------------------------------------------------------------------------------------------------------
    p(D.zdruziPiskotke("a=1; b=2", "a=3; c=4") == "a=3; b=2; c=4" && D.zdruziPiskotke("", "x=1") == "x=1" && D.zdruziPiskotke("a=1", "a=1") == "a=1", "P11: piskotki po imenu, zadnja vrednost zmaga")
    p(D.jsonGlavDovoljen("{\"a\":\"b\"}") && !D.jsonGlavDovoljen("{\"a\":\"" + "x".repeat(D.MAKS_JSON_GLAV) + "\"}") && !D.jsonGlavDovoljen(" "), "P11: JSON glav z mostu omejen na 16 KiB")

    // --- DDD1/DDD2 (slepi pregled DDD, 15. 11. 2026) --------------------------------------------------------------------------
    p(!D.licencneGlaveSmejo("https://license.a.test/lic", "https://zlobnez.test/widevine/license", "https://a.test") &&
        D.licencneGlaveSmejo("https://license.a.test/lic", "https://license.a.test/other", "https://a.test"),
        "DDD1: glave strani samo na gostitelja licence, ki jo je stran napovedala")
    p(!D.licencneGlaveSmejo("", "https://zlobnez.test/license", "https://a.test") && D.licencneGlaveSmejo("", "https://a.test/license", "https://a.test"),
        "DDD1: brez napovedi samo na gostitelja izvora strani")
    val obcutljive = mapOf("Authorization" to "Bearer t", "x-dt-auth-token" to "t", "dt-custom-data" to "d", "X-Api-Key" to "k", "Accept" to "*/*", "User-Agent" to "ua")
    val navzkrizno = D.glaveZaSkok(obcutljive, "https://cdn.a.test/x.mpd", "https://tretji.test/y.mpd")
    p(navzkrizno.keys.toSet() == setOf("Accept", "User-Agent"), "DDD2: navzkrizna preusmeritev brez Authorization, zetonov in DT glav (${navzkrizno.keys})")
    p(D.glaveZaSkok(obcutljive, "https://cdn.a.test/x.mpd", "https://cdn.a.test/z.mpd") == obcutljive, "DDD2: isti gostitelj obdrzi vse glave")
    val dp = java.io.File("src/main/kotlin/si/safeer/tv/tv/DashPrevzem.kt").readText()
    p(dp.contains("pageLicHeaders = emptyMap()") && dp.substringAfter("fun naStrani(").substringBefore("fun izvorStrani").contains("pageLicUrl = \"\""),
        "DDD1: naStrani ob novem izvoru pocisti licenco in glave strani")

    println("PopravkiTv2Test: $preverb preverb, $padlo padlo")
    if (padlo > 0) exitProcess(1)
}
