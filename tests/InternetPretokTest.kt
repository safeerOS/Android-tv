import si.safeer.tv.link.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Safeer Internet Gateway, protokol 2: nadzor pretoka, dovoljenja po napravah, poraba (cisti JVM).
 * Ista pravila preizkusa na racunalniku tests/test_link_internet.py (Safeer-linux).
 */
private class Slovar : ShrambaVrednosti {
    val v = LinkedHashMap<String, String>()
    var zapisov = 0
    override fun niz(kljuc: String): String? = v[kljuc]
    override fun nastavi(kljuc: String, vrednost: String?) { zapisov++; if (vrednost == null) v.remove(kljuc) else v[kljuc] = vrednost }
    override fun kljuci(predpona: String): List<String> = v.keys.filter { it.startsWith(predpona) }
}

private fun preveri(opis: String, pogoj: Boolean) { check(pogoj) { "NAPAKA: $opis" } }

private fun proracun() {
    val p = InternetProracun(mejaBajtov = 100, mejaOkvirjev = 2)
    preveri("prvi okvir", p.zakupi(60) { false })
    preveri("drugi okvir", p.zakupi(30) { false })
    val dobil = AtomicBoolean(false)
    val konec = CountDownLatch(1)
    thread { dobil.set(p.zakupi(10) { false }); konec.countDown() }
    Thread.sleep(300)
    preveri("tretji okvir caka (meja okvirjev)", !dobil.get())
    p.sprosti(60)
    preveri("po sprostitvi gre naprej", konec.await(2, TimeUnit.SECONDS) && dobil.get())
    preveri("preklic med cakanjem", !p.zakupi(90) { true })
    preveri("stanje po preklicu", p.bajtov == 40 && p.okvirjev == 2)
    // En sam okvir, vecji od proracuna, ne sme obstati za vedno.
    val velik = InternetProracun(mejaBajtov = 10, mejaOkvirjev = 5)
    preveri("prevelik okvir gre, ko je pot prazna", velik.zakupi(50) { false })
    preveri("naslednji caka", !velik.zakupi(1) { true })
}

private fun okno() {
    val o = OknoPosiljanja(okno = 100)
    preveri("prazno okno ne caka", o.pocakaj { false })
    o.poslal(60); o.poslal(40)
    preveri("polno okno caka", !o.pocakaj { true })
    preveri("nepotrjeno", o.nepotrjeno == 100L)
    preveri("delna potrditev ne sprosti okvirja", o.potrdi(30) == (0 to 0))
    preveri("po delni potrditvi je v oknu prostor", o.pocakaj { false })
    preveri("potrditev prvega okvirja", o.potrdi(60) == (60 to 1))
    preveri("stara potrditev se ne steje", o.potrdi(50) == (0 to 0))
    preveri("potrditev cez poslano se ustavi pri poslanem", o.potrdi(5000) == (40 to 1))
    preveri("vse potrjeno", o.nepotrjeno == 0L)
    o.poslal(10); o.poslal(20)
    preveri("konec toka vrne vse v proracun", o.sprostiVse() == (30 to 2))
    preveri("po koncu ni nicesar na poti", o.sprostiVse() == (0 to 0))
}

