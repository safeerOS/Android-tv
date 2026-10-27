package si.safeer.tv.os

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.system.exitProcess
import si.safeer.tv.os.UsenetPonudnikPravila as P

// Preizkus pravil ponudnika Usenet: (1) skupni primeri iz Python reference (isti vhod -> ista odlocitev),
// (2) vedenje, ki ga primeri ne pokrijejo (zapah, omejitev, meja racuna, TLS, shramba brez gesla).

private var napak = 0

private fun preveriEnako(ime: String, pricakovano: Any?, dejansko: Any?) {
    if (pricakovano == dejansko) println("OK     $ime")
    else { napak++; println("NAPAKA $ime: pricakovano <$pricakovano>, dobljeno <$dejansko>") }
}

private fun izid(f: () -> Any?): String = try { f(); "OK" } catch (e: P.NeveljavenVnos) { e.message ?: "" }

private fun napakaPonudnika(f: () -> Any?): String? = try { f(); null } catch (e: P.NapakaPonudnika) { e.razlog }

// ------------------------------------------------------------------ lazne shrambe (pomnilnik)

private class UpLazneNastavitve : P.Nastavitve, P.ShrambaZapaha {
    val m = HashMap<String, String>()
    override fun beri(kljuc: String): String? = m[kljuc]
    override fun pisi(kljuc: String, vrednost: String?) { if (vrednost == null) m.remove(kljuc) else m[kljuc] = vrednost }
    override fun beri(): String? = m[P.KLJUC_ZAPAHA]
    override fun pisi(besedilo: String) { m[P.KLJUC_ZAPAHA] = besedilo }
    fun vse(): String = m.entries.joinToString("\n") { it.key + "=" + it.value }
}

private class UpLaznaZbirka(var deluje: Boolean = true) : P.ZbirkaGesel {
    val m = HashMap<String, String>()
    override fun shrani(kljuc: String, geslo: P.Geslo): Boolean { if (!deluje) return false; m[kljuc] = "sifrirano:" + geslo.vrednost().reversed(); return true }
    override fun preberi(kljuc: String): String? = m[kljuc]?.removePrefix("sifrirano:")?.reversed()
    override fun pozabi(kljuc: String) { m.remove(kljuc) }
}

private class UpUra(var t: Long = 1_000_000L) { fun zdaj() = t }

private class UpOkolje {
    val ura = UpUra()
    val nast = UpLazneNastavitve()
    val zbirka = UpLaznaZbirka()
    fun zapahi() = P.ZapahRacunov(nast, { n -> ByteArray(n) { it.toByte() } }, { 1L })
    val omejevalec = P.OmejevalecPreverb { ura.zdaj() }
    val shramba = P.ShrambaPonudnika(nast, zbirka, zapahi(), omejevalec, { 1L })
}

private val P1 = P.Ponudnik.ustvari("news.example", "uporabnik1")
private val G1 = P.Geslo.iz("geslo1-skrivno")
private val G2 = P.Geslo.iz("geslo2-drugo")

private class UpStreznik(var izid: String = P.NNTP_OK, var faza: String = P.FAZA_PRIJAVA) {
    var klicev = 0
    val prijava: (P.Ponudnik, P.Geslo, List<String>) -> P.Prijava = { _, _, _ -> klicev++; P.Prijava(izid, faza) }
}

// ------------------------------------------------------------------ skupni primeri

