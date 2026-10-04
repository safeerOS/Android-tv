package si.safeer.tv.os

import si.safeer.tv.os.DomPreverjanjePravila as D
import si.safeer.tv.os.RazpolozljivostPravila as P

/** Preverjevalec doma: kdo preverja, vrsta (okno pred zalogo, en naslov enkrat), delo na zalogo. */
fun main() {
    val zdaj = 1_800_000_000_000L
    val s = 1_000L

    // ---- kdo preverja: televizor pred tablico in telefonom; pri enakem rangu manjsa oznaka naprave
    val tv = D.Kandidat("n-aaaa000000000001", "n-aaaa000000000001", "tv")
    val tablica = D.Kandidat("n-bbbb000000000002", "n-bbbb000000000002", "tablet")
    val telefon1 = D.Kandidat("n-cccc000000000003", "n-cccc000000000003", "phone")
    val telefon2 = D.Kandidat("n-dddd000000000004", "n-dddd000000000004", "phone")
    check(D.izberi(telefon1, listOf(tv, tablica, telefon2)) == tv.id)
    check(D.izberi(tablica, listOf(tv, telefon1)) == tv.id)
    check(D.izberi(tv, listOf(tablica, telefon1)) == null) { "televizor preverja sam" }
    check(D.izberi(telefon1, listOf(tablica, telefon2)) == tablica.id)
    check(D.izberi(telefon1, listOf(telefon2)) == null && D.izberi(telefon2, listOf(telefon1)) == telefon1.id) { "enak rang: obe napravi izbereta isto" }
    check(D.izberi(telefon1, emptyList()) == null) { "sama naprava preverja sama" }
    // Vse naprave pridejo do istega preverjevalca, ne glede na to, katera vprasa.
    val vse = listOf(tv, tablica, telefon1, telefon2)
    val izbrani = vse.map { jaz -> D.izberi(jaz, vse - jaz) ?: jaz.id }.toSet()
    check(izbrani == setOf(tv.id)) { izbrani }
    // Preverjevalec, ki ne more (ne odgovori, drugi dodatki), je izlocen: na vrsti je naslednji.
    check(D.izberi(telefon1, listOf(tv, tablica, telefon2), izloceni = setOf(tv.id)) == tablica.id)
    check(D.izberi(telefon1, listOf(tv, tablica), izloceni = setOf(tv.id, tablica.id)) == null)
    // Aplikacija, ki sama ne more biti preverjevalec (Predvajalnik), vprasa najboljsega - tudi na isti napravi.
    check(D.izberi(null, listOf(telefon1, telefon2)) == telefon1.id && D.izberi(null, emptyList()) == null)
    // Druga identiteta iste fizicne naprave (sprejemnik in aplikacija) ni »druga naprava«.
    val jazOs = D.Kandidat("n-cccc000000000003-os", "n-cccc000000000003", "phone")
    check(D.izberi(jazOs, listOf(telefon1)) == null) { "lastni sprejemnik ni preverjevalec zame" }
    check(D.izberi(jazOs, listOf(telefon1, telefon2)) == null && D.izberi(D.Kandidat("n-dddd000000000004-os", telefon2.naprava, "phone"), listOf(telefon1, telefon2)) == telefon1.id)
    // Naprava brez ranga (racunalnik, brskalnik) ni kandidat.
    check(D.izberi(telefon1, listOf(D.Kandidat("n-eeee000000000005", "n-eeee000000000005", "linux"))) == null)
    check(D.rang("tv") > D.rang("tablet") && D.rang("tablet") > D.rang("phone") && D.rang("linux") == 0 && D.rang("") == 0)

    // ---- kljuci in meja
    check(D.veljaven("movie|tt0111161") && D.veljaven("series|tt0944947") && D.veljaven("series|tt0944947:1:2"))
    check(!D.veljaven("movie|zp:12") && !D.veljaven("tv|tt0111161") && !D.veljaven("movie|tt0111161|x"))
    check(D.jeSerija("series|tt0944947") && !D.jeSerija("series|tt0944947:1:2") && !D.jeSerija("movie|tt0111161"))
    check(D.razstavi("series|tt0944947:1:2") == ("series" to "tt0944947:1:2"))
    check(D.mejaIzNiza("M") == P.VSE && D.mejaIzNiza("0") == 0L && D.mejaIzNiza("123") == 123L)
    check(D.mejaIzNiza(null) == 0L && D.mejaIzNiza("-5") == 0L && D.mejaIzNiza("x") == 0L) { "neveljavna meja = najstrozja (samo neposredni tokovi)" }
    check(D.mejaVNiz(P.VSE) == "M" && D.mejaVNiz(0L) == "0" && D.mejaIzNiza(D.mejaVNiz(5_000_000L)) == 5_000_000L)

    // ---- vrsta: okno pred zalogo, isti naslov samo enkrat
    val v = D.Vrsta()
    check(v.naslednji(zdaj, tudiZaloga = true) == null)
    check(v.naZalogo("movie|tt0000001", zdaj) && v.naZalogo("movie|tt0000002", zdaj))
    v.zeli("movie|tt0000003", P.VSE, zdaj)
    v.zeli("movie|tt0000003", 0L, zdaj + s)                       // druga naprava zeli isti naslov, zmore manj
    v.zeli("movie|tt0000004", P.VSE, zdaj + s)
    check(v.velikostOkna == 2 && v.velikostZaloge == 2)
    val prvi = v.naslednji(zdaj + s, tudiZaloga = true)!!
    check(prvi.first.kljuc == "movie|tt0000003" && prvi.second && prvi.first.meja == 0L) { "okno najprej; meja = najmanjsa med cakajocimi" }
    val drugi = v.naslednji(zdaj + s, tudiZaloga = true)!!
    check(drugi.first.kljuc == "movie|tt0000004" && drugi.second) { "naslov v delu ne gre v delo dvakrat" }
    check(v.caka(listOf("movie|tt0000003", "movie|tt0000004", "movie|tt0000009")) == 2)
    check(!v.oknoCaka(zdaj + s)) { "vse iz okna je v delu" }
    // Zaloga pride na vrsto sele, ko okno ne caka, in samo, ce je dovoljena.
    check(v.naslednji(zdaj + s, tudiZaloga = false) == null)
    val tretji = v.naslednji(zdaj + s, tudiZaloga = true)!!
    check(tretji.first.kljuc == "movie|tt0000001" && !tretji.second && tretji.first.meja == P.BREZ) { "zaloga: popolno preverjanje (meja BREZ)" }
    // Koncano z odgovorom: naslov gre iz vrste; neuspeh: nekaj casa ga ne vprasamo znova.
    v.koncano("movie|tt0000003", odgovor = true, zdaj = zdaj + 2 * s)
    v.koncano("movie|tt0000004", odgovor = false, zdaj = zdaj + 2 * s)
    check(v.velikostOkna == 0 && v.caka(listOf("movie|tt0000003", "movie|tt0000004")) == 0)
    check(v.neuspeli(listOf("movie|tt0000003", "movie|tt0000004"), zdaj + 3 * s) == listOf("movie|tt0000004"))
    v.zeli("movie|tt0000004", P.VSE, zdaj + 3 * s)
    check(v.naslednji(zdaj + 3 * s, tudiZaloga = false) == null) { "po neuspehu pocakamo" }
    check(!v.oknoCaka(zdaj + 3 * s))
    v.zeli("movie|tt0000004", P.VSE, zdaj + D.PO_NEUSPEHU_MS + 3 * s)
    check(v.naslednji(zdaj + D.PO_NEUSPEHU_MS + 3 * s, tudiZaloga = false)?.first?.kljuc == "movie|tt0000004")
    v.koncano("movie|tt0000004", odgovor = true, zdaj = zdaj + D.PO_NEUSPEHU_MS + 4 * s)
    // Preklicano (vprasanja nismo poslali): naslov ostane v vrsti in pride spet na vrsto.
    v.koncano("movie|tt0000001", odgovor = false, zdaj = zdaj + 4 * s, preklicano = true)
    check(v.velikostZaloge == 2 && v.neuspeli(listOf("movie|tt0000001"), zdaj + 4 * s).isEmpty())

    // ---- okno, ki ga nihce vec ne sprasuje, gre med zalogo; dokler ga kdo sprasuje, ostane
    val w = D.Vrsta()
    w.zeli("movie|tt0000010", P.VSE, zdaj)
    w.zeli("movie|tt0000011", P.VSE, zdaj)
    check(w.seZelen("movie|tt0000010", zdaj + D.ZELJA_VELJA_MS) && !w.seZelen("movie|tt0000010", zdaj + D.ZELJA_VELJA_MS + 1))
    w.zeli("movie|tt0000011", P.VSE, zdaj + D.ZELJA_VELJA_MS)     // odjemalec se sprasuje
    w.pospravi(zdaj + D.ZELJA_VELJA_MS + s)
    check(w.velikostOkna == 1 && w.velikostZaloge == 1 && w.seZelen("movie|tt0000010", zdaj + D.ZELJA_VELJA_MS + s)) { "zapusceno okno = zaloga" }
    check(w.oknoCaka(zdaj + D.ZELJA_VELJA_MS + s))
    // Naslov, ki ga kdo spet zeli, se vrne v okno (in ni dvakrat v vrsti).
    w.zeli("movie|tt0000010", 0L, zdaj + D.ZELJA_VELJA_MS + 2 * s)
    check(w.velikostOkna == 2 && w.velikostZaloge == 0)
    // Stara zaloga izgine.
    val z = D.Vrsta()
    z.naZalogo("movie|tt0000020", zdaj)
    z.pospravi(zdaj + D.ZALOGA_VELJA_MS + 1)
    check(z.velikostZaloge == 0)
    // Polna zaloga ne raste.
    val poln = D.Vrsta()
    for (i in 0 until D.NAJVEC_ZALOGE) check(poln.naZalogo("movie|tt%07d".format(i), zdaj))
    check(!poln.naZalogo("movie|tt9999999", zdaj) && poln.velikostZaloge == D.NAJVEC_ZALOGE)
    check(poln.naZalogo("movie|tt0000005", zdaj)) { "ze v vrsti: v redu" }
    // Naslov v delu ne gre med zalogo, tudi ce ga odjemalec vmes neha sprasevati.
    val d = D.Vrsta()
    d.zeli("movie|tt0000030", P.VSE, zdaj)
    check(d.naslednji(zdaj, tudiZaloga = false) != null)
    d.pospravi(zdaj + D.ZELJA_VELJA_MS + s)
    check(d.velikostOkna == 1 && d.velikostZaloge == 0)
    d.koncano("movie|tt0000030", odgovor = true, zdaj = zdaj + D.ZELJA_VELJA_MS + 2 * s)
    check(d.velikostOkna == 0 && d.velikostZaloge == 0)

    // ---- delo na zalogo: samo s polnim vedrom, po premoru, ne takoj za uporabnikom, z dnevno mejo
    val zal = D.Zaloga()
    check(zal.smem(zdaj, "2026-10-04", zetonov = 4.0, uporabnikOb = 0L, premorOb = 0L, sme = true))
    check(!zal.smem(zdaj, "2026-10-04", zetonov = 2.9, uporabnikOb = 0L, premorOb = 0L, sme = true)) { "vedro ni polno: zetoni so za uporabnika" }
    check(!zal.smem(zdaj, "2026-10-04", zetonov = 4.0, uporabnikOb = zdaj - 5 * s, premorOb = 0L, sme = true)) { "uporabnik je pravkar nekaj izbral" }
    check(zal.smem(zdaj, "2026-10-04", zetonov = 4.0, uporabnikOb = zdaj - D.ZALOGA_PO_UPORABNIKU_MS, premorOb = 0L, sme = true))
    check(!zal.smem(zdaj, "2026-10-04", zetonov = 4.0, uporabnikOb = 0L, premorOb = zdaj - 60 * s, sme = true)) { "dodatek je pravkar omejil dom" }
    check(zal.smem(zdaj, "2026-10-04", zetonov = 4.0, uporabnikOb = 0L, premorOb = zdaj - D.ZALOGA_PO_PREMORU_MS, sme = true))
    check(!zal.smem(zdaj, "2026-10-04", zetonov = 4.0, uporabnikOb = 0L, premorOb = 0L, sme = false)) { "naprava nima moci (baterija, predvaja, omejeno omrezje)" }
    zal.vprasano(zdaj)
    check(!zal.smem(zdaj + D.ZALOGA_RAZMIK_MS - 1, "2026-10-04", 4.0, 0L, 0L, true) && zal.smem(zdaj + D.ZALOGA_RAZMIK_MS, "2026-10-04", 4.0, 0L, 0L, true))
    var t = zdaj
    while (zal.danes < D.ZALOGA_NA_DAN) { t += D.ZALOGA_RAZMIK_MS; check(zal.smem(t, "2026-10-04", 4.0, 0L, 0L, true)); zal.vprasano(t) }
    check(!zal.smem(t + D.ZALOGA_RAZMIK_MS, "2026-10-04", 4.0, 0L, 0L, true)) { "dnevna meja" }
    check(zal.smem(t + D.ZALOGA_RAZMIK_MS, "2026-10-05", 4.0, 0L, 0L, true) && zal.danes == 0) { "nov dan" }

    println("DomPreverjanjePravilaTest OK")
}
