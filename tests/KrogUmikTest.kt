package si.safeer.tv.cast

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Pregled 10. 11. 2026, najdba A: krog s sredisca (KrogNaprave.sprejmi -> zdruzi, varujUmike) z nepodpisanim novejsim
 * vnosom ne sme izbrisati umika; naprava, ki se po umiku vrne, zacne s praznim zapisom dovoljenj.
 */
private var preverb = 0

private fun preveri(opis: String, pogoj: Boolean) {
    check(pogoj) { "NAPAKA: $opis" }
    preverb++
}

private fun par(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
private fun javni(p: KeyPair): String = Base64.getEncoder().encodeToString(p.public.encoded)

private fun podpis(p: KeyPair, podatki: ByteArray): String {
    val s = Signature.getInstance("SHA256withECDSA")
    s.initSign(p.private)
    s.update(podatki)
    return Base64.getEncoder().encodeToString(s.sign())
}

/** Krog z enim vnosom, kot ga poslje sredisce; [podpisnik] = kljuc, s katerim je vnos podpisan (null = brez podpisa). */
private fun vnos(id: String, kljuc: String, dodano: Double, dodal: String, podpisnik: KeyPair? = null, platforma: String = "linux"): String {
    val z = JsonLahki.Zapis().niz("kljuc", kljuc).niz("ime", "Naprava").niz("platforma", platforma)
        .stevilo("dodano", dodano).niz("dodal", dodal)
    if (podpisnik != null) z.niz("podpis", podpis(podpisnik, KrogZaupanja.podatkiClana(id, kljuc, platforma, dodano, dodal)))
    return JsonLahki.Zapis().stevilo("v", 1.0).surovo("clani", JsonLahki.Zapis().surovo(id, z.toString()).toString())
        .surovo("umiki", "{}").toString()
}

/** Zmoznosti naprave, kot jih izracuna Dostop: zapis, ki ne velja za umaknjeno napravo. */
private fun zmoznosti(zapis: Map<String, Set<DostopPravila.Zmoznost>>, krog: KrogZaupanja, jedro: String): Set<DostopPravila.Zmoznost> =
    DostopPravila.veljavne(zapis[jedro] ?: emptySet(), krog.jeUmaknjen(jedro))

private class Oder {
    val sredisce = par()
    val naprava = par()
    val krog = KrogZaupanja()
    val jedro = KrogZaupanja.idIzKljuca(javni(naprava))
    val id = "$jedro-control"
    /** Zapis dovoljenj te naprave (Dostop): jedro -> zmoznosti. */
    val zapis = HashMap<String, Set<DostopPravila.Zmoznost>>()

    init {
        // Krog s sredisca, kot ob prvi prijavi: sprejet brez podpisov (naprava kljucev se ne pozna).
        preveri("prvi krog s sredisca sprejmemo brez podpisov", krog.zdruzi(vnos("hub", javni(sredisce), 100.0, "hub"), varujUmike = true))
        preveri("nova naprava brez podpisa pride v krog", krog.zdruzi(vnos(id, javni(naprava), 100.0, "hub"), varujUmike = true))
        zapis[jedro] = DostopPravila.VSE_ZMOZNOSTI
        // Kot KrogNaprave.krog + Dostop.pocistiUmaknjene: umik zbrise zapis dovoljenj.
        krog.obSpremembi = { for (j in DostopPravila.umaknjenaJedra(zapis.keys.toList()) { krog.jeUmaknjen(it) }) zapis.remove(j) }
    }

    fun umakni() {
        preveri("pred umikom ima naprava odprte zmoznosti", zmoznosti(zapis, krog, jedro) == DostopPravila.VSE_ZMOZNOSTI)
        preveri("umik spremeni krog", krog.umakni(id, "hub", 200.0))
        preveri("po umiku je naprava umaknjena", krog.jeUmaknjen(id) && krog.jeUmaknjen(jedro))
        preveri("po umiku zmoznosti ni", zmoznosti(zapis, krog, jedro).isEmpty())
    }

    fun ostaneUmaknjena(opis: String) {
        preveri("$opis: naprava ostane umaknjena", krog.jeUmaknjen(id) && krog.jeUmaknjen(jedro) && krog.clanZaId(id) == null && krog.clanZaId(jedro) == null)
        preveri("$opis: zmoznosti ostanejo prazne", zmoznosti(zapis, krog, jedro).isEmpty())
    }
}

private fun nepodpisanVnosNeIzbriseUmika() {
    val o = Oder()
    o.umakni()
    preveri("nepodpisan novejsi vnos z istim kljucem ne spremeni kroga", !o.krog.zdruzi(vnos(o.id, javni(o.naprava), 300.0, "hub"), varujUmike = true))
    o.ostaneUmaknjena("nepodpisan novejsi vnos")
    o.krog.zdruzi(vnos(o.id, javni(par()), 300.0, "hub"), varujUmike = true)
    o.ostaneUmaknjena("nepodpisan novejsi vnos z drugim kljucem")
    o.krog.zdruzi(vnos("${o.jedro}-os", javni(o.naprava), 50.0, "hub"), varujUmike = true)
    o.ostaneUmaknjena("nepodpisan sorodnik z istim kljucem (starejsi cas)")
    o.krog.zdruzi(vnos("${o.jedro}-os", javni(o.naprava), 300.0, "hub"), varujUmike = true)
    o.ostaneUmaknjena("nepodpisan sorodnik z istim kljucem (novejsi cas)")
    o.krog.zdruzi(vnos(o.id, javni(o.naprava), 300.0, o.id, o.naprava), varujUmike = true)
    o.ostaneUmaknjena("vnos, ki ga podpise umaknjena naprava sama")
    val tujec = par()
    o.krog.zdruzi(vnos(o.id, javni(o.naprava), 300.0, "tujec", tujec), varujUmike = true)
    o.ostaneUmaknjena("vnos s podpisom naprave, ki ni v krogu")
    o.krog.zdruzi(vnos(o.id, javni(o.naprava), 300.0, "hub", tujec), varujUmike = true)
    o.ostaneUmaknjena("vnos s podpisom napacnega kljuca v imenu sredisca")
    // Podpis sredisca za drug cas ne velja za ta vnos (podpis veze cas).
    val starPodpis = podpis(o.sredisce, KrogZaupanja.podatkiClana(o.id, javni(o.naprava), "linux", 100.0, "hub"))
    val ponovljen = JsonLahki.Zapis().stevilo("v", 1.0).surovo("clani", JsonLahki.Zapis().surovo(o.id, JsonLahki.Zapis()
        .niz("kljuc", javni(o.naprava)).niz("ime", "Naprava").niz("platforma", "linux").stevilo("dodano", 300.0).niz("dodal", "hub")
        .niz("podpis", starPodpis).toString()).toString()).toString()
    o.krog.zdruzi(ponovljen, varujUmike = true)
    o.ostaneUmaknjena("star podpis sredisca z novejsim casom")
}

private fun podpisanVnosVrneNapravoBrezDovoljenj() {
    val o = Oder()
    o.umakni()
    preveri("veljaven podpisan vnos sredisca se sprejme", o.krog.zdruzi(vnos(o.id, javni(o.naprava), 300.0, "hub", o.sredisce), varujUmike = true))
    preveri("naprava je spet clan kroga", !o.krog.jeUmaknjen(o.id) && o.krog.clanZaId(o.id) != null)
    preveri("po vrnitvi zacne s praznim zapisom dovoljenj", zmoznosti(o.zapis, o.krog, o.jedro).isEmpty() && o.jedro !in o.zapis)
}

private fun drugoOstaneKotPrej() {
    val o = Oder()
    // Naprava, ki ni umaknjena: nov nepodpisan vnos (starejsa sredisca ne podpisujejo) gre skozi kot prej.
    preveri("novejsi nepodpisan vnos neumaknjene naprave se sprejme", o.krog.zdruzi(vnos(o.id, javni(o.naprava), 150.0, "hub"), varujUmike = true))
    preveri("nepodpisana nova naprava se sprejme", o.krog.zdruzi(vnos("tablica", javni(par()), 120.0, "hub"), varujUmike = true))
    // Umik s sredisca ostane brez podpisa veljaven (umik samo jemlje).
    val umik = JsonLahki.Zapis().stevilo("v", 1.0).surovo("clani", "{}").surovo("umiki", JsonLahki.Zapis().surovo("tablica",
        JsonLahki.Zapis().stevilo("umaknjeno", 400.0).niz("umaknil", "hub").toString()).toString()).toString()
    preveri("nepodpisan umik s sredisca velja", o.krog.zdruzi(umik, varujUmike = true) && o.krog.jeUmaknjen("tablica"))
    // Vnos, starejsi od umika, naprave ne vrne in podpisa ne potrebuje.
    o.umakni()
    o.krog.zdruzi(vnos(o.id, javni(o.naprava), 150.0, "hub"), varujUmike = true)
    o.ostaneUmaknjena("vnos, starejsi od umika")

    // Sosedje (preveriPodpise): vnos z istim kljucem prej ni potreboval podpisa - umaknjene naprave ne vrne vec.
    val s = Oder()
    s.umakni()
    s.krog.zdruzi(vnos(s.id, javni(s.naprava), 300.0, "hub"), obvesti = false, preveriPodpise = true)
    s.ostaneUmaknjena("sosed z nepodpisanim novejsim vnosom")
    preveri("sosed s podpisanim vnosom sredisca napravo vrne",
        s.krog.zdruzi(vnos(s.id, javni(s.naprava), 300.0, "hub", s.sredisce), obvesti = false, preveriPodpise = true) && !s.krog.jeUmaknjen(s.id))

    // Umaknjen je samo star id naprave, njen kljuc ima se veljaven vnos: nov sorodnik nicesar ne vrne in gre skozi.
    val d = Oder()
    d.krog.zdruzi(vnos("stari-id", javni(d.naprava), 90.0, "hub"), varujUmike = true)
    d.krog.umakni("stari-id", "hub", 200.0)
    preveri("sorodnik naprave z veljavnim vnosom se sprejme brez podpisa",
        d.krog.zdruzi(vnos("${d.jedro}-os", javni(d.naprava), 250.0, "hub"), varujUmike = true) && d.krog.clan("${d.jedro}-os") != null)
    preveri("umaknjeni stari id ostane umaknjen", d.krog.jeUmaknjen("stari-id"))
    // Naprava svojega umaknjenega id-ja ne vrne sama s podpisom svojega drugega (veljavnega) id-ja.
    d.krog.zdruzi(vnos("stari-id", javni(d.naprava), 300.0, "${d.jedro}-os", d.naprava), varujUmike = true)
    preveri("vnos s podpisom istega kljuca umaknjenega id-ja ne vrne", d.krog.jeUmaknjen("stari-id"))

    // Podpisnik (novo sredisce) in vrnjena naprava v ISTEM krogu: vrstni red vnosov ne odloca.
    val v = Oder()
    v.umakni()
    val novo = par()
    val idNovega = "zz-novo-sredisce"
    fun zapisVnosa(id: String, kljuc: String, dodano: Double, dodal: String, podpisnik: java.security.KeyPair?): String {
        val z = JsonLahki.Zapis().niz("kljuc", kljuc).niz("ime", "Naprava").niz("platforma", "linux").stevilo("dodano", dodano).niz("dodal", dodal)
        if (podpisnik != null) z.niz("podpis", podpis(podpisnik, KrogZaupanja.podatkiClana(id, kljuc, "linux", dodano, dodal)))
        return z.toString()
    }
    val skupaj = JsonLahki.Zapis().stevilo("v", 1.0).surovo("clani", JsonLahki.Zapis()
        .surovo(v.id, zapisVnosa(v.id, javni(v.naprava), 300.0, idNovega, novo))
        .surovo(idNovega, zapisVnosa(idNovega, javni(novo), 250.0, "hub", null)).toString()).toString()
    preveri("vrnitev s podpisom sredisca iz istega kroga se sprejme", v.krog.zdruzi(skupaj, varujUmike = true) && !v.krog.jeUmaknjen(v.id))

    // Shranjen krog ob zagonu (brez varovala) se nalozi kot prej: vnos, novejsi od umika, je veljaven.
    val shranjen = KrogZaupanja()
    shranjen.zdruzi(vnos("a", javni(par()), 100.0, "hub"), obvesti = false)
    shranjen.umakni("a", "hub", 200.0)
    preveri("brez varovala (shramba) novejsi vnos napravo vrne", shranjen.zdruzi(vnos("a", javni(par()), 300.0, "hub"), obvesti = false) && !shranjen.jeUmaknjen("a"))
}

private fun krogSamoOdSredisca(izvorna: String) {
    preveri("trust.update brez posiljatelja je od sredisca", KrogZaupanja.krogOdSredisca(null) && KrogZaupanja.krogOdSredisca(""))
    preveri("trust.update s posiljateljem (mesh.route) ni od sredisca", !KrogZaupanja.krogOdSredisca("n-0123456789abcdef-control"))
    preveri("umaknjena jedra: samo umaknjena", DostopPravila.umaknjenaJedra(listOf("a", "b", "c")) { it == "b" } == setOf("b"))
    if (izvorna.isEmpty()) return
    fun vir(pot: String) = java.io.File(izvorna, pot).readText()
    preveri("KrogNaprave.sprejmi varuje umike", vir("si/safeer/tv/cast/KrogNaprave.kt").contains("zdruzi(json, varujUmike = true)"))
    preveri("Dostop ob spremembi kroga zbrise zapis umaknjenih",
        vir("si/safeer/tv/cast/Dostop.kt").contains("KrogNaprave.obSpremembiKroga(\"dostop\") { c -> pocistiUmaknjene(c) }"))
    for (pot in listOf("si/safeer/tv/cast/CastReceiverService.kt", "si/safeer/tv/os/LinkOdjemalec.kt"))
        preveri("$pot: trust.update samo od sredisca", vir(pot).substringAfter("\"trust.update\"").substringBefore("KrogNaprave.sprejmi").contains("krogOdSredisca("))
}

fun main(args: Array<String>) {
    nepodpisanVnosNeIzbriseUmika()
    podpisanVnosVrneNapravoBrezDovoljenj()
    drugoOstaneKotPrej()
    krogSamoOdSredisca(args.firstOrNull().orEmpty())
    println("KrogUmikTest: OK ($preverb preverb)")
}