private fun skupniPrimeri(pot: String) {
    val vrstice = File(pot).readLines().filter { it.isNotBlank() }
    var n = 0
    for (v in vrstice) {
        val d = P.Json.beri(v) as Map<*, *>
        n++
        when (d["vrsta"]) {
            "gostitelj" -> {
                val g = d["g"] as String
                preveriEnako("ref gostitelj ${esc(g)}", d["izid"], izid { P.Ponudnik.izSlovarja(mapOf("gostitelj" to g, "uporabnik" to "uporabnik1")) })
                if (d["domace"] != null) preveriEnako("ref domace ${esc(g)}", d["domace"], P.domaceOmrezje(g))
                if (d["racun"] != null) preveriEnako("ref racun ${esc(g)}", d["racun"], P.ponudnikRacuna(g))
            }
            "poverilnica" -> preveriEnako("ref poverilnica ${esc(d["v"] as String)}", d["izid"], P.varnaPoverilnica(d["v"]))
            "zapis" -> preveriEnako("ref zapis ${esc(P.Json.pisi(d["d"]))}", d["izid"], izid { P.Ponudnik.izSlovarja(d["d"]) })
            "kljuc" -> {
                val p = P.Ponudnik.ustvari(d["g"] as String, d["u"] as String)
                preveriEnako("ref kljuc_gesla ${esc(p.gostitelj)}/${esc(p.uporabnik)}", d["kljuc_gesla"], p.kljucGesla)
                preveriEnako("ref kljuc_racuna ${esc(p.gostitelj)}/${esc(p.uporabnik)}", d["kljuc_racuna"], p.kljucRacuna)
            }
            "zapah" -> {
                val sol = (d["sol"] as String).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val (r, o) = P.zapahOdtis(sol, d["p"] as String, d["u"] as String, d["geslo"] as String)
                preveriEnako("ref zapah racun ${d["p"]}", d["racun"], r)
                preveriEnako("ref zapah odtis ${d["p"]}", d["odtis"], o)
            }
            "ukrep" -> preveriEnako("ref ukrep ${d["izid"]}", d["ukrep"], P.ukrepPrijave(d["izid"] as String))
            "izid" -> preveriEnako("ref izid ${d["izid"]}/${d["faza"]}/${d["zapahnjen"]}", d["rezultat"],
                P.izidNapake(d["izid"] as String, d["faza"] as String, d["zapahnjen"] as Boolean))
            "stanje" -> {
                val p = P.Ponudnik.ustvari("news.example", "uporabnik1", ime = "Moj")
                val s = P.stanjeZaVmesnik(p, d["zadnji"], d["geslo"] as Boolean, d["zapahnjen"] as Boolean)
                val ime = "ref stanje ${d["zadnji"]}/${d["geslo"]}/${d["zapahnjen"]}"
                preveriEnako("$ime stanje", d["stanje"], s.stanje)
                preveriEnako("$ime besedilo", d["besedilo"], s.besedilo)
                preveriEnako("$ime sl", d["sl"], P.besedilo(s.besedilo, "sl"))
            }
            "stanje_prazno" -> {
                val s = P.stanjeZaVmesnik(null, null, false, false)
                preveriEnako("ref stanje prazno", d["stanje"], s.stanje)
                preveriEnako("ref stanje prazno vpisan", d["vpisan"], s.vpisan)
                preveriEnako("ref stanje prazno sl", d["sl"], P.besedilo(s.besedilo, "sl"))
            }
            else -> { napak++; println("NAPAKA neznana vrsta primera ${d["vrsta"]}") }
        }
    }
    preveriEnako("skupnih primerov vsaj 300", true, n >= 300)
}

private fun esc(s: String): String = s.map { if (it < ' ' || it.code > 0x7e) "\\u%04x".format(it.code) else it.toString() }.joinToString("")

// ------------------------------------------------------------------ vedenje