private fun potrjevanje() {
    val p = PotrjevanjePrejema(potrdiPo = 100)
    preveri("majhen kos, vrsta ni prazna: brez potrditve", p.porabil(40, vrstaPrazna = false) == -1L)
    preveri("vrsta prazna: potrdi vse dotlej", p.porabil(10, vrstaPrazna = true) == 50L)
    preveri("pod pragom", p.porabil(99, vrstaPrazna = false) == -1L)
    preveri("prag dosezen", p.porabil(1, vrstaPrazna = false) == 150L)
    // Potrditev je najvec toliko, kolikor je kosov: vsak klic vrne najvec eno.
    var potrditev = 0
    repeat(50) { if (p.porabil(10, vrstaPrazna = true) >= 0) potrditev++ }
    preveri("ena potrditev na kos", potrditev == 50)

    // Tih tok ponovi zadnjo potrditev, vsakic po dvakrat daljsem premoru (izgubljena potrditev, sonda »ali tok se zivi«).
    var zdaj = 0L
    val korak = InternetProtokol.PONOVI_POTRDITEV_MS
    val t = PotrjevanjePrejema(potrdiPo = 100, ura = { zdaj })
    preveri("pred premorom ni ponovitve", t.ponovitev() == -1L)
    zdaj = korak
    preveri("po premoru ponovi, tudi ce ni se nic porabljenega", t.ponovitev() == 0L)
    zdaj += korak
    preveri("druga ponovitev sele po dvakrat daljsem premoru", t.ponovitev() == -1L)
    zdaj += korak
    preveri("druga ponovitev", t.ponovitev() == 0L)
    preveri("kos pod pragom: brez potrditve", t.porabil(40, vrstaPrazna = false) == -1L)
    zdaj += korak - 1
    preveri("promet ponavljanje ponastavi na kratek premor: se ne", t.ponovitev() == -1L)
    zdaj += 1
    preveri("ponovitev pove vse porabljeno, tudi se nepotrjeno", t.ponovitev() == 40L)
    preveri("po ponovitvi je porabljeno ze javljeno", t.porabil(10, vrstaPrazna = false) == -1L)
    repeat(12) { zdaj += InternetProtokol.PONOVI_NAJVEC_MS; t.ponovitev() }
    zdaj += InternetProtokol.PONOVI_NAJVEC_MS - 1
    preveri("premor ne zraste cez najvecjega: tik pred njim se ne", t.ponovitev() == -1L)
    zdaj += 1
    preveri("premor ne zraste cez najvecjega", t.ponovitev() == 50L)
}

private fun dovoljenja() {
    var ura = 1_000_000L
    val s = Slovar()
    val d = InternetDovoljenja(s) { ura }
    preveri("neznana naprava", d.stanje("n-aaaa") == InternetDovoljenja.Stanje.NEZNANO)
    preveri("prva prosnja: vprasaj", d.zabeleziProsnjo("n-aaaa", "Racunalnik"))
    preveri("caka", d.stanje("n-aaaa") == InternetDovoljenja.Stanje.CAKA)
    preveri("takoj zatem ne vprasaj znova", !d.zabeleziProsnjo("n-aaaa", "Racunalnik"))
    ura += InternetDovoljenja.PONOVI_VPRASANJE_MS + 1
    preveri("po minuti vprasaj znova", d.zabeleziProsnjo("n-aaaa", ""))
    preveri("ime ostane, ce novo ni dano", d.vsi().single().ime == "Racunalnik")
    d.odloci("n-aaaa", true)
    preveri("dovoljeno", d.stanje("n-aaaa") == InternetDovoljenja.Stanje.DOVOLJENO)
    preveri("dovoljena naprava ne sprozi vprasanja", !d.zabeleziProsnjo("n-aaaa", "Racunalnik"))
    d.odloci("n-aaaa", false)
    preveri("zavrnjeno", d.stanje("n-aaaa") == InternetDovoljenja.Stanje.ZAVRNJENO)
    ura += 10 * InternetDovoljenja.PONOVI_VPRASANJE_MS
    preveri("zavrnjena naprava ne sprozi vprasanja nikoli vec", !d.zabeleziProsnjo("n-aaaa", "Racunalnik"))
    // Odlocitev prezivi ponovni zagon (ista shramba).
    preveri("po ponovnem zagonu", InternetDovoljenja(s) { ura }.stanje("n-aaaa") == InternetDovoljenja.Stanje.ZAVRNJENO)
    d.pozabi("n-aaaa")
    preveri("pozabljena je spet neznana", d.stanje("n-aaaa") == InternetDovoljenja.Stanje.NEZNANO)
    // Ime ne sme pokvariti zapisa.
    d.zabeleziProsnjo("n-bbbb", "Ime\ts tabulatorjem\nin vrstico " + "x".repeat(200))
    val vnos = d.vsi().single()
    preveri("ime ocisceno in skrajsano", !vnos.ime.contains('\t') && !vnos.ime.contains('\n') && vnos.ime.length <= InternetDovoljenja.NAJVEC_IME)
    preveri("prazna naprava se ne zapise", !d.zabeleziProsnjo("", "x") && d.vsi().size == 1)
    // Seznam ne raste v nedogled; odlocitve ostanejo, najstarejse prosnje gredo.
    d.odloci("n-odlocena", true, "Odlocena")
    for (i in 0 until 60) { ura += 1; d.zabeleziProsnjo("n-%04d".format(i), "Naprava $i") }
    preveri("najvec vnosov", d.vsi().size <= InternetDovoljenja.NAJVEC_VNOSOV)
    preveri("odlocitev ostane", d.stanje("n-odlocena") == InternetDovoljenja.Stanje.DOVOLJENO)
    preveri("zadnja prosnja ostane", d.stanje("n-0059") == InternetDovoljenja.Stanje.CAKA)
    preveri("cakajoce so na vrhu seznama", d.vsi().first().stanje == InternetDovoljenja.Stanje.CAKA)

    // Ob odprtju Safeer OS pokazemo samo sveze prosnje brez odlocitve.
    var cas = 5_000_000L
    val s2 = Slovar()
    val d2 = InternetDovoljenja(s2) { cas }
    preveri("brez prosenj ni svezih", d2.svezeCakajoce().isEmpty())
    d2.zabeleziProsnjo("n-sveza", "Racunalnik")
    preveri("sveza prosnja", d2.svezeCakajoce().single().naprava == "n-sveza")
    preveri("zapis nosi cas prosnje", d2.vnos("n-sveza")?.cas == cas && d2.vnos("n-neznana") == null)
    cas += InternetDovoljenja.SVEZA_PROSNJA_MS
    preveri("na meji se sveza", d2.svezeCakajoce().size == 1)
    cas += 1
    preveri("stara prosnja ni vec sveza (ostane v nastavitvah)", d2.svezeCakajoce().isEmpty() && d2.vsi().single().stanje == InternetDovoljenja.Stanje.CAKA)
    preveri("nova prosnja jo osvezi", d2.zabeleziProsnjo("n-sveza", "") && d2.svezeCakajoce().size == 1)
    d2.odloci("n-sveza", true)
    preveri("odlocena ni vec med cakajocimi", d2.svezeCakajoce().isEmpty())
    d2.zabeleziProsnjo("n-druga", "Drugi")
    cas -= 5 * 60_000L      // ura telefona je sla nazaj (nastavitev casa)
    preveri("ura nazaj: prosnja ostane sveza", d2.svezeCakajoce().single().naprava == "n-druga")
    preveri("ura nazaj: vprasanje ni utisano", d2.zabeleziProsnjo("n-druga", "") && d2.vnos("n-druga")?.cas == cas)

    preveri("fizicna naprava: control", fizicnaNapravaInterneta("n-96bfc3da3b52ea80-control") == "n-96bfc3da3b52ea80")
    preveri("fizicna naprava: brez pripone", fizicnaNapravaInterneta("n-96bfc3da3b52ea80") == "n-96bfc3da3b52ea80")
    preveri("stari id ostane", fizicnaNapravaInterneta("pc-ime") == "pc-ime")
}

