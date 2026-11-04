// Dokaz enakosti (4. 11. 2026): AbpVzorec (brez regexa) se mora ujemati NATANKO tako kot stari Pattern iz toRegex().
// Primerja pravilo za pravilom in celotne odlocitve FilterSet (stari = compile(lines, legacyRegex = true)).
//   java -cp ... com.safeer.threatfeed.AbpVzorecTestKt [pot/do/easylist.txt]
// Brez poti tece na sinteticnih robnih pravilih; s polnim EasyList (SAFEER_EASYLIST v run_abp_vzorec_tests.sh) na vseh.
package com.safeer.threatfeed

import java.io.File
import java.util.Random
import kotlin.system.exitProcess

private val ROBNA_PRAVILA = """
||sub.domena.example^
||domena.example/pot^
||domena.example^*.js
||cdn.example/*/oglas^
||vrata.example:8443^
||x.example^|
||*.example^
|||cudno
||
|*|
**
|https://tocno.example/
|http://
|ws://
|*foo
.swf|
*.gif|
a*b*c|
^foo^bar^|
*ozadje*
*/zacetek
konec/*
^lociloz^
^
/a^b^c
%2fkodirano%2f
:8080/
?ads=
&utm_
/ADS/Velike
-oglas-^
_reklama_
.example^
.example|
http://*
@@||izjema.example^${'$'}script
@@/izjema/*^${'$'}image
||imp.example^${'$'}important
/sledi.${'$'}third-party
/prva/${'$'}~third-party
/dom/${'$'}domain=a.example|~b.a.example
/mc/${'$'}match-case
/tip/${'$'}image,script
/brez/${'$'}~image
@@||celastran.example^${'$'}document
/^https?:\/\/[a-z]+\.re\.example\//${'$'}script
""".trimIndent().lines()

private val ROBNI_NASLOVI = listOf(
    "https://sub.domena.example/", "https://a.sub.domena.example/x", "https://xsub.domena.example/", "https://domena.example",
    "https://domena.example/pot", "https://domena.example/pot/", "https://domena.example/potx", "https://adomena.example/pot",
    "https://domena.example.evil/pot", "https://domena.example:8080/pot", "https://user:pw@domena.example/pot",
    "https://domena.example/a.js", "https://cdn.example/x/y/oglas", "https://cdn.example/oglas?", "https://vrata.example:8443/",
    "https://vrata.example:8443", "https://vrata.example:844/", "https://x.example", "https://x.example/", "https://cudno/x",
    "https://tocno.example/", "http://tocno.example/", "HTTPS://TOCNO.EXAMPLE/", "ws://x.example/", "wss://x.example/",
    "https://x.example/file.swf", "https://x.example/file.swf?x=1", "https://x.example/file.SWF", "https://x.example/foo",
    "https://x.example/a.gif", "https://x.example/abc", "https://x.example/foo/bar/", "https://x.example/foo/bar",
    "https://x.example/ozadje", "https://x.example/zacetek", "https://x.example/konec/", "https://x.example/^lociloz^",
    "https://x.example/a/b/c", "https://x.example/a.b.c", "https://x.example/a%2fkodirano%2fb", "https://x.example:8080/",
    "https://x.example/?ads=1", "https://x.example/p?q=1&utm_source=x", "https://x.example/ads/velike", "https://x.example/-oglas-",
    "https://x.example/-oglas-x", "https://x.example/_reklama_", "https://www.example/", "https://a.example", "http://x/",
    "https://izjema.example/s.js", "https://x.example/izjema/a/", "https://imp.example/", "https://x.example/sledi.js",
    "https://x.example/prva/", "https://x.example/dom/", "https://x.example/mc/", "https://x.example/tip/", "https://x.example/brez/",
    "https://celastran.example/", "https://abc.re.example/x.js", "data:text/html,foo", "about:blank", "", "x", "://x.example/",
    "1http://x.example/", "h+t.p://x.example/", "https://[::1]:8080/foo", "https://x.example/a#frag^", "https://x.example/%20^",
    "https://x.example/žoga/ozadje", "https://x.example/ñ^", "https://x.example/a~b",
    // ne navadni (konci vrstic, nadomestni pari): natancna pot
    "https://x.example/file.swf\n", "https://x.example/file.swf\r\n", "https://x.example/file.swf\r", "https://x.example/file.swf\n\n",
    "https://x.example/file.swf\u2028", "https://x.example/file.swf\u0085", "https://x.example/a\nb/ozadje", "https://x.example/ozadje\nx",
    "https://x.example/\uD83D\uDE00/foo", "https://x.example/foo\uD83D\uDE00bar", "https://x.example/foo\uD83D\uDE00", "https://x.example/\uDE00foo",
    "https://x.example/\uD83Dfoo", "\nhttps://x.example/", "https://x.example/a\r\nb", "https://sub.domena.example\n",
    "https://domena.example/pot\n", "https://x.example/foo\uD83D\uDE00bar\n", "https://x.example/a.gif\r\n", "https://x.example/abc\n",
)