private fun skrivnostiNePuscajo() {
    val g = "geslo1-skrivno"
    preveriEnako("Geslo.toString brez vrednosti", false, G1.toString().contains(g))
    preveriEnako("Geslo v nizu brez vrednosti", false, "$G1".contains("geslo1"))
    val e = try { P.Geslo.iz("slabo\ngeslo"); "" } catch (x: P.NeveljavenVnos) { x.toString() }
    preveriEnako("napaka gesla brez vrednosti", false, e.contains("slabo"))
    preveriEnako("napaka gesla je N_GESLO", true, e.contains(P.N_GESLO))
    preveriEnako("Ponudnik.toString brez uporabnika", false, P1.toString().contains("uporabnik1"))
    val u = try { P.Ponudnik.ustvari("news.example", "tajnoime\n1"); "" } catch (x: P.NeveljavenVnos) { x.toString() }
    preveriEnako("napaka uporabnika brez vrednosti", false, u.contains("tajnoime"))
    preveriEnako("napaka uporabnika je N_UPORABNIK", true, u.contains(P.N_UPORABNIK))
    preveriEnako("NapakaPonudnika samo razlog", "usenet ponudnik: ${P.NI_GESLA}", P.NapakaPonudnika(P.NI_GESLA).message)
    preveriEnako("ZapisKroga.toString brez gesla", false, P.zaKrog(P1, G1).toString().contains(g))

    val o = UpOkolje()
    o.shramba.shrani(P1, G1)
    preveriEnako("nastavitve brez gesla po shrani", false, o.nast.vse().contains(g))
    preveriEnako("geslo v zbirki (sifrirano)", true, o.zbirka.m.containsKey(P1.kljucGesla))
    preveriEnako("kljuc zbirke brez uporabnika", false, P1.kljucGesla.contains("uporabnik1"))
    val s = UpStreznik(P.NNTP_PRIJAVA)
    o.shramba.preveri(P1, G1, false, P.Tls.SISTEMSKI, s.prijava)
    preveriEnako("zapah brez gesla v nastavitvah", false, o.nast.vse().contains(g))
    preveriEnako("zapah zapisan", true, o.nast.m[P.KLJUC_ZAPAHA]?.startsWith(P.GLAVA_ZAPAHA) == true)

    val brez = UpOkolje().also { it.zbirka.deluje = false }
    preveriEnako("brez zbirke shrani = ne trajno", false, brez.shramba.shrani(P1, G1))
    preveriEnako("brez zbirke geslo samo v pomnilniku", G1.vrednost(), brez.shramba.geslo(P1)?.vrednost())
    preveriEnako("brez zbirke nastavitve brez gesla", false, brez.nast.vse().contains(g))
}

private fun omejitevPreverb() {
    val ura = UpUra()
    val om = P.OmejevalecPreverb { ura.zdaj() }
    preveriEnako("preverba prva", true, om.rezerviraj(P1.kljucRacuna))
    ura.t += P.PREVERBA_NA_MS - 1
    preveriEnako("preverba pred minuto ne", false, om.rezerviraj(P1.kljucRacuna))
    val alias = P.Ponudnik.ustvari("eu.news.example", "uporabnik1")
    preveriEnako("drugo ime streznika isti racun ne", false, om.rezerviraj(alias.kljucRacuna))
    preveriEnako("drug uporabnik sme", true, om.rezerviraj(P.Ponudnik.ustvari("news.example", "uporabnik2").kljucRacuna))
    ura.t += 1
    preveriEnako("preverba po minuti", true, om.rezerviraj(P1.kljucRacuna))
    ura.t += P.PREVERBA_NA_MS
    om.rezerviraj(P1.kljucRacuna)
    preveriEnako("stari vnosi pobrisani", 1, om.velikost())

    // Preko orkestracije: drugi klik v minuti ne gre na omrezje.
    val o = UpOkolje(); val s = UpStreznik()
    preveriEnako("Preveri 1", P.VELJA, o.shramba.preveri(P1, G1, false, P.Tls.SISTEMSKI, s.prijava))
    preveriEnako("Preveri 2 v minuti", P.PREPOGOSTO, o.shramba.preveri(P1, G1, false, P.Tls.SISTEMSKI, s.prijava))
    preveriEnako("Preveri 2 brez omrezja", 1, s.klicev)
    o.shramba.shrani(P1, G1)
    preveriEnako("nov vpis sme takoj", P.VELJA, o.shramba.preveri(P1, G1, false, P.Tls.SISTEMSKI, s.prijava))
}