private fun poraba() {
    var ura = 0L
    val s = Slovar()
    // Stevec protokola 1 (kljuca cellular_month / cellular_bytes) se nadaljuje.
    s.v["cellular_month"] = "202610"; s.v["cellular_bytes"] = "1000"
    val p = InternetPoraba(s) { ura }
    preveri("mesecni stevec iz prejsnje razlicice", p.mesecno(20261004) == 1000L)
    preveri("dnevni se zacne pri 0", p.dnevno(20261004) == 0L)
    preveri("dodaj vrne mesecno vsoto", p.dodaj(500, 20261004) == 1500L)
    preveri("dnevno", p.dnevno(20261004) == 500L)
    val zapisovPrej = s.zapisov
    repeat(1000) { p.dodaj(1, 20261004) }
    preveri("tisoc kosov v isti sekundi ne pomeni tisoc zapisov", s.zapisov == zapisovPrej)
    ura += InternetPoraba.SHRANI_NA_MS
    p.dodaj(1, 20261004)
    preveri("po dveh sekundah zapise", s.zapisov > zapisovPrej && s.v["cellular_bytes"] == "2501")
    preveri("nov dan: dnevni od 0, mesecni ostane", p.dodaj(10, 20261005) == 2511L && p.dnevno(20261005) == 10L)
    preveri("nov mesec: oba od 0", p.dodaj(7, 20261101) == 7L && p.dnevno(20261101) == 7L)
    p.shrani()
    val drugi = InternetPoraba(s) { ura }
    preveri("po ponovnem zagonu", drugi.mesecno(20261101) == 7L && drugi.dnevno(20261101) == 7L)
    preveri("branje za drug mesec ne vrne starega", drugi.mesecno(20261201) == 0L)
}

fun main() {
    proracun()
    okno()
    potrjevanje()
    dovoljenja()
    poraba()
    println("InternetPretokTest OK")
}