private class Par(val nov: NetworkFilter, val star: NetworkFilter)

fun main(args: Array<String>) {
    val polni = args.firstOrNull()?.let { File(it) }?.takeIf { it.isFile }
    val vrstice = (polni?.readLines() ?: emptyList()) + ROBNA_PRAVILA
    println("pravila: ${vrstice.size} vrstic (${if (polni != null) "poln seznam + robna" else "samo robna - poln seznam ni podan"})")
    var napake = 0
    fun napaka(s: String) { napake++; if (napake <= 30) println("RAZLIKA $s") }

    // 1) Razclenitev pravilo za pravilom: isti rezultat (null/ne null), isti zeton, isti indeks.
    val pari = ArrayList<Par>()
    var prejPattern = 0
    var zdajPattern = 0
    var regexPravil = 0
    val budgetNov = intArrayOf(2000)
    val budgetStar = intArrayOf(2000)
    for (v in vrstice) {
        val t = v.trim()
        if (t.isEmpty() || t.startsWith("!") || t.startsWith("[") || FilterListEngine.parseCosmetic(t) != null) continue
        val nov = FilterListEngine.parse(t, budgetNov, null, false)
        val star = FilterListEngine.parse(t, budgetStar, null, true)
        if ((nov == null) != (star == null)) { napaka("razclenitev '$t': nov=${nov != null} star=${star != null}"); continue }
        if (nov == null || star == null) continue
        if (nov.token != star.token || nov.hostAnchor != star.hostAnchor || nov.typeMask != star.typeMask || nov.thirdParty != star.thirdParty ||
            nov.domains != star.domains || nov.excludedDomains != star.excludedDomains || nov.important != star.important || nov.exception != star.exception)
            napaka("lastnosti '$t'")
        if (star.pattern != null) prejPattern++
        if (nov.pattern != null) zdajPattern++
        val telo = t.removePrefix("@@")
        val jeRegex = telo.length > 2 && telo.startsWith("/") && (telo.endsWith("/") || telo.contains("/$"))
        if (nov.pattern != null && (jeRegex || t.any { it.isSurrogate() })) regexPravil++
        if (nov.abpBody != null && star.pattern != null) pari.add(Par(nov, star))
    }
    println("Pattern objektov: prej $prejPattern, zdaj $zdajPattern (od tega /regex/ ali z nadomestnimi znaki $regexPravil); brez Pattern zdaj ${pari.size} pravil")
    if (zdajPattern != regexPravil) napaka("Pattern ostal pri navadnem pravilu")

    // 2) Deterministicen korpus naslovov: gostitelji seznama, naslovi iz pravil samih (da se res ujemajo), robni primeri.
    val rnd = Random(4112026L)
    val gostitelji = ArrayList<String>()
    for (v in vrstice) {
        val t = v.trim()
        if (t.startsWith("||")) {
            val h = t.substring(2).substringBefore('^').substringBefore('/').substringBefore('$').substringBefore('*').substringBefore(':')
            if (h.contains('.') && h.all { it.isLetterOrDigit() || it == '.' || it == '-' }) gostitelji.add(h)
        }
    }
    if (gostitelji.isEmpty()) gostitelji.add("x.example")
    gostitelji.addAll(listOf("example.com", "www.news.example", "cdn.example.org", "a.example"))
    fun gost() = gostitelji[rnd.nextInt(gostitelji.size)]
    val zvezde = listOf("", "x", "abc/def", "123", "-", "a.b", "q=1&r=2", "/")
    val locila = listOf("/", "?", "&", "", ":", "=", "#", "!", ".")
    val poti = listOf("/", "/index.html", "/js/app.js", "/img/a.png", "/ads/banner.gif", "/p?q=1&utm_source=x", "/a/b/c?d=e#f", "/%20x%2Fy")
    fun izPravila(t: String): String {
        var p = t.removePrefix("@@")
        if (p.startsWith("/") && p.length > 2 && (p.endsWith("/") || p.contains("/$"))) return "https://${gost()}/re"
        val d = p.lastIndexOf('$'); if (d > 0) p = p.substring(0, d)
        val sb = StringBuilder()
        var telo = p
        when {
            telo.startsWith("||") -> { sb.append(if (rnd.nextInt(5) == 0) "http://" else "https://"); if (rnd.nextBoolean()) sb.append("sub."); telo = telo.substring(2) }
            telo.startsWith("|") -> telo = telo.substring(1)
            else -> { sb.append("https://").append(gost()); if (!telo.startsWith("/") && !telo.startsWith(".") && rnd.nextBoolean()) sb.append('/') }
        }
        val konec = telo.endsWith("|"); if (konec) telo = telo.dropLast(1)
        for (c in telo) when (c) {
            '*' -> sb.append(zvezde[rnd.nextInt(zvezde.size)])
            '^' -> sb.append(locila[rnd.nextInt(locila.size)])
            else -> sb.append(if (rnd.nextInt(12) == 0) c.uppercaseChar() else c)
        }
        if (!konec && rnd.nextBoolean()) sb.append(poti[rnd.nextInt(poti.size)])
        return sb.toString()
    }
    val korpus = LinkedHashSet<String>(ROBNI_NASLOVI)
    val pravilaZaNaslove = vrstice.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("!") && !it.startsWith("[") && !it.contains("##") && !it.contains("#@#") }
    val ciljNavadnih = 12000
    while (korpus.size < ciljNavadnih) {
        korpus.add(if (rnd.nextInt(4) == 0) "https://${gost()}${poti[rnd.nextInt(poti.size)]}" else izPravila(pravilaZaNaslove[rnd.nextInt(pravilaZaNaslove.size)]))
    }
    val vstavki = listOf("\n", "\r\n", "\r", "\u2028", "\u0085", "\uD83D\uDE00", "\uDE00", "\uD83D")
    val nenavadni = ArrayList<String>()
    for ((i, u) in korpus.withIndex()) {
        if (i % 12 != 0) continue
        val v = vstavki[rnd.nextInt(vstavki.size)]
        nenavadni.add(when (rnd.nextInt(3)) { 0 -> u + v; 1 -> { val k = rnd.nextInt(u.length + 1); u.substring(0, k) + v + u.substring(k) }; else -> v + u })
    }
    korpus.addAll(nenavadni)
    val naslovi = korpus.toList()
    println("korpus: ${naslovi.size} naslovov (${naslovi.count { !AbpVzorec.navaden(it.lowercase()) }} ne navadnih)")

    // 3) Pravilo za pravilo: za vsak naslov vsa pravila, ki jih indeks lahko obisce (zeton/brez zetona), plus polni
    //    kriz za podmnozico naslovov (vsa pravila x naslov), da indeks ne skrije razlike.
    val poZetonu = HashMap<String, MutableList<Par>>()
    val brezZetona = ArrayList<Par>()
    for (p in pari) if (p.nov.token != null) poZetonu.getOrPut(p.nov.token) { ArrayList() }.add(p) else brezZetona.add(p)
    var primerjav = 0L
    var ujemanj = 0L
    fun primerjaj(p: Par, u: String, lower: String, plain: Boolean) {
        val a = p.star.pattern!!.matcher(lower).find()
        val b = AbpVzorec.ujema(p.nov.abpBody!!, p.nov.abpFlags, lower, plain)
        primerjav++
        if (a) ujemanj++
        if (a != b) napaka("pravilo '${p.nov.text}' naslov ${u.replace("\n", "\\n").replace("\r", "\\r")}: regex=$a nov=$b")
    }
    for (u in naslovi) {
        val lower = u.lowercase()
        val plain = AbpVzorec.navaden(lower)
        val videni = HashSet<String>()
        for (tok in FilterListEngine.tokens(lower)) if (videni.add(tok)) poZetonu[tok]?.forEach { primerjaj(it, u, lower, plain) }
        brezZetona.forEach { primerjaj(it, u, lower, plain) }
    }
    val kriz = naslovi.filterIndexed { i, u -> i < ROBNI_NASLOVI.size || i % 40 == 0 || !AbpVzorec.navaden(u.lowercase()) && i % 4 == 0 }
    for (u in kriz) {
        val lower = u.lowercase()
        val plain = AbpVzorec.navaden(lower)
        for (p in pari) primerjaj(p, u, lower, plain)
    }
    println("pravilo x naslov: $primerjav primerjav, $ujemanj ujemanj (polni kriz: ${kriz.size} naslovov x ${pari.size} pravil)")
    if (ujemanj < 100) napaka("premalo ujemanj ($ujemanj): korpus ne preizkusa ujemalnika")

    // 4) Celotne odlocitve: blokiraj/dovoli, katero pravilo, izjeme in izjeme cele strani.
    val nov = FilterListEngine.compile(vrstice, false)
    val star = FilterListEngine.compile(vrstice, true)
    if (nov.size != star.size || nov.skipped != star.skipped) napaka("velikost: nov ${nov.size}/${nov.skipped} star ${star.size}/${star.skipped}")
    val tipi = ResourceType.values()
    val strani = listOf(null, "https://www.news.example/", "https://a.example/", "https://example.com/clanek") + gostitelji.take(50).map { "https://$it/" }
    val zahteve = naslovi.mapIndexed { i, u -> FilterRequest(u, strani[i % strani.size], tipi[(i / 3) % tipi.size]) }
    var blokiranih = 0
    for (r in zahteve) {
        val a = star.decide(r)
        val b = nov.decide(r)
        if ((a == null) != (b == null) || a != null && b != null && (a.block != b.block || a.filter?.text != b.filter?.text))
            napaka("odlocitev ${r.url}: star=${a?.block}/${a?.filter?.text} nov=${b?.block}/${b?.filter?.text}")
        if (a?.block == true) blokiranih++
        if (star.exceptionFor(r)?.text != nov.exceptionFor(r)?.text) napaka("izjema ${r.url}")
        if (r.type == ResourceType.DOCUMENT && star.isPageAllowed(r.url) != nov.isPageAllowed(r.url)) napaka("cela stran ${r.url}")
    }
    println("odlocitve: ${zahteve.size} zahtev, $blokiranih blokiranih (star)")

    // 5) Cas ujemanja: izmenicno, 5 ponovitev, mediana (ms za vse zahteve).
    fun tek(set: FilterSet): Long { val t = System.nanoTime(); var x = 0; for (r in zahteve) if (set.decide(r)?.block == true) x++; if (x < 0) println(x); return System.nanoTime() - t }
    repeat(2) { tek(star); tek(nov) }
    val cs = ArrayList<Long>(); val cn = ArrayList<Long>()
    repeat(5) { cs.add(tek(star)); cn.add(tek(nov)) }
    cs.sort(); cn.sort()
    println("cas decide() za ${zahteve.size} zahtev, mediana 5 izmenicnih tekov: regex ${cs[2] / 1_000_000.0} ms, nov ${cn[2] / 1_000_000.0} ms " +
            "(na zahtevo: regex ${cs[2] / zahteve.size / 1000.0} us, nov ${cn[2] / zahteve.size / 1000.0} us)")

    println(if (napake == 0) "AbpVzorec: 0 razlik - OK" else "AbpVzorec: $napake razlik - PADLO")
    if (napake > 0) exitProcess(1)
}