private fun predOmrezjem() {
    val o = UpOkolje(); val s = UpStreznik()
    preveriEnako("glavna nit zavrnjena", P.GLAVNA_NIT, napakaPonudnika { o.shramba.preveri(P1, G1, true, P.Tls.SISTEMSKI, s.prijava) })
    val doma = P.Ponudnik.ustvari("192.168.1.10", "uporabnik1", vrata = 119, tls = false, nevarnoBrezTls = true)
    preveriEnako("brez TLS zavrnjen", P.BREZ_TLS, napakaPonudnika { o.shramba.preveri(doma, G1, false, P.Tls.SISTEMSKI, s.prijava) })
    preveriEnako("CERT_NONE zavrnjen", P.NEVAREN_TLS,
        napakaPonudnika { o.shramba.preveri(P1, G1, false, P.Tls(false, true, listOf("TLSv1.3")), s.prijava) })
    preveriEnako("brez preverbe imena zavrnjen", P.NEVAREN_TLS,
        napakaPonudnika { o.shramba.preveri(P1, G1, false, P.Tls(true, false, listOf("TLSv1.3")), s.prijava) })
    preveriEnako("samo TLS 1.0 zavrnjen", P.NEVAREN_TLS,
        napakaPonudnika { o.shramba.preveri(P1, G1, false, P.Tls(true, true, listOf("TLSv1", "TLSv1.1")), s.prijava) })
    preveriEnako("nic omrezja pred zavrnitvami", 0, s.klicev)
    preveriEnako("TLS < 1.2 odrezan", listOf("TLSv1.2"), P.preveriTls(P.Tls(true, true, listOf("TLSv1.1", "TLSv1.2"))))
    preveriEnako("ovira bazena brez TLS", P.BREZ_TLS, P.oviraBazena(doma, G1, o.zapahi()))
    preveriEnako("ovira bazena brez gesla", P.NI_GESLA, P.oviraBazena(P1, null, o.zapahi()))

    val par = P.varniParametri(SSLParameters(emptyArray(), arrayOf("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3")))
    preveriEnako("parametri: preverba imena", "HTTPS", par.endpointIdentificationAlgorithm)
    preveriEnako("parametri: samo 1.2/1.3", listOf("TLSv1.2", "TLSv1.3"), par.protocols.toList())
    preveriEnako("goli parametri niso varni", P.NEVAREN_TLS, napakaPonudnika { P.preveriTls(P.tlsIzParametrov(SSLParameters(), true)) })
    preveriEnako("varni parametri prestanejo", listOf("TLSv1.2", "TLSv1.3"), P.preveriTls(P.tlsIzParametrov(par, true)))
    val vt = SSLSocketFactory.getDefault().createSocket() as SSLSocket  // brez connect: ni omrezja
    vt.sslParameters = P.varniParametri(vt.sslParameters)
    preveriEnako("vticnica: preverba imena", "HTTPS", vt.sslParameters.endpointIdentificationAlgorithm)
    preveriEnako("vticnica: brez TLS 1.0/1.1", false, vt.enabledProtocols.any { it == "TLSv1" || it == "TLSv1.1" })
    vt.close()
}

