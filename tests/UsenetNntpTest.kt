package si.safeer.tv.os

import si.safeer.tv.cast.JsonLahki
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import si.safeer.tv.os.UsenetPonudnikPravila as P

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/**
 * Lazni streznik NNTP na 127.0.0.1 (brez TLS - povezovalnik preizkusa): [odgovori] po vrsti - prvi je pozdrav, nato en
 * odgovor na vsak prejeti ukaz; null = streznik zapre povezavo, "" = molci (rok). Prejete ukaze zapise v [prejeto].
 */
private class LazniStreznik(private val odgovori: List<String?>) : AutoCloseable {
    val streznik = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
    val prejeto: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile var povezav = 0
    private val nit = Thread {
        try {
            while (!streznik.isClosed) {
                val s = streznik.accept()
                povezav++
                s.use { obdelaj(it) }
            }
        } catch (_: Exception) { }
    }.apply { isDaemon = true; start() }

    private fun obdelaj(s: Socket) {
        val vhod = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val izhod = s.getOutputStream()
        for ((i, o) in odgovori.withIndex()) {
            if (i > 0) prejeto.add(vhod.readLine() ?: return)
            if (o == null) return
            if (o.isEmpty()) { Thread.sleep(1_500); return }
            izhod.write((o + "\r\n").toByteArray(Charsets.UTF_8)); izhod.flush()
        }
        vhod.readLine()?.let { prejeto.add(it) }   // QUIT
    }

    val povezovalnik = UsenetNntp.Povezovalnik { _, _, _ ->
        Socket(InetAddress.getLoopbackAddress(), streznik.localPort).apply { soTimeout = 700 }
    }

    override fun close() { streznik.close(); nit.join(2_000) }
}