private fun zapah() {
    val o = UpOkolje(); val s = UpStreznik(P.NNTP_PRIJAVA)
    o.shramba.shrani(P1, G1)
    preveriEnako("zavrnjena prijava", P.NE_VELJA, o.shramba.preveri(P1, G1, false, P.Tls.SISTEMSKI, s.prijava))
    preveriEnako("stanje NE_VELJA", P.NE_VELJA, o.shramba.stanje().stanje)
    o.ura.t += P.PREVERBA_NA_MS
    preveriEnako("Preveri po zavrnitvi = ZAKLENJEN", P.ZAKLENJEN, o.shramba.preveri(P1, G1, false, P.Tls.SISTEMSKI, s.prijava))
    preveriEnako("zavrnjeno geslo poslano samo enkrat", 1, s.klicev)
    preveriEnako("Preveri ne odklene (stanje)", P.ZAKLENJEN, o.shramba.stanje().stanje)
    preveriEnako("bazen zaklenjen", P.RACUN_ZAKLENJEN, o.shramba.oviraBazena(P1))
    val alias = P.Ponudnik.ustvari("eu.news.example", "uporabnik1")
    preveriEnako("drugo ime streznika tudi zaklenjeno", P.RACUN_ZAKLENJEN, P.oviraBazena(alias, G1, o.shramba.zapahi))
    preveriEnako("po ponovnem zagonu se zaklenjen", true, o.zapahi().zavrnjen("news.example", "uporabnik1", G1))
    preveriEnako("drugo geslo ni zaklenjeno", false, o.zapahi().zavrnjen("news.example", "uporabnik1", G2))
    preveriEnako("iz kroga isto geslo", true, o.shramba.prejetoIzKroga(P1.vSlovar(), G1.vrednost()))
    preveriEnako("krog NE odklene", P.RACUN_ZAKLENJEN, o.shramba.oviraBazena(P1))
    preveriEnako("krog NE odklene (trajno)", true, o.zapahi().zavrnjen("news.example", "uporabnik1", G1))
    o.ura.t += P.PREVERBA_NA_MS
    preveriEnako("krog: Preveri se vedno ZAKLENJEN", P.ZAKLENJEN, o.shramba.preveri(P1, G1, false, P.Tls.SISTEMSKI, s.prijava))
    preveriEnako("krog: brez omrezja", 1, s.klicev)
    o.shramba.shrani(P1, G1)  // izrecen nov vpis uporabnika
    preveriEnako("nov vpis odklene", null, o.shramba.oviraBazena(P1))
    preveriEnako("nov vpis odklene (trajno)", false, o.zapahi().zavrnjen("news.example", "uporabnik1", G1))
    s.izid = P.NNTP_OK
    preveriEnako("po vpisu Preveri gre na omrezje", P.VELJA, o.shramba.preveri(P1, G1, false, P.Tls.SISTEMSKI, s.prijava))
    preveriEnako("stanje VELJA", P.VELJA, o.shramba.stanje().stanje)

    // Neznan izid prijave: prvic brez zapaha, drugic zapah (kot _po_prijavi).
    val o2 = UpOkolje(); val s2 = UpStreznik(P.NNTP_OMREZJE, P.FAZA_PRIJAVA)
    preveriEnako("neznana prijava 1", P.NI_ODGOVORA, o2.shramba.preveri(P1, G1, false, P.Tls.SISTEMSKI, s2.prijava))
    preveriEnako("neznana prijava 1 brez zapaha", false, o2.shramba.zapahi.zavrnjen("news.example", "uporabnik1", G1))
    o2.ura.t += P.PREVERBA_NA_MS
    preveriEnako("neznana prijava 2", P.ZAKLENJEN, o2.shramba.preveri(P1, G1, false, P.Tls.SISTEMSKI, s2.prijava))
    preveriEnako("neznana prijava 2 zapah", true, o2.zapahi().zavrnjen("news.example", "uporabnik1", G1))

    val o3 = UpOkolje(); val s3 = UpStreznik(P.NNTP_ZASEDEN, P.FAZA_PRIJAVA)
    preveriEnako("zaseden", P.PREVEC_POVEZAV, o3.shramba.preveri(P1, G1, false, P.Tls.SISTEMSKI, s3.prijava))
    preveriEnako("zaseden brez zapaha", false, o3.zapahi().zavrnjen("news.example", "uporabnik1", G1))
    val o4 = UpOkolje()
    val izjema: (P.Ponudnik, P.Geslo, List<String>) -> P.Prijava = { _, _, _ -> throw java.io.IOException("x") }
    preveriEnako("izjema pogona", P.NI_ODGOVORA, o4.shramba.preveri(P1, G1, false, P.Tls.SISTEMSKI, izjema))
    preveriEnako("izjema brez zapaha", false, o4.zapahi().zavrnjen("news.example", "uporabnik1", G1))

    val o5 = UpOkolje(); val s5 = UpStreznik(P.NNTP_PRIJAVA)
    o5.shramba.shrani(P1, G1)
    o5.shramba.preveri(P1, G1, false, P.Tls.SISTEMSKI, s5.prijava)
    o5.shramba.pozabi()
    preveriEnako("pozabi odklene in brise", 0, o5.zapahi().steviloTrajnih())
    preveriEnako("pozabi brise geslo", true, o5.zbirka.m.isEmpty())
    preveriEnako("pozabi brise zapis", null, o5.shramba.preberi())
}

private fun mejaRacuna() {
    val m = P.MejeRacunov()
    val p = P.Ponudnik.ustvari("news.example", "uporabnik1", najvecPovezav = 3)
    val alias = P.Ponudnik.ustvari("eu.news.example", "uporabnik1", najvecPovezav = 3)
    preveriEnako("meja 1", true, m.zasedi(p)); preveriEnako("meja 2", true, m.zasedi(p))
    preveriEnako("meja 3 (drug bazen, drugo ime)", true, m.zasedi(alias))
    preveriEnako("meja 4 zavrnjena", false, m.zasedi(p))
    preveriEnako("meja 4 zavrnjena (drugo ime)", false, m.zasedi(alias))
    m.sprosti(p)
    preveriEnako("po sprostitvi sme", true, m.zasedi(p))
    preveriEnako("drug uporabnik loceno", true, m.zasedi(P.Ponudnik.ustvari("news.example", "uporabnik2", najvecPovezav = 1)))
    val velik = P.Ponudnik.ustvari("news.example", "uporabnik3", najvecPovezav = 100)
    preveriEnako("meja ne cez pogon", P.NAJVEC_POVEZAV_NNTP, velik.mejaPovezav)
    preveriEnako("omejiNajvec od zunaj", 3, m.omejiNajvec(p, 1000))
    // Hkratnost: 16 niti, nikoli vec kot meja odprtih hkrati.
    val q = P.Ponudnik.ustvari("news.example", "uporabnik4", najvecPovezav = 5)
    val odprtih = AtomicInteger(); val najvec = AtomicInteger()
    val niti = (1..16).map {
        Thread {
            repeat(2000) {
                if (m.zasedi(q)) {
                    val n = odprtih.incrementAndGet(); najvec.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                    odprtih.decrementAndGet(); m.sprosti(q)
                }
            }
        }
    }
    niti.forEach { it.start() }; niti.forEach { it.join() }
    preveriEnako("hkratno nikoli cez mejo", true, najvec.get() <= 5)
    preveriEnako("hkratno vse sproscene", 0, m.odprtih(q))
}