/** Naloga 42 G: prijava NNTP s shranjenim ponudnikom (UsenetNntp) - lazni streznik, nikoli pravi ponudnik. */
fun main(args: Array<String>) {
    println("Preizkus prijave NNTP (lazni streznik)")

    println("\n== skupni primeri izid_odgovora (core/usenet_nntp.py) ==")
    val pot = args.firstOrNull() ?: "tests/skupni-primeri/usenet-nntp-izid.json"
    val primeri = JsonLahki.elementi(JsonLahki.objekt(File(pot).readText())!!.surovo("primeri")!!)!!
    var skupnih = 0
    for (surov in primeri) {
        val o = JsonLahki.objekt(surov)!!
        val v = o.objekt("vhod")!!
        val dobljeno = UsenetNntp.izidOdgovora(v.nizAli("ukaz"), v.nizAli("vrstica"))
        if (dobljeno != o.nizAli("izhod")) preveriEnako("ref ${v.nizAli("ukaz")} | ${v.nizAli("vrstica")}", o.nizAli("izhod"), dobljeno)
        else skupnih++
    }
    preveriEnako("vseh ${primeri.size} skupnih primerov enako kot na namizju", primeri.size, skupnih)

    val p = P.Ponudnik.ustvari("news.example", "uporabnik1", najvecPovezav = 2)
    val geslo = P.Geslo.iz("lazno-geslo-1")

    println("\n== prijava ==")
    LazniStreznik(listOf("200 lazni streznik", "381 PASS required", "281 Ok")).use { s ->
        val meje = P.MejeRacunov()
        val r = UsenetNntp.prijava(p, geslo, listOf("TLSv1.3"), meje, s.povezovalnik)
        preveriEnako("prava prijava: ok v fazi prijave", P.NNTP_OK to P.FAZA_PRIJAVA, r.izid to r.faza)
        Thread.sleep(150)
        preveriEnako("ukazi strezniku (geslo enkrat, nato QUIT)", listOf("AUTHINFO USER uporabnik1", "AUTHINFO PASS lazno-geslo-1", "QUIT"), s.prejeto.toList())
        preveriEnako("povezava sproscena", 0, meje.odprtih(p))
    }
    LazniStreznik(listOf("200 lazni", "381 PASS required", "481 Authentication failed")).use { s ->
        val r = UsenetNntp.prijava(p, geslo, listOf("TLSv1.3"), P.MejeRacunov(), s.povezovalnik)
        preveriEnako("zavrnjeno geslo: prijava", P.NNTP_PRIJAVA to P.FAZA_PRIJAVA, r.izid to r.faza)
    }
    LazniStreznik(listOf("400 Too many connections from your account")).use { s ->
        val r = UsenetNntp.prijava(p, geslo, listOf("TLSv1.3"), P.MejeRacunov(), s.povezovalnik)
        preveriEnako("meja povezav v pozdravu: zaseden, geslo ne gre", P.NNTP_ZASEDEN to P.FAZA_POVEZAVA, r.izid to r.faza)
        preveriEnako("brez AUTHINFO", emptyList<String>(), s.prejeto.toList())
    }
    LazniStreznik(listOf("200 lazni", null)).use { s ->
        val r = UsenetNntp.prijava(p, geslo, listOf("TLSv1.3"), P.MejeRacunov(), s.povezovalnik)
        preveriEnako("streznik zapre po AUTHINFO USER: zaprto, geslo ne gre", P.NNTP_ZAPRTO to P.FAZA_PRIJAVA, r.izid to r.faza)
        preveriEnako("geslo ni bilo poslano", false, s.prejeto.any { it.startsWith("AUTHINFO PASS") })
    }
    LazniStreznik(listOf("")).use { s ->
        val r = UsenetNntp.prijava(p, geslo, listOf("TLSv1.3"), P.MejeRacunov(), s.povezovalnik)
        preveriEnako("tiho streznik: rok v fazi povezave", P.NNTP_ROK to P.FAZA_POVEZAVA, r.izid to r.faza)
    }
    LazniStreznik(listOf("200 " + "x".repeat(5_000))).use { s ->
        val r = UsenetNntp.prijava(p, geslo, listOf("TLSv1.3"), P.MejeRacunov(), s.povezovalnik)
        preveriEnako("predolga vrstica: napaka protokola", P.NNTP_NAPAKA_PROTOKOLA, r.izid)
    }

    println("\n== trda meja povezav na racun ==")
    LazniStreznik(listOf("200 lazni", "381 PASS", "281 Ok")).use { s ->
        val meje = P.MejeRacunov()
        meje.zasedi(p); meje.zasedi(p)   // dve odprti povezavi iz bazena (meja 2)
        val r = UsenetNntp.prijava(p, geslo, listOf("TLSv1.3"), meje, s.povezovalnik)
        preveriEnako("meja dosezena: zaseden brez omrezja", P.NNTP_ZASEDEN to P.FAZA_POVEZAVA, r.izid to r.faza)
        Thread.sleep(100)
        preveriEnako("streznik ni dobil povezave", 0, s.povezav)
        preveriEnako("stevec ostane 2 (preverba ga ne spremeni)", 2, meje.odprtih(p))
    }

    println("\n== z gumbom Preveri (P.preveri): zapah po zavrnitvi, brez nove povezave ==")
    LazniStreznik(listOf("200 lazni", "381 PASS required", "481 Authentication failed")).use { s ->
        val meje = P.MejeRacunov()
        val zapahi = P.ZapahRacunov(null, { n -> ByteArray(n) { it.toByte() } }, { 1_000L })
        var ura = 0L
        val omejevalec = P.OmejevalecPreverb { ura }
        val prijava = { pp: P.Ponudnik, g: P.Geslo, pr: List<String> -> UsenetNntp.prijava(pp, g, pr, meje, s.povezovalnik) }
        preveriEnako("prvic: ne velja", P.NE_VELJA, P.preveri(p, geslo, false, P.Tls.SISTEMSKI, zapahi, omejevalec, prijava))
        ura += 120_000
        preveriEnako("drugic: zaklenjen brez omrezja", P.ZAKLENJEN, P.preveri(p, geslo, false, P.Tls.SISTEMSKI, zapahi, omejevalec, prijava))
        Thread.sleep(100)
        preveriEnako("streznik je dobil eno samo povezavo", 1, s.povezav)
    }
    LazniStreznik(listOf("200 lazni", "381 PASS required", "281 Ok")).use { s ->
        val meje = P.MejeRacunov()
        val r = P.preveri(p, geslo, false, P.Tls.SISTEMSKI, P.ZapahRacunov(null, { n -> ByteArray(n) }, { 1L }), P.OmejevalecPreverb { 0L }) { pp, g, pr ->
            UsenetNntp.prijava(pp, g, pr, meje, s.povezovalnik) }
        preveriEnako("prava prijava prek Preveri: velja", P.VELJA, r)
    }
    preveriEnako("na glavni niti: napaka pred omrezjem", P.GLAVNA_NIT, try {
        P.preveri(p, geslo, true, P.Tls.SISTEMSKI, P.ZapahRacunov(null, { n -> ByteArray(n) }, { 1L }), P.OmejevalecPreverb { 0L }) { _, _, _ ->
            P.Prijava(P.NNTP_OK, P.FAZA_PRIJAVA) }
    } catch (e: P.NapakaPonudnika) { e.razlog })

    println("\n== ozji krog (secrets.sync) ==")
    preveriEnako("kljuc ponudnika je veljaven kljuc usklajevanja", true, UskladitevKljucevPravila.veljavenKljuc("usenet_ponudnik", emptySet()))
    val zk = P.zaKrog(p, geslo)
    val vrednost = P.Json.pisi(mapOf("ponudnik" to zk.ponudnik, "geslo" to zk.geslo.vrednost()))
    val nazaj = P.Json.beri(vrednost) as Map<*, *>
    val prejeto = P.izKroga(nazaj["ponudnik"], nazaj["geslo"] as String?)
    preveriEnako("vrednost za krog in nazaj: isti ponudnik", p.vSlovar(), prejeto?.first?.vSlovar())
    preveriEnako("vrednost za krog in nazaj: isto geslo", "lazno-geslo-1", prejeto?.second?.vrednost())
    preveriEnako("ZapisKroga ne izda gesla", "ZapisKroga(<skrito>)", zk.toString())
    preveriEnako("pokvarjen prejem (gostitelj s shemo) se ne shrani", null,
        P.izKroga(mapOf("gostitelj" to "https://news.example", "uporabnik" to "u", "vrata" to 563L, "tls" to true), "x"))
    preveriEnako("prejem brez gesla se ne shrani", null, P.izKroga(nazaj["ponudnik"], null))

    println("\n== geslo ne uide ==")
    preveriEnako("Geslo.toString", "Geslo(<skrito>)", geslo.toString())
    preveriEnako("Prijava nima gesla v izidu", false, UsenetNntp.prijava(p, geslo, listOf("TLSv1.3"), P.MejeRacunov(),
        UsenetNntp.Povezovalnik { _, _, _ -> throw java.io.IOException("lazno-geslo-1") }).let { it.izid.contains("lazno") || it.faza.contains("lazno") })

    if (napak == 0) println("UsenetNntpTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