private fun shramba() {
    val o = UpOkolje()
    o.shramba.shrani(P1, G1)
    preveriEnako("preberi", P1, o.shramba.preberi())
    val p2 = P.Ponudnik.ustvari("news.example", "uporabnik2")
    o.shramba.shrani(p2, G2)
    preveriEnako("zamenjan racun: staro geslo pozabljeno", false, o.zbirka.m.containsKey(P1.kljucGesla))
    preveriEnako("novo geslo", G2.vrednost(), o.shramba.geslo(p2)?.vrednost())
    // Izid preverbe za prejsnji racun ne velja za novega.
    o.ura.t += P.PREVERBA_NA_MS
    o.shramba.preveri(P1, G1, false, P.Tls.SISTEMSKI, UpStreznik().prijava)
    preveriEnako("izid tujega racuna ni zapisan", P.NEPREVERJEN, o.shramba.stanje().stanje)
    o.nast.m[P.KLJUC_NASTAVITEV] = o.nast.m[P.KLJUC_NASTAVITEV]!!.replace("\"verzija\":1", "\"verzija\":2")
    preveriEnako("tuja verzija zavrnjena", null, o.shramba.preberi())
    o.nast.m[P.KLJUC_NASTAVITEV] = P.Json.pisi(mapOf("verzija" to 1, "ponudnik" to P1.vSlovar() + ("insecure" to true)))
    preveriEnako("zapis z insecure zavrnjen", null, o.shramba.preberi())
    o.nast.m[P.KLJUC_NASTAVITEV] = P.Json.pisi(mapOf("verzija" to 1, "ponudnik" to P1.vSlovar(), "x" to "a".repeat(P.NAJVEC_NASTAVITEV_BAJTOV)))
    preveriEnako("prevelik zapis zavrnjen", null, o.shramba.preberi())
    o.nast.m[P.KLJUC_NASTAVITEV] = "{pokvarjeno"
    preveriEnako("pokvarjen JSON", null, o.shramba.preberi())
    preveriEnako("stanje brez zapisa", false, o.shramba.stanje().vpisan)
    o.zbirka.m[P1.kljucGesla] = "sifrirano:" + "a\nb".reversed()
    preveriEnako("pokvarjeno geslo v zbirki ne gre v ukaz", null, o.shramba.geslo(P1))

    // Ozji krog: isto preverjanje kot vnos, neveljavno se ne shrani.
    val k = UpOkolje()
    preveriEnako("krog: neveljaven zapis", false, k.shramba.prejetoIzKroga(mapOf("gostitelj" to "news.example", "uporabnik" to "uporabnik1", "insecure" to true), "geslo1"))
    preveriEnako("krog: neveljavno geslo", false, k.shramba.prejetoIzKroga(P1.vSlovar(), "a\u0000b"))
    preveriEnako("krog: nic shranjeno", true, k.nast.m.isEmpty() && k.zbirka.m.isEmpty())
    preveriEnako("krog: veljaven", true, k.shramba.prejetoIzKroga(P1.vSlovar(), "geslo1"))
    val z = k.shramba.zaKrog()
    preveriEnako("krog: zapis brez zapaha/preverbe", P1.vSlovar().keys, z?.ponudnik?.keys)
}

private fun besedila() {
    val kljuci = listOf("NI_VPISAN", P.NEPREVERJEN, P.NI_GESLA, P.VELJA, P.NE_VELJA, P.ZAKLENJEN, P.NI_ODGOVORA,
        P.PREVEC_POVEZAV, P.PREPOGOSTO, P.BREZ_TLS, P.N_GOSTITELJ, P.N_VRATA, P.N_UPORABNIK, P.N_GESLO, P.N_POVEZAV,
        P.N_IME, P.N_BREZ_TLS, P.N_BREZ_TLS_DOMA, "NASLOV", "POLJE_STREZNIK", "POLJE_VRATA", "POLJE_TLS",
        "POLJE_UPORABNIK", "POLJE_GESLO", "POLJE_POVEZAV", "GUMB_SHRANI", "GUMB_PREVERI", "GUMB_POZABI")
    for (k in kljuci) for (j in P.JEZIKI) {
        val b = P.BESEDILA[k]?.get(j)
        if (b.isNullOrBlank()) { napak++; println("NAPAKA besedilo manjka: $k/$j") }
    }
    preveriEnako("besedila za vse kljuce in 6 jezikov", true, kljuci.all { k -> P.JEZIKI.all { P.BESEDILA[k]?.get(it)?.isNotBlank() == true } })
}

fun main(args: Array<String>) {
    skupniPrimeri(args.getOrElse(0) { "tests/skupni-primeri/usenet-ponudnik.jsonl" })
    skrivnostiNePuscajo()
    omejitevPreverb()
    predOmrezjem()
    zapah()
    mejaRacuna()
    shramba()
    besedila()
    if (napak > 0) { println("UsenetPonudnikPravilaTest: $napak napak"); exitProcess(1) }
    println("UsenetPonudnikPravilaTest: VSE OK")
}
